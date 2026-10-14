package device

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestHealthAndCloseKeepConfiguredTimeout(t *testing.T) {
	for _, operation := range []string{"health", "close"} {
		t.Run(operation, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				select {
				case <-r.Context().Done():
				case <-time.After(500 * time.Millisecond):
					w.WriteHeader(http.StatusOK)
				}
			}))
			defer srv.Close()
			c := NewMCPClient(MCPOptions{BaseURL: srv.URL, HTTPClient: &http.Client{Timeout: 30 * time.Millisecond}})
			c.sessionID = "test-session"
			started := time.Now()
			var err error
			if operation == "health" {
				_, err = c.Health(context.Background())
			} else {
				err = c.Close(context.Background())
			}
			if err == nil || time.Since(started) > 300*time.Millisecond {
				t.Fatalf("%s ignored configured timeout: elapsed=%s err=%v", operation, time.Since(started), err)
			}
		})
	}
}

// slowPhone answers initialize at once and every tools/call after delay.
func slowPhone(t *testing.T, delay time.Duration) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			ID     any    `json:"id"`
			Method string `json:"method"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if req.ID == nil {
			w.WriteHeader(http.StatusAccepted)
			return
		}
		var result any = map[string]any{}
		switch req.Method {
		case "initialize":
			w.Header().Set("Mcp-Session-Id", "mcp_test")
			result = map[string]any{"protocolVersion": ProtocolVersion, "serverInfo": map[string]any{"name": "android-agent-harness-runtime"}}
		case "tools/call":
			time.Sleep(delay)
			result = map[string]any{"isError": false, "structuredContent": map[string]any{"completed": true}}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"jsonrpc": "2.0", "id": req.ID, "result": result})
	}))
}

// A caller-supplied client's Timeout becomes the ordinary per-request bound,
// but automation.run is bounded by its own timeout_ms plus a margin, so a
// batch the phone is legitimately still running is not cut off at 60 s.
func TestAutomationRunOutlivesTheOrdinaryRequestTimeout(t *testing.T) {
	srv := slowPhone(t, 300*time.Millisecond)
	defer srv.Close()
	c := NewMCPClient(MCPOptions{BaseURL: srv.URL, Token: "tok", HTTPClient: &http.Client{Timeout: 100 * time.Millisecond}})
	ctx := context.Background()
	if _, err := c.Initialize(ctx); err != nil {
		t.Fatalf("Initialize: %v", err)
	}
	if _, err := c.CallTool(ctx, "device.get_battery", nil, CallToolOptions{ReadOnly: true}); err == nil {
		t.Fatal("an ordinary call slower than the request timeout should fail")
	}
	out, err := c.CallTool(ctx, "automation.run", map[string]any{"steps": []any{}, "timeout_ms": float64(1000)}, CallToolOptions{})
	if err != nil {
		t.Fatalf("automation.run: %v", err)
	}
	if !out.OK {
		t.Fatalf("automation.run outcome not ok: %+v", out)
	}
}

func TestCallTimeoutClampsLikeThePhone(t *testing.T) {
	c := NewMCPClient(MCPOptions{BaseURL: "http://127.0.0.1:1"})
	cases := []struct {
		name string
		args map[string]any
		want time.Duration
	}{
		{"device.get_battery", nil, DefaultRequestTimeout},
		{"automation.run", map[string]any{}, 60*time.Second + automationMargin},
		{"automation.run", map[string]any{"timeout_ms": float64(3_600_000)}, 300*time.Second + automationMargin},
		{"automation.run", map[string]any{"timeout_ms": float64(10)}, DefaultRequestTimeout}, // 1 s floor + margin is under the default: the default wins
	}
	for _, tc := range cases {
		if got := c.callTimeout(tc.name, tc.args); got != tc.want {
			t.Errorf("callTimeout(%s, %v) = %v, want %v", tc.name, tc.args, got, tc.want)
		}
	}
}

// After a 404 (session expired) the re-initialize handshake gets the ordinary
// request timeout, not automation.run's minutes-long budget.
func TestReinitializeUsesTheOrdinaryTimeout(t *testing.T) {
	var inits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			ID     any    `json:"id"`
			Method string `json:"method"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if req.ID == nil {
			w.WriteHeader(http.StatusAccepted)
			return
		}
		switch req.Method {
		case "initialize":
			if atomic.AddInt32(&inits, 1) > 1 {
				time.Sleep(400 * time.Millisecond) // the re-initialize is slow
			}
			w.Header().Set("Mcp-Session-Id", "mcp_test")
			_ = json.NewEncoder(w).Encode(map[string]any{"jsonrpc": "2.0", "id": req.ID, "result": map[string]any{"protocolVersion": ProtocolVersion}})
		default:
			w.WriteHeader(http.StatusNotFound) // session expired
		}
	}))
	defer srv.Close()
	c := NewMCPClient(MCPOptions{BaseURL: srv.URL, Token: "tok", HTTPClient: &http.Client{Timeout: 100 * time.Millisecond}})
	ctx := context.Background()
	if _, err := c.Initialize(ctx); err != nil {
		t.Fatalf("Initialize: %v", err)
	}
	start := time.Now()
	_, err := c.CallTool(ctx, "automation.run", map[string]any{"timeout_ms": float64(1000)}, CallToolOptions{})
	if err == nil {
		t.Fatal("expected the slow re-initialize to time out")
	}
	if el := time.Since(start); el > 350*time.Millisecond {
		t.Fatalf("re-initialize ran on the batch budget: took %v", el)
	}
}
