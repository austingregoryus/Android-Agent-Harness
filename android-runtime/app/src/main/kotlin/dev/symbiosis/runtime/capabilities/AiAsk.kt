package dev.symbiosis.runtime.capabilities

import dev.symbiosis.runtime.core.Json
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.policy.AiConfig
import dev.symbiosis.runtime.policy.RateClass
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/**
 * `ai.ask`: one non-streaming chat completion from the owner's LAN LLM router, straight from the phone, so a
 * workflow can think with the PC off. The request mirrors tools/converse.py's Brain against the same router:
 * POST {base}/chat/completions with `{model, messages, stream:false, max_tokens, reasoning_effort:"none"}` —
 * "none" because the router's general models otherwise spend a small max_tokens budget reasoning and return an
 * empty string with no error (converse.py's hard-won rule).
 *
 * Classification: **LOW_RISK, rate class `ai`**, not SENSITIVE_READ. SENSITIVE_READ is for reads that expose
 * personal data *to the caller*; ai.ask exposes nothing new to the caller — the risk runs the other way, data
 * leaving the phone, which is an action. LOW_RISK keeps it above every read class, so a read-only ceiling
 * (`SENSITIVE_READ`) peer cannot use it to move what it has read off the phone, and it counts under observe-only as
 * a non-read (refused). The destination is fixed by the phone's own config (not by the caller) and, for plain
 * HTTP, by the network security config, which permits cleartext to the router's address only. Whatever reaches a
 * prompt was already obtained through a tool the caller was allowed to call.
 */
object AiAsk {
  const val MAX_PROMPT = 32 * 1024
  /** Longest reply body read; beyond it the reply is refused rather than half-parsed. */
  const val MAX_RESPONSE = 256 * 1024

  fun capability(config: () -> AiConfig) = Capability(
    name = "ai.ask",
    description = "Ask the owner's LAN language model (OpenAI-compatible router; model set in the phone's config) and get its answer: {text}, or {json} " +
      "parsed from the reply when json=true. Runs on the phone, so it works in workflows with the PC off. Up to 60 s.",
    params = listOf(
      Param("prompt", "string", "The question or instruction (max 32 KB)", required = true),
      Param("system", "string", "System prompt"),
      Param("model", "string", "Router model (default: the phone's configured model; other models share GPUs on that box and evict each other)"),
      Param("max_tokens", "integer", "Reply budget (default 512, max 4096)"),
      Param("json", "boolean", "Ask for a JSON object and parse it into result.json (default false)"),
    ),
    actionClass = ActionClass.LOW_RISK, rateClass = RateClass.AI, tier = 0,
  ) { args, _ -> ask(config(), args) }

  fun ask(cfg: AiConfig, args: Map<String, Any?>): CapabilityResult {
    val prompt = args["prompt"]?.toString().orEmpty()
    if (prompt.isBlank()) return CapabilityResult.fail("invalid_params", "prompt is required")
    if (prompt.length > MAX_PROMPT) return CapabilityResult.fail("invalid_params", "prompt is over 32 KB")
    val wantJson = args["json"] == true
    val model = args["model"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: cfg.model
    // McpHandler's validation accepts "512" for an integer param, so coerce numeric strings too.
    val maxTokens = ((args["max_tokens"] as? Number)?.toInt() ?: args["max_tokens"]?.toString()?.trim()?.toIntOrNull() ?: 512).coerceIn(1, 4096)
    var system = args["system"]?.toString()?.takeIf { it.isNotBlank() }
    if (wantJson) system = (system?.let { "$it\n\n" } ?: "") + "Reply with a single JSON object and nothing else."
    val body = buildJsonObject {
      put("model", model)
      put("messages", buildJsonArray {
        system?.let { add(buildJsonObject { put("role", "system"); put("content", it) }) }
        add(buildJsonObject { put("role", "user"); put("content", prompt) })
      })
      put("stream", false)
      put("max_tokens", maxTokens)
      put("temperature", 0.3)
      put("reasoning_effort", "none")
      if (wantJson) put("response_format", buildJsonObject { put("type", "json_object") })
    }
    val url = endpoint(cfg.baseUrl)
    val t0 = System.currentTimeMillis()
    val conn = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) { return CapabilityResult.fail("invalid_params", "bad ai base url '$url': ${e.message}") }
    val totalMs = cfg.timeoutMs.coerceIn(1_000, 120_000)
    val deadline = System.nanoTime() + totalMs * 1_000_000
    // readTimeout is per read: a server trickling a byte every few seconds would never trip it. Two fences: the read
    // loop checks the overall deadline between reads (and each read is bounded by readTimeout), and a watchdog
    // disconnects at the deadline, which on Android's OkHttp-backed connection also fails a read that is blocked.
    val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
    val watchdog = Thread {
      try { Thread.sleep(totalMs); timedOut.set(true); conn.disconnect() } catch (_: InterruptedException) {}
    }.apply { isDaemon = true; name = "ai.ask-deadline"; start() }
    val (status, text) = try {
      conn.requestMethod = "POST"
      conn.connectTimeout = 5_000
      conn.readTimeout = minOf(totalMs, 10_000L).toInt()
      conn.doOutput = true
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty("Accept", "application/json")
      conn.outputStream.use { it.write(body.toString().toByteArray()) }
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      val bytes = stream?.use { readCapped(it, MAX_RESPONSE + 1, deadline) } ?: ByteArray(0)
      if (bytes.size > MAX_RESPONSE) return CapabilityResult.fail("ai.bad_response", "reply is larger than ${MAX_RESPONSE / 1024} KB", mapOf("model" to model))
      code to String(bytes, Charsets.UTF_8)
    } catch (e: Exception) {
      if (timedOut.get() || e is java.net.SocketTimeoutException) return CapabilityResult.fail("ai.timeout", "no complete answer from $url within $totalMs ms", mapOf("model" to model))
      return CapabilityResult.fail("ai.unreachable", "${e.javaClass.simpleName}: ${e.message} ($url)", mapOf("model" to model))
    } finally { watchdog.interrupt(); conn.disconnect() }
    val ms = System.currentTimeMillis() - t0
    if (status !in 200..299) return CapabilityResult.fail("ai.http_error", "HTTP $status: ${text.take(300)}", mapOf("model" to model, "status" to status))
    val resp = runCatching { Json.lenient.parseToJsonElement(text) as JsonObject }.getOrNull()
      ?: return CapabilityResult.fail("ai.bad_response", "not JSON: ${text.take(300)}")
    val choice = (resp["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
    val content = ((choice?.get("message") as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    val usage = resp["usage"]?.let { Json.fromJson(it) }
    val usedModel = (resp["model"] as? JsonPrimitive)?.contentOrNull ?: model
    if (content.isEmpty()) return CapabilityResult.fail("ai.empty", "the model returned no text (a reasoning model out of budget?)", mapOf("model" to usedModel, "ms" to ms))
    if (!wantJson) return CapabilityResult.ok(mapOf("text" to content, "model" to usedModel, "ms" to ms, "usage" to usage))
    val parsed = parseJsonReply(content)
      ?: return CapabilityResult.fail("ai.bad_json", "the reply was not a JSON object", mapOf("text" to content, "model" to usedModel, "ms" to ms))
    return CapabilityResult.ok(mapOf("json" to parsed, "text" to content, "model" to usedModel, "ms" to ms, "usage" to usage))
  }

  private fun readCapped(input: java.io.InputStream, cap: Int, deadlineNanos: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (out.size() < cap) {
      if (System.nanoTime() > deadlineNanos) throw java.net.SocketTimeoutException("overall deadline")
      val n = input.read(buf, 0, minOf(buf.size, cap - out.size()))
      if (n < 0) break
      out.write(buf, 0, n)
    }
    return out.toByteArray()
  }

  /** `…/v1` + `/chat/completions`; a bare host gets `/v1/chat/completions`. */
  fun endpoint(base: String): String {
    val b = base.trim().trimEnd('/')
    return if (b.endsWith("/v1")) "$b/chat/completions" else "$b/v1/chat/completions"
  }

  /** Small models wrap JSON in ```json fences or a sentence; take the outermost {...} if the whole reply is not JSON. */
  fun parseJsonReply(text: String): JsonElement? {
    val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    runCatching { return Json.lenient.parseToJsonElement(t) as JsonObject }
    val a = t.indexOf('{'); val b = t.lastIndexOf('}')
    if (a < 0 || b <= a) return null
    return runCatching { Json.lenient.parseToJsonElement(t.substring(a, b + 1)) as JsonObject }.getOrNull()
  }
}
