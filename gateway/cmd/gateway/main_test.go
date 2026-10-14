package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/config"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/fakephone"
)

// writeConfig materialises a config file pointing at a fake phone.
func writeConfig(t *testing.T, baseURL string, extra string) string {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")
	body := fmt.Sprintf(`device:
  base_url: %s
  token_env: SYMB_TEST_DEVICE_TOKEN
  token_file: secrets/token
providers:
  - name: offline
    kind: fake
failover: [offline]
agent:
  max_turns: 4
  correlation_prefix: corr_
storage:
  sqlite_path: gateway.db
%s`, baseURL, extra)
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestCmdRunSmoke(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	t.Setenv("SYMB_TEST_DEVICE_TOKEN", "tok")

	cfgPath := writeConfig(t, p.URL(), "")
	if err := cmdRun(context.Background(), []string{"--config", cfgPath, "what is the battery level"}); err != nil {
		t.Fatalf("cmdRun: %v", err)
	}
}

func TestCmdRunRequiresTaskText(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	t.Setenv("SYMB_TEST_DEVICE_TOKEN", "tok")

	cfgPath := writeConfig(t, p.URL(), "")
	err := cmdRun(context.Background(), []string{"--config", cfgPath})
	if err == nil || !strings.Contains(err.Error(), "task text") {
		t.Fatalf("err = %v", err)
	}
}

func TestCmdRunWithoutToken(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	// SYMB_TEST_DEVICE_TOKEN deliberately unset and no token file written.
	t.Setenv("SYMB_TEST_DEVICE_TOKEN", "")

	cfgPath := writeConfig(t, p.URL(), "")
	err := cmdRun(context.Background(), []string{"--config", cfgPath, "x"})
	if err == nil {
		t.Fatal("expected a missing-token error")
	}
	if !strings.Contains(err.Error(), "no device token found") {
		t.Fatalf("err = %v", err)
	}
}

func TestCmdPairWritesTokenAndNeverReturnsIt(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	t.Setenv("SYMB_TEST_DEVICE_TOKEN", "")

	cfgPath := writeConfig(t, p.URL(), "")
	if err := cmdPair(context.Background(), []string{
		"--config", cfgPath, "--code", "K7Q2-9F4A", "--label", "homelab",
	}); err != nil {
		t.Fatalf("cmdPair: %v", err)
	}

	cfg, err := config.Load(cfgPath)
	if err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(cfg.TokenFilePath())
	if err != nil {
		t.Fatalf("token file was not written: %v", err)
	}
	if strings.TrimSpace(string(raw)) != "fake-token-value-do-not-log" {
		t.Errorf("token file contents = %q", strings.TrimSpace(string(raw)))
	}
	tok, err := cfg.DeviceToken()
	if err != nil || tok == "" {
		t.Fatalf("DeviceToken after pairing = %q, %v", tok, err)
	}
}

func TestCmdPairErrors(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	cfgPath := writeConfig(t, p.URL(), "")

	tests := []struct {
		name string
		argv []string
		want string
	}{
		{"no config", []string{"--code", "X"}, "--config is required"},
		{"no code", []string{"--config", cfgPath}, "--code is required"},
		{"rejected code", []string{"--config", cfgPath, "--code", "BAD-CODE"}, "HTTP 403"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			err := cmdPair(context.Background(), tc.argv)
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("err = %v, want it to contain %q", err, tc.want)
			}
		})
	}
}

func TestCmdHealth(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	cfgPath := writeConfig(t, p.URL(), "")

	// The fake provider's Health always succeeds, so this must pass.
	if err := cmdHealth(context.Background(), []string{"--config", cfgPath}); err != nil {
		t.Fatalf("cmdHealth: %v", err)
	}

	// With the phone gone, health must fail.
	p.Close()
	if err := cmdHealth(context.Background(), []string{"--config", cfgPath}); err == nil {
		t.Fatal("expected health to fail once the phone is unreachable")
	}
}

func TestModelOrDefault(t *testing.T) {
	tests := []struct {
		name string
		pc   config.Provider
		want string
	}{
		{"explicit", config.Provider{Kind: config.KindAnthropic, Model: "claude-opus-4"}, "claude-opus-4"},
		{"anthropic default", config.Provider{Kind: config.KindAnthropic}, "claude-sonnet-5"},
		{"openai default", config.Provider{Kind: config.KindOpenAI}, "gpt-4o"},
		{"gemini default", config.Provider{Kind: config.KindGemini}, "gemini-2.5-flash"},
		{"fake", config.Provider{Kind: config.KindFake}, "(none)"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := modelOrDefault(tc.pc); got != tc.want {
				t.Errorf("= %q, want %q", got, tc.want)
			}
		})
	}
}
