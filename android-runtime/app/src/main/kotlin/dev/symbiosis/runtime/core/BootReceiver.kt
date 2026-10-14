package dev.symbiosis.runtime.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the runtime after boot or an app update if it was enabled. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
    if (!RuntimeService.isEnabled(context)) return
    runCatching { RuntimeService.start(context) }
      .onFailure { android.util.Log.e("BootReceiver", "could not start runtime on boot", it) }
  }
}
