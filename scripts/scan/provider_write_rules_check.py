#!/usr/bin/env python3
"""ARCH-01 gate — provider write rules are single-sourced (ProviderWriteRules).

Companion to `provider_rpc_symmetry_check.py`. That gate is deliberately
*shape-tolerant* (it accepts both the pre-ARCH-01 inline predicate + literal
message and the post-ARCH-01 shared-constant form, so it does not fire on
unrelated in-flight edits). This gate pins the ARCH-01 end state itself: the
shared rule layer must exist, must be declared exactly once, and every write
face must route through it instead of carrying a copy.

Criteria (all fail-closed — a missing file or missing anchor is RED, never a
silent pass):
  W1  `ProviderWriteRules.kt` declares CUSTOM_BASE_URL_OPENROUTER_MESSAGE,
      isCustomBaseURLWriteAllowed, normalizeCustomModelId and
      canonicalModelProviderName.
  W2  CUSTOM_BASE_URL_OPENROUTER_MESSAGE is declared exactly once across the
      main source set (a second copy in ProvidersCollection /
      ProviderMutationMethods is the drift this whole layer exists to stop).
  W3  CUSTOM_USER_AGENT_OAUTH_MESSAGE is declared exactly once (it stays in
      ProvidersCollection.kt — `memory_gate_provider_fields_check` pins that
      home; ARCH-01 must not move it out).
  W4  ProviderMutationMethods.kt carries NO literal copy of either message —
      both faces must reference the shared constants.
  W5  The debug mutation faces reference the shared rules: instancesCreate and
      instancesUpdate bodies each contain CUSTOM_BASE_URL_OPENROUTER_MESSAGE,
      and the customUserAgent guard sites call customUserAgentWritable(.
  W6  modelsAdd() uses normalizeCustomModelId( and canonicalModelProviderName(
      and no longer builds the label inline.
  W7  ProvidersCollection.kt's `customBaseURL(id)` writer routes through
      isCustomBaseURLWriteAllowed( — pins ARCH-01's wiring decision, which the
      shape-tolerant symmetry gate intentionally cannot.

  W8  [deep-audit-1039 A-04] `ProviderWriteRules.kt` declares the
      normalization single source `normalizeCustomBaseURL`, and every
      customBaseURL write face consumes it instead of spelling its own
      blank/trim handling. Faces checked: the collection field writer
      (ProvidersCollection), the add payload path
      (`resolveAddCustomBaseURL`), and the debug
      `provider.instances.create` / `instancesUpdate` bodies
      (ProviderMutationMethods). fail-closed: an unknown/moved anchor is RED.

  W10 [meta-audit-1024 anchor] W8 pins the *declaration* and the *call sites*;
  the external witness stripped `.trim()` out of the helper's own body while
  leaving both intact, so the gate stayed green while every write face began
  persisting padded / blank URLs again. W10 therefore reads the body: it must
  carry whitespace normalization (`.trim()`) AND the blank -> null collapse
  (`takeIf { it.isNotEmpty() }` / `takeIf { it.isNotBlank() }` /
  `ifEmpty { null }`). A body that cannot be extracted is RED (fail-closed),
  never a silent pass.

  W13 [R3-t20 w39] `effectiveBaseURL` must apply `appendV1Suffix` with the
      documented polarity (append `/v1` IFF the flag is true). W9 pinned the
      read-side trimming chain; the flag's own polarity sat between W9 and
      xproc_boundary_check and neither gate asked about it, so inverting the
      condition silently sent every default instance to `host/chat/completions`.

  W14 [R3-t20 w40] The capability getters on `ProviderInstance`
      (`supportsImageEndpointSetting`, `supportsAzureMode`,
      `supportsCustomThinkingRules`) must stay DECISIONS: a getter folded to a
      constant hides/redirects UI for every instance while W9's `effectiveBaseURL`
      anchors (and xproc's constructor-field list) stay green.

  W15 [R3-t20 w41] `ProvidersCollection`'s `customUserAgent(id)` writer must
      route through `customUserAgentWritable(`, and the guard must actually
      terminate. W5 pins the debug create/update faces and W7 pins the
      `customBaseURL` writer — the 2x2 (two write faces x two fields) was
      missing exactly this quadrant, so `minis-config set …customUserAgent`
      could overwrite an OAuth client's UA.

Usage:  provider_write_rules_check.py <repo-root>
        provider_write_rules_check.py --selftest
"""

import json
import os
import re
import shutil
import sys
import tempfile

# [batch6] structural analysis comes from the shared layer: no local
# comment/string stripper, no line-based window, no hand-rolled expression span.
from _kotlin_lexer import KotlinLexError
from _kotlin_ast import (match_bracket, split_top, strip_parens, const_bool,
                         const_value, if_blocks, stmt_spans, tail_expr)
from _kotlin_valueflow import (code_view, literal_view, peel_discarding, expr_end,
                               contains_ident, path_feasible)

SELFTEST_MARKER = "PROVIDER_WRITE_RULES_SELFTEST=PASS"

PWR_REL = "src/android/app/src/main/java/com/rikkaminis/app/config/collections/ProviderWriteRules.kt"
PC_REL = "src/android/app/src/main/java/com/rikkaminis/app/config/collections/ProvidersCollection.kt"
PM_REL = "src/android/app/src/main/java/com/rikkaminis/app/debug/ProviderMutationMethods.kt"
PCF_REL = "src/android/app/src/main/java/com/rikkaminis/app/data/model/ProviderConfig.kt"

# [deep-audit-1039 A-04] The normalization single source, and the bare
# blank-handling spellings the customBaseURL faces used to carry on their own.
NORMALIZE_DECL = "internal fun normalizeCustomBaseURL"
NORMALIZE_CALL = "normalizeCustomBaseURL("
# A customBaseURL write face that still trims/collapses inline is the drift W8
# exists to stop. The check is scoped to the lines that TOUCH customBaseURL:
# apiKey / customUserAgent / oauthToken blank handling is a different field's
# business and must not make this criterion fire (a criterion that reds the
# tree for an unrelated sibling is a broken criterion, not a strict one).
INLINE_BLANK_PATTERNS = (
    ".ifEmpty { null }",
    "takeIf { it.isNotBlank() }",
)
# Faces that must consume the shared helper (label, file-relative path, code).
# `add` is checked inside ProvidersCollection (resolveAddCustomBaseURL body),
# so the PC-side entry is the field writer only.
BASE_URL_FACES = (
    ("ProvidersCollection.customBaseURL writer", "PC"),
    ("ProvidersCollection.resolveAddCustomBaseURL", "PC"),
    ("ProviderMutationMethods.instancesCreate", "PM"),
    ("ProviderMutationMethods.instancesUpdate", "PM"),
)


def _interior(body):
    """The statement region of a (possibly) brace-wrapped body. A body that
    starts with `{` is a block header: feeding it to the statement splitter
    makes the whole function one depth-0 statement, so every line-level question
    ("which lines touch customBaseURL?") gets answered for the entire body."""
    t = body
    stripped = t.lstrip()
    if not stripped.startswith("{"):
        return t
    open_i = t.index("{")
    close = match_bracket(t, open_i)
    return t[open_i + 1:close] if close > 0 else t[open_i + 1:]


def inline_blank_on_base_url_lines(body):
    """STATEMENTS of `body` that touch customBaseURL and spell blank handling
    inline — the drift W8 forbids.

    [batch6 A09-3] The old version was line-based, so the drift simply moved to
    the continuation line of a multi-line call:

        params.optString(
            "customBaseURL", ""
        ).ifEmpty { null }

    The anchor line carried no pattern and the pattern line carried no anchor.
    A statement is the unit that actually holds both halves.
    """
    raw = _interior(body)
    code = code_view(raw)
    offenders = []
    for a, b in stmt_spans(code):
        seg = code[a:b]
        if "customBaseURL" not in seg:
            continue
        for pat in INLINE_BLANK_PATTERNS:
            if pat in seg:
                offenders.append((re.sub(r"\s+", " ", raw[a:b]).strip()[:90], pat))
    return offenders

SRC_SCAN_ROOT = "src/android/app/src/main/java"

BASE_URL_MSG_DECL = "internal const val CUSTOM_BASE_URL_OPENROUTER_MESSAGE"
UA_MSG_DECL = "internal const val CUSTOM_USER_AGENT_OAUTH_MESSAGE"

OPENROUTER_MSG = "customBaseURL is not supported for providerType=openRouter"
UA_MSG = "Custom User-Agent is only supported for third-party API-key providers."

SHARED_RULE_DECLS = (
    "internal const val CUSTOM_BASE_URL_OPENROUTER_MESSAGE",
    "internal fun isCustomBaseURLWriteAllowed",
    "internal fun normalizeCustomModelId",
    "internal fun canonicalModelProviderName",
)


def strip_comments(text):
    """Remove // and /* */ comments, respecting string literals."""
    out = []
    i, n = 0, len(text)
    in_str = False
    while i < n:
        c = text[i]
        if in_str:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def read(path):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except OSError:
        return None


def read_code(path):
    raw = read(path)
    return None if raw is None else strip_comments(raw)


def extract_function(code, name):
    """Body of `fun <name>(...)` — handles `{ }` and `= <expr>` bodies and an
    optional single-line return-type annotation (`: Foo` / `: Foo<Bar>`)."""
    m = re.search(
        r"\bfun\s+%s\s*\((?:[^()]|\([^()]*\))*\)\s*(?::[^\n={]+)?[={]" % re.escape(name),
        code,
    )
    if not m:
        return None
    opener = m.group(0)[-1]
    j = m.end() - 1
    if opener == "=":
        # expression body — skip whitespace/newlines (the body may begin on the
        # following line), then scan with bracket awareness so a multi-line call
        # such as `= ClosureField(\n  writer = { … },\n)` is captured whole.
        k = j + 1
        while k < len(code) and code[k] in " \t\r\n":
            k += 1
        depth = 0
        opened = False
        while k < len(code):
            c = code[k]
            if c in "([{":
                depth += 1
                opened = True
            elif c in ")]}":
                depth -= 1
                if opened and depth == 0:
                    return code[j:k + 1]
            elif c == "\n" and not opened:
                return code[j:k]
            k += 1
        return code[j:]
    depth = 0
    k = j
    while k < len(code):
        if code[k] == "{":
            depth += 1
        elif code[k] == "}":
            depth -= 1
            if depth == 0:
                return code[j:k + 1]
        k += 1
    return None


# [meta-audit-1024 anchor] W10 reads the *body* of the shared normalization
# helper: whitespace normalization (trim) AND the blank -> null collapse. Both
# have to be there; the witness removed only the trim and the gate stayed green.
TRIM_RE = re.compile(r"\.trim[A-Za-z]*\s*\(")
COLLAPSE_RE = re.compile(
    r"takeIf\s*\{[^}\n]*isNot(?:Empty|Blank)\(\)"
    r"|ifEmpty\s*\{[^}\n]*null"
)


def expression_body(code, name):
    """Body of an expression-bodied `fun <name>(...) = …`.

    [meta-audit-1024 anchor] `extract_function` stops at the FIRST balanced
    paren pair, so `= raw?.trim()?.takeIf { it.isNotEmpty() }` was captured as
    `= raw?.trim()` — a body-level criterion built on it would have read only
    the first call and gone blind on the collapse half. This variant keeps
    scanning while the expression carries on with `.` / `?.` after a `)`/`}`.

    fail-closed: no declaration, an empty body, or an unterminated (bracket-
    unbalanced) body all return None, which every caller must treat as RED.
    """
    m = re.search(
        r"\bfun\s+%s\s*\((?:[^()]|\([^()]*\))*\)\s*(?::[^\n={]+)?=" % re.escape(name),
        code,
    )
    if not m:
        return None
    j = m.end() - 1  # index of '='
    k = j + 1
    while k < len(code) and code[k] in " \t\r\n":
        k += 1
    depth = 0
    opened = False
    while k < len(code):
        c = code[k]
        if c in "([{":
            depth += 1
            opened = True
        elif c in ")]}":
            depth -= 1
            if depth < 0:
                return None
            if depth == 0:
                nxt = k + 1
                while nxt < len(code) and code[nxt] in " \t":
                    nxt += 1
                if nxt < len(code) and code[nxt] in ".?":
                    k = nxt
                    continue
                return code[j:k + 1]
        elif c == "\n" and depth == 0:
            # A bare single-token body (`= raw`) is extractable and is exactly
            # the "helper emptied out" shape W10 must catch, so only an EMPTY
            # body is fail-closed here.
            text_so_far = code[j:k]
            return text_so_far if text_so_far.strip("= \t\r\n") else None
        k += 1
    return None  # unterminated body — fail-closed


def count_decl(root, needle):
    """Occurrences of `needle` across every .kt file under SRC_SCAN_ROOT."""
    hits = []
    base = os.path.join(root, SRC_SCAN_ROOT)
    for dirpath, _dirnames, filenames in os.walk(base):
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            p = os.path.join(dirpath, fn)
            code = read_code(p)
            if code is None:
                continue
            c = code.count(needle)
            if c:
                hits.append((os.path.relpath(p, root), c))
    return hits


# ── [batch6 A09] dataflow helpers ───────────────────────────────────────────
# Every witness in this batch kept the *spelling* (the call, the guard, the
# `.trim()`) and moved the *value*: `raw.also { raw?.trim()… }` returns `raw`,
# `val newBase = s` skips the helper while the helper still appears on a dead
# neighbour line, a two-line `optString(…).ifEmpty { null }` wears the drift on
# the continuation line, and `= true` deletes a policy whose head is intact.
# So the criteria below ask "does the value that leaves this face derive from
# the normalized chain?" instead of "does the token appear?".

BIND_RE = re.compile(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=(?!=)")
IDENT_RE = re.compile(r"(?<![\w.])[A-Za-z_]\w*")
BASE_WRITE_RE = re.compile(r"(?<![\w.])customBaseURL\s*=(?!=)")
NULLISH_RE = re.compile(r'^null$|^""$')
# code_view blanks string/char contents (`.trimEnd('/')` → `.trimEnd(' ')`), so
# the trailing-slash half is matched structurally, not by literal comparison.
TRIM_END_RE = re.compile(r"trimEnd\(\s*'[^']*'\s*\)")


def _rhs_end(code, start):
    """End of the value expression that begins at `start`: stops at a depth-0
    `,` / `)` / `}` / `]` (an argument boundary) or at a depth-0 newline that is
    not a continuation. `expr_end` alone runs past the trailing `,` of a named
    argument (`… else current.customBaseURL,`) and would swallow the next
    argument into the value."""
    depth = 0
    i = start
    while i < len(code):
        ch = code[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                return i
            depth -= 1
        elif ch == "," and depth == 0:
            return i
        elif ch == "\n" and depth == 0:
            j = i + 1
            while j < len(code) and code[j] in " \t\r\n":
                j += 1
            prev = code[start:i].rstrip()
            if not prev:
                i = j
                continue
            if prev.endswith((".", "?.", "?:", "&&", "||", "=", "+", "-", "->",
                              "if", "else")):
                i = j
                continue
            if j < len(code) and code[j] in ".?:":
                i = j
                continue
            return i
        i += 1
    return len(code)


def _local_inits(code):
    """name -> [init expression] for the local `val`/`var`s of one body."""
    out = {}
    for m in BIND_RE.finditer(code):
        end = _rhs_end(code, m.end())
        out.setdefault(m.group(1), []).append(code[m.end():end])
    return out


def _values_leaving(code):
    """Every value that can leave a face: each `customBaseURL = …` write and
    each bare `return …`. Held to one rule — it must derive from the shared
    normalization — so a face cannot call the helper into a discarded local and
    persist something else."""
    vals = []
    for m in BASE_WRITE_RE.finditer(code):
        vals.append(code[m.end():_rhs_end(code, m.end())])
    for m in re.finditer(r"\breturn\b", code):
        if code.startswith("return@", m.start()):
            continue
        vals.append(code[m.end():_rhs_end(code, m.end())])
    return [v for v in vals if v.strip()]


def _derives_from_normalize(code, expr, inits=None, depth=2):
    """Does `expr` come out of `normalizeCustomBaseURL(…)` — directly, through a
    local binding, or as a blank-as-clear constant?"""
    e = strip_parens(expr.strip())
    if not e:
        return False
    if NORMALIZE_CALL in e:
        return True
    if NULLISH_RE.match(e):
        return True
    if depth <= 0:
        return False
    inits = _local_inits(code) if inits is None else inits
    for name in set(IDENT_RE.findall(e)):
        for init in inits.get(name, []):
            if _derives_from_normalize(code, init, inits, depth - 1):
                return True
    return False


def _is_normalized(expr):
    """The full read-side chain: whitespace trim AND trailing-slash collapse
    AND blank->null. `.trimEnd(…)` alone does not satisfy the whitespace half
    (that is S13's witness)."""
    e = strip_parens(expr.strip())
    if NULLISH_RE.match(e):
        return True
    return (".trim()" in e and bool(TRIM_END_RE.search(e))
            and bool(COLLAPSE_RE.search(e)))


def _fold_const_if(expr):
    """Fold `if (<const>) A else B` to the branch that actually runs."""
    m = re.match(r"if\s*\(", expr)
    if not m:
        return expr
    op = m.end() - 1
    cp = match_bracket(expr, op)
    if cp < 0:
        return expr
    cond = const_bool(expr[op + 1:cp])
    if cond is None:
        return expr
    rest = expr[cp + 1:].strip()
    parts = split_top(rest, ("else",))
    if parts and len(parts) >= 2:
        then_txt = rest[parts[0][0]:parts[0][1]]
        else_txt = rest[parts[1][0]:parts[1][1]]
        branch = then_txt if cond else else_txt
    else:
        branch = rest
    b = branch.strip()
    if b.startswith("{"):
        close = match_bracket(b, 0)
        if close > 0:
            b = b[1:close]
    return b.strip()


def effective_value(body):
    """The value a helper body actually returns, after peeling the scopes that
    discard it (`raw.also { normalize(raw) }` → `raw`) and folding constant
    `if`s. This is the difference between "the normalization is written" and
    "the normalization is what comes out"."""
    e = body.strip()
    if e.startswith("="):
        e = e[1:].strip()
    for _ in range(8):
        prev = e
        e = _fold_const_if(e)
        pe = peel_discarding(e).strip()
        if pe:
            e = pe
        if e == prev:
            break
    return e


def _shared_body(code, name):
    """Body of `fun <name>` — block form as-is, expression form via a
    continuation-aware scan.

    The older `expression_body` stopped at the FIRST balanced paren, so
    `customBaseURL.isNullOrBlank() || providerType.supportsCustomBaseURL` was
    read as `customBaseURL.isNullOrBlank()` — the capability arm (and the whole
    decision) fell outside the slice. `|| true` had the same escape route.
    """
    m = re.search(r"\bfun\s+[\w.]*\b%s\s*\(" % re.escape(name), code)
    if not m:
        return None
    op = m.end() - 1
    cp = match_bracket(code, op)
    if cp < 0:
        return None
    i = cp + 1
    depth = 0
    while i < len(code):
        ch = code[i]
        if ch == "{":
            close = match_bracket(code, i)
            return code[i:close + 1] if close > 0 else None
        if ch == "=" and code[i:i + 2] != "==" and depth == 0:
            start = i + 1
            end = _rhs_end(code, start)
            body = code[start:end]
            return body if body.strip() else None
        if ch in "([":
            depth += 1
        elif ch in ")]":
            depth -= 1
        i += 1
    return None


def _decl_params(code, name):
    """Parameter names of `fun <name>(…)`, derived from the declaration."""
    m = re.search(r"\bfun\s+[\w.]*\b%s\s*\(" % re.escape(name), code)
    if not m:
        return []
    op = m.end() - 1
    cp = match_bracket(code, op)
    if cp < 0:
        return []
    names = []
    for a, b in split_top(code[op + 1:cp], (",")):
        decl = code[op + 1 + a:op + 1 + b].strip()
        nm = re.match(r"([A-Za-z_]\w*)\s*:", decl)
        if nm:
            names.append(nm.group(1))
    return names


def _snip(raw, code, a, b):
    """Message text taken from the raw body at the offsets found in the masked
    body (masking preserves length), so findings stay readable."""
    return re.sub(r"\s+", " ", raw[a:b]).strip()[:80]

# ── [R3-t20] W13 / W14 / W15: the values that walk between two gates ──────
APPEND_V1 = "appendV1Suffix"
NEG_APPEND_RE = re.compile(
    r"!\s*appendV1Suffix\b|appendV1Suffix\s*==\s*false|appendV1Suffix\s*\.\s*not\s*\(")


def v1_suffix_problems(cc, lc):
    """W13 — `/v1` is appended IFF `appendV1Suffix` is true.

    Judged on the branch that BUILDS the suffix, in `literal_view` (the
    `"/v1"` literal is blanked in the code channel) mapped onto the
    `if_blocks` spans of the code channel — both views are length-preserving,
    so the offsets line up.
    """
    span = effective_base_span(cc)
    if span is None:
        return []  # W9 already reports the missing getter (fail-closed)
    s, e = span
    body_c, body_l = cc[s:e], lc[s:e]
    # `/` cannot appear inside an identifier, so no look-behind is needed — and
    # a look-behind would MISS the template form `"$base/v1"` (the `/` follows
    # `base`), which is exactly how the production getter builds it.
    suffix = [m.start() for m in re.finditer(r"/v1(?![A-Za-z0-9_])", body_l)]
    if not suffix:
        return ["effectiveBaseURL never builds the `/v1` suffix — `appendV1Suffix` "
                "has no effect on the URL that leaves the model"]
    guards = []
    for cond, ts, te, es, ee in if_blocks(body_c):
        if not re.search(r"(?<![A-Za-z0-9_])appendV1Suffix(?![A-Za-z0-9_])", cond):
            continue
        guards.append((cond,
                       bool(NEG_APPEND_RE.search(cond)),
                       any(ts <= q < te for q in suffix),
                       es is not None and any(es <= q < ee for q in suffix)))
    if not guards:
        return ["effectiveBaseURL builds the `/v1` suffix outside any "
                "`appendV1Suffix` guard — the flag is consulted nowhere"]
    out = []
    for cond, negated, then_has, else_has in guards:
        flat = re.sub(r"\s+", " ", cond).strip()[:70]
        if then_has and negated:
            out.append("effectiveBaseURL appends `/v1` when `appendV1Suffix` is FALSE "
                       "(`if (%s)`) — the polarity is inverted: the default instance "
                       "(appendV1Suffix = true) loses the version segment and a URL "
                       "that pins its own version gets one appended" % flat)
        if else_has and not negated:
            out.append("effectiveBaseURL's `else` branch appends `/v1` under a POSITIVE "
                       "`appendV1Suffix` guard (`if (%s) … else …`) — the suffix lands "
                       "exactly when the flag is off" % flat)
    if not any(t or el for _c, _n, t, el in guards):
        out.append("no `appendV1Suffix` branch builds the suffix — the guard and the "
                   "value it guards were separated (fail-closed)")
    return out


CAPABILITY_PROPS = ("supportsImageEndpointSetting", "supportsAzureMode",
                    "supportsCustomThinkingRules")
CAPABILITY_INPUTS = ("providerType", "credentialType", "useResponsesAPI", "customBaseURL")


def property_body(code, name):
    """Body of `val <name>: T get() = …` / `get() { … }` (expression or block)."""
    m = re.search(r"\bval\s+%s\s*:" % re.escape(name), code)
    if m is None:
        return None
    g = code.find("get()", m.end())
    if g < 0:
        return None
    if re.search(r"\n\s{0,8}(?:val|var|fun|internal|private|public|override|@)\b",
                 code[m.end():g]):
        return None  # another declaration sits in between — not this property
    j = g + len("get()")
    while j < len(code) and code[j] in " \t":
        j += 1
    if j >= len(code):
        return None
    if code[j] == "=":
        return code[j + 1:expr_end(code, j + 1)]
    if code[j] == "{":
        close = match_bracket(code, j)
        return None if close < 0 else code[j + 1:close]
    return None


def capability_getter_problems(cc):
    """W14 — a capability getter is a decision about THIS instance.

    W9 anchors `effectiveBaseURL`; xproc anchors the constructor field list.
    The computed properties in between were unowned, so `get() = false` closed
    an entire UI surface (custom thinking rules) for every provider while both
    gates stayed green.
    """
    out = []
    for name in CAPABILITY_PROPS:
        body = property_body(cc, name)
        if body is None:
            out.append("`%s`: getter body not extractable — re-anchor W14 "
                       "(fail-closed)" % name)
            continue
        eff = effective_value(body)
        if not eff.strip():
            out.append("`%s`: getter has no value — re-anchor W14" % name)
            continue
        folded = const_bool(strip_parens(eff))
        if folded is not None:
            out.append("`%s` returns the CONSTANT `%s` — every instance gets the same "
                       "capability answer, so the UI gate it drives is gone"
                       % (name, re.sub(r"\s+", " ", eff).strip()[:50]))
            continue
        if not any(contains_ident(eff, key) for key in CAPABILITY_INPUTS):
            out.append("`%s` never reads %s — the answer cannot depend on the instance"
                       % (name, " / ".join(CAPABILITY_INPUTS)))
    return out


UA_WRITE_GUARD = "customUserAgentWritable("


def ua_writer_problems(pc_code):
    """W15 — the `minis-config` UA writer must carry the same guard as the
    debug faces, and the guard must actually stop the write."""
    writer = extract_function(pc_code, "customUserAgent")
    if writer is None:
        return ["customUserAgent(id) writer not found in ProvidersCollection — "
                "re-anchor W15 (fail-closed)"]
    if UA_WRITE_GUARD not in writer:
        return ["customUserAgent writer does not route through customUserAgentWritable( "
                "— an OAuth instance's UA can be written through `minis-config set` "
                "while the debug create/update faces still block it (the 2x2 matrix "
                "lost its fourth quadrant)"]
    wc = code_view(writer)
    write_pos = wc.find("mutate(")
    for cond, ts, te, _es, _ee in if_blocks(wc):
        if not re.search(r"customUserAgentWritable\s*\(", cond):
            continue
        if not path_feasible(wc, ts):
            continue
        if not re.search(r"\bthrow\b|\breturn\b", wc[ts:te]):
            continue
        if write_pos >= 0 and ts > write_pos:
            continue
        return []
    return ["customUserAgent writer calls customUserAgentWritable( but no reachable "
            "branch on it terminates BEFORE the write — the guard is decoration"]


def check_tree(root):
    detail = {k: [] for k in ("w1", "w2", "w3", "w4", "w5", "w6", "w7",
                               "w8", "w9", "w10", "w11", "w12", "w13",
                               "w14", "w15", "fail_closed")}

    pwr = read_code(os.path.join(root, PWR_REL))
    pc = read_code(os.path.join(root, PC_REL))
    pm = read_code(os.path.join(root, PM_REL))
    if pwr is None:
        detail["fail_closed"].append("%s missing" % PWR_REL)
    if pc is None:
        detail["fail_closed"].append("%s missing" % PC_REL)
    if pm is None:
        detail["fail_closed"].append("%s missing" % PM_REL)
    if detail["fail_closed"]:
        return 1, detail

    # ---- W1 ----
    for decl in SHARED_RULE_DECLS:
        if decl not in pwr:
            detail["w1"].append("ProviderWriteRules lacks %r" % decl)

    # ---- W2 / W3 ----
    base_hits = count_decl(root, BASE_URL_MSG_DECL)
    ua_hits = count_decl(root, UA_MSG_DECL)
    if sum(c for _, c in base_hits) != 1:
        detail["w2"].append("CUSTOM_BASE_URL_OPENROUTER_MESSAGE declared %d times: %s"
                            % (sum(c for _, c in base_hits), base_hits))
    elif base_hits[0][0] != PWR_REL:
        detail["w2"].append("declared in %s, expected %s" % (base_hits[0][0], PWR_REL))
    if sum(c for _, c in ua_hits) != 1:
        detail["w3"].append("CUSTOM_USER_AGENT_OAUTH_MESSAGE declared %d times: %s"
                            % (sum(c for _, c in ua_hits), ua_hits))
    elif ua_hits[0][0] != PC_REL:
        detail["w3"].append("declared in %s, expected %s (memory-gate home)"
                            % (ua_hits[0][0], PC_REL))

    # ---- W4 ----
    if OPENROUTER_MSG in pm:
        detail["w4"].append("ProviderMutationMethods still carries an openRouter message literal")
    if UA_MSG in pm:
        detail["w4"].append("ProviderMutationMethods still carries a customUserAgent message literal")

    # ---- W5 ----
    for fname in ("instancesCreate", "instancesUpdate"):
        body = extract_function(pm, fname)
        if body is None:
            detail["fail_closed"].append("%s not found in ProviderMutationMethods" % fname)
            continue
        if "CUSTOM_BASE_URL_OPENROUTER_MESSAGE" not in body:
            detail["w5"].append("%s does not reference the shared base-URL message" % fname)
        if "customUserAgentWritable(" not in body:
            detail["w5"].append("%s does not route the UA guard through customUserAgentWritable(" % fname)

    # ---- W6 ----
    models_add = extract_function(pm, "modelsAdd")
    if models_add is None:
        detail["fail_closed"].append("modelsAdd not found in ProviderMutationMethods")
    else:
        if "normalizeCustomModelId(" not in models_add:
            detail["w6"].append("modelsAdd does not use normalizeCustomModelId(")
        if "canonicalModelProviderName(" not in models_add:
            detail["w6"].append("modelsAdd does not use canonicalModelProviderName(")
        if "instance.providerType.displayName" in models_add:
            detail["w6"].append("modelsAdd still builds the provider label inline")

    # ---- W15 [R3-t20 w41] the minis-config UA writer carries the same guard ----
    detail["w15"].extend(ua_writer_problems(pc))

    # ---- W7 ----
    writer = extract_function(pc, "customBaseURL")
    if writer is None:
        detail["fail_closed"].append("customBaseURL(id) writer not found in ProvidersCollection")
    else:
        if "isCustomBaseURLWriteAllowed(" not in writer:
            detail["w7"].append("customBaseURL writer does not route through isCustomBaseURLWriteAllowed(")

    # ---- W8 [deep-audit-1039 A-04] normalization is single-sourced ----
    if NORMALIZE_DECL not in pwr:
        detail["w8"].append("ProviderWriteRules lacks %r (normalization single source)" % NORMALIZE_DECL)

    face_code = {
        "PC": pc,
        "PM": pm,
    }
    for label, key in BASE_URL_FACES:
        code = face_code[key]
        if label.endswith("writer"):
            body = extract_function(code, "customBaseURL")
        elif label.endswith("resolveAddCustomBaseURL"):
            body = extract_function(code, "resolveAddCustomBaseURL")
        else:
            body = extract_function(code, label.split(".")[-1])
        if body is None:
            detail["fail_closed"].append("%s: face body not found — re-anchor W8" % label)
            continue
        if NORMALIZE_CALL not in body:
            detail["w8"].append("%s does not consume %s" % (label, NORMALIZE_CALL))
        for line_text, pat in inline_blank_on_base_url_lines(body):
            detail["w8"].append("%s spells blank handling inline on a customBaseURL statement (%r): %s"
                                % (label, pat, line_text))
        # [batch6 A09-2] The call being *present* proves nothing: a witness keeps
        # it on a discarded local (`val _ignored = normalizeCustomBaseURL(s)`) and
        # persists `s`. Follow the value that leaves the face instead.
        bc = code_view(_interior(body))
        inits = _local_inits(bc)
        leaving = _values_leaving(bc)
        if not leaving:
            detail["w8"].append(
                "%s: no customBaseURL write / return value found — re-anchor W8" % label)
        for val in leaving:
            if not _derives_from_normalize(bc, val, inits):
                detail["w8"].append(
                    "%s persists / returns a value that never went through %s (`%s`) — "
                    "the helper is called in the face, but the written value is not its "
                    "result" % (label, NORMALIZE_CALL,
                                re.sub(r"\s+", " ", val).strip()[:80]))

    # No other file in the main source set may carry a second normalization
    # helper: exactly one declaration tree-wide.
    norm_decls = count_decl(root, NORMALIZE_DECL)
    if sum(c for _, c in norm_decls) != 1:
        detail["w8"].append("normalizeCustomBaseURL declared %d times: %s"
                            % (sum(c for _, c in norm_decls), norm_decls))
    elif norm_decls[0][0] != PWR_REL:
        detail["w8"].append("normalizeCustomBaseURL declared in %s, expected %s"
                            % (norm_decls[0][0], PWR_REL))

    # ---- W10 [meta-audit-1024 anchor] the normalization BODY still normalizes
    # W8 pins the declaration head and the call sites; the external witness
    # stripped `.trim()` out of the body while leaving both intact, so the gate
    # stayed green while every write face persisted padded / blank URLs again.
    # The declaration is not the decision point — its body is.
    norm_body = _shared_body(pwr, "normalizeCustomBaseURL")
    if norm_body is None:
        detail["fail_closed"].append(
            "normalizeCustomBaseURL body not extractable from ProviderWriteRules — re-anchor W10")
    else:
        # [batch6 A09-1] Match on the value the body RETURNS, not on the tokens
        # it spells. `raw.also { raw?.trim()?.takeIf { … } }` contains both
        # halves and returns the raw input; a constant `if (true) raw else …`
        # does the same. Peeling discarded scopes and folding constant branches
        # leaves the value that is actually persisted.
        effective = effective_value(code_view(norm_body))
        if not TRIM_RE.search(effective):
            detail["w10"].append(
                "normalizeCustomBaseURL returns a value without whitespace "
                "normalization (.trim()): %r (body: %r)"
                % (effective.strip()[:90], norm_body.strip()[:90]))
        if not COLLAPSE_RE.search(effective):
            detail["w10"].append(
                "normalizeCustomBaseURL returns a value without the blank->null "
                "collapse: %r (body: %r)"
                % (effective.strip()[:90], norm_body.strip()[:90]))

    # ---- W9 [deep-audit-1039 A-04] the read side trims before use ----
    pcf = read_code(os.path.join(root, PCF_REL))
    if pcf is None:
        detail["fail_closed"].append("%s missing" % PCF_REL)
    else:
        getter = effective_base_getter(pcf)
        if getter is None:
            detail["fail_closed"].append("effectiveBaseURL getter not found in ProviderConfig.kt — re-anchor W9")
        else:
            # [batch6 A09-4] Whole-getter substring matching let a witness park
            # the whole chain on a local nobody reads (`val _unused = trim…`) and
            # return the raw field. The chain has to be on the value that is
            # RETURNED — directly or through a local the return actually names.
            gc = code_view(getter)
            inits = _local_inits(gc)
            norm_names = {n for n, exprs in inits.items()
                          if any(_is_normalized(e) for e in exprs)}
            returns = [gc[m.end():_rhs_end(gc, m.end())]
                       for m in re.finditer(r"\breturn\b", gc)
                       if not gc.startswith("return@", m.start())]
            returns = [r for r in returns if r.strip()]
            if not returns:
                detail["w9"].append(
                    "effectiveBaseURL getter has no return value — re-anchor W9")
            for val in returns:
                if _is_normalized(val):
                    continue
                if any(name in norm_names for name in set(IDENT_RE.findall(val))):
                    continue
                detail["w9"].append(
                    "effectiveBaseURL returns a value that skips the read-side chain "
                    "(`%s`) — the trim / trailing-slash / blank-collapse work happens "
                    "on a local the return never names"
                    % re.sub(r"\s+", " ", val).strip()[:80])

    # ---- W13/W14 [R3-t20] the getter's polarity + the capability getters ----
    # Judged on the length-preserving shared views of the RAW file: the gate's
    # own `read_code` drops comment text, so its offsets cannot be mapped onto
    # the literal channel (see report §6 — W1..W12 still read through it).
    if pcf is not None:
        raw_pcf = read(os.path.join(root, PCF_REL)) or ""
        cc_pcf = code_view(raw_pcf)
        lc_pcf = literal_view(raw_pcf)
        detail["w13"].extend(v1_suffix_problems(cc_pcf, lc_pcf))
        detail["w14"].extend(capability_getter_problems(cc_pcf))

    # ---- W11 [batch6 A09-5] normalizeCustomModelId still normalizes --------
    # The model-id helper carries the same contract as the URL helper (trim +
    # blank -> null). A witness emptied it to `= raw` and both the minis-config
    # face and the debug RPC face began minting padded / blank composite keys.
    model_body = _shared_body(pwr, "normalizeCustomModelId")
    if model_body is None:
        detail["fail_closed"].append(
            "normalizeCustomModelId body not extractable from ProviderWriteRules — re-anchor W11")
    else:
        effective = effective_value(code_view(model_body))
        if not TRIM_RE.search(effective):
            detail["w11"].append(
                "normalizeCustomModelId returns a value without whitespace "
                "normalization (.trim()): %r (body: %r)"
                % (effective.strip()[:90], model_body.strip()[:90]))
        if not COLLAPSE_RE.search(effective):
            detail["w11"].append(
                "normalizeCustomModelId returns a value without the blank->null "
                "collapse: %r (body: %r)"
                % (effective.strip()[:90], model_body.strip()[:90]))

    # ---- W12 [batch6 A09-6] the write policy still decides -----------------
    # `isCustomBaseURLWriteAllowed` is the single decision every face routes
    # through. A witness replaced its body with `= true`: the head, the name and
    # the call sites all stay, and the openRouter phantom write is back.
    policy = _shared_body(pwr, "isCustomBaseURLWriteAllowed")
    params = _decl_params(pwr, "isCustomBaseURLWriteAllowed")
    if policy is None:
        detail["fail_closed"].append(
            "isCustomBaseURLWriteAllowed body not extractable from ProviderWriteRules "
            "— re-anchor W12")
    else:
        eff = effective_value(code_view(policy))
        folded = const_bool(strip_parens(eff)) if eff else None
        if not params:
            detail["w12"].append(
                "isCustomBaseURLWriteAllowed declaration has no readable parameter list "
                "— re-anchor W12")
        for name in params:
            if not re.search(r"(?<![\w.])%s(?![\w])" % re.escape(name), eff):
                detail["w12"].append(
                    "isCustomBaseURLWriteAllowed never reads its `%s` argument (`%s`) — "
                    "the decision does not depend on it" % (name, eff.strip()[:70]))
        if folded is not None:
            detail["w12"].append(
                "isCustomBaseURLWriteAllowed is a constant (`%s`) — every write is "
                "allowed or denied regardless of the provider and the URL"
                % eff.strip()[:70])
        else:
            if "supportsCustomBaseURL" not in eff:
                detail["w12"].append(
                    "isCustomBaseURLWriteAllowed no longer consults "
                    "providerType.supportsCustomBaseURL — the openRouter phantom write "
                    "is allowed again")
            if not re.search(r"isNullOr(?:Blank|Empty)\(|isBlank\(\)|isEmpty\(\)", eff):
                detail["w12"].append(
                    "isCustomBaseURLWriteAllowed lost the blank-is-a-clear arm — a "
                    "stale value can no longer be cleared from any face")

    bad = any(detail[k] for k in detail if k != "fail_closed") or detail["fail_closed"]
    return (1 if bad else 0), detail


def effective_base_span(code):
    """(start, end) of the `effectiveBaseURL` property getter. A property getter
    is not a `fun`, so `function_body` cannot find it; the closing brace comes
    from the shared bracket matcher (no hand-rolled depth counter, which a
    string literal in the getter used to close early)."""
    i = code.find("val effectiveBaseURL")
    if i < 0:
        return None
    j = code.find("get() {", i)
    if j < 0:
        return None
    k = code.index("{", j)
    close = match_bracket(code, k)
    if close < 0:
        return None
    return (i, close + 1)


def effective_base_getter(code):
    """Body of the `effectiveBaseURL` property getter (see effective_base_span)."""
    span = effective_base_span(code)
    return None if span is None else code[span[0]:span[1]]


def replace_in(rel, old, new):
    """Selftest fixture builder: replace `old` with `new` in file `rel`.
    fail-closed — a missing anchor aborts the whole selftest rather than
    silently producing a fixture that mutates nothing."""
    def fn(r, text):
        if r != rel:
            return text
        assert old in text, "fixture anchor missing: %r" % old[:60]
        return text.replace(old, new)
    return fn


def compose(*fns):
    def fn(r, text):
        for f in fns:
            text = f(r, text)
        return text
    return fn


def run_selftest():
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.abspath(os.path.join(here, "..", ".."))
    real = {}
    for rel in (PWR_REL, PC_REL, PM_REL, PCF_REL):
        code = read(os.path.join(repo, rel))
        if code is None:
            print("SELFTEST cannot load %s" % rel)
            return 1
        real[rel] = code

    results = []

    def run(name, mutate=None, expect=()):
        root = tempfile.mkdtemp(prefix="pwrselftest-")
        try:
            for rel, text in real.items():
                dest = os.path.join(root, rel)
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with open(dest, "w", encoding="utf-8") as f:
                    f.write(mutate(rel, text) if mutate else text)
            rc, detail = check_tree(root)
            got = tuple(sorted(k for k in detail if detail[k]))
            exp = tuple(sorted(expect))
            ok = (rc == 1 if exp else rc == 0) and got == exp
            results.append((name, ok, rc, got, exp))
        finally:
            shutil.rmtree(root)

    run("S0-clean-tree-green")

    def dup_decl(rel, text):
        if rel == PC_REL:
            return text + ("\n\ninternal const val CUSTOM_BASE_URL_OPENROUTER_MESSAGE: String =\n"
                           '    "customBaseURL is not supported for providerType=openRouter"\n')
        return text

    run("S1-duplicate-constant-declaration-W2", dup_decl, ("w2",))

    def literal_copy(rel, text):
        if rel == PM_REL:
            return text.replace(
                "throw RPCException(-32602, CUSTOM_BASE_URL_OPENROUTER_MESSAGE)",
                'throw RPCException(-32602, "customBaseURL is not supported for providerType=openRouter")')
        return text

    run("S2-message-literal-copy-back-W4", literal_copy, ("w4", "w5"))

    def ua_literal_copy(rel, text):
        if rel == PM_REL:
            return text.replace(
                "throw RPCException(-32602, CUSTOM_USER_AGENT_OAUTH_MESSAGE)",
                'throw RPCException(-32602, "Custom User-Agent is only supported for third-party '
                'API-key providers. OAuth providers (Anthropic/Codex) use their own authentication '
                'UA and cannot be overridden.")')
        return text

    run("S3-ua-message-literal-copy-back-W4", ua_literal_copy, ("w4",))

    def writer_inline(rel, text):
        if rel == PC_REL:
            return text.replace(
                """                if (!isCustomBaseURLWriteAllowed(inst.providerType, newBase)) {""",
                """                if (!inst.providerType.supportsCustomBaseURL) {""")
        return text

    run("S4-writer-inline-predicate-W7", writer_inline, ("w7",))

    def models_add_revert(rel, text):
        if rel == PM_REL:
            return text.replace(
                """        val modelId = normalizeCustomModelId(params.optString("modelId", ""))
            ?: throw RPCException(-32602, "Missing 'modelId' param")""",
                """        val modelId = params.optString("modelId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'modelId' param")
        }""").replace(
                "provider = canonicalModelProviderName(instance.providerType),",
                "provider = instance.providerType.displayName,")
        return text

    run("S5-modelsAdd-old-shape-W6", models_add_revert, ("w6",))

    def helper_removed(rel, text):
        if rel == PWR_REL:
            return text.replace("internal fun canonicalModelProviderName(", "internal fun unusedHelper(")
        return text

    run("S6-shared-rule-missing-W1", helper_removed, ("w1",))

    # ─── [deep-audit-1039 A-04] W8/W9 fixtures ─────────────────────────────
    # Every fixture is a "breaks exactly this criterion" mutation of the REAL
    # tree files, so each new criterion has a tooth of its own.

    def run_real(name, mutate_map, expect):
        root = tempfile.mkdtemp(prefix="pwrselftest-w8-")
        try:
            for rel, text in real.items():
                dest = os.path.join(root, rel)
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                mutated = mutate_map(rel, text) if callable(mutate_map) else (
                    mutate_map[rel] if rel in mutate_map else text)
                with open(dest, "w", encoding="utf-8") as f:
                    f.write(mutated)
            rc, detail = check_tree(root)
            got = tuple(sorted(k for k in detail if detail[k]))
            exp = tuple(sorted(expect))
            ok = (rc == 1 if exp else rc == 0) and got == exp
            results.append((name, ok, rc, got, exp))
        finally:
            shutil.rmtree(root)

    # S7: the shared helper disappears (declaration renamed) — W1 AND W8 red
    # (W1 pins the declaration list, W8 pins the same declaration by name).
    def helper_gone2(rel, text):
        if rel == PWR_REL:
            return text.replace(
                "internal fun normalizeCustomBaseURL(raw: String?): String?",
                "internal fun retiredNormalize(raw: String?): String?")
        return text

    # NOTE: W1 pins the four ORIGINAL shared-rule declarations and W8 pins the
    # new helper by name — both read the same file, but W1's list does not
    # include the helper, so removing it reds W8 only. That asymmetry is
    # deliberate: W1's list is the ARCH-01 baseline (frozen so an unrelated
    # rename cannot silently satisfy it), W8 owns the new criterion.
    # W10 also reads the body, so a renamed helper leaves W10's body
    # unextractable — fail-closed, alongside W8's declaration miss.
    run_real("S7-normalize-helper-missing-W8", helper_gone2, ("w8", "fail_closed"))

    # S8: the collection writer reverts to its own inline blank handling — W8.
    run_real("S8-writer-inline-blank-W8", replace_in(
        PC_REL,
        "                val newBase = normalizeCustomBaseURL(s)",
        "                val newBase = s.ifEmpty { null }"), ("w8",))

    # S9: the add path reverts to `takeIf { it.isNotBlank() }` — W8.
    run_real("S9-add-path-inline-blank-W8", replace_in(
        PC_REL,
        "    val base = normalizeCustomBaseURL(raw) ?: return null",
        "    val base = raw?.takeIf { it.isNotBlank() } ?: return null"), ("w8",))

    # S10: the create face reverts to `ifEmpty { null }` — W8.
    run_real("S10-create-face-inline-blank-W8", replace_in(
        PM_REL,
        '        val customBaseURL = normalizeCustomBaseURL(params.optString("customBaseURL", ""))',
        '        val customBaseURL = params.optString("customBaseURL", "").ifEmpty { null }'), ("w8",))

    # S11: the update face reverts (guard + copy arm) — W8.
    def update_face_revert(rel, text):
        if rel != PM_REL:
            return text
        return compose(
            replace_in(
                PM_REL,
                '            val newBase = normalizeCustomBaseURL(params.optString("customBaseURL", ""))',
                '            val newBase = params.optString("customBaseURL", "").ifEmpty { null }'),
            replace_in(
                PM_REL,
                '                else normalizeCustomBaseURL(params.optString("customBaseURL", ""))',
                '                else params.optString("customBaseURL", "").ifEmpty { null }'),
        )(rel, text)

    run_real("S11-update-face-inline-blank-W8", update_face_revert, ("w8",))

    # S12: a SECOND normalization helper appears in another file — W8 (single decl).
    def second_helper(rel, text):
        if rel == PM_REL:
            return text + ("\n\ninternal fun normalizeCustomBaseURL(raw: String?): String? = raw\n")
        return text

    run_real("S12-second-normalize-helper-W8", second_helper, ("w8",))

    # S13: the read side stops trimming — W9.
    run_real("S13-getter-drops-trim-W9", replace_in(
        PCF_REL,
        "            val base = customBaseURL?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null",
        "            val base = customBaseURL?.trimEnd('/') ?: return null"), ("w9",))

    # S14: the read side keeps the trim but drops the trailing-slash collapse
    # — W9 (a different tooth of the same criterion).
    run_real("S14-getter-drops-slash-collapse-W9", replace_in(
        PCF_REL,
        "            val base = customBaseURL?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null",
        "            val base = customBaseURL?.trim()?.takeIf { it.isNotEmpty() } ?: return null"), ("w9",))

    # ─── [meta-audit-1024 anchor] W10 — the normalization BODY ─────────────
    # S16 is the external witness itself: declaration and every call site stay
    # put, only `.trim()` leaves the body. Before W10 this shape was GREEN while
    # padded / blank URLs were persisted again by every write face.

    # S16: witness — the helper body loses its whitespace normalization. W10
    # only (W8's declaration + call sites are untouched, as in the witness).
    run_real("S16-witness-body-drops-trim-W10", replace_in(
        PWR_REL,
        "internal fun normalizeCustomBaseURL(raw: String?): String? =\n"
        "    raw?.trim()?.takeIf { it.isNotEmpty() }",
        "internal fun normalizeCustomBaseURL(raw: String?): String? =\n"
        "    raw?.takeIf { it.isNotEmpty() }"), ("w10",))

    # S17: the other half of the same criterion — the collapse goes away, so a
    # padded-but-blank string survives instead of becoming a clear.
    run_real("S17-body-drops-collapse-W10", replace_in(
        PWR_REL,
        "internal fun normalizeCustomBaseURL(raw: String?): String? =\n"
        "    raw?.trim()?.takeIf { it.isNotEmpty() }",
        "internal fun normalizeCustomBaseURL(raw: String?): String? =\n"
        "    raw?.trim()"), ("w10",))

    # S18: the body is emptied out entirely (a no-op helper that returns its
    # input) — W10 reds, and the faces still call it so W8 stays green: the
    # "declaration + call site" anchor proves nothing about behaviour.
    run_real("S18-body-emptied-W10", replace_in(
        PWR_REL,
        "internal fun normalizeCustomBaseURL(raw: String?): String? =\n"
        "    raw?.trim()?.takeIf { it.isNotEmpty() }",
        "internal fun normalizeCustomBaseURL(raw: String?): String? =\n"
        "    raw"), ("w10",))

    # S19: precision — the accepted shape (the real tree, both halves present)
    # must stay GREEN: W10 fires only on a missing half, never on the accepted
    # spelling. Same content as S15, deliberately re-run under the W10 name so a
    # future edit of either criterion has its own green arm.
    run_real("S19-fixed-shape-green-W10", lambda rel, text: text, ())

    # S15: contrast — a fixture where the helper is consumed on every face and
    # the getter keeps both guards must stay GREEN (no criterion fires on the
    # accepted shape).
    run_real("S15-fixed-shape-green", lambda rel, text: text, ())

    # ─── [batch6 A09] the six witnesses of the sixth wave ──────────────────
    # Each one keeps the rule's SPELLING (the call, the guard, the chain) and
    # moves the VALUE, so each is固化 here as its own arm: red on the mutation,
    # and the untouched tree still green (S15/S19).

    # A09-1: `.also { normalize… }` — the chain runs as a discarded scope and
    # the expression returns the RAW input. W10 must judge the value, not the
    # tokens the body spells.
    run_real("S20-witness-also-discards-normalization-W10", replace_in(
        PWR_REL,
        "internal fun normalizeCustomBaseURL(raw: String?): String? =\n"
        "    raw?.trim()?.takeIf { it.isNotEmpty() }",
        "internal fun normalizeCustomBaseURL(raw: String?): String? =\n"
        "    raw.also { raw?.trim()?.takeIf { s -> s.isNotEmpty() } }"), ("w10",))

    # A09-1 sister shape: the same trick with `apply`, which peels the same way.
    run_real("S20b-witness-apply-discards-normalization-W10", replace_in(
        PWR_REL,
        "internal fun normalizeCustomBaseURL(raw: String?): String? =\n"
        "    raw?.trim()?.takeIf { it.isNotEmpty() }",
        "internal fun normalizeCustomBaseURL(raw: String?): String? =\n"
        "    raw.apply { raw?.trim()?.takeIf { it?.isNotEmpty() } }"), ("w10",))

    # A09-2: the helper is still CALLED, its result is dropped on an unused
    # local, and `newBase` (the raw RPC string) is what gets persisted.
    run_real("S21-witness-normalize-result-discarded-W8", compose(
        replace_in(
            PC_REL,
            "                val newBase = normalizeCustomBaseURL(s)",
            "                val _ignoredNorm = normalizeCustomBaseURL(s)\n"
            "                val newBase = s"),
    ), ("w8",))

    # A09-3: the blank handling is spelled across THREE lines, so the old
    # two-line window never saw the anchor and the pattern on one line.
    run_real("S22-witness-three-line-inline-blank-W8", replace_in(
        PM_REL,
        '                else normalizeCustomBaseURL(params.optString("customBaseURL", ""))',
        '                else params.optString(\n'
        '                    "customBaseURL", ""\n'
        '                ).ifEmpty { null }'), ("w8",))

    # A09-4: the whole read-side chain survives on a DEAD local; the value the
    # getter returns is the untrimmed field.
    run_real("S23-witness-chain-on-dead-local-W9", replace_in(
        PCF_REL,
        "            val base = customBaseURL?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }"
        " ?: return null\n"
        '            return if (appendV1Suffix && !base.endsWith("/v1")) "$base/v1" else base',
        "            val _unused = customBaseURL?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }\n"
        "            val raw = customBaseURL ?: return null\n"
        '            return if (appendV1Suffix && !raw.endsWith("/v1")) "$raw/v1" else raw'),
        ("w9",))

    # A09-5: the SIBLING helper in the same file (normalizeCustomModelId) is
    # emptied — the family hole: W10 was written for one function only.
    run_real("S24-witness-sibling-modelid-body-emptied-W11", replace_in(
        PWR_REL,
        "internal fun normalizeCustomModelId(raw: String?): String? =\n"
        "    raw?.trim()?.takeIf { it.isNotEmpty() }",
        "internal fun normalizeCustomModelId(raw: String?): String? =\n"
        "    raw"), ("w11",))

    # A09-6: the shared WRITE POLICY becomes `= true` — declaration, call sites
    # and every face stay, the decision is gone (openRouter phantom write back).
    run_real("S25-witness-policy-body-constant-true-W12", replace_in(
        PWR_REL,
        "): Boolean = customBaseURL.isNullOrBlank() || providerType.supportsCustomBaseURL",
        "): Boolean = true"), ("w12",))

    # A09-6 sisters: the policy folded to a constant through an OR, and the
    # policy that lost only the capability arm (blank-clear kept).
    run_real("S25b-witness-policy-constant-by-or-W12", replace_in(
        PWR_REL,
        "): Boolean = customBaseURL.isNullOrBlank() || providerType.supportsCustomBaseURL",
        "): Boolean = customBaseURL.isNullOrBlank() || true"), ("w12",))

    run_real("S25c-witness-policy-drops-capability-arm-W12", replace_in(
        PWR_REL,
        "): Boolean = customBaseURL.isNullOrBlank() || providerType.supportsCustomBaseURL",
        "): Boolean = customBaseURL.isNullOrBlank()"), ("w12",))

    # Precision: a policy that ignores only ONE argument (keeps the shape,
    # drops the provider check) must still red — and the real tree must not.
    run_real("S25d-witness-policy-ignores-providerType-W12", replace_in(
        PWR_REL,
        "): Boolean = customBaseURL.isNullOrBlank() || providerType.supportsCustomBaseURL",
        "): Boolean = customBaseURL.isNullOrBlank() || customBaseURL?.length != 0"), ("w12",))

    # ── [R3-t20] the three witnesses this revision was handed ─────────────
    # S26 (w39): the `/v1` suffix polarity is inverted; the trim chain, the
    # return shape and every W8/W9/W10 anchor stay intact.
    run_real("S26-getter-inverted-append-v1-suffix-W13", replace_in(
        PCF_REL, 'if (appendV1Suffix && !base.endsWith("/v1"))',
        'if (!appendV1Suffix && !base.endsWith("/v1"))'), ("w13",))

    # S27 (w40): a capability getter folds to a constant — the UI surface it
    # drives vanishes for every instance.
    run_real("S27-thinking-rules-getter-constant-W14", replace_in(
        PCF_REL,
        """    val supportsCustomThinkingRules: Boolean
        get() = when (providerType) {
            ProviderType.anthropic, ProviderType.gemini -> false
            else -> {
                val codexOAuth = credentialType == ProviderCredential.oauth &&
                    customBaseURL.isNullOrBlank()
                !useResponsesAPI && !codexOAuth
            }
        }
""",
        """    val supportsCustomThinkingRules: Boolean
        get() = false
"""), ("w14",))

    # S28 (w41): the minis-config UA writer loses its guard; W5's debug faces
    # keep theirs, so W5 alone cannot see this.
    run_real("S28-ua-writer-drops-guard-W15", replace_in(
        PC_REL,
        """                val inst = repo.instance(id) ?: throw ConfigError.InvalidValue("Provider not found")
                if (!customUserAgentWritable(inst.credentialType)) {
                    throw ConfigError.InvalidValue(CUSTOM_USER_AGENT_OAUTH_MESSAGE)
                }
                mutate(id) { it.copy(customUserAgent = s.ifEmpty { null }) }
""",
        """                val inst = repo.instance(id) ?: throw ConfigError.InvalidValue("Provider not found")
                mutate(id) { it.copy(customUserAgent = s.ifEmpty { null }) }
"""), ("w15",))

    # S29 (w41'): the guard survives but no longer terminates — spelling without
    # teeth (a `Unit` body still "calls customUserAgentWritable(").
    run_real("S29-ua-writer-guard-no-teeth-W15", replace_in(
        PC_REL,
        """                if (!customUserAgentWritable(inst.credentialType)) {
                    throw ConfigError.InvalidValue(CUSTOM_USER_AGENT_OAUTH_MESSAGE)
                }
""",
        """                if (!customUserAgentWritable(inst.credentialType)) {
                    Unit
                }
"""), ("w15",))

    all_ok = True
    for name, ok, rc, got, exp in results:
        print("%s %s (rc=%d got=%s exp=%s)" % ("PASS" if ok else "FAIL", name, rc, got, exp))
        if not ok:
            all_ok = False
    if all_ok:
        print(SELFTEST_MARKER)
        print("PROVIDER_WRITE_RULES_SELFTEST_ARMS=%d" % len(results))
        return 0
    return 1


def main():
    if len(sys.argv) >= 2 and sys.argv[1] == "--selftest":
        return run_selftest()
    if len(sys.argv) < 2:
        print("usage: provider_write_rules_check.py <repo-root> | --selftest")
        return 2
    root = sys.argv[1]
    rc, detail = check_tree(root)
    if rc == 0:
        print("provider_write_rules: ALL GREEN (W1 shared rules, W2/W3 single declaration, "
              "W4 no literal copies, W5 debug wiring, W6 modelsAdd, W7 writer wiring, "
              "W8 normalization single-sourced on every write face, W9 read side trims, "
              "W10 normalization body still trims and collapses, "
              "W13 appendV1Suffix polarity, W14 capability getters decide, "
              "W15 minis-config UA writer guarded)")
        return 0
    print("provider_write_rules: FAIL")
    print(json.dumps(detail, indent=2))
    return 1


if __name__ == "__main__":
    sys.exit(main())
