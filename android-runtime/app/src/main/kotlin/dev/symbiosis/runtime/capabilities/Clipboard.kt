package dev.symbiosis.runtime.capabilities

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.PersistableBundle
import android.view.View
import android.view.WindowManager
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.policy.RateClass
import dev.symbiosis.runtime.ui.SymbiosisAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * `device.clipboard_set` / `device.clipboard_get`.
 *
 * **Reading is the hard half.** Since Android 10, ClipboardService hands the clip only to the default IME, to
 * a holder of READ_CLIPBOARD_IN_BACKGROUND, or to the app whose window has input focus; anyone else silently gets
 * null. READ_CLIPBOARD_IN_BACKGROUND is `signature|role` on the Pixel (Android 17) and the Nokia (Android 13) —
 * checked with `dumpsys package permission` on 2026-09-26 — so it is not privileged-grantable and no privapp
 * install can hold it. (The OnePlus was not reachable over adb that day; LineageOS keeps AOSP's definition.)
 *
 * What does work on every phone is the focus rule: the accessibility service may add a TYPE_ACCESSIBILITY_OVERLAY
 * window without SYSTEM_ALERT_WINDOW, and a focusable one takes input focus, which makes our uid "focused" for the
 * moment it takes to read the clip. So clipboard_get adds a 1x1 transparent, untouchable, focusable overlay,
 * reads when it gains focus, and removes it — tens of milliseconds. Chosen over a transparent activity (the other
 * known route) because an activity pauses whatever app the owner is using and needs a background-activity-launch
 * exemption; an overlay does neither. The costs: it needs the accessibility service on (the same one-time switch
 * `ui.*` needs), the focused app loses input focus for that instant (an open keyboard may drop), and Android 12+
 * shows its "Android Agent Harness pasted from your clipboard" toast — which is also the owner's signal that it happened.
 * Without the service, the only read attempted is a direct one, which answers only when our own app is in front;
 * otherwise the result is `clipboard_read_unavailable`, never a fake "empty".
 *
 * **Writing** is not focus-gated (ClipboardService checks only the WRITE_CLIPBOARD app-op), so clipboard_set
 * works from the background service on every phone; it runs on the main thread anyway, where ClipboardManager's
 * listeners live.
 */
object Clipboard {
  const val MAX_TEXT = 64 * 1024
  private const val FOCUS_WAIT_MS = 1_500L

  fun all(context: Context): List<Capability> = listOf(set(context), get(context))

  fun set(context: Context) = Capability(
    name = "device.clipboard_set",
    description = "Put text on the phone's clipboard (replacing what is there). sensitive=true marks it so Android 13+ hides it from the paste preview.",
    params = listOf(
      Param("text", "string", "Text to copy (max 64 KB)", required = true),
      Param("label", "string", "User-visible label for the clip (default \"Android Agent Harness\")"),
      Param("sensitive", "boolean", "Mark as sensitive, e.g. a password or code (default false)"),
    ),
    // A local, reversible mutation like files.write: LOW_RISK with the MUTATION rate class. The owner's current
    // clip is overwritten, which is the whole point, and nothing leaves the phone.
    actionClass = ActionClass.LOW_RISK, rateClass = RateClass.MUTATION, tier = 0,
  ) { args, _ ->
    val text = args["text"]?.toString() ?: return@Capability CapabilityResult.fail("invalid_params", "text is required")
    if (text.length > MAX_TEXT) return@Capability CapabilityResult.fail("invalid_params", "text is over ${MAX_TEXT / 1024} KB")
    val label = args["label"]?.toString()?.take(100) ?: "Android Agent Harness"
    val sensitive = args["sensitive"] == true
    withContext(Dispatchers.Main) {
      val cm = context.getSystemService(ClipboardManager::class.java)
      val clip = ClipData.newPlainText(label, text)
      if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
      cm.setPrimaryClip(clip)
    }
    CapabilityResult.ok(mapOf("set" to true, "chars" to text.length, "label" to label, "sensitive" to sensitive))
  }

  fun get(context: Context) = Capability(
    name = "device.clipboard_get",
    description = "Read the phone's current clipboard text. Needs the Android Agent Harness accessibility service enabled (Android blocks background clipboard reads); " +
      "takes input focus for an instant and Android 12+ shows a 'pasted from your clipboard' toast. Clipboards often hold codes and passwords.",
    params = emptyList(),
    // Clipboards hold OTPs, passwords and copied messages: classified with the messaging reads, SENSITIVE_READ.
    actionClass = ActionClass.SENSITIVE_READ, tier = 2,
  ) { _, _ ->
    val svc = SymbiosisAccessibilityService.current
    if (svc != null) {
      val clip = withTimeoutOrNull(FOCUS_WAIT_MS + 1_000) { readWithFocus(svc) }
      return@Capability when (clip) {
        null -> CapabilityResult.fail("clipboard_read_unavailable", "the focus overlay never gained input focus (a secure or system window may be on top); try again")
        else -> CapabilityResult.ok(clipJson(context, clip.clip, "accessibility_focus"))
      }
    }
    // No service: a direct read answers only if our own activity happens to be focused, and null cannot be told
    // apart from "denied", so null is reported as unavailable rather than as an empty clipboard.
    val direct = withContext(Dispatchers.Main) { context.getSystemService(ClipboardManager::class.java).primaryClip }
    if (direct != null) CapabilityResult.ok(clipJson(context, direct, "direct"))
    else CapabilityResult.fail(
      "clipboard_read_unavailable",
      "Android blocks clipboard reads from the background; enable the Android Agent Harness accessibility service (Settings > Accessibility) so clipboard_get can read through a focus overlay",
    )
  }

  /** Holder so "read succeeded, clipboard empty" (clip == null) is distinguishable from "never got focus". */
  private class Read(val clip: ClipData?)

  private suspend fun readWithFocus(svc: SymbiosisAccessibilityService): Read? = withContext(Dispatchers.Main) {
    val wm = svc.getSystemService(WindowManager::class.java)
    val cm = svc.getSystemService(ClipboardManager::class.java)
    val result = CompletableDeferred<Read>()
    val view = object : View(svc) {
      override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && !result.isCompleted) result.complete(Read(runCatching { cm.primaryClip }.getOrNull()))
      }
    }
    val lp = WindowManager.LayoutParams(
      1, 1,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      // Focusable (no FLAG_NOT_FOCUSABLE) is the point; untouchable and not touch-modal so no tap is ever eaten.
      WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      PixelFormat.TRANSLUCENT,
    ).apply { title = "symbiosis-clipboard" } // the view draws nothing; 1x1 px
    try {
      wm.addView(view, lp)
    } catch (t: Throwable) {
      return@withContext null
    }
    try {
      withTimeoutOrNull(FOCUS_WAIT_MS) { result.await() }
    } finally {
      runCatching { wm.removeViewImmediate(view) }
    }
  }

  private fun clipJson(context: Context, clip: ClipData?, via: String): Map<String, Any?> {
    if (clip == null || clip.itemCount == 0) return mapOf("empty" to true, "text" to null, "via" to via)
    val item = clip.getItemAt(0)
    // Plain text first; then HTML's text; a URI is reported, never dereferenced (coerceToText would open it).
    val text = item.text?.toString() ?: item.htmlText ?: item.uri?.toString()
    val desc: ClipDescription = clip.description
    val sensitive = desc.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) ?: false
    return mapOf(
      "empty" to (text == null),
      "text" to text?.take(MAX_TEXT),
      "truncated" to ((text?.length ?: 0) > MAX_TEXT),
      "label" to desc.label?.toString(),
      "mime_types" to (0 until desc.mimeTypeCount).map { desc.getMimeType(it) },
      "item_count" to clip.itemCount,
      "is_uri" to (item.text == null && item.htmlText == null && item.uri != null),
      "sensitive" to sensitive,
      "timestamp" to desc.timestamp.takeIf { it > 0 },
      "via" to via,
    )
  }

  /** ClipDescription.EXTRA_IS_SENSITIVE (API 33); the literal so it can be set on older releases harmlessly. */
  private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
}
