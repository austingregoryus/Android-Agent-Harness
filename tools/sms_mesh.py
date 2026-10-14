#!/usr/bin/env python3
"""
Every phone texts every other phone, and each message is confirmed ARRIVED, not just sent.

    python tools/sms_mesh.py                  # all ordered pairs across the phones below
    python tools/sms_mesh.py --only oneplus   # just the pairs that involve one phone

A carrier-level end-to-end check of the fleet: the sending phone's runtime, its policy engine and approval
flow, the real SMS network, and the receiving phone's inbox. "Sent" is not the test - the message is only
counted when the RECEIVING phone can find it by a unique marker in its own inbox.

By default (since 2026-09-23) texts need no approval and go straight through. If a phone has "Ask me first"
switched on, each send creates a pending approval on that phone, and this approves exactly that one: a debug
build (the rooted Pixel) through its debug approval broadcast, a release build by tapping the Approve button
beside the row carrying this message's marker in the app's own pending list. Nothing else in the queue is
touched. Run it only when you mean to send real texts - it sends N x (N-1) of them.
"""
from __future__ import annotations

import argparse
import itertools
import json
import os
import random
import re
import string
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import converse as C  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = "dev.symbiosis.runtime"

def load_phones(path: str) -> dict:
    """Read private fleet configuration; importing this module never discovers or contacts devices."""
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    if not isinstance(raw, dict) or len(raw) < 2:
        raise ValueError("fleet must contain at least two phones")
    phones = {}
    for name, entry in raw.items():
        if not isinstance(entry, dict) or not entry.get("serial") or not entry.get("credentials_dir"):
            raise ValueError("each fleet entry needs serial and credentials_dir")
        creds = os.path.join(os.path.dirname(os.path.abspath(path)), entry["credentials_dir"])
        phones[name] = (entry["serial"], creds, entry.get("debug") is True)
    return phones

APPROVE_WAIT_S = 20     # how long to look for the pending row on a release phone
ARRIVE_WAIT_S = 90      # carrier delivery is usually seconds, occasionally a minute


def adb(serial: str, *args: str) -> str:
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    out = subprocess.run(["adb", "-s", serial, *args], capture_output=True, env=env, timeout=60)
    return out.stdout.decode("utf-8", "replace")


def runtime(creds: str) -> C.Runtime:
    read = lambda f: open(os.path.join(creds, f), encoding="utf-8").read().strip()  # noqa: E731
    rt = C.Runtime(read("device-url"), read("device-token"), read("device-tls-fingerprint"))
    rt.init()
    return rt


def e164(n: str) -> str | None:
    """NANP E.164: '+' followed by exactly 11 digits (country code 1 + a 10-digit number). Returns None -
    never a mangled number - when the input can't be made into that: e164("") used to silently return "+1",
    and a bare 7-digit number was silently padded into an 8-digit non-number, either of which would have
    sent a real text to a bogus destination."""
    d = re.sub(r"\D", "", n or "")
    if len(d) == 10:
        d = "1" + d
    result = "+" + d
    return result if re.fullmatch(r"\+1\d{10}", result) else None


def approve_debug(serial: str, pending_id: str) -> bool:
    out = adb(serial, "shell", "am", "broadcast", "-n", f"{PKG}/.policy.ApprovalReceiver",
              "-a", "dev.symbiosis.DEBUG_RESOLVE", "--es", "id", pending_id, "--es", "decision", "approve")
    return "Broadcast completed" in out


def approve_release(serial: str, marker: str) -> bool:
    """Tap Approve on the pending row whose text contains `marker` - and only that row."""
    adb(serial, "shell", "am", "start", "-n", f"{PKG}/.ui.MainActivity")
    try:
        deadline = time.time() + APPROVE_WAIT_S
        while time.time() < deadline:
            time.sleep(1.5)
            adb(serial, "shell", "uiautomator", "dump", "/sdcard/sms_mesh.xml")
            xml = adb(serial, "shell", "cat", "/sdcard/sms_mesh.xml")
            nodes = [(m.group(1), [int(v) for v in re.findall(r"\d+", m.group(2))])
                     for m in re.finditer(r'text="([^"]*)"[^>]*?bounds="(\[[^"]+\])"', xml)]
            row = next((b for t, b in nodes if marker in t), None)
            if row is None:
                # uiautomator only dumps what is ON SCREEN, and the pending list sits below the fold. Keep scrolling
                # down until the row appears (or the deadline passes) - waiting in place never finds it.
                adb(serial, "shell", "input", "swipe", "500", "1600", "500", "700", "250")
                continue
            # The Approve button is the first one BELOW that row; the next row down has its own.
            below = sorted((b for t, b in nodes if t == "Approve" and b[1] >= row[3]), key=lambda b: b[1])
            if not below:
                # Row visible but its button is off the bottom of the screen: scroll a little and look again.
                adb(serial, "shell", "input", "swipe", "500", "1500", "500", "900", "300")
                continue
            b = below[0]
            adb(serial, "shell", "input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2))
            return True
        return False
    finally:
        # The uiautomator dump is scratch, not output - don't leave it sitting on the phone.
        adb(serial, "shell", "rm", "-f", "/sdcard/sms_mesh.xml")


def arrived(rt: C.Runtime, marker: str) -> float | None:
    t0 = time.time()
    while time.time() - t0 < ARRIVE_WAIT_S:
        r = rt.call("messaging.search_sms", {"query": marker, "limit": 5})
        if any(marker in (m.get("body") or "") for m in (r.get("messages") or [])):
            return time.time() - t0
        time.sleep(3)
    return None


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--fleet", default=os.path.join(ROOT, "secrets", "fleet.json"),
                    help="private JSON fleet config (see docs/development.md)")
    ap.add_argument("--only", help="only pairs involving this phone")
    a = ap.parse_args()
    try:
        phones = load_phones(a.fleet)
    except (OSError, ValueError) as e:
        ap.error(f"could not load fleet: {e}")
    if a.only and a.only not in phones:
        ap.error("--only must name an entry in the fleet")

    rts, nums = {}, {}
    for name, (_, creds, _) in phones.items():
        rts[name] = runtime(creds)
        raw = rts[name].call("telephony.get_phone_number", {}).get("phone_number", "")
        num = e164(raw)
        if num is None:
            print(f"{name:8} SKIPPED: {raw!r} is not a NANP number (telephony.get_phone_number)")
            continue
        nums[name] = num
        print(f"{name:8} {nums[name]}")
    print()

    rows = []
    for src, dst in itertools.permutations(phones, 2):
        if src not in nums or dst not in nums:
            continue
        if a.only and a.only not in (src, dst):
            continue
        serial, _, debug = phones[src]
        marker = "SYM" + "".join(random.choices(string.ascii_uppercase + string.digits, k=6))
        body = f"Android Agent Harness mesh test: {src} to {dst} [{marker}]"
        r = rts[src].call("messaging.send_sms", {"to": nums[dst], "body": body}, meta={"approvalWaitMs": 1})
        # Shape, measured: {"error": "policy.approval_required", "pending_action_id": "pa_...", ...}.
        # `error` is a code STRING on a tool-level refusal and a dict on a transport-level one.
        err = r.get("error") if isinstance(r, dict) else "no response"
        code = err if isinstance(err, str) else (err or {}).get("message", str(err))
        pending = r.get("pending_action_id") if isinstance(r, dict) else None
        if err and not pending:
            rows.append((src, dst, "REFUSED", str(code)[:60]))
            print(f"  {src:>7} -> {dst:<7} refused: {code}")
            continue
        how = "no approval needed"   # sent straight through: approvals off, or the number is allowlisted
        if pending:
            ok = approve_debug(serial, pending) if debug and pending else approve_release(serial, marker)
            how = "approved" if ok else "NOT APPROVED"
            if not ok:
                rows.append((src, dst, "FAIL", "could not approve on the sending phone"))
                print(f"  {src:>7} -> {dst:<7} could not approve")
                continue
        t = arrived(rts[dst], marker)
        status = "PASS" if t is not None else "FAIL"
        detail = f"{how}, arrived in {t:.0f}s" if t is not None else f"{how}, NOT in {dst}'s inbox after {ARRIVE_WAIT_S}s"
        rows.append((src, dst, status, detail))
        print(f"  {src:>7} -> {dst:<7} {status}  {detail}")

    print()
    passed = sum(r[2] == "PASS" for r in rows)
    print(f"{passed}/{len(rows)} delivered")
    sys.exit(0 if passed == len(rows) else 1)


if __name__ == "__main__":
    main()
