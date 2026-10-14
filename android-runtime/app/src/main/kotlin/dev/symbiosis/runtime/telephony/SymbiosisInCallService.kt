package dev.symbiosis.runtime.telephony

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import dev.symbiosis.runtime.core.Runtime
import dev.symbiosis.runtime.events.Topics
import java.util.concurrent.ConcurrentHashMap

/**
 * Bound by Telecom whenever the app holds either the default-dialer role OR the privileged
 * CONTROL_INCALL_EXPERIENCE permission (the BCR mechanism). While bound during a call and holding
 * CAPTURE_AUDIO_OUTPUT, the VOICE_CALL / VOICE_UPLINK / VOICE_DOWNLINK streams become capturable —
 * the binding is what routes the full call audio to the app. Everything here is additive: the
 * TelephonyCallback path keeps working when the service is not bound.
 *
 * With RECORD_AUDIO granted, this promotes itself to a `microphone` foreground service for the duration of a call
 * so background capture is unrestricted (matches BCR). This used to happen on debug builds only, a leftover from
 * when the call-audio permissions lived in the debug manifest: conversation mode ships in release (the type and
 * FOREGROUND_SERVICE_MICROPHONE are in the main manifest), and a release build with the screen off could not
 * hear the far party.
 */
class SymbiosisInCallService : InCallService() {

  override fun onCallAdded(call: Call) {
    super.onCallAdded(call)
    Holder.calls[call] = Unit
    // Before the first state is reported: if this is the call a session just placed, bind it to that session so
    // end/DTMF act on it and its states drive that session. Every other call (the user's own) stays unbound.
    runCatching {
      val d = call.details
      Runtime.get(this).calls.onTelecomCallAdded(call, d.handle?.schemeSpecificPart, d.callDirection == Call.Details.DIRECTION_OUTGOING)
    }.onFailure { android.util.Log.w("SymbiosisInCall", "call binding failed", it) }
    call.registerCallback(callback)
    emit(call, "added")
    maybeStartMicForeground()
    // Only draw a call screen when we are the actual default dialer; as a background companion (BCR
    // mode) telephony still owns the dialer UI and we must not pop our own.
    val isDialer = runCatching { getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_DIALER) }.getOrDefault(false)
    if (isDialer && (call.details.callDirection == Call.Details.DIRECTION_INCOMING || call.state == Call.STATE_RINGING)) {
      startActivity(Intent(this, dev.symbiosis.runtime.ui.InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
  }

  override fun onCallRemoved(call: Call) {
    call.unregisterCallback(callback)
    Holder.calls.remove(call)
    emit(call, "removed")
    runCatching { Runtime.get(this).calls.onTelecomCallRemoved(call) }
    if (Holder.calls.isEmpty()) stopMicForeground()
    super.onCallRemoved(call)
  }

  private var micForeground = false
  private fun maybeStartMicForeground() {
    if (micForeground) return
    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
    runCatching {
      val nm = getSystemService(android.app.NotificationManager::class.java)
      nm.createNotificationChannel(android.app.NotificationChannel(FGS_CHANNEL, "Call audio", android.app.NotificationManager.IMPORTANCE_LOW))
      val n = android.app.Notification.Builder(this, FGS_CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Android Agent Harness call audio").build()
      startForeground(FGS_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
      micForeground = true
    }.onFailure { android.util.Log.w("SymbiosisInCall", "mic FGS start failed", it) }
  }
  private fun stopMicForeground() {
    if (!micForeground) return
    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    micForeground = false
  }

  override fun onCallAudioStateChanged(audioState: CallAudioState) {
    super.onCallAudioStateChanged(audioState)
    Holder.audioState = audioState
    runCatching {
      Runtime.get(this).broker.emit(
        Topics.CALL_AUDIO_STATE,
        mapOf("route" to routeName(audioState.route), "muted" to audioState.isMuted, "supported_routes" to audioState.supportedRouteMask),
      )
    }
  }

  private val callback = object : Call.Callback() {
    override fun onStateChanged(call: Call, state: Int) = emit(call, "state")
    override fun onDetailsChanged(call: Call, details: Call.Details) { /* number/caps updates are covered by state emits */ }
  }

  private fun emit(call: Call, why: String) {
    // Snapshot everything from the Call here, on the main thread where Telecom delivers it; the work itself goes
    // to the runtime's call-events thread. It used to run inline, and a DISCONNECTED callback then blocked the
    // main thread for up to ~2.7 s: finish() polls the call log (up to 1.2 s) and the terminal transition joins
    // the conversation loop (up to 1.5 s). One single thread shared with the TelephonyCallback path keeps
    // precise and coarse call-state updates in the order they happened.
    val d = call.details
    val number = d.handle?.schemeSpecificPart
    val state = stateName(call.state)
    val incoming = d.callDirection == Call.Details.DIRECTION_INCOMING
    val canHold = d.can(Call.Details.CAPABILITY_HOLD)
    val canMute = d.can(Call.Details.CAPABILITY_MUTE)
    val cause = d.disconnectCause
    val causeText = cause?.let { it.description?.toString() ?: it.reason }
    val causeCode = cause?.code
    val causeReason = cause?.reason
    val rt = runCatching { Runtime.get(this) }.getOrNull() ?: return
    // The session this Call belongs to, or null: only a call bound in onCallAdded is ever attributed to a session.
    val sessionId = rt.calls.sessionFor(call)
    runCatching {
      rt.callEvents.execute {
        runCatching {
          rt.broker.emit(
            Topics.CALL_PRECISE_STATE,
            mapOf(
              "state" to state, "why" to why, "number" to number,
              "direction" to if (incoming) "in" else "out",
              "session_id" to sessionId,
              "can_hold" to canHold, "can_mute" to canMute,
              "disconnect_cause" to causeText,
            ),
          )
          if (sessionId != null) rt.calls.onPreciseState(sessionId, state, number, causeCode, causeReason)
        }.onFailure { android.util.Log.w("SymbiosisInCall", "precise state handling failed", it) }
      }
    }
  }

  /**
   * Static handle: the live calls Telecom has handed us, or none when we are not bound. These include the user's
   * own calls; a capability acting on a session's call uses CallSessions.telecomCallFor, never a call picked from here.
   */
  object Holder {
    val calls = ConcurrentHashMap<Call, Unit>()
    @Volatile var audioState: CallAudioState? = null
    fun ringing(): Call? = calls.keys.firstOrNull { it.state == Call.STATE_RINGING }
    val bound: Boolean get() = instance != null
    @Volatile var instance: SymbiosisInCallService? = null
  }

  override fun onCreate() { super.onCreate(); Holder.instance = this }
  override fun onDestroy() { Holder.instance = null; Holder.calls.clear(); super.onDestroy() }

  companion object {
    private const val FGS_CHANNEL = "symbiosis.callaudio"
    private const val FGS_ID = 1101
    fun stateName(s: Int) = when (s) {
      Call.STATE_NEW -> "NEW"; Call.STATE_DIALING -> "DIALING"; Call.STATE_RINGING -> "RINGING"; Call.STATE_HOLDING -> "HOLDING"
      Call.STATE_ACTIVE -> "ACTIVE"; Call.STATE_DISCONNECTED -> "DISCONNECTED"; Call.STATE_CONNECTING -> "CONNECTING"
      Call.STATE_DISCONNECTING -> "DISCONNECTING"; Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
      Call.STATE_PULLING_CALL -> "PULLING"; Call.STATE_AUDIO_PROCESSING -> "AUDIO_PROCESSING"; Call.STATE_SIMULATED_RINGING -> "SIMULATED_RINGING"
      else -> "STATE_$s"
    }
    fun routeName(r: Int) = when (r) {
      CallAudioState.ROUTE_EARPIECE -> "earpiece"; CallAudioState.ROUTE_SPEAKER -> "speaker"; CallAudioState.ROUTE_BLUETOOTH -> "bluetooth"
      CallAudioState.ROUTE_WIRED_HEADSET -> "wired_headset"; else -> "route_$r"
    }
  }
}
