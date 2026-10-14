//go:build windows

package store

import (
	"errors"
	"fmt"
	"io/fs"
	"os"

	"golang.org/x/sys/windows"
)

// restrictFile rewrites the file's discretionary ACL so that only the account
// running the gateway can open it, the same restriction internal/config applies
// to the phone token file: inheritance is dropped and a single non-inheritable
// ACE grants the current user full control. Windows ignores Unix mode bits, so
// without this the database inherits the parent directory's ACL. A missing file
// is not an error: the -wal and -shm sidecars exist only once SQLite has opened
// the database in WAL mode.
func restrictFile(path string) error {
	if _, err := os.Stat(path); errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	var token windows.Token
	if err := windows.OpenProcessToken(windows.CurrentProcess(), windows.TOKEN_QUERY, &token); err != nil {
		return fmt.Errorf("open process token: %w", err)
	}
	defer token.Close()

	user, err := token.GetTokenUser()
	if err != nil {
		return fmt.Errorf("read token user: %w", err)
	}

	acl, err := windows.ACLFromEntries([]windows.EXPLICIT_ACCESS{{
		AccessPermissions: windows.GENERIC_ALL,
		AccessMode:        windows.GRANT_ACCESS,
		Inheritance:       windows.NO_INHERITANCE,
		Trustee: windows.TRUSTEE{
			TrusteeForm:  windows.TRUSTEE_IS_SID,
			TrusteeType:  windows.TRUSTEE_IS_USER,
			TrusteeValue: windows.TrusteeValueFromSID(user.User.Sid),
		},
	}}, nil)
	if err != nil {
		return fmt.Errorf("build ACL: %w", err)
	}

	err = windows.SetNamedSecurityInfo(
		path,
		windows.SE_FILE_OBJECT,
		windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION,
		nil, nil, acl, nil,
	)
	if err != nil {
		return fmt.Errorf("set DACL on %s: %w", path, err)
	}
	return nil
}
