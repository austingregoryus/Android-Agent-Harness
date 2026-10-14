package dev.symbiosis.runtime.capabilities

import android.content.Context
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.telephony.StreamingAsr
import dev.symbiosis.runtime.telephony.TelephonyAudio
import dev.symbiosis.runtime.telephony.Tts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Closed-loop check of the recognition path with no phone call involved: synthesize a known phrase, push the
 * PCM through [StreamingAsr] exactly as the capture loop would, and report what came back.
 *
 * This exists because the risky, undocumented part of conversation mode is feeding a [android.speech.SpeechRecognizer]
 * from our own audio instead of the microphone (`EXTRA_AUDIO_SOURCE`, Android 13+). Everything else can be reasoned
 * about; this either round-trips or it does not. Debug builds only.
 */
object AsrSelfTest {

  fun capability(context: Context): Capability = Capability(
    name = "debug.asr_selftest",
    description = "RESEARCH (debug). Synthesize a phrase, feed it through the streaming recognizer, and return the transcript. Proves the ASR pipe works without needing a call.",
    params = listOf(
      Param("text", "string", "Phrase to synthesize and recognise (default: a pangram)"),
      Param("timeout_ms", "integer", "How long to wait for a final result (default 15000)"),
      Param("realtime", "boolean", "Feed at 1x speed like a real call instead of as fast as possible (default true)"),
      Param("second_text", "string", "If set, a second turn is started on the same recognizer the instant the first is finished — BEFORE the first final arrives — and both must come back (the resumed-turn race)"),
    ),
    actionClass = ActionClass.LOW_RISK, tier = 1,
  ) { args, _ ->
    val text = args["text"]?.toString()?.takeIf { it.isNotBlank() } ?: "the quick brown fox jumps over the lazy dog"
    val second = args["second_text"]?.toString()?.takeIf { it.isNotBlank() }
    val timeout = (args["timeout_ms"] as? Number)?.toLong() ?: 15_000L
    val realtime = args["realtime"] as? Boolean ?: true

    withContext(Dispatchers.IO) {
      val asr = StreamingAsr(context)
      Tts.warmUp(context) // the engine is shut down when the last conversation ends
      // Prefix the lead-in the capture loop's pre-roll provides on a call: the recognizer clips a phrase that
      // starts on its very first sample ("does seven" came back as "The 7" without it).
      val lead = ShortArray(TelephonyAudio.RATE * 3 / 10)
      val speech = Tts.synthesize(context, text)
        ?: return@withContext CapabilityResult.fail("capability.failed", "TTS synthesis failed")
      val speech2 = second?.let { Tts.synthesize(context, it) }
      val pcm1 = lead + speech.pcm
      val pcm2 = speech2?.let { lead + it.pcm }

      val started = System.currentTimeMillis()

      class Turn(val spoke: String) {
        val done = CountDownLatch(1)
        @Volatile var finalText: String? = null
        val partials = mutableListOf<String>()
        @Volatile var firstPartialMs: Long? = null
        @Volatile var finalAtMs: Long? = null
        fun start() = asr.start(
          onPartial = { p ->
            if (firstPartialMs == null) firstPartialMs = System.currentTimeMillis() - started
            synchronized(partials) { if (partials.lastOrNull() != p) partials += p }
          },
          onFinal = { t -> finalText = t; finalAtMs = System.currentTimeMillis() - started; done.countDown() },
        )
        fun report(): Map<String, Any?> {
          val heard = finalText?.lowercase()?.filter { it.isLetterOrDigit() || it.isWhitespace() }?.trim()
          val expected = spoke.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }.trim()
          return mapOf(
            "spoke" to spoke, "heard" to finalText, "match" to (heard != null && heard == expected),
            "partials" to synchronized(partials) { partials.toList() },
            "first_partial_ms" to firstPartialMs, "final_at_ms" to finalAtMs,
          )
        }
      }

      // Push it in 20 ms frames, the same size the capture loop uses.
      fun feed(pcm: ShortArray) {
        val frame = TelephonyAudio.RATE / 50
        var i = 0
        while (i < pcm.size) {
          val n = minOf(frame, pcm.size - i)
          asr.feed(pcm.copyOfRange(i, i + n), n)
          i += n
          if (realtime) Thread.sleep(20)
        }
      }

      val t1 = Turn(text)
      t1.start()
      feed(pcm1)
      asr.finish()
      var t2: Turn? = null
      if (pcm2 != null && second != null) {
        // No waiting: the far party resumed while turn 1's final was still on its way.
        t2 = Turn(second)
        t2.start()
        feed(pcm2)
        asr.finish()
      }
      val completed = t1.done.await(timeout, TimeUnit.MILLISECONDS)
      val completed2 = t2?.done?.await(timeout, TimeUnit.MILLISECONDS)
      if (!completed || completed2 == false) asr.cancel()

      val out = HashMap<String, Any?>(t1.report())
      out["on_device_asr"] = asr.onDeviceAvailable()
      out["completed"] = completed
      out["total_ms"] = System.currentTimeMillis() - started
      out["audio_ms"] = speech.pcm.size * 1000L / TelephonyAudio.RATE
      out["tts_engine"] = speech.engineName
      if (t2 != null) {
        out["second"] = t2.report() + mapOf("completed" to completed2)
        out["both_transcribed"] = (t1.finalText != null && t2.finalText != null)
      }
      CapabilityResult.ok(out)
    }
  }
}
