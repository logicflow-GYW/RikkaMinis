#!/usr/bin/env python3
"""session_sheet_id_binding_check.py — deep-audit-1022 FIX-B (ISSUE-02).

Guards that ChatScreen's session-scoped subpanels receive the ACTIVE session
id (viewModel.currentSessionId — real id after draft promotion, __new__ route
arg before), not the bare `sessionId` route arg which stays on `__new__…`
after the first send and orphans every write against it.

Judged by CODE anchors (comments stripped first — never anchor on comments):

  ChatScreen.kt
    * SessionSkillsSheet(    block: `sessionId = viewModel.currentSessionId` present,
                                   bare `sessionId = sessionId` forbidden
    * SessionMcpsSheet(      block: same
    * WebPreviewBottomSheet( block: `pinSessionId = viewModel.currentSessionId` present,
                                   bare `pinSessionId = sessionId` forbidden
    * ChatHistoryDrawer(     block: `currentSessionId = viewModel.currentSessionId` present,
                                   bare `currentSessionId = sessionId` forbidden
    * `LocalMarkdownSessionId provides viewModel.currentSessionId` present;
      bare `provides sessionId` forbidden

  SessionSkillsSheet.kt
    * the seed must MERGE (mirrors SessionMcpsSheet):
      `remember(sessionId)` (fresh map per session id) present +
      `LaunchedEffect(skills, sessionId)` present (re-runs on id change);
      the rebuild shape `overrides = remember(skills…` (whole map re-derived
      from the live list — one SQLite read per skill, per emission, inside
      composition) and the keyless `overrides = remember {` are both forbidden

  [da1034-b3] Until this revision the SessionSkillsSheet criterion was the
  REBUILD shape (`remember(skills, sessionId) { … isEnabledForSession … }`) —
  the gate itself pinned the defect in place and made the two sheets
  contradictory (the Mcps criterion already demanded the incremental merge).
  Both sheets are now judged by the same incremental-merge shape.

  SessionMcpsSheet.kt
    * `remember(sessionId)` (fresh map per session id) present;
      keyless `overrides = remember {` forbidden
    * `LaunchedEffect(servers, sessionId)` present (seeding effect re-runs on
      id change)

  [da1045-B6] BOTH sheets — the seeding write must re-check membership at the
  write, not earlier. `deep-audit-1045` B-06: SessionMcpsSheet computed
  `missing` on Main, awaited one `isEnabledForSession` SQLite read per missing
  id on IO, then wrote every result back with a bare `for (id in missing)
  overrides[id] = …`. `missing` is a snapshot taken BEFORE the await while
  `overrides` is a live map, so a toggle the user flipped inside that window
  was overwritten with the pre-toggle stored value. SessionSkillsSheet
  delegates to `mergeSessionOverrides`, whose first line IS the guard
  (`if (id !in overrides)`) — the two files carried the same contract in their
  comments and only one of them in its code.

  Judged per `LaunchedEffect(<keys with sessionId>)` body of the map derived
  from `… = remember(sessionId)`:
    * a write `<map>[…] =` must be attached to an `if ( … )` whose condition
      re-tests membership in THAT map — any of `x !in map`,
      `!map.containsKey(…)`, `map[…] == null` (spelling-tolerant, the decision
      point is "is it still missing when the write runs");
    * OR the body calls a `merge*Overrides(…)` family helper that receives that
      map (family matched, not a pinned name — renaming the helper keeps the
      guard alive, deleting the call flips red);
    * neither writes nor helper in the body is red too (fail closed: the
      seeding statement was not moved, it was removed).
  `if (id in missing)` — guarding on the pre-await snapshot — does NOT count:
  it is the defect, rephrased.

  [da1032-A] ISSUE-02 — `SessionEditSheet` is host-agnostic: its Regenerate
  button and its live row are both defaulted parameters, so a host that omits
  them still compiles and the button silently no-ops. Two more blocks:

  ChatScreen.kt
    * SessionEditSheet(  block: `onRegenerate =` present AND `liveSession =`
                                present (never rely on the defaults)

  SessionsShared.kt (inside the `internal fun SessionEditSheet(` body only)
    * `var title by remember(<keys containing session.id>)` present,
      keyless `var title by remember {` forbidden
    * `var selectedCategory by remember(<keys containing session.id>)` present,
      keyless `var selectedCategory by remember {` forbidden
    * `LaunchedEffect(<keys containing liveSession.id>)` present (the seeding
      effect must re-run when the sheet is re-pointed at another row)

Other bare `sessionId = sessionId` sites in ChatScreen (routing params of
unrelated UI children) are OUT OF SCOPE — the guard pins to the named call
blocks only, never the whole file.

Usage:
    python3 session_sheet_id_binding_check.py <repo_root>
    python3 session_sheet_id_binding_check.py --root <repo_root>
    python3 session_sheet_id_binding_check.py --selftest
"""

import os
import re
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_lexer import KotlinLexError
from _kotlin_ast import (
    const_bool,
    const_value,
    enclosing_conditions,
    function_body,
    match_bracket,
    split_top,
    strip_parens,
)
from _kotlin_valueflow import (
    call_args,
    code_view,
    expr_end,
    scope_calls,
    value_sources,
)

SELFTEST_MARKER = "SESSION_SHEET_ID_BINDING_SELFTEST=PASS"

# ---------------------------------------------------------------------------
# Tree layout

CHAT_DIR = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat"
CHATSCREEN = os.path.join(CHAT_DIR, "ChatScreen.kt")
SKILLS_SHEET = os.path.join(CHAT_DIR, "SessionSkillsSheet.kt")
MCPS_SHEET = os.path.join(CHAT_DIR, "SessionMcpsSheet.kt")
SESSIONS_DIR = "src/android/app/src/main/java/com/rikkaminis/app/ui/sessions"
SESSIONS_SHARED = os.path.join(SESSIONS_DIR, "SessionsShared.kt")

# ---------------------------------------------------------------------------
# [batch7] Structure helpers — shared primitives only
#
# The old revision carried a hand-written string-aware comment stripper, a
# hand-rolled balanced-paren scanner and a backward `if (…)` condition matcher
# here. All three are deleted: structure analysis goes through `code_view()`
# (comments AND string/char literals masked to spaces, length preserved, so a
# position maps straight back and a `"/*"` inside a string cannot swallow
# code), `match_bracket`, `split_top` and `enclosing_conditions`.
#
# Fail-closed: a file the lexer refuses is reported by check(), never scanned
# as raw text.

def _call_block(code, call_name):
    """(block_text, open_paren_offset) of the first `callName(` call, or None.

    A call whose parentheses do not close is a call this gate cannot judge;
    the caller reports it instead of guessing a region.
    """
    m = re.search(r"\b" + re.escape(call_name) + r"\s*\(", code)
    if not m:
        return None, -1
    open_paren = m.end() - 1
    close = match_bracket(code, open_paren)
    if close < 0:
        return None, -1
    return code[open_paren:close + 1], open_paren


def _call_args(code, open_paren):
    """[(name_or_None, value_text)] for the call whose `(` is at open_paren.

    Argument splitting is the shared `split_top` (bracket aware), so a nested
    call or lambda inside an argument cannot truncate it, and named arguments
    are matched on `=` (never `==`).
    """
    out = []
    for start, end in call_args(code, open_paren):
        arg = code[start:end].strip()
        if not arg:
            continue
        m = re.match(r"^([A-Za-z_]\w*)\s*=(?!=)\s*(.*)$", arg, re.S)
        out.append((m.group(1), m.group(2).strip()) if m else (None, arg))
    return out


def _effect_blocks_handoff(body, call_re):
    """Yield (call_start, args) for every `call_re` match inside `body`."""
    for m in re.finditer(call_re, body):
        open_paren = m.end() - 1
        args = _call_args(body, open_paren)
        if args:
            yield m.start(), args


def _fun_body(code, name):
    """Body slice of the (block-bodied) `fun name(`, or None (fail-closed)."""
    span = function_body(code, name)
    return code[span[0]:span[1]] if span else None


def _pretty(expr):
    """One-line rendering for findings (the view masks literal CONTENT)."""
    return " ".join(expr.split())


# ---------------------------------------------------------------------------
# Value judgements ([batch7] dataflow / spelling)
#
# Both families of witness that walked through this gate shared one shape: the
# TOKEN the criterion greps for survives, the VALUE it contributes does not
# (`sessionId = viewModel.currentSessionId.let { "" }`,
#  `isEnabledForSession(it, sessionId.let { "__new__" })`,
#  `skills.map { it.id }.take(0)`, `liveSession.title?.let { title = "" }`,
#  `selectedCategory = liveSession.category?.takeIf { false }`).
# The judgement therefore runs on `value_sources()` — "what does this position
# actually evaluate to" — not on the presence of a name.

def _snorm(expr):
    return re.sub(r"\s+", "", strip_parens(expr))


def _sources(expr):
    """`value_sources` + takeIf/takeUnless feasibility.

    `x?.takeIf { false }` evaluates to null, not to `x`, but `value_sources`
    reports the receiver (that is what takeIf returns when it matches), so the
    predicate is folded here. Without this fold "the value still mentions the
    source" is exactly the sentence an attacker writes.
    """
    for name, _start, open_pos, end in scope_calls(expr):
        if name in ("takeIf", "takeUnless"):
            flag = const_bool(expr[open_pos + 1:end - 1])
            if (name == "takeIf" and flag is False) or (name == "takeUnless" and flag is True):
                return ["null"]
    return value_sources(expr)


def _strip_marks(expr):
    """Drop the null-assertion / safe-call marks that do not change the value.

    `value_sources` reports the RECEIVER of `x?.takeIf { … }` including the
    safe-call mark (`liveSession.category?`), which is the same value as
    `liveSession.category` when the predicate can hold.
    """
    e = _snorm(expr)
    while e.endswith(("?", "!")):
        e = e[:-1]
    return e


def _is_value(expr, target):
    return _strip_marks(expr) == _strip_marks(target)


def _derives_only_from(value, target):
    """Every possible value of `value` IS `target` — no literal, no rewrite."""
    srcs = _sources(value)
    if not srcs:
        return False
    return all(_is_value(s, target) for s in srcs)


def _derives_from(value, target):
    """At least one path of `value` IS `target`, and no path is a literal.

    The looser shape used for state mirrors: `liveSession.title ?: title` is a
    legal mirror (the fallback is the previous state), while `""`,
    `let { "" }` and `takeIf { false }` all replace the value outright.
    """
    hit = False
    for s in _sources(value):
        if _is_value(s, target):
            hit = True
            continue
        if const_value(strip_parens(s)) is not None:
            return False
    return hit


def _assignments(text, state):
    """[(pos, rhs_text)] of every `state = <expr>` in text."""
    out = []
    for m in re.finditer(r"(?<![\w.`])" + re.escape(state) + r"\s*=(?!=)", text):
        start = m.end()
        out.append((m.start(), text[start:expr_end(text, start)].strip()))
    return out


def _provably_empty(expr):
    """True when the expression is a collection that is EMPTY at runtime.

    The wide-1024 arm knew `emptyList()`; `.take(0)` (batch7) is the same
    decision spelled as a pipeline, and `filter { false }` / `drop(MAX_VALUE)`
    are the same family.
    """
    e = re.sub(r"\s+", "", expr)
    if not e:
        return False
    if EMPTY_LITERAL_RE.match(e):
        return True
    for m in re.finditer(r"\.(?:take|takeLast)\(\s*(-?\d+)\s*\)", e):
        if int(m.group(1)) <= 0:
            return True
    if re.search(r"\.(?:drop|dropLast)\(\s*Int\.MAX_VALUE\s*\)", e):
        return True
    for m in re.finditer(r"\.(filter|filterNot)\s*\{([^{}]*)\}", e):
        flag = const_bool(m.group(2))
        if (m.group(1) == "filter" and flag is False) or \
                (m.group(1) == "filterNot" and flag is True):
            return True
    return False


# ---------------------------------------------------------------------------
# [da1045-B6] Seeding-guard machinery

# The merge helper family. Matched by shape, never by a pinned name, so
# `mergeSessionOverrides` → `mergeMcpOverrides` → `applySeeds` (kept in the
# family) all stay judged; a helper that stops being a merge is what should
# flip the gate red.
MERGE_FAMILY_RE = r"\bmerge[A-Za-z0-9_]*Overrides?\s*\("

# [batch7] Every spelling of "write into the live map". The old revision only
# knew `map[key] = value`, so `map.put(k, v)` — the same write, since
# `SnapshotStateMap` implements MutableMap — counted as no write at all and an
# unguarded seeding overwrite walked straight through.
WRITE_SPELLINGS = (
    (r"(?<![\w.]){}\s*\[[^\]]*\]\s*=(?!=)", "map[key] = value"),
    (r"(?<![\w.]){}\s*\.\s*(?:put|putAll|set|getOrPut|compute|computeIfAbsent|merge)\s*\(",
     "map.put(...)"),
    (r"(?<![\w.]){}\s*(?:\+=|-=)", "map += ..."),
)

# The three spellings of "this id is still missing". What is pinned is the
# DECISION (membership re-tested against THAT map at the write), not the words.
GUARD_TMPLS = [
    r"!\s*in\s+{}\b",
    r"!\s*{}\s*\.\s*containsKey\s*\(",
    r"\b{}\s*\[[^\]]*\]\s*==\s*null",
]


def _write_sites(body, map_var):
    """[(pos, spelling)] of every write into `map_var`, all spellings."""
    out = []
    for tmpl, spelling in WRITE_SPELLINGS:
        for m in re.finditer(tmpl.format(re.escape(map_var)), body):
            out.append((m.start(), spelling))
    return sorted(out)


def _seeding_effect_bodies(code):
    """[(offset, body)] of every `LaunchedEffect(<keys with sessionId>) { … }`.

    Scoping to the effect body is what keeps the row toggle — which legitimately
    writes `overrides[server.id] = enabled` unguarded inside `onCheckedChange` —
    out of the judgement.
    """
    out = []
    for m in re.finditer(r"\bLaunchedEffect\s*\(", code):
        open_paren = m.end() - 1
        close_paren = match_bracket(code, open_paren)
        if close_paren < 0:
            continue
        if not re.search(r"\bsessionId\b", code[open_paren:close_paren]):
            continue
        open_brace = code.find("{", close_paren)
        if open_brace < 0:
            continue
        close_brace = match_bracket(code, open_brace)
        if close_brace < 0:
            continue
        out.append((open_brace, code[open_brace:close_brace + 1]))
    return out


def _write_is_guarded(code, idx, map_var):
    """True when a condition ENCLOSING the write re-tests membership in it.

    `enclosing_conditions` covers braced and braceless bodies, nested `if`s and
    inverted `else` branches alike, so the guard no longer has to be spelled
    `if (…) <write>` on one line.
    """
    guards = [re.compile(t.format(re.escape(map_var))) for t in GUARD_TMPLS]
    for _kind, cond in enclosing_conditions(code, idx):
        if any(g.search(cond) for g in guards):
            return True
    return False


def _judge_merge_live_ids(body, value, label, findings):
    """[wide-1024, hardened in batch7] the merge must receive the LIVE id list.

    Anchored on the value, not on the call. `liveIds = emptyList()` (wide-1024)
    and `val liveIds = skills.map { it.id }.take(0)` (batch7) are the same
    defect one hop apart: the identifier is still handed to the helper, the
    list it names is empty, every stored seed is dropped and every toggle in
    the sheet renders off.
    """
    norm = re.sub(r"\s+", "", value)
    if _provably_empty(norm):
        findings.append(
            f"{label}: the merge helper is handed `liveIds = {_pretty(value)}` — an empty id "
            f"list walks nothing, so the whole per-session seed set is discarded and "
            f"every toggle in the sheet renders off (the call looks right, the "
            f"argument is the wrong value)"
        )
        return
    if not re.fullmatch(r"[A-Za-z_]\w*", norm):
        return  # an inline expression derived from the live list
    decl = re.search(r"\b(?:val|var)\s+" + re.escape(norm) + r"\s*=(?!=)", body)
    if not decl:
        findings.append(
            f"{label}: the merge helper is handed `liveIds = {value}`, a value this "
            f"effect never computes — the id list has to come from the live list"
        )
        return
    rhs = body[decl.end():expr_end(body, decl.end())].strip()
    if _provably_empty(rhs):
        findings.append(
            f"{label}: `{norm}` is computed from the live list and then emptied "
            f"(`{norm} = {_pretty(rhs)}`) — the merge helper walks an empty id list, so "
            f"no session override is ever read back and every toggle renders off"
        )
        return
    if len(re.findall(r"\b" + re.escape(norm) + r"\b", body)) < 2:
        findings.append(
            f"{label}: `{value}` is computed inside the effect and then discarded — "
            f"it never feeds the seed read, so the merge walks ids that were never "
            f"queried"
        )


def _judge_seed_read_args(body, label, findings):
    """[wide-1024, hardened in batch7] the per-session read must USE that id.

    Spelling is not the judgement (`\bsessionId\b` in the argument text is what
    let `sessionId.let { "__new__" }` through): the argument must resolve to
    exactly the sheet's `sessionId`, or every lookup lands on the orphan
    `__new__` row.
    """
    for _, args in _effect_blocks_handoff(body, r"\bisEnabledForSession\s*\("):
        value = args[-1][1]
        if not _derives_only_from(value, SESSION_ID_TOKEN):
            findings.append(
                f"{label}: the seeding read calls `isEnabledForSession(…, {_pretty(value)})` "
                f"instead of the sheet's own `{SESSION_ID_TOKEN}` — every lookup lands on the "
                f"orphan `__new__` row, so the real session's toggles never come back"
            )


def check_seeding_guard(code, label, findings):
    """[da1045-B6] Shared by BOTH sheets: seed writes must survive a re-check.

    Skipped (not failed) when no sessionId-keyed map / effect exists — those
    absences are already reported by the sibling criteria above, and re-reporting
    them would inflate their precision counts.
    """
    map_vars = re.findall(
        r"\b([A-Za-z_][A-Za-z0-9_]*)\s*=\s*remember\s*\(\s*sessionId\s*\)", code)
    if not map_vars:
        return
    bodies = _seeding_effect_bodies(code)
    if not bodies:
        return
    for map_var in map_vars:
        for offset, body in bodies:
            writes = _write_sites(body, map_var)
            helper = re.search(MERGE_FAMILY_RE, body)
            helper_ok = False
            if helper:
                # the family call must hand ITSELF the map it is seeding
                call_open = offset + helper.end() - 1
                call_close = match_bracket(code, call_open)
                helper_ok = (
                    call_close > call_open
                    and bool(re.search(r"\b" + re.escape(map_var) + r"\b",
                                       code[call_open:call_close + 1]))
                )
                # ... and the live id list it walks must be the real one
                args = _call_args(code, call_open)
                if args:
                    named = dict((n, v) for n, v in args if n)
                    live_ids = named.get("liveIds", args[0][1] if args[0][0] is None else None)
                    if live_ids is not None:
                        _judge_merge_live_ids(body, live_ids, label, findings)
            _judge_seed_read_args(body, label, findings)
            if not writes and not helper_ok:
                findings.append(
                    f"{label}: the seeding effect no longer writes `{map_var}` and does "
                    f"not call a merge*Overrides(…) helper that receives it — the per-session "
                    f"seed either vanished or moved somewhere this guard cannot see"
                )
                continue
            for w_pos, spelling in writes:
                idx = offset + w_pos
                if _write_is_guarded(code, idx, map_var):
                    continue
                line = code.count("\n", 0, idx) + 1
                findings.append(
                    f"{label}:{line}: unguarded seeding write `{spelling.format(map_var)}` inside the "
                    f"LaunchedEffect — the missing-id set is a snapshot taken before the IO "
                    f"await, so this overwrites an in-flight toggle. Guard it with "
                    f"`if (id !in {map_var})` at the write, or seed through a "
                    f"merge*Overrides(…) helper that receives {map_var}"
                )
    return findings


# ---------------------------------------------------------------------------
# Anchor specs

# [batch7] The call-site criterion is on the VALUE the panel receives, not on
# the spelling of the argument: `sessionId = viewModel.currentSessionId` is
# required, and `sessionId = viewModel.currentSessionId.let { "" }` — same
# prefix, same token, empty session id at runtime — is the same orphan-row
# defect the gate exists to prevent.
# (call_name, active-id argument, required value, forbidden bare pass, label)
BLOCK_SPECS = [
    ("SessionSkillsSheet", "sessionId", "viewModel.currentSessionId",
     r"sessionId\s*=\s*sessionId\b", "skills sheet"),
    ("SessionMcpsSheet", "sessionId", "viewModel.currentSessionId",
     r"sessionId\s*=\s*sessionId\b", "mcps sheet"),
    ("WebPreviewBottomSheet", "pinSessionId", "viewModel.currentSessionId",
     r"pinSessionId\s*=\s*sessionId\b", "web preview pin"),
    ("ChatHistoryDrawer", "currentSessionId", "viewModel.currentSessionId",
     r"currentSessionId\s*=\s*sessionId\b", "history drawer"),
]

ACTIVE_ID = "viewModel.currentSessionId"

# [da1032-A] ISSUE-02: `SessionEditSheet` is host-agnostic — `onRegenerate`
# defaults to `{}` and `liveSession` defaults to the (stale) `session`
# snapshot, so a host that omits THEM still compiles and the button silently
# no-ops. [batch7] …and so does a host that spells the argument out and passes
# the default anyway (`onRegenerate = {}`): the criterion is now the value.
EDIT_SHEET_CALL = "SessionEditSheet"
EDIT_SHEET_SPECS = [
    (EDIT_SHEET_CALL, ["onRegenerate", "liveSession"], "session edit sheet"),
]

EDIT_SHEET_FUN_SIG = "internal fun SessionEditSheet"

# Keyed-remember key lists: the id must appear SOMEWHERE among the keys (the
# decision point is "re-seeds when the sheet is re-pointed"), so extra keys
# stay legal.
TITLE_KEYED_RE = r"\bvar\s+title\s+by\s+remember\s*\([^)]*\bsession\.id\b"
TITLE_KEYLESS_RE = r"\bvar\s+title\s+by\s+remember\s*\{"
CATEGORY_KEYED_RE = r"\bvar\s+selectedCategory\s+by\s+remember\s*\([^)]*\bsession\.id\b"
CATEGORY_KEYLESS_RE = r"\bvar\s+selectedCategory\s+by\s+remember\s*\{"
LIVE_SEED_EFFECT_RE = r"LaunchedEffect\s*\([^)]*\bliveSession\.id\b"

# wide-1024 (dataflow axis): the sheet name it sits in is the id every
# per-session read must use. The gate already pins this token at the effect
# keys / the call sites, so the seed READ is judged against the same name.
SESSION_ID_TOKEN = "sessionId"

EMPTY_LITERAL_RE = re.compile(
    r"^(?:emptyList|emptySet|emptySequence|listOf|setOf|mutableListOf|"
    r"mutableSetOf|arrayListOf|sequenceOf)\s*(?:<[^>]*>)?\s*\(\s*\)$"
)


def _mirrors_state(block, state, source):
    """True when the effect body feeds `source` into the local `state` state.

    [batch7] Anchoring the DECISION ("the fresh value reaches the state") is not
    enough — the value has to BE the fresh value. The old spelling-based
    branches accepted `liveSession.title?.let { title = "" }` (the mirror shape
    with a constant on the right) and `selectedCategory =
    liveSession.category?.takeIf { false }` (the assignment shape whose value is
    always null), so the field was blanked on every regeneration instead of
    mirrored.
    """
    for _pos, rhs in _assignments(block, state):
        if _derives_from(rhs, source):
            return True
    # scope-function spellings: the receiver must be the source, and the lambda
    # must feed `it` (not a constant) into the state
    pattern = (re.escape(source) + r"\s*\??\s*\.\s*(?:let|also|run|apply|takeIf)\s*\{")
    for m in re.finditer(pattern, block):
        open_brace = m.end() - 1
        close = match_bracket(block, open_brace)
        if close < 0:
            continue
        inner = block[open_brace:close + 1]
        for _pos, rhs in _assignments(inner, state):
            if _derives_from(rhs, "it") or _derives_from(rhs, source):
                return True
    return False


def _live_seed_effect_block(body):
    """Body of the first `LaunchedEffect(<keys with liveSession.id>) { … }`."""
    for m in re.finditer(r"\bLaunchedEffect\s*\(", body):
        close_paren = match_bracket(body, m.end() - 1)
        if close_paren < 0:
            continue
        if not re.search(r"\bliveSession\.id\b", body[m.end():close_paren]):
            continue
        open_brace = body.find("{", close_paren)
        if open_brace < 0:
            continue
        close_brace = match_bracket(body, open_brace)
        if close_brace < 0:
            return None  # unbalanced effect body -> caller fails closed
        return body[open_brace:close_brace + 1]
    return None


def _empty_callback_reason(value):
    """Why a callback argument equals the parameter default (a no-op), or None.

    `{}` is the declaration's own default (`onRegenerate: () -> Unit = {}`), so
    spelling the argument out and passing it changes nothing at runtime — the
    button stays dead. Anything that calls something is accepted; a bare
    reference to a real callback is accepted too.
    """
    v = value.strip()
    if not v.startswith("{"):
        return None
    close = match_bracket(v, 0)
    body = v[1:close if close > 0 else len(v)]
    if re.search(r"[A-Za-z_]\w*\s*\(", body):
        return None
    return "the lambda body never calls anything, so it IS the parameter's default no-op"


def check_chatscreen(code, findings):
    for call_name, arg_name, required, forbidden, label in BLOCK_SPECS:
        block, open_paren = _call_block(code, call_name)
        if block is None:
            findings.append(f"ChatScreen.kt: call site `{call_name}(` not found")
            continue
        args = dict((n, v) for n, v in _call_args(code, open_paren) if n)
        value = args.get(arg_name)
        if value is None:
            findings.append(
                f"ChatScreen.kt: `{call_name}(` ({label}) must pass the ACTIVE session id "
                f"(expected `{arg_name} = {required}`, found argument)"
            )
        elif not _derives_only_from(value, required):
            findings.append(
                f"ChatScreen.kt: `{call_name}(` ({label}) passes "
                f"`{arg_name} = {_pretty(value)}` — the panel has to receive `{required}` "
                f"ITSELF, not a value derived from it: a rewrite (`.let {{ … }}`, `?: …`, "
                f"`takeIf {{ false }}`) keeps the spelling and orphans every write against an "
                f"empty / stale session id"
            )
        if re.search(forbidden, block):
            findings.append(
                f"ChatScreen.kt: `{call_name}(` ({label}) still binds the bare route arg "
                f"`{forbidden}` — orphan key after draft promotion"
            )
    # LocalMarkdownSessionId composition local
    m = re.search(r"LocalMarkdownSessionId\s+provides\s+([^\n,]+)", code)
    if m is None:
        findings.append("ChatScreen.kt: `LocalMarkdownSessionId provides` not found")
    else:
        provided = m.group(1).strip()
        if re.fullmatch(r"sessionId", provided):
            findings.append(
                "ChatScreen.kt: LocalMarkdownSessionId binds the bare route arg `sessionId` — "
                "markdown media lookups resolve against the stale __new__ session dir"
            )
        elif not _derives_only_from(provided, ACTIVE_ID):
            findings.append(
                f"ChatScreen.kt: LocalMarkdownSessionId provides `{_pretty(provided)}` — expected "
                f"{ACTIVE_ID} itself; a rewrite re-orphans markdown media lookups"
            )
    # [da1032-A] SessionEditSheet call block
    for call_name, required_args, label in EDIT_SHEET_SPECS:
        block, open_paren = _call_block(code, call_name)
        if block is None:
            findings.append(f"ChatScreen.kt: call site `{call_name}(` not found")
            continue
        args = dict((n, v) for n, v in _call_args(code, open_paren) if n)
        for arg in required_args:
            if arg not in args:
                findings.append(
                    f"ChatScreen.kt: `{call_name}(` ({label}) must pass `{arg}` explicitly — "
                    f"it is a defaulted parameter, so omitting it compiles clean and silently "
                    f"disables the control it feeds"
                )
        value = args.get("onRegenerate")
        if value is not None:
            reason = _empty_callback_reason(value)
            if reason:
                findings.append(
                    f"ChatScreen.kt: `{call_name}(` ({label}) passes "
                    f"`onRegenerate = {_pretty(value)}` — {reason}; the Regenerate button "
                    f"fires the parameter's own default and silently does nothing"
                )
        live = args.get("liveSession")
        if live is not None and _snorm(live) == "session":
            findings.append(
                f"ChatScreen.kt: `{call_name}(` ({label}) passes `liveSession = session` — that "
                f"IS the parameter default (the stale snapshot), so a regenerated title never "
                f"reaches the field"
            )
    return findings


def check_sessions_shared(code, findings):
    """[da1032-A] The edit-sheet seed state must be keyed on the row id.

    Scoped to the `internal fun SessionEditSheet` body: other composables in
    this file legitimately hold keyless `var … by remember` state and must not
    be judged.
    """
    body = _fun_body(code, "SessionEditSheet")
    if body is None:
        findings.append(
            "SessionsShared.kt: `internal fun SessionEditSheet(` body not found — "
            "cannot verify the edit-state keys"
        )
        return findings
    if not re.search(TITLE_KEYED_RE, body):
        findings.append(
            "SessionsShared.kt: `title` must be seeded by "
            "`var title by remember(<keys with session.id>)` — otherwise the field keeps "
            "the previous session's text when the sheet is re-pointed"
        )
    if re.search(TITLE_KEYLESS_RE, body):
        findings.append("SessionsShared.kt: keyless `var title by remember {` still present")
    if not re.search(CATEGORY_KEYED_RE, body):
        findings.append(
            "SessionsShared.kt: `selectedCategory` must be seeded by "
            "`var selectedCategory by remember(<keys with session.id>)`"
        )
    if re.search(CATEGORY_KEYLESS_RE, body):
        findings.append(
            "SessionsShared.kt: keyless `var selectedCategory by remember {` still present"
        )
    if not re.search(LIVE_SEED_EFFECT_RE, body):
        findings.append(
            "SessionsShared.kt: the live-row seeding effect must key on `liveSession.id` "
            "(expected `LaunchedEffect(liveSession.id, …)`) — otherwise a re-pointed sheet "
            "never re-seeds from the new row"
        )
    else:
        # wide-1024 (dataflow): correct keys are only half the contract — the
        # effect has to actually mirror the fresh row into the edit state.
        effect = _live_seed_effect_block(body)
        if effect is None:
            findings.append(
                "SessionsShared.kt: the liveSession-keyed seeding effect body could not be "
                "parsed (fail-closed) — re-point this guard"
            )
        else:
            for state, source in (("title", "liveSession.title"),
                                  ("selectedCategory", "liveSession.category")):
                if not _mirrors_state(effect, state, source):
                    findings.append(
                        f"SessionsShared.kt: the liveSession-keyed effect no longer mirrors "
                        f"`{source}` into `{state}` — the effect fires on regeneration but the "
                        f"field keeps the text it was opened with, and Save writes the stale "
                        f"value back over the freshly generated one"
                    )
    return findings


def check_skills_sheet(code, findings):
    """[da1034-b3] Same incremental-merge criterion as [check_mcps_sheet].

    The previous criterion required the REBUILD shape
    (`overrides = remember(skills, sessionId) { … }`), so this gate held the
    defect in place and directly contradicted the Mcps criterion below: the
    rebuild re-derived the whole map from the live list on every emission —
    one `isEnabledForSession` SQLite read per skill, inside composition — and
    threw away any toggle the user had already flipped in the open sheet. The
    two sheets are checked by the same shape now.
    """
    if not re.search(r"overrides\s*=\s*remember\s*\(\s*sessionId\s*\)", code):
        findings.append(
            "SessionSkillsSheet.kt: overrides map must be keyed on sessionId "
            "(expected `overrides = remember(sessionId)`) — a map rebuilt from the live skills "
            "list keeps the pre-promotion __new__ seeds and re-runs the seed per emission"
        )
    if re.search(r"overrides\s*=\s*remember\s*\(\s*skills", code):
        findings.append(
            "SessionSkillsSheet.kt: `overrides = remember(skills…)` full rebuild still present — "
            "every skills StateFlow emission re-derives the map and runs one isEnabledForSession "
            "SQLite read per skill inside composition; seed by merging into a sessionId-keyed map "
            "instead (mirrors SessionMcpsSheet)"
        )
    if re.search(r"overrides\s*=\s*remember\s*\{", code):
        findings.append("SessionSkillsSheet.kt: keyless `overrides = remember {` still present")
    if not re.search(r"LaunchedEffect\s*\(\s*skills\s*,\s*sessionId\s*\)", code):
        findings.append(
            "SessionSkillsSheet.kt: seeding effect must re-run on sessionId change "
            "(expected `LaunchedEffect(skills, sessionId)`) — otherwise a promoted draft keeps "
            "the __new__ seeds and never re-derives from the real id"
        )
    # [da1045-B6] the same membership-guard criterion the Mcps sheet is now held
    # to — the two files claim one contract, so one gate judges both.
    check_seeding_guard(code, "SessionSkillsSheet.kt", findings)
    return findings


def check_mcps_sheet(code, findings):
    if not re.search(r"overrides\s*=\s*remember\s*\(\s*sessionId\s*\)", code):
        findings.append(
            "SessionMcpsSheet.kt: overrides map must be keyed on sessionId "
            "(expected `remember(sessionId)`) — keyless map keeps __new__ seeds and "
            "the merge guard skips re-seeding same-id servers"
        )
    if re.search(r"overrides\s*=\s*remember\s*\{", code):
        findings.append("SessionMcpsSheet.kt: keyless `overrides = remember {` still present")
    if not re.search(r"LaunchedEffect\s*\(\s*servers\s*,\s*sessionId\s*\)", code):
        findings.append(
            "SessionMcpsSheet.kt: seeding effect must re-run on sessionId change "
            "(expected `LaunchedEffect(servers, sessionId)`)"
        )
    # [da1045-B6] B-06: `missing` was captured before the IO await and written
    # back unguarded, so an in-flight toggle was overwritten by its own
    # pre-toggle stored value.
    check_seeding_guard(code, "SessionMcpsSheet.kt", findings)
    return findings


def check(root):
    findings = []
    paths = {
        CHATSCREEN: check_chatscreen,
        SKILLS_SHEET: check_skills_sheet,
        MCPS_SHEET: check_mcps_sheet,
        SESSIONS_SHARED: check_sessions_shared,
    }
    for rel, fn in paths.items():
        p = os.path.join(root, rel)
        if not os.path.isfile(p):
            findings.append(f"missing file: {rel}")
            continue
        with open(p, encoding="utf-8", errors="replace") as f:
            src = f.read()
        # [batch7] Fail-closed lexing: the shared lexer masks comments AND
        # string/char literals (length preserved), so an unterminated literal is
        # reported instead of being scanned as if it were code.
        try:
            code = code_view(src)
        except KotlinLexError as exc:
            findings.append(f"{rel}: cannot be lexed (fail-closed): {exc}")
            continue
        fn(code, findings)
    return findings


# ---------------------------------------------------------------------------
# Selftest fixtures

FIX_CHATSCEEN_OK = """\
fun ChatScreen() {
    LocalMarkdownSessionId provides viewModel.currentSessionId,
    val historyDrawerState = rememberDrawerState()
    ModalNavigationDrawer(drawerContent = {
        ChatHistoryDrawer(
            chatRepository = chatRepository,
            currentSessionId = viewModel.currentSessionId,
            draft = draftSnapshot,
        )
    })
    if (showSkillsSheet && skillRepository != null) {
        SessionSkillsSheet(
            skillRepository = skillRepository,
            sessionId = viewModel.currentSessionId,
            onDismiss = { showSkillsSheet = false },
        )
    }
    if (showMcpsSheet && mcpRepository != null) {
        SessionMcpsSheet(
            mcpRepository = mcpRepository,
            sessionId = viewModel.currentSessionId,
            onDismiss = { showMcpsSheet = false },
        )
    }
    WebPreviewBottomSheet(
        holder = holder,
        pinSessionId = viewModel.currentSessionId,
        onDismiss = onFullDismiss,
    )
    // [da1032-A] the edit sheet host block: both defaulted args passed
    // explicitly, and deliberately NOT in declaration order (the guard is on
    // the decision point, not the argument order).
    editingSession?.let { session ->
        com.rikkaminis.app.ui.sessions.SessionEditSheet(
            session = session,
            liveSession = liveSessions.firstOrNull { it.id == session.id } ?: session,
            onDismiss = { editingSession = null },
            onSave = { t, c -> viewModel.updateTitleAndCategory(t, c) },
            onRegenerate = { viewModel.regenerateSessionTitle() },
        )
    }
    // unrelated bare pass-through that stays legal
    SomeOtherChild(sessionId = sessionId)
}
"""

# [da1032-A] the pre-fix call site: compiles clean, button dead.
FIX_CHATSCEEN_EDITSHEET_MISSING = FIX_CHATSCEEN_OK.replace(
    """            liveSession = liveSessions.firstOrNull { it.id == session.id } ?: session,
            onDismiss = { editingSession = null },
            onSave = { t, c -> viewModel.updateTitleAndCategory(t, c) },
            onRegenerate = { viewModel.regenerateSessionTitle() },
""",
    """            onDismiss = { editingSession = null },
            onSave = { t, c -> viewModel.updateTitleAndCategory(t, c) },
""",
)

# half-wired: the button works, the field never refreshes.
FIX_CHATSCEEN_EDITSHEET_NO_LIVE = FIX_CHATSCEEN_OK.replace(
    "            liveSession = liveSessions.firstOrNull { it.id == session.id } ?: session,\n",
    "",
)

FIX_CHATSCEEN_DIRTY = """\
fun ChatScreen() {
    LocalMarkdownSessionId provides sessionId,
    ModalNavigationDrawer(drawerContent = {
        ChatHistoryDrawer(
            chatRepository = chatRepository,
            currentSessionId = sessionId,
            draft = draftSnapshot,
        )
    })
    if (showSkillsSheet && skillRepository != null) {
        SessionSkillsSheet(
            skillRepository = skillRepository,
            sessionId = sessionId,
            onDismiss = { showSkillsSheet = false },
        )
    }
    if (showMcpsSheet && mcpRepository != null) {
        SessionMcpsSheet(
            mcpRepository = mcpRepository,
            sessionId = sessionId,
            onDismiss = { showMcpsSheet = false },
        )
    }
    WebPreviewBottomSheet(
        holder = holder,
        pinSessionId = sessionId,
        onDismiss = onFullDismiss,
    )
}
"""

# [da1034-b3] OK = incremental merge into a sessionId-keyed map (mirrors
# FIX_MCPS_OK). The comment names the old shape on purpose: the scanner must
# judge code, never comments.
FIX_SKILLS_OK = """\
fun SessionSkillsSheet(sessionId: String, repo: SkillRepository) {
    val skills by repo.skills.collectAsState()
    // `remember(skills, sessionId) { … }` was the OLD rebuild form (comment trap)
    val overrides = remember(sessionId) {
        mutableStateMapOf<String, Boolean>()
    }
    LaunchedEffect(skills, sessionId) {
        mergeSessionOverrides(
            liveIds = skills.map { it.id },
            overrides = overrides,
            lookup = { repo.isEnabledForSession(it, sessionId) },
        )
    }
    // a different remember keyed only on skills stays LEGAL (search filter)
    val filteredSkills by remember(skills) {
        derivedStateOf { skills }
    }
}
"""

# [da1034-b3] the pre-fix shape — this was the gate's OK fixture until the
# criterion was corrected.
FIX_SKILLS_DIRTY = """\
fun SessionSkillsSheet(sessionId: String, repo: SkillRepository) {
    val skills by repo.skills.collectAsState()
    val overrides = remember(skills, sessionId) {
        mutableStateMapOf<String, Boolean>().apply {
            for (skill in skills) put(skill.id, repo.isEnabledForSession(skill.id, sessionId))
        }
    }
}
"""

FIX_SKILLS_KEYLESS = """\
fun SessionSkillsSheet(sessionId: String, repo: SkillRepository) {
    val skills by repo.skills.collectAsState()
    val overrides = remember {
        mutableStateMapOf<String, Boolean>()
    }
    LaunchedEffect(skills, sessionId) {
        for (skill in skills) {
            if (skill.id !in overrides) {
                overrides[skill.id] = repo.isEnabledForSession(skill.id, sessionId)
            }
        }
    }
}
"""

FIX_MCPS_OK = """\
fun SessionMcpsSheet(sessionId: String, mcpRepository: MCPRepository) {
    val servers by mcpRepository.servers.collectAsState()
    // remember(servers) { mutableStateMapOf(...) } was the OLD form (comment trap)
    val overrides = remember(sessionId) {
        mutableStateMapOf<String, Boolean>()
    }
    LaunchedEffect(servers, sessionId) {
        for (server in servers) {
            if (server.id !in overrides) {
                overrides[server.id] = mcpRepository.isEnabledForSession(server.id, sessionId)
            }
        }
    }
}
"""

FIX_MCPS_DIRTY = """\
fun SessionMcpsSheet(sessionId: String, mcpRepository: MCPRepository) {
    val servers by mcpRepository.servers.collectAsState()
    val overrides = remember {
        mutableStateMapOf<String, Boolean>()
    }
    LaunchedEffect(servers) {
        for (server in servers) {
            if (server.id !in overrides) {
                overrides[server.id] = mcpRepository.isEnabledForSession(server.id, sessionId)
            }
        }
    }
}
"""


# [da1045-B6] the pre-fix shape: `missing` is a snapshot taken before the IO
# await, then written back with no membership re-check — an in-flight toggle is
# overwritten by its own pre-toggle stored value. Note the file DOES contain
# `it !in overrides` (inside the `missing` filter), which is exactly why the
# criterion has to judge the write site rather than the file.
FIX_MCPS_NOGUARD = """\
fun SessionMcpsSheet(sessionId: String, mcpRepository: MCPRepository) {
    val servers by mcpRepository.servers.collectAsState()
    val overrides = remember(sessionId) {
        mutableStateMapOf<String, Boolean>()
    }
    LaunchedEffect(servers, sessionId) {
        val missing = servers.map { it.id }.filter { it !in overrides }
        val seeds = withContext(Dispatchers.IO) {
            missing.associateWith { mcpRepository.isEnabledForSession(it, sessionId) }
        }
        for (id in missing) overrides[id] = seeds[id] ?: false
        val live = servers.mapTo(HashSet()) { it.id }
        overrides.keys.retainAll(live)
    }
}
"""

# [da1045-B6] the shipped shape — the same helper SessionSkillsSheet uses.
FIX_MCPS_MERGE = """\
fun SessionMcpsSheet(sessionId: String, mcpRepository: MCPRepository) {
    val servers by mcpRepository.servers.collectAsState()
    val overrides = remember(sessionId) {
        mutableStateMapOf<String, Boolean>()
    }
    LaunchedEffect(servers, sessionId) {
        val liveIds = servers.map { it.id }
        val seeds = withContext(Dispatchers.IO) {
            liveIds.filter { it !in overrides }
                .associateWith { mcpRepository.isEnabledForSession(it, sessionId) }
        }
        mergeSessionOverrides(
            liveIds = liveIds,
            overrides = overrides,
            lookup = { seeds[it] ?: false },
        )
    }
}
"""

# [da1045-B6] same decision, three tolerated spellings.
FIX_MCPS_BRACELESS = """\
fun SessionMcpsSheet(sessionId: String, mcpRepository: MCPRepository) {
    val overrides = remember(sessionId) {
        mutableStateMapOf<String, Boolean>()
    }
    LaunchedEffect(servers, sessionId) {
        val ids = servers.map { it.id }
        for (id in ids) {
            if (id !in overrides)
                overrides[id] = mcpRepository.isEnabledForSession(id, sessionId)
        }
    }
}
"""

FIX_MCPS_NULLREAD = """\
fun SessionMcpsSheet(sessionId: String, mcpRepository: MCPRepository) {
    val overrides = remember(sessionId) {
        mutableStateMapOf<String, Boolean>()
    }
    LaunchedEffect(servers, sessionId) {
        val ids = servers.map { it.id }
        withContext(Dispatchers.IO) {
            ids.filter { overrides[it] == null }
        }
        for (id in ids) {
            if (overrides[id] == null) overrides[id] = mcpRepository.isEnabledForSession(id, sessionId)
        }
    }
}
"""

FIX_MCPS_CONTAINSKEY = """\
fun SessionMcpsSheet(sessionId: String, mcpRepository: MCPRepository) {
    val overrides = remember(sessionId) {
        mutableStateMapOf<String, Boolean>()
    }
    LaunchedEffect(servers, sessionId) {
        val ids = servers.map { it.id }
        for (id in ids) {
            if (!overrides.containsKey(id)) {
                overrides[id] = mcpRepository.isEnabledForSession(id, sessionId)
            }
        }
    }
}
"""

# [da1045-B6] the guard must name the map it writes: `!in seeds` decides
# nothing about `overrides` (seeds is the just-read snapshot).
FIX_MCPS_WRONG_MAP = """\
fun SessionMcpsSheet(sessionId: String, mcpRepository: MCPRepository) {
    val overrides = remember(sessionId) {
        mutableStateMapOf<String, Boolean>()
    }
    LaunchedEffect(servers, sessionId) {
        val seeds = withContext(Dispatchers.IO) {
            servers.associate { it.id to mcpRepository.isEnabledForSession(it.id, sessionId) }
        }
        for (id in servers.map { it.id }) {
            if (id !in seeds) overrides[id] = seeds[id] ?: false
        }
    }
}
"""

# [da1045-B6] fail closed: a helper call that never receives the map seeds
# nothing this gate can see, and the inline writes are gone.
FIX_MCPS_HELPER_BLIND = """\
fun SessionMcpsSheet(sessionId: String, mcpRepository: MCPRepository) {
    val overrides = remember(sessionId) {
        mutableStateMapOf<String, Boolean>()
    }
    LaunchedEffect(servers, sessionId) {
        val seeds = withContext(Dispatchers.IO) {
            servers.associate { it.id to mcpRepository.isEnabledForSession(it.id, sessionId) }
        }
        mergeSessionOverrides(liveIds = servers.map { it.id }, lookup = { seeds[it] ?: false })
    }
}
"""

# [da1045-B6] the SAME defect on the twin sheet — one criterion, both files.
FIX_SKILLS_NOGUARD = """\
fun SessionSkillsSheet(sessionId: String, repo: SkillRepository) {
    val skills by repo.skills.collectAsState()
    val overrides = remember(sessionId) {
        mutableStateMapOf<String, Boolean>()
    }
    LaunchedEffect(skills, sessionId) {
        val missing = skills.map { it.id }.filter { it !in overrides }
        val seeds = withContext(Dispatchers.IO) {
            missing.associateWith { repo.isEnabledForSession(it, sessionId) }
        }
        for (id in missing) overrides[id] = seeds[id] ?: false
    }
}
"""

def write_fixture(root, rel, content):
    p = os.path.join(root, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        f.write(content)


FIX_SESSIONS_OK = """\
@Composable
internal fun SessionEditSheet(
    session: ChatSessionEntity,
    onDismiss: () -> Unit,
    onSave: (title: String, category: String?) -> Unit,
    liveSession: ChatSessionEntity = session,
    isRegenerating: Boolean = false,
    onRegenerate: () -> Unit = {},
) {
    // extra keys are legal: the guard asks whether the key list carries the id,
    // not whether the list is exactly (session.id)
    var title by remember(session.id, session.title) { mutableStateOf(session.title ?: "") }
    var selectedCategory by remember(session.id) { mutableStateOf(session.category) }
    val defaultSessionTitle = stringResource(R.string.session_new_chat_default)

    LaunchedEffect(liveSession.id, liveSession.title, liveSession.category) {
        liveSession.title?.let { title = it }
        selectedCategory = liveSession.category
    }

    OutlinedButton(onClick = onRegenerate, enabled = !isRegenerating) {
        Text(defaultSessionTitle)
    }
}

// PRECISION: a different composable in the SAME file holding keyless seed
// state with the same names — legal, and the guard is scoped to the
// SessionEditSheet body so it must not be judged.
@Composable
internal fun SessionListRow(session: ChatSessionEntity) {
    var title by remember { mutableStateOf(session.title ?: "") }
    var selectedCategory by remember { mutableStateOf(session.category) }
    Text(title ?: "", selectedCategory)
}
"""

# [da1032-A] the pre-fix shape: unkeyed seeds + an effect that cannot re-seed
# when the sheet is re-pointed at another row.
FIX_SESSIONS_DIRTY = """\
@Composable
internal fun SessionEditSheet(
    session: ChatSessionEntity,
    liveSession: ChatSessionEntity = session,
    onRegenerate: () -> Unit = {},
) {
    var title by remember { mutableStateOf(session.title ?: "") }
    var selectedCategory by remember { mutableStateOf(session.category) }

    LaunchedEffect(liveSession.title, liveSession.category) {
        liveSession.title?.let { title = it }
        selectedCategory = liveSession.category
    }

    OutlinedButton(onClick = onRegenerate) { Text(title) }
}
"""


# ── wide-1024 witnesses: the three shapes that defeated this gate ──────────
# (each is the find→replace from the wide-sweep report, replayed verbatim)
FIX_SKILLS_EMPTY_LIVE_IDS = FIX_SKILLS_OK.replace(
    "            liveIds = skills.map { it.id },",
    "            liveIds = emptyList(),",
)
FIX_MCPS_NEW_SESSION_READ = FIX_MCPS_MERGE.replace(
    "mcpRepository.isEnabledForSession(it, sessionId)",
    'mcpRepository.isEnabledForSession(it, "__new__")',
)
FIX_SESSIONS_TITLE_MIRROR_DROPPED = FIX_SESSIONS_OK.replace(
    "        liveSession.title?.let { title = it }\n", "")
# clean counter-arm: a different legal spelling of the same mirror
FIX_SESSIONS_DIRECT_MIRROR = FIX_SESSIONS_OK.replace(
    "        liveSession.title?.let { title = it }",
    "        title = liveSession.title ?: title")


# ── batch7 witnesses: "the token survives, the value does not" ─────────────
# (each is the find→replace from the batch-7 report, replayed verbatim)

# 12-1 (dataflow): the call site still says viewModel.currentSessionId and
# hands the panel "" — the panel then writes every toggle against an empty id.
FIX_CHATSCEEN_ID_REWRITTEN = FIX_CHATSCEEN_OK.replace(
    "            sessionId = viewModel.currentSessionId,",
    '            sessionId = viewModel.currentSessionId.let { "" },', 1)

# clean counter-arm: a different legal spelling of the same argument
FIX_CHATSCEEN_ID_BANGS = FIX_CHATSCEEN_OK.replace(
    "            sessionId = viewModel.currentSessionId,",
    "            sessionId = viewModel.currentSessionId!!,", 1)

# 12-2 (family-hole): the id list is built from the live list and then emptied.
FIX_SKILLS_LIVE_IDS_TAKE_ZERO = FIX_SKILLS_OK.replace(
    "            liveIds = skills.map { it.id },",
    "            liveIds = skills.map { it.id }.take(0),")

# clean counter-arm: a non-emptying stage on the same pipeline
FIX_SKILLS_LIVE_IDS_STAGE = FIX_SKILLS_OK.replace(
    "            liveIds = skills.map { it.id },",
    "            liveIds = skills.map { it.id }.filter { it.isNotBlank() },")

# 12-3 (dataflow): the seed read keeps the word `sessionId` and swaps the value.
FIX_SKILLS_READ_NEW_ID = FIX_SKILLS_OK.replace(
    "lookup = { repo.isEnabledForSession(it, sessionId) }",
    'lookup = { repo.isEnabledForSession(it, sessionId.let { "__new__" }) }')

# 12-4 (spelling): the same write in another spelling — a map put, unguarded.
FIX_MCPS_PUT_NOGUARD = FIX_MCPS_MERGE.replace(
    "        mergeSessionOverrides(",
    "        for ((id, v) in seeds) overrides.put(id, v)\n        mergeSessionOverrides(", 1)

# 12-5 (dataflow): both mirror shapes keep their spelling and drop the value.
FIX_SESSIONS_MIRROR_BLANKED = FIX_SESSIONS_OK.replace(
    "        liveSession.title?.let { title = it }\n"
    "        selectedCategory = liveSession.category",
    '        liveSession.title?.let { title = "" }\n'
    "        selectedCategory = liveSession.category?.takeIf { false }")

# clean counter-arm: a feasible takeIf predicate on the same mirror
FIX_SESSIONS_TAKEIF_OK = FIX_SESSIONS_OK.replace(
    "        selectedCategory = liveSession.category",
    "        selectedCategory = liveSession.category?.takeIf { it != null }")

# 12-6 (family-hole): the argument is passed and equals the parameter default.
FIX_CHATSCEEN_REGEN_EMPTY = FIX_CHATSCEEN_OK.replace(
    "            onRegenerate = { viewModel.regenerateSessionTitle() },",
    "            onRegenerate = {},", 1)

def make_tree(chatscreen, skills, mcps, sessions=FIX_SESSIONS_OK):
    root = tempfile.mkdtemp(prefix="ssib-selftest-")
    base = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat"
    write_fixture(root, os.path.join(base, "ChatScreen.kt"), chatscreen)
    write_fixture(root, os.path.join(base, "SessionSkillsSheet.kt"), skills)
    write_fixture(root, os.path.join(base, "SessionMcpsSheet.kt"), mcps)
    write_fixture(root, os.path.join(SESSIONS_DIR, "SessionsShared.kt"), sessions)
    return root


def selftest():
    cases = [
        # (name, chatscreen, skills, mcps, expect_clean)
        ("compliant tree", FIX_CHATSCEEN_OK, FIX_SKILLS_OK, FIX_MCPS_OK, True),
        ("dirty chatscreen (bare sessionId everywhere)", FIX_CHATSCEEN_DIRTY, FIX_SKILLS_OK, FIX_MCPS_OK, False),
        # [da1034-b3] the skills arms flipped direction: the rebuild shape is
        # now the dirty one, the merge shape the clean one.
        ("dirty skills sheet (rebuild on skills+sessionId)", FIX_CHATSCEEN_OK, FIX_SKILLS_DIRTY, FIX_MCPS_OK, False),
        ("dirty skills sheet (keyless remember)", FIX_CHATSCEEN_OK, FIX_SKILLS_KEYLESS, FIX_MCPS_OK, False),
        ("dirty mcps sheet (keyless remember + effect key)", FIX_CHATSCEEN_OK, FIX_SKILLS_OK, FIX_MCPS_DIRTY, False),
        # per-violation precision: exactly one finding each
        ("only markdown site dirty", FIX_CHATSCEEN_DIRTY, FIX_SKILLS_OK, FIX_MCPS_OK, False),
    ]
    ok = True
    for name, cs, sk, mp, expect_clean in cases:
        root = make_tree(cs, sk, mp)
        try:
            findings = check(root)
            clean = not findings
            if clean != expect_clean:
                ok = False
                print(f"[selftest FAIL] {name}: expected {'clean' if expect_clean else 'findings'}, got: {findings}")
            else:
                print(f"[selftest ok] {name}: {'clean' if clean else f'{len(findings)} finding(s)'}")
        finally:
            shutil.rmtree(root, ignore_errors=True)

    # [da1045-B6] seeding-guard arms: the defect shape flips red, every spelling
    # of the same decision stays clean, and one criterion judges both sheets.
    for name, mp, sk, expect_clean in (
        ("mcps baseline: snapshot-unguarded seed loop", FIX_MCPS_NOGUARD, FIX_SKILLS_OK, False),
        ("mcps shipped: seeds through the merge helper", FIX_MCPS_MERGE, FIX_SKILLS_OK, True),
        ("mcps direct guard, brace-less", FIX_MCPS_BRACELESS, FIX_SKILLS_OK, True),
        ("mcps guard spelled `overrides[id] == null`", FIX_MCPS_NULLREAD, FIX_SKILLS_OK, True),
        ("mcps guard spelled `!overrides.containsKey(id)`", FIX_MCPS_CONTAINSKEY, FIX_SKILLS_OK, True),
        ("mcps guard on the wrong map (`!in seeds`)", FIX_MCPS_WRONG_MAP, FIX_SKILLS_OK, False),
        ("mcps helper called without the map", FIX_MCPS_HELPER_BLIND, FIX_SKILLS_OK, False),
        ("skills sheet, same unguarded loop", FIX_MCPS_MERGE, FIX_SKILLS_NOGUARD, False),
    ):
        root = make_tree(FIX_CHATSCEEN_OK, sk, mp)
        try:
            findings = check(root)
            clean = not findings
            if clean != expect_clean:
                ok = False
                print(f"[selftest FAIL] {name}: expected {'clean' if expect_clean else 'findings'}, got: {findings}")
            else:
                print(f"[selftest ok] {name}: {'clean' if clean else f'{len(findings)} finding(s)'}")
        finally:
            shutil.rmtree(root, ignore_errors=True)

    # precision: each seed arm must produce EXACTLY its own finding and name its
    # own anchor — a second finding means the arm is not isolating the
    # criterion, zero means the criterion went blind.
    for name, mp, sk, must_mention, want_count in (
        ("mcps unguarded seed loop", FIX_MCPS_NOGUARD, FIX_SKILLS_OK,
         ["SessionMcpsSheet.kt", "unguarded seeding write", "in-flight toggle"], 1),
        ("mcps guard on the wrong map", FIX_MCPS_WRONG_MAP, FIX_SKILLS_OK,
         ["SessionMcpsSheet.kt", "unguarded seeding write"], 1),
        ("mcps helper without the map arg", FIX_MCPS_HELPER_BLIND, FIX_SKILLS_OK,
         ["SessionMcpsSheet.kt", "merge*Overrides"], 1),
        ("skills unguarded seed loop", FIX_MCPS_MERGE, FIX_SKILLS_NOGUARD,
         ["SessionSkillsSheet.kt", "unguarded seeding write"], 1),
    ):
        root = make_tree(FIX_CHATSCEEN_OK, sk, mp)
        try:
            findings = check(root)
            joined = " | ".join(findings)
            for hit in must_mention:
                if hit not in joined:
                    ok = False
                    print(f"[selftest FAIL] precision {name}: missing `{hit}` in findings")
            if len(findings) != want_count:
                ok = False
                print(f"[selftest FAIL] precision {name}: expected {want_count} finding(s), got {len(findings)}: {findings}")
            else:
                print(f"[selftest ok] precision {name}: exactly {want_count} finding(s), anchors named")
        finally:
            shutil.rmtree(root, ignore_errors=True)

    # [da1032-A] the new condition, three shapes + one precision arm
    edit_cases = [
        ("edit sheet call missing BOTH defaulted args",
         FIX_CHATSCEEN_EDITSHEET_MISSING, FIX_SESSIONS_OK, False),
        ("edit sheet call missing liveSession only",
         FIX_CHATSCEEN_EDITSHEET_NO_LIVE, FIX_SESSIONS_OK, False),
        ("sessions shared unkeyed seeds + effect without liveSession.id",
         FIX_CHATSCEEN_OK, FIX_SESSIONS_DIRTY, False),
        ("sessions shared keyed (precision: extra keys stay legal)",
         FIX_CHATSCEEN_OK, FIX_SESSIONS_OK, True),
    ]
    for name, cs, ss, expect_clean in edit_cases:
        root = make_tree(cs, FIX_SKILLS_OK, FIX_MCPS_OK, ss)
        try:
            findings = check(root)
            clean = not findings
            if clean != expect_clean:
                ok = False
                print(f"[selftest FAIL] {name}: expected {'clean' if expect_clean else 'findings'}, got: {findings}")
            else:
                print(f"[selftest ok] {name}: {'clean' if clean else f'{len(findings)} finding(s)'}")
        finally:
            shutil.rmtree(root, ignore_errors=True)

    # precision: the two new shapes must name their own anchor, and only theirs.
    for name, cs, ss, must_mention, must_not_mention, want_count in (
        ("edit sheet both args missing", FIX_CHATSCEEN_EDITSHEET_MISSING, FIX_SESSIONS_OK,
         ["SessionEditSheet", "onRegenerate", "liveSession"], [], 2),
        ("edit sheet liveSession missing", FIX_CHATSCEEN_EDITSHEET_NO_LIVE, FIX_SESSIONS_OK,
         ["SessionEditSheet", "liveSession"], ["onRegenerate"], 1),
        ("sessions shared unkeyed", FIX_CHATSCEEN_OK, FIX_SESSIONS_DIRTY,
         ["liveSession.id", "var title by remember", "var selectedCategory by remember"], [], 5),
    ):
        root = make_tree(cs, FIX_SKILLS_OK, FIX_MCPS_OK, ss)
        try:
            findings = check(root)
            joined = " | ".join(findings)
            for hit in must_mention:
                if hit not in joined:
                    ok = False
                    print(f"[selftest FAIL] precision {name}: missing `{hit}` in findings")
            for hit in must_not_mention:
                if hit in joined:
                    ok = False
                    print(f"[selftest FAIL] precision {name}: unexpected `{hit}` in findings")
            if len(findings) != want_count:
                ok = False
                print(f"[selftest FAIL] precision {name}: expected {want_count} finding(s), got {len(findings)}: {findings}")
            else:
                print(f"[selftest ok] precision {name}: exactly {want_count} finding(s), anchors named")
        finally:
            shutil.rmtree(root, ignore_errors=True)

    # [da1034-b3] the skills arms must name their own anchors and only theirs;
    # the OK fixture's comment names the old shape (comment trap) and must stay clean.
    for name, sk, must_mention, must_not_mention, want_count in (
        ("skills rebuild shape", FIX_SKILLS_DIRTY,
         ["SessionSkillsSheet", "remember(skills", "LaunchedEffect(skills, sessionId)"],
         ["keyless"], 3),
        ("skills keyless shape", FIX_SKILLS_KEYLESS,
         ["SessionSkillsSheet", "keyless"],
         ["remember(skills", "LaunchedEffect(skills, sessionId)"], 2),
    ):
        root = make_tree(FIX_CHATSCEEN_OK, sk, FIX_MCPS_OK)
        try:
            findings = check(root)
            joined = " | ".join(findings)
            for hit in must_mention:
                if hit not in joined:
                    ok = False
                    print(f"[selftest FAIL] precision {name}: missing `{hit}` in findings")
            for hit in must_not_mention:
                if hit in joined:
                    ok = False
                    print(f"[selftest FAIL] precision {name}: unexpected `{hit}` in findings")
            if len(findings) != want_count:
                ok = False
                print(f"[selftest FAIL] precision {name}: expected {want_count} finding(s), got {len(findings)}: {findings}")
            else:
                print(f"[selftest ok] precision {name}: exactly {want_count} finding(s), anchors named")
        finally:
            shutil.rmtree(root, ignore_errors=True)

    # precision: dirty tree findings must name the right anchors
    root = make_tree(FIX_CHATSCEEN_DIRTY, FIX_SKILLS_OK, FIX_MCPS_OK)
    try:
        findings = check(root)
        joined = " | ".join(findings)
        required_hits = [
            "SessionSkillsSheet",
            "SessionMcpsSheet",
            "WebPreviewBottomSheet",
            "ChatHistoryDrawer",
            "LocalMarkdownSessionId",
        ]
        for hit in required_hits:
            if hit not in joined:
                ok = False
                print(f"[selftest FAIL] precision: missing anchor `{hit}` in findings")
        print(f"[selftest ok] precision: all 5 anchors flagged ({len(findings)} findings)")
    finally:
        shutil.rmtree(root, ignore_errors=True)

    # ── wide-1024 witnesses: exactly one finding each, naming its own anchor ──
    for name, sk, mp, ss, must_mention, want_count in (
        ("W5 skills merge handed an empty id list", FIX_SKILLS_EMPTY_LIVE_IDS,
         FIX_MCPS_OK, FIX_SESSIONS_OK, ["SessionSkillsSheet.kt", "empty id list"], 1),
        ("W6 mcps seed read queries __new__", FIX_SKILLS_OK,
         FIX_MCPS_NEW_SESSION_READ, FIX_SESSIONS_OK,
         ["SessionMcpsSheet.kt", "__new__", "isEnabledForSession"], 1),
        ("W4 edit-sheet effect drops the title mirror", FIX_SKILLS_OK,
         FIX_MCPS_OK, FIX_SESSIONS_TITLE_MIRROR_DROPPED,
         ["SessionsShared.kt", "liveSession.title"], 1),
        ("W4ok direct title mirror stays clean", FIX_SKILLS_OK,
         FIX_MCPS_OK, FIX_SESSIONS_DIRECT_MIRROR, [], 0),
    ):
        root = make_tree(FIX_CHATSCEEN_OK, sk, mp, ss)
        try:
            findings = check(root)
            joined = " | ".join(findings)
            for hit in must_mention:
                if hit not in joined:
                    ok = False
                    print(f"[selftest FAIL] wide {name}: missing `{hit}` in findings")
            if len(findings) != want_count:
                ok = False
                print(f"[selftest FAIL] wide {name}: expected {want_count} finding(s), "
                      f"got {len(findings)}: {findings}")
            else:
                print(f"[selftest ok] wide {name}: exactly {want_count} finding(s)")
        finally:
            shutil.rmtree(root, ignore_errors=True)

    # ── batch7 witnesses: the token survives, the value does not ────────────
    # 12-1/12-3/12-5 are the dataflow axis, 12-2/12-6 the family-hole axis and
    # 12-4 the spelling axis; every one of them used to leave this gate green.
    for name, cs, sk, mp, ss, must_mention, want_count in (
        ("W7 call site hands the panel a rewritten id", FIX_CHATSCEEN_ID_REWRITTEN,
         FIX_SKILLS_OK, FIX_MCPS_OK, FIX_SESSIONS_OK,
         ["ChatScreen.kt", "SessionSkillsSheet", "currentSessionId"], 1),
        ("W7ok `!!` on the same argument stays clean", FIX_CHATSCEEN_ID_BANGS,
         FIX_SKILLS_OK, FIX_MCPS_OK, FIX_SESSIONS_OK, [], 0),
        ("W8 live id list emptied by .take(0)", FIX_CHATSCEEN_OK,
         FIX_SKILLS_LIVE_IDS_TAKE_ZERO, FIX_MCPS_OK, FIX_SESSIONS_OK,
         ["SessionSkillsSheet.kt", "take(0)", "liveIds"], 1),
        ("W8ok a non-emptying stage stays clean", FIX_CHATSCEEN_OK,
         FIX_SKILLS_LIVE_IDS_STAGE, FIX_MCPS_OK, FIX_SESSIONS_OK, [], 0),
        ("W9 seed read queries the __new__ id", FIX_CHATSCEEN_OK,
         FIX_SKILLS_READ_NEW_ID, FIX_MCPS_OK, FIX_SESSIONS_OK,
         ["SessionSkillsSheet.kt", "isEnabledForSession", "sessionId"], 1),
        ("W10 unguarded seed write in map.put spelling", FIX_CHATSCEEN_OK,
         FIX_SKILLS_OK, FIX_MCPS_PUT_NOGUARD, FIX_SESSIONS_OK,
         ["SessionMcpsSheet.kt", "unguarded seeding write", "put"], 1),
        ("W11 both mirrors blanked", FIX_CHATSCEEN_OK,
         FIX_SKILLS_OK, FIX_MCPS_OK, FIX_SESSIONS_MIRROR_BLANKED,
         ["SessionsShared.kt", "liveSession.title", "liveSession.category"], 2),
        ("W11ok feasible takeIf mirror stays clean", FIX_CHATSCEEN_OK,
         FIX_SKILLS_OK, FIX_MCPS_OK, FIX_SESSIONS_TAKEIF_OK, [], 0),
        ("W12 onRegenerate passed as its own default", FIX_CHATSCEEN_REGEN_EMPTY,
         FIX_SKILLS_OK, FIX_MCPS_OK, FIX_SESSIONS_OK,
         ["ChatScreen.kt", "SessionEditSheet", "onRegenerate"], 1),
    ):
        root = make_tree(cs, sk, mp, ss)
        try:
            findings = check(root)
            joined = " | ".join(findings)
            for hit in must_mention:
                if hit not in joined:
                    ok = False
                    print(f"[selftest FAIL] batch7 {name}: missing `{hit}` in findings")
            if len(findings) != want_count:
                ok = False
                print(f"[selftest FAIL] batch7 {name}: expected {want_count} finding(s), "
                      f"got {len(findings)}: {findings}")
            else:
                print(f"[selftest ok] batch7 {name}: exactly {want_count} finding(s)")
        finally:
            shutil.rmtree(root, ignore_errors=True)

    print(SELFTEST_MARKER if ok else "SESSION_SHEET_ID_BINDING_SELFTEST=FAIL")
    return 0 if ok else 1


def main():
    args = sys.argv[1:]
    if "--selftest" in args:
        return selftest()
    root = None
    if "--root" in args:
        i = args.index("--root")
        if i + 1 >= len(args):
            print("usage: --root <repo_root>", file=sys.stderr)
            return 2
        root = args[i + 1]
    elif args:
        root = args[0]
    else:
        root = os.getcwd()
    if not os.path.isdir(root):
        print(f"repo root not found: {root}", file=sys.stderr)
        return 2
    findings = check(root)
    if findings:
        print(f"session_sheet_id_binding_check: FAIL ({len(findings)} finding(s))")
        for f in findings:
            print(f"  - {f}")
        return 1
    print("session_sheet_id_binding_check: OK (4 session-scoped call blocks + LocalMarkdownSessionId "
          "active-bound, SessionEditSheet call args wired, both sheets id-keyed with incremental "
          "seeding through membership-guarded writes, edit-sheet keys id-keyed)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
