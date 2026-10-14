package dev.symbiosis.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.symbiosis.runtime.automation.AutomationCapability
import dev.symbiosis.runtime.automation.AutomationExecutor
import dev.symbiosis.runtime.automation.BatchError
import dev.symbiosis.runtime.automation.Batches
import dev.symbiosis.runtime.automation.StepDispatcher
import dev.symbiosis.runtime.automation.TemplateError
import dev.symbiosis.runtime.automation.Templates
import dev.symbiosis.runtime.capabilities.CallContext
import dev.symbiosis.runtime.capabilities.Capability
import dev.symbiosis.runtime.capabilities.CapabilityRegistry
import dev.symbiosis.runtime.capabilities.CapabilityResult
import dev.symbiosis.runtime.capabilities.Diagnostics
import dev.symbiosis.runtime.capabilities.LogRedaction
import dev.symbiosis.runtime.capabilities.Param
import dev.symbiosis.runtime.capabilities.Principal
import dev.symbiosis.runtime.core.DispatchOutcome
import dev.symbiosis.runtime.core.Json
import dev.symbiosis.runtime.core.ToolDispatch
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.policy.PolicyConfig
import dev.symbiosis.runtime.policy.PolicyDecision
import dev.symbiosis.runtime.policy.PolicyEngine
import dev.symbiosis.runtime.policy.PolicyOutcome
import dev.symbiosis.runtime.policy.RateClass
import dev.symbiosis.runtime.policy.RateLimit
import dev.symbiosis.runtime.policy.RateLimiter
import dev.symbiosis.runtime.storage.Db
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * automation.run: templating, step failure handling, limits, no nesting, and — the property that matters — that
 * every step goes through the same scope/policy path as a direct call. The policy tests use the real
 * [ToolDispatch] (lookup, scope, ceiling, argument validation) against a real [PolicyEngine] on SQLite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomationTest {
  private lateinit var ctx: Context
  private lateinit var db: Db
  private val stepScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val caller = Principal("peer_a", "agent", listOf("automation.*", "device.*", "contacts.*"), ActionClass.EXTERNAL_COMMITMENT)
  private val parent = CallContext("peer_a", "corr_batch", idempotencyKey = "k1", principal = caller)

  @Before fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    ctx.deleteDatabase("symbiosis.db")
    db = Db(ctx)
  }

  private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = Json.toJson(mapOf(*pairs)) as JsonObject
  private fun batch(vararg steps: Map<String, Any?>, timeoutMs: Long? = null, input: Map<String, Any?>? = null) =
    Batches.parse(buildMap { put("steps", steps.toList()); timeoutMs?.let { put("timeout_ms", it) }; input?.let { put("input", it) } })

  // ---------------------------------------------------------------- templating

  private val scope = Templates.Scope(
    input = obj("name" to "Ann", "n" to 3),
    steps = mapOf(
      "find" to obj("ok" to true, "ms" to 5, "result" to mapOf("count" to 2, "contacts" to listOf(mapOf("number" to "+15125550100"), mapOf("number" to "+15125550101")))),
      "bad" to obj("ok" to false, "ms" to 1, "error" to "policy.denied"),
    ),
  )

  @Test fun `a whole-value reference keeps its JSON type`() {
    assertEquals(JsonPrimitive(2L), Templates.resolve(JsonPrimitive("{{steps.find.result.count}}"), scope))
    assertEquals(JsonPrimitive(3L), Templates.resolve(JsonPrimitive("{{ input.n }}"), scope))
    val arr = Templates.resolve(JsonPrimitive("{{steps.find.result.contacts}}"), scope)
    assertTrue(arr is JsonArray && arr.size == 2)
    assertEquals(JsonPrimitive(false), Templates.resolve(JsonPrimitive("{{steps.bad.ok}}"), scope))
  }

  @Test fun `interpolation is string-level and both array syntaxes work`() {
    assertEquals(JsonPrimitive("Hi Ann, 2 found"), Templates.resolve(JsonPrimitive("Hi {{input.name}}, {{steps.find.result.count}} found"), scope))
    assertEquals(JsonPrimitive("+15125550101"), Templates.resolve(JsonPrimitive("{{steps.find.result.contacts[1].number}}"), scope))
    assertEquals(JsonPrimitive("to +15125550100"), Templates.resolve(JsonPrimitive("to {{steps.find.result.contacts.0.number}}"), scope))
    // Recursive through objects and arrays; keys and non-strings untouched; an unclosed {{ is literal.
    val out = Templates.resolve(obj("to" to "{{steps.find.result.contacts.0.number}}", "list" to listOf("{{input.n}}", 7), "{{input.name}}" to "x", "lit" to "a {{ b"), scope) as JsonObject
    assertEquals(JsonPrimitive("+15125550100"), out["to"])
    assertEquals(JsonArray(listOf(JsonPrimitive(3L), JsonPrimitive(7L))), out["list"])
    assertTrue(out.containsKey("{{input.name}}"))
    assertEquals(JsonPrimitive("a {{ b"), out["lit"])
  }

  @Test fun `missing references are errors, never empty strings`() {
    for (ref in listOf(
      "{{steps.nope.result.x}}", "{{steps.find.result.missing}}", "{{steps.find.result.contacts.5.number}}",
      "{{steps.bad.result.x}}", "{{input.absent}}", "{{env.HOME}}", "{{steps.find.secret}}", "{{steps.find.result.count.deeper}}",
    )) {
      try { Templates.resolve(JsonPrimitive("text $ref"), scope); fail("expected TemplateError for $ref") } catch (_: TemplateError) {}
    }
  }

  // ---------------------------------------------------------------- parsing and limits

  @Test fun `limits and shape are checked before anything runs`() {
    val step = mapOf("tool" to "device.get_battery")
    assertEquals(25, Batches.parse(mapOf("steps" to List(25) { step })).steps.size)
    assertThrows { Batches.parse(mapOf("steps" to List(26) { step })) }
    assertThrows { Batches.parse(mapOf("steps" to emptyList<Any>())) }
    assertThrows { batch(mapOf("id" to "a", "tool" to "x.y"), mapOf("id" to "a", "tool" to "x.z")) }
    assertThrows { batch(mapOf("tool" to "x.y", "on_error" to "retry")) }
    assertThrows { batch(mapOf("tool" to "x.y", "wait_ms" to -1)) }
    assertThrows { batch(mapOf("tool" to "x.y", "args" to "nope")) }
    assertEquals(Batches.DEFAULT_TIMEOUT_MS, batch(step).timeoutMs)
    assertEquals(Batches.MAX_TIMEOUT_MS, batch(step, timeoutMs = 3_600_000).timeoutMs)
    // An id-less step is addressable by its index.
    assertEquals(listOf("0", "b"), batch(step, mapOf("id" to "b", "tool" to "x.y")).steps.map { it.id })
  }

  @Test fun `batches do not nest`() = runBlocking {
    assertThrows { batch(mapOf("tool" to "automation.run", "args" to mapOf("steps" to emptyList<Any>()))) }
    assertThrows { batch(mapOf("tool" to "automation.anything")) }
    // Second, independent check: automation.run refuses to execute on a step's context.
    val cap = AutomationCapability.capability(AutomationExecutor({ _, _, _, _ -> error("must not dispatch") }, stepScope))
    val r = cap.execute(mapOf("steps" to listOf(mapOf("tool" to "device.get_battery"))), parent.copy(inAutomation = true))
    assertEquals("automation.nested", r.errorCode)
  }

  private fun assertThrows(block: () -> Unit) { try { block(); fail("expected BatchError") } catch (_: BatchError) {} }

  // ---------------------------------------------------------------- executor

  /** A dispatcher that answers from a table: ok with data, or an isError envelope with a code. */
  private fun fakeDispatcher(seen: MutableList<Pair<String, CallContext>> = mutableListOf(), answer: (String, Map<String, Any?>) -> CapabilityResult) =
    StepDispatcher { _, tool, args, c -> seen += tool to c; DispatchOutcome.Result(envelope(answer(tool, args))) }

  @Test fun `on_error stop ends the batch, continue moves on, and templates see earlier results`() = runBlocking {
    val seen = mutableListOf<Pair<String, CallContext>>()
    val calls = mutableListOf<Map<String, Any?>>()
    val exec = AutomationExecutor(fakeDispatcher(seen) { tool, args ->
      calls += args
      when (tool) {
        "contacts.search" -> CapabilityResult.ok(mapOf("contacts" to listOf(mapOf("number" to "+15125550100"))))
        "device.fails" -> CapabilityResult.fail("capability.failed", "boom", mapOf("hint" to "x"))
        else -> CapabilityResult.ok(mapOf("echo" to args))
      }
    }, stepScope)

    val r1 = exec.run(batch(
      mapOf("id" to "find", "tool" to "contacts.search", "args" to mapOf("query" to "Ann")),
      mapOf("id" to "soft", "tool" to "device.fails", "on_error" to "continue"),
      mapOf("id" to "use", "tool" to "device.echo", "args" to mapOf("to" to "{{steps.find.result.contacts.0.number}}", "prev_ok" to "{{steps.soft.ok}}")),
      mapOf("id" to "hard", "tool" to "device.fails"),
      mapOf("id" to "never", "tool" to "device.echo"),
    ), caller, parent)
    assertFalse(r1.completed)
    assertEquals("hard", r1.stoppedAt)
    assertEquals("step_failed", r1.stopReason)
    assertEquals(listOf("never"), r1.notRun)
    assertEquals(listOf(true, false, true, false), r1.steps.map { it.ok })
    assertEquals("+15125550100", calls[2]["to"])
    assertEquals(false, calls[2]["prev_ok"])
    assertEquals("capability.failed", r1.steps[1].error)
    assertEquals(JsonPrimitive("x"), r1.steps[1].errorData!!["hint"])
    // Each step is its own call: the caller's correlation id, a derived per-step idempotency key, marked inAutomation.
    assertTrue(seen.all { it.second.correlationId == "corr_batch" && it.second.inAutomation && it.second.principal == caller })
    assertEquals(listOf("k1#find@0", "k1#soft@1", "k1#use@2", "k1#hard@3"), seen.map { it.second.idempotencyKey })
  }

  @Test fun `a missing reference fails only that step`() = runBlocking {
    val exec = AutomationExecutor(fakeDispatcher { _, _ -> CapabilityResult.ok(mapOf("v" to 1)) }, stepScope)
    val r = exec.run(batch(
      mapOf("id" to "a", "tool" to "device.x", "args" to mapOf("q" to "{{steps.zzz.result.v}}"), "on_error" to "continue"),
      mapOf("id" to "b", "tool" to "device.x", "args" to mapOf("q" to "{{steps.a.result.v}}")),
    ), caller, parent)
    assertEquals("automation.template", r.steps[0].error)
    // b references a's result, but a failed: that is a missing reference too, and b stops the batch.
    assertEquals("automation.template", r.steps[1].error)
    assertEquals("b", r.stoppedAt)
  }

  @Test fun `the batch deadline holds even when a step never returns`() = runBlocking {
    val exec = AutomationExecutor({ _, tool, _, _ -> if (tool == "device.hang") awaitCancellation() else DispatchOutcome.Result(envelope(CapabilityResult.ok(emptyMap()))) }, stepScope)
    val t0 = System.currentTimeMillis()
    val r = exec.run(batch(mapOf("tool" to "device.ok"), mapOf("id" to "h", "tool" to "device.hang"), mapOf("tool" to "device.ok"), timeoutMs = 1_000), caller, parent)
    val took = System.currentTimeMillis() - t0
    assertTrue("took $took ms", took in 900..3_000)
    assertEquals("timeout", r.stopReason)
    assertEquals("h", r.stoppedAt)
    assertEquals(AutomationExecutor.TIMEOUT, r.steps.last().error)
    assertEquals(listOf("2"), r.notRun)
  }

  // ---------------------------------------------------------------- every step goes through the policy path

  private fun cap(name: String, ac: ActionClass, rate: RateClass = RateClass.NONE, params: List<Param> = emptyList()) =
    Capability(name, "", params, ac, rateClass = rate) { _, _ -> CapabilityResult.ok(mapOf("ran" to name)) }

  /**
   * The registry and dispatch are the real ones; invoke mirrors Runtime.invoke's policy step (evaluate → deny or
   * execute → release on failure), so a step can only run if the real PolicyEngine allows it.
   */
  private fun realPath(policy: PolicyEngine): Pair<ToolDispatch, MutableList<String>> {
    val executed = mutableListOf<String>()
    val registry = CapabilityRegistry()
    registry.registerAll(listOf(
      cap("device.read", ActionClass.READ_ONLY),
      cap("device.denied_by_override", ActionClass.LOW_RISK),
      cap("device.metered", ActionClass.LOW_RISK, RateClass.MUTATION),
      cap("device.screen_tap", ActionClass.DEVICE_MUTATION, RateClass.MUTATION), // above the caller's ceiling
      cap("messaging.send_sms", ActionClass.EXTERNAL_COMMITMENT, RateClass.SMS), // outside the caller's scopes
      cap("device.typed", ActionClass.READ_ONLY, params = listOf(Param("n", "integer", "", required = true))),
    ))
    val dispatch = ToolDispatch(registry) { _, c, args, callCtx ->
      val r = when (val o = policy.evaluate(c, args)) {
        is PolicyOutcome.Deny -> CapabilityResult.fail(o.code, o.message, o.extra)
        is PolicyOutcome.Approve -> CapabilityResult.fail("policy.approval_required", o.reason)
        is PolicyOutcome.Allow -> c.execute(args, callCtx).also { if (c.name != AutomationCapability.NAME) executed += c.name; if (!it.ok) policy.release(o) }
      }
      envelope(r)
    }
    registry.register(AutomationCapability.capability(AutomationExecutor({ p, n, a, c -> dispatch.dispatch(p, n, a, c) }, stepScope)))
    return dispatch to executed
  }

  @Test fun `a step the caller could not call directly fails in the batch too`() = runBlocking {
    val policy = PolicyEngine(db, RateLimiter(db))
    policy.setCapabilityOverride("device.denied_by_override", PolicyDecision.DENY)
    policy.updateConfig(policy.config.value.copy(rateLimits = policy.config.value.rateLimits + (RateClass.MUTATION to RateLimit(1, 10))))
    val (dispatch, executed) = realPath(policy)

    val out = dispatch.dispatch(caller, "automation.run", mapOf("steps" to listOf(
      mapOf("id" to "read", "tool" to "device.read", "on_error" to "continue"),
      mapOf("id" to "scope", "tool" to "messaging.send_sms", "args" to mapOf("to" to "+15125550100", "text" to "hi"), "on_error" to "continue"),
      mapOf("id" to "ceiling", "tool" to "device.screen_tap", "on_error" to "continue"),
      mapOf("id" to "override", "tool" to "device.denied_by_override", "on_error" to "continue"),
      mapOf("id" to "m1", "tool" to "device.metered", "on_error" to "continue"),
      mapOf("id" to "m2", "tool" to "device.metered", "on_error" to "continue"),
      mapOf("id" to "badargs", "tool" to "device.typed", "args" to mapOf("n" to "seven"), "on_error" to "continue"),
    )), parent) as DispatchOutcome.Result
    val steps = (out.body["structuredContent"] as JsonObject)["steps"] as JsonArray
    fun err(i: Int) = ((steps[i] as JsonObject)["error"] as? JsonPrimitive)?.content
    assertNull(err(0))
    assertEquals("tool.unknown", err(1))   // scope: hidden exactly as for a direct call (-32601)
    assertEquals("tool.unknown", err(2))   // ceiling: DEVICE_MUTATION above EXTERNAL_COMMITMENT
    assertEquals("policy.denied", err(3))  // per-capability override
    assertNull(err(4))
    assertEquals("policy.rate_limited", err(5)) // the step's own rate class is charged, 1/hour here
    assertEquals("invalid_params", err(6))
    // Only the allowed steps ever executed.
    assertEquals(listOf("device.read", "device.metered"), executed)
  }

  @Test fun `emergency stop and observe-only reach inside a batch`() = runBlocking {
    val policy = PolicyEngine(db, RateLimiter(db))
    val (dispatch, executed) = realPath(policy)
    policy.setObserveOnly(true)
    val out = dispatch.dispatch(caller, "automation.run", mapOf("steps" to listOf(
      mapOf("tool" to "device.read"), mapOf("tool" to "device.metered"),
    )), parent) as DispatchOutcome.Result
    val sc = out.body["structuredContent"] as JsonObject
    // The batch itself (AUTOMATION) passes observe-only; the mutating step inside it does not.
    assertEquals("policy.observe_only", ((sc["steps"] as JsonArray)[1] as JsonObject)["error"]?.let { (it as JsonPrimitive).content })
    assertEquals(listOf("device.read"), executed)
    policy.setObserveOnly(false)
    policy.setEmergencyStop(true)
    val stopped = dispatch.dispatch(caller, "automation.run", mapOf("steps" to listOf(mapOf("tool" to "device.read"))), parent) as DispatchOutcome.Result
    assertEquals(JsonPrimitive("policy.emergency_stop"), (stopped.body["structuredContent"] as JsonObject)["error"])
  }

  @Test fun `a peer without automation scope cannot see the batch tool`() = runBlocking {
    val (dispatch, _) = realPath(PolicyEngine(db, RateLimiter(db)))
    val noAutomation = caller.copy(scopes = listOf("device.*"))
    val r = dispatch.dispatch(noAutomation, "automation.run", mapOf("steps" to listOf(mapOf("tool" to "device.read"))), parent)
    assertEquals(-32601, (r as DispatchOutcome.RpcError).code)
    // And READ_ONLY-ceiling peers do not see it either (AUTOMATION ranks just above READ_ONLY).
    val readOnly = caller.copy(maxActionClass = ActionClass.READ_ONLY)
    assertEquals(-32601, (dispatch.dispatch(readOnly, "automation.run", mapOf("steps" to emptyList<Any>()), parent) as DispatchOutcome.RpcError).code)
  }

  @Test fun `a saved config that predates the automation class still allows and meters batches`() {
    val policy = PolicyEngine(db, RateLimiter(db))
    // Simulate a config saved before AUTOMATION existed: neither key present.
    val old = PolicyConfig().let { it.copy(classDefaults = it.classDefaults - ActionClass.AUTOMATION, rateLimits = it.rateLimits - RateClass.AUTOMATION) }
    policy.updateConfig(old)
    val batchCap = cap("automation.run", ActionClass.AUTOMATION, RateClass.AUTOMATION)
    val o = policy.evaluate(batchCap, emptyMap())
    assertTrue(o is PolicyOutcome.Allow)
    assertTrue("rate slot reserved from the code default", (o as PolicyOutcome.Allow).reservationId > 0)
  }

  // ---------------------------------------------------------------- diagnostics helpers

  @Test fun `log redaction scrubs credentials and pairing codes but keeps the rest`() {
    val token = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde" // 43 chars, the bearer-token shape
    assertEquals(43, token.length)
    assertEquals("SYMBIOSIS_PAIRING_CODE=[redacted]", LogRedaction.redact("SYMBIOSIS_PAIRING_CODE=K7Q2-9F4A"))
    assertEquals("Authorization: Bearer [redacted]", LogRedaction.redact("Authorization: Bearer $token"))
    assertEquals("token=[redacted] next", LogRedaction.redact("token=abc123 next"))
    assertEquals("\"api_key\": \"[redacted]\"", LogRedaction.redact("\"api_key\": \"sk-live-xyz\""))
    assertEquals("got [redacted-token] back", LogRedaction.redact("got $token back"))
    assertEquals("placed call to +15125550100 in 3 ms", LogRedaction.redact("placed call to +15125550100 in 3 ms"))
  }

  @Test fun `logcat threadtime lines parse and unknown lines survive raw`() {
    val e = Diagnostics.parseLine("09-26 14:02:11.123  1234  1250 W Runtime : driver lease expired password=hunter2")
    assertEquals("W", e["level"]); assertEquals("Runtime", e["tag"]); assertEquals(1234, e["pid"])
    assertEquals("driver lease expired password=[redacted]", e["msg"])
    assertEquals("weird line", Diagnostics.parseLine("weird line")["raw"])
    assertEquals("anr", Diagnostics.reasonName(6)); assertEquals("code_99", Diagnostics.reasonName(99))
  }

  private fun envelope(r: CapabilityResult): JsonObject = buildJsonObject {
    put("isError", !r.ok)
    put("structuredContent", Json.toJson(if (r.ok) r.data else r.data + mapOf("error" to r.errorCode, "detail" to r.errorDetail)))
  }
}
