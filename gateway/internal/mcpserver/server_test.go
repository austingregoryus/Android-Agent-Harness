package mcpserver

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"strings"
	"testing"
	"time"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/device"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/fakephone"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/provider"
)

// pipeSession runs the stdio server over an in-memory pipe pair, mimicking how
// Claude Code launches it.
type pipeSession struct {
	t      *testing.T
	in     *io.PipeWriter
	out    *bufio.Reader
	done   chan error
	cancel context.CancelFunc

	// pending holds responses read out of order while waiting for a specific id.
	pending map[float64]map[string]any
}

// newSession starts a server and completes the MCP handshake, which every other
// request now depends on.
func newSession(t *testing.T, opts Options) *pipeSession {
	t.Helper()
	s := newRawSession(t, opts)
	s.send(map[string]any{"jsonrpc": "2.0", "id": handshakeID, "method": "initialize",
		"params": map[string]any{"protocolVersion": ProtocolVersion}})
	if resp := s.readID(handshakeID); resp["error"] != nil {
		t.Fatalf("handshake failed: %v", resp["error"])
	}
	return s
}

// handshakeID is the request id newSession uses for its initialize call. Tests
// pick their own ids from 1 upward, so it cannot collide.
const handshakeID = 9000

// newRawSession starts a server without performing the handshake.
func newRawSession(t *testing.T, opts Options) *pipeSession {
	t.Helper()
	srv, err := New(opts)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	inR, inW := io.Pipe()
	outR, outW := io.Pipe()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		err := srv.Serve(ctx, inR, outW)
		outW.CloseWithError(io.EOF)
		done <- err
	}()
	s := &pipeSession{t: t, in: inW, out: bufio.NewReader(outR), done: done, cancel: cancel,
		pending: map[float64]map[string]any{}}
	t.Cleanup(func() {
		inW.Close()
		cancel()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
		}
	})
	return s
}

func (s *pipeSession) send(v any) {
	s.t.Helper()
	raw, err := json.Marshal(v)
	if err != nil {
		s.t.Fatal(err)
	}
	if _, err := s.in.Write(append(raw, '\n')); err != nil {
		s.t.Fatalf("write: %v", err)
	}
}

func (s *pipeSession) read() map[string]any {
	s.t.Helper()
	type result struct {
		line string
		err  error
	}
	ch := make(chan result, 1)
	go func() {
		line, err := s.out.ReadString('\n')
		ch <- result{line, err}
	}()
	select {
	case r := <-ch:
		if r.err != nil && strings.TrimSpace(r.line) == "" {
			s.t.Fatalf("read: %v", r.err)
		}
		var m map[string]any
		if err := json.Unmarshal([]byte(r.line), &m); err != nil {
			s.t.Fatalf("decode %q: %v", r.line, err)
		}
		return m
	case <-time.After(10 * time.Second):
		s.t.Fatal("timed out waiting for a response")
		return nil
	}
}

// readID reads until the response with the given id arrives. Requests are now
// dispatched concurrently, so responses may not come back in request order.
func (s *pipeSession) readID(id float64) map[string]any {
	s.t.Helper()
	if resp, ok := s.pending[id]; ok {
		delete(s.pending, id)
		return resp
	}
	for i := 0; i < 32; i++ {
		resp := s.read()
		got, ok := resp["id"].(float64)
		if !ok {
			s.t.Fatalf("response carried no numeric id: %v", resp)
		}
		if got == id {
			return resp
		}
		s.pending[got] = resp
	}
	s.t.Fatalf("no response with id %v arrived", id)
	return nil
}

// readIDWithin is readID with a deadline, for asserting that a response is not
// blocked behind another in-flight request.
func (s *pipeSession) readIDWithin(id float64, d time.Duration) map[string]any {
	s.t.Helper()
	type res struct{ m map[string]any }
	ch := make(chan res, 1)
	go func() { ch <- res{s.readID(id)} }()
	select {
	case r := <-ch:
		return r.m
	case <-time.After(d):
		s.t.Fatalf("no response with id %v within %s", id, d)
		return nil
	}
}

func phoneOpts(t *testing.T, p *fakephone.Phone, ev Events) Options {
	t.Helper()
	return Options{
		Device:        device.NewMCPClient(device.MCPOptions{BaseURL: p.URL(), Token: "tok"}),
		Events:        ev,
		DeviceBaseURL: p.URL(),
		Providers: []provider.Provider{
			provider.NewFake(provider.FakeOptions{Name: "fake"}),
		},
	}
}

func TestInitializeAndPing(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	s := newSession(t, phoneOpts(t, p, nil))

	s.send(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "initialize",
		"params": map[string]any{"protocolVersion": ProtocolVersion}})
	resp := s.readID(1)
	if resp["id"].(float64) != 1 {
		t.Fatalf("id = %v", resp["id"])
	}
	result := resp["result"].(map[string]any)
	if result["protocolVersion"] != ProtocolVersion {
		t.Errorf("protocolVersion = %v", result["protocolVersion"])
	}
	info := result["serverInfo"].(map[string]any)
	if info["name"] != ServerName {
		t.Errorf("serverInfo.name = %v", info["name"])
	}

	// A notification must produce no response at all.
	s.send(map[string]any{"jsonrpc": "2.0", "method": "notifications/initialized"})

	s.send(map[string]any{"jsonrpc": "2.0", "id": 2, "method": "ping"})
	resp = s.readID(2)
	if resp["id"].(float64) != 2 {
		t.Fatalf("the initialized notification produced a response; got id %v", resp["id"])
	}
	if len(s.pending) != 0 {
		t.Fatalf("the initialized notification produced a response: %v", s.pending)
	}
	if _, ok := resp["result"]; !ok {
		t.Errorf("ping result missing: %v", resp)
	}
}

func TestToolsListProxiesPhoneToolsPlusGatewayTools(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	s := newSession(t, phoneOpts(t, p, nil))

	s.send(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
	resp := s.read()
	if e, ok := resp["error"]; ok {
		t.Fatalf("tools/list failed: %v", e)
	}
	tools := resp["result"].(map[string]any)["tools"].([]any)

	names := map[string]bool{}
	for _, tool := range tools {
		names[tool.(map[string]any)["name"].(string)] = true
	}
	// Phone tool names pass through verbatim.
	for _, want := range []string{
		"device.get_battery", "device.get_info", "messaging.read_sms",
		"messaging.send_sms", "telephony.place_call", "events.subscribe",
	} {
		if !names[want] {
			t.Errorf("phone tool %q is missing", want)
		}
	}
	for _, want := range []string{ToolWaitForEvent, ToolListDevices, ToolHealth} {
		if !names[want] {
			t.Errorf("gateway tool %q is missing", want)
		}
	}
	if len(tools) != 9 {
		t.Errorf("tool count = %d, want 9 (6 phone + 3 gateway)", len(tools))
	}
}

func TestToolsCallProxiesToPhone(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	s := newSession(t, phoneOpts(t, p, nil))

	tests := []struct {
		name        string
		tool        string
		args        map[string]any
		wantIsError bool
		wantSubstr  string
	}{
		{"read only tool", "device.get_battery", map[string]any{}, false, `"percent":82`},
		{"mutating tool", "messaging.send_sms",
			map[string]any{"to": "+15550001234", "text": "hi"}, false, `"sent":true`},
		{"policy outcome stays a tool result", "telephony.place_call",
			map[string]any{"target": "+15550001234"}, true, "policy.approval_required"},
	}
	for i, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			s.send(map[string]any{"jsonrpc": "2.0", "id": 100 + i, "method": "tools/call",
				"params": map[string]any{"name": tc.tool, "arguments": tc.args}})
			resp := s.read()
			if e, ok := resp["error"]; ok {
				t.Fatalf("unexpected JSON-RPC error: %v", e)
			}
			result := resp["result"].(map[string]any)
			if result["isError"] != tc.wantIsError {
				t.Errorf("isError = %v, want %v", result["isError"], tc.wantIsError)
			}
			raw, _ := json.Marshal(result)
			if !strings.Contains(string(raw), tc.wantSubstr) {
				t.Errorf("result %s does not contain %q", raw, tc.wantSubstr)
			}
		})
	}

	// The proxy must stamp a correlation id on every call.
	for _, c := range p.Calls() {
		corr, _ := c.Meta["correlationId"].(string)
		if !strings.HasPrefix(corr, "corr_") {
			t.Errorf("%s carried correlationId %q", c.Name, corr)
		}
	}
}

func TestUnknownToolAndMethodErrors(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	s := newSession(t, phoneOpts(t, p, nil))

	tests := []struct {
		name     string
		msg      map[string]any
		wantCode float64
	}{
		{"unknown method", map[string]any{"jsonrpc": "2.0", "id": 1, "method": "resources/list"}, codeMethodNotFound},
		{"unknown tool", map[string]any{"jsonrpc": "2.0", "id": 2, "method": "tools/call",
			"params": map[string]any{"name": "files.rm_rf"}}, codeMethodNotFound},
		{"missing tool name", map[string]any{"jsonrpc": "2.0", "id": 3, "method": "tools/call",
			"params": map[string]any{}}, codeInvalidParams},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			s.send(tc.msg)
			resp := s.read()
			e, ok := resp["error"].(map[string]any)
			if !ok {
				t.Fatalf("expected an error, got %v", resp)
			}
			if e["code"] != tc.wantCode {
				t.Errorf("code = %v, want %v", e["code"], tc.wantCode)
			}
		})
	}
}

func TestMalformedLineYieldsParseError(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	s := newSession(t, phoneOpts(t, p, nil))

	if _, err := s.in.Write([]byte("{not json\n")); err != nil {
		t.Fatal(err)
	}
	resp := s.read()
	e := resp["error"].(map[string]any)
	if e["code"] != float64(codeParseError) {
		t.Errorf("code = %v, want %d", e["code"], codeParseError)
	}
}

func TestGatewayHealthAndListDevices(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	s := newSession(t, phoneOpts(t, p, nil))

	s.send(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "tools/call",
		"params": map[string]any{"name": ToolHealth}})
	resp := s.read()
	sc := resp["result"].(map[string]any)["structuredContent"].(map[string]any)
	phone := sc["phone"].(map[string]any)
	if phone["ok"] != true || phone["device_id"] != fakephone.DeviceID {
		t.Errorf("phone health = %v", phone)
	}
	provs := sc["providers"].([]any)
	if len(provs) != 1 || provs[0].(map[string]any)["name"] != "fake" {
		t.Errorf("providers = %v", provs)
	}

	s.send(map[string]any{"jsonrpc": "2.0", "id": 2, "method": "tools/call",
		"params": map[string]any{"name": ToolListDevices}})
	resp = s.read()
	sc = resp["result"].(map[string]any)["structuredContent"].(map[string]any)
	devices := sc["devices"].([]any)
	if len(devices) != 1 {
		t.Fatalf("devices = %v", devices)
	}
	d := devices[0].(map[string]any)
	if d["base_url"] != p.URL() || d["ok"] != true {
		t.Errorf("device = %v", d)
	}
}

func TestWaitForEventReturnsFirstMatch(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()

	ec := device.NewEventClient(device.EventOptions{BaseURL: p.URL(), Token: "tok"})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = ec.Run(ctx) }()
	defer ec.Close()
	wctx, wcancel := context.WithTimeout(ctx, 10*time.Second)
	defer wcancel()
	if err := ec.WaitConnected(wctx); err != nil {
		t.Fatalf("WaitConnected: %v", err)
	}

	s := newSession(t, phoneOpts(t, p, ec))

	// Push the event slightly after the call so the subscription exists first.
	go func() {
		deadline := time.Now().Add(5 * time.Second)
		for time.Now().Before(deadline) {
			if len(p.SubscriptionIDs()) > 0 {
				p.Push("messaging.sms_received", map[string]any{"from": "+15550001234", "text": "code 123456"})
				return
			}
			time.Sleep(20 * time.Millisecond)
		}
	}()

	s.send(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "tools/call",
		"params": map[string]any{
			"name": ToolWaitForEvent,
			"arguments": map[string]any{
				"topic_glob": "messaging.*",
				"timeout_ms": 8000,
			},
		}})
	resp := s.read()
	if e, ok := resp["error"]; ok {
		t.Fatalf("unexpected error: %v", e)
	}
	result := resp["result"].(map[string]any)
	if result["isError"] == true {
		t.Fatalf("wait_for_event failed: %v", result)
	}
	sc := result["structuredContent"].(map[string]any)
	if sc["topic"] != "messaging.sms_received" {
		t.Errorf("topic = %v", sc["topic"])
	}
	payload := sc["payload"].(map[string]any)
	if payload["text"] != "code 123456" {
		t.Errorf("payload = %v", payload)
	}

	// The subscription must have been removed again.
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if len(p.SubscriptionIDs()) == 0 {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Error("wait_for_event did not unsubscribe")
}

func TestWaitForEventTimesOut(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()

	ec := device.NewEventClient(device.EventOptions{BaseURL: p.URL(), Token: "tok"})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = ec.Run(ctx) }()
	defer ec.Close()
	wctx, wcancel := context.WithTimeout(ctx, 10*time.Second)
	defer wcancel()
	if err := ec.WaitConnected(wctx); err != nil {
		t.Fatal(err)
	}

	s := newSession(t, phoneOpts(t, p, ec))
	s.send(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "tools/call",
		"params": map[string]any{
			"name":      ToolWaitForEvent,
			"arguments": map[string]any{"topic_glob": "call.*", "timeout_ms": 300},
		}})
	resp := s.read()
	result := resp["result"].(map[string]any)
	if result["isError"] != true {
		t.Fatalf("expected a timeout tool error, got %v", result)
	}
	sc := result["structuredContent"].(map[string]any)
	if sc["error"] != "events.timeout" {
		t.Errorf("error = %v", sc["error"])
	}
}

func TestWaitForEventWithoutEventChannel(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	s := newSession(t, phoneOpts(t, p, nil))

	s.send(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "tools/call",
		"params": map[string]any{
			"name":      ToolWaitForEvent,
			"arguments": map[string]any{"topic_glob": "*"},
		}})
	resp := s.read()
	result := resp["result"].(map[string]any)
	if result["isError"] != true {
		t.Fatalf("expected a tool error, got %v", result)
	}
	if sc := result["structuredContent"].(map[string]any); sc["error"] != "events.unavailable" {
		t.Errorf("error = %v", sc["error"])
	}
}
