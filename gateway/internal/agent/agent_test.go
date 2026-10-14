package agent

import (
	"context"
	"errors"
	"reflect"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/config"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/device"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/fakephone"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/provider"
)

func newAgent(t *testing.T, p *fakephone.Phone, providers []provider.Provider, cfg config.Agent) *Agent {
	t.Helper()
	dev := device.NewMCPClient(device.MCPOptions{BaseURL: p.URL(), Token: "tok"})
	a, err := New(Options{Device: dev, Providers: providers, Config: cfg})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	return a
}

func TestMatchGlob(t *testing.T) {
	tests := []struct {
		glob, name string
		want       bool
	}{
		{"*", "messaging.send_sms", true},
		{"messaging.*", "messaging.send_sms", true},
		{"messaging.*", "telephony.place_call", false},
		{"device.get_*", "device.get_battery", true},
		{"device.get_*", "device.set_brightness", false},
		{"messaging.send_sms", "messaging.send_sms", true},
		{"messaging.send_sms", "messaging.send_sms_v2", false},
		{"*.send_sms", "messaging.send_sms", true},
		{"*.send_sms", "messaging.read_sms", false},
		{"events.*", "events.subscribe", true},
	}
	for _, tc := range tests {
		t.Run(tc.glob+"|"+tc.name, func(t *testing.T) {
			if got := MatchGlob(tc.glob, tc.name); got != tc.want {
				t.Errorf("MatchGlob(%q, %q) = %v, want %v", tc.glob, tc.name, got, tc.want)
			}
		})
	}
}

func TestFilterTools(t *testing.T) {
	tools := []device.Tool{
		{Name: "device.get_battery"}, {Name: "device.get_info"},
		{Name: "messaging.send_sms"}, {Name: "telephony.place_call"},
	}
	tests := []struct {
		name      string
		allowlist []string
		want      []string
	}{
		{"empty allowlist keeps everything", nil,
			[]string{"device.get_battery", "device.get_info", "messaging.send_sms", "telephony.place_call"}},
		{"single glob", []string{"device.*"}, []string{"device.get_battery", "device.get_info"}},
		{"multiple globs", []string{"device.get_battery", "messaging.*"},
			[]string{"device.get_battery", "messaging.send_sms"}},
		{"no match", []string{"files.*"}, nil},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got := FilterTools(tools, tc.allowlist)
			var names []string
			for _, tool := range got {
				names = append(names, tool.Name)
			}
			if !reflect.DeepEqual(names, tc.want) {
				t.Errorf("got %v, want %v", names, tc.want)
			}
		})
	}
}

func TestCorrelationIDShape(t *testing.T) {
	id := NewCorrelationID("corr_")
	if !strings.HasPrefix(id, "corr_") {
		t.Fatalf("id = %q", id)
	}
	if len(id) != len("corr_")+16 {
		t.Fatalf("id = %q, want a 16 hex digit suffix", id)
	}
	if id == NewCorrelationID("corr_") {
		t.Error("correlation ids must not repeat")
	}
}

func TestSystemPromptCarriesPolicyHints(t *testing.T) {
	prompt := SystemPrompt(
		device.HealthResult{OK: true, DeviceID: "dev_1", Version: "0.1.0"},
		[]device.Tool{{Name: "device.get_battery"}},
	)
	for _, want := range []string{
		"dev_1", "device.get_battery",
		"policy.approval_required", "Do NOT retry",
		"policy.denied", "policy.rate_limited", "terminal",
		"permission.missing", "capability.unavailable",
	} {
		if !strings.Contains(prompt, want) {
			t.Errorf("system prompt is missing %q", want)
		}
	}
}

func TestAgentCompletesThreeToolTask(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()

	fake := provider.NewFake(provider.FakeOptions{
		Name: "fake",
		Script: []provider.ScriptStep{
			{Text: "Checking the battery.", ToolCalls: []provider.Part{
				provider.ToolCallPart("c1", "device.get_battery", map[string]any{}),
			}},
			{ToolCalls: []provider.Part{
				provider.ToolCallPart("c2", "device.get_info", map[string]any{}),
			}},
			{ToolCalls: []provider.Part{
				provider.ToolCallPart("c3", "messaging.send_sms",
					map[string]any{"to": "+15550001234", "text": "battery is 82%"}),
			}},
			{Text: "Battery is 82% and I texted the summary."},
		},
	})

	a := newAgent(t, p, []provider.Provider{fake}, config.Agent{MaxTurns: 10, CorrelationPrefix: "corr_"})
	res, err := a.Run(context.Background(), "check the battery and text it to +15550001234")
	if err != nil {
		t.Fatalf("Run: %v", err)
	}

	if res.Answer != "Battery is 82% and I texted the summary." {
		t.Errorf("answer = %q", res.Answer)
	}
	if len(res.Trace) != 3 {
		t.Fatalf("trace has %d entries, want 3: %+v", len(res.Trace), res.Trace)
	}
	wantTools := []string{"device.get_battery", "device.get_info", "messaging.send_sms"}
	for i, want := range wantTools {
		if res.Trace[i].Name != want {
			t.Errorf("trace[%d].Name = %q, want %q", i, res.Trace[i].Name, want)
		}
		if !res.Trace[i].OK {
			t.Errorf("trace[%d] failed: %+v", i, res.Trace[i])
		}
	}
	if res.Turns != 4 {
		t.Errorf("turns = %d, want 4", res.Turns)
	}

	// Every phone call must carry the task's correlation id.
	calls := p.Calls()
	if len(calls) != 3 {
		t.Fatalf("phone saw %d calls, want 3", len(calls))
	}
	for _, c := range calls {
		if c.Meta["correlationId"] != res.CorrelationID {
			t.Errorf("call %s carried correlationId %v, want %s", c.Name, c.Meta["correlationId"], res.CorrelationID)
		}
	}
	// The mutating call must carry an idempotency key; read-only ones must not.
	for _, c := range calls {
		_, has := c.Meta["idempotencyKey"]
		wantKey := c.Name == "messaging.send_sms"
		if has != wantKey {
			t.Errorf("%s idempotencyKey present = %v, want %v", c.Name, has, wantKey)
		}
	}

	// Every assistant message records its producing provider.
	assistants := 0
	for _, m := range res.Transcript {
		if m.Role == provider.RoleAssistant {
			assistants++
			if m.Provider != "fake" {
				t.Errorf("assistant message has provider %q, want fake", m.Provider)
			}
		}
	}
	if assistants != 4 {
		t.Errorf("assistant messages = %d, want 4", assistants)
	}
}

func TestAgentSurfacesPolicyApprovalRequired(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()

	fake := provider.NewFake(provider.FakeOptions{
		Name: "fake",
		Script: []provider.ScriptStep{
			{ToolCalls: []provider.Part{
				provider.ToolCallPart("c1", "telephony.place_call", map[string]any{"target": "+15550001234"}),
			}},
			{Text: "Approval is pending (pa_0001); I did not retry."},
		},
	})
	a := newAgent(t, p, []provider.Provider{fake}, config.Agent{MaxTurns: 5})
	res, err := a.Run(context.Background(), "call the restaurant")
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if len(res.Trace) != 1 {
		t.Fatalf("trace = %+v", res.Trace)
	}
	tr := res.Trace[0]
	if tr.OK {
		t.Error("the approval-required outcome must be marked as not OK")
	}
	if tr.ErrorCode != "policy.approval_required" {
		t.Errorf("ErrorCode = %q", tr.ErrorCode)
	}
	if !strings.Contains(tr.Summary, "pa_0001") {
		t.Errorf("summary should carry the pending action id: %q", tr.Summary)
	}
	// The transcript must hand the structured policy error back to the model.
	var toolContent string
	for _, m := range res.Transcript {
		if m.Role == provider.RoleTool {
			toolContent = m.Parts[0].Content
			if !m.Parts[0].IsError {
				t.Error("tool result should be flagged as an error")
			}
		}
	}
	if !strings.Contains(toolContent, "policy.approval_required") {
		t.Errorf("tool result content = %q", toolContent)
	}
}

func TestAgentRespectsAllowlist(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()

	fake := provider.NewFake(provider.FakeOptions{
		Name: "fake",
		Script: []provider.ScriptStep{
			{ToolCalls: []provider.Part{
				provider.ToolCallPart("c1", "messaging.send_sms", map[string]any{"to": "+1", "text": "x"}),
			}},
			{Text: "That tool is not available to me."},
		},
	})
	a := newAgent(t, p, []provider.Provider{fake},
		config.Agent{MaxTurns: 5, ToolAllowlist: []string{"device.*"}})
	res, err := a.Run(context.Background(), "send an sms")
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if len(res.Trace) != 1 || res.Trace[0].ErrorCode != "tool.unknown" {
		t.Fatalf("trace = %+v", res.Trace)
	}
	if len(p.Calls()) != 0 {
		t.Errorf("a filtered tool must never reach the phone, saw %+v", p.Calls())
	}
}

func TestFailoverAtTurnBoundary(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()

	script := []provider.ScriptStep{
		{ToolCalls: []provider.Part{provider.ToolCallPart("c1", "device.get_battery", map[string]any{})}},
		{Text: "Battery is 82%."},
	}
	// primary fails on its second Generate call, which is the turn after the
	// tool result comes back.
	primary := provider.NewFake(provider.FakeOptions{Name: "primary", Script: script, FailOnCall: 2})
	secondary := provider.NewFake(provider.FakeOptions{Name: "secondary", Script: script})

	a := newAgent(t, p, []provider.Provider{primary, secondary}, config.Agent{MaxTurns: 6})
	res, err := a.Run(context.Background(), "battery?")
	if err != nil {
		t.Fatalf("Run: %v", err)
	}

	if len(res.Switches) != 1 {
		t.Fatalf("switches = %+v, want exactly one", res.Switches)
	}
	sw := res.Switches[0]
	if sw.From != "primary" || sw.To != "secondary" || sw.Turn != 2 {
		t.Errorf("switch = %+v", sw)
	}
	if res.Answer != "Battery is 82%." {
		t.Errorf("answer = %q", res.Answer)
	}

	// The tool must have run exactly once: failover happened between turns, not
	// during the call.
	if n := len(p.Calls()); n != 1 {
		t.Fatalf("phone saw %d calls, want exactly 1 (a mid-tool-call switch would repeat it)", n)
	}

	// Provider attribution must reflect the switch.
	var producers []string
	for _, m := range res.Transcript {
		if m.Role == provider.RoleAssistant {
			producers = append(producers, m.Provider)
		}
	}
	if !reflect.DeepEqual(producers, []string{"primary", "secondary"}) {
		t.Errorf("assistant providers = %v, want [primary secondary]", producers)
	}

	// The secondary must have been handed the identical canonical transcript.
	obs := secondary.ObservedTranscripts()
	if len(obs) != 1 {
		t.Fatalf("secondary was called %d times, want 1", len(obs))
	}
	primaryObs := primary.ObservedTranscripts()
	if len(primaryObs) != 2 {
		t.Fatalf("primary was called %d times, want 2", len(primaryObs))
	}
	if !reflect.DeepEqual(primaryObs[1], obs[0]) {
		t.Errorf("failover retried with a different transcript:\nprimary saw %+v\nsecondary saw %+v",
			primaryObs[1], obs[0])
	}
}

func TestFailoverOnFirstTurn(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()

	primary := provider.NewFake(provider.FakeOptions{
		Name: "primary", FailOnCall: 1,
		Script: []provider.ScriptStep{{Text: "unused"}},
	})
	secondary := provider.NewFake(provider.FakeOptions{
		Name:   "secondary",
		Script: []provider.ScriptStep{{Text: "answered by the backup"}},
	})
	a := newAgent(t, p, []provider.Provider{primary, secondary}, config.Agent{MaxTurns: 4})
	res, err := a.Run(context.Background(), "hello")
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if res.Answer != "answered by the backup" {
		t.Errorf("answer = %q", res.Answer)
	}
	if len(res.Switches) != 1 || res.Switches[0].Turn != 1 {
		t.Errorf("switches = %+v", res.Switches)
	}
}

func TestAllProvidersFailing(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()

	a := newAgent(t, p, []provider.Provider{
		provider.NewFake(provider.FakeOptions{Name: "a", Script: []provider.ScriptStep{{Err: errors.New("boom a")}}}),
		provider.NewFake(provider.FakeOptions{Name: "b", Script: []provider.ScriptStep{{Err: errors.New("boom b")}}}),
	}, config.Agent{MaxTurns: 3})
	_, err := a.Run(context.Background(), "x")
	if err == nil {
		t.Fatal("expected an error when every provider fails")
	}
	if !strings.Contains(err.Error(), "all 2 provider(s) failed") {
		t.Errorf("error = %v", err)
	}
}

func TestPhoneTransportFailureIsNotAProviderFailure(t *testing.T) {
	// A tool that the phone rejects at transport level must be reported to the
	// model as a tool error, not trigger a provider switch.
	p := fakephone.New(fakephone.Options{
		Token: "tok",
		ToolHandler: func(name string, args, meta map[string]any) map[string]any {
			return map[string]any{
				"isError":           true,
				"content":           []any{map[string]any{"type": "text", "text": "capability failed"}},
				"structuredContent": map[string]any{"error": "capability.failed", "detail": "radio off"},
			}
		},
	})
	defer p.Close()

	primary := provider.NewFake(provider.FakeOptions{
		Name: "primary",
		Script: []provider.ScriptStep{
			{ToolCalls: []provider.Part{provider.ToolCallPart("c1", "device.get_battery", map[string]any{})}},
			{Text: "The phone could not read the battery: radio off."},
		},
	})
	secondary := provider.NewFake(provider.FakeOptions{Name: "secondary", Script: []provider.ScriptStep{{Text: "unused"}}})

	a := newAgent(t, p, []provider.Provider{primary, secondary}, config.Agent{MaxTurns: 5})
	res, err := a.Run(context.Background(), "battery?")
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if len(res.Switches) != 0 {
		t.Errorf("a tool failure must not cause failover, got %+v", res.Switches)
	}
	if secondary.Calls() != 0 {
		t.Errorf("secondary provider was used %d times, want 0", secondary.Calls())
	}
	if res.Trace[0].ErrorCode != "capability.failed" {
		t.Errorf("trace = %+v", res.Trace)
	}
}

// TestTransportFailureAfterExecutionRetriesIdempotently is test gap 4: the
// phone accepts a non-read-only call and actually executes it, but the HTTP
// connection fails before the response reaches the gateway (the fake phone
// hijacks and closes the connection right after running the handler). The
// model - seeing a transport.failed tool result - retries with the identical
// arguments on the next turn. Because IdempotencyKey is derived from
// correlation_id | tool | canonical_args and the task keeps one correlation id
// throughout, the retry carries the exact same idempotencyKey as the first
// attempt, and the phone's idempotency table returns the stored result rather
// than executing messaging.send_sms a second time.
func TestTransportFailureAfterExecutionRetriesIdempotently(t *testing.T) {
	var execCount int32
	p := fakephone.New(fakephone.Options{
		Token: "tok",
		ToolHandler: func(name string, args, meta map[string]any) map[string]any {
			if name == "messaging.send_sms" {
				atomic.AddInt32(&execCount, 1)
			}
			return fakephone.DefaultToolHandler(name, args, meta)
		},
	})
	defer p.Close()
	// The very first messaging.send_sms call executes (and is recorded under
	// its idempotency key) but its response never reaches the client.
	p.FailResponseOnce("messaging.send_sms")

	sendArgs := map[string]any{"to": "+15550001234", "text": "hello"}
	fake := provider.NewFake(provider.FakeOptions{
		Name: "fake",
		Script: []provider.ScriptStep{
			{ToolCalls: []provider.Part{provider.ToolCallPart("c1", "messaging.send_sms", sendArgs)}},
			// The model sees the transport.failed tool result and retries with
			// the identical arguments.
			{ToolCalls: []provider.Part{provider.ToolCallPart("c2", "messaging.send_sms", sendArgs)}},
			{Text: "sent."},
		},
	})

	a := newAgent(t, p, []provider.Provider{fake}, config.Agent{MaxTurns: 6, CorrelationPrefix: "corr_"})
	res, err := a.Run(context.Background(), "text +15550001234 hello")
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if res.Answer != "sent." {
		t.Errorf("answer = %q", res.Answer)
	}

	if got := atomic.LoadInt32(&execCount); got != 1 {
		t.Fatalf("messaging.send_sms executed %d times, want exactly 1 (the retry must hit the idempotency table)", got)
	}

	calls := p.Calls()
	if len(calls) != 2 {
		t.Fatalf("phone saw %d tools/call requests, want 2 (the failed attempt and the retry): %+v", len(calls), calls)
	}
	key0, _ := calls[0].Meta["idempotencyKey"].(string)
	key1, _ := calls[1].Meta["idempotencyKey"].(string)
	if key0 == "" || key1 == "" {
		t.Fatalf("idempotencyKey missing: call0=%q call1=%q", key0, key1)
	}
	if key0 != key1 {
		t.Errorf("idempotencyKey differs between the failed attempt and the retry: %q != %q", key0, key1)
	}
	wantKey, err := device.IdempotencyKey(res.CorrelationID, "messaging.send_sms", sendArgs)
	if err != nil {
		t.Fatalf("IdempotencyKey: %v", err)
	}
	if key0 != wantKey {
		t.Errorf("idempotencyKey = %q, want %q", key0, wantKey)
	}

	// The agent loop's own view of this call must resolve to exactly one
	// successful tool result: the transport-failure trace entry is not a
	// second execution, and the retry that recovers the stored result must not
	// be double-counted as another send.
	var sendTrace []ToolTrace
	for _, tr := range res.Trace {
		if tr.Name == "messaging.send_sms" {
			sendTrace = append(sendTrace, tr)
		}
	}
	if len(sendTrace) != 2 {
		t.Fatalf("trace has %d messaging.send_sms entries, want 2 (one failed, one recovered): %+v", len(sendTrace), sendTrace)
	}
	if sendTrace[0].OK || sendTrace[0].ErrorCode != "transport.failed" {
		t.Errorf("trace[0] = %+v, want a transport.failed failure", sendTrace[0])
	}
	successCount := 0
	for _, tr := range sendTrace {
		if tr.OK {
			successCount++
		}
	}
	if successCount != 1 {
		t.Errorf("the agent loop saw %d successful messaging.send_sms tool results, want exactly 1", successCount)
	}
	if !sendTrace[1].OK {
		t.Errorf("trace[1] = %+v, want the retry to succeed via the stored idempotent result", sendTrace[1])
	}
}

func TestMaxTurnsIsEnforced(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()

	// A provider that never stops calling tools.
	loop := make([]provider.ScriptStep, 10)
	for i := range loop {
		loop[i] = provider.ScriptStep{ToolCalls: []provider.Part{
			provider.ToolCallPart("", "device.get_battery", map[string]any{}),
		}}
	}
	fake := provider.NewFake(provider.FakeOptions{Name: "fake", Script: loop})
	a := newAgent(t, p, []provider.Provider{fake}, config.Agent{MaxTurns: 3})
	res, err := a.Run(context.Background(), "loop forever")
	if err == nil {
		t.Fatal("expected a max_turns error")
	}
	if !strings.Contains(err.Error(), "max_turns (3)") {
		t.Errorf("error = %v", err)
	}
	if res.Turns != 3 {
		t.Errorf("turns = %d, want 3", res.Turns)
	}
}
