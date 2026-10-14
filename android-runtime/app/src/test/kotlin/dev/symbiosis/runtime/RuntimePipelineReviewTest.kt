package dev.symbiosis.runtime

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.symbiosis.runtime.capabilities.CallContext
import dev.symbiosis.runtime.capabilities.Capability
import dev.symbiosis.runtime.capabilities.CapabilityResult
import dev.symbiosis.runtime.core.Runtime
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.policy.RateClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Real invocation pipeline with inert capabilities: never sends texts, starts sockets or touches a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimePipelineReviewTest {
  private lateinit var runtime: Runtime

  @Before fun setUp() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    app.deleteDatabase("symbiosis.db")
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    // Avoid the process singleton so each test owns its background jobs and database.
    runtime = Runtime::class.java.getDeclaredConstructor(Context::class.java).let {
      it.isAccessible = true
      it.newInstance(app)
    }
  }

  @After fun tearDown() {
    if (::runtime.isInitialized) {
      runtime.scope.cancel()
      runtime.callEvents.shutdownNow()
    }
  }

  @Test fun `inline approval cannot execute after expiry revocation or narrowed scope`() = runBlocking {
    runtime.policy.setAsksFirst(true)
    val executions = AtomicInteger()
    val cap = Capability("messaging.test_send", "inert test", emptyList(), ActionClass.EXTERNAL_COMMITMENT,
      rateClass = RateClass.SMS) { _, _ ->
      executions.incrementAndGet()
      CapabilityResult.ok(emptyMap())
    }
    for (change in listOf("expires_at", "revoked_at", "scopes_json")) {
      val code = runtime.peers.mintPairingCode(listOf("messaging.*"), ActionClass.EXTERNAL_COMMITMENT)
      val peer = runtime.peers.redeem(code.code, "test", null, null)!!.first.principal()
      val call = async(Dispatchers.Default) {
        runtime.invoke(peer, cap, emptyMap(), CallContext(peer.peerId, change, approvalWaitMs = 10_000, principal = peer))
      }
      val pending = withTimeout(5_000) {
        var action = runtime.approvals.pending().firstOrNull { it.peerId == peer.peerId }
        while (action == null) {
          delay(10)
          action = runtime.approvals.pending().firstOrNull { it.peerId == peer.peerId }
        }
        action
      }
      runtime.db.writableDatabase.update("trusted_peers", ContentValues().apply {
        if (change == "scopes_json") put(change, "[\"device.*\"]") else put(change, 1L)
      }, "peer_id = ?", arrayOf(peer.peerId))
      runtime.resolveApproval(pending.id, true, "test")
      val result = withTimeout(5_000) { call.await() }
      assertEquals("policy.denied", result["structuredContent"]!!.jsonObject["error"]!!.jsonPrimitive.content)
    }
    assertEquals(0, executions.get())
    val reserved = runtime.db.readableDatabase.rawQuery("SELECT COUNT(*) FROM rate_counters", null).use {
      it.moveToFirst(); it.getInt(0)
    }
    assertEquals("refused execution must return its rate reservation", 0, reserved)
  }

  @Test fun `overlapping retries execute once and all later retries replay`() = runBlocking {
    val principal = runtime.peers.adminPrincipal
    val calls = AtomicInteger()
    val entered = CompletableDeferred<Unit>()
    val finish = CompletableDeferred<Unit>()
    val cap = Capability("device.test_write", "inert test", emptyList(), ActionClass.LOW_RISK) { _, _ ->
      calls.incrementAndGet()
      entered.complete(Unit)
      finish.await()
      CapabilityResult.ok(mapOf("written" to true))
    }
    val ctx = CallContext(principal.peerId, "retry-test", idempotencyKey = "one-write", principal = principal)
    val original = async(Dispatchers.Default) { runtime.invoke(principal, cap, emptyMap(), ctx) }
    withTimeout(5_000) { entered.await() }
    try {
      val blocked = runtime.invoke(principal, cap, emptyMap(), ctx)
      assertEquals("conflict.in_progress", blocked["structuredContent"]!!.jsonObject["error"]!!.jsonPrimitive.content)
    } finally { finish.complete(Unit) }
    assertFalse(original.await()["isError"]!!.jsonPrimitive.boolean)
    val retries = (1..24).map { async(Dispatchers.Default) { runtime.invoke(principal, cap, emptyMap(), ctx) } }.awaitAll()
    assertEquals(1, calls.get())
    assertTrue(retries.all { result ->
      result["_meta"]!!.jsonObject["replayed"]?.jsonPrimitive?.boolean == true ||
        result["structuredContent"]!!.jsonObject["error"]?.jsonPrimitive?.content == "conflict.in_progress"
    })
    // A cache-hit return must release the key too.
    val replay: JsonObject = runtime.invoke(principal, cap, emptyMap(), ctx)
    assertEquals("a replay receipt must not hide the original outcome", true,
      replay["_meta"]!!.jsonObject["replayed"]?.jsonPrimitive?.boolean)
  }
}
