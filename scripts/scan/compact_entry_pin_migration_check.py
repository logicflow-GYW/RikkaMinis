#!/usr/bin/env python3
"""deep-audit-1023 ISSUE-03 (P1) — compact-pin secondary-reference gate.

`AgentRuntimeLimitsPrefs.compactModelEntryId` is a *secondary reference* to a
model entry id, held in SharedPreferences rather than in ProviderConfig. Every
per-config cascade that heals the in-config references (modelGroups
.memberEntryIds, agentLoopModelEntryIds, prefs KEY_LAST_USED_ENTRY) is blind to
it, because `saveConfig` only translates ids on its way through
ProviderConfigMapping:

    idMap[entry.uuid] = compositeEntryKey(instanceId, modelId)   (write side)
    uuid = row.id                                                (load side)

A pin that is not rewritten dangles, and `CompactOrchestration` then resolves
it to nothing and silently follows the session chain (INFO-level only) — the
user's explicit "compact with THIS model" choice evaporates with no error.

Pre-fix, ProviderRepository.kt had ZERO references to AgentRuntimeLimitsPrefs:
the in-config cascades existed (lastUsedEntryId was migrated on rename) and the
prefs-level one simply was not part of the family. This gate pins the fixed
shape, in CODE (comments are stripped first):

  Arm 1  `ProviderRepository.updateEntry` — inside the rename branch
         (`if (renamed)`), the pin is migrated through the pure decision
         helper and written back.
  Arm 2  `ProviderRepository.removeEntry` — the deleted id cascades into the
         pin (reset to "" = explicit follow-session), after the agent-loop
         cascade that it mirrors.
  Arm 3  `ProviderRepository.removeInstance` — its own entry cascade (it does
         NOT route through removeEntry) also cleans the pin for the whole
         removed set.
  Arm 4  the pure decision helpers exist as top-level `internal fun`s in the
         repository file (JVM-testable, no Android dependency in the slice).

deep-audit-1024 ISSUE-03 (P1) widened the same family from two removal paths
to four, and from the pin alone to the prefs-held references next to it:

  Arm 5  `ProviderRepository.replaceEntries` — inside the
         non-`suspiciousShrink` `else` branch (a transient API hiccup must
         never nuke user config), the pruned set cascades into the pin AND
         into the per-device references.
  Arm 6  `ProviderRepository.ensureVoiceTemplateModels` — dissolving a stale
         seed entry runs the SAME cascade (group members / agent-loop pins /
         pin / per-device refs); previously it cleaned nothing but the entry
         row itself. [da1031-A] the cascade now compares ENTRY ids
         (`staleEntryIds` — the uuid/composite form every secondary ref is
         stored in); the bare `baseModel.id` spelling made all four routes a
         silent no-op.
  Arm 7  the per-device applier (`clearStaleSecondaryRefs`) clears all three
         of `lastUsedEntryId` / `voiceInputOverrideEntryId` /
         `voiceOutputOverrideEntryId` through the shared `decideStaleRefCleanup`
         predicate — the gate checks the applier BODY, so calling an emptied
         helper does not satisfy arm 6/5.
  Arm 8  `ui/settings/ModelGroupsScreen.kt` — the relocated compact-pin picker
         re-reads `AgentRuntimeLimitsPrefs.KEY_COMPACT_MODEL_ENTRY_ID` from a
         strongly-held `OnSharedPreferenceChangeListener` (the `remember{}`
         snapshot alone left the row showing an id the repository no longer
         resolves after an external write / backup restore / cascade reset).

deep-audit-1031 ISSUE-01 (P1) added arm 9:

  Arm 9  `updateEntry`'s rename branch migrates the two prefs-held voice
         overrides (`voiceInputOverrideEntryId` / `voiceOutputOverrideEntryId`)
         — the same class of per-device secondary reference as
         `lastUsedEntryId`. Judged on the WRITE POINTS (the `== oldKey`
         comparisons and the `= newUuid` rewrites) inside `if (renamed)`, not
         on the function name: a second rename path that forks the migration
         must carry its own write points, and a rename of this function keeps
         the criterion alive as long as the write points stay inside the
         rename branch.

Usage: python3 scripts/scan/compact_entry_pin_migration_check.py [ROOT]
       python3 scripts/scan/compact_entry_pin_migration_check.py --root ROOT
       python3 scripts/scan/compact_entry_pin_migration_check.py --selftest
Exit:  0 = green, 1 = violation(s) (file:line printed) / selftest failure.
"""
import os
import re
import sys

REPO_REL = "src/android/app/src/main/java/com/rikkaminis/app/data/repository/ProviderRepository.kt"

PIN_READ = "AgentRuntimeLimitsPrefs.compactModelEntryId()"
PIN_SAVE = "AgentRuntimeLimitsPrefs.save(context, compactModelEntryId ="
PIN_RESET = 'compactModelEntryId = ""'

UPDATE_ARM = (
    "decideCompactPinMigration(",
    PIN_READ,
    "compactModelEntryId = migrated",
)
REMOVE_ARM = (
    "decideCompactPinCleanup(",
    PIN_READ,
    PIN_RESET,
)
REMOVE_INSTANCE_ARM = (
    "decideCompactPinCleanup(",
    PIN_RESET,
)
HELPER_ARM = (
    "internal fun decideCompactPinMigration(",
    "internal fun decideCompactPinCleanup(",
)

# ── [da1024-C] arms 5-8 ──────────────────────────────────────────────────
UI_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/ModelGroupsScreen.kt"

# Arm 5: replaceEntries' prune branch (the else of the suspicious-shrink guard).
REPLACE_SHRINK_GUARD = "if (suspiciousShrink)"
REPLACE_ARM = (
    "decideCompactPinCleanup(",
    PIN_READ,
    PIN_RESET,
    "clearStaleSecondaryRefs(prunedEntryIds)",
)

# Arm 6: ensureVoiceTemplateModels' stale-seed branch. [da1031-A] the cascade
# must compare ENTRY ids (staleEntryIds — the uuid/composite form every
# secondary reference is stored in); bare baseModel.id values made all four
# routes a silent no-op, and the old spelling was pinned by the test below.
VOICE_GUARD = "if (staleSeedIds.isNotEmpty())"
VOICE_ARM = (
    "val staleEntryIds",
    "config.agentLoopModelEntryIds.removeAll { it in staleEntryIds }",
    "group.memberEntryIds.removeAll { it in staleEntryIds }",
    "decideCompactPinCleanup(",
    PIN_READ,
    PIN_RESET,
    "clearStaleSecondaryRefs(staleEntryIds)",
)

# Arm 7: the per-device applier's BODY (so an emptied helper cannot pass).
CLEAR_HEADER = "private fun clearStaleSecondaryRefs("
CLEAR_ARM = (
    "decideStaleRefCleanup(lastUsedEntryId",
    "decideStaleRefCleanup(voiceInputOverrideEntryId",
    "decideStaleRefCleanup(voiceOutputOverrideEntryId",
)
DECIDE_STALE_ARM = "internal fun decideStaleRefCleanup("

# Arm 8: the relocated picker's prefs listener.
UI_ARM = (
    "val listener = SharedPreferences.OnSharedPreferenceChangeListener",
    "AgentRuntimeLimitsPrefs.PREFS",
    "registerOnSharedPreferenceChangeListener(listener)",
    "AgentRuntimeLimitsPrefs.KEY_COMPACT_MODEL_ENTRY_ID",
    "compactModelEntryId = AgentRuntimeLimitsPrefs.compactModelEntryId()",
    "unregisterOnSharedPreferenceChangeListener(listener)",
)

# Code anchors that must appear BEFORE the pin handling inside the same
# function, i.e. the pin edit rides along with the cascade it mirrors.
UPDATE_AFTER = "if (renamed)"
REMOVE_AFTER = "config.agentLoopModelEntryIds.removeAll { it == entryId }"

# Arm 9: updateEntry's rename branch must migrate the two prefs-held voice
# overrides. The criterion hangs on the WRITE POINTS (the `== oldKey`
# comparisons and the `= newUuid` rewrites) inside `if (renamed)` — family
# lesson (deep-audit-1030-E): a second release/clean-up path forks silently
# unless the criterion is bound to the point where the write happens, not to a
# function name a refactor can rename around.
VOICE_RENAME_ARM = (
    "voiceInputOverrideEntryId == oldKey",
    "voiceInputOverrideEntryId = newUuid",
    "voiceOutputOverrideEntryId == oldKey",
    "voiceOutputOverrideEntryId = newUuid",
)


import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_lexer import KotlinLexError  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool,
    const_value,
    enclosing_conditions,
    function_body,
    if_blocks,
    is_pure_literal,
    match_bracket,
    split_top,
    strip_parens,
    tail_expr,
    when_branches,
)
from _kotlin_valueflow import (  # noqa: E402
    call_args,
    code_view,
    contains_ident,
    expr_end,
    guard_feasible,
    literal_view,
    path_feasible,
    value_sources,
)

SELFTEST_MARK = "COMPACT_ENTRY_PIN_MIGRATION_SELFTEST=PASS"


# ── views ────────────────────────────────────────────────────────────────
def views(src):
    """(code, literal) — both length preserving, so offsets map 1:1.

    [b6-t11 parser-death] This gate used to carry a hand-rolled `code_only`
    that stripped `//` and nothing else. Wrapping a whole cascade in `/* … */`
    therefore left every anchor alive in the scanned text: all nine arms were
    blind to the cheapest possible kill (witness A12-6). The shared masker
    covers line comments, NESTED block comments, `\"\"\"…\"\"\"`, `${…}`
    templates and char literals; `KotlinLexError` is fail-closed at the caller.
    """
    return code_view(src), literal_view(src)


def line_of(text, idx):
    return text.count("\n", 0, max(0, idx)) + 1


# ── declaration spans (shared function_body) ─────────────────────────────
DECL_RE = re.compile(
    r"\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\s*\.\s*)?([A-Za-z_]\w*)\s*\(")


def decl_name(header):
    m = DECL_RE.search(header)
    return m.group(1) if m else None


def decl_span(cc, header):
    """(start, end) of the declaration's body in the code view, or (None, None)."""
    name = decl_name(header)
    if name is None:
        return None, None
    fb = function_body(cc, name)
    if fb is None:
        return None, None
    return fb[0], fb[1]


def decl_params(cc, header):
    """Declared parameter names (used for "the body must read its inputs")."""
    name = decl_name(header)
    if name is None:
        return []
    m = re.search(
        r"\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\s*\.\s*)?%s\s*\(" % re.escape(name), cc)
    if m is None:
        return []
    close = match_bracket(cc, m.end() - 1)
    if close < 0:
        return []
    return re.findall(r"([A-Za-z_]\w*)\s*:", cc[m.end():close])


def body_of(cc, header):
    start, end = decl_span(cc, header)
    if start is None:
        return None, 0
    return cc[start:end], start


# ── constant folding of an expression's VALUE ────────────────────────────
def depth0_token(text, token, start=0):
    """Index of `token` at bracket depth 0 at/after start (-1 when absent)."""
    depth = 0
    i, n = start, len(text)
    while i < n:
        c = text[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and text.startswith(token, i):
            after = i + len(token)
            if token[0].isalpha() and after < n and (text[after].isalnum() or text[after] == "_"):
                i += 1
                continue
            return i
        i += 1
    return -1


def split_if(text, start):
    """(cond, then_span, else_span|None) for an `if (...)` starting at start.

    `else` is located at depth 0 from the then-branch, so a same-line
    `if (c) a else b` is read as two branches — the shared `if_blocks` derives
    the else from what FOLLOWS the then-branch extent, which swallows the
    same-line spelling into the then-branch (registered as R1).
    """
    m = re.match(r"\s*if\s*\(", text[start:])
    if m is None:
        return None
    op = start + m.end() - 1
    close = match_bracket(text, op)
    if close < 0:
        return None
    cond = text[op + 1:close]
    k = depth0_token(text, "else", close + 1)
    if k < 0:
        return cond, (close + 1, len(text)), None
    return cond, (close + 1, k), (k + 4, len(text))


def when_parts(expr):
    """(subject_text, [(pattern, body_text)]) for a `when (...) { … }` head."""
    m = re.match(r"when\s*(\(|\{)", expr)
    if m is None:
        return None
    k = m.end() - 1
    if m.group(1) == "(":
        close = match_bracket(expr, k)
        if close < 0:
            return None
        subject = expr[m.end():close]
        k = close + 1
        while k < len(expr) and expr[k] in " \t\r\n":
            k += 1
    else:
        subject = ""
    if k >= len(expr) or expr[k] != "{":
        return None
    close = match_bracket(expr, k)
    if close < 0:
        return None
    head = expr[:close + 1]
    branches = [(cond.strip(), head[b_start:b_end])
                for _subj, cond, b_start, b_end in when_branches(head)]
    return subject, branches


def branch_value(text):
    """The value a branch yields: a `{ … }` block reads as its tail expression."""
    t = text.strip()
    if t.startswith("{"):
        close = match_bracket(t, 0)
        if close > 0:
            return tail_expr(t[1:close])
    return t


def fold_value(expr, depth=0):
    """('const', text) when every REACHABLE branch yields a literal.

    Witness A12-1 keeps the helper declared, keeps all nine call sites and
    writes `if (false && pinEntryId.isNotEmpty() && …) newKey else null` — the
    old `CONST_BODY_RE` only rejected the bare `if (false)` spelling, so the
    conjunction (and every arithmetic/`when` equivalent) read as "still
    deciding". Folding the condition through the shared `const_bool` (which
    implements Kotlin's short-circuit semantics) and then folding only the
    reachable branch makes the whole family visible: `false && x`,
    `(2 == 3) && x`, `!true`, `1 > 2`, `when (false) { … }`.
    """
    if depth > 6:
        return ("open", "")
    e = expr.strip().rstrip(";").strip()
    if not e:
        return ("open", "")
    if e.startswith("return "):
        return fold_value(e[len("return "):], depth + 1)
    if is_pure_literal(e):
        return ("const", e)
    parts = split_if(e, 0)
    if parts is not None:
        cond, t_span, e_span = parts
        cb = const_bool(cond)
        if cb is True:
            return fold_value(branch_value(e[t_span[0]:t_span[1]]), depth + 1)
        if cb is False:
            if e_span is None:
                return ("open", "")
            return fold_value(branch_value(e[e_span[0]:e_span[1]]), depth + 1)
        if e_span is None:
            return ("open", "")
        then_kind, _ = fold_value(branch_value(e[t_span[0]:t_span[1]]), depth + 1)
        else_kind, _ = fold_value(branch_value(e[e_span[0]:e_span[1]]), depth + 1)
        if then_kind == "const" and else_kind == "const":
            return ("const", e)
        return ("open", "")
    wp = when_parts(e)
    if wp is not None:
        subject, branches = wp
        sv = const_value(subject) if subject.strip() else None
        if sv is None:
            return ("open", "")
        for pattern, body_text in branches:
            if pattern == "else" or const_value(pattern) == sv:
                return fold_value(branch_value(body_text), depth + 1)
        return ("open", "")
    srcs = value_sources(e)
    if len(srcs) > 1 and all(is_pure_literal(s) for s in srcs):
        return ("const", e)
    return ("open", "")


# ── liveness ─────────────────────────────────────────────────────────────
def dead_condition_at(cc, pos):
    """The first enclosing `if` condition that cannot be true ('' when none)."""
    for kind, cond in enclosing_conditions(cc, pos):
        if kind != "if":
            continue  # when(subject) is not modelled by the shared fold (R2)
        if not guard_feasible(cond):
            return re.sub(r"\s+", " ", cond).strip()
    return ""


def check_live(cc, rel, header, arm, needle, what):
    """A write point under a DEAD condition is not a write.

    Witnesses A12-1/2/4/5 all keep the anchor text and kill its effect by
    making the enclosing condition a constant (`false && …`,
    `(2 == 3) && …`, a bare `if (false)`). Presence checks cannot see that;
    feasibility can, and it folds every spelling rather than the one constant
    pair a blacklist happened to enumerate.
    """
    if header is None:
        body, base = cc, 0
    else:
        body, base = body_of(cc, header)
        if body is None:
            return [(rel, 0, arm, header + " not found — gate ROT")]
    pos = body.find(needle)
    if pos < 0:
        return []  # a missing anchor is check_body / check_inside's finding
    cond = dead_condition_at(cc, base + pos)
    if cond:
        return [(rel, line_of(cc, base + pos), arm,
                 "%s: `%s` sits under a DEAD condition (`%s`) — the branch can never "
                 "run, so the anchor is decoration" % (what, needle, cond[:90]))]
    return []


# ── anchor / order / containment ─────────────────────────────────────────
def check_body(cc, rel, header, arm, anchors, what):
    body, base = body_of(cc, header)
    if body is None:
        return [(rel, 0, arm, header + " not found — gate ROT")]
    return [(rel, line_of(cc, base), arm,
             "%s is missing code anchor `%s`" % (what, a))
            for a in anchors if a not in body]


def check_order(cc, rel, header, arm, first, second, what):
    """`first` must appear before `second` inside the function (code view)."""
    body, base = body_of(cc, header)
    if body is None:
        return [(rel, 0, arm, header + " not found — gate ROT")]
    i, j = body.find(first), body.find(second)
    if i < 0:
        return [(rel, line_of(cc, base), arm,
                 "%s is missing code anchor `%s`" % (what, first))]
    if j >= 0 and j < i:
        return [(rel, line_of(cc, base), arm,
                 "%s: `%s` must come AFTER `%s`" % (what, second, first))]
    return []


def check_inside(cc, rel, header, arm, guard, needle, what):
    """`needle` must appear INSIDE the `guard { ... }` block of `header`."""
    body, base = body_of(cc, header)
    if body is None:
        return [(rel, 0, arm, header + " not found — gate ROT")]
    g = body.find(guard)
    if g < 0:
        return [(rel, line_of(cc, base), arm,
                 "%s is missing guard `%s`" % (what, guard))]
    brace = body.find("{", g)
    end = match_bracket(body, brace) if brace >= 0 else -1
    if brace < 0 or end < 0:
        return [(rel, line_of(cc, base), arm,
                 "%s guard `%s` is not a braced block — gate ROT" % (what, guard))]
    if needle not in body[brace:end]:
        return [(rel, line_of(cc, base), arm,
                 "%s: `%s` must sit INSIDE `%s`" % (what, needle, guard))]
    return []


def check_inside_else_of(cc, rel, header, arm, guard, needles, what):
    """`needles` must appear INSIDE the `else { ... }` of `guard`."""
    body, base = body_of(cc, header)
    if body is None:
        return [(rel, 0, arm, header + " not found — gate ROT")]
    g = body.find(guard)
    if g < 0:
        return [(rel, line_of(cc, base), arm,
                 "%s is missing guard `%s`" % (what, guard))]
    brace = body.find("{", g)
    block_end = match_bracket(body, brace) if brace >= 0 else -1
    if brace < 0 or block_end < 0:
        return [(rel, line_of(cc, base), arm,
                 "%s guard `%s` is not a braced block — gate ROT" % (what, guard))]
    e = body.find("else", block_end)
    if e < 0:
        return [(rel, line_of(cc, base), arm,
                 "%s: `%s` has no `else` branch — gate ROT" % (what, guard))]
    ebrace = body.find("{", e)
    eend = match_bracket(body, ebrace) if ebrace >= 0 else -1
    if ebrace < 0 or eend < 0:
        return [(rel, line_of(cc, base), arm,
                 "%s: the `else` of `%s` is not a braced block — gate ROT"
                 % (what, guard))]
    block = body[ebrace:eend]
    return [(rel, line_of(cc, base), arm,
             "%s: `%s` must sit INSIDE the `else` of `%s`" % (what, n, guard))
            for n in needles if n not in block]


# ── arm 4: the decision helpers must still DECIDE ────────────────────────
def check_helper_bodies(cc, rel):
    out = []
    for header in HELPER_ARM + (DECIDE_STALE_ARM,):
        name = decl_name(header)
        start, end = decl_span(cc, header)
        if start is None:
            out.append((rel, 0, "4", "helper `%s` has no body this gate can read "
                                   "(fail-closed: re-point arm 4)" % name))
            continue
        raw = cc[start:end]
        # Fold the RAW body: `when_branches` splits branches on top-level
        # newlines, so a whitespace-collapsed copy loses every branch after the
        # first and the `when` fold would silently read as "open".
        kind, value = fold_value(raw)
        if kind == "const":
            out.append((rel, line_of(cc, start), "4",
                        "helper `%s` can only return a CONSTANT (`%s`) — the decision "
                        "is gone while every call site still calls it"
                        % (name, value[:60])))
            continue
        missing = [p for p in decl_params(cc, header) if not contains_ident(raw, p)]
        if missing:
            out.append((rel, line_of(cc, start), "4",
                        "helper `%s` never reads %s — a parameter it declares cannot "
                        "influence the decision (fail-closed)"
                        % (name, ", ".join("`%s`" % p for p in missing))))
    return out


# ── arm 7: the convergence applier's branches must be LIVE ───────────────
CLEAR_REFS = ("lastUsedEntryId", "voiceInputOverrideEntryId", "voiceOutputOverrideEntryId")


def check_clear_applier(cc, rel):
    body, base = body_of(cc, CLEAR_HEADER)
    if body is None:
        return [(rel, 0, "7", CLEAR_HEADER + " not found — gate ROT")]
    out = []
    for cond, _ts, _te, _es, _ee in if_blocks(body):
        if not guard_feasible(cond):
            out.append((rel, line_of(cc, base), "7",
                        "clearStaleSecondaryRefs gates a cleanup on a DEAD condition "
                        "(`%s`) — the branch can never run, so the reference survives "
                        "the delete it was meant to heal"
                        % re.sub(r"\s+", " ", cond)[:90]))
            break
    for ref in CLEAR_REFS:
        live = [m for m in re.finditer(r"(?<![.\w])%s\s*=\s*null\b" % re.escape(ref), body)
                if path_feasible(cc, base + m.start())]
        if not live:
            out.append((rel, line_of(cc, base), "7",
                        "clearStaleSecondaryRefs never clears `%s` on a reachable path — "
                        "the reference outlives the entries it points at" % ref))
    return out


# ── arm 6: the stale-entry-id set must be DERIVED, and non-empty ─────────
DERIVE_CALL_RE = re.compile(
    r"\.\s*(?:map|mapNotNull|filter|filterNot|asSequence|asIterable|distinct|"
    r"sortedBy|sortedByDescending|sortedWith|toList|toSet|plus|minus)\b")
ZERO_TAKE = ("take", "takeLast")
ZERO_PRED = ("filter", "filterNot", "takeWhile")


def zeroing_reason(cc, expr):
    """Reason when a call in the derivation chain forces the result to be EMPTY.

    Witness A12-3 kept the whole `instanceEntries.filter{}.filter{}.map{}`
    chain and appended `.take(0)` at the tail: every anchor (source ident,
    `.filter`, `.map`) still matched, so the old criterion was satisfied while
    the cascade was told "nothing was removed". The fix reads the CHAIN's
    ARGUMENTS and folds them: a truncation with a constant 0 and a predicate
    that folds to `false` both pin the result to the empty set — that is the
    shape, not one spelling of it.
    """
    for m in re.finditer(r"\.\s*([A-Za-z_]\w*)\s*(?=[({])", expr):
        name = m.group(1)
        op = m.end()
        if op >= len(expr):
            continue
        if expr[op] == "(":
            args = call_args(expr, op)
            if not args:
                continue
            v = const_value(expr[args[0][0]:args[0][1]])
            if name in ZERO_TAKE and v == ("num", 0.0):
                return "`.%s(0)` truncates the derived set to the empty list" % name
        elif expr[op] == "{":
            close = match_bracket(expr, op)
            if close < 0:
                continue
            b = const_bool(strip_parens(tail_expr(expr[op + 1:close])))
            if name == "filter" and b is False:
                return "`.filter { false }` drops every element"
            if name == "filterNot" and b is True:
                return "`.filterNot { true }` drops every element"
            if name == "takeWhile" and b is False:
                return "`.takeWhile { false }` keeps nothing"
    return ""


def check_stale_entry_ids(cc, rel, source="instanceEntries"):
    out = []
    body, base = body_of(cc, "fun ensureVoiceTemplateModels(")
    if body is None:
        return [(rel, 0, "6", "fun ensureVoiceTemplateModels( not found — gate ROT")]
    m = re.search(r"\bval\s+staleEntryIds\s*=", body)
    if m is None:
        return out  # the anchor loop already reports the missing declaration
    start = m.end()
    end = expr_end(body, start)
    expr = body[start:end]
    flat = re.sub(r"\s+", " ", expr).strip()
    if not re.match(r"%s\b" % re.escape(source), flat):
        out.append((rel, line_of(cc, base + start), "6",
                    "`val staleEntryIds` no longer derives the set from `%s` (`%s`) — "
                    "fail-closed: re-point this arm at the new derivation"
                    % (source, flat[:70])))
        return out
    zero = zeroing_reason(body[start:end], flat)
    if zero:
        out.append((rel, line_of(cc, base + start), "6",
                    "`val staleEntryIds` can only be EMPTY (%s) — the cascade is told "
                    "that nothing was removed while the seed entries are still "
                    "dissolved" % zero))
        return out
    for s in value_sources(flat):
        if not contains_ident(s, source) and not DERIVE_CALL_RE.search(s):
            out.append((rel, line_of(cc, base + start), "6",
                        "`val staleEntryIds` has a branch (`%s`) that does not produce "
                        "the derived set — fail-closed" % re.sub(r"\s+", " ", s)[:60]))
            break
    return out


# ── entry points ─────────────────────────────────────────────────────────
def run_checks(root, rel=REPO_REL):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return [(rel, 0, "?", "file not found — gate ROT")]
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        src = f.read()
    try:
        cc, _lc = views(src)
    except KotlinLexError as exc:
        return [(rel, 0, "?", "the repository file does not lex (%s) — fail-closed: "
                              "a file this gate cannot read must never pass" % exc)]

    out = []
    out += check_body(cc, rel, "fun updateEntry(", "1", UPDATE_ARM, "updateEntry rename branch")
    out += check_inside(cc, rel, "fun updateEntry(", "1", UPDATE_AFTER,
                        "decideCompactPinMigration(", "updateEntry rename branch")
    out += check_live(cc, rel, "fun updateEntry(", "1", "decideCompactPinMigration(",
                      "updateEntry rename branch")
    out += check_live(cc, rel, "fun updateEntry(", "1",
                      "compactModelEntryId = migrated", "updateEntry rename branch")
    out += check_body(cc, rel, "fun removeEntry(", "2", REMOVE_ARM, "removeEntry")
    out += check_order(cc, rel, "fun removeEntry(", "2", REMOVE_AFTER,
                       "decideCompactPinCleanup(", "removeEntry")
    out += check_live(cc, rel, "fun removeEntry(", "2", PIN_RESET, "removeEntry")
    out += check_body(cc, rel, "fun removeInstance(", "3", REMOVE_INSTANCE_ARM, "removeInstance")
    out += check_live(cc, rel, "fun removeInstance(", "3", PIN_RESET, "removeInstance")

    body_all = cc
    for a in HELPER_ARM:
        if a not in body_all:
            out.append((rel, 0, "4", "helper missing: `%s`" % a))
    out += check_helper_bodies(cc, rel)

    # ── [da1024-C] arms 5-8 ──
    out += check_inside_else_of(cc, rel, "fun replaceEntries(", "5",
                                REPLACE_SHRINK_GUARD, REPLACE_ARM, "replaceEntries prune branch")
    out += check_live(cc, rel, "fun replaceEntries(", "5", PIN_RESET,
                      "replaceEntries prune branch")
    for needle in VOICE_ARM:
        out += check_inside(cc, rel, "fun ensureVoiceTemplateModels(", "6", VOICE_GUARD,
                            needle, "ensureVoiceTemplateModels stale-seed branch")
    out += check_live(cc, rel, "fun ensureVoiceTemplateModels(", "6", PIN_RESET,
                      "ensureVoiceTemplateModels stale-seed branch")
    out += check_live(cc, rel, "fun ensureVoiceTemplateModels(", "6", "val staleEntryIds",
                      "ensureVoiceTemplateModels stale-seed branch")
    out += check_stale_entry_ids(cc, rel)
    out += check_body(cc, rel, CLEAR_HEADER, "7", CLEAR_ARM, "clearStaleSecondaryRefs")
    out += check_clear_applier(cc, rel)
    if DECIDE_STALE_ARM not in body_all:
        out.append((rel, 0, "7", "helper missing: `%s`" % DECIDE_STALE_ARM))
    for needle in VOICE_RENAME_ARM:
        out += check_inside(cc, rel, "fun updateEntry(", "9", UPDATE_AFTER,
                            needle, "updateEntry rename branch voice overrides")
        out += check_live(cc, rel, "fun updateEntry(", "9", needle,
                          "updateEntry rename branch voice overrides")
    return out


def run_ui_checks(root, rel=UI_REL):
    """Arm 8: the ModelGroupsScreen prefs listener that keeps the relocated
    compact-pin picker in sync with external writers."""
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return [(rel, 0, "8", "file not found — gate ROT")]
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        src = f.read()
    try:
        cc, _lc = views(src)
    except KotlinLexError as exc:
        return [(rel, 0, "8", "the screen file does not lex (%s) — fail-closed" % exc)]
    out = [(rel, line_of(cc, cc.find("ModelGroupsScreen")), "8",
            "ModelGroupsScreen compact-pin listener is missing code anchor `%s`" % a)
           for a in UI_ARM if a not in cc]
    # A listener that is only REGISTERED inside `if (false)` is not registered:
    # the row then never refreshes and the pin highlight keeps pointing at an
    # entry id the repository already dropped (witness A12-5).
    for needle in ("registerOnSharedPreferenceChangeListener(listener)",
                   "unregisterOnSharedPreferenceChangeListener(listener)",
                   "compactModelEntryId = AgentRuntimeLimitsPrefs.compactModelEntryId()"):
        out += check_live(cc, rel, None, "8", needle, "ModelGroupsScreen compact-pin listener")
    return out


def run_all(root):
    return run_checks(root) + run_ui_checks(root)


# ----------------------------------------------------------------------
# Selftest fixtures — Kotlin-shaped snippets exercising every arm.
#
# Every fixture writes BOTH files the gate reads (repository + ModelGroups
# Screen). A fixture that forgot one would light the "file not found — gate
# ROT" arm instead, which is exactly what the ROT control checks.
#
# Each bad fixture is built as a DELTA of the fixed one, so it violates its own
# arm and nothing else; `_report` asserts that (an arm that only ever goes red
# together with others is not evidence of its own criterion).
# ----------------------------------------------------------------------
PR_OK = """class ProviderRepository(private val context: Context) {
    private fun clearStaleSecondaryRefs(removedEntryIds: Collection<String>) {
        if (decideStaleRefCleanup(lastUsedEntryId, removedEntryIds)) lastUsedEntryId = null
        if (decideStaleRefCleanup(voiceInputOverrideEntryId, removedEntryIds)) {
            voiceInputOverrideEntryId = null
        }
        if (decideStaleRefCleanup(voiceOutputOverrideEntryId, removedEntryIds)) {
            voiceOutputOverrideEntryId = null
        }
    }

    fun ensureVoiceTemplateModels() = synchronized(configLock) {
        val staleSeedIds = instanceEntries
            .filter { it.baseModel.isVoiceTemplateSeedShape }
            .map { it.baseModel.id }
            .filter { it !in templateById }
        if (staleSeedIds.isNotEmpty()) {
            val staleEntryIds = instanceEntries
                .filter { it.baseModel.isVoiceTemplateSeedShape }
                .filter { it.baseModel.id !in templateById }
                .map { it.id }
            config.modelEntries.removeAll {
                it.providerInstanceId == instance.id && it.baseModel.id in staleSeedIds
            }
            for (group in config.modelGroups) {
                group.memberEntryIds.removeAll { it in staleEntryIds }
            }
            config.agentLoopModelEntryIds.removeAll { it in staleEntryIds }
            if (decideCompactPinCleanup(AgentRuntimeLimitsPrefs.compactModelEntryId(), staleEntryIds)) {
                AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = "")
            }
            clearStaleSecondaryRefs(staleEntryIds)
            changed = true
        }
    }

    fun replaceEntries(instanceId: String, models: List<LLMModel>) = synchronized(configLock) {
        val prunedEntryIds = existingEntryIds - survivingEntryIds
        if (prunedEntryIds.isNotEmpty()) {
            val suspiciousShrink = existing.size >= 4 && models.size * 2 < existing.size
            if (suspiciousShrink) {
                android.util.Log.w("ProviderRepo", "[ModelList] replaceEntries SUSPICIOUS SHRINK")
            } else {
                for (i in config.modelGroups.indices) {
                    config.modelGroups[i].memberEntryIds.removeAll { it in prunedEntryIds }
                }
                config.agentLoopModelEntryIds.removeAll { it in prunedEntryIds }
                if (decideCompactPinCleanup(AgentRuntimeLimitsPrefs.compactModelEntryId(), prunedEntryIds)) {
                    AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = "")
                }
                clearStaleSecondaryRefs(prunedEntryIds)
            }
        }
        saveConfig(config)
    }

    fun updateEntry(entry: ModelEntry): String? = synchronized(configLock) {
        val renamed = decision.newKey != null
        val newUuid = decision.newKey ?: existing.id
        if (renamed) {
            val oldKey = decision.oldKey
            if (lastUsedEntryId == oldKey) lastUsedEntryId = newUuid
            if (voiceInputOverrideEntryId == oldKey) voiceInputOverrideEntryId = newUuid
            if (voiceOutputOverrideEntryId == oldKey) voiceOutputOverrideEntryId = newUuid
            decideCompactPinMigration(
                pinEntryId = AgentRuntimeLimitsPrefs.compactModelEntryId(),
                oldKey = oldKey,
                newKey = newUuid,
            )?.let { migrated ->
                AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = migrated)
            }
        }
        saveConfig(config)
        return@synchronized newUuid
    }

    fun removeEntry(entryId: String) = synchronized(configLock) {
        config.modelEntries.removeAll { it.id == entryId }
        config.agentLoopModelEntryIds.removeAll { it == entryId }
        if (decideCompactPinCleanup(AgentRuntimeLimitsPrefs.compactModelEntryId(), setOf(entryId))) {
            AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = "")
        }
        clearStaleSecondaryRefs(setOf(entryId))
        saveConfig(config)
    }

    fun removeInstance(instanceId: String) = synchronized(configLock) {
        if (removedEntryIds.isNotEmpty()) {
            config.agentLoopModelEntryIds.removeAll { it in removedEntryIds }
            if (decideCompactPinCleanup(AgentRuntimeLimitsPrefs.compactModelEntryId(), removedEntryIds)) {
                AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = "")
            }
            clearStaleSecondaryRefs(removedEntryIds)
        }
        saveConfig(config)
    }
}

internal fun decideCompactPinMigration(pinEntryId: String, oldKey: String, newKey: String): String? =
    if (pinEntryId.isNotEmpty() && pinEntryId == oldKey && oldKey != newKey) newKey else null

internal fun decideCompactPinCleanup(pinEntryId: String, removedEntryIds: Collection<String>): Boolean =
    pinEntryId.isNotEmpty() && removedEntryIds.contains(pinEntryId)

internal fun decideStaleRefCleanup(ref: String?, removedEntryIds: Collection<String>): Boolean =
    ref != null && removedEntryIds.contains(ref)
"""

UI_OK = """package com.rikkaminis.app.ui.settings

@Composable
fun ModelGroupsScreen(providerRepository: ProviderRepository) {
    val context = LocalContext.current
    var compactModelEntryId by remember {
        mutableStateOf(AgentRuntimeLimitsPrefs.compactModelEntryId())
    }
    DisposableEffect(context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == AgentRuntimeLimitsPrefs.KEY_COMPACT_MODEL_ENTRY_ID) {
                compactModelEntryId = AgentRuntimeLimitsPrefs.compactModelEntryId()
            }
        }
        val pinPrefs = context.applicationContext.getSharedPreferences(
            AgentRuntimeLimitsPrefs.PREFS,
            Context.MODE_PRIVATE,
        )
        pinPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { pinPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
"""

_MIGRATION_BLOCK = """            decideCompactPinMigration(
                pinEntryId = AgentRuntimeLimitsPrefs.compactModelEntryId(),
                oldKey = oldKey,
                newKey = newUuid,
            )?.let { migrated ->
                AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = migrated)
            }
"""

# Regression shape A: the pre-fix world — pin untouched on rename.
PR_BAD_NO_MIGRATION = PR_OK.replace(_MIGRATION_BLOCK, "")

# Regression shape B: pin migrated but on the WRONG side of the rename guard
# (i.e. after the branch closes) — must be rejected as ordering violation.
PR_BAD_ORDER = PR_OK.replace(_MIGRATION_BLOCK, "").replace(
    """                if (lastUsedEntryId == oldKey) lastUsedEntryId = newUuid
            }
""",
    """                if (lastUsedEntryId == oldKey) lastUsedEntryId = newUuid
            }
""" + _MIGRATION_BLOCK,
)

# Regression shape C: removeEntry/removeInstance cascades still missing the pin.
_REMOVE_ENTRY_PIN = """        if (decideCompactPinCleanup(AgentRuntimeLimitsPrefs.compactModelEntryId(), setOf(entryId))) {
            AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = "")
        }
"""
_REMOVE_INSTANCE_PIN = """            if (decideCompactPinCleanup(AgentRuntimeLimitsPrefs.compactModelEntryId(), removedEntryIds)) {
                AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = "")
            }
"""
PR_BAD_NO_CLEANUP = PR_OK.replace(_REMOVE_ENTRY_PIN, "").replace(_REMOVE_INSTANCE_PIN, "")

# Regression shape D: helper removed (inlined at call sites) — JVM slice gone.
PR_BAD_NO_HELPER = PR_OK.replace("internal fun decideCompactPinCleanup", "private fun legacyCleanup")

# Regression shape E: replaceEntries' prune branch cleaned only the in-config
# refs (the ISSUE-03 path-A pre-fix world).
_REPLACE_CLEANUP = """                if (decideCompactPinCleanup(AgentRuntimeLimitsPrefs.compactModelEntryId(), prunedEntryIds)) {
                    AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = "")
                }
                clearStaleSecondaryRefs(prunedEntryIds)
"""
PR_BAD_NO_REPLACE_CLEANUP = PR_OK.replace(_REPLACE_CLEANUP, "")

# Regression shape F: cleanup present but OUTSIDE the else branch — it would
# also fire on a suspicious shrink, nuking the user's pin on an API hiccup.
PR_BAD_CLEANUP_OUTSIDE_ELSE = PR_OK.replace(
    _REPLACE_CLEANUP + "            }\n",
    "            }\n" + _REPLACE_CLEANUP.replace("\n                ", "\n        "),
)

# Regression shape G: ensureVoiceTemplateModels dissolved the seeds but cleaned
# only the entry rows (the ISSUE-03 path-B pre-fix world).
_VOICE_CASCADE = """            for (group in config.modelGroups) {
                group.memberEntryIds.removeAll { it in staleEntryIds }
            }
            config.agentLoopModelEntryIds.removeAll { it in staleEntryIds }
            if (decideCompactPinCleanup(AgentRuntimeLimitsPrefs.compactModelEntryId(), staleEntryIds)) {
                AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = "")
            }
            clearStaleSecondaryRefs(staleEntryIds)
"""
PR_BAD_NO_VOICE_CASCADE = PR_OK.replace(_VOICE_CASCADE, "")

# Regression shape L (da1031-A): the cascade restored to the pre-fix spelling —
# bare baseModel.id values compared against entry-id references (all four
# routes a silent no-op). Arm 6's re-anchored criterion must catch exactly
# this shape, including the missing `val staleEntryIds` declaration.
_VOICE_CASCADE_BARE = """            for (group in config.modelGroups) {
                group.memberEntryIds.removeAll { it in staleSeedIds }
            }
            config.agentLoopModelEntryIds.removeAll { it in staleSeedIds }
            if (decideCompactPinCleanup(AgentRuntimeLimitsPrefs.compactModelEntryId(), staleSeedIds)) {
                AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = "")
            }
            clearStaleSecondaryRefs(staleSeedIds)
"""
_VOICE_DECL = """            val staleEntryIds = instanceEntries
                .filter { it.baseModel.isVoiceTemplateSeedShape }
                .filter { it.baseModel.id !in templateById }
                .map { it.id }
"""
PR_BAD_BARE_SEED_IDS = PR_OK.replace(_VOICE_DECL, "").replace(_VOICE_CASCADE, _VOICE_CASCADE_BARE)

# Regression shape M (da1031-A): updateEntry renamed but did NOT migrate the
# two prefs-held voice overrides (they dangle and the voice panel silently
# falls back to the group binding).
_VOICE_RENAME_BLOCK = """            if (voiceInputOverrideEntryId == oldKey) voiceInputOverrideEntryId = newUuid
            if (voiceOutputOverrideEntryId == oldKey) voiceOutputOverrideEntryId = newUuid
"""
PR_BAD_NO_VOICE_RENAME = PR_OK.replace(_VOICE_RENAME_BLOCK, "")

# Regression shape N: the voice overrides migrated OUTSIDE `if (renamed)` —
# the migration would fire on every update, not just renames.
PR_BAD_VOICE_RENAME_OUTSIDE = PR_OK.replace(_VOICE_RENAME_BLOCK, "").replace(
    """            )?.let { migrated ->
                AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = migrated)
            }
        }
""",
    """            )?.let { migrated ->
                AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = migrated)
            }
        }
""" + _VOICE_RENAME_BLOCK,
)

# Regression shape H: the applier is called everywhere but its body is empty —
# a call-site-only arm must not be satisfiable that way.
_APPLIER_BODY = """        if (decideStaleRefCleanup(lastUsedEntryId, removedEntryIds)) lastUsedEntryId = null
        if (decideStaleRefCleanup(voiceInputOverrideEntryId, removedEntryIds)) {
            voiceInputOverrideEntryId = null
        }
        if (decideStaleRefCleanup(voiceOutputOverrideEntryId, removedEntryIds)) {
            voiceOutputOverrideEntryId = null
        }
"""
PR_BAD_EMPTY_APPLIER = PR_OK.replace(_APPLIER_BODY, "")

# ── wide-1024 witnesses (each mechanically reproduced on the real tree:
# pre-fix rc=0, post-fix rc=1) ────────────────────────────────────────────

_MIGRATION_BODY = """internal fun decideCompactPinMigration(pinEntryId: String, oldKey: String, newKey: String): String? =
    if (pinEntryId.isNotEmpty() && pinEntryId == oldKey && oldKey != newKey) newKey else null"""

# W1 (dataflow): the helper is still declared and still called — its body is
# now a constant, so all nine cascades are no-ops.
PR_W_BAD_HELPER_CONST = PR_OK.replace(
    _MIGRATION_BODY,
    """internal fun decideCompactPinMigration(pinEntryId: String, oldKey: String, newKey: String): String? =
    null""")

# W2 (dataflow): every anchor and every `= null` write stays put; each branch
# is simply dead.
PR_W_BAD_DEAD_GUARD = PR_OK.replace(
    "if (decideStaleRefCleanup(",
    "if (false && decideStaleRefCleanup(")

# W3 (dataflow): the declaration survives, the three consumers survive, the
# set is a constant — the cascade is told nothing was removed.
_STALE_IDS_DECL = """            val staleEntryIds = instanceEntries
                .filter { it.baseModel.isVoiceTemplateSeedShape }
                .filter { it.baseModel.id !in templateById }
                .map { it.id }
"""
PR_W_BAD_EMPTY_STALE_IDS = PR_OK.replace(
    _STALE_IDS_DECL, "            val staleEntryIds = emptyList<String>()\n")

# Regression shape I: inline lambda listener — no strong holder (exactly the
# hazard prefs_listener_holder_guard exists for) and nothing unregisters it.
_LISTENER_DECL = """        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == AgentRuntimeLimitsPrefs.KEY_COMPACT_MODEL_ENTRY_ID) {
                compactModelEntryId = AgentRuntimeLimitsPrefs.compactModelEntryId()
            }
        }
"""
UI_BAD_INLINE = UI_OK.replace(_LISTENER_DECL, "").replace(
    "pinPrefs.registerOnSharedPreferenceChangeListener(listener)",
    """pinPrefs.registerOnSharedPreferenceChangeListener(
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == AgentRuntimeLimitsPrefs.KEY_COMPACT_MODEL_ENTRY_ID) {
                    compactModelEntryId = AgentRuntimeLimitsPrefs.compactModelEntryId()
                }
            },
        )""",
).replace("        onDispose { pinPrefs.unregisterOnSharedPreferenceChangeListener(listener) }\n", "")

# Regression shape J: named, strongly-held listener that never re-reads the pin
# (registered, survives GC, and still shows the stale value).
UI_BAD_NO_REFRESH = UI_OK.replace(
    "compactModelEntryId = AgentRuntimeLimitsPrefs.compactModelEntryId()\n            }",
    "// refresh removed\n            }",
)

# Regression shape K: listener bound to the wrong prefs file.
UI_BAD_WRONG_PREFS = UI_OK.replace("            AgentRuntimeLimitsPrefs.PREFS,", '            "provider_config",')


# ── b6-t11 witnesses: each one was mechanically reproduced on the real tree
# (pre-fix rc=0 = blind, post-fix rc=1 = caught) before this arm was written ──

# A12-1 (family-hole): the condition is a CONJUNCTION with `false` — the old
# `CONST_BODY_RE` only knew the bare `if (false)` spelling, and the body still
# reads every parameter, so the helper looked like it was still deciding.
PR_W_A121_DEAD_CONJ = PR_OK.replace(
    _MIGRATION_BODY,
    """internal fun decideCompactPinMigration(pinEntryId: String, oldKey: String, newKey: String): String? =
    if (false && pinEntryId.isNotEmpty() && pinEntryId == oldKey && oldKey != newKey) newKey else null""")

# A12-1' (family-hole, `when` spelling of the same hole): a constant subject
# selects the dead branch; the enumerated literal blacklist saw neither.
PR_W_A121_WHEN_DEAD = PR_OK.replace(
    _MIGRATION_BODY,
    """internal fun decideCompactPinMigration(pinEntryId: String, oldKey: String, newKey: String): String? =
    when (false && pinEntryId.isNotEmpty()) {
        true -> if (oldKey != newKey) newKey else null
        else -> null
    }""")

# A12-2 (family-hole): `(2 == 3)` — the old `DEAD_COND_RE` enumerated the
# constant pairs `0 != 0` / `1 == 0` / `0 == 1` and nothing else.
PR_W_A122_ARITH_DEAD = PR_OK.replace(
    "        if (decideStaleRefCleanup(lastUsedEntryId, removedEntryIds)) lastUsedEntryId = null",
    "        if ((2 == 3) && decideStaleRefCleanup(lastUsedEntryId, removedEntryIds)) "
    "lastUsedEntryId = null")

# A12-3 (dataflow): the derivation chain is fully intact — `.take(0)` zeroes
# its tail, so the cascade is told "nothing was removed".
PR_W_A123_TAKE_ZERO = PR_OK.replace(
    _STALE_IDS_DECL, _STALE_IDS_DECL.rstrip("\n") + "\n                .take(0)\n")

# A12-3' (dataflow, predicate spelling of the same hole): the chain still
# derives, and the predicate drops everything.
PR_W_A123_FILTER_FALSE = PR_OK.replace(
    _STALE_IDS_DECL, _STALE_IDS_DECL.rstrip("\n") + "\n                .filter { false }\n")

# A12-4 (family-hole): both voice-override write points keep their `== oldKey`
# comparison and their `= newUuid` rewrite, and neither can ever run.
PR_W_A124_VOICE_DEAD = PR_OK.replace(
    _VOICE_RENAME_BLOCK,
    """            if (false && voiceInputOverrideEntryId == oldKey) voiceInputOverrideEntryId = newUuid
            if (false && voiceOutputOverrideEntryId == oldKey) voiceOutputOverrideEntryId = newUuid
""")

# A12-5 (family-hole): the listener call is present, its registration is not.
UI_W_A125_DEAD_REGISTER = UI_OK.replace(
    "        pinPrefs.registerOnSharedPreferenceChangeListener(listener)",
    "        if (false) pinPrefs.registerOnSharedPreferenceChangeListener(listener)")

# A12-5' (family-hole): the callback runs, the refresh inside it never does.
UI_W_A125_DEAD_REFRESH = UI_OK.replace(
    "                compactModelEntryId = AgentRuntimeLimitsPrefs.compactModelEntryId()",
    "                if (false) compactModelEntryId = AgentRuntimeLimitsPrefs.compactModelEntryId()")

# A12-6 (parser-death): the cascade is wrapped in a BLOCK comment. The gate's
# own `code_only` stripped `//` only, so every anchor inside `/* … */` counted.
PR_W_A126_BLOCK_COMMENT = PR_OK.replace(
    _REMOVE_ENTRY_PIN,
    "        /*\n" + _REMOVE_ENTRY_PIN + "        */\n")


FIXTURE_ARMS = []


def _report(name, label, checks, want_arms, contains=None, exact=True):
    FIXTURE_ARMS.append(name)
    hit = [c for c in checks
           if c[2] in want_arms and (contains is None or contains in c[3])]
    if not hit:
        print("  [FAIL] %s (%s): no finding on arm(s) %s (want text %r)"
              % (name, label, want_arms, contains))
        return False
    if exact:
        extra = sorted({c[2] for c in checks} - set(want_arms))
        if extra:
            print("  [FAIL] %s (%s): also fired unrelated arm(s) %s — the fixture is"
                  " not a single-criterion delta" % (name, label, extra))
            return False
    print("  [OK]   %s (%s): %d finding(s) on arm(s) %s" % (name, label, len(hit), want_arms))
    return True


def selftest():
    ok = True
    import shutil
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        repo_target = os.path.join(tmp, REPO_REL)
        ui_target = os.path.join(tmp, UI_REL)
        os.makedirs(os.path.dirname(repo_target), exist_ok=True)
        os.makedirs(os.path.dirname(ui_target), exist_ok=True)

        def write(repo_text, ui_text=UI_OK):
            with open(repo_target, "w", encoding="utf-8") as f:
                f.write(repo_text)
            with open(ui_target, "w", encoding="utf-8") as f:
                f.write(ui_text)

        # 1) fixed shape → all 8 arms clean (precision control).
        write(PR_OK)
        c = run_all(tmp)
        if c:
            print("  [FAIL] fixed fixture not clean: %s" % (c[0],))
            ok = False
        else:
            print("  [OK]   fixed fixture: all 9 arms clean")

        # 2) pre-fix shape → arm 1 must fire on the missing migration.
        write(PR_BAD_NO_MIGRATION)
        ok &= _report("no-migration", "arm 1", run_all(tmp), ("1",),
                      contains="decideCompactPinMigration(")

        # 3) migration placed OUTSIDE `if (renamed)` → containment violation.
        write(PR_BAD_ORDER)
        ok &= _report("wrong-order", "arm 1", run_all(tmp), ("1",),
                      contains="must sit INSIDE")

        # 4) delete paths untouched → arms 2+3 fire.
        write(PR_BAD_NO_CLEANUP)
        ok &= _report("no-cleanup", "arms 2/3", run_all(tmp), ("2", "3"))

        # 5) pure slice removed → arm 4 fires.
        write(PR_BAD_NO_HELPER)
        ok &= _report("no-helper", "arm 4", run_all(tmp), ("4",))

        # 6) replaceEntries' prune branch untouched → arm 5 fires.
        write(PR_BAD_NO_REPLACE_CLEANUP)
        ok &= _report("no-replace-cleanup", "arm 5", run_all(tmp), ("5",))

        # 7) replaceEntries cleanup outside the else → arm 5 containment fires.
        write(PR_BAD_CLEANUP_OUTSIDE_ELSE)
        ok &= _report("cleanup-outside-else", "arm 5", run_all(tmp), ("5",),
                      contains="INSIDE the `else`")

        # 8) voice-seed migration cleaned nothing but the rows → arm 6 fires.
        write(PR_BAD_NO_VOICE_CASCADE)
        ok &= _report("no-voice-cascade", "arm 6", run_all(tmp), ("6",))

        # 9) applier emptied → arm 7 fires (call sites are still in place).
        write(PR_BAD_EMPTY_APPLIER)
        ok &= _report("empty-applier", "arm 7", run_all(tmp), ("7",))

        # 10) inline listener → arm 8 fires.
        write(PR_OK, UI_BAD_INLINE)
        ok &= _report("ui-inline-listener", "arm 8", run_all(tmp), ("8",))

        # 11) listener that never re-reads the pin → arm 8 fires.
        write(PR_OK, UI_BAD_NO_REFRESH)
        ok &= _report("ui-no-refresh", "arm 8", run_all(tmp), ("8",))

        # 12) listener on the wrong prefs file → arm 8 fires.
        write(PR_OK, UI_BAD_WRONG_PREFS)
        ok &= _report("ui-wrong-prefs", "arm 8", run_all(tmp), ("8",))

        # 14) [da1031-A] arm 6 re-anchored: the bare-seed spelling (all four
        # routes a no-op) must be red, including the missing declaration.
        write(PR_BAD_BARE_SEED_IDS)
        ok &= _report("bare-seed-ids", "arm 6", run_all(tmp), ("6",),
                      contains="staleEntryIds")

        # 15) [da1031-A] updateEntry rename must migrate the voice overrides.
        write(PR_BAD_NO_VOICE_RENAME)
        ok &= _report("no-voice-rename", "arm 9", run_all(tmp), ("9",),
                      contains="voiceInputOverrideEntryId == oldKey")

        # 16) voice overrides migrated outside `if (renamed)` → containment.
        write(PR_BAD_VOICE_RENAME_OUTSIDE)
        ok &= _report("voice-rename-outside", "arm 9", run_all(tmp), ("9",),
                      contains="must sit INSIDE")

        # 18) [wide-1024 W1] helper gutted to a constant → arm 4 fires.
        write(PR_W_BAD_HELPER_CONST)
        ok &= _report("helper-constant-body", "arm 4", run_all(tmp), ("4",),
                      contains="can only return a CONSTANT")

        # 19) [wide-1024 W2] applier branches dead-guarded → arm 7 fires.
        write(PR_W_BAD_DEAD_GUARD)
        ok &= _report("applier-dead-guard", "arm 7", run_all(tmp), ("7",),
                      contains="DEAD condition")

        # 20) [wide-1024 W3] stale id set replaced by a constant → arm 6.
        write(PR_W_BAD_EMPTY_STALE_IDS)
        ok &= _report("stale-ids-constant", "arm 6", run_all(tmp), ("6",),
                      contains="`val staleEntryIds`")

        # ── b6-t11: the six witnesses this branch was handed ──────────────
        # 21) A12-1 — `false && …` inside the helper's condition.
        write(PR_W_A121_DEAD_CONJ)
        ok &= _report("a12-1-helper-dead-conjunction", "arm 4", run_all(tmp), ("4",),
                      contains="can only return a CONSTANT")

        # 22) A12-1' — the `when` spelling of the same dead decision.
        write(PR_W_A121_WHEN_DEAD)
        ok &= _report("a12-1b-helper-dead-when", "arm 4", run_all(tmp), ("4",),
                      contains="can only return a CONSTANT")

        # 23) A12-2 — `(2 == 3) && …` in the applier (arithmetic constant pair).
        write(PR_W_A122_ARITH_DEAD)
        ok &= _report("a12-2-applier-arith-dead", "arm 7", run_all(tmp), ("7",),
                      contains="DEAD condition")

        # 24) A12-3 — the derived set keeps every operator and ends in `.take(0)`.
        write(PR_W_A123_TAKE_ZERO)
        ok &= _report("a12-3-stale-ids-take-zero", "arm 6", run_all(tmp), ("6",),
                      contains="can only be EMPTY")

        # 25) A12-3' — same hole, spelled as a predicate that drops everything.
        write(PR_W_A123_FILTER_FALSE)
        ok &= _report("a12-3b-stale-ids-filter-false", "arm 6", run_all(tmp), ("6",),
                      contains="can only be EMPTY")

        # 26) A12-4 — both voice-override migrations dead-guarded.
        write(PR_W_A124_VOICE_DEAD)
        ok &= _report("a12-4-voice-dead-guard", "arm 9", run_all(tmp), ("9",),
                      contains="DEAD condition")

        # 27) A12-5 — the prefs listener is registered inside `if (false)`.
        write(PR_OK, UI_W_A125_DEAD_REGISTER)
        ok &= _report("a12-5-ui-dead-register", "arm 8", run_all(tmp), ("8",),
                      contains="DEAD condition")

        # 28) A12-5' — the callback refreshes the pin inside `if (false)`.
        write(PR_OK, UI_W_A125_DEAD_REFRESH)
        ok &= _report("a12-5b-ui-dead-refresh", "arm 8", run_all(tmp), ("8",),
                      contains="DEAD condition")

        # 29) A12-6 — the removeEntry cascade wrapped in a BLOCK comment.
        write(PR_W_A126_BLOCK_COMMENT)
        ok &= _report("a12-6-block-comment", "arm 2", run_all(tmp), ("2",))

        # 17) ROT control (LAST: it wipes the fixture tree): both files missing
        # must be reported, never silently green (one finding per file, so a
        # half-populated tree cannot pass).
        shutil.rmtree(tmp)
        os.makedirs(tmp)
        c = run_all(tmp)
        rot = [f for f in c if "not found" in f[3]]
        if len(rot) != 2:
            print("  [FAIL] ROT control: missing files produced %d finding(s) (want 2): %s"
                  % (len(rot), c))
            ok = False
        else:
            print("  [OK]   ROT control: both missing files reported")

    # A stable marker the main session can assert in test_scan.py: the count
    # makes an arm that quietly disappears visible in the CI log.
    print("%s (%d arms)" % (SELFTEST_MARK, len(FIXTURE_ARMS)))
    return 0 if ok else 1


def main():
    args = sys.argv[1:]
    if "--selftest" in args:
        return selftest()
    root = None
    if "--root" in args:
        i = args.index("--root")
        root = args[i + 1] if i + 1 < len(args) else None
    else:
        for a in args:
            if not a.startswith("-"):
                root = a
                break
    if not root:
        print("usage: compact_entry_pin_migration_check.py [ROOT] | --selftest", file=sys.stderr)
        return 2
    if not os.path.isdir(root):
        print("root not found: %s" % root, file=sys.stderr)
        return 2
    checks = run_all(root)
    if not checks:
        print("compact_entry_pin_migration_check: OK (9 arms green)")
        return 0
    for rel, line, arm, msg in checks:
        print("%s:%s [arm %s] %s" % (rel, line, arm, msg))
    print("compact_entry_pin_migration_check: %d violation(s)" % len(checks))
    return 1


if __name__ == "__main__":
    sys.exit(main())
