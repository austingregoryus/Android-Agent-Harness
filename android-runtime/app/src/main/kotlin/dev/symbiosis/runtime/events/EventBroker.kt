package dev.symbiosis.runtime.events

import android.content.ContentValues
import dev.symbiosis.runtime.core.Ids
import dev.symbiosis.runtime.core.Json
import dev.symbiosis.runtime.storage.Db
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonObject

/**
 * The single event journal for the device plus an in-process fan-out. Every emitted event is
 * persisted first (so replay is always possible), then published to live subscribers.
 */
class EventBroker(private val db: Db, private val deviceId: String) {
  /**
   * Live fan-out. DROP_OLDEST, not the default SUSPEND: with SUSPEND, one collector falling 1024 events behind made
   * `tryEmit` fail, and the event then reached NO live subscriber — WebSocket pushes and events.wait went silent for
   * everyone because of one slow consumer. Now only the slow collector loses the oldest buffered events. Nothing is
   * lost from the journal (every event is persisted before it is published), and the one collector that must not
   * miss silently — the WebSocket fan-out in Server.kt — detects the hole by event id and sends `gap` frames, so
   * clients recover with `replay` exactly as they do for a slow socket. That relies on ids being published in id
   * order, which [emit] guarantees by inserting and publishing under one lock.
   */
  private val _live = MutableSharedFlow<Event>(extraBufferCapacity = 1024, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
  val live: SharedFlow<Event> = _live
  private val publishLock = Any()

  /** Hook for the audit log; set by the runtime. */
  var onEmitted: ((Event) -> Unit)? = null

  fun emit(topic: String, payload: Map<String, Any?>, correlationId: String? = null): Event =
    emit(topic, Json.toJson(payload) as JsonObject, correlationId)

  fun emit(topic: String, payload: JsonObject, correlationId: String? = null): Event {
    val ev = synchronized(publishLock) {
      val ts = Ids.now()
      val id = db.writableDatabase.insertOrThrow(
        "events", null,
        ContentValues().apply {
          put("topic", topic); put("ts", ts); put("correlation_id", correlationId)
          put("payload_json", Json.lenient.encodeToString(JsonObject.serializer(), payload))
        },
      )
      Event(id, topic, ts, correlationId, payload, deviceId).also { _live.tryEmit(it) } // never fails with DROP_OLDEST
    }
    onEmitted?.invoke(ev)
    return ev
  }

  // Each statement is closed after use: these run per subscribe/replay/status call and leaked one native statement each.
  fun lastId(): Long = scalar("SELECT COALESCE(MAX(id), 0) FROM events")
  fun earliestId(): Long = scalar("SELECT COALESCE(MIN(id), 0) FROM events")
  fun count(): Long = scalar("SELECT COUNT(*) FROM events")
  private fun scalar(sql: String): Long = db.readableDatabase.compileStatement(sql).use { it.simpleQueryForLong() }

  /** Events with id > sinceId, ascending, up to limit+1 so the caller can detect "more". */
  fun after(sinceId: Long, limit: Int): List<Event> = db.readableDatabase.query(
    "events", null, "id > ?", arrayOf(sinceId.toString()), null, null, "id ASC", (limit + 1).toString(),
  ).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }

  fun get(id: Long): Event? = db.readableDatabase.query("events", null, "id = ?", arrayOf(id.toString()), null, null, null).use { c -> if (c.moveToFirst()) row(c) else null }

  fun recent(limit: Int = 50): List<Event> = db.readableDatabase.query("events", null, null, null, null, null, "id DESC", limit.toString())
    .use { c -> buildList { while (c.moveToNext()) add(row(c)) } }

  /** Retention: keep the newer of [maxRows] rows or [maxAgeMs]. */
  fun prune(maxRows: Int = 10_000, maxAgeMs: Long = 7L * 24 * 3600 * 1000) {
    val w = db.writableDatabase
    val cutoffTs = Ids.now() - maxAgeMs
    val cutoffId = w.compileStatement("SELECT COALESCE((SELECT id FROM events ORDER BY id DESC LIMIT 1 OFFSET ?), 0)").use { it.bindLong(1, maxRows.toLong()); it.simpleQueryForLong() }
    w.delete("events", "ts < ? AND id <= ?", arrayOf(cutoffTs.toString(), cutoffId.toString()))
  }

  private fun row(c: android.database.Cursor) = Event(
    id = c.getLong(c.getColumnIndexOrThrow("id")),
    topic = c.getString(c.getColumnIndexOrThrow("topic")),
    ts = c.getLong(c.getColumnIndexOrThrow("ts")),
    correlation_id = c.getString(c.getColumnIndexOrThrow("correlation_id")),
    payload = Json.lenient.parseToJsonElement(c.getString(c.getColumnIndexOrThrow("payload_json"))) as JsonObject,
    device_id = deviceId,
  )
}
