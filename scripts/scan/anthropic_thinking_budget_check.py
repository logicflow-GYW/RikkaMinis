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

REL = "src/android/app/src/main/java/com/rikkaminis/app/provider/anthropic/AnthropicProvider.kt"
ROT_MARK = "class AnthropicProvider"

# ── Kotlin-aware source scanning ─────────────────────────────────────────────
# Criteria below are matched on comment-stripped text with string literals
# intact (several anchors ARE string literals: put("max_tokens", …)). Comments
# are blanked but their newlines kept, so every line number this gate prints is
# a real line in the original file.

def skip_string(text, i):
    """Index just past the string literal starting at `text[i]` (a quote)."""
    if text.startswith('"""', i):
        j = text.find('"""', i + 3)
        return len(text) if j < 0 else j + 3
    q = text[i]
    i += 1
    while i < len(text) and text[i] != q:
        if text[i] == "\\":
            i += 2
        elif text.startswith("${", i):
            i = skip_template(text, i + 2)
        else:
            i += 1
    return i + 1


def skip_template(text, i):
    """Index just past the `}` matching an already-consumed `${`."""
    depth = 1
    while i < len(text):
        c = text[i]
        if c == '"' or c == "'":
            i = skip_string(text, i)
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return len(text)


def strip_comments(src):
    """Remove line + block comments, leaving string literals and newlines."""
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == '"' or c == "'":
            j = skip_string(src, i)
            out.append(src[i:j])
            i = j
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            end = n if j < 0 else j + 2
            out.append("\n" * src.count("\n", i, end))
            i = end
        else:
            out.append(c)
            i += 1
    return "".join(out)


def brace_body(text, start):
    """Balanced `{...}` starting at `start`; -> (body incl. braces, end index)."""
    depth = 0
    k = start
    while k < len(text):
        c = text[k]
        if c == '"' or c == "'":
            k = skip_string(text, k)
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[start:k + 1], k + 1
        k += 1
    return text[start:], len(text)


def paren_body(text, start):
    """Balanced `(...)` starting at `start`; -> (body incl. parens, end index)."""
    depth = 0
    k = start
    while k < len(text):
        c = text[k]
        if c == '"' or c == "'":
            k = skip_string(text, k)
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return text[start:k + 1], k + 1
        k += 1
    return text[start:], len(text)


FUN_HEAD_RE = re.compile(r"\bfun\s+(?:<[^>\n]*>\s*)?(?:[\w.]+\.)?([A-Za-z_]\w*)\s*\(")


def iter_functions(text):
    """-> [(name, start, body)] for every block-bodied `fun` in `text`.

    Derived, not listed: no criterion below names the functions it polices, so
    renaming (or splitting) the request builder keeps the gate honest.
    """
    out = []
    for m in FUN_HEAD_RE.finditer(text):
        paren_at = text.find("(", m.end() - 1)
        _sig, after = paren_body(text, paren_at)
        brace_at = text.find("{", after - 1)
        if brace_at < 0:
            continue
        # A `)` followed by `=` / `:` before any `{` is an expression-bodied fun.
        gap = text[after:brace_at] if brace_at > after else ""
        if "=" in gap:
            continue
        body, _end = brace_body(text, brace_at)
        out.append((m.group(1), brace_at, body))
    return out


def split_top_level_args(text, open_paren_index):
    """Split the argument list of the call whose `(` is at `open_paren_index`.

    -> list of raw argument expressions (trimmed); nested calls/brackets safe.
    """
    args = []
    i = open_paren_index + 1
    depth = 0
    cur = []
    while i < len(text):
        c = text[i]
        if c == '"' or c == "'":
            j = skip_string(text, i)
            cur.append(text[i:j])
            i = j
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                break
            depth -= 1
        if c == "," and depth == 0:
            args.append("".join(cur).strip())
            cur = []
            i += 1
            continue
        cur.append(c)
        i += 1
    tail = "".join(cur).strip()
    if tail:
        args.append(tail)
    return args


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
        args = split_top_level_args(text, paren_at)
        out.append((m.start(), args))
    return out


MAX_TOKENS_CALL = re.compile(r"\bput\(\s*\"max_tokens\"")
BUDGET_CALL = re.compile(r"\bput\(\s*\"budget_tokens\"")
BUDGET_FN = re.compile(r"(?<![\w.])thinkingBudget\s*\(")
THINKING_HAS = re.compile(r"\bhas\(\s*\"thinking\"\s*\)")
GUARD_GT_ZERO = "\x00gt0"   # placeholder, real regex built per-variable below
IF_RE = re.compile(r"\bif\s*\(")
DISABLED_COMPARE = re.compile(
    r"""(?:==|!=)\s*"disabled"|"disabled"\s*(?:==|!=)|"disabled"\s*->"""
)


def norm(expr):
    return re.sub(r"\s+", "", expr)


BETA_ADD_RE = re.compile(r"\.add\s*\(")
BETA_TOKENS = ("interleaved-thinking", "effort-")


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
        if BETA_ADD_RE.search(body) or any(t in body for t in BETA_TOKENS):
            return re.sub(r"\s+", " ", body).strip()[:60]
    # `if (type == "disabled") betaFlags.add(...)` — the same contradiction in
    # comparison form.
    for m in IF_RE.finditer(text):
        paren_at = text.find("(", m.end() - 1)
        cond, after = paren_body(text, paren_at)
        if not re.search(r'==\s*"disabled"', cond):
            continue
        brace_at = text.find("{", after - 1)
        if brace_at < 0:
            continue
        body, _end = brace_body(text, brace_at)
        if BETA_ADD_RE.search(body) or any(t in body for t in BETA_TOKENS):
            return re.sub(r"\s+", " ", cond[1:-1]).strip()
    return None


def enclosing_ifs(func_body, write_index):
    """-> [condition text] of every brace-bodied `if` whose body holds write_index."""
    out = []
    for m in IF_RE.finditer(func_body):
        paren_at = func_body.find("(", m.end() - 1)
        cond, after = paren_body(func_body, paren_at)
        brace_at = func_body.find("{", after - 1)
        if brace_at < 0:
            continue
        body, end = brace_body(func_body, brace_at)
        if brace_at <= write_index < end:
            out.append(cond[1:-1])
    return out


# ── Guard liveness: an anchor that is PRESENT but SHORT-CIRCUITED ───────────
# `if (a && (true || b))` keeps the `b` tokens in the condition while deleting
# the decision, so任何「条件里出现了 X」的判据都会被它骗过。A guard is only a
# guard when a bare boolean literal is not an operand of `&&` / `||`.
BOOL_OP_LITERAL = re.compile(
    r"(?:&&|\|\|)\s*(?:true|false)(?![\w])"
    r"|(?<![\w.\"'])(?:true|false)\s*(?:&&|\|\|)"
)


def literal_decides(cond):
    """True when a bare `true` / `false` is an operand of a boolean operator."""
    return bool(BOOL_OP_LITERAL.search(cond))


WHEN_BLOCK_RE = re.compile(r"\bwhen\s*\{")


def enclosing_when_arms(text, pos):
    """-> [arm condition] for every `when { ... }` arm whose body holds `pos`.

    `when { x > 0 -> ... }` is an `if` in disguise: the same defect comes back
    when the guard is nested inside an arm that only matches positive values.
    """
    out = []
    for m in WHEN_BLOCK_RE.finditer(text):
        if m.start() > pos:
            break
        brace_at = m.end() - 1
        _body, end = brace_body(text, brace_at)
        if not (brace_at < pos < end):
            continue
        depth = 0
        arm_start = brace_at + 1
        k = brace_at + 1
        while k < pos:
            c = text[k]
            if c == '"' or c == "'":
                k = skip_string(text, k)
                continue
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
            elif depth == 0 and text.startswith("->", k):
                out.append(text[arm_start:k])
                arm_start = k + 2
                k += 2
                continue
            k += 1
    return out


def enclosing_conditions(text, pos):
    """`if` and `when`-arm conditions that enclose `pos`, INCLUDING braceless
    `if (x) if (y) { ... }` chains — the parser shape that used to slip past
    `enclosing_ifs` (it only walks `if (...) {`)."""
    out = list(enclosing_ifs(text, pos))
    for m in IF_RE.finditer(text):
        if m.start() > pos:
            break
        paren_at = text.find("(", m.end() - 1)
        cond, after = paren_body(text, paren_at)
        nxt = after
        while nxt < len(text) and text[nxt] in " \t\r\n":
            nxt += 1
        if nxt < len(text) and text[nxt] == "{":
            continue  # handled by enclosing_ifs
        if nxt <= pos and re.match(r"if\s*\(", text[nxt:nxt + 8]):
            out.append(cond[1:-1])
    out.extend(enclosing_when_arms(text, pos))
    return out


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
            # A guard that keeps both halves but is short-circuited by a literal
            # (`a && (true || b)`, `false && a`) decides nothing: the write runs
            # as if the guard were absent. Only live conditions may satisfy C2.
            live = [c for c in conds if not literal_decides(c)]
            dead = [c for c in conds if literal_decides(c)]
            if not live:
                c2_ok = False
                c2_detail = (
                    "the only guard(s) around the budget_tokens write are short-circuited by a "
                    "boolean literal (`%s`) — the guard is present but decides nothing"
                    % re.sub(r"\s+", " ", dead[0]).strip())
                continue
            if not any(gt.search(c) for c in live):
                c2_ok = False
                c2_detail = "no `%s > 0` guard encloses the budget_tokens write" % value
            elif not any(lt.search(c) for c in live):
                c2_ok = False
                c2_detail = "no `%s < <max>` guard encloses the budget_tokens write" % value
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
    if writes == 0:
        violations.append(
            "no max_tokens write site found in OpenAIProvider.kt — refusing to report green "
            "(the serialization shape moved; update this criterion deliberately)")
    # One message per distinct (writer, wire value vs budget source) pair: the two
    # `put` spellings usually share one injector call.
    return list(dict.fromkeys(violations))


def check_resolver_guard(src):
    """C-OAI-2 — the `maxTokens < 2` guard must not nest inside `maxTokens > 0`.

    Derived by brace depth: for every `if` whose condition tests `< 2` and whose
    body zeroes a variable, no ENCLOSING `if` may test `maxTokens > 0`."""
    stripped = strip_comments(src)
    violations = []
    guards = 0
    for m in IF_RE.finditer(stripped):
        paren_at = stripped.find("(", m.end() - 1)
        cond, after = paren_body(stripped, paren_at)
        brace_at = stripped.find("{", after - 1)
        if brace_at < 0:
            continue
        body, _end = brace_body(stripped, brace_at)
        if not LT_TWO.search(cond) or not ZERO_ASSIGN.search(body):
            continue
        guards += 1
        line = stripped[:m.start()].count("\n") + 1
        inner = cond[1:-1]
        # (a) the guard must DECIDE: `if (false && maxTokens < 2)` keeps the
        #     comparison and the `budget = 0` block while clamping nothing.
        if literal_decides(inner):
            violations.append(
                "ThinkingRuleResolver.kt:%d the `< 2 → 0` guard is short-circuited by a "
                "boolean literal (`%s`) — the clamp is present but never runs, so a "
                "non-positive request value ships its raw budget against a wire "
                "max_tokens of 1" % (line, re.sub(r"\s+", " ", inner).strip()))
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
        for outer in enclosing_conditions(stripped, m.start()):
            if MAX_TOKENS_GT_ZERO.search(outer):
                violations.append(
                    "ThinkingRuleResolver.kt:%d `maxTokens < 2` guard nests inside "
                    "`%s` — a non-positive request value then skips the clamp entirely "
                    "while the provider floors the wire value at 1, so the raw budget "
                    "reaches the wire above max_tokens" % (line, re.sub(r"\s+", " ", outer).strip()))
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


def _crit_set(src):
    """-> {'C1','C2','C3'} of the criteria that FAIL on `src`."""
    failed = set()
    for crit, ok, _detail in check_source(src):
        if not ok:
            failed.add(crit.split()[0])
    return failed


def selftest():
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
    ]
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
    ):
        if mutated == base:
            fails.append("arm '%s' did not apply (string replace missed)" % label)
    fails.extend(_selftest_oai())
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
    fails = []
    # (label, criterion, source, expected-to-be-red) — each fixture is shaped for
    # ONE criterion, so the arms stay independent (an OpenAI fixture is not a
    # resolver and must not be graded by C-OAI-2).
    oai_arms = [
        ("openai pair wired to the clamped value", check_openai_pair, OAI_GREEN, False),
        ("baseline shape: injector gets the raw value", check_openai_pair, OAI_PRE, True),
        ("no injector call in the max_tokens writer", check_openai_pair, OAI_NO_INJECT, True),
        ("only one of two max_tokens writes is wired", check_openai_pair, OAI_HALF, True),
        ("resolver guard outside `maxTokens > 0`", check_resolver_guard, RESOLVER_GREEN, False),
        ("resolver guard nested inside `maxTokens > 0`", check_resolver_guard, RESOLVER_PRE, True),
        ("resolver guard removed entirely", check_resolver_guard, RESOLVER_NO_GUARD, True),
        ("resolver guard short-circuited by `false &&`", check_resolver_guard, RESOLVER_DEAD_GUARD, True),
        ("resolver zeroing overwritten in the same block", check_resolver_guard, RESOLVER_LAST_WRITE, True),
        ("resolver guard nested in a `when` arm that needs maxTokens > 0", check_resolver_guard, RESOLVER_WHEN_ARM, True),
        ("resolver guard nested in a braceless `if` chain", check_resolver_guard, RESOLVER_BRACELESS, True),
    ]
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
        print("ANTHROPIC_THINKING_BUDGET_SELFTEST=PASS")
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
