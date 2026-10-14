package provider

import (
	"encoding/json"
	"io"
	"net/http"
	"reflect"
	"strings"
	"testing"
)

// stubDoer adapts a function into the httpDoer interface.
type stubDoer func(*http.Request) (*http.Response, error)

// Do implements httpDoer.
func (f stubDoer) Do(req *http.Request) (*http.Response, error) { return f(req) }

func jsonResponse(status int, body string) *http.Response {
	return &http.Response{
		StatusCode: status,
		Header:     http.Header{"Content-Type": []string{"application/json"}},
		Body:       io.NopCloser(strings.NewReader(body)),
	}
}

// assertJSONEqual compares a marshalled value against a fixture, ignoring key
// order and formatting.
func assertJSONEqual(t *testing.T, got any, fixture string) {
	t.Helper()
	gotRaw, err := json.Marshal(got)
	if err != nil {
		t.Fatalf("marshal got: %v", err)
	}
	var gotAny, wantAny any
	if err := json.Unmarshal(gotRaw, &gotAny); err != nil {
		t.Fatalf("unmarshal got: %v", err)
	}
	if err := json.Unmarshal([]byte(fixture), &wantAny); err != nil {
		t.Fatalf("unmarshal fixture: %v", err)
	}
	if !reflect.DeepEqual(gotAny, wantAny) {
		gotPretty, _ := json.MarshalIndent(gotAny, "", "  ")
		wantPretty, _ := json.MarshalIndent(wantAny, "", "  ")
		t.Fatalf("wire form does not match the fixture:\n--- got ---\n%s\n--- want ---\n%s", gotPretty, wantPretty)
	}
}

// stripProvider clears the Provider attribution so encode/decode round trips can
// be compared structurally (the wire formats do not carry it).
func stripProvider(msgs []Message) []Message {
	out := make([]Message, len(msgs))
	for i, m := range msgs {
		c := m
		c.Provider = ""
		c.Parts = append([]Part(nil), m.Parts...)
		for j := range c.Parts {
			if c.Parts[j].Args != nil && len(c.Parts[j].Args) == 0 {
				c.Parts[j].Args = map[string]any{}
			}
		}
		out[i] = c
	}
	return out
}

func dump(msgs []Message) string {
	raw, _ := json.MarshalIndent(msgs, "", "  ")
	return string(raw)
}

func dumpParts(parts []Part) string {
	raw, _ := json.MarshalIndent(parts, "", "  ")
	return string(raw)
}
