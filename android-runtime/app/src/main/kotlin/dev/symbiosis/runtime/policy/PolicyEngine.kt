package dev.symbiosis.runtime.policy

import android.content.ContentValues
import dev.symbiosis.runtime.capabilities.Capability
import dev.symbiosis.runtime.core.Ids
import dev.symbiosis.runtime.core.Json
import dev.symbiosis.runtime.storage.Db
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar

/**
 * Phone-side policy. Evaluated for every tools/call in the order documented in docs/protocol.md §6.
 * Emergency stop and observe-only live here and are not capabilities: no tool can toggle them.
 */
class PolicyEngine(private val db: Db, private val rateLimiter: RateLimiter) {
  private val _emergencyStop = MutableStateFlow(readFlag("emergency_stop"))
  private val _observeOnly = MutableStateFlow(readFlag("observe_only"))
  private val _config = MutableStateFlow(readConfig().let { (cfg, corrupt) -> migrateUnattended(cfg, corrupt) })

  val emergencyStop: StateFlow<Boolean> = _emergencyStop
  val observeOnly: StateFlow<Boolean> = _observeOnly
  val config: StateFlow<PolicyConfig> = _config

  /** Listeners notified when emergency stop flips on, so sessions and sockets can be torn down. */
  var onEmergencyStop: (() -> Unit)? = null

  fun setEmergencyStop(on: Boolean) {
    writeFlag("emergency_stop", on)
    _emergencyStop.value = on
    if (on) onEmergencyStop?.invoke()
  }

  fun setObserveOnly(on: Boolean) {
    writeFlag("observe_only", on)
    _observeOnly.value = on
  }

  fun updateConfig(cfg: PolicyConfig) {
    db.writableDatabase.insertWithOnConflict(
      "settings", null,
      ContentValues().apply { put("key", "policy_config"); put("value", Json.lenient.encodeToString(PolicyConfig.serializer(), cfg)) },
      android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
    )
    _config.value = cfg
  }

  fun capabilityOverride(name: String): PolicyDecision? = db.readableDatabase.query(
    "policies", arrayOf("decision"), "capability = ?", arrayOf(name), null, null, null,
  ).use { c -> if (c.moveToFirst()) PolicyDecision.valueOf(c.getString(0)) else null }

  fun setCapabilityOverride(name: String, decision: PolicyDecision?) {
    if (decision == null) db.writableDatabase.delete("policies", "capability = ?", arrayOf(name))
    else db.writableDatabase.insertWithOnConflict(
      "policies", null,
      ContentValues().apply { put("capability", name); put("decision", decision.name); put("updated_at", Ids.now()) },
      android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
    )
  }

  fun overrides(): Map<String, PolicyDecision> = db.readableDatabase.query("policies", null, null, null, null, null, null).use { c ->
    buildMap { while (c.moveToNext()) put(c.getString(c.getColumnIndexOrThrow("capability")), PolicyDecision.valueOf(c.getString(c.getColumnIndexOrThrow("decision")))) }
  }

  /**
   * Steps 1, 2, 4–8 of the pipeline (scope is enforced by the registry before we get here).
   * Does not record rate usage — call [recordUsage] after a successful execution.
   */
  fun evaluate(cap: Capability, args: Map<String, Any?>, approved: Boolean = false): PolicyOutcome {
    if (_emergencyStop.value) return PolicyOutcome.Deny("policy.emergency_stop", "emergency stop is engaged")
    if (_observeOnly.value && cap.actionClass > ActionClass.SENSITIVE_READ) return PolicyOutcome.Deny("policy.observe_only", "runtime is in observe-only mode")

    val cfg = _config.value
    // A saved config is a snapshot of the defaults at the time it was saved, so a class or rate class added
    // later (AUTOMATION) is simply absent from it. Fall back to the code default for that key rather than to
    // APPROVE / no limit: otherwise every phone whose policy was ever edited would ask before each batch, and
    // never meter them, while a fresh install would not.
    var decision = capabilityOverride(cap.name) ?: cfg.classDefaults[cap.actionClass]
      ?: PolicyConfig.DEFAULTS.classDefaults[cap.actionClass] ?: PolicyDecision.APPROVE
    var approveReason = "class default ${cap.actionClass}"

    if (decision == PolicyDecision.DENY) return PolicyOutcome.Deny("policy.denied", "capability ${cap.name} is denied by policy")

    if (cap.actionClass >= ActionClass.COMMUNICATION) {
      // A resolver names every identity the call reaches (a notification reply: sender name and number); an
      // argument names one. Denied if ANY identity is on the denylist, allowlisted only if EVERY one is, so a group
      // thread or a name the list does not hold still asks. An unresolvable target counts as not on the allowlist.
      val resolved = cap.targetResolver?.let { r -> runCatching { r(args) }.getOrDefault(emptyList()).filter { it.isNotBlank() } }
      val targets = resolved ?: cap.targetArg?.let { a -> args[a]?.toString()?.let { listOf(it) } }
      if (resolved != null && resolved.isEmpty() && cfg.contactAllowlist.isNotEmpty() && decision == PolicyDecision.ALLOW) {
        decision = PolicyDecision.APPROVE; approveReason = "target unknown, not on allowlist"
      }
      if (!targets.isNullOrEmpty()) {
        if (targets.any { t -> cfg.contactDenylist.any { sameTarget(it, t) } }) return PolicyOutcome.Deny("policy.denied", "target is on the denylist")
        val allowlisted = targets.all { t -> cfg.contactAllowlist.any { sameTarget(it, t) } }
        if (cfg.contactAllowlist.isNotEmpty() && !allowlisted && decision == PolicyDecision.ALLOW) {
          decision = PolicyDecision.APPROVE; approveReason = "target not on allowlist"
        }
        // The allowlist is the user's standing approval: a call or message to a number on it does not need a
        // tap every time, even for a class whose default is APPROVE (place_call and send_sms are external
        // commitments). An explicit per-capability APPROVE override still wins.
        if (allowlisted && decision == PolicyDecision.APPROVE && capabilityOverride(cap.name) == null) {
          decision = PolicyDecision.ALLOW
        }
      }
      if (cfg.quietHours.enabled && inQuietHours(cfg.quietHours) && decision == PolicyDecision.ALLOW) {
        decision = PolicyDecision.APPROVE; approveReason = "quiet hours"
      }
    }

    // A human approval satisfies the APPROVE step; deny checks above and the rate reservation below still apply.
    if (decision == PolicyDecision.APPROVE && !approved) return PolicyOutcome.Approve(approveReason)

    // Rate limits apply to every class that declares a rate class, and the slot is reserved atomically here.
    val limit = cfg.rateLimits[cap.rateClass] ?: PolicyConfig.DEFAULTS.rateLimits[cap.rateClass]
    if (limit != null) {
      val check = rateLimiter.reserve(cap.rateClass, limit)
      if (!check.allowed) return PolicyOutcome.Deny(
        "policy.rate_limited", "rate limit for ${cap.rateClass} exceeded",
        mapOf("retry_after_ms" to check.retryAfterMs, "hour_used" to check.hourUsed, "day_used" to check.dayUsed),
      )
      return PolicyOutcome.Allow(check.reservationId)
    }
    return PolicyOutcome.Allow()
  }

  /** Give back a reserved rate slot when the allowed action did not execute. */
  fun release(outcome: PolicyOutcome) { if (outcome is PolicyOutcome.Allow) rateLimiter.release(outcome.reservationId) }

  private fun inQuietHours(q: QuietHours, cal: Calendar = Calendar.getInstance()): Boolean {
    val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    return if (q.startMinute <= q.endMinute) m in q.startMinute until q.endMinute else m >= q.startMinute || m < q.endMinute
  }

  private fun readFlag(key: String): Boolean = db.readableDatabase.query("settings", arrayOf("value"), "key = ?", arrayOf(key), null, null, null)
    .use { c -> c.moveToFirst() && c.getString(0) == "1" }

  private fun writeFlag(key: String, on: Boolean) {
    db.writableDatabase.insertWithOnConflict(
      "settings", null, ContentValues().apply { put("key", key); put("value", if (on) "1" else "0") },
      android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
    )
  }

  /**
   * Once per install: move a SAVED config onto the unattended defaults.
   *
   * The config is persisted with every default encoded, so a phone that ever had its policy edited (the Pixel,
   * whose allowlist was set) keeps APPROVE for texts, calls and screen control no matter what the code default
   * says. This rewrites exactly those three things and clears the allowlist, because a non-empty allowlist
   * turns every other number back into an approval prompt, and the owner does not want an allowlist on sending.
   * The marker makes it one-shot: if the owner later flips "Ask me first" back on, that choice is kept.
   */
  private fun migrateUnattended(cfg: PolicyConfig, storedConfigCorrupt: Boolean): PolicyConfig {
    if (readFlag(MIGRATED_UNATTENDED)) return cfg
    // A stored row that failed to decode is not "nothing stored yet": cfg is only PolicyConfig()'s defaults
    // as a fallback. Persisting the migration over it would permanently overwrite whatever the user had
    // (denylist, quiet hours) with those defaults. Skip and leave the marker unset, so this is retried next
    // start once the row is readable again.
    if (storedConfigCorrupt) return cfg
    val next = cfg.copy(
      classDefaults = cfg.classDefaults + mapOf(
        ActionClass.EXTERNAL_COMMITMENT to PolicyDecision.ALLOW,
        ActionClass.DEVICE_MUTATION to PolicyDecision.ALLOW,
      ),
      rateLimits = cfg.rateLimits + (RateClass.MUTATION to PolicyConfig().rateLimits.getValue(RateClass.MUTATION)),
      contactAllowlist = emptyList(),
    )
    db.writableDatabase.insertWithOnConflict(
      "settings", null,
      ContentValues().apply { put("key", "policy_config"); put("value", Json.lenient.encodeToString(PolicyConfig.serializer(), next)) },
      android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
    )
    writeFlag(MIGRATED_UNATTENDED, true)
    return next
  }

  /** True when texts, calls and screen control wait for the owner's tap. */
  val asksFirst: Boolean
    get() = _config.value.classDefaults[ActionClass.EXTERNAL_COMMITMENT] == PolicyDecision.APPROVE

  /** The app's "Ask me first" switch. Deliberately not a capability: an agent must not be able to lift it. */
  fun setAsksFirst(on: Boolean) {
    val d = if (on) PolicyDecision.APPROVE else PolicyDecision.ALLOW
    val cfg = _config.value
    updateConfig(cfg.copy(classDefaults = cfg.classDefaults + mapOf(
      ActionClass.EXTERNAL_COMMITMENT to d,
      ActionClass.DEVICE_MUTATION to d,
    )))
  }

  /** Returns the stored config (or PolicyConfig() defaults) plus whether a row existed but failed to decode
   *  — migrateUnattended needs that distinction; see there. */
  private fun readConfig(): Pair<PolicyConfig, Boolean> = db.readableDatabase.query("settings", arrayOf("value"), "key = ?", arrayOf("policy_config"), null, null, null)
    .use { c ->
      if (!c.moveToFirst()) return@use PolicyConfig() to false
      val decoded = runCatching { Json.lenient.decodeFromString(PolicyConfig.serializer(), c.getString(0)) }
      decoded.getOrDefault(PolicyConfig()) to decoded.isFailure
    }

  companion object {
    private const val MIGRATED_UNATTENDED = "policy_migrated_unattended_v1"

    /**
     * Best-effort E.164 for list comparison. Assumes NANP (+1) for bare 10-digit and 1-prefixed 11-digit
     * numbers; short codes and other lengths are left as digits. A default country code setting can replace
     * the +1 assumption later without changing callers.
     */
    private val DIAL_DECORATIONS = Regex("^(\\*67|\\*82|#31#|\\*31#|\\*\\d{2,3}#?)+")

    /** Dial-string decorations (caller-id prefixes, pauses, waits) are stripped before comparison so they cannot evade a list. */
    fun stripDialDecorations(s: String): String {
      // Pauses/waits split a dial string into segments; the target is the first segment that still holds a
      // full number after caller-id prefixes are removed (later segments are post-dial digits such as extensions).
      val segments = s.trim().split(Regex("[,;pPwW]")).map { it.trim().replace(DIAL_DECORATIONS, "") }
      var t = segments.firstOrNull { it.count { c -> c.isDigit() } >= 7 } ?: segments.joinToString("")
      if (t.startsWith("00")) t = "+" + t.substring(2) // international access prefix
      return t
    }

    /** Compare complete normalized numbers; a shared national suffix does not make country codes equal. */
    fun sameNumber(a: String, b: String): Boolean = normalizeNumber(a) == normalizeNumber(b)

    /**
     * A contact-list entry against a call's target. Numbers compare as [sameNumber]. When either side has no digits
     * (a contact name, as a notification title gives it) they compare as trimmed, case-insensitive text instead:
     * two different names both normalize to "" and must not count as the same number.
     */
    fun sameTarget(entry: String, target: String): Boolean {
      val a = normalizeNumber(entry); val b = normalizeNumber(target)
      if (a.isEmpty() || b.isEmpty()) return entry.isNotBlank() && entry.trim().equals(target.trim(), ignoreCase = true)
      return a == b
    }

    fun normalizeNumber(raw: String): String {
      val s = stripDialDecorations(raw)
      val plus = s.startsWith("+")
      val digits = s.filter { it.isDigit() }
      return when {
        plus -> "+$digits"
        digits.length == 10 -> "+1$digits"
        digits.length == 11 && digits.startsWith("1") -> "+$digits"
        digits.length > 11 -> "+$digits"
        else -> digits
      }
    }
  }
}
