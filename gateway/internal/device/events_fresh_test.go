package device

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/fakephone"
)

// TestEphemeralSubscriptionIgnoresStoredBacklog is the M4 regression for
// gateway.wait_for_event: it used to reuse the (subscription id, cursor) stored
// for its spec, so after a crash a new wait replayed the old subscription's
// backlog and returned a stale SMS as if it had just arrived.
func TestEphemeralSubscriptionIgnoresStoredBacklog(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	st := NewMemoryCursorStore()
	spec := SubscribeSpec{TopicGlob: "messaging.*"}

	// A gateway subscribes durably, then dies without unsubscribing: the row
	// and the phone-side subscription both survive, and a message arrives for
	// nobody.
	crashed := NewEventClient(EventOptions{BaseURL: p.URL(), Token: "tok", Store: st})
	ctx, cancel := context.WithCancel(context.Background())
	go func() { _ = crashed.Run(ctx) }()
	wctx, wcancel := context.WithTimeout(ctx, recvTimeout)
	defer wcancel()
	if err := crashed.WaitConnected(wctx); err != nil {
		t.Fatal(err)
	}
	if _, _, err := crashed.Subscribe(wctx, spec); err != nil {
		t.Fatal(err)
	}
	crashed.Close()
	cancel()
	waitFor(t, 3*time.Second, func() bool { return p.ConnCount() == 0 })
	stale := p.Push("messaging.sms_received", map[string]any{"text": "stale"})

	c, _ := startClient(t, p, st)
	wctx2, wcancel2 := context.WithTimeout(context.Background(), recvTimeout)
	defer wcancel2()
	if err := c.WaitConnected(wctx2); err != nil {
		t.Fatal(err)
	}
	fresh := spec
	fresh.Ephemeral = true
	ch, unsub, err := c.Subscribe(wctx2, fresh)
	if err != nil {
		t.Fatalf("Subscribe: %v", err)
	}
	expectNoEvent(t, ch, 300*time.Millisecond)

	next := p.Push("messaging.sms_received", map[string]any{"text": "fresh"})
	got := recv(t, ch)
	if got.ID == stale.ID || got.ID != next.ID {
		t.Fatalf("ephemeral subscription delivered id %d, want only the fresh event %d", got.ID, next.ID)
	}
	if ids := p.SubscriptionIDs(); len(ids) != 2 {
		t.Errorf("phone subscriptions = %v, want the crashed one plus a fresh one", ids)
	}
	unsub()

	// The ephemeral subscription never touched the durable row.
	if id, _, _ := st.LoadSubscription(spec.Key()); id == "" {
		t.Error("the durable row was removed by an ephemeral subscription")
	}
}

// TestDurableUnsubscribeDeletesStoredCursor covers the other half of M4:
// cancelling a subscription deletes its phone-side subscription, so its stored
// row must go too, or the next subscription to the spec resumes a dead id.
func TestDurableUnsubscribeDeletesStoredCursor(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	st := NewMemoryCursorStore()
	c, _ := startClient(t, p, st)
	ctx, cancel := context.WithTimeout(context.Background(), recvTimeout)
	defer cancel()
	if err := c.WaitConnected(ctx); err != nil {
		t.Fatal(err)
	}
	spec := SubscribeSpec{TopicGlob: "*"}
	ch, unsub, err := c.Subscribe(ctx, spec)
	if err != nil {
		t.Fatal(err)
	}
	p.Push("battery.changed", nil)
	recv(t, ch)
	if id, _, _ := st.LoadSubscription(spec.Key()); id == "" {
		t.Fatal("durable subscription was not persisted")
	}
	unsub()
	if id, cursor, _ := st.LoadSubscription(spec.Key()); id != "" || cursor != 0 {
		t.Errorf("row after unsubscribe = (%q, %d), want it deleted", id, cursor)
	}
}

// TestJournalResetBelowCursorIsDetectedFromHello covers M4's journal reset:
// the phone's journal restarts at id 1 while the subscription survives, and the
// gateway's cursor (3) is above anything the journal now holds. hello's
// last_event_id reveals that, and the cursor restarts, so the new event 1 is
// delivered instead of being dropped as a duplicate.
func TestJournalResetBelowCursorIsDetectedFromHello(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	st := NewMemoryCursorStore()
	c, _ := startClient(t, p, st)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := c.WaitConnected(ctx); err != nil {
		t.Fatal(err)
	}
	spec := SubscribeSpec{TopicGlob: "*"}
	ch, unsub, err := c.Subscribe(ctx, spec)
	if err != nil {
		t.Fatal(err)
	}
	defer unsub()
	for i := 0; i < 3; i++ {
		p.Push("battery.changed", map[string]any{"n": i})
		recv(t, ch)
	}
	subID := p.SubscriptionIDs()[0]
	waitFor(t, 3*time.Second, func() bool { return p.AckCursor(subID) >= 3 })

	p.ResetJournal(false)
	// Pushed live on the old socket, this one is dropped as a duplicate (its id
	// is below the cursor). The reconnect must recover it.
	reborn := p.Push("battery.changed", map[string]any{"n": "after reset"})
	if reborn.ID != 1 {
		t.Fatalf("journal did not restart: id %d", reborn.ID)
	}
	drain(c)
	p.ForceDrop()

	got := recv(t, ch)
	if got.ID != reborn.ID || got.Payload["n"] != "after reset" {
		t.Fatalf("after the journal reset got %+v, want the new event 1", got)
	}
	if !sawNotice(c, NoticeJournalReset, 3*time.Second) {
		t.Error("expected a journal_reset notice")
	}
	waitFor(t, 3*time.Second, func() bool {
		_, cursor, _ := st.LoadSubscription(spec.Key())
		return cursor == reborn.ID
	})
}

// TestNewSubscriptionIDResetsCursor covers M4's other reset path, on a runtime
// that sends no hello: the phone forgot the subscription (app data cleared), so
// attach creates a new one. Its cursor starts at the new subscription's
// creation point; carrying the old, higher cursor over dropped every new event.
func TestNewSubscriptionIDResetsCursor(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok", Legacy: true})
	defer p.Close()
	st := NewMemoryCursorStore()
	c, _ := startClient(t, p, st)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := c.WaitConnected(ctx); err != nil {
		t.Fatal(err)
	}
	spec := SubscribeSpec{TopicGlob: "*"}
	ch, unsub, err := c.Subscribe(ctx, spec)
	if err != nil {
		t.Fatal(err)
	}
	defer unsub()
	for i := 0; i < 3; i++ {
		p.Push("battery.changed", map[string]any{"n": i})
		recv(t, ch)
	}
	oldID, _, _ := st.LoadSubscription(spec.Key())

	p.ResetJournal(true)
	drain(c)
	p.ForceDrop()
	if !sawNotice(c, NoticeConnected, 5*time.Second) {
		t.Fatal("client did not re-attach")
	}
	ids := p.SubscriptionIDs()
	if len(ids) != 1 || ids[0] == oldID {
		t.Fatalf("phone subscriptions after reset = %v, want one new subscription", ids)
	}

	ev := p.Push("battery.changed", map[string]any{"n": "new journal"})
	if got := recv(t, ch); got.ID != ev.ID {
		t.Fatalf("got id %d, want %d", got.ID, ev.ID)
	}
	waitFor(t, 3*time.Second, func() bool {
		id, cursor, _ := st.LoadSubscription(spec.Key())
		return id == ids[0] && cursor == ev.ID
	})
}

// TestConcurrentEphemeralSubscriptionsWithSameSpec is the M7 regression: the
// local key used to be the spec alone, so a second concurrent wait on the same
// topic_glob failed with "already subscribed". Each must get its own
// subscription and see the event.
func TestConcurrentEphemeralSubscriptionsWithSameSpec(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c, _ := startClient(t, p, nil)
	ctx, cancel := context.WithTimeout(context.Background(), recvTimeout)
	defer cancel()
	if err := c.WaitConnected(ctx); err != nil {
		t.Fatal(err)
	}

	const waiters = 4
	spec := SubscribeSpec{TopicGlob: "messaging.*", Ephemeral: true}
	chans := make([]<-chan Event, waiters)
	var wg sync.WaitGroup
	errs := make(chan error, waiters)
	for i := 0; i < waiters; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			ch, unsub, err := c.Subscribe(ctx, spec)
			if err != nil {
				errs <- err
				return
			}
			t.Cleanup(unsub)
			chans[i] = ch
		}(i)
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Fatalf("concurrent Subscribe with the same spec: %v", err)
	}
	if ids := p.SubscriptionIDs(); len(ids) != waiters {
		t.Fatalf("phone subscriptions = %d, want %d", len(ids), waiters)
	}

	ev := p.Push("messaging.sms_received", map[string]any{"text": "hi"})
	for i, ch := range chans {
		if got := recv(t, ch); got.ID != ev.ID {
			t.Errorf("waiter %d got id %d, want %d", i, got.ID, ev.ID)
		}
	}
}
