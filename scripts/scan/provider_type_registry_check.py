#!/usr/bin/env python3
"""Gate: [ARCH-03] ProviderType metadata must be COMPILE-TIME exhaustive and
ModelsDevApi.providerKeyMap must actually derive from it.

Background — the F-212 / 1026-ISSUE-04 family (recurred 3 rounds):
`ProviderType` (data/model/ProviderConfig.kt) and the string-keyed
`ModelsDevApi.providerKeyMap` had no compile-time link, so every new vendor
silently fell out of the map:
  * F-212  — xAI / kimiCode were added to the enum and nobody added map keys;
             their 14 models fell through to the order-dependent
             all-providers scan (grok-4.5 took aihubmix's 1,000,000 context,
             kimi-k3 took a 5-tier effort ladder from greenpt).
  * 1026-D — the fix added the short keys but missed the *displayName* keys
             ("Google Gemini" / "xAI (Grok)" / "Kimi Code") that every add
             surface actually persists.
ARCH-03 removes the failure mode entirely: `modelsDevProviderIds` and
`legacyProviderNames` are exhaustive `when (this)` properties on the enum, so
a new vendor fails COMPILATION until it is mapped, and `providerKeyMap` is the
literal table MERGED with the table derived from those properties.

This Python gate cannot run the Kotlin compiler; it locks the two things that
make the compile-time guarantee real, i.e. that the wiring is present and
cannot be quietly dropped:

  Arm 1  ProviderConfig.kt — for `modelsDevProviderIds` and
         `legacyProviderNames`:
           a) both copied property declarations exist and their `when (this)`
              bodies contain NO `else ->` branch (an `else` silently disarms
              the exhaustiveness check — present but toothless)
           b) every `ProviderType` enum constant declared in the file is
              covered by BOTH `when` bodies (this is the arm that fires the
              moment a new vendor is added; the compiler fires in parallel)
         Fail-closed: enum header or either property missing ⇒ red.

  Arm 2  ModelsDevApi.kt:
           a) imports the three ProviderType symbols
           b) `providerKeyMap`'s initializer merges the derived table
              (ProviderType.values()/entries + flatMap + modelsDevProviderIds +
              legacyProviderNames + toMap) — checked on comment-stripped text
              so prose in a KDoc cannot satisfy it
           c) the explicit literal table inside `providerKeyMap = mapOf(...)`
              still carries all ten historical keys (Anthropic / Google /
              OpenAI / OpenRouter / Antigravity / xAI / Kimi / Google Gemini /
              xAI (Grok) / Kimi Code) — the merge must be additive, and the
              literal anchors stay readable for source-text gates and humans.

Usage: python3 scripts/scan/provider_type_registry_check.py [root]
       python3 scripts/scan/provider_type_registry_check.py --root <root>
       python3 scripts/scan/provider_type_registry_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage/ROT
"""
import argparse
import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import KotlinLexError, mask_code  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool,
    const_value,
    match_bracket,
    split_top,
    strip_parens,
)
from _kotlin_valueflow import code_view, literal_view, value_sources  # noqa: E402

APP = "src/android/app/src/main/java/com/rikkaminis/app"
PC_REL = APP + "/data/model/ProviderConfig.kt"
MD_REL = APP + "/provider/ModelsDevApi.kt"

ENUM_HEADER = "enum class ProviderType(val displayName: String)"
DERIVED_PROPS = ("modelsDevProviderIds", "legacyProviderNames")

MD_IMPORTS = (
    "import com.rikkaminis.app.data.model.ProviderType",
    "import com.rikkaminis.app.data.model.modelsDevProviderIds",
    "import com.rikkaminis.app.data.model.legacyProviderNames",
)
# Merge wiring tokens. The enum-iteration call accepts both spellings so the
# gate does not become a style cop.
MERGE_TOKENS = ("flatMap", "modelsDevProviderIds", "legacyProviderNames", ".toMap()")
ENUM_ITER_RE = re.compile(r"ProviderType\s*\.\s*(?:values\(\)|entries)")
# [wide-1024] A branch whose body is a statically EMPTY list disarms the
# mapping just as effectively as a missing branch: `ProviderType.xAI ->
# emptyList()` keeps the `when` exhaustive (the compiler is happy) while the
# family silently falls back to the order-dependent all-providers scan — the
# exact F-212 failure this file's docstring opens with. A vendor that truly
# has no models.dev row must be listed here, so the decision is explicit.
EMPTY_MAPPING_RE = re.compile(
    r"\b(?:emptyList|emptySet|emptyArray|listOf|setOf|arrayOf)\s*"
    r"(?:<[^<>]*>\s*)?\(\s*\)")
# [batch6-t06] A collection can be emptied AFTER construction, one operator
# later: `listOf("a", "b").filter { false }` / `.take(0)` / `.drop(n)` is the
# same zero mapping as `listOf()` — it just happens at evaluation time instead
# of at construction time. Judging the constructor only sees the first shape.
TRUNC_RE = re.compile(r"\.\s*(?:take|drop|filter|filterNot|takeWhile|dropWhile)\s*[({]")
# A single blank element is not an empty list, but it is not a provider id
# either: every lookup for the family then asks models.dev for "" and misses.
BLANK_ELEMENT_RE = re.compile(r'"\s*"')
LIST_CTOR_RE = re.compile(
    r"\b(?:emptyList|emptySet|emptyArray|listOf|setOf|arrayOf)\s*"
    r"(?:<[^<>]*>\s*)?\(")
ALLOW_EMPTY_MAPPINGS = frozenset()
# [wide-1024] The derived key set must still carry the displayName keys: the
# add surfaces (`provider.models.add`, AddCustomModelScreen,
# ModelsCollection.add) persist `ProviderType.displayName`, and for
# Gemini / xAI / KimiCode that string differs from both `name` and the
# legacy names, so dropping it is 1026-D all over again.
DERIVED_KEYS_RE = re.compile(r"\.\s*(?:map|mapNotNull|associate)\s*\{")
BOUND_IDS_RE = re.compile(r"\bval\s+(\w+)\s*=\s*[\w.]*\.?\s*modelsDevProviderIds\b")
# Historical literal keys that must survive inside the mapOf(...) body.
BASE_KEYS = ("Anthropic", "Google", "OpenAI", "OpenRouter", "Antigravity",
             "xAI", "Kimi", "Google Gemini", "xAI (Grok)", "Kimi Code")



def code_only(text):
    """Comment-stripped text with STRING CONTENT kept (anchor checks).

    Delegates to the shared lexer: the old line-based stripper kept `/* … */`
    block comments alive, which is how a commented-out branch (or a commented
    import) still satisfied a presence criterion. `literal_view` blanks both
    comment forms and keeps literals, which is what the anchor checks need
    (`"Anthropic" to …`); structural judgements use `code_view` instead.
    """
    try:
        return literal_view(text)
    except KotlinLexError:
        return text


def _literal(src):
    """Literal view (comments blanked, string content kept) or None."""
    try:
        return literal_view(src)
    except KotlinLexError:
        return None


def _structure(src):
    """Code view (comments + literal bodies blanked) or None on parse failure."""
    try:
        return code_view(src)
    except KotlinLexError:
        return None




def enum_constants(src):
    r"""Constant names of `enum class ProviderType(...)`, or None when the
    declaration is gone (gate ROT).

    [batch6-t06] The old parser read ONE constant per line via a `^\s*NAME(`
    anchor, so appending a second constant to an existing line
    (`kimiCode("Kimi Code"), deepSeek("DeepSeek");`) was invisible — the gate
    kept claiming the enum was fully covered while Kotlin had a new subclass.
    The constant list is a comma-separated list, so it is parsed as one: code
    view (comments blanked, a commented-out constant cannot be born), then the
    region before the first top-level `;` (member declarations follow it),
    then `split_top(…, ',')`.
    """
    code = _structure(src)
    if code is None:
        return None
    i = code.find(ENUM_HEADER)
    if i < 0:
        return None
    brace = code.find("{", i)
    if brace < 0:
        return None
    end = match_bracket(code, brace)
    if end < 0:
        return None
    inner = code[brace + 1:end]
    head = split_top(inner, (";",))
    region = inner[head[0][0]:head[0][1]] if head else ""
    names = []
    for a, b in split_top(region, (",",)):
        chunk = region[a:b]
        m = re.match(r"\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?:\(|$)", chunk)
        if not m:
            continue
        name = m.group(1)
        if name in ("val", "var", "fun", "get", "set", "companion", "object",
                    "override", "private", "internal", "public"):
            continue
        if re.search(r"\b(?:val|var|fun)\b", chunk):
            continue
        names.append(name)
    return names or None


def branch_map(code, prop):
    """[(labels, (start, end))] of the `when (this)` in `val ProviderType.<prop>`.

    `code` must be the code view. Two layout facts the old arrow-to-arrow
    parser got wrong are handled here: a branch may carry SEVERAL labels
    (`ProviderType.xAI, ProviderType.kimiCode -> …`) and the label list may
    wrap, while a branch whose arrow was only in a block comment must not
    exist at all.
    """
    i = code.find("val ProviderType.%s" % prop)
    if i < 0:
        return None
    w = code.find("when (this)", i)
    if w < 0:
        return None
    brace = code.find("{", w)
    if brace < 0:
        return None
    close = match_bracket(code, brace)
    if close < 0:
        return None
    body = code[brace + 1:close]
    arrows = []
    depth = 0
    k = 0
    while k < len(body):
        ch = body[k]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "-" and depth == 0 and body.startswith("->", k):
            arrows.append(k)
            k += 2
            continue
        k += 1
    starts = [_enum_label_run_start(body, a) for a in arrows]
    out = []
    for idx, arrow in enumerate(arrows):
        run = body[starts[idx]:arrow]
        rhs_end = starts[idx + 1] if idx + 1 < len(arrows) else len(body)
        labels = [t for t in re.findall(r"(?:ProviderType\s*\.\s*)?\b([A-Za-z_]\w*)\b", run)
                  if t not in ("is", "in", "null")]
        out.append((labels, (brace + 1 + arrow + 2, brace + 1 + rhs_end)))
    return out


def _enum_label_run_start(body, arrow):
    """Start of the enum-label list ending at `arrow` (labels may wrap)."""
    start = body.rfind("\n", 0, arrow) + 1
    while start > 0:
        prev_end = start - 1
        prev_start = body.rfind("\n", 0, prev_end) + 1
        prev = body[prev_start:prev_end]
        if "->" in prev:
            break
        if not re.fullmatch(r"\s*(?:(?:ProviderType\s*\.\s*)?[A-Za-z_]\w*\s*"
                            r"(?:,\s*(?:ProviderType\s*\.\s*)?[A-Za-z_]\w*\s*)*,)?\s*",
                            prev):
            break
        start = prev_start
    return start


def map_value_state(code_expr, lit_expr):
    """'empty' | 'blank' | 'truncated' | 'ok' — the EFFECTIVE state of a
    branch's `List<String>` value.

    `listOf()` (construction) and `listOf("a").filter { false }` (evaluation)
    both hand the family zero ids; `listOf("")` hands it one id that matches
    nothing. Only the first shape was visible to an EMPTY_MAPPING_RE that
    inspected the constructor.
    """
    e = strip_parens(code_expr.strip())
    if not e:
        return "ok"
    for s in value_sources(e) or [e]:
        s = strip_parens(s.strip())
        if not s:
            continue
        if TRUNC_RE.search(s):
            return "truncated"
        if EMPTY_MAPPING_RE.search(s):
            return "empty"
    # Literal bodies are blanked in the code view (that is what makes the
    # structural judgements safe), so an empty provider id has to be read off
    # the literal projection at the same offsets.
    if BLANK_ELEMENT_RE.search(lit_expr):
        return "blank"
    return "ok"


def derivation(code):
    """Locate the enum-iterating merge inside `providerKeyMap`.

    `code` must be the code view. Returns a dict with the mapping body, the
    key expression, the pair value and the ids binding — or None when the
    derivation is gone. The gap between `ProviderType.values()` and the
    mapping operator is reported verbatim so a `.take(0)` inserted there
    cannot hide behind "the flatMap exists" (`ENUM_ITER_RE` still matches).
    """
    i = code.find("providerKeyMap")
    if i < 0:
        return None
    it = ENUM_ITER_RE.search(code, i)
    if not it:
        return None
    tail = code[it.end():]
    m = re.match(r"\s*(\.\s*(?:flatMap|map|mapNotNull|associate|associateBy)\s*\{)",
                 tail)
    if not m:
        nxt = re.search(r"\.\s*(\w+)\s*[({]", tail)
        return {"intercepted": nxt.group(1) if nxt else "", "body": None}
    brace = it.end() + m.end() - 1
    close = match_bracket(code, brace)
    if close < 0:
        return {"intercepted": "", "body": None}
    body = code[brace + 1:close]
    info = {"intercepted": "", "body": body, "keys_expr": None,
            "pair_value": None, "bound": BOUND_IDS_RE.findall(body)}
    km = DERIVED_KEYS_RE.search(body)
    if km is None:
        return info
    info["keys_expr"] = body[:km.start()]
    kbrace = km.end() - 1
    kclose = match_bracket(body, kbrace)
    if kclose < 0:
        return info
    pair = body[kbrace + 1:kclose]
    tm = re.search(r"\bto\s+(.+)", pair)
    info["pair_value"] = tm.group(1).strip() if tm else None
    return info







def map_literal_text(src):
    """The `providerKeyMap = mapOf(...)` literal body (comments blanked)."""
    code = code_only(src)
    i = code.find("providerKeyMap")
    if i < 0:
        return None
    open_idx = code.find("mapOf(", i)
    if open_idx < 0:
        return None
    close = match_bracket(code, open_idx + len("mapOf") )
    return None if close < 0 else code[open_idx:close + 1]

def read(rel, root):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return None
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def check_arm1(src, rel):
    out = []
    code = _structure(src)
    if code is None:
        return [(rel, 0, "1a",
                 "ProviderConfig.kt cannot be parsed by the shared Kotlin "
                 "lexer — fail-closed (an unreadable file must not pass the "
                 "mapping criteria)")]
    constants = enum_constants(src)
    if constants is None:
        out.append((rel, 0, "1a",
                    "`%s` not found — gate ROT" % ENUM_HEADER))
        return out
    for prop in DERIVED_PROPS:
        branches = branch_map(code, prop)
        if branches is None:
            out.append((rel, 0, "1b",
                        "`val ProviderType.%s` with a `when (this)` body not "
                        "found — the compile-time exhaustiveness contract is "
                        "gone (a new vendor would compile and silently fall out "
                        "of providerKeyMap)" % prop))
            continue
        labels = set()
        for names, _rhs in branches:
            labels.update(names)
        if "else" in labels:
            out.append((rel, 0, "1c",
                        "`%s` has an `else ->` branch — the `when` is no longer "
                        "exhaustive, so adding a ProviderType silently bypasses "
                        "the mapping instead of failing compilation" % prop))
        for c in constants:
            if c not in labels:
                out.append((rel, 0, "1d",
                            "`%s` does not cover ProviderType.%s — a new vendor "
                            "reached the enum without its models.dev mapping "
                            "(F-212 shape)" % (prop, c)))
        # Arm 1e [wide-1024 + batch6-t06]: covering the constant is not enough
        # — the branch must YIELD usable ids. `emptyList()` empties the mapping
        # at construction time, `listOf("a").filter { false }` / `.take(0)`
        # empty it at evaluation time, and `listOf("")` yields one id that
        # matches nothing. All three compile, all three keep the `when`
        # exhaustive, and all three make the family fall back to the
        # order-dependent all-providers scan (F-212).
        lit = _literal(src) or src
        for names, (a, b) in branches:
            rhs_lit = lit[a:b]
            state = map_value_state(code[a:b], rhs_lit)
            if state == "ok":
                continue
            for c in names:
                if c in ALLOW_EMPTY_MAPPINGS:
                    continue
                if state == "empty":
                    out.append((rel, 0, "1e",
                                "`%s` maps ProviderType.%s to an EMPTY list — the "
                                "`when` stays exhaustive, so this compiles, and the "
                                "family silently falls out of providerKeyMap (the "
                                "F-212 / 1026 shape: grok-4.5 takes a relay's "
                                "1,000,000 context). Give it its models.dev ids, or "
                                "add it to ALLOW_EMPTY_MAPPINGS in this gate with a "
                                "reason" % (prop, c)))
                elif state == "truncated":
                    out.append((rel, 0, "1e",
                                "`%s` maps ProviderType.%s to a list that is "
                                "emptied by a collection operator after "
                                "construction (`%s`) — `listOf(…).filter { false }` "
                                "/ `.take(0)` is the same zero mapping as "
                                "`listOf()`, one evaluation stage later"
                                % (prop, c,
                                   TRUNC_RE.search(code[a:b]).group(0).strip())))
                else:
                    out.append((rel, 0, "1e",
                                "`%s` maps ProviderType.%s to BLANK provider "
                                "id(s) (`%s`) — a single empty string is not an "
                                "empty list, but no models.dev row is keyed "", "
                                "so every lookup for the family misses and it "
                                "falls back to the relay scan (F-212)"
                                % (prop, c, rhs_lit.strip().splitlines()[0][:60]
                                   if rhs_lit.strip() else "")))
    return out

def check_arm2(src, rel):
    out = []
    code = _structure(src)
    if code is None:
        return [(rel, 0, "2a",
                 "ModelsDevApi.kt cannot be parsed by the shared Kotlin lexer — "
                 "fail-closed")]
    for imp in MD_IMPORTS:
        if imp not in code_only(src):
            out.append((rel, 0, "2a",
                        "missing `%s` — providerKeyMap cannot derive from the "
                        "ProviderType metadata without it" % imp))
    if not ENUM_ITER_RE.search(code):
        out.append((rel, 0, "2b",
                    "providerKeyMap does not iterate the enum "
                    "(ProviderType.values()/entries not found) — the derived "
                    "table was dropped; new vendors fall out of the key map"))
    for token in MERGE_TOKENS:
        if token not in code:
            out.append((rel, 0, "2b",
                        "providerKeyMap merge is missing `%s`" % token))
    info = derivation(code)
    if info is None:
        out.append((rel, 0, "2b",
                    "the enum-iterating `.flatMap { … }` of providerKeyMap is "
                    "missing — the derived table was dropped"))
    elif info["intercepted"] or info["body"] is None:
        out.append((rel, 0, "2b",
                    "the enum iteration is intercepted by `.%s` before the "
                    "mapping block — `ProviderType.values().take(0).flatMap { "
                    "… }` still matches `ProviderType.values()` and still "
                    "contains a flatMap, but the block never runs and the "
                    "derived table is empty"
                    % (info["intercepted"] or "?")))
    else:
        body = info["body"]
        if info["keys_expr"] is None:
            out.append((rel, 0, "2e",
                        "the derived table never builds key→ids pairs "
                        "(`.map { key -> … }` absent) — nothing is merged"))
        else:
            keys_expr = info["keys_expr"]
            # [batch6-t06] The key space is a SET; listing the right symbolic
            # sources is not the same as keeping them. `.filter { it !=
            # type.displayName }` keeps all three names in the text and drops
            # one whole key family from the value.
            trunc = TRUNC_RE.search(keys_expr)
            if trunc:
                out.append((rel, 0, "2d",
                            "the derived key chain runs through a truncation "
                            "operator (`%s`) — the key space is a set, and "
                            "filtering it (e.g. `filter { it != "
                            "type.displayName }`) silently drops a whole key "
                            "family while every source name stays in the text"
                            % trunc.group(0).strip()))
            for token, pat in (
                ("`type.displayName`", r"\bdisplayName\b"),
                ("`type.name`", r"\bname\b"),
                ("`type.legacyProviderNames`", r"\blegacyProviderNames\b"),
            ):
                if not re.search(pat, keys_expr):
                    out.append((rel, 0, "2d",
                                "the derived key set no longer includes %s — "
                                "every add surface persists displayName, so "
                                "\"Google Gemini\" / \"xAI (Grok)\" / \"Kimi "
                                "Code\" rows miss the map (1026-D)" % token))
            value = info["pair_value"]
            bound = info["bound"]
            if value is None:
                out.append((rel, 0, "2e",
                            "the derived pair expression does not bind a value "
                            "with `to` — the merge yields nothing usable"))
            else:
                core = strip_parens(value).strip()
                direct = re.fullmatch(r"(?:[A-Za-z_]\w*\s*\.\s*)?"
                                      r"modelsDevProviderIds", core)
                if not (core in bound or direct):
                    out.append((rel, 0, "2e",
                                "the derived table maps every key to `%s` "
                                "instead of the family's ids — `val ids = "
                                "type.modelsDevProviderIds` becomes dead and all "
                                "%d vendors fall out of the key map (the "
                                "derivation is present but its result is "
                                "discarded; `.take(0)` / `.filter { }` on the "
                                "binding is the same defect one stage later)"
                                % (core, len(BASE_KEYS))))
    body = map_literal_text(src)
    if body is None:
        out.append((rel, 0, "2c",
                    "`providerKeyMap = mapOf(` literal not found — gate ROT"))
    else:
        for key in BASE_KEYS:
            if ('"%s"' % key) not in body:
                out.append((rel, 0, "2c",
                            "providerKeyMap literal lost `\"%s\"` — the merge "
                            "must be additive (static/text gates and the "
                            "readability anchors depend on these literals)"
                            % key))
    return out

def run_checks(root, rels=None):
    rels = rels or {"pc": PC_REL, "md": MD_REL}
    out = []
    pc = read(rels["pc"], root)
    md = read(rels["md"], root)
    if pc is None:
        out.append((rels["pc"], 0, "1a", "file not found — gate ROT"))
    else:
        out.extend(check_arm1(pc, rels["pc"]))
    if md is None:
        out.append((rels["md"], 0, "2a", "file not found — gate ROT"))
    else:
        out.extend(check_arm2(md, rels["md"]))
    return out


# ── fixtures ───────────────────────────────────────────────────────────────
FIX_PC_OK = '''package com.rikkaminis.app.data.model

enum class ProviderType(val displayName: String) {
    anthropic("Anthropic"),
    gemini("Google Gemini"),
    openAI("OpenAI"),
    kimiCode("Kimi Code");

    val builtInModels: List<LLMModel>
        get() = when (this) {
            anthropic -> LLMModel.allAnthropic
            gemini -> LLMModel.allGemini
            openAI -> LLMModel.allOpenAI
            kimiCode -> LLMModel.allKimi
        }
}

val ProviderType.modelsDevProviderIds: List<String>
    get() = when (this) {
        ProviderType.anthropic -> listOf("anthropic")
        ProviderType.gemini -> listOf("google", "google-vertex")
        ProviderType.openAI -> listOf("openai")
        ProviderType.kimiCode -> listOf("moonshotai", "moonshotai-cn")
    }

val ProviderType.legacyProviderNames: List<String>
    get() = when (this) {
        ProviderType.anthropic -> listOf("Anthropic")
        ProviderType.gemini -> listOf("Google")
        ProviderType.openAI -> listOf("OpenAI")
        ProviderType.kimiCode -> listOf("Kimi")
    }

data class LLMModel(val id: String)
'''

# Uncovered-constant fixture: the `when` is exhaustive for the OLD enum, then a
# new vendor is added to the enum only (the exact F-212 / 2031-N shape).
FIX_PC_NEW_VENDOR = FIX_PC_OK.replace(
    '    kimiCode("Kimi Code");',
    '    kimiCode("Kimi Code"),\n    deepSeek("DeepSeek");',
).replace(
    '            kimiCode -> LLMModel.allKimi',
    '            kimiCode -> LLMModel.allKimi',
)

FIX_PC_ELSE_BRANCH = FIX_PC_OK.replace(
    'val ProviderType.legacyProviderNames: List<String>\n    get() = when (this) {\n'
    '        ProviderType.anthropic -> listOf("Anthropic")',
    'val ProviderType.legacyProviderNames: List<String>\n    get() = when (this) {\n'
    '        else -> listOf("Anthropic")',
)

FIX_MD_OK = '''package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ProviderType
import com.rikkaminis.app.data.model.legacyProviderNames
import com.rikkaminis.app.data.model.modelsDevProviderIds

object ModelsDevApi {
    private val providerKeyMap = mapOf(
        "Anthropic" to listOf("anthropic"),
        "Google" to listOf("google", "google-vertex"),
        "OpenAI" to listOf("openai"),
        "OpenRouter" to listOf("openrouter"),
        "Antigravity" to emptyList(),
        "xAI" to listOf("xai"),
        "Kimi" to listOf("moonshotai", "moonshotai-cn"),
        "Google Gemini" to listOf("google", "google-vertex"),
        "xAI (Grok)" to listOf("xai"),
        "Kimi Code" to listOf("moonshotai", "moonshotai-cn"),
    ) + ProviderType.values().flatMap { type ->
        val ids = type.modelsDevProviderIds
        (listOf(type.displayName, type.name) + type.legacyProviderNames)
            .distinct()
            .map { key -> key to ids }
    }.toMap()
}
'''

# Pre-ARCH-03 shape: literals only, no derivation.
FIX_MD_NO_DERIVE = '''package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.LLMModel

object ModelsDevApi {
    private val providerKeyMap = mapOf(
        "Anthropic" to listOf("anthropic"),
        "Google" to listOf("google", "google-vertex"),
        "OpenAI" to listOf("openai"),
        "OpenRouter" to listOf("openrouter"),
        "Antigravity" to emptyList(),
        "xAI" to listOf("xai"),
        "Kimi" to listOf("moonshotai", "moonshotai-cn"),
        "Google Gemini" to listOf("google", "google-vertex"),
        "xAI (Grok)" to listOf("xai"),
        "Kimi Code" to listOf("moonshotai", "moonshotai-cn"),
    )
}
'''

# Comment decoy: the merge tokens exist, but only inside prose/KDoc and a
# commented-out line — code must stay red.
FIX_MD_COMMENT_DECOY = FIX_MD_NO_DERIVE.replace(
    "object ModelsDevApi {",
    '''// ProviderType.values().flatMap { type -> type.modelsDevProviderIds }
// + legacyProviderNames … .toMap()
/* import com.rikkaminis.app.data.model.modelsDevProviderIds */
object ModelsDevApi {''',
)

# Literal key dropped (merge not additive).
FIX_MD_MISSING_KEY = FIX_MD_OK.replace(
    '        "Kimi" to listOf("moonshotai", "moonshotai-cn"),\n', "")

# ── wide-1024 witnesses (the exact knives that used to walk through) ────────
# W1 (Arm 1e, completeness): the `when` stays exhaustive but the family maps
# to an EMPTY list — compiles, and the family silently falls out of the map.
FIX_PC_EMPTY_MAPPING = FIX_PC_OK.replace(
    '        ProviderType.kimiCode -> listOf("moonshotai", "moonshotai-cn")',
    '        ProviderType.kimiCode -> emptyList()')

# W2 (Arm 2d, completeness): the derived key set drops `type.displayName`.
FIX_MD_NO_DISPLAYNAME = FIX_MD_OK.replace(
    "(listOf(type.displayName, type.name) + type.legacyProviderNames)",
    "(listOf(type.name) + type.legacyProviderNames)")

# W3 (Arm 2e, dataflow): the derivation is present, its RESULT is discarded —
# every key maps to `emptyList<String>()` and `val ids` goes unused.
FIX_MD_EMPTY_VALUE = FIX_MD_OK.replace(
    ".map { key -> key to ids }", ".map { key -> key to emptyList<String>() }")


def _report(checks, fixture_name, label):
    if not checks:
        print("  [OK]   %s (%s)" % (fixture_name, label))
        return True
    for rel, line, arm, msg in checks:
        print("  [FAIL] %s arm %s line %s: %s" % (fixture_name, arm, line, msg))
    return False


def selftest():
    ok = True
    rels = {"pc": "ProviderConfig.kt", "md": "ModelsDevApi.kt"}
    with tempfile.TemporaryDirectory() as tmp:
        def write(name, text):
            with open(os.path.join(tmp, rels[name]), "w", encoding="utf-8") as f:
                f.write(text)

        # 1. Clean shape ⇒ no findings at all.
        write("pc", FIX_PC_OK)
        write("md", FIX_MD_OK)
        ok &= _report(run_checks(tmp, rels), "fixed", "all arms clean")

        # 2. Legacy shape (pre-ARCH-03): enum without the metadata properties,
        #    map without the derivation — both files fail-closed.
        write("pc", FIX_PC_OK[:FIX_PC_OK.index("val ProviderType.modelsDevProviderIds")])
        write("md", FIX_MD_NO_DERIVE)
        checks = run_checks(tmp, rels)
        a1 = [c for c in checks if c[2] == "1b"]
        a2 = [c for c in checks if c[2].startswith("2")]
        if len(a1) != 2 or not a2:
            print("  [FAIL] pre-fix fixture: expected 2×arm1b + arm2 findings, got %s"
                  % [(c[2], c[3][:40]) for c in checks])
            ok = False
        else:
            print("  [OK]   pre-fix fixture trips arm1b ×2 + arm2 ×%d" % len(a2))

        # 3. New vendor in enum, not covered by either `when` ⇒ arm1d fires for
        #    BOTH properties (the F-212 shape the Kotlin compiler also rejects).
        write("pc", FIX_PC_NEW_VENDOR)
        write("md", FIX_MD_OK)
        checks = [c for c in run_checks(tmp, rels) if c[2] == "1d"]
        if len(checks) != 2 or not all("deepSeek" in c[3] for c in checks):
            print("  [FAIL] new-vendor fixture: expected 2×arm1d(deepSeek), got %s"
                  % [(c[2], c[3][:60]) for c in checks])
            ok = False
        else:
            print("  [OK]   new-vendor fixture trips arm1d on both properties")

        # 4. `else ->` disarms exhaustiveness ⇒ arm1c fires (and arm1d too).
        write("pc", FIX_PC_ELSE_BRANCH)
        checks = run_checks(tmp, rels)
        if not [c for c in checks if c[2] == "1c"]:
            print("  [FAIL] else-branch fixture did not trip arm1c")
            ok = False
        else:
            print("  [OK]   else-branch fixture trips arm1c")
        write("pc", FIX_PC_OK)

        # 5. Enum header removed ⇒ ROT.
        write("pc", FIX_PC_OK.replace(ENUM_HEADER, "enum class ProviderTypeXX(val displayName: String)"))
        checks = run_checks(tmp, rels)
        if not [c for c in checks if c[2] == "1a"]:
            print("  [FAIL] enum-absent fixture did not trip arm1a (ROT)")
            ok = False
        else:
            print("  [OK]   enum-absent fixture trips arm1a (ROT)")
        write("pc", FIX_PC_OK)

        # 6. Comment decoy: merge tokens only in comments ⇒ arm2b must fire.
        write("md", FIX_MD_COMMENT_DECOY)
        checks = [c for c in run_checks(tmp, rels) if c[2] == "2b"]
        if not checks:
            print("  [FAIL] comment-decoy fixture did not trip arm2b")
            ok = False
        else:
            print("  [OK]   comment-decoy fixture trips arm2b (%d findings)" % len(checks))

        # 7. Literal key dropped ⇒ arm2c fires while arm2b stays green
        #    (precise attribution: additive-ness is a separate property).
        write("md", FIX_MD_MISSING_KEY)
        checks = run_checks(tmp, rels)
        if not [c for c in checks if c[2] == "2c"]:
            print("  [FAIL] dropped-literal-key fixture did not trip arm2c")
            ok = False
        elif [c for c in checks if c[2] == "2b"]:
            print("  [FAIL] dropped-literal-key fixture also tripped arm2b — "
                  "arms are not independent")
            ok = False
        else:
            print("  [OK]   dropped-literal-key fixture trips arm2c alone")
        write("md", FIX_MD_OK)

        # 8. ROT control: empty root.
        with tempfile.TemporaryDirectory() as empty:
            if not run_checks(empty, rels):
                print("  [FAIL] ROT control: missing files produced no finding")
                ok = False
            else:
                print("  [OK]   ROT control: missing files reported")

        # ── wide-1024 witness arms ────────────────────────────────────────
        # W1: exhaustive `when` + EMPTY mapping (the xAI knife) ⇒ arm1e alone.
        write("pc", FIX_PC_EMPTY_MAPPING)
        write("md", FIX_MD_OK)
        checks = run_checks(tmp, rels)
        a1e = [c for c in checks if c[2] == "1e"]
        others = [c for c in checks if c[2] not in ("1e",)]
        if len(a1e) != 1 or not all("kimiCode" in c[3] for c in a1e):
            print("  [FAIL] empty-mapping witness: expected 1×arm1e(kimiCode), got %s"
                  % [(c[2], c[3][:50]) for c in checks])
            ok = False
        elif others:
            print("  [FAIL] empty-mapping witness tripped unrelated arms: %s"
                  % [(c[2], c[3][:50]) for c in others])
            ok = False
        else:
            print("  [OK]   empty-mapping witness trips arm1e alone")
        write("pc", FIX_PC_OK)

        # W2: derivation drops displayName ⇒ arm2d alone.
        write("md", FIX_MD_NO_DISPLAYNAME)
        checks = run_checks(tmp, rels)
        a2d = [c for c in checks if c[2] == "2d"]
        others = [c for c in checks if c[2] != "2d"]
        if not a2d:
            print("  [FAIL] no-displayName witness did not trip arm2d")
            ok = False
        elif others:
            print("  [FAIL] no-displayName witness tripped unrelated arms: %s"
                  % [(c[2], c[3][:50]) for c in others])
            ok = False
        else:
            print("  [OK]   no-displayName witness trips arm2d alone")

        # W3: derivation present, its value discarded ⇒ arm2e alone.
        write("md", FIX_MD_EMPTY_VALUE)
        checks = run_checks(tmp, rels)
        a2e = [c for c in checks if c[2] == "2e"]
        others = [c for c in checks if c[2] != "2e"]
        if not a2e:
            print("  [FAIL] discarded-value witness did not trip arm2e")
            ok = False
        elif others:
            print("  [FAIL] discarded-value witness tripped unrelated arms: %s"
                  % [(c[2], c[3][:50]) for c in others])
            ok = False
        else:
            print("  [OK]   discarded-value witness trips arm2e alone")
        write("md", FIX_MD_OK)

        # ── [batch6-t06] the six witnesses this branch's report proved blind ─
        # Each keeps every token the old criteria looked for (an exhaustive
        # `when`, `ProviderType.values()`, `flatMap`, `key to …`, the enum
        # constant's name) and moves the EFFECT: the value empties one stage
        # later, the key set is filtered, the enum stream is truncated, a
        # branch lives only in a block comment, a constant shares a line.

        def expect(label, want, checks, needle=""):
            nonlocal ok
            got = [(c[2], c[3]) for c in checks]
            if len(got) != want or (needle and not all(needle in g[1] for g in got)):
                ok = False
                print("  [FAIL] %s: expected %d×%s, got %s"
                      % (label, want, needle or "finding",
                         [(c[2], c[3][:60]) for c in checks]))
            else:
                print("  [OK]   %s trips %d×%s" % (label, want, needle or "finding"))

        # N1: the mapping value is emptied ONE STAGE LATER — `listOf(...).filter
        # { false }` is `listOf()` at evaluation time, and it still contains a
        # non-empty constructor for any criterion that inspects construction.
        write("pc", FIX_PC_OK.replace(
            'ProviderType.kimiCode -> listOf("moonshotai", "moonshotai-cn")',
            'ProviderType.kimiCode -> listOf("moonshotai", "moonshotai-cn")'
            '.filter { false }'))
        write("md", FIX_MD_OK)
        expect("evaluation-stage empty mapping", 1, run_checks(tmp, rels),
               "emptied by a collection operator")

        # N2: the value is derived, bound, then TRUNCATED at the pair site —
        # `val ids = …` stays live-looking while `key to ids.take(0)` empties it.
        write("pc", FIX_PC_OK)
        write("md", FIX_MD_OK.replace("key to ids", "key to ids.take(0)"))
        expect("truncated pair value", 1, run_checks(tmp, rels),
               "instead of the family's ids")

        # N3: the key SPACE is a set; filtering it keeps every symbolic source
        # in the text while dropping one whole key family from the value.
        write("md", FIX_MD_OK.replace(".distinct()",
                                      ".filter { it != type.displayName }"))
        expect("filtered key space", 1, run_checks(tmp, rels),
               "truncation operator")

        # N4: the enum stream is intercepted before the mapping block —
        # `ProviderType.values().take(0).flatMap { … }` matches both old
        # criteria (ENUM_ITER + MERGE_TOKENS) and maps nothing.
        write("md", FIX_MD_OK.replace("ProviderType.values().flatMap",
                                      "ProviderType.values().take(0).flatMap"))
        expect("zeroed enum iteration", 1, run_checks(tmp, rels),
               "enum iteration is intercepted by")

        # N5: two branches move into a block comment and merge into a branch
        # that yields one BLANK id — the line-based stripper kept `/* … */`
        # alive, so coverage stayed "complete" via branches that do not exist.
        write("pc", FIX_PC_OK.replace(
            '        ProviderType.anthropic -> listOf("anthropic")\n'
            '        ProviderType.gemini -> listOf("google", "google-vertex")',
            '        /* ProviderType.anthropic -> listOf("anthropic")\n'
            '        ProviderType.gemini -> listOf("google", "google-vertex") */\n'
            '        ProviderType.anthropic, ProviderType.gemini -> listOf("")'))
        write("md", FIX_MD_OK)
        expect("commented-out branches + blank id", 2, run_checks(tmp, rels),
               "BLANK provider id")

        # N6: a second constant on an EXISTING line — the old enum parser was
        # anchored at `^\s*NAME(`, so the enum grew a subclass the gate could
        # not see (exhaustiveness then "held" for a constant it never listed).
        write("pc", FIX_PC_OK.replace(
            '    kimiCode("Kimi Code");',
            '    kimiCode("Kimi Code"), deepSeek("DeepSeek");'))
        expect("same-line enum constant", 2, run_checks(tmp, rels),
               "does not cover ProviderType.deepSeek")
        write("pc", FIX_PC_OK)
        write("md", FIX_MD_OK)

    print("PROVIDER_TYPE_REGISTRY_SELFTEST=%s" % ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("root", nargs="?", default=None)
    ap.add_argument("--root", dest="root_opt", default=None)
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    root = args.root_opt or args.root
    if not root:
        ap.print_usage(sys.stderr)
        return 2
    if not os.path.isdir(root):
        print("root not found: %s" % root, file=sys.stderr)
        return 2

    checks = run_checks(root)
    if not checks:
        print("provider_type_registry_check: OK (2 arms clean)")
        return 0
    for rel, line, arm, msg in checks:
        print("%s:%s [arm %s] %s" % (rel, line, arm, msg))
    print("provider_type_registry_check: %d violation(s)" % len(checks))
    return 1


if __name__ == "__main__":
    sys.exit(main())
