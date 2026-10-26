#!/usr/bin/env python3
"""
Gate: FIX-A (deep-audit-1019 ISSUE-01) — the extra_headers / custom-endpoint
envelope dialect must live ONCE, in ModelUseProtocol.kt.

The bug: `parseExtraHeaders` and `parseCustomEndpointPath` still had a
hand-written copy in EACH process (`ModelExecutionService.kt` and
`ModelUseOffloadHandler.kt`) and the copies had drifted — the worker dropped a
present-but-not-an-object `extra_headers` with ZERO warning, accepted any
non-empty `endpoint` as a path (symbolic modes like `chat` were written into
`provider.absoluteEndpointOverride`), and never read `image_endpoint` at all.
The fix collapsed both into `ModelUseProtocol` with one-line delegates at the
call sites (the `parseChatExtraBody` precedent).

Checks assert:
  1. the dialect definitions live in ModelUseProtocol.kt, and every definition
     remaining in a caller file is a one-line DELEGATE into ModelUseProtocol
     (a re-collapsed implementation body fails);
  2. the caller files invoke through the `ModelUseProtocol.` prefix;
  3. the silent `optJSONObject("extra_headers")` branch does not come back in
     either caller file.

[dv14-A4] ISSUE-08 — same defect one layer over (the IMAGE passthrough
dialect: implicit top-level keys minus the reserved set, plus the explicit
`extra_body` / `extra_headers` / `endpoint_path` envelope). It was written
THREE times, each copy carrying its own 17-key `imageReservedKeys` set, and
two of the three copies were already unreachable — one dead, one feeding a
reader nobody called. A set that is never the one being read cannot be kept in
sync by discipline, so:

  4. neither caller may DEFINE the reserved set (`val … imageReservedKeys` /
     `val … IMAGE_PASSTHROUGH_RESERVED_KEYS`) — it lives in ModelUseProtocol;
  5. `parseImagePassthrough` must exist in ModelUseProtocol and the caller
     that owns the image path must actually go through it;
  6. the silent `optJSONObject("extra_body")` read must not come back in a
     caller — a present-but-not-an-object `extra_body` has to be reported.

  7. [dv23-A03] the `model =` argument at EVERY `buildRequestJson(` call site in
     ModelUseOffloadHandler.kt is the ENRICHED model (the value bound from
     `…effectiveModel(`, or that call itself) — never the raw `entry.model`.
     `cmdRun` judged `--output`/input modalities on `effectiveModel` but handed
     `entry.model` to :modelservice, so every models.dev capability field was
     dropped at the process boundary (no `responseModalities` → a `--output`
     run that passed the gate here came back text-only; `maxOutputTokens` fell
     back to the provider default). Derived per call site + counted, so a
     THIRD dispatch point cannot reintroduce the raw hand-off.

Scope note: every check below is limited to ModelUseProtocol.kt and
CALLER_RELS; the gate never judges a file outside them.

Checks run on comment-stripped text, so prose in a KDoc cannot satisfy them.
Usage: python3 scripts/scan/modeluse_proto_single_source_check.py [repo_root]
       python3 scripts/scan/modeluse_proto_single_source_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage
"""
import os
import re
import sys

SRC = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload"
PROTO_REL = SRC + "/ModelUseProtocol.kt"
CALLER_RELS = (
    SRC + "/ModelExecutionService.kt",
    SRC + "/ModelUseOffloadHandler.kt",
)
FUNCS = ("parseExtraHeaders", "parseCustomEndpointPath")
SILENT_BRANCH = 'optJSONObject("extra_headers")'
PREFIX = "ModelUseProtocol."
PROTO_TOKENS = (
    "fun parseExtraHeaders(",
    "fun parseCustomEndpointPath(",
    "extra_headers is not a JSON object",
    'safeOptString("image_endpoint"',
    'startsWith("/")',
    # [dv14-A4] ISSUE-08 tokens
    "fun parseImagePassthrough(",
    "internal val imageReservedKeys",
)

# [dv14-A4] ISSUE-08 — the image passthrough dialect's single source.
IMAGE_PARSER = "parseImagePassthrough"
IMAGE_PARSER_CALL = PREFIX + IMAGE_PARSER + "("
# The caller that owns the image path: `applyImagePassthrough` is called only
# from the worker's image route, and routing itself has one owner
# (`ModelUseProtocol.decideImageRoute`). The app-process handler owns no image
# call at all and must not grow one back.
IMAGE_CALLER_REL = SRC + "/ModelExecutionService.kt"
# A caller DEFINING the set is the defect; merely reading
# `ModelUseProtocol.imageReservedKeys` is fine, so the anchor requires `val`.
IMAGE_KEY_DEF_RE = re.compile(
    r"\bval\s+(?:imageReservedKeys|IMAGE_PASSTHROUGH_RESERVED_KEYS)\b",
)
SILENT_IMAGE_BRANCH = 'optJSONObject("extra_body")'

# ── [dv16-A04] the --provider matcher must live once ───────────────────────
# list / search / run each answered `--provider` differently: list substring-
# matched model.provider+label (case-sensitive), search never read the option
# it documents, run matched label/id exactly. The fix is a single top-level
# `providerMatches(query, label, id, typeName)` every subcommand calls.
PROVIDER_MATCHER = "providerMatches("
PROVIDER_SUBJECTS = ("cmdList", "cmdSearch", "resolveEntry")
PROVIDER_SEARCH_FLAG = 'args.get("provider")'
PROVIDER_FILTER_GUARD = "if (providerFilter != null)"
PROVIDER_HANDLER_REL = CALLER_RELS[1]

# ── [dv23-A03] the model that crosses into :modelservice must be ENRICHED ──
# `cmdRun` resolves `effectiveModel` (models.dev base + overrides) and judges the
# --output / input modality guards on it, but handed the RAW `entry.model` to
# buildRequestJson. The worker rebuilds LLMModel from that JSON and never calls
# ModelsDevApi (init happens in the app process only), so every capability field
# models.dev supplied died at the boundary.
DISPATCH_REL = CALLER_RELS[1]
DISPATCH_CALL = "buildRequestJson("
# An enrichment outlet: a call into the shared resolver. Matches both
# `ModelsDevApi.effectiveModel(` and a local alias of it.
ENRICH_CALL_RE = re.compile(r"\beffective(?:Entry)?Model\s*\(")
# `val <name> [ : <type> ] = … effectiveModel(` — the binding the fix reuses.
ALIAS_RE = re.compile(
    r"\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*[^=\n]+)?=\s*[^=\n]*\beffective(?:Entry)?Model\s*\(",
)
# The raw hand-off shape the fix removed (`entry.model`, `x.model`, …).
RAW_MODEL_ARG_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_.]*\.model$")
# What may follow the enrichment call and still be "the enriched value":
# closing delimiters of enclosing scopes and plain member reads. Anything else
# (a combinator, an elvis, another call) hands over something derived from it.
_PASSTHROUGH_TAIL_RE = re.compile(r"^[)\]\}]*(?:\s*\.\s*[A-Za-z_]\w*)*$")
# [B6-t07 10-1] the field the parsed endpoint is written into.
ENDPOINT_TARGET_RE = re.compile(r"\babsoluteEndpointOverride\s*=(?!=)")

# ── [dv27-A02] the request body must reach the provider WHOLE on both paths ──
# `buildRequestJson` wrote `thinking_level` for every non-OFF turn and the
# STREAMING path parsed it, but `executeRun` — the non-streaming path — never
# read the key at all. The worker then fell back to `LLMProvider.sendMessage`'s
# default (`ThinkingLevel.OFF`), so every non-streamed offloaded request asked
# the vendor NOT to reason: silently, and on exactly the families where the
# level decides whether the model reasons at all (DeepSeek / Qwen3 / Claude 3.7+).
#
# Criteria, all DERIVED from the tree, never from a hand-written field list:
#   C1  — no orphan request key. Every request-level key derived from
#         `buildRequestJson`'s write points must be read SOMEWHERE in the worker
#         service. A third field added to the dispatcher and consumed by nobody
#         fails here.
#   C1b — [B6-t07 10-4] the value READ from such a key is the value used: the
#         binding's right-hand side must still derive from the read. A key whose
#         read is post-processed into a constant keeps the key "consumed" while
#         the user's value never reaches the provider.
#   C2  — the level reaches both provider calls, bound to a live read of
#         `thinking_level`; C2b — both paths hand over the same argument surface.
#
# Scope note: C1 is a *service-wide* read check on purpose. Three derived keys
# are legitimately single-path — `input_json` / `passthrough_request` /
# `output_ext` exist only for the non-streaming image & raw-passthrough routes,
# and `streaming` decides the route before either body runs — so a literal
# "consumed in BOTH bodies" rule would be red on a clean tree, i.e. a gate that
# cannot be trusted. The per-path half of the contract is enforced by C2 at the
# call sites, where a drop is unambiguous.
SERVICE_REL = CALLER_RELS[0]
EXEC_DISPATCH_REL = SRC + "/ModelExecutionDispatcher.kt"
# (entry point, provider method) — the pair that must agree.
EXEC_PATHS = (("executeRun", "sendMessage"), ("executeStreamingRun", "streamMessage"))
LEVEL_KEY = "thinking_level"
LEVEL_ARG = "thinkingLevel"
READ_CALLS = (
    "optString", "optInt", "optBoolean", "optLong", "optDouble",
    "optJSONObject", "optJSONArray", "opt",
    "has", "getString", "getInt", "getBoolean", "getLong", "getDouble",
    "getJSONObject", "getJSONArray",
)
KEY_READ_RE = re.compile(
    r'\b(?:%s)\(\s*"([A-Za-z_][A-Za-z0-9_]*)"' % "|".join(READ_CALLS)
)
LEVEL_READ_RE = re.compile(r'\b(?:%s)\(\s*"%s"' % ("|".join(READ_CALLS), LEVEL_KEY))
NESTED_BUILDER_RE = re.compile(r"JSON(?:Object|Array)\(\)\s*\.apply\s*\{")
IDENT_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
LEVEL_CONST_RE = re.compile(r"\bThinkingLevel\s*\.\s*[A-Za-z_]\w*")




# ── shared lexer / ast / valueflow (batch-6 hard constraint #1) ────────────────
# Everything structural reads the two shared projections of one file:
#   code — comments AND string/char contents masked (length-preserving, so every
#          position maps back onto the source);
#   lit  — comments masked, string contents kept (the JSON keys / string anchors).
# A token that lives only inside a string literal can no longer satisfy a code
# anchor, and an anchor parked in `/* … */` can no longer read as live code — the
# hand-rolled `code_only` saw neither (witness 10-6).
_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

from _kotlin_lexer import KotlinLexError  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool,
    const_value,
    function_body,
    match_bracket,
    split_top,
    stmt_spans,
    strip_parens,
)
from _kotlin_valueflow import (  # noqa: E402
    call_args,
    code_view,
    contains_ident,
    expr_end,
    literal_view,
    path_feasible,
    value_sources,
)


class Views:
    """Both shared projections of one file (equal length → positions agree)."""

    __slots__ = ("code", "lit")

    def __init__(self, text):
        self.code = code_view(text)
        self.lit = literal_view(text)


def load(root, rel, name):
    path = os.path.join(root, rel)
    if not os.path.exists(path):
        return None, ["%s: NOT FOUND at %s" % (name, path)]
    try:
        return Views(open(path, encoding="utf-8").read()), None
    except KotlinLexError as exc:
        return None, ["%s: cannot be lexed (%s) — fail-closed" % (name, exc)]


def body_pair(views, fname):
    """(code_body, lit_body, span) of `fun <fname>(`, or None (fail-closed)."""
    sp = function_body(views.code, fname)
    if sp is None:
        return None
    start, end, _kind = sp
    return views.code[start:end], views.lit[start:end], sp


def stmt_spans_at(code, pos):
    """[(abs_start, abs_end)] top-level statements of the innermost `{…}` block
    containing `pos` (or of the whole text when no block encloses it)."""
    stack = []
    for i, ch in enumerate(code[:max(0, pos)]):
        if ch == "{":
            stack.append(i)
        elif ch == "}" and stack:
            stack.pop()
    if stack:
        b = stack[-1]
        close = match_bracket(code, b)
        if close < 0:
            return []
        base = b + 1
        return [(base + a, base + z) for a, z in stmt_spans(code[base:close])]
    return [(a, z) for a, z in stmt_spans(code)]


def stmt_of(code, pos):
    """The innermost statement containing `pos`, as (start, end, text)."""
    for a, z in stmt_spans_at(code, pos):
        if a <= pos < z:
            return a, z, code[a:z]
    return None


def _absorbed(expr):
    """True when [expr]'s truth value is already decided without its call.

    [B6-t07 10-2] `inst != null && (providerMatches(…) || true)` keeps the matcher
    substring while the `|| true` arm fixes the value: the filter matches every
    entry and `--provider` does nothing again. Any operand of an `&&`/`||` chain
    (at any paren depth) that folds to a constant decides the whole expression.
    """
    e = strip_parens(expr)
    if not e:
        return False
    val = const_value(e)
    if val is not None and val[0] == "bool":
        return True
    for op in ("&&", "||"):
        parts = split_top(e, (op,))
        if len(parts) > 1:
            return any(_absorbed(e[a:b]) for a, b in parts)
    for m in re.finditer(r"[([{]", e):
        close = match_bracket(e, m.start())
        if close > 0 and _absorbed(e[m.start() + 1:close]):
            return True
    return False


def check_provider(root):
    """[dv16-A04] Every `--provider` reader goes through one matcher — and its
    RESULT is the filter (not short-circuited into a constant)."""
    v = []
    views, err = load(root, PROVIDER_HANDLER_REL, os.path.basename(PROVIDER_HANDLER_REL))
    if views is None:
        return ["%s — cannot verify the --provider matcher" % err[0]]
    name = os.path.basename(PROVIDER_HANDLER_REL)
    if views.code.count("fun %s" % PROVIDER_MATCHER) != 1:
        v.append(
            "%s: expected exactly ONE 'fun %s' definition — %d found (a second "
            "--provider decision is the pre-fix shape)"
            % (name, PROVIDER_MATCHER.rstrip("("), views.code.count("fun %s" % PROVIDER_MATCHER))
        )
    for subject in PROVIDER_SUBJECTS:
        pair = body_pair(views, subject)
        if pair is None:
            v.append(
                "%s: `%s` could not be delimited — fail-closed (member renamed or "
                "moved?)" % (name, subject)
            )
            continue
        body_code, _body_lit, span = pair
        hits = [m.start() for m in re.finditer(
            r"(?<![\w`])%s\s*\(" % re.escape(PROVIDER_MATCHER.rstrip("(")), body_code)]
        if not hits:
            v.append(
                "%s: %s does not call the single %s — its own --provider opinion "
                "is back (list/search/run drift)"
                % (name, subject, PROVIDER_MATCHER.rstrip("("))
            )
            continue
        for off in hits:
            stmt = stmt_of(views.code, span[0] + off)
            if stmt is None:
                v.append(
                    "%s: %s's matcher call could not be placed in a statement — "
                    "fail-closed" % (name, subject)
                )
                continue
            if _absorbed(stmt[2]):
                v.append(
                    "%s: %s's `%s` call is not the filter any more — its statement "
                    "`%s` is already decided (a constant `||` arm / fold), so the "
                    "--provider filter matches everything (witness 10-2)"
                    % (name, subject, PROVIDER_MATCHER.rstrip("("), " ".join(stmt[2].split())[:80])
                )
    search = body_pair(views, "cmdSearch")
    if search is not None:
        body_code, body_lit, _span = search
        if PROVIDER_SEARCH_FLAG not in body_lit:
            v.append(
                "%s: cmdSearch does not read %s — the flag is documented and "
                "ignored again" % (name, PROVIDER_SEARCH_FLAG)
            )
        if PROVIDER_FILTER_GUARD not in body_code:
            v.append(
                "%s: cmdSearch has no provider filter decision point (%s) — the "
                "read flag would be dropped" % (name, PROVIDER_FILTER_GUARD)
            )
    return v


def _alias_discards(binding):
    """(True, why) when the `val x = … effectiveModel(…) …` binding keeps only a
    DERIVED value.

    [wide-1024 Rule 7 / B6-t07 10-3] The binding is delimited by the shared
    expression-end scanner, so a continuation line (`.let { entry.model }` on the
    NEXT line) is inside it — the old `rest.find('\\n')` cut the binding at the
    first newline and the fix's own witness re-entered by inserting exactly one.
    The final value is then traced with the shared `value_sources`: every
    possible source must still be the enrichment call itself.
    """
    m = ENRICH_CALL_RE.search(binding)
    if not m:
        return False, ""
    open_pos = binding.find("(", m.end() - 1)
    if open_pos < 0:
        return True, "the enrichment call has no argument list"
    close = match_bracket(binding, open_pos)
    if close < 0:
        return True, "the enrichment call could not be delimited (fail-closed)"
    expr = binding[m.start():].strip()
    sources = value_sources(expr)
    foreign = [s for s in sources
               if not ENRICH_CALL_RE.search(s)]
    if sources and foreign:
        return True, foreign[0].strip()[:60]
    return False, ""


def check_enriched_dispatch(root):
    """[dv23-A03] Every `buildRequestJson(` hand-off resolves the enriched model."""
    v = []
    views, err = load(root, DISPATCH_REL, os.path.basename(DISPATCH_REL))
    if views is None:
        return err
    name = os.path.basename(DISPATCH_REL)
    code = views.code

    aliases = set()
    for m in ALIAS_RE.finditer(code):
        aliases.add(m.group(1))
        line = code.count("\n", 0, m.start()) + 1
        # [B6-t07 10-3] expression end, not line end: a chained `.let { … }` on
        # the next line is part of the binding.
        end = expr_end(code, m.end() - 1)
        binding = code[m.start():end]
        discards, tail = _alias_discards(binding)
        if discards:
            v.append(
                "%s:%d: `val %s = …%s` binds a DERIVED value, not the enriched model "
                "(`%s`) — the hand-off looks enriched and every models.dev capability "
                "field is dropped at the process boundary"
                % (name, line, m.group(1), " ".join(binding.strip().split())[:60], tail[:40])
            )
    if not aliases:
        v.append(
            "%s: no `val … = …effectiveModel(` binding — the enriched model is never "
            "resolved, so nothing can be handed over (fail-closed)" % name
        )

    sites = [i for i in range(len(code)) if code.startswith(DISPATCH_CALL, i)]
    if not sites:
        v.append(
            "%s: no '%s' call site — fail-closed (dispatch renamed or moved out of "
            "this file?)" % (name, DISPATCH_CALL)
        )

    compliant = 0
    for idx in sites:
        line = code.count("\n", 0, idx) + 1
        open_pos = idx + len(DISPATCH_CALL) - 1
        arglist = call_args(code, open_pos)
        if not arglist:
            v.append(
                "%s:%d: '%s' could not be delimited — fail-closed" % (name, line, DISPATCH_CALL)
            )
            continue
        value = _named_arg_value(code, arglist, "model")
        if value is None:
            v.append(
                "%s:%d: '%s' carries no `model =` argument — the worker would be handed "
                "no model at all" % (name, line, DISPATCH_CALL)
            )
            continue
        if RAW_MODEL_ARG_RE.match(value):
            v.append(
                "%s:%d: `model = %s` hands the RAW entry to :modelservice — every "
                "models.dev capability field (output_modalities, max_output_tokens, "
                "reasoning_effort_values, …) is dropped at the process boundary; pass "
                "the enriched model the guards above read" % (name, line, value)
            )
            continue
        if value in aliases or ENRICH_CALL_RE.search(value):
            compliant += 1
            continue
        v.append(
            "%s:%d: `model = %s` is not an enrichment outlet — the argument must be the "
            "value bound from `effectiveModel(...)` (or that call itself)"
            % (name, line, value)
        )
    if sites and compliant != len(sites):
        v.append(
            "%s: %d of %d '%s' call sites hand over the enriched model — every site must"
            % (name, compliant, len(sites), DISPATCH_CALL)
        )
    return v


def _named_arg_value(code, arglist, arg):
    """Value text of the named argument `arg` from a call_args() slice list."""
    for a, b in arglist:
        m = re.match(r"\s*%s\s*=(?!=)" % re.escape(arg), code[a:b])
        if m:
            return code[a + m.end():b].strip()
    return None


# ── [dv27-A02] the request body must reach the provider WHOLE on both paths ──
# C1  — no orphan request key (every key the dispatcher writes is read).
# C1b — the value READ from that key is the value used: a read that is then
#       post-processed into something else (`req.optInt("max_tokens", 4096).let {
#       4096 }`) keeps the key visible while the user's value never reaches the
#       provider (witness 10-4).
# C2  — the level reaches both provider calls, bound to a live read of
#       `thinking_level`; a lossy member call on the read (`….take(0)` → "") makes
#       `thinkingLevelFromWire` fall back to OFF with the read still in the text
#       (witness 10-5), and both paths hand over the same argument surface.
LOSSY_MEMBERS = {
    "take", "takeLast", "drop", "dropLast", "substring", "subSequence",
    "replace", "removePrefix", "removeSuffix", "takeWhile", "dropWhile",
}
VALUE_COMBINATOR_RE = re.compile(r"\.\s*(let|run|takeIf|takeUnless)\s*\{")
READ_ANY_RE = re.compile(r"\b(?:%s)\s*\(" % "|".join(READ_CALLS))


def _allowed_receivers(code):
    """`it` plus every lambda parameter name declared in [code]."""
    names = {"it", "this"}
    for m in re.finditer(r"\{\s*([A-Za-z_]\w*)\s*(?::[^->{}]*)?->", code):
        names.add(m.group(1))
    return names


def _derives_from_read(source, allowed):
    """True when a value source still carries the read (or the lambda receiver)."""
    if READ_ANY_RE.search(source):
        return True
    return any(contains_ident(source, n) for n in allowed)


def _value_replaced(code, hint_re=None):
    """(True, why) when the value read is no longer the value used.

    Three shapes, all derived rather than spelled:
      1. a lossy member call straight onto the read — `read(…).take(0)` keeps the
         read in the text and hands on a different value (witness 10-5);
      2. a value combinator (`let` / `run` / `takeIf` / `takeUnless`) whose block
         never touches its receiver, or whose tail is a constant (witness 10-4);
      3. value-flow: no possible final value still derives from the read
         (`read(…).let { 4096 }`, `.let { entry.model }`).
    """
    allowed = _allowed_receivers(code)
    # 1. lossy member call on the read result
    for m in READ_ANY_RE.finditer(code):
        close = match_bracket(code, m.end() - 1)
        if close < 0:
            return True, "a read call could not be delimited (fail-closed)"
        lm = re.match(r"\s*\.\s*([A-Za-z_]\w*)\s*[({]", code[close + 1:])
        if lm and lm.group(1) in LOSSY_MEMBERS:
            return True, "the value read is mangled by `.%s(` before use" % lm.group(1)
    # 2. combinator block that replaces the value
    for m in VALUE_COMBINATOR_RE.finditer(code):
        name = m.group(1)
        brace = m.end() - 1
        close = match_bracket(code, brace)
        if close < 0:
            return True, "a `.%s {` block could not be delimited (fail-closed)" % name
        inner = code[brace + 1:close]
        if hint_re is not None and hint_re.search(inner):
            return True, "the `.%s {` block returns a hard-coded level" % name
        tail = _tail_stmt(inner)
        if tail and const_value(tail) is not None:
            return True, "the `.%s {` block returns a constant (`%s`)" % (name, tail[:32])
        if name != "run":
            declared = re.match(r"\s*([A-Za-z_]\w*)\s*(?::[^->]*)?->", inner)
            recv = declared.group(1) if declared else "it"
            if not contains_ident(inner, recv):
                return True, "the `.%s {` block ignores its receiver" % name
    # 3. value flow
    sources = value_sources(code)
    if sources and not any(_derives_from_read(s, allowed) for s in sources):
        return True, "no possible value still derives from the read (`%s`)" % (
            " ".join(sources[0].split())[:40])
    return False, ""


def _tail_stmt(body):
    spans = stmt_spans(body)
    if not spans:
        return body.strip()
    a, z = spans[-1]
    return body[a:z].strip()


def _traces_to_level(value_code, value_lit, body_code, body_lit, svc):
    """True when [value] is, or is bound in [body] to, a live read of `thinking_level`."""
    if LEVEL_READ_RE.search(value_lit):
        return not _value_replaced(value_code, LEVEL_CONST_RE)[0]
    ident = value_code.strip()
    if not IDENT_RE.match(ident):
        return False
    m = re.search(r"\bval\s+%s\b[^=\n]*=(?!=)" % re.escape(ident), body_lit)
    if not m:
        return False
    start = m.end()
    end = expr_end(body_code, start)
    rhs_code = body_code[start:end]
    rhs_lit = body_lit[start:end]
    if _value_replaced(rhs_code, LEVEL_CONST_RE)[0]:
        return False
    if LEVEL_READ_RE.search(rhs_lit):
        return True
    # one helper hop: `val x = helper(…)` where helper() reads the key
    for hm in re.finditer(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(", rhs_code):
        helper = function_body(svc.code, hm.group(1))
        if helper and LEVEL_READ_RE.search(svc.lit[helper[0]:helper[1]]):
            return True
    return False


def _dispatch_request_keys(views):
    """Request-level keys written by `buildRequestJson`, or None (fail-closed).

    Structure is read off the code projection, the keys off the literal one — both
    are length-preserving, so the nested-builder regions line up.
    """
    pair = body_pair(views, "buildRequestJson")
    if pair is None:
        return None
    body_code, body_lit, _span = pair
    regions = []
    for m in NESTED_BUILDER_RE.finditer(body_code):
        b = m.end() - 1
        e = match_bracket(body_code, b)
        if e > b:
            regions.append((b, e))
    outer = None
    for b, e in regions:
        if all(b <= b2 and e2 <= e for b2, e2 in regions):
            if outer is None or (b <= outer[0] and e >= outer[1]):
                outer = (b, e)
    nested = [r for r in regions if r != outer]
    keys = []
    for m in re.finditer(r'\bput\(\s*"([A-Za-z_][A-Za-z0-9_]*)"', body_lit):
        pos = m.start()
        if any(b < pos < e for b, e in nested):
            continue
        if m.group(1) not in keys:
            keys.append(m.group(1))
    return keys


def check_service_wiring(root):
    """[dv27-A02] C1 (no orphan key) + C1b (the read is the value used) + C2."""
    v = []
    svc, err = load(root, SERVICE_REL, os.path.basename(SERVICE_REL))
    if svc is None:
        return err
    svc_name = os.path.basename(SERVICE_REL)
    disp_path = os.path.join(root, EXEC_DISPATCH_REL)
    if not os.path.exists(disp_path):
        # The dialect fixtures model only the protocol files; a tree that ships
        # the real service but no dispatcher is a different (bigger) breakage.
        if "fun executeRun(" in svc.code:
            return [
                "%s: NOT FOUND at %s — the request body cannot be derived, so the "
                "worker's coverage of it cannot be judged (fail-closed)"
                % (os.path.basename(EXEC_DISPATCH_REL), disp_path)
            ]
        return v
    disp, derr = load(root, EXEC_DISPATCH_REL, os.path.basename(EXEC_DISPATCH_REL))
    if disp is None:
        return derr
    keys = _dispatch_request_keys(disp)
    if keys is None:
        return [
            "%s: `fun buildRequestJson(` could not be delimited — fail-closed"
            % os.path.basename(EXEC_DISPATCH_REL)
        ]
    if not keys:
        return [
            "%s: no request-level `put(\"…\"` could be derived — fail-closed "
            "(the writer was renamed or its body changed shape)"
            % os.path.basename(EXEC_DISPATCH_REL)
        ]

    # ── C1: every key the dispatcher writes is read by the worker ──
    read_sites = [(m.start(), m.group(1)) for m in KEY_READ_RE.finditer(svc.lit)]
    read_keys = {k for _p, k in read_sites}
    for k in keys:
        if k not in read_keys:
            v.append(
                "%s: the dispatcher writes '%s' but no worker site reads it — the "
                "field dies at the process boundary" % (svc_name, k)
            )

    # ── C1b: the value read from a key is the value that gets used ──
    allowed = _allowed_receivers(svc.code)
    for pos, k in read_sites:
        if k not in keys:
            continue
        stmt = stmt_of(svc.code, pos)
        if stmt is None:
            continue
        s_start, s_end, s_text = stmt
        if not re.match(r"\s*(?:val|var)\b", s_text):
            continue
        # the binding's right-hand side (the read is the value being bound)
        m = re.match(r"\s*(?:val|var)\s+[A-Za-z_]\w*\s*(?::[^=\n]+?)?=(?!=)", s_text)
        if not m:
            continue
        rhs_start = s_start + m.end()
        rhs_end = expr_end(svc.code, rhs_start)
        rhs_end = min(max(rhs_end, rhs_start), s_end)
        replaced, why = _value_replaced(svc.code[rhs_start:rhs_end])
        if replaced:
            line = svc.code.count("\n", 0, pos) + 1
            v.append(
                "%s:%d: the value read from '%s' is replaced before use — %s; the key "
                "looks consumed while the provider receives something else (witness 10-4)"
                % (svc_name, line, k, why)
            )

    # ── C2: the level reaches BOTH provider calls, bound to a live read ──
    surfaces = {}
    for entry, callee in EXEC_PATHS:
        pair = body_pair(svc, entry)
        if pair is None:
            v.append(
                "%s: `fun %s(` could not be delimited — fail-closed (entry renamed "
                "or moved?)" % (svc_name, entry)
            )
            continue
        body_code, body_lit, _span = pair
        token = "provider.%s(" % callee
        idx = body_code.find(token)
        if idx < 0:
            v.append(
                "%s: `%s` makes no '%s' call — fail-closed"
                % (svc_name, entry, token)
            )
            continue
        open_pos = idx + len(token) - 1
        arglist = call_args(body_code, open_pos)
        if not arglist:
            v.append(
                "%s: the '%s' call in `%s` could not be delimited — fail-closed"
                % (svc_name, token, entry)
            )
            continue
        names = set()
        level = None
        for a, b in arglist:
            m = re.match(r"\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)", body_code[a:b])
            if not m:
                continue
            arg = m.group(1)
            names.add(arg)
            if arg == LEVEL_ARG:
                level = (body_code[a + m.end():b], body_lit[a + m.end():b])
        surfaces[entry] = (names, token)
        if level is None:
            v.append(
                "%s: `%s` calls %s without `%s =` — the level the dispatcher "
                "serialized is dropped and the provider falls back to its OFF "
                "default (reasoning silently disabled)"
                % (svc_name, entry, token.rstrip("("), LEVEL_ARG)
            )
            continue
        if not _traces_to_level(level[0], level[1], body_code, body_lit, svc):
            v.append(
                "%s: `%s` passes `%s = %s` — that value is not derived from a live "
                "read of '%s' in this function body (constant, re-typed literal, or "
                "the read is post-processed away)"
                % (svc_name, entry, LEVEL_ARG, " ".join(level[0].split()), LEVEL_KEY)
            )

    # ── C2b: the two paths hand the provider the same argument surface ──
    if len(surfaces) == 2:
        (a_name, (a_args, a_tok)), (b_name, (b_args, b_tok)) = list(surfaces.items())
        only_a = sorted(a_args - b_args)
        only_b = sorted(b_args - a_args)
        if only_a:
            v.append(
                "%s: `%s` passes %s to %s but `%s` does not — one path drops an "
                "argument the other hands over"
                % (svc_name, a_name, ", ".join("`%s =`" % x for x in only_a),
                   a_tok.rstrip("("), b_name)
            )
        if only_b:
            v.append(
                "%s: `%s` passes %s to %s but `%s` does not — one path drops an "
                "argument the other hands over"
                % (svc_name, b_name, ", ".join("`%s =`" % x for x in only_b),
                   b_tok.rstrip("("), a_name)
            )
    return v


def lines_of(text, needle):
    """1-based line numbers whose text contains `needle` (comment-free text)."""
    return [i + 1 for i, line in enumerate(text.split("\n")) if needle in line]


def _def_spans(code, fn):
    """[(decl_pos, body_start, body_end)] for every `fun <fn>(` declaration."""
    out = []
    pat = re.compile(r"(?:private |internal |public |protected )?fun\s+%s\s*\(" % re.escape(fn))
    for m in pat.finditer(code):
        sp = function_body(code[m.start():], fn)
        if sp is None:
            continue
        out.append((m.start(), m.start() + sp[0], m.start() + sp[1]))
    return out


def is_delegate_def(code, bstart, bend, fn):
    """A caller-file definition is legal only when its BODY is one delegating
    expression into ModelUseProtocol.

    [wide-1024 Rule 1] The old anchor was a 3-line window containing
    `ModelUseProtocol.` and no `for (`/`while (` — so appending a fallback
    (`…parseCustomEndpointPath(inputJson) ?: JSONObject(inputJson)
    .optString("endpoint")`) stayed inside the window and read as a delegate
    while it re-implemented the dialect decision at the call site.

    [B6-t07 10-6] The body, the call and its tail are read off the shared
    projections: a `"/*"` literal inside the expression can no longer open a
    "block comment" that swallowed `.optString("endpoint").removePrefix("*/")`
    from the hand-rolled stripper — the parser death the witness used to smuggle
    the fallback past `code_only`.
    """
    body = code[bstart:bend]
    if not body.strip():
        return False
    token = "%s%s(" % (PREFIX, fn)
    if body.count(token) != 1:
        return False
    at = body.find(token)
    close = match_bracket(body, at + len(token) - 1)
    if close < 0:
        return False
    if body[:at].strip() not in ("", "return"):
        return False
    return _PASSTHROUGH_TAIL_RE.match(body[close + 1:].strip()) is not None


def check_delegate_used(views, name):
    """[B6-t07 10-1] A private delegate nobody CALLS is not the single source.

    The witness kept `ModelUseProtocol.parseCustomEndpointPath` inside the private
    helper and replaced the only call site with a direct
    `JSONObject(inputJson).optString("endpoint")`. The delegation check still saw
    one clean delegate while the decision had moved into the call site — so what
    is checked now is direction: every private delegate must have at least one
    LIVE caller in its own file (a dead helper proves the call site grew its own
    read).
    """
    out = []
    code = views.code
    for fn in FUNCS:
        for decl_pos, bstart, bend in _def_spans(code, fn):
            live = 0
            pat = re.compile(r"(?<![\w.`])%s\s*\(" % re.escape(fn))
            for m in pat.finditer(code):
                if decl_pos <= m.start() < bend:
                    # the declaration itself (`fun name(`) is not a call
                    continue
                if not path_feasible(code, m.start()):
                    continue
                live += 1
            if live == 0:
                line = code.count("\n", 0, decl_pos) + 1
                out.append(
                    "%s:%d: the private delegate `fun %s(` has no live caller — a helper "
                    "nobody calls is not the single source; the call site reads the raw "
                    "key itself again (witness 10-1)"
                    % (name, line, fn)
                )
    return out


def check_endpoint_provenance(views, name):
    """[B6-t07 10-1] `absoluteEndpointOverride` must be written from the parser.

    The overridden field is what actually reaches the HTTP call: a value bound
    from `JSONObject(inputJson).optString("endpoint")` re-introduces the symbolic
    `chat`/`auto` mode the migration deleted. Only values bound out of
    `parseCustomEndpointPath(…)` (or the call itself) are accepted.
    """
    out = []
    code, lit = views.code, views.lit
    bound = set()
    for m in re.finditer(r"(?<![\w.`])parseCustomEndpointPath\s*\(", lit):
        close = match_bracket(lit, m.end() - 1)
        if close < 0:
            continue
        rest = code[close + 1:]
        bm = re.match(r"\s*\??\.\s*(?:let|also|run)\s*\{\s*([A-Za-z_]\w*)\s*->", rest)
        if bm:
            bound.add(bm.group(1))
    for m in ENDPOINT_TARGET_RE.finditer(code):
        line = code.count("\n", 0, m.start()) + 1
        rhs = code[m.end():expr_end(code, m.end())].strip()
        inner = rhs.split(".")[-1].strip()
        if inner in bound or "parseCustomEndpointPath(" in rhs:
            continue
        out.append(
            "%s:%d: `absoluteEndpointOverride = %s` is not derived from "
            "parseCustomEndpointPath( — the raw endpoint text (with its symbolic "
            "`chat`/`auto` modes) is written into the request URL again "
            "(witness 10-1)" % (name, line, " ".join(rhs.split())[:48] or "<empty>")
        )
    return out


def check(root):
    v = []
    proto_views, err = load(root, PROTO_REL, "ModelUseProtocol.kt")
    if proto_views is None:
        return err
    proto_code = proto_views.code
    proto_lit = proto_views.lit

    for token in PROTO_TOKENS:
        if token not in proto_lit:
            v.append("ModelUseProtocol.kt: the single dialect is missing '%s'" % token)
    for f in FUNCS:
        for n in lines_of(proto_code, "fun %s(" % f):
            # definitions inside ModelUseProtocol are the single source; a
            # delegate to itself would be a collapse bug — flag it
            window = "\n".join(proto_code.split("\n")[n - 1: n + 2])
            if "ModelUseProtocol.%s(" % f in window:
                v.append("ModelUseProtocol.kt:%d: the dialect delegates to itself" % n)

    for rel in CALLER_RELS:
        views, err = load(root, rel, os.path.basename(rel))
        if views is None:
            v += err
            continue
        name = os.path.basename(rel)
        for f in FUNCS:
            if "ModelUseProtocol.%s(" % f not in views.lit:
                v.append(
                    "%s: no prefixed call 'ModelUseProtocol.%s(' — the call "
                    "site must go through the single dialect" % (name, f)
                )
        for f in FUNCS:
            for decl_pos, bstart, bend in _def_spans(views.code, f):
                if not is_delegate_def(views.code, bstart, bend, f):
                    line = views.code.count("\n", 0, decl_pos) + 1
                    v.append(
                        "%s:%d: 'fun %s(' is NOT a one-line delegate into "
                        "ModelUseProtocol — a second implementation body is "
                        "back" % (name, line, f)
                    )
        v += check_delegate_used(views, name)
        v += check_endpoint_provenance(views, name)
        for n in lines_of(views.lit, SILENT_BRANCH):
            v.append(
                "%s:%d: the silent branch '%s' is back — a "
                "present-but-not-an-object value would be dropped with zero "
                "warning" % (name, n, SILENT_BRANCH)
            )
        for n in lines_of(views.lit, SILENT_IMAGE_BRANCH):
            v.append(
                "%s:%d: the silent branch '%s' is back — a "
                "present-but-not-an-object extra_body would be dropped with "
                "zero warning" % (name, n, SILENT_IMAGE_BRANCH)
            )
        for n in lines_of(views.code, "imageReservedKeys"):
            line = views.code.split("\n")[n - 1]
            if IMAGE_KEY_DEF_RE.search(line):
                v.append(
                    "%s:%d: the reserved-key set is DEFINED here again — it "
                    "lives in ModelUseProtocol.kt, next to the single "
                    "%s" % (name, n, IMAGE_PARSER_CALL.rstrip("("))
                )
    # [dv14-A4] ISSUE-08: the image path has ONE parser, and its caller uses it.
    views, err = load(root, IMAGE_CALLER_REL, os.path.basename(IMAGE_CALLER_REL))
    if views is None:
        v += err
    elif IMAGE_PARSER_CALL not in views.lit:
        v.append(
            "%s: no '%s' — the image passthrough dialect must be parsed by "
            "the single implementation in ModelUseProtocol"
            % (os.path.basename(IMAGE_CALLER_REL), IMAGE_PARSER_CALL)
        )
    return v


GOOD_CALLER = """\
package p
class C {
    private fun cmdList(args: OffloadArgs): NativeOffloadResult {
        val providerFilter = args.get("provider")
        if (providerFilter != null) {
            entries = entries.filter { providerMatches(providerFilter, it.label, it.id, it.typeName) }
        }
        return NativeOffloadResult(0, "")
    }

    private fun cmdSearch(args: OffloadArgs): NativeOffloadResult {
        val providerFilter = args.get("provider")
        if (providerFilter != null) {
            matches = matches.filter { providerMatches(providerFilter, it.label, it.id, it.typeName) }
        }
        return NativeOffloadResult(0, "")
    }

    private fun resolveEntry(idOrName: String, providerFilter: String? = null): ModelEntry? {
        val pool = if (providerFilter.isNullOrEmpty()) all else {
            all.filter { providerMatches(providerFilter, it.label, it.id, it.typeName) }
        }
        return null
    }

    private fun parseExtraHeaders(inputJson: String, warnings: MutableList<String>): Map<String, String> =
        ModelUseProtocol.parseExtraHeaders(inputJson, warnings)

    private fun parseCustomEndpointPath(inputJson: String): String? =
        ModelUseProtocol.parseCustomEndpointPath(inputJson)

    private fun applyImagePassthrough(inputJson: String): Map<String, Any?> =
        ModelUseProtocol.parseImagePassthrough(inputJson).body

    private fun applyDialect(inputJson: String, warnings: MutableList<String>, provider: MutableProvider) {
        parseExtraHeaders(inputJson, warnings).let { headers -> provider.chatExtraHeaders = headers }
        parseCustomEndpointPath(inputJson)?.let { path ->
            provider.absoluteEndpointOverride = path
        }
    }

    // [dv23-A03] the enriched model is what crosses into :modelservice
    private fun cmdRun(args: OffloadArgs): NativeOffloadResult {
        val effectiveModel = com.rikkaminis.app.provider.ModelsDevApi.effectiveModel(entry)
        val ptRequestJson = ModelExecutionDispatcher.buildRequestJson(
            instance = instance,
            model = effectiveModel,
            messages = messages,
        )
        val remoteRequestJson = ModelExecutionDispatcher.buildRequestJson(
            instance = instance,
            model = effectiveModel,
            messages = messages,
        )
        return NativeOffloadResult(0, "")
    }
}

internal fun providerMatches(query: String, label: String, id: String, typeName: String): Boolean {
    val q = query.trim().lowercase()
    return label.trim().lowercase() == q
}
"""

# Fixture 7's caller: everything else right, the image delegation gone.
GOOD_CALLER_NO_IMAGE = GOOD_CALLER.replace(
    "    private fun applyImagePassthrough(inputJson: String): Map<String, Any?> =\n"
    "        ModelUseProtocol.parseImagePassthrough(inputJson).body\n",
    "",
)

# Fixture 6's caller: a second definition of the reserved-key set.
GOOD_CALLER_REDEFINES_KEYS = GOOD_CALLER.replace(
    "    private fun parseCustomEndpointPath(",
    '    private val imageReservedKeys: Set<String> = setOf("messages")\n'
    "\n"
    "    private fun parseCustomEndpointPath(",
)

BAD_CALLER_COPY = """\
package p
class C {
    private fun parseExtraHeaders(inputJson: String, warnings: MutableList<String>): Map<String, String> {
        val obj = JSONObject(inputJson)
        val eh = obj.optJSONObject("extra_headers") ?: return emptyMap()
        return mapOf("k" to (eh.optString("k")))
    }

    private fun parseCustomEndpointPath(inputJson: String): String? =
        ModelUseProtocol.parseCustomEndpointPath(inputJson)

    private fun applyImagePassthrough(inputJson: String): Map<String, Any?> =
        ModelUseProtocol.parseImagePassthrough(inputJson).body
}
"""

BAD_CALLER_NO_PREFIX = """\
package p
class C {
    private fun parseExtraHeaders(inputJson: String, warnings: MutableList<String>): Map<String, String> =
        parseExtraHeaders(inputJson, warnings)

    private fun parseCustomEndpointPath(inputJson: String): String? =
        ModelUseProtocol.parseCustomEndpointPath(inputJson)

    private fun applyImagePassthrough(inputJson: String): Map<String, Any?> =
        ModelUseProtocol.parseImagePassthrough(inputJson).body
}
"""

GOOD_PROTO = """\
package p
object ModelUseProtocol {
    internal val imageReservedKeys: Set<String> = setOf("messages", "model")

    fun parseExtraHeaders(inputJson: String, warnings: MutableList<String>): Map<String, String> {
        val raw = obj.opt("extra_headers") ?: return emptyMap()
        warnings.add("extra_headers is not a JSON object (actual type: x)")
        return out
    }

    fun parseCustomEndpointPath(inputJson: String): String? {
        val raw = obj.safeOptString("image_endpoint", "")
        return if (raw.startsWith("/")) raw else null
    }

    fun parseImagePassthrough(inputJson: String): ImagePassthroughSpec {
        val body = LinkedHashMap<String, Any?>()
        return ImagePassthroughSpec(body = body)
    }
}
"""


# [dv16-A04] the pre-fix shapes: one subcommand drops the shared matcher, and
# cmdSearch ignores the flag it documents.
GOOD_CALLER_LIST_SUBSTRING = GOOD_CALLER.replace(
    "            entries = entries.filter { providerMatches(providerFilter, it.label, it.id, it.typeName) }",
    "            entries = entries.filter { it.provider.lowercase().contains(providerFilter.lowercase()) }",
)

GOOD_CALLER_SEARCH_NO_FLAG = GOOD_CALLER.replace(
    '        val providerFilter = args.get("provider")\n'
    "        if (providerFilter != null) {\n"
    "            matches = matches.filter { providerMatches(providerFilter, it.label, it.id, it.typeName) }\n"
    "        }\n",
    "",
)

# [dv23-A03] the pre-fix shape: BOTH dispatch points hand the raw entry over.
GOOD_CALLER_RAW_HANDOFF = GOOD_CALLER.replace(
    "model = effectiveModel,",
    "model = entry.model,",
)

# Only the standard path regressed — the passthrough branch stays enriched.
# The gate counts call sites, so a single raw site must already be red.
GOOD_CALLER_ONE_RAW = GOOD_CALLER.replace(
    "            model = effectiveModel,",
    "            model = entry.model,",
)

# A `model =` argument that is neither the alias nor the shared resolver: the
# hand-off bypasses enrichment without looking like `entry.model`.
GOOD_CALLER_FOREIGN_ARG = GOOD_CALLER.replace(
    "            model = effectiveModel,",
    "            model = resolvedElsewhere,",
)

# The enriched model is bound from something other than the shared resolver, so
# there is no alias to hand over (a local re-derivation would be the same bug).
GOOD_CALLER_NO_ALIAS = GOOD_CALLER.replace(
    "        val effectiveModel = com.rikkaminis.app.provider.ModelsDevApi.effectiveModel(entry)",
    "        val effectiveModel = entry.baseModel",
)

# No dispatch call site at all — fail-closed, not silently clean.
GOOD_CALLER_NO_DISPATCH = GOOD_CALLER.replace(
    "    private fun cmdRun(args: OffloadArgs): NativeOffloadResult {",
    "    private fun notRun(args: OffloadArgs): NativeOffloadResult {",
).replace(
    "ModelExecutionDispatcher.buildRequestJson(",
    "ModelExecutionDispatcher.buildBodyJson(",
)

# [wide-1024] Rule 7 witness: the alias is bound to a DERIVED value
# (`.let { entry.model }` hands the raw entry back, type-compatible).
GOOD_CALLER_DISCARDED_ALIAS = GOOD_CALLER.replace(
    "        val effectiveModel = com.rikkaminis.app.provider.ModelsDevApi.effectiveModel(entry)",
    "        val effectiveModel = "
    "com.rikkaminis.app.provider.ModelsDevApi.effectiveModel(entry).let { entry.model }",
)

# [wide-1024] Rule 1 witness: the delegate keeps its `ModelUseProtocol.` call
# and re-decides with an elvis fallback on the same line.
GOOD_CALLER_FALLBACK_DELEGATE = GOOD_CALLER.replace(
    "    private fun parseCustomEndpointPath(inputJson: String): String? =\n"
    "        ModelUseProtocol.parseCustomEndpointPath(inputJson)\n",
    "    private fun parseCustomEndpointPath(inputJson: String): String? =\n"
    "        ModelUseProtocol.parseCustomEndpointPath(inputJson) ?: "
    "JSONObject(inputJson).optString(\"endpoint\").ifEmpty { null }\n",
)

GOOD_CALLER_TWO_MATCHERS = GOOD_CALLER.replace(
    "class C {",
    "class C {\n"
    "    private fun providerMatches(q: String, label: String): Boolean = label == q\n",
)


# ── batch-6 witnesses (external adversarial audit, 2026-10-26) ──
# 10-1: the single-endpoint parser survives inside its private delegate while the
# only call site reads the raw key itself — and writes the raw value into the
# field that actually reaches the HTTP call.
GOOD_CALLER_DEAD_DELEGATE = GOOD_CALLER.replace(
    """        parseCustomEndpointPath(inputJson)?.let { path ->
            provider.absoluteEndpointOverride = path
        }""",
    """        JSONObject(inputJson).optString("endpoint").takeIf { it.isNotEmpty() }?.let { path ->
            provider.absoluteEndpointOverride = path
        }""",
)

# 10-2: the matcher is still called and its result is short-circuited into a
# constant, so `--provider` filters nothing.
GOOD_CALLER_ABSORBED = GOOD_CALLER.replace(
    "            entries = entries.filter { providerMatches(providerFilter, it.label, it.id, it.typeName) }",
    "            entries = entries.filter { it != null && "
    "(providerMatches(providerFilter, it.label, it.id, it.typeName) || true) }",
)

# 10-2b: the only `providerMatches(` text is inside a string literal — a code
# anchor must not be satisfiable from a literal (the code/lit split).
GOOD_CALLER_MATCHER_IN_STRING = GOOD_CALLER.replace(
    "            entries = entries.filter { providerMatches(providerFilter, it.label, it.id, it.typeName) }",
    '            entries = entries.filter { log("providerMatches(providerFilter, it.label, it.id, it.typeName)") }',
)

# 10-3: the derivation moved onto the continuation line the old reader cut away.
GOOD_CALLER_MULTILINE_ALIAS = GOOD_CALLER.replace(
    "        val effectiveModel = com.rikkaminis.app.provider.ModelsDevApi.effectiveModel(entry)",
    "        val effectiveModel = com.rikkaminis.app.provider.ModelsDevApi.effectiveModel(entry)\n"
    "            .let { entry.model }",
)

# 10-6: a `"/*"` literal in the expression open-coded as a block comment used to
# hide the elvis fallback from the hand-rolled stripper (parser death).
GOOD_CALLER_STRING_COMMENT_DELEGATE = GOOD_CALLER.replace(
    '    private fun parseCustomEndpointPath(inputJson: String): String? =\n'
    '        ModelUseProtocol.parseCustomEndpointPath(inputJson)\n',
    '    private fun parseCustomEndpointPath(inputJson: String): String? =\n'
    '        ModelUseProtocol.parseCustomEndpointPath(inputJson + "/*") ?: '
    'JSONObject(inputJson).optString("endpoint").removePrefix("*/")\n',
)


# ── [dv27-A02] fixtures: the request body must reach the provider whole ──
# The delegate block keeps these fixtures legal for the dialect checks above, so
# a wiring arm isolates check_service_wiring() instead of tripping four gates.
WIRING_DELEGATES = """\
    private fun parseExtraHeaders(inputJson: String, warnings: MutableList<String>): Map<String, String> =
        ModelUseProtocol.parseExtraHeaders(inputJson, warnings)

    private fun parseCustomEndpointPath(inputJson: String): String? =
        ModelUseProtocol.parseCustomEndpointPath(inputJson)

    private fun applyImagePassthrough(inputJson: String): Map<String, Any?> =
        ModelUseProtocol.parseImagePassthrough(inputJson).body
"""

def wiring_service(parse_line=("        val thinkingLevel = "
                               "thinkingLevelFromWire(req.optString(\"thinking_level\", \"\"))\n"),
                   run_level="thinkingLevel",
                   run_drop_max_tokens=False,
                   run_mt_line='        val maxTokens = req.optInt("max_tokens", 4096)\n'):
    """A worker service whose two execution paths mirror the shipped shape."""
    run_mt = "" if run_drop_max_tokens else "            maxTokens = maxTokens,\n"
    return (
        "package p\nclass ModelExecutionService {\n"
        + WIRING_DELEGATES
        + "\n    private fun executeRun(req: JSONObject): String {\n"
        + "        val messages = req.optJSONArray(\"messages\")\n"
        + run_mt_line
        + parse_line
        + "        provider.sendMessage(\n"
        + "            messages = messages,\n"
        + run_mt
        + "            thinkingLevel = %s,\n" % run_level
        + "        )\n        return \"\"\n    }\n"
        + "\n    private fun executeStreamingRun(req: JSONObject): String {\n"
        + "        val messages = req.optJSONArray(\"messages\")\n"
        + "        val maxTokens = req.optInt(\"max_tokens\", 4096)\n"
        + "        val thinkingLevel = "
        + "thinkingLevelFromWire(req.optString(\"thinking_level\", \"\"))\n"
        + "        provider.streamMessage(\n"
        + "            messages = messages,\n"
        + "            maxTokens = maxTokens,\n"
        + "            thinkingLevel = thinkingLevel,\n"
        + "        )\n        return \"\"\n    }\n}\n"
    )

def wiring_dispatch(extra_key=None):
    """The dispatcher writer: request-level puts at the top level of one
    builder, plus a nested builder whose keys must NOT be derived."""
    extra = '            put("%s", 1)\n' % extra_key if extra_key else ""
    return (
        "package p\nobject ModelExecutionDispatcher {\n"
        "    fun buildRequestJson(thinkingLevel: ThinkingLevel): String {\n"
        "        return JSONObject().apply {\n"
        "            put(\"messages\", JSONArray().apply { put(JSONObject().apply { "
        "put(\"role\", \"user\") }) })\n"
        "            put(\"max_tokens\", 4096)\n"
        + extra
        + "            if (thinkingLevel != ThinkingLevel.OFF) "
        "put(\"thinking_level\", thinkingLevel.name)\n"
        "        }\n    }\n}\n"
    )

def write(root, rel, text):
    p = os.path.join(root, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as fh:
        fh.write(text)


def selftest():
    import tempfile

    ok = True

    def run(root):
        violations = check(root) + check_provider(root) + check_enriched_dispatch(root) + check_service_wiring(root)
        return violations

    # fixture 1: good tree → exit 0
    with tempfile.TemporaryDirectory() as root:
        write(root, PROTO_REL, GOOD_PROTO)
        for rel in CALLER_RELS:
            write(root, rel, GOOD_CALLER)
        bad = run(root)
        if bad:
            ok = False
            print("SELFTEST-FAIL good fixture must be clean:", *bad, sep="\n  ")

    # fixture 2: a second implementation body pasted back into a caller → must FAIL
    with tempfile.TemporaryDirectory() as root:
        write(root, PROTO_REL, GOOD_PROTO)
        write(root, CALLER_RELS[0], BAD_CALLER_COPY)
        write(root, CALLER_RELS[1], GOOD_CALLER)
        bad = run(root)
        if not any("NOT a one-line delegate" in b or SILENT_BRANCH in b for b in bad):
            ok = False
            print("SELFTEST-FAIL re-collapsed copy must be caught:", bad)

    # fixture 3: delegate without the ModelUseProtocol. prefix → must FAIL
    with tempfile.TemporaryDirectory() as root:
        write(root, PROTO_REL, GOOD_PROTO)
        write(root, CALLER_RELS[0], GOOD_CALLER)
        write(root, CALLER_RELS[1], BAD_CALLER_NO_PREFIX)
        bad = run(root)
        if not any("no prefixed call" in b for b in bad):
            ok = False
            print("SELFTEST-FAIL missing-prefix delegate must be caught:", bad)

    # fixture 4: silent optJSONObject branch back in a caller → must FAIL
    with tempfile.TemporaryDirectory() as root:
        write(root, PROTO_REL, GOOD_PROTO)
        write(root, CALLER_RELS[0], GOOD_CALLER)
        write(root, CALLER_RELS[1], BAD_CALLER_COPY)
        bad = run(root)
        if not any(SILENT_BRANCH in b for b in bad):
            ok = False
            print("SELFTEST-FAIL silent branch must be caught:", bad)

    # fixture 5: comments cannot satisfy the checks
    with tempfile.TemporaryDirectory() as root:
        commented = """\
package p
class C {
    // ModelUseProtocol.parseExtraHeaders( — prose only
    /* private fun parseCustomEndpointPath(inputJson: String): String? = */
    private fun other(x: Int): Int = x
}
"""
        write(root, PROTO_REL, GOOD_PROTO)
        write(root, CALLER_RELS[0], GOOD_CALLER)
        write(root, CALLER_RELS[1], commented)
        bad = run(root)
        if not any("no prefixed call" in b for b in bad):
            ok = False
            print("SELFTEST-FAIL comment-only references must not count:", bad)

    # [dv14-A4] ISSUE-08 fixture 6: a caller defines the reserved-key set
    # again (the shape both deleted copies had) → must FAIL
    with tempfile.TemporaryDirectory() as root:
        write(root, PROTO_REL, GOOD_PROTO)
        write(root, CALLER_RELS[0], GOOD_CALLER_REDEFINES_KEYS)
        write(root, CALLER_RELS[1], GOOD_CALLER)
        bad = run(root)
        if not any("reserved-key set is DEFINED here again" in b for b in bad):
            ok = False
            print("SELFTEST-FAIL a second reserved-key set must be caught:", bad)

    # [dv14-A4] ISSUE-08 fixture 7: the image-path caller stops delegating to
    # the single parser → must FAIL (an unused single source is the defect)
    with tempfile.TemporaryDirectory() as root:
        write(root, PROTO_REL, GOOD_PROTO)
        write(root, CALLER_RELS[0], GOOD_CALLER_NO_IMAGE)
        write(root, CALLER_RELS[1], GOOD_CALLER)
        bad = run(root)
        if not any("must be parsed by the single implementation" in b for b in bad):
            ok = False
            print("SELFTEST-FAIL a caller without the image delegation must be caught:", bad)

    # [dv16-A04] fixture 8: a subcommand rolls its own --provider filter
    # (list's pre-fix substring match) → must FAIL on the shared-matcher arm
    with tempfile.TemporaryDirectory() as root:
        write(root, PROTO_REL, GOOD_PROTO)
        write(root, CALLER_RELS[0], GOOD_CALLER)
        write(root, CALLER_RELS[1], GOOD_CALLER_LIST_SUBSTRING)
        bad = run(root)
        if not any("cmdList does not call the single providerMatches" in b for b in bad):
            ok = False
            print("SELFTEST-FAIL a hand-rolled list filter must be caught:", bad)

    # [dv16-A04] fixture 9: cmdSearch drops the documented --provider flag
    with tempfile.TemporaryDirectory() as root:
        write(root, PROTO_REL, GOOD_PROTO)
        write(root, CALLER_RELS[0], GOOD_CALLER)
        write(root, CALLER_RELS[1], GOOD_CALLER_SEARCH_NO_FLAG)
        bad = run(root)
        if not any('cmdSearch does not read args.get("provider")' in b for b in bad):
            ok = False
            print("SELFTEST-FAIL an ignored search flag must be caught:", bad)

    # [dv16-A04] fixture 10: a second matcher definition (the drift source)
    with tempfile.TemporaryDirectory() as root:
        write(root, PROTO_REL, GOOD_PROTO)
        write(root, CALLER_RELS[0], GOOD_CALLER)
        write(root, CALLER_RELS[1], GOOD_CALLER_TWO_MATCHERS)
        bad = run(root)
        if not any("expected exactly ONE" in b for b in bad):
            ok = False
            print("SELFTEST-FAIL a duplicated matcher must be caught:", bad)

    # [dv16-A04] fixture 11: fail-closed — the handler file is gone
    with tempfile.TemporaryDirectory() as root:
        write(root, PROTO_REL, GOOD_PROTO)
        write(root, CALLER_RELS[0], GOOD_CALLER)
        bad = run(root) + check_provider(root)
        if not any("cannot verify the --provider matcher" in b for b in bad):
            ok = False
            print("SELFTEST-FAIL a missing handler file must not pass silently:", bad)

    # [dv23-A03] fixtures 12-15: the dispatched model must be the enriched one
    def dispatch_arm(label, caller, needle):
        nonlocal ok
        with tempfile.TemporaryDirectory() as root:
            write(root, PROTO_REL, GOOD_PROTO)
            write(root, CALLER_RELS[0], GOOD_CALLER)
            write(root, CALLER_RELS[1], caller)
            bad = run(root)
            if not any(needle in b for b in bad):
                ok = False
                print("SELFTEST-FAIL %s must be caught:" % label, bad)

    dispatch_arm(
        "a raw `model = entry.model` hand-off (the pre-fix shape)",
        GOOD_CALLER_RAW_HANDOFF,
        "hands the RAW entry to :modelservice",
    )
    dispatch_arm(
        "one enriched site beside one raw site (second dispatch point re-added)",
        GOOD_CALLER_ONE_RAW,
        "hands the RAW entry to :modelservice",
    )
    dispatch_arm(
        "a `model =` argument that is neither the alias nor the shared resolver",
        GOOD_CALLER_FOREIGN_ARG,
        "is not an enrichment outlet",
    )
    dispatch_arm(
        "the enriched model never resolved (no alias binding)",
        GOOD_CALLER_NO_ALIAS,
        "no `val … = …effectiveModel(` binding",
    )
    dispatch_arm(
        "the dispatch call site removed (fail-closed)",
        GOOD_CALLER_NO_DISPATCH,
        "no 'buildRequestJson(' call site",
    )
    # [wide-1024] Rule 7 witness: the alias line keeps `effectiveModel(` but
    # binds a derived value, so the hand-off looks enriched and is not.
    dispatch_arm(
        "an alias bound to a derived value (.let { entry.model })",
        GOOD_CALLER_DISCARDED_ALIAS,
        "binds a DERIVED value",
    )

    # [wide-1024] Rule 1 witness: the delegate re-decides with an elvis.
    with tempfile.TemporaryDirectory() as root:
        write(root, PROTO_REL, GOOD_PROTO)
        write(root, CALLER_RELS[0], GOOD_CALLER_FALLBACK_DELEGATE)
        write(root, CALLER_RELS[1], GOOD_CALLER)
        bad = run(root)
        if not any("NOT a one-line delegate" in b for b in bad):
            ok = False
            print("SELFTEST-FAIL a delegate with an elvis fallback must be caught:", bad)

    # [dv27-A02] fixtures 16-20: thinking_level must reach BOTH provider calls,
    # and every key the dispatcher writes must have a reader.
    def wiring_arm(label, service, dispatch, needle, only=True):
        nonlocal ok
        with tempfile.TemporaryDirectory() as root:
            write(root, PROTO_REL, GOOD_PROTO)
            write(root, CALLER_RELS[0], service)
            write(root, CALLER_RELS[1], GOOD_CALLER)
            write(root, EXEC_DISPATCH_REL, dispatch)
            bad = check_service_wiring(root)
            if not any(needle in b for b in bad):
                ok = False
                print("SELFTEST-FAIL %s must be caught:" % label, bad)
            elif only and len(bad) != 1:
                ok = False
                print("SELFTEST-FAIL %s must be the ONLY violation:" % label, bad)

    with tempfile.TemporaryDirectory() as root:
        write(root, PROTO_REL, GOOD_PROTO)
        write(root, CALLER_RELS[0], wiring_service())
        write(root, CALLER_RELS[1], GOOD_CALLER)
        write(root, EXEC_DISPATCH_REL, wiring_dispatch())
        bad = check_service_wiring(root)
        if bad:
            ok = False
            print("SELFTEST-FAIL the wiring-compliant fixture must be clean:", *bad, sep="\n  ")

    wiring_arm(
        "the non-streaming path parses nothing (pre-fix shape: value undefined)",
        wiring_service(parse_line=""),
        wiring_dispatch(),
        "is not derived from a live read of 'thinking_level'",
    )
    wiring_arm(
        "the non-streaming path passes a constant level",
        wiring_service(run_level="ThinkingLevel.OFF"),
        wiring_dispatch(),
        "is not derived from a live read of 'thinking_level'",
    )
    # [wide-1024] C2 witness: the wire read stays inside the binding and the
    # value handed to the provider is a constant.
    wiring_arm(
        "the parsed level is replaced after the read (.let { ThinkingLevel.OFF })",
        wiring_service(
            parse_line='        val thinkingLevel = '
                       'thinkingLevelFromWire(req.optString("thinking_level", ""))'
                       '.let { ThinkingLevel.OFF }\n',
        ),
        wiring_dispatch(),
        "is not derived from a live read of 'thinking_level'",
        only=False,
    )

    wiring_arm(
        "the dispatcher writes a key no worker site reads",
        wiring_service(),
        wiring_dispatch(extra_key="nobody_reads_me"),
        "but no worker site reads it",
    )
    wiring_arm(
        "one path drops an argument its sibling hands over",
        wiring_service(run_drop_max_tokens=True),
        wiring_dispatch(),
        "one path drops an argument the other hands over",
    )


    # ── batch-6 witness arms (external adversarial audit, 2026-10-26) ──
    def dialect_arm(label, caller, needle):
        nonlocal ok
        with tempfile.TemporaryDirectory() as root:
            write(root, PROTO_REL, GOOD_PROTO)
            write(root, CALLER_RELS[0], GOOD_CALLER)
            write(root, CALLER_RELS[1], caller)
            bad = run(root)
            if not any(needle in b for b in bad):
                ok = False
                print("SELFTEST-FAIL %s must be caught:" % label, bad)

    dialect_arm(
        "10-1 the private delegate nobody calls (the call site reads the raw key)",
        GOOD_CALLER_DEAD_DELEGATE,
        "has no live caller",
    )
    dialect_arm(
        "10-1 the endpoint field written from a raw read instead of the parser",
        GOOD_CALLER_DEAD_DELEGATE,
        "is not derived from parseCustomEndpointPath(",
    )
    dialect_arm(
        "10-2 the matcher result short-circuited by `|| true`",
        GOOD_CALLER_ABSORBED,
        "is not the filter any more",
    )
    dialect_arm(
        "10-2b `providerMatches(` present only inside a string literal",
        GOOD_CALLER_MATCHER_IN_STRING,
        "does not call the single providerMatches",
    )
    dialect_arm(
        "10-3 the alias derives on the continuation line",
        GOOD_CALLER_MULTILINE_ALIAS,
        "binds a DERIVED value",
    )
    dialect_arm(
        "10-6 a `\"/*\"` literal smuggling an elvis fallback past the stripper",
        GOOD_CALLER_STRING_COMMENT_DELEGATE,
        "NOT a one-line delegate",
    )

    # 10-4 / 10-5: the read is present but the value is not the one used.
    with tempfile.TemporaryDirectory() as root:
        write(root, PROTO_REL, GOOD_PROTO)
        write(root, CALLER_RELS[0],
              wiring_service(run_mt_line='        val maxTokens = '
                                          'req.optInt("max_tokens", 4096).let { 4096 }\n'))
        write(root, CALLER_RELS[1], GOOD_CALLER)
        write(root, EXEC_DISPATCH_REL, wiring_dispatch())
        bad = check_service_wiring(root)
        if not any("the value read from 'max_tokens' is replaced before use" in b for b in bad):
            ok = False
            print("SELFTEST-FAIL 10-4 a `max_tokens` read replaced by a constant must be caught:", bad)

    with tempfile.TemporaryDirectory() as root:
        write(root, PROTO_REL, GOOD_PROTO)
        write(root, CALLER_RELS[0],
              wiring_service(parse_line='        val thinkingLevel = '
                                        'thinkingLevelFromWire(req.optString("thinking_level", "").take(0))\n'))
        write(root, CALLER_RELS[1], GOOD_CALLER)
        write(root, EXEC_DISPATCH_REL, wiring_dispatch())
        bad = check_service_wiring(root)
        if not any("mangled by `.take(`" in b for b in bad):
            ok = False
            print("SELFTEST-FAIL 10-5 a lossy `.take(0)` on the level read must be caught:", bad)
        if not any("is not derived from a live read of 'thinking_level'" in b for b in bad):
            ok = False
            print("SELFTEST-FAIL 10-5 the mangled level must also fail the C2 trace:", bad)

    if ok:
        print("MODELUSE_PROTO_SELFTEST=PASS")
        print("MODELUSE_PROVIDER_MATCHER_SELFTEST=PASS (4 fixtures: 3 violations caught, 1 fail-closed)")
        return 0
    return 1


def main():
    args = [a for a in sys.argv[1:] if a != "--selftest"]
    if "--selftest" in sys.argv[1:]:
        sys.exit(selftest())
    if len(args) > 1:
        print(__doc__)
        sys.exit(2)
    root = args[0] if args else os.getcwd()
    violations = check(root) + check_provider(root) + check_enriched_dispatch(root) + check_service_wiring(root)
    if violations:
        print("modeluse_proto_single_source_check: %d violation(s)" % len(violations))
        for v in violations:
            print("  " + v)
        sys.exit(1)
    print("modeluse_proto_single_source_check: clean (dialect single-sourced in ModelUseProtocol)")
    sys.exit(0)


if __name__ == "__main__":
    main()
