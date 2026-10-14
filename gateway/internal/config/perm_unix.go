//go:build !windows

package config

import (
	"fmt"
	"os"
)

// restrictTokenFile re-asserts owner-only permissions on the token file.
//
// os.WriteFile already applies 0600 on creation, but an existing file keeps the
// mode it had, so a token written over a file someone had loosened would inherit
// those bits. Chmod makes the guarantee unconditional.
func restrictTokenFile(path string) error {
	if err := os.Chmod(path, 0o600); err != nil {
		return fmt.Errorf("chmod %s: %w", path, err)
	}
	return nil
}
