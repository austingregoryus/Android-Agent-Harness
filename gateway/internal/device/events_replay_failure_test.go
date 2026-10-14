package device

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/fakephone"
)

// startFastClient is startClient with short request and retry bounds, so
// replay failures resolve in milliseconds rather than the production 15 s.
func startFastClient(t *testing.T, p *fakephone.Phone, maxFailures int) *EventClient {
	t.Helper()
	c := NewEventClient(EventOptions{BaseURL: p.URL(), Token: "tok"})
	c.reqTimeout = 150 * time.Millisecond
	c.replayRetryMin = 20 * time.Millisecond
	c.replayRetryMax = 100 * time.Millisecond
	c.replayMaxFailures = maxFailures
	ctx, cancel := context.WithCancel(context.Background())
	go func() { _ = c.Run(ctx) }()
	t.Cleanup(func() {
		c.Close()
		cancel()
	})
	wctx, wcancel := context.WithTimeout(ctx, recvTimeout)
	defer wcancel()
	if err := c.WaitConnected(wctx); err != nil {
		t.Fatalf("WaitConnected: %v", err)
	}
	return c
}

// TestFailedGapReplayDoesNotSwallowLiveEvents is the H5 regression. replay()
// marks the subscription as catching up before it asks, and used to clear that
// only on success: a gap replay that timed out, drew an error frame, or got a
// reply for another subscription left catchingUp stuck, so every later live
// event took the "catching up, defer it" branch and was swallowed until the
// next reconnect. gateway.wait_for_event then timed out while events arrived.
func TestFailedGapReplayDoesNotSwallowLiveEvents(t *testing.T) {
	cases := []struct {
		name   string
		legacy bool
		action fakephone.ReplayAction
	}{
		{"timeout", false, fakephone.ReplayDrop},
		{"error frame", false, fakephone.ReplayError},
		{"wrong subscription", false, fakephone.ReplayWrongSubscription},
		{"legacy runtime timeout", true, fakephone.ReplayDrop},
		{"legacy runtime error frame", true, fakephone.ReplayError},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			p := fakephone.New(fakephone.Options{Token: "tok", Legacy: tc.legacy})
			defer p.Close()
			c := startFastClient(t, p, 100)

			ch, unsub, err := c.Subscribe(context.Background(), SubscribeSpec{TopicGlob: "*"})
			if err != nil {
				t.Fatalf("Subscribe: %v", err)
			}
			defer unsub()
			first := p.Push("battery.changed", map[string]any{"percent": 90})
			if got := recv(t, ch); got.ID != first.ID {
				t.Fatalf("first id = %d, want %d", got.ID, first.ID)
			}

			// Backpressure: the phone journals an event without pushing it,
			// then reports the gap. The replay the gap triggers fails.
			p.PauseLive()
			missed := p.Push("battery.changed", map[string]any{"percent": 80})
			p.ScriptReplays(tc.action)
			p.ResumeLiveWithGap()

			if got := recv(t, ch); got.ID != missed.ID {
				t.Fatalf("recovered id = %d, want the gap event %d", got.ID, missed.ID)
			}
			// And live push must flow again afterwards.
			live := p.Push("battery.changed", map[string]any{"percent": 70})
			if got := recv(t, ch); got.ID != live.ID {
				t.Fatalf("live id = %d, want %d", got.ID, live.ID)
			}
		})
	}
}

// TestPersistentReplayFailureReconnects covers the escalation: when replay
// keeps failing on a socket, the client tears the socket down so the reconnect
// path re-attaches and replays, rather than retrying the same socket forever.
func TestPersistentReplayFailureReconnects(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c := startFastClient(t, p, 2)

	ch, unsub, err := c.Subscribe(context.Background(), SubscribeSpec{TopicGlob: "*"})
	if err != nil {
		t.Fatalf("Subscribe: %v", err)
	}
	defer unsub()
	first := p.Push("battery.changed", map[string]any{"percent": 90})
	if got := recv(t, ch); got.ID != first.ID {
		t.Fatalf("first id = %d", got.ID)
	}
	drain(c)

	p.PauseLive()
	missed := p.Push("battery.changed", map[string]any{"percent": 80})
	// The gap replay and both recovery attempts fail; the replay after the
	// reconnect succeeds.
	p.ScriptReplays(fakephone.ReplayDrop, fakephone.ReplayDrop, fakephone.ReplayDrop)
	p.ResumeLiveWithGap()

	if !sawNotice(c, NoticeReconnect, 5*time.Second) {
		t.Fatal("persistent replay failure did not tear the socket down")
	}
	if got := recv(t, ch); got.ID != missed.ID {
		t.Fatalf("recovered id = %d, want %d", got.ID, missed.ID)
	}
	waitFor(t, 5*time.Second, func() bool { return p.ConnCount() == 1 })
	live := p.Push("battery.changed", map[string]any{"percent": 70})
	if got := recv(t, ch); got.ID != live.ID {
		t.Fatalf("live id after reconnect = %d, want %d", got.ID, live.ID)
	}
}

// TestUnrelatedErrorFrameIsNotAReply checks the routing half of H5: once the
// runtime is known to echo refs, an error frame without a ref (or with another
// request's ref) is not handed to the outstanding request. Against a runtime
// that echoes no refs the old type-only matching still applies.
func TestUnrelatedErrorFrameIsNotAReply(t *testing.T) {
	frame := func(m map[string]any) []byte {
		raw, err := json.Marshal(m)
		if err != nil {
			t.Fatal(err)
		}
		return raw
	}
	pending := func(c *EventClient, ref string) *replyWaiter {
		w := &replyWaiter{want: "replay_result", ref: ref, ch: make(chan json.RawMessage, 1)}
		c.mu.Lock()
		c.waiter = w
		c.mu.Unlock()
		return w
	}
	resolved := func(w *replyWaiter) bool {
		select {
		case <-w.ch:
			return true
		default:
			return false
		}
	}
	ctx := context.Background()

	c := NewEventClient(EventOptions{})
	defer c.Close()
	// A reply carrying a ref proves this runtime echoes refs.
	c.handleFrame(ctx, frame(map[string]any{"type": "pong", "ref": "g1"}))
	w := pending(c, "g2")
	c.handleFrame(ctx, frame(map[string]any{"type": "error", "code": "invalid_json", "message": "frame is not a JSON object"}))
	if resolved(w) {
		t.Fatal("a ref-less error frame failed the pending replay")
	}
	c.handleFrame(ctx, frame(map[string]any{"type": "error", "code": "internal", "message": "x", "ref": "g1"}))
	if resolved(w) {
		t.Fatal("another request's error frame failed the pending replay")
	}
	c.handleFrame(ctx, frame(map[string]any{"type": "error", "code": "subscription.unknown", "message": "sub_x", "ref": "g2"}))
	if !resolved(w) {
		t.Fatal("the pending request's own error frame did not reach it")
	}

	legacy := NewEventClient(EventOptions{})
	defer legacy.Close()
	w = pending(legacy, "g1")
	legacy.handleFrame(ctx, frame(map[string]any{"type": "error", "code": "internal", "message": "x"}))
	if !resolved(w) {
		t.Fatal("without ref echo an error frame must still end the outstanding request")
	}
}

// drain discards queued notices.
func drain(c *EventClient) {
	for {
		select {
		case <-c.Notices():
		default:
			return
		}
	}
}
