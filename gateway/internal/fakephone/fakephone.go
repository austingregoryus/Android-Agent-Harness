// Package fakephone is an in-process implementation of the Android Agent Harness runtime's
// wire surface, backed by httptest. It exists so the gateway's tests can drive
// a real HTTP and WebSocket peer with no network and no Android.
//
// It implements GET /health, POST and DELETE /mcp (initialize with a session id,
// ping, tools/list, tools/call including an isError policy outcome) and
// GET /ws/events with a journal, persisted subscriptions, cumulative ack,
// replay with truncation, gap frames and a way to force-drop the socket.
package fakephone

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

// DeviceID is the device id the fake reports.
const DeviceID = "dev_fake0001"

// Version is the runtime version the fake reports.
const Version = "0.1.0-fake"

// ToolHandler produces a tools/call result envelope for a call.
type ToolHandler func(name string, args map[string]any, meta map[string]any) map[string]any

// Event is a journal record.
type Event struct {
	// ID is the journal id, assigned by Push.
	ID int64 `json:"id"`
	// Topic is the dotted topic.
	Topic string `json:"topic"`
	// TS is the event time in epoch milliseconds.
	TS int64 `json:"ts"`
	// DeviceID identifies the phone.
	DeviceID string `json:"device_id"`
	// CorrelationID links the event to a tool call.
	CorrelationID string `json:"correlation_id,omitempty"`
	// Payload is the topic-specific body.
	Payload map[string]any `json:"payload"`
}

// subscription is a persisted subscription, surviving socket drops.
type subscription struct {
	ID               string
	TopicGlob        string
	Filter           []map[string]any
	Delivery         string
	CreatedAtEventID int64
	AckCursor        int64
}

// Phone is a fake Android Agent Harness runtime.
type Phone struct {
	// Server is the underlying httptest server.
	Server *httptest.Server

	mu sync.Mutex
	// journal holds every pushed event, oldest first.
	journal []Event
	nextID  int64
	// earliestAvailable simulates retention eviction for replay truncation.
	earliestAvailable int64

	subs   map[string]*subscription
	nextSb int

	sessions map[string]bool
	nextSess int

	// conns tracks live event sockets so ForceDrop can close them.
	conns map[*websocket.Conn]struct{}

	// pauseLive simulates backpressure: pushes are journalled but not sent,
	// and the accumulated range is reported as a gap when live push resumes.
	pauseLive   bool
	pausedFrom  int64
	pausedTo    int64
	toolHandler ToolHandler

	// Calls records every tools/call the fake received.
	calls []RecordedCall

	// idempotency stores completed tools/call results by peer and idempotency
	// key, per protocol.md section 3: "If present and an audit row with the
	// same key and a completed outcome exists, the stored result is returned
	// without re-executing." The peer key is the caller's bearer token (empty
	// string when auth is disabled), which is this fake's stand-in for a real
	// peer_id since a Phone only ever serves one configured token.
	idempotency map[string]map[string]map[string]any

	// dropResponseFor, when non-empty, makes the fake execute the next
	// tools/call for that tool name normally (recording it, running the
	// handler, storing the idempotent result if any) and then hijack and close
	// the raw connection instead of writing the HTTP response - simulating a
	// transport failure that happens after the phone has already acted. It is
	// consumed (reset to "") on the first matching call.
	dropResponseFor string

	// requireAuth rejects unauthenticated MCP and WS requests when true.
	requireAuth bool
	// unhealthy makes /health report ok:false.
	unhealthy bool
	token     string
	// tls is true when the fake is served behind httptest.NewTLSServer.
	tls bool

	// deniedSubscribes maps a topic glob to the error frame the fake replies
	// with instead of creating a subscription, simulating a policy-denied
	// subscribe (e.g. an out-of-scope topic).
	deniedSubscribes map[string]deniedSubscribe
	// deniedSubscribeAttempts counts every subscribe request that was denied,
	// so a test can prove a denied subscription is not retried on reconnect.
	deniedSubscribeAttempts int

	// legacy makes the event channel behave like an older runtime: no hello
	// frame and no ref echoed on replies.
	legacy bool
	// replayScript holds the scripted outcomes for the next replay requests,
	// consumed one per request; once empty, replays are answered normally.
	replayScript []ReplayAction

	// reqMu guards reqCount, tracked independently of mu so counting a request
	// never contends with the protocol-state lock.
	reqMu    sync.Mutex
	reqCount int
}

// ReplayAction scripts how the fake answers one replay request.
type ReplayAction int

// Replay actions.
const (
	// ReplayDrop sends no reply at all, so the client's request times out.
	ReplayDrop ReplayAction = iota + 1
	// ReplayError answers with an error frame (ref echoed, as the runtime
	// does for subscription.unknown).
	ReplayError
	// ReplayWrongSubscription answers with an empty replay_result naming a
	// different subscription.
	ReplayWrongSubscription
)

// deniedSubscribe is the error frame content for a denied topic glob.
type deniedSubscribe struct {
	code    string
	message string
}

// RecordedCall is one observed tools/call.
type RecordedCall struct {
	// Name is the tool name.
	Name string
	// Args are the arguments.
	Args map[string]any
	// Meta is the params._meta object.
	Meta map[string]any
	// SessionID is the Mcp-Session-Id header value.
	SessionID string
}

// Options configures a fake phone.
type Options struct {
	// Token, when non-empty, is the bearer token the fake requires.
	Token string
	// ToolHandler overrides the default scripted tools/call behaviour.
	ToolHandler ToolHandler
	// Unhealthy makes GET /health answer 200 with ok:false, the shape a runtime
	// uses when it is reachable but not ready to act.
	Unhealthy bool
	// TLS starts the fake behind httptest.NewTLSServer instead of NewServer,
	// for exercising the gateway's certificate-pinning TLS connector. The
	// certificate is httptest's default self-signed cert (no SANs, no CA),
	// exposed via Phone.Server.Certificate() and reported by GET /health and
	// POST /pair as tls_fingerprint_sha256, matching the phone's real TLS-only
	// connector.
	TLS bool
	// Legacy makes /ws/events behave like an older runtime: no hello frame on
	// connect and no "ref" echoed on replies.
	Legacy bool
}

// New starts a fake runtime. Call Close when done.
func New(opts Options) *Phone {
	p := &Phone{
		subs:             map[string]*subscription{},
		sessions:         map[string]bool{},
		conns:            map[*websocket.Conn]struct{}{},
		toolHandler:      opts.ToolHandler,
		token:            opts.Token,
		requireAuth:      opts.Token != "",
		unhealthy:        opts.Unhealthy,
		tls:              opts.TLS,
		legacy:           opts.Legacy,
		idempotency:      map[string]map[string]map[string]any{},
		deniedSubscribes: map[string]deniedSubscribe{},
	}
	if p.toolHandler == nil {
		p.toolHandler = DefaultToolHandler
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/health", p.handleHealth)
	mux.HandleFunc("/pair", p.handlePair)
	mux.HandleFunc("/mcp", p.handleMCP)
	mux.HandleFunc("/ws/events", p.handleEvents)
	// Wrap with a counting handler so a test can prove a rejected TLS
	// handshake (wrong pinned fingerprint) never reached any handler at all:
	// the handshake fails before net/http hands the connection to this mux.
	counting := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p.reqMu.Lock()
		p.reqCount++
		p.reqMu.Unlock()
		mux.ServeHTTP(w, r)
	})
	if opts.TLS {
		p.Server = httptest.NewTLSServer(counting)
	} else {
		p.Server = httptest.NewServer(counting)
	}
	return p
}

// URL is the fake runtime's base URL.
func (p *Phone) URL() string { return p.Server.URL }

// RequestCount reports how many HTTP requests of any kind (including ones
// that failed later in the handler) reached the fake's handler. A TLS
// handshake that is rejected before the HTTP layer runs — e.g. a certificate
// pinning mismatch — never increments this.
func (p *Phone) RequestCount() int {
	p.reqMu.Lock()
	defer p.reqMu.Unlock()
	return p.reqCount
}

// tlsFingerprintSHA256 returns the SHA-256 fingerprint of the fake's own TLS
// certificate (colon-separated uppercase hex), or "" when the fake is not
// running with TLS.
func (p *Phone) tlsFingerprintSHA256() string {
	if !p.tls {
		return ""
	}
	cert := p.Server.Certificate()
	if cert == nil {
		return ""
	}
	sum := sha256.Sum256(cert.Raw)
	parts := make([]string, len(sum))
	for i, b := range sum {
		parts[i] = fmt.Sprintf("%02X", b)
	}
	return strings.Join(parts, ":")
}

// Close shuts the fake down.
func (p *Phone) Close() {
	p.mu.Lock()
	conns := make([]*websocket.Conn, 0, len(p.conns))
	for c := range p.conns {
		conns = append(conns, c)
	}
	p.mu.Unlock()
	for _, c := range conns {
		c.CloseNow()
	}
	p.Server.Close()
}

func (p *Phone) authOK(r *http.Request) bool {
	if !p.requireAuth {
		return true
	}
	return r.Header.Get("Authorization") == "Bearer "+p.token
}

// --- /health ----------------------------------------------------------------

func (p *Phone) handleHealth(w http.ResponseWriter, r *http.Request) {
	var fp any
	if v := p.tlsFingerprintSHA256(); v != "" {
		fp = v
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": !p.unhealthy, "device_id": DeviceID, "version": Version,
		"tls_fingerprint_sha256": fp,
	})
}

// --- /pair ------------------------------------------------------------------

func (p *Phone) handlePair(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	var req struct {
		Code           string   `json:"code"`
		Label          string   `json:"label"`
		Scopes         []string `json:"scopes"`
		MaxActionClass string   `json:"max_action_class"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "bad request"})
		return
	}
	if req.Code == "" || req.Code == "BAD-CODE" {
		writeJSON(w, http.StatusForbidden, map[string]any{"error": "invalid or expired code"})
		return
	}
	resp := map[string]any{
		"peer_id":          "peer_abcdef012345",
		"token":            "fake-token-value-do-not-log",
		"scopes":           req.Scopes,
		"max_action_class": req.MaxActionClass,
		"expires_at":       nil,
	}
	if fp := p.tlsFingerprintSHA256(); fp != "" {
		resp["tls_fingerprint_sha256"] = fp
		resp["tls_mcp_url"] = p.Server.URL + "/mcp"
		resp["tls_events_url"] = "wss" + strings.TrimPrefix(p.Server.URL, "https") + "/ws/events"
	} else {
		resp["tls_fingerprint_sha256"] = nil
	}
	writeJSON(w, http.StatusOK, resp)
}

// --- /mcp -------------------------------------------------------------------

func (p *Phone) handleMCP(w http.ResponseWriter, r *http.Request) {
	if !p.authOK(r) {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	switch r.Method {
	case http.MethodGet:
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	case http.MethodDelete:
		sid := r.Header.Get("Mcp-Session-Id")
		p.mu.Lock()
		delete(p.sessions, sid)
		p.mu.Unlock()
		w.WriteHeader(http.StatusNoContent)
		return
	case http.MethodPost:
	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	var probe any
	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(&probe); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "parse error"})
		return
	}
	if _, isBatch := probe.([]any); isBatch {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "batch not supported"})
		return
	}
	obj, ok := probe.(map[string]any)
	if !ok {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid request"})
		return
	}
	method, _ := obj["method"].(string)
	id, hasID := obj["id"]
	params, _ := obj["params"].(map[string]any)

	// Notifications get 202 with an empty body.
	if !hasID {
		w.WriteHeader(http.StatusAccepted)
		return
	}

	if method != "initialize" {
		sid := r.Header.Get("Mcp-Session-Id")
		p.mu.Lock()
		known := sid != "" && p.sessions[sid]
		p.mu.Unlock()
		if !known {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "unknown session"})
			return
		}
	}

	if v := r.Header.Get("MCP-Protocol-Version"); v != "" {
		w.Header().Set("MCP-Protocol-Version", v)
	}

	switch method {
	case "initialize":
		p.mu.Lock()
		p.nextSess++
		sid := fmt.Sprintf("sess_%04d", p.nextSess)
		p.sessions[sid] = true
		p.mu.Unlock()
		w.Header().Set("Mcp-Session-Id", sid)
		writeRPC(w, id, map[string]any{
			"protocolVersion": "2025-06-18",
			"capabilities":    map[string]any{"tools": map[string]any{}, "resources": map[string]any{}},
			"serverInfo":      map[string]any{"name": "android-agent-harness-runtime", "version": Version},
			"_meta":           map[string]any{"symbiosis": map[string]any{"protocol": "0.1"}},
		})
	case "ping":
		writeRPC(w, id, map[string]any{})
	case "tools/list":
		writeRPC(w, id, map[string]any{"tools": Tools()})
	case "tools/call":
		name, _ := params["name"].(string)
		args, _ := params["arguments"].(map[string]any)
		meta, _ := params["_meta"].(map[string]any)
		if args == nil {
			args = map[string]any{}
		}
		idemKey, _ := meta["idempotencyKey"].(string)
		peer := r.Header.Get("Authorization")

		p.mu.Lock()
		p.calls = append(p.calls, RecordedCall{
			Name: name, Args: args, Meta: meta, SessionID: r.Header.Get("Mcp-Session-Id"),
		})
		// Per protocol.md section 3: a call carrying an idempotencyKey that
		// already has a completed outcome on record gets that outcome back
		// verbatim, without re-executing.
		if idemKey != "" {
			if stored, ok := p.idempotency[peer][idemKey]; ok {
				p.mu.Unlock()
				writeRPC(w, id, stored)
				return
			}
		}
		handler := p.toolHandler
		p.mu.Unlock()

		if !toolExists(name) {
			writeRPCError(w, id, -32601, "unknown tool: "+name)
			return
		}
		result := handler(name, args, meta)
		if result == nil {
			result = map[string]any{"content": []any{}, "isError": false}
		}
		if _, ok := result["_meta"]; !ok {
			corr := ""
			if meta != nil {
				corr, _ = meta["correlationId"].(string)
			}
			result["_meta"] = map[string]any{"correlationId": corr, "auditId": 1, "durationMs": 5}
		}

		if idemKey != "" {
			p.mu.Lock()
			if p.idempotency[peer] == nil {
				p.idempotency[peer] = map[string]map[string]any{}
			}
			p.idempotency[peer][idemKey] = result
			p.mu.Unlock()
		}

		// FailResponseOnce fires here: the call above already ran (and, if it
		// carried an idempotency key, its result is already on record), but
		// the client never sees this response - it observes a transport
		// failure, exactly as if the connection dropped after the phone acted
		// but before the reply made it back.
		if p.consumeDropResponse(name) {
			if hj, ok := w.(http.Hijacker); ok {
				if conn, _, herr := hj.Hijack(); herr == nil {
					conn.Close()
					return
				}
			}
		}

		writeRPC(w, id, result)
	default:
		writeRPCError(w, id, -32601, "unknown method: "+method)
	}
}

// FailResponseOnce arms the fake so the next tools/call for toolName executes
// normally - recorded, run through the handler, its result stored under the
// caller's idempotency key if it sent one - but the raw HTTP connection is
// then hijacked and closed instead of writing the response. The caller
// observes a transport failure even though the phone already acted, which is
// what lets a retry with the same idempotency key be told apart from a first
// attempt. It disarms itself after the first matching call.
func (p *Phone) FailResponseOnce(toolName string) {
	p.mu.Lock()
	p.dropResponseFor = toolName
	p.mu.Unlock()
}

// consumeDropResponse reports whether name matches an armed FailResponseOnce,
// disarming it if so.
func (p *Phone) consumeDropResponse(name string) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.dropResponseFor != "" && p.dropResponseFor == name {
		p.dropResponseFor = ""
		return true
	}
	return false
}

// DenySubscribe makes the fake reply to a "subscribe" request for topicGlob
// with an error frame - code and message verbatim - instead of creating a
// subscription, the way a real runtime answers an out-of-scope topic
// (e.g. "policy.denied"). No subscription is registered for a denied glob.
func (p *Phone) DenySubscribe(topicGlob, code, message string) {
	p.mu.Lock()
	p.deniedSubscribes[topicGlob] = deniedSubscribe{code: code, message: message}
	p.mu.Unlock()
}

// DeniedSubscribeAttempts reports how many "subscribe" requests the fake has
// denied, across every topic glob configured with DenySubscribe. A test can
// use this to prove a denied subscription is not retried on reconnect.
func (p *Phone) DeniedSubscribeAttempts() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.deniedSubscribeAttempts
}

// Calls returns every tools/call the fake observed.
func (p *Phone) Calls() []RecordedCall {
	p.mu.Lock()
	defer p.mu.Unlock()
	out := make([]RecordedCall, len(p.calls))
	copy(out, p.calls)
	return out
}

// ExpireSessions forgets every MCP session, so the next non-initialize request
// gets HTTP 404 and the client must re-initialize.
func (p *Phone) ExpireSessions() {
	p.mu.Lock()
	p.sessions = map[string]bool{}
	p.mu.Unlock()
}

// SessionCount reports how many MCP sessions have been created.
func (p *Phone) SessionCount() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.nextSess
}

// --- tools ------------------------------------------------------------------

// Tools returns the six tools the fake advertises. Names follow the protocol's
// dotted capability namespaces.
func Tools() []map[string]any {
	obj := func(props map[string]any, required ...string) map[string]any {
		m := map[string]any{"type": "object", "properties": props}
		if len(required) > 0 {
			m["required"] = toAnySlice(required)
		}
		return m
	}
	str := map[string]any{"type": "string"}
	return []map[string]any{
		{
			"name":        "device.get_battery",
			"description": "Read the phone battery percentage and charging state.",
			"inputSchema": obj(map[string]any{}),
			"annotations": map[string]any{"readOnlyHint": true, "idempotentHint": true},
			"_meta": map[string]any{"symbiosis": map[string]any{
				"action_class": "READ_ONLY", "tier": "1", "rate_class": "none",
				"emits_events": []any{"battery.changed"},
			}},
		},
		{
			"name":        "device.get_info",
			"description": "Read device model, Android version and identifiers.",
			"inputSchema": obj(map[string]any{}),
			"annotations": map[string]any{"readOnlyHint": true, "idempotentHint": true},
			"_meta": map[string]any{"symbiosis": map[string]any{
				"action_class": "READ_ONLY", "tier": "1", "rate_class": "none",
			}},
		},
		{
			"name":        "messaging.read_sms",
			"description": "Read recent SMS messages, optionally filtered by thread.",
			"inputSchema": obj(map[string]any{
				"thread_id": str,
				"limit":     map[string]any{"type": "integer", "minimum": 1, "maximum": 100},
			}),
			"annotations": map[string]any{"readOnlyHint": true},
			"_meta": map[string]any{"symbiosis": map[string]any{
				"action_class": "READ_ONLY", "tier": "1", "rate_class": "none",
			}},
		},
		{
			"name":        "messaging.send_sms",
			"description": "Send an SMS message.",
			"inputSchema": obj(map[string]any{"to": str, "text": str}, "to", "text"),
			"annotations": map[string]any{"readOnlyHint": false, "openWorldHint": true},
			"_meta": map[string]any{"symbiosis": map[string]any{
				"action_class": "COMMUNICATION", "tier": "1", "rate_class": "sms",
				"emits_events": []any{"messaging.sms_sent"},
			}},
		},
		{
			"name":        "telephony.place_call",
			"description": "Place an outbound phone call and create a session.",
			"inputSchema": obj(map[string]any{
				"target": str, "display_name": str, "purpose": str,
				"audio_route": map[string]any{"type": "string", "enum": []any{"speaker", "earpiece"}},
			}, "target"),
			"annotations": map[string]any{"readOnlyHint": false, "openWorldHint": true},
			"_meta": map[string]any{"symbiosis": map[string]any{
				"action_class": "EXTERNAL_COMMITMENT", "tier": "1", "rate_class": "call",
				"emits_events": []any{"call.ringing", "call.connected", "call.ended"},
			}},
		},
		{
			"name":        "events.subscribe",
			"description": "Create an event subscription over the MCP surface.",
			"inputSchema": obj(map[string]any{
				"topic_glob": str,
				"delivery":   map[string]any{"type": "string", "enum": []any{"live", "queue"}},
				"ttl_ms":     map[string]any{"type": "integer"},
			}, "topic_glob"),
			"annotations": map[string]any{"readOnlyHint": false, "idempotentHint": true},
			"_meta": map[string]any{"symbiosis": map[string]any{
				"action_class": "LOW_RISK", "tier": "1", "rate_class": "none",
			}},
		},
	}
}

func toolExists(name string) bool {
	for _, t := range Tools() {
		if t["name"] == name {
			return true
		}
	}
	return false
}

func toAnySlice(ss []string) []any {
	out := make([]any, len(ss))
	for i, s := range ss {
		out[i] = s
	}
	return out
}

// DefaultToolHandler is the fake's scripted tools/call behaviour.
//
// telephony.place_call always returns the policy.approval_required isError
// envelope from protocol.md section 3.2, so the agent's handling of a policy
// outcome is exercised.
func DefaultToolHandler(name string, args map[string]any, meta map[string]any) map[string]any {
	ok := func(structured map[string]any) map[string]any {
		raw, _ := json.Marshal(structured)
		return map[string]any{
			"content":           []any{map[string]any{"type": "text", "text": string(raw)}},
			"structuredContent": structured,
			"isError":           false,
		}
	}
	switch name {
	case "device.get_battery":
		return ok(map[string]any{"percent": 82, "charging": false, "status": "discharging"})
	case "device.get_info":
		return ok(map[string]any{"model": "Fake Pixel", "android": "16", "device_id": DeviceID})
	case "messaging.read_sms":
		return ok(map[string]any{"messages": []any{
			map[string]any{"message_id": "m1", "from": "+15550001234", "text": "your code is 123456", "ts": 1757139000123},
		}})
	case "messaging.send_sms":
		to, _ := args["to"].(string)
		return ok(map[string]any{"message_id": "m_sent_1", "to": to, "sent": true})
	case "telephony.place_call":
		return map[string]any{
			"isError": true,
			"content": []any{map[string]any{"type": "text", "text": "approval required"}},
			"structuredContent": map[string]any{
				"error":             "policy.approval_required",
				"pending_action_id": "pa_0001",
				"expires_at":        1757140000000,
				"action_class":      "EXTERNAL_COMMITMENT",
			},
		}
	case "events.subscribe":
		glob, _ := args["topic_glob"].(string)
		return ok(map[string]any{"subscription_id": "sub_mcp000001", "topic_glob": glob, "created_at_event_id": 0})
	default:
		return map[string]any{
			"isError":           true,
			"content":           []any{map[string]any{"type": "text", "text": "capability unavailable"}},
			"structuredContent": map[string]any{"error": "capability.unavailable"},
		}
	}
}

// --- /ws/events -------------------------------------------------------------

func (p *Phone) handleEvents(w http.ResponseWriter, r *http.Request) {
	if !p.authOK(r) {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	// Go WebSocket clients send no Origin header, so the default origin check
	// is sufficient here; no TLS verification is being relaxed.
	conn, err := websocket.Accept(w, r, nil)
	if err != nil {
		return
	}
	conn.SetReadLimit(8 << 20)

	p.mu.Lock()
	p.conns[conn] = struct{}{}
	// Like the runtime, hello is the first frame, sent before any client frame
	// is read: the newest journal id and the peer's subscriptions.
	var hello map[string]any
	if !p.legacy {
		ids := make([]any, 0, len(p.subs))
		for id := range p.subs {
			ids = append(ids, id)
		}
		hello = map[string]any{"type": "hello", "peer_id": "peer_abcdef012345", "last_event_id": p.nextID, "subscriptions": ids}
	}
	p.mu.Unlock()
	defer func() {
		p.mu.Lock()
		delete(p.conns, conn)
		p.mu.Unlock()
		conn.CloseNow()
	}()

	ctx := r.Context()
	if hello != nil {
		p.writeFrame(ctx, conn, hello)
	}
	for {
		_, data, err := conn.Read(ctx)
		if err != nil {
			return
		}
		var frame map[string]any
		if err := json.Unmarshal(data, &frame); err != nil {
			p.writeFrame(ctx, conn, map[string]any{"type": "error", "code": "bad_frame", "message": err.Error()})
			continue
		}
		p.handleEventFrame(ctx, conn, frame)
	}
}

func (p *Phone) handleEventFrame(ctx context.Context, conn *websocket.Conn, frame map[string]any) {
	typ, _ := frame["type"].(string)
	// reply echoes the request's ref, as the runtime's Server.kt does for every
	// reply, error replies included.
	ref, hasRef := frame["ref"]
	reply := func(out map[string]any) {
		if hasRef && !p.legacy {
			out["ref"] = ref
		}
		p.writeFrame(ctx, conn, out)
	}
	switch typ {
	case "ping":
		reply(map[string]any{"type": "pong"})

	case "list":
		p.mu.Lock()
		list := make([]any, 0, len(p.subs))
		for _, s := range p.subs {
			list = append(list, map[string]any{
				"subscription_id":     s.ID,
				"topic_glob":          s.TopicGlob,
				"delivery":            s.Delivery,
				"created_at_event_id": s.CreatedAtEventID,
				"ack_cursor":          s.AckCursor,
			})
		}
		p.mu.Unlock()
		reply(map[string]any{"type": "subscriptions", "subscriptions": list})

	case "subscribe":
		glob, _ := frame["topic_glob"].(string)
		delivery, _ := frame["delivery"].(string)
		p.mu.Lock()
		if deny, ok := p.deniedSubscribes[glob]; ok {
			p.deniedSubscribeAttempts++
			p.mu.Unlock()
			reply(map[string]any{"type": "error", "code": deny.code, "message": deny.message})
			return
		}
		p.nextSb++
		sub := &subscription{
			ID:               fmt.Sprintf("sub_%012d", p.nextSb),
			TopicGlob:        glob,
			Delivery:         delivery,
			CreatedAtEventID: p.nextID,
		}
		p.subs[sub.ID] = sub
		p.mu.Unlock()
		reply(map[string]any{
			"type": "subscribed", "subscription_id": sub.ID, "created_at_event_id": sub.CreatedAtEventID,
		})

	case "unsubscribe":
		id, _ := frame["subscription_id"].(string)
		p.mu.Lock()
		delete(p.subs, id)
		p.mu.Unlock()
		reply(map[string]any{"type": "unsubscribed", "subscription_id": id})

	case "ack":
		id, _ := frame["subscription_id"].(string)
		through := asInt64(frame["through_id"])
		p.mu.Lock()
		if s, ok := p.subs[id]; ok && through > s.AckCursor {
			s.AckCursor = through
		}
		p.mu.Unlock()

	case "replay":
		id, _ := frame["subscription_id"].(string)
		since := asInt64(frame["since_id"])
		limit := asInt64(frame["limit"])
		if limit <= 0 {
			limit = 200
		}
		if limit > 1000 {
			limit = 1000
		}
		p.mu.Lock()
		var action ReplayAction
		if len(p.replayScript) > 0 {
			action = p.replayScript[0]
			p.replayScript = p.replayScript[1:]
		}
		switch action {
		case ReplayDrop:
			p.mu.Unlock()
			return
		case ReplayError:
			p.mu.Unlock()
			reply(map[string]any{"type": "error", "code": "internal", "message": "scripted replay failure"})
			return
		case ReplayWrongSubscription:
			p.mu.Unlock()
			reply(map[string]any{
				"type": "replay_result", "subscription_id": "sub_ffffffffffff",
				"events": []Event{}, "next_since_id": since, "truncated": false, "earliest_available_id": 0,
			})
			return
		}
		sub, ok := p.subs[id]
		if !ok {
			p.mu.Unlock()
			reply(map[string]any{
				"type": "error", "code": "unknown_subscription", "message": id,
			})
			return
		}
		floor := since
		if sub.CreatedAtEventID > floor {
			floor = sub.CreatedAtEventID
		}
		var out []Event
		for _, ev := range p.journal {
			if ev.ID <= floor {
				continue
			}
			if !topicMatch(sub.TopicGlob, ev.Topic) {
				continue
			}
			out = append(out, ev)
			if int64(len(out)) >= limit {
				break
			}
		}
		truncated := p.earliestAvailable > since+1
		earliest := p.earliestAvailable
		next := since
		if len(out) > 0 {
			next = out[len(out)-1].ID
		}
		p.mu.Unlock()
		payload := map[string]any{
			"type": "replay_result", "subscription_id": id,
			"events": out, "next_since_id": next, "truncated": truncated,
		}
		if truncated {
			payload["earliest_available_id"] = earliest
		}
		if out == nil {
			payload["events"] = []Event{}
		}
		reply(payload)

	default:
		reply(map[string]any{"type": "error", "code": "unknown_frame", "message": typ})
	}
}

// ScriptReplays sets how the fake answers the next replay requests, one action
// per request in order; after the script runs out, replays are answered
// normally again.
func (p *Phone) ScriptReplays(actions ...ReplayAction) {
	p.mu.Lock()
	p.replayScript = append([]ReplayAction(nil), actions...)
	p.mu.Unlock()
}

// SendErrorFrame writes an unsolicited error frame, with no ref, on every live
// event socket, the way the runtime reports a frame it could not parse.
func (p *Phone) SendErrorFrame(code, message string) {
	p.mu.Lock()
	conns := make([]*websocket.Conn, 0, len(p.conns))
	for c := range p.conns {
		conns = append(conns, c)
	}
	p.mu.Unlock()
	for _, c := range conns {
		p.writeFrame(context.Background(), c, map[string]any{"type": "error", "code": code, "message": message})
	}
}

// ResetJournal empties the journal and restarts event ids at 1, as when the
// phone's event store is rebuilt. With dropSubscriptions every persisted
// subscription is forgotten too (app data cleared); otherwise they survive with
// their positions reset to the start of the new journal.
func (p *Phone) ResetJournal(dropSubscriptions bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.journal = nil
	p.nextID = 0
	p.earliestAvailable = 0
	if dropSubscriptions {
		p.subs = map[string]*subscription{}
		return
	}
	for _, s := range p.subs {
		s.CreatedAtEventID = 0
		s.AckCursor = 0
	}
}

func (p *Phone) writeFrame(ctx context.Context, conn *websocket.Conn, frame map[string]any) {
	raw, err := json.Marshal(frame)
	if err != nil {
		return
	}
	wctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	_ = conn.Write(wctx, websocket.MessageText, raw)
}

// Push appends an event to the journal and pushes it to every matching live
// subscription, unless live push is paused.
func (p *Phone) Push(topic string, payload map[string]any) Event {
	p.mu.Lock()
	p.nextID++
	ev := Event{
		ID: p.nextID, Topic: topic, TS: time.Now().UnixMilli(),
		DeviceID: DeviceID, Payload: payload,
	}
	p.journal = append(p.journal, ev)
	if p.pauseLive {
		if p.pausedFrom == 0 {
			p.pausedFrom = ev.ID
		}
		p.pausedTo = ev.ID
		p.mu.Unlock()
		return ev
	}
	type target struct {
		conn  *websocket.Conn
		subID string
	}
	var targets []target
	for _, s := range p.subs {
		if !topicMatch(s.TopicGlob, topic) || ev.ID <= s.CreatedAtEventID {
			continue
		}
		for c := range p.conns {
			targets = append(targets, target{conn: c, subID: s.ID})
		}
	}
	p.mu.Unlock()

	for _, t := range targets {
		p.writeFrame(context.Background(), t.conn, map[string]any{
			"type": "event", "subscription_id": t.subID, "event": ev,
		})
	}
	return ev
}

// PauseLive stops live pushes. Events are still journalled.
func (p *Phone) PauseLive() {
	p.mu.Lock()
	p.pauseLive = true
	p.pausedFrom, p.pausedTo = 0, 0
	p.mu.Unlock()
}

// ResumeLiveWithGap resumes live push and emits a gap frame covering everything
// that was not pushed while paused, per the protocol's backpressure rule.
func (p *Phone) ResumeLiveWithGap() {
	p.mu.Lock()
	p.pauseLive = false
	from, to := p.pausedFrom, p.pausedTo
	p.pausedFrom, p.pausedTo = 0, 0
	type target struct {
		conn  *websocket.Conn
		subID string
	}
	var targets []target
	for _, s := range p.subs {
		for c := range p.conns {
			targets = append(targets, target{conn: c, subID: s.ID})
		}
	}
	p.mu.Unlock()
	if from == 0 {
		return
	}
	for _, t := range targets {
		p.writeFrame(context.Background(), t.conn, map[string]any{
			"type": "gap", "subscription_id": t.subID, "from_id": from, "to_id": to,
		})
	}
}

// SetEarliestAvailable simulates journal retention: replays for a cursor older
// than id come back with truncated set.
func (p *Phone) SetEarliestAvailable(id int64) {
	p.mu.Lock()
	p.earliestAvailable = id
	p.mu.Unlock()
}

// SendBye sends a bye frame on every live socket and closes it.
func (p *Phone) SendBye(reason string) {
	p.mu.Lock()
	conns := make([]*websocket.Conn, 0, len(p.conns))
	for c := range p.conns {
		conns = append(conns, c)
	}
	p.mu.Unlock()
	for _, c := range conns {
		p.writeFrame(context.Background(), c, map[string]any{"type": "bye", "reason": reason})
		c.Close(websocket.StatusNormalClosure, reason)
	}
}

// ForceDrop closes every live event socket without a bye, simulating a network
// failure so the client's reconnect and replay path can be exercised.
func (p *Phone) ForceDrop() {
	p.mu.Lock()
	conns := make([]*websocket.Conn, 0, len(p.conns))
	for c := range p.conns {
		conns = append(conns, c)
	}
	p.mu.Unlock()
	for _, c := range conns {
		c.CloseNow()
	}
}

// ConnCount reports how many event sockets are currently open.
func (p *Phone) ConnCount() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.conns)
}

// AckCursor returns the phone-side ack cursor for a subscription.
func (p *Phone) AckCursor(subID string) int64 {
	p.mu.Lock()
	defer p.mu.Unlock()
	if s, ok := p.subs[subID]; ok {
		return s.AckCursor
	}
	return -1
}

// SubscriptionIDs lists the persisted subscription ids.
func (p *Phone) SubscriptionIDs() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	out := make([]string, 0, len(p.subs))
	for id := range p.subs {
		out = append(out, id)
	}
	return out
}

// --- helpers ----------------------------------------------------------------

// topicMatch implements the protocol's topic glob grammar.
func topicMatch(glob, topic string) bool {
	if glob == "" || glob == "*" {
		return true
	}
	if strings.HasSuffix(glob, "*") {
		return strings.HasPrefix(topic, strings.TrimSuffix(glob, "*"))
	}
	return glob == topic
}

func asInt64(v any) int64 {
	switch t := v.(type) {
	case float64:
		return int64(t)
	case int64:
		return t
	case int:
		return int64(t)
	case json.Number:
		n, _ := t.Int64()
		return n
	default:
		return 0
	}
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func writeRPC(w http.ResponseWriter, id any, result any) {
	writeJSON(w, http.StatusOK, map[string]any{"jsonrpc": "2.0", "id": id, "result": result})
}

func writeRPCError(w http.ResponseWriter, id any, code int, message string) {
	writeJSON(w, http.StatusOK, map[string]any{
		"jsonrpc": "2.0", "id": id,
		"error": map[string]any{"code": code, "message": message},
	})
}
