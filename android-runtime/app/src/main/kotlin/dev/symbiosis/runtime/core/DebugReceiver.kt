package dev.symbiosis.runtime.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.symbiosis.runtime.BuildConfig
import dev.symbiosis.runtime.policy.ActionClass

/**
 * DEBUG builds only (no-op in release): lets an emulator verification script drive flows that
 * normally need a tap on the screen.
 *
 *   adb shell am broadcast -n dev.symbiosis.runtime/.core.DebugReceiver -a dev.symbiosis.DEBUG_PAIR --es access standard|full
 *     → logs "SYMBIOSIS_PAIRING_CODE=XXXX-XXXX" (redeem it with POST /pair, exactly like a real agent)
 *   adb shell am broadcast -n dev.symbiosis.runtime/.core.DebugReceiver -a dev.symbiosis.DEBUG_FLAG --es flag estop|observe --ez on true|false
 */
class DebugReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (!BuildConfig.DEBUG) return
    val runtime = Runtime.get(context)
    when (intent.action) {
      ACTION_START -> { RuntimeService.start(context); Log.i(TAG, "SYMBIOSIS_START requested") }
      ACTION_PAIR -> {
        val code = when (intent.getStringExtra("access")) {
          "full" -> runtime.peers.mintPairingCode(listOf("*"), ActionClass.DESTRUCTIVE)
          // "readonly": every read family, nothing that acts.
          "readonly" -> runtime.peers.mintPairingCode(dev.symbiosis.runtime.transport.STANDARD_SCOPES, ActionClass.SENSITIVE_READ)
          else -> runtime.peers.mintStandardCode()
        }
        Log.i(TAG, "SYMBIOSIS_PAIRING_CODE=${code.code}")
      }
      ACTION_FLAG -> {
        val on = intent.getBooleanExtra("on", false)
        when (intent.getStringExtra("flag")) {
          "estop" -> runtime.policy.setEmergencyStop(on)
          "observe" -> runtime.policy.setObserveOnly(on)
          "reset_rates" -> runtime.db.writableDatabase.delete("rate_counters", null, null)
          // --es number +1...: put a number on the allowlist, or take it off. Under the unattended ALLOW
          // defaults this is not just a standing approval for that number — a non-empty allowlist makes
          // every OTHER number require approval too (PolicyEngine.kt evaluate(), ~line 85).
          "allow_target" -> {
            val n = intent.getStringExtra("number")?.trim().orEmpty()
            if (n.isNotEmpty()) {
              val cfg = runtime.policy.config.value
              val list = if (on) (cfg.contactAllowlist + n).distinct() else cfg.contactAllowlist.filterNot { it == n }
              runtime.policy.updateConfig(cfg.copy(contactAllowlist = list))
            }
          }
          "lan" -> RuntimeService.setExposeLan(context, on)
          "dialer_role" -> context.startActivity(
            android.content.Intent(context, dev.symbiosis.runtime.ui.MainActivity::class.java)
              .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
              .putExtra(dev.symbiosis.runtime.ui.MainActivity.EXTRA_REQUEST_DIALER_ROLE, on),
          )
        }
        Log.i(TAG, "SYMBIOSIS_FLAG ${intent.getStringExtra("flag")}=$on")
      }
    }
  }

  companion object {
    const val TAG = "SymbiosisDebug"
    const val ACTION_START = "dev.symbiosis.DEBUG_START"
    const val ACTION_PAIR = "dev.symbiosis.DEBUG_PAIR"
    const val ACTION_FLAG = "dev.symbiosis.DEBUG_FLAG"
  }
}
