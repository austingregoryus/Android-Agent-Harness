package dev.symbiosis.runtime.automation

import dev.symbiosis.runtime.capabilities.CallContext
import dev.symbiosis.runtime.capabilities.Capability
import dev.symbiosis.runtime.capabilities.CapabilityResult
import dev.symbiosis.runtime.capabilities.Param
import dev.symbiosis.runtime.capabilities.Principal
import dev.symbiosis.runtime.core.Json
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.policy.RateClass
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * `automation.save / list / get / delete / enable / run_saved`.
 *
 * All AUTOMATION class, like automation.run: they manage and start batches, and every step still runs as a
 * separate call with the owner's own authority. Mutating ones (save/delete/enable/run_saved) take the
 * `automation` rate class. Ownership: a workflow belongs to the peer that saved it; a peer sees and manages only
 * its own (a name is (owner, name)); the admin token sees all and may pass `owner` to address another peer's.
 * A saved workflow may not call `automation.*` at all (not even run_saved) — Batches.parse refuses it, so
 * workflows can never start each other and a trigger can never fan out into a tree of runs.
 */
object WorkflowCapabilities {
  private val NAME = Regex("^[A-Za-z0-9 _.-]{1,64}$")

  /** @param canSee whether a principal can currently see a tool (for save-time warnings only; runs re-check). */
  fun all(engine: WorkflowEngine, canSee: (Principal, String) -> Boolean): List<Capability> {
    val store = engine.store
    val ownerParam = Param("owner", "string", "Admin only: the owning peer_id (default: the caller)")

    fun owner(args: Map<String, Any?>, ctx: CallContext): String? {
      val p = ctx.principal ?: return null
      val asked = args["owner"]?.toString()?.takeIf { it.isNotBlank() }
      return if (p.isAdmin && asked != null) asked else p.peerId
    }

    fun find(args: Map<String, Any?>, ctx: CallContext): Pair<Workflow?, CapabilityResult?> {
      val o = owner(args, ctx) ?: return null to CapabilityResult.fail("unauthorized", "no caller identity")
      val name = args["name"]?.toString()?.trim().orEmpty()
      // Not-found and not-yours are the same answer: a peer cannot probe for another's workflow names.
      val wf = store.get(o, name) ?: return null to CapabilityResult.fail("automation.not_found", "no workflow named '$name'")
      return wf to null
    }

    return listOf(
      Capability(
        name = "automation.save",
        description = "Save (create or replace) a named workflow: steps in automation.run's format (plus an optional `if` per step), " +
          "optional inputs_schema for run_saved input, and an optional trigger — {event:{topic, where?}}, {schedule:{every_minutes:N}} (N>=15) " +
          "or {schedule:{daily_at:\"HH:MM\", days?:[\"mon\",...]}}, with optional cooldown_ms (default 10000, min 1000) and max_runs_per_hour (default 60, max 120); automation.* topics cannot be triggers. " +
          "A triggered workflow runs by itself, without approval, as you: your scopes, ceiling and rate limits. Steps may not call automation.*. See docs/automation.md.",
        params = listOf(
          Param("name", "string", "Unique among your workflows: letters, digits, space, _ . - (max 64)", required = true),
          Param("description", "string", "What it is for"),
          Param("steps", "array", "Steps, as for automation.run", required = true),
          Param("inputs_schema", "object", "JSON Schema subset for run_saved input: {required:[...], properties:{name:{type}}}"),
          Param("trigger", "object", "When it runs by itself (omit for run_saved only)"),
          Param("enabled", "boolean", "Default true"),
          Param("timeout_ms", "integer", "Per-run deadline (default 60000, max 300000)"),
        ),
        actionClass = ActionClass.AUTOMATION, rateClass = RateClass.AUTOMATION, tier = 0, readOnly = false,
      ) { args, ctx ->
        val p = ctx.principal ?: return@Capability CapabilityResult.fail("unauthorized", "no caller identity")
        if (ctx.inAutomation) return@Capability CapabilityResult.fail("automation.nested", "workflows cannot be saved from inside a batch")
        val name = args["name"]?.toString()?.trim().orEmpty()
        if (!NAME.matches(name)) return@Capability CapabilityResult.fail("invalid_params", "name must be 1-64 of letters, digits, space, _ . -")
        val batch = try { Batches.parse(mapOf("steps" to args["steps"], "timeout_ms" to args["timeout_ms"])) }
        catch (e: BatchError) { return@Capability CapabilityResult.fail("invalid_params", e.message) }
        val trigger = when (val t = args["trigger"]) {
          null -> null
          is Map<*, *> -> Json.toJson(t) as JsonObject
          else -> return@Capability CapabilityResult.fail("invalid_params", "trigger must be an object")
        }
        val spec = try { trigger?.let { Triggers.parse(it) } } catch (e: TriggerError) { return@Capability CapabilityResult.fail("invalid_params", "trigger: ${e.message}") }
        (spec?.trigger as? Trigger.OnEvent)?.let { t ->
          // A trigger hands the event's payload to the steps: it is a subscription, and gated like one.
          if (!p.canSubscribe(t.topic)) return@Capability CapabilityResult.fail("policy.denied", "trigger topic '${t.topic}' is outside your scopes")
        }
        val schema = when (val s = args["inputs_schema"]) { null -> null; is Map<*, *> -> Json.toJson(s) as JsonObject; else -> return@Capability CapabilityResult.fail("invalid_params", "inputs_schema must be an object") }
        val (wf, created) = store.upsert(
          p.peerId, name, args["description"]?.toString()?.take(500), Json.toJson(args["steps"]) as JsonArray,
          schema, trigger, batch.timeoutMs, args["enabled"] != false,
        )
        engine.reload()
        val warnings = batch.steps.filter { !canSee(p, it.tool) }.map { "step '${it.id}': ${it.tool} is not available to you; that step will fail as tool.unknown" }
        CapabilityResult.ok(mapOf("created" to created, "workflow" to store.getById(wf.id)!!.summary(), "warnings" to warnings))
      },

      Capability(
        name = "automation.list",
        description = "Your saved workflows: name, trigger, enabled, run count, last run and its result (the admin sees every peer's).",
        params = listOf(ownerParam),
        actionClass = ActionClass.AUTOMATION, tier = 0,
      ) { args, ctx ->
        val p = ctx.principal ?: return@Capability CapabilityResult.fail("unauthorized", "no caller identity")
        val o = if (p.isAdmin) args["owner"]?.toString()?.takeIf { it.isNotBlank() } else p.peerId
        CapabilityResult.ok(mapOf("workflows" to store.list(o).map { it.summary() }))
      },

      Capability(
        name = "automation.get",
        description = "One saved workflow in full (steps, trigger, last result) plus its recent run log, including skipped triggers.",
        params = listOf(Param("name", "string", "Workflow name", required = true), ownerParam),
        actionClass = ActionClass.AUTOMATION, tier = 0,
      ) { args, ctx ->
        val (wf, err) = find(args, ctx); if (err != null) return@Capability err
        CapabilityResult.ok(wf!!.full() + mapOf("running" to engine.isRunning(wf.id), "runs" to store.recentLog(wf.id)))
      },

      Capability(
        name = "automation.delete",
        description = "Delete a saved workflow and its run log (the audit log keeps what its runs did).",
        params = listOf(Param("name", "string", "Workflow name", required = true), ownerParam),
        actionClass = ActionClass.AUTOMATION, rateClass = RateClass.AUTOMATION, tier = 0, readOnly = false,
      ) { args, ctx ->
        val (wf, err) = find(args, ctx); if (err != null) return@Capability err
        store.delete(wf!!.owner, wf.name)
        engine.reload()
        CapabilityResult.ok(mapOf("deleted" to wf.name))
      },

      Capability(
        name = "automation.enable",
        description = "Turn a saved workflow's trigger on or off (a disabled workflow can still be run with run_saved).",
        params = listOf(Param("name", "string", "Workflow name", required = true), Param("enabled", "boolean", "true to enable", required = true), ownerParam),
        actionClass = ActionClass.AUTOMATION, rateClass = RateClass.AUTOMATION, tier = 0, readOnly = false,
      ) { args, ctx ->
        val (wf, err) = find(args, ctx); if (err != null) return@Capability err
        val on = args["enabled"] == true
        if (on && engine.ownerUnavailable(wf!!.owner)) return@Capability CapabilityResult.fail("automation.owner_unavailable", "the owning peer is revoked or expired")
        store.setEnabled(wf!!.id, on, if (on) null else "disabled_by_${ctx.peerId ?: "unknown"}")
        engine.reload()
        CapabilityResult.ok(mapOf("name" to wf.name, "enabled" to on))
      },

      Capability(
        name = "automation.run_saved",
        description = "Run a saved workflow now with {{input.x}} bound from input; waits for it and returns the same per-step report as automation.run. " +
          "Runs as the workflow's owner. Fails with automation.busy if it is already running.",
        params = listOf(Param("name", "string", "Workflow name", required = true), Param("input", "object", "Values for {{input.<name>}}"), ownerParam),
        actionClass = ActionClass.AUTOMATION, rateClass = RateClass.AUTOMATION, tier = 0, readOnly = false,
      ) { args, ctx ->
        if (ctx.inAutomation) return@Capability CapabilityResult.fail("automation.nested", "run_saved cannot run inside a batch")
        val (wf, err) = find(args, ctx); if (err != null) return@Capability err
        val input = when (val i = args["input"]) { null -> JsonObject(emptyMap()); is Map<*, *> -> Json.toJson(i) as JsonObject; else -> return@Capability CapabilityResult.fail("invalid_params", "input must be an object") }
        WorkflowEngine.checkInput(wf!!.inputsSchema, input)?.let { return@Capability CapabilityResult.fail("invalid_params", it) }
        val (corr, report) = engine.runManual(wf, input) ?: return@Capability CapabilityResult.fail("automation.busy", "'${wf.name}' is already running")
        report ?: return@Capability CapabilityResult.fail("automation.not_run", "the run did not start (owner unavailable, or its stored steps no longer parse); see automation.get runs")
        val data = report.toMap() + mapOf("name" to wf.name, "run_correlation_id" to corr)
        when (report.stopReason) {
          null -> CapabilityResult.ok(data)
          "timeout" -> CapabilityResult.fail(AutomationExecutor.TIMEOUT, "run deadline reached at step '${report.stoppedAt}'", data)
          "revoked" -> CapabilityResult.fail("automation.revoked", "the owner was revoked or expired before step '${report.stoppedAt}'", data)
          else -> report.steps.last().let { CapabilityResult.fail("automation.step_failed", "step '${it.id}' (${it.tool}) failed: ${it.error}", data) }
        }
      },
    )
  }
}
