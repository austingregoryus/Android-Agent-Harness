package provider

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/config"
)

func TestFromConfig(t *testing.T) {
	t.Setenv("TEST_ANTHROPIC_KEY", "sk-a")
	t.Setenv("TEST_OPENAI_KEY", "sk-o")
	t.Setenv("TEST_GEMINI_KEY", "sk-g")

	tests := []struct {
		name    string
		pc      config.Provider
		wantErr string
	}{
		{"anthropic", config.Provider{Name: "c", Kind: config.KindAnthropic, APIKeyEnv: "TEST_ANTHROPIC_KEY"}, ""},
		{"openai", config.Provider{Name: "o", Kind: config.KindOpenAI, APIKeyEnv: "TEST_OPENAI_KEY"}, ""},
		{"gemini", config.Provider{Name: "g", Kind: config.KindGemini, APIKeyEnv: "TEST_GEMINI_KEY"}, ""},
		{"fake", config.Provider{Name: "f", Kind: config.KindFake}, ""},
		{"keyless local openai", config.Provider{Name: "l", Kind: config.KindOpenAI,
			APIKeyEnv: "TEST_ABSENT", BaseURL: "http://127.0.0.1:11434/v1"}, ""},
		{"missing anthropic key", config.Provider{Name: "c", Kind: config.KindAnthropic,
			APIKeyEnv: "TEST_ABSENT"}, "unset or empty"},
		{"missing gemini key", config.Provider{Name: "g", Kind: config.KindGemini,
			APIKeyEnv: "TEST_ABSENT"}, "unset or empty"},
		{"unknown kind", config.Provider{Name: "x", Kind: "llama"}, "unknown kind"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			p, err := FromConfig(tc.pc)
			if tc.wantErr != "" {
				if err == nil || !strings.Contains(err.Error(), tc.wantErr) {
					t.Fatalf("err = %v, want it to contain %q", err, tc.wantErr)
				}
				return
			}
			if err != nil {
				t.Fatalf("FromConfig: %v", err)
			}
			if p.Name() != tc.pc.Name {
				t.Errorf("Name() = %q, want %q", p.Name(), tc.pc.Name)
			}
			if !p.Capabilities().ToolCalling {
				t.Error("every configured provider must support tool calling")
			}
		})
	}
}

func TestChainFromConfig(t *testing.T) {
	cfg, err := config.Parse([]byte(`
device: {base_url: "http://x", token_env: T}
providers:
  - {name: a, kind: fake}
  - {name: b, kind: fake}
failover: [b, a]
`))
	if err != nil {
		t.Fatal(err)
	}
	chain, err := ChainFromConfig(cfg)
	if err != nil {
		t.Fatalf("ChainFromConfig: %v", err)
	}
	if len(chain) != 2 || chain[0].Name() != "b" || chain[1].Name() != "a" {
		t.Fatalf("chain order = %v", []string{chain[0].Name(), chain[1].Name()})
	}
}

func TestFakeProviderScriptAndFailure(t *testing.T) {
	ctx := context.Background()
	script := []ScriptStep{
		{Text: "one", ToolCalls: []Part{ToolCallPart("", "device.get_battery", nil)}},
		{Text: "two"},
	}

	t.Run("script advances with assistant turns", func(t *testing.T) {
		p := NewFake(FakeOptions{Name: "f", Script: script})
		first, err := p.Generate(ctx, GenerateRequest{Messages: []Message{
			{Role: RoleUser, Parts: []Part{TextPart("go")}},
		}})
		if err != nil {
			t.Fatal(err)
		}
		if first.StopReason != StopToolUse || len(first.Message.ToolCalls()) != 1 {
			t.Fatalf("first turn = %+v", first)
		}
		if first.Message.ToolCalls()[0].ID == "" {
			t.Error("the fake must mint a tool-call id when the script omits one")
		}

		second, err := p.Generate(ctx, GenerateRequest{Messages: []Message{
			{Role: RoleUser, Parts: []Part{TextPart("go")}},
			first.Message,
			{Role: RoleTool, Parts: []Part{ToolResultPart("x", "{}", false)}},
		}})
		if err != nil {
			t.Fatal(err)
		}
		if second.StopReason != StopEndTurn || second.Message.TextOnly() != "two" {
			t.Fatalf("second turn = %+v", second)
		}
	})

	t.Run("FailOnCall", func(t *testing.T) {
		p := NewFake(FakeOptions{Name: "f", Script: script, FailOnCall: 1})
		if _, err := p.Generate(ctx, GenerateRequest{}); err == nil {
			t.Fatal("expected the first call to fail")
		}
		if _, err := p.Generate(ctx, GenerateRequest{}); err != nil {
			t.Fatalf("the second call should succeed: %v", err)
		}
	})

	t.Run("HealthErr", func(t *testing.T) {
		p := NewFake(FakeOptions{Name: "f", HealthErr: errors.New("no brain")})
		if err := p.Health(ctx); err == nil {
			t.Fatal("expected Health to fail")
		}
		ok := NewFake(FakeOptions{Name: "g"})
		if err := ok.Health(ctx); err != nil {
			t.Fatalf("Health = %v, want nil", err)
		}
	})

	t.Run("exhausted script terminates", func(t *testing.T) {
		p := NewFake(FakeOptions{Name: "f", Script: nil})
		res, err := p.Generate(ctx, GenerateRequest{})
		if err != nil {
			t.Fatal(err)
		}
		if res.StopReason != StopEndTurn {
			t.Errorf("stop reason = %q", res.StopReason)
		}
	})
}

func TestProviderErrorRetryable(t *testing.T) {
	tests := []struct {
		name string
		err  *Error
		want bool
	}{
		{"429", &Error{Status: 429}, true},
		{"500", &Error{Status: 500}, true},
		{"503", &Error{Status: 503}, true},
		{"400", &Error{Status: 400}, false},
		{"401", &Error{Status: 401}, false},
		{"404", &Error{Status: 404}, false},
		{"transport", &Error{Err: errors.New("dial tcp: refused")}, true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := tc.err.Retryable(); got != tc.want {
				t.Errorf("Retryable() = %v, want %v", got, tc.want)
			}
		})
	}
}

func TestCanonicalJSON(t *testing.T) {
	tests := []struct {
		name string
		in   any
		want string
	}{
		{"sorted keys", map[string]any{"b": 1, "a": 2}, `{"a":2,"b":1}`},
		{"nested", map[string]any{"o": map[string]any{"z": 1, "a": 2}}, `{"o":{"a":2,"z":1}}`},
		{"array order preserved", []any{3, 1, 2}, `[3,1,2]`},
		{"empty map", map[string]any{}, `{}`},
		{"nil", nil, `null`},
		{"string escaping", map[string]any{"t": "a\"b"}, `{"t":"a\"b"}`},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, err := CanonicalJSON(tc.in)
			if err != nil {
				t.Fatal(err)
			}
			if string(got) != tc.want {
				t.Errorf("= %s, want %s", got, tc.want)
			}
		})
	}
}
