# Android Agent Harness Protocol v0

The wire contract between an **Agent Gateway** (or any MCP client) and the **Android Embodiment Runtime**.
Selected JSON Schemas live in `protocol/schemas`. This document describes the intended current contract;
report disagreements with the implementation rather than assuming a documented safeguard is enforced.

## 1. Transport overview

| Surface | Path | Purpose |
|---|---|---|
| MCP Streamable HTTP | `POST /mcp`, `GET /mcp`, `DELETE /mcp` | Discrete tools and resources (JSON-RPC 2.0). |
| Event channel | `GET /ws/events` (WebSocket) | Subscriptions, live push, ack, replay. |
| Session channel | `GET /ws/sessions/{session_id}` (WebSocket) | Long-lived sessions (calls). JSON frames + binary audio frames. |
| Pairing | `POST /pair` | Exchange a one-time code for a scoped bearer token. |
| Health | `GET /health` | Unauthenticated liveness: `{ "ok": true, "device_id": "...", "version": "..." }`. |

Default bind: `127.0.0.1:8765`. Reaching the phone from a workstation goes through `adb reverse`/`adb forward`
or an overlay network (Tailscale). Binding to a non-loopback interface is a user-visible toggle and requires TLS
(self-signed, fingerprint pinned by the client). Plaintext on a LAN interface is never the default.

## 2. Authentication and scopes

Every request except `/health` and `/pair` carries `Authorization: Bearer <token>`.

Peer tokens are random 32-byte values, base64url. The phone stores only `sha256(token)` for paired peers. A token belongs to a
**peer** (row in `trusted_peers`):

```
peer_id            TEXT PRIMARY KEY     -- "peer_" + 12 hex
label              TEXT                 -- human label ("homelab-gateway")
token_sha256       TEXT UNIQUE
scopes             TEXT                 -- JSON array of capability globs, e.g. ["messaging.*","device.get_*","events.*"]
max_action_class   TEXT                 -- ceiling: READ_ONLY | AUTOMATION | SENSITIVE_READ | LOW_RISK | COMMUNICATION | EXTERNAL_COMMITMENT | DEVICE_MUTATION | DESTRUCTIVE
created_at, expires_at, revoked_at, last_seen_at   INTEGER (epoch ms, nullable)
```

**Scope grammar.** Within a dotted segment, `*` matches zero or more non-dot characters. Bare `*` matches everything.
A trailing `.*` also matches any nonempty suffix beneath its literal prefix (`messaging.*` matches
`messaging.send_sms` and deeper names); `device.get_*` matches `device.get_battery`. A tool is visible to a peer iff at least one scope matches **and**
the tool's action class ≤ the peer's `max_action_class`. `tools/list` is filtered by this rule; `tools/call` on a
hidden tool returns JSON-RPC error `-32601` (method/tool not found) so a peer cannot enumerate by probing.

**Admin token.** Created on first run and retained so the unlocked UI can reveal it repeatedly with **Show admin token**.
SecureStore normally uses Keystore-backed encrypted preferences; see [storage limitations](security.md#limits).
Scopes `["*"]`, ceiling `DESTRUCTIVE`. Pairing-code issuance is a local UI operation; redeeming a code at `/pair` is unauthenticated.

**Pairing.** The UI mints a one-time code (8 chars, 5-minute TTL, single use). A client calls:

```
POST /pair
{ "code": "K7Q2-9F4A", "label": "homelab-gateway", "scopes": ["messaging.*","telephony.*","device.*","events.*","contacts.*","notifications.*"], "max_action_class": "COMMUNICATION" }
→ 200 { "peer_id": "peer_...", "token": "<shown once>", "scopes": [...], "max_action_class": "COMMUNICATION", "expires_at": null }
```

Requested scopes are clamped to what the code was minted for (the UI chooses when it mints). Revoking a peer closes
its WebSockets and ends its sessions within one second.

## 3. MCP Streamable HTTP

Implements MCP protocol version `2025-06-18` (accepts `2025-03-26`). Rules the runtime follows:

- `POST /mcp` body is a single JSON-RPC request, response, or notification. **Batch arrays are rejected** with
  HTTP 400 `{ "error": "batch not supported" }`. Clients on 2025-03-26 that batch will get a clear error.
- Notifications (`notifications/initialized`, `notifications/cancelled`) → HTTP `202`, empty body.
- Requests → HTTP `200`, `Content-Type: application/json`, one JSON-RPC response. (SSE streaming of a POST
  response is not used in v0; the runtime always answers with plain JSON.)
- `initialize` result carries `Mcp-Session-Id` (random) in the response header. Subsequent requests send it back.
  The session is bound to the `peer_id` that created it; a mismatched token gets HTTP 401. `DELETE /mcp` ends it.
  A missing/unknown session id on a non-initialize request → HTTP 404 (client re-initializes).
- `GET /mcp` → HTTP 405. The runtime does not push server-initiated MCP messages; events use `/ws/events`.
- `MCP-Protocol-Version` request header is validated when present and echoed on responses.
- Correlation: header `Mcp-Correlation-Id` **or** `params._meta.correlationId` on `tools/call`. If absent the
  runtime mints `corr_<16 hex>`. The effective id is echoed in `result._meta.correlationId` and stamped on every
  audit row, every causally derived event, and any session the call creates.
- Idempotency: `params._meta.idempotencyKey` (string, optional). If present and an audit row with the same key
  and a completed outcome exists, the stored result is returned without re-executing. Gateways set it to
  `sha256(correlation_id | tool | canonical_args)` for every non-read-only call. A replay is bound to the call: the
  stored outcome must be for the same tool with the same arguments (a keyed digest is stored with it), otherwise the
  result is `conflict.idempotency_mismatch`. An outcome whose result exceeded the 8 KB audit cap is never replayed:
  the retry gets `idempotency.result_unavailable` with the `audit_id` of the run — the call is not executed again,
  because it already succeeded and repeating a side effect is worse than losing its result.

### 3.1 Methods

| Method | Notes |
|---|---|
| `initialize` | `serverInfo.name = "android-agent-harness-runtime"`, capabilities: `tools`, `resources`. |
| `ping` | `{}` |
| `tools/list` | Scope-filtered. Each tool has `name` (dotted, e.g. `messaging.send_sms`), `description`, `inputSchema`, `annotations` (`readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`) and `_meta.symbiosis = { action_class, tier, rate_class, emits_events[] }`. |
| `tools/call` | See result envelope below. |
| `resources/list` | `phone://device/status`, `phone://capabilities`, `phone://audit/recent`, `phone://audit/verify`, `phone://events/recent`, `phone://policy`, `phone://sessions`. |
| `resources/read` | JSON text contents. |

### 3.2 `tools/call` result envelope

Success:
```json
{ "content": [{ "type": "text", "text": "{...json result...}" }], "structuredContent": { ... }, "isError": false,
  "_meta": { "correlationId": "corr_...", "auditId": 1234, "durationMs": 42 } }
```

Policy outcomes are **tool results, not JSON-RPC errors**, so an agent can read and recover from them:
```json
{ "isError": true, "content": [{ "type": "text", "text": "approval required" }],
  "structuredContent": { "error": "policy.approval_required", "pending_action_id": "pa_...", "expires_at": 1757140000000, "action_class": "EXTERNAL_COMMITMENT" } }
```
Error codes in `structuredContent.error`: `policy.denied`, `policy.approval_required`, `policy.approval_timeout`,
`policy.rate_limited` (with `retry_after_ms`), `policy.observe_only`, `policy.emergency_stop`, `permission.missing`
(with `permissions[]`), `capability.unavailable`, `capability.failed` (with `detail`).

JSON-RPC errors are reserved for transport-level problems: `-32700` parse, `-32600` invalid request, `-32601`
unknown method/tool (including scope-hidden), `-32602` invalid params (schema validation), `-32000` unauthorized.

## 4. Events

An event is an immutable fact:

```json
{ "id": 4711, "topic": "messaging.sms_received", "ts": 1757139000123, "device_id": "dev_...",
  "correlation_id": "corr_... | null", "payload": { "message_id": "...", "from": "+15550001234", "text": "...", "thread_id": "..." } }
```

`id` is a global, monotonically increasing integer from the journal (`events.id INTEGER PRIMARY KEY AUTOINCREMENT`).
There is exactly one journal per device; subscriptions are views over it.

### 4.1 Topics (v0)

| Topic | Payload keys |
|---|---|
| `messaging.sms_received` | `message_id, thread_id, from, text, ts, sim_slot` |
| `messaging.rcs_received` | `message_id, thread_id, from, from_digits, text, ts, transport ("rcs"), notification_key, can_reply` — RCS chats never reach the SMS broadcast or provider; this is derived from the messaging app's incoming-message notification (Pixel/Google Messages verified on a real phone). Reply with `notifications.reply { key: notification_key, text }`. |
| `call.state_changed` | `state` (IDLE/RINGING/OFFHOOK), `number`, `session_id?` |
| `call.ringing`, `call.connected`, `call.ended` | `number, session_id?, direction` (in/out), `duration_ms?` |
| `notification.posted`, `notification.removed` | `key, package, title, text, ts, category, can_reply` |
| `battery.changed` | `percent, charging, status` |
| `connectivity.changed` | `internet_available, transport` (wifi/cellular/none), `cellular_type?` |
| `session.state` | `session_id, kind, state, previous_state` |
| `policy.approval_required` | `pending_action_id, capability, action_class, expires_at` |
| `policy.approval_resolved` | `pending_action_id, decision` |
| `runtime.started`, `runtime.stopping` | `version, reason?` |
| `automation.run_started` | a saved workflow run began: `workflow_id, name, owner, trigger` (`manual`, `schedule` or `event:<topic>`), `event_id?`. `correlation_id` is the run's own (`corr_wf_…`), which all its steps and their events carry. Scope `automation.*`. |
| `automation.run_finished` | the run ended: the same keys plus `ok, stopped_at, stop_reason, ms`. |
| `call.precise_state` | `state` (Telecom's DIALING/RINGING/ACTIVE/HOLDING/DISCONNECTED…), `number, direction, session_id?, disconnect_cause?` — from the runtime's InCallService; `ACTIVE` is the reliable "answered" signal (`call.state_changed` reports OFFHOOK from the moment of dialing). |
| `call.audio_state` | `route` (earpiece/speaker/bluetooth/wired_headset), `muted, supported_routes` |
| `call.listening` | conversation mode: a far-party turn opened (`listening:true, turn_id, rms, while_speaking`) or closed (`listening:false, turn_id, endpoint` = model/short/timeout/timer, `silence_ms, checks, p_complete?, audio_ms`). Fetch the turn's audio with `telephony.turn_audio`. |
| `call.transcript` | conversation mode: `text, final, turn_id`; interim results stream with `final:false`, the final adds `speech_ms, latency_ms`. |
| `call.speaking` | conversation mode: the agent started (`speaking:true, samples`) or stopped talking. |
| `call.barge_in` | conversation mode: the far party kept talking over the agent for `barge_in_min_ms` and playback was cut; `after_ms?, rms, ongoing?`. |
| `call.conversation` | conversation mode stopped on the phone's side: `state:"stopped"`, `reason` = `error` (the listening loop died - no more transcripts; `error` says why) or `driver_lost` (the owning peer held no socket and made no request for 90 s, so the phone ended the call itself; `owner, idle_ms`). Only calls a peer put in conversation mode are ever ended this way. |

### 4.2 Subscriptions

```
subscription_id        TEXT PRIMARY KEY  -- "sub_" + 12 hex
peer_id                TEXT
topic_glob             TEXT              -- "messaging.*", "call.*", "*"
filter                 TEXT              -- JSON array of {field, op, value}; op ∈ eq, ne, lt, lte, gt, gte, contains, prefix; field is a top-level payload key
delivery               TEXT              -- "live" | "queue"
created_at             INTEGER
created_at_event_id    INTEGER           -- replay can never reach behind this
expires_at             INTEGER NULL
ack_cursor             INTEGER           -- highest event id the peer has cumulatively acknowledged
```

Subscriptions are persisted and survive runtime restarts. They are owned by a peer; a peer sees only its own.
The same operations are exposed both as WebSocket frames and as MCP tools (`events.subscribe`, `events.unsubscribe`,
`events.list_subscriptions`, `events.replay`, `events.ack`) so pure-MCP clients can poll with replay.

### 4.3 `/ws/events` frames

Client → runtime (JSON text frames):

| `type` | Fields |
|---|---|
| `subscribe` | `topic_glob, filter?, delivery?, ttl_ms?` → `subscribed { subscription_id, created_at_event_id }` |
| `unsubscribe` | `subscription_id` → `unsubscribed` |
| `ack` | `subscription_id, through_id` (cumulative) → no reply |
| `replay` | `subscription_id, since_id, limit?` (default 200, max 1000) → `replay_result` |
| `list` | → `subscriptions [ ... ]` |
| `ping` | → `pong` |

Runtime → client:

| `type` | Fields |
|---|---|
| `event` | `subscription_id, event { id, topic, ts, correlation_id, payload }` |
| `replay_result` | `subscription_id, events[], next_since_id, truncated (bool), earliest_available_id` |
| `gap` | `subscription_id, from_id, to_id` — live push was paused because the socket was slow; call `replay`. |
| `error` | `code, message, ref?` |
| `pong` | |
| `bye` | `reason` (revoked, shutdown, emergency_stop) — then close. |

**Backpressure rule.** Each socket has a bounded outbound queue (256 events). When full, the runtime **stops
pushing** for that subscription and sends one `gap { from_id, to_id }` covering everything it did not push.
Events are never dropped from the journal by backpressure. The client recovers with `replay { since_id: ack_cursor }`.

**Replay rule.** `replay` re-applies the subscription's persisted `topic_glob` + `filter` over the journal for
`id > max(since_id, created_at_event_id)`. If the journal's oldest retained id is greater than `since_id + 1`, the
response sets `truncated: true` and `earliest_available_id` so the client knows events were evicted by retention.

**Retention.** Journal keeps the newer of 10 000 events or 7 days; eviction runs hourly. `audit_entries` are
never evicted by this rule.

**Socket binding.** Subscriptions belong to the peer, not to the socket that created them. On connect the runtime
attaches every live subscription the peer owns to the new socket and sends `hello { peer_id, last_event_id,
subscriptions[] }`; live push for a subscription goes to **every** open socket of the owning peer. A `delivery: "queue"`
subscription is never pushed live; it is served only by `replay` (WS or `events.replay`).

**Reconnect.** On connect a client should `replay` each subscription from its persisted `ack_cursor`, then start
receiving live pushes. Live pushes begin immediately after `subscribe`/connect; a client that interleaves replay
and live must de-duplicate by `event.id` (ids are unique and ordered, so `if id <= last_seen: skip`).

## 5. Sessions

A session is a stateful, long-lived interaction with a `kind` (`call` in v0) and a state machine.

```
CREATED → DIALING → RINGING → CONNECTED → ACTIVE ⇄ HOLDING → ENDING → ENDED
Terminal failures: BUSY, NO_ANSWER, FAILED, CANCELLED, PERMISSION_DENIED
```

`telephony.place_call { target, display_name?, purpose?, audio_route? }` → `{ session_id, state: "DIALING" }`.
`telephony.end_call { session_id }`, `telephony.set_audio_route { session_id, route: speaker|earpiece }`,
`telephony.get_session { session_id }`.

### 5.1 `/ws/sessions/{session_id}?since_seq=N`

Every frame carries `session_id`, `seq` (monotonic per session, assigned by the runtime, incremented for **every**
frame including binary audio), `ts`, and `correlation_id`.

JSON text frames, `type` ∈ `session.state`, `session.summary`, `transcript.partial`, `transcript.final`,
`agent.tool_request`, `agent.tool_result`, `dtmf.sent`, `user.interrupt`, `policy.approval_required`, `gap`, `error`.

Binary frames (reserved, not emitted in v0) use a 16-byte header:
```
magic  u32  0x53594D42 ("SYMB")
seq    u64
type   u16  1 = audio.input.chunk (phone → agent), 2 = audio.output.chunk (agent → phone)
codec  u16  1 = PCM16 mono 16 kHz
```
followed by the payload.

On reconnect with `since_seq`, the runtime replays persisted frames (`session.state`, `transcript.*`,
`agent.*`, `session.summary`) with `seq > since_seq`; audio frames are never replayed and a `gap` is sent instead.
`session_messages` persists every non-audio frame.

## 6. Policy

Action classes, ordered: `READ_ONLY < AUTOMATION < SENSITIVE_READ < LOW_RISK < COMMUNICATION < EXTERNAL_COMMITMENT < DEVICE_MUTATION < DESTRUCTIVE`.
`AUTOMATION` is `automation.run` alone (§6.1): the batch does nothing itself, so it ranks just above `READ_ONLY` — any
ceiling from `SENSITIVE_READ` up sees it, it passes observe-only, and each step is then judged on its own class.
`SENSITIVE_READ` covers reads that expose personal data (message bodies, contacts, call history, precise location, screen contents,
notification text); it is allowed by default, still counts as a read under observe-only, and exists so operators can require approval
for it without touching harmless reads. A peer whose ceiling is `READ_ONLY` cannot see those tools at all.

Decision pipeline for every `tools/call`, evaluated in this order and short-circuiting:

1. **Emergency stop** on → `policy.emergency_stop` (nothing runs, live sessions are ended, WS clients get `bye`).
2. **Observe-only** on and class > SENSITIVE_READ → `policy.observe_only`.
3. **Scope / ceiling** (peer) → hidden (`-32601`).
4. **Capability override** (per-tool rule in `policies`): `allow | deny | approve`.
5. **Class default**: READ_ONLY, AUTOMATION, SENSITIVE_READ, LOW_RISK, COMMUNICATION,
   EXTERNAL_COMMITMENT and DEVICE_MUTATION allow; DESTRUCTIVE deny. `telephony.place_call` and
   `messaging.send_sms` are EXTERNAL_COMMITMENT and can run without a tap by default. The phone's
   **Ask me first** switch sets EXTERNAL_COMMITMENT and DEVICE_MUTATION to approve. Persisted configuration
   may differ from these code defaults; inspect the effective configuration before granting authority.
6. **Contact lists** (COMMUNICATION and above with a `target`/`to` argument): denylist → deny; allowlist non-empty
   and target absent → approve; **target on the allowlist → the user's standing approval**: an APPROVE that came
   from the class default becomes ALLOW (an explicit per-capability APPROVE override still asks).
7. **Quiet hours** (COMMUNICATION and above): inside the window → approve.
8. **Rate limits** (persisted sliding windows in `rate_counters`, keyed by rate class): defaults
   `sms 20/hour, 100/day` · `call 5/hour, 20/day` · `install 3/day` · `mutation 300/hour, 2000/day` · `wake 6/hour` ·
   `automation 240/hour, 2000/day` (batches, not their steps) · `ai 120/hour, 1000/day` (`ai.ask`). Exceeded → `policy.rate_limited`. A class default or rate
   limit missing from a saved config (it predates that class) falls back to the code default, not to approve/unlimited.
9. Execute, with audit rows before (intent) and after (outcome).

`approve` creates a `pending_actions` row and a high-priority notification with Approve/Deny actions, then waits up
to `approval_wait_ms` (default 20 s, max 120 s, set per call via `_meta.approvalWaitMs`). If resolved in time the
call proceeds or returns `policy.denied`; otherwise `policy.approval_required` with the `pending_action_id`. A later
approval executes the action and emits `policy.approval_resolved` plus the tool's own events, all carrying the
original `correlation_id`. The agent can poll `events.replay` or subscribe to `policy.*`. Pending actions expire
after 10 minutes. Exception: `telephony.place_call` with `driver_managed: true` only accepts approval while
the invoking request is waiting. A wait timeout cancels that pending action and returns `policy.denied`;
late approval, including after runtime restart, cannot execute it. This flag also stores a startup deadline:
if conversation mode has never attached after 90 seconds, a 10-second watchdog attempts to end the call.
Successful attachment is persisted, so an explicit later conversation stop does not reactivate that deadline.
This covers lost dial responses but does not guarantee platform hang-up succeeds. Existing conversation leases
continue to use peer presence after attachment.

`phone://policy` is an admin-only, read-only resource. Its JSON is
`{config, overrides, emergency_stop, observe_only}`. `config` is the serialized `PolicyConfig`, with camelCase
fields such as `classDefaults`, `rateLimits`, `contactAllowlist`, `quietHours`, and `ai`. Class and decision
values are uppercase enum names (`EXTERNAL_COMMITMENT`, `ALLOW`); prose above uses lowercase for readability.
See `protocol/schemas/policy.json` for this resource and the separate internal `pending_actions` database-row
shape. The latter is not an MCP resource or the smaller `policy.approval_required` tool-result envelope.

### 6.1 `automation.run` (batches)

One tools/call that runs up to 25 other tool calls in order. It is the executor a later phase builds saved
workflows and event triggers on, so the step format below is what those will store.

```json
{ "steps": [
    { "id": "who",  "tool": "contacts.search", "args": { "query": "Ann" } },
    { "id": "text", "tool": "messaging.send_sms",
      "args": { "to": "{{steps.who.result.contacts.0.number}}", "text": "Running late, {{input.eta}}" },
      "on_error": "stop", "wait_ms": 0 } ],
  "timeout_ms": 60000,
  "input": { "eta": "10 min" } }
```

- **Step fields:** `tool` (required; any `automation.*` tool is refused — batches do not nest), `args` (object, default
  `{}`), `id` (optional, `[A-Za-z0-9_-]{1,64}`, unique; default the step's 0-based index), `on_error` `stop` (default)
  or `continue`, `wait_ms` (pause before the step, counted against the batch deadline).
- **Limits:** 1-25 steps (more → `invalid_params`); `timeout_ms` default 60 000, clamped to 1 000..300 000, covers the
  whole batch including waits. The whole batch is validated before step 1 runs.
- **Templates** (string values only, recursively; never keys, numbers or `tool`): `{{steps.<id>.result.<path>}}` reads an
  earlier step's `structuredContent`; `{{steps.<id>.ok|error|ms}}` its status; `{{input.<name>.<path>}}` the `input`
  object. Array elements are `items.0.x` or `items[0].x`. A string that is exactly one reference keeps the value's JSON
  type (`"{{steps.a.result.count}}"` → `3`); otherwise values are text-substituted (non-strings as compact JSON). A
  missing reference — unknown or later step, a failed step's `result`, absent path, index out of range — fails that
  step with `automation.template`; it is never replaced by an empty string. No escapes, no expressions.
- **Each step is a direct call.** A step is dispatched through the same entry point as `tools/call` (lookup → scope and
  ceiling → argument validation → idempotency → audit intent → policy → rate limit → execute → audit outcome), as the
  caller, under the batch's correlation id. A tool the caller cannot see fails as `tool.unknown` (same answer for
  hidden and nonexistent); bad arguments fail as `invalid_params`; policy outcomes keep their codes
  (`policy.denied`, `policy.rate_limited`, `policy.approval_required`, …) and each step is charged to its own rate class.
  If the batch call carries `_meta.idempotencyKey` K, the step with id `x` at index *i* carries `K#x@i`, so retrying a
  failed batch replays the steps that already succeeded instead of repeating them; a step edited in between gets
  `conflict.idempotency_mismatch` rather than the old step's result (§3). The caller is re-resolved before every step:
  a revoked or expired peer stops the batch (`stop_reason: "revoked"`, `error: automation.revoked`), narrowed scopes
  apply from the next step.
- **Result** (`structuredContent`): `{ completed, stopped_at, stop_reason ("step_failed" | "timeout" | "revoked" | null),
  steps: [{ id, tool, ok, result | error+detail+data, ms, audit_id }], not_run: [ids], failed, ms, timeout_ms }`. A batch
  that completed (including failed `continue` steps) is a success; one that stopped is `isError` with
  `error: automation.step_failed` or `automation.timeout` and the same fields. At the deadline the running step is
  cancelled: cooperative work stops; blocking work may still finish, and its outcome is in the audit log. A `wait_ms`
  that cannot fit before the deadline fails at once instead of sleeping. Integer fields accept numeric strings, as
  the tools/call argument check does.
- **Class:** `automation.run` is `AUTOMATION` / rate class `automation` (see §6), scope `automation.*`.

### 6.2 Tools added 2026-09-26

| Tool | Class / rate | Notes |
|---|---|---|
| `automation.run` | AUTOMATION / automation | §6.1 |
| `ui.long_press_element` | DEVICE_MUTATION / mutation | droid-mcp `long_click_node`: ACTION_LONG_CLICK on a node by selector (`text`, `view_id`, `class_name`, `package_name`, `index`) |
| `ui.scroll` | DEVICE_MUTATION / mutation | droid-mcp `scroll_node`: selector + `direction` forward/backward |
| `ui.scroll_to_find` | DEVICE_MUTATION / mutation | droid-mcp `scroll_to_find`: `match`, `direction` down/up/left/right, `max_scrolls` 1-20; returns the matched node |
| `ui.find_and_tap` | DEVICE_MUTATION / mutation | droid-mcp `find_and_tap`: `match`, `match_kind` text/desc/id/class, `case_insensitive` |
| `ui.long_press_coordinates` | DEVICE_MUTATION / mutation | droid-mcp `long_press`: `x`, `y`, `duration_ms` 100-5000 (default 800) |
| `ui.double_tap` | DEVICE_MUTATION / mutation | native (our accessibility service): `x`, `y`, `interval_ms` 40-250 (default 100) |
| `diagnostics.get_runtime_logs` | SENSITIVE_READ | this app's own logcat lines: `lines` (200, max 2000), `min_level` V..F, `tag`, `include_previous`; credentials redacted |
| `diagnostics.get_exit_history` | SENSITIVE_READ | `getHistoricalProcessExitReasons` for this app (Android 11+): reason, description, time, importance, pss/rss, ANR/native trace ≤ 16 KB |
| `device.clipboard_set` | LOW_RISK / mutation | `text` (≤ 64 KB), `label?`, `sensitive?` |
| `device.clipboard_get` | SENSITIVE_READ | needs the accessibility service (reads through a momentary focus overlay); else `clipboard_read_unavailable` |

Added with saved workflows (same day, phase 2):

| Tool | Class / rate | Notes |
|---|---|---|
| `automation.save` | AUTOMATION / automation | §6.3 |
| `automation.list`, `automation.get` | AUTOMATION | own workflows (admin: all) |
| `automation.delete`, `automation.enable` | AUTOMATION / automation | |
| `automation.run_saved` | AUTOMATION / automation | `{name, input?}`; returns §6.1's report plus `run_correlation_id` |
| `ai.ask` | LOW_RISK / ai | `{prompt, system?, model?, max_tokens?, json?}` → `{text}` or `{json}`; LAN LLM router, scope `ai.*` |
| `notifications.post` | LOW_RISK / mutation | `{title, text, urgent?}`: a notification to the owner |

A step may also carry `if` (§6.3): a condition on references; false → the step is `skipped` and the batch goes on.

The `ui.*` additions share `ui.tap_element`'s class, tier and rate class: one MUTATION slot per call. See
`docs/security.md` §2.3a for the accepted gap that screen control shares.

### 6.3 Saved workflows and triggers

Full reference with examples: `docs/automation.md`. In brief:

- `automation.save {name, description?, steps, inputs_schema?, trigger?, enabled?, timeout_ms?}` stores a workflow in
  SQLite (DB v3, table `workflows`) owned by the calling peer; names are unique per owner. Steps are §6.1 steps,
  may not call `automation.*`, and may carry `if: {"<reference>": <condition>}`.
- `trigger` is `{event: {topic, where?}}`, `{schedule: {every_minutes: N}}` (N ≥ 15) or
  `{schedule: {daily_at: "HH:MM", days?: ["mon", …]}}`, plus optional `cooldown_ms` (default 10 000) and
  `max_runs_per_hour` (default 60), limited to 1 s .. 1 day and 1 .. 120. The owner must be able to subscribe to an event
  trigger's topic; `automation.*` topics (and globs that could match them, bare `*` included) are refused.
- Conditions (`where` on payload fields, `if` on references): a bare value (= `equals`) or an object of
  `equals` / `contains` (case-insensitive) / `regex` / `in` / `lt` / `gt`, all ANDed.
- Extra references in a triggered run: `{{event.id}}`, `{{event.topic}}`, `{{event.ts}}`, `{{event.payload.<path>}}`.
- A triggered run needs no approval. It runs as its owner's **current** principal through the §3 dispatch path;
  the run gets its own correlation id. An event on a topic the owner can no longer see is ignored silently. A trigger
  is skipped (logged in the workflow's run log, see `automation.get`) when the event carries the correlation id of
  ANY recent workflow run (global loop guard), when a `where` regex times out, under emergency stop, within the
  cooldown, over the hourly cap, or while a run is active and one is already queued (`busy`; one may queue). The
  loop guard only sees events that carry a correlation id; see docs/automation.md for what that leaves to the
  cooldown, the cap and the SMS rate limit. Revocation and emergency stop also cancel running runs.
- Emergency stop disables every workflow; revoking a peer disables its workflows. Schedules use one inexact
  allow-while-idle alarm (jitter up to a doze window) and are re-armed on every process start.

Emergency stop and observe-only are **not** capabilities. No tool can toggle them; only the UI and the runtime
notification can.

## 7. Audit

`audit_entries` is append-only, enforced by SQLite triggers (`BEFORE UPDATE`/`BEFORE DELETE … RAISE(ABORT)`) and a
hash chain:

```
id INTEGER PK AUTOINCREMENT, ts, correlation_id, peer_id, kind ('tool.intent'|'tool.outcome'|'policy.decision'|'approval'|'event.emitted'|'session.transition'|'admin'|'workflow.run'),
capability, action_class, args_json (redacted), result_json (truncated to 8 KB), success, error_code, duration_ms,
idempotency_key, prev_hash, entry_hash = sha256(prev_hash || canonical_json(all fields except entry_hash))
```

The chain seed is a random 32-byte value generated on first run and kept in Keystore-backed storage. The UI can
verify the chain. `phone://audit/recent` returns the last 100 rows. Argument redaction: values for keys named
`text`, `body`, `message` are kept (the operator needs them); keys matching `token|secret|password|api[_-]?key|authorization`
(case-insensitive) are replaced with `"[redacted]"`. Bare `key` is deliberately not matched: notification keys and similar
identifiers must stay auditable.

## 8. Capability naming

Runtime capabilities use dotted namespaces from the spec (`telephony.*`, `messaging.*`, `contacts.*`,
`notifications.*`, `apps.*`, `ui.*`, `media.*`, `device.*`, `location.*`, `sensors.*`, `files.*`, `calendar.*`,
`connectivity.*`, `events.*`, `automation.*`, `ai.*`, `diagnostics.*`). Conversation mode lives under `telephony.*` (`converse`, `speak`, `stop_speaking`,
`play_audio`, `turn_audio`); `camera.*` is reserved and not implemented (background capture is a product decision). MCP tool names are the capability names verbatim. Where a capability is a passthrough of a droid-mcp
tool, `_meta.symbiosis.source = "droid-mcp:<original_name>"`.

## 9. Versioning

`initialize` → `serverInfo.version` is the app version; `_meta.symbiosis.protocol = "0.1"`. Breaking changes bump
the protocol minor until 1.0. Adding topics, tools, or optional fields is not breaking.
