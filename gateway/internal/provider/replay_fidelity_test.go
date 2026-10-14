package provider

import (
	"context"
	"encoding/json"
	"regexp"
	"strings"
	"testing"
)

// TestAnthropicReplaysInterleavedBlocksInOrder is the M2 regression. With
// interleaved thinking a turn can be [thinking, text, thinking, tool_use]; the
// encoder used to emit every opaque block first and then the rest, replaying
// [thinking, thinking, text, tool_use], which the Messages API rejects.
func TestAnthropicReplaysInterleavedBlocksInOrder(t *testing.T) {
	const turn1 = `{
	  "id": "msg_i1", "type": "message", "role": "assistant", "model": "claude-sonnet-5",
	  "content": [
	    {"type": "thinking", "thinking": "first, look at the battery", "signature": "sig-one"},
	    {"type": "text", "text": "Let me check."},
	    {"type": "thinking", "thinking": "now call the tool", "signature": "sig-two"},
	    {"type": "tool_use", "id": "toolu_i1", "name": "device.get_battery", "input": {}}
	  ],
	  "stop_reason": "tool_use",
	  "usage": {"input_tokens": 10, "output_tokens": 10}
	}`
	doer, captured := twoTurnStub(t, turn1, anthropicThinkingTurn2)
	p := NewAnthropic(AnthropicOptions{Name: "claude", APIKey: "sk-test", HTTPClient: doer})

	transcript := []Message{{Role: RoleUser, Parts: []Part{TextPart("battery?")}}}
	got, err := p.Generate(context.Background(), GenerateRequest{Messages: transcript, Tools: toolDefFixture()})
	if err != nil {
		t.Fatal(err)
	}
	transcript = append(transcript, got.Message,
		Message{Role: RoleTool, Parts: []Part{ToolResultPart("toolu_i1", `{"percent":82}`, false)}})
	if _, err := p.Generate(context.Background(), GenerateRequest{Messages: transcript, Tools: toolDefFixture()}); err != nil {
		t.Fatal(err)
	}

	asst := blocks(t, (*captured)[1], 1)
	var order []string
	for _, b := range asst {
		order = append(order, blockType(b))
	}
	if strings.Join(order, ",") != "thinking,text,thinking,tool_use" {
		t.Fatalf("replayed block order = %v, want the original [thinking text thinking tool_use]", order)
	}
	if asst[0].(map[string]any)["signature"] != "sig-one" || asst[2].(map[string]any)["signature"] != "sig-two" {
		t.Errorf("thinking blocks were reordered or rewritten: %v", asst)
	}
}

// anthropicIDPattern is the Messages API's constraint on tool_use ids.
var anthropicIDPattern = regexp.MustCompile(`^[a-zA-Z0-9_-]+$`)

// TestGeminiMintedCallIDsAreUniqueAndAPISafe is the first half of M3. Ids the
// adapter minted for calls without one used to be "call_<index>_<name>": they
// restarted at 0 each turn (colliding across turns) and carried the dotted tool
// name, which Anthropic rejects as a tool_use id after failover.
func TestGeminiMintedCallIDsAreUniqueAndAPISafe(t *testing.T) {
	var resp geminiResponse
	if err := json.Unmarshal([]byte(geminiResponseFixture), &resp); err != nil {
		t.Fatal(err)
	}
	seen := map[string]bool{}
	for turn := 0; turn < 3; turn++ {
		got := decodeGeminiResponse("gemini", resp)
		for _, call := range got.Message.ToolCalls() {
			if !anthropicIDPattern.MatchString(call.ID) {
				t.Errorf("minted id %q is not API-safe", call.ID)
			}
			if seen[call.ID] {
				t.Errorf("minted id %q repeats across turns", call.ID)
			}
			seen[call.ID] = true
			if geminiModelCallID(call.ID) != "" {
				t.Errorf("minted id %q would be sent back to Gemini as the model's own id", call.ID)
			}
		}
	}
}

// TestAnthropicSanitizesForeignToolIDs covers transcripts that already hold an
// unsafe id (from an older Gemini run): the tool_use and its tool_result must
// both get the same legal id.
func TestAnthropicSanitizesForeignToolIDs(t *testing.T) {
	msgs := encodeAnthropicMessages([]Message{
		{Role: RoleUser, Parts: []Part{TextPart("battery?")}},
		{Role: RoleAssistant, Provider: "gemini", Parts: []Part{ToolCallPart("call_0_device.get_battery", "sym_x", nil)}},
		{Role: RoleTool, Parts: []Part{ToolResultPart("call_0_device.get_battery", `{"percent":82}`, false)}},
		{Role: RoleAssistant, Provider: "gemini", Parts: []Part{ToolCallPart("call_0_device.get_info", "sym_y", nil)}},
		{Role: RoleTool, Parts: []Part{ToolResultPart("call_0_device.get_info", `{}`, false)}},
	}, "claude")
	use1, res1 := msgs[1].Content[0].ID, msgs[2].Content[0].ToolUseID
	use2 := msgs[3].Content[0].ID
	if !anthropicIDPattern.MatchString(use1) || use1 != res1 {
		t.Errorf("tool_use id %q / tool_result id %q, want one legal id", use1, res1)
	}
	if use1 == use2 {
		t.Errorf("distinct ids collapsed to %q", use1)
	}
	if got := anthropicToolID("toolu_01A"); got != "toolu_01A" {
		t.Errorf("a legal id was rewritten to %q", got)
	}
}

// geminiSignedResponse is a thinking-model response: the function call carries
// a thoughtSignature, and a trailing empty part carries another.
const geminiSignedResponse = `{
  "candidates": [{
    "content": {"role": "model", "parts": [
      {"text": "Checking.", "thoughtSignature": "c2lnLXRleHQ="},
      {"functionCall": {"name": "device.get_battery", "args": {}}, "thoughtSignature": "c2lnLWNhbGw="},
      {"text": "", "thoughtSignature": "c2lnLXRhaWw="}
    ]},
    "finishReason": "STOP"
  }],
  "usageMetadata": {"promptTokenCount": 1, "candidatesTokenCount": 1}
}`

// TestGeminiThoughtSignaturesReplayToGeminiOnly is the second half of M3:
// geminiPart used to drop thoughtSignature, and Gemini expects it echoed back
// on the function-call history. It must survive to the next Gemini request on
// the same parts, in order, and never reach another provider.
func TestGeminiThoughtSignaturesReplayToGeminiOnly(t *testing.T) {
	var resp geminiResponse
	if err := json.Unmarshal([]byte(geminiSignedResponse), &resp); err != nil {
		t.Fatal(err)
	}
	turn := decodeGeminiResponse("gemini", resp).Message
	if len(turn.Parts) != 3 {
		t.Fatalf("parts = %s", dumpParts(turn.Parts))
	}
	if turn.Parts[1].Type != PartToolCall || turn.Parts[1].Signature != "c2lnLWNhbGw=" || turn.Parts[1].Provider != "gemini" {
		t.Fatalf("function call lost its signature: %s", dumpParts(turn.Parts))
	}
	if turn.Parts[2].Type != PartOpaque {
		t.Fatalf("signature-only part was not kept as an opaque part: %s", dumpParts(turn.Parts))
	}

	transcript := []Message{
		{Role: RoleUser, Parts: []Part{TextPart("battery?")}},
		turn,
		{Role: RoleTool, Parts: []Part{ToolResultPart(turn.Parts[1].ID, `{"percent":82}`, false)}},
	}
	g := NewGemini(GeminiOptions{Name: "gemini", APIKey: "unused"})
	wire := g.encodeGeminiRequest(GenerateRequest{Messages: transcript})
	raw, err := json.Marshal(wire.Contents[1])
	if err != nil {
		t.Fatal(err)
	}
	var model struct {
		Parts []map[string]any `json:"parts"`
	}
	if err := json.Unmarshal(raw, &model); err != nil {
		t.Fatal(err)
	}
	if len(model.Parts) != 3 {
		t.Fatalf("model turn parts = %s", raw)
	}
	for i, want := range []string{"c2lnLXRleHQ=", "c2lnLWNhbGw=", "c2lnLXRhaWw="} {
		if model.Parts[i]["thoughtSignature"] != want {
			t.Errorf("part %d thoughtSignature = %v, want %s (%s)", i, model.Parts[i]["thoughtSignature"], want, raw)
		}
	}
	if model.Parts[1]["functionCall"] == nil {
		t.Errorf("signature is not on the function-call part: %s", raw)
	}

	// Another Gemini provider (a different configured name) must not get them.
	other := NewGemini(GeminiOptions{Name: "gemini-backup", APIKey: "unused"})
	otherRaw, _ := json.Marshal(other.encodeGeminiRequest(GenerateRequest{Messages: transcript}))
	if strings.Contains(string(otherRaw), "thoughtSignature") {
		t.Errorf("signatures leaked to a provider that did not produce them: %s", otherRaw)
	}
	// Nor must Anthropic or OpenAI.
	claudeRaw, _ := json.Marshal(NewAnthropic(AnthropicOptions{Name: "claude"}).encodeAnthropicRequest(GenerateRequest{Messages: transcript}))
	gptRaw, _ := json.Marshal(NewOpenAI(OpenAIOptions{Name: "gpt"}).encodeOpenAIRequest(GenerateRequest{Messages: transcript}))
	for name, raw := range map[string][]byte{"anthropic": claudeRaw, "openai": gptRaw} {
		if strings.Contains(string(raw), "c2lnL") {
			t.Errorf("Gemini signatures leaked into the %s request: %s", name, raw)
		}
	}
}

// TestMalformedToolArgumentsAreMarked is the provider half of M6: arguments
// that are not a JSON object must mark the call invalid instead of becoming
// {"_raw": ...} (OpenAI) or a silently empty {} (Anthropic, Gemini).
func TestMalformedToolArgumentsAreMarked(t *testing.T) {
	t.Run("openai", func(t *testing.T) {
		var resp openAIResponse
		if err := json.Unmarshal([]byte(`{"choices":[{"message":{"role":"assistant","tool_calls":[
			{"id":"call_a","type":"function","function":{"name":"sym_x","arguments":"{\"to\": \"+1555"}},
			{"id":"call_b","type":"function","function":{"name":"sym_y","arguments":"{\"ok\":true}"}},
			{"id":"call_c","type":"function","function":{"name":"sym_z","arguments":""}}
		]},"finish_reason":"tool_calls"}]}`), &resp); err != nil {
			t.Fatal(err)
		}
		calls := decodeOpenAIResponse("gpt", resp).Message.ToolCalls()
		if calls[0].ArgsError == "" || len(calls[0].Args) != 0 {
			t.Errorf("truncated arguments not marked: %+v", calls[0])
		}
		if calls[1].ArgsError != "" || calls[1].Args["ok"] != true {
			t.Errorf("valid arguments rejected: %+v", calls[1])
		}
		if calls[2].ArgsError != "" {
			t.Errorf("empty arguments are a no-argument call, not an error: %+v", calls[2])
		}
	})
	t.Run("anthropic", func(t *testing.T) {
		var resp anthropicResponse
		if err := json.Unmarshal([]byte(`{"content":[{"type":"tool_use","id":"toolu_1","name":"x","input":"not an object"}],
			"stop_reason":"tool_use"}`), &resp); err != nil {
			t.Fatal(err)
		}
		if call := decodeAnthropicResponse("claude", resp).Message.ToolCalls()[0]; call.ArgsError == "" {
			t.Errorf("non-object input not marked: %+v", call)
		}
	})
	t.Run("gemini", func(t *testing.T) {
		var resp geminiResponse
		if err := json.Unmarshal([]byte(`{"candidates":[{"content":{"role":"model","parts":[
			{"functionCall":{"name":"x","args":[1,2]}},
			{"functionCall":{"name":"y"}}
		]},"finishReason":"STOP"}]}`), &resp); err != nil {
			t.Fatal(err)
		}
		calls := decodeGeminiResponse("gemini", resp).Message.ToolCalls()
		if calls[0].ArgsError == "" {
			t.Errorf("array args not marked: %+v", calls[0])
		}
		if calls[1].ArgsError != "" {
			t.Errorf("absent args are a no-argument call, not an error: %+v", calls[1])
		}
	})
}
