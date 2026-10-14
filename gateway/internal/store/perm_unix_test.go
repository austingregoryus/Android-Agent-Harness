//go:build !windows

package store

import (
	"os"
	"path/filepath"
	"syscall"
	"testing"
)

// TestDatabaseFilesAreOwnerOnly is the M5 regression: the database and its WAL
// sidecars hold SMS text and transcripts, and used to be created with the
// process umask, which under the common 022 leaves them world-readable.
func TestDatabaseFilesAreOwnerOnly(t *testing.T) {
	old := syscall.Umask(0o022)
	defer syscall.Umask(old)

	dir := t.TempDir()
	path := filepath.Join(dir, "gateway.db")
	s, err := Open(path)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer s.Close()
	if err := s.RecordEvent(1, "sub_a", "messaging.sms_received", 1, "dev_1", "",
		map[string]any{"text": "your code is 123456"}); err != nil {
		t.Fatal(err)
	}
	for _, f := range []string{path, path + "-wal", path + "-shm"} {
		info, err := os.Stat(f)
		if err != nil {
			t.Fatalf("stat %s: %v", f, err)
		}
		if mode := info.Mode().Perm(); mode != 0o600 {
			t.Errorf("%s mode = %#o, want 0600", filepath.Base(f), mode)
		}
	}
}

// TestExistingDatabaseIsTightened covers a database created world-readable by
// an older gateway: opening it again restricts it.
func TestExistingDatabaseIsTightened(t *testing.T) {
	path := filepath.Join(t.TempDir(), "gateway.db")
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	s.Close()
	for _, f := range []string{path, path + "-wal", path + "-shm"} {
		if err := os.Chmod(f, 0o644); err != nil && !os.IsNotExist(err) {
			t.Fatal(err)
		}
	}

	s, err = Open(path)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	defer s.Close()
	for _, f := range []string{path, path + "-wal", path + "-shm"} {
		info, err := os.Stat(f)
		if os.IsNotExist(err) {
			continue
		}
		if err != nil {
			t.Fatal(err)
		}
		if mode := info.Mode().Perm(); mode != 0o600 {
			t.Errorf("%s mode = %#o after reopen, want 0600", filepath.Base(f), mode)
		}
	}
}
