#!/usr/bin/env python3
"""
Conversation driver: close the loop between what the far party says and what the agent says back.

The phone does capture -> VAD -> ASR and emits `call.transcript` events; it deliberately does not decide what to
say. This script is the other half: it subscribes to those events, asks a responder for a reply, and renders it
into the call. Swapping the responder is the only thing that changes between a scripted demo and a real model.

The task is whatever `--system` says. Nothing below is specific to one kind of errand: the speaking rules, the
turn-taking and the ending are about holding a phone call, and the goal, the facts to offer and what counts as
success come from the brief. Booking, asking, confirming, chasing, cancelling and rescheduling are all the same
shape — say who you are, get the thing settled, read it back once, hang up.

    # places the call and talks on it; defaults (LAN TLS URL, token, pinned cert) come from secrets/
    python tools/converse.py --dial +1XXXXXXXXXX --responder local --tts kokoro -v \
        --system "You are Alex's assistant. Find out whether his prescription is ready and when they close."

What makes it feel like a conversation rather than a form being read aloud (docs/conversation-mode.md):
  * the opening line is written by the model and synthesized before dialing, and plays on their first turn;
  * sentences are synthesized ahead of playback, so sentence 2 starts the instant sentence 1 ends;
  * replies are one short sentence; barge-in and the other person carry the rhythm;
  * digits in the transcript become words before the model sees them ("720" -> "seven twenty");
  * it can never hang up on "No", and once it has decided to hang up it stops answering.

Borrowed from the production voice-agent stacks:
  * the reply starts on the INTERIM transcript and is kept if the final reads the same (preemptive generation);
  * on an interruption the history keeps only the words that were heard, the rest is parked, and a false
    interruption (a cough, an "uh-huh") resumes it instead of losing it; a "Yes" over the tail of our question
    is the answer, not a nod;
  * it never starts a sentence while the far party's turn is open;
  * the phone closes a turn when the person SOUNDS finished (smart-turn v3) AND the words agree, not after a
    fixed silence, and only treats sustained speech as an interruption;
  * a slow or failed model gets a neutral filler (opt-in) or a "could you say that again?", never silence;
  * silence gets a nudge before a hang-up.
"""
from __future__ import annotations

import argparse
import collections
import datetime
import concurrent.futures as cf
import http.client
import json
import os
import queue
import random
import re
import signal
import ssl
import sys
import threading
import time
import urllib.request
from typing import Callable, Iterator, Optional, Union

# A responder returns either a finished reply, or an iterator of sentences to speak as they are generated
# (optionally ending with a HangUp when the model decided to end the call).
Responder = Callable[[str, list], Union[str, None, Iterator]]

try:
    from websockets.sync.client import connect as ws_connect  # type: ignore
except Exception:  # pragma: no cover - dependency is optional until you actually run this
    ws_connect = None


# ---------------------------------------------------------------- single-instance guard


def lock_path(base: str) -> str:
    """
    One driver per PHONE, not per machine. Every phone's gateway runs its own driver; a machine-wide lock let a
    call on the OnePlus force-kill a live call's driver on the Pixel (found in review, 2026-09-24).
    """
    import hashlib
    key = hashlib.sha1(base.strip().rstrip("/").lower().encode()).hexdigest()[:12]
    return os.path.join(os.path.expanduser("~"), f".symbiosis-converse-{key}.pid")


def _is_converse_process(pid: int) -> bool:
    """
    Is [pid] still one of us? PIDs are recycled; a stale lock file must never get an unrelated process killed.
    Verified via the command line when psutil is present, via the image name otherwise; unknown means no.
    """
    try:
        import psutil  # type: ignore
        return any("converse" in part for part in psutil.Process(pid).cmdline())
    except ImportError:
        pass
    except Exception:
        return False
    try:
        if sys.platform == "win32":
            import subprocess
            out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV", "/NH"],
                                 capture_output=True, text=True).stdout
            if "python" not in out.lower():
                return False
            # Any python process is not necessarily a driver, and since a live driver now makes a new call
            # REFUSE to start, a recycled pid owned by some unrelated script would block every call. Ask for
            # the command line (no psutil on this box); if even that is unavailable, assume it is one of us -
            # refusing a call is recoverable, two drivers on one phone is not.
            try:
                cl = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command",
                                     f"(Get-CimInstance Win32_Process -Filter 'ProcessId={int(pid)}').CommandLine"],
                                    capture_output=True, text=True, timeout=10).stdout
            except Exception:
                return True
            return "converse" in cl.lower() if cl.strip() else True
        with open(f"/proc/{pid}/cmdline", "rb") as f:
            return b"converse" in f.read()
    except Exception:
        return False


def claim_singleton(base: str) -> None:
    """
    Enforce one driver at a time per phone.

    A live driver for the same phone is REFUSED, not replaced. This used to force-kill the previous instance on
    the theory that the newest invocation is always the intended one - but the old one may be holding a live
    call, and a hard kill skips its hang-up entirely: the call stayed up with nobody on it (review 2026-09-26).
    So: say so and exit non-zero, before anything is dialed. A stale lock (its pid is gone, or is no longer a
    driver) is reclaimed as before.
    """
    old = None
    try:
        with open(lock_path(base)) as f:
            old = int(f.read().strip())
    except Exception:
        pass
    if old and old != os.getpid() and _is_converse_process(old):
        print(f"[converse] ERROR: another conversation driver (pid {old}) is already running for this phone "
              f"({base}); refusing to start. Let it finish, or stop it gracefully so it hangs up its call. "
              f"Lock: {lock_path(base)}", flush=True)
        raise SystemExit(3)
    try:
        with open(lock_path(base), "w") as f:
            f.write(str(os.getpid()))
    except Exception:
        return
    import atexit

    def release() -> None:
        # Only our own lock: a later driver may legitimately hold it by now.
        try:
            with open(lock_path(base)) as f:
                mine = int(f.read().strip()) == os.getpid()
            if mine:
                os.remove(lock_path(base))
        except Exception:
            pass
    atexit.register(release)


# ---------------------------------------------------------------- transport


def pinned_ssl_context(base: str, pin: Optional[str]) -> ssl.SSLContext:
    """
    Trust exactly the phone's own self-signed certificate, identified by its SHA-256 fingerprint.

    The phone's TLS connector is meant for any agent on the LAN, so there is no CA to chain to; the pin IS the
    identity. Fetch the certificate the server presents, hash it, refuse unless it matches, and then trust
    precisely that certificate for the session. Verification stays on (CERT_REQUIRED against that cert);
    hostname checking is off because the cert is for an IP and the pin already answers "is this the right device".
    """
    import hashlib
    from urllib.parse import urlparse

    u = urlparse(base)
    host, port = u.hostname or "", u.port or 443
    want = (pin or "").replace(":", "").strip().upper()
    if not want:
        raise SystemExit("an https base requires the phone's TLS pin (secrets/device-tls-fingerprint or --pin)")
    pem = ssl.get_server_certificate((host, port), timeout=10)
    got = hashlib.sha256(ssl.PEM_cert_to_DER_cert(pem)).hexdigest().upper()
    if got != want:
        raise SystemExit(f"TLS pin mismatch for {host}: got {got[:12]}…, expected {want[:12]}… — refusing to connect")
    ctx = ssl.create_default_context(cadata=pem)
    ctx.check_hostname = False
    # Python 3.13 turns on VERIFY_X509_STRICT, which rejects a self-signed cert without CA extensions (an X.509
    # v1 cert) as "invalid CA certificate". Strict mode checks how a CA cert is built; here the one trusted cert
    # is the pinned leaf, so it adds nothing and only breaks the connection.
    strict = getattr(ssl, "VERIFY_X509_STRICT", 0)
    if strict and ctx.verify_flags & strict:
        ctx.verify_flags &= ~strict
    return ctx


class McpHttpError(RuntimeError):
    """A non-2xx answer from /mcp. The phone sent it instead of a JSON-RPC reply, so nothing was dispatched."""

    def __init__(self, status: int, body: str):
        super().__init__(f"MCP HTTP error {status}")
        self.status = status
        self.body = body

    @property
    def session_unknown(self) -> bool:
        """
        The phone no longer knows our session. McpHandler.kt answers 404 {"error": "unknown session; initialize
        again"} (or "missing Mcp-Session-Id") BEFORE it looks at the method, so re-initializing and resending is
        safe. It forgets sessions on an app restart, after a day, and past 8 per peer.
        """
        if self.status != 404:
            return False
        try:
            err = json.loads(self.body).get("error")
        except Exception:
            return False
        return isinstance(err, str) and "session" in err.lower()


class Runtime:
    """Minimal MCP Streamable HTTP client — just enough to call tools."""

    def __init__(self, base: str, token: str, pin: Optional[str] = None):
        self.base = base.rstrip("/")
        self.token = token
        self.session: Optional[str] = None
        self.ssl = pinned_ssl_context(self.base, pin) if self.base.startswith("https://") else None
        self._conn = None
        self._conn_lock = threading.Lock()
        self._init_lock = threading.Lock()

    def _connection(self):
        """One persistent connection: a fresh TLS handshake per call cost ~60-100 ms, all of it on the path
        between 'we have audio' and 'they hear it'."""
        import http.client
        from urllib.parse import urlparse
        if self._conn is None:
            u = urlparse(self.base)
            host = u.hostname or "127.0.0.1"
            if self.ssl is not None:
                self._conn = http.client.HTTPSConnection(host, u.port or 443, context=self.ssl, timeout=120)
            else:
                self._conn = http.client.HTTPConnection(host, u.port or 80, timeout=120)
        return self._conn

    def _post(self, body: dict, session: Optional[str] = None):
        headers = {"Content-Type": "application/json", "Authorization": "Bearer " + self.token,
                   "MCP-Protocol-Version": "2025-06-18"}
        if session:
            headers["Mcp-Session-Id"] = session
        data = json.dumps(body).encode()
        with self._conn_lock:
            for attempt in (1,):
                conn = self._connection()
                try:
                    conn.request("POST", "/mcp", body=data, headers=headers)
                except (http.client.HTTPException, OSError):
                    # request() can fail after sending part or all of the body. The outcome is unknown;
                    # replaying here could place a second call or send a second SMS.
                    self._drop(conn)
                    raise
                try:
                    r = conn.getresponse()
                    txt = r.read().decode()
                    if not 200 <= r.status < 300:
                        raise McpHttpError(r.status, txt)
                    return dict(r.getheaders()), (json.loads(txt) if txt else None)
                except (http.client.HTTPException, OSError):
                    # The request DID leave. Re-sending would place a second call or play a clip twice, so this
                    # is an unknown outcome, not a retry. (Call 2's history carries the opening line twice.)
                    self._drop(conn)
                    raise
        raise RuntimeError("unreachable")

    def _drop(self, conn) -> None:
        try:
            conn.close()
        except Exception:
            pass
        self._conn = None

    def init(self):
        headers, body = self._post({
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                       "clientInfo": {"name": "converse", "version": "2"}},
        })
        if (not isinstance(body, dict) or body.get("jsonrpc") != "2.0" or body.get("id") != 1
                or not isinstance(body.get("result"), dict) or body.get("error")):
            raise RuntimeError("MCP initialize did not return a valid result")
        self.session = next((v for k, v in headers.items() if k.lower() == "mcp-session-id"), None)
        if not self.session:
            raise RuntimeError("MCP initialize did not return a session ID")
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"}, self.session)
        return self.session

    def request(self, method: str, params: dict, rid: int = 2):
        """
        One session-bound JSON-RPC request; returns the response body. If the phone has forgotten the session,
        initialize again and resend ONCE. Without this a long-running daemon (tools/inbound.py) failed every
        call after the phone app restarted, while its event socket reconnected and it looked healthy.
        """
        with self._init_lock:
            if not self.session:
                self.init()
            session = self.session
        msg = {"jsonrpc": "2.0", "id": rid, "method": method, "params": params}
        try:
            return self._post(msg, session)[1]
        except McpHttpError as e:
            if not e.session_unknown:
                raise
        with self._init_lock:
            # Another thread may have replaced the session while this one waited; use its new one.
            if self.session == session:
                self.session = None
                self.init()
            session = self.session
        return self._post(msg, session)[1]

    def call(self, tool: str, args: dict, meta: Optional[dict] = None) -> dict:
        """meta: MCP _meta for the call, e.g. {"approvalWaitMs": 90000} to hold a policy approval open longer."""
        params: dict = {"name": tool, "arguments": args}
        if meta:
            params["_meta"] = meta
        body = self.request("tools/call", params)
        if not isinstance(body, dict):
            return {"error": "MCP response missing; action outcome is unknown"}
        if body.get("jsonrpc") != "2.0" or body.get("id") != 2:
            return {"error": "MCP response envelope mismatch; action outcome is unknown"}
        if body.get("error"):
            return {"error": body["error"]}
        result = body.get("result")
        if not isinstance(result, dict):
            return {"error": "MCP result missing; action outcome is unknown"}
        structured = result.get("structuredContent")
        if result.get("isError"):
            out = dict(structured) if isinstance(structured, dict) else {}
            out["error"] = out.get("error") or result.get("content") or "MCP tool failed"
            return out
        if not isinstance(structured, dict):
            return {"error": "MCP structured result missing; action outcome is unknown"}
        return structured


# ---------------------------------------------------------------- transcript normalisation


_ONES = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
         "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"]
_TENS = ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"]


def num_to_words(n: int) -> str:
    if n < 20:
        return _ONES[n]
    if n < 100:
        return _TENS[n // 10] + ("" if n % 10 == 0 else " " + _ONES[n % 10])
    if n < 1000:
        return _ONES[n // 100] + " hundred" + ("" if n % 100 == 0 else " " + num_to_words(n % 100))
    if n < 10000:
        return num_to_words(n // 1000) + " thousand" + ("" if n % 1000 == 0 else " " + num_to_words(n % 1000))
    return str(n)


def _time_words(h: int, m: int, ampm: str) -> str:
    hw = num_to_words(h)
    if m == 0 and ampm:
        return f"{hw} {ampm}"          # "four PM", not "four o'clock PM"
    if m == 0:
        mw = "o'clock"
    elif m < 10:
        mw = "oh " + _ONES[m]
    else:
        mw = num_to_words(m)
    return f"{hw} {mw}{(' ' + ampm) if ampm else ''}".strip()


def normalize_transcript(text: str) -> str:
    """
    Rewrite what ASR gives us into what a person would have *said*, before the model sees it.

    The recogniser emits digits ("Does 720 work", "7 20 p.m."), but the model was trained to speak numbers as
    words and misreads digit input — it turned 7:20 into "seven o'clock". Times are the common case on a booking
    call, so they get special handling; any other integer becomes words.
    """
    t = text

    def ampm_of(s: Optional[str]) -> str:
        if not s:
            return ""
        s = s.lower().replace(".", "").replace(" ", "")
        return "PM" if s.startswith("p") else "AM"

    # "7:20 pm", "7 20 p.m.", "7.30pm", "11 15"
    def sep_time(m: re.Match) -> str:
        h, mi = int(m.group(1)), int(m.group(2))
        if 1 <= h <= 12 and 0 <= mi < 60:
            return _time_words(h, mi, ampm_of(m.group(3)))
        return m.group(0)
    # The am/pm group owns its own leading whitespace, so a bare "7 20 works" keeps the space after "twenty".
    t = re.sub(r"\b(\d{1,2})[:.\s](\d{2})(?:\s*(a\.?m\.?|p\.?m\.?))?\b", sep_time, t, flags=re.I)

    # "720", "1130" — a bare 3-4 digit block that parses as a plausible clock time
    def packed_time(m: re.Match) -> str:
        s = m.group(1)
        h, mi = int(s[:-2]), int(s[-2:])
        if 1 <= h <= 12 and 0 <= mi < 60:
            return _time_words(h, mi, ampm_of(m.group(2)))
        return m.group(0)
    t = re.sub(r"\b(\d{3,4})(?:\s*(a\.?m\.?|p\.?m\.?))?\b", packed_time, t, flags=re.I)

    # "7pm", "7 pm" -> seven PM
    t = re.sub(r"\b(\d{1,2})\s*(a\.?m\.?|p\.?m\.?)\b",
               lambda m: _time_words(int(m.group(1)), 0, ampm_of(m.group(2))) if 1 <= int(m.group(1)) <= 12 else m.group(0),
               t, flags=re.I)

    # "$20", "20 dollars"
    t = re.sub(r"\$\s?(\d+)", lambda m: num_to_words(int(m.group(1))) + " dollars", t)

    # any remaining integer
    t = re.sub(r"\b\d{1,4}\b", lambda m: num_to_words(int(m.group(0))), t)
    return " ".join(t.split())


# ---------------------------------------------------------------- hang-up judgement


_NEGATION = re.compile(r"\b(no|nope|nah|not|don't|dont|doesn't|isn't|can't|cannot|wait|hold on|hang on|"
                       r"actually|but|wrong|incorrect|never mind|nevermind)\b", re.I)
_QUESTION_START = re.compile(r"^\s*(what|when|where|which|who|how|why|can|could|would|will|do|does|did|is|are|"
                             r"was|were|should|may|any|and)\b", re.I)
# An OFFER is not agreement: "Okay, what about seven twenty?" contains "okay" and is a question the agent
# still has to answer — hanging up right after accepting it (live call 3) skipped the host's confirmation.
_OFFER = re.compile(r"\b(what|how) about\b|\b(does|would|will|is|are|can|could)\b.*\b(work|okay|ok|fine|good|"
                    r"available|free|open)\b", re.I)
_AFFIRMATIVE = re.compile(r"\b(yes|yeah|yep|yup|sure|okay|ok|great|perfect|awesome|cool|wonderful|excellent|"
                          r"fantastic|lovely|alright|all right|will do|sounds (good|great)|that works|works for me|"
                          r"all set|you're all set|see you|bye|goodbye|cheers|you too|thanks|thank you|confirmed|"
                          r"booked|got it|no problem|have a (good|great|nice) (one|day|night|evening|afternoon|"
                          r"morning|weekend|week))\b", re.I)

# "Is there anything else?" -> "No, I'm good, thanks." Starting with "no" does not make it a refusal: it is the
# most common way a call ends. Checked BEFORE the negation guard, and only for these exact closing shapes, so
# "No, that time doesn't work" still blocks a hang-up. Found on live call 7: the guard vetoed every one of these.
# Anchored at BOTH ends: after the closing phrase only thanks and farewells may follow. Unanchored, "No that's
# all wrong" and "No that's it but can you text me the address" read as goodbyes (found in review).
_CLOSING_TRAILER = (r"(?:[\s,.!]*(?:thanks?(?: you)?(?: (?:so much|very much|a lot))?|(?:good ?)?bye(?: now)?|"
                    r"take care|(?:i )?appreciate (?:it|you)|you too|"
                    r"have a (?:good|great|nice|lovely|wonderful) (?:one|day|night|evening|afternoon|morning|weekend|week)))*"
                    r"[\s,.!]*$")
_CLOSING_DECLINE = re.compile(r"^\s*(?:no|nope|nah|na)\b[\s,.!]*"
                              r"(?:(?:thanks?(?: you)?[\s,.!]*)?"
                              r"(?:that'?s (?:it|all|everything)|that is (?:it|all)|"
                              r"i'?m (?:good|fine|all set|all good|ok(?:ay)?)|we'?re (?:good|all set|fine)|all good|"
                              r"nothing else|that'?ll be all|i think that'?s it)"
                              r"|thanks?(?: you)?(?: (?:so much|very much|a lot))?)"   # a bare "No thanks" / "No thank you" - the commonest closer
                              + _CLOSING_TRAILER, re.I)

# Every "have a good ..." a person actually says. Only evening/day/night/one were here, so "have a good
# afternoon" - live call 7, placed at 1:48 pm - was not heard as a goodbye: the call stayed open, went quiet,
# and the agent asked "are you still there?" straight after saying goodbye itself.
_FAREWELL = re.compile(r"\b(good ?bye|bye( now)?|see you( then| tonight| soon| later)?|take care|take it easy|"
                       r"talk (to you )?(soon|later)|"
                       r"have a (good|great|nice|lovely|wonderful) (one|day|night|evening|afternoon|morning|weekend|week)|"
                       r"enjoy (the rest of )?your (day|evening|afternoon|morning|weekend|night))\b", re.I)



# Fillers and acknowledgements a real turn opens with, before the question actually starts: "um, where is
# it", "oh okay, what time". The phone's own transcript carries no punctuation at all, so a trailing "?"
# cannot be the test - on live call 6 "Um where is it" was swallowed whole and Taylor was hung up on.
_LEAD_IN = re.compile(r"^(?:\s*\b(?:um+|uh+|er+|oh|ah|so|but|well|okay|ok|yeah|yes|hey|hi|hello|"
                      r"sorry|actually|wait|now|then|alright|right)\b[\s,.!-]*)+", re.I)


def is_question(text: str) -> bool:
    """
    Does their turn ask something? Used to call off a hang-up that is already in flight, so a late question
    gets answered instead of talked over. Deliberately generous: answering something that was not a question
    costs one turn, while missing one hangs up on a person mid-sentence.
    """
    t = " ".join((text or "").split())
    if not t:
        return False
    return "?" in t or bool(_QUESTION_START.search(_LEAD_IN.sub("", t)))


def agent_said_farewell(sentences: list) -> bool:
    """
    Did the agent just say goodbye? A model that says "Great, goodbye." and then forgets to call the hang_up
    tool leaves the far party talking to a line that answers every "bye" with another "Goodbye." (three times,
    on a live call). The spoken farewell IS the decision; the tool call is just the mechanism.

    Only the LAST sentence counts, and never a reply that still asks something: "I'll say goodbye once it's
    confirmed — does seven work?" mentions goodbye and is not one.
    """
    said = [s for s in sentences if s]
    if not said or any("?" in s for s in said):
        return False
    return bool(_FAREWELL.search(said[-1]))


# Words a sentence does not end on (mirrors CallConversation.DANGLING on the phone). A final transcript ending
# here is held for HOLD_FRAGMENT_S in case the rest of the thought arrives as the next turn.
_DANGLING = {"about", "at", "for", "to", "of", "with", "on", "in", "from", "by", "into", "than", "and", "or", "but",
             "so", "the", "a", "an", "my", "your", "our", "their", "is", "are", "was", "were", "do", "does", "did",
             "can", "could", "would", "should", "will", "have", "has", "had", "if", "when", "what", "which", "how",
             "that", "this", "these", "those", "um", "uh", "like", "just", "very", "really", "maybe", "around"}
HOLD_FRAGMENT_S = 1.2


def looks_unfinished(text: str) -> bool:
    # Digits stay: "what about 720" ends in a number, not in "about" (stripping them held that turn 1.2 s).
    words = re.sub(r"[^a-z0-9' ]", "", (text or "").lower()).split()
    return bool(words) and words[-1] in _DANGLING


def far_party_affirmative(last_text: str) -> bool:
    """
    May the agent hang up right after the far party said this?

    A small model will happily call hang_up after the other person says "No". The far party's own last turn is a
    far better signal than the model's judgement: hang up only after something that reads as agreement or a
    farewell, never after a negation, a question, or a bare one-word reply that isn't clearly a yes.
    """
    s = (last_text or "").strip()
    if not s:
        return False
    if s.endswith("?") or _QUESTION_START.search(s) or _OFFER.search(s):
        return False
    if _CLOSING_DECLINE.search(s):
        return True
    if _NEGATION.search(s):
        return False
    if _AFFIRMATIVE.search(s):
        return True
    return len(s.split()) >= 3


# ---------------------------------------------------------------- responders


def scripted_responder() -> Responder:
    """Rule-based replies so the loop can be exercised on a real call with no model at all."""
    rules = [
        (r"\b(hello|hi|hey)\b", "Hello. This is an automated assistant. Can you hear me clearly?"),
        (r"\b(yes|yeah|yep|i can)\b", "Great. I can hear you too. What would you like to test next?"),
        (r"\b(no|nope|cannot|can't)\b", "Understood. I will speak up. Is this better?"),
        (r"\b(book|table|reserv|appointment|schedul)\w*", "Sure. What day and time did you have in mind?"),
        (r"\b(time|clock)\b", lambda: "It is " + time.strftime("%I:%M %p") + "."),
        (r"\b(name)\b", "My name is Android Agent Harness. I am running on the phone itself."),
        (r"\b(bye|goodbye|hang up|thank you|thanks)\b", "Goodbye. Ending the call now."),
        (r"\b(repeat|say again|what)\b", "I said: this is an automated assistant running on the phone."),
    ]

    def respond(text: str, history: list) -> Optional[str]:
        del history  # scripted replies are stateless by design
        low = text.lower()
        for pattern, reply in rules:
            if re.search(pattern, low):
                return str(reply() if callable(reply) else reply)
        return f"I heard you say: {text}. I am a scripted responder, so I do not have much else to add."

    return respond


LOCAL_ROUTER = os.environ.get("SYMBIOSIS_LLM_BASE", "http://127.0.0.1:11430/v1")
LOCAL_DEFAULT_MODEL = "ornith-9b-uncensored"   # the router's resident `fast` lane; `ornith-1.5-9b` is an alias

# The hosted brains (Luna, Terra, Sonnet) pass tools/judgement_eval.py 5/5 on the phone rules alone. The
# router's 9B does not: measured 2026-09-21 it wrote "7:20 PM" in digits and said goodbye without calling
# hang_up — the two failures that produced the mangled-time and goodbye-loop live calls. Spelling both out
# fixes them, at the cost of prefill the hosted brains should not pay for, so it is per-brain, not global.
SMALL_MODEL_RULES = (
    "\n\nCRITICAL OUTPUT RULES — these override everything above:\n"
    "1. NEVER write digits. Not \"7:20\", not \"7.23pm\", not \"469\". Every number, time, date and phone "
    "number is words only: \"seven twenty\", \"seven twenty-three p.m.\", \"four six nine\". A digit in your "
    "reply is a failure.\n"
    "2. When the other person ends the call (goodbye, bye, see you, you're all set), you MUST call the hang_up "
    "tool in that same turn as well as saying a short goodbye. A goodbye without hang_up is a failure.\n"
    "3. Call hang_up ONLY on that farewell. Never while they are asking you something, never to answer a "
    "question, never in the first two turns.\n"
    "4. Never narrate what you are doing. Not \"I'll hang up now\", not \"let me check\".\n"
    "5. You are the ASSISTANT of the person named in your instructions, not that person. Say \"I'm calling "
    "for <name>\" or \"this is <name>'s assistant\" — never \"my name is <name>\".\n"
    "6. EXACTLY ONE sentence per reply. Stop at the first full stop. No follow-up question, no thank-you, no "
    "confirmation after it — the other person gets the next turn. A second sentence is a failure.\n"
)


def is_small_model(model: str) -> bool:
    """The local router's own models, which need the extra rules and whose tool calls are not trusted alone."""
    return "ornith" in model or "bonsai" in model


def rules_for(model: str) -> str:
    """Extra system-prompt rules a given model needs. Only the small local ones do."""
    return SMALL_MODEL_RULES if is_small_model(model) else ""


class HangUp:
    """Yielded by a streaming responder, after its sentences, when the model called the hang_up tool."""

    def __init__(self, reason: str):
        self.reason = reason


# Messages of history the model sees. Ten (five exchanges) lost the opening and the first offer by mid-call;
# a booking call rarely exceeds this, and the tokens are cheap.
# How long the line stays open after our goodbye finishes playing, for a last question. Their turn needs
# ~0.5-0.9s of silence just to be endpointed, so anything shorter cuts questions off mid-word.
CLOSING_GRACE = 1.6

# After OUR goodbye, this much silence ends the call. Well under the idle nudge (8 s), so a farewell is never
# followed by "are you still there?", and long enough for their own "bye" to arrive first.
AFTER_GOODBYE_S = 3.5

HISTORY_TURNS = 24


class Brain:
    """One model behind an OpenAI-compatible endpoint, with whatever extra rules it needs to behave."""

    def __init__(self, base: str, model: str, reasoning: Optional[str] = "none",
                 rules: str = "", api_key: Optional[str] = None, trust_tools: bool = True):
        self.base = base.rstrip("/")
        self.model = model
        self.reasoning = reasoning
        self.rules = rules
        self.api_key = api_key
        # Does a hang_up tool call from this model mean the call is over? For the hosted brains, yes. The
        # router's 9B fires it on about half of all turns, including mid-booking questions (measured
        # 2026-09-21), so for it the tool is only a vote: the spoken farewell has to agree before we hang up.
        self.trust_tools = trust_tools

    @property
    def hosted(self) -> bool:
        return self.base.startswith("https://")

    def __str__(self) -> str:
        host = self.base.split("//", 1)[-1].split("/", 1)[0]
        return f"{self.model}@{host}"


def _local_responder(system: str, primary: Brain, fallback: Optional[Brain],
                     first_token_timeout: float) -> Responder:
    # A sentence ends at . ! ? — except the period of "a.m." / "p.m.", which split "seven p.m. isn't free"
    # into two clips on a live call.
    sentence_end = re.compile(r"(?<![ap]\.m\.)(?<=[.!?])\s+", re.I)
    TOOLS = [{
        "type": "function",
        "function": {
            "name": "hang_up",
            "description": ("Hang up the phone RIGHT NOW. This physically ends the call immediately. Call it only "
                            "when the thing you called about is settled one way or the other — done, answered, or "
                            "refused — and both sides have said goodbye. Do NOT call it to describe an intention, "
                            "to note what still needs doing, or while any question is unanswered."),
            "parameters": {
                "type": "object",
                "properties": {"reason": {"type": "string", "description": "Why the call is being ended"}},
                "required": ["reason"],
            },
        },
    }]

    # One connection pool per host: the primary and the fallback can live on different boxes (a hosted brain
    # that stalls is re-asked of the LAN router, which answers in half a second).
    pools: dict = {}

    def open_stream(brain: Brain, messages: list, timeout: float) -> "_Stream":
        body = {"model": brain.model, "messages": messages, "stream": True, "temperature": 0.7, "tools": TOOLS}
        # One short sentence is the goal; 60 tokens is a ceiling, not a target. OpenAI's GPT-5.x reject the
        # classic max_tokens field; the router and the other hosts still expect it.
        body["max_completion_tokens" if "openai.com" in brain.base else "max_tokens"] = 60
        # The router's general models need "none" or they think the whole budget away; hosted APIs take
        # "low"/"minimal" or nothing at all (Anthropic's compatible endpoint ignores it; some reject "none").
        if brain.reasoning:
            body["reasoning_effort"] = brain.reasoning
        headers = {"Content-Type": "application/json", "Accept": "text/event-stream"}
        if brain.api_key:
            headers["Authorization"] = "Bearer " + brain.api_key
        pool = pools.get(brain.base) or pools.setdefault(brain.base, _StreamPool(brain.base))
        return pool.post(json.dumps(body).encode(), headers, timeout)

    def events(s: "_Stream"):
        """SSE lines -> (content_delta, tool_name_delta, tool_args_delta)."""
        for raw in s.lines():
            line = raw.decode(errors="replace").strip()
            if not line.startswith("data:"):
                continue
            line = line[5:].strip()
            if line == "[DONE]":
                s.finished()
                return
            try:
                o = json.loads(line)
            except Exception:
                continue
            delta = ((o.get("choices") or [{}])[0]).get("delta", {}) or {}
            tname, targs = None, ""
            for tc in (delta.get("tool_calls") or []):
                fn = tc.get("function") or {}
                tname = fn.get("name") or tname
                targs += fn.get("arguments") or ""
            yield (delta.get("content") or ""), tname, targs

    def messages_for(brain: Brain, text: str, history: list) -> list:
        # Each brain carries its own extra rules: the small local models need spelling out what the hosted
        # ones already do (numbers as words, call hang_up on a goodbye), and those tokens are prefill the
        # hosted brains should not pay for.
        msgs = [{"role": "system", "content": system + brain.rules}]
        msgs += [{"role": h["role"], "content": h["text"]} for h in history[-HISTORY_TURNS:]]
        msgs.append({"role": "user", "content": text})
        return msgs

    def respond_stream(text: str, history: list):
        # The socket timeout doubles as the first-token deadline: nothing has been yielded yet when it fires,
        # so the whole turn can be re-asked — of the fallback brain (a different box entirely, if that is how
        # it is configured), or of the same one with a longer deadline (a p99 first token is slow, not broken).
        attempts = [(primary, first_token_timeout)]
        if fallback is not None and (fallback.model, fallback.base) != (primary.model, primary.base):
            attempts.append((fallback, 60.0))
        else:
            attempts.append((primary, max(8.0, first_token_timeout * 3)))
        r = None
        first = None
        ev = None
        used = attempts[0][0]
        for i, (brain, deadline) in enumerate(attempts):
            try:
                r = open_stream(brain, messages_for(brain, text, history), deadline)
                ev = events(r)
                first = next(ev, None)
                used = brain
                break
            except Exception as e:
                if r is not None:
                    r.close()
                    r = None
                if i + 1 < len(attempts):
                    nxt = attempts[i + 1]
                    print(f"[responder] {brain} gave nothing in {deadline}s ({type(e).__name__}); "
                          f"re-asking {nxt[0]} (deadline {nxt[1]:.0f}s)", flush=True)
                else:
                    raise
        if first is None or r is None or ev is None:
            if r is not None:
                r.close()
            return
        r.first_token_seen()

        buf = ""
        said: list = []
        tool_name = None
        tool_args = ""

        def take(content, tname, targs):
            nonlocal buf, tool_name, tool_args
            if tname:
                tool_name = tname
            tool_args += targs
            out = []
            if not content:
                return out
            buf += content
            while True:
                parts = sentence_end.split(buf, 1)
                if len(parts) < 2:
                    break
                done, buf = parts[0].strip(), parts[1]
                if done:
                    out.append(done)
                    said.append(done)
            # A long sentence still in progress: speak its first clause now rather than waiting for the
            # full stop. The synthesizer is happy with a fragment ending in a comma, and the first sound
            # arrives that much sooner on the turns where the model ignores the "one short sentence" rule.
            if len(buf.split()) >= 12:
                m = re.search(r"^(.{20,}?,)\s+(?=\S)", buf)
                if m and len(m.group(1).split()) >= 6:
                    out.append(m.group(1).strip())
                    said.append(m.group(1).strip())
                    buf = buf[m.end():]
            return out

        try:
            for s in take(*first):
                yield s
            for item in ev:
                for s in take(*item):
                    yield s
        except GeneratorExit:
            # The driver abandoned this turn (they spoke again): release the stream and stop.
            r.close()
            raise
        except Exception as e:
            # Mid-stream stall (a read timeout after the first token): say what we have rather than nothing.
            print(f"[responder] stream ended early: {type(e).__name__}", flush=True)
        finally:
            r.close()
        if buf.strip():
            said.append(buf.strip())
            yield buf.strip()
        if tool_name == "hang_up":
            reason = ""
            try:
                reason = (json.loads(tool_args) or {}).get("reason", "")
            except Exception:
                pass
            if not used.trust_tools and said and not agent_said_farewell(said):
                # This model calls hang_up while the other person is still asking things. Without a spoken
                # farewell to agree with it, it is noise; the driver's own farewell rule still ends the call
                # when the conversation really is over. A reply with NO text at all is the exception: the
                # tool call is then the only thing the model produced, and dropping it too would leave the
                # far party in silence — so it is passed on and the driver's own guards decide.
                print(f"[responder] ignoring hang_up from {used}: it did not say goodbye ({reason[:50]!r})", flush=True)
                return
            # A bare hang_up with no words is NOT patched here. The far party must never get silence and then
            # a dead line, but the driver already covers that, and covers it in the only place that knows
            # whether the call is really ending: after its own guards (not in the first two turns, not unless
            # their last turn was agreement) have accepted the hang-up, it speaks a goodbye itself when the
            # generation said nothing. Doing it here instead would speak a goodbye into a call the driver
            # then decides to keep going.
            # The decision travels with THIS generation's sentences. A shared flag was popped by whichever
            # turn came next, so an abandoned generation's hang_up could end the call one turn late.
            yield HangUp(reason or "model ended the call")

    def warm() -> None:
        """Load both brains before the call so neither's first turn pays a cold start."""
        seen = set()
        for brain in (primary, fallback):
            if brain is None or (brain.model, brain.base) in seen:
                continue
            seen.add((brain.model, brain.base))
            t0 = time.time()
            r = None
            try:
                # With the real system prompt, so the server's prefix cache is hot for the first real turn too.
                r = open_stream(brain, [{"role": "system", "content": system + brain.rules},
                                        {"role": "user", "content": "Say: ready."}], 120)
                for _ in events(r):
                    pass  # drain, so the connection goes back to the pool clean
                print(f"[responder] {brain} warm ({time.time()-t0:.1f}s to first token)", flush=True)
            except Exception as e:
                print(f"[responder] could not warm {brain}: {e}", flush=True)
            finally:
                if r is not None:
                    r.close()

    respond_stream.warm = warm  # type: ignore[attr-defined]
    return respond_stream


class _Stream:
    """One in-flight chat completion on a pooled connection."""

    def __init__(self, pool: "_StreamPool", conn, resp):
        self.pool, self.conn, self.resp = pool, conn, resp
        self._clean = False
        self._closed = False

    def lines(self):
        while True:
            raw = self.resp.readline()
            if not raw:
                return
            yield raw

    def first_token_seen(self) -> None:
        """The first-token deadline has passed; the rest of the stream gets a generous read timeout."""
        try:
            self.conn.sock.settimeout(60.0)
        except Exception:
            pass

    def finished(self) -> None:
        """[DONE] arrived: drain the trailer so the connection can be reused."""
        try:
            self.resp.read()
            self._clean = True
        except Exception:
            self._clean = False

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        self.pool.release(self.conn, reusable=self._clean)


class _StreamPool:
    """
    Persistent connections to the chat-completions host, one per in-flight stream.

    `urllib` opened a new TCP + TLS connection for every turn: ~100-150 ms of handshake to api.openai.com on
    the critical path of every reply. A preemptive and a real generation can overlap, so this is a small pool
    rather than a single connection.
    """

    def __init__(self, base: str):
        from urllib.parse import urlparse
        u = urlparse(base.rstrip("/"))
        self.https = u.scheme == "https"
        self.host = u.hostname or "127.0.0.1"
        self.port = u.port or (443 if self.https else 80)
        self.path = (u.path or "").rstrip("/") + "/chat/completions"
        self.ssl = ssl.create_default_context() if self.https else None
        self._idle: list = []
        self._lock = threading.Lock()

    def _new(self, timeout: float):
        if self.https:
            return http.client.HTTPSConnection(self.host, self.port, context=self.ssl, timeout=timeout)
        return http.client.HTTPConnection(self.host, self.port, timeout=timeout)

    def post(self, body: bytes, headers: dict, timeout: float) -> _Stream:
        headers = {**headers, "Host": self.host}
        with self._lock:
            conn = self._idle.pop() if self._idle else None
        for attempt in (1, 2):
            if conn is None:
                conn = self._new(timeout)
            try:
                conn.timeout = timeout
                if conn.sock is not None:
                    conn.sock.settimeout(timeout)
                conn.request("POST", self.path, body=body, headers=headers)
                resp = conn.getresponse()
            except (http.client.HTTPException, OSError):
                # An idle pooled connection the server has since closed: retry once on a fresh one. A stream
                # request is idempotent (nothing has been spoken yet), unlike the phone's tool calls.
                try:
                    conn.close()
                except Exception:
                    pass
                conn = None
                if attempt == 2:
                    raise
                continue
            if resp.status != 200:
                txt = resp.read().decode(errors="replace")[:300]
                self.release(conn, reusable=False)
                raise RuntimeError(f"HTTP {resp.status} from {self.host}: {txt}")
            return _Stream(self, conn, resp)
        raise RuntimeError("unreachable")

    def release(self, conn, reusable: bool) -> None:
        if reusable:
            with self._lock:
                if len(self._idle) < 4:
                    self._idle.append(conn)
                    return
        try:
            conn.close()
        except Exception:
            pass


# ---------------------------------------------------------------- what the call achieved


OUTCOMES = ("done", "partial", "failed", "no_answer", "unclear")

OUTCOME_SCHEMA = """Return ONE JSON object and nothing else:
{"outcome": "done|partial|failed|no_answer|unclear",
 "summary": "one sentence an assistant would tell the user, in the past tense",
 "facts": {"<thing learned>": "<value, as the other party said it>"},
 "we_promised": ["anything the CALLER said they would do - check back, find out, call again, pass a message"],
 "they_promised": ["anything the OTHER PARTY said they would do, and by when if they said"],
 "follow_up": "what still needs doing, or null"}

outcome — read the task as a list of everything it asked for, and grade against ALL of it:
  done       every part was accomplished or answered.
  partial    some of it was, but not all. A question left unanswered, or an answer promised for later,
             is partial even when the other person was helpful and the call went well.
  failed     they were reached and the answer was no, or it could not be done at all.
  no_answer  nobody was reached: voicemail, a menu, silence, hung up on.
  unclear    the transcript does not say.
Put every concrete detail in `facts` (times, dates, names, prices, reference numbers, opening hours,
whether something is ready) as plain words. Facts only: never write a fact, a promise or a summary
clause that was not actually said on the call.

`we_promised` matters most: a real person is now waiting on each of those, so list them exactly, in the
caller's own terms ("check with Alex and call back about the dinner location"). "I'll let him know" counts.
If the caller promised nothing, it is an empty list - do not invent one to look thorough."""


def call_context(now=None) -> str:
    """
    What the far party takes for granted and the model had no way to know: what day it is.

    Without this, "book a table tomorrow at seven" is a phrase the model can only parrot. It cannot turn it
    into a weekday, cannot answer "so that's Thursday the twelfth?", and cannot notice when the day it is
    offered is not the day it asked for - which is exactly what the readback rule is supposed to catch. The
    driver runs off-phone, so local time here is the caller's time.
    """
    now = now or datetime.datetime.now()
    tz = time.strftime("%Z") or "local time"
    tomorrow = now + datetime.timedelta(days=1)
    dash = "-" if os.name != "nt" else "#"   # strftime's no-pad flag differs on Windows
    d = tomorrow.day
    nth = "th" if 4 <= d % 100 <= 20 else {1: "st", 2: "nd", 3: "rd"}.get(d % 10, "th")
    return (
        "\n\n--- WHEN THIS CALL IS HAPPENING ---\n"
        f"Right now it is {now.strftime(f'%A, %{dash}d %B %Y, %{dash}I:%M %p')} ({tz}).\n"
        f"So \"today\" is {now.strftime('%A')}, \"tomorrow\" is {tomorrow.strftime('%A the ')}{d}{nth}, and "
        f"\"this evening\" is tonight.\n"
        "- Say days the way a person does - \"tomorrow\", \"Thursday\", \"this Saturday\" - not a full date,\n"
        "  unless they give you one, in which case use theirs.\n"
        "- You now know which weekday any date falls on, so CHECK what they tell you against what you asked\n"
        "  for. If you asked for Thursday and they say the twelfth, work out whether those agree before you\n"
        "  confirm anything.\n"
    )


def summarize_outcome(brain: "Brain", task: str, history: list, ended: str = "",
                      timeout: float = 45.0) -> Optional[dict]:
    """
    Turn the transcript into a machine-readable result, so a calling agent gets an ANSWER rather than a
    conversation to read. This runs after the call, off every critical path - correctness over speed.

    Same brain that held the call, one non-streaming request, no phone rules (this is not speech). Returns
    None rather than raising: a failed summary must never cost us the transcript.
    """
    if not history:
        return {"outcome": "no_answer", "summary": "Nobody was reached; nothing was said on the call.",
                "facts": {}, "we_promised": [], "they_promised": [], "follow_up": None}
    lines = "\n".join(f"{'THEM' if h['role'] == 'user' else 'US'}: {h['text']}" for h in history)
    body = {
        "model": brain.model,
        "messages": [
            {"role": "system", "content": "You read a phone-call transcript and report what it achieved. "
                                          "You are precise and you never add anything that was not said.\n\n"
                                          + OUTCOME_SCHEMA},
            {"role": "user", "content": f"{call_context().strip()}\n\nThe caller's task was:\n{task.strip()}\n\n"
                                        + (f"The call ended: {ended}\n\n" if ended else "")
                                        + f"Transcript ('US' is the caller):\n{lines}"},
        ],
        "response_format": {"type": "json_object"},
    }
    # Same split as the streaming path: OpenAI renamed the field and rejects a non-default temperature on the
    # current models with a bare 400, so ask for neither there.
    if "openai.com" in brain.base:
        body["max_completion_tokens"] = 900
    else:
        body["max_tokens"] = 900
        body["temperature"] = 0
    if brain.reasoning:
        body["reasoning_effort"] = brain.reasoning
    headers = {"Content-Type": "application/json"}
    if brain.api_key:
        headers["Authorization"] = f"Bearer {brain.api_key}"
    req = urllib.request.Request(brain.base.rstrip("/") + "/chat/completions",
                                 data=json.dumps(body).encode(), headers=headers, method="POST")
    try:
        ctx = ssl.create_default_context() if brain.hosted else None
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
            txt = json.loads(r.read().decode())["choices"][0]["message"]["content"] or ""
    except Exception as e:
        print(f"[outcome] could not summarise ({type(e).__name__}: {e})", flush=True)
        return None
    # Small models ignore response_format and wrap the object in prose or a fence; take the outermost braces.
    i, j = txt.find("{"), txt.rfind("}")
    try:
        o = json.loads(txt[i:j + 1]) if i >= 0 and j > i else None
    except Exception:
        o = None
    if not isinstance(o, dict):
        print(f"[outcome] the model did not return JSON: {txt[:120]!r}", flush=True)
        return None
    if o.get("outcome") not in OUTCOMES:
        o["outcome"] = "unclear"
    o.setdefault("summary", "")
    o["facts"] = o.get("facts") if isinstance(o.get("facts"), dict) else {}
    # A model that ignored the split still hands back the old single list; treat it as ours, because an
    # obligation wrongly surfaced costs a glance and one wrongly dropped leaves a person waiting.
    legacy = o.pop("commitments", None)
    for k in ("we_promised", "they_promised"):
        o[k] = o[k] if isinstance(o.get(k), list) else []
    if isinstance(legacy, list) and legacy and not o["we_promised"]:
        o["we_promised"] = legacy
    if not o.get("follow_up"):
        o["follow_up"] = None
    return o


def local_responder(system: str, model: str = LOCAL_DEFAULT_MODEL,
                    base: str = LOCAL_ROUTER,
                    state: Optional[dict] = None, fallback: Optional[str] = None,
                    first_token_timeout: float = 2.5, api_key: Optional[str] = None,
                    reasoning: Optional[str] = "none",
                    brain: Optional[Brain] = None, fallback_brain: Optional[Brain] = None) -> Responder:
    """
    Local OpenAI-compatible server (the Ollama router) or any hosted OpenAI-compatible API. Streams whole
    SENTENCES so speech can start before generation finishes, and lets the model END THE CALL via a tool call
    (yielded as a [HangUp] after the sentences).

    Two hard-won rules from the server's own guide:
      * `reasoning_effort: "none"` is mandatory for the general models — with a small max_tokens they burn the
        entire budget reasoning internally and return an EMPTY string, with no error. `voice` disables reasoning
        server-side and needs nothing.
      * `think: false` is silently ignored on /v1.

    `voice` is always resident (~1 s to first token warm, 7 s cold — the pre-dial opening line warms it). The
    other models are much faster once loaded (nemotron-lightning: 0.1 s) but share GPUs and evict each other,
    costing a 15-70 s reload — mid-call that is dead air. So: if the chosen model produces no token within
    [first_token_timeout], the turn is re-asked of [fallback] instead of waiting for the reload. With no
    fallback (a hosted brain) the same model is re-asked once with a longer deadline.
    """
    del state  # the hang-up decision is yielded per generation now; kept for call-site compatibility
    primary = brain or Brain(base, model, reasoning, rules_for(model), api_key,
                             trust_tools=not is_small_model(model))
    if fallback_brain is None and fallback:
        fallback_brain = Brain(base, fallback, reasoning, rules_for(fallback), api_key,
                               trust_tools=not is_small_model(fallback))
    return _local_responder(system, primary, fallback_brain, first_token_timeout)


def anthropic_responder(system: str, model: str = "claude-opus-4-8") -> Responder:
    """Hosted model. Kept short because this is speech, not text."""
    key = os.environ.get("ANTHROPIC_API_KEY")
    if not key:
        raise SystemExit("ANTHROPIC_API_KEY is not set; use --responder local or scripted instead")

    def respond(text: str, history: list) -> Optional[str]:
        messages = [{"role": h["role"], "content": h["text"]} for h in history[-HISTORY_TURNS:]]
        messages.append({"role": "user", "content": text})
        body = {"model": model, "max_tokens": 80, "system": system, "messages": messages}
        req = urllib.request.Request(
            "https://api.anthropic.com/v1/messages", data=json.dumps(body).encode(), method="POST")
        req.add_header("content-type", "application/json")
        req.add_header("x-api-key", key)
        req.add_header("anthropic-version", "2023-06-01")
        with urllib.request.urlopen(req, timeout=60, context=ssl.create_default_context()) as r:
            out = json.loads(r.read().decode())
        return "".join(b.get("text", "") for b in out.get("content", []) if b.get("type") == "text").strip() or None

    return respond


# ---------------------------------------------------------------- speech synthesis


def speakable(text: str) -> str:
    """
    Normalise text that is about to be read aloud: typographic punctuation a TTS engine may voice literally,
    stray markdown, and anything outside latin-1 (emoji) which is dropped rather than voiced.
    """
    repl = {
        "—": ", ", "–": ", ",
        "…": ". ",
        "‘": "'", "’": "'",
        "“": '"', "”": '"',
        " ": " ", "​": "",
        "*": "", "#": "", "`": "",
    }
    for a, b in repl.items():
        text = text.replace(a, b)
    # "--" is an em dash the model typed as ASCII; left alone the engine runs the two clauses together.
    text = text.replace("--", ", ")
    # A semicolon or colon is how a second sentence gets smuggled into a one-sentence turn, and the prompt
    # cannot fully stop it. Spoken, both are just a pause, so make them one rather than leaving the engine
    # to guess — "everything's okay; how are you?" should breathe where the comma would.
    text = text.replace(";", ",").replace(":", ",")
    text = "".join(ch for ch in text if 32 <= ord(ch) < 0x250)
    text = " ".join(text.split()).strip()
    # Some models wrap the whole reply in quotation marks (ornith did, on every turn of live call 5). They are
    # not speech; strip a matched pair rather than leave the engine to decide what to do with them.
    if len(text) > 1 and text[0] == '"' and text.count('"') <= 2:
        text = text[1:].rstrip('"').strip()
    return text


# A natural pause inside a sentence: a comma, semicolon, colon, or a dash (typed or typographic).
# A comma or colon BETWEEN DIGITS is part of a number or a time ("1,250", "7:30"), never a pause (found in review).
_CLAUSE_BREAK = re.compile(r"(?:(?<!\d)[,;:]|[,;:](?!\d)|\s*[—–]\s*|\s+--?\s+)")
HEAD_MAX_WORDS = 7


def split_head(sentence: str) -> tuple:
    """
    Split a reply's first sentence at a natural pause near its start: (head, tail), or (sentence, "").

    The far party waits on the first clip, and Chatterbox renders a whole clip before any of it plays, so a
    long opening sentence was most of the dead air: 1.2 s for "Great, thanks, Alex." vs 3.0 s for the whole
    106-character sentence it began (measured 2026-09-23). The head is the LAST pause within the first
    HEAD_MAX_WORDS words (a comma after one word makes a head too choppy to sound like speech); the tail must
    be worth a clip of its own. No pause near the start: no split - a cut mid-phrase sounds worse than waiting.
    """
    words = sentence.split()
    if len(words) < 6:
        return sentence, ""
    best = None
    for m in _CLAUSE_BREAK.finditer(sentence):
        head_words = len(sentence[:m.start()].split())
        if head_words > HEAD_MAX_WORDS:
            break
        if head_words >= 2 and len(sentence[m.end():].split()) >= 3:
            best = m
    if best is None:
        return sentence, ""
    head = sentence[:best.start()].rstrip() + ","
    return head, sentence[best.end():].strip()


# Fillers for a slow think. NEUTRAL on purpose: "Sure." / "Yeah." before "Seven is booked." is an answer the
# agent did not mean. Short, because the clip's own length is most of the felt delay.
BACKCHANNELS = ["Mm-hmm.", "Let me see.", "One moment."]
# Speak a filler only when the first sentence has not arrived this long after the turn was accepted (final
# transcript in hand). Luna lands at ~0.7-1.0 s, so this covers the slow turns and stays quiet on the rest.
FILLER_AFTER_S = 1.0
# What the agent says when it could not get a reply at all (both attempts at the model failed).
FALLBACK_LINE = "Sorry, could you say that again?"
IDLE_NUDGE_LINE = "Are you still there?"
# Spoken by the driver itself when the model hangs up without a word (see _generate).
GOODBYE_LINE = "Thank you very much. Goodbye."
# A reply whose first sentence has not arrived this long after it was asked for is given up on: the far party
# hears FALLBACK_LINE instead of more dead air. The responder's own retry (first-token timeout, then the
# fallback brain with a 60 s socket deadline) could otherwise hold a turn for over a minute.
FIRST_SENTENCE_DEADLINE_S = 10.0
# ...and the whole reply, first sentence to last. Sentences already spoken stand; the rest is dropped.
TURN_DEADLINE_S = 25.0
# The idle watchdog treats "a reply is being written" as not-silence only this long after their turn closed.
# Past it, the nudge and hang-up logic act again (a slow model used to buy 1-2 minutes of dead air).
SLOW_REPLY_S = 12.0
# The player gives up on the call after this many play_audio/speak calls in a row fail at the transport.
PLAY_FAIL_LIMIT = 3
# telephony.end_call attempts per hang-up decision, with a growing pause between them.
HANGUP_ATTEMPTS = 3
# How long the event socket may stay down, reconnecting, before the driver gives up and hangs up.
RECONNECT_FOR_S = 30.0
# How often the event loop wakes without an event: to notice an abort, and so Ctrl-C/Ctrl-Break are
# handled promptly on Windows, where a blocking wait is not interrupted by a signal.
EVENT_POLL_S = 0.5


class Voice:
    """
    Turns text into audio for the call.

    `android` uses the phone's built-in engine via `telephony.speak` (fast, robotic). `kokoro` runs Kokoro-82M
    here and ships finished audio via `telephony.play_audio`; markedly more human at ~+0.35s per sentence on CPU.
    Kokoro also lets us pre-synthesize: the greeting before dialing, and a small cache of acknowledgements that
    play the instant the far party stops talking, while the model is still thinking.
    """

    def __init__(self, provider: str, voice: str = "af_sarah", speed: float = 1.0,
                 server: str = "http://127.0.0.1:8880", helper: Optional[str] = None):
        self.provider = provider
        self.voice = voice
        self.speed = speed
        self.server = server.rstrip("/")
        # A second Chatterbox on another GPU (a configured voice service, `clone` voice - Alex: "the same voice,
        # a little less exaggerated"). Renders everything after a reply's first clause, in parallel with the
        # main box, which then only ever holds the one clip the far party is waiting on.
        self.helper = helper.rstrip("/") if helper else None
        # Recent synth seconds per audio second, per box. The main box is shared (the video pipeline renders on
        # it too) and swung from 0.5 to 6+ within an hour on 2026-09-23 while the helper held ~0.6, so the
        # head of a reply goes to whichever box is fast NOW. The main box starts slightly favoured.
        self.rtf = {"main": 0.5, "helper": 0.6}
        self.rtf_at = {"main": 0.0, "helper": 0.0}
        # The main box was not ready at warm-up but the helper was: the whole call runs on the helper (the same
        # voice) rather than switching to Kokoro (a different one).
        self.main_down = False
        self._k = None
        self.backchannels: list = []   # [(text, b64, seconds)]
        self._cache: dict = {}         # text -> (b64, seconds): repeated lines ("Goodbye.") cost nothing twice

    def _note(self, lane: str, synth_s: float, audio_s: float) -> None:
        if audio_s > 0.2:
            self.rtf[lane] = 0.5 * self.rtf[lane] + 0.5 * (synth_s / audio_s)
            self.rtf_at[lane] = time.time()

    def _rtf_now(self, lane: str) -> float:
        # A box that was slow is not used for heads, so it would never be measured again: after 20 s without a
        # measurement, give it the benefit of the doubt for a non-critical clip (a "rest"), which re-measures it.
        r = self.rtf[lane]
        return min(r, 0.8) if time.time() - self.rtf_at[lane] > 20.0 else r

    def lane_for(self, role: str) -> str:
        """'head' -> the faster box; 'rest' -> the other one, unless it is too slow to keep ahead of playback."""
        if not self.helper:
            return "main"
        if self.main_down:
            return "helper"
        fast = min(self.rtf, key=lambda k: self.rtf[k])
        if role == "head":
            return fast
        other = "helper" if fast == "main" else "main"
        return other if self._rtf_now(other) < 0.9 else fast

    def can_presynth(self) -> bool:
        return self.provider in ("kokoro", "kokoro-server", "chatterbox")

    def cached(self, text: str):
        """(b64, seconds) if either box has already rendered this line, else None. Never touches a box."""
        key = speakable(text).lower()
        return self._cache.get(key) or self._cache.get("helper|" + key)

    def warm(self) -> None:
        if not self.can_presynth():
            return
        if self.provider == "chatterbox":
            # The router answers `ok` the moment it is up; `ready` only once its workers have loaded their
            # models (20-35 s after a restart). A request in between fails. An unknown voice name is NOT an
            # error there — it silently falls back to channel_a — so it is checked here instead.
            if self.voice not in CHATTERBOX_VOICES:
                print(f"[voice] chatterbox voice {self.voice!r} is not registered ({', '.join(CHATTERBOX_VOICES)}); "
                      f"the server would silently use channel_a — using joi", flush=True)
                self.voice = "joi"
            ready = False
            for _ in range(40):
                try:
                    h = json.loads(urllib.request.urlopen(self.server + "/health", timeout=3).read())
                    if h.get("ready"):
                        ready = True
                        break
                    print("[voice] chatterbox is up but its workers are still loading…", flush=True)
                except Exception as e:
                    print(f"[voice] chatterbox {self.server} unreachable ({e})", flush=True)
                    break
                time.sleep(1.0)
            if not ready and self.helper:
                try:
                    self._helper_pcm("Ready.")
                    self.main_down = True
                    print(f"[voice] chatterbox {self.server} not ready; every clip from the helper {self.helper}",
                          flush=True)
                except Exception as e:
                    print(f"[voice] helper {self.helper} unavailable too ({e})", flush=True)
                    self.helper = None
            if not ready and not self.main_down:
                print("[voice] chatterbox not ready; falling back to the Kokoro server", flush=True)
                self.provider, self.voice, self.server = "kokoro-server", "af_sarah", KOKORO_DEFAULT
        if self.provider == "kokoro-server":
            # The GPU container (ghcr.io/remsky/kokoro-fastapi-gpu) gives first audio in ~120ms versus 500-900ms
            # in-process on CPU. If it isn't up, degrade to in-process rather than to the robotic phone engine.
            try:
                urllib.request.urlopen(self.server + "/v1/models", timeout=3).read()
            except Exception as e:
                print(f"[voice] kokoro server {self.server} unreachable ({e}); using in-process kokoro", flush=True)
                self.provider = "kokoro"
        try:
            t0 = time.time()
            self.synth_wav_b64("ready")
            for bc in BACKCHANNELS:
                b64, secs, _ = self.synth_wav_b64(bc)
                self.backchannels.append((bc, b64, secs))
            for line in (FALLBACK_LINE, IDLE_NUDGE_LINE, GOODBYE_LINE):
                self.synth_wav_b64(line)  # cached: these must play instantly when they are needed
            print(f"[voice] {self.provider} warmed in {time.time()-t0:.1f}s, {len(self.backchannels)} fillers cached", flush=True)
        except Exception as e:
            print(f"[voice] warm-up failed ({e}); will fall back to the phone's engine if needed", flush=True)

    def warm_helper(self) -> None:
        """Probe the helper once; any failure drops it for the call rather than risking a slow clip later."""
        if not self.helper or self.provider != "chatterbox":
            self.helper = None
            return
        if self.main_down:
            return  # already probed by warm(), and the only box this call has
        try:
            t0 = time.time()
            self._helper_pcm("Ready.")
            print(f"[voice] helper {self.helper} warm ({time.time()-t0:.1f}s)", flush=True)
        except Exception as e:
            print(f"[voice] helper {self.helper} unavailable ({e}); every clip from {self.server}", flush=True)
            self.helper = None

    def _helper_pcm(self, text: str) -> bytes:
        assert self.helper
        body = {"input": speakable(text), "voice": "clone", "engine": "chatterbox", "response_format": "pcm"}
        req = urllib.request.Request(self.helper + "/v1/audio/speech", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req, timeout=15) as r:
            pcm = r.read()
        if len(pcm) < 2400:  # under 50 ms of audio: an error body or nothing, not speech
            raise ValueError(f"helper returned {len(pcm)} bytes")
        return pcm[:len(pcm) - len(pcm) % 2]

    def synth_helper_wav_b64(self, text: str, _no_main: bool = False):
        """Like synth_wav_b64, on the helper; falls back to the main server if the helper fails."""
        import base64 as _b64
        import struct as _struct
        # Its own cache entries: the helper's voice is close to Joi but not identical, and a shared key let
        # whichever box rendered a line first serve it for the rest of the call.
        key = "helper|" + speakable(text).lower()
        hit = self._cache.get(key)
        if hit is not None:
            return hit[0], hit[1], 0.0
        if not self.helper:
            if _no_main:
                raise RuntimeError("no helper")
            return self.synth_wav_b64(text)
        t0 = time.time()
        try:
            pcm, sr = to_uplink_rate(self._helper_pcm(text), 24000)
        except Exception as e:
            print(f"[voice] helper failed ({e})" + ("" if _no_main else f"; using {self.server}"), flush=True)
            self.rtf["helper"], self.rtf_at["helper"] = 5.0, time.time()
            if _no_main:
                raise
            return self.synth_wav_b64(text, _no_helper=True)
        wav = (b"RIFF" + _struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt "
               + _struct.pack("<IHHIIHH", 16, 1, 1, sr, sr * 2, 2, 16)
               + b"data" + _struct.pack("<I", len(pcm)) + pcm)
        b64, secs = _b64.b64encode(wav).decode(), len(pcm) / 2 / sr
        self._note("helper", time.time() - t0, secs)
        if len(self._cache) < 200:
            self._cache[key] = (b64, secs)
        return b64, secs, time.time() - t0

    def _kokoro(self):
        if self._k is None:
            from kokoro_onnx import Kokoro  # imported lazily so 'android' needs no ML deps
            d = os.path.expanduser("~/.kokoro")
            t0 = time.time()
            self._k = Kokoro(os.path.join(d, "kokoro-v1.0.onnx"), os.path.join(d, "voices-v1.0.bin"))
            print(f"[voice] kokoro loaded in {time.time()-t0:.1f}s (voice={self.voice})", flush=True)
        return self._k

    def synth_wav_b64(self, text: str, _no_helper: bool = False):
        """Return (base64 WAV, seconds of audio, synth seconds). A failing main box hands over to the helper."""
        t0 = time.time()
        key = speakable(text).lower()
        hit = self._cache.get(key)
        if hit is not None:
            return hit[0], hit[1], 0.0
        use_helper = self.helper and self.provider == "chatterbox" and not _no_helper
        if self.main_down and use_helper:
            return self.synth_helper_wav_b64(text, _no_main=True)
        try:
            b64, secs, synth = self._synth_uncached(text, t0)
        except Exception as e:
            if not use_helper:
                raise
            # The same voice from the other box beats the phone's robotic engine, which is what a failed
            # clip otherwise falls back to.
            print(f"[voice] {self.server} failed ({e}); using the helper", flush=True)
            self.rtf["main"], self.rtf_at["main"] = 5.0, time.time()
            return self.synth_helper_wav_b64(text, _no_main=True)
        self._note("main", synth, secs)
        if len(self._cache) < 200:
            self._cache[key] = (b64, secs)
        return b64, secs, synth

    def _synth_uncached(self, text: str, t0: float):
        import base64 as _b64
        import struct as _struct
        if self.provider == "chatterbox":
            # Custom endpoint: JSON in, a 24 kHz mono 16-bit WAV out. No delivery parameters on purpose —
            # each voice carries tuned defaults and sending any field overrides them (joi's Turbo engine
            # ignores exaggeration/cfg_weight entirely and would lose its temperature default). Empty text
            # is not an error server-side either: it returns seconds of invented speech.
            spoken = speakable(text)
            if not spoken.strip():
                raise ValueError("empty text")
            req = urllib.request.Request(self.server + "/synthesize",
                                         data=json.dumps({"text": spoken, "voice": self.voice}).encode(),
                                         headers={"Content-Type": "application/json"}, method="POST")
            # CLIP_DEADLINE_S, not 120 s: the player stops waiting on a clip after that anyway, and a hung box
            # held the single main-lane worker for two minutes behind it - every line queued there (the
            # fallback, the nudge, the goodbye) went unsaid for as long (review 2026-09-26).
            with urllib.request.urlopen(req, timeout=CLIP_DEADLINE_S) as r:
                wav = r.read()
            pcm, sr = wav_pcm(wav)
        elif self.provider == "kokoro-server":
            # OpenAI-compatible endpoint; raw 24kHz 16-bit PCM back, which we wrap as WAV for the phone.
            body = {"model": "kokoro", "input": speakable(text), "voice": self.voice, "speed": self.speed,
                    "response_format": "pcm", "stream": False}
            req = urllib.request.Request(self.server + "/v1/audio/speech", data=json.dumps(body).encode(),
                                         headers={"Content-Type": "application/json"}, method="POST")
            with urllib.request.urlopen(req, timeout=60) as r:
                pcm = bytearray(r.read())
            sr = 24000
            if len(pcm) % 2:
                pcm = pcm[:-1]
        else:
            samples, sr = self._kokoro().create(speakable(text), voice=self.voice, speed=self.speed, lang="en-us")
            pcm = bytearray()
            for v in samples:
                pcm += _struct.pack("<h", int(max(-1.0, min(1.0, float(v))) * 32767))
        pcm, sr = to_uplink_rate(bytes(pcm), sr)
        wav = (b"RIFF" + _struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt "
               + _struct.pack("<IHHIIHH", 16, 1, 1, sr, sr * 2, 2, 16)
               + b"data" + _struct.pack("<I", len(pcm)) + pcm)
        return _b64.b64encode(wav).decode(), len(pcm) / 2 / sr, time.time() - t0


UPLINK_RATE = 16000
KOKORO_DEFAULT = "http://127.0.0.1:8880"
CHATTERBOX_DEFAULT = os.environ.get("SYMBIOSIS_CHATTERBOX_URL", "http://127.0.0.1:8001")
CHATTERBOX_VOICES = ("joi", "channel_a", "channel_b")
VOICE_HELPER_DEFAULT = os.environ.get("SYMBIOSIS_VOICE_HELPER_URL", "http://127.0.0.1:8123")
# A reply's head that has not rendered by now is also sent to the other box. Normal heads take 1.0-1.9 s.
HEDGE_AFTER_S = 1.8
# A head, hedged or not, never holds the player longer than this; past it the phone's own engine says the line.
HEAD_DEADLINE_S = 8.0
# The same bound for any other clip the player waits on (per-request HTTP timeouts are 15-120 s).
CLIP_DEADLINE_S = 15.0
WHISPER_DEFAULT = os.environ.get("SYMBIOSIS_WHISPER_URL", "http://127.0.0.1:8002")
# Whisper overrides the phone's transcript only above this segment confidence (measured: its two wrong
# "corrections" on live call 4 were at -0.55 and -0.80; its confirmations sat at -0.24 to -0.67).
WHISPER_CORRECT_MIN_LOGPROB = -0.45


def wav_pcm(wav: bytes):
    """(16-bit mono PCM bytes, sample rate) from a WAV; a stereo file keeps its left channel."""
    import struct as _struct
    if len(wav) < 12 or wav[:4] != b"RIFF":
        raise ValueError("not a WAV")
    i, rate, channels, bits = 12, 16000, 1, 16
    while i + 8 <= len(wav):
        cid, size = wav[i:i+4], _struct.unpack("<I", wav[i+4:i+8])[0]
        if cid == b"fmt ":
            channels, rate, bits = _struct.unpack("<H", wav[i+10:i+12])[0], _struct.unpack("<I", wav[i+12:i+16])[0], _struct.unpack("<H", wav[i+22:i+24])[0]
        elif cid == b"data":
            data = wav[i+8:i+8+size]
            if bits != 16:
                raise ValueError(f"{bits}-bit WAV")
            if channels > 1:
                data = b"".join(data[k:k+2] for k in range(0, len(data) - 1, 2 * channels))
            return bytes(data[:len(data) - len(data) % 2]), rate
        i += 8 + size + (size & 1)
    raise ValueError("WAV without data")


def whisper_transcribe(server: str, wav: bytes, prompt: str = "", timeout: float = 20.0) -> Optional[dict]:
    """
    faster-whisper on the LAN (OpenAI-shaped /v1/audio/transcriptions). Returns {"text", "no_speech_prob",
    "avg_logprob"} or None when the service is unreachable (the driver then stays on the phone's transcript).
    `prompt` seeds the decoder with the vocabulary of THIS call (names, "seven twenty") — measured to fix the
    words that keep coming back wrong, at no cost in time.
    """
    boundary = "----symbiosis" + str(int(time.time() * 1000))
    parts = []

    def field(name: str, value: str) -> None:
        parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode())
    field("response_format", "verbose_json")
    field("language", "en")
    field("temperature", "0")
    if prompt:
        field("prompt", prompt)
    parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"turn.wav\"\r\n"
                 f"Content-Type: audio/wav\r\n\r\n".encode() + wav + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    req = urllib.request.Request(server.rstrip("/") + "/v1/audio/transcriptions", data=b"".join(parts),
                                 headers={"Content-Type": f"multipart/form-data; boundary={boundary}"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            o = json.loads(r.read().decode())
    except Exception as e:
        print(f"[whisper] unavailable: {type(e).__name__}: {e}", flush=True)
        return None
    segs = o.get("segments") or []
    return {
        "text": (o.get("text") or "").strip(),
        # No segments at all is what silence and noise come back as.
        "no_speech_prob": (max(float(s.get("no_speech_prob", 0.0)) for s in segs) if segs else 1.0),
        "avg_logprob": (min(float(s.get("avg_logprob", 0.0)) for s in segs) if segs else -9.0),
    }


def to_uplink_rate(pcm: bytes, sr: int):
    """
    Resample 16-bit mono PCM to the call's 16 kHz here, with a proper low-pass, instead of on the phone.

    The phone's resampler is linear interpolation with no anti-aliasing filter: Kokoro's 24 kHz output folded
    its 8-12 kHz band down into 4-8 kHz on the way to 16 kHz, which the far party hears as harshness. A
    windowed-sinc polyphase filter here costs a few milliseconds per sentence, and the WAV is a third smaller.
    Falls back to the original rate if numpy is unavailable (the phone still plays it, just less cleanly).
    """
    if sr == UPLINK_RATE or not pcm:
        return pcm, sr
    try:
        import numpy as np
    except ImportError:
        return pcm, sr
    from math import gcd
    g = gcd(sr, UPLINK_RATE)
    up, down = UPLINK_RATE // g, sr // g          # 24000 -> 16000: up 2, down 3
    x = np.frombuffer(pcm, dtype="<i2").astype(np.float64)
    if up > 1:
        y = np.zeros(len(x) * up)
        y[::up] = x * up
    else:
        y = x
    # Low-pass at the lower of the two Nyquist frequencies, on the upsampled signal.
    cutoff = min(sr, UPLINK_RATE) / 2.0 / (sr * up)    # as a fraction of the upsampled rate
    taps = 96
    n = np.arange(taps) - (taps - 1) / 2.0
    h = 2 * cutoff * np.sinc(2 * cutoff * n) * np.hamming(taps)
    h /= h.sum()
    y = np.convolve(y, h, mode="same")[::down]
    out = np.clip(np.rint(y), -32768, 32767).astype("<i2")
    return out.tobytes(), UPLINK_RATE


# ---------------------------------------------------------------- the player: synth ahead, play in order


# What a listener says while you talk that means "go on", not "stop". Judged only when it arrives while the
# agent is speaking (or has just been interrupted); the same words at any other time are a real turn.
_BACKCHANNEL_WORDS = {"uh huh", "uhhuh", "mhm", "mhmm", "mm hm", "mmhm", "mm hmm", "mmhmm", "mm", "hmm", "okay",
                      "ok", "yeah", "yes", "yep", "right", "sure", "i see", "got it", "go on", "go ahead",
                      "alright", "all right", "oh", "aha"}


# The subset of those that are also a complete ANSWER to a yes/no question. "Does seven work?" — "Yes." spoken
# over the question's tail is the answer, not a nod; dropping it left both sides waiting.
_ANSWER_WORDS = {"okay", "ok", "yeah", "yes", "yep", "sure", "right", "alright", "all right", "got it"}


def is_backchannel(text: str) -> bool:
    words = re.sub(r"[^a-z ]", "", (text or "").lower().replace("-", " ")).split()
    return 0 < len(words) <= 2 and " ".join(words) in _BACKCHANNEL_WORDS


def is_answer_word(text: str) -> bool:
    words = re.sub(r"[^a-z ]", "", (text or "").lower().replace("-", " ")).split()
    return " ".join(words) in _ANSWER_WORDS


def same_utterance(a: str, b: str) -> bool:
    """Interim vs final transcript: identical once case and punctuation are ignored."""
    norm = lambda s: re.sub(r"[^a-z0-9 ]", "", (s or "").lower()).split()
    return norm(a) == norm(b) and bool(norm(a))


# Audio the phone had queued but the far party had not yet heard when playback stopped: the AudioTrack buffer
# (150-500 ms) plus the telephony path. `played_ms` is wall-clock from play() to stop and includes all of it.
UNHEARD_TAIL_MS = 300
# Longest the player waits for an open far-party turn to close before it speaks anyway.
WAIT_FOR_TURN_S = 1.5
# After a turn closed with words in it, how long the player waits for the final transcript before speaking.
WAIT_FOR_FINAL_S = 0.7


def heard_portion(text: str, played_ms, total_ms):
    """Split a sentence into (heard, unheard) by how much of its audio actually played before an interruption."""
    words = text.split()
    if not words or not total_ms:
        return text, ""
    heard_ms = max(0.0, float(played_ms or 0) - UNHEARD_TAIL_MS)
    k = int(round(len(words) * max(0.0, min(1.0, heard_ms / float(total_ms)))))
    return " ".join(words[:k]), " ".join(words[k:])


class Player:
    """
    Single playback thread fed by an ordered queue.

    Synthesis happens *before* items reach this thread (futures submitted by the turn handler), so sentence N+1 is
    being rendered while sentence N plays. Before playing anything it re-checks that the item's turn is still the
    current one: a reply the far party has already talked past is dropped, not spoken — that overlap was how the
    agent used to babble two answers at once.

    Interruptions follow what LiveKit's agents do: the history records only the words that were actually HEARD
    (the model otherwise believes it said the whole sentence and won't repeat the part that mattered), the rest
    of the turn is parked rather than discarded, and if the interruption turns out to be a false one — a cough,
    an "uh-huh" — the parked speech resumes.
    """

    def __init__(self, rt: "Runtime", voice: Voice, state: dict, turn_seq: dict, activity: dict, history: list):
        self.rt, self.voice, self.state = rt, voice, state
        self.turn_seq, self.activity, self.history = turn_seq, activity, history
        self.q: "queue.Queue" = queue.Queue()
        self.dead_gens: set = set()    # abandoned generations (a preemptive reply whose transcript changed)
        # Items enqueued but not yet finished PLAYING. The queue being empty only means the last item was
        # dequeued — waiting on that hung up mid-"Goodbye". Wait on this instead.
        self.pending = 0
        self.speaking = False
        self.speaking_until = 0.0
        # Interruption bookkeeping: the turn that was cut off, what was left unsaid of the sentence that was
        # playing, and the sentences behind it that never started.
        self.interrupted_seq: Optional[int] = None
        self.remainder: Optional[str] = None
        self.parked: list = []
        self.synth: Callable = lambda text: None   # set by run(): text -> future of (b64, seconds)
        self.timing: dict = {}                      # per-turn timestamps, shared with run(); see _first_audio
        self.listening: dict = {}                   # set by run(): {"active": bool, ...} — is a far-party turn open?
        self.interim: dict = {}                     # set by run(): {"text": str} — latest interim transcript
        self.last_final: dict = {}                  # set by run(): {"text", "at"} — the last final transcript
        self._last_reply_key: Optional[tuple] = None  # (seq, gen) of the last clip written to history
        self.play_failures = 0                      # transport failures in a row; see PLAY_FAIL_LIMIT
        self._gave_up = False
        self.on_give_up: Callable = lambda: None    # set by run(): end the call, the phone no longer plays audio
        self._lock = threading.Lock()
        threading.Thread(target=self._loop, daemon=True).start()

    def enqueue(self, seq: Optional[int], text: str, fut, gen=None, activity: bool = True) -> None:
        """fut resolves to (b64, seconds) for kokoro, or None for the phone's own engine. seq=None never goes stale
        (the opening line). gen tags the generation the sentence came from so an abandoned one can be dropped.
        activity=False for a line that must not reset the silence clock (the idle nudge)."""
        with self._lock:
            self.pending += 1
        self.q.put((seq, text, fut, gen, activity))

    def wait_idle(self, timeout: float = 20.0) -> None:
        """Block until everything enqueued has actually been played (or dropped)."""
        t0 = time.time()
        while time.time() - t0 < timeout:
            with self._lock:
                if self.pending == 0:
                    return
            time.sleep(0.1)

    def _done(self) -> None:
        with self._lock:
            self.pending = max(0, self.pending - 1)

    # -- interruptions

    def interrupt(self, seq: int) -> None:
        """The phone stopped playback because the far party kept talking. Park the rest of this turn."""
        with self._lock:
            self.interrupted_seq = seq
            self.remainder = None
            self.parked = []

    def resume(self, why: str) -> None:
        """False interruption: say what was left, in order."""
        with self._lock:
            seq, rem, parked = self.interrupted_seq, self.remainder, self.parked
            self.interrupted_seq, self.remainder, self.parked = None, None, []
        if seq is None:
            return
        if not rem and not parked:
            return
        print(f"[resuming after {why}: {(1 if rem else 0) + len(parked)} item(s)]", flush=True)
        if rem:
            self.enqueue(seq, rem, self.synth(rem))
        for text, fut in parked:
            self._done()               # parked items are still counted as pending; re-enqueueing counts again
            self.enqueue(seq, text, fut)

    def discard_parked(self) -> None:
        """A real new turn: what was parked is no longer worth saying."""
        with self._lock:
            n = len(self.parked)
            self.interrupted_seq, self.remainder, self.parked = None, None, []
        for _ in range(n):
            self._done()

    # -- playback

    def _first_audio(self, seq: int) -> None:
        """One line per turn with the dead-air budget: how long after they stopped each stage landed."""
        t = self.timing.get(seq)
        if not t or "audio" in t or not t.get("closed"):
            return
        t["audio"] = time.time()
        c = t["closed"]
        def rel(k):
            return f"{t[k]-c:.2f}s" if t.get(k) else "?"
        print(f"[turn {t.get('n', '?')} dead air: turn closed -> final {rel('final')} -> first sentence {rel('think')}"
              f" -> first audio {rel('audio')}]", flush=True)

    def _stale(self, seq, gen) -> bool:
        return (seq is not None and seq != self.turn_seq["n"]) or (gen is not None and gen in self.dead_gens)

    def _loop(self) -> None:
        while True:
            seq, text, fut, gen, counts = self.q.get()
            parked = False
            try:
                # A future turn: the acknowledgement and a preemptive reply are queued before the transcript
                # is final. Wait for the turn to arrive (bounded) instead of losing the race and dropping them.
                if seq is not None and seq > self.turn_seq["n"]:
                    deadline = time.time() + 4.0
                    while seq > self.turn_seq["n"] and time.time() < deadline and gen not in self.dead_gens:
                        time.sleep(0.02)
                if self._stale(seq, gen):
                    print(f"[dropped: they spoke again] {text[:50]}", flush=True)
                    continue
                if self.interrupted_seq is not None and seq == self.interrupted_seq:
                    # Behind an interruption: hold it until we know whether that interruption was real. It stays
                    # counted as pending until resume() replays it or discard_parked() lets it go.
                    with self._lock:
                        self.parked.append((text, fut))
                    parked = True
                    continue
                t0 = time.time()
                try:
                    clip = fut.result(timeout=CLIP_DEADLINE_S) if fut is not None else None
                except Exception as e:
                    print(f"[voice] synth failed ({e}); falling back to the phone's engine", flush=True)
                    clip = None
                # Re-check after waiting on synthesis: the far party can speak inside that window.
                if self._stale(seq, gen):
                    print(f"[dropped after synth: they spoke again] {text[:50]}", flush=True)
                    continue
                # Don't start talking while they are talking. Between two sentences of one reply the phone is
                # briefly not "speaking", so a person who starts in that gap opens an ordinary turn — and the
                # phone's barge-in only arms for speech that STARTS during playback. Wait for their turn to
                # close (bounded), then re-check that this sentence is still worth saying.
                if self.listening.get("active"):
                    deadline = time.time() + WAIT_FOR_TURN_S
                    while self.listening.get("active") and time.time() < deadline:
                        time.sleep(0.02)
                    if self.listening.get("active"):
                        print(f"[speaking over an open turn after {WAIT_FOR_TURN_S}s] {text[:50]}", flush=True)
                # A turn that just closed with real words behind it: the final (and the new turn it starts)
                # is at most a few hundred ms away. Wait for it rather than answer the previous question.
                closed_at = self.listening.get("closed_at", 0.0)
                if (time.time() - closed_at < 1.0 and closed_at > self.last_final.get("at", 0.0)
                        and self.interim.get("text") and not is_backchannel(self.interim["text"])):
                    # (Only while that close's final is still outstanding: the reply to a turn is enqueued
                    # AFTER its final, and waiting 0.7 s then cost every first sentence on live call 3.)
                    deadline = time.time() + WAIT_FOR_FINAL_S
                    while not self._stale(seq, gen) and time.time() < deadline:
                        time.sleep(0.02)
                if self._stale(seq, gen):
                    print(f"[dropped: they spoke again] {text[:50]}", flush=True)
                    continue
                sid = self.state["session_id"]
                if seq is not None and not text.startswith("("):
                    self._first_audio(seq)
                self.speaking = True
                try:
                    if clip is not None:
                        b64, secs = clip
                        r = self.rt.call("telephony.play_audio", {"session_id": sid, "audio_base64": b64, "format": "wav"})
                        extra = f" [{secs:.1f}s audio]"
                    else:
                        r = self.rt.call("telephony.speak", {"session_id": sid, "text": speakable(text)})
                        extra = ""
                except Exception as e:
                    # A transport error (the phone dropped the connection, a read timeout) used to end this
                    # thread: `speaking` stayed True, `pending` only grew, the idle watchdog skipped forever, and
                    # the call sat live and silent with nobody hanging it up (review 2026-09-26). The clip's
                    # outcome is unknown, so it is not written to the history; the next one is tried as normal.
                    self.speaking = False
                    self.speaking_until = time.time() + 0.4
                    self.play_failures += 1
                    print(f"[agent] could not play ({type(e).__name__}: {e}); {self.play_failures} failure(s) in a row: "
                          f"{text[:50]}", flush=True)
                    if self.play_failures >= PLAY_FAIL_LIMIT and not self._gave_up:
                        self._gave_up = True
                        print(f"[agent] the phone has stopped taking audio ({self.play_failures} failures in a row)",
                              flush=True)
                        threading.Thread(target=self.on_give_up, daemon=True).start()
                    continue
                self.play_failures = 0
                self.speaking = False
                self.speaking_until = time.time() + 0.4
                if counts:
                    self.activity["at"] = time.time()
                if r.get("error"):
                    print(f"[agent] phone refused: {r['error']}", flush=True)
                    continue
                interrupted = bool(r.get("interrupted"))
                heard, unheard = (heard_portion(text, r.get("played_ms"), r.get("duration_ms"))
                                  if interrupted else (text, ""))
                print(f"[agent {time.time()-t0:.2f}s]{extra} {text}"
                      + (f"  (interrupted after {r.get('played_ms')}ms: heard {heard!r})" if interrupted else ""),
                      flush=True)
                if not text.startswith("(") and (heard or not unheard):
                    # Fillers are logged in parentheses and not kept as dialogue; a sentence cut before any
                    # of it was heard is not something the agent said.
                    said_text = (heard + "—") if unheard else text
                    prev = self.history[-1] if self.history else None
                    if (seq is not None and prev is not None and prev.get("role") == "assistant"
                            and self._last_reply_key == (seq, gen) and not prev["text"].endswith("—")):
                        # The next clip of the SAME reply (split_head plays one sentence as two clips): keep the
                        # reply as one entry, so the model reads whole sentences back and the goodbye check sees
                        # "Goodbye, Taylor, and thanks again", not just its tail.
                        prev["text"] = prev["text"] + " " + said_text
                    else:
                        self.history.append({"role": "assistant", "text": said_text})
                    self._last_reply_key = (seq, gen)
                if interrupted and unheard:
                    with self._lock:
                        if self.interrupted_seq == seq:
                            # Resume from the unheard part when there is a real sentence left, else say it again.
                            self.remainder = unheard if len(unheard.split()) >= 3 else text
            except Exception as e:
                # Nothing in one item may take the playback thread down with it: everything after it would
                # queue forever and the call would go silent.
                self.speaking = False
                print(f"[agent] playback error ({type(e).__name__}: {e}); carrying on", flush=True)
            finally:
                if not parked:
                    self._done()


# ---------------------------------------------------------------- keeping hold of the call


def end_call(rt: "Runtime", state: dict, attempts: int = HANGUP_ATTEMPTS) -> bool:
    """
    telephony.end_call, CHECKED, retried a bounded number of times. True only when the call really ended.

    Every hang-up used to be fire-and-forget: the result was never read, `state["ended"]` went True anyway, and a
    refused or failed end_call (the phone's transport blipped, Telecom said no) left the driver believing it had
    hung up on a call that was still live - so it stopped watching it (review 2026-09-26). An exception from it
    also took the idle watchdog's thread down. Now: ended means the phone said ended, or its call.ended event
    arrived meanwhile.
    """
    sid = state.get("session_id")
    if not sid:
        return False
    for i in range(attempts):
        if state.get("ended"):
            return True
        try:
            r = rt.call("telephony.end_call", {"session_id": sid}) or {}
        except Exception as e:
            r = {"error": f"{type(e).__name__}: {e}"}
        if not r.get("error"):
            state["ended"] = True
            state.setdefault("ended_at", time.time())
            return True
        print(f"[converse] end_call failed (attempt {i + 1}/{attempts}): {r.get('error')}", flush=True)
        if i + 1 < attempts:
            time.sleep(0.5 * (i + 1))
    return bool(state.get("ended"))


def paced(ask: Callable, first_s: float, total_s: float) -> Iterator:
    """
    A responder's output, bounded in time: TimeoutError when the first item has not arrived within first_s of
    asking, or the reply is still going at total_s.

    The responder cannot enforce this itself - its blocking happens inside next(), and a socket deadline only
    covers one read. Its own retry chain (a 2.5 s first-token timeout, then the fallback brain with a 60 s
    socket deadline) held a turn for over a minute of dead air. So the asking and the iterating happen on a
    helper thread and the turn waits on a queue; on a timeout the late reply is simply never read. The helper
    closes the stream as soon as it notices it has been abandoned (at its next item, or the socket timeout).
    """
    q: "queue.Queue" = queue.Queue()
    stop = threading.Event()
    end = object()

    def pump() -> None:
        src = None
        try:
            out = ask()
            if out is not None:
                src = [out] if isinstance(out, str) else out
                for item in src:
                    if stop.is_set():
                        break
                    q.put((True, item))
        except Exception as e:
            q.put((False, e))
            return
        finally:
            close = getattr(src, "close", None)
            if callable(close):
                try:
                    close()
                except Exception:
                    pass
        q.put((True, end))

    threading.Thread(target=pump, daemon=True).start()
    t0 = time.time()
    got_first = False
    try:
        while True:
            left = (total_s if got_first else first_s) - (time.time() - t0)
            try:
                ok, item = q.get(timeout=max(0.0, left))
            except queue.Empty:
                raise TimeoutError(f"no {'end of the' if got_first else 'first sentence of a'} reply in "
                                   f"{total_s if got_first else first_s:.0f}s") from None
            if not ok:
                raise item
            if item is end:
                return
            got_first = got_first or isinstance(item, str)
            yield item
    finally:
        stop.set()


class EventChannel:
    """
    The phone's /ws/events socket for one conversation, kept alive across blips.

    A Wi-Fi hiccup used to END THE CALL: the first recv() error returned from run(), whose finally hung up on a
    person mid-sentence (review 2026-09-26). The subscription is the phone's, not the socket's - persisted per
    peer and re-attached to any new socket (protocol.md 4.3) - so a blip loses nothing `replay` cannot bring
    back: reconnect with the same pin and token, ask for everything after the last event id seen, carry on.
    Only when the phone stays unreachable for RECONNECT_FOR_S does recv() give up (ConnectionError).

    Opened BEFORE dialing, so the call's own ACTIVE event can never land before the subscription exists
    (subscribing after place_call returned could miss it, and then the opening fallback and the idle watchdog
    never ran). Events are de-duplicated by id: replay and live push overlap by design.
    """

    def __init__(self, url: str, token: str, ssl_ctx, topic_glob: str, reconnect_for: Optional[float] = None,
                 max_delay: float = 4.0, label: str = "converse"):
        self.url, self.topic_glob, self.label = url, topic_glob, label
        # None: RECONNECT_FOR_S, read when it is needed. A daemon (tools/inbound.py) passes float("inf").
        self.reconnect_for, self.max_delay = reconnect_for, max_delay
        self.kw: dict = {"additional_headers": {"Authorization": "Bearer " + token}}
        if ssl_ctx is not None:
            self.kw["ssl"] = ssl_ctx
        self.ws = None
        self.sub_id: Optional[str] = None
        self.last_id = 0                     # highest event id delivered (or the floor set at open())
        self.baseline = False                # last_id is known to be recent: safe to replay from
        self._seen: "collections.deque" = collections.deque()
        self._seen_set: set = set()
        self.backlog: "collections.deque" = collections.deque()   # frames read while waiting for a reply
        self.reconnects = 0

    def open(self) -> None:
        if ws_connect is None:
            raise SystemExit("pip install websockets  (needed for the event channel)")
        self.ws = ws_connect(self.url, **self.kw)
        self._subscribe()
        # `subscribe` is idempotent on the phone: the same glob returns the peer's EXISTING subscription, which
        # can be days old. Replaying it from id 0 would bring back an earlier call's transcripts, so the replay
        # floor is the phone's newest event id at the moment we connected (its `hello`), never anything older.
        hello = next((f for f in self.backlog if f.get("type") == "hello"), None)
        if hello is not None and isinstance(hello.get("last_event_id"), int):
            self.last_id = max(self.last_id, hello["last_event_id"])
            self.baseline = True

    def close(self) -> None:
        try:
            if self.ws is not None:
                self.ws.close()
        except Exception:
            pass

    def _subscribe(self) -> None:
        self.ws.send(json.dumps({"type": "subscribe", "topic_glob": self.topic_glob}))
        r = self._await("subscribed", 5.0)
        if r is not None and r.get("type") == "subscribed":
            self.sub_id = r.get("subscription_id")
        else:
            print(f"[{self.label}] no subscription id from the phone ({r}); a reconnect will re-subscribe instead "
                  f"of replaying", flush=True)

    def _await(self, kind: str, timeout: float) -> Optional[dict]:
        """The next `kind` (or `error`) frame; everything read on the way is kept for recv(), in order."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                raw = self.ws.recv(timeout=max(0.05, deadline - time.time()))
            except TimeoutError:
                return None
            try:
                f = json.loads(raw)
            except Exception:
                continue
            if f.get("type") in (kind, "error"):
                return f
            self.backlog.append(f)
        return None

    def _replay(self) -> int:
        """Everything after last_id, put ahead of whatever live frames arrived meanwhile. Returns the count."""
        got: list = []
        since = self.last_id
        for _ in range(5):   # 1000 per page; a conversation never has more, but do not loop forever either
            self.ws.send(json.dumps({"type": "replay", "subscription_id": self.sub_id, "since_id": since,
                                     "limit": 1000}))
            r = self._await("replay_result", 5.0)
            if r is None:
                raise ConnectionError("no replay_result from the phone")
            if r.get("type") == "error":
                raise ConnectionError(f"replay refused: {r.get('code')}: {r.get('message')}")
            if r.get("truncated"):
                print(f"[{self.label}] replay truncated: events before {r.get('earliest_available_id')} were evicted",
                      flush=True)
            evs = r.get("events") or []
            got += [{"type": "event", "subscription_id": self.sub_id, "event": e} for e in evs]
            nxt = r.get("next_since_id")
            if len(evs) < 1000 or not isinstance(nxt, int) or nxt <= since:
                break
            since = nxt
        self.backlog.extendleft(reversed(got))
        return len(got)

    def _fresh(self, frame: dict) -> bool:
        eid = (frame.get("event") or {}).get("id")
        if not isinstance(eid, int):
            return True
        if eid in self._seen_set:
            return False
        self._seen.append(eid)
        self._seen_set.add(eid)
        if len(self._seen) > 4000:
            self._seen_set.discard(self._seen.popleft())
        self.last_id = max(self.last_id, eid)
        self.baseline = True
        return True

    def _reconnect(self, why: BaseException) -> None:
        print(f"[{self.label}] event socket dropped ({type(why).__name__}: {why}); reconnecting", flush=True)
        self.close()
        t0, delay, attempt = time.time(), 0.5, 0
        while True:
            attempt += 1
            try:
                self.ws = ws_connect(self.url, **self.kw)
                if self.sub_id and not self.baseline:
                    # No idea where "now" was when we started: a replay could only bring back other calls'
                    # events. Live push resumes on its own (the subscription is re-attached on connect).
                    print(f"[{self.label}] no event id to replay from; missed events are lost", flush=True)
                    n = 0
                elif self.sub_id:
                    try:
                        n = self._replay()
                    except ConnectionError as e:
                        if "replay refused" not in str(e):
                            raise
                        # The subscription is gone (expired, or the phone's store was reset): a new one sees
                        # only what happens from now on, which beats seeing nothing.
                        print(f"[{self.label}] {e}; subscribing afresh", flush=True)
                        self._subscribe()
                        n = 0
                else:
                    self._subscribe()
                    n = 0
                self.reconnects += 1
                print(f"[{self.label}] event socket back after {time.time() - t0:.1f}s ({attempt} attempt(s)); "
                      f"{n} missed event(s) replayed", flush=True)
                return
            except Exception as e:
                self.close()
                limit = RECONNECT_FOR_S if self.reconnect_for is None else self.reconnect_for
                if time.time() - t0 + delay > limit:
                    raise ConnectionError(f"event socket down for {time.time() - t0:.0f}s ({type(e).__name__}: {e})")
                if attempt == 1 or attempt % 10 == 0:
                    print(f"[{self.label}] event socket still down ({type(e).__name__}: {e}); retrying", flush=True)
                time.sleep(delay)
                delay = min(delay * 2, self.max_delay)

    def recv(self) -> Optional[dict]:
        """The next frame - an event (never one already delivered) or a control frame - or None after
        EVENT_POLL_S of nothing. Reconnects transparently; raises ConnectionError once it cannot."""
        while True:
            if self.backlog:
                f = self.backlog.popleft()
            else:
                try:
                    raw = self.ws.recv(timeout=EVENT_POLL_S)
                except TimeoutError:
                    return None
                except Exception as e:
                    self._reconnect(e)
                    continue
                try:
                    f = json.loads(raw)
                except Exception:
                    continue
            kind = f.get("type")
            if kind == "event":
                if self._fresh(f):
                    return f
                continue
            if kind == "gap" and self.sub_id:
                # The phone paused live push because we were slow; it resumes after a replay.
                print(f"[{self.label}] event gap {f.get('from_id')}..{f.get('to_id')}: replaying", flush=True)
                try:
                    self.ws.send(json.dumps({"type": "replay", "subscription_id": self.sub_id,
                                             "since_id": self.last_id, "limit": 1000}))
                except Exception as e:
                    # A closing socket raises websockets' ConnectionClosed, which is not a ConnectionError and
                    # used to escape recv(): it hung up a live call and ended the inbound daemon. Reconnecting
                    # replays from last_id, which covers the gap as well.
                    self._reconnect(e)
                continue
            if kind == "replay_result":
                evs = f.get("events") or []
                self.backlog.extendleft(reversed([{"type": "event", "subscription_id": f.get("subscription_id"),
                                                   "event": e} for e in evs]))
                continue
            if kind == "bye":
                self._reconnect(ConnectionError(f"phone said bye: {f.get('reason')}"))
                continue
            return f


# ---------------------------------------------------------------- the loop


def run(base: str, ws_url: str, token: str, respond: Responder,
        greeting: Optional[str], verbose: bool, state: Optional[dict] = None, *args, **kwargs) -> None:
    """
    Hold one call (see _run), and make sure that however this ends, a call this process placed or took over is
    not left up with nobody on it.

    The hang-up used to live in a finally around the event loop only - so every exit between dialing and that
    loop ("converse start refused", an OSError from the start call, the Whisper session failing to open) raised
    SystemExit with the call ringing or already answered and nobody driving it (review 2026-09-26). Now any exit
    after `state["owns_call"]` is set - an exception, SystemExit from a SIGTERM/SIGBREAK, Ctrl-C - comes through
    here. Raises SystemExit (non-zero) when the call had to be abandoned, so a supervisor can tell.
    """
    state = state if state is not None else {}
    try:
        _run(base, ws_url, token, respond, greeting, verbose, state, *args, **kwargs)
    finally:
        chan = state.get("_channel")
        if state.get("owns_call") and not state.get("ended"):
            print("[converse] leaving with the call still up: hanging up", flush=True)
            rt = state.get("_rt")
            if rt is None or not end_call(rt, state):
                print(f"[converse] could not hang up session {state.get('session_id')}: the call may still be "
                      f"up", flush=True)
                state.setdefault("abort", "could not hang up the call")
        if chan is not None:
            chan.close()
    if state.get("abort"):
        raise SystemExit(f"[converse] {state['abort']}")


def _run(base: str, ws_url: str, token: str, respond: Responder,
         greeting: Optional[str], verbose: bool, state: dict,
         voice: Optional[Voice] = None, idle_hangup: float = 25.0,
        dial: Optional[str] = None, session_id: Optional[str] = None,
        pin: Optional[str] = None, backchannel: bool = True, preempt: bool = True,
        converse_args: Optional[dict] = None, idle_nudge: float = 8.0,
        stt: str = "device", whisper_server: str = WHISPER_DEFAULT, stt_prompt: str = "",
        task: str = "", outcome_brain: Optional[Brain] = None,
        outcome_fallback: Optional[Brain] = None) -> None:
    if ws_connect is None:
        raise SystemExit("pip install websockets  (needed for the event channel)")

    rt = Runtime(base, token, pin)
    rt.init()
    state["_rt"] = rt   # for run()'s last-ditch hang-up
    voice = voice or Voice("android")
    voice.warm()
    # ONE worker, deliberately. A reply's sentences are submitted together, and the far party is waiting on
    # the FIRST one: running the second alongside it can only slow that down (shared TTS GPU) or leave it
    # alone — never speed it up. Measured on the Joi box, live call 5: a sentence alone takes 1.5 s, the same
    # sentence with a sibling in flight takes 3.0-3.4 s, which put first audio at 3.7 s and 5.4 s on the two
    # turns where the model answered in two sentences. Serialised, sentence 2 renders while sentence 1 plays,
    # which is all the pipeline ever needed (synth RTF is 0.2-0.5, so it always wins that race).
    synth_pool = cf.ThreadPoolExecutor(max_workers=1)
    # The helper box is a different GPU, so its lane runs alongside the main one without slowing it: the main
    # box renders the head of a reply (the only clip anyone is waiting on), the helper renders the rest.
    # Measured 2026-09-23: head alone 1.1 s to first audio, all three chunks of a long reply ready at 3.8 s.
    voice.warm_helper()
    helper_pool = cf.ThreadPoolExecutor(max_workers=1)

    # Synthesis queued per generation, so an abandoned one can be taken back out of the queue.
    synth_jobs: dict = {}

    hedge_pool = cf.ThreadPoolExecutor(max_workers=2)
    # Clips rendering right now, per box. A request already running cannot be taken back, so a reply that was
    # abandoned mid-render (a preempted guess the transcript then contradicted) still holds its box; the next
    # head goes to the idle box instead of queueing behind it (6.05 s -> 3 s of dead air in review's repro).
    busy = {"main": 0, "helper": 0}
    busy_lock = threading.Lock()   # a box can be counted from two pools at once (its lane and the backup)

    def on(lane: str, fn: Callable):
        def job():
            with busy_lock:
                busy[lane] += 1
            try:
                return fn()
            finally:
                with busy_lock:
                    busy[lane] -= 1
        return job
    backup_pool = cf.ThreadPoolExecutor(max_workers=1)

    def hedged_head(text: str, gen: Optional[int]):
        """
        The head on the box that is fast now, and - if it is not back in HEDGE_AFTER_S - on the other box too;
        whichever lands first plays. Either box can stall on its own (the helper took 4.3 s once in a run
        where it otherwise held 1.1-1.9 s), and the head is the one clip the far party is waiting on.

        Bounded by HEAD_DEADLINE_S overall: with both boxes hung, the per-request timeouts and their fallbacks
        stacked to ~135 s of a stuck player (found in review). Past the deadline it raises, and the player
        says the line with the phone's own engine instead.
        """
        lanes = {"main": (synth_pool, voice.synth_wav_b64), "helper": (helper_pool, voice.synth_helper_wav_b64)}
        first = voice.lane_for("head")
        other = "helper" if first == "main" else "main"
        if busy[first] and not busy[other] and not voice.main_down:
            first, other = other, first
        pool, fn = lanes[first]
        futs = [pool.submit(on(first, lambda: fn(text)[:2]))]
        if gen is not None:
            # Tracked with the generation, so kill_gen can take them back off the boxes' queues too.
            synth_jobs.setdefault(gen, []).extend(futs)
        deadline = time.time() + HEAD_DEADLINE_S
        done, _ = cf.wait(futs, timeout=HEDGE_AFTER_S)
        if (not done and voice.helper and not voice.main_down
                and (gen is None or gen not in player.dead_gens)):
            # Its own worker, not the other box's lane: that lane already holds this reply's rest, and the
            # backup queued behind it would land after the original anyway. Not when the main box is down:
            # both "boxes" are then the helper, and the backup would only double its load.
            _, fn2 = lanes[other]
            backup = backup_pool.submit(on(other, lambda: fn2(text)[:2]))
            futs.append(backup)
            if gen is not None:
                synth_jobs.setdefault(gen, []).append(backup)
            print(f"[voice] head slow on {first} after {HEDGE_AFTER_S}s; asking the other box too", flush=True)
        pending = set(futs)
        error: Optional[BaseException] = None
        while pending:
            left = deadline - time.time()
            if left <= 0:
                break
            done, pending = cf.wait(pending, timeout=left, return_when=cf.FIRST_COMPLETED)
            for f in done:
                if f.exception() is None:
                    for other in pending:
                        other.cancel()  # the loser, if it has not started yet
                    return f.result()
                error = f.exception()
        for f in pending:
            f.cancel()
        raise error if error is not None else TimeoutError(f"no head clip in {HEAD_DEADLINE_S}s")

    def synth_future(text: str, gen: Optional[int] = None, role: Optional[str] = None):
        """role None: the main box (opening line, fillers, cached lines). 'head'/'rest': routed by live speed."""
        if not voice.can_presynth():
            return None
        hit = voice.cached(text)
        if hit is not None:
            # Already rendered (the fallback, the nudge, the goodbye, a filler, any repeat): never queue it
            # behind a box. With the main box hung, the one worker in front of these lines held them for as
            # long as its request took - "Sorry, could you say that again?" arriving two minutes late.
            fut = cf.Future()
            fut.set_result(hit)
            return fut
        if role == "head" and getattr(voice, "helper", None):
            fut = hedge_pool.submit(hedged_head, text, gen)
        elif role and voice.lane_for(role) == "helper":
            fut = helper_pool.submit(on("helper", lambda: voice.synth_helper_wav_b64(text)[:2]))
        else:
            fut = synth_pool.submit(on("main", lambda: voice.synth_wav_b64(text)[:2]))
        if gen is not None:
            synth_jobs.setdefault(gen, []).append(fut)
        return fut

    def kill_gen(gen: int) -> None:
        """
        Abandon a generation AND cancel whatever it still has queued for synthesis.

        The player already drops a stale sentence before it waits on the clip, but the synthesis itself was
        still running: one worker, FIFO, so a dead reply's second and third sentences were synthesised ahead
        of the live reply's FIRST one - which is the only one the far party is actually waiting on. At Joi's
        measured RTF of ~0.48 that is seconds of dead air bought for audio nobody will ever hear. A queued
        future cancels cleanly; one already running is left alone.
        """
        player.dead_gens.add(gen)
        for f in synth_jobs.pop(gen, []):
            f.cancel()

    # Load the model(s) before dialing: a cold `voice` took 7 s to its first token, which used to land on the
    # far party's first question.
    warm_model = getattr(respond, "warm", None)
    if callable(warm_model):
        warm_model()

    # --- opening line: the caller speaks first. Written by the model, synthesized BEFORE we dial, so it plays
    # the instant the far party has answered instead of after a cold think + cold synth.
    opening_text = greeting
    if not opening_text:
        try:
            out = respond("(The call has just been answered. Say your opening line — one sentence.)", [])
            if isinstance(out, str):
                opening_text = out
            elif out is not None:
                # EVERY chunk, joined — not just the first. The responder streams long sentences a clause at a
                # time (see `take`), so taking one chunk truncated the greeting at its first comma: live call 5
                # opened with "Hi, I'm Alex's assistant--I'm calling to book a table for two tonight," and
                # stopped there, which is most of why that call felt abrupt.
                opening_text = " ".join(s for s in out if isinstance(s, str)).strip() or None
                close = getattr(out, "close", None)
                if callable(close):
                    close()
        except Exception as e:
            print(f"[opening] model could not write an opening line ({e})", flush=True)
    opening_clip = None
    if opening_text and voice.can_presynth():
        try:
            b64, secs, synth_s = voice.synth_wav_b64(opening_text)
            opening_clip = (b64, secs)
            print(f"[opening] ready ({synth_s:.2f}s synth, {secs:.1f}s audio): {opening_text}", flush=True)
        except Exception as e:
            print(f"[opening] pre-synth failed ({e})", flush=True)

    if not dial and not session_id:
        raise SystemExit("need --dial NUMBER or --session SESSION_ID")
    # Subscribe BEFORE dialing: the phone pushes an event only to subscriptions that already exist, and the
    # call's ACTIVE can come back within a second of place_call returning. A socket that cannot even be opened
    # is found out here, before anyone's phone rings.
    channel = EventChannel(ws_url, token, rt.ssl, "call.*")
    try:
        channel.open()
    except SystemExit:
        raise
    except Exception as e:
        raise SystemExit(f"could not open the phone's event channel {ws_url} ({type(e).__name__}: {e}); not dialing")
    state["_channel"] = channel

    if dial:
        # Placing a call is an external commitment: unless the number is on the phone's allowlist, the phone
        # shows an approval notification and holds this request open while the user decides. Give them 90 s.
        print(f"[call] placing {dial} (if the phone asks, tap Approve)", flush=True)
        placed = rt.call("telephony.place_call", {"target": dial, "purpose": "conversation driver", "driver_managed": True},
                         meta={"approvalWaitMs": 90000})
        session_id = placed.get("session_id")
        if not session_id:
            err = placed.get("error")
            if err == "policy.approval_required":
                raise SystemExit("place_call was not approved on the phone in time (or add the number to the allowlist: "
                                 "DEBUG_FLAG allow_target --es number ... --ez on true)")
            if err == "policy.denied":
                raise SystemExit(f"place_call denied by the phone's policy: {placed.get('detail') or placed}")
            raise SystemExit(f"place_call failed: {placed}")
        # From this line on this process owns a live call, and every way out of it - an error below, a signal,
        # Ctrl-C - goes through run()'s hang-up. (The gateway parses the next line to end orphaned calls: keep
        # its format exactly.)
        state["session_id"] = session_id
        state["owns_call"] = True
        print(f"[call] dialing {dial} -> {session_id}", flush=True)
    state["session_id"] = session_id

    started = rt.call("telephony.converse", {"action": "start", "session_id": session_id, **(converse_args or {})})
    if started.get("error"):
        raise SystemExit(f"converse start refused: {started['error']}")
    # Attached to a call placed elsewhere (--session): it is ours to hang up only once the phone has made us
    # its agent. A refused start leaves it alone - it may be another driver's live call.
    state["owns_call"] = True
    print(f"[converse] {'started' if started.get('started') else 'attached'}: {started}", flush=True)
    if not started.get("mic_muted"):
        print("[converse] WARNING: microphone is NOT muted for this call", flush=True)
    if not dial:
        # Attached to a call placed elsewhere: no precise-state event will announce the pickup, and without
        # `connected_at` the idle watchdog and the silent-pickup opening never run. The caller vouched for it.
        state["connected_at"] = time.time()

    history: list = []
    turn_seq = {"n": 0}          # the turn whose reply may be spoken right now
    next_seq = {"n": 0}          # allocator; a preemptive turn takes its number before its transcript is final
    turns = {"count": 0}
    last_final = {"text": "", "at": 0.0}
    activity = {"at": time.time()}
    greeted = {"done": opening_text is None}
    last_backchannel = {"i": -1}
    gen_counter = {"n": 0}
    player = Player(rt, voice, state, turn_seq, activity, history)
    player.synth = synth_future

    def player_gave_up() -> None:
        # The phone has refused our audio several times running: the far party is hearing nothing, and a call
        # we cannot speak on is only a stranger listening to silence. End it.
        state["closing"] = True
        print("[hanging up: the phone stopped taking our audio]", flush=True)
        if not end_call(rt, state):
            state["abort"] = "the phone stopped taking audio and the call could not be hung up"
    player.on_give_up = player_gave_up
    timing = player.timing
    stats = {"preempt_kept": 0, "preempt_lost": 0}
    inflight = {"n": 0}          # replies currently being generated; see idle_watchdog
    first_sentence_lock = threading.Lock()   # orders "first sentence enqueued" against "filler enqueued"

    def call_summary() -> None:
        """What happened, in numbers, plus the transcript to a file — bad calls get reviewed, not remembered."""
        turns_t = [t for k, t in timing.items() if isinstance(k, int) and t.get("audio") and t.get("closed")]
        gaps = [t["audio"] - t["closed"] for t in turns_t]
        kept, lost = stats["preempt_kept"], stats["preempt_lost"]
        line = (f"[summary] {turns['count']} turns; dead air after their turn closed: "
                + (f"mean {sum(gaps)/len(gaps):.2f}s, worst {max(gaps):.2f}s" if gaps else "n/a")
                + f"; preemptive replies kept {kept}/{kept+lost}")
        print(line, flush=True)
        try:
            d = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "logs")
            os.makedirs(d, exist_ok=True)
            path = os.path.join(d, time.strftime("call-%Y%m%d-%H%M%S.txt"))
            with open(path, "w", encoding="utf-8") as f:
                f.write(f"session {state.get('session_id')}  dial {dial}\n{line}\n\n")
                for h in history:
                    f.write(f"{'THEM ' if h['role'] == 'user' else 'AGENT'}: {h['text']}\n")
                f.write("\nper-turn (seconds after their turn closed):\n")
                for t in sorted(turns_t, key=lambda t: t.get("n", 0)):
                    c = t["closed"]
                    f.write(f"  turn {t.get('n')}: final {t['final']-c:.2f}  first sentence "
                            f"{(t['think']-c) if t.get('think') else float('nan'):.2f}  first audio {t['audio']-c:.2f}\n")
            print(f"[summary] transcript written to {path}", flush=True)
        except Exception as e:
            path = None
            print(f"[summary] could not write the call log: {e}", flush=True)
        # The point of the call. A calling agent reads THIS; the transcript is the evidence behind it.
        if outcome_brain is not None:
            o = summarize_outcome(outcome_brain, task, history, state.get("end_outcome", ""))
            if o is None and outcome_fallback is not None:
                print(f"[outcome] re-asking {outcome_fallback}", flush=True)
                o = summarize_outcome(outcome_fallback, task, history, state.get("end_outcome", ""))
            if o:
                print("[outcome] " + json.dumps(o, ensure_ascii=False), flush=True)
                print(f"[outcome] {o['outcome']}: {o.get('summary', '')}", flush=True)
                for owed in o.get("we_promised") or []:
                    # Said out loud, every time. Someone on the other end of a real phone call is waiting
                    # for this, and until call 6 it existed only as a field nobody read.
                    print(f"[owed] we told them we would: {owed}", flush=True)
                if path:
                    try:
                        with open(path, "a", encoding="utf-8") as f:
                            f.write("\noutcome:\n" + json.dumps(o, ensure_ascii=False, indent=2) + "\n")
                    except Exception:
                        pass
    # A reply started on the interim transcript, before the final arrived.
    speculative: dict = {}       # {"seq", "gen", "text", "at"} while one is in flight
    interruption: dict = {}      # {"at"} while a barge-in awaits its verdict (real turn / false alarm)
    listening = {"at": 0.0, "active": False, "closed_at": 0.0}
    player.listening = listening
    interim = {"text": ""}
    player.interim = interim
    player.last_final = last_final
    held: dict = {}              # a final that ended mid-thought, waiting briefly for its continuation

    def alloc_seq() -> int:
        next_seq["n"] += 1
        return next_seq["n"]

    def speak_opening(trigger: str) -> None:
        if greeted["done"] or not opening_text:
            return
        greeted["done"] = True
        print(f"[opening] speaking ({trigger})", flush=True)
        fut = cf.Future()
        fut.set_result(opening_clip) if opening_clip else fut.set_result(None)
        player.enqueue(None, opening_text, fut if opening_clip else synth_future(opening_text))

    def idle_watchdog() -> None:
        # Silence handling the way the production stacks do it: a gentle nudge first ("Are you still there?"),
        # then hang up. Fifteen seconds straight to a hang-up was abrupt on a person who had merely stepped
        # away to check the book.
        nudged = {"at": 0.0}
        failed = {"n": 0, "retry_at": 0.0}
        while idle_hangup > 0 and not state.get("ended"):
            time.sleep(0.5)
            try:
                if watchdog_tick(nudged, failed):
                    return
            except Exception as e:
                # One bad tick must not end silence handling for the rest of the call: that is how a failed
                # end_call left a silent call up forever (review 2026-09-26).
                print(f"[converse] idle watchdog error ({type(e).__name__}: {e}); carrying on", flush=True)

    def watchdog_hang_up(why: str, failed: dict) -> bool:
        """Hang up for the watchdog. True when the watchdog is done: the call ended, or we have stopped trying."""
        if time.time() < failed["retry_at"]:
            return False
        print(f"[hanging up: {why}]", flush=True)
        if end_call(rt, state):
            return True
        failed["n"] += 1
        if failed["n"] >= 3:
            # Each round was HANGUP_ATTEMPTS tries. Let the event loop end the process: run() tries once more,
            # and a non-zero exit tells the gateway the call may still be up.
            print("[converse] could not hang up the silent call; giving up", flush=True)
            state["abort"] = "could not hang up the call"
            return True
        failed["retry_at"] = time.time() + 5.0
        return False

    def watchdog_tick(nudged: dict, failed: dict) -> bool:
        if state.get("ended"):
            return True
        connected_at = state.get("connected_at")
        if not connected_at or player.speaking or player.pending > 0 or listening["active"]:
            return False  # not answered yet, or someone is mid-sentence: that is not silence
        slow_reply = False
        if inflight["n"] > 0:
            # A reply is still being WRITTEN. It has not reached the player, so `pending` is zero and
            # this looks exactly like silence - which is how live call 6 asked the far party "are you
            # still there?" one second after she had finished speaking. Waiting on a model is not
            # silence; the idle timers only mean anything once there is nothing coming.
            # But only for so long: a model that takes a minute is a minute of dead air, and that is exactly
            # what the silence handling exists for (review 2026-09-26).
            if time.time() - max(listening["closed_at"], last_final["at"]) < SLOW_REPLY_S:
                return False
            slow_reply = True
        quiet = time.time() - max(activity["at"], connected_at)
        # The opening counts as activity: on a voicemail nobody ever speaks, and the call must still end.
        if not (turns["count"] > 0 or greeted["done"]):
            return False
        if quiet > idle_hangup:
            return watchdog_hang_up(f"{quiet:.0f}s of silence", failed)
        if slow_reply and nudged["at"] < activity["at"] and voice.backchannels and not state.get("closing"):
            # They are waiting on US: "are you still there?" would be absurd. Say we are, once; it stands in for
            # the nudge, and the silence clock keeps running towards the hang-up if nothing ever comes.
            nudged["at"] = time.time()
            bc_text, bc_b64, bc_secs = next((b for b in voice.backchannels if b[0] == "One moment."),
                                            voice.backchannels[-1])
            fut = cf.Future()
            fut.set_result((bc_b64, bc_secs))
            print(f"[still writing a reply after {SLOW_REPLY_S:.0f}s: holding line]", flush=True)
            player.enqueue(None, f"({bc_text})", fut, activity=False)
            return False
        # We already said goodbye and they have gone quiet: that is the call ending, not a lost connection.
        # Asking "are you still there?" after our own farewell (live call 7) is exactly what a person would
        # never do - hang up instead, whether or not the hang-up decision fired on the words.
        # "Quiet" here must count from when THEY last made a sound, not from our last clip: someone who starts
        # "Oh wait, what's the address…" right after our goodbye and talks past the 3.5 s mark closes their
        # turn with `quiet` already over the limit, while the final is still ~0.2-0.4 s away - and the call
        # was hung up on them (found in review). A turn whose final has not landed, or a held fragment, is
        # them still talking.
        heard_quiet = time.time() - max(activity["at"], connected_at, listening["at"], listening["closed_at"])
        final_pending = listening["closed_at"] > last_final["at"] or bool(held)
        last_ours = next((h["text"] for h in reversed(history) if h.get("role") == "assistant"), "")
        if (last_ours and agent_said_farewell([last_ours]) and not final_pending
                and heard_quiet >= AFTER_GOODBYE_S):
            return watchdog_hang_up(f"we said goodbye and they went quiet for {quiet:.0f}s", failed)
        if 0 < idle_nudge <= quiet and nudged["at"] < activity["at"] and not state.get("closing"):
            nudged["at"] = time.time()
            print(f"[nudging after {quiet:.0f}s of silence]", flush=True)
            player.enqueue(None, IDLE_NUDGE_LINE, synth_future(IDLE_NUDGE_LINE), activity=False)
        return False

    threading.Thread(target=idle_watchdog, daemon=True).start()

    def false_interruption_watch() -> None:
        # The phone cut playback because someone kept talking for half a second — but if no transcript follows
        # once they go quiet, it was noise, a cough, a "hmm": carry on where we left off (LiveKit's
        # false-interruption resume).
        while not state.get("ended"):
            time.sleep(0.1)
            at = interruption.get("at")
            if not at:
                continue
            now = time.time()
            if listening["active"] and now - at < 8.0:
                continue
            closed = listening["closed_at"] or at
            if now - closed > 1.5 and now - at > 1.5:
                interruption.clear()
                player.resume("no transcript followed the interruption")

    threading.Thread(target=false_interruption_watch, daemon=True).start()

    def wait_for_answer_then_open() -> None:
        # A real caller waits for the other person to answer and speak, then talks. The opening therefore plays
        # on the far party's FIRST turn (instantly — it's pre-synthesized). Speaking first is only a fallback for
        # dead air (a voicemail, a silent pickup): a full 7s of complete silence after connect, and never once
        # they have started talking. A 3s fallback fired exactly as the far party was saying hello, and the two
        # talked over each other — the worst possible opening. If in doubt, wait.
        #
        # "Connected" means the precise call state went ACTIVE (a call.precise_state event) — NOT
        # telephony.get_call_state, which reports "offhook" from the moment we dial. Trusting that played the
        # greeting into a ringing line and then let the idle watchdog hang up three seconds after the pickup.
        for _ in range(1200):
            connected_at = state.get("connected_at")
            if connected_at:
                if greeted["done"]:
                    return
                if listening["at"] > connected_at:
                    return  # they have spoken (or are speaking); the transcript path handles the opening
                if time.time() - connected_at > 7.0:
                    speak_opening("complete silence for 7s after connect")
                    return
            elif state.get("ended"):
                return
            time.sleep(0.25)

    threading.Thread(target=wait_for_answer_then_open, daemon=True).start()

    def abandoned(seq: int, gen: int) -> bool:
        return gen in player.dead_gens or turn_seq["n"] > seq or bool(state.get("closing"))

    def filler_after(seq: int, gen: int) -> None:
        """Opt-in: a neutral filler if the first sentence is late, once per turn, never over a sentence."""
        time.sleep(FILLER_AFTER_S)
        with first_sentence_lock:
            if timing.get(seq, {}).get("think") or abandoned(seq, gen) or player.speaking or player.pending:
                return
            i = random.choice([k for k in range(len(voice.backchannels)) if k != last_backchannel["i"]])
            last_backchannel["i"] = i
            bc_text, bc_b64, bc_secs = voice.backchannels[i]
            fut = cf.Future()
            fut.set_result((bc_b64, bc_secs))
            print(f"[filler: no first sentence after {FILLER_AFTER_S}s]", flush=True)
            player.enqueue(seq, f"({bc_text})", fut, gen)

    def generate(user_text: str, seq: int, gen: int, hist: list, label: str) -> None:
        """Count this reply as in flight for as long as it is being written, then hand off to _generate."""
        inflight["n"] += 1
        try:
            _generate(user_text, seq, gen, hist, label)
        finally:
            inflight["n"] -= 1

    def _generate(user_text: str, seq: int, gen: int, hist: list, label: str) -> None:
        """Ask the responder and stream its sentences to the player, tagged with this turn and generation."""
        t0 = time.time()
        said: list = []
        hangup: Optional[HangUp] = None
        sentences = None
        if backchannel and voice.backchannels:
            threading.Thread(target=filler_after, args=(seq, gen), daemon=True).start()
        try:
            # Bounded: a first sentence within FIRST_SENTENCE_DEADLINE_S, the whole reply within TURN_DEADLINE_S.
            # Past either, the TimeoutError lands in the handler below like any other model failure, and the
            # far party hears the fallback line instead of a minute of nothing (review 2026-09-26).
            sentences = paced(lambda: respond(user_text, hist), FIRST_SENTENCE_DEADLINE_S, TURN_DEADLINE_S)
            first = True
            for sentence in sentences:
                if isinstance(sentence, HangUp):
                    hangup = sentence
                    continue
                if gen in player.dead_gens:
                    print(f"[{label or 'reply'} abandoned: transcript changed]", flush=True)
                    return
                if turn_seq["n"] > seq or state.get("closing"):
                    print("[reply abandoned: they spoke again]", flush=True)
                    return
                with first_sentence_lock:
                    if first:
                        print(f"[think {time.time()-t0:.2f}s to first sentence{' (' + label + ')' if label else ''}]", flush=True)
                        timing.setdefault(seq, {})["think"] = time.time()
                        first = False
                    # Synthesis starts now, in the pool; playback happens in order on the player thread.
                    # The reply's head (its first clause) goes to whichever box is fast right now; everything
                    # after it goes to the other box, which renders while the head plays.
                    if not said:
                        head, tail = split_head(sentence)
                        player.enqueue(seq, head, synth_future(head, gen, "head"), gen)
                        if tail:
                            player.enqueue(seq, tail, synth_future(tail, gen, "rest"), gen)
                    else:
                        player.enqueue(seq, sentence, synth_future(sentence, gen, "rest"), gen)
                said.append(sentence)
        except Exception as e:
            # The responder is a generator: a first-token timeout, a dropped connection, an HTTP error all
            # surface HERE, mid-iteration, not at the call. Losing the thread to a traceback left the far
            # party with silence. Say something that keeps the call alive instead.
            print(f"[responder error] {type(e).__name__}: {e}", flush=True)
            if not said and not abandoned(seq, gen):
                with first_sentence_lock:
                    timing.setdefault(seq, {})["think"] = time.time()
                    player.enqueue(seq, FALLBACK_LINE, synth_future(FALLBACK_LINE, gen), gen)
            return
        finally:
            close = getattr(sentences, "close", None)
            if callable(close):
                close()  # an abandoned generation releases its HTTP stream now, not at garbage collection

        farewell = agent_said_farewell(said)
        if hangup is None and not farewell:
            return
        if abandoned(seq, gen):
            return
        reason = hangup.reason if hangup is not None else "the agent said goodbye"
        if turns["count"] < 2:
            print("[hangup ignored: too early in the call]", flush=True)
        elif not far_party_affirmative(user_text):
            print(f"[hangup ignored: their last turn was not agreement: {user_text[:50]!r}]", flush=True)
        else:
            # One decision, one closing thread. From here on no new turn starts a reply: their "bye" after our
            # goodbye is answered by the hang-up, not by another "Goodbye." (three times, on call 2).
            state["closing"] = True
            if not said:
                player.enqueue(seq, GOODBYE_LINE, synth_future(GOODBYE_LINE, gen), gen)

            def end_after_queue() -> None:
                # Let everything actually finish PLAYING (not just leave the queue), then end.
                player.wait_idle()
                # Then hold the line open a moment longer. A goodbye is exactly when the other person
                # remembers the thing they still needed to ask, and their turn cannot even be ENDPOINTED
                # until ~0.5-0.9s after they stop talking - so hanging up the instant our audio stops is
                # how you cut someone off mid-question. On live call 6 that is what happened to Taylor.
                # `abort_close` is set by the transcript handler when a question lands inside this window.
                deadline = time.time() + CLOSING_GRACE
                while time.time() < deadline:
                    if state.get("abort_close"):
                        print("[closing cancelled: they asked something]", flush=True)
                        return
                    # An open turn means they ARE talking: wait for it rather than timing them out.
                    if listening.get("active") and time.time() > deadline - 0.5:
                        deadline = time.time() + 0.5
                    time.sleep(0.05)
                if state.get("abort_close"):
                    print("[closing cancelled: they asked something]", flush=True)
                    return
                print(f"[hanging up: {reason}]", flush=True)
                if not end_call(rt, state):
                    # Not "ended": the call is still up. The event loop ends the process and run() tries again.
                    print("[converse] the hang-up did not go through", flush=True)
                    state["abort"] = "could not hang up the call"

            def end_after_queue_safe() -> None:
                try:
                    end_after_queue()
                except Exception as e:
                    print(f"[converse] closing error ({type(e).__name__}: {e})", flush=True)
                    state["abort"] = f"closing failed: {e}"
            threading.Thread(target=end_after_queue_safe, daemon=True).start()

    def start_turn(user_text: str, seq: int, hist: list, label: str = "") -> int:
        gen_counter["n"] += 1
        gen = gen_counter["n"]
        threading.Thread(target=generate, args=(user_text, seq, gen, list(hist), label), daemon=True).start()
        return gen

    # Re-entrant: the Whisper thread calls on_final(), which calls handle_final(), under the same lock.
    _final_lock = threading.RLock()
    # Per far-party turn (keyed by the phone's turn_id): what the phone heard, which reply answers it, and
    # whether Whisper has had its say. This is what lets a Whisper transcript that lands after the reply
    # started confirm it, correct it, or withdraw it.
    turn_state: dict = {}

    def handle_final(text: str, raw_text: str, payload: dict, lag: str) -> None:
        """One finished far-party turn: log it, keep or abandon the preemptive reply, start the real one."""
        with _final_lock:
            _handle_final(text, raw_text, payload, lag)

    def _handle_final(text: str, raw_text: str, payload: dict, lag: str) -> None:
        print(f"[them +{lag}] {text}" + (f"   (heard: {raw_text})" if text != raw_text else ""), flush=True)

        now = time.time()
        if text == last_final["text"] and now - last_final["at"] < 6.0:
            print("[duplicate transcript ignored]", flush=True)
            return
        last_final["text"], last_final["at"] = text, now
        ts = turn_state.setdefault(payload.get("turn_id"), {}) if payload.get("turn_id") is not None else {}
        ts.update({"handled": True, "text": text, "hist_index": len(history), "seq": None, "gen": None})
        history.append({"role": "user", "text": text})

        # Their first turn is "Hello, Giovanni's": answer it with the pre-synthesized opening, not a
        # fresh generation, and do not also generate a reply to it — that was the double intro.
        if not greeted["done"]:
            speak_opening("far party answered")  # the player records it in the history once played
            return
        if state.get("closing"):
            # Normally right: their "bye" after our goodbye is answered by the hang-up, not by a second
            # goodbye (call 2 said it three times). But a QUESTION is not a farewell, and swallowing it is
            # how Taylor got hung up on while asking where dinner was. Take the close back and answer her.
            if is_question(text) and not state.get("ended"):
                state["abort_close"] = True
                state["closing"] = False
                print(f"[closing cancelled: they asked {text[:40]!r}]", flush=True)
            else:
                print("[closing: not answering]", flush=True)
                return

        turns["count"] += 1
        spec = dict(speculative)
        speculative.clear()

        def mark_turn(seq: int) -> None:
            t = timing.setdefault(seq, {})
            t.update({"n": turns["count"], "closed": timing.get("last_closed") or now, "final": now,
                      "speech_ms": payload.get("speech_ms")})

        if spec and now - spec["at"] < 6.0:
            seq = spec["seq"]
            mark_turn(seq)
            turn_seq["n"] = seq   # releases the acknowledgement queued for this turn
            if spec.get("gen") is not None and spec.get("text") and same_utterance(spec["text"], text):
                print(f"[preempted reply kept: interim matched final]", flush=True)
                stats["preempt_kept"] += 1
                ts.update({"seq": seq, "gen": spec["gen"]})
                return
            if spec.get("gen") is not None:
                kill_gen(spec["gen"])
                stats["preempt_lost"] += 1
                print(f"[preempted reply abandoned: interim {spec['text']!r}]", flush=True)
            ts.update({"seq": seq, "gen": start_turn(text, seq, history[:-1])})
        else:
            seq = alloc_seq()
            mark_turn(seq)
            turn_seq["n"] = seq
            ts.update({"seq": seq, "gen": start_turn(text, seq, history[:-1])})

    def apply_whisper(turn_id: int, res: dict) -> None:
        """
        Whisper's transcript for a turn the phone has (usually) already answered from its own recognizer.
        Junk (silence, noise) withdraws the reply; a matching transcript confirms it; a different one
        corrects the history and regenerates. Arriving BEFORE the phone's final, it simply becomes the final.
        """
        with _final_lock:
            ts = turn_state.setdefault(turn_id, {})
            ts["whisper_done"] = True
            raw = res.get("text") or ""
            junk = not raw.strip() or res.get("no_speech_prob", 0.0) > 0.5
            if junk:
                ts["junk"] = True
                if held.get("turn_id") == turn_id:
                    held.clear()
                    print(f"[whisper: turn {turn_id} was not speech; held fragment dropped]", flush=True)
                if ts.get("handled") and ts.get("gen") is not None:
                    kill_gen(ts["gen"])
                    idx = ts.get("hist_index")
                    if idx is not None and idx < len(history) and history[idx].get("text") == ts.get("text"):
                        del history[idx]
                    print(f"[whisper: turn {turn_id} was not speech; reply withdrawn]", flush=True)
                return
            text = normalize_transcript(raw)
            if ts.get("ignored"):
                return  # the phone heard a backchannel and we let it pass; Whisper agrees it was short
            if held.get("turn_id") == turn_id:
                held.update({"raw": raw, "text": text})
                print(f"[whisper: held fragment is {text!r}]", flush=True)
                return
            if not ts.get("handled"):
                on_final(raw, {"turn_id": turn_id, "final": True}, "whisper")
                return
            prefix = ts.get("prefix", "")
            full_raw = (prefix + " " + raw).strip()
            full = normalize_transcript(full_raw)
            if same_utterance(ts.get("text", ""), full):
                print(f"[whisper confirmed turn {turn_id}]", flush=True)
                return
            # A disagreement is only a correction when Whisper is sure. On live call 4 it turned "booked" into
            # "blocked" at logprob -0.55 and a merged fragment's "Table" into "on the table." at -0.80 — the
            # phone had both right. Large-v3 on carrier audio is better on lost words and noise, not always on
            # a short clear turn. A merged turn is never corrected: Whisper only heard its continuation.
            if prefix or res.get("avg_logprob", -9.0) < WHISPER_CORRECT_MIN_LOGPROB:
                print(f"[whisper disagrees with turn {turn_id} ({full!r}, logprob {res.get('avg_logprob', -9.0):.2f}) "
                      f"but not surely enough to correct it]", flush=True)
                return
            print(f"[whisper corrected turn {turn_id}: {ts.get('text')!r} -> {full!r}]", flush=True)
            idx = ts.get("hist_index")
            if idx is not None and idx < len(history) and history[idx].get("text") == ts.get("text"):
                history[idx]["text"] = full
            ts["text"] = full
            last_final["text"] = full
            if ts.get("seq") is None:
                return  # the opening or a closing turn: the words are fixed, nothing to regenerate
            if ts.get("gen") is not None:
                kill_gen(ts["gen"])
            if state.get("closing"):
                return
            ts["gen"] = start_turn(full, ts["seq"], history[:idx] if idx is not None else history)

    rt_fetch: Optional[Runtime] = None
    if stt == "whisper":
        # Its own MCP session and connection: the main one is held for the whole of every play_audio, and
        # this fetch has to happen while the agent may be talking.
        # A second session that will not open costs Whisper's corrections, not the call: the phone's own
        # transcript still drives every reply. (It used to raise SystemExit with the call already dialed.)
        try:
            rt_fetch = Runtime(base, token, pin)
            rt_fetch.init()
        except Exception as e:
            print(f"[whisper] could not open a second session to the phone ({type(e).__name__}: {e}); "
                  f"using the phone's transcripts only", flush=True)
            rt_fetch, stt = None, "device"

    def whisper_turn(turn_id: int) -> None:
        """Fetch the turn's audio from the phone and transcribe it on the LAN; runs off the event loop."""
        import base64 as _b64
        t0 = time.time()
        assert rt_fetch is not None
        r = rt_fetch.call("telephony.turn_audio", {"session_id": state["session_id"], "turn_id": turn_id})
        if r.get("error") or not r.get("audio_base64"):
            print(f"[whisper: no audio for turn {turn_id}: {r.get('error') or r}]", flush=True)
            return
        t1 = time.time()
        res = whisper_transcribe(whisper_server, _b64.b64decode(r["audio_base64"]), stt_prompt)
        if res is None:
            return
        print(f"[whisper turn {turn_id}: {res['text']!r} (fetch {t1-t0:.2f}s + stt {time.time()-t1:.2f}s, "
              f"no_speech {res['no_speech_prob']:.2f}, logprob {res['avg_logprob']:.2f})]", flush=True)
        apply_whisper(turn_id, res)

    heard = {"at": 0.0}

    def on_final(raw_text: str, payload: dict, source: str) -> None:
        """A finished far-party transcript, from the phone's recognizer or from Whisper: classify it (a nod,
        an answer, a fragment, a real turn) and hand a real turn to handle_final()."""
        with _final_lock:
            _on_final(raw_text, payload, source)

    def _on_final(raw_text: str, payload: dict, source: str) -> None:
        text = normalize_transcript(raw_text)
        activity["at"] = time.time()
        lag = f"{time.time()-heard['at']:.2f}s" if heard["at"] else "?"
        tid = payload.get("turn_id")
        ts = turn_state.setdefault(tid, {}) if tid is not None else {}
        if source == "device" and ts.get("whisper_done"):
            print(f"[phone's transcript for turn {tid} ignored: Whisper already decided it: {text!r}]", flush=True)
            return

        # Said while we were talking (or right after an interruption): a listener's "uh-huh" is not
        # an interruption. It is still not a turn UNLESS it answers a question we had just finished
        # asking: "Yes" over the tail of "Does seven work?", with nothing more queued behind it, is
        # the answer — dropping it left both sides waiting for the other.
        if is_backchannel(raw_text) and (player.speaking or time.time() < player.speaking_until or interruption):
            if interruption:
                print(f"[them, listening] {text}", flush=True)
                history.append({"role": "user", "text": text})
                ts["ignored"] = True
                interruption.clear()
                player.resume(f"they only said {text!r}")
                return
            if not (is_answer_word(raw_text) and player.pending <= 1 and not state.get("closing")):
                print(f"[them, listening] {text}", flush=True)
                history.append({"role": "user", "text": text})  # the model still gets to see it
                ts["ignored"] = True
                return
            print(f"[them, answering over the tail] {text}", flush=True)
        if interruption:
            # A real turn followed the interruption: whatever was parked is no longer worth saying.
            interruption.clear()
            player.discard_parked()

        now = time.time()
        if held.get("raw"):
            # The continuation of a turn we held back: one thought, one reply.
            ts["prefix"] = held["raw"]
            raw_text = held.pop("raw") + " " + raw_text
            text = normalize_transcript(raw_text)
            held.clear()
            print(f"[merged with the held fragment -> {text!r}]", flush=True)
        elif looks_unfinished(raw_text):
            # "What about" ended a turn on a live call because the acoustic model was sure; the next
            # turn was the rest of the sentence. Hold a final that ends mid-thought for a moment and
            # answer the whole thing.
            held.clear()
            held.update({"raw": raw_text, "text": text, "payload": payload, "lag": lag, "at": now, "turn_id": tid})
            ts["held"] = True

            def release(at: float) -> None:
                # Wait out the hold — and as long as they are still talking (a turn is open, or
                # its final is still on the way), keep waiting: the continuation is coming.
                while True:
                    time.sleep(0.1)
                    if held.get("at") != at:
                        return
                    elapsed = time.time() - at
                    still_talking = listening["active"] or time.time() - listening["closed_at"] < 0.6
                    if (elapsed >= HOLD_FRAGMENT_S and not still_talking) or elapsed > 8.0:
                        break
                with _final_lock:
                    if held.get("at") == at:
                        h = dict(held)
                        held.clear()
                        print(f"[held fragment released after {HOLD_FRAGMENT_S}s]", flush=True)
                        handle_final(normalize_transcript(h["raw"]), h["raw"], h["payload"], h["lag"])

            threading.Thread(target=release, args=(now,), daemon=True).start()
            print(f"[holding a fragment: {text!r}]", flush=True)
            return
        handle_final(text, raw_text, payload, lag)

    print(f"[converse] listening. responder={respond.__module__}", flush=True)
    while True:
        if state.get("abort"):
            # Something off this thread (the player, a hang-up that would not go through) has decided the
            # call cannot continue. run() hangs up on the way out and exits non-zero.
            print(f"[converse] stopping: {state['abort']}", flush=True)
            return
        if state.get("ended") and time.time() - state.get("ended_at", time.time()) > 10.0:
            # We hung up and the phone said so, but its call.ended never reached us: do not wait forever.
            print("[converse] hung up; no call.ended event followed", flush=True)
            call_summary()
            return
        try:
            frame = channel.recv()
        except ConnectionError as e:
            # Reconnecting failed for RECONNECT_FOR_S: without events there is no conversation to hold, and a
            # call nobody can hear is worse than none. run() hangs up.
            print(f"[converse] socket closed: {e}", flush=True)
            return
        if frame is None:
            continue
        try:
            if frame.get("type") != "event":
                if verbose:
                    print(f"[ws] {frame}", flush=True)
                continue

            ev = frame.get("event", {})
            topic, payload = ev.get("topic"), ev.get("payload", {})
            if verbose:
                print(f"[ev] {topic} {payload}", flush=True)

            ev_sid = payload.get("session_id")
            if ev_sid and ev_sid != state["session_id"]:
                continue
            if topic == "call.ended":
                # The outcome in its own key: "ended" is a flag, and the summary used to read "The call ended: True".
                state["end_outcome"] = str(payload.get("outcome") or "")
                print(f"[call ended: {payload.get('outcome')} after {payload.get('duration_ms')}ms]", flush=True)
                state["ended"] = True
                state.setdefault("ended_at", time.time())
                call_summary()
                return
            if topic == "call.precise_state":
                st = str(payload.get("state", "")).upper()
                if st == "ACTIVE" and not state.get("connected_at"):
                    state["connected_at"] = time.time()
                    activity["at"] = time.time()
                    print("[call] answered", flush=True)
                elif st in ("DIALING", "RINGING", "ALERTING", "CONNECTING"):
                    print(f"[call] {st.lower()}", flush=True)
                continue
            if topic == "call.conversation":
                # The phone's side of conversation mode stopped. "error": its listening loop died, so no
                # transcript will ever arrive again - an agent that can no longer hear is only dead air for the
                # far party, so end the call (after whatever is playing). "driver_lost": the phone judged this
                # driver gone and ended the call itself; call.ended follows, and that is what ends this loop.
                if payload.get("state") == "stopped":
                    why = payload.get("reason")
                    print(f"[converse] the phone stopped the conversation: {why}"
                          + (f" ({payload.get('error')})" if payload.get("error") else ""), flush=True)
                    if why == "error" and not state.get("closing") and not state.get("ended"):
                        state["closing"] = True

                        def stop_after_error() -> None:
                            try:
                                player.wait_idle(timeout=8.0)
                                print("[hanging up: the phone stopped listening]", flush=True)
                                if not end_call(rt, state):
                                    state["abort"] = "the phone stopped listening and the call could not be hung up"
                            except Exception as e:
                                state["abort"] = f"could not hang up after the phone stopped listening: {e}"
                        threading.Thread(target=stop_after_error, daemon=True).start()
                continue

            if topic == "call.listening":
                listening["active"] = bool(payload.get("listening"))
                listening["at"] = time.time()
                if payload.get("listening"):
                    heard["at"] = time.time()
                    interim["text"] = ""
                    continue
                listening["closed_at"] = time.time()
                timing["last_closed"] = time.time()
                how = payload.get("endpoint")
                if how:
                    p = payload.get("p_complete")
                    print(f"  [turn closed by {how} after {payload.get('silence_ms')}ms"
                          + (f", p={p:.2f}" if isinstance(p, (int, float)) else "") + "]", flush=True)
                if stt == "whisper" and payload.get("turn_id") is not None and (payload.get("audio_ms") or 0) >= 150:
                    threading.Thread(target=whisper_turn, args=(payload["turn_id"],), daemon=True).start()
                if not greeted["done"] or interruption or state.get("closing"):
                    continue  # the opening handles their first turn; an interruption awaits its verdict
                if preempt and interim["text"] and not player.speaking and not looks_unfinished(interim["text"]):
                    # Start answering the interim transcript now. The final lands ~0.15s later; if it reads
                    # the same, this reply is already that far ahead. If not, it is abandoned and we start
                    # over. A previous speculation still in flight (a fragment being held, a second close)
                    # is abandoned too — otherwise it keeps generating and synthesising sentences nobody
                    # will hear.
                    if speculative.get("gen") is not None:
                        kill_gen(speculative["gen"])
                    seq = alloc_seq()
                    text = normalize_transcript(interim["text"])
                    gen = start_turn(text, seq, history, "preempted")
                    speculative.clear()
                    speculative.update({"seq": seq, "gen": gen, "text": text, "at": time.time()})
                continue

            if topic == "call.barge_in":
                print(f"[far party interrupted{' after ' + str(payload.get('after_ms')) + 'ms' if payload.get('after_ms') else ''}"
                      f"{', rms ' + str(payload.get('rms')) if payload.get('rms') is not None else ''}]", flush=True)
                player.interrupt(turn_seq["n"])
                interruption.clear()
                interruption["at"] = time.time()
                continue

            if topic != "call.transcript":
                continue
            raw_text = (payload.get("text") or "").strip()
            if not payload.get("final"):
                if raw_text:
                    interim["text"] = raw_text
                    if verbose:
                        print(f"  … {raw_text}", flush=True)
                continue
            if not raw_text:
                continue
            on_final(raw_text, payload, "device")
        except Exception as e:
            # One bad event must not kill the conversation; log it and keep listening.
            import traceback
            print(f"[converse] handler error on {frame.get('event', {}).get('topic')}: {e}", flush=True)
            traceback.print_exc()




# Brains that passed tools/judgement_eval.py 5/5 (2026-09-07), as (base, model, reasoning). Keys come from
# secrets/api_keys. `voice` is the LAN router; the rest are hosted and cost a fraction of a cent per call.
# Presets, as (base, model, reasoning). `voice` is gone: the LAN router was rebuilt 2026-09-20 and 404s that
# name. Measured to first SENTENCE with the real prompt and the hang_up tool (2026-09-21):
#   ornith 0.48 s (LAN, free)  ·  luna 1.52 s (hosted, ~$0.004/call)
BRAINS = {
    "ornith": (LOCAL_ROUTER, LOCAL_DEFAULT_MODEL, "none"),                   # ~0.5 s, LAN, free, needs SMALL_MODEL_RULES
    "luna": ("https://api.openai.com/v1", "gpt-5.6-luna", "none"),           # ~1.5 s, $0.20/$1.20 per 1M
    "terra": ("https://api.openai.com/v1", "gpt-5.6-terra", "none"),         # ~0.6-1.1 s, $2/$12 per 1M
    "sonnet5": ("https://api.anthropic.com/v1", "claude-sonnet-5", "off"),   # ~0.8 s, $2/$10 (needs a key)
    "haiku": ("https://api.anthropic.com/v1", "claude-haiku-4-5-20251001", "off"),
}


# ---------------------------------------------------------------- how to be on a phone call


# Appended to whatever the task says (`--system`). The task is WHY the call is happening; this is how a phone
# call goes, which is the same whoever is being called and whatever it is about. Two things earn their length
# here: the SHAPE section, because live call 5 sounded like a form being read out rather than a call being
# made, and the two register sections, because a business and a person you know are genuinely different calls
# and the business version of a personal call is unsettling to be on the end of.
PHONE_RULES = """

--- HOW TO TALK ON THIS CALL ---
You are speaking OUT LOUD to a real person on the telephone. Every word is read aloud verbatim.

THE ONE RULE THAT MATTERS MOST: ONE SHORT SENTENCE PER TURN, then stop and let them talk.
Aim for under twenty words. Two sentences is a mistake; three is a monologue. A semicolon or a colon means
you are about to break this rule - say the first half and let them answer. The other person is listening to
silence while you speak, so every extra word is a longer wait for them; this is the difference between a
conversation and being read a paragraph.

SOUND LIKE A PERSON, NOT A FORM:
- React, THEN answer. "Oh, six forty-five? That works." is a person; "Six forty-five works." is a form.
- Use their words back - if they say "quarter to seven", say "quarter to seven", not "six forty-five".
- Vary how you agree: "perfect", "that's great", "yeah, that works", "sounds good". Never reuse one.
- Contractions, always. A little warmth at the edges: "thanks so much", "have a good one".
- Numbers, times, dates and money as a person says them aloud: "seven forty-five", "the fourteenth",
  "twenty dollars". Never digits, symbols, markdown, asterisks or emoji.
- Never narrate what you are doing, never use a placeholder like [Name], never wrap your reply in quotes.

HOW A PHONE CALL GOES - the same shape every time:
1. THEY speak first, and how they answer tells you which kind of call this is: a business gives its name
   ("Giovanni's, this is Maria"), a person just says "Hello?".
2. Greet them BACK, then say who you are and why you are calling. Answering a greeting with a demand is the
   most robotic thing a caller can do.
3. The middle is THEIR process. They ask questions, look things up, put you on hold, transfer you. Answer
   what they ask and let them run it at their pace.
4. It gets settled - arranged, answered, or refused.
5. You read it back, once.
6. You close, over two or three turns.

CALLING A BUSINESS:
- Answer their greeting, then give the whole purpose in one sentence. "How can I help you?" wants all of it.
- Use the name they gave you: "Thanks, Maria."
- Put on hold, or "let me check"? Say yes briefly, then GO QUIET. Do not fill the silence or ask if they are
  still there. They often return with "thanks for holding"; just carry on.
- Expect to be asked for a name, a callback number, an account or order number, or a spelling. Give what you
  were told to give and nothing else. Say numbers in small groups, and spell an unusual name unasked:
  "Morgan - M, O, R, G, A, N."
- Transferred? Say "sure, thanks" - then INTRODUCE YOURSELF AGAIN. The new person heard none of it.
- Wrong place or wrong department: say what you need in one sentence and ask who you should speak to.
- "Is there anything else I can help you with?" is the closing signal, and your last clean chance to ask
  something you still need.
- These calls end briskly: "Great, thanks so much." / "You too, bye."

CALLING SOMEONE THEY KNOW - family, a friend, a neighbour:
- A different call. The business version of it sounds cold and strange, so slow down.
- Greet first, and say early and plainly that you are an assistant calling for them - whoever answers is
  expecting the person whose number this is. "Hi - this is Alex's assistant, I'm calling for him."
- MAKE SURE IT IS THEM BEFORE YOU SAY WHY YOU CALLED. Numbers go stale and other people pick up phones.
  Work their name into the greeting so they can correct you - "Hi, is this Taylor?" - and only once they have
  said yes do you explain the reason, mention anyone else, or repeat anything you were told. Until then you
  are talking to a stranger.
- "How are you?" is a real question here. Answer briefly, ask it back, and listen.
- Do NOT lead with the errand. One beat of greeting, then the reason. "What's up?" means get to the point.
- Small talk and tangents ARE the call. Follow them, then come back to what you needed.
- Asked after the person you are calling for? Say only what you were told. "I'll let him tell you all about
  it" is a perfectly normal answer and a much better one than guessing.
- Asked to pass something on ("tell him I said hi")? Say you will, and remember it - it goes in your report.
- These end slowly and with warning: "alright-", "okay, well-", "I'll let you go". Then goodbyes, then hang up.
- Never say "love you", and never send anyone else's love. That belongs to them, not to you.

WHEN IT DOES NOT GO STRAIGHT THROUGH:
- A recording, a phone menu or hold music: stay quiet and wait. Never talk over it.
- Voicemail: ONE short message - who you are, who you are calling for, why, and a callback number if you
  were given one - then stop and hang up. Do not converse with a machine.
- Someone else answered: ask for the person you wanted. If they are not there, ask when would be a good
  time, or leave a short message - your name, who you are calling for, and that you will try again. Do NOT
  hand over private details, plans or anyone else's business to whoever happened to pick up.
- Wrong person entirely, or they do not know who you mean: apologise, check nothing further, and let them
  go. Never explain the errand to them on the way out.
- They sound rushed or say it is a bad time: offer to call back rather than pushing on.
- Wrong number: apologise in one sentence and let them go.
- You both start at once: yield, every time. "Sorry - go ahead."
- You did not catch it: "Sorry, could you say that again?"

BEHAVIOUR:
- Answer the question you were actually asked. Do not restate your request every turn.
- Do NOT summarise what has been settled in order to lead into your next question. "Great, so his
  prescription is ready - and what time do you close?" is two turns jammed into one and sounds like a machine
  reading its notes. Just ask: "Perfect - and what time do you close today?"
- THE FACTS YOU WERE GIVEN ARE YOURS TO OFFER; they should never have to drag them out of you. The name it
  is under, how many people, a date, an order or account or reference number, a callback number - give the
  relevant one the moment it becomes relevant: "Perfect, and that's under Alex." Never ask them for
  something your instructions already told you.
- NEVER INVENT AN ANSWER. Your brief is short and will not have anticipated everything they ask. The moment
  you are about to supply a detail you were not given - where it is, what time, which address, how much, who
  else is coming, whether that is alright - STOP. A plausible answer is worse than none, because they act on
  it: someone told the wrong address drives to the wrong place. Say "that's a good question, let me check and
  get back to you."
- BUT ANSWER THE QUESTION THEY ASKED. Not having the answer is itself an answer - say so plainly and say
  what you will do about it: "I don't have the number of people in front of me, can I check and call you
  straight back?" Two ways of avoiding it are both worse than admitting it:
    * answering a DIFFERENT question you do know ("how many people?" / "it's under Alex Morgan") - they
      think they have been answered and they have not;
    * handing their question back to them ("how many people?" / "how many will be joining us?") - they asked
      YOU, they cannot possibly know, and it makes you sound like you are not listening.
  You are the one who called. Anything about the person you are calling for is yours to know or to go and
  find out, never theirs to supply. The same goes for anything you would be AGREEING to on their behalf - payment, medical
  details, dates of birth, passwords, permissions, committing them to something new.
- ONE readback, at the END, once it is settled - booked, confirmed, answered, scheduled or refused. One
  sentence: "Great, so that's two at six forty-five under Alex." / "Understood, so nothing before Thursday."
  That is the only place in the call where you repeat details back.
- THE READBACK IS A CHECK, NOT A RECITAL. Before you say it, compare what they just told you against what
  you actually came for. If they differ in ANY detail - a different day, a different time, a different name,
  a different person, a different price - say so instead of confirming it: "Sorry, I think we said Thursday
  - did that move to Wednesday?" People mishear and mistype constantly, and this is the one moment in the
  call where it can still be caught. Reading back a wrong detail as if it were right is the single most
  expensive thing you can do.
- If they offer something that fits what you were told to accept, take it and move on. If it falls OUTSIDE
  that, do not accept on your own authority: find the nearest thing that would work, say you will check, and
  get the details you would need.
- GET WHAT YOU CAME FOR. If the task was to find something out, do not start closing until you have the
  answer or they have said they cannot give it.
- If asked whether you are a real person, be honest: you are an assistant calling on their behalf.

ENDING - a ritual, not a line. Do not rush these together:
1. They say it is settled - done, answered, or impossible. You read it back once and thank them.
2. They say goodbye, or start winding down ("alright", "I'll let you go", "anything else?").
3. You say goodbye warmly and call the hang_up tool in that SAME turn.
- When you accept something, that turn ends with the acceptance and whichever fact they now need. Wait for
  them to confirm it is done before you start closing.
- Never hang up while anything is unresolved or a question is unanswered. But a call that FAILED still ends
  properly: thank them, say goodbye, hang up. Being told no is a result, not a reason to keep talking.
"""


def install_stop_signals() -> None:
    """
    SIGTERM (a supervisor stopping us) and SIGBREAK (Ctrl-Break: what the gateway sends on Windows to cancel a
    call) both used to kill the process outright, skipping the finally that hangs up - the call stayed up with
    nobody on it (review 2026-09-26). Turned into SystemExit, they unwind through run() the way Ctrl-C does.
    Main thread only (a Python rule); the event loop wakes every EVENT_POLL_S, so the handler runs promptly.
    """
    def stop(signum, _frame) -> None:
        print(f"\n[converse] stopping on signal {signum}", flush=True)
        raise SystemExit(128 + signum)
    for name in ("SIGTERM", "SIGBREAK"):
        sig = getattr(signal, name, None)
        if sig is not None:
            try:
                signal.signal(sig, stop)
            except (ValueError, OSError):
                pass


def main() -> None:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
        except Exception:
            pass

    secrets = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "secrets")

    def secret(name: str) -> Optional[str]:
        try:
            return open(os.path.join(secrets, name)).read().strip() or None
        except Exception:
            return None

    def api_key_for(base: str) -> Optional[str]:
        """Pick the matching KEY=VALUE line from secrets/api_keys for a hosted base URL."""
        keys = {}
        try:
            for line in open(os.path.join(secrets, "api_keys")):
                if "=" in line and not line.lstrip().startswith("#"):
                    k, v = line.strip().split("=", 1)
                    keys[k.strip()] = v.strip()
        except Exception:
            return None
        for host, var in (("openai.com", "OPENAI_API_KEY"), ("anthropic.com", "ANTHROPIC_API_KEY"),
                          ("cerebras.ai", "CEREBRAS_API_KEY"), ("groq.com", "GROQ_API_KEY"),
                          ("googleapis.com", "GEMINI_API_KEY"), ("livekit", "LIVEKIT_API_KEY")):
            if host in base:
                return keys.get(var)
        return None

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default=os.environ.get("SYMBIOSIS_URL") or secret("device-url") or "http://127.0.0.1:18765",
                    help="phone base URL (default: secrets/device-url, the LAN TLS endpoint)")
    ap.add_argument("--pin", default=os.environ.get("SYMBIOSIS_TLS_PIN") or secret("device-tls-fingerprint"),
                    help="SHA-256 fingerprint of the phone's TLS cert (default: secrets/device-tls-fingerprint)")
    ap.add_argument("--ws", default=None, help="event channel URL (default: derived from --base)")
    ap.add_argument("--token", default=os.environ.get("SYMBIOSIS_TOKEN"))
    ap.add_argument("--token-file", default=os.path.join(secrets, "device-token"))
    ap.add_argument("--dial", default=None, metavar="NUMBER", help="place the call, then converse on it")
    ap.add_argument("--session", default=None, metavar="SESSION_ID",
                    help="converse on a call you already placed (from telephony.place_call)")
    ap.add_argument("--responder", choices=["scripted", "local", "anthropic"], default="local")
    # luna by default, not the faster local `ornith`: measured on live call 5 the 9B follows the phone rules
    # loosely — it ignored "one short sentence" on about half its turns, talked past a time it had been told
    # to accept, and sometimes answered with no text at all. It is the right FALLBACK (see --fallback-brain),
    # not the right brain.
    ap.add_argument("--brain", choices=sorted(BRAINS) + ["custom"], default="luna",
                    help="preset for model settings; 'custom' preserves --local-base/model/reasoning: "
                         + ", ".join(f"{k} = {v[1]}" for k, v in sorted(BRAINS.items())))
    # --local-base/--local-model/--llm-reasoning/--llm-api-key default to None so main() can tell a flag that
    # was given from one that was not: with a preset they used to be silently replaced, and the key then went
    # to the preset's host (a Cerebras key sent to api.openai.com). Now that is refused.
    ap.add_argument("--local-base", default=None,
                    help=f"OpenAI-compatible base URL (default {LOCAL_ROUTER}). Needs --brain custom")
    ap.add_argument("--local-model", default=None,
                    help=f"model on --local-base (default {LOCAL_DEFAULT_MODEL}). The router's resident 'fast' lane "
                         "answers in ~0.5 s and is free, but needs SMALL_MODEL_RULES (applied automatically) to "
                         "write numbers as words and to call hang_up on a goodbye. Needs --brain custom")
    ap.add_argument("--local-fallback", default="", help="model on --local-base to re-ask when --local-model "
                    "stalls ('' = none; prefer --fallback-brain, which may be on another host)")
    ap.add_argument("--fallback-brain", choices=sorted(BRAINS) + ["none"], default="ornith",
                    help="brain to re-ask when the main one gives no token within --first-token-timeout. "
                         "Default 'ornith' (the LAN router, ~0.5 s, free), which covers a hosted brain's slow "
                         "minute; 'none' to fall back to the same brain with a longer deadline")
    ap.add_argument("--first-token-timeout", type=float, default=2.5)
    ap.add_argument("--llm-api-key", default=None,
                    help="Bearer key for --local-base when it is a hosted OpenAI-compatible API (env LLM_API_KEY). "
                         "Needs --brain custom; a preset uses its own host's key from secrets/api_keys. E.g. --local-base https://api.openai.com/v1 --local-model gpt-5.6-luna --llm-reasoning none, "
                         "https://api.anthropic.com/v1 --local-model claude-sonnet-5 --llm-reasoning off, or "
                         "https://api.cerebras.ai/v1 --local-model qwen-3.8-27b; see docs/conversation-mode.md. "
                         "Default: env LLM_API_KEY, else the matching line in secrets/api_keys")
    ap.add_argument("--llm-reasoning", default=None,
                    help="reasoning_effort sent for non-'voice' models: 'none' (the default) for the router, "
                         "'low'/'minimal' for hosted models that reject 'none', 'off' to omit the field. "
                         "Needs --brain custom")
    brief = ap.add_mutually_exclusive_group()
    brief.add_argument("--system", default="You are a helpful assistant making a phone call on the user's behalf.",
                       help="the brief: what this call is for")
    # The brief can carry account and callback numbers; on the command line it is visible to anything that can
    # list processes. The gateway writes it to a private file and passes the path instead.
    brief.add_argument("--system-file", default=None, metavar="PATH",
                       help="read the brief from this file (UTF-8) instead of --system")
    ap.add_argument("--model", default="claude-opus-4-8")
    ap.add_argument("--greeting", default=None,
                    help="opening line (default: the model writes one from --system; synthesized before dialing)")
    ap.add_argument("--backchannel", action="store_true",
                    help=f"play a neutral filler ('Mm-hmm.', 'Let me see.') when the first sentence is more than "
                         f"{FILLER_AFTER_S}s late, once per turn, only after the final transcript was accepted. Off "
                         "by default")
    ap.add_argument("--min-silence-ms", type=int, default=400,
                    help="shortest pause before the phone may close a turn; the end-of-turn model can only make it "
                         "wait longer (up to --hangover-ms), never shorter. 200 chopped mid-sentence pauses on real calls")
    ap.add_argument("--no-preempt", action="store_true",
                    help="don't start the reply on the interim transcript (wait for the final)")
    ap.add_argument("--no-outcome", action="store_true",
                    help="skip the structured [outcome] JSON the driver reports after the call")
    # The call brain by default. The LAN 9B writes good `facts` and a good `follow_up` (four transcripts,
    # 1.1-1.9s each), but it is soft on the one field an agent BRANCHES on: a call where the question went
    # unanswered and an answer was promised for later came back "done" rather than "partial", twice, with the
    # rule spelled out. luna graded all four correctly. One extra non-streaming request per call is nothing
    # next to the call itself, so buy the judgment; '--outcome-brain ornith' makes the pass free.
    ap.add_argument("--outcome-brain", choices=sorted(BRAINS) + ["same"], default="same",
                    help="brain that reads the transcript back into the [outcome] JSON (default: the same brain "
                         "that held the call; 'ornith' does it free on the LAN, but grades partial/done loosely)")
    ap.add_argument("--hangover-ms", type=int, default=900,
                    help="longest pause before the phone closes a turn; the end-of-turn model usually closes it "
                         "at ~200ms when the person sounds finished")
    ap.add_argument("--no-endpoint-model", action="store_true",
                    help="close turns on silence alone (--hangover-ms) instead of the end-of-turn model")
    ap.add_argument("--barge-in-min-ms", type=int, default=500,
                    help="how long they must keep talking over the agent before it stops (0 = first syllable)")
    ap.add_argument("--idle-nudge", type=float, default=8.0,
                    help=f"after this many seconds of silence ask '{IDLE_NUDGE_LINE}' once (0 disables)")
    ap.add_argument("--idle-hangup", type=float, default=25.0,
                    help="hang up after this many seconds of total silence (0 disables)")
    ap.add_argument("--tts", choices=["android", "kokoro", "kokoro-server", "chatterbox"], default="chatterbox",
                    help="chatterbox = the Joi voice on the LAN box, warmer and more expressive, ~1.0 s per short "
                         "sentence (default since live call 4; falls back to kokoro-server if the box is not "
                         "ready); kokoro-server = GPU Kokoro, ~0.4 s per sentence; kokoro = in-process CPU; "
                         "android = phone's engine")
    ap.add_argument("--tts-server", default=None,
                    help=f"base URL of the TTS server (default: $KOKORO_SERVER or {KOKORO_DEFAULT} for "
                         f"kokoro-server, {CHATTERBOX_DEFAULT} for chatterbox)")
    ap.add_argument("--tts-helper", default=VOICE_HELPER_DEFAULT,
                    help="with --tts chatterbox: a second Chatterbox (OpenAI-style /v1/audio/speech, `clone` voice) "
                         "that renders everything after a reply's first clause in parallel; 'none' disables "
                         f"(default {VOICE_HELPER_DEFAULT}, a configured voice service)")
    ap.add_argument("--voice", default=None,
                    help="voice name: a kokoro voice (see --list-voices; default af_sarah) or a chatterbox voice "
                         "(joi, channel_a, channel_b; default joi)")
    ap.add_argument("--stt", choices=["device", "whisper"], default="whisper",
                    help="whisper = also send each turn's audio to the faster-whisper server on the LAN; the "
                         "phone's transcript still drives the reply, Whisper's confirms, corrects or withdraws it")
    ap.add_argument("--whisper-server", default=os.environ.get("WHISPER_SERVER", WHISPER_DEFAULT))
    ap.add_argument("--stt-prompt", default=None,
                    help="vocabulary to seed Whisper with (names, jargon); default: the capitalised words of "
                         "--system plus a few phone-call phrases")
    ap.add_argument("--voice-speed", type=float, default=1.0)
    ap.add_argument("--list-voices", action="store_true", help="print kokoro voices and exit")
    ap.add_argument("-v", "--verbose", action="store_true", help="show interim transcripts")
    a = ap.parse_args()
    if a.responder == "local" and a.brain != "custom":
        given = [flag for flag, v in (("--local-base", a.local_base), ("--local-model", a.local_model),
                                      ("--llm-reasoning", a.llm_reasoning), ("--llm-api-key", a.llm_api_key))
                 if v is not None]
        if given:
            raise SystemExit(f"{', '.join(given)} {'needs' if len(given) == 1 else 'need'} --brain custom: the "
                             f"'{a.brain}' preset sets its own endpoint, model and reasoning, and uses its own "
                             f"host's key from secrets/api_keys")
    a.local_base = a.local_base if a.local_base is not None else LOCAL_ROUTER
    a.local_model = a.local_model if a.local_model is not None else LOCAL_DEFAULT_MODEL
    a.llm_reasoning = a.llm_reasoning if a.llm_reasoning is not None else "none"
    if a.system_file:
        try:
            with open(a.system_file, encoding="utf-8") as f:
                a.system = f.read().strip()
        except OSError as e:
            raise SystemExit(f"could not read --system-file {a.system_file}: {e}")
        if not a.system:
            raise SystemExit(f"--system-file {a.system_file} is empty")

    install_stop_signals()

    if a.list_voices:
        from kokoro_onnx import Kokoro
        d = os.path.expanduser("~/.kokoro")
        k = Kokoro(os.path.join(d, "kokoro-v1.0.onnx"), os.path.join(d, "voices-v1.0.bin"))
        print(" ".join(sorted(k.get_voices())))
        return

    claim_singleton(a.base)

    token = a.token
    if not token and a.token_file:
        try:
            token = open(a.token_file).read().strip()
        except Exception:
            token = None
    if not token:
        raise SystemExit("no token: set SYMBIOSIS_TOKEN, pass --token-file, or create secrets/device-token")

    ws_url = a.ws or (a.base.replace("http://", "ws://").replace("https://", "wss://") + "/ws/events")

    state: dict = {}
    # How to behave on a phone call, independent of WHY the call is being made (--system carries the task).
    # Tested against observed failures: confirming everything back, restating the request each turn,
    # six-second opening monologues, reading "7:45" as digits, inventing facts about the person, hanging up
    # while the other side is still disagreeing.
    phone_rules = PHONE_RULES
    outcome_brain: Optional[Brain] = None     # set below when there is a brain to read the transcript back
    outcome_fallback: Optional[Brain] = None  # the call brain, if the cheap summariser cannot be reached
    if a.responder == "scripted":
        respond = scripted_responder()
    elif a.responder == "local":
        primary_key = None
        if a.brain != "custom":
            a.local_base, a.local_model, a.llm_reasoning = BRAINS[a.brain]
            if os.environ.get("LLM_API_KEY"):
                print(f"[converse] LLM_API_KEY is ignored with --brain {a.brain}; it applies to --brain custom only",
                      flush=True)
        else:
            primary_key = a.llm_api_key or (os.environ.get("LLM_API_KEY") if a.local_base.startswith("https://")
                                            else None)

        def make_brain(base: str, model: str, reasoning: str, key: Optional[str] = None) -> Brain:
            # Only the custom primary (and its --local-fallback, same host) is given a key by the caller. Every
            # other brain gets the key secrets/api_keys holds for ITS host, so no key reaches another provider.
            if base.startswith("https://") and not key:
                key = api_key_for(base)
            return Brain(base, model, None if reasoning == "off" else reasoning, rules_for(model), key,
                         trust_tools=not is_small_model(model))

        primary = make_brain(a.local_base, a.local_model, a.llm_reasoning, primary_key)
        # The fallback is whatever answers when the primary gives no token in time. It does NOT have to live
        # on the same host: a hosted brain having a slow minute is best covered by the LAN router, which
        # answers in about half a second — far better than the canned "could you say that again".
        fb = None
        if a.fallback_brain and a.fallback_brain != "none":
            fb_base, fb_model, fb_reasoning = BRAINS[a.fallback_brain]
            if (fb_model, fb_base) != (primary.model, primary.base):
                fb = make_brain(fb_base, fb_model, fb_reasoning)
        elif a.local_fallback and a.local_fallback != a.local_model:
            fb = make_brain(a.local_base, a.local_fallback, a.llm_reasoning, primary_key)
        respond = local_responder(a.system + call_context() + phone_rules, state=state,
                                  first_token_timeout=a.first_token_timeout,
                                  brain=primary, fallback_brain=fb)
        outcome_brain = None if a.no_outcome else primary
        if outcome_brain is not None and a.outcome_brain != "same":
            ob_base, ob_model, ob_reasoning = BRAINS[a.outcome_brain]
            outcome_brain = make_brain(ob_base, ob_model, ob_reasoning)
            outcome_fallback = primary
    else:
        respond = anthropic_responder(a.system + call_context() + phone_rules, a.model)

    if a.tts == "chatterbox":
        tts_server = a.tts_server or CHATTERBOX_DEFAULT
        tts_voice = a.voice or "joi"
    else:
        tts_server = a.tts_server or os.environ.get("KOKORO_SERVER") or KOKORO_DEFAULT
        tts_voice = a.voice or "af_sarah"
    if a.stt_prompt is not None:
        stt_prompt = a.stt_prompt
    else:
        # Seeded from THIS task, not from a fixed domain: the proper nouns and spelled-out numbers in --system
        # are exactly the words a recogniser gets wrong on carrier audio, plus the few any phone call uses.
        skip = {"you", "if", "say", "accept", "ask", "the", "i", "a", "do", "when", "never", "and", "or", "it",
                "your", "their", "this", "that", "on", "in", "of", "to", "for", "is", "are", "be", "call"}
        vocab: list = []
        for w in re.findall(r"\b[A-Z][a-zA-Z']+\b", a.system):
            if w not in vocab and w.lower() not in skip:
                vocab.append(w)
        for n in re.findall(r"\b(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|twenty|"
                            r"thirty|forty|fifty|hundred|thousand)\b", a.system, re.I):
            if n not in vocab:
                vocab.append(n)
        stt_prompt = ", ".join(vocab[:16] + ["a.m.", "p.m.", "today", "tomorrow", "goodbye"])
    try:
        run(a.base, ws_url, token, respond, a.greeting, a.verbose, state,
            Voice(a.tts, tts_voice, a.voice_speed, tts_server,
                  helper=None if a.tts_helper in (None, "", "none") else a.tts_helper), a.idle_hangup,
            dial=a.dial, session_id=a.session, pin=a.pin, backchannel=a.backchannel,
            preempt=not a.no_preempt, idle_nudge=a.idle_nudge,
            stt=a.stt, whisper_server=a.whisper_server, stt_prompt=stt_prompt,
            task=a.system, outcome_brain=outcome_brain, outcome_fallback=outcome_fallback,
            converse_args={"hangover_ms": a.hangover_ms, "endpoint_model": not a.no_endpoint_model,
                           "min_silence_ms": a.min_silence_ms,
                           "barge_in_min_ms": a.barge_in_min_ms})
    except KeyboardInterrupt:
        print("\n[converse] stopping", flush=True)


if __name__ == "__main__":
    main()
