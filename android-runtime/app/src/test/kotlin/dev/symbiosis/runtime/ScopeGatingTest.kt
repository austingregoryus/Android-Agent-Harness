package dev.symbiosis.runtime

import dev.symbiosis.runtime.capabilities.Principal
import dev.symbiosis.runtime.capabilities.Scopes
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.sessions.CallSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the review's P0-1/P0-2: events and resources must be gated by the same scopes as tools. */
class ScopeGatingTest {
  private val eventsOnly = Principal("peer_e", "events only", listOf("events.*", "device.get_*"), ActionClass.READ_ONLY)
  private val messaging = Principal("peer_m", "messaging", listOf("messaging.*", "events.*"), ActionClass.COMMUNICATION)
  private val admin = Principal("peer_admin", "admin", listOf("*"), ActionClass.DESTRUCTIVE)

  @Test fun `topic to scope mapping`() {
    assertEquals("messaging.*", Scopes.requiredScopeForTopic("messaging.sms_received"))
    assertEquals("notifications.*", Scopes.requiredScopeForTopic("notification.posted"))
    assertEquals("telephony.*", Scopes.requiredScopeForTopic("call.ended"))
    assertEquals("telephony.*", Scopes.requiredScopeForTopic("session.state"))
    assertEquals("device.*", Scopes.requiredScopeForTopic("battery.changed"))
    assertEquals(6, Scopes.requiredScopesForTopicGlob("*").size)
    assertTrue("automation.*" in Scopes.requiredScopesForTopicGlob("*"))
    assertEquals("automation.*", Scopes.requiredScopeForTopic("automation.run_finished"))
  }

  @Test fun `a peer with only events scope cannot read SMS or notifications through events`() {
    assertFalse(eventsOnly.canSeeTopic("messaging.sms_received"))
    assertFalse(eventsOnly.canSeeTopic("notification.posted"))
    assertFalse(eventsOnly.canSubscribe("messaging.sms_received"))
    assertFalse(eventsOnly.canSubscribe("*"))
    assertFalse(eventsOnly.canSubscribe("call.*"))
    // device.get_* is a partial namespace grant; battery/connectivity events need the whole device namespace.
    assertFalse(eventsOnly.canSeeTopic("battery.changed"))
  }

  @Test fun `a messaging peer sees SMS events but not calls`() {
    assertTrue(messaging.canSubscribe("messaging.sms_received"))
    assertTrue(messaging.canSubscribe("messaging.*"))
    assertTrue(messaging.canSeeTopic("messaging.sms_received"))
    assertFalse(messaging.canSubscribe("call.*"))
    assertFalse(messaging.canSubscribe("*"))
  }

  @Test fun `admin sees everything`() {
    assertTrue(admin.canSubscribe("*"))
    assertTrue(admin.canSeeTopic("notification.posted"))
  }

  @Test fun `topic access requires its action ceiling even with every namespace granted`() {
    val readOnly = Principal("peer_read", "read", listOf("*"), ActionClass.READ_ONLY)
    val automation = readOnly.copy(maxActionClass = ActionClass.AUTOMATION)
    val sensitive = readOnly.copy(maxActionClass = ActionClass.SENSITIVE_READ)
    for (topic in listOf("messaging.sms_received", "notification.posted", "call.transcript", "session.state")) {
      assertFalse(topic, readOnly.canSeeTopic(topic))
      assertFalse(topic, readOnly.canSubscribe(topic))
      assertFalse(topic, automation.canSeeTopic(topic))
      assertTrue(topic, sensitive.canSeeTopic(topic))
      assertTrue(topic, sensitive.canSubscribe(topic))
    }
    for (glob in listOf("*", "*.changed", "messaging.*", "call.*")) {
      assertFalse(glob, readOnly.canSubscribe(glob))
      assertTrue(glob, sensitive.canSubscribe(glob))
    }
    assertFalse(readOnly.canSeeTopic("automation.run_finished"))
    assertTrue(automation.canSeeTopic("automation.run_finished"))
    assertTrue(readOnly.canSeeTopic("battery.changed"))
    assertTrue(readOnly.canSubscribe("policy.*"))
  }

  @Test fun `call targets must be plain E164 or national digits`() {
    assertTrue(CallSessions.E164.matches("+15125550199"))
    assertTrue(CallSessions.E164.matches("5125550199"))
    assertFalse(CallSessions.E164.matches("*67,+15125550199"))
    assertFalse(CallSessions.E164.matches("+1 512 555 0199"))
    assertFalse(CallSessions.E164.matches("tel:5125550199"))
    assertFalse(CallSessions.E164.matches("12"))
  }
}
