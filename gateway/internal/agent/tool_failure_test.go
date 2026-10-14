package agent

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/config"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/device"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/fakephone"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/provider"
)

// toolResults returns the tool-result parts of a transcript, in order.
func toolResults(msgs []provider.Message) []provider.Part {
	var out []provider.Part
	for _, m := range msgs {
		for _, p := range m.Parts {
			if p.Type == provider.PartToolResult {
				out = append(out, p)
			}
		}
	}
	return out
}

func resultError(t *testing.T, part provider.Part) map[string]any {
	t.Helper()
	var body map[string]any
	if err := json.Unmarshal([]byte(part.Content), &body); err != nil {
		t.Fatalf("tool result %q is not JSON: %v", part.Content, err)
	}
	return body
}

// TestMalformedToolArgumentsNeverReachThePhone is the M6 regression. Arguments
// that did not parse became {"_raw": s} (OpenAI) or {} (Anthropic, Gemini) and
// the agent still called the phone with them. A call marked invalid is now
// answered with tool.invalid_arguments and never executed.
func TestMalformedToolArgumentsNeverReachThePhone(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()

	bad := provider.ToolCallPart("c1", "messaging.send_sms", map[string]any{})
	bad.ArgsError = `tool arguments are not a JSON object: unexpected end of JSON input (got {"to":"+1555`
	fake := provider.NewFake(provider.FakeOptions{
		Name: "fake",
		Script: []provider.ScriptStep{
			{ToolCalls: []provider.Part{bad}},
			{Text: "I could not send it."},
		},
	})
	a := newAgent(t, p, []provider.Provider{fake}, config.Agent{MaxTurns: 4})
	res, err := a.Run(context.Background(), "text +1555")
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if n := len(p.Calls()); n != 0 {
		t.Fatalf("phone saw %d calls, want 0", n)
	}
	if len(res.Trace) != 1 || res.Trace[0].ErrorCode != "tool.invalid_arguments" || res.Trace[0].OK {
		t.Fatalf("trace = %+v, want one tool.invalid_arguments failure", res.Trace)
	}
	results := toolResults(res.Transcript)
	if len(results) != 1 || !results[0].IsError {
		t.Fatalf("tool results = %+v", results)
	}
	body := resultError(t, results[0])
	if body["error"] != "tool.invalid_arguments" || !strings.Contains(body["detail"].(string), "not a JSON object") {
		t.Errorf("tool result = %v", body)
	}
}

// TestCallFailuresAreClassified checks what the model is told for each kind of
// phone failure: a call that never left the gateway, and one the phone refused
// at dispatch, are not "outcome unknown".
func TestCallFailuresAreClassified(t *testing.T) {
	t.Run("not sent", func(t *testing.T) {
		p := fakephone.New(fakephone.Options{Token: "tok"})
		defer p.Close()
		fake := provider.NewFake(provider.FakeOptions{
			Name: "fake",
			Script: []provider.ScriptStep{
				{ToolCalls: []provider.Part{provider.ToolCallPart("c1", "messaging.send_sms",
					map[string]any{"to": "+1555", "text": "x"})}},
				{Text: "nothing was sent."},
			},
		})
		dev := device.NewMCPClient(device.MCPOptions{BaseURL: p.URL(), Token: "tok",
			HTTPClient: &http.Client{Transport: refuseToolCalls{}}})
		a, err := New(Options{Device: dev, Providers: []provider.Provider{fake}, Config: config.Agent{MaxTurns: 4}})
		if err != nil {
			t.Fatal(err)
		}
		res, err := a.Run(context.Background(), "text +1555")
		if err != nil {
			t.Fatalf("Run: %v", err)
		}
		if len(res.Trace) != 1 || res.Trace[0].ErrorCode != "transport.not_sent" {
			t.Fatalf("trace = %+v, want transport.not_sent", res.Trace)
		}
		if n := len(p.Calls()); n != 0 {
			t.Errorf("phone saw %d calls, want 0", n)
		}
	})

	t.Run("outcome unknown carries retry identity", func(t *testing.T) {
		p := fakephone.New(fakephone.Options{Token: "tok"})
		defer p.Close()
		p.FailResponseOnce("messaging.send_sms")
		fake := provider.NewFake(provider.FakeOptions{
			Name: "fake",
			Script: []provider.ScriptStep{
				{ToolCalls: []provider.Part{provider.ToolCallPart("c1", "messaging.send_sms",
					map[string]any{"to": "+1555", "text": "x"})}},
				{Text: "the outcome is unknown."},
			},
		})
		a := newAgent(t, p, []provider.Provider{fake}, config.Agent{MaxTurns: 4})
		res, err := a.Run(context.Background(), "text +1555")
		if err != nil {
			t.Fatalf("Run: %v", err)
		}
		body := resultError(t, toolResults(res.Transcript)[0])
		if body["error"] != "transport.outcome_unknown" || body["correlation_id"] != res.CorrelationID ||
			body["idempotency_key"] == nil || !strings.Contains(body["detail"].(string), "Do not retry blindly") {
			t.Errorf("tool result = %v", body)
		}
	})
}

// refuseToolCalls is a RoundTripper that fails every tools/call before writing
// anything, as a refused dial would, and passes everything else through.
type refuseToolCalls struct{}

func (refuseToolCalls) RoundTrip(req *http.Request) (*http.Response, error) {
	if req.GetBody != nil {
		if body, err := req.GetBody(); err == nil {
			raw, _ := io.ReadAll(body)
			if strings.Contains(string(raw), `"tools/call"`) {
				return nil, errors.New("dial tcp: connection refused")
			}
		}
	}
	return http.DefaultTransport.RoundTrip(req)
}
