package device

import (
	"context"
	"testing"
	"time"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/fakephone"
)

func TestOverflowCannotAcknowledgePastMissingEvent(t *testing.T) {
	c := NewEventClient(EventOptions{})
	defer c.Close()
	sub := &subscription{key: "test", subID: "sub", out: make(chan Event, 1)}
	c.subs[sub.key] = sub
	c.deliver(context.Background(), "sub", Event{ID: 1})
	c.deliver(context.Background(), "sub", Event{ID: 2}) // full buffer
	<-sub.out
	c.deliver(context.Background(), "sub", Event{ID: 3}) // space, but event 2 still missing
	if sub.ackCursor != 1 || sub.lastSeen != 1 || len(sub.out) != 0 {
		t.Fatalf("advanced past an undelivered event: ack=%d lastSeen=%d queued=%d", sub.ackCursor, sub.lastSeen, len(sub.out))
	}
}

func TestOverflowRecoversAllEventsWhenConsumerResumes(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c, stop := startClient(t, p, nil)
	defer stop()
	waitCtx, waitCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer waitCancel()
	if err := c.WaitConnected(waitCtx); err != nil {
		t.Fatal(err)
	}
	ch, cancel, err := c.Subscribe(context.Background(), SubscribeSpec{TopicGlob: "*"})
	if err != nil {
		t.Fatal(err)
	}
	defer cancel()
	for i := 0; i < 600; i++ {
		p.Push("test.event", nil)
	}
	if !sawNotice(c, NoticeGap, 5*time.Second) {
		t.Fatal("expected overflow")
	}
	assertEventSequence(t, ch, 600)
}

func TestReconnectReplaysEveryPage(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c, stop := startClient(t, p, nil)
	defer stop()
	waitCtx, waitCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer waitCancel()
	if err := c.WaitConnected(waitCtx); err != nil {
		t.Fatal(err)
	}
	ch, cancel, err := c.Subscribe(context.Background(), SubscribeSpec{TopicGlob: "*"})
	if err != nil {
		t.Fatal(err)
	}
	defer cancel()
	p.ForceDrop()
	for i := 0; i < 600; i++ {
		p.Push("test.event", nil)
	}
	assertEventSequence(t, ch, 600)
}

func assertEventSequence(t *testing.T, ch <-chan Event, count int) {
	t.Helper()
	deadline := time.After(10 * time.Second)
	for id := 1; id <= count; id++ {
		select {
		case ev := <-ch:
			if ev.ID != int64(id) {
				t.Fatalf("event ID = %d, want %d", ev.ID, id)
			}
		case <-deadline:
			t.Fatalf("stopped replaying before event %d of %d", id, count)
		}
	}
}
