//go:build windows

package mcpserver

import (
	"os/exec"
	"syscall"

	"golang.org/x/sys/windows"
)

// configureGracefulCancel arranges for cmd's cancellation (the converse_call timeout elapsing, or an MCP
// notifications/cancelled) to ask the driver to stop instead of going straight to TerminateProcess, which is
// what exec.Cmd's default Cancel does on Windows. converse.py hangs up a live call in a finally block, but
// TerminateProcess gives it no chance to run that — the process is gone instantly, and the call stays up with
// nobody driving it (see the cancel-path comment in converseCall).
//
// CREATE_NEW_PROCESS_GROUP puts the child (and anything it spawns) in its own process group, which
// GenerateConsoleCtrlEvent then needs in order to target just that group rather than the gateway's own.
// cmd.Cancel sends CTRL_BREAK_EVENT to it; converse.py's default console-control handling treats that like an
// interrupt and unwinds through its finally block, hanging up the call on the way out.
//
// If GenerateConsoleCtrlEvent itself fails — the gateway may have no console to deliver the event through at
// all, e.g. running as a service or spawned without one — this falls back to a hard kill so the process at
// least doesn't leak; converseCall's shouldGatewayHangUp/endCallBackstop is the actual safety net for the call
// itself in that case, not this function. Either way, cmd.WaitDelay (set by the caller) bounds how long Cmd
// waits for the graceful path before it falls back to killing the process outright.
func configureGracefulCancel(cmd *exec.Cmd) {
	if cmd.SysProcAttr == nil {
		cmd.SysProcAttr = &syscall.SysProcAttr{}
	}
	cmd.SysProcAttr.CreationFlags |= windows.CREATE_NEW_PROCESS_GROUP

	cmd.Cancel = func() error {
		pid := uint32(cmd.Process.Pid)
		if err := windows.GenerateConsoleCtrlEvent(windows.CTRL_BREAK_EVENT, pid); err != nil {
			return cmd.Process.Kill()
		}
		return nil
	}
}
