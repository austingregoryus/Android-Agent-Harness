package dev.symbiosis.runtime.capabilities

import android.content.Context
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.policy.RateClass
import io.droidmcp.core.McpTool
import io.droidmcp.core.ParameterType

/**
 * Adapts droid-mcp tools (Apache-2.0, stixez/droid-mcp) into namespaced [Capability]s with our
 * metadata. Only tools listed in [MAPPING] are exposed; everything else droid-mcp offers is ignored
 * until deliberately mapped. Swapping droid-mcp out later means replacing this file only.
 */
object DroidMcpAdapter {
  data class Meta(
    val name: String,
    val actionClass: ActionClass,
    val tier: Int = 1,
    val rateClass: RateClass = RateClass.NONE,
    val permissions: List<String> = emptyList(),
    val targetArg: String? = null,
    val emits: List<String> = emptyList(),
  )

  private const val P_SMS_READ = "android.permission.READ_SMS"
  private const val P_SMS_SEND = "android.permission.SEND_SMS"
  private const val P_CONTACTS = "android.permission.READ_CONTACTS"
  private const val P_CALL_LOG = "android.permission.READ_CALL_LOG"
  private const val P_PHONE = "android.permission.READ_PHONE_STATE"
  private const val P_LOC = "android.permission.ACCESS_FINE_LOCATION"

  /** droid-mcp tool name → our capability metadata. */
  val MAPPING: Map<String, Meta> = listOf(
    // messaging
    Meta("messaging.read_sms", ActionClass.SENSITIVE_READ, permissions = listOf(P_SMS_READ)) to "read_messages",
    Meta("messaging.search_sms", ActionClass.SENSITIVE_READ, permissions = listOf(P_SMS_READ)) to "search_messages",
    Meta("messaging.send_sms", ActionClass.EXTERNAL_COMMITMENT, rateClass = RateClass.SMS, permissions = listOf(P_SMS_SEND), targetArg = "to") to "send_message",
    // contacts
    Meta("contacts.search", ActionClass.SENSITIVE_READ, permissions = listOf(P_CONTACTS)) to "search_contacts",
    Meta("contacts.get", ActionClass.SENSITIVE_READ, permissions = listOf(P_CONTACTS)) to "read_contact",
    Meta("contacts.list", ActionClass.SENSITIVE_READ, permissions = listOf(P_CONTACTS)) to "list_contacts",
    // telephony (queries only; place/end are native)
    Meta("telephony.get_call_state", ActionClass.READ_ONLY, permissions = listOf(P_PHONE)) to "get_call_state",
    Meta("telephony.get_network_operator", ActionClass.READ_ONLY) to "get_network_operator",
    Meta("telephony.get_phone_number", ActionClass.READ_ONLY, permissions = listOf("android.permission.READ_PHONE_NUMBERS")) to "get_phone_number",
    Meta("telephony.get_sim_info", ActionClass.READ_ONLY, permissions = listOf(P_PHONE)) to "get_sim_info",
    Meta("telephony.get_call_log", ActionClass.SENSITIVE_READ, permissions = listOf(P_CALL_LOG)) to "read_call_log",
    Meta("telephony.search_call_log", ActionClass.SENSITIVE_READ, permissions = listOf(P_CALL_LOG)) to "search_call_log",
    // device
    Meta("device.get_battery", ActionClass.READ_ONLY, tier = 0) to "get_battery_info",
    Meta("device.get_info", ActionClass.READ_ONLY, tier = 0) to "get_device_info",
    Meta("device.get_storage", ActionClass.READ_ONLY, tier = 0) to "get_storage_info",
    Meta("device.get_network", ActionClass.READ_ONLY, tier = 0) to "get_connectivity",
    Meta("connectivity.get_cellular", ActionClass.READ_ONLY, permissions = listOf(P_PHONE)) to "get_cellular_signal",
    Meta("connectivity.get_data_usage", ActionClass.READ_ONLY, tier = 2) to "get_data_usage",
    Meta("connectivity.is_vpn_active", ActionClass.READ_ONLY, tier = 0) to "is_vpn_active",
    // apps / intents
    Meta("apps.list", ActionClass.READ_ONLY, tier = 0) to "list_installed_apps",
    Meta("apps.get_info", ActionClass.READ_ONLY, tier = 0) to "get_app_info",
    Meta("apps.launch", ActionClass.LOW_RISK, tier = 0) to "launch_app",
    Meta("apps.open_deep_link", ActionClass.LOW_RISK, tier = 0) to "open_deep_link",
    Meta("apps.send_intent", ActionClass.DEVICE_MUTATION, tier = 0, rateClass = RateClass.MUTATION) to "send_intent",
    Meta("apps.share_content", ActionClass.LOW_RISK, tier = 0) to "share_content",
    // location
    Meta("location.get", ActionClass.SENSITIVE_READ, permissions = listOf(P_LOC)) to "get_current_location",
    Meta("location.get_address", ActionClass.SENSITIVE_READ, permissions = listOf(P_LOC)) to "get_location_address",
    // notifications (listener-backed)
    Meta("notifications.list", ActionClass.SENSITIVE_READ, tier = 2) to "list_repliable_notifications",
    Meta("notifications.reply", ActionClass.COMMUNICATION, tier = 2, rateClass = RateClass.SMS) to "reply_to_notification",
    Meta("notifications.dismiss", ActionClass.LOW_RISK, tier = 2) to "dismiss_notification",
    Meta("notifications.invoke_action", ActionClass.DEVICE_MUTATION, tier = 2, rateClass = RateClass.MUTATION) to "invoke_notification_action",
    // ui (accessibility-backed; passthrough, disabled by scope until a device has the service on)
    Meta("ui.get_tree", ActionClass.SENSITIVE_READ, tier = 2) to "query_screen",
    Meta("ui.get_active_window", ActionClass.SENSITIVE_READ, tier = 2) to "get_active_window_info",
    Meta("ui.find", ActionClass.SENSITIVE_READ, tier = 2) to "find_node",
    Meta("ui.tap_element", ActionClass.DEVICE_MUTATION, tier = 2, rateClass = RateClass.MUTATION) to "click_node",
    Meta("ui.tap_coordinates", ActionClass.DEVICE_MUTATION, tier = 2, rateClass = RateClass.MUTATION) to "tap",
    Meta("ui.swipe", ActionClass.DEVICE_MUTATION, tier = 2, rateClass = RateClass.MUTATION) to "gesture",
    Meta("ui.type_text", ActionClass.DEVICE_MUTATION, tier = 2, rateClass = RateClass.MUTATION) to "set_node_text",
    Meta("ui.press_global", ActionClass.DEVICE_MUTATION, tier = 2, rateClass = RateClass.MUTATION) to "global_action",
    // Mapped 2026-09-26 from droid-mcp 0.10.1's AccessibilityTools (names and params read from the AAR's classes).
    // Same class, tier and rate class as ui.tap_element on purpose: each is one screen action, so each counts one
    // MUTATION slot like a tap. They do not widen the accepted gap in docs/security.md#limits (screen control as a way
    // round the text/call brakes): a long-press, scroll or find-and-tap reaches nothing a tap_element sequence
    // could not already reach, at the same per-action cost. (lock_screen needs no mapping: it is already one of
    // ui.press_global's actions.)
    Meta("ui.long_press_element", ActionClass.DEVICE_MUTATION, tier = 2, rateClass = RateClass.MUTATION) to "long_click_node",
    Meta("ui.scroll", ActionClass.DEVICE_MUTATION, tier = 2, rateClass = RateClass.MUTATION) to "scroll_node",
    // Up to 20 swipes behind one call, but still one MUTATION slot: it only scrolls, and its tap-free result is the
    // matched node's selector, which the caller then has to tap as a separate, separately-charged action.
    Meta("ui.scroll_to_find", ActionClass.DEVICE_MUTATION, tier = 2, rateClass = RateClass.MUTATION) to "scroll_to_find",
    Meta("ui.find_and_tap", ActionClass.DEVICE_MUTATION, tier = 2, rateClass = RateClass.MUTATION) to "find_and_tap",
    // droid-mcp's long_press takes duration_ms (100-5000, default 800), so no native gesture is needed for it.
    // ui.double_tap is native (capabilities/UiGestures.kt): no droid-mcp gesture dispatches two taps.
    Meta("ui.long_press_coordinates", ActionClass.DEVICE_MUTATION, tier = 2, rateClass = RateClass.MUTATION) to "long_press",
    Meta("ui.wait_for_text", ActionClass.SENSITIVE_READ, tier = 2) to "wait_for_text",
    Meta("ui.screenshot", ActionClass.SENSITIVE_READ, tier = 2) to "capture_screen",
    // media
    Meta("media.tts", ActionClass.LOW_RISK, tier = 0) to "speak_text",
    Meta("media.get_tts_info", ActionClass.READ_ONLY, tier = 0) to "get_tts_info",
    Meta("media.get_audio_devices", ActionClass.READ_ONLY, tier = 0) to "get_audio_devices",
  ).associate { (meta, droid) -> droid to meta }

  fun adapt(context: Context, tools: List<McpTool>): List<Capability> = tools.mapNotNull { tool ->
    val meta = MAPPING[tool.name] ?: return@mapNotNull null
    Capability(
      name = meta.name,
      description = tool.description,
      params = tool.parameters.map { p -> Param(p.name, p.type.jsonType, p.description, p.required) },
      actionClass = meta.actionClass,
      tier = meta.tier,
      rateClass = meta.rateClass,
      requiredPermissions = meta.permissions,
      emitsEvents = meta.emits,
      source = "droid-mcp:${tool.name}",
      readOnly = tool.annotations.readOnlyHint || meta.actionClass <= ActionClass.SENSITIVE_READ,
      idempotent = tool.annotations.idempotentHint,
      targetArg = meta.targetArg,
      execute = { args, _ ->
        val missing = meta.permissions.filter { context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) CapabilityResult.missingPermission(*missing.toTypedArray())
        else {
          val coerced = coerce(tool, args)
          val r = tool.execute(coerced)
          if (r.isSuccess) CapabilityResult.ok(r.data ?: emptyMap())
          else {
            val msg = r.errorMessage ?: "failed"
            val code = msg.substringBefore(':').trim()
            if (code.matches(Regex("[a-z_]+")) && msg.contains(':')) CapabilityResult.fail("capability.failed", msg, mapOf("droid_code" to code))
            else CapabilityResult.fail("capability.failed", msg)
          }
        }
      },
    )
  }

  /** droid-mcp reads loosely typed values but some tools cast; coerce JSON numbers to the declared type. */
  private fun coerce(tool: McpTool, args: Map<String, Any?>): Map<String, Any> {
    val types = tool.parameters.associate { it.name to it.type }
    return args.filterValues { it != null }.mapValues { (k, v) ->
      when (types[k]) {
        ParameterType.INTEGER -> (v as? Number)?.toInt() ?: v.toString().toIntOrNull() ?: v!!
        ParameterType.NUMBER -> (v as? Number)?.toDouble() ?: v.toString().toDoubleOrNull() ?: v!!
        ParameterType.BOOLEAN -> (v as? Boolean) ?: v.toString().toBooleanStrictOrNull() ?: v!!
        ParameterType.STRING -> v.toString()
        else -> v!!
      }
    }
  }
}
