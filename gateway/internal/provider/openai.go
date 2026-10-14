package provider

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"strings"
)

// DefaultOpenAIModel is used when the configuration omits a model.
const DefaultOpenAIModel = "gpt-4o"

// OpenAIProvider talks to the OpenAI chat-completions API. Because the wire
// format is widely cloned, it also drives Ollama, LM Studio and vLLM through a
// base_url override.
type OpenAIProvider struct {
	name      string
	model     string
	apiKey    string
	baseURL   string
	client    httpDoer
	maxTokens int
}

// OpenAIOptions configures an OpenAIProvider.
type OpenAIOptions struct {
	// Name is the configured provider name.
	Name string
	// Model is the model id. Empty means DefaultOpenAIModel.
	Model string
	// APIKey is the bearer token. It may be empty for keyless local servers.
	APIKey string
	// BaseURL overrides https://api.openai.com/v1. Point it at a local
	// OpenAI-compatible server to run Ollama, LM Studio or vLLM.
	BaseURL string
	// MaxTokens caps the response. Zero means no cap is sent.
	MaxTokens int
	// HTTPClient overrides the default HTTP client (used by tests).
	HTTPClient httpDoer
}

// NewOpenAI constructs an OpenAI-compatible chat-completions provider.
func NewOpenAI(opts OpenAIOptions) *OpenAIProvider {
	p := &OpenAIProvider{
		name:      opts.Name,
		model:     opts.Model,
		apiKey:    opts.APIKey,
		baseURL:   strings.TrimRight(opts.BaseURL, "/"),
		client:    opts.HTTPClient,
		maxTokens: opts.MaxTokens,
	}
	if p.model == "" {
		p.model = DefaultOpenAIModel
	}
	if p.baseURL == "" {
		p.baseURL = "https://api.openai.com/v1"
	}
	p.baseURL = normalizeOpenAIBaseURL(p.baseURL)
	if p.client == nil {
		p.client = defaultClient()
	}
	return p
}

// normalizeOpenAIBaseURL appends the API version segment when the configured
// base_url carries no path. "http://127.0.0.1:11434" is the address a user reads
// off an Ollama or LM Studio window; the chat-completions route lives under
// "/v1", and joining without it produces a 404 that looks like an outage. A
// base_url that already has a path is left exactly as configured, because
// gateways and proxies mount the API under their own prefixes.
func normalizeOpenAIBaseURL(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return raw
	}
	if strings.Trim(u.Path, "/") == "" {
		u.Path = "/v1"
		return strings.TrimRight(u.String(), "/")
	}
	return raw
}

// openAIUsesMaxCompletionTokens reports whether a model rejects "max_tokens" in
// favour of "max_completion_tokens". The reasoning families (o1/o3/o4, gpt-5)
// took that rename; sending the old field to them is a 400.
func openAIUsesMaxCompletionTokens(model string) bool {
	m := strings.ToLower(strings.TrimSpace(model))
	// Strip a provider routing prefix such as "openai/gpt-5".
	if i := strings.LastIndex(m, "/"); i >= 0 {
		m = m[i+1:]
	}
	if strings.HasPrefix(m, "gpt-5") {
		return true
	}
	for _, pre := range []string{"o1", "o3", "o4"} {
		if m == pre || strings.HasPrefix(m, pre+"-") {
			return true
		}
	}
	return false
}

// Name returns the configured provider name.
func (p *OpenAIProvider) Name() string { return p.name }

// Capabilities describes the chat-completions surface.
func (p *OpenAIProvider) Capabilities() Capabilities {
	return Capabilities{
		TextStreaming: true,
		ToolCalling:   true,
		ImageInput:    true,
		LocalOffline:  !strings.Contains(p.baseURL, "api.openai.com"),
		ContextWindow: 128000,
	}
}

// --- wire types -------------------------------------------------------------

type openAIRequest struct {
	Model      string          `json:"model"`
	Messages   []openAIMessage `json:"messages"`
	Tools      []openAITool    `json:"tools,omitempty"`
	ToolChoice string          `json:"tool_choice,omitempty"`
	MaxTokens  int             `json:"max_tokens,omitempty"`
	// MaxCompletionTokens replaces MaxTokens on the reasoning families.
	MaxCompletionTokens int      `json:"max_completion_tokens,omitempty"`
	Temperature         *float64 `json:"temperature,omitempty"`
}

type openAIMessage struct {
	Role       string           `json:"role"`
	Content    *string          `json:"content"`
	ToolCalls  []openAIToolCall `json:"tool_calls,omitempty"`
	ToolCallID string           `json:"tool_call_id,omitempty"`
	Name       string           `json:"name,omitempty"`
}

type openAIToolCall struct {
	ID       string             `json:"id"`
	Type     string             `json:"type"`
	Function openAIToolCallFunc `json:"function"`
}

type openAIToolCallFunc struct {
	Name string `json:"name"`
	// Arguments is a JSON document encoded as a string, per the API.
	Arguments string `json:"arguments"`
}

type openAITool struct {
	Type     string         `json:"type"`
	Function openAIFunction `json:"function"`
}

type openAIFunction struct {
	Name        string         `json:"name"`
	Description string         `json:"description,omitempty"`
	Parameters  map[string]any `json:"parameters"`
}

type openAIResponse struct {
	ID      string `json:"id"`
	Object  string `json:"object"`
	Model   string `json:"model"`
	Choices []struct {
		Index        int           `json:"index"`
		Message      openAIMessage `json:"message"`
		FinishReason string        `json:"finish_reason"`
	} `json:"choices"`
	Usage struct {
		PromptTokens     int `json:"prompt_tokens"`
		CompletionTokens int `json:"completion_tokens"`
	} `json:"usage"`
}

// --- serialization ----------------------------------------------------------

func strptr(s string) *string { return &s }

// encodeOpenAIRequest converts a canonical request into chat-completions wire form.
func (p *OpenAIProvider) encodeOpenAIRequest(req GenerateRequest) openAIRequest {
	out := openAIRequest{
		Model:       p.model,
		Temperature: req.Temperature,
	}
	cap := req.MaxTokens
	if cap == 0 {
		cap = p.maxTokens
	}
	if cap > 0 {
		if openAIUsesMaxCompletionTokens(p.model) {
			out.MaxCompletionTokens = cap
		} else {
			out.MaxTokens = cap
		}
	}
	if req.System != "" {
		out.Messages = append(out.Messages, openAIMessage{Role: "system", Content: strptr(req.System)})
	}
	out.Messages = append(out.Messages, encodeOpenAIMessages(req.Messages)...)
	for _, t := range req.Tools {
		schema := t.InputSchema
		if schema == nil {
			schema = map[string]any{"type": "object", "properties": map[string]any{}}
		}
		out.Tools = append(out.Tools, openAITool{
			Type: "function",
			Function: openAIFunction{
				Name:        t.Name,
				Description: t.Description,
				Parameters:  SanitizeOpenAISchema(schema),
			},
		})
	}
	return out
}

// encodeOpenAIMessages maps canonical messages onto chat-completions messages.
// Each canonical tool result becomes its own role:"tool" message.
func encodeOpenAIMessages(msgs []Message) []openAIMessage {
	var out []openAIMessage
	for _, m := range msgs {
		switch m.Role {
		case RoleSystem:
			out = append(out, openAIMessage{Role: "system", Content: strptr(m.TextOnly())})
		case RoleUser:
			out = append(out, openAIMessage{Role: "user", Content: strptr(m.TextOnly())})
		case RoleAssistant:
			am := openAIMessage{Role: "assistant"}
			text := m.TextOnly()
			if text != "" {
				am.Content = strptr(text)
			}
			for _, part := range m.Parts {
				if part.Type != PartToolCall {
					continue
				}
				args := "{}"
				if part.Args != nil {
					if raw, err := CanonicalJSON(part.Args); err == nil {
						args = string(raw)
					}
				}
				am.ToolCalls = append(am.ToolCalls, openAIToolCall{
					ID:       part.ID,
					Type:     "function",
					Function: openAIToolCallFunc{Name: part.Name, Arguments: args},
				})
			}
			out = append(out, am)
		case RoleTool:
			for _, part := range m.Parts {
				if part.Type != PartToolResult {
					continue
				}
				out = append(out, openAIMessage{
					Role:       "tool",
					ToolCallID: part.CallID,
					Content:    strptr(part.Content),
				})
			}
		}
	}
	return out
}

// decodeOpenAIMessages converts chat-completions wire messages back into the
// canonical transcript. Consecutive role:"tool" messages collapse into one
// canonical tool message, mirroring encodeOpenAIMessages.
func decodeOpenAIMessages(msgs []openAIMessage) []Message {
	var out []Message
	for _, m := range msgs {
		switch m.Role {
		case "system":
			out = append(out, Message{Role: RoleSystem, Parts: []Part{TextPart(deref(m.Content))}})
		case "user":
			out = append(out, Message{Role: RoleUser, Parts: []Part{TextPart(deref(m.Content))}})
		case "assistant":
			cm := Message{Role: RoleAssistant}
			if s := deref(m.Content); s != "" {
				cm.Parts = append(cm.Parts, TextPart(s))
			}
			for _, tc := range m.ToolCalls {
				cm.Parts = append(cm.Parts, ToolCallPart(tc.ID, tc.Function.Name, decodeArguments(tc.Function.Arguments)))
			}
			out = append(out, cm)
		case "tool":
			part := ToolResultPart(m.ToolCallID, deref(m.Content), false)
			if n := len(out); n > 0 && out[n-1].Role == RoleTool {
				out[n-1].Parts = append(out[n-1].Parts, part)
				continue
			}
			out = append(out, Message{Role: RoleTool, Parts: []Part{part}})
		}
	}
	return out
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

// decodeArguments parses the JSON-string arguments field of a tool call.
// Malformed arguments are surfaced under a "_raw" key rather than dropped, so
// the agent can report what the model actually emitted.
func decodeArguments(s string) map[string]any {
	if strings.TrimSpace(s) == "" {
		return map[string]any{}
	}
	var m map[string]any
	if err := json.Unmarshal([]byte(s), &m); err != nil {
		return map[string]any{"_raw": s}
	}
	if m == nil {
		return map[string]any{}
	}
	return m
}

// decodeOpenAIResponse converts a chat-completions response into a canonical
// assistant message.
func decodeOpenAIResponse(name string, resp openAIResponse) GenerateResult {
	msg := Message{Role: RoleAssistant, Provider: name}
	stop := StopOther
	if len(resp.Choices) > 0 {
		ch := resp.Choices[0]
		if s := deref(ch.Message.Content); s != "" {
			msg.Parts = append(msg.Parts, TextPart(s))
		}
		for _, tc := range ch.Message.ToolCalls {
			msg.Parts = append(msg.Parts, ToolCallPart(tc.ID, tc.Function.Name, decodeArguments(tc.Function.Arguments)))
		}
		switch ch.FinishReason {
		case "stop":
			stop = StopEndTurn
		case "tool_calls", "function_call":
			stop = StopToolUse
		case "length":
			stop = StopMaxTokens
		case "content_filter":
			stop = StopRefusal
		}
		if ch.FinishReason != "" {
			// Surfaced verbatim so a stop reason this adapter does not model
			// still reaches the operator.
			return GenerateResult{
				Message:     msg,
				StopReason:  stop,
				StopDetails: "finish_reason=" + ch.FinishReason,
				Usage:       Usage{InputTokens: resp.Usage.PromptTokens, OutputTokens: resp.Usage.CompletionTokens},
				Provider:    name,
			}
		}
	}
	return GenerateResult{
		Message:    msg,
		StopReason: stop,
		Usage:      Usage{InputTokens: resp.Usage.PromptTokens, OutputTokens: resp.Usage.CompletionTokens},
		Provider:   name,
	}
}

// --- Provider ---------------------------------------------------------------

// Generate runs one turn against the chat-completions endpoint.
func (p *OpenAIProvider) Generate(ctx context.Context, req GenerateRequest) (GenerateResult, error) {
	req, names := aliasToolNames(req)
	wire := p.encodeOpenAIRequest(req)
	var resp openAIResponse
	if err := postJSON(ctx, p.client, p.name, p.baseURL+"/chat/completions", p.headers(), wire, &resp); err != nil {
		return GenerateResult{}, err
	}
	if len(resp.Choices) == 0 {
		return GenerateResult{}, &Error{Provider: p.name, Err: fmt.Errorf("response contained no choices")}
	}
	return restoreToolNames(decodeOpenAIResponse(p.name, resp), names), nil
}

func (p *OpenAIProvider) headers() map[string]string {
	h := map[string]string{}
	if p.apiKey != "" {
		h["Authorization"] = "Bearer " + p.apiKey
	}
	return h
}

// Health lists models on the configured endpoint.
func (p *OpenAIProvider) Health(ctx context.Context) error {
	var out struct {
		Data []struct {
			ID string `json:"id"`
		} `json:"data"`
	}
	if err := getJSON(ctx, p.client, p.name, p.baseURL+"/models", p.headers(), &out); err != nil {
		return err
	}
	if len(out.Data) == 0 {
		return fmt.Errorf("provider %s: endpoint reported no models", p.name)
	}
	return nil
}
