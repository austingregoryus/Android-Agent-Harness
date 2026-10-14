package dev.symbiosis.runtime.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Shared JSON configuration and Map<->JsonElement bridges (droid-mcp tools speak in Maps). */
object Json {
  val lenient: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
  }

  fun toJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Float -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Enum<*> -> JsonPrimitive(value.name)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
    is Iterable<*> -> JsonArray(value.map { toJson(it) })
    is Array<*> -> JsonArray(value.map { toJson(it) })
    is ByteArray -> JsonPrimitive(android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP))
    else -> JsonPrimitive(value.toString())
  }

  fun fromJson(element: JsonElement?): Any? = when (element) {
    null, JsonNull -> null
    is JsonPrimitive -> when {
      element.isString -> element.content
      element.booleanOrNull != null -> element.booleanOrNull
      element.longOrNull != null -> element.longOrNull
      element.doubleOrNull != null -> element.doubleOrNull
      else -> element.contentOrNull
    }
    is JsonObject -> element.entries.associate { (k, v) -> k to fromJson(v) }
    is JsonArray -> element.map { fromJson(it) }
  }

  fun toMap(obj: JsonObject?): Map<String, Any?> =
    obj?.entries?.associate { (k, v) -> k to fromJson(v) } ?: emptyMap()

  /** Canonical (sorted keys, no whitespace) encoding used for hashing. */
  fun canonical(element: JsonElement): String = when (element) {
    is JsonObject -> element.entries.sortedBy { it.key }
      .joinToString(",", "{", "}") { (k, v) -> "\"${escape(k)}\":${canonical(v)}" }
    is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
    is JsonPrimitive -> if (element.isString) "\"${escape(element.content)}\"" else element.content
    JsonNull -> "null"
  }

  private fun escape(s: String): String = buildString {
    for (c in s) when (c) {
      '"' -> append("\\\"")
      '\\' -> append("\\\\")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
    }
  }
}
