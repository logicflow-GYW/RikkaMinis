#!/usr/bin/env python3
"""Cross-process boundary completeness guard.

Background: models are handed to the `:modelservice` worker over an IPC boundary.
The dispatcher serialises a model by writing named keys into a bundle; the worker
reconstructs the model by reading those same keys. Adding a field to the model
without adding it to BOTH sides means the worker silently falls back to the
field's default value -- no exception, no log, just wrong behaviour.

This is a repeat offender. Live example (2026-09-22, P0): `LLMModel.maxOutputTokens`
was never written by the dispatcher, so the worker fell back to its 16384 default
and clamped 96.3% of real requests to a smaller reply budget than the user chose.

Note the sibling guard `four_way_sync_check.py` covers Model<->Entity<->snapshot
for ProviderInstance/ModelGroup only -- it does NOT cover this IPC hop, and it does
not cover LLMModel at all. Hence this gate.

Judgement:
  * every field of a boundary model must appear as a dispatcher put() key
    (or be explicitly listed as BENIGN with a reason)
  * every dispatcher key must be consumed on the worker side

## Judgement, second wave (2026-10-25, wide3/10) — "presence" is not "effect"

Four shapes used to pass while re-introducing the P0. Each is now a criterion,
and each has a negative arm in `--selftest`:

  1. `if (false) model.maxOutputTokens?.let { put("max_output_tokens", it) }`
     kept the literal, killed the write. Keys are now collected from **live**
     call sites only (`dead_guarded()`: constant-false condition on the same
     statement, or in an enclosing `{` block).
  2. `put("max_output_tokens", 0)` still spelled the key. A live site must now
     also be **derived**: the value expression is not a bare literal, and the
     field name appears in the expression that produces it (`model.x`, or `it`
     bound by `model.x?.let/let`). Otherwise the write carries no user value.
  3. A `)` inside a comment (`// close paren in comment: )`) truncated the
     constructor list, so every field after it dropped out of the scan. The
     comment is now blanked **before** the paren walk (same length, same
     offsets), and an empty/unparseable constructor list is a violation rather
     than an empty field list ("parser death" must fail closed, never pass).
  4. `maxOutputTokens = null` plus a comment mentioning the key passed the
     reverse check, because it searched the raw worker text. The worker source is
     now comment-blanked and the key must appear in a **read call**
     (`get*`/`opt*`/`has`/`is*`), and — when the worker constructs the model —
     **every** construction site must carry the key (the worker reconstructs
     LLMModel twice: streaming and non-streaming; one silently-degraded path is
     the defect).

## Judgement, third wave (2026-10-26, round 3 "dual-gate bypass")

Round 3 attacked the seams BETWEEN gates: one edit, every gate green, defect
live inside the gap. Four of those edits landed in this gate's file set. Each is
now a criterion of its own (`--selftest` arm named `r3-…`):

  5. A *derived projection* on a boundary model was pinned to a constant
     (`val supportsAzureMode: Boolean get() = false`). This gate only enumerated
     the constructor parameters, the write-face gate only pinned
     `effectiveBaseURL`, and the projection sat in between: every consumer (the
     UI affordance, the write face that would let `azureMode` become true, the
     worker's read of `azure_mode`) now sees a fixed answer, so a field that IS
     carried across the boundary can never take a non-default value. Every
     Boolean projection declared on a boundary model is now constant-folded; a
     projection that folds to one value for all inputs is a violation.
  6. The worker READ the key and then threw the value away
     (`req.getInt("max_output_tokens").takeIf { false }`). The sender side has
     judged `_chain_kills` since wide4; the receiver only asked whether a read
     call existed. The same kill analysis now runs on the worker's read.
  7. One of the two worker entry points stopped restoring the session state that
     travels in the same request (`executeRun` dropped
     `restoreThinkingRules(req)` while the streaming entry kept it), so the
     global "this key is read somewhere" set stayed non-empty. Restore helpers
     are now derived from the tree (a worker function taking one
     `JSONObject`) and **every** entry point that rebuilds a boundary model must
     call every one of them — the same per-site rule the constructions already
     have, applied to the session-level half of the request.
  8. A key read gated a block, and the block then assigned the *neutral*
     constant (`if (req.optBoolean("enhanced_cache", false)) { …enhancedCache =
     false }`). The read call and the assignment were both there; the feature was
     off. A guard on a key's truth value may not be followed by a constant
     assignment that contradicts it — that block is a no-op.
"""
import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_lexer import analyse  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool,
    const_value,
    enclosing_conditions,
    function_body,
    if_blocks,
    match_bracket,
    split_top,
    stmt_spans,
    tail_expr,
    when_branches,
)
from _kotlin_valueflow import expr_end  # noqa: E402

REPO = sys.argv[1] if len(sys.argv) > 1 else "."

# Models that cross the :modelservice IPC boundary.
BOUNDARY_MODELS = [
    {
        "name": "LLMModel",
        "model_path": "src/android/app/src/main/java/com/rikkaminis/app/data/model/LLMModel.kt",
    },
    {
        "name": "ProviderInstance",
        "model_path": "src/android/app/src/main/java/com/rikkaminis/app/data/model/ProviderConfig.kt",
    },
]

# Fields whose dispatcher key is NOT the plain snake_case of the field name.
# (Only needed for acronyms / renamed keys; everything else falls back to
# snake_case so that a NEWLY added field is still caught automatically.)
KEY_ALIASES = {
    "ProviderInstance": {
        "id": "instance_id",
        "label": "instance_label",
        "customBaseURL": "base_url",
        "appendV1Suffix": "append_v1",
        "customUserAgent": "user_agent",
        "useResponsesAPI": "use_responses_api",
        "imageEndpointMode": "image_endpoint_mode",
        "imageEndpointResolved": "image_endpoint_resolved",
        "azureMode": "azure_mode",
    },
    "LLMModel": {
        "id": "model_id",
        "displayName": "model_display_name",
        "provider": "model_provider",
    },
}

DISPATCHER = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelExecutionDispatcher.kt"
WORKER = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelExecutionService.kt"

# Fields deliberately NOT carried across the boundary.
# Each entry MUST have a reason -- an unexplained entry is how the next P0 hides.
BENIGN = {
    "ProviderInstance": {
        "credentials": (
            "credential METADATA never crosses the boundary: the worker reads "
            "its secret from EncryptedPrefs by slot name, so the labels/notes "
            "stored here are host-side presentation data only. The multi-key "
            "feature that wrote this field was removed; the field survives "
            "only because its column is part of schema version 11."
        ),
        "isEnabled": "worker never reads it; gating happens on the host side",
        "createdAt": "presentation-only ordering metadata",
        "pinned": "worker hard-codes pinned=false; pinning is a host-side UI concept",
    },
}

SELFTEST_MARKER = "XPROC-BOUNDARY SELFTEST=PASS"


def read(path):
    full = os.path.join(REPO, path)
    try:
        return open(full, encoding="utf-8", errors="replace").read()
    except OSError:
        return None


# ── lexical helpers ───────────────────────────────────────────────────────────
# Both views come from the ONE shared Kotlin lexer (_kotlin_lexer.analyse):
#   views['comments'] → comments blanked, string literals intact (keys live there)
#   views['code']     → comments AND literal contents blanked (structure: parens,
#                       braces and commas inside a literal must not count)
# They are strictly the same length as the input, so every offset (and every
# line number) stays valid.
#
# [wide4 / `blank`] This gate used to carry its own lexer. It mishandled
# `${...}` templates inside a string (a `"` inside the interpolation ended the
# string early), so `val contextWindow: Int? = "${")"}".toIntOrNull()` put a
# REAL `)` into the structural view, truncated `data class LLMModel(...)` after
# four fields, and every field declared below it silently dropped out of the
# boundary check. Lexing is now delegated, and an unparseable source is a
# VIOLATION — never an empty field list that reads as clean.
def views_of(text):
    """(comments_view, code_view, error)."""
    ok, reason, views = analyse(text)
    if not ok:
        return None, None, reason
    return views['comments'], views['code'], None


def when_arm_dead(subject, cond):
    """`when (false) { true -> … }` / `when (1) { 2 -> … }`: an arm that can
    never be selected because subject and arm are unequal constants."""
    cond = cond.strip()
    if not cond or cond.startswith("else"):
        return False
    subj = const_value(subject) if subject.strip() else None
    if subj is None:
        return False
    for arm in cond.split(","):
        v = const_value(arm)
        if v is None or v == subj:
            return False
    return True


DEAD_RE = re.compile(
    r"(?:\bif\s*\(\s*|\bwhile\s*\(\s*)"
    r"(?:false\b|!\s*true\b|0\s*==\s*1\b|true\s*\|\||false\s*&&)"
    r"|\bfalse\s*&&|\btrue\s*\|\|"
)


def stmt_start(code, off):
    """Offset just after the last statement boundary before `off`.

    A newline at bracket depth 0 is a boundary: Kotlin rarely uses semicolons,
    so without it the scan walks back into the previous statement and a guard
    from a different statement would be attributed to this one.
    """
    depth = 0
    i = off - 1
    while i >= 0:
        c = code[i]
        if c in ")]":
            depth += 1
        elif c in "([":
            if depth == 0:
                break
            depth -= 1
        elif depth == 0 and c in "\n;{}":
            return i + 1
        i -= 1
    return i + 1


def dead_guarded(code, off):
    """True when a constant-FALSE condition governs `off`.

    [wide4 / dead_guarded] The regex this replaced enumerated spellings
    (`if (false`, `!true`, `0 == 1`, `false &&`, `true ||`) and therefore
    missed the whole `when` family — `when (false) { true -> … else -> Unit }`
    keeps every token of the write while deleting the decision, and the P0
    (`max_output_tokens` never written) came straight back. Liveness is now
    decided by constant folding over the conditions that actually enclose the
    offset: `if` headers (braced and braceless), enclosing `{ }` blocks and
    `when` arms whose subject/arm pair can never match. Unknown conditions fold
    to None, which is "not dead" — the criterion only fires on proof.
    """
    if DEAD_RE.search(code[stmt_start(code, off):off]):
        return True
    for kind, cond in enclosing_conditions(code, off):
        if kind == "if" and const_bool(cond) is False:
            return True
        if kind == "when" and when_arm_dead("", cond):
            return True
    for subject, cond, b_start, b_end in when_branches(code):
        if b_start <= off < b_end and when_arm_dead(subject, cond):
            return True
    return False


def paren_end(code, open_idx):
    """Index of the `)` matching code[open_idx] == '(' (or None)."""
    depth = 0
    for j in range(open_idx, len(code)):
        c = code[j]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return j
    return None


def split_first_arg(argtext):
    depth = 0
    for i, c in enumerate(argtext):
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "," and depth == 0:
            return argtext[:i], argtext[i + 1:]
    return argtext, ""


def extract_data_class_fields(src, cls_name):
    """Return the ctor parameter names of `data class <cls_name>(...)`."""
    _ks, st, lex_err = views_of(src)
    if st is None:
        return None, "cannot lex %s (%s) — refusing to enumerate a field list " \
                     "it cannot read" % (cls_name, lex_err)
    m = re.search(r"data\s+class\s+" + re.escape(cls_name) + r"\s*\(", st)
    if not m:
        return None, "data class not found: " + cls_name
    i = m.end() - 1
    j = paren_end(st, i)
    if j is None:
        return None, "unbalanced parentheses in " + cls_name
    # The comment is blanked BEFORE the walk above (both views are same-length,
    # so a `)` inside a comment can no longer truncate the parameter list).
    # Top-level params only: split on commas at depth 0 (structural view, so a
    # comma inside a default string literal does not split the declaration, and
    # a comment between two params cannot merge them into one segment).
    body = st[i + 1:j]
    fields, depth, cur = [], 0, ""
    for ch in body:
        if ch in "(<[":
            depth += 1
        elif ch in ")>]":
            depth -= 1
        if ch == "," and depth == 0:
            fields.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        fields.append(cur)
    out = []
    for f in fields:
        f = f.strip()
        if not f:
            continue
        nm = re.match(r"(?:@\w+(?:\([^)]*\))?\s+)*(?:val|var)\s+(\w+)", f)
        if nm:
            out.append(nm.group(1))
    if not out:
        return None, "no constructor parameters parsed in " + cls_name
    return out, None


PUT_RE = re.compile(
    r'(?:\.|\b)put(?:String|Int|Long|Boolean|Float|Double|StringArrayList)?\s*\(\s*"([^"]+)"')
LITERAL_RE = re.compile(
    r'^(?:-?\d[\d_]*[lLfFdD]?|0x[0-9a-fA-F]+|true|false|null|"([^"\\]|\\.)*"|\'([^\'\\]|\\.)\')$')


SCOPE_HEAD_RE = re.compile(
    r"\??\s*\.\s*(?:let|also|run|apply|with|takeIf|takeUnless)\s*"
    r"(?:\([^()]*\))?\s*$")


def producing_start(code, off):
    """Start of the expression that PRODUCES the value written at `off`.

    The statement boundary is not enough: in `model.maxOutputTokens?.let { put(
    "max_output_tokens", it) }` the statement starts at `put`, so the chain that
    decides the value lives in the enclosing lambda header. Walk out through
    every enclosing scope-function lambda (`let`/`also`/`takeIf`/…) until the
    enclosing construct is not a scope call, then take that statement boundary.
    Without this the derivation check sees only `put(..., it)` (wide4).
    """
    depth = 0
    i = off - 1
    while i >= 0:
        c = code[i]
        if c in ")]}":
            depth += 1
        elif c in "([{":
            if depth == 0:
                if c == "{":
                    m = SCOPE_HEAD_RE.search(code[:i])
                    if m:
                        # Resume just BEFORE the scope head, swallowing the
                        # indentation/newline that may separate chain segments
                        # (`x?.takeIf { … }\n  ?.let { put(…) }`): otherwise the
                        # walk stops at that newline and the chain header above
                        # it is cut out of the window.
                        i = m.start() - 1
                        while i >= 0 and code[i] in " \t\r\n":
                            i -= 1
                        continue
                return i + 1
            depth -= 1
        elif depth == 0 and c in "\n;":
            return i + 1
        i -= 1
    return 0


def put_sites(ks, st):
    """Every named put() call, with its value expression and liveness.

    `ks` keeps string literals (keys live there); `st` blanks them (paren
    matching must not be fooled by a literal). Both share offsets.

    Each site also carries `producer`: the statement that produces the value,
    from its statement boundary up to the end of the value expression. That is
    where a chain like `model.maxOutputTokens?.takeIf { false }?.let { … }`
    hides its decision, so the derivation check needs it (wide4 / derives_field).
    """
    out = []
    for m in PUT_RE.finditer(ks):
        op = ks.find("(", m.start())
        end = paren_end(st, op)
        if end is None:
            continue  # unbalanced call: cannot judge it; other arms still run
        args = st[op + 1:end]
        parts = split_top(args, (",",))
        if len(parts) < 2:
            continue
        vs, ve = parts[1]
        val = ks[op + 1 + vs:op + 1 + ve]
        out.append({
            "key": m.group(1),
            "value": val.strip(),
            "off": m.start(),
            "end": end,
            "producer": st[producing_start(st, m.start()):op + 1 + ve],
            "dead": dead_guarded(st, m.start()),
        })
    return out


SCOPE_CHAIN_RE = re.compile(
    r"\.\s*(let|map|mapNotNull|takeIf|takeUnless|filter|filterNot)\s*"
    r"(?:<[^<>{}]*>)?\s*\{")


def _chain_kills(text):
    """Why this producing expression can never carry the field value, or None.

    [wide4 / derives_field] "the field name appears within 320 characters of
    the put" is a presence test. `model.maxOutputTokens?.takeIf { false }?.let
    { put("max_output_tokens", it) }` contains the field name, the key, a
    non-literal value and a live call — and `takeIf { false }` turns the value
    into null, so the key is never written at all. Any constant-false filter in
    the chain, and any scope function whose lambda body folds to a constant,
    destroys the value; both are now judged by folding, not by spelling.
    """
    for m in SCOPE_CHAIN_RE.finditer(text):
        ob = m.end() - 1
        cb = match_bracket(text, ob)
        if cb < 0:
            # The producer window ends inside the lambda that CONTAINS the put,
            # so its closing brace is not in the window. That is a truncation
            # artifact, not a defect — the earlier, decision-carrying calls in
            # the chain (takeIf/…) are balanced and are still judged.
            continue
        body = text[ob + 1:cb].strip()
        kind = m.group(1)
        cv = const_value(body)
        if kind == "takeIf":
            if cv == ("bool", False):
                return "`takeIf { false }` — the value is always null, the key is never written"
        elif kind == "takeUnless":
            if cv == ("bool", True):
                return "`takeUnless { true }` — the value is always null"
        elif kind in ("filter", "filterNot"):
            if cv is not None:
                return "`%s { %s }` — the collection is filtered away" % (kind, body)
        elif cv is not None:
            return "`%s { %s }` — the lambda returns a constant, not the field" % (kind, body)
    return None


def derives_field(site, field, ks, st=None):
    """Does this live put() carry a value derived from `field`?

    `put("k", 0)` and `put("k", "literal")` spell the key but carry no user
    value; `model.maxOutputTokens?.let { put("k", it) }` derives through the
    `it` bound by the receiver expression. Two tests, in order:
      1. LIVE: no scope function in the producing chain can fold the value away
         (wide4: `takeIf { false }` swallowed the whole write);
      2. DERIVED: the field name is in the expression that PRODUCES the value —
         the receiver chain for `it`, the value expression otherwise. A field
         name that only appears in a neighbouring statement or in a dead branch
         does not count.
    Returns (derived: bool, why: str|None).
    """
    value = site["value"]
    if not value or LITERAL_RE.match(value):
        return False, "the value is a constant literal, not the field"
    producer = site.get("producer", "")
    kill = _chain_kills(producer)
    if kill:
        return False, kill
    if re.search(r"\?:\s*(?:-?\d|true\b|false\b|null\b|\")", value):
        return False, "the value falls back to a constant when the field is absent"
    if re.search(r"\b%s\b" % re.escape(field), value):
        return True, None
    if re.search(r"\b%s\b" % re.escape(field), producer):
        return True, None
    return False, "the field name is not in the expression that produces the value"


def key_read_sites(ks, st):
    """Keys that are *read* (not merely mentioned) in live worker code.

    The read shapes in this repo: `req.optString("k", default)`,
    `req.has("k")`, `getString("role")`, and the helper form
    `getString(obj, "k")`. A key that only appears in a comment, or in a
    `maxOutputTokens = null` replacement, is not consumed.
    """
    out = set()
    read_callee = re.compile(r"\b(?:get|opt|has|is)[A-Za-z]*$")
    for m in re.finditer(r'"([^"\n]+)"', ks):
        key = m.group(1)
        j = m.start() - 1
        while j >= 0 and ks[j] in " \t\n":
            j -= 1
        if j < 0:
            continue
        ok = False
        if ks[j] == "(":
            name = re.search(r"[\w.]+$", ks[max(0, j - 60):j])
            ok = bool(name and read_callee.search(name.group(0)))
        elif ks[j] == ",":
            # helper form: callee(<receiver>, "key") — find the call's open paren
            depth = 0
            k = j
            while k >= 0:
                if ks[k] in ")]":
                    depth += 1
                elif ks[k] in "([":
                    if depth == 0:
                        break
                    depth -= 1
                k -= 1
            if k > 0:
                name = re.search(r"[\w.]+$", ks[max(0, k - 60):k])
                ok = bool(name and read_callee.search(name.group(0)))
        if ok and not dead_guarded(st, m.start()):
            out.add(key)
    return out


def construction_spans(st, name):
    """Argument spans of every `Name(...)` construction in the worker."""
    spans = []
    for m in re.finditer(r"\b" + re.escape(name) + r"\s*\(", st):
        head = st[max(0, m.start() - 40):m.start()]
        if re.search(r"\b(?:data\s+)?class\s+$", head):
            continue  # a declaration, not a construction
        op = st.find("(", m.start())
        end = paren_end(st, op)
        if end is None:
            continue
        spans.append((op, end))
    return spans


def named_args(st, span):
    """{param_name: (rhs_start, rhs_end)} for `Name(...)` args; positional args
    are collected under None so a caller can tell the two apart."""
    op, end = span
    out = {}
    for vs, ve in split_top(st[op + 1:end], (",",)):
        seg = st[op + 1 + vs:op + 1 + ve]
        m = re.match(r"\s*(\w+)\s*=(?!=)", seg)
        if not m:
            out.setdefault(None, []).append((op + 1 + vs, op + 1 + ve))
            continue
        out[m.group(1)] = (op + 1 + vs + m.end(), op + 1 + ve)
    return out


READ_CALL_RE = re.compile(r"\b(?:get|opt)[A-Za-z]*\s*\(\s*\"([^\"]+)\"")


def key_read_problem(rhs, key, base=None, full=None):
    """None when this expression really CARRIES the key's value, else why not.

    [wide4 / construction_spans] `if (req.has("max_output_tokens")) null else
    null` mentions the key, calls the bundle, and returns a constant: the `has()`
    probe satisfied the old "the key is read somewhere" test while both branches
    dropped it. Only `get*`/`opt*` with the key as the first string argument count
    as extraction; a probe is not a value.

    [r3 / witness #55] Extraction is not enough either.
    `req.getInt("max_output_tokens").takeIf { false }` extracts the value and
    then throws it away — the exact shape this gate already refuses on the
    SENDER side (`_chain_kills`), one hop away. The receiver was judged only by
    "is there a read call", so the sender/receiver pair disagreed about the same
    key. The kill analysis now runs with the same code path on both sides, and a
    read inside dead code (`if (false) …`, an unreachable `when` arm) does not
    count as a read at all.
    """
    for m in READ_CALL_RE.finditer(rhs):
        if m.group(1) != key:
            continue
        if base is not None and full is not None and dead_guarded(full, base + m.start()):
            return ("the key is extracted in dead code (`if (false)` / an "
                    "unreachable branch), so the value never arrives")
        kill = _chain_kills(rhs[m.start():])
        if kill:
            return "the extracted value is thrown away: " + kill
        return None
    return ("the key is never extracted (`has()` is a probe, not a value; a "
            "constant assignment carries nothing)")


def key_read_in(rhs, key):
    """Backwards-compatible boolean form (see `key_read_problem`)."""
    return key_read_problem(rhs, key) is None


# ── r3 #54: derived projections on a boundary model ──────────────────────────
DERIVED_BOOL_RE = re.compile(
    r"\bval\s+(\w+)\s*:\s*Boolean\s*(?:\r?\n\s*)?get\s*\(\s*\)\s*(=|\{)")


def _enum_spans(text):
    out = []
    for m in re.finditer(r"\benum\s+class\s+\w+[^{]*\{", text):
        j = match_bracket(text, m.end() - 1)
        if j > 0:
            out.append((m.start(), j))
    return out


def derived_boolean_getters(text):
    """[(name, offset, body-or-None)] for every `val x: Boolean` with a getter.

    `body` is the value expression (expression body) or the concatenation of the
    block's top-level `return` expressions (block body) — the set of values the
    property can actually take. Enum entries are skipped: `val isEnabled: Boolean
    get() = this != OFF` inside an `enum class` is a per-constant predicate, not a
    projection of a boundary model.
    """
    enums = _enum_spans(text)
    out = []
    for m in DERIVED_BOOL_RE.finditer(text):
        if any(a <= m.start() < b for a, b in enums):
            continue
        if m.group(2) == "=":
            start = m.end()
            out.append((m.group(1), m.start(), text[start:expr_end(text, start)]))
            continue
        ob = m.end() - 1
        cb = match_bracket(text, ob)
        if cb < 0:
            out.append((m.group(1), m.start(), None))
            continue
        inner = text[ob + 1:cb]
        exits = [inner[a:b].strip()[len("return"):].strip()
                 for a, b in stmt_spans(inner)
                 if inner[a:b].strip().startswith("return")]
        if not exits:
            t = tail_expr(inner)
            if t:
                exits = [t]
        out.append((m.group(1), m.start(), "\n".join(exits) if exits else None))
    return out


def constant_projection(body):
    """The single constant a projection always evaluates to, or None.

    `get() = false` and `get() { return false }` both fold; `get() = if (x)
    return true else return false` (i.e. the property IS `x`) folds per-exit to
    two different values and is therefore not constant. `None` means "the value
    depends on something".
    """
    if body is None:
        return None
    vals = {const_bool(seg.strip()) for seg in body.split("\n") if seg.strip()}
    if len(vals) == 1:
        v = vals.pop()
        if v is not None:
            return v
    return None


# ── r3 #56: session-level restores must run on EVERY worker entry point ──────
FUN_DECL_RE = re.compile(r"\bfun\s+(\w+)\s*\(")


def _first_param(params):
    """(name, type) of a declaration's first parameter, or (None, None)."""
    parts = split_top(params, (",",))
    if not parts:
        return None, None
    seg = params[parts[0][0]:parts[0][1]].strip()
    m = re.match(r"(?:@\w+\s+)*(?:\w+\s*:\s*)?(\w+)\s*:\s*([\w<>.? ]+)$", seg)
    return (m.group(1), m.group(2).strip()) if m else (None, None)


def worker_functions(full):
    """[(name, param_count, first_param_name, first_param_type, body, body_start)].

    Bodies come from the shared `function_body` (it handles expression bodies and
    default-argument lambdas); a declaration it cannot read is reported with an
    empty body so the caller's completeness rules fail closed rather than skip.
    """
    out = []
    for m in FUN_DECL_RE.finditer(full):
        op = m.end() - 1
        cl = match_bracket(full, op)
        if cl < 0:
            continue
        params = full[op + 1:cl]
        pname, ptype = _first_param(params)
        fb = function_body(full, m.group(1))
        body, bstart = ("", 0) if fb is None else (full[fb[0]:fb[1]], fb[0])
        out.append((m.group(1), len(split_top(params, (",",))), pname, ptype,
                    body, bstart))
    return out


def worker_entry_points(full, code):
    """Worker functions that take the request JSON and rebuild a boundary model.

    Derived, not name-pinned: `executeRun` and `executeStreamingRun` are the two
    sites today, but the set is whatever the tree says it is — a renamed or newly
    added entry is caught by the same rule.
    """
    names = [g["name"] for g in BOUNDARY_MODELS]
    out = []
    for name, pcount, pname, ptype, body, bstart in worker_functions(code):
        if ptype != "JSONObject" or pcount < 1 or not body:
            continue
        if not any(re.search(r"\b%s\s*\(" % re.escape(n), body) for n in names):
            continue
        out.append((name, pname, body, bstart))
    return out


def session_restores(full):
    """Worker helpers that take the request JSON and nothing else."""
    out = []
    for name, pcount, pname, ptype, body, bstart in worker_functions(full):
        if ptype == "JSONObject" and pcount == 1 and body:
            out.append((name, pname))
    return out


def calls_live(code, entry, callee, pname):
    """Does `entry` call `callee` on its request argument, in live code?"""
    _name, _p, body, bstart = entry
    for m in re.finditer(r"\b%s\s*\(\s*%s\b" % (re.escape(callee), re.escape(pname or "req")),
                         body):
        if not dead_guarded(code, bstart + m.start()):
            return True
    return False


def guard_contradiction(code, ks):
    """`if (read(key)) { … x = <neutral constant> }` — the block is a no-op.

    [r3 / witness #59] `if (req.optBoolean("enhanced_cache", false)) {
    (provider as? AnthropicProvider)?.enhancedCache = false }` reads the key,
    takes the branch, and then pins the effect to the value that means "off".
    Both the read call and the assignment are present, so every presence test is
    satisfied while the feature is dead on that path. The polarity of the guard
    is what makes the assignment decidable: a positive guard (the key says ON)
    followed by `= false` (or a `!`-guarded key followed by `= true`) undoes
    itself, and the whole block collapses to nothing.

    Returns [(key, offset, detail)].
    """
    out = []
    for cond, t_start, t_end, _e_start, _e_end in if_blocks(ks):
        keys = [m.group(1) for m in READ_CALL_RE.finditer(cond)]
        if not keys:
            continue
        negated = bool(re.search(r"!\s*[\w.]*\s*$", cond[:max(
            [m.start() for m in READ_CALL_RE.finditer(cond)])]))
        body = ks[t_start:t_end]
        for a in re.finditer(r"(?:\?\.|\.)?\s*\b(\w+)\s*=(?!=)\s*(true|false)\b", body):
            assigned = a.group(2) == "true"
            if assigned == negated:
                out.append((keys[0], t_start + a.start(),
                            "%s = %s under `if (%s)`" % (a.group(1), a.group(2),
                                                          " ".join(cond.split())[:70])))
    return out


def to_snake(name):
    """camelCase / PascalCase -> snake_case (matches the dispatcher's key style)."""
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", s)
    return s.lower()


def evaluate(root):
    """(problems, report_lines) for the tree at `root`."""
    problems, lines = [], []

    def read_at(path):
        try:
            return open(os.path.join(root, path), encoding="utf-8",
                        errors="replace").read()
        except OSError:
            return None

    dsrc = read_at(DISPATCHER)
    wsrc = read_at(WORKER)
    if dsrc is None:
        return ["cannot read " + DISPATCHER], lines
    if wsrc is None:
        # Anchor drift: the worker moved or was renamed. The reverse direction
        # cannot be judged at all, and "cannot judge" must never read as clean.
        return ["cannot read " + WORKER + " (worker moved? reverse check cannot run)"], lines

    dks, dst, lex_err = views_of(dsrc)
    if dst is None:
        # Fail closed: a source this gate cannot lex must never read as clean.
        return ["cannot lex the dispatcher (%s) — the boundary check refuses to "
                "judge a file whose structure it cannot read" % lex_err], lines
    wks, wst, lex_err = views_of(wsrc)
    if wst is None:
        return ["cannot lex the worker (%s) — the reverse direction and the "
                "per-site completeness cannot be judged" % lex_err], lines

    sites = put_sites(dks, dst)
    if not sites:
        return ["dispatcher writes no named put() keys — parser death, not a clean tree"], lines
    live_keys = {s["key"] for s in sites if not s["dead"]}
    model_keys = {}      # model -> {field: dispatcher key}  (live + derived)
    for g in BOUNDARY_MODELS:
        name = g["name"]
        src = read_at(g["model_path"])
        if src is None:
            problems.append("cannot read %s" % g["model_path"])
            continue
        fields, err = extract_data_class_fields(src, name)
        if err:
            problems.append("%s: %s" % (name, err))
            continue
        model_keys[name] = {}

        benign = BENIGN.get(name, {})
        alias = KEY_ALIASES.get(name, {})
        missing = []
        for f in fields:
            want = alias.get(f, to_snake(f))
            if f in benign:
                continue
            cand = [s for s in sites if s["key"] == want]
            live = [s for s in cand if not s["dead"]]
            if not live:
                why = ("dispatcher never writes %r (only in dead code)" % want
                       if cand else "dispatcher never writes %r" % want)
                missing.append((f, why))
                continue
            derived, why_not = False, "unresolved expression"
            for s in live:
                ok_now, why_now = derives_field(s, f, dks, dst)
                if ok_now:
                    derived = True
                    break
                why_not = why_now or why_not
            if not derived:
                missing.append((f, "dispatcher writes %r but the value carries no "
                                   "`%s` (%s)" % (want, f, why_not)))
                continue
            model_keys[name][f] = want

        # r3 #54: the model's derived PROJECTIONS, not just its fields. A
        # projection folded to a constant answers the same for every instance,
        # so the field it gates can never take a non-default value — and it is
        # exactly the shape that falls in the gap between "the ctor list is
        # complete" (this gate) and "the read side trims" (the write-face gate).
        _mks, mst, merr = views_of(src)
        if mst is None:
            problems.append("%s: cannot lex the model file (%s) — its projections "
                            "cannot be judged" % (name, merr))
        else:
            for pname, poff, pbody in derived_boolean_getters(mst):
                folded = constant_projection(pbody)
                if folded is None:
                    continue
                pline = mst.count("\n", 0, poff) + 1
                problems.append(
                    "%s.%s folds to `%s` (line %d)" %
                    (name, pname, "true" if folded else "false", pline))
                lines.append(
                    "    FROZEN   %s.%s (line %d) always evaluates to %s — a "
                    "projection that stops deriving from the instance gates every "
                    "consumer to one answer" %
                    (name, pname, pline, "true" if folded else "false"))

        status = "OK" if not missing else "GAP"
        lines.append("%-18s %2d fields  ->  %s" % (name, len(fields), status))
        for f, why in missing:
            problems.append("%s.%s" % (name, f))
            lines.append("    MISSING  %s.%s  (%s)" % (name, f, why))
        for f, why in sorted(benign.items()):
            if f in fields:
                lines.append("    benign   %s.%s  (%s)" % (name, f, why))

    # Reverse direction: keys written but never consumed on the worker side.
    read_keys = key_read_sites(wks, wst)
    orphan = sorted(k for k in live_keys if k not in read_keys)
    if orphan:
        lines.append("\nDispatcher writes %d key(s) the worker never reads:" % len(orphan))
        for k in orphan[:20]:
            lines.append("    ORPHAN   %s" % k)
        problems.extend("orphan:" + k for k in orphan)

    # Per-construction completeness: the worker rebuilds each boundary model at
    # more than one site (streaming + non-streaming). A key missing from ONE
    # site is a silently degraded path, and the global text search cannot see
    # it because the other site mentions the key.
    # Each NAMED argument of each construction site is judged on its own
    # right-hand side (wide4 / construction_spans): `maxOutputTokens =
    # if (req.has(k)) null else null` mentions the key, calls the bundle and
    # still drops the value; a span-wide `'"key"' in span` test cannot see it,
    # because the other construction site keeps the key spelled out.
    for name, fmap in model_keys.items():
        spans = construction_spans(wst, name)
        wanted = {f: k for f, k in fmap.items() if k in live_keys}
        if not spans or not wanted:
            continue
        for span in spans:
            args = named_args(wst, span)
            bad = []
            for f, k in sorted(wanted.items()):
                where = args.get(f)
                if where is None:
                    bad.append((k, "the constructor has no `%s = …` argument" % f))
                    continue
                rs, re_ = where
                rhs = wks[rs:re_]
                if ('"%s"' % k) not in rhs:
                    bad.append((k, "`%s` is built without the %r key" % (f, k)))
                else:
                    why = key_read_problem(rhs, k, rs, wks)
                    if why:
                        bad.append((k, "`%s` mentions %r but %s" % (f, k, why)))
            if bad:
                line = wst.count("\n", 0, span[0]) + 1
                lines.append("    SITE     %s(...) at line %d drops %s"
                             % (name, line, ", ".join(k for k, _ in bad[:6])))
                problems.extend("%s site@%d drops %s (%s)" % (name, line, k, why)
                                for k, why in bad)
                break

    # ── r3 #56: session-level restores, per entry point ──────────────────────
    # The ctor arguments are the per-field half of the request; the other half is
    # process-level state that also travels in the same JSON (`thinking_rules`
    # restores the worker's resolver cache). The set of keys "read somewhere" in
    # the worker is therefore satisfied by ONE entry point doing the restore, and
    # the entry that forgot it degrades silently — the T-worker-thinking-rules
    # defect, one hop away from the ctor sites this gate already judges per-site.
    entries = worker_entry_points(wks, wst)
    if re.search(r":\s*JSONObject\b", wks):
        if len(entries) < 2:
            problems.append(
                "worker: found %d entry point(s) that take the request JSON and "
                "rebuild a boundary model (%s) — the per-entry restore symmetry "
                "cannot be judged (parser death or a renamed entry, never a "
                "silent pass)" %
                (len(entries), ", ".join(e[0] for e in entries) or "none"))
        else:
            for hname, hparam in session_restores(wks):
                have = [e for e in entries if calls_live(wst, e, hname, hparam)]
                if not have:
                    continue
                for e in entries:
                    if e in have:
                        continue
                    problems.append(
                        "worker entry %s(%s) never calls %s(%s) while %s does" %
                        (e[0], e[1], hname, hparam, have[0][0]))
                    lines.append(
                        "    ENTRY    %s(...) skips %s(...) — the state that "
                        "travels in the same request is restored on the other "
                        "entry and left empty here" % (e[0], hname))

    # ── r3 #59: a key read that gates a block, undone inside it ──────────────
    for key, off, detail in guard_contradiction(wst, wks):
        wline = wst.count("\n", 0, off) + 1
        problems.append(
            "worker: the guard on %r is undone inside its own block (%s, line %d)" %
            (key, detail, wline))
        lines.append(
            "    UNDONE   `%s` guards a block that assigns the neutral constant "
            "(%s) at line %d — the branch is a no-op, the feature is off" %
            (key, detail, wline))
    return problems, lines


def main():
    problems, lines = evaluate(REPO)
    for ln in lines:
        print(ln)
    print("")
    if problems:
        print("== %d cross-process boundary problem(s) ==" % len(problems))
        print("")
        print("A field on a boundary model that the dispatcher never writes will")
        print("silently fall back to its default inside the worker. Fix by adding it")
        print("to ModelExecutionDispatcher, or list it under BENIGN with a reason.")
        return 1
    print("== cross-process boundary OK ==")
    return 0


# ── self-test: every negative arm is one of the four re-introduction shapes ───
MODEL = (
    "package com.rikkaminis.app.data.model\n\n"
    "data class LLMModel(\n"
    "    val id: String,\n"
    "    val maxOutputTokens: Int? = null,\n"
    ")\n"
)
PROVIDER = (
    "package com.rikkaminis.app.data.model\n\n"
    "data class ProviderInstance(\n"
    "    val id: String,\n"
    ")\n"
)
DISPATCHER_HEAD = (
    "package com.rikkaminis.app.sandbox.offload\n\n"
    "class ModelExecutionDispatcher {\n"
    "    fun build(model: LLMModel, instance: ProviderInstance) {\n"
    "        val bundle = Bundle()\n"
    "        bundle.apply {\n"
    "            put(\"instance_id\", instance.id)\n"
    "            put(\"model_id\", model.id)\n"
)
DISPATCHER_TAIL = "        }\n    }\n}\n"
WORKER_HEAD = (
    "package com.rikkaminis.app.sandbox.offload\n\n"
    "class ModelExecutionService {\n"
    "    fun read(b: Bundle) {\n"
    "        val i = b.getString(\"instance_id\")\n"
    "        val m = com.rikkaminis.app.data.model.LLMModel(\n"
    "            id = b.getString(\"model_id\"),\n"
)
WORKER_TAIL = "            maxOutputTokens = b.getInt(\"max_output_tokens\"),\n        )\n    }\n}\n"


def _tree(files):
    root = tempfile.mkdtemp(prefix="xproc-selftest-")
    for rel, text in files.items():
        p = os.path.join(root, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p, "w", encoding="utf-8").write(text)
    return root


B = "src/android/app/src/main/java/com/rikkaminis/app/"


def _fixture(model=MODEL, puts="", worker_tail=WORKER_TAIL, worker_extra=""):
    return {
        B + "data/model/LLMModel.kt": model,
        B + "data/model/ProviderConfig.kt": PROVIDER,
        B + "sandbox/offload/ModelExecutionDispatcher.kt":
            DISPATCHER_HEAD + puts + DISPATCHER_TAIL,
        B + "sandbox/offload/ModelExecutionService.kt":
            WORKER_HEAD + worker_tail + worker_extra + "}\n",
    }


def _fixture_json(drop_restore_in=None):
    """Worker with JSONObject entry points — the real worker's request shape.

    `drop_restore_in` names the entry that forgets the session-level restore
    (witness #56). Both entries otherwise rebuild LLMModel with every key, so the
    only difference between the green and the red arm is that one call.
    """
    def entry(fname):
        restore = ("" if drop_restore_in == fname
                   else "        restoreThinkingRules(req)\n")
        return (
            "    private fun %s(req: JSONObject, dir: File): String {\n"
            "%s"
            "        val instanceId = req.getString(\"instance_id\")\n"
            "        val model = LLMModel(\n"
            "            id = req.getString(\"model_id\"),\n"
            "            maxOutputTokens = req.getInt(\"max_output_tokens\"),\n"
            "        )\n"
            "        return model.id\n"
            "    }\n" % (fname, restore)
        )

    worker = (
        "package com.rikkaminis.app.sandbox.offload\n\n"
        "class ModelExecutionService {\n"
        "    private var restored: String = \"\"\n\n"
        "    private fun restoreThinkingRules(req: JSONObject) {\n"
        "        restored = req.optString(\"thinking_rules\", \"\")\n"
        "    }\n\n"
        + entry("executeRun")
        + "\n"
        + entry("executeStreamingRun")
        + "}\n"
    )
    return dict(_fixture(puts='            model.maxOutputTokens?.let '
                              '{ put("max_output_tokens", it) }\n'),
                **{B + "sandbox/offload/ModelExecutionService.kt": worker})


def selftest():
    import shutil



    failures = []
    count = [0]

    def arm(name, files, want_rc, needle=None):
        count[0] += 1
        root = _tree(files)
        try:
            problems, lines = evaluate(root)
            rc = 0 if not problems else 1
            haystack = problems + lines
            ok = rc == want_rc and (needle is None
                                    or any(needle in h for h in haystack))
            print("  %s %s" % ("✅" if ok else "❌", name))
            if not ok:
                failures.append("%s (rc=%d want=%d problems=%s panels=%s)"
                                % (name, rc, want_rc, problems[:4], lines[-3:]))
        finally:
            shutil.rmtree(root, ignore_errors=True)

    live_put = '            model.maxOutputTokens?.let { put("max_output_tokens", it) }\n'
    # 1. control: the real shape passes.
    arm("clean: derived live put() passes", _fixture(puts=live_put), 0)

    # 2. dataflow: `if (false)` keeps the literal, kills the write.
    arm("dead branch: `if (false) … put(\"max_output_tokens\", it)` is red",
        _fixture(puts='            if (false) model.maxOutputTokens?.let { put("max_output_tokens", it) }\n'),
        1, "maxOutputTokens")

    # 3. dataflow: the key is written, the value is a constant.
    arm("constant value: `put(\"max_output_tokens\", 0)` is red",
        _fixture(puts='            put("max_output_tokens", 0)\n'), 1, "maxOutputTokens")

    # 4. parser-death: a `)` inside a comment must not truncate the ctor list.
    truncated = (
        "package com.rikkaminis.app.data.model\n\n"
        "data class LLMModel(\n"
        "    val id: String,\n"
        "    val maxOutputTokens: Int? = null, // close paren in comment: )\n"
        "    val unbridgedNewField: Int? = null,\n"
        ")\n"
    )
    arm("parser-death: `)` inside a comment cannot hide later fields",
        _fixture(model=truncated, puts=live_put), 1, "unbridgedNewField")

    # 5. anchor: worker replaces the read with `null` + a comment naming the key.
    broken_tail = (
        "            maxOutputTokens = null, // \"max_output_tokens\" ignored here\n"
    )
    arm("worker: `= null` plus a comment mentioning the key is red",
        _fixture(puts=live_put, worker_tail=broken_tail), 1, "orphan:max_output_tokens")

    # 6. completeness: a SECOND construction site that drops the key.
    second = (
        "    fun read2(b: Bundle) {\n"
        "        val m = com.rikkaminis.app.data.model.LLMModel(\n"
        "            id = b.getString(\"model_id\"),\n"
        "            maxOutputTokens = null,\n"
        "        )\n"
        "    }\n"
    )
    arm("multi-site: one of two ctor sites dropping the key is red",
        _fixture(puts=live_put, worker_extra=second), 1, "SITE")

    # ── w4- witnesses: each flipped this gate green before ───────────
    # 8. dead_guarded: `when (false) { true -> … }` keeps the literal and kills
    #    the write — the shape the DEAD_RE spelling list could not see.
    arm("w4-dead-guarded: `when (false) { true -> put(…) }` is red",
        _fixture(puts='            when (false) { true -> model.maxOutputTokens?.let '
                      '{ put("max_output_tokens", it) } else -> Unit }\n'),
        1, "maxOutputTokens")

    # 9. derives_field: `takeIf { false }` folds the value to null while the
    #    field name, the key and the live call all stay in place.
    arm("w4-derives-field: `?.takeIf { false }?.let { put(…) }` is red",
        _fixture(puts='            model.maxOutputTokens?.takeIf { false }?.let '
                      '{ put("max_output_tokens", it) }\n'),
        1, "maxOutputTokens")

    # 10. construction_spans: both branches null — `has()` is a probe, not a read.
    arm("w4-construction-spans: `if (has(k)) null else null` is red",
        _fixture(puts=live_put,
                 worker_tail='            maxOutputTokens = if (b.has("max_output_tokens")) '
                             'null else null,\n        )\n    }\n}\n'),
        1, "max_output_tokens")

    # 11. blank: a `${…}` template holding a `)` must not truncate the ctor list.
    template_model = (
        "package com.rikkaminis.app.data.model\n\n"
        "data class LLMModel(\n"
        "    val id: String,\n"
        "    val contextWindow: Int? = \"${\")\"}\".toIntOrNull(),\n"        "    val maxOutputTokens: Int? = null,\n"
        "    val unbridgedNewField: Int? = null,\n"
        ")\n"
    )
    arm("w4-blank: `)` inside a ${…} template cannot hide later fields",
        _fixture(model=template_model, puts=live_put), 1, "unbridgedNewField")

    # ── r3 witnesses: the four edits that landed in this gate's seams ────────
    # r3 #54: a derived projection pinned to a constant (the `supportsAzureMode`
    # shape). Both the ctor list and the read side stay perfect; the answer is
    # fixed for every instance, so the carried field can never become true.
    pinned_provider = (
        "package com.rikkaminis.app.data.model\n\n"
        "data class ProviderInstance(\n"
        "    val id: String,\n"
        ") {\n"
        "    val supportsAzureMode: Boolean\n"
        "        get() = false\n"
        "}\n"
    )
    arm("r3-frozen-projection: `get() = false` on a boundary model is red",
        dict(_fixture(puts=live_put), **{B + "data/model/ProviderConfig.kt": pinned_provider}),
        1, "supportsAzureMode")
    # …and the control: a projection that really derives stays green.
    live_provider = pinned_provider.replace("        get() = false\n",
                                            "        get() = id.isNotEmpty()\n")
    arm("r3-frozen-projection: a deriving projection stays green",
        dict(_fixture(puts=live_put), **{B + "data/model/ProviderConfig.kt": live_provider}),
        0)

    # r3 #55: the value is READ and then thrown away on the receiving side.
    arm("r3-read-discarded: `req.getInt(k).takeIf { false }` is red",
        _fixture(puts=live_put,
                 worker_tail='            maxOutputTokens = b.getInt("max_output_tokens")'
                             '.takeIf { false },\n        )\n    }\n}\n'),
        1, "thrown away")

    # r3 #56: one of two JSON entry points skips the session-level restore.
    arm("r3-entry-restore: one entry skipping a restore is red",
        _fixture_json(drop_restore_in="executeRun"), 1, "executeRun")
    arm("r3-entry-restore: both entries restoring is green",
        _fixture_json(), 0)

    # r3 #59: the guard on a key is undone inside its own block.
    arm("r3-guard-undone: `if (optBoolean(k)) { x = false }` is red",
        _fixture(puts=live_put,
                 worker_extra='    fun applyCache(b: Bundle, p: Any) {\n'
                              '        if (b.optBoolean("enhanced_cache", false)) {\n'
                              '            p.enhancedCache = false\n'
                              '        }\n'
                              '    }\n'),
        1, "enhanced_cache")
    arm("r3-guard-undone: the honest `= true` stays green",
        _fixture(puts=live_put,
                 worker_extra='    fun applyCache(b: Bundle, p: Any) {\n'
                              '        if (b.optBoolean("enhanced_cache", false)) {\n'
                              '            p.enhancedCache = true\n'
                              '        }\n'
                              '    }\n'),
        0)

    # 7. fail-closed: a missing worker file is a violation, not a clean tree.
    files = _fixture(puts=live_put)
    del files[B + "sandbox/offload/ModelExecutionService.kt"]
    arm("fail-closed: missing worker file is red", files, 1, "cannot read")

    if failures:
        for f in failures:
            print("FAIL  " + f)
        print("XPROC-BOUNDARY SELFTEST=FAIL")
        return 1
    print("%s (%d arms)" % (SELFTEST_MARKER, count[0]))
    return 0


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    sys.exit(main())
