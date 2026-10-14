package device

import (
	"context"
	"sync"
	"testing"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/fakephone"
)

// TestEnsureSessionIsSingleFlight is the P1-8 regression. ensureSession checked
// and then initialized without holding anything, so concurrent callers each
// opened their own session on the phone and all but the last were orphaned. The
// stdio MCP server now dispatches requests in parallel, so this is reachable in
// normal use, not just under a stress test.
func TestEnsureSessionIsSingleFlight(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c := NewMCPClient(MCPOptions{BaseURL: p.URL(), Token: "tok"})

	var wg sync.WaitGroup
	errs := make(chan error, 16)
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := c.ListTools(context.Background()); err != nil {
				errs <- err
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Fatalf("ListTools: %v", err)
	}

	if n := p.SessionCount(); n != 1 {
		t.Fatalf("phone holds %d sessions, want 1 (concurrent callers double-initialized)", n)
	}
	if c.SessionID() == "" {
		t.Error("no session id was recorded")
	}
}

// TestExpiredSessionIsReinitializedOnce checks the 404 recovery path is also
// single-flight: many callers hitting a dead session must produce one new one.
func TestExpiredSessionIsReinitializedOnce(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c := NewMCPClient(MCPOptions{BaseURL: p.URL(), Token: "tok"})

	if _, err := c.ListTools(context.Background()); err != nil {
		t.Fatalf("initial ListTools: %v", err)
	}
	first := c.SessionID()
	p.ExpireSessions()

	var wg sync.WaitGroup
	errs := make(chan error, 8)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := c.ListTools(context.Background()); err != nil {
				errs <- err
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Fatalf("ListTools after expiry: %v", err)
	}

	// SessionCount is cumulative: one for the initial handshake plus exactly one
	// re-initialize, however many callers saw the 404 at once.
	if n := p.SessionCount(); n != 2 {
		t.Fatalf("phone created %d sessions in total, want 2 (the expiry was recovered more than once)", n)
	}
	if c.SessionID() == first || c.SessionID() == "" {
		t.Errorf("session id was not refreshed: %q", c.SessionID())
	}
}
