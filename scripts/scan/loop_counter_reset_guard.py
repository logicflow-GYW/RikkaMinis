#!/usr/bin/env python3
"""
Guard: a counter reset that CLAIMS tool-call semantics must sit inside a
tool-call guard.

The regression this exists for (audit2 BUG-01, fixed 2026-10-15): the three
continuation counters of the agent loop

    loopState.lengthWallContinues
    loopState.deterministicEmptyStreak
    loopState.eofStubContinues

were reset to 0 ~640 lines ABOVE the `if (toolCalls.isEmpty()) {` branch, at a
spot every turn passes through. Their comments said "A tool-call turn clears
…", but the statements executed unconditionally — so each counter was pinned to
0 before it could reach its limit and the length-wall / deterministic-empty /
EOF-stub guards were all dead. Only `maxTurns` (default 256) still bounded the
loop. Nothing caught it: the code compiled, the comments read correctly, and
`scan.sh` had no gate that compares a statement's EXECUTION position with the
semantics its comment claims.

Criterion (mechanical, brace-depth based — no regex judgement calls):

  1. Candidate = a statement `loopState.<name> = <numeric expression>` whose
     enclosing comment block matches `tool[- ]call` — i.e. the comment claims
     "this happens on a tool-call turn". The block is walked up to the nearest
     line that CLOSES a brace: blank lines, sibling resets and ordinary
     statements in between do not detach the claim (wide3 C1_candidate_detect:
     inserting a `Unit` line between the comment and the resets used to make
     them invisible, which is the same defect the comment is describing).
     The right-hand side is read as an EXPRESSION of literals, so `0 + 0` /
     `(0)` / `0; other = 0` are the same reset (wide3 C3_empty_guard_depth).
  2. Such a statement MUST fall strictly inside the brace span of a positive
     `toolCalls` guard: `if (toolCalls.isNotEmpty())` / `if (!toolCalls.isEmpty())`
     / `if (toolCalls.any())` / `if (toolCalls.size > 0)`, as computed from the
     header line and its closing brace. A guard whose condition a literal
     short-circuits (`if (true || toolCalls.isNotEmpty())`, `if (false &&
     …)`) is NOT a guard — it decides nothing (wide3 C2_positive_guard).
  3. Anything else is a violation, reported with file:line and the depth of the
     nearest `if (toolCalls.isEmpty())` header (depth equality is exactly the
     old bug shape).
  4. Fail-closed: if not a single Kotlin file in the tree mentions `toolCalls`,
     the scan surface is empty (wrong root / moved code) and that is a
     violation, never a silent "0 violations".

Usage: python3 scripts/scan/loop_counter_reset_guard.py <repo_root>
       python3 scripts/scan/loop_counter_reset_guard.py --selftest
Exit:  0 = clean, 1 = violation(s)
"""
import os
import re
import sys

KOTLIN_APP = "src/android/app/src/main/java"

# `= <expression of numeric literals>` — `0`, `0 + 0`, `(0)`, `0L`. The old
# pattern pinned the line to `= <digits>` with an optional `//` comment, so a
# cosmetic `0 + 0` (or a block comment, or a second statement on the line) made
# the reset invisible to the gate.
RESET_RE = re.compile(r"^loopState\.(\w+)\s*=\s*([-+*/()\d_\s]+)$")
LITERAL_EXPR_RE = re.compile(r"^[\d_\s+\-*/()]+$")
GUARD_RE = re.compile(r"^\s*if\s*\((.*)\)\s*\{\s*(?://.*)?$")
EMPTY_GUARD_RE = re.compile(r"^\s*if\s*\(\s*toolCalls\.isEmpty\(\)\s*\)\s*\{")
SIBLING_RESET_RE = re.compile(r"^\s*loopState\.\w+\s*=\s*\d+\s*$")
TOOLCALL_CLAIM_RE = re.compile(r"tool[-\s]?call", re.IGNORECASE)
SELFTEST_MARK = "LOOP_COUNTER_RESET_SELFTEST=PASS"

WALK_LIMIT = 120  # lines a claim comment may sit above its reset


def strip_code(line):
    """Drop string literals, char literals and trailing // comments."""
    out = []
    i = 0
    in_str = in_char = False
    while i < len(line):
        c = line[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if in_char:
            if c == "\\":
                i += 2
                continue
            if c == "'":
                in_char = False
            i += 1
            continue
        if c == '"':
            in_str = True
            i += 1
            continue
        if c == "'":
            in_char = True
            i += 1
            continue
        if line.startswith("//", i):
            break
        out.append(c)
        i += 1
    return "".join(out)


def clean_lines(src):
    """Strip block comments (stateful across lines) then per-line code."""
    out = []
    in_block = False
    for raw in src:
        code = raw
        if in_block:
            if "*/" in code:
                code = code.split("*/", 1)[1]
                in_block = False
            else:
                code = ""
        while "/*" in code:
            before, rest = code.split("/*", 1)
            if "*/" in rest:
                code = before + rest.split("*/", 1)[1]
            else:
                code = before
                in_block = True
                break
        out.append(strip_code(code))
    return out


def split_statements(line):
    """`loopState.a = 0; loopState.b = 0` is two resets, not one unmatchable line."""
    return [s for s in line.split(";") if s.strip()]


def always_true(cond):
    """A literal `true` disjunct makes the condition uncondition – spelling kept,
    decision gone (`if (true || toolCalls.isNotEmpty())`)."""
    depth, i = 0, 0
    while i < len(cond):
        ch = cond[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif depth == 0:
            if re.match(r"\|\|", cond[i:]):
                before = cond[:i].split("||")[-1].strip().strip("()")
                if before in ("true", "1"):
                    return True
            if re.match(r"&&", cond[i:]):
                before = cond[:i].split("&&")[-1].strip().strip("()")
                if before in ("false",):
                    return True
        i += 1
    flat = " ".join(cond.split())
    if flat in ("true", "1", "(true)"):
        return True
    # `toolCalls.isEmpty() || toolCalls.isNotEmpty()` — a tautology spelled out
    if "isEmpty()" in flat and "isNotEmpty()" in flat and "&&" not in flat:
        return True
    return False


def is_positive_toolcalls_guard(cond):
    if "toolCalls" not in cond:
        return False
    if always_true(cond):
        return False
    if "isNotEmpty" in cond:
        return True
    if "!" in cond and "isEmpty()" in cond:
        return True
    if ".any(" in cond or "size > 0" in cond or "size >= 1" in cond:
        return True
    return False


def claim_above(src, clean, i):
    """The contiguous comment block above line i (0-based), walked up to the
    nearest line that closes a brace. Blank lines, sibling numeric resets and
    ordinary statements in the same block are skipped — a claim cannot be
    detached from the statement it describes by inserting one `Unit` line."""
    comments = []
    j = i - 1
    steps = 0
    while j >= 0 and steps < WALK_LIMIT:
        s = src[j].strip()
        # A brace — opening or closing — delimits the block the statement lives
        # in. Walking past one would attribute a comment that introduces a
        # DIFFERENT block (e.g. the 20-line KDoc of the enclosing `if`).
        if "}" in clean[j] or "{" in clean[j]:
            break
        if s == "":
            j -= 1
            steps += 1
            continue
        if s.startswith("//") or s.startswith("/*") or s.startswith("*"):
            comments.append(s)
        j -= 1
        steps += 1
    return " ".join(comments)


def analyse_lines(src):
    """Return (violations, stats) for one Kotlin file's lines."""
    clean = clean_lines(src)

    start_depth, delta = [], []
    d = 0
    for c in clean:
        start_depth.append(d)
        delta.append(c.count("{") - c.count("}"))
        d += delta[-1]
    after = [start_depth[i] + delta[i] for i in range(len(clean))]

    guard_spans = []
    empty_guard_depths = []
    for i, c in enumerate(clean):
        m = GUARD_RE.match(c)
        if not m:
            continue
        line_no = i + 1
        if EMPTY_GUARD_RE.match(c):
            empty_guard_depths.append((line_no, start_depth[i]))
            continue
        if not is_positive_toolcalls_guard(m.group(1)):
            continue
        close = None
        for k in range(i + 1, len(clean)):
            if after[k] == start_depth[i]:
                close = k + 1
                break
        guard_spans.append((line_no, close if close is not None else len(clean)))

    violations = []
    candidates = 0
    for i, c in enumerate(clean):
        for seg in split_statements(c):
            m = RESET_RE.match(seg.strip())
            if not m:
                continue
            expr = m.group(2)
            if not re.search(r"\d", expr) or not LITERAL_EXPR_RE.match(expr):
                continue
            comments = claim_above(src, clean, i)
            if not comments or not TOOLCALL_CLAIM_RE.search(comments):
                continue
            candidates += 1
            line_no = i + 1
            inside = any(h <= line_no <= c2 for (h, c2) in guard_spans)
            if inside:
                continue
            violations.append(
                {
                    "line": line_no,
                    "statement": (seg.strip() or src[i].strip()),
                    "counter": m.group(1),
                    "depth": start_depth[i],
                    "empty_guard": [
                        (ln, dp) for (ln, dp) in empty_guard_depths if dp == start_depth[i]
                    ],
                    "guards": guard_spans,
                }
            )

    return violations, {"candidates": candidates, "guards": guard_spans}


def analyse(path):
    try:
        with open(path, encoding="utf-8") as f:
            src = f.read().split("\n")
    except OSError as e:
        return [], {"error": str(e), "candidates": 0}
    return analyse_lines(src)


def report(root, files, verbose=True):
    total_violations = 0
    total_candidates = 0
    saw_toolcalls = False
    for path in files:
        violations, stats = analyse(path)
        total_candidates += stats.get("candidates", 0)
        try:
            with open(path, encoding="utf-8") as f:
                if "toolCalls" in f.read():
                    saw_toolcalls = True
        except OSError:
            pass
        for v in violations:
            total_violations += 1
            rel = os.path.relpath(path, root)
            print(f"❌ {rel}:{v['line']}")
            print(f"   statement: {v['statement']}")
            print(
                "   its comment block claims tool-call semantics, but the "
                f"statement is not inside a `toolCalls` positive guard "
                f"(brace depth {v['depth']})."
            )
            for ln, dp in v["empty_guard"]:
                print(
                    f"   depth {dp} is the same depth as `if (toolCalls.isEmpty())` "
                    f"at line {ln} — i.e. the statement runs EVERY turn, "
                    "including the turns the comment says it should skip."
                )
            if not v["guards"]:
                print(
                    "   no `if (toolCalls.isNotEmpty())` / `if (!toolCalls.isEmpty())` "
                    "guard exists in this file at all."
                )
            print("   fix: move the reset inside the tool-call branch, or wrap it in")
            print("        `if (toolCalls.isNotEmpty()) { … }`.")
            print("")

    # Fail-closed: an empty scan surface (wrong root, moved package, renamed
    # dispatcher) must never read as "0 violations".
    if not saw_toolcalls:
        print("❌ loop counter reset guard: no Kotlin file in the tree mentions `toolCalls` —")
        print("   the scan surface is empty (wrong root or the loop moved); refusing to pass.")
        return 1

    if total_violations:
        print(f"❌ loop counter reset guard: {total_violations} violation(s)")
        return 1
    print(
        f"✅ loop counter reset guard: {total_candidates} tool-call-claimed reset(s), "
        "all inside a tool-call guard"
    )
    return 0


def kotlin_files(root):
    app_dir = os.path.join(root, KOTLIN_APP)
    if not os.path.isdir(app_dir):
        return None
    files = []
    for dirpath, _, fns in os.walk(app_dir):
        for fn in fns:
            if fn.endswith(".kt"):
                files.append(os.path.join(dirpath, fn))
    files.sort()
    return files


# ─── selftest: in-memory fixtures, one per criterion ───────────────────────

HEAD = """package com.rikkaminis.app.ui.chat
class LoopState {
    var lengthWallContinues = 0
    var deterministicEmptyStreak = 0
    var eofStubContinues = 0
}
class Loop {
    fun turn(loopState: LoopState, toolCalls: List<String>) {
"""
TAIL = """        AppLogger.info(TAG_STREAM, "turn done")
    }
}
"""
EMPTY_GUARD = """        if (toolCalls.isEmpty()) {
            return
        }
"""
CLAIM = """        // [feat/hermes-tier1] A tool-call turn clears the length-wall
        // continuation budget: the model recovered and is doing new work.
"""
RESETS = """        loopState.lengthWallContinues = 0
        loopState.deterministicEmptyStreak = 0
        loopState.eofStubContinues = 0
"""


def _src(body):
    return (HEAD + body + TAIL).split("\n")


def selftest(_root):
    arms = [
        # (name, body, expected violation count)
        ("guarded resets are clean", EMPTY_GUARD
         + "        if (toolCalls.isNotEmpty()) {\n" + CLAIM + RESETS + "        }\n", 0),
        ("unguarded claimed reset is caught", EMPTY_GUARD + CLAIM + RESETS, 3),
        # wide3 C1: a `Unit` line detaches the claim from the resets
        ("claim detached by a stray statement is still a claim",
         EMPTY_GUARD + CLAIM + "        Unit\n" + RESETS, 3),
        # wide3 C2: a tautological guard decides nothing
        ("`true || toolCalls.isNotEmpty()` is not a guard",
         EMPTY_GUARD + "        if (true || toolCalls.isNotEmpty()) {\n" + CLAIM + RESETS
         + "        }\n", 3),
        # wide3 C2b: a dead guard (`false &&`) is not a guard
        ("`false && toolCalls.isNotEmpty()` is not a guard",
         EMPTY_GUARD + "        if (false && toolCalls.isNotEmpty()) {\n" + CLAIM + RESETS
         + "        }\n", 3),
        # wide3 C3: the reset expression is a literal arithmetic expression
        ("`= 0 + 0` is still a reset",
         EMPTY_GUARD + CLAIM + "        loopState.lengthWallContinues = 0 + 0\n", 1),
        # …and the second statement on a shared line counts too
        ("second statement on the line counts",
         EMPTY_GUARD + CLAIM
         + "        AppLogger.info(TAG, \"x\"); loopState.eofStubContinues = 0\n", 1),
        # fixture parity: a reset with no tool-call claim is out of remit
        ("unclaimed reset is out of remit", EMPTY_GUARD + "        loopState.eofStubContinues = 0\n", 0),
    ]
    failed = []
    for name, body, expected in arms:
        violations, _stats = analyse_lines(_src(body))
        if len(violations) != expected:
            failed.append("%s (expected %d, got %d: %s)"
                          % (name, expected, len(violations),
                             [v["line"] for v in violations]))
    if failed:
        print("❌ loop counter reset guard --selftest:")
        for line in failed:
            print("   - %s" % line)
        return 1
    print("✅ loop counter reset guard --selftest: %d arms match their expected verdict" % len(arms))
    print(SELFTEST_MARK)
    return 0


def main():
    if "--selftest" in sys.argv:
        root = next((a for a in sys.argv[1:] if not a.startswith("-")), ".")
        return selftest(root)
    root = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")
    app_dir = os.path.join(root, KOTLIN_APP)
    if not os.path.isdir(app_dir):
        print(f"❌ kotlin source root not found: {app_dir}")
        return 1
    files = kotlin_files(root)
    return report(root, files or [])


if __name__ == "__main__":
    sys.exit(main())
