# Agent workflow recipes

These recipes use a paired Android Agent Harness MCP gateway. Discover the current tool catalogue before acting; permissions, scopes and device support vary. See [setup](../docs/development.md), [protocol](../docs/protocol.md) and [security](../docs/security.md).

- [Phone integration](hermes/android-agent-harness-phone/SKILL.md)
- [Call a business](telephony/call_business_and_reserve.md)
- [Wait for a confirmation text](messaging/wait_for_confirmation_text.md)

Honor policy and approval results. Never interpret an approval request as completion or blindly retry an unknown outcome. Use event subscriptions/replay for longer waits, and keep session IDs for call control. Examples use synthetic identities; replace them only with user-authorized targets.
