package provider

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
)

// DefaultAnthropicModel is used when the configuration omits a model.
const DefaultAnthropicModel = "claude-sonnet-5"

// anthropicVersion is the required anthropic-version request header.
const anthropicVersion = "2023-06-01"

// DefaultAnthropicMaxTokens is the response cap sent when the configuration
// omits max_tokens. It is deliberately generous: an extended-thinking turn
// spends part of the budget on thinking blocks, and a cap that truncates a
// tool_use block mid-arguments fails the whole task.
const DefaultAnthropicMaxTokens = 8192

// Thinking modes for the Messages API "thinking" request field.
const (
	// ThinkingAdaptive lets the model decide how much to think. It is the
	// gateway default and is sent explicitly rather than left to the API's
	// default, so a server-side change of default cannot silently alter the
	// shape of the assistant turns the gateway has to replay.
	ThinkingAdaptive = "adaptive"
	// ThinkingDisabled turns extended thinking off.
	ThinkingDisabled = "disabled"
)

// blockThinking and blockRedactedThinking are the two provider-bound block
// types the gateway must replay byte-identically. The Messages API rejects an
// assistant turn that drops or rewrites them.
const (
	blockThinking         = "thinking"
	blockRedactedThinking = "redacted_thinking"
)

// AnthropicProvider talks to the Anthropic Messages API.
type AnthropicProvider struct {
	name    string
	model   string
	apiKey  string
	baseURL string
	client  httpDoer
	// maxTokens is the response cap sent on every request.
	maxTokens int
	// thinking is the value of the request's thinking.type field.
	thinking string
}

// AnthropicOptions configures an AnthropicProvider.
type AnthropicOptions struct {
	// Name is the configured provider name.
	Name string
	// Model is the model id. Empty means DefaultAnthropicModel.
	Model string
	// APIKey is the Anthropic API key. It is never logged.
	APIKey string
	// BaseURL overrides https://api.anthropic.com.
	BaseURL string
	// MaxTokens caps the response. Zero means DefaultAnthropicMaxTokens.
	MaxTokens int
	// Thinking is "adaptive" or "disabled". Empty means ThinkingAdaptive.
	Thinking string
	// HTTPClient overrides the default HTTP client (used by tests).
	HTTPClient httpDoer
}

// NewAnthropic constructs an Anthropic Messages API provider.
func NewAnthropic(opts AnthropicOptions) *AnthropicProvider {
	p := &AnthropicProvider{
		name:      opts.Name,
		model:     opts.Model,
		apiKey:    opts.APIKey,
		baseURL:   strings.TrimRight(opts.BaseURL, "/"),
		client:    opts.HTTPClient,
		maxTokens: opts.MaxTokens,
		thinking:  strings.ToLower(strings.TrimSpace(opts.Thinking)),
	}
	if p.thinking == "" {
		p.thinking = ThinkingAdaptive
	}
	if p.model == "" {
		p.model = DefaultAnthropicModel
	}
	if p.baseURL == "" {
		p.baseURL = "https://api.anthropic.com"
	}
	if p.client == nil {
		p.client = defaultClient()
	}
	if p.maxTokens == 0 {
		p.maxTokens = DefaultAnthropicMaxTokens
	}
	return p
}

// Name returns the configured provider name.
func (p *AnthropicProvider) Name() string { return p.name }

// Capabilities describes the Anthropic Messages API surface.
func (p *AnthropicProvider) Capabilities() Capabilities {
	return Capabilities{
		TextStreaming: true,
		ToolCalling:   true,
		ImageInput:    true,
		ContextWindow: 200000,
	}
}

// --- wire types -------------------------------------------------------------

// anthropicRequest deliberately has no temperature field. Temperature is not
// accepted alongside extended thinking on the current Claude family and would
// fail the request, so the adapter cannot populate it even by accident.
type anthropicRequest struct {
	Model     string             `json:"model"`
	MaxTokens int                `json:"max_tokens"`
	System    string             `json:"system,omitempty"`
	Messages  []anthropicMessage `json:"messages"`
	Tools     []anthropicTool    `json:"tools,omitempty"`
	Thinking  *anthropicThinking `json:"thinking,omitempty"`
}

// anthropicThinking is the request's thinking configuration.
type anthropicThinking struct {
	// Type is "adaptive" or "disabled".
	Type string `json:"type"`
}

type anthropicMessage struct {
	Role    string           `json:"role"`
	Content []anthropicBlock `json:"content"`
}

type anthropicBlock struct {
	Type string `json:"type"`
	// text block
	Text string `json:"text,omitempty"`
	// tool_use block. Input is raw JSON because the Messages API requires the
	// field to be present even when the tool takes no arguments, and an empty
	// map would be dropped by omitempty.
	ID    string          `json:"id,omitempty"`
	Name  string          `json:"name,omitempty"`
	Input json.RawMessage `json:"input,omitempty"`
	// tool_result block
	ToolUseID string `json:"tool_use_id,omitempty"`
	Content   any    `json:"content,omitempty"`
	IsError   bool   `json:"is_error,omitempty"`

	// raw is the verbatim JSON of a provider-bound block (thinking,
	// redacted_thinking). When set it is emitted instead of the typed fields,
	// so the block — including its signature — survives a round trip byte for
	// byte. The Messages API rejects a re-signed or reordered thinking block.
	raw json.RawMessage
}

// MarshalJSON emits a provider-bound block verbatim, and every other block from
// its typed fields.
func (b anthropicBlock) MarshalJSON() ([]byte, error) {
	if len(b.raw) > 0 {
		return b.raw, nil
	}
	type plain anthropicBlock
	return json.Marshal(plain(b))
}

// UnmarshalJSON captures the verbatim bytes of a provider-bound block so it can
// be replayed unchanged.
func (b *anthropicBlock) UnmarshalJSON(data []byte) error {
	type plain anthropicBlock
	var v plain
	if err := json.Unmarshal(data, &v); err != nil {
		return err
	}
	*b = anthropicBlock(v)
	if b.Type == blockThinking || b.Type == blockRedactedThinking {
		b.raw = append(json.RawMessage(nil), data...)
	}
	return nil
}

type anthropicTool struct {
	Name        string         `json:"name"`
	Description string         `json:"description,omitempty"`
	InputSchema map[string]any `json:"input_schema"`
}

type anthropicResponse struct {
	ID           string           `json:"id"`
	Type         string           `json:"type"`
	Role         string           `json:"role"`
	Model        string           `json:"model"`
	Content      []anthropicBlock `json:"content"`
	StopReason   string           `json:"stop_reason"`
	StopSequence string           `json:"stop_sequence,omitempty"`
	// StopDetails is present on newer models; it is surfaced verbatim rather
	// than interpreted.
	StopDetails json.RawMessage `json:"stop_details,omitempty"`
	Usage       struct {
		InputTokens  int `json:"input_tokens"`
		OutputTokens int `json:"output_tokens"`
	} `json:"usage"`
}

// --- serialization ----------------------------------------------------------

// encodeAnthropicRequest converts a canonical request into Messages API wire form.
func (p *AnthropicProvider) encodeAnthropicRequest(req GenerateRequest) anthropicRequest {
	out := anthropicRequest{
		Model:     p.model,
		MaxTokens: p.maxTokens,
		System:    req.System,
		Thinking:  &anthropicThinking{Type: p.thinking},
	}
	if req.MaxTokens > 0 {
		out.MaxTokens = req.MaxTokens
	}
	// req.Temperature is deliberately ignored; see anthropicRequest.
	out.Messages = encodeAnthropicMessages(req.Messages, p.name)
	for _, t := range req.Tools {
		out.Tools = append(out.Tools, anthropicTool{
			Name:        t.Name,
			Description: t.Description,
			InputSchema: SanitizeAnthropicSchema(t.InputSchema),
		})
	}
	return out
}

// encodeAnthropicMessages maps canonical messages onto Anthropic's user/assistant
// alternation. Tool results become user messages containing tool_result blocks,
// which is what the Messages API expects.
func encodeAnthropicMessages(msgs []Message, providerName string) []anthropicMessage {
	var out []anthropicMessage
	for _, m := range msgs {
		switch m.Role {
		case RoleSystem:
			// The system prompt is hoisted into the top-level system field.
			continue
		case RoleAssistant:
			blocks := make([]anthropicBlock, 0, len(m.Parts))
			// Provider-bound blocks come first: the Messages API requires an
			// assistant turn produced with extended thinking to open with its
			// thinking block. OpaqueParts returns nothing unless the whole
			// message was produced by this provider, so a transcript that
			// failed over from another provider replays without them.
			for _, part := range m.OpaqueParts(providerName) {
				blocks = append(blocks, anthropicBlock{raw: part.Raw})
			}
			for _, part := range m.Parts {
				switch part.Type {
				case PartText:
					if part.Text == "" {
						continue
					}
					blocks = append(blocks, anthropicBlock{Type: "text", Text: part.Text})
				case PartToolCall:
					blocks = append(blocks, anthropicBlock{
						Type:  "tool_use",
						ID:    part.ID,
						Name:  part.Name,
						Input: encodeArgs(part.Args),
					})
				}
			}
			if len(blocks) == 0 {
				continue
			}
			out = append(out, anthropicMessage{Role: "assistant", Content: blocks})
		default: // RoleUser and RoleTool both map to a user-role message.
			blocks := make([]anthropicBlock, 0, len(m.Parts))
			for _, part := range m.Parts {
				switch part.Type {
				case PartText:
					if part.Text == "" {
						continue
					}
					blocks = append(blocks, anthropicBlock{Type: "text", Text: part.Text})
				case PartToolResult:
					blocks = append(blocks, anthropicBlock{
						Type:      "tool_result",
						ToolUseID: part.CallID,
						Content:   part.Content,
						IsError:   part.IsError,
					})
				}
			}
			if len(blocks) == 0 {
				continue
			}
			// Merge consecutive user-role messages so tool results batch into
			// one turn, as the Messages API requires.
			if n := len(out); n > 0 && out[n-1].Role == "user" {
				out[n-1].Content = append(out[n-1].Content, blocks...)
				continue
			}
			out = append(out, anthropicMessage{Role: "user", Content: blocks})
		}
	}
	return out
}

// decodeAnthropicMessages converts Messages API wire messages back into the
// canonical transcript. Tool-result blocks are recovered with RoleTool.
func decodeAnthropicMessages(msgs []anthropicMessage, providerName string) []Message {
	var out []Message
	for _, m := range msgs {
		if m.Role == "assistant" {
			cm := Message{Role: RoleAssistant, Provider: providerName}
			for _, b := range m.Content {
				switch b.Type {
				case "text":
					cm.Parts = append(cm.Parts, TextPart(b.Text))
				case "tool_use":
					cm.Parts = append(cm.Parts, ToolCallPart(b.ID, b.Name, decodeArgs(b.Input)))
				case blockThinking, blockRedactedThinking:
					if len(b.raw) > 0 && providerName != "" {
						cm.Parts = append(cm.Parts, OpaquePart(providerName, b.raw))
					}
				}
			}
			out = append(out, cm)
			continue
		}
		// A user-role message may mix plain text and tool results. Split it so
		// the canonical roles stay honest.
		var text Message
		var tools Message
		text.Role = RoleUser
		tools.Role = RoleTool
		for _, b := range m.Content {
			switch b.Type {
			case "text":
				text.Parts = append(text.Parts, TextPart(b.Text))
			case "tool_result":
				tools.Parts = append(tools.Parts, ToolResultPart(b.ToolUseID, anthropicContentString(b.Content), b.IsError))
			}
		}
		if len(tools.Parts) > 0 {
			out = append(out, tools)
		}
		if len(text.Parts) > 0 {
			out = append(out, text)
		}
	}
	return out
}

// anthropicContentString renders a tool_result content field, which the API
// accepts either as a plain string or as an array of content blocks.
func anthropicContentString(v any) string {
	switch t := v.(type) {
	case nil:
		return ""
	case string:
		return t
	case []any:
		var b strings.Builder
		for _, e := range t {
			m, ok := e.(map[string]any)
			if !ok {
				continue
			}
			if s, ok := m["text"].(string); ok {
				if b.Len() > 0 {
					b.WriteString("\n")
				}
				b.WriteString(s)
			}
		}
		return b.String()
	default:
		raw, err := json.Marshal(v)
		if err != nil {
			return ""
		}
		return string(raw)
	}
}

// decodeAnthropicResponse converts a Messages API response into a canonical
// assistant message.
func decodeAnthropicResponse(name string, resp anthropicResponse) GenerateResult {
	msg := Message{Role: RoleAssistant, Provider: name}
	// Provider-bound blocks are captured in the order the API sent them, which
	// is the order they must be replayed in.
	for _, b := range resp.Content {
		switch b.Type {
		case "text":
			msg.Parts = append(msg.Parts, TextPart(b.Text))
		case "tool_use":
			msg.Parts = append(msg.Parts, ToolCallPart(b.ID, b.Name, decodeArgs(b.Input)))
		case blockThinking, blockRedactedThinking:
			if len(b.raw) > 0 {
				msg.Parts = append(msg.Parts, OpaquePart(name, b.raw))
			}
		}
	}
	stop := StopOther
	switch resp.StopReason {
	case "end_turn", "stop_sequence":
		stop = StopEndTurn
	case "tool_use", "pause_turn":
		stop = StopToolUse
	case "max_tokens":
		stop = StopMaxTokens
	case "refusal":
		stop = StopRefusal
	}
	return GenerateResult{
		Message:     msg,
		StopReason:  stop,
		StopDetails: anthropicStopDetails(resp),
		Usage:       Usage{InputTokens: resp.Usage.InputTokens, OutputTokens: resp.Usage.OutputTokens},
		Provider:    name,
	}
}

// anthropicStopDetails renders the provider's own stop wording for diagnostics.
func anthropicStopDetails(resp anthropicResponse) string {
	var parts []string
	if resp.StopReason != "" {
		parts = append(parts, "stop_reason="+resp.StopReason)
	}
	if resp.StopSequence != "" {
		parts = append(parts, "stop_sequence="+resp.StopSequence)
	}
	if len(resp.StopDetails) > 0 && string(resp.StopDetails) != "null" {
		parts = append(parts, "stop_details="+truncate(string(resp.StopDetails), 256))
	}
	return strings.Join(parts, " ")
}

// --- Provider ---------------------------------------------------------------

// Generate runs one turn against the Anthropic Messages API.
func (p *AnthropicProvider) Generate(ctx context.Context, req GenerateRequest) (GenerateResult, error) {
	req, names := aliasToolNames(req)
	wire := p.encodeAnthropicRequest(req)
	var resp anthropicResponse
	err := postJSON(ctx, p.client, p.name, p.baseURL+"/v1/messages", p.headers(), wire, &resp)
	if err != nil {
		return GenerateResult{}, err
	}
	return restoreToolNames(decodeAnthropicResponse(p.name, resp), names), nil
}

func (p *AnthropicProvider) headers() map[string]string {
	return map[string]string{
		"x-api-key":         p.apiKey,
		"anthropic-version": anthropicVersion,
	}
}

// Health confirms the endpoint, the credentials and the configured model with
// GET /v1/models/{model}. It is a read-only probe, so it cannot be fooled by a
// request-shape bug the way a minimal POST /v1/messages could: any 4xx is a
// failure and the truncated, credential-free body is reported.
func (p *AnthropicProvider) Health(ctx context.Context) error {
	if p.apiKey == "" {
		return fmt.Errorf("provider %s: no API key configured", p.name)
	}
	var out struct {
		ID   string `json:"id"`
		Type string `json:"type"`
	}
	endpoint := p.baseURL + "/v1/models/" + url.PathEscape(p.model)
	if err := getJSON(ctx, p.client, p.name, endpoint, p.headers(), &out); err != nil {
		var pe *Error
		if errors.As(err, &pe) {
			switch pe.Status {
			case http.StatusUnauthorized, http.StatusForbidden:
				return fmt.Errorf("provider %s: credentials rejected (http %d): %s", p.name, pe.Status, pe.Body)
			case http.StatusNotFound:
				return fmt.Errorf("provider %s: model %q is not available to this key (http 404): %s",
					p.name, p.model, pe.Body)
			}
		}
		return err
	}
	if out.ID == "" {
		return fmt.Errorf("provider %s: model %q not found", p.name, p.model)
	}
	return nil
}
