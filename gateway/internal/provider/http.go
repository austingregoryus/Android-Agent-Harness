package provider

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// maxAttempts is the total number of tries (1 initial + 2 retries) for a
// retryable provider failure.
const maxAttempts = 3

// maxRetryAfter caps how long a server-supplied Retry-After may hold a request.
// Anthropic answers an overload with 529 and Retry-After; a hostile or buggy
// value must not park the agent loop for hours.
const maxRetryAfter = 60 * time.Second

// retryAfter parses the Retry-After header in both forms the RFC allows:
// delta-seconds and an HTTP-date. It returns 0 when the header is absent,
// unparseable, or already in the past, and clamps the result to maxRetryAfter.
func retryAfter(h http.Header, now time.Time) time.Duration {
	v := strings.TrimSpace(h.Get("Retry-After"))
	if v == "" {
		return 0
	}
	if secs, err := strconv.ParseFloat(v, 64); err == nil {
		if secs <= 0 {
			return 0
		}
		return clampRetryAfter(time.Duration(secs * float64(time.Second)))
	}
	if t, err := http.ParseTime(v); err == nil {
		d := t.Sub(now)
		if d <= 0 {
			return 0
		}
		return clampRetryAfter(d)
	}
	return 0
}

func clampRetryAfter(d time.Duration) time.Duration {
	if d > maxRetryAfter {
		return maxRetryAfter
	}
	return d
}

// nextBackoff applies the server's Retry-After hint as a floor on the computed
// exponential backoff, so a provider that asks for a longer pause gets one and a
// provider that asks for a shorter one is still backed off.
func nextBackoff(backoff time.Duration, last *Error) time.Duration {
	if last != nil && last.RetryAfter > backoff {
		return clampRetryAfter(last.RetryAfter)
	}
	return backoff
}

// httpDoer is the subset of *http.Client the adapters use. Tests substitute it.
type httpDoer interface {
	Do(req *http.Request) (*http.Response, error)
}

// defaultClient is the shared HTTP client for provider adapters.
func defaultClient() *http.Client {
	return &http.Client{Timeout: 120 * time.Second}
}

// postJSON sends body as JSON to url with the given headers and decodes a JSON
// response into out. It retries 429 and 5xx responses and transport errors with
// exponential backoff, up to maxAttempts.
func postJSON(ctx context.Context, doer httpDoer, name, url string, headers map[string]string, body any, out any) error {
	raw, err := json.Marshal(body)
	if err != nil {
		return &Error{Provider: name, Err: fmt.Errorf("encode request: %w", err)}
	}

	var last *Error
	backoff := 500 * time.Millisecond
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		if attempt > 1 {
			wait := nextBackoff(backoff, last)
			jitter := time.Duration(rand.Int63n(int64(wait/4) + 1))
			select {
			case <-ctx.Done():
				return &Error{Provider: name, Err: ctx.Err()}
			case <-time.After(wait + jitter):
			}
			backoff *= 2
		}

		req, rerr := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(raw))
		if rerr != nil {
			return &Error{Provider: name, Err: rerr}
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Accept", "application/json")
		for k, v := range headers {
			req.Header.Set(k, v)
		}

		resp, derr := doer.Do(req)
		if derr != nil {
			last = &Error{Provider: name, Err: derr}
			if ctx.Err() != nil {
				return last
			}
			continue
		}

		respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		resp.Body.Close()

		if resp.StatusCode >= 200 && resp.StatusCode < 300 {
			if out == nil {
				return nil
			}
			if err := json.Unmarshal(respBody, out); err != nil {
				return &Error{Provider: name, Status: resp.StatusCode, Err: fmt.Errorf("decode response: %w", err)}
			}
			return nil
		}

		last = &Error{
			Provider:   name,
			Status:     resp.StatusCode,
			Body:       truncate(string(respBody), 512),
			RetryAfter: retryAfter(resp.Header, time.Now()),
		}
		if !last.Retryable() {
			return last
		}
	}
	if last == nil {
		last = &Error{Provider: name, Err: fmt.Errorf("no attempts made")}
	}
	return last
}

// getJSON performs a GET and decodes JSON, with the same retry policy as postJSON.
func getJSON(ctx context.Context, doer httpDoer, name, url string, headers map[string]string, out any) error {
	var last *Error
	backoff := 500 * time.Millisecond
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		if attempt > 1 {
			wait := nextBackoff(backoff, last)
			jitter := time.Duration(rand.Int63n(int64(wait/4) + 1))
			select {
			case <-ctx.Done():
				return &Error{Provider: name, Err: ctx.Err()}
			case <-time.After(wait + jitter):
			}
			backoff *= 2
		}
		req, rerr := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
		if rerr != nil {
			return &Error{Provider: name, Err: rerr}
		}
		req.Header.Set("Accept", "application/json")
		for k, v := range headers {
			req.Header.Set(k, v)
		}
		resp, derr := doer.Do(req)
		if derr != nil {
			last = &Error{Provider: name, Err: derr}
			if ctx.Err() != nil {
				return last
			}
			continue
		}
		respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		resp.Body.Close()
		if resp.StatusCode >= 200 && resp.StatusCode < 300 {
			if out == nil {
				return nil
			}
			if err := json.Unmarshal(respBody, out); err != nil {
				return &Error{Provider: name, Status: resp.StatusCode, Err: fmt.Errorf("decode response: %w", err)}
			}
			return nil
		}
		last = &Error{
			Provider:   name,
			Status:     resp.StatusCode,
			Body:       truncate(string(respBody), 512),
			RetryAfter: retryAfter(resp.Header, time.Now()),
		}
		if !last.Retryable() {
			return last
		}
	}
	if last == nil {
		last = &Error{Provider: name, Err: fmt.Errorf("no attempts made")}
	}
	return last
}

func truncate(s string, n int) string {
	s = strings.TrimSpace(s)
	if len(s) <= n {
		return s
	}
	return s[:n] + "..."
}
