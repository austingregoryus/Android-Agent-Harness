package device

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/fakephone"
)

func callErrorKind(t *testing.T, err error) *CallError {
	t.Helper()
	var ce *CallError
	if !errors.As(err, &ce) {
		t.Fatalf("error %v (%T) is not a *CallError", err, err)
	}
	return ce
}

// TestCallToolReportsWhetherThePhoneMayHaveActed is the device half of H4:
// CallTool must tell a call that never reached the phone from one whose
// response was lost after the phone may have executed it.
func TestCallToolReportsWhetherThePhoneMayHaveActed(t *testing.T) {
	ctx := context.Background()
	args := map[string]any{"to": "+15550001234", "text": "hi"}

	t.Run("response lost after execution", func(t *testing.T) {
		p := fakephone.New(fakephone.Options{Token: "tok"})
		defer p.Close()
		c := testClient(t, p)
		p.FailResponseOnce("messaging.send_sms")
		_, err := c.CallTool(ctx, "messaging.send_sms", args, CallToolOptions{CorrelationID: "corr_0000000000000001"})
		ce := callErrorKind(t, err)
		if ce.Kind != CallOutcomeUnknown {
			t.Fatalf("kind = %v, want CallOutcomeUnknown (%v)", ce.Kind, err)
		}
		want, _ := IdempotencyKey("corr_0000000000000001", "messaging.send_sms", args)
		if ce.IdempotencyKey != want || ce.CorrelationID != "corr_0000000000000001" {
			t.Errorf("call error = %+v, want the call's correlation id and idempotency key", ce)
		}
	})

	t.Run("dial refused", func(t *testing.T) {
		p := fakephone.New(fakephone.Options{Token: "tok"})
		c := NewMCPClient(MCPOptions{BaseURL: p.URL(), Token: "tok",
			HTTPClient: &http.Client{Transport: &http.Transport{DisableKeepAlives: true}}})
		if _, err := c.Initialize(ctx); err != nil {
			t.Fatal(err)
		}
		p.Close()
		_, err := c.CallTool(ctx, "messaging.send_sms", args, CallToolOptions{})
		if ce := callErrorKind(t, err); ce.Kind != CallNotSent {
			t.Fatalf("kind = %v, want CallNotSent (%v)", ce.Kind, err)
		}
	})

	t.Run("no session", func(t *testing.T) {
		c := NewMCPClient(MCPOptions{BaseURL: "http://127.0.0.1:1"})
		_, err := c.CallTool(ctx, "messaging.send_sms", args, CallToolOptions{})
		if ce := callErrorKind(t, err); ce.Kind != CallNotSent {
			t.Fatalf("kind = %v, want CallNotSent (%v)", ce.Kind, err)
		}
	})

	t.Run("json-rpc error", func(t *testing.T) {
		p := fakephone.New(fakephone.Options{Token: "tok"})
		defer p.Close()
		c := testClient(t, p)
		_, err := c.CallTool(ctx, "files.rm_rf", nil, CallToolOptions{})
		ce := callErrorKind(t, err)
		if ce.Kind != CallRejected || ce.RPCCode != -32601 {
			t.Fatalf("call error = %+v, want CallRejected -32601", ce)
		}
	})

	for _, tc := range []struct {
		status int
		want   CallErrorKind
	}{
		{http.StatusBadRequest, CallNotSent},
		{http.StatusUnauthorized, CallNotSent},
		{http.StatusInternalServerError, CallOutcomeUnknown},
		{http.StatusBadGateway, CallOutcomeUnknown},
	} {
		t.Run(fmt.Sprintf("http %d", tc.status), func(t *testing.T) {
			srv := scriptedMCPServer(t, func(w http.ResponseWriter, id int64) {
				w.WriteHeader(tc.status)
			})
			c := NewMCPClient(MCPOptions{BaseURL: srv.URL})
			_, err := c.CallTool(ctx, "messaging.send_sms", args, CallToolOptions{})
			if ce := callErrorKind(t, err); ce.Kind != tc.want {
				t.Fatalf("kind = %v, want %v (%v)", ce.Kind, tc.want, err)
			}
		})
	}

	t.Run("result is not an object", func(t *testing.T) {
		srv := scriptedMCPServer(t, func(w http.ResponseWriter, id int64) {
			fmt.Fprintf(w, `{"jsonrpc":"2.0","id":%d,"result":["sent"]}`, id)
		})
		c := NewMCPClient(MCPOptions{BaseURL: srv.URL})
		_, err := c.CallTool(ctx, "messaging.send_sms", args, CallToolOptions{})
		if ce := callErrorKind(t, err); ce.Kind != CallOutcomeUnknown {
			t.Fatalf("kind = %v, want CallOutcomeUnknown (%v)", ce.Kind, err)
		}
	})
}

// scriptedMCPServer answers initialize normally and every tools/call with
// answer.
func scriptedMCPServer(t *testing.T, answer func(w http.ResponseWriter, id int64)) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			ID     int64  `json:"id"`
			Method string `json:"method"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		switch req.Method {
		case "initialize":
			w.Header().Set("Mcp-Session-Id", "test-session")
			fmt.Fprintf(w, `{"jsonrpc":"2.0","id":%d,"result":{"protocolVersion":"2025-06-18"}}`, req.ID)
		case "notifications/initialized":
			w.WriteHeader(http.StatusAccepted)
		default:
			answer(w, req.ID)
		}
	}))
	t.Cleanup(srv.Close)
	return srv
}
