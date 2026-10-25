#!/usr/bin/env python3
"""
Four-way sync check v2.1 — Model ↔ Entity ↔ to-DB ↔ from-DB field consistency.

Background: Adding fields to a data class and forgetting to update any of the
other layers causes silent data loss (GH#68 image-endpoint, ProviderInstance.pinned,
F-199 thinking-rule id read-side drop). Lint/compilation don't catch this.

v1 hardcoded 2 families (ProviderInstance, ModelGroup) — the other 8 Room
entities' field gaps were unchecked. v2 DERIVES the families from
data/db/*Entity.kt: for every Room entity it resolves the domain model class
and the constructor sites that build each side, then checks the same three
gap classes:

  1. Model has but Entity missing  → field saved nowhere, lost on every save
  2. Entity has but to-DB site doesn't set it → saved as NULL/default forever
  3. Model has but from-DB site doesn't restore it → lost on every load

Families that can't be resolved (no model data class, no constructor sites)
are printed in an UNRESOLVED list — visible, exit 0, NOT failures. The
coverage table makes the blind spot explicit instead of silent.

v2.1 (2026-10-13, review D-1): v2's honest-UNRESOLVED design left the chat
core tables (ChatSessionEntity / MessageEntity / …) silently unchecked —
real-tree run printed "checked 4 · unresolved 6" next to "ALL CLEAN ✅".
Those families are their own domain shape (no `ChatSession`/`Message` data
class): ENTITY_SELF_FAMILIES now checks every column is written by SOME
constructor site, with audited pins for DAO-written lifecycle columns.
The Done line now carries the coverage counts so a "clean" can never again
be read without its denominator.

Usage: python3 scripts/scan/four_way_sync_check.py <repo_root>
Exit code: 0 = clean (or unresolved-only), 1 = real field gaps found
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import (  # noqa: E402  (shared, single lexer for all gates)
    KotlinLexError, analyse, mask_strings,
)

KOTLIN = "src/android/app/src/main/java/com/rikkaminis/app/"
DB_DIR = "data/db"

# ── Naming-convention breaks (audited pins, each with a reason line) ──────
# Entity name → domain model data class name. Only needed when the model
# class name is NOT the entity name minus the "Entity" suffix.
FAMILY_MODEL_ALIASES = {
    # The "Provider" prefix belongs to the table name, not the domain class:
    # the model is ThinkingRule (provider/thinking/ThinkingRule.kt). This
    # family is checked because F-199 (persisted rule id dropped on the read
    # side of toRule) was a real bug in exactly this family.
    "ProviderThinkingRuleEntity": "ThinkingRule",
    # Same prefix break: the domain classes are ModelEntry / ModelGroup
    # (data/model/ProviderConfig.kt); the entity names carry the table
    # prefix. Both were v1-hardcoded families — v2 must keep them checked.
    "ProviderModelEntryEntity": "ModelEntry",
    "ProviderModelGroupEntity": "ModelGroup",
}

# ── Field aliases: model field ↔ entity column (1:1 renames, audited) ─────
FIELD_ALIASES = {
    "ProviderInstance": {
        # [T-multi-api-key] Credential list is List<ProviderCredentialMeta> on
        # the model but a JSON blob column (credentialsJson) in Room — same
        # shape as ModelGroup.memberEntryIdsJson.
        "credentials": "credentialsJson",
    },
    "ModelGroup": {
        # List<String> memberEntryIds serialized into a JSON blob column.
        "memberEntryIds": "memberEntryIdsJson",
    },
    "ModelEntry": {
        # LLMModel object serialized into baseModelJson; overrides object into
        # overridesJson; the model's `uuid` becomes the composite-key `id`
        # column ("{instanceId}/{modelId}" — see compositeEntryKey).
        "baseModel": "baseModelJson",
        "overrides": "overridesJson",
        "uuid": "id",
    },
}

# ── KNOWN_MISMATCH pins: (group, field) → reason ──────────────────────────
# Fields that have NO 1:1 entity counterpart by design (transform / blob
# serialization). Printed as a WARNING line every run so the known gap stays
# visible instead of silently swallowed; never counted as a failure.
KNOWN_MISMATCH = {
    ("ThinkingRule", "kind"): (
        "sealed Kind is transformed into scopeKind (+scopePattern) via "
        "toEntity's `when` — no 1:1 column by design"
    ),
    ("ThinkingRule", "scope"): (
        "sealed Scope (AllModels/ModelPattern) serialized into the "
        "scopeKind/scopePattern column pair via toEntity's `when`"
    ),
    ("ThinkingRule", "wireFormat"): (
        "ThinkingWireFormat object serialized into the wire_format_json blob "
        "(wireFormatJson) via ThinkingRuleCoding.encodeWireFormat"
    ),
    ("ThinkingRule", "reasoningEcho"): (
        "ReasoningEchoPolicy serialized into the reasoning_echo_json blob "
        "(reasoningEchoJson) via ThinkingRuleCoding.encodeEcho"
    ),
}

# Entity-internal columns that legitimately have no model counterpart.
LEGIT_ENTITY_ONLY = {"sortOrder"}

# ── ENTITY_SELF_FAMILIES: entities checked against their OWN ctor sites ──
# v2 left these UNRESOLVED (no `ChatSession`/`Message` domain data class
# exists — the entity IS the domain shape). v2.1 checks every column is
# written by SOME constructor site. The set is AUDITED-PINNED (each name has
# a reason); an entity NOT listed and WITHOUT a domain data class still lands
# in UNRESOLVED visibly, so a new chat-table-shaped entity can't slip through
# silently.
#
# DAO_WRITTEN pins: lifecycle columns no constructor ever passes, written by
# @Query UPDATE statements instead. Audited per entity (grepped ChatDao.kt).
ENTITY_SELF_FAMILIES = {
    # Chat core tables — the exact family "field saved nowhere" historically
    # bites (session metadata, message parts/reasoning/usage columns).
    "ChatSessionEntity",
    "MessageEntity",
    # Single-site entities where a dropped ctor arg would silently default.
    "CompactMarkerEntity",
    "ProviderAgentLoopIdEntity",
    "ProviderConfigMetaEntity",   # positional ctor sites (KEY, value) — handled
    "WebAppShortcutEntity",
}
POSITIONAL_ONLY_SITES = {
    # ProviderConfigMetaEntity(KEY, value): every site is positional, so
    # named-arg extraction sees 0 args. Each site assigns fields by ORDER;
    # a no-named-args site is treated as "covers all non-defaulted fields".
    "ProviderConfigMetaEntity",
}
DAO_WRITTEN = {
    "MessageEntity": {
        "updatedAt",          # ChatDao: UPDATE messages SET … updated_at
        "streamInterruptCount",  # ChatDao: stream_interrupt_count = … + 1
    },
    "ChatSessionEntity": {
        "updatedAt",          # ChatDao: UPDATE sessions SET updated_at
    },
}

# ── ENTITY_CONST_PINS: columns a ctor site legitimately writes as a literal ──
# [wide4-12 witness w4-fw-3, axis=completeness] The derivation criterion used
# to run only for the four derived families; the six entity-self families —
# including the chat core tables — `continue`d right after the "is this column
# written at all?" set difference, so `summary = ""` on CompactMarkerEntity
# (the compaction summary thrown away on EVERY save) passed as "mapped".
# Every entity-self column is now also judged by the SAME positive criterion:
# it must be carried from source at least once. A column whose value IS a
# literal by design is pinned here, with a reason, so the pin is the audit.
ENTITY_CONST_PINS = {
    ("CompactMarkerEntity", "boundaryMessageId"):
        "explicit null: the message-id boundary pin is idle for a "
        "full-session compaction — the marker keeps the sort-order boundary",
    ("CompactMarkerEntity", "firstKeptMessageId"):
        "explicit null: 'kept-from-here' message boundary unused by the "
        "sort-order-based compaction path (single ctor site by design)",
    ("CompactMarkerEntity", "uiBoundarySortOrder"):
        "explicit null: UI sort-order boundary only set by the trim path",
    ("CompactMarkerEntity", "version"):
        "marker FORMAT version constant — bumped with the marker schema, "
        "never derived from the session row",
    ("ProviderAgentLoopIdEntity", "kind"):
        "discriminator column: the row kind IS the literal key "
        "(\"entry\" / \"group\") that selects which table slot the id fills",
}


PARSE_FAILURES = []   # fail-closed: an unparseable shape is a violation
CONST_IDS = {"true", "false", "null", "this", "it"}
# Kotlin hard keywords — they spell control flow, not a value read, so they do
# not make an expression "derived". `if` / `else` / `when` used to count as
# source reads (the old CONST_IDS forgot them entirely).
KOTLIN_KEYWORDS = {
    "as", "break", "by", "class", "continue", "do", "else", "false", "for",
    "fun", "if", "in", "interface", "is", "null", "object", "package",
    "return", "super", "this", "throw", "true", "try", "typealias", "typeof",
    "val", "var", "when", "while", "it", "and", "or", "not",
}



def mask_comments(src):
    """Blank `//` and `/* */` comment text with spaces (newlines and string
    literals preserved), so masks keep every index aligned with `src`.

    [wide4-12, axis=parser-death] This used to be a hand-written single-level
    scanner: it ended a block comment at the FIRST `*/`, so Kotlin's nested
    form `/* outer /* inner */ ) */` leaked a `)` out of the comment and
    truncated the `data class` parameter list right there (witness w4-fw-5:
    the extra field after the comment was never checked). The shared
    `_kotlin_lexer` is a strictly conforming state machine (nested block
    comments, `'"'` char literals, triple-quoted raw strings, `${…}` templates) —
    and a lexing failure is FAIL-CLOSED: an unparsed file is a violation, not
    a silent "return the source unchanged" that reports itself clean.
    """
    try:
        ok, reason, views = analyse(src)
    except KotlinLexError as exc:          # pragma: no cover - defensive
        ok, reason, views = False, str(exc), None
    if not ok or views is None:
        PARSE_FAILURES.append(
            "无法解析（%s）—— 门拒绝在残缺词法上作判断 (fail-closed)" % reason)
        return ""
    return views["comments"]


# ── Expression normalisation: "is this value a literal in disguise?" ───────
# [wide4-12, axis=family-hole] The old criterion only removed string/char
# literals and then asked "is any identifier left that is not in
# CONST_IDS?". Enumerating the allowed identifiers is a blacklist: every
# rewrite that keeps an identifier on the page — `if (false) A else B`,
# `idx * 0`, `when (false) …`, `x == x`, `"${0}".toInt()` — reads as
# "derived" while the value is a fixed literal. The criterion is now
# POSITIVE: an expression is constant only when it provably folds to a
# literal and carries no identifier that stems from the source object.
def _strip_outer(s):
    s = s.strip()
    while len(s) >= 2 and s[0] == "(" and s[-1] == ")":
        depth = 0
        inner = -1
        for i, ch in enumerate(s):
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    inner = i
                    break
        if inner != len(s) - 1:
            break
        s = s[1:-1].strip()
    return s


def _match_paren(s, open_idx):
    """Index of the `)` matching `s[open_idx] == '('` (-1 when unbalanced)."""
    depth = 0
    for i in range(open_idx, len(s)):
        ch = s[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i
    return -1


def _split_top(s, op):
    """Split at depth-0 occurrences of `op` (string contents are masked first
    so an operator inside a literal cannot split the expression)."""
    masked = mask_strings(s)
    parts, depth, start, i = [], 0, 0, 0
    while i < len(masked):
        ch = masked[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif depth == 0 and masked.startswith(op, i):
            parts.append(s[start:i])
            i += len(op)
            start = i
            continue
        i += 1
    parts.append(s[start:])
    return parts


def _skeleton(value):
    """Drop literal text, KEEP what the literal interpolates.

    `"fixed"` → nothing (constant). `"${inst.id}"` → `inst.id` (reads the
    source). `"${0}".toInt()` → `(0).toInt()` (folds). `$sessionId` inside a
    template counts as reading `sessionId` — ignoring it was the other half of
    the old `"${" in value` shortcut.
    """
    out = []
    i, n = 0, len(value)
    while i < n:
        if value[i] == '"':
            j = i + 1
            buf = []
            while j < n and value[j] != '"':
                if value[j] == "\\":
                    j += 2
                    continue
                if value.startswith("${", j):
                    depth, k = 1, j + 2
                    while k < n and depth:
                        if value[k] == "{":
                            depth += 1
                        elif value[k] == "}":
                            depth -= 1
                        k += 1
                    buf.append(" (" + _skeleton(value[j + 2:k - 1]) + ") ")
                    j = k
                    continue
                m = re.match(r"\$([A-Za-z_]\w*)", value[j:])
                if m:
                    buf.append(" " + m.group(1) + " ")
                    j += len(m.group(0))
                    continue
                j += 1
            out.append(" " + "".join(buf) + " ")
            i = j + 1
            continue
        if value[i] == "'":
            j = i + 1
            while j < n:
                if value[j] == "\\":
                    j += 2
                    continue
                if value[j] == "'":
                    break
                j += 1
            out.append(" ")
            i = j + 1
            continue
        out.append(value[i])
        i += 1
    return "".join(out)


def _const_bool(expr):
    """True / False when the expression is a literal decision, else None.

    `x == x` / `1 == 1` count as literal (they decide nothing about the
    source), which is what the witness `if (1 == 1) …` leaned on.
    """
    s = _strip_outer(expr)
    if s == "true":
        return True
    if s == "false":
        return False
    if s.startswith("!") and not s.startswith("!="):
        inner = _const_bool(s[1:])
        return None if inner is None else (not inner)
    for op, dead, alive in (("||", True, False), ("&&", False, True)):
        parts = _split_top(s, op)
        if len(parts) > 1:
            vals = [_const_bool(p) for p in parts]
            if dead in vals:
                return dead
            if all(v is alive for v in vals):
                return alive
            return None
    for op in ("==", "!="):
        parts = _split_top(s, op)
        if len(parts) == 2:
            a, b = _strip_outer(parts[0]), _strip_outer(parts[1])
            if a == b and a:
                return True if op == "==" else False
            if re.fullmatch(r"-?\d+", a) and re.fullmatch(r"-?\d+", b):
                same = int(a) == int(b)
                return same if op == "==" else (not same)
            if re.fullmatch(r'"[^"]*"', a) and re.fullmatch(r'"[^"]*"', b):
                same = a == b
                return same if op == "==" else (not same)
            return None
    return None


def _if_arms(rest):
    """(then_arm, else_arm) of the tail after `if (cond)`; None when the tail
    is not a recognised if/else shape."""
    rest = rest.strip()
    if not rest:
        return None
    if rest.startswith("{"):
        end = _match_brace_src(rest, 0)
        if end < 0:
            return None
        then_arm = rest[1:end]
        tail = rest[end + 1:].strip()
    else:
        depth = 0
        split_at = -1
        for i, ch in enumerate(rest):
            if ch in "([{":
                depth += 1
            elif ch in ")]}":
                depth -= 1
            elif depth == 0 and rest.startswith("else", i):
                split_at = i
                break
        if split_at < 0:
            return rest, None
        then_arm = rest[:split_at]
        tail = rest[split_at + 4:].strip()
    if not tail:
        return then_arm, None
    if tail.startswith("if"):
        # `else if (…) …` — the nested if-expression IS the else arm, and
        # `_is_const_expr` folds it recursively.
        return then_arm, tail
    return then_arm, tail


def _match_brace_src(s, open_idx):
    depth = 0
    for i in range(open_idx, len(s)):
        if s[i] == "{":
            depth += 1
        elif s[i] == "}":
            depth -= 1
            if depth == 0:
                return i
    return -1


def _is_literal_zero(expr):
    s = _strip_outer(expr)
    return bool(re.fullmatch(r"[+-]?0+(?:\.0*)?[LlFfDd]?", s))


def _is_const_expr(expr, guard=0):
    s = _strip_outer(_skeleton(expr).strip())
    if not s:
        return True                       # pure literal text
    if guard > 6:
        return False                      # cannot prove → treat as derived
    # `if (cond) A else B` — a literal cond selects ONE arm; the other arm is
    # dead code and must not lend its identifiers to the value.
    m = re.match(r"if\s*\(", s)
    if m:
        end = _match_paren(s, m.end() - 1)
        if end > 0:
            cond = _const_bool(s[m.end():end])
            arms = _if_arms(s[end + 1:])
            if cond is not None and arms is not None:
                live = arms[0] if cond else arms[1]
                if live is None or not live.strip():
                    return True
                return _is_const_expr(live, guard + 1)
    # `when (<literal>) { … }` — a literal subject decides the branch.
    m = re.match(r"when\s*\(", s)
    if m:
        end = _match_paren(s, m.end() - 1)
        if end > 0 and _const_bool(s[m.end():end]) is not None:
            body = s[end + 1:].strip()
            if body.startswith("{"):
                b_end = _match_brace_src(body, 0)
                if b_end > 0:
                    return _when_is_const(body[1:b_end], guard)
    if s.startswith("!"):
        return _const_bool(s) is not None
    # boolean composition
    for op, dead in (("||", True), ("&&", False)):
        parts = _split_top(s, op)
        if len(parts) > 1:
            vals = [_const_bool(p) for p in parts]
            if dead in vals:
                return True               # the whole expression is a literal
            if all(v is True for v in vals):
                return True
            return all(_is_const_expr(p, guard + 1) for p in parts)
    # arithmetic: `idx * 0` is zero, and an all-literal sum is a literal
    for op in ("*", "/", "%", "+", "-"):
        parts = _split_top(s, op)
        if len(parts) > 1:
            if op == "*" and any(_is_literal_zero(p) for p in parts):
                return True               # annihilation
            if all(_is_const_expr(p, guard + 1) for p in parts):
                return True
            return False
    # trailing pure-call chain on a constant receiver: `"${0}".toInt()`
    for m in re.finditer(r"\.([A-Za-z_]\w*)\s*\(", s):
        if _match_paren(s, m.end() - 1) == len(s) - 1:
            recv = s[:m.start()]
            args = s[m.end():len(s) - 1]
            if _is_const_expr(recv, guard + 1) and all(
                    _is_const_expr(a, guard + 1)
                    for a in _split_top(args, ",") if a.strip()):
                return True
            return False
    masked = mask_strings(s)
    idents = set(re.findall(r"[A-Za-z_]\w*", masked))
    calls = set(re.findall(r"\b([A-Za-z_]\w*)\s*\(", masked))
    return not (idents - calls - KOTLIN_KEYWORDS)


def _when_is_const(body, guard):
    """`when (<literal>) { … }` — the selected branch decides the value."""
    branches = _split_top(body, ";")
    if len(branches) == 1:
        # branch separators may be newlines (Kotlin does not need `;`)
        branches = [b for b in re.split(r"\n(?=\s*(?:else|[^.\n]*->))", body)]
    for branch in branches:
        if "->" not in branch:
            continue
        head, _, tail = branch.partition("->")
        labels = [l.strip() for l in _split_top(head, ",")]
        if "else" in labels:
            return _is_const_expr(tail, guard + 1)
    return False


def value_is_constant(value):
    """True when an argument value cannot carry anything out of the source
    object.

    `pinned = 0` keeps the argument NAME on the page while the user's value
    never reaches the row (witness G2/dataflow). [wide4-12] family holes —
    `if (false) (if (w.pinned) 1 else 0) else 0`, `"${0}".toInt()`,
    `idx * 0`, `when (false) { … }` — are folded first, so keeping an
    identifier on the page no longer buys a pass.
    """
    return _is_const_expr(value)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def balanced(src, start, open_ch, close_ch):
    """Return index of the close_ch matching src[start] == open_ch, or -1.

    An unbalanced walk is recorded as a parse failure — returning "not found"
    silently is how a truncated scan surface reads as a clean tree.
    """
    depth = 0
    i = start
    while i < len(src):
        c = src[i]
        if c == open_ch:
            depth += 1
        elif c == close_ch:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    PARSE_FAILURES.append(
        "unbalanced %r…%r from line %d — the shape could not be parsed"
        % (open_ch, close_ch, src.count("\n", 0, start) + 1))
    return -1


def extract_data_class_fields(src, cls_name):
    """Fields (val/var names) of `data class <cls_name>( ... )` — ctor only."""
    m = re.search(r"data\s+class\s+" + re.escape(cls_name) + r"\s*\(", src)
    if not m:
        return None
    end = balanced(src, m.end() - 1, "(", ")")
    if end < 0:
        return None
    body = src[m.end():end]
    return set(re.findall(r"\b(?:val|var)\s+(\w+)\s*:", body))


def extract_defaulted_fields(src, cls_name):
    """Fields of `data class <cls_name>` that declare a default (`= …`).
    A fully positional ctor call can legally omit only these."""
    m = re.search(r"data\s+class\s+" + re.escape(cls_name) + r"\s*\(", src)
    if not m:
        return set()
    end = balanced(src, m.end() - 1, "(", ")")
    if end < 0:
        return set()
    body = src[m.end():end]
    # [review G2] `[^=\n]*` — a bare `[^=]*` runs across lines and swallows the
    # NEXT field's `=`, mis-flagging the previous field as defaulted (e.g.
    # `val key: String,\nval value: String = "x"` reported 'key' instead of
    # 'value'), which silently voids the positional-branch gate.
    return set(re.findall(r"\b(?:val|var)\s+(\w+)\s*:[^=\n]*=\s", body))


def _split_args(body):
    """Split a ctor argument list on top-level commas."""
    parts = []
    depth = 0
    start = 0
    for i, ch in enumerate(body):
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "," and depth == 0:
            parts.append(body[start:i])
            start = i + 1
    parts.append(body[start:])
    return parts


def collect_ctor_sites(files, cls_name):
    """All `cls_name(` call sites across files, skipping the file that
    declares the class itself (the data-class header would otherwise be
    parsed as a call and default values as named args).

    Returns (named_args_union, sites) where sites =
    [(relpath, line, args_set, {arg: value_text})]. Values are kept because
    "the argument is named" is not the criterion — "the value is carried
    from the source object" is (witness G2/dataflow: `pinned = 0`).
    """
    decl = re.compile(r"\bclass\s+" + re.escape(cls_name) + r"\b")
    call = re.compile(r"(?<![\w])" + re.escape(cls_name) + r"\s*\(")
    args = set()
    sites = []
    for rel, src in sorted(files.items()):
        if decl.search(src):
            continue
        for m in call.finditer(src):
            end = balanced(src, m.end() - 1, "(", ")")
            if end < 0:
                continue
            body = src[m.end():end]
            values = {}
            for part in _split_args(body):
                am = re.match(r"\s*(\w+)\s*=(?!=)\s*(.*)\Z", part, re.S)
                if am:
                    values[am.group(1)] = am.group(2).strip()
            site_args = set(values)
            args.update(site_args)
            sites.append((rel, src.count("\n", 0, m.start()) + 1, site_args, values))
    return args, sites


def mapping_shaped_sites(sites, target_fields):
    """Sites that map a family rather than synthesise one.

    A mapping site writes at least half of the target's fields; a synthetic
    construction (`ProviderInstance(label = "x", pinned = false)` in an offload
    worker) writes a handful and is not the mapping this gate guards.
    """
    out = []
    for site in sites:
        covered = sum(1 for a in site[2] if a in target_fields)
        if covered >= 2 and covered * 2 >= len(target_fields):
            out.append(site)
    return out


def check_derivation(family, col_to_model, todb_sites, fromdb_sites,
                     entity_fields, model_fields):
    """Every four-way field must be CARRIED by at least one mapping site.

    A field set to a constant at every mapping-shaped site is the G2/dataflow
    witness: the argument name is still there, the value never travels. A
    field carried by at least one mapping site stays silent — the gate does
    not police how synthetic constructions fill their own defaults.
    """
    out = []
    for direction, sites, target_fields, fields in (
        ("to-DB", todb_sites, entity_fields, sorted(col_to_model)),
        ("from-DB", fromdb_sites, model_fields,
         sorted({col_to_model[c] for c in col_to_model})),
    ):
        mapping = mapping_shaped_sites(sites, target_fields)
        if not mapping:
            continue
        for field in fields:
            values = [s[3][field] for s in mapping if field in s[3]]
            if not values:
                continue
            if all(value_is_constant(v) for v in values):
                at = ", ".join("%s:%d" % (s[0], s[1]) for s in mapping if field in s[3])
                out.append(
                    "%s: %s `%s` is a constant at every mapping site (%s) — the "
                    "value never leaves the source object, so it is lost on every "
                    "%s" % (family, direction, field, at,
                            "save" if direction == "to-DB" else "load"))
    return out


def entity_self_const_gaps(entity_name, sites, entity_fields):
    """Columns written as a literal at EVERY ctor site that sets them.

    [wide4-12 w4-fw-3, axis=completeness] Same positive criterion as
    `check_derivation`, applied to the entity-self families (chat core tables
    included). A column absent from some sites may legitimately rely on the
    column default, so only "set everywhere, literal everywhere" is a gap; a
    by-design literal is pinned in ENTITY_CONST_PINS.
    """
    out = []
    for field in sorted(entity_fields):
        carrier = [(rel, line, vals[field])
                   for rel, line, _args, vals in sites if field in vals]
        if not carrier or len(carrier) < len(sites):
            continue
        if all(value_is_constant(v) for _r, _l, v in carrier):
            if (entity_name, field) in ENTITY_CONST_PINS:
                continue
            at = ", ".join("%s:%d" % (r, l) for r, l, _v in carrier)
            out.append(
                "%s: constructor column `%s` is a constant at every constructor "
                "site (%s) — the value never travels, so it is lost on every save "
                "(pin it in ENTITY_CONST_PINS with a reason if the literal IS the "
                "design)" % (entity_name, field, at))
    return out


def discover_entities(db_dir_rel, root):
    """Every `data class *Entity` in data/db/ → {entity_name: fields}."""
    entities = {}
    db_path = os.path.join(root, KOTLIN, db_dir_rel)
    if not os.path.isdir(db_path):
        return entities
    for fn in sorted(os.listdir(db_path)):
        if not fn.endswith(".kt"):
            continue
        src = mask_comments(read(os.path.join(db_path, fn)))
        for m in re.finditer(r"data\s+class\s+(\w*Entity)\s*\(", src):
            name = m.group(1)
            fields = extract_data_class_fields(src, name)
            if fields is not None:
                entities[name] = fields
    return entities


def load_kotlin_files(root):
    """Every production .kt under the app package, comment-blanked.

    Masking happens once at load so no criterion can read prose as code.
    """
    files = {}
    base = os.path.join(root, KOTLIN)
    for dirpath, dirnames, filenames in os.walk(base):
        for name in filenames:
            if name.endswith(".kt"):
                rel = os.path.relpath(os.path.join(dirpath, name), base)
                files[rel] = mask_comments(read(os.path.join(dirpath, name)))
    return files


def run_checks(root):
    """Print the per-family report; return 0 = clean, 1 = real gaps."""
    PARSE_FAILURES.clear()
    problems = 0
    files = load_kotlin_files(root)
    entities = discover_entities(DB_DIR, root)

    checked = []
    entity_self_checked = []
    unresolved = []  # (entity, reason)
    for entity_name in sorted(entities):
        entity_fields = entities[entity_name]

        # ── v2.1: entity-self families (no domain data class exists) ──
        if entity_name in ENTITY_SELF_FAMILIES:
            site_args, sites = collect_ctor_sites(files, entity_name)
            if not sites:
                unresolved.append(
                    (entity_name, "entity-self family but no constructor "
                     "site found — columns unchecked")
                )
                continue
            entity_self_checked.append(entity_name)
            dao = DAO_WRITTEN.get(entity_name, set())
            positional = entity_name in POSITIONAL_ONLY_SITES
            if positional:
                # Every site assigns by ORDER — a site with zero extractable
                # named args covers all non-defaulted fields by position.
                entity_src = read(os.path.join(
                    root, KOTLIN, DB_DIR, entity_name + ".kt"))
                defaulted = extract_defaulted_fields(entity_src, entity_name)
                site_args = entity_fields - defaulted
                print(f"\n═══ {entity_name} — entity-self check "
                      f"(positional ctor sites) ═══")
            else:
                print(f"\n═══ {entity_name} — entity-self check "
                      f"(no domain data class) ═══")
            print(f"  entity: {DB_DIR}/{entity_name}.kt · {len(sites)} ctor site(s)"
                  + (f" · DAO-written pins: {sorted(dao)}" if dao else ""))
            print(f"  Entity ({len(entity_fields)}): {sorted(entity_fields)}")
            if not positional:
                partial = {
                    f: (sum(1 for _, _, a, _v in sites if f in a), len(sites))
                    for f in sorted(entity_fields & site_args)
                    if sum(1 for _, _, a, _v in sites if f in a) < len(sites)
                }
                for f, (n, total) in partial.items():
                    print(f"  ℹ set by {n}/{total} site(s) (OK if the other sites "
                          f"rely on the column default): {f}")
            never = entity_fields - site_args - dao
            if never:
                problems += 1
                print(f"  ❌ Column never written by ANY constructor site and not "
                      f"DAO-written-pinned (saved as its default forever — if that "
                      f"is intended, pin it in DAO_WRITTEN): {sorted(never)}")
            else:
                print(f"  ✅ Every column written by some constructor site "
                      f"(or pinned DAO-written)")
            # [wide4-12 w4-fw-3] written-at-all is not the same as CARRIED:
            # `summary = ""` keeps every column name on the page while the
            # model's summary never reaches the row.
            if not positional:
                for msg in entity_self_const_gaps(entity_name, sites, entity_fields):
                    problems += 1
                    print(f"  ❌ {msg}")
                for (ent, fld), reason in sorted(ENTITY_CONST_PINS.items()):
                    if ent != entity_name:
                        continue
                    vals = [val_map[fld] for _r, _l, _a, val_map in sites
                            if fld in val_map]
                    if vals and all(value_is_constant(v) for v in vals):
                        print(f"  ℹ constant-by-design pin: {fld} — {reason}")
            continue

        model_name = entity_name[: -len("Entity")]
        model_name = FAMILY_MODEL_ALIASES.get(entity_name, model_name)

        model_fields = None
        model_file = None
        for rel, src in sorted(files.items()):
            if re.search(r"data\s+class\s+" + re.escape(model_name) + r"\s*\(", src):
                model_fields = extract_data_class_fields(src, model_name)
                model_file = rel
                break
        if model_fields is None:
            unresolved.append(
                (entity_name, f"no data class '{model_name}' under {KOTLIN} "
                 "(domain shape not derivable — family unchecked; if the entity "
                 "IS its own domain shape, pin it in ENTITY_SELF_FAMILIES)")
            )
            continue

        todb_args, todb_sites = collect_ctor_sites(files, entity_name)
        fromdb_args, fromdb_sites = collect_ctor_sites(files, model_name)
        if not todb_sites:
            unresolved.append(
                (entity_name, "no constructor site writes the entity row "
                 "(mapping direction not found) — family unchecked")
            )
            continue
        if not fromdb_sites:
            unresolved.append(
                (entity_name, f"no constructor site builds '{model_name}' "
                 "back from rows (load direction not found) — family unchecked")
            )
            continue

        checked.append(entity_name)
        aliases = FIELD_ALIASES.get(model_name, {})
        print(f"\n═══ {model_name} — four-way sync check (derived) ═══")
        print(f"  model: {model_file} · entity: {DB_DIR}/{entity_name}.kt · "
              f"to-db: {len(todb_sites)} site(s) · from-db: {len(fromdb_sites)} site(s)")
        print(f"  Model ({len(model_fields)}): {sorted(model_fields)}")
        print(f"  Entity ({len(entity_fields)}): {sorted(entity_fields)}")

        missing_entity = set()
        for f in model_fields:
            if f in entity_fields:
                continue
            alias = aliases.get(f)
            if alias and alias in entity_fields:
                continue
            if (model_name, f) in KNOWN_MISMATCH:
                continue  # printed as WARNING below
            missing_entity.add(f)
        missing_todb = entity_fields - todb_args
        missing_fromdb = model_fields - fromdb_args
        missing_todb -= LEGIT_ENTITY_ONLY
        missing_fromdb -= LEGIT_ENTITY_ONLY

        # ── Derivation: a mapping site must CARRY the value, not name it ──
        # `pinned = 0` keeps the argument name on the page while the user's
        # setting never reaches the row (witness G2/dataflow). KNOWN_MISMATCH
        # fields have no 1:1 column by design, so they are not in this set.
        col_to_model = {}
        for f in model_fields:
            if (model_name, f) in KNOWN_MISMATCH:
                continue
            col = aliases.get(f, f)
            if col in entity_fields:
                col_to_model[col] = f
        for col in entity_fields:
            if col in model_fields and col not in col_to_model:
                col_to_model[col] = col
        derived_gaps = check_derivation(
            f"{model_name}/{entity_name}", col_to_model, todb_sites, fromdb_sites,
            entity_fields, model_fields)

        for (grp, fld), reason in sorted(KNOWN_MISMATCH.items()):
            if grp == model_name and fld in model_fields:
                print(f"  ⚠ KNOWN-MISMATCH (pinned, by design): {grp}.{fld} — {reason}")

        if not missing_entity and not missing_todb and not missing_fromdb \
                and not derived_gaps:
            print(f"  ✅ All four layers consistent")
        else:
            if missing_entity:
                problems += 1
                print(f"  ❌ Model has but Entity missing (data lost on save): {sorted(missing_entity)}")
            if missing_todb:
                problems += 1
                print(f"  ❌ Entity has but toSnapshot missing (to-DB site doesn't set it — lost on every save): {sorted(missing_todb)}")
            if missing_fromdb:
                problems += 1
                print(f"  ❌ Model has but toProviderConfig missing (from-DB site doesn't restore it — lost on every load): {sorted(missing_fromdb)}")
            for msg in derived_gaps:
                problems += 1
                print(f"  ❌ {msg}")

    # ── Coverage table: the blind spot must be printed, not silent ─────────
    print(f"\n{'='*60}")
    print(f"Coverage: derived {len(entities)} entities · checked {len(checked)} "
          f"+ entity-self {len(entity_self_checked)} "
          f"(total {len(checked) + len(entity_self_checked)}) "
          f"· unresolved {len(unresolved)}")
    if unresolved:
        print("UNRESOLVED (visible, not failures — families NOT field-checked):")
        for name, reason in unresolved:
            print(f"  - {name}: {reason}")
    if PARSE_FAILURES:
        # fail-closed: an unparsed shape shrinks the checked surface, and an
        # empty surface reports itself clean.
        unique = []
        for msg in PARSE_FAILURES:
            if msg not in unique:
                unique.append(msg)
        problems += len(unique)
        print("❌ PARSE FAILURES (fail-closed — an unparsed shape is a violation):")
        for msg in unique:
            print(f"  - {msg}")
    if not entities:
        print("!! No Room entities found — scanner is blind on this tree.")
    print(f"Done: {'PROBLEMS FOUND: ' + str(problems) if problems else 'ALL CLEAN ✅'} "
          f"[checked {len(checked) + len(entity_self_checked)}/{len(entities)}]")

    if not entities:
        return 1  # fail loud: a tree with no derivable families is a rot signal
    return 1 if problems else 0


# ── Selftest: one arm per criterion this gate claims, including the wide3
# witnesses that blinded it (commented-out mapping, constant value, comment
# paren truncating the parameter list). ──────────────────────────────────────
FW_MODEL = """package com.rikkaminis.app.data.model

data class Widget(
    val id: String,
    val name: String,
)
"""

FW_ENTITY = """package com.rikkaminis.app.data.db

data class WidgetEntity(
    val id: String,
    val name: String,
)
"""

FW_MAPPING = """package com.rikkaminis.app.data.db

fun toEntity(w: Widget): WidgetEntity =
    WidgetEntity(
        id = w.id,
        name = w.name,
    )

fun toModel(e: WidgetEntity): Widget =
    Widget(
        id = e.id,
        name = e.name,
    )
"""


def _selftest_run(model, entity, mapping):
    import contextlib
    import io
    import shutil
    import tempfile
    tmp = tempfile.mkdtemp(prefix="fws-")
    try:
        base = os.path.join(tmp, KOTLIN)
        for rel, content in (("data/model/Widget.kt", model),
                             ("data/db/WidgetEntity.kt", entity),
                             ("data/db/WidgetMapping.kt", mapping)):
            path = os.path.join(base, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = run_checks(tmp)
        return rc, buf.getvalue()
    finally:
        shutil.rmtree(tmp)


def _selftest_tree(files):
    """Run the gate over an arbitrary {relative path: text} fixture tree."""
    import contextlib
    import io
    import shutil
    import tempfile
    tmp = tempfile.mkdtemp(prefix="fws-")
    try:
        for rel, content in files.items():
            path = os.path.join(tmp, KOTLIN, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = run_checks(tmp)
        return rc, buf.getvalue()
    finally:
        shutil.rmtree(tmp)


# ── wide4-12 arms: entity-self + lexer fixtures ───────────────────────────
FW4_MARKER_ENTITY = """package com.rikkaminis.app.data.db

data class CompactMarkerEntity(
    val id: String,
    val sessionId: String,
    val summary: String,
)
"""

FW4_MARKER_SITE = """package com.rikkaminis.app.data.repository

fun mark(sessionId: String, summary: String) =
    CompactMarkerEntity(
        id = "m-$sessionId",
        sessionId = sessionId,
        summary = summary,
    )
"""



def selftest():
    ok = True

    def arm(label, model, entity, mapping, want_rc, needle=""):
        nonlocal ok
        rc, out = _selftest_run(model, entity, mapping)
        good = rc == want_rc and (not needle or needle in out)
        print("  selftest %-38s %s" % (label, "PASS" if good else "FAIL"))
        if not good:
            print("    expected rc=%d%s, got rc=%d\n%s"
                  % (want_rc, (" containing %r" % needle) if needle else "", rc,
                     "\n".join(l for l in out.splitlines() if "❌" in l or "PARSE" in l)[:600]))
            ok = False

    arm("clean four-way family", FW_MODEL, FW_ENTITY, FW_MAPPING, 0, "All four layers")

    # G2/parser-death: commented-out mapping still counted as mapped.
    arm("commented-out to-DB field caught",
        FW_MODEL, FW_ENTITY,
        FW_MAPPING.replace("        name = w.name,", "        // name = w.name,"),
        1, "toSnapshot missing")

    # G2/dataflow: the argument name survives, the value does not.
    arm("constant to-DB value caught",
        FW_MODEL, FW_ENTITY,
        FW_MAPPING.replace("        name = w.name,", "        name = \"fixed\","),
        1, "is a constant at every mapping site")

    arm("constant from-DB value caught",
        FW_MODEL, FW_ENTITY,
        FW_MAPPING.replace("        name = e.name,", "        name = \"fixed\","),
        1, "is a constant at every mapping site")

    # G1/parser-death: `// 1)` inside the parameter list closed the list early.
    arm("comment paren does not truncate the field list",
        FW_MODEL.replace("    val id: String,",
                         "    val id: String,\n    // 1) early paren in a comment\n"
                         "    val unmappedExtraField: Boolean = false,"),
        FW_ENTITY, FW_MAPPING, 1, "unmappedExtraField")

    # negative control: a balanced paren in a comment is normal prose.
    arm("balanced comment paren stays green",
        FW_MODEL.replace("    val id: String,",
                         "    val id: String,\n    // (kept for history)\n"),
        FW_ENTITY, FW_MAPPING, 0)

    # ── wide4-12 arms: the five family holes / parser-death witnesses ──────
    # w4-fw-1 dataflow: `if (false) A else B` keeps the identifier on the page
    # while a literal decides the row.
    arm("w4-fw-1 dead branch keeps the value literal",
        FW_MODEL, FW_ENTITY,
        FW_MAPPING.replace("        name = w.name,",
                           '        name = if (false) w.name else "fixed",'),
        1, "is a constant at every mapping site")

    # w4-fw-2 dataflow: `"${"` used to short-circuit the whole judgement, so a
    # constant template (and anything appended to it) read as "derived".
    arm("w4-fw-2 constant string template is a literal",
        FW_MODEL, FW_ENTITY,
        FW_MAPPING.replace("        name = w.name,",
                           '        name = "${"fixed"}",'),
        1, "is a constant at every mapping site")

    # w4-fw-4 dataflow: an identifier from the ENVIRONMENT (the map index,
    # multiplied by zero) is not the source field.
    arm("w4-fw-4 annihilated index is a literal",
        FW_MODEL, FW_ENTITY,
        FW_MAPPING.replace("        name = w.name,", "        name = idx * 0,"),
        1, "is a constant at every mapping site")

    # w4-fw-5 parser-death: Kotlin nests block comments; a single-level masker
    # leaked the `)` out of `/* outer /* inner */ ) */` and truncated the
    # parameter list exactly there.
    arm("w4-fw-5 nested block comment paren",
        FW_MODEL.replace("    val id: String,",
                         "    val id: String, /* outer /* inner */ ) */\n"
                         "    val unmappedExtraField: Boolean = false,"),
        FW_ENTITY, FW_MAPPING, 1, "unmappedExtraField")

    # precision twin: the SAME nested comment with no fake paren stays green.
    arm("w4-fw-5b nested comment without a fake paren",
        FW_MODEL.replace("    val id: String,",
                         "    val id: String, /* outer /* inner */ note */"),
        FW_ENTITY, FW_MAPPING, 0)

    # w4-fw-3 completeness: the entity-self families were only asked "is the
    # column written at all?", so `summary = ""` (the compaction summary
    # thrown away on every save) passed.
    def arm_tree(label, files, want_rc, needle=""):
        nonlocal ok
        rc, out = _selftest_tree(files)
        good = rc == want_rc and (not needle or needle in out)
        print("  selftest %-38s %s" % (label, "PASS" if good else "FAIL"))
        if not good:
            print("    expected rc=%d%s, got rc=%d\n%s"
                  % (want_rc, (" containing %r" % needle) if needle else "", rc,
                     "\n".join(l for l in out.splitlines()
                               if "❌" in l or "PARSE" in l)[:600]))
        ok = ok and good

    arm_tree("w4-fw-3 entity-self clean twin",
             {"data/db/CompactMarkerEntity.kt": FW4_MARKER_ENTITY,
              "data/repository/Marker.kt": FW4_MARKER_SITE}, 0)
    arm_tree("w4-fw-3 entity-self constant column",
             {"data/db/CompactMarkerEntity.kt": FW4_MARKER_ENTITY,
              "data/repository/Marker.kt":
                  FW4_MARKER_SITE.replace("summary = summary,",
                                          'summary = "",')},
             1, "is a constant at every constructor site")

    # w4-fw-6 parser fail-closed: an unterminated block comment must not be
    # silently reported clean (that is how a poisoned tree shrinks the surface).
    arm_tree("w4-fw-6 unlexable file is a violation",
             {"data/db/WidgetEntity.kt": FW_ENTITY,
              "data/model/Widget.kt": FW_MODEL,
              "data/db/WidgetMapping.kt": FW_MAPPING + "\n/* unterminated\n"},
             1, "无法解析")


    if ok:
        print("FOUR_WAY_SYNC_SELFTEST=PASS")
        return 0
    print("FOUR_WAY_SYNC_SELFTEST=FAIL")
    return 1


def main():
    argv = sys.argv[1:]
    if "--selftest" in argv:
        return selftest()
    positional = [a for a in argv if not a.startswith("--") and a != "root"]
    if "--root" in argv:
        i = argv.index("--root")
        root = argv[i + 1] if i + 1 < len(argv) else "."
    else:
        root = positional[0] if positional else "."
    if not os.path.isdir(root):
        print("repo root not found: %s" % root, file=sys.stderr)
        return 2
    return run_checks(root)


if __name__ == "__main__":
    sys.exit(main())
