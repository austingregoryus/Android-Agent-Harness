package dev.symbiosis.runtime.automation

import dev.symbiosis.runtime.capabilities.CallContext
import dev.symbiosis.runtime.capabilities.Principal
import dev.symbiosis.runtime.core.Ids
import dev.symbiosis.runtime.core.Json
import dev.symbiosis.runtime.events.Event
import dev.symbiosis.runtime.events.EventBroker
import dev.symbiosis.runtime.events.TopicGlob
import dev.symbiosis.runtime.events.Topics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

/** Where the phone's alarm for the next scheduled run goes; null cancels it. The app uses AlarmManager (ScheduleReceiver.kt). */
fun interface AlarmSink { fun arm(atMs: Long?) }

/**
 * Runs saved workflows by themselves: on matching events and on schedules. Tasker-style, with the owner's
 * decisions (2026-09-26): **no approval step** — a workflow runs as the peer that saved it, with that peer's
 * current scopes, ceiling and rate limits, every step through the same ToolDispatch path as a direct call, every
 * run and step audited. Steps also re-resolve the owner between each other, so a revocation mid-run stops it.
 *
 * For each event, per workflow, in this order:
 *  1. **Topic and scope.** The trigger's topic glob must match, and the owner's *current* principal must exist and be
 *     allowed to see the topic. Failing either is silent (no log line): a logged skip would tell the owner, one bit
 *     at a time, which events on topics they cannot see matched their `where`.
 *  2. **Loop guard (global).** Every run gets its own correlation id, carried by every step and by every event a
 *     step causes *that carries a correlation id at all*. An event whose correlation id belongs to ANY recent
 *     workflow run triggers NO workflow — so two workflows cannot ping-pong through each other's direct effects.
 *     What it does NOT cover: events that arrive without a correlation id even though a run caused them — an inbound
 *     SMS or call (including the reply from another phone running its own workflow), notifications from other apps
 *     (a messaging app's notification for a text a step sent), battery and connectivity changes. Those loops are
 *     bounded by rules 5 and 6 below and, for texts, by the SMS rate limit (20/h). `automation.*` topics cannot be
 *     triggers at all (refused at save and ignored here), so run announcements cannot chain workflows either.
 *  3. **`where`** (regexes precompiled, input-capped, deadline-bounded; a timeout is a logged no-match).
 *  4. **Emergency stop** refuses to start anything (and the stop itself disables every workflow, see [disableAll]).
 *  5. **One run at a time.** While running, one trigger may queue; more are dropped (`skipped:busy`).
 *  6. **Cooldown** (default 10 s, floor 1 s) and **max runs per hour** (default 60, ceiling 120, counted from the
 *     persisted run log so it survives restarts): `skipped:cooldown`, `skipped:rate_cap`.
 * Skips go to the `workflow_runs` log, not the audit log: they did nothing, and a notification storm would
 * otherwise bury the audit log in non-events. Runs go to both.
 *
 * Events reach the engine through its OWN bounded queue (256, drop-oldest, drops logged), fed by a collector that
 * only enqueues: however slow matching gets, it can never back up the broker's shared live flow, which WebSocket
 * pushes and events.wait depend on.
 *
 * Revocation disables the owner's workflows and cancels their running runs; emergency stop and "Disable all" do the
 * same for every workflow.
 *
 * @param principalFor the owner's CURRENT principal (null if revoked/expired).
 * @param gate false while the runtime is switched off in the app: triggers are ignored and due schedules are
 *   skipped to their next occurrence (`skipped:runtime_off`).
 */
class WorkflowEngine(
  val store: WorkflowStore,
  private val broker: EventBroker,
  private val executor: AutomationExecutor,
  private val principalFor: (String) -> Principal?,
  private val emergencyStopped: () -> Boolean,
  private val alarms: AlarmSink,
  private val scope: CoroutineScope,
  private val audit: (peerId: String?, corr: String, args: JsonObject, result: JsonObject?, ok: Boolean?) -> Unit = { _, _, _, _, _ -> },
  private val gate: () -> Boolean = { true },
  private val clock: () -> Long = Ids::now,
) {
  /** What a trigger came to. [Run] carries the job so callers (and tests) can await it. */
  sealed class Decision {
    data class Run(val workflowId: String, val correlationId: String, val job: Job) : Decision()
    data class Queued(val workflowId: String) : Decision()
    data class Skipped(val workflowId: String, val reason: String) : Decision()
  }

  private class Slot(val owner: String) {
    var running = false
    var job: Job? = null
    var pending: Pair<String, Event?>? = null
    var lastStart = 0L
  }

  private val slots = HashMap<String, Slot>()
  /** Correlation ids of recent runs of ANY workflow: the global loop guard. Guarded by [slots]. */
  private val runCorrs = LinkedHashSet<String>()
  @Volatile private var eventWorkflows: List<Workflow> = emptyList()
  private var collector: Job? = null
  private var consumer: Job? = null
  private val dropped = AtomicLong()
  private val inbox = Channel<Event>(capacity = INBOX, onBufferOverflow = BufferOverflow.DROP_OLDEST) { ev ->
    val n = dropped.incrementAndGet()
    if (n == 1L || n % 100 == 0L) android.util.Log.w(TAG, "trigger queue full: dropped event ${ev.id} (${ev.topic}); $n dropped so far")
  }

  /** Subscribe to the event bus and arm schedules. Called once from Runtime.init (and so after every process start). */
  fun start() {
    reload()
    collector?.cancel(); consumer?.cancel()
    // The collector only enqueues (never blocks), so the broker's shared flow never waits on workflow matching.
    collector = scope.launch { broker.live.collect { inbox.trySend(it) } }
    consumer = scope.launch {
      for (ev in inbox) runCatching { onEvent(ev) }.onFailure { android.util.Log.w(TAG, "trigger evaluation failed for ${ev.topic}", it) }
    }
  }

  /** Re-read enabled triggered workflows and re-arm the schedule alarm. Call after any change to the store. */
  fun reload() {
    val all = store.enabledWithTriggers()
    eventWorkflows = all.filter { it.triggerSpec?.trigger is Trigger.OnEvent }
    rearm()
  }

  // ---------------------------------------------------------------- event triggers

  fun onEvent(ev: Event): List<Decision> {
    if (!gate()) return emptyList()
    // The engine's own announcements never trigger anything (save refuses such topics too).
    if (ev.topic.startsWith(AUTOMATION_PREFIX)) return emptyList()
    val guarded = ev.correlation_id?.let { c -> synchronized(slots) { c in runCorrs } } == true
    val owners = HashMap<String, Principal?>()
    val out = mutableListOf<Decision>()
    for (wf in eventWorkflows) {
      val t = wf.triggerSpec?.trigger as? Trigger.OnEvent ?: continue
      if (!TopicGlob.matches(t.topic, ev.topic)) continue
      // Scope before anything observable: silent, and before where/cooldown/slot so nothing leaks or is consumed.
      val owner = owners.getOrPut(wf.owner) { principalFor(wf.owner) } ?: continue
      if (!owner.canSeeTopic(ev.topic)) continue
      if (guarded) { out += skip(wf, "event:${ev.topic}", "loop_guard", ev, detail = "event caused by workflow run ${ev.correlation_id}"); continue }
      val matched = try { t.where == null || Matcher.where(t.where, ev.payload) } catch (e: RegexTimeout) {
        out += skip(wf, "event:${ev.topic}", "regex_timeout", ev, detail = e.message); continue
      }
      if (!matched) continue
      out += fire(wf, "event:${ev.topic}", ev)
    }
    return out
  }

  // ---------------------------------------------------------------- schedules

  /** A schedule missed by this much (phone off, process dead) is skipped to the next occurrence, not run late. */
  val missedGraceMs = 15 * 60_000L

  /** The alarm fired: run what is due, then arm the next alarm. Every due schedule advances, run or not. */
  fun onAlarm(): List<Decision> {
    val now = clock()
    val on = gate()
    val out = mutableListOf<Decision>()
    for (wf in store.enabledWithTriggers()) {
      val spec = wf.triggerSpec ?: continue
      if (spec.trigger is Trigger.OnEvent) continue
      val due = wf.nextRunAt ?: continue
      // Inexact alarms may arrive early; a minute of slack treats "nearly due" as due rather than re-arming for seconds.
      if (due > now + 60_000L) continue
      // Advance first, whatever happens next: an un-advanced due time re-arms for the past and the alarm spins.
      store.setNextRun(wf.id, Triggers.next(spec, maxOf(now, due)))
      when {
        !on -> out += skip(wf, "schedule", "runtime_off", null, detail = "due at $due")
        due < now - missedGraceMs -> store.log(wf.id, now, "schedule", "missed", detail = "was due ${(now - due) / 60_000} min ago; next occurrence instead")
        else -> out += fire(wf, "schedule", null)
      }
    }
    rearm()
    return out
  }

  /** The clock or time zone changed: every schedule's next time is recomputed from now (TimeChangeReceiver). */
  fun recomputeSchedules() {
    val now = clock()
    for (wf in store.enabledWithTriggers()) {
      val spec = wf.triggerSpec ?: continue
      if (spec.trigger is Trigger.OnEvent) continue
      store.setNextRun(wf.id, Triggers.next(spec, now))
    }
    rearm()
  }

  private fun rearm() {
    val now = clock()
    var earliest: Long? = null
    for (wf in store.enabledWithTriggers()) {
      val spec = wf.triggerSpec ?: continue
      if (spec.trigger is Trigger.OnEvent) continue
      var next = wf.nextRunAt
      if (next == null) next = Triggers.next(spec, now)
      else if (next < now - missedGraceMs) {
        store.log(wf.id, now, "schedule", "missed", detail = "was due ${(now - next) / 60_000} min ago (phone off or runtime stopped); next occurrence instead")
        next = Triggers.next(spec, now)
      }
      if (next != wf.nextRunAt) store.setNextRun(wf.id, next)
      if (next != null && (earliest == null || next < earliest)) earliest = next
    }
    alarms.arm(earliest)
  }

  // ---------------------------------------------------------------- the rules

  /** E-stop, busy/queue, cooldown and rate cap, then start a run. (Scope and loop guard are applied by the caller.) */
  fun fire(wf: Workflow, origin: String, ev: Event?): Decision {
    val spec = wf.triggerSpec ?: TriggerSpec(Trigger.Every(Triggers.MIN_EVERY_MINUTES))
    val now = clock()
    val corr: String
    val job: Job
    synchronized(slots) {
      val s = slots.getOrPut(wf.id) { Slot(wf.owner) }
      if (emergencyStopped()) return skip(wf, origin, "emergency_stop", ev)
      if (s.running) {
        if (s.pending == null) {
          s.pending = origin to ev
          store.log(wf.id, now, origin, "queued", detail = ev?.let { "event ${it.id}" })
          return Decision.Queued(wf.id)
        }
        return skip(wf, origin, "busy", ev)
      }
      if (s.lastStart > 0 && now - s.lastStart < spec.cooldownMs) return skip(wf, origin, "cooldown", ev)
      if (store.triggeredRunsSince(wf.id, now - 3_600_000L) >= spec.maxRunsPerHour) return skip(wf, origin, "rate_cap", ev)
      corr = claim(s, now)
      job = scope.launch(start = CoroutineStart.LAZY) { try { execute(wf, origin, ev, JsonObject(emptyMap()), corr) } finally { finished(wf) } }
      s.job = job
    }
    job.start()
    return Decision.Run(wf.id, corr, job)
  }

  /** `automation.run_saved`: no cooldown or hourly cap (it was asked for), but still one run at a time. */
  suspend fun runManual(wf: Workflow, input: JsonObject): Pair<String, AutomationExecutor.Report?>? {
    val corr: String
    val run = synchronized(slots) {
      val s = slots.getOrPut(wf.id) { Slot(wf.owner) }
      if (s.running) return null
      corr = claim(s, clock())
      // Its own job (not the caller's), so revocation / e-stop can cancel it like a triggered run.
      scope.async(start = CoroutineStart.LAZY) { try { execute(wf, "manual", null, input, corr) } finally { finished(wf) } }.also { s.job = it }
    }
    return corr to run.await()
  }

  private fun claim(s: Slot, now: Long): String {
    s.running = true; s.lastStart = now
    val corr = "corr_wf_" + Ids.hex(8)
    runCorrs.add(corr)
    while (runCorrs.size > MAX_GUARDED_RUNS) runCorrs.remove(runCorrs.first())
    return corr
  }

  private fun finished(wf: Workflow) {
    val next = synchronized(slots) {
      val s = slots[wf.id] ?: return
      s.running = false; s.job = null
      s.pending.also { s.pending = null }?.let { it to (s.lastStart + (wf.triggerSpec?.cooldownMs ?: 0) - clock()) }
    } ?: return
    val (p, wait) = next
    scope.launch {
      if (wait > 0) delay(wait)
      // Re-read: the workflow may have been disabled, edited or deleted while this run waited.
      val fresh = store.getById(wf.id)?.takeIf { it.enabled } ?: return@launch
      val owner = principalFor(fresh.owner) ?: return@launch
      if (p.second != null && !owner.canSeeTopic(p.second!!.topic)) return@launch
      fire(fresh, p.first, p.second)
    }
  }

  private fun skip(wf: Workflow, origin: String, reason: String, ev: Event?, detail: String? = null): Decision.Skipped {
    store.log(wf.id, clock(), origin, "skipped:$reason", ev?.correlation_id, detail ?: ev?.let { "event ${it.id}" })
    return Decision.Skipped(wf.id, reason)
  }

  /** One run, start to finish: resolve the owner, announce, execute through the dispatch path, record, announce. */
  private suspend fun execute(wf: Workflow, origin: String, ev: Event?, input: JsonObject, corr: String): AutomationExecutor.Report? {
    val t0 = clock()
    val owner = principalFor(wf.owner)
    if (owner == null) {
      store.setEnabled(wf.id, false, "owner_unavailable"); reload()
      store.log(wf.id, t0, origin, "skipped:owner_unavailable", corr)
      return null
    }
    val batch = try {
      Batches.parse(mapOf("steps" to Json.fromJson(wf.steps), "timeout_ms" to wf.timeoutMs, "input" to Json.fromJson(input)))
        .copy(event = ev?.let { eventJson(it) })
    } catch (e: BatchError) {
      store.log(wf.id, t0, origin, "failed", corr, "invalid steps: ${e.message}")
      return null
    }
    store.log(wf.id, t0, origin, "started", corr, ev?.let { "event ${it.id}" })
    val announce = mapOf("workflow_id" to wf.id, "name" to wf.name, "owner" to wf.owner, "trigger" to origin, "event_id" to ev?.id)
    broker.emit(Topics.AUTOMATION_RUN_STARTED, announce, corr)
    val args = buildJsonObject { put("workflow_id", wf.id); put("name", wf.name); put("trigger", origin); ev?.let { put("event_id", it.id) } }
    audit(wf.owner, corr, args, null, null)

    val report = try {
      executor.run(batch, owner, CallContext(owner.peerId, corr, principal = owner))
    } catch (e: CancellationException) {
      // Killed by revocation, emergency stop or Disable all: still record it, then let the cancellation through.
      withContext(NonCancellable) {
        val summary = buildJsonObject { put("ok", false); put("stop_reason", "cancelled"); put("trigger", origin); put("correlation_id", corr); put("at", t0) }
        runCatching {
          store.recordRun(wf.id, t0, summary)
          store.log(wf.id, clock(), origin, "failed", corr, "cancelled")
          broker.emit(Topics.AUTOMATION_RUN_FINISHED, announce + mapOf("ok" to false, "stop_reason" to "cancelled"), corr)
          audit(wf.owner, corr, args, summary, false)
        }
      }
      throw e
    }
    val ok = report.completed && report.steps.none { !it.ok }
    val summary = buildJsonObject {
      put("ok", ok); put("completed", report.completed); put("stopped_at", report.stoppedAt); put("stop_reason", report.stopReason)
      put("failed", report.steps.count { !it.ok }); put("ms", report.ms); put("trigger", origin); put("correlation_id", corr)
      put("at", t0)
    }
    store.recordRun(wf.id, t0, summary)
    store.log(wf.id, clock(), origin, if (ok) "ok" else "failed", corr, report.steps.firstOrNull { !it.ok }?.let { "${it.id}: ${it.error}" })
    broker.emit(Topics.AUTOMATION_RUN_FINISHED, announce + mapOf("ok" to ok, "stopped_at" to report.stoppedAt, "stop_reason" to report.stopReason, "ms" to report.ms), corr)
    audit(wf.owner, corr, args, Json.toJson(report.toMap()) as JsonObject, ok)
    return report
  }

  // ---------------------------------------------------------------- control

  /** Emergency stop, or the app's "Disable all": every workflow off, queued runs dropped, running runs cancelled. */
  fun disableAll(reason: String): Int {
    val n = store.disableAll(reason)
    cancelRuns { true }
    reload()
    return n
  }

  /** Revocation: the peer's workflows off (kept), its queued and running runs cancelled. */
  fun onOwnerRevoked(peerId: String): Int {
    val n = store.disableOwnedBy(peerId, "owner_revoked")
    cancelRuns { it.owner == peerId }
    reload()
    return n
  }

  private fun cancelRuns(which: (Slot) -> Boolean) {
    val jobs = synchronized(slots) { slots.values.filter(which).mapNotNull { s -> s.pending = null; s.job } }
    jobs.forEach { it.cancel(CancellationException("workflow cancelled")) }
  }

  fun ownerUnavailable(peerId: String): Boolean = principalFor(peerId) == null

  fun isRunning(id: String): Boolean = synchronized(slots) { slots[id]?.running == true }

  private fun eventJson(ev: Event): JsonObject = buildJsonObject {
    put("id", ev.id); put("topic", ev.topic); put("ts", ev.ts); put("correlation_id", ev.correlation_id); put("payload", ev.payload)
  }

  companion object {
    private const val TAG = "WorkflowEngine"
    const val INBOX = 256
    const val MAX_GUARDED_RUNS = 512
    const val AUTOMATION_PREFIX = "automation."

    /** Checks run input against a saved `inputs_schema` (required names and simple JSON types); null when fine. */
    fun checkInput(schema: JsonObject?, input: JsonObject): String? {
      if (schema == null) return null
      val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: emptyList()
      for (r in required) if (input[r] == null || input[r] is kotlinx.serialization.json.JsonNull) return "input.$r is required"
      val props = schema["properties"] as? JsonObject ?: return null
      for ((k, v) in input) {
        val type = ((props[k] as? JsonObject)?.get("type") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
        val ok = when (type) {
          "string" -> v is kotlinx.serialization.json.JsonPrimitive && v.isString
          "number" -> (v as? kotlinx.serialization.json.JsonPrimitive)?.let { !it.isString && it.content.toDoubleOrNull() != null } == true
          "integer" -> (v as? kotlinx.serialization.json.JsonPrimitive)?.let { !it.isString && it.content.toLongOrNull() != null } == true
          "boolean" -> (v as? kotlinx.serialization.json.JsonPrimitive)?.let { !it.isString && it.content.toBooleanStrictOrNull() != null } == true
          "object" -> v is JsonObject
          "array" -> v is JsonArray
          else -> true
        }
        if (!ok) return "input.$k must be $type"
      }
      return null
    }
  }
}
