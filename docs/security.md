# Security and privacy

Read the [use-at-your-own-risk notice and operator responsibilities](disclaimer.md). Security controls do not guarantee that an agent will act correctly or prevent all harmful actions.

## Trust boundary

The Android runtime enforces authority. Peers have bearer tokens, scopes, action-class ceilings, expiry and revocation state. Tool lookup, data visibility, policy, approvals and rate limits are enforced on the phone. Model instructions are not authorization.

HTTP is loopback-only; optional LAN access uses pinned TLS. Protect pairing codes, bearer tokens and device identity. **Rotate admin token** in the app replaces a leaked admin token: the old one stops authenticating, its sockets and MCP sessions close, and its pending approvals are cancelled. Debug builds add ADB diagnostics; their receivers require `android.permission.DUMP`, which the adb shell holds and installed apps cannot. Use your own signed release build for deployment and protect the unlocked UI.

## Configure the defaults deliberately

Communication and UI actions can run without a per-action tap under the default policy. Use Ask me first, observe-only, emergency stop and narrow scopes before granting real authority. Configure contact restrictions, quiet hours and rate limits. Count limits do not bound carrier charges or spending.

Approval prompts list the target and message body first, every value in full, and flag arguments the tool does not declare. On Android 12+ Approve and Deny need the device unlocked, and the lock screen shows only a redacted notification. Approvals are revalidated before execution. Revoked peers cannot start new requests; asynchronous cleanup closes existing sessions/sockets. Emergency stop cannot undo a sent SMS or an action already executed elsewhere.

## Sensitive data

Messages, contacts, location, notifications, screen data, transcripts and diagnostics can contain personal information. Sensitive reads are a distinct action class, not automatically harmless. MCP clients and configured model/speech endpoints may receive this data. `ai.ask` transfers prompts off-device; configure it deliberately.

Event delivery checks namespace scopes and the peer's action ceiling. SMS, notification, call and session events require a `SENSITIVE_READ` ceiling or higher. Per-tool approval settings do not prompt for each delivered event; restrict event namespaces and the peer ceiling to prevent that disclosure.

Backup and device-to-device transfer exclude all app data (`data_extraction_rules.xml`). Audit/event databases, gateway SQLite and Python logs are private runtime data. Logs can contain briefs, numbers and transcripts. Redact before sharing. Git ignores runtime locations, but ignore rules do not remove old commits.

## Limits

- Root/device compromise or someone operating the unlocked trusted UI is outside the remote-agent boundary.
- Audit hashing cannot withstand an attacker who controls both the database and chain seed.
- SecureStore refuses identity replacement after prior encrypted use, but a device where encryption never worked can use private plaintext preferences. Check storage status in the app.
- SMS sender IDs can be spoofed. The inbound allowlist is a routing filter, and the optional passphrase crosses the carrier in plaintext.
- Screen control (`ui.*`, `notifications.invoke_action`) is an accepted gap: it can operate any other app, including messaging apps, and is charged one `mutation` slot per action rather than the SMS/call rate classes or contact lists. Grant it only to peers you would trust with the unlocked phone. It cannot operate this app: every `ui.*` call fails with `ui.self_protected` while one of this app's windows is on screen, `apps.launch`/`open_deep_link`/`send_intent` refuse this package, the notification tools never see this app's notifications, and the control panel is `FLAG_SECURE`. An Approve tap within 10 seconds of any peer's screen-control call is ignored (left pending, audited as `approval.ignored_screen_control`), because it may be the agent tapping Approve in the notification shade. A screen-control action approved from inside this app is refused for the same reason (this app is then on screen); approve those from the notification while the target app is in front.
- Transport failure does not prove an action failed. Preserve ambiguous outcomes; do not blindly repeat effects.
- Audio, accessibility and background service behavior depend on firmware, permissions and Android restrictions. Unit tests do not qualify physical behavior.

See [publication](publishing.md) and [security reporting](../SECURITY.md).
