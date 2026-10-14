package dev.symbiosis.runtime

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.symbiosis.runtime.audit.AuditEntry
import dev.symbiosis.runtime.audit.AuditKind
import dev.symbiosis.runtime.audit.AuditStore
import dev.symbiosis.runtime.capabilities.CallContext
import dev.symbiosis.runtime.capabilities.SessionAccess
import dev.symbiosis.runtime.core.Runtime
import dev.symbiosis.runtime.events.EventBroker
import dev.symbiosis.runtime.sessions.CallSessions
import dev.symbiosis.runtime.sessions.SessionManager
import dev.symbiosis.runtime.sessions.SessionState
import dev.symbiosis.runtime.storage.Db
import dev.symbiosis.runtime.telephony.DriverLease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/**
 * Regression tests for the 2026-09-26 review fixes that are testable off-device: audit arg compaction,
 * retention with a verifiable chain, the safe audit read, partial-transcript auditing, the driver lease's
 * timing, the live-call requirement, and the process-restart sweep of stale call sessions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewFixesTest {
  private lateinit var ctx: Context
  private lateinit var db: Db
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @Before fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    ctx.deleteDatabase("symbiosis.db")
    db = Db(ctx)
    SessionAccess.liveCallId = { null }
  }

  @After fun tearDown() {
    scope.cancel()
    SessionAccess.liveCallId = { null }
  }

  // ---- audit: large args ------------------------------------------------------------------------

  @Test fun `large string args are stored as len and sha256 and the chain still verifies`() {
    val audit = AuditStore(db, "seed")
    val clip = "A".repeat(300_000) // a play_audio base64 clip
    audit.append(AuditEntry(ts = 1, correlationId = "c", peerId = "peer_a", kind = AuditKind.TOOL_INTENT, capability = "telephony.play_audio",
      args = buildJsonObject { put("session_id", "call_1"); put("audio_base64", clip); put("api_key", "k") }))
    val stored = audit.recent(1).single().args!!.jsonObject
    assertEquals("call_1", stored["session_id"]!!.jsonPrimitive.content)          // short strings kept verbatim
    assertEquals("[redacted]", stored["api_key"]!!.jsonPrimitive.content)          // redaction still applies
    val compact = stored["audio_base64"]!!.jsonObject
    assertEquals(300_000, compact["len"]!!.jsonPrimitive.content.toInt())
    assertEquals(dev.symbiosis.runtime.core.Ids.sha256Hex(clip), compact["sha256"]!!.jsonPrimitive.content)
    assertNull(audit.verifyChain())
  }

  @Test fun `an SMS-sized body is kept verbatim`() {
    val body = "x".repeat(AuditStore.MAX_ARG_STRING)
    val out = AuditStore.sanitizeArgs(buildJsonObject { put("text", body) }) as JsonObject
    assertEquals(body, out["text"]!!.jsonPrimitive.content)
  }

  // ---- audit: retention -------------------------------------------------------------------------

  private fun fill(audit: AuditStore, n: Int, tsOf: (Int) -> Long) {
    for (i in 0 until n) audit.append(AuditEntry(ts = tsOf(i), correlationId = null, peerId = if (i % 2 == 0) "peer_a" else "peer_b", kind = AuditKind.TOOL_INTENT, capability = "t", args = JsonPrimitive("row $i")))
  }

  @Test fun `retention prunes by age, keeps a floor, and the kept chain verifies from the anchor`() {
    val audit = AuditStore(db, "seed")
    val now = 100L * 24 * 3600 * 1000
    val day = 24L * 3600 * 1000
    fill(audit, 50) { i -> if (i < 30) now - 40 * day else now - day }  // 30 old rows, 20 recent
    assertNull(audit.verifyChain())

    val deleted = audit.prune(now = now, maxAgeMs = 30 * day, maxRows = 10_000, minKeep = 5)
    assertEquals(30, deleted)
    assertEquals(21L, audit.count())                     // 20 recent + the audit.prune entry itself
    val anchor = audit.anchor()!!
    assertEquals(31L, anchor.firstKeptId)
    assertNull("pruned chain must verify from the anchor", audit.verifyChain())
    assertEquals("audit.prune", audit.recent(1).single().capability)

    // Appends after a prune keep chaining, and a second prune moves the anchor forward.
    fill(audit, 5) { now }
    assertNull(audit.verifyChain())
    audit.prune(now = now + 60 * day, maxAgeMs = 30 * day, maxRows = 10_000, minKeep = 3)
    assertTrue(audit.anchor()!!.firstKeptId > 31L)
    assertNull(audit.verifyChain())
  }

  @Test fun `retention caps row count but never drops below the floor`() {
    val audit = AuditStore(db, "seed")
    fill(audit, 40) { 1_000_000L }                       // all "recent"
    audit.prune(now = 1_000_000L, maxAgeMs = Long.MAX_VALUE / 4, maxRows = 10, minKeep = 5)
    assertEquals(11L, audit.count())                      // 10 kept + the prune entry
    assertNull(audit.verifyChain())

    val idle = AuditStore(db, "seed")                     // a quiet month: everything is "old"
    idle.prune(now = Long.MAX_VALUE / 2, maxAgeMs = 1, maxRows = 10_000, minKeep = 8)
    assertEquals(9L, idle.count())                        // the floor, plus the prune entry
    assertNull(idle.verifyChain())
  }

  @Test fun `rows at or after the anchor still cannot be deleted or updated`() {
    val audit = AuditStore(db, "seed")
    fill(audit, 20) { 1L }
    audit.prune(now = 10L, maxAgeMs = 1, maxRows = 10_000, minKeep = 10)
    val anchor = audit.anchor()!!
    try {
      db.writableDatabase.delete("audit_entries", "id = ?", arrayOf(anchor.firstKeptId.toString()))
      fail("deleting a kept row must abort")
    } catch (_: android.database.sqlite.SQLiteException) { }
    try {
      db.writableDatabase.update("audit_entries", ContentValues().apply { put("kind", "x") }, "id = ?", arrayOf(anchor.firstKeptId.toString()))
      fail("updating must abort")
    } catch (_: android.database.sqlite.SQLiteException) { }
    try {
      db.writableDatabase.delete("audit_anchors", null, null)
      fail("anchors are append-only")
    } catch (_: android.database.sqlite.SQLiteException) { }
    assertNull(audit.verifyChain())
  }

  @Test fun `tampering with a kept row is still detected after pruning`() {
    val audit = AuditStore(db, "seed")
    fill(audit, 20) { 1L }
    audit.prune(now = 10L, maxAgeMs = 1, maxRows = 10_000, minKeep = 10)
    // Bypass the trigger the way an attacker with raw file access would: drop it, edit, restore.
    val w = db.writableDatabase
    w.execSQL("DROP TRIGGER audit_no_update")
    w.execSQL("UPDATE audit_entries SET args_json = '\"forged\"' WHERE id = (SELECT MIN(id) FROM audit_entries) + 2")
    assertNotNull(audit.verifyChain())
  }

  // ---- audit: safe recent ------------------------------------------------------------------------

  @Test fun `recent filters by peer in SQL and never loads a huge legacy row`() {
    val audit = AuditStore(db, "seed")
    fill(audit, 150) { it.toLong() }
    // A pre-fix row: args stored whole (well over the read cap), inserted raw.
    db.writableDatabase.insertOrThrow("audit_entries", null, ContentValues().apply {
      put("ts", 999L); put("peer_id", "peer_b"); put("kind", AuditKind.TOOL_INTENT); put("capability", "telephony.play_audio")
      put("args_json", "{\"audio_base64\":\"" + "A".repeat(3_000_000) + "\"}"); put("prev_hash", "x"); put("entry_hash", "y")
    })
    val mine = audit.recent(100, peerId = "peer_a")
    assertEquals(75, mine.size)                           // all of peer_a's, not "peer_a's share of the last 100"
    assertTrue(mine.all { it.peerId == "peer_a" })
    val legacy = audit.recent(1, peerId = "peer_b").single().args!!.jsonObject
    assertEquals("true", legacy["truncated"]!!.jsonPrimitive.content)
  }

  // ---- audit: partial transcripts --------------------------------------------------------------

  @Test fun `interim transcripts are not audited, finals and state changes are`() {
    assertFalse(Runtime.auditsEvent("call.transcript", buildJsonObject { put("final", false); put("text", "hel") }))
    assertTrue(Runtime.auditsEvent("call.transcript", buildJsonObject { put("final", true); put("text", "hello") }))
    assertTrue(Runtime.auditsEvent("call.listening", buildJsonObject { put("listening", false) }))
    assertTrue(Runtime.auditsEvent("session.state", buildJsonObject { put("state", "ENDED") }))
    assertFalse(Runtime.auditsEvent("battery.changed", buildJsonObject { put("percent", 50) }))
  }

  // ---- driver lease --------------------------------------------------------------------------------

  @Test fun `driver lease expires only after the owner is absent for the whole lease`() {
    val start = 1_000_000L
    val lease = DriverLease.LEASE_MS
    // Present (socket open or request in flight): never expires, however old the last request.
    assertFalse(DriverLease.expired(start + 10 * lease, start, lastSeenAt = start, present = true))
    // Absent, last seen at start: just before the lease, not expired; at the lease, expired.
    assertFalse(DriverLease.expired(start + lease - 1, start, lastSeenAt = start, present = false))
    assertTrue(DriverLease.expired(start + lease, start, lastSeenAt = start, present = false))
    // A later request (or socket close) restarts the clock.
    assertFalse(DriverLease.expired(start + lease + 5_000, start, lastSeenAt = start + 10_000, present = false))
    // An owner never seen in this process still gets the full window from when conversation mode started.
    assertFalse(DriverLease.expired(start + lease - 1, start, lastSeenAt = null, present = false))
    assertTrue(DriverLease.expired(start + lease, start, lastSeenAt = null, present = false))
    // A last-seen from before the conversation started does not shorten the window.
    assertFalse(DriverLease.expired(start + lease - 1, start, lastSeenAt = start - lease, present = false))
  }

  // ---- secure storage fallback ------------------------------------------------------------------------

  @Test fun `secure store falls back only on an install whose Keystore never worked`() {
    // Robolectric has no AndroidKeyStore, so the encrypted store never opens here.
    val meta = ctx.getSharedPreferences("symbiosis.secure.meta", Context.MODE_PRIVATE)
    meta.edit().clear().commit()
    val fresh = dev.symbiosis.runtime.storage.SecureStore(ctx)
    assertFalse(fresh.usingKeystore)                     // never worked: the old fallback, and it says so
    val id = fresh.deviceId
    assertEquals(id, dev.symbiosis.runtime.storage.SecureStore(ctx).deviceId)   // and it is stable

    meta.edit().putBoolean("encrypted_ever_opened", true).commit()
    try {
      dev.symbiosis.runtime.storage.SecureStore(ctx)
      fail("must not fall back (and mint a new identity) once the encrypted store has existed")
    } catch (e: IllegalStateException) {
      assertTrue(e.message!!.contains("refusing"))
    } finally { meta.edit().clear().commit() }
  }

  // ---- live-call requirement and the restart sweep ----------------------------------------------------

  private fun ctxFor(peer: String) = CallContext(peer, "corr")

  @Test fun `an owned non-terminal call that is not the live call is refused`() {
    val sessions = SessionManager(db)
    val s = sessions.create("call", "peer_a", "corr", mapOf("target" to "+15125550100"))
    sessions.transition(s.sessionId, SessionState.ACTIVE)

    val notLive = SessionAccess.ownedActiveCall(sessions, s.sessionId, ctxFor("peer_a"))
    assertTrue(notLive is SessionAccess.Access.Denied && notLive.result.errorCode == "session.not_active")

    SessionAccess.liveCallId = { s.sessionId }
    assertTrue(SessionAccess.ownedActiveCall(sessions, s.sessionId, ctxFor("peer_a")) is SessionAccess.Access.Ok)
    // Ownership is still checked first, and still answers "unknown" to a non-owner.
    val other = SessionAccess.ownedActiveCall(sessions, s.sessionId, ctxFor("peer_b"))
    assertTrue(other is SessionAccess.Access.Denied && other.result.errorCode == "session.unknown")
  }

  @Test fun `call sessions left live by a dead process are failed with process_restart`() {
    val sessions = SessionManager(db)
    val stale = sessions.create("call", "peer_a", "corr", mapOf("target" to "+15125550100"))
    sessions.transition(stale.sessionId, SessionState.ACTIVE)
    val done = sessions.create("call", "peer_a", "corr", mapOf("target" to "+15125550101"))
    sessions.transition(done.sessionId, SessionState.ENDED)

    val calls = CallSessions(ctx, sessions, EventBroker(db, "dev_test"), scope)
    assertEquals(listOf(stale.sessionId), calls.failStale("process_restart"))
    val after = sessions.get(stale.sessionId)!!
    assertEquals(SessionState.FAILED, after.state)
    assertEquals("process_restart", after.summary!!["reason"]!!.jsonPrimitive.content)
    assertEquals(SessionState.ENDED, sessions.get(done.sessionId)!!.state)   // terminal sessions untouched
    assertTrue(calls.failStale().isEmpty())                                   // idempotent
  }
}
