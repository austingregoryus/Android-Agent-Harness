//go:build windows

package config

import (
	"fmt"

	"golang.org/x/sys/windows"
)

// restrictTokenFile rewrites the file's discretionary ACL so that only the
// account running the gateway can read it.
//
// Windows ignores the Unix mode bits os.WriteFile is given, so a 0600 token file
// is in practice readable by every account that inherits permissions from the
// parent directory (commonly Users on a default profile). This drops inheritance
// (PROTECTED_DACL_SECURITY_INFORMATION) and installs a single non-inheritable
// ACE granting the current user full control, which is the closest equivalent to
// 0600 the platform offers.
func restrictTokenFile(path string) error {
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
