package device

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestCallToolRequiresConfirmedRPCResult(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		body   string
	}{
		{"accepted", 202, ""},
		{"no_content", 204, ""},
		{"empty", 200, ""},
		{"missing_result", 200, `{"jsonrpc":"2.0","id":%d}`},
		{"null_result", 200, `{"jsonrpc":"2.0","id":%d,"result":null}`},
		{"wrong_id", 200, `{"jsonrpc":"2.0","id":999,"result":{}}`},
		{"wrong_version", 200, `{"jsonrpc":"1.0","id":%d,"result":{}}`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				var req struct {
					ID     int64  `json:"id"`
					Method string `json:"method"`
				}
				_ = json.NewDecoder(r.Body).Decode(&req)
				if req.Method == "initialize" {
					w.Header().Set("Mcp-Session-Id", "test-session")
					fmt.Fprintf(w, `{"jsonrpc":"2.0","id":%d,"result":{"protocolVersion":"2025-06-18"}}`, req.ID)
					return
				}
				if req.Method == "notifications/initialized" {
					w.WriteHeader(202)
					return
				}
				w.WriteHeader(tc.status)
				if tc.body != "" {
					fmt.Fprint(w, replaceResponseID(tc.body, req.ID))
				}
			}))
			defer srv.Close()
			c := NewMCPClient(MCPOptions{BaseURL: srv.URL})
			out, err := c.CallTool(context.Background(), "messaging.send_sms", nil, CallToolOptions{})
			if err == nil || out.OK {
				t.Fatalf("unconfirmed result accepted: outcome=%+v err=%v", out, err)
			}
			// The phone answered, so it may have acted: the outcome is unknown,
			// not a call that never left the gateway.
			var ce *CallError
			if !errors.As(err, &ce) || ce.Kind != CallOutcomeUnknown {
				t.Errorf("error = %v, want a CallOutcomeUnknown *CallError", err)
			}
		})
	}
}

func replaceResponseID(body string, id int64) string {
	return strings.ReplaceAll(body, "%d", fmt.Sprint(id))
}
