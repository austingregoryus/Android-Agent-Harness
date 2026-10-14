// Package mcpserver exposes the gateway itself as an MCP stdio server, so
// Claude Code and Claude Desktop can drive the phone through the gateway.
//
// It speaks JSON-RPC 2.0 over newline-delimited stdin/stdout, protocol version
// 2025-06-18. It proxies the phone's tools verbatim and adds three gateway
// tools: gateway.wait_for_event, gateway.list_devices and gateway.health.
package mcpserver

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/agent"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/device"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/provider"
)

// ProtocolVersion is the MCP revision this server implements.
const ProtocolVersion = "2025-06-18"

// ServerName is the name reported in initialize.
const ServerName = "android-agent-harness-gateway"

// ServerVersion is the version reported in initialize.
const ServerVersion = "0.1.0"

// Gateway tool names.
const (
	// ToolWaitForEvent subscribes to the phone's event channel and waits for
	// the first matching event.
	ToolWaitForEvent = "gateway.wait_for_event"
	// ToolListDevices reports the configured phone.
	ToolListDevices = "gateway.list_devices"
	// ToolHealth reports phone and provider health.
	ToolHealth = "gateway.health"
)

// defaultWaitTimeout bounds gateway.wait_for_event when the caller omits one.
const defaultWaitTimeout = 60 * time.Second

// maxWaitTimeout caps gateway.wait_for_event.
const maxWaitTimeout = 10 * time.Minute

// Events is the subset of the event client the server needs.
type Events interface {
	// Subscribe registers a subscription and returns its event channel.
	Subscribe(ctx context.Context, spec device.SubscribeSpec) (<-chan device.Event, func(), error)
	// WaitConnected blocks until subscriptions are attached.
	WaitConnected(ctx context.Context) error
}

// Options configures a Server.
type Options struct {
	// Device is the phone MCP client.
	Device *device.MCPClient
	// Events is the phone event channel. Nil disables gateway.wait_for_event.
	Events Events
	// Providers is the failover chain, used by gateway.health.
	Providers []provider.Provider
	// DeviceBaseURL is reported by gateway.list_devices.
	DeviceBaseURL string
	// Allowlist filters the proxied phone tools.
	Allowlist []string
	// CorrelationPrefix prefixes minted correlation ids.
	CorrelationPrefix string
	// ConverseScript is the absolute path of tools/converse.py; empty disables gateway.converse_call.
	ConverseScript string
	// Python is the interpreter used for ConverseScript (default "python").
	Python string
	// ConverseDevice is passed to ConverseScript ahead of the call arguments (--base/--token-file/--pin),
	// so the call is placed from this gateway's phone rather than the script's default.
	ConverseDevice []string
	// ConverseEnv is appended to the child's environment after the gateway's own SYMBIOSIS_* are stripped
	// (see converseEnv). Set by main.go to SYMBIOSIS_TOKEN=<resolved token> when device.token_file is empty,
	// so converse.py still gets this gateway's token rather than falling back to its own default secrets.
	ConverseEnv []string
}

// Server is the gateway's stdio MCP server.
type Server struct {
	opts Options

	mu sync.Mutex
	// handshakeDone is set when the initialize request has been answered. Every
	// other request is refused until then.
	handshakeDone bool
	// initialized is set by the notifications/initialized notification.
	initialized bool
	toolCache   []device.Tool

	// converseBusy serializes gateway.converse_call: two concurrent live calls on one phone would fight over
	// the same audio route and call session, so a second call while one is running is refused outright rather
	// than allowed to race. See converseCall in converse.go.
	converseBusy atomic.Bool
}

// New constructs a stdio MCP server.
func New(opts Options) (*Server, error) {
	if opts.Device == nil {
		return nil, errors.New("mcpserver: device client is required")
	}
	if opts.CorrelationPrefix == "" {
		opts.CorrelationPrefix = "corr_"
	}
	return &Server{opts: opts}, nil
}

// --- JSON-RPC envelopes -----------------------------------------------------

type rpcMessage struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Method  string          `json:"method,omitempty"`
	Params  json.RawMessage `json:"params,omitempty"`
}

type rpcResult struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Result  any             `json:"result,omitempty"`
	Error   *rpcErrorObject `json:"error,omitempty"`
}

type rpcErrorObject struct {
	// Code is the JSON-RPC error code.
	Code int `json:"code"`
	// Message is the human-readable error.
	Message string `json:"message"`
}

// JSON-RPC error codes used by this server.
const (
	codeParseError     = -32700
	codeInvalidRequest = -32600
	codeMethodNotFound = -32601
	codeInvalidParams  = -32602
	codeInternalError  = -32603
)

// Serve reads newline-delimited JSON-RPC messages from in and writes responses
// to out until in reaches EOF or ctx is cancelled.
//
// Each request is dispatched on its own goroutine so one slow call cannot block
// the rest of the session: gateway.wait_for_event legitimately blocks for up to
// ten minutes, and a single-threaded reader would freeze ping, tools/list and
// notifications/cancelled behind it for that whole window. Writes stay behind
// one mutex, so responses are whole lines even though they can now interleave.
//
// initialize is the one exception: it runs on the reader, so the handshake is
// strictly ordered ahead of everything that depends on it.
//
// Reading in is itself done on its own goroutine, feeding a channel, so that a
// blocked read (stdin with no data and no EOF, e.g. an interactive terminal or
// a host that never closes the pipe) cannot delay shutdown: the dispatch loop
// below selects on that channel and on ctx.Done(), so SIGTERM/SIGINT (and
// Ctrl+C on Windows, which os/signal already turns into ctx cancellation)
// makes Serve return promptly even while the reader goroutine is still stuck
// in its blocking read. That reader goroutine is intentionally leaked in that
// case; it dies with the process. Once Serve decides to stop it stops
// accepting new requests, cancels every in-flight one and drains them (via the
// deferred cancelAll+wg.Wait below) before returning; closing the device
// client is the caller's responsibility (cmd/gateway already does this with a
// fresh context, since ctx is cancelled by the time Serve returns).
func (s *Server) Serve(ctx context.Context, in io.Reader, out io.Writer) error {
	scanner := bufio.NewScanner(in)
	scanner.Buffer(make([]byte, 0, 64*1024), 16*1024*1024)

	type scanResult struct {
		line string
		err  error // non-nil (io.EOF on a clean end) signals scanning has stopped
	}
	lines := make(chan scanResult)
	go func() {
		for scanner.Scan() {
			// No ctx/done guard on this send: if the dispatch loop below has
			// already returned (ctx cancelled) nobody will ever read this
			// unbuffered channel again, so the send - and the goroutine -
			// blocks forever. That is a deliberate, harmless leak: it dies
			// with the process, and there is no portable way to abort a
			// blocking Read on in out from under this goroutine anyway.
			lines <- scanResult{line: scanner.Text()}
		}
		err := scanner.Err()
		if err == nil {
			err = io.EOF
		}
		lines <- scanResult{err: err}
	}()

	var writeMu sync.Mutex
	write := func(v any) error {
		raw, err := json.Marshal(v)
		if err != nil {
			return err
		}
		writeMu.Lock()
		defer writeMu.Unlock()
		if _, err := out.Write(append(raw, '\n')); err != nil {
			return err
		}
		if f, ok := out.(interface{ Flush() error }); ok {
			return f.Flush()
		}
		return nil
	}

	serveCtx, cancelAll := context.WithCancel(ctx)
	defer cancelAll()

	// inflight tracks the cancel function of every request still running, keyed
	// by the JSON-RPC id as it appeared on the wire, so notifications/cancelled
	// can reach it.
	var inflightMu sync.Mutex
	inflight := map[string]context.CancelFunc{}
	var wg sync.WaitGroup

	// Every dispatched request is drained before Serve returns, so a caller that
	// closes stdin never leaves a tool call running against the phone.
	defer func() {
		cancelAll()
		wg.Wait()
	}()

readLoop:
	for {
		var raw string
		select {
		case <-serveCtx.Done():
			return serveCtx.Err()
		case sr := <-lines:
			if sr.err != nil {
				if errors.Is(sr.err, io.EOF) {
					return nil
				}
				return sr.err
			}
			raw = sr.line
		}

		line := strings.TrimSpace(raw)
		if line == "" {
			continue readLoop
		}

		var msg rpcMessage
		if err := json.Unmarshal([]byte(line), &msg); err != nil {
			_ = write(rpcResult{JSONRPC: "2.0", ID: json.RawMessage("null"),
				Error: &rpcErrorObject{Code: codeParseError, Message: "parse error"}})
			continue
		}
		if msg.JSONRPC != "2.0" {
			_ = write(rpcResult{JSONRPC: "2.0", ID: nullIfEmpty(msg.ID),
				Error: &rpcErrorObject{Code: codeInvalidRequest, Message: "jsonrpc must be \"2.0\""}})
			continue
		}

		// Notifications carry no id and get no response.
		if len(msg.ID) == 0 {
			s.handleNotification(msg.Method, msg.Params, func(id string) {
				inflightMu.Lock()
				cancel := inflight[id]
				inflightMu.Unlock()
				if cancel != nil {
					cancel()
				}
			})
			continue
		}

		if msg.Method != "initialize" && !s.handshaken() {
			_ = write(rpcResult{JSONRPC: "2.0", ID: msg.ID, Error: &rpcErrorObject{
				Code:    codeInvalidRequest,
				Message: "initialize must be the first request of the session (got " + msg.Method + ")",
			}})
			continue
		}

		// initialize is answered inline so the handshake cannot be reordered
		// against the requests that depend on it.
		if msg.Method == "initialize" {
			result, rpcErr := s.dispatch(serveCtx, msg.Method, msg.Params)
			resp := rpcResult{JSONRPC: "2.0", ID: msg.ID}
			if rpcErr != nil {
				resp.Error = rpcErr
			} else {
				resp.Result = result
			}
			if err := write(resp); err != nil {
				return err
			}
			continue
		}

		id := string(msg.ID)
		reqCtx, cancelReq := context.WithCancel(serveCtx)
		inflightMu.Lock()
		if prev, dup := inflight[id]; dup {
			// A repeated in-flight id is a client bug; the older request is
			// cancelled so the map cannot leak.
			prev()
		}
		inflight[id] = cancelReq
		inflightMu.Unlock()

		wg.Add(1)
		go func(msg rpcMessage, id string, reqCtx context.Context, cancelReq context.CancelFunc) {
			defer wg.Done()
			defer func() {
				inflightMu.Lock()
				delete(inflight, id)
				inflightMu.Unlock()
				cancelReq()
			}()
			result, rpcErr := s.dispatch(reqCtx, msg.Method, msg.Params)
			resp := rpcResult{JSONRPC: "2.0", ID: msg.ID}
			if rpcErr != nil {
				resp.Error = rpcErr
			} else {
				resp.Result = result
			}
			_ = write(resp)
		}(msg, id, reqCtx, cancelReq)
	}
}

// handshaken reports whether initialize has been answered.
func (s *Server) handshaken() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.handshakeDone
}

func nullIfEmpty(id json.RawMessage) json.RawMessage {
	if len(id) == 0 {
		return json.RawMessage("null")
	}
	return id
}

// handleNotification processes a client notification. cancelRequest cancels the
// in-flight request with the given wire id, if one is running.
func (s *Server) handleNotification(method string, params json.RawMessage, cancelRequest func(id string)) {
	switch method {
	case "notifications/initialized":
		s.mu.Lock()
		s.initialized = true
		s.mu.Unlock()
	case "notifications/cancelled":
		var p struct {
			RequestID json.RawMessage `json:"requestId"`
			Reason    string          `json:"reason"`
		}
		if len(params) == 0 {
			return
		}
		if err := json.Unmarshal(params, &p); err != nil || len(p.RequestID) == 0 {
			return
		}
		// The id is matched on its verbatim wire form, the same key Serve used
		// when it registered the request, so 3 and "3" stay distinct.
		cancelRequest(strings.TrimSpace(string(p.RequestID)))
	}
}

func (s *Server) dispatch(ctx context.Context, method string, params json.RawMessage) (any, *rpcErrorObject) {
	switch method {
	case "initialize":
		s.mu.Lock()
		s.handshakeDone = true
		s.mu.Unlock()
		return map[string]any{
			"protocolVersion": ProtocolVersion,
			"capabilities": map[string]any{
				"tools": map[string]any{"listChanged": false},
			},
			"serverInfo": map[string]any{"name": ServerName, "version": ServerVersion},
		}, nil

	case "ping":
		return map[string]any{}, nil

	case "tools/list":
		tools, err := s.listTools(ctx)
		if err != nil {
			return nil, &rpcErrorObject{Code: codeInternalError, Message: err.Error()}
		}
		return map[string]any{"tools": tools}, nil

	case "tools/call":
		var p struct {
			Name      string         `json:"name"`
			Arguments map[string]any `json:"arguments"`
		}
		if len(params) > 0 {
			if err := json.Unmarshal(params, &p); err != nil {
				return nil, &rpcErrorObject{Code: codeInvalidParams, Message: err.Error()}
			}
		}
		if p.Name == "" {
			return nil, &rpcErrorObject{Code: codeInvalidParams, Message: "params.name is required"}
		}
		if p.Arguments == nil {
			p.Arguments = map[string]any{}
		}
		return s.callTool(ctx, p.Name, p.Arguments)

	default:
		return nil, &rpcErrorObject{Code: codeMethodNotFound, Message: "unknown method: " + method}
	}
}

// listTools returns the phone's tools verbatim plus the gateway tools.
func (s *Server) listTools(ctx context.Context) ([]map[string]any, error) {
	phoneTools, err := s.opts.Device.ListTools(ctx)
	if err != nil {
		return nil, fmt.Errorf("phone tools/list: %w", err)
	}
	phoneTools = agent.FilterTools(phoneTools, s.opts.Allowlist)

	s.mu.Lock()
	s.toolCache = phoneTools
	s.mu.Unlock()

	out := make([]map[string]any, 0, len(phoneTools)+3)
	for _, t := range phoneTools {
		schema := t.InputSchema
		if schema == nil {
			schema = map[string]any{"type": "object", "properties": map[string]any{}}
		}
		entry := map[string]any{
			"name":        t.Name,
			"description": t.Description,
			"inputSchema": schema,
		}
		if t.Meta != nil {
			entry["_meta"] = t.Meta
		}
		out = append(out, entry)
	}
	out = append(out, gatewayToolDefs()...)
	if s.opts.ConverseScript != "" {
		out = append(out, converseToolDef())
	}
	return out, nil
}

// gatewayToolDefs are the tools the gateway adds on top of the phone's.
func gatewayToolDefs() []map[string]any {
	return []map[string]any{
		{
			"name": ToolWaitForEvent,
			"description": "Subscribe to the phone's event channel and block until the first event matching " +
				"topic_glob (and the optional filter) arrives, then unsubscribe and return it. " +
				"Use this to wait for an inbound SMS, a call state change, or an approval resolution.",
			"inputSchema": map[string]any{
				"type": "object",
				"properties": map[string]any{
					"topic_glob": map[string]any{
						"type":        "string",
						"description": "Topic glob, e.g. messaging.sms_received, call.*, or *.",
					},
					"filter": map[string]any{
						"type":        "array",
						"description": "Optional payload predicates.",
						"items": map[string]any{
							"type": "object",
							"properties": map[string]any{
								"field": map[string]any{"type": "string"},
								"op": map[string]any{"type": "string",
									"enum": []any{"eq", "ne", "lt", "lte", "gt", "gte", "contains", "prefix"}},
								"value": map[string]any{},
							},
							"required": []any{"field", "op", "value"},
						},
					},
					"timeout_ms": map[string]any{
						"type":        "integer",
						"description": "How long to wait. Default 60000, maximum 600000.",
						"minimum":     1,
						"maximum":     600000,
					},
				},
				"required": []any{"topic_glob"},
			},
		},
		{
			"name":        ToolListDevices,
			"description": "List the phone runtimes this gateway is configured to reach, with liveness.",
			"inputSchema": map[string]any{"type": "object", "properties": map[string]any{}},
		},
		{
			"name":        ToolHealth,
			"description": "Report gateway health: the phone's /health plus each configured model provider.",
			"inputSchema": map[string]any{"type": "object", "properties": map[string]any{}},
		},
	}
}

// callTool dispatches to a gateway tool or proxies to the phone.
func (s *Server) callTool(ctx context.Context, name string, args map[string]any) (any, *rpcErrorObject) {
	switch name {
	case ToolWaitForEvent:
		return s.waitForEvent(ctx, args)
	case ToolListDevices:
		return s.listDevices(ctx)
	case ToolHealth:
		return s.health(ctx)
	case ToolConverseCall:
		return s.converseCall(ctx, args)
	}

	// Proxy to the phone. Names are passed through verbatim.
	tool, ok := s.lookupTool(ctx, name)
	if !ok {
		return nil, &rpcErrorObject{Code: codeMethodNotFound, Message: "unknown tool: " + name}
	}
	corrID := agent.NewCorrelationID(s.opts.CorrelationPrefix)
	outcome, err := s.opts.Device.CallTool(ctx, name, args, device.CallToolOptions{
		CorrelationID: corrID,
		ReadOnly:      tool.ReadOnly(),
	})
	if err != nil {
		return nil, &rpcErrorObject{Code: codeInternalError, Message: err.Error()}
	}
	// Pass the phone's envelope straight through so policy outcomes stay
	// isError tool results rather than becoming JSON-RPC errors.
	if outcome.Raw != nil {
		return outcome.Raw, nil
	}
	return textResult(outcome.Text, !outcome.OK), nil
}

func (s *Server) lookupTool(ctx context.Context, name string) (device.Tool, bool) {
	s.mu.Lock()
	cache := s.toolCache
	s.mu.Unlock()
	for _, t := range cache {
		if t.Name == name {
			return t, true
		}
	}
	tools, err := s.opts.Device.ListTools(ctx)
	if err != nil {
		return device.Tool{}, false
	}
	tools = agent.FilterTools(tools, s.opts.Allowlist)
	s.mu.Lock()
	s.toolCache = tools
	s.mu.Unlock()
	for _, t := range tools {
		if t.Name == name {
			return t, true
		}
	}
	return device.Tool{}, false
}

// waitForEvent implements gateway.wait_for_event.
func (s *Server) waitForEvent(ctx context.Context, args map[string]any) (any, *rpcErrorObject) {
	if s.opts.Events == nil {
		return errorResult("events.unavailable", "the gateway event channel is not running"), nil
	}
	glob, _ := args["topic_glob"].(string)
	if strings.TrimSpace(glob) == "" {
		return nil, &rpcErrorObject{Code: codeInvalidParams, Message: "topic_glob is required"}
	}
	timeout := defaultWaitTimeout
	if v, ok := args["timeout_ms"]; ok {
		if ms := toInt64(v); ms > 0 {
			timeout = time.Duration(ms) * time.Millisecond
		}
	}
	if timeout > maxWaitTimeout {
		timeout = maxWaitTimeout
	}

	spec := device.SubscribeSpec{TopicGlob: glob}
	if raw, ok := args["filter"]; ok {
		clauses, err := decodeFilter(raw)
		if err != nil {
			return nil, &rpcErrorObject{Code: codeInvalidParams, Message: err.Error()}
		}
		spec.Filter = clauses
	}

	waitCtx, cancelCtx := context.WithTimeout(ctx, timeout)
	defer cancelCtx()

	if err := s.opts.Events.WaitConnected(waitCtx); err != nil {
		return errorResult("events.unavailable", "event channel is not connected: "+err.Error()), nil
	}
	ch, unsubscribe, err := s.opts.Events.Subscribe(waitCtx, spec)
	if err != nil {
		return errorResult("events.subscribe_failed", err.Error()), nil
	}
	defer unsubscribe()

	select {
	case ev, ok := <-ch:
		if !ok {
			return errorResult("events.closed", "subscription closed before an event arrived"), nil
		}
		structured := map[string]any{
			"id":              ev.ID,
			"topic":           ev.Topic,
			"ts":              ev.TS,
			"device_id":       ev.DeviceID,
			"correlation_id":  ev.CorrelationID,
			"payload":         ev.Payload,
			"subscription_id": ev.SubscriptionID,
		}
		return structuredResult(structured), nil
	case <-waitCtx.Done():
		// A cancelled request and an elapsed timeout both land here; they are
		// reported apart so a client that sent notifications/cancelled can tell
		// its cancellation was honoured.
		if ctx.Err() != nil {
			return errorResult("events.cancelled",
				fmt.Sprintf("the wait for %q was cancelled by the client", glob)), nil
		}
		return errorResult("events.timeout",
			fmt.Sprintf("no event matching %q arrived within %s", glob, timeout)), nil
	}
}

func decodeFilter(raw any) ([]device.FilterClause, error) {
	arr, ok := raw.([]any)
	if !ok {
		return nil, errors.New("filter must be an array of {field, op, value}")
	}
	out := make([]device.FilterClause, 0, len(arr))
	for _, e := range arr {
		m, ok := e.(map[string]any)
		if !ok {
			return nil, errors.New("filter entries must be objects")
		}
		field, _ := m["field"].(string)
		op, _ := m["op"].(string)
		if field == "" || op == "" {
			return nil, errors.New("filter entries require field and op")
		}
		out = append(out, device.FilterClause{Field: field, Op: op, Value: m["value"]})
	}
	return out, nil
}

// listDevices implements gateway.list_devices.
func (s *Server) listDevices(ctx context.Context) (any, *rpcErrorObject) {
	entry := map[string]any{"base_url": s.opts.DeviceBaseURL}
	hctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if h, err := s.opts.Device.Health(hctx); err == nil {
		entry["ok"] = h.OK
		entry["device_id"] = h.DeviceID
		entry["version"] = h.Version
	} else {
		entry["ok"] = false
		entry["error"] = err.Error()
	}
	if sid := s.opts.Device.SessionID(); sid != "" {
		entry["mcp_session"] = "established"
	} else {
		entry["mcp_session"] = "none"
	}
	return structuredResult(map[string]any{"devices": []any{entry}}), nil
}

// health implements gateway.health.
func (s *Server) health(ctx context.Context) (any, *rpcErrorObject) {
	out := map[string]any{}
	hctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if h, err := s.opts.Device.Health(hctx); err == nil {
		out["phone"] = map[string]any{"ok": h.OK, "device_id": h.DeviceID, "version": h.Version}
	} else {
		out["phone"] = map[string]any{"ok": false, "error": err.Error()}
	}
	var provs []any
	for _, p := range s.opts.Providers {
		pctx, pcancel := context.WithTimeout(ctx, 10*time.Second)
		err := p.Health(pctx)
		pcancel()
		entry := map[string]any{"name": p.Name(), "ok": err == nil}
		if err != nil {
			entry["error"] = err.Error()
		}
		provs = append(provs, entry)
	}
	if provs == nil {
		provs = []any{}
	}
	out["providers"] = provs
	return structuredResult(out), nil
}

// --- result helpers ---------------------------------------------------------

func structuredResult(structured map[string]any) map[string]any {
	raw, err := json.Marshal(structured)
	if err != nil {
		raw = []byte("{}")
	}
	return map[string]any{
		"content":           []any{map[string]any{"type": "text", "text": string(raw)}},
		"structuredContent": structured,
		"isError":           false,
	}
}

func errorResult(code, detail string) map[string]any {
	structured := map[string]any{"error": code, "detail": detail}
	raw, _ := json.Marshal(structured)
	return map[string]any{
		"content":           []any{map[string]any{"type": "text", "text": string(raw)}},
		"structuredContent": structured,
		"isError":           true,
	}
}

func textResult(text string, isError bool) map[string]any {
	return map[string]any{
		"content": []any{map[string]any{"type": "text", "text": text}},
		"isError": isError,
	}
}

func toInt64(v any) int64 {
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
