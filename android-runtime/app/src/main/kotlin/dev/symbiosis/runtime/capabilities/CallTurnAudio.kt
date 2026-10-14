package dev.symbiosis.runtime.capabilities

import android.util.Base64
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.sessions.SessionManager
import dev.symbiosis.runtime.telephony.Conversations
import dev.symbiosis.runtime.telephony.TelephonyAudio
import java.io.ByteArrayOutputStream

/**
 * The audio of a far-party turn that just closed, for an agent that wants to run its own recognizer.
 *
 * The phone's on-device recognizer is fast (its final lands ~150 ms after the turn closes) but not accurate on
 * carrier audio; a Whisper large-v3 on the LAN is accurate but batch. The driver uses both: the on-device
 * transcript drives the reply, this audio goes to Whisper, and Whisper's transcript confirms or corrects it.
 * A pull rather than an event because events are persisted, and 85 KB of base64 per turn does not belong in
 * the event log. The conversation keeps the last few closed turns; `call.listening {listening:false}`
 * carries the `turn_id` and `audio_ms` to ask for.
 */
object CallTurnAudio {

  fun capability(sessions: SessionManager): Capability = Capability(
    name = "telephony.turn_audio",
    description = "Fetch the audio (16 kHz mono WAV, base64) of a far-party turn that conversation mode just closed on a call you own, by the turn_id from the call.listening close event (default: the latest). For running your own speech recognizer on it.",
    params = listOf(
      Param("session_id", "string", "The call session", required = true),
      Param("turn_id", "integer", "turn_id from the call.listening close event (default: the most recent closed turn)"),
    ),
    actionClass = ActionClass.LOW_RISK, tier = 1,
  ) { args, ctx ->
    val access = SessionAccess.ownedActiveCall(sessions, args["session_id"]?.toString(), ctx)
    if (access is SessionAccess.Access.Denied) return@Capability access.result
    val sessionId = (access as SessionAccess.Access.Ok).session.sessionId
    val conv = Conversations.get(sessionId)?.conversation
      ?: return@Capability CapabilityResult.fail("capability.unavailable", "no conversation on this call")
    val id = (args["turn_id"] as? Number)?.toInt()
    val rec = conv.turnAudio(id)
      ?: return@Capability CapabilityResult.fail("not_found", "no audio held for turn ${id ?: "(latest)"}")
    val pcm = rec.pcm
    val bytes = ByteArrayOutputStream(44 + pcm.size * 2)
    fun le32(v: Int) { bytes.write(v and 0xff); bytes.write((v shr 8) and 0xff); bytes.write((v shr 16) and 0xff); bytes.write((v shr 24) and 0xff) }
    fun le16(v: Int) { bytes.write(v and 0xff); bytes.write((v shr 8) and 0xff) }
    val rate = TelephonyAudio.RATE
    bytes.write("RIFF".toByteArray()); le32(36 + pcm.size * 2); bytes.write("WAVE".toByteArray())
    bytes.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
    bytes.write("data".toByteArray()); le32(pcm.size * 2)
    val data = ByteArray(pcm.size * 2)
    for (i in pcm.indices) { val v = pcm[i].toInt(); data[i * 2] = (v and 0xff).toByte(); data[i * 2 + 1] = ((v shr 8) and 0xff).toByte() }
    bytes.write(data)
    CapabilityResult.ok(
      mapOf(
        "session_id" to sessionId, "turn_id" to rec.id, "closed_at" to rec.closedAt,
        "duration_ms" to (pcm.size * 1000L / rate), "sample_rate" to rate, "format" to "wav",
        "audio_base64" to Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP),
      ),
    )
  }
}
