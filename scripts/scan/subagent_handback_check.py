#!/usr/bin/env python3
"""Sub-agent turn hand-back guard — [deep-audit-1017] FIX-C.

Bug class: `runSubagentLoop` (the spawn_agent loop) may hand a turn BACK to the
model (`continue`) instead of finishing. Two contracts apply to every such
hand-back, and nothing mechanical looked at either:

  1. **Echo** — the turn's VISIBLE text must enter `history` as an ASSISTANT
     message (`appendSubagentTurnText`) before the nudge/reminder is appended,
     that call must RUN, it must receive the turn's text (not a constant, not a
     provably-empty derivation), and the message it appended must still be
     there when the loop continues. `history` is the model's ONLY view of what
     it already wrote; a hand-back that drops it makes the model re-write the
     same prose on the retry, and the sub-agent's persisted result (`resultSb`,
     read by the main agent as its own text) then carries it twice.
  2. **Cancellation** — the loop's terminal `catch (…: Exception)` must be
     preceded by a `CancellationException` clause whose RETHROW is reachable, or
     a user stop is reclassified as "Sub-agent '<x>' encountered an error" (and
     the log blames the model). Same shape as the other executors in the file.

Criterion (scoped to `runSubagentLoop`'s body — the two contracts are about
that loop, not about every catch/continue in the codebase):
  C1: every `continue` token is preceded, since the previous `continue`, by a
      LIVE `appendSubagentTurnText(history, <the turn's text>)` call whose
      append is not undone before the `continue` — or the enclosing hand-back
      carries an exemption marker that is registered in
      `ECHO_EXEMPT_REGISTRY` (a source comment is NOT a licence: an
      unregistered `[no-assistant-echo: …]` is a self-signed exemption and is
      itself a violation).
  C2: every `catch (…: Exception | Throwable)` clause is immediately preceded
      by a `catch (…: CancellationException)` clause with a REACHABLE rethrow.

Structure, constant folding and reachability come from the shared primitives
(`_kotlin_lexer` / `_kotlin_ast` / `_kotlin_valueflow`), not from a gate-local
lexer: a `'"'` char literal followed by `// "` used to unmask a commented-out
call, because the local masker handled `"` and not `'`.

Deliberate non-goals: `catch (e: Exception)` around non-suspending work (e.g.
`persistBrowserArtifact`'s file IO) is NOT a cancellation hazard and is not
flagged; widening the rule repo-wide would bury real hits under that noise.

Usage: python3 scripts/scan/subagent_handback_check.py [repo_root] [--selftest]
Exit:  0 = contracts hold, 1 = violation (or the detector rotted)
"""
import os
import re
import sys
from pathlib import Path

_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

from _kotlin_lexer import KotlinLexError, mask_code  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool,
    const_value,
    function_body,
    match_bracket,
    stmt_spans,
    strip_parens,
)
from _kotlin_valueflow import (  # noqa: E402
    brace_depth,
    call_args,
    guard_feasible,
    path_feasible,
)

TARGET = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatToolExecutors.kt"
LOOP_FN = "runSubagentLoop"
ECHO_FN = "appendSubagentTurnText"
SELFTEST_MARK = "SUBAGENT_HANDBACK_SELFTEST=PASS"

# A hand-back may declare itself exempt — but only by a reason that a human put
# in this registry. Production currently uses none: every site must echo.
# `{reason: max branches with that reason}`.
ECHO_EXEMPT_REGISTRY = {}

ECHO_EXEMPT_RE = re.compile(r"\[no-assistant-echo:\s*([^\]]*)\]")
ECHO_CALL_RE = re.compile(re.escape(ECHO_FN) + r"\s*\(")
CANCELLATION = "CancellationException"
CATCH = re.compile(r"catch\s*\(\s*\w+\s*:\s*([\w.]+)\s*\)")
# Anything that takes the message we just appended back out of `history`.
HISTORY_MUTATOR_RE = re.compile(
    r"\bhistory\s*\.\s*(?:removeAt|removeLast|removeFirst|remove|removeAll|clear|"
    r"retainAll|dropLast|subList|set)\s*\(")


# ── structural helpers (shared primitives, no local lexer) ─────────────────

def enclosing_block_open(code, pos):
    """Index of the `{` opening the block that contains pos, or -1."""
    depth = 0
    i = pos - 1
    while i >= 0:
        c = code[i]
        if c == "}":
            depth += 1
        elif c == "{":
            if depth == 0:
                return i
            depth -= 1
        i -= 1
    return -1


def unconditional_exit_before(code, pos):
    """True when a statement in the SAME block unconditionally leaves the
    function (`return …` / `throw …`) before pos — pos is then unreachable."""
    open_idx = enclosing_block_open(code, pos)
    start = open_idx + 1 if open_idx >= 0 else 0
    seg = code[start:pos]
    for a, b in stmt_spans(seg):
        stmt = seg[a:b].strip()
        if re.match(r"^(?:return|throw)\b", stmt):
            return True
    return False


_INT_DECL = re.compile(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::\s*Int\s*)?=\s*(-?\d+)\b")


def int_domains(code):
    """{name: (lo, hi)} for Int locals that provably keep their sign.

    `var turns = 0` plus `turns++` and no decrement / reset ⇒ turns ∈ [0, ∞),
    so `if (turns < 0) throw e` folds to dead code instead of passing as a
    "conditional rethrow". A variable we cannot bound is simply absent and the
    caller treats its conditions as feasible (fail-open there, by design: a
    condition we cannot fold must not become a violation).
    """
    init = {}
    for m in _INT_DECL.finditer(code):
        init[m.group(1)] = int(m.group(2))
    out = {}
    for name, value in init.items():
        p = re.escape(name)
        if re.search(r"(?<![\w])" + p + r"\s*(?:--|-=)", code) or \
                re.search(r"--\s*" + p + r"(?![\w])", code):
            continue
        ok = True
        for m in re.finditer(r"(?<![\w])" + p + r"\s*=(?!=)", code):
            rhs = code[m.end(): m.end() + 60]
            if re.match(r"\s*" + p + r"\s*[+]", rhs):
                continue
            if re.match(r"\s*" + str(value) + r"\b", rhs):
                continue
            ok = False
            break
        if ok and value >= 0:
            out[name] = (value, None)
    return out


def live_throw(code, pos, domains):
    """True when the `throw` at pos can run: reachable, and not shadowed by an
    unconditional exit earlier in the same block."""
    if not path_feasible(code, pos, domains):
        return False
    if unconditional_exit_before(code, pos):
        return False
    return True


def _tail(body):
    spans = stmt_spans(body)
    return body[spans[-1][0]:spans[-1][1]].strip() if spans else body.strip()


_STOP_IDENTS = {"if", "when", "else", "return", "throw", "true", "false", "null"}


def _root_ident(expr):
    for m in re.finditer(r"[A-Za-z_]\w*", expr):
        if m.group(0) not in _STOP_IDENTS:
            return m.group(0)
    return None


def _arg_text(text, span):
    return text[span[0]:span[1]]


def empty_turn_text_reason(arg2, scope_code):
    """Why `arg2` cannot carry the turn's visible text, or None when it can.

    The pre-fix criterion only rejected a blank ARGUMENT SPELLING (`""` is
    masked to spaces). `text.take(0)` is spelled with a live identifier and
    evaluates to the empty string: `appendSubagentTurnText` then returns false
    at its `isBlank()` early-exit and the model loses the truncated prose.
    """
    e = arg2.strip()
    if not e:
        return "passes an empty or literal turn text"
    const = const_value(strip_parens(e))
    if const is not None and (const[0] == "null" or
                              (const[0] == "str" and const[1].strip() == "")):
        return "passes an empty or literal turn text"
    # `.take(0)` / `.takeLast(0)` / `.substring(0, 0)` / `.slice(0 until 0)`
    for m in re.finditer(
            r"\.\s*(take|takeLast|takeWhile|dropLast|substring|subSequence|slice|"
            r"subList|windowed|chunked)\s*\(", e):
        args = call_args(e, m.end() - 1)
        if not args:
            continue
        first = const_value(strip_parens(_arg_text(e, args[0])))
        zero_first = first is not None and first[0] == "num" and first[1] == 0
        if m.group(1) in ("substring", "subSequence", "subList"):
            if len(args) > 1:
                second = const_value(strip_parens(_arg_text(e, args[1])))
                if zero_first and second is not None and second[0] == "num" \
                        and second[1] == 0:
                    return ("derives an EMPTY string (`%s`) from the turn text — the "
                            "model still sees nothing it wrote"
                            % " ".join(e.split())[:64])
            continue
        if zero_first:
            return ("derives an EMPTY string (`%s`) from the turn text — the model "
                    "still sees nothing it wrote" % " ".join(e.split())[:64])
    # `.filter { false }` / `.takeIf { infeasible }` / `.let { <blank> }`
    for m in re.finditer(r"\.\s*(filter|filterNot|takeIf|takeUnless|let|run)\s*\{", e):
        close = match_bracket(e, m.end() - 1)
        if close < 0:
            continue
        body = e[m.end():close]
        name = m.group(1)
        if name in ("let", "run"):
            inner = empty_turn_text_reason(_tail(body), scope_code)
            if inner:
                return inner
            continue
        cond = body if name != "filterNot" else "!(" + body + ")"
        if const_bool(strip_parens(cond)) is False:
            return ("derives an EMPTY string (`%s` — its predicate can never pass) "
                    "from the turn text" % " ".join(e.split())[:64])
    # And the argument has to come from a value of THIS loop (the turn text):
    # `appendSubagentTurnText(history, skillName)` is spelled live and carries
    # something that was never the turn's visible prose.
    root = _root_ident(e)
    if root is None:
        return ("passes a value with no identifier (`%s`) — the hand-back must echo "
                "the turn's visible text" % " ".join(e.split())[:64])
    if not re.search(r"\b(?:val|var)\s+" + re.escape(root) + r"\b", scope_code):
        return ("`%s` (2nd argument of %s) does not derive from a local of the loop "
                "body, so it is not this turn's visible text (`%s`)"
                % (root, ECHO_FN, " ".join(e.split())[:64]))
    return None


def post_call_mutation(code_window, call_end):
    """`history.removeAt(history.lastIndex)` right after the append: the call
    runs, the message is gone by the time the loop continues."""
    depth = brace_depth(code_window, call_end)
    seg = code_window[call_end:]
    base = call_end
    for m in HISTORY_MUTATOR_RE.finditer(seg):
        pos = base + m.start()
        if brace_depth(code_window, pos) == depth:
            return m.group(0).strip()
    return None


# ── C1 / C2 ───────────────────────────────────────────────────────────────

def check_c1(raw, code, start, end, domains):
    viol = []
    body = code[start:end]
    hits = [m.start() for m in re.finditer(r"\bcontinue\b", body)]
    if not hits:
        return ["ROT: no `continue` found inside %s — the detector stopped matching"
                % LOOP_FN]
    bounds = [0] + [h + len("continue") for h in hits]
    for idx, h in enumerate(hits):
        pos = start + h
        wstart = start + bounds[idx]
        window_code = code[wstart:pos]
        window_raw = raw[wstart:pos]
        line = raw[:pos].count("\n") + 1

        marker = ECHO_EXEMPT_RE.search(window_raw)
        if marker:
            reason = " ".join(marker.group(1).split())
            if reason not in ECHO_EXEMPT_REGISTRY:
                viol.append(
                    "%s:%d — the hand-back carries a self-signed `[no-assistant-echo: "
                    "%s]` exemption that is NOT in ECHO_EXEMPT_REGISTRY: a source "
                    "comment cannot license dropping the turn text (the model then "
                    "re-writes the prose and the sub-agent's result carries it twice)"
                    % (TARGET, line, reason))
            continue

        calls = list(ECHO_CALL_RE.finditer(window_code))
        live = [c for c in calls if path_feasible(window_code, c.start(), domains)]
        if not live:
            if calls:
                viol.append(
                    "%s:%d — the echo call is dead-guarded / unreachable (`%s`) — it "
                    "keeps the call and appends nothing, so the hand-back still drops "
                    "the turn text"
                    % (TARGET, line,
                       " ".join(window_code[:calls[-1].start()].split())[-70:]))
            else:
                viol.append(
                    "%s:%d — `continue` hands the turn back without a live `%s(…)` and "
                    "without a registered `[no-assistant-echo: …]` exemption"
                    % (TARGET, line, ECHO_FN))
            continue

        call = live[-1]
        open_paren = call.end() - 1
        args = call_args(window_code, open_paren)
        if len(args) < 2:
            viol.append(
                "%s:%d — `%s(…)` does not take (history, text, …) anymore "
                "(fail-closed: re-point this arm)" % (TARGET, line, ECHO_FN))
            continue
        arg2 = _arg_text(window_code, args[1])
        reason = empty_turn_text_reason(arg2, body)
        if reason:
            viol.append(
                "%s:%d — `%s(history, %s)` %s"
                % (TARGET, line, ECHO_FN, " ".join(arg2.split())[:64], reason))
            continue
        mut = post_call_mutation(window_code, call.end() - 1)
        if mut:
            viol.append(
                "%s:%d — the echo call is undone before the hand-back (`%s` after the "
                "append) — the model still never sees the turn it just wrote"
                % (TARGET, line, mut))
    return viol


def check_c2(raw, code, start, end, domains):
    viol = []
    body = code[start:end]
    clauses = list(CATCH.finditer(body))
    if not clauses:
        return ["ROT: no catch clause found inside %s — the detector stopped matching"
                % LOOP_FN]
    for m in clauses:
        kind = m.group(1)
        if not (kind.endswith("Exception") or kind.endswith("Throwable")):
            continue
        if kind == CANCELLATION:
            continue
        prev = None
        for p in clauses:
            if p.end() <= m.start():
                prev = p
        ok = False
        if prev is not None and prev.group(1).endswith(CANCELLATION):
            brace = body.find("{", prev.end(), m.start())
            if brace >= 0:
                close = match_bracket(body, brace)
                block = body[brace: close + 1] if close > 0 else body[brace:]
                for t in re.finditer(r"\bthrow\b", block):
                    if live_throw(body, brace + t.start(), domains):
                        ok = True
                        break
        if not ok:
            line = raw[:start + m.start()].count("\n") + 1
            viol.append(
                "%s:%d — `catch (…: %s)` swallows CancellationException (no REACHABLE "
                "`catch (…: %s) {{ throw … }}` clause before it)"
                % (TARGET, line, kind, CANCELLATION))
    return viol


def scan(root):
    path = os.path.join(root, TARGET)
    if not os.path.isfile(path):
        return ["ROT: missing " + TARGET]
    raw = open(path, encoding="utf-8", errors="replace").read()
    try:
        code = mask_code(raw)
    except KotlinLexError as exc:  # fail-closed, never re-scan the raw text
        return ["ROT: %s does not lex (%s) — fail-closed: the hand-back contracts "
                "cannot be judged on an unmasked view" % (TARGET, exc)]
    span = function_body(code, LOOP_FN)
    if span is None:
        return ["ROT: %s not found in %s" % (LOOP_FN, TARGET)]
    start, end = span[0], span[1]
    domains = int_domains(code[start:end])
    return (check_c1(raw, code, start, end, domains)
            + check_c2(raw, code, start, end, domains))


# ── self-test fixtures ──────────────────────────────────────────────────────

GOOD = """package p
internal suspend fun runSubagentLoop() {
    try {
        while (true) {
            val text = "t"
            if (a) {
                appendSubagentTurnText(history, text)
                history.add(USER)
                continue
            }
            if (b) {
                appendSubagentTurnText(history, text)
                history.add(USER)
                continue
            }
            break
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return failure
    }
}
"""

# the loop body with an Int turn counter — `turns` can never go negative
GOOD_TURNS = GOOD.replace(
    "    try {\n        while (true) {\n",
    "    var turns = 0\n    try {\n        while (true) {\n            turns++\n")

MARKED = GOOD.replace(
    "appendSubagentTurnText(history, text)\n                history.add(USER)\n"
    "                continue\n            }\n            break",
    "// [no-assistant-echo: this arm carries no turn text]\n                continue\n"
    "            }\n            break")

NO_ECHO = GOOD.replace(
    "appendSubagentTurnText(history, text)\n                history.add(USER)\n"
    "                continue\n            }\n            if (b)",
    "history.add(USER)\n                continue\n            }\n            if (b)")

NO_CANCEL = GOOD.replace("    } catch (e: CancellationException) {\n        throw e\n", "")
NO_THROW = GOOD.replace("    } catch (e: CancellationException) {\n        throw e\n",
                        "    } catch (e: CancellationException) {\n        return quiet\n")
BAD_COMMENT_TRICK = GOOD.replace(
    "appendSubagentTurnText(history, text)\n                history.add(USER)\n"
    "                continue\n            }\n            if (b)",
    "// appendSubagentTurnText(history,\n                continue\n            }\n"
    "            if (b)")


# [wide-1024 12-misc-b] witness shapes — the call/`throw` stays spelled out and
# the effect is gone.
ECHO_EMPTY = GOOD.replace("appendSubagentTurnText(history, text)",
                          'appendSubagentTurnText(history, "")', 1)
ECHO_DEAD = GOOD.replace("appendSubagentTurnText(history, text)",
                         "if (false) appendSubagentTurnText(history, text)", 1)
CANCEL_DEAD = GOOD.replace(
    "    } catch (e: CancellationException) {\n        throw e\n",
    "    } catch (e: CancellationException) {\n        if (false) throw e\n"
    "        return quiet\n",
)

# ── [batch6 t12] witnesses this wave added ────────────────────────────────
# 05-1: `!true` instead of the literal `false` (the old guard whitelist).
ECHO_NOT_TRUE = GOOD.replace(
    "appendSubagentTurnText(history, text)",
    "if (!true) appendSubagentTurnText(history, text)", 1)
# 05-2: a live identifier that evaluates to the empty string.
ECHO_TAKE0 = GOOD.replace("appendSubagentTurnText(history, text)",
                          "appendSubagentTurnText(history, text.take(0))", 1)
# 05-2 variant: some other in-scope identifier (never the turn's visible text).
ECHO_FOREIGN = GOOD.replace("appendSubagentTurnText(history, text)",
                            "appendSubagentTurnText(history, skillName)", 1)
# 05-3: the append is undone in the same statement.
ECHO_POPPED = GOOD.replace(
    "appendSubagentTurnText(history, text)",
    "if (appendSubagentTurnText(history, text)) history.removeAt(history.lastIndex)", 1)
# 05-4: an "impossible" counter guard instead of the literal `false`.
CANCEL_TURNS = GOOD_TURNS.replace(
    "    } catch (e: CancellationException) {\n        throw e\n",
    "    } catch (e: CancellationException) {\n        if (turns < 0) throw e\n"
    "        return quiet\n")
# 05-5: the gate's own self-signed exemption marker, no registry entry.
ECHO_SELF_SIGNED = GOOD.replace(
    "appendSubagentTurnText(history, text)\n                history.add(USER)\n"
    "                continue\n            }\n            break",
    "// [no-assistant-echo: bypass]\n                continue\n            }\n            break")
# 05-6: char-literal parser death (`'"'` then `// "` unmasked a commented call).
ECHO_CHAR_TRICK = GOOD.replace(
    "appendSubagentTurnText(history, text)",
    "val quote = '\"' // \" appendSubagentTurnText(history, text)", 1)


def _run_fixture(src, root):
    path = os.path.join(root, TARGET)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(src)
    return scan(root)


def selftest():
    import shutil
    import tempfile

    cases = [
        ("compliant fixture", GOOD, 0, None),
        ("missing echo call", NO_ECHO, 1, "hands the turn back without"),
        ("cancellation catch removed", NO_CANCEL, 1, "swallows CancellationException"),
        ("cancellation catch does not rethrow", NO_THROW, 1,
         "swallows CancellationException"),
        ("echo call only in a comment", BAD_COMMENT_TRICK, 1,
         "hands the turn back without"),
        ("echo passes an empty turn text", ECHO_EMPTY, 1,
         "passes an empty or literal turn text"),
        ("echo call dead-guarded", ECHO_DEAD, 1, "dead-guarded"),
        ("cancellation rethrow dead-guarded", CANCEL_DEAD, 1,
         "swallows CancellationException"),
        # ── batch6 t12 ──
        ("echo guarded by `!true` (not a literal false)", ECHO_NOT_TRUE, 1,
         "dead-guarded"),
        ("echo passes `text.take(0)`", ECHO_TAKE0, 1, "derives an EMPTY string"),
        ("echo passes a foreign identifier", ECHO_FOREIGN, 1,
         "does not derive from a local of the loop body"),
        ("echo append popped right after the call", ECHO_POPPED, 1,
         "is undone before the hand-back"),
        ("cancellation rethrow behind `turns < 0`", CANCEL_TURNS, 1,
         "swallows CancellationException"),
        ("self-signed [no-assistant-echo:] marker", ECHO_SELF_SIGNED, 1,
         "NOT in ECHO_EXEMPT_REGISTRY"),
        ("char-literal parser death unmasks the call", ECHO_CHAR_TRICK, 1,
         "hands the turn back without"),
    ]
    bad = 0
    ok = True
    for name, src, want, needle in cases:
        root = tempfile.mkdtemp(prefix="handback-selftest-")
        try:
            viol = _run_fixture(src, root)
        finally:
            shutil.rmtree(root, ignore_errors=True)
        got = 1 if viol else 0
        hit = needle is None or any(needle in v for v in viol)
        if got != want or not hit:
            bad += 1
            print("  [FAIL] %s: exit=%d (want %d) %s" % (name, got, want, viol[:1]))
        else:
            print("  [ok ] %s: exit=%d %s" % (name, got, viol[:1]))
    # the exemption MECHANISM still works when the reason is registered — the
    # registry is the only licence, and this arm is the negative control for it.
    ECHO_EXEMPT_REGISTRY["this arm carries no turn text"] = 1
    root = tempfile.mkdtemp(prefix="handback-selftest-")
    try:
        viol = _run_fixture(MARKED, root)
    finally:
        shutil.rmtree(root, ignore_errors=True)
        ECHO_EXEMPT_REGISTRY.pop("this arm carries no turn text", None)
    if viol:
        bad += 1
        print("  [FAIL] registered marker exemption rejected: %s" % viol[:1])
    else:
        print("  [ok ] registered marker exemption accepted: exit=0")
    if bad:
        print("SELFTEST FAILED (%d/%d)" % (bad, len(cases) + 1))
        return 1
    print("SELFTEST OK (%d arms) %s" % (len(cases) + 1, SELFTEST_MARK))
    return 0


def main():
    if "--selftest" in sys.argv:
        return selftest()
    argv = [a for a in sys.argv[1:] if not a.startswith("-")]
    root = os.path.abspath(
        argv[0] if argv else os.path.join(os.path.dirname(__file__), "..", ".."))
    viol = scan(root)
    if viol:
        print("Sub-agent hand-back contract violations:")
        for v in viol:
            print("  ✗ %s" % v)
        return 1
    raw = open(os.path.join(root, TARGET), encoding="utf-8", errors="replace").read()
    code = mask_code(raw)
    start, end = function_body(code, LOOP_FN)[:2]
    n = len(re.findall(r"\bcontinue\b", code[start:end]))
    print("OK: runSubagentLoop's %d hand-back(s) echo the turn text and rethrow "
          "cancellation" % n)
    return 0


if __name__ == "__main__":
    sys.exit(main())
