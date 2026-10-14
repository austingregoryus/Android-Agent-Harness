package dev.symbiosis.runtime.transport

import dev.symbiosis.runtime.capabilities.Principal
import dev.symbiosis.runtime.core.Ids
import dev.symbiosis.runtime.core.Json
import dev.symbiosis.runtime.core.Runtime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap

/** Outcome of handling one JSON-RPC message. */
sealed class McpResponse {
  data class Reply(val body: JsonObject, val sessionId: String? = null) : McpResponse()
  data object Accepted : McpResponse() // notification → 202
  data class HttpError(val status: Int, val message: String) : McpResponse()
}

/**
 * MCP Streamable HTTP request handler (docs/protocol.md §3). Transport-agnostic: the Ktor route
 * hands it a parsed body, the auth principal, and the session header.
 */
class McpHandler(private val runtime: Runtime) {
  private data class McpSession(val peerId: String, val createdAt: Long, val protocolVersion: String)
  private val sessions = ConcurrentHashMap<String, McpSession>()

  fun endSession(id: String, peerId: String): Boolean = sessions.computeIfPresent(id) { _, s -> if (s.peerId == peerId) null else s } == null && !sessions.containsKey(id)
  fun endSessionsFor(peerId: String) = sessions.entries.removeIf { it.value.peerId == peerId }
  fun sessionCount() = sessions.size

  suspend fun handle(principal: Principal, body: JsonElement, sessionHeader: String?, protocolVersionHeader: String?, correlationHeader: String?): McpResponse {
    if (body is JsonArray) return McpResponse.HttpError(400, "batch not supported")
    val msg = body as? JsonObject ?: return McpResponse.Reply(rpcError(JsonNull, -32600, "invalid request"))
    val id = msg["id"]
    val method = msg["method"]?.jsonPrimitive?.contentOrNull
    val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())

    if (method == null) {
      // A response from the client (we never send requests) — accept and ignore.
      return McpResponse.Accepted
    }
    if (id == null || id is JsonNull) {
      // Notification.
      return McpResponse.Accepted
    }

    if (method == "initialize") {
      val version = params["protocolVersion"]?.jsonPrimitive?.contentOrNull ?: "2025-06-18"
      val negotiated = if (version in SUPPORTED) version else "2025-06-18"
      // Bounded per peer: evict this peer's oldest sessions beyond the cap, and any session idle for a day.
      val cutoff = Ids.now() - 24 * 3600_000L
      sessions.entries.removeIf { it.value.createdAt < cutoff }
      val mine = sessions.entries.filter { it.value.peerId == principal.peerId }.sortedBy { it.value.createdAt }
      if (mine.size >= MAX_SESSIONS_PER_PEER) mine.take(mine.size - MAX_SESSIONS_PER_PEER + 1).forEach { sessions.remove(it.key) }
      val sid = "mcp_" + Ids.hex(12)
      sessions[sid] = McpSession(principal.peerId, Ids.now(), negotiated)
      val result = buildJsonObject {
        put("protocolVersion", negotiated)
        put("capabilities", buildJsonObject { put("tools", buildJsonObject { put("listChanged", false) }); put("resources", buildJsonObject { put("subscribe", false); put("listChanged", false) }) })
        put("serverInfo", buildJsonObject { put("name", "android-agent-harness-runtime"); put("version", runtime.version) })
        put("instructions", INSTRUCTIONS)
        put("_meta", buildJsonObject { put("symbiosis", buildJsonObject { put("protocol", "0.1"); put("device_id", runtime.deviceId) }) })
      }
      return McpResponse.Reply(rpcResult(id, result), sid)
    }

    // Every other request needs a session bound to this peer.
    val sid = sessionHeader ?: return McpResponse.HttpError(404, "missing Mcp-Session-Id; initialize first")
    val sess = sessions[sid] ?: return McpResponse.HttpError(404, "unknown session; initialize again")
    if (sess.peerId != principal.peerId) return McpResponse.HttpError(401, "session belongs to another peer")
    if (protocolVersionHeader != null && protocolVersionHeader !in SUPPORTED) return McpResponse.HttpError(400, "unsupported MCP-Protocol-Version")

    return when (method) {
      "ping" -> McpResponse.Reply(rpcResult(id, JsonObject(emptyMap())))
      "tools/list" -> McpResponse.Reply(rpcResult(id, buildJsonObject { put("tools", JsonArray(runtime.registry.visibleTo(principal).map { runtime.toolJson(it) })) }))
      "tools/call" -> {
        val name = params["name"]?.jsonPrimitive?.contentOrNull ?: return McpResponse.Reply(rpcError(id, -32602, "name is required"))
        val argsEl = params["arguments"]
        // An unknown/hidden tool must answer -32601 whatever its arguments look like, so a peer cannot tell a
        // hidden tool from a missing one by sending malformed arguments: check visibility before the shape.
        val cap = runtime.registry.get(name)
        if (cap == null || !principal.canSee(cap)) return McpResponse.Reply(rpcError(id, -32601, "unknown tool: $name"))
        if (argsEl != null && argsEl !is JsonNull && argsEl !is JsonObject) return McpResponse.Reply(rpcError(id, -32602, "arguments must be an object"))
        val args = Json.toMap(argsEl as? JsonObject)
        val meta = params["_meta"] as? JsonObject
        val corr = meta?.get("correlationId")?.jsonPrimitive?.contentOrNull ?: correlationHeader ?: Ids.correlation()
        val idem = meta?.get("idempotencyKey")?.jsonPrimitive?.contentOrNull
        val wait = meta?.get("approvalWaitMs")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        // The one dispatch path (core/ToolDispatch.kt), shared with automation.run's steps.
        when (val r = runtime.dispatch(principal, name, args, dev.symbiosis.runtime.capabilities.CallContext(principal.peerId, corr, idem, wait, principal))) {
          is dev.symbiosis.runtime.core.DispatchOutcome.RpcError -> McpResponse.Reply(rpcError(id, r.code, r.message))
          is dev.symbiosis.runtime.core.DispatchOutcome.Result -> McpResponse.Reply(rpcResult(id, r.body))
        }
      }
      "resources/list" -> McpResponse.Reply(rpcResult(id, buildJsonObject { put("resources", JsonArray(runtime.resources().map { (uri, desc) -> buildJsonObject { put("uri", uri); put("name", uri.removePrefix("phone://")); put("description", desc); put("mimeType", "application/json") } })) }))
      "resources/read" -> {
        val uri = params["uri"]?.jsonPrimitive?.contentOrNull ?: return McpResponse.Reply(rpcError(id, -32602, "uri is required"))
        val content = runtime.readResource(principal, uri) ?: return McpResponse.Reply(rpcError(id, -32602, "unknown resource: $uri"))
        McpResponse.Reply(rpcResult(id, buildJsonObject { put("contents", JsonArray(listOf(buildJsonObject { put("uri", uri); put("mimeType", "application/json"); put("text", Json.lenient.encodeToString(JsonElement.serializer(), content)) }))) }))
      }
      "prompts/list" -> McpResponse.Reply(rpcResult(id, buildJsonObject { put("prompts", JsonArray(emptyList())) }))
      "completion/complete", "logging/setLevel" -> McpResponse.Reply(rpcError(id, -32601, "method not supported: $method"))
      else -> McpResponse.Reply(rpcError(id, -32601, "method not found: $method"))
    }
  }

  private fun rpcResult(id: JsonElement, result: JsonElement) = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }
  private fun rpcError(id: JsonElement, code: Int, message: String) = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject { put("code", code); put("message", message) }) }

  companion object {
    val SUPPORTED = setOf("2025-06-18", "2025-03-26", "2024-11-05")
    const val MAX_SESSIONS_PER_PEER = 8
    const val INSTRUCTIONS = "This server is a physical Android phone. Tools are namespaced (messaging.*, telephony.*, contacts.*, device.*, events.*). " +
      "Policy runs on the phone: a result with isError and structuredContent.error = policy.approval_required means a human must approve; do not retry blindly. " +
      "Use events.subscribe + events.replay (or /ws/events) instead of polling read tools. telephony.place_call returns a session_id; watch call.* events or telephony.get_session. " +
      "automation.run runs several tool calls in one round trip, each checked exactly like a direct call."
  }
}
