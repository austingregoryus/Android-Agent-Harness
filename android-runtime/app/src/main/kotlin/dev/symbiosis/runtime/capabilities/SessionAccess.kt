package dev.symbiosis.runtime.capabilities

import dev.symbiosis.runtime.sessions.Session
import dev.symbiosis.runtime.sessions.SessionManager

/**
 * Who may act on a call session. Same rule `telephony.end_call` has always used: the peer that placed the
 * call, or the admin. Everything that speaks into a call now goes through this, so a second agent — or a
 * stale driver from a previous run — is refused by the phone itself rather than by client-side etiquette.
 *
 * "Not yours" and "does not exist" are deliberately the same answer (`session.unknown`): a peer must not be
 * able to probe for calls it does not own.
 */
object SessionAccess {

  sealed class Access {
    data class Ok(val session: Session) : Access()
    data class Denied(val result: CapabilityResult) : Access()
  }

  fun owns(s: Session, ctx: CallContext): Boolean =
    ctx.principal?.isAdmin == true || (s.peerId != null && s.peerId == ctx.peerId)

  /**
   * The call session the phone is tracking as live right now (`CallSessions.currentSessionId`), installed by the
   * Runtime. A non-terminal row alone is not proof of a live call: a crash or reboot mid-call used to leave the
   * row ACTIVE forever, and its owner could then speak/play/converse into whatever call came next — including
   * the owner's own personal calls, which have no session at all. The default (nothing installed) is "no live
   * call", so a missing hook refuses rather than permits.
   */
  @Volatile var liveCallId: () -> String? = { null }

  /** Resolve a call session the caller owns, that is not terminal, and that is the call actually live now. */
  fun ownedActiveCall(sessions: SessionManager, sessionId: String?, ctx: CallContext): Access {
    val id = sessionId?.takeIf { it.isNotBlank() }
      ?: return Access.Denied(CapabilityResult.fail("invalid_params", "session_id is required"))
    val s = sessions.get(id)
    if (s == null || s.kind != "call" || !owns(s, ctx)) {
      return Access.Denied(CapabilityResult.fail("session.unknown", id))
    }
    if (s.state.terminal) {
      return Access.Denied(CapabilityResult.fail("session.not_active", "call $id is ${s.state.name}"))
    }
    // Same code as terminal on purpose: to the caller a session the phone no longer tracks IS over, and
    // `converse stop` relies on session.not_active to still allow cleanup.
    if (liveCallId() != id) {
      return Access.Denied(CapabilityResult.fail("session.not_active", "call $id is not the live call"))
    }
    return Access.Ok(s)
  }
}
