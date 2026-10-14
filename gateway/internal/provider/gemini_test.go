package provider

import (
	"context"
	"encoding/json"
	"net/http"
	"reflect"
	"testing"
)

// geminiRequestFixture is written by hand from the Gemini generateContent
// documentation (systemInstruction, contents with functionCall and
// functionResponse parts, tools.functionDeclarations).
const geminiRequestFixture = `{
  "systemInstruction": {"parts": [{"text": "You are the Android Agent Harness agent."}]},
  "contents": [
    {"role": "user", "parts": [{"text": "What is the battery level?"}]},
    {
      "role": "model",
      "parts": [
        {"text": "Let me check the phone."},
        {"functionCall": {"id": "toolu_01A", "name": "device.get_battery", "args": {}}}
      ]
    },
    {
      "role": "user",
      "parts": [
        {
          "functionResponse": {
            "id": "toolu_01A",
            "name": "device.get_battery",
            "response": {
              "call_id": "toolu_01A",
              "content": {"percent": 82, "charging": false}
            }
          }
        }
      ]
    }
  ],
  "tools": [
    {
      "functionDeclarations": [
        {
          "name": "device.get_battery",
          "description": "Read the phone battery percentage and charging state.",
          "parameters": {"type": "OBJECT", "properties": {}}
        }
      ]
    }
  ]
}`

// geminiResponseFixture is a generateContent response requesting a function call.
const geminiResponseFixture = `{
  "candidates": [
    {
      "content": {
        "role": "model",
        "parts": [
          {"text": "Reading the battery now."},
          {"functionCall": {"name": "device.get_battery", "args": {"verbose": true}}}
        ]
      },
      "finishReason": "STOP"
    }
  ],
  "usageMetadata": {"promptTokenCount": 210, "candidatesTokenCount": 33}
}`

func TestGeminiEncodeMatchesFixture(t *testing.T) {
	p := NewGemini(GeminiOptions{Name: "gemini", APIKey: "unused"})
	got := p.encodeGeminiRequest(GenerateRequest{
		System:   "You are the Android Agent Harness agent.",
		Messages: canonicalFixture(),
		Tools:    toolDefFixture(),
	})
	assertJSONEqual(t, got, geminiRequestFixture)
}

func TestGeminiRoundTripTable(t *testing.T) {
	p := NewGemini(GeminiOptions{Name: "gemini", APIKey: "unused"})
	tests := []struct {
		name string
		msgs []Message
	}{
		{"fixture transcript", canonicalFixture()},
		{"text only", []Message{{Role: RoleUser, Parts: []Part{TextPart("hi")}}}},
		{"assistant text", []Message{
			{Role: RoleUser, Parts: []Part{TextPart("hi")}},
			{Role: RoleAssistant, Parts: []Part{TextPart("hello")}},
		}},
		{"error tool result", []Message{
			{Role: RoleUser, Parts: []Part{TextPart("call")}},
			{Role: RoleAssistant, Parts: []Part{ToolCallPart("c1", "telephony.place_call", map[string]any{"target": "+1555"})}},
			{Role: RoleTool, Parts: []Part{ToolResultPart("c1", `{"error":"policy.approval_required"}`, true)}},
		}},
		{"plain text tool result", []Message{
			{Role: RoleUser, Parts: []Part{TextPart("x")}},
			{Role: RoleAssistant, Parts: []Part{ToolCallPart("c2", "device.get_info", map[string]any{})}},
			{Role: RoleTool, Parts: []Part{ToolResultPart("c2", "not json at all", false)}},
		}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			wire := p.encodeGeminiRequest(GenerateRequest{Messages: tc.msgs})
			back := decodeGeminiContents(wire.Contents)
			// Gemini identifies function calls by name rather than by id, so the
			// tool-call id is not recoverable from the wire form. Everything
			// else, including the tool-result call id (which the adapter smuggles
			// through the response envelope), must survive.
			want := normalizeContent(clearToolCallIDs(stripProvider(tc.msgs)))
			got := normalizeContent(clearToolCallIDs(stripProvider(back)))
			if !reflect.DeepEqual(want, got) {
				t.Fatalf("round trip mismatch:\nwant %s\ngot  %s", dump(tc.msgs), dump(back))
			}
		})
	}
}

func TestGeminiToolResultCallIDSurvives(t *testing.T) {
	p := NewGemini(GeminiOptions{Name: "gemini", APIKey: "unused"})
	wire := p.encodeGeminiRequest(GenerateRequest{Messages: canonicalFixture()})
	back := decodeGeminiContents(wire.Contents)
	for _, m := range back {
		if m.Role != RoleTool {
			continue
		}
		if m.Parts[0].CallID != "toolu_01A" {
			t.Fatalf("tool result call id = %q, want toolu_01A", m.Parts[0].CallID)
		}
		return
	}
	t.Fatal("no tool message survived the round trip")
}

func TestGeminiDecodeResponse(t *testing.T) {
	var resp geminiResponse
	if err := json.Unmarshal([]byte(geminiResponseFixture), &resp); err != nil {
		t.Fatal(err)
	}
	got := decodeGeminiResponse("gemini", resp)
	// A candidate carrying a functionCall is a tool-use turn even though Gemini
	// reports finishReason STOP.
	if got.StopReason != StopToolUse {
		t.Errorf("stop reason = %q, want tool_use", got.StopReason)
	}
	if got.Usage.InputTokens != 210 || got.Usage.OutputTokens != 33 {
		t.Errorf("usage = %+v", got.Usage)
	}
	calls := got.Message.ToolCalls()
	if len(calls) != 1 {
		t.Fatalf("got %d tool calls", len(calls))
	}
	if calls[0].Name != "device.get_battery" {
		t.Errorf("tool name = %q", calls[0].Name)
	}
	if calls[0].ID == "" {
		t.Error("the adapter must mint a tool-call id because Gemini does not supply one")
	}
	if !reflect.DeepEqual(calls[0].Args, map[string]any{"verbose": true}) {
		t.Errorf("args = %v", calls[0].Args)
	}
}

func TestGeminiSchemaSanitizer(t *testing.T) {
	in := map[string]any{
		"$schema":              "https://json-schema.org/draft/2020-12/schema",
		"type":                 "object",
		"additionalProperties": false,
		"description":          "send an sms",
		"properties": map[string]any{
			"to": map[string]any{
				"type":      "string",
				"pattern":   "^\\+[0-9]+$",
				"minLength": 4,
			},
			"count": map[string]any{"type": "integer", "minimum": 1, "maximum": 5},
			"tags": map[string]any{
				"type":     "array",
				"items":    map[string]any{"type": "string", "pattern": "x"},
				"minItems": 1,
			},
		},
		"required": []any{"to"},
	}
	got := SanitizeGeminiSchema(in)

	if got["type"] != "OBJECT" {
		t.Errorf("root type = %v, want the uppercase Gemini Type enum", got["type"])
	}
	for _, k := range []string{"$schema", "additionalProperties"} {
		if _, ok := got[k]; ok {
			t.Errorf("%s survived sanitization", k)
		}
	}
	props := got["properties"].(map[string]any)
	to := props["to"].(map[string]any)
	if to["type"] != "STRING" {
		t.Errorf("nested type = %v", to["type"])
	}
	for _, k := range []string{"pattern", "minLength"} {
		if _, ok := to[k]; ok {
			t.Errorf("%s is outside Gemini's subset and should be dropped", k)
		}
	}
	count := props["count"].(map[string]any)
	if count["type"] != "INTEGER" || count["minimum"] == nil || count["maximum"] == nil {
		t.Errorf("count = %v; minimum/maximum are in the subset and must survive", count)
	}
	tags := props["tags"].(map[string]any)
	if tags["type"] != "ARRAY" || tags["minItems"] == nil {
		t.Errorf("tags = %v", tags)
	}
	items := tags["items"].(map[string]any)
	if items["type"] != "STRING" {
		t.Errorf("items type = %v", items["type"])
	}
	if _, ok := items["pattern"]; ok {
		t.Error("pattern survived inside items")
	}
	if got["required"] == nil {
		t.Error("required must survive")
	}
	if _, ok := in["$schema"]; !ok {
		t.Error("sanitizer mutated its input")
	}
}

func TestGeminiSendsKeyInHeaderNotURL(t *testing.T) {
	var gotURL, gotKey string
	stub := stubDoer(func(req *http.Request) (*http.Response, error) {
		gotURL = req.URL.String()
		gotKey = req.Header.Get("x-goog-api-key")
		return jsonResponse(200, geminiResponseFixture), nil
	})
	p := NewGemini(GeminiOptions{Name: "gemini", Model: "gemini-2.5-flash", APIKey: "AIza-secret", HTTPClient: stub})
	if _, err := p.Generate(context.Background(), GenerateRequest{Messages: canonicalFixture()}); err != nil {
		t.Fatalf("Generate: %v", err)
	}
	if gotKey != "AIza-secret" {
		t.Errorf("x-goog-api-key = %q", gotKey)
	}
	if got := gotURL; got == "" || contains(got, "AIza-secret") {
		t.Errorf("the API key must not appear in the URL: %q", got)
	}
}

func contains(haystack, needle string) bool {
	return len(needle) > 0 && len(haystack) >= len(needle) && indexOf(haystack, needle) >= 0
}

func indexOf(h, n string) int {
	for i := 0; i+len(n) <= len(h); i++ {
		if h[i:i+len(n)] == n {
			return i
		}
	}
	return -1
}

// normalizeContent re-renders JSON tool-result content canonically. The Gemini
// envelope parses the content into an object and re-serialises it, so key order
// is normalised; that is a lossless transformation, not lost information.
func normalizeContent(msgs []Message) []Message {
	out := make([]Message, len(msgs))
	for i, m := range msgs {
		c := m
		c.Parts = append([]Part(nil), m.Parts...)
		for j := range c.Parts {
			if c.Parts[j].Type != PartToolResult {
				continue
			}
			var parsed any
			if err := json.Unmarshal([]byte(c.Parts[j].Content), &parsed); err != nil {
				continue
			}
			if raw, err := CanonicalJSON(parsed); err == nil {
				c.Parts[j].Content = string(raw)
			}
		}
		out[i] = c
	}
	return out
}

func clearToolCallIDs(msgs []Message) []Message {
	out := make([]Message, len(msgs))
	for i, m := range msgs {
		c := m
		c.Parts = append([]Part(nil), m.Parts...)
		for j := range c.Parts {
			if c.Parts[j].Type == PartToolCall {
				c.Parts[j].ID = ""
			}
		}
		out[i] = c
	}
	return out
}
