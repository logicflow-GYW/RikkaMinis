#!/usr/bin/env python3
"""
Gate: model entry override parity (deep-audit-1023 ISSUE-02,
branch fix/da1023-model-override-parity).

ModelEntryDetailScreen's Save used to rebuild ModelOverrides inline and got
four things wrong (all verified in pre-fix):

  1. the modality lists were rebuilt from the toggle switches ALONE, so
     "text" (both sides) and output "video" — dimensions the screen has no
     switch for — were dropped. A written override is non-null, and
     ModelEntry.model replaces the base list wholesale, so a text-out model
     lost "text" from outputModalities: isTextOutput flipped to false (model
     disappears from the new-chat default candidates) and
     isPureImageGenerator flipped to true (pinned to the Images endpoint).
  2. the "did the user change anything?" comparison ran against the RAW base
     list while the switch state is derived through normalizeModalityName() —
     suffixed bases ("image_input" from OpenRouter) "always differed" and
     forced a write on a no-op save, freezing the entry to today's metadata.
  3. `ModelOverrides(...)` was constructed positionally with the six
     screen-bound fields, so maxThinkingLevel (7th field, written by backup
     import / config restore / config RPC) defaulted to null — one Save
     wiped the user's thinking-ceiling override.

The fix moved the derivation into the pure buildModelEntryOverrides()
(ModelEntryOverrideBuilder.kt) built on previous.copy(). This gate pins the
wiring in BOTH files:

  Arm 1 — the screen must call buildModelEntryOverrides( and must NOT
          construct ModelOverrides( itself (the Reset button's
          `overrides = ModelOverrides()` is the one sanctioned blank-slate).
  Arm 2 — the builder must derive from `previous.copy(`, must normalize the
          base lists (normalizeModalityList calls), and must pass every
          previous-owning field through the copy, never a positional
          constructor.
  Arm 3 — non-toggle dimensions must be carried over from base, not rebuilt
          from the switches alone: deriveModalityList( must be fed the base
          list, and the screen's toggle init must keep deriving from the
          normalized override-or-base lists.

  Arm 4 — [deep-audit-1044 A-01] ProviderRepository: every capability /
          modality decision must be taken on the EFFECTIVE model
          (ModelsDevApi.effectiveModel = enrich the base first, then apply the
          user's overrides), never on `ModelEntry.model` (base + overrides,
          no registry). All 39 built-in models declare null modality lists by
          construction, so the raw read says "no audio / yes text" for
          everything: a multimodal model the registry declares audio-capable
          was dropped from the Voice Input candidates (recording then failed
          with "No provider voice-input model configured"), the Voice Services
          shadow list reported "0 models", and pure image generators were
          offered as the new-chat default. The arm also pins the funnel's
          definition (it must route through ModelsDevApi.effectiveModel, not a
          re-derivation) and a ledger of the six decision functions, so
          deleting a capability gate trips instead of passing silently.

  Arm 5 — ProviderSpeechRecognitionEngine: the `resolvedModel` handed to
          VoiceProvider.transcribe must be the effective model too (the
          adapters read modalities / capability off it).

  Arm 6 — the ONE sanctioned raw read: voice-TEMPLATE seeds
          (`voiceTemplate?.mockModels`) are plain LLMModel values with no
          ModelEntry behind them, so they cannot be enriched. Exactly one
          function may carry the seed anchor, it must keep its modality read,
          and it must NOT be routed through the funnel.

  Arm 7 — drift: every `val LLMModel.<name>: Boolean` in VoiceModality.kt
          whose body is about audio must be in MODALITY_PREDICATES (or in the
          declared non-selection list), so a new audio predicate cannot escape
          the funnel by not being listed here.

[batch5-t07] Five external witnesses walked through this gate (report-A §08).
Every arm now judges the EFFECT on the shared lexer/ast/valueflow primitives,
and the gate's own lexers (`strip_kotlin_comments`, `code_only`, `_balanced`,
`_close_of`, `function_text`) are gone — structure runs on `mask_code` only:

  * arm 1b — the persist call must be REACHABLE: `path_feasible` on the
    `updateEntry(...)` site (folds `if (false) …` / `if (!true) …` /
    `if (1 == 2) …` alike) plus "no always-taken exit before it".
  * arm 2b — the builder must RETURN the derivation on every path: the first
    guaranteed exit must be the `previous.copy(...)` return (`if (!false)
    return previous` no longer hides behind a literal-only whitelist).
  * arm 3 — the `out.add(name)` merge guard must be FEASIBLE
    (`false && name.isNotEmpty() && …` folds to false) and still carry the
    `toggleNames` passthrough.
  * arm 4b/5 — the value that leaves the funnel / goes into the transcription
    request is resolved with `value_sources` + constant-branch folding, so a
    chain tail (`.let { entry.model }`) or a dead branch cannot pass on a
    substring.

Usage: python3 scripts/scan/model_entry_override_parity_check.py [root]
       python3 scripts/scan/model_entry_override_parity_check.py --root <root>
       python3 scripts/scan/model_entry_override_parity_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage/ROT
"""
import argparse
import os
import re
import sys
import tempfile

from _kotlin_lexer import KotlinLexError, mask_code
from _kotlin_ast import (const_bool, enclosing_conditions, function_body,
                         if_blocks, match_bracket, strip_parens)
from _kotlin_valueflow import expr_end, path_feasible, value_sources

APP = "src/android/app/src/main/java/com/rikkaminis/app"
ME_REL = APP + "/ui/settings/ModelEntryDetailScreen.kt"
OB_REL = APP + "/ui/settings/ModelEntryOverrideBuilder.kt"

# Arm 1 — screen wiring. Code anchors only (comments are stripped first).
ARM1_ANCHORS = (
    "buildModelEntryOverrides(",
    "previous = overrides",
)

# Arm 2 — builder derivation shape.
ARM2_ANCHORS = (
    "previous.copy(",
    "normalizeModalityList(baseModel.inputModalities)",
    "normalizeModalityList(baseModel.outputModalities)",
    "inputModalities = if (newInputs.toSet() != baseInputs.toSet()) newInputs else null",
    "outputModalities = if (newOutputs.toSet() != baseOutputs.toSet()) newOutputs else null",
)

# Forbidden inside buildModelEntryOverrides: the positional constructor that
# wipes every field this screen does not name (maxThinkingLevel & any future
# 8th field). previous.copy(...) is the only sanctioned construction.
ARM2_FORBIDDEN = ("ModelOverrides(",)

# Arm 3 — base passthrough for non-toggle dimensions.
ARM3_ANCHORS = (
    "deriveModalityList(",
    "name !in toggleNames",
    "for ((name, on) in toggles)",
)

# Forbidden: the OLD inline construction inside the save onClick. Located
# strictly between the Save MinisButton onClick start and the entry.copy(
# that follows, so the Reset button's sanctioned `overrides = ModelOverrides()`
# (outside the save block, further down the file) is not flagged.
# ── Arms 4-7: [deep-audit-1044 A-01] the effective-model funnel ─────────────
PR_REL = APP + "/data/repository/ProviderRepository.kt"
ASR_REL = APP + "/speech/ProviderSpeechRecognitionEngine.kt"
VM_REL = APP + "/data/model/VoiceModality.kt"

# Capability predicates a SELECTION decision may read. Off an un-enriched
# `ModelEntry.model` every one of them answers "no" for a built-in model (all
# 39 declare null modality lists by construction). Arm 7 derives coverage from
# VoiceModality.kt, so a NEW audio predicate cannot escape by omission.
MODALITY_PREDICATES = (
    "hasAudioInput",
    "hasAudioOutput",
    "hasVoiceModality",
    "isTextOutput",
)

# Not a selection decision: the single-flag voice-TEMPLATE-SEED shape
# discriminator (launch cleanup over raw LLMModel seeds).
NON_SELECTION_PREDICATES = ("isVoiceTemplateSeedShape",)

# The one sanctioned raw read. Voice-template seeds are LLMModel values with no
# ModelEntry behind them — they cannot be enriched, so their filter must stay
# raw. Exactly one function may own this anchor (arm 6).
SEED_ANCHOR = "?.mockModels"

# Arm 4 ledger: function -> the capability predicate(s) it must read. Naming a
# function here means "if this disappears or loses its gate, say so" — a rename
# trips loudly instead of silently shrinking the gate's reach.
PR_DECISION_LEDGER = {
    "newestProviderNewestTextEntry": ("isTextOutput",),
    "resolveVoiceInputChoice": ("hasAudioInput",),
    "resolveVoiceInputCandidates": ("hasAudioInput",),
    "resolveVoiceOutputChoice": ("hasAudioOutput",),
    "hasVoiceModels": ("hasVoiceModality",),
    "shadowVoiceProviders": ("hasAudioInput", "hasAudioOutput"),
}

FUN_RE = re.compile(
    r"(?m)^[ \t]*(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:(?:private|internal|public|protected|override|suspend|inline|operator|open|abstract|tailrec)\s+)*"
    r"fun\s+([A-Za-z_]\w*)\s*\(",
)

# The funnel is named by convention (`effective*`) and its DEFINITION is pinned
# separately (arm 4b) — so the judgement anchors the decision (read the
# effective model), not one particular spelling of the call site.
SANCTIONED_RE = re.compile(r"^(?:[A-Za-z_][\w.]*\.)?(effective\w*)$")

SAVE_BLOCK_START = "onClick = {"
SAVE_BLOCK_END = "val updated = if (entry.isCustom) {"
FORBIDDEN_SAVE_SNIPPETS = (
    "val newInputs = buildList",
    "val newOutputs = buildList",
    "ModelOverrides(",
)


def _view(src):
    """The ONE structure view: comments AND string/char interiors blanked.

    Every structural judgement (anchor presence, brackets, conditions, value
    flow) runs on this view, so neither prose nor a string literal can satisfy
    a criterion, and a `}` inside a literal cannot close a body early. Positions
    are preserved byte for byte, so verdicts report real line numbers.
    """
    return mask_code(src)


def _fatal(rel, exc):
    """Fail-closed: an unparsable file is a violation, never a silent pass."""
    return [(rel, 0, "?", "unparsable Kotlin (%s) — fail-closed, no raw-text fallback"
             % exc)]


def line_of(src, idx):
    return src.count("\n", 0, idx) + 1


def read(rel, root):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return None
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


COPY_CALL_RE = re.compile(r"\bentry\.copy\s*\(")
LITERAL_GUARD_RE = re.compile(r"\b(?:if|while)\s*\(\s*(?:true|false)(?:[\s)]|$)")
BOOL_OP_LITERAL = re.compile(
    r"(?:&&|\|\|)\s*(?:true|false)(?![\w])"
    r"|(?<![\w.\"'])(?:true|false)\s*(?:&&|\|\|)")


EXIT_RE = re.compile(r"\breturn\b(\s*@\s*[A-Za-z_]\w*)?|\bthrow\b")


def guaranteed_exits(code, start=0, end=None):
    """Positions of statements that leave the enclosing function on EVERY path.

    A bare `return` / `throw` with no enclosing condition is unconditional; one
    inside `if (…)` is unconditional when every enclosing condition folds to
    `true` (`if (true)`, `if (!false)`, `if (1 == 1)`). `return@label` leaves a
    lambda only and is never an exit here.
    """
    stop = len(code) if end is None else end
    out = []
    for m in EXIT_RE.finditer(code, start, stop):
        if m.group(1):
            continue
        pos = m.start()
        conds = [c for _kind, c in enclosing_conditions(code, pos)]
        if all(const_bool(strip_parens(c)) is True for c in conds):
            out.append(pos)
    return out


def check_save_persists_derived(rel, block, base_line=1):
    """Arm 1b — the value written to the repository must CARRY the derivation,
    and the persist call must actually RUN.

    Anchors only prove `buildModelEntryOverrides(` is called somewhere in the
    block. Two defect shapes keep every anchor and still lose every edit:
    the persisted copy takes `ModelOverrides()` instead of `newOverrides`, or
    the `updateEntry(...)` call sits behind a condition that folds to false
    (`if (false) …` / `if (!true) …` / `if (1 == 2) …`) so it never executes.
    """
    out = []
    code = block

    def at(pos):
        return base_line + line_of(code, pos) - 1

    copies = list(COPY_CALL_RE.finditer(code))
    if not copies:
        out.append((rel, 0, "1",
                    "the Save block builds no `entry.copy(` value — the computed overrides "
                    "have nowhere to go (gate ROT)"))
        return out
    for m in copies:
        open_paren = code.find("(", m.end() - 1)
        close = match_bracket(code, open_paren) if open_paren >= 0 else -1
        args = code[open_paren + 1:close if close > 0 else len(code)]
        if not re.search(r"\boverrides\s*=\s*newOverrides\b", args):
            given = re.search(r"\boverrides\s*=\s*([^,)]+)", args)
            out.append((rel, at(m.start()), "1",
                        "`entry.copy(...)` persists `overrides = %s` instead of the computed "
                        "`newOverrides` — `newOverrides` is computed and dropped, so every "
                        "override the user edited (display name, token caps, thinking level, "
                        "modalities) is erased on Save"
                        % (given.group(1).strip() if given else "<default>")))

    # The persist call must consume the value that was built (not the raw entry).
    binder = re.search(r"\bval\s+(\w+)\s*=\s*if\s*\(entry\.isCustom\)", code)
    persist = re.search(r"updateEntry\s*\(\s*([A-Za-z_]\w*)\s*\)", code)
    if persist and binder and persist.group(1) != binder.group(1):
        out.append((rel, at(persist.start()), "1",
                    "`updateEntry(%s)` persists a different value than the one built from "
                    "`entry.copy(...)` (`%s`) — the derivation is computed and thrown away"
                    % (persist.group(1), binder.group(1))))

    # …and the call must be REACHABLE.
    for m in re.finditer(r"\bupdateEntry\s*\(", code):
        pos = m.start()
        if path_feasible(code, pos):
            continue
        conds = " && ".join(" ".join(c.split())
                            for _kind, c in enclosing_conditions(code, pos))
        out.append((rel, at(pos), "1",
                    "`updateEntry(...)` is guarded by a condition that folds to false "
                    "(`%s`) — the persist is dead code, so every override the user saved "
                    "vanishes on the next launch while the sheet reports success" % conds))
        break
    first_persist = min((m.start() for m in re.finditer(r"\bupdateEntry\s*\(", code)),
                        default=None)
    if first_persist is not None:
        for pos in guaranteed_exits(code, 0, first_persist):
            out.append((rel, at(pos), "1",
                        "the Save handler exits here on every path (a constant-true guard) "
                        "before `updateEntry(...)` — the edited entry is never persisted"))
            break
    return out


def check_anchors(rel, body, arm, anchors, what):
    out = []
    for anchor in anchors:
        if anchor not in body:
            out.append((rel, 0, arm,
                        "%s is missing required code anchor `%s`" % (what, anchor)))
    return out


def save_block(src):
    """The Save button's onClick body — through the persist call.

    The slice stops AT `val updated = if (entry.isCustom) {` in the old version,
    which put the two `entry.copy(...)` expressions that actually become the
    persisted value OUTSIDE the audited region: the block could compute
    `newOverrides` and then persist `ModelOverrides()`. The block now runs to
    the end of that value expression (then/else arms) and through the following
    `updateEntry(...)`.
    """
    i = src.find(SAVE_BLOCK_START)
    if i < 0:
        return None
    marker = src.find(SAVE_BLOCK_END, i)
    if marker < 0:
        return None
    end = marker + len(SAVE_BLOCK_END)
    brace = src.find("{", end - 1)
    if brace >= 0:
        close = match_bracket(src, brace)
        if close > 0:
            end = close + 1
            k = end
            while k < len(src) and src[k] in " \t\r\n":
                k += 1
            if src.startswith("else", k):
                b2 = src.find("{", k)
                if b2 >= 0:
                    c2 = match_bracket(src, b2)
                    if c2 > 0:
                        end = c2 + 1
    m = re.search(r"updateEntry\s*\(", src[end:end + 400])
    if m:
        close = match_bracket(src, end + m.end() - 1)
        if close > 0:
            end = close + 1
    return src[i:end], i


DECL_RE = re.compile(
    r"\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*\(")


def fun_bodies(code):
    """[(name, body_start, body_end, kind)] for every `fun`, braced OR
    expression body. An expression body ends where its expression ends (bracket
    matching + continuation-aware line ends), so slicing it cannot swallow the
    following declaration — the shape that made a previous wave's装置 attribute
    a defect to the wrong function."""
    out = []
    for m in DECL_RE.finditer(code):
        close_paren = match_bracket(code, m.end() - 1)
        if close_paren < 0:
            continue
        i = close_paren + 1
        while i < len(code) and code[i] not in "={":
            if code[i] == "\n":
                break
            i += 1
        if i >= len(code) or code[i] not in "={":
            continue  # abstract / interface declaration — no body
        if code[i] == "{":
            body_end = match_bracket(code, i)
            if body_end < 0:
                continue
            out.append((m.group(1), i + 1, body_end, "block"))
        else:
            out.append((m.group(1), i + 1, expr_end(code, i + 1), "expr"))
    return out


def receiver_before(src, dot_index):
    """(chain-without-arguments, is_call) of the receiver of the `.` at
    [dot_index]. `entry.model` -> ("entry.model", False);
    `effectiveEntryModel(it)` -> ("effectiveEntryModel", True)."""
    j = dot_index - 1
    while j >= 0 and src[j] in " \t\n":
        j -= 1
    if j >= 0 and src[j] == ")":
        depth = 0
        while j >= 0:
            if src[j] == ")":
                depth += 1
            elif src[j] == "(":
                depth -= 1
                if depth == 0:
                    break
            j -= 1
        if j < 0:
            return (None, False)
        open_paren = j
        j -= 1
        while j >= 0 and (src[j].isalnum() or src[j] in "_."):
            j -= 1
        return (src[j + 1:open_paren].strip(), True)
    while j >= 0 and (src[j].isalnum() or src[j] in "_?."):
        j -= 1
    return (src[j + 1:dot_index].strip(), False)


def is_sanctioned(receiver):
    chain, is_call = receiver
    return bool(is_call and chain and SANCTIONED_RE.match(chain))


def modality_occurrences(code):
    """[(line, owner_function_or_None, predicate, receiver)] for every
    capability read in comment-stripped [code]. Import lines are skipped: the
    import of `hasAudioInput` is not a decision."""
    bodies = fun_bodies(code)
    lines = code.split("\n")
    out = []
    for pred in MODALITY_PREDICATES:
        for m in re.finditer(r"\.%s\b" % pred, code):
            line = code.count("\n", 0, m.start()) + 1
            if lines[line - 1].lstrip().startswith("import "):
                continue
            owner = None
            for name, start, end, _kind in bodies:
                if start <= m.start() <= end:
                    owner = name
                    break
            out.append((line, owner, pred, receiver_before(code, m.start())))
    return bodies, out


EFFECTIVE_CALL_RE = re.compile(r"^(?:[A-Za-z_][\w.]*\.)?effective\w*\s*\(")


def _branch_value(text):
    """The value a braceless / braced branch yields, as written."""
    t = strip_parens(text.strip())
    if not t:
        return ""
    if t.startswith("{"):
        close = match_bracket(t, 0)
        inner = t[1:close] if close > 0 else t[1:]
        from _kotlin_ast import tail_expr
        return tail_expr(inner).strip()
    return t


def is_effective_value(expr):
    """Does this expression's value come from the effective-model funnel?"""
    return bool(EFFECTIVE_CALL_RE.match(strip_parens(expr.strip())))


def value_candidates(expr, depth=0):
    """Every value [expr] can evaluate to, with constant branches folded away.

    This is the anti-`spelling` core of arms 4b/5: it answers "what does this
    position really evaluate to", so neither a chain tail (`effectiveModel(x)
    .let { entry.model }`) nor a dead branch (`if (false) … else entry.model`)
    can pass on the strength of a substring.
    """
    e = strip_parens(expr.strip())
    if not e or depth > 4:
        return [e] if e else []
    if re.match(r"if\s*\(", e):
        blocks = if_blocks(e)
        if blocks:
            cond, t_start, t_end, e_start, e_end = blocks[0]
            folded = const_bool(strip_parens(cond))
            out = []
            if folded is not False:
                out += value_candidates(_branch_value(e[t_start:t_end]), depth + 1)
            if e_start is not None:
                if folded is not True:
                    out += value_candidates(_branch_value(e[e_start:e_end]), depth + 1)
            elif folded is False:
                return []
            return out
    return value_sources(e)


def check_effective_model_funnel(root, rels):
    """Arms 4-7 — the effective-model funnel (deep-audit-1044 A-01)."""
    out = []
    srcs = {k: read(rel, root) for k, rel in rels.items()}
    for key, arm in (("pr", "4"), ("asr", "5"), ("vm", "7")):
        if srcs.get(key) is None:
            out.append((rels[key], 0, arm, "file not found — gate ROT"))
    if out:
        return out

    pr_code = _view(srcs["pr"])
    pr_bodies, pr_occ = modality_occurrences(pr_code)
    by_name = {}
    for name, start, end, kind in pr_bodies:
        by_name.setdefault(name, []).append((start, end, kind))

    seed_fns = [name for name, start, end, _kind in pr_bodies
                if SEED_ANCHOR in pr_code[start:end]]
    exempt = set(seed_fns)

    # Arm 4a — no capability decision on an un-enriched model.
    for line, owner, pred, receiver in pr_occ:
        if owner in exempt:
            continue
        if is_sanctioned(receiver):
            continue
        out.append((rels["pr"], line, "4",
                    "`%s` is read off `%s` — an un-enriched model (base + overrides, "
                    "no models.dev). A modality decision must go through the effective-model "
                    "funnel; for a built-in model the raw lists are null, so this answers "
                    "\"not capable\" for every multimodal model"
                    % (pred, receiver[0] or "<unknown>")))

    # Arm 4b — the funnel must RETURN the enrichment, not merely mention it.
    funnel = by_name.get("effectiveEntryModel")
    if not funnel:
        out.append((rels["pr"], 0, "4",
                    "`effectiveEntryModel(` funnel is gone — every capability decision in "
                    "this file has to route through ModelsDevApi.effectiveModel"))
    elif not _funnel_returns_effective(pr_code, funnel[0]):
        out.append((rels["pr"], 0, "4",
                    "the effectiveEntryModel( funnel does not RETURN the enrichment — the "
                    "call is present but the value that leaves the function is not what "
                    "`ModelsDevApi.effectiveModel(` produced (a dead-branch literal guard, or "
                    "a chain tail such as `.let { entry.model }` / `.also {}` that discards "
                    "it). Every capability decision downstream then reads the un-enriched "
                    "base + overrides model"))

    # Arm 4c — decision ledger: a dropped/renamed capability gate must not pass.
    for fn, preds in PR_DECISION_LEDGER.items():
        if fn not in by_name:
            out.append((rels["pr"], 0, "4",
                        "decision function `%s` not found — gate ROT (renamed or removed? "
                        "update PR_DECISION_LEDGER in this gate if the rename is intended)" % fn))
            continue
        start, end, _kind = by_name[fn][0]
        body = pr_code[start:end]
        for pred in preds:
            if "." + pred not in body:
                out.append((rels["pr"], 0, "4",
                            "`%s` no longer reads `%s` — the capability gate that keeps "
                            "non-conforming models out was dropped" % (fn, pred)))

    # Arm 6 — the one sanctioned raw read (voice-template seeds).
    if len(seed_fns) > 1:
        out.append((rels["pr"], 0, "6",
                    "the seed carve-out is claimed by %d functions (%s) — exactly ONE may "
                    "own `%s`, otherwise the exemption silently widens"
                    % (len(seed_fns), ", ".join(sorted(seed_fns)), SEED_ANCHOR)))
    elif seed_fns:
        start, end, _kind = by_name[seed_fns[0]][0]
        body = pr_code[start:end]
        if not any("." + p in body for p in MODALITY_PREDICATES):
            out.append((rels["pr"], 0, "6",
                        "the voice-template seed filter in `%s` lost its modality read — seeds "
                        "would be kept/dropped by accident" % seed_fns[0]))
        if "effectiveEntryModel(" in body or "effectiveModel(" in body:
            out.append((rels["pr"], 0, "6",
                        "`%s` routes the template seeds through the effective-model funnel — "
                        "seeds are plain LLMModel values with no ModelEntry, so there is nothing "
                        "to enrich; this read must stay raw" % seed_fns[0]))

    # Arm 5 — the ASR request carries the effective model.
    asr_code = _view(srcs["asr"])
    _, asr_occ = modality_occurrences(asr_code)
    for line, owner, pred, receiver in asr_occ:
        if is_sanctioned(receiver):
            continue
        out.append((rels["asr"], line, "5",
                    "`%s` is read off `%s` in the transcription engine — the vendor adapters "
                    "must see the effective model" % (pred, receiver[0] or "<unknown>")))
    resolved = re.search(r"\bresolvedModel\s*=\s*", asr_code)
    if resolved is None:
        out.append((rels["asr"], 0, "5",
                    "`resolvedModel =` not found in the VoiceInputRequest construction — "
                    "gate ROT (renamed field?)"))
    else:
        stop = expr_end(asr_code, resolved.end())
        expr = asr_code[resolved.end():stop].strip().rstrip(",").strip()
        cands = value_candidates(expr)
        if not cands or not all(is_effective_value(c) for c in cands):
            out.append((rels["asr"], line_of(asr_code, resolved.start()), "5",
                        "the transcription request hands over `%s` — that is base + "
                        "overrides with no models.dev row (the vendor adapters read "
                        "modalities and capability off it): %s"
                        % (expr, " / ".join(cands) or "<unparseable>")))

    # Arm 7 — coverage drift: a new audio predicate must be listed above.
    vm_code = _view(srcs["vm"])
    declared = [m.group(1) for m in re.finditer(r"val\s+LLMModel\.(\w+)\s*:\s*Boolean", vm_code)]
    for idx, name in enumerate(declared):
        if name in MODALITY_PREDICATES or name in NON_SELECTION_PREDICATES:
            continue
        start = vm_code.find("val LLMModel." + name)
        next_start = vm_code.find("val LLMModel.", start + 1)
        body = vm_code[start:next_start if next_start > 0 else len(vm_code)].lower()
        if "audio" not in body:
            continue
        out.append((rels["vm"], vm_code.count("\n", 0, start) + 1, "7",
                    "new audio predicate `%s` is not covered by this gate — add it to "
                    "MODALITY_PREDICATES (a selection decision) or NON_SELECTION_PREDICATES "
                    "(not a selection decision)" % name))

    return out


def _funnel_returns_effective(code, span):
    """Arm 4b — the funnel must BE the enrichment, not merely mention it.

    `= if (false) ModelsDevApi.effectiveModel(entry) else entry.model` and
    `= ModelsDevApi.effectiveModel(entry).let { entry.model }` both keep the
    substring while every capability decision downstream reads the raw model
    (all 39 built-ins have null modality lists → voice-input list empties, an
    image-only model can be chosen as the default text model).
    """
    start, end, kind = span
    body = code[start:end]
    if kind == "expr":
        expr = body.strip()
    else:
        last = None
        for last in re.finditer(r"\breturn\b", body):
            pass
        if last is None:
            return False
        expr = body[last.end():expr_end(body, last.end())].strip()
    cands = value_candidates(expr)
    if not cands:
        return False
    return all(is_effective_value(c) for c in cands)


RETURN_HEAD_RE = re.compile(r"return\b\s*")


def _return_value(body, pos):
    """The expression a `return` at [pos] yields (bracket-aware, no fixed window)."""
    m = RETURN_HEAD_RE.match(body, pos)
    if m is None:
        return ""
    return body[m.end():expr_end(body, m.end())].strip()


def check_builder_derives(rel, code, span):
    """Arm 2b — the builder must RETURN the derivation, not just contain it.

    Every ARM2 anchor can stay verbatim while the function returns early:
    `if (true) return previous` / `if (!false) return previous` keeps
    `previous.copy(...)`, `normalizeModalityList(...)` and both diff expressions
    in the body and makes Save a silent no-op. The judgement is therefore
    "which exit is taken on every path", not "which literal is spelled".
    """
    out = []
    start, end, _kind = span
    body = code[start:end]
    exits = guaranteed_exits(body)
    productive = [p for p in exits if "previous.copy(" in _return_value(body, p)]
    if not exits:
        out.append((rel, 0, "2",
                    "buildModelEntryOverrides has no `return` — the derivation result never "
                    "leaves the function"))
        return out
    if not productive:
        out.append((rel, 0, "2",
                    "buildModelEntryOverrides never returns a `previous.copy(...)` value — the "
                    "derivation never reaches the caller, so every Save is a no-op"))
        return out
    if exits[0] < productive[0]:
        out.append((rel, line_of(code, start + exits[0]), "2",
                    "buildModelEntryOverrides exits here on EVERY path (a constant-true guard "
                    "such as `if (true) return previous` / `if (!false) return previous`) "
                    "before the `previous.copy(...)` derivation is returned — the copy "
                    "expression is left in the body as dead code, every anchor matches, and "
                    "every Save is a silent no-op"))
    return out


def check_derive_modality_guard(rel, code, span):
    """Arm 3 — the merge guard must be FEASIBLE and carry the toggle set.

    `if (false && name.isNotEmpty() && name !in toggleNames) out.add(name)`
    keeps both anchors and never merges: every non-default capability name
    disappears from the saved override.
    """
    out = []
    start, end, _kind = span
    body = code[start:end]
    merge_sites = 0
    for m in re.finditer(r"\bout\.add\(\s*name\s*\)", body):
        pos = m.start()
        conds = [c for _kind, c in enclosing_conditions(body, pos)]
        folded = [const_bool(strip_parens(c)) for c in conds]
        if any(f is False for f in folded):
            bad = conds[folded.index(False)]
            out.append((rel, line_of(code, start + pos), "3",
                        "`out.add(name)` is guarded by `%s`, which folds to false — the "
                        "toggle names are never merged, so every non-default capability "
                        "switch disappears from the saved override"
                        % " ".join(bad.split())))
            continue
        if any("toggleNames" in c for c in conds):
            merge_sites += 1
    if merge_sites == 0 and not out:
        out.append((rel, 0, "3",
                    "no `out.add(name)` is guarded by the `toggleNames` passthrough "
                    "condition — the base list is rebuilt from the switches alone "
                    "(text / video dimensions dropped)"))
    return out


def run_checks(root, rels=None):
    rels = rels or {"me": ME_REL, "ob": OB_REL, "pr": PR_REL, "asr": ASR_REL, "vm": VM_REL}
    srcs = {k: read(rel, root) for k, rel in rels.items()}
    missing = [(rel, 0, "?", "file not found — gate ROT")
               for k, rel in rels.items() if srcs[k] is None]
    if missing:
        return missing
    views = {}
    for key, rel in rels.items():
        try:
            views[key] = _view(srcs[key])
        except KotlinLexError as exc:
            return _fatal(rel, exc)
    checks = []

    # Arm 1 — screen save block wiring.
    found = save_block(views["me"])
    block, block_start = (None, 0) if found is None else found
    if block is None:
        checks.append((ME_REL, 0, "1", "save onClick block not found — gate ROT"))
    else:
        checks += check_anchors(ME_REL, block, "1", ARM1_ANCHORS, "save block")
        for snippet in FORBIDDEN_SAVE_SNIPPETS:
            if snippet in block:
                checks.append((ME_REL, 0, "1",
                               "save block contains forbidden inline-construction snippet `%s` "
                               "(regression of da1023 ISSUE-02: text/video drop, raw-space compare, "
                               "positional ctor wipes maxThinkingLevel)" % snippet))
        checks += check_save_persists_derived(
            ME_REL, block, views["me"].count("\n", 0, block_start) + 1)

    # Arm 2 — builder shape (anchors + "returns the derivation").
    span = function_body(views["ob"], "buildModelEntryOverrides")
    if span is None:
        checks.append((OB_REL, 0, "2", "buildModelEntryOverrides( not found — gate ROT"))
    else:
        body = views["ob"][span[0]:span[1]]
        checks += check_anchors(OB_REL, body, "2", ARM2_ANCHORS, "buildModelEntryOverrides")
        for snippet in ARM2_FORBIDDEN:
            if snippet in body:
                checks.append((OB_REL, 0, "2",
                               "buildModelEntryOverrides contains positional construction `%s` — "
                               "wipes fields the screen does not own (maxThinkingLevel)" % snippet))
        checks += check_builder_derives(OB_REL, views["ob"], span)

    # Arm 3 — base passthrough in deriveModalityList.
    span3 = function_body(views["ob"], "deriveModalityList")
    if span3 is None:
        checks.append((OB_REL, 0, "3", "deriveModalityList( not found — gate ROT"))
    else:
        # include the declaration header: `deriveModalityList(` is an ARM3 anchor
        # and lives in the signature, not the body
        decl = views["ob"].rfind("fun deriveModalityList(", 0, span3[0])
        body3 = views["ob"][decl if decl >= 0 else span3[0]:span3[1]]
        checks += check_anchors(OB_REL, body3, "3", ARM3_ANCHORS, "deriveModalityList")
        checks += check_derive_modality_guard(OB_REL, views["ob"], span3)

    # Arms 4-7 — the effective-model funnel.
    checks += check_effective_model_funnel(root, rels)

    return checks


# ----------------------------------------------------------------------
# Selftest fixtures — minimal Kotlin-shaped files exercising every arm.
# ----------------------------------------------------------------------
FIX_ME_OK = """@Composable
fun ModelEntryDetailScreen(instanceId: String, entryId: String, providerRepository: ProviderRepository, onBack: () -> Unit) {
    MinisButton(
        onClick = {
            val newOverrides = buildModelEntryOverrides(
                baseModel = baseModel,
                previous = overrides,
                displayName = displayName,
            )
            val updated = if (entry.isCustom) {
                entry.copy(overrides = newOverrides)
            } else {
                entry.copy(overrides = newOverrides)
            }
            providerRepository.updateEntry(updated)
            onBack()
        },
        enabled = !entry.isCustom || modelId.isNotBlank(),
    ) { Text("save") }
}
"""

FIX_ME_BAD_INLINE_LISTS = """@Composable
fun ModelEntryDetailScreen(instanceId: String, entryId: String, providerRepository: ProviderRepository, onBack: () -> Unit) {
    MinisButton(
        onClick = {
            val newInputs = buildList {
                if (imageInput) add("image")
            }
            val newOutputs = buildList {
                if (imageOutput) add("image")
            }
            val newOverrides = buildModelEntryOverrides(
                baseModel = baseModel,
                previous = overrides,
                displayName = displayName,
            )
            val updated = if (entry.isCustom) {
                entry.copy(overrides = newOverrides)
            } else {
                entry.copy(overrides = newOverrides)
            }
            providerRepository.updateEntry(updated)
            onBack()
        },
        enabled = !entry.isCustom || modelId.isNotBlank(),
    ) { Text("save") }
}
"""

FIX_ME_BAD_BARE_CTOR = """@Composable
fun ModelEntryDetailScreen(instanceId: String, entryId: String, providerRepository: ProviderRepository, onBack: () -> Unit) {
    MinisButton(
        onClick = {
            val newOverrides = ModelOverrides(
                displayName = displayName,
            )
            val updated = if (entry.isCustom) {
                entry.copy(overrides = newOverrides)
            } else {
                entry.copy(overrides = newOverrides)
            }
            providerRepository.updateEntry(updated)
            onBack()
        },
        enabled = !entry.isCustom || modelId.isNotBlank(),
    ) { Text("save") }
}
"""

FIX_OB_OK = """internal fun buildModelEntryOverrides(
    baseModel: LLMModel,
    previous: ModelOverrides,
    displayName: String,
): ModelOverrides {
    val baseInputs = normalizeModalityList(baseModel.inputModalities)
    val baseOutputs = normalizeModalityList(baseModel.outputModalities)
    val newInputs = deriveModalityList(base = baseInputs, toggles = emptyList())
    val newOutputs = deriveModalityList(base = baseOutputs, toggles = emptyList())
    return previous.copy(
        displayName = displayName,
        inputModalities = if (newInputs.toSet() != baseInputs.toSet()) newInputs else null,
        outputModalities = if (newOutputs.toSet() != baseOutputs.toSet()) newOutputs else null,
        maxThinkingLevel = previous.maxThinkingLevel,
    )
}

internal fun deriveModalityList(
    base: List<String>,
    toggles: List<Pair<String, Boolean>>,
): List<String> {
    val toggleNames = toggles.map { it.first }.toSet()
    val out = LinkedHashSet<String>()
    for ((name, on) in toggles) if (on) out.add(name)
    for (raw in base) {
        val name = raw.normalizeModalityName()
        if (name.isNotEmpty() && name !in toggleNames) out.add(name)
    }
    return out.toList()
}
"""

FIX_OB_BAD_POSITIONAL = """internal fun buildModelEntryOverrides(
    baseModel: LLMModel,
    previous: ModelOverrides,
    displayName: String,
): ModelOverrides {
    val baseInputs = normalizeModalityList(baseModel.inputModalities)
    val baseOutputs = normalizeModalityList(baseModel.outputModalities)
    val newInputs = deriveModalityList(base = baseInputs, toggles = emptyList())
    val newOutputs = deriveModalityList(base = baseOutputs, toggles = emptyList())
    return ModelOverrides(
        displayName = displayName,
        inputModalities = if (newInputs.toSet() != baseInputs.toSet()) newInputs else null,
        outputModalities = if (newOutputs.toSet() != baseOutputs.toSet()) newOutputs else null,
        maxThinkingLevel = previous.maxThinkingLevel,
    )
}

internal fun deriveModalityList(
    base: List<String>,
    toggles: List<Pair<String, Boolean>>,
): List<String> {
    val toggleNames = toggles.map { it.first }.toSet()
    val out = LinkedHashSet<String>()
    for ((name, on) in toggles) if (on) out.add(name)
    for (raw in base) {
        val name = raw.normalizeModalityName()
        if (name.isNotEmpty() && name !in toggleNames) out.add(name)
    }
    return out.toList()
}
"""

FIX_OB_BAD_RAW_COMPARE = """internal fun buildModelEntryOverrides(
    baseModel: LLMModel,
    previous: ModelOverrides,
    displayName: String,
): ModelOverrides {
    val baseInputs = baseModel.inputModalities ?: emptyList()
    val baseOutputs = baseModel.outputModalities ?: emptyList()
    val newInputs = deriveModalityList(base = baseInputs, toggles = emptyList())
    val newOutputs = deriveModalityList(base = baseOutputs, toggles = emptyList())
    return previous.copy(
        displayName = displayName,
        inputModalities = if (newInputs.toSet() != baseInputs.toSet()) newInputs else null,
        outputModalities = if (newOutputs.toSet() != baseOutputs.toSet()) newOutputs else null,
        maxThinkingLevel = previous.maxThinkingLevel,
    )
}

internal fun deriveModalityList(
    base: List<String>,
    toggles: List<Pair<String, Boolean>>,
): List<String> {
    val toggleNames = toggles.map { it.first }.toSet()
    val out = LinkedHashSet<String>()
    for ((name, on) in toggles) if (on) out.add(name)
    for (raw in base) {
        val name = raw.normalizeModalityName()
        if (name.isNotEmpty() && name !in toggleNames) out.add(name)
    }
    return out.toList()
}
"""

FIX_OB_BAD_NO_PASSTHROUGH = """internal fun buildModelEntryOverrides(
    baseModel: LLMModel,
    previous: ModelOverrides,
    displayName: String,
): ModelOverrides {
    val baseInputs = normalizeModalityList(baseModel.inputModalities)
    val baseOutputs = normalizeModalityList(baseModel.outputModalities)
    val newInputs = deriveModalityList(base = baseInputs, toggles = emptyList())
    val newOutputs = deriveModalityList(base = baseOutputs, toggles = emptyList())
    return previous.copy(
        displayName = displayName,
        inputModalities = if (newInputs.toSet() != baseInputs.toSet()) newInputs else null,
        outputModalities = if (newOutputs.toSet() != baseOutputs.toSet()) newOutputs else null,
        maxThinkingLevel = previous.maxThinkingLevel,
    )
}

internal fun deriveModalityList(
    base: List<String>,
    toggles: List<Pair<String, Boolean>>,
): List<String> {
    val toggleNames = toggles.map { it.first }.toSet()
    val out = LinkedHashSet<String>()
    for ((name, on) in toggles) if (on) out.add(name)
    return out.toList()
}
"""

FIX_OB_BAD_NO_TOGGLE_LOOP = """internal fun buildModelEntryOverrides(
    baseModel: LLMModel,
    previous: ModelOverrides,
    displayName: String,
): ModelOverrides {
    val baseInputs = normalizeModalityList(baseModel.inputModalities)
    val baseOutputs = normalizeModalityList(baseModel.outputModalities)
    val newInputs = deriveModalityList(base = baseInputs, toggles = emptyList())
    val newOutputs = deriveModalityList(base = baseOutputs, toggles = emptyList())
    return previous.copy(
        displayName = displayName,
        inputModalities = if (newInputs.toSet() != baseInputs.toSet()) newInputs else null,
        outputModalities = if (newOutputs.toSet() != baseOutputs.toSet()) newOutputs else null,
        maxThinkingLevel = previous.maxThinkingLevel,
    )
}

internal fun deriveModalityList(
    base: List<String>,
    toggles: List<Pair<String, Boolean>>,
): List<String> {
    val toggleNames = toggles.map { it.first }.toSet()
    val out = LinkedHashSet<String>()
    for (raw in base) {
        val name = raw.normalizeModalityName()
        if (name.isNotEmpty() && name !in toggleNames) out.add(name)
    }
    return out.toList()
}
"""

# ── arms 4-7 fixtures ────────────────────────────────────────────────────────
# A plausible ProviderRepository: the funnel, the six ledger decisions, and the
# one sanctioned raw seed read. The `hasAudioInput` IMPORT is deliberate — an
# import line is not a decision and must not trip arm 4.
FIX_PR_OK = """import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.hasAudioInput
import com.rikkaminis.app.data.model.hasVoiceModality
import com.rikkaminis.app.provider.ModelsDevApi

class ProviderRepository {
    private fun effectiveEntryModel(entry: ModelEntry): LLMModel =
        ModelsDevApi.effectiveModel(entry)

    fun newestProviderNewestTextEntry(): ModelEntry? {
        val textEntry = config.modelEntries
            .filter { effectiveEntryModel(it).isTextOutput }
            .lastOrNull()
        return textEntry
    }

    fun resolveVoiceInputChoice(): Any? {
        fun providerEntry(memberId: String): Any? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            if (!inst.isEnabled || !effectiveEntryModel(entry).hasAudioInput) return null
            return inst to entry
        }
        return providerEntry("x")
    }

    fun resolveVoiceInputCandidates(): List<Any> {
        fun providerEntry(memberId: String): Any? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            if (!inst.isEnabled || !effectiveEntryModel(entry).hasAudioInput) return null
            return inst to entry
        }
        return listOfNotNull(providerEntry("x"))
    }

    fun resolveVoiceOutputChoice(): Any? {
        fun providerEntry(memberId: String): Any? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            if (!inst.isEnabled || !effectiveEntryModel(entry).hasAudioOutput) return null
            return inst to entry
        }
        return providerEntry("x")
    }

    fun hasVoiceModels(instanceId: String): Boolean =
        config.modelEntries.any {
            it.providerInstanceId == instanceId && effectiveEntryModel(it).hasVoiceModality
        }

    fun shadowVoiceProviders(): List<Any> {
        val entries = config.modelEntries
        return listOf(
            entries.filter { effectiveEntryModel(it).hasAudioInput },
            entries.filter { effectiveEntryModel(it).hasAudioOutput },
        )
    }

    fun replaceEntries(instanceId: String, models: List<LLMModel>) {
        // Seeds are authored single-flag on purpose; they have no ModelEntry.
        val voiceTemplate = VoiceProviderTemplate.template(instanceBaseURL)
        val templateVoiceModelById = voiceTemplate?.mockModels
            ?.filter { it.hasVoiceModality }
            ?.associateBy { it.id }
            ?: emptyMap()
        models.size
    }
}
"""

# The pre-fix shape: same functions, same gates, read off the raw entry model.
FIX_PR_BAD_BARE = (FIX_PR_OK
                   .replace("effectiveEntryModel(it).isTextOutput", "it.model.isTextOutput")
                   .replace("!effectiveEntryModel(entry).hasAudioInput", "!entry.model.hasAudioInput")
                   .replace("!effectiveEntryModel(entry).hasAudioOutput", "!entry.model.hasAudioOutput")
                   .replace("effectiveEntryModel(it).hasVoiceModality", "it.model.hasVoiceModality")
                   .replace("effectiveEntryModel(it).hasAudioInput", "it.model.hasAudioInput")
                   .replace("effectiveEntryModel(it).hasAudioOutput", "it.model.hasAudioOutput"))

# The funnel exists but re-derives the raw model — the shape that hands the
# registry value back over an explicit user override.
FIX_PR_BAD_NO_FUNNEL = FIX_PR_OK.replace(
    "ModelsDevApi.effectiveModel(entry)",
    "entry.model",
)

# A capability gate quietly removed (a model would be offered even when the
# registry says it cannot produce audio).
FIX_PR_BAD_DROPPED_GATE = FIX_PR_OK.replace(
    "            if (!inst.isEnabled || !effectiveEntryModel(entry).hasAudioOutput) return null\n",
    "",
)

# The seed read routed through the funnel (nothing to enrich — a seed has no
# ModelEntry).
FIX_PR_BAD_SEED_ENRICHED = FIX_PR_OK.replace(
    "?.filter { it.hasVoiceModality }",
    "?.filter { effectiveEntryModel(seedEntry).hasVoiceModality }",
)

# A second function claiming the exempt anchor would widen the carve-out.
FIX_PR_BAD_TWO_SEEDS = FIX_PR_OK + """
fun templateSeedCount(instanceBaseURL: String?): Int {
    val seeds = VoiceProviderTemplate.template(instanceBaseURL)?.mockModels
    return seeds?.size ?: 0
}
"""

FIX_ASR_OK = """import com.rikkaminis.app.provider.ModelsDevApi

class ProviderSpeechRecognitionEngine {
    private fun transcribeOnce() {
        val response = provider.transcribe(
            VoiceInputRequest(
                audioData = wav,
                model = entry.baseModel.id,
                language = locale.toLanguageTag(),
                resolvedModel = ModelsDevApi.effectiveModel(entry),
            ),
        )
        response.text
    }
}
"""

FIX_ASR_BAD = """class ProviderSpeechRecognitionEngine {
    private fun transcribeOnce() {
        val response = provider.transcribe(
            VoiceInputRequest(
                audioData = wav,
                model = entry.baseModel.id,
                language = locale.toLanguageTag(),
                resolvedModel = entry.model,
            ),
        )
        response.text
    }
}
"""

FIX_ASR_ROT = """class ProviderSpeechRecognitionEngine {
    private fun transcribeOnce() {
        val response = provider.transcribe(VoiceInputRequest(audioData = wav))
        response.text
    }
}
"""

FIX_VM = """package com.rikkaminis.app.data.model

val LLMModel.hasAudioInput: Boolean
    get() = normalizedInputs?.contains("audio") == true

val LLMModel.hasAudioOutput: Boolean
    get() = normalizedOutputs?.contains("audio") == true

val LLMModel.hasVoiceModality: Boolean
    get() = hasAudioInput || hasAudioOutput

val LLMModel.isVoiceTemplateSeedShape: Boolean
    get() = normalizedInputs == listOf("audio")
"""

FIX_VM_DRIFT = FIX_VM + """
val LLMModel.hasAudioStream: Boolean
    get() = normalizedInputs?.contains("audio") == true
"""

BASE_FIXTURES = {
    "me": FIX_ME_OK,
    "ob": FIX_OB_OK,
    "pr": FIX_PR_OK,
    "asr": FIX_ASR_OK,
    "vm": FIX_VM,
}

# (name, {rel-key: fixture override}, expected-arm)
# ── wide3-01 witnesses ──────────────────────────────────────────────────────
# The Save block still computes `newOverrides`; the persisted copy takes an empty
# ModelOverrides() instead (the old slice stopped before the copy, so it never
# looked).
FIX_ME_DROPPED_OVERRIDES = (FIX_ME_OK
                            .replace("entry.copy(overrides = newOverrides)",
                                     "entry.copy(overrides = ModelOverrides())"))

# Every ARM2 anchor stays in the body; an unconditional literal guard returns
# the stale object first.
FIX_OB_EARLY_RETURN = FIX_OB_OK.replace(
    "): ModelOverrides {\n",
    "): ModelOverrides {\n    if (true) return previous\n",
    1,
)

# The funnel keeps the substring and returns the raw model.
FIX_PR_FUNNEL_LITERAL_GATE = FIX_PR_OK.replace(
    "ModelsDevApi.effectiveModel(entry)\n",
    "if (false) ModelsDevApi.effectiveModel(entry) else entry.model\n",
    1,
)

# ── [batch5-t07] witnesses (report-A §08): the knives that used to pass ────
# W1 (family-hole): the persist call is dead behind the OTHER spelling of a
# constant-false guard — an equivalently folded unary negation.
FIX_ME_DEAD_GUARD_NOT_TRUE = FIX_ME_OK.replace(
    "providerRepository.updateEntry(updated)",
    "if (!true) providerRepository.updateEntry(updated)")

# W2 (family-hole): the early exit keeps every ARM2 anchor alive and folds to
# `true` through `!false` instead of the literal the old check looked for.
FIX_OB_EARLY_RETURN_NOT_FALSE = FIX_OB_OK.replace(
    "): ModelOverrides {\n",
    "): ModelOverrides {\n    if (!false) return previous\n",
    1,
)

# W3 (family-hole): a constant-false CONJUNCT PREFIX is prepended to the real
# business condition — no `if (false)` of its own to match.
FIX_OB_DEAD_MERGE_PREFIX = FIX_OB_OK.replace(
    "if (name.isNotEmpty() && name !in toggleNames) out.add(name)",
    "if (false && name.isNotEmpty() && name !in toggleNames) out.add(name)")

# W4 (dataflow): the funnel really calls effectiveModel and then throws the
# result away through a value-scope chain tail.
FIX_PR_FUNNEL_DISCARDED_TAIL = FIX_PR_OK.replace(
    "ModelsDevApi.effectiveModel(entry)\n",
    "ModelsDevApi.effectiveModel(entry).let { entry.model }\n",
    1,
)

# W5 (dataflow): same weapon one layer down — the ASR request field.
FIX_ASR_DISCARDED_TAIL = FIX_ASR_OK.replace(
    "resolvedModel = ModelsDevApi.effectiveModel(entry),",
    "resolvedModel = effectiveModel(entry).let { entry.model },")

SELFTEST_CASES = [
    ("all-ok", {}, None),
    ("me-inline-lists", {"me": FIX_ME_BAD_INLINE_LISTS}, "1"),
    ("me-bare-ctor", {"me": FIX_ME_BAD_BARE_CTOR}, "1"),
    ("me-overrides-dropped", {"me": FIX_ME_DROPPED_OVERRIDES}, "1"),
    ("ob-positional-ctor", {"ob": FIX_OB_BAD_POSITIONAL}, "2"),
    ("ob-raw-compare", {"ob": FIX_OB_BAD_RAW_COMPARE}, "2"),
    ("ob-early-return", {"ob": FIX_OB_EARLY_RETURN}, "2"),
    ("ob-no-passthrough", {"ob": FIX_OB_BAD_NO_PASSTHROUGH}, "3"),
    ("ob-no-toggle-loop", {"ob": FIX_OB_BAD_NO_TOGGLE_LOOP}, "3"),
    ("pr-bare-modality-read", {"pr": FIX_PR_BAD_BARE}, "4"),
    ("pr-funnel-is-raw", {"pr": FIX_PR_BAD_NO_FUNNEL}, "4"),
    ("pr-funnel-literal-gated", {"pr": FIX_PR_FUNNEL_LITERAL_GATE}, "4"),
    ("pr-gate-dropped", {"pr": FIX_PR_BAD_DROPPED_GATE}, "4"),
    ("pr-seed-enriched", {"pr": FIX_PR_BAD_SEED_ENRICHED}, "6"),
    ("pr-two-seed-owners", {"pr": FIX_PR_BAD_TWO_SEEDS}, "6"),
    ("asr-raw-resolved-model", {"asr": FIX_ASR_BAD}, "5"),
    ("asr-no-resolved-model", {"asr": FIX_ASR_ROT}, "5"),
    ("vm-new-audio-predicate", {"vm": FIX_VM_DRIFT}, "7"),
    # [batch5-t07] witness arms
    ("me-dead-guard-not-true", {"me": FIX_ME_DEAD_GUARD_NOT_TRUE}, "1"),
    ("ob-early-return-not-false", {"ob": FIX_OB_EARLY_RETURN_NOT_FALSE}, "2"),
    ("ob-dead-merge-prefix", {"ob": FIX_OB_DEAD_MERGE_PREFIX}, "3"),
    ("pr-funnel-discards-tail", {"pr": FIX_PR_FUNNEL_DISCARDED_TAIL}, "4"),
    ("asr-resolved-discards-tail", {"asr": FIX_ASR_DISCARDED_TAIL}, "5"),
]


def selftest():
    rels = {
        "me": "ModelEntryDetailScreen.kt",
        "ob": "ModelEntryOverrideBuilder.kt",
        "pr": "ProviderRepository.kt",
        "asr": "ProviderSpeechRecognitionEngine.kt",
        "vm": "VoiceModality.kt",
    }
    ok = True
    for name, overrides, expect_arm in SELFTEST_CASES:
        fixtures = dict(BASE_FIXTURES)
        fixtures.update(overrides)
        with tempfile.TemporaryDirectory() as tmp:
            for k, rel in rels.items():
                path = os.path.join(tmp, rel)
                parent = os.path.dirname(path)
                if parent:
                    os.makedirs(parent, exist_ok=True)
                with open(path, "w", encoding="utf-8") as f:
                    f.write(fixtures[k])
            violations = run_checks(tmp, rels=rels)
            arms = {v[2] for v in violations}
            if expect_arm is None:
                if violations:
                    ok = False
                    print("  selftest %-18s FAIL: expected clean, got arms %s" % (name, sorted(arms)))
                else:
                    print("  selftest %-18s PASS (clean)" % name)
            else:
                if arms == {expect_arm} and violations:
                    print("  selftest %-18s PASS (red on arm %s)" % (name, expect_arm))
                else:
                    ok = False
                    print("  selftest %-18s FAIL: expected red on arm %s, got arms %s" % (name, expect_arm, sorted(arms)))
    if ok:
        print("MODEL_ENTRY_OVERRIDE_SELFTEST=PASS (%d arms)" % len(SELFTEST_CASES))
    else:
        print("MODEL_ENTRY_OVERRIDE_SELFTEST=FAIL")
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root_pos", nargs="?", help="repo root (positional)")
    ap.add_argument("--root", dest="root", help="repo root")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    root = args.root or args.root_pos
    if not root:
        print("usage: model_entry_override_parity_check.py [root] | --root <root> | --selftest")
        return 2
    root = os.path.abspath(root)
    if not os.path.isdir(root):
        print("not a directory: %s" % root)
        return 2
    violations = run_checks(root)
    if violations:
        print("model_entry_override_parity_check: %d violation(s)" % len(violations))
        for rel, line, arm, msg in violations:
            print("  [%s] %s:%d %s" % (arm, rel, line, msg))
        return 1
    print("model_entry_override_parity_check: OK (7 arms, 0 violations)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
