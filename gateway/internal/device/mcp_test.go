package device

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/fakephone"
)

func testClient(t *testing.T, p *fakephone.Phone) *MCPClient {
	t.Helper()
	return NewMCPClient(MCPOptions{BaseURL: p.URL(), Token: "tok"})
}

func TestInitializeCapturesSession(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c := testClient(t, p)

	ctx := context.Background()
	res, err := c.Initialize(ctx)
	if err != nil {
		t.Fatalf("Initialize: %v", err)
	}
	if res.ServerInfo.Name != "android-agent-harness-runtime" {
		t.Errorf("serverInfo.name = %q", res.ServerInfo.Name)
	}
	if res.ProtocolVersion != ProtocolVersion {
		t.Errorf("protocolVersion = %q, want %q", res.ProtocolVersion, ProtocolVersion)
	}
	if c.SessionID() == "" {
		t.Fatal("session id was not captured from the Mcp-Session-Id header")
	}
}

func TestUnauthorizedIsReported(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c := NewMCPClient(MCPOptions{BaseURL: p.URL(), Token: "wrong"})
	if _, err := c.Initialize(context.Background()); err == nil {
		t.Fatal("expected an unauthorized error")
	}
}

func TestListToolsAutoInitializes(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c := testClient(t, p)

	tools, err := c.ListTools(context.Background())
	if err != nil {
		t.Fatalf("ListTools: %v", err)
	}
	if len(tools) != 6 {
		t.Fatalf("got %d tools, want 6", len(tools))
	}
	want := map[string]bool{
		"device.get_battery": true, "device.get_info": true,
		"messaging.send_sms": true, "messaging.read_sms": true,
		"telephony.place_call": true, "events.subscribe": true,
	}
	for _, tool := range tools {
		if !want[tool.Name] {
			t.Errorf("unexpected tool %q", tool.Name)
		}
		delete(want, tool.Name)
	}
	if len(want) != 0 {
		t.Errorf("missing tools: %v", want)
	}
}

func TestToolMetadataDecoding(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c := testClient(t, p)
	tools, err := c.ListTools(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	byName := map[string]Tool{}
	for _, tool := range tools {
		byName[tool.Name] = tool
	}

	tests := []struct {
		name        string
		readOnly    bool
		actionClass string
	}{
		{"device.get_battery", true, "READ_ONLY"},
		{"messaging.send_sms", false, "COMMUNICATION"},
		{"telephony.place_call", false, "EXTERNAL_COMMITMENT"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			tool := byName[tc.name]
			if tool.ReadOnly() != tc.readOnly {
				t.Errorf("ReadOnly() = %v, want %v", tool.ReadOnly(), tc.readOnly)
			}
			if got := tool.Symbiosis().ActionClass; got != tc.actionClass {
				t.Errorf("action_class = %q, want %q", got, tc.actionClass)
			}
		})
	}
}

func TestSessionExpiryReinitializesOnce(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c := testClient(t, p)
	ctx := context.Background()

	if _, err := c.ListTools(ctx); err != nil {
		t.Fatalf("first ListTools: %v", err)
	}
	first := c.SessionID()
	if p.SessionCount() != 1 {
		t.Fatalf("sessions created = %d, want 1", p.SessionCount())
	}

	p.ExpireSessions()

	if _, err := c.ListTools(ctx); err != nil {
		t.Fatalf("ListTools after expiry: %v", err)
	}
	if c.SessionID() == first {
		t.Error("expected a new session id after the 404 recovery")
	}
	if p.SessionCount() != 2 {
		t.Errorf("sessions created = %d, want 2", p.SessionCount())
	}
}

func TestCallToolStampsCorrelationAndIdempotency(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c := testClient(t, p)
	ctx := context.Background()

	tests := []struct {
		name        string
		tool        string
		args        map[string]any
		readOnly    bool
		wantIdemKey bool
	}{
		{"read only omits idempotency key", "device.get_battery", map[string]any{}, true, false},
		{"mutating call carries idempotency key", "messaging.send_sms",
			map[string]any{"to": "+15550001234", "text": "hi"}, false, true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			before := len(p.Calls())
			if _, err := c.CallTool(ctx, tc.tool, tc.args, CallToolOptions{
				CorrelationID: "corr_00112233445566aa",
				ReadOnly:      tc.readOnly,
			}); err != nil {
				t.Fatalf("CallTool: %v", err)
			}
			calls := p.Calls()
			if len(calls) != before+1 {
				t.Fatalf("expected exactly one new call")
			}
			meta := calls[len(calls)-1].Meta
			if meta["correlationId"] != "corr_00112233445566aa" {
				t.Errorf("correlationId = %v", meta["correlationId"])
			}
			_, has := meta["idempotencyKey"]
			if has != tc.wantIdemKey {
				t.Errorf("idempotencyKey present = %v, want %v", has, tc.wantIdemKey)
			}
			if tc.wantIdemKey {
				want, err := IdempotencyKey("corr_00112233445566aa", tc.tool, tc.args)
				if err != nil {
					t.Fatal(err)
				}
				if meta["idempotencyKey"] != want {
					t.Errorf("idempotencyKey = %v, want %v", meta["idempotencyKey"], want)
				}
			}
		})
	}
}

func TestIdempotencyKeyDeterminism(t *testing.T) {
	// Key order must not change the key: the hash is over canonical JSON.
	a := map[string]any{"to": "+15550001234", "text": "hello", "sim_slot": 1}
	b := map[string]any{"sim_slot": 1, "text": "hello", "to": "+15550001234"}

	ka, err := IdempotencyKey("corr_1", "messaging.send_sms", a)
	if err != nil {
		t.Fatal(err)
	}
	kb, err := IdempotencyKey("corr_1", "messaging.send_sms", b)
	if err != nil {
		t.Fatal(err)
	}
	if ka != kb {
		t.Fatalf("key depends on map iteration order:\n%s\n%s", ka, kb)
	}
	if len(ka) != 64 {
		t.Errorf("key length = %d, want 64 hex chars", len(ka))
	}

	tests := []struct {
		name string
		corr string
		tool string
		args map[string]any
	}{
		{"different correlation", "corr_2", "messaging.send_sms", a},
		{"different tool", "corr_1", "messaging.read_sms", a},
		{"different args", "corr_1", "messaging.send_sms", map[string]any{"to": "+15550009999"}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			k, err := IdempotencyKey(tc.corr, tc.tool, tc.args)
			if err != nil {
				t.Fatal(err)
			}
			if k == ka {
				t.Errorf("key collided with the baseline")
			}
		})
	}

	// Nested maps must also be order independent.
	n1 := map[string]any{"outer": map[string]any{"z": 1, "a": 2}, "list": []any{map[string]any{"b": 1, "a": 2}}}
	n2 := map[string]any{"list": []any{map[string]any{"a": 2, "b": 1}}, "outer": map[string]any{"a": 2, "z": 1}}
	k1, _ := IdempotencyKey("c", "t", n1)
	k2, _ := IdempotencyKey("c", "t", n2)
	if k1 != k2 {
		t.Error("nested maps are not canonicalised")
	}
}

func TestPolicyOutcomeIsAToolResultNotAnError(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c := testClient(t, p)

	out, err := c.CallTool(context.Background(), "telephony.place_call",
		map[string]any{"target": "+15550001234"}, CallToolOptions{CorrelationID: "corr_x"})
	if err != nil {
		t.Fatalf("policy outcomes must not surface as errors, got %v", err)
	}
	if out.OK {
		t.Fatal("expected isError to be true")
	}
	if out.ErrorCode != "policy.approval_required" {
		t.Errorf("ErrorCode = %q", out.ErrorCode)
	}
	if out.PendingActionID != "pa_0001" {
		t.Errorf("PendingActionID = %q", out.PendingActionID)
	}
	if !strings.Contains(out.Text, "approval required") {
		t.Errorf("Text = %q", out.Text)
	}
}

func TestUnknownToolIsAJSONRPCError(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c := testClient(t, p)
	_, err := c.CallTool(context.Background(), "files.delete_everything", nil, CallToolOptions{})
	if err == nil {
		t.Fatal("expected a JSON-RPC error for a hidden or unknown tool")
	}
	if !strings.Contains(err.Error(), "-32601") {
		t.Errorf("error = %v, want jsonrpc -32601", err)
	}
}

func TestHealth(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c := testClient(t, p)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	h, err := c.Health(ctx)
	if err != nil {
		t.Fatalf("Health: %v", err)
	}
	if !h.OK || h.DeviceID != fakephone.DeviceID {
		t.Errorf("health = %+v", h)
	}
}

func TestCloseEndsSession(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()
	c := testClient(t, p)
	ctx := context.Background()
	if _, err := c.ListTools(ctx); err != nil {
		t.Fatal(err)
	}
	if err := c.Close(ctx); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if c.SessionID() != "" {
		t.Error("session id should be cleared after Close")
	}
}
