#!/usr/bin/env python3
"""
Execution-gate guard — deep-audit-1016 FIX-D.

Why this gate exists
--------------------
`sandbox/ExecutionCoordinator.kt` acquires two semaphores to serialize shell
work: a process-wide heavy gate (`Semaphore(1, true)`) and a per-process global
slot (`Semaphore(MAX_CONCURRENT_SHELLS, true)`). Two properties of that code are
invisible to a compiler, to the existing unit tests and to a code reviewer
reading only a diff, yet both fail silently:

  1. INTERRUPTIBILITY (4b). Both waits used `Semaphore.tryAcquire(timeout, unit)`
     directly. That is a blocking JUC call with no suspension point, so a
     coroutine cancelled while parked in it stayed parked — the shell stayed
     reserved for up to the timeout and the session's next command reported
     "System busy". The fix routes every blocking acquire through
     `runInterruptible(Dispatchers.IO)`. Deleting that wrapper restores the bug
     with zero compile errors.

  2. PERMIT ROLLBACK (4a). Between taking the heavy permit and entering the
     `try` whose `finally` releases it there is exactly one throwing statement:
     the global-slot acquire. Before 4b nothing could throw there. Once the wait
     became interruptible, an interrupt on that line propagates out of `execute`
     still holding the ONE heavy permit, and a leaked heavy permit queues every
     HEAVY command in the process until it dies.

  3. THREAD OF THE RECOVERY LOOP (4c). `postRecycleMemoryRecovery` runs up to
     three `System.gc()` rounds with a 120ms settle each. It is reachable
     straight from the main thread (Compose -> promoteDraftIfNeeded ->
     viewModelScope(Main.immediate) -> ensureSession -> migrateDraftResources ->
     sessionDidTerminate, and ViewModel.onCleared), where it is UI-thread jank.

Criterion (mechanical, not "does it look right")
-----------------------------------------------
  G1  `tryAcquire(` appears EXACTLY twice in the file — once per interruptible
      wrapper. A third occurrence means a raw blocking acquire came back; zero
      means the anchors moved and this gate can no longer see them (that is a
      FAIL, never a silent pass — the gate's own blind spot is how a gate dies).
  G2  `awaitHeavyGatePermitInterruptibly`'s body contains `runInterruptible(`.
  G3  `awaitGlobalShellSlotInterruptibly`'s body contains `runInterruptible(`
      and delegates to `internalAcquireGlobalShellSlot(`.
  G4  `internalAcquireGlobalShellSlot`'s body contains a `catch` that releases
      the heavy permit AND rethrows (`heavyGate.release()`, `throw`).
  G5  `postRecycleMemoryRecovery` dispatches on the caller thread:
      `Looper.getMainLooper()` + `recycleDispatchMode(` + `recoveryScope.launch`,
      with the synchronous body kept in `postRecycleMemoryRecoveryNow`.
  G6  The calibrated settle `Thread.sleep(120)` is still there: the value is a
      measured tuning constant (see the KDoc it sits in), so changing it must be
      a deliberate act that also updates this gate.

Deliberately NOT checked: that `execute()` calls the wrappers by name — a rename
is visible in review, and pinning call-site text is the brittle kind of rule
that makes a gate get disabled later. The two wrapper bodies are pinned instead.

Third wave (2026-10-26, round 3 "dual-gate bypass")
--------------------------------------------------
G1-G6 all judge the CATCH path (`internalAcquireGlobalShellSlot`) and the
wrappers. Round 3 walked around them on the two paths that are not exceptions:
the timeout-rejection branch (`if (!acquired) ... return`, witness #21) and the
happy path's `finally` (witness #22). Both edits delete only the *return* of the
permit — the acquire, the wrappers, the catch and every substring stay intact —
and a leaked heavy permit queues every HEAVY command in the process until it
dies, so "the catch still rolls back" says nothing about the path that leaked.

  G7  The heavy permit is accounted for by EXIT PATH, derived from the tree
      rather than from a spelling: the held variable comes from the assignment
      that calls `awaitHeavyGatePermitInterruptibly`, the gate expression from
      its first argument, and then
        a) every `return` between the acquire and the guarded `try` needs a
           release on its own path — unless an enclosing condition proves the
           permit is NOT held (`!held`, `held == false`), which is exactly the
           "heavy-gate timeout" branch that must not release;
        b) when a guarded `try` follows, its `finally` must hand the permit back,
           and the release must not sit behind a dead branch.
      A shape with no guarded region (the JVM fixture's minimal command path)
      has nothing to judge and stays green: the check never invents a structure.

Usage: python3 scripts/scan/exec_gate_release_check.py [repo_root]
Exit:  0 = clean, 1 = violation or lost anchor
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import analyse                      # noqa: E402
from _kotlin_ast import (const_bool, if_blocks, split_top as ast_split_top,  # noqa: E402
                         stmt_spans, strip_parens, when_branches)

TARGET = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/ExecutionCoordinator.kt"

MARK_RAW_ACQUIRE = "[exec-gate] raw blocking acquire"
MARK_HEAVY_WRAP = "[exec-gate] heavy-gate wait not interruptible"
MARK_GLOBAL_WRAP = "[exec-gate] global-slot wait not interruptible"
MARK_ROLLBACK = "[exec-gate] heavy permit rollback missing"
MARK_DISPATCH = "[exec-gate] main-thread recovery dispatch missing"
MARK_WIRING = "[exec-gate] wrapper wiring changed"
MARK_SETTLE = "[exec-gate] calibrated settle changed"
MARK_ANCHOR = "[exec-gate] anchor lost"
MARK_DEAD = "[exec-gate] decision short-circuited by a constant"
MARK_INNER = "[exec-gate] blocking acquire outside the interruptible block"
# [wide4] 新增两条：词法解析失败 fail-closed，以及可中断包装跑在错误的调度器上
# （`runInterruptible(Dispatchers.Main)` 里做阻塞等待 = 主线程卡死，4b 的镜像面）。
MARK_LEX = "[exec-gate] unparseable Kotlin source"
MARK_IO = "[exec-gate] interruptible wait not on Dispatchers.IO"
# [r3] 新增：heavy permit 的「申请-归还」必须覆盖每一条退出路径。
# 前三波都只看 internalAcquireGlobalShellSlot 的 catch（异常路径），于是 round 3
# 的两条见证把 permit 泄漏搬到两条**正常**路径上：超时拒绝分支里删掉 release、
# 成功路径的 finally 里删掉 release。两处都让「申请」原样存在、只删「归还」。
MARK_PERMIT = "[exec-gate] heavy permit leaked on an exit path"


def match_paren(text: str, open_idx: int) -> int:
    """Index of the `)` closing text[open_idx], or -1."""
    depth, i = 0, open_idx
    while i < len(text):
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def brace_span_from(text: str, open_idx: int) -> tuple:
    """(start, end_exclusive) of the `{...}` opened at open_idx; unbalanced = (open_idx, len)."""
    depth, i, n = 0, open_idx, len(text)
    while i < n:
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return open_idx, i + 1
        i += 1
    return open_idx, n


def skip_ws(text: str, i: int) -> int:
    while i < len(text) and text[i] in " \t\r\n":
        i += 1
    return i


def lambda_after_call(text: str, call_idx: int) -> tuple:
    """The trailing `{...}` lambda of the call that starts at call_idx.

    `runInterruptible(Dispatchers.IO) { ... }` — the braces AFTER the balanced
    argument list. Parsing this is the whole point: the wide3 witness smuggled
    the real acquire OUT of the lambda into a sibling `run { ... }`, which left
    `runInterruptible(` in the body and the acquire in the same function and so
    satisfied both substring checks while deleting the decision.
    """
    op = text.find("(", call_idx)
    if op < 0:
        return None
    cl = match_paren(text, op)
    if cl < 0:
        return None
    b = skip_ws(text, cl + 1)
    if b < len(text) and text[b] == "{":
        return brace_span_from(text, b)
    return None


def interruptible_lambda(text: str, call_idx: int):
    """(args_text, lambda_start, lambda_end) for a `runInterruptible<...>(args) { }`.

    [wide4] Returns the ARGUMENTS as well as the lambda: the wide3 criteria only
    asked whether a `{ … }` followed the call, so `runInterruptible(Dispatchers.
    Main) { blocking() }` — a blocking wait parked on the UI thread — passed.
    """
    op = text.find("(", call_idx)
    if op < 0:
        return None
    cl = match_paren(text, op)
    if cl < 0:
        return None
    span = lambda_after_call(text, call_idx)
    if span is None:
        return None
    return text[op + 1:cl], span[0], span[1]


def lambda_tail_holds(body: str, needle: str) -> tuple:
    """(ok, why) — `needle` is the LAST top-level statement of the lambda body.

    [wide4] Range containment (`bs <= pos < be`) was the whole criterion, and
    the witness kept the call inside the lambda while parking it in an
    unevaluated nested lambda (`val _unused = { heavyGate.tryAcquire(…) }`)
    followed by `true`. The blocking call has to be the statement the lambda
    RETURNS, at its own top level — anything else is a call that never runs.
    """
    inner = body
    if inner.startswith("{"):
        # brace_span_from hands back the braces themselves; judge the interior.
        inner = inner[1:-1] if inner.rstrip().endswith("}") else inner[1:]
        off = 1
    pat = re.compile(r"\b%s\s*\(" % re.escape(needle))
    hit = pat.search(inner)
    if not hit:
        return False, "no `%s(` inside the interruptible lambda" % needle
    spans = stmt_spans(inner)
    if not spans:
        return False, "cannot parse the interruptible lambda body"
    last = inner[spans[-1][0]:spans[-1][1]].strip()
    head = inner[:hit.start()]
    depth = head.count("{") - head.count("}")
    if depth != 0:   # 0 == the lambda's own top level (braces stripped above)
        return (False, "`%s(` sits inside a nested block/lambda of the interruptible "
                       "block, so the wrapper never runs it" % needle)
    if not re.fullmatch(r"(?:[A-Za-z_][\w.]*\.)?%s\s*\(.*\)" % re.escape(needle),
                        last, re.S):
        return (False, "`%s(` is not the interruptible block's own result (the block "
                       "returns `%s`), so the wait is not what this wrapper performs"
                % (needle, (last.splitlines()[0][:60] if last else "<empty>")))
    return True, ""


def if_branches(text: str):
    """[(cond_start, cond_end, body_start, body_end)] for every `if (...)`.

    Handles both `{ ... }` bodies and the single-statement body that runs to the
    end of the line (`if (heavyGateHeld) heavyGate.release()`).
    """
    out = []
    for m in re.finditer(r"\bif\s*\(", text):
        op = m.end() - 1
        cl = match_paren(text, op)
        if cl < 0:
            continue
        b = skip_ws(text, cl + 1)
        if b < len(text) and text[b] == "{":
            bs, be = brace_span_from(text, b)
        else:
            bs = b
            eol = text.find("\n", b)
            be = len(text) if eol < 0 else eol
        out.append((op + 1, cl, bs, be))
    return out


def enclosing_if(text: str, idx: int):
    """The innermost `if (...)` whose CONDITION or BODY contains idx.

    Returns (cond_start, cond_end, body_start, body_end, in_body) or None.
    """
    best = None
    for cs, ce, bs, be in if_branches(text):
        if (cs <= idx < ce) or (bs <= idx < be):
            if best is None or (ce - cs) < (best[1] - best[0]):
                best = (cs, ce, bs, be, bs <= idx < be)
    return best


def split_top(s: str, tokens) -> list:
    """Split on the given operators at parenthesis/brace depth 0."""
    parts, depth, cur = [], 0, []
    i, n = 0, len(s)
    while i < n:
        c = s[i]
        if c in "([":
            depth += 1
        elif c in ")]":
            depth -= 1
        hit = None
        if depth == 0:
            for t in tokens:
                if s.startswith(t, i):
                    hit = t
                    break
        if hit:
            parts.append("".join(cur).strip())
            cur = []
            i += len(hit)
            continue
        cur.append(c)
        i += 1
    parts.append("".join(cur).strip())
    return parts


def strip_outer_parens(s: str) -> str:
    s = s.strip()
    while s.startswith("(") and s.endswith(")"):
        depth = 0
        ok = True
        for i, ch in enumerate(s):
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0 and i != len(s) - 1:
                    ok = False
                    break
        if not ok:
            break
        s = s[1:-1].strip()
    return s


def constant_truth(cond: str):
    """True/False when the condition's value is decidable without running it.

    The wide3 witnesses did not delete the guarded call — they prefixed the
    guard with `false &&` (or the thread check with `false &&`), which keeps
    every substring the gate looked for while making the decision constant.
    `false && anything` and `anything || true` are the two shapes that decide.
    """
    s = strip_outer_parens(cond)
    ors = split_top(s, ("||",))
    if len(ors) > 1:
        vals = [constant_truth(p) for p in ors]
        if any(v is True for v in vals):
            return True
        if all(v is False for v in vals):
            return False
        return None
    ands = split_top(s, ("&&",))
    if len(ands) > 1:
        vals = [constant_truth(p) for p in ands]
        if any(v is False for v in vals):
            return False
        if all(v is True for v in vals):
            return True
        return None
    if s == "true":
        return True
    if s == "false":
        return False
    if s.startswith("!"):
        inner = constant_truth(s[1:])
        return None if inner is None else (not inner)
    return None


def catch_blocks(body: str):
    """(start, end_exclusive) of every `catch (...) { ... }` in body."""
    out = []
    for m in re.finditer(r"\bcatch\s*\(", body):
        cl = match_paren(body, m.end() - 1)
        if cl < 0:
            continue
        b = skip_ws(body, cl + 1)
        if b < len(body) and body[b] == "{":
            out.append(brace_span_from(body, b))
    return out


LEX_FAILURES = []


def mask(src: str) -> str:
    """Blank out comments and string-literal interiors, preserving offsets.

    [wide4] Delegated to the shared `_kotlin_lexer` state machine. The gate used
    to carry its own scanner and it died on the Kotlin constructs that scanner
    did not model: a single-quote char literal (`'"'`) opened a fake string that
    swallowed the code between it and the next quote (the `if (true) throw t`
    witness), non-nested `/* */` handling let a nested block comment end early,
    and `\"\"\"` was matched by `find` without the "last three quotes win" rule.
    A lexing surface this gate cannot trust is a gate that reports green about
    code it never saw — so a parse failure is recorded here and turned into a
    finding by `main()` (fail closed), never silently ignored.
    """
    ok, reason, views = analyse(src)
    if not ok:
        LEX_FAILURES.append(reason)
        return src
    return views["code"]


def normalize_expr(expr: str) -> str:
    """Whitespace-free, outer-paren-free form — the shape a criterion pins.

    [wide4] The wide3 criteria compared *spellings*: they stripped `false &&`
    with a constant fold, so the next witness wrote `(1 == 2) &&` and every
    character the fold looked at was still there. Normalising the whole
    expression and asserting it EQUALS the expected shape closes the whole
    family instead of the one spelling.
    """
    return strip_parens(re.sub(r"\s+", "", expr))


# The only two spellings of the caller-thread decision. Anything else (a
# constant prefix, a comparison, a negation, a helper call) is a decision the
# gate cannot see, which is exactly how 4c came back last wave.
DISPATCH_ARG_OK = (
    "Looper.myLooper()==Looper.getMainLooper()",
    "Looper.getMainLooper()==Looper.myLooper()",
)


def covering_conditions(text: str, idx: int) -> list:
    """Every enclosing condition covering idx: ('if', cond) / ('when', subject, arm).

    [wide4] `enclosing_if` returned only the INNERMOST `if`, so any outer dead
    wrapper — `when (false) { true -> … }`, `if (false) { … }` — was invisible
    while the inner `if (heavyGateHeld)` stayed live and the rollback counted as
    present. A branch is only reachable when EVERY enclosing condition lets it
    run, so the judgement has to walk the whole chain.
    """
    out = []
    for cond, t_start, t_end, e_start, e_end in if_blocks(text):
        if t_start <= idx < t_end:
            out.append(("if", cond))
        elif e_start is not None and e_start <= idx < e_end:
            out.append(("if", "!" + cond))
    for subject, cond, b_start, b_end in when_branches(text):
        if b_start <= idx < b_end:
            out.append(("when", subject, cond))
    return out


def condition_dead(entry) -> bool:
    """True when this enclosing condition can never let the guarded code run.

    `if (const)` folds directly. For `when`, the SUBJECT is folded too: the
    witness `when (false) { true -> … }` has a perfectly live-looking arm
    condition (`true`) and a const-false subject, which is why judging the arm
    alone stayed green.
    """
    if entry[0] == "if":
        return const_bool(entry[1]) is False
    _kind, subject, arm = entry
    if const_bool(arm) is False:
        return True
    subj = const_bool(subject)
    if subj is None:
        return False
    arm_text = arm.strip()
    if arm_text in ("else", "otherwise"):
        return False
    arm_val = const_bool(arm_text)
    if arm_val is None:
        return False
    return arm_val != subj


def body_of(masked: str, signature: str) -> str:
    """Return the body of the declaration whose text contains `signature`.

    The declaration's extent is bounded FIRST (by the next declaration at the
    same or lower indentation / the closing brace of its enclosing block) and
    the braces are balanced only INSIDE that region. Doing it the other way
    round is how this gate first shipped a blind spot: an expression body with
    no braces at all (`fun f(): Boolean = g()`) has no brace of its own, so
    balancing from the first `{` after the signature ran straight into the NEXT
    declaration's braces and read that neighbour's `runInterruptible(` as this
    function's — reporting green for the exact regression it was built to catch.
    The fixture `no_interruptible` in test_scan.py now pins that shape.

    Handles block bodies (`fun f() { ... }`) and expression bodies
    (`fun f(): Boolean = runInterruptible(...) { ... }`) alike, and returns the
    brace-less expression body as-is so a missing marker is still reported.
    """
    m = re.search(re.escape(signature), masked)
    if not m:
        return ""
    line_start = masked.rfind("\n", 0, m.start()) + 1
    indent = len(masked[line_start:m.start()]) - len(masked[line_start:m.start()].lstrip())

    end = len(masked)
    pos = masked.find("\n", m.end())
    while pos != -1:
        cand_start = pos + 1
        if cand_start >= len(masked):
            break
        cand_end = masked.find("\n", cand_start)
        if cand_end == -1:
            cand_end = len(masked)
        line = masked[cand_start:cand_end]
        stripped = line.strip()
        if stripped:
            line_indent = len(line) - len(line.lstrip())
            if line_indent <= indent and re.match(
                    r"(@|internal\b|private\b|public\b|protected\b|override\b|object\b|"
                    r"class\b|enum\b|fun\b|val\b|var\b|const\b|\})", stripped):
                end = cand_start
                break
        pos = cand_end

    region = masked[m.end():end]
    open_idx = region.find("{")
    if open_idx < 0:
        return region
    depth = 0
    for k in range(open_idx, len(region)):
        ch = region[k]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return region[:k + 1]
    return region


ACQUIRE_CALL = "awaitHeavyGatePermitInterruptibly"


def _call_arg0(text: str, open_idx: int):
    """First argument of the call whose `(` is at open_idx (text, depth-aware)."""
    cl = match_paren(text, open_idx)
    if cl < 0:
        return None
    parts = split_top(text[open_idx + 1:cl], (",",))
    return parts[0] if parts and parts[0] else None


def _acquire_site(masked: str):
    """(call_idx, open_idx) of the CALL to the interruptible heavy acquire."""
    for m in re.finditer(r"\b%s\s*\(" % ACQUIRE_CALL, masked):
        if re.search(r"\bfun\s*$", masked[max(0, m.start() - 80):m.start()]):
            continue                      # the declaration itself
        return m.start(), m.end() - 1
    return None, None


def _innermost_if_body(text: str, idx: int):
    """Start offset of the innermost `if` body containing idx, or None."""
    lo = None
    for _cond, t_start, t_end, _es, _ee in if_blocks(text):
        if t_start <= idx < t_end:
            lo = t_start if lo is None else max(lo, t_start)
    return lo


def permit_exit_path_findings(masked: str) -> list:
    """[r3] G7 — the heavy permit is handed back on EVERY exit path.

    Witness #21 deleted the release from the timeout-rejection branch
    (`if (!acquired) { …; return }`), witness #22 deleted it from the happy
    path's `finally`. G1–G6 judge the catch of the *global-slot* acquire, the
    wrappers and the recovery loop: every one of them stays perfect while the ONE
    heavy permit is never returned, so the next HEAVY command queues forever.

    Structure derived from the tree, not from spelling:
      * held variable  = the `val` bound to `awaitHeavyGatePermitInterruptibly(`
      * gate expression = that call's first argument
      * the "window"   = from the end of the acquire statement to the guarded
                         `try`, i.e. the only stretch where the permit is held
                         and unprotected
      * a `return` in the window needs a release on its own path UNLESS an
        enclosing condition proves the permit is not held (`!held`,
        `held == false`) — that is the heavy-gate timeout branch, which must NOT
        release (releasing a permit it never took is its own defect).
    """
    call_i, open_i = _acquire_site(masked)
    if call_i is None:
        return []                     # anchor moved: G2/G3 already fail closed
    held = None
    for m in re.finditer(r"\bval\s+(\w+)\s*=(?!=)", masked[max(0, call_i - 200):call_i]):
        held = m.group(1)
    gate = _call_arg0(masked, open_i)
    if not gate or not held:
        return ["%s — the acquire call's held variable / gate expression cannot be "
                "derived from the tree, so the permit accounting is unjudgeable"
                % MARK_PERMIT]
    rel = re.compile(r"\b%s\s*\.\s*release\s*\(\s*\)" % re.escape(gate.strip()))
    cl = match_paren(masked, open_i)
    if cl < 0:
        return ["%s — the acquire call does not close" % MARK_PERMIT]
    stmt_end = masked.find("\n", cl)
    if stmt_end < 0:
        stmt_end = len(masked)
    try_at = re.search(r"\btry\s*\{", masked[stmt_end:])
    window_end = stmt_end + try_at.start() if try_at else len(masked)
    out = []

    # (a) every return between the acquire and the guarded try
    for m in re.finditer(r"\breturn\b", masked[stmt_end:window_end]):
        p = stmt_end + m.start()
        if re.match(r"\s*@", masked[p + len("return"):p + len("return") + 8]):
            continue                                   # return@lambda
        conds = covering_conditions(masked, p)
        not_held = any(
            re.search(r"!\s*%s\b" % re.escape(held), c[1]) or
            re.search(r"\b%s\s*==\s*false" % re.escape(held), c[1])
            for c in conds if c[0] == "if")
        if not_held:
            continue
        lo = _innermost_if_body(masked, p)
        if lo is None:
            lo = stmt_end
        live = [r for r in rel.finditer(masked[lo:p])
                if not any(condition_dead(c) for c in
                           covering_conditions(masked, lo + r.start()))]
        if not live:
            line = masked.count("\n", 0, p) + 1
            cond_txt = " ".join(conds[-1][1].split())[:60] if conds else "straight-line code"
            out.append(
                "%s — the branch that returns at line %d still holds %s "
                "(`%s`) and never calls `%s.release()`: one HEAVY command then "
                "queues the whole process" %
                (MARK_PERMIT, line, held, cond_txt, gate.strip()))

    # (b) the guarded try must hand the permit back in its finally
    if try_at:
        t_open = stmt_end + try_at.end() - 1
        _s, t_end = brace_span_from(masked, t_open)
        catches, fin = try_sections(masked, t_open)
        if fin is None:
            out.append(
                "%s — the guarded region opened at line %d has no `finally`, so "
                "any throw or early exit inside it keeps the single heavy permit" %
                (MARK_PERMIT, masked.count("\n", 0, t_open) + 1))
        else:
            body = masked[fin[0]:fin[1]]
            m = rel.search(body)
            if not m:
                out.append(
                    "%s — the `finally` at line %d no longer calls `%s.release()`: "
                    "the happy path keeps the permit, so the next HEAVY command "
                    "queues forever (the catch still rolls back, which is why "
                    "this reads as fine)" %
                    (MARK_PERMIT, masked.count("\n", 0, fin[0]) + 1, gate.strip()))
            elif any(condition_dead(c) for c in covering_conditions(masked, fin[0] + m.start())):
                out.append(
                    "%s — the release in the `finally` at line %d is behind a dead "
                    "condition and can never run" %
                    (MARK_PERMIT, masked.count("\n", 0, fin[0] + m.start()) + 1))
    return out


def try_sections(text: str, try_open_idx: int):
    """(catch_spans, finally_span) of the `try` whose `{` is at try_open_idx."""
    _s, t_end = brace_span_from(text, try_open_idx)
    i = t_end
    catches, fin = [], None
    while True:
        i = skip_ws(text, i)
        m = re.match(r"catch\s*\(", text[i:])
        if m:
            cl = match_paren(text, i + m.end() - 1)
            if cl < 0:
                break
            b = skip_ws(text, cl + 1)
            if b < len(text) and text[b] == "{":
                cs, ce = brace_span_from(text, b)
                catches.append((cs, ce))
                i = ce
                continue
        m = re.match(r"finally\s*", text[i:])
        if m:
            b = skip_ws(text, i + m.end())
            if b < len(text) and text[b] == "{":
                fin = brace_span_from(text, b)
        break
    return catches, fin


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "..")
    path = os.path.join(root, TARGET)
    if not os.path.isfile(path):
        print(f"❌ {MARK_ANCHOR}: {TARGET} not found under {root}")
        return 1

    raw = open(path, encoding="utf-8").read()
    del LEX_FAILURES[:]
    masked = mask(raw)

    findings: list[str] = []
    for reason in LEX_FAILURES:
        findings.append(
            f"{MARK_LEX}: the target does not lex ({reason}) — the gate refuses to "
            "judge a file it cannot tokenise, because a mis-lexed surface is how the "
            "single-quote / nested-comment witnesses hid thirty lines of real code"
        )

    # --- G1: exactly two blocking acquires, both inside the wrappers ---------
    acquire_sites = [m.start() for m in re.finditer(r"tryAcquire\(", masked)]
    if len(acquire_sites) != 2:
        lines = sorted({masked[:s].count("\n") + 1 for s in acquire_sites})
        findings.append(
            f"{MARK_RAW_ACQUIRE}: expected exactly 2 `tryAcquire(` (one inside each "
            f"interruptible wrapper), found {len(acquire_sites)}"
            + (f" at lines {lines}" if lines else "")
            + ". A blocking acquire outside `runInterruptible(Dispatchers.IO)` "
              "cannot be cancelled (4b)."
        )

    heavy_wrap = body_of(masked, "suspend fun awaitHeavyGatePermitInterruptibly(")
    if not heavy_wrap:
        findings.append(f"{MARK_ANCHOR}: `awaitHeavyGatePermitInterruptibly` not found")
    else:
        if "runInterruptible(" not in heavy_wrap:
            findings.append(
                f"{MARK_HEAVY_WRAP}: the heavy-gate wait is no longer wrapped in "
                "runInterruptible — a cancelled coroutine parks in tryAcquire for the "
                "whole timeout (4b)"
            )
        # G2b (wide3): `runInterruptible(` being IN the body is not the contract —
        # the ACQUIRE must run inside its lambda. `run { runInterruptible(IO) { true
        # }; heavyGate.tryAcquire(...) }` keeps every substring while the acquire
        # runs uninterruptibly on the worker thread (4b returns).
        # [wide4] …and being inside the lambda is not enough either: the wide4
        # witness parked the acquire in an unevaluated nested lambda
        # (`val _unused = { heavyGate.tryAcquire(…) }; true`), which satisfies any
        # range containment. The wait must be the block's own result, and the
        # block must run on Dispatchers.IO (`runInterruptible(Dispatchers.Main)`
        # parks a blocking wait on the UI thread).
        interrupts = []
        for m in re.finditer(r"\brunInterruptible\s*\(", heavy_wrap):
            parsed = interruptible_lambda(heavy_wrap, m.start())
            if parsed is not None:
                interrupts.append(parsed)
        if not interrupts:
            findings.append(
                f"{MARK_INNER}: `awaitHeavyGatePermitInterruptibly` has a "
                "runInterruptible call with no trailing block — nothing runs "
                "interruptibly (4b)"
            )
        acquires = [m.start() for m in re.finditer(r"\btryAcquire\s*\(", heavy_wrap)]
        if not acquires:
            findings.append(
                f"{MARK_INNER}: `awaitHeavyGatePermitInterruptibly` no longer performs "
                "any tryAcquire — the wait may have moved where nothing checks it"
            )
        else:
            for args, b0, b1 in interrupts:
                if normalize_expr(args) != "Dispatchers.IO":
                    findings.append(
                        f"{MARK_IO}: the heavy-gate wait is wrapped in "
                        f"runInterruptible({args.strip()}) — the blocking acquire runs "
                        "on a dispatcher the caller did not park for (4b)"
                    )
                    break
            ok_tail, why_tail = False, "no interruptible lambda encloses the acquire"
            for args, b0, b1 in interrupts:
                ok_tail, why_tail = lambda_tail_holds(heavy_wrap[b0:b1], "tryAcquire")
                if ok_tail:
                    break
            if not ok_tail:
                findings.append(f"{MARK_INNER}: {why_tail} (4b)")

    global_wrap = body_of(masked, "suspend fun awaitGlobalShellSlotInterruptibly(")
    if not global_wrap:
        findings.append(f"{MARK_ANCHOR}: `awaitGlobalShellSlotInterruptibly` not found")
    else:
        if "runInterruptible(" not in global_wrap:
            findings.append(
                f"{MARK_GLOBAL_WRAP}: the global-slot wait is no longer wrapped in "
                "runInterruptible (4b)"
            )
        if "internalAcquireGlobalShellSlot(" not in global_wrap:
            findings.append(
                f"{MARK_ROLLBACK}: the global-slot wait no longer goes through "
                "internalAcquireGlobalShellSlot, so the heavy permit taken before "
                "it is not handed back when the wait throws (4a)"
            )
        else:
            # Same rule as the heavy wrapper (one axis, both call sites): the
            # delegated acquire must be the interruptible block's own result, on
            # Dispatchers.IO — a delegate parked in an unevaluated lambda or run
            # on another dispatcher is a wait the wrapper does not actually do.
            g_parsed = [interruptible_lambda(global_wrap, m.start())
                        for m in re.finditer(r"\brunInterruptible\s*\(", global_wrap)]
            g_parsed = [x for x in g_parsed if x is not None]
            if not g_parsed:
                findings.append(
                    f"{MARK_GLOBAL_WRAP}: the global-slot wait has a runInterruptible "
                    "call with no trailing block (4b)"
                )
            else:
                for args, b0, b1 in g_parsed:
                    if normalize_expr(args) != "Dispatchers.IO":
                        findings.append(
                            f"{MARK_IO}: the global-slot wait is wrapped in "
                            f"runInterruptible({args.strip()}) — the blocking acquire "
                            "runs on a dispatcher the caller did not park for (4b)"
                        )
                        break
                ok_gate, why_gate = False, "no interruptible lambda encloses the delegate"
                for args, b0, b1 in g_parsed:
                    ok_gate, why_gate = lambda_tail_holds(
                        global_wrap[b0:b1], "internalAcquireGlobalShellSlot")
                    if ok_gate:
                        break
                if not ok_gate:
                    findings.append(f"{MARK_INNER}: {why_gate} (4b)")

    # --- G3b: the wiring the wrappers exist for -------------------------------
    # Counted, not text-pinned (comments are masked out above): each name occurs
    # exactly twice — its own declaration plus the single call on the command
    # path. This is the shape a reviewer cannot see in a diff and a JVM test
    # cannot reach: if `execute()` goes back to calling the bare helper, both
    # wrappers stay behind as dead code, every JVM arm stays green (it tests the
    # wrappers, which still work) and the regression is live.
    for name in (
        "awaitHeavyGatePermitInterruptibly(",
        "awaitGlobalShellSlotInterruptibly(",
        "internalAcquireGlobalShellSlot(",
    ):
        found = len(re.findall(re.escape(name), masked))
        if found != 2:
            findings.append(
                f"{MARK_WIRING}: `{name}` appears {found}x, expected 2 (declaration "
                "+ the single call on the command path). An interruptible wrapper "
                "is only worth having if the path goes through it."
            )

    rollback = body_of(masked, "fun internalAcquireGlobalShellSlot(")
    if not rollback:
        findings.append(f"{MARK_ANCHOR}: `internalAcquireGlobalShellSlot` not found")
    else:
        catches = catch_blocks(rollback)
        if "catch" not in rollback or not catches:
            findings.append(
                f"{MARK_ROLLBACK}: no catch clause — an interrupt while waiting for "
                "the global slot throws out of execute() holding the heavy permit (4a)"
            )
        else:
            # The rollback is judged on the CATCH BODY, not on the whole function:
            # `heavyGate.release()` also appears in the caller, and a substring
            # anywhere in the body was satisfied by the wide3 witness
            # `if (false && heavyGateHeld) heavyGate.release()` — the call stayed,
            # the decision went (4a comes straight back).
            released = []
            for cs, ce in catches:
                block = rollback[cs:ce]
                for m in re.finditer(r"\bheavyGate\s*\.\s*release\s*\(\s*\)", block):
                    idx = cs + m.start()
                    # [wide4] EVERY enclosing condition decides reachability. The
                    # wide3 rule looked at the INNERMOST `if` only, so
                    # `when (false) { true -> if (heavyGateHeld) release() }` and
                    # `if (false) { if (heavyGateHeld) release() }` both read as a
                    # live rollback: the inner condition is genuinely unknown.
                    dead = next((c for c in covering_conditions(rollback, idx)
                                 if condition_dead(c)), None)
                    if dead is not None:
                        # label reads like the source it came from, so the CI log
                        # tells an operator which construct to look at.
                        label = (dead[1].strip() if dead[0] == "if"
                                 else "%s) { %s ->"
                                 % (dead[1].strip(), dead[2].strip()))
                        findings.append(
                            f"{MARK_DEAD}: the catch's heavy-permit rollback sits "
                            f"behind `{dead[0]} ({label} …`, which can never be taken — "
                            "release() never runs (4a)"
                        )
                        continue
                    released.append(idx)
                if not any(cs <= idx < ce for idx in released):
                    findings.append(
                        f"{MARK_ROLLBACK}: the catch clause no longer releases the "
                        "heavy permit (4a)"
                    )
                # ...and the rethrow must come AFTER the release: a `throw` before
                # it (or without it) leaks the permit just the same.
                throw_at = block.find("throw")
                if throw_at < 0:
                    findings.append(
                        f"{MARK_ROLLBACK}: the catch no longer rethrows, which would "
                        "silently turn a cancellation into a 'no slot' rejection"
                    )
                elif released and not any(
                        cs <= idx < cs + throw_at for idx in released):
                    findings.append(
                        f"{MARK_ROLLBACK}: the catch rethrows BEFORE releasing the "
                        "heavy permit — the permit leaks on the throwing path (4a)"
                    )
                break

        if "throw" not in rollback:
            findings.append(
                f"{MARK_ROLLBACK}: the catch no longer rethrows, which would silently "
                "turn a cancellation into a 'no slot' rejection"
            )

    wrapper = body_of(masked, "fun postRecycleMemoryRecovery(")
    if not wrapper:
        findings.append(f"{MARK_ANCHOR}: `postRecycleMemoryRecovery` not found")
    else:
        if "Looper.getMainLooper()" not in wrapper:
            findings.append(
                f"{MARK_DISPATCH}: no main-thread check — the GC loop runs inline on "
                "whatever thread calls it, including the UI thread (4c)"
            )
        if "recycleDispatchMode(" not in wrapper:
            findings.append(
                f"{MARK_DISPATCH}: the dispatch decision no longer goes through "
                "recycleDispatchMode (4c)"
            )
        else:
            # G5b (wide3): the ARGUMENT is the decision. `isOnMainThread = false &&
            # Looper.myLooper() == Looper.getMainLooper()` keeps both substrings and
            # pins the argument to false, so main-thread callers run the GC loop
            # inline (4c) — the review-visible shape, invisibly inverted.
            at = wrapper.find("recycleDispatchMode(")
            op = wrapper.find("(", at)
            cl = match_paren(wrapper, op)
            if cl < 0:
                findings.append(
                    f"{MARK_DEAD}: cannot parse the recycleDispatchMode argument list "
                    "(4c decision unreadable — fail closed)"
                )
            else:
                args = wrapper[op + 1:cl]
                if "Looper.myLooper()" not in args or "Looper.getMainLooper()" not in args:
                    findings.append(
                        f"{MARK_DISPATCH}: the dispatch argument no longer compares the "
                        "caller thread with the main looper (4c)"
                    )
                # [wide4] Whitelist, not "is it constant". `constant_truth` only
                # folded literals, so `(1 == 2) && Looper… == Looper…` was
                # unknown-to-the-fold yet permanently false. The argument must BE
                # the caller-thread comparison, in one of its two spellings.
                named = re.match(r"\s*[A-Za-z_]\w*\s*=(?!=)", args)
                arg_expr = args[named.end():] if named else args
                if normalize_expr(arg_expr) not in DISPATCH_ARG_OK:
                    findings.append(
                        f"{MARK_DEAD}: the isOnMainThread argument is "
                        f"`{arg_expr.strip()}`, not the caller-thread comparison itself — "
                        "anything else (a constant prefix, a comparison, a helper) pins "
                        "every call to one branch, so main-thread callers run the GC "
                        "loop inline (4c)"
                    )
                # The decision must be CONSUMED: the `when`'s subject is the call.
                if not re.search(r"\bwhen\s*\(\s*recycleDispatchMode\s*\(", wrapper):
                    findings.append(
                        f"{MARK_DISPATCH}: the recycleDispatchMode(...) result is not the "
                        "`when` subject — the decision is computed and discarded (4c)"
                    )
        if "recoveryScope.launch" not in wrapper:
            findings.append(
                f"{MARK_DISPATCH}: main-thread callers are no longer deferred to "
                "recoveryScope (4c)"
            )
        # [wide4] …and the ARM the launch sits in is the decision. The wide4
        # witness kept `recoveryScope.launch` somewhere in the wrapper while
        # moving it into the INLINE arm (and running the GC loop synchronously
        # under DEFERRED): a substring in the body cannot tell the two arms
        # apart, and a dead `if (false)` wrapper inside an arm cannot either.
        arms = {}
        for subject, cond, b0, b1 in when_branches(wrapper):
            arms[cond.strip()] = wrapper[b0:b1].strip()
        deferred_arm = arms.get("RecycleDispatch.DEFERRED")
        inline_arm = arms.get("RecycleDispatch.INLINE")
        if deferred_arm is None or inline_arm is None:
            findings.append(
                f"{MARK_DISPATCH}: cannot identify the RecycleDispatch.DEFERRED / "
                "INLINE arms of the recovery dispatch — the branch/action pairing is "
                "unreadable (4c decision unreadable — fail closed)"
            )
        else:
            if (not deferred_arm.startswith("recoveryScope.launch")
                    or "postRecycleMemoryRecoveryNow()" not in deferred_arm):
                findings.append(
                    f"{MARK_DISPATCH}: the DEFERRED arm is `{deferred_arm[:60]}…` — a "
                    "main-thread caller must hand the GC loop to "
                    "recoveryScope.launch { postRecycleMemoryRecoveryNow() } (4c)"
                )
            if "recoveryScope.launch" in inline_arm or \
                    "postRecycleMemoryRecoveryNow()" not in inline_arm:
                findings.append(
                    f"{MARK_DISPATCH}: the INLINE arm is `{inline_arm[:60]}…` — the "
                    "caller-thread arm must run the GC loop itself, not defer it (4c)"
                )

    # G5c (wide4): the DECISION function itself. `recycleDispatchMode` maps the
    # caller's flag to the arm; a constant or an inverted body there defeats the
    # `when` above just as thoroughly, and the wide3 criteria never looked at it.
    mode_fn = body_of(masked, "internal fun recycleDispatchMode(")
    if not mode_fn:
        findings.append(f"{MARK_ANCHOR}: `recycleDispatchMode` not found")
    else:
        mode_ifs = if_blocks(mode_fn)
        if not mode_ifs:
            findings.append(
                f"{MARK_DISPATCH}: recycleDispatchMode no longer branches on the "
                "caller flag (4c)"
            )
        else:
            cond, t0, _t1, _e0, _e1 = mode_ifs[0]
            if const_bool(cond) is not None:
                findings.append(
                    f"{MARK_DEAD}: recycleDispatchMode decides on the constant "
                    f"`{cond.strip()}` — every caller takes the same arm (4c)"
                )
            if normalize_expr(cond) != "isOnMainThread":
                findings.append(
                    f"{MARK_DEAD}: recycleDispatchMode no longer decides on its "
                    f"`isOnMainThread` argument (found `{cond.strip()}`) (4c)"
                )
            # then/else values are read off the if's own tail rather than from
            # `if_blocks`' arm spans: this function is an EXPRESSION body whose
            # `else` sits on the same line, and a one-line arm split is exactly
            # the shape that made a naive arm parser report "no else".
            tail = mode_fn[t0:]
            halves = re.split(r"\belse\b", tail, maxsplit=1)
            then_val = re.search(r"RecycleDispatch\.(\w+)", halves[0])
            else_val = (re.search(r"RecycleDispatch\.(\w+)", halves[1])
                        if len(halves) > 1 else None)
            if (then_val is None or then_val.group(1) != "DEFERRED"
                    or else_val is None or else_val.group(1) != "INLINE"):
                findings.append(
                    f"{MARK_DISPATCH}: recycleDispatchMode no longer maps a main-thread "
                    "caller to DEFERRED and a background caller to INLINE (4c)"
                )
    if "fun postRecycleMemoryRecoveryNow(" not in masked:
        findings.append(
            f"{MARK_ANCHOR}: `postRecycleMemoryRecoveryNow` not found — the "
            "synchronous body must stay reachable for background callers"
        )

    # --- G6: the calibrated settle value --------------------------------------
    if "Thread.sleep(120)" not in masked.replace("  ", " "):
        findings.append(
            f"{MARK_SETTLE}: `Thread.sleep(120)` is gone. It is the measured "
            "post-GC settle used by the recycle loop; changing it is a deliberate "
            "act (update this gate together with the reason)."
        )

    # --- G7 (r3): the heavy permit, accounted per exit path -------------------
    findings.extend(permit_exit_path_findings(masked))

    if findings:
        print("❌ Execution-gate guard found issues:")
        for f in findings:
            print(f"   • {f}")
        return 1

    print("✅ Execution-gate guard: 4a rollback + 4b interruptible waits + "
          "4c main-thread dispatch all wired")
    return 0


HEAVY_WRAPPER = (
    "internal suspend fun awaitHeavyGatePermitInterruptibly(\n"
    "    heavyGate: Semaphore,\n"
    "    timeoutMs: Long,\n"
    "): Boolean = runInterruptible(Dispatchers.IO) {\n"
    "    heavyGate.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)\n"
    "}\n"
)

ROLLBACK_BODY = (
    "    return try {\n"
    "        globalConcurrency.tryAcquire(1, TimeUnit.SECONDS)\n"
    "    } catch (t: Throwable) {\n"
    "        if (heavyGateHeld) heavyGate.release()\n"
    "        throw t\n"
    "    }\n"
)

DISPATCH_BODY = (
    "        when (recycleDispatchMode(isOnMainThread = "
    "Looper.myLooper() == Looper.getMainLooper())) {\n"
    "            RecycleDispatch.DEFERRED -> "
    "recoveryScope.launch { postRecycleMemoryRecoveryNow() }\n"
    "            RecycleDispatch.INLINE -> postRecycleMemoryRecoveryNow()\n"
    "        }\n"
)


def selftest_fixture(shape):
    """The criterion written out — one dirty shape per wide3 witness."""
    heavy = HEAVY_WRAPPER
    if shape == "acquire_outside_lambda":
        # wide3 G2: the wrapper keeps `runInterruptible(` and the file keeps two
        # `tryAcquire(`s, but the blocking wait left the lambda.
        heavy = heavy.replace(
            "): Boolean = runInterruptible(Dispatchers.IO) {\n"
            "    heavyGate.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)\n"
            "}\n",
            "): Boolean = run { runInterruptible(Dispatchers.IO) { true }; "
            "heavyGate.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS) }\n",
        )
    rollback = ROLLBACK_BODY
    if shape == "dead_rollback":
        # wide3 G4: the rollback call stays, the decision goes.
        rollback = rollback.replace(
            "        if (heavyGateHeld) heavyGate.release()\n",
            "        if (false && heavyGateHeld) heavyGate.release()\n",
        )
    if shape == "when_dead_rollback":
        # wide4 witness 1: the rollback keeps a LIVE inner `if` and hides it
        # under a constantly-false `when` subject.
        rollback = rollback.replace(
            "        if (heavyGateHeld) heavyGate.release()\n",
            "        when (false) { true -> if (heavyGateHeld) heavyGate.release() "
            "else -> Unit }\n",
        )
    if shape == "nested_dead_if_rollback":
        # wide4 witness 1 (self-doubt): the same escape with a plain outer `if`.
        rollback = rollback.replace(
            "        if (heavyGateHeld) heavyGate.release()\n",
            "        if (false) { if (heavyGateHeld) heavyGate.release() }\n",
        )
    if shape == "char_literal_escape":
        # wide4 witness 5: `'"'` char literals used to open a fake string that
        # swallowed the `if (true) throw t` in between.
        rollback = rollback.replace(
            "        if (heavyGateHeld) heavyGate.release()\n        throw t\n",
            "        val _open = '\"'; if (true) throw t; val _close = '\"'\n"
            "        if (heavyGateHeld) heavyGate.release()\n        throw t\n",
        )
    dispatch = DISPATCH_BODY
    if shape == "dead_dispatch_arg":
        # wide3 G5: the thread comparison stays, pinned to false.
        dispatch = dispatch.replace(
            "isOnMainThread = Looper.myLooper() == Looper.getMainLooper()",
            "isOnMainThread = false && Looper.myLooper() == Looper.getMainLooper()",
        )
    if shape == "dead_dispatch_compare":
        # wide4 witness 2: `(1 == 2) &&` is permanently false but folds to
        # "unknown" for a literal-only constant evaluator.
        dispatch = dispatch.replace(
            "isOnMainThread = Looper.myLooper() == Looper.getMainLooper()",
            "isOnMainThread = (1 == 2) && "
            "(Looper.myLooper() == Looper.getMainLooper())",
        )
    if shape == "swapped_when_arms":
        # wide4 witness 4: the arms' actions swap while every substring stays.
        dispatch = dispatch.replace(
            "            RecycleDispatch.DEFERRED -> "
            "recoveryScope.launch { postRecycleMemoryRecoveryNow() }\n"
            "            RecycleDispatch.INLINE -> postRecycleMemoryRecoveryNow()\n",
            "            RecycleDispatch.DEFERRED -> postRecycleMemoryRecoveryNow()\n"
            "            RecycleDispatch.INLINE -> "
            "recoveryScope.launch { postRecycleMemoryRecoveryNow() }\n",
        )
    if shape == "dead_inline_arm":
        # wide4 witness 4 (self-doubt): the launch moves into the INLINE arm
        # behind `if (false)`.
        dispatch = dispatch.replace(
            "            RecycleDispatch.DEFERRED -> "
            "recoveryScope.launch { postRecycleMemoryRecoveryNow() }\n"
            "            RecycleDispatch.INLINE -> postRecycleMemoryRecoveryNow()\n",
            "            RecycleDispatch.DEFERRED -> recoveryScope.launch { "
            "postRecycleMemoryRecoveryNow() }\n"
            "            RecycleDispatch.INLINE -> if (false) { recoveryScope.launch { "
            "postRecycleMemoryRecoveryNow() } } else postRecycleMemoryRecoveryNow()\n",
        )
    if shape == "unused_lambda_acquire":
        # wide4 witness 3: the acquire never leaves the lambda but moves into an
        # unevaluated nested lambda.
        heavy = heavy.replace(
            "): Boolean = runInterruptible(Dispatchers.IO) {\n"
            "    heavyGate.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)\n"
            "}\n",
            "): Boolean = runInterruptible(Dispatchers.IO) {\n"
            "    val _unused = { heavyGate.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS) }\n"
            "    true\n"
            "}\n",
        )
    if shape == "main_dispatcher":
        # wide4 witness 3 (self-doubt): an interruptible block on the wrong
        # dispatcher parks the blocking wait on the caller's thread.
        heavy = heavy.replace("runInterruptible(Dispatchers.IO)",
                              "runInterruptible(Dispatchers.Main)")
    mode_fn = (
        "internal fun recycleDispatchMode(isOnMainThread: Boolean): RecycleDispatch =\n"
        "    if (isOnMainThread) RecycleDispatch.DEFERRED else RecycleDispatch.INLINE\n"
    )
    if shape == "constant_mode_fn":
        # wide4 witness 2 (self-doubt): the decision function itself is pinned.
        mode_fn = (
            "internal fun recycleDispatchMode(isOnMainThread: Boolean): RecycleDispatch =\n"
            "    if (false) RecycleDispatch.DEFERRED else RecycleDispatch.INLINE\n"
        )
    # ---- [r3] G7 shapes: the permit accounting, per exit path ---------------
    # The clean shape is the REAL structure (reject branch releases, finally
    # releases); each dirty shape deletes exactly one `release` and keeps every
    # substring the earlier criteria look for.
    reject_rel = "            if (heavyHeld) heavyGate.release()\n"
    fin_rel = "            if (heavyHeld) heavyGate.release()\n"
    has_finally = True  # noqa: F841
    if shape == "leak_on_reject":            # witness #21
        reject_rel = ""
    elif shape == "leak_on_success":         # witness #22
        fin_rel = ""
    elif shape == "dead_reject_release":
        reject_rel = "            if (false && heavyHeld) heavyGate.release()\n"
    elif shape == "dead_finally_release":
        fin_rel = "            if (false) heavyGate.release()\n"
    elif shape == "no_finally":
        has_finally = False

    command = (
        "        val heavyHeld = awaitHeavyGatePermitInterruptibly(heavyGate, 1000L)\n"
        "        if (!heavyHeld) {\n"
        "            return\n"
        "        }\n"
        "        val acquired = awaitGlobalShellSlotInterruptibly(\n"
        "            globalConcurrency, heavyGate, heavyHeld)\n"
        "        if (!acquired) {\n"
        + reject_rel +
        "            return\n"
        "        }\n"
    )
    if shape == "no_finally":
        command += (
            "        try {\n"
            "            runCommand()\n"
            "        } catch (e: Exception) {\n"
            "            throw e\n"
            "        }\n")
    else:
        command += (
            "        try {\n"
            "            runCommand()\n"
            "        } finally {\n"
            "            globalConcurrency.release()\n"
            + fin_rel +
            "        }\n")
    return (
        "package com.rikkaminis.app.sandbox\n"
        "\n"
        "import java.util.concurrent.Semaphore\n"
        "import java.util.concurrent.TimeUnit\n"
        "\n"
        + heavy +
        "\n"
        "internal suspend fun awaitGlobalShellSlotInterruptibly(\n"
        "    globalConcurrency: Semaphore,\n"
        "    heavyGate: Semaphore,\n"
        "    heavyGateHeld: Boolean,\n"
        "): Boolean = runInterruptible(Dispatchers.IO) {\n"
        "    internalAcquireGlobalShellSlot(globalConcurrency, heavyGate, heavyGateHeld)\n"
        "}\n"
        "\n"
        "internal fun internalAcquireGlobalShellSlot(\n"
        "    globalConcurrency: Semaphore,\n"
        "    heavyGate: Semaphore,\n"
        "    heavyGateHeld: Boolean,\n"
        "): Boolean {\n"
        + rollback +
        "}\n"
        "\n"
        "internal enum class RecycleDispatch { INLINE, DEFERRED }\n"
        "\n"
        + mode_fn +
        "\n"
        "object ExecutionCoordinator {\n"
        "    private suspend fun executeCommand(\n"
        "        globalConcurrency: Semaphore,\n"
        "        heavyGate: Semaphore,\n"
        "    ) {\n"
        + command +
        "    }\n"
        "\n"
        "    private fun postRecycleMemoryRecovery() {\n"
        + dispatch +
        "    }\n"
        "\n"
        "    private fun postRecycleMemoryRecoveryNow() {\n"
        "        Thread.sleep(120)\n"
        "    }\n"
        "}\n"
    )


def selftest():
    """Negative arms for the three wide3 witnesses + the clean shape."""
    import tempfile
    import shutil

    arms = [
        ("clean tree stays green", "good", 0, None),
        ("witness G2: acquire moved out of the interruptible lambda",
         "acquire_outside_lambda", 1, MARK_INNER),
        ("witness G4: rollback behind `false &&`", "dead_rollback", 1, MARK_DEAD),
        ("witness G5: dispatch argument pinned to false",
         "dead_dispatch_arg", 1, MARK_DEAD),
        # ---- [wide4] the five externally falsified shapes -------------------
        # Each keeps every substring the wide3 criteria looked for and drops
        # only the decision, so each expectation names exactly one marker.
        ("w4-when-false rollback hides a live inner if", "when_dead_rollback",
         1, MARK_DEAD),
        ("w4-nested dead if wraps the rollback", "nested_dead_if_rollback",
         1, MARK_DEAD),
        ("w4-char literal `'\"'` no longer swallows the early throw",
         "char_literal_escape", 1, "rethrows BEFORE releasing the heavy permit"),
        ("w4-dispatch arg derived from a constant comparison", "dead_dispatch_compare",
         1, MARK_DEAD),
        ("w4-when arms swap their actions", "swapped_when_arms", 1, MARK_DISPATCH),
        ("w4-launch parked in the INLINE arm", "dead_inline_arm", 1, MARK_DISPATCH),
        ("w4-acquire parked in an unevaluated nested lambda", "unused_lambda_acquire",
         1, MARK_INNER),
        ("w4-interruptible block on Dispatchers.Main", "main_dispatcher", 1, MARK_IO),
        ("w4-decision function pinned to a constant", "constant_mode_fn", 1, MARK_DEAD),
        # ---- [r3] the two exits that are not exceptions ----------------------
        # Both keep the acquire, the wrappers, the catch and every substring;
        # only the RETURN of the permit is deleted.
        ("r3-leak: the reject branch keeps the heavy permit", "leak_on_reject",
         1, MARK_PERMIT),
        ("r3-leak: the finally no longer returns the permit", "leak_on_success",
         1, MARK_PERMIT),
        ("r3-dead: the reject branch's release sits behind `false &&`",
         "dead_reject_release", 1, MARK_PERMIT),
        ("r3-dead: the finally's release sits behind `if (false)`",
         "dead_finally_release", 1, MARK_PERMIT),
        ("r3-structure: a guarded try with no finally is red", "no_finally",
         1, MARK_PERMIT),
    ]
    ok = True
    for name, shape, want_rc, needle in arms:
        root = tempfile.mkdtemp(prefix="execgate_")
        try:
            target = os.path.join(root, *TARGET.split("/"))
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "w", encoding="utf-8") as f:
                f.write(selftest_fixture(shape))
            old_argv = sys.argv
            sys.argv = ["exec_gate_release_check.py", root]
            try:
                import contextlib
                import io
                buf = io.StringIO()
                with contextlib.redirect_stdout(buf):
                    rc = main()
                out = buf.getvalue()
            finally:
                sys.argv = old_argv
        finally:
            shutil.rmtree(root, ignore_errors=True)
        passed = rc == want_rc and (needle is None or needle in out)
        print(f"  selftest {name}: {'ok' if passed else 'FAIL'} (rc={rc})")
        if not passed:
            ok = False
            if needle is not None and needle not in out:
                print(f"      - expected {needle!r} in the gate output")
            print(out)
    print(f"EXEC_GATE_SELFTEST={'PASS' if ok else 'FAIL'} ({len(arms)} arms)")
    return 0 if ok else 1


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    sys.exit(main())
