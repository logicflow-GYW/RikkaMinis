#!/usr/bin/env python3
"""deep-audit-1016c ISSUE-11 (P2) — RSS probe lock-scope gate.

Pins the contract introduced by branch fix/da1016-rssprobe-lock-scope for
sandbox/OffloadRssProbe.kt: the four field increments, the LEAK-SUSPECT
winner-flag write, the governance threshold decision and the cumulative
reset must form ONE critical section, and summary() must take a snapshot
under the same lock.

Before the fix, `synchronized(st)` guarded only the increments; the
threshold checks, `st.leaked = true` and `st.totalDeltaKb = 0L` all ran
outside it, so two offload workers (Semaphore(2), NativeOffload.kt:116/160)
could lose an increment between check and reset, double-fire the
LEAK-SUSPECT WARN, and concurrently execute the high-cost governanceHook
twice.

Scope: OffloadRssProbe.kt is enforced (FAIL). The same family exists in
browser/BrowserRssProbe.kt and provider/ProviderRssProbe.kt; all three are
enforced.

[batch6-t03] The 12 witnesses that broke this gate all attacked the same
weakness: the criteria counted TOKENS (`synchronized(` appears once,
`st.totalDeltaKb = 0L` appears somewhere) instead of judging the DECISION —
which monitor, inside which block, on a reachable path, with the value
actually consumed. Each criterion below is now derived from structure:

  * the monitor must be a STABLE, shared object (`synchronized(Any())` is a
    fresh object per call ⇒ zero mutual exclusion);
  * no state write to the guarded object may sit outside the block — any
    field, any operator, any zero spelling (`st.totalDeltaKb = 0.toLong()`);
  * the decision call must be LIVE (not `if (false) … else RssDecision(0…)`)
    and its result consumed;
  * the cumulative reset and the leak-flag write inside the decision
    function must be on a reachable path (`if (govern && false) …` is dead);
  * summary's snapshot value must be a `copy()` taken under that stats'
    own lock — a bare `name to st` stores a live reference, and a decoy
    `synchronized(this) {}` elsewhere in the function proves nothing.

Every structural question reads `mask_code()` (comments AND string/char
literal bodies blanked, length preserved), so `"http://"; st.leaked = true`
is ordinary code again and a `}` inside a comment cannot truncate a body.

Usage: python3 rss_probe_lock_scope_check.py [ROOT]   (ROOT = repo root)
       python3 rss_probe_lock_scope_check.py --selftest
Exit:  0 = gate green, 1 = violations found (or selftest failures).
"""
import os
import re
import sys

from _kotlin_lexer import KotlinLexError, mask_code
from _kotlin_ast import (const_bool, match_bracket, split_top,
                         enclosing_conditions, stmt_spans, strip_parens)
from _kotlin_valueflow import (brace_depth, contains_ident, expr_end,
                               guard_feasible, path_feasible, value_sources)

FAMILY = (
    "src/android/app/src/main/java/com/rikkaminis/app/sandbox/OffloadRssProbe.kt",
    "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserRssProbe.kt",
    "src/android/app/src/main/java/com/rikkaminis/app/provider/ProviderRssProbe.kt",
)
# [da1019 FIX-I] the whole family is now enforced: both remaining probes were
# fixed in fix/da1019-rssprobe-lock-scope. The governance-reset criterion is
# scoped to GOVERNANCE_OWNER — Browser/Provider probes deliberately have NO
# governance semantics (no cumulative reset), so requiring the reset there
# would force a dead/fake write and change their behaviour.
ENFORCED = FAMILY
GOVERNANCE_OWNER = FAMILY[0]

RECORD = "record"
DECISION = "applyAndDecideLocked"
SUMMARY = "summary"

OUTSIDE_RECORD_BAD = (
    "reset outside record's critical section",
    "leak winner flag write outside record's critical section",
    "LEAK_SUSPECT threshold check outside record's critical section",
    "CUMULATIVE_GOVERN threshold check outside record's critical section",
    "PEAK_GOVERN threshold check outside record's critical section",
)
OUTSIDE_RECORD_NEEDLES = (
    ">= LEAK_SUSPECT_KB",
    ">= CUMULATIVE_GOVERN_KB",
    ">= PEAK_GOVERN_KB",
)
DECISION_MUST_CONTAIN = (
    ("governance reset moved out of the locked decision function", "totalDeltaKb"),
    ("leak winner flag moved out of the locked decision function", "leaked"),
)

# [batch6-t03] The positional criteria. Names are stable: the selftest arms
# (and the frozen test_scan marker) address them by string.
MONITOR_CRIT = "record's synchronized block uses a stable, shared monitor"
WRITE_OUTSIDE_CRIT = "record performs no state write outside its synchronized block"
DECISION_LOCKED_CRIT = "record calls %s inside its synchronized block" % DECISION
SUMMARY_CRIT = "summary takes a snapshot under the lock"
SUMMARY_COPY_CRIT = "summary's snapshot value is a copy taken under that stats' lock"
ONE_SECTION_CRIT = "record has exactly one synchronized section"


def line_of(src, needle):
    i = src.find(needle)
    return src.count("\n", 0, i) + 1 if i >= 0 else 0


FUN_DECL_FULL = re.compile(
    r"\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*\("
)


def fn_spans(text, name):
    """[(body_start, body_end, kind)] for EVERY `fun <name>(…)`.

    Overloads are judged too (ProviderRssProbe ships two `record`s) — taking
    only the first declaration left the second one unread.
    """
    out = []
    for m in FUN_DECL_FULL.finditer(text):
        if m.group(1) != name:
            continue
        p_close = match_bracket(text, m.end() - 1)
        if p_close < 0:
            continue
        i, n = p_close + 1, len(text)
        while i < n and text[i] not in "={":
            if text[i] == "\n":
                i = -1
                break
            i += 1
        if i < 0 or i >= n:
            continue
        if text[i] == "{":
            close = match_bracket(text, i)
            if close > 0:
                out.append((i + 1, close, "block"))
        else:
            out.append((i + 1, expr_end(text, i + 1), "expr"))
    return out


def decl_span(text, decl):
    """(body_start, body_end) inside the `{…}` that opens [decl]; None if absent/duplicated."""
    if not decl or text.count(decl) != 1:
        return None
    i = text.index(decl)
    open_idx = i + len(decl) - 1
    if open_idx >= len(text) or text[open_idx] != "{":
        open_idx = text.find("{", i)
        if open_idx < 0:
            return None
    close = match_bracket(text, open_idx)
    return (open_idx + 1, close) if close > 0 else None


def synchronized_blocks(text):
    """[(lock_expr, body_start, body_end)] for every `synchronized(…) { … }`.

    Braces come from `match_bracket` on the masked view, so a `{`/`}` inside a
    string or a comment cannot shift the span. Unparseable calls are dropped and
    the caller fail-closes when it needs a block and finds none.
    """
    out = []
    for m in re.finditer(r"\bsynchronized\s*\(", text):
        p = m.end() - 1
        close = match_bracket(text, p)
        if close < 0:
            continue
        lock_expr = text[p + 1: close].strip()
        brace = text.find("{", close)
        if brace < 0:
            continue
        bend = match_bracket(text, brace)
        if bend < 0:
            continue
        out.append((lock_expr, brace + 1, bend))
    return out


def lock_is_stable(lock_expr):
    """True when the monitor expression is a shared, pre-existing object.

    `synchronized(Any())` / `synchronized(Object())` / `synchronized(Mutex())`
    allocate a NEW object on every call: two threads then never contend, so the
    block protects nothing. Stability is judged on the expression SHAPE — a
    call/constructor/literal is not a monitor; an identifier chain is.
    """
    e = strip_parens((lock_expr or "").strip())
    if not e:
        return False
    if "(" in e or ")" in e:
        return False           # constructor / factory call / wrapper
    if re.fullmatch(r"(?:true|false|null|Unit|Any|Object)", e):
        return False
    if not re.fullmatch(r"[A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)*", e):
        return False
    return True


def decision_calls(text):
    """[(pos, first_arg)] for every live call to the locked decision helper."""
    out = []
    for m in re.finditer(r"\b" + re.escape(DECISION) + r"\s*\(", text):
        p = m.end() - 1
        close = match_bracket(text, p)
        if close < 0:
            continue
        args = split_top(text[p + 1: close], (",",))
        first = text[p + 1 + args[0][0]: p + 1 + args[0][1]].strip() if args else ""
        out.append((m.start(), first))
    return out


def state_writes(text, ident, field=None):
    """[(pos, field, op)] for `ident.<field> <op>` WRITES (never reads)."""
    fpat = re.escape(field) if field else r"[A-Za-z_]\w*"
    pat = re.compile(
        r"(?<![\w`])" + re.escape(ident) + r"\s*\.\s*(" + fpat + r")\s*"
        r"(?:\+=|-=|\*=|/=|%=|=(?!=)|\+\+|--)"
    )
    return [(m.start(), m.group(1), "") for m in pat.finditer(text)]


def in_any_block(pos, blocks):
    return any(a <= pos <= b for (_lock, a, b) in blocks)


def live(text, pos, body_offset=0, domains=None):
    """Reachability inside a body slice (positions are body-local)."""
    return path_feasible(text, pos, domains)


def value_is_zero(rhs):
    """`0L` / `0` / `0.toLong()` — the reset's zero spelled any way it compiles."""
    e = strip_parens((rhs or "").strip())
    if not e:
        return False
    e = re.sub(r"\.\s*to(?:Long|Int|Short|Byte)\s*\(\s*\)", "", e)
    e = re.sub(r"(?<=\d)[lL]\b", "", e)
    e = e.replace("_", "")
    return bool(re.fullmatch(r"0+", e))


_LEX = {"reason": ""}


def mask_or_fail(raw_src):
    """The one structural view (mask_code). None = lex error (fail-closed)."""
    try:
        return mask_code(raw_src)
    except KotlinLexError as exc:
        _LEX["reason"] = str(exc)
        return None


def _field_writes(body, field):
    """[(pos, op, rhs)] for `<x>.<field> <op> <rhs>` — any receiver, any operator."""
    out = []
    pat = re.compile(
        r"(?<![\w`])[A-Za-z_]\w*\s*\.\s*" + re.escape(field)
        + r"\s*(\+=|-=|\*=|/=|%=|(?:=(?!=))|\+\+|--)"
    )
    for m in pat.finditer(body):
        op = m.group(1)
        rhs_start = m.end()
        rhs = body[rhs_start: expr_end(body, rhs_start)].strip()
        out.append((m.start(), op, rhs))
    return out


def value_is_true(rhs):
    return const_bool(strip_parens((rhs or "").strip())) is True


def _map_regions(body):
    """[(start, end)] of every `.map { … }` argument region inside [body]."""
    out = []
    for m in re.finditer(r"\.\s*map(?:To|NotNull)?\s*(?:<[^>]*>)?\s*\{", body):
        brace = body.rfind("{", m.start(), m.end())
        bend = match_bracket(body, brace)
        if bend > 0:
            out.append((brace + 1, bend))
    return out


def _lambda_params(region):
    """Identifiers introduced by the region's lambda parameter lists."""
    params = set()
    for arrow in re.finditer(r"->", region):
        open_brace = None
        depth = 0
        i = arrow.start() - 1
        while i >= 0:
            if region[i] == "}":
                depth += 1
            elif region[i] == "{":
                if depth == 0:
                    open_brace = i
                    break
                depth -= 1
            i -= 1
        if open_brace is None:
            head = region[: arrow.start()]   # region starts after `{` — head is the param list
        else:
            head = region[open_brace + 1: arrow.start()]
        params.update(re.findall(r"[A-Za-z_]\w*", head))
    for m in re.finditer(r"\bit\b", region):
        params.add("it")
    return params


def _bare_param_uses(region, param, blocks):
    """Uses of [param] in [region] that are neither a lock argument nor a field
    access inside its own locked block — i.e. the live reference escaping."""
    escapes, unlocked_reads = [], []
    for m in re.finditer(r"(?<![\w`])" + re.escape(param) + r"(?![\w`])", region):
        before = region[: m.start()].rstrip()
        after = region[m.end():].lstrip()
        if after.startswith("."):
            if not in_any_block(m.start(), blocks):
                unlocked_reads.append(m.start())
            continue
        if before.endswith("synchronized(") or before.endswith("synchronized ("):
            continue
        if re.search(r"(?:^|[{(,]\s*|\b(?:val|var)\s+)$", before):
            continue  # the parameter declaration itself
        escapes.append(m.start())
    return escapes, unlocked_reads


def _judge_record(text, res):
    spans = fn_spans(text, RECORD)
    if not spans:
        res.append(("record function found", False, "missing", 0))
        return
    for s, e, _kind in spans:
        body = text[s:e]
        blocks = synchronized_blocks(body)
        calls = decision_calls(body)
        idents = set()
        for lock_expr, _bs, _be in blocks:
            if re.fullmatch(r"[A-Za-z_]\w*", lock_expr.strip()):
                idents.add(lock_expr.strip())
        for _pos, first in calls:
            if re.fullmatch(r"[A-Za-z_]\w*", first):
                idents.add(first)
        if not calls and not blocks and not idents:
            continue  # a delegating overload has no critical section to judge

        res.append((ONE_SECTION_CRIT, len(blocks) == 1,
                    "found %d synchronized block(s)" % len(blocks), s))
        if len(blocks) == 1:
            lock_expr, _bs, _be = blocks[0]
            res.append((MONITOR_CRIT, lock_is_stable(lock_expr),
                        "monitor = `%s`" % (lock_expr or "(empty)"), s))
        else:
            res.append((MONITOR_CRIT, False, "no single block to take a monitor from", s))

        if not calls:
            res.append((DECISION_LOCKED_CRIT, False,
                        "%s( is never called — the locked decision was inlined or removed "
                        "(fail-closed)" % DECISION, s))
        else:
            dead = [c for c in calls if not path_feasible(body, c[0])]
            outside = [c for c in calls
                       if path_feasible(body, c[0]) and not in_any_block(c[0], blocks)]
            ok = calls and not outside and not dead
            if ok:
                detail = "all %d call site(s) live inside a synchronized block" % len(calls)
            elif dead:
                detail = "%d/%d call site(s) sit on an unreachable path (a dead branch " \
                         "short-circuits the locked decision)" % (len(dead), len(calls))
            else:
                detail = "%d/%d live call site(s) OUTSIDE every synchronized block body " \
                         "(the lock is a decoy — the decision runs unlocked)" \
                         % (len(outside), len(calls))
            res.append((DECISION_LOCKED_CRIT, ok, detail,
                        line_of(text, DECISION + "(")))
        if not idents:
            res.append(("guarded state object derivable (fail-closed)", False,
                        "neither the monitor nor the decision's first argument is a plain "
                        "identifier — the gate cannot tell which object the lock guards", s))
            continue
        for ident in sorted(idents):
            for pos, field, _op in state_writes(body, ident):
                if in_any_block(pos, blocks):
                    continue
                if field == "totalDeltaKb":
                    crit = OUTSIDE_RECORD_BAD[0]
                elif field == "leaked":
                    crit = OUTSIDE_RECORD_BAD[1]
                else:
                    crit = WRITE_OUTSIDE_CRIT
                res.append((crit, False,
                            "`%s.%s` is written outside the synchronized block (any field, "
                            "any operator, any spelling counts — a bare `= 0.toLong()` is a "
                            "reset and a bare `= true` is a winner flag)" % (ident, field),
                            s + pos))


def _judge_decision(text, res, require_govern_reset):
    spans = fn_spans(text, DECISION)
    if not spans:
        res.append(("locked decision function %s found" % DECISION, False, "missing", 0))
        return
    for s, e, _kind in spans:
        body = text[s:e]
        for crit, field in DECISION_MUST_CONTAIN:
            if field == "totalDeltaKb" and not require_govern_reset:
                continue
            writes = [(pos, op, rhs) for pos, op, rhs in _field_writes(body, field)
                      if op == "="]
            live_zero = [pos for pos, _op, rhs in writes
                         if path_feasible(body, pos) and value_is_zero(rhs)]
            live_true = [pos for pos, _op, rhs in writes
                         if path_feasible(body, pos) and value_is_true(rhs)]
            if field == "totalDeltaKb":
                ok = bool(live_zero)
                if ok:
                    detail = "cumulative reset is a live `= 0` write"
                elif writes:
                    detail = "every reset write is dead (`if (govern && false) …`) or not zero " \
                             "— the cumulative total is never actually cleared"
                else:
                    detail = "no `<x>.totalDeltaKb = …` write at all"
            else:
                ok = bool(live_true)
                if ok:
                    detail = "leak winner flag is a live `= true` write"
                elif writes:
                    detail = "every winner-flag write is dead on its path"
                else:
                    detail = "no `<x>.leaked = …` write at all"
            res.append((crit, ok, detail, s))


def _judge_summary(text, res):
    spans = fn_spans(text, SUMMARY)
    if not spans:
        res.append(("summary function found", False, "missing", 0))
        return
    for s, e, _kind in spans:
        body = text[s:e]
        regions = _map_regions(body)
        judged = False
        for rs, rend in regions:
            region = body[rs:rend]
            blocks = synchronized_blocks(region)
            param = None
            for lock_expr, _bs, _be in blocks:
                if re.fullmatch(r"[A-Za-z_]\w*", lock_expr.strip()):
                    param = lock_expr.strip()
                    break
            if param is None:
                params = sorted(_lambda_params(region))
                if len(params) >= 2:
                    param = params[1]      # `(name, st) ->` — the stats slot
            if param is None:
                continue
            judged = True
            copy_ok = any(
                re.search(r"(?<![\w`])" + re.escape(param) + r"\s*\.\s*copy\s*\(",
                          region[bs:be])
                for lock_expr, bs, be in blocks if lock_expr.strip() == param
            )
            escapes, unlocked = _bare_param_uses(region, param, blocks)
            ok = copy_ok and not escapes and not unlocked
            if ok:
                detail = "snapshot value is `%s.copy()` taken under `synchronized(%s)`" % (param, param)
            elif not copy_ok:
                detail = "no `synchronized(%s) { %s.copy() }` in the snapshot mapping — the " \
                         "stored value is the live object" % (param, param)
            elif escapes:
                detail = "the stats reference escapes the snapshot (`%s` used as a bare " \
                         "value) — a decoy lock elsewhere proves nothing" % param
            else:
                detail = "`%s.<field>` read outside the block locked on `%s`" % (param, param)
            res.append((SUMMARY_CRIT, ok, detail, s + rs))
        if not judged:
            res.append((SUMMARY_CRIT, False,
                        "no snapshot mapping could be located (fail-closed: the gate cannot "
                        "confirm the summary reads copies)", s))


def analyse(raw_src, require_govern_reset=True):
    """Returns list of (criterion, ok, detail, line). ok=True means contract held."""
    res = []
    text = mask_or_fail(raw_src)
    if text is None:
        return [("source tokenises (fail-closed)", False, _LEX["reason"], 0)]
    _judge_record(text, res)
    _judge_decision(text, res, require_govern_reset)
    _judge_summary(text, res)
    for idx, needle in enumerate(OUTSIDE_RECORD_NEEDLES):
        n = 0
        for s, e, _kind in fn_spans(text, RECORD):
            n += text[s:e].count(needle)
        res.append((OUTSIDE_RECORD_BAD[2 + idx], n == 0,
                    "%d occurrence(s) in record" % n, line_of(text, needle)))
    return res


# ───────────────────────────────────────────────────────────────────────────
# fixtures
# ───────────────────────────────────────────────────────────────────────────
COMPLIANT = """package com.rikkaminis.app.sandbox

internal object OffloadRssProbe {
    private val byHandler = LinkedHashMap<String, Stats>()
    private var governHookRuns = 0L

    fun record(handler: String, deltaKb: Long) {
        val st = byHandler.getOrPut(handler) { Stats() }
        val d = synchronized(st) { applyAndDecideLocked(st, deltaKb) }
        if (d.govern) { governHookRuns++; runCatching { governanceHook() } }
    }

    private fun applyAndDecideLocked(st: Stats, deltaKb: Long): RssDecision {
        st.totalDeltaKb += deltaKb
        st.leaked = false
        val govern = st.totalDeltaKb >= CUMULATIVE_GOVERN_KB
        if (govern) st.totalDeltaKb = 0L
        val warnLeak = st.totalDeltaKb >= LEAK_SUSPECT_KB
        if (warnLeak) st.leaked = true
        return RssDecision(0L, 0, 0L, 0L, govern, false, false)
    }

    fun summary(): String {
        val snapshot = byHandler.entries.map { (name, st) -> name to synchronized(st) { st.copy() } }
        return "offload-rss summary:" + snapshot.size
    }
}
"""

LOCKED = "val d = synchronized(st) { applyAndDecideLocked(st, deltaKb) }"
SNAPSHOT = ("val snapshot = byHandler.entries.map { (name, st) -> "
            "name to synchronized(st) { st.copy() } }")

BREAKERS = {
    # 1 — the whole decision + reset pushed out of the block (pre-fix shape).
    1: ([(LOCKED, "val d = applyAndDecideLocked(st, deltaKb)\n"
                  "        if (st.totalDeltaKb >= CUMULATIVE_GOVERN_KB) st.totalDeltaKb = 0L")],
        OUTSIDE_RECORD_BAD[0]),
    # 2 — winner flag written outside the block while the flag is read there.
    2: ([("if (warnLeak) st.leaked = true", ""),
         (LOCKED, "if (!st.leaked) st.leaked = true\n        " + LOCKED)],
        OUTSIDE_RECORD_BAD[1]),
    # 3 — the block removed entirely.
    3: ([(LOCKED, "val d = applyAndDecideLocked(st, deltaKb)")], ONE_SECTION_CRIT),
    # 4 — summary stores the live object instead of a copy.
    4: ([(SNAPSHOT, "val snapshot = byHandler.entries.map { (name, st) -> name to st }")],
        SUMMARY_CRIT),
}

WITNESS_ARMS = {
    # [batch6-t03] witness 1 — a decoy empty block plus a hand-written call.
    "decoy-empty-block": ([
        (LOCKED, "synchronized(st) {}\n        val d = applyAndDecideLocked(st, deltaKb)"),
    ], DECISION_LOCKED_CRIT),
    # witness 2 — the block kept, the call replaced by inline increments.
    "decision-call-removed": ([
        (LOCKED, "val d = synchronized(st) { st.totalDeltaKb += deltaKb }"),
    ], DECISION_LOCKED_CRIT),
    # witness 3 — a fresh monitor per call: `synchronized(Any())` protects nothing.
    "monitor-freshly-allocated": ([
        (LOCKED, "val d = synchronized(Any()) { applyAndDecideLocked(st, deltaKb) }"),
    ], MONITOR_CRIT),
    # witness 4 — decoy `synchronized(this) {}` elsewhere, snapshot stores the live ref.
    "summary-bare-reference": ([
        (SNAPSHOT, "synchronized(this) {}\n        val snapshot = byHandler.entries.map "
                   "{ (name, st) -> name to st }"),
    ], SUMMARY_CRIT),
    # witness 5 — the cumulative reset kept but guarded by an unsatisfiable condition.
    "reset-on-dead-path": ([
        ("if (govern) st.totalDeltaKb = 0L", "if (govern && false) st.totalDeltaKb = 0L"),
    ], DECISION_MUST_CONTAIN[0][0]),
    # witness 6 — a second reset spelling (`0.toLong()`) outside the block.
    "reset-outside-by-spelling": ([
        (LOCKED, LOCKED + "\n        st.totalDeltaKb = 0.toLong()"),
    ], OUTSIDE_RECORD_BAD[0]),
    # witness 7 — the decision moved into an unreachable branch.
    "decision-in-dead-branch": ([
        (LOCKED, "val d = synchronized(st) {\n"
                 "            if (false) applyAndDecideLocked(st, deltaKb) "
                 "else RssDecision(0L, 0, 0L, 0L, false, false, false)\n        }"),
    ], DECISION_LOCKED_CRIT),
    # witness 8 — a `"http://"` literal swallows the rest of the line for a
    # string-blind comment stripper; the unconditional writes go unjudged.
    "string-swallowed-writes": ([
        (LOCKED, LOCKED + '\n        val u = "http://"; st.totalDeltaKb = 0L; st.leaked = true'),
    ], OUTSIDE_RECORD_BAD[1]),
}


def selftest():
    failures = []

    def run_arm(label, pairs, target):
        mutated = COMPLIANT
        missing = [old for old, _new in pairs if old not in mutated]
        if missing:
            failures.append("%s cannot apply (fixture text moved): %s" % (label, missing))
            print("  [FAIL] %s: anchor not found in fixture" % label)
            return
        for old, new in pairs:
            mutated = mutated.replace(old, new, 1)
        res = analyse(mutated)
        red = [c for c, ok, _, _ in res if not ok]
        if target not in red:
            failures.append("%s did not turn %r red (red=%s)" % (label, target, red))
            print("  [FAIL] %s -> %r stayed green (red=%s)" % (label, target, red))
        else:
            print("  [OK] %s -> red on %r%s"
                  % (label, target, "" if len(red) == 1 else " (+%d side)" % (len(red) - 1)))

    clean = [c for c, ok, _, _ in analyse(COMPLIANT) if not ok]
    print("  [%s] compliant fixture green%s"
          % ("OK" if not clean else "FAIL", "" if not clean else " (red=%s)" % clean))
    if clean:
        failures.append("compliant fixture turned red: %s" % clean)

    for idx, (pairs, target) in BREAKERS.items():
        run_arm("breaker %d" % idx, pairs, target)

    n_witness_bad = len(failures)
    for name, (pairs, target) in WITNESS_ARMS.items():
        run_arm("witness %s" % name, pairs, target)
    witness_bad = len(failures) > n_witness_bad
    return failures, witness_bad


def main():
    if "--selftest" in sys.argv:
        fails, witness_bad = selftest()
        if fails:
            print("SELFTEST FAIL (%d)" % len(fails))
            return 1
        # Marker read by scripts/scan/test_scan.py — keep the exact wording.
        print("SELFTEST=PASS (%d fixtures)" % (1 + len(BREAKERS)))
        print("RSS_PROBE_LOCK_SCOPE_SELFTEST=PASS (1 compliant + %d breakers + %d witnesses)"
              % (len(BREAKERS), len(WITNESS_ARMS)))
        print("WITNESS_ARM_ORDER=%s (%d arm(s): monitor identity, live decision, "
              "live reset, no unlocked write, snapshot copy)"
              % ("PASS" if not witness_bad else "FAIL", len(WITNESS_ARMS)))
        return 0

    root = None
    args = [a for a in sys.argv[1:]]
    if "--root" in args:
        root = args[args.index("--root") + 1]
    elif args:
        root = args[0]
    root = root or "."
    for rel in ENFORCED:
        if not os.path.isfile(os.path.join(root, rel)):
            print("FAIL %s not found under root %s" % (rel, root))
            return 1

    results = []
    for rel in ENFORCED:
        path = os.path.join(root, rel)
        src = open(path, encoding="utf-8").read()
        print("== enforced: %s" % rel)
        for crit, ok, detail, line in analyse(
            src, require_govern_reset=(rel == GOVERNANCE_OWNER)
        ):
            results.append((crit, ok, detail, line, rel))

    failed = [(c, d, l, r) for c, ok, d, l, r in results if not ok]
    for c, ok, d, l, r in results:
        if ok:
            print("  [OK] %s (%s)" % (c, r))
        else:
            print("  [FAIL] %s:%s %s — %s" % (os.path.basename(r), l, c, d))

    total = len(results)
    print("Summary: %d passed, %d failed" % (total - len(failed), len(failed)))
    print("Observation (same family, not fixed in this branch): 0")
    if failed:
        print("rss-probe lock scope gate: RED — the fix contract is violated")
        return 1
    print("GATE=GREEN rss_probe_lock_scope")
    return 0


if __name__ == "__main__":
    sys.exit(main())
