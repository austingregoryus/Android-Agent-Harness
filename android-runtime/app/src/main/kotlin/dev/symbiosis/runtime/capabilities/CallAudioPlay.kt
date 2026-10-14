package dev.symbiosis.runtime.capabilities

import android.content.Context
import android.util.Base64
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.sessions.SessionManager
import dev.symbiosis.runtime.telephony.Conversations
import dev.symbiosis.runtime.telephony.TelephonyAudio
import dev.symbiosis.runtime.telephony.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Play caller-supplied audio into the uplink of a call the caller owns.
 *
 * This decouples speech synthesis from the phone. `telephony.speak` uses the on-device Android engine, which is
 * fast and free but audibly robotic; with this the agent can synthesize anywhere — a neural TTS on a GPU box, or
 * a hosted voice — and hand the phone finished audio. The phone stays a pure audio sink, which is the right
 * split: it owns the call, not the voice.
 *
 * Accepts WAV (any sample rate, mono or stereo, 16-bit) or raw 16-bit PCM, and resamples to the uplink rate.
 * WAV is preferred because every TTS API can emit it and it is self-describing; MP3 would need a decoder and
 * buys nothing here since the audio is about to be crushed into a telephony codec anyway.
 */
object CallAudioPlay {

  /** Guard against a caller trying to push an unreasonable buffer through a base64 JSON field. */
  private const val MAX_BYTES = 12 * 1024 * 1024

  fun capability(context: Context, sessions: SessionManager): Capability = Capability(
    name = "telephony.play_audio",
    description = "Play supplied audio into a call you own so the far party hears it. Use this to speak with an external/neural TTS voice instead of the on-device engine. Accepts base64 WAV or raw 16-bit PCM.",
    params = listOf(
      Param("session_id", "string", "The call session (from telephony.place_call)", required = true),
      Param("audio_base64", "string", "Base64-encoded WAV file, or raw 16-bit little-endian PCM", required = true),
      Param("format", "string", "wav (default) or pcm", enum = listOf("wav", "pcm")),
      Param("sample_rate", "integer", "Sample rate of raw PCM, 8000..48000 (default 16000; ignored for wav, which carries its own)"),
      Param("gain", "number", "0..1 output level (default 1.0)"),
    ),
    actionClass = ActionClass.COMMUNICATION, tier = 1,
  ) { args, ctx ->
    val access = SessionAccess.ownedActiveCall(sessions, args["session_id"]?.toString(), ctx)
    if (access is SessionAccess.Access.Denied) return@Capability access.result
    val sessionId = (access as SessionAccess.Access.Ok).session.sessionId
    val b64 = args["audio_base64"]?.toString()?.takeIf { it.isNotBlank() }
      ?: return@Capability CapabilityResult.fail("invalid_params", "audio_base64 is required")
    val format = (args["format"]?.toString() ?: "wav").lowercase()
    // Bounded: sample_rate 1 asked the resampler for 16 000 times the input (a multi-GB allocation), and a few
    // hundred Hz meant hours of uplink audio.
    val rawRate = args["sample_rate"]
    val srcRate = when (rawRate) {
      null -> TelephonyAudio.RATE
      is Number -> rawRate.toDouble().takeIf { it == Math.floor(it) && it.toLong() in Wav.RATES.first..Wav.RATES.last }?.toInt()
      else -> null
    } ?: return@Capability CapabilityResult.fail("invalid_params", "sample_rate must be an integer between ${Wav.RATES.first} and ${Wav.RATES.last}")
    val gain = ((args["gain"] as? Number)?.toDouble() ?: 1.0).coerceIn(0.0, 1.0)
    if (!TelephonyAudio.hasPermission(context)) {
      return@Capability CapabilityResult.fail("capability.unavailable", "MODIFY_AUDIO_ROUTING not granted")
    }

    withContext(Dispatchers.IO) {
      val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
        ?: return@withContext CapabilityResult.fail("invalid_params", "audio_base64 is not valid base64")
      if (bytes.size > MAX_BYTES) {
        return@withContext CapabilityResult.fail("invalid_params", "audio too large (${bytes.size} bytes, max $MAX_BYTES)")
      }

      val pcm: ShortArray
      val sourceRate: Int
      if (format == "pcm") {
        if (bytes.size / 2 > Wav.MAX_SECONDS.toLong() * srcRate) {
          return@withContext CapabilityResult.fail("invalid_params", "audio longer than ${Wav.MAX_SECONDS} s")
        }
        val mono = ShortArray(bytes.size / 2)
        for (i in mono.indices) {
          mono[i] = ((bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
        sourceRate = srcRate
        pcm = if (srcRate == TelephonyAudio.RATE) mono else Wav.resample(mono, srcRate, TelephonyAudio.RATE)
      } else {
        // Wav.decodeMono16 handles the chunk walk, channel downmix and resample.
        val tmp = File(context.cacheDir, "play_${System.nanoTime()}.wav")
        try {
          tmp.writeBytes(bytes)
          val decoded = Wav.decodeMono16(tmp, TelephonyAudio.RATE)
            ?: return@withContext CapabilityResult.fail("invalid_params", "could not decode WAV (need 16-bit PCM at ${Wav.RATES.first}..${Wav.RATES.last} Hz, at most ${Wav.MAX_SECONDS} s)")
          pcm = decoded.pcm
          sourceRate = decoded.sourceRate
        } finally {
          runCatching { tmp.delete() }
        }
      }
      if (pcm.isEmpty()) return@withContext CapabilityResult.fail("invalid_params", "audio decoded to zero samples")
      if (gain < 1.0) for (i in pcm.indices) pcm[i] = (pcm[i] * gain).toInt().toShort()

      // Go through the conversation when one is open on this call: it owns the uplink (no policy churn per
      // utterance) AND the speaking state the capture loop checks for barge-in. Playing on the raw session,
      // as this used to, meant the far party could never interrupt a neural-voice utterance — the loop never
      // saw us as speaking — while the phone's own TTS via telephony.speak could be interrupted fine.
      val conv = Conversations.get(sessionId)?.conversation
      val result = if (conv != null) {
        conv.speak(pcm) ?: return@withContext CapabilityResult.fail("capability.failed", "uplink unavailable")
      } else {
        // Serialised one-shot uplink: a concurrent speak/play_audio waits instead of overlapping.
        val played = TelephonyAudio.playOneShot(context, pcm)
        played.getOrNull() ?: return@withContext CapabilityResult.fail(
          "capability.failed", played.exceptionOrNull()?.message ?: "could not open the call uplink",
        )
      }

      CapabilityResult.ok(
        mapOf(
          "session_id" to sessionId, "played" to true, "source_rate" to sourceRate, "samples" to pcm.size,
          "duration_ms" to (pcm.size * 1000L / TelephonyAudio.RATE),
          "played_ms" to result.playedMs, "drained" to result.drained,
          "interrupted" to result.interrupted,
        ),
      )
    }
  }
}
