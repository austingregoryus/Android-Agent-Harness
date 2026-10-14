package dev.symbiosis.runtime.capabilities

import dev.symbiosis.runtime.policy.ActionClass
import java.util.concurrent.ConcurrentHashMap

/** Scope glob matching per docs/protocol.md §2. Compiled patterns are cached per glob. */
object Scopes {
  private val cache = ConcurrentHashMap<String, Pair<Regex, Regex?>>()

  private fun compile(glob: String): Pair<Regex, Regex?> = cache.getOrPut(glob) {
    val re = buildString {
      append('^')
      for (ch in glob) when (ch) {
        '*' -> append("[^.]*")
        '.' -> append("\\.")
        else -> append(Regex.escape(ch.toString()))
      }
      append('$')
    }
    // A trailing ".*" also matches deeper segments (messaging.* matches messaging.a.b).
    val deep = if (glob.endsWith(".*")) Regex("^" + Regex.escape(glob.dropLast(2)) + "\\..+$") else null
    Regex(re) to deep
  }

  fun matches(glob: String, name: String): Boolean {
    if (glob == "*") return true
    val (re, deep) = compile(glob)
    return re.matches(name) || (deep?.matches(name) ?: false)
  }

  fun anyMatch(globs: List<String>, name: String) = globs.any { matches(it, name) }

  /**
   * The capability namespace a peer must hold to receive events of [topic]. Event payloads carry the same
   * data as the corresponding read tools, so they are gated by the same scopes.
   */
  fun requiredScopeForTopic(topic: String): String = when (topic.substringBefore('.')) {
    "messaging" -> "messaging.*"
    "notification" -> "notifications.*"
    "call", "session" -> "telephony.*"
    "policy" -> "events.*"
    // Workflow run announcements name workflows and their owners: visible to peers that hold automation.*.
    "automation" -> "automation.*"
    else -> "device.*" // battery, connectivity, runtime
  }

  /** Every namespace [requiredScopeForTopic] can return. Keep in step with it: `*` must require all of them. */
  val TOPIC_SCOPES = listOf("messaging.*", "notifications.*", "telephony.*", "events.*", "device.*", "automation.*")

  /** Events expose the same personal data as sensitive read tools, regardless of their delivery path. */
  fun requiredActionClassForTopic(topic: String): ActionClass = when (topic.substringBefore('.')) {
    "messaging", "notification", "call", "session" -> ActionClass.SENSITIVE_READ
    "automation" -> ActionClass.AUTOMATION
    else -> ActionClass.READ_ONLY
  }

  /** A wildcard namespace can include sensitive topics, so its entire subscription needs that ceiling. */
  fun requiredActionClassForTopicGlob(glob: String): ActionClass {
    val first = glob.substringBefore('.')
    return if (first.isEmpty() || first.contains('*')) ActionClass.SENSITIVE_READ
    else requiredActionClassForTopic(glob)
  }

  /**
   * All namespaces a topic glob could expand to; a glob whose first segment is a wildcard touches every namespace.
   * (`*` used to omit `automation.*`, so a peer without it could subscribe to everything and receive workflow run
   * announcements through the glob.)
   */
  fun requiredScopesForTopicGlob(glob: String): List<String> {
    val first = glob.substringBefore('.')
    return if (first.isEmpty() || first.contains('*')) TOPIC_SCOPES
    else listOf(requiredScopeForTopic(glob))
  }
}

/** Who is asking: resolved from the bearer token. */
data class Principal(
  val peerId: String,
  val label: String,
  val scopes: List<String>,
  val maxActionClass: ActionClass,
) {
  val isAdmin: Boolean get() = peerId == "peer_admin"

  fun canSee(cap: Capability): Boolean = Scopes.anyMatch(scopes, cap.name) && cap.actionClass <= maxActionClass

  /** Whether this peer's scopes and action ceiling permit the data in [topic]. */
  fun canSeeTopic(topic: String): Boolean = isAdmin ||
    (hasScope(Scopes.requiredScopeForTopic(topic)) && Scopes.requiredActionClassForTopic(topic) <= maxActionClass)

  /** Every namespace and data class reachable through [glob] must be authorized. */
  fun canSubscribe(glob: String): Boolean = isAdmin ||
    (Scopes.requiredActionClassForTopicGlob(glob) <= maxActionClass &&
      Scopes.requiredScopesForTopicGlob(glob).all { hasScope(it) })

  /** True when one of the peer's scopes covers the whole [namespaceGlob] (e.g. "messaging.*"). */
  private fun hasScope(namespaceGlob: String): Boolean {
    val probe = namespaceGlob.removeSuffix(".*") + ".x"
    return scopes.any { it == "*" || it == namespaceGlob || Scopes.matches(it, probe) }
  }
}

/** Thread-safe registry of capabilities keyed by dotted name. */
class CapabilityRegistry {
  private val caps = ConcurrentHashMap<String, Capability>()

  fun register(cap: Capability) { caps[cap.name] = cap }
  fun registerAll(list: Iterable<Capability>) = list.forEach { register(it) }
  fun get(name: String): Capability? = caps[name]
  fun all(): List<Capability> = caps.values.sortedBy { it.name }
  fun visibleTo(p: Principal): List<Capability> = all().filter { p.canSee(it) }
  fun size() = caps.size
  fun names(): Set<String> = caps.keys
}
