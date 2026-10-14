package dev.symbiosis.runtime.automation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A `{{...}}` reference that could not be resolved. Fails the step that used it, never the whole batch. */
class TemplateError(message: String) : Exception(message)

/**
 * `{{...}}` substitution in step arguments.
 *
 * References:
 *  - `{{steps.<id>.result.<path>}}` — a field of an earlier step's structuredContent. `<path>` is dotted, and an
 *    array element is either a numeric segment or brackets: `items.0.name` and `items[0].name` are the same.
 *    `{{steps.<id>.ok}}`, `{{steps.<id>.error}}` and `{{steps.<id>.ms}}` are also readable, so a later engine
 *    with conditions can branch on a failed `on_error: continue` step without a new syntax.
 *  - `{{input.<name>(.<path>)}}` — the batch's top-level `input` object (a saved workflow's run_saved input).
 *  - `{{event.id}}`, `{{event.topic}}`, `{{event.ts}}`, `{{event.payload.<path>}}` — the event that triggered a saved
 *    workflow. Absent (a reference is an error) for manual runs and schedules.
 *
 * Rules (the contract saved workflows will be stored against, so they are deliberately small and strict):
 *  - Substitution happens only inside JSON **string values**, recursively through objects and arrays. Object keys,
 *    numbers, booleans and the step's `tool` are never templated: what tool runs must be readable in the audit
 *    row as written, not computed.
 *  - A string that is exactly one reference (`"{{steps.a.result.count}}"`, surrounding whitespace allowed inside
 *    the braces only) is replaced by the referenced JSON value with its type: a number stays a number, an object
 *    stays an object. Anything else is string interpolation: strings are inserted as-is, other values as compact
 *    JSON (`null`, `3`, `{"a":1}`).
 *  - A missing reference (unknown step, step not run yet, path absent, index out of range, a failed step's
 *    `result`) is a [TemplateError]. Silently substituting "" would turn "text {{steps.lookup.result.number}}"
 *    into an SMS to nobody, or a tap at 0,0.
 *  - There is no escape syntax and no expressions: `{{` without a closing `}}` is literal text.
 */
object Templates {
  // Every brace escaped, including the closing pair: Android's ICU regex rejects a bare `}` (PatternSyntaxException
  // on the phone) although the desktop JVM running the unit tests accepts it.
  private val REF = Regex("\\{\\{\\s*([^{}]*?)\\s*\\}\\}")
  private val WHOLE = Regex("^\\{\\{\\s*([^{}]*?)\\s*\\}\\}$")

  /** What references resolve against: the input object and the finished steps by id. */
  class Scope(val input: JsonObject, val steps: Map<String, JsonObject>, val event: JsonObject? = null)

  fun resolve(value: JsonElement, scope: Scope): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { (_, v) -> resolve(v, scope) })
    is JsonArray -> JsonArray(value.map { resolve(it, scope) })
    is JsonPrimitive -> if (value.isString) resolveString(value.content, scope) else value
    JsonNull -> value
  }

  /** True if [value] contains any `{{...}}` reference; used to tell a static argument from a templated one. */
  fun hasReferences(value: JsonElement): Boolean = when (value) {
    is JsonObject -> value.values.any { hasReferences(it) }
    is JsonArray -> value.any { hasReferences(it) }
    is JsonPrimitive -> value.isString && REF.containsMatchIn(value.content)
    JsonNull -> false
  }

  fun resolveString(s: String, scope: Scope): JsonElement {
    WHOLE.matchEntire(s)?.let { return lookup(it.groupValues[1], scope) }
    if (!REF.containsMatchIn(s)) return JsonPrimitive(s)
    return JsonPrimitive(REF.replace(s) { m -> render(lookup(m.groupValues[1], scope)) })
  }

  private fun render(e: JsonElement): String = when (e) {
    is JsonPrimitive -> if (e.isString) e.content else e.toString()
    else -> e.toString() // kotlinx renders JsonObject/JsonArray/JsonNull as compact JSON
  }

  /** Resolves one reference expression or throws [TemplateError] saying exactly which part is missing. */
  fun lookup(expr: String, scope: Scope): JsonElement {
    val segs = segments(expr)
    if (segs.isEmpty()) throw TemplateError("empty reference {{}}")
    return when (segs[0]) {
      "input" -> {
        if (segs.size < 2) throw TemplateError("{{input}} needs a name: {{input.<name>}}")
        walk(scope.input, segs.drop(1), "input")
      }
      "steps" -> {
        if (segs.size < 3) throw TemplateError("{{$expr}}: use {{steps.<id>.result.<path>}}")
        val id = segs[1]
        val step = scope.steps[id] ?: throw TemplateError("{{$expr}}: step '$id' has not run (unknown id, a later step, or skipped)")
        val field = segs[2]
        if (field !in STEP_FIELDS) throw TemplateError("{{$expr}}: a step exposes ${STEP_FIELDS.joinToString("/")}, not '$field'")
        if (field == "result" && step["result"] == null) throw TemplateError("{{$expr}}: step '$id' has no result (it failed, or its if was false)")
        walk(step, segs.drop(2), "steps.$id")
      }
      "event" -> {
        val ev = scope.event ?: throw TemplateError("{{$expr}}: this run was not started by an event")
        if (segs.size < 2) throw TemplateError("{{event}} needs a field: {{event.payload.<name>}}, {{event.topic}}, {{event.id}}")
        walk(ev, segs.drop(1), "event")
      }
      else -> throw TemplateError("{{$expr}}: references start with steps., input. or event.")
    }
  }

  private val STEP_FIELDS = setOf("result", "ok", "error", "ms", "skipped")

  private fun walk(root: JsonElement, path: List<String>, prefix: String): JsonElement {
    var cur: JsonElement = root
    var at = prefix
    for (seg in path) {
      cur = when (val c = cur) {
        is JsonObject -> c[seg] ?: throw TemplateError("{{$at.$seg}} is missing")
        is JsonArray -> {
          val i = seg.toIntOrNull() ?: throw TemplateError("{{$at}} is an array; '$seg' is not an index")
          c.getOrNull(i) ?: throw TemplateError("{{$at.$seg}}: index out of range (size ${c.size})")
        }
        else -> throw TemplateError("{{$at}} is not an object or array, so it has no '$seg'")
      }
      at = "$at.$seg"
    }
    return cur
  }

  /** `a.b[0].c` → [a, b, 0, c]. Empty segments (`a..b`) are an error rather than silently skipped. */
  private fun segments(expr: String): List<String> {
    val out = mutableListOf<String>()
    for (part in expr.trim().split('.')) {
      val bracket = part.indexOf('[')
      if (bracket >= 0) {
        val head = part.substring(0, bracket)
        if (head.isNotEmpty()) out += head
        var rest = part.substring(bracket)
        while (rest.isNotEmpty()) {
          val close = rest.indexOf(']')
          if (!rest.startsWith("[") || close < 0) throw TemplateError("{{$expr}}: malformed [index]")
          out += rest.substring(1, close).trim()
          rest = rest.substring(close + 1)
        }
        continue
      }
      if (part.isEmpty()) throw TemplateError("{{$expr}}: empty path segment")
      out += part
    }
    return out
  }
}
