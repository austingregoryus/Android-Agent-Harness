package dev.symbiosis.runtime.automation

import dev.symbiosis.runtime.capabilities.CallContext
import dev.symbiosis.runtime.capabilities.Principal
import dev.symbiosis.runtime.core.DispatchOutcome
import dev.symbiosis.runtime.core.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Calls one tool by name as a given caller. In the app this is `Runtime.dispatch` — the same entry point
 * McpHandler uses for a direct tools/call (core/ToolDispatch.kt) — and nothing else: an executor that could
 * reach a capability any other way would be a policy bypass.
 */
fun interface StepDispatcher {
  suspend fun dispatch(principal: Principal, tool: String, args: Map<String, Any?>, ctx: CallContext): DispatchOutcome
}

/**
 * Runs a [Batch] step by step. Built as the core of the later workflow engine (saved macros, event triggers):
 * it knows nothing about MCP, where the batch came from, or who will read the report — a trigger can hand it a
 * Batch and an owner principal exactly as automation.run does.
 *
 * Every step is a separate dispatch with the caller's own principal, so each is scope-checked, argument-checked,
 * policed (emergency stop, observe-only, overrides, denylist, quiet hours, approvals), rate-limited against its
 * own rate class and audited with its own intent/decision/outcome rows, under the batch's correlation id. The
 * batch adds limits; it never removes one.
 *
 * [stepScope] runs each step's dispatch detached from the caller, so the batch deadline is a real deadline:
 * capabilities are blocking code on Dispatchers.IO and do not all cooperate with cancellation, so a withTimeout
 * around the call itself would wait for a stuck step to finish anyway. On timeout the batch returns and the step's
 * job is cancelled: cooperative work (delays, waits, suspending I/O) stops there; blocking work stops when it next
 * checks, and its outcome still lands in the audit log (the report says so). If the batch itself is cancelled (a
 * workflow run killed by revocation or emergency stop), the running step is cancelled the same way.
 *
 * @param refresh re-resolves the caller before EACH step: a peer revoked or expired mid-batch stops the batch
 *   (`stop_reason: "revoked"`), and scopes narrowed mid-batch apply from the next step. Null = gone.
 */
class AutomationExecutor(
  private val dispatcher: StepDispatcher,
  private val stepScope: CoroutineScope,
  private val clock: () -> Long = System::currentTimeMillis,
  private val refresh: (Principal) -> Principal? = { it },
) {
  data class StepReport(
    val id: String,
    val tool: String,
    val ok: Boolean,
    /** structuredContent of a successful step. */
    val result: JsonObject? = null,
    val error: String? = null,
    val detail: String? = null,
    /** The rest of a failed step's structuredContent: pending_action_id, retry_after_ms, permissions… */
    val errorData: JsonObject? = null,
    val ms: Long,
    val auditId: Long? = null,
    /** The step's `if` was false: not run, not a failure. */
    val skipped: Boolean = false,
  ) {
    /** The object `{{steps.<id>...}}` resolves against. */
    fun templateView(): JsonObject = buildJsonObject {
      put("ok", ok); put("ms", ms)
      if (skipped) put("skipped", true)
      result?.let { put("result", it) }
      error?.let { put("error", it) }
    }

    fun toMap(): Map<String, Any?> = buildMap {
      put("id", id); put("tool", tool); put("ok", ok)
      if (skipped) { put("skipped", true); detail?.let { put("detail", it) } }
      else if (ok) put("result", Json.fromJson(result))
      else { put("error", error); detail?.let { put("detail", it) }; errorData?.takeIf { it.isNotEmpty() }?.let { put("data", Json.fromJson(it)) } }
      put("ms", ms)
      auditId?.let { put("audit_id", it) }
    }
  }

  data class Report(
    val steps: List<StepReport>,
    /** Every step ran (a failed `on_error: continue` step still counts as run). */
    val completed: Boolean,
    val stoppedAt: String?,
    /** `step_failed`, `timeout` or `revoked` when not [completed]. */
    val stopReason: String?,
    val notRun: List<String>,
    val ms: Long,
    val timeoutMs: Long,
  ) {
    fun toMap(): Map<String, Any?> = mapOf(
      "completed" to completed, "stopped_at" to stoppedAt, "stop_reason" to stopReason,
      "steps" to steps.map { it.toMap() }, "not_run" to notRun,
      "failed" to steps.count { !it.ok }, "ms" to ms, "timeout_ms" to timeoutMs,
    )
  }

  suspend fun run(batch: Batch, principal: Principal, parent: CallContext): Report {
    val start = clock()
    val deadline = start + batch.timeoutMs
    val done = mutableListOf<StepReport>()
    val views = LinkedHashMap<String, JsonObject>()
    var stoppedAt: String? = null
    var stopReason: String? = null

    for ((index, step) in batch.steps.withIndex()) {
      if (step.waitMs > 0) {
        val remaining = deadline - clock()
        // Known in advance to overrun: fail now rather than sleep until the deadline to say so.
        if (step.waitMs >= remaining) {
          done += StepReport(step.id, step.tool, ok = false, error = TIMEOUT, detail = "wait_ms ${step.waitMs} does not fit in the ${maxOf(remaining, 0)} ms left before the batch deadline", ms = 0)
          stoppedAt = step.id; stopReason = "timeout"; break
        }
        delay(step.waitMs)
      }
      val remaining = deadline - clock()
      if (remaining <= 0) {
        done += StepReport(step.id, step.tool, ok = false, error = TIMEOUT, detail = "batch deadline reached before this step started", ms = 0)
        stoppedAt = step.id; stopReason = "timeout"; break
      }
      // Who is asking, as of now: revocation and expiry take effect between steps, not at the end of the batch.
      val current = refresh(principal)
      if (current == null) {
        done += StepReport(step.id, step.tool, ok = false, error = "automation.revoked", detail = "the calling peer was revoked or expired during the batch", ms = 0)
        stoppedAt = step.id; stopReason = "revoked"; break
      }

      val t0 = clock()
      val report = runStep(index, step, current, parent, Templates.Scope(batch.input, views, batch.event), remaining)
      if (report == null) {
        done += StepReport(
          step.id, step.tool, ok = false, error = TIMEOUT, ms = clock() - t0,
          detail = "batch deadline reached while this step was running; it was cancelled, but blocking work may still complete, and its outcome will be in the audit log under this correlation id",
        )
        stoppedAt = step.id; stopReason = "timeout"; break
      }

      done += report
      views[step.id] = report.templateView()
      if (!report.ok && step.onError == OnError.STOP) { stoppedAt = step.id; stopReason = "step_failed"; break }
    }

    val ran = done.map { it.id }.toSet()
    return Report(
      steps = done,
      completed = stopReason == null,
      stoppedAt = stoppedAt,
      stopReason = stopReason,
      notRun = batch.steps.map { it.id }.filter { it !in ran },
      ms = clock() - start,
      timeoutMs = batch.timeoutMs,
    )
  }

  /** One step; null means the batch deadline passed while it ran. */
  private suspend fun runStep(index: Int, step: Step, principal: Principal, parent: CallContext, refs: Templates.Scope, remaining: Long): StepReport? {
    val t0 = clock()
    step.condition?.let { cond ->
      val holds = try {
        cond.all { (ref, test) -> Matcher.test(Templates.resolveString(ref, refs), test) }
      } catch (e: TemplateError) {
        return StepReport(step.id, step.tool, ok = false, error = "automation.template", detail = "if: ${e.message}", ms = clock() - t0)
      } catch (e: RegexTimeout) {
        // A runaway regex counts as "does not match": the step is skipped, and the report says why.
        return StepReport(step.id, step.tool, ok = true, skipped = true, detail = "if: ${e.message}", ms = clock() - t0)
      }
      if (!holds) return StepReport(step.id, step.tool, ok = true, skipped = true, ms = clock() - t0)
    }
    val args = try {
      Templates.resolve(step.args, refs) as JsonObject
    } catch (e: TemplateError) {
      return StepReport(step.id, step.tool, ok = false, error = "automation.template", detail = e.message, ms = clock() - t0)
    }
    val ctx = CallContext(
      peerId = parent.peerId,
      // One correlation id for the batch and all its steps: the audit log reads as one causal chain.
      correlationId = parent.correlationId,
      // A retried batch (same outer key) replays each step that already succeeded instead of re-running it —
      // the difference between "retry after a network blip" and "send the text twice". No outer key, no step keys.
      // Keyed by id AND index, and the replay itself is bound to tool + args (AuditStore.findOutcome), so an edited
      // batch retried under the same key can never get another step's result back.
      idempotencyKey = parent.idempotencyKey?.let { "$it#${step.id}@$index" },
      approvalWaitMs = parent.approvalWaitMs,
      principal = principal,
      inAutomation = true,
    )
    val job = stepScope.async { dispatcher.dispatch(principal, step.tool, Json.toMap(args), ctx) }
    val outcome = try {
      withTimeoutOrNull(remaining) { job.await() } ?: run { job.cancel(); return null }
    } catch (e: CancellationException) {
      job.cancel() // the batch itself was cancelled: do not leave its step running detached
      throw e
    } catch (t: Throwable) {
      return StepReport(step.id, step.tool, ok = false, error = "capability.failed", detail = t.message ?: t.javaClass.simpleName, ms = clock() - t0)
    }
    val ms = clock() - t0
    return when (outcome) {
      is DispatchOutcome.RpcError -> StepReport(
        step.id, step.tool, ok = false, ms = ms,
        // -32601 covers both "no such tool" and "not visible to this caller", exactly as for a direct call.
        error = if (outcome.code == -32601) "tool.unknown" else "invalid_params",
        detail = outcome.message,
      )
      is DispatchOutcome.Result -> {
        val body = outcome.body
        val isError = body["isError"]?.jsonPrimitive?.contentOrNull == "true"
        val structured = body["structuredContent"] as? JsonObject ?: JsonObject(emptyMap())
        val auditId = ((body["_meta"] as? JsonObject)?.get("auditId") as? JsonPrimitive)?.longOrNull
        if (!isError) StepReport(step.id, step.tool, ok = true, result = structured, ms = ms, auditId = auditId)
        else StepReport(
          step.id, step.tool, ok = false, ms = ms, auditId = auditId,
          error = structured.string("error") ?: "capability.failed",
          detail = structured.string("detail"),
          errorData = JsonObject(structured.filterKeys { it != "error" && it != "detail" }),
        )
      }
    }
  }

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.contentOrNull

  companion object {
    const val TIMEOUT = "automation.timeout"
  }
}
