package provider

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"testing"
)

// TestNormalizeOpenAIBaseURL covers the P2 base_url joining item. A user reads
// "http://127.0.0.1:11434" off an Ollama window; the chat-completions route
// lives under /v1, and joining without it 404s in a way that looks like the
// server is down.
func TestNormalizeOpenAIBaseURL(t *testing.T) {
	tests := []struct {
		in, want string
	}{
		{"http://127.0.0.1:11434", "http://127.0.0.1:11434/v1"},
		{"http://127.0.0.1:11434/", "http://127.0.0.1:11434/v1"},
		{"http://127.0.0.1:11434/v1", "http://127.0.0.1:11434/v1"},
		{"https://api.openai.com/v1", "https://api.openai.com/v1"},
		{"https://proxy.example/openai", "https://proxy.example/openai"},
		{"https://proxy.example/openai/v1", "https://proxy.example/openai/v1"},
		{"not a url", "not a url"},
	}
	for _, tc := range tests {
		t.Run(tc.in, func(t *testing.T) {
			p := NewOpenAI(OpenAIOptions{Name: "local", BaseURL: tc.in})
			if p.baseURL != tc.want {
				t.Errorf("baseURL = %q, want %q", p.baseURL, tc.want)
			}
		})
	}
}

// TestOpenAIMaxCompletionTokens covers the P2 field-rename item: the reasoning
// families reject "max_tokens" outright.
func TestOpenAIMaxCompletionTokens(t *testing.T) {
	tests := []struct {
		model   string
		newName bool
	}{
		{"gpt-4o", false},
		{"gpt-4.1", false},
		{"gpt-5", true},
		{"gpt-5-mini", true},
		{"openai/gpt-5.1", true},
		{"o1", true},
		{"o1-mini", true},
		{"o3-pro", true},
		{"o4-mini", true},
		{"qwen3:14b", false},
		{"o1lama", false},
	}
	for _, tc := range tests {
		t.Run(tc.model, func(t *testing.T) {
			if got := openAIUsesMaxCompletionTokens(tc.model); got != tc.newName {
				t.Fatalf("openAIUsesMaxCompletionTokens(%q) = %v", tc.model, got)
			}

			var body map[string]any
			stub := stubDoer(func(req *http.Request) (*http.Response, error) {
				raw, _ := io.ReadAll(req.Body)
				_ = json.Unmarshal(raw, &body)
				return jsonResponse(200, `{"choices":[{"message":{"role":"assistant","content":"hi"},`+
					`"finish_reason":"stop"}]}`), nil
			})
			p := NewOpenAI(OpenAIOptions{Name: "gpt", Model: tc.model, APIKey: "k",
				MaxTokens: 1234, HTTPClient: stub})
			if _, err := p.Generate(context.Background(), GenerateRequest{
				Messages: []Message{{Role: RoleUser, Parts: []Part{TextPart("hi")}}},
			}); err != nil {
				t.Fatal(err)
			}
			if tc.newName {
				if body["max_completion_tokens"] != float64(1234) {
					t.Errorf("max_completion_tokens = %v", body["max_completion_tokens"])
				}
				if _, present := body["max_tokens"]; present {
					t.Error("max_tokens was sent to a model that rejects it")
				}
			} else {
				if body["max_tokens"] != float64(1234) {
					t.Errorf("max_tokens = %v", body["max_tokens"])
				}
				if _, present := body["max_completion_tokens"]; present {
					t.Error("max_completion_tokens was sent to a model that does not know it")
				}
			}
		})
	}
}

// TestGeminiSanitizerLeavesNestedSubSchemasAlone covers the P2 Gemini sanitizer
// item: it used to stamp OBJECT plus an empty "properties" onto every typeless
// node, which rewrote the argument contract the phone published.
func TestGeminiSanitizerLeavesNestedSubSchemasAlone(t *testing.T) {
	in := map[string]any{
		"type": "object",
		"properties": map[string]any{
			// Typeless, enum-only: legal JSON Schema, and must stay as is.
			"mode": map[string]any{"enum": []any{"a", "b"}, "description": "which"},
			// A supported format on the type that defines it.
			"when": map[string]any{"type": "string", "format": "date-time"},
			// An unsupported format that Gemini rejects outright.
			"to": map[string]any{"type": "string", "format": "email"},
			// A format on the wrong type.
			"n": map[string]any{"type": "integer", "format": "double"},
		},
	}
	got := SanitizeGeminiSchema(in)
	props := got["properties"].(map[string]any)

	mode := props["mode"].(map[string]any)
	if _, hasType := mode["type"]; hasType {
		t.Errorf("a typeless sub-schema was given a type: %v", mode)
	}
	if _, hasProps := mode["properties"]; hasProps {
		t.Errorf("a typeless sub-schema was given properties: %v", mode)
	}

	if props["when"].(map[string]any)["format"] != "date-time" {
		t.Errorf("a supported format was dropped: %v", props["when"])
	}
	if _, present := props["to"].(map[string]any)["format"]; present {
		t.Errorf("the unsupported format \"email\" reached the wire: %v", props["to"])
	}
	if _, present := props["n"].(map[string]any)["format"]; present {
		t.Errorf("a format was kept on the wrong type: %v", props["n"])
	}

	// The root is still forced into shape.
	if got["type"] != "OBJECT" {
		t.Errorf("root type = %v", got["type"])
	}
	rootless := SanitizeGeminiSchema(map[string]any{"description": "no type"})
	if rootless["type"] != "OBJECT" {
		t.Errorf("typeless root was not forced to OBJECT: %v", rootless)
	}
	if _, ok := rootless["properties"]; !ok {
		t.Errorf("typeless root did not get properties: %v", rootless)
	}
}
