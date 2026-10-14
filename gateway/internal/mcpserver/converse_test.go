package mcpserver

import (
	"context"
	"os"
	"reflect"
	"runtime"
	"strings"
	"testing"
)

// TestOutcomeNeverDoneWhileOwed covers the rule that a call which promised something is not a finished
// errand. `outcome` is the one field a calling agent branches on, and the cost of getting it wrong is a real
// person who was told "I'll get back to you" and never heard again — so the downgrade happens here rather
// than being left to the summarising model's judgement.
func TestOutcomeNeverDoneWhileOwed(t *testing.T) {
	for _, tc := range []struct {
		name       string
		outcome    string
		owed       []any
		wantOutURL string
		wantOuts   int
		wantNote   bool
	}{
		{name: "done with a promise is downgraded", outcome: "done",
			owed: []any{"call her back about the location"}, wantOutURL: "partial", wantOuts: 1, wantNote: true},
		{name: "done with nothing owed stays done", outcome: "done",
			owed: nil, wantOutURL: "done", wantOuts: 0},
		{name: "partial with a promise keeps outstanding", outcome: "partial",
			owed: []any{"a", "b"}, wantOutURL: "partial", wantOuts: 2},
		{name: "failed with a promise is still failed", outcome: "failed",
			owed: []any{"send them the form"}, wantOutURL: "failed", wantOuts: 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			result := map[string]any{"outcome": tc.outcome, "we_promised": tc.owed}
			applyOwedGuard(result)

			if got := result["outcome"]; got != tc.wantOutURL {
				t.Errorf("outcome = %v, want %v", got, tc.wantOutURL)
			}
			outs, _ := result["outstanding"].(int)
			if outs != tc.wantOuts {
				t.Errorf("outstanding = %d, want %d", outs, tc.wantOuts)
			}
			_, noted := result["downgraded_from_done"]
			if noted != tc.wantNote {
				t.Errorf("downgraded_from_done present = %v, want %v", noted, tc.wantNote)
			}
		})
	}
}

// TestOwedGuardIgnoresAWrongType makes sure a summariser that returns the wrong shape cannot break the call
// result: a non-list we_promised is simply not an obligation.
func TestOwedGuardIgnoresAWrongType(t *testing.T) {
	result := map[string]any{"outcome": "done", "we_promised": "not a list"}
	applyOwedGuard(result)
	if result["outcome"] != "done" {
		t.Errorf("outcome = %v, want done", result["outcome"])
	}
	if _, ok := result["outstanding"]; ok {
		t.Error("outstanding must not be set for a malformed we_promised")
	}
}

// TestConverseArgvDeviceArgsPrecedeCallArgs covers the ordering a caller reading the command line relies
// on: which phone is being used (ConverseDevice) must appear before what it's being asked to do (--dial,
// --system-file), and the optional --brain/--tts only appear when set.
func TestConverseArgvDeviceArgsPrecedeCallArgs(t *testing.T) {
	got := converseArgv("/opt/converse.py", []string{"--base", "https://phone", "--token-file", "/secrets/tok"},
		"+14695551234", "/tmp/brief123.txt", "luna", "chatterbox")
	want := []string{
		"-u", "/opt/converse.py",
		"--base", "https://phone", "--token-file", "/secrets/tok",
		"--dial", "+14695551234", "--system-file", "/tmp/brief123.txt",
		"--brain", "luna", "--tts", "chatterbox",
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("converseArgv = %v, want %v", got, want)
	}

	if got := converseArgv("/opt/converse.py", nil, "+1", "/tmp/brief.txt", "", ""); len(got) != 6 {
		t.Errorf("converseArgv with no brain/tts = %v, want 6 elements (no --brain/--tts)", got)
	}
}

// TestConverseArgvNeverCarriesTheBriefText covers the fix for the brief being visible in argv (and so in
// process listings): converseArgv takes the path of a temp file, not the brief text, and --system-file (not
// --system) is what actually reaches converse.py. A brief containing account and callback numbers must never
// show up anywhere in the returned argv, only its file's path.
func TestConverseArgvNeverCarriesTheBriefText(t *testing.T) {
	brief := "You are calling as Alex. Account number 55501234, callback +14695559876 if they ask."
	got := converseArgv("/opt/converse.py", nil, "+14695551234", "/tmp/android-agent-harness-brief-xyz.txt", "", "")

	for _, a := range got {
		if strings.Contains(a, "55501234") || strings.Contains(a, "+14695559876") || a == brief {
			t.Fatalf("converseArgv leaked the brief into argv: %v", got)
		}
	}
	if !reflect.DeepEqual(got, []string{"-u", "/opt/converse.py", "--dial", "+14695551234",
		"--system-file", "/tmp/android-agent-harness-brief-xyz.txt"}) {
		t.Errorf("converseArgv = %v", got)
	}

	// --system (the old, argv-visible flag) must be gone entirely.
	for _, a := range got {
		if a == "--system" {
			t.Error("converseArgv still passes --system; the brief must go through --system-file only")
		}
	}
}

// TestWriteBriefFileIsPrivateAndRemovable covers the temp-file side of the same fix: the file
// converseCall hands to --system-file must actually contain the brief, be owner-only (0600) on platforms
// that support Unix permission bits, and be cleanly removable by the caller afterwards.
func TestWriteBriefFileIsPrivateAndRemovable(t *testing.T) {
	brief := "call and confirm the appointment"
	path, err := writeBriefFile(brief)
	if err != nil {
		t.Fatalf("writeBriefFile: %v", err)
	}
	defer os.Remove(path)

	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("reading brief file: %v", err)
	}
	if string(got) != brief {
		t.Errorf("brief file content = %q, want %q", got, brief)
	}

	if info, err := os.Stat(path); err == nil {
		if runtime.GOOS != "windows" && info.Mode().Perm() != 0o600 {
			t.Errorf("brief file mode = %v, want 0600", info.Mode().Perm())
		}
	}

	if err := os.Remove(path); err != nil {
		t.Errorf("removing brief file: %v", err)
	}
}

// TestDialedSessionIDParse covers pulling the session id out of the driver's one-time announcement line, the
// only place converse.py reports it before anything else can go wrong.
func TestDialedSessionIDParse(t *testing.T) {
	for _, tc := range []struct {
		name    string
		log     string
		wantSID string
		wantOK  bool
	}{
		{name: "normal dial line", log: "[call] dialing +14695551234 -> sess-abc123\nother output\n",
			wantSID: "sess-abc123", wantOK: true},
		{name: "dial line is not the first line", log: "[converse] starting\n[call] dialing +1 -> s1\nmore\n",
			wantSID: "s1", wantOK: true},
		{name: "no dial line at all", log: "[converse] starting\nsome error before dialing\n",
			wantSID: "", wantOK: false},
		{name: "empty log", log: "", wantSID: "", wantOK: false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			sid, ok := dialedSessionID(tc.log)
			if ok != tc.wantOK || sid != tc.wantSID {
				t.Errorf("dialedSessionID(%q) = (%q, %v), want (%q, %v)", tc.log, sid, ok, tc.wantSID, tc.wantOK)
			}
		})
	}
}

// TestShouldGatewayHangUp covers the decision that drives converseCall's backstop: the gateway must end the
// call itself only when the driver process actually exited abnormally, a call was in fact dialed, and nothing
// in the log shows that call ending on its own (either the confirming event, or the driver's own "[hanging
// up: ...]" line, which calls telephony.end_call synchronously right before the process might get killed).
func TestShouldGatewayHangUp(t *testing.T) {
	dialedLog := "[call] dialing +14695551234 -> sess-1\n[converse] connected\n"
	for _, tc := range []struct {
		name             string
		log              string
		exitedAbnormally bool
		wantSID          string
		wantOK           bool
	}{
		{name: "killed mid-call with no sign of ending must hang up",
			log: dialedLog, exitedAbnormally: true, wantSID: "sess-1", wantOK: true},
		{name: "clean exit must not trigger the backstop even with no sign of ending",
			log: dialedLog, exitedAbnormally: false, wantOK: false},
		{name: "call.ended event already seen means no backstop needed",
			log: dialedLog + "[call ended: done after 12000ms]\n", exitedAbnormally: true, wantOK: false},
		{name: "driver's own hang-up line is not proof: killed before end_call landed",
			log: dialedLog + "[hanging up: 30s of silence]\n", exitedAbnormally: true, wantSID: "sess-1", wantOK: true},
		{name: "never got as far as dialing: nothing to hang up",
			log: "[converse] could not place the call: busy\n", exitedAbnormally: true, wantOK: false},
		{name: "never dialed and exited cleanly",
			log: "[converse] no target given\n", exitedAbnormally: false, wantOK: false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			sid, ok := shouldGatewayHangUp(tc.log, tc.exitedAbnormally)
			if ok != tc.wantOK {
				t.Fatalf("shouldGatewayHangUp ok = %v, want %v", ok, tc.wantOK)
			}
			if ok && sid != tc.wantSID {
				t.Errorf("shouldGatewayHangUp sessionID = %q, want %q", sid, tc.wantSID)
			}
		})
	}
}

// TestConverseCallRefusesWhenBusy covers the concurrency guard: a second gateway.converse_call while one is
// already running must be refused immediately with a clear, machine-checkable error, and must not touch the
// device client at all (Device is left nil here — touching it would panic and fail the test).
func TestConverseCallRefusesWhenBusy(t *testing.T) {
	s := &Server{opts: Options{ConverseScript: "/opt/converse.py"}}
	s.converseBusy.Store(true)

	result, rpcErr := s.converseCall(context.Background(),
		map[string]any{"target": "+14695551234", "brief": "call and ask about the order"})
	if rpcErr != nil {
		t.Fatalf("unexpected rpc-level error: %+v", rpcErr)
	}
	m, ok := result.(map[string]any)
	if !ok {
		t.Fatalf("result = %#v (%T), want map[string]any", result, result)
	}
	if isErr, _ := m["isError"].(bool); !isErr {
		t.Error("isError = false, want true for a busy refusal")
	}
	structured, _ := m["structuredContent"].(map[string]any)
	if structured["error"] != "converse.busy" {
		t.Errorf("structuredContent.error = %v, want converse.busy", structured["error"])
	}
	if detail, _ := structured["detail"].(string); !strings.Contains(detail, "already in progress") {
		t.Errorf("structuredContent.detail = %q, want it to say a call is already in progress", detail)
	}

	// The guard must not have been left held: releasing it and calling again with a bad request (no
	// target/brief) should reach past the busy check to the normal validation error instead.
	s.converseBusy.Store(false)
	_, rpcErr = s.converseCall(context.Background(), map[string]any{})
	if rpcErr == nil || rpcErr.Code != codeInvalidParams {
		t.Errorf("after releasing the guard, expected the normal invalid-params error, got %+v", rpcErr)
	}
}

// TestConverseCallSetsBusyThenReleasesIt is a narrower unit check on the atomic itself (the same
// CompareAndSwap/Store pair converseCall uses), independent of the rest of converseCall's behavior: only one
// acquire can succeed at a time, and Store(false) always makes the slot available again.
func TestConverseCallSetsBusyThenReleasesIt(t *testing.T) {
	var s Server
	if !s.converseBusy.CompareAndSwap(false, true) {
		t.Fatal("first acquire should have succeeded")
	}
	if s.converseBusy.CompareAndSwap(false, true) {
		t.Fatal("second concurrent acquire should have been refused")
	}
	s.converseBusy.Store(false)
	if !s.converseBusy.CompareAndSwap(false, true) {
		t.Fatal("acquire after release should have succeeded")
	}
}

// TestConverseEnvStripsInheritedSymbiosisVars covers the security-relevant part of the fix: converse.py's
// --token/--base/--pin default from SYMBIOSIS_TOKEN/SYMBIOSIS_URL/SYMBIOSIS_TLS_PIN, and --token beats
// --token-file. If the gateway's own (inherited) environment happened to carry those — e.g. another
// phone's gateway running in the same environment — they must never reach the child, only whatever this
// call's `extra` explicitly supplies.
func TestConverseEnvStripsInheritedSymbiosisVars(t *testing.T) {
	environ := []string{
		"PATH=/usr/bin",
		"SYMBIOSIS_TOKEN=someone-elses-token",
		"SYMBIOSIS_URL=https://someone-elses-phone",
		"SYMBIOSIS_TLS_PIN=AA:BB",
		"OTHER=kept",
	}
	got := converseEnv(environ, []string{"SYMBIOSIS_TOKEN=this-gateways-token"})
	want := []string{"PATH=/usr/bin", "OTHER=kept", "SYMBIOSIS_TOKEN=this-gateways-token"}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("converseEnv = %v, want %v", got, want)
	}

	// No extra: the stripped variables are simply gone, not replaced.
	if got := converseEnv(environ, nil); reflect.DeepEqual(got, environ) {
		t.Error("converseEnv with no extra must still strip the inherited SYMBIOSIS_* vars")
	}
}
