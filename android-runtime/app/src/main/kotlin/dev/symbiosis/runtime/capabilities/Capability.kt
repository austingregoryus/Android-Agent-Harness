package dev.symbiosis.runtime.capabilities

import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.policy.RateClass
import kotlinx.serialization.json.JsonObject

/** One declared input parameter, rendered into JSON Schema. */
data class Param(
  val name: String,
  val type: String, // string | integer | number | boolean | array | object
  val description: String,
  val required: Boolean = false,
  val enum: List<String>? = null,
  /** JSON Schema for array elements (rendered as `items`), so a model sees e.g. automation.run's step shape. */
  val items: JsonObject? = null,
)

/** Everything a tool call needs to know about who asked and why. */
data class CallContext(
  val peerId: String?,
  val correlationId: String,
  val idempotencyKey: String? = null,
  val approvalWaitMs: Long? = null,
  /** The authenticated caller; capabilities that expose data beyond their own name (events, sessions) gate on it. */
  val principal: Principal? = null,
  /**
   * Set on the context of every automation.run step. automation.run refuses to execute under it, which keeps
   * batches flat even if a future path (a saved workflow calling another) tried to nest one: the step parser
   * already rejects `automation.*` tools, and this is the second, independent check.
   */
  val inAutomation: Boolean = false,
)

/** Outcome of executing a capability. Mirrors droid-mcp's ToolResult but with a machine-readable code. */
data class CapabilityResult(
  val ok: Boolean,
  val data: Map<String, Any?> = emptyMap(),
  val errorCode: String? = null,
  val errorDetail: String? = null,
) {
  companion object {
    fun ok(data: Map<String, Any?>) = CapabilityResult(true, data)
    fun fail(code: String, detail: String? = null, data: Map<String, Any?> = emptyMap()) = CapabilityResult(false, data, code, detail)
    fun unavailable(detail: String) = fail("capability.unavailable", detail)
    fun missingPermission(vararg perms: String) = fail("permission.missing", perms.joinToString(","), mapOf("permissions" to perms.toList()))
  }
}

/**
 * A typed capability: the unit the registry, policy engine, audit log, and MCP surface all reason about.
 * Names are dotted (`messaging.send_sms`). [source] identifies where it came from (`native` or `droid-mcp:<name>`).
 */
class Capability(
  val name: String,
  val description: String,
  val params: List<Param>,
  val actionClass: ActionClass,
  val tier: Int = 1,
  val rateClass: RateClass = RateClass.NONE,
  val requiredPermissions: List<String> = emptyList(),
  val emitsEvents: List<String> = emptyList(),
  val source: String = "native",
  val readOnly: Boolean = actionClass <= ActionClass.SENSITIVE_READ,
  val idempotent: Boolean = false,
  val version: String = "0.1",
  /** Argument key that names a contact/number target, checked against allow/deny lists. */
  val targetArg: String? = null,
  val execute: suspend (args: Map<String, Any?>, ctx: CallContext) -> CapabilityResult,
) {
  fun inputSchema(): JsonObject = kotlinx.serialization.json.buildJsonObject {
    put("type", kotlinx.serialization.json.JsonPrimitive("object"))
    put(
      "properties",
      kotlinx.serialization.json.buildJsonObject {
        for (p in params) put(
          p.name,
          kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive(p.type))
            put("description", kotlinx.serialization.json.JsonPrimitive(p.description))
            p.enum?.let { e -> put("enum", kotlinx.serialization.json.JsonArray(e.map { kotlinx.serialization.json.JsonPrimitive(it) })) }
            p.items?.let { put("items", it) }
          },
        )
      },
    )
    put("required", kotlinx.serialization.json.JsonArray(params.filter { it.required }.map { kotlinx.serialization.json.JsonPrimitive(it.name) }))
    put("additionalProperties", kotlinx.serialization.json.JsonPrimitive(true))
  }
}
