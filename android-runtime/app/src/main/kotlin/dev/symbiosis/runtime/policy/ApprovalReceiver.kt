package dev.symbiosis.runtime.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.symbiosis.runtime.BuildConfig
import dev.symbiosis.runtime.core.Runtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives Approve/Deny from the approval notification. Not exported: only our own PendingIntents
 * reach it. In DEBUG builds only, `adb shell am broadcast -n dev.symbiosis.runtime/.policy.ApprovalReceiver
 * -a dev.symbiosis.DEBUG_RESOLVE --es id pa_xxx --es decision approve` is also accepted, so the
 * allow path can be exercised on an emulator without a human tapping the notification. The debug export
 * requires android.permission.DUMP, which the adb shell holds and installed apps cannot.
 */
class ApprovalReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    if (action != ACTION_RESOLVE && !(BuildConfig.DEBUG && action == ACTION_DEBUG_RESOLVE)) return
    val id = intent.getStringExtra(EXTRA_ID) ?: return
    val approved = intent.getStringExtra(EXTRA_DECISION) == "approve"
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        Runtime.get(context).resolveApproval(id, approved, via = if (action == ACTION_RESOLVE) "notification" else "debug-broadcast")
      } finally {
        pending.finish()
      }
    }
  }

  companion object {
    const val ACTION_RESOLVE = "dev.symbiosis.RESOLVE_APPROVAL"
    const val ACTION_DEBUG_RESOLVE = "dev.symbiosis.DEBUG_RESOLVE"
    const val EXTRA_ID = "id"
    const val EXTRA_DECISION = "decision"
  }
}
