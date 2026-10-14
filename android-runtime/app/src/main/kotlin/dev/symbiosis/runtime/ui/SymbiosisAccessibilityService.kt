package dev.symbiosis.runtime.ui

import io.droidmcp.accessibility.DroidMcpAccessibilityService

/**
 * The accessibility service behind `ui.*` (screen tree, find/tap, type, swipe, global actions).
 *
 * droid-mcp ships the implementation but no manifest entry — the host app has to declare a subclass, which
 * nothing did until 2026-09-18, so all ten `ui.*` tools answered "Accessibility service not bound" forever.
 * The user still has to enable it once in Settings → Accessibility (or, on a debug build, via adb:
 * `settings put secure enabled_accessibility_services dev.symbiosis.runtime/.ui.SymbiosisAccessibilityService`).
 *
 * It also publishes itself as [current] for our own native capabilities that need the live service rather than
 * a droid-mcp tool: `ui.double_tap` (dispatchGesture) and `device.clipboard_get` (a focusable accessibility
 * overlay — the one way a background app can read the clipboard on Android 10+; see capabilities/Clipboard.kt).
 * Kept here rather than read from droid-mcp's holder so those capabilities do not depend on droid-mcp internals.
 */
class SymbiosisAccessibilityService : DroidMcpAccessibilityService() {
  override fun onServiceConnected() {
    super.onServiceConnected()
    current = this
  }

  override fun onUnbind(intent: android.content.Intent?): Boolean {
    if (current === this) current = null
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    if (current === this) current = null
    super.onDestroy()
  }

  companion object {
    /** The bound service, or null while it is disabled in Settings (or the process has not been bound yet). */
    @Volatile var current: SymbiosisAccessibilityService? = null
      private set
  }
}
