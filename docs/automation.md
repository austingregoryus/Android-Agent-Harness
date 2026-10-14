# Automation: batches, saved workflows, triggers, ai.ask

The phone can run sequences of its own tools without a round trip per step, save them under a name, and run
them by itself when an event arrives or on a schedule — Tasker-style — optionally asking a configured
OpenAI-compatible model what to do. Code: `android-runtime/.../automation/`. Wire contract: `docs/protocol.md` §6.1–6.3.

## Concepts

| Term | Meaning |
|---|---|
| **Step** | One tool call: `{id?, tool, args?, on_error?, wait_ms?, if?}`. |
| **Batch** | Up to 25 steps run in order: `automation.run {steps, timeout_ms?, input?}`. |
| **Workflow** | A named, saved batch owned by the peer that saved it (`automation.save`). |
| **Trigger** | Optional on a workflow: an event (`{event:{topic, where?}}`) or a schedule. |
| **Run** | One execution of a workflow. It has its own correlation id (`corr_wf_…`), carried by every step. |

A workflow **always runs as its owner**: the peer that saved it, with that peer's *current* scopes, action-class
ceiling and rate limits. Every step goes through the same dispatch path as a direct tool call from that peer
(scope → ceiling → argument check → policy → rate limit → execute → audit). Triggering a run has no additional
approval gate; individual steps still use current phone policy, including any approval requirement.

## Tools

| Tool | What it does |
|---|---|
| `automation.run` | Run an ad-hoc batch now. |
| `automation.save` | Create or replace a workflow: `{name, description?, steps, inputs_schema?, trigger?, enabled?, timeout_ms?}`. Returns `warnings` for steps you cannot currently call. |
| `automation.list` | Your workflows (the admin token sees everyone's; `owner` filters). |
| `automation.get` | One workflow in full, plus its recent run log (including skipped triggers). |
| `automation.delete` | Delete one (the audit log keeps what its runs did). |
| `automation.enable` | `{name, enabled}`: turn its trigger on/off. |
| `automation.run_saved` | `{name, input?}`: run now and wait for the report. |
| `ai.ask` | `{prompt, system?, model?, max_tokens?, json?}` → `{text}` or `{json}` from the configured OpenAI-compatible endpoint. |
| `notifications.post` | `{title, text, urgent?}`: show the owner a notification. |

Names are unique per owner (two agents can both have a `status`). A peer sees and manages only its own workflows;
asking for another peer's name answers `automation.not_found`, the same as a name that does not exist. The admin
token may pass `owner` to address any peer's.

## Step format and templating

```json
{ "id": "reply", "tool": "messaging.send_sms",
  "args": { "to": "{{event.payload.from}}", "body": "Battery is {{steps.bat.result.level_percent}}%" },
  "on_error": "stop", "wait_ms": 0,
  "if": { "{{steps.triage.result.json.action}}": { "equals": "reply" } } }
```

- `id` defaults to the step's index (`steps.0`), `on_error` to `stop`. `continue` moves on after a failure.
- References, in string values only:
  - `{{steps.<id>.result.<path>}}` — an earlier step's result; also `.ok`, `.error`, `.ms`, `.skipped`.
  - `{{input.<name>}}` — `run_saved`'s input (or `automation.run`'s `input`).
  - `{{event.payload.<path>}}`, `{{event.topic}}`, `{{event.id}}`, `{{event.ts}}` — the triggering event.
  - Paths are dotted; array elements are `items.0.x` or `items[0].x`.
- A string that is exactly one reference keeps the value's JSON type; otherwise it is text-substituted.
- A missing reference (unknown step, a step not yet run, a failed or skipped step's `result`, a missing field,
  `{{event…}}` in a manual run) fails that step with `automation.template` — never an empty string.
- `if`: keys are references, values are conditions (below). When false the step is reported `skipped: true`
  (`ok: true`) and the batch continues; a later step that reads its `result` fails as a missing reference.
- Steps may not call `automation.*`: batches and workflows never nest or start each other.

## Triggers

```json
{ "event": { "topic": "messaging.sms_received", "where": { "text": { "contains": "status" } } },
  "cooldown_ms": 10000, "max_runs_per_hour": 60 }
{ "schedule": { "every_minutes": 30 } }
{ "schedule": { "daily_at": "07:30", "days": ["mon", "tue", "wed", "thu", "fri"] } }
```

**Event triggers** listen on the phone's own event bus. `topic` is a topic or a topic glob (`call.*`). The owner
must be able to subscribe to it (a trigger is a subscription), checked at save and again at every run. Topics that
exist today (`docs/protocol.md` §4.1): `messaging.sms_received`, `messaging.rcs_received`,
`call.state_changed`, `call.ringing`, `call.connected`, `call.ended`, `call.precise_state`, `call.audio_state`,
`call.listening`, `call.transcript`, `call.speaking`, `call.barge_in`, `call.conversation`, `notification.posted`,
`notification.removed`, `battery.changed`, `connectivity.changed`, `session.state`, `policy.approval_required`,
`policy.approval_resolved`, `runtime.started`, `runtime.stopping`. **Not** `automation.*` (the engine's own
announcements): a topic or glob that could match them (including bare `*`) is refused. The owner's current scopes are
checked before `where` is evaluated; an event on a topic the owner can no longer see is ignored without a log line.

**Schedules**: `every_minutes` (≥ 15) or `daily_at` "HH:MM" (24 h, phone's local time) with optional `days`
(`mon`…`sun`). They ride on one inexact `AlarmManager.setAndAllowWhileIdle` alarm — no new permission (exact
alarms would need `SCHEDULE_EXACT_ALARM`). **Expect jitter**: seconds to a few minutes while the phone is awake,
up to the next doze maintenance window (often ~10–15 min) while it sleeps. A schedule missed by more than 15
minutes (phone off, runtime stopped) is logged `missed` and moves to its next occurrence rather than running late.
A schedule that falls due while the runtime is switched off in the app is logged `skipped:runtime_off` and advanced.
Clock and time-zone changes recompute every schedule (a `daily_at` means that wall-clock time in the new zone).
Alarms and event subscriptions are re-armed from the database on every process start (boot included).

### Conditions (`where` and `if`)

One matcher serves both. A spec maps a subject (a payload field path for `where`, a reference for `if`) to a
condition; all entries must hold. A condition is a bare value (= `equals`) or an object of operators, all of which
must hold:

| Operator | Meaning |
|---|---|
| `equals` | Numbers numerically ("20" = 20); phone numbers as the same line (`+14695551234` = `(469) 555-1234`); else exact. |
| `contains` | Case-insensitive substring. |
| `regex` | RE2 syntax (linear-time; no backreferences or lookaround), found anywhere (anchor with `^…$`; `(?i)` for case-insensitive). Max 200 chars, compiled at save; only the first 4 KB of the value is searched; a match running past 50 ms counts as no-match (`skipped:regex_timeout` for a trigger, a skipped step for an `if`). |
| `in` | A list; true if the value equals any element. |
| `lt`, `gt` | Numeric comparison. |

An absent field fails every operator. There is no OR or NOT: write two workflows.

## `ai.ask`

Calls a configured OpenAI-compatible model endpoint **directly from the phone**. The gateway is not needed,
but the model service must be reachable. Defaults are `http://127.0.0.1:11430/v1` and `local-model`; loopback
refers to the phone itself. Configure `PolicyConfig.ai` for your service before using this tool.
The request includes `messages`, `stream:false`, `max_tokens` and `reasoning_effort:"none"`.
With `json: true` the model is
told to answer with one JSON object, `response_format: json_object` is sent, and the reply is parsed (code
fences and surrounding prose are tolerated) into `result.json`; otherwise `result.text`. 60 s timeout. Errors:
`ai.unreachable`, `ai.timeout`, `ai.http_error`, `ai.bad_response`, `ai.empty`, `ai.bad_json`.

The address and model live in persisted policy (`PolicyConfig.ai`: `baseUrl`, `model`, `timeoutMs`) with defaults
in `policy/PolicyModel.kt`. There is no UI editor for them. See [configuration](development.md). Cleartext is
limited to loopback by `res/xml/network_security_config.xml`; use HTTPS for network endpoints or deliberately
configure a narrow exception.

Class `LOW_RISK`, rate class `ai` (120/h, 1000/day), scope `ai.*` (in the Standard pairing preset). Not
`SENSITIVE_READ`: it reveals nothing to the caller; its risk is data leaving the phone, which is an action, so it
ranks above every read and a read-only peer cannot use it to carry what it read off the phone.

## Safety model

- **Runs as its creator.** Current scopes, ceiling and rate limits of the saving peer, every step through the
  normal dispatch path. The owner is re-resolved before every step: narrow a peer's scopes and the next step already
  runs narrowed; revoke it (or let it expire) and the run stops (`stop_reason: "revoked"`) — revocation also
  cancels its running and queued runs outright.
- **Audited.** A `workflow.run` audit row at start and end of every run (trigger, event id, result), and each step's
  own intent / decision / outcome rows, all under the run's correlation id.
- **Loop guard (global).** Every step carries its run's correlation id, and so does every event a step causes *if
  that event carries a correlation id at all*. An event carrying the correlation id of ANY recent workflow run
  triggers no workflow, so two workflows cannot ping-pong through each other's direct effects, and `automation.*`
  topics cannot be triggers (refused at save), so run announcements cannot chain workflows. What it does **not**
  cover: events that arrive without a correlation id although a run caused them — an inbound SMS or call (including
  a reply from another phone running its own workflow: two phones auto-replying to each other), another app's
  notification about a text a step sent, battery and connectivity changes. Those loops are bounded by the cooldown,
  the hourly cap and, for texts, the SMS rate limit (20/h, 100/day). (Our own `notifications.post` notifications
  never become events: the notification listener ignores this app's package. Sent SMS produce no event.)
- **Cooldown** (default 10 s, minimum 1 s, since the last run started) and **max runs per hour** (default 60,
  maximum 120, counted from the persisted run log). Both per workflow, settable on the trigger; out-of-range values
  are refused at save (older stored values are clamped).
- **One run at a time**, at most one queued; further triggers are dropped as `skipped:busy`. Skips are written to
  the workflow's run log (`automation.get` → `runs`), not the audit log.
- **Emergency stop** disables every workflow (they stay disabled, reason `emergency_stop`, after the stop is
  released), cancels running runs, and refuses to start new ones. **Revoking** a peer disables its workflows (kept, reason `owner_revoked`);
  they cannot be re-enabled while the owner is revoked. The app's **Workflows** section lists them with switches
  and a **Disable all** button. Switching the runtime off in the app pauses all triggers.
- `automation.run_started` / `automation.run_finished` events announce each run (scope `automation.*`).

## Worked examples

### (a) Macro: "text my wife I'm on my way"

```json
automation.save {
  "name": "on my way",
  "description": "Tell my wife I'm on my way, optionally with an ETA",
  "inputs_schema": { "properties": { "eta": { "type": "string" } } },
  "steps": [
    { "tool": "messaging.send_sms",
      "args": { "to": "+1XXXXXXXXXX", "body": "On my way! ETA {{input.eta}}" } }
  ]
}
automation.run_saved { "name": "on my way", "input": { "eta": "20 min" } }
```

`input.eta` is referenced, so a `run_saved` without it fails the step (`automation.template`); make the step
unconditional text if the ETA is optional, or add `"required": ["eta"]` to the schema to reject it up front.

### (b) Auto-reply: "status" from one number → battery level

```json
automation.save {
  "name": "status reply",
  "trigger": {
    "event": { "topic": "messaging.sms_received",
               "where": { "from": { "in": ["+1XXXXXXXXXX"] }, "text": { "contains": "status" } } },
    "cooldown_ms": 60000
  },
  "steps": [
    { "id": "bat", "tool": "device.get_battery" },
    { "tool": "messaging.send_sms",
      "args": { "to": "{{event.payload.from}}",
                "body": "Phone battery {{steps.bat.result.level_percent}}%, charging: {{steps.bat.result.is_charging}}" } }
  ]
}
```

The owner needs `messaging.*` (trigger + send) and `device.*`. `from` matches national and E.164 spellings.

### (c) AI triage of every inbound text

```json
automation.save {
  "name": "sms triage",
  "trigger": { "event": { "topic": "messaging.sms_received" }, "cooldown_ms": 5000, "max_runs_per_hour": 30 },
  "steps": [
    { "id": "t", "tool": "ai.ask", "args": {
        "json": true,
        "system": "You triage text messages for a busy person. Answer {\"urgent\": true|false, \"summary\": \"<10 words>\"}.",
        "prompt": "From {{event.payload.from}}: {{event.payload.text}}" } },
    { "tool": "notifications.post", "if": { "{{steps.t.result.json.urgent}}": true },
      "args": { "title": "Urgent text from {{event.payload.from}}", "text": "{{steps.t.result.json.summary}}", "urgent": true } },
    { "tool": "messaging.send_sms", "if": { "{{steps.t.result.json.urgent}}": true }, "on_error": "continue",
      "args": { "to": "+1XXXXXXXXXX", "body": "Urgent from {{event.payload.from}}: {{event.payload.text}}" } }
  ]
}
```

Tools used: `ai.ask`, `notifications.post` (added for this), `messaging.send_sms`. Needs scopes `ai.*`,
`notifications.*`, `messaging.*`. The forwarded SMS does not re-trigger the workflow (it is outgoing; and its
events carry the run's correlation id). Note the message text goes to the `ai.ask` endpoint — that is the point of the
workflow, and the reason `ai.ask` is an action class rather than a read.
