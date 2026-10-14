package dev.symbiosis.runtime.events.sources

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import dev.symbiosis.runtime.events.EventBroker
import dev.symbiosis.runtime.events.Topics
import dev.symbiosis.runtime.sessions.CallSessions
import java.util.concurrent.Executor

/**
 * Device-wide call state → `call.state_changed` (+ derived `call.ringing`) events, and feeds the
 * current outbound [CallSessions] state machine. Numbers are only available with READ_CALL_LOG on
 * API 28+; we emit null rather than fail.
 */
class CallStateSource(private val context: Context, private val broker: EventBroker, private val calls: CallSessions) {
  private var callback: TelephonyCallback? = null
  @Suppress("DEPRECATION")
  private var legacy: PhoneStateListener? = null
  private var last = TelephonyManager.CALL_STATE_IDLE

  fun start(executor: Executor) {
    if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
    val tm = context.getSystemService(TelephonyManager::class.java)
    if (Build.VERSION.SDK_INT >= 31) {
      val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) = handle(state, null)
      }
      callback = cb
      tm.registerTelephonyCallback(executor, cb)
    } else {
      @Suppress("DEPRECATION")
      val l = object : PhoneStateListener() {
        @Deprecated("Deprecated in Java")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) = handle(state, phoneNumber)
      }
      legacy = l
      @Suppress("DEPRECATION")
      tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
    }
  }

  fun stop() {
    val tm = context.getSystemService(TelephonyManager::class.java)
    if (Build.VERSION.SDK_INT >= 31) callback?.let { tm.unregisterTelephonyCallback(it) }
    else @Suppress("DEPRECATION") legacy?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
  }

  private fun handle(state: Int, number: String?) {
    val name = when (state) { TelephonyManager.CALL_STATE_RINGING -> "RINGING"; TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"; else -> "IDLE" }
    val sessionId = calls.currentSessionId()
    broker.emit(Topics.CALL_STATE_CHANGED, mapOf("state" to name, "number" to number, "session_id" to sessionId))
    if (state == TelephonyManager.CALL_STATE_RINGING && last != TelephonyManager.CALL_STATE_RINGING) {
      broker.emit(Topics.CALL_RINGING, mapOf("number" to number, "direction" to "in", "session_id" to null))
    }
    if (state == TelephonyManager.CALL_STATE_IDLE && last == TelephonyManager.CALL_STATE_RINGING) {
      broker.emit(Topics.CALL_ENDED, mapOf("number" to number, "direction" to "in", "session_id" to null, "outcome" to "MISSED_OR_REJECTED"))
    }
    if (state == TelephonyManager.CALL_STATE_OFFHOOK && last == TelephonyManager.CALL_STATE_RINGING && sessionId == null) {
      broker.emit(Topics.CALL_CONNECTED, mapOf("number" to number, "direction" to "in", "session_id" to null))
    }
    calls.onCallState(state, number)
    last = state
  }
}
