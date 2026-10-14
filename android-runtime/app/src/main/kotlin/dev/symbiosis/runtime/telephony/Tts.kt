package dev.symbiosis.runtime.telephony

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device speech synthesis, rendered to PCM at [TelephonyAudio.RATE] so it can be pushed straight into the
 * call uplink.
 *
 * The engine is kept warm between utterances: constructing a [TextToSpeech] costs a service bind plus voice
 * load, which is roughly a second — unacceptable per turn in a conversation. [warmUp] pays that once when a
 * call starts, and [shutdown] releases it when the call ends.
 */
object Tts {

  @Volatile private var engine: TextToSpeech? = null
  @Volatile private var ready = false
  private val lock = Any()
  /**
   * One synthesis at a time. The progress listener is engine-wide, so two concurrent `telephony.speak` calls used
   * to replace each other's listener: one waited the full 30 s timeout for a callback that went to the other, and
   * pitch/rate set for one applied to both. Separate from [lock] so warmUp/shutdown are not stuck behind a synth.
   */
  private val synthLock = Any()

  data class Speech(val pcm: ShortArray, val engineName: String?, val nativeRate: Int, val synthMs: Long)

  /** Bind the engine ahead of time so the first reply is not a second late. Safe to call repeatedly. */
  fun warmUp(context: Context): Boolean = synchronized(lock) {
    if (ready && engine != null) return true
    val latch = CountDownLatch(1)
    var ok = false
    val t = TextToSpeech(context.applicationContext) { status ->
      ok = status == TextToSpeech.SUCCESS
      latch.countDown()
    }
    if (!latch.await(10, TimeUnit.SECONDS) || !ok) {
      runCatching { t.shutdown() }
      return false
    }
    runCatching { t.language = Locale.US }
    engine = t
    ready = true
    true
  }

  fun shutdown() = synchronized(lock) {
    runCatching { engine?.stop() }
    runCatching { engine?.shutdown() }
    engine = null
    ready = false
  }

  /**
   * Synthesize [text] to mono PCM at [TelephonyAudio.RATE]. Returns null if the engine is unavailable or the
   * utterance produced nothing usable.
   */
  fun synthesize(context: Context, text: String, pitch: Float = 1f, rate: Float = 1f): Speech? = synchronized(synthLock) {
    synthesizeLocked(context, text, pitch, rate)
  }

  private fun synthesizeLocked(context: Context, text: String, pitch: Float, rate: Float): Speech? {
    if (!warmUp(context)) return null
    val t = engine ?: return null
    val started = System.currentTimeMillis()
    val out = File(context.cacheDir, "tts_${System.nanoTime()}.wav")
    try {
      synchronized(lock) {
        runCatching { t.setPitch(pitch) }
        runCatching { t.setSpeechRate(rate) }
      }
      val done = CountDownLatch(1)
      var failed = false
      t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) = done.countDown()
        @Deprecated("required override") override fun onError(utteranceId: String?) { failed = true; done.countDown() }
        override fun onError(utteranceId: String?, errorCode: Int) { failed = true; done.countDown() }
      })
      if (t.synthesizeToFile(text, android.os.Bundle(), out, "symbiosis-${System.nanoTime()}") != TextToSpeech.SUCCESS) return null
      if (!done.await(30, TimeUnit.SECONDS) || failed || !out.exists() || out.length() < 64) return null
      val wav = Wav.decodeMono16(out, TelephonyAudio.RATE) ?: return null
      return Speech(wav.pcm, t.defaultEngine, wav.sourceRate, System.currentTimeMillis() - started)
    } finally {
      runCatching { out.delete() }
    }
  }
}

/** Minimal RIFF/WAVE reader: enough for what the TTS engine writes, and safe on what a caller sends (play_audio). */
object Wav {
  data class Decoded(val pcm: ShortArray, val sourceRate: Int)

  /** Sample rates accepted from a WAV header or a caller (raw PCM): telephony narrowband through 48 kHz. */
  val RATES = 8_000..48_000

  /**
   * Longest audio accepted, after resampling. Bounds memory and how long one call can hold the uplink: a header
   * claiming 1 Hz turned a small upload into an hours-long, gigabyte-sized resample.
   */
  const val MAX_SECONDS = 300

  /** Decode to mono 16-bit and resample to [targetRate]. */
  fun decodeMono16(f: File, targetRate: Int): Decoded? = runCatching { decodeMono16(f.readBytes(), targetRate) }.getOrNull()

  /**
   * [b] as mono 16-bit at [targetRate], or null if it is not a 16-bit PCM WAV with a rate in [RATES] and at most
   * [MAX_SECONDS] long. Chunk sizes are unsigned 32-bit and are checked against the bytes actually present: read
   * as a signed Int, a size of 0xFFFFFFF8 stepped the walk back to where it started and looped forever.
   */
  fun decodeMono16(b: ByteArray, targetRate: Int): Decoded? = runCatching {
    if (b.size < 44) return null
    if (String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
    var i = 12L
    var rate = -1; var channels = 0; var bits = 0
    var dataOff = -1; var dataLen = 0
    while (i + 8 <= b.size) {
      val at = i.toInt()
      val id = String(b, at, 4, Charsets.US_ASCII)
      val size = le32u(b, at + 4)
      val remaining = b.size - (i + 8)
      when (id) {
        "fmt " -> {
          if (size < 16 || size > remaining) return null
          channels = le16(b, at + 10); rate = le32(b, at + 12); bits = le16(b, at + 22)
        }
        // A streaming writer may leave the data size at 0xFFFFFFFF; what is present is what plays.
        "data" -> { dataOff = at + 8; dataLen = minOf(size, remaining).toInt() }
        else -> if (size > remaining) return null
      }
      if (dataOff >= 0) break
      i += 8 + size + (size and 1) // Long: always advances, and past the end ends the walk
    }
    if (dataOff < 0 || bits != 16 || channels < 1 || rate !in RATES) return null
    val frames = dataLen / 2 / channels
    if (frames <= 0) return null
    if (frames.toLong() > MAX_SECONDS.toLong() * rate) return null
    val mono = ShortArray(frames)
    for (n in 0 until frames) { // channel 0
      val p = dataOff + n * 2 * channels
      mono[n] = ((b[p].toInt() and 0xff) or (b[p + 1].toInt() shl 8)).toShort()
    }
    Decoded(if (rate == targetRate) mono else resample(mono, rate, targetRate), rate)
  }.getOrNull()

  /** Linear interpolation — adequate for speech going into an 8 kHz-ish telephony path. */
  fun resample(src: ShortArray, from: Int, to: Int): ShortArray {
    if (from <= 0 || to <= 0 || src.isEmpty() || from == to) return src
    val out = ShortArray((src.size.toLong() * to / from).toInt().coerceAtLeast(1))
    for (i in out.indices) {
      val x = i.toDouble() * from / to
      val a = x.toInt().coerceIn(0, src.size - 1)
      val b = (a + 1).coerceAtMost(src.size - 1)
      val t = x - a
      out[i] = (src[a] * (1 - t) + src[b] * t).toInt().toShort()
    }
    return out
  }

  fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
  /** Unsigned little-endian 32-bit, for RIFF chunk sizes. */
  fun le32u(b: ByteArray, i: Int): Long = le32(b, i).toLong() and 0xffff_ffffL
  fun le32(b: ByteArray, i: Int) =
    (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
      ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
}
