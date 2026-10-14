# Wait for a confirmation text

Wait for a new SMS matching the user-supplied sender and return the requested text or code. Follow [setup](../../docs/development.md), [protocol](../../docs/protocol.md) and [security](../../docs/security.md). An SMS sender field is not proof of sender identity.

## Preconditions

Use a paired peer with `messaging.*` and `events.*` scopes and at least a `LOW_RISK` ceiling for persistent subscription creation and acknowledgement. Observe-only blocks those subscription mutations. Discover the available tools and honor any policy refusal. Use an initialized MCP session, or the authenticated event WebSocket protocol.

Ask the user for the expected sender and timeout if missing. Prefer an exact `eq` filter against the full sender representation used by the phone. `messaging.watch_sms` is a convenience tool that strips nondigits from its `from` argument and uses substring matching; that is unsuitable when an exact sender match is required. Number normalization does not establish authenticity.

## Subscribe and wait

Create a subscription before the expected SMS arrives:

```json
{"name":"events.subscribe","arguments":{"topic_glob":"messaging.sms_received","filter":[{"field":"from","op":"eq","value":"+12025550100"}],"ttl_ms":300000}}
```

The response supplies `subscription_id`, `created_at_event_id` and `expires_at`. Replay cannot return events at or before creation; use `messaging.search_sms` for older messages.

Poll `events.replay` or attach the event WebSocket:

```json
{"name":"events.replay","arguments":{"subscription_id":"sub_example","limit":100}}
```

For polling, omit `since_id` initially to use the persisted acknowledgement cursor. Process the returned `events` and continue paging with `since_id: next_since_id` while the cursor advances, including pages with no matching events. `truncated` indicates journal retention loss, not whether another page exists. Report a retention gap explicitly; it means the wait cannot establish that no matching message was missed.

Acknowledge only the highest cursor whose returned events you have processed:

```json
{"name":"events.ack","arguments":{"subscription_id":"sub_example","through_id":4713}}
```

The acknowledgement is cumulative. Do not advance it past an unprocessed matching event. Reconnect using the subscription's acknowledged cursor; subscriptions persist on the phone until deletion or expiry. If combining live events and replay, deduplicate by event ID without allowing a newer live event to cause older, unprocessed replay events to be skipped.

For a single short wait, `events.wait` accepts `topic_glob`, `filter` and `timeout_ms` (up to 60000), returning an event or `timed_out: true`. It listens only while the request is active and has no persisted cursor; events during a disconnect or between calls require a subscription/replay path.

## Finish and report

After processing the desired message, unsubscribe unless the user asked to keep listening:

```json
{"name":"events.unsubscribe","arguments":{"subscription_id":"sub_example"}}
```

Report the observed sender, timestamp and requested message/code, or the elapsed timeout and filter. Distinguish message content from independent confirmation of a booking or other commitment. Keep private message bodies and verification codes out of public reports. State whether the subscription remains active and disclose any replay gap or unresolved transport outcome.

Honor `policy.emergency_stop`, `policy.observe_only`, scope/ceiling failures and expired subscriptions. Do not route around a phone-side stop decision. This recipe does not send SMS.
