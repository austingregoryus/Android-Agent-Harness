package dev.symbiosis.runtime.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.symbiosis.runtime.core.Runtime
import kotlinx.coroutines.launch

/**
 * Schedule triggers ride on ONE inexact AlarmManager alarm, always set for the earliest `next_run_at` among enabled
 * scheduled workflows (the engine re-arms it after every change and every firing).
 *
 * Why AlarmManager.setAndAllowWhileIdle and not WorkManager or an exact alarm:
 *  - no new permission. Exact alarms need SCHEDULE_EXACT_ALARM (user-granted on Android 14+, and revocable);
 *    inexact ones need nothing.
 *  - doze: an allow-while-idle alarm fires in doze (at most about once per 9 minutes per app, which the 15-minute
 *    minimum for `every_minutes` stays above); WorkManager periodic work waits for doze maintenance windows.
 *  - no new dependency, and nothing to reconcile: the schedule's source of truth is the `workflows` table, the
 *    alarm is only a wake-up.
 * The cost is jitter: an inexact alarm may be delivered late — typically seconds to a few minutes while the phone
 * is awake, up to the next doze maintenance window (commonly ~10-15 min, longer in deep doze without the
 * battery-optimization exemption) when it is asleep. Schedules here are "around HH:MM", not "at HH:MM:00".
 *
 * Alarms do not survive a reboot, and they are not needed to: BootReceiver starts the runtime, Runtime.init starts
 * the engine, and the engine re-arms from the table. A run missed by more than 15 minutes (phone off) is logged as
 * `missed` and skipped to the next occurrence rather than run late.
 */
class ScheduleReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION) return
    val pending = goAsync()
    val runtime = Runtime.get(context)
    runtime.scope.launch {
      try { runtime.workflows.onAlarm() }
      catch (t: Throwable) { android.util.Log.e("ScheduleReceiver", "scheduled workflows failed", t) }
      finally { pending.finish() }
    }
  }

  companion object {
    const val ACTION = "dev.symbiosis.runtime.automation.SCHEDULE"
  }
}

/**
 * Wall clock or time zone changed: every schedule's next time is recomputed from now, so "daily at 07:30" means
 * 07:30 in the new zone rather than whatever instant was computed in the old one. Both broadcasts are protected
 * (only the system sends them), are exempt from the Android 8 implicit-broadcast limits, and need no permission.
 */
class TimeChangeReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_TIMEZONE_CHANGED && intent.action != Intent.ACTION_TIME_CHANGED) return
    val pending = goAsync()
    val runtime = Runtime.get(context)
    runtime.scope.launch {
      try { runtime.workflows.recomputeSchedules() }
      catch (t: Throwable) { android.util.Log.e("TimeChangeReceiver", "schedule recompute failed", t) }
      finally { pending.finish() }
    }
  }
}

/** The app's [AlarmSink]: one PendingIntent, replaced on every arm. */
class AndroidAlarms(private val context: Context) : AlarmSink {
  override fun arm(atMs: Long?) {
    val am = context.getSystemService(AlarmManager::class.java) ?: return
    val pi = PendingIntent.getBroadcast(
      context, 0, Intent(context, ScheduleReceiver::class.java).setAction(ScheduleReceiver.ACTION),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    if (atMs == null) am.cancel(pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
  }
}
