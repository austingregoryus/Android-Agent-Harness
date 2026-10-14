package device

import (
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"
)

// FormatFingerprintSHA256 renders a SHA-256 digest as colon-separated
// uppercase hex, e.g. "A0:D3:96:2E:...". This is the format the phone
// advertises in GET /health and POST /pair (tls_fingerprint_sha256) and the
// format "gateway pair" persists to disk.
func FormatFingerprintSHA256(sum []byte) string {
	parts := make([]string, len(sum))
	for i, b := range sum {
		parts[i] = fmt.Sprintf("%02X", b)
	}
	return strings.Join(parts, ":")
}

// normalizeFingerprint upper-cases a fingerprint and strips colons, so two
// fingerprints can be compared case-insensitively and colon-insensitively.
func normalizeFingerprint(fp string) string {
	return strings.ToUpper(strings.ReplaceAll(strings.TrimSpace(fp), ":", ""))
}

// FingerprintPreview renders the first 8 bytes (16 hex chars) of a
// fingerprint as colon-separated uppercase hex, for display in logs and error
// messages without printing the whole value.
func FingerprintPreview(fp string) string {
	return previewNormalized(normalizeFingerprint(fp))
}

// previewNormalized renders the first 8 bytes of an already-normalized
// (upper-case, colon-free) fingerprint as colon-separated hex.
func previewNormalized(normalized string) string {
	const n = 8 // bytes
	if len(normalized) > n*2 {
		normalized = normalized[:n*2]
	}
	var b strings.Builder
	for i := 0; i < len(normalized); i += 2 {
		if i > 0 {
			b.WriteByte(':')
		}
		end := i + 2
		if end > len(normalized) {
			end = len(normalized)
		}
		b.WriteString(normalized[i:end])
	}
	return b.String()
}

// NewPinnedTLSConfig builds a *tls.Config that authenticates the phone's
// TLS-only connector by pinning its self-signed certificate's SHA-256
// fingerprint, instead of verifying it against a certificate authority.
//
// Nothing about the certificate is checked except the pin: InsecureSkipVerify
// is set deliberately so Go's normal chain-of-trust and hostname verification
// never run (the certificate has no SANs and is signed by no CA), and
// VerifyPeerCertificate enforces the fingerprint pin in their place. No client
// certificate is sent.
func NewPinnedTLSConfig(fingerprint string) (*tls.Config, error) {
	want := normalizeFingerprint(fingerprint)
	if want == "" {
		return nil, errors.New("device: TLS: no fingerprint configured to pin against")
	}
	if len(want) != sha256.Size*2 {
		return nil, fmt.Errorf("device: TLS: fingerprint %q is not a 32-byte SHA-256 digest", fingerprint)
	}
	return &tls.Config{
		InsecureSkipVerify: true, // nolint: gosec // VerifyPeerCertificate below replaces the default check.
		VerifyPeerCertificate: func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
			if len(rawCerts) == 0 {
				return errors.New("device: TLS: server presented no certificate")
			}
			sum := sha256.Sum256(rawCerts[0])
			got := normalizeFingerprint(FormatFingerprintSHA256(sum[:]))
			if got != want {
				return fmt.Errorf("device: TLS certificate fingerprint mismatch: configured %s..., server presented %s...",
					previewNormalized(want), previewNormalized(got))
			}
			return nil
		},
	}, nil
}

// NewPinnedHTTPClient builds one *http.Client whose transport uses the pinned
// TLS config. The same client is meant to be shared by the MCP HTTP client
// and, via EventOptions.HTTPClient, the /ws/events WebSocket dialer:
// coder/websocket dials through an *http.Client, so wiring the transport here
// is enough to cover both.
func NewPinnedHTTPClient(fingerprint string, timeout time.Duration) (*http.Client, error) {
	tlsCfg, err := NewPinnedTLSConfig(fingerprint)
	if err != nil {
		return nil, err
	}
	return &http.Client{
		Timeout:   timeout,
		Transport: &http.Transport{TLSClientConfig: tlsCfg},
	}, nil
}
