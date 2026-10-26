#!/usr/bin/env python3
"""[deep-audit-1016c FIX-A] Gate: persistence slices start at the run-scoped
UNPERSISTED block index, not at the per-turn start.

Two indices live side by side in `runAgentLoop` and must not be collapsed:

  * `turnStartBlockIndex` — captured at the top of EVERY iteration
    (`allToolBlocks.size` at that moment). It answers "what did THIS turn show?"
    and is what `rollbackTurnBlocksTo` needs (roll back only the failed
    attempt's partial blocks).

  * `loopState.unpersistedBlockStart` — run-scoped, advances ONLY when a persist
    actually landed. Five no-tool continuation branches (EOF-stub, length-wall
    prefill / reminder, verify nudge, residue refill) `continue` without
    persisting the text they already streamed; the next iteration re-captures
    `turnStartBlockIndex` at the new end of the append-only list, so any slice
    that starts there skips the continuation prefix FOREVER — the segment shows
    in the live bubble and vanishes on reload.

Rules enforced here:
  R1 every `buildTurnParts(loopState.allToolBlocks, X, toolInputMap)` call site
     passes `loopState.unpersistedBlockStart` (never `turnStartBlockIndex`);
  R2 rollback keeps the per-turn index (exactly two sites);
  R3 per-turn visibility keeps the per-turn index;
  R4 the no-tool terminal advances the unpersisted index inside its success
     guard (`if (persistedRowId != null) {`), and that advance is REACHABLE;
  R5 the tool-path terminal advances it inside its own dbId guard (reachable);
  R5b the no-tool terminal backfills dbMessageId (ISSUE-01/B);
  R6 clearing `allToolBlocks` resets the unpersisted index to 0 (reachable);
  R7 `AgentLoopState` declares the field as a plain backed `var` (no accessor);
  R8 every write to the index — under ANY receiver spelling — is `= 0` or
     `= loopState.allToolBlocks.size`;
  R9 every advance-to-size sits inside a success (null-id) guard and on a
     reachable path;
  R10 the dbId backfill is reachable (jointly satisfiable enclosing guards).

batch-5 hardening (six witnesses passed this gate unchanged):

  * 11-1 `parser-death` — a NEWLINE between `buildTurnParts(` and its
    arguments made the old line-based `SLICE_CALL` miss the site entirely, so
    `buildTurnParts(\n allToolBlocks, turnStartBlockIndex, …)` was invisible.
    Call sites are now located by shared `call_args()` over the whole file, so
    a call is a call however it wraps.
  * 11-2 / 11-4 `family-hole` — `if (1 == 2) <advance|reset>` kept the exact
    text of the guarded statement while making it impossible to execute. Every
    statement below is now checked for reachability (`path_feasible` +
    `guard_feasible`, constant folding with Kotlin precedence).
  * 11-3 `family-hole` — contradictory *stacked* guards
    (`if (persistedRowId != null) { … if (persistedRowId == null) backfill }`)
    were judged condition-by-condition, so each half looked fine. Guards are
    now intersected jointly (null-ness + integer ranges), which also catches
    `if (i >= 0) { if (i < 0) … }`.
  * 11-5 `parser-death` — R7 matched the field declaration inside a `//`
    comment, and a `var x: Int get() = 0; set(_) {}` accessor pair satisfies
    any "declares …" substring. R7 now reads the masked (comment-free) view and
    rejects custom accessors.
  * 11-6 `spelling` — `(loopState).unpersistedBlockStart = …` (or
    `loopState.apply { unpersistedBlockStart = … }`) dodged an assignment
    regex anchored on the literal `loopState.` prefix. Writes are now found by
    property name and their RECEIVER is resolved separately; an unresolvable
    receiver fail-closes.

All structure (comments, strings, braces, parentheses, constant folding,
reachability) comes from the shared `_kotlin_lexer` / `_kotlin_ast` /
`_kotlin_valueflow` primitives — this gate keeps no private tokenizer.

Exit 0 = PASS, 1 = FAIL (prints file:line for each violation).
`--selftest` builds violating + compliant fixtures and asserts the checker reds
every violating one on ITS OWN criterion and greens the compliant ones.

Registration into scripts/scan/scan.sh is the main session's job (this branch is
forbidden from touching scan.sh / test_scan.py).
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import KotlinLexError  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    enclosing_conditions,
    if_blocks,
    match_bracket,
    split_top,
    tail_expr,
)
from _kotlin_valueflow import (  # noqa: E402
    call_args,
    code_view,
    expr_end,
    path_feasible,
)

ENGINE_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/AgentLoopEngine.kt"
STATE_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/AgentLoopState.kt"

UNPERSISTED = "loopState.unpersistedBlockStart"
PER_TURN = "turnStartBlockIndex"
ALL_BLOCKS = "loopState.allToolBlocks"
ADVANCE_RHS = "loopState.allToolBlocks.size"
ADVANCE_TO_SIZE = "loopState.unpersistedBlockStart=" + ADVANCE_RHS
FIELD = "unpersistedBlockStart"
FIELD_DECL = re.compile(
    r"\bvar\s+unpersistedBlockStart\s*:\s*Int\s*=\s*0\b")
ACCESSOR_HEAD = re.compile(r"\s*(?:(?:private|internal|public|protected)\s+)?(get|set)\s*\(")

SLICE_FUN = "buildTurnParts"
ROLLBACK_FUN = "rollbackTurnBlocksTo"
VISIBILITY_RE = re.compile(r"\bturnVisibleBlocks\s*=")
VISIBILITY_SHAPE = re.compile(
    r"^loopState\.allToolBlocks\.drop\((?P<arg>.+)\)$")
WRITE_RE = re.compile(r"(?<![\w`])unpersistedBlockStart(?![\w`])\s*(\+=|-=|=)(?!=)")
BACKFILL = re.compile(r"copy\s*\(\s*dbMessageId\s*=\s*([^,)\n]*)")
IDX_GUARD = re.compile(r"^[A-Za-z_]\w*(?:>=0|>-1|!=-1)$")
NULL_GUARD = re.compile(r"^[A-Za-z_][\w.]*!=null$")
VAL_DECL = re.compile(r"(?m)^[ \t]*(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=\s*([^\n]*)")

# ── round-3 additions (dual-gate seams, witnesses #121 / #122 / #123) ─────
# R11 the tool terminal ROUTES between the two persistence verbs: a row that
#     already exists (the pre-tool preview insert) is refreshed, a turn without
#     one is inserted. Neither gate looked at the refresh call, so dropping it
#     left `assistantDbId` non-null while the row kept its pre-tool PENDING
#     snapshot — and R5 still saw a successful advance.
ROW_ROUTING_DECL = re.compile(r"\bval\s+assistantDbId\s*=")
UPDATE_ROW_FUN = "updatePersistedAssistantTurn"
INSERT_ROW_FUN = "persistAssistantTurn"

# R12 the tool-result persist must be handed the container the turn FILLED.
#     `persistToolResultMessage(emptyList(), …)` returns null inside the host
#     (`if (resultParts.isEmpty()) return null`), so the paired tool_result row
#     silently never lands while the assistant row does.
TOOL_RESULT_FUN = "persistToolResultMessage"
EMPTY_COLLECTION = re.compile(
    r"^(?:emptyList|emptySet|emptyArray|emptySequence|listOf|setOf|arrayListOf|mutableListOf|"
    r"mutableSetOf|LinkedList|ArrayList|HashSet|arrayOf)\s*(?:<[^<>]*>)?\s*\(\s*\)$")

# R13 the per-turn baseline is INITIALISED to the list length at this turn's
#     start. R2/R3 only audited how the index is USED; seeding it with 0 (or a
#     stale value) makes rollback wipe every earlier turn's finished blocks and
#     misplaces this turn's own text block.
PER_TURN_DECL = re.compile(
    r"(?m)^[ \t]*(?:val|var)\s+turnStartBlockIndex\s*(?::[^=\n]+)?=(?!=)")


def line_of(src: str, idx: int) -> int:
    return src.count("\n", 0, idx) + 1


def squash(expr: str) -> str:
    """Whitespace-free, outer-paren-stripped form of an expression.

    `(loopState)` -> `loopState`, `loopState\\n .allToolBlocks` ->
    `loopState.allToolBlocks`. Structural equivalence is what the rules care
    about; the source spelling is not.
    """
    e = re.sub(r"\s+", "", expr or "")
    while (len(e) >= 2 and e[0] == "(" and e[-1] == ")"
           and match_bracket(e, 0) == len(e) - 1):
        e = e[1:-1]
    return e


def block_span(text: str, pos: int):
    """(start, end) of the innermost `{ … }` block containing `pos`."""
    stack = []
    for i, ch in enumerate(text):
        if ch == "{":
            stack.append(i)
        elif ch == "}":
            if not stack:
                continue
            start = stack.pop()
            if start < pos <= i:
                return start, i + 1
    return 0, len(text)


def enclosing_open_braces(text: str, pos: int):
    """`{` indices of every block containing `pos`, innermost first."""
    stack, out = [], []
    for i, ch in enumerate(text):
        if ch == "{":
            stack.append(i)
        elif ch == "}":
            if stack:
                start = stack.pop()
                if start < pos < i:
                    out.append(start)
    out.sort(reverse=True)
    return out


def call_sites(code: str, name: str):
    """[(pos, [squashed argument text…])] for every `name( … )` call.

    Args come from the shared `call_args()` (bracket-matched, comma-split at
    depth 0), so a call that wraps across lines is still one site with the same
    argument list — the exact shape a line-based regex missed.
    """
    out = []
    for m in re.finditer(r"\b" + re.escape(name) + r"\s*\(", code):
        out.append((m.start(), [squash(code[a:b]) for a, b in call_args(code, m.end() - 1)]))
    return out


def local_domains(code: str):
    """Integer domains implied by a local's initializer.

    `indexOfLast` / `indexOf` / `lastIndexOf` return -1 when absent, so the
    variable is >= -1; `.size` is >= 0. Feeds the shared `guard_feasible`.
    """
    d = {}
    for m in VAL_DECL.finditer(code):
        name, expr = m.group(1), m.group(2)
        if "indexOfLast" in expr or "indexOf" in expr or "lastIndexOf" in expr:
            d[name] = (-1, None)
        elif ".size" in expr:
            d[name] = (0, None)
    return d


# ── joint (path-sensitive) guard feasibility ──────────────────────────────
NEG_OP = {"==": "!=", "!=": "==", "<": ">=", ">": "<=", "<=": ">", ">=": "<"}
CMP = re.compile(r"^([A-Za-z_]\w*)(==|!=|<=|>=|<|>)(-?\d+)$")
NULL_L = re.compile(r"^([A-Za-z_]\w*)(==|!=)null$")
NULL_R = re.compile(r"^null(==|!=)([A-Za-z_]\w*)$")


def _apply_int(name, op, num, lo, hi):
    cur_lo, cur_hi = lo.get(name), hi.get(name)
    if op == ">=":
        nlo, nhi = num, cur_hi
    elif op == ">":
        nlo, nhi = num + 1, cur_hi
    elif op == "<=":
        nlo, nhi = cur_lo, num
    elif op == "<":
        nlo, nhi = cur_lo, num - 1
    elif op == "==":
        nlo, nhi = num, num
    else:  # !=
        return not (cur_lo is not None and cur_hi is not None and cur_lo == cur_hi == num)
    if nlo is not None:
        lo[name] = nlo
    if nhi is not None:
        hi[name] = nhi
    return not (name in lo and name in hi and lo[name] > hi[name])


def _strip_neg(atom):
    neg = False
    while atom.startswith("!"):
        neg = not neg
        atom = atom[1:].lstrip()
    return atom, neg


def conds_feasible(conds, domains=None):
    """Can ALL of `conds` hold at the same time?

    Conditions are intersected JOINTLY — that is the only way to see the
    `if (rowId != null) { … if (rowId == null) … }` family, where each guard is
    individually plausible and only the stack is impossible. Unknown shapes are
    treated as feasible (a gate must not red on something it cannot read).
    """
    lo = {k: v[0] for k, v in (domains or {}).items() if v[0] is not None}
    hi = {k: v[1] for k, v in (domains or {}).items() if v[1] is not None}
    nonnull = {}
    for cond in conds:
        if "||" in cond:
            continue  # disjunction: cannot prove infeasibility from it
        for a0, a1 in split_top(cond, ("&&",)):
            atom, neg = _strip_neg((cond[a0:a1] or "").strip())
            a = squash(atom)
            if not a:
                continue
            m = CMP.match(a)
            if m:
                name, op, num = m.group(1), m.group(2), int(m.group(3))
                if neg:
                    op = NEG_OP[op]
                if not _apply_int(name, op, num, lo, hi):
                    return False
                continue
            m = NULL_L.match(a)
            if m:
                name, op = m.group(1), m.group(2)
            else:
                m = NULL_R.match(a)
                if not m:
                    continue
                op, name = m.group(1), m.group(2)
            if neg:
                op = NEG_OP[op]
            want = (op == "!=")
            if name in nonnull and nonnull[name] != want:
                return False
            nonnull[name] = want
    return True


def statement_reachable(code: str, pos: int, domains=None) -> bool:
    """`pos` can execute: no enclosing guard is an impossible condition."""
    return path_feasible(code, pos, domains)


# ── the rules ─────────────────────────────────────────────────────────────


def check_source(engine: str, state: str, label: str):
    """`engine` / `state` are RAW sources; masking happens here (fail-closed)."""
    try:
        code = code_view(engine)
        state_code = code_view(state)
    except KotlinLexError as exc:
        return ["%s:1: unparseable Kotlin source (%s) — fail-closed" % (label, exc)]

    bad = []
    domains = local_domains(code)

    # ── R1: every persistence slice reads the run-scoped index ────────────
    sites = call_sites(code, SLICE_FUN)
    if not sites:
        bad.append("%s:1: no buildTurnParts(…) persistence slice found — did the call "
                   "shape change?" % label)
    for pos, args in sites:
        line = line_of(engine, pos)
        if len(args) < 3:
            bad.append("%s:%d: buildTurnParts(…) call has %d argument(s) — cannot audit the "
                       "slice start (fail-closed)" % (label, line, len(args)))
            continue
        if args[0] != ALL_BLOCKS:
            bad.append("%s:%d: buildTurnParts(…) slices '%s', not 'loopState.allToolBlocks' — "
                       "cannot audit the slice start (fail-closed)" % (label, line, args[0]))
            continue
        if args[1] != UNPERSISTED:
            bad.append("%s:%d: persistence slice starts at '%s' — the per-turn start skips blocks a "
                       "continuation branch already streamed (they never reach the DB); it must be '%s'"
                       % (label, line, args[1], UNPERSISTED))

    # ── R2: rollback keeps the per-turn index ─────────────────────────────
    rollbacks = call_sites(code, ROLLBACK_FUN)
    if len(rollbacks) != 2:
        bad.append("%s:1: expected exactly 2 rollbackTurnBlocksTo(loopState.allToolBlocks, …) sites, "
                   "found %d" % (label, len(rollbacks)))
    for pos, args in rollbacks:
        line = line_of(engine, pos)
        if len(args) < 2:
            bad.append("%s:%d: rollbackTurnBlocksTo(…) call has %d argument(s) — cannot audit "
                       "(fail-closed)" % (label, line, len(args)))
            continue
        if args[1] != PER_TURN:
            bad.append("%s:%d: rollback uses '%s' — rollback must remove only the failed attempt's "
                       "blocks (%s), never an earlier turn's unpersisted prefix"
                       % (label, line, args[1], PER_TURN))

    # ── R3: per-turn visibility keeps the per-turn index ──────────────────
    vis = list(VISIBILITY_RE.finditer(code))
    if not vis:
        bad.append("%s:1: turnVisibleBlocks = allToolBlocks.drop(…) not found — the per-turn "
                   "visibility slice disappeared" % label)
    for m in vis:
        line = line_of(engine, m.start())
        rhs = squash(code[m.end():expr_end(code, m.end())])
        shape = VISIBILITY_SHAPE.match(rhs)
        if not shape:
            bad.append("%s:%d: turnVisibleBlocks is computed as '%s' — expected "
                       "loopState.allToolBlocks.drop(%s)" % (label, line, rhs, PER_TURN))
            continue
        if squash(shape.group("arg")) != PER_TURN:
            bad.append("%s:%d: per-turn visibility uses '%s' — with the run-scoped index the 'turn "
                       "showed nothing' test can never fire again"
                       % (label, line, squash(shape.group("arg"))))

    # ── R7: the state field is a plain backed property ────────────────────
    decl = FIELD_DECL.search(state_code)
    if decl is None:
        bad.append("%s:1: AgentLoopState does not declare '%s: Int = 0' as CODE (a commented-out "
                   "declaration, a renamed field, or a property without a backing initializer all "
                   "read the same here)" % (state.split("/")[-1], FIELD))
    else:
        after = state_code[decl.end():decl.end() + 200]
        acc = ACCESSOR_HEAD.match(after.split("\n\n")[0])
        if acc:
            bad.append("%s:%d: AgentLoopState declares '%s' with a custom '%s' accessor — a getter/"
                       "setter pair can pin the value (or swallow the write) while the declaration "
                       "text stays identical, so every advance above becomes a no-op"
                       % (state.split("/")[-1], line_of(state, decl.start()), FIELD, acc.group(1)))

    # ── R8 / R9: every write to the index, under any receiver spelling ────
    for m in WRITE_RE.finditer(code):
        pos = m.start()
        line = line_of(engine, pos)
        prev = code[:pos].rstrip()
        if re.search(r"\b(?:var|val)\s*$", prev):
            continue  # a declaration, not a write
        receiver = _receiver_of(code, pos)
        if receiver is None:
            bad.append("%s:%d: cannot resolve the receiver of this `unpersistedBlockStart` write "
                       "(fail-closed) — if the holder was renamed, re-point the rule" % (label, line))
            continue
        if receiver != "loopState":
            continue  # another holder's property; not this gate's range
        op = m.group(1)
        rhs = squash(code[m.end():expr_end(code, m.end())])
        if op != "=":
            bad.append("%s:%d: the unpersisted index is adjusted with '%s' — it is set to 0 "
                       "(reset) or to loopState.allToolBlocks.size (advance), never arithmetically"
                       % (label, line, op))
            continue
        if rhs not in ("0", ADVANCE_RHS):
            bad.append("%s:%d: the unpersisted index is set to '%s' — only 0 (reset) or "
                       "loopState.allToolBlocks.size (advance) may feed it; anything derived from "
                       "the per-turn start re-collapses the two indices and cuts the blocks a "
                       "continuation branch already streamed out of the next persist"
                       % (label, line, rhs))
            continue
        if rhs == ADVANCE_RHS:
            if not statement_reachable(code, pos, domains):
                bad.append("%s:%d: the advance sits on an UNREACHABLE path (a constant-false or "
                           "contradictory guard) — the index never moves, so the next persist "
                           "re-sends blocks this row already carries" % (label, line))
                continue
            encl = [c for _k, c in enclosing_conditions(code, pos)]
            if not any(NULL_GUARD.match(squash(c)) for c in encl):
                bad.append("%s:%d: the unpersisted index advances OUTSIDE a success guard "
                           "(`if (persistedRowId != null)` / `if (assistantDbId != null)`) — when "
                           "the insert returns null the range is still marked durable and a retry "
                           "can never re-cover it" % (label, line))

    # ── R4 / R5 / R5b: the two terminals ──────────────────────────────────
    guard, at = _guard_block(code, domains, "persistedRowId")
    if guard is None:
        bad.append("%s:1: `if (persistedRowId != null) {` success guard not found at the no-tool "
                   "terminal" % label)
    else:
        g_start, g_end = guard
        advance = [p for p, rhs in _writes_in(code, g_start, g_end) if rhs == ADVANCE_RHS]
        if not advance:
            bad.append("%s:%d: the no-tool terminal does not advance the unpersisted index inside "
                       "its success guard — the next persist would re-send blocks this row already "
                       "carries" % (label, line_of(engine, at)))
        elif not any(statement_reachable(code, p, domains) for p in advance):
            bad.append("%s:%d: the no-tool terminal's advance sits behind a guard that can never "
                       "hold (constant-false / contradictory condition) — the text is there, the "
                       "index never moves, and the next persist re-sends the same blocks"
                       % (label, line_of(engine, at)))
        backs = [m for m in BACKFILL.finditer(code) if g_start <= m.start() < g_end]
        if not backs:
            bad.append("%s:%d: the no-tool terminal does not backfill dbMessageId inside its success "
                       "guard (ISSUE-01/B) — the tool path does" % (label, line_of(engine, at)))

    tool, tool_at = _guard_block(code, domains, "assistantDbId")
    if tool is None:
        bad.append("%s:1: `if (assistantDbId != null) {` guard not found at the tool terminal" % label)
    else:
        t_start, t_end = tool
        advance = [p for p, rhs in _writes_in(code, t_start, t_end) if rhs == ADVANCE_RHS]
        if not advance:
            bad.append("%s:%d: the tool-path terminal does not advance the unpersisted index inside "
                       "its dbId guard" % (label, line_of(engine, tool_at)))
        elif not any(statement_reachable(code, p, domains) for p in advance):
            bad.append("%s:%d: the tool-path terminal's advance sits behind a guard that can never "
                       "hold — the index never moves past blocks that are already durable"
                       % (label, line_of(engine, tool_at)))

    # ── R6: clearing the block list resets the index (reachable) ──────────
    clears = [m.start() for m in re.finditer(
        r"loopState\s*\.\s*allToolBlocks\s*\.\s*clear\s*\(\s*\)", code)]
    if not clears:
        bad.append("%s:1: loopState.allToolBlocks.clear() not found (queue-interrupt reset site)"
                   % label)
    for pos in clears:
        b_start, b_end = block_span(code, pos)
        reset = [p for p, rhs in _writes_in(code, pos, b_end) if rhs == "0"]
        if not reset:
            bad.append("%s:%d: clear site does not reset the unpersisted index to 0 in its own "
                       "block — the two indices drift apart on a queued-message interrupt"
                       % (label, line_of(engine, pos)))
        elif not any(statement_reachable(code, p, domains) for p in reset):
            bad.append("%s:%d: the clear site's reset sits behind a guard that can never hold — the "
                       "list is emptied while the index keeps pointing into the previous bubble"
                       % (label, line_of(engine, pos)))

    # ── R10: the dbId backfill must be REACHABLE (jointly) ────────────────
    for m in BACKFILL.finditer(code):
        line = line_of(engine, m.start())
        val = squash(m.group(1))
        bad_value = (not re.fullmatch(r"[A-Za-z_]\w*", val)) or val == "null"
        if bad_value:
            bad.append("%s:%d: dbMessageId is backfilled from '%s' — it must be the row id "
                       "returned by the persist call" % (label, line, val))
        encl = [c for _k, c in enclosing_conditions(code, m.start())]
        if not encl:
            bad.append("%s:%d: the dbMessageId backfill is unguarded — indexOfLast returns -1 "
                       "when no assistant row is pending" % (label, line))
            continue
        if not conds_feasible(encl, domains):
            bad.append("%s:%d: the dbMessageId backfill sits behind guards that cannot hold "
                       "TOGETHER (%s) — each one reads fine alone, so the backfill never runs and "
                       "compaction loses its DB anchor"
                       % (label, line, " && ".join(squash(c) for c in encl)))
            continue
        if not any(IDX_GUARD.match(squash(c)) for c in encl):
            bad.append("%s:%d: the dbMessageId backfill is not guarded by an index-presence check "
                       "(`if (<index> >= 0)`) — a -1 index would rewrite the last message"
                       % (label, line))
        # The written value must be the id the enclosing success guard tests:
        # `if (persistedRowId != null) { … copy(dbMessageId = persistedRowId) }`.
        # A different (however live-looking) value stamps the wrong anchor.
        guard_ids = set()
        for c in encl:
            mm = re.fullmatch(r"([A-Za-z_]\w*)!=null", squash(c))
            if mm:
                guard_ids.add(mm.group(1))
        if guard_ids and not bad_value and val not in guard_ids:
            bad.append("%s:%d: dbMessageId is backfilled from '%s', but the enclosing success guard "
                       "tests '%s' — the row that was just written gets no DB anchor, so compaction "
                       "falls back to a degraded walk"
                       % (label, line, val, "/".join(sorted(guard_ids))))

    # ── R11: the tool terminal ROUTES row-refresh vs row-insert ────────────
    bad.extend(_row_routing(code, engine, label))

    # ── R12: the tool-result persist carries what the turn FILLED ──────────
    bad.extend(_tool_result_persist(code, engine, label))

    # ── R13: the per-turn baseline is seeded with the list length ──────────
    bad.extend(_per_turn_baseline(code, engine, label))

    return bad


def _branch_body(code: str, start: int, end: int):
    """Text of the branch starting at `start`, braces stripped."""
    i = start
    while i < end and code[i] in " \t\r\n":
        i += 1
    if i < end and code[i] == "{":
        close = match_bracket(code, i)
        if close > 0:
            return code[i + 1:close]
    return code[i:end]


def _row_routing(code: str, engine: str, label: str):
    """R11 — `assistantDbId` must refresh the early row, not just return its id."""
    m = ROW_ROUTING_DECL.search(code)
    if m is None:
        return ["%s:1: `val assistantDbId = …` row routing not found — the tool terminal must route "
                "between refreshing the row written before the tools ran and inserting a new one "
                "(fail-closed)" % label]
    rhs_end = expr_end(code, m.end())
    rhs = code[m.end():rhs_end]
    blocks = if_blocks(rhs)
    if not blocks:
        return ["%s:%d: the assistant turn is persisted unconditionally — a turn whose preview row "
                "already exists (`earlyAssistantDbId != null`, written before the tools ran) gets a "
                "SECOND row instead of a refresh, so the first one keeps its pre-tool PENDING "
                "snapshot while the cursor moves past those blocks"
                % (label, line_of(engine, m.start()))]
    cond, t_start, t_end, e_start, e_end = blocks[0]
    cm = re.fullmatch(r"([A-Za-z_]\w*)!=null", squash(cond))
    if cm is None:
        return ["%s:%d: the assistant-row routing condition `%s` is not `if (<earlyId> != null)` — "
                "cannot audit which row is refreshed (fail-closed)"
                % (label, line_of(engine, m.start()), " ".join(cond.split()))]
    early = cm.group(1)
    then_body = _branch_body(rhs, t_start, t_end)
    refresh = call_sites(then_body, UPDATE_ROW_FUN)
    out = []
    if not refresh:
        out.append("%s:%d: the `if (%s != null)` branch returns the early row without calling %s(…) — "
                   "the row keeps the pre-tool PENDING content/status while the run-scoped cursor "
                   "advances past those blocks, so every finished tool card is lost on reload"
                   % (label, line_of(engine, m.start() + t_start), early, UPDATE_ROW_FUN))
    else:
        for _pos, args in refresh:
            if len(args) < 2:
                out.append("%s:%d: %s(…) has %d argument(s) — cannot audit which row it refreshes "
                           "(fail-closed)"
                           % (label, line_of(engine, m.start() + t_start), UPDATE_ROW_FUN, len(args)))
                continue
            if args[0] != early:
                out.append("%s:%d: %s(…) refreshes '%s', not the early row '%s' the branch belongs to"
                           % (label, line_of(engine, m.start() + t_start), UPDATE_ROW_FUN,
                              args[0], early))
            if not re.fullmatch(r"[A-Za-z_]\w*", args[1]):
                out.append("%s:%d: %s(…) is handed '%s' as its slice — must be the value built from "
                           "loopState.allToolBlocks/unpersistedBlockStart, not an expression the "
                           "gate cannot follow (fail-closed)"
                           % (label, line_of(engine, m.start() + t_start), UPDATE_ROW_FUN, args[1]))
        value = squash(tail_expr(then_body))
        if value != early:
            out.append("%s:%d: the early branch evaluates to '%s' — it must evaluate to '%s', the id "
                       "it just refreshed (anything else makes the dbMessageId backfill anchor the "
                       "wrong row)" % (label, line_of(engine, m.start() + t_start), value, early))
    if e_start is None:
        out.append("%s:%d: the assistant-row routing has no `else` branch — a turn without an early "
                   "row would never be inserted" % (label, line_of(engine, m.start())))
    else:
        else_body = _branch_body(rhs, e_start, e_end)
        if not call_sites(else_body, INSERT_ROW_FUN):
            out.append("%s:%d: the `else` branch does not call %s(…) — the turn's row is never "
                       "inserted" % (label, line_of(engine, m.start() + e_start), INSERT_ROW_FUN))
    return out


def _filled_container(code: str, name: str, pos: int):
    """Last `+=' / `.add(` / `.addAll(` on `name` before `pos`, or None."""
    fill = None
    for m in re.finditer(r"(?<![\w`])" + re.escape(name) + r"\s*(?:\+=|\.\s*(?:add|addAll)\s*\()",
                         code):
        if m.start() < pos:
            fill = m.start()
    return fill


def _tool_result_persist(code: str, engine: str, label: str):
    """R12 — the persisted tool_result row must carry this turn's results."""
    sites = call_sites(code, TOOL_RESULT_FUN)
    if not sites:
        return ["%s:1: %s(…) call not found — the turn's tool_result row is never persisted, so a "
                "reload shows tool_use blocks with no paired result (fail-closed)"
                % (label, TOOL_RESULT_FUN)]
    out = []
    for pos, args in sites:
        line = line_of(engine, pos)
        if not args:
            out.append("%s:%d: %s(…) takes no argument — cannot audit what it persists (fail-closed)"
                       % (label, line, TOOL_RESULT_FUN))
            continue
        value = args[0]
        if EMPTY_COLLECTION.match(value):
            out.append("%s:%d: the tool-result persist is handed '%s' — the host returns null for an "
                       "empty list, so the paired tool_result row never lands while the assistant row "
                       "does; a reload then shows orphan tool_use blocks and the model loses every "
                       "real tool output" % (label, line, value))
            continue
        if not re.fullmatch(r"[A-Za-z_]\w*", value):
            out.append("%s:%d: the tool-result persist is handed '%s' — that is not the container the "
                       "turn fills, so what lands cannot be audited (fail-closed)"
                       % (label, line, value))
            continue
        fill = _filled_container(code, value, pos)
        if fill is None:
            out.append("%s:%d: '%s' reaches %s(…) without ever being filled (`add` / `+=`) — an "
                       "unbuilt or empty container persists nothing"
                       % (label, line, value, TOOL_RESULT_FUN))
            continue
        cleared = [m.start() for m in re.finditer(
            r"(?<![\w`])" + re.escape(value) +
            r"\s*(?:\.\s*clear\s*\(\s*\)|=\s*(?:emptyList|emptySet|listOf|setOf|mutableListOf)"
            r"\s*(?:<[^<>]*>)?\s*\(\s*\))", code)]
        if any(fill < c < pos for c in cleared):
            out.append("%s:%d: '%s' is emptied after it was filled and before it is persisted — what "
                       "lands is the empty tail, not this turn's tool results"
                       % (label, line, value))
    return out


def _per_turn_baseline(code: str, engine: str, label: str):
    """R13 — `turnStartBlockIndex` starts at the list length, not at 0."""
    decls = list(PER_TURN_DECL.finditer(code))
    if not decls:
        return ["%s:1: no `val turnStartBlockIndex = …` declaration found — the per-turn baseline "
                "that rollback and turn visibility slice on is gone (fail-closed)" % label]
    out = []
    for m in decls:
        rhs = squash(code[m.end():expr_end(code, m.end())])
        if rhs != ADVANCE_RHS:
            out.append("%s:%d: the per-turn baseline is initialised to '%s' — it must be the block "
                       "count AT THIS TURN's start ('%s'); 0 (or any stale value) makes rollback "
                       "erase earlier turns' finished blocks and misplaces this turn's own text "
                       "block" % (label, line_of(engine, m.start()), rhs, ADVANCE_RHS))
    return out


def _receiver_of(code: str, pos: int):
    """Receiver of the property write at `pos`, squashed; 'loopState' or other.

    Returns None when the receiver cannot be resolved (fail-closed). Handles
    `loopState.x =`, `(loopState).x =`, `this.loopState.x =`, and the implicit
    receiver inside `loopState.apply { x = … }` / `with(loopState) { x = … }`.
    """
    prev = code[:pos].rstrip()
    if prev.endswith("."):
        # Only the receiver CHAIN immediately before the dot matters. Squashing
        # the whole file prefix used to glue the previous line onto it
        # (`…allToolBlocks.size` + `loopState` -> `sizeloopState`), so a
        # multi-statement fixture silently escaped R8 entirely.
        head_text = prev[:-1].rstrip()
        if head_text.endswith(")"):
            # `(loopState).x` / `((loopState)).x` — the parenthesised group IS
            # the receiver, so unwrap it before reading the tail chain.
            depth = 0
            for i in range(len(head_text) - 1, -1, -1):
                ch = head_text[i]
                if ch == ")":
                    depth += 1
                elif ch == "(":
                    depth -= 1
                    if depth == 0:
                        inner = squash(head_text[i + 1:-1])
                        if inner == "loopState" or inner.endswith(".loopState"):
                            return "loopState"
                        head_text = head_text[:i].rstrip()
                        break
        chain_m = re.search(r"([A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)*)\s*$", head_text)
        head = squash(chain_m.group(1)) if chain_m else ""
        if head == "loopState" or head.endswith(".loopState"):
            return "loopState"
        term = re.search(r"([A-Za-z_]\w*)$", head)
        return term.group(1) if term else None
    for brace in enclosing_open_braces(code, pos):
        head = code[max(0, brace - 160):brace].rstrip()
        if re.search(r"(?<![\w.])loopState\s*\.\s*(?:apply|run|also|let)\s*$", head) or \
                re.search(r"(?<![\w.])with\s*\(\s*loopState\s*\)\s*$", head):
            return "loopState"
    return None


def _writes_in(code: str, start: int, end: int):
    """[(pos, squashed rhs)] for property writes inside [start, end)."""
    out = []
    for m in WRITE_RE.finditer(code, start, end):
        prev = code[:m.start()].rstrip()
        if re.search(r"\b(?:var|val)\s*$", prev):
            continue
        if _receiver_of(code, m.start()) != "loopState":
            continue
        out.append((m.start(), squash(code[m.end():expr_end(code, m.end())])))
    return out


def _guard_block(code: str, domains, name: str):
    """The (start, end) of `if (<name> != null) { … }` at the terminal, or None."""
    for m in re.finditer(r"\bif\s*\(\s*" + re.escape(name) + r"\s*!=\s*null\s*\)\s*\{", code):
        open_i = code.index("{", m.end() - 1)
        close = match_bracket(code, open_i)
        if close < 0:
            continue
        if not statement_reachable(code, m.start(), domains):
            continue
        return (open_i + 1, close), m.start()
    return None


def run(repo: str) -> int:
    engine_path = os.path.join(repo, ENGINE_REL)
    state_path = os.path.join(repo, STATE_REL)
    for p in (engine_path, state_path):
        if not os.path.exists(p):
            print("FAIL %s: not found (run from the repo root)" % p)
            return 1
    engine = open(engine_path, encoding="utf-8", errors="replace").read()
    state = open(state_path, encoding="utf-8", errors="replace").read()
    bad = check_source(engine, state, ENGINE_REL)
    if bad:
        for b in bad:
            print("FAIL " + b)
        return 1
    print("PASS loop_persist_slice_check (persistence slices start at "
          "loopState.unpersistedBlockStart; rollback + per-turn visibility keep turnStartBlockIndex; "
          "advance only inside reachable success guards; clear resets to 0; state field is a plain var)")
    return 0


# ── fixtures for --selftest ────────────────────────────────────────────────

GOOD_ENGINE = '''
                val turnStartBlockIndex = loopState.allToolBlocks.size
                val turnParts = host.buildTurnParts(loopState.allToolBlocks, loopState.unpersistedBlockStart, toolInputMap)
                val persistedRowId = host.persistAssistantTurn(turnParts)
                if (persistedRowId != null) {
                    loopState.unpersistedBlockStart = loopState.allToolBlocks.size
                    val lastAssistantIdx = host.agentHistory.indexOfLast { it.dbMessageId == null }
                    if (lastAssistantIdx >= 0) {
                        host.agentHistory[lastAssistantIdx] = host.agentHistory[lastAssistantIdx].copy(dbMessageId = persistedRowId)
                    }
                }
            val livePreviewParts = host.buildTurnParts(loopState.allToolBlocks, loopState.unpersistedBlockStart, toolInputMap)
            val turnParts2 = host.buildTurnParts(loopState.allToolBlocks, loopState.unpersistedBlockStart, toolInputMap)
                val earlyId = earlyAssistantDbId
                val assistantDbId = if (earlyId != null) {
                    host.updatePersistedAssistantTurn(earlyId, turnParts, blockMeta)
                    earlyId
                } else {
                    host.persistAssistantTurn(turnParts2)
                }
                if (assistantDbId != null) {
                    loopState.unpersistedBlockStart = loopState.allToolBlocks.size
                }
                    resultParts.add(AgentContentPart.ToolResult(id = id, name = name))
                    resultParts += pendingResults
                    loopState.allToolBlocks.clear()
                    loopState.unpersistedBlockStart = 0
                val toolResultDbId = host.persistToolResultMessage(resultParts, transcriptRedactions)
                rollbackTurnBlocksTo(loopState.allToolBlocks, turnStartBlockIndex)
                rollbackTurnBlocksTo(loopState.allToolBlocks, turnStartBlockIndex)
                val turnVisibleBlocks = loopState.allToolBlocks.drop(turnStartBlockIndex)
'''

GOOD_STATE = "    var unpersistedBlockStart: Int = 0\n"

# A correctly wrapped call / a `with(loopState)` reset are legal spellings: the
# rules must anchor on structure, not on a single-line style.
GOOD_MULTILINE_SLICE = GOOD_ENGINE.replace(
    "                val turnParts = host.buildTurnParts(loopState.allToolBlocks, loopState.unpersistedBlockStart, toolInputMap)\n",
    "                val turnParts = host.buildTurnParts(\n"
    "                    loopState.allToolBlocks,\n"
    "                    loopState.unpersistedBlockStart,\n"
    "                    toolInputMap,\n"
    "                )\n",
)
GOOD_WITH_SCOPE = GOOD_ENGINE.replace(
    "                    loopState.unpersistedBlockStart = 0\n",
    "                    with(loopState) { unpersistedBlockStart = 0 }\n",
)

# ── deep-audit-1016c fixtures (R1 / R4 / R5 / R2 / R6 / R5b / R7) ─────────
BAD_PER_TURN_SLICE = GOOD_ENGINE.replace(
    "val turnParts = host.buildTurnParts(loopState.allToolBlocks, loopState.unpersistedBlockStart,",
    "val turnParts = host.buildTurnParts(loopState.allToolBlocks, turnStartBlockIndex,",
)

BAD_NO_ADVANCE = GOOD_ENGINE.replace(
    "                if (assistantDbId != null) {\n"
    "                    loopState.unpersistedBlockStart = loopState.allToolBlocks.size\n"
    "                }\n",
    "                if (assistantDbId != null) {\n                }\n",
)

BAD_ROLLBACK_SWITCHED = GOOD_ENGINE.replace(
    "rollbackTurnBlocksTo(loopState.allToolBlocks, turnStartBlockIndex)\n                rollbackTurnBlocksTo(loopState.allToolBlocks, turnStartBlockIndex)",
    "rollbackTurnBlocksTo(loopState.allToolBlocks, loopState.unpersistedBlockStart)\n"
    "rollbackTurnBlocksTo(loopState.allToolBlocks, loopState.unpersistedBlockStart)",
)

BAD_CLEAR_NO_RESET = GOOD_ENGINE.replace(
    "                    loopState.allToolBlocks.clear()\n                    loopState.unpersistedBlockStart = 0\n",
    "                    loopState.allToolBlocks.clear()\n",
)

BAD_NO_BACKFILL = GOOD_ENGINE.replace(
    "                    if (lastAssistantIdx >= 0) {\n"
    "                        host.agentHistory[lastAssistantIdx] = host.agentHistory[lastAssistantIdx].copy(dbMessageId = persistedRowId)\n"
    "                    }\n",
    "",
)

BAD_STATE = "    var somethingElse: Int = 0\n"

# ── wide-1024 witnesses (each one passed this gate unchanged) ─────────────

BAD_TAINTED_ADVANCE = GOOD_ENGINE.replace(
    "                val turnParts = host.buildTurnParts(loopState.allToolBlocks, loopState.unpersistedBlockStart, toolInputMap)\n",
    "                loopState.unpersistedBlockStart = turnStartBlockIndex\n"
    "                val turnParts = host.buildTurnParts(loopState.allToolBlocks, loopState.unpersistedBlockStart, toolInputMap)\n",
)

BAD_HOISTED_ADVANCE = GOOD_ENGINE.replace(
    """                if (persistedRowId != null) {
                    loopState.unpersistedBlockStart = loopState.allToolBlocks.size""",
    """                loopState.unpersistedBlockStart = loopState.allToolBlocks.size
                if (persistedRowId != null) {
                    loopState.unpersistedBlockStart = loopState.allToolBlocks.size""",
)

BAD_UNREACHABLE_BACKFILL = GOOD_ENGINE.replace(
    "                    if (lastAssistantIdx >= 0) {",
    "                    if (lastAssistantIdx < -1) {",
)

BAD_VISIBILITY_SWITCHED = GOOD_ENGINE.replace(
    "val turnVisibleBlocks = loopState.allToolBlocks.drop(turnStartBlockIndex)",
    "val turnVisibleBlocks = loopState.allToolBlocks.drop(loopState.unpersistedBlockStart)",
)

# ── batch-5 witnesses (11-1 … 11-6) ──────────────────────────────────────

# 11-1 (parser-death): the SAME tainted argument, wrapped across lines so the
# old line-based regex saw no call at all.
BAD_MULTILINE_SLICE = GOOD_ENGINE.replace(
    "                val turnParts = host.buildTurnParts(loopState.allToolBlocks, loopState.unpersistedBlockStart, toolInputMap)\n",
    "                val turnParts = host.buildTurnParts(\n"
    "                    loopState.allToolBlocks, turnStartBlockIndex, toolInputMap)\n",
)

# 11-2 (family-hole): the advance keeps its exact text inside the success guard
# and is gated on a constant that never holds.
BAD_DEAD_ADVANCE = GOOD_ENGINE.replace(
    "                if (persistedRowId != null) {\n"
    "                    loopState.unpersistedBlockStart = loopState.allToolBlocks.size",
    "                if (persistedRowId != null) {\n"
    "                    if (1 == 2) loopState.unpersistedBlockStart = loopState.allToolBlocks.size",
)

# 11-3 (family-hole): a second, CONTRADICTORY guard stacked inside the first.
BAD_CONTRADICTORY_BACKFILL = GOOD_ENGINE.replace(
    "                    if (lastAssistantIdx >= 0) {\n"
    "                        host.agentHistory[lastAssistantIdx]",
    "                    if (lastAssistantIdx >= 0) {\n"
    "                        if (persistedRowId == null) {\n"
    "                        host.agentHistory[lastAssistantIdx]",
)
BAD_CONTRADICTORY_BACKFILL = BAD_CONTRADICTORY_BACKFILL.replace(
    "                        host.agentHistory[lastAssistantIdx] = host.agentHistory[lastAssistantIdx].copy(dbMessageId = persistedRowId)\n"
    "                    }\n",
    "                        host.agentHistory[lastAssistantIdx] = host.agentHistory[lastAssistantIdx].copy(dbMessageId = persistedRowId)\n"
    "                        }\n"
    "                    }\n",
)

# 11-3b (family-hole): the same contradiction spelled with integer ranges.
BAD_RANGE_CONTRADICTION = GOOD_ENGINE.replace(
    "                    if (lastAssistantIdx >= 0) {\n"
    "                        host.agentHistory[lastAssistantIdx]",
    "                    if (lastAssistantIdx >= 0) {\n"
    "                        if (lastAssistantIdx < 0) {\n"
    "                        host.agentHistory[lastAssistantIdx]",
)
BAD_RANGE_CONTRADICTION = BAD_RANGE_CONTRADICTION.replace(
    "                        host.agentHistory[lastAssistantIdx] = host.agentHistory[lastAssistantIdx].copy(dbMessageId = persistedRowId)\n"
    "                    }\n",
    "                        host.agentHistory[lastAssistantIdx] = host.agentHistory[lastAssistantIdx].copy(dbMessageId = persistedRowId)\n"
    "                        }\n"
    "                    }\n",
)

# 11-4 (family-hole): the reset keeps its text and its `= 0` right-hand side,
# and is gated on a constant that never holds.
BAD_DEAD_RESET = GOOD_ENGINE.replace(
    "                    loopState.unpersistedBlockStart = 0\n",
    "                    if (1 == 2) loopState.unpersistedBlockStart = 0\n",
)

# 11-5 (parser-death): the field declaration survives only in a comment, plus a
# `var x: Int = 0` accessor pair that swallows every write.
BAD_ACCESSOR_FIELD = (
    "    // var unpersistedBlockStart: Int = 0\n"
    "    var unpersistedBlockStart: Int = 0\n"
    "        get() = 0\n"
    "        set(_) {}\n"
)
BAD_COMMENTED_FIELD = "    // var unpersistedBlockStart: Int = 0\n"

# 11-6 (spelling): the receiver is parenthesised, so a `loopState\.` anchored
# regex sees nothing.
BAD_PAREN_RECEIVER = GOOD_ENGINE.replace(
    "                val turnParts = host.buildTurnParts(loopState.allToolBlocks, loopState.unpersistedBlockStart, toolInputMap)\n",
    "                (loopState).unpersistedBlockStart = turnStartBlockIndex\n"
    "                val turnParts = host.buildTurnParts(loopState.allToolBlocks, loopState.unpersistedBlockStart, toolInputMap)\n",
)

# 11-6b (spelling): the receiver is implicit inside `loopState.apply { }`.
BAD_APPLY_RECEIVER = GOOD_ENGINE.replace(
    "                val turnParts = host.buildTurnParts(loopState.allToolBlocks, loopState.unpersistedBlockStart, toolInputMap)\n",
    "                loopState.apply { unpersistedBlockStart = turnStartBlockIndex }\n"
    "                val turnParts = host.buildTurnParts(loopState.allToolBlocks, loopState.unpersistedBlockStart, toolInputMap)\n",
)

# R8 arithmetic spelling.
BAD_ARITHMETIC_INDEX = GOOD_ENGINE.replace(
    "                    loopState.unpersistedBlockStart = loopState.allToolBlocks.size\n"
    "                    val lastAssistantIdx",
    "                    loopState.unpersistedBlockStart += loopState.allToolBlocks.size\n"
    "                    val lastAssistantIdx",
)

# R10 value shape: the backfill takes something other than the row id.
BAD_BACKFILL_WRONG_VALUE = GOOD_ENGINE.replace(
    "copy(dbMessageId = persistedRowId)",
    "copy(dbMessageId = turnStartBlockIndex)",
)

# ── round-3 witnesses (#121 / #122 / #123) ───────────────────────────────

# #121 (dual-gate seam): the early row keeps its id but is never REFRESHED, so
# the pre-tool PENDING snapshot stays in the DB while the cursor advances.
BAD_EARLY_REFRESH_DROPPED = GOOD_ENGINE.replace(
    "                    host.updatePersistedAssistantTurn(earlyId, turnParts, blockMeta)\n"
    "                    earlyId\n",
    "                    earlyId\n",
)

# #121b (spelling): the refresh call survives but targets a different row.
BAD_EARLY_REFRESH_WRONG_ROW = GOOD_ENGINE.replace(
    "host.updatePersistedAssistantTurn(earlyId, turnParts, blockMeta)",
    "host.updatePersistedAssistantTurn(nearestId, turnParts, blockMeta)",
)

# #122 (dual-gate seam): an empty container makes the host return null, so the
# paired tool_result row never lands.
BAD_TOOL_RESULT_EMPTY = GOOD_ENGINE.replace(
    "host.persistToolResultMessage(resultParts, transcriptRedactions)",
    "host.persistToolResultMessage(emptyList(), transcriptRedactions)",
)

# #122b (family-hole): the container is handed over but never filled.
BAD_TOOL_RESULT_UNFILLED = GOOD_ENGINE.replace(
    "                    resultParts.add(AgentContentPart.ToolResult(id = id, name = name))\n"
    "                    resultParts += pendingResults\n",
    "",
)

# #123 (symmetric axis): the rollback baseline is seeded with 0, so any retry
# wipes every earlier turn's finished blocks.
BAD_PER_TURN_ZERO = GOOD_ENGINE.replace(
    "val turnStartBlockIndex = loopState.allToolBlocks.size",
    "val turnStartBlockIndex = 0",
)

# #123b (stale baseline): a plausible-looking but wrong seed.
BAD_PER_TURN_STALE = GOOD_ENGINE.replace(
    "val turnStartBlockIndex = loopState.allToolBlocks.size",
    "val turnStartBlockIndex = loopState.unpersistedBlockStart",
)

VIOLATING = [
    # (fixture name, engine, state, expected message fragment)
    ("per-turn-slice", BAD_PER_TURN_SLICE, GOOD_STATE, "persistence slice starts at 'turnStartBlockIndex'"),    ("missing-advance", BAD_NO_ADVANCE, GOOD_STATE, "does not advance the unpersisted index"),
    ("over-broad-rollback", BAD_ROLLBACK_SWITCHED, GOOD_STATE, "rollback uses"),
    ("clear-without-reset", BAD_CLEAR_NO_RESET, GOOD_STATE, "does not reset the unpersisted index"),
    ("missing-backfill", BAD_NO_BACKFILL, GOOD_STATE, "does not backfill dbMessageId"),
    ("missing-field", GOOD_ENGINE, BAD_STATE, "does not declare"),
    ("visibility-switched", BAD_VISIBILITY_SWITCHED, GOOD_STATE, "per-turn visibility uses"),
    ("arithmetic-index", BAD_ARITHMETIC_INDEX, GOOD_STATE, "adjusted with '+='"),
    ("backfill-wrong-value", BAD_BACKFILL_WRONG_VALUE, GOOD_STATE, "backfilled from"),
    ("tainted-advance", BAD_TAINTED_ADVANCE, GOOD_STATE, "is set to 'turnStartBlockIndex'"),
    ("hoisted-advance", BAD_HOISTED_ADVANCE, GOOD_STATE, "advances OUTSIDE a success guard"),
    ("unreachable-backfill", BAD_UNREACHABLE_BACKFILL, GOOD_STATE, "cannot hold TOGETHER"),
    ("multiline-slice", BAD_MULTILINE_SLICE, GOOD_STATE, "persistence slice starts at 'turnStartBlockIndex'"),
    ("dead-guarded-advance", BAD_DEAD_ADVANCE, GOOD_STATE, "UNREACHABLE path"),
    ("contradictory-backfill", BAD_CONTRADICTORY_BACKFILL, GOOD_STATE, "cannot hold TOGETHER"),
    ("range-contradiction", BAD_RANGE_CONTRADICTION, GOOD_STATE, "cannot hold TOGETHER"),
    ("dead-guarded-reset", BAD_DEAD_RESET, GOOD_STATE, "reset sits behind a guard that can never hold"),
    ("accessor-backed-field", GOOD_ENGINE, BAD_ACCESSOR_FIELD, "custom 'get' accessor"),
    ("commented-out-field", GOOD_ENGINE, BAD_COMMENTED_FIELD, "does not declare"),
    ("paren-receiver", BAD_PAREN_RECEIVER, GOOD_STATE, "is set to 'turnStartBlockIndex'"),
    ("apply-receiver", BAD_APPLY_RECEIVER, GOOD_STATE, "is set to 'turnStartBlockIndex'"),
    ("early-refresh-dropped", BAD_EARLY_REFRESH_DROPPED, GOOD_STATE,
     "returns the early row without calling"),
    ("early-refresh-wrong-row", BAD_EARLY_REFRESH_WRONG_ROW, GOOD_STATE,
     "refreshes 'nearestId'"),
    ("tool-result-empty-container", BAD_TOOL_RESULT_EMPTY, GOOD_STATE,
     "the tool-result persist is handed 'emptyList()'"),
    ("tool-result-never-filled", BAD_TOOL_RESULT_UNFILLED, GOOD_STATE,
     "without ever being filled"),
    ("per-turn-baseline-zero", BAD_PER_TURN_ZERO, GOOD_STATE,
     "the per-turn baseline is initialised to '0'"),
    ("per-turn-baseline-stale", BAD_PER_TURN_STALE, GOOD_STATE,
     "the per-turn baseline is initialised to 'loopState.unpersistedBlockStart'"),
]

CLEAN = [
    ("compliant", GOOD_ENGINE, GOOD_STATE),
    ("compliant-multiline-slice", GOOD_MULTILINE_SLICE, GOOD_STATE),
    ("compliant-with-scope", GOOD_WITH_SCOPE, GOOD_STATE),
]


def selftest() -> int:
    failures = []
    print("loop_persist_slice_check selftest:")
    for name, engine, state in CLEAN:
        got = check_source(engine, state, name + ".kt")
        status = "PASS" if not got else "FAIL"
        if got:
            failures.append("%s fixture must be clean, got %s" % (name, got))
        print("  [%s] clean %-28s -> %d violation(s)" % (status, name, len(got)))
    red = 0
    for name, engine, state, marker in VIOLATING:
        got = check_source(engine, state, name + ".kt")
        hit = any(marker in b for b in got)
        if got:
            red += 1
        if not hit:
            failures.append("%s fixture did not trip its own criterion (%r), got %s"
                            % (name, marker, got))
        print("  [%s] dirty %-28s -> %d violation(s)" % ("PASS" if hit else "FAIL", name, len(got)))
    print("  violating fixtures red: %d / %d ; clean fixtures: %d" % (red, len(VIOLATING), len(CLEAN)))
    if failures:
        for f in failures:
            print("SELFTEST FAIL " + f)
        return 1
    # Frozen test_scan.py asserts the legacy sentence verbatim; it stays TRUE
    # (scoped to the original deep-audit-1016 six-fixture set, all still red)
    # so the assertion keeps working while the batch-5 arm total lives on the
    # second line — register THAT one with the main session.
    print("SELFTEST=PASS loop_persist_slice_check (6 violating fixtures red, compliant fixture green)"
          " [legacy marker: the original deep-audit-1016 six-fixture set, all still red above]")
    print("LOOP_PERSIST_SLICE_SELFTEST=PASS (%d violating + %d clean arms)"
          % (len(VIOLATING), len(CLEAN)))
    return 0


def main() -> int:
    args = [a for a in sys.argv[1:] if a]
    if "--selftest" in args:
        return selftest()
    repo = next((a for a in args if not a.startswith("-")), os.getcwd())
    return run(repo)


if __name__ == "__main__":
    sys.exit(main())
