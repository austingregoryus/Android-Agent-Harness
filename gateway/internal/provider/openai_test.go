package provider

import (
	"context"
	"encoding/json"
	"net/http"
	"reflect"
	"testing"
)

// openAIRequestFixture is written by hand from the OpenAI chat-completions
// documentation (POST /v1/chat/completions with tools, tool_calls and
// role:"tool" messages).
const openAIRequestFixture = `{
  "model": "gpt-4o",
  "messages": [
    {"role": "system", "content": "You are the Android Agent Harness agent."},
    {"role": "user", "content": "What is the battery level?"},
    {
      "role": "assistant",
      "content": "Let me check the phone.",
      "tool_calls": [
        {
          "id": "toolu_01A",
          "type": "function",
          "function": {"name": "device.get_battery", "arguments": "{}"}
        }
      ]
    },
    {"role": "tool", "content": "{\"percent\":82,\"charging\":false}", "tool_call_id": "toolu_01A"}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "device.get_battery",
        "description": "Read the phone battery percentage and charging state.",
        "parameters": {"type": "object", "properties": {}}
      }
    }
  ]
}`

// openAIResponseFixture is a chat-completions response requesting a tool call.
const openAIResponseFixture = `{
  "id": "chatcmpl-123",
  "object": "chat.completion",
  "model": "gpt-4o",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": null,
        "tool_calls": [
          {
            "id": "call_abc123",
            "type": "function",
            "function": {"name": "messaging.send_sms", "arguments": "{\"text\":\"on my way\",\"to\":\"+15550001234\"}"}
          }
        ]
      },
      "finish_reason": "tool_calls"
    }
  ],
  "usage": {"prompt_tokens": 320, "completion_tokens": 41}
}`

func TestOpenAIEncodeMatchesFixture(t *testing.T) {
	p := NewOpenAI(OpenAIOptions{Name: "gpt", APIKey: "unused"})
	got := p.encodeOpenAIRequest(GenerateRequest{
		System:   "You are the Android Agent Harness agent.",
		Messages: canonicalFixture(),
		Tools:    toolDefFixture(),
	})
	assertJSONEqual(t, got, openAIRequestFixture)
}

func TestOpenAIRoundTripTable(t *testing.T) {
	p := NewOpenAI(OpenAIOptions{Name: "gpt", APIKey: "unused"})
	tests := []struct {
		name string
		msgs []Message
	}{
		{"fixture transcript", canonicalFixture()},
		{"text only", []Message{{Role: RoleUser, Parts: []Part{TextPart("hi")}}}},
		{"assistant text without tools", []Message{
			{Role: RoleUser, Parts: []Part{TextPart("hi")}},
			{Role: RoleAssistant, Parts: []Part{TextPart("hello")}},
		}},
		{"two tool calls and two results", []Message{
			{Role: RoleUser, Parts: []Part{TextPart("do both")}},
			{Role: RoleAssistant, Parts: []Part{
				ToolCallPart("a", "device.get_battery", map[string]any{}),
				ToolCallPart("b", "messaging.send_sms", map[string]any{"to": "+1555", "text": "hi"}),
			}},
			{Role: RoleTool, Parts: []Part{
				ToolResultPart("a", `{"percent":10}`, false),
				ToolResultPart("b", `{"sent":true}`, false),
			}},
		}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			wire := p.encodeOpenAIRequest(GenerateRequest{Messages: tc.msgs})
			back := decodeOpenAIMessages(wire.Messages)
			// OpenAI's wire format has no is_error flag on tool messages, so the
			// canonical IsError bit is expected to be lost; compare with it
			// cleared on both sides.
			if !reflect.DeepEqual(clearIsError(stripProvider(tc.msgs)), clearIsError(stripProvider(back))) {
				t.Fatalf("round trip mismatch:\nwant %s\ngot  %s", dump(tc.msgs), dump(back))
			}
		})
	}
}

func TestOpenAIDecodeResponse(t *testing.T) {
	var resp openAIResponse
	if err := json.Unmarshal([]byte(openAIResponseFixture), &resp); err != nil {
		t.Fatal(err)
	}
	got := decodeOpenAIResponse("gpt", resp)
	if got.StopReason != StopToolUse {
		t.Errorf("stop reason = %q", got.StopReason)
	}
	if got.Usage.InputTokens != 320 || got.Usage.OutputTokens != 41 {
		t.Errorf("usage = %+v", got.Usage)
	}
	want := []Part{ToolCallPart("call_abc123", "messaging.send_sms",
		map[string]any{"to": "+15550001234", "text": "on my way"})}
	if !reflect.DeepEqual(got.Message.Parts, want) {
		t.Fatalf("parts = %s, want %s", dumpParts(got.Message.Parts), dumpParts(want))
	}
}

func TestOpenAIToolCallArgumentsAreDeterministic(t *testing.T) {
	p := NewOpenAI(OpenAIOptions{Name: "gpt", APIKey: "unused"})
	msgs := []Message{{Role: RoleAssistant, Parts: []Part{
		ToolCallPart("x", "messaging.send_sms", map[string]any{"z": 1, "a": 2, "m": 3}),
	}}}
	first := p.encodeOpenAIRequest(GenerateRequest{Messages: msgs}).Messages[0].ToolCalls[0].Function.Arguments
	for i := 0; i < 20; i++ {
		again := p.encodeOpenAIRequest(GenerateRequest{Messages: msgs}).Messages[0].ToolCalls[0].Function.Arguments
		if again != first {
			t.Fatalf("arguments are not deterministic: %q vs %q", first, again)
		}
	}
	if first != `{"a":2,"m":3,"z":1}` {
		t.Errorf("arguments = %q, want canonical key order", first)
	}
}

func TestOpenAISchemaSanitizer(t *testing.T) {
	in := map[string]any{
		"$schema":     "https://json-schema.org/draft/2020-12/schema",
		"$id":         "urn:x",
		"type":        "object",
		"description": "keep me",
		"properties": map[string]any{
			"target": map[string]any{
				"type":       "string",
				"pattern":    "^\\+[0-9]+$",
				"$comment":   "drop me",
				"deprecated": true,
			},
			"opts": map[string]any{
				"type":                 "object",
				"patternProperties":    map[string]any{"^x": map[string]any{"type": "string"}},
				"additionalProperties": false,
				"properties":           map[string]any{"n": map[string]any{"type": "integer"}},
			},
			"tags": map[string]any{
				"type":  "array",
				"items": map[string]any{"type": "string", "examples": []any{"a"}},
			},
		},
		"required": []any{"target"},
	}
	got := SanitizeOpenAISchema(in)

	for _, k := range []string{"$schema", "$id"} {
		if _, ok := got[k]; ok {
			t.Errorf("%s survived sanitization", k)
		}
	}
	props := got["properties"].(map[string]any)
	target := props["target"].(map[string]any)
	if _, ok := target["$comment"]; ok {
		t.Error("$comment survived in a nested schema")
	}
	if _, ok := target["deprecated"]; ok {
		t.Error("deprecated survived in a nested schema")
	}
	if target["pattern"] != "^\\+[0-9]+$" {
		t.Error("pattern is supported and must be preserved")
	}
	opts := props["opts"].(map[string]any)
	if _, ok := opts["patternProperties"]; ok {
		t.Error("patternProperties survived")
	}
	if opts["additionalProperties"] != false {
		t.Error("additionalProperties must be preserved")
	}
	items := props["tags"].(map[string]any)["items"].(map[string]any)
	if _, ok := items["examples"]; ok {
		t.Error("examples survived inside items")
	}

	// The input must not be mutated.
	if _, ok := in["$schema"]; !ok {
		t.Error("sanitizer mutated its input")
	}
}

func TestOpenAIRetriesRetryableStatuses(t *testing.T) {
	tests := []struct {
		name        string
		status      int
		wantCalls   int
		wantSuccess bool
	}{
		{"429 is retried then succeeds", 429, 2, true},
		{"503 is retried then succeeds", 503, 2, true},
		{"400 is not retried", 400, 1, false},
		{"401 is not retried", 401, 1, false},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			stub := stubDoer(func(req *http.Request) (*http.Response, error) {
				calls++
				if calls == 1 {
					return jsonResponse(tc.status, `{"error":{"message":"nope"}}`), nil
				}
				return jsonResponse(200, openAIResponseFixture), nil
			})
			p := NewOpenAI(OpenAIOptions{Name: "gpt", APIKey: "k", HTTPClient: stub})
			_, err := p.Generate(context.Background(), GenerateRequest{Messages: canonicalFixture()})
			if calls != tc.wantCalls {
				t.Errorf("http calls = %d, want %d", calls, tc.wantCalls)
			}
			if (err == nil) != tc.wantSuccess {
				t.Errorf("err = %v, wantSuccess = %v", err, tc.wantSuccess)
			}
		})
	}
}

func TestOpenAIBaseURLOverrideForLocalServers(t *testing.T) {
	var gotURL, gotAuth string
	stub := stubDoer(func(req *http.Request) (*http.Response, error) {
		gotURL = req.URL.String()
		gotAuth = req.Header.Get("Authorization")
		return jsonResponse(200, openAIResponseFixture), nil
	})
	p := NewOpenAI(OpenAIOptions{Name: "ollama", BaseURL: "http://127.0.0.1:11434/v1", HTTPClient: stub})
	if _, err := p.Generate(context.Background(), GenerateRequest{Messages: canonicalFixture()}); err != nil {
		t.Fatalf("Generate: %v", err)
	}
	if gotURL != "http://127.0.0.1:11434/v1/chat/completions" {
		t.Errorf("url = %q", gotURL)
	}
	if gotAuth != "" {
		t.Errorf("a keyless local server must get no Authorization header, got %q", gotAuth)
	}
	if !p.Capabilities().LocalOffline {
		t.Error("a non-OpenAI base_url should report LocalOffline")
	}
}

func clearIsError(msgs []Message) []Message {
	out := make([]Message, len(msgs))
	for i, m := range msgs {
		c := m
		c.Parts = append([]Part(nil), m.Parts...)
		for j := range c.Parts {
			c.Parts[j].IsError = false
		}
		out[i] = c
	}
	return out
}
