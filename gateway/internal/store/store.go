// Package store is the gateway's local SQLite persistence: task records, the
// canonical transcript for each task, event-subscription ack cursors, and a
// bounded copy of received events.
//
// It uses modernc.org/sqlite, a pure-Go driver, so the gateway builds and runs
// without cgo.
package store

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	_ "modernc.org/sqlite" // pure-Go SQLite driver
)

// MaxEvents bounds the local copy of received events.
const MaxEvents = 10000

// schema is applied on every Open; every statement is idempotent.
const schema = `
CREATE TABLE IF NOT EXISTS tasks (
  id              TEXT PRIMARY KEY,
  correlation_id  TEXT NOT NULL,
  task_text       TEXT NOT NULL,
  status          TEXT NOT NULL,
  answer          TEXT,
  error           TEXT,
  created_at      INTEGER NOT NULL,
  finished_at     INTEGER
);

CREATE TABLE IF NOT EXISTS task_messages (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id    TEXT NOT NULL,
  seq        INTEGER NOT NULL,
  role       TEXT NOT NULL,
  provider   TEXT,
  parts_json TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_task_messages_task ON task_messages(task_id, seq);

CREATE TABLE IF NOT EXISTS subscriptions (
  sub_key         TEXT PRIMARY KEY,
  subscription_id TEXT NOT NULL,
  ack_cursor      INTEGER NOT NULL DEFAULT 0,
  updated_at      INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS events (
  id              INTEGER PRIMARY KEY,
  subscription_id TEXT,
  topic           TEXT NOT NULL,
  ts              INTEGER NOT NULL,
  device_id       TEXT,
  correlation_id  TEXT,
  payload_json    TEXT NOT NULL,
  received_at     INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_events_topic ON events(topic);
`

// Store is the gateway's SQLite-backed persistence.
type Store struct {
	db *sql.DB

	// mu guards eventCount, an in-memory row count for the events table that
	// keeps retention trimming O(1) per insert instead of rescanning.
	mu         sync.Mutex
	eventCount int
}

// pragmas configure the connection. WAL plus synchronous=NORMAL avoids an fsync
// per insert, which matters because the event channel writes one row per event.
const pragmas = "_pragma=journal_mode(WAL)&_pragma=synchronous(NORMAL)&_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)"

// Open opens (creating if needed) the SQLite database at path and applies the
// schema. Use ":memory:" for an ephemeral store.
func Open(path string) (*Store, error) {
	dsn := "file:" + filepath.ToSlash(path) + "?" + pragmas
	if path == ":memory:" {
		dsn = "file::memory:?cache=shared&" + pragmas
	} else if dir := filepath.Dir(path); dir != "" && dir != "." {
		// storage.sqlite_path resolves against the config file's directory, so
		// it routinely points into a subdirectory that does not exist yet. SQLite
		// creates the file but not the path to it.
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return nil, fmt.Errorf("store: create directory for %s: %w", path, err)
		}
	}
	if path != ":memory:" {
		// The database holds SMS text and transcripts. Create it owner-only
		// before SQLite does (which would honour the umask, commonly leaving it
		// world-readable), and tighten one that already exists.
		if err := createPrivate(path); err != nil {
			return nil, err
		}
	}
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("store: open %s: %w", path, err)
	}
	// modernc.org/sqlite serialises poorly under heavy concurrency; the gateway
	// is low-volume, so one connection keeps behaviour predictable.
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, fmt.Errorf("store: apply schema: %w", err)
	}
	if path != ":memory:" {
		// The schema write has created the -wal and -shm sidecars. On Unix
		// SQLite gives them the main file's mode already; restricting them here
		// covers an older database and Windows, where they inherit the
		// directory ACL. The one connection stays open, so they persist.
		for _, sidecar := range []string{path + "-wal", path + "-shm"} {
			if err := restrictFile(sidecar); err != nil {
				db.Close()
				return nil, fmt.Errorf("store: restrict %s: %w", sidecar, err)
			}
		}
	}
	s := &Store{db: db}
	if err := db.QueryRow(`SELECT COUNT(*) FROM events`).Scan(&s.eventCount); err != nil {
		db.Close()
		return nil, fmt.Errorf("store: count events: %w", err)
	}
	return s, nil
}

// createPrivate creates path with owner-only permissions if it does not exist,
// and restricts it either way.
func createPrivate(path string) error {
	f, err := os.OpenFile(path, os.O_RDWR|os.O_CREATE, 0o600)
	if err != nil {
		return fmt.Errorf("store: create %s: %w", path, err)
	}
	if err := f.Close(); err != nil {
		return fmt.Errorf("store: create %s: %w", path, err)
	}
	if err := restrictFile(path); err != nil {
		return fmt.Errorf("store: restrict %s: %w", path, err)
	}
	return nil
}

// Close releases the database handle.
func (s *Store) Close() error { return s.db.Close() }

// DB exposes the underlying handle for callers that need raw SQL.
func (s *Store) DB() *sql.DB { return s.db }

// TaskStatus is the lifecycle state of a task row.
type TaskStatus string

// Task statuses.
const (
	// TaskRunning means the agent loop is in progress.
	TaskRunning TaskStatus = "running"
	// TaskDone means the agent produced a final answer.
	TaskDone TaskStatus = "done"
	// TaskFailed means the agent gave up.
	TaskFailed TaskStatus = "failed"
)

// CreateTask inserts a running task row.
func (s *Store) CreateTask(id, correlationID, taskText string) error {
	_, err := s.db.Exec(
		`INSERT INTO tasks (id, correlation_id, task_text, status, created_at) VALUES (?, ?, ?, ?, ?)`,
		id, correlationID, taskText, string(TaskRunning), time.Now().UnixMilli())
	if err != nil {
		return fmt.Errorf("store: create task: %w", err)
	}
	return nil
}

// FinishTask records the terminal state of a task. status is one of the
// TaskStatus constants.
func (s *Store) FinishTask(id string, status string, answer, errText string) error {
	_, err := s.db.Exec(
		`UPDATE tasks SET status = ?, answer = ?, error = ?, finished_at = ? WHERE id = ?`,
		status, answer, errText, time.Now().UnixMilli(), id)
	if err != nil {
		return fmt.Errorf("store: finish task: %w", err)
	}
	return nil
}

// TaskRecord is a persisted task.
type TaskRecord struct {
	// ID is the task identifier.
	ID string
	// CorrelationID is the correlation id minted for the task.
	CorrelationID string
	// TaskText is the operator's request.
	TaskText string
	// Status is the lifecycle state.
	Status TaskStatus
	// Answer is the final assistant text, when finished.
	Answer string
	// Error is the failure text, when failed.
	Error string
	// CreatedAt is the creation time in epoch milliseconds.
	CreatedAt int64
	// FinishedAt is the completion time in epoch milliseconds, or 0.
	FinishedAt int64
}

// GetTask reads one task row.
func (s *Store) GetTask(id string) (TaskRecord, error) {
	var t TaskRecord
	var answer, errText sql.NullString
	var finished sql.NullInt64
	var status string
	row := s.db.QueryRow(
		`SELECT id, correlation_id, task_text, status, answer, error, created_at, finished_at FROM tasks WHERE id = ?`, id)
	if err := row.Scan(&t.ID, &t.CorrelationID, &t.TaskText, &status, &answer, &errText, &t.CreatedAt, &finished); err != nil {
		return TaskRecord{}, err
	}
	t.Status = TaskStatus(status)
	t.Answer = answer.String
	t.Error = errText.String
	t.FinishedAt = finished.Int64
	return t, nil
}

// AppendMessage persists one canonical transcript message. parts is marshalled
// to JSON, so it accepts the provider package's []Part without importing it.
func (s *Store) AppendMessage(taskID string, seq int, role, providerName string, parts any) error {
	raw, err := json.Marshal(parts)
	if err != nil {
		return fmt.Errorf("store: encode message parts: %w", err)
	}
	_, err = s.db.Exec(
		`INSERT INTO task_messages (task_id, seq, role, provider, parts_json, created_at) VALUES (?, ?, ?, ?, ?, ?)`,
		taskID, seq, role, providerName, string(raw), time.Now().UnixMilli())
	if err != nil {
		return fmt.Errorf("store: append message: %w", err)
	}
	return nil
}

// MessageRow is one persisted transcript entry.
type MessageRow struct {
	// Seq orders messages within a task.
	Seq int
	// Role is the canonical role.
	Role string
	// Provider is the provider that produced an assistant message.
	Provider string
	// PartsJSON is the JSON-encoded parts array.
	PartsJSON string
}

// Messages returns a task's transcript in order.
func (s *Store) Messages(taskID string) ([]MessageRow, error) {
	rows, err := s.db.Query(
		`SELECT seq, role, provider, parts_json FROM task_messages WHERE task_id = ? ORDER BY seq`, taskID)
	if err != nil {
		return nil, fmt.Errorf("store: read messages: %w", err)
	}
	defer rows.Close()
	var out []MessageRow
	for rows.Next() {
		var m MessageRow
		var prov sql.NullString
		if err := rows.Scan(&m.Seq, &m.Role, &prov, &m.PartsJSON); err != nil {
			return nil, err
		}
		m.Provider = prov.String
		out = append(out, m)
	}
	return out, rows.Err()
}

// LoadSubscription implements device.CursorStore.
func (s *Store) LoadSubscription(key string) (string, int64, error) {
	var subID string
	var cursor int64
	row := s.db.QueryRow(`SELECT subscription_id, ack_cursor FROM subscriptions WHERE sub_key = ?`, key)
	switch err := row.Scan(&subID, &cursor); err {
	case nil:
		return subID, cursor, nil
	case sql.ErrNoRows:
		return "", 0, nil
	default:
		return "", 0, fmt.Errorf("store: load subscription: %w", err)
	}
}

// SaveSubscription implements device.CursorStore.
//
// For the same subscription id the cursor only moves forward, so a late ack
// from an old socket cannot rewind progress. A different subscription id is a
// new phone-side subscription, and its cursor replaces the old one outright:
// keeping the larger value would make the gateway drop the new subscription's
// events as duplicates after a phone journal reset.
func (s *Store) SaveSubscription(key, subscriptionID string, ackCursor int64) error {
	_, err := s.db.Exec(`
INSERT INTO subscriptions (sub_key, subscription_id, ack_cursor, updated_at) VALUES (?, ?, ?, ?)
ON CONFLICT(sub_key) DO UPDATE SET ack_cursor = CASE
                                     WHEN subscriptions.subscription_id = excluded.subscription_id
                                     THEN MAX(subscriptions.ack_cursor, excluded.ack_cursor)
                                     ELSE excluded.ack_cursor END,
                                   subscription_id = excluded.subscription_id,
                                   updated_at = excluded.updated_at`,
		key, subscriptionID, ackCursor, time.Now().UnixMilli())
	if err != nil {
		return fmt.Errorf("store: save subscription: %w", err)
	}
	return nil
}

// DeleteSubscription implements device.CursorStore. Deleting a missing key is
// not an error.
func (s *Store) DeleteSubscription(key string) error {
	if _, err := s.db.Exec(`DELETE FROM subscriptions WHERE sub_key = ?`, key); err != nil {
		return fmt.Errorf("store: delete subscription: %w", err)
	}
	return nil
}

// RecordEvent stores a received event, then trims the table to MaxEvents rows.
// Re-inserting a known event id is a no-op, which makes replay idempotent.
func (s *Store) RecordEvent(id int64, subscriptionID, topic string, ts int64, deviceID, correlationID string, payload any) error {
	raw, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("store: encode event payload: %w", err)
	}
	res, err := s.db.Exec(`
INSERT INTO events (id, subscription_id, topic, ts, device_id, correlation_id, payload_json, received_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(id) DO NOTHING`,
		id, subscriptionID, topic, ts, deviceID, correlationID, string(raw), time.Now().UnixMilli())
	if err != nil {
		return fmt.Errorf("store: record event: %w", err)
	}
	inserted, err := res.RowsAffected()
	if err != nil || inserted == 0 {
		// A duplicate id (a replayed event) changes nothing.
		return nil
	}

	s.mu.Lock()
	s.eventCount++
	excess := s.eventCount - MaxEvents
	s.mu.Unlock()
	if excess <= 0 {
		return nil
	}

	// Drop exactly the oldest `excess` rows. Once the table is at capacity this
	// deletes one row per insert and rides the primary key index.
	if _, err := s.db.Exec(
		`DELETE FROM events WHERE id IN (SELECT id FROM events ORDER BY id ASC LIMIT ?)`, excess); err != nil {
		return fmt.Errorf("store: trim events: %w", err)
	}
	s.mu.Lock()
	s.eventCount -= excess
	s.mu.Unlock()
	return nil
}

// CountEvents reports how many events are retained locally.
func (s *Store) CountEvents() (int, error) {
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM events`).Scan(&n); err != nil {
		return 0, err
	}
	return n, nil
}
