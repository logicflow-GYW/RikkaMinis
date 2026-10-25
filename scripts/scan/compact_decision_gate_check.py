#!/usr/bin/env python3
"""
Compact-governance decision gate (deep-audit-1016c ISSUE-04 / ISSUE-05).

Three coupled properties of the auto-compact entry, each of which failed
silently in production before [fix/compact-pregate-exhausted-1016] /
[fix/compact-escalation-tier-contract-1016] / [fix/compact-lastfailure-pin-1016]
/ [fix/compact-silent-abort-cards-1016]:

  1. ENTRY ADMITS EVERY VERDICT `decide()` ADJUDICATES.
     `maybeAutoCompactInLoop` used to enter `decide()` only on NEEDS_COMPACT
     (plus the one-shot offload escalation). `ContextPolicy.check` answers
     EXHAUSTED the moment tokens >= window and NEEDS_COMPACT only strictly
     below it, so the single state `decide()` turns into RESCUE / EXHAUSTED
     could not reach it — the once-per-user-action rescue was behind a door
     that was shut exactly when it was needed, and an over-ceiling context fell
     straight through to the verbatim hard trim.

  2. ESCALATION DOES NOT FABRICATE A COMPACT LINE.
     `escalatedFromOffload` exists to bypass the *band* check on tiers that
     have a compact line. On an `exhaustedOnly` tier (`compactThreshold == 0`,
     contract: "too small for auto-compact; only surface .exhausted") the
     bypass used to auto-compact anyway.

  3. THE CHAIN THROWS ITS CAPACITY EVIDENCE.
     The outer catch decides "halve and retry" purely on
     `isContextTooLargeError(e)`. When a 400 context-length failure was
     followed by an ordinary one (timeout / 5xx / unavailable), the chain threw
     the ordinary one and the halving retry never fired.

  plus the silent-mode card contract in `compactAll` (criterion 4) and the two
  latency levers of [fix/compact-timeout-budget-1022] (criterion 5).

Mechanical criteria (each anchored on the DECISION, not on a spelling):
  1. `entry-admits-exhausted` — inside `maybeAutoCompactInLoop` a single
     `val verdict = policy.check(...)` feeds `if (!loopGateOpens(verdict, …))`
     and that guard is the WHOLE decision: between it and `decide(` there is no
     other reachable early exit that can refuse the ceiling verdict.
  2. `gate-admits-exhausted` — `loopGateOpens` admits EXHAUSTED on a LIVE
     disjunct (constant-folded: `(false && …)` / `(!true && …)` admit nothing).
  3. `escalation-tier-contract` — the escalation shortcut consults the tier
     predicate, and its body is exactly `return Decision.OK` (a nested early
     return would eat half of the truth table the guard was written for).
  4. `chain-pins-too-large` — the chain routes failures through
     `ContextCompactor.chainFailureAfter(…)`; the two state variables are left
     holding that reduction's halves (no kill-after-def, e.g. `= null`), and
     `chainTerminalFailure(…)` is applied BEFORE `throw lastFailure`.
  5. `silent-card-contract` — every repeatable `appendSystemInfo` card is
     enclosed by a live `if (!silent)` (a constant-true arm such as `|| true`
     or `|| !false` absorbs the guard even though `!silent` is still spelled).
  6. `chain-budget-adaptive` — the summary budget is a function of the material
     size, the large-material threshold is the single-source constant, and the
     deadline still clamps.
  7. `chain-timeout-dedup` — a route that timed out in this chain is skipped
     before it is budgeted, and the skip branch actually terminates the
     iteration (a dead `if (false) continue` terminates nothing).

[batch5 B5-t02] All structural reading now goes through the shared primitives
(`_kotlin_lexer.mask_code`, `_kotlin_ast.{function_body,if_blocks,
enclosing_conditions,last_write,stmt_spans,const_bool,split_top}`,
`_kotlin_valueflow.{guard_feasible,path_feasible,brace_depth,expr_end}`): the
hand-written lexer / brace counter / literal whitelist this gate used to carry
was exactly what the five falsifying witnesses walked through (`!true`,
`|| !false`, an extra `return false` after the gate, an early return inside the
escalation guard, a kill-after-def on the pinned failure).

Usage: python3 scripts/scan/compact_decision_gate_check.py [repo_root]
       python3 scripts/scan/compact_decision_gate_check.py --selftest
Exit:  0 = guarded, 1 = violation (file:line printed)
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import KotlinLexError, mask_code, mask_comments  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool,
    const_value,
    enclosing_conditions,
    function_body,
    if_blocks,
    last_write,
    split_top,
    stmt_spans,
    when_branches,
)
from _kotlin_valueflow import (  # noqa: E402
    brace_depth,
    expr_end,
    guard_feasible,
    path_feasible,
)

MAIN = "src/android/app/src/main/java/com/rikkaminis/app"
LOOP_FILE = MAIN + "/ui/chat/ChatContextWindowExt.kt"
COMPACTOR_FILE = MAIN + "/conversation/ContextCompactor.kt"
ORCH_FILE = MAIN + "/conversation/CompactOrchestration.kt"

LOOP_FUN = "maybeAutoCompactInLoop"
GATE_CALL = "ContextCompactor.loopGateOpens(verdict, escalatedFromOffload)"
VERDICT_DECL = "val verdict = policy.check(lastContextTokens, contextWindow)"
OLD_GATE = "if (!inCompactBand && !escalatedFromOffload)"
TIER_GUARD = "tierHasNoCompactLine"
TIER_DECL = "val tierHasNoCompactLine = policy.compactThreshold <= 0 || policy.exhaustedOnly"
# the guard CONDITION, not just the identifier: dropping the predicate from the
# condition while leaving the declaration behind must still flip this red.
TIER_GUARD_COND = "!bandReached && (!escalatedFromOffload || tierHasNoCompactLine)"
TIER_GUARD_EXPR = "if (" + TIER_GUARD_COND + ")"
RESOURCE_RE = re.compile(r"R\.string\.([A-Za-z0-9_]+)")
CHAIN_PREDICATE = "isTooLarge = ::isContextTooLargeError"
CHAIN_STEP = "ContextCompactor.chainFailureAfter("
CHAIN_TERMINAL = "ContextCompactor.chainTerminalFailure("
REDUCE_DECL_RE = re.compile(r"\bval\s+(\w+)\s*=\s*" + re.escape(CHAIN_STEP))
REMOTE_FAILURE = "SendResult.RemoteFailure"
SILENT_RE = re.compile(r"!\s*silent\b")
KEPT_CRITERION = "Deliberately NOT"

# ── criterion 5 [fix/compact-timeout-budget-1022] anchors ────────────────
BUDGET_FN = "internal fun compactionCandidateBudgetMs("
MATERIAL_PARAM = "materialTokens: Int"
LARGE_TIER = "if (declaresNoEffortTiers == true || largeMaterial)"
LARGE_CONST_REF = "ChatViewModel.COMPACT_SUMMARY_LARGE_MATERIAL_TOKENS"
DEADLINE_CLAMP = "return minOf(perCandidate, deadlineAt - nowMs)"
BUDGET_CALL = "val budgetMs = compactionCandidateBudgetMs("
KEY_FN = "internal fun compactionCandidateKey("
ROUTE_SET_DECL = "val timedOutRoutes = mutableSetOf<String>()"
ROUTE_DECL = "val route = compactionCandidateKey("
ROUTE_MODEL_ARG = "modelId = candidate.model.id"
ROUTE_SKIP_COND = "route in timedOutRoutes"
ROUTE_SKIP = "if (" + ROUTE_SKIP_COND + ")"
ROUTE_RECORD = "timedOutRoutes.add(route)"
TIMEOUT_LOG = "summary TIMEOUT after"

# resource ids of the compactAll cards the silent contract is judged on
GATED_CARDS = [
    "sysmsg_compact_empty_summary",
    "sysmsg_compact_anchor_failed",
    "sysmsg_compact_failed",
]
KEPT_CARD = "sysmsg_compact_anchor_id_missing"

SELFTEST_MARK = "COMPACT_DECISION_GATE_SELFTEST=PASS"


def line_of(text, needle):
    idx = text.find(needle)
    return text.count("\n", 0, idx) + 1 if idx >= 0 else 0


# ── shared-primitive adapters ────────────────────────────────────────────
def parts(expr, op):
    """顶层按 op 切分的片段文本（共享 split_top 返回区间）。"""
    return [expr[a:b] for a, b in split_top(expr, (op,))]


def norm(expr):
    return re.sub(r"\s+", "", expr)


def const_true_arm(cond):
    """条件里存在恒真的顶层析取臂（`|| true` / `|| !false` / `|| 1 == 1`）。

    该臂把整个条件吸收成真，其它臂（含 `!silent`）再也无法拒绝 —— 这是
    `always_true` 只看字面 `true` 的家族洞（G02-A5）。
    """
    return any(const_bool(p) is True for p in parts(cond, "||"))


def live_cond(cond):
    """条件是否可能为真。**同门所有臂都走这一个判定**：只认字面 `false`
    的白名单漏掉 `!true` / `1 == 2` / `x && !x`。"""
    return guard_feasible(cond) is not False


def returning_expr(text, start, end):
    """[start,end) 内顶层 `return` 的返回表达式；找不到则返回整段。"""
    base = brace_depth(text, start)
    for m in re.finditer(r"\breturn\b", text[start:end]):
        pos = start + m.start()
        if brace_depth(text, pos) != base:
            continue
        return text[pos + len("return"):expr_end(text, pos + len("return"))]
    return text[start:end]


def early_exit_between(text, start, end):
    """[start,end) 内**可达的、真正退出外层函数**的 return/throw 位置。

    `return@label` 只退 lambda；死分支（`if (false)`）里的不算 —— 判据问的是
    「这之后还有没有别的语句能把这次调用拒掉」，不是「有没有出现过 return」。
    """
    base = brace_depth(text, start)
    for m in re.finditer(r"\b(return|throw)\b", text[start:end]):
        pos = start + m.start()
        nxt = text[pos + len(m.group(1)):pos + len(m.group(1)) + 1]
        if nxt == "@":
            continue
        if brace_depth(text, pos) != base:
            continue
        if not path_feasible(text, pos):
            continue
        return pos
    return -1


def segments_between_calls(text):
    """(start, end, resource_id) for every appendSystemInfo card call site."""
    calls = []
    cursor = 0
    while True:
        idx = text.find("appendSystemInfo(", cursor)
        if idx < 0:
            break
        calls.append(idx)
        cursor = idx + 1
    out = []
    prev_end = 0
    for idx in calls:
        match = RESOURCE_RE.search(text[idx:idx + 400])
        out.append((prev_end, idx, match.group(1) if match else ""))
        prev_end = idx
    return out


def check(loop, compactor, orch):
    """Return [(criterion, message)] — empty list means guarded."""
    problems = []

    def need(text, needle, where, criterion, why):
        if needle not in text:
            problems.append((criterion, "%s: missing %r (%s)" % (where, needle, why)))

    def forbid(text, needle, where, criterion, why):
        if needle in text:
            problems.append((
                criterion,
                "%s:%d forbidden %r remains (%s)" % (where, line_of(text, needle), needle, why),
            ))

    try:
        loop_m = mask_code(loop)
        comp_m = mask_code(compactor)
        orch_m = mask_code(orch)
        # literal channel (strings kept, comments stripped) for needles that live
        # inside string literals (`summary TIMEOUT after`), and raw text for the
        # keep-criterion, which IS a written comment by design.
        orch_l = mask_comments(orch)
    except KotlinLexError as exc:  # fail-closed: never fall back to raw text
        return [("parse", "kotlin lexer refused a source file — gate fails closed (%s)" % exc)]

    # ── criterion 1: the loop entry admits EXHAUSTED, and the gate decides ──
    body = function_body(loop_m, LOOP_FUN)
    if body is None:
        problems.append(("entry-admits-exhausted",
                         "%s: could not isolate fun %s (gate ROT)" % (LOOP_FILE, LOOP_FUN)))
    else:
        b_start, b_end, _kind = body
        region = loop_m[b_start:b_end]
        need(region, VERDICT_DECL, LOOP_FILE, "entry-admits-exhausted",
             "the pressure verdict must be computed once and fed to the gate")
        need(region, GATE_CALL, LOOP_FILE, "entry-admits-exhausted",
             "the pre-gate must be the single-source predicate")
        forbid(region, OLD_GATE, LOOP_FILE, "entry-admits-exhausted",
               "the pre-fix gate refused EXHAUSTED, which is what decide() adjudicates")
        # The gate call must be the WHOLE decision:
        #  - `if (!gate || verdict == EXHAUSTED) return false` keeps the call and
        #    the verdict (cond-exact check), and
        #  - `if (!gate) { return false }` followed by a second, independent
        #    `if (verdict == EXHAUSTED) return false` (G02-A1 order witness) keeps
        #    the guard byte-identical — so the sequence up to `decide(` is judged
        #    too: no reachable early exit may sit between the gate and the call.
        call_ns = norm(GATE_CALL)
        blocks = [b for b in if_blocks(region) if GATE_CALL in b[0]]
        if not blocks:
            problems.append((
                "entry-admits-exhausted",
                "%s: the loop gate verdict does not decide an `if` — computed and discarded"
                % LOOP_FILE,
            ))
        else:
            blocks.sort(key=lambda b: b[1])
            cond, t_start, t_end, _es, _ee = blocks[-1]
            cond_ns = norm(cond)
            if cond_ns not in ("!" + call_ns, "!(" + call_ns + ")", call_ns):
                problems.append((
                    "entry-admits-exhausted",
                    "%s:%d: the entry guard must be exactly `!%s`; it is `%s` — an extra operand can "
                    "refuse the ceiling verdict before decide() ever adjudicates it"
                    % (LOOP_FILE, loop_m.count("\n", 0, b_start + t_start) + 1, GATE_CALL,
                       cond.strip()),
                ))
            decide_idx = region.find("ContextCompactor.decide(", t_end)
            stop = decide_idx if decide_idx > 0 else len(region)
            exit_pos = early_exit_between(region, t_end, stop)
            if exit_pos >= 0:
                problems.append((
                    "entry-admits-exhausted",
                    "%s:%d: another early exit sits between the loop gate and `decide(` — the gate "
                    "is no longer the whole decision, so a later statement can refuse the ceiling "
                    "verdict that decide() exists to adjudicate (`%s`)"
                    % (LOOP_FILE, loop_m.count("\n", 0, b_start + exit_pos) + 1,
                       " ".join(region[exit_pos:exit_pos + 70].split())),
                ))

    # ── criterion 2: the gate itself (+ criterion 3's tier contract) ────
    gate_at = comp_m.find("fun loopGateOpens(")
    gate_line = comp_m.count("\n", 0, gate_at) + 1 if gate_at >= 0 else 0
    gate_fn = function_body(comp_m, "loopGateOpens")
    if gate_fn is None:
        problems.append(("gate-admits-exhausted",
                         "%s: fun loopGateOpens( missing — gate ROT" % COMPACTOR_FILE))
    else:
        g_start, g_end, g_kind = gate_fn
        expr = comp_m[g_start:g_end] if g_kind == "expr" else returning_expr(comp_m, g_start, g_end)
        if "ContextPolicy.CheckResult.EXHAUSTED" not in expr:
            problems.append((
                "gate-admits-exhausted",
                "%s:%d: the gate no longer admits the ceiling verdict — EXHAUSTED is the only "
                "state decide() turns into RESCUE" % (COMPACTOR_FILE, gate_line),
            ))
        else:
            arms = [p for p in parts(expr, "||") if "EXHAUSTED" in p] or [expr]
            if not any(live_cond(a) for a in arms):
                problems.append((
                    "gate-admits-exhausted",
                    "%s:%d: the EXHAUSTED admission is constant-false (`%s`) — a dead disjunct "
                    "admits nothing, the gate still refuses the ceiling verdict"
                    % (COMPACTOR_FILE, gate_line, " ".join(arms[0].split())[:80]),
                ))
    need(comp_m, TIER_DECL, COMPACTOR_FILE, "escalation-tier-contract",
         "the tier contract needs a single-source predicate")
    need(comp_m, TIER_GUARD_EXPR, COMPACTOR_FILE, "escalation-tier-contract",
         "the escalation shortcut must consult the tier predicate")
    # The shortcut's branch must be exactly `return Decision.OK`: inserting a
    # second, narrower early return INSIDE it (G02-A3) keeps the condition head
    # byte-identical while eating half the truth table the guard was written for.
    tier_blocks = [b for b in if_blocks(comp_m) if norm(b[0]) == norm(TIER_GUARD_COND)]
    if not tier_blocks:
        problems.append((
            "escalation-tier-contract",
            "%s: no `%s` guard is decided as a condition — the escalation bypass cannot be judged"
            % (COMPACTOR_FILE, TIER_GUARD_COND),
        ))
    else:
        for cond, t_start, t_end, _es, _ee in tier_blocks:
            then_txt = comp_m[t_start:t_end].strip()
            inner = then_txt[1:-1] if then_txt.startswith("{") and then_txt.endswith("}") else then_txt
            stmts = [inner[a:b].strip() for a, b in stmt_spans(inner) if inner[a:b].strip()]
            if len(stmts) != 1 or not norm(stmts[0]).startswith("returnDecision.OK"):
                problems.append((
                    "escalation-tier-contract",
                    "%s:%d: the escalation guard's body must be exactly `return Decision.OK`; it is "
                    "`%s` — an extra early exit inside the guard narrows the escalation it was "
                    "written to allow"
                    % (COMPACTOR_FILE, comp_m.count("\n", 0, t_start) + 1,
                       " ".join(inner.split())[:90]),
                ))

    # ── criterion 4: the chain keeps + throws its capacity evidence ─────
    need(orch_m, CHAIN_STEP, ORCH_FILE, "chain-pins-too-large",
         "the reduction must be single-sourced in the pure function")
    need(orch_m, CHAIN_PREDICATE, ORCH_FILE, "chain-pins-too-large",
         "the reduction must be driven by the real classifier")
    red_m = REDUCE_DECL_RE.search(orch_m)
    if not red_m:
        problems.append(("chain-pins-too-large",
                         "%s: no `val <reduced> = %s…)` binding — the reduction result is dropped"
                         % (ORCH_FILE, CHAIN_STEP)))
    else:
        reduced = red_m.group(1)
        branch = [b for b in when_branches(orch_m) if REMOTE_FAILURE in b[1]]
        seg_base = branch[0][2] if branch else red_m.start()
        seg = orch_m[seg_base:branch[0][3] if branch else len(orch_m)]
        for name, side, why in (
            ("tooLargeFailure", "second",
             "the pinned capacity evidence must hold the reduction's pinned half"),
            ("lastFailure", "first",
             "the chain's last failure must hold the reduction's ordinary half"),
        ):
            lw = last_write(seg, name)
            if lw is None:
                problems.append((
                    "chain-pins-too-large",
                    "%s: `%s` is never assigned from the reduction — the chain drops its capacity "
                    "evidence and the outer catch cannot halve-and-retry" % (ORCH_FILE, name),
                ))
                continue
            pos, rhs, _is_decl = lw
            folded = const_value(rhs)
            if folded is not None and folded[0] == "null":
                problems.append((
                    "chain-pins-too-large",
                    "%s:%d: `%s` is overwritten with `null` right after it is set (kill-after-def) "
                    "— the outer catch sees no capacity evidence (%s)"
                    % (ORCH_FILE, orch_m.count("\n", 0, seg_base + pos) + 1, name, why),
                ))
            elif not (reduced in rhs and side in rhs):
                problems.append((
                    "chain-pins-too-large",
                    "%s:%d: `%s` ends up holding `%s` instead of `%s.%s` — %s"
                    % (ORCH_FILE, orch_m.count("\n", 0, seg_base + pos) + 1, name,
                       " ".join(rhs.split())[:60], reduced, side, why),
                ))
    if CHAIN_TERMINAL in orch_m:
        terminal_at = orch_m.find(CHAIN_TERMINAL)
        throw_at = orch_m.rfind("throw lastFailure")
        if throw_at < 0 or terminal_at > throw_at:
            problems.append((
                "chain-pins-too-large",
                "%s: %s must be applied BEFORE `throw lastFailure`" % (ORCH_FILE, CHAIN_TERMINAL),
            ))
    else:
        problems.append(("chain-pins-too-large",
                         "%s: %s missing" % (ORCH_FILE, CHAIN_TERMINAL)))

    # ── criterion 5: the silent-mode card contract ─────────────────────
    cards = segments_between_calls(orch_m)
    orch_ifs = if_blocks(orch_m)
    seen = {resource: (start, idx) for start, idx, resource in cards if resource}

    def live_silent_gate(idx):
        for _kind, cond in enclosing_conditions(orch_m, idx):
            if not SILENT_RE.search(cond):
                continue
            # `|| true` / `|| !false` are constant-true arms: the `!silent` is
            # still spelled, the guard can never reject.
            if const_true_arm(cond):
                continue
            if not live_cond(cond):
                continue
            return True
        return False

    for resource in GATED_CARDS:
        if resource not in seen:
            problems.append(("silent-card-contract",
                             "%s: card %s not found" % (ORCH_FILE, resource)))
            continue
        _start, idx = seen[resource]
        if not live_silent_gate(idx):
            problems.append((
                "silent-card-contract",
                "%s:%d card %s is reachable in silent mode (not enclosed by a live `if (!silent)` "
                "in code — a commented-out gate, a look-back neighbour, or a constant-true arm does "
                "not count)" % (ORCH_FILE, orch_m.count("\n", 0, idx) + 1, resource),
            ))
    if KEPT_CARD not in seen:
        problems.append(("silent-card-contract",
                         "%s: kept card %s not found" % (ORCH_FILE, KEPT_CARD)))
    else:
        start, idx = seen[KEPT_CARD]
        span = orch[start:idx]
        if live_silent_gate(idx):
            problems.append((
                "silent-card-contract",
                "%s:%d %s is now silent-gated: the written keep-criterion must be revisited, not copied"
                % (ORCH_FILE, orch_m.count("\n", 0, idx) + 1, KEPT_CARD),
            ))
        if KEPT_CRITERION not in span:
            problems.append((
                "silent-card-contract",
                "%s:%d %s is not silent-gated but carries no explicit keep-criterion"
                % (ORCH_FILE, orch_m.count("\n", 0, idx) + 1, KEPT_CARD),
            ))

    # ── criterion 6 [fix/compact-timeout-budget-1022] ──────────────────
    budget_fn = function_body(orch_m, "compactionCandidateBudgetMs")
    if budget_fn is None:
        problems.append(("chain-budget-adaptive",
                         "%s: %s missing" % (ORCH_FILE, BUDGET_FN)))
    else:
        bstart, bend, _k = budget_fn
        header_at = orch_m.rfind("fun compactionCandidateBudgetMs(", 0, bstart)
        # signature + body: `materialTokens: Int` is a PARAMETER, so a body-only
        # slice would report it missing on a healthy tree.
        budget_body = orch_m[header_at if header_at >= 0 else bstart:bend]
        need(budget_body, MATERIAL_PARAM, ORCH_FILE, "chain-budget-adaptive",
             "the budget must take the summary material size")
        need(budget_body, LARGE_TIER, ORCH_FILE, "chain-budget-adaptive",
             "a large material must be able to open the 60s tier")
        need(budget_body, LARGE_CONST_REF, ORCH_FILE, "chain-budget-adaptive",
             "the threshold must be the single-source constant, not a bare literal")
        if DEADLINE_CLAMP not in budget_body and not re.search(
                r"return\s+minOf\(", budget_body):
            problems.append((
                "chain-budget-adaptive",
                "%s: the chain deadline must still clamp every candidate (no `minOf(perCandidate, "
                "deadlineAt - nowMs)` in the budget body)" % ORCH_FILE,
            ))
        if re.search(r"materialTokens\s*>\s*\d", budget_body):
            problems.append((
                "chain-budget-adaptive",
                "%s: the material threshold is inlined as a bare number" % ORCH_FILE,
            ))
    if BUDGET_CALL in orch_m:
        args = orch_m.split(BUDGET_CALL, 1)[1].split(")", 1)[0]
        call_parts = [p.strip() for p in args.split(",")]
        second = call_parts[1].split("=")[-1].strip() if len(call_parts) > 1 else ""
        if not second or re.fullmatch(r"[0-9_]+L?", second):
            problems.append((
                "chain-budget-adaptive",
                "%s: the budget call must pass the real material estimate (got %r) — a literal pins "
                "every fold into one tier" % (ORCH_FILE, second or "<missing>"),
            ))

    # ── criterion 7: a route that timed out is not paid for twice ──────
    for needle, why in (
        (ROUTE_SET_DECL, "the chain must remember which routes timed out"),
        (KEY_FN, "the route identity must be a single-source function"),
        (ROUTE_DECL, "the candidate route must be resolved at the top of the loop body"),
        (ROUTE_MODEL_ARG, "the route key must carry the model id (host alone is not an identity)"),
        (ROUTE_SKIP_COND, "a route that already timed out must be skipped"),
        (ROUTE_RECORD, "the timeout verdict must record its route"),
    ):
        need(orch_m, needle, ORCH_FILE, "chain-timeout-dedup", why)
    if ROUTE_SKIP_COND in orch_m and BUDGET_CALL in orch_m and ROUTE_RECORD in orch_m:
        skip_at = orch_m.find(ROUTE_SKIP_COND)
        budget_at = orch_m.find(BUDGET_CALL)
        timeout_at = orch_l.find(TIMEOUT_LOG)
        record_at = orch_m.find(ROUTE_RECORD)
        if not skip_at < budget_at:
            problems.append((
                "chain-timeout-dedup",
                "%s: the route skip must precede the budget call — a skipped candidate must not "
                "consume the chain deadline" % ORCH_FILE,
            ))
        if timeout_at < 0 or not timeout_at < record_at:
            problems.append((
                "chain-timeout-dedup",
                "%s: the route must be recorded on the timeout verdict, not before it" % ORCH_FILE,
            ))
    # The skip branch must TERMINATE the iteration. `if (route in timedOutRoutes) {
    # AppLogger.info(… "skip" …) }` keeps the needle, prints a line that says
    # "skip", and then pays the same wall again — position is not the decision;
    # the `continue` is. And a `continue` behind a dead guard terminates nothing.
    skip_blocks = [b for b in orch_ifs if norm(b[0]) == norm(ROUTE_SKIP_COND)]
    if ROUTE_SKIP_COND in orch_m and not skip_blocks:
        problems.append((
            "chain-timeout-dedup",
            "%s: the route skip is not an `if (...) { … }` statement — its body cannot be checked "
            "for a terminating branch" % ORCH_FILE,
        ))
    for cond, t_start, t_end, _es, _ee in skip_blocks:
        terminators = [m.start() for m in re.finditer(
            r"\b(continue|break|return|throw)\b", orch_m[t_start:t_end])
            if path_feasible(orch_m, t_start + m.start())]
        if not terminators:
            problems.append((
                "chain-timeout-dedup",
                "%s:%d: the timed-out route's skip branch does not terminate the iteration — it logs "
                "`skip candidate` and then sends anyway, paying the same dead route twice and "
                "starving the heterogeneous backups of the chain deadline"
                % (ORCH_FILE, orch_m.count("\n", 0, t_start) + 1),
            ))

    return problems


def load(root):
    out = []
    for rel in (LOOP_FILE, COMPACTOR_FILE, ORCH_FILE):
        path = os.path.join(root, rel)
        if not os.path.exists(path):
            print("❌ compact-decision gate: %s not found (moved?)" % rel)
            return None
        with open(path, encoding="utf-8") as fh:
            out.append(fh.read())
    return out


def selftest(root):
    files = load(root)
    if files is None:
        return 1
    loop, compactor, orch = files

    def on_loop(fn):
        return lambda l, c, o: (fn(l), c, o)

    def on_compactor(fn):
        return lambda l, c, o: (l, fn(c), o)

    def on_orch(fn):
        return lambda l, c, o: (l, c, fn(o))

    arms = [
        # (name, criterion that must fire, mutator)
        (
            "pre-fix entry gate restored",
            "entry-admits-exhausted",
            on_loop(lambda s: s.replace(GATE_CALL, OLD_GATE, 1)),
        ),
        (
            "gate no longer admits EXHAUSTED",
            "gate-admits-exhausted",
            on_compactor(lambda s: s.replace(
                "verdict == ContextPolicy.CheckResult.EXHAUSTED ||\n            escalatedFromOffload",
                "escalatedFromOffload",
                1,
            )),
        ),
        (
            "escalation tier guard dropped",
            "escalation-tier-contract",
            on_compactor(lambda s: s.replace(
                TIER_GUARD_EXPR, "if (!bandReached && !escalatedFromOffload)", 1,
            )),
        ),
        (
            "chain terminal rule removed",
            "chain-pins-too-large",
            on_orch(lambda s: s.replace(
                "    lastFailure = ContextCompactor.chainTerminalFailure(lastFailure, tooLargeFailure)\n",
                "",
                1,
            )),
        ),
        (
            "silent gate removed from a repeatable card",
            "silent-card-contract",
            on_orch(lambda s: s.replace(
                "                if (!silent) {\n                    withContext(Dispatchers.Main) {\n"
                "                        appendSystemInfo(context.getString(R.string.sysmsg_compact_empty_summary), \"compact\")",
                "                if (true) {\n                    withContext(Dispatchers.Main) {\n"
                "                        appendSystemInfo(context.getString(R.string.sysmsg_compact_empty_summary), \"compact\")",
                1,
            )),
        ),
        (
            "keep-criterion deleted from the un-gated card",
            "silent-card-contract",
            on_orch(lambda s: s.replace(KEPT_CRITERION, "Silenced", 1)),
        ),
        # ── criterion 5 [fix/compact-timeout-budget-1022] ───────────────
        (
            "large-material tier dropped from the budget",
            "chain-budget-adaptive",
            on_orch(lambda s: s.replace(LARGE_TIER, "if (declaresNoEffortTiers == true) {", 1)),
        ),
        (
            "material threshold inlined as a bare literal",
            "chain-budget-adaptive",
            on_orch(lambda s: s.replace(
                "materialTokens > " + LARGE_CONST_REF, "materialTokens > 2_000", 1,
            )),
        ),
        (
            "material estimate replaced by a literal at the call site",
            "chain-budget-adaptive",
            on_orch(lambda s: s.replace("            estimatedInput,\n", "            0,\n", 1)),
        ),
        (
            "deadline clamp removed from the budget",
            "chain-budget-adaptive",
            on_orch(lambda s: s.replace(DEADLINE_CLAMP, "return perCandidate", 1)),
        ),
        (
            "timeout no longer records its route",
            "chain-timeout-dedup",
            on_orch(lambda s: s.replace("            " + ROUTE_RECORD + "\n", "", 1)),
        ),
        (
            "route skip removed from the loop",
            "chain-timeout-dedup",
            on_orch(lambda s: s.replace(ROUTE_SKIP, "if (false)", 1)),
        ),
        (
            "route identity loses the model id",
            "chain-timeout-dedup",
            on_orch(lambda s: s.replace(ROUTE_MODEL_ARG, "instanceId = candidate.instanceContext?.id", 1)),
        ),
        (
            "route skip moved after the budget call",
            "chain-timeout-dedup",
            on_orch(lambda s: s.replace(ROUTE_SKIP, "if (false)", 1).replace(
                "        val started = SystemClock.elapsedRealtime()",
                "        " + ROUTE_SKIP + " { continue }\n        val started = SystemClock.elapsedRealtime()",
                1,
            )),
        ),
        # ── wide3 witnesses: the shapes the old criteria let through ─────
        (
            "entry guard gains a second operand that refuses EXHAUSTED",
            "entry-admits-exhausted",
            on_loop(lambda s: s.replace(
                "if (!" + GATE_CALL + ") {",
                "if (!" + GATE_CALL + " || verdict == ContextPolicy.CheckResult.EXHAUSTED) {",
                1,
            )),
        ),
        (
            "EXHAUSTED admission becomes a dead disjunct",
            "gate-admits-exhausted",
            on_compactor(lambda s: s.replace(
                "verdict == ContextPolicy.CheckResult.EXHAUSTED ||",
                "(false && verdict == ContextPolicy.CheckResult.EXHAUSTED) ||",
                1,
            )),
        ),
        (
            "route skip logs the skip but keeps sending",
            "chain-timeout-dedup",
            on_orch(lambda s: s.replace(
                "($label ${candidate.model.displayName}) — route $route already timed out in this chain\",\n"
                "            )\n            continue\n",
                "($label ${candidate.model.displayName}) — route $route already timed out in this chain\",\n"
                "            )\n",
                1,
            )),
        ),
        (
            "silent gate commented out instead of removed",
            "silent-card-contract",
            on_orch(lambda s: s.replace(
                "                if (!silent) {\n                    withContext(Dispatchers.Main) {\n"
                "                        appendSystemInfo(context.getString(R.string.sysmsg_compact_anchor_failed)",
                "                // if (!silent)\n                    withContext(Dispatchers.Main) {\n"
                "                        appendSystemInfo(context.getString(R.string.sysmsg_compact_anchor_failed)",
                1,
            )),
        ),
        # ── batch5 B5-t02 witnesses (each was BLIND_CONFIRMED before) ────
        (
            "a second EXHAUSTED refusal is appended after the gate (order)",
            "entry-admits-exhausted",
            on_loop(lambda s: s.replace(
                "    if (!" + GATE_CALL + ") {\n        return false\n    }",
                "    if (!" + GATE_CALL + ") {\n        return false\n    }\n"
                "    if (verdict == ContextPolicy.CheckResult.EXHAUSTED) return false",
                1,
            )),
        ),
        (
            "EXHAUSTED admission negated with !true (family)",
            "gate-admits-exhausted",
            on_compactor(lambda s: s.replace(
                "verdict == ContextPolicy.CheckResult.EXHAUSTED ||",
                "(!true && verdict == ContextPolicy.CheckResult.EXHAUSTED) ||",
                1,
            )),
        ),
        (
            "escalation guard body gains a narrower early return (dataflow)",
            "escalation-tier-contract",
            on_compactor(lambda s: s.replace(
                "        if (" + TIER_GUARD_COND + ") {\n            return Decision.OK\n        }",
                "        if (" + TIER_GUARD_COND + ") {\n"
                "            if (!escalatedFromOffload) return Decision.OK\n"
                "            return Decision.OK\n        }",
                1,
            )),
        ),
        (
            "pinned capacity evidence killed after it is set (dataflow)",
            "chain-pins-too-large",
            on_orch(lambda s: s.replace(
                "                lastFailure = reduced.first\n                tooLargeFailure = reduced.second",
                "                lastFailure = candidateFailure\n                tooLargeFailure = null",
                1,
            )),
        ),
        (
            "silent guard absorbed by || !false (family)",
            "silent-card-contract",
            on_orch(lambda s: s.replace(
                "            if (!silent) {\n                withContext(Dispatchers.Main) {\n"
                "                    appendSystemInfo(\n                        text = context.getString(R.string.sysmsg_compact_failed",
                "            if (!silent || !false) {\n                withContext(Dispatchers.Main) {\n"
                "                    appendSystemInfo(\n                        text = context.getString(R.string.sysmsg_compact_failed",
                1,
            )),
        ),
        (
            "skip branch terminate wrapped in a dead guard (family)",
            "chain-timeout-dedup",
            on_orch(lambda s: s.replace(
                "($label ${candidate.model.displayName}) — route $route already timed out in this chain\",\n"
                "            )\n            continue\n",
                "($label ${candidate.model.displayName}) — route $route already timed out in this chain\",\n"
                "            )\n            if (!true) continue\n",
                1,
            )),
        ),
    ]

    # compliant fixture first: the real tree must be clean, otherwise every arm below is meaningless.
    baseline = check(loop, compactor, orch)
    if baseline:
        print("❌ selftest baseline is not clean: %s" % baseline)
        return 1

    failed = []
    for name, expected, mutate in arms:
        mutated = mutate(loop, compactor, orch)
        if mutated == (loop, compactor, orch):
            failed.append("%s (mutation did not apply — the anchor moved)" % name)
            continue
        criteria = [c for c, _ in check(*mutated)]
        if expected not in criteria:
            failed.append("%s (expected %r, got %s)" % (name, expected, criteria or "nothing"))

    if failed:
        print("❌ compact-decision gate --selftest:")
        for line in failed:
            print("   - %s" % line)
        return 1
    print("✅ compact-decision gate --selftest: %d arms flip exactly their criterion" % len(arms))
    print(SELFTEST_MARK)
    return 0


def main():
    if "--selftest" in sys.argv:
        root = next((a for a in sys.argv[1:] if not a.startswith("-")), ".")
        return selftest(root)
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    files = load(root)
    if files is None:
        return 1
    problems = check(*files)
    if problems:
        print("❌ compact-decision gate findings:")
        for criterion, msg in problems:
            print("   - [%s] %s" % (criterion, msg))
        return 1
    print("✅ compact-decision gate: the loop entry admits every verdict decide() adjudicates "
          "and does so alone, the gate + escalation keep the tier contract, the chain keeps and "
          "throws its 400 evidence, every repeatable card is silent-gated, and a route that timed "
          "out is skipped before it is paid for")
    return 0


if __name__ == "__main__":
    sys.exit(main())
