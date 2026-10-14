# Call a business and request a reservation

## Goal

Ask a user-specified business about availability and, when authorized, request a reservation within the user's stated constraints.

## Preconditions

The user supplied the target number, party size, acceptable dates/times and permitted contact details. The paired phone has the needed call scopes/roles. Spoken conversations require qualified privileged audio provisioning and a configured gateway conversation driver.

## Sequence

1. Check gateway health and tool availability.
2. Use `gateway.converse_call` when configured, supplying `target` and a `brief` containing only authorized details. State the acceptable range and what must be confirmed. Do not invent missing facts.
3. If conversation support is unavailable, stop before dialing unless a human will speak. `telephony.place_call` alone does not provide an autonomous conversation.
4. Honor `policy.approval_required`, denial, quiet hours and rate-limit results. Defaults do not guarantee an approval tap.
5. Confirm call termination before another attempt. A transport error is an unknown outcome, not permission to dial again.

## Report

Report the model's stated outcome, agreed details, unresolved questions and required follow-up. Distinguish that summary from independent confirmation such as an SMS from the business. Keep account details, numbers and transcripts out of public logs.
