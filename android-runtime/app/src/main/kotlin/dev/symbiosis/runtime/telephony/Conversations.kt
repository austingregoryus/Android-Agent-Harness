package dev.symbiosis.runtime.telephony

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of live conversations, keyed by the call session they belong to.
 *
 * This replaces a single global "active conversation". That global was the root of every stale-session
 * incident: it outlived the call it was started for, any peer holding a token could speak into it, and a
 * second agent's driver simply joined in — the far party heard two voices. Binding a conversation to one
 * owned call session gives it a lifecycle (it dies when the call does) and an owner (only the peer that
 * placed the call, or the admin, can speak through it). Ownership itself is checked by the capabilities,
 * which know the caller; this registry only records who started it.
 */
object Conversations {

  class Entry(val sessionId: String, val ownerPeerId: String?, val conversation: CallConversation) {
    val isRunning: Boolean get() = conversation.isRunning
    /** When conversation mode started; the driver lease counts from here until the owner is first seen. */
    val startedAt: Long = System.currentTimeMillis()
  }

  private val byId = ConcurrentHashMap<String, Entry>()
  /**
   * Guards check-then-insert in [startOrExisting] and removal in [stop]. Two `converse start` calls used to race
   * between the lookup and the insert and could start two capture loops on one call (both hear the far party,
   * both answer). Teardown itself (which joins the loop for up to 1.5 s) runs outside the lock.
   */
  private val lock = Any()

  /** The running conversation for [sessionId]. A loop that died on its own is reaped here, not handed out. */
  fun get(sessionId: String): Entry? {
    val e = byId[sessionId] ?: return null
    if (e.isRunning) return e
    reap(e, "loop no longer running")
    return null
  }

  fun all(): List<Entry> = byId.values.filter { it.isRunning }

  /**
   * Start a conversation for [sessionId], or report the one already running. Exactly one per session:
   * two loops on one call would both capture the downlink and both answer. A dead loop (capture error, uplink
   * lost) is replaced, never returned as "already running" — that answer made the driver wait for transcripts
   * that could not come.
   */
  fun startOrExisting(
    sessionId: String,
    ownerPeerId: String?,
    create: () -> CallConversation,
    start: (CallConversation) -> Result<Unit>,
  ): Result<Pair<Entry, Boolean>> {
    var dead: Entry? = null
    val result: Result<Pair<Entry, Boolean>> = synchronized(lock) {
      val existing = byId[sessionId]
      if (existing != null && existing.isRunning) return Result.success(existing to false)
      if (existing != null) { byId.remove(sessionId, existing); dead = existing }
      val conv = create()
      val r = start(conv)
      if (r.isFailure) Result.failure(r.exceptionOrNull() ?: IllegalStateException("could not start"))
      else Entry(sessionId, ownerPeerId, conv).also { byId[sessionId] = it }.let { Result.success(it to true) }
    }
    dead?.let { d -> runCatching { d.conversation.stop() } }
    return result
  }

  /** Tear down the conversation for a session (mic unmuted, policy released, ASR cancelled). */
  fun stop(sessionId: String, reason: String): Boolean {
    val e = synchronized(lock) { byId.remove(sessionId) } ?: return false
    runCatching { e.conversation.stop() }
    android.util.Log.i("Conversations", "stopped conversation for $sessionId: $reason")
    return true
  }

  /** Remove [e] only if it is still the registered entry (a replacement may already be running), then tear it down. */
  private fun reap(e: Entry, reason: String) {
    val removed = synchronized(lock) { byId.remove(e.sessionId, e) }
    if (!removed) return
    runCatching { e.conversation.stop() }
    android.util.Log.i("Conversations", "reaped conversation for ${e.sessionId}: $reason")
  }

  /** Emergency stop / runtime shutdown. */
  fun stopAll(reason: String): Int = byId.keys.toList().count { stop(it, reason) }

  /** A peer was revoked: nothing it started may keep talking. */
  fun stopOwnedBy(peerId: String, reason: String): Int =
    byId.values.filter { it.ownerPeerId == peerId }.count { stop(it.sessionId, reason) }

  /** Every registered entry, running or not — for the driver-lease watchdog, which must also see dead loops. */
  fun entries(): List<Entry> = byId.values.toList()
}
