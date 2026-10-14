package dev.symbiosis.runtime.capabilities

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import dev.symbiosis.runtime.policy.ActionClass
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * The runtime's own diagnostics: its recent log lines and why its process last died. Both read-only, both under
 * their own `diagnostics.*` namespace (they started under `device.get_*`, which security.md recommends as the
 * NARROW device scope — so the narrow grant leaked transcripts and message text), both SENSITIVE_READ: log lines carry phone numbers, SMS and
 * transcript text, and a crash trace can carry whatever the failing call was holding.
 *
 * Whole-device logs are deliberately not offered. READ_LOGS is `signature|privileged|development` on the Pixel
 * (Android 17) and the Nokia (Android 13) — privileged-grantable, even `pm grant`-able — but since Android 13 logd
 * routes every READ_LOGS reader through LogcatManagerService (present on both phones), which asks the user for
 * consent per request and declines outright unless the app is in the foreground (TOP). The runtime is a
 * foreground *service*, never TOP, so the grant would buy nothing on any of the three phones while adding a
 * privileged permission whose omission from the privapp allowlist crash-loops system_server on boot.
 */
object Diagnostics {

  fun all(context: Context): List<Capability> = listOf(runtimeLogs(), exitHistory(context))

  // ---------------------------------------------------------------- logs

  const val MAX_LINES = 2000
  private val LEVELS = listOf("V", "D", "I", "W", "E", "F")

  fun runtimeLogs() = Capability(
    name = "diagnostics.get_runtime_logs",
    description = "This app's own recent logcat lines (main, system and crash buffers), newest last. No special permission: an app may always read its own logs. " +
      "By default only the current process; include_previous also returns lines from earlier runs of the app still in the ring buffer (e.g. just before a crash). " +
      "Credential-shaped values are redacted.",
    params = listOf(
      Param("lines", "integer", "How many lines (default 200, max $MAX_LINES)"),
      Param("min_level", "string", "Lowest priority to include (default V)", enum = LEVELS),
      Param("tag", "string", "Only this log tag (exact match)"),
      Param("include_previous", "boolean", "Also lines from earlier processes of this app (default false)"),
    ),
    actionClass = ActionClass.SENSITIVE_READ, tier = 0,
  ) { args, _ ->
    val n = ((args["lines"] as? Number)?.toInt() ?: 200).coerceIn(1, MAX_LINES)
    val level = args["min_level"]?.toString()?.uppercase()?.takeIf { it in LEVELS } ?: "V"
    val tag = args["tag"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    if (tag != null && !tag.matches(Regex("[A-Za-z0-9_.:$-]{1,64}"))) return@Capability CapabilityResult.fail("invalid_params", "tag must be a plain log tag")
    val previous = args["include_previous"] == true
    val filtered = tag != null || level != "V"
    val cmd = buildList {
      add("logcat"); add("-d"); add("-b"); add("main,system,crash"); add("-v"); add("threadtime")
      // Without --pid, logd still returns only this app's uid's lines (no READ_LOGS), which is how the lines of
      // an earlier, crashed process of ours come back.
      if (!previous) add("--pid=${Process.myPid()}")
      // logd applies -t before logcat's own tag/level filter specs, so with a filter "-t 200" would mean "the
      // last 200 lines, then filtered" — often none. Filtered reads dump everything (size-capped below) and the
      // last n matching lines are taken here instead.
      if (!filtered) { add("-t"); add(n.toString()) }
      if (tag != null) { add("$tag:$level"); add("*:S") } else add("*:$level")
    }
    val proc = try { ProcessBuilder(cmd).redirectErrorStream(true).start() }
    catch (e: Exception) { return@Capability CapabilityResult.unavailable("could not run logcat: ${e.message}") }
    // logcat -d exits on its own, but a wedged logd must not hang the tool call: a watchdog kills it at 8 s,
    // which also unblocks the read below. Output is capped at 4 MB for the same reason.
    val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
    val watchdog = Thread {
      try { Thread.sleep(8_000); timedOut.set(true); proc.destroy() } catch (_: InterruptedException) {}
    }.apply { isDaemon = true; start() }
    val raw = try { proc.inputStream.use { readCapped(it, 4 shl 20) } } catch (e: java.io.IOException) { "" } finally { watchdog.interrupt() }
    if (!proc.waitFor(1, TimeUnit.SECONDS)) proc.destroy()
    val minRank = LEVELS.indexOf(level)
    val parsed = raw.lineSequence()
      .filter { it.isNotBlank() && !it.startsWith("--------- beginning of") }
      .map { parseLine(it) }
      // Belt and braces over logcat's own filter specs (and the only filter for unparseable lines' neighbours).
      .filter { e -> tag == null || e["tag"] == null || e["tag"] == tag }
      .filter { e -> (e["level"] as? String)?.let { rank(it) >= minRank } ?: true }
      .toList()
      .takeLast(n)
    CapabilityResult.ok(
      mapOf(
        "lines" to parsed, "count" to parsed.size, "pid" to Process.myPid(),
        "min_level" to level, "tag" to tag, "include_previous" to previous, "timed_out" to timedOut.get(),
      ),
    )
  }

  /** V < D < I < W < E < F; logcat's A (assert) ranks with F. */
  private fun rank(level: String): Int = if (level == "A") LEVELS.size - 1 else LEVELS.indexOf(level)

  // threadtime: "09-26 14:02:11.123  1234  1250 W Runtime : message"
  private val THREADTIME = Regex("^(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFA])\\s+(.*?)\\s*: (.*)$")

  /** One line → {ts, pid, tid, level, tag, msg}, message redacted; an unparseable line comes back as {raw}. */
  fun parseLine(line: String): Map<String, Any?> {
    val m = THREADTIME.matchEntire(line) ?: return mapOf("raw" to LogRedaction.redact(line))
    val (ts, pid, tid, lvl, tag, msg) = m.destructured
    return mapOf("ts" to ts, "pid" to pid.toInt(), "tid" to tid.toInt(), "level" to lvl, "tag" to tag, "msg" to LogRedaction.redact(msg))
  }

  // ---------------------------------------------------------------- exit history

  const val TRACE_CAP = 16 * 1024

  fun exitHistory(context: Context) = Capability(
    name = "diagnostics.get_exit_history",
    description = "Why this app's recent processes ended (ActivityManager.getHistoricalProcessExitReasons, Android 11+): reason, description, time, importance, pss/rss, " +
      "and for an ANR the thread dump (for a native crash the tombstone, base64) truncated to 16 KB. Newest first.",
    params = listOf(
      Param("limit", "integer", "Max records (default 10, max 50)"),
      Param("include_trace", "boolean", "Include ANR/native-crash traces (default true)"),
    ),
    actionClass = ActionClass.SENSITIVE_READ, tier = 0,
  ) { args, _ ->
    // minSdk is 29; the API arrived in 30 (Android 11). Every phone in the fleet is newer, but say so plainly if not.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@Capability CapabilityResult.unavailable("exit history needs Android 11 (API 30); this device is API ${Build.VERSION.SDK_INT}")
    val limit = ((args["limit"] as? Number)?.toInt() ?: 10).coerceIn(1, 50)
    val withTrace = args["include_trace"] != false
    val am = context.getSystemService(ActivityManager::class.java)
    val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, limit)
    CapabilityResult.ok(mapOf("exits" to infos.map { exitJson(it, withTrace) }, "count" to infos.size))
  }

  @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
  private fun exitJson(e: ApplicationExitInfo, withTrace: Boolean): Map<String, Any?> {
    val out = linkedMapOf<String, Any?>(
      "reason" to reasonName(e.reason), "reason_code" to e.reason, "description" to e.description?.let { LogRedaction.redact(it) },
      "timestamp" to e.timestamp, "time" to java.time.Instant.ofEpochMilli(e.timestamp).toString(),
      "importance" to e.importance, "pss_kb" to e.pss, "rss_kb" to e.rss, "status" to e.status,
      "pid" to e.pid, "process" to e.processName,
    )
    if (withTrace && (e.reason == ApplicationExitInfo.REASON_ANR || e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE)) {
      val bytes = runCatching { e.traceInputStream?.use { readCappedBytes(it, TRACE_CAP + 1) } }.getOrNull()
      if (bytes != null) {
        val truncated = bytes.size > TRACE_CAP
        val body = if (truncated) bytes.copyOf(TRACE_CAP) else bytes
        // An ANR trace is a text thread dump; a native crash's is a tombstone protobuf (Android 12+), so base64.
        if (e.reason == ApplicationExitInfo.REASON_ANR) {
          out["trace"] = LogRedaction.redact(String(body, Charsets.UTF_8)); out["trace_encoding"] = "text"
        } else {
          out["trace"] = android.util.Base64.encodeToString(body, android.util.Base64.NO_WRAP); out["trace_encoding"] = "base64"
        }
        out["trace_truncated"] = truncated
      }
    }
    return out
  }

  /** ApplicationExitInfo.REASON_* by value; literals so the newer ones (API 33/34) need no version guard. */
  fun reasonName(code: Int): String = when (code) {
    0 -> "unknown"; 1 -> "exit_self"; 2 -> "signaled"; 3 -> "low_memory"; 4 -> "crash"; 5 -> "crash_native"
    6 -> "anr"; 7 -> "initialization_failure"; 8 -> "permission_change"; 9 -> "excessive_resource_usage"
    10 -> "user_requested"; 11 -> "user_stopped"; 12 -> "dependency_died"; 13 -> "other"; 14 -> "freezer"
    15 -> "package_state_change"; 16 -> "package_updated"
    else -> "code_$code"
  }

  private fun readCapped(input: InputStream, cap: Int): String = String(readCappedBytes(input, cap), Charsets.UTF_8)

  private fun readCappedBytes(input: InputStream, cap: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (out.size() < cap) {
      val n = input.read(buf, 0, minOf(buf.size, cap - out.size()))
      if (n < 0) break
      out.write(buf, 0, n)
    }
    return out.toByteArray()
  }
}

/**
 * Scrubs credential-shaped text from log lines before they leave the phone. Nothing in this codebase logs a token
 * (checked 2026-09-26); the one secret that does reach logcat is the pairing code DebugReceiver prints on debug
 * builds (`SYMBIOSIS_PAIRING_CODE=...`), which is redeemable for a token for five minutes. The rest is defence
 * against libraries (Ktor, Netty, droid-mcp) and future code: bearer headers, key=value credentials, and bare
 * 43-character base64url strings — the exact shape of our bearer tokens (32 random bytes, Ids.token()).
 */
object LogRedaction {
  private val RULES: List<Pair<Regex, String>> = listOf(
    Regex("(SYMBIOSIS_PAIRING_CODE=)\\S+") to "$1[redacted]",
    Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+") to "$1[redacted]",
    Regex("(?i)\\b((?:access_|refresh_|auth_)?token|secret|password|passwd|api[_-]?key|authorization|passphrase)(\"?\\s*[:=]\\s*\"?)(?!bearer\\b|\\[redacted)[^\\s\"',;&}]+") to "$1$2[redacted]",
    Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])") to "[redacted-token]",
  )

  fun redact(s: String): String = RULES.fold(s) { acc, (re, rep) -> re.replace(acc, rep) }
}
