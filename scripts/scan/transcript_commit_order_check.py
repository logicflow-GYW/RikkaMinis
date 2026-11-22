#!/usr/bin/env python3
"""[surgery-1122 G1] Static gate: assistant-row writes follow plan -> write -> commit.

Invariant 1 of docs/stability/protocol-invariants.md: a row the user can see is
not "delivered" until a durable plan names its id and a commit marker settles the
spool. Two regressions are silent:

  * insert without a durable plan  -> a crash between insert and commit leaves
    recovery no id to look for, so the answer is inserted a SECOND time;
  * insert/update without a commit -> the attempt's spool keeps no marker, so it
    stays "uncommitted" for a later recovery to resurrect forever.

Judgment is on the ARGUMENT that reaches the write, inside the SAME function
body as the write — not on "a plan token exists somewhere earlier in the file",
which is how a neighbouring function's plan/commit used to satisfy the check.

Site set is derived from the tree (`host.persistAssistantTurn(` /
`host.updatePersistedAssistantTurn(`), so a new legal path does not go red while
a new bypass does. The `persistTranscriptRow` wrapper is judged through its own
body instead of being exempted.

Escape hatch: `transcript-commit-ok: <reason>` in a comment inside the same
function body.

Usage: python3 scripts/scan/transcript_commit_order_check.py [repo_root] [--selftest]
Exit:  0 PASS, 1 violation (file:line printed), 2 usage/ROT.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from _kotlin_lexer import KotlinLexError, mask_code, mask_strings  # noqa: E402
from _kotlin_ast import function_body  # noqa: E402
from _kotlin_valueflow import call_args, path_feasible  # noqa: E402

ENGINE_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/AgentLoopEngine.kt"
SELFTEST_MARK = "TRANSCRIPT_COMMIT_ORDER_SELFTEST=PASS"
ESCAPE = "transcript-commit-ok:"
FUN_RE = re.compile(r"\bfun\s+(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*\(")
HELP_RE = re.compile(r"(?<![.\w])(plan|commit)TranscriptRow\s*\(")
WRITE_NAMES = (("host.persistAssistantTurn", "insert"),
               ("host.updatePersistedAssistantTurn", "update"))


def line_of(src, pos):
    return src.count("\n", 0, pos) + 1


def functions(code):
    """[(name, start, end)] for every function declaration, outermost span."""
    out = []
    for m in FUN_RE.finditer(code):
        name = m.group(1)
        got = function_body(code[m.start():], name)
        if not got:
            continue
        out.append((name, m.start(), m.start() + got[1] + 1))
    return out


def enclosing(functions_list, pos):
    best = None
    for name, start, end in functions_list:
        if start <= pos < end and (best is None or start > best[1]):
            best = (name, start, end)
    return best


def call_positions(code, needle):
    return [m.start() for m in re.finditer(r"(?<![\w])" + re.escape(needle) + r"\s*\(", code)]


def named_arg(code, pos, wanted):
    op = code.find("(", pos)
    if op < 0:
        return None
    for a, b in call_args(code, op):
        text = code[a:b].strip()
        m = re.match(r"([A-Za-z_]\w*)\s*=\s*(.+)", text, re.S)
        if m and m.group(1) == wanted:
            value = re.sub(r"\s+", "", m.group(2))
            return None if value == "null" else value
    return None


def assigned_target(code, call_pos, lo):
    """Name bound to the value of the call at `call_pos`, or None."""
    prefix = code[lo:call_pos]
    m = re.search(r"(?:val|var)?\s*([A-Za-z_][\w.]*)\s*=\s*$", prefix)
    return m.group(1) if m else None


def has_escape(raw, start, end):
    """Exemption is a REASON on the function, not an assertion inside real code.

    Read from the view that KEEPS comments and blanks string bodies
    (`mask_strings`), so a marker inside a string literal cannot exempt a site.
    It covers the function body plus the comment lines directly above its
    declaration — that is where a reviewer writes it.
    """
    text = mask_strings(raw)
    # Start from the beginning of the declaration's own line so the comment
    # lines directly above it are the first thing examined.
    lo = text.rfind("\n", 0, start) + 1
    while lo > 0:
        prev_end = lo
        prev_start = text.rfind("\n", 0, prev_end - 1) + 1
        line = text[prev_start:prev_end]
        if ESCAPE in line:
            return True
        if line.strip():
            break
        lo = prev_start
    return ESCAPE in text[start:end]


def judge_site(code, raw, funcs, pos, kind):
    """Violations for ONE write site."""
    out = []
    enc = enclosing(funcs, pos)
    if enc is None:
        return ["%s: write site is outside any function — cannot judge (ROT)" % line_of(code, pos)]
    _name, lo, hi = enc
    if has_escape(raw, lo, hi):
        return out

    if kind == "insert":
        stable = named_arg(code, pos, "stableId")
        if stable is None:
            out.append("AgentLoopEngine.kt:%d: insert passes no stableId, so the row id is not the "
                       "planned one and recovery cannot dedupe" % line_of(code, pos))
        else:
            linked = False
            for ppos in call_positions(code, "planTranscriptRow"):
                if not (lo <= ppos < pos):
                    continue
                target = assigned_target(code, ppos, lo)
                if target is None:
                    continue
                if re.sub(r"\s+", "", target) == stable and path_feasible(code, ppos, {}):
                    linked = True
                    break
            if not linked:
                out.append("AgentLoopEngine.kt:%d: insert's stableId '%s' is not a value produced by "
                           "a preceding planTranscriptRow in this function"
                           % (line_of(code, pos), stable))

    settled = False
    for cpos in call_positions(code, "commitTranscriptRow"):
        if not (pos < cpos < hi):
            continue
        if path_feasible(code, cpos, {}):
            settled = True
            break
    if not settled:
        out.append("AgentLoopEngine.kt:%d: %s is never followed by commitTranscriptRow in the same "
                   "function — the attempt spool stays unmarked" % (line_of(code, pos), kind))
    return out


def check_source(src):
    try:
        code = mask_code(src)
    except KotlinLexError as exc:
        return ["lexical failure (fail-closed): %s" % exc]
    funcs = functions(code)
    v = []

    names = {name for name, _s, _e in funcs}
    for required in ("planTranscriptRow", "commitTranscriptRow", "persistTranscriptRow"):
        if required not in names:
            v.append("AgentLoopEngine.kt: `%s` is gone (ROT — update this gate)" % required)

    # The wrapper is a protocol witness, not an exempt black box.
    for name, lo, hi in funcs:
        if name != "persistTranscriptRow":
            continue
        writes = [p for p in call_positions(code, "host.persistAssistantTurn") if lo <= p < hi]
        if len(writes) != 1:
            v.append("AgentLoopEngine.kt:%d: persistTranscriptRow must own exactly one insert; found %d"
                     % (line_of(code, lo), len(writes)))
            continue
        write = writes[0]
        if not any(lo <= p < write for p in call_positions(code, "planTranscriptRow")):
            v.append("AgentLoopEngine.kt:%d: wrapper inserts before planning the deterministic id"
                     % line_of(code, write))
        if not any(write < p < hi for p in call_positions(code, "commitTranscriptRow")):
            v.append("AgentLoopEngine.kt:%d: wrapper returns without settling its pending handles"
                     % line_of(code, write))

    for name, kind in WRITE_NAMES:
        for pos in call_positions(code, name):
            enc = enclosing(funcs, pos)
            if enc and enc[0] == "persistTranscriptRow":
                continue
            v.extend(judge_site(code, src, funcs, pos, kind))
    return v


def run(root):
    if not os.path.isdir(root):
        print("transcript_commit_order_check: not a directory: %s" % root)
        return 2
    path = os.path.join(root, ENGINE_REL)
    if not os.path.isfile(path):
        print("transcript_commit_order_check: cannot read sources: %s" % path)
        return 2
    with open(path, encoding="utf-8") as fh:
        src = fh.read()
    violations = check_source(src)
    if violations:
        print("FAIL transcript_commit_order_check")
        for violation in violations:
            print("  " + violation)
        return 1
    print("PASS transcript_commit_order_check (plan -> write -> commit at every site)")
    return 0


GOOD = '''package com.rikkaminis.app.ui.chat

class AgentLoopEngine {
    suspend fun planTranscriptRow(loopState: LoopState, rowIndex: Int): String? = "row"

    suspend fun commitTranscriptRow(loopState: LoopState, rowId: String?) {
        loopState.pending.clear()
    }

    suspend fun persistTranscriptRow(loopState: LoopState): String? {
        val plannedId = planTranscriptRow(loopState, 0)
        val rowId = host.persistAssistantTurn(parts, stableId = plannedId)
        commitTranscriptRow(loopState, rowId)
        return rowId
    }

    suspend fun errorRow(loopState: LoopState) {
        val plannedId = planTranscriptRow(loopState, 0)
        val rowId = host.persistAssistantTurn(parts, stableId = plannedId)
        commitTranscriptRow(loopState, rowId)
    }

    suspend fun refreshRow(loopState: LoopState) {
        host.updatePersistedAssistantTurn(earlyId, parts)
        commitTranscriptRow(loopState, earlyId)
    }
}
'''


def _arm(label, src, expect_rc, marker, failures):
    import shutil
    import subprocess
    import tempfile
    root = tempfile.mkdtemp(prefix="tco-arm-")
    try:
        path = os.path.join(root, ENGINE_REL)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(src)
        proc = subprocess.run([sys.executable, os.path.abspath(__file__), root],
                              capture_output=True, text=True)
        out = (proc.stdout or "") + (proc.stderr or "")
        print("selftest %-28s -> rc=%d" % (label, proc.returncode))
        if proc.returncode != expect_rc or marker not in out:
            failures.append("%s: rc=%d out=%s" % (label, proc.returncode, out[-500:]))
    finally:
        shutil.rmtree(root)


def selftest():
    failures = []
    _arm("compliant tree", GOOD, 0, "PASS transcript_commit_order_check", failures)

    # 1. the plan is removed from ONE site and its stableId falls back to null
    no_plan = GOOD.replace(
        """    suspend fun errorRow(loopState: LoopState) {
        val plannedId = planTranscriptRow(loopState, 0)
        val rowId = host.persistAssistantTurn(parts, stableId = plannedId)""",
        """    suspend fun errorRow(loopState: LoopState) {
        val rowId = host.persistAssistantTurn(parts, stableId = null)""")
    _arm("insert without plan", no_plan, 1, "passes no stableId", failures)

    # 2. the plan still runs but its result is NOT what the insert receives
    unlinked = GOOD.replace(
        """        val plannedId = planTranscriptRow(loopState, 0)
        val rowId = host.persistAssistantTurn(parts, stableId = plannedId)
        commitTranscriptRow(loopState, rowId)
    }

    suspend fun refreshRow""",
        """        val plannedId = planTranscriptRow(loopState, 0)
        val rowId = host.persistAssistantTurn(parts, stableId = "other")
        commitTranscriptRow(loopState, rowId)
    }

    suspend fun refreshRow""")
    _arm("plan result not used", unlinked, 1, "is not a value produced by", failures)

    # 3. commit dropped from one site only (a sibling commit must not rescue it)
    no_commit = GOOD.replace(
        """        val rowId = host.persistAssistantTurn(parts, stableId = plannedId)
        commitTranscriptRow(loopState, rowId)
    }

    suspend fun refreshRow""",
        """        val rowId = host.persistAssistantTurn(parts, stableId = plannedId)
    }

    suspend fun refreshRow""")
    _arm("insert without commit", no_commit, 1, "is never followed by commitTranscriptRow", failures)

    # 4. the refresh path loses its commit
    no_refresh = GOOD.replace(
        """        host.updatePersistedAssistantTurn(earlyId, parts)
        commitTranscriptRow(loopState, earlyId)""",
        """        host.updatePersistedAssistantTurn(earlyId, parts)""")
    _arm("refresh without commit", no_refresh, 1, "update is never followed", failures)

    # 5. a NEW direct write site (a path added tomorrow) is judged the same day
    new_site = GOOD + """
    suspend fun lateBypass(loopState: LoopState) {
        host.persistAssistantTurn(parts, stableId = null)
    }
"""
    _arm("new direct bypass", new_site, 1, "passes no stableId", failures)

    # 6. the wrapper loses its own commit (it must not be an exempt black box)
    wrapper_open = GOOD.replace(
        """        val rowId = host.persistAssistantTurn(parts, stableId = plannedId)
        commitTranscriptRow(loopState, rowId)
        return rowId""",
        """        val rowId = host.persistAssistantTurn(parts, stableId = plannedId)
        return rowId""")
    _arm("wrapper without commit", wrapper_open, 1, "wrapper returns without settling", failures)

    # 7. deleting the helper is a gate event, not a silent pass
    _arm("commit helper deleted", GOOD.replace("commitTranscriptRow", "settledRow"),
         1, "is gone (ROT", failures)

    # 8. the documented escape hatch works, and only inside its own function
    escaped = GOOD.replace(
        """    suspend fun refreshRow(loopState: LoopState) {""",
        """    // transcript-commit-ok: non-streamed maintenance row, no spool to settle
    suspend fun maintenanceRow(loopState: LoopState) {
        host.persistAssistantTurn(parts, stableId = null)
    }

    suspend fun refreshRow(loopState: LoopState) {""")
    _arm("documented escape hatch", escaped, 0, "PASS transcript_commit_order_check", failures)

    if failures:
        for failure in failures:
            print("SELFTEST FAIL " + failure)
        return 1
    print(SELFTEST_MARK)
    return 0


def main():
    args = sys.argv[1:]
    if "--selftest" in args:
        return selftest()
    root = next((arg for arg in args if not arg.startswith("-")), os.getcwd())
    return run(root)


if __name__ == "__main__":
    sys.exit(main())
