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

  fun currentSessionId(): String? = current.get()

  /** Places a call; returns the session id or throws IllegalStateException with a machine-readable message. */
  fun placeCall(target: String, displayName: String?, purpose: String?, audioRoute: String?, peerId: String?, correlationId: String, driverManaged: Boolean = false): Session {
    if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) throw SecurityException("CALL_PHONE")
    if (!E164.matches(target)) throw IllegalArgumentException("invalid_target")
    // Single-call gate: claim the slot atomically before creating the session so concurrent requests cannot both dial.
    val existingId = current.get()
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
    try {
      tm.placeCall(uri, Bundle())
      sessions.transition(session.sessionId, SessionState.DIALING, mapOf("number" to target))
    } catch (t: Throwable) {
      sessions.transition(session.sessionId, SessionState.FAILED, mapOf("reason" to (t.message ?: t.javaClass.simpleName)))
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
    val id = current.get() ?: return
    val s = sessions.get(id) ?: return
    if (s.state.terminal) { current.compareAndSet(id, null); return }
    when (state) {
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
   * may have moved the Call on.
   */
  fun onPreciseState(state: String, number: String?, disconnectCode: Int?, disconnectReason: String?) {
    val id = current.get() ?: return
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

  /** Prefer Telecom's Call.disconnect() when we are the dialer; otherwise best-effort TelecomManager.endCall. */
  fun endCall(id: String): Boolean {
    val s = sessions.get(id) ?: throw IllegalArgumentException("session.unknown")
    if (s.state.terminal) return true
    dev.symbiosis.runtime.telephony.SymbiosisInCallService.Holder.active()?.let { c ->
      sessions.transition(id, SessionState.ENDING)
      c.disconnect()
      return true
    }
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
    sessions.active().filter { it.kind == "call" && !it.state.terminal && it.sessionId != current.get() }.mapNotNull { s ->
      sessions.transition(s.sessionId, SessionState.FAILED, mapOf("reason" to reason)) ?: return@mapNotNull null
      sessions.setSummary(s.sessionId, mapOf("outcome" to SessionState.FAILED.name, "reason" to reason, "target" to s.meta["target"]?.toString()?.trim('"')))
      s.sessionId
    }

  fun endAll(reason: String) {
    current.get()?.let { id -> runCatching { endCall(id) }; finish(id, SessionState.CANCELLED, reason) }
  }

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
