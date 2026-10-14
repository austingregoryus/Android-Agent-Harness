//go:build !windows

package store

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
)

// restrictFile makes path readable and writable by its owner only. A missing
// file is not an error: the -wal and -shm sidecars exist only once SQLite has
// opened the database in WAL mode.
func restrictFile(path string) error {
	if err := os.Chmod(path, 0o600); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return fmt.Errorf("chmod %s: %w", path, err)
	}
	return nil
}
