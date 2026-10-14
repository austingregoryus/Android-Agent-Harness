// Package agent runs the gateway's tool-calling loop: a canonical transcript
// driven by a failover chain of model providers, with tool calls executed
// against the phone runtime.
package agent

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/config"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/device"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/provider"
)

// ToolTrace is one executed tool call, for the compact trace the CLI prints.
type ToolTrace struct {
	// Turn is the 1-based provider turn that requested the call.
	Turn int
	// Provider is the provider that requested the call.
	Provider string
	// Name is the phone tool name.
	Name string
	// Args are the arguments sent.
	Args map[string]any
	// OK reports whether the tool succeeded.
	OK bool
	// ErrorCode is the policy or capability error code, if any.
	ErrorCode string
	// Summary is a short rendering of the result.
	Summary string
	// Duration is the wall-clock time of the call.
	Duration time.Duration
}

// ProviderSwitch records one failover event.
type ProviderSwitch struct {
	// Turn is the 1-based turn where the switch happened.
	Turn int
	// From is the provider that failed.
	From string
	// To is the provider that took over.
	To string
	// Reason is the failure that triggered the switch.
	Reason string
}

// Result is the outcome of one agent task.
type Result struct {
	// TaskID identifies the persisted task.
	TaskID string
	// CorrelationID is the id stamped on every tool call in this task.
	CorrelationID string
	// Answer is the final assistant text.
	Answer string
	// Transcript is the full canonical transcript, including which provider
	// produced each assistant message.
	Transcript []provider.Message
	// Trace lists the executed tool calls in order.
	Trace []ToolTrace
	// Switches lists failover events.
	Switches []ProviderSwitch
	// Turns is the number of provider turns consumed.
	Turns int
	// Truncated is true when the final answer was cut short by the provider's
	// token cap. The answer is still returned; it is just incomplete.
	Truncated bool
}

// Options configures an Agent.
type Options struct {
	// Device is the phone MCP client.
	Device *device.MCPClient
	// Providers is the failover chain, in order.
	Providers []provider.Provider
	// Config is the agent section of the gateway configuration.
	Config config.Agent
	// Store persists tasks and transcripts. Optional.
	Store Store
	// Clock supplies the current time. Nil means time.Now.
	Clock func() time.Time
}

// Store is the persistence surface the agent needs.
type Store interface {
	// CreateTask inserts a running task row.
	CreateTask(id, correlationID, taskText string) error
	// AppendMessage persists one canonical transcript message.
	AppendMessage(taskID string, seq int, role, providerName string, parts any) error
	// FinishTask records the terminal state of a task.
	FinishTask(id string, status string, answer, errText string) error
}

// Agent runs tasks against the phone with a provider failover chain.
type Agent struct {
	dev       *device.MCPClient
	providers []provider.Provider
	cfg       config.Agent
	store     Store
	now       func() time.Time
}

// New constructs an Agent.
func New(opts Options) (*Agent, error) {
	if opts.Device == nil {
		return nil, errors.New("agent: device client is required")
	}
	if len(opts.Providers) == 0 {
		return nil, errors.New("agent: at least one provider is required")
	}
	cfg := opts.Config
	if cfg.MaxTurns < 1 {
		cfg.MaxTurns = 12
	}
	if cfg.CorrelationPrefix == "" {
		cfg.CorrelationPrefix = "corr_"
	}
	now := opts.Clock
	if now == nil {
		now = time.Now
	}
	return &Agent{
		dev:       opts.Device,
		providers: opts.Providers,
		cfg:       cfg,
		store:     opts.Store,
		now:       now,
	}, nil
}

// NewCorrelationID mints a correlation id of the form prefix + 16 hex digits,
// matching the runtime's corr_<16 hex> convention.
func NewCorrelationID(prefix string) string {
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		// crypto/rand failing is fatal for auditability; fall back to the clock
		// so the task still carries a distinguishable id.
		nano := time.Now().UnixNano()
		for i := range b {
			b[i] = byte(nano >> (8 * i))
		}
	}
	return prefix + hex.EncodeToString(b[:])
}

// MatchGlob reports whether a capability name matches a scope glob using the
// protocol's grammar: "*" matches any run of characters within the name, and a
// bare "*" matches everything.
func MatchGlob(glob, name string) bool {
	if glob == "*" {
		return true
	}
	parts := strings.Split(glob, "*")
	pos := 0
	for i, part := range parts {
		if part == "" {
			continue
		}
		idx := strings.Index(name[pos:], part)
		if idx < 0 {
			return false
		}
		if i == 0 && idx != 0 {
			// A glob that does not start with * must match from the beginning.
			return false
		}
		pos += idx + len(part)
	}
	if last := parts[len(parts)-1]; last != "" && !strings.HasSuffix(name, last) {
		return false
	}
	if len(parts) == 1 {
		return glob == name
	}
	return true
}

// FilterTools keeps only the tools whose names match at least one allowlist
// glob. An empty allowlist keeps everything.
func FilterTools(tools []device.Tool, allowlist []string) []device.Tool {
	if len(allowlist) == 0 {
		return tools
	}
	var out []device.Tool
	for _, t := range tools {
		for _, g := range allowlist {
			if MatchGlob(g, t.Name) {
				out = append(out, t)
				break
			}
		}
	}
	return out
}

// SystemPrompt builds the system prompt from the device summary and the policy
// hints the security review requires the agent to honour.
func SystemPrompt(health device.HealthResult, tools []device.Tool) string {
	var b strings.Builder
	b.WriteString("You are the Android Agent Harness agent. You act through a paired Android phone that exposes its capabilities as tools.\n\n")
	b.WriteString("Device:\n")
	fmt.Fprintf(&b, "- device_id: %s\n", nonEmpty(health.DeviceID, "unknown"))
	fmt.Fprintf(&b, "- runtime version: %s\n", nonEmpty(health.Version, "unknown"))
	fmt.Fprintf(&b, "- tools available: %d\n", len(tools))
	if len(tools) > 0 {
		b.WriteString("- capabilities: ")
		names := make([]string, 0, len(tools))
		for _, t := range tools {
			names = append(names, t.Name)
		}
		b.WriteString(strings.Join(names, ", "))
		b.WriteString("\n")
	}
	b.WriteString(`
Policy rules you must follow:
- A tool result with error "policy.approval_required" means a human must approve the action on the phone.
  Tell the user that approval is pending and report the pending_action_id. Do NOT retry the call blindly.
- A tool result with error "policy.denied", "policy.rate_limited", "policy.observe_only" or
  "policy.emergency_stop" is terminal for that action. Do not retry it. Explain what was blocked and stop.
- A tool result with error "permission.missing" lists the Android permissions that must be granted first.
- A tool result with error "capability.unavailable" or "capability.failed" means the phone cannot do it.
  Say so plainly rather than inventing a result.
- Never claim an action happened unless a tool result confirms it.

Answer the user's task using the tools. When you have the answer, reply with plain text and no tool calls.
`)
	return b.String()
}

func nonEmpty(s, fallback string) string {
	if strings.TrimSpace(s) == "" {
		return fallback
	}
	return s
}

// ToolDefs converts phone tools into provider-neutral tool definitions. Names
// pass through verbatim.
func ToolDefs(tools []device.Tool) []provider.ToolDef {
	out := make([]provider.ToolDef, 0, len(tools))
	for _, t := range tools {
		schema := t.InputSchema
		if schema == nil {
			schema = map[string]any{"type": "object", "properties": map[string]any{}}
		}
		out = append(out, provider.ToolDef{
			Name:        t.Name,
			Description: t.Description,
			InputSchema: schema,
		})
	}
	return out
}

// Run executes one task and returns its result.
//
// Failover rules, from the security review:
//   - Providers switch only at a turn boundary. Tool execution happens strictly
//     between provider calls, so a provider is never swapped while a tool call
//     is in flight.
//   - On provider error the same turn is retried on the next provider in the
//     chain with the identical canonical transcript.
//   - Every assistant message records the provider that produced it.
func (a *Agent) Run(ctx context.Context, taskText string) (Result, error) {
	corrID := NewCorrelationID(a.cfg.CorrelationPrefix)
	taskID := NewCorrelationID("task_")
	res := Result{TaskID: taskID, CorrelationID: corrID}

	if a.store != nil {
		if err := a.store.CreateTask(taskID, corrID, taskText); err != nil {
			return res, err
		}
	}

	health, err := a.dev.Health(ctx)
	if err != nil {
		a.fail(taskID, err)
		return res, fmt.Errorf("agent: phone health check failed: %w", err)
	}
	if !health.OK {
		err := fmt.Errorf("agent: phone reports ok=false (device_id=%s version=%s); it is reachable but not ready to act",
			nonEmpty(health.DeviceID, "unknown"), nonEmpty(health.Version, "unknown"))
		a.fail(taskID, err)
		return res, err
	}
	allTools, err := a.dev.ListTools(ctx)
	if err != nil {
		a.fail(taskID, err)
		return res, fmt.Errorf("agent: tools/list failed: %w", err)
	}
	tools := FilterTools(allTools, a.cfg.ToolAllowlist)
	byName := map[string]device.Tool{}
	for _, t := range tools {
		byName[t.Name] = t
	}

	system := SystemPrompt(health, tools)
	defs := ToolDefs(tools)

	transcript := []provider.Message{
		{Role: provider.RoleUser, Parts: []provider.Part{provider.TextPart(taskText)}},
	}
	seq := 0
	a.persist(taskID, &seq, transcript[0])

	providerIdx := 0
	for turn := 1; turn <= a.cfg.MaxTurns; turn++ {
		res.Turns = turn

		// --- provider turn (the only place a provider may change) ---
		req := provider.GenerateRequest{System: system, Messages: transcript, Tools: defs}
		var gen provider.GenerateResult
		var lastErr error
		attempted := 0
		for idx := providerIdx; idx < len(a.providers); idx++ {
			p := a.providers[idx]
			attempted++
			out, gerr := p.Generate(ctx, req)
			if gerr == nil {
				if idx != providerIdx {
					// The provider that actually failed is the one tried
					// immediately before this one, which is not necessarily the
					// provider the turn started on when more than one was tried.
					res.Switches = append(res.Switches, ProviderSwitch{
						Turn:   turn,
						From:   a.providers[idx-1].Name(),
						To:     p.Name(),
						Reason: errText(lastErr),
					})
				}
				providerIdx = idx
				gen = out
				lastErr = nil
				break
			}
			lastErr = gerr
			if ctx.Err() != nil {
				res.Transcript = transcript
				a.fail(taskID, ctx.Err())
				return res, fmt.Errorf("agent: cancelled: %w", ctx.Err())
			}
		}
		if lastErr != nil {
			res.Transcript = transcript
			a.fail(taskID, lastErr)
			return res, fmt.Errorf("agent: all %d provider(s) failed on turn %d: %w", attempted, turn, lastErr)
		}

		asst := gen.Message
		asst.Role = provider.RoleAssistant
		if asst.Provider == "" {
			asst.Provider = a.providers[providerIdx].Name()
		}
		transcript = append(transcript, asst)
		a.persist(taskID, &seq, asst)

		calls := asst.ToolCalls()

		// --- stop-reason handling (before any tool runs) ---
		//
		// A turn that ended at the token cap is not a turn the model finished.
		// Its last tool_use block can be truncated mid-arguments, and executing
		// it would send the phone a call the model never actually wrote. The
		// gateway fails the task instead of guessing or silently retrying: the
		// operator raises providers[].max_tokens (or narrows the task) and runs
		// it again, which is a decision the agent has no basis to make for them.
		if gen.StopReason == provider.StopMaxTokens && len(calls) > 0 {
			res.Transcript = transcript
			err := fmt.Errorf("agent: provider %s hit its max_tokens cap on turn %d with %d unfinished tool call(s); "+
				"the arguments may be truncated so no tool was executed - raise providers[].max_tokens and re-run%s",
				asst.Provider, turn, len(calls), stopDetailSuffix(gen))
			a.fail(taskID, err)
			return res, err
		}

		if len(calls) == 0 {
			answer := strings.TrimSpace(asst.TextOnly())
			if answer == "" {
				// A refusal (or any terminal stop the adapter does not model)
				// arrives as an assistant turn with no content at all. Reporting
				// that as a successful empty answer is the worst outcome: the
				// caller cannot tell it apart from a task with nothing to say.
				res.Transcript = transcript
				err := fmt.Errorf("agent: provider %s produced no content on turn %d (stop reason %q)%s",
					asst.Provider, turn, gen.StopReason, stopDetailSuffix(gen))
				a.fail(taskID, err)
				return res, err
			}
			res.Answer = answer
			res.Transcript = transcript
			if gen.StopReason == provider.StopMaxTokens {
				// Text, but truncated. It is still the model's answer, so it is
				// returned; the trace records that it was cut short.
				res.Truncated = true
			}
			a.finish(taskID, answer)
			return res, nil
		}

		// --- tool execution (no provider switching may occur here) ---
		toolMsg := provider.Message{Role: provider.RoleTool}
		for _, call := range calls {
			tool, known := byName[call.Name]
			if !known {
				toolMsg.Parts = append(toolMsg.Parts, provider.ToolResultPart(
					call.ID,
					fmt.Sprintf(`{"error":"tool.unknown","detail":%q}`, call.Name),
					true,
				))
				res.Trace = append(res.Trace, ToolTrace{
					Turn: turn, Provider: asst.Provider, Name: call.Name, Args: call.Args,
					OK: false, ErrorCode: "tool.unknown",
					Summary: "tool is not exposed by the phone (or is filtered by the allowlist)",
				})
				continue
			}
			started := a.now()
			outcome, cerr := a.dev.CallTool(ctx, call.Name, call.Args, device.CallToolOptions{
				CorrelationID: corrID,
				ReadOnly:      tool.ReadOnly(),
			})
			elapsed := a.now().Sub(started)
			if cerr != nil {
				// A transport failure against the phone is not a provider
				// failure: it is reported to the model as a tool error so the
				// same provider can decide what to do next turn.
				body := fmt.Sprintf(`{"error":"transport.failed","detail":%q}`, cerr.Error())
				toolMsg.Parts = append(toolMsg.Parts, provider.ToolResultPart(call.ID, body, true))
				res.Trace = append(res.Trace, ToolTrace{
					Turn: turn, Provider: asst.Provider, Name: call.Name, Args: call.Args,
					OK: false, ErrorCode: "transport.failed", Summary: cerr.Error(), Duration: elapsed,
				})
				continue
			}
			toolMsg.Parts = append(toolMsg.Parts, provider.ToolResultPart(call.ID, outcomeBody(outcome), !outcome.OK))
			res.Trace = append(res.Trace, ToolTrace{
				Turn: turn, Provider: asst.Provider, Name: call.Name, Args: call.Args,
				OK: outcome.OK, ErrorCode: outcome.ErrorCode,
				Summary: outcomeSummary(outcome), Duration: elapsed,
			})
		}
		transcript = append(transcript, toolMsg)
		a.persist(taskID, &seq, toolMsg)
	}

	res.Transcript = transcript
	err = fmt.Errorf("agent: reached max_turns (%d) without a final answer", a.cfg.MaxTurns)
	a.fail(taskID, err)
	return res, err
}

// outcomeBody renders a tool outcome as the JSON the model sees.
func outcomeBody(o device.ToolOutcome) string {
	if o.Structured != nil {
		if raw, err := json.Marshal(o.Structured); err == nil {
			return string(raw)
		}
	}
	if o.Text != "" {
		return o.Text
	}
	if raw, err := json.Marshal(map[string]any{"ok": o.OK}); err == nil {
		return string(raw)
	}
	return "{}"
}

// outcomeSummary renders a short single-line summary for the CLI trace.
func outcomeSummary(o device.ToolOutcome) string {
	switch {
	case o.ErrorCode == "policy.approval_required":
		return "approval required (pending_action_id=" + o.PendingActionID + ")"
	case o.ErrorCode == "policy.rate_limited":
		return fmt.Sprintf("rate limited (retry_after_ms=%d)", o.RetryAfterMs)
	case o.ErrorCode != "":
		if o.Detail != "" {
			return o.ErrorCode + ": " + o.Detail
		}
		return o.ErrorCode
	case o.Text != "":
		return firstLine(o.Text, 160)
	case o.Structured != nil:
		raw, _ := json.Marshal(o.Structured)
		return firstLine(string(raw), 160)
	default:
		return "ok"
	}
}

func firstLine(s string, n int) string {
	if i := strings.IndexAny(s, "\r\n"); i >= 0 {
		s = s[:i]
	}
	s = strings.TrimSpace(s)
	if len(s) > n {
		return s[:n] + "..."
	}
	return s
}

// stopDetailSuffix renders the provider's verbatim stop wording for an error
// message, or "" when it supplied none.
func stopDetailSuffix(gen provider.GenerateResult) string {
	if strings.TrimSpace(gen.StopDetails) == "" {
		return ""
	}
	return " [" + gen.StopDetails + "]"
}

func errText(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

func (a *Agent) persist(taskID string, seq *int, m provider.Message) {
	if a.store == nil {
		return
	}
	_ = a.store.AppendMessage(taskID, *seq, string(m.Role), m.Provider, m.Parts)
	*seq++
}

func (a *Agent) finish(taskID, answer string) {
	if a.store == nil {
		return
	}
	_ = a.store.FinishTask(taskID, "done", answer, "")
}

func (a *Agent) fail(taskID string, err error) {
	if a.store == nil {
		return
	}
	_ = a.store.FinishTask(taskID, "failed", "", errText(err))
}
