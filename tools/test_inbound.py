#!/usr/bin/env python3
"""
Offline checks for tools/inbound.py: no phone, no model.

Every text sent to the phone was ignored for as long as inbound.py existed: it read `frame["payload"]`, and the
phone's event frames carry the payload under `frame["event"]`. Nothing caught it because nothing ever fed the
handler a frame shaped like the real thing. These do, and they also cover the daemon surviving a failed send
and a dropped socket (review 2026-09-26).

    python tools/test_inbound.py
"""
from __future__ import annotations

import contextlib
import io
import json
import time

import converse as C
import inbound


OWNER = "+14695550100"


def sms_frame(text: str, sender: str = OWNER, mid: str = "m1", eid: int = 1) -> dict:
    """A messaging.sms_received event exactly as the phone pushes it on /ws/events (protocol.md 4.3)."""
    return {"type": "event", "subscription_id": "sub_1",
            "event": {"id": eid, "topic": "messaging.sms_received", "ts": 1760000000000, "correlation_id": "c1",
                      "payload": {"from": sender, "text": text, "message_id": mid, "received_at": 1760000000000}}}


class FakeRt:
    def __init__(self, send_fails=None):
        self.sent: list = []
        self.send_fails = list(send_fails or [])   # per call: an Exception to raise, a dict to return, or None

    def call(self, tool, args, meta=None):
        del meta
        if tool == "messaging.send_sms":
            f = self.send_fails.pop(0) if self.send_fails else None
            if isinstance(f, BaseException):
                raise f
            if isinstance(f, dict):
                return f
            self.sent.append((args["to"], args["body"]))
            return {"sent": True}
        return {}


@contextlib.contextmanager
def patched():
    """No model and no log file: `answer` echoes, `log_line` collects."""
    logged: list = []
    saved = inbound.answer, inbound.log_line
    inbound.answer = lambda rt, brain, text, elevated, verbose=False, has_passphrase=False: f"echo: {text}"
    inbound.log_line = logged.append
    try:
        yield logged
    finally:
        inbound.answer, inbound.log_line = saved


def check(cond, msg, out=""):
    if not cond:
        print(out)
        raise AssertionError(msg)


def test_real_frame_reaches_handler():
    """The bug itself: a real-shaped frame must produce a reply to its sender."""
    rt = FakeRt()
    with patched() as logged, contextlib.redirect_stdout(io.StringIO()) as out:
        handle = inbound.make_handler(rt, None, [OWNER], None)
        took = inbound.dispatch(sms_frame("is the battery ok?"), handle, verbose=True)
    check(took, "an sms_received event must be dispatched", out.getvalue())
    check(rt.sent == [(OWNER, "echo: is the battery ok?")], f"the sender gets the reply: {rt.sent}", out.getvalue())
    check("[ev] messaging.sms_received id=1" in out.getvalue(), "verbose shows topic and id without raw body", out.getvalue())
    check(logged and logged[0]["sent"] == {"sent": True}, f"logged: {logged}", out.getvalue())
    print("ok  real-frame")


def test_other_frames_ignored():
    rt = FakeRt()
    with patched(), contextlib.redirect_stdout(io.StringIO()):
        handle = inbound.make_handler(rt, None, [OWNER], None)
        other = {"type": "event", "event": {"id": 2, "topic": "battery.changed", "payload": {"percent": 50}}}
        check(not inbound.dispatch(other, handle), "another topic is not a text")
        check(not inbound.dispatch({"type": "hello", "peer_id": "p"}, handle), "a control frame is not a text")
        # The old, wrong shape (payload at the top level) must not be mistaken for a text either.
        check(not inbound.dispatch({"type": "event", "topic": "messaging.sms_received",
                                    "payload": {"from": OWNER, "text": "hi"}}, handle), "no event object, no text")
    check(rt.sent == [], f"nothing sent: {rt.sent}")
    print("ok  other-frames")


def test_stranger_and_duplicates():
    rt = FakeRt()
    with patched() as logged, contextlib.redirect_stdout(io.StringIO()):
        handle = inbound.make_handler(rt, None, [OWNER], None)
        inbound.dispatch(sms_frame("hi", sender="+15555550199", mid="s1"), handle)
        inbound.dispatch(sms_frame("hi", mid="d1"), handle)
        inbound.dispatch(sms_frame("hi", mid="d1", eid=9), handle)   # carrier redelivery / replay overlap
    check(rt.sent == [(OWNER, "echo: hi")], f"stranger ignored, duplicate answered once: {rt.sent}")
    check(logged[0].get("ignored") == "not allowlisted", f"the stranger is logged: {logged}")
    print("ok  stranger-and-duplicates")


def test_send_failure_does_not_kill_daemon():
    """An OSError from send_sms used to end the process. Log it, and answer the next text as normal."""
    rt = FakeRt(send_fails=[OSError("connection reset by peer"), {"error": {"code": "policy.denied"}}])
    with patched() as logged, contextlib.redirect_stdout(io.StringIO()) as out:
        handle = inbound.make_handler(rt, None, [OWNER], None)
        inbound.dispatch(sms_frame("one", mid="a"), handle)
        inbound.dispatch(sms_frame("two", mid="b"), handle)
        inbound.dispatch(sms_frame("three", mid="c"), handle)
    check(rt.sent == [(OWNER, "echo: three")], f"the third goes through: {rt.sent}", out.getvalue())
    check("send failed" in out.getvalue(), "failures are logged", out.getvalue())
    check("OSError" in json.dumps(logged[0]["sent"]), f"and recorded: {logged[0]}", out.getvalue())
    print("ok  send-failure-survives")


class StopTest(BaseException):
    """Ends listen()'s endless loop from inside the fake socket (BaseException: not taken for a socket error)."""


class FakeWs:
    """Socket 1 delivers a text then drops; socket 2 answers the replay with the text that arrived meanwhile."""

    def __init__(self, n: int, journal: list):
        self.n, self.journal = n, journal
        self.out: list = [{"type": "hello", "peer_id": "p", "last_event_id": 4 if n == 1 else 6, "subscriptions": []}]
        self.script = [sms_frame("before the blip", mid="x1", eid=5)] if n == 1 else []

    def send(self, raw):
        m = json.loads(raw)
        if m["type"] == "subscribe":
            self.out.append({"type": "subscribed", "subscription_id": "sub_1", "created_at_event_id": 0})
        elif m["type"] == "replay":
            evs = [e["event"] for e in self.journal if e["event"]["id"] > m["since_id"]]
            self.out.append({"type": "replay_result", "subscription_id": "sub_1", "events": evs,
                             "next_since_id": 99, "truncated": False, "earliest_available_id": 1})

    def recv(self, timeout=None):
        del timeout
        if self.out:
            return json.dumps(self.out.pop(0))
        if self.script:
            return json.dumps(self.script.pop(0))
        if self.n == 1:
            raise ConnectionError("simulated Wi-Fi blip")
        raise StopTest()

    def close(self):
        pass


def test_reconnects_and_replays():
    """A dropped socket used to end the daemon for good. Reconnect, and replay the text sent during the outage."""
    journal = [sms_frame("before the blip", mid="x1", eid=5), sms_frame("during the blip", mid="x2", eid=6)]
    conns = {"n": 0}

    def connect(url, **kw):
        del url, kw
        conns["n"] += 1
        if conns["n"] == 2:
            raise ConnectionRefusedError("phone still unreachable")   # one refusal: the backoff path
        return FakeWs(1 if conns["n"] == 1 else 2, journal)

    saved = C.ws_connect
    C.ws_connect = connect
    rt = FakeRt()
    t0 = time.time()
    try:
        with patched(), contextlib.redirect_stdout(io.StringIO()) as out:
            handle = inbound.make_handler(rt, None, [OWNER], None)
            try:
                inbound.listen("ws://fake", "tok", None, handle)
            except StopTest:
                pass
    finally:
        C.ws_connect = saved
    log = out.getvalue()
    check(rt.sent == [(OWNER, "echo: before the blip"), (OWNER, "echo: during the blip")],
          f"both texts answered, each once: {rt.sent}", log)
    check("event socket back" in log and "1 missed event(s) replayed" in log, "reconnect + replay logged", log)
    check(time.time() - t0 < 10, "the backoff starts short", log)
    print("ok  reconnect-and-replay")


if __name__ == "__main__":
    for t in (test_real_frame_reaches_handler, test_other_frames_ignored, test_stranger_and_duplicates,
              test_send_failure_does_not_kill_daemon, test_reconnects_and_replays):
        t()
    print("all inbound tests passed")
