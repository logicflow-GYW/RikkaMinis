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

SELFTEST_MARKER = "LOOP_TOOL_STATUS_SELFTEST=PASS"


def blank_comments(src: str) -> str:
    """Replace comment characters with spaces, PRESERVING offsets and newlines.

    Region anchors are comments, so the text cannot be pre-stripped; the criteria
    instead read this comment-free projection with identical indices. String
    literals are skipped first so a `//` inside one (a URL) is not a comment.
    """
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            i = n if j < 0 else j + 3
            continue
        c = src[i]
        if c == '"':
            i += 1
            while i < n and src[i] != '"':
                if src[i] == "\\":
                    i += 1
                elif src[i] == "\n":
                    break
                i += 1
            i += 1
            continue
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
            continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if src[k] != "\n":
                    out[k] = " "
            i = j
            continue
        i += 1
    return "".join(out)


def line_of(src: str, idx: int) -> int:
    return src.count("\n", 0, idx) + 1


# ── structure helpers (wide3: span/liveness based, never line- or spelling-based)

def mask_code(src):
    """Offset-preserving projection with comment text AND string/char literal
    contents blanked. Brace matching and guard detection read THIS: a paren
    inside a string literal must not shift the depth, and a criterion satisfied
    by a commented-out line is not a criterion."""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            for k in range(i, j):
                if src[k] != "\n":
                    out[k] = " "
            i = j
            continue
        if src[i] == '"':
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == '"' or src[j] == "\n":
                    break
                j += 1
            for k in range(i, min(j + 1, n)):
                out[k] = " "
            i = j + 1
            continue
        if src[i] == "'":
            j = src.find("'", i + 1)
            if 0 < j - i <= 4:
                for k in range(i, j + 1):
                    out[k] = " "
                i = j + 1
                continue
            i += 1
            continue
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
            continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if src[k] != "\n":
                    out[k] = " "
            i = j
            continue
        i += 1
    return "".join(out)


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


def enclosing(ifs, idx):
    hits = [s for s in ifs if s[2] <= idx <= s[3]]
    hits.sort(key=lambda s: s[0])
    return [s[4] for s in hits]


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


def is_lit(expr, word):
    return norm_atom(expr) == word


def always_false(cond):
    """`if (false && guard)` — the guard's spelling survives, its decision does not."""
    for disj in split_top(cond, "||"):
        if is_lit(disj, "false"):
            continue
        if any(is_lit(c, "false") for c in split_top(disj, "&&")):
            continue
        return False
    return True


def always_true(cond):
    return any(is_lit(d, "true") for d in split_top(cond, "||"))


def fun_body(masked, name):
    """Body span of `fun name(...)`: brace-balanced, or the expression after `=`.
    Located from the parameter list, never from the first `{` (a lambda in an
    expression body owns that one)."""
    m = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", masked)
    if not m:
        return None
    close = match_close(masked, m.end() - 1, "(", ")")
    if close < 0:
        return None
    i = close + 1
    while i < len(masked) and masked[i] not in "{=":
        i += 1
    if i >= len(masked):
        return None
    if masked[i] == "{":
        end = match_close(masked, i, "{", "}")
        return (i + 1, end) if end > 0 else None
    stop = len(masked)
    tm = re.search(r"\n[ \t]*\n|\n[ \t]*\}|\n[ \t]*(?:fun|internal|private|public|object|class|@)\b",
                   masked[i:])
    if tm:
        stop = i + tm.start()
    return (i + 1, stop)


def policy_sig_params(masked, name):
    m = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", masked)
    if not m:
        return None
    close = match_close(masked, m.end() - 1, "(", ")")
    if close < 0:
        return None
    return [p.group(1) for p in re.finditer(r"\b([A-Za-z_]\w*)\s*:", masked[m.end():close])]


def check(src: str, label: str):
    """Return (violations, notes). violations empty = PASS."""
    code = blank_comments(src)
    bad, notes = [], []

    # ── Regions (anchored on the raw text, read from the masked projection) ──
    masked = mask_code(src)
    ifs = if_statements(masked)
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
        guards = enclosing(crit_ifs, critical_at + m.start())
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
                 if not any(always_false(g) for g in enclosing(crit_ifs, at))]
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
    live_pending = False
    for m in re.finditer(re.escape(PENDING), pass2_seq):
        at = cue_call_at + m.start()
        conds = list(enclosing(ifs, at))
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
        body = masked[span[0]:span[1]]
        if PENDING not in body or RUNNING not in body or not re.search(r"\b(?:if|when)\b", body):
            bad.append("%s:%d: `statusAtDispatch` no longer decides on %s — its body is `%s`; an "
                       "unconditional flip resurrects a block Pass 1 already refused (FAILED) into "
                       "a RUNNING spinner"
                       % (label, line_of(src, span[0]), PENDING,
                          " ".join(body.split())[:80]))

    # ── T6: starting a call retires its cue ──────────────────────────────────
    # Same shape as T5: the call site can stay while the body degrades into
    # `= current` (cue text never retired, then persisted as tool output).
    span = fun_body(masked, "contentAtDispatchFlip")
    params = policy_sig_params(masked, "contentAtDispatchFlip")
    if span is None or not params:
        bad.append("%s:1: `fun contentAtDispatchFlip(` body not found — anchor moved or unbalanced"
                   % label)
    else:
        body = masked[span[0]:span[1]]
        missing = [p for p in params if not re.search(r"\b" + re.escape(p) + r"\b", body)]
        if missing or not re.search(r"\b(?:if|when)\b", body):
            bad.append("%s:%d: `contentAtDispatchFlip` no longer retires the queue cue — its body "
                       "is `%s` (missing %s / no branch); a leftover cue is shown and persisted as "
                       "that tool's output"
                       % (label, line_of(src, span[0]), " ".join(body.split())[:80],
                          ", ".join(missing) or "branch"))
    if FLIP_CONTENT_CALL not in code:
        bad.append("%s:1: the dispatch flip no longer retires the queue cue (%s) — the cue text is "
                   "not tool output and the finalisation pass keeps max(existing, result) BY "
                   "LENGTH, so a leftover cue would be shown as that tool's output"
                   % (label, FLIP_CONTENT_CALL))
    else:
        notes.append("T6 dispatch flip retires the queue cue")

    return bad, notes


def run(repo: str) -> int:
    path = os.path.join(repo, REL)
    if not os.path.exists(path):
        print("FAIL loop_tool_status_transition (missing %s)" % REL)
        return 1
    with open(path, "r", encoding="utf-8") as fh:
        src = fh.read()
    bad, notes = check(src, REL)
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
    print(SELFTEST_MARKER)
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
