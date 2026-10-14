package device

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/config"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/fakephone"
)

// tlsTimeout bounds the TLS connection tests; generous for a loaded CI box.
const tlsTimeout = 10 * time.Second

// realFingerprint computes the fake phone's own certificate fingerprint, the
// way a caller would after reading it from GET /health or POST /pair.
func realFingerprint(t *testing.T, p *fakephone.Phone) string {
	t.Helper()
	cert := p.Server.Certificate()
	if cert == nil {
		t.Fatal("fake phone has no TLS certificate; construct it with fakephone.Options{TLS: true}")
	}
	sum := sha256.Sum256(cert.Raw)
	return FormatFingerprintSHA256(sum[:])
}

// (a) connecting with the correct fingerprint succeeds for both /mcp and
// /ws/events.
func TestTLSPinningAcceptsCorrectFingerprint(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok", TLS: true})
	defer p.Close()

	fp := realFingerprint(t, p)
	client, err := NewPinnedHTTPClient(fp, tlsTimeout)
	if err != nil {
		t.Fatalf("NewPinnedHTTPClient: %v", err)
	}

	mc := NewMCPClient(MCPOptions{BaseURL: p.URL(), Token: "tok", HTTPClient: client})
	if _, err := mc.Initialize(context.Background()); err != nil {
		t.Fatalf("Initialize over pinned TLS: %v", err)
	}
	if _, err := mc.ListTools(context.Background()); err != nil {
		t.Fatalf("ListTools over pinned TLS: %v", err)
	}

	ec := NewEventClient(EventOptions{BaseURL: p.URL(), Token: "tok", HTTPClient: client})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = ec.Run(ctx) }()
	defer ec.Close()

	wctx, wcancel := context.WithTimeout(context.Background(), tlsTimeout)
	defer wcancel()
	if err := ec.WaitConnected(wctx); err != nil {
		t.Fatalf("event client did not connect over pinned TLS: %v", err)
	}
}

// (b) a wrong fingerprint fails with the clear mismatch error, and the fake
// phone never even saw the request: the TLS handshake is rejected before any
// HTTP request reaches its handler.
func TestTLSPinningRejectsWrongFingerprint(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok", TLS: true})
	defer p.Close()

	real := realFingerprint(t, p)
	wrong := strings.Repeat("00:", 31) + "00" // valid shape, deliberately incorrect
	if wrong == real {
		t.Fatal("test fixture collision: wrong fingerprint matches the real one")
	}

	client, err := NewPinnedHTTPClient(wrong, tlsTimeout)
	if err != nil {
		t.Fatalf("NewPinnedHTTPClient: %v", err)
	}

	mc := NewMCPClient(MCPOptions{BaseURL: p.URL(), Token: "tok", HTTPClient: client})
	_, err = mc.Initialize(context.Background())
	if err == nil {
		t.Fatal("expected a TLS certificate fingerprint mismatch error")
	}
	if !strings.Contains(err.Error(), "fingerprint mismatch") {
		t.Errorf("error = %v, want a fingerprint mismatch error", err)
	}
	// The error names both fingerprints, truncated to the first 8 bytes.
	if !strings.Contains(err.Error(), "00:00:00:00:00:00:00:00") {
		t.Errorf("error = %v, want it to name the configured (wrong) fingerprint", err)
	}
	if !strings.Contains(err.Error(), real[:23]) { // first 8 bytes = 23 chars of "XX:XX:...:XX"
		t.Errorf("error = %v, want it to name the server's real fingerprint", err)
	}

	if got := p.RequestCount(); got != 0 {
		t.Errorf("fake phone saw %d requests, want 0: a rejected TLS handshake must never reach the handler", got)
	}
}

// (c) https without a fingerprint is refused at config validation, before any
// connection is attempted. config.Validate's rules are exercised in depth in
// internal/config; this confirms the same guarantee from the device package's
// point of view, since it's what stands between an unpinned https base_url
// and an actual dial.
func TestHTTPSWithoutFingerprintRefusedAtConfigValidation(t *testing.T) {
	yaml := `
device:
  base_url: https://127.0.0.1:8766
  token_env: SYMBIOSIS_DEVICE_TOKEN
providers: []
`
	_, err := config.Parse([]byte(yaml))
	if err == nil {
		t.Fatal("expected a validation error for https base_url with no TLS fingerprint configured")
	}
	if !strings.Contains(err.Error(), "tls_fingerprint_sha256") {
		t.Errorf("error = %v, want it to mention device.tls_fingerprint_sha256", err)
	}
	if !strings.Contains(err.Error(), "gateway pair") || !strings.Contains(err.Error(), "/health") {
		t.Errorf("error = %v, want it to point at gateway pair and /health as sources", err)
	}
}

// (d) formatting round trip: sha256 -> "AA:BB:...", and the comparison used
// for pinning ignores case and colons.
func TestFormatFingerprintRoundTrip(t *testing.T) {
	sum := sha256.Sum256([]byte("symbiosis-tls-fingerprint-test"))

	got := FormatFingerprintSHA256(sum[:])
	if !regexp.MustCompile(`^([0-9A-F]{2}:){31}[0-9A-F]{2}$`).MatchString(got) {
		t.Fatalf("format = %q, want 32 colon-separated uppercase hex bytes", got)
	}

	want := strings.ToUpper(hex.EncodeToString(sum[:]))
	if normalizeFingerprint(got) != want {
		t.Errorf("normalizeFingerprint(format(x)) = %q, want %q", normalizeFingerprint(got), want)
	}

	lower := strings.ToLower(got)
	noColons := strings.ReplaceAll(got, ":", "")
	if normalizeFingerprint(lower) != normalizeFingerprint(got) {
		t.Error("normalizeFingerprint should ignore case")
	}
	if normalizeFingerprint(noColons) != normalizeFingerprint(got) {
		t.Error("normalizeFingerprint should ignore colons")
	}
}
