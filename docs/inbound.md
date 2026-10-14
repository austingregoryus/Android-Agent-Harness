# Inbound SMS

`tools/inbound.py` answers incoming texts with a restricted tool set. It filters senders, deduplicates recent message IDs, reconnects its event socket and replays missed events while the process is alive. Deduplication is in-memory; it is not exactly-once delivery across restarts.

Follow [build and connect](development.md) and use its shared `device.token_file: ../secrets/device-token` setting. All `secrets/` paths in this guide are relative to the repository root, not `gateway/`. Python reads `secrets/device-url`, `secrets/device-token` and `secrets/device-tls-fingerprint`, or `SYMBIOSIS_URL`, `SYMBIOSIS_TOKEN` and `SYMBIOSIS_TLS_PIN`. The gateway's YAML and provider environment settings are not automatically imported.

Mint a new **Standard agent** pairing code. From `gateway/`, grant the messaging namespace needed for incoming events and the individual read tools used by the normal responder:

```sh
bin/gateway pair --config config.local.yaml --code XXXX-XXXX --label inbound --scopes "events.*,messaging.*,calendar.list_events,contacts.search,telephony.get_call_log,device.get_battery,device.get_network,location.get,location.get_address" --max-action-class EXTERNAL_COMMITMENT
```

This replaces the token file; revoke unused older peers in the phone UI. The ceiling is needed for reply SMS; the default gateway pairing ceiling `COMMUNICATION` cannot send them. Incoming SMS subscriptions require `messaging.*`, which grants more messaging tools than the responder exposes to its model; protect the token accordingly. Grant Android permissions for the selected reads and SMS, configure **Ask me first** as needed, and keep observe-only enabled until ready to send replies. Elevated calendar writes require an additional deliberate `calendar.*` scope grant; the normal example intentionally omits them.

Create ignored `secrets/inbound-allowlist`, one complete number per line. Blank lines and `#` comments are ignored. NANP national and +1 forms match; arbitrary country codes are not discarded. No allowlist means startup is refused.

The default brain is the hosted `luna` preset. Create ignored `secrets/api_keys` containing `OPENAI_API_KEY=YOUR_KEY` for that endpoint, or `ANTHROPIC_API_KEY=YOUR_KEY` for an Anthropic preset. This driver currently reads those file entries, not `LLM_API_KEY` or gateway environment keys. Named presets and models must be available to your account. `--brain ornith` uses `SYMBIOSIS_LLM_BASE` with the built-in local model name; the inbound CLI currently has no arbitrary model/base override. Inspect `python tools/inbound.py --help` and `BRAINS` in `tools/converse.py` when selecting a preset.

Default tools read calendars, contacts, messages, call history, battery/network and location. Replies go to the matched sender. Sender ID can be forged, so the allowlist is not authentication and these reads can disclose sensitive information.

Optional `secrets/inbound-passphrase` enables calendar writes and third-party SMS for one message. It crosses the carrier in plaintext. The driver strips it before logging/model input; passphrase-only messages are discarded. Configure phone approvals as an additional boundary.

```sh
python tools/inbound.py --dry-run
python tools/inbound.py
```

Dry run still reads the phone and contacts the model, but disables elevated write tools and suppresses reply SMS. It is not offline. `--selftest` probes real phone reads. Use `test_inbound.py` and the unittest safety suite for offline checks.

`logs/inbound.jsonl` contains private diagnostics. Protect and redact before sharing. This is not a channel for highly sensitive secrets or a durable messaging service.
