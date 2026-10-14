package mcpserver

import (
	"context"
	"testing"
	"time"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/device"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/fakephone"
)

// startEvents brings up an event client attached to a fake phone and waits for
// its subscriptions to be attached.
func startEvents(t *testing.T, p *fakephone.Phone) *device.EventClient {
	t.Helper()
	ec := device.NewEventClient(device.EventOptions{BaseURL: p.URL(), Token: "tok"})
	ctx, cancel := context.WithCancel(context.Background())
	go func() { _ = ec.Run(ctx) }()
	t.Cleanup(func() {
		ec.Close()
		cancel()
	})
	wctx, wcancel := context.WithTimeout(ctx, 10*time.Second)
	defer wcancel()
	if err := ec.WaitConnected(wctx); err != nil {
		t.Fatalf("WaitConnected: %v", err)
	}
	return ec
}

// TestPingIsNotBlockedByALongWait is the P0-2 regression. The stdio server used
// to dispatch on the reader goroutine, so a gateway.wait_for_event with a ten
// minute timeout froze ping, tools/list, every other tools/call and even
// notifications/cancelled for that whole window — the MCP host would conclude
// the server had hung.
func TestPingIsNotBlockedByALongWait(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	ec := startEvents(t, p)
	s := newSession(t, phoneOpts(t, p, ec))

	// A wait that will not complete on its own inside this test.
	s.send(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "tools/call",
		"params": map[string]any{
			"name":      ToolWaitForEvent,
			"arguments": map[string]any{"topic_glob": "call.*", "timeout_ms": 600000},
		}})

	// Give the wait a moment to be dispatched, then prove the session is alive.
	time.Sleep(50 * time.Millisecond)
	s.send(map[string]any{"jsonrpc": "2.0", "id": 2, "method": "ping"})
	pong := s.readIDWithin(2, 200*time.Millisecond)
	if _, ok := pong["result"]; !ok {
		t.Fatalf("ping did not succeed while a wait was in flight: %v", pong)
	}

	// tools/list must be equally unaffected.
	s.send(map[string]any{"jsonrpc": "2.0", "id": 3, "method": "tools/list"})
	list := s.readIDWithin(3, 3*time.Second)
	if list["error"] != nil {
		t.Fatalf("tools/list failed while a wait was in flight: %v", list["error"])
	}

	// notifications/cancelled must reach the blocked request and end it.
	s.send(map[string]any{"jsonrpc": "2.0", "method": "notifications/cancelled",
		"params": map[string]any{"requestId": 1, "reason": "user cancelled"}})

	resp := s.readIDWithin(1, 3*time.Second)
	if resp["error"] != nil {
		t.Fatalf("cancelled wait returned a JSON-RPC error rather than a tool result: %v", resp["error"])
	}
	result, ok := resp["result"].(map[string]any)
	if !ok {
		t.Fatalf("cancelled wait returned no result: %v", resp)
	}
	if result["isError"] != true {
		t.Fatalf("a cancelled wait must report an error result, got %v", result)
	}
	sc, _ := result["structuredContent"].(map[string]any)
	if sc["error"] != "events.cancelled" {
		t.Errorf("error code = %v, want events.cancelled", sc["error"])
	}
}

// TestConcurrentToolCallsAreNotSerialized checks that two independent calls can
// be in flight at once and that responses are matched by id, not by arrival
// order.
func TestConcurrentToolCallsAreNotSerialized(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	ec := startEvents(t, p)
	s := newSession(t, phoneOpts(t, p, ec))

	// A wait that times out on its own, and a phone tool call behind it.
	s.send(map[string]any{"jsonrpc": "2.0", "id": 10, "method": "tools/call",
		"params": map[string]any{
			"name":      ToolWaitForEvent,
			"arguments": map[string]any{"topic_glob": "call.*", "timeout_ms": 1500},
		}})
	time.Sleep(50 * time.Millisecond)
	s.send(map[string]any{"jsonrpc": "2.0", "id": 11, "method": "tools/call",
		"params": map[string]any{"name": "device.get_battery", "arguments": map[string]any{}}})

	// The battery call must land well before the wait's own timeout.
	battery := s.readIDWithin(11, 1*time.Second)
	if battery["error"] != nil {
		t.Fatalf("battery call failed: %v", battery["error"])
	}
	if result := battery["result"].(map[string]any); result["isError"] == true {
		t.Fatalf("battery call reported an error result: %v", result)
	}

	// The wait still gets its own response afterwards.
	wait := s.readIDWithin(10, 5*time.Second)
	if wait["result"] == nil {
		t.Fatalf("the wait never answered: %v", wait)
	}
}

// TestRequestsBeforeInitializeAreRefused enforces the MCP handshake ordering.
func TestRequestsBeforeInitializeAreRefused(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	s := newRawSession(t, phoneOpts(t, p, nil))

	for _, method := range []string{"ping", "tools/list", "tools/call"} {
		s.send(map[string]any{"jsonrpc": "2.0", "id": 1, "method": method,
			"params": map[string]any{"name": "device.get_battery"}})
		resp := s.read()
		e, ok := resp["error"].(map[string]any)
		if !ok {
			t.Fatalf("%s before initialize was accepted: %v", method, resp)
		}
		if e["code"] != float64(codeInvalidRequest) {
			t.Errorf("%s: code = %v, want %d", method, e["code"], codeInvalidRequest)
		}
	}

	// After the handshake the same request works.
	s.send(map[string]any{"jsonrpc": "2.0", "id": 2, "method": "initialize",
		"params": map[string]any{"protocolVersion": ProtocolVersion}})
	if resp := s.readID(2); resp["error"] != nil {
		t.Fatalf("initialize failed: %v", resp["error"])
	}
	s.send(map[string]any{"jsonrpc": "2.0", "id": 3, "method": "ping"})
	if resp := s.readID(3); resp["error"] != nil {
		t.Fatalf("ping after initialize failed: %v", resp["error"])
	}
}

// TestCancelledNotificationForUnknownIDIsIgnored checks the notification path is
// tolerant: a cancel for a request that already finished must not panic or
// produce a response.
func TestCancelledNotificationForUnknownIDIsIgnored(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	s := newSession(t, phoneOpts(t, p, nil))

	for _, params := range []any{
		map[string]any{"requestId": 4242, "reason": "gone"},
		map[string]any{"reason": "no id at all"},
		nil,
	} {
		msg := map[string]any{"jsonrpc": "2.0", "method": "notifications/cancelled"}
		if params != nil {
			msg["params"] = params
		}
		s.send(msg)
	}

	s.send(map[string]any{"jsonrpc": "2.0", "id": 7, "method": "ping"})
	resp := s.readIDWithin(7, 2*time.Second)
	if resp["error"] != nil {
		t.Fatalf("session did not survive stray cancellations: %v", resp["error"])
	}
}
