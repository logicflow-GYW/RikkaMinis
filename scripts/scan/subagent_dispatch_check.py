#!/usr/bin/env python3
"""Sub-agent dispatch guard — [rework/spawn-agent-as-real-session 2026-11-17].

`spawn_agent` used to be a second agent loop (`runSubagentLoop`) with its own
filtered tool set. It is now a dispatch into a REAL chat session via
`ChatMutationMethods.prompt` — the same path `minis-sessions-cli send` uses —
so the loop-internal contracts are gone and what can silently rot is the wiring
around that one call. Each criterion below is a defect that has already
happened once in this tree, in a sibling path:

  D1  The dispatch call exists in `executeSpawnAgentTool`. (A rewrite that
      drops the call must not leave a green gate behind.)
  D2  It asks for the FINAL answer — `wait = true` plus a bounded wait. A
      fire-and-forget dispatch returns a session id where the caller expects
      an answer, and the model reads "started" as "done".
  D3  The run's deadline and ONE provider attempt are charged BEFORE the call.
      The old spawn path burned provider attempts the host budget never
      counted ([FIX-R4-Q F-14]); a gate placed after the call pays the same
      bill.
  D4  `CancellationException` is caught first and RETHROWN. A user stop
      reported as "sub-agent failed" misattributes the stop to the model.
      Same contract the retired hand-back gate enforced.
  D5  The dispatcher is pinned before the call and released inside a `finally`.
      A waiting parent stops updating its own row, so children sort above it;
      the pin is what keeps it on the drawer. A throw must not leak a pin.
  D6  The outcome mapping is delegated to `SubagentSkill.resultOf`. A
      hand-written `status == "Completed"` arm here drifts from the JVM-tested
      mapping (and re-introduces the "refusal read as an answer" class,
      [deep-audit-1044 A-04]).

Usage: python3 scripts/scan/subagent_dispatch_check.py [repo_root] [--selftest]
Exit:  0 = contracts hold, 1 = violation (or the detector rotted)
"""
import os
import sys
from pathlib import Path

_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

from _kotlin_lexer import mask_comments  # noqa: E402

TARGET = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatPromptAndTools.kt"
SIGNATURE = "internal suspend fun ChatViewModel.executeSpawnAgentTool("

DISPATCH_CALL = "ChatMutationMethods.prompt("
WAIT_ON = '.put("wait", true)'
WAIT_BOUND = "SubagentSkill.WAIT_TIMEOUT_SECONDS"
DEADLINE_GATE = "activeRunBudget?.isExpired() == true"
ATTEMPT_GATE = "DIMENSION_PROVIDER_ATTEMPTS"
CANCEL_CATCH = "catch (ce: CancellationException)"
CANCEL_RETHROW = "throw ce"
GENERIC_CATCH = "catch (t: Throwable)"
PIN_BEGIN = "SubagentDispatchPin.begin("
PIN_END = "SubagentDispatchPin.end("
PIN_FINALLY = "} finally {"
RESULT_MAP = "SubagentSkill.resultOf("
HANDWRITTEN_COMPLETED = 'status == "Completed"'


def function_region(src: str, signature: str) -> str:
    """Body text of the declaration starting at [signature], cut at the next
    top-level declaration (column-0 `@` / `internal` / `private` / `fun`)."""
    at = src.find(signature)
    if at < 0:
        return ""
    rest = src[at + len(signature):]
    end = len(rest)
    for marker in ("\n@", "\ninternal ", "\nprivate ", "\npublic ", "\nfun "):
        k = rest.find(marker)
        if 0 <= k < end:
            end = k
    return rest[:end]


def check_source(src: str) -> list:
    """Return [(code, line, message)] for every violated criterion."""
    masked = mask_comments(src)
    body = function_region(masked, SIGNATURE)
    if not body:
        return [("dispatch-missing", 0, SIGNATURE + " not found")]
    problems = []

    call = body.find(DISPATCH_CALL)
    if call < 0:
        problems.append(("dispatch-call-missing", 0,
                         "the session dispatch call is gone — this gate is blind"))
        return problems

    if WAIT_ON not in body:
        problems.append(("dispatch-not-synchronous", 0,
                         "dispatch must ask for the final answer (" + WAIT_ON + ")"))
    if WAIT_BOUND not in body:
        problems.append(("dispatch-wait-unbounded", 0,
                         "the wait must be bounded (" + WAIT_BOUND + ")"))

    deadline = body.find(DEADLINE_GATE)
    if not 0 <= deadline < call:
        problems.append(("budget-deadline-after-call", 0,
                         "the run deadline must be checked BEFORE the dispatch call"))
    attempt = body.find(ATTEMPT_GATE)
    if not 0 <= attempt < call:
        problems.append(("budget-attempt-after-call", 0,
                         "one provider attempt must be charged BEFORE the dispatch call"))

    cancel = body.find(CANCEL_CATCH)
    generic = body.find(GENERIC_CATCH)
    if cancel < 0:
        problems.append(("cancel-catch-missing", 0,
                         "CancellationException must be caught before the generic arm"))
    else:
        if generic >= 0 and cancel > generic:
            problems.append(("cancel-catch-after-generic", 0,
                             "the CancellationException arm must precede catch (t: Throwable)"))
        rethrow = body.find(CANCEL_RETHROW, cancel)
        if rethrow < 0 or (generic >= 0 and rethrow > generic):
            problems.append(("cancel-not-rethrown", 0,
                             "a user stop must be rethrown, not reported as a failure"))

    begin = body.find(PIN_BEGIN)
    if not 0 <= begin < call:
        problems.append(("dispatch-pin-missing", 0,
                         "the dispatcher must be pinned BEFORE the child runs"))
    finally_at = body.find(PIN_FINALLY)
    end = body.find(PIN_END)
    if end < 0:
        problems.append(("dispatch-unpin-missing", 0,
                         "the pin taken for the dispatch must be released"))
    elif finally_at < 0 or end < finally_at:
        problems.append(("dispatch-unpin-outside-finally", 0,
                         "the release must live in a `finally` (a throw must not leak a pin"))

    if RESULT_MAP not in body:
        problems.append(("result-map-not-delegated", 0,
                         "outcome mapping must go through " + RESULT_MAP))
    if HANDWRITTEN_COMPLETED in body:
        problems.append(("handwritten-completed-arm", 0,
                         "a hand-written `" + HANDWRITTEN_COMPLETED + "` arm duplicates "
                         "SubagentSkill.resultOf and will drift from its JVM tests"))
    return problems


# ── Fixtures ────────────────────────────────────────────────────────────────

GOOD = '''internal suspend fun ChatViewModel.executeSpawnAgentTool(argsJson: String): ToolExecutionResult {
    if (traceObserver.activeRunBudget?.isExpired() == true) {
        return ToolExecutionResult("Error: refused", false)
    }
    if (!traceObserver.t7ConsumeAndTrace(AgentTraceRecorder.DIMENSION_PROVIDER_ATTEMPTS) { it.consumeProviderAttempt() }) {
        return ToolExecutionResult("Error: refused", false)
    }
    val params = JSONObject()
        .put("wait", true)
        .put("waitTimeout", SubagentSkill.WAIT_TIMEOUT_SECONDS)
    var ourPin: Long? = null
    val depth = SubagentDispatchPin.begin(activeSessionId)
    val result = try {
        try {
            ChatMutationMethods.prompt(context, params)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return ToolExecutionResult("Error", false)
        }
    } finally {
        if (SubagentDispatchPin.end(activeSessionId) == 0 && ourPin != null) {
            chatRepository.pinSession(activeSessionId, false)
        }
    }
    return SubagentSkill.resultOf("Completed", "x", "s", false)
}
'''

DIRTY = {
    "NO_CALL": GOOD.replace("ChatMutationMethods.prompt(context, params)",
                            "chatRepository.observeSessions()"),
    "FIRE_AND_FORGET": GOOD.replace('.put("wait", true)', '.put("wait", false)'),
    "UNBOUNDED": GOOD.replace('.put("waitTimeout", SubagentSkill.WAIT_TIMEOUT_SECONDS)',
                              '.put("waitTimeout", 999999999)'),
    "GATE_AFTER_CALL": GOOD.replace(
        '''    if (traceObserver.activeRunBudget?.isExpired() == true) {
        return ToolExecutionResult("Error: refused", false)
    }
''', '').replace(
        '''    return SubagentSkill.resultOf("Completed", "x", "s", false)''',
        '''    if (traceObserver.activeRunBudget?.isExpired() == true) {
        return ToolExecutionResult("Error: refused", false)
    }
    return SubagentSkill.resultOf("Completed", "x", "s", false)'''),
    "NO_CANCEL_ARM": GOOD.replace('''        } catch (ce: CancellationException) {
            throw ce
''', ''),
    "CANCEL_SWALLOWED": GOOD.replace("            throw ce\n", "            return ToolExecutionResult(\"Error\", false)\n"),
    "NO_PIN": GOOD.replace("SubagentDispatchPin.begin(activeSessionId)", "0"),
    "PIN_LEAK": GOOD.replace("    } finally {", "    } catch (x: Throwable) {"),
    "HANDWRITTEN": GOOD.replace(
        'return SubagentSkill.resultOf("Completed", "x", "s", false)',
        'if (status == "Completed") return ToolExecutionResult("x", true)\n    return ToolExecutionResult("e", false)'),
}


def selftest() -> int:
    ok = True
    problems = check_source(GOOD)
    if problems:
        ok = False
        print("SELFTEST FAIL: GOOD fixture is red: %r" % (problems,))
    for name, src in sorted(DIRTY.items()):
        problems = check_source(src)
        if not problems:
            ok = False
            print("SELFTEST FAIL: %s fixture is green (gate has no teeth)" % name)
        else:
            print("SELFTEST arm %-18s red: %s" % (name, problems[0][0]))
    if ok:
        print("SELFTEST OK")
    return 0 if ok else 1


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if "--selftest" in sys.argv:
        return selftest()
    root = Path(args[0]) if args else Path(".")
    target = root / TARGET
    if not target.exists():
        print("FAIL: %s not found" % target)
        return 1
    src = target.read_text(encoding="utf-8")
    problems = check_source(src)
    if not problems:
        print("OK: sub-agent dispatch wiring holds (6 contracts)")
        return 0
    for code, line, message in problems:
        print("FAIL [%s]: %s" % (code, message))
    return 1


if __name__ == "__main__":
    sys.exit(main())
