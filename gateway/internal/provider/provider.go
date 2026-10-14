// Package provider defines the gateway's model abstraction.
//
// The gateway keeps one provider-neutral canonical transcript for a task. Each
// adapter serializes that transcript into its own wire format and parses the
// response back into canonical parts, so the same transcript can be replayed
// against a different provider at a turn boundary during failover.
package provider

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/canonicaljson"
)

// Role is the author of a canonical message.
type Role string

// Canonical message roles.
const (
	// RoleSystem is the system instruction. Adapters hoist it into whatever
	// their wire format calls a system prompt.
	RoleSystem Role = "system"
	// RoleUser is a message from the operator.
	RoleUser Role = "user"
	// RoleAssistant is a message produced by a model.
	RoleAssistant Role = "assistant"
	// RoleTool carries tool results back to the model.
	RoleTool Role = "tool"
)

// PartType discriminates the typed parts of a canonical message.
type PartType string

// Canonical part types.
const (
	// PartText is plain text.
	PartText PartType = "text"
	// PartToolCall is a model request to invoke a tool.
	PartToolCall PartType = "tool_call"
	// PartToolResult is the outcome of a tool invocation.
	PartToolResult PartType = "tool_result"
	// PartOpaque is a provider-bound block the gateway does not interpret. It
	// exists so a provider that requires its own content blocks to be replayed
	// byte-identically (Anthropic extended thinking, for example) can round-trip
	// them through the canonical transcript. An opaque part is only ever emitted
	// back to the provider named in Provider; every other adapter drops it.
	PartOpaque PartType = "opaque"
)

// Part is one typed element of a canonical message.
type Part struct {
	// Type discriminates the fields below.
	Type PartType `json:"type"`
	// Text is set when Type is PartText.
	Text string `json:"text,omitempty"`
	// ID is the tool-call id when Type is PartToolCall.
	ID string `json:"id,omitempty"`
	// Name is the tool name when Type is PartToolCall.
	Name string `json:"name,omitempty"`
	// Args are the tool arguments when Type is PartToolCall.
	Args map[string]any `json:"args,omitempty"`
	// CallID references the originating tool call when Type is PartToolResult.
	CallID string `json:"call_id,omitempty"`
	// Content is the tool result body when Type is PartToolResult. It is a JSON
	// document when the tool produced structured content, otherwise plain text.
	Content string `json:"content,omitempty"`
	// IsError marks a failed tool result.
	IsError bool `json:"is_error,omitempty"`
	// Provider binds an opaque part to the provider that produced it. It is set
	// only when Type is PartOpaque, and an adapter must refuse to replay a part
	// bound to a different provider.
	Provider string `json:"opaque_provider,omitempty"`
	// Raw is the verbatim provider block when Type is PartOpaque. It is stored
	// and replayed byte for byte; the gateway never inspects or rewrites it.
	Raw json.RawMessage `json:"raw,omitempty"`
}

// Text builds a text part.
func TextPart(s string) Part { return Part{Type: PartText, Text: s} }

// ToolCallPart builds a tool-call part.
func ToolCallPart(id, name string, args map[string]any) Part {
	return Part{Type: PartToolCall, ID: id, Name: name, Args: args}
}

// OpaquePart builds a provider-bound opaque part carrying a verbatim provider
// content block. providerName must be the configured provider name, not the
// model id.
func OpaquePart(providerName string, raw json.RawMessage) Part {
	cp := make(json.RawMessage, len(raw))
	copy(cp, raw)
	return Part{Type: PartOpaque, Provider: providerName, Raw: cp}
}

// ToolResultPart builds a tool-result part.
func ToolResultPart(callID, content string, isError bool) Part {
	return Part{Type: PartToolResult, CallID: callID, Content: content, IsError: isError}
}

// Message is one entry in the canonical transcript.
type Message struct {
	// Role is the message author.
	Role Role `json:"role"`
	// Parts are the typed contents of the message.
	Parts []Part `json:"parts"`
	// Provider records which provider produced an assistant message. It is
	// empty for messages the gateway authored.
	Provider string `json:"provider,omitempty"`
}

// TextOnly concatenates every text part of the message.
func (m Message) TextOnly() string {
	var b strings.Builder
	for _, p := range m.Parts {
		if p.Type == PartText {
			if b.Len() > 0 {
				b.WriteString("\n")
			}
			b.WriteString(p.Text)
		}
	}
	return b.String()
}

// OpaqueParts returns the opaque parts bound to providerName, in order. Parts
// bound to any other provider are skipped, which is what keeps a transcript that
// crossed a failover boundary from replaying another provider's blocks.
func (m Message) OpaqueParts(providerName string) []Part {
	if providerName == "" || m.Provider != providerName {
		return nil
	}
	var out []Part
	for _, p := range m.Parts {
		if p.Type == PartOpaque && p.Provider == providerName && len(p.Raw) > 0 {
			out = append(out, p)
		}
	}
	return out
}

// ToolCalls returns the tool-call parts of the message.
func (m Message) ToolCalls() []Part {
	var out []Part
	for _, p := range m.Parts {
		if p.Type == PartToolCall {
			out = append(out, p)
		}
	}
	return out
}

// ToolDef is a provider-neutral tool definition.
type ToolDef struct {
	// Name is the tool name, passed through verbatim from the phone.
	Name string `json:"name"`
	// Description is human/model-readable prose.
	Description string `json:"description"`
	// InputSchema is a JSON Schema object describing the arguments.
	InputSchema map[string]any `json:"input_schema"`
}

// GenerateRequest is one provider turn.
type GenerateRequest struct {
	// System is the system prompt.
	System string
	// Messages is the canonical transcript, excluding the system prompt.
	Messages []Message
	// Tools are the tool definitions offered to the model.
	Tools []ToolDef
	// MaxTokens caps the response. Zero means the adapter default.
	MaxTokens int
	// Temperature overrides sampling when non-nil.
	Temperature *float64
}

// Usage reports token consumption when the provider supplies it.
type Usage struct {
	// InputTokens is the prompt token count.
	InputTokens int
	// OutputTokens is the completion token count.
	OutputTokens int
}

// StopReason explains why generation ended.
type StopReason string

// Canonical stop reasons.
const (
	// StopEndTurn means the model produced a final answer.
	StopEndTurn StopReason = "end_turn"
	// StopToolUse means the model wants tools invoked.
	StopToolUse StopReason = "tool_use"
	// StopMaxTokens means the response hit the token cap.
	StopMaxTokens StopReason = "max_tokens"
	// StopRefusal means the provider declined to answer.
	StopRefusal StopReason = "refusal"
	// StopOther is anything else the provider reported.
	StopOther StopReason = "other"
)

// Delta is an incremental streaming chunk. Streaming is not implemented in v0;
// the type exists so GenerateResult can carry a stream without an API change.
type Delta struct {
	// Text is appended assistant text.
	Text string
	// Part is a completed part, when the chunk finishes one.
	Part *Part
	// Err terminates the stream.
	Err error
}

// GenerateResult is one assistant turn.
type GenerateResult struct {
	// Message is the assistant message in canonical form.
	Message Message
	// StopReason explains why generation ended.
	StopReason StopReason
	// StopDetails is the provider's verbatim stop_reason (and any stop_details
	// object) rendered for a human, or "" when the provider supplied none. It is
	// diagnostic only; control flow keys off StopReason.
	StopDetails string
	// Usage reports token consumption when available.
	Usage Usage
	// Provider names the provider that produced this result.
	Provider string
	// Stream is non-nil only when the adapter streamed the response. v0
	// adapters always leave it nil and fill Message instead.
	Stream <-chan Delta
}

// Capabilities describes what a provider supports.
type Capabilities struct {
	// TextStreaming reports incremental text output support.
	TextStreaming bool
	// ToolCalling reports function/tool calling support.
	ToolCalling bool
	// ImageInput reports image input support.
	ImageInput bool
	// NativeAudioInput reports direct audio input support.
	NativeAudioInput bool
	// NativeAudioOutput reports direct audio output support.
	NativeAudioOutput bool
	// LocalOffline is true for providers that run without internet access.
	LocalOffline bool
	// ContextWindow is the advertised context size in tokens, or 0 if unknown.
	ContextWindow int
}

// Provider is one model backend.
type Provider interface {
	// Name is the configured provider name (not the model id).
	Name() string
	// Capabilities describes what this provider supports.
	Capabilities() Capabilities
	// Generate runs one turn against the canonical transcript.
	Generate(ctx context.Context, req GenerateRequest) (GenerateResult, error)
	// Health reports whether the provider is reachable and usable.
	Health(ctx context.Context) error
}

// Error is a provider failure with enough detail for failover decisions.
type Error struct {
	// Provider is the configured provider name.
	Provider string
	// Status is the HTTP status code, or 0 for transport errors.
	Status int
	// Body is a truncated, redacted response body.
	Body string
	// RetryAfter is the server's Retry-After hint, or 0 when it sent none. It is
	// a floor for the next backoff, not a replacement for it.
	RetryAfter time.Duration
	// Err is the underlying error, if any.
	Err error
}

// Error implements the error interface.
func (e *Error) Error() string {
	switch {
	case e.Err != nil && e.Status != 0:
		return fmt.Sprintf("provider %s: http %d: %v", e.Provider, e.Status, e.Err)
	case e.Err != nil:
		return fmt.Sprintf("provider %s: %v", e.Provider, e.Err)
	default:
		return fmt.Sprintf("provider %s: http %d: %s", e.Provider, e.Status, e.Body)
	}
}

// Unwrap exposes the underlying error.
func (e *Error) Unwrap() error { return e.Err }

// Retryable reports whether the failure is worth retrying on the same provider.
// 529 is Anthropic's "overloaded" and falls inside the 5xx range.
func (e *Error) Retryable() bool {
	return e.Status == 429 || (e.Status >= 500 && e.Status <= 599) || (e.Status == 0 && e.Err != nil)
}

// CanonicalJSON renders v with object keys sorted and no insignificant
// whitespace. It is the canonical form used for idempotency keys and for
// deterministic tool-argument encoding.
func CanonicalJSON(v any) ([]byte, error) {
	return canonicaljson.Marshal(v)
}

// encodeArgs renders tool-call arguments as raw JSON. Nil and empty maps both
// become "{}" so adapters that require the field to be present emit it.
func encodeArgs(args map[string]any) json.RawMessage {
	if len(args) == 0 {
		return json.RawMessage("{}")
	}
	raw, err := canonicaljson.Marshal(args)
	if err != nil {
		return json.RawMessage("{}")
	}
	return json.RawMessage(raw)
}

// decodeArgs parses raw JSON tool-call arguments back into a map. Absent or
// malformed arguments become an empty map rather than nil, so round trips are
// structurally stable.
func decodeArgs(raw json.RawMessage) map[string]any {
	if len(raw) == 0 {
		return map[string]any{}
	}
	var m map[string]any
	if err := json.Unmarshal(raw, &m); err != nil || m == nil {
		return map[string]any{}
	}
	return m
}
