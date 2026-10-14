# Android Agent Harness Protocol v0 — JSON Schemas

Draft 2020-12 schemas for selected contracts in [the protocol guide](../../docs/protocol.md). Report discrepancies
between schemas, documentation and emitted payloads; a documented safeguard does not prove runtime enforcement.
Each file is standalone (its own `$id`) and files
cross-reference each other with `$ref` URIs where the doc reuses a shape (e.g. `event.json#/$defs/topic`,
`event.json#/$defs/actionClass`, `subscription.json#/$defs/filterClause`, `session-frames.json#/$defs/sessionState`).

| File | protocol.md section(s) | Covers |
|---|---|---|
| `event.json` | 4, 4.1 | The Event envelope and a `oneOf`-discriminated payload per topic. |
| `subscription.json` | 4.2 | The persisted subscription record, including the filter clause array. |
| `events-ws-frames.json` | 4.3 | Every `/ws/events` JSON frame, both directions, discriminated by `type`. |
| `session-frames.json` | 5, 5.1 | The `/ws/sessions/{id}` common envelope, JSON frame types, the session state enum, and the 16-byte binary audio header layout (documented, not JSON-representable). |
| `tool-call-result.json` | 3.2 | The `tools/call` result envelope: success and the `structuredContent.error` policy/capability outcomes, one branch per error code with its extra fields. |
| `policy.json` | 6 | Admin-only `phone://policy` wrapper, its nested camelCase `PolicyConfig`, uppercase enums and current code defaults; separately, a raw internal `pending_actions` SQLite row with JSON strings in its JSON columns. |
| `audit-entry.json` | 7 | The append-only `audit_entries` row, including the hash-chain fields. |
| `trusted-peer.json` | 2 | The `trusted_peers` row (bearer-token peers and scopes). |
| `pair-request.json` | 2 | `POST /pair` request body. |
| `pair-response.json` | 2 | `POST /pair` 200 response body. |
| `capability-manifest.json` | 3.1, 8 | One capability's `_meta.symbiosis` metadata as exposed in `tools/list`. |

## Notes on faithfulness

- Every closed set the doc actually specifies (action classes, policy decisions, rate classes, session states,
  event topics, WS frame types, error codes, filter ops) is an `enum`/`const`, not a free string.
- Policy resource fields follow `Runtime.readResource` and `PolicyModel`; pending-row fields follow `Db` and
  `ApprovalManager`. The pending row is not an MCP resource and differs from the approval-required tool result.
  Code-default annotations do not establish the values in an existing installation's persisted settings.
- A few shapes protocol.md names but never fully specifies (several `/ws/sessions`
  JSON frame payloads: `session.summary`, `transcript.*`, `agent.*`, `dtmf.sent`, `user.interrupt`) are called
  out in their schema's `description` as inferred or intentionally left open, rather than invented as if
  spec'd. `gap`/`error` on the session channel are modeled by analogy to the equivalent `/ws/events` frames,
  also noted in-line.
- `additionalProperties`/`unevaluatedProperties` is `false` everywhere the doc gives a closed field list, and
  deliberately `true` (with a description explaining why) where it doesn't.
