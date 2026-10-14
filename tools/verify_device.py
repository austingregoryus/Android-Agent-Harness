#!/usr/bin/env python3
"""
Verify the general-purpose MCP surface against the REAL phone over the LAN and print a table.

    python tools/verify_device.py                # everything read-only, plus a calendar/file round trip
    python tools/verify_device.py --sms +1...    # also send one SMS (needs approval or an allowlisted number)
    python tools/verify_device.py --md docs/verification-2026-09-18.md   # append the table to a markdown file
    python tools/verify_device.py --device oneplus   # another phone: reads secrets/devices/oneplus/

Reads secrets/device-url, device-token, device-tls-fingerprint (the admin token, all scopes), or the same three
files under secrets/devices/<name>/ with --device. Every row is a
real tools/call; PASS means the call returned ok with the shape we expect, SKIP means the device cannot do it
right now (no cached location, accessibility off) and says why. Nothing here changes the phone beyond a
file in the app's own private storage. With --write it also creates a calendar event and a file in
Downloads/Symbiosis, which stay behind - there is no delete tool - so that is off by default.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import converse  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


DEVICE_DIR = os.path.join(ROOT, "secrets")   # --device points this at secrets/devices/<name>/


def secret(name: str) -> str:
    return open(os.path.join(DEVICE_DIR, name)).read().strip()


class Check:
    def __init__(self, rt: converse.Runtime):
        self.rt = rt
        self.rows: list = []

    def run(self, name: str, tool: str, args: dict, expect=None, skip_codes=(), meta=None, note: str = "") -> dict:
        t0 = time.time()
        r = self.rt.call(tool, args, meta=meta)
        dt = time.time() - t0
        if isinstance(r, dict) and r.get("error"):
            code = r["error"] if isinstance(r["error"], str) else (r["error"] or {}).get("message", "error")
            detail = str(r.get("detail") or "")[:70]
            status = "SKIP" if code in skip_codes else "FAIL"
            self.rows.append((status, name, tool, f"{dt:.2f}s", f"{code}: {detail}"))
            return r
        try:
            ok, summary = (True, "") if expect is None else expect(r)
        except Exception as e:  # noqa: BLE001
            ok, summary = False, f"unexpected shape: {e}"
        self.rows.append(("PASS" if ok else "FAIL", name, tool, f"{dt:.2f}s", (summary or note)[:70]))
        return r

    def table(self) -> str:
        w = [max(len(str(r[i])) for r in self.rows) for i in range(5)]
        lines = [f"| {'result':{w[0]}} | {'check':{w[1]}} | {'tool':{w[2]}} | {'time':{w[3]}} | {'detail':{w[4]}} |",
                 f"|{'-'*(w[0]+2)}|{'-'*(w[1]+2)}|{'-'*(w[2]+2)}|{'-'*(w[3]+2)}|{'-'*(w[4]+2)}|"]
        for r in self.rows:
            lines.append("| " + " | ".join(f"{str(r[i]):{w[i]}}" for i in range(5)) + " |")
        return "\n".join(lines)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sms", default=None, help="send one test SMS to this number")
    ap.add_argument("--md", default=None, help="append the results table to this markdown file")
    ap.add_argument("--write", action="store_true",
                    help="also test writes the owner will SEE and that stay behind: a calendar event and a file in "
                         "Downloads (there is no delete tool). Off by default.")
    ap.add_argument("--device", default=None,
                    help="verify another phone: read its credentials from secrets/devices/<name>/")
    a = ap.parse_args()
    if a.device:
        global DEVICE_DIR
        DEVICE_DIR = os.path.join(ROOT, "secrets", "devices", a.device)
        if not os.path.isdir(DEVICE_DIR):
            raise SystemExit(f"no credentials for {a.device!r}: expected {DEVICE_DIR}")

    rt = converse.Runtime(secret("device-url"), secret("device-token"), secret("device-tls-fingerprint"))
    rt.init()
    c = Check(rt)

    _, body = rt._post({"jsonrpc": "2.0", "id": 9, "method": "tools/list", "params": {}}, rt.session)
    tools = sorted(t["name"] for t in body["result"]["tools"])
    fam: dict = {}
    for t in tools:
        fam.setdefault(t.split(".")[0], []).append(t)
    print(f"tools/list: {len(tools)} tools: " + ", ".join(f"{k} {len(v)}" for k, v in sorted(fam.items())))
    c.rows.append(("PASS", "tools/list", "-", "-", f"{len(tools)} tools"))

    # ---- device / connectivity
    c.run("device status", "device.get_status", {}, lambda r: (bool(r.get("device_id")), f"device_id={r.get('device_id')}"))
    # Any phone, not just the Pixel: it passed on the OnePlus and failed only because this used to say "Pixel".
    c.run("device info", "device.get_info", {},
          lambda r: (bool(r.get("manufacturer") and r.get("model")), f"{r.get('manufacturer')} {r.get('model')}"))
    c.run("battery", "device.get_battery", {}, lambda r: (bool(r), json.dumps(r)[:60]))
    c.run("storage", "device.get_storage", {}, lambda r: (bool(r), json.dumps(r)[:60]))
    c.run("network", "device.get_network", {}, lambda r: (bool(r), json.dumps(r)[:60]))
    c.run("cellular", "connectivity.get_cellular", {}, lambda r: (bool(r), json.dumps(r)[:60]))
    c.run("vpn", "connectivity.is_vpn_active", {}, lambda r: (True, json.dumps(r)[:60]))
    # ---- telephony reads
    c.run("call state", "telephony.get_call_state", {}, lambda r: ("state" in r, f"state={r.get('state')}"))
    c.run("phone number", "telephony.get_phone_number", {}, lambda r: (True, json.dumps(r)[:60]))
    c.run("sim info", "telephony.get_sim_info", {}, lambda r: (True, json.dumps(r)[:60]))
    c.run("operator", "telephony.get_network_operator", {}, lambda r: (True, json.dumps(r)[:60]))
    c.run("call log", "telephony.get_call_log", {"limit": 5}, lambda r: (isinstance(r.get("calls"), list), f"calls={len(r.get('calls', []))}"))
    c.run("sessions", "telephony.list_sessions", {}, lambda r: (True, json.dumps(r)[:60]))
    # ---- messaging / contacts / notifications
    c.run("read sms", "messaging.read_sms", {"limit": 5}, lambda r: (isinstance(r.get("messages"), list), f"messages={len(r.get('messages', []))}"))
    c.run("search sms", "messaging.search_sms", {"query": "a", "limit": 3}, lambda r: (True, json.dumps(r)[:60]))
    c.run("read mms", "messaging.read_mms", {"limit": 5}, lambda r: (isinstance(r.get("messages"), list), f"messages={r.get('count')}"))
    c.run("contacts list", "contacts.list", {"limit": 5}, lambda r: (True, json.dumps(r)[:60]))
    c.run("contacts search", "contacts.search", {"query": "a"}, lambda r: (True, json.dumps(r)[:60]))
    c.run("notifications", "notifications.list", {}, lambda r: (True, f"notifications={len(r.get('notifications', []))}"))
    # ---- apps / media / location
    c.run("apps list", "apps.list", {"limit": 5}, lambda r: (True, json.dumps(r)[:60]))
    c.run("audio devices", "media.get_audio_devices", {}, lambda r: (True, json.dumps(r)[:60]))
    c.run("tts info", "media.get_tts_info", {}, lambda r: (True, json.dumps(r)[:60]))
    c.run("location", "location.get", {}, lambda r: (True, json.dumps(r)[:60]), skip_codes=("capability.failed",))
    # ---- new families
    c.run("sensors list", "sensors.list", {}, lambda r: (len(r.get("sensors", [])) > 0, f"{len(r.get('sensors', []))} sensors"))
    c.run("sensors read", "sensors.read", {}, lambda r: (len(r.get("readings", {})) > 0, f"read {sorted(r.get('readings', {}))} missing {r.get('missing')}"))
    c.run("calendar list", "calendar.list_events", {"days": 30}, lambda r: (isinstance(r.get("events"), list), f"events={r.get('count')}"))
    # Writes the OWNER would see - a calendar event, a file in Downloads - only with --write. There is no delete
    # tool, so these stay behind: on 2026-09-23 a day of runs left nine "Android Agent Harness verification" events on two
    # Google-synced calendars and nine files in Downloads, and the owner found them before being told.
    if a.write:
        start = int(time.time() * 1000) + 3_600_000
        c.run("calendar create", "calendar.create_event", {"title": "Android Agent Harness verification", "start_ms": start},
              lambda r: (bool(r.get("event_id")), f"event_id={r.get('event_id')} (left on the calendar)"),
              skip_codes=("capability.unavailable",))
    c.run("files write", "files.write", {"name": "android-agent-harness-verify.txt", "text": "hello from verify_device.py"},
          lambda r: (bool(r.get("path")), r.get("path", "")))
    c.run("files list", "files.list", {"area": "app"}, lambda r: (any(f.get("name") == "android-agent-harness-verify.txt" for f in r.get("files", [])), f"count={r.get('count')}"))
    fl = rt.call("files.list", {"area": "app"})
    path = next((f["path"] for f in fl.get("files", []) if f.get("name") == "android-agent-harness-verify.txt"), "")
    c.run("files read", "files.read", {"path": path}, lambda r: (r.get("text") == "hello from verify_device.py", "round-trip ok"))
    if a.write:
        c.run("files write downloads", "files.write",
              {"name": "android-agent-harness-verify.txt", "text": "visible in Downloads/Symbiosis", "area": "downloads"},
              lambda r: (bool(r.get("uri")), r.get("uri", "") + " (left in Downloads)"))
    c.run("files list downloads", "files.list", {"area": "downloads"}, lambda r: (isinstance(r.get("files"), list), f"count={r.get('count')}"))
    # ---- ui (needs the accessibility service)
    c.run("ui active window", "ui.get_active_window", {}, lambda r: (True, json.dumps(r)[:60]), skip_codes=("capability.failed",))
    c.run("ui tree", "ui.get_tree", {}, lambda r: (True, json.dumps(r)[:60]), skip_codes=("capability.failed",))
    # ---- events / sessions plumbing
    sub = c.run("events subscribe", "events.subscribe", {"topic_glob": "battery.*"}, lambda r: (bool(r.get("subscription_id")), r.get("subscription_id", "")))
    c.run("events list", "events.list_subscriptions", {}, lambda r: (True, f"{len(r.get('subscriptions', []))} subscriptions"))
    if sub.get("subscription_id"):
        c.run("events unsubscribe", "events.unsubscribe", {"subscription_id": sub["subscription_id"]}, lambda r: (True, ""))
    # ---- policy. READ it rather than probe it by sending. Since 2026-09-23 texts, calls and screen control run
    # without a tap by default, so the old probe - send a text and expect it to be stopped - would now SEND it.
    PROBE_NUMBER = "+15550100000"
    t0 = time.time()
    _, body = rt._post({"jsonrpc": "2.0", "id": 9, "method": "resources/read",
                        "params": {"uri": "phone://policy"}}, rt.session)
    sms_needs_approval = False  # safe default: skip the probe when the policy can't be read at all
    try:
        pol = json.loads(body["result"]["contents"][0]["text"])
        cfg = pol["config"]
        d = cfg["classDefaults"]
        asks = d.get("EXTERNAL_COMMITMENT") == "APPROVE"
        mode = ("asks first" if asks else "unattended") + f" (texts/calls {d.get('EXTERNAL_COMMITMENT')}, "                f"screen {d.get('DEVICE_MUTATION')}; estop={pol.get('emergency_stop')}, observe={pol.get('observe_only')})"
        c.rows.append(("PASS", "policy mode", "phone://policy", f"{time.time() - t0:.2f}s", mode))
        # The class default (asks, above) is only the fallback the engine uses. A per-capability override in
        # "overrides" wins over it, and the probe number being on the allowlist (a standing approval) wins over
        # both - either one means the probe would actually be sent, not stopped, so only probe on plain APPROVE.
        overrides = pol.get("overrides") or {}
        effective = overrides.get("messaging.send_sms", d.get("EXTERNAL_COMMITMENT"))
        probe_digits = "".join(ch for ch in PROBE_NUMBER if ch.isdigit())
        allowlisted = any("".join(ch for ch in n if ch.isdigit())[-10:] == probe_digits[-10:]
                           for n in cfg.get("contactAllowlist", []))
        sms_needs_approval = effective == "APPROVE" and not allowlisted
    except Exception as e:
        c.rows.append(("SKIP", "policy mode", "phone://policy", f"{time.time() - t0:.2f}s",
                       f"not readable with this token ({type(e).__name__}); needs an admin token"))
    if sms_needs_approval:
        # Only when the effective decision for this capability is APPROVE is it safe to probe by sending: the
        # phone must stop this, never send it.
        c.run("policy: sms needs approval", "messaging.send_sms", {"to": PROBE_NUMBER, "body": "policy probe"},
              lambda r: (False, "SENT without approval!"), skip_codes=("policy.approval_required", "policy.denied", "policy.rate_limited"),
              meta={"approvalWaitMs": 1})
    if a.sms:
        c.run("send sms", "messaging.send_sms", {"to": a.sms, "body": "Android Agent Harness verification " + time.strftime("%H:%M")},
              lambda r: (True, json.dumps(r)[:60]), meta={"approvalWaitMs": 60000})
    # ---- conversation surface present (no call): must be refused for a bogus session, not unknown
    c.run("converse registered", "telephony.converse", {"action": "status"}, lambda r: ("running" in r, f"running={r.get('running')}"))
    c.run("turn_audio registered", "telephony.turn_audio", {"session_id": "none"}, lambda r: (False, ""), skip_codes=("session.unknown",))

    print()
    print(c.table())
    counts = {k: sum(1 for r in c.rows if r[0] == k) for k in ("PASS", "SKIP", "FAIL")}
    print(f"\nPASS {counts['PASS']}  SKIP {counts['SKIP']}  FAIL {counts['FAIL']}")
    if a.md:
        with open(a.md, "a", encoding="utf-8") as f:
            f.write(f"\n\n## Run {time.strftime('%Y-%m-%d %H:%M')} — {len(tools)} tools\n\n{c.table()}\n\nPASS {counts['PASS']}  SKIP {counts['SKIP']}  FAIL {counts['FAIL']}\n")
    sys.exit(1 if counts["FAIL"] else 0)


if __name__ == "__main__":
    main()
