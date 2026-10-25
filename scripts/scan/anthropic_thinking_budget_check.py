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
from _kotlin_lexer import KotlinLexError, mask_comments  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool,
    enclosing_conditions as shared_enclosing_conditions,
    if_blocks,
    match_bracket,
    split_top,
    strip_parens,
    when_branches,
)
from _kotlin_valueflow import (  # noqa: E402
    call_args,
    guard_feasible,
    is_single_assignment,
    path_feasible,
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
