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
from _kotlin_ast import (const_bool, match_bracket, stmt_spans,      # noqa: E402
                         strip_parens)


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
                            editContent = displayContent
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
        "template-brace, arm6c literal-short-circuited and never-flipped gate)"
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
    except Unparseable as exc:
        # fail-closed: an unviewable screen is a violation, never a pass
        print("skill_file_save_result_check: 1 violation(s) in %s" % TARGET_REL)
        print("  - lex: the screen cannot be lexed (%s) — the gate refuses to judge "
              "a broken lexical projection" % exc)
        return 1
    failures = run_checks(seg) + run_offmain_checks(seg)
    if failures:
        print("skill_file_save_result_check: %d violation(s) in %s" % (len(failures), TARGET_REL))
        for x in failures:
            print("  - " + x)
        return 1
    print("skill_file_save_result_check: OK (5/5 arms + offmain 3/3) on %s" % TARGET_REL)
    return 0


if __name__ == "__main__":
    sys.exit(main())
