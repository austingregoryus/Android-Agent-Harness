package mcpserver

import (
	"context"
	"net/http"
	"sync/atomic"
	"testing"
	"time"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/device"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/fakephone"
)

func callTool(s *pipeSession, id int, name string, args map[string]any, meta map[string]any) map[string]any {
	s.t.Helper()
	params := map[string]any{"name": name, "arguments": args}
	if meta != nil {
		params["_meta"] = meta
	}
	s.send(map[string]any{"jsonrpc": "2.0", "id": id, "method": "tools/call", "params": params})
	return s.readID(float64(id))
}

func toolResult(t *testing.T, resp map[string]any) (map[string]any, map[string]any) {
	t.Helper()
	if e, ok := resp["error"]; ok {
		t.Fatalf("unexpected JSON-RPC error: %v", e)
	}
	result := resp["result"].(map[string]any)
	sc, _ := result["structuredContent"].(map[string]any)
	return result, sc
}

// TestLostResponseIsOutcomeUnknownAndClientRetryIsIdempotent is the H4
// regression. The phone executes messaging.send_sms but the response never
// arrives. The server used to answer JSON-RPC -32603, exactly as for a call
// that never left the gateway, and minted a fresh correlation id per
// tools/call, so the client's retry carried a different idempotency key and the
// SMS went out twice. Now the client is told the outcome is unknown, and a
// retry that reuses its own params._meta.correlationId is deduplicated.
func TestLostResponseIsOutcomeUnknownAndClientRetryIsIdempotent(t *testing.T) {
	var sent atomic.Int32
	p := fakephone.New(fakephone.Options{Token: "tok", ToolHandler: func(name string, args, meta map[string]any) map[string]any {
		if name == "messaging.send_sms" {
			sent.Add(1)
		}
		return fakephone.DefaultToolHandler(name, args, meta)
	}})
	defer p.Close()
	s := newSession(t, phoneOpts(t, p, nil))

	const corr = "corr_client0000000001"
	args := map[string]any{"to": "+15550001234", "text": "hello"}
	p.FailResponseOnce("messaging.send_sms")

	result, sc := toolResult(t, callTool(s, 1, "messaging.send_sms", args, map[string]any{"correlationId": corr}))
	if result["isError"] != true || sc["error"] != errTransportOutcomeUnknown {
		t.Fatalf("lost response = %v, want an isError transport.outcome_unknown result", result)
	}
	wantKey, err := device.IdempotencyKey(corr, "messaging.send_sms", args)
	if err != nil {
		t.Fatal(err)
	}
	if sc["correlation_id"] != corr || sc["idempotency_key"] != wantKey {
		t.Errorf("structuredContent = %v, want correlation_id %s and idempotency_key %s", sc, corr, wantKey)
	}

	result, sc = toolResult(t, callTool(s, 2, "messaging.send_sms", args, map[string]any{"correlationId": corr}))
	if result["isError"] == true || sc["sent"] != true {
		t.Fatalf("retry = %v, want the stored success", result)
	}
	if got := sent.Load(); got != 1 {
		t.Fatalf("messaging.send_sms executed %d times, want 1", got)
	}
	calls := p.Calls()
	if len(calls) != 2 || calls[0].Meta["idempotencyKey"] != calls[1].Meta["idempotencyKey"] {
		t.Errorf("retry did not reuse the idempotency key: %+v", calls)
	}
}

// TestUnsentCallIsNotSent covers the other side of H4: a failure before the
// request was written (the phone is gone, the dial is refused) is reported as
// transport.not_sent, so the client knows nothing happened.
func TestUnsentCallIsNotSent(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	opts := phoneOpts(t, p, nil)
	// No pooled connection may outlive the phone, so the next call has to dial.
	opts.Device = device.NewMCPClient(device.MCPOptions{BaseURL: p.URL(), Token: "tok",
		HTTPClient: &http.Client{Transport: &http.Transport{DisableKeepAlives: true}}})
	s := newSession(t, opts)

	s.send(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
	if resp := s.readID(1); resp["error"] != nil {
		t.Fatalf("tools/list: %v", resp["error"])
	}
	p.Close()

	result, sc := toolResult(t, callTool(s, 2, "messaging.send_sms", map[string]any{"to": "+1555", "text": "x"}, nil))
	if result["isError"] != true || sc["error"] != errTransportNotSent {
		t.Fatalf("unsent call = %v, want an isError transport.not_sent result", result)
	}
}

// TestInvalidPhoneResultIsAnError is the M1 regression: a result without
// structuredContent is decoded as protocol.invalid_result (outcome unknown),
// but the server still returned the raw envelope, so the client saw success.
func TestInvalidPhoneResultIsAnError(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok", ToolHandler: func(name string, args, meta map[string]any) map[string]any {
		return map[string]any{"content": []any{map[string]any{"type": "text", "text": "ok"}}}
	}})
	defer p.Close()
	s := newSession(t, phoneOpts(t, p, nil))

	result, sc := toolResult(t, callTool(s, 1, "messaging.send_sms", map[string]any{"to": "+1555", "text": "x"}, nil))
	if result["isError"] != true || sc["error"] != device.ErrInvalidResult {
		t.Fatalf("invalid phone result = %v, want an isError protocol.invalid_result", result)
	}
	if corr, _ := sc["correlation_id"].(string); corr == "" {
		t.Errorf("invalid result carries no correlation id: %v", sc)
	}
}

// TestClientCorrelationIDIsValidated checks that a malformed
// params._meta.correlationId is refused before anything reaches the phone.
func TestClientCorrelationIDIsValidated(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	s := newSession(t, phoneOpts(t, p, nil))
	for i, bad := range []any{"corr_short", "nope_0000000000000000", "corr_has space 00000", 42} {
		resp := callTool(s, 10+i, "messaging.send_sms", map[string]any{"to": "+1555", "text": "x"},
			map[string]any{"correlationId": bad})
		e, ok := resp["error"].(map[string]any)
		if !ok || e["code"] != float64(codeInvalidParams) {
			t.Errorf("correlationId %v: response %v, want -32602", bad, resp)
		}
	}
	if n := len(p.Calls()); n != 0 {
		t.Errorf("phone saw %d calls, want 0", n)
	}
	// A well-formed id is passed to the phone verbatim.
	const corr = "corr_0123456789abcdef"
	if _, sc := toolResult(t, callTool(s, 20, "device.get_battery", map[string]any{}, map[string]any{"correlationId": corr})); sc["percent"] == nil {
		t.Fatalf("battery = %v", sc)
	}
	if calls := p.Calls(); len(calls) != 1 || calls[0].Meta["correlationId"] != corr {
		t.Errorf("phone calls = %+v, want correlationId %s", calls, corr)
	}
}

// TestConcurrentWaitsOnTheSameTopic is the M7 regression: the MCP server
// handles requests concurrently, and a second gateway.wait_for_event with the
// same topic_glob used to fail with events.subscribe_failed: already
// subscribed. Each wait now has its own subscription and both see the event.
func TestConcurrentWaitsOnTheSameTopic(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	ec := startEvents(t, p)
	s := newSession(t, phoneOpts(t, p, ec))

	for id := 1; id <= 2; id++ {
		s.send(map[string]any{"jsonrpc": "2.0", "id": id, "method": "tools/call",
			"params": map[string]any{"name": ToolWaitForEvent,
				"arguments": map[string]any{"topic_glob": "messaging.*", "timeout_ms": 8000}}})
	}
	deadline := time.Now().Add(5 * time.Second)
	for len(p.SubscriptionIDs()) < 2 {
		if time.Now().After(deadline) {
			t.Fatalf("phone holds %d subscriptions, want 2", len(p.SubscriptionIDs()))
		}
		time.Sleep(10 * time.Millisecond)
	}
	ev := p.Push("messaging.sms_received", map[string]any{"text": "code 123456"})

	for id := 1; id <= 2; id++ {
		result, sc := toolResult(t, s.readID(float64(id)))
		if result["isError"] == true {
			t.Fatalf("wait %d failed: %v", id, result)
		}
		if sc["id"] != float64(ev.ID) {
			t.Errorf("wait %d returned event %v, want %d", id, sc["id"], ev.ID)
		}
	}
}

// TestWaitForEventIgnoresAStaleBacklog is M4 through the MCP surface: a
// gateway that crashed mid-wait left its subscription and cursor behind, and a
// message arrived meanwhile. A new wait must not return that message as new.
func TestWaitForEventIgnoresAStaleBacklog(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	st := device.NewMemoryCursorStore()

	crashed := device.NewEventClient(device.EventOptions{BaseURL: p.URL(), Token: "tok", Store: st})
	ctx, cancel := context.WithCancel(context.Background())
	go func() { _ = crashed.Run(ctx) }()
	wctx, wcancel := context.WithTimeout(ctx, 5*time.Second)
	defer wcancel()
	if err := crashed.WaitConnected(wctx); err != nil {
		t.Fatal(err)
	}
	if _, _, err := crashed.Subscribe(wctx, device.SubscribeSpec{TopicGlob: "messaging.*"}); err != nil {
		t.Fatal(err)
	}
	crashed.Close()
	cancel()
	p.Push("messaging.sms_received", map[string]any{"text": "stale"})

	ec := device.NewEventClient(device.EventOptions{BaseURL: p.URL(), Token: "tok", Store: st})
	ectx, ecancel := context.WithCancel(context.Background())
	defer ecancel()
	go func() { _ = ec.Run(ectx) }()
	defer ec.Close()
	s := newSession(t, phoneOpts(t, p, ec))

	go func() {
		deadline := time.Now().Add(5 * time.Second)
		for time.Now().Before(deadline) {
			// The crashed gateway's subscription plus the wait's own.
			if len(p.SubscriptionIDs()) >= 2 {
				p.Push("messaging.sms_received", map[string]any{"text": "fresh"})
				return
			}
			time.Sleep(10 * time.Millisecond)
		}
	}()
	result, sc := toolResult(t, callTool(s, 1, ToolWaitForEvent, map[string]any{"topic_glob": "messaging.*", "timeout_ms": 8000}, nil))
	if result["isError"] == true {
		t.Fatalf("wait failed: %v", result)
	}
	if payload := sc["payload"].(map[string]any); payload["text"] != "fresh" {
		t.Fatalf("wait returned %v, want only the fresh message", payload)
	}
}
