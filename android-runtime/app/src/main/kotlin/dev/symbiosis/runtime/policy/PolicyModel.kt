package dev.symbiosis.runtime.policy

import kotlinx.serialization.Serializable

/** Ordered risk classes from the spec. Ordinal order is the ceiling order. */
enum class ActionClass {
  READ_ONLY,
  /**
   * `automation.run`: a batch of other tool calls. The batch itself does nothing to the world — every step is
   * dispatched, scope-checked, policed, rate-limited and audited as the separate call it is — so it sits just
   * above READ_ONLY: cheap to see (any ceiling from SENSITIVE_READ up can use it, and it can only ever run the
   * steps that ceiling already allows) and it still passes under observe-only, where each mutating step is then
   * refused on its own. Ranking it high instead would buy nothing and hide batching of reads from read-only peers
   * that can already make those reads one at a time. It is not READ_ONLY itself so operators can deny or
   * require approval for batching as a separate knob.
   */
  AUTOMATION,
  /** Reads that expose personal data (message bodies, precise location, screen contents, call history). Still a read for observe-only, but a distinct policy knob. */
  SENSITIVE_READ,
  LOW_RISK, COMMUNICATION, EXTERNAL_COMMITMENT, DEVICE_MUTATION, DESTRUCTIVE;

  companion object {
    fun parse(s: String?): ActionClass? = s?.let { v -> entries.firstOrNull { it.name.equals(v, ignoreCase = true) } }
  }
}

/** AUTOMATION counts batches, not their steps (each step is charged to its own class when it runs). */
enum class RateClass { NONE, SMS, CALL, INSTALL, MUTATION, WAKE, AUTOMATION, AI }

enum class PolicyDecision { ALLOW, DENY, APPROVE }

@Serializable
data class RateLimit(val perHour: Int, val perDay: Int)

@Serializable
data class QuietHours(val enabled: Boolean = false, val startMinute: Int = 22 * 60, val endMinute: Int = 7 * 60)

/** User-editable policy configuration. Persisted as JSON in the settings table. */
@Serializable
data class PolicyConfig(
  val classDefaults: Map<ActionClass, PolicyDecision> = mapOf(
    ActionClass.READ_ONLY to PolicyDecision.ALLOW,
    ActionClass.AUTOMATION to PolicyDecision.ALLOW,
    ActionClass.SENSITIVE_READ to PolicyDecision.ALLOW,
    ActionClass.LOW_RISK to PolicyDecision.ALLOW,
    ActionClass.COMMUNICATION to PolicyDecision.ALLOW,
    // Texts, calls and screen control run unattended (owner's decision, 2026-09-23): the phones are driven
    // remotely and the owner is not in front of them most of the time, so an approval tap is an action that
    // never happens. The brakes that remain are the rate limits below, the denylist, the audit log, and
    // emergency stop / observe-only on the phone. The app's "Ask me first" switch puts both classes back to
    // APPROVE without a rebuild.
    ActionClass.EXTERNAL_COMMITMENT to PolicyDecision.ALLOW,
    ActionClass.DEVICE_MUTATION to PolicyDecision.ALLOW,
    ActionClass.DESTRUCTIVE to PolicyDecision.DENY,
  ),
  val rateLimits: Map<RateClass, RateLimit> = mapOf(
    RateClass.SMS to RateLimit(20, 100),
    RateClass.CALL to RateLimit(5, 20),
    RateClass.INSTALL to RateLimit(3, 3),
    // Screen control shares this class. One ordinary task (open an app, search, tap a result) is 6-10
    // actions, so the old 30/hour would stall an unattended agent after a handful of tasks. 300/hour still
    // stops a runaway tap loop within minutes, and with approvals off this is the main brake.
    RateClass.MUTATION to RateLimit(300, 2000),
    RateClass.WAKE to RateLimit(6, 24),
    // Deliberately loose: each step is charged to its own class, so this is not a second brake on texts or taps.
    // It only bounds a runaway loop of batches whose steps are all unmetered reads.
    RateClass.AUTOMATION to RateLimit(240, 2000),
    // ai.ask: bounds a runaway workflow hammering the LAN router (one GPU box shared with the voice stack).
    RateClass.AI to RateLimit(120, 1000),
  ),
  val contactAllowlist: List<String> = emptyList(),
  val contactDenylist: List<String> = emptyList(),
  val quietHours: QuietHours = QuietHours(),
  val approvalWaitMs: Long = 20_000,
  val approvalMaxWaitMs: Long = 120_000,
  val pendingTtlMs: Long = 10 * 60_000,
  /** Where ai.ask sends prompts. Part of the policy config because it decides where data leaves the phone. */
  val ai: AiConfig = AiConfig(),
) {
  companion object {
    /** The code defaults, for classes a saved config predates (see [PolicyEngine.evaluate]). */
    val DEFAULTS = PolicyConfig()
  }
}

/**
 * OpenAI-compatible endpoint used by ai.ask. Configure the endpoint and model for your installation.
 * The loopback default can be reached through an explicitly configured adb reverse tunnel; remote hosts
 * should use HTTPS. Plain HTTP hosts require an explicit network_security_config.xml exception.
 */
@Serializable
data class AiConfig(
  val baseUrl: String = "http://127.0.0.1:11430/v1",
  val model: String = "local-model",
  val timeoutMs: Long = 60_000,
)

/** Result of the policy pipeline for one call. */
sealed class PolicyOutcome {
  /** Allowed. [reservationId] is a reserved rate-limit slot (0 when the capability has no rate class); release it if the action never runs. */
  data class Allow(val reservationId: Long = 0) : PolicyOutcome()
  data class Deny(val code: String, val message: String, val extra: Map<String, Any?> = emptyMap()) : PolicyOutcome()
  data class Approve(val reason: String) : PolicyOutcome()
}
