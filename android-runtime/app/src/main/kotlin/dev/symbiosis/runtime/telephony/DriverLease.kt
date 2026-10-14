package dev.symbiosis.runtime.telephony

/**
 * Phone-side driver lease for conversation mode.
 *
 * A conversation is driven from off the phone: the peer that started `telephony.converse` reads transcripts from
 * its event socket and answers with `telephony.speak` / `play_audio`. If that driver dies (its host crashed, the
 * LAN dropped, the gateway was restarted) the phone used to keep the call up indefinitely, listening and muted,
 * with nobody to answer: an orphaned live call, with a real person on the other end hearing silence. Nothing on
 * the phone noticed, because nothing on the phone knew a driver was expected.
 *
 * The lease makes the phone notice. The owner is "present" while it holds any open socket to the runtime or has
 * a request executing; otherwise it is absent since its last request or socket close. Absent for [LEASE_MS] and
 * the conversation is stopped and the call ended, with a `call.conversation {reason: driver_lost}` event.
 *
 * Managed dialing also has a startup deadline before conversation mode begins, covering a lost dial response.
 * Scope: calls explicitly marked driver_managed or on which a peer started conversation mode. The owner's own calls have
 * no session and no conversation, so they are never in [Conversations] and can never be hung up by this.
 */
object DriverLease {
  /** Long enough to ride out a gateway restart or a Wi-Fi reassociation; short enough that a stranded caller is not left for long. */
  const val LEASE_MS = 90_000L
  /** How often the watchdog looks. The lease is measured from timestamps, so this only bounds the overshoot. */
  const val CHECK_EVERY_MS = 10_000L

  fun startupExpired(now: Long, createdAt: Long, managed: Boolean, conversationStarted: Boolean): Boolean =
    managed && !conversationStarted && now - createdAt >= LEASE_MS

  /**
   * Has the owner been gone for the whole lease? [lastSeenAt] is the owner's last request or socket close (null if
   * never seen in this process); [startedAt] is when conversation mode began, so a lease always gets its full
   * window even for an owner the phone has not seen since it restarted.
   */
  fun expired(now: Long, startedAt: Long, lastSeenAt: Long?, present: Boolean, leaseMs: Long = LEASE_MS): Boolean {
    if (present) return false
    val since = maxOf(startedAt, lastSeenAt ?: Long.MIN_VALUE)
    return now - since >= leaseMs
  }
}
