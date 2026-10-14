package dev.symbiosis.runtime

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.symbiosis.runtime.audit.AuditEntry
import dev.symbiosis.runtime.audit.AuditKind
import dev.symbiosis.runtime.audit.AuditStore
import dev.symbiosis.runtime.capabilities.Capability
import dev.symbiosis.runtime.capabilities.CapabilityResult
import dev.symbiosis.runtime.events.EventBroker
import dev.symbiosis.runtime.events.FilterClause
import dev.symbiosis.runtime.events.SubscriptionStore
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.policy.ApprovalManager
import dev.symbiosis.runtime.policy.PolicyConfig
import dev.symbiosis.runtime.policy.PolicyDecision
import dev.symbiosis.runtime.policy.PolicyEngine
import dev.symbiosis.runtime.policy.PolicyOutcome
import dev.symbiosis.runtime.policy.RateClass
import dev.symbiosis.runtime.policy.RateLimit
import dev.symbiosis.runtime.policy.RateLimiter
import dev.symbiosis.runtime.storage.Db
import dev.symbiosis.runtime.storage.SecureStore
import dev.symbiosis.runtime.transport.PeerStore
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression tests for the stateful P0/P1 findings, run against a real SQLite database under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatefulPathsTest {
  private lateinit var ctx: Context
  private lateinit var db: Db

  @Before fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    ctx.deleteDatabase("symbiosis.db")
    db = Db(ctx)
  }

  private fun cap(name: String, ac: ActionClass, rate: RateClass = RateClass.NONE) =
    Capability(name, "", emptyList(), ac, rateClass = rate) { _, _ -> CapabilityResult.ok(emptyMap()) }

  // ---- P0-4 / P1-1: approvals -------------------------------------------------------------

  @Test fun `approval decided before the caller waits is not lost and executes once`() = runBlocking {
    val am = ApprovalManager(ctx, db)
    // Resolve concurrently, very early: the waiter must already be registered when the row appears.
    val job = launch {
      var pa = am.pending().firstOrNull()
      while (pa == null) { delay(5); pa = am.pending().firstOrNull() }
      am.resolve(pa.id, approved = true)
    }
    val (pa, decision) = am.createAndAwait("messaging.send_sms", ActionClass.COMMUNICATION, mapOf("to" to "+1"), "peer_a", "corr", 60_000, "test", waitMs = 5_000)
    job.join()
    assertEquals(true, decision)                    // in-window, not late
    assertEquals("approved", am.get(pa.id)!!.status)
    assertNull(am.resolve(pa.id, approved = true))  // second tap is a no-op
  }

  @Test fun `late approval is reported as late and expired actions cannot be approved`() = runBlocking {
    val am = ApprovalManager(ctx, db)
    val (pa, decision) = am.createAndAwait("x", ActionClass.DEVICE_MUTATION, emptyMap(), "peer_a", null, 60_000, "t", waitMs = 0)
    assertNull(decision)
    val res = am.resolve(pa.id, approved = true)
    assertNotNull(res); assertTrue(res!!.late)
    val (expired, _) = am.createAndAwait("y", ActionClass.DEVICE_MUTATION, emptyMap(), "peer_a", null, ttlMs = -1, reason = "t", waitMs = 0)
    assertNull(am.resolve(expired.id, approved = true))
  }

  @Test fun `cancelPending cancels the peer's pending actions only`() = runBlocking {
    val am = ApprovalManager(ctx, db)
    val a = async { am.createAndAwait("x", ActionClass.DEVICE_MUTATION, emptyMap(), "peer_a", null, 60_000, "t", waitMs = 2_000) }
    val b = async { am.createAndAwait("x", ActionClass.DEVICE_MUTATION, emptyMap(), "peer_b", null, 60_000, "t", waitMs = 2_000) }
    delay(100)
    assertEquals(1, am.cancelPending("peer_a", "revoked"))
    assertEquals(false, a.await().second)   // cancelled waiter sees "denied"
    assertNull(b.await().second)            // untouched peer times out normally
    assertEquals("cancelled", am.pending().let { am.get(a.getCompleted().first.id)!!.status })
  }

  // ---- P0-5: idempotency ------------------------------------------------------------------

  @Test fun `idempotency lookup is peer scoped and success only`() {
    val audit = AuditStore(db, "seed")
    val ok = buildJsonObject { put("isError", false); put("x", 1) }
    audit.append(AuditEntry(ts = 1, correlationId = "c", peerId = "peer_a", kind = AuditKind.TOOL_OUTCOME, capability = "t", result = ok, success = true, idempotencyKey = "k1"))
    audit.append(AuditEntry(ts = 2, correlationId = "c", peerId = "peer_a", kind = AuditKind.TOOL_OUTCOME, capability = "t", result = ok, success = false, errorCode = "capability.failed", idempotencyKey = "k2"))
    assertNotNull(audit.findOutcome("k1", "peer_a"))
    assertNull(audit.findOutcome("k1", "peer_b"))   // another peer cannot replay it
    assertNull(audit.findOutcome("k2", "peer_a"))   // failures are never replayed
  }

  // ---- P1-11: audit chain survives oversized results ----------------------------------------

  @Test fun `oversized results are stored as valid JSON and the chain verifies`() {
    val audit = AuditStore(db, "seed")
    val big = buildJsonObject { put("blob", "x".repeat(20_000)) }
    audit.append(AuditEntry(ts = 1, correlationId = null, peerId = null, kind = AuditKind.TOOL_OUTCOME, capability = "t", result = big, success = true))
    audit.append(AuditEntry(ts = 2, correlationId = null, peerId = null, kind = AuditKind.TOOL_INTENT, capability = "t", args = JsonPrimitive("a")))
    assertNull(audit.verifyChain())
    val stored = audit.recent(2).last().result!!
    assertTrue(stored.toString().contains("\"truncated\":true"))
  }

  // ---- P1-2: rate reservation -----------------------------------------------------------------

  @Test fun `rate limit is reserved at decision time and released on failure`() {
    val engine = PolicyEngine(db, RateLimiter(db))
    val c = cap("telephony.place_call", ActionClass.COMMUNICATION, RateClass.CALL) // 5/hour
    val outcomes = (1..6).map { engine.evaluate(c, emptyMap()) }
    assertEquals(5, outcomes.count { it is PolicyOutcome.Allow })
    assertTrue(outcomes.last() is PolicyOutcome.Deny && (outcomes.last() as PolicyOutcome.Deny).code == "policy.rate_limited")
    engine.release(outcomes.first())                // one slot handed back
    assertTrue(engine.evaluate(c, emptyMap()) is PolicyOutcome.Allow)
  }

  @Test fun `human approval satisfies the approve step but deny checks still apply`() {
    val engine = PolicyEngine(db, RateLimiter(db))
    engine.setAsksFirst(true)   // approvals are off by default since 2026-09-23; this is the owner's switch
    val c = cap("apps.send_intent", ActionClass.DEVICE_MUTATION)
    assertTrue(engine.evaluate(c, emptyMap()) is PolicyOutcome.Approve)
    assertTrue(engine.evaluate(c, emptyMap(), approved = true) is PolicyOutcome.Allow)
    engine.setEmergencyStop(true)
    assertTrue(engine.evaluate(c, emptyMap(), approved = true) is PolicyOutcome.Deny)
  }

  // ---- unattended by default (2026-09-23): the owner is not in front of the phones -------------

  @Test fun `texts, calls and screen control run without a tap by default`() {
    val engine = PolicyEngine(db, RateLimiter(db))
    for (c in listOf(
      cap("messaging.send_sms", ActionClass.EXTERNAL_COMMITMENT, RateClass.SMS),
      cap("telephony.place_call", ActionClass.EXTERNAL_COMMITMENT, RateClass.CALL),
      cap("ui.tap_element", ActionClass.DEVICE_MUTATION, RateClass.MUTATION),
    )) assertTrue("${c.name} should be allowed unattended", engine.evaluate(c, emptyMap()) is PolicyOutcome.Allow)
    // Destructive stays denied, and the two owner switches still stop everything that is not a read.
    assertTrue(engine.evaluate(cap("x.delete", ActionClass.DESTRUCTIVE), emptyMap()) is PolicyOutcome.Deny)
    engine.setObserveOnly(true)
    assertTrue(engine.evaluate(cap("ui.tap_element", ActionClass.DEVICE_MUTATION), emptyMap()) is PolicyOutcome.Deny)
  }

  @Test fun `screen control fits real use but still stops a runaway loop`() {
    val engine = PolicyEngine(db, RateLimiter(db))
    val tap = cap("ui.tap_element", ActionClass.DEVICE_MUTATION, RateClass.MUTATION)
    val outcomes = (1..301).map { engine.evaluate(tap, emptyMap()) }
    assertEquals(300, outcomes.count { it is PolicyOutcome.Allow })
    assertTrue((outcomes.last() as PolicyOutcome.Deny).code == "policy.rate_limited")
  }

  @Test fun `a saved config from before is migrated once, and the owner's later choice sticks`() {
    // What the Pixel had on disk: the old APPROVE defaults, the old 30/hour screen limit, and an allowlist.
    val old = PolicyConfig().let { d ->
      d.copy(
        classDefaults = d.classDefaults + mapOf(
          ActionClass.EXTERNAL_COMMITMENT to PolicyDecision.APPROVE, ActionClass.DEVICE_MUTATION to PolicyDecision.APPROVE),
        rateLimits = d.rateLimits + (RateClass.MUTATION to RateLimit(30, 200)),
        contactAllowlist = listOf("+12025550123"),
      )
    }
    PolicyEngine(db, RateLimiter(db)).updateConfig(old)
    db.writableDatabase.delete("settings", "key = ?", arrayOf("policy_migrated_unattended_v1"))

    val migrated = PolicyEngine(db, RateLimiter(db))
    assertTrue(!migrated.asksFirst)
    assertTrue(migrated.config.value.contactAllowlist.isEmpty())   // a non-empty list would re-impose approvals
    assertEquals(300, migrated.config.value.rateLimits.getValue(RateClass.MUTATION).perHour)
    val sms = Capability("messaging.send_sms", "", emptyList(), ActionClass.EXTERNAL_COMMITMENT, targetArg = "to") { _, _ -> CapabilityResult.ok(emptyMap()) }
    assertTrue(migrated.evaluate(sms, mapOf("to" to "+15125550100")) is PolicyOutcome.Allow)

    // The owner turns approvals back on: a restart must NOT migrate that away again.
    migrated.setAsksFirst(true)
    assertTrue(PolicyEngine(db, RateLimiter(db)).asksFirst)
  }

  @Test fun `a config row that fails to decode is not wiped by the unattended migration`() {
    // Simulate corruption: a row exists under the policy_config key but is not valid PolicyConfig JSON. This
    // is not "nothing stored yet" -- migrateUnattended must not treat it that way and persist fresh defaults
    // over whatever the user had.
    db.writableDatabase.insertWithOnConflict(
      "settings", null,
      ContentValues().apply { put("key", "policy_config"); put("value", "{not valid json") },
      android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
    )
    PolicyEngine(db, RateLimiter(db))

    val stored = db.readableDatabase.query("settings", arrayOf("value"), "key = ?", arrayOf("policy_config"), null, null, null)
      .use { c -> c.moveToFirst(); c.getString(0) }
    assertEquals("{not valid json", stored)   // untouched, not overwritten with defaults
    val migrated = db.readableDatabase.query("settings", arrayOf("value"), "key = ?", arrayOf("policy_migrated_unattended_v1"), null, null, null)
      .use { c -> c.moveToFirst() }
    assertFalse(migrated)   // marker left unset, so this is retried once the row is readable again

    // Once the row is fixed (the owner re-saves settings), migration still runs exactly once, as normal, and
    // leaves fields it doesn't own -- the denylist -- alone.
    PolicyEngine(db, RateLimiter(db)).updateConfig(PolicyConfig().copy(contactDenylist = listOf("+15125550199")))
    val fixed = PolicyEngine(db, RateLimiter(db))
    assertEquals(listOf("+15125550199"), fixed.config.value.contactDenylist)
  }

  @Test fun `denylist cannot be evaded with dial prefixes or national spelling`() {
    val engine = PolicyEngine(db, RateLimiter(db))
    engine.updateConfig(engine.config.value.copy(contactDenylist = listOf("+15125550199")))
    val c = Capability("messaging.send_sms", "", emptyList(), ActionClass.COMMUNICATION, targetArg = "to") { _, _ -> CapabilityResult.ok(emptyMap()) }
    for (spelling in listOf("+15125550199", "5125550199", "15125550199", "*67,+15125550199", "0015125550199", "+1 (512) 555-0199")) {
      val o = engine.evaluate(c, mapOf("to" to spelling))
      assertTrue("$spelling should be denied", o is PolicyOutcome.Deny && (o as PolicyOutcome.Deny).message.contains("denylist"))
    }
    assertTrue(engine.evaluate(c, mapOf("to" to "+15125550100")) is PolicyOutcome.Allow)
  }

  // ---- P1-15: pairing clamp -------------------------------------------------------------------

  @Test fun `pairing clamps to the code and refuses out-of-grant requests`() {
    val peers = PeerStore(db, SecureStore(ctx))
    peers.knownCapabilities = { listOf("messaging.send_sms", "messaging.read_sms", "device.get_battery", "ui.get_tree") }
    val code = peers.mintPairingCode(listOf("messaging.*", "device.get_*"), ActionClass.COMMUNICATION)
    assertNull(peers.redeem(code.code, "greedy", listOf("ui.*"), null))                   // refused, not widened
    val ok = peers.redeem(code.code, "narrow", listOf("messaging.send_sms"), ActionClass.DESTRUCTIVE)!!
    assertEquals(listOf("messaging.send_sms"), ok.first.scopes)
    assertEquals(ActionClass.COMMUNICATION, ok.first.maxActionClass)                  // ceiling clamped
    assertNull(peers.redeem(code.code, "again", null, null))                             // single use
    val dashless = peers.mintPairingCode(listOf("*"), ActionClass.DESTRUCTIVE)
    assertNotNull(peers.redeem(dashless.code.replace("-", ""), "nodash", null, null))
  }

  // ---- replay cursor and queue delivery ----------------------------------------------------------

  @Test fun `replay never regresses the cursor and honours filters`() {
    val broker = EventBroker(db, "dev_test")
    val subs = SubscriptionStore(db, broker)
    val sub = subs.create("peer_a", "messaging.sms_received", listOf(FilterClause("from", "eq", JsonPrimitive("+1"))), "live", null)
    repeat(30) { broker.emit("messaging.sms_received", mapOf("from" to "+2", "text" to "noise $it")) }
    broker.emit("messaging.sms_received", mapOf("from" to "+1", "text" to "hit"))
    repeat(5) { broker.emit("battery.changed", mapOf("percent" to it)) }
    val r = subs.replay(sub, 0, 200)
    assertEquals(listOf("hit"), r.events.map { it.payload["text"]!!.toString().trim('"') })
    assertEquals(broker.lastId(), r.nextSinceId)   // cursor advanced past the scanned non-matches too
    assertFalse(r.truncated)
    assertEquals(sub.subscription_id, subs.create("peer_a", "messaging.sms_received", listOf(FilterClause("from", "eq", JsonPrimitive("+1"))), "live", null).subscription_id) // idempotent
  }
}
