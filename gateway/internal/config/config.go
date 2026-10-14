// Package config loads the gateway's YAML configuration.
//
// Secrets are never stored inline in the configuration file. A bearer token for
// the phone comes from an environment variable (device.token_env) or from a file
// on disk (device.token_file); provider API keys come from environment variables
// named by providers[].api_key_env.
package config

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"gopkg.in/yaml.v3"
)

// ProviderKind identifies which model-provider adapter to construct.
type ProviderKind string

// Supported provider kinds.
const (
	// KindAnthropic is the Anthropic Messages API adapter.
	KindAnthropic ProviderKind = "anthropic"
	// KindOpenAI is the OpenAI chat-completions adapter. It also covers
	// OpenAI-compatible servers such as Ollama, LM Studio and vLLM via base_url.
	KindOpenAI ProviderKind = "openai"
	// KindGemini is the Google Gemini generateContent adapter.
	KindGemini ProviderKind = "gemini"
	// KindFake is a deterministic scripted provider used by tests.
	KindFake ProviderKind = "fake"
)

// Device describes how to reach the Android embodiment runtime.
type Device struct {
	// BaseURL is the phone runtime root, e.g. http://127.0.0.1:8765. A
	// https:// root selects the phone's TLS-only connector, which uses a
	// self-signed certificate that must be pinned by fingerprint — see
	// TLSFingerprintSHA256 and TLSFingerprintFile.
	BaseURL string `yaml:"base_url"`
	// TokenEnv names an environment variable holding the bearer token.
	TokenEnv string `yaml:"token_env"`
	// TokenFile is a path to a file whose contents are the bearer token.
	// It is also the file that "gateway pair" writes to.
	TokenFile string `yaml:"token_file"`
	// TLSFingerprintSHA256 pins the self-signed certificate presented by an
	// https base_url: colon-separated uppercase hex of the SHA-256 digest of
	// the DER-encoded certificate, e.g. "A0:D3:96:2E:...". The phone
	// advertises it in GET /health and POST /pair as tls_fingerprint_sha256.
	// Required (or TLSFingerprintFile) when base_url is https. Ignored for
	// http. TLSFingerprintFile wins when both are set.
	TLSFingerprintSHA256 string `yaml:"tls_fingerprint_sha256,omitempty"`
	// TLSFingerprintFile is a path to a file whose contents are the pinned
	// fingerprint, in the same format as TLSFingerprintSHA256. It is also the
	// file "gateway pair" writes to when the phone advertises TLS. When set,
	// it wins over the inline TLSFingerprintSHA256 key.
	TLSFingerprintFile string `yaml:"tls_fingerprint_file,omitempty"`
}

// Provider is one configured model provider.
type Provider struct {
	// Name is the identifier referenced by the failover chain.
	Name string `yaml:"name"`
	// Kind selects the adapter implementation.
	Kind ProviderKind `yaml:"kind"`
	// Model is the provider-specific model id. Empty means the adapter default.
	Model string `yaml:"model"`
	// APIKeyEnv names an environment variable holding the provider API key.
	APIKeyEnv string `yaml:"api_key_env"`
	// BaseURL overrides the provider endpoint. It is required for local
	// OpenAI-compatible servers.
	BaseURL string `yaml:"base_url,omitempty"`
	// MaxTokens caps the response length. Zero means the adapter default.
	MaxTokens int `yaml:"max_tokens,omitempty"`
	// Thinking selects extended-thinking behaviour on providers that have it
	// (anthropic). One of "adaptive" (the default) or "disabled". It is ignored
	// by the other kinds.
	Thinking string `yaml:"thinking,omitempty"`
}

// Thinking modes accepted by providers[].thinking.
const (
	// ThinkingAdaptive lets the model decide how much to think.
	ThinkingAdaptive = "adaptive"
	// ThinkingDisabled turns extended thinking off.
	ThinkingDisabled = "disabled"
)

// Agent tunes the tool-calling loop.
type Agent struct {
	// MaxTurns bounds provider round trips for one task.
	MaxTurns int `yaml:"max_turns"`
	// ToolAllowlist, when non-empty, restricts the phone tools exposed to the
	// model. Entries use the protocol's capability glob grammar.
	ToolAllowlist []string `yaml:"tool_allowlist,omitempty"`
	// CorrelationPrefix prefixes minted correlation ids. Defaults to "corr_".
	CorrelationPrefix string `yaml:"correlation_prefix"`
	// ConverseScript is the path of tools/converse.py (relative to this file), which backs
	// gateway.converse_call. Empty disables the tool.
	ConverseScript string `yaml:"converse_script,omitempty"`
	// Python is the interpreter for ConverseScript. Defaults to "python".
	Python string `yaml:"python,omitempty"`
}

// Storage configures local persistence.
type Storage struct {
	// SQLitePath is the on-disk SQLite database path.
	SQLitePath string `yaml:"sqlite_path"`
}

// Config is the whole gateway configuration file.
type Config struct {
	// Device is the phone connection.
	Device Device `yaml:"device"`
	// Providers is the set of configured model providers.
	Providers []Provider `yaml:"providers"`
	// Failover lists provider names in the order they should be tried.
	Failover []string `yaml:"failover"`
	// Agent tunes the agent loop.
	Agent Agent `yaml:"agent"`
	// Storage configures persistence.
	Storage Storage `yaml:"storage"`

	// dir is the directory the config was loaded from. It is used to resolve
	// relative token and database paths.
	dir string
}

// Load reads and validates a YAML configuration file.
func Load(path string) (*Config, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("config: read %s: %w", path, err)
	}
	c, err := Parse(raw)
	if err != nil {
		return nil, fmt.Errorf("config: %s: %w", path, err)
	}
	abs, err := filepath.Abs(path)
	if err != nil {
		abs = path
	}
	c.dir = filepath.Dir(abs)
	return c, nil
}

// Parse decodes and validates configuration bytes. Relative paths inside the
// result are resolved against the process working directory.
func Parse(raw []byte) (*Config, error) {
	var c Config
	dec := yaml.NewDecoder(strings.NewReader(string(raw)))
	dec.KnownFields(true)
	if err := dec.Decode(&c); err != nil {
		return nil, fmt.Errorf("parse: %w", err)
	}
	c.applyDefaults()
	if err := c.Validate(); err != nil {
		return nil, err
	}
	return &c, nil
}

func (c *Config) applyDefaults() {
	if c.Agent.MaxTurns == 0 {
		c.Agent.MaxTurns = 12
	}
	if c.Agent.CorrelationPrefix == "" {
		c.Agent.CorrelationPrefix = "corr_"
	}
	if c.Storage.SQLitePath == "" {
		c.Storage.SQLitePath = "gateway.db"
	}
	if len(c.Failover) == 0 {
		for _, p := range c.Providers {
			c.Failover = append(c.Failover, p.Name)
		}
	}
}

// Validate reports structural problems in the configuration.
func (c *Config) Validate() error {
	var errs []string
	if strings.TrimSpace(c.Device.BaseURL) == "" {
		errs = append(errs, "device.base_url is required")
	}
	if c.Device.TokenEnv == "" && c.Device.TokenFile == "" {
		errs = append(errs, "device: one of token_env or token_file is required")
	}
	if strings.HasPrefix(strings.ToLower(strings.TrimSpace(c.Device.BaseURL)), "https://") &&
		strings.TrimSpace(c.Device.TLSFingerprintSHA256) == "" && strings.TrimSpace(c.Device.TLSFingerprintFile) == "" {
		errs = append(errs, "device.tls_fingerprint_sha256 (or device.tls_fingerprint_file) is required when "+
			"device.base_url is https: run `gateway pair` to record it, or read tls_fingerprint_sha256 from the phone's GET /health")
	}
	seen := map[string]bool{}
	for i, p := range c.Providers {
		if p.Name == "" {
			errs = append(errs, fmt.Sprintf("providers[%d].name is required", i))
			continue
		}
		if seen[p.Name] {
			errs = append(errs, fmt.Sprintf("providers[%d]: duplicate name %q", i, p.Name))
		}
		seen[p.Name] = true
		switch p.Kind {
		case KindAnthropic, KindOpenAI, KindGemini, KindFake:
		case "":
			errs = append(errs, fmt.Sprintf("providers[%d] (%s): kind is required", i, p.Name))
		default:
			errs = append(errs, fmt.Sprintf("providers[%d] (%s): unknown kind %q", i, p.Name, p.Kind))
		}
		if p.Kind != KindFake && p.APIKeyEnv == "" && p.BaseURL == "" {
			errs = append(errs, fmt.Sprintf("providers[%d] (%s): api_key_env is required, or set base_url for a keyless local server", i, p.Name))
		}
		switch strings.ToLower(strings.TrimSpace(p.Thinking)) {
		case "", ThinkingAdaptive, ThinkingDisabled:
		default:
			errs = append(errs, fmt.Sprintf("providers[%d] (%s): thinking must be %q or %q, got %q",
				i, p.Name, ThinkingAdaptive, ThinkingDisabled, p.Thinking))
		}
	}
	for _, name := range c.Failover {
		if !seen[name] {
			errs = append(errs, fmt.Sprintf("failover references unknown provider %q", name))
		}
	}
	if c.Agent.MaxTurns < 1 {
		errs = append(errs, "agent.max_turns must be >= 1")
	}
	if len(errs) > 0 {
		return errors.New(strings.Join(errs, "; "))
	}
	return nil
}

// Dir is the directory the config file was loaded from.
func (c *Config) Dir() string { return c.dir }

// resolve turns a possibly relative path into one rooted at the config directory.
func (c *Config) resolve(p string) string {
	if p == "" || filepath.IsAbs(p) || c.dir == "" {
		return p
	}
	return filepath.Join(c.dir, p)
}

// TokenFilePath is the absolute path of device.token_file, or "" if unset.
func (c *Config) TokenFilePath() string { return c.resolve(c.Device.TokenFile) }

// SQLitePath is the absolute path of storage.sqlite_path.
func (c *Config) SQLitePath() string { return c.resolve(c.Storage.SQLitePath) }

// DeviceToken resolves the phone bearer token from the environment or the token
// file. The token itself is never echoed in errors.
func (c *Config) DeviceToken() (string, error) {
	if c.Device.TokenEnv != "" {
		if v := strings.TrimSpace(os.Getenv(c.Device.TokenEnv)); v != "" {
			return v, nil
		}
	}
	if p := c.TokenFilePath(); p != "" {
		b, err := os.ReadFile(p)
		if err == nil {
			if v := strings.TrimSpace(string(b)); v != "" {
				return v, nil
			}
		} else if !os.IsNotExist(err) {
			return "", fmt.Errorf("config: read device token file: %w", err)
		}
	}
	return "", fmt.Errorf("config: no device token found (env %q unset or empty, token_file %q missing or empty); run: gateway pair",
		c.Device.TokenEnv, c.Device.TokenFile)
}

// WriteDeviceToken persists a freshly paired token to device.token_file,
// restricted to the current user. It never logs the token.
//
// The 0600 mode bits are honoured on Unix. On Windows they are a no-op, so
// restrictTokenFile rewrites the file's DACL to grant the current user alone
// full control with inheritance disabled; a failure there is fatal rather than
// ignored, because silently leaving a world-readable bearer token on disk is
// worse than failing the pairing.
func (c *Config) WriteDeviceToken(token string) error {
	p := c.TokenFilePath()
	if p == "" {
		return errors.New("config: device.token_file is not set; cannot persist the pairing token")
	}
	if dir := filepath.Dir(p); dir != "" {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return fmt.Errorf("config: create token dir: %w", err)
		}
	}
	if err := os.WriteFile(p, []byte(token+"\n"), 0o600); err != nil {
		return fmt.Errorf("config: write token file: %w", err)
	}
	if err := restrictTokenFile(p); err != nil {
		return fmt.Errorf("config: restrict token file permissions: %w", err)
	}
	return nil
}

// TLSFingerprintFilePath is the absolute path used to persist and read the
// pinned TLS certificate fingerprint. device.tls_fingerprint_file is used
// when set; otherwise it defaults to "device-tls-fingerprint" alongside
// device.token_file, which is where "gateway pair" writes a fingerprint the
// phone advertises when tls_fingerprint_file is not explicitly configured.
// Returns "" if neither tls_fingerprint_file nor token_file is set.
func (c *Config) TLSFingerprintFilePath() string {
	if c.Device.TLSFingerprintFile != "" {
		return c.resolve(c.Device.TLSFingerprintFile)
	}
	if tok := c.TokenFilePath(); tok != "" {
		return filepath.Join(filepath.Dir(tok), "device-tls-fingerprint")
	}
	return ""
}

// DeviceTLSFingerprint resolves the pinned TLS certificate fingerprint for a
// https device.base_url. TLSFingerprintFilePath() (an explicit
// tls_fingerprint_file, or its default location alongside the token file)
// wins over the inline device.tls_fingerprint_sha256 key when both resolve to
// a value, mirroring what "gateway pair" persists.
func (c *Config) DeviceTLSFingerprint() (string, error) {
	if p := c.TLSFingerprintFilePath(); p != "" {
		b, err := os.ReadFile(p)
		if err == nil {
			if v := strings.TrimSpace(string(b)); v != "" {
				return v, nil
			}
		} else if !os.IsNotExist(err) {
			return "", fmt.Errorf("config: read device TLS fingerprint file: %w", err)
		}
	}
	if v := strings.TrimSpace(c.Device.TLSFingerprintSHA256); v != "" {
		return v, nil
	}
	return "", fmt.Errorf("config: device.base_url is https but no TLS fingerprint is configured "+
		"(device.tls_fingerprint_sha256 or device.tls_fingerprint_file); run `gateway pair` to record it, "+
		"or read tls_fingerprint_sha256 from the phone's GET %s/health", strings.TrimRight(c.Device.BaseURL, "/"))
}

// WriteDeviceTLSFingerprint persists a fingerprint discovered during pairing
// to TLSFingerprintFilePath(), restricted to the current user the same way
// WriteDeviceToken restricts the bearer token file. It never fails silently:
// a failure to restrict permissions on Windows is fatal, matching
// WriteDeviceToken's reasoning.
func (c *Config) WriteDeviceTLSFingerprint(fingerprint string) error {
	p := c.TLSFingerprintFilePath()
	if p == "" {
		return errors.New("config: device.token_file (or device.tls_fingerprint_file) must be set to persist the TLS fingerprint")
	}
	if dir := filepath.Dir(p); dir != "" {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return fmt.Errorf("config: create TLS fingerprint dir: %w", err)
		}
	}
	if err := os.WriteFile(p, []byte(fingerprint+"\n"), 0o600); err != nil {
		return fmt.Errorf("config: write TLS fingerprint file: %w", err)
	}
	if err := restrictTokenFile(p); err != nil {
		return fmt.Errorf("config: restrict TLS fingerprint file permissions: %w", err)
	}
	return nil
}

// ProviderByName returns the named provider configuration.
func (c *Config) ProviderByName(name string) (Provider, bool) {
	for _, p := range c.Providers {
		if p.Name == name {
			return p, true
		}
	}
	return Provider{}, false
}

// FailoverChain returns the configured providers in failover order.
func (c *Config) FailoverChain() []Provider {
	out := make([]Provider, 0, len(c.Failover))
	for _, n := range c.Failover {
		if p, ok := c.ProviderByName(n); ok {
			out = append(out, p)
		}
	}
	return out
}
