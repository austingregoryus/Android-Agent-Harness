package mcpserver

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/agent"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/device"
)

// ToolConverseCall places a phone call and holds a spoken conversation on it, driven by a brief.
//
// The conversation loop (turn-taking, barge-in, speech recognition on the LAN, the Joi voice, the brain)
// lives in tools/converse.py; this tool runs it for the agent. The brief says what the call is FOR — book,
// ask, confirm, chase, cancel, reschedule — and the result reports what it achieved, so the agent gets an
// answer (`outcome`, `facts`) rather than a transcript to read. The transcript comes too, as the evidence.
const ToolConverseCall = "gateway.converse_call"

// defaultConverseTimeout bounds one conversation; a call this long is a runaway, not an errand.
const defaultConverseTimeout = 5 * time.Minute

// maxConverseTimeout caps the caller's timeout_s.
const maxConverseTimeout = 15 * time.Minute

// converseCancelWaitDelay bounds how long we wait, after asking the driver to stop (context cancelled, or the
// timeout above elapsed), for it to actually exit before Cmd falls back to a hard kill. See
// configureGracefulCancel (converse_windows.go / converse_unix.go).
const converseCancelWaitDelay = 5 * time.Second

// endCallBackstopTimeout bounds the gateway's own telephony.end_call, issued when the driver process is gone
// and nothing in its output showed the call ending on its own. It runs on a detached context (the request's
// own ctx is by then cancelled or timed out), so this is the only timeout protecting it.
const endCallBackstopTimeout = 10 * time.Second

var (
	reSummary    = regexp.MustCompile(`(?m)^\[summary\] (.+)$`)
	reTranscript = regexp.MustCompile(`(?m)^\[summary\] transcript written to (.+)$`)
	reEnded      = regexp.MustCompile(`(?m)^\[call ended: (.+)\]$`)
	reOutcome    = regexp.MustCompile(`(?m)^\[outcome\] (\{.*\})$`)
	// reDialing matches converse.py's one-time announcement of the session id it got back from placing the
	// call: "[call] dialing <number> -> <session_id>". Its presence is how the gateway tells "never got
	// through" apart from "was live and something went wrong after that".
	reDialing = regexp.MustCompile(`(?m)^\[call\] dialing \S+ -> (\S+)$`)
)

func converseToolDef() map[string]any {
	return map[string]any{
		"name": ToolConverseCall,
		"description": "Place a phone call from the phone and hold a real spoken conversation on it to carry out a " +
			"task: book or reschedule something, ask a question and get the answer, confirm or cancel, chase a status, " +
			"leave a message. `brief` is the whole instruction — who you are calling as, what to accomplish, which " +
			"facts you may give out, and what you are allowed to agree to. Blocks until the call ends. Returns " +
			"`outcome` (done | partial | failed | no_answer | unclear), a one-sentence `summary`, a `facts` map of " +
			"everything learned on the call, `they_promised`, `follow_up`, plus the full transcript and timing. " +
			"IMPORTANT: `we_promised` lists what the call told the other person it would do - a real person is now " +
			"waiting on each of those, so the errand is not finished until you have discharged them (a text, a " +
			"second call) or told the user you cannot. While anything is outstanding the result will never say " +
			"`done`: it comes back `partial` with `outstanding` set, even if the call itself went perfectly. The " +
			"call is placed without asking by default; if the owner has switched on \"Ask me first\" on the phone, it waits for their approval tap.",
		"inputSchema": map[string]any{
			"type": "object",
			"properties": map[string]any{
				"target": map[string]any{"type": "string", "description": "E.164 number to call, e.g. +14695551234"},
				"brief": map[string]any{"type": "string", "description": "The task, in plain prose: who you are calling as, " +
					"what you are trying to get done or find out, the facts you may offer (name, dates, an order or account " +
					"number, a callback number), and what you may accept without checking back. How to hold a phone call — " +
					"one sentence per turn, numbers as words, when to hang up — is added automatically; do not restate it."},
				"brain": map[string]any{"type": "string", "description": "Model preset: luna (default, hosted, ~1.5s to the first sentence), ornith (the LAN router, ~0.4s and free, needs no API key), terra, sonnet5, haiku",
					"enum": []any{"luna", "ornith", "terra", "sonnet5", "haiku"}},
				"tts": map[string]any{"type": "string", "description": "Voice engine: chatterbox (Joi, default) or kokoro-server",
					"enum": []any{"chatterbox", "kokoro-server"}},
				"timeout_s": map[string]any{"type": "integer", "description": "Give up after this many seconds (default 300, max 900)", "minimum": 30, "maximum": 900},
			},
			"required": []any{"target", "brief"},
		},
	}
}

// converseCall runs tools/converse.py for one call and reports what happened.
func (s *Server) converseCall(ctx context.Context, args map[string]any) (any, *rpcErrorObject) {
	if s.opts.ConverseScript == "" {
		return errorResult("converse.unavailable", "the gateway has no converse_script configured (agent.converse_script in config.yaml)"), nil
	}
	target, _ := args["target"].(string)
	brief, _ := args["brief"].(string)
	if strings.TrimSpace(target) == "" || strings.TrimSpace(brief) == "" {
		return nil, &rpcErrorObject{Code: codeInvalidParams, Message: "target and brief are required"}
	}
	// Only one live call per phone at a time: a second concurrent converse_call would fight the first over
	// the same audio route and call session, so it is refused outright rather than allowed to race.
	if !s.converseBusy.CompareAndSwap(false, true) {
		return errorResult("converse.busy", "a call is already in progress on this phone"), nil
	}
	defer s.converseBusy.Store(false)

	timeout := defaultConverseTimeout
	if v := toInt64(args["timeout_s"]); v > 0 {
		timeout = time.Duration(v) * time.Second
		if timeout > maxConverseTimeout {
			timeout = maxConverseTimeout
		}
	}
	python := s.opts.Python
	if python == "" {
		python = "python"
	}
	brain, _ := args["brain"].(string)
	tts, _ := args["tts"].(string)

	// The brief can carry account numbers, callback numbers and other specifics the argv would otherwise
	// expose to anything that can list processes on this machine. It is written to a private temp file and
	// handed to converse.py as --system-file instead of appearing on the command line at all.
	briefFile, err := writeBriefFile(brief)
	if err != nil {
		return errorResult("converse.brief_file_failed", "could not write the call brief to a temp file: "+err.Error()), nil
	}
	defer os.Remove(briefFile)

	argv := converseArgv(s.opts.ConverseScript, s.opts.ConverseDevice, target, briefFile, brain, tts)

	runCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	cmd := exec.CommandContext(runCtx, python, argv...)
	cmd.Dir = filepath.Dir(s.opts.ConverseScript)
	// converse.py's --token/--base/--pin default to SYMBIOSIS_TOKEN/SYMBIOSIS_URL/SYMBIOSIS_TLS_PIN, and
	// --token beats --token-file. Inheriting those from the gateway's own environment unfiltered would let
	// them override the --base/--token-file/--pin already in ConverseDevice, sending this call — and this
	// phone's token — to whatever phone those variables name. ConverseEnv carries SYMBIOSIS_TOKEN back in
	// only when main.go resolved one itself (no token_file configured).
	cmd.Env = converseEnv(os.Environ(), s.opts.ConverseEnv)
	// On cancellation (the timeout above, or an MCP notifications/cancelled), ask the driver to stop instead
	// of going straight to a hard kill: converse.py hangs up the call in a finally block, but only on a
	// graceful exit — a hard kill (the exec package's default Cancel) skips it entirely and leaves the call
	// live with nobody driving it. configureGracefulCancel wires up the OS-specific graceful-stop mechanism
	// where one exists (Windows: CTRL_BREAK) and is a no-op elsewhere, where Cmd's default kill-on-cancel
	// stands; either way converseCancelWaitDelay bounds how long we wait for it before falling back to a hard
	// kill, and the exitedAbnormally check below is the real backstop regardless of how the exit happened.
	configureGracefulCancel(cmd)
	cmd.WaitDelay = converseCancelWaitDelay
	var out bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &out
	started := time.Now()
	err = cmd.Run()
	log := out.String()

	result := map[string]any{
		"target":        target,
		"duration_s":    int(time.Since(started).Seconds()),
		"exit_code":     0,
		"outcome":       "unclear",
		"summary":       "",
		"facts":         map[string]any{},
		"we_promised":   []any{},
		"they_promised": []any{},
		"follow_up":     nil,
		"timing":        "",
		"call_ended":    "",
		"agent_hung_up": strings.Contains(log, "[hanging up:"),
		"log_tail":      tail(log, 40),
	}
	if m := reSummary.FindStringSubmatch(log); m != nil {
		result["timing"] = m[1]
	}
	// What the call achieved, as the driver's own post-call pass reported it. The keys are lifted to the top
	// level so the agent reads an answer, not a nested blob; anything missing keeps the default above.
	if m := reOutcome.FindStringSubmatch(log); m != nil {
		var o map[string]any
		if json.Unmarshal([]byte(m[1]), &o) == nil {
			for _, k := range []string{"outcome", "summary", "facts", "we_promised", "they_promised", "follow_up"} {
				if v, ok := o[k]; ok {
					result[k] = v
				}
			}
		}
	}
	applyOwedGuard(result)
	if m := reEnded.FindStringSubmatch(log); m != nil {
		result["call_ended"] = m[1]
	}
	if m := reTranscript.FindStringSubmatch(log); m != nil {
		// The path comes out of unstructured child output that also carries what the far party said, so it is
		// only trusted if it is exactly where the driver writes transcripts: <repo>/logs/call-YYYYMMDD-HHMMSS.txt.
		if p, ok := transcriptPath(s.opts.ConverseScript, strings.TrimSpace(m[1])); ok {
			if b, rerr := os.ReadFile(p); rerr == nil {
				result["transcript"] = string(b)
				result["transcript_path"] = p
			}
		}
	}
	// If the driver process is gone (whatever the reason) and nothing in its output shows the call ending on
	// its own, the gateway hangs it up itself rather than leaving a live call with nobody driving it. This is
	// the real backstop: configureGracefulCancel's CTRL_BREAK is only a best effort, and on non-Windows, or
	// when the gateway has no console to deliver it through, the driver is hard-killed with no chance to hang
	// up on its own at all.
	if sid, ok := shouldGatewayHangUp(log, err != nil); ok {
		result["gateway_ended_call"] = s.endCallBackstop(sid)
	}

	if err != nil {
		var exitErr *exec.ExitError
		switch {
		case errors.Is(runCtx.Err(), context.DeadlineExceeded):
			result["exit_code"] = -1
			detail := fmt.Sprintf("the call did not end within %s; the driver was stopped", timeout)
			return errorResultWith("converse.timeout", noteGatewayHangup(detail, result), result), nil
		case errors.As(err, &exitErr):
			result["exit_code"] = exitErr.ExitCode()
			return errorResultWith("converse.failed", noteGatewayHangup(strings.TrimSpace(lastLine(log)), result), result), nil
		default:
			return errorResultWith("converse.failed", noteGatewayHangup(err.Error(), result), result), nil
		}
	}
	return structuredResult(result), nil
}

// noteGatewayHangup appends a note to detail when the gateway itself ended the call after the driver exited
// abnormally (see shouldGatewayHangUp) — so an agent reading the error also learns the call is actually down,
// not just that the driver process is gone.
func noteGatewayHangup(detail string, result map[string]any) string {
	if ended, _ := result["gateway_ended_call"].(bool); ended {
		return detail + " (the driver did not hang up on exit, so the gateway ended the call itself)"
	}
	return detail
}

// endCallBackstop calls the phone's telephony.end_call for sessionID directly, bypassing the driver that was
// supposed to do it. It runs on its own short-lived, detached context: by the time this is called the
// request's own ctx is cancelled or its deadline has passed, so reusing it would fail immediately. The bool it
// returns says only whether the call was ended here, not why a failure happened — a failure most often means
// the call had already ended by the time we got to it (the phone errors with an unknown-session code), which
// is the common, unremarkable case, not a sign something is wrong.
func (s *Server) endCallBackstop(sessionID string) bool {
	endCtx, cancel := context.WithTimeout(context.Background(), endCallBackstopTimeout)
	defer cancel()
	corrID := agent.NewCorrelationID(s.opts.CorrelationPrefix)
	outcome, err := s.opts.Device.CallTool(endCtx, "telephony.end_call", map[string]any{"session_id": sessionID},
		device.CallToolOptions{CorrelationID: corrID})
	return err == nil && outcome.OK
}

// converseArgv builds the argv for converse.py: the device args (--base/--token-file/--pin, naming this
// gateway's phone) must precede --dial/--system-file so a caller reading the command line sees which phone is
// being used before what it's being asked to do. systemFile is the path of a temp file holding the brief
// (see writeBriefFile) — the brief itself never appears in argv, since it can carry account and callback
// numbers and argv is visible in process listings.
func converseArgv(script string, device []string, target, systemFile, brain, tts string) []string {
	argv := append([]string{"-u", script}, device...)
	argv = append(argv, "--dial", target, "--system-file", systemFile)
	if brain != "" {
		argv = append(argv, "--brain", brain)
	}
	if tts != "" {
		argv = append(argv, "--tts", tts)
	}
	return argv
}

// writeBriefFile writes brief to a new, private temp file (mode 0600, via os.CreateTemp) and returns its path.
// The caller is responsible for removing it once the driver process has exited.
func writeBriefFile(brief string) (string, error) {
	f, err := os.CreateTemp("", "android-agent-harness-brief-*.txt")
	if err != nil {
		return "", err
	}
	defer f.Close()
	if _, err := f.WriteString(brief); err != nil {
		name := f.Name()
		os.Remove(name)
		return "", err
	}
	return f.Name(), nil
}

// converseEnvStrip are the variables converse.py defaults --token/--base/--pin from. They must never reach
// the child from the gateway's own environment — see the call site in converseCall.
var converseEnvStrip = map[string]bool{
	"SYMBIOSIS_TOKEN":   true,
	"SYMBIOSIS_URL":     true,
	"SYMBIOSIS_TLS_PIN": true,
}

// converseEnv strips converseEnvStrip out of environ and appends extra, so extra is the only way any of
// those variables can reach converse.py.
func converseEnv(environ []string, extra []string) []string {
	env := make([]string, 0, len(environ)+len(extra))
	for _, kv := range environ {
		// Upper-cased: Windows environment names are case-insensitive, so "Symbiosis_Token" is the same variable.
		if k, _, ok := strings.Cut(kv, "="); ok && converseEnvStrip[strings.ToUpper(k)] {
			continue
		}
		env = append(env, kv)
	}
	return append(env, extra...)
}

// applyOwedGuard keeps `outcome` honest: a call that promised something is not a finished errand, whatever
// the summarising model graded it. Someone is waiting on each entry in we_promised, and `done` would tell
// the agent to stop while there is work left. Deciding it here rather than trusting the model is deliberate
// — this is the one field agents branch on, and the cost of getting it wrong is a person who was told "I'll
// get back to you" and never heard again. `outstanding` (and, for a downgrade, a note saying so) means the
// agent sees why rather than a silent change.
func applyOwedGuard(result map[string]any) {
	owed, ok := result["we_promised"].([]any)
	if !ok || len(owed) == 0 {
		return
	}
	if result["outcome"] == "done" {
		result["outcome"] = "partial"
		result["downgraded_from_done"] = "the call promised to do something; it is not finished until that is discharged"
	}
	result["outstanding"] = len(owed)
}

// dialedSessionID returns the session id from the driver's one-time announcement of it — "[call] dialing
// <number> -> <session_id>" — if the call was ever placed. Its absence means we never got as far as dialing,
// so there is nothing for the gateway to hang up.
func dialedSessionID(log string) (string, bool) {
	m := reDialing.FindStringSubmatch(log)
	if m == nil {
		return "", false
	}
	return m[1], true
}

// callLooksEnded reports whether the log shows the call over: the phone's call.ended event reached the driver
// ("[call ended: ...]"). The driver's own "[hanging up: ...]" line does not count - end_call can still fail after
// it, or the process can be killed between the line and the request - and a redundant end_call on a call that
// already ended is harmless.
func callLooksEnded(log string) bool {
	return reEnded.MatchString(log)
}

// shouldGatewayHangUp decides whether the gateway must call telephony.end_call itself once the driver process
// has exited: only when the process actually exited abnormally (killed, non-zero, or timed out), a call was
// dialed, and nothing in the log shows that call ending on its own. Leaving a live call with nobody driving it
// is the failure this guards against; calling telephony.end_call on a call that has already ended is the safe
// side to err on.
func shouldGatewayHangUp(log string, exitedAbnormally bool) (sessionID string, ok bool) {
	if !exitedAbnormally {
		return "", false
	}
	sid, dialed := dialedSessionID(log)
	if !dialed || callLooksEnded(log) {
		return "", false
	}
	return sid, true
}

// reTranscriptName is the only file name the driver ever writes a transcript under.
var reTranscriptName = regexp.MustCompile(`^call-\d{8}-\d{6}\.txt$`)

// transcriptPath accepts a path only if it resolves to a transcript file inside the driver's logs directory
// (the `logs/` sibling of the `tools/` directory converse.py lives in). Anything else — another directory, a
// symlink out, an odd name — is ignored rather than read.
func transcriptPath(script, candidate string) (string, bool) {
	if script == "" || candidate == "" {
		return "", false
	}
	logsDir, err := filepath.EvalSymlinks(filepath.Join(filepath.Dir(filepath.Dir(script)), "logs"))
	if err != nil {
		return "", false
	}
	real, err := filepath.EvalSymlinks(candidate)
	if err != nil {
		return "", false
	}
	rel, err := filepath.Rel(logsDir, real)
	if err != nil || rel != filepath.Base(rel) || !reTranscriptName.MatchString(rel) {
		return "", false
	}
	return real, true
}

// errorResultWith is errorResult plus the partial outcome, so a failed call still hands back its transcript.
func errorResultWith(code, detail string, extra map[string]any) map[string]any {
	res := errorResult(code, detail)
	structured := res["structuredContent"].(map[string]any)
	for k, v := range extra {
		structured[k] = v
	}
	return res
}

func tail(s string, n int) string {
	lines := strings.Split(strings.TrimRight(s, "\n"), "\n")
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return strings.Join(lines, "\n")
}

func lastLine(s string) string {
	lines := strings.Split(strings.TrimRight(s, "\n"), "\n")
	for i := len(lines) - 1; i >= 0; i-- {
		if strings.TrimSpace(lines[i]) != "" {
			return lines[i]
		}
	}
	return ""
}
