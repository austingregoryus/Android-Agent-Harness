package dev.symbiosis.runtime.automation

import android.content.ContentValues
import android.database.Cursor
import dev.symbiosis.runtime.core.Ids
import dev.symbiosis.runtime.core.Json
import dev.symbiosis.runtime.storage.Db
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A saved workflow row. [steps] is stored exactly as saved and re-validated (Batches.parse) on every run. */
data class Workflow(
  val id: String,
  val owner: String,
  val name: String,
  val description: String?,
  val steps: JsonArray,
  val inputsSchema: JsonObject?,
  val trigger: JsonObject?,
  val timeoutMs: Long,
  val enabled: Boolean,
  val disabledReason: String?,
  val createdAt: Long,
  val updatedAt: Long,
  val lastRunAt: Long?,
  val lastResult: JsonObject?,
  val runCount: Long,
  val nextRunAt: Long?,
) {
  /** Parsed trigger, or null (manual-only workflow, or a stored trigger this build can no longer read). */
  val triggerSpec: TriggerSpec? by lazy { trigger?.let { runCatching { Triggers.parse(it, strict = false) }.getOrNull() } }

  fun summary(): Map<String, Any?> = mapOf(
    "name" to name, "owner" to owner, "description" to description, "enabled" to enabled,
    "disabled_reason" to disabledReason, "trigger" to triggerSpec?.let { Triggers.describe(it) },
    "steps" to steps.size, "run_count" to runCount, "last_run_at" to lastRunAt,
    "last_ok" to (lastResult?.get("ok") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull(),
    "next_run_at" to nextRunAt, "updated_at" to updatedAt,
  )

  fun full(): Map<String, Any?> = summary() + mapOf(
    "workflow_id" to id, "steps" to Json.fromJson(steps), "inputs_schema" to Json.fromJson(inputsSchema),
    "trigger" to Json.fromJson(trigger), "trigger_summary" to triggerSpec?.let { Triggers.describe(it) },
    "timeout_ms" to timeoutMs, "created_at" to createdAt, "last_result" to Json.fromJson(lastResult),
  )
}

/**
 * SQLite persistence for saved workflows (table `workflows`, DB v3) and their run log (`workflow_runs`).
 * Ownership is part of every lookup: a name means (owner, name), and nothing here returns another owner's row
 * unless the caller passed that owner explicitly (the capabilities only do so for the admin).
 */
class WorkflowStore(private val db: Db) {

  /** Insert or replace (owner, name). Keeps id, created_at and run history on replace. Returns (row, created). */
  fun upsert(
    owner: String, name: String, description: String?, steps: JsonArray, inputsSchema: JsonObject?,
    trigger: JsonObject?, timeoutMs: Long, enabled: Boolean, now: Long = Ids.now(),
  ): Pair<Workflow, Boolean> {
    val w = db.writableDatabase
    w.beginTransactionNonExclusive()
    try {
      val existing = get(owner, name)
      val values = ContentValues().apply {
        put("description", description)
        put("steps_json", Json.canonical(steps))
        put("inputs_schema_json", inputsSchema?.let { Json.canonical(it) })
        put("trigger_json", trigger?.let { Json.canonical(it) })
        put("timeout_ms", timeoutMs)
        put("enabled", if (enabled) 1 else 0)
        putNull("disabled_reason")
        put("updated_at", now)
        // A changed trigger starts its schedule afresh.
        putNull("next_run_at")
      }
      val id = if (existing == null) {
        val newId = "wf_" + Ids.hex(8)
        values.put("workflow_id", newId); values.put("owner_peer_id", owner); values.put("name", name); values.put("created_at", now)
        w.insertOrThrow("workflows", null, values)
        newId
      } else {
        w.update("workflows", values, "workflow_id = ?", arrayOf(existing.id))
        existing.id
      }
      w.setTransactionSuccessful()
      return getById(id)!! to (existing == null)
    } finally { w.endTransaction() }
  }

  fun get(owner: String, name: String): Workflow? = one("owner_peer_id = ? AND name = ?", arrayOf(owner, name))
  fun getById(id: String): Workflow? = one("workflow_id = ?", arrayOf(id))

  /** [owner] null = every owner (admin / UI). */
  fun list(owner: String? = null): List<Workflow> = db.readableDatabase.query(
    "workflows", null, owner?.let { "owner_peer_id = ?" }, owner?.let { arrayOf(it) }, null, null, "owner_peer_id, name",
  ).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }

  fun enabledWithTriggers(): List<Workflow> = db.readableDatabase.query(
    "workflows", null, "enabled = 1 AND trigger_json IS NOT NULL", null, null, null, null,
  ).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }

  fun delete(owner: String, name: String): Boolean {
    val wf = get(owner, name) ?: return false
    db.writableDatabase.delete("workflows", "workflow_id = ?", arrayOf(wf.id))
    db.writableDatabase.delete("workflow_runs", "workflow_id = ?", arrayOf(wf.id))
    return true
  }

  fun setEnabled(id: String, enabled: Boolean, reason: String? = null, now: Long = Ids.now()): Boolean =
    db.writableDatabase.update(
      "workflows",
      ContentValues().apply { put("enabled", if (enabled) 1 else 0); put("disabled_reason", if (enabled) null else reason); put("updated_at", now); putNull("next_run_at") },
      "workflow_id = ?", arrayOf(id),
    ) > 0

  /** Emergency stop / "Disable all": every enabled workflow off, with the reason recorded. Returns how many. */
  fun disableAll(reason: String, now: Long = Ids.now()): Int = db.writableDatabase.update(
    "workflows", ContentValues().apply { put("enabled", 0); put("disabled_reason", reason); put("updated_at", now) }, "enabled = 1", null,
  )

  /** Revocation: the peer's workflows are kept (the owner may want to see them) but disabled. */
  fun disableOwnedBy(owner: String, reason: String, now: Long = Ids.now()): Int = db.writableDatabase.update(
    "workflows", ContentValues().apply { put("enabled", 0); put("disabled_reason", reason); put("updated_at", now) },
    "owner_peer_id = ? AND enabled = 1", arrayOf(owner),
  )

  fun setNextRun(id: String, at: Long?) {
    db.writableDatabase.update("workflows", ContentValues().apply { if (at == null) putNull("next_run_at") else put("next_run_at", at) }, "workflow_id = ?", arrayOf(id))
  }

  fun recordRun(id: String, at: Long, result: JsonObject) {
    db.writableDatabase.execSQL(
      "UPDATE workflows SET last_run_at = ?, last_result_json = ?, run_count = run_count + 1 WHERE workflow_id = ?",
      arrayOf(at, Json.canonical(result), id),
    )
  }

  /** One line in the run log: status is `started`, then `ok` / `failed`; or `queued`, `missed`, `skipped:<reason>`. */
  fun log(id: String, at: Long, origin: String, status: String, correlationId: String? = null, detail: String? = null) {
    db.writableDatabase.insert("workflow_runs", null, ContentValues().apply {
      put("workflow_id", id); put("ts", at); put("origin", origin); put("status", status)
      put("correlation_id", correlationId); put("detail", detail?.take(500))
    })
  }

  /** Runs that a trigger (not a manual run_saved) started since [since]: what max_runs_per_hour counts. */
  fun triggeredRunsSince(id: String, since: Long): Int = db.readableDatabase.compileStatement(
    "SELECT COUNT(*) FROM workflow_runs WHERE workflow_id = ? AND ts >= ? AND status = 'started' AND origin != 'manual'",
  ).use { it.bindString(1, id); it.bindLong(2, since); it.simpleQueryForLong().toInt() }

  fun recentLog(id: String, limit: Int = 20): List<Map<String, Any?>> = db.readableDatabase.query(
    "workflow_runs", null, "workflow_id = ?", arrayOf(id), null, null, "id DESC", limit.toString(),
  ).use { c ->
    buildList {
      while (c.moveToNext()) add(mapOf(
        "ts" to c.getLong(c.getColumnIndexOrThrow("ts")), "origin" to c.getString(c.getColumnIndexOrThrow("origin")),
        "status" to c.getString(c.getColumnIndexOrThrow("status")), "correlation_id" to c.getString(c.getColumnIndexOrThrow("correlation_id")),
        "detail" to c.getString(c.getColumnIndexOrThrow("detail")),
      ))
    }
  }

  /** Keep a week of run log. */
  fun prune(now: Long = Ids.now()) { db.writableDatabase.delete("workflow_runs", "ts < ?", arrayOf((now - 7 * 86_400_000L).toString())) }

  private fun one(where: String, args: Array<String>): Workflow? =
    db.readableDatabase.query("workflows", null, where, args, null, null, null).use { c -> if (c.moveToFirst()) row(c) else null }

  private fun obj(s: String?): JsonObject? = s?.let { Json.lenient.parseToJsonElement(it) as? JsonObject }
  private fun arr(s: String): JsonArray = Json.lenient.parseToJsonElement(s) as? JsonArray ?: JsonArray(emptyList<JsonElement>())

  private fun row(c: Cursor): Workflow {
    fun s(n: String) = c.getString(c.getColumnIndexOrThrow(n))
    fun l(n: String): Long? = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getLong(it) }
    return Workflow(
      id = s("workflow_id"), owner = s("owner_peer_id"), name = s("name"), description = s("description"),
      steps = arr(s("steps_json")), inputsSchema = obj(s("inputs_schema_json")), trigger = obj(s("trigger_json")),
      timeoutMs = l("timeout_ms") ?: Batches.DEFAULT_TIMEOUT_MS, enabled = l("enabled") == 1L, disabledReason = s("disabled_reason"),
      createdAt = l("created_at") ?: 0, updatedAt = l("updated_at") ?: 0, lastRunAt = l("last_run_at"),
      lastResult = obj(s("last_result_json")), runCount = l("run_count") ?: 0, nextRunAt = l("next_run_at"),
    )
  }
}
