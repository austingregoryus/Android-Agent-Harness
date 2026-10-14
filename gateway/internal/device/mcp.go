// Package device implements the gateway's client side of the Android Agent Harness
// protocol: the MCP Streamable HTTP surface (docs/protocol.md section 3), the
// /ws/events channel (section 4.3), pairing (section 2) and /health.
package device

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptrace"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ProtocolVersion is the MCP protocol revision the gateway speaks.
const ProtocolVersion = "2025-06-18"

// JSONRPCVersion is the JSON-RPC envelope version.
const JSONRPCVersion = "2.0"

// rpcRequest is a JSON-RPC 2.0 request or notification.
type rpcRequest struct {
	JSONRPC string `json:"jsonrpc"`
	ID      any    `json:"id,omitempty"`
	Method  string `json:"method"`
	Params  any    `json:"params,omitempty"`
}

// rpcError is a JSON-RPC 2.0 error object.
type rpcError struct {
	// Code is the JSON-RPC error code.
	Code int `json:"code"`
	// Message is the human-readable error.
	Message string `json:"message"`
	// Data is optional structured detail.
	Data json.RawMessage `json:"data,omitempty"`
}

// Error implements the error interface.
func (e *rpcError) Error() string {
	return fmt.Sprintf("jsonrpc error %d: %s", e.Code, e.Message)
}

// rpcResponse is a JSON-RPC 2.0 response.
type rpcResponse struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      any             `json:"id,omitempty"`
	Result  json.RawMessage `json:"result,omitempty"`
	Error   *rpcError       `json:"error,omitempty"`
}

// ToolAnnotations are the MCP behavioural hints on a tool.
type ToolAnnotations struct {
	// Title is a human-readable tool title.
	Title string `json:"title,omitempty"`
	// ReadOnlyHint is true when the tool has no side effects.
	ReadOnlyHint bool `json:"readOnlyHint,omitempty"`
	// DestructiveHint is true when the tool may destroy data.
	DestructiveHint bool `json:"destructiveHint,omitempty"`
	// IdempotentHint is true when repeating the call is safe.
	IdempotentHint bool `json:"idempotentHint,omitempty"`
	// OpenWorldHint is true when the tool touches the outside world.
	OpenWorldHint bool `json:"openWorldHint,omitempty"`
}

// SymbiosisMeta is the runtime's per-tool metadata from _meta.symbiosis.
type SymbiosisMeta struct {
	// ActionClass is the policy action class, e.g. COMMUNICATION.
	ActionClass string `json:"action_class,omitempty"`
	// Tier is the privilege tier required.
	Tier string `json:"tier,omitempty"`
	// RateClass names the rate-limit bucket.
	RateClass string `json:"rate_class,omitempty"`
	// EmitsEvents lists topics the tool can produce.
	EmitsEvents []string `json:"emits_events,omitempty"`
	// Source records a droid-mcp passthrough origin.
	Source string `json:"source,omitempty"`
}

// Tool is one phone capability as reported by tools/list.
type Tool struct {
	// Name is the dotted capability name, e.g. messaging.send_sms.
	Name string `json:"name"`
	// Description is model-readable prose.
	Description string `json:"description"`
	// InputSchema is the JSON Schema for the arguments.
	InputSchema map[string]any `json:"inputSchema"`
	// Annotations are the MCP behavioural hints.
	Annotations ToolAnnotations `json:"annotations"`
	// Meta carries the raw _meta object.
	Meta map[string]any `json:"_meta,omitempty"`
}

// Symbiosis decodes the _meta.symbiosis block, if present.
func (t Tool) Symbiosis() SymbiosisMeta {
	var out SymbiosisMeta
	if t.Meta == nil {
		return out
	}
	raw, err := json.Marshal(t.Meta["symbiosis"])
	if err != nil {
		return out
	}
	_ = json.Unmarshal(raw, &out)
	return out
}

// ReadOnly reports whether the tool is free of side effects. It trusts
// annotations.readOnlyHint first and falls back to the action class.
func (t Tool) ReadOnly() bool {
	if t.Annotations.ReadOnlyHint {
		return true
	}
	return strings.EqualFold(t.Symbiosis().ActionClass, "READ_ONLY")
}

// ToolOutcome is a decoded tools/call result. Policy outcomes arrive as tool
// results with isError set, not as JSON-RPC errors, so they land here rather
// than in the returned error.
type ToolOutcome struct {
	// OK is true when isError was false.
	OK bool
	// Structured is the structuredContent object, if any.
	Structured map[string]any
	// Text is the concatenated text content blocks.
	Text string
	// ErrorCode is structuredContent.error, e.g. policy.approval_required.
	ErrorCode string
	// PendingActionID is set for policy.approval_required outcomes.
	PendingActionID string
	// RetryAfterMs is set for policy.rate_limited outcomes.
	RetryAfterMs int64
	// Permissions is set for permission.missing outcomes.
	Permissions []string
	// Detail is set for capability.failed outcomes.
	Detail string
	// CorrelationID echoes _meta.correlationId, or the id the call was sent
	// with when the phone echoed none.
	CorrelationID string
	// IdempotencyKey is the key the call was sent with, or "" for a read-only
	// call. It is set by CallTool, not decoded.
	IdempotencyKey string
	// AuditID echoes _meta.auditId.
	AuditID int64
	// DurationMs echoes _meta.durationMs.
	DurationMs int64
	// Raw is the undecoded result object.
	Raw map[string]any
}

// HealthResult is the /health response.
type HealthResult struct {
	// OK reports liveness.
	OK bool `json:"ok"`
	// DeviceID identifies the phone.
	DeviceID string `json:"device_id"`
	// Version is the runtime app version.
	Version string `json:"version"`
	// TLSFingerprintSHA256 is the SHA-256 fingerprint of the certificate
	// presented by the phone's TLS-only connector (colon-separated uppercase
	// hex), or "" when TLS is off. JSON null decodes to "".
	TLSFingerprintSHA256 string `json:"tls_fingerprint_sha256"`
}

// InitializeResult is the MCP initialize result.
type InitializeResult struct {
	// ProtocolVersion is the version the runtime agreed to.
	ProtocolVersion string `json:"protocolVersion"`
	// Capabilities is the server capability object.
	Capabilities map[string]any `json:"capabilities"`
	// ServerInfo names and versions the runtime.
	ServerInfo struct {
		// Name is the server name, "android-agent-harness-runtime".
		Name string `json:"name"`
		// Version is the app version.
		Version string `json:"version"`
	} `json:"serverInfo"`
	// Meta carries _meta, including _meta.symbiosis.protocol.
	Meta map[string]any `json:"_meta,omitempty"`
}

// MCPClient is the phone's MCP Streamable HTTP client.
//
// It manages the Mcp-Session-Id handshake, stamps correlation and idempotency
// metadata on tools/call, and re-initializes exactly once when the runtime
// reports the session is gone (HTTP 404).
type MCPClient struct {
	baseURL string
	token   string
	client  *http.Client
	// requestTimeout bounds one request to the phone. It is applied per request
	// (not as http.Client.Timeout) so that a call that legitimately runs longer
	// — automation.run, up to 300 s on the phone — can be given its own bound
	// while everything else keeps the short one. Zero means no bound.
	requestTimeout time.Duration

	mu        sync.Mutex
	sessionID string
	nextID    int64
	serverInf InitializeResult
	// sessionGen increments on every successful initialize. It lets a caller
	// that saw a dead session tell "nobody has fixed it yet" from "another
	// goroutine already re-initialized while I was waiting".
	sessionGen uint64

	// initMu makes initialize single-flight. Without it, concurrent callers
	// (the MCP stdio server now dispatches requests in parallel) each open their
	// own session and all but the last one is orphaned on the phone.
	initMu sync.Mutex
}

// MCPOptions configures an MCPClient.
type MCPOptions struct {
	// BaseURL is the phone runtime root, e.g. http://127.0.0.1:8765.
	BaseURL string
	// Token is the bearer token. It is never logged.
	Token string
	// HTTPClient overrides the default client.
	HTTPClient *http.Client
}

// NewMCPClient constructs an MCP Streamable HTTP client for the phone.
func NewMCPClient(opts MCPOptions) *MCPClient {
	c := &MCPClient{
		baseURL: strings.TrimRight(opts.BaseURL, "/"),
		token:   opts.Token,
	}
	switch {
	case opts.HTTPClient == nil:
		c.client = &http.Client{}
		c.requestTimeout = DefaultRequestTimeout
	default:
		// http.Client.Timeout is a hard cap on every request and cannot be
		// raised for one call, so move it into requestTimeout and run on a
		// copy without it. The caller's client (shared with the WebSocket
		// dialer) is left untouched.
		cp := *opts.HTTPClient
		c.requestTimeout = cp.Timeout
		cp.Timeout = 0
		c.client = &cp
	}
	return c
}

// DefaultRequestTimeout bounds an ordinary request to the phone.
const DefaultRequestTimeout = 60 * time.Second

// Batch limits mirrored from the runtime's automation.run
// (android-runtime automation/Steps.kt): default and maximum whole-batch
// deadline. A batch may legitimately take up to the maximum, so the HTTP
// request carrying it must outlive it.
const (
	automationDefaultTimeout = 60 * time.Second
	automationMaxTimeout     = 300 * time.Second
	// automationMargin covers the phone finishing the step that was running at
	// the deadline and writing its audit rows before it answers.
	automationMargin = 30 * time.Second
)

// callTimeout is the request bound for one tools/call: the client default,
// except automation.run, which gets its own timeout_ms (clamped as the phone
// clamps it) plus a margin so the gateway never gives up on a batch the phone
// is still legitimately running.
func (c *MCPClient) callTimeout(name string, args map[string]any) time.Duration {
	if name != "automation.run" {
		return c.requestTimeout
	}
	d := automationDefaultTimeout
	if ms, ok := asFloat(args["timeout_ms"]); ok {
		d = time.Duration(ms) * time.Millisecond
	}
	if d < time.Second {
		d = time.Second
	}
	if d > automationMaxTimeout {
		d = automationMaxTimeout
	}
	d += automationMargin
	if c.requestTimeout > d {
		return c.requestTimeout
	}
	return d
}

func asFloat(v any) (float64, bool) {
	switch n := v.(type) {
	case float64:
		return n, true
	case int:
		return float64(n), true
	case int64:
		return float64(n), true
	case json.Number:
		f, err := n.Float64()
		return f, err == nil
	}
	return 0, false
}

type requestTimeoutKey struct{}

// withRequestTimeout overrides the per-request bound for requests made with ctx.
func withRequestTimeout(ctx context.Context, d time.Duration) context.Context {
	return context.WithValue(ctx, requestTimeoutKey{}, d)
}

// SessionID returns the current MCP session id, or "" before initialize.
func (c *MCPClient) SessionID() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.sessionID
}

// ServerInfo returns the result of the last successful initialize.
func (c *MCPClient) ServerInfo() InitializeResult {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.serverInf
}

// session returns the current session id together with the generation it
// belongs to.
func (c *MCPClient) session() (string, uint64) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.sessionID, c.sessionGen
}

func (c *MCPClient) allocID() int64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.nextID++
	return c.nextID
}

// httpError is a non-2xx HTTP response from the runtime.
type httpError struct {
	// Status is the HTTP status code.
	Status int
	// Body is a truncated response body with no credential material.
	Body string
}

// Error implements the error interface.
func (e *httpError) Error() string {
	return fmt.Sprintf("phone returned HTTP %d: %s", e.Status, e.Body)
}

// maybeSentError marks a failure that happened after the request may have
// reached the phone: the bytes were written (or a response, however broken,
// came back), so the phone may have acted on it. Its Error text is the wrapped
// error's, so callers that only print errors see no difference.
type maybeSentError struct{ err error }

func (e *maybeSentError) Error() string { return e.err.Error() }
func (e *maybeSentError) Unwrap() error { return e.err }

// post sends one JSON-RPC message to POST /mcp.
//
// Errors raised once the request may have been written are wrapped in
// maybeSentError, so CallTool can tell "never reached the phone" from "the
// phone may have executed it". HTTP 4xx answers are the runtime refusing the
// request before dispatch (unknown session, auth, bad header) and stay plain;
// 5xx answers can come from a handler that already ran and are wrapped.
func (c *MCPClient) post(ctx context.Context, msg rpcRequest, isInitialize bool) (*rpcResponse, error) {
	raw, err := json.Marshal(msg)
	if err != nil {
		return nil, err
	}
	timeout := c.requestTimeout
	if d, ok := ctx.Value(requestTimeoutKey{}).(time.Duration); ok {
		timeout = d
	}
	if timeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, timeout)
		defer cancel()
	}
	// wrote records whether the transport attempted to write the request at
	// all. Until it has, a failure (dial, TLS, a cancelled context) cannot have
	// reached the phone. A failed write still counts: some bytes may have left.
	var wroteMu sync.Mutex
	wrote := false
	trace := &httptrace.ClientTrace{WroteRequest: func(httptrace.WroteRequestInfo) {
		wroteMu.Lock()
		wrote = true
		wroteMu.Unlock()
	}}
	mayHaveSent := func() bool {
		wroteMu.Lock()
		defer wroteMu.Unlock()
		return wrote
	}
	sent := func(err error) error { return &maybeSentError{err: err} }
	req, err := http.NewRequestWithContext(httptrace.WithClientTrace(ctx, trace), http.MethodPost, c.baseURL+"/mcp", bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	// Streamable HTTP lets a server answer a POST with either a JSON body or an
	// SSE stream. The runtime always answers with JSON in v0, but the spec
	// requires the client to declare both, and a strict server rejects a client
	// that does not.
	req.Header.Set("Accept", "application/json, text/event-stream")
	req.Header.Set("MCP-Protocol-Version", ProtocolVersion)
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	if !isInitialize {
		if sid := c.SessionID(); sid != "" {
			req.Header.Set("Mcp-Session-Id", sid)
		}
	}

	resp, err := c.client.Do(req)
	if err != nil {
		err = fmt.Errorf("phone %s: %w", msg.Method, err)
		if mayHaveSent() {
			return nil, sent(err)
		}
		return nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		return nil, sent(fmt.Errorf("phone %s: read response: %w", msg.Method, err))
	}

	// Notifications are answered with 202 and an empty body.
	if resp.StatusCode == http.StatusAccepted && msg.ID == nil {
		return nil, nil
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		he := &httpError{Status: resp.StatusCode, Body: truncate(string(body), 400)}
		if resp.StatusCode >= 500 {
			return nil, sent(he)
		}
		return nil, he
	}
	if len(bytes.TrimSpace(body)) == 0 {
		if msg.ID == nil {
			return nil, nil
		}
		return nil, sent(fmt.Errorf("phone %s: missing JSON-RPC response", msg.Method))
	}
	var out rpcResponse
	if err := json.Unmarshal(body, &out); err != nil {
		return nil, sent(fmt.Errorf("phone %s: decode response: %w", msg.Method, err))
	}
	wantID, _ := json.Marshal(msg.ID)
	gotID, _ := json.Marshal(out.ID)
	if out.JSONRPC != JSONRPCVersion || !bytes.Equal(wantID, gotID) {
		return nil, sent(fmt.Errorf("phone %s: invalid JSON-RPC version or response id", msg.Method))
	}
	if out.Error != nil {
		return &out, out.Error
	}
	if len(out.Result) == 0 || bytes.Equal(bytes.TrimSpace(out.Result), []byte("null")) {
		return nil, sent(fmt.Errorf("phone %s: missing JSON-RPC result", msg.Method))
	}
	if isInitialize {
		if sid := resp.Header.Get("Mcp-Session-Id"); sid != "" {
			c.mu.Lock()
			c.sessionID = sid
			c.sessionGen++
			c.mu.Unlock()
		}
	}
	return &out, nil
}

// call performs one request, transparently re-initializing once if the runtime
// reports the session is unknown (HTTP 404, per protocol.md section 3).
func (c *MCPClient) call(ctx context.Context, method string, params any, out any) error {
	do := func() (*rpcResponse, error) {
		return c.post(ctx, rpcRequest{
			JSONRPC: JSONRPCVersion,
			ID:      c.allocID(),
			Method:  method,
			Params:  params,
		}, false)
	}

	_, gen := c.session()
	resp, err := do()
	if err != nil {
		var he *httpError
		if errors.As(err, &he) && he.Status == http.StatusNotFound {
			// The handshake gets the ordinary per-request bound, not the budget of the
			// call that tripped the 404 (automation.run's can be minutes).
			if ierr := c.reinitialize(withRequestTimeout(ctx, c.requestTimeout), gen); ierr != nil {
				// The 404 means the call itself was refused, and initialize never
				// carries it, so a broken handshake leaves the call unsent.
				var ms *maybeSentError
				if errors.As(ierr, &ms) {
					ierr = ms.err
				}
				return fmt.Errorf("phone %s: session expired and re-initialize failed: %w", method, ierr)
			}
			resp, err = do()
		}
	}
	if err != nil {
		return err
	}
	if out == nil || resp == nil || len(resp.Result) == 0 {
		return nil
	}
	if err := json.Unmarshal(resp.Result, out); err != nil {
		// The phone answered, so it may well have acted; only the result is unreadable.
		return &maybeSentError{err: fmt.Errorf("phone %s: decode result: %w", method, err)}
	}
	return nil
}

func isHTTPError(err error, target **httpError) bool {
	// errors.As, not a bare type assertion: the error may have been wrapped on
	// the way up, and a bare assertion silently reports "not an HTTP error" for a
	// wrapped 404, which turns a recoverable expired session into a hard failure.
	return errors.As(err, target)
}

// Initialize performs the MCP handshake and records the session id.
func (c *MCPClient) Initialize(ctx context.Context) (InitializeResult, error) {
	params := map[string]any{
		"protocolVersion": ProtocolVersion,
		"capabilities":    map[string]any{},
		"clientInfo": map[string]any{
			"name":    "android-agent-harness-gateway",
			"version": "0.1.0",
		},
	}
	resp, err := c.post(ctx, rpcRequest{
		JSONRPC: JSONRPCVersion,
		ID:      c.allocID(),
		Method:  "initialize",
		Params:  params,
	}, true)
	if err != nil {
		return InitializeResult{}, err
	}
	var out InitializeResult
	if resp != nil && len(resp.Result) > 0 {
		if err := json.Unmarshal(resp.Result, &out); err != nil {
			return InitializeResult{}, fmt.Errorf("phone initialize: decode result: %w", err)
		}
	}
	c.mu.Lock()
	c.serverInf = out
	c.mu.Unlock()

	// Per MCP, follow initialize with the initialized notification.
	_, _ = c.post(ctx, rpcRequest{
		JSONRPC: JSONRPCVersion,
		Method:  "notifications/initialized",
	}, false)
	return out, nil
}

// ensureSession initializes the session if it is not already established.
func (c *MCPClient) ensureSession(ctx context.Context) error {
	sid, gen := c.session()
	if sid != "" {
		return nil
	}
	return c.reinitialize(ctx, gen)
}

// reinitialize establishes a session, unless another caller already replaced the
// one the caller saw at generation seenGen.
//
// Concurrent callers funnel through initMu; the first performs the handshake and
// bumps the generation, and the rest observe a newer generation with a live
// session and return without opening a second one.
func (c *MCPClient) reinitialize(ctx context.Context, seenGen uint64) error {
	c.initMu.Lock()
	defer c.initMu.Unlock()

	sid, gen := c.session()
	if sid != "" && gen != seenGen {
		return nil
	}
	c.mu.Lock()
	c.sessionID = ""
	c.mu.Unlock()
	_, err := c.Initialize(ctx)
	return err
}

// Ping issues an MCP ping.
func (c *MCPClient) Ping(ctx context.Context) error {
	if err := c.ensureSession(ctx); err != nil {
		return err
	}
	return c.call(ctx, "ping", map[string]any{}, nil)
}

// ListTools fetches the scope-filtered tool list from the phone.
func (c *MCPClient) ListTools(ctx context.Context) ([]Tool, error) {
	if err := c.ensureSession(ctx); err != nil {
		return nil, err
	}
	var out struct {
		Tools []Tool `json:"tools"`
	}
	if err := c.call(ctx, "tools/list", map[string]any{}, &out); err != nil {
		return nil, err
	}
	return out.Tools, nil
}

// CallToolOptions carries the per-call metadata protocol.md requires.
type CallToolOptions struct {
	// CorrelationID is stamped on the call, its audit rows and derived events.
	CorrelationID string
	// ReadOnly suppresses the idempotency key; the protocol only requires one
	// for non-read-only calls.
	ReadOnly bool
	// ApprovalWaitMs overrides the runtime's approval wait, when non-zero.
	ApprovalWaitMs int
}

// IdempotencyKey computes sha256(correlation_id | tool | canonical_args) as a
// lowercase hex string, per protocol.md section 3.
func IdempotencyKey(correlationID, tool string, args map[string]any) (string, error) {
	canonical, err := canonicalJSON(args)
	if err != nil {
		return "", err
	}
	h := sha256.New()
	h.Write([]byte(correlationID))
	h.Write([]byte("|"))
	h.Write([]byte(tool))
	h.Write([]byte("|"))
	h.Write(canonical)
	return hex.EncodeToString(h.Sum(nil)), nil
}

// CallErrorKind classifies a failed tools/call by what the phone may have done.
type CallErrorKind int

// Call error kinds.
const (
	// CallNotSent means the phone did not execute the call: the request was
	// never written (no session, dial or TLS failure, cancelled first), or the
	// runtime refused the HTTP request before dispatch (4xx). Retrying is safe.
	CallNotSent CallErrorKind = iota
	// CallOutcomeUnknown means the request may have reached the phone and the
	// phone may have acted, but no usable result came back (a timeout or a
	// dropped connection after the write, a 5xx, an undecodable response).
	// Retrying with the same idempotency key is deduplicated by the phone;
	// retrying with a different one may repeat the side effect.
	CallOutcomeUnknown
	// CallRejected means the phone answered with a JSON-RPC error. Those come
	// from dispatch checks (unknown tool, invalid arguments) that run before
	// anything executes.
	CallRejected
)

// CallError is the error CallTool returns when it has no tool result. Policy
// outcomes are not CallErrors; they arrive as a ToolOutcome.
type CallError struct {
	// Kind says whether the phone may have executed the call.
	Kind CallErrorKind
	// Tool is the tool name.
	Tool string
	// CorrelationID is the correlation id the call carried.
	CorrelationID string
	// IdempotencyKey is the key the call carried, or "" for read-only calls.
	IdempotencyKey string
	// RPCCode and RPCMessage are the phone's JSON-RPC error when Kind is
	// CallRejected.
	RPCCode    int
	RPCMessage string
	// Err is the underlying failure.
	Err error
}

// Error implements the error interface.
func (e *CallError) Error() string { return e.Err.Error() }

// Unwrap exposes the underlying failure.
func (e *CallError) Unwrap() error { return e.Err }

// newCallError classifies a tools/call failure.
func newCallError(err error, name, corrID, key string) *CallError {
	ce := &CallError{Kind: CallNotSent, Tool: name, CorrelationID: corrID, IdempotencyKey: key, Err: err}
	var re *rpcError
	var ms *maybeSentError
	switch {
	case errors.As(err, &re):
		ce.Kind = CallRejected
		ce.RPCCode = re.Code
		ce.RPCMessage = re.Message
	case errors.As(err, &ms):
		ce.Kind = CallOutcomeUnknown
	}
	return ce
}

// CallTool invokes a phone tool. Policy outcomes are returned as a ToolOutcome
// with OK false and ErrorCode set; transport and JSON-RPC failures are returned
// as a *CallError that says whether the phone may have executed the call.
func (c *MCPClient) CallTool(ctx context.Context, name string, args map[string]any, opts CallToolOptions) (ToolOutcome, error) {
	if args == nil {
		args = map[string]any{}
	}
	meta := map[string]any{}
	if opts.CorrelationID != "" {
		meta["correlationId"] = opts.CorrelationID
	}
	key := ""
	if !opts.ReadOnly {
		var err error
		key, err = IdempotencyKey(opts.CorrelationID, name, args)
		if err != nil {
			return ToolOutcome{}, newCallError(fmt.Errorf("phone tools/call %s: idempotency key: %w", name, err), name, opts.CorrelationID, "")
		}
		meta["idempotencyKey"] = key
	}
	if err := c.ensureSession(ctx); err != nil {
		// Initialize never carries the tool call, so whatever went wrong, the
		// phone has not seen it.
		return ToolOutcome{}, &CallError{Kind: CallNotSent, Tool: name, CorrelationID: opts.CorrelationID, IdempotencyKey: key, Err: err}
	}
	if opts.ApprovalWaitMs > 0 {
		meta["approvalWaitMs"] = opts.ApprovalWaitMs
	}
	params := map[string]any{
		"name":      name,
		"arguments": args,
	}
	if len(meta) > 0 {
		params["_meta"] = meta
	}

	var raw map[string]any
	if err := c.call(withRequestTimeout(ctx, c.callTimeout(name, args)), "tools/call", params, &raw); err != nil {
		return ToolOutcome{}, newCallError(err, name, opts.CorrelationID, key)
	}
	out := decodeToolOutcome(raw)
	if out.CorrelationID == "" {
		out.CorrelationID = opts.CorrelationID
	}
	out.IdempotencyKey = key
	return out, nil
}

// ErrInvalidResult is the ToolOutcome.ErrorCode for a phone result the gateway
// cannot interpret (no object structuredContent, a mistyped isError or error).
// The call reached the phone, so its outcome is unknown.
const ErrInvalidResult = "protocol.invalid_result"

// decodeToolOutcome maps a tools/call result envelope into a typed outcome.
func decodeToolOutcome(raw map[string]any) ToolOutcome {
	out := ToolOutcome{OK: true, Raw: raw}
	// This client speaks to the phone runtime, which always returns structuredContent.
	// A valid RPC envelope alone is not evidence that an effect succeeded.
	if _, ok := raw["structuredContent"].(map[string]any); !ok {
		out.OK = false
		out.ErrorCode = ErrInvalidResult
		out.Detail = "phone tool result is missing object structuredContent; outcome unknown"
		return out
	}
	if v, exists := raw["isError"]; exists {
		if _, ok := v.(bool); !ok {
			out.OK = false
			out.ErrorCode = ErrInvalidResult
			out.Detail = "phone tool result has invalid isError; outcome unknown"
			return out
		}
	}
	if v, ok := raw["isError"].(bool); ok {
		out.OK = !v
	}
	if sc, ok := raw["structuredContent"].(map[string]any); ok {
		out.Structured = sc
		if value, exists := sc["error"]; exists && value != nil {
			if _, valid := value.(string); !valid {
				out.OK = false
				out.ErrorCode = ErrInvalidResult
				out.Detail = "phone tool result has invalid error field; outcome unknown"
				return out
			}
		}
		if s, ok := sc["error"].(string); ok {
			out.ErrorCode = s
			if s != "" {
				out.OK = false
			}
		}
		if s, ok := sc["pending_action_id"].(string); ok {
			out.PendingActionID = s
		}
		if s, ok := sc["detail"].(string); ok {
			out.Detail = s
		}
		out.RetryAfterMs = asInt64(sc["retry_after_ms"])
		if perms, ok := sc["permissions"].([]any); ok {
			for _, p := range perms {
				if s, ok := p.(string); ok {
					out.Permissions = append(out.Permissions, s)
				}
			}
		}
	}
	if content, ok := raw["content"].([]any); ok {
		var b strings.Builder
		for _, blk := range content {
			m, ok := blk.(map[string]any)
			if !ok {
				continue
			}
			if m["type"] == "text" {
				if s, ok := m["text"].(string); ok {
					if b.Len() > 0 {
						b.WriteString("\n")
					}
					b.WriteString(s)
				}
			}
		}
		out.Text = b.String()
	}
	if meta, ok := raw["_meta"].(map[string]any); ok {
		if s, ok := meta["correlationId"].(string); ok {
			out.CorrelationID = s
		}
		out.AuditID = asInt64(meta["auditId"])
		out.DurationMs = asInt64(meta["durationMs"])
	}
	return out
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
	case string:
		n, _ := strconv.ParseInt(t, 10, 64)
		return n
	default:
		return 0
	}
}

// Close ends the MCP session with DELETE /mcp.
func (c *MCPClient) Close(ctx context.Context) error {
	if c.requestTimeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, c.requestTimeout)
		defer cancel()
	}
	sid := c.SessionID()
	if sid == "" {
		return nil
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodDelete, c.baseURL+"/mcp", nil)
	if err != nil {
		return err
	}
	req.Header.Set("Mcp-Session-Id", sid)
	req.Header.Set("MCP-Protocol-Version", ProtocolVersion)
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	resp, err := c.client.Do(req)
	if err != nil {
		return err
	}
	io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
	resp.Body.Close()
	c.mu.Lock()
	c.sessionID = ""
	c.mu.Unlock()
	return nil
}

// Health calls the unauthenticated GET /health endpoint.
func (c *MCPClient) Health(ctx context.Context) (HealthResult, error) {
	if c.requestTimeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, c.requestTimeout)
		defer cancel()
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.baseURL+"/health", nil)
	if err != nil {
		return HealthResult{}, err
	}
	resp, err := c.client.Do(req)
	if err != nil {
		return HealthResult{}, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != http.StatusOK {
		return HealthResult{}, &httpError{Status: resp.StatusCode, Body: truncate(string(body), 200)}
	}
	var out HealthResult
	if err := json.Unmarshal(body, &out); err != nil {
		return HealthResult{}, fmt.Errorf("phone /health: decode: %w", err)
	}
	return out, nil
}

func truncate(s string, n int) string {
	s = strings.TrimSpace(s)
	if len(s) <= n {
		return s
	}
	return s[:n] + "..."
}
