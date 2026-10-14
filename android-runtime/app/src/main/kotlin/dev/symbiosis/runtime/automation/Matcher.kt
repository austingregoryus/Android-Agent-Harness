package dev.symbiosis.runtime.automation

import dev.symbiosis.runtime.policy.PolicyEngine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** A `where` / `if` spec that cannot work (unknown op, bad regex, `in` without a list): refused at save time. */
class MatcherError(message: String) : Exception(message)

/** A regex ran past its deadline. The caller treats it as no-match and logs a skip. */
class RegexTimeout(val pattern: String) : RuntimeException("regex exceeded ${Matcher.REGEX_DEADLINE_MS} ms: $pattern")

/**
 * The one condition language, used by trigger `where` clauses (against an event payload) and by step `if`
 * clauses (against resolved template values), so there is exactly one set of semantics to learn and test.
 *
 * A spec maps a subject to a condition; all entries must hold (AND). A condition is either a bare JSON value
 * (shorthand for `equals`) or an object of operators, all of which must hold:
 *  - `equals`: numbers compare numerically ("20" equals 20), phone-number-shaped strings compare as the same line
 *    (PolicyEngine.sameNumber: "+14695551234" equals "4695551234"), everything else exactly.
 *  - `contains`: case-insensitive substring of the value's text.
 *  - `regex`: RE2 syntax (no backreferences or lookaround), found anywhere (anchor with ^…$; `(?i)` for
 *    case-insensitive). Patterns are at most [MAX_REGEX] chars and compiled once (at save, and cached for loads);
 *    only the first [MAX_REGEX_INPUT] chars of the value are searched; a match that runs past [REGEX_DEADLINE_MS]
 *    throws [RegexTimeout]. RE2 is linear-time, so the deadline is a second fence, not the only one: event matching
 *    runs on the workflow engine's own consumer, and a stuck match must never be able to hold it.
 *  - `in`: a list; true if the value `equals` any element.
 *  - `lt` / `gt`: numeric; a value that is not a number (or numeric string) fails.
 * A subject that is absent (or JSON null) fails every operator. There is no OR and no negation, deliberately:
 * two workflows are clearer than one clever condition, and it keeps the spec checkable at save time.
 */
object Matcher {
  private val OPS = setOf("equals", "contains", "regex", "in", "lt", "gt")
  const val MAX_REGEX = 200
  const val MAX_REGEX_INPUT = 4 * 1024
  const val REGEX_DEADLINE_MS = 50L
  private val compiled = java.util.concurrent.ConcurrentHashMap<String, com.google.re2j.Pattern>()

  /** Compile (once) or throw [MatcherError]. */
  fun compile(p: String): com.google.re2j.Pattern {
    compiled[p]?.let { return it }
    if (p.length > MAX_REGEX) throw MatcherError("regex is longer than $MAX_REGEX characters")
    val pat = try { com.google.re2j.Pattern.compile(p) } catch (e: Exception) { throw MatcherError("regex: ${e.message}") }
    if (compiled.size > 512) compiled.clear() // bounded; patterns come only from saved workflows
    compiled[p] = pat
    return pat
  }

  /** A view of [s] whose reads fail once [deadlineNanos] has passed: how the match is bounded in time. */
  private class DeadlineChars(private val s: CharSequence, private val deadlineNanos: Long, private val pattern: String) : CharSequence {
    override val length: Int get() = s.length
    override fun get(index: Int): Char {
      if (System.nanoTime() > deadlineNanos) throw RegexTimeout(pattern)
      return s[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = DeadlineChars(s.subSequence(startIndex, endIndex), deadlineNanos, pattern)
    override fun toString(): String = s.toString()
  }

  /** Search [input] (capped) for [p] within the deadline; throws [RegexTimeout]. */
  fun regexFind(p: String, input: String, deadlineMs: Long = REGEX_DEADLINE_MS): Boolean {
    val pat = runCatching { compile(p) }.getOrNull() ?: return false
    val capped = if (input.length > MAX_REGEX_INPUT) input.substring(0, MAX_REGEX_INPUT) else input
    return pat.matcher(DeadlineChars(capped, System.nanoTime() + deadlineMs * 1_000_000, p)).find()
  }

  /** Checks a spec's shape without evaluating it. */
  fun validate(spec: JsonObject, what: String) {
    for ((subject, cond) in spec) {
      if (cond !is JsonObject) continue // bare value = equals
      if (cond.isEmpty()) throw MatcherError("$what.$subject: empty condition")
      for ((op, arg) in cond) {
        if (op !in OPS) throw MatcherError("$what.$subject: unknown operator '$op' (use ${OPS.joinToString("/")})")
        when (op) {
          "in" -> if (arg !is JsonArray) throw MatcherError("$what.$subject.in must be a list")
          "lt", "gt" -> if ((arg as? JsonPrimitive)?.doubleOrNull == null) throw MatcherError("$what.$subject.$op must be a number")
          "regex" -> {
            val p = (arg as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw MatcherError("$what.$subject.regex must be a string")
            try { compile(p) } catch (e: MatcherError) { throw MatcherError("$what.$subject.${e.message}") }
          }
          "contains" -> if (arg !is JsonPrimitive || arg is JsonNull) throw MatcherError("$what.$subject.contains must be a string")
        }
      }
    }
  }

  /** `where`: subjects are payload field paths (`from`, or dotted `extras.title`). May throw [RegexTimeout]. */
  fun where(spec: JsonObject, payload: JsonObject): Boolean = spec.all { (field, cond) -> test(path(payload, field), cond) }

  /** One subject value against one condition. May throw [RegexTimeout]. */
  fun test(actual: JsonElement?, cond: JsonElement): Boolean {
    if (actual == null || actual is JsonNull) return false
    if (cond !is JsonObject) return equalsValue(actual, cond)
    return cond.all { (op, arg) ->
      when (op) {
        "equals" -> equalsValue(actual, arg)
        "contains" -> {
          val needle = (arg as? JsonPrimitive)?.contentOrNull ?: return@all false
          text(actual).contains(needle, ignoreCase = true)
        }
        "regex" -> {
          val p = (arg as? JsonPrimitive)?.contentOrNull ?: return@all false
          regexFind(p, text(actual)) // RegexTimeout propagates: the caller logs it and counts it as no-match
        }
        "in" -> (arg as? JsonArray)?.any { equalsValue(actual, it) } ?: false
        "lt" -> cmp(actual, arg)?.let { it < 0 } ?: false
        "gt" -> cmp(actual, arg)?.let { it > 0 } ?: false
        else -> false
      }
    }
  }

  private fun path(root: JsonObject, field: String): JsonElement? {
    var cur: JsonElement = root
    for (seg in field.split('.')) {
      cur = when (val c = cur) {
        is JsonObject -> c[seg] ?: return null
        is JsonArray -> seg.toIntOrNull()?.let { c.getOrNull(it) } ?: return null
        else -> return null
      }
    }
    return cur
  }

  private fun text(e: JsonElement): String = if (e is JsonPrimitive) e.content else e.toString()

  private fun number(e: JsonElement): Double? = (e as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull() }

  private fun cmp(actual: JsonElement, arg: JsonElement): Int? {
    val a = number(actual) ?: return null
    val b = number(arg) ?: return null
    return a.compareTo(b)
  }

  private val PHONE = Regex("^\\+?[0-9 ().-]{7,}$")

  private fun equalsValue(actual: JsonElement, expected: JsonElement): Boolean {
    if (actual is JsonPrimitive && expected is JsonPrimitive) {
      val an = number(actual); val en = number(expected)
      val at = actual.content; val et = expected.content
      if (PHONE.matches(at) && PHONE.matches(et) && at.count { it.isDigit() } >= 7 && et.count { it.isDigit() } >= 7) {
        return PolicyEngine.sameNumber(PolicyEngine.normalizeNumber(at), PolicyEngine.normalizeNumber(et))
      }
      if (an != null && en != null) return an == en
      return at == et
    }
    return actual == expected
  }
}
