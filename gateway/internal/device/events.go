package device

import (
	"context"
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
	SaveSubscription(key, subscriptionID string, ackCursor int64) error
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
	s.m[key] = memCursor{subID: subID, cursor: cursor}
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
	// While catching up, live events must not advance the cumulative cursor
	// past older events still in the journal. One bounded worker drains replay.
	catchingUp     bool
	recovering     bool
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
// The protocol's client frames carry no request id, so the client serializes
// its requests and matches each reply by frame type.
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

	stopOnce  sync.Once
	stop      chan struct{}
	connected chan struct{}
}

// replyWaiter is a single outstanding request awaiting a typed reply.
type replyWaiter struct {
	want string
	ch   chan json.RawMessage
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

	switch env.Type {
	case "event":
		var ev Event
		if err := json.Unmarshal(env.Event, &ev); err != nil {
			c.notify(Notice{Kind: NoticeError, Message: "undecodable event: " + err.Error()})
			return
		}
		ev.SubscriptionID = env.SubscriptionID
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
			if !c.deliverEvent(ctx, env.SubscriptionID, ev, true) {
				// Stop this batch at the first overflow: even if a consumer frees
				// space now, later entries cannot be cumulatively acknowledged.
				break
			}
		}
		c.resolve(env.Type, raw)
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
		// An error frame also terminates whatever request is outstanding.
		c.resolveAny(raw)
		return

	case "subscribed", "unsubscribed", "subscriptions", "pong":
		c.resolve(env.Type, raw)
		return
	}
}

// resolve hands raw to the outstanding request if it is waiting for this type.
func (c *EventClient) resolve(typ string, raw []byte) {
	c.mu.Lock()
	w := c.waiter
	if w == nil || w.want != typ {
		c.mu.Unlock()
		return
	}
	c.waiter = nil
	c.mu.Unlock()
	cp := make([]byte, len(raw))
	copy(cp, raw)
	select {
	case w.ch <- cp:
	default:
	}
}

// resolveAny hands raw to the outstanding request whatever it was waiting for.
func (c *EventClient) resolveAny(raw []byte) {
	c.mu.Lock()
	w := c.waiter
	c.waiter = nil
	c.mu.Unlock()
	if w == nil {
		return
	}
	cp := make([]byte, len(raw))
	copy(cp, raw)
	select {
	case w.ch <- cp:
	default:
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
	key := sub.key
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

	_ = c.store.SaveSubscription(key, subID, cursor)
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

// request writes a frame and waits for a reply of the expected type. Requests
// are serialized because the protocol's frames carry no request id.
func (c *EventClient) request(ctx context.Context, want string, frame map[string]any) ([]byte, error) {
	c.reqMu.Lock()
	defer c.reqMu.Unlock()

	w := &replyWaiter{want: want, ch: make(chan json.RawMessage, 1)}
	c.mu.Lock()
	c.waiter = w
	c.mu.Unlock()

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

	timer := time.NewTimer(requestTimeout)
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
func (c *EventClient) Subscribe(ctx context.Context, spec SubscribeSpec) (<-chan Event, func(), error) {
	key := spec.Key()
	subID, cursor, err := c.store.LoadSubscription(key)
	if err != nil {
		return nil, nil, err
	}
	sub := &subscription{
		spec:      spec,
		key:       key,
		out:       make(chan Event, 256),
		subID:     subID,
		ackCursor: cursor,
		lastSeen:  cursor,
	}
	c.mu.Lock()
	if _, exists := c.subs[key]; exists {
		c.mu.Unlock()
		return nil, nil, fmt.Errorf("event client: already subscribed to %s", spec.TopicGlob)
	}
	c.subs[key] = sub
	connected := c.conn != nil
	c.mu.Unlock()

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
		key, cursor := sub.key, sub.ackCursor
		c.mu.Unlock()
		_ = c.store.SaveSubscription(key, resp.SubscriptionID, cursor)
	}

	if err := c.replay(ctx, sub); err != nil {
		return err
	}
	c.mu.Lock()
	sub.attachedGen = gen
	c.mu.Unlock()
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
// than one worker per subscription.
func (c *EventClient) recoverReplay(ctx context.Context, sub *subscription) {
	c.mu.Lock()
	if sub.recovering || sub.closed || c.conn == nil {
		c.mu.Unlock()
		return
	}
	sub.recovering = true
	// Subscribe's request context can end as soon as attachment returns. The
	// recovery worker belongs to the connection, not to that individual call.
	ctx = c.sessionCtx
	c.mu.Unlock()
	go func() {
		defer func() { c.mu.Lock(); sub.recovering = false; c.mu.Unlock() }()
		ticker := time.NewTicker(100 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-c.stop:
				return
			case <-ticker.C:
			}
			c.mu.Lock()
			done := sub.closed || !sub.catchingUp
			space := len(sub.out) < cap(sub.out)
			c.mu.Unlock()
			if done {
				return
			}
			if space {
				if err := c.replay(ctx, sub); err != nil {
					return
				}
			}
		}
	}()
}

// replayFor issues a replay for a phone-side subscription id, recovering from a
// gap frame.
func (c *EventClient) replayFor(ctx context.Context, subID string) {
	c.mu.Lock()
	sub := c.subBySubIDLocked(subID)
	c.mu.Unlock()
	if sub == nil {
		return
	}
	_ = c.replay(ctx, sub)
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
