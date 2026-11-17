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
  D5  The dispatcher is pinned before the call and released inside a `finally`
      — and only through the window operations
      (`SubagentDispatchPin.begin` / `.end`), never the raw bookkeeping.
  D5b The window operations themselves keep the decisions INCR-1117B fixed
      (`data/SubagentDispatchPin.kt`): both are NonCancellable, both are
      serialized per session, the open pins only the FIRST window and never
      adopts a pin it did not write, the close restores only on the LAST
      window and only after reading the current value.
  D6  The outcome mapping is delegated to `SubagentSkill.resultOf`. A
      hand-written `status == "Completed"` arm here drifts from the JVM-tested
      mapping (and re-introduces the "refusal read as an answer" class,
      [deep-audit-1044 A-04]).

[INCR-1117B T1 ROT repair] D5 used to anchor on `SubagentDispatchPin.begin(` /
`.end(` ALONE, i.e. on the token, not on the decision. When the pin bookkeeping
moved into the object (A3-a/A3-b: the count and the Room round trip are one
mutually exclusive operation), the call site kept the same two tokens — so the
gate stayed green while the very properties it exists for (serialized window,
uncancellable restore, restore only on the last window) became unasserted.
The repair is a re-anchor, not a relaxation: the call-site anchor stays (the
names stayed too) and D5b now asserts the object's decisions. The behaviour of
those decisions (interleaved windows, cancel-after-write, manual pin survives)
is JVM-tested in `SubagentDispatchPinTest`; this gate asserts that the code
shape which makes those tests meaningful is still there.

Usage: python3 scripts/scan/subagent_dispatch_check.py [repo_root] [--selftest]
Exit:  0 = contracts hold, 1 = violation (or the detector rotted)
"""
import os
import re
import sys
from pathlib import Path

_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

from _kotlin_lexer import mask_comments  # noqa: E402

TARGET = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatPromptAndTools.kt"
PIN_OBJECT = "src/android/app/src/main/java/com/rikkaminis/app/data/SubagentDispatchPin.kt"
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
# The raw bookkeeping is private now: touching it at the call site is exactly
# the "two suspend calls outside the window" shape that produced A3-a/A3-b.
RAW_BOOKKEEPING = ("SubagentDispatchPin.noteWrittenPin(", "SubagentDispatchPin.takeWrittenPin(")

PIN_BEGIN_SIG = "suspend fun begin("
PIN_END_SIG = "suspend fun end("
NON_CANCELLABLE = "withContext(NonCancellable)"
WINDOW_LOCK = "withLock"


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


def balanced_region(src: str, marker: str) -> str:
    """Text of [marker]'s own body, brace matched from its opening `{`.

    Member functions are indented, so `function_region`'s column-0 cut cannot
    end them; the pin object's ops are members."""
    at = src.find(marker)
    if at < 0:
        return ""
    open_at = src.find("{", at + len(marker))
    if open_at < 0:
        return ""
    depth = 0
    for i in range(open_at, len(src)):
        c = src[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return src[at:i + 1]
    return ""


def _depth_var(region: str):
    """Name of the variable holding this window's remaining depth, if any."""
    m = re.search(r"\b(?:val|var)\s+(\w+)\s*=\s*depths\.(?:compute|merge)\(", region)
    return m.group(1) if m else None


def _guard_mentions(region: str, upto: int, var: str) -> bool:
    """True when an `if (<…var…>)` guards the code before [upto].

    Deliberately not anchored on the comparison operator: `if (left != 0) …`,
    `if (left > 0) …` and `if (left == 0) { … } else return` are the same
    decision, and pinning the spelling is how a gate goes blind."""
    for m in re.finditer(r"if\s*\(([^)]*)\)", region[:upto]):
        if var in m.group(1):
            return True
    return False


def check_source(src: str) -> list:
    """Return [(code, line, message)] for every violated call-site criterion."""
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

    # D5 (call site): open before the child, close inside a finally, and only
    # through the window ops — the raw bookkeeping is private because calling
    # it here is the shape that let A3-a/A3-b happen.
    begin = body.find(PIN_BEGIN)
    if not 0 <= begin < call:
        problems.append(("dispatch-pin-missing", 0,
                         "the dispatcher must be pinned BEFORE the child runs "
                         "(through " + PIN_BEGIN + ")"))
    finally_at = body.find(PIN_FINALLY)
    end = body.find(PIN_END)
    if end < 0:
        problems.append(("dispatch-unpin-missing", 0,
                         "the pin taken for the dispatch must be released "
                         "(through " + PIN_END + ")"))
    elif finally_at < 0 or end < finally_at:
        problems.append(("dispatch-unpin-outside-finally", 0,
                         "the release must live in a `finally` (a throw must not leak a pin"))
    for token in RAW_BOOKKEEPING:
        if token in body:
            problems.append(("dispatch-pin-bypasses-window", 0,
                             token + " at the call site skips the per-session lock and the "
                             "NonCancellable block that make the window atomic"))

    if RESULT_MAP not in body:
        problems.append(("result-map-not-delegated", 0,
                         "outcome mapping must go through " + RESULT_MAP))
    if HANDWRITTEN_COMPLETED in body:
        problems.append(("handwritten-completed-arm", 0,
                         "a hand-written `" + HANDWRITTEN_COMPLETED + "` arm duplicates "
                         "SubagentSkill.resultOf and will drift from its JVM tests"))
    return problems


def check_pin_object(src: str) -> list:
    """D5b: the window ops must still keep the decisions INCR-1117B fixed."""
    masked = mask_comments(src)
    begin = balanced_region(masked, PIN_BEGIN_SIG)
    end = balanced_region(masked, PIN_END_SIG)
    problems = []
    if not begin:
        problems.append(("pin-window-missing", 0, PIN_BEGIN_SIG + " not found — the window open op is gone"))
    if not end:
        problems.append(("pin-window-missing", 0, PIN_END_SIG + " not found — the window close op is gone"))
    if not begin or not end:
        return problems

    for side, region in (("open", begin), ("close", end)):
        if NON_CANCELLABLE not in region:
            problems.append(("pin-window-cancellable-" + side, 0,
                             "the " + side + " half of the window must run under "
                             + NON_CANCELLABLE + ": a stop that lands between the pin write "
                             "and its bookkeeping used to leave the parent pinned for good"))
        if WINDOW_LOCK not in region:
            problems.append(("pin-window-unsynchronized-" + side, 0,
                             "the " + side + " half must hold the per-session lock: without it a "
                             "new window reads a pin the previous restore is about to undo"))

    depth_var = _depth_var(begin)
    if depth_var is None:
        problems.append(("pin-open-no-depth-count", 0,
                         "the open op must count the window (depths.merge)"))
    pin_at = begin.find("pin(")
    if pin_at < 0:
        problems.append(("pin-open-never-pins", 0, "the first window must pin the dispatcher"))
    elif depth_var is not None and not _guard_mentions(begin, pin_at, depth_var):
        problems.append(("pin-open-every-window", 0,
                         "only the FIRST window may pin — the pin must sit behind a guard on "
                         "the window count"))
    read_at = begin.find("readPinnedAt(")
    if read_at < 0 or (pin_at >= 0 and read_at > pin_at):
        problems.append(("pin-open-adopts-existing", 0,
                         "the current pin must be read BEFORE pinning: a session the user "
                         "pinned already was adopted as ours and later unpinned"))
    elif not re.search(r"null", begin[read_at:read_at + 200]):
        problems.append(("pin-open-adopts-existing", 0,
                         "the read pin must be compared with null (an existing pin is not ours)"))

    restore_at = end.find("unpin(")
    close_depth = _depth_var(end)
    if restore_at < 0:
        problems.append(("pin-close-never-restores", 0,
                         "the last window must undo the pin it took"))
    else:
        if close_depth is None:
            problems.append(("pin-close-no-depth-count", 0,
                             "the close op must count the window down (depths.compute)"))
        elif not _guard_mentions(end, restore_at, close_depth):
            problems.append(("pin-close-restores-early", 0,
                             "only the LAST window may restore — the undo must sit behind a "
                             "guard on the remaining depth"))
        read_close = end.find("readPinnedAt(")
        if read_close < 0 or read_close > restore_at:
            problems.append(("pin-close-undone-blindly", 0,
                             "the current value must be read before undoing: a manual pin "
                             "taken during the window must survive the restore"))
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
    SubagentDispatchPin.begin(
        sessionId = activeSessionId,
        readPinnedAt = { chatRepository.getSession(it)?.pinnedAt },
        pin = { chatRepository.pinSession(it, true) },
    )
    val result = try {
        try {
            ChatMutationMethods.prompt(context, params)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return ToolExecutionResult("Error", false)
        }
    } finally {
        val restored = SubagentDispatchPin.end(
            sessionId = activeSessionId,
            readPinnedAt = { chatRepository.getSession(it)?.pinnedAt },
            unpin = { chatRepository.pinSession(it, false) },
        )
        if (!restored) {
            AppLogger.warning("TAG", "restore failed")
        }
    }
    return SubagentSkill.resultOf("Completed", "x", "s", false)
}
'''

GOOD_PIN = '''object SubagentDispatchPin {

    private val depths = ConcurrentHashMap<String, Int>()
    private val writtenPins = ConcurrentHashMap<String, Long>()
    private val windowLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(sessionId: String): Mutex =
        windowLocks.computeIfAbsent(sessionId) { Mutex() }

    suspend fun begin(
        sessionId: String,
        readPinnedAt: suspend (String) -> Long?,
        pin: suspend (String) -> Long?,
    ): Int = withContext(NonCancellable) {
        lockFor(sessionId).withLock {
            val depth = depths.merge(sessionId, 1, Int::plus) ?: 1
            if (depth == 1 && readPinnedAt(sessionId) == null) {
                pin(sessionId)?.let { writtenPins[sessionId] = it }
            }
            depth
        }
    }

    suspend fun end(
        sessionId: String,
        readPinnedAt: suspend (String) -> Long?,
        unpin: suspend (String) -> Unit,
    ): Boolean = withContext(NonCancellable) {
        lockFor(sessionId).withLock {
            val left = depths.compute(sessionId) { _, v ->
                if (v == null || v <= 1) null else v - 1
            } ?: 0
            if (left != 0) return@withLock true
            val ourPin = writtenPins[sessionId] ?: return@withLock true
            val current = readPinnedAt(sessionId)
            if (current == ourPin) {
                unpin(sessionId)
            }
            writtenPins.remove(sessionId)
            true
        }
    }
}
'''

# (name, mutant source, expected code, which checker)
DIRTY = {
    "NO_CALL": (GOOD.replace("ChatMutationMethods.prompt(context, params)",
                             "chatRepository.observeSessions()"), "dispatch-call-missing", "call"),
    "FIRE_AND_FORGET": (GOOD.replace('.put("wait", true)', '.put("wait", false)'),
                        "dispatch-not-synchronous", "call"),
    "UNBOUNDED": (GOOD.replace('.put("waitTimeout", SubagentSkill.WAIT_TIMEOUT_SECONDS)',
                               '.put("waitTimeout", 999999999)'), "dispatch-wait-unbounded", "call"),
    "GATE_AFTER_CALL": (GOOD.replace(
        '''    if (traceObserver.activeRunBudget?.isExpired() == true) {
        return ToolExecutionResult("Error: refused", false)
    }
''', '').replace(
        '''    return SubagentSkill.resultOf("Completed", "x", "s", false)''',
        '''    if (traceObserver.activeRunBudget?.isExpired() == true) {
        return ToolExecutionResult("Error: refused", false)
    }
    return SubagentSkill.resultOf("Completed", "x", "s", false)'''), "budget-deadline-after-call", "call"),
    "NO_CANCEL_ARM": (GOOD.replace('''        } catch (ce: CancellationException) {
            throw ce
''', ''), "cancel-catch-missing", "call"),
    "CANCEL_SWALLOWED": (GOOD.replace("            throw ce\n",
                                      "            return ToolExecutionResult(\"Error\", false)\n"),
                         "cancel-not-rethrown", "call"),
    "NO_PIN": (GOOD.replace('''    SubagentDispatchPin.begin(
        sessionId = activeSessionId,
        readPinnedAt = { chatRepository.getSession(it)?.pinnedAt },
        pin = { chatRepository.pinSession(it, true) },
    )
''', ''), "dispatch-pin-missing", "call"),
    "PIN_AFTER_CALL": (GOOD.replace('''    SubagentDispatchPin.begin(
        sessionId = activeSessionId,
        readPinnedAt = { chatRepository.getSession(it)?.pinnedAt },
        pin = { chatRepository.pinSession(it, true) },
    )
''', '').replace("            ChatMutationMethods.prompt(context, params)",
                 "            ChatMutationMethods.prompt(context, params)\n            SubagentDispatchPin.begin(1) { it }"),
                       "dispatch-pin-missing", "call"),
    "PIN_LEAK": (GOOD.replace("    } finally {", "    } catch (x: Throwable) {"),
                 "dispatch-unpin-outside-finally", "call"),
    "UNPIN_MISSING": (GOOD.replace('''        val restored = SubagentDispatchPin.end(
            sessionId = activeSessionId,
            readPinnedAt = { chatRepository.getSession(it)?.pinnedAt },
            unpin = { chatRepository.pinSession(it, false) },
        )
''', ''), "dispatch-unpin-missing", "call"),
    "RAW_BOOKKEEPING": (GOOD.replace('''    val result = try {''',
                                     '''    SubagentDispatchPin.noteWrittenPin(activeSessionId, 1L)
    val result = try {'''), "dispatch-pin-bypasses-window", "call"),
    "HANDWRITTEN": (GOOD.replace(
        'return SubagentSkill.resultOf("Completed", "x", "s", false)',
        'if (status == "Completed") return ToolExecutionResult("x", true)\n    return ToolExecutionResult("e", false)'),
                    "handwritten-completed-arm", "call"),
    # D5b — the object's own decisions
    "PIN_OBJ_NO_NONCANCELLABLE": (GOOD_PIN.replace("withContext(NonCancellable)", "run"),
                                  "pin-window-cancellable-open", "pin"),
    "PIN_OBJ_CLOSE_CANCELLABLE": (GOOD_PIN.replace(
        "    ): Boolean = withContext(NonCancellable) {", "    ): Boolean = run {"),
                                  "pin-window-cancellable-close", "pin"),
    "PIN_OBJ_NO_LOCK": (GOOD_PIN.replace("lockFor(sessionId).withLock {", "lockFor(sessionId).run {"),
                        "pin-window-unsynchronized-open", "pin"),
    "PIN_OBJ_PINS_EVERY_WINDOW": (GOOD_PIN.replace("if (depth == 1 && readPinnedAt(sessionId) == null) {",
                                                   "if (readPinnedAt(sessionId) == null) {"),
                                   "pin-open-every-window", "pin"),
    "PIN_OBJ_ADOPTS_EXISTING": (GOOD_PIN.replace("if (depth == 1 && readPinnedAt(sessionId) == null) {",
                                                 "if (depth == 1) {"),
                                "pin-open-adopts-existing", "pin"),
    "PIN_OBJ_RESTORES_EARLY": (GOOD_PIN.replace("            if (left != 0) return@withLock true\n", ""),
                               "pin-close-restores-early", "pin"),
    "PIN_OBJ_BLIND_UNDO": (GOOD_PIN.replace('''            val current = readPinnedAt(sessionId)
            if (current == ourPin) {
                unpin(sessionId)
            }
''', '''            unpin(sessionId)
'''), "pin-close-undone-blindly", "pin"),
    "PIN_OBJ_NO_RESTORE": (GOOD_PIN.replace("                unpin(sessionId)\n", ""),
                           "pin-close-never-restores", "pin"),
}


def selftest() -> int:
    ok = True
    problems = check_source(GOOD)
    if problems:
        ok = False
        print("SELFTEST FAIL: GOOD call-site fixture is red: %r" % (problems,))
    pin_problems = check_pin_object(GOOD_PIN)
    if pin_problems:
        ok = False
        print("SELFTEST FAIL: GOOD pin-object fixture is red: %r" % (pin_problems,))
    arms = 0
    for name in sorted(DIRTY):
        src, expect, which = DIRTY[name]
        problems = check_source(src) if which == "call" else check_pin_object(src)
        codes = [p[0] for p in problems]
        arms += 1
        if not codes:
            ok = False
            print("SELFTEST FAIL: %s fixture is green (gate has no teeth)" % name)
        elif expect not in codes:
            ok = False
            print("SELFTEST FAIL: %s went red as %r, expected %r" % (name, codes, expect))
        else:
            print("SELFTEST arm %-26s red: %s" % (name, expect))
    if ok:
        print("SELFTEST OK (%d arms)" % arms)
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
    problems = check_source(target.read_text(encoding="utf-8"))
    pin_target = root / PIN_OBJECT
    if pin_target.exists():
        problems += check_pin_object(pin_target.read_text(encoding="utf-8"))
    else:
        problems.append(("pin-window-missing", 0, "%s not found" % PIN_OBJECT))
    if not problems:
        print("OK: sub-agent dispatch wiring holds (5 call-site contracts + 8 pin-window decisions)")
        return 0
    for code, line, message in problems:
        print("FAIL [%s]: %s" % (code, message))
    return 1


if __name__ == "__main__":
    sys.exit(main())
