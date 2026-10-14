package dev.symbiosis.runtime.capabilities

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.policy.RateClass
import dev.symbiosis.runtime.ui.SymbiosisAccessibilityService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Screen gestures droid-mcp does not offer, dispatched through our own accessibility service.
 *
 * droid-mcp 0.10.1's gesture tools are `tap` (one 50 ms stroke), `long_press` (one held stroke, mapped as
 * ui.long_press_coordinates) and `gesture` (a single swipe stroke through points): none can put two taps in one
 * gesture. Two separate `tap` calls are not a double tap either — two round trips can exceed the ~300 ms
 * double-tap window, and they would be two MUTATION charges for one user action.
 */
object UiGestures {
  private const val TAP_MS = 50L

  fun all(): List<Capability> = listOf(doubleTap())

  fun doubleTap() = Capability(
    name = "ui.double_tap",
    description = "Double-tap at screen coordinates (pixels) via the accessibility service: two 50 ms taps in one gesture, interval_ms apart (default 100, 40-250; the platform's double-tap window is about 300 ms).",
    params = listOf(
      Param("x", "number", "Screen X in pixels", required = true),
      Param("y", "number", "Screen Y in pixels", required = true),
      Param("interval_ms", "integer", "Gap between the first tap's release and the second tap (default 100, 40-250)"),
    ),
    // Same class, tier and rate class as ui.tap_coordinates: one screen action (docs/security.md §2.3a).
    actionClass = ActionClass.DEVICE_MUTATION, tier = 2, rateClass = RateClass.MUTATION,
  ) { args, _ ->
    val svc = SymbiosisAccessibilityService.current
      ?: return@Capability CapabilityResult.unavailable("accessibility service not enabled: turn on Android Agent Harness in Settings > Accessibility")
    val x = (args["x"] as? Number)?.toFloat() ?: args["x"]?.toString()?.toFloatOrNull()
    val y = (args["y"] as? Number)?.toFloat() ?: args["y"]?.toString()?.toFloatOrNull()
    if (x == null || y == null || x < 0 || y < 0) return@Capability CapabilityResult.fail("invalid_params", "x and y must be non-negative numbers")
    val gap = ((args["interval_ms"] as? Number)?.toLong() ?: 100L).coerceIn(40, 250)
    val gesture = GestureDescription.Builder()
      .addStroke(GestureDescription.StrokeDescription(tapPath(x, y), 0, TAP_MS))
      .addStroke(GestureDescription.StrokeDescription(tapPath(x, y), TAP_MS + gap, TAP_MS))
      .build()
    // The whole gesture is ~350 ms; the bound is for a dispatch the platform never answers.
    val completed = withTimeoutOrNull(3_000) { dispatch(svc, gesture) }
    when (completed) {
      true -> CapabilityResult.ok(mapOf("success" to true, "x" to x, "y" to y, "interval_ms" to gap))
      false -> CapabilityResult.fail("capability.failed", "double tap was cancelled or rejected by the platform")
      null -> CapabilityResult.fail("capability.failed", "double tap dispatch did not complete within 3 s")
    }
  }

  /**
   * A tap stroke: moveTo plus a half-pixel lineTo, the same shape droid-mcp's own tap uses (a bare moveTo is an
   * empty path, which StrokeDescription rejects on some releases).
   */
  private fun tapPath(x: Float, y: Float) = Path().apply { moveTo(x, y); lineTo(x + 0.5f, y + 0.5f) }

  private suspend fun dispatch(svc: AccessibilityService, g: GestureDescription): Boolean = suspendCancellableCoroutine { cont ->
    val cb = object : AccessibilityService.GestureResultCallback() {
      override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
      override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
    }
    // null handler: callbacks arrive on the service's main thread; dispatchGesture itself may be called from any thread.
    if (!svc.dispatchGesture(g, cb, null) && cont.isActive) cont.resume(false)
  }
}
