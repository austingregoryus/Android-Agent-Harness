package dev.symbiosis.runtime.sessions

import android.content.ContentValues
import dev.symbiosis.runtime.core.Ids
import dev.symbiosis.runtime.core.Json
import dev.symbiosis.runtime.storage.Db
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** Session states per docs/protocol.md §5. */
enum class SessionState(val terminal: Boolean = false) {
  CREATED, DIALING, RINGING, CONNECTED, ACTIVE, HOLDING, ENDING,
  ENDED(true), BUSY(true), NO_ANSWER(true), FAILED(true), CANCELLED(true), PERMISSION_DENIED(true),
}

data class Session(
  val sessionId: String,
  val kind: String,
  val state: SessionState,
  val peerId: String?,
  val correlationId: String?,
  val createdAt: Long,
  val updatedAt: Long,
  val endedAt: Long?,
  val meta: JsonObject,
  val summary: JsonObject?,
)

/** A frame on the session channel. `seq` is monotonic per session and assigned here. */
data class SessionFrame(val sessionId: String, val seq: Long, val ts: Long, val correlationId: String?, val type: String, val body: JsonObject) {
  fun toJson(): JsonObject = buildJsonObject {
    put("session_id", sessionId); put("seq", seq); put("ts", ts); put("correlation_id", correlationId); put("type", type)
    for ((k, v) in body) put(k, v)
  }
}

/**
 * Owns long-lived sessions: persistence, state transitions, per-session frame sequencing, and a
 * live fan-out for the `/ws/sessions/{id}` channel. Call-specific behaviour lives in [CallSession].
 */
class SessionManager(private val db: Db) {
  private val seqs = ConcurrentHashMap<String, Long>()
  private val _frames = MutableSharedFlow<SessionFrame>(extraBufferCapacity = 512)
  val frames: SharedFlow<SessionFrame> = _frames

  /** Hooks set by the runtime: audit + `session.state` events. */
  var onTransition: ((Session, SessionState, SessionState) -> Unit)? = null

  fun create(kind: String, peerId: String?, correlationId: String?, meta: Map<String, Any?>): Session {
    val now = Ids.now()
    val s = Session(Ids.session(kind), kind, SessionState.CREATED, peerId, correlationId, now, now, null, Json.toJson(meta) as JsonObject, null)
    db.writableDatabase.insertOrThrow(
      "sessions", null,
      ContentValues().apply {
        put("session_id", s.sessionId); put("kind", kind); put("state", s.state.name); put("peer_id", peerId)
        put("correlation_id", correlationId); put("created_at", now); put("updated_at", now)
        put("meta_json", Json.lenient.encodeToString(JsonObject.serializer(), s.meta))
      },
    )
    seqs[s.sessionId] = 0
    emitFrame(s.sessionId, correlationId, "session.state", buildJsonObject { put("state", s.state.name); put("previous_state", JsonPrimitive(null as String?)); put("kind", kind) })
    return s
  }

  fun get(id: String): Session? = db.readableDatabase.query("sessions", null, "session_id = ?", arrayOf(id), null, null, null).use { c -> if (c.moveToFirst()) row(c) else null }

  fun active(): List<Session> = db.readableDatabase.query("sessions", null, "ended_at IS NULL", null, null, null, "created_at DESC").use { c ->
    buildList { while (c.moveToNext()) add(row(c)) }
  }

  fun recent(limit: Int = 20): List<Session> = db.readableDatabase.query("sessions", null, null, null, null, null, "created_at DESC", limit.toString()).use { c ->
    buildList { while (c.moveToNext()) add(row(c)) }
  }

  /** Durable startup completion; explicitly stopping conversation mode must not restart its startup timer. */
  fun markDriverAttached(id: String) {
    val s = get(id) ?: return
    val meta = JsonObject(s.meta + ("driver_attached" to JsonPrimitive(true)))
    db.writableDatabase.update("sessions", ContentValues().apply {
      put("meta_json", Json.lenient.encodeToString(JsonObject.serializer(), meta))
    }, "session_id = ?", arrayOf(id))
  }

  /** Applies a transition if it is a change; terminal states also set ended_at. Returns the new session or null if unknown. */
  fun transition(id: String, to: SessionState, extra: Map<String, Any?> = emptyMap()): Session? {
    val s = get(id) ?: return null
    if (s.state == to) return s
    if (s.state.terminal) return s
    val now = Ids.now()
    // Conditional update: only the caller that observed `s.state` wins; a concurrent transition is a no-op here.
    val changed = db.writableDatabase.compileStatement("UPDATE sessions SET state = ?, updated_at = ?, ended_at = COALESCE(ended_at, ?) WHERE session_id = ? AND state = ?")
      .use { st -> st.bindString(1, to.name); st.bindLong(2, now); if (to.terminal) st.bindLong(3, now) else st.bindNull(3); st.bindString(4, id); st.bindString(5, s.state.name); st.executeUpdateDelete() }
    if (changed != 1) return get(id)
    val updated = s.copy(state = to, updatedAt = now, endedAt = if (to.terminal) now else null)
    emitFrame(id, s.correlationId, "session.state", buildJsonObject { put("state", to.name); put("previous_state", s.state.name); put("kind", s.kind); for ((k, v) in extra) put(k, Json.toJson(v)) })
    onTransition?.invoke(updated, s.state, to)
    return updated
  }

  fun setSummary(id: String, summary: Map<String, Any?>) {
    val obj = Json.toJson(summary) as JsonObject
    db.writableDatabase.update("sessions", ContentValues().apply { put("summary_json", Json.lenient.encodeToString(JsonObject.serializer(), obj)) }, "session_id = ?", arrayOf(id))
    val s = get(id)
    emitFrame(id, s?.correlationId, "session.summary", obj)
  }

  /** Persist and publish a frame with the next sequence number. */
  fun emitFrame(sessionId: String, correlationId: String?, type: String, body: JsonObject): SessionFrame = synchronized(seqs) {
    // Allocation, persistence and publication happen under one lock so observers never see seq go backwards.
    val cur = seqs[sessionId] ?: lastSeq(sessionId)
    val seq = cur + 1
    seqs[sessionId] = seq
    val f = SessionFrame(sessionId, seq, Ids.now(), correlationId, type, body)
    db.writableDatabase.insertOrThrow(
      "session_messages", null,
      ContentValues().apply {
        put("session_id", sessionId); put("seq", seq); put("ts", f.ts); put("type", type)
        put("frame_json", Json.lenient.encodeToString(JsonObject.serializer(), f.toJson()))
      },
    )
    if (!_frames.tryEmit(f)) android.util.Log.w("SessionManager", "session frame buffer overflow: dropped live seq=$seq for $sessionId (persisted; clients replay with since_seq)")
    f
  }

  /** Frames after [sinceSeq]; `second` is true when more remain beyond [limit] (the caller should send a gap). */
  fun framesAfter(sessionId: String, sinceSeq: Long, limit: Int = 500): Pair<List<JsonObject>, Boolean> = db.readableDatabase.query(
    "session_messages", arrayOf("frame_json"), "session_id = ? AND seq > ?", arrayOf(sessionId, sinceSeq.toString()), null, null, "seq ASC", (limit + 1).toString(),
  ).use { c ->
    val all = buildList { while (c.moveToNext()) add(Json.lenient.parseToJsonElement(c.getString(0)) as JsonObject) }
    all.take(limit) to (all.size > limit)
  }

  /** Retention: drop frames of sessions that ended more than [olderThanMs] ago. */
  fun pruneFrames(olderThanMs: Long = 7L * 24 * 3600 * 1000) {
    db.writableDatabase.execSQL("DELETE FROM session_messages WHERE session_id IN (SELECT session_id FROM sessions WHERE ended_at IS NOT NULL AND ended_at < ?)", arrayOf<Any>(Ids.now() - olderThanMs))
  }

  private fun lastSeq(sessionId: String): Long = db.readableDatabase.compileStatement("SELECT COALESCE(MAX(seq), 0) FROM session_messages WHERE session_id = ?")
    .use { it.bindString(1, sessionId); it.simpleQueryForLong() }

  fun toJson(s: Session): JsonObject = buildJsonObject {
    put("session_id", s.sessionId); put("kind", s.kind); put("state", s.state.name); put("peer_id", s.peerId)
    put("correlation_id", s.correlationId); put("created_at", s.createdAt); put("updated_at", s.updatedAt); put("ended_at", s.endedAt)
    put("meta", s.meta); put("summary", s.summary ?: JsonPrimitive(null as String?))
  }

  private fun row(c: android.database.Cursor) = Session(
    sessionId = c.getString(c.getColumnIndexOrThrow("session_id")),
    kind = c.getString(c.getColumnIndexOrThrow("kind")),
    state = SessionState.valueOf(c.getString(c.getColumnIndexOrThrow("state"))),
    peerId = c.getString(c.getColumnIndexOrThrow("peer_id")),
    correlationId = c.getString(c.getColumnIndexOrThrow("correlation_id")),
    createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
    updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
    endedAt = if (c.isNull(c.getColumnIndexOrThrow("ended_at"))) null else c.getLong(c.getColumnIndexOrThrow("ended_at")),
    meta = Json.lenient.parseToJsonElement(c.getString(c.getColumnIndexOrThrow("meta_json"))) as JsonObject,
    summary = c.getString(c.getColumnIndexOrThrow("summary_json"))?.let { Json.lenient.parseToJsonElement(it) as? JsonObject },
  )
}
