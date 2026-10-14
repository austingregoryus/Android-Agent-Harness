package dev.symbiosis.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.symbiosis.runtime.audit.AuditEntry
import dev.symbiosis.runtime.audit.AuditKind
import dev.symbiosis.runtime.audit.AuditStore
import dev.symbiosis.runtime.automation.AlarmSink
import dev.symbiosis.runtime.automation.AutomationExecutor
import dev.symbiosis.runtime.automation.BatchError
import dev.symbiosis.runtime.automation.Batches
import dev.symbiosis.runtime.automation.Matcher
import dev.symbiosis.runtime.automation.MatcherError
import dev.symbiosis.runtime.automation.RegexTimeout
import dev.symbiosis.runtime.automation.StepDispatcher
import dev.symbiosis.runtime.automation.TriggerError
import dev.symbiosis.runtime.automation.TriggerSpec
import dev.symbiosis.runtime.automation.Triggers
import dev.symbiosis.runtime.automation.WorkflowCapabilities
import dev.symbiosis.runtime.automation.WorkflowEngine
import dev.symbiosis.runtime.automation.WorkflowStore
import dev.symbiosis.runtime.capabilities.AiAsk
import dev.symbiosis.runtime.capabilities.CallContext
import dev.symbiosis.runtime.capabilities.Capability
import dev.symbiosis.runtime.capabilities.CapabilityResult
import dev.symbiosis.runtime.capabilities.Diagnostics
import dev.symbiosis.runtime.capabilities.Principal
import dev.symbiosis.runtime.core.DispatchOutcome
import dev.symbiosis.runtime.core.Json
import dev.symbiosis.runtime.core.Runtime
import dev.symbiosis.runtime.events.Event
import dev.symbiosis.runtime.events.EventBroker
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.policy.AiConfig
import dev.symbiosis.runtime.storage.Db
import dev.symbiosis.runtime.transport.STANDARD_SCOPES
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** One test (or more) per finding of the 2026-09-26 review of the batch core and the workflow engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewRound3Test {
  private lateinit var ctx: Context
  private lateinit var db: Db
  private lateinit var broker: EventBroker
  private val bg = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var now = 1_800_000_000_000L
  private val armed = mutableListOf<Long?>()
  private var gateOpen = true

  private val alice = Principal("peer_alice", "alice", listOf("automation.*", "messaging.*", "device.*"), ActionClass.EXTERNAL_COMMITMENT)
  private val principals = mutableMapOf(alice.peerId to alice)

  @Before fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    ctx.deleteDatabase("symbiosis.db")
    db = Db(ctx)
    broker = EventBroker(db, "dev_test")
  }

  private fun ok(tool: String) = DispatchOutcome.Result(buildJsonObject { put("isError", false); put("structuredContent", buildJsonObject { put("tool", tool) }) })

  private fun engine(dispatcher: StepDispatcher = StepDispatcher { _, tool, _, _ -> ok(tool) }) = WorkflowEngine(
    store = WorkflowStore(db), broker = broker, executor = AutomationExecutor(dispatcher, bg, clock = { now }),
    principalFor = { principals[it] }, emergencyStopped = { false }, alarms = AlarmSink { armed += it }, scope = bg,
    gate = { gateOpen }, clock = { now },
  )

  private fun save(e: WorkflowEngine, name: String, trigger: Map<String, Any?>?): CapabilityResult = runBlocking {
    val caps: Map<String, Capability> = WorkflowCapabilities.all(e) { _, _ -> true }.associateBy { it.name }
    caps.getValue("automation.save").execute(
      buildMap { put("name", name); put("steps", listOf(mapOf("tool" to "device.get_battery"))); trigger?.let { put("trigger", it) } },
      CallContext(alice.peerId, "c", principal = alice),
    )
  }

  private val sms = mapOf("event" to mapOf("topic" to "messaging.sms_received"))
  private fun smsEvent(corr: String? = null): Event = broker.emit("messaging.sms_received", mapOf("from" to "+1", "text" to "hi"), corr)

  // ---- 1a: regex is bounded ----------------------------------------------------------------

  @Test fun `regexes are length-capped, input-capped, linear and deadline-bounded`() {
    try { Matcher.compile("a".repeat(Matcher.MAX_REGEX + 1)); fail() } catch (_: MatcherError) {}
    // The classic catastrophic pattern is linear under RE2: 4 KB of near-matches finish at once.
    val t0 = System.nanoTime()
    assertFalse(Matcher.regexFind("(a|aa)+$", "a".repeat(4_000) + "!"))
    assertTrue("took ${(System.nanoTime() - t0) / 1_000_000} ms", System.nanoTime() - t0 < 2_000_000_000L)
    // Only the first 4 KB is searched.
    assertFalse(Matcher.regexFind("b", "a".repeat(Matcher.MAX_REGEX_INPUT) + "b"))
    assertTrue(Matcher.regexFind("b", "a".repeat(Matcher.MAX_REGEX_INPUT - 1) + "b"))
    // Past the deadline the match throws instead of running on.
    try { Matcher.regexFind("z", "a".repeat(1000), deadlineMs = -1); fail() } catch (_: RegexTimeout) {}
  }

  // ---- 1b: one stuck subscriber cannot silence the broker ---------------------------------------

  @Test fun `a stuck live subscriber does not stop delivery to the others`() = runBlocking {
    val seen = Collections.synchronizedList(mutableListOf<Long>())
    val stuck = bg.launch { broker.live.collect { awaitCancellation() } }
    val fast = bg.launch { broker.live.collect { seen += it.id } }
    (broker.live as MutableSharedFlow<Event>).subscriptionCount.first { it == 2 }
    var last = 0L
    repeat(1_500) { last = broker.emit("battery.changed", mapOf("percent" to it)).id }
    withTimeout(10_000) { while (seen.lastOrNull() != last) delay(10) }
    stuck.cancel(); fast.cancel()
  }

  // ---- 2: loop guard, trigger limits --------------------------------------------------------

  @Test fun `automation topics are refused and trigger limits have a floor and a ceiling`() {
    for (topic in listOf("automation.run_finished", "automation.*", "*", "*.run_started")) {
      try { Triggers.parse(Json.toJson(mapOf("event" to mapOf("topic" to topic))) as JsonObject); fail(topic) } catch (_: TriggerError) {}
    }
    fun t(cool: Long, perHour: Int) = Json.toJson(mapOf("event" to mapOf("topic" to "messaging.sms_received"), "cooldown_ms" to cool, "max_runs_per_hour" to perHour)) as JsonObject
    try { Triggers.parse(t(0, 60)); fail() } catch (_: TriggerError) {}
    try { Triggers.parse(t(10_000, 600)); fail() } catch (_: TriggerError) {}
    // A stored row from before the limits keeps working, clamped.
    val old = Triggers.parse(t(0, 600), strict = false)
    assertEquals(TriggerSpec.MIN_COOLDOWN_MS, old.cooldownMs)
    assertEquals(TriggerSpec.MAX_PER_HOUR, old.maxRunsPerHour)
    // And automation.* events never reach a workflow even if one were stored.
    val e = engine()
    assertTrue(e.onEvent(broker.emit("automation.run_finished", mapOf("ok" to true))).isEmpty())
  }

  // ---- 3: revocation mid-run ----------------------------------------------------------------

  @Test fun `a batch re-resolves its caller before every step`() = runBlocking {
    var calls = 0
    val narrowed = alice.copy(scopes = listOf("device.*"))
    val seen = mutableListOf<Principal>()
    val exec = AutomationExecutor({ p, tool, _, _ -> seen += p; ok(tool) }, bg, refresh = { calls++; when (calls) { 1 -> alice; 2 -> narrowed; else -> null } })
    val r = exec.run(Batches.parse(mapOf("steps" to List(3) { mapOf("tool" to "device.x") })), alice, CallContext(alice.peerId, "c", principal = alice))
    assertEquals(listOf(alice, narrowed), seen) // narrowed scopes apply from the next step
    assertEquals("revoked", r.stopReason)
    assertEquals("automation.revoked", r.steps.last().error)
  }

  @Test fun `revoking the owner cancels its running workflow`() = runBlocking {
    val started = kotlinx.coroutines.CompletableDeferred<Unit>()
    val e = engine(StepDispatcher { _, _, _, _ -> started.complete(Unit); awaitCancellation() })
    save(e, "slow", sms)
    val run = e.onEvent(smsEvent()).single() as WorkflowEngine.Decision.Run
    withTimeout(5_000) { started.await() }
    e.onOwnerRevoked(alice.peerId)
    withTimeout(5_000) { run.job.join() }
    val wf = WorkflowStore(db).get(alice.peerId, "slow")!!
    assertEquals("cancelled", (wf.lastResult!!["stop_reason"] as JsonPrimitive).content)
    assertFalse(wf.enabled)
  }

  // ---- 4, 6: schedules ----------------------------------------------------------------------

  @Test fun `an alarm while the runtime is off advances the schedule instead of spinning`() {
    val e = engine()
    save(e, "s", mapOf("schedule" to mapOf("every_minutes" to 30)))
    val due = WorkflowStore(db).get(alice.peerId, "s")!!.nextRunAt!!
    gateOpen = false
    now = due
    assertTrue(e.onAlarm().all { it is WorkflowEngine.Decision.Skipped })
    val wf = WorkflowStore(db).get(alice.peerId, "s")!!
    assertEquals(due + 30 * 60_000L, wf.nextRunAt)
    assertEquals(due + 30 * 60_000L, armed.last()) // re-armed for the next occurrence, not the past
    assertTrue(WorkflowStore(db).recentLog(wf.id).any { it["status"] == "skipped:runtime_off" })
  }

  @Test fun `onAlarm skips a long-missed run and time changes recompute`() {
    val e = engine()
    save(e, "s", mapOf("schedule" to mapOf("every_minutes" to 30)))
    val id = WorkflowStore(db).get(alice.peerId, "s")!!.id
    WorkflowStore(db).setNextRun(id, now - 20 * 60_000L) // missed by more than the 15 min grace
    assertTrue(e.onAlarm().isEmpty())
    assertEquals(0L, WorkflowStore(db).getById(id)!!.runCount)
    assertTrue(WorkflowStore(db).recentLog(id).any { it["status"] == "missed" })
    WorkflowStore(db).setNextRun(id, now + 10 * 3_600_000L)
    e.recomputeSchedules()
    assertEquals(now + 30 * 60_000L, WorkflowStore(db).getById(id)!!.nextRunAt)
  }

  // ---- 5: scope before matching, silently ---------------------------------------------------

  @Test fun `an owner who lost the topic scope triggers nothing and leaves no trace`() {
    val e = engine()
    save(e, "s", sms)
    principals[alice.peerId] = alice.copy(scopes = listOf("automation.*", "device.*")) // messaging.* taken away
    assertTrue(e.onEvent(smsEvent()).isEmpty())
    assertTrue(WorkflowStore(db).recentLog(WorkflowStore(db).get(alice.peerId, "s")!!.id).isEmpty())
  }

  // ---- 7: ai.ask ----------------------------------------------------------------------------

  private fun rawServer(handle: (String, java.io.OutputStream) -> Unit): java.net.ServerSocket {
    val server = java.net.ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
    Thread {
      while (!server.isClosed) {
        val sock = runCatching { server.accept() }.getOrNull() ?: break
        Thread {
          runCatching {
            sock.use { so ->
              val input = java.io.BufferedInputStream(so.getInputStream())
              val headers = StringBuilder()
              while (!headers.endsWith("\r\n\r\n")) { val b = input.read(); if (b < 0) break; headers.append(b.toChar()) }
              val len = Regex("(?i)content-length:\\s*(\\d+)").find(headers)?.groupValues?.get(1)?.toInt() ?: 0
              val buf = ByteArray(len); var off = 0
              while (off < len) { val n = input.read(buf, off, len - off); if (n < 0) break; off += n }
              handle(String(buf, Charsets.UTF_8), so.getOutputStream())
            }
          }
        }.apply { isDaemon = true; start() }
      }
    }.apply { isDaemon = true; start() }
    return server
  }

  private fun respond(out: java.io.OutputStream, body: ByteArray, contentLength: Int = body.size) {
    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: $contentLength\r\nConnection: close\r\n\r\n".toByteArray())
    out.write(body); out.flush()
  }

  @Test fun `ai ask coerces max_tokens, caps the reply and has an overall deadline`() {
    val bodies = Collections.synchronizedList(mutableListOf<String>())
    val reply = """{"choices":[{"message":{"content":"ok"}}]}""".toByteArray()
    val normal = rawServer { req, out -> bodies += req; respond(out, reply) }
    val huge = rawServer { _, out -> respond(out, ByteArray(AiAsk.MAX_RESPONSE + 10) { ' '.code.toByte() }) }
    // Headers, then one byte every 300 ms: never trips a per-read timeout.
    val trickle = rawServer { _, out ->
      out.write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".toByteArray()); out.flush()
      repeat(1_000) { out.write(' '.code); out.flush(); Thread.sleep(300) }
    }
    try {
      fun cfg(s: java.net.ServerSocket, ms: Long = 60_000) = AiConfig(baseUrl = "http://127.0.0.1:${s.localPort}/v1", model = "m", timeoutMs = ms)
      assertTrue(AiAsk.ask(cfg(normal), mapOf("prompt" to "p", "max_tokens" to "300")).ok)
      assertEquals(JsonPrimitive(300), (Json.lenient.parseToJsonElement(bodies[0]) as JsonObject)["max_tokens"])
      assertEquals("ai.bad_response", AiAsk.ask(cfg(huge), mapOf("prompt" to "p")).errorCode)
      val t0 = System.currentTimeMillis()
      assertEquals("ai.timeout", AiAsk.ask(cfg(trickle, 1_500), mapOf("prompt" to "p")).errorCode)
      assertTrue("took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 5_000)
    } finally { normal.close(); huge.close(); trickle.close() }
  }

  // ---- 8, 9: idempotent replay is bound to the call and never replays a truncated result -----------

  @Test fun `replay is bound to tool and args and refuses truncated outcomes`() {
    val audit = AuditStore(db, "seed")
    val args = Json.toJson(mapOf("to" to "+1", "body" to "hi"))
    val digest = audit.argsDigest(args)
    val env = buildJsonObject { put("content", "x"); put("isError", false) }
    audit.append(AuditEntry(ts = 1, correlationId = "c", peerId = "p", kind = AuditKind.TOOL_OUTCOME, capability = "messaging.send_sms", args = buildJsonObject { put(AuditStore.ARGS_DIGEST, digest) }, result = env, success = true, idempotencyKey = "k"))
    val stored = audit.findOutcome("k", "p")!!
    assertNull(Runtime.replayRefusal(stored, "messaging.send_sms", digest))
    assertEquals("conflict.idempotency_mismatch", Runtime.replayRefusal(stored, "telephony.place_call", digest))
    assertEquals("conflict.idempotency_mismatch", Runtime.replayRefusal(stored, "messaging.send_sms", audit.argsDigest(Json.toJson(mapOf("to" to "+2", "body" to "hi")))))
    // Rows from before replay was bound to args carry no digest: refused too.
    audit.append(AuditEntry(ts = 2, correlationId = "c", peerId = "p", kind = AuditKind.TOOL_OUTCOME, capability = "t", result = env, success = true, idempotencyKey = "old"))
    assertEquals("conflict.idempotency_mismatch", Runtime.replayRefusal(audit.findOutcome("old", "p")!!, "t", digest))
    // An outcome larger than the 8 KB audit cap was stored cut down: never replayed as if it were the result.
    val big = buildJsonObject { put("content", "x".repeat(20_000)); put("isError", false) }
    audit.append(AuditEntry(ts = 3, correlationId = "c", peerId = "p", kind = AuditKind.TOOL_OUTCOME, capability = "ui.screenshot", args = buildJsonObject { put(AuditStore.ARGS_DIGEST, digest) }, result = big, success = true, idempotencyKey = "big"))
    val bigStored = audit.findOutcome("big", "p")!!
    assertTrue(bigStored.truncated)
    assertEquals("idempotency.result_unavailable", Runtime.replayRefusal(bigStored, "ui.screenshot", digest))
  }

  // ---- 10: diagnostics namespace ------------------------------------------------------------

  @Test fun `diagnostics tools are outside device get_star`() {
    val narrow = Principal("p", "p", listOf("device.get_*"), ActionClass.DESTRUCTIVE)
    for (cap in Diagnostics.all(ctx)) {
      assertTrue(cap.name.startsWith("diagnostics."))
      assertFalse(narrow.canSee(cap))
    }
    assertTrue("diagnostics.*" in STANDARD_SCOPES)
  }

  // ---- 11, 12: timed-out steps are cancelled; waits that cannot fit fail at once; numeric strings --------

  @Test fun `a step still running at the deadline is cancelled`() = runBlocking {
    val stopped = AtomicBoolean(false)
    val exec = AutomationExecutor({ _, _, _, _ -> try { delay(60_000); ok("x") } finally { stopped.set(true) } }, bg)
    val r = exec.run(Batches.parse(mapOf("steps" to listOf(mapOf("tool" to "device.slow")), "timeout_ms" to 1_000)), alice, CallContext(alice.peerId, "c", principal = alice))
    assertEquals("timeout", r.stopReason)
    withTimeout(2_000) { while (!stopped.get()) delay(10) }
  }

  @Test fun `a wait that cannot fit fails at once, and numeric strings parse`() = runBlocking {
    val exec = AutomationExecutor({ _, tool, _, _ -> ok(tool) }, bg)
    val t0 = System.currentTimeMillis()
    val r = exec.run(Batches.parse(mapOf("steps" to listOf(mapOf("tool" to "device.x", "wait_ms" to "5000")), "timeout_ms" to "1000")), alice, CallContext(alice.peerId, "c", principal = alice))
    assertEquals("timeout", r.stopReason)
    assertTrue("took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 500)
    assertEquals(5_000L, Batches.parse(mapOf("steps" to listOf(mapOf("tool" to "x.y")), "timeout_ms" to "5000")).timeoutMs)
    try { Batches.parse(mapOf("steps" to listOf(mapOf("tool" to "x.y")), "timeout_ms" to "soon")); fail() } catch (_: BatchError) {}
  }
}
