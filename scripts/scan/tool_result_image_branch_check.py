#!/usr/bin/env python3
"""
Tool-result image consumer guard — every provider serializer that handles a
tool-result part must consume its image bytes.

Bug class (deep-audit-1017 ISSUE-01, P0): `read_image` / `browser_use`
screenshots reach the request as `AgentContentPart.ToolResult.imageData`, not as
an `ImageData` part. Anthropic (`AnthropicProvider.kt` -> image block inside
`tool_result.content`) and Gemini (`GeminiProvider.kt` -> sibling `inlineData`)
were fixed by "[FIX-1 / F-192]"; `OpenAIProvider.kt` never was — `grep -n
imageData` over that file returned **0 hits** while both siblings had the branch,
so every image a model read back through a tool call was invisible on OpenAI
Chat-Completions *and* Responses APIs. Classic "fixed 2 of 3" family recurrence:
the defect is invisible in review because the file looks complete, and it is
invisible at runtime because the request still succeeds (the model simply never
sees the bytes).

[deep-audit-1045 A-03] Sibling criterion, same family ("bytes that exist but
never reach the wire"): the three provider serializers each drop messages the
orphan-tool-pairing sanitizer emptied, using
`contentParts.isNotEmpty() || content.isNotEmpty()` — i.e. "is there TEXT?".
A `minis-model-use` turn carrying only an image or only an audio block has no
text, so the filter deleted the whole user turn and the payload never left the
device (the request stayed valid and semantically empty). The predicate now
lives once, in `MessagePayloadFilter.kt:hasWirePayload(...)`, and every
empty-message filter site must route through it. Criteria 5/6 below pin the
decision (does a media-only turn survive?) and the single source.

Criterion (structural — behaviour is pinned by `OpenAIToolResultImagesTest` and
`MessagePayloadFilterTest`):
  1. Every `*.kt` under the provider package that handles tool-result parts —
     either a `when` branch (`is AgentContentPart.ToolResult ->`) or a filter
     (`filterIsInstance<AgentContentPart.ToolResult>()`) — must reference
     `imageData` somewhere in the file. A provider that serializes tool results
     without ever looking at the bytes is the defect.
  2. Each individual `is AgentContentPart.ToolResult ->` branch body must itself
     reference `imageData`: a provider that handles one tool-result site and not
     the other (the shape OpenAI would have had if only its Chat path had been
     patched) fails here.
  3. Deliberate non-serializers live in EXEMPTIONS with (a) a written reason and
     (b) a `symbol` that must still exist in the file. A stale exemption — the
     file no longer handles tool results, or the cited symbol was renamed —
     is itself a failure, so an exemption cannot outlive the shape it excused.
  4. Scanning zero files is a hard failure (a path typo must not read as green).

Usage: python3 scripts/scan/tool_result_image_branch_check.py [repo_root]
       python3 scripts/scan/tool_result_image_branch_check.py --selftest
Exit:  0 = pass, 1 = violation (file:line printed)
"""
import os
import re
import shutil
import sys
import tempfile

# [batch6] structural analysis comes from the shared layer; this file keeps
# no lexer of its own (see the criteria helpers below).
from _kotlin_lexer import KotlinLexError
from _kotlin_ast import (match_bracket, split_top, strip_parens, const_bool,
                         const_value, stmt_spans, function_body, tail_expr,
                         if_blocks as shared_if_blocks, enclosing_conditions,
                         when_branches)
from _kotlin_valueflow import (code_view, literal_view, value_sources,
                               peel_discarding, contains_ident, guard_feasible,
                               path_feasible, call_args, expr_end)

PROVIDER_REL = os.path.join(
    "src", "android", "app", "src", "main", "java", "com", "rikkaminis", "app", "provider"
)

WHEN_BRANCH = "is AgentContentPart.ToolResult ->"
FILTER_CALL = "filterIsInstance<AgentContentPart.ToolResult>()"
IMAGE_FIELD = "imageData"

# [deep-audit-1045 A-03] The empty-message filter (derived from the tree: any
# `.filter {` / `.filterIndexed {` whose body asks `contentParts.isNotEmpty()`)
# must route through the shared payload predicate, and that predicate must weigh
# every media payload the providers can serialize.
# A site is either the delegated form (predicate call) or the legacy inline form
# (the marker the pre-fix tree used) — both are derived, so a reverted site is
# caught by the same criterion that a new site is covered by.
EMPTY_FILTER_SITE_MARKS = ("hasWirePayload(", "contentParts.isNotEmpty()")
PAYLOAD_PREDICATE_FILE = "MessagePayloadFilter.kt"
PAYLOAD_PREDICATE_DECL = "fun LLMMessage.hasWirePayload("
PAYLOAD_PREDICATE_CALL = "hasWirePayload("
PAYLOAD_MEDIA_ARMS = ("audioParts.isNotEmpty()", "imageParts.isNotEmpty()")
PAYLOAD_TURN_IMAGE_ARM = "turnImageCount > 0"
MIN_EMPTY_FILTER_SITES = 3

# file (provider-relative) -> {"reason": ..., "symbol": ...}
EXEMPTIONS = {
    "RequestSanitizers.kt": {
        "reason": (
            "sanitizeToolPairing only decides whether to KEEP or DROP whole parts "
            "(orphan tool_use / tool_result pairing) and never serializes bytes to "
            "any wire format, so there is nothing for it to consume"
        ),
        "symbol": "fun sanitizeToolPairing(",
    },
}


# ── [batch6] shared analysis layer ──────────────────────────────────────────
# Every structural question (braces / statements / constant folding / value
# flow / reachability) comes from `_kotlin_lexer` / `_kotlin_ast` /
# `_kotlin_valueflow`. This file no longer carries its own comment+string
# stripper, its own brace counter, or its own literal-whitelist dead-branch
# regex: the sixth-batch witnesses walked through all three
# (`(1 == 2) && …`, a wrapped `if (false)` that never mentions the needle,
# `?.let { if (true) return@let … }`).

EXIT_RE = re.compile(r"^(?:return|throw|break|continue)\b")
IF_KW = re.compile(r"\bif\s*\(")
FOR_RE = re.compile(r"\bfor\s*\(")
BIND_RE = re.compile(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=(?!=)")
IMAGE_IDENT_HINT = re.compile(r"[A-Za-z_]\w*[Ii]mage\w*")
SCOPE_LAMBDA_RE = re.compile(
    r"^(?:\?\.|\.)\s*(?:let|run|also|apply|takeIf|takeUnless|forEach|onEach|ifEmpty)\s*\{")
CONST_EMPTY_RE = re.compile(
    r"^(?:emptyList|emptySet|emptySequence|emptyArray|listOf|setOf|sequenceOf)"
    r"\s*(?:<[^<>]*>)?\s*\(\s*\)$")
FLIP_OP = {"==": "==", "!=": "!=", "<": ">", ">": "<", "<=": ">=", ">=": "<="}
INT_CMP_RE = re.compile(r"^([A-Za-z_]\w*)\s*(==|!=|<=|>=|<|>)\s*(-?\d+)$")
INT_CMP_REV_RE = re.compile(r"^(-?\d+)\s*(==|!=|<=|>=|<|>)\s*([A-Za-z_]\w*)$")


def _lex(text):
    """Structure view + fail-closed policy. A lex failure must surface as a
    violation (the callers translate KotlinLexError), never fall back to
    scanning raw text where comments and string contents can fake a match."""
    return code_view(text)


def _brace_enclosures(code, pos):
    """Innermost-first [(open, close)] for every brace block containing `pos`.
    None = unbalanced (fail-closed)."""
    stack = []
    for i, ch in enumerate(code[:pos]):
        if ch == "{":
            stack.append(i)
        elif ch == "}" and stack:
            stack.pop()
    out = []
    for i in reversed(stack):
        j = match_bracket(code, i)
        if j < 0:
            return None
        out.append((i, j))
    return out


def stmt_is_exit(code, start, end):
    """Does [start,end) leave its enclosing block unconditionally?

    `return` / `throw` / `break` / `continue` at statement head, and
    `if (<const true>) <exit>` — the shape a witness uses to cut a block short
    without deleting anything (`if (true) return@let`). A non-constant `if`
    never counts: only something the compiler can fold is proof."""
    raw = code[start:end]
    stmt = raw.strip()
    if not stmt:
        return False
    lead = len(raw) - len(raw.lstrip())
    if EXIT_RE.match(stmt):
        return True
    m = re.match(r"if\s*\(", stmt)
    if not m:
        return False
    op = start + lead + m.end() - 1
    cp = match_bracket(code, op)
    if cp < 0 or cp >= end:
        return False
    if const_bool(code[op + 1:cp]) is not True:
        return False
    return bool(re.search(r"\b(?:return|throw|break|continue)\b", code[cp + 1:end]))


def live_path(code, pos):
    """`pos` can actually run: every enclosing condition is feasible AND no
    unconditional exit sits before it inside any enclosing block."""
    try:
        if not path_feasible(code, pos):
            return False
        enc = _brace_enclosures(code, pos)
    except KotlinLexError:
        return False
    if enc is None:
        return False
    for open_i, close_i in enc:
        body_start, body_end = open_i + 1, close_i
        for a, b in stmt_spans(code[body_start:body_end]):
            s, e = body_start + a, body_start + b
            if e > pos:
                break
            if stmt_is_exit(code, s, e):
                return False
    return True


def _following_scope_lambda(code, pos):
    """(open, close) of the lambda a reference at `pos` hands its value to
    (`x?.let {`, `x.ifEmpty {`, …), or None."""
    m = SCOPE_LAMBDA_RE.match(code[pos + len(IMAGE_FIELD):])
    if not m:
        return None
    brace = pos + len(IMAGE_FIELD) + m.end() - 1
    close = match_bracket(code, brace)
    return (brace, close) if close > 0 else None


def _lambda_live_work(code, brace, close):
    """How many statements of the lambda can actually run (0 = fully dead).

    The `params ->` header is cut first: it ends with `->`, which the statement
    splitter treats as a continuation, so otherwise the first statement reads
    `img -> if (true) return@let …` and the exit hides inside it (the same
    block-header trap that hid a dead arm in the i18n gate)."""
    body = _filter_lambda_body(code, brace, close)[0]
    live = 0
    for a, b in stmt_spans(code[body:close]):
        s, e = body + a, body + b
        if stmt_is_exit(code, s, e):
            break  # everything after the exit is unreachable
        live += 1
    return live


def _inside_condition(code, pos):
    """A reference inside an `if`/`when` condition is a TEST, not a consumption:
    `if (part.imageData != null && …)` keeps the token while the bytes go
    unused, which is how a witness hides a dropped image behind the guard that
    is supposed to protect it."""
    for m in IF_KW.finditer(code):
        p_open = m.end() - 1
        p_close = match_bracket(code, p_open)
        if p_close > 0 and p_open < pos < p_close:
            return True
    for m in re.finditer(r"\bwhen\s*\(", code):
        p_open = m.end() - 1
        p_close = match_bracket(code, p_open)
        if p_close > 0 and p_open < pos < p_close:
            return True
    return False


def dead_guard_in(text):
    """Reason text when the image reference inside `text` can never reach the
    wire. Three shapes, one question — "can this reference run?":

      1. every reference sits under a condition that can never hold
         (`if (false && …)`, `if ((1 == 2) && …)`, an outer `if (false)`
         wrapper that never mentions the needle, `when (false) { … }`);
      2. every live reference hands its value to a lambda that can never do
         work (`?.let { if (true) return@let … }`);
      3. an unconditional exit precedes the reference in its own block.
    """
    try:
        code = _lex(text)
    except KotlinLexError as exc:
        return ("unparsable ToolResult branch (%s) — refusing to report green"
                % exc)
    positions = [m.start() for m in re.finditer(r"\b%s\b" % IMAGE_FIELD, code)]
    if not positions:
        return None
    dead_conditions = []
    live = []
    unbalanced = False
    for p in positions:
        if _brace_enclosures(code, p) is None:
            unbalanced = True
            continue
        if not live_path(code, p) or _inside_condition(code, p):
            if not live_path(code, p):
                for _kind, cond in enclosing_conditions(code, p):
                    c = strip_parens(cond)
                    if const_bool(c) is False or not guard_feasible(cond):
                        dead_conditions.append(re.sub(r"\s+", " ", cond).strip()[:80])
            else:
                dead_conditions.append(re.sub(r"\s+", " ", code[p:p + 40]).strip()[:60])
            continue
        live.append(p)
    if unbalanced:
        return "unbalanced braces in the ToolResult branch — refusing to report green"
    if not live:
        detail = ("`%s`" % dead_conditions[0]) if dead_conditions else "an exit before it"
        return ("every `%s` reference sits where it can never run (%s) — the image "
                "path is dead code" % (IMAGE_FIELD, detail))
    consuming = 0
    for p in live:
        rng = _following_scope_lambda(code, p)
        if rng is None or _lambda_live_work(code, rng[0], rng[1]) > 0:
            consuming += 1
    if consuming == 0:
        return ("`%s` is read and then handed to a block that can never do work — "
                "the image path is dead code" % IMAGE_FIELD)
    return None


def payload_predicate_body(text):
    """Body of the shared payload predicate — block or expression form, braces
    and strings handled by the shared lexer, so a literal `}` inside the body
    cannot truncate the slice and a blank line cannot hide an arm."""
    try:
        code = _lex(text)
    except KotlinLexError:
        return None
    fb = function_body(code, "hasWirePayload")
    if fb is None:
        return None
    start, end, _kind = fb
    body = code[start:end]
    return body if body.strip() else None


def chain_feasible(expr):
    """Can this `&&` chain ever be true? Constant folding (shared `const_bool`)
    plus same-variable integer range intersection — `x > 0 && x < 0` is dead
    even though no literal is involved."""
    lo, hi = {}, {}
    spans = split_top(expr, ("&&",)) or [(0, len(expr))]
    for a, b in spans:
        part = strip_parens(expr[a:b].strip())
        if not part:
            continue
        folded = const_bool(part)
        if folded is False:
            return False
        if folded is True:
            continue
        m = INT_CMP_RE.match(part)
        if m:
            name, op, num = m.group(1), m.group(2), int(m.group(3))
        else:
            m = INT_CMP_REV_RE.match(part)
            if not m:
                continue  # unknown / disjunctive operand: cannot prove infeasible
            num, op, name = int(m.group(1)), FLIP_OP[m.group(2)], m.group(3)
        if op == ">=":
            lo[name] = max(lo.get(name, num), num)
        elif op == ">":
            lo[name] = max(lo.get(name, num + 1), num + 1)
        elif op == "<=":
            hi[name] = min(hi.get(name, num), num)
        elif op == "<":
            hi[name] = min(hi.get(name, num - 1), num - 1)
        elif op == "==":
            lo[name] = max(lo.get(name, num), num)
            hi[name] = min(hi.get(name, num), num)
        else:  # !=
            if name in lo and name in hi and lo[name] == hi[name] == num:
                return False
    for name in set(lo) & set(hi):
        if lo[name] > hi[name]:
            return False
    return True


def dead_media_arms(body):
    """-> [(marks, arm_text)] for every top-level `||` arm of the predicate that
    can never hold. `false &&` and `(1 == 2) &&` both fold to false here; the
    arms stay in the text either way, which is exactly why presence alone was
    blind."""
    try:
        code = _lex(body)
    except KotlinLexError:
        return [((), "<unparsable predicate body>")]  # fail-closed
    out = []
    spans = split_top(code, ("||",)) or [(0, len(code))]
    for a, b in spans:
        arm = code[a:b]
        if chain_feasible(strip_parens(arm.strip())):
            continue
        marks = tuple(mk for mk in PAYLOAD_MEDIA_ARMS + (PAYLOAD_TURN_IMAGE_ARM,)
                      if mk in arm)
        out.append((marks, re.sub(r"\s+", " ", arm).strip()[:90]))
    return out


def predicate_param_names(predicate_text):
    """Parameter names of the shared predicate, derived from its declaration."""
    try:
        code = _lex(predicate_text)
    except KotlinLexError:
        return []
    m = re.search(r"\bfun\s+[\w.]*\bhasWirePayload\s*\(", code)
    if not m:
        return []
    op = m.end() - 1
    cp = match_bracket(code, op)
    if cp < 0:
        return []
    names = []
    for a, b in split_top(code[op + 1:cp], (",")):
        decl = code[op + 1 + a:op + 1 + b].strip()
        if not decl:
            continue
        nm = re.match(r"([A-Za-z_]\w*)\s*:", decl)
        if nm:
            names.append(nm.group(1))
    return names


def _filter_lambda_body(code, brace, close):
    """Interior start after the lambda's `params ->` header, if one is present."""
    seg = code[brace + 1:close]
    depth = 0
    i = 0
    while i < len(seg):
        ch = seg[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif depth == 0 and seg.startswith("->", i):
            return brace + 1 + i + 2, close
        i += 1
    return brace + 1, close


def predicate_call_problems(code, brace, close, params, rel, line):
    """A delegating site must delegate the DECISION. The predicate call has to
    be the whole filter decision, and every argument must be derived —
    `hasWirePayload(isLastUserTurn = false, turnImageCount = 0)` keeps the call
    spelled and still deletes the media-only turn."""
    problems = []
    body_start, body_end = _filter_lambda_body(code, brace, close)
    spans = stmt_spans(code[body_start:body_end])
    if not spans:
        return ["%s:%d the empty-message filter body is empty — no decision at all"
                % (rel, line)]
    s, e = body_start + spans[-1][0], body_start + spans[-1][1]
    decision = code[s:e].strip()
    decision = re.sub(r"^return@[A-Za-z_]\w*\s*", "", decision)
    vague = ("%s:%d the filter's decision is not the shared predicate call alone "
             "(`%s`) — an extra conjunct re-drops media-only turns the predicate "
             "just kept" % (rel, line, re.sub(r"\s+", " ", decision)[:80]))
    m = re.match(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*\s*\.\s*hasWirePayload\s*\(",
                 decision)
    if not m:
        problems.append(vague)
        return problems
    op = m.end() - 1
    cp = match_bracket(decision, op)
    if cp != len(decision) - 1:
        problems.append(vague)
        return problems
    named, positional = {}, []
    for a, b in split_top(decision[op + 1:cp], (",")):
        raw = decision[op + 1 + a:op + 1 + b].strip()
        if not raw:
            continue
        nm = re.match(r"([A-Za-z_]\w*)\s*=(?!=)\s*(.*)$", raw, re.S)
        if nm:
            named[nm.group(1)] = nm.group(2).strip()
        else:
            positional.append(raw)
    provided = set(named) | set(params[:len(positional)])
    missing = [p for p in params if p not in provided]
    if missing:
        problems.append(
            "%s:%d the predicate call omits %s — the filter cannot weigh a payload "
            "it is never told about" % (rel, line, ", ".join(missing)))
    for name, val in list(named.items()) + list(zip(params, positional)):
        if val and (const_value(val) is not None or const_bool(val) is not None):
            problems.append(
                "%s:%d the predicate is called with the constant `%s = %s` — the "
                "call is spelled but the decision is pinned, so a media-only turn "
                "is deleted" % (rel, line, name, re.sub(r"\s+", " ", val)[:40]))
    return problems



def branch_block(text, start):
    """The ToolResult branch body opened at/after `start`: the brace-balanced
    block when the branch is brace-bodied, or the single statement when it is
    braceless (`is AgentContentPart.ToolResult -> put(...)`)."""
    code = _lex(text)  # KotlinLexError propagates: callers report, never guess
    arrow = code.find("->", start)
    brace = code.find("{", start)
    if brace >= 0 and (arrow < 0 or arrow < brace):
        close = match_bracket(code, brace)
        return text[start:close + 1] if close > 0 else None
    if arrow >= 0:
        return text[start:expr_end(code, arrow + 2)]
    return None


def payload_filter_sites(code):
    """Derive the empty-message filter sites in one file: every brace block that
    opens a `.filter` / `.filterIndexed` lambda and either delegates to the
    shared predicate or still carries the legacy inline marker. No line numbers
    and no file list, so a new provider call site is covered the day it lands,
    and a site reverted to the inline predicate is caught by the same pass.

    -> [(line, brace_open, brace_close)]
    """
    sites = []
    seen = set()
    for mark in EMPTY_FILTER_SITE_MARKS:
        for m in re.finditer(re.escape(mark), code):
            enc = _brace_enclosures(code, m.start())
            if not enc:
                continue
            brace, close = enc[0]  # innermost block = the filter lambda
            if brace in seen:
                continue
            if not re.search(r"\.filter(Indexed)?\s*$", code[max(0, brace - 40):brace]):
                continue
            seen.add(brace)
            sites.append((code[:brace].count("\n") + 1, brace, close))
    return sites


def check_payload_filters(root, provider_dir, texts):
    """[deep-audit-1045 A-03] Empty-message filters must keep media-only turns,
    through the one shared predicate — and a site that spells the call while
    feeding it constants (or dropping a payload argument, or ANDing an extra
    text-only conjunct onto the decision) is the same defect wearing the fix's
    clothes."""
    violations = []
    total = 0
    predicate = texts.get(PAYLOAD_PREDICATE_FILE)
    params = predicate_param_names(predicate) if predicate is not None else []
    for rel, text in texts.items():
        try:
            code = _lex(text)
        except KotlinLexError as exc:
            violations.append("%s: cannot be parsed (%s) — refusing to report green"
                              % (rel, exc))
            continue
        for line, brace, close in payload_filter_sites(code):
            total += 1
            block = code[brace:close + 1]
            if PAYLOAD_PREDICATE_CALL in block:
                violations.extend(predicate_call_problems(
                    code, brace, close, params, rel, line))
                continue
            missing = [arm for arm in PAYLOAD_MEDIA_ARMS if arm not in block]
            violations.append(
                "%s:%d empty-message filter drops media-only turns (no %s, no shared "
                "%s) — a minis-model-use turn carrying only an image or an audio block "
                "is deleted and the bytes never reach the model"
                % (rel, line, " / ".join(missing) or "<media arm>", PAYLOAD_PREDICATE_CALL)
            )
    if total < MIN_EMPTY_FILTER_SITES:
        violations.append(
            "only %d empty-message filter site(s) found under the provider package "
            "(expected >= %d) — the filter was removed or renamed, which either loses the "
            "sanitizer defense or hides the media arm from this gate"
            % (total, MIN_EMPTY_FILTER_SITES)
        )
    if predicate is None:
        violations.append(
            "%s: the shared payload predicate file is gone — the three serializers are "
            "free to drift apart again" % PAYLOAD_PREDICATE_FILE
        )
        return violations, total
    if PAYLOAD_PREDICATE_DECL not in _lex(predicate):
        violations.append(
            "%s: '%s' declaration not found (the predicate was inlined or renamed)"
            % (PAYLOAD_PREDICATE_FILE, PAYLOAD_PREDICATE_DECL)
        )
        return violations, total
    body = payload_predicate_body(predicate)
    if body is None:
        violations.append(
            "%s: the payload predicate's body could not be derived — refusing to report "
            "green (the declaration shape moved)" % PAYLOAD_PREDICATE_FILE)
        return violations, total
    for marks, arm in dead_media_arms(body):
        if marks:
            violations.append(
                "%s: %s can never be weighed — the arm sits behind a condition that can "
                "never hold (`%s`), so a media-only turn is deleted and its bytes never "
                "reach the model"
                % (PAYLOAD_PREDICATE_FILE, ", ".join("`%s`" % mk for mk in marks), arm))
        else:
            violations.append(
                "%s: the payload predicate's decision is short-circuited by a condition "
                "that can never hold (`%s`) — every media arm is still present as text "
                "and none of them is weighed, so a media-only turn is deleted and its "
                "bytes never reach the model" % (PAYLOAD_PREDICATE_FILE, arm))
    alive = [arm for arm in PAYLOAD_MEDIA_ARMS + (PAYLOAD_TURN_IMAGE_ARM,)
             if arm not in body]
    if alive:
        violations.append(
            "%s: the payload predicate no longer weighs %s — that payload can only come "
            "from a media-only turn, so dropping the arm re-drops the user's turn"
            % (PAYLOAD_PREDICATE_FILE, ", ".join(alive))
        )
    return violations, total


# ── [deep-audit-1047 A-03] Declared carriers must have a wire consumer ──────
# `hasWirePayload` is the ONE place that decides "this turn still has payload".
# Every arm it weighs is therefore a promise to some serializer: the turn is
# kept, so the serializer had better know what to do with those bytes. Until
# 1047 nothing on the wire side read `msg.imageParts` at all — the promise was
# unbacked, and the empty filter happily let a text-less image turn through to
# an empty `content: ""`.
#
# C-IMG-1 (declaration implies consumption): derive the carrier list from the
#     predicate BODY (`<carrier>.isNotEmpty()` arms) and require `.<carrier>`
#     reads in the files that actually write media wire parts. Byte-estimate
#     readers (`LLMProvider.approxInputBytes`) do NOT count — that is exactly
#     the read that made the old gate blind. A read that cannot affect the wire
#     does not count either: dead-path reads, a binding nobody uses, and a loop
#     over a constant-empty list are all "read and dropped".
# C-IMG-2 (images and audio must not be mutually exclusive): the `input_audio`
#     write may not sit in a branch that the image carrier can close — neither
#     an `else` sibling of an image branch nor a brace-less `if
#     (images.isEmpty())` wrapper — because a turn carrying both would lose one
#     of them silently.
#
# Both derive from the tree: no carrier list, no file list, no line numbers.

CARRIER_ARM_RE = re.compile(r"([A-Za-z_]\w*)\.isNotEmpty\(\)")
MEDIA_WIRE_MARKS = (
    re.compile(r'put\(\s*"type"\s*,\s*"image_url"'),
    re.compile(r'put\(\s*"type"\s*,\s*"input_image"'),
    re.compile(r'put\(\s*"type"\s*,\s*"image"'),
    re.compile(r'put\(\s*"inlineData"'),
    # The shared image encoders (OpenAIToolResultImages.chat/responsesUserContent)
    # build the parts themselves, so a serialization site may hand them the bytes
    # without ever spelling an image marker inline.
    re.compile(r"\b[A-Za-z_]*UserContent\s*\("),
)
AUDIO_WRITE_RE = re.compile(r'put\(\s*"type"\s*,\s*"input_audio"')
MIN_MEDIA_SERIALIZER_FILES = 2


def has_media_image_mark(text):
    return any(mark.search(text) for mark in MEDIA_WIRE_MARKS)


def _is_media_carrier(carrier):
    low = carrier.lower()
    return "audio" in low or "image" in low


def predicate_carrier_arms(predicate_text):
    """-> set of carrier names the shared payload predicate weighs, or None."""
    span = payload_predicate_body(predicate_text)
    if span is None:
        return None
    return {m.group(1) for m in CARRIER_ARM_RE.finditer(span)}


def _dead_binding(code, pos):
    """Name of the local a read at `pos` is bound to when that local is never
    used again (the value is read and thrown away), else None."""
    for m in BIND_RE.finditer(code):
        rhs_start = m.end()
        if rhs_start > pos:
            break
        end = expr_end(code, rhs_start)
        if not (rhs_start <= pos < end):
            continue
        name = m.group(1)
        uses = len(re.findall(r"(?<![\w`])%s(?![\w`])" % re.escape(name), code))
        return name if uses <= 1 else None
    return None


def _empty_media_loops(code, flat):
    """-> [(line, iterable)] for `for (x in <constant empty>)` whose body still
    writes a media wire part: the loop is a no-op wearing the serialization."""
    out = []
    for m in FOR_RE.finditer(code):
        op = m.end() - 1
        cp = match_bracket(code, op)
        if cp < 0:
            continue
        header = code[op + 1:cp].strip()
        h = re.match(r"^([A-Za-z_]\w*)\s*(?::[^,]+)?\s+in\s+(.*)$", header, re.S)
        if not h:
            continue
        iterable = re.sub(r"\s+", " ", h.group(2)).strip()
        if not CONST_EMPTY_RE.match(iterable):
            continue
        brace = code.find("{", cp)
        if brace < 0:
            continue
        close = match_bracket(code, brace)
        if close < 0:
            continue
        body = flat[brace:close + 1]
        if has_media_image_mark(body) or AUDIO_WRITE_RE.search(body):
            out.append((code[:m.start()].count("\n") + 1, iterable))
    return out


def check_carrier_consumption(texts):
    """C-IMG-1: a carrier the predicate weighs must be READ by a serializer, on
    a live path, into something that reaches the wire."""
    violations = []
    predicate = texts.get(PAYLOAD_PREDICATE_FILE)
    arms = predicate_carrier_arms(predicate) if predicate is not None else None
    if not arms:
        violations.append(
            "%s: no `<carrier>.isNotEmpty()` arm could be derived from the payload "
            "predicate — refusing to report green" % PAYLOAD_PREDICATE_FILE)
        return violations
    serializers = {}
    for rel, text in texts.items():
        try:
            code = _lex(text)
        except KotlinLexError as exc:
            violations.append("%s: cannot be parsed (%s) — refusing to report green"
                              % (rel, exc))
            continue
        flat = literal_view(text)
        if has_media_image_mark(flat):
            serializers[rel] = (code, flat)
    if len(serializers) < MIN_MEDIA_SERIALIZER_FILES:
        violations.append(
            "only %d file(s) under the provider package write a media wire part "
            "(expected >= %d) — the serializers moved or the markers changed, so "
            "carrier consumption cannot be judged"
            % (len(serializers), MIN_MEDIA_SERIALIZER_FILES))
        return violations
    for carrier in sorted(arms):
        read = re.compile(r"\.%s\b" % re.escape(carrier))
        if not any(read.search(flat) for _code, flat in serializers.values()):
            violations.append(
                "%s: the payload predicate keeps a turn alive for `%s.isNotEmpty()`, but "
                "no media serializer under the provider package ever reads `.%s` — those "
                "bytes are dropped and a text-less turn goes out as `content: \"\"`"
                % (PAYLOAD_PREDICATE_FILE, carrier, carrier))
    for carrier in sorted(arms):
        if not _is_media_carrier(carrier):
            continue  # text carriers are not serialized by a media loop
        read = re.compile(r"\.%s\b" % re.escape(carrier))
        for rel in sorted(serializers):
            code, flat = serializers[rel]
            for m in read.finditer(code):
                pos = m.start()
                line = code[:pos].count("\n") + 1
                if not live_path(code, pos):
                    violations.append(
                        "%s:%d the `.%s` read sits on a path that can never run — the "
                        "serializer reads the carrier only in dead code, so a media-only "
                        "turn still goes out empty" % (rel, line, carrier))
                    continue
                dead = _dead_binding(code, pos)
                if dead is not None:
                    violations.append(
                        "%s:%d the `.%s` read is bound to `%s`, which nothing ever uses — "
                        "the bytes are read and dropped, so the turn goes out empty"
                        % (rel, line, carrier, dead))
            for line, iterable in _empty_media_loops(code, flat):
                violations.append(
                    "%s:%d iterates the constant-empty list `%s` while still writing media "
                    "parts — the serialization loop is a no-op and those bytes are dropped "
                    "even though the payload predicate kept the turn"
                    % (rel, line, iterable))
    return violations


def if_sites(code):
    """(if_start, cond, then_start, then_end, else_start, else_end) for every
    `if`, brace-bodied or not — the branch spans come from the shared
    `if_blocks`, so a brace-less `if (cond) for (...) { … }` is not invisible."""
    by_then = {}
    for cond, t_start, t_end, e_start, e_end in shared_if_blocks(code):
        by_then.setdefault(t_start, (cond, t_start, t_end, e_start, e_end))
    out = []
    for m in IF_KW.finditer(code):
        p_open = m.end() - 1
        p_close = match_bracket(code, p_open)
        if p_close < 0:
            continue
        b = by_then.get(p_close + 1)
        if b is None:
            continue
        out.append((m.start(),) + b)
    return out


EMPTY_CALL_RE = re.compile(
    r"^([A-Za-z_][\w.]*)\s*\.\s*(isEmpty|isNotEmpty|size|count|length)\s*\(\s*\)$")
EMPTY_ZERO_RE = re.compile(r"^(.+?)\s*(==|<|<=)\s*0$")
ZERO_EMPTY_RE = re.compile(r"^0\s*(==|>|>=)\s*(.+)$")


def _asserts_no_images(conjunct):
    """Does this conjunct claim the turn carries no image bytes?"""
    c = strip_parens(conjunct.strip())
    if not c:
        return False
    if c.startswith("!"):
        inner = strip_parens(c[1:].strip())
        m = EMPTY_CALL_RE.match(inner)
        return bool(m and m.group(2) == "isNotEmpty"
                    and IMAGE_IDENT_HINT.search(m.group(1)))
    m = EMPTY_CALL_RE.match(c)
    if m and m.group(2) == "isEmpty" and IMAGE_IDENT_HINT.search(m.group(1)):
        return True
    m = EMPTY_ZERO_RE.match(c)
    if m and IMAGE_IDENT_HINT.search(m.group(1)):
        return True
    m = ZERO_EMPTY_RE.match(c)
    if m and IMAGE_IDENT_HINT.search(m.group(2)):
        return True
    return False


def _infeasible_when_images(cond):
    """True when the condition can only hold while the turn carries no images:
    every top-level `||` arm then has an image-emptiness conjunct."""
    try:
        code = _lex(cond)
    except KotlinLexError:
        return False
    arms = split_top(code, ("||",)) or [(0, len(code))]
    if not arms:
        return False
    for a, b in arms:
        conj = split_top(code[a:b], ("&&",)) or [(0, b - a)]
        base = a
        if not any(_asserts_no_images(code[base + x:base + y]) for x, y in conj):
            return False
    return True


def _exclusive_media_split(flat, sites):
    """The `if` whose two branches hold the two carriers *and nothing else* —
    the exclusive-sibling shape (`if (images…) { image parts } else if
    (audio…) { input_audio }`).

    Deliberately narrow: an `if` whose branches merely CONTAIN the writes deep
    inside (a whole serialization path in one arm) is not a media-exclusivity
    decision, and flagging it would red the merged tree for a shape that drops
    nothing."""
    out = []
    for if_start, cond, t_start, t_end, e_start, e_end in sites:
        if e_start is None or e_end is None:
            continue
        then_txt = flat[t_start:t_end]
        else_txt = flat[e_start:e_end]
        then_img = has_media_image_mark(then_txt)
        else_img = has_media_image_mark(else_txt)
        then_aud = bool(AUDIO_WRITE_RE.search(then_txt))
        else_aud = bool(AUDIO_WRITE_RE.search(else_txt))
        if then_img and not then_aud and else_aud and not else_img:
            out.append((if_start, cond))
        elif then_aud and not then_img and else_img and not else_aud:
            out.append((if_start, cond))
    return out


def check_image_audio_not_exclusive(texts):
    """C-IMG-2.

    Anchored on the two WRITES, not on a carrier name: whatever feeds the image
    parts, an `input_audio` write that the image carrier can close — an `else`
    sibling of an image branch, or a guard such as `if (msg.imageParts.isEmpty()
    && turnImages.isEmpty())` (brace-less or not) — is unreachable whenever
    images are attached. The merged shape (both writes reachable in one branch)
    is the only compliant one."""
    violations = []
    audio_sites = 0
    for rel in sorted(texts):
        text = texts[rel]
        try:
            code = _lex(text)
        except KotlinLexError as exc:
            violations.append("%s: cannot be parsed (%s) — refusing to report green"
                              % (rel, exc))
            continue
        flat = literal_view(text)
        audio_positions = [m.start() for m in AUDIO_WRITE_RE.finditer(flat)]
        if not audio_positions:
            continue
        sites = if_sites(code)
        for apos in audio_positions:
            audio_sites += 1
            line = flat[:apos].count("\n") + 1
            guarded = None
            for _kind, cond in enclosing_conditions(code, apos):
                if _infeasible_when_images(cond):
                    guarded = cond
                    break
            if guarded is not None:
                violations.append(
                    "%s:%d writes `input_audio` behind `%s` — that condition is false "
                    "whenever the turn also carries images, so the audio is dropped "
                    "silently (the two carriers must share one branch)"
                    % (rel, line, re.sub(r"\s+", " ", guarded).strip()[:80]))
                continue
        for if_start, cond in _exclusive_media_split(flat, sites):
            apos = AUDIO_WRITE_RE.search(flat).start()
            line = flat[:apos].count("\n") + 1
            violations.append(
                "%s:%d writes the two carriers (`input_audio` and the image parts) in "
                "exclusive branches of `%s` — a turn carrying both loses one of them "
                "silently (the two carriers must share one branch)"
                % (rel, line, re.sub(r"\s+", " ", cond).strip()[:60]))
    if audio_sites == 0:
        violations.append(
            "no `input_audio` write site found under the provider package — refusing to "
            "report green (the audio serialization moved)")
    return violations


def scan_root(root, exemptions=None):
    """Return (violations, scanned_files, per_file_report)."""
    exemptions = EXEMPTIONS if exemptions is None else exemptions
    provider_dir = os.path.join(root, PROVIDER_REL)
    violations = []
    report = []
    scanned = 0
    texts = {}
    if not os.path.isdir(provider_dir):
        return (["provider package not found at %s" % provider_dir], 0, [])

    for dirpath, _dirs, files in os.walk(provider_dir):
        for name in sorted(files):
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, provider_dir)
            with open(path, encoding="utf-8") as fh:
                text = fh.read()
            texts[rel] = text
            when_hits = [m.start() for m in re.finditer(re.escape(WHEN_BRANCH), text)]
            has_filter = FILTER_CALL in text
            if not when_hits and not has_filter:
                continue
            scanned += 1

            exempt = exemptions.get(rel)
            if exempt is not None:
                symbol = exempt["symbol"]
                if symbol not in text:
                    violations.append(
                        "%s: stale exemption — cited symbol %r no longer exists; "
                        "re-check whether it really never serializes bytes" % (rel, symbol)
                    )
                    report.append((rel, "STALE-EXEMPT"))
                else:
                    report.append((rel, "exempt"))
                continue

            if IMAGE_FIELD not in text:
                line = text[: when_hits[0]].count("\n") + 1 if when_hits else 1
                violations.append(
                    "%s:%d handles tool-result parts but never references %s "
                    "(tool-result images would be dropped)" % (rel, line, IMAGE_FIELD)
                )
                report.append((rel, "missing-imageData"))
                continue

            bad_branch = False
            for start in when_hits:
                line = text[:start].count("\n") + 1
                try:
                    block = branch_block(text, start)
                except KotlinLexError as exc:
                    violations.append(
                        "%s:%d the ToolResult branch cannot be parsed (%s) — refusing "
                        "to report green" % (rel, line, exc))
                    bad_branch = True
                    continue
                if block is None:
                    violations.append("%s:%d unbalanced ToolResult branch" % (rel, line))
                    bad_branch = True
                    continue
                if IMAGE_FIELD not in block:
                    violations.append(
                        "%s:%d ToolResult branch does not consume %s"
                        % (rel, line, IMAGE_FIELD)
                    )
                    bad_branch = True
                else:
                    dead = dead_guard_in(block)
                    if dead is not None:
                        violations.append(
                            "%s:%d %s"
                            % (rel, line, dead)
                        )
                        bad_branch = True

            report.append((rel, "branch-without-imageData" if bad_branch else "ok"))

    # An exemption that no longer matches any scanned file is dead weight and
    # would silently excuse a future regression in that file.
    for rel, exempt in exemptions.items():
        if not any(r[0] == rel for r in report):
            violations.append(
                "%s: exemption is stale — file no longer handles tool-result parts "
                "(%s)" % (rel, exempt["reason"][:60])
            )

    payload_violations, _sites = check_payload_filters(root, provider_dir, texts)
    violations.extend(payload_violations)
    # [deep-audit-1047 A-03] The two criteria that make "declared" mean
    # "consumed", and that keep the image / audio carriers out of mutually
    # exclusive branches.
    violations.extend(check_carrier_consumption(texts))
    violations.extend(check_image_audio_not_exclusive(texts))
    return (violations, scanned, report)


def selftest():
    """Fixtures proving the gate has teeth: a violating sample must be red, a
    compliant one green, and exemption rot must be caught."""
    tmp = tempfile.mkdtemp(prefix="trimg-gate-")
    try:
        pkg = os.path.join(tmp, PROVIDER_REL)
        os.makedirs(os.path.join(pkg, "anthropic"), exist_ok=True)

        def write(rel, body):
            path = os.path.join(pkg, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(body)

        # (a) violating: filter form, no imageData anywhere.
        write(
            "openai/ViolatingProvider.kt",
            "class V {\n"
            "    fun f(parts: List<Any>) {\n"
            "        for (tr in parts.filterIsInstance<AgentContentPart.ToolResult>()) {\n"
            "            put(\"content\", tr.content)\n"
            "        }\n"
            "    }\n"
            "}\n",
        )
        # (b) compliant: same shape, consumes the bytes.
        write(
            "gemini/CompliantProvider.kt",
            "class C {\n"
            "    fun f(parts: List<Any>) {\n"
            "        for (tr in parts.filterIsInstance<AgentContentPart.ToolResult>()) {\n"
            "            if (tr.imageData != null) put(\"blob\", tr.imageData)\n"
            "        }\n"
            "    }\n"
            "}\n",
        )
        # (c) when-branch form with the image handled in the branch body.
        write(
            "anthropic/WhenProvider.kt",
            "class W {\n"
            "    fun f(part: Any) = when (part) {\n"
            "        is AgentContentPart.ToolResult -> {\n"
            "            if (part.imageData != null) put(\"image\", part.imageData)\n"
            "        }\n"
            "        else -> Unit\n"
            "    }\n"
            "}\n",
        )
        # (d) file-level imageData present but the branch body itself ignores it.
        write(
            "anthropic/SplitProvider.kt",
            "class S {\n"
            "    fun other(p: Any) = p.imageData\n"
            "    fun f(part: Any) = when (part) {\n"
            "        is AgentContentPart.ToolResult -> {\n"
            "            put(\"content\", part.content)\n"
            "        }\n"
            "        else -> Unit\n"
            "    }\n"
            "}\n",
        )

        violations, scanned, report = scan_root(tmp)
        ok = True
        arms = [0]

        def check(label, condition, detail=""):
            nonlocal ok
            arms[0] += 1
            mark = "OK" if condition else "FAIL"
            print("  [%s] %s%s" % (mark, label, (" — " + detail) if detail and not condition else ""))
            if not condition:
                ok = False

        check("scanned 4 fixture files", scanned == 4, "scanned=%d" % scanned)
        check(
            "filter-form violation is red",
            any("ViolatingProvider.kt" in v for v in violations),
        )
        check(
            "compliant filter-form is green",
            not any("CompliantProvider.kt" in v for v in violations),
        )
        check("compliant when-branch is green", not any("WhenProvider.kt" in v for v in violations))
        check(
            "when-branch that ignores imageData inside its own body is red",
            any("SplitProvider.kt" in v and "ToolResult branch does not consume" in v for v in violations),
        )

        # (e) wide3-01: the branch keeps `imageData` but the condition that
        #     gates the encode is short-circuited by a literal — the path is dead.
        d_root = tempfile.mkdtemp(prefix="trimg-deadguard-")
        try:
            d_pkg = os.path.join(d_root, PROVIDER_REL)
            write_fixture(os.path.join(d_pkg, "anthropic/DeadGuardProvider.kt"),
                          "class D {\n"
                          "    fun f(part: Any) = when (part) {\n"
                          "        is AgentContentPart.ToolResult -> {\n"
                          "            if (false && part.imageData != null "
                          "&& part.imageMimeType != null) {\n"
                          '                put("image", part.imageData)\n'
                          "            }\n"
                          "        }\n"
                          "        else -> Unit\n"
                          "    }\n"
                          "}\n")
            v_dead_guard, _, _ = scan_root(d_root)
            check("a ToolResult image guard short-circuited by a literal is red",
                  any("DeadGuardProvider.kt" in v and "dead code" in v for v in v_dead_guard),
                  str(v_dead_guard[:2]))
        finally:
            shutil.rmtree(d_root, ignore_errors=True)

        # [batch6 A03-1/A03-2] The branch keeps `imageData` and keeps the guard
        # head; only the VALUE path died. Each shape below was a green witness
        # against the literal-whitelist version of this criterion.
        b6_root = tempfile.mkdtemp(prefix="trimg-batch6-")
        try:
            b6_pkg = os.path.join(b6_root, PROVIDER_REL)
            shapes = {
                # (A03-1) an outer `if (false)` wrapper that never mentions the
                # needle: the old criterion only read conditions CONTAINING it.
                "anthropic/OuterDeadProvider.kt": (
                    "class O {\n"
                    "    fun f(part: Any) = when (part) {\n"
                    "        is AgentContentPart.ToolResult -> {\n"
                    "            if (false) {\n"
                    "                if (part.imageData != null) {\n"
                    '                    put("image", part.imageData)\n'
                    "                }\n"
                    "            }\n"
                    "        }\n"
                    "        else -> Unit\n"
                    "    }\n"
                    "}\n"),
                # (A03-1) same family, constant that needs folding (`1 == 2`).
                "anthropic/FoldedDeadProvider.kt": (
                    "class F {\n"
                    "    fun f(part: Any) = when (part) {\n"
                    "        is AgentContentPart.ToolResult -> {\n"
                    "            if ((1 == 2) && part.imageData != null) {\n"
                    '                put("image", part.imageData)\n'
                    "            }\n"
                    "        }\n"
                    "        else -> Unit\n"
                    "    }\n"
                    "}\n"),
                # (A03-2) the guard is live, the block is cut short inside the
                # `?.let` — no `if (___)` around imageData at all.
                "anthropic/LetDeadProvider.kt": (
                    "class L {\n"
                    "    fun f(part: Any) = when (part) {\n"
                    "        is AgentContentPart.ToolResult -> {\n"
                    "            part.imageData?.let { img ->\n"
                    "                if (true) return@let\n"
                    '                put("inlineData", img)\n'
                    "            }\n"
                    "        }\n"
                    "        else -> Unit\n"
                    "    }\n"
                    "}\n"),
                # Control: the same let shape, alive.
                "anthropic/LetLiveProvider.kt": (
                    "class LL {\n"
                    "    fun f(part: Any) = when (part) {\n"
                    "        is AgentContentPart.ToolResult -> {\n"
                    "            part.imageData?.let { img ->\n"
                    "                if (img.isEmpty()) return@let\n"
                    '                put("inlineData", img)\n'
                    "            }\n"
                    "        }\n"
                    "        else -> Unit\n"
                    "    }\n"
                    "}\n"),
            }
            for rel, body in shapes.items():
                write_fixture(os.path.join(b6_pkg, rel), body)
            v_b6, _, _ = scan_root(b6_root)
            for rel, label in (
                    ("OuterDeadProvider.kt", "an outer `if (false)` wrapper that never names imageData"),
                    ("FoldedDeadProvider.kt", "a folded `(1 == 2) &&` guard"),
                    ("LetDeadProvider.kt", "`if (true) return@let` cutting the let block short"),
            ):
                check("[batch6] %s is dead-code red" % label,
                      any(rel in v and "dead code" in v for v in v_b6),
                      str([x for x in v_b6 if rel in x][:1]))
            check("[batch6] a live `?.let` with a real guard stays green",
                  not any("LetLiveProvider.kt" in v for v in v_b6),
                  str([x for x in v_b6 if "LetLiveProvider" in x][:1]))
        finally:
            shutil.rmtree(b6_root, ignore_errors=True)

        # Exemption with a live symbol is honoured; a dead one is rot.
        write(
            "played/ExemptProvider.kt",
            "fun sanitizeToolPairing() {}\n"
            "class E { fun f(p: Any) = when (p) {\n"
            "    is AgentContentPart.ToolResult -> put(\"x\")\n"
            "    else -> Unit\n"
            "} }\n",
        )
        live = {
            "played/ExemptProvider.kt": {
                "reason": "fixture",
                "symbol": "fun sanitizeToolPairing(",
            }
        }
        v_live, _, _ = scan_root(tmp, live)
        check("exemption with a live symbol is honoured", not any("ExemptProvider" in v for v in v_live))

        dead = {
            "played/ExemptProvider.kt": {
                "reason": "fixture",
                "symbol": "fun noLongerHere(",
            }
        }
        v_dead, _, _ = scan_root(tmp, dead)
        check(
            "exemption with a renamed symbol is reported as rot",
            any("stale exemption" in v for v in v_dead),
        )

        # [deep-audit-1045 A-03] media-only turns survive the empty-message filter
        def payload_tree():
            t = tempfile.mkdtemp(prefix="trimg-payload-")
            pkg = os.path.join(t, PROVIDER_REL)
            os.makedirs(pkg, exist_ok=True)

            def w(rel, body):
                path = os.path.join(pkg, rel)
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(body)

            w(PAYLOAD_PREDICATE_FILE,
              "internal fun LLMMessage.hasWirePayload(\n"
              "    isLastUserTurn: Boolean,\n"
              "    turnImageCount: Int,\n"
              "): Boolean =\n"
              "    contentParts.isNotEmpty() ||\n"
              "        content.isNotEmpty() ||\n"
              "        audioParts.isNotEmpty() ||\n"
              "        imageParts.isNotEmpty() ||\n"
              "        (isLastUserTurn && turnImageCount > 0)\n")
            for rel, var in (("openai/A.kt", "a"), ("openai/B.kt", "b"),
                             ("anthropic/C.kt", "c")):
                w(rel,
                  "class %s {\n"
                  "    fun f(raw: List<LLMMessage>, images: List<Any>) {\n"
                  "        val sites = raw\n"
                  "            .filterIndexed { index, m ->\n"
                  "                m.hasWirePayload(\n"
                  "                    isLastUserTurn = index == 0,\n"
                  "                    turnImageCount = images.size,\n"
                  "                )\n"
                  "            }\n"
                  "    }\n"
                  "}\n" % ("P" + var.upper()))
            return t, pkg

        p_root, p_pkg = payload_tree()
        try:
            v_clean, _sc, _rp = scan_root(p_root)
            check("media-aware filter sites are green",
                  not any("empty-message filter" in v or "payload predicate" in v
                          for v in v_clean),
                  str(v_clean[:2]))

            def mutate(rel, old, new_text):
                path = os.path.join(p_pkg, rel)
                with open(path, encoding="utf-8") as fh:
                    body = fh.read()
                assert old in body, "fixture mutation anchor missing in %s" % rel
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(body.replace(old, new_text, 1))

            # (1) predicate loses the audio arm
            mutate(PAYLOAD_PREDICATE_FILE, "        audioParts.isNotEmpty() ||\n", "")
            v_audio, _, _ = scan_root(p_root)
            check("dropping the audio arm is red",
                  any(PAYLOAD_PREDICATE_FILE in v and "audioParts" in v for v in v_audio),
                  str(v_audio[:2]))
            mutate(PAYLOAD_PREDICATE_FILE, "        content.isNotEmpty() ||\n",
                   "        content.isNotEmpty() ||\n        audioParts.isNotEmpty() ||\n")

            # (2) predicate loses the request-level turn-image arm
            mutate(PAYLOAD_PREDICATE_FILE,
                   "        imageParts.isNotEmpty() ||\n"
                   "        (isLastUserTurn && turnImageCount > 0)\n",
                   "        imageParts.isNotEmpty()\n")
            v_turn, _, _ = scan_root(p_root)
            check("dropping the turn-image arm is red",
                  any(PAYLOAD_PREDICATE_FILE in v and "turnImageCount" in v for v in v_turn),
                  str(v_turn[:2]))
            mutate(PAYLOAD_PREDICATE_FILE,
                   "        imageParts.isNotEmpty()\n",
                   "        imageParts.isNotEmpty() ||\n"
                   "        (isLastUserTurn && turnImageCount > 0)\n")

            # (3) one site reverts to the legacy inline predicate
            mutate("openai/B.kt",
                   "                m.hasWirePayload(\n"
                   "                    isLastUserTurn = index == 0,\n"
                   "                    turnImageCount = images.size,\n"
                   "                )\n",
                   "                m.contentParts.isNotEmpty() || m.content.isNotEmpty()\n")
            v_site, _, _ = scan_root(p_root)
            check("a site reverted to the inline text-only predicate is red",
                  any("openai/B.kt" in v and "empty-message filter" in v for v in v_site),
                  str(v_site[:2]))
            mutate("openai/B.kt",
                   "                m.contentParts.isNotEmpty() || m.content.isNotEmpty()\n",
                   "                m.hasWirePayload(\n"
                   "                    isLastUserTurn = index == 0,\n"
                   "                    turnImageCount = images.size,\n"
                   "                )\n")

            # (5) wide3-01: the media arms stay verbatim but a literal
            #     short-circuits the whole disjunction.
            write_fixture(os.path.join(p_pkg, PAYLOAD_PREDICATE_FILE),
                          "internal fun LLMMessage.hasWirePayload(\n"
                          "    isLastUserTurn: Boolean,\n"
                          "    turnImageCount: Int,\n"
                          "): Boolean =\n"
                          "    contentParts.isNotEmpty() ||\n"
                          "        content.isNotEmpty() ||\n"
                          "        (false && (audioParts.isNotEmpty() || "
                          "imageParts.isNotEmpty() || "
                          "(isLastUserTurn && turnImageCount > 0)))\n")
            v_dead, _sc, _rp = scan_root(p_root)
            check("a payload predicate short-circuited by `false &&` is red",
                  any("can never be weighed" in v and "false &&" in v for v in v_dead),
                  str(v_dead[:2]))

            # [batch6 A03-3] Folding, not literal matching: `(1 == 2) &&` and a
            # self-contradicting integer range are both dead, and neither spells
            # `false`.
            write_fixture(os.path.join(p_pkg, PAYLOAD_PREDICATE_FILE),
                          PREDICATE_BODY.replace(
                              "        (isLastUserTurn && turnImageCount > 0)\n",
                              "        ((1 == 2) && (audioParts.isNotEmpty() || "
                              "imageParts.isNotEmpty() || "
                              "(isLastUserTurn && turnImageCount > 0)))\n"))
            v_fold, _sc, _rp = scan_root(p_root)
            check("[batch6] a folded `(1 == 2) &&` payload arm is red",
                  any("can never be weighed" in v for v in v_fold), str(v_fold[:2]))
            write_fixture(os.path.join(p_pkg, PAYLOAD_PREDICATE_FILE),
                          PREDICATE_BODY.replace(
                              "        (isLastUserTurn && turnImageCount > 0)\n",
                              "        (isLastUserTurn && turnImageCount > 0 "
                              "&& turnImageCount < 0)\n"))
            v_range, _sc, _rp = scan_root(p_root)
            check("[batch6] a self-contradicting turn-image range is red",
                  any("turnImageCount > 0 && turnImageCount < 0" in v for v in v_range),
                  str(v_range[:2]))
            write_fixture(os.path.join(p_pkg, PAYLOAD_PREDICATE_FILE), PREDICATE_BODY)

            # [batch6 A03-4] The site still SPELLS the shared call; every tooth
            # here is about the decision the call actually makes.
            site_raw = ("            .filterIndexed { index, m ->\n"
                        "                m.hasWirePayload(\n"
                        "                    isLastUserTurn = index == 0,\n"
                        "                    turnImageCount = images.size,\n"
                        "                )\n"
                        "            }\n")
            for tag, new_site, want in (
                    ("constant arguments",
                     site_raw.replace("isLastUserTurn = index == 0",
                                      "isLastUserTurn = false")
                             .replace("turnImageCount = images.size",
                                      "turnImageCount = 0"),
                     "pinned"),
                    ("a dropped argument",
                     site_raw.replace("                    turnImageCount = images.size,\n", ""),
                     "omits"),
                    ("an extra text-only conjunct",
                     site_raw.replace("                )\n            }\n",
                                      "                ) && m.content.isNotEmpty()\n            }\n"),
                     "not the shared predicate call alone"),
            ):
                write_fixture(os.path.join(p_pkg, "openai/A.kt"),
                              ("class PA {\n    fun f(raw: List<LLMMessage>, images: List<Any>) {\n"
                               "        val sites = raw\n" + new_site + "    }\n}\n"))
                v_site, _sc, _rp = scan_root(p_root)
                check("[batch6] a delegating site with %s is red" % tag,
                      any(want in v for v in v_site), str(v_site[:2]))
            write_fixture(os.path.join(p_pkg, "openai/A.kt"),
                          ("class PA {\n    fun f(raw: List<LLMMessage>, images: List<Any>) {\n"
                           "        val sites = raw\n" + site_raw + "    }\n}\n"))
            v_site_ok, _sc, _rp = scan_root(p_root)
            check("[batch6] the unmodified delegating site stays green",
                  not any("openai/A.kt" in v for v in v_site_ok), str(v_site_ok[:2]))

            # (4) the shared predicate file disappears
            os.remove(os.path.join(p_pkg, PAYLOAD_PREDICATE_FILE))
            v_gone, _, _ = scan_root(p_root)
            check("a missing payload predicate file is red",
                  any("payload predicate file is gone" in v for v in v_gone),
                  str(v_gone[:2]))
        finally:
            shutil.rmtree(p_root, ignore_errors=True)

        # [deep-audit-1047 A-03] Carrier arms must have a wire consumer, and the
        # image / audio carriers must not sit in mutually exclusive branches.
        # Fixtures live at module level (MEDIA_*) so they can be inspected and
        # mutated without the payload-filter scaffolding around them.
        m_root, m_pkg, m_snapshot = build_media_tree(MEDIA_MERGED)
        try:
            v_ok, _sc, _rp = scan_root(m_root)
            check("merged image/audio branch and full carrier reads are green",
                  not media_crit_codes(v_ok), str(v_ok[:2]))

            # (1) no serializer reads a carrier the predicate weighs -> C-IMG-1 only
            for rel in m_snapshot:
                write_fixture(os.path.join(m_pkg, rel), m_snapshot[rel]
                              .replace("msg.imageParts", "msg.contentParts"))
            v_unread, _, _ = scan_root(m_root)
            check("a carrier arm with no serializer reader is C-IMG-1 red",
                  media_crit_codes(v_unread) == {"C-IMG-1"},
                  str(sorted(media_crit_codes(v_unread))))
            for rel in m_snapshot:
                write_fixture(os.path.join(m_pkg, rel), m_snapshot[rel])

            # (2) split the merged branch back into exclusive `else if` arms
            write_fixture(os.path.join(m_pkg, "openai/A.kt"),
                          m_snapshot["openai/A.kt"].replace(MEDIA_MERGED, MEDIA_EXCLUSIVE))
            v_split, _, _ = scan_root(m_root)
            check("image and audio in exclusive else-if branches is C-IMG-2 red",
                  media_crit_codes(v_split) == {"C-IMG-2"},
                  str(sorted(media_crit_codes(v_split))))
            write_fixture(os.path.join(m_pkg, "openai/A.kt"), m_snapshot["openai/A.kt"])
            v_back, _, _ = scan_root(m_root)
            check("restoring the merged branch clears the C-IMG-2 arm",
                  not media_crit_codes(v_back), str(sorted(media_crit_codes(v_back))))
            # [batch6 A03-5] An image-emptiness guard closes the audio write: a
            # turn carrying both loses the audio. Brace-less and braced.
            for tag, guard in (
                    ("brace-less", '            if (msg.imageParts.isEmpty() && '
                                   'turnImages.isEmpty()) '),
                    ("braced", '            if (msg.imageParts.isEmpty() && '
                               'turnImages.isEmpty()) {\n'),
            ):
                mutated = MEDIA_MERGED.replace(
                    "                for (audio in msg.audioParts) {\n",
                    guard + "for (audio in msg.audioParts) {\n")
                if tag == "braced":
                    mutated = mutated.replace(
                        "                }\n                if (msg.content.isNotEmpty()) {\n",
                        "                }\n            }\n                if (msg.content.isNotEmpty()) {\n", 1)
                write_fixture(os.path.join(m_pkg, "openai/A.kt"),
                              m_snapshot["openai/A.kt"].replace(MEDIA_MERGED, mutated))
                v_guard, _, _ = scan_root(m_root)
                check("[batch6] audio behind an %s image-emptiness guard is C-IMG-2 red" % tag,
                      "C-IMG-2" in media_crit_codes(v_guard), str(v_guard[:2]))
            write_fixture(os.path.join(m_pkg, "openai/A.kt"), m_snapshot["openai/A.kt"])

            # [batch6 A03-6] A read that cannot affect the wire is not a
            # consumption: a binding nobody uses plus a loop over an empty list
            # still spell `.audioParts` and `input_audio`.
            dropped = MEDIA_MERGED.replace(
                "                for (audio in msg.audioParts) {\n"
                '                    contentArray.put(JSONObject().put("type", "input_audio"))\n'
                "                }\n",
                "                val _droppedAudio = msg.audioParts\n"
                "                for (audio in emptyList<Any>()) {\n"
                '                    contentArray.put(JSONObject().put("type", "input_audio"))\n'
                "                }\n")
            write_fixture(os.path.join(m_pkg, "openai/A.kt"),
                          m_snapshot["openai/A.kt"].replace(MEDIA_MERGED, dropped))
            v_drop, _, _ = scan_root(m_root)
            check("[batch6] a dead carrier binding / empty serialization loop is C-IMG-1 red",
                  "C-IMG-1" in media_crit_codes(v_drop), str(v_drop[:2]))
            write_fixture(os.path.join(m_pkg, "openai/A.kt"), m_snapshot["openai/A.kt"])
            v_restored, _, _ = scan_root(m_root)
            check("[batch6] restoring the real loop clears both carrier arms",
                  not media_crit_codes(v_restored), str(v_restored[:2]))

            # (3) wide3-01: a carrier arm added after a BLANK LINE must still be
            #     derived — the old slice ended at the first `\n\n`.
            write_fixture(os.path.join(m_pkg, PAYLOAD_PREDICATE_FILE),
                          PREDICATE_BODY.replace(
                              "        imageParts.isNotEmpty() ||\n",
                              "        imageParts.isNotEmpty() ||\n"
                              "\n"
                              "        unconsumedCarrierParts.isNotEmpty() ||\n"))
            v_blank, _, _ = scan_root(m_root)
            check("a carrier arm after a blank line is still derived (C-IMG-1 red)",
                  media_crit_codes(v_blank) == {"C-IMG-1"},
                  str(sorted(media_crit_codes(v_blank))))
            write_fixture(os.path.join(m_pkg, PAYLOAD_PREDICATE_FILE), PREDICATE_BODY)
            if "openai/A.kt" not in m_snapshot or MEDIA_MERGED not in m_snapshot["openai/A.kt"]:
                check("media fixture carries the merged branch it mutates", False)
        finally:
            shutil.rmtree(m_root, ignore_errors=True)

        missing_dir, scanned_missing, _ = scan_root(os.path.join(tmp, "does-not-exist"))
        check(
            "missing provider package is a hard failure, not a green skip",
            bool(missing_dir) and scanned_missing == 0,
        )
        if ok:
            # stable summary line for CI-side witness (test_scan registration)
            print("TOOL_RESULT_IMAGE_BRANCH_SELFTEST=PASS (%d arms)" % arms[0])
        return 0 if ok else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)



# ── [deep-audit-1047 A-03] selftest fixtures for C-IMG-1 / C-IMG-2 ──────────
PREDICATE_BODY = (
    "internal fun LLMMessage.hasWirePayload(\n"
    "    isLastUserTurn: Boolean,\n"
    "    turnImageCount: Int,\n"
    "): Boolean =\n"
    "    contentParts.isNotEmpty() ||\n"
    "        content.isNotEmpty() ||\n"
    "        audioParts.isNotEmpty() ||\n"
    "        imageParts.isNotEmpty() ||\n"
    "        (isLastUserTurn && turnImageCount > 0)\n"
)
FILTER_SITE = (
    "        val sites = raw\n"
    "            .filterIndexed { index, m ->\n"
    "                m.hasWirePayload(\n"
    "                    isLastUserTurn = index == 0,\n"
    "                    turnImageCount = images.size,\n"
    "                )\n"
    "            }\n"
)
# Compliant shape: images (request-level + per-message) and audio share ONE branch,
# text last.
MEDIA_MERGED = (
    "        for ((index, msg) in sites.withIndex()) {\n"
    "            val legacy = (if (index == 0) images else emptyList()) + msg.imageParts\n"
    "            if (legacy.isNotEmpty() || msg.audioParts.isNotEmpty()) {\n"
    "                val contentArray = JSONArray()\n"
    "                for (part in legacy) {\n"
    '                    contentArray.put(JSONObject().put("type", "image_url"))\n'
    "                }\n"
    "                for (audio in msg.audioParts) {\n"
    '                    contentArray.put(JSONObject().put("type", "input_audio"))\n'
    "                }\n"
    "                if (msg.content.isNotEmpty()) {\n"
    '                    contentArray.put(JSONObject().put("type", "text"))\n'
    "                }\n"
    "            }\n"
    "        }\n"
)
# Defect shape (88ef9b27): images and audio are exclusive `else if` siblings.
MEDIA_EXCLUSIVE = (
    "        for ((index, msg) in sites.withIndex()) {\n"
    "            if (index == 0 && images.isNotEmpty()) {\n"
    "                val contentArray = JSONArray()\n"
    "                for (part in images) {\n"
    '                    contentArray.put(JSONObject().put("type", "image_url"))\n'
    "                }\n"
    "                val alt = msg.imageParts.count { it != null }\n"
    '                contentArray.put(JSONObject().put("alt", alt))\n'
    "            } else if (msg.audioParts.isNotEmpty()) {\n"
    "                val contentArray = JSONArray()\n"
    "                for (audio in msg.audioParts) {\n"
    '                    contentArray.put(JSONObject().put("type", "input_audio"))\n'
    "                }\n"
    "            }\n"
    "        }\n"
)


def write_fixture(path, body):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(body)


def build_media_tree(media_body):
    """-> (root, provider_dir, {rel: text}) for the C-IMG fixtures.

    A carries both carriers; B and C serialize images only (Anthropic and Gemini
    have no audio-input shape, so the carrier criterion is tree-wide by design).
    """
    root = tempfile.mkdtemp(prefix="trimg-media-")
    pkg = os.path.join(root, PROVIDER_REL)
    os.makedirs(pkg, exist_ok=True)
    files = {
        PAYLOAD_PREDICATE_FILE: PREDICATE_BODY,
        # RequestSanitizers is exempt in the real tree; a fixture tree without it
        # would report that exemption as stale and mask the arms under test.
        "RequestSanitizers.kt": "fun sanitizeToolPairing() {}\n",
        "openai/A.kt": (
            "class A {\n"
            "    fun f(raw: List<LLMMessage>, images: List<Any>) {\n"
            + FILTER_SITE
            + "        val parts = sites.map { it.contentParts.size + it.content.length }\n"
            + media_body
            + "    }\n"
            "}\n"),
        "anthropic/B.kt": (
            "class B {\n"
            "    fun f(raw: List<LLMMessage>, images: List<Any>) {\n"
            + FILTER_SITE
            + '        put("type", "image")\n'
            "        val n = sites.map { it.contentParts.size + it.content.length }\n"
            "    }\n"
            "    fun g(msg: LLMMessage) = msg.imageParts.size\n"
            "}\n"),
        "gemini/C.kt": (
            "class C {\n"
            "    fun f(raw: List<LLMMessage>, images: List<Any>) {\n"
            + FILTER_SITE
            + '        put("inlineData", images)\n'
            "        val n = sites.map { it.contentParts.size + it.content.length }\n"
            "    }\n"
            "    fun g(msg: LLMMessage) = msg.imageParts.size\n"
            "}\n"),
    }
    for rel, body in files.items():
        write_fixture(os.path.join(pkg, rel), body)
    return root, pkg, dict(files)


def media_crit_codes(violations):
    """-> the C-IMG criteria a violation list actually fails."""
    codes = set()
    for v in violations:
        if ("no media serializer under the provider package ever reads" in v
                or "read and dropped" in v
                or "reads the carrier only in dead code" in v
                or "iterates the constant-empty list" in v):
            codes.add("C-IMG-1")
        if "input_audio" in v:
            codes.add("C-IMG-2")
    return codes


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--selftest":
        print("tool_result_image_branch_check self-test:")
        return selftest()
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    violations, scanned, report = scan_root(root)
    for rel, status in report:
        print("  %-8s %s" % ("[" + status + "]", rel))
    if scanned == 0:
        violations.append(
            "no provider file handling tool-result parts was found — refusing to report green"
        )
    print("  scanned %d provider file(s)" % scanned)
    if violations:
        print("")
        print("❌ tool_result_image_branch_check findings:")
        for v in violations:
            print("   - %s" % v)
        return 1
    print("")
    print("✅ tool-result image guard: every provider serializer consumes ToolResult.imageData")
    return 0


if __name__ == "__main__":
    sys.exit(main())
