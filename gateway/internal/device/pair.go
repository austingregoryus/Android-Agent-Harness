package device

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// PairRequest is the POST /pair body from protocol.md section 2.
type PairRequest struct {
	// Code is the one-time pairing code minted by the phone UI.
	Code string `json:"code"`
	// Label is a human label for the peer, e.g. "homelab-gateway".
	Label string `json:"label"`
	// Scopes are the requested capability globs. The runtime clamps them to
	// what the code was minted for.
	Scopes []string `json:"scopes,omitempty"`
	// MaxActionClass is the requested action-class ceiling.
	MaxActionClass string `json:"max_action_class,omitempty"`
}

// PairResult is the POST /pair response. The Token field is secret: callers
// must never log or print it.
type PairResult struct {
	// PeerID is the phone-side peer identifier. Safe to display.
	PeerID string `json:"peer_id"`
	// Token is the bearer token, shown by the runtime exactly once.
	Token string `json:"token"`
	// Scopes are the granted capability globs.
	Scopes []string `json:"scopes"`
	// MaxActionClass is the granted ceiling.
	MaxActionClass string `json:"max_action_class"`
	// ExpiresAt is the expiry in epoch milliseconds, or nil for no expiry.
	ExpiresAt *int64 `json:"expires_at"`
	// TLSFingerprintSHA256 is the SHA-256 fingerprint of the certificate
	// presented by the phone's TLS-only connector (colon-separated uppercase
	// hex), or "" when TLS is off. Not secret: safe to display and persist
	// unencrypted, unlike Token.
	TLSFingerprintSHA256 string `json:"tls_fingerprint_sha256"`
	// TLSMCPURL is the https MCP endpoint, set when TLS is on.
	TLSMCPURL string `json:"tls_mcp_url,omitempty"`
	// TLSEventsURL is the wss /ws/events endpoint, set when TLS is on.
	TLSEventsURL string `json:"tls_events_url,omitempty"`
}

// DefaultPairScopes are the capability globs the gateway asks for. The runtime
// clamps them to what the pairing code was minted for.
var DefaultPairScopes = []string{
	"messaging.*", "telephony.*", "device.*", "events.*", "contacts.*", "notifications.*",
}

// DefaultMaxActionClass is the ceiling the gateway requests.
const DefaultMaxActionClass = "COMMUNICATION"

// Pair exchanges a one-time code for a scoped bearer token. The endpoint is
// unauthenticated, so no Authorization header is sent.
func Pair(ctx context.Context, baseURL string, req PairRequest, client *http.Client) (PairResult, error) {
	if client == nil {
		client = &http.Client{Timeout: 30 * time.Second}
	}
	if len(req.Scopes) == 0 {
		req.Scopes = DefaultPairScopes
	}
	if req.MaxActionClass == "" {
		req.MaxActionClass = DefaultMaxActionClass
	}
	raw, err := json.Marshal(req)
	if err != nil {
		return PairResult{}, err
	}
	url := strings.TrimRight(baseURL, "/") + "/pair"
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(raw))
	if err != nil {
		return PairResult{}, err
	}
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("Accept", "application/json")

	resp, err := client.Do(httpReq)
	if err != nil {
		return PairResult{}, fmt.Errorf("pair: %w", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != http.StatusOK {
		return PairResult{}, fmt.Errorf("pair: phone returned HTTP %d: %s", resp.StatusCode, truncate(string(body), 300))
	}
	var out PairResult
	if err := json.Unmarshal(body, &out); err != nil {
		return PairResult{}, fmt.Errorf("pair: decode response: %w", err)
	}
	if out.Token == "" {
		return PairResult{}, fmt.Errorf("pair: phone returned no token")
	}
	return out, nil
}
