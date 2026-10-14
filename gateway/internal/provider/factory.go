package provider

import (
	"fmt"
	"os"
	"strings"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/config"
)

// FromConfig constructs a provider from one configuration entry. API keys are
// read from the environment variable named by api_key_env and are never logged.
func FromConfig(pc config.Provider) (Provider, error) {
	key := ""
	if pc.APIKeyEnv != "" {
		key = strings.TrimSpace(os.Getenv(pc.APIKeyEnv))
	}
	switch pc.Kind {
	case config.KindAnthropic:
		if key == "" {
			return nil, fmt.Errorf("provider %s: environment variable %s is unset or empty", pc.Name, pc.APIKeyEnv)
		}
		return NewAnthropic(AnthropicOptions{
			Name: pc.Name, Model: pc.Model, APIKey: key, BaseURL: pc.BaseURL,
			MaxTokens: pc.MaxTokens, Thinking: pc.Thinking,
		}), nil
	case config.KindOpenAI:
		// A keyless local OpenAI-compatible server is legitimate, so an empty
		// key is only an error when no base_url override was supplied.
		if key == "" && pc.BaseURL == "" {
			return nil, fmt.Errorf("provider %s: environment variable %s is unset or empty", pc.Name, pc.APIKeyEnv)
		}
		return NewOpenAI(OpenAIOptions{
			Name: pc.Name, Model: pc.Model, APIKey: key, BaseURL: pc.BaseURL, MaxTokens: pc.MaxTokens,
		}), nil
	case config.KindGemini:
		if key == "" {
			return nil, fmt.Errorf("provider %s: environment variable %s is unset or empty", pc.Name, pc.APIKeyEnv)
		}
		return NewGemini(GeminiOptions{
			Name: pc.Name, Model: pc.Model, APIKey: key, BaseURL: pc.BaseURL, MaxTokens: pc.MaxTokens,
		}), nil
	case config.KindFake:
		// The fake provider has no configurable script outside tests; it emits a
		// single terminal message so "gateway run" is usable without credentials.
		return NewFake(FakeOptions{
			Name: pc.Name,
			Script: []ScriptStep{{
				Text: "fake provider configured: no model is attached, so no task was performed.",
			}},
		}), nil
	default:
		return nil, fmt.Errorf("provider %s: unknown kind %q", pc.Name, pc.Kind)
	}
}

// ChainFromConfig builds the failover chain in configured order.
func ChainFromConfig(cfg *config.Config) ([]Provider, error) {
	chain := cfg.FailoverChain()
	if len(chain) == 0 {
		return nil, fmt.Errorf("config: failover chain is empty")
	}
	out := make([]Provider, 0, len(chain))
	for _, pc := range chain {
		p, err := FromConfig(pc)
		if err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, nil
}
