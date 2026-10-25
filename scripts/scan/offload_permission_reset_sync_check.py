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

MANAGER = "src/android/app/src/main/java/com/rikkaminis/app/offload/OffloadPermissionManager.kt"
SCREEN = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/OffloadPermissionScreen.kt"
MARKER = "OFFLOAD_PERMISSION_RESET_SYNC_SELFTEST=PASS"

FUN_RE = re.compile(r"\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*[<(]")
KEYLESS_RE = re.compile(
    r"remember\s*\{\s*mutableStateOf\(\s*OffloadPermissionManager\.getLevel"
)
REVISION_KEY_RE = re.compile(r"remember\s*\([^)\n]*\brevision\b[^)\n]*\)")


def strip_comments(src: str) -> str:
    """Remove // and /* */ comments, honouring string and raw-string literals.

    KDoc in these files quotes the very anchors the gate looks for, so comment
    text must not be able to satisfy a criterion.
    """
    out = []
    state = "code"
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        two = src[i:i + 2]
        if state == "code":
            if two == "//":
                state = "line"
                i += 2
            elif two == "/*":
                state = "block"
                i += 2
            elif src[i:i + 3] == '"""':
                state = "raw"
                i += 3
            elif c == '"':
                state = "str"
                out.append(c)
                i += 1
            else:
                out.append(c)
                i += 1
        elif state == "line":
            if c == "\n":
                state = "code"
                out.append(c)
            i += 1
        elif state == "block":
            if two == "*/":
                state = "code"
                i += 2
            else:
                if c == "\n":
                    out.append(c)
                i += 1
        elif state == "str":
            if c == "\\":
                out.append(src[i:i + 2])
                i += 2
                continue
            if c == "\n":
                state = "code"
            elif c == '"':
                state = "code"
            out.append(c)
            i += 1
        else:  # raw string
            if src[i:i + 3] == '"""':
                state = "code"
                i += 3
            else:
                i += 1
    return "".join(out)


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
def _strip_outer_parens(expr: str) -> str:
    e = expr.strip()
    while len(e) >= 2 and e[0] == "(" and e[-1] == ")":
        depth, ok = 0, True
        for i, ch in enumerate(e):
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0 and i != len(e) - 1:
                    ok = False
                    break
        if not ok:
            break
        e = e[1:-1].strip()
    return e


def _split_top(expr: str, op: str):
    parts, depth, cur, i = [], 0, [], 0
    while i < len(expr):
        ch = expr[i]
        if ch in "\"'":
            j = i + 1
            while j < len(expr) and expr[j] != ch:
                if expr[j] == "\\":
                    j += 1
                j += 1
            cur.append(expr[i:j + 1])
            i = j + 1
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if depth == 0 and expr.startswith(op, i):
            parts.append("".join(cur))
            cur = []
            i += len(op)
            continue
        cur.append(ch)
        i += 1
    parts.append("".join(cur))
    return parts


def fold_bool(expr: str):
    """True / False / None for a Kotlin condition, folding literal operands.

    [meta-audit wide3 offload-C1] "clears the caches" has to mean the clear
    RUNS. Wrapping the two `clear()` calls in `if (false) { … }` keeps both
    substrings — and keeps the gate green — while Reset All stops clearing
    anything, so every session-scoped verdict survives the reset. Same class as
    `false && guard`: the spelling is preserved, the decision is deleted.
    """
    e = _strip_outer_parens(expr)
    if not e:
        return None
    ors = _split_top(e, "||")
    if len(ors) > 1:
        vals = [fold_bool(x) for x in ors]
        if any(v is True for v in vals):
            return True
        if all(v is False for v in vals):
            return False
        return None
    ands = _split_top(e, "&&")
    if len(ands) > 1:
        vals = [fold_bool(x) for x in ands]
        if any(v is False for v in vals):
            return False
        if all(v is True for v in vals):
            return True
        return None
    if e == "true":
        return True
    if e == "false":
        return False
    return None


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


def prune_dead_branches(src: str) -> str:
    """Blank out never-executed branch bodies (offset and line preserving).

    Only literal-decided conditions are folded, so this cannot mis-prune real
    logic; it just stops dead code from satisfying an existence criterion.
    """
    out = src
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
            seg = out[dead[0]:dead[1]]
            blanked = "".join(ch if ch == "\n" else " " for ch in seg)
            if seg != blanked:
                out = out[:dead[0]] + blanked + out[dead[1]:]
                changed = True
                break
        if not changed:
            break
    return out


def expand(src: str, body: str, defined, depth: int = 3) -> str:
    """`body` plus the bodies of any file-defined function it calls (recursively,
    depth-capped) so an extracted helper is judged by its own contents.

    Every layer is pruned of literal-decided branches first, so a helper whose
    whole effect sits inside `if (false)` contributes nothing (wide3 offload-C1).
    """
    if depth <= 0 or not body:
        return prune_dead_branches(body)
    body = prune_dead_branches(body)
    out = body
    for name in defined:
        if name + "(" in body:
            sub = body_of(src, "fun " + name + "(")
            if sub and sub != body:
                out += "\n" + expand(src, sub, defined, depth - 1)
    return out


REV_ASSIGN_RE = re.compile(r"_revision\s*\.\s*value\s*=(?!=)\s*([^\n;]*)")


def bumps_revision(text: str) -> bool:
    """True only when the counter actually MOVES FORWARD.

    [meta-audit wide3 offload-C0] `"_revision.value =" in text` accepted
    `_revision.value = _revision.value` (and `= 0`). `MutableStateFlow` is
    distinctUntilChanged, so writing the same value emits nothing: the settings
    page keeps showing pre-reset levels while the gate reports "bumps the
    revision". A bump has to derive the new value from the old one.
    """
    if re.search(r"_revision\s*\.\s*value\s*\+\+", text):
        return True
    if re.search(r"_revision\s*\.\s*value\s*\+=\s*[^=\s]", text):
        return True
    if re.search(r"_revision\s*\.\s*update\s*[({][^}]*\bit\b", text):
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
            return True  # e.g. `_revision.value = _revision.value + 1`
        if re.match(r"(?:nextRevision|nextRev|advance\w*|bump\w*)\s*\(", rhs):
            return True
    return False


def check(manager_raw: str, screen_raw: str) -> list:
    mgr = strip_comments(manager_raw)
    screen = strip_comments(screen_raw)
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
