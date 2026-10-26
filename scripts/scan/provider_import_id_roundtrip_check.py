#!/usr/bin/env python3
"""
Gate: provider import/export id round-trip + backup field completeness
(deep-audit-1021, ISSUE-02 + ISSUE-09, branch fix/da1021-import-id-roundtrip).

Three silent-on-restore defect classes, all "the payload/return value looks
complete but one id or field points at something that no longer exists":

  1. MERGE RETURNS A TEMP UUID (ProviderRepository.mergeImportInstanceJSON)
     The `addEntry(entry)` branch returned `entry.id` — the random UUID minted
     by parseImportedModelEntries. saveConfig canonicalizes every persisted
     entry id to the composite "{instanceId}/{modelId}" shape (toSnapshot →
     toProviderConfig), so the returned id never existed on disk. Every
     entryIdMap consumer (model groups / agentLoopEntries /
     compactModelEntryId / session bindings) dangled.

  2/3. EXPORT+PARSE DROP THE REASONING PAIR (exportInstanceJSON /
     parseImportedModelEntries)
     The Room/kotlinx path carries `reasoningEffortValues` +
     `declaresNoEffortTiers`; the hand-written org.json serializer dropped
     both. A backup restore evaporated the effort-tier ceiling
     (ThinkingLevelCatalog) and the no-effort flag (xAI models must NOT
     receive reasoning_effort) for gateway models absent from models.dev.

  4. OPENMINIS CONVERSION DROPS THE ECHO POLICY (convertThinkingRules)
     restoreThinkingRules already reads "reasoningEchoJson"; only this
     converter failed to copy it, so a cross-device restore silently reset
     the reasoning-echo policy to null.

  5. FIELD COMPLETENESS (the "add a field, sync it everywhere" family)
     Programmatically enumerates LLMModel's constructor `val` properties and
     asserts each survives the export and the parse (or is in the explicit
     exemption table below, with a reason) — so the NEXT dropped field turns
     red here instead of on a device.

Usage: python3 scripts/scan/provider_import_id_roundtrip_check.py [root]
       python3 scripts/scan/provider_import_id_roundtrip_check.py --root <root>
       python3 scripts/scan/provider_import_id_roundtrip_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage/ROT
"""
import argparse
import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import KotlinLexError, mask_code, mask_comments  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool,
    const_value,
    is_pure_literal,
    match_bracket,
    split_top,
    stmt_spans,
    strip_parens,
)
from _kotlin_valueflow import (  # noqa: E402
    call_args,
    code_view,
    expr_end,
    guard_feasible,
    literal_view,
    path_feasible,
    returns_exiting,
    value_sources,
)

APP = "src/android/app/src/main/java/com/rikkaminis/app"
PR_REL = APP + "/data/repository/ProviderRepository.kt"
BM_REL = APP + "/backup/OpenMinisBackupCompat.kt"
LM_REL = APP + "/data/model/LLMModel.kt"

# Arm 5: property name → JSON key it must survive as in export AND parse.
# Exemptions carry a reason; anything else must appear under its own name.
LLM_KEY_MAP = {
    "id": "modelId",  # exported/read as "modelId"
}
LLM_EXEMPT = {
    # The export is per-instance and the parse takes the section-level
    # "providerType" — the provider NAME is never serialized per model.
    "provider": "derived from the section-level providerType (parse: "
                "providerType.displayName; export: per-instance dict)",
}

# Parse-direction exemption: these keys are read through the bitfield-fallback
# helper, not by a quoted literal in parseImportedModelEntries' body. The gate
# asserts the helper is the reader (call present) instead of a bare literal.
PARSE_HELPER_READ = {
    "inputModalities": "readModalitiesWithBitfieldFallback",
    "outputModalities": "readModalitiesWithBitfieldFallback",
}
PARSE_HELPER_CALL = "readModalitiesWithBitfieldFallback(m)"

# Arm 2/3 anchors: the READ/WRITE call, not a bare key string — the parse
# body also carries each key as the LLMModel constructor argument
# (`declaresNoEffortTiers = declaresNoEffortTiers`), which satisfies a bare
# presence check even after the read block is deleted (mutation M3 caught
# exactly that false green).
EXPORT_ANCHORS = ('put("reasoningEffortValues"', 'put("declaresNoEffortTiers"')
PARSE_ANCHORS = ('optJSONArray("reasoningEffortValues")', 'optBoolean("declaresNoEffortTiers")')

REASONING_KEYS = ("reasoningEffortValues", "declaresNoEffortTiers")
ECHO_KEY = "reasoningEchoJson"

# Bare `entry.id` = the temp-uuid return. Does NOT match `entry.baseModel.id`
# (that is followed by ".baseModel", not ".id") nor `hit?.id`.
BARE_ENTRY_ID = re.compile(r"(?<![.\w])entry\.id(?![\w])")


def line_of(src, idx):
    return src.count("\n", 0, idx) + 1


def _end_after(text, open_idx):
    """Index just past the bracket matching the opener at [open_idx] (-1: none).

    [batch5] One implementation: `_kotlin_ast.match_bracket` over the masked
    view. The hand-rolled scanner this replaces counted braces inside string
    literals, so a `}` in a message closed the function body early
    (parser-death).
    """
    close = match_bracket(text, open_idx)
    return -1 if close < 0 else close + 1


def function_text(src, header):
    """Text of the declaration starting at [header], up to its closing brace.

    Braces are matched on the masked view (a string literal cannot close the
    body early); the RETURNED slice is the original source so callers can read
    literal contents (JSON keys) from it.
    """
    i = src.find(header)
    if i < 0:
        return None
    masked = code_view(src)
    brace = masked.find("{", i)
    if brace < 0:
        return None
    end = _end_after(masked, brace)
    return None if end < 0 else src[i:end]


def read(rel, root):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return None
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


# ----------------------------------------------------------------------
# Arm 1 — merge must not return the temp uuid
# ----------------------------------------------------------------------
def _elvis_parts(expr):
    """(left, right) of the TOP-LEVEL `?:` in [expr], or None.

    [batch5] Split via the shared `split_top` (masked view: a `?:` inside a
    string or a nested lambda is not top-level) instead of the hand-rolled
    depth/quote scanner this gate used to carry.
    """
    parts = split_top(expr, ("?:",))
    if len(parts) != 2:
        return None
    a, b = parts
    return expr[a[0]:a[1]].strip(), expr[b[0]:b[1]].strip()


def check_merge_requery(src, rel=PR_REL):
    body = function_text(src, "fun mergeImportInstanceJSON(")
    if body is None:
        return [(rel, 0, "1", "mergeImportInstanceJSON( not found — gate ROT")]
    code = code_view(body)
    lit = literal_view(body)
    add_idx = code.find("addEntry(entry)")
    add_m = re.search(r"addEntry\(\s*([A-Za-z_]\w*)\s*\)", code)
    if add_idx < 0:
        return [(rel, 0, "1", "mergeImportInstanceJSON no longer calls addEntry(entry) — gate ROT")]
    # The run-block that follows addEntry must resolve the persisted id.
    tail_marker = code.find("entryMap[", add_idx)
    tail = code[add_idx: tail_marker if tail_marker > 0 else len(code)]
    if "firstOrNull" not in tail and "compositeEntryKey(" not in lit:
        return [(rel, line_of(src, src.find("fun mergeImportInstanceJSON(")), "1",
                 "addEntry(entry) branch returns without re-querying the persisted id "
                 "(no firstOrNull re-query, no compositeEntryKey) — entryIdMap receives "
                 "a temp uuid that never existed on disk")]
    # [wide-1024] The id must be PROJECTED OFF THE RE-QUERY RESULT, not read off
    # the imported entry. The sweep's witness wrote
    #   …firstOrNull { … }?.let { entry.uuid } ?: compositeEntryKey(…)
    # — `ModelEntry.id` is `get() = uuid`, so the temp uuid came back through a
    # different spelling while `firstOrNull`, `compositeEntryKey(` and the
    # absence of a bare `entry.id` all stayed true.
    problems = []
    imported = add_m.group(1) if add_m else "entry"
    elvis = _elvis_parts(tail)
    if elvis is None:
        # No elvis: the branch may return the deterministic composite key
        # straight away (`compositeEntryKey(existing.id, entry.baseModel.id)`),
        # which is derived from STABLE inputs and needs no re-query. What it may
        # not do is read the temp uuid/id off the imported entry.
        whole = tail.strip()
        if not re.search(r"\bcompositeEntryKey\s*\(", whole):
            problems.append("the returned id is neither a re-query projection nor a "
                            "deterministic compositeEntryKey(…) — fail-closed")
        if re.search(r"(?<![.\w])%s\s*\.\s*(?:uuid|id)\b" % re.escape(imported), whole):
            problems.append("it is read off the imported entry `%s` itself (a temp "
                            "uuid/id that saveConfig canonicalizes away)" % imported)
        left_txt = whole
    else:
        left_txt = elvis[0]
        rq = re.search(r"firstOrNull\s*\{|\.\s*find\s*\{|firstOrNull\s*\(|singleOrNull", left_txt)
        if not rq:
            problems.append("the returned id is not produced by a re-query of the persisted list")
        else:
            # The predicate lambda legitimately reads the imported entry (it
            # matches on `entry.baseModel.id`); only the PROJECTION — what the
            # expression evaluates to after the lambda closes — must not.
            brace = left_txt.find("{", rq.start())
            projection = left_txt
            if brace >= 0:
                close = match_bracket(left_txt, brace)
                if close > 0:
                    projection = left_txt[close + 1:]
                    # [batch5 G06-A1 · family-hole/dataflow] `firstOrNull { false }`
                    # keeps every token this arm used to look for (`firstOrNull`,
                    # `?.id`, `?: compositeEntryKey(`) and matches nothing, so the
                    # callers get the fallback for every entry. The predicate has
                    # to be SATISFIABLE — folded with the shared constant folder,
                    # which covers `false`, `!true`, `1 == 2`, `x && false`.
                    pred = left_txt[brace + 1:close]
                    if guard_feasible(pred) is False:
                        problems.append(
                            "the re-query predicate `%s` is unsatisfiable (constant-folds "
                            "to false) — firstOrNull never matches, so every imported entry "
                            "is handed the fallback id" % strip_parens(pred).strip()[:60])
            projection = projection.strip()
            if re.search(r"(?<![.\w])%s\s*\." % re.escape(imported), projection):
                problems.append("it is read off the imported entry `%s` itself (a temp uuid / "
                                "field that saveConfig canonicalized away)" % imported)
            if not re.search(r"(?:\?\.\s*|\.\s*(?:let|run)\s*\{[^}]*\.\s*)"
                             r"(?:id|uuid|key)\b\s*\}?\s*$", projection):
                problems.append("it ends in no projection of the lookup result "
                                "(`?.id` / `?.let { it.id }` / `?.key`) — fail-closed")
    # [batch5 G06-A1] The deterministic fallback must be DERIVED, not typed in:
    # `compositeEntryKey("", "")` answers the same constant for every entry, so
    # phase-2/3 of ConfigBackup rewrite every model reference to a key that does
    # not exist. Judged on the values (shared literal folder), not the spelling.
    for m in re.finditer(r"\bcompositeEntryKey\s*\(", code):
        open_idx = code.index("(", m.start())
        args = call_args(code, open_idx)
        if not args:
            problems.append("`compositeEntryKey(…)` has no readable argument list — "
                            "fail-closed")
            continue
        lits = [code[a:b].strip() for a, b in args if is_pure_literal(code[a:b])]
        if lits:
            problems.append(
                "`compositeEntryKey(%s)` is built from literal constants (%s) — every "
                "imported entry maps to the same key, so the caller rewrites all "
                "model-group / default-model references to a key that does not exist"
                % (", ".join(code[a:b].strip() for a, b in args), ", ".join(lits)))
            break
    if problems:
        return [(rel, line_of(src, src.find("fun mergeImportInstanceJSON(") + add_idx),
                 "1",
                 "mergeImportInstanceJSON's re-query result is not what it returns: %s"
                 % "; ".join(problems))]
    m = BARE_ENTRY_ID.search(code)
    if m is not None:
        return [(rel, line_of(src, src.find("fun mergeImportInstanceJSON(") + m.start()), "1",
                 "mergeImportInstanceJSON still returns the bare temp `entry.id` — "
                 "a uuid saveConfig canonicalizes away")]
    return []


# ----------------------------------------------------------------------
# Arms 2/3 — export & parse must carry the reasoning pair
# ----------------------------------------------------------------------
def check_reasoning_keys(src, rel, header, arm, what, anchors):
    body = function_text(src, header)
    if body is None:
        return [(rel, 0, arm, header + " not found — gate ROT")]
    code = literal_view(body)
    out = []
    for anchor in anchors:
        if anchor not in code:
            out.append((rel, line_of(src, src.find(header)), arm,
                        "%s does not %s — a backup restore evaporates the field "
                        "(Room path already has it)" % (what, anchor)))
    return out


def _dead_scope_guard(expr):
    """Reason the value of [expr] is decided at compile time by an inline guard,
    or None. Covers `.takeIf { false }` / `.takeUnless { true }`.

    [batch5] `x.takeIf { false } ?: ""` keeps the whole outer AST shape of the
    expression the arms look for (`optString(KEY)` / `displayName` / the read
    call) and still evaluates to the other side. The guard is judged with the
    shared constant folder, so `1 == 2` / `!true` / `x && false` fold too.
    """
    for m in re.finditer(r"\.\s*(takeIf|takeUnless)\s*\{", expr):
        brace = expr.index("{", m.end() - 1)
        close = match_bracket(expr, brace)
        if close < 0:
            return "an unreadable `.%s { … }` guard — fail-closed" % m.group(1)
        cond = const_bool(expr[brace + 1:close])
        if cond is None:
            continue
        dead = (cond is False) if m.group(1) == "takeIf" else (cond is True)
        if dead:
            return ("`.%s { %s }` is decided at compile time, so the value is never "
                    "produced" % (m.group(1),
                                  strip_parens(expr[brace + 1:close]).strip()[:40]))
    return None


def _stmt_at(code, pos):
    """[start, end) of the top-level statement in [code] containing [pos]."""
    spans = stmt_spans(code)
    for a, b in spans:
        if a <= pos < b:
            return a, b
    return 0, len(code)


def check_convert_echo(src, rel=BM_REL):
    """[batch5 G06-A2] The echo key must be READ *and WRITTEN* into the output.

    The old criterion was a bare `KEY in body` presence test: the witness kept
    `d.optString("reasoningEchoJson", "").takeIf { false }` — the read anchor is
    still there, the `r.put(...)` is gone — and the gate stayed green while
    every iOS restore dropped the field. The judgement now follows the value:
    a `put(KEY, …)` must exist, its value must not be a constant, and the write
    must be reachable (no compile-time-decided guard).
    """
    body = function_text(src, "fun convertThinkingRules(")
    if body is None:
        return [(rel, 0, "4", "convertThinkingRules( not found — gate ROT")]
    code, lit = code_view(body), literal_view(body)
    dec_at = src.find("fun convertThinkingRules(")
    out = []

    def _at(pos):
        return line_of(src, dec_at + pos)

    read_re = re.compile(r"\bopt(?:String|)\s*\(\s*\"%s\"|\bhas\s*\(\s*\"%s\""
                         % (ECHO_KEY, ECHO_KEY))
    write_re = re.compile(r"\bput\s*\(\s*\"%s\"" % ECHO_KEY)
    if not read_re.search(lit):
        out.append((rel, _at(0), "4",
                    "convertThinkingRules no longer reads " + ECHO_KEY +
                    " — cross-device restore resets the reasoning-echo policy"))
        return out
    writes = list(write_re.finditer(lit))
    if not writes:
        out.append((rel, _at(0), "4",
                    "convertThinkingRules READS " + ECHO_KEY + " but never writes it "
                    "into the output object (`put(\"" + ECHO_KEY + "\", …)` absent) — the "
                    "read value is dropped, so every iOS/OpenMinis restore loses the "
                    "custom reasoning-echo setting (DeepSeek/Kimi multi-turn tool calls "
                    "then re-send reasoning without the echo field)"))
        return out
    for w in writes:
        open_paren = code.index("(", w.start())
        args = call_args(code, open_paren)
        if len(args) < 2:
            out.append((rel, _at(w.start()), "4",
                        "the `put(\"" + ECHO_KEY + "\", …)` write has no readable value "
                        "argument — fail-closed"))
            continue
        val = code[args[1][0]:args[1][1]].strip()
        if not val or is_pure_literal(val):
            out.append((rel, _at(w.start()), "4",
                        "the value written for " + ECHO_KEY + " is the constant `%s`, not "
                        "the value that was read — the field is restored as a fixed "
                        "placeholder" % val[:40]))
            continue
        if not path_feasible(code, w.start()):
            out.append((rel, _at(w.start()), "4",
                        "the `put(\"" + ECHO_KEY + "\", …)` write sits inside a branch "
                        "that is constant-folded dead — the field is never emitted"))
            continue
        a, b = _stmt_at(code, w.start())
        dead = _dead_scope_guard(code[a:b])
        if dead:
            out.append((rel, _at(w.start()), "4",
                        "the " + ECHO_KEY + " write is unreachable: " + dead))
            continue
        if not read_re.search(lit[a:b]):
            out.append((rel, _at(w.start()), "4",
                        "the " + ECHO_KEY + " write does not consume the value read from "
                        "the record (the read and the write are not in the same "
                        "expression) — the field is written from something else"))
    return out


# ----------------------------------------------------------------------
# Arm 5 — every LLMModel constructor property survives export AND parse
# ----------------------------------------------------------------------
def llm_model_properties(src, rel=LM_REL):
    i = src.find("data class LLMModel(")
    if i < 0:
        return None
    open_paren = src.find("(", i)
    end = _end_after(code_view(src), open_paren)
    if end < 0:
        return None
    ctor = src[i:end]
    return re.findall(r"\bval\s+(\w+)\s*:", ctor)


def _ctor_named_args(code, callee, lit=None):
    """{parameter: value-text} of every `callee( … )` call in [code].

    Structure (which arguments are named, where each one starts/ends) comes
    from the masked [code]; the value TEXT comes from [lit] when given —
    string literals (`optJSONArray("reasoningEffortValues")`) are blank in the
    masked view and the callers judge on the key named. Both views are
    length-preserving, so offsets are interchangeable.
    """
    out = {}
    lit = code if lit is None else lit
    for m in re.finditer(r"(?<![.\w])%s\s*\(" % re.escape(callee), code):
        open_paren = code.index("(", m.start())
        for a, b in call_args(code, open_paren):
            kv = re.match(r"\s*([A-Za-z_]\w*)\s*=\s*(?![=>])", code[a:b])
            if kv:
                out.setdefault(kv.group(1), lit[a + kv.end():b].strip())
    return out


_ctor_args = _ctor_named_args


def _decl_rhs(code, name, lit=None):
    """RHS bound to [name] by its declaration in [code], or None.

    The span is derived on the masked [code]; the text is taken from [lit] when
    given, so a `"key"` inside the RHS is readable.
    """
    lit = code if lit is None else lit
    m = re.search(r"\b(?:val|var)\s+%s\b\s*(?::[^=\n]*)?=\s*" % re.escape(name), code)
    if m:
        return lit[m.end():expr_end(code, m.end())].strip()
    for m in re.finditer(r"\b(?:val|var)\s*\(([^)]*)\)\s*=\s*", code):
        if re.search(r"(?<![\w])%s(?![\w])" % re.escape(name), m.group(1)):
            return lit[m.end():expr_end(code, m.end())].strip()
    return None


def _ctor_value_findings(parse_shape, ctor_args, prop, key, rel, at_line, lit=None):
    """[batch5 G06-A3] The value passed for [prop] must be the PARSED value.

    Three false greens this replaces, all of which kept every anchor:
      * `reasoningEffortValues = null` (the parsed local is discarded),
      * `contextWindow = 0` / `= ""` (a constant re-introduces "no data"),
      * `inputModalities = <something that never read the JSON>`.
    Fail-closed: a property the constructor never receives is a violation too —
    the field silently falls back to the data class default.
    """
    if prop not in ctor_args:
        return [(rel, at_line, "5",
                 "parseImportedModelEntries never passes `%s` to LLMModel(…) — the field "
                 "falls back to the data-class default, so a restore drops it "
                 "(add-a-field-sync-everywhere regression)" % prop)]
    val = ctor_args[prop]
    if not val or val == "null" or is_pure_literal(val):
        return [(rel, at_line, "5",
                 "LLMModel(%s = %s) in parseImportedModelEntries passes a constant — the "
                 "value parsed out of the backup is computed and then discarded, so every "
                 "restored model loses this field" % (prop, val[:40]))]
    dead = _dead_scope_guard(val)
    if dead:
        return [(rel, at_line, "5",
                 "LLMModel(%s = %s) — the value is never produced: %s"
                 % (prop, val[:50], dead))]
    sources = value_sources(val)
    ok = False
    for src_expr in sources:
        s = src_expr.strip()
        if not s or s == "null":
            continue
        if '"%s"' % key in s or PARSE_HELPER_CALL in s:
            ok = True
            break
        if re.fullmatch(r"[A-Za-z_]\w*", s):
            rhs = _decl_rhs(parse_shape, s, lit)
            if rhs and ('"%s"' % key in rhs or PARSE_HELPER_CALL in rhs
                        or (PARSE_HELPER_CALL.split("(")[0] + "(") in rhs):
                ok = True
                break
    if not ok:
        return [(rel, at_line, "5",
                 "LLMModel(%s = %s) in parseImportedModelEntries — the argument does not "
                 "derive from the backup's \"%s\" read, so the field is restored from "
                 "something else (add-a-field-sync-everywhere regression)"
                 % (prop, val[:50], key))]
    return []


def check_field_completeness(srcs, rels=None):
    rels = rels or {"lm": LM_REL, "pr": PR_REL}
    lm_src, pr_src = srcs["lm"], srcs["pr"]
    props = llm_model_properties(lm_src, rels["lm"])
    if not props:
        return [(rels["lm"], 0, "5", "data class LLMModel( not found — gate ROT")]
    export_body = function_text(pr_src, "fun exportInstanceJSON(")
    parse_body = function_text(pr_src, "fun parseImportedModelEntries(")
    out = []
    if export_body is None:
        out.append((rels["pr"], 0, "5", "exportInstanceJSON( not found — gate ROT"))
    if parse_body is None:
        out.append((rels["pr"], 0, "5", "parseImportedModelEntries( not found — gate ROT"))
    if out:
        return out
    export_code, parse_code = literal_view(export_body), literal_view(parse_body)
    parse_shape = code_view(parse_body)
    ctor_args = _ctor_args(parse_shape, "LLMModel", parse_code)
    for prop in props:
        if prop in LLM_EXEMPT:
            continue
        key = LLM_KEY_MAP.get(prop, prop)
        key_lit = '"%s"' % key
        # Line-level: the key must appear inside a READ/WRITE call, not as a
        # bare constructor argument — a dropped read leaves the constructor
        # arg behind (mutation M3's false green).
        def _has_call(code, callwords):
            for line in code.split("\n"):
                if key_lit in line and any(w in line for w in callwords):
                    return True
            return False
        if not _has_call(export_code, ("put(", "opt", "has(")):
            out.append((rels["pr"], line_of(pr_src, pr_src.find("fun exportInstanceJSON(")), "5",
                        "exportInstanceJSON does not serialize LLMModel.%s (key \"%s\") — "
                        "add-a-field-sync-everywhere regression" % (prop, key)))
        if not _has_call(parse_code, ("opt", "has(")):
            if prop in PARSE_HELPER_READ and PARSE_HELPER_CALL in parse_code:
                pass  # helper-owned read (bitfield fallback contract)
            else:
                out.append((rels["pr"], line_of(pr_src, pr_src.find("fun parseImportedModelEntries(")), "5",
                            "parseImportedModelEntries does not read LLMModel.%s (key \"%s\") — "
                            "add-a-field-sync-everywhere regression" % (prop, key)))
        # [batch5 G06-A3 · dataflow] Reading the key is not restoring it: the
        # witness kept `val reasoningEffortValues = m.optJSONArray(...)` and
        # passed `reasoningEffortValues = null` to the constructor — every
        # imported model lost its effort tiers / no-effort declaration while
        # the read and the constructor argument both stayed in place. The value
        # handed to LLMModel(…) must carry the parsed value.
        out.extend(_ctor_value_findings(parse_shape, ctor_args, prop, key, rels["pr"],
                                        line_of(pr_src,
                                                pr_src.find("fun parseImportedModelEntries(")),
                                        parse_code))
    return out


# ----------------------------------------------------------------------
# Arm 6 — [fix/dv16-a A-08] the merge path must share the append path's
# instance-field write face
# ----------------------------------------------------------------------
SHARED_FIELDS_READER = "readImportInstanceFields("
SHARED_FIELDS_APPLIER = "applyImportInstanceFields("


def check_merge_shares_instance_fields(src, rel=PR_REL):
    out = []
    import_body = function_text(src, "fun importInstanceJSON(")
    if import_body is None:
        out.append((rel, 0, "6", "importInstanceJSON( not found — gate ROT"))
    elif SHARED_FIELDS_READER not in code_view(import_body):
        out.append((rel, line_of(src, src.find("fun importInstanceJSON(")), "6",
                    "importInstanceJSON no longer decodes the instance-config fields via the "
                    "shared readImportInstanceFields seam — the append path drifted from the "
                    "merge path (one decode, two restore paths)"))
    merge_body = function_text(src, "fun mergeImportInstanceJSON(")
    if merge_body is None:
        out.append((rel, 0, "6", "mergeImportInstanceJSON( not found — gate ROT"))
    else:
        code = code_view(merge_body)
        if SHARED_FIELDS_READER not in code or SHARED_FIELDS_APPLIER not in code:
            out.append((rel, line_of(src, src.find("fun mergeImportInstanceJSON(")), "6",
                        "merge path does not apply the instance-config fields through the "
                        "shared write face (readImportInstanceFields + applyImportInstanceFields) "
                        "— a same-(type,label) restore drops customBaseURL / appendV1Suffix / "
                        "useResponsesAPI / customUserAgent / the five run-config fields"))
        # [wide-1024] The fields must be applied onto the value that is WRITTEN.
        # The sweep's witness kept the shared read + apply in place and passed
        # the UNTOUCHED `existing` to updateInstance — every decoded field was
        # computed and then dropped, silently.
        enriched = set(m.group(1) for m in re.finditer(
            r"\b([A-Za-z_]\w*)\s*=\s*applyImportInstanceFields\s*\(", code))
        if not enriched:
            out.append((rel, line_of(src, src.find("fun mergeImportInstanceJSON(")), "6",
                        "applyImportInstanceFields' result is discarded (no `val x = "
                        "applyImportInstanceFields(…)` binding) — the decoded fields cannot "
                        "reach the write face — fail-closed"))
        else:
            writes = re.findall(r"\bupdateInstance\s*\(\s*([A-Za-z_]\w*)\s*\)", code)
            if not writes:
                out.append((rel, line_of(src, src.find("fun mergeImportInstanceJSON(")), "6",
                            "merge path never writes the applied instance back through "
                            "updateInstance(<ident>) — the fields are decoded and dropped — "
                            "fail-closed"))
            else:
                for w in writes:
                    if w not in enriched:
                        out.append((rel, line_of(src, src.find("fun mergeImportInstanceJSON(")), "6",
                                    "updateInstance(%s) writes a value that did NOT come from "
                                    "applyImportInstanceFields (expected one of %s) — the "
                                    "decoded instance-config fields are silently dropped"
                                    % (w, ", ".join(sorted(enriched)))))
    n = len(re.findall(r"fun\s+applyImportInstanceFields\s*\(", src))
    if n != 1:
        out.append((rel, 0, "6",
                    "applyImportInstanceFields defined %d times — the instance-field write face "
                    "must stay single-source" % n))
    return out


# ----------------------------------------------------------------------
# Arm 7 — [fix/dv20-a2 A-08] the merge path's hit branch restores the model
# entry through the shared write face (nothing dropped, nothing hand-copied)
# ----------------------------------------------------------------------
MERGE_FIELDS_HELPER = "mergeImportedEntryOntoLocal("
ENTRY_WRITE_FACE = "updateEntry("


def check_merge_restores_entry_fields(src, rel=PR_REL):
    """A same-(type,label) merge must apply the backup's model-level values onto
    the entry it already has.

    Two decision points, both fail-closed:
      a) the hit branch restores through the shared helper, and the helper is
         defined exactly once (single source — a second copy is the drift this
         arm exists to prevent);
      b) the call site hands over whole entry objects, so adding a field to
         ModelEntry needs no edit here (a hand-written whitelist at the call
         site compiles, restores today's fields, and drifts tomorrow);
      c) the restored entry is actually written back through the shared
         whole-entry face.
    """
    out = []
    merge_body = function_text(src, "fun mergeImportInstanceJSON(")
    if merge_body is None:
        return [(rel, 0, "7", "mergeImportInstanceJSON( not found — gate ROT")]
    code = code_view(merge_body)
    if MERGE_FIELDS_HELPER not in code:
        out.append((rel, line_of(src, src.find("fun mergeImportInstanceJSON(")), "7",
                    "merge hit branch takes the local entry's id and drops the rest — "
                    "the backup's model-level values (overrides / isHidden / isCustom / "
                    "costTier / base model metadata) never reach the entry it matched "
                    "(%s absent from mergeImportInstanceJSON)" % MERGE_FIELDS_HELPER))
        return out
    n = len(re.findall(r"fun\s+mergeImportedEntryOntoLocal\s*\(", src))
    if n != 1:
        out.append((rel, 0, "7",
                    "mergeImportedEntryOntoLocal defined %d times — the entry-level "
                    "restore face must stay single-source" % n))
    m = re.search(r"mergeImportedEntryOntoLocal\(([^)]*)\)", code)
    if m is None:
        out.append((rel, line_of(src, src.find("fun mergeImportInstanceJSON(")), "7",
                    "mergeImportedEntryOntoLocal call has no readable argument list — "
                    "gate ROT"))
    else:
        args = m.group(1).strip()
        if args != "hit, entry":
            out.append((rel, line_of(src, src.find("fun mergeImportInstanceJSON(")), "7",
                        "merge hit branch does not hand the whole entry pair to the "
                        "single-source face (args=%r) — a field whitelist at the call "
                        "site drifts the moment ModelEntry gains a field" % args))
    if ENTRY_WRITE_FACE not in code:
        out.append((rel, line_of(src, src.find("fun mergeImportInstanceJSON(")), "7",
                    "merge hit branch restores fields but never writes them back — "
                    "%s absent from mergeImportInstanceJSON" % ENTRY_WRITE_FACE))
    else:
        # [batch5 G06-A4 · dataflow] Writing SOMETHING back is not enough: the
        # witness kept `val updated = mergeImportedEntryOntoLocal(hit, entry)`
        # and then called `updateEntry(hit, …)` — the merged value was computed
        # and thrown away, so a restore of an existing model silently kept the
        # old overrides. The argument written must BE the merged value.
        merged = set(m.group(1) for m in re.finditer(
            r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*=\s*mergeImportedEntryOntoLocal\s*\(", code))
        for m in re.finditer(r"\bupdateEntry\s*\(", code):
            args = call_args(code, code.index("(", m.start()))
            if not args:
                out.append((rel, line_of(src, src.find("fun mergeImportInstanceJSON(") + m.start()),
                            "7",
                            "updateEntry(…) call has no readable argument list — fail-closed"))
                continue
            first = code[args[0][0]:args[0][1]].strip()
            if MERGE_FIELDS_HELPER in first:
                continue
            ok = any(s.strip() in merged for s in value_sources(first))
            if not ok:
                out.append((rel, line_of(src, src.find("fun mergeImportInstanceJSON(") + m.start()),
                            "7",
                            "updateEntry(%s, …) writes a value that is NOT the merge result "
                            "(expected %s) — the restored overrides / isHidden / base-model "
                            "metadata are computed and then dropped, so importing over an "
                            "existing model keeps the local copy"
                            % (first[:40], ", ".join(sorted(merged)) or
                               "mergeImportedEntryOntoLocal(…)")))
    return out

# ----------------------------------------------------------------------
# Arm 8 — [fix/dv20-a2 A-04] saveConfig persists exactly what it publishes
# ----------------------------------------------------------------------
def check_save_persists_published(src, rel=PR_REL):
    """The publish and the persist must read the same local.

    [persistToDbAndMirror] serializes its argument into the prefs mirror (and
    hashes that string into DB meta). Handing it the pre-canonicalization
    object writes the legacy shape to disk while `_config.value` carries the
    canonical shape — the mirror and the live store disagree until the next
    save, and any reader that only sees the mirror (downgrade build, mirror
    fallback load, backup export reading prefs) gets the stale form.

    Anchored on the callee's argument expression, not the surrounding lines.
    """
    out = []
    body = function_text(src, "fun saveConfig(")
    if body is None:
        return [(rel, 0, "8", "saveConfig( not found — gate ROT")]
    code = code_view(body)
    lit = literal_view(body)
    calls = re.findall(r"persistToDbAndMirror\(\s*([A-Za-z_]\w*)\s*\)", code)
    if not calls:
        out.append((rel, line_of(src, src.find("fun saveConfig(")), "8",
                    "saveConfig no longer calls persistToDbAndMirror(<ident>) — gate ROT "
                    "(persist face renamed or fed a non-identifier expression)"))
        return out
    if len(calls) != 1:
        out.append((rel, line_of(src, src.find("fun saveConfig(")), "8",
                    "saveConfig calls persistToDbAndMirror %d times — the write path "
                    "must be single-shot" % len(calls)))
    persists = calls[0]
    pubs = re.findall(r"_config\.value\s*=\s*([A-Za-z_]\w*)", code)
    if len(pubs) != 1:
        out.append((rel, line_of(src, src.find("fun saveConfig(")), "8",
                    "saveConfig assigns _config.value from %d identifiers (expected 1) — "
                    "gate cannot tell what was published" % len(pubs)))
        return out
    published = pubs[0]
    if persists != published:
        out.append((rel, line_of(src, src.find("fun saveConfig(")), "8",
                    "saveConfig publishes %r but persists %r — the mirror + its stored "
                    "hash carry a different shape than the running store (canonicalize "
                    "runs on publish only)" % (published, persists)))
        return out
    # [wide-1024] Publishing the same NAME is not enough — the value it is built
    # from matters. The sweep's witness kept `_config.value = published` and
    # `persistToDbAndMirror(published)` intact while re-deriving `published` from
    # the raw `config` argument: the canonicalization ran, its result was never
    # used, and the mirror + DB kept the legacy uuid shape.
    canon = set()
    for m in re.finditer(r"\.\s*toSnapshot\s*\(", code):
        decls = list(re.finditer(r"\bval\s+([A-Za-z_]\w*)\s*(?::[^=\n]*)?=\s*",
                                 code[:m.start()]))
        if decls:
            canon.add(decls[-1].group(1))
    if not canon:
        out.append((rel, line_of(src, src.find("fun saveConfig(")), "8",
                    "no `val x = …toSnapshot(…).toProviderConfig(…)` canonicalization in "
                    "saveConfig — fail-closed, re-derive this criterion"))
        return out
    dm = re.search(r"\b(?:val|var)\s+%s\s*(?::[^=\n]*)?=\s*" % re.escape(published), code)
    if dm is None:
        out.append((rel, line_of(src, src.find("fun saveConfig(")), "8",
                    "the published identifier %r has no readable declaration — fail-closed"
                    % published))
        return out
    pub_end = expr_end(code, dm.end())
    expr = code[dm.end():pub_end].strip()
    root = re.match(r"([A-Za-z_]\w*)", expr)
    root = root.group(1) if root else ""
    if root not in canon:
        out.append((rel, line_of(src, src.find("fun saveConfig(")), "8",
                    "%s is derived from %r, not from the canonicalized value (%s) — the "
                    "canonicalization runs and its result is discarded, so the published "
                    "store and the mirror keep the pre-canonical id shape"
                    % (published, expr[:60], ", ".join(sorted(canon)))))
        return out
    # [batch5 G06-A5 · dataflow] The published value must carry the CANONICAL
    # collections. The witness kept `val published = canonical.copy(…)` and only
    # swapped one named argument for `config.modelEntries.toMutableList()` —
    # the declaration, the persist and the publish all name `published`, while
    # the mirror + DB receive the pre-canonical list (legacy uuid / duplicate
    # composite keys) and the running store keeps the canonical one. Judge the
    # VALUES of the copy arguments, not the presence of the `.copy(` call.
    raw_params = set()
    for pm in re.finditer(r"fun\s+saveConfig\s*\(", code):
        for a, b in call_args(code, code.index("(", pm.start())):
            nm = re.match(r"\s*([A-Za-z_]\w*)\s*:", code[a:b])
            if nm:
                raw_params.add(nm.group(1))
    raw_alt = "|".join(re.escape(n) for n in sorted(raw_params)) or "config"
    for cm in re.finditer(r"\.\s*copy\s*\(", code[dm.end():pub_end]):
        open_idx = dm.end() + cm.end() - 1
        for a, b in call_args(code, open_idx):
            kv = re.match(r"\s*([A-Za-z_]\w*)\s*=\s*(?![=>])", code[a:b])
            if not kv:
                continue
            arg_name = kv.group(1)
            val = code[a + kv.end():b].strip()
            raw_srcs = [s.strip() for s in value_sources(val)
                        if re.match(r"(?:%s)\s*\." % raw_alt, s.strip())]
            if not raw_srcs:
                continue
            copied = [s for s in raw_srcs
                      if re.search(r"\.\s*(?:toMutableList|toList|toMutableSet|toSet)\s*\(", s)]
            bare = [s for s in raw_srcs
                    if re.fullmatch(r"(?:%s)\s*\.\s*[A-Za-z_]\w*" % raw_alt, s)]
            if copied or bare:
                out.append((rel, line_of(src, src.find("fun saveConfig(") + a), "8",
                            "saveConfig publishes `published` but builds `%s = %s` from the "
                            "PRE-canonical argument, not from %s — the mirror + DB keep the "
                            "legacy list (temp uuids / duplicate composite keys) while the "
                            "running store is canonical, until the next save"
                            % (arg_name, val[:50], "/".join(sorted(canon)))))
    return out

# ----------------------------------------------------------------------
# Arm 9 — [fix/dv21-a3 A-03] a restore must not restamp userModifiedAt
# ----------------------------------------------------------------------
ENTRY_UPDATE_DECL = "fun updateEntry("
ENTRY_WRITE_TARGET = "config.modelEntries[idx]"

def _params_of(fn_text):
    """Declared parameter names of the `fun …(...)` header [fn_text] starts
    with (`Map<String, String>` stays one parameter)."""
    open_paren = fn_text.find("(")
    if open_paren < 0:
        return None
    close = match_bracket(fn_text, open_paren)
    if close < 0:
        return None
    names = _split_top_level(fn_text[open_paren + 1:close])
    return [m.group(1) for m in (re.match(r"\s*(\w+)\s*:", p) for p in names) if m]

def _assignment_statement(code, target):
    """The whole statement `target = …`, balanced from the `=` (multi-line for
    a brace/bracket-bodied right-hand side). None when the anchor drifted.

    Anchored on `target <ws> =` — NOT on the first `=` after the first
    occurrence, which would land on a later call's named argument when the
    target is itself the right-hand side of a declaration
    (`val existing = config.modelEntries[idx]`)."""
    m = re.search(re.escape(target) + r"\s*=(?!=)", code)
    if m is None:
        return None
    i, eq = m.start(), m.end() - 1
    depth = 0
    for j in range(eq, len(code)):
        c = code[j]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                nl = code.find("\n", j)
                return code[i:(nl if nl >= 0 else len(code))]
    return None

def _assignment_count(code, target):
    return len(re.findall(re.escape(target) + r"\s*=(?!=)", code))

def _split_top_level(text):
    """Split on commas at depth 0 (so `hit.copy(a = 1), flag = false` splits
    into two arguments while `Map<String, String>` stays one)."""
    parts, cur, depth = [], "", 0
    for ch in text:
        if ch in "([{<":
            depth += 1
        elif ch in ")]}>":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    parts.append(cur)
    return [p.strip() for p in parts]

def _call_args(code, name):
    """Argument lists of every bare `name( … )` call in [code].

    Each entry is the argument-list TEXT (the gate compares `hit.copy(…)` /
    `flag = false` shapes), or None when the list cannot be balanced.
    [batch5] Built on the shared `call_args` spans instead of the hand-rolled
    bracket scanner.
    """
    out = []
    for m in re.finditer(r"(?<![.\w])" + re.escape(name) + r"\s*\(", code):
        open_idx = code.index("(", m.start())
        close = match_bracket(code, open_idx)
        out.append(None if close < 0 else code[open_idx + 1:close])
    return out

def check_merge_preserves_entry_timestamp(src, rel=PR_REL):
    """A restore writes the backup's values — INCLUDING its userModifiedAt.

    Two coupled decision points, both fail-closed:

      a) the merge hit branch DECLARES the restore at the call site: the write
         face is called with a second argument selecting "do not stamp". The
         bare `updateEntry(updated)` shape (baseline) stamps
         `userModifiedAt = now`, which (i) makes `restored == local` — the
         no-op short-circuit in the same block — unreachable from the second
         import on, so every re-import rewrites every hit, and (ii) pushes this
         instance's shadowVoiceProviders mostRecentModified past a sibling the
         user really edited.
      b) the write face HONORS it: the statement writing the entry must consult
         the declared parameter, guard the stamp with a positive test on it,
         and carry `entry.userModifiedAt` on the other side of the branch. A
         decorative parameter (accepted, ignored) compiles and still stamps;
         an inverted test stamps the restore and preserves the editor.
      c) fail-closed: an absent declaration / unreadable write target is a ROT
         error, never a silent pass.
    """
    out = []
    merge_body = function_text(src, "fun mergeImportInstanceJSON(")
    if merge_body is None:
        return [(rel, 0, "9", "mergeImportInstanceJSON( not found — gate ROT")]
    code = code_view(merge_body)
    if MERGE_FIELDS_HELPER in code:
        calls = _call_args(code, "updateEntry")
        if len(calls) > 1:
            out.append((rel, line_of(src, src.find("fun mergeImportInstanceJSON(")), "9",
                        "merge hit branch writes the restored entry %d times — the "
                        "restore must go through the write face exactly once"
                        % len(calls)))
        elif len(calls) == 1:
            if calls[0] is None:
                out.append((rel, line_of(src, src.find("fun mergeImportInstanceJSON(")), "9",
                            "merge hit branch's updateEntry call has no readable argument "
                            "list — gate ROT"))
            else:
                args, parts = calls[0].strip(), _split_top_level(calls[0])
                if len(parts) < 2:
                    out.append((rel, line_of(src, src.find("fun mergeImportInstanceJSON(")), "9",
                                "merge hit branch writes the restored entry through the default "
                                "(editor) mode — `updateEntry(%s)` stamps userModifiedAt = now, "
                                "so the backup's timestamp never lands and the restored == local "
                                "short-circuit dies on the second import" % args))
                elif "false" not in parts[1]:
                    out.append((rel, line_of(src, src.find("fun mergeImportInstanceJSON(")), "9",
                                "merge hit branch does not select the restore mode at the call "
                                "site (second argument %r) — a restore must not stamp "
                                "userModifiedAt with now" % parts[1]))
        # 0 calls = the write face is gone entirely; arm 7 owns that regression.

    decl = src.find(ENTRY_UPDATE_DECL)
    if decl < 0:
        out.append((rel, 0, "9", ENTRY_UPDATE_DECL + " not found — gate ROT"))
        return out
    fn = function_text(src, ENTRY_UPDATE_DECL)
    if fn is None:
        out.append((rel, line_of(src, decl), "9", ENTRY_UPDATE_DECL + " has no body — gate ROT"))
        return out
    fn_code = code_view(fn)
    params = _params_of(fn_code)
    if params is None:
        out.append((rel, line_of(src, decl), "9",
                    "updateEntry's parameter list is unreadable — gate ROT"))
        return out
    if len(params) < 2:
        out.append((rel, line_of(src, decl), "9",
                    "updateEntry declares %d parameter(s) (%s) — the write face has no way "
                    "to tell a restore from an edit, so every restore stamps "
                    "userModifiedAt = now" % (len(params), params)))
        return out
    stamp_param = params[1]
    n_writes = _assignment_count(fn_code, ENTRY_WRITE_TARGET)
    if n_writes != 1:
        out.append((rel, line_of(src, decl), "9",
                    "updateEntry assigns %s %d times — the entry write must stay "
                    "single-shot for the stamp decision to be readable"
                    % (ENTRY_WRITE_TARGET, n_writes)))
        if n_writes == 0:
            return out
    stmt = _assignment_statement(fn_code, ENTRY_WRITE_TARGET)
    if stmt is None:
        out.append((rel, line_of(src, decl), "9",
                    "updateEntry no longer writes %s through a readable statement — "
                    "gate ROT" % ENTRY_WRITE_TARGET))
        return out
    if stamp_param not in stmt:
        out.append((rel, line_of(src, decl), "9",
                    "updateEntry ignores %r when writing the entry — the switch is "
                    "decorative and a restored timestamp is stamped with now anyway"
                    % stamp_param))
    elif not re.search(r"if\s*\(\s*%s\s*\)" % re.escape(stamp_param), stmt):
        out.append((rel, line_of(src, decl), "9",
                    "updateEntry's stamp is not guarded by a positive test on %r — an "
                    "absent or negated test flips the two modes (stamp on restore, "
                    "preserve on edit) while still reading the parameter" % stamp_param))
    elif "entry.userModifiedAt" not in stmt:
        out.append((rel, line_of(src, decl), "9",
                    "updateEntry's non-stamping branch does not carry "
                    "entry.userModifiedAt — the restore mode has nothing to preserve"))
    return out

# ----------------------------------------------------------------------
# ----------------------------------------------------------------------
# Arms 10/11 — [fix/dv22-a1 §1042-A-01] a DAO write must not be able to
# destroy the store that survives it (bare DAO call before the mirror write;
# bare DAO call in the one function whose nine siblings all wrap).
# ----------------------------------------------------------------------
PERSIST_DECL = "fun persistToDbAndMirror("
DAO_SYNC_CALL = "providerDao.syncAllIncremental("
MIRROR_WRITE_CALL = 'prefs.edit().putString("config", mirrorStr).commit()'
RESTORE_DECL = "fun restoreThinkingRules("
DAO_RESTORE_CALL = "providerDao.replaceThinkingRules("
FAILURE_HANDLERS = (".onFailure", ".getOrElse", ".getOrDefault", ".fold")


def _envelope_containing(code, idx):
    """(open_idx, end_idx, kind) of the INNERMOST runCatching{}/try{} region
    containing [idx], or None. Brace-matched on comment-stripped code."""
    best = None
    for m in re.finditer(r"\b(runCatching|try)\s*\{", code):
        open_idx = code.index("{", m.start())
        end = _end_after(code, open_idx)
        if end < 0:
            continue
        if open_idx < idx < end and (best is None or open_idx > best[0]):
            best = (open_idx, end, m.group(1))
    return best


def check_persist_dao_wrapped(src, rel=PR_REL):
    """[fix/dv22-a1 §1042-A-01] persistToDbAndMirror: the DB write must not be
    able to skip the mirror write.

    The DAO row-set sync runs BEFORE the prefs mirror write. Unwrapped, a
    provider.db that failed to open (migration abort — ProviderDatabase has no
    fallbackToDestructiveMigration) throws straight out of the function, the
    mirror write never runs, and the user loses the change entirely — with the
    DB down, prefs is the only surviving store (the caller logs "DB+mirror
    stale" while the mirror was never attempted).

    Two decision points, both fail-closed:
      A. the syncAllIncremental call sits inside a runCatching/try envelope;
      B. the mirror write sits OUTSIDE that envelope — inside it a DAO throw
         skips the mirror, i.e. the same defect wearing a wrapper.
    """
    out = []
    body = function_text(src, PERSIST_DECL)
    if body is None:
        return [(rel, 0, "10", "persistToDbAndMirror( not found — gate ROT")]
    dec_at = src.find(PERSIST_DECL)
    code, lit = code_view(body), literal_view(body)
    dao_rel = code.find(DAO_SYNC_CALL)
    if dao_rel < 0:
        return [(rel, line_of(src, dec_at), "10",
                 "persistToDbAndMirror no longer calls %s — gate ROT "
                 "(DB write face renamed or removed)" % DAO_SYNC_CALL)]
    env = _envelope_containing(code, dao_rel)
    if env is None:
        out.append((rel, line_of(src, dec_at + body.find("providerDao")), "10",
                    "the %s call is BARE — a provider.db that failed to open throws out "
                    "of persistToDbAndMirror before the mirror write, destroying the only "
                    "store that survives the DB being down" % DAO_SYNC_CALL))
    # The mirror anchor names a JSON key ("config"), which is blank in the
    # masked view — positions still line up because both views keep length.
    mirror_rel = lit.find(MIRROR_WRITE_CALL)
    if mirror_rel < 0:
        out.append((rel, line_of(src, dec_at), "10",
                    "the prefs mirror write is gone from persistToDbAndMirror — gate ROT "
                    "(mirror face renamed or removed)"))
        return out
    if env is not None and mirror_rel < env[1]:
        out.append((rel, line_of(src, dec_at + mirror_rel), "10",
                    "the prefs mirror write sits INSIDE the DAO write's %s envelope — a DAO "
                    "throw still skips it (the wrapper must not swallow the mirror too)"
                    % env[2]))
    else:
        # [batch5 G06-A6 · order/termination] Between the envelope and the mirror
        # write, the function must not give up. The witness kept the runCatching
        # envelope, the onFailure handler AND the mirror write, and only inserted
        # `dbWrite.getOrThrow()` after the handler: the DB failure is re-raised,
        # the mirror never runs, and with provider.db down the change is destroyed
        # — the exact loss this arm exists to prevent, now spelled as a rethrow.
        dead_zone = env[1] if env is not None else 0
        if mirror_rel > dead_zone:
            zone = code[dead_zone:mirror_rel]
            probs = []
            if re.search(r"\.\s*getOrThrow\s*\(", zone):
                probs.append("`.getOrThrow()` re-raises the DB failure")
            if re.search(r"(?<![.\w])throw\b", zone):
                probs.append("a `throw` sits between the DB write and the mirror write")
            if returns_exiting(code, dead_zone, mirror_rel):
                probs.append("an early `return` sits between the DB write and the mirror write")
            if re.search(r"\b(?:require|check)\s*\([^)]*\b(?:isFailure|isSuccess|"
                         r"exceptionOrNull|getOrNull)\b", zone):
                probs.append("a `require`/`check` on the DB outcome aborts the function "
                             "before the mirror write")
            if probs:
                out.append((rel, line_of(src, dec_at + dead_zone), "10",
                            "persistToDbAndMirror still loses the mirror write when the DB "
                            "write fails: %s — with provider.db down prefs is the only "
                            "surviving store, so the caller's change is destroyed instead of "
                            "degrading to mirror-only" % "; ".join(probs)))
    return out


def check_restore_dao_wrapped(src, rel=PR_REL):
    """[fix/dv22-a1 §1042-A-01] restoreThinkingRules: the DAO call must be
    wrapped AND the failure must not be silently discarded.

    Nine sibling functions in the same file wrap their DAO call
    (runCatching + a loud log — the [audit-0909 T4-H1] hunks); this one did
    not, so a provider.db that failed to open threw straight into the backup
    importer. A wrapper that throws away the failure is the silent-swallow
    form of the same defect, so the envelope must carry a failure handler.
    """
    out = []
    body = function_text(src, RESTORE_DECL)
    if body is None:
        return [(rel, 0, "11", "restoreThinkingRules( not found — gate ROT")]
    dec_at = src.find(RESTORE_DECL)
    code = code_view(body)
    call_rel = code.find(DAO_RESTORE_CALL)
    if call_rel < 0:
        return [(rel, line_of(src, dec_at), "11",
                 "restoreThinkingRules no longer calls %s — gate ROT "
                 "(restore write face renamed or removed)" % DAO_RESTORE_CALL)]
    env = _envelope_containing(code, call_rel)
    if env is None:
        return [(rel, line_of(src, dec_at + body.find("providerDao")), "11",
                 "the %s call is BARE — unlike the nine sibling functions in this "
                 "file, a provider.db that failed to open throws into the caller"
                 % DAO_RESTORE_CALL)]
    tail = code[env[1]:].lstrip()
    if env[2] == "runCatching":
        if not tail.startswith(FAILURE_HANDLERS):
            out.append((rel, line_of(src, dec_at + env[1]), "11",
                        "the runCatching envelope around %s carries no failure handler "
                        "(%s) — the failure is discarded silently"
                        % (DAO_RESTORE_CALL, " / ".join(FAILURE_HANDLERS))))
    elif not tail.startswith("catch"):
        out.append((rel, line_of(src, dec_at + env[1]), "11",
                    "the try envelope around %s has no catch clause at its close — "
                    "the failure escapes" % DAO_RESTORE_CALL))
    return out



# ----------------------------------------------------------------------
# [fix/dv23-a1 §1043-A01-A02] Arm 12 / Arm 13
# ----------------------------------------------------------------------
# Arm 12 — publish-order: the list handed to republishThinkingCache(instanceId, X)
# must be assigned only AFTER the DAO write returned. Assigning it before the
# write means a failed write (disk full / SQLite lock / IO error) still hands a
# non-null X to the republish fast path -> the resolver cache and
# customRulesRevision advance with rules that never reached provider.db.
#
# Arm 13 — cold-start guard: every agent-loop read-modify-write mutator must
# call ensureConfigLoaded() BEFORE reading _config.value, and the read must sit
# inside the configLock region. During the async load window _config holds an
# empty placeholder ProviderConfig(), so an unguarded read persists the empty
# list over the user's real pins.

REPUBLISH_CALL = re.compile(r"republishThinkingCache\(\s*instanceId\s*,\s*(\w+)\s*\)")
DAO_RULE_WRITE = "providerDao.replaceThinkingRules("
AGENT_LOOP_READ = re.compile(r"_config\.value\.agentLoop(?:ModelEntryIds|GroupIds)")
LOAD_GUARD = "ensureConfigLoaded()"
LOCK_CALL = "synchronized(configLock)"


def _fun_bodies(src):
    """[(name, body, start_idx)] for every `fun` in [src].

    Two body forms are handled: `{ … }` (brace-balanced) and `= <expr>`
    (expression bodies — `fun addAgentLoopEntry(id: String) = synchronized(lock) {…}`).
    An expression body is cut at the NEXT `fun` declaration of the same file,
    which is exact enough for the class-member layout of this file and keeps
    the whole `synchronized(configLock) { … }` block inside the body.
    """
    out = []
    pat = re.compile(
        r"(?m)^([ \t]*)(?:(?:private|internal|public|protected|override|suspend|"
        r"inline|open|final|abstract)\s+)*fun\s+(\w+)\s*\(")
    decls = list(pat.finditer(src))
    for n, m in enumerate(decls):
        popen = src.index("(", m.end() - 1)
        pend = _end_after(src, popen)
        if pend < 0:
            continue
        i = pend
        while i < len(src) and src[i] in " \t":
            i += 1
        if i < len(src) and src[i] == ":":
            while i < len(src) and src[i] not in "{=\n":
                i += 1
        if i >= len(src):
            continue
        if src[i] == "{":
            end = _end_after(src, i)
            if end < 0:
                continue
            body = src[i:end]
        elif src[i] == "=":
            nxt = decls[n + 1].start() if n + 1 < len(decls) else len(src)
            body = src[i:nxt]
        else:
            continue
        out.append((m.group(2), body, m.start()))
    return out


def _body_line(src, body_start, code, idx):
    """Absolute line number of an index inside a comment-stripped body.

    The shared views keep the line count (comments become spaces, strings stay
    blanked), so the line offset is the newline count, not the character
    offset.
    """
    return line_of(src, body_start) + code.count("\n", 0, idx)


_FAIL_TAIL_RE = re.compile(r"\.\s*(?:onFailure|getOrElse|recover|recoverCatching)\s*(?:\(|\{)")
_CATCH_RE = re.compile(r"\bcatch\s*\(")


def _failure_regions(code):
    """[(start, end, label)] — bodies that run only when the surrounding write
    FAILED (`.onFailure { … }`, `.getOrElse { … }`, `.recover{…}`, `catch (…) {…}`)."""
    regions = []
    for rx, label in ((_FAIL_TAIL_RE, "failure handler"), (_CATCH_RE, "catch block")):
        for m in rx.finditer(code):
            brace = code.find("{", m.start())
            if brace < 0:
                continue
            end = _end_after(code, brace)
            if end > 0:
                regions.append((brace, end, "%s `%s`" % (label, m.group(0).strip())))
    return regions


def _failure_region_at(code, pos):
    """Label of the failure-only region containing [pos], or None."""
    for start, end, label in _failure_regions(code):
        if start <= pos < end:
            return label
    return None


def check_thinking_rule_publish_order(src, rel=PR_REL):
    """[fix/dv23-a1 A-01] Arm 12: publish-after-persist ordering.

    The candidate set is DERIVED, not hardcoded: every function that hands a
    list variable to republishThinkingCache(instanceId, X) AND assigns X.
    For each candidate the DAO write must exist and must come BEFORE the
    assignment.
    """
    out = []
    seen = 0
    for name, body, start in _fun_bodies(src):
        code = code_view(body)
        pm = REPUBLISH_CALL.search(code)
        if not pm:
            continue
        var = pm.group(1)
        assigns = [a.start() for a in re.finditer(
            r"\b%s\s*=\s*[^=]" % re.escape(var), code)]
        if not assigns:
            continue
        seen += 1
        first = min(assigns)
        dao = code.find(DAO_RULE_WRITE)
        if dao < 0:
            out.append((rel, _body_line(src, start, code, first), "12",
                        "%s publishes %s to the resolver cache but never writes the "
                        "rule set through %s — the published list is not the "
                        "persisted one (gate ROT: the write face was renamed or "
                        "removed)" % (name, var, DAO_RULE_WRITE)))
            continue
        if first < dao:
            out.append((rel, _body_line(src, start, code, first), "12",
                        "%s assigns %s BEFORE %s — when the write throws "
                        "(disk full / SQLite lock / IO error) the variable is still "
                        "non-null, so republishThinkingCache takes the 'persisted' "
                        "fast path and publishes rules that never reached "
                        "provider.db" % (name, var, DAO_RULE_WRITE)))
        # [wide-1024] "After the DAO write" is necessary, not sufficient: the
        # value must also exist ONLY on the success path. The sweep's witness
        # added `persistedRules = listOf(rule)` inside `.onFailure { … }` — the
        # ordering check still saw the in-block assignment first, but a failed
        # write now hands a non-null list to the publisher (memory/disk split).
        if not re.search(r"\bvar\s+%s\s*:[^=\n]*=\s*null\b" % re.escape(var), code):
            out.append((rel, _body_line(src, start, code, first), "12",
                        "%s is not initialised to null — the 'failed write ⇒ re-read the DB' "
                        "fallback has no value to fall back to (fail-closed)" % var))
        for a in assigns:
            region = _failure_region_at(code, a)
            if region:
                out.append((rel, _body_line(src, start, code, a), "12",
                            "%s assigns %s inside %s — that body runs only when the write "
                            "FAILED, so the publisher receives a non-null rule set that "
                            "never reached provider.db (memory/disk split until restart)"
                            % (name, var, region)))
    if not seen:
        out.append((rel, 0, "12",
                    "no rule-publishing mutator found — gate ROT "
                    "(the publish face was renamed or removed)"))
    return out


def check_agent_loop_load_guard(src, rel=PR_REL):
    """[fix/dv23-a1 A-02] Arm 13: cold-start load guard + lock coverage.

    The candidate set is DERIVED: every function that reads
    _config.value.agentLoopModelEntryIds / .agentLoopGroupIds. For each:
      A. ensureConfigLoaded() must be called BEFORE that read;
      B. the read must sit inside a synchronized(configLock) { … } region.
    """
    out = []
    seen = 0
    for name, body, start in _fun_bodies(src):
        code = code_view(body)
        reads = [m.start() for m in AGENT_LOOP_READ.finditer(code)]
        if not reads:
            continue
        seen += 1
        first = min(reads)
        guard = code.find(LOAD_GUARD)
        if guard < 0:
            out.append((rel, _body_line(src, start, code, first), "13",
                        "%s reads the agent-loop pin list without calling %s — "
                        "during the cold-start async load window _config still "
                        "holds the empty placeholder ProviderConfig(), so this "
                        "write persists the empty list over the user's pins"
                        % (name, LOAD_GUARD)))
        elif guard > first:
            out.append((rel, _body_line(src, start, code, guard), "13",
                        "%s calls %s AFTER reading the agent-loop pin list — the "
                        "placeholder config is already read by then"
                        % (name, LOAD_GUARD)))
        regions = []
        for m in re.finditer(r"synchronized\(\s*configLock\s*\)\s*\{", code):
            o = code.index("{", m.start())
            e = _end_after(code, o)
            if e > 0:
                regions.append((o, e))
        if not regions:
            out.append((rel, _body_line(src, start, code, first), "13",
                        "%s reads the agent-loop pin list outside any %s region — "
                        "the read/write symmetry against the async loader's emit "
                        "is broken" % (name, LOCK_CALL)))
        elif not any(o < first < e for o, e in regions):
            out.append((rel, _body_line(src, start, code, first), "13",
                        "%s reads the agent-loop pin list BEFORE the %s region — "
                        "the read is not covered by the lock" % (name, LOCK_CALL)))
    if not seen:
        out.append((rel, 0, "13",
                    "no agent-loop pin mutator found — gate ROT "
                    "(the read face was renamed or removed)"))
    return out


def run_checks(root):
    pr = read(PR_REL, root)
    bm = read(BM_REL, root)
    lm = read(LM_REL, root)
    missing = [(rel, 0, "?", "file not found — gate ROT")
               for rel, src in ((PR_REL, pr), (BM_REL, bm), (LM_REL, lm)) if src is None]
    if missing:
        return missing
    srcs = {"pr": pr, "bm": bm, "lm": lm}
    checks = []
    checks += check_merge_requery(pr)
    checks += check_reasoning_keys(pr, PR_REL, "fun exportInstanceJSON(", "2", "exportInstanceJSON", EXPORT_ANCHORS)
    checks += check_reasoning_keys(pr, PR_REL, "fun parseImportedModelEntries(", "3", "parseImportedModelEntries", PARSE_ANCHORS)
    checks += check_convert_echo(bm)
    checks += check_field_completeness(srcs)
    checks += check_merge_shares_instance_fields(pr)
    checks += check_merge_restores_entry_fields(pr)
    checks += check_save_persists_published(pr)
    checks += check_merge_preserves_entry_timestamp(pr)
    checks += check_persist_dao_wrapped(pr)
    checks += check_restore_dao_wrapped(pr)
    checks += check_thinking_rule_publish_order(pr)
    checks += check_agent_loop_load_guard(pr)
    return checks


# ----------------------------------------------------------------------
# Selftest fixtures — minimal Kotlin-shaped files exercising every arm.
# ----------------------------------------------------------------------
FIX_LM = """package com.rikkaminis.app.data.model

@Serializable
data class LLMModel(
    val id: String,
    val displayName: String,
    val provider: String,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val supportsReasoning: Boolean? = null,
    val interleavedReasoningField: String? = null,
    val reasoningEffortValues: List<String>? = null,
    val declaresNoEffortTiers: Boolean? = null,
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null,
)
"""

FIX_EXPORT_OK = """fun exportInstanceJSON(instanceId: String): String? {
    val obj = JSONObject()
    for (entry in entries) {
        modelsArr.put(JSONObject().apply {
            put("modelId", entry.baseModel.id)
            put("displayName", entry.baseModel.displayName)
            entry.baseModel.contextWindow?.let { put("contextWindow", it) }
            entry.baseModel.maxOutputTokens?.let { put("maxOutputTokens", it) }
            entry.baseModel.supportsReasoning?.let { put("supportsReasoning", it) }
            entry.baseModel.interleavedReasoningField?.let { put("interleavedReasoningField", it) }
            entry.baseModel.reasoningEffortValues?.takeIf { it.isNotEmpty() }?.let {
                put("reasoningEffortValues", JSONArray(it))
            }
            entry.baseModel.declaresNoEffortTiers?.let { put("declaresNoEffortTiers", it) }
            entry.baseModel.inputModalities?.let { put("inputModalities", JSONArray(it)) }
            entry.baseModel.outputModalities?.let { put("outputModalities", JSONArray(it)) }
        })
    }
    return obj.toString()
}
"""

FIX_EXPORT_BAD = """fun exportInstanceJSON(instanceId: String): String? {
    val obj = JSONObject()
    for (entry in entries) {
        modelsArr.put(JSONObject().apply {
            put("modelId", entry.baseModel.id)
            put("displayName", entry.baseModel.displayName)
            entry.baseModel.contextWindow?.let { put("contextWindow", it) }
            entry.baseModel.maxOutputTokens?.let { put("maxOutputTokens", it) }
            entry.baseModel.supportsReasoning?.let { put("supportsReasoning", it) }
            entry.baseModel.interleavedReasoningField?.let { put("interleavedReasoningField", it) }
            entry.baseModel.inputModalities?.let { put("inputModalities", JSONArray(it)) }
            entry.baseModel.outputModalities?.let { put("outputModalities", JSONArray(it)) }
        })
    }
    return obj.toString()
}
"""

FIX_PARSE_OK = """private fun parseImportedModelEntries(dict: JSONObject, instanceId: String): List<ModelEntry> {
    val entries = mutableListOf<ModelEntry>()
    for (i in 0 until models.length()) {
        val m = models.getJSONObject(i)
        val modelId = m.optString("modelId", "")
        val displayName = m.optString("displayName", modelId)
        val contextWindow = if (m.has("contextWindow")) m.optInt("contextWindow").takeIf { it > 0 } else null
        val maxOutputTokens = if (m.has("maxOutputTokens")) m.optInt("maxOutputTokens").takeIf { it > 0 } else null
        val supportsReasoning = if (m.has("supportsReasoning")) m.optBoolean("supportsReasoning") else null
        val interleavedReasoningField = m.optString("interleavedReasoningField", "").ifEmpty { null }
        val reasoningEffortValues = m.optJSONArray("reasoningEffortValues")?.let { arr ->
            (0 until arr.length())
                .mapNotNull { idx -> arr.optString(idx, "").takeIf { it.isNotBlank() } }
                .takeIf { it.isNotEmpty() }
        }
        val declaresNoEffortTiers = if (m.has("declaresNoEffortTiers") && !m.isNull("declaresNoEffortTiers")) {
            m.optBoolean("declaresNoEffortTiers")
        } else null
        val (baseIn, baseOut) = readModalitiesWithBitfieldFallback(m)
        val model = LLMModel(
            id = modelId,
            displayName = displayName,
            contextWindow = contextWindow,
            maxOutputTokens = maxOutputTokens,
            supportsReasoning = supportsReasoning,
            interleavedReasoningField = interleavedReasoningField,
            inputModalities = baseIn,
            outputModalities = baseOut,
            reasoningEffortValues = reasoningEffortValues,
            declaresNoEffortTiers = declaresNoEffortTiers,
        )
        entries.add(ModelEntry(providerInstanceId = instanceId, baseModel = model))
    }
    return entries
}
"""

FIX_PARSE_BAD = """private fun parseImportedModelEntries(dict: JSONObject, instanceId: String): List<ModelEntry> {
    val entries = mutableListOf<ModelEntry>()
    for (i in 0 until models.length()) {
        val m = models.getJSONObject(i)
        val modelId = m.optString("modelId", "")
        val displayName = m.optString("displayName", modelId)
        val contextWindow = if (m.has("contextWindow")) m.optInt("contextWindow").takeIf { it > 0 } else null
        val maxOutputTokens = if (m.has("maxOutputTokens")) m.optInt("maxOutputTokens").takeIf { it > 0 } else null
        val supportsReasoning = if (m.has("supportsReasoning")) m.optBoolean("supportsReasoning") else null
        val interleavedReasoningField = m.optString("interleavedReasoningField", "").ifEmpty { null }
        val (baseIn, baseOut) = readModalitiesWithBitfieldFallback(m)
        val model = LLMModel(
            id = modelId,
            displayName = displayName,
            contextWindow = contextWindow,
            maxOutputTokens = maxOutputTokens,
            supportsReasoning = supportsReasoning,
            interleavedReasoningField = interleavedReasoningField,
            inputModalities = baseIn,
            outputModalities = baseOut,
        )
        entries.add(ModelEntry(providerInstanceId = instanceId, baseModel = model))
    }
    return entries
}
"""

# M3-mimicking variant: the constructor argument stays (bare key presence
# would satisfy it) but the READ is dropped — the read/writer-call anchors
# must catch exactly this false green.
FIX_PARSE_READDROPPED_BAD = """private fun parseImportedModelEntries(dict: JSONObject, instanceId: String): List<ModelEntry> {
    val entries = mutableListOf<ModelEntry>()
    for (i in 0 until models.length()) {
        val m = models.getJSONObject(i)
        val modelId = m.optString("modelId", "")
        val displayName = m.optString("displayName", modelId)
        val contextWindow = if (m.has("contextWindow")) m.optInt("contextWindow").takeIf { it > 0 } else null
        val maxOutputTokens = if (m.has("maxOutputTokens")) m.optInt("maxOutputTokens").takeIf { it > 0 } else null
        val supportsReasoning = if (m.has("supportsReasoning")) m.optBoolean("supportsReasoning") else null
        val interleavedReasoningField = m.optString("interleavedReasoningField", "").ifEmpty { null }
        val (baseIn, baseOut) = readModalitiesWithBitfieldFallback(m)
        val model = LLMModel(
            id = modelId,
            displayName = displayName,
            contextWindow = contextWindow,
            maxOutputTokens = maxOutputTokens,
            supportsReasoning = supportsReasoning,
            interleavedReasoningField = interleavedReasoningField,
            inputModalities = baseIn,
            outputModalities = baseOut,
            reasoningEffortValues = null,
            declaresNoEffortTiers = null,
        )
        entries.add(ModelEntry(providerInstanceId = instanceId, baseModel = model))
    }
    return entries
}
"""

# [fix/dv16-a A-08] Shared instance-field decode / write face (append + merge
# restore paths). Appended to every fixture PR tree so arm 6's single-source
# definition check is exercisable.
# [fix/dv20-a2 A-08] The single-source entry-restore face the hit branch must
# route through; fixture copy of the production helper.
FIX_MERGE_FIELDS_HELPER = """
internal fun mergeImportedEntryOntoLocal(local: ModelEntry, imported: ModelEntry): ModelEntry? {
    val restored = imported.copy(uuid = local.id)
    return if (restored == local) null else restored
}
"""

# [fix/dv20-a2 A-04] Compliant saveConfig: publishes and persists the same local.
FIX_SAVE_CANONICAL = """
fun saveConfig(config: ProviderConfig) {
    synchronized(configLock) {
        val canonical = config.toSnapshot(json).toProviderConfig(json)
        val published = canonical.copy(revision = config.revision + 1)
        _config.value = published
        persistExecutor.execute {
            runBlocking { persistToDbAndMirror(published) }
        }
    }
}
"""

# [fix/dv20-a2 A-04] Breaker: publishes the canonical object, persists the raw
# pre-canonicalization one — the exact baseline shape.
FIX_SAVE_RAW_BAD = """
fun saveConfig(config: ProviderConfig) {
    synchronized(configLock) {
        val canonical = config.toSnapshot(json).toProviderConfig(json)
        val published = canonical.copy(revision = config.revision + 1)
        _config.value = published
        persistExecutor.execute {
            runBlocking { persistToDbAndMirror(config) }
        }
    }
}
"""

# [fix/dv22-a1 §1042-A-01] Arm 10 fixtures: persistToDbAndMirror must not be
# able to skip the prefs mirror write when the DB write throws. Appended to
# every fixture tree so the arm is exercisable in both directions.
FIX_PERSIST_OK = """
private suspend fun persistToDbAndMirror(config: ProviderConfig): ProviderConfig {
    val mirrorStr = json.encodeToString(ProviderConfig.serializer(), config)
    val snapshot = config.toSnapshot(json, jsonSyncHash = hashJsonMirror(mirrorStr))
    val dbWrite = runCatching {
        providerDao.syncAllIncremental(
            instances = snapshot.instances,
            entries = snapshot.entries,
            meta = snapshot.meta,
        )
    }
    dbWrite.onFailure { e ->
        android.util.Log.e("ProviderRepo", "DB incremental sync failed: ${'$'}{e.message}", e)
    }
    val mirrorWritten = prefs.edit().putString("config", mirrorStr).commit()
    if (!mirrorWritten) {
        android.util.Log.w("ProviderRepo", "mirror commit() returned false")
    }
    return snapshot.toProviderConfig(json)
}
"""

# Breaker: the baseline shape — the DAO call is BARE before the mirror write,
# so a provider.db that failed to open throws out and the mirror never lands.
FIX_PERSIST_BARE_BAD = """
private suspend fun persistToDbAndMirror(config: ProviderConfig): ProviderConfig {
    val mirrorStr = json.encodeToString(ProviderConfig.serializer(), config)
    val snapshot = config.toSnapshot(json, jsonSyncHash = hashJsonMirror(mirrorStr))
    providerDao.syncAllIncremental(
        instances = snapshot.instances,
        entries = snapshot.entries,
        meta = snapshot.meta,
    )
    val mirrorWritten = prefs.edit().putString("config", mirrorStr).commit()
    return snapshot.toProviderConfig(json)
}
"""

# Breaker: wrapped, but the mirror write is INSIDE the envelope — a DAO throw
# still skips it (the same defect wearing a wrapper).
FIX_PERSIST_MIRROR_INSIDE_BAD = """
private suspend fun persistToDbAndMirror(config: ProviderConfig): ProviderConfig {
    val mirrorStr = json.encodeToString(ProviderConfig.serializer(), config)
    val snapshot = config.toSnapshot(json, jsonSyncHash = hashJsonMirror(mirrorStr))
    runCatching {
        providerDao.syncAllIncremental(
            instances = snapshot.instances,
            entries = snapshot.entries,
            meta = snapshot.meta,
        )
        val mirrorWritten = prefs.edit().putString("config", mirrorStr).commit()
    }.onFailure { e ->
        android.util.Log.e("ProviderRepo", "DB incremental sync failed: ${'$'}{e.message}", e)
    }
    return snapshot.toProviderConfig(json)
}
"""

# fail-closed: the mirror write face is gone -> ROT, not a silent pass.
FIX_PERSIST_NOMIRROR_BAD = """
private suspend fun persistToDbAndMirror(config: ProviderConfig): ProviderConfig {
    val mirrorStr = json.encodeToString(ProviderConfig.serializer(), config)
    val snapshot = config.toSnapshot(json, jsonSyncHash = hashJsonMirror(mirrorStr))
    val dbWrite = runCatching {
        providerDao.syncAllIncremental(instances = snapshot.instances)
    }
    dbWrite.onFailure { e -> android.util.Log.e("ProviderRepo", "failed", e) }
    return snapshot.toProviderConfig(json)
}
"""

# [fix/dv22-a1 §1042-A-01] Arm 11 fixtures: restoreThinkingRules must wrap its
# DAO call (like its nine siblings) and must not silently discard the failure.
FIX_RESTORE_OK = """
private suspend fun restoreThinkingRules(instanceId: String, rows: JSONArray): Int = runBlocking {
    val entities = ArrayList<ProviderThinkingRuleEntity>()
    for (i in 0 until rows.length()) {
        val o = rows.optJSONObject(i) ?: continue
        val id = o.optString("id", "")
        if (id.isEmpty()) continue
        entities.add(
            ProviderThinkingRuleEntity(
                id = id,
                providerInstanceId = instanceId,
                label = o.optString("label", ""),
                sortOrder = o.optInt("sortOrder", i),
            ),
        )
    }
    if (entities.isEmpty()) return@runBlocking 0
    val persisted = runCatching {
        providerDao.replaceThinkingRules(instanceId, entities)
    }.onFailure { e ->
        android.util.Log.e("ProviderRepo", "restoreThinkingRules failed: ${'$'}{e.message}", e)
    }.isSuccess
    if (!persisted) return@runBlocking 0
    republishThinkingCache(instanceId)
    entities.size
}
"""

# Breaker: the baseline shape — bare DAO call, the exception escapes into the
# backup importer while the nine siblings all wrap.
FIX_RESTORE_BARE_BAD = """
private suspend fun restoreThinkingRules(instanceId: String, rows: JSONArray): Int = runBlocking {
    val entities = ArrayList<ProviderThinkingRuleEntity>()
    if (entities.isEmpty()) return@runBlocking 0
    providerDao.replaceThinkingRules(instanceId, entities)
    republishThinkingCache(instanceId)
    entities.size
}
"""

# Breaker: wrapped but the failure is discarded silently (no handler) — the
# silent-swallow form of the same defect.
FIX_RESTORE_SILENT_BAD = """
private suspend fun restoreThinkingRules(instanceId: String, rows: JSONArray): Int = runBlocking {
    val entities = ArrayList<ProviderThinkingRuleEntity>()
    if (entities.isEmpty()) return@runBlocking 0
    val persisted = runCatching {
        providerDao.replaceThinkingRules(instanceId, entities)
    }.isSuccess
    if (!persisted) return@runBlocking 0
    republishThinkingCache(instanceId)
    entities.size
}
"""

# Breaker: a try envelope with no catch — the failure escapes anyway.
FIX_RESTORE_NOCATCH_BAD = """
private suspend fun restoreThinkingRules(instanceId: String, rows: JSONArray): Int = runBlocking {
    val entities = ArrayList<ProviderThinkingRuleEntity>()
    if (entities.isEmpty()) return@runBlocking 0
    try {
        providerDao.replaceThinkingRules(instanceId, entities)
    }
    republishThinkingCache(instanceId)
    entities.size
}
"""

# fail-closed: the restore write face is gone -> ROT.
FIX_RESTORE_MISSING_BAD = """
private suspend fun restoreThinkingRules(instanceId: String, rows: JSONArray): Int = runBlocking {
    val entities = ArrayList<ProviderThinkingRuleEntity>()
    if (entities.isEmpty()) return@runBlocking 0
    republishThinkingCache(instanceId)
    entities.size
}
"""

# [fix/dv20-a2 A-08] Breaker: the baseline hit-branch shape — id taken, fields
# dropped.
FIX_MERGE_ID_ONLY_BAD = """fun mergeImportInstanceJSON(
    jsonStr: String,
    srcEntryIds: List<String>,
): Pair<String, Map<String, String>>? {
    val entries = parseImportedModelEntries(dict, existing.id)
    val entryMap = HashMap<String, String>()
    for (i in entries.indices) {
        val srcId = srcEntryIds.getOrNull(i) ?: ""
        val entry = entries[i]
        val hit = _config.value.modelEntries.firstOrNull {
            it.providerInstanceId == existing.id && it.baseModel.id == entry.baseModel.id
        }
        val resolvedEntryId = hit?.id ?: run {
            addEntry(entry)
            _config.value.modelEntries.firstOrNull {
                it.providerInstanceId == existing.id && it.baseModel.id == entry.baseModel.id
            }?.id ?: compositeEntryKey(existing.id, entry.baseModel.id)
        }
        if (srcId.isNotEmpty()) entryMap[srcId] = resolvedEntryId
    }
    val fields = readImportInstanceFields(dict)
    val updated = applyImportInstanceFields(existing, fields)
    if (updated != existing) updateInstance(updated)
    return existing.id to entryMap
}
"""

# [fix/dv20-a2 A-08] Breaker: a hand-written field whitelist at the call site —
# restores today's fields, drifts the moment ModelEntry gains one.
FIX_MERGE_WHITELIST_BAD = """fun mergeImportInstanceJSON(
    jsonStr: String,
    srcEntryIds: List<String>,
): Pair<String, Map<String, String>>? {
    val entries = parseImportedModelEntries(dict, existing.id)
    val entryMap = HashMap<String, String>()
    for (i in entries.indices) {
        val srcId = srcEntryIds.getOrNull(i) ?: ""
        val entry = entries[i]
        val hit = _config.value.modelEntries.firstOrNull {
            it.providerInstanceId == existing.id && it.baseModel.id == entry.baseModel.id
        }
        val resolvedEntryId = if (hit != null) {
            updateEntry(hit.copy(overrides = entry.overrides, isHidden = entry.isHidden), stampUserModified = false)
            hit.id
        } else {
            addEntry(entry)
            compositeEntryKey(existing.id, entry.baseModel.id)
        }
        if (srcId.isNotEmpty()) entryMap[srcId] = resolvedEntryId
    }
    val fields = readImportInstanceFields(dict)
    val updated = applyImportInstanceFields(existing, fields)
    if (updated != existing) updateInstance(updated)
    return existing.id to entryMap
}
"""

FIX_HELPERS = """
internal data class RunConfigSnapshot(
    val isEnabled: Boolean = true,
    val azureMode: Boolean = false,
    val imageEndpointMode: ImageEndpointMode = ImageEndpointMode.auto,
    val imageEndpointResolved: ImageEndpointMode? = null,
    val pinned: Boolean = false,
)

internal fun readRunConfigFields(dict: JSONObject): RunConfigSnapshot = RunConfigSnapshot()

internal data class ImportInstanceFields(
    val customBaseURL: String?,
    val appendV1Suffix: Boolean,
    val useResponsesAPI: Boolean,
    val customUserAgent: String?,
    val runConfig: RunConfigSnapshot,
)

internal fun readImportInstanceFields(dict: JSONObject): ImportInstanceFields =
    ImportInstanceFields(
        customBaseURL = dict.optString("customBaseURL", "").ifEmpty { null },
        appendV1Suffix = dict.optBoolean("appendV1Suffix", true),
        useResponsesAPI = dict.optBoolean("useResponsesAPI", false),
        customUserAgent = dict.optString("customUserAgent", "").ifEmpty { null },
        runConfig = readRunConfigFields(dict),
    )

internal fun applyImportInstanceFields(base: ProviderInstance, f: ImportInstanceFields): ProviderInstance =
    base.copy(
        customBaseURL = f.customBaseURL,
        appendV1Suffix = f.appendV1Suffix,
        useResponsesAPI = f.useResponsesAPI,
        customUserAgent = f.customUserAgent,
        isEnabled = f.runConfig.isEnabled,
        azureMode = f.runConfig.azureMode,
        imageEndpointMode = f.runConfig.imageEndpointMode,
        imageEndpointResolved = f.runConfig.imageEndpointResolved,
        pinned = f.runConfig.pinned,
    )
"""

# [fix/dv16-a A-08] The append path decodes through the shared seam.
FIX_IMPORT_OK = """fun importInstanceJSON(jsonStr: String): String? {
    val dict = JSONObject(jsonStr)
    val f = readImportInstanceFields(dict)
    val instance = ProviderInstance(
        id = newId(),
        label = resolvedLabel,
        customBaseURL = f.customBaseURL,
        appendV1Suffix = f.appendV1Suffix,
        useResponsesAPI = f.useResponsesAPI,
        customUserAgent = f.customUserAgent,
        isEnabled = f.runConfig.isEnabled,
        azureMode = f.runConfig.azureMode,
        imageEndpointMode = f.runConfig.imageEndpointMode,
        imageEndpointResolved = f.runConfig.imageEndpointResolved,
        pinned = f.runConfig.pinned,
    )
    addInstance(instance)
    return resolvedLabel
}
"""

FIX_MERGE_OK = """fun mergeImportInstanceJSON(
    jsonStr: String,
    srcEntryIds: List<String>,
): Pair<String, Map<String, String>>? {
    val entries = parseImportedModelEntries(dict, existing.id)
    val entryMap = HashMap<String, String>()
    for (i in entries.indices) {
        val srcId = srcEntryIds.getOrNull(i) ?: ""
        val entry = entries[i]
        val hit = _config.value.modelEntries.firstOrNull {
            it.providerInstanceId == existing.id && it.baseModel.id == entry.baseModel.id
        }
        val resolvedEntryId = if (hit != null) {
            val restored = mergeImportedEntryOntoLocal(hit, entry)
            if (restored == null) hit.id else (updateEntry(restored, stampUserModified = false) ?: hit.id)
        } else {
            addEntry(entry)
            _config.value.modelEntries.firstOrNull {
                it.providerInstanceId == existing.id && it.baseModel.id == entry.baseModel.id
            }?.id ?: compositeEntryKey(existing.id, entry.baseModel.id)
        }
        if (srcId.isNotEmpty()) entryMap[srcId] = resolvedEntryId
    }
    // [fix/dv20-a2 A-08] A restore is a restore: the hit branch restores the
    // backup's model-level values through the single-source helper + the
    // whole-entry write face. (Arms 7/8 assume this shape as the compliant
    // baseline; the per-arm breakers below each remove exactly one decision.)
    val fields = readImportInstanceFields(dict)
    val updated = applyImportInstanceFields(existing, fields)
    if (updated != existing) updateInstance(updated)
    return existing.id to entryMap
}
"""

FIX_MERGE_BAD = """fun mergeImportInstanceJSON(
    jsonStr: String,
    srcEntryIds: List<String>,
): Pair<String, Map<String, String>>? {
    val entries = parseImportedModelEntries(dict, existing.id)
    val entryMap = HashMap<String, String>()
    for (i in entries.indices) {
        val srcId = srcEntryIds.getOrNull(i) ?: ""
        val entry = entries[i]
        val hit = _config.value.modelEntries.firstOrNull {
            it.providerInstanceId == existing.id && it.baseModel.id == entry.baseModel.id
        }
        val resolvedEntryId = if (hit != null) {
            val restored = mergeImportedEntryOntoLocal(hit, entry)
            if (restored == null) hit.id else (updateEntry(restored, stampUserModified = false) ?: hit.id)
        } else {
            addEntry(entry)
            entry.id
        }
        if (srcId.isNotEmpty()) entryMap[srcId] = resolvedEntryId
    }
    val fields = readImportInstanceFields(dict)
    val updated = applyImportInstanceFields(existing, fields)
    if (updated != existing) updateInstance(updated)
    return existing.id to entryMap
}
"""

# [fix/dv21-a3 A-03] The entry write face's timestamp decision. Compliant:
# the restore mode (stampUserModified = false) keeps the backup's own
# userModifiedAt; the default (true) stamps now for the editor paths.
FIX_UPDATE_ENTRY_OK = """
fun updateEntry(entry: ModelEntry, stampUserModified: Boolean = true): String? {
    val existing = config.modelEntries.first { it.id == entry.id }
    val newUuid = existing.id
    config.modelEntries[idx] = entry.copy(
        uuid = newUuid,
        userModifiedAt = if (stampUserModified) System.currentTimeMillis() else entry.userModifiedAt,
    )
    return newUuid
}
"""

# Breaker: the switch exists but the stamp is unconditional — the baseline
# write face (dv20-a2 A-08), where a restore silently re-dates the entry.
FIX_UPDATE_ENTRY_STAMP_BAD = """
fun updateEntry(entry: ModelEntry, stampUserModified: Boolean = true): String? {
    val existing = config.modelEntries.first { it.id == entry.id }
    val newUuid = existing.id
    config.modelEntries[idx] = entry.copy(uuid = newUuid, userModifiedAt = System.currentTimeMillis())
    return newUuid
}
"""

# Breaker: no switch at all (the pre-fix signature) — the fail-closed arm.
FIX_UPDATE_ENTRY_ONEPARAM_BAD = """
fun updateEntry(entry: ModelEntry): String? {
    val existing = config.modelEntries.first { it.id == entry.id }
    val newUuid = existing.id
    config.modelEntries[idx] = entry.copy(uuid = newUuid, userModifiedAt = System.currentTimeMillis())
    return newUuid
}
"""

# Breaker: the hit branch keeps the write face but drops the restore flag —
# the exact call-site regression arm 9a exists for.
FIX_MERGE_STAMP_BAD = FIX_MERGE_OK.replace(
    "updateEntry(restored, stampUserModified = false)", "updateEntry(restored)")

# [fix/dv20-a2 A-08] Compliant hit branch, used as the arm-8 fixture's carriage.
FIX_MERGE_FIELDS_OK = FIX_MERGE_OK


# [fix/dv16-a A-08] Arm 6 teeth: the merge path stops applying the shared
# instance-field write face (the exact regression the arm exists for).
FIX_MERGE_NOAPPLY_BAD = """fun mergeImportInstanceJSON(
    jsonStr: String,
    srcEntryIds: List<String>,
): Pair<String, Map<String, String>>? {
    val entries = parseImportedModelEntries(dict, existing.id)
    val entryMap = HashMap<String, String>()
    for (i in entries.indices) {
        val srcId = srcEntryIds.getOrNull(i) ?: ""
        val entry = entries[i]
        val hit = _config.value.modelEntries.firstOrNull {
            it.providerInstanceId == existing.id && it.baseModel.id == entry.baseModel.id
        }
        val resolvedEntryId = if (hit != null) {
            val restored = mergeImportedEntryOntoLocal(hit, entry)
            if (restored == null) hit.id else (updateEntry(restored, stampUserModified = false) ?: hit.id)
        } else {
            addEntry(entry)
            _config.value.modelEntries.firstOrNull {
                it.providerInstanceId == existing.id && it.baseModel.id == entry.baseModel.id
            }?.id ?: compositeEntryKey(existing.id, entry.baseModel.id)
        }
        if (srcId.isNotEmpty()) entryMap[srcId] = resolvedEntryId
    }
    importInstanceCredentials(dict, existing)
    return existing.id to entryMap
}
"""

FIX_CONVERT_OK = """private fun convertThinkingRules(
    records: List<JSONObject>,
    typeToLabel: Map<String, Pair<String, String>>,
): JSONArray {
    for (rec in records) {
        if (rec.optString("t", "") != "ThinkingRuleV1") continue
        val d = rec.optJSONObject("d") ?: continue
        val r = JSONObject()
        r.put("id", d.optString("id", ""))
        r.put("label", d.optString("label", ""))
        r.put("scopeKind", d.optString("scopeKind", "allModels"))
        d.optString("scopePattern", "").takeIf { it.isNotEmpty() }
            ?.let { r.put("scopePattern", it) }
        r.put("wireFormatJson", d.optString("wireFormatJson", "{}"))
        d.optString("reasoningEchoJson", "").takeIf { it.isNotEmpty() }
            ?.let { r.put("reasoningEchoJson", it) }
        r.put("sortOrder", d.optInt("sortOrder", 0))
        arr.put(r)
    }
    return out
}
"""

FIX_CONVERT_BAD = """private fun convertThinkingRules(
    records: List<JSONObject>,
    typeToLabel: Map<String, Pair<String, String>>,
): JSONArray {
    for (rec in records) {
        if (rec.optString("t", "") != "ThinkingRuleV1") continue
        val d = rec.optJSONObject("d") ?: continue
        val r = JSONObject()
        r.put("id", d.optString("id", ""))
        r.put("label", d.optString("label", ""))
        r.put("scopeKind", d.optString("scopeKind", "allModels"))
        d.optString("scopePattern", "").takeIf { it.isNotEmpty() }
            ?.let { r.put("scopePattern", it) }
        r.put("wireFormatJson", d.optString("wireFormatJson", "{}"))
        r.put("sortOrder", d.optInt("sortOrder", 0))
        arr.put(r)
    }
    return out
}
"""


FIX_THINKING_OK = '\n# [fix/dv23-a1 A-01] Arm 12 compliant fixture: publish-after-persist.\nprivate fun republishThinkingCache(instanceId: String, rules: List<ThinkingRule>?) {\n    if (rules != null) {\n        ThinkingRuleResolver.setCustomRules(instanceId, rules)\n        return\n    }\n    republishThinkingCache(instanceId)\n}\n\nfun saveThinkingRule(instanceId: String, rule: ThinkingRule, id: String? = null): String = runBlocking {\n    val ruleId = id ?: "minted"\n    var persistedRules: List<ThinkingRule>? = null\n    runCatching {\n        val existing = providerDao.loadThinkingRules(instanceId).toMutableList()\n        val renumbered = existing.mapIndexed { i, e -> e.copy(sortOrder = i) }\n        providerDao.replaceThinkingRules(instanceId, renumbered)\n        persistedRules = renumbered.map { ThinkingRuleCoding.toRule(it) }\n    }.onFailure { e ->\n        android.util.Log.e("ProviderRepo", "saveThinkingRule failed", e)\n    }\n    republishThinkingCache(instanceId, persistedRules)\n    ruleId\n}\n\nfun deleteThinkingRule(instanceId: String, id: String) = runBlocking {\n    var persistedRules: List<ThinkingRule>? = null\n    runCatching {\n        providerDao.deleteThinkingRule(id)\n        val survivors = providerDao.loadThinkingRules(instanceId)\n            .sortedBy { it.sortOrder }\n            .mapIndexed { i, e -> e.copy(sortOrder = i) }\n        providerDao.replaceThinkingRules(instanceId, survivors)\n        persistedRules = survivors.map { ThinkingRuleCoding.toRule(it) }\n    }.onFailure { e ->\n        android.util.Log.e("ProviderRepo", "deleteThinkingRule failed", e)\n    }\n    republishThinkingCache(instanceId, persistedRules)\n}\n\nfun reorderThinkingRules(instanceId: String, orderedIds: List<String>) = runBlocking {\n    var persistedRules: List<ThinkingRule>? = null\n    runCatching {\n        val byId = providerDao.loadThinkingRules(instanceId).associateBy { it.id }\n        val reordered = orderedIds.mapNotNull { byId[it] }\n            .mapIndexed { i, e -> e.copy(sortOrder = i) }\n        val omitted = byId.values.filter { it.id !in orderedIds }.map { it }\n        providerDao.replaceThinkingRules(instanceId, reordered + omitted)\n        persistedRules = (reordered + omitted).map { ThinkingRuleCoding.toRule(it) }\n    }.onFailure { e ->\n        android.util.Log.e("ProviderRepo", "reorderThinkingRules failed", e)\n    }\n    republishThinkingCache(instanceId, persistedRules)\n}\n'

FIX_THINKING_ASSIGN_BEFORE_BAD = '\nprivate fun republishThinkingCache(instanceId: String, rules: List<ThinkingRule>?) {\n    if (rules != null) {\n        ThinkingRuleResolver.setCustomRules(instanceId, rules)\n        return\n    }\n    republishThinkingCache(instanceId)\n}\n\nfun saveThinkingRule(instanceId: String, rule: ThinkingRule, id: String? = null): String = runBlocking {\n    val ruleId = id ?: "minted"\n    var persistedRules: List<ThinkingRule>? = null\n    runCatching {\n        val existing = providerDao.loadThinkingRules(instanceId).toMutableList()\n        val renumbered = existing.mapIndexed { i, e -> e.copy(sortOrder = i) }\n        persistedRules = renumbered.map { ThinkingRuleCoding.toRule(it) }\n        providerDao.replaceThinkingRules(instanceId, renumbered)\n    }.onFailure { e ->\n        android.util.Log.e("ProviderRepo", "saveThinkingRule failed", e)\n    }\n    republishThinkingCache(instanceId, persistedRules)\n    ruleId\n}\n'

FIX_THINKING_NO_DAO_BAD = '\nprivate fun republishThinkingCache(instanceId: String, rules: List<ThinkingRule>?) {\n    if (rules != null) {\n        ThinkingRuleResolver.setCustomRules(instanceId, rules)\n        return\n    }\n    republishThinkingCache(instanceId)\n}\n\nfun saveThinkingRule(instanceId: String, rule: ThinkingRule, id: String? = null): String = runBlocking {\n    val ruleId = id ?: "minted"\n    var persistedRules: List<ThinkingRule>? = null\n    runCatching {\n        persistedRules = listOf(ThinkingRuleCoding.toRule(rule, "minted", instanceId))\n    }.onFailure { e ->\n        android.util.Log.e("ProviderRepo", "saveThinkingRule failed", e)\n    }\n    republishThinkingCache(instanceId, persistedRules)\n    ruleId\n}\n'

FIX_AGENT_LOOP_OK = '\n# [fix/dv23-a1 A-02] Arm 13 compliant fixture: load guard + lock.\nfun addAgentLoopEntry(entryId: String) = synchronized(configLock) {\n    ensureConfigLoaded()\n    val cur = _config.value.agentLoopModelEntryIds.toList()\n    if (entryId in cur) return@synchronized\n    setAgentLoopEntryIds(cur + entryId)\n}\n\nfun removeAgentLoopEntry(entryId: String) = synchronized(configLock) {\n    ensureConfigLoaded()\n    val cur = _config.value.agentLoopModelEntryIds.toList()\n    if (entryId !in cur) return@synchronized\n    setAgentLoopEntryIds(cur.filterNot { it == entryId })\n}\n\nfun reorderAgentLoopEntries(newOrder: List<String>) = synchronized(configLock) {\n    ensureConfigLoaded()\n    val cur = _config.value.agentLoopModelEntryIds.toList()\n    val reordered = permuteById(cur, newOrder) { it } ?: return@synchronized\n    setAgentLoopEntryIds(reordered)\n}\n'

FIX_AGENT_LOOP_NO_GUARD_BAD = '\nfun addAgentLoopEntry(entryId: String) = synchronized(configLock) {\n    val cur = _config.value.agentLoopModelEntryIds.toList()\n    if (entryId in cur) return@synchronized\n    setAgentLoopEntryIds(cur + entryId)\n}\n\nfun reorderAgentLoopEntries(newOrder: List<String>) = synchronized(configLock) {\n    ensureConfigLoaded()\n    val cur = _config.value.agentLoopModelEntryIds.toList()\n    val reordered = permuteById(cur, newOrder) { it } ?: return@synchronized\n    setAgentLoopEntryIds(reordered)\n}\n'

FIX_AGENT_LOOP_GUARD_AFTER_BAD = '\nfun addAgentLoopEntry(entryId: String) = synchronized(configLock) {\n    val cur = _config.value.agentLoopModelEntryIds.toList()\n    ensureConfigLoaded()\n    if (entryId in cur) return@synchronized\n    setAgentLoopEntryIds(cur + entryId)\n}\n\nfun reorderAgentLoopEntries(newOrder: List<String>) = synchronized(configLock) {\n    ensureConfigLoaded()\n    val cur = _config.value.agentLoopModelEntryIds.toList()\n    val reordered = permuteById(cur, newOrder) { it } ?: return@synchronized\n    setAgentLoopEntryIds(reordered)\n}\n'

FIX_AGENT_LOOP_NO_LOCK_BAD = '\nfun addAgentLoopEntry(entryId: String) = synchronized(configLock) {\n    ensureConfigLoaded()\n    val cur = _config.value.agentLoopModelEntryIds.toList()\n    if (entryId in cur) return@synchronized\n    setAgentLoopEntryIds(cur + entryId)\n}\n\nfun reorderAgentLoopEntries(newOrder: List<String>) {\n    ensureConfigLoaded()\n    val cur = _config.value.agentLoopModelEntryIds.toList()\n    val reordered = permuteById(cur, newOrder) { it } ?: return\n    setAgentLoopEntryIds(reordered)\n}\n'

FIX_AGENT_LOOP_READ_BEFORE_LOCK_BAD = '\nfun addAgentLoopEntry(entryId: String) {\n    val cur = _config.value.agentLoopModelEntryIds.toList()\n    synchronized(configLock) {\n        ensureConfigLoaded()\n        if (entryId in cur) return@synchronized\n        setAgentLoopEntryIds(cur + entryId)\n    }\n}\n\nfun reorderAgentLoopEntries(newOrder: List<String>) = synchronized(configLock) {\n    ensureConfigLoaded()\n    val cur = _config.value.agentLoopModelEntryIds.toList()\n    val reordered = permuteById(cur, newOrder) { it } ?: return@synchronized\n    setAgentLoopEntryIds(reordered)\n}\n'

# --- [wide-1024] the sweep's witnesses, frozen as arms ---------------------
# Arm 1: the re-query still runs and the elvis fallback is intact, but the id
# that comes back is read off the imported entry — `ModelEntry.id` IS `uuid`,
# so the temp uuid returns through a different spelling.
FIX_MERGE_ID_OFF_IMPORTED_BAD = FIX_MERGE_OK.replace(
    "            }?.id ?: compositeEntryKey(existing.id, entry.baseModel.id)",
    "            }?.let { entry.uuid } ?: compositeEntryKey(existing.id, entry.baseModel.id)")

# Arm 6: the fields are decoded AND applied — then the UNMERGED instance is
# written through updateInstance, so every decoded field is dropped.
FIX_MERGE_WRITES_UNMERGED_BAD = FIX_MERGE_OK.replace(
    "    if (updated != existing) updateInstance(updated)",
    "    if (updated != existing) updateInstance(existing)")

# Arm 8: the canonicalization still runs and `published` is still the single
# object on both faces — but it is re-derived from the RAW config argument, so
# the canonical value is computed and discarded.
FIX_SAVE_PUBLISHED_FROM_RAW_BAD = FIX_SAVE_CANONICAL.replace(
    "        val published = canonical.copy(",
    "        val published = config.copy(")

# Arm 12: the assignment is still after the DAO write, and the failure handler
# now makes the published value non-null — a failed write publishes rules that
# never reached provider.db.
FIX_THINKING_ASSIGN_IN_FAILURE_BAD = FIX_THINKING_OK.replace(
    """    }.onFailure { e ->
        android.util.Log.e("ProviderRepo", "saveThinkingRule failed", e)""",
    """    }.onFailure { e ->
        persistedRules = listOf(rule)
        android.util.Log.e("ProviderRepo", "saveThinkingRule failed", e)""")


# --- [batch5 t12] the 6 witnesses of G06, frozen as arms -------------------
# G06-A1 (Arm 1): the re-query, its `?.id` projection and the
# `?: compositeEntryKey(…)` fallback all stay — the predicate is made
# constant-false and the fallback is typed as two empty literals, so every
# imported entry gets the same non-existent key.
FIX_MERGE_DEAD_REQUERY_BAD = FIX_MERGE_OK.replace(
    """            _config.value.modelEntries.firstOrNull {
                it.providerInstanceId == existing.id && it.baseModel.id == entry.baseModel.id
            }?.id ?: compositeEntryKey(existing.id, entry.baseModel.id)""",
    """            _config.value.modelEntries.firstOrNull {
                false
            }?.id ?: compositeEntryKey("", "")""")

# G06-A2 (Arm 4): the read anchor stays, the write is gone.
FIX_CONVERT_ECHO_DROPPED_BAD = FIX_CONVERT_OK.replace(
    """        d.optString("reasoningEchoJson", "").takeIf { it.isNotEmpty() }
            ?.let { r.put("reasoningEchoJson", it) }
""",
    """        d.optString("reasoningEchoJson", "").takeIf { false }
""")

# G06-A3 (Arm 5): the reads stay, the constructor receives `null`.
FIX_PARSE_CTOR_NULL_BAD = (
    FIX_PARSE_OK
    .replace("            reasoningEffortValues = reasoningEffortValues,",
             "            reasoningEffortValues = null,")
    .replace("            declaresNoEffortTiers = declaresNoEffortTiers,",
             "            declaresNoEffortTiers = null,"))

# G06-A4 (Arm 7): the merge result is computed and the PRE-merge entry is
# written back (the restore flag is still passed, so arm 9 stays quiet).
FIX_MERGE_WRITES_PREMERGE_BAD = FIX_MERGE_OK.replace(
    "updateEntry(restored, stampUserModified = false)",
    "updateEntry(hit, stampUserModified = false)")

# G06-A5 (Arm 8): `published` is still the canonical object, one named
# argument is taken from the PRE-canonical argument instead.
FIX_SAVE_COPY_RAW_LIST_BAD = FIX_SAVE_CANONICAL.replace(
    "        val published = canonical.copy(revision = config.revision + 1)",
    "        val published = canonical.copy(\n"
    "            revision = config.revision + 1,\n"
    "            modelEntries = config.modelEntries.toMutableList(),\n"
    "        )")

# G06-A6 (Arm 10): envelope + handler + mirror write all in place, and the
# failure is re-raised between the handler and the mirror — the change is
# destroyed exactly as in the bare-DAO baseline.
FIX_PERSIST_RETHROW_BAD = FIX_PERSIST_OK.replace(
    "    val mirrorWritten = prefs.edit()",
    "    dbWrite.getOrThrow()\n    val mirrorWritten = prefs.edit()")


def build_fixture_tree(tmp, merge_src, export_src, parse_src, convert_src,
                       update_entry_src=None, persist_src=None, restore_src=None,
                       thinking_src=None, agentloop_src=None,
                       save_src=None):
    os.makedirs(os.path.join(tmp, APP + "/data/repository"), exist_ok=True)
    os.makedirs(os.path.join(tmp, APP + "/backup"), exist_ok=True)
    os.makedirs(os.path.join(tmp, APP + "/data/model"), exist_ok=True)
    with open(os.path.join(tmp, PR_REL), "w") as f:
        f.write(merge_src + "\n" + export_src + "\n" + parse_src + "\n" + FIX_HELPERS
                + "\n" + FIX_IMPORT_OK + "\n" + FIX_MERGE_FIELDS_HELPER + "\n"
                + (FIX_UPDATE_ENTRY_OK if update_entry_src is None else update_entry_src)
                + "\n" + (FIX_SAVE_CANONICAL if save_src is None else save_src)
                + "\n" + (FIX_PERSIST_OK if persist_src is None else persist_src)
                + "\n" + (FIX_RESTORE_OK if restore_src is None else restore_src)
                + "\n" + (FIX_THINKING_OK if thinking_src is None else thinking_src)
                + "\n" + (FIX_AGENT_LOOP_OK if agentloop_src is None else agentloop_src))
    with open(os.path.join(tmp, BM_REL), "w") as f:
        f.write(convert_src)
    with open(os.path.join(tmp, LM_REL), "w") as f:
        f.write(FIX_LM)


def _arm_set(tmp):
    return sorted({x[2] for x in run_checks(tmp)})


def selftest():
    failures = []
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK)
        v = run_checks(tmp)
        if v:
            failures.append("compliant fixture flagged: %r" % (v,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_BAD, FIX_EXPORT_BAD, FIX_PARSE_BAD, FIX_CONVERT_BAD)
        v = run_checks(tmp)
        arms = sorted({x[2] for x in v})
        if arms != ["1", "2", "3", "4", "5"]:
            failures.append("violating fixture arms=%r (violations=%r)" % (arms, v))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_READDROPPED_BAD, FIX_CONVERT_OK)
        v = run_checks(tmp)
        arms = sorted({x[2] for x in v})
        if arms != ["3", "5"]:
            failures.append("read-dropped fixture arms=%r (violations=%r)" % (arms, v))
    # [fix/dv16-a A-08] Arm 6: the merge path stops applying the shared
    # instance-field write face → only arm 6 red.
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_NOAPPLY_BAD, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK)
        v = run_checks(tmp)
        arms = sorted({x[2] for x in v})
        if arms != ["6"]:
            failures.append("merge-noapply fixture arms=%r (violations=%r)" % (arms, v))
    # [fix/dv20-a2] Arms 7/8: the merge hit branch and the persist argument are
    # the two decision points this batch added; each fixture reds only its own.
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_FIELDS_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK)
        got = _arm_set(tmp)
        if got:
            failures.append("merge-fields compliant fixture red: %r" % (run_checks(tmp),))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_ID_ONLY_BAD, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK)
        got = _arm_set(tmp)
        if got != ["7"]:
            failures.append("merge-id-only fixture arms=%r (want ['7'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_WHITELIST_BAD, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK)
        got = _arm_set(tmp)
        if got != ["7"]:
            failures.append("merge-whitelist fixture arms=%r (want ['7'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        # Arm 8: same compliant merge, breaker lives in saveConfig instead.
        os.makedirs(os.path.join(tmp, APP + "/data/repository"), exist_ok=True)
        os.makedirs(os.path.join(tmp, APP + "/backup"), exist_ok=True)
        os.makedirs(os.path.join(tmp, APP + "/data/model"), exist_ok=True)
        with open(os.path.join(tmp, PR_REL), "w") as f:
            f.write(FIX_MERGE_FIELDS_OK + "\n" + FIX_EXPORT_OK + "\n" + FIX_PARSE_OK + "\n"
                    + FIX_HELPERS + "\n" + FIX_IMPORT_OK + "\n" + FIX_MERGE_FIELDS_HELPER
                    + "\n" + FIX_UPDATE_ENTRY_OK + "\n" + FIX_SAVE_RAW_BAD
                    # [fix/dv22-a1 §1042-A-01] this fixture hand-builds its tree
                    # instead of calling build_fixture_tree, so the compliant
                    # arm-10/11 bodies must be carried explicitly — otherwise
                    # arms 10/11 ROT and the fixture stops isolating arm 8.
                    + "\n" + FIX_PERSIST_OK + "\n" + FIX_RESTORE_OK
                    # [fix/dv23-a1 §1043-A01-A02] same reason for the new
                    # arm-12/13 bodies: this fixture hand-builds its tree, so
                    # the compliant arm-12/13 bodies must be carried
                    # explicitly — otherwise arms 12/13 ROT and the fixture
                    # stops isolating arm 8.
                    + "\n" + FIX_THINKING_OK + "\n" + FIX_AGENT_LOOP_OK)
        with open(os.path.join(tmp, BM_REL), "w") as f:
            f.write(FIX_CONVERT_OK)
        with open(os.path.join(tmp, LM_REL), "w") as f:
            f.write(FIX_LM)
        got = _arm_set(tmp)
        if got != ["8"]:
            failures.append("save-raw fixture arms=%r (want ['8'])" % (got,))
    # [fix/dv21-a3 A-03] Arm 9: a restore must not restamp userModifiedAt —
    # broken at the call site, inside the write face (decorative switch), by
    # dropping the switch entirely, and by removing the write face (ROT).
    # [wide-1024] The sweep's four witnesses, one arm each: the named anchor
    # stayed, the VALUE stopped flowing.
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_ID_OFF_IMPORTED_BAD, FIX_EXPORT_OK,
                           FIX_PARSE_OK, FIX_CONVERT_OK)
        got = _arm_set(tmp)
        if got != ["1"]:
            failures.append("merge-id-off-imported fixture arms=%r (want ['1'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_WRITES_UNMERGED_BAD, FIX_EXPORT_OK,
                           FIX_PARSE_OK, FIX_CONVERT_OK)
        got = _arm_set(tmp)
        if got != ["6"]:
            failures.append("merge-writes-unmerged fixture arms=%r (want ['6'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK,
                           save_src=FIX_SAVE_PUBLISHED_FROM_RAW_BAD)
        got = _arm_set(tmp)
        if got != ["8"]:
            failures.append("save-published-from-raw fixture arms=%r (want ['8'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK,
                           thinking_src=FIX_THINKING_ASSIGN_IN_FAILURE_BAD)
        got = _arm_set(tmp)
        if got != ["12"]:
            failures.append("thinking-assign-in-failure fixture arms=%r (want ['12'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_STAMP_BAD, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK)
        got = _arm_set(tmp)
        if got != ["9"]:
            failures.append("merge-restamp fixture arms=%r (want ['9'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK,
                           update_entry_src=FIX_UPDATE_ENTRY_STAMP_BAD)
        got = _arm_set(tmp)
        if got != ["9"]:
            failures.append("update-unconditional-stamp fixture arms=%r (want ['9'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK,
                           update_entry_src=FIX_UPDATE_ENTRY_ONEPARAM_BAD)
        got = _arm_set(tmp)
        if got != ["9"]:
            failures.append("update-no-switch fixture arms=%r (want ['9'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        # fail-closed: the write face is gone -> ROT message, not a silent pass.
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK,
                           update_entry_src="")
        v = run_checks(tmp)
        got = sorted({x[2] for x in v})
        if got != ["9"]:
            failures.append("update-missing fixture arms=%r (want ['9'])" % (got,))
        elif not any("gate ROT" in x[3] for x in v):
            failures.append("update-missing fixture did not report ROT: %r" % (v,))
    # [fix/dv22-a1 §1042-A-01] Arms 10/11: the DB write must not be able to
    # skip the mirror write (10), and the one function whose nine siblings all
    # wrap their DAO call must wrap it too — with a handler, not a silent
    # swallow (11). Each fixture reds only its own arm; both ROT cases are
    # fail-closed (a renamed/removed write face is a violation, not a pass).
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK,
                           persist_src=FIX_PERSIST_BARE_BAD)
        got = _arm_set(tmp)
        if got != ["10"]:
            failures.append("persist-bare-dao fixture arms=%r (want ['10'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK,
                           persist_src=FIX_PERSIST_MIRROR_INSIDE_BAD)
        got = _arm_set(tmp)
        if got != ["10"]:
            failures.append("persist-mirror-inside fixture arms=%r (want ['10'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK,
                           persist_src=FIX_PERSIST_NOMIRROR_BAD)
        v = run_checks(tmp)
        got = sorted({x[2] for x in v})
        if got != ["10"]:
            failures.append("persist-no-mirror fixture arms=%r (want ['10'])" % (got,))
        elif not any("gate ROT" in x[3] for x in v):
            failures.append("persist-no-mirror fixture did not report ROT: %r" % (v,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK,
                           restore_src=FIX_RESTORE_BARE_BAD)
        got = _arm_set(tmp)
        if got != ["11"]:
            failures.append("restore-bare-dao fixture arms=%r (want ['11'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK,
                           restore_src=FIX_RESTORE_SILENT_BAD)
        got = _arm_set(tmp)
        if got != ["11"]:
            failures.append("restore-silent-swallow fixture arms=%r (want ['11'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK,
                           restore_src=FIX_RESTORE_NOCATCH_BAD)
        got = _arm_set(tmp)
        if got != ["11"]:
            failures.append("restore-no-catch fixture arms=%r (want ['11'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK,
                           restore_src=FIX_RESTORE_MISSING_BAD)
        v = run_checks(tmp)
        got = sorted({x[2] for x in v})
        if got != ["11"]:
            failures.append("restore-missing fixture arms=%r (want ['11'])" % (got,))
        elif not any("gate ROT" in x[3] for x in v):
            failures.append("restore-missing fixture did not report ROT: %r" % (v,))
    # [fix/dv23-a1 §1043-A01-A02] Arms 12/13: the publish-after-persist
    # ordering (12) and the cold-start load guard + lock coverage (13). Each
    # fixture reds only its own arm; the ROT cases are fail-closed.
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK,
                           thinking_src=FIX_THINKING_ASSIGN_BEFORE_BAD)
        got = _arm_set(tmp)
        if got != ["12"]:
            failures.append("thinking-assign-before-dao fixture arms=%r (want ['12'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK,
                           thinking_src=FIX_THINKING_NO_DAO_BAD)
        v = run_checks(tmp)
        got = sorted({x[2] for x in v})
        if got != ["12"]:
            failures.append("thinking-no-dao fixture arms=%r (want ['12'])" % (got,))
        elif not any("gate ROT" in x[3] for x in v):
            failures.append("thinking-no-dao fixture did not report ROT: %r" % (v,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK,
                           agentloop_src=FIX_AGENT_LOOP_NO_GUARD_BAD)
        got = _arm_set(tmp)
        if got != ["13"]:
            failures.append("agentloop-no-guard fixture arms=%r (want ['13'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK,
                           agentloop_src=FIX_AGENT_LOOP_GUARD_AFTER_BAD)
        got = _arm_set(tmp)
        if got != ["13"]:
            failures.append("agentloop-guard-after fixture arms=%r (want ['13'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK,
                           agentloop_src=FIX_AGENT_LOOP_NO_LOCK_BAD)
        got = _arm_set(tmp)
        if got != ["13"]:
            failures.append("agentloop-no-lock fixture arms=%r (want ['13'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK,
                           agentloop_src=FIX_AGENT_LOOP_READ_BEFORE_LOCK_BAD)
        got = _arm_set(tmp)
        if got != ["13"]:
            failures.append("agentloop-read-before-lock fixture arms=%r (want ['13'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK,
                           agentloop_src="")
        v = run_checks(tmp)
        got = sorted({x[2] for x in v})
        if got != ["13"]:
            failures.append("agentloop-missing fixture arms=%r (want ['13'])" % (got,))
        elif not any("gate ROT" in x[3] for x in v):
            failures.append("agentloop-missing fixture did not report ROT: %r" % (v,))
    # [batch5 t12] The six G06 witnesses, one arm each. Every one of them kept
    # the anchor the old criterion looked for, so the assertion is on the ARM
    # that fires — not merely "something is red".
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_DEAD_REQUERY_BAD, FIX_EXPORT_OK, FIX_PARSE_OK,
                           FIX_CONVERT_OK)
        got = _arm_set(tmp)
        if got != ["1"]:
            failures.append("g06-a1 dead-requery fixture arms=%r (want ['1'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK,
                           FIX_CONVERT_ECHO_DROPPED_BAD)
        got = _arm_set(tmp)
        if got != ["4"]:
            failures.append("g06-a2 echo-write-dropped fixture arms=%r (want ['4'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_CTOR_NULL_BAD,
                           FIX_CONVERT_OK)
        got = _arm_set(tmp)
        if got != ["5"]:
            failures.append("g06-a3 ctor-null fixture arms=%r (want ['5'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_WRITES_PREMERGE_BAD, FIX_EXPORT_OK, FIX_PARSE_OK,
                           FIX_CONVERT_OK)
        got = _arm_set(tmp)
        if got != ["7"]:
            failures.append("g06-a4 update-premerge fixture arms=%r (want ['7'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK,
                           save_src=FIX_SAVE_COPY_RAW_LIST_BAD)
        got = _arm_set(tmp)
        if got != ["8"]:
            failures.append("g06-a5 published-raw-list fixture arms=%r (want ['8'])" % (got,))
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_MERGE_OK, FIX_EXPORT_OK, FIX_PARSE_OK, FIX_CONVERT_OK,
                           persist_src=FIX_PERSIST_RETHROW_BAD)
        got = _arm_set(tmp)
        if got != ["10"]:
            failures.append("g06-a6 persist-rethrow fixture arms=%r (want ['10'])" % (got,))
    if failures:
        for f in failures:
            print("SELFTEST-FAIL: " + f)
        print("PROVIDER_IMPORT_ID_ROUNDTRIP_SELFTEST=FAIL")
        return 1
    print("selftest: compliant green / violating red arms 1-6 / read-dropped red arms 3+5"
          " / merge-noapply red arm 6 / merge-id-only+whitelist red arm 7 / save-raw red arm 8"
          " / merge-restamp+update-restamp+update-no-switch+update-missing red arm 9"
          " / persist-bare+persist-mirror-inside+persist-no-mirror red arm 10"
          " / restore-bare+restore-silent+restore-no-catch+restore-missing red arm 11"
          " / thinking-assign-before+thinking-no-dao red arm 12"
          " / agentloop-no-guard+guard-after+no-lock+read-before-lock+missing red arm 13")
    print("PROVIDER_IMPORT_ID_ROUNDTRIP_SELFTEST=PASS")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root", nargs="?", default=".")
    ap.add_argument("--root", dest="rootkw", default=None)
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        sys.exit(selftest())
    root = args.rootkw or args.root
    violations = run_checks(root)
    if violations:
        print("PROVIDER_IMPORT_ID_ROUNDTRIP: %d violation(s)" % len(violations))
        for rel, line, arm, msg in violations:
            print("  [arm %s] %s:%s — %s" % (arm, rel, line, msg))
        sys.exit(1)
    print("PROVIDER_IMPORT_ID_ROUNDTRIP: clean (13 arms)")
    sys.exit(0)


if __name__ == "__main__":
    main()
