package provider

import (
	"context"
	"encoding/json"
	"net/http"
	"reflect"
	"testing"
)

// canonicalFixture is the transcript every adapter round-trip test uses: a user
// turn, an assistant turn with one tool call, and the tool result.
func canonicalFixture() []Message {
	return []Message{
		{Role: RoleUser, Parts: []Part{TextPart("What is the battery level?")}},
		{Role: RoleAssistant, Parts: []Part{
			TextPart("Let me check the phone."),
			ToolCallPart("toolu_01A", "device.get_battery", map[string]any{}),
		}},
		{Role: RoleTool, Parts: []Part{
			ToolResultPart("toolu_01A", `{"percent":82,"charging":false}`, false),
		}},
	}
}

func toolDefFixture() []ToolDef {
	return []ToolDef{{
		Name:        "device.get_battery",
		Description: "Read the phone battery percentage and charging state.",
		InputSchema: map[string]any{
			"type":       "object",
			"properties": map[string]any{},
		},
	}}
}

// anthropicRequestFixture is written by hand from the Anthropic Messages API
// documentation (POST /v1/messages, tool_use and tool_result content blocks).
const anthropicRequestFixture = `{
  "model": "claude-sonnet-5",
  "max_tokens": 8192,
  "thinking": {"type": "adaptive"},
  "system": "You are the Android Agent Harness agent.",
  "messages": [
    {
      "role": "user",
      "content": [{"type": "text", "text": "What is the battery level?"}]
    },
    {
      "role": "assistant",
      "content": [
        {"type": "text", "text": "Let me check the phone."},
        {"type": "tool_use", "id": "toolu_01A", "name": "device.get_battery", "input": {}}
      ]
    },
    {
      "role": "user",
      "content": [
        {"type": "tool_result", "tool_use_id": "toolu_01A", "content": "{\"percent\":82,\"charging\":false}"}
      ]
    }
  ],
  "tools": [
    {
      "name": "device.get_battery",
      "description": "Read the phone battery percentage and charging state.",
      "input_schema": {"type": "object", "properties": {}}
    }
  ]
}`

// anthropicResponseFixture is a Messages API response requesting a tool call.
const anthropicResponseFixture = `{
  "id": "msg_01XYZ",
  "type": "message",
  "role": "assistant",
  "model": "claude-sonnet-5",
  "content": [
    {"type": "text", "text": "I will read the battery."},
    {"type": "tool_use", "id": "toolu_02B", "name": "device.get_battery", "input": {"verbose": true}}
  ],
  "stop_reason": "tool_use",
  "usage": {"input_tokens": 512, "output_tokens": 64}
}`

func TestAnthropicEncodeMatchesFixture(t *testing.T) {
	p := NewAnthropic(AnthropicOptions{Name: "claude", APIKey: "unused"})
	got := p.encodeAnthropicRequest(GenerateRequest{
		System:   "You are the Android Agent Harness agent.",
		Messages: canonicalFixture(),
		Tools:    toolDefFixture(),
	})
	assertJSONEqual(t, got, anthropicRequestFixture)
}

func TestAnthropicRoundTrip(t *testing.T) {
	p := NewAnthropic(AnthropicOptions{Name: "claude", APIKey: "unused"})
	original := canonicalFixture()
	wire := p.encodeAnthropicRequest(GenerateRequest{Messages: original, Tools: toolDefFixture()})
	back := decodeAnthropicMessages(wire.Messages, "claude")

	// Anthropic batches tool results into a user-role message, so the decoded
	// transcript orders the tool message before any accompanying user text.
	// The fixture has no such text, so the round trip is exact.
	if !reflect.DeepEqual(stripProvider(original), stripProvider(back)) {
		t.Fatalf("round trip lost information:\nwant %s\ngot  %s", dump(original), dump(back))
	}
}

func TestAnthropicRoundTripTable(t *testing.T) {
	p := NewAnthropic(AnthropicOptions{Name: "claude", APIKey: "unused"})
	tests := []struct {
		name string
		msgs []Message
	}{
		{"text only", []Message{{Role: RoleUser, Parts: []Part{TextPart("hi")}}}},
		{"assistant text", []Message{
			{Role: RoleUser, Parts: []Part{TextPart("hi")}},
			{Role: RoleAssistant, Parts: []Part{TextPart("hello")}},
		}},
		{"two tool calls", []Message{
			{Role: RoleUser, Parts: []Part{TextPart("do both")}},
			{Role: RoleAssistant, Parts: []Part{
				ToolCallPart("a", "device.get_battery", map[string]any{}),
				ToolCallPart("b", "device.get_info", map[string]any{"deep": true}),
			}},
			{Role: RoleTool, Parts: []Part{
				ToolResultPart("a", `{"percent":10}`, false),
				ToolResultPart("b", `{"error":"policy.denied"}`, true),
			}},
		}},
		{"error tool result", []Message{
			{Role: RoleUser, Parts: []Part{TextPart("call")}},
			{Role: RoleAssistant, Parts: []Part{ToolCallPart("c", "telephony.place_call", map[string]any{"target": "+1555"})}},
			{Role: RoleTool, Parts: []Part{ToolResultPart("c", `{"error":"policy.approval_required"}`, true)}},
		}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			wire := p.encodeAnthropicRequest(GenerateRequest{Messages: tc.msgs})
			back := decodeAnthropicMessages(wire.Messages, "claude")
			if !reflect.DeepEqual(stripProvider(tc.msgs), stripProvider(back)) {
				t.Fatalf("round trip mismatch:\nwant %s\ngot  %s", dump(tc.msgs), dump(back))
			}
		})
	}
}

func TestAnthropicDecodeResponse(t *testing.T) {
	var resp anthropicResponse
	if err := json.Unmarshal([]byte(anthropicResponseFixture), &resp); err != nil {
		t.Fatal(err)
	}
	got := decodeAnthropicResponse("claude", resp)

	if got.StopReason != StopToolUse {
		t.Errorf("stop reason = %q", got.StopReason)
	}
	if got.Provider != "claude" || got.Message.Provider != "claude" {
		t.Errorf("provider attribution missing: %+v", got)
	}
	if got.Usage.InputTokens != 512 || got.Usage.OutputTokens != 64 {
		t.Errorf("usage = %+v", got.Usage)
	}
	want := []Part{
		TextPart("I will read the battery."),
		ToolCallPart("toolu_02B", "device.get_battery", map[string]any{"verbose": true}),
	}
	if !reflect.DeepEqual(got.Message.Parts, want) {
		t.Fatalf("parts = %s, want %s", dumpParts(got.Message.Parts), dumpParts(want))
	}
}

func TestAnthropicGenerateAgainstStub(t *testing.T) {
	var gotAuth, gotVersion string
	stub := stubDoer(func(req *http.Request) (*http.Response, error) {
		gotAuth = req.Header.Get("x-api-key")
		gotVersion = req.Header.Get("anthropic-version")
		return jsonResponse(200, anthropicResponseFixture), nil
	})
	p := NewAnthropic(AnthropicOptions{Name: "claude", APIKey: "sk-test", HTTPClient: stub})
	res, err := p.Generate(context.Background(), GenerateRequest{Messages: canonicalFixture()})
	if err != nil {
		t.Fatalf("Generate: %v", err)
	}
	if gotAuth != "sk-test" || gotVersion != anthropicVersion {
		t.Errorf("headers: x-api-key=%q anthropic-version=%q", gotAuth, gotVersion)
	}
	if len(res.Message.ToolCalls()) != 1 {
		t.Errorf("expected one tool call, got %d", len(res.Message.ToolCalls()))
	}
}
