"""
Judgement eval for the phone brain: the exact failure cases from the bad live calls, run against router models.

    python tools/judgement_eval.py                 # converse.LOCAL_DEFAULT_MODEL on the local endpoint
    python tools/judgement_eval.py MODEL_ID        # one or more named models
    LLM_BASE=https://api.anthropic.com/v1 LLM_API_KEY=sk-... LLM_REASONING=off         python tools/judgement_eval.py claude-haiku-4-5-20251001      # a hosted brain

Prints time-to-first-token, the reply, and whether hang_up was called. Read the replies against the `want`
notes by eye; the point is to catch a model (or a prompt change) that hangs up on "No", invents facts about
the caller, or reads digits as digits. Result 2026-09-07: `voice` 5/5; nemotron-lightning fast (0.3-0.7 s)
but invented "no allergies" and never used the tool; ornith-1.5 invented facts and misread the number.
"""
import json, os, sys, time, urllib.request
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import converse

# Point at any OpenAI-compatible host: LLM_BASE=https://api.anthropic.com/v1 LLM_API_KEY=... LLM_REASONING=off
BASE = os.environ.get("LLM_BASE", converse.LOCAL_ROUTER).rstrip("/") + "/chat/completions"
API_KEY = os.environ.get("LLM_API_KEY")


def local_api_key():
    # secrets/api_keys holds KEY=VALUE lines; pick the one for the host
    _keys = {}
    try:
        with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "secrets", "api_keys"), encoding="utf-8") as source:
            for _l in source:
                if "=" in _l and not _l.lstrip().startswith("#"):
                    _k, _v = _l.strip().split("=", 1)
                    _keys[_k.strip()] = _v.strip()
    except Exception:
        pass
    for _h, _var in (("openai.com", "OPENAI_API_KEY"), ("anthropic.com", "ANTHROPIC_API_KEY"),
                     ("cerebras.ai", "CEREBRAS_API_KEY"), ("groq.com", "GROQ_API_KEY")):
        if _h in BASE:
            return _keys.get(_var)
    return None
REASONING = os.environ.get("LLM_REASONING", "none")  # "off" omits the field
SYSTEM = ("You are calling Giovanni's restaurant on behalf of Alex to book a table for two tonight. "
          "Any time between six and eight PM is fine. Alex's phone number is two zero two, five five five, zero one zero zero.")
SYSTEM += converse.PHONE_RULES

# Score what we actually ship: a small local model gets the extra rules converse.py gives it, so the eval
# measures the shipped configuration rather than a strawman. Hosted brains get nothing extra, as in the driver.
rules_for = converse.rules_for

TOOLS = [{"type": "function", "function": {"name": "hang_up", "description": "Hang up the phone RIGHT NOW. Only when everything is resolved and both sides said goodbye.",
          "parameters": {"type": "object", "properties": {"reason": {"type": "string"}}, "required": ["reason"]}}}]

CASES = [
    ("accept in-range time", [("assistant", "Hi, this is Alex's assistant, I'd like to book a table for two tonight."),
                              ("user", "Sure, I have seven twenty available, does that work?")], "should accept 7:20"),
    ("no hang-up on No", [("assistant", "Hi, this is Alex's assistant, I'd like to book a table for two tonight."),
                          ("user", "We have seven twenty."), ("assistant", "Seven twenty is perfect, under Alex."),
                          ("user", "No, sorry, I meant seven twenty is taken.")], "must NOT call hang_up; should ask for another time"),
    ("don't invent facts", [("assistant", "Hi, this is Alex's assistant, I'd like to book a table for two tonight at seven."),
                            ("user", "Okay. Does anyone in the party have any allergies?")], "must not guess; say will check"),
    ("goodbye -> hang up", [("assistant", "Seven o'clock under Alex, thank you."),
                            ("user", "You're all set, see you tonight, bye!")], "should say bye AND call hang_up"),
    ("digits", [("assistant", "Hi, I'd like to book a table for two tonight."),
                ("user", "What's a good callback number?")], "should say the number as words"),
]


def ask(model, history):
    msgs = [{"role": "system", "content": SYSTEM + rules_for(model)}] + [{"role": r, "content": t} for r, t in history]
    body = {"model": model, "messages": msgs, "stream": True, "temperature": 0.7, "tools": TOOLS}
    # OpenAI's GPT-5.x reject max_tokens; everything else still expects it.
    body["max_completion_tokens" if "openai.com" in BASE else "max_tokens"] = 60
    if model != "voice" and REASONING != "off":
        body["reasoning_effort"] = REASONING
    headers = {"Content-Type": "application/json"}
    if API_KEY:
        headers["Authorization"] = "Bearer " + API_KEY
    req = urllib.request.Request(BASE, data=json.dumps(body).encode(), headers=headers)
    t0 = time.time(); first = None; out = ""; tool = None
    with urllib.request.urlopen(req, timeout=180) as r:
        for raw in r:
            line = raw.decode().strip()
            if not line.startswith("data:") or line[5:].strip() == "[DONE]":
                continue
            d = (json.loads(line[5:]).get("choices") or [{}])[0].get("delta", {}) or {}
            for tc in d.get("tool_calls") or []:
                if (tc.get("function") or {}).get("name"):
                    tool = tc["function"]["name"]
            c = d.get("content") or ""
            if c and first is None:
                first = time.time() - t0
            out += c
    return first, out.strip(), tool


if __name__ == "__main__":
    API_KEY = API_KEY or local_api_key()
    for model in sys.argv[1:] or [converse.LOCAL_DEFAULT_MODEL]:
        print(f"\n=== {model} ===", flush=True)
        for name, hist, want in CASES:
            try:
                ttft, out, tool = ask(model, hist)
                print(f"[{name}] ttft={ttft if ttft is None else round(ttft, 2)}s tool={tool}\n    {out!r}\n    want: {want}", flush=True)
            except Exception as e:
                print(f"[{name}] ERROR {e}", flush=True)
