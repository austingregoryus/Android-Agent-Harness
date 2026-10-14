package dev.symbiosis.runtime

import android.Manifest
import android.app.Application
import android.telecom.TelecomManager
import androidx.test.core.app.ApplicationProvider
import dev.symbiosis.runtime.events.EventBroker
import dev.symbiosis.runtime.sessions.CallBinding
import dev.symbiosis.runtime.sessions.CallSessions
import dev.symbiosis.runtime.sessions.SessionManager
import dev.symbiosis.runtime.sessions.SessionState
import dev.symbiosis.runtime.storage.Db
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/** The single-call gate, and which Telecom call a session may act on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallSessionGateTest {
  private lateinit var app: Application
  private lateinit var db: Db
  private lateinit var sessions: SessionManager
  private lateinit var calls: CallSessions
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @Before fun setUp() {
    app = ApplicationProvider.getApplicationContext()
    app.deleteDatabase("symbiosis.db")
    shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)
    db = Db(app)
    sessions = SessionManager(db)
    calls = CallSessions(app, sessions, EventBroker(db, "dev_test"), scope)
  }

  @After fun tearDown() { scope.cancel() }

  private fun telecom() = shadowOf(app.getSystemService(TelecomManager::class.java))
  private fun place(target: String = "+15551234567") = calls.placeCall(target, null, null, null, "peer_a", "corr_test")

  private fun busyCode(block: () -> Unit): String? = try { block(); null } catch (e: IllegalStateException) { e.message }

  @Suppress("UNCHECKED_CAST")
  private fun slot(): AtomicReference<String?> =
    CallSessions::class.java.getDeclaredField("current").apply { isAccessible = true }.get(calls) as AtomicReference<String?>

  @Test fun `a request that finds another one mid-placement is busy and does not dial`() {
    // Request A has claimed the slot but not yet created its session: B used to read that as free and dial too.
    slot().set("call_placing")
    assertEquals("call.busy:concurrent", busyCode { place() })
    assertTrue(telecom().allOutgoingCalls.isEmpty())
    assertTrue(sessions.recent().isEmpty())
    assertNull(calls.currentSessionId()) // the placeholder is never reported as a session
  }

  @Test fun `a second call while one is live is busy`() {
    val first = place()
    assertEquals("call.busy:${first.sessionId}", busyCode { place("+15559876543") })
    assertEquals(1, telecom().allOutgoingCalls.size)
  }

  @Test fun `concurrent placements dial at most once`() {
    val results = (1..8).map { i -> Thread { runCatching { place("+1555000000$i") } } }
    results.forEach { it.start() }; results.forEach { it.join() }
    assertEquals(1, telecom().allOutgoingCalls.size)
  }

  @Test fun `end_call without an identified call refuses when another call was up at dial time`() {
    // No InCallService is bound under Robolectric, so the only way to hang up is TelecomManager.endCall, which ends
    // the foreground call: with the user already on a call that may be theirs, so nothing is touched.
    telecom().setIsInCall(true)
    val s = place()
    assertEquals("call.unidentified", busyCode { calls.endCall(s.sessionId) })
    assertEquals(SessionState.DIALING, sessions.get(s.sessionId)!!.state)
  }

  @Test fun `end_call refuses a session that is not the live call`() {
    val stale = sessions.create("call", "peer_a", "corr_old", emptyMap())
    sessions.transition(stale.sessionId, SessionState.ACTIVE)
    place()
    assertEquals("call.unidentified", busyCode { calls.endCall(stale.sessionId) })
    assertEquals(SessionState.ACTIVE, sessions.get(stale.sessionId)!!.state)
  }

  @Test fun `an incoming call while ours is up blocks the foreground-call fallback`() {
    val s = place()
    calls.onCallState(android.telephony.TelephonyManager.CALL_STATE_RINGING, "+15550001111")
    assertEquals("call.unidentified", busyCode { calls.endCall(s.sessionId) })
  }

  @Test fun `end_call with no other call uses the platform fallback`() {
    val s = place()
    try { calls.endCall(s.sessionId) } catch (e: IllegalStateException) { fail("should not refuse: ${e.message}") }
    assertEquals(SessionState.ENDING, sessions.get(s.sessionId)!!.state)
  }

  @Test fun `binding takes only our outgoing call to the dialed number, soon after dialing`() {
    val p = CallBinding.Placed("call_1", "+15551234567", 1_000_000)
    assertTrue(CallBinding.matches(p, "+15551234567", outgoing = true, addedAt = 1_000_100))
    assertTrue(CallBinding.matches(p, "5551234567", outgoing = true, addedAt = 1_000_100))      // national form
    assertTrue(CallBinding.matches(p, "+1 (555) 123-4567", outgoing = true, addedAt = 1_000_100))
    assertFalse(CallBinding.matches(p, "+15551234567", outgoing = false, addedAt = 1_000_100)) // incoming
    assertFalse(CallBinding.matches(p, "+15559999999", outgoing = true, addedAt = 1_000_100))  // the user's call
    assertFalse(CallBinding.matches(p, null, outgoing = true, addedAt = 1_000_100))
    assertFalse(CallBinding.matches(p, "+15551234567", outgoing = true, addedAt = 999_000))    // before we dialed
    assertFalse(CallBinding.matches(p, "+15551234567", outgoing = true, addedAt = 1_000_000 + CallBinding.WINDOW_MS + 1))
    // Short codes match exactly only: 911 must not match a number that merely ends in 911.
    assertTrue(CallBinding.sameNumber("611", "611"))
    assertFalse(CallBinding.sameNumber("611", "5555550611"))
  }

  @Test fun `no call is attributed to a session until Telecom reports it`() {
    val s = place()
    assertNull(calls.telecomCallFor(s.sessionId))
  }
}
