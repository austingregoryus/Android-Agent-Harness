package dev.symbiosis.runtime.capabilities

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.policy.RateClass
import java.util.concurrent.atomic.AtomicInteger

/**
 * `notifications.post`: show the phone's owner a notification from Android Agent Harness. Added for workflows (docs/automation.md
 * example c: "if urgent, tell me") — nothing else could put a message in front of the owner without texting them.
 *
 * LOW_RISK with the MUTATION rate class: it is local, cannot reach anyone but the person holding the phone, and is
 * metered with the other local mutations. It cannot loop through `notification.posted` triggers:
 * NotificationEventListener ignores our own package's notifications.
 */
object Notify {
  const val CHANNEL = "symbiosis.alerts"
  private val ids = AtomicInteger(5_000)

  fun capability(context: Context) = Capability(
    name = "notifications.post",
    description = "Show the owner a notification on this phone (title + text; high priority when urgent=true).",
    params = listOf(
      Param("title", "string", "Title (max 100 chars)", required = true),
      Param("text", "string", "Body (max 2000 chars)", required = true),
      Param("urgent", "boolean", "Heads-up / sound (default false)"),
    ),
    actionClass = ActionClass.LOW_RISK, rateClass = RateClass.MUTATION, tier = 0,
  ) { args, _ ->
    val nm = context.getSystemService(NotificationManager::class.java)
    // POST_NOTIFICATIONS is a runtime permission only from Android 13; areNotificationsEnabled covers both eras.
    if (!nm.areNotificationsEnabled()) return@Capability CapabilityResult.missingPermission("android.permission.POST_NOTIFICATIONS")
    val urgent = args["urgent"] == true
    val channel = if (urgent) "$CHANNEL.urgent" else CHANNEL
    nm.createNotificationChannel(
      NotificationChannel(channel, if (urgent) "Workflow alerts (urgent)" else "Workflow alerts", if (urgent) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT),
    )
    val title = args["title"]?.toString().orEmpty().take(100)
    val text = args["text"]?.toString().orEmpty().take(2000)
    val id = ids.incrementAndGet()
    nm.notify(
      id,
      Notification.Builder(context, channel).setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build(),
    )
    CapabilityResult.ok(mapOf("posted" to true, "notification_id" to id, "urgent" to urgent))
  }
}
