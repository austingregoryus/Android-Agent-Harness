package dev.symbiosis.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.symbiosis.runtime.automation.AlarmSink
import dev.symbiosis.runtime.automation.AutomationExecutor
import dev.symbiosis.runtime.automation.Batches
import dev.symbiosis.runtime.automation.Matcher
import dev.symbiosis.runtime.automation.StepDispatcher
import dev.symbiosis.runtime.automation.Trigger
import dev.symbiosis.runtime.automation.TriggerError
import dev.symbiosis.runtime.automation.Triggers
import dev.symbiosis.runtime.automation.WorkflowCapabilities
import dev.symbiosis.runtime.automation.WorkflowEngine
import dev.symbiosis.runtime.automation.WorkflowStore
import dev.symbiosis.runtime.capabilities.AiAsk
import dev.symbiosis.runtime.capabilities.CallContext
import dev.symbiosis.runtime.capabilities.Capability
import dev.symbiosis.runtime.capabilities.CapabilityResult
import dev.symbiosis.runtime.capabilities.Principal
import dev.symbiosis.runtime.core.DispatchOutcome
import dev.symbiosis.runtime.core.Json
import dev.symbiosis.runtime.events.EventBroker
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.policy.AiConfig
import dev.symbiosis.runtime.storage.Db
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Collections

/** Saved workflows: storage and ownership, triggers and their brakes, re-arm on restart, `if`, and ai.ask. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkflowTest {
  private lateinit var ctx: Context
  private lateinit var db: Db
  private lateinit var broker: EventBroker
  private val bg = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var now = 1_800_000_000_000L
  private val armed = mutableListOf<Long?>()
  private var stopped = false

  private val alice = Principal("peer_alice", "alice", listOf("automation.*", "messaging.*", "device.*", "notifications.*", "ai.*"), ActionClass.EXTERNAL_COMMITMENT)
  private val bob = Principal("peer_bob", "bob", listOf("automation.*", "device.*"), ActionClass.EXTERNAL_COMMITMENT)
  private val admin = Principal("peer_admin", "admin", listOf("*"), ActionClass.DESTRUCTIVE)
  private val principals = mutableMapOf(alice.peerId to alice, bob.peerId to bob, admin.peerId to admin)

  /** Every dispatched step: (tool, args, ctx). */
  private val calls = Collections.synchronizedList(mutableListOf<Triple<String, Map<String, Any?>, CallContext>>())
  /** When set, dispatch waits on it: lets a test hold a run open. */
  @Volatile private var hold: CompletableDeferred<Unit>? = null

  private val dispatcher = StepDispatcher { _, tool, args, c ->
    calls += Triple(tool, args, c)
    hold?.await()
    DispatchOutcome.Result(buildJsonObject {
      put("isError", false)
      put("structuredContent", Json.toJson(mapOf("tool" to tool, "percent" to 57, "echo" to args)))
    })
  }

  private lateinit var engine: WorkflowEngine
  private lateinit var caps: Map<String, Capability>

  @Before fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    ctx.deleteDatabase("symbiosis.db")
    db = Db(ctx)
    broker = EventBroker(db, "dev_test")
    engine = newEngine()
    caps = WorkflowCapabilities.all(engine) { _, _ -> true }.associateBy { it.name }
  }

  @After fun tearDown() { hold?.complete(Unit) }

  private fun newEngine() = WorkflowEngine(
    store = WorkflowStore(db), broker = broker, executor = AutomationExecutor(dispatcher, bg, clock = { now }),
    principalFor = { principals[it] }, emergencyStopped = { stopped }, alarms = AlarmSink { armed += it }, scope = bg, clock = { now },
  )

  private fun call(name: String, p: Principal, vararg args: Pair<String, Any?>): CapabilityResult = runBlocking {
    caps.getValue(name).execute(mapOf(*args), CallContext(p.peerId, "corr_call", principal = p))
  }

  private fun smsTrigger(where: Map<String, Any?>? = null, cooldown: Long? = null, perHour: Int? = null) = buildMap<String, Any?> {
    put("event", buildMap { put("topic", "messaging.sms_received"); where?.let { put("where", it) } })
    cooldown?.let { put("cooldown_ms", it) }
    perHour?.let { put("max_runs_per_hour", it) }
  }

  private val replySteps = listOf(
    mapOf("id" to "bat", "tool" to "device.get_battery"),
    mapOf("id" to "reply", "tool" to "messaging.send_sms", "args" to mapOf("to" to "{{event.payload.from}}", "text" to "Battery {{steps.bat.result.percent}}%")),
  )

  private fun sms(from: String, text: String, corr: String? = null) =
    broker.emit("messaging.sms_received", mapOf("from" to from, "text" to text, "message_id" to "m1"), corr)

  private fun runs(d: List<WorkflowEngine.Decision>) = runBlocking { d.filterIsInstance<WorkflowEngine.Decision.Run>().forEach { withTimeout(5_000) { it.job.join() } } }

  // ---------------------------------------------------------------- storage and ownership

  @Test fun `crud and ownership isolation`() {
    assertTrue(call("automation.save", alice, "name" to "status", "steps" to replySteps).ok)
    assertTrue(call("automation.save", bob, "name" to "status", "steps" to listOf(mapOf("tool" to "device.get_battery"))).ok) // same name, other owner
    val again = call("automation.save", alice, "name" to "status", "steps" to replySteps, "description" to "v2")
    assertEquals(false, again.data["created"]) // replace, not duplicate

    val aliceList = call("automation.list", alice).data["workflows"] as List<*>
    assertEquals(1, aliceList.size)
    assertEquals(2, (call("automation.list", admin).data["workflows"] as List<*>).size) // admin sees all
    assertEquals("v2", call("automation.get", alice, "name" to "status").data["description"])
    // Bob addresses "status" and gets his own; he cannot reach Alice's even by naming her as owner.
    assertEquals(1, (call("automation.get", bob, "name" to "status", "owner" to alice.peerId).data["steps"] as List<*>).size)
    assertTrue(call("automation.delete", bob, "name" to "nope").errorCode == "automation.not_found")
    // Admin can address Alice's by owner.
    assertTrue(call("automation.enable", admin, "name" to "status", "enabled" to false, "owner" to alice.peerId).ok)
    assertFalse(WorkflowStore(db).get(alice.peerId, "status")!!.enabled)
    assertTrue(call("automation.delete", alice, "name" to "status").ok)
    assertNull(WorkflowStore(db).get(alice.peerId, "status"))
    assertNotNull(WorkflowStore(db).get(bob.peerId, "status"))
  }

  @Test fun `save validates steps, nesting, trigger and scope`() {
    assertEquals("invalid_params", call("automation.save", alice, "name" to "x", "steps" to listOf(mapOf("tool" to "automation.run_saved"))).errorCode)
    assertEquals("invalid_params", call("automation.save", alice, "name" to "bad/name", "steps" to replySteps).errorCode)
    assertEquals("invalid_params", call("automation.save", alice, "name" to "x", "steps" to replySteps, "trigger" to mapOf("schedule" to mapOf("every_minutes" to 5))).errorCode)
    assertEquals("invalid_params", call("automation.save", alice, "name" to "x", "steps" to replySteps, "trigger" to smsTrigger(mapOf("text" to mapOf("regex" to "(")))).errorCode)
    // Bob holds no messaging.* scope, so he may not subscribe a workflow to SMS.
    assertEquals("policy.denied", call("automation.save", bob, "name" to "x", "steps" to replySteps, "trigger" to smsTrigger()).errorCode)
  }

  // ---------------------------------------------------------------- matching

  @Test fun `where matching`() {
    val payload = Json.toJson(mapOf("from" to "+14695551234", "text" to "Status please", "level" to 18, "meta" to mapOf("sim" to 1))) as JsonObject
    fun w(vararg p: Pair<String, Any?>) = Matcher.where(Json.toJson(mapOf(*p)) as JsonObject, payload)
    assertTrue(w("from" to mapOf("in" to listOf("4695551234", "+15550000000")), "text" to mapOf("contains" to "status")))
    assertTrue(w("from" to "(469) 555-1234")) // bare value = equals; phone numbers compare as the same line
    assertTrue(w("text" to mapOf("regex" to "^Status")))
    assertFalse(w("text" to mapOf("regex" to "^status")))  // regex is case-sensitive unless (?i)
    assertTrue(w("level" to mapOf("lt" to 20, "gt" to 10)))
    assertFalse(w("level" to mapOf("gt" to 20)))
    assertTrue(w("meta.sim" to 1))
    assertFalse(w("missing" to mapOf("contains" to "")))
    assertFalse(w("text" to mapOf("contains" to "status"), "from" to "+15550000000"))
  }

  // ---------------------------------------------------------------- triggers

  @Test fun `an event trigger runs the workflow as its owner with the event bound`() {
    call("automation.save", alice, "name" to "status", "steps" to replySteps, "trigger" to smsTrigger(mapOf("text" to mapOf("contains" to "status"))))
    assertTrue(engine.onEvent(sms("+14695551234", "hello")).isEmpty()) // where does not match
    val d = engine.onEvent(sms("+14695551234", "Status?"))
    assertEquals(1, d.size); runs(d)
    val reply = calls.single { it.first == "messaging.send_sms" }
    assertEquals("+14695551234", reply.second["to"])
    assertEquals("Battery 57%", reply.second["text"])
    assertEquals(alice, reply.third.principal)
    assertTrue(reply.third.correlationId.startsWith("corr_wf_"))
    val wf = WorkflowStore(db).get(alice.peerId, "status")!!
    assertEquals(1L, wf.runCount)
    assertEquals("true", (wf.lastResult!!["ok"] as JsonPrimitive).content)
    // Announced on the bus under the run's correlation id.
    val topics = broker.recent(10).map { it.topic }
    assertTrue("automation.run_started" in topics && "automation.run_finished" in topics)
  }

  @Test fun `cooldown and hourly cap`() {
    call("automation.save", alice, "name" to "s", "steps" to replySteps, "trigger" to smsTrigger(cooldown = 10_000, perHour = 2))
    runs(engine.onEvent(sms("+1", "a")))
    now += 5_000
    assertEquals("cooldown", (engine.onEvent(sms("+1", "b")).single() as WorkflowEngine.Decision.Skipped).reason)
    now += 6_000
    runs(engine.onEvent(sms("+1", "c")))
    now += 11_000
    assertEquals("rate_cap", (engine.onEvent(sms("+1", "d")).single() as WorkflowEngine.Decision.Skipped).reason)
    now += 3_600_000 // an hour later the cap has rolled over
    assertTrue(engine.onEvent(sms("+1", "e")).single() is WorkflowEngine.Decision.Run)
  }

  @Test fun `loop guard is global - an event from any workflow run triggers no workflow`() {
    call("automation.save", alice, "name" to "a", "steps" to replySteps, "trigger" to smsTrigger(cooldown = 1_000))
    call("automation.save", alice, "name" to "b", "steps" to replySteps, "trigger" to smsTrigger(cooldown = 1_000))
    val first = engine.onEvent(sms("+1", "x")).filterIsInstance<WorkflowEngine.Decision.Run>()
    assertEquals(2, first.size); runs(first)
    now += 2_000
    // An event stamped with a run's correlation id (as every step-caused event is) re-triggers neither workflow:
    // not the one that caused it, and not the other one (no ping-pong through each other).
    val caused = engine.onEvent(sms("+1", "y", corr = first[0].correlationId))
    assertEquals(listOf("loop_guard", "loop_guard"), caused.map { (it as WorkflowEngine.Decision.Skipped).reason })
    // An unrelated correlation id does trigger.
    assertEquals(2, engine.onEvent(sms("+1", "z", corr = "corr_other")).count { it is WorkflowEngine.Decision.Run })
  }

  @Test fun `one run at a time, one queued, the rest skipped`() = runBlocking {
    call("automation.save", alice, "name" to "s", "steps" to replySteps, "trigger" to smsTrigger(cooldown = 1_000))
    hold = CompletableDeferred()
    val first = engine.onEvent(sms("+1", "a")).single() as WorkflowEngine.Decision.Run
    assertTrue(engine.onEvent(sms("+1", "b")).single() is WorkflowEngine.Decision.Queued)
    assertEquals("busy", (engine.onEvent(sms("+1", "c")).single() as WorkflowEngine.Decision.Skipped).reason)
    now += 2_000 // past the cooldown, so the queued run starts as soon as the first ends
    hold!!.complete(Unit)
    first.job.join()
    // The queued run starts on its own after the first finishes.
    withTimeout(5_000) { while (WorkflowStore(db).get(alice.peerId, "s")!!.runCount < 2L) delay(20) }
    assertEquals(2L, WorkflowStore(db).get(alice.peerId, "s")!!.runCount)
  }

  @Test fun `emergency stop and revocation disable workflows`() {
    call("automation.save", alice, "name" to "a", "steps" to replySteps, "trigger" to smsTrigger())
    call("automation.save", bob, "name" to "b", "steps" to replySteps)
    engine.onOwnerRevoked(alice.peerId)
    val a = WorkflowStore(db).get(alice.peerId, "a")!!
    assertFalse(a.enabled); assertEquals("owner_revoked", a.disabledReason)
    assertTrue(WorkflowStore(db).get(bob.peerId, "b")!!.enabled)
    assertTrue(engine.onEvent(sms("+1", "x")).isEmpty())
    engine.disableAll("emergency_stop")
    assertEquals("emergency_stop", WorkflowStore(db).get(bob.peerId, "b")!!.disabledReason)
    // A revoked owner's workflow cannot be re-enabled.
    principals.remove(alice.peerId)
    assertEquals("automation.owner_unavailable", call("automation.enable", admin, "name" to "a", "enabled" to true, "owner" to alice.peerId).errorCode)
  }

  @Test fun `triggers re-arm after a restart`() {
    call("automation.save", alice, "name" to "sched", "steps" to replySteps, "trigger" to mapOf("schedule" to mapOf("every_minutes" to 30)))
    call("automation.save", alice, "name" to "ev", "steps" to replySteps, "trigger" to smsTrigger())
    val armedAt = armed.last()!!
    assertEquals(now + 30 * 60_000L, armedAt)
    // "Process restart": a fresh engine over the same database, as Runtime.init builds on every start.
    armed.clear()
    val restarted = newEngine().also { it.reload() }
    assertEquals(armedAt, armed.last())
    assertEquals(1, restarted.onEvent(sms("+1", "x")).size)
    // The alarm fires: the due schedule runs and the next one is armed 30 min on.
    now = armedAt
    runs(restarted.onAlarm())
    assertEquals(1L, WorkflowStore(db).get(alice.peerId, "sched")!!.runCount)
    assertEquals(armedAt + 30 * 60_000L, armed.last())
    // Phone off for 2 h: the missed run is logged and skipped to the next occurrence, not run late.
    now += 2 * 3_600_000L
    newEngine().reload()
    val wf = WorkflowStore(db).get(alice.peerId, "sched")!!
    assertEquals(now + 30 * 60_000L, wf.nextRunAt)
    assertTrue(WorkflowStore(db).recentLog(wf.id).any { it["status"] == "missed" })
  }

  @Test fun `daily schedule honours time and days`() {
    val zone = ZoneId.of("America/Chicago")
    val spec = Triggers.parse(Json.toJson(mapOf("schedule" to mapOf("daily_at" to "07:30", "days" to listOf("mon", "fri")))) as JsonObject)
    val t = spec.trigger as Trigger.Daily
    assertEquals(LocalTime.of(7, 30), t.time)
    val wed = ZonedDateTime.of(2026, 9, 23, 12, 0, 0, 0, zone) // a Wednesday
    val next = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(Triggers.next(spec, wed.toInstant().toEpochMilli(), zone)!!), zone)
    assertEquals(DayOfWeek.FRIDAY, next.dayOfWeek); assertEquals(7, next.hour); assertEquals(30, next.minute)
    try { Triggers.parse(Json.toJson(mapOf("schedule" to mapOf("daily_at" to "25:00"))) as JsonObject); fail() } catch (_: TriggerError) {}
  }

  // ---------------------------------------------------------------- if

  @Test fun `a false if skips the step and the batch goes on`() = runBlocking {
    val exec = AutomationExecutor({ _, tool, _, _ ->
      DispatchOutcome.Result(buildJsonObject { put("isError", false); put("structuredContent", Json.toJson(if (tool == "ai.ask") mapOf("json" to mapOf("action" to "ignore")) else mapOf("done" to tool))) })
    }, bg)
    val batch = Batches.parse(mapOf("steps" to listOf(
      mapOf("id" to "x", "tool" to "ai.ask", "args" to mapOf("prompt" to "p")),
      mapOf("id" to "reply", "tool" to "messaging.send_sms", "if" to mapOf("{{steps.x.result.json.action}}" to mapOf("equals" to "reply"))),
      mapOf("id" to "note", "tool" to "notifications.post", "if" to mapOf("{{steps.x.result.json.action}}" to mapOf("in" to listOf("ignore", "log")))),
    )))
    val r = exec.run(batch, alice, CallContext(alice.peerId, "c", principal = alice))
    assertTrue(r.completed)
    assertTrue(r.steps[1].skipped && r.steps[1].ok)
    assertFalse(r.steps[2].skipped)
    // A malformed if is refused at parse time.
    try { Batches.parse(mapOf("steps" to listOf(mapOf("tool" to "x.y", "if" to mapOf("{{a}}" to mapOf("like" to "z")))))); fail() } catch (_: dev.symbiosis.runtime.automation.BatchError) {}
  }

  // ---------------------------------------------------------------- ai.ask

  /** A one-connection-at-a-time HTTP/1.1 server on a raw socket (unit tests compile against android.jar: no com.sun.net.httpserver). */
  private fun fakeRouter(bodies: MutableList<String>, reply: (String) -> String): java.net.ServerSocket {
    val server = java.net.ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
    Thread {
      while (!server.isClosed) {
        val sock = runCatching { server.accept() }.getOrNull() ?: break
        sock.use { so ->
          val input = java.io.BufferedInputStream(so.getInputStream())
          val headers = StringBuilder()
          while (!headers.endsWith(CRLF + CRLF)) { val b = input.read(); if (b < 0) break; headers.append(b.toChar()) }
          val len = Regex("(?i)content-length:\\s*(\\d+)").find(headers)?.groupValues?.get(1)?.toInt() ?: 0
          val buf = ByteArray(len)
          var off = 0
          while (off < len) { val n = input.read(buf, off, len - off); if (n < 0) break; off += n }
          val req = String(buf, Charsets.UTF_8)
          bodies += req
          val out = reply(req).toByteArray()
          val head = "HTTP/1.1 200 OK" + CRLF + "Content-Type: application/json" + CRLF + "Content-Length: ${out.size}" + CRLF + "Connection: close" + CRLF + CRLF
          so.getOutputStream().apply { write(head.toByteArray()); write(out); flush() }
        }
      }
    }.apply { isDaemon = true; start() }
    return server
  }

  @Test fun `ai ask against a fake router`() {
    val bodies = Collections.synchronizedList(mutableListOf<String>())
    val server = fakeRouter(bodies) { req ->
      val content = if (req.contains("json_object")) "```json\n{\"urgent\": true, \"why\": \"flood\"}\n```" else "Hello from voice"
      """{"model":"voice","choices":[{"message":{"role":"assistant","content":${JsonPrimitive(content)}}}],"usage":{"total_tokens":9}}"""
    }
    try {
      val cfg = AiConfig(baseUrl = "http://127.0.0.1:${server.localPort}/v1", model = "voice")
      val plain = AiAsk.ask(cfg, mapOf("prompt" to "hi", "system" to "be brief"))
      assertTrue(plain.errorDetail, plain.ok)
      assertEquals("Hello from voice", plain.data["text"])
      val req = Json.lenient.parseToJsonElement(bodies[0]) as JsonObject
      assertEquals(JsonPrimitive("voice"), req["model"]); assertEquals(JsonPrimitive(false), req["stream"]); assertEquals(JsonPrimitive("none"), req["reasoning_effort"])
      val js = AiAsk.ask(cfg, mapOf("prompt" to "classify", "json" to true))
      assertTrue(js.errorDetail, js.ok)
      assertEquals(JsonPrimitive(true), (js.data["json"] as JsonObject)["urgent"])
      // Unreachable router: a clean error, not an exception.
      val down = AiAsk.ask(AiConfig(baseUrl = "http://127.0.0.1:1/v1", timeoutMs = 2_000), mapOf("prompt" to "x"))
      assertEquals("ai.unreachable", down.errorCode)
      assertEquals("http://h:1/v1/chat/completions", AiAsk.endpoint("http://h:1"))
    } finally { server.close() }
  }

  private companion object { const val CRLF = "\r\n" }
}
