package config

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

const thinkingConfigTemplate = `device:
  base_url: http://127.0.0.1:8765
  token_env: TOK
providers:
  - {name: claude, kind: anthropic, api_key_env: K%s}
`

func TestParseThinking(t *testing.T) {
	tests := []struct {
		name    string
		extra   string
		want    string
		wantErr string
	}{
		{"absent", "", "", ""},
		{"adaptive", ", thinking: adaptive", ThinkingAdaptive, ""},
		{"disabled", ", thinking: disabled", ThinkingDisabled, ""},
		{"mixed case", ", thinking: Disabled", "Disabled", ""},
		{"invalid", ", thinking: hard", "", "thinking must be"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			raw := []byte(strings.Replace(thinkingConfigTemplate, "%s", tc.extra, 1))
			c, err := Parse(raw)
			if tc.wantErr != "" {
				if err == nil || !strings.Contains(err.Error(), tc.wantErr) {
					t.Fatalf("err = %v, want one containing %q", err, tc.wantErr)
				}
				return
			}
			if err != nil {
				t.Fatalf("Parse: %v", err)
			}
			if c.Providers[0].Thinking != tc.want {
				t.Errorf("thinking = %q, want %q", c.Providers[0].Thinking, tc.want)
			}
		})
	}
}

// TestWriteDeviceTokenRestrictsPermissions checks that the token file is not
// left readable by other accounts. On Unix that is the 0600 mode bits; on
// Windows the mode bits are a no-op, so the file's DACL is rewritten instead and
// the check is that the write reports success rather than silently skipping it.
func TestWriteDeviceTokenRestrictsPermissions(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "nested", "device-token")
	c := &Config{Device: Device{BaseURL: "http://x", TokenFile: path}}

	if err := c.WriteDeviceToken("s3cret-token"); err != nil {
		t.Fatalf("WriteDeviceToken: %v", err)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read back: %v", err)
	}
	if strings.TrimSpace(string(raw)) != "s3cret-token" {
		t.Errorf("token file contents = %q", raw)
	}

	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS != "windows" {
		if perm := info.Mode().Perm(); perm != 0o600 {
			t.Errorf("mode = %o, want 600", perm)
		}
	}

	// Overwriting an existing (loosened) file must re-assert the restriction.
	if err := os.Chmod(path, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := c.WriteDeviceToken("second-token"); err != nil {
		t.Fatalf("second WriteDeviceToken: %v", err)
	}
	if runtime.GOOS != "windows" {
		info, err = os.Stat(path)
		if err != nil {
			t.Fatal(err)
		}
		if perm := info.Mode().Perm(); perm != 0o600 {
			t.Errorf("mode after rewrite = %o, want 600", perm)
		}
	}
}
