package dev.symbiosis.runtime.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Single SQLite database for the runtime. Plain SQLiteOpenHelper on purpose (no Room/KSP): the
 * schema is small and the audit table needs raw triggers.
 */
class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, "symbiosis.db", null, VERSION) {

  override fun onConfigure(db: SQLiteDatabase) {
    db.setForeignKeyConstraintsEnabled(true)
    db.enableWriteAheadLogging()
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      """CREATE TABLE audit_entries (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        ts INTEGER NOT NULL,
        correlation_id TEXT,
        peer_id TEXT,
        kind TEXT NOT NULL,
        capability TEXT,
        action_class TEXT,
        args_json TEXT,
        result_json TEXT,
        success INTEGER,
        error_code TEXT,
        duration_ms INTEGER,
        idempotency_key TEXT,
        prev_hash TEXT NOT NULL,
        entry_hash TEXT NOT NULL
      )""",
    )
    db.execSQL("CREATE INDEX audit_corr ON audit_entries(correlation_id)")
    db.execSQL("CREATE INDEX audit_idem ON audit_entries(idempotency_key) WHERE idempotency_key IS NOT NULL")
    // Append-only, enforced at the engine level.
    db.execSQL("CREATE TRIGGER audit_no_update BEFORE UPDATE ON audit_entries BEGIN SELECT RAISE(ABORT, 'audit_entries is append-only'); END")
    db.execSQL("CREATE TRIGGER audit_no_delete BEFORE DELETE ON audit_entries BEGIN SELECT RAISE(ABORT, 'audit_entries is append-only'); END")

    db.execSQL(
      """CREATE TABLE events (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        topic TEXT NOT NULL,
        ts INTEGER NOT NULL,
        correlation_id TEXT,
        payload_json TEXT NOT NULL
      )""",
    )
    db.execSQL("CREATE INDEX events_topic ON events(topic, id)")

    db.execSQL(
      """CREATE TABLE subscriptions (
        subscription_id TEXT PRIMARY KEY,
        peer_id TEXT NOT NULL,
        topic_glob TEXT NOT NULL,
        filter_json TEXT NOT NULL,
        delivery TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        created_at_event_id INTEGER NOT NULL,
        expires_at INTEGER,
        ack_cursor INTEGER NOT NULL DEFAULT 0
      )""",
    )

    db.execSQL(
      """CREATE TABLE sessions (
        session_id TEXT PRIMARY KEY,
        kind TEXT NOT NULL,
        state TEXT NOT NULL,
        peer_id TEXT,
        correlation_id TEXT,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        ended_at INTEGER,
        meta_json TEXT NOT NULL,
        summary_json TEXT
      )""",
    )
    db.execSQL(
      """CREATE TABLE session_messages (
        session_id TEXT NOT NULL,
        seq INTEGER NOT NULL,
        ts INTEGER NOT NULL,
        type TEXT NOT NULL,
        frame_json TEXT NOT NULL,
        PRIMARY KEY (session_id, seq)
      )""",
    )

    db.execSQL(
      """CREATE TABLE pending_actions (
        pending_action_id TEXT PRIMARY KEY,
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        peer_id TEXT,
        correlation_id TEXT,
        capability TEXT NOT NULL,
        action_class TEXT NOT NULL,
        args_json TEXT NOT NULL,
        status TEXT NOT NULL,
        resolved_at INTEGER,
        result_json TEXT
      )""",
    )

    db.execSQL(
      """CREATE TABLE trusted_peers (
        peer_id TEXT PRIMARY KEY,
        label TEXT NOT NULL,
        token_sha256 TEXT NOT NULL UNIQUE,
        scopes_json TEXT NOT NULL,
        max_action_class TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        expires_at INTEGER,
        revoked_at INTEGER,
        last_seen_at INTEGER
      )""",
    )

    db.execSQL(
      """CREATE TABLE pairing_codes (
        code TEXT PRIMARY KEY,
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        scopes_json TEXT NOT NULL,
        max_action_class TEXT NOT NULL,
        used_at INTEGER
      )""",
    )

    db.execSQL(
      """CREATE TABLE policies (
        capability TEXT PRIMARY KEY,
        decision TEXT NOT NULL,
        updated_at INTEGER NOT NULL
      )""",
    )

    db.execSQL(
      """CREATE TABLE rate_counters (
        rate_class TEXT NOT NULL,
        ts INTEGER NOT NULL
      )""",
    )
    db.execSQL("CREATE INDEX rate_idx ON rate_counters(rate_class, ts)")

    db.execSQL(
      """CREATE TABLE settings (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL
      )""",
    )
    createAuditRetention(db)
    createWorkflows(db)
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    // Audit rows are never dropped by a migration; v2 only adds the retention anchor and narrows the delete guard.
    if (oldVersion < 2) createAuditRetention(db)
    // v3 only adds tables; nothing existing is touched.
    if (oldVersion < 3) createWorkflows(db)
  }

  /**
   * v3: saved workflows (automation/WorkflowStore.kt). One row per workflow, owned by the peer that saved it; names
   * are unique per owner, not globally, so two agents can each have a "status" workflow. `workflow_runs` is a short
   * operational log (runs, and triggers that were skipped by the cooldown, rate cap, loop guard or busy rule) — the
   * durable record of what a run DID is the audit log, where every step is its own tool call.
   */
  private fun createWorkflows(db: SQLiteDatabase) {
    db.execSQL(
      """CREATE TABLE IF NOT EXISTS workflows (
        workflow_id TEXT PRIMARY KEY,
        owner_peer_id TEXT NOT NULL,
        name TEXT NOT NULL,
        description TEXT,
        steps_json TEXT NOT NULL,
        inputs_schema_json TEXT,
        trigger_json TEXT,
        timeout_ms INTEGER NOT NULL,
        enabled INTEGER NOT NULL,
        disabled_reason TEXT,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        last_run_at INTEGER,
        last_result_json TEXT,
        run_count INTEGER NOT NULL DEFAULT 0,
        next_run_at INTEGER,
        UNIQUE (owner_peer_id, name)
      )""",
    )
    db.execSQL(
      """CREATE TABLE IF NOT EXISTS workflow_runs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        workflow_id TEXT NOT NULL,
        ts INTEGER NOT NULL,
        origin TEXT NOT NULL,
        status TEXT NOT NULL,
        correlation_id TEXT,
        detail TEXT
      )""",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS workflow_runs_idx ON workflow_runs(workflow_id, ts)")
  }

  /**
   * v2: audit retention. The log used to be append-only forever (and grew by whole base64 clips). Deleting old
   * rows is now allowed, but only rows *before the latest anchor*: `AuditStore.prune` records an anchor (oldest
   * kept id + its prev_hash) and deletes below it in one transaction. Anything at or after the anchor is still
   * protected by the engine, so the kept chain stays tamper-evident, and the anchors themselves are append-only.
   */
  private fun createAuditRetention(db: SQLiteDatabase) {
    db.execSQL(
      """CREATE TABLE IF NOT EXISTS audit_anchors (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        created_at INTEGER NOT NULL,
        first_kept_id INTEGER NOT NULL,
        first_kept_prev_hash TEXT NOT NULL,
        pruned_count INTEGER NOT NULL
      )""",
    )
    db.execSQL("CREATE TRIGGER IF NOT EXISTS audit_anchors_no_update BEFORE UPDATE ON audit_anchors BEGIN SELECT RAISE(ABORT, 'audit_anchors is append-only'); END")
    db.execSQL("CREATE TRIGGER IF NOT EXISTS audit_anchors_no_delete BEFORE DELETE ON audit_anchors BEGIN SELECT RAISE(ABORT, 'audit_anchors is append-only'); END")
    db.execSQL("DROP TRIGGER IF EXISTS audit_no_delete")
    db.execSQL(
      "CREATE TRIGGER audit_no_delete BEFORE DELETE ON audit_entries " +
        "WHEN OLD.id >= COALESCE((SELECT MAX(first_kept_id) FROM audit_anchors), 0) " +
        "BEGIN SELECT RAISE(ABORT, 'audit_entries is append-only before the retention anchor'); END",
    )
  }

  companion object {
    const val VERSION = 3
  }
}
