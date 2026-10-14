package provider

import (
	"context"
	"errors"
	"fmt"
	"sync"
)

// ScriptStep is one scripted assistant turn for the fake provider.
type ScriptStep struct {
	// Text is assistant text emitted for this turn.
	Text string
	// ToolCalls are tool invocations emitted for this turn. When non-empty the
	// step's stop reason is tool_use.
	ToolCalls []Part
	// Err, when non-nil, makes this turn fail. The agent treats that as a
	// provider failure and moves to the next provider in the failover chain.
	Err error
	// Stop overrides the derived stop reason, so a test can reproduce a turn
	// that ended at the token cap or in a refusal.
	Stop StopReason
	// StopDetails is the provider's verbatim stop wording, when Stop is set.
	StopDetails string
}

// FakeProvider is a deterministic scripted provider used by tests and by
// "gateway run" when no real credentials are configured. It reads the canonical
// transcript, walks a fixed script, and can be told to fail Health() or to
// return an error on the Nth Generate call so failover can be exercised.
type FakeProvider struct {
	mu sync.Mutex

	name   string
	script []ScriptStep
	// calls counts Generate invocations.
	calls int
	// failOnCall, when > 0, makes the Nth (1-based) Generate call fail.
	failOnCall int
	// healthErr, when non-nil, is returned by Health.
	healthErr error
	// observed records the transcript length seen on each Generate call, so a
	// test can assert failover replayed the identical transcript.
	observed [][]Message
}

// FakeOptions configures a FakeProvider.
type FakeOptions struct {
	// Name is the configured provider name.
	Name string
	// Script is the sequence of assistant turns to emit.
	Script []ScriptStep
	// FailOnCall makes the Nth (1-based) Generate call return an error.
	FailOnCall int
	// HealthErr makes Health always fail with this error.
	HealthErr error
}

// NewFake constructs a deterministic scripted provider.
func NewFake(opts FakeOptions) *FakeProvider {
	return &FakeProvider{
		name:       opts.Name,
		script:     opts.Script,
		failOnCall: opts.FailOnCall,
		healthErr:  opts.HealthErr,
	}
}

// Name returns the configured provider name.
func (p *FakeProvider) Name() string { return p.name }

// Capabilities reports a tool-calling, offline-capable provider.
func (p *FakeProvider) Capabilities() Capabilities {
	return Capabilities{ToolCalling: true, LocalOffline: true, ContextWindow: 8192}
}

// Generate emits the next scripted turn.
//
// The script index is derived from the transcript rather than from a mutable
// cursor, so a failover retry of the same turn against a second FakeProvider
// yields the same scripted step.
func (p *FakeProvider) Generate(ctx context.Context, req GenerateRequest) (GenerateResult, error) {
	p.mu.Lock()
	p.calls++
	call := p.calls
	p.observed = append(p.observed, cloneMessages(req.Messages))
	p.mu.Unlock()

	if err := ctx.Err(); err != nil {
		return GenerateResult{}, &Error{Provider: p.name, Err: err}
	}
	if p.failOnCall > 0 && call == p.failOnCall {
		return GenerateResult{}, &Error{Provider: p.name, Status: 503, Body: "scripted failure"}
	}

	idx := assistantTurns(req.Messages)
	if idx >= len(p.script) {
		return GenerateResult{
			Message:    Message{Role: RoleAssistant, Provider: p.name, Parts: []Part{TextPart("(fake provider: script exhausted)")}},
			StopReason: StopEndTurn,
			Provider:   p.name,
		}, nil
	}
	step := p.script[idx]
	if step.Err != nil {
		return GenerateResult{}, &Error{Provider: p.name, Err: step.Err}
	}

	msg := Message{Role: RoleAssistant, Provider: p.name}
	if step.Text != "" {
		msg.Parts = append(msg.Parts, TextPart(step.Text))
	}
	stop := StopEndTurn
	for i, tc := range step.ToolCalls {
		call := tc
		if call.Type == "" {
			call.Type = PartToolCall
		}
		if call.ID == "" {
			call.ID = fmt.Sprintf("%s_t%d_c%d", p.name, idx, i)
		}
		msg.Parts = append(msg.Parts, call)
		stop = StopToolUse
	}
	if step.Stop != "" {
		stop = step.Stop
	}
	return GenerateResult{
		Message:     msg,
		StopReason:  stop,
		StopDetails: step.StopDetails,
		Provider:    p.name,
	}, nil
}

// Health returns the configured health error, or nil.
func (p *FakeProvider) Health(ctx context.Context) error {
	if p.healthErr != nil {
		return fmt.Errorf("provider %s: %w", p.name, p.healthErr)
	}
	return nil
}

// Calls reports how many times Generate was invoked.
func (p *FakeProvider) Calls() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.calls
}

// ObservedTranscripts returns a copy of the transcript passed to each Generate
// call, so tests can assert that failover replayed an identical transcript.
func (p *FakeProvider) ObservedTranscripts() [][]Message {
	p.mu.Lock()
	defer p.mu.Unlock()
	out := make([][]Message, len(p.observed))
	copy(out, p.observed)
	return out
}

// ErrFakeScripted is the canonical error for a scripted provider failure.
var ErrFakeScripted = errors.New("scripted provider failure")

// assistantTurns counts assistant messages in the transcript, which is the
// index of the next scripted step.
func assistantTurns(msgs []Message) int {
	n := 0
	for _, m := range msgs {
		if m.Role == RoleAssistant {
			n++
		}
	}
	return n
}

func cloneMessages(msgs []Message) []Message {
	out := make([]Message, len(msgs))
	for i, m := range msgs {
		c := m
		c.Parts = make([]Part, len(m.Parts))
		for j, p := range m.Parts {
			pc := p
			if p.Args != nil {
				pc.Args = deepCopy(p.Args).(map[string]any)
			}
			c.Parts[j] = pc
		}
		out[i] = c
	}
	return out
}
