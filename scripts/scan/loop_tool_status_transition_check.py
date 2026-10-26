#!/usr/bin/env python3
"""Loop tool-status transition gate — RikkaMinis CI scan gate.

## Why this gate exists

[deep-audit-1019 ISSUE-02] `AgentLoopEngine.runAgentLoop` dispatches tools in
three passes: Pass 1 decides whether each queued call may run (same-turn dedupe /
truncation guard / loop detector / preflight), Pass 2 hands the survivors to
`host.executeTool`, Pass 3 finalises the blocks. The PENDING → RUNNING flip used
to sit in Pass 1, ABOVE both refusal gates, and that single misplacement caused
two user-visible defects at once:

  1. a call the loop detector refused with CRITICAL had already been published as
     RUNNING, and the CRITICAL branch edited the block in memory only — no
     `updateAssistantMessage` — so the tool card kept its spinner (every sibling
     refusal path publishes: dedupe, truncation, preflight);
  2. the sequential branch's "waiting for previous tool(s)" cue is guarded on
     PENDING, and Pass 1 had already flipped every queued call, so the guard
     could never match and the cue was unreachable dead code.

Both are invisible to every other gate (it compiles, i18n is untouched, the
four-way sync is complete) and invisible to value-level tests — the state is
right in memory, only the publication is wrong. What this gate pins is the
*placement*: who publishes RUNNING, and that a refusal publishes at all.

## What it enforces (on the comment-stripped engine source)

  T1  the CRITICAL loop-detector branch contains a UI push
      (`host.updateAssistantMessage(`) before its `continue`;
  T2  the Pass-1 region (Pass-1 tool loop → Pass-2 dispatch condition) contains
      NO `ToolBlockStatus.RUNNING` at all — Pass 1 must never publish a start,
      in either polarity (`copy(toolStatus = …)` or a direct assignment);
  T3  the sequential branch asks `ToolPassTransitionPolicy.sequentialQueueCueIndices`
      for its cue indices, the cue literal is still there, and the cue site is
      guarded on PENDING (so it can only annotate a call that has not started);
  T4  the cue is published BEFORE the dispatch flip, which is BEFORE the call is
      handed to `host.executeTool` — the order that makes the cue visible while
      an earlier call runs instead of being overwritten in the same iteration;
  T5  the dispatch flip goes through `ToolPassTransitionPolicy.statusAtDispatch`
      at BOTH dispatch sites (parallel batch + sequential), i.e. no branch flips
      a block on its own with a hard-coded RUNNING;
  T6  the sequential dispatch flip retires the queue cue through
      `ToolPassTransitionPolicy.contentAtDispatchFlip` — the cue text is not tool
      output, and the finalisation pass keeps max(existing, result) BY LENGTH, so
      a leftover 38-char cue would be shown and persisted as that tool's output.

Offsets are preserved when comments are blanked, so the region anchors (which are
themselves comments) still resolve while the criteria read code only — a
criterion satisfied by a comment must NOT count (see the `comment_only_push`
self-test fixture).

## wide4 (2026-10-25): decisions removed, spellings kept

An external audit re-attacked this gate five times and found that every criterion
tested for a TOKEN rather than for a DECISION:

  T1/T3  `if (!true) publish()` / `if (!true && … != PENDING) continue` are the
         same defects; liveness is now decided by const folding with Kotlin
         precedence (`!true && x` == false), and `when (false) { … }` counts as a
         guard context exactly like `if (false)`;
  T1     the projection is the shared lexer's: a `"${"}"}"` template used to leak
         a `}` and close a dead `if (false)` block early, which made the dead
         publish read as live (parser-death);
  T3     a guard can also invert instead of disappearing: `== PENDING → continue`
         skips exactly the waiting calls;
  T5/T6  "the body has an `if`" was the whole criterion — it now parses the
         policy expression: T5 needs a LIVE PENDING comparison whose branches
         are RUNNING / null, and T6 needs one branch to clear the cue (`""`) on
         the branch that MATCHES it.

## Registration

Registration into scripts/scan/scan.sh + the test_scan.py marker fixture is the
main session's job (this branch may not touch scan.sh / test_scan.py).

Usage:
  python3 scripts/scan/loop_tool_status_transition_check.py <repo_root>
  python3 scripts/scan/loop_tool_status_transition_check.py --selftest
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import analyse                                       # noqa: E402
from _kotlin_ast import (const_bool, function_body, if_blocks,          # noqa: E402
                         match_bracket, split_top as ast_split_top,
                         stmt_spans, strip_parens, when_branches)


class Unparseable(Exception):
    """The engine cannot be lexed — the gate refuses to judge a broken view."""


def _views(src):
    ok, reason, views = analyse(src)
    if not ok:
        raise Unparseable(reason)
    return views


def blank_comments(src: str) -> str:
    """Comment-free projection, offsets and newlines preserved.

    Region anchors are comments, so the text cannot be pre-stripped; the criteria
    read this projection with identical indices. String literals stay intact —
    the cue literal IS a criterion subject (`code.count(CUE_TEXT)`).

    [wide4 parser-death] This used to be a hand-written scanner with its own idea
    of strings and comments. It is now the shared state machine: nested block
    comments, `'"'`, raw strings and `${…}` templates are all handled in one
    place, and a failed parse raises instead of silently judging raw text.
    """
    return _views(src)["comments"]


def line_of(src: str, idx: int) -> int:
    return src.count("\n", 0, idx) + 1


# ── structure helpers (wide3: span/liveness based, never line- or spelling-based)

def mask_code(src):
    """Comment text AND string/char literal contents blanked, offsets preserved.

    Brace matching and guard detection read THIS: a paren inside a string literal
    must not shift the depth, a `}` inside a `${…}` template must not close an
    `if` block early, and a criterion satisfied by a commented-out line is not a
    criterion.
    """
    return _views(src)["code"]


REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/AgentLoopEngine.kt"

LOOP_HEADER = "for ((id, name, args) in toolCalls) {"
PASS2_COND = "if (pending.size > 1 && pending.all {"
PASS3_MARK = "// ============================ Pass 3 ============================"
CRITICAL_GUARD = "if (precheck.level == Level.CRITICAL) {"
CUE_INDICES_CALL = "ToolPassTransitionPolicy.sequentialQueueCueIndices("
CUE_TEXT = '"⏳ Waiting for previous tool(s) to finish…"'
DISPATCH_FLIP_CALL = "ToolPassTransitionPolicy.statusAtDispatch("
EXECUTE_CALL = "resultsById[p.id] = host.executeTool("
UI_PUSH = "host.updateAssistantMessage("
PENDING = "ToolBlockStatus.PENDING"
RUNNING = "ToolBlockStatus.RUNNING"
CUE_CONST_REF = "ToolPassTransitionPolicy.QUEUE_CUE_TEXT"
FLIP_CONTENT_CALL = "ToolPassTransitionPolicy.contentAtDispatchFlip("

CONTINUE_STMT = re.compile(r"\n[ \t]*continue[ \t]*\n")

# ── round-3 additions (dual-gate seams, witnesses #124 / #125 / #126) ────
# T7  Pass 3's confluence must map every outcome to a TERMINAL status. Both
#     gates read the flip INTO RUNNING (T2/T5) and the publish paths (T1/T8),
#     but nothing read the flip OUT of it: `result.success -> RUNNING` keeps
#     every finished card spinning and sends ToolOutcome.Unknown to the overlay.
# T8  every Pass-1 refusal must PUBLISH. T1 only pinned the CRITICAL branch, so
#     deleting the sibling `preflightError` publish left a blocked call stuck on
#     whatever the stream last published (PENDING).
# T9  `sequentialQueueCueIndices` must DECIDE. T3 pins the call site, T5/T6 pin
#     the sibling policy functions' bodies — the cue-index body itself was never
#     read, so `= emptyList()` silently removed the waiting cue.
TERMINAL_STATUSES = ("SUCCESS", "TIMEOUT", "FAILED")
NON_TERMINAL_STATUSES = ("RUNNING", "PENDING")
EMPTY_COLLECTION_RE = re.compile(
    r"^(?:emptyList|emptySet|emptyArray|emptySequence|listOf|setOf|arrayListOf|mutableListOf|"
    r"mutableSetOf|arrayOf)\s*(?:<[^<>]*>)?\s*\(\s*\)$")
FINAL_STATUS_DECL = re.compile(r"\bval\s+finalStatus\s*=\s*when\s*\{")

SELFTEST_MARKER = "LOOP_TOOL_STATUS_SELFTEST=PASS"


# ── structure helpers (wide3: span/liveness based, never line- or spelling-based)

def match_close(text, start, opener, closer):
    depth = 0
    for i in range(start, len(text)):
        if text[i] == opener:
            depth += 1
        elif text[i] == closer:
            depth -= 1
            if depth == 0:
                return i
    return -1


IF_RE = re.compile(r"\bif\s*\(")


def if_statements(masked):
    out = []
    for m in IF_RE.finditer(masked):
        p = m.end() - 1
        close = match_close(masked, p, "(", ")")
        if close < 0:
            continue
        j = close + 1
        while j < len(masked) and masked[j] in " \t":
            j += 1
        if j < len(masked) and masked[j] == "{":
            body_end = match_close(masked, j, "{", "}")
            if body_end < 0:
                body_end = len(masked)
        else:
            # Statement body (no braces right after the `)`): it runs to the end
            # of the STATEMENT, which may carry a trailing lambda block
            # (`if (false) withContext(Dispatchers.Main) { host.push(…) }`) — a
            # line-based end would stop before the block and let the dead guard
            # hide the publish from every liveness check.
            k, depth = j, 0
            while k < len(masked):
                ch = masked[k]
                if ch in "([{":
                    depth += 1
                elif ch in ")]}":
                    if depth == 0:
                        break
                    depth -= 1
                elif ch == "\n" and depth == 0:
                    nxt = masked[k + 1:].lstrip(" \t")
                    if nxt[:1] in (".", "?", "&", "|"):
                        k += 1
                        continue
                    break
                k += 1
            body_end = k
        out.append((p, close, j, body_end, masked[p + 1:close]))
    return out


def enclosing(ifs, whens, idx):
    """Conditions that enclose `idx` — `if` bodies plus `when` branches.

    [wide4 w4-T1] `when (false) { publish() }` is the same dead publish as
    `if (false) publish()`; both contexts count so neither spelling buys a pass.
    """
    hits = [s for s in ifs if s[2] <= idx <= s[3]]
    hits.sort(key=lambda s: s[0])
    conds = [s[4] for s in hits]
    conds += [c for c, start, end in whens if start <= idx < end]
    return conds


def split_top(expr, op):
    parts, cur, depth, i = [], [], 0, 0
    while i < len(expr):
        ch = expr[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if depth == 0 and expr.startswith(op, i):
            parts.append("".join(cur))
            cur = []
            i += len(op)
            continue
        cur.append(ch)
        i += 1
    parts.append("".join(cur))
    return parts


def norm_atom(expr):
    s = expr.strip()
    while True:
        if s.startswith("(") and match_close(s, 0, "(", ")") == len(s) - 1:
            s = s[1:-1].strip()
        elif s.startswith("(") or s.endswith(")"):
            s = s.strip("() \t")
        else:
            return s


def _fold_bool(expr):
    """Boolean value of `expr` when literals alone decide it, else None.

    Kotlin precedence is applied here — `||` lowest, then `&&`, then unary `!` —
    because the shared folder tests a leading `!` BEFORE splitting the operators:
    `!true && x` (== `false && x` == false) came back "unknown" and every
    literal-short-circuited guard written that way stayed green (wide4 w4-T1/T3).
    Atoms go to the shared folder.
    """
    e = strip_parens(expr.strip())
    if not e:
        return None
    for op in ("||", "&&"):
        parts = split_top(e, op)
        if len(parts) > 1:
            vals = [_fold_bool(p) for p in parts]
            if op == "||":
                if any(v is True for v in vals):
                    return True
                return None if any(v is None for v in vals) else False
            if any(v is False for v in vals):
                return False
            return None if any(v is None for v in vals) else True
    if e.startswith("!") and not e.startswith("!="):
        v = _fold_bool(e[1:])
        return None if v is None else (not v)
    return const_bool(e)


def is_lit(expr, word):
    return _fold_bool(expr) is {"true": True, "false": False}.get(word)


def always_false(cond):
    """True when `cond` can never hold.

    [wide4 w4-T1/T3] The spelling `false` was only one way to say it; `!true`,
    `0 == 1`, `false && guard` and `!false` decide exactly the same way, and the
    old literal comparison let them all through. The decision is now taken by the
    shared const folder (short-circuit semantics included), so a guard that
    cannot hold can never be counted as live again.
    """
    return _fold_bool(cond) is False


def always_true(cond):
    return _fold_bool(cond) is True


def when_contexts(masked):
    """Contexts opened by `when` branches: [(cond, start, end)].

    [wide4 w4-T1] `when (false) { … }` wraps a publish exactly like `if (false)`
    does; a branch is dead when its own condition is a constant false, or when
    the subject is a constant false (`else ->` always lives).
    """
    out = []
    for subject, cond, b_start, b_end in when_branches(masked):
        conde = " ".join(cond.split())
        if not conde or conde == "else":
            continue
        if _fold_bool(subject) is False:
            conde = "false"
        out.append((conde, b_start, b_end))
    return out


def cue_guard_polarity(ifs, masked, lo, hi):
    """Conditions of `… PENDING … → continue` guards that ask the WRONG question.

    The cue branch must skip the blocks that are NOT pending: `!=` (or a
    negated test). `if (… == PENDING) continue` skips exactly the waiting calls
    and annotates the running ones — the cue inverts into noise. Returns the
    offending conditions (empty = every such guard is negative).
    """
    out = []
    for p, close, body_start, body_end, cond in ifs:
        if not (lo <= body_start <= hi):
            continue
        body = masked[body_start:body_end].strip()
        if not re.match(r"^\{?\s*continue\b", body):
            continue
        if PENDING not in cond or always_false(cond) or always_true(cond):
            continue
        norm = " ".join(cond.split())
        neg = re.search(r"!=\s*" + re.escape(PENDING), norm) or \
            re.search(r"!\s*\(\s*[^)]*" + re.escape(PENDING), norm)
        pos = re.search(r"(?<![=!<>])==\s*" + re.escape(PENDING), norm)
        if not neg or pos:
            out.append(norm)
    return out


def split_else_top(text):
    """Split `A else B` on the depth-0 `else` keyword."""
    depth = 0
    i = 0
    while i < len(text):
        ch = text[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif depth == 0 and text.startswith("else", i) \
                and (i == 0 or not (text[i - 1].isalnum() or text[i - 1] == "_")):
            nxt = text[i + 4:i + 5]
            if not (nxt.isalnum() or nxt == "_"):
                return text[:i], text[i + 4:]
        i += 1
    return text, ""


def branch_value(raw):
    """Normalized value of one branch; a `{ … }` block collapses to its last stmt."""
    v = raw.strip()
    while v.startswith("{") and match_close(v, 0, "{", "}") == len(v) - 1:
        inner = v[1:-1]
        stmts = [inner[a:b].strip() for a, b in stmt_spans(inner)]
        stmts = [s for s in stmts if s]
        if not stmts:
            return ""
        v = stmts[-1]
    v = " ".join(v.split())
    # `{ return "" }` is the same value as `""`
    if v.startswith("return ") or v == "return":
        v = v[len("return"):].strip()
    return v


def policy_if(masked, code, span):
    """(cond, then_value, else_value) of the `if (…) A else B` that IS the body.

    Structure comes from the masked projection; the branch VALUES are read from
    the comment-free projection so the empty-string literal `""` — the entire
    point of T6 — stays visible.
    """
    m = re.search(r"\bif\s*\(", masked[span[0]:span[1]])
    if not m:
        return None
    p_open = span[0] + m.end() - 1
    p_close = match_close(masked, p_open, "(", ")")
    if p_close < 0 or p_close > span[1]:
        return None
    cond = " ".join(masked[p_open + 1:p_close].split())
    rest_start = p_close + 1
    then_raw, else_raw = split_else_top(masked[rest_start:span[1]])
    cut = rest_start + len(then_raw)
    then_v = branch_value(code[rest_start:cut])
    else_v = branch_value(code[cut + 4:span[1]]) if else_raw.strip() else ""
    return cond, then_v, else_v


def fun_body(masked, name):
    """Body span of `fun name(...)`, from the shared primitive.

    Located from the parameter list (a lambda in an expression body owns the
    first `{`), and an expression body ends at the first depth-0 newline that is
    not a continuation — the old `\\n\\n`-based stop swallowed trailing code.
    """
    span = function_body(masked, name)
    return None if span is None else (span[0], span[1])


def short_body(text, limit=80):
    return " ".join(text.split())[:limit]


def policy_sig_params(masked, name):
    m = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", masked)
    if not m:
        return None
    close = match_close(masked, m.end() - 1, "(", ")")
    if close < 0:
        return None
    return [p.group(1) for p in re.finditer(r"\b([A-Za-z_]\w*)\s*:", masked[m.end():close])]


def refusal_branch(ifs, masked, at):
    """(body_start, body_end) of the innermost `if` that contains `at` AND
    terminates after it — the refusal branch a block edit belongs to.

    Deriving the branch instead of naming it is what makes a SIBLING refusal
    path visible: the old T1 was anchored on the CRITICAL guard, so a fourth
    refusal gate added (or emptied) next to it was never read.
    """
    best = None
    for _p, _close, b_start, b_end, _cond in ifs:
        if not (b_start <= at < b_end):
            continue
        if not re.search(r"\b(?:continue|break|return|throw)\b", masked[at:b_end]):
            continue
        if best is None or (b_end - b_start) < (best[1] - best[0]):
            best = (b_start, b_end)
    return best


def check(src: str, label: str):
    """Return (violations, notes). violations empty = PASS."""
    code = blank_comments(src)
    bad, notes = [], []

    # ── Regions (anchored on the raw text, read from the masked projection) ──
    masked = mask_code(src)
    ifs = if_statements(masked)
    whens = when_contexts(masked)
    # (the LOOP_HEADER / PASS3_MARK anchors are comments themselves, so they are
    # looked up in the RAW source; the mask can never contain them.)
    header_at = src.find(LOOP_HEADER, src.find("// Pass 1 (sequential)"))
    pass2_at = masked.find(PASS2_COND)
    pass3_at = src.find(PASS3_MARK)
    critical_at = masked.find(CRITICAL_GUARD)
    execute_call_at = code.find(EXECUTE_CALL)
    for name, at in (
        ("Pass-1 tool loop", header_at),
        ("Pass-2 dispatch condition", pass2_at),
        ("Pass-3 marker", pass3_at),
        ("CRITICAL guard", critical_at),
        ("sequential execute call", execute_call_at),
    ):
        if at < 0:
            bad.append("%s:1: anchor not found: %s" % (label, name))
    if bad:
        return bad, notes

    # The cue-index call is what T3 pins; it must be REQUIRED, not just an
    # anchor — a missing call must be reported by T3, never short-circuit the
    # other criteria.
    cue_call_at = code.find(CUE_INDICES_CALL)
    if cue_call_at < 0:
        cue_call_at = execute_call_at

    pass1 = code[header_at:pass2_at]
    pass2_seq = code[cue_call_at:execute_call_at]
    # The CRITICAL branch is delimited by BRACE DEPTH, not by "the first line that
    # is exactly `continue`": `if (true) continue` / `continue // blocked` used to
    # push the terminator search past this branch and swallow the NEXT refusal
    # branch's publish as if it belonged here (wide3 T1 parser-death).
    crit_open = masked.find("{", critical_at + len(CRITICAL_GUARD) - 1)
    crit_end = match_close(masked, crit_open, "{", "}") if crit_open > 0 else -1
    if crit_end < 0:
        bad.append("%s:%d: the CRITICAL branch braces are unbalanced — cannot delimit it"
                   % (label, line_of(src, critical_at)))
        return bad, notes
    critical = code[critical_at:crit_end]
    crit_ifs = [s for s in ifs if crit_open < s[2] and s[3] < crit_end]
    terminators = 0
    for m in re.finditer(r"\b(?:continue|break|return|throw)\b", critical):
        guards = enclosing(crit_ifs, whens, critical_at + m.start())
        if any(always_false(g) for g in guards):
            continue
        terminators += 1
    if not terminators:
        bad.append("%s:%d: the CRITICAL branch has no live terminating `continue` — the refusal "
                   "would fall through into execution" % (label, line_of(src, critical_at)))
        return bad, notes

    # ── T1: a refusal publishes — and the publish must be LIVE ───────────────
    pushes = [critical_at + m.start() for m in re.finditer(re.escape(UI_PUSH), critical)]
    live_push = [at for at in pushes
                 if not any(always_false(g) for g in enclosing(crit_ifs, whens, at))]
    if not live_push:
        if pushes:
            bad.append("%s:%d: the CRITICAL loop-detector branch publishes the refusal only under a "
                       "dead guard (literal-short-circuited) — the call is spelled out, the card "
                       "still keeps spinning" % (label, line_of(src, critical_at)))
        else:
            bad.append("%s:%d: the CRITICAL loop-detector branch records the refusal without "
                       "publishing it (no `%s`) — the tool card keeps spinning"
                       % (label, line_of(src, critical_at), UI_PUSH))
    else:
        notes.append("T1 CRITICAL branch publishes its refusal")

    # ── T2: Pass 1 never publishes a start ───────────────────────────────────
    if RUNNING in pass1:
        bad.append("%s:%d: Pass 1 flips a call to %s — a call refused by the loop detector or "
                   "preflight then shows a spinner nothing retracts, and Pass 2's PENDING-guarded "
                   "queue cue becomes unreachable (ISSUE-02)"
                   % (label, line_of(src, header_at + pass1.index(RUNNING)), RUNNING))
    else:
        notes.append("T2 Pass-1 region publishes no RUNNING")

    # ── T3: the sequential cue exists, is index-driven and PENDING-guarded ───
    cue_publish_at = code.find("cueBlock.copy(", cue_call_at)
    cue_published = cue_publish_at > 0 and CUE_CONST_REF in code[cue_publish_at:cue_publish_at + 300]
    if CUE_INDICES_CALL not in code:
        bad.append("%s:1: the sequential queue cue no longer asks %s for its indices"
                   % (label, CUE_INDICES_CALL))
    if not cue_published:
        bad.append("%s:1: the sequential branch no longer publishes the queue cue "
                   "(no `cueBlock.copy(... %s ...)` in the sequential region)"
                   % (label, CUE_CONST_REF))
    # The PENDING test must DECIDE: `if (false && cueBlock.toolStatus != PENDING) continue`
    # keeps the spelling and drops the guard, which is the whole point of T3.
    # [wide4 w4-T3] Two more spellings that keep it and drop it: a condition
    # decided by literals (`!true && …`), and a guard that asks the OPPOSITE
    # question (`== PENDING → continue` skips exactly the blocks it must annotate).
    live_pending = False
    for m in re.finditer(re.escape(PENDING), pass2_seq):
        at = cue_call_at + m.start()
        conds = list(enclosing(ifs, whens, at))
        # the token may sit in a guard's CONDITION (the usual shape:
        # `if (cueBlock.toolStatus != PENDING) continue`) rather than in its body
        conds += [s[4] for s in ifs if s[0] <= at <= s[1]]
        for cond in conds:
            if PENDING in cond and not always_false(cond) and not always_true(cond):
                live_pending = True
    if not live_pending:
        bad.append("%s:1: the sequential cue is not guarded on a LIVE %s test — it would annotate a "
                   "call that already started (a literal-short-circuited guard is the same defect)"
                   % (label, PENDING))
    wrong_polarity = cue_guard_polarity(ifs, masked, cue_call_at, execute_call_at)
    for cond in wrong_polarity:
        bad.append("%s:1: the sequential cue guard `%s` skips on a POSITIVE %s test — it annotates "
                   "every call that already started and never the ones still waiting"
                   % (label, cond, PENDING))
    if code.count(CUE_TEXT) != 1:
        bad.append("%s:1: the cue literal appears %d time(s) in code — it must be single-sourced in "
                   "%s, a second copy drifts" % (label, code.count(CUE_TEXT), "ToolPassTransitionPolicy"))
    if CUE_INDICES_CALL in code and cue_published and live_pending \
            and code.count(CUE_TEXT) == 1:
        notes.append("T3 sequential queue cue present, index-driven, PENDING-guarded, single-sourced")

    # ── T4: cue → flip → execute, in that order ──────────────────────────────
    cue_text_at = cue_publish_at
    flip_at = code.find(DISPATCH_FLIP_CALL, cue_call_at)
    if cue_text_at < 0 or flip_at < 0:
        bad.append("%s:1: cannot order cue (%d) and dispatch flip (%d) in the sequential branch"
                   % (label, cue_text_at, flip_at))
    elif not (cue_text_at < flip_at < execute_call_at):
        bad.append("%s:%d: sequential publication order is wrong (cue@%d, flip@%d, execute@%d) — the "
                   "cue must be published before the flip, and both before the dispatch"
                   % (label, line_of(src, flip_at), cue_text_at, flip_at, execute_call_at))
    else:
        notes.append("T4 cue → dispatch flip → executeTool order holds")

    # ── T5: both dispatch sites flip through the policy, and the policy DECIDES ─
    if code.count(DISPATCH_FLIP_CALL) < 2:
        bad.append("%s:1: only %d call site(s) of %s — the parallel batch and the sequential "
                   "dispatch must both flip through the policy"
                   % (label, code.count(DISPATCH_FLIP_CALL), DISPATCH_FLIP_CALL))
    else:
        notes.append("T5 both dispatch sites flip through the policy")
    # [wide3 T5] Delegating to the policy is only half the contract: an
    # unconditional `= ToolBlockStatus.RUNNING` inside statusAtDispatch keeps
    # both call sites and resurrects every refused block into a spinner. The
    # body must branch on PENDING.
    span = fun_body(masked, "statusAtDispatch")
    if span is None:
        bad.append("%s:1: `fun statusAtDispatch(` body not found — anchor moved or unbalanced"
                   % label)
    else:
        # [wide4 w4-T5] "there is an if" was the whole criterion: `if (current ==
        # PENDING || true)` and `if (true) RUNNING else RUNNING` both have one.
        # The body must now BE a live PENDING comparison whose branches return
        # RUNNING / null respectively.
        parts = policy_if(masked, code, span)
        why = None
        if parts is None:
            why = "its body `%s` has no branching `if`" % short_body(masked[span[0]:span[1]])
        else:
            cond, then_v, else_v = parts
            if always_true(cond) or always_false(cond):
                why = "its condition `%s` is decided by literals, so every status flips" % cond
            elif PENDING not in cond:
                why = "its condition `%s` never looks at %s" % (cond, PENDING)
            elif "==" not in cond:
                why = "its condition `%s` is not a PENDING comparison" % cond
            elif then_v != RUNNING:
                why = "the PENDING branch yields `%s`, not %s" % (then_v, RUNNING)
            elif else_v != "null":
                why = ("the non-PENDING branch yields `%s`, not null — a refused block would be "
                       "resurrected into a spinner" % else_v)
        if why:
            bad.append("%s:%d: `statusAtDispatch` no longer decides on %s — %s; an "
                       "unconditional flip resurrects a block Pass 1 already refused (FAILED) into "
                       "a RUNNING spinner"
                       % (label, line_of(src, span[0]), PENDING, why))

    # ── T6: starting a call retires its cue ──────────────────────────────────
    # Same shape as T5: the call site can stay while the body degrades into
    # `= current` (cue text never retired, then persisted as tool output).
    span = fun_body(masked, "contentAtDispatchFlip")
    params = policy_sig_params(masked, "contentAtDispatchFlip")
    if span is None or not params:
        bad.append("%s:1: `fun contentAtDispatchFlip(` body not found — anchor moved or unbalanced"
                   % label)
    else:
        # [wide4 w4-T6] The branch VALUES decide, not the presence of an `if`:
        # `if (current == queueCue) current else current` has a branch and never
        # retires anything. The strings-intact projection is used so the empty
        # literal is visible (the masked view blanks it).
        parts = policy_if(masked, code, span)
        missing = [p for p in params
                   if not re.search(r"\b" + re.escape(p) + r"\b", masked[span[0]:span[1]])]
        why = None
        if missing:
            why = "it never reads %s" % ", ".join(missing)
        elif parts is None:
            why = "its body `%s` has no branching `if`" % short_body(masked[span[0]:span[1]])
        else:
            cond, then_v, else_v = parts
            if always_true(cond) or always_false(cond):
                why = "its condition `%s` is decided by literals" % cond
            elif then_v == else_v:
                why = "both branches yield `%s` — the cue is never retired" % then_v
            elif set([then_v, else_v]) != {'""', params[0]}:
                why = ("its branches yield `%s` / `%s` — one must clear the cue to the empty "
                       "string, the other must pass the live content through"
                       % (then_v, else_v))
            elif re.search(r"(?<![=!<>])==\s*", cond) and then_v != '""':
                why = ("the `==` branch yields `%s` — the branch that MATCHES the cue is the one "
                       "that must clear it" % then_v)
            elif re.search(r"!=\s*", cond) and not re.search(r"(?<![=!<>])==\s*", cond) \
                    and else_v != '""':
                why = ("the `else` branch yields `%s` — the branch that MATCHES the cue is the one "
                       "that must clear it" % else_v)
        if why:
            bad.append("%s:%d: `contentAtDispatchFlip` no longer retires the queue cue — %s; a "
                       "leftover cue is shown and persisted as that tool's output"
                       % (label, line_of(src, span[0]), why))
    if FLIP_CONTENT_CALL not in code:
        bad.append("%s:1: the dispatch flip no longer retires the queue cue (%s) — the cue text is "
                   "not tool output and the finalisation pass keeps max(existing, result) BY "
                   "LENGTH, so a leftover cue would be shown as that tool's output"
                   % (label, FLIP_CONTENT_CALL))
    else:
        notes.append("T6 dispatch flip retires the queue cue")

    # ── T7: the Pass-3 confluence yields TERMINAL states only ───────────────
    fm = FINAL_STATUS_DECL.search(masked)
    if fm is None:
        bad.append("%s:1: `val finalStatus = when { … }` not found — Pass 3 must collapse the "
                   "execution result into a terminal block status (fail-closed)" % label)
    else:
        open_i = masked.index("{", fm.end() - 1)
        close_i = match_close(masked, open_i, "{", "}")
        if close_i < 0:
            bad.append("%s:%d: the finalStatus when-block is unbalanced — cannot audit the Pass-3 "
                       "status mapping" % (label, line_of(src, fm.start())))
        else:
            seg_code = code[fm.start():close_i + 1]
            branches = when_branches(masked[fm.start():close_i + 1])
            if not branches:
                bad.append("%s:%d: the finalStatus when-block has no readable branch — cannot audit "
                           "the Pass-3 status mapping (fail-closed)"
                           % (label, line_of(src, fm.start())))
            seen = []
            for _subject, cond, b_start, b_end in branches:
                cnorm = " ".join(cond.split())
                val = branch_value(seg_code[b_start:b_end])
                seen.append((cnorm, val))
                at_line = line_of(src, fm.start() + b_start)
                if not val.startswith("ToolBlockStatus."):
                    bad.append("%s:%d: the finalStatus branch `%s` yields '%s' — Pass 3 must map "
                               "each outcome to a ToolBlockStatus constant (fail-closed)"
                               % (label, at_line, cnorm, val))
                    continue
                status = val.split(".")[-1]
                if status in NON_TERMINAL_STATUSES:
                    bad.append("%s:%d: a finished tool is mapped back to %s — Pass 3 is the only "
                               "place a call stops being RUNNING; the block, the persisted turn "
                               "parts and every card then keep the spinner forever, and the overlay "
                               "receives ToolOutcome.Unknown" % (label, at_line, val))
                if cnorm != "else" and always_false(cond):
                    bad.append("%s:%d: the finalStatus branch `%s` can never be taken — that outcome "
                               "falls through to the next branch" % (label, at_line, cnorm))
            if not any("success" in c and v.endswith("SUCCESS") for c, v in seen):
                bad.append("%s:%d: no `result.success -> …SUCCESS` branch — a call that succeeded "
                           "never reaches the terminal SUCCESS state"
                           % (label, line_of(src, fm.start())))
            if not any("timedOut" in c and v.endswith("TIMEOUT") for c, v in seen):
                bad.append("%s:%d: no `result.timedOut -> …TIMEOUT` branch — a timed-out call is "
                           "reported as some other outcome" % (label, line_of(src, fm.start())))
            if not any(c == "else" and v.endswith("FAILED") for c, v in seen):
                bad.append("%s:%d: the finalStatus when has no `else -> …FAILED` fallback — an "
                           "unexpected result leaves the block non-terminal"
                           % (label, line_of(src, fm.start())))

    # ── T8: EVERY Pass-1 refusal publishes its flip ─────────────────────────
    refusal_writes = [header_at + m.start()
                      for m in re.finditer(re.escape("ToolBlockStatus.FAILED"), pass1)]
    if not refusal_writes:
        bad.append("%s:1: Pass 1 records no FAILED refusal at all — the refusal gates (truncation / "
                   "loop detector / preflight) are gone (fail-closed)" % label)
    for at in refusal_writes:
        line = line_of(src, at)
        branch = refusal_branch(ifs, masked, at)
        if branch is None:
            bad.append("%s:%d: a Pass-1 refusal sets FAILED outside a terminating branch — the "
                       "refusal would fall through into dispatch" % (label, line))
            continue
        b_start, b_end = branch
        term = re.search(r"\b(?:continue|break|return|throw)\b", masked[at:b_end])
        limit = at + term.start() if term else b_end
        pushes = [b_start + m.start()
                  for m in re.finditer(re.escape(UI_PUSH), code[b_start:b_end])]
        live = [p for p in pushes if p < limit
                and not any(always_false(g) for g in enclosing(ifs, whens, p))]
        if not live:
            bad.append("%s:%d: a Pass-1 refusal marks the block FAILED but never publishes it "
                       "before its terminator (no live `%s`) — the card keeps whatever the stream "
                       "last published (PENDING), so a blocked call shows no error and spins until "
                       "the next turn" % (label, line, UI_PUSH))
    if refusal_writes:
        notes.append("T8 every Pass-1 refusal publishes before it continues")

    # ── T9: the cue-index policy DECIDES ────────────────────────────────────
    span = fun_body(masked, "sequentialQueueCueIndices")
    if span is not None:
        # The shared expression-body stop can run past the body when a KDoc
        # follows it (comments are blanked, so the next line looks like a
        # continuation). Clamp at the next declaration so the judgement stays
        # inside THIS function.
        nxt = re.search(r"\n[ \t]*(?:private |internal |public |override )*fun\s+\w+",
                        masked[span[0]:span[1]])
        if nxt:
            span = (span[0], span[0] + nxt.start())
    params = policy_sig_params(masked, "sequentialQueueCueIndices")
    if span is None or not params:
        bad.append("%s:1: `fun sequentialQueueCueIndices(` body not found — anchor moved or "
                   "unbalanced (fail-closed)" % label)
    else:
        body = masked[span[0]:span[1]]
        parts = policy_if(masked, code, span)
        why = None
        if parts is None:
            why = ("its body `%s` has no branching `if` — a constant list queues nobody, so the "
                   "second..Nth serial calls show a blank card instead of the waiting cue"
                   % short_body(body))
        elif not re.search(r"\b" + re.escape(params[0]) + r"\b", body):
            why = "it never reads its `%s` parameter" % params[0]
        else:
            cond, then_v, else_v = parts
            if always_true(cond) or always_false(cond):
                why = "its condition `%s` is decided by literals" % cond
            elif params[0] not in cond:
                why = "its condition `%s` never looks at %s" % (cond, params[0])
            elif not re.search(r"[<>]=?|==", cond):
                why = "its condition `%s` does not compare the pending count" % cond
            elif then_v == else_v:
                why = "both branches yield `%s` — the count decides nothing" % then_v
            else:
                vals = (then_v, else_v)
                empties = [v for v in vals if EMPTY_COLLECTION_RE.match(v)]
                if len(empties) != 1:
                    why = ("its branches yield `%s` / `%s` — exactly one must be the empty list and "
                           "the other the queued-index range" % vals)
                else:
                    other = [v for v in vals if not EMPTY_COLLECTION_RE.match(v)][0]
                    if params[0] not in other:
                        why = ("its non-empty branch yields `%s` — it must derive the range from "
                               "%s" % (other, params[0]))
        if why:
            bad.append("%s:%d: `sequentialQueueCueIndices` no longer decides which calls are "
                       "queued — %s; Pass 2 still calls it and still writes the cue, but the index "
                       "list is empty, so no card ever shows the waiting cue"
                       % (label, line_of(src, span[0]), why))
        else:
            notes.append("T9 cue-index policy decides on the pending count")

    return bad, notes


def run(repo: str) -> int:
    path = os.path.join(repo, REL)
    if not os.path.exists(path):
        print("FAIL loop_tool_status_transition (missing %s)" % REL)
        return 1
    with open(path, "r", encoding="utf-8") as fh:
        src = fh.read()
    try:
        bad, notes = check(src, REL)
    except Unparseable as exc:
        # fail-closed: an unviewable engine is a violation, never a pass
        bad, notes = ["%s: lex: the engine cannot be lexed (%s) — the gate refuses to judge a "
                      "broken lexical projection" % (REL, exc)], []
    for note in notes:
        print("  [OK] %s" % note)
    if bad:
        for b in bad:
            print("  [X] %s" % b)
        print("FAIL loop_tool_status_transition (%d violation(s))" % len(bad))
        return 1
    print("PASS loop_tool_status_transition (Pass 1 decides, Pass 2 publishes, "
          "refusals reach the UI)")
    return 0


# ── self-test: negative controls, one per criterion ──────────────────────────

FIXTURES = [
    # (name, expected criterion substring, mutation)
    ("drop_critical_push", "CRITICAL loop-detector branch records the refusal",
     lambda s: s.replace(
         """                        withContext(Dispatchers.Main) {
                            host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                        }
                    }
                    // Record the blocked attempt""",
         """                    }
                    // Record the blocked attempt""", 1)),
    ("comment_only_push", "CRITICAL loop-detector branch records the refusal",
     lambda s: s.replace(
         """                        withContext(Dispatchers.Main) {
                            host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                        }
                    }
                    // Record the blocked attempt""",
         """                        // host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                    }
                    // Record the blocked attempt""", 1)),
    ("pass1_flips_running", "Pass 1 flips a call to",
     lambda s: s.replace(
         "                val argsStr = args.toString()\n                val paramsMap = parseToolParams(argsStr)\n"
         "                // [deep-audit-1019 ISSUE-02] No status flip here any more.",
         "                val argsStr = args.toString()\n                val paramsMap = parseToolParams(argsStr)\n"
         "                loopState.allToolBlocks[0] = loopState.allToolBlocks[0].copy(toolStatus = ToolBlockStatus.RUNNING)\n"
         "                // [deep-audit-1019 ISSUE-02] No status flip here any more.", 1)),
    ("cue_indices_call_gone", "no longer asks",
     lambda s: s.replace(
         "val cueIndices = ToolPassTransitionPolicy.sequentialQueueCueIndices(pending.size)",
         "val cueIndices = emptyList<Int>()", 1)),
    ("cue_const_gone", "no longer publishes the queue cue",
     lambda s: s.replace(
         """loopState.allToolBlocks[cueIdx] = cueBlock.copy(
                            content = ToolPassTransitionPolicy.QUEUE_CUE_TEXT,
                        )""",
         "loopState.allToolBlocks[cueIdx] = cueBlock", 1)),
    ("cue_literal_duplicated", "cue literal appears",
     lambda s: s.replace(
         "const val QUEUE_CUE_TEXT = \"⏳ Waiting for previous tool(s) to finish…\"",
         "const val QUEUE_CUE_TEXT = \"⏳ Waiting for previous tool(s) to finish…\"\n"
         "    // second copy\n"
         "    const val QUEUE_CUE_TEXT_FALLBACK = \"⏳ Waiting for previous tool(s) to finish…\"", 1)),
    ("cue_guard_gone", "not guarded on",
     lambda s: s.replace(
         "                        if (cueBlock.toolStatus != ToolBlockStatus.PENDING) continue\n", "", 1)),
    ("flip_before_cue", "publication order is wrong",
     lambda s: s.replace(
         "                val cueIndices = ToolPassTransitionPolicy.sequentialQueueCueIndices(pending.size)",
         "                val cueIndices = ToolPassTransitionPolicy.sequentialQueueCueIndices(pending.size)\n"
         "                ToolPassTransitionPolicy.statusAtDispatch(null)", 1)),
    ("one_flip_site", "call site(s) of",
     lambda s: s.replace(
         "                    val flippedStatus = ToolPassTransitionPolicy.statusAtDispatch(sharedBlocks[flipIdx].toolStatus)",
         "                    val flippedStatus = sharedBlocks[flipIdx].toolStatus", 1)),
    # ── wide3 witnesses: shapes the old criteria let through ────────────────
    ("critical_push_dead_guard", "only under a dead guard",
     lambda s: s.replace(
         """                        withContext(Dispatchers.Main) {
                            host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                        }
                    }
                    // Record the blocked attempt""",
         """                        if (false) withContext(Dispatchers.Main) {
                            host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                        }
                    }
                    // Record the blocked attempt""", 1)),
    ("critical_push_lost_to_terminator", "records the refusal without publishing",
     lambda s: s.replace(
         """                        withContext(Dispatchers.Main) {
                            host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                        }
                    }
                    // Record the blocked attempt""",
         """                    }
                    // Record the blocked attempt""", 1).replace(
         """                        content = blockedMsg,
                        isError = true,
                    ))
                    continue
                }""",
         """                        content = blockedMsg,
                        isError = true,
                    ))
                    if (true) continue
                }""", 1)),
    ("cue_guard_short_circuited", "not guarded on a LIVE",
     lambda s: s.replace(
         "                        if (cueBlock.toolStatus != ToolBlockStatus.PENDING) continue\n",
         "                        if (false && cueBlock.toolStatus != ToolBlockStatus.PENDING) continue\n", 1)),
    ("status_at_dispatch_unconditional", "statusAtDispatch` no longer decides",
     lambda s: s.replace(
         """    fun statusAtDispatch(current: ToolBlockStatus?): ToolBlockStatus? =
        if (current == ToolBlockStatus.PENDING) ToolBlockStatus.RUNNING else null""",
         """    fun statusAtDispatch(current: ToolBlockStatus?): ToolBlockStatus? =
        ToolBlockStatus.RUNNING""", 1)),
    ("flip_content_passthrough", "contentAtDispatchFlip` no longer retires",
     lambda s: s.replace(
         """    fun contentAtDispatchFlip(current: String, queueCue: String): String =
        if (current == queueCue) "" else current""",
         """    fun contentAtDispatchFlip(current: String, queueCue: String): String =
        current""", 1)),
    ("flip_keeps_cue_text", "no longer retires the queue cue",
     lambda s: s.replace(
         """                                content = ToolPassTransitionPolicy.contentAtDispatchFlip(
                                    flippingBlock.content,
                                    ToolPassTransitionPolicy.QUEUE_CUE_TEXT,
                                ),""",
         "                                content = flippingBlock.content,", 1)),
    # ── wide4 witnesses: identical defects, the DECISION removed instead of the
    #    spelling (literal folding / inverted polarity / branch values / a
    #    template-brace that used to shift the block extents) ─────────────────
    ("w4_t1_not_true", "only under a dead guard",
     lambda s: s.replace(
         """                        withContext(Dispatchers.Main) {
                            host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                        }
                    }
                    // Record the blocked attempt""",
         """                        if (!true) withContext(Dispatchers.Main) {
                            host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                        }
                    }
                    // Record the blocked attempt""", 1)),
    ("w4_t1_when_false", "only under a dead guard",
     lambda s: s.replace(
         """                        withContext(Dispatchers.Main) {
                            host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                        }
                    }
                    // Record the blocked attempt""",
         """                        when (false) {
                            true -> withContext(Dispatchers.Main) {
                                host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                            }
                        }
                    }
                    // Record the blocked attempt""", 1)),
    ("w4_t1_template_brace", "only under a dead guard",
     lambda s: s.replace(
         """                        withContext(Dispatchers.Main) {
                            host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                        }
                    }
                    // Record the blocked attempt""",
         """                        if (false) {
                            val _close = "${"}"}"
                            withContext(Dispatchers.Main) {
                                host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                            }
                            val _open = "${"{"}"
                        }
                    }
                    // Record the blocked attempt""", 1)),
    ("w4_t3_not_true", "not guarded on a LIVE",
     lambda s: s.replace(
         "                        if (cueBlock.toolStatus != ToolBlockStatus.PENDING) continue\n",
         "                        if (!true && cueBlock.toolStatus != ToolBlockStatus.PENDING) continue\n", 1)),
    ("w4_t3_polarity", "skips on a POSITIVE",
     lambda s: s.replace(
         "                        if (cueBlock.toolStatus != ToolBlockStatus.PENDING) continue\n",
         "                        if (cueBlock.toolStatus == ToolBlockStatus.PENDING) continue\n", 1)),
    ("w4_t5_or_true", "no longer decides",
     lambda s: s.replace(
         """    fun statusAtDispatch(current: ToolBlockStatus?): ToolBlockStatus? =
        if (current == ToolBlockStatus.PENDING) ToolBlockStatus.RUNNING else null""",
         """    fun statusAtDispatch(current: ToolBlockStatus?): ToolBlockStatus? =
        if (current == ToolBlockStatus.PENDING || true) ToolBlockStatus.RUNNING else null""", 1)),
    ("w4_t5_both_running", "no longer decides",
     lambda s: s.replace(
         """    fun statusAtDispatch(current: ToolBlockStatus?): ToolBlockStatus? =
        if (current == ToolBlockStatus.PENDING) ToolBlockStatus.RUNNING else null""",
         """    fun statusAtDispatch(current: ToolBlockStatus?): ToolBlockStatus? =
        if (current == ToolBlockStatus.PENDING) ToolBlockStatus.RUNNING else ToolBlockStatus.RUNNING""", 1)),
    ("w4_t6_both_current", "no longer retires",
     lambda s: s.replace(
         """    fun contentAtDispatchFlip(current: String, queueCue: String): String =
        if (current == queueCue) "" else current""",
         """    fun contentAtDispatchFlip(current: String, queueCue: String): String =
        if (current == queueCue) current else current""", 1)),
    ("w4_t6_inverted", "must clear",
     lambda s: s.replace(
         """    fun contentAtDispatchFlip(current: String, queueCue: String): String =
        if (current == queueCue) "" else current""",
         """    fun contentAtDispatchFlip(current: String, queueCue: String): String =
        if (current == queueCue) current else "" """, 1)),
    # ── round-3 witnesses (#124 / #125 / #126) ─────────────────────────────
    # #124 (dual-gate seam): Pass 3 maps a SUCCESSFUL call back to RUNNING.
    # Nothing used to read the flip OUT of RUNNING.
    ("r3_pass3_success_running", "mapped back to ToolBlockStatus.RUNNING",
     lambda s: s.replace(
         "                        result.success -> ToolBlockStatus.SUCCESS",
         "                        result.success -> ToolBlockStatus.RUNNING", 1)),
    # #124b (family-hole): the success branch keeps its value and can never fire.
    ("r3_pass3_success_dead", "can never be taken",
     lambda s: s.replace(
         "                        result.success -> ToolBlockStatus.SUCCESS",
         "                        !true -> ToolBlockStatus.SUCCESS", 1)),
    # #125 (dual-gate seam): the sibling preflight refusal stops publishing.
    # T1 only ever pinned the CRITICAL branch.
    ("r3_preflight_push_gone", "never publishes it",
     lambda s: s.replace(
         """                    resultParts.add(AgentContentPart.ToolResult(
                        id = id, name = name,
                        content = modelMessage,
                        isError = true,
                    ))
                    withContext(Dispatchers.Main) {
                        host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                    }
                    continue""",
         """                    resultParts.add(AgentContentPart.ToolResult(
                        id = id, name = name,
                        content = modelMessage,
                        isError = true,
                    ))
                    continue""", 1)),
    # #125b (order): the push survives but lands after the terminator.
    ("r3_preflight_push_after_continue", "before its terminator",
     lambda s: s.replace(
         """                    withContext(Dispatchers.Main) {
                        host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                    }
                    continue
                }

                pending.add(PendingTool(id, name, args, argsStr, paramsMap))""",
         """                    continue
                    withContext(Dispatchers.Main) {
                        host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                    }
                }

                pending.add(PendingTool(id, name, args, argsStr, paramsMap))""", 1)),
    # #126 (symmetric axis): the cue-index body becomes a constant.
    ("r3_cue_indices_body_empty", "no longer decides which calls are queued",
     lambda s: s.replace(
         "        if (pendingCount > 1) (1 until pendingCount).toList() else emptyList()",
         "        emptyList()", 1)),
    # #126b (family-hole): the branch survives, both arms are empty.
    ("r3_cue_indices_both_empty", "both branches yield",
     lambda s: s.replace(
         "        if (pendingCount > 1) (1 until pendingCount).toList() else emptyList()",
         "        if (pendingCount > 1) emptyList() else emptyList()", 1)),
]


def selftest(repo: str) -> int:
    path = os.path.join(repo, REL)
    with open(path, "r", encoding="utf-8") as fh:
        pristine = fh.read()

    bad, _ = check(pristine, "pristine")
    if bad:
        print("SELFTEST FAIL: pristine tree is already red:")
        for b in bad:
            print("  [X] %s" % b)
        return 1
    print("  [OK] pristine tree GREEN")

    failures = 0
    for name, expect, mutate in FIXTURES:
        mutated = mutate(pristine)
        if mutated == pristine:
            print("  [X] fixture %s did not change the source (stale anchor)" % name)
            failures += 1
            continue
        bad, _ = check(mutated, name)
        if not bad:
            print("  [X] fixture %s stayed GREEN (gate has no tooth for it)" % name)
            failures += 1
            continue
        if not any(expect in b for b in bad):
            print("  [X] fixture %s red for the wrong reason: %s" % (name, bad))
            failures += 1
            continue
        print("  [OK] fixture %s → RED (%s)" % (name, bad[0].split(": ", 1)[-1][:70]))

    # The comment blanker itself: criteria must not be satisfiable by comments.
    probe = "val x = 1 // ToolBlockStatus.RUNNING ToolBlockStatus.PENDING host.updateAssistantMessage(\nval y = 2\n"
    stripped = blank_comments(probe)
    if RUNNING in stripped or PENDING in stripped or UI_PUSH in stripped:
        print("  [X] comment blanker leaks comment text into the code projection")
        failures += 1
    elif len(stripped) != len(probe):
        print("  [X] comment blanker shifts offsets (%d vs %d)" % (len(stripped), len(probe)))
        failures += 1
    else:
        print("  [OK] comment blanker preserves offsets and hides comment text")

    if failures:
        print("SELFTEST FAIL (%d)" % failures)
        return 1
    print("%s (%d arms)" % (SELFTEST_MARKER, len(FIXTURES)))
    return 0


def main(argv) -> int:
    if "--selftest" in argv:
        # The self-test needs a tree to mutate; use the cwd (scripts/scan → repo)
        # unless a repo root is passed explicitly.
        repo = next((a for a in argv[1:] if not a.startswith("--")), None)
        if repo is None:
            repo = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
        return selftest(repo)
    repo = argv[1] if len(argv) > 1 else "."
    return run(repo)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
