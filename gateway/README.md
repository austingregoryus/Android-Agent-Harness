# Android Agent Harness gateway

The Go gateway connects MCP clients and model providers to a paired Android runtime. The phone enforces scopes and policy; gateway tool filtering is an additional restriction.

## Build

```sh
go test ./...
go vet ./...
go build -o bin/gateway ./cmd/gateway
```

Go 1.24+ is required. Use `bin/gateway.exe` on Windows. SQLite is pure Go; ordinary builds do not require CGo.

## Configure

Copy `config.example.yaml` to ignored `config.local.yaml`. Set phone URL, token file, provider/model choices, failover order and optional tool allowlist. Relative file paths resolve from the configuration file's directory. Model identifiers in the example are illustrative; select models available to your provider account.

For USB, use `adb forward tcp:18765 tcp:8765` and `http://127.0.0.1:18765`. For LAN, enable the phone's TLS listener and configure `https://PHONE_HOST:8766` plus `device.tls_fingerprint_sha256` or `device.tls_fingerprint_file`. Pairing can record the pin, but independently compare it with the phone UI before trusting a network peer.

Phone tokens come from `device.token_env` or `device.token_file`. Model keys come from each provider's `api_key_env`; no keys belong in committed YAML. Supported kinds are `anthropic`, `openai`, `gemini` and `fake`. OpenAI-compatible local services use `base_url` and may omit a key.

## Commands

```sh
bin/gateway pair --config config.local.yaml --code XXXX-XXXX --label development
bin/gateway health --config config.local.yaml
bin/gateway mcp --config config.local.yaml
bin/gateway run --config config.local.yaml "Read the battery level"
```

Generate the code in the phone UI. `mcp` serves stdio for an MCP host; it must be launched by that host using absolute executable/config paths. Diagnostic output goes to stderr. The tool catalogue depends on the phone peer's scopes, ceiling and current device capabilities.

The gateway adds health, device-list and event-wait tools. To enable `gateway.converse_call`, configure `agent.converse_script` with the path to `tools/converse.py` (relative to the config file or absolute), and `agent.python` with the interpreter if needed. Python uses its own model/speech settings; gateway provider selection does not configure the call driver. See [spoken calls](../docs/conversation-mode.md).

## Behavior and limits

Provider failover happens between turns, not during an in-flight tool action. OpenAI and Anthropic receive legal wire aliases for dotted tool names; canonical names remain in the stored transcript. Provider-specific opaque content is not forwarded to other providers.

Event replay is paginated and delivery is bounded. A full buffer triggers catch-up without acknowledging dropped events. Missing or mismatched MCP response envelopes are errors, not successful actions. An HTTP timeout can leave the actual device outcome unknown.

The gateway persists transcripts and event state in a private SQLite database. Logs and transcripts can contain personal content. Task-scoped idempotency may collapse identical effectful calls in a single task; inspect that behavior when designing workflows that intentionally repeat an action.

Read [architecture](../docs/architecture.md), [security](../docs/security.md) and [validation](../docs/review.md) before using the gateway with real authority.
