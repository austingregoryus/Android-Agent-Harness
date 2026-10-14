package device

import "testing"

func TestToolOutcomeNeverConfirmsMalformedOrErrorResult(t *testing.T) {
	for _, raw := range []map[string]any{
		nil, {}, {"isError": "true"},
		{"structuredContent": map[string]any{}, "isError": "false"},
		{"structuredContent": map[string]any{"error": "policy.denied"}},
		{"structuredContent": map[string]any{"error": true}},
		{"structuredContent": map[string]any{"error": "policy.denied"}, "isError": false},
	} {
		if out := decodeToolOutcome(raw); out.OK || out.ErrorCode == "" {
			t.Fatalf("unconfirmed result reported as success: %+v", out)
		}
	}
	for _, raw := range []map[string]any{
		{"structuredContent": map[string]any{}},
		{"structuredContent": map[string]any{"ended": true}, "isError": false},
	} {
		if out := decodeToolOutcome(raw); !out.OK {
			t.Fatalf("valid result rejected: %+v", out)
		}
	}
}
