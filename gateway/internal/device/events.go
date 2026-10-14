package device

import (
	"context"
	crand "crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math/rand"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

// Reconnect backoff bounds for /ws/events.
const (
	reconnectMin = 250 * time.Millisecond
	reconnectMax = 30 * time.Second
)

// requestTimeout bounds how long the client waits for a reply frame.
const requestTimeout = 15 * time.Second

// Replay recovery bounds. A failed replay (timeout, error frame, send error) is
// retried with exponential backoff from replayRetryMin to replayRetryMax; after
// replayMaxFailures consecutive failures the socket is torn down so the
// reconnect path re-attaches and replays from scratch. Until a replay succeeds
// the subscription is catching up and holds back live events, so giving up
// silently would swallow every later event until the next reconnect.
const (
	replayRetryMin    = 250 * time.Millisecond
	replayRetryMax    = 5 * time.Second
	replayMaxFailures = 5
)

// FilterClause is one subscription filter predicate. Op is one of eq, ne, lt,
// lte, gt, gte, contains, prefix; Field is a top-level payload key.
type FilterClause struct {
	// Field is a top-level payload key.
	Field string `json:"field"`
	// Op is the comparison operator.
	Op string `json:"op"`
	// Value is the comparison operand.
	Value any `json:"value"`
}

// SubscribeSpec describes a subscription to create on the phone.
type SubscribeSpec struct {
	// TopicGlob selects topics, e.g. "messaging.*" or "*".
	TopicGlob string `json:"topic_glob"`
	// Filter is an optional list of payload predicates.
	Filter []FilterClause `json:"filter,omitempty"`
	// Delivery is "live" or "queue"; empty means the runtime default.
	Delivery string `json:"delivery,omitempty"`
	// TTLMs expires the subscription after this many milliseconds.
	TTLMs int64 `json:"ttl_ms,omitempty"`
	// Ephemeral makes a one-shot subscription that only delivers events
	// created after it: it neither reads nor writes the cursor store, and gets
	// a unique local key, so concurrent subscriptions with the same spec do
	// not collide. gateway.wait_for_event uses it; a durable subscription (the
	// default) resumes from its persisted cursor across gateway restarts.
	Ephemeral bool `json:"-"`
}

// Key is the stable local identity of a subscription spec. It is the lookup key
// for the persisted subscription id and ack cursor across reconnects.
func (s SubscribeSpec) Key() string {
	raw, err := canonicalJSON(map[string]any{
		"topic_glob": s.TopicGlob,
		"filter":     s.Filter,
		"delivery":   s.Delivery,
	})
	if err != nil {
		return s.TopicGlob
	}
	return string(raw)
}

// Event is an immutable fact from the phone's journal.
type Event struct {
	// ID is the global monotonically increasing journal id.
	ID int64 `json:"id"`
	// Topic is the dotted event topic.
	Topic string `json:"topic"`
	// TS is the event time in epoch milliseconds.
	TS int64 `json:"ts"`
	// DeviceID identifies the phone.
	DeviceID string `json:"device_id,omitempty"`
	// CorrelationID links the event to the tool call that caused it.
	CorrelationID string `json:"correlation_id,omitempty"`
	// Payload is the topic-specific body.
	Payload map[string]any `json:"payload,omitempty"`
	// SubscriptionID is the subscription that delivered the event. It is set by
	// the client, not carried inside the journal record.
	SubscriptionID string `json:"-"`
}

// NoticeKind classifies out-of-band notices from the event channel.
type NoticeKind string

// Notice kinds.
const (
	// NoticeGap reports that live push was paused and a replay was issued.
	NoticeGap NoticeKind = "gap"
	// NoticeTruncated reports that a replay could not reach far enough back
	// because the journal evicted events.
	NoticeTruncated NoticeKind = "truncated"
	// NoticeBye reports that the runtime closed the channel deliberately.
	NoticeBye NoticeKind = "bye"
	// NoticeError reports an error frame from the runtime.
	NoticeError NoticeKind = "error"
	// NoticeReconnect reports that the socket dropped and is being retried.
	NoticeReconnect NoticeKind = "reconnect"
	// NoticeConnected reports that subscriptions are attached on a fresh socket.
	NoticeConnected NoticeKind = "connected"
	// NoticeJournalReset reports that the phone's journal restarted below a
	// subscription's cursor (hello.last_event_id), so the cursor was reset.
	NoticeJournalReset NoticeKind = "journal_reset"
)

// Notice is an out-of-band event-channel signal surfaced to the caller.
type Notice struct {
	// Kind classifies the notice.
	Kind NoticeKind
	// SubscriptionID is the subscription concerned, when applicable.
	SubscriptionID string
	// Message is human-readable detail.
	Message string
	// FromID and ToID bound a gap.
	FromID, ToID int64
	// EarliestAvailableID is the oldest id still in the journal, reported with
	// a truncated replay result.
	EarliestAvailableID int64
}

// EventRecorder receives a copy of every event delivered to a consumer, before
// it is acknowledged. Implementations must not block for long.
type EventRecorder interface {
	// RecordEvent stores a received event. Errors are logged by the caller, not
	// propagated: persistence failures must not stall the event channel.
	RecordEvent(id int64, subscriptionID, topic string, ts int64, deviceID, correlationID string, payload any) error
}

// CursorStore persists per-subscription ack cursors so a reconnect can replay
// from the last acknowledged event.
type CursorStore interface {
	// LoadSubscription returns the persisted subscription id and ack cursor for
	// a local subscription key. Missing keys return ("", 0, nil).
	LoadSubscription(key string) (subscriptionID string, ackCursor int64, err error)
	// SaveSubscription persists the subscription id and ack cursor for a key.
	// For an unchanged subscription id the stored cursor only moves forward; a
	// new subscription id replaces it.
	SaveSubscription(key, subscriptionID string, ackCursor int64) error
	// DeleteSubscription forgets a key, so the next subscription for it starts
	// clean. Missing keys are not an error.
	DeleteSubscription(key string) error
}

// MemoryCursorStore is an in-process CursorStore, used when no SQLite store is
// supplied.
type MemoryCursorStore struct {
	mu sync.Mutex
	m  map[string]memCursor
}

type memCursor struct {
	subID  string
	cursor int64
}

// NewMemoryCursorStore constructs an in-process cursor store.
func NewMemoryCursorStore() *MemoryCursorStore {
	return &MemoryCursorStore{m: map[string]memCursor{}}
}

// LoadSubscription implements CursorStore.
func (s *MemoryCursorStore) LoadSubscription(key string) (string, int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	v, ok := s.m[key]
	if !ok {
		return "", 0, nil
	}
	return v.subID, v.cursor, nil
}

// SaveSubscription implements CursorStore.
func (s *MemoryCursorStore) SaveSubscription(key, subID string, cursor int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if old, ok := s.m[key]; ok && old.subID == subID && old.cursor > cursor {
		cursor = old.cursor
	}
	s.m[key] = memCursor{subID: subID, cursor: cursor}
	return nil
}

// DeleteSubscription implements CursorStore.
func (s *MemoryCursorStore) DeleteSubscription(key string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.m, key)
	return nil
}

// subscription is one active local subscription.
//
// Every mutable field below is guarded by EventClient.mu, including out: the
// channel is only ever sent on, and only ever closed, while that mutex is held,
// which is what makes "cancel during delivery" safe. Sends are non-blocking, so
// holding the mutex across one cannot stall the read loop.
type subscription struct {
	spec SubscribeSpec
	key  string
	out  chan Event
	// subID is the phone-assigned subscription id for the current attachment.
	subID string
	// lastSeen is the highest event id delivered to the consumer. It is the
	// de-duplication watermark for interleaved replay and live push.
	lastSeen int64
	// ackCursor is the highest id cumulatively acknowledged to the phone. It
	// only ever advances past an event the consumer actually received, so a
	// dropped event is re-delivered by the next replay rather than lost.
	ackCursor int64
	closed    bool
	// durable subscriptions persist their id and cursor in the CursorStore;
	// ephemeral ones (SubscribeSpec.Ephemeral) never touch it.
	durable bool
	// While catching up, live events must not advance the cumulative cursor
	// past older events still in the journal. One bounded worker drains replay.
	catchingUp bool
	// recoverGen is the connection generation of the running recovery worker,
	// or 0 when none runs. Keying it by generation means a worker still winding
	// down on a dead socket cannot stop a fresh one starting on the next.
	recoverGen     uint64
	pendingThrough int64
	// attachedGen is the connection generation this subscription is bound to.
	// It stops Subscribe and attachAll from both subscribing the same spec when
	// they race on a fresh socket.
	attachedGen uint64
	// attachMu serializes attach attempts for this one subscription.
	attachMu sync.Mutex
}

// EventClient is the /ws/events WebSocket client.
//
// It owns a single socket, re-establishes it with exponential backoff and
// jitter (250ms to 30s), re-attaches persisted subscriptions on reconnect,
// replays from the persisted ack cursor, de-duplicates by event id, honours
// gap frames by issuing a replay, and surfaces bye and truncated as notices.
//
// The client serializes its requests. Each carries a "ref" the runtime echoes on
// its reply, which is how a reply (and above all an error frame) is matched to
// its request; against a runtime that echoes no ref the client falls back to
// matching by frame type.
type EventClient struct {
	baseURL  string
	token    string
	store    CursorStore
	recorder EventRecorder
	dialer   *websocket.DialOptions
	notices  chan Notice

	// reqMu serializes request/reply exchanges.
	reqMu sync.Mutex
	// writeMu serializes socket writes.
	writeMu sync.Mutex

	mu         sync.Mutex
	subs       map[string]*subscription
	conn       *websocket.Conn
	sessionCtx context.Context
	waiter     *replyWaiter
	running    bool
	// connGen increments on every successful dial. A subscription attached in an
	// earlier generation must re-attach.
	connGen uint64
	// attached is true while a socket is up with every subscription bound.
	attached bool
	// nextRef numbers request refs.
	nextRef uint64
	// refsEchoed is set once the runtime on the current socket has echoed a
	// ref. From then on an error frame without a ref is not a reply to the
	// outstanding request and is not handed to it.
	refsEchoed bool
	// helloSeen and highWater describe the phone's journal on the current
	// socket: highWater is the hello frame's last_event_id, raised by every
	// event id seen since. A cursor above it predates a journal reset.
	helloSeen bool
	highWater int64

	// reqTimeout, replayRetryMin, replayRetryMax and replayMaxFailures are the
	// package defaults, held per client so tests can shorten them.
	reqTimeout        time.Duration
	replayRetryMin    time.Duration
	replayRetryMax    time.Duration
	replayMaxFailures int

	stopOnce  sync.Once
	stop      chan struct{}
	connected chan struct{}
}

// replyWaiter is a single outstanding request awaiting a typed reply.
type replyWaiter struct {
	want string
	// ref is the request's ref, or "" for a waiter that matches by type only.
	ref string
	ch  chan json.RawMessage
}

// EventOptions configures an EventClient.
type EventOptions struct {
	// BaseURL is the phone runtime root, e.g. http://127.0.0.1:8765.
	BaseURL string
	// Token is the bearer token. It is never logged.
	Token string
	// Recorder receives a copy of every delivered event. Optional.
	Recorder EventRecorder
	// Store persists ack cursors. Nil means an in-memory store.
	Store CursorStore
	// HTTPClient is used for the WebSocket handshake.
	HTTPClient *http.Client
}

// NewEventClient constructs an event-channel client. Call Run to start it.
func NewEventClient(opts EventOptions) *EventClient {
	c := &EventClient{
		baseURL:   strings.TrimRight(opts.BaseURL, "/"),
		token:     opts.Token,
		store:     opts.Store,
		recorder:  opts.Recorder,
		notices:   make(chan Notice, 128),
		subs:      map[string]*subscription{},
		stop:      make(chan struct{}),
		connected: make(chan struct{}),

		reqTimeout:        requestTimeout,
		replayRetryMin:    replayRetryMin,
		replayRetryMax:    replayRetryMax,
		replayMaxFailures: replayMaxFailures,
	}
	if c.store == nil {
		c.store = NewMemoryCursorStore()
	}
	hdr := http.Header{}
	if opts.Token != "" {
		hdr.Set("Authorization", "Bearer "+opts.Token)
	}
	c.dialer = &websocket.DialOptions{HTTPHeader: hdr, HTTPClient: opts.HTTPClient}
	return c
}

// Notices returns the out-of-band notice channel (gap, truncated, bye, error,
// reconnect, connected). It is buffered; notices are dropped rather than
// blocking the read loop.
func (c *EventClient) Notices() <-chan Notice { return c.notices }

func (c *EventClient) notify(n Notice) {
	select {
	case c.notices <- n:
	default:
	}
}

// wsURL converts the configured HTTP base URL into a WebSocket URL.
func (c *EventClient) wsURL() string {
	u := c.baseURL
	switch {
	case strings.HasPrefix(u, "https://"):
		u = "wss://" + strings.TrimPrefix(u, "https://")
	case strings.HasPrefix(u, "http://"):
		u = "ws://" + strings.TrimPrefix(u, "http://")
	}
	return u + "/ws/events"
}

// Run maintains the socket until ctx is cancelled or Close is called.
func (c *EventClient) Run(ctx context.Context) error {
	c.mu.Lock()
	if c.running {
		c.mu.Unlock()
		return errors.New("event client: already running")
	}
	c.running = true
	c.mu.Unlock()

	backoff := reconnectMin
	for {
		if c.stopped(ctx) {
			return ctx.Err()
		}
		start := time.Now()
		err := c.session(ctx)
		if c.stopped(ctx) {
			return ctx.Err()
		}
		if time.Since(start) > 30*time.Second {
			backoff = reconnectMin
		}
		msg := "socket closed"
		if err != nil {
			msg = err.Error()
		}
		c.notify(Notice{Kind: NoticeReconnect, Message: msg})

		jitter := time.Duration(rand.Int63n(int64(backoff/2) + 1))
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-c.stop:
			return nil
		case <-time.After(backoff + jitter):
		}
		if backoff < reconnectMax {
			backoff *= 2
			if backoff > reconnectMax {
				backoff = reconnectMax
			}
		}
	}
}

func (c *EventClient) stopped(ctx context.Context) bool {
	select {
	case <-ctx.Done():
		return true
	case <-c.stop:
		return true
	default:
		return false
	}
}

// session runs one connection: dial, attach subscriptions, read until failure.
func (c *EventClient) session(ctx context.Context) error {
	sessCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	conn, _, err := websocket.Dial(sessCtx, c.wsURL(), c.dialer)
	if err != nil {
		return err
	}
	conn.SetReadLimit(8 << 20)
	defer conn.CloseNow()

	c.mu.Lock()
	c.conn = conn
	c.sessionCtx = sessCtx
	c.waiter = nil
	c.connGen++
	c.refsEchoed = false
	c.helloSeen = false
	c.highWater = 0
	for _, sub := range c.subs {
		sub.catchingUp = true
	}
	c.mu.Unlock()
	defer func() {
		c.mu.Lock()
		c.conn = nil
		c.waiter = nil
		if c.attached {
			// Losing the socket un-attaches every subscription; arm a fresh
			// channel so WaitConnected blocks again until the next attach.
			c.attached = false
			c.connected = make(chan struct{})
		}
		c.mu.Unlock()
	}()

	readErr := make(chan error, 1)
	go func() {
		for {
			_, data, err := conn.Read(sessCtx)
			if err != nil {
				readErr <- err
				return
			}
			c.handleFrame(sessCtx, data)
		}
	}()

	// Attach on a goroutine so the read loop above is already draining replies.
	attachDone := make(chan error, 1)
	go func() { attachDone <- c.attachAll(sessCtx) }()

	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-c.stop:
			return nil
		case err := <-readErr:
			return err
		case err := <-attachDone:
			if err != nil {
				return fmt.Errorf("attach subscriptions: %w", err)
			}
			c.mu.Lock()
			ch := c.connected
			newlyAttached := !c.attached
			c.attached = true
			c.mu.Unlock()
			if newlyAttached {
				close(ch)
			}
			c.notify(Notice{Kind: NoticeConnected, Message: "subscriptions attached"})
			attachDone = nil
		}
	}
}

// WaitConnected blocks until subscriptions have been attached on a socket. It
// returns immediately when a socket is already up.
func (c *EventClient) WaitConnected(ctx context.Context) error {
	c.mu.Lock()
	if c.attached {
		c.mu.Unlock()
		return nil
	}
	ch := c.connected
	c.mu.Unlock()
	select {
	case <-ch:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	case <-c.stop:
		return errors.New("event client: closed")
	}
}

// frameEnvelope is the common shape of runtime-to-client frames from
// protocol.md section 4.3.
type frameEnvelope struct {
	Type           string          `json:"type"`
	SubscriptionID string          `json:"subscription_id"`
	Event          json.RawMessage `json:"event"`
	// Ref echoes the request's ref on a reply.
	Ref json.RawMessage `json:"ref"`
	// hello
	LastEventID int64 `json:"last_event_id"`
	// replay_result
	Events              []Event `json:"events"`
	NextSinceID         int64   `json:"next_since_id"`
	Truncated           bool    `json:"truncated"`
	EarliestAvailableID int64   `json:"earliest_available_id"`
	// gap
	FromID int64 `json:"from_id"`
	ToID   int64 `json:"to_id"`
	// error
	Code    string `json:"code"`
	Message string `json:"message"`
	// bye
	Reason string `json:"reason"`
}

func (c *EventClient) handleFrame(ctx context.Context, raw []byte) {
	var env frameEnvelope
	if err := json.Unmarshal(raw, &env); err != nil {
		c.notify(Notice{Kind: NoticeError, Message: "undecodable frame: " + err.Error()})
		return
	}

	ref := frameRef(env.Ref)

	switch env.Type {
	case "hello":
		c.handleHello(env.LastEventID)
		return

	case "event":
		var ev Event
		if err := json.Unmarshal(env.Event, &ev); err != nil {
			c.notify(Notice{Kind: NoticeError, Message: "undecodable event: " + err.Error()})
			return
		}
		ev.SubscriptionID = env.SubscriptionID
		c.raiseHighWater(ev.ID)
		c.deliver(ctx, env.SubscriptionID, ev)
		return

	case "replay_result":
		// Delivery is non-blocking. Finish it before resolving the request so
		// the next replay observes the cursor from this batch.
		if env.Truncated {
			c.notify(Notice{
				Kind:                NoticeTruncated,
				SubscriptionID:      env.SubscriptionID,
				EarliestAvailableID: env.EarliestAvailableID,
				Message:             "journal evicted events before the requested cursor",
			})
		}
		for _, ev := range env.Events {
			ev.SubscriptionID = env.SubscriptionID
			c.raiseHighWater(ev.ID)
			if !c.deliverEvent(ctx, env.SubscriptionID, ev, true) {
				// Stop this batch at the first overflow: even if a consumer frees
				// space now, later entries cannot be cumulatively acknowledged.
				break
			}
		}
		c.resolve(env.Type, ref, raw)
		return

	case "gap":
		c.notify(Notice{
			Kind: NoticeGap, SubscriptionID: env.SubscriptionID,
			FromID: env.FromID, ToID: env.ToID,
			Message: "live push paused; replaying from ack cursor",
		})
		go c.replayFor(ctx, env.SubscriptionID)
		return

	case "bye":
		c.notify(Notice{Kind: NoticeBye, Message: env.Reason})
		return

	case "error":
		c.notify(Notice{Kind: NoticeError, SubscriptionID: env.SubscriptionID, Message: env.Code + ": " + env.Message})
		// An error frame terminates the outstanding request only when it is
		// that request's reply.
		c.resolveError(ref, raw)
		return

	case "subscribed", "unsubscribed", "subscriptions", "pong":
		c.resolve(env.Type, ref, raw)
		return
	}
}

// frameRef renders a reply's echoed ref. The client sends string refs, which
// come back as JSON strings; anything else is compared by its raw text.
func frameRef(raw json.RawMessage) string {
	if len(raw) == 0 || string(raw) == "null" {
		return ""
	}
	var s string
	if err := json.Unmarshal(raw, &s); err == nil {
		return s
	}
	return string(raw)
}

// resolve hands raw to the outstanding request if it is this reply's request:
// the echoed ref matches, or, when the reply carries no ref, the type does.
func (c *EventClient) resolve(typ, ref string, raw []byte) {
	c.mu.Lock()
	if ref != "" {
		// Only this client sends refs on this socket, so any echoed ref
		// proves the runtime echoes them.
		c.refsEchoed = true
	}
	w := c.waiter
	if w == nil || w.want != typ || (ref != "" && ref != w.ref) {
		c.mu.Unlock()
		return
	}
	c.waiter = nil
	c.mu.Unlock()
	c.handOff(w, raw)
}

// resolveError hands an error frame to the outstanding request when it is that
// request's reply. With a ref, the ref decides. Without one it is that
// request's reply only on a runtime that echoes no refs at all; once refs are
// echoed, a ref-less error (an unparseable frame, an internal fault) belongs to
// no request, and failing an unrelated pending replay with it would leave that
// subscription catching up for nothing.
func (c *EventClient) resolveError(ref string, raw []byte) {
	c.mu.Lock()
	if ref != "" {
		c.refsEchoed = true
	}
	w := c.waiter
	if w == nil || (ref != "" && ref != w.ref) || (ref == "" && c.refsEchoed && w.ref != "") {
		c.mu.Unlock()
		return
	}
	c.waiter = nil
	c.mu.Unlock()
	c.handOff(w, raw)
}

func (c *EventClient) handOff(w *replyWaiter, raw []byte) {
	cp := make([]byte, len(raw))
	copy(cp, raw)
	select {
	case w.ch <- cp:
	default:
	}
}

// raiseHighWater records that event id exists in the phone's current journal.
func (c *EventClient) raiseHighWater(id int64) {
	c.mu.Lock()
	if id > c.highWater {
		c.highWater = id
	}
	c.mu.Unlock()
}

// handleHello processes the runtime's first frame on a socket. Its
// last_event_id is the newest id in the phone's journal; a subscription whose
// cursor is above it was acknowledged against a journal that no longer exists
// (app data cleared, journal rebuilt), and keeping that cursor would make the
// client drop every new event as a duplicate. Such cursors restart from zero.
//
// hello is sent before the runtime reads any client frame, and every replay on
// a socket follows at least one reply on it (list or subscribe), so this always
// runs before the socket's first replay reads a cursor.
func (c *EventClient) handleHello(lastEventID int64) {
	type reset struct {
		key, subID string
		durable    bool
	}
	var resets []reset
	c.mu.Lock()
	c.helloSeen = true
	if lastEventID > c.highWater {
		c.highWater = lastEventID
	}
	for _, sub := range c.subs {
		if sub.ackCursor > lastEventID || sub.lastSeen > lastEventID {
			sub.ackCursor, sub.lastSeen, sub.pendingThrough = 0, 0, 0
			resets = append(resets, reset{key: sub.key, subID: sub.subID, durable: sub.durable})
		}
	}
	c.mu.Unlock()
	for _, r := range resets {
		if r.durable {
			// Delete first: for an unchanged subscription id the store keeps
			// the larger cursor.
			_ = c.store.DeleteSubscription(r.key)
			if r.subID != "" {
				_ = c.store.SaveSubscription(r.key, r.subID, 0)
			}
		}
		c.notify(Notice{
			Kind: NoticeJournalReset, SubscriptionID: r.subID, ToID: lastEventID,
			Message: "phone journal restarted below this subscription's cursor; replaying from the start",
		})
	}
}

// deliver de-duplicates by event id, hands the event to the consumer and
// cumulatively acknowledges it.
//
// The handoff is non-blocking. A consumer that is not reading (or is gone) must
// never stall the socket read loop, because the loop is also what carries the
// replies attach and replay are waiting for: blocking here on a 600-event replay
// with nobody draining the channel deadlocks the client against itself. When the
// buffer is full the event is dropped and a gap notice is raised instead, and
// the ack cursor is deliberately left where it was so the phone still holds the
// event and the next replay re-delivers it.
func (c *EventClient) deliver(ctx context.Context, subID string, ev Event) {
	c.deliverEvent(ctx, subID, ev, false)
}

func (c *EventClient) deliverEvent(ctx context.Context, subID string, ev Event, replay bool) bool {
	c.mu.Lock()
	sub := c.subBySubIDLocked(subID)
	if sub == nil || sub.closed || ev.ID <= sub.lastSeen {
		c.mu.Unlock()
		return true
	}
	if sub.catchingUp && !replay {
		if ev.ID > sub.pendingThrough {
			sub.pendingThrough = ev.ID
		}
		c.mu.Unlock()
		return false
	}
	key, durable := sub.key, sub.durable
	c.mu.Unlock()

	if c.recorder != nil {
		if err := c.recorder.RecordEvent(ev.ID, subID, ev.Topic, ev.TS, ev.DeviceID, ev.CorrelationID, ev.Payload); err != nil {
			c.notify(Notice{Kind: NoticeError, SubscriptionID: subID, Message: "record event: " + err.Error()})
		}
	}

	// The subscription may have been cancelled while the recorder ran, so every
	// field is re-checked under the lock that also guards close(sub.out).
	c.mu.Lock()
	if sub.closed || ev.ID <= sub.lastSeen {
		c.mu.Unlock()
		return true
	}
	delivered := false
	select {
	case sub.out <- ev:
		delivered = true
		sub.lastSeen = ev.ID
		if ev.ID > sub.ackCursor {
			sub.ackCursor = ev.ID
		}
	default:
		sub.catchingUp = true
		if ev.ID > sub.pendingThrough {
			sub.pendingThrough = ev.ID
		}
	}
	cursor := sub.ackCursor
	c.mu.Unlock()

	if !delivered {
		c.notify(Notice{
			Kind: NoticeGap, SubscriptionID: subID,
			FromID: ev.ID, ToID: ev.ID,
			Message: "no consumer ready for this subscription; event left unacknowledged for replay",
		})
		c.recoverReplay(ctx, sub)
		return false
	}

	if durable {
		_ = c.store.SaveSubscription(key, subID, cursor)
	}
	_ = c.send(ctx, map[string]any{"type": "ack", "subscription_id": subID, "through_id": cursor})
	return true
}

func (c *EventClient) subBySubIDLocked(subID string) *subscription {
	for _, s := range c.subs {
		if s.subID == subID {
			return s
		}
	}
	return nil
}

// send writes one JSON frame to the current socket.
func (c *EventClient) send(ctx context.Context, frame map[string]any) error {
	c.mu.Lock()
	conn := c.conn
	c.mu.Unlock()
	if conn == nil {
		return errors.New("event client: not connected")
	}
	raw, err := json.Marshal(frame)
	if err != nil {
		return err
	}
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	return conn.Write(ctx, websocket.MessageText, raw)
}

// request writes a frame and waits for its reply. The frame is stamped with a
// fresh ref, which the runtime echoes on the reply (and on an error answering
// it). Requests are still serialized, so a runtime that echoes no ref can be
// matched by reply type.
func (c *EventClient) request(ctx context.Context, want string, frame map[string]any) ([]byte, error) {
	c.reqMu.Lock()
	defer c.reqMu.Unlock()

	c.mu.Lock()
	c.nextRef++
	w := &replyWaiter{want: want, ref: fmt.Sprintf("g%d", c.nextRef), ch: make(chan json.RawMessage, 1)}
	c.waiter = w
	c.mu.Unlock()
	frame["ref"] = w.ref

	clear := func() {
		c.mu.Lock()
		if c.waiter == w {
			c.waiter = nil
		}
		c.mu.Unlock()
	}

	if err := c.send(ctx, frame); err != nil {
		clear()
		return nil, err
	}

	timer := time.NewTimer(c.reqTimeout)
	defer timer.Stop()
	select {
	case raw := <-w.ch:
		return raw, nil
	case <-timer.C:
		clear()
		return nil, fmt.Errorf("event client: timed out waiting for %s reply", want)
	case <-ctx.Done():
		clear()
		return nil, ctx.Err()
	case <-c.stop:
		clear()
		return nil, errors.New("event client: closed")
	}
}

// Subscribe registers a subscription. The returned channel receives matching
// events until the returned cancel function is called or the client stops.
// Subscribing before the socket is up is fine: the subscription attaches on the
// next successful connect.
//
// A durable spec (the default) resumes from the subscription id and cursor
// persisted for its Key, and only one durable subscription per spec may be
// active. An ephemeral spec starts fresh every time, and any number may be
// active at once.
func (c *EventClient) Subscribe(ctx context.Context, spec SubscribeSpec) (<-chan Event, func(), error) {
	key := spec.Key()
	durable := !spec.Ephemeral
	var subID string
	var cursor int64
	if durable {
		var err error
		if subID, cursor, err = c.store.LoadSubscription(key); err != nil {
			return nil, nil, err
		}
	} else {
		key += "#" + randomSuffix()
	}
	sub := &subscription{
		spec:      spec,
		key:       key,
		out:       make(chan Event, 256),
		subID:     subID,
		ackCursor: cursor,
		lastSeen:  cursor,
		durable:   durable,
	}
	c.mu.Lock()
	if _, exists := c.subs[key]; exists {
		c.mu.Unlock()
		return nil, nil, fmt.Errorf("event client: already subscribed to %s", spec.TopicGlob)
	}
	// A cursor above anything the current journal holds predates a journal
	// reset (see handleHello).
	stale := c.helloSeen && cursor > c.highWater
	if stale {
		sub.ackCursor, sub.lastSeen = 0, 0
	}
	c.subs[key] = sub
	connected := c.conn != nil
	c.mu.Unlock()
	if stale {
		_ = c.store.DeleteSubscription(key)
	}

	// attach is idempotent per connection generation, so it does not matter
	// whether this call or a concurrent attachAll on a freshly dialled socket
	// gets there first: exactly one subscribe frame goes out.

	if connected {
		if err := c.attach(ctx, sub); err != nil {
			c.mu.Lock()
			delete(c.subs, key)
			c.mu.Unlock()
			return nil, nil, err
		}
	}

	var once sync.Once
	cancel := func() {
		once.Do(func() {
			// closed is set and out is closed under the same mutex deliver holds
			// while it sends, so a cancel that races an in-flight delivery can
			// neither send on a closed channel nor close one mid-send.
			c.mu.Lock()
			s, ok := c.subs[key]
			if !ok {
				c.mu.Unlock()
				return
			}
			s.closed = true
			id := s.subID
			delete(c.subs, key)
			close(s.out)
			c.mu.Unlock()
			if s.durable {
				// The phone-side subscription is going away, so its cursor
				// means nothing; a later subscription to the spec starts clean.
				_ = c.store.DeleteSubscription(key)
			}
			if id != "" {
				uctx, ucancel := context.WithTimeout(context.Background(), 5*time.Second)
				_, _ = c.request(uctx, "unsubscribed", map[string]any{"type": "unsubscribe", "subscription_id": id})
				ucancel()
			}
		})
	}
	return sub.out, cancel, nil
}

// attachAll re-attaches every registered subscription on a fresh socket.
func (c *EventClient) attachAll(ctx context.Context) error {
	c.mu.Lock()
	subs := make([]*subscription, 0, len(c.subs))
	for _, s := range c.subs {
		subs = append(subs, s)
	}
	c.mu.Unlock()
	for _, s := range subs {
		if err := c.attach(ctx, s); err != nil {
			return err
		}
	}
	return nil
}

// attach binds one subscription to the current socket.
//
// Subscriptions are persisted phone-side and survive a dropped socket, so the
// client reuses its previous subscription id when the runtime still lists it.
// That matters for replay: a fresh subscription's created_at_event_id would
// prevent replay from reaching behind it. Either way the client replays from the
// persisted ack cursor and relies on id de-duplication to absorb the overlap
// with live pushes.
func (c *EventClient) attach(ctx context.Context, sub *subscription) error {
	// One attach at a time per subscription, and at most one per connection
	// generation: Subscribe attaches a brand new subscription itself, while
	// attachAll re-attaches everything on a fresh socket, and without this the
	// two race into a duplicate subscribe (two phone-side subscriptions, double
	// delivery, and a stale id in the cursor store).
	sub.attachMu.Lock()
	defer sub.attachMu.Unlock()

	c.mu.Lock()
	gen := c.connGen
	if gen == 0 || (sub.attachedGen == gen && !sub.closed) {
		done := gen != 0
		c.mu.Unlock()
		if done {
			return nil
		}
		return errors.New("event client: not connected")
	}
	knownID := sub.subID
	c.mu.Unlock()

	reuse := false
	if knownID != "" {
		if ids, err := c.listSubscriptionIDs(ctx); err == nil {
			for _, id := range ids {
				if id == knownID {
					reuse = true
					break
				}
			}
		}
	}

	if !reuse {
		frame := map[string]any{"type": "subscribe", "topic_glob": sub.spec.TopicGlob}
		if len(sub.spec.Filter) > 0 {
			frame["filter"] = sub.spec.Filter
		}
		if sub.spec.Delivery != "" {
			frame["delivery"] = sub.spec.Delivery
		}
		if sub.spec.TTLMs > 0 {
			frame["ttl_ms"] = sub.spec.TTLMs
		}
		raw, err := c.request(ctx, "subscribed", frame)
		if err != nil {
			return err
		}
		var resp struct {
			Type             string `json:"type"`
			SubscriptionID   string `json:"subscription_id"`
			CreatedAtEventID int64  `json:"created_at_event_id"`
			Code             string `json:"code"`
			Message          string `json:"message"`
		}
		if err := json.Unmarshal(raw, &resp); err != nil {
			return err
		}
		if resp.Type != "subscribed" || resp.SubscriptionID == "" {
			return fmt.Errorf("event client: subscribe failed: %s %s", resp.Code, resp.Message)
		}
		c.mu.Lock()
		sub.subID = resp.SubscriptionID
		// A new phone-side subscription can never replay behind its creation
		// point, so that is where its cursor starts. Carrying the old cursor
		// over would, after a phone journal reset, drop the new subscription's
		// low-numbered events as duplicates.
		sub.ackCursor = resp.CreatedAtEventID
		sub.lastSeen = resp.CreatedAtEventID
		sub.pendingThrough = 0
		key, cursor, durable := sub.key, sub.ackCursor, sub.durable
		c.mu.Unlock()
		if durable {
			_ = c.store.SaveSubscription(key, resp.SubscriptionID, cursor)
		}
	}

	c.mu.Lock()
	sub.attachedGen = gen
	c.mu.Unlock()
	if err := c.replay(ctx, sub); err != nil {
		// The subscription is bound; only its catch-up failed. Hand it to the
		// recovery worker, which retries and, if the socket stays unusable,
		// reconnects. Failing the attach instead would leave a phone-side
		// subscription behind with nothing local to clean it up.
		c.notify(Notice{Kind: NoticeError, SubscriptionID: sub.subID, Message: "replay failed; retrying: " + err.Error()})
		c.recoverReplay(ctx, sub)
	}
	return nil
}

// listSubscriptionIDs asks the runtime which subscriptions this peer owns.
func (c *EventClient) listSubscriptionIDs(ctx context.Context) ([]string, error) {
	raw, err := c.request(ctx, "subscriptions", map[string]any{"type": "list"})
	if err != nil {
		return nil, err
	}
	var resp struct {
		Subscriptions []struct {
			SubscriptionID string `json:"subscription_id"`
		} `json:"subscriptions"`
	}
	if err := json.Unmarshal(raw, &resp); err != nil {
		return nil, err
	}
	out := make([]string, 0, len(resp.Subscriptions))
	for _, s := range resp.Subscriptions {
		out = append(out, s.SubscriptionID)
	}
	return out, nil
}

// replay asks the runtime to re-send everything after the persisted cursor.
func (c *EventClient) replay(ctx context.Context, sub *subscription) error {
	c.mu.Lock()
	id, since := sub.subID, sub.ackCursor
	sub.catchingUp = true
	c.mu.Unlock()
	if id == "" {
		return nil
	}
	raw, err := c.request(ctx, "replay_result", map[string]any{
		"type":            "replay",
		"subscription_id": id,
		"since_id":        since,
		"limit":           200,
	})
	if err == nil {
		var reply frameEnvelope
		if err = json.Unmarshal(raw, &reply); err == nil && reply.Type != "replay_result" {
			err = fmt.Errorf("event client: replay failed: %s %s", reply.Code, reply.Message)
		}
		if err == nil && reply.SubscriptionID != id {
			err = fmt.Errorf("event client: replay_result for subscription %q, requested %q", reply.SubscriptionID, id)
		}
		if err == nil {
			c.mu.Lock()
			// A short page is the end only when everything in it fit and no
			// newer live event was deferred while that page was in flight.
			last := since
			if len(reply.Events) > 0 {
				last = reply.Events[len(reply.Events)-1].ID
			}
			if reply.Truncated && reply.EarliestAvailableID > sub.pendingThrough {
				sub.pendingThrough = 0 // eviction was explicitly reported to the caller
			}
			sub.catchingUp = len(reply.Events) >= 200 || sub.lastSeen < last || sub.lastSeen < sub.pendingThrough
			needsReplay := sub.catchingUp
			c.mu.Unlock()
			if needsReplay {
				c.recoverReplay(ctx, sub)
			}
		}
	}
	return err
}

// recoverReplay retries in bounded pages once consumers make room. It keeps
// the socket reader available for ping/cancellation, and never starts more
// than one worker per subscription and connection.
//
// A failed replay (timeout, an error frame, a send error) is retried with
// bounded exponential backoff; after replayMaxFailures in a row the worker
// closes the socket, so the reconnect path re-attaches and replays. The worker
// only stops once the subscription has caught up, is cancelled, or its socket
// is gone (and with it the reconnect that will catch it up), so catchingUp
// can never stay set with nobody working to clear it.
func (c *EventClient) recoverReplay(ctx context.Context, sub *subscription) {
	c.mu.Lock()
	if sub.closed || c.conn == nil || sub.recoverGen == c.connGen {
		c.mu.Unlock()
		return
	}
	gen := c.connGen
	sub.recoverGen = gen
	conn := c.conn
	// Subscribe's request context can end as soon as attachment returns. The
	// recovery worker belongs to the connection, not to that individual call.
	ctx = c.sessionCtx
	c.mu.Unlock()
	go func() {
		// releaseLocked must run under c.mu, in the same critical section as
		// the check that decided to stop, so a catch-up that starts right
		// after the check finds no worker registered and starts a new one.
		releaseLocked := func() {
			if sub.recoverGen == gen {
				sub.recoverGen = 0
			}
		}
		release := func() { c.mu.Lock(); releaseLocked(); c.mu.Unlock() }
		const poll = 100 * time.Millisecond
		wait := poll
		failures := 0
		for {
			timer := time.NewTimer(wait)
			select {
			case <-ctx.Done():
				timer.Stop()
				release()
				return
			case <-c.stop:
				timer.Stop()
				release()
				return
			case <-timer.C:
			}
			wait = poll
			c.mu.Lock()
			if sub.closed || !sub.catchingUp {
				releaseLocked()
				c.mu.Unlock()
				return
			}
			space := len(sub.out) < cap(sub.out)
			c.mu.Unlock()
			if !space {
				continue
			}
			err := c.replay(ctx, sub)
			if err == nil {
				failures = 0
				continue
			}
			failures++
			if failures >= c.replayMaxFailures {
				release()
				c.notify(Notice{Kind: NoticeError, SubscriptionID: sub.subIDSnapshot(c),
					Message: fmt.Sprintf("replay failed %d times in a row; reconnecting: %v", failures, err)})
				// Closing the socket ends this session; the reconnect marks
				// every subscription as catching up and replays it on attach.
				conn.CloseNow()
				return
			}
			wait = c.replayRetryMin << (failures - 1)
			if wait > c.replayRetryMax || wait <= 0 {
				wait = c.replayRetryMax
			}
		}
	}()
}

// subIDSnapshot reads the subscription's phone-side id under c.mu.
func (s *subscription) subIDSnapshot(c *EventClient) string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return s.subID
}

// replayFor issues a replay for a phone-side subscription id, recovering from a
// gap frame. A failed replay is handed to the recovery worker.
func (c *EventClient) replayFor(ctx context.Context, subID string) {
	c.mu.Lock()
	sub := c.subBySubIDLocked(subID)
	c.mu.Unlock()
	if sub == nil {
		return
	}
	if err := c.replay(ctx, sub); err != nil {
		c.recoverReplay(ctx, sub)
	}
}

// randomSuffix returns 16 random hex digits for a unique local key.
func randomSuffix() string {
	var b [8]byte
	if _, err := crand.Read(b[:]); err != nil {
		return fmt.Sprintf("%x", time.Now().UnixNano())
	}
	return hex.EncodeToString(b[:])
}

// Ping issues a heartbeat and waits for the pong.
func (c *EventClient) Ping(ctx context.Context) error {
	_, err := c.request(ctx, "pong", map[string]any{"type": "ping"})
	return err
}

// Close stops the client and closes the socket.
func (c *EventClient) Close() {
	c.stopOnce.Do(func() {
		close(c.stop)
		c.mu.Lock()
		conn := c.conn
		c.conn = nil
		c.mu.Unlock()
		if conn != nil {
			conn.Close(websocket.StatusNormalClosure, "gateway shutting down")
		}
	})
}
