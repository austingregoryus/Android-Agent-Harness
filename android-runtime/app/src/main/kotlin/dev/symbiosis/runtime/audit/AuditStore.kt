package dev.symbiosis.runtime.audit

import android.content.ContentValues
import dev.symbiosis.runtime.core.Ids
import dev.symbiosis.runtime.core.Json
import dev.symbiosis.runtime.storage.Db
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One row of the append-only audit log. */
@Serializable
data class AuditEntry(
  val id: Long = 0,
  val ts: Long,
  val correlationId: String?,
  val peerId: String?,
  val kind: String,
  val capability: String? = null,
  val actionClass: String? = null,
  val args: JsonElement? = null,
  val result: JsonElement? = null,
  val success: Boolean? = null,
  val errorCode: String? = null,
  val durationMs: Long? = null,
  val idempotencyKey: String? = null,
  val prevHash: String = "",
  val entryHash: String = "",
)

object AuditKind {
  const val TOOL_INTENT = "tool.intent"
  const val TOOL_OUTCOME = "tool.outcome"
  const val POLICY_DECISION = "policy.decision"
  const val APPROVAL = "approval"
  const val EVENT_EMITTED = "event.emitted"
  const val SESSION_TRANSITION = "session.transition"
  const val ADMIN = "admin"
  /** A saved workflow run: one row when it starts (success null), one when it ends; its steps have their own rows. */
  const val WORKFLOW_RUN = "workflow.run"
}

/** A retention checkpoint: rows before [firstKeptId] were pruned, and the chain resumes from [firstKeptPrevHash]. */
data class AuditAnchor(val id: Long, val createdAt: Long, val firstKeptId: Long, val firstKeptPrevHash: String, val prunedCount: Long)

/**
 * Append-only audit store with a SHA-256 hash chain. The table has engine-level triggers that abort UPDATE, and
 * DELETE of anything at or after the latest retention anchor; this class only INSERTs, except [prune].
 *
 * Retention without breaking verification: [prune] first records an anchor (the id of the oldest row it keeps and
 * that row's stored prev_hash) in an equally append-only table, then deletes the rows before it, in one
 * transaction. [verifyChain] starts from the latest anchor's hash instead of the seed, so the kept suffix still
 * verifies end to end, and the prune itself is an entry in the chain (`admin` / `audit.prune`).
 */
class AuditStore(private val db: Db, private val seed: String) {
  private val lock = Any()
  @Volatile private var lastHash: String? = null

  fun append(entry: AuditEntry): AuditEntry = synchronized(lock) {
    val prev = lastHash ?: readLastHash()
    val canonical = canonicalOf(entry, prev)
    val hash = Ids.sha256Hex(prev + canonical)
    val cv = ContentValues().apply {
      put("ts", entry.ts)
      put("correlation_id", entry.correlationId)
      put("peer_id", entry.peerId)
      put("kind", entry.kind)
      put("capability", entry.capability)
      put("action_class", entry.actionClass)
      put("args_json", entry.args?.let { Json.lenient.encodeToString(JsonElement.serializer(), sanitizeArgs(it)) })
      put("result_json", entry.result?.let { truncate(Json.lenient.encodeToString(JsonElement.serializer(), it)) })
      entry.success?.let { put("success", if (it) 1 else 0) }
      put("error_code", entry.errorCode)
      entry.durationMs?.let { put("duration_ms", it) }
      put("idempotency_key", entry.idempotencyKey)
      put("prev_hash", prev)
      put("entry_hash", hash)
    }
    val id = db.writableDatabase.insertOrThrow("audit_entries", null, cv)
    lastHash = hash
    entry.copy(id = id, prevHash = prev, entryHash = hash)
  }

  /**
   * The newest [limit] entries, optionally only [peerId]'s, for display (`phone://audit/recent`).
   *
   * Safe on any history: the peer filter is in SQL (it used to take the last 100 rows of everyone and filter
   * afterwards, so a peer saw fewer than 100 of its own, or none), the limit is clamped, and an args/result
   * column longer than [READ_MAX_CHARS] is never pulled into the cursor. Rows written before args were compacted
   * can hold a whole base64 clip (play_audio): 100 of those overflow the 2 MB CursorWindow and the read threw.
   * Such a value comes back as `{truncated, original_length}`.
   */
  fun recent(limit: Int = 100, peerId: String? = null): List<AuditEntry> = db.readableDatabase.rawQuery(
    """SELECT id, ts, correlation_id, peer_id, kind, capability, action_class,
         CASE WHEN length(args_json) > $READ_MAX_CHARS THEN NULL ELSE args_json END AS args_json, length(args_json) AS args_len,
         CASE WHEN length(result_json) > $READ_MAX_CHARS THEN NULL ELSE result_json END AS result_json, length(result_json) AS result_len,
         success, error_code, duration_ms, idempotency_key, prev_hash, entry_hash
       FROM audit_entries ${if (peerId != null) "WHERE peer_id = ?" else ""} ORDER BY id DESC LIMIT ${limit.coerceIn(1, 500)}""",
    if (peerId != null) arrayOf(peerId) else null,
  ).use { c ->
    fun column(name: String, lenName: String): JsonElement? {
      val text = c.getString(c.getColumnIndexOrThrow(name))
      if (text != null) return runCatching { Json.lenient.parseToJsonElement(text) }.getOrNull()
      val lenIdx = c.getColumnIndexOrThrow(lenName)
      return if (c.isNull(lenIdx)) null else buildJsonObject { put("truncated", true); put("original_length", c.getLong(lenIdx)) }
    }
    buildList {
      while (c.moveToNext()) add(
        AuditEntry(
          id = c.getLong(c.getColumnIndexOrThrow("id")),
          ts = c.getLong(c.getColumnIndexOrThrow("ts")),
          correlationId = c.getString(c.getColumnIndexOrThrow("correlation_id")),
          peerId = c.getString(c.getColumnIndexOrThrow("peer_id")),
          kind = c.getString(c.getColumnIndexOrThrow("kind")),
          capability = c.getString(c.getColumnIndexOrThrow("capability")),
          actionClass = c.getString(c.getColumnIndexOrThrow("action_class")),
          args = column("args_json", "args_len"),
          result = column("result_json", "result_len"),
          success = if (c.isNull(c.getColumnIndexOrThrow("success"))) null else c.getInt(c.getColumnIndexOrThrow("success")) == 1,
          errorCode = c.getString(c.getColumnIndexOrThrow("error_code")),
          durationMs = if (c.isNull(c.getColumnIndexOrThrow("duration_ms"))) null else c.getLong(c.getColumnIndexOrThrow("duration_ms")),
          idempotencyKey = c.getString(c.getColumnIndexOrThrow("idempotency_key")),
          prevHash = c.getString(c.getColumnIndexOrThrow("prev_hash")),
          entryHash = c.getString(c.getColumnIndexOrThrow("entry_hash")),
        ),
      )
    }
  }

  /** A stored successful outcome for an idempotency key, with what it was the outcome OF. */
  data class StoredOutcome(
    val auditId: Long,
    val capability: String?,
    /** [argsDigest] of the arguments that produced it; null for rows written before replay was bound to args. */
    val argsDigest: String?,
    val result: JsonElement?,
    /** The result exceeded the 8 KB audit cap and was stored cut down: it is not the real result. */
    val truncated: Boolean,
  )

  /**
   * Returns the stored *successful* outcome for an idempotency key owned by [peerId]. Failures are never
   * replayed (a retry after a transient error must be allowed to run), and keys are peer-scoped so one
   * peer cannot read another's results by guessing a key. The caller must check [StoredOutcome.capability] and
   * [StoredOutcome.argsDigest] against the call in hand: a key alone does not say which call it was for.
   */
  fun findOutcome(idempotencyKey: String, peerId: String?): StoredOutcome? = db.readableDatabase.query(
    // Replay receipts are successful TOOL_OUTCOME rows too, but contain no result or argument digest.
    // They must never shadow the actual execution when the same key is retried for a third time.
    "audit_entries", arrayOf("id", "capability", "args_json", "result_json"), "idempotency_key = ? AND kind = ? AND success = 1 AND result_json IS NOT NULL AND peer_id IS ?",
    arrayOf(idempotencyKey, AuditKind.TOOL_OUTCOME, peerId), null, null, "id DESC", "1",
  ).use { c ->
    if (!c.moveToFirst()) return@use null
    val args = c.getString(2)?.let { runCatching { Json.lenient.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    val result = c.getString(3)?.let { runCatching { Json.lenient.parseToJsonElement(it) }.getOrNull() }
    val r = result as? JsonObject
    // truncate() replaces an oversized result with {truncated, original_length, text}; a real envelope has content.
    val truncated = r != null && r["truncated"] != null && r["original_length"] != null && r["content"] == null
    StoredOutcome(c.getLong(0), c.getString(1), (args?.get(ARGS_DIGEST) as? JsonPrimitive)?.contentOrNull, result, truncated)
  }

  /**
   * A keyed digest of a call's arguments, stored beside an idempotent outcome so a replay can be bound to the exact
   * call. Keyed with the chain seed (not a bare hash) so the audit log does not carry a guessable hash of argument
   * values such as a message body.
   */
  fun argsDigest(args: JsonElement): String = Ids.sha256Hex(seed + "|args|" + Json.canonical(args))

  fun count(): Long = db.readableDatabase.compileStatement("SELECT COUNT(*) FROM audit_entries").use { it.simpleQueryForLong() }

  /** The latest retention anchor, or null if the log has never been pruned. */
  fun anchor(): AuditAnchor? = db.readableDatabase.query(
    "audit_anchors", null, null, null, null, null, "id DESC", "1",
  ).use { c ->
    if (!c.moveToFirst()) null else AuditAnchor(
      id = c.getLong(c.getColumnIndexOrThrow("id")),
      createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
      firstKeptId = c.getLong(c.getColumnIndexOrThrow("first_kept_id")),
      firstKeptPrevHash = c.getString(c.getColumnIndexOrThrow("first_kept_prev_hash")),
      prunedCount = c.getLong(c.getColumnIndexOrThrow("pruned_count")),
    )
  }

  /**
   * Walks the chain; returns the first broken id or null when intact. Starts at the seed, or — once the log has
   * been pruned — at the latest anchor: the first kept row must be exactly the anchored id and must carry the
   * anchored prev_hash, and everything after it must chain as before.
   */
  fun verifyChain(): Long? {
    val anchor = anchor()
    var prev = anchor?.firstKeptPrevHash ?: seedHash()
    var first = true
    db.readableDatabase.query("audit_entries", null, "id >= ?", arrayOf((anchor?.firstKeptId ?: 0L).toString()), null, null, "id ASC").use { c ->
      while (c.moveToNext()) {
        val id = c.getLong(c.getColumnIndexOrThrow("id"))
        // The anchored head itself was removed (or never existed): the suffix no longer starts where the anchor says.
        if (first && anchor != null && id != anchor.firstKeptId) return id
        first = false
        val storedPrev = c.getString(c.getColumnIndexOrThrow("prev_hash"))
        val storedHash = c.getString(c.getColumnIndexOrThrow("entry_hash"))
        if (storedPrev != prev) return id
        val entry = AuditEntry(
          ts = c.getLong(c.getColumnIndexOrThrow("ts")),
          correlationId = c.getString(c.getColumnIndexOrThrow("correlation_id")),
          peerId = c.getString(c.getColumnIndexOrThrow("peer_id")),
          kind = c.getString(c.getColumnIndexOrThrow("kind")),
          capability = c.getString(c.getColumnIndexOrThrow("capability")),
          actionClass = c.getString(c.getColumnIndexOrThrow("action_class")),
          args = c.getString(c.getColumnIndexOrThrow("args_json"))?.let { runCatching { Json.lenient.parseToJsonElement(it) }.getOrNull() ?: return id },
          result = c.getString(c.getColumnIndexOrThrow("result_json"))?.let { runCatching { Json.lenient.parseToJsonElement(it) }.getOrNull() ?: return id },
          success = if (c.isNull(c.getColumnIndexOrThrow("success"))) null else c.getInt(c.getColumnIndexOrThrow("success")) == 1,
          errorCode = c.getString(c.getColumnIndexOrThrow("error_code")),
          durationMs = if (c.isNull(c.getColumnIndexOrThrow("duration_ms"))) null else c.getLong(c.getColumnIndexOrThrow("duration_ms")),
          idempotencyKey = c.getString(c.getColumnIndexOrThrow("idempotency_key")),
        )
        val expect = Ids.sha256Hex(prev + canonicalOf(entry, prev, alreadyStored = true))
        if (expect != storedHash) return id
        prev = storedHash
      }
    }
    return null
  }

  /**
   * Retention, run from the hourly maintenance. Keeps rows younger than [maxAgeMs] and at most [maxRows] of them,
   * but never fewer than [minKeep] (an idle phone must not wipe its whole history after a quiet month). Returns
   * the number of rows deleted. Records an anchor first and deletes in the same transaction, so verification of
   * what remains keeps working; then appends an `audit.prune` entry so the prune is itself on the record.
   */
  fun prune(now: Long = Ids.now(), maxAgeMs: Long = RETENTION_MS, maxRows: Long = RETENTION_MAX_ROWS, minKeep: Long = RETENTION_MIN_KEEP): Int {
    val pruned = synchronized(lock) {
      val w = db.writableDatabase
      val lastId = w.compileStatement("SELECT COALESCE(MAX(id), 0) FROM audit_entries").use { it.simpleQueryForLong() }
      if (lastId == 0L) return 0
      val oldestId = w.compileStatement("SELECT COALESCE(MIN(id), 0) FROM audit_entries").use { it.simpleQueryForLong() }
      val byAge = w.compileStatement("SELECT COALESCE(MIN(id), ?) FROM audit_entries WHERE ts >= ?").use { st ->
        st.bindLong(1, lastId + 1); st.bindLong(2, now - maxAgeMs); st.simpleQueryForLong()
      }
      val keepFrom = minOf(maxOf(byAge, lastId - maxRows + 1), lastId - minKeep + 1)
      if (keepFrom <= oldestId) return 0
      // The anchor must be a row that exists: ids have gaps if an insert ever failed.
      val (headId, headPrev) = w.rawQuery("SELECT id, prev_hash FROM audit_entries WHERE id >= ? ORDER BY id ASC LIMIT 1", arrayOf(keepFrom.toString()))
        .use { c -> if (c.moveToFirst()) c.getLong(0) to c.getString(1) else return 0 }
      w.beginTransaction()
      try {
        val count = w.compileStatement("SELECT COUNT(*) FROM audit_entries WHERE id < ?").use { it.bindLong(1, headId); it.simpleQueryForLong() }
        w.insertOrThrow(
          "audit_anchors", null,
          ContentValues().apply { put("created_at", now); put("first_kept_id", headId); put("first_kept_prev_hash", headPrev); put("pruned_count", count) },
        )
        val deleted = w.delete("audit_entries", "id < ?", arrayOf(headId.toString()))
        w.setTransactionSuccessful()
        Triple(deleted, headId, headPrev)
      } finally { w.endTransaction() }
    }
    append(
      AuditEntry(
        ts = now, correlationId = null, peerId = null, kind = AuditKind.ADMIN, capability = "audit.prune", success = true,
        args = buildJsonObject { put("deleted", pruned.first); put("first_kept_id", pruned.second); put("first_kept_prev_hash", pruned.third); put("max_age_ms", maxAgeMs); put("max_rows", maxRows) },
      ),
    )
    return pruned.first
  }

  private fun readLastHash(): String = db.readableDatabase.query(
    "audit_entries", arrayOf("entry_hash"), null, null, null, null, "id DESC", "1",
  ).use { c -> if (c.moveToFirst()) c.getString(0) else seedHash() }

  private fun seedHash() = Ids.sha256Hex("symbiosis-audit-seed:$seed")

  /** Canonical JSON of the hashed fields. Args are hashed post-redaction and results post-truncation so verify() can recompute. */
  private fun canonicalOf(e: AuditEntry, prev: String, alreadyStored: Boolean = false): String {
    val args = e.args?.let { if (alreadyStored) it else sanitizeArgs(it) }
    val result = e.result?.let { if (alreadyStored) it else Json.lenient.parseToJsonElement(truncate(Json.lenient.encodeToString(JsonElement.serializer(), it))) }
    val obj = buildJsonObject {
      put("ts", e.ts)
      put("correlation_id", e.correlationId)
      put("peer_id", e.peerId)
      put("kind", e.kind)
      put("capability", e.capability)
      put("action_class", e.actionClass)
      put("args", args ?: JsonNull)
      put("result", result ?: JsonNull)
      put("success", e.success)
      put("error_code", e.errorCode)
      put("duration_ms", e.durationMs)
      put("idempotency_key", e.idempotencyKey)
      put("prev_hash", prev)
    }
    return Json.canonical(obj)
  }

  /** Over-size results are stored as a valid JSON object so the stored text always parses and hashes identically on verify. */
  private fun truncate(s: String): String = if (s.length <= MAX_RESULT) s else
    Json.lenient.encodeToString(JsonElement.serializer(), buildJsonObject { put("truncated", true); put("original_length", s.length); put("text", s.substring(0, MAX_RESULT)) })

  companion object {
    /** Key in a tool.outcome row's args holding [argsDigest]. */
    const val ARGS_DIGEST = "args_digest"
    private const val MAX_RESULT = 8 * 1024
    /** Longest args/result text [recent] will load; anything longer is summarised, never read into the cursor. */
    const val READ_MAX_CHARS = 16 * 1024
    /**
     * Longest string argument stored verbatim. Above it the value becomes `{len, sha256}`: enough to prove what
     * was sent without keeping it (a play_audio clip is hundreds of KB of base64 per utterance, and it used to be
     * stored whole on every call). Well above any SMS or spoken sentence, which operators do need to read.
     */
    const val MAX_ARG_STRING = 2 * 1024
    const val RETENTION_MS = 30L * 24 * 3600 * 1000
    const val RETENTION_MAX_ROWS = 200_000L
    const val RETENTION_MIN_KEEP = 1_000L
    private val SECRET_KEY = Regex("(?i)(token|secret|password|api[_-]?key|authorization)")

    /** What is stored (and hashed) for tool arguments: secrets redacted, oversized strings compacted. */
    fun sanitizeArgs(element: JsonElement): JsonElement = compactStrings(redact(element))

    fun compactStrings(element: JsonElement): JsonElement = when (element) {
      is JsonObject -> JsonObject(element.mapValues { (_, v) -> compactStrings(v) })
      is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(element.map { compactStrings(it) })
      is JsonPrimitive -> if (element.isString && element.content.length > MAX_ARG_STRING) {
        buildJsonObject { put("len", element.content.length); put("sha256", Ids.sha256Hex(element.content)) }
      } else element
    }

    /** Replace secret-looking keys; keep message bodies (operators need them). */
    fun redact(element: JsonElement): JsonElement = when (element) {
      is JsonObject -> JsonObject(element.entries.associate { (k, v) -> k to if (SECRET_KEY.containsMatchIn(k)) JsonPrimitive("[redacted]") else redact(v) })
      is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(element.map { redact(it) })
      else -> element
    }
  }
}
