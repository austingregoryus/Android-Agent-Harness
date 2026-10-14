package dev.symbiosis.runtime.core

import dev.symbiosis.runtime.capabilities.CallContext
import dev.symbiosis.runtime.capabilities.Capability
import dev.symbiosis.runtime.capabilities.CapabilityRegistry
import dev.symbiosis.runtime.capabilities.Principal
import kotlinx.serialization.json.JsonObject

/** What one tool call by name came to: a tools/call result envelope, or a JSON-RPC-level refusal. */
sealed class DispatchOutcome {
  /** The pipeline ran (policy outcomes included): the `tools/call` result envelope, isError and all. */
  data class Result(val body: JsonObject) : DispatchOutcome()

  /** Refused before the pipeline: -32601 unknown or scope-hidden tool, -32602 invalid arguments. */
  data class RpcError(val code: Int, val message: String) : DispatchOutcome()
}

/**
 * The single entry point for "call tool X by name, as this caller": lookup → scope/ceiling → argument
 * validation → [Runtime.invoke] (idempotency → audit intent → policy → rate limit → execute → audit outcome).
 *
 * It exists as its own seam because there are two callers and they must not drift apart: `McpHandler`
 * (a direct tools/call) and `automation.run` (each step of a batch). Before automation, the scope check and
 * argument validation lived inline in McpHandler and only `invoke` was shared; a second caller that called
 * `invoke` directly would have skipped the scope gate — a peer could have batched a tool it cannot see. Now
 * both go through here, so a batch step is exactly a direct call from the same principal: nothing is checked
 * less, and nothing needs to be kept in sync by hand.
 *
 * [invoke] is a parameter (not a Runtime reference) so tests can drive the real lookup/scope/validation code
 * against a real registry and a real PolicyEngine without constructing the whole Runtime.
 */
class ToolDispatch(
  private val registry: CapabilityRegistry,
  private val invoke: suspend (Principal, Capability, Map<String, Any?>, CallContext) -> JsonObject,
) {
  suspend fun dispatch(principal: Principal, name: String, args: Map<String, Any?>, ctx: CallContext): DispatchOutcome {
    val cap = registry.get(name)
    // Hidden and nonexistent are deliberately the same answer (docs/protocol.md §2): no enumeration by probing.
    if (cap == null || !principal.canSee(cap)) return DispatchOutcome.RpcError(-32601, "unknown tool: $name")
    validateArgs(cap, args)?.let { return DispatchOutcome.RpcError(-32602, it) }
    return DispatchOutcome.Result(invoke(principal, cap, args, ctx))
  }

  companion object {
    /** Required params present and primitive types match the declared JSON type; null on success. */
    fun validateArgs(cap: Capability, args: Map<String, Any?>): String? {
      for (p in cap.params) {
        val v = args[p.name]
        if (p.required && v == null) return "missing required argument: ${p.name}"
        if (v == null) continue
        val ok = when (p.type) {
          "string" -> v is String
          "integer" -> (v is Number && v.toDouble() % 1.0 == 0.0) || (v is String && v.toLongOrNull() != null)
          "number" -> v is Number || (v is String && v.toDoubleOrNull() != null)
          "boolean" -> v is Boolean
          "array" -> v is List<*>
          "object" -> v is Map<*, *>
          else -> true
        }
        if (!ok) return "argument ${p.name} must be ${p.type}"
        if (p.enum != null && v.toString() !in p.enum) return "argument ${p.name} must be one of ${p.enum.joinToString(", ")}"
      }
      return null
    }
  }
}
