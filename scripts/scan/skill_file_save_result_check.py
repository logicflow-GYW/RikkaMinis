#!/usr/bin/env python3
"""
Gate: FIX-E (deep-audit-1021 ISSUE-06, P1) — SkillFileViewerScreen must
consume the save result, never silently pop the screen.

ISSUE-06: the Save callback of `SkillFileViewerScreen` dropped the return
values of `SkillRepository.importFromContent` / `writeSkillFile` and then
unconditionally executed `isEditing = false; onBack()` — a broken SKILL.md
frontmatter made importFromContent return null without writing anything, so
the user's whole edit vanished with no error shown. The save also ran
synchronously on the main thread (SQLite + file I/O).

Fix contract asserted here (presence of the corrected wiring, not absence of
a spelling — a refactor that keeps the contract is a deliberate gate edit):

  arm1  the `importFromContent(` call in the screen is consumed (`) != null`)
  arm2  the save runs inside `val ok = withContext(Dispatchers.IO) { ... }`
        and both importFromContent and writeSkillFile live in that block
  arm3  `if (ok)` guards the exit: its block contains `isEditing = false`
        and `onBack()`, and `isEditing = false` occurs exactly once in the
        screen (no unconditional pop sequence outside the guard)
  arm4  `withContext(Dispatchers.IO)` is present in the screen
  arm5  the `preferredId = skillId` pin survives (audit-1015-b4-2 contract)

[dv14-B5] arm6 (independent check function `run_offmain_checks`) — the
sibling-file disk read must ride IO off the composition thread (the twin of
MemoryManagementScreen's [deep-audit-1029 ISSUE-03] fix):

  arm6a `skillRepository.readSkillFile(` sits inside a balanced
        `withContext(Dispatchers.IO)` block (not inside the `remember {}`
        composition lambda — that ran the read on the main thread of every
        recomposition)
  arm6b the read is triggered from a `LaunchedEffect` block (async load,
        not a synchronous composition-phase call)
  arm6c the Edit button is gated with `enabled = isLoaded` (a tap before the
        read lands would open the editor against a blank base and a Save
        could overwrite the real file with "")

arm2's discovery is anchored on the `val ok =` binding (then the
`withContext(` that immediately follows it) rather than on the first
`withContext(` in the screen — the [dv14-B5] async-read `withContext` in the
`LaunchedEffect` must not shadow the save's context. The asserted contract is
unchanged.

[wide4 2026-10-25] Four ways to keep the spelling and delete the decision were
closed after an external audit re-attacked this gate:

  arm2   the block's VALUE must be the write's result, not a presence of the
         call: `writeSkillFile(…) || true` and `writeSkillFile(…).let { true }`
         (w4-arm2) are constants — the write's boolean is discarded. A local
         indirection (`val saved = …; saved`) still counts as the same value.
  arm3   `if (ok)` must be exactly that condition (a literal-short-circuited
         `if (ok || true)` is a guard in spelling only); the pop may be spelled
         `onBack.invoke()` (w4-arm3); and statements are judged on the shared
         lexer's projection, so a `"${"}"}"` template can no longer leak a `}`
         and promote a dead `onBack()` out of an `if (false)` block
         (w4-arm3, parser-death).
  arm6c  "live flag" must be decided by const folding (`isLoaded || (1 == 1)`,
         w4-arm6c) — and the flag must actually be FLIPPED to true after the
         read in the same LaunchedEffect, which this docstring has promised
         since [dv14-B5] but the code never checked.

Any screen that cannot be lexed is a violation: the gate refuses to judge a
projection it could not build.

Usage: python3 scripts/scan/skill_file_save_result_check.py [repo_root]
       python3 scripts/scan/skill_file_save_result_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage
"""
import os
import re
import sys

TARGET_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/SkillsManagementScreen.kt"
SCREEN_ANCHOR = "fun SkillFileViewerScreen("

CONSUME_IMPORT = "importFromContent("
IO_CTX = "withContext(Dispatchers.IO)"
OK_BINDING = "val ok ="
OK_GUARD = "if (ok)"
PREFERRED_ID = "preferredId = skillId"
EXIT_ASSIGN = "isEditing = false"
EXIT_CALL = "onBack()"
# [wide4 w4-arm3] a pop is a pop in either spelling; the guard must be exactly
# `if (ok)` — `if (ok || true)` keeps the spelling and drops the guard.
EXIT_CALL_RE = re.compile(r"^onBack\s*(?:\(\s*\)|\.\s*invoke\s*\(\s*\))$")
GUARD_RE = re.compile(r"\bif\s*\(\s*ok\s*\)")


# ── shared lexical projection (wide4 parser-death axis) ─────────────────────
# This gate used to carry its own `strip_comments` / `mask_strings`. Both were
# blind to Kotlin string TEMPLATES: `val _s = "${"}"}"` left the inner `}` in
# the projection, which closed an `if (false) { … }` block early and promoted
# the dead `onBack()` inside it to a top-level statement of the save guard
# (wide4 witness `mask_strings`). The projection now comes from the single
# shared state machine, and a failed parse is a VIOLATION — never a silent
# fallback to the raw text.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import analyse                                    # noqa: E402
from _kotlin_ast import (const_bool, enclosing_conditions,           # noqa: E402
                         if_blocks, match_bracket, stmt_spans,
                         strip_parens, when_branches)
from _kotlin_valueflow import (call_args, expr_end,                  # noqa: E402
                               value_sources)


class Unparseable(Exception):
    """The screen could not be lexed — the gate refuses to judge it."""


def _views(text):
    ok, reason, views = analyse(text)
    if not ok:
        raise Unparseable(reason)
    return views


def strip_comments(text):
    """Comment-free projection, offsets preserved (string contents intact)."""
    return _views(text)["comments"]


def mask_strings(text):
    """Projection with comment text AND string/char contents blanked."""
    return _views(text)["code"]


def find_matching_brace(text, open_idx):
    """Index of the '}' matching the '{' at open_idx, on the code projection."""
    return match_bracket(mask_strings(text), open_idx)


def _match_paren(text, open_idx):
    """Index of the ')' matching the '(' at open_idx (-1 if none)."""
    return match_bracket(mask_strings(text), open_idx)


def _unparen(p):
    return strip_parens(p)


def _split_top(s, op):
    """Split at depth-0 occurrences of `op` (string literals masked)."""
    masked = mask_strings(s)
    parts, depth, start, i = [], 0, 0, 0
    while i < len(masked):
        c = masked[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and masked.startswith(op, i):
            parts.append(s[start:i])
            i += len(op)
            start = i
            continue
        i += 1
    parts.append(s[start:])
    return parts


def _fold_bool(expr):
    """Boolean value of `expr` when literals alone decide it, else None.

    Kotlin precedence is applied here — `||` lowest, then `&&`, then unary `!` —
    because the shared folder tests a leading `!` BEFORE splitting the operators:
    `!true && isLoaded` (== `false && …`) came back "unknown", so a gate that can
    never open read as a live gate. Atoms go to the shared folder.
    """
    e = strip_parens(expr.strip())
    if not e:
        return None
    for op in ("||", "&&"):
        parts = _split_top(e, op)
        if len(parts) > 1:
            vals = [_fold_bool(p) for p in parts]
            if op == "||":
                if any(v is True for v in vals):
                    return True
                return None if any(v is None for v in vals) else False
            if any(v is False for v in vals):
                return False
            return None if any(v is None for v in vals) else True
    if e.startswith("!") and not e.startswith("!="):
        v = _fold_bool(e[1:])
        return None if v is None else (not v)
    return const_bool(e)


def _lit_value(part):
    """Boolean value of `part` when literals alone decide it (else None).

    [wide4 w4-arm6c] The old helper understood only a bare `true` / `false`
    (optionally negated), so `isLoaded || (1 == 1)` — a constant by every
    reading — came back as "unknown" and the gate accepted a dead gate.
    Const folding is the shared primitive's job: it covers `!true`, `0 == 1`,
    `"a" == "b"` and Kotlin's short-circuit semantics.
    """
    return _fold_bool(part)


def _literal_decides(expr):
    """True when the boolean value of `expr` is fixed by literals alone.

    `enabled = isLoaded || true` (wide3-08 W2) and `enabled = isLoaded ||
    (1 == 1)` (wide4 w4-arm6c) both keep the spelling and delete the decision.
    """
    return _fold_bool(expr) is not None


def _statements(block_body):
    """Top-level statements of a block body (Kotlin needs no semicolons)."""
    body = mask_strings(block_body)
    return [body[a:b].strip() for a, b in stmt_spans(body) if body[a:b].strip()]

REPO_WRITE_CALLS = ("importFromContent(", "writeSkillFile(")

# A plain receiver prefix (`skillRepository.`, `app.repo.`) — nothing else may
# precede the write call: `.let { }.writeSkillFile(` is not the call's value.
RECEIVER_PREFIX = re.compile(r"^[A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)*\s*\.\s*$")
IDENT_ONLY = re.compile(r"^[A-Za-z_]\w*$")
# What may follow the write call without replacing its value.
VALUE_SUFFIXES = ("", "!= null", "== null", "!=null", "==null", "!!")
BINDING_RE = re.compile(r"(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=]+?)?=\s*")


def _norm(s):
    return " ".join(s.split())


def _leaf_is_write(e):
    """True when the leaf expression's VALUE **is** a repository write result.

    [wide4 w4-arm2] The old leaf test was `any(call in e)` — a presence test. So
    `writeSkillFile(…) || true` (value: constant true) and
    `writeSkillFile(…).let { true }` (value: the lambda's) both read as "the
    write's result" and a FAILED write popped the screen as if it succeeded.
    The leaf must now BE the call: a plain receiver prefix, the call itself, and
    nothing after it but a null comparison.
    """
    n = _norm(e)
    for call in REPO_WRITE_CALLS:
        start = 0
        while True:
            idx = n.find(call, start)
            if idx < 0:
                break
            start = idx + 1
            prefix = n[:idx]
            if prefix.strip() and not RECEIVER_PREFIX.match(prefix):
                continue
            close = match_bracket(n, idx + len(call) - 1)
            if close < 0:
                continue
            if n[close + 1:].strip() in VALUE_SUFFIXES:
                return True
    return False


def _block_last(expr):
    """(last statement, {name: init}) of a `{ … }` block body."""
    body = expr[1:-1]
    stmts = [body[a:b].strip() for a, b in stmt_spans(body)]
    stmts = [s for s in stmts if s]
    if not stmts:
        return None, {}
    env = {}
    for s in stmts[:-1]:
        m = BINDING_RE.match(s)
        if m:
            env[m.group(1)] = s[m.end():]
    return stmts[-1], env


def _value_from_write(expr, env=None):
    """True when the VALUE of `expr` is derived from a repository write.

    `val ok = withContext(Dispatchers.IO) { …; true }` returns a constant: the
    write's boolean is discarded, `if (ok)` pops the screen and the user's edits
    vanish with no error (wide3-08 W1). wide4 closes two more holes in this
    walk: a trailing `|| true` on the write itself (w4-arm2), and the audited
    FALSE_POSITIVE `val saved = …; saved`, which must stay GREEN.
    """
    env = dict(env or {})
    e = strip_parens(expr.strip())
    while e.startswith("{") and match_bracket(e, 0) == len(e) - 1:
        last, inner = _block_last(e)
        if last is None:
            return False
        env.update(inner)
        e = strip_parens(last)
    if IDENT_ONLY.match(e) and e in env:
        return _value_from_write(env.pop(e), env)
    m = re.match(r"if\s*\(", e)
    if m:
        mp = _match_paren(e, m.end() - 1)
        if mp < 0:
            return False
        then_part, else_part = _split_else(e[mp + 1:])
        if not else_part.strip():
            # `if (c) X` without else evaluates to Unit — never the write result
            return False
        decided = const_bool(strip_parens(e[m.end():mp]))
        if decided is True:
            return _value_from_write(then_part, env)
        if decided is False:
            return _value_from_write(else_part, env)
        return _value_from_write(then_part, env) and _value_from_write(else_part, env)
    if const_bool(e) is not None:
        return False
    if re.fullmatch(r"(?:null|Unit|nothing)", e):
        return False
    for op in ("||", "&&"):
        parts = _split_top(e, op)
        if len(parts) > 1:
            return all(_value_from_write(p, env) for p in parts)
    return _leaf_is_write(e)


def _split_else(rest):
    """`then` / `else` halves of an `if (...) A else B` expression."""
    masked = mask_strings(rest)
    depth = 0
    i = 0
    while i < len(masked):
        c = masked[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and re.match(r"\belse\b", masked[i:]):
            return rest[:i], rest[i + 4:]
        i += 1
    return rest, ""


def skip_ws(text, idx):
    while idx < len(text) and text[idx] in " \t\r\n":
        idx += 1
    return idx


def screen_segment(repo_root):
    path = os.path.join(repo_root, TARGET_REL)
    with open(path, encoding="utf-8") as f:
        src = f.read()
    idx = src.find(SCREEN_ANCHOR)
    if idx < 0:
        raise SystemExit("FATAL: %s not found in %s" % (SCREEN_ANCHOR, TARGET_REL))
    # The screen is the last top-level function in the file.
    # [wide4] The projection is the shared lexer's `code` view (comments AND
    # string/char contents blanked, offsets preserved). A parse failure raises
    # Unparseable, which `main` reports as a violation: a gate that silently
    # judged the raw text is exactly how `"${"}"}"` promoted a dead `onBack()`.
    seg = mask_strings(src[idx:])
    return seg


def screen_source(repo_root):
    """Raw file text — the arms that need both projections (arm7-arm10)."""
    path = os.path.join(repo_root, TARGET_REL)
    with open(path, encoding="utf-8") as f:
        return f.read()


def balanced_block(text, after_idx, opener="{"):
    """Return the {...} block whose '{' is the first non-ws char at/after
    after_idx, or None."""
    i = skip_ws(text, after_idx)
    if i >= len(text) or text[i] != opener:
        return None
    close = find_matching_brace(text, i)
    if close < 0:
        return None
    return text[i : close + 1]


def block_span(text, after_idx, opener="{"):
    """(start, end) of the {...} block whose '{' is the first non-ws char
    at/after after_idx, or None."""
    i = skip_ws(text, after_idx)
    if i >= len(text) or text[i] != opener:
        return None
    close = find_matching_brace(text, i)
    if close < 0:
        return None
    return (i, close)


def enclosing_io_block(text, read_idx):
    """Span of the withContext(Dispatchers.IO) block containing read_idx."""
    idx = text.find("withContext(Dispatchers.IO)")
    while idx >= 0:
        open_paren = text.find("(", idx)
        close_paren = _match_paren(text, open_paren) if open_paren >= 0 else -1
        if close_paren < 0:
            return None
        span = block_span(text, close_paren + 1)
        if span and span[0] < read_idx <= span[1]:
            return span
        idx = text.find("withContext(Dispatchers.IO)", idx + 1)
    return None


def enclosing_launched_effect(text, read_idx):
    """Span of the LaunchedEffect block containing read_idx (paren-matched, so
    a nested call in the key list cannot truncate the search)."""
    idx = text.find("LaunchedEffect(")
    while idx >= 0:
        open_paren = text.find("(", idx)
        close_paren = _match_paren(text, open_paren) if open_paren >= 0 else -1
        if close_paren < 0:
            return None
        span = block_span(text, close_paren + 1)
        if span and span[0] < read_idx <= span[1]:
            return span
        idx = text.find("LaunchedEffect(", idx + 1)
    return None

def _enabled_expressions(text):
    """The value expressions of every `enabled = …` argument."""
    out = []
    masked = mask_strings(text)
    for m in re.finditer(r"\benabled\s*=", masked):
        i = m.end()
        depth = 0
        start = i
        while i < len(masked):
            c = masked[i]
            if c in "([{":
                depth += 1
            elif c in ")]}":
                if depth == 0:
                    break
                depth -= 1
            elif c == "," and depth == 0:
                break
            i += 1
        out.append(text[start:i].strip())
    return out

def _flag_decl_init(text, name):
    """The `mutableStateOf(<init>)` initialiser of `var <name> by remember…`."""
    m = re.search(
        r"\b(?:var|val)\s+%s\s*(?::[^=\n]+)?by\s+remember[^\n]*?"
        r"mutableStateOf\s*\(([^\n]*?)\)\s*\}" % re.escape(name), text)
    return None if m is None else m.group(1).strip()

def _live_gate_flag(text, expr):
    """True when `expr` gates on a LIVE async-load flag.

    A live flag is: (a) declared as state whose initial value is not a literal
    `true`/`false` (a flag seeded `mutableStateOf(true)` can never hold the
    editor back), (b) its value is not fixed by literals
    (`isLoaded || true` — wide3-08 W2 — and `isLoaded || (1 == 1)` — wide4
    w4-arm6c — both keep the spelling and delete the decision), and (c) it is
    actually flipped to `true` AFTER the read, inside the LaunchedEffect that
    performs the read.

    (c) was promised by this docstring since [dv14-B5] but never implemented:
    deleting `isLoaded = true` left the gate green, which is a gate that can
    never open.
    """
    e = strip_parens(expr).rstrip(",").strip()
    if not e or _literal_decides(e):
        return False
    read_idx = text.find("readSkillFile(")
    for name in sorted(set(re.findall(r"[A-Za-z_][A-Za-z0-9_]*", e))):
        init = _flag_decl_init(text, name)
        if init is None:
            continue
        # a flag seeded `mutableStateOf(true)` can never hold the editor back
        if _lit_value(init) is not None:
            continue
        if _flip_after_read(text, name, read_idx):
            return True
    return False


def _flip_after_read(text, name, read_idx):
    """`<name> = true` inside the LaunchedEffect that performs the read, after it.

    Returns False when such an effect exists but never flips the flag (the gate
    can never open), and False when no effect contains the read at all — the
    read then runs during composition, which arm6a/6b already reject; arm6c
    stays fail-closed rather than crediting an unproven flag.
    """
    if read_idx < 0:
        return False
    idx = text.find("LaunchedEffect(")
    while idx >= 0:
        open_paren = text.find("(", idx)
        close_paren = _match_paren(text, open_paren) if open_paren >= 0 else -1
        if close_paren < 0:
            return False
        span = block_span(text, close_paren + 1)
        if span and span[0] < read_idx <= span[1]:
            blk = text[span[0]:span[1] + 1]
            for m in re.finditer(r"\b" + re.escape(name) + r"\s*=\s*([^\n]+)", blk):
                rhs = m.group(1).split("}")[0]
                if span[0] + m.start() > read_idx and _lit_value(rhs) is True:
                    return True
            return False
        idx = text.find("LaunchedEffect(", idx + 1)
    return False


def run_offmain_checks(seg):
    """[dv14-B5] arm6: the sibling-file disk read rides IO off the
    composition thread, and the Edit button is gated until it lands.
    Independent of run_checks (different fixtures) so a refactor of the save
    path cannot hollow these criteria and vice versa."""
    failures = []
    read_idx = seg.find("readSkillFile(")

    # arm6a: the read sits inside a withContext(Dispatchers.IO) block
    arm_a = enclosing_io_block(seg, read_idx) is not None
    if not arm_a:
        failures.append(
            "arm6a: skillRepository.readSkillFile( must sit inside a "
            "withContext(Dispatchers.IO) block, not in the remember {} composition lambda"
        )

    # arm6b: the read is triggered from a LaunchedEffect block
    arm_b = enclosing_launched_effect(seg, read_idx) is not None
    if not arm_b:
        failures.append(
            "arm6b: the readSkillFile( read must be triggered from a LaunchedEffect block"
        )

    # arm6c: the Edit button is gated on a LIVE async-load flag.
    # [wide3-08 W2] `enabled = isLoaded` as a SUBSTRING is satisfied by
    # `enabled = isLoaded || true` — the spelling survives, the decision does
    # not. Parse the argument's value and require a real state gate.
    enabled = _enabled_expressions(seg)
    if not enabled:
        failures.append("arm6c: no `enabled = …` argument found on the Edit button "
                        "(the gate lost its subject — fail-closed)")
    elif not any(_live_gate_flag(seg, e) for e in enabled):
        failures.append(
            "arm6c: the Edit button must be gated on a live async-load flag "
            "(a state var seeded from the load and flipped inside the "
            "LaunchedEffect) — got enabled = %s; a constant or literal-short-"
            "circuited gate lets the editor open against a blank base"
            % " / ".join(enabled))

    return failures


def _unguarded_pop(seg, guard_span):
    r"""True when a pop runs OUTSIDE the `if (ok)` guard but inside the same save
    callback (an unconditional pop, wide3-08 W3).

    [wide4 w4-arm3] `onBack` is a `() -> Unit` parameter, so `onBack.invoke()` is
    the same call in different spelling and used to walk straight past the old
    `\bonBack\s*\(\s*\)` pattern.
    """
    masked = mask_strings(seg)
    for m in re.finditer(r"\bonBack\s*(?:\(\s*\)|\.\s*invoke\s*\(\s*\))", masked):
        i = m.start()
        if guard_span[0] <= i <= guard_span[1]:
            continue
        for lm in re.finditer(r"\b(?:scope\s*\.\s*)?launch\s*\{", seg):
            brace = seg.index("{", lm.end() - 1)
            close = find_matching_brace(seg, brace)
            if close < 0:
                continue
            if brace < i < close:
                return True
    return False

def run_checks(seg):
    failures = []

    # arm1: importFromContent consumed with `) != null`
    arm1 = False
    pos = seg.find(CONSUME_IMPORT)
    if pos >= 0:
        close = seg.find(")", seg.find("(", pos))
        if close >= 0:
            nxt = skip_ws(seg, close + 1)
            arm1 = seg.startswith("!=", nxt)
    if not arm1:
        failures.append("arm1: importFromContent( call is not consumed with ') != null'")

    # arm2: save is bound via `val ok = withContext(...) { ... }` and both
    # importFromContent and writeSkillFile live in that block. (The IO
    # dispatcher itself is arm4's contract, so a dispatcher-only mutation
    # flips exactly arm4.) [dv14-B5] Discovery anchors on the `val ok =`
    # binding + the withContext immediately following it, not the first
    # withContext in the screen: the async-read withContext inside the
    # LaunchedEffect ([dv14-B5]) must not shadow the save's context.
    arm2 = False
    idx_ok = seg.find(OK_BINDING)
    if idx_ok >= 0:
        ctx = seg.find("withContext(", idx_ok)
        if ctx >= 0 and ctx - (idx_ok + len(OK_BINDING)) <= 8:
            open_paren = seg.find("(", ctx)
            close_paren = _match_paren(seg, open_paren)
            blk = balanced_block(seg, close_paren + 1) if close_paren >= 0 else None
            # [wide3-08 W1] the binding must receive a value that IS the
            # write's result. `... } ; true` inside the block makes `val ok` a
            # constant while every presence check above still passes. (The
            # dispatcher itself stays arm4's contract, now read from THIS
            # binding instead of "somewhere in the screen".)
            arm2 = blk is not None \
                and CONSUME_IMPORT in blk and "writeSkillFile(" in blk \
                and _value_from_write(blk)
    if not arm2:
        failures.append(
            "arm2: save must run inside 'val ok = withContext(Dispatchers.IO) { ... }' "
            "with both importFromContent and writeSkillFile in the block, and the "
            "block's VALUE must be the write result (a trailing constant such as "
            "`true` makes `if (ok)` unconditional)"
        )

    # arm3: if (ok) guards the exit; no unconditional pop.
    # [wide3-08 W3] judged on STATEMENTS of the guard block, with string
    # literals masked: `val _dead = "isEditing = false; onBack()"` is not an
    # exit, and an `onBack()` hoisted above the guard is an unconditional pop
    # even though the guard block still spells both.
    # [wide4 w4-arm3] the guard must be `if (ok)` exactly (a constant condition
    # decides as a literal), and the pop may be spelled `onBack.invoke()`.
    arm3 = False
    guard_m = GUARD_RE.search(seg)
    if guard_m:
        span = block_span(seg, guard_m.end())
        blk = seg[span[0]:span[1] + 1] if span else None
        exit_assigns = seg.count(EXIT_ASSIGN)
        if blk is not None:
            stmts = _statements(blk[1:-1])
            has_assign = any(s.startswith(EXIT_ASSIGN) for s in stmts)
            has_call = any(EXIT_CALL_RE.match(_norm(s)) for s in stmts)
            arm3 = has_assign and has_call and exit_assigns == 1 \
                and not _unguarded_pop(seg, span)
    if not arm3:
        failures.append(
            "arm3: 'if (ok)' must guard isEditing=false + onBack(), and "
            "'isEditing = false' must occur exactly once in the screen"
        )

    # arm4: the SAVE binding rides Dispatchers.IO.
    # [wide3-08 W1 self-note] presence of any `withContext(Dispatchers.IO)` in
    # the screen is not the decision: the async-read block already provides one,
    # so flipping the SAVE block to Main stayed green. The dispatcher is read
    # from the `val ok =` binding's own call.
    arm4 = False
    idx_ok4 = seg.find(OK_BINDING)
    if idx_ok4 >= 0:
        ctx4 = seg.find("withContext(", idx_ok4)
        if ctx4 >= 0 and ctx4 - (idx_ok4 + len(OK_BINDING)) <= 8:
            op4 = seg.find("(", ctx4)
            cp4 = _match_paren(seg, op4) if op4 >= 0 else -1
            arm4 = cp4 > 0 and "Dispatchers.IO" in seg[op4 + 1:cp4]
    if not arm4:
        failures.append("arm4: withContext(Dispatchers.IO) missing from the screen")

    # arm5: preferredId pin survives
    arm5 = PREFERRED_ID in seg
    if not arm5:
        failures.append("arm5: preferredId = skillId pin missing (audit-1015-b4-2 contract)")

    return failures


# ── [R3-t19] round-3 (dual-gate bypass) arms ───────────────────────────────
# The round-3 audit attacked the SEAMS around this gate instead of its arms:
# every mutation below kept all eight arms above green while the user-visible
# outcome flipped. Each hole is answered by a criterion that follows the value
# or the decision, never the spelling.
#
#   arm7  `skillRepository.update(…)` must carry the PARSED document's
#         name/description/body. Witness 47: passing the record being replaced
#         writes the old text back and still reports `UpdateStatus.Done`.
#   arm8  `updateFromURL(…)`'s result must drive the status through a branch
#         that still has a reachable failure path. Witness 46: an
#         unconditional `Done` turns a 404 into a green check.
#   arm9  the viewer's async-load early exit must not skip SIBLING files.
#         Witness 48: `if (!isSkillMd) return@LaunchedEffect` (inverted
#         polarity) never reads the disk for a script, so the viewer is blank
#         and Edit stays disabled forever.
#   arm10 the Edit button must SEED the draft from the loaded base. Witness
#         45's second half: `editContent = ""` opens a blank editor on a
#         loaded file, and Save writes the blank back over the real file.
#
# arm9/arm10 are the dataflow siblings of arm6: arm6 proves the read lands,
# these prove the landed value is reachable (arm9) and actually used (arm10).

PARSE_CALL = "parseSkillMdPublic("
UPDATE_CALL = "skillRepository.update("
UPDATE_FIELDS = ("name", "description", "body")
URL_CALL = "updateFromURL("
SEED_SOURCES = ("initialContent", "loadedDiskBase", "skillFileViewerBase")
MD_LITERAL = "SKILL.md"
DONE = "UpdateStatus.Done"
FAILED = "UpdateStatus.Failed"
SUCCESS_WORDS = ("Success",)
FAILURE_WORDS = ("Failure", "PartialSuccess")


def _mentions(text, ident):
    """True when `ident` appears in `text` as a whole identifier."""
    return re.search(r"(?<![\w.`])" + re.escape(ident) + r"(?![\w`])",
                     text) is not None


def _fold_with(cond, env):
    """Boolean value of `cond` with boolean identifiers substituted from env.

    The viewer's "is this SKILL.md" flag is a local whose initialiser names the
    literal; substituting it is how the gate asks the question that matters —
    *what happens for a sibling file* — instead of "is the string present".
    Returns None when the condition still cannot be decided (caller is
    fail-closed).
    """
    c = cond
    for name, val in env.items():
        c = re.sub(r"(?<![\w.`])" + re.escape(name) + r"(?![\w`])",
                   "true" if val else "false", c)
    return _fold_bool(strip_parens(c))


def _named_args(code, open_idx):
    """{name: expr} of the named arguments of the call whose '(' is open_idx."""
    out = {}
    for a, b in call_args(code, open_idx):
        seg = code[a:b]
        m = re.match(r"\s*([A-Za-z_]\w*)\s*=(?![=>])\s*", seg)
        if m:
            out.setdefault(m.group(1), seg[m.end():].strip())
    return out


def _binding_before(code, idx):
    """Name bound by the `val/var <name> = <receiver>.` immediately before idx.

    Used to recover the identifier a call's result was bound to, whether the
    binding is a plain `val r = …` or Kotlin's `when (val r = …)`.
    """
    if idx < 0:
        return None
    for m in reversed(list(re.finditer(
            r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]{0,60})?=(?!=)",
            code[:idx]))):
        between = code[m.end():idx]
        if "\n" in between.strip():
            return None
        if RECEIVER_PREFIX.match(between.strip()):
            return m.group(1)
    return None


def _enclosing_block(code, pos):
    """(start, end) of the innermost {...} block containing pos."""
    depth = 0
    i = min(pos, len(code) - 1)
    while i >= 0:
        ch = code[i]
        if ch == "}":
            depth += 1
        elif ch == "{":
            if depth == 0:
                close = match_bracket(code, i)
                return i, (close if close > 0 else len(code) - 1)
            depth -= 1
        i -= 1
    return 0, len(code) - 1


def run_update_carry_checks(code):
    """arm7: a file update must carry the parsed document, not the old record.

    The judged subject is derived, not listed: the `skillRepository.update(`
    call that lives in the same block as the `parseSkillMdPublic(` binding. The
    rename dialog's `update(skill.id, name = trimmed)` is a different decision
    (it never claims to apply a picked document) and stays out of scope.
    """
    failures = []
    sites = [m.start() for m in re.finditer(re.escape(UPDATE_CALL), code)]
    if not sites or PARSE_CALL not in code:
        return failures
    binding = _binding_before(code, code.find(PARSE_CALL))
    if binding is None:
        return [
            "arm7: `parseSkillMdPublic(` is called but its result is not bound "
            "(`val <name> = …`) — the picked document cannot be traced into the "
            "update (fail-closed)"
        ]
    b_start, b_end = _enclosing_block(code, code.find(PARSE_CALL))
    in_block = [s for s in sites if b_start < s < b_end]
    if not in_block:
        return [
            "arm7: no `skillRepository.update(` call in the block that parses the "
            "picked SKILL.md — the parsed document never reaches the record "
            "(the file update would report Done and change nothing)"
        ]
    for site in in_block:
        args = _named_args(code, site + len(UPDATE_CALL) - 1)
        for field in UPDATE_FIELDS:
            expr = args.get(field)
            if not expr:
                failures.append(
                    "arm7: skillRepository.update( is called without `%s = …` — the "
                    "parsed %s never reaches the record, so a file update rewrites "
                    "the old text and still reports Done" % (field, field))
                continue
            srcs = value_sources(expr)
            if not any(_mentions(s, binding) or PARSE_CALL in s for s in srcs):
                failures.append(
                    "arm7: skillRepository.update(%s = %s) does not carry the parsed "
                    "document (expected a value derived from `%s`) — the picked "
                    "SKILL.md would be discarded behind a green UpdateStatus.Done"
                    % (field, _norm(expr)[:90], binding))
    return failures


def run_url_result_checks(code):
    """arm8: the URL update's UpdateResult must drive the status mapping."""
    failures = []
    idx = code.find(URL_CALL)
    if idx < 0:
        return [
            "arm8: no `…updateFromURL(` call site in the screen — the 'update from "
            "URL' entry point lost its subject (fail-closed; swapping the API is a "
            "deliberate gate edit)"
        ]

    def _branches(pairs):
        ok_done = any(any(w in c for w in SUCCESS_WORDS) and DONE in b
                      for c, b in pairs)
        ok_fail = any(any(w in c for w in FAILURE_WORDS) and FAILED in b
                      for c, b in pairs)
        return ok_done, ok_fail

    direct = [(cond.strip(), code[bs:be])
              for subject, cond, bs, be in when_branches(code)
              if URL_CALL in subject and bs > 0]
    if direct:
        ok_done, ok_fail = _branches(direct)
    else:
        binding = _binding_before(code, idx)
        if binding is None:
            return [
                "arm8: the result of updateFromURL( is discarded — neither "
                "`when (val r = …)` nor a `val r = …` binding follows, so the "
                "status cannot depend on Success / PartialSuccess / Failure "
                "(a 404 would render as a green check)"
            ]
        pairs = []
        for subject, cond, bs, be in when_branches(code):
            if _mentions(subject, binding) and bs > 0:
                pairs.append((cond.strip(), code[bs:be]))
        for cond, t_start, t_end, e_start, e_end in if_blocks(code):
            if not _mentions(cond, binding):
                continue
            pairs.append((cond, code[t_start:t_end]))
            if e_start is not None:
                pairs.append(("!" + cond, code[e_start:e_end]))
        ok_done, ok_fail = _branches(pairs)
    if not ok_done:
        failures.append(
            "arm8: no branch keyed on %s maps to %s — the completion check is not "
            "tied to the repository's verdict" % ("Success", DONE))
    if not ok_fail:
        failures.append(
            "arm8: no branch keyed on %s maps to %s — a 404 / rate limit / broken "
            "remote SKILL.md would render as a green 'updated' check"
            % (" / ".join(FAILURE_WORDS), FAILED))
    # the same defect in one line: `updateStatus = UpdateStatus.Done` outside any
    # Success branch is an unconditional success report
    for m in re.finditer(r"updateStatus\s*=\s*" + re.escape(DONE), code):
        conds = [c for _kind, c in enclosing_conditions(code, m.start())]
        if not any(any(w in c for w in SUCCESS_WORDS) for c in conds):
            failures.append(
                "arm8: `updateStatus = UpdateStatus.Done` sits outside any Success "
                "branch — the green check is unconditional (the update result is "
                "ignored)")
            break
    return failures


def _enclosing_call(code, name, pos):
    """(start, close) of the innermost `name(…)` call enclosing pos.

    The span includes a trailing lambda (`Button(…) { … }`), because a Compose
    label usually lives in the lambda, not in the parenthesised arguments.
    """
    start = code.rfind(name, 0, pos)
    while start >= 0:
        close = match_bracket(code, start + len(name) - 1)
        if close > 0:
            j = skip_ws(code, close + 1)
            if j < len(code) and code[j] == "{":
                brace = match_bracket(code, j)
                if brace > pos:
                    return start, brace
        if close > pos:
            return start, close
        start = code.rfind(name, 0, start)
    return None


def _one_hop(code, expr):
    """The initialiser when `expr` is a bare local identifier (one hop)."""
    ident = expr.strip()
    if not re.fullmatch(r"[A-Za-z_]\w*", ident):
        return None
    m = None
    for m2 in re.finditer(r"\b(?:val|var)\s+" + re.escape(ident)
                          + r"\s*(?::[^=\n]{0,60})?=(?!=)", code):
        m = m2
    if m is None:
        return None
    return code[m.end():expr_end(code, m.end())].strip()


def _derives_from_base(code, expr):
    """True when `expr` (or the local it names) is built from the loaded base."""
    sources = value_sources(expr)
    if any(_mentions(s, name) for s in sources for name in SEED_SOURCES):
        return True
    hop = _one_hop(code, expr)
    if hop is None:
        return False
    return any(_mentions(s, name) for s in value_sources(hop)
               for name in SEED_SOURCES)


def run_seed_checks(text):
    """arm9 (sibling path reaches the read) + arm10 (draft seeded from base)."""
    views = _views(text)
    code, nc = views["code"], views["comments"]
    idx = code.find(SCREEN_ANCHOR)
    if idx < 0:
        return ["arm9/arm10: %s not found in the screen — fail-closed"
                % SCREEN_ANCHOR]
    seg, nc_seg = code[idx:], nc[idx:]
    failures = []

    # arm9: what does this screen mean by "this file is SKILL.md"?
    md_m = re.search(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]{0,40})?=\s*[^\n]*"
                     + re.escape(MD_LITERAL), nc_seg)
    if md_m is None:
        failures.append(
            "arm9: the screen no longer binds a `… = <expr naming \"SKILL.md\">` flag "
            "— the sibling-file path cannot be decided (fail-closed)")
    else:
        md = md_m.group(1)
        for cond, t_start, t_end, e_start, e_end in if_blocks(seg):
            if "return@LaunchedEffect" not in seg[t_start:t_end]:
                continue
            val = _fold_with(cond, {md: False})
            if val is None:
                failures.append(
                    "arm9: the LaunchedEffect early exit `if (%s) "
                    "return@LaunchedEffect` cannot be decided for a SIBLING file "
                    "(fail-closed)" % _norm(cond))
            elif val is True:
                failures.append(
                    "arm9: `if (%s) return@LaunchedEffect` takes the early exit when "
                    "`%s` is false — a sibling file never reaches the disk read, so "
                    "`isLoaded` never flips, the viewer stays blank and Edit stays "
                    "disabled for every script/reference file" % (_norm(cond), md))

    # arm10: the Edit button must seed the draft from the loaded base.
    label = seg.find("R.string.skill_file_edit")
    if label < 0:
        failures.append("arm10: no Edit button (`R.string.skill_file_edit`) found in "
                        "the viewer — fail-closed")
    else:
        span = _enclosing_call(seg, "MinisTextButton(", label)
        if span is None:
            failures.append("arm10: the Edit label is not inside a MinisTextButton( "
                            "call — fail-closed")
        else:
            btn = seg[span[0]:span[1] + 1]
            writes = list(re.finditer(r"\beditContent\s*=(?!=)", btn))
            if not writes:
                failures.append(
                    "arm10: the Edit button never seeds `editContent` — the editor "
                    "opens on whatever the draft held last time")
            for m in writes:
                rhs = btn[m.end():expr_end(btn, m.end())].strip()
                if not _derives_from_base(seg, rhs):
                    failures.append(
                        "arm10: the Edit button seeds `editContent = %s`, which does "
                        "not come from the loaded base (%s) — the editor opens "
                        "blank/stale and Save writes that back over the real file"
                        % (_norm(rhs)[:70], " / ".join(SEED_SOURCES)))
    return failures


def run_round3_checks(text):
    """Every round-3 arm on one raw file text (arm7-arm10)."""
    code = _views(text)["code"]
    return (run_update_carry_checks(code) + run_url_result_checks(code)
            + run_seed_checks(text))


# -- selftest fixtures (trimmed mirror of the fixed screen) --

COMPLIANT = """fun SkillFileViewerScreen(skillId: String, relativePath: String, onBack: () -> Unit) {
    var isEditing by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            actions = {
                if (isEditing) {
                    MinisTextButton(onClick = {
                        if (isSaving) return@MinisTextButton
                        isSaving = true
                        val contentToSave = editContent
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                if (isSkillMd) {
                                    skillRepository.importFromContent(
                                        contentToSave,
                                        skill.importSource,
                                        preferredId = skillId,
                                    ) != null
                                } else {
                                    skillRepository.writeSkillFile(skillId, relativePath, contentToSave)
                                }
                            }
                            isSaving = false
                            if (ok) {
                                isEditing = false
                                onBack()
                            } else {
                                snackbarHostState.showSnackbar(invalidFormatMsg)
                            }
                        }
                    }) { Text(stringResource(R.string.skill_file_save)) }
                } else {
                    MinisTextButton(onClick = {
                        editContent = initialContent
                        isEditing = true
                    }) { Text(stringResource(R.string.skill_file_edit)) }
                }
            }
        },
    ) { padding -> if (isEditing) { DialogTextField() } else { LazyColumn() } }
}
"""

# V1: bare importFromContent call (result dropped) -> arm1 red
V1 = COMPLIANT.replace(
    """skillRepository.importFromContent(
                                        contentToSave,
                                        skill.importSource,
                                        preferredId = skillId,
                                    ) != null""",
    """skillRepository.importFromContent(
                                        contentToSave,
                                        skill.importSource,
                                        preferredId = skillId,
                                    )""",
)

# V2: writeSkillFile hoisted out of the withContext block (synchronous save
# outside) -> arm2 red only
V2 = COMPLIANT.replace(
    """                                } else {
                                    skillRepository.writeSkillFile(skillId, relativePath, contentToSave)
                                }""",
    """                                } else {
                                    false
                                }""",
).replace(
    "val contentToSave = editContent",
    """val contentToSave = editContent
                        skillRepository.writeSkillFile(skillId, relativePath, contentToSave)""",
)

# V3: unconditional pop (no if (ok) guard) -> arm3 red
V3 = COMPLIANT.replace(
    """if (ok) {
                                isEditing = false
                                onBack()
                            } else {""",
    """if (ok) {
                                snackbarHostState.showSnackbar(invalidFormatMsg)
                            }
                            isEditing = false
                            onBack()
                            if (ok) {""",
)

# V4: synchronous main-thread save (no Dispatchers.IO) -> arm4 red only
V4 = COMPLIANT.replace("withContext(Dispatchers.IO)", "withContext(Dispatchers.Default)")

# V5: preferredId pin dropped -> arm5 red only
V5 = COMPLIANT.replace("preferredId = skillId,", 'preferredId = "other",')


# -- [dv14-B5] arm6 fixtures (trimmed mirror of the async-read wiring) --

OFFMAIN_COMPLIANT = """fun SkillFileViewerScreen(skillId: String, relativePath: String, onBack: () -> Unit) {
    val isSkillMd = relativePath.equals("SKILL.md", ignoreCase = true)
    val initialContent = remember(skillId, relativePath, skill.updatedAt) {
        if (isSkillMd) { SkillRepository.mergeFrontmatter() } else { "" }
    }
    var loadedDiskBase by remember(skillId, relativePath, skill.updatedAt) { mutableStateOf<String?>(null) }
    var isLoaded by remember(skillId, relativePath, skill.updatedAt) { mutableStateOf(isSkillMd) }
    LaunchedEffect(skillId, relativePath, skill.updatedAt) {
        if (isSkillMd) return@LaunchedEffect
        loadedDiskBase = withContext(Dispatchers.IO) {
            skillRepository.readSkillFile(skillId, relativePath) ?: ""
        }
        isLoaded = true
    }
    var isEditing by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            actions = {
                if (isEditing) {
                    MinisTextButton(onClick = { }) { Text(stringResource(R.string.skill_file_save)) }
                } else {
                    MinisTextButton(
                        enabled = isLoaded,
                        onClick = {
                            editContent = initialContent
                            isEditing = true
                        },
                    ) { Text(stringResource(R.string.skill_file_edit)) }
                }
            }
        },
    ) { padding -> if (isEditing) { DialogTextField() } else { LazyColumn() } }
}
"""

# W1: read moved back into the remember lambda (sync main-thread read,
# no LaunchedEffect) -> arm6a + arm6b red, arm6c green
OFFMAIN_W1 = OFFMAIN_COMPLIANT.replace(
    """    LaunchedEffect(skillId, relativePath, skill.updatedAt) {
        if (isSkillMd) return@LaunchedEffect
        loadedDiskBase = withContext(Dispatchers.IO) {
            skillRepository.readSkillFile(skillId, relativePath) ?: ""
        }
        isLoaded = true
    }
""",
    """    loadedDiskBase = remember(skillId, relativePath, skill.updatedAt) {
        skillRepository.readSkillFile(skillId, relativePath) ?: ""
    }
""",
)

# W2: read rides Dispatchers.Default instead of IO -> arm6a red only
OFFMAIN_W2 = OFFMAIN_COMPLIANT.replace(
    "withContext(Dispatchers.IO)", "withContext(Dispatchers.Default)"
)

# W3: Edit gate dropped -> arm6c red only
OFFMAIN_W3 = OFFMAIN_COMPLIANT.replace("enabled = isLoaded,", "enabled = true,")

# ── [wide3-08] witnesses re-attacked from the external audit ────────────────
# W1' (arm2, axis=dataflow): a trailing `true` inside the withContext block
# makes `val ok` a constant — the write's boolean is discarded while every
# presence anchor above still passes.
SAVE_W1_TAIL_TRUE = COMPLIANT.replace(
    "skillRepository.writeSkillFile(skillId, relativePath, contentToSave)\n"
    "                                }\n"
    "                            }",
    "skillRepository.writeSkillFile(skillId, relativePath, contentToSave)\n"
    "                                }\n"
    "                                true\n"
    "                            }",
)

# W3' (arm3, axis=parser-death): string literals kept the guard block "green"
# while `onBack()` was hoisted out of the guard (an unconditional pop).
SAVE_W3_LITERAL = COMPLIANT.replace(
    "                            if (ok) {\n"
    "                                isEditing = false\n"
    "                                onBack()\n"
    "                            } else {",
    "                            onBack()\n"
    "                            if (ok) {\n"
    "                                val _dead = \"isEditing = false; onBack()\"\n"
    "                            } else {",
)

# W2' (arm6c, axis=dataflow): the gate's spelling survives, the decision does
# not (`isLoaded || true` is constantly true).
SAVE_W2_OR_TRUE = OFFMAIN_COMPLIANT.replace(
    "enabled = isLoaded,", "enabled = isLoaded || true,")

# ── [wide4] witnesses re-attacked from the external audit ───────────────────
# w4-arm2 (dataflow): the write's value is short-circuited on the SAME
# expression, so the block's tail statement is still the write call — wide3's
# trailing-`true`-statement arm cannot see it.
SAVE_W4_OR_TRUE = COMPLIANT.replace(
    "skillRepository.writeSkillFile(skillId, relativePath, contentToSave)\n",
    "skillRepository.writeSkillFile(skillId, relativePath, contentToSave) || true\n")

# w4-arm2 (dataflow): a scope function replaces the write's value.
SAVE_W4_LET_TRUE = COMPLIANT.replace(
    "skillRepository.writeSkillFile(skillId, relativePath, contentToSave)\n",
    "skillRepository.writeSkillFile(skillId, relativePath, contentToSave).let { true }\n")

# w4-arm3 (family-hole): `onBack` is a function VALUE, so `.invoke()` is the
# same pop in a spelling the old `onBack()` pattern never matched.
SAVE_W4_INVOKE_POP = COMPLIANT.replace(
    """if (ok) {
                                isEditing = false
                                onBack()
                            } else {""",
    """onBack.invoke()
                            if (ok) {
                                isEditing = false
                                onBack()
                            } else {""")

# w4-arm3 (parser-death): a `${…}` template inside the guard block used to leak
# its inner `}` into the projection, split the block early and promote the dead
# `onBack()` under `if (false)` to a top-level statement of the guard.
SAVE_W4_TEMPLATE_BRACE = COMPLIANT.replace(
    """if (ok) {
                                isEditing = false
                                onBack()
                            } else {""",
    '''if (ok) {
                                isEditing = false
                                if (false) {
                                    val _s = "${"}"}"
                                    onBack()
                                }
                            } else {''')

# w4-arm6c (family-hole): a constant comparison in place of the literal `true`.
SAVE_W4_CONST_COMPARE = OFFMAIN_COMPLIANT.replace(
    "enabled = isLoaded,", "enabled = isLoaded || (1 == 1),")

# w4-arm6c (dataflow): the flag is declared and gated on but never flipped —
# the gate can never open, which the unaudited docstring promised to catch.
SAVE_W4_FLAG_NEVER_FLIPPED = OFFMAIN_COMPLIANT.replace("        isLoaded = true\n", "")

# w4-arm2 GREEN control: the audited FALSE_POSITIVE. The block's value is
# carried by a local (`val saved = …; saved`) — an indirection, not a constant,
# and a legitimate refactor the tightened leaf test must keep accepting.
SAVE_W4_OK_SAVED = COMPLIANT.replace(
    "                                if (isSkillMd) {\n",
    "                                val saved = if (isSkillMd) {\n",
).replace(
    "skillRepository.writeSkillFile(skillId, relativePath, contentToSave)\n"
    "                                }\n"
    "                            }\n",
    "skillRepository.writeSkillFile(skillId, relativePath, contentToSave)\n"
    "                                }\n"
    "                                saved\n"
    "                            }\n")

assert SAVE_W1_TAIL_TRUE != COMPLIANT, "wide3-08 W1 fixture anchor missing"
assert SAVE_W3_LITERAL != COMPLIANT, "wide3-08 W3 fixture anchor missing"
assert SAVE_W2_OR_TRUE != OFFMAIN_COMPLIANT, "wide3-08 W2 fixture anchor missing"
assert SAVE_W4_OR_TRUE != COMPLIANT, "wide4 arm2 fixture anchor missing"
assert SAVE_W4_LET_TRUE != COMPLIANT, "wide4 arm2 let fixture anchor missing"
assert SAVE_W4_INVOKE_POP != COMPLIANT, "wide4 arm3 fixture anchor missing"
assert SAVE_W4_TEMPLATE_BRACE != COMPLIANT, "wide4 arm3 template fixture anchor missing"
assert SAVE_W4_CONST_COMPARE != OFFMAIN_COMPLIANT, "wide4 arm6c fixture anchor missing"
assert SAVE_W4_FLAG_NEVER_FLIPPED != OFFMAIN_COMPLIANT, "wide4 arm6c flip fixture anchor missing"
assert SAVE_W4_OK_SAVED != COMPLIANT, "wide4 saved-indirection fixture anchor missing"

# ── [R3-t19] round-3 (dual-gate) fixtures ────────────────────────────────
# Trimmed mirror of `SkillDetailScreen`'s two writers (picked file + URL) plus
# the viewer scene above. Every fixture carries BOTH scenes so each arm keeps a
# subject, and each lambda asserts its own splice before the gate sees it.
DETAIL_OK = """fun SkillDetailScreen(skill: Skill) {
    val fileUpdateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val content = String(bytes, Charsets.UTF_8)
                    if (content.isBlank()) {
                        false
                    } else {
                        val parsed = skillRepository.parseSkillMdPublic(content)
                        if (parsed == null) {
                            false
                        } else {
                            skillRepository.update(
                                id = skill.id,
                                name = parsed.name,
                                description = parsed.description,
                                body = parsed.body,
                            )
                        }
                    }
                } catch (e: Exception) {
                    false
                }
            }
            updateStatus = if (ok) UpdateStatus.Done else UpdateStatus.Failed("x")
        }
    }
    DetailRow(clickable = !isBusy, onClick = {
        scope.launch {
            updateStatus = when (val r = skillRepository.updateFromURL(skill.id)) {
                is SkillRepository.UpdateResult.Success -> UpdateStatus.Done
                is SkillRepository.UpdateResult.PartialSuccess -> UpdateStatus.Failed("Updated, but: ${r.reason}")
                is SkillRepository.UpdateResult.Failure -> UpdateStatus.Failed(r.reason)
            }
        }
    })
}
"""
URL_WHEN_BLOCK = """            updateStatus = when (val r = skillRepository.updateFromURL(skill.id)) {
                is SkillRepository.UpdateResult.Success -> UpdateStatus.Done
                is SkillRepository.UpdateResult.PartialSuccess -> UpdateStatus.Failed("Updated, but: ${r.reason}")
                is SkillRepository.UpdateResult.Failure -> UpdateStatus.Failed(r.reason)
            }"""


def _detail(replace=None):
    """The round-3 scene: the detail screen's two writers + the viewer screen.

    Both scenes are always present so every arm keeps its subject; a fixture
    that dropped the viewer would turn arm9/arm10 into fail-closed noise.
    """
    text = DETAIL_OK
    if replace:
        old, new = replace
        assert old in text, "detail fixture lost its anchor: %r" % (old,)
        text = text.replace(old, new)
    return text + "\n" + OFFMAIN_COMPLIANT


R3_SCENE = _detail()
R3_POLARITY = _detail().replace(
    "if (isSkillMd) return@LaunchedEffect",
    "if (!isSkillMd) return@LaunchedEffect")
R3_BLANK_SEED = _detail().replace(
    "editContent = initialContent", 'editContent = ""')
R3_FIXTURES = [
    ("round3 scene is fully compliant", R3_SCENE, []),
    ("arm7 update carries the OLD record", _detail((
        "name = parsed.name,\n                                description = parsed.description,\n"
        "                                body = parsed.body,",
        "name = skill.name,\n                                description = skill.description,\n"
        "                                body = skill.body,")), ["arm7"]),
    ("arm7 update drops the body field", _detail((
        "                                body = parsed.body,\n", "")), ["arm7"]),
    ("arm8 URL result discarded (unconditional Done)", _detail((
        URL_WHEN_BLOCK,
        "            updateStatus = UpdateStatus.Done\n"
        "            skillRepository.updateFromURL(skill.id)")), ["arm8"]),
    ("arm8 failure branch removed", _detail((
        '                is SkillRepository.UpdateResult.PartialSuccess -> UpdateStatus.Failed("Updated, but: ${r.reason}")\n'
        "                is SkillRepository.UpdateResult.Failure -> UpdateStatus.Failed(r.reason)\n",
        "")), ["arm8"]),
    ("arm9 polarity inverted (sibling never read)", R3_POLARITY, ["arm9"]),
    ("arm10 editor seeded with a blank draft", R3_BLANK_SEED, ["arm10"]),
]


def selftest():
    failures = []
    # compliant fixture: all arms green
    f = run_checks(mask_strings(COMPLIANT))
    if f:
        failures.append("compliant fixture unexpectedly red: %s" % f)
    # each violation fixture flips exactly its own arm red
    expectations = [("V1", V1, "arm1"), ("V2", V2, "arm2"), ("V3", V3, "arm3"),
                    ("V4", V4, "arm4"), ("V5", V5, "arm5")]
    all_arms = ["arm1", "arm2", "arm3", "arm4", "arm5"]
    for name, fixture, arm in expectations + [
        ("W1'-tail-true", SAVE_W1_TAIL_TRUE, "arm2"),
        ("W3'-literal-pop", SAVE_W3_LITERAL, "arm3"),
        ("w4-arm2-or-true", SAVE_W4_OR_TRUE, "arm2"),
        ("w4-arm2-let-true", SAVE_W4_LET_TRUE, "arm2"),
        ("w4-arm3-invoke-pop", SAVE_W4_INVOKE_POP, "arm3"),
        ("w4-arm3-template-brace", SAVE_W4_TEMPLATE_BRACE, "arm3"),
    ]:
        got = run_checks(mask_strings(fixture))
        red = [a for a in all_arms if any(a in x for x in got)]
        if red != [arm]:
            failures.append("%s: expected exactly [%s] red, got %s" % (name, arm, red))
    # wide4 GREEN control: an indirection through a local keeps the contract.
    got = run_checks(mask_strings(SAVE_W4_OK_SAVED))
    if got:
        failures.append("w4-ok-saved-indirection: a `val saved = …; saved` block value must "
                        "stay green, got %s" % got)
    # [dv14-B5] arm6 fixtures: offmain compliant green + per-mutation teeth
    f6 = run_offmain_checks(mask_strings(OFFMAIN_COMPLIANT))
    if f6:
        failures.append("offmain compliant fixture unexpectedly red: %s" % f6)
    off_expectations = [
        # [wide4] arm6c now also proves the flag is FLIPPED after the read (the
        # docstring promised it since [dv14-B5]); removing the whole
        # LaunchedEffect leaves the flag stuck at its seed, so W1 trips all
        # three arms — the read moved into composition AND the gate can never
        # open.
        ("W1", OFFMAIN_W1, ["arm6a", "arm6b", "arm6c"]),
        ("W2", OFFMAIN_W2, ["arm6a"]),
        ("W3", OFFMAIN_W3, ["arm6c"]),
        ("W2'-or-true", SAVE_W2_OR_TRUE, ["arm6c"]),
        ("w4-arm6c-const-compare", SAVE_W4_CONST_COMPARE, ["arm6c"]),
        ("w4-arm6c-never-flipped", SAVE_W4_FLAG_NEVER_FLIPPED, ["arm6c"]),
    ]
    for name, fixture, arms in off_expectations:
        got = run_offmain_checks(mask_strings(fixture))
        red = [a for a in ["arm6a", "arm6b", "arm6c"] if any(a in x for x in got)]
        if red != arms:
            failures.append("%s: expected exactly %s red, got %s" % (name, arms, red))
    # [R3-t19] round-3 dual-gate arms: each dirty fixture must go red on its own
    # arm only (a fixture that trips a neighbouring arm is a fake witness).
    for name, text, arms in R3_FIXTURES:
        got = run_round3_checks(text)
        red = [a for a in ("arm7", "arm8", "arm9", "arm10")
               if any(x.startswith(a + ":") for x in got)]
        if red != arms:
            failures.append("%s: expected exactly %s red, got %s (%s)"
                            % (name, arms, red, got[:2]))
    if failures:
        for x in failures:
            print("SELFTEST FAIL: " + x)
        print("SKILL_FILE_SAVE_RESULT_SELFTEST=FAIL")
        return 1
    print(
        "selftest OK: compliant green + 11 violation fixtures each flip exactly "
        "their own arm + offmain green + 6 offmain fixtures each flip their own arm "
        "+ the allowed indirection stays green "
        "(incl. the wide3-08 and wide4 re-attacks: arm2 trailing constant / "
        "short-circuit / scope suffix, arm3 literal pop / `onBack.invoke()` / "
        "template-brace, arm6c literal-short-circuited and never-flipped gate) "
        "+ round3 re-attacks: arm7 old-record carry / dropped field, arm8 dropped "
        "or fail-open UpdateResult, arm9 inverted sibling early exit, arm10 blank "
        "draft seed (each flips exactly its own arm)"
    )
    print("SKILL_FILE_SAVE_RESULT_SELFTEST=PASS")
    return 0


def main():
    if "--selftest" in sys.argv[1:]:
        return selftest()
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    root = args[0] if args else "."
    try:
        seg = screen_segment(root)
        src = screen_source(root)
        failures = (run_checks(seg) + run_offmain_checks(seg)
                    + run_round3_checks(src))
    except Unparseable as exc:
        # fail-closed: an unviewable screen is a violation, never a pass
        print("skill_file_save_result_check: 1 violation(s) in %s" % TARGET_REL)
        print("  - lex: the screen cannot be lexed (%s) — the gate refuses to judge "
              "a broken lexical projection" % exc)
        return 1
    if failures:
        print("skill_file_save_result_check: %d violation(s) in %s" % (len(failures), TARGET_REL))
        for x in failures:
            print("  - " + x)
        return 1
    print("skill_file_save_result_check: OK (5/5 arms + offmain 3/3 + "
          "round3 4/4) on %s" % TARGET_REL)
    return 0


if __name__ == "__main__":
    sys.exit(main())
