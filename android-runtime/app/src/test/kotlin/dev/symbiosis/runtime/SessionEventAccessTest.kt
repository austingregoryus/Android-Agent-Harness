package dev.symbiosis.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.symbiosis.runtime.automation.AlarmSink
import dev.symbiosis.runtime.automation.AutomationExecutor
import dev.symbiosis.runtime.automation.StepDispatcher
import dev.symbiosis.runtime.automation.WorkflowCapabilities
import dev.symbiosis.runtime.automation.WorkflowEngine
import dev.symbiosis.runtime.automation.WorkflowStore
import dev.symbiosis.runtime.capabilities.CallContext
import dev.symbiosis.runtime.capabilities.Capability
import dev.symbiosis.runtime.capabilities.NativeCapabilities
import dev.symbiosis.runtime.capabilities.Principal
import dev.symbiosis.runtime.core.DispatchOutcome
import dev.symbiosis.runtime.events.Event
import dev.symbiosis.runtime.events.EventBroker
import dev.symbiosis.runtime.events.SubscriptionStore
import dev.symbiosis.runtime.events.Topics
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.sessions.SessionManager
import dev.symbiosis.runtime.storage.Db
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Session-scoped events (a call's transcript, its state) reach only the peer that owns the session, on every
 * delivery path. A telephony.* scope used to be enough to read another peer's live call transcript.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionEventAccessTest {
  private lateinit var ctx: Context
  private lateinit var db: Db
  private lateinit var broker: EventBroker
  private lateinit var sessions: SessionManager
  private lateinit var subs: SubscriptionStore
  private lateinit var events: Map<String, Capability>

  private val scopes = listOf("telephony.*", "events.*", "automation.*", "device.*")
  private val alice = Principal("peer_alice", "alice", scopes, ActionClass.EXTERNAL_COMMITMENT)
  private val bob = Principal("peer_bob", "bob", scopes, ActionClass.EXTERNAL_COMMITMENT)
  private val admin = Principal("peer_admin", "admin", listOf("*"), ActionClass.DESTRUCTIVE)

  @Before fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    ctx.deleteDatabase("symbiosis.db")
    db = Db(ctx)
    broker = EventBroker(db, "dev_test")
    sessions = SessionManager(db)
    subs = SubscriptionStore(db, broker)
    events = NativeCapabilities.events(broker, subs) { sessions.ownerOf(it) }.associateBy { it.name }
  }

  private fun transcript(sessionId: String?, text: String): Event =
    broker.emit(Topics.CALL_TRANSCRIPT, mapOf("session_id" to sessionId, "text" to text, "final" to true, "turn_id" to 1))

  private fun run(name: String, p: Principal, vararg args: Pair<String, Any?>) = runBlocking {
    events.getValue(name).execute(mapOf(*args), CallContext(p.peerId, "corr_test", principal = p))
  }

  @Test fun `only the session owner and the admin see a session's events`() {
    val aliceCall = sessions.create("call", alice.peerId, "corr_a", emptyMap())
    val ownerless = sessions.create("call", null, "corr_n", emptyMap())
    val owner: (String) -> String? = { sessions.ownerOf(it) }
    val ev = transcript(aliceCall.sessionId, "my card number is ...")
    assertTrue(alice.canSeeEvent(ev, owner))
    assertFalse(bob.canSeeEvent(ev, owner))
    assertTrue(admin.canSeeEvent(ev, owner))
    // Unknown and ownerless sessions are admin-only, like /ws/sessions/{id}.
    assertFalse(alice.canSeeEvent(transcript("call_doesnotexist", "x"), owner))
    assertFalse(alice.canSeeEvent(transcript(ownerless.sessionId, "x"), owner))
    assertTrue(admin.canSeeEvent(transcript(ownerless.sessionId, "x"), owner))
    // No session named: topic scope alone decides, as before.
    assertTrue(bob.canSeeEvent(transcript(null, "x"), owner))
    assertTrue(bob.canSeeEvent(broker.emit(Topics.CALL_RINGING, mapOf("number" to "+1555", "direction" to "in", "session_id" to null)), owner))
    // Topic scope still applies first.
    val noTelephony = Principal("peer_c", "c", listOf("events.*"), ActionClass.EXTERNAL_COMMITMENT)
    assertFalse(noTelephony.canSeeEvent(transcript(null, "x"), owner))
  }

  @Test fun `an ended session's events stay owned after the owner cache is gone`() {
    val s = sessions.create("call", alice.peerId, "corr_a", emptyMap())
    sessions.transition(s.sessionId, dev.symbiosis.runtime.sessions.SessionState.ENDED)
    val fresh = SessionManager(db) // a restarted process: nothing cached, ownership comes from the sessions table
    assertEquals(alice.peerId, fresh.ownerOf(s.sessionId))
    assertFalse(bob.canSeeEvent(transcript(s.sessionId, "x")) { fresh.ownerOf(it) })
  }

  @Test fun `replay drops another peer's call transcript`() {
    val aliceCall = sessions.create("call", alice.peerId, "corr_a", emptyMap())
    val bobCall = sessions.create("call", bob.peerId, "corr_b", emptyMap())
    val sub = run("events.subscribe", bob, "topic_glob" to "call.*")
    assertTrue(sub.ok)
    transcript(aliceCall.sessionId, "alice's secret")
    transcript(bobCall.sessionId, "bob's own")
    val r = run("events.replay", bob, "subscription_id" to sub.data["subscription_id"], "since_id" to 0)
    @Suppress("UNCHECKED_CAST")
    val texts = (r.data["events"] as List<Map<String, Any?>>).map { (it["payload"] as Map<*, *>)["text"] }
    assertEquals(listOf("bob's own"), texts)
  }

  @Test fun `events wait skips another peer's call event`() = runBlocking {
    val aliceCall = sessions.create("call", alice.peerId, "corr_a", emptyMap())
    val bobCall = sessions.create("call", bob.peerId, "corr_b", emptyMap())
    val waiting = async(Dispatchers.Default) { run("events.wait", bob, "topic_glob" to "call.transcript", "timeout_ms" to 5_000) }
    delay(500) // let the wait subscribe to the live flow
    transcript(aliceCall.sessionId, "alice's secret")
    transcript(bobCall.sessionId, "bob's own")
    val r = waiting.await()
    assertEquals(false, r.data["timed_out"])
    @Suppress("UNCHECKED_CAST")
    assertEquals("bob's own", ((r.data["event"] as Map<String, Any?>)["payload"] as Map<*, *>)["text"])
  }

  @Test fun `a workflow is not triggered by another peer's call`() {
    val principals = mapOf(alice.peerId to alice, bob.peerId to bob)
    val dispatcher = StepDispatcher { _, tool, _, _ -> DispatchOutcome.Result(buildJsonObject { put("isError", false); put("structuredContent", buildJsonObject { put("tool", tool) }) }) }
    val bg = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = WorkflowEngine(
      store = WorkflowStore(db), broker = broker, executor = AutomationExecutor(dispatcher, bg),
      principalFor = { principals[it] }, emergencyStopped = { false }, alarms = AlarmSink { }, scope = bg,
      sessionOwner = { sessions.ownerOf(it) },
    )
    val caps = WorkflowCapabilities.all(engine) { _, _ -> true }.associateBy { it.name }
    val saved = runBlocking {
      caps.getValue("automation.save").execute(
        mapOf("name" to "on-transcript", "steps" to listOf(mapOf("tool" to "device.get_battery")), "trigger" to mapOf("event" to mapOf("topic" to "call.transcript"))),
        CallContext(bob.peerId, "corr_save", principal = bob),
      )
    }
    assertTrue(saved.errorDetail ?: "", saved.ok)
    val aliceCall = sessions.create("call", alice.peerId, "corr_a", emptyMap())
    val bobCall = sessions.create("call", bob.peerId, "corr_b", emptyMap())
    assertTrue(engine.onEvent(transcript(aliceCall.sessionId, "alice's secret")).isEmpty())
    val own = engine.onEvent(transcript(bobCall.sessionId, "bob's own"))
    assertEquals(1, own.size)
    assertTrue(own.single() is WorkflowEngine.Decision.Run)
    runBlocking { (own.single() as WorkflowEngine.Decision.Run).job.join() }
  }

  @Test fun `the payload's session_id is read only when it is a string`() {
    fun ev(sid: kotlinx.serialization.json.JsonElement?) = Event(1, Topics.CALL_TRANSCRIPT, 0, null, JsonObject(if (sid == null) emptyMap() else mapOf("session_id" to sid)))
    val owner: (String) -> String? = { if (it == "call_x") alice.peerId else null }
    assertTrue(bob.canSeeEvent(ev(null), owner))
    assertTrue(bob.canSeeEvent(ev(JsonPrimitive(null as String?)), owner))
    assertFalse(bob.canSeeEvent(ev(JsonPrimitive("call_x")), owner))
    assertTrue(alice.canSeeEvent(ev(JsonPrimitive("call_x")), owner))
  }
}
