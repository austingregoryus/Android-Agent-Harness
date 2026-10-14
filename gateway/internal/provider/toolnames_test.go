package provider

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"reflect"
	"regexp"
	"strings"
	"testing"
)

func TestProvidersUseLegalNamesAndRestoreCanonicalCalls(t *testing.T) {
	for _, kind := range []string{"openai", "anthropic"} {
		t.Run(kind, func(t *testing.T) {
			original := GenerateRequest{
				Tools:    []ToolDef{{Name: "device.get_battery"}, {Name: "device_get_battery"}, {Name: strings.Repeat("long.tool.", 20)}},
				Messages: []Message{{Role: RoleAssistant, Parts: []Part{ToolCallPart("old", "device.get_battery", nil)}}},
			}
			before, _ := json.Marshal(original)
			var firstAlias string
			doer := stubDoer(func(r *http.Request) (*http.Response, error) {
				var body map[string]any
				if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
					t.Fatal(err)
				}
				seen := map[string]bool{}
				legal := regexp.MustCompile(`^[a-zA-Z0-9_-]{1,64}$`)
				for i, tool := range body["tools"].([]any) {
					def := tool.(map[string]any)
					if kind == "openai" {
						def = def["function"].(map[string]any)
					}
					name := def["name"].(string)
					if !legal.MatchString(name) || seen[name] {
						t.Fatalf("invalid or colliding tool name %q", name)
					}
					seen[name] = true
					if i == 0 {
						firstAlias = name
					}
				}
				msg := body["messages"].([]any)[0].(map[string]any)
				var historyName string
				if kind == "openai" {
					historyName = msg["tool_calls"].([]any)[0].(map[string]any)["function"].(map[string]any)["name"].(string)
				} else {
					historyName = msg["content"].([]any)[0].(map[string]any)["name"].(string)
				}
				if historyName != firstAlias {
					t.Fatalf("history name %q differs from definition %q", historyName, firstAlias)
				}
				if kind == "openai" {
					return jsonResponse(200, fmt.Sprintf(`{"choices":[{"message":{"tool_calls":[{"id":"call","type":"function","function":{"name":%q,"arguments":"{}"}}]},"finish_reason":"tool_calls"}]}`, firstAlias)), nil
				}
				return jsonResponse(200, fmt.Sprintf(`{"content":[{"type":"tool_use","id":"call","name":%q,"input":{}}],"stop_reason":"tool_use"}`, firstAlias)), nil
			})
			var p Provider
			if kind == "openai" {
				p = NewOpenAI(OpenAIOptions{Name: kind, HTTPClient: doer})
			} else {
				p = NewAnthropic(AnthropicOptions{Name: kind, HTTPClient: doer})
			}
			got, err := p.Generate(context.Background(), original)
			if err != nil {
				t.Fatal(err)
			}
			if calls := got.Message.ToolCalls(); len(calls) != 1 || calls[0].Name != "device.get_battery" {
				t.Fatalf("canonical name not restored: %+v", calls)
			}
			after, _ := json.Marshal(original)
			if !reflect.DeepEqual(before, after) {
				t.Fatal("provider mutated canonical request")
			}
		})
	}
}
