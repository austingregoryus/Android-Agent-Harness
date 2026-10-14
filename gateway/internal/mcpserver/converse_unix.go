//go:build !windows

package mcpserver

import "os/exec"

// configureGracefulCancel is a no-op on non-Windows platforms. exec.Cmd's default Cancel (Process.Kill,
// i.e. SIGKILL) is used; cmd.WaitDelay, set by the caller, still bounds the shutdown before that fallback
// fires. converseCall's shouldGatewayHangUp/endCallBackstop backstop is what actually ends the call in this
// case, since the driver gets no chance to hang up on its own.
func configureGracefulCancel(cmd *exec.Cmd) {}
