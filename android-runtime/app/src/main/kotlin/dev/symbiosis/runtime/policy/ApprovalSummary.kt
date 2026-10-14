package dev.symbiosis.runtime.policy

import dev.symbiosis.runtime.capabilities.Capability
import dev.symbiosis.runtime.core.Json

/**
 * What an approval prompt shows: the arguments in an order the agent cannot choose. The target first (the
 * capability's targetArg, or for a resolver-backed tool such as notifications.reply, who it reaches), then the
 * message body, then the other declared params in declared order, each value in full. Arguments the capability
 * does not declare follow, flagged as undeclared. Values are compact JSON with control and Unicode format
 * characters escaped, so a value cannot draw a fake line (a newline and "to: ...") or reorder text (bidi controls).
 *
 * Undeclared arguments are shown rather than rejected: the passthrough droid-mcp tools receive the args map as is,
 * and their declared parameter lists (read from the AAR) are what we show, but nothing here proves a tool reads
 * no other key, so rejecting could break a working call while flagging cannot.
 */
object ApprovalSummary {
  data class Line(val key: String, val value: String, val declared: Boolean)

  /** Argument names treated as the message body, shown right after the target. */
  val BODY_KEYS = listOf("body", "text", "message", "content")

  const val RECIPIENT = "recipient"

  fun lines(cap: Capability?, args: Map<String, Any?>): List<Line> {
    val declared = buildList {
      cap?.targetArg?.let(::add)
      cap?.params?.forEach { add(it.name) }
    }.distinct()
    val order = buildList {
      cap?.targetArg?.let(::add)
      BODY_KEYS.filter { it in declared }.forEach(::add)
      addAll(declared)
    }.distinct()
    val out = mutableListOf<Line>()
    cap?.targetResolver?.let { r ->
      val who = runCatching { r(args) }.getOrDefault(emptyList()).filter { it.isNotBlank() }
      out += Line(RECIPIENT, if (who.isEmpty()) "unknown" else who.joinToString(", ") { value(it) }, declared = true)
    }
    for (k in order) if (args.containsKey(k)) out += Line(k, value(args[k]), declared = true)
    for (k in args.keys.filter { it !in declared }.sorted()) out += Line(escape(k), value(args[k]), declared = false)
    return out
  }

  /** One line per argument, `key: value`, undeclared ones marked. */
  fun format(lines: List<Line>): String = lines.joinToString("\n") { l ->
    if (l.declared) "${l.key}: ${l.value}" else "${l.key} (undeclared): ${l.value}"
  }

  fun format(cap: Capability?, args: Map<String, Any?>): String = format(lines(cap, args))

  private fun value(v: Any?): String = escape(Json.canonical(Json.toJson(v)))

  /** Escapes what [Json.canonical] leaves: C1 controls, line/paragraph separators and format characters (bidi). */
  private fun escape(s: String): String = buildString {
    for (c in s) {
      val t = Character.getType(c)
      val hidden = c.code < 0x20 || c.code in 0x7f..0x9f || t == Character.FORMAT.toInt() ||
        t == Character.LINE_SEPARATOR.toInt() || t == Character.PARAGRAPH_SEPARATOR.toInt()
      if (hidden) append(String.format("\\u%04x", c.code)) else append(c)
    }
  }
}
