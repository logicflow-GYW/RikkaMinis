#!/usr/bin/env python3
"""
Gate: FIX-E (deep-audit-1023 ISSUE-05, P1) — the Skills sheet PASTE import must
not run the repository write on the Main dispatcher.

The URL tab already wraps its repository call in withContext(Dispatchers.IO);
the paste branch carried the [audit-0917] comment demanding the same wrap while
the code never had it — importFromContent (parse + SQLite write + SKILL.md
write) ran on Main, the ANR the URL branch avoids.

Fix contract asserted here:
  arm1  the paste branch's importFromContent(pasteContent) call is lexically
        inside a `withContext(Dispatchers.IO) { ... }` block (brace-depth scan,
        comments stripped, string templates masked)
  arm2  there is exactly one importFromContent(pasteContent) call site, so the
        wrap cannot be satisfied by an orphan copy elsewhere in the file

deep-audit-1032 ISSUE-06 (P1) arms added to the same gate (E owns only this
gate; no new gate, no new SELFTEST marker):
  arm3  every `reloadFromDisk()` call in the Skills screen is OFF Main — either
        lexically inside a `withContext(Dispatchers.IO)` block *in that file*,
        or routed through a repository method that wraps internally. The
        criterion binds the DECISION POINT (the thread context the call runs
        on), not one spelling of it: moving the wrap into the repository is an
        equivalent fix and must stay green (1031-D lesson).
  arm4  same for the MCP screen (`mcpRepository.reloadFromDisk()`).
        Both screens are asserted — fixing only one is the shape this arm
        exists to catch.
  arm5  the skill editor's remember keys: `editName` must carry keys (an
        un-keyed draft deadlocks the name dialog), `editContent` keys must
        include `updatedAt` (otherwise initialContent re-derives while
        editContent keeps the pre-edit snapshot forever), and the shape anchor
        `val initialContent = remember` must still be present in the file
        (skill_frontmatter_roundtrip_check.py pins that literal).

deep-audit-1040 B-04 (P1) arms added to the same gate (B2 owns only this gate;
no new gate, no new SELFTEST marker):
  arm7  every call to a repository / Keystore-store method that performs disk
        I/O runs OFF Main — in the MCP settings screen AND in both per-session
        sheets (SessionSkillsSheet / SessionMcpsSheet). "Performs disk I/O" is
        derived from the callee's own body (brace-matched, comments stripped,
        one delegation hop) instead of from a literal method list, so a
        brand-new IO method invoked bare is caught while a genuinely pure one
        (`exportServerJSON` / `previewImport` — JSONObject work only) stays
        legal on Main. Fail-closed twice: a (file, call-prefix) pair with no
        call site at all means the anchor lost its subject, and a called method
        with no declaration to judge cannot be proven pure.
  arm8  the OAuth seeding effect's keys must COVER the draft remember's keys.
        `remember(editServer)` + `LaunchedEffect(editServer?.id)` resets the
        draft to "" whenever an edit changes the server but keeps its id, and
        the reader never re-runs — the form then shows an empty client secret
        while storage still holds one.

deep-audit-1041 B-03 (P1) arm added to the same gate (B3 owns only this
gate; no new gate, no new SELFTEST marker):
  arm9  every call to an `EnvVarRepository` method that touches disk runs OFF
        Main in `EnvironmentVariablesScreen` — the sixth screen of this family
        (the other five are arms 6/7). "Touches disk" is derived, not listed:
        `add`/`update`/`delete` reach `saveMetadata().writeText(...)` on
        `env-vars.json`, and `getValue` reads a field that DECLARES itself as a
        SharedPreferences store (`_prefs_fields`), so a Keystore-backed read is
        caught while a genuinely pure `isValidKey` / `isDuplicateKey` stays
        legal on Main. `produceState` is deliberately NOT an IO opener: its body
        runs in the composition's coroutine context (Main), which is exactly the
        half-fix this arm exists to catch.

Limits (deliberate, documented so they are not mistaken for coverage):
  * the repository-side alternative resolves the method body plus ONE
    delegation hop (`fun reloadFromDisk() = load()` / `{ loadAll() }`), not a
    whole call-graph fixpoint. An unresolvable chain counts as "not wrapped"
    (fail-closed) — keep the call-site wrap in that case.
  * arms 3/4 only look at the two Settings screens. The chat/backup callers of
    reloadFromDisk run on non-suspend paths (buildSystemPrompt) and are out of
    this gate's scope; see fix-reports/FIX-E.md §7.

Usage: python3 scripts/scan/skill_import_offmain_check.py [repo_root]
       python3 scripts/scan/skill_import_offmain_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import analyse as _analyse  # noqa: E402 (shared lexer)
from _kotlin_ast import (const_bool, enclosing_conditions,          # noqa: E402
                         function_body, match_bracket, split_top,
                         strip_parens)
from _kotlin_valueflow import (call_args, expr_end, value_sources)  # noqa: E402

SM_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/SkillsManagementScreen.kt"
CALL = "skillRepository.importFromContent(pasteContent)"
OPENER = "withContext(Dispatchers.IO)"

# [deep-audit-1032 ISSUE-06] The two Settings screens re-scan the repositories
# on entry; both used to do it inline on the Main dispatcher.
MCP_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/MCPIntegrationsScreen.kt"
SKILL_REPO_REL = "src/android/app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt"
MCP_REPO_REL = "src/android/app/src/main/java/com/rikkaminis/app/data/repository/MCPRepository.kt"

# (screen file, call expression, repository file, repository entry point)
RELOAD_SITES = (
    (SM_REL, "skillRepository.reloadFromDisk()", SKILL_REPO_REL, "reloadFromDisk"),
    (MCP_REL, "mcpRepository.reloadFromDisk()", MCP_REPO_REL, "reloadFromDisk"),
)

# Editor remember keys (arm5) live in the same screen file.
EDITOR_REL = SM_REL
EDITOR_KEYS = (
    ("editName", None),
    ("editContent", "updatedAt"),
)
INITIAL_LITERAL = "val initialContent = remember"

SELFTEST_MARK = "SKILL_IMPORT_OFFMAIN_SELFTEST=PASS"


LEX_FAILURES = []


def strip_comments(text):
    """Comments blanked to spaces (length preserved), strings untouched.

    [wide4-12 arm5, axis=parser-death] This used to be a hand-written scanner
    with a `"` state and NO `'` state: `val _o = '"'; val _c = "/*"` made the
    next `/*` open a "block comment" that swallowed the real
    `MCPOAuthStore.signOut(…)` line between them. The shared `_kotlin_lexer`
    is the single conforming state machine (char literals, nested block
    comments, raw strings, `${…}` templates) and a lexing failure is
    FAIL-CLOSED: the gate reports it instead of judging a truncated surface.
    """
    ok, reason, views = _analyse(text)
    if not ok or views is None:
        LEX_FAILURES.append(
            "a scanned file could not be lexed (%s) — the gate refuses to judge "
            "a truncated surface (fail-closed)" % reason)
        return ""
    return views["comments"]


def mask_strings(text):
    """String/char literal CONTENTS blanked to spaces (length preserved), so
    brace matching survives `${...}` templates."""
    ok, reason, views = _analyse(text)
    if not ok or views is None:
        LEX_FAILURES.append(
            "a scanned file could not be lexed (%s) — the gate refuses to judge "
            "a truncated surface (fail-closed)" % reason)
        return ""
    return views["strings"]


DISPATCH_API_RE = re.compile(r"\b(withContext|launch|async)$")
# `CoroutineScope(Dispatchers.Main).launch { … }` — the scope's context decides,
# and the spelling puts the dispatcher in the receiver, not in `launch(...)`.
SCOPE_CHAIN_RE = re.compile(
    r"\bCoroutineScope\s*\(([^()]*)\)\s*\.\s*(withContext|launch|async)\s*$")
DISPATCH_ARG_NAMES = ("context", "dispatcher", "newContext")
LAMBDA_ARG_RE = re.compile(r"[A-Za-z_]\w*\s*=\s*\{")


def _matching_open(text, close_idx):
    """Index of the `(` matching the `)` at close_idx (-1 when unbalanced)."""
    depth = 0
    for k in range(close_idx, -1, -1):
        if text[k] == ")":
            depth += 1
        elif text[k] == "(":
            depth -= 1
            if depth == 0:
                return k
    return -1


def _enclosing_open_paren(masked, idx):
    """Index of the innermost `(` left open at position idx (-1 when none)."""
    depth = 0
    for k in range(idx - 1, -1, -1):
        if masked[k] == ")":
            depth += 1
        elif masked[k] == "(":
            if depth == 0:
                return k
            depth -= 1
    return -1


def _dispatch_callee(head):
    """The dispatcher API name when `head` ends with one (else None)."""
    m = DISPATCH_API_RE.search(head.rstrip())
    return m.group(1) if m else None


def _first_dispatch_arg(arglist):
    """The dispatcher expression inside a dispatcher call's argument list.

    Handles the three spellings that carry a context:
      `withContext(Dispatchers.IO)`            → first positional argument
      `withContext(context = Dispatchers.Main)` → the named context argument
      `withContext(context = X, block = { … })` → same, lambda argument skipped
    Returns "" when the call names a dispatcher API but shows no context.
    """
    for part in _split_top(arglist, ","):
        p = part.strip()
        if not p:
            continue
        m = re.match(r"(%s)\s*=(?!=)\s*(.*)\Z" % "|".join(DISPATCH_ARG_NAMES),
                     p, re.S)
        if m:
            return m.group(2).strip()
        if p.startswith("{") or LAMBDA_ARG_RE.match(p):
            continue                    # a lambda argument switches nothing
        return p                        # first positional argument
    return ""


def _dispatch_block_context(masked, i):
    """Dispatcher expression of the lambda block whose `{` is at `i`.

    None  → this block switches nothing (plain `if` / `let` / bare `launch {`,
            which INHERITS the enclosing context — Kotlin semantics).
    ''    → it IS a dispatcher-establishing call with an unknown context
            (fail-closed: unknown is not IO).
    expr  → that context decides the thread of everything inside.

    [wide4-12 arm1, axis=family-hole] The old `_trailing_dispatch_head` only
    accepted a `{` that followed a `)` directly, so the NAMED-argument lambda
    `withContext(context = Dispatchers.Main, block = { … })` was not even seen
    as a dispatcher block and the call inside inherited the OUTER IO context.
    """
    head = masked[max(0, i - 400):i].rstrip()
    # (a) trailing lambda: `withContext(X) {`, `scope.launch(X) {`
    if head.endswith(")"):
        open_idx = _matching_open(head, len(head) - 1)
        if open_idx >= 0 and _dispatch_callee(head[:open_idx]) is not None:
            return _first_dispatch_arg(head[open_idx + 1:len(head) - 1])
    # (b) `CoroutineScope(Dispatchers.Main).launch {` — dispatcher in the receiver
    m = SCOPE_CHAIN_RE.search(head)
    if m:
        return m.group(1).strip()
    # (c) bare `withContext {` / `scope.launch {` — inherits the parent context
    if _dispatch_callee(head) is not None:
        return None
    # (d) named-argument lambda inside a dispatcher call:
    #     `withContext(context = Dispatchers.Main, block = {`
    paren = _enclosing_open_paren(masked, i)
    if paren >= 0 and LAMBDA_ARG_RE.match(masked[paren + 1:].lstrip()):
        callee_head = masked[:paren].rstrip()
        if _dispatch_callee(callee_head) is not None:
            close = _match_paren(masked, paren)
            args = masked[paren + 1:close if close > 0 else len(masked)]
            return _first_dispatch_arg(args)
    elif paren >= 0 and _dispatch_callee(masked[:paren].rstrip()) is not None:
        close = _match_paren(masked, paren)
        args = masked[paren + 1:close if close > 0 else len(masked)]
        return _first_dispatch_arg(args)
    return None


def _innermost_dispatch(masked, idx):
    """Dispatcher expression of the INNERMOST enclosing dispatcher block.

    [wide3-08 arm1, axis=scope] The old stack answered `any(stack)`, so an
    OUTER `withContext(Dispatchers.IO)` made an INNER
    `withContext(Dispatchers.Main) { ... }` look off-Main. The thread a call
    runs on is decided by the nearest enclosing context switch, so only the
    innermost dispatcher-establishing block counts (plain `if` / lambda blocks
    do not switch the dispatcher and are skipped).
    """
    stack = []
    for i, ch in enumerate(masked[:idx]):
        if ch == "{":
            stack.append(_dispatch_block_context(masked, i))
        elif ch == "}":
            if stack:
                stack.pop()
    for entry in reversed(stack):
        if entry is not None:
            return entry
    return None


IO_EXPR_RE = re.compile(r"Dispatchers\.IO(?:\.[A-Za-z_]\w*\s*\([^()]*\))?$")


def _alias_is_io(name, masked):
    """True when the local binding `name` provably holds `Dispatchers.IO`.

    [wide4-12 arm2, axis=dataflow] The old check was an existence regex that
    accepted `var` and any RHS starting with `Dispatchers.IO`, so
      `var ioCtx = Dispatchers.IO; ioCtx = Dispatchers.Main`
    and `val ioCtx = Dispatchers.IO + Dispatchers.Main` both read as IO (the
    second one really runs on Main: `+` keeps the RIGHT pair of the same Key).
    Now: immutable `val` only, and the LAST write must be exactly IO.
    """
    if not masked:
        return False
    if re.search(r"\bvar\s+%s\b" % re.escape(name), masked):
        return False                    # a mutable binding is not a constant
    writes = []
    for m in re.finditer(r"\bval\s+%s\s*(?::[^=\n]+)?=\s*" % re.escape(name), masked):
        writes.append((m.start(), _line_end_expr(masked, m.end()).strip()))
    for m in re.finditer(r"(?<![\w.])%s\s*=(?!=)" % re.escape(name), masked):
        writes.append((m.start(), _line_end_expr(masked, m.end()).strip()))
    if not writes:
        return False
    writes.sort()
    _pos, rhs = writes[-1]
    return bool(IO_EXPR_RE.match(rhs.rstrip().rstrip(";,")))


def _is_io_dispatcher(expr, masked=""):
    """True when a dispatcher expression is an IO context (alias resolved)."""
    if not expr:
        return False
    e = expr.strip()
    if IO_EXPR_RE.match(e):
        return True
    if re.fullmatch(r"[A-Za-z_]\w*", e):
        return _alias_is_io(e, masked)
    return False

def inside_io_wrap(text_nc, text_masked):
    """True when the paste call site's INNERMOST dispatcher block is IO."""
    call_idx = text_nc.find(CALL)
    if call_idx < 0:
        return False, "call site not found"
    if text_nc.count(CALL) != 1:
        return False, "expected exactly 1 call site, found %d" % text_nc.count(CALL)
    disp = _innermost_dispatch(text_masked, call_idx)
    if disp is None:
        return False, "call site is not lexically inside %s { ... }" % OPENER
    if _is_io_dispatcher(disp, text_masked):
        return True, ""
    return False, ("call site runs inside `%s`, not %s - an enclosing IO wrap does "
                   "not rescue an inner dispatcher switch (wide3-08 arm1)"
                   % (disp, OPENER))

def _io_wrapped_at(text_nc, text_masked, idx, openers=(OPENER,)):
    """True when code position `idx` runs on an IO dispatcher.

    [wide3-08 arm1] Judged on the INNERMOST dispatcher-establishing block, not
    on `any(enclosing opener)`: `withContext(IO) { withContext(Main) { call } }`
    runs the call on Main and used to pass.
    """
    return _is_io_dispatcher(_innermost_dispatch(text_masked, idx), text_masked)

IO_OPENERS = (OPENER, "launch(Dispatchers.IO)")

def _match_paren(text, open_idx):
    """Index of the `)` matching the `(` at open_idx (-1 if none).

    Delegates to the shared bracket matcher (single conforming scanner) — the
    hand-rolled depth loop this used to carry was the third copy of it.
    """
    return match_bracket(mask_strings(text), open_idx)

def _split_top(s, op):
    """Split at depth-0 occurrences of `op` (string literals masked)."""
    masked = mask_strings(s)
    return [s[a:b] for a, b in split_top(masked, (op,))]

def _unparen(p):
    return strip_parens(p)

def _lit_value(part):
    """True/False when `part` is decided by literals alone (else None).

    Was a bare `true`/`false` (optionally negated) matcher; now the shared
    const folder, so `1 == 1` / `"a" == "a"` / `!true` are the same question.
    """
    return const_bool(_unparen(part))

def _const_truth(cond):
    """True/False when the condition is decided by literals alone, else None.

    [wide4-12 arm4, axis=family-hole] `_literal_decides` only knew bare
    `true` / `false`, so `if ((1 == 1) || sid == null)` re-introduced the
    same dead guard through a comparison operator. `x == x` counts too: the
    spelling tests nothing.
    """
    c = _unparen(cond)
    v = _lit_value(c)
    if v is not None:
        return v
    if c.startswith("!") and not c.startswith("!="):
        inner = _const_truth(c[1:])
        return None if inner is None else (not inner)
    for op in ("==", "!="):
        parts = _split_top(c, op)
        if len(parts) == 2:
            a, b = _unparen(parts[0]), _unparen(parts[1])
            if a and a == b:
                return op == "=="
            if re.fullmatch(r"-?\d+", a) and re.fullmatch(r"-?\d+", b):
                same = int(a) == int(b)
                return same if op == "==" else (not same)
            return None
    return None


def _literal_decides(cond):
    """True when the condition's outcome is fixed by a literal.

    [wide3-08 arm8, axis=dataflow] `if (true || sid == null)` keeps the null
    test in the spelling and deletes the decision.
    """
    c = _unparen(cond)
    if _lit_value(c) is not None:
        return True
    if _const_truth(c) is not None:
        return True
    for op, dead in (("||", True), ("&&", False)):
        for part in _split_top(c, op):
            if _lit_value(part) is dead:
                return True
            if _const_truth(part) is dead:
                return True
    return False

def _trailing_if_cond(masked, i):
    """The condition when the text before the `{` at `i` ends with `if (...)`."""
    head = masked[max(0, i - 400):i].rstrip()
    if not head.endswith(")"):
        return None
    depth = 0
    open_idx = -1
    for k in range(len(head) - 1, -1, -1):
        if head[k] == ")":
            depth += 1
        elif head[k] == "(":
            depth -= 1
            if depth == 0:
                open_idx = k
                break
    if open_idx < 0 or not re.search(r"\bif$", head[:open_idx].rstrip()):
        return None
    return head[open_idx + 1:len(head) - 1].strip()

def _enclosing_if_conds(body, idx):
    """Conditions of the `if (...)` blocks that lexically enclose `idx`."""
    masked = mask_strings(body)
    stack = []
    for i, ch in enumerate(masked[:idx]):
        if ch == "{":
            stack.append(_trailing_if_cond(masked, i))
        elif ch == "}":
            if stack:
                stack.pop()
    return [c for c in stack if c is not None]

def _line_end_expr(text, start):
    """The expression beginning at `start`, up to a depth-0 newline."""
    masked = mask_strings(text)
    depth = 0
    i = start
    while i < len(masked):
        c = masked[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                return text[start:i]
            depth -= 1
        elif c == "\n" and depth == 0:
            return text[start:i]
        i += 1
    return text[start:]

EMPTY_VALUES = ('""', "false", "null", "String()", "emptyList()", "emptyMap()",
                "0", "-1", "Unit")

def _subject_writes(body, subject):
    """(reset_offsets, seed_offsets) for `subject = <rhs>` inside `body`."""
    resets, seeds = [], []
    for m in re.finditer(r"\b%s\s*=(?!=)" % re.escape(subject), body):
        rhs = _line_end_expr(body, m.end()).strip()
        if rhs in EMPTY_VALUES or _lit_value(rhs) is not None:
            resets.append(m.start())
        else:
            seeds.append(m.start())
    return resets, seeds

def _id_expressions(body):
    """Identifiers/expressions standing for the edited server's id."""
    names = []
    for m in re.finditer(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?="
                         r"\s*editServer\s*\?\.\s*id\b", body):
        names.append(m.group(1))
    if re.search(r"editServer\s*\?\.\s*id", body):
        names.append("editServer?.id")
    return names

def _tests_null_of(cond, names):
    """True when `cond` compares one of `names` against null."""
    return _null_test_kind(cond, names) is not None


def _null_test_kind(cond, names):
    """'eq' for `<name> == null`, 'ne' for `<name> != null`, None otherwise.

    [wide4-12 arm4, axis=family-hole] The old regex `[=!]=\\s*null` matched
    BOTH directions, so `if (sid != null) { reset; return }` — which wipes the
    OAuth draft for every server that HAS an id, i.e. on every real edit — read
    as a proper null guard. Direction is part of the meaning: a reset branch on
    the then-side may only fire when the id is ABSENT.
    """
    c = _unparen(cond)
    hit = any(re.search(r"(?<![\w.])%s\b" % re.escape(n), c) for n in names)
    if not hit:
        return None
    eq = bool(re.search(r"(?<![=!<>])==\s*null\b", c)) or bool(
        re.search(r"\bnull\s*==(?!=)", c))
    ne = bool(re.search(r"!=\s*null\b", c)) or bool(
        re.search(r"\bnull\s*!=", c))
    if eq and not ne:
        return "eq"
    if ne and not eq:
        return "ne"
    return None


REPO_CALL_RE = re.compile(r"skillRepository\.([A-Za-z_]\w*)\s*\(")

def check_all_repo_calls(files):
    """arm6 [deep-audit-1039 B-05]: EVERY repository call in the Skills screen
    runs OFF Main.

    The file had four bare sites (delete from the list confirm, listSkillFiles
    inside a composition-phase remember, delete + update on the detail screen)
    while thirteen siblings were wrapped — the same defect class graded one
    call site at a time, which is how the four were missed. The criterion is
    the DECISION POINT (the dispatcher the call runs on): a site passes when it
    is lexically inside `withContext(Dispatchers.IO)` / `launch(Dispatchers.IO)`
    or when the repository method itself wraps internally (arm 3/4's
    alternative). Fail-closed when the file has no repository call at all —
    that means the anchor lost its subject rather than "nothing to judge".

    `skillRepository.skills` (the StateFlow property) is not a call and is
    excluded by construction: the pattern requires `(` right after the name.
    """
    text = files.get(SM_REL)
    if text is None:
        return ["missing file %s" % SM_REL]
    nc = strip_comments(text)
    masked = mask_strings(nc)
    repo_text = files.get(SKILL_REPO_REL)
    hits = [(m.start(), m.group(1)) for m in REPO_CALL_RE.finditer(nc)]
    # [wide4-12 arm3] the same receiver through a scope block: the benchmark is
    # "which object is called", not "which spelling names it".
    hits.extend(_scope_receiver_hits(
        nc, masked, "skillRepository.", _alias_receivers(nc, "skillRepository.")))
    hits.sort()
    if not hits:
        return ["%s: no `skillRepository.<method>(` call site found — the arm6 "
                "anchor lost its subject (fail-closed)" % SM_REL]
    failures = []
    for pos, method in hits:
        line = nc[:pos].count("\n") + 1
        if _io_wrapped_at(nc, masked, pos, IO_OPENERS):
            continue
        alt = False
        if repo_text is not None:
            alt, _ = repo_method_wraps_io(repo_text, method)
        if not alt:
            failures.append(
                "%s: bare (Main-thread) `skillRepository.%s(` at line %d — wrap it "
                "in withContext(Dispatchers.IO), or move the IO into the repository "
                "method (the criterion is the dispatcher the call runs on, not the "
                "spelling)" % (SM_REL, method, line))
    return failures


def all_calls_offline(text_nc, text_masked, call):
    """True when EVERY occurrence of `call` sits inside an OPENER block."""
    idxs = []
    start = 0
    while True:
        j = text_nc.find(call, start)
        if j < 0:
            break
        idxs.append(j)
        start = j + 1
    if not idxs:
        return False, "no call site found (%s)" % call
    bad = [
        text_nc[:i].count("\n") + 1
        for i in idxs
        if not _io_wrapped_at(text_nc, text_masked, i)
    ]
    if bad:
        return False, "bare (Main-thread) call site(s) at line(s) %s" % bad
    return True, ""


def _match_brace(masked, j):
    """Index just past the `}` matching the `{` at `j` (masked text)."""
    close = match_bracket(masked, j)
    return len(masked) if close < 0 else close + 1


def repo_method_wraps_io(text, name, hops=1):
    """True when `fun <name>(...)` wraps its work inside OPENER.

    Handles a block body (brace-matched, comments stripped, strings masked)
    and an expression body — either the wrap itself (`fun f(): X =
    withContext(Dispatchers.IO) { ... }`, the shape every `suspend fun` in
    SkillRepository uses) or ONE delegation hop (`fun f() = load()`). Not a
    call-graph fixpoint — fail-closed when the chain does not resolve.
    """
    nc = strip_comments(text)
    masked = mask_strings(nc)
    m = re.search(r"\bfun\s+%s\s*\(" % re.escape(name), nc)
    if not m:
        return False, "no `fun %s(` declaration" % name
    i = m.end()  # first char after '('
    depth = 1
    while i < len(masked) and depth:
        if masked[i] == "(":
            depth += 1
        elif masked[i] == ")":
            depth -= 1
        i += 1
    # The body starts at the first `{` after the parameter list; an `=`
    # before it (and not inside the params) means an expression body. The
    # return-type annotation `: Foo` sits between the two and is skipped by
    # searching rather than by consuming it.
    body_open = masked.find("{", i)
    assign = -1
    stop = body_open if body_open >= 0 else len(masked)
    k = i
    while k < stop:
        if masked[k] == "=" and masked[k - 1:k] not in ("=", "!", "<", ">") \
                and masked[k + 1:k + 2] != "=":
            assign = k
            break
        if masked[k] == "\n":
            break
        k += 1
    if assign >= 0:
        line = nc[assign:].split("\n", 1)[0]
        if OPENER in line:
            return True, ""
        call = re.search(r"([A-Za-z_][A-Za-z0-9_]*)\s*\(", line)
        if call and hops > 0:
            return repo_method_wraps_io(text, call.group(1), hops - 1)
        return False, "`%s` expression body is not inside %s" % (name, OPENER)
    if body_open < 0:
        return False, "`%s`: body form not recognised" % name
    body = nc[body_open:_match_brace(masked, body_open)]
    if OPENER in body:
        return True, ""
    if hops > 0:
        callees = re.findall(r"([A-Za-z_][A-Za-z0-9_]*)\s*\(", body)
        for callee in callees:
            if callee == name:
                continue
            ok, _ = repo_method_wraps_io(text, callee, hops - 1)
            if ok:
                return True, ""
    return False, "`%s` body is not inside %s" % (name, OPENER)


def check_paste(text):
    """arms 1-2: the paste import's repository write must be off Main."""
    nc = strip_comments(text)
    masked = mask_strings(nc)
    ok, detail = inside_io_wrap(nc, masked)
    return [] if ok else ["paste import not on IO — %s" % detail]


def check_reload(files):
    """arms 3-4: every reloadFromDisk() call in the two screens is OFF Main.

    Offline = the call site is lexically inside an OPENER block in its own
    file, OR the repository entry point wraps internally (decision point =
    the thread the call runs on, not the spelling).
    """
    failures = []
    for screen_rel, call, repo_rel, repo_fn in RELOAD_SITES:
        text = files.get(screen_rel)
        if text is None:
            failures.append("missing file %s" % screen_rel)
            continue
        nc = strip_comments(text)
        masked = mask_strings(nc)
        ok, detail = all_calls_offline(nc, masked, call)
        if ok:
            continue
        repo_text = files.get(repo_rel)
        alt = False
        if repo_text is not None:
            alt, _ = repo_method_wraps_io(repo_text, repo_fn)
        if not alt:
            failures.append("%s: reload not off Main — %s" % (screen_rel, detail))
    return failures


# [deep-audit-1040 B-04] The MCP settings screen plus the two per-session
# sheets. Same defect class as arm6 one file over.
MCP_OAUTH_REL = ("src/android/app/src/main/java/com/rikkaminis/app/mcp/oauth/"
                 "MCPOAuthStore.kt")
SHEET_SKILLS_REL = ("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/"
                    "SessionSkillsSheet.kt")
SHEET_MCPS_REL = ("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/"
                  "SessionMcpsSheet.kt")

# [deep-audit-1041 B-03] The env-var screen — the sixth screen of the family.
ENVVAR_SCREEN_REL = ("src/android/app/src/main/java/com/rikkaminis/app/ui/settings/"
                     "EnvironmentVariablesScreen.kt")
ENVVAR_REPO_REL = ("src/android/app/src/main/java/com/rikkaminis/app/data/repository/"
                   "EnvVarRepository.kt")

# (screen file, callee prefix, file that declares those methods)
B04_CALL_SITES = (
    (MCP_REL, "mcpRepository.", MCP_REPO_REL),
    (MCP_REL, "MCPOAuthStore.", MCP_OAUTH_REL),
    (SHEET_SKILLS_REL, "skillRepository.", SKILL_REPO_REL),
    (SHEET_MCPS_REL, "mcpRepository.", MCP_REPO_REL),
)

B03_CALL_SITES = (
    (ENVVAR_SCREEN_REL, "envVarRepository.", ENVVAR_REPO_REL),
)

# Tokens that mean "this body reaches the disk (or Keystore-backed prefs)".
IO_MARKERS = (
    "File(", "rawQuery(", "execSQL(", "readText(", "writeText(", "readLines(",
    "mkdirs(", "deleteRecursively(", "renameTo(", "listFiles(", "walkTopDown(",
    "openFileOutput(", "openFileInput(", "contentResolver",
    "EncryptedSharedPreferences", "SharedPreferences", ".edit()", "prefs(",
    "save()", "deleteBridgeFile(", "writeBridgeFile(", "reconcileForServer(",
)


PREFS_ACCESS_SUFFIXES = (".getString(", ".getInt(", ".getBoolean(",
                          ".contains(", ".remove(", ".edit()")


def _prefs_fields(text_nc):
    """Field names whose DECLARATION is a (encrypted) SharedPreferences store.

    Derived from the declaration rather than hard-coded, so renaming
    `encryptedPrefs` cannot silently disarm the arm, and a `getString` on a
    JSONObject / Bundle is still read as pure. Matches both the typed form
    (`val x: SharedPreferences by lazy { ... }`) and the inferred form
    (`val x = ...EncryptedSharedPreferences...` / `...EncryptedPrefsFactory...`).
    """
    names = set()
    for m in re.finditer(
            r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*:[^=\n]*\bSharedPreferences\b",
            text_nc):
        names.add(m.group(1))
    for m in re.finditer(
            r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*=[^\n]*?"
            r"(?:EncryptedSharedPreferences|EncryptedPrefsFactory)",
            text_nc):
        names.add(m.group(1))
    return names


def _next_nonspace(masked, i):
    """(char, index) of the next non-whitespace character at or after `i`."""
    n = len(masked)
    while i < n and masked[i] in " \t\r\n":
        i += 1
    if i >= n:
        return "", n
    return masked[i], i


# A line that starts with one of these continues the expression above it.
EXPR_CONTINUATION_CHARS = ".&|?:+*%<>=!-"
# ... and so do these keywords (`= try { } catch (e) { }` and friends).
EXPR_CONTINUATION_WORDS = ("catch", "finally", "else")


def _expr_body_nc(text_nc, masked, k):
    """Full text of the expression body that follows the `=` at index `k`.

    The old implementation kept only the remainder of the FIRST LINE, so an
    expression body whose value starts on the next line was reduced to `"="`.
    The function then looked marker-free and was classified pure — which is
    exactly how `MCPOAuthStore.isAuthorized` (value
    `tokens(context, server)?.accessToken?.isNotEmpty() == true` on the
    following line) escaped the off-main arm.

    Extraction rules:
      * a brace-headed form (`withContext(...) { }`, `try { }`, `when { }`,
        `runCatching { }`, ...) is taken as a balanced block, so the whole
        block is visible to the callers;
      * otherwise the expression continues while the next non-blank line
        starts with a continuation operator or keyword, and stops at a
        declaration boundary — so an expression body never swallows the
        function that follows it.
    """
    n = len(masked)
    j = k + 1
    while j < n and masked[j] in " \t\r\n":
        j += 1
    end = j
    i = j
    depth = 0
    while i < n:
        c = masked[i]
        if c in "([":
            depth += 1
        elif c in ")]":
            depth -= 1
        elif c == "{" and depth == 0:
            i = _match_brace(masked, i)
            end = i
            continue
        elif c == ";" and depth == 0:
            break
        elif c == "\n" and depth == 0:
            nxt, ni = _next_nonspace(masked, i + 1)
            if not nxt:
                break
            wm = re.match(r"[A-Za-z_]\w*", masked[ni:])
            word = wm.group(0) if wm else ""
            if word in EXPR_CONTINUATION_WORDS or nxt in EXPR_CONTINUATION_CHARS:
                i = ni
                continue
            break
        i += 1
        end = i
    return text_nc[j:end]


def _fun_body_nc(text_nc, masked, name):
    """(body text, 'block'|'expr') for `fun <name>(...)`; (None, reason) if the
    declaration is absent or its body form is not recognised."""
    m = re.search(r"\bfun\s+%s\s*\(" % re.escape(name), text_nc)
    if not m:
        return None, "no `fun %s(` declaration in this file" % name
    i = m.end()
    depth = 1
    while i < len(masked) and depth:
        if masked[i] == "(":
            depth += 1
        elif masked[i] == ")":
            depth -= 1
        i += 1
    # `i` is now just past the closing paren; from here the body FORM
    # decides the shape: `{ ... }` (block) or `= <expr>` (expression).
    n = len(masked)
    k = i
    while k < n:
        c = masked[k]
        if c == "=" and masked[k - 1:k] not in ("=", "!", "<", ">") \
                and masked[k + 1:k + 2] != "=":
            return _expr_body_nc(text_nc, masked, k), "expr"
        if c == "{":
            return text_nc[k:_match_brace(masked, k)], "block"
        if c == "\n":
            # The declaration may continue on the next line (a return type,
            # the `=` of an expression body, or the opening brace).
            nxt, ni = _next_nonspace(masked, k + 1)
            if nxt in (":", "=", "{", "where"):
                k = ni
                continue
            break
        k += 1
    return None, "`%s`: body form not recognised" % name


def method_io_state(text, name, hops=2, _seen=None):
    """('io'|'pure'|'unknown', reason) — does `fun <name>` reach the disk?

    Direct evidence is an IO_MARKERS token in the body; otherwise follow up to
    `hops` delegation steps into callees declared in the same file. Callees
    that are NOT declared here (stdlib collectors, renames) end the walk rather
    than counting as I/O, so a body that merely maps/filters is not misread.
    The caller treats 'unknown' as I/O for the *top-level* call site only —
    that is the fail-closed half of this arm.
    """
    seen = set() if _seen is None else _seen
    if name in seen:
        return "pure", "recursion"
    seen.add(name)
    nc = strip_comments(text)
    masked = mask_strings(nc)
    body, kind = _fun_body_nc(nc, masked, name)
    if body is None:
        return "unknown", kind
    for marker in IO_MARKERS:
        if marker in body:
            return "io", "`%s` body uses `%s`" % (name, marker)
    # A read through a field that declares itself as a SharedPreferences store is
    # disk-backed even when the body carries no IO_MARKERS token (`getValue`'s
    # whole body is `encryptedPrefs.getString(key, null)`). Derived from the
    # declaration so neither the field name nor the method name is hard-coded.
    for field in _prefs_fields(nc):
        for suffix in PREFS_ACCESS_SUFFIXES:
            if field + suffix in body:
                return "io", "`%s` reads the prefs-backed store `%s`" % (name, field)
    if hops > 0:
        # Only follow BARE calls (`save()`, `parseImportRoot(x)`). A member call
        # (`list.add(…)`, `root.keys()`) must not resolve to a same-named
        # declaration in the repository — that name collision is exactly how
        # `previewImport` got misread as I/O through `MutableList.add` →
        # `fun add(server)` → `save()`.
        for cm in re.finditer(r"(?<![.\w])([A-Za-z_][A-Za-z0-9_]*)\s*\(", body):
            callee = cm.group(1)
            if callee == name:
                continue
            state, why = method_io_state(text, callee, hops - 1, seen)
            if state == "io":
                return "io", why
    return "pure", "`%s` (%s body) has no I/O marker" % (name, kind)


def check_b04_offmain(files):
    """arm7: every disk-I/O repository / store call in the B-04 files is OFF Main.

    The criterion is the decision point (does this call touch disk, and on
    which dispatcher does it run), so the repository-side alternative accepted
    by arms 3/4 also passes: a call site may be bare when the callee wraps its
    own work in `withContext(Dispatchers.IO)`.
    """
    return check_offmain_calls(files, B04_CALL_SITES, "B-04")


def check_b03_offmain(files):
    """arm9 [deep-audit-1041 B-03]: the env-var screen's repository calls are
    OFF Main — same criterion as arm7, applied to the sixth screen of the family.

    Runs only when the map MODELS this screen (either key present). The arm1-8
    fixtures do not, and reporting them red would be a false alarm that hides
    their own regressions. On the real tree `run_checks` seeds both keys (with
    value None when a read failed), so an unreadable file is still fail-closed.
    """
    if ENVVAR_SCREEN_REL not in files and ENVVAR_REPO_REL not in files:
        return []
    return check_offmain_calls(files, B03_CALL_SITES, "B-03")


def _alias_receivers(text_nc, prefix):
    """Variable names bound to the object `prefix` names (`val x = <obj>`)."""
    obj = prefix.rstrip(".").split(".")[-1]
    pat = re.compile(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=\s*"
                     r"(?:[A-Za-z_][\w.]*\.)?%s\b" % re.escape(obj))
    return [m.group(1) for m in pat.finditer(text_nc)]


# Scope functions that hand the receiver to a lambda under another name.
SCOPE_FN_RE = r"(let|run|also|apply)"


def _scope_receiver_hits(text_nc, masked, prefix, aliases):
    """(pos, method) for calls made on a scope block's IMPLICIT receiver.

    [wide4-12 arm3, axis=family-hole] `MCPOAuthStore.let { it.signOut(ctx, id) }`
    — and `with(MCPOAuthStore) { signOut(…) }` — reach the same object through
    the lambda receiver. The old criterion only understood
    `val store = MCPOAuthStore`, so the call was invisible: `.let {` is not
    `.<method>(` and `it` is not an alias binding.

      .let / .also   → receiver is `it` (or the explicit lambda parameter)
      .run / .apply  → receiver is `this` (or the explicit parameter)
      with(obj) { }  → receiver is `this` (or the explicit parameter)
    """
    obj = prefix.rstrip(".").split(".")[-1]
    receivers = [obj] + list(aliases)
    out = []
    for recv in dict.fromkeys(receivers):
        # `com.….oauth.MCPOAuthStore.let { … }` is the same receiver as the
        # short spelling; a qualified prefix must not hide it.
        qualified = r"(?:[A-Za-z_][\w.]*\.)?" + re.escape(recv)
        patterns = [
            (r"(?<![\w.])%s\s*\.\s*let\s*\{" % qualified, False),
            (r"(?<![\w.])%s\s*\.\s*also\s*\{" % qualified, False),
            (r"(?<![\w.])%s\s*\.\s*run\s*\{" % qualified, True),
            (r"(?<![\w.])%s\s*\.\s*apply\s*\{" % qualified, True),
            (r"(?<![\w.])with\s*\(\s*%s\s*\)\s*\{" % qualified, True),
        ]
        for pat, implicit_this in patterns:
            for m in re.finditer(pat, masked):
                op = masked.find("{", m.end() - 1)
                if op < 0:
                    continue
                end = _match_brace(masked, op)
                body = text_nc[op + 1:end - 1]
                names = {"it"} if not implicit_this else {"this"}
                pm = re.match(
                    r"\s*(?:\(\s*([A-Za-z_]\w*)[^)]*\)|([A-Za-z_]\w*))\s*->", body)
                if pm:
                    names = {pm.group(1) or pm.group(2)}
                alt = "|".join(re.escape(n) for n in sorted(names))
                if implicit_this:
                    # bare `method(` = a call on `this` inside the block
                    for mm in re.finditer(
                            r"(?:\b(?:%s)\s*\.\s*)?\b([A-Za-z_]\w*)\s*\(" % alt,
                            body):
                        out.append((op + 1 + mm.start(0), mm.group(1)))
                else:
                    for mm in re.finditer(
                            r"\b(?:%s)\s*\.\s*([A-Za-z_]\w*)\s*\(" % alt, body):
                        out.append((op + 1 + mm.start(0), mm.group(1)))
    return out


def check_offmain_calls(files, sites, label):
    """Shared criterion for arms 7 and 9: a call passes when it sits inside an
    IO opener, or when the callee wraps its own work (repository-side fix).

    Fail-closed twice: a (file, prefix) pair with no call site at all means the
    anchor lost its subject, and a called method with no declaration to judge
    cannot be proven pure.
    """
    failures = []
    for screen_rel, prefix, decl_rel in sites:
        text = files.get(screen_rel)
        if text is None:
            failures.append("missing file %s" % screen_rel)
            continue
        nc = strip_comments(text)
        masked = mask_strings(nc)
        hits = [(m.start(), m.group(1))
                for m in re.finditer(re.escape(prefix) + r"([A-Za-z_]\w*)\s*\(", nc)]
        # [wide3-08 arm7, axis=spelling] `val store = com.….MCPOAuthStore` then
        # `store.setClientSecret(…)` is the same call through another name: the
        # criterion binds the RECEIVER, so the alias set is derived from the
        # bindings in this file instead of spelling the object once.
        aliases = _alias_receivers(nc, prefix)
        for alias in aliases:
            hits.extend((m.start(), m.group(1)) for m in re.finditer(
                r"(?<![\w.])%s\s*\.\s*([A-Za-z_]\w*)\s*\(" % re.escape(alias), nc))
        # [wide4-12 arm3] …and a scope block hands the same receiver to `it`
        # (`.let` / `.also`) or to `this` (`.run` / `.apply` / `with(obj)`) —
        # the call is spelled on the receiver, not on the object.
        hits.extend(_scope_receiver_hits(nc, masked, prefix, aliases))
        hits.sort()
        if not hits:
            failures.append(
                "%s: no `%s<method>(` call site found — the %s anchor lost its "
                "subject (fail-closed)" % (screen_rel, prefix, label))
            continue
        decl = files.get(decl_rel)
        if decl is None:
            failures.append(
                "missing file %s (cannot judge the I/O-ness of the `%s` calls in "
                "%s)" % (decl_rel, prefix, screen_rel))
            continue
        for pos, method in hits:
            state, why = method_io_state(decl, method)
            if state == "pure":
                continue
            if _io_wrapped_at(nc, masked, pos, IO_OPENERS):
                continue
            alt, _ = repo_method_wraps_io(decl, method)
            if alt:
                continue
            line = nc[:pos].count("\n") + 1
            failures.append(
                "%s: `%s%s(` at line %d runs on Main while its callee performs "
                "disk I/O (%s) — wrap it in withContext(Dispatchers.IO), or move "
                "the IO into the repository/store method (the criterion is the "
                "dispatcher the call runs on, not the spelling)"
                % (screen_rel, prefix, method, line, why))
    return failures


def _launched_effects(nc, masked):
    """[(key tokens, body text)] for every `LaunchedEffect(...) { ... }`."""
    out = []
    for m in re.finditer(r"\bLaunchedEffect\s*\(", nc):
        start = m.end()
        depth = 1
        i = start
        while i < len(masked) and depth:
            if masked[i] == "(":
                depth += 1
            elif masked[i] == ")":
                depth -= 1
            i += 1
        if depth:
            continue
        keys = [k.strip() for k in nc[start:i - 1].split(",") if k.strip()]
        op = masked.find("{", i)
        if op < 0:
            continue
        out.append((keys, nc[op:_match_brace(masked, op)]))
    return out


SEED_DRAFT_MARKERS = (
    "var oauthClientSecret by remember",
    "var oauthAuthorizedSeed by remember",
)


def check_seed_effect_keys(files):
    """arm8: a seed draft's `remember` keys must be COVERED by the keys of the
    LaunchedEffect that seeds it (direction matters: a narrower effect stops
    re-running on a change the draft still re-derives on).
    """
    text = files.get(MCP_REL)
    if text is None:
        return ["missing file %s" % MCP_REL]
    nc = strip_comments(text)
    masked = mask_strings(nc)
    found = False
    for marker in SEED_DRAFT_MARKERS:
        idx = nc.find(marker)
        if idx < 0:
            continue
        found = True
        j = idx + len(marker)
        while j < len(nc) and nc[j] in " \t":
            j += 1
        keys = [] if nc[j:j + 1] == "{" else _remember_keys(nc, marker)
        if keys is None:
            keys = []
        if not keys:
            return ["%s: `%s` carries no remember key — the draft never re-seeds "
                    "when the edited server changes" % (MCP_REL, marker)]
        subject = marker.split(" by ")[0].replace("var ", "")
        effects = [(k, b) for k, b in _launched_effects(nc, masked)
                   if "%s =" % subject in b]
        if len(effects) != 1:
            return ["%s: expected exactly 1 LaunchedEffect seeding `%s`, found %d "
                    "— fail-closed" % (MCP_REL, subject, len(effects))]
        missing = [k for k in keys if k not in effects[0][0]]
        if missing:
            return ["%s: `%s` is keyed %s but the LaunchedEffect seeding it is "
                    "keyed %s (missing %s) — an editServer change that keeps the "
                    "id resets the draft and the reader never re-runs"
                    % (MCP_REL, marker, keys, effects[0][0], missing)]
        # [wide3-08 arm8, axis=dataflow] the effect must actually SEED on the
        # non-null path, and the reset must be reached only when the id is null.
        # `if (true || sid == null)` (and an effect that only clears) keeps every
        # anchor above green while the draft is wiped on every edit.
        body = effects[0][1]
        resets, seeds = _subject_writes(body, subject)
        if not seeds:
            return ["%s: the LaunchedEffect for `%s` only clears the draft — no "
                    "assignment reads the stored value, so the form stays empty"
                    % (MCP_REL, subject)]
        if resets:
            # the guard criterion only has a subject when the effect resets the
            # draft at all; a seed-only effect cannot wipe anything.
            id_exprs = _id_expressions(body)
            if not id_exprs:
                return ["%s: the LaunchedEffect seeding `%s` resets the draft but no "
                        "longer binds the edited server's id (`val x = "
                        "editServer?.id`) — fail-closed" % (MCP_REL, subject)]
            for pos in resets:
                guards = [c for c in _enclosing_if_conds(body, pos)
                          if _null_test_kind(c, id_exprs) == "eq"
                          and not _literal_decides(c)]
                if not guards:
                    return ["%s: the `%s` reset is not inside a live `<id> == null` "
                            "guard — the draft is wiped on every editServer change and "
                            "the seeding path never runs (wide3-08 arm8)"
                            % (MCP_REL, subject)]
    if not found:
        return ["%s: neither OAuth seed draft (`%s`) was found — fail-closed"
                % (MCP_REL, "` / `".join(SEED_DRAFT_MARKERS))]
    return []


def check_editor_keys(files):
    """arm5: the skill editor's remember keys + shape anchor literal."""
    text = files.get(EDITOR_REL)
    if text is None:
        return ["missing file %s" % EDITOR_REL]
    nc = strip_comments(text)
    failures = []
    for var, require in EDITOR_KEYS:
        decl = re.search(r"\bvar\s+%s\s+by\s+remember" % var, nc)
        if not decl:
            failures.append(
                "%s: `var %s by remember` declaration not found" % (EDITOR_REL, var))
            continue
        keys = re.match(r"\s*\(([^)]*)\)", nc[decl.end():])
        if not keys:
            failures.append("%s: `%s` remember has no key" % (EDITOR_REL, var))
            continue
        k = keys.group(1).strip()
        if k in ("", "Unit"):
            failures.append(
                "%s: `%s` remember key list is empty/Unit" % (EDITOR_REL, var))
        elif require and require not in k:
            failures.append(
                "%s: `%s` remember keys lack `%s`" % (EDITOR_REL, var, require))
    if INITIAL_LITERAL not in nc:
        failures.append(
            "%s: shape anchor `%s` missing" % (EDITOR_REL, INITIAL_LITERAL))
    return failures


def _remember_keys(nc, marker):
    """Ordered key tokens of the `remember(...)` call that follows `marker`.

    Paren-matched, so a nested call inside a key (a `collectAsState()` source)
    cannot truncate the list the way a first-`)` scan would. Returns None when
    the marker or a balanced key list is absent (caller reports fail-closed).
    """
    idx = nc.find(marker)
    if idx < 0:
        return None
    op = nc.find("(", idx + len(marker))
    if op < 0:
        return None
    depth = 0
    i = op
    while i < len(nc):
        c = nc[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                break
        i += 1
    if depth != 0:
        return None
    return [k.strip() for k in nc[op + 1:i].split(",") if k.strip()]


def check_editor_key_alignment(files):
    """The editor draft must re-derive on everything the content it seeds from
    re-derives on (initialContent keys ⊆ editContent keys).

    binds the DECISION POINT (which changes re-seed the draft), not a literal
    key list: any future key added to one remember and not the match is red.
    Direction is deliberate — an EXTRA key on the draft is not a defect, a
    MISSING one is (the draft then freezes on a stale snapshot).
    """
    text = files.get(EDITOR_REL)
    if text is None:
        return ["missing file %s" % EDITOR_REL]
    nc = strip_comments(text)
    initial = _remember_keys(nc, INITIAL_LITERAL)
    edit = _remember_keys(nc, "var editContent by remember")
    if not initial or not edit:
        return ["%s: could not read the initialContent/editContent remember key "
                "lists — fail-closed" % EDITOR_REL]
    missing = [k for k in initial if k not in edit]
    if missing:
        return ["%s: `editContent` remember keys are missing %s that "
                "`initialContent` re-derives from — the editor draft freezes on "
                "the pre-read snapshot and Save writes it back over an "
                "out-of-band edit" % (EDITOR_REL, missing)]
    return []


# ── [R3-t19] round-3 (dual-gate) arms ─────────────────────────────────────
# The round-3 audit attacked the seams AROUND this gate, not its arms: two
# mutations kept all fifteen arms green while the user-visible outcome flipped.
#
#   arm10  the viewer's base value must BE the loaded disk base on the
#          sibling-file path (witness 50). This gate already asserts that both
#          `remember(...)` blocks carry `loadedDiskBase` in their keys; it never
#          asserted that the value built from those keys is consumed. A
#          `skillFileViewerBase` that returns "" on the sibling branch keeps
#          every key green while the viewer is blank AND Save writes the blank
#          back over the real script.
#   arm11  the form's assembled OAuth config must carry the custom redirect URI
#          the user typed (witness 49). The draft state's `remember(editServer)`
#          re-seed is asserted by mcp_oauth_lifecycle_check C4; the assembly
#          step that decides whether that value ever leaves the form is not —
#          `redirectUri = null` silently wipes it on every save.
#
# Both follow the value (`value_sources` / const folding on the branch a
# sibling file takes), never the spelling.

VIEWER_BASE_FUN = "skillFileViewerBase"
LOADED_PARAM = "loadedDiskBase"
MCP_CFG_CALL = "MCPOAuthConfig("
REDIRECT_FIELD = "redirectUri"
REDIRECT_DRAFT = "oauthRedirectUri"
MD_FLAG_RE = re.compile(
    r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]{0,40})?=\s*[^\n]*SKILL\.md")


def _named_args(code, open_idx):
    """{name: expr} of the named arguments of the call whose '(' is open_idx."""
    out = {}
    for a, b in call_args(code, open_idx):
        seg = code[a:b]
        m = re.match(r"\s*([A-Za-z_]\w*)\s*=(?![=>])\s*", seg)
        if m:
            out.setdefault(m.group(1), seg[m.end():].strip())
    return out


def _mentions(text, ident):
    """True when `ident` appears in `text` as a whole identifier."""
    return re.search(r"(?<![\w.`])" + re.escape(ident) + r"(?![\w`])",
                     text) is not None


def _subst_fold(cond, env):
    """Boolean value of `cond` with boolean identifiers substituted from env.

    Substituting the caller-derived "this file is SKILL.md" flag is how the
    gate asks the only question that matters for a viewer: *what does a
    sibling file get?* None = undecidable (caller is fail-closed).
    """
    c = cond
    for name, val in env.items():
        c = re.sub(r"(?<![\w.`])" + re.escape(name) + r"(?![\w`])",
                   "true" if val else "false", c)
    return const_bool(strip_parens(c))


def _split_else_expr(rest):
    """then / else halves of `A else B` at depth 0."""
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


def _value_on_path(expr, env, depth=0):
    """The value expression on the path where `env` holds (if/else folded)."""
    e = strip_parens(expr.strip())
    if not e or depth > 4:
        return e
    m = re.match(r"if\s*\(", e)
    if not m:
        return e
    close = match_bracket(e, m.end() - 1)
    if close < 0:
        return e
    cond = e[m.end():close]
    then_part, else_part = _split_else_expr(e[close + 1:].strip())
    v = _subst_fold(cond, env)
    if v is True:
        return _value_on_path(then_part, env, depth + 1)
    if v is False:
        return _value_on_path(else_part, env, depth + 1) if else_part.strip() else "Unit"
    return e


def check_viewer_base_carry(files):
    """arm10: on the sibling path the viewer base must BE the loaded disk base."""
    text = files.get(EDITOR_REL)
    if text is None:
        return ["missing file %s" % EDITOR_REL]
    nc = strip_comments(text)
    masked = mask_strings(nc)
    sig = re.search(r"\bfun\s+" + VIEWER_BASE_FUN + r"\s*\(", masked)
    if sig is None:
        return ["%s: `fun %s(` not found — the viewer base lost its subject "
                "(fail-closed)" % (EDITOR_REL, VIEWER_BASE_FUN)]
    popen = sig.end() - 1
    pclose = match_bracket(masked, popen)
    if pclose < 0:
        return ["%s: `%s` parameter list is unbalanced — fail-closed"
                % (EDITOR_REL, VIEWER_BASE_FUN)]
    params = masked[popen + 1:pclose]

    # Which parameter receives the SKILL.md flag / the loaded base? Derived
    # from the CALLERS, so a rename of either parameter is not a gate failure.
    md_flag = MD_FLAG_RE.search(nc)
    md_param = loaded_param = None
    for call_m in re.finditer(r"\b" + VIEWER_BASE_FUN + r"\s*\(", masked):
        if call_m.start() == sig.start():
            continue
        for name, expr in _named_args(masked, call_m.end() - 1).items():
            if md_flag is not None and _mentions(expr, md_flag.group(1)):
                md_param = md_param or name
            if _mentions(expr, LOADED_PARAM):
                loaded_param = loaded_param or name
    if md_flag is None or md_param is None:
        return ["%s: no caller passes a `… = <expr naming \"SKILL.md\">` flag into "
                "`%s` — the sibling path cannot be decided (fail-closed)"
                % (EDITOR_REL, VIEWER_BASE_FUN)]
    if loaded_param is None or not re.search(r"\b" + re.escape(loaded_param) + r"\s*:",
                                             params):
        return ["%s: no caller passes the loaded disk base into `%s` — fail-closed"
                % (EDITOR_REL, VIEWER_BASE_FUN)]

    body = function_body(masked, VIEWER_BASE_FUN)
    if body is None:
        return ["%s: `%s` body cannot be delimited — fail-closed"
                % (EDITOR_REL, VIEWER_BASE_FUN)]
    b_start, b_end, kind = body
    seg = masked[b_start:b_end]
    env = {md_param: False}

    def _carries(expr):
        return any(_mentions(s, loaded_param) for s in value_sources(expr))

    if kind == "expr":
        value = _value_on_path(seg, env)
        if not _carries(value):
            return ["%s: `%s` returns `%s` for a SIBLING file, which does not read "
                    "`%s` — the viewer renders blank (and Save writes the blank "
                    "back over the real file)" % (EDITOR_REL, VIEWER_BASE_FUN,
                                                 " ".join(value.split())[:70],
                                                 loaded_param)]
        return []

    reachable = []
    for rm in re.finditer(r"\breturn\b", seg):
        live = True
        for _kind, cond in enclosing_conditions(seg, rm.start()):
            if _subst_fold(cond, env) is False:
                live = False
                break
        if live:
            reachable.append(seg[rm.end():expr_end(seg, rm.end())].strip())
    if not reachable:
        return ["%s: no `return` in `%s` is reachable for a SIBLING file — "
                "fail-closed" % (EDITOR_REL, VIEWER_BASE_FUN)]
    for value in reachable:
        if not _carries(value):
            return ["%s: `%s` returns `%s` on a reachable sibling path, which does "
                    "not read `%s` — the viewer renders blank (and Save writes the "
                    "blank back over the real file)"
                    % (EDITOR_REL, VIEWER_BASE_FUN,
                       " ".join(value.split())[:70], loaded_param)]
    return []


def check_oauth_config_assembly(files):
    """arm11: the assembled OAuth config must carry the typed redirect URI."""
    text = files.get(MCP_REL)
    if text is None:
        return ["missing file %s" % MCP_REL]
    nc = strip_comments(text)
    masked = mask_strings(nc)
    body = function_body(masked, "currentOAuthConfig")
    if body is None:
        return ["%s: `fun currentOAuthConfig()` not found — the form's OAuth "
                "assembly lost its subject (fail-closed)" % MCP_REL]
    start, end, _kind = body
    seg = masked[start:end]
    cfg = re.search(re.escape(MCP_CFG_CALL), seg)
    if cfg is None:
        return ["%s: currentOAuthConfig() no longer builds MCPOAuthConfig( — "
                "fail-closed" % MCP_REL]
    args = _named_args(seg, cfg.end() - 1)
    expr = args.get(REDIRECT_FIELD)
    if not expr:
        return ["%s: MCPOAuthConfig( is built without `%s = …` — a custom "
                "redirect_uri is dropped instead of round-tripped"
                % (MCP_REL, REDIRECT_FIELD)]
    if not any(_mentions(s, REDIRECT_DRAFT) for s in value_sources(expr)):
        return ["%s: MCPOAuthConfig(%s = %s) does not come from the form's draft "
                "state `%s` — a typed custom redirect_uri never leaves the form, so "
                "it evaporates on save" % (MCP_REL, REDIRECT_FIELD,
                                           " ".join(expr.split())[:70],
                                           REDIRECT_DRAFT)]
    if not re.search(r"\bvar\s+" + re.escape(REDIRECT_DRAFT) + r"\b", nc):
        return ["%s: no `var %s` draft state — fail-closed"
                % (MCP_REL, REDIRECT_DRAFT)]
    return []


def _read(root, rel):
    with open(os.path.join(root, rel), encoding="utf-8") as fh:
        return fh.read()


def run_checks_map(files):
    """All arms over a {rel_path: text} map (real tree or fixtures)."""
    failures = []
    del LEX_FAILURES[:]
    sm = files.get(SM_REL)
    if sm is None:
        failures.append("missing file %s" % SM_REL)
    else:
        failures.extend(check_paste(sm))
    failures.extend(check_reload(files))
    failures.extend(check_all_repo_calls(files))
    failures.extend(check_editor_keys(files))
    failures.extend(check_editor_key_alignment(files))
    failures.extend(check_b04_offmain(files))
    failures.extend(check_b03_offmain(files))
    failures.extend(check_seed_effect_keys(files))
    failures.extend("arm10: " + m for m in check_viewer_base_carry(files))
    failures.extend("arm11: " + m for m in check_oauth_config_assembly(files))
    for msg in dict.fromkeys(LEX_FAILURES):
        failures.append(msg)
    return failures


def run_checks(repo_root):
    files = {}
    for rel in (SM_REL, MCP_REL, SKILL_REPO_REL, MCP_REPO_REL, MCP_OAUTH_REL,
                SHEET_SKILLS_REL, SHEET_MCPS_REL, ENVVAR_SCREEN_REL,
                ENVVAR_REPO_REL):
        try:
            files[rel] = _read(repo_root, rel)
        except OSError:
            pass
    # Seed the arm9 anchors explicitly: an unreadable file must read as
    # "missing file" (fail-closed), not as "this fixture set does not model the
    # screen" (which is how `check_b03_offmain` treats an absent key).
    for rel in (ENVVAR_SCREEN_REL, ENVVAR_REPO_REL):
        files.setdefault(rel, None)
    return run_checks_map(files)


# ── selftest fixtures ────────────────────────────────────────────────────

GOOD_SM = '''package com.rikkaminis.app.ui.settings

fun sample(pasteContent: String, scope: CoroutineScope, onDismiss: () -> Unit) {
    scope.launch {
        try {
            isLoading = true
            val result = withContext(Dispatchers.IO) {
                skillRepository.importFromContent(pasteContent)
            }
            if (result != null) onDismiss()
        } catch (e: Exception) {
            errorText = "Error: ${e.message}"
        } finally {
            isLoading = false
        }
    }
}
'''

BAD_MAIN_THREAD = GOOD_SM.replace(
    """            val result = withContext(Dispatchers.IO) {
                skillRepository.importFromContent(pasteContent)
            }""",
    "            val result = skillRepository.importFromContent(pasteContent)")

BAD_DOUBLE_SITE = GOOD_SM + '''
fun sample2(pasteContent: String) {
    val result = skillRepository.importFromContent(pasteContent)
}
'''


def run_checks_text(text):
    """arms 1-2 only — kept for the two original paste fixtures."""
    return check_paste(text)


# ── deep-audit-1032 ISSUE-06 fixtures (arms 3-5) ─────────────────────────

def _sm_text(
    paste_io=True,
    reload_io=True,
    edit_name_keyed=True,
    edit_content_keys="skillId, relativePath, skill.updatedAt, loadedDiskBase",
    initial_keys="skillId, relativePath, skill.updatedAt, loadedDiskBase",
    initial_literal=True,
    extra="",
    viewer=None,
):
    viewer = DEFAULT_VIEWER if viewer is None else viewer
    paste = (
        "            val r = withContext(Dispatchers.IO) {\n"
        "                skillRepository.importFromContent(pasteContent)\n"
        "            }"
    ) if paste_io else "            val r = skillRepository.importFromContent(pasteContent)"
    reload_ = (
        "    LaunchedEffect(Unit) {\n"
        "        withContext(Dispatchers.IO) {\n"
        "            skillRepository.reloadFromDisk()\n"
        "        }\n"
        "    }"
    ) if reload_io else (
        "    LaunchedEffect(Unit) {\n"
        "        skillRepository.reloadFromDisk()\n"
        "    }"
    )
    edit_name = (
        "    var editName by remember(skill.id, skill.name) { mutableStateOf(skill.name) }"
    ) if edit_name_keyed else "    var editName by remember { mutableStateOf(skill.name) }"
    initial = (
        '    val initialContent = remember(' + initial_keys + ') { "" }'
    ) if initial_literal else (
        '    val contentInit = remember(skillId, relativePath) { "" }'
    )
    return (
        "package com.rikkaminis.app.ui.settings\n\n"
        "fun sample(pasteContent: String) {\n"
        "    scope.launch {\n" + paste + "\n    }\n"
        + reload_ + "\n"
        + edit_name + "\n"
        + initial + "\n"
        "    var editContent by remember(" + edit_content_keys + ") { mutableStateOf(initialContent) }\n"
        "    var unrelatedDialog by remember { mutableStateOf(false) }\n"
        + extra + "}\n" + viewer
    )

# [R3-t19] arm10 fixture scene: the viewer's pure base value + a caller, so the
# gate derives which parameter carries the SKILL.md flag and which carries the
# loaded disk base (renaming either is not a gate failure).
VIEWER_CALLER = (
    "\nfun viewerCaller(relativePath: String, loadedDiskBase: String?) {\n"
    "    val isSkillMd = relativePath.equals(\"SKILL.md\", ignoreCase = true)\n"
    "    val base = skillFileViewerBase(\n"
    "        isSkillMd = isSkillMd,\n"
    "        mergedFrontmatter = { \"\" },\n"
    "        loadedDiskBase = loadedDiskBase,\n"
    "    )\n"
    "}\n"
)
VIEWER_SIG = ("\nfun skillFileViewerBase(isSkillMd: Boolean, mergedFrontmatter: () -> String, "
              "loadedDiskBase: String?): String")
VIEWER_BASE_EXPR = (VIEWER_SIG + " =\n"
                    '    if (isSkillMd) mergedFrontmatter() else loadedDiskBase ?: ""\n')
VIEWER_BASE_BLANK = (VIEWER_SIG + " =\n"
                     '    if (isSkillMd) mergedFrontmatter() else ""\n')
VIEWER_BASE_POLARITY = (VIEWER_SIG + " =\n"
                        '    if (!isSkillMd) mergedFrontmatter() else loadedDiskBase ?: ""\n')
VIEWER_BLOCK_SIG = VIEWER_SIG + " {\n"
VIEWER_BASE_BLOCK_CLEAN = (VIEWER_BLOCK_SIG
                           + "    if (isSkillMd) return mergedFrontmatter()\n"
                           + '    return loadedDiskBase ?: ""\n}\n')
VIEWER_BASE_BLOCK_BLANK = (VIEWER_BLOCK_SIG
                           + "    if (isSkillMd) return mergedFrontmatter()\n"
                           + '    return ""\n}\n')
VIEWER_BASE_BLOCK_NO_RETURN = (VIEWER_BLOCK_SIG
                               + "    if (isSkillMd) return mergedFrontmatter()\n}\n")
DEFAULT_VIEWER = VIEWER_BASE_EXPR + VIEWER_CALLER


def _mcp_text(reload_io=True, oauth_io=True, seed_aligned=True, extra="",
              redirect_uri='oauthRedirectUri.trim().ifBlank { null }',
              with_redirect_arg=True):
    body = (
        "        withContext(Dispatchers.IO) {\n"
        "            mcpRepository.reloadFromDisk()\n"
        "        }\n"
    ) if reload_io else "        mcpRepository.reloadFromDisk()\n"
    oauth = (
        "    scope.launch {\n"
        "        withContext(Dispatchers.IO) {\n"
        "            MCPOAuthStore.setClientSecret(context, id, \"\")\n"
        "        }\n"
        "    }\n"
    ) if oauth_io else "    MCPOAuthStore.setClientSecret(context, id, \"\")\n"
    effect_key = "editServer" if seed_aligned else "editServer?.id"
    # [R3-t19] arm11 scene: the draft state + the assembly step that decides
    # whether the typed redirect URI ever leaves the form.
    redirect = ("            redirectUri = " + redirect_uri + ",\n") \
        if with_redirect_arg else ""
    oauth_cfg = (
        "    var oauthRedirectUri by remember(editServer) "
        "{ mutableStateOf(editServer?.oauth?.redirectUri ?: \"\") }\n"
        "    fun currentOAuthConfig(): MCPOAuthConfig? {\n"
        "        val cfg = MCPOAuthConfig(\n"
        "            clientId = oauthClientId,\n"
        + redirect +
        "        )\n"
        "        return cfg.takeIf { it.isConfigured }\n"
        "    }\n"
    )
    return (
        "package com.rikkaminis.app.ui.settings\n\n"
        "fun sample() {\n    LaunchedEffect(Unit) {\n" + body + "    }\n"
        + oauth
        + "    var oauthClientSecret by remember(editServer) { mutableStateOf(\"\") }\n"
        + "    LaunchedEffect(" + effect_key + ") {\n"
        + "        val s = withContext(Dispatchers.IO) {\n"
        + "            MCPOAuthStore.clientSecret(context, id)\n"
        + "        }\n"
        + "        oauthClientSecret = s ?: \"\"\n"
        + "    }\n"
        + oauth_cfg
        + extra
        + "}\n"
    )


REPO_BARE = '''package com.rikkaminis.app.data.repository

private fun loadAll() {
    db.rawQuery("SELECT * FROM skills", null)
    filesDir.listFiles()
}

fun reloadFromDisk() {
    loadAll()
}
'''

REPO_WRAPS = '''package com.rikkaminis.app.data.repository

private fun loadAll() {
    withContext(Dispatchers.IO) {
        db.rawQuery("SELECT * FROM skills", null)
    }
}

fun reloadFromDisk() {
    loadAll()
}
'''

MCP_REPO_BARE = '''package com.rikkaminis.app.data.repository

fun load() {
    val parsed = readServersFile()
    _servers.value = parsed
}

fun reloadFromDisk() = load()
'''

MCP_REPO_WRAPS = '''package com.rikkaminis.app.data.repository

fun load() {
    withContext(Dispatchers.IO) {
        val parsed = readServersFile()
    }
}

fun reloadFromDisk() = load()
'''

# ── deep-audit-1040 B-04 fixtures ────────────────────────────────────────
# The chat-side sheets write their per-session overrides through a SQLite
# `INSERT OR REPLACE` / `DELETE` per row, and seed from one `rawQuery` per row.
OAUTH_STORE_FIXTURE = '''package com.rikkaminis.app.mcp.oauth

private fun prefs(context: Context): SharedPreferences =
    EncryptedSharedPreferences.create(context, FILE)

fun clientSecret(context: Context, server: String): String? =
    prefs(context).getString(secretKey(server), null)

fun setClientSecret(context: Context, server: String, secret: String?) {
    prefs(context).edit().putString(secretKey(server), secret).apply()
}

private fun tokens(context: Context, server: String): String? =
    prefs(context).getString(tokensKey(server), null)

// [dv27-A3] A MULTI-LINE expression body: the value starts on the NEXT line.
// The old parser kept only the remainder of the first line ("="), so this
// function looked marker-free and was classified pure — which is how a bare
// `isAuthorized` on Main escaped the off-main arm.
fun isAuthorized(context: Context, server: String): Boolean =
    tokens(context, server)?.isNotEmpty() == true

// A genuinely PURE single-line expression body: it must stay legal on Main.
fun validateServerName(server: String): Boolean =
    server.isNotBlank() && !server.contains('/')
'''


def _sheet_skills_text(seed_io=True, write_io=True):
    seed = (
        "        val seeds = withContext(Dispatchers.IO) {\n"
        "            ids.associateWith { skillRepository.isEnabledForSession(it, sessionId) }\n"
        "        }\n"
    ) if seed_io else (
        "        val seeds = ids.associateWith {\n"
        "            skillRepository.isEnabledForSession(it, sessionId)\n"
        "        }\n"
    )
    write = (
        "    scope.launch {\n"
        "        withContext(Dispatchers.IO) {\n"
        "            skillRepository.setSessionOverride(sessionId, \"a\", true)\n"
        "        }\n"
        "    }\n"
    ) if write_io else (
        "    skillRepository.setSessionOverride(sessionId, \"a\", true)\n"
    )
    return (
        "package com.rikkaminis.app.ui.chat\n\n"
        "fun sample(skills: List<String>, sessionId: String, scope: CoroutineScope) {\n"
        "    val overrides = mutableStateMapOf<String, Boolean>()\n"
        "    LaunchedEffect(skills, sessionId) {\n"
        "        val ids = skills.map { it }\n"
        + seed
        + "        mergeSessionOverrides(\n"
        + "            liveIds = ids,\n"
        + "            overrides = overrides,\n"
        + "            lookup = { seeds[it] ?: false },\n"
        + "        )\n"
        "    }\n"
        + write
        + "}\n"
    )


def _sheet_mcps_text(seed_io=True, write_io=True):
    seed = (
        "        val seeds = withContext(Dispatchers.IO) {\n"
        "            missing.associateWith { mcpRepository.isEnabledForSession(it, sessionId) }\n"
        "        }\n"
    ) if seed_io else (
        "        val seeds = missing.associateWith {\n"
        "            mcpRepository.isEnabledForSession(it, sessionId)\n"
        "        }\n"
    )
    write = (
        "    scope.launch {\n"
        "        withContext(Dispatchers.IO) {\n"
        "            mcpRepository.setSessionOverride(sessionId, \"a\", true)\n"
        "        }\n"
        "    }\n"
    ) if write_io else (
        "    mcpRepository.setSessionOverride(sessionId, \"a\", true)\n"
    )
    return (
        "package com.rikkaminis.app.ui.chat\n\n"
        "fun sample(servers: List<String>, sessionId: String, scope: CoroutineScope) {\n"
        "    val overrides = mutableStateMapOf<String, Boolean>()\n"
        "    LaunchedEffect(servers, sessionId) {\n"
        "        val missing = servers.filter { it !in overrides }\n"
        + seed
        + "        for (id in missing) overrides[id] = seeds[id] ?: false\n"
        "    }\n"
        + write
        + "}\n"
    )


# Two declarations of the same entry points, differing only in where the disk
# access sits: `MCP_REPO_FIX` keeps it in the method bodies (so a bare call site
# is a real Main-thread I/O), `MCP_REPO_WRAPS_WRITE` wraps it internally (the
# equivalent fix arms 3/4 accept).
MCP_REPO_FIX = '''package com.rikkaminis.app.data.repository

fun exportServerJSON(server: MCPServerConfig): String {
    val o = JSONObject().put(server.id, entryToJson(server))
    return o.toString(2)
}

private fun entryToJson(s: MCPServerConfig): JSONObject {
    val o = JSONObject()
    o.put("command", s.command)
    return o
}

fun previewImport(text: String): Int = parseRoot(text).size

private fun parseRoot(text: String): List<String> {
    val root = JSONObject(text.trim())
    return root.keys().asSequence().toList()
}

fun setEnabled(id: String, enabled: Boolean) {
    save()
}

fun setSessionOverride(sessionId: String, mcpId: String, enabled: Boolean) {
    db.execSQL("INSERT OR REPLACE INTO mcp_session_overrides VALUES (?, ?, ?)", null)
}

fun isEnabledForSession(mcpId: String, sessionId: String): Boolean {
    val cursor = db.rawQuery("SELECT is_enabled FROM mcp_session_overrides", null)
    cursor.close()
    return false
}

private fun save() {
    mcpDir.mkdirs()
}
'''

MCP_REPO_WRAPS_WRITE = '''package com.rikkaminis.app.data.repository

fun setEnabled(id: String, enabled: Boolean) {
    withContext(Dispatchers.IO) {
        save()
    }
}

private fun save() {
    mcpDir.mkdirs()
}
'''

SKILL_REPO_FIX = '''package com.rikkaminis.app.data.repository

fun isEnabledForSession(skillId: String, sessionId: String): Boolean {
    val cursor = db.rawQuery("SELECT is_enabled FROM session_skill_overrides", null)
    cursor.close()
    return false
}

fun setSessionOverride(sessionId: String, skillId: String, enabled: Boolean) {
    db.execSQL("INSERT OR REPLACE INTO session_skill_overrides VALUES (?, ?, ?)", null)
}
'''


def _files(sm=None, mcp=None, skill_repo=None, mcp_repo=None, sheet_skills=None,
           sheet_mcps=None, oauth_store=None):
    return {
        SM_REL: _sm_text() if sm is None else sm,
        MCP_REL: _mcp_text() if mcp is None else mcp,
        SKILL_REPO_REL: REPO_BARE if skill_repo is None else skill_repo,
        MCP_REPO_REL: MCP_REPO_BARE if mcp_repo is None else mcp_repo,
        SHEET_SKILLS_REL: (_sheet_skills_text() if sheet_skills is None
                           else sheet_skills),
        SHEET_MCPS_REL: _sheet_mcps_text() if sheet_mcps is None else sheet_mcps,
        MCP_OAUTH_REL: OAUTH_STORE_FIXTURE if oauth_store is None else oauth_store,
    }

# ── arm9 [deep-audit-1041 B-03] fixtures ─────────────────────────────────
# `_b03_files()` = the common fixture map plus the env-var screen/declaration.
# Kept separate from `_files()` on purpose: folding these two entries into the
# shared map would make every arm1-8 fixture arm9-red (fail-closed on a missing
# screen) and mask real regressions in those arms.
ENVVAR_REPO_FIXTURE = '''package com.rikkaminis.app.data.repository

class EnvVarRepository {
    private val _entries = MutableStateFlow<List<String>>(emptyList())

    private val encryptedPrefs: SharedPreferences by lazy {
        EncryptedPrefsFactory.safeCreate(context, "env_var_values")
    }

    private val metadataFile: File
        get() = File(context.filesDir, "env-vars.json")

    fun isValidKey(key: String): Boolean = KEY_REGEX.matches(key)

    fun isDuplicateKey(key: String): Boolean =
        _entries.value.any { it.equals(key, ignoreCase = true) }

    fun getValue(key: String): String? = encryptedPrefs.getString(key, null)

    fun add(key: String, value: String): Boolean {
        _entries.value = _entries.value + key
        encryptedPrefs.edit().putString(key, value).apply()
        saveMetadata()
        return true
    }

    fun update(id: String, key: String, value: String): Boolean {
        encryptedPrefs.edit().putString(key, value).apply()
        saveMetadata()
        return true
    }

    fun delete(id: String) {
        encryptedPrefs.edit().remove(id).apply()
        saveMetadata()
    }

    private fun saveMetadata() {
        metadataFile.writeText("[]")
    }
}
'''

# Same repository, prefs store renamed: the arm must judge the DECLARATION, so a
# rename cannot disarm it.
ENVVAR_REPO_RENAMED = ENVVAR_REPO_FIXTURE.replace("encryptedPrefs", "secureStore")

# Same repository whose `getValue` reads a plain in-memory map. `getValue` is
# then genuinely pure and a bare call site on Main must stay legal — the
# precision twin of the arm above.
ENVVAR_REPO_PURE_READ = (
    ENVVAR_REPO_FIXTURE
    .replace(
        '''    private val encryptedPrefs: SharedPreferences by lazy {
        EncryptedPrefsFactory.safeCreate(context, "env_var_values")
    }
''',
        "    private val valueCache = mutableMapOf<String, String>()\n")
    .replace(
        "fun getValue(key: String): String? = encryptedPrefs.getString(key, null)",
        "fun getValue(key: String): String? = valueCache[key]")
    .replace("encryptedPrefs.edit().putString(key, value).apply()",
             "valueCache[key] = value")
    .replace("encryptedPrefs.edit().remove(id).apply()",
             "valueCache.remove(id)")
)

# The repository-side alternative the criterion accepts: `add` wraps its own
# work, so a bare call site resolves through `repo_method_wraps_io`.
ENVVAR_REPO_WRAPS = ENVVAR_REPO_FIXTURE.replace(
    '''    fun add(key: String, value: String): Boolean {
        _entries.value = _entries.value + key
        encryptedPrefs.edit().putString(key, value).apply()
        saveMetadata()
        return true
    }
''',
    '''    fun add(key: String, value: String): Boolean {
        withContext(Dispatchers.IO) {
            saveMetadata()
        }
        _entries.value = _entries.value + key
        return true
    }
''')

ENVVAR_SCREEN_TEMPLATE = '''package com.rikkaminis.app.ui.settings

@Composable
fun EnvironmentVariablesScreen(envVarRepository: EnvVarRepository) {
    val scope = rememberCoroutineScope()
    val isValid = envVarRepository.isValidKey(normalizedKey)
    val isDuplicate = envVarRepository.isDuplicateKey(normalizedKey, excludeId = null)
%(body)s
}
'''

CLEAN_BODY = '''    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { envVarRepository.delete("a") }
    }
    val display by produceState("", 1) {
        value = withContext(Dispatchers.IO) { envVarRepository.getValue("k") ?: "" }
    }
    scope.launch {
        withContext(Dispatchers.IO) { envVarRepository.add("k", "v") }
    }
'''

BARE_WRITE_BODY = CLEAN_BODY.replace(
    "        withContext(Dispatchers.IO) { envVarRepository.add(\"k\", \"v\") }\n",
    "        envVarRepository.add(\"k\", \"v\")\n")

BARE_READ_BODY = CLEAN_BODY.replace(
    "        value = withContext(Dispatchers.IO) { envVarRepository.getValue(\"k\") ?: \"\" }\n",
    "        value = envVarRepository.getValue(\"k\") ?: \"\"\n")

PURE_ONLY_BODY = ""

# No repository CALL at all (only the StateFlow): the anchor lost its subject.
NO_CALLS_SCREEN = '''package com.rikkaminis.app.ui.settings

@Composable
fun EnvironmentVariablesScreen(envVarRepository: EnvVarRepository) {
    val entries by envVarRepository.entries.collectAsState()
    Text(entries.size.toString())
}
'''

def _b03_files(body=CLEAN_BODY, repo=None, screen=None):
    files = _files()
    files[ENVVAR_SCREEN_REL] = (
        ENVVAR_SCREEN_TEMPLATE % {"body": body} if screen is None else screen)
    files[ENVVAR_REPO_REL] = ENVVAR_REPO_FIXTURE if repo is None else repo
    return files


B03_FIXTURES = [
    # (label, files, expect_red, needle)
    ("b03 clean: every env-var IO call off Main",
     _b03_files(), False, None),
    ("b03 bare write (add) on Main",
     _b03_files(body=BARE_WRITE_BODY), True, "`envVarRepository.add(` at line"),
    ("b03 bare read (getValue) inside produceState",
     _b03_files(body=BARE_READ_BODY), True, "`envVarRepository.getValue(` at line"),
    ("b03 precision: pure key checks alone stay legal on Main",
     _b03_files(body=PURE_ONLY_BODY), False, None),
    ("b03 precision: the prefs field is judged from its declaration",
     _b03_files(body=BARE_READ_BODY, repo=ENVVAR_REPO_RENAMED),
     True, "`envVarRepository.getValue(` at line"),
    ("b03 precision: a pure getValue over a plain map stays legal",
     _b03_files(body=BARE_READ_BODY, repo=ENVVAR_REPO_PURE_READ), False, None),
    ("b03 alt branch: the repository method wraps internally",
     _b03_files(body=BARE_WRITE_BODY, repo=ENVVAR_REPO_WRAPS), False, None),
    ("b03 fail-closed: no declaration to judge the callee",
     _b03_files(body=CLEAN_BODY + '    scope.launch { envVarRepository.wipeAll() }\n'),
     True, "no `fun wipeAll(` declaration"),
    ("b03 fail-closed: screen without repository calls",
     _b03_files(screen=NO_CALLS_SCREEN), True, "lost its subject"),
    ("b03 fail-closed: declaration file is empty",
     _b03_files(body=CLEAN_BODY, repo=""),
     True, "no `fun isValidKey(` declaration"),
]

# ── arm6 [deep-audit-1039 B-05] fixtures ─────────────────────────────────
# Extra call sites appended to the task body (inside the function, before the
# closing brace), so arms 1-5 stay in their clean shape while arm6 sees them.
REPO_WRAPS_LIST = '''package com.rikkaminis.app.data.repository

fun listSkillFiles(skillId: String): List<String> {
    withContext(Dispatchers.IO) {
        File(skillsDir, skillId).walkTopDown().toList()
    }
    return emptyList()
}
'''

EXTRA_WRAPPED = (
    "    scope.launch {\n"
    "        withContext(Dispatchers.IO) { skillRepository.delete(\"a\") }\n"
    "    }\n"
    "    scope.launch {\n"
    "        withContext(Dispatchers.IO) { skillRepository.listSkillFiles(\"a\") }\n"
    "    }\n"
    "    val enabled = skillRepository.skills\n"
)
EXTRA_BARE_DELETE = "    skillRepository.delete(\"a\")\n"
EXTRA_BARE_SETENABLED = "    skillRepository.setEnabled(\"a\", true)\n"
EXTRA_BARE_LIST = "    val files = skillRepository.listSkillFiles(\"a\")\n"
EXTRA_LAUNCH_DISPATCHER = (
    "    scope.launch(Dispatchers.IO) { skillRepository.delete(\"a\") }\n"
)
EXTRA_FLOW_ONLY = "    val enabled = skillRepository.skills\n"

ARM6_FIXTURES = [
    # (label, files, expect_red, needle)
    ("arm6 clean: extra call sites wrapped",
     _files(sm=_sm_text(extra=EXTRA_WRAPPED)), False, None),
    ("arm6 bare delete on Main",
     _files(sm=_sm_text(extra=EXTRA_BARE_DELETE)), True,
     "bare (Main-thread) `skillRepository.delete(` at line"),
    ("arm6 bare setEnabled on Main",
     _files(sm=_sm_text(extra=EXTRA_BARE_SETENABLED)), True,
     "bare (Main-thread) `skillRepository.setEnabled(` at line"),
    ("arm6 bare call whose repo method wraps internally",
     _files(sm=_sm_text(extra=EXTRA_BARE_LIST), skill_repo=REPO_WRAPS_LIST), False, None),
    ("arm6 precision: launch(Dispatchers.IO) is off Main too",
     _files(sm=_sm_text(extra=EXTRA_LAUNCH_DISPATCHER)), False, None),
    ("arm6 precision: the StateFlow property is not a call site",
     _files(sm=_sm_text(extra=EXTRA_FLOW_ONLY)), False, None),
]

def _arm6_fail_closed() -> list:
    """A screen with no repository CALL at all must be red (fail-closed)."""
    only_flow = ("package com.rikkaminis.app.ui.settings\n\n"
                 "fun sample() {\n" + EXTRA_FLOW_ONLY + "}\n")
    return check_all_repo_calls({SM_REL: only_flow})


ISSUE06_FIXTURES = [
    # (label, files, expect_red, needle)
    ("clean: both screens wrapped (branch A)",
     _files(), False, None),
    ("precision: paste IO block does not cover reload",
     _files(sm=_sm_text(paste_io=True, reload_io=False)), True, "bare (Main-thread)"),
    ("MCP screen bare on Main",
     _files(mcp=_mcp_text(reload_io=False)), True, "MCPIntegrationsScreen"),
    ("alt branch: repo method wraps (call site bare)",
     _files(sm=_sm_text(reload_io=False), skill_repo=REPO_WRAPS), False, None),
    ("alt branch: MCP repo wraps via 1 delegation hop",
     _files(mcp=_mcp_text(reload_io=False), mcp_repo=MCP_REPO_WRAPS), False, None),
    ("commented-out bare reload must not count",
     _files(sm=_sm_text(extra="    // skillRepository.reloadFromDisk()\n")), False, None),
    ("paste arm not satisfied by the reload IO block",
     _files(sm=_sm_text(paste_io=False, reload_io=True)), True, "paste import not on IO"),
    ("editName un-keyed (dialog snapshot)",
     _files(sm=_sm_text(edit_name_keyed=False)), True, "`editName` remember has no key"),
    ("editName keyed with Unit (no usable key)",
     _files(sm=_sm_text(edit_name_keyed=False).replace(
         "    var editName by remember {",
         "    var editName by remember(Unit) {")), True, "empty/Unit"),
    ("editContent keys lack updatedAt",
     _files(sm=_sm_text(edit_content_keys="skillId, relativePath")),
     True, "lack `updatedAt`"),
    ("editContent drops loadedDiskBase (B-04 pre-fix shape)",
     _files(sm=_sm_text(edit_content_keys="skillId, relativePath, skill.updatedAt")),
     True, "editor draft freezes"),
    ("precision: extra editor-only key is not a defect",
     _files(sm=_sm_text(edit_content_keys="skillId, relativePath, skill.updatedAt, loadedDiskBase, draftExtra")),
     False, None),
    ("precision: alignment follows the key, not its position",
     _files(sm=_sm_text(initial_keys="loadedDiskBase, skill.updatedAt, skillId, relativePath")),
     False, None),
    ("initialContent shape anchor removed",
     _files(sm=_sm_text(initial_literal=False)), True, "shape anchor"),
]


# ── arm7/arm8 [deep-audit-1040 B-04] fixtures ────────────────────────────
B04_FIXTURES = [
    # (label, files, expect_red, needle)
    ("b04 clean: every disk-IO call off Main",
     _files(), False, None),
    ("b04 bare mcpRepository.setEnabled on Main",
     _files(mcp=_mcp_text(extra='    mcpRepository.setEnabled("a", true)\n'),
            mcp_repo=MCP_REPO_FIX),
     True, "`mcpRepository.setEnabled(` at line"),
    ("b04 bare MCPOAuthStore.setClientSecret on Main",
     _files(mcp=_mcp_text(extra='    MCPOAuthStore.setClientSecret(context, "a", "")\n')),
     True, "`MCPOAuthStore.setClientSecret(` at line"),
    ("b04 precision: pure exportServerJSON stays legal on Main",
     _files(mcp=_mcp_text(extra="    val j = mcpRepository.exportServerJSON(server)\n"),
            mcp_repo=MCP_REPO_FIX),
     False, None),
    ("b04 precision: pure previewImport stays legal on Main",
     _files(mcp=_mcp_text(extra="    val n = mcpRepository.previewImport(text)\n"),
            mcp_repo=MCP_REPO_FIX),
     False, None),
    ("b04 precision: a member call does not resolve to a same-named fun",
     _files(mcp=_mcp_text(extra="    val n = mcpRepository.previewImport(text)\n"),
            mcp_repo=MCP_REPO_FIX + "\nprivate fun note() { out.add(1) }\n"),
     False, None),
    ("b04 alt branch: the repo method wraps internally",
     _files(mcp=_mcp_text(extra='    mcpRepository.setEnabled("a", true)\n'),
            mcp_repo=MCP_REPO_WRAPS_WRITE),
     False, None),
    ("b04 fail-closed: no declaration to judge",
     _files(mcp=_mcp_text(extra="    mcpRepository.wipeEverything()\n"),
            mcp_repo=MCP_REPO_FIX),
     True, "no `fun wipeEverything(` declaration"),
    ("b04 fail-closed: screen without repository calls",
     _files(mcp="package x\n\nfun a() {}\n"),
     True, "lost its subject"),
    ("b04 sheet bare write on Main (skills)",
     _files(sheet_skills=_sheet_skills_text(write_io=False), skill_repo=SKILL_REPO_FIX),
     True, "SessionSkillsSheet"),
    ("b04 sheet bare seed read on Main (skills)",
     _files(sheet_skills=_sheet_skills_text(seed_io=False), skill_repo=SKILL_REPO_FIX),
     True, "rawQuery("),
    ("b04 sheet bare write on Main (mcps)",
     _files(sheet_mcps=_sheet_mcps_text(write_io=False), mcp_repo=MCP_REPO_FIX),
     True, "SessionMcpsSheet"),
    ("b04 sheet bare seed read on Main (mcps)",
     _files(sheet_mcps=_sheet_mcps_text(seed_io=False), mcp_repo=MCP_REPO_FIX),
     True, "SessionMcpsSheet"),
    ("b04 arm8: seed effect keyed narrower than the draft",
     _files(mcp=_mcp_text(seed_aligned=False)),
     True, "seeding it is keyed"),
    ("b04 arm8: un-keyed draft never re-seeds",
     _files(mcp=_mcp_text().replace(
         "var oauthClientSecret by remember(editServer) {",
         "var oauthClientSecret by remember {")),
     True, "carries no remember key"),
    # ── [dv27-A3] A-06 arms: the expression-body parser must see the whole
    # expression, not just the remainder of the first line. The old parser
    # reduced `fun isAuthorized(...): Boolean =` + a next-line value to "=",
    # so the function looked marker-free (pure) and a bare Main-thread call
    # escaped the off-main arm.
    ("dv27-A06 bare MCPOAuthStore.isAuthorized on Main",
     _files(mcp=_mcp_text(extra='    MCPOAuthStore.isAuthorized(context, id)\n')),
     True, "runs on Main while its callee performs"),
    ("dv27-A06 bare MCPOAuthStore.clientSecret on Main",
     _files(mcp=_mcp_text(extra='    val s2 = MCPOAuthStore.clientSecret(context, id)\n')),
     True, "runs on Main while its callee performs"),
    ("dv27-A06 precision: a pure expression body stays legal on Main",
     _files(mcp=_mcp_text(extra='    val ok = MCPOAuthStore.validateServerName(id)\n')),
     False, None),
]

# ── [dv27-A3] A-06 parser arms ───────────────────────────────────────────
# The expression-body extractor must take the WHOLE expression (not just the
# remainder of the first line) and must stop at the next declaration. These
# are python-level arms: the expected strings are literals, never derived
# from the code under test.
EXPR_PARSER_ARMS = [
    ("single-line expression body",
     "fun a(): Int = foo(1)",
     "foo(1)",
     None),
    ("multi-line expression body",
     "fun b(): Int =\n    foo(2)",
     "foo(2)",
     None),
    ("chained continuation line",
     "fun c(): Int =\n    foo(3)\n        .bar()",
     ".bar()",
     None),
    ("expression body does not swallow the next function",
     "fun d(): Int = foo(4)\nfun e() {\n    sideEffect()\n}",
     "foo(4)",
     "fun e"),
    ("block-bodied function is a block",
     "fun f() {\n    sideEffect()\n}",
     "sideEffect()",
     None),
]


def _expr_parser_arms():
    """(ok, detail) for every A-06 parser arm."""
    out = []
    for label, src, must_contain, must_not_contain in EXPR_PARSER_ARMS:
        nc = strip_comments(src)
        masked = mask_strings(nc)
        m = re.search(r"\bfun\s+([A-Za-z_]\w*)\s*\(", nc)
        name = m.group(1)
        body, kind = _fun_body_nc(nc, masked, name)
        if body is None:
            out.append((label, False, "body not recognised"))
            continue
        if must_contain is not None and must_contain not in body:
            out.append((label, False, "body %r lacks %r" % (body, must_contain)))
            continue
        if must_not_contain is not None and must_not_contain in body:
            out.append((label, False, "body %r wrongly contains %r"
                        % (body, must_not_contain)))
            continue
        out.append((label, True, ""))
    return out


# ── [wide3-08] witness arms ──────────────────────────────────────────────
# Every criterion added for wide3-08 carries its own negative arm here: the
# witness patch applied to the fixture must turn EXACTLY this arm red while the
# clean/precision twins stay green.
_SEED_EFFECT_BLOCK = (
    '    LaunchedEffect(editServer) {\n'
    '        val s = withContext(Dispatchers.IO) {\n'
    '            MCPOAuthStore.clientSecret(context, id)\n'
    '        }\n'
    '        oauthClientSecret = s ?: ""\n'
    '    }\n'
)
_DRAFT_LINE = ('    var oauthClientSecret by remember(editServer) '
               '{ mutableStateOf("") }\n')
_ARM8_DEAD = (
    '    LaunchedEffect(editServer?.id) {\n'
    '        val sid = editServer?.id\n'
    '        if (true || sid == null) {\n'
    '            oauthClientSecret = ""\n'
    '            return@LaunchedEffect\n'
    '        }\n'
    '        val s = withContext(Dispatchers.IO) {\n'
    '            MCPOAuthStore.clientSecret(context, id)\n'
    '        }\n'
    '        oauthClientSecret = s ?: ""\n'
    '    }\n'
)
_ARM8_LIVE = _ARM8_DEAD.replace('if (true || sid == null) {', 'if (sid == null) {')


def _w308_mcp(seed):
    """The shared MCP fixture with its seed effect replaced by `seed` and the
    draft key matched to the effect key."""
    text = _mcp_text()
    text = text.replace(_SEED_EFFECT_BLOCK, seed)
    text = text.replace(_DRAFT_LINE,
                        _DRAFT_LINE.replace('remember(editServer)',
                                            'remember(editServer?.id)'))
    return text


W308_FIXTURES = [
    # arm1 scope: the INNER dispatcher decides. `scope.launch` wrapping a
    # `withContext(Dispatchers.Main)` never makes the call off-main.
    ("arm1 nested Main dispatcher still runs on Main",
     lambda: _files(mcp=_mcp_text().replace(
         "withContext(Dispatchers.IO) {\n            MCPOAuthStore.setClientSecret",
         "withContext(Dispatchers.Main) {\n            MCPOAuthStore.setClientSecret")),
     True, "runs on Main"),
    # arm7 spelling: `val store = MCPOAuthStore` then `store.clientSecret(...)`.
    ("arm7 alias receiver on Main",
     lambda: _files(mcp=_mcp_text(extra=(
         '    val store = MCPOAuthStore\n'
         '    val s3 = store.clientSecret(context, id)\n'))),
     True, "runs on Main"),
    # arm8 dataflow: the reset must sit inside a LIVE `<id> == null` guard.
    ("arm8 dead reset guard wipes the draft",
     lambda: _files(mcp=_w308_mcp(_ARM8_DEAD)),
     True, "reset is not inside a live"),
    # precision twin: a live guard with the same keys stays legal.
    ("arm8 precision: live null guard stays legal",
     lambda: _files(mcp=_w308_mcp(_ARM8_LIVE)),
     False, None),
]


# ── [wide4-12] witness arms ──────────────────────────────────────────────
# Five gates-blind shapes verified by the external audit; each becomes one
# negative arm, plus clean twins that must stay legal.
_OAUTH_IO_BLOCK = (
    "        withContext(Dispatchers.IO) {\n"
    "            MCPOAuthStore.setClientSecret(context, id, \"\")\n"
    "        }\n"
)
_ARM8_INVERTED = _ARM8_DEAD.replace('if (true || sid == null) {',
                                    'if (sid != null) {')
_ARM8_CONST_CMP = _ARM8_DEAD.replace('if (true || sid == null) {',
                                     'if ((1 == 1) || sid == null) {')

W4_FIXTURES = [
    # w4-arm1 family-hole: the lambda arrives as a NAMED argument, so the `{`
    # is not preceded by `)` and the inner Main switch disappeared from the
    # stack — the call inherited the outer IO.
    ("w4-arm1 named-arg lambda still runs on Main",
     lambda: _files(mcp=_mcp_text().replace(
         _OAUTH_IO_BLOCK,
         "        withContext(context = Dispatchers.Main, block = {\n"
         "            MCPOAuthStore.setClientSecret(context, id, \"\")\n"
         "        })\n")),
     True, "runs on Main"),
    # w4-arm2 dataflow: a `var` dispatcher reassigned to Main is not IO.
    ("w4-arm2 reassigned var dispatcher is Main",
     lambda: _files(mcp=_mcp_text().replace(
         _OAUTH_IO_BLOCK,
         "        var ioCtx: kotlin.coroutines.CoroutineContext = Dispatchers.IO\n"
         "        ioCtx = Dispatchers.Main\n"
         "        withContext(ioCtx) {\n"
         "            MCPOAuthStore.setClientSecret(context, id, \"\")\n"
         "        }\n")),
     True, "runs on Main"),
    # w4-arm2 precision: `+` keeps the RIGHT pair of the same Key (Main wins).
    ("w4-arm2 context-plus-Main runs on Main",
     lambda: _files(mcp=_mcp_text().replace(
         _OAUTH_IO_BLOCK,
         "        val ioCtx = Dispatchers.IO + Dispatchers.Main\n"
         "        withContext(ioCtx) {\n"
         "            MCPOAuthStore.setClientSecret(context, id, \"\")\n"
         "        }\n")),
     True, "runs on Main"),
    # w4-arm2 precision twin: an immutable local binding is still legal.
    ("w4-arm2 precision: val ioCtx = Dispatchers.IO stays legal",
     lambda: _files(mcp=_mcp_text().replace(
         _OAUTH_IO_BLOCK,
         "        val ioCtx = Dispatchers.IO\n"
         "        withContext(ioCtx) {\n"
         "            MCPOAuthStore.setClientSecret(context, id, \"\")\n"
         "        }\n")),
     False, None),
    # w4-arm3 family-hole: the receiver arrives through a scope function.
    ("w4-arm3 scope receiver .let on Main",
     lambda: _files(mcp=_mcp_text(extra=(
         '    val s3 = MCPOAuthStore.let { it.clientSecret(context, id) }\n'))),
     True, "runs on Main"),
    # w4-arm3 precision twin: the same scope block inside an IO wrap is legal.
    ("w4-arm3 precision: scope receiver inside IO stays legal",
     lambda: _files(mcp=_mcp_text(extra=(
         '    val s3 = withContext(Dispatchers.IO) {\n'
         '        MCPOAuthStore.let { it.clientSecret(context, id) }\n'
         '    }\n'))),
     False, None),
    # w4-arm4 family-hole: the null test's DIRECTION is the decision.
    ("w4-arm4 inverted null guard wipes the draft",
     lambda: _files(mcp=_w308_mcp(_ARM8_INVERTED)),
     True, "reset is not inside a live"),
    # w4-arm4 family-hole: `(1 == 1)` is a literal decision in disguise.
    ("w4-arm4 constant-comparison guard is dead",
     lambda: _files(mcp=_w308_mcp(_ARM8_CONST_CMP)),
     True, "reset is not inside a live"),
    # w4-arm5 parser-death: a char literal `'"'` used to desync the comment
    # scanner, swallowing every line up to the planted `*/`.
    ("w4-arm5 char-literal desync does not hide the call",
     lambda: _files(mcp=_mcp_text(extra=(
         '    val _o = \'"\'; val _c = "/*"\n'
         '    val s3 = MCPOAuthStore.clientSecret(context, id)\n'
         '    val _x = "*/" + \'"\'\n'))),
     True, "runs on Main"),
    # w4-arm6 parser fail-closed: an unlexable file is a violation, not a
    # silently-empty surface that reports itself clean.
    ("w4-arm6 unlexable file is a violation",
     lambda: _files(mcp=_mcp_text() + "    /* unterminated\n"),
     True, "could not be lexed"),
]


# ── [R3-t19] round-3 (dual-gate) fixtures ────────────────────────────────
# arm10 = viewer base carry, arm11 = OAuth config assembly. Anchor drift is a
# real hazard here (the fixtures are hand-written mirrors), so each lambda
# asserts its own splice before handing the text to the gate.
def _r3_viewer(block):
    """Attach the caller to a viewer-base variant (asserting the splice)."""
    assert VIEWER_SIG in block, "viewer-base fixture lost its signature"
    return _files(sm=_sm_text(viewer=block + VIEWER_CALLER))


R3_FIXTURES = [
    ("arm10 sibling path reads loadedDiskBase (expr)",
     lambda: _r3_viewer(VIEWER_BASE_EXPR), False, None),
    ("arm10 sibling path returns \"\" instead",
     lambda: _r3_viewer(VIEWER_BASE_BLANK), True, "arm10:"),
    ("arm10 polarity: !isSkillMd takes the md branch",
     lambda: _r3_viewer(VIEWER_BASE_POLARITY), True, "arm10:"),
    ("arm10 block body returns loadedDiskBase (clean control)",
     lambda: _r3_viewer(VIEWER_BASE_BLOCK_CLEAN), False, None),
    ("arm10 block body returns \"\" on the sibling path",
     lambda: _r3_viewer(VIEWER_BASE_BLOCK_BLANK), True, "arm10:"),
    ("arm10 block body has no sibling return (fail-closed)",
     lambda: _r3_viewer(VIEWER_BASE_BLOCK_NO_RETURN), True, "no `return`"),
    ("arm10 no caller passes the SKILL.md flag (fail-closed)",
     lambda: _files(sm=_sm_text(viewer=VIEWER_BASE_EXPR)), True,
     "no caller passes"),
    ("arm11 redirectUri comes from the draft (clean)",
     lambda: _files(), False, None),
    ("arm11 redirectUri = null wipes the typed value",
     lambda: _files(mcp=_mcp_text(redirect_uri="null")), True, "arm11:"),
    ("arm11 redirectUri argument dropped entirely",
     lambda: _files(mcp=_mcp_text(with_redirect_arg=False)), True, "arm11:"),
    ("arm11 redirectUri via the draft chain stays legal",
     lambda: _files(mcp=_mcp_text(
         redirect_uri="oauthRedirectUri.takeIf { it.isNotBlank() }")),
     False, None),
]


def selftest():
    ok = True
    f = run_checks_text(GOOD_SM)
    print("selftest clean tree: %s" % ("PASS" if not f else "FAIL %s" % f))
    ok &= not f

    for label, text in [
        ("bare Main-thread call", BAD_MAIN_THREAD),
        ("second call site outside IO", BAD_DOUBLE_SITE),
    ]:
        f = run_checks_text(text)
        red = len(f) > 0
        print("selftest dirty %-30s: %s %s" % (label, "RED" if red else "NOT-RED", f[:1]))
        ok &= red

    for label, files, expect_red, needle in ISSUE06_FIXTURES:
        f = run_checks_map(files)
        red = len(f) > 0
        good = (red == expect_red)
        if good and expect_red and needle:
            good = any(needle in x for x in f)
        print("selftest 1032-06 %-46s: %s%s" % (
            label, "RED" if red else "CLEAN",
            "" if good else "  UNEXPECTED %s" % f[:2]))
        ok &= good

    for label, files, expect_red, needle in ARM6_FIXTURES:
        f = run_checks_map(files)
        red = len(f) > 0
        good = (red == expect_red)
        if good and expect_red and needle:
            good = any(needle in x for x in f)
        print("selftest 1039-B05 %-46s: %s%s" % (
            label, "RED" if red else "CLEAN",
            "" if good else "  UNEXPECTED %s" % f[:2]))
        ok &= good

    f = _arm6_fail_closed()
    red = len(f) > 0
    good = red and "no `skillRepository.<method>(` call site found" in f[0]
    print("selftest 1039-B05 %-46s: %s%s" % (
        "fail-closed when no repository call exists", "RED" if red else "CLEAN",
        "" if good else "  UNEXPECTED %s" % f[:2]))
    ok &= good

    for label, files, expect_red, needle in B04_FIXTURES:
        f = run_checks_map(files)
        red = len(f) > 0
        good = (red == expect_red)
        if good and expect_red and needle:
            good = any(needle in x for x in f)
        print("selftest 1040-B04 %-46s: %s%s" % (
            label, "RED" if red else "CLEAN",
            "" if good else "  UNEXPECTED %s" % f[:2]))
        ok &= good

    for label, files, expect_red, needle in B03_FIXTURES:
        f = run_checks_map(files)
        red = len(f) > 0
        good = (red == expect_red)
        if good and expect_red and needle:
            good = any(needle in x for x in f)
        print("selftest 1041-B03 %-46s: %s%s" % (
            label, "RED" if red else "CLEAN",
            "" if good else "  UNEXPECTED %s" % f[:2]))
        ok &= good


    for label, make, expect_red, needle in W308_FIXTURES:
        f = run_checks_map(make())
        red = len(f) > 0
        good = (red == expect_red)
        if good and expect_red and needle:
            good = any(needle in x for x in f)
        print("selftest wide3-08 %-45s: %s%s" % (
            label, "RED" if red else "CLEAN",
            "" if good else "  UNEXPECTED %s" % f[:2]))
        ok &= good

    for label, make, expect_red, needle in W4_FIXTURES:
        f = run_checks_map(make())
        red = len(f) > 0
        good = (red == expect_red)
        if good and expect_red and needle:
            good = any(needle in x for x in f)
        print("selftest wide4-12 %-45s: %s%s" % (
            label, "RED" if red else "CLEAN",
            "" if good else "  UNEXPECTED %s" % f[:2]))
        ok &= good

    # [R3-t19] round-3 dual-gate arms: the seam criteria follow values, so each
    # dirty fixture must go red ON ITS OWN needle (a fixture that trips a
    # neighbouring arm is a fake witness).
    for label, make, expect_red, needle in R3_FIXTURES:
        f = run_checks_map(make())
        red = len(f) > 0
        good = (red == expect_red)
        if good and expect_red and needle:
            good = any(needle in x for x in f)
        print("selftest r3-t19 %-46s: %s%s" % (
            label, "RED" if red else "CLEAN",
            "" if good else "  UNEXPECTED %s" % f[:2]))
        ok &= good

    # [dv27-A3] A-06 parser arms: the expression-body extractor must take the
    # whole expression and must stop at the next declaration.
    for label, good, detail in _expr_parser_arms():
        print("selftest dv27-A06 %-46s: %s%s" % (
            label, "PASS" if good else "FAIL",
            "" if good else "  %s" % detail))
        ok &= good

    print(SELFTEST_MARK if ok else "SKILL_IMPORT_OFFMAIN_SELFTEST=FAIL")
    return 0 if ok else 1


def main():
    # Argv conventions in this tree: test_scan.py passes `[script, ROOT]` (real
    # tree) or `[script, --selftest]`; gate_run.sh passes
    # `[script, --selftest, ROOT]`. Scan for the flag instead of indexing argv
    # (indexing silently treated "--selftest" as the repo root → red gate).
    args = [a for a in sys.argv[1:] if a]
    if "--selftest" in args:
        sys.exit(selftest())
    repo = next((a for a in args if not a.startswith("-")), ".")
    failures = run_checks(repo)
    if failures:
        # Each finding names its own file — the check now covers two screens,
        # so a single fixed header path would point at the wrong file.
        print("FAIL skill_import_offmain_check: %d finding(s)" % len(failures))
        for f in failures:
            print("  - %s" % f)
        sys.exit(1)
    print("OK %s (17 arms: paste x2, reload-off-main x2, all-repo-calls-off-main x1, "
          "editor-keys x3, editor-key-alignment x1, B-04 io-call-off-main x1 "
          "(MCP screen + both sheets), B-04 seed-effect-keys x1, "
          "B-03 io-call-off-main x1 (env-var screen), R3 viewer-base-carry x1, "
          "R3 oauth-config-assembly x1, fixture gates)"
          % SM_REL)
    sys.exit(0)


if __name__ == "__main__":
    main()
