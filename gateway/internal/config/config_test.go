package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const goodYAML = `
device:
  base_url: http://127.0.0.1:8765
  token_env: SYMBIOSIS_DEVICE_TOKEN
  token_file: secrets/device-token
providers:
  - name: claude
    kind: anthropic
    model: claude-sonnet-5
    api_key_env: ANTHROPIC_API_KEY
  - name: local
    kind: openai
    model: qwen3
    api_key_env: LOCAL_KEY
    base_url: http://127.0.0.1:11434/v1
failover: [claude, local]
agent:
  max_turns: 8
  tool_allowlist: ["messaging.*", "device.get_*"]
  correlation_prefix: corr_
storage:
  sqlite_path: gateway.db
`

func TestParseGood(t *testing.T) {
	c, err := Parse([]byte(goodYAML))
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
	if c.Device.BaseURL != "http://127.0.0.1:8765" {
		t.Errorf("base_url = %q", c.Device.BaseURL)
	}
	if len(c.Providers) != 2 || c.Providers[0].Kind != KindAnthropic {
		t.Fatalf("providers = %+v", c.Providers)
	}
	chain := c.FailoverChain()
	if len(chain) != 2 || chain[0].Name != "claude" || chain[1].Name != "local" {
		t.Errorf("chain = %+v", chain)
	}
	if c.Agent.MaxTurns != 8 {
		t.Errorf("max_turns = %d", c.Agent.MaxTurns)
	}
}

func TestParseDefaults(t *testing.T) {
	c, err := Parse([]byte(`
device: {base_url: "http://x", token_env: T}
providers: [{name: f, kind: fake}]
`))
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
	if c.Agent.MaxTurns != 12 {
		t.Errorf("default max_turns = %d, want 12", c.Agent.MaxTurns)
	}
	if c.Agent.CorrelationPrefix != "corr_" {
		t.Errorf("default correlation prefix = %q", c.Agent.CorrelationPrefix)
	}
	if c.Storage.SQLitePath != "gateway.db" {
		t.Errorf("default sqlite path = %q", c.Storage.SQLitePath)
	}
	if len(c.Failover) != 1 || c.Failover[0] != "f" {
		t.Errorf("default failover = %v", c.Failover)
	}
}

func TestParseErrors(t *testing.T) {
	tests := []struct {
		name string
		yaml string
		want string
	}{
		{"no base url", "device: {token_env: T}\nproviders: []\n", "device.base_url is required"},
		{"no token source", "device: {base_url: http://x}\nproviders: []\n", "token_env or token_file"},
		{"unknown kind", "device: {base_url: http://x, token_env: T}\nproviders: [{name: p, kind: llama, api_key_env: K}]\n", "unknown kind"},
		{"missing key env", "device: {base_url: http://x, token_env: T}\nproviders: [{name: p, kind: anthropic}]\n", "api_key_env is required"},
		{"bad failover ref", "device: {base_url: http://x, token_env: T}\nproviders: [{name: p, kind: fake}]\nfailover: [nope]\n", "unknown provider"},
		{"unknown field", "device: {base_url: http://x, token_env: T}\nnonsense: 1\n", "field nonsense"},
		{"https without tls fingerprint", "device: {base_url: https://x, token_env: T}\nproviders: []\n", "tls_fingerprint_sha256"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			_, err := Parse([]byte(tc.yaml))
			if err == nil {
				t.Fatalf("expected an error")
			}
			if !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("error = %v, want it to contain %q", err, tc.want)
			}
		})
	}
}

func TestDeviceTokenPrefersEnv(t *testing.T) {
	dir := t.TempDir()
	tokenPath := filepath.Join(dir, "tok")
	if err := os.WriteFile(tokenPath, []byte("from-file\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	c := &Config{Device: Device{TokenEnv: "SYMB_TEST_TOKEN", TokenFile: tokenPath}}

	// File only.
	got, err := c.DeviceToken()
	if err != nil || got != "from-file" {
		t.Fatalf("file token = %q, %v", got, err)
	}

	// Env wins.
	t.Setenv("SYMB_TEST_TOKEN", "from-env")
	got, err = c.DeviceToken()
	if err != nil || got != "from-env" {
		t.Fatalf("env token = %q, %v", got, err)
	}
}

func TestDeviceTokenMissingIsNotSecretLeaking(t *testing.T) {
	c := &Config{Device: Device{TokenEnv: "SYMB_ABSENT_TOKEN", TokenFile: filepath.Join(t.TempDir(), "absent")}}
	_, err := c.DeviceToken()
	if err == nil {
		t.Fatal("expected an error")
	}
	if !strings.Contains(err.Error(), "gateway pair") {
		t.Errorf("error should point at pairing, got %v", err)
	}
}

func TestWriteDeviceToken(t *testing.T) {
	dir := t.TempDir()
	c := &Config{Device: Device{TokenFile: filepath.Join(dir, "nested", "tok")}}
	if err := c.WriteDeviceToken("s3cret"); err != nil {
		t.Fatalf("WriteDeviceToken: %v", err)
	}
	got, err := c.DeviceToken()
	if err != nil || got != "s3cret" {
		t.Fatalf("round trip = %q, %v", got, err)
	}
}

func TestLoadResolvesRelativePaths(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")
	if err := os.WriteFile(path, []byte(goodYAML), 0o600); err != nil {
		t.Fatal(err)
	}
	c, err := Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if want := filepath.Join(dir, "gateway.db"); c.SQLitePath() != want {
		t.Errorf("SQLitePath = %q, want %q", c.SQLitePath(), want)
	}
	if want := filepath.Join(dir, "secrets", "device-token"); c.TokenFilePath() != want {
		t.Errorf("TokenFilePath = %q, want %q", c.TokenFilePath(), want)
	}
}

func TestValidateAllowsHTTPSWithInlineFingerprint(t *testing.T) {
	_, err := Parse([]byte(`
device: {base_url: "https://x", token_env: T, tls_fingerprint_sha256: "AA:BB:CC"}
providers: [{name: f, kind: fake}]
`))
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
}

func TestValidateAllowsHTTPSWithFingerprintFile(t *testing.T) {
	_, err := Parse([]byte(`
device: {base_url: "https://x", token_env: T, tls_fingerprint_file: secrets/device-tls-fingerprint}
providers: [{name: f, kind: fake}]
`))
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
}

func TestDeviceTLSFingerprintFileWinsOverInline(t *testing.T) {
	dir := t.TempDir()
	fpPath := filepath.Join(dir, "fp")
	if err := os.WriteFile(fpPath, []byte("FILE:FINGERPRINT\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	c := &Config{Device: Device{TLSFingerprintSHA256: "INLINE:FINGERPRINT", TLSFingerprintFile: fpPath}}
	got, err := c.DeviceTLSFingerprint()
	if err != nil || got != "FILE:FINGERPRINT" {
		t.Fatalf("DeviceTLSFingerprint = %q, %v, want the file value to win", got, err)
	}
}

func TestDeviceTLSFingerprintFallsBackToInline(t *testing.T) {
	c := &Config{Device: Device{TLSFingerprintSHA256: "INLINE:FINGERPRINT"}}
	got, err := c.DeviceTLSFingerprint()
	if err != nil || got != "INLINE:FINGERPRINT" {
		t.Fatalf("DeviceTLSFingerprint = %q, %v", got, err)
	}
}

func TestDeviceTLSFingerprintMissingIsNotSecretLeaking(t *testing.T) {
	c := &Config{Device: Device{BaseURL: "https://x"}}
	_, err := c.DeviceTLSFingerprint()
	if err == nil {
		t.Fatal("expected an error")
	}
	if !strings.Contains(err.Error(), "gateway pair") || !strings.Contains(err.Error(), "/health") {
		t.Errorf("error should point at gateway pair and /health, got %v", err)
	}
}

func TestWriteDeviceTLSFingerprintDefaultsNextToTokenFile(t *testing.T) {
	dir := t.TempDir()
	c := &Config{Device: Device{TokenFile: filepath.Join(dir, "secrets", "device-token")}}

	want := filepath.Join(dir, "secrets", "device-tls-fingerprint")
	if got := c.TLSFingerprintFilePath(); got != want {
		t.Fatalf("TLSFingerprintFilePath = %q, want %q", got, want)
	}

	if err := c.WriteDeviceTLSFingerprint("AA:BB:CC"); err != nil {
		t.Fatalf("WriteDeviceTLSFingerprint: %v", err)
	}
	got, err := c.DeviceTLSFingerprint()
	if err != nil || got != "AA:BB:CC" {
		t.Fatalf("round trip = %q, %v", got, err)
	}
}

func TestWriteDeviceTLSFingerprintExplicitFileWinsOverDefault(t *testing.T) {
	dir := t.TempDir()
	c := &Config{Device: Device{
		TokenFile:          filepath.Join(dir, "secrets", "device-token"),
		TLSFingerprintFile: filepath.Join(dir, "elsewhere", "fp"),
	}}
	if err := c.WriteDeviceTLSFingerprint("DD:EE:FF"); err != nil {
		t.Fatalf("WriteDeviceTLSFingerprint: %v", err)
	}
	want := filepath.Join(dir, "elsewhere", "fp")
	if c.TLSFingerprintFilePath() != want {
		t.Errorf("TLSFingerprintFilePath = %q, want %q", c.TLSFingerprintFilePath(), want)
	}
	if _, err := os.Stat(filepath.Join(dir, "secrets", "device-tls-fingerprint")); !os.IsNotExist(err) {
		t.Error("default path should not have been written when tls_fingerprint_file is explicit")
	}
}
