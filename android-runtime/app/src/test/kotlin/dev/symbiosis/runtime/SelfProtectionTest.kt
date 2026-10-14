package dev.symbiosis.runtime

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import dev.symbiosis.runtime.capabilities.CallContext
import dev.symbiosis.runtime.capabilities.Capability
import dev.symbiosis.runtime.capabilities.CapabilityResult
import dev.symbiosis.runtime.capabilities.DroidMcpAdapter
import dev.symbiosis.runtime.capabilities.Param
import dev.symbiosis.runtime.capabilities.ScreenControlWindow
import dev.symbiosis.runtime.capabilities.SelfProtection
import dev.symbiosis.runtime.core.Runtime
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.policy.ApprovalSummary
import dev.symbiosis.runtime.policy.PolicyEngine
import dev.symbiosis.runtime.policy.PolicyOutcome
import dev.symbiosis.runtime.policy.RateClass
import dev.symbiosis.runtime.policy.RateLimiter
import dev.symbiosis.runtime.transport.PeerStore
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * The 2026-10 hardening: agents cannot drive this app's own screens or notifications, an approval right after
 * screen control is ignored, the approval prompt orders arguments itself, notifications.reply obeys "Ask me first"
 * and the contact lists, and the admin token can be rotated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelfProtectionTest {
  private lateinit var app: Application
  private lateinit var runtime: Runtime
  private val own = "dev.symbiosis.runtime"

  @Before fun setUp() {
    app = ApplicationProvider.getApplicationContext()
    app.deleteDatabase("symbiosis.db")
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    SelfProtection.startedActivities.set(0)
    runtime = Runtime::class.java.getDeclaredConstructor(Context::class.java).let { it.isAccessible = true; it.newInstance(app) }
  }

  @After fun tearDown() {
    SelfProtection.startedActivities.set(0)
    if (::runtime.isInitialized) { runtime.scope.cancel(); runtime.callEvents.shutdownNow(); runtime.db.close() }
  }

  private fun inert(name: String, cls: ActionClass, runs: AtomicInteger, params: List<Param> = emptyList()) =
    Capability(name, "inert test", params, cls, rateClass = RateClass.MUTATION) { _, _ -> runs.incrementAndGet(); CapabilityResult.ok(mapOf("ran" to true)) }

  private fun errorOf(body: kotlinx.serialization.json.JsonObject): String? =
    body["structuredContent"]!!.jsonObject["error"]?.jsonPrimitive?.content

  // ---- self protection ------------------------------------------------------------------------

  @Test fun `screen control is refused only while our window shows`() {
    assertEquals(SelfProtection.CODE, SelfProtection.refusal("ui.get_tree", emptyMap(), own) { true }?.errorCode)
    assertEquals(SelfProtection.CODE, SelfProtection.refusal("ui.screenshot", emptyMap(), own) { true }?.errorCode)
    assertEquals(SelfProtection.CODE, SelfProtection.refusal("ui.double_tap", emptyMap(), own) { true }?.errorCode)
    assertNull(SelfProtection.refusal("ui.find_and_tap", emptyMap(), own) { false })
    // The window query only runs for screen-control tools.
    assertNull(SelfProtection.refusal("device.get_battery", emptyMap(), own) { error("must not query windows") })
  }

  @Test fun `apps cannot open us and notification tools cannot touch our notifications`() {
    for (tool in listOf("apps.launch", "apps.open_deep_link", "apps.send_intent")) {
      assertEquals(SelfProtection.CODE, SelfProtection.refusal(tool, mapOf("package_name" to own), own) { false }?.errorCode)
      assertNull(SelfProtection.refusal(tool, mapOf("package_name" to "com.example.app"), own) { false })
    }
    val ourKey = "0|$own|1234|null|10123"
    assertEquals(own, SelfProtection.notificationPackage(ourKey))
    assertNull(SelfProtection.notificationPackage("garbage"))
    for (tool in SelfProtection.NOTIFICATION_KEY_TOOLS) {
      assertEquals(SelfProtection.CODE, SelfProtection.refusal(tool, mapOf("key" to ourKey), own) { false }?.errorCode)
      assertNull(SelfProtection.refusal(tool, mapOf("key" to "0|com.google.android.apps.messaging|7|null|10200"), own) { false })
    }
  }

  @Test fun `the pipeline refuses ui calls while one of our activities is started`() = runBlocking {
    val admin = runtime.peers.adminPrincipal
    val runs = AtomicInteger()
    val cap = inert("ui.test_tap", ActionClass.DEVICE_MUTATION, runs)
    SelfProtection.startedActivities.set(1)
    val refused = runtime.invoke(admin, cap, emptyMap(), CallContext(admin.peerId, "self-1", principal = admin))
    assertEquals(SelfProtection.CODE, errorOf(refused))
    assertEquals(0, runs.get())
    assertFalse("a refused call is not screen control", runtime.screenControl.recentlyUsed())

    SelfProtection.startedActivities.set(0)
    val ok = runtime.invoke(admin, cap, emptyMap(), CallContext(admin.peerId, "self-2", principal = admin))
    assertNull(errorOf(ok))
    assertEquals(1, runs.get())
    assertTrue("an executed ui call opens the approval guard window", runtime.screenControl.recentlyUsed())
  }

  // ---- approvals after screen control -----------------------------------------------------------

  @Test fun `screen control window lasts ten seconds from the last mark`() {
    var now = 1_000_000L
    val w = ScreenControlWindow { now }
    assertFalse(w.recentlyUsed())
    w.mark()
    now += ScreenControlWindow.WINDOW_MS - 1
    assertTrue(w.recentlyUsed())
    now += 1
    assertFalse(w.recentlyUsed())
    w.mark()
    assertTrue(w.recentlyUsed())
  }

  @Test fun `an approval right after screen control is ignored and left pending`() = runBlocking {
    val (pa, _) = runtime.approvals.createAndAwait("messaging.send_sms", ActionClass.EXTERNAL_COMMITMENT, mapOf("to" to "+15125550100", "body" to "hi"), PeerStore.ADMIN_ID, "corr", 60_000, "test", waitMs = 0)
    runtime.screenControl.mark()
    assertEquals(Runtime.APPROVAL_IGNORED_SCREEN_CONTROL, runtime.resolveApproval(pa.id, true, "notification"))
    assertEquals(Runtime.APPROVAL_IGNORED_SCREEN_CONTROL, runtime.resolveApproval(pa.id, true, "ui"))
    assertEquals("pending", runtime.approvals.get(pa.id)!!.status)
    val audited = runtime.audit.recent(20).any { it.errorCode == Runtime.APPROVAL_IGNORED_SCREEN_CONTROL }
    assertTrue(audited)
    // A denial is never ignored.
    assertNull(runtime.resolveApproval(pa.id, false, "ui"))
    assertEquals("denied", runtime.approvals.get(pa.id)!!.status)
  }

  // ---- approval summary --------------------------------------------------------------------------

  @Test fun `approval summary puts target and body first in full and flags undeclared args`() {
    val sms = Capability("messaging.send_sms", "", listOf(Param("to", "string", "", true), Param("body", "string", "", true)),
      ActionClass.EXTERNAL_COMMITMENT, targetArg = "to") { _, _ -> CapabilityResult.ok(emptyMap()) }
    val longBody = "Transfer approved, reference " + "x".repeat(200)
    // Decoys first, as an agent would order them to fill a three-argument, 40-character preview.
    val args = linkedMapOf<String, Any?>("a_note" to "Hi mom, see you at 6", "b_note" to "nothing to see", "c" to "here", "body" to longBody, "to" to "+15125550199")
    val lines = ApprovalSummary.lines(sms, args)
    assertEquals(listOf("to", "body", "a_note", "b_note", "c"), lines.map { it.key })
    assertEquals("\"+15125550199\"", lines[0].value)
    assertTrue(lines[1].value.contains(longBody))
    assertEquals(listOf(true, true, false, false, false), lines.map { it.declared })
    assertTrue(ApprovalSummary.format(lines).contains("a_note (undeclared): "))

    // A value cannot draw a fake line or hide behind bidi controls.
    val text = ApprovalSummary.format(sms, mapOf("to" to "+15125550199", "body" to "ok\nto: +15125550100‮"))
    assertEquals(2, text.lines().size)
    assertFalse(text.contains('‮'))

    // Unknown capability: everything is undeclared.
    assertTrue(ApprovalSummary.lines(null, mapOf("x" to 1)).all { !it.declared })
    // A resolver-backed tool shows who it reaches first.
    val reply = Capability("notifications.reply", "", listOf(Param("key", "string", "", true), Param("text", "string", "", true)),
      ActionClass.EXTERNAL_COMMITMENT, targetResolver = { listOf("Mom", "+15125550100") }) { _, _ -> CapabilityResult.ok(emptyMap()) }
    val r = ApprovalSummary.lines(reply, mapOf("key" to "k", "text" to "hello"))
    assertEquals(listOf(ApprovalSummary.RECIPIENT, "text", "key"), r.map { it.key })
    assertEquals("\"Mom\", \"+15125550100\"", r[0].value)
  }

  // ---- notifications.reply under policy -----------------------------------------------------------

  @Test fun `notifications reply is an external commitment that obeys contact lists`() {
    val meta = DroidMcpAdapter.MAPPING.getValue("reply_to_notification")
    assertEquals(ActionClass.EXTERNAL_COMMITMENT, meta.actionClass)
    assertNotNull(meta.targetResolver)

    var who = listOf("Mom", "+15125550100")
    val reply = Capability("notifications.reply", "", emptyList(), ActionClass.EXTERNAL_COMMITMENT, rateClass = RateClass.SMS,
      targetResolver = { who }) { _, _ -> CapabilityResult.ok(emptyMap()) }
    val engine = PolicyEngine(runtime.db, RateLimiter(runtime.db))
    val args = mapOf("key" to "0|com.google.android.apps.messaging|1|null|10200", "text" to "hi")

    engine.setAsksFirst(true)
    assertTrue("Ask me first covers it", engine.evaluate(reply, args) is PolicyOutcome.Approve)
    engine.setAsksFirst(false)
    assertTrue(engine.evaluate(reply, args) is PolicyOutcome.Allow)

    engine.updateConfig(engine.config.value.copy(contactDenylist = listOf("5125550100")))
    assertTrue("a denylisted sender number is denied", engine.evaluate(reply, args) is PolicyOutcome.Deny)
    engine.updateConfig(engine.config.value.copy(contactDenylist = listOf("mom")))
    assertTrue("a denylisted sender name is denied", engine.evaluate(reply, args) is PolicyOutcome.Deny)

    engine.updateConfig(engine.config.value.copy(contactDenylist = emptyList(), contactAllowlist = listOf("+15125550100")))
    assertTrue("only one of the identities is allowlisted", engine.evaluate(reply, args) is PolicyOutcome.Approve)
    engine.updateConfig(engine.config.value.copy(contactAllowlist = listOf("+15125550100", "Mom")))
    assertTrue(engine.evaluate(reply, args) is PolicyOutcome.Allow)
    who = listOf("Dad")
    assertTrue("a different name is not the same target", engine.evaluate(reply, args) is PolicyOutcome.Approve)
    who = emptyList()
    val unknown = engine.evaluate(reply, args)
    assertTrue("an unresolvable sender is not on the allowlist", unknown is PolicyOutcome.Approve && unknown.reason.contains("unknown"))
    engine.updateConfig(engine.config.value.copy(contactAllowlist = emptyList()))
    assertTrue(engine.evaluate(reply, args) is PolicyOutcome.Allow)
    assertFalse(PolicyEngine.sameTarget("Mom", "Dad"))
    assertTrue(PolicyEngine.sameTarget("+1 (512) 555-0100", "5125550100"))
  }

  @Test fun `a notification's sender is resolved from the listener store`() {
    val sender = Person.Builder().setName("Mom").setUri("tel:+15125550100").build()
    val me = Person.Builder().setName("Me").build()
    val n = NotificationCompat.Builder(app, "test")
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle("Mom")
      .setStyle(NotificationCompat.MessagingStyle(me).addMessage("dinner?", 1L, sender))
      .build()
    @Suppress("DEPRECATION")
    val sbn = StatusBarNotification("com.google.android.apps.messaging", "com.google.android.apps.messaging", 7, null, 10200, 0, 0, n, Process.myUserHandle(), 1L)
    val store = io.droidmcp.notification.NotificationStore
    store.javaClass.getDeclaredMethod("put\$droid_mcp_notification_listener_release", StatusBarNotification::class.java).invoke(store, sbn)
    try {
      val who = DroidMcpAdapter.notificationIdentities(sbn.key)
      assertTrue(who.toString(), "Mom" in who && "+15125550100" in who)
      assertTrue(DroidMcpAdapter.notificationIdentities("0|nope|1|null|1").isEmpty())
    } finally {
      store.javaClass.getDeclaredMethod("remove\$droid_mcp_notification_listener_release", String::class.java).invoke(store, sbn.key)
    }
  }

  // ---- admin token rotation ----------------------------------------------------------------------

  @Test fun `rotating the admin token revokes the old one and cancels its pending approvals`() = runBlocking {
    val old = runtime.secure.adminToken
    assertNotNull(runtime.peers.authenticate(old))
    val (pa, _) = runtime.approvals.createAndAwait("messaging.send_sms", ActionClass.EXTERNAL_COMMITMENT, mapOf("to" to "+15125550100"), PeerStore.ADMIN_ID, "corr", 60_000, "test", waitMs = 0)
    runtime.rotateAdminToken()
    val new = runtime.secure.adminToken
    assertTrue(old != new)
    assertNull(runtime.peers.authenticate(old))
    assertEquals(PeerStore.ADMIN_ID, runtime.peers.authenticate(new)?.peerId)
    assertEquals("cancelled", runtime.approvals.get(pa.id)!!.status)
  }
}
