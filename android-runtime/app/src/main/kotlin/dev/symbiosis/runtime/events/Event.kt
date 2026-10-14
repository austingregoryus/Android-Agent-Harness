package dev.symbiosis.runtime.events

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** An immutable fact from the journal. */
@Serializable
data class Event(
  val id: Long,
  val topic: String,
  val ts: Long,
  val correlation_id: String? = null,
  val payload: JsonObject,
  val device_id: String? = null,
)

object Topics {
  const val SMS_RECEIVED = "messaging.sms_received"
  /** RCS chat messages surface only as the messaging app's notification; normalised to the SMS shape with transport=rcs. */
  const val RCS_RECEIVED = "messaging.rcs_received"
  const val CALL_STATE_CHANGED = "call.state_changed"
  /** Telecom-precise states (NEW/DIALING/RINGING/ACTIVE/HOLDING/DISCONNECTED…) with the remote number; only while we are the default dialer. */
  const val CALL_PRECISE_STATE = "call.precise_state"
  const val CALL_AUDIO_STATE = "call.audio_state"
  const val CALL_RINGING = "call.ringing"
  const val CALL_CONNECTED = "call.connected"
  const val CALL_ENDED = "call.ended"
  const val NOTIFICATION_POSTED = "notification.posted"
  const val NOTIFICATION_REMOVED = "notification.removed"
  const val BATTERY_CHANGED = "battery.changed"
  const val CONNECTIVITY_CHANGED = "connectivity.changed"
  const val SESSION_STATE = "session.state"
  /** Far party started talking (VAD opened a turn). */
  const val CALL_LISTENING = "call.listening"
  /** Transcript of the far party; `final=false` for interim results that may still change. */
  const val CALL_TRANSCRIPT = "call.transcript"
  /** The agent started/stopped speaking into the uplink. */
  const val CALL_SPEAKING = "call.speaking"
  /** The far party talked over the agent, so playback was cut short. */
  const val CALL_BARGE_IN = "call.barge_in"
  /**
   * Conversation mode stopped without the driver asking: `{session_id, state:"stopped", reason, error?}` where
   * reason is `error` (the capture loop died) or `driver_lost` (the owner vanished; the call was also ended).
   */
  const val CALL_CONVERSATION = "call.conversation"
  const val APPROVAL_REQUIRED = "policy.approval_required"
  const val APPROVAL_RESOLVED = "policy.approval_resolved"
  /** A saved workflow run began / ended: `{workflow_id, name, owner, trigger, event_id?}` (+ `ok, stopped_at, stop_reason, ms`). */
  const val AUTOMATION_RUN_STARTED = "automation.run_started"
  const val AUTOMATION_RUN_FINISHED = "automation.run_finished"
  const val RUNTIME_STARTED = "runtime.started"
  const val RUNTIME_STOPPING = "runtime.stopping"
}

/** One clause of a subscription filter: top-level payload field compared to a value. */
@Serializable
data class FilterClause(val field: String, val op: String, val value: JsonElement) {
  fun matches(payload: JsonObject): Boolean {
    val actual = payload[field] ?: return op == "ne"
    val a = actual as? JsonPrimitive ?: return false
    val v = value as? JsonPrimitive ?: return false
    val an = a.doubleOrNull; val vn = v.doubleOrNull
    return when (op) {
      "eq" -> if (an != null && vn != null) an == vn else a.contentOrNull == v.contentOrNull || a.booleanOrNull == v.booleanOrNull && a.booleanOrNull != null
      "ne" -> !(if (an != null && vn != null) an == vn else a.contentOrNull == v.contentOrNull)
      "lt" -> an != null && vn != null && an < vn
      "lte" -> an != null && vn != null && an <= vn
      "gt" -> an != null && vn != null && an > vn
      "gte" -> an != null && vn != null && an >= vn
      "contains" -> a.contentOrNull?.contains(v.contentOrNull ?: return false, ignoreCase = true) == true
      "prefix" -> a.contentOrNull?.startsWith(v.contentOrNull ?: return false) == true
      else -> false
    }
  }
}

/** Topic glob: `*` matches one segment, a trailing `.*` matches any depth, bare `*` matches all. */
object TopicGlob {
  fun matches(glob: String, topic: String): Boolean {
    if (glob == "*" || glob == topic) return true
    if (glob.endsWith(".*")) {
      val prefix = glob.dropLast(2)
      return topic.startsWith("$prefix.")
    }
    val gs = glob.split('.'); val ts = topic.split('.')
    if (gs.size != ts.size) return false
    return gs.zip(ts).all { (g, t) -> g == "*" || g == t }
  }
}
