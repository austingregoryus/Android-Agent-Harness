package dev.symbiosis.runtime.automation

import dev.symbiosis.runtime.core.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** When a saved workflow runs by itself. */
sealed class Trigger {
  /** Every event on [topic] (a topic or topic glob) whose payload satisfies [where]. */
  data class OnEvent(val topic: String, val where: JsonObject?) : Trigger()

  /** Every [minutes] minutes (>= 15). */
  data class Every(val minutes: Int) : Trigger()

  /** At [time] local, on [days] (null = every day). */
  data class Daily(val time: LocalTime, val days: Set<DayOfWeek>?) : Trigger()
}

/**
 * A trigger plus its brakes. [cooldownMs] and [maxRunsPerHour] exist because a workflow can cause the very event it
 * listens for (a reply that produces a notification, a text that produces a delivery event) in ways the
 * correlation-id loop guard cannot see — an inbound message caused indirectly carries no correlation id.
 */
data class TriggerSpec(val trigger: Trigger, val cooldownMs: Long = DEFAULT_COOLDOWN_MS, val maxRunsPerHour: Int = DEFAULT_MAX_PER_HOUR) {
  companion object {
    const val DEFAULT_COOLDOWN_MS = 10_000L
    const val DEFAULT_MAX_PER_HOUR = 60
    /** Floor and ceiling: a 0 ms cooldown or 600 runs/hour turned any loop into a storm before a human could react. */
    const val MIN_COOLDOWN_MS = 1_000L
    const val MAX_PER_HOUR = 120
  }
}

class TriggerError(message: String) : Exception(message)

object Triggers {
  const val MIN_EVERY_MINUTES = 15
  private val AUTOMATION_TOPICS = listOf(dev.symbiosis.runtime.events.Topics.AUTOMATION_RUN_STARTED, dev.symbiosis.runtime.events.Topics.AUTOMATION_RUN_FINISHED)
  private val DAYS = mapOf(
    "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
    "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
  )

  /**
   * `{event: {topic, where?}}` or `{schedule: {every_minutes: N}}` or `{schedule: {daily_at: "HH:MM", days?: [...]}}`,
   * each optionally with `cooldown_ms` (1 s .. 1 day) and `max_runs_per_hour` (1..120) beside it.
   *
   * [strict] (save time) rejects out-of-range values; loading a stored row clamps them instead, so a workflow saved
   * under older, looser limits keeps working, at the new limits, rather than silently losing its trigger.
   */
  fun parse(json: JsonObject, strict: Boolean = true): TriggerSpec {
    var cooldown = (json["cooldown_ms"] as? JsonPrimitive)?.let { it.longOrNull ?: throw TriggerError("cooldown_ms must be an integer") }
      ?: TriggerSpec.DEFAULT_COOLDOWN_MS
    if (cooldown !in TriggerSpec.MIN_COOLDOWN_MS..86_400_000L) {
      if (strict) throw TriggerError("cooldown_ms must be between ${TriggerSpec.MIN_COOLDOWN_MS} and 86400000")
      cooldown = cooldown.coerceIn(TriggerSpec.MIN_COOLDOWN_MS, 86_400_000L)
    }
    var perHour = (json["max_runs_per_hour"] as? JsonPrimitive)?.let { it.intOrNull ?: throw TriggerError("max_runs_per_hour must be an integer") }
      ?: TriggerSpec.DEFAULT_MAX_PER_HOUR
    if (perHour !in 1..TriggerSpec.MAX_PER_HOUR) {
      if (strict) throw TriggerError("max_runs_per_hour must be between 1 and ${TriggerSpec.MAX_PER_HOUR}")
      perHour = perHour.coerceIn(1, TriggerSpec.MAX_PER_HOUR)
    }
    val ev = json["event"] as? JsonObject
    val sched = json["schedule"] as? JsonObject
    if ((ev == null) == (sched == null)) throw TriggerError("trigger needs exactly one of event or schedule")
    val t = if (ev != null) {
      val topic = (ev["topic"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
      if (topic.isEmpty() || !topic.matches(Regex("[a-z0-9_*]+(\\.[a-z0-9_*]+)*"))) throw TriggerError("event.topic must be a topic like messaging.sms_received (globs allowed)")
      // A workflow may not trigger on the engine's own announcements (run_started/run_finished): that is how two
      // workflows would chain or ping-pong with no event from the outside world at all. This refuses any glob that
      // could reach them, bare `*` included.
      if (AUTOMATION_TOPICS.any { dev.symbiosis.runtime.events.TopicGlob.matches(topic, it) } || topic.startsWith("automation")) {
        throw TriggerError("event.topic '$topic' would include automation.* topics, which cannot trigger workflows")
      }
      val where = when (val w = ev["where"]) {
        null -> null
        is JsonObject -> w.also { try { Matcher.validate(it, "where") } catch (e: MatcherError) { throw TriggerError(e.message ?: "bad where") } }
        else -> throw TriggerError("event.where must be an object")
      }
      Trigger.OnEvent(topic, where)
    } else {
      val s = sched!!
      val every = (s["every_minutes"] as? JsonPrimitive)?.intOrNull
      val daily = (s["daily_at"] as? JsonPrimitive)?.contentOrNull
      when {
        every != null && daily != null -> throw TriggerError("schedule takes every_minutes or daily_at, not both")
        every != null -> {
          if (every < MIN_EVERY_MINUTES) throw TriggerError("every_minutes must be at least $MIN_EVERY_MINUTES")
          if (every > 7 * 24 * 60) throw TriggerError("every_minutes must be at most a week")
          Trigger.Every(every)
        }
        daily != null -> {
          val time = try { LocalTime.parse(daily.trim().padStart(5, '0')) } catch (e: Exception) { throw TriggerError("daily_at must be HH:MM (24 h)") }
          val days = when (val d = s["days"]) {
            null -> null
            is JsonArray -> d.map { el ->
              val k = (el as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.take(3)
              DAYS[k] ?: throw TriggerError("days entries must be mon..sun (got $el)")
            }.toSet().also { if (it.isEmpty()) throw TriggerError("days is empty") }
            else -> throw TriggerError("days must be a list like [\"mon\",\"fri\"]")
          }
          Trigger.Daily(time.withSecond(0).withNano(0), days)
        }
        else -> throw TriggerError("schedule needs every_minutes or daily_at")
      }
    }
    return TriggerSpec(t, cooldown, perHour)
  }

  /** The next time a schedule is due strictly after [after]; null for event triggers. */
  fun next(spec: TriggerSpec, after: Long, zone: ZoneId = ZoneId.systemDefault()): Long? = when (val t = spec.trigger) {
    is Trigger.OnEvent -> null
    is Trigger.Every -> after + t.minutes * 60_000L
    is Trigger.Daily -> {
      val from = ZonedDateTime.ofInstant(Instant.ofEpochMilli(after), zone)
      var cand = from.with(t.time)
      if (!cand.isAfter(from)) cand = cand.plusDays(1)
      var guard = 0
      while (t.days != null && cand.dayOfWeek !in t.days && guard++ < 8) cand = cand.plusDays(1)
      // Re-apply the wall-clock time after day steps so a DST change in between does not shift it.
      cand.with(t.time).toInstant().toEpochMilli()
    }
  }

  /** Short human form for lists and the UI. */
  fun describe(spec: TriggerSpec): String = when (val t = spec.trigger) {
    is Trigger.OnEvent -> "on ${t.topic}" + (t.where?.let { " where ${Json.canonical(it)}" } ?: "")
    is Trigger.Every -> "every ${t.minutes} min"
    is Trigger.Daily -> "daily at ${t.time}" + (t.days?.let { d -> " on " + d.sortedBy { it.value }.joinToString(",") { it.name.take(3).lowercase() } } ?: "")
  }
}
