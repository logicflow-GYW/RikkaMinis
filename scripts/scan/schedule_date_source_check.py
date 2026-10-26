#!/usr/bin/env python3
"""
Gate: a date that decides a DAY must be derived from the caller's day, not
re-read from the clock at the wrong moment.

Two silent-in-production defect classes from deep-audit-1016c (fixed 2026-10-16,
branch fix/da1016-schedule-date-bounds):

  A. SETTLE-TIME DATE LEAKAGE (ISSUE-09A)
     `ScheduledTaskRunner.finalizeRun` passed the settle-day clock
     (`LocalDate.now()`) into `ScheduledTasksStore.completeRun`. A scheduled run
     fired 23:55 that settled at 00:06 therefore wrote TOMORROW into
     `ScheduledTask.lastRunDate`, and `ScheduledTaskPolicy` answered
     ALREADY_RAN for the whole of tomorrow — a full day of work silently
     skipped, nothing having run. The rule: the date handed to `completeRun`
     must come from the run's SCHEDULED day (`Dispatch.scheduledDate`, captured
     in the fire pass), routed through `ScheduledRunDating.gateDateOnSettle`.

  B. RAW PARSE AT AN END BOUNDARY (ISSUE-09B)
     `resolveDateRange` fed `parseDate(str)` straight into the window, so a bare
     `--end 2026-10-16` parsed to 00:00:00.000 and `--start X --end X` was a
     zero-width window (measured width=0) — a day the caller named explicitly
     answered empty. Same for `freebusy`. The rule: the query surfaces must
     route a bare-date end through `alignBareDateEnd` (docs say INCLUSIVE
     end-of-day) and must not rebuild calendar math inline (the `--today`
     +24h constant was 1h wrong on every DST day).

[BATCH7-t10 hardening] every witness re-checked 2026-10-26: presence of the
routing call is NOT the decision. The judgements below are anchored on the
EFFECT: the dating call's scheduled-day argument must evaluate to the scheduled
day (a clock read hidden inside a `.let { }` argument is red), the align call's
result must be the value that reaches the consumer (`.let { _ -> it }` is red),
the bare-date branch must be reachable (`&& false` is red), the absent/present
guard must not be decided by a constant (`|| true` is red), the leniency mode
is the CONSTANT-FOLDED value of the last write (`!false` / `1 == 1` are red),
and the photos Invalid arm must refuse on a reachable path (`if (false) return`
is red). All lexical/structural work goes through the shared primitives
(_kotlin_lexer / _kotlin_ast / _kotlin_valueflow); there is no self-written
comment stripper or brace counter left in this gate.

Usage: python3 scripts/scan/schedule_date_source_check.py [repo_root]
       python3 scripts/scan/schedule_date_source_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage/ROT
"""
import argparse
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_lexer import KotlinLexError, mask_code, mask_comments  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool, function_body, if_blocks, match_bracket, split_top, tail_expr,
)
from _kotlin_valueflow import (  # noqa: E402
    call_args as vf_call_args, contains_ident, expr_end, guard_feasible,
    path_feasible, value_sources,
)

SCHEDULER_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/scheduled/ScheduledTaskRunner.kt"
)
CALENDAR_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/CalendarOffloadHandler.kt"
)
PHOTOS_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/PhotosOffloadHandler.kt"
)

COMPLETE_CALL = "ScheduledTasksStore.completeRun("
DATING_CALL = "ScheduledRunDating.gateDateOnSettle("
DISPATCH_CALL = "dispatched[sid] = Dispatch("
ALIGN_CALL = "alignBareDateEnd("
CLOCK_IN_ARG = re.compile(
    r"LocalDate\.now\(\)|LocalDateTime\.now\(\)|currentTimeMillis|Instant\.now|Clock\.system"
)

IDENT_RE = re.compile(r"[A-Za-z_]\w*")


def line_of(src, idx):
    return src.count("\n", 0, idx) + 1


# ── shared-primitive helpers (no self-written lexer / brace counter) ───────

def blank_spans(text, spans):
    """Blank every [a,b) span (keep newlines) — used to see what remains
    OUTSIDE a call once the shared primitives located the span."""
    out = list(text)
    for a, b in spans:
        for k in range(max(0, a), min(len(out), b)):
            if out[k] != "\n":
                out[k] = " "
    return "".join(out)


def call_span(text, open_paren):
    """Span of the call whose '(' sits at open_paren, INCLUDING a trailing
    lambda when one follows (matched by the shared match_bracket)."""
    close = match_bracket(text, open_paren)
    if close < 0:
        return None
    end = close + 1
    j = end
    while j < len(text) and text[j] in " \t\n":
        j += 1
    if j < len(text) and text[j] == "{":
        b = match_bracket(text, j)
        if b > 0:
            end = b + 1
    return (open_paren, end)


def call_spans(text, prefix):
    """Every `prefix(` call span (prefix ends with '(')."""
    out = []
    for m in re.finditer(re.escape(prefix), text):
        sp = call_span(text, m.end() - 1)
        if sp:
            out.append(sp)
    return out


def arg_texts(text, open_paren):
    return [text[a:b].strip() for a, b in vf_call_args(text, open_paren)]


def fun_slice(text, marker):
    """(start, end, kind) of the body of the fun whose declaration matches
    [marker] (e.g. 'internal fun alignBareDateEnd('), via the shared
    function_body; None when the declaration is gone."""
    pos = text.find(marker)
    if pos < 0:
        return None
    m = re.search(r"\bfun\s+([A-Za-z_]\w*)\s*\(", marker)
    if not m:
        return None
    fb = function_body(text[pos:], m.group(1))
    if fb is None:
        return None
    return (pos + fb[0], pos + fb[1], fb[2])


def fun_params(text, marker):
    """Top-level parameter names of the declaration at [marker]."""
    i = text.find(marker)
    if i < 0:
        return []
    open_paren = i + marker.rfind("(")
    names = []
    for a, b in vf_call_args(text, open_paren):
        m = re.match(r"\s*(?:vararg\s+)?([A-Za-z_]\w*)\s*:", text[a:b])
        if m:
            names.append(m.group(1))
    return names


def returned_expressions(body):
    """Every expression the body hands back (returns + `= expr` tail)."""
    outs = []
    for m in re.finditer(r"\breturn\b", body):
        e = expr_end(body, m.end())
        expr = body[m.end():e].strip()
        if expr:
            outs.append(expr)
    if not outs:
        t = tail_expr(body)
        if t:
            outs.append(t.strip())
    return outs


# ── [BATCH7-t10 14-2] value flow after a call: does the receiver survive? ──

DISCARD_OPS = ("also", "apply", "onEach", "forEach", "doOnNext")


def scope_tail_carries_receiver(block_text):
    """For a lambda `{ [param ->] body }`: does the value it produces derive
    from its receiver (the `it` / declared param)? A tail that names neither
    (e.g. `{ _ -> it }`, `{ parsed }`) returns a value the receiver never
    produced."""
    inner = block_text[1:-1] if block_text.startswith("{") else block_text
    m = re.match(r"\s*([A-Za-z_]\w*)\s*->", inner)
    param, body = (m.group(1), inner[m.end():]) if m else (None, inner)
    tail = tail_expr(body) or body.strip()
    if not tail:
        return False
    if param:
        if param == "_":
            return False  # `_ -> …`: the received value is deliberately dropped
        return contains_ident(tail, param)
    return contains_ident(tail, "it")


def postfix_chain_keeps_value(text, start):
    """Scan the postfix chain beginning right after a call's ')'.

    Returns (ok, saw_combinator): ok is False when a VALUE combinator
    (let/run) produces a tail that does not derive from the receiver — the
    call's result is discarded (witness 14-2: `align(...).let { _ -> it }`)."""
    pos = start
    saw = False
    while True:
        j = pos
        while j < len(text) and text[j] in " \t":
            j += 1
        m = re.match(r"\??\.\s*([A-Za-z_]\w*)\s*", text[j:])
        if not m:
            return True, saw
        name = m.group(1)
        k = j + m.end()
        if name in ("let", "run") and k < len(text) and text[k] in "{(":
            close = match_bracket(text, k)
            if close < 0:
                return True, saw
            blk = text[k:close + 1]
            return scope_tail_carries_receiver(blk), True
        if name in DISCARD_OPS and k < len(text) and text[k] in "{(":
            close = match_bracket(text, k)
            if close < 0:
                return True, saw
            saw = True
            pos = close + 1
            continue
        # takeIf/takeUnless (receiver or null), filter*, and every other
        # member call all derive their value from the receiver.
        return True, saw


def violations_scheduler(cv, lv, label):
    """ISSUE-09A rules for ScheduledTaskRunner.kt (analysis on code_view)."""
    bad = []

    starts = [m.end() - 1 for m in re.finditer(re.escape(COMPLETE_CALL), cv)]
    if len(starts) != 1:
        bad.append("%s:1: expected exactly 1 %s call (finalizeRun's funnel), found %d"
                   % (label, COMPLETE_CALL, len(starts)))

    # [14-1] the scheduled day is whatever Dispatch(…) binds to scheduledDate
    sched_idents = set()
    for m in re.finditer(r"\bDispatch\s*\(", cv):
        for a, b in vf_call_args(cv, m.end() - 1):
            am = re.search(r"\bscheduledDate\s*=\s*(.+)$", cv[a:b], re.S)
            if not am:
                continue
            for srcv in value_sources(am.group(1).strip().rstrip(",")):
                idents = IDENT_RE.findall(srcv)
                if idents:
                    sched_idents.add(idents[-1])
    if not sched_idents:
        bad.append("%s:1: no Dispatch(…) construction binds scheduledDate — the scheduled "
                   "day the settlement must credit cannot be derived" % label)

    for at in starts:
        args = vf_call_args(cv, at)
        if len(args) != 4:
            bad.append("%s:%d: %s must take 4 arguments (app, taskId, outcome, gateDate), found %d"
                       % (label, line_of(cv, at), COMPLETE_CALL, len(args)))
            continue
        gate = cv[args[3][0]:args[3][1]]
        spans = call_spans(gate, DATING_CALL)
        if not spans:
            bad.append("%s:%d: the date argument of completeRun is not routed through %s "
                       "(found: %s) — a settle-time clock read here re-opens ISSUE-09A "
                       "(a run settling after local midnight pins TOMORROW into lastRunDate "
                       "and the whole next day degrades to ALREADY_RAN)"
                       % (label, line_of(cv, at), DATING_CALL, gate[:80]))
        for a, b in spans:
            inner = arg_texts(gate, a)
            if len(inner) < 3:
                bad.append("%s:%d: %s no longer takes (scheduledDate, settleDate, outcome) — "
                           "the dating decision moved; re-anchor this gate"
                           % (label, line_of(cv, at), DATING_CALL.strip("(")))
                continue
            first = inner[0]
            srcs = value_sources(first)
            # [14-1] a clock read hidden inside the scheduled-day argument
            # (`.let { LocalDate.now().toString() }`) never reached the old
            # blank-the-whole-call check; the ARGUMENT VALUE is the decision.
            if any(CLOCK_IN_ARG.search(s) for s in srcs):
                bad.append("%s:%d: the dating call's scheduled-day argument evaluates to a "
                           "wall-clock read (`%s`) — the settled day is re-read at settle "
                           "time INSIDE the argument, so a run that settles past local "
                           "midnight pins TOMORROW into lastRunDate (ISSUE-09A)"
                           % (label, line_of(cv, at), first[:70]))
            elif not any(
                i in sched_idents for s in srcs for i in IDENT_RE.findall(s)
            ):
                bad.append("%s:%d: the dating call's scheduled-day argument is `%s`, which "
                           "does not derive from the run's scheduled day (%s) — the "
                           "settlement is credited to the wrong calendar day"
                           % (label, line_of(cv, at), first[:60],
                              ", ".join(sorted(sched_idents))[:60] or "none"))
        outside = blank_spans(gate, spans)
        if CLOCK_IN_ARG.search(outside):
            bad.append("%s:%d: a wall-clock read reaches completeRun's date argument outside "
                       "the dating call (%s)" % (label, line_of(cv, at), outside[:80]))

    builds = call_spans(cv, "Dispatch(")
    if not builds:
        bad.append("%s:1: no dispatch record construction found (expected `%s` with "
                   "`scheduledDate = …`)" % (label, DISPATCH_CALL))
    else:
        bound = False
        for a, b in builds:
            if re.search(r"\bscheduledDate\s*=", cv[a:b]):
                bound = True
        if not bound:
            bad.append("%s:1: Dispatch(…) is built without scheduledDate — the settle path "
                       "cannot credit the scheduled day" % label)

    if "internal object ScheduledRunDating {" not in cv:
        bad.append("%s:1: ScheduledRunDating is gone — the dating decision is no longer a "
                   "pure, harness-covered unit" % label)
    else:
        sl = fun_slice(cv, "fun gateDateOnSettle(")
        if sl is None:
            bad.append("%s:1: gateDateOnSettle cannot be read (fail-closed) — the dating "
                       "decision is no longer a readable unit" % label)
        else:
            body = cv[sl[0]:sl[1]]
            params = fun_params(cv, "fun gateDateOnSettle(")
            sched = params[0] if params else "scheduledDate"
            settle = params[1] if len(params) > 1 else "settleDate"
            outs = returned_expressions(body)
            if not outs:
                bad.append("%s:1: gateDateOnSettle returns nothing readable (fail-closed)"
                           % label)
            for expr in outs:
                if contains_ident(expr, settle):
                    bad.append(
                        "%s:1: gateDateOnSettle returns the SETTLE day (`%s`) — a run fired "
                        "23:55 that settles 00:06 pins tomorrow into lastRunDate and the "
                        "whole next day degrades to ALREADY_RAN (ISSUE-09A re-opened)"
                        % (label, expr[:60]))
                elif CLOCK_IN_ARG.search(expr):
                    bad.append(
                        "%s:1: gateDateOnSettle reads the wall clock (`%s`) — the date must "
                        "be the run's SCHEDULED day, not a re-read of the clock at settle time"
                        % (label, expr[:60]))
                elif not contains_ident(expr, sched):
                    bad.append(
                        "%s:1: gateDateOnSettle returns `%s`, which is not the scheduled day "
                        "`%s` — the run is credited to the wrong calendar day"
                        % (label, expr[:60], sched))
    return bad


def align_call_gaps(cv, sl, label, where):
    """Every alignBareDateEnd() call must align THE CALLER'S date string AND
    hand its own result to the consumer."""
    bad = []
    body = cv[sl[0]:sl[1]]
    for a, b in call_spans(body, "alignBareDateEnd("):
        args = arg_texts(body, a)
        if len(args) < 3:
            bad.append("%s:1: alignBareDateEnd in %s no longer takes (epochMs, raw, zone) — "
                       "the alignment rule moved; re-anchor this gate" % (label, where))
            continue
        raw = args[1].strip()
        idents = [t for t in IDENT_RE.findall(raw) if t not in ("null", "true", "false")]
        if raw.startswith('"') or not idents:
            bad.append("%s:1: alignBareDateEnd in %s receives `%s` as the raw date — the "
                       "bare-date decision needs the CALLER'S string; a literal (or empty "
                       "string) never matches YYYY-MM-DD and the inclusive end-of-day is "
                       "silently skipped" % (label, where, raw[:40]))
            continue
        candidate = idents[-1]
        if not re.search(r"\b\w*[Pp]arse\w*\s*\(\s*%s\b" % re.escape(candidate), body):
            bad.append("%s:1: alignBareDateEnd in %s aligns `%s`, which this scope never "
                       "parsed — the value aligned is not the caller's date string"
                       % (label, where, candidate))
        # [14-2] the aligned value must be the one that reaches the consumer
        ok, _saw = postfix_chain_keeps_value(body, b)
        if not ok:
            bad.append("%s:1: alignBareDateEnd in %s discards its own result (a `.let`/`.run` "
                       "tail that does not derive from the aligned value, e.g. "
                       "`.let { _ -> it }`) — the bare-date end keeps its 00:00:00.000 "
                       "value, so `--start D --end D` is a zero-width window again"
                       % (label, where))
    return bad


def align_bare_end_dead_branch(cv, label):
    """[14-3] the bare-date branch of alignBareDateEnd must be REACHABLE:
    `if (DATE_ONLY.matches(…) && false)` keeps every substring while every
    --end keeps its parsed 00:00:00.000 value."""
    sl = fun_slice(cv, "internal fun alignBareDateEnd(")
    if sl is None:
        return None  # absence covered by the helper-is-gone rule
    body = cv[sl[0]:sl[1]]
    aligned = None
    for cond, t0, t1, _e0, _e1 in if_blocks(body):
        then = body[t0:t1]
        em = re.search(r"\belse\b", then)
        if em:
            then = then[:em.start()]
        if "endOfLocalDay(" in then:
            aligned = cond
            break
    if aligned is None:
        return ("alignBareDateEnd no longer returns the end-of-day value on a bare date — "
                "no `if (…)` branch yields endOfLocalDay, so the inclusive end-of-day "
                "contract is gone (ISSUE-09B)")
    if guard_feasible(aligned) is False:
        return ("the bare-date branch of alignBareDateEnd is DEAD (`%s` folds to false) — "
                "every --end keeps its parsed 00:00:00.000 value, so `--start D --end D` is "
                "a zero-width window again (ISSUE-09B)" % aligned.strip()[:70])
    return None


def create_default_guard_always(cv, label):
    """[14-4] the guard separating an ABSENT --end (1h default) from a
    PRESENT one (parse-or-refuse) must not be decided by a constant."""
    sl = fun_slice(cv, "internal fun createEndMs(")
    if sl is None:
        return None  # absence covered by the helper-is-gone rule
    body = cv[sl[0]:sl[1]]
    for cond, t0, t1, _e0, _e1 in if_blocks(body):
        then = body[t0:t1]
        if not re.search(r"60L?\s*\*\s*60\s*\*\s*1000", then):
            continue
        if const_bool(cond) is True:
            return ("the absent-END guard of createEndMs is ALWAYS taken (`%s` folds to "
                    "true) — a present --end never reaches the parser, so an unparseable "
                    "value is silently served as the 1-hour default AND a valid "
                    "multi-hour --end is truncated to it" % cond.strip()[:70])
        return None
    return None


def violations_calendar(cv, lv, label):
    """ISSUE-09B rules for CalendarOffloadHandler.kt."""
    bad = []

    sl = fun_slice(cv, "private fun resolveDateRange(")
    if sl is None:
        bad.append("%s:1: resolveDateRange is gone (rename => update this gate)" % label)
    else:
        rdr = cv[sl[0]:sl[1]]
        if "resolveRangePure(" not in rdr:
            bad.append("%s:1: resolveDateRange must delegate to the pure core resolveRangePure(…)"
                       % label)
        own_code = blank_spans(rdr, call_spans(rdr, "resolveRangePure("))
        if "parseDate(" in own_code or "parseDateInZone(" in own_code:
            bad.append("%s:1: resolveDateRange parses bounds itself again — the zero-width "
                       "--start X --end X window (bare date parsed to 00:00:00.000) and the "
                       "+24h DST arithmetic both come back with it" % label)
        if "24 * 60 * 60 * 1000L" in own_code:
            bad.append("%s:1: resolveDateRange keeps a flat 24h constant — DST days are 23/25h "
                       "and Calendar day arithmetic (addLocalDays/startOfLocalDay) is required"
                       % label)

    sl = fun_slice(cv, "internal fun resolveRangePure(")
    if sl is None:
        bad.append("%s:1: resolveRangePure is gone" % label)
    else:
        pure = cv[sl[0]:sl[1]]
        if "alignBareDateEnd(" not in pure:
            bad.append("%s:1: resolveRangePure no longer aligns a bare-date --end — "
                       "`--start D --end D` answers an empty day again" % label)
        for needed in ("startOfLocalDay(", "addLocalDays("):
            if needed not in pure:
                bad.append("%s:1: resolveRangePure lost %s — --today/--days must walk local "
                           "calendar days, not fixed offsets" % (label, needed))
        bad.extend(align_call_gaps(cv, sl, label, "resolveRangePure"))

    if "internal fun alignBareDateEnd(" not in cv:
        bad.append("%s:1: alignBareDateEnd helper is gone" % label)
    else:
        sl = fun_slice(cv, "internal fun alignBareDateEnd(")
        if sl is None or "DATE_ONLY" not in cv[sl[0]:sl[1]]:
            bad.append("%s:1: alignBareDateEnd no longer gates on the bare `YYYY-MM-DD` form — "
                       "explicit instants (datetime / relative) must keep their exact value"
                       % label)
        dead = align_bare_end_dead_branch(cv, label)
        if dead:
            bad.append("%s:1: %s" % (label, dead))

    sl = fun_slice(cv, "internal fun endOfLocalDay(")
    if sl is None or "add(Calendar.DAY_OF_YEAR, 1)" not in cv[sl[0]:sl[1]]:
        bad.append("%s:1: endOfLocalDay no longer uses add(DAY_OF_YEAR, 1) — the DST-safe "
                   "inclusive end (a 24h-1ms constant stops 1h short on a 25-hour day)" % label)

    sl = fun_slice(cv, "private fun doFreebusy(")
    if sl is not None:
        fb = cv[sl[0]:sl[1]]
        if "parseDate(endStr)" in fb:
            bad.append("%s:1: freebusy feeds the raw parse of --end into the window — a bare "
                       "date must go through alignBareDateEnd" % label)
        if ALIGN_CALL not in fb:
            bad.append("%s:1: freebusy --end must route through %s" % (label, ALIGN_CALL))
        bad.extend(align_call_gaps(cv, sl, label, "freebusy"))

    sl = fun_slice(cv, "private fun doCreate(")
    if sl is None:
        bad.append("%s:1: doCreate is gone (rename => update this gate)" % label)
    else:
        crt = cv[sl[0]:sl[1]]
        crt_lv = lv[sl[0]:sl[1]]
        if "createEndMs(" not in crt:
            bad.append("%s:1: doCreate does not route --end through createEndMs(…) — an "
                       "explicit --end that fails to parse silently falls back to the 1h "
                       "default: create answers an event in a window the caller never asked "
                       "for, and nothing says the value was rejected" % label)
        if "invalid --end" not in crt_lv:
            bad.append("%s:1: doCreate no longer refuses an unparseable --end — the caller is "
                       "never told the value was rejected" % label)
        if re.search(r"\?: \(\s*startMs\s*\+", crt):
            bad.append("%s:1: doCreate keeps the elvis-default swallow (present-but-unusable "
                       "--end silently becomes start+1h) — the rule is bound at the decision, "
                       "not at the spelling of the default" % label)
    if "internal fun createEndMs(" not in cv:
        bad.append("%s:1: createEndMs helper is gone" % label)
    else:
        sl = fun_slice(cv, "internal fun createEndMs(")
        cem = cv[sl[0]:sl[1]] if sl else ""
        if "isNullOrBlank" not in cem:
            bad.append("%s:1: createEndMs no longer separates ABSENT (→ the 1h default) from "
                       "PRESENT-BUT-UNPARSEABLE (→ null → refusal): that decision point is the "
                       "whole fix — an invalid --end slips back into the default branch" % label)
        if "60L * 60 * 1000" not in cem:
            bad.append("%s:1: createEndMs lost the 1h default for an absent --end" % label)
        # [14-4] the absent/present guard must not be decided by a constant,
        # and a present --end must still reach the parser.
        always = create_default_guard_always(cv, label)
        if always:
            bad.append("%s:1: %s" % (label, always))
        params = fun_params(cv, "internal fun createEndMs(")
        raw = params[0] if params else "rawEnd"
        reaches = False
        for m in re.finditer(r"\bparse\s*\(", cem):
            for a, b in vf_call_args(cem, m.end() - 1):
                if contains_ident(cem[a:b], raw) and path_feasible(cem, m.start()):
                    reaches = True
        if sl and cem and "isNullOrBlank" in cem and not reaches:
            bad.append("%s:1: a present --end no longer reaches the parser inside createEndMs "
                       "(no reachable `parse(<raw>)` call) — the present-but-unparseable "
                       "refusal is gone" % label)
    return bad


def photos_invalid_arm_falls_through(cv, lv, label):
    """[14-6] the PhotoRange.Invalid arm of handleList must REFUSE on a
    reachable path: `{ if (false) return …; PhotoRange.Window(null, null) }`
    keeps the branch and the message while answering the whole library."""
    sl = fun_slice(cv, "private fun handleList(")
    if sl is None:
        return []
    body = cv[sl[0]:sl[1]]
    m = re.search(r"is\s+PhotoRange\.Invalid\s*->", body)
    if not m:
        return []  # absence covered by the branch rule
    i = m.end()
    while i < len(body) and body[i] in " \t\n":
        i += 1
    if i < len(body) and body[i] == "{":
        close = match_bracket(body, i)
        if close < 0:
            return ["%s:1: handleList's PhotoRange.Invalid arm cannot be read "
                    "(fail-closed)" % label]
        arm = (i, close + 1)
    else:
        arm = (i, expr_end(body, i))
    arm_cv = body[arm[0]:arm[1]]
    arm_lv = lv[sl[0] + arm[0]: sl[0] + arm[1]]
    out = []
    if re.search(r"\bPhotoRange\s*\.\s*Window\s*\(", arm_cv):
        out.append("handleList's PhotoRange.Invalid arm answers an UNFILTERED "
                   "`PhotoRange.Window(…)` instead of refusing — an unparseable "
                   "--start/--end serves the entire library (ISSUE-04(B))")
    live = [r for r in re.finditer(r"\breturn\b", arm_cv)
            if path_feasible(arm_cv, r.start())]
    if not live:
        out.append("handleList's PhotoRange.Invalid arm never returns on a reachable path "
                   "(the refusal sits inside `if (false)` / `{ Unit }`) — the command falls "
                   "through to an unfiltered query (ISSUE-04(B))")
    else:
        told = False
        for r in live:
            e = expr_end(arm_cv, r.end())
            expr_cv = arm_cv[r.end():e]
            expr_lv = arm_lv[r.end():e]
            if "NativeOffloadResult(" in expr_cv and re.search(r"invalid", expr_lv):
                told = True
        if not told:
            out.append("handleList's PhotoRange.Invalid arm returns without telling the "
                       "caller which bound was invalid — the refusal message is gone")
    return out


def violations_photos(cv, lv, label):
    """ISSUE-04(B) rules for PhotosOffloadHandler.kt: a --start/--end that decides a
    day must REFUSE a value the caller spelled wrong, never silently widen the window
    to the whole library."""
    bad = []

    sl = fun_slice(cv, "private fun resolveDateRange(")
    if sl is None:
        bad.append("%s:1: resolveDateRange is gone (rename => update this gate)" % label)
    else:
        rdr = cv[sl[0]:sl[1]]
        if "resolvePhotoRange(" not in rdr:
            bad.append("%s:1: resolveDateRange must delegate to the pure core "
                       "resolvePhotoRange(…)" % label)
        own_code = blank_spans(rdr, call_spans(rdr, "resolvePhotoRange("))
        if "parseDate(" in own_code:
            bad.append("%s:1: resolveDateRange parses --start/--end itself again — an "
                       "unparseable value goes back to being silently dropped and the "
                       "command answers the entire library" % label)
        if "daysAgoStartOfDayMs(" in own_code or "alignDateOnlyEndMs(" in own_code:
            bad.append("%s:1: resolveDateRange rebuilds the day math inline instead of the "
                       "shared helpers" % label)

    if "internal sealed class PhotoRange" not in cv:
        bad.append("%s:1: PhotoRange is gone — the refusal outcome has no carrier, so an "
                   "invalid bound degrades to null == 'no filter' again" % label)
    sl = fun_slice(cv, "internal fun resolvePhotoRange(")
    if sl is None:
        bad.append("%s:1: resolvePhotoRange is gone" % label)
    else:
        pure = cv[sl[0]:sl[1]]
        if "PhotoRange.Invalid(" not in pure:
            bad.append("%s:1: resolvePhotoRange no longer returns an Invalid outcome for a bad "
                       "--start/--end — ISSUE-04(B) re-opens (the window silently widens)" % label)
        if "endMs < startMs" not in pure:
            bad.append("%s:1: resolvePhotoRange lost the inverted-range refusal (--start after "
                       "--end must not be served as a back-to-front window)" % label)
        for needed in ("daysAgoStartOfDayMs(", "alignDateOnlyEndMs("):
            if needed not in pure:
                bad.append("%s:1: resolvePhotoRange lost %s (the shared day-math helper)"
                           % (label, needed))

    sl = fun_slice(cv, "private fun handleList(")
    if sl is None:
        bad.append("%s:1: handleList is gone" % label)
    else:
        hl = cv[sl[0]:sl[1]]
        hl_lv = lv[sl[0]:sl[1]]
        if "PhotoRange.Invalid" not in hl:
            bad.append("%s:1: handleList no longer branches on PhotoRange.Invalid — the refusal "
                       "never reaches the caller" % label)
        if "android-photos list: invalid" not in hl_lv:
            bad.append("%s:1: handleList no longer reports which bound was invalid" % label)
        for msg in photos_invalid_arm_falls_through(cv, lv, label):
            bad.append("%s:1: %s" % (label, msg))

    sl = fun_slice(cv, "private fun handleAlbum(")
    if sl is None:
        bad.append("%s:1: handleAlbum is gone" % label)
    else:
        alb = cv[sl[0]:sl[1]]
        alb_lv = lv[sl[0]:sl[1]]  # the JSON keys are string literals
        if ("MediaStore.Images.Media.WIDTH" not in alb
                or "MediaStore.Images.Media.HEIGHT" not in alb):
            bad.append("%s:1: handleAlbum's projection no longer asks for WIDTH/HEIGHT — every "
                       "album row answers 0x0 and the caller cannot pick a size" % label)
        if 'put("width"' not in alb_lv or 'put("height"' not in alb_lv:
            bad.append("%s:1: handleAlbum no longer emits width/height in its JSON" % label)
    return bad


# ── [dv16-A A-01] strict date parsing ───────────────────────────────────────
# A date that decides a DAY must not come back as a DIFFERENT day: in the
# default lenient mode SimpleDateFormat silently ROLLS an out-of-range field
# over (`2026-02-30` → March 2, `2026-13-01` → next January), so a mistyped
# `--start` / `--end` / `--at` answered for another day instead of being
# refused. SessionsOffloadHandler.kt:561 is the one parser that got this right;
# these are its four siblings.
NOTIFICATION_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/NotificationOffloadHandler.kt"
)
ALARM_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/AlarmOffloadHandler.kt"
)

# (file, member marker, what the parsed value decides)
STRICT_DATE_PARSERS = (
    (PHOTOS_REL, "private fun parseDate(", "--start/--end"),
    (CALENDAR_REL, "internal fun parseDateInZone(", "--start/--end"),
    (NOTIFICATION_REL, "private fun parseIso(", "--at"),
    (ALARM_REL, "private fun parseTimeArg(", "the alarm time"),
)

STRICT_PARSE_FLAG = "isLenient = false"


def leniency_writes(body):
    """[(pos, rhs, folded)] for every leniency write in [body].

    [14-5] the mode is decided by the VALUE of the last write, not by the
    spelling: `isLenient = !false` / `1 == 1` bypassed the literal regex, so
    every RHS is constant-folded here (unknown ⇒ None, fail-closed upstream)."""
    out = []
    for m in re.finditer(r"(?:\bisLenient\s*=\s*|\bsetLenient\s*\(\s*)", body):
        if m.group(0).lstrip().startswith("setLenient"):
            p = body.find("(", m.start())
            args = vf_call_args(body, p)
            if not args:
                out.append((m.start(), "", None))
                continue
            a, b = args[0]
            out.append((m.start(), body[a:b].strip(), const_bool(body[a:b])))
        else:
            e = expr_end(body, m.end())
            rhs = body[m.end():e].strip()
            out.append((m.start(), rhs, const_bool(rhs)))
    return out


def violations_strict_date_parser(cv, lv, label, marker, what):
    """The parser must not run in the default (lenient) mode.

    Bound to the DECISION, not to a spelling: the flag may sit anywhere inside
    the parser (after the pattern list, inside the `apply {}`, before the parse
    call). Fail-closed in both directions — a parser that cannot be sliced out
    is a finding, and so is one that no longer builds a SimpleDateFormat at all
    (that is a rewrite of the decision this gate pins)."""
    bad = []
    sl = fun_slice(cv, marker)
    if sl is None:
        bad.append("%s:1: %s is gone — the strict-date decision this gate pins cannot be "
                   "read; re-point the marker" % (label, marker.strip()))
        return bad
    body = cv[sl[0]:sl[1]]
    if "SimpleDateFormat" not in body or ".parse(" not in body:
        bad.append("%s:1: %s no longer parses with a SimpleDateFormat — re-read this rule "
                   "before trusting it" % (label, marker.strip()))
        return bad
    writes = leniency_writes(body)
    if not writes:
        bad.append("%s:1: %s parses %s in the DEFAULT LENIENT mode — an out-of-range field is "
                   "rolled over (2026-02-30 → March 2) and the command answers for a DIFFERENT "
                   "day instead of refusing" % (label, marker.strip(), what))
        return bad
    pos, rhs, folded = writes[-1]
    if folded is True:
        bad.append("%s:1: %s re-enables LENIENT mode — the last leniency write (`%s`) folds "
                   "to true, so the parser again rolls an out-of-range field over instead of "
                   "refusing it" % (label, marker.strip(), rhs[:40]))
    elif folded is None:
        bad.append("%s:1: the last leniency write in %s (`%s`) cannot be proven strict "
                   "(fail-closed) — the effective mode is whatever that expression evaluates "
                   "to, and this gate refuses to guess" % (label, marker.strip(), rhs[:40]))
    return bad


def views(src):
    """(code_view, literal_view) — shared lexer; fail-closed on parse errors."""
    return mask_code(src), mask_comments(src)


def check(repo_root):
    bad = []
    for rel, fn in ((SCHEDULER_REL, violations_scheduler), (CALENDAR_REL, violations_calendar),
                    (PHOTOS_REL, violations_photos)):
        path = os.path.join(repo_root, rel)
        if not os.path.isfile(path):
            bad.append("%s:1: ROT — file not found under %s" % (rel, repo_root))
            continue
        with open(path, encoding="utf-8") as fh:
            src = fh.read()
        try:
            cv, lv = views(src)
        except KotlinLexError as e:
            bad.append("%s:1: fail-closed — the shared lexer cannot analyse this file (%s)"
                       % (rel, e))
            continue
        bad.extend(fn(cv, lv, rel))
    # [dv16-A A-01] the strictness of every offload date parser, on the same
    # is-it-really-there footing: the function body is sliced out and the flag
    # must be inside it.
    for rel, marker, what in STRICT_DATE_PARSERS:
        path = os.path.join(repo_root, rel)
        if not os.path.isfile(path):
            bad.append("%s:1: ROT — file not found under %s" % (rel, repo_root))
            continue
        with open(path, encoding="utf-8") as fh:
            src = fh.read()
        try:
            cv, lv = views(src)
        except KotlinLexError as e:
            bad.append("%s:1: fail-closed — the shared lexer cannot analyse this file (%s)"
                       % (rel, e))
            continue
        bad.extend(violations_strict_date_parser(cv, lv, rel, marker, what))
    return bad
# ─── fixtures: the gate proves its own teeth ────────────────────────────────

GOOD_RUNNER = """
    private data class Dispatch(
        val taskId: String,
        val deadlineSec: Int,
        val startedAtMs: Long,
        val scheduledDate: String,
    )

    private suspend fun fire(app: MinisApp, task: ScheduledTask, scheduledDate: String) {
            dispatched[sid] = Dispatch(
                taskId = task.id,
                deadlineSec = task.deadlineSec,
                startedAtMs = System.currentTimeMillis(),
                scheduledDate = scheduledDate,
            )
    }

    internal object ScheduledRunDating {
        fun gateDateOnSettle(scheduledDate: String, settleDate: String, outcome: String): String = scheduledDate
    }

    private suspend fun finalizeRun(app: MinisApp, taskId: String, outcome: String, sessionId: String?, scheduledDate: String) {
        val res = try {
            ScheduledTasksStore.completeRun(
                app,
                taskId,
                outcome,
                ScheduledRunDating.gateDateOnSettle(scheduledDate, LocalDate.now().toString(), outcome),
            )
        } catch (t: Throwable) {
            null
        }
    }
"""

BAD_RUNNER_SETTLE_CLOCK = GOOD_RUNNER.replace(
    """                ScheduledRunDating.gateDateOnSettle(scheduledDate, LocalDate.now().toString(), outcome),""",
    """                LocalDate.now().toString(),""",
).replace(
    """        val scheduledDate: String,\n""", "",
).replace(
    """                scheduledDate = scheduledDate,\n""", "",
)

GOOD_CALENDAR = """
    companion object {
        private val DATE_ONLY: Regex = Regex(\"\"\"^\\d{4}-\\d{2}-\\d{2}$\"\"\")

        internal fun startOfLocalDay(ms: Long, zone: TimeZone): Long =
            Calendar.getInstance(zone).apply { timeInMillis = ms }.timeInMillis

        internal fun endOfLocalDay(ms: Long, zone: TimeZone): Long {
            val cal = Calendar.getInstance(zone).apply {
                timeInMillis = ms
                add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis - 1
        }

        internal fun addLocalDays(ms: Long, days: Int, zone: TimeZone): Long = ms

        internal fun alignBareDateEnd(parsedMs: Long, raw: String, zone: TimeZone): Long =
            if (DATE_ONLY.matches(raw.trim())) endOfLocalDay(parsedMs, zone) else parsedMs

        internal fun resolveRangePure(
            nowMs: Long,
            zone: TimeZone,
            todayFlag: Boolean,
            daysRaw: Int?,
            startRaw: String?,
            endRaw: String?,
            parse: (String, Long, TimeZone) -> Long?,
        ): Pair<Long, Long>? {
            val s = startOfLocalDay(addLocalDays(nowMs, -1, zone), zone)
            return alignBareDateEnd(parse(endRaw ?: "", nowMs, zone) ?: return null, endRaw ?: "", zone) to s
        }

        internal fun createEndMs(rawEnd: String?, startMs: Long, parse: (String) -> Long?): Long? {
            if (rawEnd.isNullOrBlank()) return startMs + 60L * 60 * 1000
            return parse(rawEnd)
        }
    }

    private fun resolveDateRange(args: OffloadArgs): Pair<Long, Long>? =
        resolveRangePure(
            nowMs = System.currentTimeMillis(),
            zone = TimeZone.getDefault(),
            todayFlag = args.hasFlag("today"),
            daysRaw = args.getInt("days"),
            startRaw = args.get("start"),
            endRaw = args.get("end"),
        ) { s, nowMs, z -> parseDateInZone(s, nowMs, z) }

    private fun doFreebusy(args: OffloadArgs): NativeOffloadResult {
        val endMs = parseDateInZone(endStr, 0L, TimeZone.getDefault())
            ?.let { alignBareDateEnd(it, endStr, TimeZone.getDefault()) }
            ?: return NativeOffloadResult(2, "bad --end")
        return NativeOffloadResult(0, "$endMs")
    }

    private fun doCreate(args: OffloadArgs): NativeOffloadResult {
        val startMs = parseDate(startStr) ?: return NativeOffloadResult(2, "bad --start")
        val rawEnd = args.get("end")
        val endMs = createEndMs(rawEnd, startMs) { if (isAllDay) parseDateUtc(it) else parseDate(it) }
            ?: return NativeOffloadResult(2, "android-calendar create: invalid --end '$rawEnd'\\n")
        return NativeOffloadResult(0, "$startMs..$endMs")
    }
"""

BAD_CALENDAR_RAW_PARSE = GOOD_CALENDAR.replace(
    """    private fun resolveDateRange(args: OffloadArgs): Pair<Long, Long>? =
        resolveRangePure(
            nowMs = System.currentTimeMillis(),
            zone = TimeZone.getDefault(),
            todayFlag = args.hasFlag("today"),
            daysRaw = args.getInt("days"),
            startRaw = args.get("start"),
            endRaw = args.get("end"),
        ) { s, nowMs, z -> parseDateInZone(s, nowMs, z) }""",
    """    private fun resolveDateRange(args: OffloadArgs): Pair<Long, Long>? {
        val now = System.currentTimeMillis()
        if (args.hasFlag("today")) {
            val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0) }
            val s = cal.timeInMillis
            return s to (s + 24 * 60 * 60 * 1000L)
        }
        val startStr = args.get("start")
        val endStr = args.get("end")
        val startMs = if (startStr != null) parseDate(startStr) ?: return null else now - 24 * 60 * 60 * 1000L
        val endMs = if (endStr != null) parseDate(endStr) ?: return null else now
        return if (startMs > endMs) endMs to startMs else startMs to endMs
    }""",
).replace(
    """        val endMs = parseDateInZone(endStr, 0L, TimeZone.getDefault())
            ?.let { alignBareDateEnd(it, endStr, TimeZone.getDefault()) }
            ?: return NativeOffloadResult(2, "bad --end")""",
    """        val endMs = parseDate(endStr) ?: return NativeOffloadResult(2, "bad --end")""",
)

# [da1032-C] ISSUE-04(A) negatives: the old shape (present-but-invalid --end falls
# back to the 1h default) must trip a rule of its own.
BAD_CALENDAR_SWALLOW_END = GOOD_CALENDAR.replace(
    "        val endMs = createEndMs(rawEnd, startMs) { if (isAllDay) parseDateUtc(it) else parseDate(it) }",
    '        val endMs = args.get("end")?.let { parseDate(it) } ?: (startMs + 60 * 60 * 1000L)',
)

BAD_CALENDAR_NO_REFUSAL = GOOD_CALENDAR.replace(
    '"android-calendar create: invalid --end',
    '"android-calendar create: bad value for',
)

BAD_CALENDAR_NULL_CHECK = GOOD_CALENDAR.replace(
    "if (rawEnd.isNullOrBlank()) return startMs + 60L * 60 * 1000",
    "if (rawEnd == null) return startMs + 60L * 60 * 1000",
)

BAD_CALENDAR_NO_DEFAULT = GOOD_CALENDAR.replace(
    "if (rawEnd.isNullOrBlank()) return startMs + 60L * 60 * 1000",
    "if (rawEnd.isNullOrBlank()) return startMs",
)

# [da1032-C] ISSUE-04(B) fixtures: a --start/--end the caller spelled wrong must be
# REFUSED, never silently widened to the whole library.
GOOD_PHOTOS = """
    private fun resolveDateRange(args: OffloadArgs): PhotoRange =
        resolvePhotoRange(
            nowMs = System.currentTimeMillis(),
            zone = TimeZone.getDefault(),
            daysRaw = args.getInt("days"),
            startRaw = args.get("start"),
            endRaw = args.get("end"),
        ) { parseDate(it) }

    private fun handleList(args: OffloadArgs): NativeOffloadResult {
        val range = when (val r = resolveDateRange(args)) {
            is PhotoRange.Window -> r
            is PhotoRange.Invalid ->
                return NativeOffloadResult(2, "android-photos list: invalid ${r.flag} '${r.raw}'")
        }
        return NativeOffloadResult(0, "${range.startMs}..${range.endMs}")
    }

    private fun handleAlbum(args: OffloadArgs): NativeOffloadResult {
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        val out = JSONArray()
        while (c.moveToNext()) {
            out.put(JSONObject().put("id", c.getLong(0))
                .put("size", c.getLong(5))
                .put("width", c.getInt(3))
                .put("height", c.getInt(4))
                .put("mime", c.getString(6)))
        }
        return NativeOffloadResult(0, out.toString())
    }

internal sealed class PhotoRange {
    data class Window(val startMs: Long?, val endMs: Long?) : PhotoRange()
    data class Invalid(val flag: String, val raw: String) : PhotoRange()
}

internal fun resolvePhotoRange(
    nowMs: Long,
    zone: TimeZone,
    daysRaw: Int?,
    startRaw: String?,
    endRaw: String?,
    parse: (String) -> Long?,
): PhotoRange {
    if (daysRaw != null) {
        val n = if (daysRaw <= 0) 7 else daysRaw
        return PhotoRange.Window(daysAgoStartOfDayMs(nowMs, n, zone), nowMs)
    }
    val startMs = if (startRaw.isNullOrBlank()) null else parse(startRaw) ?: return PhotoRange.Invalid("--start", startRaw)
    val endMs = if (endRaw.isNullOrBlank()) {
        null
    } else {
        val ms = parse(endRaw) ?: return PhotoRange.Invalid("--end", endRaw)
        if (isDateOnly(endRaw)) alignDateOnlyEndMs(ms, zone) else ms
    }
    if (startMs != null && endMs != null && endMs < startMs) {
        return PhotoRange.Invalid("--end", endRaw.orEmpty())
    }
    return PhotoRange.Window(startMs, endMs)
}
"""

BAD_PHOTOS_OWN_PARSE = GOOD_PHOTOS.replace(
    ") { parseDate(it) }",
    ') { parseDate(it) }.also { parseDate("x") }',
)

BAD_PHOTOS_NO_REFUSAL = GOOD_PHOTOS.replace(
    'parse(startRaw) ?: return PhotoRange.Invalid("--start", startRaw)',
    'parse(startRaw) ?: null',
).replace(
    'val ms = parse(endRaw) ?: return PhotoRange.Invalid("--end", endRaw)',
    'val ms = parse(endRaw) ?: 0L',
).replace(
    'return PhotoRange.Invalid("--end", endRaw.orEmpty())',
    'return PhotoRange.Window(null, null)',
)

BAD_PHOTOS_NO_INVERT = GOOD_PHOTOS.replace("endMs < startMs", "false")

BAD_PHOTOS_ALBUM_NO_SIZE = GOOD_PHOTOS.replace(
    "            MediaStore.Images.Media.WIDTH,\n"
    "            MediaStore.Images.Media.HEIGHT,\n",
    "",
)

BAD_PHOTOS_LIST_NO_BRANCH = GOOD_PHOTOS.replace(
    """        val range = when (val r = resolveDateRange(args)) {
            is PhotoRange.Window -> r
            is PhotoRange.Invalid ->
                return NativeOffloadResult(2, "android-photos list: invalid ${r.flag} '${r.raw}'")
        }""",
    "        val range = resolveDateRange(args) as PhotoRange.Window",
)

SELFTEST_MARK = "SELFTEST=PASS (schedule-date-source: 2 files x 2 arms, teeth on every criterion)"

# ── [dv16-A A-01] fixtures for the strict-parser criterion ─────────────────
GOOD_STRICT_PARSER = """
class FakeHandler {
    private fun parseDate(s: String): Long? {
        for (p in patterns) {
            try {
                val sdf = SimpleDateFormat(p, Locale.US).apply {
                    timeZone = if (p.endsWith("'Z'")) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
                    isLenient = false
                }
                return sdf.parse(s)?.time
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }
}
"""

BAD_LENIENT_PARSER = GOOD_STRICT_PARSER.replace("                    isLenient = false\n", "")

BAD_PARSER_REWRITTEN = GOOD_STRICT_PARSER.replace("SimpleDateFormat", "MyFormats")

BAD_PARSER_RENAMED = GOOD_STRICT_PARSER.replace("private fun parseDate(", "private fun parseDay(")

# ── [wide-1024] witness arms: each of these was GREEN before this branch ───
# dataflow: the dating funnel is still routed through the helper, but the
# helper credits the SETTLE day (one identifier) — ISSUE-09A in full.
BAD_RUNNER_SETTLE_DAY = GOOD_RUNNER.replace(
    "fun gateDateOnSettle(scheduledDate: String, settleDate: String, outcome: String): String = scheduledDate",
    "fun gateDateOnSettle(scheduledDate: String, settleDate: String, outcome: String): String = settleDate",
)

# dataflow: the alignment call is still there, still after the parse — but it
# aligns an empty string, so `--start D --end D` is a zero-width window again.
BAD_CALENDAR_ALIGN_EMPTY = GOOD_CALENDAR.replace(
    "alignBareDateEnd(it, endStr, TimeZone.getDefault())",
    'alignBareDateEnd(it, "", TimeZone.getDefault())',
)

# order: the strict assignment survives, a later lenient one overrides it.
BAD_PARSER_LENIENT_REENABLED = GOOD_STRICT_PARSER.replace(
    "                    isLenient = false\n",
    "                    isLenient = false\n                    isLenient = true\n",
)

STRICT_PARSERS_MARK = "STRICT_DATE_PARSERS_SELFTEST=PASS (4 parsers x 4 arms)"



# ── [BATCH7-t10] witness arms: each of these passed this gate before the
# hardening. Same six shapes the external audit used, re-expressed here so the
# gate proves it now decides on the EFFECT and not on the spelling.
#
# dataflow: the funnel is still routed through the helper, but the scheduled
# day it hands over is re-read from the clock INSIDE the argument — the old
# "blank the whole call, then look for a clock read" check could not see it.
BAD_RUNNER_DATING_ARG_CLOCK = GOOD_RUNNER.replace(
    "ScheduledRunDating.gateDateOnSettle(scheduledDate, LocalDate.now().toString(), outcome),",
    "ScheduledRunDating.gateDateOnSettle(scheduledDate.let { LocalDate.now().toString() }, "
    "LocalDate.now().toString(), outcome),",
)

# dataflow: no clock at all, but the first argument is some other day entirely.
BAD_RUNNER_DATING_FOREIGN_ARG = GOOD_RUNNER.replace(
    "ScheduledRunDating.gateDateOnSettle(scheduledDate, LocalDate.now().toString(), outcome),",
    "ScheduledRunDating.gateDateOnSettle(settleDay, LocalDate.now().toString(), outcome),",
)

# dataflow: the alignment call is present, after the parse, with the caller's
# own string — and then its result is dropped in favour of the parsed value.
BAD_CALENDAR_ALIGN_DISCARDED = GOOD_CALENDAR.replace(
    "?.let { alignBareDateEnd(it, endStr, TimeZone.getDefault()) }",
    "?.let { alignBareDateEnd(it, endStr, TimeZone.getDefault()).let { _ -> it } }",
)

# …same discard, spelled without a lambda parameter (the audit's self-doubt
# variant): the tail names an outer binding instead of the received value.
BAD_CALENDAR_ALIGN_DISCARD_PARSED = GOOD_CALENDAR.replace(
    "?.let { alignBareDateEnd(it, endStr, TimeZone.getDefault()) }",
    "?.let { alignBareDateEnd(it, endStr, TimeZone.getDefault()).let { ms } }",
)

# family-hole: the bare-date branch survives as a substring but its guard is
# folded to false — DATE_ONLY still matches, the alignment never happens.
BAD_CALENDAR_ALIGN_DEAD_GUARD = GOOD_CALENDAR.replace(
    "if (DATE_ONLY.matches(raw.trim())) endOfLocalDay(parsedMs, zone) else parsedMs",
    "if (DATE_ONLY.matches(raw.trim()) && false) endOfLocalDay(parsedMs, zone) else parsedMs",
)

# family-hole: the absent/present split survives, but its guard is now always
# true — a present --end never reaches the parser.
BAD_CALENDAR_CREATE_GUARD_ALWAYS = GOOD_CALENDAR.replace(
    "if (rawEnd.isNullOrBlank()) return startMs + 60L * 60 * 1000",
    "if (rawEnd.isNullOrBlank() || true) return startMs + 60L * 60 * 1000",
)

# spelling: the strict write is intact, a second one spells "relaxed" as an
# expression instead of the `true` literal.
BAD_PARSER_LENIENT_NOT_LITERAL = GOOD_STRICT_PARSER.replace(
    "                    isLenient = false\n",
    "                    isLenient = false\n                    isLenient = !false\n",
)

# order/dead-branch: the refusal branch and its message both survive, but the
# return sits behind `if (false)` and the arm answers an unfiltered window.
BAD_PHOTOS_LIST_DEAD_REFUSAL = GOOD_PHOTOS.replace(
    '            is PhotoRange.Invalid ->\n'
    '                return NativeOffloadResult(2, "android-photos list: invalid ${r.flag} '
    "'${r.raw}'\")\n",
    '            is PhotoRange.Invalid -> {\n'
    '                if (false) return NativeOffloadResult(2, "android-photos list: invalid '
    "${r.flag} '${r.raw}'\")\n"
    '                PhotoRange.Window(null, null)\n'
    '            }\n',
)
def _v(fn, fixture, *extra):
    cv, lv = views(fixture)
    return fn(cv, lv, "fixture", *extra)


def run_selftest():
    cases = [
        ("runner/compliant", _v(violations_scheduler, GOOD_RUNNER), 0),
        ("runner/settle-clock", _v(violations_scheduler, BAD_RUNNER_SETTLE_CLOCK), ">0"),
        ("calendar/compliant", _v(violations_calendar, GOOD_CALENDAR), 0),
        ("calendar/raw-parse", _v(violations_calendar, BAD_CALENDAR_RAW_PARSE), ">0"),
        ("calendar/create-swallows-end", _v(violations_calendar, BAD_CALENDAR_SWALLOW_END), ">0"),
        ("calendar/no-refusal", _v(violations_calendar, BAD_CALENDAR_NO_REFUSAL), ">0"),
        ("calendar/absent-vs-invalid", _v(violations_calendar, BAD_CALENDAR_NULL_CHECK), ">0"),
        ("calendar/no-1h-default", _v(violations_calendar, BAD_CALENDAR_NO_DEFAULT), ">0"),
        ("photos/compliant", _v(violations_photos, GOOD_PHOTOS), 0),
        ("photos/own-parse", _v(violations_photos, BAD_PHOTOS_OWN_PARSE), ">0"),
        ("photos/no-refusal", _v(violations_photos, BAD_PHOTOS_NO_REFUSAL), ">0"),
        ("photos/no-inversion-refusal", _v(violations_photos, BAD_PHOTOS_NO_INVERT), ">0"),
        ("photos/album-drops-size", _v(violations_photos, BAD_PHOTOS_ALBUM_NO_SIZE), ">0"),
        ("photos/list-loses-branch", _v(violations_photos, BAD_PHOTOS_LIST_NO_BRANCH), ">0"),
        # [dv16-A A-01] the strict-date-parser criterion: compliant fixture is
        # clean, and a lenient / rewritten / renamed parser each trip it.
        ("parsers/strict", _v(violations_strict_date_parser, GOOD_STRICT_PARSER,
                              "private fun parseDate(", "--start/--end"), 0),
        ("parsers/lenient", _v(violations_strict_date_parser, BAD_LENIENT_PARSER,
                               "private fun parseDate(", "--start/--end"), ">0"),
        ("parsers/rewritten", _v(violations_strict_date_parser, BAD_PARSER_REWRITTEN,
                                 "private fun parseDate(", "--start/--end"), ">0"),
        ("parsers/renamed", _v(violations_strict_date_parser, BAD_PARSER_RENAMED,
                               "private fun parseDate(", "--start/--end"), ">0"),
        # ── wide-1024 witness arms ──
        ("runner/settle-day-returned", _v(violations_scheduler, BAD_RUNNER_SETTLE_DAY), ">0"),
        ("calendar/aligns-empty-raw", _v(violations_calendar, BAD_CALENDAR_ALIGN_EMPTY), ">0"),
        ("parsers/lenient-reenabled", _v(violations_strict_date_parser,
                                         BAD_PARSER_LENIENT_REENABLED,
                                         "private fun parseDate(", "--start/--end"), ">0"),
        # ── [BATCH7-t10] witness arms ──
        ("runner/dating-arg-clock", _v(violations_scheduler, BAD_RUNNER_DATING_ARG_CLOCK), ">0"),
        ("runner/dating-foreign-arg", _v(violations_scheduler, BAD_RUNNER_DATING_FOREIGN_ARG), ">0"),
        ("calendar/align-result-discarded", _v(violations_calendar, BAD_CALENDAR_ALIGN_DISCARDED), ">0"),
        ("calendar/align-result-discarded-2", _v(violations_calendar, BAD_CALENDAR_ALIGN_DISCARD_PARSED), ">0"),
        ("calendar/align-dead-guard", _v(violations_calendar, BAD_CALENDAR_ALIGN_DEAD_GUARD), ">0"),
        ("calendar/create-guard-always", _v(violations_calendar, BAD_CALENDAR_CREATE_GUARD_ALWAYS), ">0"),
        ("parsers/lenient-not-literal", _v(violations_strict_date_parser, BAD_PARSER_LENIENT_NOT_LITERAL,
                                           "private fun parseDate(", "--start/--end"), ">0"),
        ("photos/list-dead-refusal", _v(violations_photos, BAD_PHOTOS_LIST_DEAD_REFUSAL), ">0"),
    ]
    problems = []
    for name, bad, expected in cases:
        want_clean = expected == 0
        if want_clean and bad:
            problems.append("%s: expected CLEAN, got %d violation(s): %s" % (name, len(bad), bad[:2]))
        if not want_clean and not bad:
            problems.append("%s: expected VIOLATIONS, got clean" % name)
    # The blocking criteria must each have teeth: neutering any single rule must
    # turn one of the fixtures red for that rule alone.
    if not any("not routed through" in b for b in _v(violations_scheduler, BAD_RUNNER_SETTLE_CLOCK)):
        problems.append("runner/settle-clock: the routing rule did not fire")
    if not any("zero-width" in b for b in _v(violations_calendar, BAD_CALENDAR_RAW_PARSE)):
        problems.append("calendar/raw-parse: the raw-parse rule did not fire")
    # [wide-1024] each witness arm must be red for ITS OWN rule, not by accident
    if not any("returns the SETTLE day" in b for b in _v(violations_scheduler, BAD_RUNNER_SETTLE_DAY)):
        problems.append("runner/settle-day-returned: the returned-day rule did not fire")
    if not any("as the raw date" in b for b in _v(violations_calendar, BAD_CALENDAR_ALIGN_EMPTY)):
        problems.append("calendar/aligns-empty-raw: the raw-date-origin rule did not fire")
    if not any("re-enables LENIENT mode" in b
               for b in _v(violations_strict_date_parser, BAD_PARSER_LENIENT_REENABLED,
                           "private fun parseDate(", "--start/--end")):
        problems.append("parsers/lenient-reenabled: the effective-mode rule did not fire")
    if not any("24h constant" in b for b in _v(violations_calendar, BAD_CALENDAR_RAW_PARSE)):
        problems.append("calendar/raw-parse: the DST-constant rule did not fire")
    if not any("alignBareDateEnd" in b for b in _v(violations_calendar, BAD_CALENDAR_RAW_PARSE)):
        problems.append("calendar/raw-parse: the freebusy alignment rule did not fire")
    # [da1032-C] ISSUE-04(A): each new Calendar rule proves itself, and the
    # compliant fixture's legit "absent --end -> 1h default" path stays clean.
    if not any("does not route --end through createEndMs" in b
               for b in _v(violations_calendar, BAD_CALENDAR_SWALLOW_END)):
        problems.append("calendar/create-swallows-end: the routing rule did not fire")
    if not any("keeps the elvis-default swallow" in b
               for b in _v(violations_calendar, BAD_CALENDAR_SWALLOW_END)):
        problems.append("calendar/create-swallows-end: the elvis-swallow rule did not fire")
    if not any("no longer refuses an unparseable --end" in b
               for b in _v(violations_calendar, BAD_CALENDAR_NO_REFUSAL)):
        problems.append("calendar/no-refusal: the refusal rule did not fire")
    if not any("separates ABSENT" in b for b in _v(violations_calendar, BAD_CALENDAR_NULL_CHECK)):
        problems.append("calendar/absent-vs-invalid: the decision-point rule did not fire")
    if not any("lost the 1h default" in b for b in _v(violations_calendar, BAD_CALENDAR_NO_DEFAULT)):
        problems.append("calendar/no-1h-default: the default rule did not fire")
    # [da1032-C] ISSUE-04(B): each Photos rule proves itself; the two precision
    # controls pin that a delegating/emitting fixture is NOT flagged.
    if not any("parses --start/--end itself again" in b
               for b in _v(violations_photos, BAD_PHOTOS_OWN_PARSE)):
        problems.append("photos/own-parse: the inline-parse rule did not fire")
    if any("must delegate to the pure core" in b for b in _v(violations_photos, BAD_PHOTOS_OWN_PARSE)):
        problems.append("photos/own-parse: the delegate rule fired on a delegating fixture")
    if not any("no longer returns an Invalid outcome" in b
               for b in _v(violations_photos, BAD_PHOTOS_NO_REFUSAL)):
        problems.append("photos/no-refusal: the Invalid-outcome rule did not fire")
    if any("inverted-range refusal" in b for b in _v(violations_photos, BAD_PHOTOS_NO_REFUSAL)):
        problems.append("photos/no-refusal: the inversion rule fired on a fixture that kept it")
    if not any("inverted-range refusal" in b for b in _v(violations_photos, BAD_PHOTOS_NO_INVERT)):
        problems.append("photos/no-inversion-refusal: the inversion rule did not fire")
    if not any("projection no longer asks for WIDTH/HEIGHT" in b
               for b in _v(violations_photos, BAD_PHOTOS_ALBUM_NO_SIZE)):
        problems.append("photos/album-drops-size: the projection rule did not fire")
    if any("no longer emits width/height" in b
           for b in _v(violations_photos, BAD_PHOTOS_ALBUM_NO_SIZE)):
        problems.append("photos/album-drops-size: the emit rule fired on a fixture that kept it")
    if not any("branches on PhotoRange.Invalid" in b
               for b in _v(violations_photos, BAD_PHOTOS_LIST_NO_BRANCH)):
        problems.append("photos/list-loses-branch: the branch rule did not fire")
    if not any("reports which bound was invalid" in b
               for b in _v(violations_photos, BAD_PHOTOS_LIST_NO_BRANCH)):
        problems.append("photos/list-loses-branch: the refusal-message rule did not fire")

    # [dv16-A A-01] each branch of the strict-parser criterion proves itself,
    # and precise failure modes are distinguished (lenient vs rewritten vs
    # renamed) so a neutered rule cannot pass as "some finding fired".
    if not any("DEFAULT LENIENT mode" in b
               for b in _v(violations_strict_date_parser, BAD_LENIENT_PARSER,
                           "private fun parseDate(", "--start/--end")):
        problems.append("parsers/lenient: the lenient-mode rule did not fire")
    if any("DEFAULT LENIENT mode" in b
           for b in _v(violations_strict_date_parser, GOOD_STRICT_PARSER,
                       "private fun parseDate(", "--start/--end")):
        problems.append("parsers/strict: the lenient rule fired on the compliant fixture")
    if not any("no longer parses with a SimpleDateFormat" in b
               for b in _v(violations_strict_date_parser, BAD_PARSER_REWRITTEN,
                           "private fun parseDate(", "--start/--end")):
        problems.append("parsers/rewritten: the decision-rewrite rule did not fire")
    if not any("is gone" in b
               for b in _v(violations_strict_date_parser, BAD_PARSER_RENAMED,
                           "private fun parseDate(", "--start/--end")):
        problems.append("parsers/renamed: the fail-closed marker rule did not fire")
    # …and the flag may sit anywhere inside the parser, not only in the apply{}:
    if _v(violations_strict_date_parser,
          GOOD_STRICT_PARSER.replace(
              "                    isLenient = false\n", "").replace(
              "                return sdf.parse(s)?.time",
              "                sdf.isLenient = false\n                return sdf.parse(s)?.time"),
          "private fun parseDate(", "--start/--end"):
        problems.append("parsers/anywhere: a flag outside the apply{} was reported as missing")

    # ── [BATCH7-t10] each new arm must be red for ITS OWN rule ──
    if not any("wall-clock read" in b for b in _v(violations_scheduler, BAD_RUNNER_DATING_ARG_CLOCK)):
        problems.append("runner/dating-arg-clock: the argument-value rule did not fire")
    if any("not routed through" in b for b in _v(violations_scheduler, BAD_RUNNER_DATING_ARG_CLOCK)):
        problems.append("runner/dating-arg-clock: the routing rule fired although it is routed")
    if not any("does not derive from the run's scheduled day" in b
               for b in _v(violations_scheduler, BAD_RUNNER_DATING_FOREIGN_ARG)):
        problems.append("runner/dating-foreign-arg: the scheduled-day-origin rule did not fire")
    if not any("discards its own result" in b
               for b in _v(violations_calendar, BAD_CALENDAR_ALIGN_DISCARDED)):
        problems.append("calendar/align-result-discarded: the value-flow rule did not fire")
    if not any("discards its own result" in b
               for b in _v(violations_calendar, BAD_CALENDAR_ALIGN_DISCARD_PARSED)):
        problems.append("calendar/align-result-discarded-2: the value-flow rule did not fire")
    if any("as the raw date" in b for b in _v(violations_calendar, BAD_CALENDAR_ALIGN_DISCARDED)):
        problems.append("calendar/align-result-discarded: the raw-date rule fired on a good arg")
    if not any("is DEAD" in b for b in _v(violations_calendar, BAD_CALENDAR_ALIGN_DEAD_GUARD)):
        problems.append("calendar/align-dead-guard: the reachability rule did not fire")
    if not any("ALWAYS taken" in b
               for b in _v(violations_calendar, BAD_CALENDAR_CREATE_GUARD_ALWAYS)):
        problems.append("calendar/create-guard-always: the always-true-guard rule did not fire")
    if not any("re-enables LENIENT mode" in b
               for b in _v(violations_strict_date_parser, BAD_PARSER_LENIENT_NOT_LITERAL,
                           "private fun parseDate(", "--start/--end")):
        problems.append("parsers/lenient-not-literal: the constant-folded-mode rule did not fire")
    dead = _v(violations_photos, BAD_PHOTOS_LIST_DEAD_REFUSAL)
    if not any("never returns on a reachable path" in b for b in dead):
        problems.append("photos/list-dead-refusal: the reachability rule did not fire")
    if not any("UNFILTERED" in b for b in dead):
        problems.append("photos/list-dead-refusal: the fall-through-window rule did not fire")

    # fail-closed on a KotlinLexError (hard constraint: no silent green)
    try:
        views('val s = "unterminated')
        problems.append("lexer-fail-closed: an unterminated string did not raise")
    except KotlinLexError:
        pass

    if problems:
        print("SELFTEST=FAIL")
        for p in problems:
            print("  -", p)
        return 1
    print(SELFTEST_MARK)
    print("SCHEDULE_DATE_SOURCE_SELFTEST=PASS (%d arms)" % len(cases))
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root", nargs="?", default=None)
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return run_selftest()

    root = os.path.abspath(
        args.root or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    bad = check(root)
    if bad:
        print("schedule-date-source: %d violation(s)" % len(bad))
        for b in bad:
            print("  " + b)
        return 1
    print("schedule-date-source: OK (scheduled day threaded; bare-date end aligned; DST-safe day math)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
