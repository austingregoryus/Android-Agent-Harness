package store

import (
	"path/filepath"
	"testing"
)

func open(t *testing.T) *Store {
	t.Helper()
	s, err := Open(filepath.Join(t.TempDir(), "gateway.db"))
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func TestTaskLifecycle(t *testing.T) {
	s := open(t)
	if err := s.CreateTask("task_1", "corr_1", "check the battery"); err != nil {
		t.Fatalf("CreateTask: %v", err)
	}
	got, err := s.GetTask("task_1")
	if err != nil {
		t.Fatalf("GetTask: %v", err)
	}
	if got.Status != TaskRunning || got.CorrelationID != "corr_1" {
		t.Fatalf("task = %+v", got)
	}

	tests := []struct {
		name   string
		status TaskStatus
		answer string
		errMsg string
	}{
		{"done", TaskDone, "82%", ""},
		{"failed", TaskFailed, "", "everything broke"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if err := s.FinishTask("task_1", string(tc.status), tc.answer, tc.errMsg); err != nil {
				t.Fatalf("FinishTask: %v", err)
			}
			got, err := s.GetTask("task_1")
			if err != nil {
				t.Fatal(err)
			}
			if got.Status != tc.status || got.Answer != tc.answer || got.Error != tc.errMsg {
				t.Errorf("task = %+v", got)
			}
			if got.FinishedAt == 0 {
				t.Error("finished_at was not set")
			}
		})
	}
}

func TestMessagesRoundTrip(t *testing.T) {
	s := open(t)
	if err := s.CreateTask("task_2", "corr_2", "x"); err != nil {
		t.Fatal(err)
	}
	parts := []map[string]any{{"type": "text", "text": "hello"}}
	if err := s.AppendMessage("task_2", 0, "user", "", parts); err != nil {
		t.Fatal(err)
	}
	if err := s.AppendMessage("task_2", 1, "assistant", "claude", parts); err != nil {
		t.Fatal(err)
	}
	rows, err := s.Messages("task_2")
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 2 {
		t.Fatalf("rows = %d", len(rows))
	}
	if rows[0].Role != "user" || rows[1].Provider != "claude" {
		t.Errorf("rows = %+v", rows)
	}
	if rows[1].Seq != 1 {
		t.Errorf("seq = %d", rows[1].Seq)
	}
}

func TestSubscriptionCursorStore(t *testing.T) {
	s := open(t)

	subID, cursor, err := s.LoadSubscription("missing")
	if err != nil || subID != "" || cursor != 0 {
		t.Fatalf("missing key = (%q, %d, %v), want empty", subID, cursor, err)
	}

	if err := s.SaveSubscription("k1", "sub_a", 10); err != nil {
		t.Fatal(err)
	}
	subID, cursor, err = s.LoadSubscription("k1")
	if err != nil || subID != "sub_a" || cursor != 10 {
		t.Fatalf("= (%q, %d, %v)", subID, cursor, err)
	}

	// The cursor must never move backwards: a late ack from an old socket
	// should not rewind progress.
	if err := s.SaveSubscription("k1", "sub_a", 5); err != nil {
		t.Fatal(err)
	}
	_, cursor, _ = s.LoadSubscription("k1")
	if cursor != 10 {
		t.Errorf("cursor rewound to %d, want it pinned at 10", cursor)
	}

	if err := s.SaveSubscription("k1", "sub_b", 25); err != nil {
		t.Fatal(err)
	}
	subID, cursor, _ = s.LoadSubscription("k1")
	if subID != "sub_b" || cursor != 25 {
		t.Errorf("= (%q, %d)", subID, cursor)
	}

	// A new subscription id is a new phone-side subscription (for example after
	// a journal reset), so its lower cursor replaces the old one rather than
	// being pinned to it.
	if err := s.SaveSubscription("k1", "sub_c", 3); err != nil {
		t.Fatal(err)
	}
	subID, cursor, _ = s.LoadSubscription("k1")
	if subID != "sub_c" || cursor != 3 {
		t.Errorf("after a subscription id change = (%q, %d), want (sub_c, 3)", subID, cursor)
	}

	// Unsubscribing deletes the row, so a later subscription for the same key
	// starts clean instead of replaying an old backlog.
	if err := s.DeleteSubscription("k1"); err != nil {
		t.Fatal(err)
	}
	subID, cursor, err = s.LoadSubscription("k1")
	if err != nil || subID != "" || cursor != 0 {
		t.Errorf("after delete = (%q, %d, %v), want empty", subID, cursor, err)
	}
	if err := s.DeleteSubscription("k1"); err != nil {
		t.Errorf("deleting a missing key: %v", err)
	}
}

func TestEventsAreBounded(t *testing.T) {
	s := open(t)
	// Writing MaxEvents+50 rows must leave exactly MaxEvents, keeping newest.
	total := MaxEvents + 50
	for i := 1; i <= total; i++ {
		if err := s.RecordEvent(int64(i), "sub_a", "battery.changed", int64(i)*1000, "dev_1", "",
			map[string]any{"percent": i % 100}); err != nil {
			t.Fatalf("RecordEvent %d: %v", i, err)
		}
	}
	n, err := s.CountEvents()
	if err != nil {
		t.Fatal(err)
	}
	if n != MaxEvents {
		t.Fatalf("retained %d events, want %d", n, MaxEvents)
	}
	var minID int64
	if err := s.DB().QueryRow(`SELECT MIN(id) FROM events`).Scan(&minID); err != nil {
		t.Fatal(err)
	}
	if minID != int64(total-MaxEvents+1) {
		t.Errorf("oldest retained id = %d, want %d", minID, total-MaxEvents+1)
	}
}

func TestRecordEventIsIdempotent(t *testing.T) {
	s := open(t)
	for i := 0; i < 3; i++ {
		if err := s.RecordEvent(42, "sub_a", "messaging.sms_received", 1, "dev_1", "corr_1",
			map[string]any{"text": "hi"}); err != nil {
			t.Fatalf("RecordEvent: %v", err)
		}
	}
	n, err := s.CountEvents()
	if err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Errorf("replaying the same event id stored %d rows, want 1", n)
	}
}
