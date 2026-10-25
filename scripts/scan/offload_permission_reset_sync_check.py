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
    """Index of the `)` matching the `(` at [open_idx] (literal-aware)."""
    depth = 0
    i, n = open_idx, len(text)
    while i < n:
        ch = text[i]
        if ch in "\"'":
            j = i + 1
            while j < n and text[j] != ch and text[j] != "\n":
                if text[j] == "\\":
                    j += 1
                j += 1
            i = j + 1
            continue
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return None


def _match_brace(text: str, open_idx: int):
    depth = 0
    i, n = open_idx, len(text)
    while i < n:
        ch = text[i]
        if ch in "\"'":
            j = i + 1
            while j < n and text[j] != ch and text[j] != "\n":
                if text[j] == "\\":
                    j += 1
                j += 1
            i = j + 1
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return None


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
    """End offset (exclusive) of the statement starting at [i]."""
    n = len(src)
    while i < n and src[i] in " \t\r\n":
        i += 1
    if i >= n:
        return n
    if src[i] == "{":
        end = _match_brace(src, i)
        return (end + 1) if end is not None else n
    depth = 0
    while i < n:
        ch = src[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth < 0:
                return i
        elif ch == "\n" and depth == 0:
            return i + 1
        i += 1
    return n


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


def check(manager_raw: str, screen_raw: str) -> list:
    # [wide4 parser-death] Lex first, and refuse to judge a file whose strings /
    # comments we could not follow: `mask_code()` raises on an unterminated
    # literal, and a gate that silently fell back to the raw text would read
    # prose as code. Fail-closed = a violation, never a green.
    views = {}
    for label, raw in ((MANAGER, manager_raw), (SCREEN, screen_raw)):
        ok, reason, v = _lex.analyse(raw)
        if not ok:
            return [f"{label}: cannot be lexed ({reason}) — the gate refuses to judge "
                    f"a file whose strings/comments it cannot follow"]
        views[label] = v["code"]
    mgr = views[MANAGER]
    screen = views[SCREEN]
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
