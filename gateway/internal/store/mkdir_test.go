package store

import (
	"path/filepath"
	"testing"
)

// TestOpenCreatesParentDirectory covers the P2 item: storage.sqlite_path
// resolves against the config file's directory, so it routinely names a
// subdirectory that does not exist yet. SQLite creates the file but not the path
// to it, so Open used to fail with an opaque driver error.
func TestOpenCreatesParentDirectory(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state", "nested", "gateway.db")
	s, err := Open(path)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer s.Close()
	if err := s.CreateTask("task_1", "corr_1", "hello"); err != nil {
		t.Fatalf("the database is not usable: %v", err)
	}
}
