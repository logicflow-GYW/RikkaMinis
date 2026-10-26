#!/usr/bin/env python3
"""
Gate: FIX-G (deep-audit-1021 ISSUE-08) — SpeechRecognitionManager active-engine
binding.

Root cause (verified on the baseline tree): `startRecording` resolves
`currentEngine()` and never records the started instance, while
`stopRecording` / `cancelRecording` / `selectLocale` re-resolve
`currentEngine()`. Two mid-session paths redirect them to a different,
never-started engine and leak the real one's AudioRecord / SpeechRecognizer:

  1. an explicit `selectEngine(newId)` (the only mid-session writer of
     `_selectedEngineId`; the report's "refreshAvailability auto-degrade"
     path does not exist — `refreshAvailability` only writes `_isAvailable`);
  2. the internal fallback inside `currentEngine()` itself: when the selected
     engine degrades it silently returns the first available engine WITHOUT
     writing `_selectedEngineId`.

The fix binds `@Volatile activeEngine` to the started instance. Volatile is
sufficient: every site is a single write or a single read, no read-modify-write.

Judging shape (batch-7 rewrite): every arm anchors on *the value that is really
read at the call site* instead of on a spelling.  The shared lexer / AST /
value-flow primitives (`_kotlin_lexer`, `_kotlin_ast`, `_kotlin_valueflow`)
own the lexical work; the arms own only the decisions:

  arm0  the binding written before `<engine>.start(…)` must SURVIVE to the call
        (a later `activeEngine = null` at the same block depth wins — last write
        wins, so the latch is null while the mic is open).
  arm1  `stopRecording` must call `stop()` on the bound instance, on a reachable
        path, and must not overwrite the binding before that call.
  arm2  `cancelRecording`: same, plus it must clear the binding afterwards.
  arm3  `selectEngine`'s teardown must sit in a FEASIBLE guard (`false && …`
        is a dead guard, not a teardown) that cancels → clears → resets.
  arm3b `selectLocale` restarts mid-recording: bound cancel, cancel-then-clear.
  arm4  the terminal callbacks must clear the binding.
  arm5  the binding stays `@Volatile`.

Usage: python3 scripts/scan/speech_active_engine_check.py [repo_root]
       python3 scripts/scan/speech_active_engine_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage/ROT
"""
import os
import re
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

from _kotlin_lexer import KotlinLexError, mask_code
from _kotlin_ast import const_value, function_body, if_blocks, strip_parens
from _kotlin_valueflow import (
    brace_depth,
    contains_ident,
    expr_end,
    guard_feasible,
    path_feasible,
)

SRM_REL = "src/android/app/src/main/java/com/rikkaminis/app/speech/SpeechRecognitionManager.kt"

BINDING = "activeEngine"
ELVIS_STOP = "(activeEngine ?: currentEngine())?.stop()"
ELVIS_CANCEL = "(activeEngine ?: currentEngine())?.cancel()"

# `activeEngine = …` (assignment, not `==`, not `x.activeEngine`).
ASSIGN_RE = re.compile(r"(?<![\w.`])activeEngine\s*=(?!=)")


# --------------------------------------------------------------------------
# shared-view helpers
# --------------------------------------------------------------------------

def _view(src):
    """Structural channel (comments + literal bodies → spaces, offsets kept).

    Returns None when a literal/comment never terminates; callers report ROT /
    violation (fail-closed) instead of falling back to raw-text scanning.
    """
    try:
        return mask_code(src)
    except KotlinLexError:
        return None


def _slice(code, name):
    """[body_start, body_end) of `fun <name>(…)`, or (None, None)."""
    got = function_body(code, name)
    if not got:
        return (None, None)
    return (got[0], got[1])


def _assignments(code, start, end):
    """[{pos, rhs, depth}] for every `activeEngine = …` inside [start, end)."""
    out = []
    for m in ASSIGN_RE.finditer(code, start, end):
        rhs_start = m.end()
        rhs_end = expr_end(code, rhs_start)
        out.append({
            "pos": m.start(),
            "rhs": code[rhs_start:rhs_end].strip(),
            "depth": brace_depth(code, m.start()),
        })
    return out


def _is_constant(rhs):
    """True when the right-hand side provably cannot be an engine instance."""
    r = strip_parens(rhs)
    if not r:
        return True
    return const_value(r) is not None


def _receiver_kind(code, dot_pos):
    """Classify the receiver of the call whose `.` sits at `dot_pos`.

    'bound'   — the expression ends at `activeEngine` (bare or `?`-safe), or at
                `(activeEngine ?: …)`: the call lands on the instance that owns
                the mic.
    'reparse' — it ends at a bare `currentEngine()`: re-resolution, i.e. the
                original defect (the re-resolved engine may never have started).
    'other'   — anything else.
    """
    win = code[max(0, dot_pos - 200):dot_pos]
    cut = max(win.rfind(ch) for ch in ";={}")
    win = win[cut + 1:]
    # A statement boundary is also a depth-0 newline: `activeEngine = null` on the
    # previous line must not become part of this receiver.
    nl = win.rfind("\n")
    if nl >= 0:
        tail = win[nl + 1:]
        if tail.count("(") == tail.count(")") and tail.count("[") == tail.count("]"):
            win = tail
    recv = win.strip()
    if recv.endswith("?"):
        recv = recv[:-1].rstrip()
    if re.fullmatch(r"currentEngine\s*\(\s*\)", recv):
        return "reparse"
    if re.fullmatch(re.escape(BINDING), recv):
        return "bound"
    if recv.endswith(")"):
        depth = 0
        open_idx = -1
        for i in range(len(recv) - 1, -1, -1):
            if recv[i] == ")":
                depth += 1
            elif recv[i] == "(":
                depth -= 1
                if depth == 0:
                    open_idx = i
                    break
        if open_idx < 0:
            return "other"
        inner = recv[open_idx + 1:-1].strip()
        if re.fullmatch(r"currentEngine\s*\(\s*\)", inner):
            return "reparse"
        if re.fullmatch(re.escape(BINDING), inner):
            return "bound"
        head, sep, _tail = inner.partition("?:")
        if sep and contains_ident(head, BINDING):
            return "bound"
        return "other"
    return "other"


def _calls(code, start, end, verb):
    """[(pos, receiver_kind, live)] for every `.<verb>(` call in [start, end)."""
    out = []
    for m in re.finditer(r"\.\s*" + verb + r"\s*\(", code[start:end]):
        pos = start + m.start()
        out.append((pos, _receiver_kind(code, pos), path_feasible(code, pos)))
    return out


def _order_violation(code, label, first_bound, writes, verb_phrase):
    """A live write of the binding BEFORE the call neutralises it.

    The old shape searched `find(".cancel()") < find("activeEngine = null")`,
    which a dead `if (false) …cancel()` token in front of the clear satisfies.
    Here the position is the *bound* call's, and the writes must be reachable.
    """
    before = [w for w in writes
              if w["pos"] < first_bound and path_feasible(code, w["pos"])]
    if not before:
        return None
    return ("SpeechRecognitionManager.kt: %s clears activeEngine BEFORE %s — the call then "
            "lands on currentEngine() (or on a null) instead of the engine that owns the "
            "mic; cancel/stop first, clear after (last write wins)"
            % (label, verb_phrase))


def _null_clear(code, start, end):
    """A live `activeEngine = null` inside [start, end)? (any position)"""
    for w in _assignments(code, start, end):
        if const_value(strip_parens(w["rhs"])) == ("null", None):
            return w["pos"]
    return None


# --------------------------------------------------------------------------
# arms
# --------------------------------------------------------------------------

def _arm_start_binding(code, v):
    """arm0 — the binding must survive to the `<engine>.start(…)` call."""
    start, end = _slice(code, "startRecording")
    if start is None:
        v.append("SpeechRecognitionManager.kt: startRecording declaration not found "
                 "(gate anchors moved — deliberate edit required)")
        return
    start_calls = [(start + m.start(), m.group(1))
                   for m in re.finditer(
                       r"(?<![\w.])([A-Za-z_]\w*)\s*\.\s*start\s*\(", code[start:end])]
    if not start_calls:
        v.append("SpeechRecognitionManager.kt: startRecording no longer starts an engine "
                 "(no `<engine>.start(…)` call — the mic never opens)")
        return
    writes = _assignments(code, start, end)
    for call_pos, var in start_calls:
        binds = [w for w in writes
                 if w["pos"] < call_pos and not _is_constant(w["rhs"])
                 and contains_ident(w["rhs"], var)]
        if not binds:
            v.append("SpeechRecognitionManager.kt: startRecording does not bind activeEngine "
                     "to the engine it starts (`activeEngine = %s` before `%s.start(…)`) — the "
                     "latch stays null forever and every consumer degrades to currentEngine() "
                     "(da1021 ISSUE-08: the real engine leaks on a mid-session switch)"
                     % (var, var))
            continue
        bind = binds[-1]
        # Last write wins: a later write at the SAME block depth (not a callback
        # body or an error handler nested one level deeper) replaces the latch
        # before the engine opens the mic.
        clobber = [w for w in writes
                   if bind["pos"] < w["pos"] < call_pos and w["depth"] == bind["depth"]]
        if clobber:
            v.append("SpeechRecognitionManager.kt: startRecording overwrites the binding "
                     "(`activeEngine = %s` … then `activeEngine = %s`, same block depth, before "
                     "`%s.start(…)`) — the last write wins, so the latch is null while the "
                     "engine holds the mic and stop/cancel keep re-resolving currentEngine() "
                     "(da1021 ISSUE-08)" % (bind["rhs"][:40], clobber[0]["rhs"][:40], var))


def _arm_teardown(code, v, name, verb, verb_phrase, need_bound=True,
                  need_clear=True, quiet_when_absent=False):
    """Shared shape of arm1 / arm2 / arm3b."""
    start, end = _slice(code, name)
    if start is None:
        if not quiet_when_absent:
            v.append("SpeechRecognitionManager.kt: %s declaration not found "
                     "(gate anchors moved — deliberate edit required)" % name)
        return
    calls = _calls(code, start, end, verb)
    writes = _assignments(code, start, end)
    live = [(p, k) for p, k, ok in calls if ok]
    bound = [p for p, k in live if k == "bound"]
    reparse = [p for p, k in live if k == "reparse"]

    if need_bound and not bound and not reparse:
        v.append("SpeechRecognitionManager.kt: %s no longer targets activeEngine (elvis form "
                 "lost) — it re-resolves the engine at teardown time, so a mid-session "
                 "selectEngine or the currentEngine() internal fallback sends %s() to a "
                 "never-started engine and the real one leaks (da1021 ISSUE-08)"
                 % (name, verb))
    if reparse:
        v.append("SpeechRecognitionManager.kt: %s re-resolves currentEngine() for its %s() "
                 "call — that engine may never have been started, and the instance that "
                 "actually owns the AudioRecord / SpeechRecognizer is never released"
                 % (name, verb))
    if bound:
        w = _order_violation(code, name, min(bound), writes, verb_phrase)
        if w:
            v.append(w)
    if need_clear and _null_clear(code, start, end) is None:
        v.append("SpeechRecognitionManager.kt: %s no longer clears activeEngine (stale binding "
                 "survives into the next session)" % name)


def _arm_select_engine(code, v):
    """arm3 — the mid-recording teardown must live in a FEASIBLE guard."""
    start, end = _slice(code, "selectEngine")
    if start is None:
        v.append("SpeechRecognitionManager.kt: selectEngine declaration not found")
        return
    guards = [(cond, ts, te) for cond, ts, te, _es, _ee in if_blocks(code)
              if start <= ts < end and ".cancel(" in code[ts:te]]
    if not guards:
        if "_state.value != RecognitionState.IDLE" not in code[start:end]:
            v.append("SpeechRecognitionManager.kt: selectEngine lost the mid-recording "
                     "state check (_state.value != RecognitionState.IDLE)")
        v.append("SpeechRecognitionManager.kt: selectEngine no longer cancels the in-flight "
                 "engine (mic handle leak on mid-recording switch)")
        return
    for cond, ts, te in guards:
        block = code[ts:te]
        if not guard_feasible(cond):
            v.append("SpeechRecognitionManager.kt: selectEngine's mid-recording guard is dead "
                     "(`%s`) — the block still holds every anchor of the teardown but can "
                     "never run, so a mid-recording engine switch leaves the old engine "
                     "holding the mic and the state machine wedged"
                     % re.sub(r"\s+", " ", cond).strip()[:90])
            continue
        bcalls = [p for p, k, _live in _calls(code, ts, te, "cancel")
                  if k == "bound" and _live]
        if not bcalls:
            v.append("SpeechRecognitionManager.kt: selectEngine's teardown does not cancel the "
                     "bound activeEngine (elvis form lost inside the guard)")
        else:
            w = _order_violation(code, "selectEngine", min(bcalls),
                                 _assignments(code, ts, te), "cancelling")
            if w:
                v.append(w)
        if _null_clear(code, ts, te) is None:
            v.append("SpeechRecognitionManager.kt: selectEngine no longer clears activeEngine")
        if "_state.value = RecognitionState.IDLE" not in block:
            v.append("SpeechRecognitionManager.kt: selectEngine no longer resets the state "
                     "machine to IDLE (FINISHING/RECORDING wedge)")


def _arm_terminal_clears(code, v):
    """arm4 — terminal callbacks must clear the binding."""
    for name, label in (("onFinal", "onFinal"), ("onError", "onError")):
        start, end = _slice(code, name)
        if start is None:
            v.append("SpeechRecognitionManager.kt: %s listener not found" % label)
        elif _null_clear(code, start, end) is None:
            v.append("SpeechRecognitionManager.kt: %s no longer clears activeEngine" % label)


VOLATILE_RE = re.compile(
    r"@Volatile\s+private\s+var\s+activeEngine\s*:\s*SpeechRecognitionEngine\s*\?\s*=\s*null")


def check_manager(src):
    v = []
    code = _view(src)
    if code is None:
        return ["SpeechRecognitionManager.kt: unterminated string/comment literal — cannot "
                "build the structural view (ROT — update this gate)"]

    _arm_start_binding(code, v)
    _arm_teardown(code, v, "stopRecording", "stop", "stopping", need_clear=False)
    _arm_teardown(code, v, "cancelRecording", "cancel", "cancelling")
    _arm_select_engine(code, v)
    _arm_teardown(code, v, "selectLocale", "cancel", "cancelling", need_clear=False,
                  quiet_when_absent=False)
    _arm_terminal_clears(code, v)

    n_clears = len([w for w in _assignments(code, 0, len(code))
                    if const_value(strip_parens(w["rhs"])) == ("null", None)])
    if n_clears < 4:
        v.append("SpeechRecognitionManager.kt: only %d activeEngine = null sites "
                 "(need >= 4: final / error / cancel / selectEngine)" % n_clears)

    if VOLATILE_RE.search(code) is None:
        v.append("SpeechRecognitionManager.kt: activeEngine must be declared "
                 "@Volatile private var activeEngine: SpeechRecognitionEngine? = null "
                 "(engine callbacks and UI calls run on different threads)")
    return v


# --------------------------------------------------------------------------
# fixtures
# --------------------------------------------------------------------------

GOOD = '''
class SpeechRecognitionManager {
    fun selectEngine(id: String) {
        if (_selectedEngineId.value == id) return
        if (_state.value != RecognitionState.IDLE) {
            activeEngine?.cancel()
            activeEngine = null
            _state.value = RecognitionState.IDLE
        }
        _selectedEngineId.value = id
        prefs.edit().putString(KEY_ENGINE_ID, id).apply()
        refreshAvailability()
    }

    @Volatile
    private var activeEngine: SpeechRecognitionEngine? = null

    fun selectLocale(locale: Locale) {
        _locale.value = locale
        if (_state.value == RecognitionState.RECORDING || _state.value == RecognitionState.STARTING) {
            val cb = activeCallbacks
            if (cb != null) {
                (activeEngine ?: currentEngine())?.cancel()
                activeEngine = null
                _state.value = RecognitionState.IDLE
                startRecording(cb.first, cb.second)
            }
        }
    }

    fun startRecording(a: Int, b: Int) {
        val engine = currentEngine()
        activeCallbacks = a to b
        activeEngine = engine

        val listener = object : SpeechRecognitionEngine.Listener {
            override fun onFinal(text: String) {
                activeEngine = null
                _state.value = RecognitionState.IDLE
            }

            override fun onError(error: RecognitionError, message: String?) {
                activeEngine = null
                _state.value = RecognitionState.IDLE
            }
        }

        try {
            engine.start(locale, listener)
        } catch (e: Throwable) {
            activeEngine = null
            _state.value = RecognitionState.IDLE
        }
    }

    fun stopRecording() {
        if (_state.value != RecognitionState.RECORDING) return
        _state.value = RecognitionState.FINISHING
        (activeEngine ?: currentEngine())?.stop()
    }

    fun cancelRecording() {
        (activeEngine ?: currentEngine())?.cancel()
        activeEngine = null
        _state.value = RecognitionState.IDLE
    }

    fun currentEngine(): SpeechRecognitionEngine? = null
}
'''

# M1: stopRecording back to the bare currentEngine()?.stop() spelling
BAD_STOP = GOOD.replace(
    "        _state.value = RecognitionState.FINISHING\n        (activeEngine ?: currentEngine())?.stop()",
    "        _state.value = RecognitionState.FINISHING\n        currentEngine()?.stop()")

# M2: selectEngine without the mid-recording teardown
BAD_SELECT = GOOD.replace(
    """        if (_state.value != RecognitionState.IDLE) {
            activeEngine?.cancel()
            activeEngine = null
            _state.value = RecognitionState.IDLE
        }
        _selectedEngineId.value = id""",
    "        _selectedEngineId.value = id")

# M3: terminal callbacks no longer clear the binding (stale activeEngine)
BAD_CLEAR = GOOD.replace(
    """            override fun onFinal(text: String) {
                activeEngine = null
                _state.value = RecognitionState.IDLE
            }

            override fun onError(error: RecognitionError, message: String?) {
                activeEngine = null
                _state.value = RecognitionState.IDLE
            }""",
    """            override fun onFinal(text: String) {
                _state.value = RecognitionState.IDLE
            }

            override fun onError(error: RecognitionError, message: String?) {
                _state.value = RecognitionState.IDLE
            }""")

# M4: the @Volatile declaration dropped
BAD_VOLATILE = GOOD.replace("    @Volatile\n    private var activeEngine", "    private var activeEngine")

# W1: the write half of the latch deleted — every consumer arm stays "wired"
# while `activeEngine` is permanently null.
BAD_NO_BIND = GOOD.replace(
    """        activeCallbacks = a to b
        activeEngine = engine""",
    """        activeCallbacks = a to b""")

# W2: cancelRecording clears the binding BEFORE cancelling it.
BAD_CANCEL_ORDER = GOOD.replace(
    """    fun cancelRecording() {
        (activeEngine ?: currentEngine())?.cancel()
        activeEngine = null""",
    """    fun cancelRecording() {
        activeEngine = null
        (activeEngine ?: currentEngine())?.cancel()""")

# W3: selectEngine clears the binding BEFORE the teardown cancel.
BAD_SELECT_ORDER = GOOD.replace(
    """        if (_state.value != RecognitionState.IDLE) {
            activeEngine?.cancel()
            activeEngine = null""",
    """        if (_state.value != RecognitionState.IDLE) {
            activeEngine = null
            activeEngine?.cancel()""")

# ── batch-7 witnesses ─────────────────────────────────────────────────────

# W4 (12-1, dataflow): the binding is written, then reset at the same block
# depth before the engine starts — the write survives, the value does not.
BAD_START_CLOBBER = GOOD.replace(
    """        activeCallbacks = a to b
        activeEngine = engine""",
    """        activeCallbacks = a to b
        activeEngine = engine
        activeEngine = null""")

# W5 (12-2, order): stopRecording clears the binding first; the elvis form is
# untouched, so only the position gives the defect away.
BAD_STOP_CLEAR_FIRST = GOOD.replace(
    """        _state.value = RecognitionState.FINISHING
        (activeEngine ?: currentEngine())?.stop()""",
    """        _state.value = RecognitionState.FINISHING
        activeEngine = null
        (activeEngine ?: currentEngine())?.stop()""")

# W6 (12-3, family-hole): every teardown token is present and ordered, but the
# guard is a constant-false conjunction — the block can never run.
BAD_SELECT_DEAD_GUARD = GOOD.replace(
    "        if (_state.value != RecognitionState.IDLE) {",
    "        if (false && _state.value != RecognitionState.IDLE) {")

# W7 (12-4, completeness): selectLocale back to the pre-fix re-resolution.
BAD_LOCALE_REPARSE = GOOD.replace(
    "                (activeEngine ?: currentEngine())?.cancel()",
    "                currentEngine()?.cancel()")

# W8 (12-5, order): a dead cancel token in front of the clear fools a
# first-`find(".cancel()")` order check.
BAD_CANCEL_DEAD_TOKEN = GOOD.replace(
    """    fun cancelRecording() {
        (activeEngine ?: currentEngine())?.cancel()
        activeEngine = null""",
    """    fun cancelRecording() {
        if (false) activeEngine?.cancel()
        activeEngine = null
        (activeEngine ?: currentEngine())?.cancel()""")


ARMS = [
    # (name, fixture, needle, expect_exactly_one)
    ("M1 bare stopRecording", BAD_STOP, "stopRecording", False),
    ("M2 selectEngine teardown removed", BAD_SELECT, "selectEngine", False),
    ("M3 terminal clears removed", BAD_CLEAR, "onFinal", False),
    ("M4 volatile dropped", BAD_VOLATILE, "@Volatile", False),
    ("W1 startRecording binding deleted", BAD_NO_BIND, "does not bind activeEngine", False),
    ("W2 cancelRecording clears before cancel", BAD_CANCEL_ORDER, "cancelRecording clears", False),
    ("W3 selectEngine clears before cancel", BAD_SELECT_ORDER, "selectEngine clears", False),
    ("W4 binding clobbered before start", BAD_START_CLOBBER, "overwrites the binding", True),
    ("W5 stopRecording clears before stop", BAD_STOP_CLEAR_FIRST,
     "stopRecording clears activeEngine BEFORE", True),
    ("W6 selectEngine dead guard", BAD_SELECT_DEAD_GUARD, "guard is dead", True),
    ("W7 selectLocale re-resolves currentEngine", BAD_LOCALE_REPARSE,
     "selectLocale re-resolves currentEngine()", True),
    ("W8 cancelRecording dead cancel token", BAD_CANCEL_DEAD_TOKEN, "cancelRecording clears", True),
]


def selftest():
    failures = []
    if check_manager(GOOD):
        failures.append("GOOD fixture flagged: %s" % check_manager(GOOD))
    for name, bad, expect, exact_one in ARMS:
        v = check_manager(bad)
        if not v:
            failures.append("%s: gate stayed GREEN on a violation (expected %s)"
                            % (name, expect))
            continue
        if not any(expect in x for x in v):
            failures.append("%s: no violation mentions %r: %r" % (name, expect, v))
            continue
        if exact_one and len(v) != 1:
            failures.append("%s: tripped %d arms, expected exactly 1: %r"
                            % (name, len(v), v))
    if failures:
        print("FAIL speech_active_engine_check --selftest")
        for x in failures:
            print("  " + x)
        return 1
    print("SPEECH_ACTIVE_ENGINE_SELFTEST=PASS (%d fixtures: 1 clean + %d violations)"
          % (len(ARMS) + 1, len(ARMS)))
    print("SPEECH_ACTIVE_ENGINE_SELFTEST_ARMS=%d" % len(ARMS))
    return 0


def read(root, rel):
    with open(os.path.join(root, rel), "r", encoding="utf-8") as fh:
        return fh.read()


def run(root):
    if not os.path.isdir(root):
        print("speech_active_engine_check: not a directory: %s" % root)
        return 2
    try:
        src = read(root, SRM_REL)
    except OSError as exc:
        print("speech_active_engine_check: cannot read sources: %s" % exc)
        return 2
    violations = check_manager(src)
    if violations:
        print("FAIL speech_active_engine_check")
        for x in violations:
            print("  " + x)
        return 1
    print("PASS speech_active_engine_check (activeEngine binding wired: "
          "stop/cancel elvis forms + selectEngine teardown + terminal clears "
          "+ volatile)")
    return 0


if __name__ == "__main__":
    if "--selftest" in sys.argv[1:]:
        sys.exit(selftest())
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(run(sys.argv[1]))
