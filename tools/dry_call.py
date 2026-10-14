#!/usr/bin/env python3
"""
Hold a call with nobody: the real brain and the real rules against a scripted far party.

`test_converse_sim.py` tests the *driver* (turn-taking, barge-in, hang-up guards) with a fake responder, and
a live call tests everything at once at the cost of ringing a real phone. Neither one answers the question
"does the prompt still make it sound like a person?" — that needs the real model and no phone at all, which
is what this is. No audio, no MCP, no dialing: scripted far-party turns in, spoken replies out.

    python tools/dry_call.py                  # every script, on the default brain
    python tools/dry_call.py personal         # one script
    python tools/dry_call.py all ornith       # on a different brain

It flags a reply that runs to more than one sentence, and prints whether the call ever actually hung up.
Run it after ANY change to PHONE_RULES; a rule that reads well often does nothing, and the only way to know
which is to watch a call happen. Each script breaks in a different place:

  business      a hold and a spelling      -> go quiet on hold, spell an unusual name unasked
  personal      a call that wanders        -> greet first, small talk, take a message, close slowly
  snags         menu, wrong dept, transfer -> re-introduce yourself to whoever picks up next
  mismatch      they book the wrong day    -> the readback must CATCH it, not recite it
  missing-fact  the brief is incomplete     -> admit it, do not dodge or bounce the question back
  wrong-person  a stranger answers         -> confirm who it is before disclosing anything
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import converse as C  # noqa: E402

SECRETS = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "secrets")

# What the driver sends on the first turn, so the model writes its own opening line.
OPENER = "(The call has just been answered. Say your opening line - one sentence.)"


def api_key_for(base: str):
    for host, var in (("openai.com", "OPENAI_API_KEY"), ("anthropic.com", "ANTHROPIC_API_KEY"),
                      ("cerebras.ai", "CEREBRAS_API_KEY"), ("groq.com", "GROQ_API_KEY")):
        if host not in base:
            continue
        try:
            for line in open(os.path.join(SECRETS, "api_keys")):
                if "=" in line and not line.lstrip().startswith("#"):
                    k, v = line.strip().split("=", 1)
                    if k.strip() == var:
                        return v.strip()
        except Exception:
            return None
    return None


def drive(label: str, task: str, far_turns: list, brain_name: str = "luna") -> None:
    base, model, reasoning = C.BRAINS[brain_name]
    # "off" means omit reasoning_effort; Brain wants None for that (converse.py maps it the same way).
    brain = C.Brain(base, model, None if reasoning == "off" else reasoning, C.rules_for(model),
                    api_key_for(base), trust_tools=not C.is_small_model(model))
    respond = C._local_responder(task + C.PHONE_RULES, brain, None, 8.0)
    history: list = []
    hung_up = False
    print(f"\n{'=' * 78}\n{label}   [{brain}]\n{'=' * 78}")
    for i, them in enumerate(far_turns):
        prompt = OPENER if i == 0 else them
        if i:
            print(f"  THEM : {them}")
        t0 = time.time()
        out = respond(prompt, history)
        said = []
        if isinstance(out, str):
            said = [out]
        elif out is not None:
            for chunk in out:
                if isinstance(chunk, str):
                    said.append(chunk)
                else:
                    hung_up = True
        line = " ".join(said).strip()
        # A turn is meant to be ONE sentence. Semicolons count: they are the usual way two get smuggled in.
        sentences = sum(line.count(c) for c in ".!?;")
        note = "   <-- MORE THAN ONE SENTENCE" if sentences > 1 else ""
        print(f"  US   : {line}   ({time.time() - t0:.1f}s{', HANG UP' if hung_up else ''}){note}")
        history.append({"role": "user", "text": prompt})
        history.append({"role": "assistant", "text": line})
        if hung_up:
            return
    print("  (the call never hung up)")


SCRIPTS = {
    "business": (
        "A business call, with a hold and a spelling",
        "You are Alex Morgan's assistant. Call the pharmacy and find out whether his prescription is ready "
        "for pickup and what time the counter closes today. His callback number is four six nine, five two "
        "five, three five three nine. Do not give out his date of birth.",
        ["Thanks for calling Maple Street Pharmacy, this is Dana.",
         "Sure, can I get the patient's name?",
         "And can you spell the last name for me?",
         "Okay, let me pull that up, can you hold for just a second?",
         "Thanks for holding. Yeah, that one's filled and ready to go.",
         "The counter closes at eight tonight, the store's open till nine.",
         "Is there anything else I can help you with?",
         "Alright, have a good one.",
         "Bye now."],
    ),
    "personal": (
        "A personal call that wanders - a friend",
        "You are Alex Morgan's assistant. Call his friend Taylor and ask whether she is still able to come "
        "to dinner on Saturday at six, and whether she wants Alex to pick her up. Alex is fine either way.",
        ["Hello?",
         "Oh! Well hi. Is everything okay?",
         "I'm doing alright, my knee's been acting up but you know how it is. How's he doing?",
         "Oh good, good. Saturday, yes, I've got it on the calendar.",
         "You know what, if he doesn't mind driving, that'd be easier on me.",
         "That's perfect. Oh, tell him I said the casserole dish is still at his place.",
         "Alright honey, well, I'll let you go.",
         "Okay, bye bye."],
    ),
    "snags": (
        "A menu, the wrong department, and a transfer",
        "You are Alex Morgan's assistant. Call the dentist and reschedule his Tuesday cleaning to any "
        "morning next week. His account number is one one four two.",
        ["(recorded menu) Thank you for calling. For hours and location press one. To reach the front desk "
         "press two.",
         "Front desk, this is Kim.",
         "Oh, you'll want billing for that, let me transfer you.",
         "Billing, this is Marcus.",
         "Sure, I can move that. We've got Wednesday at nine or Thursday at ten thirty.",
         "Done, you're on for Wednesday the twelfth at nine.",
         "You too, bye."],
    ),
    "mismatch": (
        "They book something different from what you asked for",
        "You are Alex Morgan's assistant. Call the dentist and reschedule his Tuesday cleaning to "
        "THURSDAY morning next week. His account number is one one four two.",
        ["Northside Dental, this is Kim.",
         "Sure, I can move that. What day were you wanting?",
         "Let me see... okay, done. You're all set for Wednesday the twelfth at nine.",
         "Oh! Let me look again. No, you're right, I've got Thursday the thirteenth at ten. Sorry about that.",
         "You too, bye."],
    ),
    "missing-fact": (
        "The brief left out something the business will certainly ask for",
        "Book Alex a table at Giovanni's tomorrow at seven. His name is Alex Morgan and his callback "
        "number is two zero two, five five five, zero one zero zero.",
        ["Giovanni's, this is Maria.",
         "Sure, how many people?",
         "No problem at all, just let us know.",
         "You too, bye."],
    ),
    "wrong-person": (
        "Someone who is not the person you wanted picks up",
        "You are Alex Morgan's assistant. Call his friend Taylor and ask whether she can come to dinner "
        "on Saturday at six.",
        ["Hello?",
         "Taylor? There's no Taylor here, you've got the wrong number.",
         "No worries. Bye."],
    ),
}


def main() -> None:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
        except Exception:
            pass
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    brain = sys.argv[2] if len(sys.argv) > 2 else "luna"
    if which not in SCRIPTS and which != "all":
        raise SystemExit(f"unknown script {which!r}; pick one of: {', '.join(SCRIPTS)}, or 'all'")
    for name, (label, task, turns) in SCRIPTS.items():
        if which in ("all", name):
            drive(label, task, turns, brain)


if __name__ == "__main__":
    main()
