package device

import (
	"context"
	"strings"
	"testing"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/fakephone"
)

// TestSubscribeDeniedByErrorFrameLeavesNoSubscription is test gap 8: the phone
// answers "subscribe" with an "error" frame - e.g. policy.denied for an
// out-of-scope topic - instead of "subscribed". Subscribe must surface that as
// an error carrying the code, and must not leave anything behind: no local
// subscription entry (which would otherwise be retried forever) and nothing
// registered phone-side, including across a reconnect.
func TestSubscribeDeniedByErrorFrameLeavesNoSubscription(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	p.DenySubscribe("policy.restricted", "policy.denied", "topic out of scope for this peer")

	c, _ := startClient(t, p, nil)

	ctx, cancel := context.WithTimeout(context.Background(), recvTimeout)
	defer cancel()
	if err := c.WaitConnected(ctx); err != nil {
		t.Fatalf("WaitConnected: %v", err)
	}

	ch, unsub, err := c.Subscribe(ctx, SubscribeSpec{TopicGlob: "policy.restricted"})
	if err == nil {
		if unsub != nil {
			unsub()
		}
		t.Fatal("Subscribe succeeded, want an error carrying the policy.denied code")
	}
	if !strings.Contains(err.Error(), "policy.denied") {
		t.Errorf("error = %v, want it to carry the policy.denied code", err)
	}
	if ch != nil {
		t.Errorf("Subscribe returned a non-nil channel alongside its error: %v", ch)
	}
	if unsub != nil {
		t.Errorf("Subscribe returned a non-nil cancel func alongside its error")
	}

	// Nothing was created phone-side, and exactly one subscribe attempt was
	// denied so far.
	if ids := p.SubscriptionIDs(); len(ids) != 0 {
		t.Fatalf("phone still lists %d subscription(s) after a denied subscribe: %v", len(ids), ids)
	}
	if got := p.DeniedSubscribeAttempts(); got != 1 {
		t.Fatalf("denied subscribe attempts = %d, want 1", got)
	}

	// Force a reconnect. If the failed Subscribe had left the subscription
	// registered locally, attachAll would retry it here and the phone would see
	// a second denied "subscribe" frame.
	p.ForceDrop()
	ctx2, cancel2 := context.WithTimeout(context.Background(), recvTimeout)
	defer cancel2()
	if err := c.WaitConnected(ctx2); err != nil {
		t.Fatalf("WaitConnected after reconnect: %v", err)
	}

	if got := p.DeniedSubscribeAttempts(); got != 1 {
		t.Errorf("denied subscribe attempts after reconnect = %d, want still 1 (nothing local left to retry)", got)
	}
	if ids := p.SubscriptionIDs(); len(ids) != 0 {
		t.Errorf("phone lists %d subscription(s) after reconnect: %v", len(ids), ids)
	}
}
