package dev.symbiosis.runtime.sessions

import dev.symbiosis.runtime.core.Ids
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * What one /ws/sessions/{id} socket sends, in order, without duplicates and without silent holes.
 *
 * The socket subscribes to live frames FIRST and only then reads the persisted backlog ([backlog]); live frames that
 * arrive meanwhile are de-duplicated by `seq` ([live]). Reading the backlog first, as the server used to, lost every
 * frame emitted between the read and the subscription. Live frames come from a drop-oldest flow, so a slow socket
 * can miss some: `seq` is contiguous per session, so a jump is a hole, and the hole is refilled from
 * session_messages. Only when the refill would exceed one page does the socket send a `gap` frame instead
 * (docs/protocol.md section 5.1); the client reconnects with `since_seq` to fetch it.
 *
 * Not thread-safe: one cursor per socket, driven from that socket's single writer coroutine.
 *
 * @param fetch persisted frames with seq > sinceSeq, ascending, at most `limit`, and whether more remain
 *   ([SessionManager.framesAfter]).
 * @param headSeq the highest seq assigned on the session so far ([SessionManager.headSeq]).
 */
class SessionFrameCursor(
  private val sessionId: String,
  sinceSeq: Long,
  private val fetch: (sinceSeq: Long, limit: Int) -> Pair<List<JsonObject>, Boolean>,
  private val headSeq: () -> Long,
  private val page: Int = PAGE,
  private val clock: () -> Long = Ids::now,
) {
  /** The highest seq this socket has accounted for: sent, or announced in a gap. */
  var last: Long = sinceSeq.coerceAtLeast(0); private set

  /** Persisted frames after the cursor (one page), then a `gap` covering the rest if more remain. */
  fun backlog(): List<JsonObject> {
    val out = ArrayList<JsonObject>()
    // A since_seq beyond the head (a client's cursor from another session, or garbage) would otherwise make this
    // socket skip every live frame until seq caught up with it.
    headSeq().let { head -> if (last > head) last = head }
    val (frames, more) = fetch(last, page)
    for (f in frames) { val s = seqOf(f) ?: continue; if (s <= last) continue; out += f; last = s }
    if (more) {
      val head = headSeq()
      if (head > last) { out += gap(last + 1, head); last = head }
    }
    return out
  }

  /** One live frame: nothing if already accounted for, else any refilled hole before it, then the frame. */
  fun live(f: SessionFrame): List<JsonObject> {
    if (f.sessionId != sessionId || f.seq <= last) return emptyList()
    val out = ArrayList<JsonObject>()
    if (f.seq > last + 1) {
      // Frames last+1 .. f.seq-1 never reached this collector live: refill them from the store.
      val (frames, more) = fetch(last, page)
      var reachedLive = false
      for (p in frames) {
        val s = seqOf(p) ?: continue
        if (s <= last) continue
        if (s >= f.seq) { reachedLive = true; break }
        out += p; last = s
      }
      if (!reachedLive && more && last < f.seq - 1) { out += gap(last + 1, f.seq - 1); last = f.seq - 1 }
    }
    out += f.toJson()
    last = f.seq
    return out
  }

  /**
   * A `gap` frame: frames [from]..[to] were not sent on this socket. Its `seq` is the last frame delivered before
   * the hole (from - 1), so a client that resumes from the highest seq it has seen re-fetches the hole.
   */
  private fun gap(from: Long, to: Long): JsonObject = buildJsonObject {
    put("session_id", sessionId); put("seq", from - 1); put("ts", clock()); put("correlation_id", JsonPrimitive(null as String?))
    put("type", "gap"); put("from_seq", from); put("to_seq", to)
    put("hint", "reconnect with since_seq=${from - 1} to fetch the missing frames")
  }

  private fun seqOf(f: JsonObject): Long? = f["seq"]?.jsonPrimitive?.longOrNull

  companion object { const val PAGE = 500 }
}
