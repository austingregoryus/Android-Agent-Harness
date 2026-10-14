package dev.symbiosis.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.symbiosis.runtime.capabilities.CallContext
import dev.symbiosis.runtime.capabilities.Capability
import dev.symbiosis.runtime.capabilities.CapabilityResult
import dev.symbiosis.runtime.capabilities.Principal
import dev.symbiosis.runtime.core.Runtime
import dev.symbiosis.runtime.events.Topics
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.policy.PolicyEngine
import dev.symbiosis.runtime.policy.PolicyOutcome
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
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
import org.robolectric.annotation.Config

/** Offline event/resource and policy regressions; synthetic events and inert capabilities only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TopicAuthorityReviewTest {
  private lateinit var runtime: Runtime
  private val reader = Principal("peer_reader", "reader", listOf("events.*", "messaging.*", "device.*"), ActionClass.READ_ONLY)
  private val sensitive = reader.copy(maxActionClass = ActionClass.SENSITIVE_READ)
  private fun context(p: Principal) = CallContext(p.peerId, "topic-review", principal = p)

  @Before fun setUp() {
    val app = ApplicationProvider.getApplicationContext<Context>()
    app.deleteDatabase("symbiosis.db")
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

  @Test fun `recent events and old subscription replay cannot bypass a lowered ceiling`() = runBlocking {
    val sub = runtime.subscriptions.create(reader.peerId, Topics.SMS_RECEIVED, emptyList(), "queue", 60_000)
    runtime.broker.emit(Topics.SMS_RECEIVED, mapOf("from" to "+12025550100", "text" to "private body"))
    runtime.broker.emit(Topics.BATTERY_CHANGED, mapOf("percent" to 50))
    fun topics(p: Principal) = runtime.readResource(p, "phone://events/recent")!!.jsonArray
      .map { it.jsonObject.getValue("topic").jsonPrimitive.content }
    assertFalse(Topics.SMS_RECEIVED in topics(reader))
    assertTrue(Topics.BATTERY_CHANGED in topics(reader))
    assertTrue(Topics.SMS_RECEIVED in topics(sensitive))

    val replay = runtime.registry.get("events.replay")!!
    val args = mapOf("subscription_id" to sub.subscription_id, "since_id" to 0L)
    val denied = replay.execute(args, context(reader))
    assertTrue((denied.data.getValue("events") as List<*>).isEmpty())
    val allowed = replay.execute(args, context(sensitive))
    assertEquals(1, (allowed.data.getValue("events") as List<*>).size)
    assertTrue(allowed.data.toString().contains("private body"))
  }

  @Test fun `event wait rejects low ceiling and delivers to an authorized sensitive reader`() = runBlocking {
    val wait = runtime.registry.get("events.wait")!!
    val args = mapOf("topic_glob" to Topics.SMS_RECEIVED, "timeout_ms" to 1_000L)
    assertEquals("policy.denied", wait.execute(args, context(reader)).errorCode)
    val waiting = async(start = CoroutineStart.UNDISPATCHED) { wait.execute(args, context(sensitive)) }
    runtime.broker.emit(Topics.SMS_RECEIVED, mapOf("text" to "authorized body"))
    val received = waiting.await()
    assertTrue(received.ok)
    assertEquals(false, received.data["timed_out"])
    assertTrue(received.data.toString().contains("authorized body"))
  }

  @Test fun `contact rules preserve country codes and supported national spelling`() {
    val national = "2025550100"
    val us = "+12025550100"
    val foreign = "+442025550100"
    assertTrue(PolicyEngine.sameNumber(national, us))
    assertTrue(PolicyEngine.sameNumber("1 (202) 555-0100", us))
    assertTrue(PolicyEngine.sameNumber("00442025550100", foreign))
    assertFalse(PolicyEngine.sameNumber(us, foreign))
    assertFalse(PolicyEngine.sameNumber("+4723456789", "+14723456789"))
    val cap = Capability("messaging.test_send", "inert", emptyList(), ActionClass.EXTERNAL_COMMITMENT,
      targetArg = "to") { _, _ -> CapabilityResult.ok(emptyMap()) }
    runtime.policy.updateConfig(runtime.policy.config.value.copy(contactAllowlist = listOf(us)))
    assertTrue(runtime.policy.evaluate(cap, mapOf("to" to national)) is PolicyOutcome.Allow)
    assertTrue(runtime.policy.evaluate(cap, mapOf("to" to foreign)) is PolicyOutcome.Approve)
    runtime.policy.updateConfig(runtime.policy.config.value.copy(contactAllowlist = emptyList(), contactDenylist = listOf(us)))
    assertTrue(runtime.policy.evaluate(cap, mapOf("to" to national)) is PolicyOutcome.Deny)
    assertTrue(runtime.policy.evaluate(cap, mapOf("to" to foreign)) is PolicyOutcome.Allow)
  }
}
