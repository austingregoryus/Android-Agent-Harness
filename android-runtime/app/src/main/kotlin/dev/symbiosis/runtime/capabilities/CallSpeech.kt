package dev.symbiosis.runtime.capabilities

import android.content.Context
import dev.symbiosis.runtime.events.EventBroker
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.sessions.SessionManager
import dev.symbiosis.runtime.telephony.CallConversation
import dev.symbiosis.runtime.telephony.Conversations
import dev.symbiosis.runtime.telephony.StreamingAsr
import dev.symbiosis.runtime.telephony.TelephonyAudio
import dev.symbiosis.runtime.telephony.Tts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Talking and listening on a live carrier call.
 *
 * Every capability here takes a `session_id` and is refused unless the caller owns that call — the same
 * rule as `telephony.end_call`. That is what stops a second agent, or a driver left over from a previous run,
 * from talking into a call that is not theirs. A conversation lives exactly as long as its call session: it is
 * torn down automatically when the call reaches a terminal state (see the hooks in `Runtime`).
 *
 * See [TelephonyAudio] for why speaking works at all and [CallConversation] for the listen/speak loop.
 */
object CallSpeech {

  /** The open uplink of the conversation on [sessionId], so other capabilities render through the same policy. */
  fun activeSession(sessionId: String): TelephonyAudio.Session? =
    Conversations.get(sessionId)?.conversation?.uplink

  fun speakCapability(context: Context, broker: EventBroker, sessions: SessionManager): Capability = Capability(
    name = "telephony.speak",
    description = "Speak text into a call you own so the far party hears it (on-device TTS rendered at the telephony device). Blocks until spoken; returns early if the far party interrupts.",
    params = listOf(
      Param("session_id", "string", "The call session (from telephony.place_call)", required = true),
      Param("text", "string", "What to say", required = true),
      Param("gain", "number", "0..1 output level (default 1.0)"),
      Param("pitch", "number", "TTS pitch (default 1.0)"),
      Param("rate", "number", "TTS speech rate (default 1.0)"),
    ),
    actionClass = ActionClass.COMMUNICATION, tier = 1,
  ) { args, ctx ->
    val access = SessionAccess.ownedActiveCall(sessions, args["session_id"]?.toString(), ctx)
    if (access is SessionAccess.Access.Denied) return@Capability access.result
    val sessionId = (access as SessionAccess.Access.Ok).session.sessionId
    val text = args["text"]?.toString()?.takeIf { it.isNotBlank() }
      ?: return@Capability CapabilityResult.fail("invalid_params", "text is required")
    val gain = ((args["gain"] as? Number)?.toDouble() ?: 1.0).coerceIn(0.0, 1.0)
    val pitch = (args["pitch"] as? Number)?.toFloat() ?: 1.0f
    val speechRate = (args["rate"] as? Number)?.toFloat() ?: 1.0f
    if (!TelephonyAudio.hasPermission(context)) {
      return@Capability CapabilityResult.fail(
        "capability.unavailable",
        "MODIFY_AUDIO_ROUTING not granted; the runtime must be installed as a privileged system app",
      )
    }
    withContext(Dispatchers.IO) {
      val speech = Tts.synthesize(context, text, pitch, speechRate)
        ?: return@withContext CapabilityResult.fail("capability.failed", "TTS synthesis failed")
      val pcm = speech.pcm
      if (gain < 1.0) for (i in pcm.indices) pcm[i] = (pcm[i] * gain).toInt().toShort()

      val conv = Conversations.get(sessionId)?.conversation
      val result = if (conv != null) {
        // Share the conversation's uplink: it holds the policy and manages speaking state + barge-in.
        conv.speak(pcm)
      } else {
        // No conversation on this call: open a one-shot uplink (serialised, so two requests cannot overlap).
        // Still requires owning the call.
        val played = TelephonyAudio.playOneShot(context, pcm)
        played.getOrNull()
          ?: return@withContext CapabilityResult.fail(
            "capability.failed",
            played.exceptionOrNull()?.message ?: "could not open the call uplink",
          )
      } ?: return@withContext CapabilityResult.fail("capability.failed", "uplink unavailable")

      CapabilityResult.ok(
        mapOf(
          "session_id" to sessionId, "spoken" to true, "text" to text,
          "duration_ms" to (pcm.size * 1000L / TelephonyAudio.RATE),
          "played_ms" to result.playedMs, "drained" to result.drained,
          "interrupted" to result.interrupted,
          "tts_engine" to speech.engineName, "tts_native_rate" to speech.nativeRate,
          "synth_ms" to speech.synthMs,
        ),
      )
    }
  }

  fun stopSpeakingCapability(sessions: SessionManager): Capability = Capability(
    name = "telephony.stop_speaking",
    description = "Immediately stop whatever the agent is currently saying on a call you own (barge-in / cancel).",
    params = listOf(Param("session_id", "string", "The call session", required = true)),
    actionClass = ActionClass.LOW_RISK, tier = 1,
  ) { args, ctx ->
    val access = SessionAccess.ownedActiveCall(sessions, args["session_id"]?.toString(), ctx)
    if (access is SessionAccess.Access.Denied) return@Capability access.result
    val id = (access as SessionAccess.Access.Ok).session.sessionId
    val conv = Conversations.get(id)?.conversation
    conv?.stopSpeaking()
    CapabilityResult.ok(mapOf("session_id" to id, "stopped" to (conv != null), "conversation_active" to (conv != null)))
  }

  fun converseCapability(context: Context, broker: EventBroker, sessions: SessionManager): Capability = Capability(
    name = "telephony.converse",
    description = "Start or stop live conversation mode on a call you own: transcribes the far party in real time (events call.transcript / call.listening / call.barge_in) so the agent can reply with telephony.speak. Ends automatically when the call does.",
    params = listOf(
      Param("action", "string", "start | stop | status", enum = listOf("start", "stop", "status")),
      Param("session_id", "string", "The call session (required for start/stop; optional for status)"),
      Param("barge_in", "boolean", "Stop speaking when the far party starts talking (default true)"),
      Param("mute_mic", "boolean", "Mute the handset mic for the whole call so the room is never transmitted (default true)"),
      Param("hangover_ms", "integer", "Longest pause before a turn is closed (default 900). With the end-of-turn model the turn usually closes at ~200ms when the person sounds finished; this is the ceiling when they don't."),
      Param("endpoint_model", "boolean", "Use the on-device end-of-turn model (smart-turn v3) to close turns by how the person sounds, not just silence (default true)"),
      Param("min_silence_ms", "integer", "Shortest pause before a turn may close (default 400); the end-of-turn model can only extend it. 200 closed turns on mid-sentence pauses on real calls."),
      Param("barge_in_min_ms", "integer", "How long the far party must keep talking over the agent before it stops speaking (default 500; 0 = stop on the first syllable). Short interjections like 'uh-huh' then don't count as interruptions."),
    ),
    actionClass = ActionClass.COMMUNICATION, tier = 1,
  ) { args, ctx ->
    when (args["action"]?.toString() ?: "status") {
      "start" -> {
        if (!TelephonyAudio.hasPermission(context)) {
          return@Capability CapabilityResult.fail("capability.unavailable", "MODIFY_AUDIO_ROUTING not granted")
        }
        val access = SessionAccess.ownedActiveCall(sessions, args["session_id"]?.toString(), ctx)
        if (access is SessionAccess.Access.Denied) return@Capability access.result
        val id = (access as SessionAccess.Access.Ok).session.sessionId
        val bargeIn = args["barge_in"] as? Boolean ?: true
        val muteMic = args["mute_mic"] as? Boolean ?: true
        val hangover = (args["hangover_ms"] as? Number)?.toInt() ?: 900
        val useModel = args["endpoint_model"] as? Boolean ?: true
        val minSilence = (args["min_silence_ms"] as? Number)?.toInt() ?: 400
        val bargeInMin = (args["barge_in_min_ms"] as? Number)?.toInt() ?: 500

        // Load both models before the call needs them. Cold TTS costs ~1s; cold on-device ASR costs ~9s to
        // the first partial — and both would otherwise land on the opening turn.
        withContext(Dispatchers.IO) {
          Tts.warmUp(context)
          runCatching { StreamingAsr.warmUp(context) }
        }
        val r = Conversations.startOrExisting(
          id, ctx.peerId,
          create = { CallConversation(context, broker, id) },
          start = {
            it.start(
              bargeIn = bargeIn, muteMic = muteMic, hangover = hangover,
              useModel = useModel, minSilence = minSilence, bargeInMin = bargeInMin,
            )
          },
        )
        if (r.isFailure) {
          CapabilityResult.fail("capability.failed", r.exceptionOrNull()?.message ?: "could not start conversation")
        } else {
          val (entry, created) = r.getOrThrow()
          sessions.markDriverAttached(id)
          CapabilityResult.ok(
            mapOf(
              "session_id" to id, "started" to created, "already_running" to !created,
              "state" to entry.conversation.currentState.name,
              "barge_in" to bargeIn, "mic_muted" to muteMic, "hangover_ms" to hangover,
              "endpoint" to entry.conversation.endpointMode.name.lowercase(),
              "min_silence_ms" to minSilence, "barge_in_min_ms" to bargeInMin,
            ),
          )
        }
      }
      "stop" -> {
        val id = args["session_id"]?.toString()?.takeIf { it.isNotBlank() }
          ?: return@Capability CapabilityResult.fail("invalid_params", "session_id is required")
        // Ownership still applies, but a client must be able to clean up after the call has ended, so a
        // terminal state is not a refusal here.
        val access = SessionAccess.ownedActiveCall(sessions, id, ctx)
        if (access is SessionAccess.Access.Denied && access.result.errorCode != "session.not_active") return@Capability access.result
        // Read from the raw registry: get() reaps a loop that died on its own, and its error is exactly what to report.
        val err = Conversations.entries().firstOrNull { it.sessionId == id }?.conversation?.error
        val stopped = Conversations.stop(id, "peer request")
        if (Conversations.all().isEmpty()) Tts.shutdown()
        CapabilityResult.ok(mapOf("session_id" to id, "stopped" to stopped, "error" to err))
      }
      else -> {
        val id = args["session_id"]?.toString()?.takeIf { it.isNotBlank() }
        val visible = Conversations.all().filter { e ->
          val s = sessions.get(e.sessionId)
          s != null && SessionAccess.owns(s, ctx) && (id == null || e.sessionId == id)
        }
        CapabilityResult.ok(
          mapOf(
            "running" to visible.isNotEmpty(),
            "conversations" to visible.map {
              mapOf("session_id" to it.sessionId, "state" to it.conversation.currentState.name, "error" to it.conversation.error)
            },
            "modify_audio_routing" to TelephonyAudio.hasPermission(context),
          ),
        )
      }
    }
  }
}
