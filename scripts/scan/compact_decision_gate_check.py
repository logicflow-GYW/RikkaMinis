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
     bypass used to auto-compact anyway — measured on the real classes:
     window 50 000 / tokens 45 000 / escalated -> AUTO_COMPACT while the same
     coordinate's `check()` is EXHAUSTED.

  3. THE CHAIN THROWS ITS CAPACITY EVIDENCE.
     The outer catch decides "halve and retry" purely on
     `isContextTooLargeError(e)`. When a 400 context-length failure was
     followed by an ordinary one (timeout / 5xx / unavailable), the chain threw
     the ordinary one and the halving retry never fired ([Compact] Splitting:
     0 hits in three days of device logs).

  plus the silent-mode card contract in `compactAll` (criterion 4): a card that
  can be re-emitted on every eligible attempt must be `!silent`-gated, while a
  provably-unreachable one-time invariant break is kept with an explicit
  written criterion instead of being silenced for symmetry.

  plus [fix/compact-timeout-budget-1022] the two levers that keep a long agent
  run alive when the summary chain is latency-bound (criterion 5). 2026-10-22
  device log: a run with no new user turn to fold hands the whole agent
  history to one summary call (estTokens 110 670 / 140 490), and the chain
  then TIMEOUT'd on every candidate at exactly 30000ms — 25 timeouts, 32
  "Compact failed", 119 "folded nothing", and 8 runs ended on
  `finish=length ×3 empty output — giving up` because nothing folded while the
  hard trim could not hold the context under the window. The members that
  timed out were the SAME ones that succeeded at 25.0s / 28.2s / 29.1s that
  day, and they never declare declaresNoEffortTiers (a user-configured relay
  entry cannot) — so the quiet 30s floor was simply below their real latency
  on large material. Two properties, both load-bearing:
    (a) the per-candidate budget takes the summary material size and opens the
        60s tier above the measured threshold (single-source constant, never a
        bare literal, and the chain deadline still clamps);
    (b) a route that timed out inside this chain (host + model, NOT the entry
        id — three tierflow entries sharing https://tierflow.cn died three
        times in one chain) is skipped before it is budgeted or sent, so the
        remaining deadline reaches a candidate that can still answer.

Mechanical criteria:
  1. inside `maybeAutoCompactInLoop`: a single `val verdict = policy.check(...)`
     feeds `ContextCompactor.loopGateOpens(verdict, escalatedFromOffload)`, and
     the pre-fix gate shape is gone;
  2. `ContextCompactor.loopGateOpens` admits EXHAUSTED, and `decide` carries
     `tierHasNoCompactLine` in the escalation guard;
  3. the chain routes failures through `ContextCompactor.chainFailureAfter(...)`
     with `isTooLarge = ::isContextTooLargeError`, and
     `chainTerminalFailure(...)` is applied BEFORE `throw lastFailure`;
  4. each `appendSystemInfo` card in `compactAll` is either preceded by
     `if (!silent)` (between it and the previous card) or carries the explicit
     "Deliberately NOT" criterion.
  5. the summary chain budget is a function of the material size (material
     parameter + the large-material tier reading the single-source constant,
     deadline clamp intact, real estimate at the call site), and a route that
     already timed out in this chain is skipped before the budget call.

Usage: python3 scripts/scan/compact_decision_gate_check.py [repo_root]
       python3 scripts/scan/compact_decision_gate_check.py --selftest
Exit:  0 = guarded, 1 = violation (file:line printed)
"""
import os
import re
import sys

MAIN = "src/android/app/src/main/java/com/rikkaminis/app"
LOOP_FILE = MAIN + "/ui/chat/ChatContextWindowExt.kt"
COMPACTOR_FILE = MAIN + "/conversation/ContextCompactor.kt"
ORCH_FILE = MAIN + "/conversation/CompactOrchestration.kt"

LOOP_BEGIN = "internal suspend fun ChatViewModel.maybeAutoCompactInLoop("
LOOP_END = "internal suspend fun ChatViewModel.awaitAutoCompactIfNeeded()"

GATE_CALL = "ContextCompactor.loopGateOpens(verdict, escalatedFromOffload)"
VERDICT_DECL = "val verdict = policy.check(lastContextTokens, contextWindow)"
OLD_GATE = "if (!inCompactBand && !escalatedFromOffload)"
TIER_GUARD = "tierHasNoCompactLine"
TIER_DECL = "val tierHasNoCompactLine = policy.compactThreshold <= 0 || policy.exhaustedOnly"
# the guard EXPRESSION, not just the identifier: dropping the predicate from the
# condition while leaving the declaration behind must still flip this red.
TIER_GUARD_EXPR = "if (!bandReached && (!escalatedFromOffload || tierHasNoCompactLine))"
RESOURCE_RE = re.compile(r"R\.string\.([A-Za-z0-9_]+)")
CHAIN_PREDICATE = "isTooLarge = ::isContextTooLargeError"
CHAIN_STEP = "ContextCompactor.chainFailureAfter("
CHAIN_TERMINAL = "ContextCompactor.chainTerminalFailure("
SILENT_GATE = "if (!silent)"
KEPT_CRITERION = "Deliberately NOT"

# ── criterion 5 [fix/compact-timeout-budget-1022] anchors ────────────────
# Anchored on decision points (the tier expression, the set membership, the
# recording), not on whole lines: renaming an identifier must fail loudly via
# the missing-needle arm, not silently pass.
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
ROUTE_SKIP = "if (route in timedOutRoutes)"
ROUTE_RECORD = "timedOutRoutes.add(route)"
TIMEOUT_LOG = "summary TIMEOUT after"

# resource ids of the four compactAll cards the silent contract is judged on
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


def mask_code(src):
    """Offset-preserving projection: comment text AND string/char literal
    contents blanked to spaces (newlines kept). Every criterion that asks
    "does this code decide X" must read THIS projection — a criterion satisfied
    by a commented-out line is not a criterion (wide3 `silent-card-contract`)."""
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
    """(cond_start, cond_end, body_start, body_end, cond) per `if (...)`,
    brace-balanced. A single-statement body ends at the line."""
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


def enclosing_conds(ifs, idx):
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


def always_true(cond):
    return any(is_lit(d, "true") for d in split_top(cond, "||"))


def dead_for(cond, needle):
    """True when `needle` sits in the part of `cond` that a literal kills —
    including one level of parenthesised grouping (`|| (false && needle)`)."""
    for disj in split_top(cond, "||"):
        if needle not in disj:
            continue
        if is_lit(disj, "false"):
            return True
        inner = disj.strip()
        if inner.startswith("(") and match_close(inner, 0, "(", ")") == len(inner) - 1:
            if dead_for(inner[1:-1], needle):
                return True
        for con in split_top(disj, "&&"):
            if needle not in con and is_lit(con, "false"):
                return True
    return False


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

    # ── criterion 1: the loop entry admits EXHAUSTED ────────────────────
    if LOOP_BEGIN in loop and LOOP_END in loop:
        region = loop.split(LOOP_BEGIN, 1)[1].split(LOOP_END, 1)[0]
        need(region, VERDICT_DECL, LOOP_FILE, "entry-admits-exhausted",
             "the pressure verdict must be computed once and fed to the gate")
        need(region, GATE_CALL, LOOP_FILE, "entry-admits-exhausted",
             "the pre-gate must be the single-source predicate")
        forbid(region, OLD_GATE, LOOP_FILE, "entry-admits-exhausted",
               "the pre-fix gate refused EXHAUSTED, which is what decide() adjudicates")
        # [wide3 entry-admits-exhausted] The gate call must be the WHOLE
        # decision. `if (!loopGateOpens(…) || verdict == EXHAUSTED) return false`
        # keeps the call and the verdict, so every needle above still matches
        # while the ceiling verdict is refused again — the once-per-action
        # RESCUE channel is shut exactly when it is needed.
        mregion = mask_code(region)
        gate_idx = mregion.find(GATE_CALL)
        if gate_idx >= 0:
            hits = [s for s in if_statements(mregion) if s[0] <= gate_idx <= s[1]]
            hits.sort(key=lambda s: s[0])
            call_ns = re.sub(r"\s+", "", GATE_CALL)
            if not hits:
                problems.append((
                    "entry-admits-exhausted",
                    "%s: the loop gate verdict does not decide an `if` — computed and discarded"
                    % LOOP_FILE,
                ))
            else:
                cond_ns = re.sub(r"\s+", "", hits[-1][4])
                if cond_ns not in ("!" + call_ns, "!(" + call_ns + ")", call_ns):
                    problems.append((
                        "entry-admits-exhausted",
                        "%s:%d: the entry guard must be exactly `!%s`; it is `%s` — an extra "
                        "operand can refuse the ceiling verdict before decide() ever adjudicates "
                        "it, which is the pre-fix gate in new spelling"
                        % (LOOP_FILE, region.count("\n", 0, gate_idx) + 1, GATE_CALL, hits[-1][4].strip()),
                    ))
    else:
        problems.append(("entry-admits-exhausted",
                         "%s: could not isolate %s" % (LOOP_FILE, LOOP_BEGIN)))

    # ── criterion 2: the gate itself + the escalation tier contract ─────
    if "fun loopGateOpens(" in compactor:
        # Read the CODE projection: `// verdict == EXHAUSTED` must not satisfy
        # the criterion, and the admission must be a live disjunct
        # (`|| (false && verdict == EXHAUSTED)` admits nothing).
        cmp_masked = mask_code(compactor)
        gate_body = cmp_masked.split("fun loopGateOpens(", 1)[1][:400]
        need(gate_body, "ContextPolicy.CheckResult.EXHAUSTED", COMPACTOR_FILE,
             "gate-admits-exhausted", "the gate must admit the ceiling verdict")
        expr = gate_body.split("=", 1)[1] if "=" in gate_body else gate_body
        expr = expr.split("{", 1)[0].split(";", 1)[0]
        if dead_for(expr, "EXHAUSTED"):
            problems.append((
                "gate-admits-exhausted",
                "%s: the EXHAUSTED admission is short-circuited by a literal — the gate still "
                "refuses the ceiling verdict, which is the only state decide() turns into RESCUE"
                % COMPACTOR_FILE,
            ))
    else:
        problems.append(("gate-admits-exhausted",
                         "%s: fun loopGateOpens( missing" % COMPACTOR_FILE))
    need(compactor, TIER_DECL, COMPACTOR_FILE, "escalation-tier-contract",
         "the tier contract needs a single-source predicate")
    need(compactor, TIER_GUARD_EXPR, COMPACTOR_FILE, "escalation-tier-contract",
         "the escalation shortcut must consult the tier predicate")

    # ── criterion 3: the chain throws its capacity evidence ─────────────
    need(orch, CHAIN_STEP, ORCH_FILE, "chain-pins-too-large",
         "the reduction must be single-sourced in the pure function")
    need(orch, CHAIN_PREDICATE, ORCH_FILE, "chain-pins-too-large",
         "the reduction must be driven by the real classifier")
    if CHAIN_TERMINAL in orch:
        terminal_at = orch.find(CHAIN_TERMINAL)
        throw_at = orch.rfind("throw lastFailure")
        if throw_at < 0 or terminal_at > throw_at:
            problems.append((
                "chain-pins-too-large",
                "%s: %s must be applied BEFORE `throw lastFailure`" % (ORCH_FILE, CHAIN_TERMINAL),
            ))
    else:
        problems.append(("chain-pins-too-large",
                         "%s: %s missing" % (ORCH_FILE, CHAIN_TERMINAL)))

    # ── criterion 4: the silent-mode card contract ──────────────────────
    # [wide3 silent-card-contract] Two parser-death holes fixed here:
    #   (a) the gate used to be searched in RAW text, so `// if (!silent)` on
    #       the line above the card satisfied it;
    #   (b) it searched a look-back WINDOW, so an unrelated `if (!silent)` from
    #       an earlier card could vouch for a later, unguarded one.
    # Now: the guard must ENCLOSE the card call in the code projection, and the
    # condition must actually test `silent` negatively (a `if (true)` decoy is
    # not a gate).
    cards = segments_between_calls(orch)
    orch_masked = mask_code(orch)
    orch_ifs = if_statements(orch_masked)
    seen = {resource: (start, idx) for start, idx, resource in cards if resource}

    def live_silent_gate(idx):
        for cond in enclosing_conds(orch_ifs, idx):
            if re.search(r"!\s*silent\b", cond) and not always_true(cond):
                return True
        return False

    for resource in GATED_CARDS:
        if resource not in seen:
            problems.append(("silent-card-contract",
                             "%s: card %s not found" % (ORCH_FILE, resource)))
            continue
        start, idx = seen[resource]
        if not live_silent_gate(idx):
            problems.append((
                "silent-card-contract",
                "%s:%d card %s is reachable in silent mode (not enclosed by a live `if (!silent)` "
                "in code — a commented-out gate or a look-back neighbour does not count)"
                % (ORCH_FILE, line_of(orch, "R.string." + resource), resource),
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
                % (ORCH_FILE, line_of(orch, "R.string." + KEPT_CARD), KEPT_CARD),
            ))
        if KEPT_CRITERION not in span:
            problems.append((
                "silent-card-contract",
                "%s:%d %s is not silent-gated but carries no explicit keep-criterion"
                % (ORCH_FILE, line_of(orch, "R.string." + KEPT_CARD), KEPT_CARD),
            ))

    # ── criterion 5 [fix/compact-timeout-budget-1022] ───────────────────
    # (a) the per-candidate budget is a function of the summary material size
    if BUDGET_FN in orch:
        # whole function body (up to the top-level closing brace), not a fixed
        # window: a fixed window silently truncates when the KDoc above the
        # tier grows, and the gate would then report a "missing" tier that is
        # actually present (self-inflicted false red).
        budget_body = orch.split(BUDGET_FN, 1)[1].split("\n}\n", 1)[0]
        need(budget_body, MATERIAL_PARAM, ORCH_FILE, "chain-budget-adaptive",
             "the budget must take the summary material size")
        need(budget_body, LARGE_TIER, ORCH_FILE, "chain-budget-adaptive",
             "a large material must be able to open the 60s tier")
        need(budget_body, LARGE_CONST_REF, ORCH_FILE, "chain-budget-adaptive",
             "the threshold must be the single-source constant, not a bare literal")
        need(budget_body, DEADLINE_CLAMP, ORCH_FILE, "chain-budget-adaptive",
             "the chain deadline must still clamp every candidate")
        if re.search(r"materialTokens\s*>\s*\d", budget_body):
            problems.append((
                "chain-budget-adaptive",
                "%s: the material threshold is inlined as a bare number" % ORCH_FILE,
            ))
    else:
        problems.append(("chain-budget-adaptive",
                         "%s: %s missing" % (ORCH_FILE, BUDGET_FN)))
    if BUDGET_CALL in orch:
        args = orch.split(BUDGET_CALL, 1)[1].split(")", 1)[0]
        parts = [p.strip() for p in args.split(",")]
        second = parts[1].split("=")[-1].strip() if len(parts) > 1 else ""
        if not second or re.fullmatch(r"[0-9_]+L?", second):
            problems.append((
                "chain-budget-adaptive",
                "%s: the budget call must pass the real material estimate (got %r) — a literal pins every "
                "fold into one tier" % (ORCH_FILE, second or "<missing>"),
            ))

    # (b) a route that timed out inside this chain is not paid for twice
    for needle, why in (
        (ROUTE_SET_DECL, "the chain must remember which routes timed out"),
        (KEY_FN, "the route identity must be a single-source function"),
        (ROUTE_DECL, "the candidate route must be resolved at the top of the loop body"),
        (ROUTE_MODEL_ARG, "the route key must carry the model id (host alone is not an identity)"),
        (ROUTE_SKIP, "a route that already timed out must be skipped"),
        (ROUTE_RECORD, "the timeout verdict must record its route"),
    ):
        need(orch, needle, ORCH_FILE, "chain-timeout-dedup", why)
    if ROUTE_SKIP in orch and BUDGET_CALL in orch and ROUTE_RECORD in orch:
        skip_at = orch.find(ROUTE_SKIP)
        budget_at = orch.find(BUDGET_CALL)
        timeout_at = orch.find(TIMEOUT_LOG)
        record_at = orch.find(ROUTE_RECORD)
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

    # [wide3 chain-timeout-dedup] The skip branch must TERMINATE the iteration.
    # `if (route in timedOutRoutes) { AppLogger.info(… "skip" …) }` keeps the
    # needle, prints a line that says "skip", and then pays the same wall again
    # — position is not the decision; the `continue` is.
    skip_env = mask_code(orch)
    skip_idx = skip_env.find(ROUTE_SKIP)
    if skip_idx >= 0:
        paren_at = skip_idx + ROUTE_SKIP.index("(")
        blk = [s for s in if_statements(skip_env) if s[0] == paren_at]
        if not blk:
            problems.append((
                "chain-timeout-dedup",
                "%s: the route skip is not an `if (...) { … }` statement — its body cannot be "
                "checked for a terminating branch" % ORCH_FILE,
            ))
        else:
            p, close, bstart, bend, _cond = blk[0]
            body_txt = skip_env[bstart:bend]
            nested = [s for s in if_statements(skip_env) if bstart <= s[2] and s[3] <= bend]
            terminators = 0
            for m in re.finditer(r"\b(continue|break|return|throw)\b", body_txt):
                guards = enclosing_conds(nested, bstart + m.start())
                if any(is_lit(g, "false") for g in guards):
                    continue
                terminators += 1
            if not terminators:
                problems.append((
                    "chain-timeout-dedup",
                    "%s:%d: the timed-out route's skip branch does not terminate the iteration — it "
                    "logs `skip candidate` and then sends anyway, paying the same dead route twice "
                    "and starving the heterogeneous backups of the chain deadline"
                    % (ORCH_FILE, orch.count("\n", 0, skip_idx) + 1),
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
    print("✅ compact-decision gate: loop entry admits EXHAUSTED, escalation keeps the tier "
          "contract, the chain throws its 400 evidence, and every compactAll card is either "
          "silent-gated or carries a written keep-criterion")
    return 0


if __name__ == "__main__":
    sys.exit(main())
