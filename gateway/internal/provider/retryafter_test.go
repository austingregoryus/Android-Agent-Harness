package provider

import (
	"context"
	"net/http"
	"testing"
	"time"
)

// TestRetryAfterParsing covers both forms RFC 9110 allows plus the shapes a
// buggy or hostile server can send.
func TestRetryAfterParsing(t *testing.T) {
	now := time.Date(2026, 9, 6, 12, 0, 0, 0, time.UTC)
	tests := []struct {
		name  string
		value string
		want  time.Duration
	}{
		{"absent", "", 0},
		{"delta seconds", "5", 5 * time.Second},
		{"fractional seconds", "1.5", 1500 * time.Millisecond},
		{"zero", "0", 0},
		{"negative", "-3", 0},
		{"clamped", "86400", maxRetryAfter},
		{"http date", "Sun, 06 Sep 2026 12:00:30 GMT", 30 * time.Second},
		{"http date in the past", "Sun, 06 Sep 2026 11:59:00 GMT", 0},
		{"http date far out is clamped", "Mon, 07 Sep 2026 12:00:00 GMT", maxRetryAfter},
		{"garbage", "soon", 0},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			h := http.Header{}
			if tc.value != "" {
				h.Set("Retry-After", tc.value)
			}
			if got := retryAfter(h, now); got != tc.want {
				t.Errorf("retryAfter(%q) = %v, want %v", tc.value, got, tc.want)
			}
		})
	}
}

// TestRetryAfterIsAFloorNotAReplacement checks the backoff policy: a server
// asking for longer gets longer, a server asking for shorter does not shorten
// the gateway's own backoff.
func TestRetryAfterIsAFloorNotAReplacement(t *testing.T) {
	tests := []struct {
		name       string
		backoff    time.Duration
		retryAfter time.Duration
		want       time.Duration
	}{
		{"no hint", 500 * time.Millisecond, 0, 500 * time.Millisecond},
		{"shorter hint is ignored", 2 * time.Second, 100 * time.Millisecond, 2 * time.Second},
		{"longer hint wins", 500 * time.Millisecond, 3 * time.Second, 3 * time.Second},
		{"hint is capped", time.Second, 10 * time.Minute, maxRetryAfter},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got := nextBackoff(tc.backoff, &Error{RetryAfter: tc.retryAfter})
			if got != tc.want {
				t.Errorf("nextBackoff = %v, want %v", got, tc.want)
			}
		})
	}
	if got := nextBackoff(time.Second, nil); got != time.Second {
		t.Errorf("nextBackoff with no previous error = %v", got)
	}
}

// TestRetryAfterIsCapturedOnOverload checks the header reaches provider.Error on
// the two statuses that carry it in practice: 429 and Anthropic's 529.
func TestRetryAfterIsCapturedOnOverload(t *testing.T) {
	for _, status := range []int{429, 529} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			attempts := 0
			stub := stubDoer(func(req *http.Request) (*http.Response, error) {
				attempts++
				resp := jsonResponse(status, `{"error":{"type":"overloaded_error"}}`)
				resp.Header.Set("Retry-After", "1")
				return resp, nil
			})
			// The context deadline stops the retry loop before the (now
			// Retry-After floored) one-second wait elapses, which keeps the
			// test fast while still proving the hint was recorded.
			ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
			defer cancel()

			err := postJSON(ctx, stub, "claude", "http://example.invalid/v1/messages",
				nil, map[string]any{"model": "m"}, new(struct{}))
			if err == nil {
				t.Fatal("expected an error")
			}
			pe, ok := err.(*Error)
			if !ok {
				t.Fatalf("error type = %T: %v", err, err)
			}
			if pe.Status == status && pe.RetryAfter != time.Second {
				t.Errorf("RetryAfter = %v, want 1s", pe.RetryAfter)
			}
			if attempts == 0 {
				t.Error("no request was made")
			}
			if !(&Error{Status: status}).Retryable() {
				t.Errorf("HTTP %d is not treated as retryable", status)
			}
		})
	}
}
