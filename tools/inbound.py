#!/usr/bin/env python3
"""
Answer texts sent TO the phone: the other direction, for when the owner has cell signal and nothing else.

Everything else in this project points outward - an agent decides to act and the phone carries it out. This
points inward. The owner texts their own phone from wherever they are, an agent reads it, does the work on
the device, and texts back. No internet needed at their end, only a bar of signal.

    python tools/inbound.py                 # run the responder
    python tools/inbound.py --dry-run       # answer, print the reply, send nothing
    python tools/inbound.py --once          # handle one message and exit (for testing)

WHO IS ALLOWED TO DRIVE IT
--------------------------
`secrets/inbound-allowlist`, one number per line. Anything from a number not on it is ignored completely -
not answered, not acted on, not replied to. That is a filter and NOT authentication: sender IDs can be
forged, so the allowlist only decides who gets as far as being considered.

The real control is the CEILING. An inbound message can only reach the tools in ALLOWED_TOOLS, which are
reads plus the reply itself. Nothing in that set commits the owner to anything, spends money, writes to the
device or deletes anything - so a forged sender gets, at worst, a look at a calendar, and the reply goes to
the real owner's phone rather than to whoever sent it.

`secrets/inbound-passphrase` (optional) raises the ceiling for ONE message that contains it, unlocking
ELEVATED_TOOLS. It crosses the carrier in the clear. Since 2026-09-23 the phone runs texts, calls and screen
control without an approval tap (unless the owner turns "Ask me first" on), so there is no longer a second
check behind the passphrase: whoever sends it can make the phone text a third party. Treat it as a secret.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import threading
import time
import urllib.request
from typing import Optional

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import converse as C  # noqa: E402  (Runtime, Brain, BRAINS, call_context)

try:
    from websockets.sync.client import connect as ws_connect  # type: ignore
except Exception:  # pragma: no cover
    ws_connect = None

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SECRETS = os.path.join(ROOT, "secrets")

# Reads, plus the one write that IS the conversation: replying to the person who texted. Nothing here can
# commit the owner to anything or change the device, which is what makes a forged sender survivable.
ALLOWED_TOOLS = {
    "calendar.list_events": "What is on the calendar. Args: start_iso, end_iso (optional).",
    "contacts.search": "Look someone up in the owner's contacts. Args: query.",
    "messaging.search_sms": "Search the owner's texts. Args: query, limit.",
    "telephony.get_call_log": "Recent calls. Args: limit.",
    "device.get_battery": "Battery level and charging state. No args.",
    "device.get_network": "Network and connectivity state. No args.",
    "location.get": "Where the phone is now, as coordinates. No args.",
    "location.get_address": ("Turn coordinates into a street address. Args: latitude, longitude - call "
                            "location.get first to get them; this tool does NOT find the phone by itself."),
}

# Only with the passphrase. These change something or reach the outside world. Since 2026-09-23 the phone no
# longer asks its owner before texts, calls or screen control (unless "Ask me first" is on in the app), so the
# passphrase is now the ONLY thing between a forged sender and texting third parties - the rate limit caps it
# at 20 texts an hour and the audit log records it, but nothing stops it. Do not widen this set lightly.
ELEVATED_TOOLS = {
    "calendar.create_event": "Put something on the calendar. Args: title, start_iso, end_iso, location.",
    "messaging.send_sms": "Text SOMEONE ELSE (not the person you are replying to). Args: to, body.",
}

# What a probe should pass each read tool. Written out rather than left empty because a tool that needs
# arguments fails differently from one that is broken, and the point of the probe is to tell them apart.
PROBE_ARGS = {
    "calendar.list_events": {"start_iso": "2026-01-01T00:00:00Z", "end_iso": "2026-01-02T00:00:00Z"},
    "contacts.search": {"query": "a"},
    "messaging.search_sms": {"query": "the", "limit": 3},
    "telephony.get_call_log": {"limit": 3},
    # A public landmark, not the owner's own position: the probe is checking the signature, and a self-test
    # should not need the phone to be anywhere in particular (or print where it is).
    "location.get_address": {"latitude": 37.4220, "longitude": -122.0841},
}

MAX_STEPS = 4          # tool calls per message, before it has to answer with what it has
SMS_LIMIT = 300        # keep a reply to about two segments; this is a text, not an essay
SEEN_MAX = 500


def secret(name: str) -> Optional[str]:
    try:
        with open(os.path.join(SECRETS, name), encoding="utf-8") as f:
            return f.read().strip() or None
    except Exception:
        return None


def digits(number: str) -> str:
    return re.sub(r"\D", "", number or "")


def load_allowlist() -> list:
    """One number per line; `#` comments and blanks ignored. Empty file means nobody, deliberately."""
    out = []
    try:
        with open(os.path.join(SECRETS, "inbound-allowlist"), encoding="utf-8") as f:
            for line in f:
                line = line.split("#", 1)[0].strip()
                if line:
                    out.append(line)
    except FileNotFoundError:
        return []
    return out


def allowed(sender: str, allowlist: list) -> bool:
    """
    Match complete numbers, permitting NANP national and +1 spellings. Never discard an arbitrary
    country code: two international numbers can share the same final ten digits.
    """
    def canonical(number):
        value = digits(number)
        if number.strip().startswith("+"):
            return value
        if number.strip().startswith("00"):
            return value[2:]
        return "1" + value if len(value) == 10 else value
    s = canonical(sender)
    return len(s) >= 10 and any(s == canonical(a) for a in allowlist if len(digits(a)) >= 10)


SYSTEM = """You are the owner's assistant, reachable by text message on their own phone.

They are texting you from somewhere else, probably with no internet - only a bar of signal. That shapes
everything: answer in ONE text, under {limit} characters, plain words, no markdown, no bullet points, no
emoji. Say the answer first. If it will not fit, give the part that matters and offer the rest.

You can look things up on the phone with the tools you have been given. Use them rather than guessing: you
have no memory of previous messages and no knowledge of this person's life beyond what a tool returns.

If you genuinely cannot answer - no tool covers it, or it needs something you were not given - say so
plainly and say what you would need. Never invent a fact, a time, a name or a number. Being wrong by text
is worse than being unhelpful, because they cannot see that you guessed.

If you are asked to do something outside your tools, say what you would need to do it and leave it there.
"""


ELEVATED_NOTE = ("\nThis message carried the passphrase, so you may change things as well as look them up. "
                 "Say what you actually did, plainly. If the phone has asked its owner to approve something "
                 "and that has not happened yet, say so rather than claiming it is done.")


def locked_note(has_passphrase: bool) -> str:
    """
    "I can't" with no way forward is the most annoying reply a text can get. When a passphrase exists the
    model is told to point at it - but never told what it IS, only that there is one.
    """
    base = "\nYou can only look things up. You cannot change anything, or message anyone but the sender."
    if has_passphrase:
        return base + (" If they asked for something that needs changing, say so in one line and tell them to "
                       "send it again with their passphrase. Never guess the passphrase or repeat one back.")
    return base + " If they asked for something that needs changing, say plainly that you cannot."


def tool_schema(names: dict) -> list:
    return [{"type": "function",
             "function": {"name": n.replace(".", "__"),
                          "description": d,
                          "parameters": {"type": "object",
                                         "properties": {"args_json": {
                                             "type": "string",
                                             "description": "JSON object of arguments, or {} for none"}},
                                         "required": ["args_json"]}}}
            for n, d in names.items()]


def chat(brain: C.Brain, messages: list, tools: list, timeout: float = 60.0) -> dict:
    body: dict = {"model": brain.model, "messages": messages}
    if tools:
        body["tools"] = tools
    if "openai.com" in brain.base:
        body["max_completion_tokens"] = 700
    else:
        body["max_tokens"] = 700
        body["temperature"] = 0.3
    if brain.reasoning:
        body["reasoning_effort"] = brain.reasoning
    headers = {"Content-Type": "application/json"}
    if brain.api_key:
        headers["Authorization"] = "Bearer " + brain.api_key
    req = urllib.request.Request(brain.base.rstrip("/") + "/chat/completions",
                                 data=json.dumps(body).encode(), headers=headers, method="POST")
    import ssl as _ssl
    ctx = _ssl.create_default_context() if brain.hosted else None
    with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
        return json.loads(r.read().decode())["choices"][0]["message"]


def answer(rt, brain: C.Brain, text: str, elevated: bool, verbose: bool = False,
           has_passphrase: bool = False) -> str:
    """Run the tool loop for one message and return the text to send back."""
    catalogue = dict(ALLOWED_TOOLS)
    if elevated:
        catalogue.update(ELEVATED_TOOLS)
    by_wire = {n.replace(".", "__"): n for n in catalogue}
    tools = tool_schema(catalogue)
    messages = [
        {"role": "system", "content": SYSTEM.format(limit=SMS_LIMIT) + C.call_context()
         + (ELEVATED_NOTE if elevated else locked_note(has_passphrase))},
        {"role": "user", "content": text},
    ]
    for _ in range(MAX_STEPS):
        msg = chat(brain, messages, tools)
        calls = msg.get("tool_calls") or []
        if not calls:
            return (msg.get("content") or "").strip()
        messages.append({"role": "assistant", "content": msg.get("content") or "", "tool_calls": calls})
        for tc in calls:
            wire = (tc.get("function") or {}).get("name", "")
            name = by_wire.get(wire)
            try:
                args = json.loads((tc.get("function") or {}).get("arguments") or "{}")
                args = json.loads(args.get("args_json") or "{}") if "args_json" in args else args
            except Exception:
                args = {}
            if name is None:
                # Either a hallucinated tool or an elevated one asked for without the passphrase. Say which,
                # because "I can't do that" with no reason is the least useful reply a person can get.
                known = wire.replace("__", ".")
                out = {"error": ("that needs the passphrase" if known in ELEVATED_TOOLS
                                 else "no such tool")}
            else:
                if verbose:
                    print(f"    -> {name} {args}", flush=True)
                out = rt.call(name, args)
            messages.append({"role": "tool", "tool_call_id": tc.get("id"),
                             "content": json.dumps(out, ensure_ascii=False)[:4000]})
    # Out of steps: make it answer with whatever it has rather than looping.
    msg = chat(brain, messages + [{"role": "user", "content":
                                   "Answer now, in one text, using only what you have."}], [])
    return (msg.get("content") or "").strip()


def shorten(text: str, limit: int = SMS_LIMIT) -> str:
    text = " ".join((text or "").split())
    if len(text) <= limit:
        return text
    cut = text[:limit - 1]
    # Prefer a sentence end, then a word boundary; a text chopped mid-word reads as a bug.
    for stop in (". ", "? ", "! "):
        i = cut.rfind(stop)
        if i > limit * 0.6:
            return cut[:i + 1]
    i = cut.rfind(" ")
    return (cut[:i] if i > limit * 0.6 else cut) + "…"


def log_line(record: dict) -> None:
    try:
        d = os.path.join(ROOT, "logs")
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "inbound.jsonl"), "a", encoding="utf-8") as f:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")
    except Exception as e:
        print(f"[inbound] could not write the log: {e}", flush=True)


def selftest(rt) -> int:
    """
    Call every read tool once and report which ones actually work.

    This exists because a tool description that is WRONG is worse than a tool that is missing: the model
    follows it, the call fails mid-answer, and the owner gets "I could not work that out" by text with no
    clue why. `location.get_address` was described here as "where the phone is" when it is really a
    reverse-geocoder needing coordinates, and only a probe like this caught it. Signatures drift; re-run it.
    """
    bad = 0
    print("probing the read tools this responder offers:\n")
    for name in ALLOWED_TOOLS:
        args = PROBE_ARGS.get(name, {})
        try:
            out = rt.call(name, args)
        except Exception as e:
            out = {"error": {"message": f"{type(e).__name__}: {e}"}}
        err = out.get("error") if isinstance(out, dict) else None
        if err:
            bad += 1
            print(f"  FAIL  {name:26} {json.dumps(err, ensure_ascii=False)[:90]}")
        else:
            keys = ", ".join(list(out)[:5]) if isinstance(out, dict) else type(out).__name__
            print(f"  ok    {name:26} -> {keys}")
    print(f"\n{len(ALLOWED_TOOLS) - bad}/{len(ALLOWED_TOOLS)} usable"
          + ("" if not bad else "  <- fix the description or drop the tool; the model will follow it blindly"))
    return 1 if bad else 0


def make_handler(rt, brain: C.Brain, allowlist: list, passphrase: Optional[str], dry_run: bool = False,
                 verbose: bool = False):
    """
    The per-message handler: the payload of a messaging.sms_received event in, at most one reply text out.

    Nothing that goes wrong with ONE message may take the daemon down: an exception out of send_sms (the
    phone's connection dropped mid-request) used to propagate out of the event loop and end the process, so
    every later text went unanswered until someone noticed (review 2026-09-26). Each failure is logged, to the
    console and to logs/inbound.jsonl, and the next message is handled as normal.
    """
    seen: list = []          # message_ids already handled; carriers redeliver, and replay overlaps live push
    lock = threading.Lock()

    def handle(payload: dict) -> None:
        sender = str(payload.get("from") or "")
        text = str(payload.get("text") or "").strip()
        mid = str(payload.get("message_id") or "")
        if not text:
            return
        with lock:
            if mid and mid in seen:
                return
            seen.append(mid)
            del seen[:-SEEN_MAX]
        if not allowed(sender, allowlist):
            # Say nothing at all. A reply, even a refusal, confirms the number is live and answered by
            # something automated, which is exactly what a stranger probing it wants to learn.
            print(f"[inbound] ignoring {sender}: not on the allowlist", flush=True)
            log_line({"ts": time.time(), "from": sender, "ignored": "not allowlisted"})
            return

        elevated = bool(passphrase) and passphrase.lower() in text.lower()
        if elevated and passphrase:
            # Strip it before the model sees it, so the passphrase never ends up quoted back in a reply.
            text = re.sub(re.escape(passphrase), "", text, flags=re.I).strip()
            if not text:
                return
        print(f"[inbound] {sender}: {text!r}{'  [elevated]' if elevated else ''}", flush=True)

        t0 = time.time()
        try:
            # A rehearsal must not execute elevated writes before suppressing the final reply SMS.
            reply = answer(rt, brain, text, elevated and not dry_run, verbose, bool(passphrase))
        except Exception as e:
            print(f"[inbound] failed: {type(e).__name__}: {e}", flush=True)
            reply = "Sorry, something went wrong on my end and I couldn't answer that."
        reply = shorten(reply) or "Sorry, I couldn't work that one out."
        took = time.time() - t0

        print(f"[inbound] reply ({took:.1f}s): {reply}", flush=True)
        sent: dict = {"dry_run": True}
        if not dry_run:
            try:
                sent = rt.call("messaging.send_sms", {"to": sender, "body": reply}) or {}
            except Exception as e:
                # The request may or may not have reached the phone. Re-sending could text them twice, so it
                # is logged, not retried.
                sent = {"error": f"{type(e).__name__}: {e}"}
            if sent.get("error"):
                print(f"[inbound] send failed: {sent}", flush=True)
        log_line({"ts": time.time(), "from": sender, "text": text, "elevated": elevated,
                  "reply": reply, "took_s": round(took, 2), "sent": sent})

    return handle


def dispatch(frame: dict, handle, verbose: bool = False) -> bool:
    """
    One frame from /ws/events. True when it carried an incoming text (handed to `handle`), else False.

    The phone sends `{"type": "event", "subscription_id": ..., "event": {"id", "topic", "payload", ...}}`
    (protocol.md 4.3). This used to read `frame["payload"]` - a key an event frame does not have - so every
    text ever sent to the phone reached the handler as an empty payload and was silently dropped (review
    2026-09-26). Parsed here the way converse.py's event loop always has.
    """
    if frame.get("type") != "event":
        if verbose:
            print(f"[ws] {frame}", flush=True)
        return False
    ev = frame.get("event") or {}
    topic, payload = ev.get("topic"), ev.get("payload") or {}
    if verbose:
        # SMS bodies may contain the elevation passphrase. Never print raw event payloads before
        # the handler strips it, even when verbose diagnostics are enabled.
        print(f"[ev] {topic} id={ev.get('id')}", flush=True)
    if topic != "messaging.sms_received":
        return False
    try:
        handle(payload)
    except Exception as e:
        print(f"[inbound] handler error: {type(e).__name__}: {e}", flush=True)
    return True


def listen(ws_url: str, token: str, ssl_ctx, handle, once: bool = False, verbose: bool = False) -> None:
    """
    Serve texts until stopped. The socket is kept alive indefinitely: a dropped connection is reconnected with
    backoff (up to 30 s between tries), and texts that arrived meanwhile are replayed from the phone's journal
    (converse.EventChannel). It used to exit on the first drop and never answer again.
    """
    ch = C.EventChannel(ws_url, token, ssl_ctx, "messaging.sms_received", reconnect_for=float("inf"),
                        max_delay=30.0, label="inbound")
    while True:
        try:
            ch.open()
            break
        except Exception as e:
            print(f"[inbound] could not open the event channel ({type(e).__name__}: {e}); retrying in 10s",
                  flush=True)
            time.sleep(10.0)
    try:
        while True:
            frame = ch.recv()
            if frame is None:
                continue
            if dispatch(frame, handle, verbose) and once:
                return
    finally:
        ch.close()


def main() -> None:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
        except Exception:
            pass

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default=os.environ.get("SYMBIOSIS_URL") or secret("device-url")
                    or "http://127.0.0.1:18765")
    ap.add_argument("--pin", default=os.environ.get("SYMBIOSIS_TLS_PIN") or secret("device-tls-fingerprint"))
    ap.add_argument("--token", default=os.environ.get("SYMBIOSIS_TOKEN") or secret("device-token"))
    ap.add_argument("--ws", default=None)
    ap.add_argument("--brain", choices=sorted(C.BRAINS), default="luna")
    ap.add_argument("--dry-run", action="store_true", help="print the reply instead of sending it")
    ap.add_argument("--once", action="store_true", help="handle one message, then exit")
    ap.add_argument("--selftest", action="store_true",
                    help="call every read tool once and report which work, then exit")
    ap.add_argument("-v", "--verbose", action="store_true")
    a = ap.parse_args()

    if ws_connect is None and not a.selftest:
        raise SystemExit("pip install websockets  (needed for the event channel)")
    if not a.token:
        raise SystemExit("no device token (secrets/device-token)")

    if a.selftest:
        rt = C.Runtime(a.base, a.token, a.pin)
        rt.init()
        raise SystemExit(selftest(rt))

    allowlist = load_allowlist()
    if not allowlist:
        raise SystemExit(
            "secrets/inbound-allowlist is empty or missing, so nobody may drive this.\n"
            "Put one phone number per line in it - start with your own.")
    passphrase = secret("inbound-passphrase")

    base, model, reasoning = C.BRAINS[a.brain]
    key = None
    if base.startswith("https://"):
        for line in (open(os.path.join(SECRETS, "api_keys"), encoding="utf-8") if
                     os.path.exists(os.path.join(SECRETS, "api_keys")) else []):
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.strip().split("=", 1)
                if ("openai.com" in base and k.strip() == "OPENAI_API_KEY") or \
                   ("anthropic.com" in base and k.strip() == "ANTHROPIC_API_KEY"):
                    key = v.strip()
    brain = C.Brain(base, model, reasoning, "", key)

    rt = C.Runtime(a.base, a.token, a.pin)
    rt.init()
    ws_url = a.ws or (a.base.replace("http://", "ws://").replace("https://", "wss://") + "/ws/events")
    handle = make_handler(rt, brain, allowlist, passphrase, dry_run=a.dry_run, verbose=a.verbose)

    print(f"[inbound] listening. {len(allowlist)} number(s) allowed, "
          f"passphrase {'set' if passphrase else 'not set'}, brain {brain}"
          f"{'  (DRY RUN)' if a.dry_run else ''}", flush=True)
    if ws_connect is None:       # unreachable: --selftest returned above, anything else already exited
        raise SystemExit("pip install websockets")
    listen(ws_url, a.token, rt.ssl, handle, once=a.once, verbose=a.verbose)


if __name__ == "__main__":
    main()
