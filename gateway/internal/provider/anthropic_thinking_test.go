package provider

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
)

// anthropicThinkingTurn1 is a Messages API response from a model with extended
// thinking on: a signed thinking block, then a tool_use block. The Messages API
// requires this exact block list — signature included, thinking first — to come
// back on the next request, or it answers 400 "a final assistant message must
// start with a thinking block".
const anthropicThinkingTurn1 = `{
  "id": "msg_01T",
  "type": "message",
  "role": "assistant",
  "model": "claude-sonnet-5",
  "content": [
    {"type": "thinking",
     "thinking": "The user wants the battery level. I should call device.get_battery.",
     "signature": "ErUBCkYIBRgCIkDx0mQ9NsIgnATuRK0S2tW+SIG1"},
    {"type": "tool_use", "id": "toolu_99", "name": "device.get_battery", "input": {}}
  ],
  "stop_reason": "tool_use",
  "usage": {"input_tokens": 700, "output_tokens": 120}
}`

// anthropicThinkingTurn2 closes the task out.
const anthropicThinkingTurn2 = `{
  "id": "msg_02T",
  "type": "message",
  "role": "assistant",
  "model": "claude-sonnet-5",
  "content": [{"type": "text", "text": "The battery is at 82%."}],
  "stop_reason": "end_turn",
  "usage": {"input_tokens": 800, "output_tokens": 20}
}`

// twoTurnStub answers each POST from a script and records every request body.
func twoTurnStub(t *testing.T, bodies ...string) (httpDoer, *[]map[string]any) {
	t.Helper()
	var captured []map[string]any
	n := 0
	doer := stubDoer(func(req *http.Request) (*http.Response, error) {
		raw := make([]byte, 0, 4096)
		buf := make([]byte, 4096)
		for {
			k, err := req.Body.Read(buf)
			raw = append(raw, buf[:k]...)
			if err != nil {
				break
			}
		}
		var decoded map[string]any
		if err := json.Unmarshal(raw, &decoded); err != nil {
			t.Fatalf("request %d body is not JSON: %v", n+1, err)
		}
		captured = append(captured, decoded)
		body := bodies[len(bodies)-1]
		if n < len(bodies) {
			body = bodies[n]
		}
		n++
		return jsonResponse(200, body), nil
	})
	return doer, &captured
}

func blocks(t *testing.T, req map[string]any, msgIndex int) []any {
	t.Helper()
	msgs, ok := req["messages"].([]any)
	if !ok {
		t.Fatalf("request has no messages array: %v", req)
	}
	if msgIndex >= len(msgs) {
		t.Fatalf("request has %d messages, wanted index %d", len(msgs), msgIndex)
	}
	m := msgs[msgIndex].(map[string]any)
	content, ok := m["content"].([]any)
	if !ok {
		t.Fatalf("message %d has no content array: %v", msgIndex, m)
	}
	return content
}

func blockType(v any) string {
	m, ok := v.(map[string]any)
	if !ok {
		return ""
	}
	s, _ := m["type"].(string)
	return s
}

// TestAnthropicReplaysThinkingBlockOnTheNextTurn is the regression test for the
// bug that made every multi-turn Anthropic task fail on turn 2: thinking blocks
// were dropped on decode, so the replayed assistant turn opened with tool_use.
func TestAnthropicReplaysThinkingBlockOnTheNextTurn(t *testing.T) {
	doer, captured := twoTurnStub(t, anthropicThinkingTurn1, anthropicThinkingTurn2)
	p := NewAnthropic(AnthropicOptions{Name: "claude", APIKey: "sk-test", HTTPClient: doer})

	transcript := []Message{
		{Role: RoleUser, Parts: []Part{TextPart("What is the battery level?")}},
	}

	turn1, err := p.Generate(context.Background(), GenerateRequest{
		System: "You are the Android Agent Harness agent.", Messages: transcript, Tools: toolDefFixture(),
	})
	if err != nil {
		t.Fatalf("turn 1: %v", err)
	}
	if turn1.StopReason != StopToolUse {
		t.Fatalf("turn 1 stop reason = %q", turn1.StopReason)
	}

	// The thinking block must survive decode as a provider-bound opaque part,
	// ahead of the tool call.
	if len(turn1.Message.Parts) != 2 {
		t.Fatalf("turn 1 parts = %s", dumpParts(turn1.Message.Parts))
	}
	op := turn1.Message.Parts[0]
	if op.Type != PartOpaque || op.Provider != "claude" {
		t.Fatalf("first part is not a claude-bound opaque part: %s", dumpParts(turn1.Message.Parts))
	}
	if turn1.Message.Parts[1].Type != PartToolCall {
		t.Fatalf("second part = %v", turn1.Message.Parts[1].Type)
	}

	// Turn 2: replay the assistant turn plus the tool result.
	transcript = append(transcript, turn1.Message)
	transcript = append(transcript, Message{Role: RoleTool, Parts: []Part{
		ToolResultPart("toolu_99", `{"percent":82,"charging":false}`, false),
	}})
	if _, err := p.Generate(context.Background(), GenerateRequest{
		System: "You are the Android Agent Harness agent.", Messages: transcript, Tools: toolDefFixture(),
	}); err != nil {
		t.Fatalf("turn 2: %v", err)
	}

	if len(*captured) != 2 {
		t.Fatalf("expected 2 requests, got %d", len(*captured))
	}
	req2 := (*captured)[1]

	// messages[0] user, messages[1] assistant, messages[2] user (tool_result).
	msgs := req2["messages"].([]any)
	if len(msgs) != 3 {
		raw, _ := json.MarshalIndent(msgs, "", "  ")
		t.Fatalf("request 2 has %d messages, want 3:\n%s", len(msgs), raw)
	}

	asst := blocks(t, req2, 1)
	if len(asst) != 2 {
		raw, _ := json.MarshalIndent(asst, "", "  ")
		t.Fatalf("assistant turn has %d blocks, want [thinking, tool_use]:\n%s", len(asst), raw)
	}
	if got := blockType(asst[0]); got != "thinking" {
		t.Errorf("assistant block 0 type = %q, want thinking (the API rejects a turn that starts with tool_use)", got)
	}
	if got := blockType(asst[1]); got != "tool_use" {
		t.Errorf("assistant block 1 type = %q, want tool_use", got)
	}

	// The signature must be byte-identical: it is a server-side integrity check,
	// and any rewrite invalidates it.
	thinking := asst[0].(map[string]any)
	const wantSig = "ErUBCkYIBRgCIkDx0mQ9NsIgnATuRK0S2tW+SIG1"
	if thinking["signature"] != wantSig {
		t.Errorf("signature = %v, want %q", thinking["signature"], wantSig)
	}
	if !strings.Contains(thinking["thinking"].(string), "device.get_battery") {
		t.Errorf("thinking text was rewritten: %v", thinking["thinking"])
	}

	user := blocks(t, req2, 2)
	if len(user) != 1 || blockType(user[0]) != "tool_result" {
		raw, _ := json.MarshalIndent(user, "", "  ")
		t.Fatalf("message 2 is not a single tool_result:\n%s", raw)
	}
	if user[0].(map[string]any)["tool_use_id"] != "toolu_99" {
		t.Errorf("tool_result does not reference the call: %v", user[0])
	}

	// Both requests must carry an explicit thinking configuration and no
	// temperature (which the current Claude family rejects).
	for i, req := range *captured {
		th, ok := req["thinking"].(map[string]any)
		if !ok || th["type"] != ThinkingAdaptive {
			t.Errorf("request %d thinking = %v, want {type: adaptive}", i+1, req["thinking"])
		}
		if _, present := req["temperature"]; present {
			t.Errorf("request %d carries temperature, which must never be sent to Anthropic", i+1)
		}
		if req["max_tokens"] != float64(DefaultAnthropicMaxTokens) {
			t.Errorf("request %d max_tokens = %v, want %d", i+1, req["max_tokens"], DefaultAnthropicMaxTokens)
		}
	}
}

// TestAnthropicDropsAnotherProvidersOpaqueParts covers the failover case: a
// transcript that crossed over from another provider must not carry that
// provider's blocks into an Anthropic request, and an opaque part labelled with
// another provider's name must never be replayed.
func TestAnthropicDropsAnotherProvidersOpaqueParts(t *testing.T) {
	doer, captured := twoTurnStub(t, anthropicThinkingTurn2)
	p := NewAnthropic(AnthropicOptions{Name: "claude", APIKey: "sk-test", HTTPClient: doer})

	// An assistant turn produced by the "gpt" provider. The opaque part is
	// deliberately shaped like an Anthropic thinking block to prove the guard is
	// on the provider binding, not on the block's contents.
	foreign := json.RawMessage(`{"type":"thinking","thinking":"gpt reasoning","signature":"not-a-claude-signature"}`)
	transcript := []Message{
		{Role: RoleUser, Parts: []Part{TextPart("What is the battery level?")}},
		{Role: RoleAssistant, Provider: "gpt", Parts: []Part{
			OpaquePart("gpt", foreign),
			TextPart("Checking."),
			ToolCallPart("call_0", "device.get_battery", map[string]any{}),
		}},
		{Role: RoleTool, Parts: []Part{ToolResultPart("call_0", `{"percent":82}`, false)}},
	}

	if _, err := p.Generate(context.Background(), GenerateRequest{Messages: transcript}); err != nil {
		t.Fatalf("Generate: %v", err)
	}
	req := (*captured)[0]
	asst := blocks(t, req, 1)
	for _, b := range asst {
		if tp := blockType(b); tp == "thinking" || tp == "redacted_thinking" {
			raw, _ := json.MarshalIndent(asst, "", "  ")
			t.Fatalf("a transcript from provider %q leaked a %s block into an Anthropic request:\n%s", "gpt", tp, raw)
		}
	}
	if len(asst) != 2 || blockType(asst[0]) != "text" || blockType(asst[1]) != "tool_use" {
		raw, _ := json.MarshalIndent(asst, "", "  ")
		t.Fatalf("assistant blocks = \n%s", raw)
	}
	raw, _ := json.Marshal(req)
	if strings.Contains(string(raw), "not-a-claude-signature") {
		t.Error("the foreign provider's opaque payload reached the wire")
	}
}

// TestAnthropicRedactedThinkingRoundTrips checks the second provider-bound block
// type, which carries no readable text at all and must survive verbatim.
func TestAnthropicRedactedThinkingRoundTrips(t *testing.T) {
	const resp = `{
	  "id": "msg_03T", "type": "message", "role": "assistant", "model": "claude-sonnet-5",
	  "content": [
	    {"type": "redacted_thinking", "data": "EncryptedBlobAAA=="},
	    {"type": "text", "text": "Done."}
	  ],
	  "stop_reason": "end_turn",
	  "usage": {"input_tokens": 10, "output_tokens": 3}
	}`
	doer, captured := twoTurnStub(t, resp)
	p := NewAnthropic(AnthropicOptions{Name: "claude", APIKey: "sk-test", HTTPClient: doer})

	got, err := p.Generate(context.Background(), GenerateRequest{
		Messages: []Message{{Role: RoleUser, Parts: []Part{TextPart("hi")}}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if got.Message.Parts[0].Type != PartOpaque {
		t.Fatalf("parts = %s", dumpParts(got.Message.Parts))
	}

	transcript := []Message{
		{Role: RoleUser, Parts: []Part{TextPart("hi")}},
		got.Message,
		{Role: RoleUser, Parts: []Part{TextPart("and again")}},
	}
	if _, err := p.Generate(context.Background(), GenerateRequest{Messages: transcript}); err != nil {
		t.Fatal(err)
	}
	asst := blocks(t, (*captured)[1], 1)
	if blockType(asst[0]) != "redacted_thinking" {
		t.Fatalf("blocks = %v", asst)
	}
	if asst[0].(map[string]any)["data"] != "EncryptedBlobAAA==" {
		t.Errorf("redacted payload was rewritten: %v", asst[0])
	}
}

// TestAnthropicThinkingDisabled checks the config override reaches the wire.
func TestAnthropicThinkingDisabled(t *testing.T) {
	doer, captured := twoTurnStub(t, anthropicThinkingTurn2)
	p := NewAnthropic(AnthropicOptions{
		Name: "claude", APIKey: "sk-test", HTTPClient: doer,
		Thinking: ThinkingDisabled, MaxTokens: 2048,
	})
	if _, err := p.Generate(context.Background(), GenerateRequest{
		Messages: []Message{{Role: RoleUser, Parts: []Part{TextPart("hi")}}},
	}); err != nil {
		t.Fatal(err)
	}
	req := (*captured)[0]
	th := req["thinking"].(map[string]any)
	if th["type"] != ThinkingDisabled {
		t.Errorf("thinking = %v", th)
	}
	if req["max_tokens"] != float64(2048) {
		t.Errorf("max_tokens = %v, want the configured 2048", req["max_tokens"])
	}
}

// TestAnthropicStopReasons checks the stop reasons the agent loop keys off.
func TestAnthropicStopReasons(t *testing.T) {
	tests := []struct {
		wire string
		want StopReason
	}{
		{"end_turn", StopEndTurn},
		{"stop_sequence", StopEndTurn},
		{"tool_use", StopToolUse},
		{"max_tokens", StopMaxTokens},
		{"refusal", StopRefusal},
		{"model_context_window_exceeded", StopOther},
	}
	for _, tc := range tests {
		t.Run(tc.wire, func(t *testing.T) {
			var resp anthropicResponse
			body := `{"content":[],"stop_reason":"` + tc.wire + `"}`
			if err := json.Unmarshal([]byte(body), &resp); err != nil {
				t.Fatal(err)
			}
			got := decodeAnthropicResponse("claude", resp)
			if got.StopReason != tc.want {
				t.Errorf("stop reason = %q, want %q", got.StopReason, tc.want)
			}
			if !strings.Contains(got.StopDetails, tc.wire) {
				t.Errorf("stop details %q do not mention the provider's wording", got.StopDetails)
			}
		})
	}
}

// TestAnthropicHealthFailsOnBadRequest is the P1-4 regression: Health used to
// treat a 400 as proof of life, which made it blind to exactly the request-shape
// bugs it exists to catch.
func TestAnthropicHealthFailsOnBadRequest(t *testing.T) {
	tests := []struct {
		name       string
		status     int
		body       string
		wantSubstr string
	}{
		{"bad request", 400, `{"error":{"type":"invalid_request_error","message":"thinking.type: unexpected value"}}`,
			"thinking.type"},
		{"unauthorized", 401, `{"error":{"type":"authentication_error","message":"invalid x-api-key"}}`,
			"credentials rejected"},
		{"forbidden", 403, `{"error":{"type":"permission_error","message":"no access"}}`,
			"credentials rejected"},
		{"unknown model", 404, `{"error":{"type":"not_found_error","message":"model not found"}}`,
			"not available to this key"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			var method, path string
			stub := stubDoer(func(req *http.Request) (*http.Response, error) {
				method, path = req.Method, req.URL.Path
				return jsonResponse(tc.status, tc.body), nil
			})
			p := NewAnthropic(AnthropicOptions{Name: "claude", Model: "claude-sonnet-5",
				APIKey: "sk-test", HTTPClient: stub})
			err := p.Health(context.Background())
			if err == nil {
				t.Fatalf("Health returned nil on HTTP %d", tc.status)
			}
			if !strings.Contains(err.Error(), tc.wantSubstr) {
				t.Errorf("error %q does not carry the response detail %q", err, tc.wantSubstr)
			}
			if strings.Contains(err.Error(), "sk-test") {
				t.Error("the error leaked the API key")
			}
			if method != http.MethodGet || path != "/v1/models/claude-sonnet-5" {
				t.Errorf("probe was %s %s, want GET /v1/models/claude-sonnet-5", method, path)
			}
		})
	}
}

// TestAnthropicHealthSucceeds covers the happy path of the model probe.
func TestAnthropicHealthSucceeds(t *testing.T) {
	stub := stubDoer(func(req *http.Request) (*http.Response, error) {
		return jsonResponse(200, `{"id":"claude-sonnet-5","type":"model","display_name":"Claude Sonnet 5"}`), nil
	})
	p := NewAnthropic(AnthropicOptions{Name: "claude", APIKey: "sk-test", HTTPClient: stub})
	if err := p.Health(context.Background()); err != nil {
		t.Fatalf("Health: %v", err)
	}
}

// TestSanitizeAnthropicSchema covers P1-9: one bad tool schema used to 400 every
// turn of the task, and the sanitizer replaced an unchecked type assertion that
// would panic on a non-object schema.
func TestSanitizeAnthropicSchema(t *testing.T) {
	tests := []struct {
		name string
		in   map[string]any
		want map[string]any
	}{
		{"nil schema", nil, map[string]any{"type": "object", "properties": map[string]any{}}},
		{"missing type and properties", map[string]any{"description": "x"},
			map[string]any{"description": "x", "type": "object", "properties": map[string]any{}}},
		{"non-object root is forced to object",
			map[string]any{"type": "string"},
			map[string]any{"type": "object", "properties": map[string]any{}}},
		{"reference keywords are stripped recursively",
			map[string]any{
				"$schema": "https://json-schema.org/draft/2020-12/schema",
				"$id":     "urn:x",
				"$defs":   map[string]any{"Addr": map[string]any{"type": "string"}},
				"type":    "object",
				"properties": map[string]any{
					"to":   map[string]any{"$ref": "#/$defs/Addr", "type": "string"},
					"tags": map[string]any{"type": "array", "items": map[string]any{"$ref": "#/$defs/Addr"}},
				},
			},
			map[string]any{
				"type": "object",
				"properties": map[string]any{
					"to": map[string]any{"type": "string"},
					// The $ref is stripped and the sub-schema keeps its own
					// shape rather than being rewritten into an object.
					"tags": map[string]any{"type": "array", "items": map[string]any{}},
				},
			}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got := SanitizeAnthropicSchema(tc.in)
			gotRaw, _ := json.Marshal(got)
			wantRaw, _ := json.Marshal(tc.want)
			if string(gotRaw) != string(wantRaw) {
				t.Errorf("got  %s\nwant %s", gotRaw, wantRaw)
			}
		})
	}

	// The input must not be mutated.
	in := map[string]any{"$schema": "x", "type": "object", "properties": map[string]any{}}
	SanitizeAnthropicSchema(in)
	if _, ok := in["$schema"]; !ok {
		t.Error("SanitizeAnthropicSchema mutated its input")
	}
}
