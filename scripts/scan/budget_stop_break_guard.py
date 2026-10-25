#!/usr/bin/env python3
r"""
Guard: a `break` that ends a RUN must break to the run's loop, not to the
inner retry loop.

The regression this exists for (audit3 F-02, fixed 2026-10-15): the agent
loop's two execution-budget gates live INSIDE the turn body's retry loop

    turnLoop@ for (turn in 0 until maxTurnsThisRun) {
        …
        while (!collectDone) {
            if (budget.isExpired()) { … ; break }          # bare `break`
            if (!consumeProviderAttempt()) { … ; break }   # bare `break`

Those `break`s only left the retry `while`; the turn then fell through to the
"the turn's stream completed without error" tail, which
  * recorded RouteOutcome.Success for the member whose attempt budget had just
    refused it (clearing that member's cooldown / circuit state), and
  * appended an empty ASSISTANT entry to agentHistory,
while the remaining turns burned against a reducer already parked in
FINALIZING (field log 2026-09-07: "3 empty turns burned in 4ms against a
refused provider-attempt gate").

Everything compiled and every existing gate passed: none of them compares
WHICH loop a `break` exits with what the branch claims to be doing.

Criterion (mechanical — character-level brace spans + loop nesting, no regex
judgement calls, nothing anchored to a single line):

  1. Candidate = a statement assigning `t7BudgetStopReason = "<string>"`, the
     engine's "this run is over" marker. (`= null` is the reset at run end and
     is not a candidate.)
  2. The branch's block = from that assignment to the close of the brace block
     that contains it (character depth, not indentation).
  3. Walk the `break` TOKENS in that block, in source order (2026-10-25: the old
     line-shaped `^\\s*break$` missed `if (true) break`, `x; break`, and any
     chain written with the break inside an expression — witness C2/anchor).
     A loop that STARTS inside the branch and also ends inside it is a nested
     scan loop; its breaks are skipped.
  4. The first remaining `break` is the branch's run-ending transfer. Count the
     loops enclosing it:
     * < 2  → a bare `break` is correct: it already leaves the outermost loop.
              This is the shape of the turn-entry deadline / turn_limit gates,
              which sit at for-body depth.
     * >= 2 → it MUST carry the OUTERMOST enclosing loop's label
              (e.g. `break@turnLoop`). Anything else — a bare `break`, or a label
              for a loop in between — leaves the run running.
  5. If the outermost enclosing loop has no label at all, that is a violation
     too — there is nothing to break to.

Loop headers are located structurally: `while`/`for` + balanced parentheses +
brace block, on any line and split across lines (witness C3/parser-death: the
old `^\s*(label@)?(while|for)\s*\(.*\)\s*\{\s*$` only saw one-line headers, so a
wrapped header erased the loop from the nesting count and a bare `break` read as
"already leaves the outermost loop").

Fail-closed: a file with budget-stop candidates but no parsable loop / an
unbalanced brace / an undeterminable block end reports a violation instead of
checking nothing.

Usage: python3 scripts/scan/budget_stop_break_guard.py <repo_root>
       python3 scripts/scan/budget_stop_break_guard.py --selftest
Exit:  0 = clean, 1 = violation(s)
"""
import os
import re
import sys

KOTLIN_APP = "src/android/app/src/main/java"

ASSIGN_RE = re.compile(r"\bt7BudgetStopReason\s*=\s*\"")
ASSIGN_MARKER = "t7BudgetStopReason"
LOOP_HEAD_RE = re.compile(r"(?:(?P<label>[A-Za-z_]\w*)\s*@\s*)?\b(?P<kind>while|for)\s*\(")
BREAK_RE = re.compile(r"(?<![\w@$.])break\b(?:@(?P<label>[A-Za-z_]\w*))?")


def strip_code_text(text):
    """Blank comments and string/char literal CONTENTS, keeping every offset.

    Offsets must survive: the whole criterion is about positions (which loop
    encloses which break). Newlines are preserved so offsets map to lines.
    """
    out = list(text)
    i, n = 0, len(text)
    in_line = in_block = in_str = in_char = False
    while i < n:
        ch = text[i]
        two = text[i:i + 2]
        if in_line:
            if ch == "\n":
                in_line = False
            else:
                out[i] = " "
            i += 1
            continue
        if in_block:
            if two == "*/":
                out[i] = out[i + 1] = " "
                in_block = False
                i += 2
                continue
            if ch != "\n":
                out[i] = " "
            i += 1
            continue
        if in_str or in_char:
            if ch == "\\":
                out[i] = " "
                if i + 1 < n and text[i + 1] != "\n":
                    out[i + 1] = " "
                i += 2
                continue
            if (in_str and ch == '"') or (in_char and ch == "'"):
                in_str = in_char = False
                i += 1
                continue
            if ch != "\n":
                out[i] = " "
            i += 1
            continue
        if two == "//":
            in_line = True
            out[i] = out[i + 1] = " "
            i += 2
            continue
        if two == "/*":
            in_block = True
            out[i] = out[i + 1] = " "
            i += 2
            continue
        if ch == '"':
            in_str = True
            i += 1
            continue
        if ch == "'":
            in_char = True
            i += 1
            continue
        i += 1
    return "".join(out)


def match_paren(text, open_idx):
    """Index just past the `)` closing the `(` at `open_idx` (or -1)."""
    depth = 0
    for k in range(open_idx, len(text)):
        if text[k] == "(":
            depth += 1
        elif text[k] == ")":
            depth -= 1
            if depth == 0:
                return k + 1
    return -1


def match_brace(text, open_idx):
    """Index of the `}` closing the `{` at `open_idx` (or -1)."""
    depth = 0
    for k in range(open_idx, len(text)):
        if text[k] == "{":
            depth += 1
        elif text[k] == "}":
            depth -= 1
            if depth == 0:
                return k
    return -1


def loop_spans(text):
    """[(start, end, label, kind)] for every `while`/`for` with its body.

    `start` is the header's first character (the label, when present); `end` is
    the body's closing brace. A header wrapped across lines is fine — the parens
    and the brace are matched structurally.
    """
    spans = []
    for m in LOOP_HEAD_RE.finditer(text):
        close_paren = match_paren(text, m.end() - 1)
        if close_paren < 0:
            continue
        k = close_paren
        while k < len(text) and text[k] in " \t\r\n":
            k += 1
        if k >= len(text):
            continue
        if text[k] == "{":
            end = match_brace(text, k)
            if end < 0:
                continue
        else:
            # Unbraced body: extend to the end of the next statement.
            end = text.find("\n", k)
            if end < 0:
                end = len(text)
        spans.append((m.start(), end, m.group("label"), m.group("kind")))
    return spans


def block_end(text, pos):
    """Index of the `}` closing the block that contains `pos` (or -1)."""
    depth = 0
    for k in range(0, pos):
        if text[k] == "{":
            depth += 1
        elif text[k] == "}":
            depth -= 1
    if depth <= 0:
        return -1
    target = depth - 1
    depth = depth
    for k in range(pos, len(text)):
        if text[k] == "{":
            depth += 1
        elif text[k] == "}":
            depth -= 1
            if depth == target:
                return k
    return -1


def analyse(path):
    """Return (violations, stats) for one Kotlin file."""
    try:
        with open(path, encoding="utf-8") as f:
            raw = f.read()
    except OSError as e:
        return ([{"line": 0, "break": "-", "assign_line": 0, "loop_count": 0,
                  "outermost": None, "reason": f"unreadable ({type(e).__name__}: {e})"}],
                {"candidates": 0, "loops": 0})

    text = strip_code_text(raw)
    lines = text.split("\n")
    loops = loop_spans(text)

    def line_of(pos):
        return text.count("\n", 0, pos) + 1

    candidates = [m for m in ASSIGN_RE.finditer(text)]
    if candidates and not loops:
        return ([{"line": 0, "break": "-", "assign_line": line_of(candidates[0].start()),
                  "loop_count": 0, "outermost": None,
                  "reason": "no loop header could be parsed in this file — the "
                            "nesting criterion cannot run"}], {"candidates": 0, "loops": 0})

    violations = []
    for m in candidates:
        assign_pos = m.start()
        end = block_end(text, assign_pos)
        if end < 0:
            violations.append({
                "line": line_of(assign_pos), "break": "-",
                "assign_line": line_of(assign_pos), "loop_count": 0,
                "outermost": None,
                "reason": "the brace block holding this budget stop could not be "
                          "delimited (unbalanced braces?) — fail-closed",
            })
            continue
        decisive = None
        for b in BREAK_RE.finditer(text, m.end(), end):
            bpos = b.start()
            enclosing = [lp for lp in loops if lp[0] <= bpos <= lp[1]]
            # A loop that both starts and ends inside this branch is a local scan
            # loop, not the run loop: its own `break`s say nothing about the run.
            inner = [lp for lp in enclosing if lp[0] > assign_pos and lp[1] <= end]
            if inner and enclosing[-1] in inner:
                continue
            decisive = (b, bpos, enclosing)
            break
        if decisive is None:
            continue  # branch does not claim to end the run (e.g. returns)

        b, bpos, enclosing = decisive
        break_label = b.group("label")
        outermost = enclosing[0] if enclosing else None
        v = {
            "line": line_of(bpos),
            "break": b.group(0),
            "assign_line": line_of(assign_pos),
            "loop_count": len(enclosing),
            "outermost": ((line_of(outermost[0]), line_of(outermost[1]), outermost[2])
                          if outermost else None),
        }
        if len(enclosing) < 2:
            continue  # a bare break already leaves the outermost loop
        if outermost is None or not outermost[2]:
            v["reason"] = "the outermost enclosing loop carries no label"
            violations.append(v)
            continue
        if break_label != outermost[2]:
            v["reason"] = ("the `break` must be `break@%s`, not `%s`"
                           % (outermost[2], b.group(0)))
            violations.append(v)

    return violations, {"candidates": len(candidates), "loops": len(loops)}


def report(root, path, violations):
    rel = os.path.relpath(path, root)
    for v in violations:
        print(f"❌ {rel}:{v['line']}")
        if v["break"] == "-":
            print(f"   {v['reason']}.")
            print("")
            continue
        print(
            f"   `{v['break']}` ends the branch opened by the budget stop at "
            f"line {v['assign_line']}, but it is nested in "
            f"{v['loop_count']} loops — it leaves only the innermost one."
        )
        print(f"   {v['reason']}.")
        if v["outermost"]:
            print(
                f"   outermost enclosing loop: line {v['outermost'][0]}"
                + (f", label `{v['outermost'][2]}`" if v["outermost"][2] else ", NO label")
                + f" (span ends line {v['outermost'][1]})"
            )
        print("   consequence if left as-is: the turn body runs on after the")
        print("   budget stop — the success tail records a route Success for the")
        print("   refused member and appends an empty assistant entry, while the")
        print("   remaining turns burn against a reducer already in FINALIZING.")
        print("")
        print(f"   fix: `break@{v['outermost'][2] if v['outermost'] and v['outermost'][2] else 'turnLoop'}`"
              " (and label the enclosing loop if it has none).")
        print("")


def run(root):
    app_dir = os.path.join(root, KOTLIN_APP)
    if not os.path.isdir(app_dir):
        print(f"❌ kotlin source root not found: {app_dir}")
        return 1
    files = []
    for dirpath, _, fns in os.walk(app_dir):
        for fn in fns:
            if fn.endswith(".kt"):
                files.append(os.path.join(dirpath, fn))
    files.sort()
    if not files:
        print(f"❌ no .kt file under {app_dir} — the scan surface collapsed to an "
              "empty set (fail-closed)")
        return 1

    total_violations = 0
    total_candidates = 0
    for path in files:
        violations, stats = analyse(path)
        total_candidates += stats.get("candidates", 0)
        if violations:
            total_violations += len(violations)
            report(root, path, violations)

    if total_violations:
        print(f"❌ budget stop break guard: {total_violations} violation(s)")
        return 1
    print(
        f"✅ budget stop break guard: {total_candidates} budget-stop assignment(s), "
        "every run-ending `break` targets the outermost loop"
    )
    return 0


# ── selftest ────────────────────────────────────────────────────────────────
HEAD = "package com.rikkaminis.app.ui.chat\nclass Loop {\n    fun run(maxTurns: Int) {\n"
TAIL = "    }\n}\n"


def _fixture(body):
    return _tree(HEAD + body + TAIL)


def _tree(text, name="Loop.kt"):
    return {"src/android/app/src/main/java/com/rikkaminis/app/ui/chat/" + name: text}


def selftest():
    cases = [
        # 1. the production shape: turn loop labelled, retry while inside, labelled break
        ("labelled break out of a nested budget gate",
         _fixture(
             "        turnLoop@ for (turn in 0 until maxTurns) {\n"
             "            while (!done) {\n"
             "                if (!budget.consume()) {\n"
             '                    traceObserver.t7BudgetStopReason = "provider_attempt_limit"\n'
             "                    break@turnLoop\n"
             "                }\n"
             "            }\n"
             "        }\n"), 0, None),
        # 2. turn-entry gate at for-body depth: a bare break already leaves the run
        ("turn-entry gate at for-body depth",
         _fixture(
             "        turnLoop@ for (turn in 0 until maxTurns) {\n"
             "            if (budget.isExpired()) {\n"
             '                traceObserver.t7BudgetStopReason = "deadline_reached"\n'
             "                break\n"
             "            }\n"
             "        }\n"), 0, None),
        # 3. the original F-02 regression
        ("bare break out of the retry loop",
         _fixture(
             "        turnLoop@ for (turn in 0 until maxTurns) {\n"
             "            while (!done) {\n"
             "                if (!budget.consume()) {\n"
             '                    traceObserver.t7BudgetStopReason = "provider_attempt_limit"\n'
             "                    break\n"
             "                }\n"
             "            }\n"
             "        }\n"), 1, "break@turnLoop"),
        # 4. enclosing loop unlabelled
        ("nested break with an unlabelled enclosing loop",
         _fixture(
             "        for (turn in 0 until maxTurns) {\n"
             "            while (!done) {\n"
             "                if (!budget.consume()) {\n"
             '                    traceObserver.t7BudgetStopReason = "provider_attempt_limit"\n'
             "                    break\n"
             "                }\n"
             "            }\n"
             "        }\n"), 1, "carries no label"),
        # 5. wide3 witness C2 (anchor): `if (true) break` is not line-shaped
        ("break hidden behind `if (true)` (witness C2)",
         _fixture(
             "        turnLoop@ for (turn in 0 until maxTurns) {\n"
             "            while (!done) {\n"
             "                if (budget.isExpired()) {\n"
             '                    traceObserver.t7BudgetStopReason = "deadline_reached"\n'
             "                    if (true) break\n"
             "                }\n"
             "            }\n"
             "        }\n"), 1, "break@turnLoop"),
        # 6. wide3 witness C3 (order): real bare break, dead labelled break below
        ("bare break then unreachable labelled break (witness C3 order)",
         _fixture(
             "        turnLoop@ for (turn in 0 until maxTurns) {\n"
             "            while (!done) {\n"
             "                if (!budget.consume()) {\n"
             '                    traceObserver.t7BudgetStopReason = "provider_attempt_limit"\n'
             "                    collectDone = true; break\n"
             "                    break@turnLoop\n"
             "                }\n"
             "            }\n"
             "        }\n"), 1, "break@turnLoop"),
        # 7. wide3 witness C3 (parser-death): loop header wrapped across lines
        ("wrapped loop header still counts as nesting (witness C3 parser-death)",
         _fixture(
             "        turnLoop@ for (turn in 0 until maxTurns) {\n"
             "            while (\n"
             "                !done\n"
             "            ) {\n"
             "                if (budget.isExpired()) {\n"
             '                    traceObserver.t7BudgetStopReason = "deadline_reached"\n'
             "                    break\n"
             "                }\n"
             "            }\n"
             "        }\n"), 1, "break@turnLoop"),
        # 8. a local scan loop inside the branch must not be mistaken for the run loop
        ("local scan loop inside the branch is not the run loop",
         _fixture(
             "        turnLoop@ for (turn in 0 until maxTurns) {\n"
             "            while (!done) {\n"
             "                if (budget.isExpired()) {\n"
             '                    traceObserver.t7BudgetStopReason = "deadline_reached"\n'
             "                    for (m in members) {\n"
             "                        if (m.dead) break\n"
             "                    }\n"
             "                    break@turnLoop\n"
             "                }\n"
             "            }\n"
             "        }\n"), 0, None),
        # 9. out of remit: the branch returns instead of ending the run
        ("branch that returns instead of breaking",
         _fixture(
             "        turnLoop@ for (turn in 0 until maxTurns) {\n"
             "            while (!done) {\n"
             "                if (!budget.consume()) {\n"
             '                    traceObserver.t7BudgetStopReason = "tool_call_limit"\n'
             '                    return "budget exhausted"\n'
             "                }\n"
             "            }\n"
             "        }\n"), 0, None),
        # 10. fail-closed: candidates but no parsable loop
        ("budget stop with no parsable loop (fail-closed)",
         _tree("package com.rikkaminis.app.ui.chat\n"
               "fun f() {\n"
               '    traceObserver.t7BudgetStopReason = "deadline_reached"\n'
               "}\n"), 1, "no loop header"),
    ]
    import shutil
    import tempfile
    failures = []
    for label, files, want_rc, want_text in cases:
        d = tempfile.mkdtemp(prefix="bsbg-selftest-")
        try:
            for rel, body in files.items():
                p = os.path.join(d, rel)
                os.makedirs(os.path.dirname(p), exist_ok=True)
                with open(p, "w", encoding="utf-8") as f:
                    f.write(body)
            rc, out = _run(d)
            ok = rc == want_rc and (want_text is None or want_text in out)
            if not ok:
                failures.append(f"{label}: rc={rc} want={want_rc}\n{out}")
        finally:
            shutil.rmtree(d, ignore_errors=True)
    if failures:
        print("❌ budget_stop_break_guard selftest:")
        for f in failures:
            print(f"   • {f}")
        return 1
    print(f"BUDGET_STOP_BREAK_SELFTEST=PASS ({len(cases)} fixtures)")
    return 0


def _run(root):
    import contextlib
    import io
    buf = io.StringIO()
    try:
        with contextlib.redirect_stdout(buf):
            rc = run(root)
        return rc, buf.getvalue()
    except Exception as e:
        return 1, f"❌ budget_stop_break_guard raised: {type(e).__name__}: {e}"


def main():
    if len(sys.argv) < 2:
        print("usage: budget_stop_break_guard.py <repo_root> | --selftest")
        return 2
    if sys.argv[1] == "--selftest":
        return selftest()
    root = os.path.abspath(sys.argv[1])
    return run(root)


if __name__ == "__main__":
    sys.exit(main())
