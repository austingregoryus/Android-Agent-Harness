# Security and privacy

Read the [use-at-your-own-risk notice and operator responsibilities](disclaimer.md). Security controls do not guarantee that an agent will act correctly or prevent all harmful actions.

## Trust boundary

The Android runtime enforces authority. Peers have bearer tokens, scopes, action-class ceilings, expiry and revocation state. Tool lookup, data visibility, policy, approvals and rate limits are enforced on the phone. Model instructions are not authorization.

HTTP is loopback-only; optional LAN access uses pinned TLS. Protect pairing codes, bearer tokens and device identity. Debug builds add ADB diagnostics. Use your own signed release build for deployment and protect the unlocked UI.

## Configure the defaults deliberately

Communication and UI actions can run without a per-action tap under the default policy. Use Ask me first, observe-only, emergency stop and narrow scopes before granting real authority. Configure contact restrictions, quiet hours and rate limits. Count limits do not bound carrier charges or spending.

Approvals are revalidated before execution. Revoked peers cannot start new requests; asynchronous cleanup closes existing sessions/sockets. Emergency stop cannot undo a sent SMS or an action already executed elsewhere.

## Sensitive data

Messages, contacts, location, notifications, screen data, transcripts and diagnostics can contain personal information. Sensitive reads are a distinct action class, not automatically harmless. MCP clients and configured model/speech endpoints may receive this data. `ai.ask` transfers prompts off-device; configure it deliberately.

Event delivery checks namespace scopes and the peer's action ceiling. SMS, notification, call and session events require a `SENSITIVE_READ` ceiling or higher. Per-tool approval settings do not prompt for each delivered event; restrict event namespaces and the peer ceiling to prevent that disclosure.

Audit/event databases, gateway SQLite and Python logs are private runtime data. Logs can contain briefs, numbers and transcripts. Redact before sharing. Git ignores runtime locations, but ignore rules do not remove old commits.

## Limits

- Root/device compromise or someone operating the unlocked trusted UI is outside the remote-agent boundary.
- Audit hashing cannot withstand an attacker who controls both the database and chain seed.
- SecureStore refuses identity replacement after prior encrypted use, but a device where encryption never worked can use private plaintext preferences. Check storage status in the app.
- SMS sender IDs can be spoofed. The inbound allowlist is a routing filter, and the optional passphrase crosses the carrier in plaintext.
- Transport failure does not prove an action failed. Preserve ambiguous outcomes; do not blindly repeat effects.
- Audio, accessibility and background service behavior depend on firmware, permissions and Android restrictions. Unit tests do not qualify physical behavior.

See [publication](publishing.md) and [security reporting](../SECURITY.md).
