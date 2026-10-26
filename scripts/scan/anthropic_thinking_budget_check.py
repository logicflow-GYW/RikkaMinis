#!/usr/bin/env python3
"""deep-audit-1046 S-03 (P1) — Anthropic thinking-budget wiring gate.

## Why this gate exists

`thinking.budget_tokens` is defined RELATIVE to `max_tokens`: the API answers
`thinking.budget_tokens must be less than max_tokens` (a deterministic 400)
whenever the pair is illegal. `buildRequestBody` had two readers of two
different numbers:

    body.put("max_tokens", clampOutboundMaxTokens(maxTokens, effectiveMaxOutputTokens(model)))
    val budgetTokens = thinkingBudget(maxTokens, thinkingLevel)      // RAW, unclamped

Upstream `dynamicMaxTokens()` normally lands in range, so the split stayed
invisible on the happy path — but every out-of-band caller (sub-agent budget
frontmatter, synthesized requests — exactly the callers the clamp's own KDoc
says it defends) above the model ceiling emitted e.g. `max_tokens=8192` with
`budget_tokens=65536` and got a 400. Both numbers are individually "correct";
only the PAIR is illegal, which is why no test of either helper alone saw it.

The same block also had a two-way beta-header split:

    if (body.has("thinking")) { adaptive ? effort-2025-11-24 : interleaved-thinking-2025-05-14 }

`thinking.type="disabled"` is the explicit off-switch we send on adaptive
models (their default is to think — iOS ea86dd8a: max_tokens=256 burned
entirely on thinking_tokens, empty body). Sending it AND announcing a thinking
beta contradicts itself: harmless noise at api.anthropic.com, a 400 on relays
that validate the pairing.

## Criteria (all derived from the comment-stripped source, never from line numbers)

  C1  In the function that writes `max_tokens`, every `thinkingBudget(...)`
      argument is the SAME expression as the `max_tokens` value — one value,
      two readers. (Identical text counts for both spellings the task allows:
      a shared local, or the identical call expression.)
  C2  The `budget_tokens` write site is enclosed in an `if` whose condition
      constrains the budget variable from BOTH sides: `> 0` and `<` some
      other identifier. A budget of 0 or >= max_tokens is a 400, and
      `thinkingBudget()` alone cannot promise either (its `>= maxTokens`
      tighten only fires for `maxTokens > 1`).
  C3  The function that turns `body.has("thinking")` into beta flags reads
      `thinking.type` and distinguishes `"disabled"` (a `== "disabled"` /
      `!= "disabled"` comparison, or a `"disabled" ->` `when` branch). A
      whitelist that simply omits it is NOT accepted: the exclusion must be
      visible where the header is built.

[deep-audit-1047 A-01] The SAME pair contract, one provider over. OpenAI's
builder wrote the CLAMPED value into the body but handed the RAW request value
to the thinking injector, so the two readers disagree exactly when the clamp
fires:

  C-OAI-1  In OpenAIProvider.kt the value written as `max_tokens` /
           `max_completion_tokens` must BE the 3rd argument of
           `injectThinkingParams(...)` in the same function. Derived from the
           write tokens: renaming the local changes nothing, reverting it to the
           raw parameter is red.
  C-OAI-2  In ThinkingRuleResolver.kt the `maxTokens < 2 → budget = 0` guard
           must not sit inside a `maxTokens > 0` block. The provider floors the
           wire value at 1 (`clampOutboundMaxTokens`), so a non-positive request
           value means "no room", not "skip the clamp" — the old nesting let the
           raw budget through against a wire max_tokens of 1.

Deliberate strictness (fail-closed): C2 requires the guard to be a
brace-bodied `if` in the same function as the write, and it requires the guard
to be VISIBLE AT THE WRITE SITE rather than trusting `thinkingBudget()`'s
internal tighten. Both are recorded as residuals in the fix report — a
refactor that keeps the contract in a different shape must edit this gate on
purpose rather than silently pass.

Usage: python3 scripts/scan/anthropic_thinking_budget_check.py [ROOT]
       python3 scripts/scan/anthropic_thinking_budget_check.py --selftest
Exit:  0 = clean, 1 = violation(s) or selftest failure, 2 = usage/ROT
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import KotlinLexError, mask_code, mask_comments  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool,
    const_value,
    enclosing_conditions as shared_enclosing_conditions,
    if_blocks,
    match_bracket,
    split_top,
    stmt_spans,
    strip_parens,
    when_branches,
)
from _kotlin_valueflow import (  # noqa: E402
    call_args,
    contains_ident,
    expr_end,
    guard_feasible,
    is_single_assignment,
    path_feasible,
    value_sources,
)

REL = "src/android/app/src/main/java/com/rikkaminis/app/provider/anthropic/AnthropicProvider.kt"
ROT_MARK = "class AnthropicProvider"

# ── Kotlin-aware source scanning ─────────────────────────────────────────────
# Criteria below are matched on comment-masked text with string literals intact
# (several anchors ARE string literals: put("max_tokens", …)). The view comes
# from the shared lexer, which is length- and line-preserving, so every line
# number this gate prints is a real line and a `}` inside a literal can no
# longer move a brace counter.
#
# [batch5-t05] The hand-written scanners that used to live here — `skip_string`,
# `skip_template`, `strip_comments`, `brace_body`, `paren_body`,
# `split_top_level_args`, and the brace walkers behind `enclosing_*` — are gone.
# The same work is done by `_kotlin_lexer` / `_kotlin_ast` /
# `_kotlin_valueflow`; only the *criteria* live in this file now.

def strip_comments(src):
    """Comment-masked view: string literals and newlines intact, length equal."""
    return mask_comments(src)


FUN_HEAD_RE = re.compile(r"\bfun\s+(?:<[^>\n]*>\s*)?(?:[\w.]+\.)?([A-Za-z_]\w*)\s*\(")


def brace_body(text, start):
    """Balanced `{...}` starting at `start`; -> (body incl. braces, end index).

    Fail-closed: an unbalanced run returns the rest of the text with end = -1,
    so a caller can never mistake a truncated body for a complete one.
    """
    close = match_bracket(text, start)
    if close < 0:
        return text[start:], -1
    return text[start:close + 1], close + 1


def iter_functions(text):
    """-> [(name, brace_at, body)] for every block-bodied `fun` in `text`.

    Derived, not listed: no criterion below names the functions it polices, so
    renaming (or splitting) the request builder keeps the gate honest. The
    signature is closed by the shared bracket matcher, so a default-argument
    lambda cannot hand the body brace a wrong start.
    """
    out = []
    for m in FUN_HEAD_RE.finditer(text):
        close = match_bracket(text, m.end() - 1)
        if close < 0:
            continue
        k = close + 1
        brace_at = text.find("{", k)
        if brace_at < 0:
            continue
        # `fun f(): T = expr` / `fun f() = expr` are expression-bodied.
        eq = text.find("=", k)
        if 0 <= eq < brace_at:
            continue
        body, body_end = brace_body(text, brace_at)
        if body_end < 0:
            continue
        out.append((m.group(1), brace_at, body))
    return out


def split_top_level_args(text, open_paren_index):
    """-> raw argument expressions of the call whose `(` is at [open_paren_index]."""
    return [text[a:b].strip() for a, b in call_args(text, open_paren_index)]


def find_calls(text, callee_re):
    """-> [(call_start, [args])] for every `<callee>(` in `text`."""
    out = []
    for m in callee_re.finditer(text):
        # The opening paren is the first one after the callee name (the regex
        # may end on a string literal, e.g. put("max_tokens", …), so searching
        # forward from m.end() would land on the NEXT call's paren).
        paren_at = text.find("(", m.start())
        if paren_at < 0:
            continue
        out.append((m.start(), split_top_level_args(text, paren_at)))
    return out


MAX_TOKENS_CALL = re.compile(r"\bput\(\s*\"max_tokens\"")
BUDGET_CALL = re.compile(r"\bput\(\s*\"budget_tokens\"")
BUDGET_FN = re.compile(r"(?<![\w.])thinkingBudget\s*\(")
THINKING_HAS = re.compile(r"\bhas\(\s*\"thinking\"\s*\)")
IF_RE = re.compile(r"\bif\s*\(")
DISABLED_COMPARE = re.compile(
    r"""(?:==|!=)\s*"disabled"|"disabled"\s*(?:==|!=)|"disabled"\s*->"""
)


def norm(expr):
    return re.sub(r"\s+", "", expr)


BETA_ADD_RE = re.compile(r"\.(?:add|plusAssign|addAll)\s*\(")
BETA_TOKENS = ("interleaved-thinking", "effort-")

_STR_LIT = r'"(?:[^"\\\n]|\\.)*"'
_TMPL_CONST = re.compile(r'"\$\{(' + _STR_LIT + r')\}"')
_PLUS_CONST = re.compile(r'(' + _STR_LIT + r')\s*\+\s*(' + _STR_LIT + r')')


def fold_strings(text):
    """Fold constant string concatenation / literal interpolation.

    [batch5-t05 G09-A3] `plusAssign("interleaved-" + "thinking-2025-05-14")` and
    `plusAssign("${"interleaved"}-thinking-2025-05-14")` contain no
    `interleaved-thinking` substring, yet both are that exact literal to the
    compiler — the token blacklist was reading source text, not values. Folding
    only ever rewrites an equivalent spelling into the canonical one, so it can
    produce a red but never a spurious green.
    """
    prev = None
    while prev != text:
        prev = text
        text = _TMPL_CONST.sub(lambda m: '"' + m.group(1)[1:-1] + '"', text)
        text = _PLUS_CONST.sub(
            lambda m: '"' + m.group(1)[1:-1] + m.group(2)[1:-1] + '"', text)
    return text


def _beta_leak(body):
    """The offending snippet when [body] announces a thinking beta, else None."""
    folded = fold_strings(body)
    if BETA_ADD_RE.search(folded) or any(t in folded for t in BETA_TOKENS):
        return re.sub(r"\s+", " ", body).strip()[:60]
    return None


def disabled_path_adds_beta(text):
    """-> offending snippet when the `"disabled"` path still adds a beta flag.

    `"disabled" -> Unit` is the only correct arm: the body asks the API to stop
    thinking, so announcing a thinking beta contradicts the request body. The
    old criterion only proved the string `"disabled"` was COMPARED somewhere —
    `"disabled" -> betaFlags.add("interleaved-thinking-2025-05-14")` kept that
    token and shipped the contradiction.
    """
    for m in re.finditer(r'"disabled"\s*->', text):
        k = m.end()
        while k < len(text) and text[k] in " \t":
            k += 1
        if k < len(text) and text[k] == "{":
            body, _end = brace_body(text, k)
        else:
            nl = text.find("\n", k)
            body = text[k:nl if nl >= 0 else len(text)]
        leak = _beta_leak(body)
        if leak:
            return leak
    # `if (type == "disabled") betaFlags.add(...)` — the same contradiction in
    # comparison form.
    for _cond, t_start, t_end, _e_start, _e_end in if_blocks(text):
        if not re.search(r'==\s*"disabled"', fold_strings(_cond)):
            continue
        leak = _beta_leak(text[t_start:t_end])
        if leak:
            return re.sub(r"\s+", " ", _cond).strip()
    return None


def enclosing_conditions(text, pos):
    """`if` and `when`-arm conditions that enclose `pos`.

    Delegates to the shared control-flow view, which covers braceless
    `if (x) if (y) { … }` chains and `when { … }` arms (including
    `when (false) { … }`, which is the same dead guard as `if (false)`).
    """
    return [cond for _kind, cond in shared_enclosing_conditions(text, pos)]


# ── Guard liveness: an anchor that is PRESENT but SHORT-CIRCUITED ───────────
# [batch5-t05] The old whitelist (`&& true`, `|| false`, `1 == 1`) WAS the
# defect: every equivalent spelling — `(1 == 1 || b)`, `!true`, `1 == 2 && b`,
# `x && !x` — is the same "anchor present, decision deleted" shape. The shared
# `guard_feasible` / `const_bool` fold them all, so there is no list to extend.

def required_atoms(cond):
    """Atoms that MUST hold whenever [cond] holds.

    `A && B` -> atoms(A) ∪ atoms(B); `A || B` -> the surviving side only when the
    other side is constant-false; a constant-true disjunct makes the whole
    condition vacuous, so NOTHING inside it is required; a bare atom is itself.

    This is what distinguishes "the comparison is written" from "the comparison
    decides": in `if (budgetTokens > 0 && (1 == 1 || budgetTokens < capped))` the
    `< capped` atom is not required, so the guard decides nothing.
    """
    c = strip_parens(cond)
    if not c:
        return []
    if const_bool(c) is not None:
        return []
    parts = split_top(c, ("||",))
    if len(parts) > 1:
        vals = [(a, b, const_bool(c[a:b])) for a, b in parts]
        if any(v is True for _a, _b, v in vals):
            return []
        alive = [(a, b) for a, b, v in vals if v is not False]
        if len(alive) == 1:
            return required_atoms(c[alive[0][0]:alive[0][1]])
        return []
    parts = split_top(c, ("&&",))
    if len(parts) > 1:
        out = []
        for a, b in parts:
            out.extend(required_atoms(c[a:b]))
        return out
    return [c]


def _short_circuit_detail(conds):
    """Human-readable quote of the guard that keeps its tokens but not its teeth."""
    for c in conds:
        flat = re.sub(r"\s+", " ", c).strip()
        if not guard_feasible(c) or not required_atoms(c):
            return flat
    return re.sub(r"\s+", " ", conds[0]).strip() if conds else ""


def check_source(src):
    """-> [(criterion, ok, detail)] on comment-stripped source."""
    stripped = strip_comments(src)
    funcs = iter_functions(stripped)
    results = []

    # ── C1: one value, two readers ──────────────────────────────────────────
    c1_ok = True
    c1_detail = "no max_tokens writer found (update this gate deliberately)"
    checked_any = False
    for name, _start, body in funcs:
        max_calls = find_calls(body, MAX_TOKENS_CALL)
        if not max_calls:
            continue
        checked_any = True
        budget_calls = find_calls(body, BUDGET_FN)
        if len(max_calls) != 1:
            c1_ok = False
            c1_detail = "%s writes max_tokens %d times — cannot derive a single wire value" % (
                name, len(max_calls))
            continue
        max_expr = max_calls[0][1][-1] if max_calls[0][1] else ""
        if not budget_calls:
            c1_ok = False
            c1_detail = "no thinkingBudget(...) call in %s" % name
            continue
        for _pos, args in budget_calls:
            arg = args[0] if args else ""
            if norm(arg) != norm(max_expr):
                c1_ok = False
                c1_detail = "budget derives from `%s` but max_tokens writes `%s`" % (
                    arg.strip(), max_expr.strip())
                continue
            # [batch5-t05 G09-A1] Equal TEXT is not equal VALUE. Inserting
            # `var cappedMaxTokens = maxTokens` in front of the budget call keeps
            # both readers spelling the same name while the nearer binding (or
            # the re-assignment) silently hands the budget the raw request value
            # again. Lexical identity is the claim this criterion makes, so the
            # identifier must have exactly one binding in the function.
            token = arg.strip()
            if re.fullmatch(r"[A-Za-z_]\w*", token) and not is_single_assignment(body, token):
                c1_ok = False
                c1_detail = (
                    "`%s` is re-declared / re-assigned / shadowed inside %s — both readers "
                    "print the same name but no longer provably resolve to the same value "
                    "(a nearer `var %s = <raw>` hands the budget the unclamped request "
                    "value)" % (token, name, token))
    if not checked_any:
        c1_ok = False
    results.append((
        "C1 max_tokens and thinkingBudget read the same value",
        c1_ok, c1_detail,
    ))

    # ── C2: the budget write is guarded on both sides ───────────────────────
    c2_ok = True
    c2_detail = "no budget_tokens write site found (update this gate deliberately)"
    seen_write = False
    for name, _start, body in funcs:
        for pos, args in find_calls(body, BUDGET_CALL):
            seen_write = True
            value = args[-1].strip() if args else ""
            if not re.fullmatch(r"[A-Za-z_]\w*", value):
                c2_ok = False
                c2_detail = "budget_tokens written from a non-identifier `%s` — no guard can cover it" % value
                continue
            gt = re.compile(r"\b%s\s*>\s*0\b|\b0\s*<\s*%s\b" % (re.escape(value), re.escape(value)))
            lt = re.compile(r"\b%s\s*<\s*[A-Za-z_]\w*" % re.escape(value))
            conds = enclosing_conditions(body, pos)
            if not conds:
                c2_ok = False
                c2_detail = "budget_tokens written outside any brace-bodied if (%s)" % name
                continue
            # [batch5-t05 G09-A2] A guard is only a guard when the bounds are
            # REQUIRED for the branch to run. `a && (true || b)` keeps both
            # halves and deletes the decision; so does `a && (1 == 1 || b)`,
            # `a && (!false || b)` … the old whitelist enumerated spellings and
            # therefore lost this arm of the family on the very next variant.
            # `required_atoms` folds through `const_bool`, so nothing is left to
            # enumerate.
            if not guard_feasible(" && ".join("(%s)" % c for c in conds)):
                c2_ok = False
                c2_detail = (
                    "the guard(s) around the budget_tokens write can never be true (`%s`) — "
                    "the clamp is present but unreachable, so the write is gone and the "
                    "budget ships as-is" % _short_circuit_detail(conds))
                continue
            atoms = []
            for c in conds:
                atoms.extend(required_atoms(c))
            if not any(gt.search(a) for a in atoms):
                c2_ok = False
                c2_detail = "no DECIDING `%s > 0` guard encloses the budget_tokens write (`%s`)" % (
                    value, _short_circuit_detail(conds))
            elif not any(lt.search(a) for a in atoms):
                c2_ok = False
                c2_detail = (
                    "no DECIDING `%s < <max>` guard encloses the budget_tokens write — the "
                    "comparison is written but short-circuited, so the branch runs as if the "
                    "guard were absent (`%s`)" % (value, _short_circuit_detail(conds)))
    if not seen_write:
        c2_ok = False
    results.append((
        "C2 budget_tokens is written only under `> 0` AND `< max`",
        c2_ok, c2_detail,
    ))

    # ── C3: the beta branch distinguishes thinking.type == "disabled" ───────
    c3_ok = True
    c3_detail = "no function turns body.has(\"thinking\") into beta flags (update this gate deliberately)"
    seen_header = False
    for name, _start, body in funcs:
        m = THINKING_HAS.search(body)
        if not m:
            continue
        seen_header = True
        tail = body[m.start():]
        if not re.search(r"\b(?:opt|get)String\(\s*\"type\"", tail):
            c3_ok = False
            c3_detail = "%s reads body.has(\"thinking\") without reading thinking.type" % name
            continue
        if not DISABLED_COMPARE.search(tail):
            c3_ok = False
            c3_detail = "%s does not distinguish thinking.type == \"disabled\"" % name
            continue
        # Distinguishing it is not enough: the disabled path must not announce a
        # thinking beta (`"disabled" -> Unit`).
        leaked = disabled_path_adds_beta(tail)
        if leaked is not None:
            c3_ok = False
            c3_detail = ("%s still adds a thinking beta on the disabled path (`%s`) — the body "
                         "asks for thinking off while the header announces it on" % (name, leaked))
    if not seen_header:
        c3_ok = False
    results.append((
        "C3 beta flags distinguish thinking.type == \"disabled\"",
        c3_ok, c3_detail,
    ))

    return results


# ── [deep-audit-1047 A-01] The OpenAI pair contract ─────────────────────────
# Same defect class as C1 above, one provider over: `max_tokens` and
# `thinking_budget` are defined RELATIVE to each other, and OpenAI's builder
# wrote the CLAMPED value into the body while handing the RAW request value to
# the thinking injector. Neither number is wrong alone; only the pair is illegal
# — derivable only by looking at both readers, which is why no test of either
# helper ever saw it.
#
# C-OAI-1 (same source): in OpenAIProvider.kt, the value written as
#     `max_tokens` / `max_completion_tokens` must BE the 3rd argument of
#     `injectThinkingParams(...)`. Derived from the write tokens, never from the
#     local's name — renaming `safeMaxTokens` changes nothing, reverting it to
#     the raw parameter is red.
# C-OAI-2 (guard externalized): the `maxTokens < 2 → budget = 0` guard in
#     ThinkingRuleResolver.kt must NOT sit inside a `maxTokens > 0` block. The
#     provider floors the wire value at 1 (clampOutboundMaxTokens), so a
#     non-positive request value means "no room", not "skip the clamp".
REL_OAI = os.path.join(
    "src", "android", "app", "src", "main", "java", "com", "rikkaminis", "app",
    "provider", "openai", "OpenAIProvider.kt")
REL_RESOLVER = os.path.join(
    "src", "android", "app", "src", "main", "java", "com", "rikkaminis", "app",
    "provider", "thinking", "ThinkingRuleResolver.kt")
OAI_ROT_MARK = "class OpenAIProvider"
RESOLVER_ROT_MARK = "object ThinkingRuleResolver"

PUT_ANY_MAX_TOKENS = re.compile(r"\bput\(\s*\"(?:max_tokens|max_completion_tokens)\"")
INJECT_CALL = re.compile(r"(?<![\w.])injectThinkingParams\s*\(")
MAX_TOKENS_GT_ZERO = re.compile(r"\bmaxTokens\s*>\s*0\b")
ZERO_ASSIGN = re.compile(r"(?<![=!<>])[A-Za-z_]\w*\s*=\s*0\b")
LT_TWO = re.compile(r"<\s*2\b")


def check_openai_pair(src):
    """C-OAI-1 — every max_tokens writer must feed the thinking injector.

    Derived: the write VALUE tokens come from the `put(...)` calls themselves,
    the 3rd argument from the `injectThinkingParams(...)` call in the SAME
    function. No member name, no line number."""
    stripped = strip_comments(src)
    violations = []
    writes = 0
    for name, _start, body in iter_functions(stripped):
        wcalls = find_calls(body, PUT_ANY_MAX_TOKENS)
        if not wcalls:
            continue
        writes += len(wcalls)
        injects = find_calls(body, INJECT_CALL)
        if not injects:
            violations.append(
                "%s writes max_tokens but never calls injectThinkingParams — the thinking "
                "budget cannot be derived from the value that goes on the wire" % name)
            continue
        for _pos, wargs in wcalls:
            wrote = wargs[-1].strip() if len(wargs) > 1 else ""
            for _ipos, iargs in injects:
                third = iargs[2].strip() if len(iargs) > 2 else ""
                if not third:
                    violations.append(
                        "%s: injectThinkingParams has no max-tokens argument — the budget "
                        "is computed without knowing the wire value" % name)
                elif norm(third) != norm(wrote):
                    violations.append(
                        "%s: thinking budget derives from `%s` but max_tokens writes `%s` — "
                        "two readers of two different numbers (a budget above the wire "
                        "max_tokens is a deterministic 400)" % (name, third, wrote))
                elif (re.fullmatch(r"[A-Za-z_]\w*", third)
                      and not is_single_assignment(body, third)):
                    # [batch5-t05 G09-A4] Both readers spell the same name here —
                    # but the write and the injector can sit inside different
                    # blocks, and a nearer `val safeMaxTokens = maxTokens` inside
                    # the injector's block re-binds the name for ONE of them. The
                    # pair then disagrees while the source text does not.
                    violations.append(
                        "%s: `%s` is re-declared / re-assigned / shadowed inside the "
                        "function — the wire value and the budget source print the same "
                        "name but no longer provably resolve to the same binding (a "
                        "nearer `val %s = <raw>` inside the injector's block hands it "
                        "the unclamped request value)" % (name, third, third))
    if writes == 0:
        violations.append(
            "no max_tokens write site found in OpenAIProvider.kt — refusing to report green "
            "(the serialization shape moved; update this criterion deliberately)")
    # One message per distinct (writer, wire value vs budget source) pair: the two
    # `put` spellings usually share one injector call.
    return list(dict.fromkeys(violations))


def check_resolver_guard(src):
    """C-OAI-2 — the `maxTokens < 2` guard must not nest inside `maxTokens > 0`.

    Derived by shared control flow: for every `if` whose condition tests `< 2`
    and whose body zeroes a variable, (a) the comparison must be REQUIRED for
    the body to run and (b) no ENCLOSING `if` / `when` arm may test
    `maxTokens > 0`."""
    stripped = strip_comments(src)
    violations = []
    guards = 0
    for cond, t_start, t_end, _e_start, _e_end in if_blocks(stripped):
        body = stripped[t_start:t_end]
        if not LT_TWO.search(cond) or not ZERO_ASSIGN.search(body):
            continue
        guards += 1
        line = stripped[:t_start].count("\n") + 1
        inner = cond
        flat = re.sub(r"\s+", " ", inner).strip()
        # (a) the guard must DECIDE. `if (false && maxTokens < 2)` and
        #     `if ((1 == 2) && maxTokens < 2)` keep the comparison and the
        #     `budget = 0` block while clamping nothing — the old check only
        #     knew the bare literals `false` / `true`, so the arithmetic
        #     spelling of the same constant walked through (witness G09-A5).
        if not guard_feasible(inner):
            violations.append(
                "ThinkingRuleResolver.kt:%d the `< 2 → 0` guard can never be true (`%s`) — "
                "the clamp is present but unreachable, so a non-positive request value "
                "ships its raw budget against a wire max_tokens of 1" % (line, flat))
        elif not any(LT_TWO.search(a) for a in required_atoms(inner)):
            violations.append(
                "ThinkingRuleResolver.kt:%d the `< 2 → 0` guard is short-circuited (`%s`) — "
                "the comparison is written but the branch runs as if it were absent, so a "
                "non-positive request value ships its raw budget against a wire "
                "max_tokens of 1" % (line, flat))
        # (b) last write wins: `budget = 0; budget = 4096` in the same block keeps
        #     the `= 0` token while restoring the defect.
        zeroed = re.search(r"([A-Za-z_]\w*)\s*=\s*0\b", body)
        if zeroed:
            var = zeroed.group(1)
            writes = re.findall(r"\b%s\s*=\s*([^;\n]+)" % re.escape(var), body)
            if writes:
                last = writes[-1].strip().rstrip(";")
                if not re.fullmatch(r"0", last):
                    violations.append(
                        "ThinkingRuleResolver.kt:%d the `< 2` guard block ends by writing "
                        "`%s = %s` — the zeroing is dead (last write wins), so the raw "
                        "budget still reaches the wire" % (line, var, last))
        # (c) reachability: a `maxTokens > 0` enclosure (if OR when-arm) makes the
        #     guard unreachable exactly when the value is non-positive.
        for outer in enclosing_conditions(stripped, t_start - 1):
            if MAX_TOKENS_GT_ZERO.search(outer):
                violations.append(
                    "ThinkingRuleResolver.kt:%d `maxTokens < 2` guard nests inside "
                    "`%s` — a non-positive request value then skips the clamp entirely "
                    "while the provider floors the wire value at 1, so the raw budget "
                    "reaches the wire above max_tokens"
                    % (line, re.sub(r"\s+", " ", outer).strip()))
    if guards == 0:
        violations.append(
            "ThinkingRuleResolver.kt: no `< 2 → 0` budget guard found — refusing to report "
            "green (the clamp moved; update this criterion deliberately)")
    return violations


# ── [R3-t15] the three seams the pair contract stopped at ────────────────────
# Round 3 attacked the GAPS between gates. All three criteria below are the same
# family as C1/C-OAI-1 — "the value on the wire and the value the budget is
# derived from" — one hop further along the data flow than the earlier rounds
# looked:
#
#   C-OAI-3  C-OAI-1 ties the wire `max_tokens` to `injectThinkingParams`'s 3rd
#            argument. What the injector then DOES with it was unpoliced: the
#            resolver derives the budget from `ThinkingResolveContext.maxTokens`,
#            so the argument has to arrive there unchanged. A literal (or a
#            nearer re-binding of the same name) keeps every anchor on the call
#            site intact and ships `thinking_budget > max_tokens`.
#   C4       C1/C2 police the CALLER (`thinkingBudget(cappedMaxTokens, …)` and
#            the `> 0 && < max` guard) but never the helper's own body: `return
#            cap` keeps the call legal, makes the guard's `< capped` half
#            always-false and silently drops the whole thinking block.
#   C5       Extended thinking also requires `temperature == 1`. The block that
#            writes `budget_tokens` therefore has a paired constant: writing any
#            other temperature next to it is a deterministic 400 that no budget
#            criterion can see.
INJECT_DEF_RE = re.compile(r"\bfun\s+injectThinkingParams\s*\(")
CTX_CALL_RE = re.compile(r"(?<![\w.])ThinkingResolveContext\s*\(")
CTX_MAX_FIELD_RE = re.compile(r"^\s*maxTokens\s*=")
BUDGET_DEF_RE = re.compile(r"\bfun\s+thinkingBudget\s*\(")
TEMPERATURE_CALL = re.compile(r"\bput\(\s*\"temperature\"")
BOUND_CALLS = ("minOf", "min", "coerceAtMost", "coerceIn")


def _param_names(sig_inner):
    """Parameter names of a Kotlin parameter list, positionally."""
    out = []
    for a, b in split_top(sig_inner, (",",)):
        part = sig_inner[a:b].strip()
        if not part:
            continue
        out.append(part.split(":")[0].strip())
    return out


def _body_of(text, after):
    """(inner body, brace position, ok) for the block that follows `after`."""
    brace = text.find("{", after)
    if brace < 0:
        return "", -1, False
    body, end = brace_body(text, brace)
    if end < 0:
        return "", brace, False
    return body[1:-1], brace, True


def _rebindings(code, name):
    """Positions where `name` stops denoting the same value.

    `code` MUST be the code view (string contents blanked): the injector logs
    `"… maxTokens=$maxTokens"`, and reading that as an assignment would make the
    criterion red on the pristine tree. A named argument (`maxTokens = …` inside
    a constructor call, preceded by `,`) is not an assignment either.
    """
    out = [m.start() for m in
           re.finditer(r"\b(?:val|var)\s+%s(?![\w`])" % re.escape(name), code)]
    for m in re.finditer(r"(?<![\w`])%s(?![\w`])\s*=(?!=)" % re.escape(name), code):
        k = m.start() - 1
        while k >= 0 and code[k] in " \t\r\n":
            k -= 1
        if k >= 0 and code[k] == ",":
            continue
        out.append(m.start())
    return sorted(set(out))


def check_inject_forwarding(src):
    """C-OAI-3 — the injector must hand ITS OWN wire argument to the resolver."""
    visible = strip_comments(src)                 # literals intact
    try:
        code = mask_code(src)                     # literals blanked
    except KotlinLexError as e:
        return ["OpenAIProvider.kt: the injector's body does not lex (%s) — fail-closed, a "
                "shape this criterion cannot read must never read green" % e]
    m = INJECT_DEF_RE.search(visible)
    if not m:
        return ["OpenAIProvider.kt: no `fun injectThinkingParams(` found — the injector that "
                "carries the wire max_tokens into the rule engine cannot be read (fail-closed)"]
    p_open = m.end() - 1
    p_close = match_bracket(visible, p_open)
    if p_close < 0:
        return ["OpenAIProvider.kt: `injectThinkingParams` signature is unbalanced "
                "(fail-closed)"]
    params = _param_names(visible[p_open + 1:p_close])
    if len(params) < 3:
        return ["OpenAIProvider.kt: `injectThinkingParams` takes %d parameter(s) — the value "
                "written as max_tokens cannot be its 3rd argument (fail-closed)"
                % len(params)]
    wire = params[2]
    body, brace, ok = _body_of(visible, p_close + 1)
    if not ok:
        return ["OpenAIProvider.kt: `injectThinkingParams` body is unbalanced (fail-closed)"]
    body_code = code[brace + 1:brace + 1 + len(body)]
    out = []
    ctxs = find_calls(body, CTX_CALL_RE)
    if not ctxs:
        return ["OpenAIProvider.kt: `injectThinkingParams` builds no ThinkingResolveContext — "
                "the rule engine never learns the wire max_tokens, so `thinking_budget` is "
                "derived from nothing (fail-closed)"]
    for _pos, args in ctxs:
        rhs = None
        for a in args:
            if CTX_MAX_FIELD_RE.match(a):
                rhs = a.split("=", 1)[1].strip()
        if rhs is None:
            out.append("OpenAIProvider.kt: ThinkingResolveContext is built without a "
                       "`maxTokens =` ceiling — the budget is computed without the wire value")
            continue
        if norm(rhs) != norm(wire):
            out.append(
                "OpenAIProvider.kt: `injectThinkingParams` forwards `%s` as the budget "
                "ceiling, but the wire max_tokens comes from its 3rd argument `%s` — the "
                "pair's two readers disagree, so a budget above the wire max_tokens is a "
                "deterministic 400" % (re.sub(r"\s+", " ", rhs), wire))
            continue
        sites = _rebindings(body_code, wire)
        if sites:
            out.append(
                "OpenAIProvider.kt: `%s` is re-declared / re-assigned inside "
                "`injectThinkingParams` — the ceiling prints the parameter's name but no "
                "longer resolves to the wire value" % wire)
    return out


def _single_bindings(body):
    """name -> initializer for every `val`/`var` that provably has one binding."""
    out = {}
    for m in re.finditer(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+?)?\s*=(?!=)", body):
        name = m.group(1)
        end = expr_end(body, m.end())
        expr = body[m.end():end].strip()
        if name in out:
            out[name] = None            # ambiguous: never followed
        else:
            out[name] = expr
    for name in [k for k, v in out.items() if v is None]:
        del out[name]
    for name in list(out):
        if not is_single_assignment(body, name):
            del out[name]
    return out


def _split_else(text):
    """(then_part, else_part) at bracket depth 0; else (text, None)."""
    depth = 0
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif depth == 0 and text.startswith("else", i):
            before = text[i - 1] if i else ""
            after = text[i + 4] if i + 4 < n else ""
            if not (before.isalnum() or before == "_") and \
                    not (after.isalnum() or after == "_"):
                return text[:i], text[i + 4:]
        i += 1
    return text, None


def _depth_at(text, pos):
    """Bracket depth at `pos` — a clamp nested in a `when` arm is not a clamp."""
    depth = 0
    for ch in text[:pos]:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
    return depth


def _bounded_by(expr, cap, bindings, depth=0):
    """Is `expr` provably at or below `cap` for the returned value?

    Accepted shapes (each one is a binding the compiler enforces):
      * `cap - <k>` (k >= 1)                    — the strict-less tighten
      * `minOf(<anything>, cap)` / `min(…)` / `x.coerceAtMost(cap)` / `coerceIn`
      * `if (<cond mentioning cap>) <bounded> else <bounded>`
      * an identifier whose ONE initializer is one of the above
    A `when`/literal/`cap` alone is not bounded — that is the witness's shape.
    """
    e = strip_parens(expr.strip())
    if not e or depth > 5:
        return False
    m = re.match(r"if\s*\(", e)
    if m:
        close = match_bracket(e, m.end() - 1)
        if not (0 <= close < len(e)):
            return False
        cond = e[m.end():close]
        then_part, else_part = _split_else(e[close + 1:].strip())
        if else_part is None:
            return False
        return (contains_ident(cond, cap)
                and _bounded_by(then_part, cap, bindings, depth + 1)
                and _bounded_by(else_part, cap, bindings, depth + 1))
    for src in value_sources(e):
        s = strip_parens(src.strip())
        if not s:
            continue
        mm = re.fullmatch(r"%s\s*-\s*(\d+)" % re.escape(cap), s)
        if mm and int(mm.group(1)) >= 1:
            return True
        if re.fullmatch(r"[A-Za-z_]\w*", s) and s in bindings:
            if _bounded_by(bindings[s], cap, bindings, depth + 1):
                return True
        for cname in BOUND_CALLS:
            for cm in re.finditer(r"(?<![\w.])%s\s*\(" % cname, s):
                if _depth_at(s, cm.start()) != 0:
                    continue    # a clamp inside a `when` arm bounds one arm only
                for a, b in call_args(s, cm.end() - 1):
                    if contains_ident(s[a:b], cap):
                        return True
    return False


def check_budget_helper(src):
    """C4 — the budget helper must clamp below ITS OWN max argument."""
    stripped = strip_comments(src)
    m = BUDGET_DEF_RE.search(stripped)
    if not m:
        return ["AnthropicProvider.kt: no `fun thinkingBudget(` found — the helper that must "
                "keep budget_tokens below max_tokens cannot be read (fail-closed)"]
    p_open = m.end() - 1
    p_close = match_bracket(stripped, p_open)
    if p_close < 0:
        return ["AnthropicProvider.kt: `thinkingBudget` signature is unbalanced (fail-closed)"]
    params = _param_names(stripped[p_open + 1:p_close])
    if not params:
        return ["AnthropicProvider.kt: `thinkingBudget` takes no parameter — the wire max is "
                "not reachable from inside it (fail-closed)"]
    cap = params[0]
    body, _brace, ok = _body_of(stripped, p_close + 1)
    if not ok:
        return ["AnthropicProvider.kt: `thinkingBudget` body is unbalanced (fail-closed)"]
    bindings = _single_bindings(body)
    returns = []
    for s, e in stmt_spans(body):
        stmt = body[s:e].strip()
        if stmt.startswith("return"):
            returns.append(stmt[len("return"):].strip())
    if not returns:                      # expression-bodied helper
        returns = [body.strip()]
    out = []
    for r in returns:
        if not _bounded_by(r, cap, bindings):
            out.append(
                "AnthropicProvider.kt: `thinkingBudget` returns `%s` without clamping it below "
                "its own `%s` — the caller's `< %s` guard then never holds for the high tiers, "
                "so the whole thinking block is silently dropped while the picker says ON"
                % (re.sub(r"\s+", " ", r)[:60], cap, cap))
    return out


def check_temperature_pair(src):
    """C5 — a temperature written beside `budget_tokens` must be exactly 1."""
    stripped = strip_comments(src)
    out = []
    seen = False
    for name, _start, body in iter_functions(stripped):
        for pos, _args in find_calls(body, BUDGET_CALL):
            seen = True
            # Innermost brace-bodied if that encloses the write: the thinking
            # block the pairing applies to.
            block = None
            for _cond, t_start, t_end, _es, _ee in if_blocks(body):
                if t_start <= pos < t_end and (block is None or t_start > block[0]):
                    block = (t_start, t_end)
            if block is None:
                out.append("AnthropicProvider.kt: `budget_tokens` in %s is written outside any "
                           "brace-bodied if — the temperature pairing cannot be judged "
                           "(fail-closed)" % name)
                continue
            span = body[block[0]:block[1]]
            for _tpos, targs in find_calls(span, TEMPERATURE_CALL):
                value = targs[-1].strip() if len(targs) > 1 else ""
                if const_value(value) != ("num", 1.0):
                    out.append(
                        "AnthropicProvider.kt: `temperature` is written as `%s` next to "
                        "`budget_tokens` — extended thinking requires temperature == 1, any "
                        "other value is a deterministic 400 (%s)"
                        % (re.sub(r"\s+", " ", value)[:40], name))
    if not seen:
        out.append("AnthropicProvider.kt: no `budget_tokens` write site found (fail-closed)")
    return out


# ── selftest: one arm per criterion, each red exactly once ──────────────────

GREEN_BODY = '''
class AnthropicProvider {
    private fun buildRequestBody(messages: List<Int>, maxTokens: Int): JSONObject {
        val body = JSONObject()
        val cappedMaxTokens = clampOutboundMaxTokens(maxTokens, effectiveMaxOutputTokens(model))
        body.put("max_tokens", cappedMaxTokens)
        if (thinkingLevel.isEnabled) {
            if (modelUsesAdaptiveThinking(model.id)) {
                body.put("output_config", JSONObject().put("effort", thinkingEffort(thinkingLevel)))
            } else {
                val budgetTokens = thinkingBudget(cappedMaxTokens, thinkingLevel)
                if (budgetTokens > 0 && budgetTokens < cappedMaxTokens) {
                    body.put("thinking", JSONObject().apply {
                        put("type", "enabled")
                        put("budget_tokens", budgetTokens)
                    })
                }
            }
        }
        return body
    }

    private fun buildRequest(bodyStr: String, body: JSONObject): Request {
        val betaFlags = mutableListOf<String>()
        if (body.has("thinking")) {
            when (body.optJSONObject("thinking")?.optString("type")) {
                "adaptive" -> betaFlags.add("effort-2025-11-24")
                "enabled" -> betaFlags.add("interleaved-thinking-2025-05-14")
                "disabled" -> Unit
            }
        }
        return builder.build()
    }
}
'''

# Same-call-expression spelling the task explicitly allows: no shared local,
# the identical clamp expression on both sides.
GREEN_SAME_CALL_BODY = '''
class AnthropicProvider {
    private fun buildRequestBody(messages: List<Int>, maxTokens: Int): JSONObject {
        val body = JSONObject()
        body.put("max_tokens", clampOutboundMaxTokens(maxTokens, effectiveMaxOutputTokens(model)))
        val budgetTokens = thinkingBudget(clampOutboundMaxTokens(maxTokens, effectiveMaxOutputTokens(model)), thinkingLevel)
        if (budgetTokens > 0 && budgetTokens < maxTokens) {
            body.put("budget_tokens", budgetTokens)
        }
        return body
    }

    private fun buildRequest(bodyStr: String, body: JSONObject): Request {
        if (body.has("thinking")) {
            val t = body.optJSONObject("thinking")?.optString("type")
            if (t == "adaptive") betaFlags.add("effort-2025-11-24")
            if (t == "enabled") betaFlags.add("interleaved-thinking-2025-05-14")
            if (t != "disabled") sanity()
        }
        return builder.build()
    }
}
'''

# The exact baseline shape (f7aba218): all three criteria violated.
PRE_BODY = '''
class AnthropicProvider {
    private fun buildRequestBody(messages: List<Int>, maxTokens: Int): JSONObject {
        val body = JSONObject()
        body.put("max_tokens", clampOutboundMaxTokens(maxTokens, effectiveMaxOutputTokens(model)))
        if (thinkingLevel.isEnabled) {
            if (modelUsesAdaptiveThinking(model.id)) {
                body.put("output_config", JSONObject().put("effort", thinkingEffort(thinkingLevel)))
            } else {
                val budgetTokens = thinkingBudget(maxTokens, thinkingLevel)
                if (budgetTokens > 0) {
                    body.put("budget_tokens", budgetTokens)
                }
            }
        }
        return body
    }

    private fun buildRequest(bodyStr: String, body: JSONObject): Request {
        if (body.has("thinking")) {
            val isAdaptive = body.optJSONObject("thinking")?.optString("type") == "adaptive"
            if (isAdaptive) {
                betaFlags.add("effort-2025-11-24")
            } else {
                betaFlags.add("interleaved-thinking-2025-05-14")
            }
        }
        return builder.build()
    }
}
'''

# C1 only: budget still reads the raw request value.
MUT_C1_BODY = GREEN_BODY.replace(
    "thinkingBudget(cappedMaxTokens, thinkingLevel)",
    "thinkingBudget(maxTokens, thinkingLevel)",
)

# C2 only: guard drops the `< max` half (keeps `> 0`).
MUT_C2_BODY = GREEN_BODY.replace(
    "if (budgetTokens > 0 && budgetTokens < cappedMaxTokens)",
    "if (budgetTokens > 0)",
)

# C3 only: back to the two-way adaptive/else split (no "disabled" anywhere).
MUT_C3_BODY = GREEN_BODY.replace(
    '''            when (body.optJSONObject("thinking")?.optString("type")) {
                "adaptive" -> betaFlags.add("effort-2025-11-24")
                "enabled" -> betaFlags.add("interleaved-thinking-2025-05-14")
                "disabled" -> Unit
            }''',
    '''            val isAdaptive = body.optJSONObject("thinking")?.optString("type") == "adaptive"
            if (isAdaptive) {
                betaFlags.add("effort-2025-11-24")
            } else {
                betaFlags.add("interleaved-thinking-2025-05-14")
            }''',
)

# Fail-closed arm: budget written from a literal — no variable to guard.
MUT_LITERAL_BODY = GREEN_BODY.replace(
    "put(\"budget_tokens\", budgetTokens)",
    "put(\"budget_tokens\", 8192)",
)

# Guard kept verbatim but short-circuited — `a && (true || b)` deletes the
# decision while every anchor stays in the condition.
MUT_C2_DEAD_GUARD = GREEN_BODY.replace(
    "if (budgetTokens > 0 && budgetTokens < cappedMaxTokens)",
    "if (budgetTokens > 0 && (true || budgetTokens < cappedMaxTokens))",
)

# The disabled arm keeps the `"disabled"` comparison and announces a thinking
# beta anyway: body says "off", header says "on".
MUT_C3_DISABLED_ADDS = GREEN_BODY.replace(
    '"disabled" -> Unit',
    '"disabled" -> betaFlags.add("interleaved-thinking-2025-05-14")',
)

# ── [batch5-t05] the five witnesses, one arm each ────────────────────────────
# G09-A1: same NAME, different binding. The nearer `var` re-points the identifier
# the budget reads at the raw request value while the wire write keeps the
# clamped one — the pair disagrees without a single differing token.
MUT_C1_REBIND = GREEN_BODY.replace(
    "                val budgetTokens = thinkingBudget(cappedMaxTokens, thinkingLevel)",
    "                var cappedMaxTokens = maxTokens\n"
    "                val budgetTokens = thinkingBudget(cappedMaxTokens, thinkingLevel)",
)

# G09-A2: `< max` kept, decision deleted — arithmetic spelling of the same
# constant-true disjunct the `true ||` arm already covers.
MUT_C2_ARITH_TAUTOLOGY = GREEN_BODY.replace(
    "if (budgetTokens > 0 && budgetTokens < cappedMaxTokens)",
    "if (budgetTokens > 0 && (1 == 1 || budgetTokens < cappedMaxTokens))",
)

# G09-A3: the beta token is concatenated, so no single literal contains it.
MUT_C3_CONCAT = GREEN_BODY.replace(
    '"disabled" -> Unit',
    '"disabled" -> betaFlags.plusAssign("interleaved-" + "thinking-2025-05-14")',
)

# G09-A3, second spelling: a constant template interpolation.
MUT_C3_TEMPLATE = GREEN_BODY.replace(
    '"disabled" -> Unit',
    '"disabled" -> betaFlags.plusAssign("${"interleaved"}-thinking-2025-05-14")',
)

# Precision: a concatenated beta on a NON-disabled arm is legitimate — folding
# must not turn the criterion into "no string arithmetic anywhere".
GREEN_C3_CONCAT_ELSEWHERE = GREEN_BODY.replace(
    '"enabled" -> betaFlags.add("interleaved-thinking-2025-05-14")',
    '"enabled" -> betaFlags.plusAssign("interleaved-" + "thinking-2025-05-14")',
)

# Precision: the two bounds may be split across NESTED guards — that is a
# stronger shape than the single conjunction, not a weaker one.
GREEN_C2_NESTED = GREEN_BODY.replace(
    """                if (budgetTokens > 0 && budgetTokens < cappedMaxTokens) {
                    body.put("thinking", JSONObject().apply {""",
    """                if (budgetTokens > 0) {
                  if (budgetTokens < cappedMaxTokens) {
                    body.put("thinking", JSONObject().apply {""",
).replace(
    """                    })
                }
            }
        }
        return body""",
    """                    })
                  }
                }
            }
        }
        return body""",
)


def _crit_set(src):
    """-> {'C1','C2','C3'} of the criteria that FAIL on `src`."""
    failed = set()
    for crit, ok, _detail in check_source(src):
        if not ok:
            failed.add(crit.split()[0])
    return failed


_ARM_COUNT = 0
_OAI_ARMS = 0
_R3_ARMS = 0


def selftest():
    global _ARM_COUNT
    fails = []
    # Every expected set below is written from the criterion table above, never
    # derived from the gate's own output.
    arms = [
        ("compliant tree", GREEN_BODY, set()),
        ("compliant tree, same call expression on both sides", GREEN_SAME_CALL_BODY, set()),
        ("baseline (f7aba218) shape", PRE_BODY, {"C1", "C2", "C3"}),
        ("budget reads the raw request value", MUT_C1_BODY, {"C1"}),
        ("guard drops the `< max` half", MUT_C2_BODY, {"C2"}),
        ("beta branch back to two-way adaptive/else", MUT_C3_BODY, {"C3"}),
        ("budget written from a literal", MUT_LITERAL_BODY, {"C2"}),
        ("guard kept but short-circuited by `true ||`", MUT_C2_DEAD_GUARD, {"C2"}),
        ("disabled arm announces a thinking beta", MUT_C3_DISABLED_ADDS, {"C3"}),
        # [batch5-t05] witnesses G09-A1/A2/A3 — same anchor, different binding /
        # different constant spelling / different literal spelling.
        ("budget identifier re-bound to the raw value", MUT_C1_REBIND, {"C1"}),
        ("guard kept but short-circuited by `1 == 1 ||`", MUT_C2_ARITH_TAUTOLOGY, {"C2"}),
        ("disabled arm builds the beta token by concatenation", MUT_C3_CONCAT, {"C3"}),
        ("disabled arm builds the beta token by interpolation", MUT_C3_TEMPLATE, {"C3"}),
        # precision arms: legitimate shapes that must stay green
        ("concatenated beta on a non-disabled arm", GREEN_C3_CONCAT_ELSEWHERE, set()),
        ("bounds split across nested guards", GREEN_C2_NESTED, set()),
    ]
    global _OAI_ARMS
    _ARM_COUNT = len(arms)
    for label, src, expected in arms:
        got = _crit_set(src)
        if got != expected:
            fails.append("arm '%s': expected %s, got %s" % (
                label, sorted(expected) or "no violations", sorted(got) or "no violations"))
    # A mutation must actually change the text it claims to change.
    for label, mutated, base in (
        ("MUT_C1", MUT_C1_BODY, GREEN_BODY),
        ("MUT_C2", MUT_C2_BODY, GREEN_BODY),
        ("MUT_C3", MUT_C3_BODY, GREEN_BODY),
        ("MUT_LITERAL", MUT_LITERAL_BODY, GREEN_BODY),
        ("MUT_C2_DEAD_GUARD", MUT_C2_DEAD_GUARD, GREEN_BODY),
        ("MUT_C3_DISABLED_ADDS", MUT_C3_DISABLED_ADDS, GREEN_BODY),
        ("MUT_C1_REBIND", MUT_C1_REBIND, GREEN_BODY),
        ("MUT_C2_ARITH_TAUTOLOGY", MUT_C2_ARITH_TAUTOLOGY, GREEN_BODY),
        ("MUT_C3_CONCAT", MUT_C3_CONCAT, GREEN_BODY),
        ("MUT_C3_TEMPLATE", MUT_C3_TEMPLATE, GREEN_BODY),
        ("GREEN_C3_CONCAT_ELSEWHERE", GREEN_C3_CONCAT_ELSEWHERE, GREEN_BODY),
        ("GREEN_C2_NESTED", GREEN_C2_NESTED, GREEN_BODY),
    ):
        if mutated == base:
            fails.append("arm '%s' did not apply (string replace missed)" % label)
    fails.extend(_selftest_oai())
    _ARM_COUNT += _OAI_ARMS
    r3_fails, r3_arms = _selftest_r3()
    fails.extend(r3_fails)
    _ARM_COUNT += r3_arms
    return fails


# ── C-OAI-1 / C-OAI-2 arms ──────────────────────────────────────────────────
OAI_GREEN = '''
class OpenAIProvider {
    private fun buildRequestBody(messages: List<Int>, maxTokens: Int): JSONObject {
        val body = JSONObject()
        val safeMaxTokens = clampOutboundMaxTokens(maxTokens, effectiveMaxOutputTokens(model))
        if (usesMaxCompletionTokens) {
            body.put("max_completion_tokens", safeMaxTokens)
        } else {
            body.put("max_tokens", safeMaxTokens)
        }
        var echoPolicy: ReasoningEchoPolicy? = null
        if (!isMistral) {
            echoPolicy = injectThinkingParams(body, thinkingLevel, safeMaxTokens)
        }
        return body
    }
}
'''

# PRE shape (88ef9b27): the injector gets the RAW request value.
OAI_PRE = OAI_GREEN.replace(
    "injectThinkingParams(body, thinkingLevel, safeMaxTokens)",
    "injectThinkingParams(body, thinkingLevel, maxTokens)",
)

# No injector call at all in the writing function.
OAI_NO_INJECT = OAI_GREEN.replace(
    "            echoPolicy = injectThinkingParams(body, thinkingLevel, safeMaxTokens)\n",
    "",
)

# [batch5-t05 G09-A4] Both readers keep the name; the injector's block re-binds
# it to the raw request value (`val safeMaxTokens = maxTokens` inside the
# `if (!isMistral)` block). Every token is unchanged at the call sites.
OAI_SHADOW = OAI_GREEN.replace(
    "            echoPolicy = injectThinkingParams(body, thinkingLevel, safeMaxTokens)",
    "            val safeMaxTokens = maxTokens\n"
    "            echoPolicy = injectThinkingParams(body, thinkingLevel, safeMaxTokens)",
)

# Only one of the two writes is wired to the clamped value.
OAI_HALF = OAI_GREEN.replace(
    'body.put("max_completion_tokens", safeMaxTokens)',
    'body.put("max_completion_tokens", maxTokens)',
)

RESOLVER_GREEN = '''
object ThinkingRuleResolver {
    private fun emit(body: JSONObject, ctx: ThinkingResolveContext) {
        val enabled = true
        var budget = 32768
        if (budget > 0) {
            if (ctx.maxTokens < 2) {
                budget = 0
            } else {
                val margin = maxOf(2048, ctx.maxTokens / 8)
                val ceiling = maxOf(1, minOf(ctx.maxTokens - margin, ctx.maxTokens - 1))
                if (budget >= ceiling) budget = ceiling
            }
        }
        body.put("enable_thinking", enabled)
        if (budget > 0) body.put("thinking_budget", budget)
    }
}
'''

# PRE shape: guard nested inside a `maxTokens > 0` block.
RESOLVER_PRE = RESOLVER_GREEN.replace(
    "if (budget > 0) {",
    "if (budget > 0 && ctx.maxTokens > 0) {",
)

# The guard is gone: nothing zeroes the budget for a non-positive maxTokens.
RESOLVER_NO_GUARD = RESOLVER_GREEN.replace(
    '''            if (ctx.maxTokens < 2) {
                budget = 0
            } else {''',
    '''            if (false) {
                budget = 0
            } else {''',
)

# The guard is short-circuited: the comparison and the `budget = 0` block stay,
# but `false &&` makes the branch unreachable.
RESOLVER_DEAD_GUARD = RESOLVER_GREEN.replace(
    "if (ctx.maxTokens < 2) {",
    "if (false && ctx.maxTokens < 2) {",
)

# [batch5-t05 G09-A5] The arithmetic spelling of the same constant-false prefix:
# `(1 == 2)` is `false` to the compiler and invisible to a literal whitelist.
RESOLVER_ARITH_DEAD = RESOLVER_GREEN.replace(
    "if (ctx.maxTokens < 2) {",
    "if ((1 == 2) && ctx.maxTokens < 2) {",
)

# Precision: `if (true || ...)` around a DIFFERENT variable must not red the
# `< 2` guard — the constant only has to not swallow the bound under test.
RESOLVER_UNRELATED_TAUTOLOGY = RESOLVER_GREEN.replace(
    "        if (budget > 0) {\n            if (ctx.maxTokens < 2) {",
    "        if (true || budget > 0) {\n            if (ctx.maxTokens < 2) {",
)

# The zeroing is written first and overwritten in the same block.
RESOLVER_LAST_WRITE = RESOLVER_GREEN.replace(
    '''            if (ctx.maxTokens < 2) {
                budget = 0''',
    '''            if (ctx.maxTokens < 2) {
                budget = 0
                budget = 4096''',
)

# Reachability moves from an `if` to a `when` arm: same semantics, invisible to a
# parser that only walks `if (`.
RESOLVER_WHEN_ARM = '''object ThinkingRuleResolver {
    private fun emit(body: JSONObject, ctx: ThinkingResolveContext) {
        var budget = 32768
        when {
            ctx.maxTokens > 0 -> if (ctx.maxTokens < 2) {
                budget = 0
            } else {
                val ceiling = maxOf(1, ctx.maxTokens - 1)
                if (budget >= ceiling) budget = ceiling
            }
        }
        if (budget > 0) body.put("thinking_budget", budget)
    }
}
'''

# Braceless `if` chain: the outer enclosure has no `{`, so a brace-only parser
# never sees it.
RESOLVER_BRACELESS = '''object ThinkingRuleResolver {
    private fun emit(body: JSONObject, ctx: ThinkingResolveContext) {
        var budget = 32768
        if (ctx.maxTokens > 0) if (ctx.maxTokens < 2) {
            budget = 0
        }
        if (budget > 0) body.put("thinking_budget", budget)
    }
}
'''


def _selftest_oai():
    global _OAI_ARMS
    fails = []
    # (label, criterion, source, expected-to-be-red) — each fixture is shaped for
    # ONE criterion, so the arms stay independent (an OpenAI fixture is not a
    # resolver and must not be graded by C-OAI-2).
    oai_arms = [
        ("openai pair wired to the clamped value", check_openai_pair, OAI_GREEN, False),
        ("baseline shape: injector gets the raw value", check_openai_pair, OAI_PRE, True),
        ("no injector call in the max_tokens writer", check_openai_pair, OAI_NO_INJECT, True),
        ("only one of two max_tokens writes is wired", check_openai_pair, OAI_HALF, True),
        # [batch5-t05] witnesses G09-A4 / G09-A5
        ("injector block re-binds the wire value to the raw one", check_openai_pair, OAI_SHADOW, True),
        ("resolver guard short-circuited by `(1 == 2) &&`", check_resolver_guard, RESOLVER_ARITH_DEAD, True),
        ("an unrelated tautology does not swallow the `< 2` guard", check_resolver_guard,
         RESOLVER_UNRELATED_TAUTOLOGY, False),
        ("resolver guard outside `maxTokens > 0`", check_resolver_guard, RESOLVER_GREEN, False),
        ("resolver guard nested inside `maxTokens > 0`", check_resolver_guard, RESOLVER_PRE, True),
        ("resolver guard removed entirely", check_resolver_guard, RESOLVER_NO_GUARD, True),
        ("resolver guard short-circuited by `false &&`", check_resolver_guard, RESOLVER_DEAD_GUARD, True),
        ("resolver zeroing overwritten in the same block", check_resolver_guard, RESOLVER_LAST_WRITE, True),
        ("resolver guard nested in a `when` arm that needs maxTokens > 0", check_resolver_guard, RESOLVER_WHEN_ARM, True),
        ("resolver guard nested in a braceless `if` chain", check_resolver_guard, RESOLVER_BRACELESS, True),
    ]
    _OAI_ARMS = len(oai_arms)
    for label, fn, src, expect_red in oai_arms:
        got = bool(fn(src))
        if got != expect_red:
            fails.append("arm '%s': expected %s, got %s" % (
                label,
                "RED" if expect_red else "GREEN",
                "RED" if got else "GREEN"))
    for label, mutated, base in (
        ("OAI_PRE", OAI_PRE, OAI_GREEN),
        ("OAI_NO_INJECT", OAI_NO_INJECT, OAI_GREEN),
        ("OAI_HALF", OAI_HALF, OAI_GREEN),
        ("OAI_SHADOW", OAI_SHADOW, OAI_GREEN),
        ("RESOLVER_ARITH_DEAD", RESOLVER_ARITH_DEAD, RESOLVER_GREEN),
        ("RESOLVER_UNRELATED_TAUTOLOGY", RESOLVER_UNRELATED_TAUTOLOGY, RESOLVER_GREEN),
        ("RESOLVER_PRE", RESOLVER_PRE, RESOLVER_GREEN),
        ("RESOLVER_NO_GUARD", RESOLVER_NO_GUARD, RESOLVER_GREEN),
        ("RESOLVER_DEAD_GUARD", RESOLVER_DEAD_GUARD, RESOLVER_GREEN),
        ("RESOLVER_LAST_WRITE", RESOLVER_LAST_WRITE, RESOLVER_GREEN),
    ):
        if mutated == base:
            fails.append("arm '%s' did not apply (string replace missed)" % label)
    return fails


# ── [R3-t15] arms for the three seam criteria (`--selftest`) ────────────────
# Each fixture targets ONE criterion, so an arm can never be satisfied by a
# neighbouring criterion firing (that would hide a dead check).

FWD_GREEN = '''
class OpenAIProvider {
    private fun injectThinkingParams(
        body: JSONObject,
        level: ThinkingLevel,
        maxTokens: Int,
    ): ReasoningEchoPolicy? {
        val ctx = ThinkingResolveContext(
            modelId = model.id,
            level = level,
            maxTokens = maxTokens,
        )
        AppLogger.info("Thinking", "[resolve.in] maxTokens=$maxTokens")
        return ThinkingRuleResolver.apply(body, ctx)
    }
}
'''

# Witness #0: the injector discards its own argument and hands the resolver a
# literal ceiling. Every anchor C-OAI-1 checks (the call site) is untouched.
FWD_LITERAL = FWD_GREEN.replace("maxTokens = maxTokens,", "maxTokens = 65536,")
# Same discard, laundered through a scope call so the text still says `maxTokens`.
FWD_LAUNDERED = FWD_GREEN.replace(
    "maxTokens = maxTokens,", "maxTokens = maxTokens.let { 65536 },")
# Same discard, laundered through a nearer binding of the same name.
FWD_SHADOW = FWD_GREEN.replace(
    "        val ctx = ThinkingResolveContext(",
    "        val maxTokens = 65536\n        val ctx = ThinkingResolveContext(")
# Derivation probe: renaming the parameter must not change the verdict.
FWD_RENAMED = FWD_GREEN.replace("maxTokens: Int,", "wireMax: Int,").replace(
    "maxTokens = maxTokens,", "maxTokens = wireMax,")
# Fail-closed: the ceiling field disappears / the ctx disappears / the third
# parameter disappears.
FWD_NO_FIELD = FWD_GREEN.replace("            maxTokens = maxTokens,\n", "")
FWD_NO_CTX = FWD_GREEN.replace(
    '''        val ctx = ThinkingResolveContext(
            modelId = model.id,
            level = level,
            maxTokens = maxTokens,
        )
''', "")
FWD_TWO_PARAMS = FWD_GREEN.replace("        maxTokens: Int,\n", "").replace(
    "            maxTokens = maxTokens,\n", "").replace(
    "ThinkingResolveContext(\n            modelId = model.id,",
    "ThinkingResolveContext(\n            modelId = model.id,")

HELPER_GREEN = '''
class AnthropicProvider {
    companion object {
        fun thinkingBudget(maxTokens: Int, level: ThinkingLevel): Int {
            val cap = when (level) {
                ThinkingLevel.OFF -> 0
                ThinkingLevel.LOW -> 8192
                ThinkingLevel.MEDIUM -> 32768
                ThinkingLevel.HIGH -> minOf(maxTokens, 65536)
                ThinkingLevel.XHIGH,
                ThinkingLevel.MAX -> maxTokens
                ThinkingLevel.AUTO -> 0
            }
            // Anthropic requires budget_tokens STRICTLY LESS THAN max_tokens.
            val clamped = minOf(cap, maxTokens)
            return if (clamped >= maxTokens && maxTokens > 1) maxTokens - 1 else clamped
        }
    }
}
'''

# Witness #1: the caller stays legal, the helper stops clamping. `:660`'s
# `budgetTokens < cappedMaxTokens` is then false for every high tier and the
# thinking block is silently omitted.
HELPER_RETURN_CAP = HELPER_GREEN.replace(
    "            val clamped = minOf(cap, maxTokens)\n"
    "            return if (clamped >= maxTokens && maxTokens > 1) maxTokens - 1 else clamped\n",
    "            return cap\n")
# Precision: clamping at the return site is the same guarantee in another shape.
HELPER_MIN_RETURN = HELPER_GREEN.replace(
    "            val clamped = minOf(cap, maxTokens)\n"
    "            return if (clamped >= maxTokens && maxTokens > 1) maxTokens - 1 else clamped\n",
    "            return minOf(cap, maxTokens)\n")
# Same discard, two hops: the local is named `clamped` but bound to the cap.
HELPER_DEAD_CLAMP = HELPER_GREEN.replace(
    "            val clamped = minOf(cap, maxTokens)\n"
    "            return if (clamped >= maxTokens && maxTokens > 1) maxTokens - 1 else clamped\n",
    "            val clamped = cap\n            return clamped\n")
# Returning the ceiling itself is the illegal pair (budget == max_tokens).
HELPER_RETURN_MAX = HELPER_GREEN.replace(
    "            val clamped = minOf(cap, maxTokens)\n"
    "            return if (clamped >= maxTokens && maxTokens > 1) maxTokens - 1 else clamped\n",
    "            return maxTokens\n")

TEMP_GREEN = '''
class AnthropicProvider {
    private fun buildRequestBody(maxTokens: Int, cappedMaxTokens: Int, body: JSONObject) {
        val budgetTokens = thinkingBudget(cappedMaxTokens, thinkingLevel)
        if (budgetTokens > 0 && budgetTokens < cappedMaxTokens) {
            body.put("thinking", JSONObject().apply {
                put("type", "enabled")
                put("budget_tokens", budgetTokens)
            })
            if (!modelRejectsTemperature(model.id)) {
                body.put("temperature", 1)
            }
        }
    }
}
'''

# Witness #2: the budget pair is legal, the temperature half of the same
# contract is not — Anthropic 400s any temperature != 1 with extended thinking.
TEMP_0_2 = TEMP_GREEN.replace('body.put("temperature", 1)', 'body.put("temperature", 0.2)')
TEMP_ZERO = TEMP_GREEN.replace('body.put("temperature", 1)', 'body.put("temperature", 0)')
# Precision: `1f` / `1.0` are the same value to the compiler.
TEMP_ONE_FLOAT = TEMP_GREEN.replace('body.put("temperature", 1)', 'body.put("temperature", 1.0f)')
# Precision: omitting the field keeps the API default of 1.
TEMP_ABSENT = TEMP_GREEN.replace(
    '''            if (!modelRejectsTemperature(model.id)) {
                body.put("temperature", 1)
            }
''', "")
# Precision: a temperature outside the thinking block is not this contract's.
TEMP_OUTSIDE = TEMP_GREEN.replace(
    '''            if (!modelRejectsTemperature(model.id)) {
                body.put("temperature", 1)
            }
        }
''', '''        }
        body.put("temperature", 0.2)
''')


def _selftest_r3():
    global _R3_ARMS
    fails = []
    arms = [
        ("injector forwards its own wire value", check_inject_forwarding, FWD_GREEN, False),
        ("injector hands the resolver a literal ceiling (witness #0)",
         check_inject_forwarding, FWD_LITERAL, True),
        ("injector launders the ceiling through `.let { literal }`",
         check_inject_forwarding, FWD_LAUNDERED, True),
        ("injector re-binds the parameter name to a literal",
         check_inject_forwarding, FWD_SHADOW, True),
        ("renamed parameter still forwards the wire value",
         check_inject_forwarding, FWD_RENAMED, False),
        ("ctx built without a ceiling field", check_inject_forwarding, FWD_NO_FIELD, True),
        ("injector builds no ctx at all", check_inject_forwarding, FWD_NO_CTX, True),
        ("injector signature has no wire argument", check_inject_forwarding, FWD_TWO_PARAMS, True),
        ("budget helper clamps below its own max", check_budget_helper, HELPER_GREEN, False),
        ("budget helper returns the unclamped cap (witness #1)",
         check_budget_helper, HELPER_RETURN_CAP, True),
        ("budget helper clamps at the return site", check_budget_helper, HELPER_MIN_RETURN, False),
        ("budget helper binds the cap but calls it `clamped`",
         check_budget_helper, HELPER_DEAD_CLAMP, True),
        ("budget helper returns the ceiling itself", check_budget_helper, HELPER_RETURN_MAX, True),
        ("temperature 1 inside the thinking block", check_temperature_pair, TEMP_GREEN, False),
        ("temperature 0.2 beside budget_tokens (witness #2)",
         check_temperature_pair, TEMP_0_2, True),
        ("temperature 0 beside budget_tokens", check_temperature_pair, TEMP_ZERO, True),
        ("temperature written as `1.0f`", check_temperature_pair, TEMP_ONE_FLOAT, False),
        ("temperature omitted entirely", check_temperature_pair, TEMP_ABSENT, False),
        ("temperature written outside the thinking block", check_temperature_pair, TEMP_OUTSIDE, False),
    ]
    _R3_ARMS = len(arms)
    for label, fn, src, expect_red in arms:
        try:
            got = bool(fn(src))
        except Exception as e:                     # a crash is red, never green
            fails.append("arm '%s' raised %s: %s" % (label, type(e).__name__, e))
            continue
        if got != expect_red:
            fails.append("arm '%s': expected %s, got %s" % (
                label,
                "RED" if expect_red else "GREEN",
                "RED" if got else "GREEN"))
    for label, mutated, base in (
        ("FWD_LITERAL", FWD_LITERAL, FWD_GREEN),
        ("FWD_LAUNDERED", FWD_LAUNDERED, FWD_GREEN),
        ("FWD_SHADOW", FWD_SHADOW, FWD_GREEN),
        ("FWD_RENAMED", FWD_RENAMED, FWD_GREEN),
        ("FWD_NO_FIELD", FWD_NO_FIELD, FWD_GREEN),
        ("FWD_NO_CTX", FWD_NO_CTX, FWD_GREEN),
        ("FWD_TWO_PARAMS", FWD_TWO_PARAMS, FWD_GREEN),
        ("HELPER_RETURN_CAP", HELPER_RETURN_CAP, HELPER_GREEN),
        ("HELPER_MIN_RETURN", HELPER_MIN_RETURN, HELPER_GREEN),
        ("HELPER_DEAD_CLAMP", HELPER_DEAD_CLAMP, HELPER_GREEN),
        ("HELPER_RETURN_MAX", HELPER_RETURN_MAX, HELPER_GREEN),
        ("TEMP_0_2", TEMP_0_2, TEMP_GREEN),
        ("TEMP_ZERO", TEMP_ZERO, TEMP_GREEN),
        ("TEMP_ONE_FLOAT", TEMP_ONE_FLOAT, TEMP_GREEN),
        ("TEMP_ABSENT", TEMP_ABSENT, TEMP_GREEN),
        ("TEMP_OUTSIDE", TEMP_OUTSIDE, TEMP_GREEN),
    ):
        if mutated == base:
            fails.append("arm '%s' did not apply (string replace missed)" % label)
    return fails, _R3_ARMS


def main():
    if "--selftest" in sys.argv:
        fails = selftest()
        for f in fails:
            print("  [FAIL] %s" % f)
        if fails:
            print("SELFTEST FAIL (%d)" % len(fails))
            return 1
        print("ANTHROPIC_THINKING_BUDGET_SELFTEST=PASS (%d arms)" % _ARM_COUNT)
        return 0

    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    root = args[0] if args else "."
    path = os.path.join(root, REL)
    if not os.path.isfile(path):
        print("FAIL %s not found under root %s" % (REL, root))
        return 2
    src = open(path, encoding="utf-8").read()
    if ROT_MARK not in src:
        print("FAIL %s does not look like the Anthropic provider (no '%s')" % (REL, ROT_MARK))
        return 2

    results = check_source(src)
    # [R3-t15] The two Anthropic-side seams: the helper's own body (C4) and the
    # temperature half of the same pairing contract (C5).
    for crit, viols in (
        ("C4 thinkingBudget clamps below its own max argument",
         check_budget_helper(src)),
        ("C5 temperature == 1 next to budget_tokens",
         check_temperature_pair(src)),
    ):
        results.append((crit, not viols, " / ".join(viols)))
    # [deep-audit-1047 A-01] Two more readers of the same pair contract, in the
    # files the Anthropic-shaped criteria above cannot see. Fail-closed on a
    # missing file: a moved path must read as ROT, never as green.
    for rel, mark in ((REL_OAI, OAI_ROT_MARK), (REL_RESOLVER, RESOLVER_ROT_MARK)):
        extra_path = os.path.join(root, rel)
        if not os.path.isfile(extra_path):
            print("FAIL %s not found under root %s" % (rel, root))
            return 2
        extra = open(extra_path, encoding="utf-8").read()
        if mark not in extra:
            print("FAIL %s does not look like the expected source (no '%s')" % (rel, mark))
            return 2
        if rel == REL_OAI:
            oai_v = check_openai_pair(extra)
            results.append((
                "C-OAI-1 max_tokens and the thinking budget read the same value",
                not oai_v, " / ".join(oai_v),
            ))
            # [R3-t15] The OpenAI-side seam: what the injector DOES with the
            # argument C-OAI-1 checked.
            fwd_v = check_inject_forwarding(extra)
            results.append((
                "C-OAI-3 injectThinkingParams forwards its own wire max_tokens",
                not fwd_v, " / ".join(fwd_v),
            ))
        else:
            res_v = check_resolver_guard(extra)
            results.append((
                "C-OAI-2 the `maxTokens < 2` guard is not inside a `> 0` block",
                not res_v, " / ".join(res_v),
            ))
    failed = [(c, d) for c, ok, d in results if not ok]
    for crit, ok, detail in results:
        if ok:
            print("  [OK] %s" % crit)
        else:
            print("  [FAIL] %s (%s)" % (crit, detail))
    if failed:
        print("anthropic thinking budget gate: RED — the S-03 pair contract is violated")
        return 1
    print("GATE=GREEN anthropic_thinking_budget (%d criteria)" % len(results))
    return 0


if __name__ == "__main__":
    sys.exit(main())
