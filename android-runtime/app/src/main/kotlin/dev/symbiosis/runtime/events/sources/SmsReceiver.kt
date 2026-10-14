package dev.symbiosis.runtime.events.sources

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.launch
import dev.symbiosis.runtime.core.Ids
import dev.symbiosis.runtime.core.Runtime
import dev.symbiosis.runtime.events.Topics

/**
 * Turns SMS_RECEIVED broadcasts into `messaging.sms_received` events. Multipart messages arrive as
 * several PDUs in one broadcast; they are concatenated here. We are not the default SMS app, so the
 * message still lands in the system inbox for `messaging.read_sms`.
 */
class SmsReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
    val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
    if (messages.isEmpty()) return
    val first = messages.first()
    val text = messages.joinToString("") { it.messageBody ?: "" }
    val from = first.displayOriginatingAddress ?: first.originatingAddress ?: "unknown"
    val ts = first.timestampMillis.takeIf { it > 0 } ?: Ids.now()
    val slot = intent.getIntExtra("slot", intent.getIntExtra("android.telephony.extra.SLOT_INDEX", -1))
    val messageId = "sms_" + Ids.sha256Hex("$from|$ts|$text").substring(0, 16)
    val pending = goAsync()
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
      try { Runtime.get(context).broker.emit(
      Topics.SMS_RECEIVED,
      mapOf(
        "message_id" to messageId,
        "thread_id" to null,
        "from" to from,
        "text" to text,
        "ts" to ts,
        "sim_slot" to slot,
        "parts" to messages.size,
      ),
    ) } catch (t: Throwable) { android.util.Log.e("SmsReceiver", "emit failed", t) } finally { pending.finish() }
    }
  }
}
