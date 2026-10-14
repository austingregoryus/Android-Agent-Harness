package dev.symbiosis.runtime.telephony

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The uplink render path: play PCM so the far party on a carrier call hears it.
 *
 * Mechanism (decompiled from the Google Dialer, which uses it for Call Screen / Hold-for-Me): register an
 * [android.media.audiopolicy.AudioPolicy] holding an `AudioMix` with `ROUTE_FLAG_RENDER` whose device is the
 * `TYPE_TELEPHONY` output. `AudioTrack.setPreferredDevice()` is NOT sufficient — it is only a hint and the
 * policy manager ignores it for the telephony sink. Registering the policy needs the privileged
 * `MODIFY_AUDIO_ROUTING` permission, which a privapp allowlist grants (no platform signature required).
 *
 * The audiopolicy classes are hidden `@SystemApi`, hence reflection throughout — the same reason the Google
 * Dialer ships an `AudioSystemApiUtil` full of `getClassWithName`/`invokeMethod` helpers.
 *
 * A [Session] holds the policy open for a whole call instead of registering per utterance: registration is not
 * free, and churning it between sentences adds latency and risks dropping the first syllable.
 */
object TelephonyAudio {

  const val TYPE_TELEPHONY = 18
  const val RATE = 16_000
  private const val ROUTE_FLAG_RENDER = 0x1
  private const val RULE_MATCH_ATTRIBUTE_USAGE = 0x1
  const val PERM_MODIFY_AUDIO_ROUTING = "android.permission.MODIFY_AUDIO_ROUTING"
  const val PERM_MODIFY_PHONE_STATE = "android.permission.MODIFY_PHONE_STATE"
  private const val TAG = "TelephonyAudio"

  fun hasPermission(context: Context) =
    context.checkSelfPermission(PERM_MODIFY_AUDIO_ROUTING) == PackageManager.PERMISSION_GRANTED

  fun sink(am: AudioManager): AudioDeviceInfo? =
    am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == TYPE_TELEPHONY }

  val attributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

  val format: AudioFormat = AudioFormat.Builder()
    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
    .setSampleRate(RATE)
    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
    .build()

  /**
   * [playedMs] is wall-clock time between play() and the end/interrupt. Prefer it over [played]:
   * `playbackHeadPosition` is unreliable for a policy-rendered track (it can read 0 mid-interrupt and lags
   * the real tail), so the frame count is diagnostic only while the elapsed time is always meaningful.
   */
  data class PlayResult(
    val played: Int, val total: Int, val interrupted: Boolean, val drained: Boolean, val playedMs: Long,
  )

  /**
   * Serialises the one-shot path (`telephony.speak` / `play_audio` with no conversation open): each call registers
   * its own policy for the same TYPE_TELEPHONY device, and two overlapping ones would both render into the uplink.
   */
  private val oneShotLock = Any()

  /** Open a policy for one utterance, play it, release it — one caller at a time. */
  fun playOneShot(context: Context, pcm: ShortArray): Result<PlayResult> = synchronized(oneShotLock) {
    val am = context.getSystemService(AudioManager::class.java)
    Session.open(context, am).mapCatching { s -> s.use { it.play(pcm) } }
  }

  /**
   * An open uplink: the audio policy stays registered for the lifetime of this object. [play] is serialised per
   * session, so concurrent callers queue rather than overlap.
   */
  class Session private constructor(
    context: Context,
    private val am: AudioManager,
    private val policy: Any,
    val sinkName: String,
  ) : AutoCloseable {

    private val cancel = AtomicBoolean(false)
    private val usesInCallMusic = context.checkSelfPermission(PERM_MODIFY_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    init {
      // Which uplink path was chosen is decided by a permission check, not a config flag, so a device that
      // silently gains or loses MODIFY_PHONE_STATE (e.g. the Pixel) would otherwise switch paths unnoticed.
      android.util.Log.i(TAG, "uplink path: " + if (usesInCallMusic) "policy mix + setPreferredDevice/INCALL_MUSIC (MODIFY_PHONE_STATE granted)" else "policy mix only")
    }

    @Volatile private var track: AudioTrack? = null
    @Volatile var isPlaying: Boolean = false
      private set

    /**
     * Play mono 16 kHz PCM into the uplink. Blocks until played, interrupted, or the deadline passes.
     *
     * Written in ~100 ms chunks rather than one big write: sizing the buffer at the whole clip and writing it
     * in a single call makes `write()` return as soon as the frames are queued, the mixer starves after the
     * first period, and playback stalls ~220 ms in (this bug cut speech off after the first word).
     * Chunked WRITE_BLOCKING paces the writer against actual playback and makes barge-in responsive.
     */
    fun play(pcm: ShortArray, muteMic: Boolean = true): PlayResult = synchronized(playLock) { playLocked(pcm, muteMic) }

    /**
     * One utterance at a time per uplink. [track], [cancel] and [headAtStop] are per-utterance state in fields, so
     * two concurrent plays used to clobber each other (one's stop() flushed the other's track, both wrote into the
     * mix). Callers above (the conversation, the one-shot path) serialise too; this makes the class itself safe.
     */
    private val playLock = Any()

    private fun playLocked(pcm: ShortArray, muteMic: Boolean): PlayResult {
      cancel.set(false)
      headAtStop = -1
      val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        .coerceAtLeast(2 * RATE / 10)
      var wrote = 0
      var head = 0
      var interrupted = false
      var drained = false
      var playedMs = 0L
      var startedAtMs = System.currentTimeMillis()
      val t = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
        .setBufferSizeInBytes(minBuf * 4).build()
      // Qualcomm HALs (OnePlus 7 Pro): the policy mix alone plays through voice_tx -> afe-proxy and the far party
      // hears nothing. The uplink there is the INCALL_MUSIC output, which the policy manager only picks when the
      // track itself requests the TYPE_TELEPHONY device - and only opens for a holder of MODIFY_PHONE_STATE
      // ("getOutputForAttr() permission denied: modify phone state not allowed"). So request it only with that
      // permission: without it the request would get the track refused, and the Pixel (no MODIFY_PHONE_STATE)
      // stays on the policy-mix path it has always used.
      if (usesInCallMusic) sink(am)?.let { runCatching { t.setPreferredDevice(it) } }
      track = t
      isPlaying = true
      if (muteMic) runCatching { am.isMicrophoneMute = true }
      try {
        t.play()
        startedAtMs = System.currentTimeMillis()
        val chunk = RATE / 10 // 100 ms — also the barge-in reaction granularity
        while (wrote < pcm.size) {
          if (cancel.get()) { interrupted = true; break }
          val n = minOf(chunk, pcm.size - wrote)
          val w = t.write(pcm, wrote, n, AudioTrack.WRITE_BLOCKING)
          if (w <= 0) break
          wrote += w
        }
        if (interrupted) {
          // stop() already sampled the head before it flushed; flush() zeroes it, so prefer that reading.
          head = if (headAtStop >= 0) headAtStop else t.playbackHeadPosition
          runCatching { t.pause(); t.flush() }
        } else {
          // write() only queues; wait for the head to actually reach the end before returning.
          // playbackHeadPosition lags: the frames still inside the track's buffer are not reflected when we
          // sample it, and the residual varies per utterance. So don't chase the head — wait out the clip's
          // own duration from the moment playback started, which is what actually guarantees it was heard.
          val playMs = pcm.size * 1000L / RATE
          val until = startedAtMs + playMs + 150 // +tail so the last syllable clears the mixer
          while (System.currentTimeMillis() < until) {
            if (cancel.get()) {
              interrupted = true
              head = if (headAtStop >= 0) headAtStop else t.playbackHeadPosition
              runCatching { t.pause(); t.flush() }
              break
            }
            Thread.sleep(20)
          }
          if (!interrupted) head = t.playbackHeadPosition
          drained = !interrupted && wrote >= pcm.size
        }
      } finally {
        playedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0)
        isPlaying = false
        runCatching { t.stop() }; runCatching { t.release() }
        track = null
        if (muteMic) runCatching { am.isMicrophoneMute = false }
      }
      return PlayResult(head, pcm.size, interrupted, drained, playedMs)
    }

    /** How far playback had got when [stop] fired; -1 when not interrupted. */
    @Volatile private var headAtStop = -1

    /** Barge-in: stop the current utterance as soon as the next chunk boundary is reached (~100 ms). */
    fun stop() {
      cancel.set(true)
      // Sample the head before flushing — flush() zeroes it, and this runs on the caller's thread, so the
      // playing thread would otherwise only ever observe 0 and report that nothing was heard.
      runCatching { headAtStop = track?.playbackHeadPosition ?: -1 }
      runCatching { track?.pause(); track?.flush() }
    }

    override fun close() {
      stop()
      runCatching {
        AudioManager::class.java
          .getMethod("unregisterAudioPolicy", Class.forName("android.media.audiopolicy.AudioPolicy"))
          .invoke(am, policy)
      }
    }

    companion object {
      /** Register a policy that renders our voice-communication audio at the telephony device. */
      fun open(context: Context, am: AudioManager): Result<Session> = runCatching {
        val sink = sink(am) ?: error("no TYPE_TELEPHONY output device")

        val ruleB = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val rb = ruleB.getConstructor().newInstance()
        ruleB.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
          .invoke(rb, attributes, RULE_MATCH_ATTRIBUTE_USAGE)
        val rule = ruleB.getMethod("build").invoke(rb)

        val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
        val mixB = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val mb = mixB.getConstructor(Class.forName("android.media.audiopolicy.AudioMixingRule")).newInstance(rule)
        mixB.getMethod("setFormat", AudioFormat::class.java).invoke(mb, format)
        mixB.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mb, ROUTE_FLAG_RENDER)
        mixB.getMethod("setDevice", AudioDeviceInfo::class.java).invoke(mb, sink)
        val mix = mixB.getMethod("build").invoke(mb)

        val polB = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val pb = polB.getConstructor(Context::class.java).newInstance(context)
        polB.getMethod("addMix", mixCls).invoke(pb, mix)
        val policy = polB.getMethod("build").invoke(pb)

        val code = AudioManager::class.java
          .getMethod("registerAudioPolicy", Class.forName("android.media.audiopolicy.AudioPolicy"))
          .invoke(am, policy) as Int
        if (code != 0) error("registerAudioPolicy failed with $code")
        Session(context, am, policy,"${sink.productName} type=${sink.type}")
      }
    }
  }
}
