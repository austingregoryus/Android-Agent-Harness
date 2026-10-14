package dev.symbiosis.runtime.events

import android.content.ContentValues
import dev.symbiosis.runtime.core.Ids
import dev.symbiosis.runtime.core.Json
import dev.symbiosis.runtime.storage.Db
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class Subscription(
  val subscription_id: String,
  val peer_id: String,
  val topic_glob: String,
  val filter: List<FilterClause>,
  val delivery: String,
  val created_at: Long,
  val created_at_event_id: Long,
  val expires_at: Long?,
  val ack_cursor: Long,
) {
  fun matches(ev: Event): Boolean = TopicGlob.matches(topic_glob, ev.topic) && filter.all { it.matches(ev.payload) }
  fun expired(now: Long = Ids.now()) = expires_at != null && now > expires_at
}

/** Replay response per docs/protocol.md §4.3. */
data class ReplayResult(val events: List<Event>, val nextSinceId: Long, val truncated: Boolean, val earliestAvailableId: Long)

/** Persisted subscriptions owned by peers. */
class SubscriptionStore(private val db: Db, private val broker: EventBroker) {
  private val filterSer = ListSerializer(FilterClause.serializer())

  /** Bumped on every create/delete so live fan-out caches can refresh cheaply. */
  @Volatile var version: Long = 0; private set

  /** Called after a replay (WS or MCP tool) so a paused live push can resume for that subscription. */
  var onReplayed: ((peerId: String, subscriptionId: String) -> Unit)? = null

  /**
   * Idempotent per peer: an identical (topic_glob, filter, delivery) subscription that is still alive is
   * returned (with its TTL extended if one is given) instead of creating a duplicate. Duplicates would
   * otherwise deliver every matching event once per copy and accumulate across reconnects.
   */
  fun create(peerId: String, topicGlob: String, filter: List<FilterClause>, delivery: String, ttlMs: Long?): Subscription {
    val now = Ids.now()
    val filterJson = Json.lenient.encodeToString(filterSer, filter)
    val mine = listFor(peerId)
    mine.firstOrNull { it.topic_glob == topicGlob && it.delivery == delivery && Json.lenient.encodeToString(filterSer, it.filter) == filterJson }?.let { existing ->
      val newExpiry = ttlMs?.let { now + it }
      if (newExpiry != null && (existing.expires_at == null || newExpiry > existing.expires_at)) {
        db.writableDatabase.execSQL("UPDATE subscriptions SET expires_at = ? WHERE subscription_id = ?", arrayOf<Any>(newExpiry, existing.subscription_id))
        return existing.copy(expires_at = newExpiry)
      }
      return existing
    }
    if (mine.size >= MAX_PER_PEER) throw IllegalStateException("subscription.limit:$MAX_PER_PEER")
    val sub = Subscription(
      Ids.subscription(), peerId, topicGlob, filter, delivery, now, broker.lastId(),
      ttlMs?.let { now + it }, broker.lastId(),
    )
    db.writableDatabase.insertOrThrow(
      "subscriptions", null,
      ContentValues().apply {
        put("subscription_id", sub.subscription_id); put("peer_id", peerId); put("topic_glob", topicGlob)
        put("filter_json", Json.lenient.encodeToString(filterSer, filter)); put("delivery", delivery)
        put("created_at", now); put("created_at_event_id", sub.created_at_event_id)
        put("expires_at", sub.expires_at); put("ack_cursor", sub.ack_cursor)
      },
    )
    version++
    return sub
  }

  fun delete(peerId: String, id: String): Boolean = (db.writableDatabase.delete("subscriptions", "subscription_id = ? AND peer_id = ?", arrayOf(id, peerId)) > 0).also { if (it) version++ }
  fun deleteAllFor(peerId: String) = db.writableDatabase.delete("subscriptions", "peer_id = ?", arrayOf(peerId)).also { version++ }

  fun get(peerId: String, id: String): Subscription? = db.readableDatabase.query("subscriptions", null, "subscription_id = ? AND peer_id = ?", arrayOf(id, peerId), null, null, null)
    .use { c -> if (c.moveToFirst()) row(c) else null }?.takeUnless { it.expired() }

  fun listFor(peerId: String): List<Subscription> {
    pruneExpired()
    return db.readableDatabase.query("subscriptions", null, "peer_id = ?", arrayOf(peerId), null, null, "created_at ASC").use { c -> buildList { while (c.moveToNext()) add(row(c)) } }
  }

  fun all(): List<Subscription> = db.readableDatabase.query("subscriptions", null, null, null, null, null, null).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }

  fun ack(peerId: String, id: String, throughId: Long) {
    db.writableDatabase.execSQL(
      "UPDATE subscriptions SET ack_cursor = MAX(ack_cursor, ?) WHERE subscription_id = ? AND peer_id = ?",
      arrayOf<Any>(throughId, id, peerId),
    )
  }

  /** [visible] lets the caller drop events the peer's scopes do not cover (topic-level gating). */
  fun replay(sub: Subscription, sinceId: Long, limit: Int, visible: (Event) -> Boolean = { true }): ReplayResult {
    val lim = limit.coerceIn(1, 1000)
    val from = maxOf(sinceId, sub.created_at_event_id)
    val earliest = broker.earliestId()
    val truncated = earliest > from + 1 && broker.count() > 0
    val out = ArrayList<Event>(lim)
    var cursor = from
    var more = true
    while (out.size < lim && more) {
      val batch = broker.after(cursor, 500)
      if (batch.isEmpty()) break
      more = batch.size > 500
      for (ev in batch.take(500)) {
        cursor = ev.id
        if (sub.matches(ev) && visible(ev)) { out.add(ev); if (out.size >= lim) break }
      }
    }
    // Never regress the cursor to the last matching event: the scan already covered everything up to `cursor`.
    val next = if (out.size >= lim) out.last().id else maxOf(from, cursor)
    onReplayed?.invoke(sub.peer_id, sub.subscription_id)
    return ReplayResult(out, next, truncated, earliest)
  }

  private fun pruneExpired() {
    val n = db.writableDatabase.delete("subscriptions", "expires_at IS NOT NULL AND expires_at < ?", arrayOf(Ids.now().toString()))
    if (n > 0) version++
  }

  companion object { const val MAX_PER_PEER = 32 }

  private fun row(c: android.database.Cursor) = Subscription(
    subscription_id = c.getString(c.getColumnIndexOrThrow("subscription_id")),
    peer_id = c.getString(c.getColumnIndexOrThrow("peer_id")),
    topic_glob = c.getString(c.getColumnIndexOrThrow("topic_glob")),
    filter = runCatching { Json.lenient.decodeFromString(filterSer, c.getString(c.getColumnIndexOrThrow("filter_json"))) }.getOrDefault(emptyList()),
    delivery = c.getString(c.getColumnIndexOrThrow("delivery")),
    created_at = c.getLong(c.getColumnIndexOrThrow("created_at")),
    created_at_event_id = c.getLong(c.getColumnIndexOrThrow("created_at_event_id")),
    expires_at = if (c.isNull(c.getColumnIndexOrThrow("expires_at"))) null else c.getLong(c.getColumnIndexOrThrow("expires_at")),
    ack_cursor = c.getLong(c.getColumnIndexOrThrow("ack_cursor")),
  )
}
