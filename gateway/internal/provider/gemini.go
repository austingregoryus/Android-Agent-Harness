package provider

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"strings"
)

// DefaultGeminiModel is used when the configuration omits a model.
const DefaultGeminiModel = "gemini-2.5-flash"

// GeminiProvider talks to the Google Gemini generateContent API.
type GeminiProvider struct {
	name      string
	model     string
	apiKey    string
	baseURL   string
	client    httpDoer
	maxTokens int
}

// GeminiOptions configures a GeminiProvider.
type GeminiOptions struct {
	// Name is the configured provider name.
	Name string
	// Model is the model id. Empty means DefaultGeminiModel.
	Model string
	// APIKey is the Google AI Studio API key. It is never logged.
	APIKey string
	// BaseURL overrides https://generativelanguage.googleapis.com/v1beta.
	BaseURL string
	// MaxTokens caps the response. Zero means no cap is sent.
	MaxTokens int
	// HTTPClient overrides the default HTTP client (used by tests).
	HTTPClient httpDoer
}

// NewGemini constructs a Gemini generateContent provider.
func NewGemini(opts GeminiOptions) *GeminiProvider {
	p := &GeminiProvider{
		name:      opts.Name,
		model:     opts.Model,
		apiKey:    opts.APIKey,
		baseURL:   strings.TrimRight(opts.BaseURL, "/"),
		client:    opts.HTTPClient,
		maxTokens: opts.MaxTokens,
	}
	if p.model == "" {
		p.model = DefaultGeminiModel
	}
	if p.baseURL == "" {
		p.baseURL = "https://generativelanguage.googleapis.com/v1beta"
	}
	if p.client == nil {
		p.client = defaultClient()
	}
	return p
}

// Name returns the configured provider name.
func (p *GeminiProvider) Name() string { return p.name }

// Capabilities describes the Gemini generateContent surface.
func (p *GeminiProvider) Capabilities() Capabilities {
	return Capabilities{
		TextStreaming:    true,
		ToolCalling:      true,
		ImageInput:       true,
		NativeAudioInput: true,
		ContextWindow:    1000000,
	}
}

// --- wire types -------------------------------------------------------------

type geminiRequest struct {
	SystemInstruction *geminiContent          `json:"systemInstruction,omitempty"`
	Contents          []geminiContent         `json:"contents"`
	Tools             []geminiTool            `json:"tools,omitempty"`
	GenerationConfig  *geminiGenerationConfig `json:"generationConfig,omitempty"`
}

type geminiGenerationConfig struct {
	MaxOutputTokens int      `json:"maxOutputTokens,omitempty"`
	Temperature     *float64 `json:"temperature,omitempty"`
}

type geminiContent struct {
	Role  string       `json:"role,omitempty"`
	Parts []geminiPart `json:"parts"`
}

type geminiPart struct {
	Text             string                  `json:"text,omitempty"`
	FunctionCall     *geminiFunctionCall     `json:"functionCall,omitempty"`
	FunctionResponse *geminiFunctionResponse `json:"functionResponse,omitempty"`
}

type geminiFunctionCall struct {
	// ID disambiguates parallel calls to the same function. Newer models emit
	// it; when present it is the canonical tool-call id and is echoed back on
	// the matching functionResponse.
	ID   string `json:"id,omitempty"`
	Name string `json:"name"`
	// Args is raw JSON so a no-argument call still emits "args": {}.
	Args json.RawMessage `json:"args,omitempty"`
}

type geminiFunctionResponse struct {
	// ID matches the functionCall this responds to. It is only emitted when the
	// model supplied one on the call.
	ID       string         `json:"id,omitempty"`
	Name     string         `json:"name"`
	Response map[string]any `json:"response"`
}

type geminiTool struct {
	FunctionDeclarations []geminiFunctionDeclaration `json:"functionDeclarations"`
}

type geminiFunctionDeclaration struct {
	Name        string         `json:"name"`
	Description string         `json:"description,omitempty"`
	Parameters  map[string]any `json:"parameters"`
}

type geminiResponse struct {
	Candidates []struct {
		Content      geminiContent `json:"content"`
		FinishReason string        `json:"finishReason"`
	} `json:"candidates"`
	UsageMetadata struct {
		PromptTokenCount     int `json:"promptTokenCount"`
		CandidatesTokenCount int `json:"candidatesTokenCount"`
	} `json:"usageMetadata"`
}

// --- serialization ----------------------------------------------------------

// geminiToolName encodes a dotted phone capability name into a Gemini-legal
// function name. Gemini function names must match [A-Za-z0-9_.-]{1,64}, which
// already permits dots, so this is currently an identity mapping. It exists as
// the single place to change if that constraint tightens.
func geminiToolName(name string) string { return name }

// geminiCallIDPrefix marks a tool-call id this adapter minted because the model
// sent none. Ids with the prefix are local bookkeeping and are never sent back
// to Gemini as functionCall.id.
const geminiCallIDPrefix = "call_"

// geminiModelCallID returns the id the model itself minted, or "" when the id
// was synthesized locally by decodeGeminiResponse.
func geminiModelCallID(canonicalID string) string {
	if canonicalID == "" || strings.HasPrefix(canonicalID, geminiCallIDPrefix) {
		return ""
	}
	return canonicalID
}

// encodeGeminiRequest converts a canonical request into generateContent wire form.
func (p *GeminiProvider) encodeGeminiRequest(req GenerateRequest) geminiRequest {
	out := geminiRequest{}
	if req.System != "" {
		out.SystemInstruction = &geminiContent{Parts: []geminiPart{{Text: req.System}}}
	}
	out.Contents = encodeGeminiContents(req.Messages)
	if len(req.Tools) > 0 {
		decls := make([]geminiFunctionDeclaration, 0, len(req.Tools))
		for _, t := range req.Tools {
			schema := t.InputSchema
			if schema == nil {
				schema = map[string]any{"type": "object", "properties": map[string]any{}}
			}
			decls = append(decls, geminiFunctionDeclaration{
				Name:        geminiToolName(t.Name),
				Description: t.Description,
				Parameters:  SanitizeGeminiSchema(schema),
			})
		}
		out.Tools = []geminiTool{{FunctionDeclarations: decls}}
	}
	maxTok := req.MaxTokens
	if maxTok == 0 {
		maxTok = p.maxTokens
	}
	if maxTok > 0 || req.Temperature != nil {
		out.GenerationConfig = &geminiGenerationConfig{MaxOutputTokens: maxTok, Temperature: req.Temperature}
	}
	return out
}

// encodeGeminiContents maps canonical messages onto Gemini contents. Gemini has
// only "user" and "model" roles; function responses ride in a user-role content.
func encodeGeminiContents(msgs []Message) []geminiContent {
	// callNames maps tool-call ids to tool names, because Gemini identifies a
	// functionResponse by name when the model minted no id.
	callNames := map[string]string{}
	// modelIDs maps a canonical tool-call id to the id the model itself sent, so
	// parallel calls to the same function stay distinguishable on the way back.
	modelIDs := map[string]string{}
	var out []geminiContent
	for _, m := range msgs {
		switch m.Role {
		case RoleSystem:
			continue
		case RoleAssistant:
			c := geminiContent{Role: "model"}
			for _, part := range m.Parts {
				switch part.Type {
				case PartText:
					if part.Text == "" {
						continue
					}
					c.Parts = append(c.Parts, geminiPart{Text: part.Text})
				case PartToolCall:
					callNames[part.ID] = part.Name
					id := geminiModelCallID(part.ID)
					if id != "" {
						modelIDs[part.ID] = id
					}
					c.Parts = append(c.Parts, geminiPart{FunctionCall: &geminiFunctionCall{
						ID:   id,
						Name: geminiToolName(part.Name),
						Args: encodeArgs(part.Args),
					}})
				}
			}
			if len(c.Parts) == 0 {
				continue
			}
			out = append(out, c)
		default:
			c := geminiContent{Role: "user"}
			for _, part := range m.Parts {
				switch part.Type {
				case PartText:
					if part.Text == "" {
						continue
					}
					c.Parts = append(c.Parts, geminiPart{Text: part.Text})
				case PartToolResult:
					name := callNames[part.CallID]
					if name == "" {
						name = part.CallID
					}
					c.Parts = append(c.Parts, geminiPart{FunctionResponse: &geminiFunctionResponse{
						ID:       modelIDs[part.CallID],
						Name:     geminiToolName(name),
						Response: geminiResponseEnvelope(part),
					}})
				}
			}
			if len(c.Parts) == 0 {
				continue
			}
			if n := len(out); n > 0 && out[n-1].Role == "user" {
				out[n-1].Parts = append(out[n-1].Parts, c.Parts...)
				continue
			}
			out = append(out, c)
		}
	}
	return out
}

// geminiResponseEnvelope wraps a canonical tool result in the object Gemini
// requires. The call id is preserved under "call_id" so the canonical form can
// be recovered exactly.
func geminiResponseEnvelope(part Part) map[string]any {
	env := map[string]any{"call_id": part.CallID}
	var parsed any
	if err := json.Unmarshal([]byte(part.Content), &parsed); err == nil {
		env["content"] = parsed
	} else {
		env["content"] = part.Content
	}
	if part.IsError {
		env["is_error"] = true
	}
	return env
}

// decodeGeminiContents converts generateContent wire contents back into the
// canonical transcript.
func decodeGeminiContents(contents []geminiContent) []Message {
	var out []Message
	for _, c := range contents {
		if c.Role == "model" {
			cm := Message{Role: RoleAssistant}
			for _, p := range c.Parts {
				switch {
				case p.FunctionCall != nil:
					cm.Parts = append(cm.Parts, ToolCallPart(p.FunctionCall.ID, p.FunctionCall.Name, decodeArgs(p.FunctionCall.Args)))
				case p.Text != "":
					cm.Parts = append(cm.Parts, TextPart(p.Text))
				}
			}
			out = append(out, cm)
			continue
		}
		var text Message
		var tools Message
		text.Role = RoleUser
		tools.Role = RoleTool
		for _, p := range c.Parts {
			switch {
			case p.FunctionResponse != nil:
				callID, content, isErr := geminiUnwrapResponse(p.FunctionResponse.Response)
				if callID == "" {
					callID = p.FunctionResponse.ID
				}
				tools.Parts = append(tools.Parts, ToolResultPart(callID, content, isErr))
			case p.Text != "":
				text.Parts = append(text.Parts, TextPart(p.Text))
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

func geminiUnwrapResponse(env map[string]any) (callID, content string, isError bool) {
	if s, ok := env["call_id"].(string); ok {
		callID = s
	}
	if b, ok := env["is_error"].(bool); ok {
		isError = b
	}
	switch v := env["content"].(type) {
	case nil:
	case string:
		content = v
	default:
		if raw, err := CanonicalJSON(v); err == nil {
			content = string(raw)
		}
	}
	return callID, content, isError
}

// decodeGeminiResponse converts a generateContent response into a canonical
// assistant message. Gemini does not mint tool-call ids, so the gateway assigns
// deterministic ones of the form "call_<index>_<name>".
func decodeGeminiResponse(name string, resp geminiResponse) GenerateResult {
	msg := Message{Role: RoleAssistant, Provider: name}
	stop := StopOther
	details := ""
	if len(resp.Candidates) > 0 {
		cand := resp.Candidates[0]
		idx := 0
		for _, p := range cand.Content.Parts {
			switch {
			case p.FunctionCall != nil:
				// Prefer the model's own id: with parallel calls to the same
				// function it is the only thing that keeps the results apart.
				id := p.FunctionCall.ID
				if id == "" {
					id = fmt.Sprintf("%s%d_%s", geminiCallIDPrefix, idx, p.FunctionCall.Name)
				}
				msg.Parts = append(msg.Parts, ToolCallPart(id, p.FunctionCall.Name, decodeArgs(p.FunctionCall.Args)))
				idx++
			case p.Text != "":
				msg.Parts = append(msg.Parts, TextPart(p.Text))
			}
		}
		switch cand.FinishReason {
		case "STOP":
			stop = StopEndTurn
		case "MAX_TOKENS":
			stop = StopMaxTokens
		case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY":
			stop = StopRefusal
		}
		if len(msg.ToolCalls()) > 0 && stop != StopMaxTokens && stop != StopRefusal {
			stop = StopToolUse
		}
		if cand.FinishReason != "" {
			details = "finishReason=" + cand.FinishReason
		}
	}
	return GenerateResult{
		Message:     msg,
		StopReason:  stop,
		StopDetails: details,
		Usage: Usage{
			InputTokens:  resp.UsageMetadata.PromptTokenCount,
			OutputTokens: resp.UsageMetadata.CandidatesTokenCount,
		},
		Provider: name,
	}
}

// --- Provider ---------------------------------------------------------------

// Generate runs one turn against the Gemini generateContent endpoint.
func (p *GeminiProvider) Generate(ctx context.Context, req GenerateRequest) (GenerateResult, error) {
	wire := p.encodeGeminiRequest(req)
	var resp geminiResponse
	endpoint := fmt.Sprintf("%s/models/%s:generateContent", p.baseURL, url.PathEscape(p.model))
	if err := postJSON(ctx, p.client, p.name, endpoint, p.headers(), wire, &resp); err != nil {
		return GenerateResult{}, err
	}
	if len(resp.Candidates) == 0 {
		return GenerateResult{}, &Error{Provider: p.name, Err: fmt.Errorf("response contained no candidates")}
	}
	return decodeGeminiResponse(p.name, resp), nil
}

// headers carries the API key in x-goog-api-key rather than the query string, so
// it never appears in a URL that might be logged.
func (p *GeminiProvider) headers() map[string]string {
	h := map[string]string{}
	if p.apiKey != "" {
		h["x-goog-api-key"] = p.apiKey
	}
	return h
}

// Health fetches the configured model's metadata.
func (p *GeminiProvider) Health(ctx context.Context) error {
	var out struct {
		Name string `json:"name"`
	}
	endpoint := fmt.Sprintf("%s/models/%s", p.baseURL, url.PathEscape(p.model))
	if err := getJSON(ctx, p.client, p.name, endpoint, p.headers(), &out); err != nil {
		return err
	}
	if out.Name == "" {
		return fmt.Errorf("provider %s: model %s not found", p.name, p.model)
	}
	return nil
}
