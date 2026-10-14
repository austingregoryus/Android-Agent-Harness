# Code review and publication preparation — 2026-10-14

This pass reviewed Android authority/policy, files and idempotency, gateway MCP/events/providers, Python call/SMS drivers, tests, build configuration and public-source privacy. Findings were reproduced with focused tests or checked against the reachable source path before fixes. It is a bounded review, not a guarantee that all bugs are absent.

## Fixed defects

| Area | Defect and resulting behavior |
| --- | --- |
| Android approvals | Refresh peer expiry, revocation and scopes immediately before execution after a human approval wait. |
| Android idempotency | Claim the in-flight key before cache lookup; completed replay receipts no longer shadow the stored result. |
| Android files | Restrict content URI reads to individual Downloads entries rather than arbitrary providers using app permissions. |
| Gateway MCP | Reject missing/empty/null and mismatched result envelopes instead of reporting success. |
| Gateway events | Avoid ACK/dedup advancement past an overflow; recover paginated backlogs rather than stopping after 200 events. |
| Gateway deadlines | Health and close requests honor configured timeouts. |
| Model providers | Map dotted tool names to legal OpenAI/Anthropic wire aliases and restore canonical names without mutating the shared transcript. |
| Inbound dry run | Suppress elevated write tools as well as final reply SMS. |
| Inbound secrets | Discard passphrase-only messages; never restore the original secret after stripping. |
| Inbound identity | Compare complete numbers, preserving international prefixes while supporting national NANP spelling. |
| Python MCP | Stop automatically replaying ambiguous sends; reject invalid envelopes and preserve structured failure metadata. |
| Evaluation script | Import shared prompt constants rather than extracting obsolete source text from a workstation-specific path. |

## Independent publication sweep

Three additional review perspectives examined the clean export: safety and code completeness, documentation/setup, and privacy/packaging. Ten confirmed findings were addressed in the source and refreshed export:

| Finding | Change |
| --- | --- |
| Sensitive events bypassed peer action ceilings | Apply topic sensitivity to live subscriptions, waits, resources and replay, with denied/allowed regressions. |
| Driver-owned dialing could outlive its approval wait or lose its response | Cancel managed pending approvals at timeout, reject late execution after restart, and add a bounded startup lease with persisted successful attachment. Platform hang-up remains best effort. |
| Android contact policy matched different international numbers | Compare full normalized numbers while retaining supported national/NANP equivalents. |
| Malformed inner tool results appeared successful | Gateway rejects missing structured results, mistyped flags/errors and contradictory structured errors. |
| Protocol promised approval taps that defaults do not require | Correct defaults and explain the explicit Ask me first setting. |
| Optional workflows lacked pairing authority | Document required scopes and action ceilings for calls and inbound SMS. |
| Python credential handoff was unclear | Document shared token paths, separate URL/pin configuration and actual model key sources. |
| Custom model flags were overwritten by a preset | Add and test `--brain custom`; document primary, fallback and outcome routing. |
| Policy schema rejected the runtime resource | Match the actual envelope, camelCase configuration, uppercase decisions and internal approval rows. |
| Spoken-call setup depended on unspecified services | Provide an Android/device speech invocation and explicit optional service contracts/assets. |

The privacy reviewer found no additional actionable issue: manifest contents matched, Gitleaks reported no leaks, and independent fixture regeneration reproduced the entire synthetic PCM exactly. This is evidence about the selected source snapshot, not an assurance about all possible private information or the original Git history.

## Validation

- Go: full `go test ./...`, `go vet ./...` and `go build ./...` passed. Event recovery/deadline regressions also passed repeated runs. Local race detection was unavailable because the required CGo/compiler setup was absent; CI includes a Linux race run, not yet executed on GitHub.
- Android: 111 unit tests, 109 passed and two JVM-native ONNX skips after the additional sweep. Independent Python ONNX inference and deterministic fixture regeneration passed. Lint reported zero errors, 58 warnings and two informational findings; warnings are not claimed resolved. Debug and unsigned release assembly passed. Bundled PCM/metadata/model were verified against the reviewed files.
- Python: 16 unittest checks, five standalone inbound scenarios and all 54 conversation scenarios passed. New Python safety tests run against original files produced failures, establishing that the covered defects were reachable. Verbose inbound event logs no longer print unstripped SMS payloads.
- Publication: the selected source snapshot passed Gitleaks 8.30.1 and custom pattern/type checks with no findings. The history scan's one credential-pattern match was a schema hash example, replaced with an explicit zero placeholder; personal development details in history are handled by exporting without history. Private notes and the original DOCX/browser snapshot are preserved outside the export; examples/defaults are sanitized, release signing no longer uses the debug key, and an unidentified voice fixture is replaced with deterministic non-speech signals. The model hash matches upstream LFS metadata. MIT and third-party notices are included; the Go module path matches the configured repository.

## Limits and follow-up

No current real-phone, carrier, physical UI, live provider generation or production tests were run. Android background behavior, privileged audio routing, permissions, on-device inference latency and real recipient outcomes remain unqualified by this pass. ONNX Java-native skips are explicit; Python inference is not a substitute for phone inference.

The inbound responder's deduplication is in-memory. Gateway task idempotency can collapse repeated identical effects within a task. Policy defaults permit some effects without a tap. These are documented behaviors to consider when deploying, not universal safety guarantees.

The existing Git history contains private development details. A clean export does not sanitize that history. Publish the reviewed export with fresh history and deliberate author metadata; keep the original development repository private. See [publishing](publishing.md).
