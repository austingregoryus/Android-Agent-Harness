package dev.symbiosis.runtime.automation

import dev.symbiosis.runtime.core.Json
import kotlinx.serialization.json.JsonObject

/** What a failed step does to the rest of the batch. */
enum class OnError { STOP, CONTINUE }

/**
 * One step: call [tool] with [args] (which may hold `{{...}}` references, see [Templates]), after waiting
 * [waitMs]. This is the unit a saved workflow will persist, so it stays plain data with no behaviour: an
 * executor, a trigger or a UI can all build and read it.
 */
data class Step(
  val id: String,
  val tool: String,
  val args: JsonObject,
  val onError: OnError = OnError.STOP,
  val waitMs: Long = 0,
  /**
   * `if`: run the step only when this holds. Keys are template strings (`"{{steps.x.result.json.action}}"`),
   * values are [Matcher] conditions. False → the step is reported `skipped` (ok, no result) and the batch goes on.
   */
  val condition: JsonObject? = null,
)

/**
 * A validated batch. [input] is the `{{input.*}}` namespace: empty for an ad-hoc `automation.run`, filled from a
 * saved workflow's parameters (or the triggering event) once those exist.
 */
data class Batch(val steps: List<Step>, val timeoutMs: Long, val input: JsonObject, val event: JsonObject? = null)

/** The batch as a whole is malformed: reported as invalid_params before any step runs. */
class BatchError(message: String) : Exception(message)

object Batches {
  const val MAX_STEPS = 25
  const val DEFAULT_TIMEOUT_MS = 60_000L
  const val MAX_TIMEOUT_MS = 300_000L
  const val MIN_TIMEOUT_MS = 1_000L

  /** No batch may run a tool in this namespace: batches do not nest (see [parse]). */
  const val NAMESPACE = "automation."

  private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

  /**
   * An integer the same way McpHandler's argument validation accepts one for an "integer" param: a whole JSON number
   * or a numeric string ("60000"). The two used to disagree, so a value validation passed was then refused here.
   */
  private fun integer(v: Any?): Long? = when (v) {
    is Number -> if (v.toDouble() % 1.0 == 0.0) v.toLong() else null
    is String -> v.trim().toLongOrNull()
    else -> null
  }

  /**
   * Parses and validates `{steps, timeout_ms?, input?}` as it arrives from a tool call (already decoded to
   * Kotlin maps/lists by McpHandler). Everything checkable without running a step is checked here, so a batch
   * with a typo in step 20 fails before step 1 texts anyone:
   *  - 1..[MAX_STEPS] steps, each with a non-empty `tool` and an object `args` (absent = {});
   *  - no `automation.*` tool (no nesting — also re-checked at execution via CallContext.inAutomation);
   *  - ids unique and simple; a step without one gets its 0-based index as its id, so `{{steps.0.result}}` works;
   *  - `on_error` stop|continue, `wait_ms` >= 0, `if` a valid [Matcher] spec.
   * `timeout_ms` is clamped to [MIN_TIMEOUT_MS]..[MAX_TIMEOUT_MS] rather than rejected: a caller asking for more
   * than the ceiling gets the ceiling, which the result reports.
   *
   * What is NOT checked here is whether a step's tool exists or is visible to the caller: that is the dispatch
   * path's job, per step, so a hidden tool fails the same way (unknown tool) inside a batch as outside one.
   */
  fun parse(args: Map<String, Any?>): Batch {
    val rawSteps = args["steps"] as? List<*> ?: throw BatchError("steps must be an array")
    if (rawSteps.isEmpty()) throw BatchError("steps is empty")
    if (rawSteps.size > MAX_STEPS) throw BatchError("at most $MAX_STEPS steps per batch (got ${rawSteps.size})")
    val seen = HashSet<String>()
    val steps = rawSteps.mapIndexed { i, raw ->
      val m = raw as? Map<*, *> ?: throw BatchError("steps[$i] must be an object")
      val tool = (m["tool"] as? String)?.trim().orEmpty()
      if (tool.isEmpty()) throw BatchError("steps[$i].tool is required")
      if (tool.startsWith(NAMESPACE)) throw BatchError("steps[$i]: $tool cannot run inside a batch (batches do not nest)")
      val id = when (val v = m["id"]) {
        null -> i.toString()
        is String -> v.trim().also { if (!ID.matches(it)) throw BatchError("steps[$i].id must be 1-64 of A-Z a-z 0-9 _ -") }
        else -> throw BatchError("steps[$i].id must be a string")
      }
      if (!seen.add(id)) throw BatchError("duplicate step id '$id' (an id-less step's id is its index)")
      val a = when (val v = m["args"]) {
        null -> JsonObject(emptyMap())
        is Map<*, *> -> Json.toJson(v) as JsonObject
        else -> throw BatchError("steps[$i].args must be an object")
      }
      val onError = when (val v = m["on_error"]) {
        null, "stop" -> OnError.STOP
        "continue" -> OnError.CONTINUE
        else -> throw BatchError("steps[$i].on_error must be stop or continue (got $v)")
      }
      val wait = when (val v = m["wait_ms"]) {
        null -> 0L
        else -> integer(v) ?: throw BatchError("steps[$i].wait_ms must be an integer")
      }.also { if (it < 0) throw BatchError("steps[$i].wait_ms must be >= 0") }
      val cond = when (val v = m["if"]) {
        null -> null
        is Map<*, *> -> (Json.toJson(v) as JsonObject).also {
          if (it.isEmpty()) throw BatchError("steps[$i].if is empty")
          try { Matcher.validate(it, "steps[$i].if") } catch (e: MatcherError) { throw BatchError(e.message ?: "bad if") }
        }
        else -> throw BatchError("steps[$i].if must be an object of {\"{{reference}}\": condition}")
      }
      Step(id, tool, a, onError, wait, cond)
    }
    val timeout = when (val v = args["timeout_ms"]) {
      null -> DEFAULT_TIMEOUT_MS
      else -> (integer(v) ?: throw BatchError("timeout_ms must be an integer")).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }
    val input = when (val v = args["input"]) {
      null -> JsonObject(emptyMap())
      is Map<*, *> -> Json.toJson(v) as JsonObject
      else -> throw BatchError("input must be an object")
    }
    return Batch(steps, timeout, input)
  }
}
