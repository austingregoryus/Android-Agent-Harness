---
name: android-agent-harness-phone
description: Use a paired Android Agent Harness Android runtime through its configured MCP gateway.
---

# Android Agent Harness phone

Read the repository's README and docs/security.md before exercising effects. Connect using the locally configured gateway MCP server; never invent a device address, token, phone number or permission grant.

1. Check gateway health and list available tools.
2. Inspect the paired scope and use read tools to establish the required state.
3. For calls, messages and writes, use only the target and authority supplied by the user. Honor approval, observe-only, rate-limit and emergency-stop results.
4. Treat a missing response or transport failure as an unknown outcome. Do not blindly repeat an effect.
5. Report observed results and unresolved outcomes separately. A model-written call summary is not independent confirmation by the recipient.

For setup see docs/development.md; for spoken calls see docs/conversation-mode.md; for inbound SMS see docs/inbound.md. Protect local credentials, transcripts, device identifiers and audit records. Do not copy them into public reports.
