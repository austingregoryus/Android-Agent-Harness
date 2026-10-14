package device

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/fakephone"
)

// These tests are written so that `go test -race` exercises the paths they
// cover: every one of them has a consumer goroutine and a producer goroutine
// touching the same subscription concurrently. They pass without -race too,
// because cgo is not available in this environment, but they are shaped for it.

// TestCancelDuringDeliveryDoesNotPanic is the P1-1 regression. cancel() used to
// close the subscription channel outside the mutex that deliver() holds, so a
// cancel racing an in-flight delivery panicked the whole process with "send on
// closed channel" — from a goroutine the caller cannot recover.
func TestCancelDuringDeliveryDoesNotPanic(t *testing.T) {
	for round := 0; round < 20; round++ {
		func() {
			p := fakephone.New(fakephone.Options{Token: "tok"})
			defer p.Close()
			c, cancelRun := startClient(t, p, nil)
			defer cancelRun()

			ch, unsubscribe, err := c.Subscribe(context.Background(), SubscribeSpec{TopicGlob: "*"})
			if err != nil {
				t.Fatalf("round %d: Subscribe: %v", round, err)
			}

			var wg sync.WaitGroup
			stop := make(chan struct{})

			// The phone pushes continuously.
			wg.Add(1)
			go func() {
				defer wg.Done()
				for i := 0; ; i++ {
					select {
					case <-stop:
						return
					default:
					}
					p.Push("messaging.sms_received", map[string]any{"n": i})
				}
			}()

			// A consumer drains, so deliveries really are in flight.
			wg.Add(1)
			go func() {
				defer wg.Done()
				for range ch {
				}
			}()

			// Cancel in the middle of it.
			time.Sleep(15 * time.Millisecond)
			unsubscribe()
			// A second cancel must be a no-op, not a double close.
			unsubscribe()

			close(stop)
			wg.Wait()
		}()
	}
}

// TestSubscribeRacingReconnectSubscribesOnce is the P1-7 regression: Subscribe
// and the reconnect's attachAll could both attach the same spec, leaving two
// phone-side subscriptions delivering the same events and a stale id in the
// cursor store.
func TestSubscribeRacingReconnectSubscribesOnce(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c, cancelRun := startClient(t, p, nil)
	defer cancelRun()

	ch, unsubscribe, err := c.Subscribe(context.Background(), SubscribeSpec{TopicGlob: "messaging.*"})
	if err != nil {
		t.Fatalf("Subscribe: %v", err)
	}
	defer unsubscribe()
	waitFor(t, 5*time.Second, func() bool { return len(p.SubscriptionIDs()) == 1 })

	// Force reconnects while a consumer drains, so attachAll runs repeatedly
	// against a live subscription.
	done := make(chan struct{})
	go func() {
		for {
			select {
			case <-done:
				return
			case ev := <-ch:
				_ = ev
			}
		}
	}()
	for i := 0; i < 4; i++ {
		p.ForceDrop()
		waitFor(t, 10*time.Second, func() bool { return p.ConnCount() > 0 })
		if !sawNotice(c, NoticeConnected, 10*time.Second) {
			t.Fatal("client never re-attached after a drop")
		}
	}
	close(done)

	// Exactly one subscription must exist on the phone, however many reconnects
	// and attach attempts raced.
	if ids := p.SubscriptionIDs(); len(ids) != 1 {
		t.Fatalf("phone holds %d subscriptions after reconnects, want 1: %v", len(ids), ids)
	}
}

// TestReplayWithNoConsumerDoesNotStallReadLoop is the P1-6 regression. deliver()
// used to block on the consumer channel, so a replay larger than the 256-event
// buffer with nobody draining wedged the socket read loop — which is also the
// goroutine that carries the replay_result reply, so attach() then timed out and
// the reconnect loop never converged.
func TestReplayWithNoConsumerDoesNotStallReadLoop(t *testing.T) {
	c := NewEventClient(EventOptions{BaseURL: "http://127.0.0.1:0", Token: "tok"})
	defer c.Close()

	spec := SubscribeSpec{TopicGlob: "*"}
	sub := &subscription{
		spec:  spec,
		key:   spec.Key(),
		out:   make(chan Event, 256),
		subID: "sub_000000000001",
	}
	c.mu.Lock()
	c.subs[sub.key] = sub
	c.mu.Unlock()

	// An outstanding replay request, exactly as attach() would leave it.
	w := &replyWaiter{want: "replay_result", ch: make(chan json.RawMessage, 1)}
	c.mu.Lock()
	c.waiter = w
	c.mu.Unlock()

	// 600 events: well past the 256-event channel buffer, with no consumer.
	events := make([]map[string]any, 0, 600)
	for i := 1; i <= 600; i++ {
		events = append(events, map[string]any{
			"id": i, "topic": "messaging.sms_received", "ts": 1757000000000 + i,
			"payload": map[string]any{"n": i},
		})
	}
	frame, err := json.Marshal(map[string]any{
		"type": "replay_result", "subscription_id": sub.subID,
		"events": events, "next_since_id": 600, "truncated": false,
	})
	if err != nil {
		t.Fatal(err)
	}

	done := make(chan struct{})
	go func() {
		c.handleFrame(context.Background(), frame)
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("handleFrame stalled on a 600-event replay with no consumer: the read loop is deadlocked")
	}

	// The pending request must be resolved despite the full consumer buffer;
	// delivery is non-blocking, so attach() cannot deadlock behind that buffer.
	select {
	case <-w.ch:
	default:
		t.Fatal("the replay_result reply never reached the waiting request")
	}

	// The buffer holds what fit; the rest are dropped rather than delivered.
	if n := len(sub.out); n != 256 {
		t.Errorf("buffered %d events, want the full 256-event buffer", n)
	}

	// The ack cursor must not have run ahead of what was actually delivered,
	// otherwise the phone would consider the dropped events acknowledged and
	// they would be lost for good.
	c.mu.Lock()
	cursor := sub.ackCursor
	c.mu.Unlock()
	if cursor != 256 {
		t.Errorf("ack cursor = %d, want 256 (only delivered events may be acknowledged)", cursor)
	}

	// The undelivered events must be reported as a gap so the operator and the
	// next replay both know about them.
	if !sawNotice(c, NoticeGap, time.Second) {
		t.Error("no gap notice was raised for the undelivered events")
	}
}

// TestLivePushWithNoConsumerKeepsTheSocketUsable is the same property against a
// real socket: a flood with nobody reading must not wedge the read loop, so a
// ping issued afterwards still gets its pong.
func TestLivePushWithNoConsumerKeepsTheSocketUsable(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c, cancelRun := startClient(t, p, nil)
	defer cancelRun()

	_, unsubscribe, err := c.Subscribe(context.Background(), SubscribeSpec{TopicGlob: "*"})
	if err != nil {
		t.Fatalf("Subscribe: %v", err)
	}
	defer unsubscribe()
	waitFor(t, 5*time.Second, func() bool { return len(p.SubscriptionIDs()) == 1 })

	// Nobody reads the channel.
	for i := 0; i < 600; i++ {
		p.Push("messaging.sms_received", map[string]any{"n": i})
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := c.Ping(ctx); err != nil {
		t.Fatalf("the read loop is stalled: ping failed: %v", err)
	}
}

// TestConcurrentSubscribeAndCancelAcrossSpecs hammers the subscription map from
// several goroutines at once; under -race this is the one that catches an
// unguarded read of sub.subID or of the subs map.
func TestConcurrentSubscribeAndCancelAcrossSpecs(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c, cancelRun := startClient(t, p, nil)
	defer cancelRun()

	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			glob := fmt.Sprintf("topic%d.*", i)
			for round := 0; round < 3; round++ {
				ch, cancel, err := c.Subscribe(context.Background(), SubscribeSpec{TopicGlob: glob})
				if err != nil {
					continue
				}
				go func() {
					for range ch {
					}
				}()
				p.Push(fmt.Sprintf("topic%d.hit", i), map[string]any{"round": round})
				cancel()
			}
		}(i)
	}

	// Push against every topic while subscriptions come and go.
	stop := make(chan struct{})
	go func() {
		for {
			select {
			case <-stop:
				return
			default:
			}
			for i := 0; i < 8; i++ {
				p.Push(fmt.Sprintf("topic%d.hit", i), map[string]any{"bg": true})
			}
		}
	}()
	wg.Wait()
	close(stop)
}
