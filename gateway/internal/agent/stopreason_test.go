package agent

import (
	"context"
	"strings"
	"testing"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/config"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/fakephone"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/provider"
)

// TestMaxTokensMidToolCallDoesNotExecuteTheTool is the P1-3 regression. The loop
// used to ignore StopReason entirely, so a turn cut short by the token cap had
// its half-written tool_use block executed against the phone with whatever
// arguments had survived truncation — frequently "{}".
func TestMaxTokensMidToolCallDoesNotExecuteTheTool(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()

	fake := provider.NewFake(provider.FakeOptions{
		Name: "fake",
		Script: []provider.ScriptStep{{
			Text: "I will send the message",
			ToolCalls: []provider.Part{
				provider.ToolCallPart("c1", "messaging.send_sms", map[string]any{}),
			},
			Stop:        provider.StopMaxTokens,
			StopDetails: "stop_reason=max_tokens",
		}},
	})
	a := newAgent(t, p, []provider.Provider{fake}, config.Agent{MaxTurns: 4})

	res, err := a.Run(context.Background(), "text +15550001234")
	if err == nil {
		t.Fatal("a truncated tool call was treated as a successful turn")
	}
	if !strings.Contains(err.Error(), "max_tokens") {
		t.Errorf("error does not name the cause: %v", err)
	}
	if !strings.Contains(err.Error(), "no tool was executed") {
		t.Errorf("error does not say the tool was skipped: %v", err)
	}
	if !strings.Contains(err.Error(), "stop_reason=max_tokens") {
		t.Errorf("the provider's own stop wording was not surfaced: %v", err)
	}
	if calls := p.Calls(); len(calls) != 0 {
		t.Fatalf("the phone executed %d call(s) from a truncated turn: %v", len(calls), calls)
	}
	if len(res.Transcript) == 0 {
		t.Error("the transcript must be returned on a failure path, not dropped")
	}
	if len(res.Trace) != 0 {
		t.Errorf("trace records a call that never ran: %v", res.Trace)
	}
}

// TestRefusalIsNotAnEmptySuccess covers the other half of P1-3: a refusal used
// to come back as a successful task with an empty answer and exit status 0.
func TestRefusalIsNotAnEmptySuccess(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()

	fake := provider.NewFake(provider.FakeOptions{
		Name: "fake",
		Script: []provider.ScriptStep{{
			Stop:        provider.StopRefusal,
			StopDetails: "stop_reason=refusal",
		}},
	})
	a := newAgent(t, p, []provider.Provider{fake}, config.Agent{MaxTurns: 4})

	res, err := a.Run(context.Background(), "do something disallowed")
	if err == nil {
		t.Fatalf("a refusal was reported as success with answer %q", res.Answer)
	}
	if !strings.Contains(err.Error(), "no content") {
		t.Errorf("error does not describe the empty turn: %v", err)
	}
	if !strings.Contains(err.Error(), string(provider.StopRefusal)) {
		t.Errorf("error does not carry the stop reason: %v", err)
	}
	if !strings.Contains(err.Error(), "stop_reason=refusal") {
		t.Errorf("stop details were not surfaced: %v", err)
	}
	if res.Answer != "" {
		t.Errorf("answer = %q, want empty", res.Answer)
	}
	if len(res.Transcript) == 0 {
		t.Error("the transcript must be returned on a failure path")
	}
}

// TestMaxTokensWithTextStillAnswersButMarksTruncated: a cap reached while
// writing prose is not a lost turn, only an incomplete one, so the text is
// returned and flagged.
func TestMaxTokensWithTextStillAnswersButMarksTruncated(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()

	fake := provider.NewFake(provider.FakeOptions{
		Name: "fake",
		Script: []provider.ScriptStep{{
			Text: "The battery is at 8",
			Stop: provider.StopMaxTokens,
		}},
	})
	a := newAgent(t, p, []provider.Provider{fake}, config.Agent{MaxTurns: 4})

	res, err := a.Run(context.Background(), "battery?")
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if res.Answer != "The battery is at 8" {
		t.Errorf("answer = %q", res.Answer)
	}
	if !res.Truncated {
		t.Error("Truncated is false on an answer cut short by the token cap")
	}
}

// TestSwitchesRecordTheProviderThatActuallyFailed covers the P2 Switches.From
// bug: with three providers and two failures, From named the provider the turn
// started on rather than the one that failed immediately before the winner.
func TestSwitchesRecordTheProviderThatActuallyFailed(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()

	answer := []provider.ScriptStep{{Text: "done"}}
	first := provider.NewFake(provider.FakeOptions{Name: "first", Script: answer, FailOnCall: 1})
	second := provider.NewFake(provider.FakeOptions{Name: "second", Script: answer, FailOnCall: 1})
	third := provider.NewFake(provider.FakeOptions{Name: "third", Script: answer})

	a := newAgent(t, p, []provider.Provider{first, second, third}, config.Agent{MaxTurns: 4})
	res, err := a.Run(context.Background(), "hello")
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if len(res.Switches) != 1 {
		t.Fatalf("switches = %v, want one", res.Switches)
	}
	sw := res.Switches[0]
	if sw.From != "second" || sw.To != "third" {
		t.Errorf("switch = %s -> %s, want second -> third (the provider that actually failed)", sw.From, sw.To)
	}
	if !strings.Contains(sw.Reason, "second") {
		t.Errorf("reason names the wrong provider: %q", sw.Reason)
	}
}

// TestUnhealthyPhoneFailsTheTask covers the P2 health.OK item: the phone can
// answer /health with ok:false, which the agent used to ignore entirely.
func TestUnhealthyPhoneFailsTheTask(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok", Unhealthy: true})
	defer p.Close()

	fake := provider.NewFake(provider.FakeOptions{
		Name: "fake", Script: []provider.ScriptStep{{Text: "done"}},
	})
	a := newAgent(t, p, []provider.Provider{fake}, config.Agent{MaxTurns: 2})

	if _, err := a.Run(context.Background(), "hello"); err == nil {
		t.Fatal("a phone reporting ok=false was treated as ready")
	} else if !strings.Contains(err.Error(), "ok=false") {
		t.Errorf("error does not explain the failure: %v", err)
	}
	if fake.Calls() != 0 {
		t.Error("the provider was called against an unhealthy phone")
	}
}
