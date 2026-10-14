package dev.symbiosis.runtime.capabilities

import android.content.Context
import dev.symbiosis.runtime.core.Ids
import dev.symbiosis.runtime.ui.SymbiosisAccessibilityService
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps agents away from this app's own controls. The control panel shows the admin token, mints pairing codes,
 * holds the safety switches and the Approve buttons, and the approval notifications carry Approve actions. A peer
 * with screen control (`ui.*`) or `notifications.invoke_action` could otherwise drive all of that itself.
 *
 * Three layers, all checked at execution time in Runtime.executeAllowed (so direct calls, batch steps and late
 * approvals alike):
 *  - every `ui.*` call is refused while one of our windows is on screen: one of our activities is started, or the
 *    accessibility service sees our package as the active window or anywhere in the interactive windows list;
 *  - `apps.launch`, `apps.open_deep_link` and `apps.send_intent` aimed at our own package are refused;
 *  - the notification tools refuse keys of notifications our own package posted.
 * [ScreenControlWindow] is the fourth: an approval that arrives right after screen control is ignored.
 */
object SelfProtection {
  const val CODE = "ui.self_protected"

  /** Activities of this app that are currently started (visible, possibly behind a dialog or in split screen). */
  val startedActivities = AtomicInteger(0)

  /** Tools that act on a notification by its key. */
  val NOTIFICATION_KEY_TOOLS = setOf("notifications.reply", "notifications.invoke_action", "notifications.dismiss")

  /** Tools that open an app by package name. */
  private val LAUNCH_TOOLS = setOf("apps.launch", "apps.open_deep_link", "apps.send_intent")

  fun isScreenControl(name: String): Boolean = name.startsWith("ui.")

  /** Calls that can press something on screen or in the shade; an approval right after one is not trusted. */
  fun countsAsScreenControl(name: String): Boolean = isScreenControl(name) || name == "notifications.invoke_action"

  /** The posting package from a StatusBarNotification key (`userId|package|id|tag|uid`), or null if malformed. */
  fun notificationPackage(key: String?): String? = key?.split('|')?.getOrNull(1)?.takeIf { it.isNotBlank() }

  /**
   * The refusal for [name] with [args], or null when the call may run. [ownWindowShowing] is only consulted for
   * screen-control tools, so the (main-thread-free but not free) window query runs only when it matters.
   */
  fun refusal(name: String, args: Map<String, Any?>, ownPackage: String, ownWindowShowing: () -> Boolean): CapabilityResult? {
    if (isScreenControl(name) && ownWindowShowing()) {
      return CapabilityResult.fail(CODE, "screen control is refused while Android Agent Harness is on screen")
    }
    if (name in LAUNCH_TOOLS && args["package_name"]?.toString()?.trim() == ownPackage) {
      return CapabilityResult.fail(CODE, "agents cannot open Android Agent Harness itself")
    }
    if (name in NOTIFICATION_KEY_TOOLS && notificationPackage(args["key"]?.toString()) == ownPackage) {
      return CapabilityResult.fail(CODE, "notifications posted by Android Agent Harness are not available to agents")
    }
    return null
  }

  /** True when one of our windows is visible: a started activity, or our package in the accessibility window list. */
  fun ownWindowShowing(context: Context): Boolean {
    if (startedActivities.get() > 0) return true
    val svc = SymbiosisAccessibilityService.current ?: return false
    val own = context.packageName
    if (runCatching { svc.rootInActiveWindow?.packageName?.toString() }.getOrNull() == own) return true
    // flagRetrieveInteractiveWindows is set in droid-mcp's service config, so this covers split screen and overlays.
    return runCatching { svc.windows.any { w -> w.root?.packageName?.toString() == own } }.getOrDefault(false)
  }
}

/**
 * When a peer last used screen control (any `ui.*` call or `notifications.invoke_action`), in one place. A tap on
 * Approve within [WINDOW_MS] of that, in the app or in the notification shade, may be the agent's own tap and is
 * ignored (Runtime.resolveApproval). Marked when such a call starts and again when it ends, so a long call
 * (scroll_to_find, find_and_tap) is covered for its whole run plus the window.
 */
class ScreenControlWindow(private val clock: () -> Long = { Ids.now() }) {
  @Volatile private var lastAt: Long = Long.MIN_VALUE

  fun mark() { lastAt = clock() }

  fun recentlyUsed(): Boolean = lastAt != Long.MIN_VALUE && clock() - lastAt < WINDOW_MS

  companion object { const val WINDOW_MS = 10_000L }
}
