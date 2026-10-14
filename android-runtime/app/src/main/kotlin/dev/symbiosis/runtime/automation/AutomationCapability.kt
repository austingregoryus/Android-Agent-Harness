package dev.symbiosis.runtime.automation

import dev.symbiosis.runtime.capabilities.Capability
import dev.symbiosis.runtime.capabilities.CapabilityResult
import dev.symbiosis.runtime.capabilities.Param
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.policy.RateClass
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `automation.run`: a registered capability like any other, so it is listed by tools/list (and so by the gateway,
 * which passes the phone's tool list through), scope-gated by `automation.*`, policed as its own action class and
 * rate class, and audited as one intent/outcome pair — around the steps' own rows.
 */
object AutomationCapability {
  const val NAME = "automation.run"

  private val STEP_SCHEMA = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
      put("id", buildJsonObject { put("type", "string"); put("description", "Name for {{steps.<id>...}} references (default: the step's 0-based index)") })
      put("tool", buildJsonObject { put("type", "string"); put("description", "Tool name, e.g. contacts.search. automation.* is not allowed.") })
      put("args", buildJsonObject { put("type", "object"); put("description", "Arguments; string values may contain {{steps.<id>.result.<path>}} and {{input.<name>}}") })
      put("on_error", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf(JsonPrimitive("stop"), JsonPrimitive("continue")))); put("description", "stop (default) or continue") })
      put("wait_ms", buildJsonObject { put("type", "integer"); put("description", "Pause before this step, counted against the batch timeout") })
      put("if", buildJsonObject { put("type", "object"); put("description", "Run only if this holds, e.g. {\"{{steps.x.result.json.action}}\": {\"equals\": \"reply\"}}; ops equals/contains/regex/in/lt/gt. False = step skipped.") })
    })
    put("required", JsonArray(listOf(JsonPrimitive("tool"))))
  }

  fun capability(executor: AutomationExecutor): Capability = Capability(
    name = NAME,
    description = "Run up to ${Batches.MAX_STEPS} tool calls in order, in one round trip. Each step is checked exactly like a direct call " +
      "from you (scope, policy, rate limits, audit); a step you could not call directly fails here too. Step args can reference earlier " +
      "results: a string that is exactly \"{{steps.<id>.result.<path>}}\" keeps the value's JSON type, otherwise it is text-substituted " +
      "(arrays: items.0.x or items[0].x); {{input.<name>}} reads the optional input object. A missing reference fails that step. " +
      "on_error stop (default) ends the batch at a failed step; continue moves on. A step's optional `if` skips it when false. timeout_ms bounds the whole batch " +
      "(default ${Batches.DEFAULT_TIMEOUT_MS / 1000} s, max ${Batches.MAX_TIMEOUT_MS / 1000} s). Returns per-step {id, tool, ok, result|error, ms} " +
      "and stopped_at / stop_reason when it did not complete.",
    params = listOf(
      Param("steps", "array", "The steps, run sequentially", required = true, items = STEP_SCHEMA),
      Param("timeout_ms", "integer", "Whole-batch deadline in ms (default ${Batches.DEFAULT_TIMEOUT_MS}, max ${Batches.MAX_TIMEOUT_MS})"),
      Param("input", "object", "Values for {{input.<name>}} references"),
    ),
    actionClass = ActionClass.AUTOMATION,
    rateClass = RateClass.AUTOMATION,
    tier = 0,
    // Not read-only: its steps may act. Declared explicitly because AUTOMATION ranks below SENSITIVE_READ and the
    // default would otherwise mark it readOnlyHint — and the gateway sends no idempotency key for read-only tools.
    readOnly = false,
  ) { args, ctx ->
    if (ctx.inAutomation) return@Capability CapabilityResult.fail("automation.nested", "automation.run cannot run inside a batch")
    val principal = ctx.principal ?: return@Capability CapabilityResult.fail("unauthorized", "no caller identity for the batch's steps")
    val batch = try { Batches.parse(args) } catch (e: BatchError) { return@Capability CapabilityResult.fail("invalid_params", e.message) }
    val report = executor.run(batch, principal, ctx)
    val data = report.toMap()
    when (report.stopReason) {
      null -> CapabilityResult.ok(data)
      "timeout" -> CapabilityResult.fail(AutomationExecutor.TIMEOUT, "batch deadline (${batch.timeoutMs} ms) reached at step '${report.stoppedAt}'", data)
      "revoked" -> CapabilityResult.fail("automation.revoked", "the caller was revoked or expired before step '${report.stoppedAt}'", data)
      else -> {
        val failed = report.steps.last()
        CapabilityResult.fail("automation.step_failed", "step '${failed.id}' (${failed.tool}) failed: ${failed.error}${failed.detail?.let { " - $it" } ?: ""}", data)
      }
    }
  }
}
