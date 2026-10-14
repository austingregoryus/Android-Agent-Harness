# Architecture

Android Agent Harness has three cooperating layers. The phone is the authority boundary; the gateway and Python drivers are clients. The model does not get direct access to Android APIs.

## Android runtime

`core/Runtime.kt` is the composition root. `RuntimeService` runs as a foreground service with visible stop controls. `ui/MainActivity.kt` provides pairing, permission and safety controls. The app targets Android 36, requires Android 10 or later, and uses droid-mcp adapters plus native implementations.

`CapabilityRegistry` describes tools. `ToolDispatch` checks the caller's scope, action-class ceiling and arguments before `Runtime.invoke` applies idempotency, audit, policy, approval and rate reservation. Every automation step uses this path. Human approval is rechecked against current peer authority and policy before execution.

| Package | Responsibility |
| --- | --- |
| `transport` | Ktor Netty MCP HTTP and WebSocket routes, pairing and peer credentials |
| `capabilities` | Typed tools, substrate adapters and native implementations |
| `policy` | Decisions, contact restrictions, quiet hours, rate limits and approvals |
| `audit` / `storage` | SQLite records and hash chain; Keystore-backed identity storage |
| `events` | Durable journal, subscriptions, replay and Android event sources |
| `sessions` | Owned call sessions and frame sequencing |
| `automation` | Batches, templates, saved workflows, schedule/event triggers |
| `telephony` | Telecom, audio, ASR, turn detection, TTS and driver leases |

Plain HTTP listens only on loopback at port 8765. Optional LAN access uses TLS at port 8766. Pairing exchanges a short-lived code for a scoped bearer token. Clients pin the certificate for self-signed TLS. Browser-origin requests are rejected. Events and session data are filtered by caller authority, not just tool names.

The audit chain is tamper-evident within its threat model, not immutable storage. Root access can defeat it. A never-encrypted installation can fall back to private plaintext preferences when Keystore cannot initialize; an installation that previously used encryption refuses to silently create a new identity after a Keystore failure.

## Go gateway

`gateway/cmd/gateway` provides `pair`, `health`, `run` and `mcp`. `internal/device` implements MCP sessions, pinned TLS, bounded requests and event replay. `internal/provider` converts a canonical conversation into Anthropic, OpenAI-compatible, Gemini or fake-provider requests. `internal/agent` runs a bounded tool loop and switches providers at turn boundaries. `internal/store` persists transcripts and event cursors in SQLite.

The stdio MCP server proxies scoped phone tools and adds health, device, event-wait and optional conversation tools. `gateway.converse_call` supervises the Python driver; brief text passes through a temporary file instead of process arguments.

## Python drivers

`tools/converse.py` subscribes to call events, generates replies, renders speech and handles interruptions, reconnects, bounded waits and call termination. Its structured outcome is model-produced and can be wrong; check it before consequential follow-up actions.

`tools/inbound.py` consumes SMS events, filters senders, offers limited read tools and sends replies. An optional passphrase unlocks a small write-tool set. A sender allowlist is not carrier authentication. See [inbound security](inbound.md).

## Data and lifecycle

Phone data may leave through MCP results, events, model prompts and speech services. Phone audit/event journals, gateway SQLite databases and Python logs can contain personal content. Credentials, databases, logs and recordings belong outside public source. Revocation invalidates new requests immediately; cleanup of existing sockets and sessions runs asynchronously.

The [protocol document](protocol.md) and schemas describe wire formats. Source and current tests establish implemented behavior; older optional protocol proposals do not imply every feature exists.
