#!/usr/bin/env python3
"""[fix/da1024-H] offload_permission_reset_sync_check

Issue-08 gate: a permission-level change must reach BOTH the persisted level and
the in-memory session caches, and the settings screen must actually re-read the
level after such a change.

  1. OffloadPermissionManager.resetAll() drops every session's grants AND
     denials (inline, or via a helper the body calls) — "Reset All" that leaves
     a cached verdict behind silently allows/refuses the next call in the same
     session;
  2. OffloadPermissionManager.setLevel() drops ONLY that tool's cached verdict
     (a per-tool removal, never a clear-all: grants for untouched tools are
     still valid verdicts), and must not leave the session caches alone either;
  3. both paths bump the `_revision` refresh signal;
  4. OffloadPermissionScreen.kt keys its row level state on that revision — no
     keyless `remember { mutableStateOf(OffloadPermissionManager.getLevel(...)) }`
     (which froze the first-composition value, so "Reset All" left the whole
     page showing the old levels), and both rows subscribe to the signal.
  5. [R3-t17] setLevel must WRITE the key getLevel READS, and resetAll must
     clear it (templates compared with the interpolation normalised, so a
     consistent rename stays green while write/read drift does not);
  6. [R3-t17] checkPermission's ASK_ONCE decision must consult the session
     caches with the right polarity (`x in grants ⇒ true`,
     `x in denials ⇒ false`);
  7. [R3-t17] the dialog's answer must be applied in the direction the user
     chose (`ALLOW_SESSION ⇒ grants.add + true`, `DENY_SESSION ⇒ denials.add
     + false`).

Criteria 5-7 close the WRITE side of the same contract: criteria 1-4 pin where
a change must LAND, 5-7 pin that it lands in the place the reader looks and
that the session verdicts it feeds point the right way. They were witnessed
individually green by dual-gate bypasses (#30/#31 lie between this gate and
auto_approve_bypass_check; #32 is a symmetric gap inside this gate).

Bodies are followed through the helpers they call (depth-capped), so an
implementation that extracts `clearAllSessionGrants()` / `clearToolSessionState()`
/ `bumpRevision()` is judged on what those helpers actually do — an empty
helper is a red, not a pass.

Code anchors only: comments are stripped first, so a KDoc *mentioning*
`sessionGrants.clear()` cannot satisfy criterion 1.

Usage: python3 offload_permission_reset_sync_check.py <repo_root>
       python3 offload_permission_reset_sync_check.py --root <repo_root>
       python3 offload_permission_reset_sync_check.py --selftest
Exit 0 = pass, 1 = violation, 2 = usage error.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _kotlin_ast as _ast          # noqa: E402  (shared Kotlin primitives)
import _kotlin_lexer as _lex        # noqa: E402  (shared Kotlin lexer)
import _kotlin_valueflow as _vf     # noqa: E402  (shared value flow)

MANAGER = "src/android/app/src/main/java/com/rikkaminis/app/offload/OffloadPermissionManager.kt"
SCREEN = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/OffloadPermissionScreen.kt"
MARKER = "OFFLOAD_PERMISSION_RESET_SYNC_SELFTEST=PASS"

FUN_RE = re.compile(r"\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*[<(]")
KEYLESS_RE = re.compile(
    r"remember\s*\{\s*mutableStateOf\(\s*OffloadPermissionManager\.getLevel"
)
REVISION_KEY_RE = re.compile(r"remember\s*\([^)\n]*\brevision\b[^)\n]*\)")


def strip_comments(src: str) -> str:
    """Code view of `src` — comments AND string bodies blanked, length preserved.

    [wide4 parser-death] This used to be a hand-written state machine. It missed
    two constructions the witnesses used: `"${"}"}"` (a string template whose
    body holds a brace) desynchronised the brace walk so `_stmt_span` ended the
    `if (false)` block early and the dead `clear()` calls escaped the pruning,
    and a nested block comment `/* a /* b */ c */` ended at the first `*/`.
    Both now go through the repo-wide lexer (one state machine, equal-length
    views, so every offset downstream still lines up).

    String bodies are blanked too: `val _s = "sessionGrants.clear()"` is a
    string, not a statement, and must not satisfy criterion 1.
    """
    return _lex.mask_code(src)


def _match_paren(text: str, open_idx: int):
    """Index of the `)` matching the `(` at [open_idx]; None when unbalanced.

    [R3-t17] This used to be a hand-written quote/brace state machine — the same
    machinery every other gate in this repo was forced to delete because the
    witnesses broke it in the same three ways (raw strings, nested block
    comments, `${…}` templates). It now delegates to the shared Kotlin matcher;
    an unbalanced source yields None and the caller fails closed.
    """
    j = _ast.match_bracket(text, open_idx)
    return j if j >= 0 else None


def _match_brace(text: str, open_idx: int):
    """Index of the `}` matching the `{` at [open_idx]; None when unbalanced."""
    j = _ast.match_bracket(text, open_idx)
    return j if j >= 0 else None


def _body_span_of(src: str, header: str):
    """(open, close) of the BLOCK body of `header`'s function, or (None, None).

    [meta-audit wide3 offload-C2] The old version took
    `src.find("{", src.find(header))` — the first `{` after the function NAME.
    Add a default lambda parameter
    (`onUnused: () -> Unit = { clearToolSessionState(toolName); bumpRevision() }`)
    and that first brace belongs to the DEFAULT ARGUMENT, so the "body" that got
    judged was the default value, the real body (whose calls had been deleted)
    was never looked at, and the gate printed green. Walk the parameter list,
    then take the first brace that follows it.
    """
    i = src.find(header)
    if i < 0:
        return None, None
    open_paren = src.find("(", i)
    if open_paren < 0:
        return None, None
    close_paren = _match_paren(src, open_paren)
    if close_paren is None:
        return None, None
    n = len(src)
    j = close_paren + 1
    while j < n:
        ch = src[j]
        if ch == "{":
            end = _match_brace(src, j)
            return j, (end if end is not None else n - 1)
        if ch == "=" and src[j:j + 2] != "==":
            return None, None  # expression body — no block to judge
        if ch == ";":
            return None, None
        if ch == "\n":
            k = j + 1
            while k < n and src[k] in " \t":
                k += 1
            if k >= n or src[k] != "{":
                return None, None
            j = k
            continue
        j += 1
    return None, None


def body_of(src: str, header: str) -> str:
    """Braces-balanced body of the first function whose declaration contains
    `header` (which must end just before the parameter list, e.g. 'fun resetAll(').
    '' when absent — absent means red, never a silent pass."""
    open_b, close_b = _body_span_of(src, header)
    if open_b is None:
        return ""
    return src[open_b:close_b + 1]


# ── constant-condition pruning ───────────────────────────────────────────────
def fold_bool(expr: str):
    """True / False / None for a Kotlin condition, decided by the SHARED folder.

    [meta-audit wide3 offload-C1] "clears the caches" has to mean the clear
    RUNS. Wrapping the two `clear()` calls in `if (false) { … }` keeps both
    substrings — and keeps the gate green — while Reset All stops clearing
    anything, so every session-scoped verdict survives the reset. Same class as
    `false && guard`: the spelling is preserved, the decision is deleted.

    [wide4 family-hole] The local folder this replaces understood exactly two
    spellings — the bare literals `true` and `false`. `if (!true) { … }` (the
    witness), `if (0 == 1 && guard)`, `if ((false))` and `when (false)` all kept
    every anchor while the body never ran. `_kotlin_ast.const_bool` folds `!`,
    `==`/`!=` over literals and short-circuits `&&`/`||` the way Kotlin does;
    an unknown operand stays None, never a silent True.
    """
    return _ast.const_bool(expr)


def _stmt_span(src: str, i: int):
    """End offset (exclusive) of the statement starting at [i].

    Delegates to the shared `_branch_extent` (brace block, or a single statement
    up to the first depth-0 newline that is not a continuation) instead of the
    local depth counter this gate used to carry.
    """
    return _ast._branch_extent(src, i)


def _blank_region(src: str, start: int, end: int) -> str:
    """Blank [start:end) to spaces, newlines preserved (offset/line keeping)."""
    seg = src[start:end]
    blanked = "".join(ch if ch == "\n" else " " for ch in seg)
    return src[:start] + blanked + src[end:]


def _literal_branch_hits(cond: str, subject):
    """`True`/`False`/`None` — does this `when` branch label hit the constant?

    None means "not decidable as a literal list" (`is X`, `in 1..9`, `!true`,
    a subject reference), and the caller must NOT prune on None.
    """
    c = cond.strip()
    if not c or c == "else":
        return None
    if c.startswith("is ") or c.startswith("in ") or c.startswith("!") or "->" in c:
        return None
    hits = False
    for a, b in _ast.split_top(c, (",",)):
        piece = c[a:b].strip()
        if not piece:
            continue
        val = _ast.const_value(piece)
        if val is None:
            return None
        if val == subject:
            hits = True
    return hits


def _prune_when(src: str) -> str:
    """Blank `when` branches a constant subject decides against.

    Same family as `if (false)`: the branch body keeps its spelling but never
    runs, so a `sessionGrants.clear()` parked in `when (false) { else -> … }`
    kept criterion 1 green while Reset All cleared nothing. Only folds when the
    subject is a pure literal AND every label is a readable literal list;
    anything it cannot parse is left alone (no false pruning).
    """
    for m in re.finditer(r"\bwhen\s*\(", src):
        open_paren = m.end() - 1
        close_paren = _match_paren(src, open_paren)
        if close_paren is None:
            continue
        subject = _ast.const_value(src[open_paren + 1:close_paren])
        if subject is None:
            continue
        brace = close_paren + 1
        while brace < len(src) and src[brace] in " \t\r\n":
            brace += 1
        if brace >= len(src) or src[brace] != "{":
            continue
        close_brace = _match_brace(src, brace)
        if close_brace is None:
            continue
        body = src[brace + 1:close_brace]
        segments = []
        readable = True
        for a, b in _ast.split_top(body, ("\n",)):
            seg = body[a:b]
            arrow = seg.find("->")
            if arrow < 0:
                continue
            cond = seg[:arrow].strip()
            if cond == "else":
                segments.append((a, b, None))
                continue
            hits = _literal_branch_hits(cond, subject)
            if hits is None:
                readable = False
                break
            segments.append((a, b, hits))
        if not readable or not segments:
            continue
        matched = any(hits is True for _a, _b, hits in segments)
        dead = [(a, b) for a, b, hits in segments
                if (hits is False) or (hits is None and matched)]
        if not dead:
            continue
        for a, b in dead:
            src = _blank_region(src, brace + 1 + a, brace + 1 + b)
        return src
    return src


def prune_dead_branches(src: str) -> str:
    """Blank out never-executed branch bodies (offset and line preserving).

    Only literal-decided conditions are folded, so this cannot mis-prune real
    logic; it just stops dead code from satisfying an existence criterion.

    [wide4 family-hole] The folder used to know only `true` / `false`. Two
    more spellings of the same idea are handled now: `when (<literal>)` (its
    non-matching arms) and everything `_kotlin_ast.const_bool` can decide —
    `!true`, `(false)`, `0 == 1`, `false && guard`.
    """
    out = _prune_when(src)
    IF_RE = re.compile(r"\bif\s*\(")
    for _round in range(50):
        changed = False
        for m in IF_RE.finditer(out):
            open_paren = m.end() - 1
            close_paren = _match_paren(out, open_paren)
            if close_paren is None:
                continue
            val = fold_bool(out[open_paren + 1:close_paren])
            if val is None:
                continue
            then_start = close_paren + 1
            while then_start < len(out) and out[then_start] in " \t\r\n":
                then_start += 1
            then_end = _stmt_span(out, then_start)
            else_start = None
            k = then_end
            while k < len(out) and out[k] in " \t\r\n":
                k += 1
            if out.startswith("else", k):
                else_start = _stmt_span(out, k + 4)
            if val is False:
                dead = (then_start, then_end)
            else:
                if else_start is None:
                    continue
                dead = (k + 4, else_start)
            if out[dead[0]:dead[1]].strip():
                out = _blank_region(out, *dead)
                changed = True
                break
        if not changed:
            break
    return _prune_when(out)


LOCAL_LAMBDA_RE = re.compile(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=\s*\{")
LOCAL_FUN_RE = re.compile(r"\bfun\s+([A-Za-z_]\w*)\s*\(")


def strip_uncalled_locals(body: str) -> str:
    """Blank local lambdas / local `fun`s whose NAME is never used again.

    [wide4 family-hole] `val unused = { clearAllSessionGrants(); bumpRevision() }`
    keeps both anchor calls inside `resetAll()`'s body while Reset All executes
    neither — the closure is defined and never invoked. The wide3 fix only
    deflected the *default-argument* lambda; the same dead body one line lower
    walked straight through. A binding whose name appears nowhere else in the
    body cannot be running, so its text must not count as an executed statement.
    """
    out = body
    for pattern in (LOCAL_LAMBDA_RE, LOCAL_FUN_RE):
        for m in pattern.finditer(out):
            name = m.group(1)
            if len(re.findall(r"\b%s\b" % re.escape(name), out)) > 1:
                continue  # referenced elsewhere — it may well run
            brace = out.find("{", m.end() - 1)
            if brace < 0:
                continue
            close = _match_brace(out, brace)
            if close is None:
                continue
            out = _blank_region(out, brace + 1, close)
    return out


def expand(src: str, body: str, defined, depth: int = 3) -> str:
    """`body` plus the bodies of any file-defined function it calls (recursively,
    depth-capped) so an extracted helper is judged by its own contents.

    Every layer is pruned of literal-decided branches first, so a helper whose
    whole effect sits inside `if (false)` contributes nothing (wide3 offload-C1).
    """
    if depth <= 0 or not body:
        return strip_uncalled_locals(prune_dead_branches(body))
    body = strip_uncalled_locals(prune_dead_branches(body))
    out = body
    for name in defined:
        if name + "(" in body:
            sub = body_of(src, "fun " + name + "(")
            if sub and sub != body:
                out += "\n" + expand(src, sub, defined, depth - 1)
    return out


REV_ASSIGN_RE = re.compile(r"_revision\s*\.\s*value\s*=(?!=)\s*([^\n;]*)")
REV_PLUS_ASSIGN_RE = re.compile(r"_revision\s*\.\s*value\s*\+=\s*([^=\n;]+)")


def _moves_forward(expr: str) -> bool:
    """Does this expression derive a NEW value from `it` (strictly forward)?

    [wide4 family-hole] `it` alone (the identity update `_revision.update { it }`)
    is runtime-equivalent to `_revision.value = _revision.value` —
    `MutableStateFlow` is distinctUntilChanged, so it emits nothing and the
    settings page never re-reads the level. `it + 0` / `it - 0` / `it * 2` (the
    flow starts at 0) are the same no-op; only an additive step with a non-zero
    (or unknown) operand, `.inc()` or `it++` actually moves the counter.
    """
    e = " ".join(expr.split())
    if not e:
        return False
    if re.search(r"\bit\s*\.\s*inc\s*\(", e) or re.search(r"\bit\s*\+\+", e):
        return True
    if re.search(r"\bit\s*\.\s*dec\s*\(", e) or re.search(r"\bit\s*--", e):
        return False
    for m in re.finditer(r"\bit\b\s*([+\-])\s*([^+\-*/%)\s]+|\([^()]*\))", e):
        op, operand = m.group(1), m.group(2).strip("() ")
        val = _ast.const_value(operand)
        if val is None:
            return True  # unknown step: the gate cannot prove it is zero
        if val[0] != "num":
            continue
        if op == "+" and val[1] > 0:
            return True
        if op == "-" and val[1] < 0:
            return True
    for m in re.finditer(r"([^+\-*/%(\s]+)\s*\+\s*\bit\b", e):
        val = _ast.const_value(m.group(1).strip())
        if val is None or (val[0] == "num" and val[1] != 0):
            return True
    return False


def bumps_revision(text: str) -> bool:
    """True only when the counter actually MOVES FORWARD.

    [meta-audit wide3 offload-C0] `"_revision.value =" in text` accepted
    `_revision.value = _revision.value` (and `= 0`). `MutableStateFlow` is
    distinctUntilChanged, so writing the same value emits nothing: the settings
    page keeps showing pre-reset levels while the gate reports "bumps the
    revision". A bump has to derive the new value from the old one.

    [wide4 family-hole] The `update { … }` channel used to be accepted on the
    mere PRESENCE of `it` inside the braces — `_revision.update { it }` (the
    witness) passed while emitting nothing, and `_revision.value += 0` passed
    the `+=` arm. Both channels now have to prove a non-zero step.
    """
    if re.search(r"_revision\s*\.\s*value\s*\+\+", text):
        return True
    for m in REV_PLUS_ASSIGN_RE.finditer(text):
        val = _ast.const_value(m.group(1).strip())
        if val is None or (val[0] == "num" and val[1] != 0):
            return True
    for m in re.finditer(r"_revision\s*\.\s*update\s*", text):
        j = m.end()
        if j < len(text) and text[j] == "(":
            close = _match_paren(text, j)
            inner = text[j + 1:close] if close else ""
        elif j < len(text) and text[j] == "{":
            close = _match_brace(text, j)
            inner = text[j + 1:close] if close else ""
        else:
            continue
        if _moves_forward(inner):
            return True
    for m in REV_ASSIGN_RE.finditer(text):
        rhs = " ".join(m.group(1).split()).strip()
        if not rhs:
            continue
        if re.fullmatch(r"_revision\s*\.\s*value", rhs):
            continue  # self-assignment: no state change, no emission
        if re.fullmatch(r"-?\d+", rhs):
            continue  # constant: emits once, then never again
        if re.fullmatch(r"(?:true|false|null|\"\"|'.')", rhs):
            continue
        if "_revision" in rhs:
            if _moves_forward(rhs.replace("_revision.value", "it")):
                return True  # e.g. `_revision.value = _revision.value + 1`
            continue
        if re.match(r"(?:nextRevision|nextRev|advance\w*|bump\w*)\s*\(", rhs):
            return True
    return False


# ────────────────────────────────────────────────────────────────────────────
# [R3-t17] The WRITE side of the same contract (the criteria above pin where a
# change must land; these pin that it lands in the same place the reader looks,
# and that the session verdicts it feeds point the right way).
# ────────────────────────────────────────────────────────────────────────────

KEY_READ_RE = re.compile(r'\bgetString\s*\(\s*"((?:[^"\\]|\\.)*)"')
KEY_WRITE_RE = re.compile(r'\bputString\s*\(\s*"((?:[^"\\]|\\.)*)"')
KEY_REMOVE_RE = re.compile(r'\bremove\s*\(\s*"((?:[^"\\]|\\.)*)"')


def _norm_key(literal: str) -> str:
    """A prefs key template with its interpolation NORMALISED.

    `"level_$toolName"` (read/write) and `"level_${tool.toolName}"` (resetAll's
    per-tool remove) are the same key; the gate compares TEMPLATES, so renaming
    the key everywhere stays green while the two sides drifting apart does not.
    """
    out = re.sub(r"\$\{[^}]*\}", "$", literal)
    return re.sub(r"\$[A-Za-z_][A-Za-z0-9_]*", "$", out)


def _fun_views(code: str, lit: str, header: str):
    """(code_view, literal_view) of `header`'s block body, or (None, "")."""
    open_b, close_b = _body_span_of(code, header)
    if open_b is None:
        return None, ""
    return code[open_b:close_b + 1], lit[open_b:close_b + 1]


def live_keys_problem(code: str, lit: str) -> list:
    """Is the level STORED where it is READ?

    [R3-t17] #30 · dual-gate with auto_approve_bypass_check (both rc=0). The
    seam sat between `getLevel` (which auto_approve_bypass_check C3 pins) and
    `setLevel` (which this gate pins): both functions were individually intact,
    and the KEY they agreed on was nobody's business. `putString("level_
    override_$toolName", …)` therefore kept nine green criteria while every
    user-level change became a no-op — the row and the decision both kept
    reading `level_$toolName` and falling back to `info.defaultLevel`.
    """
    out = []
    _, read_lit = _fun_views(code, lit, "fun getLevel(")
    _, write_lit = _fun_views(code, lit, "fun setLevel(")
    _, reset_lit = _fun_views(code, lit, "fun resetAll(")
    if not read_lit:
        return ["getLevel() not found — the key it reads cannot be judged (fail-closed)"]
    reads = {_norm_key(k) for k in KEY_READ_RE.findall(read_lit)}
    if not reads:
        out.append("getLevel() reads no stored level key — a saved level could never "
                   "be honoured (fail-closed: expected `<prefs>.getString(…)`)")
    for key in sorted(reads):
        if "$" not in key:
            out.append("getLevel() reads the CONSTANT key %r — every tool would share "
                       "one stored level" % key)
    if not write_lit:
        out.append("setLevel() not found — the key it writes cannot be judged (fail-closed)")
    else:
        writes = {_norm_key(k) for k in KEY_WRITE_RE.findall(write_lit)}
        if not writes:
            out.append("setLevel() writes no level key at all — the change is dropped")
        elif reads and writes != reads:
            out.append(
                "setLevel() writes %s but getLevel() reads %s — the change is stored "
                "where nothing looks, so every level edit silently reverts to the "
                "default (the row and checkPermission both re-read the reader's key)"
                % (sorted(writes), sorted(reads)))
    if not reset_lit:
        out.append("resetAll() not found — the key it clears cannot be judged (fail-closed)")
    else:
        removes = {_norm_key(k) for k in KEY_REMOVE_RE.findall(reset_lit)}
        stale = reads - removes
        if stale:
            out.append(
                "resetAll() does not clear the stored level key(s) %s — 'Reset All' "
                "leaves a level the reader still honours" % sorted(stale))
    return out


_MEMBERSHIP_RE = re.compile(r"^\s*([A-Za-z_]\w*(?:\s*\.\s*\w+)*)\s+(in|!in)\s+"
                            r"([A-Za-z_]\w*)\s*$")
_CONTAINS_RE = re.compile(r"^\s*([A-Za-z_]\w*(?:\s*\.\s*\w+)*)\s*\.\s*contains\s*\("
                          r"\s*([A-Za-z_]\w*)\s*\)\s*$")
_SESSION_STORES = ("grants", "denials")


def _squash(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def _unwrap_block(text: str) -> str:
    """`{ a(); b() }` (with a possibly trailing newline) -> `a(); b()`."""
    t = text.strip()
    if t.startswith("{"):
        close = _ast.match_bracket(t, 0)
        if close > 0:
            return t[1:close]
    return t


def _strip_block(text: str) -> str:
    """`{ a(); b() }` -> `a(); b()`; whitespace squashed."""
    t = _squash(text)
    if t.startswith("{") and t.endswith("}"):
        t = _squash(t[1:-1])
    return t


def _membership_problems(body: str) -> list:
    """The ASK_ONCE decision must READ the session caches, the right way round.

    [R3-t17] #31 · dual-gate with auto_approve_bypass_check (both rc=0). Both
    gates pinned how the caches are CLEARED and what the resolved level is;
    the test that consumes them (`if (toolName in grants) return true`) was
    outside every criterion, so inverting it (`!in`) made every ASK_ONCE tool
    run without a dialog — no auto-approve switch, default install.
    """
    out = []
    seen = {"grants": 0, "denials": 0}
    for cond, t_start, t_end, _e_start, _e_end in _ast.if_blocks(body):
        if not any(store in cond for store in _SESSION_STORES):
            continue
        m = _MEMBERSHIP_RE.match(_squash(cond))
        if m is not None:
            _subject, op, store = m.group(1), m.group(2), m.group(3)
        else:
            c = _CONTAINS_RE.match(_squash(cond))
            if c is None or c.group(1) not in _SESSION_STORES:
                out.append(
                    "checkPermission() decides on `%s`, which is not a plain "
                    "membership test over the session caches — the gate cannot tell "
                    "which way the verdict points (fail-closed)" % _squash(cond)[:70])
                continue
            store, op = c.group(1), "in"
        seen[store] += 1
        then = _strip_block(body[t_start:t_end])
        want_value = "true" if store == "grants" else "false"
        if op == "!in":
            out.append(
                "checkPermission()'s %s test is INVERTED (`%s`): a tool that was "
                "NEVER %s returns %s — every ASK_ONCE tool runs without the dialog"
                % (store, _squash(cond)[:60], "granted" if store == "grants" else "denied",
                   want_value))
        elif then != "return " + want_value:
            out.append(
                "checkPermission()'s %s test answers `%s`, expected `return %s` — the "
                "session verdict is applied backwards"
                % (store, then[:60], want_value))
    for store, count in sorted(seen.items()):
        if count == 0:
            out.append(
                "checkPermission() never consults `%s` — a %s verdict recorded for "
                "this session is ignored (the decision moved out of the reader, or the "
                "short-circuit was removed)" % (store, store[:-1]))
    return out


# The two arms whose ANSWER is a security decision: ALLOW_SESSION must record a
# grant and answer true; DENY_SESSION must record a denial and answer false.
_RESPONSE_ARMS = {
    "Response.ALLOW_SESSION": (("grants",), ("denials",), "true"),
    "Response.DENY_SESSION": (("denials",), ("grants",), "false"),
}


def _response_arm_problems(body: str) -> list:
    """The dialog's answer must be applied in the direction the user chose.

    [R3-t17] #32 · single-gate symmetric gap. Every criterion above watches the
    caches being cleared and the level being resolved; none watched what the
    Response arm WRITES. `Response.DENY_SESSION -> { grants.add(toolName); true }`
    runs the call the user just refused and auto-allows the rest of the session.
    """
    out = []
    arms = {}
    for _subject, cond, b_start, b_end in _ast.when_branches(body):
        label = re.sub(r"\s+", "", cond)
        if label in _RESPONSE_ARMS:
            arms[label] = body[b_start:b_end]
    for label in sorted(_RESPONSE_ARMS):
        want_add, forbid_add, want_value = _RESPONSE_ARMS[label]
        arm = arms.get(label)
        if arm is None:
            out.append("checkPermission() has no `%s ->` arm — the user's answer is "
                       "not handled (fail-closed: anchor drift)" % label)
            continue
        adds = set(re.findall(r"\b([A-Za-z_]\w*)\s*\.\s*add\s*\(", arm))
        tail = _strip_block(_ast.tail_expr(_unwrap_block(arm)))
        problems = []
        if want_add[0] not in adds:
            problems.append("never records the verdict in `%s`" % want_add[0])
        for store in forbid_add:
            if store in adds:
                problems.append("records the verdict in the OPPOSITE store `%s`" % store)
        if tail != want_value:
            problems.append("evaluates to `%s` instead of `%s`" % (tail[:40], want_value))
        if problems:
            out.append(
                "checkPermission()'s `%s ->` arm %s — the user's explicit choice is "
                "applied BACKWARDS (the call they answered is decided the other way, "
                "and the session cache remembers the wrong verdict)"
                % (label, " and ".join(problems)))
    return out


def permission_decision_problems(code: str, defined) -> list:
    """Criteria 6+7 over checkPermission's block body (helpers followed)."""
    body = body_of(code, "fun checkPermission(")
    if not body.strip():
        return ["checkPermission() not found — the permission DECISION cannot be "
                "judged (fail-closed)"]
    expanded = expand(code, body, defined)
    return _membership_problems(expanded) + _response_arm_problems(expanded)


def check(manager_raw: str, screen_raw: str) -> list:
    # [wide4 parser-death] Lex first, and refuse to judge a file whose strings /
    # comments we could not follow: `mask_code()` raises on an unterminated
    # literal, and a gate that silently fell back to the raw text would read
    # prose as code. Fail-closed = a violation, never a green.
    views = {}
    lits = {}
    for label, raw in ((MANAGER, manager_raw), (SCREEN, screen_raw)):
        ok, reason, v = _lex.analyse(raw)
        if not ok:
            return [f"{label}: cannot be lexed ({reason}) — the gate refuses to judge "
                    f"a file whose strings/comments it cannot follow"]
        views[label] = v["code"]
        # [R3-t17] the SharedPreferences KEY lives inside a string literal, which
        # the code view blanks — the key-agreement criterion needs the
        # literal-kept view (same length, so one offset indexes both).
        lits[label] = v["comments"]
    mgr = views[MANAGER]
    screen = views[SCREEN]
    mgr_lit = lits[MANAGER]
    defined = set(FUN_RE.findall(mgr))
    errs = []

    # ── the refresh signal itself ────────────────────────────────────────────
    if "_revision" not in mgr or not re.search(r"\bval\s+revision\b", mgr):
        errs.append(
            "OffloadPermissionManager has no `revision` refresh signal "
            "(the settings rows cannot observe a level change without one)"
        )
    elif "asStateFlow()" not in mgr:
        errs.append("`revision` is not exposed as a read-only StateFlow (asStateFlow())")

    # ── 1. resetAll clears every session's grants AND denials ────────────────
    reset = expand(mgr, body_of(mgr, "fun resetAll("), defined)
    if not reset.strip():
        errs.append("resetAll() not found")
    else:
        if "sessionGrants.clear()" not in reset or "sessionDenials.clear()" not in reset:
            errs.append(
                "resetAll() does not clear the in-memory session caches "
                "(needs sessionGrants.clear() AND sessionDenials.clear(), inline or in a helper it calls)"
            )
        if not bumps_revision(reset):
            errs.append("resetAll() does not bump _revision (the UI never refreshes after Reset All)")

    # ── 2. setLevel drops that tool's cached verdict, and only that tool's ───
    setl = expand(mgr, body_of(mgr, "fun setLevel("), defined)
    if not setl.strip():
        errs.append("setLevel() not found")
    else:
        per_tool = re.search(r"remove\s*\(\s*toolName\s*\)", setl)
        if "sessionGrants" not in setl or "sessionDenials" not in setl:
            errs.append(
                "setLevel() does not touch the in-memory session caches "
                "(a cached verdict outlives the level change)"
            )
        elif not per_tool:
            errs.append(
                "setLevel() does not remove the tool's own cached verdict "
                "(expected a per-tool remove(toolName) over the session caches)"
            )
        if "sessionGrants.clear()" in setl or "sessionDenials.clear()" in setl:
            errs.append(
                "setLevel() clears EVERY tool's session cache — a level change must not "
                "invalidate grants for tools the user did not touch"
            )
        if not bumps_revision(setl):
            errs.append("setLevel() does not bump _revision (the row's own re-read is not driven by it)")

    # ── 3. the settings rows re-read the level off that signal ───────────────
    keyless = KEYLESS_RE.search(screen)
    if keyless:
        line = screen[:keyless.start()].count("\n") + 1
        errs.append(
            f"{SCREEN}:{line} has a keyless `remember {{ mutableStateOf(OffloadPermissionManager.getLevel(...)) }}` "
            "— it freezes the first-composition level"
        )
    if "OffloadPermissionManager.revision" not in screen:
        errs.append(f"{SCREEN} never subscribes to OffloadPermissionManager.revision")
    else:
        keys = REVISION_KEY_RE.findall(screen)
        if len(keys) < 2:
            errs.append(
                f"{SCREEN} keys only {len(keys)} remember(...) on revision, expected >= 2 "
                "(AgentPolicyRow and PermissionRow each own their level state)"
            )
    # ── 5. a level change must be stored where the reader READS ──────────────
    # [R3-t17] #30 (dual-gate with auto_approve_bypass_check, both green): every
    # criterion above anchors the PLACES a change must reach; nobody checked
    # that the key setLevel WRITES and the key getLevel READS are the same key.
    # Writing `level_override_$toolName` kept "setLevel persists the level" and
    # "setLevel clears the cache" green while every user choice fell back to the
    # default (the row and checkPermission both re-read `level_$toolName`).
    errs.extend(live_keys_problem(mgr, mgr_lit))

    # ── 6. the session verdicts point the RIGHT WAY ──────────────────────────
    # [R3-t17] #31: the gate policed the CLEARING of sessionGrants/sessionDenials
    # but never the DECISION that fills them, so an inverted membership test
    # (`if (toolName !in grants) return true`) admitted every tool that had
    # never been granted — no auto-approve switch needed.
    # ── 7. …and so does the dialog's answer ─────────────────────────────────
    # [R3-t17] #32: `Response.DENY_SESSION -> { grants.add(toolName); true }`
    # runs the call the user just refused and auto-allows the rest of the
    # session; the clearing-side criteria cannot see the write side.
    errs.extend(permission_decision_problems(mgr, defined))

    return errs


# ── selftest ────────────────────────────────────────────────────────────────

_HELPERS = """
    private val sessionGrants = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    private val sessionDenials = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    internal fun sessionsContaining(sessions: Map<String, Set<String>>, toolName: String): Set<String> =
        sessions.entries.filter { toolName in it.value }.map { it.key }.toSet()

    internal fun clearToolFrom(sessions: MutableMap<String, MutableSet<String>>, toolName: String) {
        for (id in sessionsContaining(sessions, toolName)) {
            sessions[id]?.remove(toolName)
        }
    }

    internal fun clearToolSessionState(toolName: String) {
        clearToolFrom(sessionGrants, toolName)
        clearToolFrom(sessionDenials, toolName)
    }

    internal fun clearAllSessionGrants() {
        sessionGrants.clear()
        sessionDenials.clear()
    }

    private fun bumpRevision() {
        _revision.update { it + 1 }
    }

    // [R3-t17] the READER and the DECISION the write-side criteria judge. The
    // fixture used to stop at setLevel/resetAll, which is exactly why the
    // witnesses #30-#32 could sit in the gap with every arm green.
    fun getLevel(toolName: String): PermissionLevel {
        val stored = prefs.getString("level_$toolName", null)?.let {
            try { PermissionLevel.valueOf(it) } catch (_: Exception) { null }
        }
        return stored ?: PermissionLevel.ASK_ONCE
    }

    enum class Response { ALLOW_SESSION, ALLOW_ONCE, DENY_SESSION }

    suspend fun checkPermission(toolName: String, toolTitle: String, sessionId: String): Boolean {
        val level = getLevel(toolName)
        return when (level) {
            PermissionLevel.BYPASS -> true
            PermissionLevel.NOT_ALLOWED -> false
            PermissionLevel.ASK_ONCE -> {
                val denials = sessionDenials.computeIfAbsent(sessionId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
                if (toolName in denials) return false
                val grants = sessionGrants.computeIfAbsent(sessionId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
                if (toolName in grants) return true
                val allowed = when (respond(toolName)) {
                    Response.ALLOW_SESSION -> {
                        grants.add(toolName)
                        true
                    }
                    Response.ALLOW_ONCE -> true
                    Response.DENY_SESSION -> {
                        denials.add(toolName)
                        false
                    }
                    null -> false
                }
                allowed
            }
        }
    }

    private suspend fun respond(toolName: String): Response? = null
"""

_GOOD_RESET = """
    fun resetAll() {
        val editor = prefs.edit()
        for (tool in toolRegistry) {
            editor.remove("level_${tool.toolName}")
        }
        editor.apply()
        clearAllSessionGrants()
        bumpRevision()
    }
"""

_GOOD_SET = """
    fun setLevel(toolName: String, level: PermissionLevel) {
        prefs.edit().putString("level_$toolName", level.name).apply()
        clearToolSessionState(toolName)
        bumpRevision()
    }
"""

_GOOD_SCREEN = """
@Composable
private fun AgentPolicyRow(toolName: String, showDivider: Boolean) {
    val revision by OffloadPermissionManager.revision.collectAsState()
    var currentLevel by remember(toolName, revision) { mutableStateOf(OffloadPermissionManager.getLevel(toolName)) }
    var expanded by remember { mutableStateOf(false) }
}

@Composable
private fun PermissionRow(tool: ToolPermissionInfo, showDivider: Boolean) {
    val revision by OffloadPermissionManager.revision.collectAsState()
    var currentLevel by remember(tool.toolName, revision) { mutableStateOf(OffloadPermissionManager.getLevel(tool.toolName)) }
    var expanded by remember { mutableStateOf(false) }
}
"""


def _manager(reset=_GOOD_RESET, set_level=_GOOD_SET, helpers=_HELPERS) -> str:
    return "object OffloadPermissionManager {\n" + helpers + set_level + reset + "}\n"


def selftest() -> int:
    arms = []

    # compliant (incl. the comment trap: KDoc quoting the anchors must not help)
    comment_trap = (
        "/**\n"
        " * [fix/da1024-H] resetAll() must call sessionGrants.clear() and\n"
        " * sessionDenials.clear(); setLevel(toolName, level) removes remove(toolName).\n"
        " */\n"
    )
    arms.append(("compliant (+ KDoc quoting the anchors)", comment_trap + _manager(), _GOOD_SCREEN, 0))

    # 1: resetAll leaves the session caches alone
    bad_reset_no_clear = """
    fun resetAll() {
        val editor = prefs.edit()
        for (tool in toolRegistry) {
            editor.remove("level_${tool.toolName}")
        }
        editor.apply()
        bumpRevision()
    }
"""
    arms.append((
        "V1 resetAll does not clear the session caches",
        _manager(reset=bad_reset_no_clear),
        _GOOD_SCREEN,
        1,
    ))

    # 1b: resetAll calls the helper, but the helper is empty (the extracted-helper hole)
    empty_helper = _HELPERS.replace(
        "    internal fun clearAllSessionGrants() {\n"
        "        sessionGrants.clear()\n"
        "        sessionDenials.clear()\n"
        "    }\n",
        "    internal fun clearAllSessionGrants() {\n"
        "    }\n",
    )
    arms.append((
        "V2 resetAll calls an EMPTY clearAllSessionGrants()",
        _manager(helpers=empty_helper),
        _GOOD_SCREEN,
        1,
    ))

    # 1c: no revision bump on reset
    no_bump_reset = _GOOD_RESET.replace("        bumpRevision()\n", "")
    arms.append((
        "V3 resetAll does not bump _revision",
        _manager(reset=no_bump_reset),
        _GOOD_SCREEN,
        1,
    ))

    # 2: setLevel ignores the session caches
    bad_set_ignores = """
    fun setLevel(toolName: String, level: PermissionLevel) {
        prefs.edit().putString("level_$toolName", level.name).apply()
        bumpRevision()
    }
"""
    arms.append((
        "V4 setLevel leaves the session caches alone",
        _manager(set_level=bad_set_ignores),
        _GOOD_SCREEN,
        1,
    ))

    # 2b: setLevel over-clears (every tool, every session) on top of the right per-tool removal
    over_clear = """
    fun setLevel(toolName: String, level: PermissionLevel) {
        prefs.edit().putString("level_$toolName", level.name).apply()
        clearToolSessionState(toolName)
        sessionGrants.clear()
        sessionDenials.clear()
        bumpRevision()
    }
"""
    arms.append((
        "V5 setLevel clears EVERY tool's cache (over-clear)",
        _manager(set_level=over_clear),
        _GOOD_SCREEN,
        1,
    ))

    # 2c: setLevel removes the tool but does not bump
    no_bump_set = _GOOD_SET.replace("        bumpRevision()\n", "")
    arms.append((
        "V6 setLevel does not bump _revision",
        _manager(set_level=no_bump_set),
        _GOOD_SCREEN,
        1,
    ))

    # 3: keyless remember reappears in one row (the other two stay keyed)
    keyless_screen = _GOOD_SCREEN + """
@Composable
private fun RegressionRow(toolName: String) {
    var currentLevel by remember { mutableStateOf(OffloadPermissionManager.getLevel(toolName)) }
}
"""
    arms.append(("V7 screen keeps a keyless remember for the level", _manager(), keyless_screen, 1))

    # 3b: screen never subscribes at all (rows deleted / signal dropped)
    unsubscribed = _GOOD_SCREEN.replace("OffloadPermissionManager.revision.collectAsState()", "null")
    arms.append(("V8 screen never subscribes to revision", _manager(), unsubscribed, 1))

    # 3c: one row keyed, the other not
    one_row = _GOOD_SCREEN.replace("remember(tool.toolName, revision)", "remember(tool.toolName)")
    arms.append(("V9 only one row is keyed on revision", _manager(), one_row, 1))

    # ── [meta-audit wide3] negative arms for the three hardened criteria ──
    # Each arm flips exactly its own criterion; the needle keeps the arm from
    # passing on a DIFFERENT error.
    dead_clear_helper = _HELPERS.replace(
        "    internal fun clearAllSessionGrants() {\n"
        "        sessionGrants.clear()\n"
        "        sessionDenials.clear()\n"
        "    }\n",
        "    internal fun clearAllSessionGrants() {\n"
        "        if (false) {\n"
        "            sessionGrants.clear()\n"
        "            sessionDenials.clear()\n"
        "        }\n"
        "    }\n",
    )
    arms.append((
        "W3-C1 clears sit inside `if (false)` (dead guard)",
        _manager(helpers=dead_clear_helper), _GOOD_SCREEN, 1,
        "does not clear the in-memory session caches",
    ))

    dead_tool_helper = _HELPERS.replace(
        "        for (id in sessionsContaining(sessions, toolName)) {\n"
        "            sessions[id]?.remove(toolName)\n"
        "        }\n",
        "        if (false && !sessions.isEmpty()) {\n"
        "            for (id in sessionsContaining(sessions, toolName)) {\n"
        "                sessions[id]?.remove(toolName)\n"
        "            }\n"
        "        }\n",
    )
    arms.append((
        "W3-C1b per-tool remove sits inside `if (false && …)`",
        _manager(helpers=dead_tool_helper), _GOOD_SCREEN, 1,
        "per-tool remove(toolName)",
    ))

    self_assign_helper = _HELPERS.replace(
        "        _revision.update { it + 1 }\n",
        "        _revision.value = _revision.value\n",
    )
    arms.append((
        "W3-C0 bumpRevision self-assigns (StateFlow emits nothing)",
        _manager(helpers=self_assign_helper), _GOOD_SCREEN, 2,
        "does not bump _revision",
    ))

    zero_helper = _HELPERS.replace(
        "        _revision.update { it + 1 }\n",
        "        _revision.value = 0\n",
    )
    arms.append((
        "W3-C0b bumpRevision resets to a constant",
        _manager(helpers=zero_helper), _GOOD_SCREEN, 2,
        "does not bump _revision",
    ))

    default_lambda_set = """
    fun setLevel(
        toolName: String,
        level: PermissionLevel,
        onUnused: () -> Unit = { clearToolSessionState(toolName); bumpRevision() },
    ) {
        prefs.edit().putString("level_$toolName", level.name).apply()
    }
"""
    arms.append((
        "W3-C2 default lambda parameter hides the real body",
        _manager(set_level=default_lambda_set), _GOOD_SCREEN, 2,
        "does not touch the in-memory session caches",
    ))

    default_lambda_reset = """
    fun resetAll(
        onUnused: () -> Unit = { clearAllSessionGrants(); bumpRevision() },
    ) {
        val editor = prefs.edit()
        for (tool in toolRegistry) {
            editor.remove("level_${tool.toolName}")
        }
        editor.apply()
    }
"""
    arms.append((
        "W3-C2b default lambda parameter on resetAll",
        _manager(reset=default_lambda_reset), _GOOD_SCREEN, 2,
        "does not clear the in-memory session caches",
    ))

    # ── wide4 witnesses: one arm per falsified axis ─────────────────────────
    # family-hole: the same "spelling preserved, decision deleted" move in the
    # spellings the wide3 fix did not know.
    arms.append((
        "w4-F1 unary-negated literal guard `if (!true)`",
        _manager(helpers=_HELPERS.replace(
            "    internal fun clearAllSessionGrants() {\n"
            "        sessionGrants.clear()\n"
            "        sessionDenials.clear()\n"
            "    }\n",
            "    internal fun clearAllSessionGrants() {\n"
            "        if (!true) {\n"
            "            sessionGrants.clear()\n"
            "            sessionDenials.clear()\n"
            "        }\n"
            "    }\n")),
        _GOOD_SCREEN,
        1,
        "does not clear the in-memory session caches",
    ))
    arms.append((
        "w4-F5 constant subject `when (false)` leaves only a non-matching arm",
        _manager(helpers=_HELPERS.replace(
            "    internal fun clearAllSessionGrants() {\n"
            "        sessionGrants.clear()\n"
            "        sessionDenials.clear()\n"
            "    }\n",
            "    internal fun clearAllSessionGrants() {\n"
            "        when (false) {\n"
            "            true -> {\n"
            "                sessionGrants.clear()\n"
            "                sessionDenials.clear()\n"
            "            }\n"
            "        }\n"
            "    }\n")),
        _GOOD_SCREEN,
        1,
        "does not clear the in-memory session caches",
    ))
    # …and the mirror arm: `else` under a constant subject DOES run, so the
    # pruner must not touch it (a hardening that blanks `else` would be a false
    # red on correct code).
    arms.append((
        "w4-F5b `when (false)` with only an `else` arm really runs it",
        _manager(helpers=_HELPERS.replace(
            "    internal fun clearAllSessionGrants() {\n"
            "        sessionGrants.clear()\n"
            "        sessionDenials.clear()\n"
            "    }\n",
            "    internal fun clearAllSessionGrants() {\n"
            "        when (false) {\n"
            "            else -> {\n"
            "                sessionGrants.clear()\n"
            "                sessionDenials.clear()\n"
            "            }\n"
            "        }\n"
            "    }\n")),
        _GOOD_SCREEN,
        0,
    ))
    arms.append((
        "w4-F2 identity update `_revision.update { it }` moves nothing",
        _manager(helpers=_HELPERS.replace(
            "        _revision.update { it + 1 }", "        _revision.update { it }")),
        _GOOD_SCREEN,
        2,
        "does not bump _revision",
    ))
    arms.append((
        "w4-F6 zero step `_revision.value += 0` moves nothing",
        _manager(helpers=_HELPERS.replace(
            "        _revision.update { it + 1 }", "        _revision.value += 0")),
        _GOOD_SCREEN,
        2,
        "does not bump _revision",
    ))
    # the dead local closure: the same statements, one line lower, never invoked
    arms.append((
        "w4-F3 uncalled local lambda holds the whole effect",
        _manager(reset=_GOOD_RESET.replace(
            "        clearAllSessionGrants()\n        bumpRevision()",
            "        val unused = { clearAllSessionGrants(); bumpRevision() }")),
        _GOOD_SCREEN,
        2,
        "does not clear the in-memory session caches",
    ))
    # parser-death: a string template holding `}` inside the dead branch
    arms.append((
        "w4-F4 template brace inside `if (false)` desyncs the brace walk",
        _manager(helpers=_HELPERS.replace(
            "    internal fun clearAllSessionGrants() {\n"
            "        sessionGrants.clear()\n"
            "        sessionDenials.clear()\n"
            "    }\n",
            "    internal fun clearAllSessionGrants() {\n"
            '        if (false) { val _s = "${"}"}"; sessionGrants.clear(); '
            "sessionDenials.clear() }\n"
            "    }\n")),
        _GOOD_SCREEN,
        1,
        "does not clear the in-memory session caches",
    ))

    # ── [R3-t17] the WRITE side: key agreement + verdict direction ─────────
    arms.append((
        "t17-V10 setLevel writes a key nothing reads (witness #30)",
        _manager(set_level=_GOOD_SET.replace('"level_$toolName"',
                                             '"level_override_$toolName"')),
        _GOOD_SCREEN, 1,
        "setLevel() writes",
    ))
    arms.append((
        "t17-V10b the reader spells the SAME key with braces (control)",
        _manager(helpers=_HELPERS.replace('prefs.getString("level_$toolName"',
                                          'prefs.getString("level_${toolName}"')),
        _GOOD_SCREEN, 0,
    ))
    arms.append((
        "t17-V11 the ASK_ONCE grant test is inverted (witness #31)",
        _manager(helpers=_HELPERS.replace(
            "if (toolName in grants) return true",
            "if (toolName !in grants) return true")),
        _GOOD_SCREEN, 1,
        "INVERTED",
    ))
    arms.append((
        "t17-V11b the denial test answers true (verdict reversed)",
        _manager(helpers=_HELPERS.replace(
            "if (toolName in denials) return false",
            "if (toolName in denials) return true")),
        _GOOD_SCREEN, 1,
        "applied backwards",
    ))
    arms.append((
        "t17-V12 DENY_SESSION grants and answers true (witness #32)",
        _manager(helpers=_HELPERS.replace(
            "                    Response.DENY_SESSION -> {\n"
            "                        denials.add(toolName)\n"
            "                        false\n"
            "                    }",
            "                    Response.DENY_SESSION -> {\n"
            "                        grants.add(toolName)\n"
            "                        true\n"
            "                    }")),
        _GOOD_SCREEN, 1,
        "applied BACKWARDS",
    ))
    arms.append((
        "t17-V12b the session-cache test removed from the decision (fail-closed)",
        _manager(helpers=_HELPERS.replace(
            "                if (toolName in grants) return true\n", "")),
        _GOOD_SCREEN, 1,
        "never consults `grants`",
    ))

    failures = 0
    for arm in arms:
        name, mgr, screen, expect = arm[:4]
        needle = arm[4] if len(arm) > 4 else None
        errs = check(mgr, screen)
        if len(errs) != expect:
            print(f"  FAIL {name}: expected {expect} error(s), got {len(errs)}: {errs}")
            failures += 1
            continue
        if needle and not any(needle in e for e in errs):
            print(f"  FAIL {name}: error text lacks {needle!r}: {errs}")
            failures += 1
            continue
        print(f"  OK {name}: {errs if errs else 'green'}")
    _ = None
    if failures:
        print(f"SELFTEST FAILED ({failures} arms)")
        return 1
    print("OFFLOAD_PERMISSION_RESET_SYNC_SELFTEST_ARMS=%d" % len(arms))
    print(MARKER)
    return 0


def main() -> int:
    args = list(sys.argv[1:])
    if "--selftest" in args:
        return selftest()
    root = None
    if "--root" in args:
        i = args.index("--root")
        if i + 1 >= len(args):
            print("usage: --root <repo_root>", file=sys.stderr)
            return 2
        root = args[i + 1]
    elif len(args) == 1:
        root = args[0]
    else:
        print("usage: <repo_root> | --root <repo_root> | --selftest", file=sys.stderr)
        return 2
    try:
        manager = open(os.path.join(root, MANAGER)).read()
        screen = open(os.path.join(root, SCREEN)).read()
    except OSError as e:
        print(f"cannot read sources under {root}: {e}", file=sys.stderr)
        return 2
    errs = check(manager, screen)
    if errs:
        print("offload_permission_reset_sync_check RED:")
        for e in errs:
            print(f"  - {e}")
        return 1
    print(f"offload_permission_reset_sync_check OK ({MANAGER} + {SCREEN})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
