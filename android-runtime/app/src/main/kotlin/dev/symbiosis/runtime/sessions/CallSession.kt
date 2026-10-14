package dev.symbiosis.runtime.sessions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import dev.symbiosis.runtime.core.Ids
import dev.symbiosis.runtime.events.EventBroker
import dev.symbiosis.runtime.events.Topics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Call sessions: placing a call through TelecomManager (no activity launch, works from a headless
 * service), tracking its lifecycle from the device call state, and best-effort end/audio-route.
 *
 * Only one outbound call session can be tracked at a time because TelephonyManager reports a single
 * call state; the emulator behaves the same way.
 */
class CallSessions(
  private val context: Context,
  private val sessions: SessionManager,
  private val broker: EventBroker,
  private val scope: CoroutineScope,
) {
  private val current = AtomicReference<String?>(null)
  private var timeoutJob: Job? = null
  private var connectedAt: Long = 0
  private var lastCallState: Int = TelephonyManager.CALL_STATE_IDLE

  /**
   * Each session's own Telecom call, once identified ([onTelecomCallAdded]). end_call, DTMF, the dial watchdog,
   * the driver lease and emergency stop act on this Call only. They used to act on `Holder.active()`, whatever call
   * Telecom had up: with the agent's call still DIALING while the user was on a personal or VoIP call (the
   * InCallService sees self-managed calls too), any of them could hang up the user's call.
   */
  private val telecomCalls = java.util.concurrent.ConcurrentHashMap<String, android.telecom.Call>()
  /** The call placeCall just asked Telecom for, until Telecom reports it (or [CallBinding.WINDOW_MS] passes). */
  private val placing = AtomicReference<CallBinding.Placed?>(null)
  /**
   * True when some other call existed when the live session dialed, or one has rung since. Only then can
   * TelecomManager.endCall (which ends the foreground call, or rejects a ringing one) reach a call that is not ours.
   */
  @Volatile private var otherCall = false

  /** The live call session, or null. Never [PLACEHOLDER]: that is a placeCall that has no session yet. */
  fun currentSessionId(): String? = current.get()?.takeUnless { it == PLACEHOLDER }

  /** Places a call; returns the session id or throws IllegalStateException with a machine-readable message. */
  fun placeCall(target: String, displayName: String?, purpose: String?, audioRoute: String?, peerId: String?, correlationId: String, driverManaged: Boolean = false): Session {
    if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) throw SecurityException("CALL_PHONE")
    if (!E164.matches(target)) throw IllegalArgumentException("invalid_target")
    // Single-call gate: claim the slot atomically before creating the session so concurrent requests cannot both dial.
    // PLACEHOLDER is another request between claiming the slot and creating its session, so it is busy. It used to
    // read as free (no session has that id), the CAS PLACEHOLDER -> PLACEHOLDER then succeeded, and both dialed.
    val existingId = current.get()
    if (existingId == PLACEHOLDER) throw IllegalStateException("call.busy:concurrent")
    val existing = existingId?.let { sessions.get(it) }
    if (existing != null && !existing.state.terminal) throw IllegalStateException("call.busy:${existing.sessionId}")
    if (!current.compareAndSet(existingId, PLACEHOLDER)) throw IllegalStateException("call.busy:concurrent")

    val session = try {
      sessions.create(
        "call", peerId, correlationId,
        mapOf("target" to target, "display_name" to displayName, "purpose" to purpose, "direction" to "out", "audio_route" to (audioRoute ?: "earpiece"), "driver_managed" to driverManaged),
      )
    } catch (t: Throwable) { current.compareAndSet(PLACEHOLDER, null); throw t }
    current.set(session.sessionId)
    finished.remove(session.sessionId)
    lastCallState = TelephonyManager.CALL_STATE_IDLE

    val tm = context.getSystemService(TelecomManager::class.java)
    val uri = Uri.fromParts("tel", target, null)
    // Both recorded before dialing: Telecom can report the new call (onTelecomCallAdded) before placeCall returns.
    otherCall = runCatching { tm.isInCall }.getOrDefault(true)
    val placed = CallBinding.Placed(session.sessionId, target, Ids.now())
    placing.set(placed)
    try {
      tm.placeCall(uri, Bundle())
      sessions.transition(session.sessionId, SessionState.DIALING, mapOf("number" to target))
    } catch (t: Throwable) {
      sessions.transition(session.sessionId, SessionState.FAILED, mapOf("reason" to (t.message ?: t.javaClass.simpleName)))
      placing.compareAndSet(placed, null)
      current.compareAndSet(session.sessionId, null)
      throw IllegalStateException("call.failed:${t.message}")
    }
    if (audioRoute == "speaker") scope.launch { delay(1500); setAudioRoute(session.sessionId, "speaker") }

    // Watchdog: if we never leave DIALING/RINGING, give up.
    timeoutJob?.cancel()
    timeoutJob = scope.launch {
      delay(DIAL_TIMEOUT_MS)
      val s = sessions.get(session.sessionId) ?: return@launch
      if (s.state == SessionState.DIALING || s.state == SessionState.RINGING) {
        runCatching { endCall(session.sessionId) }
        finish(session.sessionId, SessionState.NO_ANSWER, "dial timeout")
      }
    }
    return sessions.get(session.sessionId)!!
  }

  /** Feed from the device-wide call state listener. */
  fun onCallState(state: Int, number: String?) {
    val id = currentSessionId() ?: return
    val s = sessions.get(id) ?: return
    if (s.state.terminal) { current.compareAndSet(id, null); return }
    when (state) {
      // Ringing while ours is up is a second, incoming call: TelecomManager.endCall would now reject it.
      TelephonyManager.CALL_STATE_RINGING -> otherCall = true
      TelephonyManager.CALL_STATE_OFFHOOK -> {
        // Without an InCallService there is no public signal separating "alerting" from "active":
        // OFFHOOK is the earliest reliable "the network has the call" signal, so it maps to CONNECTED.
        // Ground truth (did the far end answer, for how long) is read from the call log at ENDED
        // and reported in session.summary as `answered` + `duration_ms`.
        if (s.state == SessionState.DIALING || s.state == SessionState.RINGING || s.state == SessionState.CREATED) {
          connectedAt = Ids.now()
          sessions.transition(id, SessionState.CONNECTED, mapOf("number" to number, "signal" to "offhook"))
          broker.emit(Topics.CALL_CONNECTED, mapOf("number" to number, "session_id" to id, "direction" to "out", "signal" to "offhook"), s.correlationId)
          sessions.transition(id, SessionState.ACTIVE)
        }
      }
      TelephonyManager.CALL_STATE_IDLE -> {
        if (lastCallState != TelephonyManager.CALL_STATE_IDLE) {
          val wasConnected = s.state == SessionState.CONNECTED || s.state == SessionState.ACTIVE || s.state == SessionState.HOLDING || s.state == SessionState.ENDING
          finish(id, if (wasConnected) SessionState.ENDED else SessionState.NO_ANSWER, if (wasConnected) "hangup" else "ended before connect")
        }
      }
    }
    lastCallState = state
  }

  private fun finish(id: String, state: SessionState, reason: String) {
    if (!finished.add(id)) return // IDLE callback and dial watchdog can both arrive; only the first finishes
    timeoutJob?.cancel()
    telecomCalls.remove(id)
    val s = sessions.get(id) ?: return
    val offhookMs = if (connectedAt > 0) Ids.now() - connectedAt else 0
    sessions.transition(id, state, mapOf("reason" to reason, "offhook_ms" to offhookMs))
    val target = s.meta["target"]?.toString()?.trim('"')
    // Ground truth from the call log (needs READ_CALL_LOG; null when unavailable).
    val log = readCallLog(target)
    sessions.setSummary(
      id,
      mapOf(
        "outcome" to state.name, "reason" to reason, "target" to target, "offhook_ms" to offhookMs,
        "answered" to log?.let { it.durationSec > 0 }, "duration_ms" to (log?.durationSec?.times(1000) ?: offhookMs), "call_log_type" to log?.type,
      ),
    )
    val duration = log?.durationSec?.times(1000) ?: offhookMs
    broker.emit(Topics.CALL_ENDED, mapOf("session_id" to id, "direction" to "out", "duration_ms" to duration, "outcome" to state.name), s.correlationId)
    connectedAt = 0
    current.compareAndSet(id, null)
  }

  /**
   * Telecom-precise states from our InCallService (only while we hold the default-dialer role). More accurate
   * than the OFFHOOK heuristic: ACTIVE means the far end answered, HOLDING is real, and the number is known.
   */
  /*
   * Takes the disconnect cause as a snapshot, not the Call: this runs on the runtime's call-events thread (the
   * InCallService posts to it so finish()'s call-log wait never blocks the main thread), and by then Telecom
   * may have moved the Call on. [sessionId] is the session bound to the Call that changed ([sessionFor]); the
   * InCallService does not call this for other calls. It used to apply every call's state to the live session, so
   * the user's own call disconnecting finished the agent's session.
   */
  fun onPreciseState(sessionId: String, state: String, number: String?, disconnectCode: Int?, disconnectReason: String?) {
    val id = sessionId
    if (current.get() != id) return
    val s = sessions.get(id) ?: return
    if (s.state.terminal) return
    when (state) {
      "DIALING", "CONNECTING" -> if (s.state == SessionState.CREATED) sessions.transition(id, SessionState.DIALING, mapOf("number" to number, "signal" to "telecom"))
      "ACTIVE" -> if (s.state != SessionState.ACTIVE) {
        if (connectedAt == 0L) connectedAt = Ids.now()
        if (s.state != SessionState.CONNECTED) { sessions.transition(id, SessionState.CONNECTED, mapOf("number" to number, "signal" to "telecom_active")); broker.emit(Topics.CALL_CONNECTED, mapOf("number" to number, "session_id" to id, "direction" to "out", "signal" to "telecom_active"), s.correlationId) }
        sessions.transition(id, SessionState.ACTIVE)
      }
      "HOLDING" -> sessions.transition(id, SessionState.HOLDING, mapOf("number" to number))
      "DISCONNECTED" -> {
        val cause = disconnectCode
        val wasConnected = connectedAt > 0
        val outcome = when {
          wasConnected -> SessionState.ENDED
          cause == android.telecom.DisconnectCause.BUSY -> SessionState.BUSY
          cause == android.telecom.DisconnectCause.REJECTED -> SessionState.NO_ANSWER
          cause == android.telecom.DisconnectCause.ERROR -> SessionState.FAILED
          else -> SessionState.NO_ANSWER
        }
        finish(id, outcome, "telecom:" + (disconnectReason ?: "disconnected"))
      }
    }
  }

  /**
   * Hang up this session's own call and nothing else. With our InCallService bound that is the session's Telecom
   * Call ([telecomCallFor]). Without it the platform only has TelecomManager.endCall, which ends the FOREGROUND call
   * (or rejects a ringing one): used only for the live session, and only when no other call existed at dial time
   * and none has rung since ([otherCall]), so it cannot reach the user's call. Otherwise this throws
   * IllegalStateException("call.unidentified") and touches no call.
   */
  fun endCall(id: String): Boolean {
    val s = sessions.get(id) ?: throw IllegalArgumentException("session.unknown")
    if (s.state.terminal) return true
    telecomCalls[id]?.let { c ->
      sessions.transition(id, SessionState.ENDING)
      c.disconnect()
      return true
    }
    // Bound but not identified (Telecom has not reported our call yet, or its number did not match): every call
    // Telecom has might be the user's. A call of ours reported after its session ended is hung up on arrival.
    if (dev.symbiosis.runtime.telephony.SymbiosisInCallService.Holder.bound) throw IllegalStateException("call.unidentified")
    if (id != currentSessionId() || otherCall) throw IllegalStateException("call.unidentified")
    if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) throw SecurityException("ANSWER_PHONE_CALLS")
    sessions.transition(id, SessionState.ENDING)
    val tm = context.getSystemService(TelecomManager::class.java)
    @Suppress("DEPRECATION")
    val ok = runCatching { tm.endCall() }.getOrDefault(false)
    if (!ok) sessions.emitFrame(id, s.correlationId, "error", kotlinx.serialization.json.buildJsonObject { put("code", kotlinx.serialization.json.JsonPrimitive("capability.unavailable")); put("message", kotlinx.serialization.json.JsonPrimitive("TelecomManager.endCall refused; end the call from the dialer")) })
    return ok
  }

  /** Best effort. Routing a call owned by the system dialer is not guaranteed for a non-dialer app. */
  fun setAudioRoute(id: String, route: String): Boolean {
    val am = context.getSystemService(AudioManager::class.java)
    val speaker = route == "speaker"
    // As the default dialer, Telecom owns call audio routing and this is authoritative.
    dev.symbiosis.runtime.telephony.SymbiosisInCallService.Holder.instance?.let { svc ->
      svc.setAudioRoute(if (speaker) android.telecom.CallAudioState.ROUTE_SPEAKER else android.telecom.CallAudioState.ROUTE_EARPIECE)
      sessions.get(id)?.let { s -> sessions.emitFrame(id, s.correlationId, "audio.route", kotlinx.serialization.json.buildJsonObject { put("route", kotlinx.serialization.json.JsonPrimitive(route)); put("applied", kotlinx.serialization.json.JsonPrimitive(true)); put("via", kotlinx.serialization.json.JsonPrimitive("telecom")) }) }
      return true
    }
    return runCatching {
      if (Build.VERSION.SDK_INT >= 31) {
        if (speaker) {
          val dev = am.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
          if (dev != null) am.setCommunicationDevice(dev) else false
        } else { am.clearCommunicationDevice(); true }
      } else {
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = speaker
        true
      }
    }.getOrDefault(false).also { ok ->
      sessions.get(id)?.let { s -> sessions.emitFrame(id, s.correlationId, "audio.route", kotlinx.serialization.json.buildJsonObject { put("route", kotlinx.serialization.json.JsonPrimitive(route)); put("applied", kotlinx.serialization.json.JsonPrimitive(ok)) }) }
    }
  }

  /**
   * Called once when the Runtime is built. [current] lives only in memory, so a call session that was live when
   * the process died (crash, low-memory kill, reboot) can never be finished by a later state callback: without
   * this it stayed non-terminal forever, and non-terminal was what let its owner keep speaking into calls.
   * Nothing survives a process restart that could still be driving it (the conversation loop, the uplink policy
   * and the dial watchdog all died with the process), so FAILED is the honest outcome. Returns the ids failed.
   */
  fun failStale(reason: String = "process_restart"): List<String> =
    sessions.active().filter { it.kind == "call" && !it.state.terminal && it.sessionId != currentSessionId() }.mapNotNull { s ->
      sessions.transition(s.sessionId, SessionState.FAILED, mapOf("reason" to reason)) ?: return@mapNotNull null
      sessions.setSummary(s.sessionId, mapOf("outcome" to SessionState.FAILED.name, "reason" to reason, "target" to s.meta["target"]?.toString()?.trim('"')))
      s.sessionId
    }

  fun endAll(reason: String) {
    currentSessionId()?.let { id -> runCatching { endCall(id) }; finish(id, SessionState.CANCELLED, reason) }
  }

  /**
   * From the InCallService's onCallAdded (before it reports the call's first state): take [call] as the session's
   * own if it is the one placeCall just dialed: outgoing, to the dialed number, within [CallBinding.WINDOW_MS].
   * Returns the session it was bound to, or null. A match whose session already ended (dial watchdog, emergency
   * stop, end_call before Telecom reported the call) is disconnected: it is ours, and nothing else would end it.
   */
  fun onTelecomCallAdded(call: android.telecom.Call, number: String?, outgoing: Boolean, now: Long = Ids.now()): String? {
    val p = placing.get() ?: return null
    if (!CallBinding.matches(p, number, outgoing, now) || !placing.compareAndSet(p, null)) return null
    val s = sessions.get(p.sessionId)
    if (s == null || s.state.terminal) { runCatching { call.disconnect() }; return null }
    telecomCalls[p.sessionId] = call
    return p.sessionId
  }

  /** The session [call] belongs to, or null for every call this runtime did not place (the user's own). */
  fun sessionFor(call: android.telecom.Call): String? = telecomCalls.entries.firstOrNull { it.value === call }?.key

  /** This session's own Telecom call, or null when it is not identified (or our InCallService is not bound). */
  fun telecomCallFor(id: String): android.telecom.Call? = telecomCalls[id]

  fun onTelecomCallRemoved(call: android.telecom.Call) { telecomCalls.entries.removeIf { it.value === call } }

  private data class LogRow(val durationSec: Long, val type: String)

  /** Most recent call-log row for the target within the last 10 minutes; the log is written shortly after IDLE. */
  private fun readCallLog(target: String?): LogRow? {
    if (target == null) return null
    if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return null
    // The provider row can lag the IDLE callback by a few hundred ms.
    repeat(5) { attempt ->
      val row = runCatching {
        context.contentResolver.query(
          android.provider.CallLog.Calls.CONTENT_URI,
          arrayOf(android.provider.CallLog.Calls.DURATION, android.provider.CallLog.Calls.TYPE, android.provider.CallLog.Calls.DATE, android.provider.CallLog.Calls.NUMBER),
          "${android.provider.CallLog.Calls.DATE} > ?", arrayOf((Ids.now() - 10 * 60_000).toString()),
          "${android.provider.CallLog.Calls.DATE} DESC",
        )?.use { c ->
          while (c.moveToNext()) {
            val num = c.getString(3) ?: continue
            if (num.filter { it.isDigit() }.endsWith(target.filter { it.isDigit() }.takeLast(7))) {
              val type = when (c.getInt(1)) { android.provider.CallLog.Calls.OUTGOING_TYPE -> "outgoing"; android.provider.CallLog.Calls.INCOMING_TYPE -> "incoming"; android.provider.CallLog.Calls.MISSED_TYPE -> "missed"; android.provider.CallLog.Calls.REJECTED_TYPE -> "rejected"; else -> "other" }
              return@use LogRow(c.getLong(0), type)
            }
          }
          null
        }
      }.getOrNull()
      if (row != null) return row
      if (attempt < 4) Thread.sleep(300)
    }
    return null
  }

  private val finished = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

  companion object {
    const val DIAL_TIMEOUT_MS = 60_000L
    private const val PLACEHOLDER = "call_placing"
    /** E.164 or a bare national number: digits with an optional leading +, 3–15 digits, no dial-string decorations. */
    val E164 = Regex("^\\+?[0-9]{3,15}$")
  }
}

/** Which Telecom call a call session placed. Pure, so the rule is unit-testable. */
object CallBinding {
  /** A call this runtime just asked Telecom to place. */
  data class Placed(val sessionId: String, val target: String, val placedAt: Long)

  /** How long after placeCall Telecom may report the call and still be taken for ours. */
  const val WINDOW_MS = 30_000L

  /** Outgoing, to the number we dialed, reported within [WINDOW_MS] of dialing. */
  fun matches(p: Placed, number: String?, outgoing: Boolean, addedAt: Long): Boolean =
    outgoing && addedAt in p.placedAt..(p.placedAt + WINDOW_MS) && sameNumber(p.target, number)

  /**
   * Same digits, or with 7 or more digits one ending with the other: Telecom can report +15551234567 for a dialed
   * 5551234567. Short numbers (service codes) must match exactly.
   */
  fun sameNumber(dialed: String, reported: String?): Boolean {
    val a = dialed.filter(Char::isDigit)
    val b = reported?.filter(Char::isDigit).orEmpty()
    if (a.isEmpty() || b.isEmpty()) return false
    if (a == b) return true
    val (short, long) = if (a.length <= b.length) a to b else b to a
    return short.length >= 7 && long.endsWith(short)
  }
}
