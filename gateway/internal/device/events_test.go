package device

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/fakephone"
)

// recvTimeout is generous enough for a loaded CI box but keeps the suite fast.
const recvTimeout = 5 * time.Second

func recv(t *testing.T, ch <-chan Event) Event {
	t.Helper()
	select {
	case ev, ok := <-ch:
		if !ok {
			t.Fatal("event channel closed unexpectedly")
		}
		return ev
	case <-time.After(recvTimeout):
		t.Fatal("timed out waiting for an event")
		return Event{}
	}
}

func expectNoEvent(t *testing.T, ch <-chan Event, d time.Duration) {
	t.Helper()
	select {
	case ev := <-ch:
		t.Fatalf("unexpected event %d (%s)", ev.ID, ev.Topic)
	case <-time.After(d):
	}
}

// startClient boots an event client against a fake phone and waits for the
// first attachment.
func startClient(t *testing.T, p *fakephone.Phone, st CursorStore) (*EventClient, context.CancelFunc) {
	t.Helper()
	c := NewEventClient(EventOptions{BaseURL: p.URL(), Token: "tok", Store: st})
	ctx, cancel := context.WithCancel(context.Background())
	go func() { _ = c.Run(ctx) }()
	t.Cleanup(func() {
		c.Close()
		cancel()
	})
	return c, cancel
}

func TestLiveDelivery(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c, _ := startClient(t, p, nil)

	ctx, cancel := context.WithTimeout(context.Background(), recvTimeout)
	defer cancel()
	if err := c.WaitConnected(ctx); err != nil {
		t.Fatalf("WaitConnected: %v", err)
	}
	ch, unsub, err := c.Subscribe(ctx, SubscribeSpec{TopicGlob: "messaging.*"})
	if err != nil {
		t.Fatalf("Subscribe: %v", err)
	}
	defer unsub()

	p.Push("messaging.sms_received", map[string]any{"from": "+15550001234", "text": "hello"})
	ev := recv(t, ch)
	if ev.Topic != "messaging.sms_received" || ev.Payload["text"] != "hello" {
		t.Fatalf("event = %+v", ev)
	}

	// A non-matching topic must not be delivered.
	p.Push("battery.changed", map[string]any{"percent": 50})
	expectNoEvent(t, ch, 300*time.Millisecond)
}

func TestReconnectReplaysAndDeduplicates(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	st := NewMemoryCursorStore()
	c, _ := startClient(t, p, st)

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := c.WaitConnected(ctx); err != nil {
		t.Fatalf("WaitConnected: %v", err)
	}
	ch, unsub, err := c.Subscribe(ctx, SubscribeSpec{TopicGlob: "messaging.*"})
	if err != nil {
		t.Fatalf("Subscribe: %v", err)
	}
	defer unsub()

	first := p.Push("messaging.sms_received", map[string]any{"n": 1})
	got := recv(t, ch)
	if got.ID != first.ID {
		t.Fatalf("first event id = %d, want %d", got.ID, first.ID)
	}

	// Wait for the cumulative ack to land phone-side before dropping.
	subIDs := p.SubscriptionIDs()
	if len(subIDs) != 1 {
		t.Fatalf("phone has %d subscriptions, want 1", len(subIDs))
	}
	waitFor(t, 3*time.Second, func() bool { return p.AckCursor(subIDs[0]) >= first.ID })

	// Drop the socket, then push while the client is disconnected.
	p.ForceDrop()
	waitFor(t, 3*time.Second, func() bool { return p.ConnCount() == 0 })
	second := p.Push("messaging.sms_received", map[string]any{"n": 2})
	third := p.Push("messaging.sms_received", map[string]any{"n": 3})

	// The client reconnects, reuses the persisted subscription and replays from
	// the ack cursor. The already-seen first event must not be redelivered.
	ev2 := recv(t, ch)
	ev3 := recv(t, ch)
	if ev2.ID != second.ID || ev3.ID != third.ID {
		t.Fatalf("replayed ids = %d, %d; want %d, %d", ev2.ID, ev3.ID, second.ID, third.ID)
	}
	expectNoEvent(t, ch, 500*time.Millisecond)

	// The persisted cursor must have advanced.
	_, cursor, err := st.LoadSubscription(SubscribeSpec{TopicGlob: "messaging.*"}.Key())
	if err != nil {
		t.Fatal(err)
	}
	if cursor < third.ID {
		t.Errorf("persisted ack cursor = %d, want >= %d", cursor, third.ID)
	}

	// Reconnect must not have created a second phone-side subscription.
	if ids := p.SubscriptionIDs(); len(ids) != 1 || ids[0] != subIDs[0] {
		t.Errorf("subscriptions after reconnect = %v, want the original %v", ids, subIDs)
	}
}

func TestGapTriggersReplay(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c, _ := startClient(t, p, nil)

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := c.WaitConnected(ctx); err != nil {
		t.Fatalf("WaitConnected: %v", err)
	}
	ch, unsub, err := c.Subscribe(ctx, SubscribeSpec{TopicGlob: "*"})
	if err != nil {
		t.Fatalf("Subscribe: %v", err)
	}
	defer unsub()

	first := p.Push("battery.changed", map[string]any{"percent": 90})
	if got := recv(t, ch); got.ID != first.ID {
		t.Fatalf("first id = %d", got.ID)
	}
	waitFor(t, 3*time.Second, func() bool { return p.AckCursor(p.SubscriptionIDs()[0]) >= first.ID })

	// Simulate backpressure: the runtime journals but stops pushing.
	p.PauseLive()
	a := p.Push("battery.changed", map[string]any{"percent": 80})
	b := p.Push("battery.changed", map[string]any{"percent": 70})
	expectNoEvent(t, ch, 300*time.Millisecond)

	// Resuming emits a gap; the client must recover with a replay.
	p.ResumeLiveWithGap()

	evA := recv(t, ch)
	evB := recv(t, ch)
	if evA.ID != a.ID || evB.ID != b.ID {
		t.Fatalf("recovered ids = %d, %d; want %d, %d", evA.ID, evB.ID, a.ID, b.ID)
	}

	if !sawNotice(c, NoticeGap, 2*time.Second) {
		t.Error("expected a gap notice")
	}
}

func TestTruncatedReplayIsSurfaced(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	st := NewMemoryCursorStore()
	// Pretend the client already acked event 5 but the journal evicted
	// everything below 40.
	spec := SubscribeSpec{TopicGlob: "*"}
	p.SetEarliestAvailable(40)

	c, _ := startClient(t, p, st)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := c.WaitConnected(ctx); err != nil {
		t.Fatalf("WaitConnected: %v", err)
	}
	ch, unsub, err := c.Subscribe(ctx, spec)
	if err != nil {
		t.Fatalf("Subscribe: %v", err)
	}
	defer unsub()

	if !sawNotice(c, NoticeTruncated, 3*time.Second) {
		t.Error("expected a truncated notice when the journal evicted events")
	}
	_ = ch
}

func TestByeIsSurfaced(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c, _ := startClient(t, p, nil)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := c.WaitConnected(ctx); err != nil {
		t.Fatalf("WaitConnected: %v", err)
	}
	if _, _, err := c.Subscribe(ctx, SubscribeSpec{TopicGlob: "*"}); err != nil {
		t.Fatal(err)
	}
	p.SendBye("revoked")
	if !sawNotice(c, NoticeBye, 3*time.Second) {
		t.Error("expected a bye notice")
	}
}

func TestSubscribeSpecKeyIsStable(t *testing.T) {
	tests := []struct {
		name string
		a, b SubscribeSpec
		same bool
	}{
		{"identical", SubscribeSpec{TopicGlob: "a.*"}, SubscribeSpec{TopicGlob: "a.*"}, true},
		{"different glob", SubscribeSpec{TopicGlob: "a.*"}, SubscribeSpec{TopicGlob: "b.*"}, false},
		{"different delivery", SubscribeSpec{TopicGlob: "a.*", Delivery: "live"},
			SubscribeSpec{TopicGlob: "a.*", Delivery: "queue"}, false},
		{"same filter", SubscribeSpec{TopicGlob: "a.*", Filter: []FilterClause{{Field: "f", Op: "eq", Value: 1}}},
			SubscribeSpec{TopicGlob: "a.*", Filter: []FilterClause{{Field: "f", Op: "eq", Value: 1}}}, true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if (tc.a.Key() == tc.b.Key()) != tc.same {
				t.Errorf("Key() equality = %v, want %v", tc.a.Key() == tc.b.Key(), tc.same)
			}
		})
	}
}

// recordingStore counts RecordEvent calls and also acts as a cursor store.
type recordingStore struct {
	*MemoryCursorStore
	mu  sync.Mutex
	ids []int64
}

func (s *recordingStore) RecordEvent(id int64, subscriptionID, topic string, ts int64, deviceID, correlationID string, payload any) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.ids = append(s.ids, id)
	return nil
}

func (s *recordingStore) recorded() []int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]int64, len(s.ids))
	copy(out, s.ids)
	return out
}

func TestRecorderSeesEveryDeliveredEvent(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()

	rec := &recordingStore{MemoryCursorStore: NewMemoryCursorStore()}
	c := NewEventClient(EventOptions{BaseURL: p.URL(), Token: "tok", Store: rec, Recorder: rec})
	ctx, cancel := context.WithCancel(context.Background())
	go func() { _ = c.Run(ctx) }()
	defer func() {
		c.Close()
		cancel()
	}()

	wctx, wcancel := context.WithTimeout(ctx, recvTimeout)
	defer wcancel()
	if err := c.WaitConnected(wctx); err != nil {
		t.Fatalf("WaitConnected: %v", err)
	}
	ch, unsub, err := c.Subscribe(wctx, SubscribeSpec{TopicGlob: "*"})
	if err != nil {
		t.Fatal(err)
	}
	defer unsub()

	a := p.Push("battery.changed", map[string]any{"percent": 55})
	b := p.Push("battery.changed", map[string]any{"percent": 54})
	recv(t, ch)
	recv(t, ch)

	waitFor(t, 2*time.Second, func() bool { return len(rec.recorded()) == 2 })
	got := rec.recorded()
	if got[0] != a.ID || got[1] != b.ID {
		t.Errorf("recorded ids = %v, want [%d %d]", got, a.ID, b.ID)
	}
}

func sawNotice(c *EventClient, kind NoticeKind, d time.Duration) bool {
	deadline := time.After(d)
	for {
		select {
		case n := <-c.Notices():
			if n.Kind == kind {
				return true
			}
		case <-deadline:
			return false
		}
	}
}

func waitFor(t *testing.T, d time.Duration, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("condition was not met in time")
}
