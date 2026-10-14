package dev.symbiosis.runtime.events.sources

import android.service.notification.StatusBarNotification
import dev.symbiosis.runtime.core.Runtime
import dev.symbiosis.runtime.events.Topics
import io.droidmcp.notification.McpNotificationListenerServiceBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The single NotificationListenerService in the app. Subclasses droid-mcp's base so its
 * notifications.* tools see every app, and additionally emits our `notification.posted/removed` events.
 * Callbacks arrive on the main thread; persistence happens on IO.
 * Enable on an emulator with: adb shell cmd notification allow_listener dev.symbiosis.runtime/.events.sources.NotificationEventListener
 */
class NotificationEventListener : McpNotificationListenerServiceBase() {
  private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onListenerConnected() {
    super.onListenerConnected()
    connected = true
  }

  override fun onListenerDisconnected() {
    connected = false
    super.onListenerDisconnected()
  }

  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    super.onNotificationPosted(sbn)
    sbn ?: return
    if (sbn.packageName == packageName) return // never loop on our own runtime/approval notifications
    val n = sbn.notification
    val extras = n.extras
    val text = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString() ?: extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
    val payload = mapOf(
      "key" to sbn.key,
      "package" to sbn.packageName,
      "title" to extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
      "text" to text,
      "ts" to sbn.postTime,
      "category" to n.category,
      "can_reply" to (n.actions?.any { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } ?: false),
      "ongoing" to sbn.isOngoing,
    )
    io.launch {
      runCatching { Runtime.get(this@NotificationEventListener).broker.emit(Topics.NOTIFICATION_POSTED, payload) }
      // RCS chats never hit the SMS broadcast or the SMS provider; the only signal is the messaging app's
      // incoming-message notification. Normalise it so agents see the same shape as messaging.sms_received.
      if (sbn.packageName in MESSAGING_APPS && sbn.key.contains("incoming_message") && text != null) {
        val from = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: "unknown"
        runCatching {
          Runtime.get(this@NotificationEventListener).broker.emit(
            Topics.RCS_RECEIVED,
            mapOf(
              "message_id" to "rcs_" + dev.symbiosis.runtime.core.Ids.sha256Hex("${sbn.key}|${sbn.postTime}").substring(0, 16),
              "thread_id" to null,
              "from" to from,
              "from_digits" to from.filter { it.isDigit() },
              "text" to text,
              "ts" to sbn.postTime,
              "transport" to "rcs",
              "notification_key" to sbn.key,
              "can_reply" to (payload["can_reply"] as Boolean),
            ),
          )
        }
      }
    }
  }

  override fun onNotificationRemoved(sbn: StatusBarNotification?) {
    super.onNotificationRemoved(sbn)
    sbn ?: return
    if (sbn.packageName == packageName) return
    val payload = mapOf("key" to sbn.key, "package" to sbn.packageName, "ts" to System.currentTimeMillis())
    io.launch { runCatching { Runtime.get(this@NotificationEventListener).broker.emit(Topics.NOTIFICATION_REMOVED, payload) } }
  }

  companion object {
    @Volatile var connected: Boolean = false
      private set

    /** Messaging apps whose incoming-message notifications are normalised into messaging.rcs_received. */
    val MESSAGING_APPS = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging")
  }
}
