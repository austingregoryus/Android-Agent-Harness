package dev.symbiosis.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.policy.ApprovalManager
import dev.symbiosis.runtime.storage.Db
import dev.symbiosis.runtime.sessions.SessionManager
import kotlinx.serialization.json.JsonPrimitive
import dev.symbiosis.runtime.telephony.DriverLease
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManagedDialReviewTest {
  @Test fun `managed dial timeout cancels pending action and refuses late approval`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    context.deleteDatabase("symbiosis.db")
    val approvals = ApprovalManager(context, Db(context))
    val (pa, decision) = approvals.createAndAwait("telephony.place_call", ActionClass.EXTERNAL_COMMITMENT,
      mapOf("driver_managed" to true), "peer_test", "corr_test", 600_000, "test", 0, allowLate = false)
    assertNull(decision)
    assertEquals("cancelled", approvals.get(pa.id)!!.status)
    assertNull(approvals.resolve(pa.id, true))
  }

  @Test fun `managed dial in-window approval remains executable once`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    context.deleteDatabase("symbiosis.db")
    val approvals = ApprovalManager(context, Db(context))
    val (pa, decision) = approvals.createAndAwait("telephony.place_call", ActionClass.EXTERNAL_COMMITMENT,
      mapOf("driver_managed" to true), "peer_test", "corr_test", 600_000, "test", 1_000, allowLate = false) {
      assertFalse(approvals.resolve(it.id, true)!!.late)
    }
    assertEquals(true, decision)
    assertEquals("approved", approvals.get(pa.id)!!.status)
    assertNull(approvals.resolve(pa.id, true))
  }

  @Test fun `lost dial response has bounded startup lease without touching unmanaged calls`() {
    assertFalse(DriverLease.startupExpired(89_999, 0, true, false))
    assertTrue(DriverLease.startupExpired(90_000, 0, true, false))
    assertFalse(DriverLease.startupExpired(900_000, 0, false, false))
    assertFalse(DriverLease.startupExpired(900_000, 0, true, true))
  }

  @Test fun `managed approval cannot execute after process restart loses waiter`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    context.deleteDatabase("symbiosis.db")
    val db = Db(context)
    // Simulate a persisted pending row whose waiting process is gone.
    val (pa, _) = ApprovalManager(context, db).createAndAwait("telephony.place_call", ActionClass.EXTERNAL_COMMITMENT,
      mapOf("driver_managed" to true), "peer_test", "corr_test", 600_000, "test", 0)
    val restarted = ApprovalManager(context, db)
    assertNull(restarted.resolve(pa.id, true))
    assertEquals("cancelled", restarted.get(pa.id)!!.status)
  }

  @Test fun `successful attachment remains recorded without a conversation registry entry`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    context.deleteDatabase("symbiosis.db")
    val db = Db(context)
    val sessions = SessionManager(db)
    val s = sessions.create("call", "peer_test", "corr_test", mapOf("driver_managed" to true))
    sessions.markDriverAttached(s.sessionId)
    val persisted = SessionManager(db).get(s.sessionId)!!
    assertEquals(JsonPrimitive(true), persisted.meta["driver_attached"])
    assertEquals(JsonPrimitive(true), persisted.meta["driver_managed"])
    assertFalse(DriverLease.startupExpired(s.createdAt + 900_000, s.createdAt, true,
      persisted.meta["driver_attached"] == JsonPrimitive(true)))
  }
}
