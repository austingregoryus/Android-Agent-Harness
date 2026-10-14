package dev.symbiosis.runtime

import dev.symbiosis.runtime.capabilities.Capability
import dev.symbiosis.runtime.capabilities.CapabilityResult
import dev.symbiosis.runtime.capabilities.Principal
import dev.symbiosis.runtime.capabilities.Scopes
import dev.symbiosis.runtime.core.Json
import dev.symbiosis.runtime.events.FilterClause
import dev.symbiosis.runtime.events.TopicGlob
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.policy.PolicyEngine
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the protocol semantics that do not need an Android context. */
class ProtocolSemanticsTest {

  @Test fun `scope globs match within and across segments`() {
    assertTrue(Scopes.matches("*", "anything.at_all"))
    assertTrue(Scopes.matches("messaging.*", "messaging.send_sms"))
    assertTrue(Scopes.matches("messaging.*", "messaging.deep.er"))
    assertTrue(Scopes.matches("device.get_*", "device.get_battery"))
    assertFalse(Scopes.matches("device.get_*", "device.keep_awake"))
    assertFalse(Scopes.matches("messaging.*", "telephony.place_call"))
    assertTrue(Scopes.matches("apps.list", "apps.list"))
    assertFalse(Scopes.matches("apps.list", "apps.list_x"))
  }

  @Test fun `principal visibility combines scopes and ceiling`() {
    val p = Principal("peer_x", "x", listOf("messaging.*", "device.*"), ActionClass.COMMUNICATION)
    fun cap(name: String, ac: ActionClass) = Capability(name, "", emptyList(), ac) { _, _ -> CapabilityResult.ok(emptyMap()) }
    assertTrue(p.canSee(cap("messaging.send_sms", ActionClass.COMMUNICATION)))
    assertTrue(p.canSee(cap("device.get_battery", ActionClass.READ_ONLY)))
    assertFalse(p.canSee(cap("telephony.place_call", ActionClass.COMMUNICATION)))       // out of scope
    assertFalse(p.canSee(cap("device.reboot", ActionClass.DESTRUCTIVE)))                // above ceiling
  }

  @Test fun `topic globs`() {
    assertTrue(TopicGlob.matches("*", "call.ended"))
    assertTrue(TopicGlob.matches("call.*", "call.ended"))
    assertTrue(TopicGlob.matches("call.*", "call.state.changed"))
    assertTrue(TopicGlob.matches("messaging.sms_received", "messaging.sms_received"))
    assertFalse(TopicGlob.matches("messaging.sms_received", "messaging.sms_sent"))
    assertTrue(TopicGlob.matches("*.changed", "battery.changed"))
    assertFalse(TopicGlob.matches("*.changed", "call.state.changed"))
  }

  @Test fun `filter clauses`() {
    val payload = buildJsonObject { put("from", "+15125550199"); put("percent", 15); put("text", "Your code is 4411"); put("ok", true) }
    assertTrue(FilterClause("from", "contains", JsonPrimitive("555019")).matches(payload))
    assertTrue(FilterClause("from", "prefix", JsonPrimitive("+1512")).matches(payload))
    assertTrue(FilterClause("percent", "lt", JsonPrimitive(20)).matches(payload))
    assertFalse(FilterClause("percent", "gte", JsonPrimitive(20)).matches(payload))
    assertTrue(FilterClause("percent", "eq", JsonPrimitive(15)).matches(payload))
    assertTrue(FilterClause("ok", "eq", JsonPrimitive(true)).matches(payload))
    assertTrue(FilterClause("text", "contains", JsonPrimitive("CODE IS")).matches(payload))
    assertTrue(FilterClause("missing", "ne", JsonPrimitive("x")).matches(payload))
    assertFalse(FilterClause("missing", "eq", JsonPrimitive("x")).matches(payload))
    assertFalse(FilterClause("from", "bogus_op", JsonPrimitive("x")).matches(payload))
  }

  @Test fun `canonical json is key-sorted and stable`() {
    val a = Json.lenient.parseToJsonElement("""{"b":1,"a":{"z":true,"y":[1,2,"x"]},"c":null}""")
    val b = Json.lenient.parseToJsonElement("""{"c":null,"a":{"y":[1,2,"x"],"z":true},"b":1}""")
    assertEquals(Json.canonical(a), Json.canonical(b))
    assertEquals("""{"a":{"y":[1,2,"x"],"z":true},"b":1,"c":null}""", Json.canonical(a))
    assertEquals("\"q\\\"uote\\n\"", Json.canonical(JsonPrimitive("q\"uote\n")))
  }

  @Test fun `number normalisation for allow and deny lists`() {
    assertEquals("+15125550199", PolicyEngine.normalizeNumber("(512) 555-0199"))
    assertEquals("+15125550199", PolicyEngine.normalizeNumber("+1 512 555 0199"))
    assertEquals("5550199", PolicyEngine.normalizeNumber("555-0199"))
  }

  @Test fun `map to json round trip`() {
    val m = mapOf("a" to 1, "b" to listOf("x", 2.5, null), "c" to mapOf("d" to true))
    val back = Json.fromJson(Json.toJson(m)) as Map<*, *>
    assertEquals(1L, back["a"])
    assertEquals(listOf("x", 2.5, null), back["b"])
    assertEquals(true, (back["c"] as Map<*, *>)["d"])
  }
}
