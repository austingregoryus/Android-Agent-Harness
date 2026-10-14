#!/usr/bin/env python3
"""
Simulated calls for the conversation driver: no phone, no model, no TTS.

The turn-taking logic in converse.py (preemptive replies, fillers, interruptions that are real or false,
resuming, history truncation, hang-up decisions, error recovery) is exactly the kind of thing that only ever
failed on a live call before. This drives `run()` with a fake phone (scripted call.* events with real timing, a
fake play_audio that can be interrupted mid-clip) and asserts what was actually spoken.

    python tools/test_converse_sim.py            # all scenarios
    python tools/test_converse_sim.py yes_over   # only those whose name contains the argument
"""
from __future__ import annotations

import contextlib
import io
import re
import sys
import threading
import time

import converse


class FakeRuntime:
    """Stands in for converse.Runtime: records what the phone was asked to play."""

    def __init__(self, base, token, pin=None):
        del base, token, pin
        self.ssl = None
        self.played: list = []        # (text, interrupted, played_ms)
        self.ended = False
        self.end_calls = 0            # telephony.end_call requests, successful or not
        self.interrupt_flag = threading.Event()
        self.audio_rate = 1.0         # 1.0 = play clips in real time
        # Failure injection. The real Runtime.call raises on a transport error (OSError, a read timeout) and
        # returns {"error": ...} when the phone refuses; the fake never did either, which is how a player thread
        # that died on its first exception went unnoticed. `faults[tool]` is a list consumed one per call: an
        # Exception instance is raised, a dict is returned, None means behave normally. `faults_forever[tool]`
        # is a function called on every call, returning the same kinds of thing.
        self.faults: dict = {}
        self.faults_forever: dict = {}
        self.on_place = None          # called inside place_call, as if the phone emitted events meanwhile
        self.converse_reply = {"started": True, "mic_muted": True, "endpoint": "model"}

    def init(self):
        return "sess"

    def _fault(self, tool):
        pending = self.faults.get(tool)
        f = pending.pop(0) if pending else (self.faults_forever[tool]() if tool in self.faults_forever else None)
        if f is not None and tool == "telephony.end_call":
            self.end_calls += 1
        if isinstance(f, BaseException):
            raise f
        return f

    def call(self, tool, args, meta=None):
        del meta
        f = self._fault(tool)
        if isinstance(f, dict):
            return f
        if tool == "telephony.place_call":
            if self.on_place:
                self.on_place()
            return {"session_id": "s1"}
        if tool == "telephony.converse":
            r = self.converse_reply
            if isinstance(r, BaseException):
                raise r
            return r
        if tool == "telephony.get_call_state":
            return {"state": "active"}
        if tool == "telephony.end_call":
            self.end_calls += 1
            self.ended = True
            return {"ended": True}
        if tool == "telephony.turn_audio":
            # A tiny valid WAV; the fake Whisper looks the turn up by id, not by audio.
            import base64, struct
            pcm = b"\x00\x00" * 1600
            wav = (b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, 16000, 32000, 2, 16)
                   + b"data" + struct.pack("<I", len(pcm)) + pcm)
            time.sleep(0.05)  # an MCP round trip
            return {"turn_id": args.get("turn_id"), "audio_base64": base64.b64encode(wav).decode(), "duration_ms": 100}
        if tool in ("telephony.play_audio", "telephony.speak"):
            text = args.get("audio_base64") or args.get("text")
            secs = float(len(text.split()) * 0.25)   # ~4 words/s, like a real clip
            t0 = time.time()
            interrupted = False
            while time.time() - t0 < secs * self.audio_rate:
                if self.interrupt_flag.is_set():
                    self.interrupt_flag.clear()
                    interrupted = True
                    break
                time.sleep(0.02)
            played_ms = int((time.time() - t0) * 1000 / self.audio_rate)
            self.played.append((text, interrupted, played_ms))
            return {"played": True, "interrupted": interrupted, "played_ms": played_ms,
                    "duration_ms": int(secs * 1000), "drained": not interrupted}
        return {}


class FakeVoice(converse.Voice):
    def __init__(self):
        super().__init__("fake")
        self.backchannels = [("Mm-hmm.", "Mm-hmm.", 0.3), ("One moment.", "One moment.", 0.3)]

    def can_presynth(self):
        return True

    def warm(self):
        pass

    def synth_wav_b64(self, text):
        time.sleep(0.05)
        return text, len(text.split()) * 0.25, 0.05   # the "clip" is the text, so play_audio logs it


class FakeSocket:
    """
    Replays a script of (delay_seconds, frame_or_action) with real timing. The script, and what the "phone" has
    journalled, are shared by every socket of one simulated call, so a reconnect picks up where the drop left off.

    Script actions: "interrupt" cuts the clip playing now; a callable is called; ("drop", [events]) kills this
    socket, and the events are what the phone emitted while it was down - reachable only through `replay`.
    """

    def __init__(self, shared: dict, rt: FakeRuntime):
        self.shared = shared
        self.rt = rt
        # The phone greets every new socket with its newest event id; the driver replays from no earlier.
        self.replies: list = [{"type": "hello", "peer_id": "sim", "last_event_id": 100, "subscriptions": []}]
        self.dead = False

    def send(self, raw):
        import json
        m = json.loads(raw)
        if m.get("type") == "subscribe":
            self.replies.append({"type": "subscribed", "subscription_id": "sub_sim", "created_at_event_id": 0})
        elif m.get("type") == "replay":
            since = m.get("since_id") or 0
            evs = [e for e in self.shared.get("journal", []) if e.get("id", 0) > since]
            self.shared.setdefault("replays", []).append(since)
            self.replies.append({"type": "replay_result", "subscription_id": m.get("subscription_id"),
                                 "events": evs, "next_since_id": max([since] + [e.get("id", 0) for e in evs]),
                                 "truncated": False, "earliest_available_id": 1})

    def recv(self, timeout=None):
        del timeout   # the script's own delays stand in for waiting
        import json
        if self.dead:
            raise ConnectionError("socket is closed")
        if self.replies:
            return json.dumps(self.replies.pop(0))
        pushed = self.shared.setdefault("pushed", [])
        if pushed:
            return json.dumps({"type": "event", "event": pushed.pop(0)})
        script = self.shared["script"]
        while script:
            delay, item = script.pop(0)
            time.sleep(delay)
            if item == "interrupt":
                self.rt.interrupt_flag.set()
                continue
            if isinstance(item, tuple) and item and item[0] == "drop":
                self.shared.setdefault("journal", []).extend(item[1])
                self.dead = True
                raise ConnectionError("simulated Wi-Fi blip")
            if callable(item):
                item()
                continue
            return json.dumps({"type": "event", "event": item})
        raise ConnectionError("script finished")

    def close(self):
        self.dead = True

    def __enter__(self):
        return self

    def __exit__(self, *a):
        del a
        return False


def ev(topic, **payload):
    payload.setdefault("session_id", "s1")
    return {"topic": topic, "payload": payload}


def listening(on, **kw):
    return ev("call.listening", listening=on, **kw)


def transcript(text, final=True):
    return ev("call.transcript", text=text, final=final)


FILLERS = {"Mm-hmm.", "One moment."}


def responder_factory(replies: dict, log: list, think: float = 0.3, opening=None):
    """
    Reply with the scripted sentences for a user turn; the opening line is fixed. A reply may also be an
    Exception instance (raised when the stream is first pulled, like a first-token timeout), or a list ending
    with a converse.HangUp (the model called the tool). `opening` may be a LIST, to stand in for a long
    greeting the responder streams a clause at a time.
    """
    def respond(text, history):
        del history
        log.append(text)
        if text.startswith("(The call has just been answered"):
            if opening is None:
                return "Hi, this is Alex's assistant calling about a table for two."
            if isinstance(opening, str):
                return opening

            def gen_opening():
                for part in opening:
                    yield part
            return gen_opening()
        key = re.sub(r"[^a-z ]", "", text.lower()).strip()
        reply = replies.get(key, ["I did not understand that."])
        pause = think
        if isinstance(reply, dict):   # {"think": seconds, "say": [...]}: this turn thinks longer or shorter
            pause, reply = reply["think"], reply["say"]

        def gen():
            time.sleep(pause)   # a model thinking
            if isinstance(reply, Exception):
                raise reply
            for s in reply:
                yield s
        return gen()
    return respond


def simulate(script, replies, think: float = 0.3, opening=None, voice=None, answered=True, setup=None,
             refuse_connects=0, **run_kw):
    """
    One simulated call. `setup(rt, shared)` runs before the driver starts (inject faults there). After the first
    connection the socket factory refuses `refuse_connects` more (a phone slow to come back after a blip). A
    SystemExit from the driver is caught and appended to the log as "[sim exit: <code>]".
    """
    rt_holder = {}
    shared: dict = {"connects": 0, "open_at_place": None}

    def make_rt(base, token, pin=None):
        # The driver opens a second Runtime for Whisper's audio fetches; playback goes through the first.
        rt = rt_holder.setdefault("rt", FakeRuntime(base, token, pin))
        return rt

    # Every call is answered first: the driver only counts a call as connected on the precise ACTIVE state.
    script = ([(0.1, ev("call.precise_state", state="ACTIVE"))] if answered else []) + list(script)
    shared["script"] = script
    refusals = {"left": refuse_connects}

    def connect(url, **kw):
        del url, kw
        shared["connects"] += 1
        if shared["connects"] > 1 and refusals["left"] > 0:
            refusals["left"] -= 1
            raise ConnectionRefusedError("phone unreachable")
        return FakeSocket(shared, rt_holder["rt"])

    converse.Runtime = make_rt  # type: ignore[assignment]
    converse.ws_connect = connect  # type: ignore[assignment]
    make_rt("http://fake", "tok")
    if setup:
        setup(rt_holder["rt"], shared)
    asked: list = []
    respond = responder_factory(replies, asked, think, opening)
    out = io.StringIO()
    run_kw.setdefault("idle_hangup", 0)
    with contextlib.redirect_stdout(out):
        try:
            converse.run("http://fake", "ws://fake", "tok", respond, None, False, {}, voice or FakeVoice(),
                         dial="+15550000000", **run_kw)
        except SystemExit as e:
            print(f"[sim exit: {e.code}]")
        except Exception as e:
            print(f"[sim error: {type(e).__name__}: {e}]")
        time.sleep(0.5)
    rt_holder["rt"].shared = shared
    return rt_holder["rt"], asked, out.getvalue()


def played_texts(rt):
    return [t for t, _, _ in rt.played]


def check(cond, msg, log):
    if not cond:
        print(log)
        raise AssertionError(msg)


# The far party's greeting, as the phone emits it: a turn opens, closes, then its final arrives.
HELLO = [(0.2, listening(True)), (0.3, listening(False, endpoint="short", silence_ms=450)), (0.05, transcript("Hello"))]


# A far-party greeting, then one closed turn with a final: the shape every scenario starts from.
def hello_then(final_text, interim=None, gap=1.5):
    s = [*HELLO, (gap, listening(True))]
    if interim:
        s.append((0.3, transcript(interim, final=False)))
    s += [(0.5, listening(False, endpoint="model", silence_ms=400)), (0.2, transcript(final_text))]
    return s


def scenario_preempt_match():
    """Interim == final: the reply started early is the one that plays, once — and no filler on a fast model."""
    script = [
        *HELLO,
        (1.5, listening(True)), (0.3, transcript("does seven twenty work", final=False)),
        (1.2, listening(False, endpoint="model", silence_ms=200, p_complete=0.93)),
        (0.4, transcript("Does seven twenty work?")),
        (3.0, ev("call.ended", outcome="remote_hangup", duration_ms=9000)),
    ]
    rt, asked, log = simulate(script, {"does seven twenty work": ["Yes, seven twenty is perfect.", "Under the name Alex."]})
    p = played_texts(rt)
    check(p[0].startswith("Hi, this is Alex"), f"opening first: {p}", log)
    check(not (FILLERS & set(p)), f"no filler when the model is fast: {p}", log)
    check(p.count("Yes, seven twenty is perfect.") == 1 and p.count("Under the name Alex.") == 1, f"reply once: {p}", log)
    check("[preempted reply kept" in log, "preemption should be kept", log)
    check(asked.count("does seven twenty work") == 1, f"model asked once: {asked}", log)
    print("ok  preempt-match       ", p)


def scenario_preempt_mismatch():
    """Interim != final: the early reply is abandoned and the real one plays, once."""
    script = [
        *HELLO,
        (1.5, listening(True)), (0.3, transcript("does seven", final=False)),
        (1.2, listening(False, endpoint="model", silence_ms=200)),
        (0.4, transcript("Does seven thirty work?")),
        (3.0, ev("call.ended", outcome="remote_hangup", duration_ms=9000)),
    ]
    rt, asked, log = simulate(script, {"does seven": ["WRONG."], "does seven thirty work": ["Seven thirty is fine."]})
    del asked
    p = played_texts(rt)
    check("WRONG." not in p, f"abandoned reply must not play: {p}", log)
    check(p.count("Seven thirty is fine.") == 1, f"real reply once: {p}", log)
    check("[preempted reply abandoned" in log, "should log the abandonment", log)
    print("ok  preempt-mismatch    ", p)


def scenario_filler_when_slow():
    """--backchannel: a neutral filler plays only when the first sentence is late, once, before the reply."""
    script = hello_then("What time") + [(4.0, ev("call.ended", outcome="ENDED", duration_ms=9000))]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty for two, please."]}, think=1.6)
    del asked
    p = played_texts(rt)
    fillers = [t for t in p if t in FILLERS]
    check(len(fillers) == 1, f"exactly one filler: {p}", log)
    check(p.index(fillers[0]) < p.index("Seven twenty for two, please."), f"filler before the reply: {p}", log)
    check("[filler: no first sentence" in log, "should log the filler", log)
    print("ok  filler-when-slow    ", p)


def scenario_false_interruption():
    """A barge-in with no transcript behind it: finish what we were saying."""
    script = [
        *HELLO,
        (1.5, listening(True)), (0.5, transcript("what time", final=False)),
        (0.5, listening(False)), (0.3, transcript("What time")),
        # the reply is ~0.3s think + synth; interrupt it 0.6s into the first sentence
        (1.3, listening(True)), (0.5, ev("call.barge_in", after_ms=500)), (0.0, "interrupt"),
        (0.3, listening(False)),
        (4.0, ev("call.ended", outcome="remote_hangup", duration_ms=9000)),
    ]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty tonight for two people please.", "Is that available?"]})
    del asked
    p = rt.played
    texts = played_texts(rt)
    inter = [t for t, i, _ in p if i]
    check(len(inter) == 1 and inter[0].startswith("Seven twenty"), f"first sentence interrupted: {p}", log)
    check("[resuming after no transcript" in log, "should resume", log)
    check(any(t.startswith("Seven twenty") or "for two people" in t for t, i, _ in p if not i), f"remainder replayed: {p}", log)
    check(texts.count("Is that available?") == 1, f"parked sentence played once: {texts}", log)
    print("ok  false-interruption  ", texts)


def scenario_backchannel_interruption():
    """They said 'uh huh' over us: that is not a turn — carry on, and do not answer it."""
    script = [
        *HELLO,
        (1.5, listening(True)), (0.5, transcript("what time", final=False)),
        (0.5, listening(False)), (0.3, transcript("What time")),
        (1.3, listening(True)), (0.5, ev("call.barge_in", after_ms=500)), (0.0, "interrupt"),
        (0.3, listening(False)), (0.3, transcript("Uh huh")),
        (4.0, ev("call.ended", outcome="remote_hangup", duration_ms=9000)),
    ]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty tonight for two people please.", "Is that available?"]})
    texts = played_texts(rt)
    check("uh huh" not in [a.lower() for a in asked], f"backchannel must not reach the model: {asked}", log)
    check("[resuming after they only said" in log, "should resume after the backchannel", log)
    check(texts.count("Is that available?") == 1, f"parked sentence played once: {texts}", log)
    print("ok  backchannel-interrupt", texts)


def scenario_uhhuh_mid_reply_ignored():
    """'Uh huh' heard while more sentences are queued is a nod: no barge-in, no reply, but it is logged."""
    script = hello_then("What time") + [
        # first sentence is ~1.75 s; they nod 0.8 s in, short enough that the phone never barges in
        (1.1, listening(True)), (0.3, listening(False)), (0.2, transcript("Uh huh")),
        (4.0, ev("call.ended", outcome="ENDED", duration_ms=9000)),
    ]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty tonight for two people please.", "Is that available?", "Thanks."]})
    texts = played_texts(rt)
    check("uh huh" not in [a.lower() for a in asked], f"a nod must not reach the model as a turn: {asked}", log)
    check("[them, listening] uh huh" in log.lower(), "the nod should be logged", log)
    check(texts.count("Is that available?") == 1 and texts.count("Thanks.") == 1, f"reply continues: {texts}", log)
    print("ok  uhhuh-mid-reply     ", texts)


def scenario_yes_over_tail():
    """'Yes' spoken over the tail of a question, with nothing queued behind it, IS the answer."""
    script = hello_then("What time") + [
        # the question clip is 5 words = 1.25 s, starting ~0.35 s after the final: it ends ~1.6 s in.
        (1.1, listening(True)), (0.3, listening(False)), (0.15, transcript("Yes")),
        (4.0, ev("call.ended", outcome="ENDED", duration_ms=9000)),
    ]
    rt, asked, log = simulate(script, {"what time": ["Does seven twenty work then?"], "yes": ["Great, see you then."]})
    texts = played_texts(rt)
    check("yes" in [a.lower() for a in asked], f"the answer must reach the model: {asked}", log)
    check("[them, answering over the tail]" in log, "should log the answer path", log)
    check(texts.count("Great, see you then.") == 1, f"the answer gets a reply once: {texts}", log)
    print("ok  yes-over-tail       ", texts)


def scenario_real_interruption():
    """They interrupted with a real turn: drop what was parked, remember only what they heard."""
    script = [
        *HELLO,
        (1.5, listening(True)), (0.5, transcript("what time", final=False)),
        (0.5, listening(False)), (0.3, transcript("What time")),
        (1.3, listening(True)), (0.5, ev("call.barge_in", after_ms=500)), (0.0, "interrupt"),
        (0.6, transcript("actually make it eight", final=False)),
        (0.2, listening(False)), (0.3, transcript("Actually make it eight")),
        (4.0, ev("call.ended", outcome="remote_hangup", duration_ms=9000)),
    ]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty tonight for two people please.", "Is that available?"],
                                       "actually make it eight": ["Eight works too."]})
    del asked
    texts = played_texts(rt)
    check("Is that available?" not in texts, f"parked sentence must be discarded: {texts}", log)
    check(texts.count("Eight works too.") == 1, f"new reply once: {texts}", log)
    check("(interrupted after" in log and "heard '" in log, "history should record the heard portion", log)
    print("ok  real-interruption   ", texts)


def scenario_speaks_between_sentences():
    """They start talking in the gap between two of our sentences: we wait, and drop the stale sentence."""
    script = hello_then("What time") + [
        # sentence 1 (3 words, 0.75 s) starts ~0.35 s after the final and ends ~1.1 s in
        (1.05, listening(True)), (0.3, transcript("actually make it eight", final=False)),
        (0.5, listening(False)), (0.2, transcript("Actually make it eight")),
        (4.0, ev("call.ended", outcome="ENDED", duration_ms=9000)),
    ]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty please.", "Is that available?"],
                                       "actually make it eight": ["Eight works too."]})
    del asked
    texts = played_texts(rt)
    check("Is that available?" not in texts, f"sentence 2 must not be spoken over/after their new turn: {texts}", log)
    check(texts.count("Eight works too.") == 1, f"new reply once: {texts}", log)
    print("ok  speaks-between      ", texts)


def scenario_spoken_farewell_hangs_up():
    """The model says goodbye but forgets the hang_up tool: the spoken farewell must still end the call."""
    script = [
        *HELLO,
        (1.5, listening(True)), (0.5, listening(False)), (0.3, transcript("What time")),
        (2.5, listening(True)), (0.5, listening(False)), (0.3, transcript("You're all set, see you tonight, bye")),
        (6.0, ev("call.ended", outcome="ENDED", duration_ms=12000)),
    ]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty for two, please."],
                                       "youre all set see you tonight bye": ["Great, goodbye."]})
    del asked
    check(rt.ended, "the call must be ended by the driver", log)
    check("[hanging up: the agent said goodbye]" in log, "should hang up on the spoken farewell", log)
    texts = played_texts(rt)
    check(texts.count("Great, goodbye.") == 1, f"goodbye spoken once: {texts}", log)
    print("ok  spoken-farewell     ", texts)


def scenario_awesome_then_bye():
    """'Awesome' is agreement; and once the hang-up is decided, their 'Bye' gets no further 'Goodbye.'"""
    script = [
        *HELLO,
        (1.5, listening(True)), (0.5, listening(False)), (0.3, transcript("What time")),
        (2.5, listening(True)), (0.5, listening(False)), (0.3, transcript("Awesome")),
        (1.0, listening(True)), (0.4, listening(False)), (0.2, transcript("Bye")),
        (4.0, ev("call.ended", outcome="ENDED", duration_ms=12000)),
    ]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty for two, please."],
                                       "awesome": ["Great, goodbye."], "bye": ["Goodbye."]})
    check(rt.ended, "the call must be ended by the driver", log)
    check("bye" not in [a.lower() for a in asked], f"no reply generated after the hang-up was decided: {asked}", log)
    texts = played_texts(rt)
    check(texts.count("Great, goodbye.") == 1 and "Goodbye." not in texts, f"one goodbye only: {texts}", log)
    check("[closing: not answering]" in log, "should log the closing state", log)
    print("ok  awesome-then-bye    ", texts)


def scenario_tool_hangup():
    """The model calls hang_up (yielded as a HangUp after its sentences): ends after the goodbye is played."""
    script = [
        *HELLO,
        (1.5, listening(True)), (0.5, listening(False)), (0.3, transcript("What time")),
        (2.5, listening(True)), (0.5, listening(False)), (0.3, transcript("Perfect, see you then")),
        (5.0, ev("call.ended", outcome="ENDED", duration_ms=12000)),
    ]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty for two, please."],
                                       "perfect see you then": ["Thanks, bye.", converse.HangUp("booking confirmed")]})
    del asked
    check(rt.ended, "the call must be ended by the driver", log)
    check("[hanging up: booking confirmed]" in log, "should hang up for the tool's reason", log)
    texts = played_texts(rt)
    check(texts.count("Thanks, bye.") == 1, f"goodbye spoken once: {texts}", log)
    print("ok  tool-hangup         ", texts)


def scenario_farewell_in_question_no_hangup():
    """A reply that mentions goodbye but still asks something is not a farewell."""
    script = [
        *HELLO,
        (1.5, listening(True)), (0.5, listening(False)), (0.3, transcript("What time")),
        (2.5, listening(True)), (0.5, listening(False)), (0.3, transcript("Sounds good")),
        (4.0, ev("call.ended", outcome="ENDED", duration_ms=12000)),
    ]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty for two, please."],
                                       "sounds good": ["I'll say goodbye once it's confirmed, does seven twenty work?"]})
    del asked
    check(not rt.ended, "must not hang up on a question", log)
    check("[hanging up" not in log, "no hang-up line expected", log)
    print("ok  farewell-in-question", played_texts(rt))


def scenario_responder_failure():
    """The model fails on this turn (first-token timeout): say the fallback line, and keep the call going."""
    script = hello_then("What time") + [
        (2.5, listening(True)), (0.5, listening(False)), (0.3, transcript("Seven works")),
        (3.0, ev("call.ended", outcome="ENDED", duration_ms=9000)),
    ]
    rt, asked, log = simulate(script, {"what time": TimeoutError("no first token"),
                                       "seven works": ["Perfect."]})
    del asked
    texts = played_texts(rt)
    check(texts.count(converse.FALLBACK_LINE) == 1, f"fallback line spoken once: {texts}", log)
    check(texts.count("Perfect.") == 1, f"the next turn still works: {texts}", log)
    check("[responder error] TimeoutError" in log, "should log the responder error", log)
    print("ok  responder-failure   ", texts)


def scenario_fragment_merge():
    """A turn that closed mid-thought ("What about") is held and merged with its continuation."""
    script = [
        *HELLO,
        (1.5, listening(True)), (0.5, listening(False)), (0.2, transcript("What about")),
        (0.5, listening(True)), (0.6, listening(False)), (0.2, transcript("seven twenty three")),
        (3.0, ev("call.ended", outcome="ENDED", duration_ms=9000)),
    ]
    rt, asked, log = simulate(script, {"what about seven twenty three": ["Seven twenty-three is perfect."],
                                       "what about": ["WRONG."]})
    del asked
    texts = played_texts(rt)
    check("WRONG." not in texts, f"the fragment alone must not be answered: {texts}", log)
    check(texts.count("Seven twenty-three is perfect.") == 1, f"merged turn answered once: {texts}", log)
    check("[merged with the held fragment" in log, "should log the merge", log)
    print("ok  fragment-merge      ", texts)


def scenario_fragment_speculation_abandoned():
    """A speculation started on a held fragment's interim is abandoned when the continuation closes."""
    script = [
        *HELLO,
        (1.5, listening(True)), (0.3, transcript("is it for", final=False)),
        (0.4, listening(False)), (0.2, transcript("Is it for")),
        (0.3, listening(True)), (0.3, transcript("is it for two", final=False)),
        (0.4, listening(False)), (0.2, transcript("Is it for two")),
        (3.0, ev("call.ended", outcome="ENDED", duration_ms=9000)),
    ]
    rt, asked, log = simulate(script, {"is it for two": ["Yes, two people."], "is it for": ["WRONG."],
                                       "is it for is it for two": ["Yes, for two."]})
    texts = played_texts(rt)
    check("WRONG." not in texts, f"the fragment alone must not be answered: {texts}", log)
    check(("Yes, two people." in texts) or ("Yes, for two." in texts), f"the whole thought answered: {texts}", log)
    check(texts.count("Yes, two people.") + texts.count("Yes, for two.") == 1, f"answered once: {texts}", log)
    print("ok  fragment-speculation", texts, asked)


def scenario_fragment_released():
    """A held fragment with no continuation is answered after the hold expires."""
    script = [
        *HELLO,
        (1.5, listening(True)), (0.5, listening(False)), (0.2, transcript("Is it for")),
        (4.0, ev("call.ended", outcome="ENDED", duration_ms=9000)),
    ]
    rt, asked, log = simulate(script, {"is it for": ["It's for Alex, two people."]})
    del asked
    texts = played_texts(rt)
    check(texts.count("It's for Alex, two people.") == 1, f"released fragment answered once: {texts}", log)
    check("[held fragment released" in log, "should log the release", log)
    print("ok  fragment-released   ", texts)


# ---- Whisper on the LAN: the phone's transcript drives, Whisper confirms / corrects / withdraws.

def fake_whisper(results: dict, delay: float = 0.4, logprob: float = -0.3):
    """results: turn_id -> text (None = silence/noise). Installed as converse.whisper_transcribe."""
    calls: list = []

    def transcribe(server, wav, prompt="", timeout=20.0):
        del server, wav, prompt, timeout
        time.sleep(delay)
        tid = calls.append(None) or len(calls)  # nth call = nth closed turn in these scripts
        text = results.get(tid)
        if text is None:
            return {"text": "", "no_speech_prob": 1.0, "avg_logprob": -9.0}
        return {"text": text, "no_speech_prob": 0.01, "avg_logprob": logprob}
    return transcribe


def scenario_whisper_unsure_keeps_phone():
    """Whisper disagrees ('blocked' for 'booked') but at low confidence: the phone's transcript stands."""
    converse.whisper_transcribe = fake_whisper({1: "Hello", 2: "yep, it is blocked"}, logprob=-0.55)
    script = whisper_script(("Hello", 0.2), ("Yep it is booked", 1.5)) + [(3.5, ev("call.ended", outcome="ENDED", duration_ms=9000))]
    rt, asked, log = simulate(script, {"yep it is booked": ["Perfect, thank you."], "yep it is blocked": ["WRONG."]}, stt="whisper")
    texts = played_texts(rt)
    check("WRONG." not in texts and texts.count("Perfect, thank you.") == 1, f"phone transcript must stand: {texts}", log)
    check("not surely enough to correct" in log, "should log the gated disagreement", log)
    print("ok  whisper-unsure       ", texts)


def whisper_script(*turns):
    """Turns as (device_final, listening_gap) with turn_ids 1..n; the greeting is turn 1."""
    s = []
    tid = 0
    for text, gap in turns:
        tid += 1
        s += [(gap, listening(True, turn_id=tid)), (0.5, listening(False, endpoint="model", silence_ms=400, turn_id=tid, audio_ms=1500))]
        if text is not None:
            s.append((0.15, ev("call.transcript", text=text, final=True, turn_id=tid)))
    return s


def scenario_whisper_confirms():
    """Whisper agrees with the phone: the reply that already started is kept, once."""
    converse.whisper_transcribe = fake_whisper({1: "Hello", 2: "Does seven twenty work?"})
    script = whisper_script(("Hello", 0.2), ("does seven twenty work", 1.5)) + [(3.5, ev("call.ended", outcome="ENDED", duration_ms=9000))]
    rt, asked, log = simulate(script, {"does seven twenty work": ["Yes, seven twenty is perfect."]}, stt="whisper")
    texts = played_texts(rt)
    check(texts.count("Yes, seven twenty is perfect.") == 1, f"reply once: {texts}", log)
    check("[whisper confirmed turn 2]" in log, "should confirm", log)
    check(asked.count("does seven twenty work") == 1, f"model asked once: {asked}", log)
    print("ok  whisper-confirms    ", texts)


def scenario_whisper_corrects():
    """The phone heard 'twenty three PM' (dropped 'seven'); Whisper heard it whole: regenerate, once."""
    converse.whisper_transcribe = fake_whisper({1: "Hello", 2: "What about seven twenty three p.m.?"})
    script = whisper_script(("Hello", 0.2), ("twenty three PM", 1.5)) + [(4.0, ev("call.ended", outcome="ENDED", duration_ms=9000))]
    rt, asked, log = simulate(script, {"twenty three pm": ["WRONG."],
                                       "what about seven twenty three pm": ["Seven twenty-three is perfect."]}, stt="whisper")
    texts = played_texts(rt)
    check("WRONG." not in texts, f"the misheard reply must not play: {texts}", log)
    check(texts.count("Seven twenty-three is perfect.") == 1, f"corrected reply once: {texts}", log)
    check("[whisper corrected turn 2" in log, "should log the correction", log)
    print("ok  whisper-corrects    ", texts)


def scenario_whisper_withdraws_noise():
    """The phone heard 'Y' in line noise; Whisper heard nothing: no reply."""
    converse.whisper_transcribe = fake_whisper({1: "Hello", 2: None, 3: "What time?"})
    script = whisper_script(("Hello", 0.2), ("Y", 1.5), ("What time", 2.0)) + [(3.5, ev("call.ended", outcome="ENDED", duration_ms=9000))]
    rt, asked, log = simulate(script, {"y": ["WRONG."], "what time": ["Seven twenty for two, please."]}, stt="whisper")
    texts = played_texts(rt)
    check("WRONG." not in texts, f"noise must not be answered: {texts}", log)
    check("reply withdrawn]" in log, "should withdraw the reply", log)
    check(texts.count("Seven twenty for two, please.") == 1, f"the real turn is answered: {texts}", log)
    print("ok  whisper-withdraws   ", texts)


def scenario_whisper_first():
    """The phone never produced a final (a lost short turn); Whisper's transcript becomes the turn."""
    converse.whisper_transcribe = fake_whisper({1: "Hello", 2: "Okay, what time?"}, delay=0.2)
    script = whisper_script(("Hello", 0.2), (None, 1.5)) + [(3.5, ev("call.ended", outcome="ENDED", duration_ms=9000))]
    rt, asked, log = simulate(script, {"okay what time": ["Any time between six and eight."]}, stt="whisper")
    texts = played_texts(rt)
    check(texts.count("Any time between six and eight.") == 1, f"whisper-only turn answered once: {texts}", log)
    print("ok  whisper-first       ", texts)


def scenario_whisper_unavailable():
    """The Whisper box is down (Private-network firewall gotcha): the phone's transcript stands, no stall."""
    def down(server, wav, prompt="", timeout=20.0):
        del server, wav, prompt, timeout
        return None
    converse.whisper_transcribe = down
    script = whisper_script(("Hello", 0.2), ("What time", 1.5)) + [(3.5, ev("call.ended", outcome="ENDED", duration_ms=9000))]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty for two, please."]}, stt="whisper")
    texts = played_texts(rt)
    check(texts.count("Seven twenty for two, please.") == 1, f"device transcript still answered: {texts}", log)
    print("ok  whisper-unavailable ", texts)


def scenario_opening_not_truncated():
    """A greeting the responder streams as clauses must be spoken WHOLE, not cut at its first comma."""
    parts = ["Hi, this is Alex's assistant,", "I'd like to book a table for two tonight between six and eight."]
    script = [*HELLO, (1.5, listening(True)), (0.5, listening(False, endpoint="model", silence_ms=400)),
              (0.2, transcript("What time")), (3.0, ev("call.ended", outcome="ENDED", duration_ms=9000))]
    rt, asked, log = simulate(script, {"what time": ["Any time between six and eight."]}, opening=parts)
    del asked
    texts = played_texts(rt)
    whole = " ".join(parts)
    check(texts and texts[0] == whole, f"the opening must be spoken whole, got {texts[:1]}", log)
    check(not texts[0].rstrip().endswith(","), f"the opening must not end mid-sentence: {texts[0]!r}", log)
    print("ok  opening-whole       ", texts[:1])


def scenario_idle_nudge():
    """Silence after the conversation started: a nudge first, then the hang-up."""
    script = hello_then("What time") + [(7.0, ev("call.ended", outcome="ENDED", duration_ms=9000))]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty for two, please."]}, idle_nudge=2.0, idle_hangup=4.5)
    del asked
    texts = played_texts(rt)
    check(texts.count(converse.IDLE_NUDGE_LINE) == 1, f"nudged once: {texts}", log)
    check(rt.ended and "[hanging up:" in log and "of silence]" in log, "hung up on silence after the nudge", log)
    check(texts.index(converse.IDLE_NUDGE_LINE) > texts.index("Seven twenty for two, please."), "nudge after the reply", log)
    print("ok  idle-nudge          ", texts)


def scenario_outcome_parsing():
    """
    The structured result a calling agent reads. The model is mocked: what is under test is the parsing and
    the guarantees around it, not the brain's judgment (that is measured by hand in docs/brain-options.md).

    Every reply below is one a real model has actually produced: a fenced block, prose around the object, an
    outcome word that is not in the enum, a half-filled object, and a hard failure.
    """
    import json as _json
    brain = converse.Brain("http://127.0.0.1:1/v1", "mock", "none", "", None)
    hist = [{"role": "assistant", "text": "Is it ready?"}, {"role": "user", "text": "Yes, after four."}]

    def with_reply(text):
        class _R:
            def read(self):
                return _json.dumps({"choices": [{"message": {"content": text}}]}).encode()

            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False
        real = converse.urllib.request.urlopen
        converse.urllib.request.urlopen = lambda *a, **k: _R()
        try:
            return converse.summarize_outcome(brain, "Find out if it is ready.", hist)
        finally:
            converse.urllib.request.urlopen = real

    good = '{"outcome": "done", "summary": "It is ready after four.", "facts": {"ready": "after four"}, ' \
           '"we_promised": [], "they_promised": [], "follow_up": null}'
    o = with_reply(good)
    check(o and o["outcome"] == "done" and o["facts"] == {"ready": "after four"}, f"plain JSON: {o}", "")

    o = with_reply("```json\n" + good + "\n```")
    check(o and o["outcome"] == "done", f"fenced JSON: {o}", "")

    o = with_reply("Here is the result:\n" + good + "\nLet me know if you need more.")
    check(o and o["outcome"] == "done" and o["follow_up"] is None, f"JSON wrapped in prose: {o}", "")

    # An outcome word outside the enum must not reach the agent as if it were one.
    o = with_reply('{"outcome": "successful", "summary": "ok"}')
    check(o and o["outcome"] == "unclear", f"unknown outcome word: {o}", "")

    # Missing and wrong-typed fields are filled in, so the caller can index without checking.
    o = with_reply('{"outcome": "partial", "facts": "not a map"}')
    check(o and o["facts"] == {} and o["we_promised"] == [] and o["they_promised"] == []
          and o["follow_up"] is None and o["summary"] == "", f"half-filled object: {o}", "")

    # A model that ignored the split and sent the old single list: those are promises someone is waiting on,
    # so they must surface as OURS rather than being dropped on the floor.
    o = with_reply('{"outcome": "partial", "commitments": ["call her back about the location"]}')
    check(o and o["we_promised"] == ["call her back about the location"] and "commitments" not in o,
          f"a legacy commitments list must become we_promised: {o}", "")

    # Not JSON at all, and an unreachable host: None, never an exception, so the transcript still survives.
    check(with_reply("I could not summarise that.") is None, "non-JSON reply must give None", "")
    check(converse.summarize_outcome(brain, "task", hist, timeout=1.0) is None,
          "an unreachable summariser must give None, not raise", "")

    # A call where nobody was reached is answered without asking a model at all.
    o = converse.summarize_outcome(brain, "task", [])
    check(o and o["outcome"] == "no_answer", f"empty history: {o}", "")
    print("ok  outcome-parsing     ", "6 shapes + empty history")


def scenario_never_hang_up_in_silence():
    """
    The model ends the call with the hang_up TOOL and no words at all.

    Dry-running the real brain (`tools/dry_call.py`) showed this is what it actually does when the far party
    says "alright, have a good one": the tool call is the whole reply. Nobody may be hung up on in silence,
    so the driver speaks a goodbye itself - and it does it HERE, not in the responder, because this is the
    only place that knows the hang-up survived the guards (not in the first two turns, not unless their last
    turn was agreement). A goodbye spoken a layer higher would go into calls that then carry on.
    """
    script = [
        *HELLO,
        (1.5, listening(True)), (0.5, listening(False)), (0.3, transcript("What time")),
        (2.5, listening(True)), (0.5, listening(False)), (0.3, transcript("Perfect, see you then")),
        (5.0, ev("call.ended", outcome="ENDED", duration_ms=12000)),
    ]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty for two, please."],
                                       "perfect see you then": [converse.HangUp("booking confirmed")]})
    del asked
    texts = played_texts(rt)
    check(rt.ended and "[hanging up: booking confirmed]" in log, "the call must end", log)
    check(len(texts) >= 2 and texts[-1].strip(), f"something must be SAID before the line dies: {texts}", log)
    check(texts[-1] != "Seven twenty for two, please.", f"the goodbye must be its own turn: {texts}", log)
    print("ok  goodbye-before-hangup", texts[-1:])


def scenario_early_bare_hangup_stays_quiet():
    """
    The other half of it: a bare hang_up the driver REFUSES (too early in the call) must not make the driver
    say goodbye either. Silence is right here - the call is still going, and a farewell would end it.
    """
    script = [
        *HELLO,
        (1.5, listening(True)), (0.5, listening(False)), (0.3, transcript("What time")),
        (6.0, ev("call.ended", outcome="ENDED", duration_ms=9000)),
    ]
    rt, asked, log = simulate(script, {"what time": [converse.HangUp("jumped the gun")]})
    del asked
    texts = played_texts(rt)
    check("[hangup ignored: too early in the call]" in log, "the early hang-up must be refused", log)
    check(not any("goodbye" in t.lower() or "bye" in t.lower() for t in texts),
          f"no farewell may be spoken into a call that continues: {texts}", log)
    print("ok  early-hangup-quiet   ", texts)


def scenario_late_question_cancels_hangup():
    """
    Live call 6: the agent decided to close, and Taylor asked "um, where is it?" - twice. Both turns hit
    `[closing: not answering]` and she was hung up on mid-sentence, never learning where dinner was.

    A goodbye is exactly when someone remembers what they still needed to ask, so the close is cancellable:
    a QUESTION during the grace window takes the hang-up back and gets answered. Their farewell still does
    not (that is the goodbye loop this latch exists to stop, and it is checked in the scenario below).
    """
    script = [
        *HELLO,
        (1.5, listening(True)), (0.5, listening(False)), (0.3, transcript("What time")),
        (2.5, listening(True)), (0.5, listening(False)), (0.3, transcript("Perfect, see you then")),
        # Their question lands while the goodbye is still playing out.
        (1.2, listening(True)), (0.4, listening(False)), (0.2, transcript("Um where is it")),
        (8.0, ev("call.ended", outcome="ENDED", duration_ms=16000)),
    ]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty for two, please."],
                                       "perfect see you then": ["Thanks, bye.", converse.HangUp("confirmed")],
                                       "um where is it": ["It's on Maple Street."]})
    del asked
    texts = played_texts(rt)
    check("[closing cancelled:" in log, "a late question must call off the hang-up", log)
    check("It's on Maple Street." in texts, f"and must actually be answered: {texts}", log)
    check(texts.index("It's on Maple Street.") > texts.index("Thanks, bye."),
          f"the answer comes after the goodbye that was cancelled: {texts}", log)
    print("ok  late-question-answered", texts[-1:])


def scenario_farewell_does_not_cancel_hangup():
    """
    The other side of it: their "okay, bye bye" after our goodbye must still NOT reopen the call. That is
    the goodbye loop (call 2 said goodbye three times), and the cancel must not have reintroduced it.
    """
    script = [
        *HELLO,
        (1.5, listening(True)), (0.5, listening(False)), (0.3, transcript("What time")),
        (2.5, listening(True)), (0.5, listening(False)), (0.3, transcript("Perfect, see you then")),
        (1.2, listening(True)), (0.4, listening(False)), (0.2, transcript("Okay bye bye")),
        (8.0, ev("call.ended", outcome="ENDED", duration_ms=16000)),
    ]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty for two, please."],
                                       "perfect see you then": ["Thanks, bye.", converse.HangUp("confirmed")],
                                       "okay bye bye": ["Goodbye again."]})
    del asked
    texts = played_texts(rt)
    check("[closing: not answering]" in log, "their farewell must still be swallowed", log)
    check("Goodbye again." not in texts, f"no second goodbye: {texts}", log)
    check(rt.ended and "[hanging up:" in log, "and the call still ends", log)
    print("ok  farewell-no-reopen   ", texts)


def scenario_no_nudge_while_thinking():
    """
    Live call 6 asked the far party "are you still there?" one second after she had finished speaking.

    She was not silent - the reply was still inside the model. It had not reached the player, so `pending`
    was zero and every other idle check agreed this was silence. Waiting on a model is not silence, and the
    idle timers only mean anything once nothing is coming. `think` here is longer than `idle_nudge`, which
    is exactly the shape of that call.
    """
    script = [
        *HELLO,
        (1.5, listening(True)), (0.5, listening(False)), (0.3, transcript("It has been a busy week")),
        (9.0, ev("call.ended", outcome="ENDED", duration_ms=12000)),
    ]
    rt, asked, log = simulate(script, {"it has been a busy week": ["Oh, that sounds like a lot."]},
                              think=3.0, idle_nudge=1.0, idle_hangup=8.0)
    del asked
    texts = played_texts(rt)
    reply = "Oh, that sounds like a lot."
    check(reply in texts, f"the slow reply still arrives: {texts}", log)
    # A nudge AFTER the reply is correct - that is real silence. One before it means the driver mistook
    # "waiting on the model" for "the line has gone quiet", which is what happened on live call 6.
    if converse.IDLE_NUDGE_LINE in texts:
        check(texts.index(converse.IDLE_NUDGE_LINE) > texts.index(reply),
              f"no nudge may fire while the reply is still being written: {texts}", log)
    check("[nudging after" not in log.split(reply)[0] if reply in log else True,
          "the nudge must not be logged before the reply exists", log)
    print("ok  no-nudge-while-thinking", texts)


def scenario_afternoon_goodbye_hangs_up():
    """
    Live call 7, 1:48 pm: "That's all, thank you" -> "Alright, thanks Alex, have a good afternoon." -> silence ->
    "Are you still there?". The goodbye detector knew day/evening/night but not afternoon, so the call stayed
    open after the agent's own farewell. Replayed word for word: it must hang up, and never nudge.
    """
    script = [
        *HELLO,
        (1.5, listening(True)), (0.5, listening(False)), (0.3, transcript("I can hear you great")),
        (2.5, listening(True)), (0.5, listening(False)), (0.3, transcript("That's all thank you")),
        (12.0, ev("call.ended", outcome="ENDED", duration_ms=20000)),
    ]
    rt, asked, log = simulate(script, {"i can hear you great": ["Perfect, is there anything you need?"],
                                       "thats all thank you": ["Alright, thanks Alex, have a good afternoon."]},
                              idle_nudge=6.0, idle_hangup=20.0)
    del asked
    texts = played_texts(rt)
    check(rt.ended and "[hanging up:" in log, f"the afternoon goodbye must end the call: {log[-300:]}", log)
    check(converse.IDLE_NUDGE_LINE not in texts, f"no 'are you still there?' after our own goodbye: {texts}", log)
    print("ok  afternoon-goodbye    ", texts[-1:])


def scenario_goodbye_then_silence_hangs_up():
    """
    The fallback under the words: we said goodbye, the hang-up decision did NOT fire (their last turn was a
    one-word "Mm" the agreement check will not accept), and then the line went quiet. Silence after our own
    farewell is the call ending, not a lost connection - hang up after AFTER_GOODBYE_S, never nudge.
    """
    script = [
        *HELLO,
        (1.5, listening(True)), (0.5, listening(False)), (0.3, transcript("What time")),
        (2.5, listening(True)), (0.5, listening(False)), (0.3, transcript("Mm")),
        (14.0, ev("call.ended", outcome="ENDED", duration_ms=22000)),
    ]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty for two, please."],
                                       "mm": ["Thanks so much, have a good afternoon."]},
                              idle_nudge=6.0, idle_hangup=20.0)
    del asked
    texts = played_texts(rt)
    check("[hanging up: we said goodbye and they went quiet" in log,
          f"silence after our goodbye must hang up: {log[-400:]}", log)
    check(converse.IDLE_NUDGE_LINE not in texts, f"no nudge after our own goodbye: {texts}", log)
    print("ok  goodbye-then-silence ", texts[-1:])


class TwoBoxVoice(FakeVoice):
    """A main box that has stalled (the shared Joi box under the video pipeline) and a healthy helper."""

    def __init__(self, main_s=4.0, helper_s=0.3):
        super().__init__()
        self.helper = "http://helper"
        self.main_s, self.helper_s = main_s, helper_s
        self.boxes = []   # (text, box) in the order clips finished

    def warm_helper(self):
        pass   # the real one drops the helper for anything but chatterbox

    def synth_wav_b64(self, text):
        time.sleep(self.main_s)
        self.boxes.append((text, "main"))
        return text, len(text.split()) * 0.25, self.main_s

    def synth_helper_wav_b64(self, text):
        time.sleep(self.helper_s)
        self.boxes.append((text, "helper"))
        return text, len(text.split()) * 0.25, self.helper_s


def scenario_head_split_and_hedged():
    """
    A long first sentence is spoken as head + rest, and a head stuck on a stalled main box is re-asked of the
    helper after HEDGE_AFTER_S: first audio at ~2 s, not the main box's 4 s. The rest renders on the helper.
    """
    v = TwoBoxVoice()
    script = [*HELLO, (1.5, listening(True)), (0.5, listening(False)), (0.3, transcript("Can you hear me")),
              (14.0, ev("call.ended", outcome="ENDED", duration_ms=20000))]
    rt, asked, log = simulate(script, {"can you hear me": [
        "Great, thanks, Alex, this is your assistant calling from your OnePlus phone; is there anything you need?"]},
        voice=v, idle_nudge=0)
    del asked
    texts = played_texts(rt)
    check("Great, thanks, Alex," in texts, f"the head must play as its own clip: {texts}", log)
    check(any(t.startswith("this is your assistant") for t in texts), f"the rest must follow: {texts}", log)
    check("asking the other box too" in log, f"a stalled head must be hedged: {log[-400:]}", log)
    check(("Great, thanks, Alex,", "helper") in v.boxes, f"the helper must render the head: {v.boxes}", log)
    heads = [b for t, b in v.boxes if t == "Great, thanks, Alex,"]
    check(heads[:1] == ["helper"], f"the helper's copy of the head must land first: {v.boxes}", log)
    check(("this is your assistant calling from your OnePlus phone; is there anything you need?", "helper") in v.boxes,
          f"the rest must render on the helper: {v.boxes}", log)
    print("ok  head-split-hedged    ", heads)


def scenario_review_regexes():
    """Review 2026-09-24: closing declines anchored at both ends; no split inside a number or a time."""
    yes = ["No thanks", "No thank you", "No thank you so much", "No, that's all, thanks", "Nope, I'm good, have a good one",
           "No I'm good thanks bye", "Nah we're good", "No that's it", "No, that's everything. Thank you!"]
    no = ["No that's all wrong", "No, that's all wrong, I said seven", "No I'm good at eight but not seven",
          "Nah we're fine with the booth but not the patio", "No that's it but can you text me the address", "No",
          "No, seven doesn't work"]
    wrong = [t for t in yes if not converse.far_party_affirmative(t)] + [t for t in no if converse.far_party_affirmative(t)]
    check(not wrong, f"far_party_affirmative misread: {wrong}", "")
    for t in ["We have 7:30 or 8 open tonight for your party.", "That comes to 1,250 dollars for the whole group."]:
        check(converse.split_head(t) == (t, ""), f"split inside a number: {converse.split_head(t)}", "")
    check(converse.split_head("At 7:30, we can seat all six of you by the window.")[0] == "At 7:30,",
          "a real pause after a time", "")
    print("ok  review-regexes       ")


def scenario_goodbye_then_late_question():
    """
    Review 2026-09-24: we said goodbye, they started "Oh wait, what's the address" 0.8 s after it and talked past
    AFTER_GOODBYE_S. Their turn closed with the final still 0.4 s away - and the watchdog hung up on them.
    Quiet must count from their last sound, and a pending final is them still talking.
    """
    script = [*HELLO,
              (1.5, listening(True)), (0.5, listening(False)), (0.3, transcript("What time")),
              (2.5, listening(True)), (0.5, listening(False)), (0.3, transcript("Mm")),
              (2.9, listening(True)), (3.2, listening(False, endpoint="model", silence_ms=300)),
              (0.4, transcript("Oh wait what is the address of the restaurant")),
              (6.0, ev("call.ended", outcome="ENDED", duration_ms=22000))]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty for two, please."],
                                       "mm": ["Thanks so much, have a good afternoon."],
                                       "oh wait what is the address of the restaurant":
                                           ["It is at one two three Main Street."]},
                              idle_nudge=6.0, idle_hangup=20.0)
    del asked
    texts = played_texts(rt)
    check("[hanging up: we said goodbye" not in log.split("Oh wait")[0], f"hung up on a question: {log[-500:]}", log)
    check("It is at one two three Main Street." in texts, f"the late question must be answered: {texts}", log)
    print("ok  goodbye-late-question", texts[-1:])


def scenario_split_farewell_still_ends_call():
    """
    Review 2026-09-24: "Goodbye, Taylor, and thanks again ..." plays as head + tail; the goodbye check saw only the
    tail and the call nudged "are you still there?". The two clips of one reply are one history entry now.
    """
    script = [*HELLO,
              (1.5, listening(True)), (0.5, listening(False)), (0.3, transcript("Okay I think that covers it")),
              (14.0, ev("call.ended", outcome="ENDED", duration_ms=20000))]
    rt, asked, log = simulate(script, {"okay i think that covers it":
                                           ["Goodbye, Taylor, and thanks again for all your help today."]},
                              idle_nudge=6.0, idle_hangup=20.0)
    del asked
    texts = played_texts(rt)
    check("Goodbye, Taylor," in texts, f"the farewell should have been split: {texts}", log)
    check("[hanging up: we said goodbye" in log, f"a split farewell must still end the call: {log[-400:]}", log)
    check(converse.IDLE_NUDGE_LINE not in texts, f"no nudge after our own goodbye: {texts}", log)
    print("ok  split-farewell       ", texts[-2:])


def scenario_stale_head_does_not_block():
    """
    Review 2026-09-24: a preempted reply the final contradicted was still rendering on the main box, and the real
    reply's head queued behind it: first audio 6.05 s against 3.05 s. The next head goes to the idle box.
    """
    v = TwoBoxVoice(main_s=3.0, helper_s=2.5)
    script = [*HELLO, (1.5, listening(True)), (0.3, transcript("does seven", final=False)),
              (1.2, listening(False, endpoint="model", silence_ms=200)),
              (0.4, transcript("Does seven thirty work for four people tonight?")),
              (12.0, ev("call.ended", outcome="remote_hangup", duration_ms=9000))]
    rt, asked, log = simulate(script, {"does seven": ["WRONG, this is a long reply that should never be heard at all."],
                                       "does seven thirty work for four people tonight":
                                           ["Yes, seven thirty works, we will see you tonight then."]},
                              voice=v, think=0.05)
    del asked, rt
    m = re.search(r"first audio ([0-9.]+)s", log)
    check(m is not None and float(m.group(1)) < 4.0, f"a dead reply must not delay the live head: {m and m.group(0)}", log)
    print("ok  stale-head           ", m.group(0) if m else "")


def scenario_both_boxes_hang_bounded():
    """Review 2026-09-24: with both boxes hung the player waited ~135 s. Past HEAD_DEADLINE_S the phone speaks."""
    v = TwoBoxVoice(main_s=6.0, helper_s=6.0)
    saved = converse.HEAD_DEADLINE_S
    converse.HEAD_DEADLINE_S = 2.5
    try:
        script = [*HELLO, (1.5, listening(True)), (0.5, listening(False)), (0.3, transcript("Can you hear me")),
                  (9.0, ev("call.ended", outcome="ENDED", duration_ms=15000))]
        rt, asked, log = simulate(script, {"can you hear me": ["Yes, I can hear you, is there anything you need today?"]},
                                  voice=v, think=0.05, idle_nudge=0)
    finally:
        converse.HEAD_DEADLINE_S = saved
    del asked
    check("no head clip in" in log, f"the hung head must give up at the deadline: {log[-400:]}", log)
    check("Yes, I can hear you," in played_texts(rt), f"the line must still be said: {played_texts(rt)}", log)
    print("ok  both-boxes-hang      ")


# ---- Review 2026-09-26: the driver has to survive a phone that misbehaves, and never strand a live call.


def ticks(seconds: float, every: float = 0.5) -> list:
    """Harmless events, so the event loop wakes the way EVENT_POLL_S wakes it on a real, quiet socket."""
    return [(every, ev("call.audio_state", route="earpiece"))] * int(seconds / every)


ENDED = ev("call.ended", outcome="ENDED", duration_ms=9000)


def scenario_play_error_recovers():
    """
    An OSError out of play_audio killed the player thread: `speaking` stuck True, `pending` only grew, and the
    idle watchdog skipped forever - a live, silent call. One failed clip must cost only that clip.
    """
    def setup(rt, shared):
        del shared
        rt.faults["telephony.play_audio"] = [None, OSError("connection reset by peer")]   # opening ok, then a blip
    script = hello_then("What time") + [(3.0, listening(True)), (0.5, listening(False)), (0.3, transcript("Seven works")),
                                        (7.0, ENDED)]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty tonight.", "Does that work?"], "seven works": ["Perfect."]},
                              setup=setup, idle_nudge=2.5, idle_hangup=30)
    del asked
    texts = played_texts(rt)
    check("[agent] could not play (OSError" in log, "the failure should be logged", log)
    check("Seven twenty tonight." not in texts and "Does that work?" in texts, f"the next clip still plays: {texts}", log)
    check("Perfect." in texts, f"the next turn is answered: {texts}", log)
    check(converse.IDLE_NUDGE_LINE in texts and texts.index(converse.IDLE_NUDGE_LINE) > texts.index("Perfect."),
          f"silence handling must survive the failure: {texts}", log)
    check(not rt.ended, "one failed clip is not a reason to hang up", log)
    print("ok  play-error-recovers  ", texts)


def scenario_play_failures_end_call():
    """The phone stops taking audio altogether: after PLAY_FAIL_LIMIT failures in a row, end the call."""
    def setup(rt, shared):
        del shared
        rt.faults_forever["telephony.play_audio"] = lambda: TimeoutError("read timed out")
    script = hello_then("What time") + ticks(6.0) + [(0.5, ENDED)]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty tonight.", "For two people.", "Does that work?"]},
                              setup=setup)
    del asked
    check(not rt.played, f"nothing could play: {rt.played}", log)
    check(rt.ended and "[hanging up: the phone stopped taking our audio]" in log, "a call we cannot speak on must end", log)
    print("ok  play-failures-end    ")


def scenario_hangup_retried():
    """end_call raising, then refusing, used to be recorded as a hang-up. Retry until the phone says ended."""
    def setup(rt, shared):
        del shared
        rt.faults["telephony.end_call"] = [OSError("connection reset"), {"error": {"code": "capability.failed"}}]
    script = hello_then("What time") + ticks(8.0) + [(0.5, ENDED)]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty for two, please."]}, idle_nudge=0, idle_hangup=2.0,
                              setup=setup)
    del asked
    check(rt.ended and rt.end_calls == 3, f"three attempts, the third lands: {rt.end_calls}", log)
    check(log.count("end_call failed") == 2, "each failure is logged", log)
    print("ok  hangup-retried       ", rt.end_calls)


def scenario_hangup_failure_not_success():
    """A hang-up that never goes through is NOT an ended call: bounded retries, then exit non-zero."""
    def setup(rt, shared):
        del shared
        rt.faults_forever["telephony.end_call"] = lambda: {"error": {"code": "capability.unavailable"}}
    script = hello_then("What time") + ticks(40.0) + [(0.5, ENDED)]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty for two, please."]}, idle_nudge=0, idle_hangup=2.0,
                              setup=setup)
    del asked
    check(not rt.ended, "the fake phone never ended it", log)
    check(rt.end_calls >= 3 * converse.HANGUP_ATTEMPTS, f"the watchdog kept trying, boundedly: {rt.end_calls}", log)
    check("could not hang up the silent call; giving up" in log, "the watchdog must give up eventually", log)
    check("[sim exit: [converse] could not hang up the call]" in log, "and the process must exit non-zero", log)
    print("ok  hangup-not-success   ", rt.end_calls)


def scenario_active_during_dial_not_missed():
    """
    The far party answers while place_call is still returning. The phone only pushes to subscriptions that
    already exist, so a driver that subscribed after dialing never saw ACTIVE: no connected_at, no opening
    fallback, no idle watchdog. The event channel is opened before dialing now.
    """
    def setup(rt, shared):
        def on_place():
            if shared["connects"]:   # a socket exists: the phone pushes the event to it
                shared.setdefault("pushed", []).append(ev("call.precise_state", state="ACTIVE"))
        rt.on_place = on_place
    script = ticks(11.0) + [(0.1, ENDED)]
    rt, asked, log = simulate(script, {}, answered=False, setup=setup)
    del asked
    texts = played_texts(rt)
    check("[call] answered" in log, "ACTIVE during dialing must not be missed", log)
    check("complete silence for 7s after connect" in log and texts and texts[0].startswith("Hi, this is Alex"),
          f"the silent-pickup opening must run: {texts}", log)
    print("ok  active-during-dial   ", texts[:1])


def scenario_ws_blip_reconnects():
    """
    A Wi-Fi blip on the event socket ended the call. Reconnect (the phone refuses twice first), replay what was
    missed - here, their whole next turn - and carry on. A live duplicate of a replayed event is dropped by id.
    """
    missed = [dict(listening(True), id=101), dict(listening(False, endpoint="model", silence_ms=400), id=102),
              dict(transcript("What time"), id=103)]
    script = [*HELLO, (1.5, ("drop", missed)), (0.2, dict(transcript("What time"), id=103)),
              (4.0, ENDED)]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty for two, please."]}, refuse_connects=2)
    texts = played_texts(rt)
    check("event socket back after" in log and "3 missed event(s) replayed" in log, "should reconnect and replay", log)
    check(rt.shared["connects"] == 4 and rt.shared.get("replays") == [100],
          f"two refusals, then one replay from the id the phone said hello with: {rt.shared}", log)
    check(texts.count("Seven twenty for two, please.") == 1, f"the missed turn is answered, once: {texts}", log)
    check(asked.count("What time") == 1 and "[duplicate transcript ignored]" not in log,
          f"the duplicate is dropped by event id, before the handler: {asked}", log)
    check(not rt.ended, "a blip is not a reason to hang up", log)
    print("ok  ws-blip-reconnects   ", texts)


def scenario_ws_down_hangs_up():
    """The phone stays unreachable past RECONNECT_FOR_S: now hang up (a call nobody can hear is worse than none)."""
    saved = converse.RECONNECT_FOR_S
    converse.RECONNECT_FOR_S = 2.0
    try:
        script = [*HELLO, (0.5, ("drop", []))]
        rt, asked, log = simulate(script, {}, refuse_connects=1000)
    finally:
        converse.RECONNECT_FOR_S = saved
    del asked
    check("socket closed: event socket down" in log, "reconnecting must give up", log)
    check(rt.ended and "leaving with the call still up: hanging up" in log, "and then hang up", log)
    print("ok  ws-down-hangs-up     ")


def scenario_cached_line_skips_hung_box():
    """
    The fallback line is rendered at warm-up. It used to be re-submitted to the main box's single worker anyway,
    so a hung main box delayed it by as long as the hung request took. A cached line never touches a box.
    """
    v = TwoBoxVoice(main_s=3.0, helper_s=3.0)
    v._cache[converse.speakable(converse.FALLBACK_LINE).lower()] = (converse.FALLBACK_LINE, 1.0)
    script = hello_then("What time") + [(5.0, ENDED)]
    rt, asked, log = simulate(script, {"what time": TimeoutError("no first token")}, voice=v, idle_nudge=0)
    del asked
    texts = played_texts(rt)
    check(converse.FALLBACK_LINE in texts, f"the fallback plays: {texts}", log)
    check(not any(t == converse.FALLBACK_LINE for t, _ in v.boxes), f"a cached line must not be rendered again: {v.boxes}", log)
    print("ok  cached-line          ", texts[-1:])


def scenario_slow_model_deadline():
    """A model that takes too long for its first sentence: the fallback line, not dead air; the late reply is dropped."""
    saved = converse.FIRST_SENTENCE_DEADLINE_S
    converse.FIRST_SENTENCE_DEADLINE_S = 2.0
    try:
        script = hello_then("What time") + [(6.0, listening(True)), (0.5, listening(False)), (0.3, transcript("Seven works")),
                                            (3.0, ENDED)]
        rt, asked, log = simulate(script, {"what time": {"think": 5.0, "say": ["TOO LATE."]}, "seven works": ["Perfect."]},
                                  backchannel=False)
    finally:
        converse.FIRST_SENTENCE_DEADLINE_S = saved
    del asked
    texts = played_texts(rt)
    check("[responder error] TimeoutError" in log and converse.FALLBACK_LINE in texts, f"fallback after the deadline: {texts}", log)
    check("TOO LATE." not in texts, f"the late reply must not play: {texts}", log)
    check("Perfect." in texts, f"the next turn still works: {texts}", log)
    print("ok  slow-model-deadline  ", texts)


def scenario_slow_model_watchdog_cap():
    """
    Waiting on the model is not silence - but only for SLOW_REPLY_S. After that the watchdog acts again, with
    a holding line rather than "are you still there?" (they are waiting on us).
    """
    saved = converse.FIRST_SENTENCE_DEADLINE_S, converse.SLOW_REPLY_S
    converse.FIRST_SENTENCE_DEADLINE_S, converse.SLOW_REPLY_S = 30.0, 2.0
    try:
        script = hello_then("It has been a busy week") + [(9.0, ENDED)]
        rt, asked, log = simulate(script, {"it has been a busy week": {"think": 5.0, "say": ["Oh, that sounds like a lot."]}},
                                  idle_nudge=1.0, idle_hangup=20.0, backchannel=False)
    finally:
        converse.FIRST_SENTENCE_DEADLINE_S, converse.SLOW_REPLY_S = saved
    del asked
    texts = played_texts(rt)
    reply = "Oh, that sounds like a lot."
    check("[still writing a reply after" in log, "the exemption must lapse", log)
    check("One moment." in texts and reply in texts and texts.index("One moment.") < texts.index(reply),
          f"a holding line before the late reply: {texts}", log)
    check(converse.IDLE_NUDGE_LINE not in texts[:texts.index(reply)], f"never 'are you still there?' while we think: {texts}", log)
    print("ok  slow-model-cap       ", texts)


def scenario_phone_conversation_error():
    """The phone's listening loop died (call.conversation stopped/error): no transcripts will come - hang up."""
    script = hello_then("What time") + [
        (2.5, ev("call.conversation", state="stopped", reason="error", error="AudioRecord died")),
        *ticks(3.0), (0.5, ENDED)]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty for two, please."]})
    del asked
    check("Seven twenty for two, please." in played_texts(rt), "the reply in flight still plays", log)
    check(rt.ended and "[hanging up: the phone stopped listening]" in log, "the call must be ended", log)
    check("[call ended: ENDED" in log, "and still reported the usual way", log)

    # driver_lost: the phone ended the call itself; log it, do not hang up again.
    script = hello_then("What time") + [
        (2.5, ev("call.conversation", state="stopped", reason="driver_lost", idle_ms=30000)), (0.5, ENDED)]
    rt, asked, log = simulate(script, {"what time": ["Seven twenty for two, please."]})
    del asked
    check("stopped the conversation: driver_lost" in log and rt.end_calls == 0, "driver_lost is only logged", log)
    print("ok  phone-conv-error     ")


def scenario_signal_hangs_up():
    """SIGTERM / SIGBREAK (the gateway's Ctrl-Break) used to kill the driver without its hang-up. Now they unwind."""
    import signal
    names = [n for n in ("SIGTERM", "SIGBREAK") if getattr(signal, n, None) is not None]
    saved = {n: signal.getsignal(getattr(signal, n)) for n in names}
    try:
        for n in names:
            sig = getattr(signal, n)
            converse.install_stop_signals()
            script = hello_then("What time") + [(1.0, lambda sig=sig: signal.raise_signal(sig))] + ticks(3.0) + [(0.5, ENDED)]
            rt, asked, log = simulate(script, {"what time": ["Seven twenty for two, please."]})
            del asked
            check(rt.ended and "leaving with the call still up: hanging up" in log, f"{n} must hang up", log)
            check(f"[sim exit: {128 + int(sig)}]" in log, f"{n} must exit non-zero", log)
    finally:
        for n, h in saved.items():
            signal.signal(getattr(signal, n), h)
    print("ok  signal-hangs-up      ", names)


def scenario_start_refused_hangs_up():
    """Every error after dialing - a refused converse start, an OSError from it - must still hang up."""
    for reply in ({"error": {"code": "session.busy"}}, OSError("connection reset")):
        def setup(rt, shared, reply=reply):
            del shared
            rt.converse_reply = reply
        rt, asked, log = simulate(ticks(1.0), {}, setup=setup)
        del asked
        check("[call] dialing +15550000000 -> s1\n" in log, "the gateway's dialing line must be unchanged", log)
        check(rt.ended and "leaving with the call still up: hanging up" in log, f"{reply!r}: the call must be hung up", log)
    print("ok  start-refused-hangs-up")


def scenario_singleton_refuses_live_driver():
    """A second driver for the same phone refuses to start (it used to force-kill the first, skipping its hang-up)."""
    import os
    import subprocess
    base = "http://sim-singleton.invalid"
    path = converse.lock_path(base)
    holder = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)", "converse-holder"])
    try:
        with open(path, "w") as f:
            f.write(str(holder.pid))
        out, code = io.StringIO(), None
        with contextlib.redirect_stdout(out):
            try:
                converse.claim_singleton(base)
            except SystemExit as e:
                code = e.code
        check(code == 3 and "refusing to start" in out.getvalue(), f"must refuse, non-zero: {code} {out.getvalue()}", "")
        check(holder.poll() is None, "the live driver must not be killed", "")
        with open(path) as f:
            check(f.read().strip() == str(holder.pid), "its lock must be left alone", "")
    finally:
        holder.kill()
        holder.wait()
    with contextlib.redirect_stdout(io.StringIO()):
        converse.claim_singleton(base)     # the pid is dead now: a stale lock is reclaimed
    with open(path) as f:
        check(f.read().strip() == str(os.getpid()), "a stale lock must be reclaimed", "")
    os.remove(path)
    print("ok  singleton-refuses    ")


def scenario_system_file():
    """--system-file reads the brief from a file (kept off the command line); --system still works."""
    import os
    import signal
    import tempfile
    captured: dict = {}
    saved = converse.run, converse.claim_singleton, list(sys.argv)
    saved_sigs = {n: signal.getsignal(getattr(signal, n)) for n in ("SIGTERM", "SIGBREAK") if hasattr(signal, n)}
    converse.run = lambda *a, **k: captured.update(task=k.get("task"))   # type: ignore[assignment]
    converse.claim_singleton = lambda base: None                         # type: ignore[assignment]
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as f:
        f.write("  Call Giovanni's and book a table for two at seven.\n")
    empty = f.name + ".empty"
    open(empty, "w").close()
    common = ["converse.py", "--token", "t", "--dial", "+15550000000", "--responder", "scripted"]
    try:
        sys.argv = common + ["--system-file", f.name]
        converse.main()
        check(captured.get("task") == "Call Giovanni's and book a table for two at seven.", f"brief from file: {captured}", "")
        sys.argv = common + ["--system", "Ask when they close."]
        converse.main()
        check(captured.get("task") == "Ask when they close.", f"--system still works: {captured}", "")
        for argv, why in ((["--system", "x", "--system-file", f.name], "both at once"), (["--system-file", empty], "empty"),
                          (["--system-file", f.name + ".missing"], "missing")):
            sys.argv = common + argv
            code = None
            with contextlib.redirect_stderr(io.StringIO()):
                try:
                    converse.main()
                except SystemExit as e:
                    code = e.code
            check(code not in (None, 0), f"{why} must be refused: {code}", "")
    finally:
        converse.run, converse.claim_singleton, sys.argv = saved   # type: ignore[assignment]
        for n, h in saved_sigs.items():
            signal.signal(getattr(signal, n), h)
        os.remove(f.name)
        os.remove(empty)
    print("ok  system-file          ")


SCENARIOS = (
    scenario_preempt_match, scenario_preempt_mismatch, scenario_filler_when_slow,
    scenario_false_interruption, scenario_backchannel_interruption, scenario_uhhuh_mid_reply_ignored,
    scenario_yes_over_tail, scenario_real_interruption, scenario_speaks_between_sentences,
    scenario_spoken_farewell_hangs_up, scenario_awesome_then_bye, scenario_tool_hangup,
    scenario_farewell_in_question_no_hangup, scenario_responder_failure,
    scenario_fragment_merge, scenario_fragment_speculation_abandoned, scenario_fragment_released,
    scenario_whisper_confirms, scenario_whisper_corrects, scenario_whisper_withdraws_noise,
    scenario_whisper_first, scenario_whisper_unavailable, scenario_whisper_unsure_keeps_phone,
    scenario_opening_not_truncated,
    scenario_idle_nudge, scenario_outcome_parsing,
    scenario_never_hang_up_in_silence, scenario_early_bare_hangup_stays_quiet,
    scenario_late_question_cancels_hangup, scenario_farewell_does_not_cancel_hangup,
    scenario_no_nudge_while_thinking, scenario_afternoon_goodbye_hangs_up,
    scenario_goodbye_then_silence_hangs_up, scenario_head_split_and_hedged,
    scenario_review_regexes, scenario_goodbye_then_late_question, scenario_split_farewell_still_ends_call,
    scenario_stale_head_does_not_block, scenario_both_boxes_hang_bounded,
    scenario_play_error_recovers, scenario_play_failures_end_call, scenario_hangup_retried,
    scenario_hangup_failure_not_success, scenario_active_during_dial_not_missed, scenario_ws_blip_reconnects,
    scenario_ws_down_hangs_up, scenario_cached_line_skips_hung_box, scenario_slow_model_deadline,
    scenario_slow_model_watchdog_cap, scenario_signal_hangs_up, scenario_start_refused_hangs_up,
    scenario_singleton_refuses_live_driver, scenario_system_file, scenario_phone_conversation_error,
)


if __name__ == "__main__":
    only = sys.argv[1:]
    for s in SCENARIOS:
        if only and not any(o in s.__name__ for o in only):
            continue
        s()
    print("all scenarios passed")
