#!/usr/bin/env python3
"""CI scan gate — system-notice coalesce window has ONE guarded home
(deep-audit-1016c FIX-G / ISSUE-10(A), P2).

The bug this pins: the pending system-notice window (iconKind / firstId /
payload / blocks) used to live in four loose `pendingSysInfo*` fields on
ChatViewModel. Two different threads reached them — Main (`checkContextBeforeSend`,
`/compact`, `revertCompact`, `setProviderName`) and the agent loop's IO coroutine
(`ChatContextWindow.trimContextHistoryWindow` ← `AgentLoopEngine` :658 / :864) —
with no mutual exclusion. Interleaved appends and flushes lost notices, emitted a
ChatMessage whose id belonged to one window while its toolBlocks belonged to
another, and could tear the backing ArrayList on the UI thread.

Nothing in the Kotlin compiler or in a single-threaded unit test can see this:
the shape only breaks when two threads meet. So the gate asserts the *shape of
the convergence*, not the presence of a comment:

  A. SINGLE HOME — any production declaration named `*sysinfo*` must live inside
     the SysInfoCoalescer class body. The only exception is a scheduling handle
     typed `Job?` (the timer), which is safe unlocked because every cancel
     precedes the cancelling thread's own re-assign. A `MutableList`-flavoured
     sysinfo declaration is a violation even as a `val`.
  B. GUARDED ACCESS — inside that class, every reference to its mutable state
     (auto-discovered from its own `private var/val` declarations, so renames do
     not evade the gate) must sit inside a `synchronized(...)` region or inside a
     `*Locked` helper.
  C. PUBLISH UNDER LOCK — the publish callback must be invoked from inside the
     lock (either directly, or through a `*Locked` helper). Publishing outside it
     re-introduces the lost-update the lock exists to prevent.
  D. NO RAW HANDOUT — the drained block list must be a copy behind a read-only
     `List<T>` type; handing the live MutableList out would let a caller mutate
     window state that is supposed to be private.
  E. CONVERGENCE POINT — `appendSystemInfo` must route through the coalescer and
     must not touch `pendingSysInfo*` again.

[deep-audit-1045 B-03] Two more shapes, in the Storage page:

  F. OFF-MAIN PUBLISH — no Compose state may be assigned inside a
     `withContext(Dispatchers.IO)` / `(Dispatchers.Default)` block. Call
     arguments are not assignments, and an assignment whose VALUE IS the
     `withContext` call (`x = withContext(Dispatcher.IO) { … }`) is fine — that
     write happens on the caller's thread once the block returns.
     [deep-audit-1048 B-01] The FILE SET is now derived (any production file
     that declares `remember` state AND contains an off-main block) instead of
     being the Storage screen alone, and two more state forms are covered —
     `val x = remember { mutableStateOf(…) }` (written as `x.value = …`) next to
     the delegated `var x by remember`. The names are still derived from each
     file's own declarations, so renames do not evade the gate. Deleting one of
     the files that anchors the scope is a failure, not a silent narrowing.
     [deep-audit-1049 B-02] The BLOCK SET is derived too, not just the file set.
     Two launch-shaped regions were invisible: `launch(Dispatchers.Default) { … }`
     (explicit dispatcher wins over the receiver) and `<scope>.launch { … }` (the
     thread comes from the scope). Which scopes carry IO/Default is derived from
     their `CoroutineScope(…)` construction — today `MinisApp.applicationScope`,
     plus per-file aliases of it — never from a member list. Main-implicit scopes
     are excluded twice over, by the factory that creates them
     (`rememberCoroutineScope()`, `viewModelScope`, `lifecycleScope`,
     `MainScope()`) and by an explicit `Dispatchers.Main` argument; without that
     exclusion every `scope.launch` in every screen would be red. Nesting is
     resolved innermost-first, so a write inside `withContext(Dispatchers.Main)
     { … }` nested in an off-main launch is safe and is not reported — which is
     exactly the shape of the fix. A deliberate off-main write is escaped with a
     `// offmain-state-ok: <reason>` marker on the offending line.
  G. SINGLE INVALIDATION — the process-lifetime scan cache must have exactly one
     non-private clearing entry, and every ViewModel entry that drives a
     mutating `RootfsManager` operation (mutating = touches disk / publishes
     install state, transitively through member calls) must call it BEFORE the
     mutation. Otherwise a rootfs install / repair / reset leaves the cached
     `shellSize` in place for the life of the process.

  C-INV. [deep-audit-1048 B-05] the same cache also outlives *deletions*. Two
     entries change what the page measures without touching the rootfs —
     `FileBrowserViewModel`'s delete (a file, or a whole session directory) and
     the drawer's session delete (which reclaims `minis-sessions/<id>`, sized by
     the page) — and neither invalidated it. The criterion derives both entries
     from their behaviour (a file deletion that re-lists; a session delete) and,
     where the entry has a `deleted` verdict, requires the invalidation to sit
     on the SUCCESS side of it: dropping the cache in the failure branch costs a
     pointless rescan and would let a refactor move it out of the success path.

A probe that cannot find its subject FAILS (exit 1) instead of reporting success:
a missing class means the convergence point was deleted, not that the tree is
clean.

[batch-5] Five blind axes closed, and the hand-written scanners behind them are
gone. Every structural view now comes from the shared primitives
(`_kotlin_lexer.mask_code`, `_kotlin_ast.match_bracket / const_bool /
function_body`, `_kotlin_valueflow.path_feasible / value_sources`); a lex failure
is a violation, never a fallback to raw text. Each item below is one arm of this
wave:

  * C — the MONITOR's identity is part of "under the lock". `synchronized(Any())`
    allocates a monitor per call, so no two threads ever meet on it; the
    acceptable monitor names are derived from the class (`private val x = Any()`),
    never from a hard-coded `lock` (witness G06-A1).
  * D — "copied" and "not yet drained" are two facts. The copy must be READ
    before the live list is cleared; ordering is decided by offset inside the
    same member, so moving `blocks.clear()` above the `Window(...)` — every line
    still shaped correctly — is red (witness G06-A2).
  * E — a call is dead when an infeasible condition governs its POSITION
    (`path_feasible`), not when one of a few `false` spellings sits on the line:
    `if (1 == 2) { … }`, `when (false) { … }` and `while (false) { … }` all
    count (witness G06-A3).
  * F — the assignment is found by OFFSET, not by `^` against a stripped line.
    `run { sharing = false }` — the write left behind when a coroutine hop
    collapses — is on an otherwise non-empty line and used to be invisible
    (witness G06-A4).
  * C-INV — the invalidation must be LIVE. A substring inside dead code is not a
    drop: `if (false) invalidateStorageSnapshot()` kept the token and skipped the
    effect (witness G06-A5).

Usage:
    python3 scripts/scan/sysinfo_state_thread_check.py <repo_root>
    python3 scripts/scan/sysinfo_state_thread_check.py --selftest
"""

import bisect
import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_lexer import KotlinLexError  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool,
    function_body,
    match_bracket,
    split_top,
    strip_parens,
)
from _kotlin_valueflow import (  # noqa: E402
    code_view,
    live_positions,
    path_feasible,
    peel_discarding,
    value_sources,
)

PKG = "src/android/app/src/main/java/com/rikkaminis/app"
CHAT = PKG + "/ui/chat"
CLASS_NAME = "SysInfoCoalescer"
SELFTEST_MARKER = "SYSINFO-STATE SELFTEST=PASS"


class GateParseError(RuntimeError):
    """Structural view failed — fail CLOSED instead of scanning raw text."""


def view(text):
    """Structural view: comments and string/char-literal contents blanked,
    length unchanged, so every offset maps back onto the source line.

    [batch-5] This replaced the gate's own blanker. The shared lexer is the one
    implementation in this wave: it also knows char literals, backtick
    identifiers and `${}` templates, so a `'{'` inside a literal can no longer
    shift brace counting, and a `"}"` inside a template cannot close a block
    early. A lex failure is a violation, never a fallback to raw text.
    """
    try:
        return code_view(text)
    except KotlinLexError as exc:
        raise GateParseError(str(exc))


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except OSError:
        return None


def find_class_span(text, name):
    """(start_line, end_line) 0-based inclusive for `class <name>` (brace balanced)."""
    code = view(text)
    m = re.search(r"^\s*(?:internal |private |public )?class\s+%s\b" % re.escape(name),
                  code, re.M)
    if not m:
        return None
    brace = code.find("{", m.end())
    if brace < 0:
        return None
    close = match_bracket(code, brace)
    if close < 0:
        return None
    return code[: m.start()].count("\n"), code[:close].count("\n")


FUN_DECL = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*\(")


def member_table(code):
    """{name: (body_start, body_end, kind)} for every `fun name(` in [code].

    [batch-5] Body extents come from the shared `_kotlin_ast.function_body`; the
    hand-written brace counter it replaces could hand back the NEXT member's
    braces (default-argument lambda / expression body), which is how a read-only
    member was once classified as mutating.
    """
    out = {}
    for m in FUN_DECL.finditer(code):
        name = m.group(1)
        got = function_body(code[m.start():], name)
        if not got:
            continue
        start, end, kind = got
        out.setdefault(name, (m.start() + start, m.start() + end, kind))
    return out


def member_bodies(code):
    """{name: ((first_line, last_line), body_text)} — 1-based inclusive lines."""
    out = {}
    for name, (start, end, _kind) in member_table(code).items():
        span = (code.count("\n", 0, start) + 1, code.count("\n", 0, end) + 1)
        out[name] = (span, code[start:end])
    return out



def iter_prod_kt(repo_root):
    base = os.path.join(repo_root, "src/android/app/src/main/java")
    for dirpath, _dirs, files in os.walk(base):
        if os.sep + "test" in dirpath:
            continue
        for fn in files:
            if fn.endswith(".kt"):
                yield os.path.join(dirpath, fn)


DECL = re.compile(
    r"^\s*(?:private |internal |protected |public |@Volatile )*(var|val)\s+"
    r"(\w*[sS]ys[Ii]nfo\w*)\s*(?::\s*([^=\n]+))?(=)?", re.M)


def rule_single_home(repo_root, class_file, class_span):
    """A: sysinfo state lives only inside the class (Job? handle excepted)."""
    problems = []
    for path in iter_prod_kt(repo_root):
        text = read(path)
        if text is None:
            continue
        rel = os.path.relpath(path, repo_root)
        in_class = rel == class_file
        for m in DECL.finditer(text):
            line_no = text[: m.start()].count("\n") + 1
            if in_class and class_span and class_span[0] + 1 <= line_no <= class_span[1] + 1:
                continue
            decl_line = text.split("\n")[line_no - 1].strip()
            if re.search(r":\s*Job\?", decl_line):
                continue  # scheduling handle: documented exception
            if CLASS_NAME in decl_line:
                continue  # the coalescer instance itself
            kind = m.group(1)
            why = "mutable collection" if re.search(r"MutableList|mutableListOf|ArrayList",
                                                    decl_line) else kind
            problems.append("%s:%d [A single-home] %s declaration `%s` outside %s"
                            % (rel, line_no, why, m.group(2), CLASS_NAME))
    return problems


# ── structural helpers: "under the lock" is a POSITION, not a substring ──────

def _line_starts(code):
    starts = [0]
    for i, ch in enumerate(code):
        if ch == "\n":
            starts.append(i + 1)
    return starts


def is_dead(code, pos):
    """True when a constant-false condition governs `pos` (dead code).

    [batch-5] One judgment for every arm of this gate. It used to be a token
    blacklist (`false` / `!true` / `0 == 1`), so `if (1 == 2)`, `if (x) { if
    (false) … }`, `when (false)` and `while (false)` all kept a call "present"
    while removing its effect. The condition is folded by the shared constant
    evaluator instead of matched as a spelling.
    """
    if not path_feasible(code, pos):
        return True
    for m in re.finditer(r"\bwhile\s*\(", code):
        op = m.end() - 1
        cl = match_bracket(code, op)
        if cl < 0 or pos <= cl:
            continue
        if const_bool(code[op + 1:cl]) is not False:
            continue
        k = cl + 1
        while k < len(code) and code[k] in " \t\r\n":
            k += 1
        if k < len(code) and code[k] == "{":
            end = match_bracket(code, k)
            if 0 < end and k < pos < end:
                return True
        else:
            eol = code.find("\n", k)
            if k <= pos < (eol if eol >= 0 else len(code)):
                return True
    return False


def _brace_end(code, open_idx):
    """Offset of the `}` matching the `{` at [open_idx]; -1 when unbalanced."""
    j = match_bracket(code, open_idx)
    return j if j > 0 else -1


LOCK_INIT = re.compile(
    r"^    (?:private|internal|protected) (?:val|var) (\w+)\s*(?::\s*[^=\n]+)?=\s*"
    r"(?:Any|Object|java\.lang\.Object)\s*\(\s*\)")


def class_lock_names(lines, lo, hi):
    """Class-level fields that hold a fresh monitor object — derived, not named."""
    out = set()
    for idx in range(lo, min(hi + 1, len(lines))):
        m = LOCK_INIT.match(lines[idx])
        if m:
            out.add(m.group(1))
    return out


def stable_monitors(expr, lock_names):
    """A monitor only excludes threads when it is a STABLE field.

    `synchronized(Any())` allocates a monitor per call, so two threads never
    meet on it — the bare-call shape with extra typing. Acceptable names are
    derived from the class (`private val x = Any()`), never from a hard-coded
    `lock` literal; with no such field the criterion still refuses a constructor
    call and accepts any plain field name.
    """
    e = strip_parens(expr.strip())
    if not re.fullmatch(r"[A-Za-z_]\w*", e):
        return False
    return (not lock_names) or (e in lock_names)


def live_sync_bodies(code, lock_names=()):
    """(start, end) offsets of every LIVE, NON-EMPTY `synchronized(…) { … }` body.

    [wide3/10] An empty block guards nothing; a block behind a constant-false
    condition never runs.
    [batch-5] A block whose MONITOR is a fresh object (`synchronized(Any())`)
    excludes nobody either — same family, one dimension over. Membership in this
    list, not the substring `"synchronized"`, is what makes an access locked.
    """
    out = []
    for m in re.finditer(r"\bsynchronized\s*\(", code):
        op = code.find("(", m.start())
        cl = match_bracket(code, op)
        if cl < 0:
            continue
        j = cl + 1
        while j < len(code) and code[j] in " \t\r\n":
            j += 1
        if j >= len(code) or code[j] != "{":
            continue
        k = _brace_end(code, j)
        if k < 0:
            continue
        if not code[j + 1:k].strip():
            continue
        if is_dead(code, m.start()):
            continue
        if not stable_monitors(code[op + 1:cl], lock_names):
            continue
        out.append((j + 1, k))
    return out


def in_live_sync(bodies, off):
    return any(a <= off < b for (a, b) in bodies)

def rule_guarded_access(class_text, class_span, code):
    """B + C + D on the coalescer source."""
    problems = []
    lo, hi = class_span
    lines = class_text.split("\n")
    lock_names = class_lock_names(lines, lo, hi)
    members = member_table(code)
    bodies = live_sync_bodies(code, lock_names)
    # The monitor expression itself (`synchronized(lock) {`) is a read of `lock`
    # — it is the lock, not state accessed outside it.
    monitor_spans = []
    for m in re.finditer(r"\bsynchronized\s*\(", code):
        op = code.find("(", m.start())
        monitor_spans.append((op, _paren_end(code, op)))
    starts = _line_starts(code)

    def line_of(off):
        return bisect.bisect_right(starts, off)

    # [batch-5] The monitor's IDENTITY is part of "under the lock": a
    # `synchronized(Any())` block allocates a fresh monitor per call and
    # therefore excludes nobody, so every access it wraps is unsynchronised no
    # matter how the block reads. Same family as the empty-block and
    # dead-condition arms, one dimension over.
    for m in re.finditer(r"\bsynchronized\s*\(", code):
        op = code.find("(", m.start())
        cl = _paren_end(code, op)
        expr = code[op + 1:cl].strip() if cl > op else ""
        line_no = line_of(m.start())
        if line_no < lo + 1 or line_no > hi + 1:
            continue
        if is_dead(code, m.start()) or stable_monitors(expr, lock_names):
            continue
        problems.append(
            "[C publish-under-lock] line %d locks `%s`, not the class lock — every call "
            "builds a fresh monitor, so no two threads ever exclude each other: %s"
            % (line_no, expr, lines[line_no - 1].strip()))

    locked_lines = set()
    for name, (start, end, _kind) in members.items():
        if name.endswith("Locked"):
            locked_lines.update(range(code.count("\n", 0, start) + 1,
                                      code.count("\n", 0, end) + 2))

    # mutable state = the class's own private var/val declarations
    state = []
    for idx in range(lo, hi + 1):
        m = re.match(r"^    private (?:var|val)\s+(\w+)", lines[idx])
        if m:
            state.append((m.group(1), idx + 1))
    if not state:
        problems.append("%s:%d [B guarded-access] no private state found in %s"
                        % ("", lo + 1, CLASS_NAME))

    for name, decl_line in state:
        pat = re.compile(r"\b%s\b" % re.escape(name))
        for m in pat.finditer(code):
            off = m.start()
            line_no = line_of(off)
            if line_no < lo + 1 or line_no > hi + 1:
                continue
            if line_no == decl_line:
                continue
            src = code[starts[line_no - 1]:starts[line_no] if line_no < len(starts)
                       else len(code)]
            # another member's declaration (e.g. `val blocks: List<T>,` in the
            # Window class) is not an access to this class's mutable state
            if re.match(r"^\s*(?:private |internal |protected |public )?(?:val|var)\s+\w+",
                        src):
                continue
            # [wide3/10] The old escape hatch was the substring
            # `"synchronized" in src`: an EMPTY block (`synchronized(lock) {}`)
            # or a constant-false guard on the same line kept the word and
            # dropped the mutual exclusion -- the access was exempt anyway.
            # Membership is now positional: the access must sit inside the body
            # of a live, non-empty synchronized block.
            if in_live_sync(bodies, off) or line_no in locked_lines:
                continue
            if any(a < off < b for (a, b) in monitor_spans):
                continue  # `synchronized(lock)` — the monitor is the lock
            problems.append("[B guarded-access] %s:%d reads/writes `%s` outside the lock: %s"
                            % ("", line_no, name, lines[line_no - 1].strip()))

    # C: every publish() invocation must be under the lock (or inside a *Locked helper)
    for m in re.finditer(r"\bpublish(Locked)?\s*\(", code):
        off = m.start()
        line_no = line_of(off)
        if line_no < lo + 1 or line_no > hi + 1:
            continue
        src = code[starts[line_no - 1]:starts[line_no] if line_no < len(starts)
                   else len(code)]
        if re.search(r"fun\s+\w+\s*\(\s*publish\s*:", src) or "publish:" in src:
            continue  # the parameter declaration
        if in_live_sync(bodies, off) or line_no in locked_lines:
            continue
        problems.append("[C publish-under-lock] line %d calls publish outside the lock: %s"
                        % (line_no, lines[line_no - 1].strip()))

    # D: the drained list must be handed out as a copy behind a read-only type
    if not re.search(r"val\s+blocks\s*:\s*List<", class_text):
        problems.append("[D no-raw-handout] Window.blocks is not declared as List<T>")
    args_list = window_ctor_args(code)
    if not args_list:
        problems.append("[D no-raw-handout] no Window(...) construction found")
    for args, base in args_list:
        span = _named_arg_span(args, "blocks")
        if span is None:
            problems.append("[D no-raw-handout] Window(...) without blocks: %s" % args.strip())
            continue
        val = args[span[0]:span[1]]
        if not _is_copy_of_blocks(val):
            # [wide3/10] `blocks = blocks.also { blocks.toList() }` CONTAINS the
            # copy call and still hands out the live list: `.also`/`.apply` return
            # the receiver, and `blocks.clear()` runs two lines later. The
            # criterion is the whole value expression, not a search inside it.
            lossy = re.search(r"\.\s*(also|apply|let|run)\s*\{", val)
            hint = " (copy result discarded by `.%s { … }`)" % lossy.group(1) if lossy else ""
            problems.append("[D no-raw-handout] window built with a live list%s: %s"
                            % (hint, val.strip()))
            continue
        # [batch-5] "copied" and "not yet drained" are different facts. Clearing
        # the live list BEFORE the window copies it rebuilds the window from an
        # emptied list while the copy criterion still passes — an ordering
        # defect, invisible to any judgement that only asks WHAT the value is.
        problems += _order_problems(code, members, base + span[0], lines, line_of)
    return problems


def window_ctor_args(code):
    """Argument text of every `Window(...)` *construction* (declaration skipped).

    The FULL argument list is returned (paren/brace balanced). The old criterion
    sliced the value with `[^,)]+`, so `blocks = blocks.also { blocks.toList() }`
    was read as `blocks.also { blocks.toList(` — the copy call was "found" inside
    a value whose outermost operation is `.also`.
    """
    out = []
    for m in re.finditer(r"\bWindow(?:<[^>]*>)?\s*\(", code):
        head = code[max(0, m.start() - 24):m.start()]
        if re.search(r"\bclass\s+$", head):
            continue
        op = code.find("(", m.start())
        end = _paren_end(code, op)
        if end is None:
            continue
        # (argument text, absolute offset of its first character) — the offset is
        # what makes the ORDER between "copy the list" and "clear the list"
        # decidable, instead of guessing from line order.
        out.append((code[op + 1:end], op + 1))
    return out


def _named_arg_span(args, name):
    """(start, end) of `name = <value>` inside a top-level argument list."""
    for start, end in split_top(args, (",",)):
        seg = args[start:end]
        m = re.match(r"\s*%s\s*=(?!=)\s*" % re.escape(name), seg)
        if m:
            return (start + m.end(), end)
    return None


COPY_OF_BLOCKS_RE = re.compile(
    r"^\s*blocks\s*\.\s*(?:toList|toMutableList)\s*\(\s*\)\s*$", re.S)


def _is_copy_of_blocks(val):
    """The value must BE `blocks.toList()` — not merely contain that call.

    [batch-5] `value_sources` peels the discarding scope calls (`.also`,
    `.apply`, `.onEach`, `.forEach`) and follows `let` / `run` / elvis: those
    wrappers hand out the RECEIVER or another value, so a copy call buried in
    one still leaves the window pointing at the live list.
    """
    for src in value_sources(val):
        if COPY_OF_BLOCKS_RE.match(src.strip()):
            return True
    return False


DESTRUCTIVE = re.compile(
    r"\bblocks\s*\.\s*(?:clear|remove|removeAt|removeAll|removeIf|retainAll)\s*\("
    r"|\bblocks\s*=(?!=)")


def innermost_bracket_at(code, pos):
    """The innermost bracket still open at [pos] (`(` = argument list)."""
    stack = []
    for ch in code[:pos]:
        if ch in "({[":
            stack.append(ch)
        elif ch in ")}]" and stack:
            stack.pop()
    return stack[-1] if stack else ""


def _order_problems(code, members, copy_pos, lines, line_of):
    """D-order: the copy must be READ before the live list is emptied.

    "The list is copied" and "the list is cleared" are two events. Draining
    first and copying afterwards builds the window from an emptied list while
    every line still satisfies the copy criterion.
    """
    lo_off, hi_off = 0, len(code)
    for _name, (start, end, _kind) in members.items():
        if start <= copy_pos < end:
            lo_off, hi_off = start, end
            break
    before = [m.start() for m in DESTRUCTIVE.finditer(code, lo_off, hi_off)
              if m.start() < copy_pos and innermost_bracket_at(code, m.start()) != "("]
    if not before:
        return []
    ln = line_of(copy_pos)
    return ["[D no-raw-handout] line %d drains `blocks` BEFORE the window copies it — "
            "the window is built from an emptied list (copy first, then clear): %s"
            % (ln, lines[ln - 1].strip())]


def rule_convergence(repo_root, class_file):
    """E: appendSystemInfo routes through the coalescer, not loose fields.

    [wide3/10] The old criterion asked whether the body CONTAINED
    `…Coalescer.method(`. `if (false) sysInfoCoalescer.append(…)` keeps that
    substring and drops every system notice, so the call must now be LIVE
    (`is_dead()`): an infeasible condition governing the call — on the statement
    or on any enclosing block, folded by the shared constant evaluator — makes it
    a no-op, which is exactly the incident shape.
    """
    problems = []
    for path in iter_prod_kt(repo_root):
        text = read(path)
        if text is None:
            continue
        rel = os.path.relpath(path, repo_root)
        code = view(text)
        for name, (start_off, end_off, _kind) in member_table(code).items():
            if not re.search(r"appendSystemInfo$", name):
                continue
            line_no = code.count("\n", 0, start_off) + 1
            body = code[start_off:end_off]
            # [batch-5] LIVENESS, not spelling. The old shape judged a call dead
            # only when a constant-false condition sat on the SAME statement —
            # `if (1 == 2) { append(…) }`, `when (false) { … }` and
            # `while (false) { … }` all kept the substring and dropped the
            # effect. `is_dead` derives the governing condition from POSITION.
            calls = list(re.finditer(r"\b\w*[Cc]oalescer\s*\.\s*\w+\s*\(", body))
            live = [c for c in calls if not is_dead(code, start_off + c.start())]
            if not live:
                why = ("only in dead code (infeasible branch / constant-false guard)"
                       if calls else "no coalescer call at all")
                problems.append("%s:%d [E convergence] appendSystemInfo does not route "
                                "through a coalescer instance — %s" % (rel, line_no, why))
            if "pendingSysInfo" in body:
                problems.append("%s:%d [E convergence] appendSystemInfo still touches "
                                "pendingSysInfo*" % (rel, line_no))
    return problems


def run(repo_root):
    problems = []
    class_file, class_text, class_span = None, None, None
    for path in iter_prod_kt(repo_root):
        text = read(path)
        if text is None:
            continue
        span = find_class_span(text, CLASS_NAME)
        if span:
            class_file = os.path.relpath(path, repo_root)
            class_text, class_span = text, span
            break
    if class_file is None:
        print("FAIL: no class %s in production sources — the convergence point "
              "was deleted, not fixed" % CLASS_NAME)
        return 1
    print("state home: %s:%d-%d" % (class_file, class_span[0] + 1, class_span[1] + 1))
    problems += rule_single_home(repo_root, class_file, class_span)
    problems += rule_guarded_access(class_text, class_span, view(class_text))
    problems += rule_convergence(repo_root, class_file)
    problems += rule_storage_state_off_io(repo_root)
    problems += rule_storage_cache_invalidation(repo_root)
    problems += rule_delete_invalidation(repo_root)

    if problems:
        print("")
        for p in problems:
            print("  VIOLATION %s" % p)
        print("")
        print("== %d sysinfo window problem(s) ==" % len(problems))
        print("The system-notice window is shared by Main and the agent loop's IO")
        print("thread; unguarded state there loses notices and can tear the list on")
        print("the UI thread. Keep the state inside %s (locked) and route" % CLASS_NAME)
        print("appendSystemInfo through it.")
        return 1
    print("== sysinfo coalesce window has a single guarded home ==")
    print("== storage page: background dispatchers publish nothing; rootfs "
          "operations and file/session deletions invalidate the cached scan ==")
    return 0



# ─── [deep-audit-1045 B-03] storage page: off-Main state + cache invalidation ─
#
# The Storage page recomputes a process-lifetime snapshot (rootfs lstat walk,
# DB size, per-session recursive sizing, orphan scan) and keeps it in
# `StorageSnapshotCache` so a re-entry does not repeat the storm. Two shapes
# went wrong and neither is visible to a single-threaded test:
#
#   F. OFF-MAIN PUBLISH — the composable holds its rows in `var … by remember`
#      state, and six of those variables (plus three in the per-session detail
#      screen) were assigned INSIDE `withContext(Dispatchers.IO)`. Compose state
#      has no cross-thread happens-before with the frame rendering it, and two
#      overlapping reloads can interleave assignments, so rows / totals / cache
#      can each come from a different scan. The criterion derives the state set
#      from the file's own `var … by remember` declarations (renames do not
#      evade it) and flags any ASSIGNMENT to one of them inside a
#      `Dispatchers.IO/Default` block. Call arguments (`name = value` inside a
#      call's parentheses) are not assignments and are skipped.
#
#   G. SINGLE INVALIDATION — the cache must have one non-private clearing entry,
#      and every ViewModel entry that drives a mutating `RootfsManager`
#      operation must call it BEFORE the mutation. Without it the cached
#      `shellSize` outlives the operation that rewrote the tree (the rescan path
#      reuses the cached totals, it does not re-walk the rootfs), i.e. the shell
#      row is pinned for the life of the process. The mutating member set is
#      derived from `RootfsManager` itself (a disk/state mutation, or a call to
#      one), so a renamed or newly added lifecycle operation is covered on the
#      day it lands.

STORAGE_SCREEN = PKG + "/ui/settings/StorageManagementScreen.kt"
ROOTFS_VM = PKG + "/ui/sandbox/RootfsManagementViewModel.kt"
ROOTFS_MANAGER = PKG + "/sandbox/RootfsManager.kt"

REMEMBER_VAR = re.compile(r"^\s*var\s+(\w+)\s*(?::\s*[^=\n]+)?\s+by\s+remember\b", re.M)
# `val x = remember { mutableStateOf(…) }` — the holder form, written `x.value =`.
REMEMBER_HOLDER = re.compile(
    r"^\s*val\s+(\w+)\s*(?::\s*[^=\n]+)?\s*=\s*remember\s*\{[^}]*mutableStateOf\b", re.M)
# `x = value` where the value came out of the IO block, but NOT `x = withContext(…)`.
STATE_ASSIGN = re.compile(r"^(\w+)(\.value)?\s*(?:\+=|-=|=)(?!=)\s*(.*)$")
CACHE_CLEAR = "StorageSnapshotCache.snapshot = null"

FILE_BROWSER_VM = PKG + "/ui/sandbox/FileBrowserViewModel.kt"
HISTORY_DRAWER = PKG + "/ui/chat/ChatHistoryDrawer.kt"
INVAL_CALL = "invalidateStorageSnapshot("
DELETE_SIGNS = (r"\.deleteRecursively\(\)", r"\.delete\(\)")

# Files that pair `remember` state with an off-main block TODAY. The criterion's
# scope is derived (so a new screen is covered the day it lands); this anchor
# only detects LOSS — renaming or deleting one of them must fail loudly instead
# of quietly shrinking the surface the gate still watches.
EXPECTED_OFF_MAIN_SCOPE = (
    PKG + "/ui/sandbox/FilePreviewScreen.kt",
    PKG + "/ui/settings/LogManagementScreen.kt",
    PKG + "/ui/settings/SoulSettingsScreen.kt",
    PKG + "/ui/settings/StorageManagementScreen.kt",
    PKG + "/webapp/AddToHomeSheet.kt",
    # [deep-audit-1049 B-02] the three sites the launch-shaped blocks uncovered:
    # the gallery's share/save resets, the backup screen's WebDAV error, the cold
    # prewarm timing. Anchored so a refactor that drops the launch (and with it
    # the only reason this criterion looks at the file) fails loudly.
    PKG + "/ui/components/ImageGalleryViewer.kt",
    PKG + "/ui/settings/BackupSettingsScreen.kt",
    PKG + "/ui/chat/ChatScreen.kt",
)

# Signals that a RootfsManager member mutates the rootfs or its published state.
MUTATE_SIGNS = (
    r"installState\.value\s*=",
    r"deleteRecursively",
    r"\.delete\(",
    r"mkdirs\(",
    r"copyTo\(",
    r"writeText\(",
    r"\.renameTo\(",
)


def brace_span(code, brace_idx):
    """(first_line, last_line) 1-based of the `{`-block at [brace_idx]."""
    close = match_bracket(code, brace_idx)
    if close < 0:
        return None
    return code.count("\n", 0, brace_idx) + 1, code.count("\n", 0, close) + 1


def innermost_brackets(code):
    """For each line, the innermost bracket still open at its start.

    `(` means the line sits directly in a call's ARGUMENT list, so `name = value`
    there is a named argument. `{` means it sits in a block (function body,
    `if`, or a lambda passed as an argument) — statements, where `name = value`
    IS an assignment. Paren depth alone cannot tell those apart, and Compose code
    nests both: the clear-confirm callback in the Storage screen is a lambda
    inside `AlertDialog(confirmButton = { … })`, i.e. two parens deep and still
    statement position. The first version of this gate used paren depth and went
    blind on exactly those two writes.
    """
    out, stack = [], []
    for line in code.split("\n"):
        out.append(stack[-1] if stack else "")
        for ch in line:
            if ch in "({[":
                stack.append(ch)
            elif ch in ")}]":
                if stack:
                    stack.pop()
    return out


# ── F: off-main blocks, derived ─────────────────────────────────────────────
#
# [deep-audit-1049 B-02] The original block set was `withContext(Dispatchers.IO|
# Default)` alone. Two more shapes write Compose state on a background thread
# and were therefore invisible to the gate:
#
#   * `launch(Dispatchers.Default) { … }` — the explicit dispatcher wins over
#     whatever context the receiver carried;
#   * `<scope>.launch { … }` — no dispatcher argument, so the thread comes from
#     the scope. WHICH scopes are off-main is derived from the tree: any name
#     declared as `CoroutineScope(…)` carrying Dispatchers.IO/Default (today
#     `MinisApp.applicationScope`), plus per-file aliases of it. No member list,
#     so a new app-wide scope is covered the day it is declared.
#
# Main-implicit scopes stay OUT, on two independent grounds: the API that
# creates them (`rememberCoroutineScope()`, `viewModelScope`, `lifecycleScope`,
# `MainScope()`) and an explicit `Dispatchers.Main` in the dispatcher argument.
# Without that exclusion every `scope.launch` in every screen would be red.
#
# Nesting resolves innermost-first: a write inside
# `withContext(Dispatchers.Main) { … }` that itself sits inside an off-main
# launch lands on Main and must not be reported. That is also why the fix for
# the three sites below is a Main hop rather than a restructure.
SCOPE_DECL = re.compile(
    r"^[ \t]*(?:(?:private|internal|public|protected)[ \t]+)?(?:val|var)[ \t]+(\w+)[ \t]*"
    r"(?::[ \t]*CoroutineScope[ \t]*)?(?:by[ \t]+lazy[ \t]*\{|=)([^\n]*)$", re.M)
# `val scope = rememberCoroutineScope()` / `val viewModelScope = …` — Main.implicit.
MAIN_SCOPE_FACTORY = re.compile(r"rememberCoroutineScope[ \t]*\(|\bMainScope[ \t]*\(")
MAIN_SCOPE_API = re.compile(r"\b(?:viewModelScope|lifecycleScope)\b")
OFF_DISPATCH = re.compile(r"Dispatchers\.(?:IO|Default)\b")
MAIN_DISPATCH = re.compile(r"Dispatchers\.Main\b")
SCOPE_CTOR = re.compile(r"CoroutineScope[ \t]*\(")
WITH_CTX = re.compile(r"\bwithContext[ \t]*\(")
LAUNCH = re.compile(r"\blaunch\b")
# The escape hatch for a deliberate off-main write is a
# `// offmain-state-ok: <reason>` marker on the offending line.
STATE_ESCAPE = "offmain-state-ok:"

def _scope_decls(code):
    """[(name, rhs)] for `val/var <name>[: CoroutineScope] = <rhs>`."""
    return [(m.group(1), m.group(2)) for m in SCOPE_DECL.finditer(code)]

def _word(name):
    return re.compile(r"\b%s\b" % re.escape(name))

def off_main_scope_roots(sources):
    """Scope names the tree gives an IO/Default dispatcher.

    Deliberately narrow: only a `CoroutineScope(…)` construction that carries
    Dispatchers.IO/Default counts. A looser test ("the RHS mentions
    Dispatchers.IO") also matches `val x = withContext(Dispatchers.IO) { … }`,
    and those names then alias-propagate through every file that mentions them
    — that version of this function took minutes instead of seconds.
    """
    roots = set()
    for _raw, code in sources.values():
        for name, rhs in _scope_decls(code):
            if (SCOPE_CTOR.search(rhs) and OFF_DISPATCH.search(rhs)
                    and not MAIN_DISPATCH.search(rhs)):
                roots.add(name)
    return roots

def file_scope_names(code, roots):
    """(off-main names, Main-implicit names) as seen inside one file.

    A file-local Main declaration shadows a tree-wide off-main name: the
    Storage screen's `val scope = rememberCoroutineScope()` and the gallery's
    `val scope = …applicationScope` are the same identifier with opposite
    threads, and only the file can tell them apart.
    """
    local_off, local_main = set(), set()
    decls = _scope_decls(code)
    for name, rhs in decls:
        if MAIN_SCOPE_FACTORY.search(rhs):
            local_main.add(name)
        elif SCOPE_CTOR.search(rhs) and OFF_DISPATCH.search(rhs) and not MAIN_DISPATCH.search(rhs):
            local_off.add(name)
    known = (roots | local_off) - local_main
    # `val child = app.applicationScope` — two passes cover `val a = b; val c = a`.
    for _ in range(2):
        for name, rhs in decls:
            if name in local_off or name in local_main:
                continue
            if any(_word(o).search(rhs) for o in known):
                local_off.add(name)
                known.add(name)
    return (roots | local_off) - local_main, local_main

def _paren_end(code, open_paren):
    """Matching `)` for the `(` at [open_paren] (shared bracket matcher)."""
    j = match_bracket(code, open_paren)
    return j if j >= 0 else len(code) - 1


def _arg_text(code, open_paren):
    return code[open_paren + 1:_paren_end(code, open_paren)]

def _inline_scope_kind(code, launch_idx):
    """`CoroutineScope(…Dispatchers.IO…).launch` — classify the receiver expr."""
    window = code[max(0, launch_idx - 240):launch_idx]
    last = None
    for m in SCOPE_CTOR.finditer(window):
        last = m
    if last is None:
        return None
    seg = window[last.start():]
    if not seg.rstrip().endswith(")"):
        return None
    if OFF_DISPATCH.search(seg) and not MAIN_DISPATCH.search(seg):
        return "off"
    if MAIN_DISPATCH.search(seg):
        return "main"
    return None

def dispatcher_blocks(code, off_names, main_names):
    """[(first_line, last_line, 'off'|'main')] for every thread-changing block."""
    out = []
    for m in WITH_CTX.finditer(code):
        args = _arg_text(code, m.end() - 1)
        brace = code.find("{", m.end())
        span = brace_span(code, brace) if brace >= 0 else None
        if not span:
            continue
        if MAIN_DISPATCH.search(args):
            out.append((span[0], span[1], "main"))
        elif OFF_DISPATCH.search(args):
            out.append((span[0], span[1], "off"))
    for m in LAUNCH.finditer(code):
        j = m.end()
        while j < len(code) and code[j] in " \t":
            j += 1
        args = ""
        if j < len(code) and code[j] == "(":
            args = _arg_text(code, j)
            j = _paren_end(code, j) + 1
        while j < len(code) and code[j] in " \t\n":
            j += 1
        if j >= len(code) or code[j] != "{":
            continue  # `launch(…)` with no trailing block: no region, nothing to classify
        span = brace_span(code, j)
        if not span:
            continue
        if MAIN_DISPATCH.search(args):
            out.append((span[0], span[1], "main"))
            continue
        if OFF_DISPATCH.search(args):
            out.append((span[0], span[1], "off"))
            continue
        tail = code[max(0, m.start() - 120):m.start()]
        rm = re.search(r"([A-Za-z_][\w.]*)[ \t]*\.[ \t]*$", tail)
        if rm:
            head = rm.group(1).split(".")[-1]
            if head in main_names or MAIN_SCOPE_API.search(head):
                out.append((span[0], span[1], "main"))
            elif head in off_names:
                out.append((span[0], span[1], "off"))
            continue
        kind = _inline_scope_kind(code, m.start())
        if kind:
            out.append((span[0], span[1], kind))
    return out

def _innermost_block(blocks, ln):
    best = None
    for lo, hi, kind in blocks:
        if lo <= ln <= hi:
            if best is None or (hi - lo) < (best[1] - best[0]):
                best = (lo, hi, kind)
    return best

def prod_sources(repo_root):
    """{rel: (raw text, comment/string-blanked code)} for every production .kt."""
    out = {}
    for path in iter_prod_kt(repo_root):
        text = read(path)
        if text is None:
            continue
        out[os.path.relpath(path, repo_root)] = (text, view(text))
    return out

def off_main_state_files(sources, roots):
    """Every production file that declares `remember` state AND runs an
    off-main block — the derived scope of criterion F."""
    out = []
    for rel, (_raw, code) in sources.items():
        if not (REMEMBER_VAR.search(code) or REMEMBER_HOLDER.search(code)):
            continue
        off_names, main_names = file_scope_names(code, roots)
        if not dispatcher_blocks(code, off_names, main_names):
            continue
        out.append(rel)
    return sorted(out)

def rule_storage_state_off_io(repo_root):
    """F: no Compose state is assigned from a background dispatcher."""
    sources = prod_sources(repo_root)
    roots = off_main_scope_roots(sources)
    files = off_main_state_files(sources, roots)
    if not files:
        return ["[F off-main-state] no production file pairs `remember` state with an "
                "off-main block — the subject of this criterion was moved or deleted"]
    problems = []
    for rel in EXPECTED_OFF_MAIN_SCOPE:
        if rel not in files:
            problems.append("[F off-main-state] %s no longer pairs Compose state with an "
                            "off-main block — if that is a rename/removal, update "
                            "EXPECTED_OFF_MAIN_SCOPE; if it is a refactor, the states moved "
                            "somewhere this criterion no longer sees" % rel)
    for rel in files:
        raw, code = sources[rel]
        problems += off_main_writes(rel, raw, code, roots)
    return problems

def off_main_writes(rel, raw, code, roots):
    """State assignments that land on a background thread.

    [batch-5] The assignment is found by OFFSET, not by matching `^` against a
    stripped line. The line-anchored version only saw a write that was the
    FIRST thing on its line, so `run { sharing = false }` — a write on an
    otherwise non-empty line, which is exactly what a coroutine hop collapses
    to — walked straight past it. Position also decides the statement/argument
    question: inside a call's `(` the text is a named argument, inside a block
    `{` it is an assignment.
    """
    delegated = set(REMEMBER_VAR.findall(code))
    holders = set(REMEMBER_HOLDER.findall(code))
    names = sorted(delegated | holders)
    if not names:
        return []
    off_names, main_names = file_scope_names(code, roots)
    blocks = dispatcher_blocks(code, off_names, main_names)
    if not blocks:
        return []
    lines = code.split("\n")
    raw_lines = raw.split("\n")
    pattern = re.compile(
        r"(?<![\w.])(" + "|".join(re.escape(n) for n in names) + r")"
        r"(\s*\.\s*value)?\s*(?:\+=|-=|=)(?!=)")
    problems, seen = [], set()
    for m in pattern.finditer(code):
        name, on_value = m.group(1), bool(m.group(2))
        if on_value:
            if name not in holders:
                continue
        elif name not in delegated:
            continue
        off = m.start()
        line_no = code.count("\n", 0, off) + 1
        if line_no in seen:
            continue
        inner = _innermost_block(blocks, line_no)
        if not inner or inner[2] != "off":
            continue
        if innermost_bracket_at(code, off) != "{":
            continue  # argument list (named argument) or top level: not a statement
        eol = code.find("\n", m.end())
        rhs = code[m.end():eol if eol >= 0 else len(code)].strip()
        if rhs.startswith("withContext(") or rhs.startswith("kotlinx.coroutines.withContext("):
            continue  # the value IS the call: the write lands on the caller's thread
        if STATE_ESCAPE in (raw_lines[line_no - 1] if line_no - 1 < len(raw_lines) else ""):
            continue
        seen.add(line_no)
        problems.append("[F off-main-state] %s:%d assigns Compose state `%s` on a "
                        "background dispatcher: %s"
                        % (rel, line_no, name, lines[line_no - 1].strip()))
    return problems

def _block_end(code, open_idx):
    """Matching `}` for the `{` at [open_idx] (shared bracket matcher)."""
    j = match_bracket(code, open_idx)
    return j if j > 0 else -1


def rule_delete_invalidation(repo_root):
    """C-INV: an entry that removes files must drop the cached storage scan.

    [batch-5] "Invalidated" is an EFFECT, not a spelling. The call used to be
    found with `body.find(...)`, so `if (false) invalidateStorageSnapshot()`
    kept the substring and dropped the drop — the Storage page then shows the
    pre-delete sizes for the rest of the process. Liveness is derived from
    position (`is_dead`), and the failure-path checks below judge the LIVE
    occurrence: an invalidation that never runs is not a placement defect, it is
    a missing invalidation.
    """
    problems, checked = [], 0

    text = read(os.path.join(repo_root, FILE_BROWSER_VM))
    if text is None:
        problems.append("[C-INV] %s missing — the subject of this criterion was "
                        "moved or deleted" % FILE_BROWSER_VM)
    else:
        code = view(text)
        for name, (span, body) in member_bodies(code).items():
            if not any(re.search(s, body) for s in DELETE_SIGNS):
                continue
            if "loadItems(" not in body:
                continue  # only the entries that re-list what they just removed
            checked += 1
            live = [m.start() for m in re.finditer(re.escape(INVAL_CALL), body)
                    if not is_dead(body, m.start())]
            if not live:
                dead = INVAL_CALL in body
                problems.append("[C-INV] %s:%d (%s) deletes a file/dir and then re-lists, "
                                "but %s" % (FILE_BROWSER_VM, span[0], name,
                                            "the cached-scan invalidation sits in dead code "
                                            "(infeasible branch) — it never runs"
                                            if dead else
                                            "never invalidates the cached storage scan"))
                continue
            inval = live[0]
            guard = re.search(r"if\s*\(\s*!\s*deleted\s*\)\s*\{", body)
            if guard:
                end = _block_end(body, guard.end() - 1)
                if end is not None and inval < end:
                    problems.append("[C-INV] %s:%d (%s) invalidates the cached scan on the "
                                    "FAILED-delete path — the tree is untouched there, so the "
                                    "drop buys a rescan and leaves the success path unpinned"
                                    % (FILE_BROWSER_VM, span[0], name))
            catch = re.search(r"catch\s*\([^)]*\)\s*\{", body)
            if catch:
                end = _block_end(body, catch.end() - 1)
                if end is not None and catch.start() < inval < end:
                    problems.append("[C-INV] %s:%d (%s) invalidates from the catch arm — the "
                                    "drop happens while the delete is still in doubt"
                                    % (FILE_BROWSER_VM, span[0], name))
    if checked == 0:
        problems.append("[C-INV] no FileBrowserViewModel member both deletes and re-lists — "
                        "the delete entry moved, so this criterion saw nothing to check")

    text = read(os.path.join(repo_root, HISTORY_DRAWER))
    if text is None:
        problems.append("[C-INV] %s missing — the subject of this criterion was "
                        "moved or deleted" % HISTORY_DRAWER)
    else:
        code = view(text)
        found = 0
        for name, (span, body) in member_bodies(code).items():
            if not re.search(r"\bdeleteSession\s*\(", body):
                continue
            found += 1
            live = [m.start() for m in re.finditer(re.escape(INVAL_CALL), body)
                    if not is_dead(body, m.start())]
            if not live:
                dead = INVAL_CALL in body
                problems.append("[C-INV] %s:%d (%s) deletes a session (and its on-disk tree, "
                                "which the Storage page sizes) and %s"
                                % (HISTORY_DRAWER, span[0], name,
                                   "invalidates the cached scan only from dead code"
                                   if dead else
                                   "never invalidates the cached scan"))
        if found == 0:
            problems.append("[C-INV] no %s entry calls deleteSession — the delete path moved, "
                            "so this criterion saw nothing to check" % HISTORY_DRAWER)
    return problems

def invalidation_entry(text):
    """(name, span) of the file-level non-private cache-clearing entry."""
    code = view(text)
    for m in re.finditer(r"^(?:internal |public )?fun\s+(\w+)\s*\(\s*\)\s*\{", code, re.M):
        span = brace_span(code, m.end() - 1)
        if not span:
            continue
        body = "\n".join(code.split("\n")[span[0] - 1: span[1]])
        if CACHE_CLEAR in body:
            return m.group(1), span
    return None

def mutating_manager_members(manager_text):
    """RootfsManager's non-private members that mutate disk / published state.

    Transitive: a member that only calls a mutating one (e.g. `autoRepair` →
    `reset`) is mutating too, so a new lifecycle op cannot slip past the gate
    by delegating.

    [batch-5] Member bodies come from the shared `member_table` — the previous
    hand-written span could swallow a following member's brace block, which
    turned a read-only member into a "mutating" one (and vice versa).
    """
    code = view(manager_text)
    public = {m.group(1) for m in re.finditer(
        r"^    (?!private )(?:internal |public )?(?:suspend )?fun\s+(\w+)\s*\(", code, re.M)}
    bodies = {name: text for name, (_span, text) in member_bodies(code).items()
              if name in public}
    mut = {n for n, b in bodies.items() if any(re.search(s, b) for s in MUTATE_SIGNS)}
    changed = True
    while changed:
        changed = False
        for name, body in bodies.items():
            if name in mut:
                continue
            if any(re.search(r"\b%s\s*\(" % re.escape(m), body) for m in mut):
                mut.add(name)
                changed = True
    return mut

def lifecycle_invalidation_problems(vm_text, entry_name, mutators):
    code = view(vm_text)
    problems, seen = [], []
    for name, (span, body) in member_bodies(code).items():
        calls = [c.group(1) for c in re.finditer(r"\bmanager\.(\w+)\s*\(", body)]
        mut = [c for c in calls if c in mutators]
        if not mut:
            continue
        seen.append(name)
        mut_pos = next(m.start() for m in re.finditer(r"\bmanager\.(\w+)\s*\(", body)
                       if m.group(1) in mutators)
        invals = [m.start() for m in re.finditer(r"\b%s\s*\(" % re.escape(entry_name), body)]
        if not invals:
            problems.append("[G cache-invalidation] %s:%d (%s) mutates the rootfs but never "
                            "invalidates the cached storage scan"
                            % (ROOTFS_VM, span[0], name))
        elif invals[0] > mut_pos:
            problems.append("[G cache-invalidation] %s:%d (%s) invalidates the cached storage "
                            "scan only AFTER manager.%s() — an interruption between the two "
                            "would leave the stale total pinned"
                            % (ROOTFS_VM, span[0], name, mut[0]))
    if not seen:
        problems.append("[G cache-invalidation] no ViewModel entry drives a mutating "
                        "RootfsManager call — the lifecycle moved, so this criterion saw "
                        "nothing to check")
    return problems

def rule_storage_cache_invalidation(repo_root):
    """G: one clearing entry, called before every rootfs mutation."""
    screen = read(os.path.join(repo_root, STORAGE_SCREEN))
    if screen is None:
        return ["%s [G cache-invalidation] file missing — the subject of this "
                "criterion was moved or deleted" % STORAGE_SCREEN]
    entry = invalidation_entry(screen)
    if entry is None:
        return ["%s [G cache-invalidation] no non-private entry clears the cached "
                "scan — nothing outside this file can invalidate it" % STORAGE_SCREEN]
    entry_name = entry[0]
    problems = []
    sites = [i + 1 for i, ln in enumerate(screen.split("\n"))
             if ln.strip().startswith(CACHE_CLEAR)]
    if len(sites) != 1:
        problems.append("[G cache-invalidation] %s:%s clears the cache from %d places "
                        "(single writer expected: only inside %s())"
                        % (STORAGE_SCREEN, ",".join(str(s) for s in sites), len(sites),
                           entry_name))
    vm = read(os.path.join(repo_root, ROOTFS_VM))
    mgr = read(os.path.join(repo_root, ROOTFS_MANAGER))
    if vm is None or mgr is None:
        problems.append("[G cache-invalidation] %s or %s missing — cannot verify who "
                        "invalidates the storage snapshot" % (ROOTFS_VM, ROOTFS_MANAGER))
        return problems
    mutators = mutating_manager_members(mgr)
    if not mutators:
        problems.append("[G cache-invalidation] no mutating RootfsManager member found — "
                        "the operations moved, not the invalidation")
        return problems
    return problems + lifecycle_invalidation_problems(vm, entry_name, mutators)


# ─── fixtures / selftest ─────────────────────────────────────────────────────

GOOD_COALESCER = """package com.rikkaminis.app.ui.chat

internal class SysInfoCoalescer<T>(private val idOf: (T) -> String) {

    internal class Window<T>(
        val firstId: String,
        val blocks: List<T>,
    )

    private class WindowState(val iconKind: String, val firstId: String)

    private val lock = Any()
    private var current: WindowState? = null
    private val blocks = ArrayList<T>()

    fun append(block: T, iconKind: String, publish: (Window<T>) -> Unit) {
        synchronized(lock) {
            if (current != null && current?.iconKind != iconKind) {
                publishLocked(publish)
            }
            if (current == null) {
                current = WindowState(iconKind = iconKind, firstId = idOf(block))
            }
            blocks.add(block)
        }
    }

    fun flush(publish: (Window<T>) -> Unit) {
        synchronized(lock) {
            publishLocked(publish)
        }
    }

    private fun publishLocked(publish: (Window<T>) -> Unit) {
        val state = current ?: return
        val drained = Window(firstId = state.firstId, blocks = blocks.toList())
        current = null
        blocks.clear()
        publish(drained)
    }
}
"""

GOOD_VM = """package com.rikkaminis.app.ui.chat

class ChatViewModel {
    private val sysInfoCoalescer = SysInfoCoalescer<String>(idOf = { it })
    private var sysInfoFlushJob: Job? = null

    internal fun appendSystemInfo(text: String, iconKind: String) {
        sysInfoCoalescer.append(text, iconKind) { window -> publish(window) }
        sysInfoFlushJob?.cancel()
    }

    private fun flushPendingSysInfo() {
        sysInfoCoalescer.flush { window -> publish(window) }
    }

    private fun publish(window: SysInfoCoalescer.Window<String>) {
        println(window.firstId)
    }
}
"""


GOOD_STORAGE = """package com.rikkaminis.app.ui.settings

private object StorageSnapshotCache {
    @Volatile
    var snapshot: Snapshot? = null
}

internal fun invalidateStorageSnapshot() {
    StorageSnapshotCache.snapshot = null
}

@Composable
fun StorageManagementScreen() {
    var shellSize by remember { mutableLongStateOf(0L) }
    var orphanInfo by remember {
        mutableStateOf<Report?>(null)
    }

    fun reload(force: Boolean = false) {
        scope.launch {
            val cached = StorageSnapshotCache.snapshot
            if (cached != null) {
                shellSize = cached.shellSize
                orphanInfo = cached.orphanInfo
            }
            val scanned = withContext(Dispatchers.IO) {
                val report = scan()
                Snapshot(
                    shellSize = report.totalBytes,
                    orphanInfo = report.orphans,
                )
            }
            StorageSnapshotCache.snapshot = scanned
            shellSize = scanned.shellSize
            orphanInfo = scanned.orphanInfo
        }
    }
}
"""

GOOD_VM_ROOTFS = """package com.rikkaminis.app.ui.sandbox

class RootfsManagementViewModel {
    fun install(ctx: Context) {
        val manager = RootfsManager.getInstance(ctx)
        invalidateStorageSnapshot()
        viewModelScope.launch {
            manager.installIfNeeded()
        }
    }

    fun repairRootfs(ctx: Context) {
        val manager = RootfsManager.getInstance(ctx)
        viewModelScope.launch {
            val initial = withContext(Dispatchers.IO) {
                manager.verifyIntegrity()
            }
            if (initial.healthy) return@launch
            invalidateStorageSnapshot()
            val repaired = manager.autoRepair()
        }
    }
}
"""

GOOD_MANAGER = """package com.rikkaminis.app.ui.sandbox

class RootfsManager {
    fun verifyIntegrity(): Boolean = true

    suspend fun installIfNeeded() {
        _installState.value = RootfsInstallState.Preparing
    }

    suspend fun reset() {
        rootfsDir.deleteRecursively()
        installIfNeeded()
    }

    suspend fun autoRepair(): Boolean {
        reset()
        return true
    }
}
"""


GOOD_PREVIEW = """package com.rikkaminis.app.ui.sandbox

@Composable
private fun TextPreview(item: FileItem) {
    var content by remember(item.file) { mutableStateOf<String?>(null) }
    var truncated by remember(item.file) { mutableStateOf(false) }

    LaunchedEffect(item.file) {
        val capped = withContext(Dispatchers.IO) { readCapped(item.file) }
        content = decodeUtf8Capped(capped)
        truncated = capped.size > MAX_BYTES
    }
}
"""

GOOD_LOG_SCREEN = """package com.rikkaminis.app.ui.settings

@Composable
private fun LogDetailScreen(file: File) {
    var text by remember(file) { mutableStateOf<String?>(null) }
    var loadError by remember(file) { mutableStateOf<String?>(null) }

    LaunchedEffect(file) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { readLogText(file) }.getOrNull()
        }
        text = loaded
        if (loaded == null) loadError = "cannot read"
    }
}
"""

GOOD_SHEET_FILE = """package com.rikkaminis.app.webapp

@Composable
fun AddToHomeSheet(source: WebAppSource, onDismiss: () -> Unit) {
    var copiedFile by remember { mutableStateOf<File?>(null) }
    var parsedTitle by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(source) {
        val staged = withContext(Dispatchers.IO) {
            stage(source)
        }
        copiedFile = staged
        parsedTitle = parseHtmlTitle(staged)
    }
}
"""

GOOD_SOUL = """package com.rikkaminis.app.ui.settings

@Composable
fun SoulSettingsScreen() {
    var name by remember { mutableStateOf("") }
    val parsed = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            SoulStore.load(context)
        }
        name = loaded.name
        parsed.value = loaded.body
    }
}
"""

GOOD_FILE_BROWSER = """package com.rikkaminis.app.ui.sandbox

import com.rikkaminis.app.ui.settings.invalidateStorageSnapshot

class FileBrowserViewModel {
    fun deleteItem(item: FileItem) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val deleted = if (item.isDirectory) item.file.deleteRecursively() else item.file.delete()
                if (!deleted) {
                    _uiState.value = _uiState.value.copy(errorMessage = "Delete failed")
                    return@launch
                }
                invalidateStorageSnapshot()
                loadItems()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = e.message)
            }
        }
    }
}
"""

GOOD_DRAWER = """package com.rikkaminis.app.ui.chat

private fun deleteSessionAndCleanup(chatRepository: ChatRepository, id: String) {
    drawerIoScope.launch {
        try {
            chatRepository.deleteSession(id)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "deleteSession failed", t)
        } finally {
            ChatViewModelStore.release(id)
        }
        com.rikkaminis.app.ui.settings.invalidateStorageSnapshot()
    }
}
"""

GOOD_APP = """package com.rikkaminis.app

class MinisApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
"""

GOOD_GALLERY = """package com.rikkaminis.app.ui.components

@Composable
fun ImageGalleryViewer() {
    val context = LocalContext.current
    val scope = (context.applicationContext as? MinisApp)?.applicationScope
        ?: rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    ImageActionButton(onClick = {
        sharing = true
        scope.launch {
            try {
                shareImage(context)
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    sharing = false
                }
            }
        }
    })
    ImageActionButton(onClick = {
        saving = true
        scope.launch {
            try {
                saveToGallery(context)
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    saving = false
                }
            }
        }
    })
}
"""

GOOD_BACKUP_SCREEN = """package com.rikkaminis.app.ui.settings

@Composable
fun BackupSettingsScreen(application: MinisApp) {
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var operationBusy by remember { mutableStateOf(false) }
    val upload: () -> Unit = upload@{
        application.applicationScope.launch {
            try {
                val cfg = withContext(Dispatchers.Main) {
                    val current = webDavConfig
                    if (current == null) {
                        errorMessage = context.getString(R.string.webdav_server_not_configured)
                    }
                    current
                }
                if (cfg == null) {
                    notifier.notifyWorkCompleted(tag = "webdav-upload")
                }
            } finally {
                withContext(Dispatchers.Main) { operationBusy = false }
            }
        }
    }
}
"""

GOOD_CHAT_SCREEN_OFFMAIN = """package com.rikkaminis.app.ui.chat

@Composable
fun ChatScreen() {
    var lastColdPrewarmMs by remember { mutableStateOf(-1L) }
    LaunchedEffect(Unit) {
        launch(Dispatchers.Default) {
            val prewarmMs = 12L
            withContext(Dispatchers.Main.immediate) {
                lastColdPrewarmMs = prewarmMs
            }
        }
    }
}
"""

GOOD_VM_SCOPE_SCREEN = """package com.rikkaminis.app.ui.settings

@Composable
fun MemoryManagementScreen() {
    var count by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        viewModelScope.launch {
            count = 1
        }
    }
}
"""

def make_tree(files):
    root = tempfile.mkdtemp(prefix="sysinfo-state-selftest-")
    for rel, content in files.items():
        path = os.path.join(root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(content)
    return root


def selftest():
    arms = []
    good = {
        CHAT + "/ChatViewModel.kt": GOOD_VM + "\n" + GOOD_COALESCER,
        STORAGE_SCREEN: GOOD_STORAGE,
        ROOTFS_VM: GOOD_VM_ROOTFS,
        ROOTFS_MANAGER: GOOD_MANAGER,
        PKG + "/ui/settings/SoulSettingsScreen.kt": GOOD_SOUL,
        PKG + "/ui/sandbox/FilePreviewScreen.kt": GOOD_PREVIEW,
        PKG + "/ui/settings/LogManagementScreen.kt": GOOD_LOG_SCREEN,
        PKG + "/webapp/AddToHomeSheet.kt": GOOD_SHEET_FILE,
        FILE_BROWSER_VM: GOOD_FILE_BROWSER,
        HISTORY_DRAWER: GOOD_DRAWER,
        PKG + "/MinisApp.kt": GOOD_APP,
        PKG + "/ui/components/ImageGalleryViewer.kt": GOOD_GALLERY,
        PKG + "/ui/settings/BackupSettingsScreen.kt": GOOD_BACKUP_SCREEN,
        PKG + "/ui/chat/ChatScreen.kt": GOOD_CHAT_SCREEN_OFFMAIN,
        PKG + "/ui/settings/MemoryManagementScreen.kt": GOOD_VM_SCOPE_SCREEN,
    }
    root = make_tree(good)
    arms.append(("compliant fixture is green", run(root) == 0))
    _rm(root)

    bad = dict(good)
    bad[CHAT + "/ChatViewModel.kt"] = (
        GOOD_VM.replace("    private var sysInfoFlushJob: Job? = null\n",
                        "    private var sysInfoFlushJob: Job? = null\n"
                        "    private val pendingSysInfoBlocks = mutableListOf<String>()\n"))
    root = make_tree(bad)
    arms.append(("loose state outside the class is red", run(root) == 1))
    _rm(root)

    bad = dict(good)
    bad[CHAT + "/ChatViewModel.kt"] = (GOOD_VM + "\n" + GOOD_COALESCER).replace(
        "        synchronized(lock) {\n            publishLocked(publish)\n        }",
        "        publishLocked(publish)")
    root = make_tree(bad)
    arms.append(("publish outside the lock is red", run(root) == 1))
    _rm(root)

    bad = dict(good)
    bad[CHAT + "/ChatViewModel.kt"] = (GOOD_VM + "\n" + GOOD_COALESCER).replace(
        "blocks = blocks.toList()", "blocks = blocks")
    root = make_tree(bad)
    arms.append(("handing out the live list is red", run(root) == 1))
    _rm(root)

    # ── [wide3/10] "the word `synchronized` is on the line" ≠ "the lock is held"
    bad = dict(good)
    bad[CHAT + "/ChatViewModel.kt"] = (GOOD_VM + "\n" + GOOD_COALESCER).replace(
        "    fun flush(publish: (Window<T>) -> Unit) {\n"
        "        synchronized(lock) {\n"
        "            publishLocked(publish)\n"
        "        }\n"
        "    }",
        "    fun flush(publish: (Window<T>) -> Unit) {\n"
        "        if (false) synchronized(lock) {}; publishLocked(publish)\n"
        "    }")
    root = make_tree(bad)
    arms.append(("dead empty lock on the same line does not guard publish", run(root) == 1))
    _rm(root)

    # a copy call whose RESULT is discarded by `.also` hands out the live list
    bad = dict(good)
    bad[CHAT + "/ChatViewModel.kt"] = (GOOD_VM + "\n" + GOOD_COALESCER).replace(
        "blocks = blocks.toList()", "blocks = blocks.also { blocks.toList() }")
    root = make_tree(bad)
    arms.append(("a copy discarded by .also is still the live list", run(root) == 1))
    _rm(root)

    # a coalescer call behind a constant-false guard routes nowhere
    bad = dict(good)
    bad[CHAT + "/ChatViewModel.kt"] = (GOOD_VM + "\n" + GOOD_COALESCER).replace(
        "        sysInfoCoalescer.append(text, iconKind) { window -> publish(window) }",
        "        if (false) sysInfoCoalescer.append(text, iconKind) { window -> publish(window) }")
    root = make_tree(bad)
    arms.append(("a dead coalescer call does not route appendSystemInfo", run(root) == 1))
    _rm(root)

    bad = dict(good)
    bad[CHAT + "/ChatViewModel.kt"] = GOOD_COALESCER + "\n" + GOOD_VM.replace(
        "        sysInfoCoalescer.append(text, iconKind) { window -> publish(window) }",
        "        pendingSysInfoBlocks.add(text)")
    root = make_tree(bad)
    arms.append(("unrouted appendSystemInfo is red", run(root) == 1))
    _rm(root)

    bad = dict(good)
    bad[CHAT + "/ChatViewModel.kt"] = GOOD_VM.replace(
        "    private var sysInfoFlushJob: Job? = null",
        "    private var sysInfoWide: MutableList<String> = mutableListOf()")
    root = make_tree(bad)
    arms.append(("mutable-collection sysinfo state is red", run(root) == 1))
    _rm(root)

    root = make_tree({CHAT + "/ChatViewModel.kt": "package x\nclass A\n"})
    arms.append(("missing coalescer class fails loudly", run(root) == 1))
    _rm(root)

    # ── [deep-audit-1045 B-03] storage page ────────────────────────────────

    bad = dict(good)
    bad[STORAGE_SCREEN] = GOOD_STORAGE.replace(
        "                val report = scan()\n",
        "                shellSize = 1L\n                val report = scan()\n")
    root = make_tree(bad)
    arms.append(("state written on the IO dispatcher is red", run(root) == 1))
    _rm(root)

    bad = dict(good)
    bad[STORAGE_SCREEN] = GOOD_STORAGE.replace(
        "            val scanned = withContext(Dispatchers.IO) {",
        "            val scanned = AlertDialog(confirmButton = {\n"
        "                withContext(Dispatchers.IO) {\n"
        "                    shellSize = 1L\n"
        "                }\n"
        "            })\n"
        "            val ignored = withContext(Dispatchers.IO) {")
    root = make_tree(bad)
    arms.append(("a write inside a lambda argument is red", run(root) == 1))
    _rm(root)

    named = dict(good)
    named[STORAGE_SCREEN] = GOOD_STORAGE.replace(
        "                val report = scan()\n",
        "                val report = scan()\n"
        "                val ignored = Snapshot(\n"
        "                    shellSize = report.totalBytes,\n"
        "                )\n")
    root = make_tree(named)
    arms.append(("a call argument inside IO is not a state write", run(root) == 0))
    _rm(root)

    bad = dict(good)
    bad[STORAGE_SCREEN] = GOOD_STORAGE.replace(
        "internal fun invalidateStorageSnapshot", "private fun invalidateStorageSnapshot")
    root = make_tree(bad)
    arms.append(("a private cache-clearing entry is red", run(root) == 1))
    _rm(root)

    bad = dict(good)
    bad[STORAGE_SCREEN] = GOOD_STORAGE + (
        "\ninternal fun clearAgain() {\n"
        "    StorageSnapshotCache.snapshot = null\n"
        "}\n")
    root = make_tree(bad)
    arms.append(("a second cache-clearing site is red", run(root) == 1))
    _rm(root)

    bad = dict(good)
    bad[ROOTFS_VM] = GOOD_VM_ROOTFS.replace("        invalidateStorageSnapshot()\n", "")
    root = make_tree(bad)
    arms.append(("a rootfs mutation that never invalidates is red", run(root) == 1))
    _rm(root)

    bad = dict(good)
    bad[ROOTFS_VM] = GOOD_VM_ROOTFS.replace(
        "        invalidateStorageSnapshot()\n"
        "        viewModelScope.launch {\n"
        "            manager.installIfNeeded()\n"
        "        }",
        "        viewModelScope.launch {\n"
        "            manager.installIfNeeded()\n"
        "        }\n"
        "        invalidateStorageSnapshot()")
    root = make_tree(bad)
    arms.append(("invalidating only after the mutation is red", run(root) == 1))
    _rm(root)

    bad = dict(good)
    bad.pop(STORAGE_SCREEN, None)
    root = make_tree(bad)
    arms.append(("deleting the storage screen fails loudly", run(root) == 1))
    _rm(root)

    # ── [deep-audit-1048 B-01] derived scope + holder form ─────────────────

    bad = dict(good)
    bad[PKG + "/ui/settings/SoulSettingsScreen.kt"] = GOOD_SOUL.replace(
        "        name = loaded.name\n", "").replace(
        "        parsed.value = loaded.body\n", "")
    root = make_tree(bad)
    arms.append(("a compliant off-main screen is green", run(root) == 0))
    _rm(root)

    bad = dict(good)
    bad[PKG + "/ui/settings/SoulSettingsScreen.kt"] = GOOD_SOUL.replace(
        "            SoulStore.load(context)\n",
        "            val early = SoulStore.load(context)\n"
        "            name = early.name\n")
    root = make_tree(bad)
    arms.append(("a delegated state written from IO is red", run(root) == 1))
    _rm(root)

    bad = dict(good)
    bad[PKG + "/ui/settings/SoulSettingsScreen.kt"] = GOOD_SOUL.replace(
        "            SoulStore.load(context)\n",
        "            val early = SoulStore.load(context)\n"
        "            parsed.value = early.body\n")
    root = make_tree(bad)
    arms.append(("a remember-holder written from IO is red", run(root) == 1))
    _rm(root)

    bad = dict(good)
    bad.pop(PKG + "/ui/settings/SoulSettingsScreen.kt", None)
    root = make_tree(bad)
    arms.append(("deleting a file that anchors the derived scope is red", run(root) == 1))
    _rm(root)

    # ── [deep-audit-1048 B-05] deletions invalidate the cached scan ────────

    bad = dict(good)
    bad[FILE_BROWSER_VM] = GOOD_FILE_BROWSER.replace(
        "                invalidateStorageSnapshot()\n", "")
    root = make_tree(bad)
    arms.append(("a delete that re-lists without invalidating is red", run(root) == 1))
    _rm(root)

    bad = dict(good)
    bad[FILE_BROWSER_VM] = GOOD_FILE_BROWSER.replace(
        "                    return@launch\n"
        "                }\n"
        "                invalidateStorageSnapshot()\n",
        "                    invalidateStorageSnapshot()\n"
        "                    return@launch\n"
        "                }\n")
    root = make_tree(bad)
    arms.append(("invalidating on the failed-delete path is red", run(root) == 1))
    _rm(root)

    bad = dict(good)
    bad[HISTORY_DRAWER] = GOOD_DRAWER.replace(
        "        com.rikkaminis.app.ui.settings.invalidateStorageSnapshot()\n", "")
    root = make_tree(bad)
    arms.append(("a session delete without invalidating is red", run(root) == 1))
    _rm(root)

    # ── [deep-audit-1049 B-02] launch-shaped off-main blocks ───────────────

    def f_problems(root):
        """Only the write findings — the anchor-loss messages are separate."""
        return [p for p in rule_storage_state_off_io(root) if " assigns Compose state " in p]

    arms.append(("the three launch-shaped screens are green as written",
                 f_problems(root) == []))

    SHARE_HOP = ("                withContext(Dispatchers.Main.immediate) {\n"
                 "                    sharing = false\n"
                 "                }\n")
    SAVE_HOP = ("                withContext(Dispatchers.Main.immediate) {\n"
                "                    saving = false\n"
                "                }\n")
    BACKUP_HOP = ("                val cfg = withContext(Dispatchers.Main) {\n"
                  "                    val current = webDavConfig\n"
                  "                    if (current == null) {\n"
                  "                        errorMessage = context.getString("
                  "R.string.webdav_server_not_configured)\n"
                  "                    }\n"
                  "                    current\n"
                  "                }\n")
    BACKUP_RAW = ("                val cfg = webDavConfig\n"
                  "                if (cfg == null) {\n"
                  "                    errorMessage = context.getString("
                  "R.string.webdav_server_not_configured)\n"
                  "                }\n")
    PREWARM_HOP = ("            withContext(Dispatchers.Main.immediate) {\n"
                   "                lastColdPrewarmMs = prewarmMs\n"
                   "            }\n")
    PREWARM_RAW = "            lastColdPrewarmMs = prewarmMs\n"

    bad = dict(good)
    bad[PKG + "/ui/components/ImageGalleryViewer.kt"] = GOOD_GALLERY.replace(
        SHARE_HOP, "                sharing = false\n")
    root = make_tree(bad)
    probs = f_problems(root)
    arms.append(("a state reset in an app-scope finally is red (one point only)",
                 len(probs) == 1 and "`sharing`" in probs[0]))
    _rm(root)

    bad = dict(good)
    bad[PKG + "/ui/components/ImageGalleryViewer.kt"] = GOOD_GALLERY.replace(
        SAVE_HOP, "                saving = false\n")
    root = make_tree(bad)
    probs = f_problems(root)
    arms.append(("the save-album reset is red on its own point only",
                 len(probs) == 1 and "`saving`" in probs[0]))
    _rm(root)

    bad = dict(good)
    bad[PKG + "/ui/settings/BackupSettingsScreen.kt"] = GOOD_BACKUP_SCREEN.replace(
        BACKUP_HOP, BACKUP_RAW)
    root = make_tree(bad)
    probs = f_problems(root)
    arms.append(("errorMessage written from an app-scope launch is red",
                 len(probs) == 1 and "`errorMessage`" in probs[0]))
    _rm(root)

    bad = dict(good)
    bad[PKG + "/ui/chat/ChatScreen.kt"] = GOOD_CHAT_SCREEN_OFFMAIN.replace(
        PREWARM_HOP, PREWARM_RAW)
    root = make_tree(bad)
    probs = f_problems(root)
    arms.append(("launch(Dispatchers.Default) writing remember state is red",
                 len(probs) == 1 and "`lastColdPrewarmMs`" in probs[0]))
    _rm(root)

    bad = dict(good)
    bad[PKG + "/ui/components/ImageGalleryViewer.kt"] = GOOD_GALLERY.replace(
        SHARE_HOP, "                sharing = false\n").replace(
        SAVE_HOP, "                saving = false\n")
    bad[PKG + "/ui/settings/BackupSettingsScreen.kt"] = GOOD_BACKUP_SCREEN.replace(
        BACKUP_HOP, BACKUP_RAW)
    bad[PKG + "/ui/chat/ChatScreen.kt"] = GOOD_CHAT_SCREEN_OFFMAIN.replace(
        PREWARM_HOP, PREWARM_RAW)
    root = make_tree(bad)
    probs = f_problems(root)
    names = sorted(re.findall(r"assigns Compose state `(\w+)`", " ".join(probs)))
    arms.append(("reverting all four sites reddens exactly those four",
                 names == ["errorMessage", "lastColdPrewarmMs", "saving", "sharing"]))
    _rm(root)

    named = dict(good)
    named[PKG + "/ui/components/ImageGalleryViewer.kt"] = GOOD_GALLERY.replace(
        SHARE_HOP, "                sharing = false // offmain-state-ok: fixture\n")
    root = make_tree(named)
    arms.append(("the offmain-state-ok escape hatch silences one site",
                 f_problems(root) == []))
    _rm(root)

    named = dict(good)
    named[PKG + "/ui/chat/ChatScreen.kt"] = GOOD_CHAT_SCREEN_OFFMAIN.replace(
        "launch(Dispatchers.Default) {\n            val prewarmMs = 12L",
        "launch(Dispatchers.Main.immediate) {\n            val prewarmMs = 12L").replace(
        PREWARM_HOP, PREWARM_RAW)
    root = make_tree(named)
    arms.append(("an explicit Main dispatcher is not off-main",
                 f_problems(root) == []))
    _rm(root)

    named = dict(good)
    named[PKG + "/MinisApp.kt"] = GOOD_APP.replace(
        "Dispatchers.IO", "Dispatchers.Main.immediate")
    root = make_tree(named)
    arms.append(("a Main-backed application scope is not off-main",
                 f_problems(root) == []))
    _rm(root)

    bad = dict(good)
    bad.pop(PKG + "/ui/components/ImageGalleryViewer.kt", None)
    root = make_tree(bad)
    arms.append(("deleting a launch-shaped anchor file is red", run(root) == 1))
    _rm(root)

    # ── [batch-5] the five blind axes of this wave, one arm each ───────────
    # (a) monitor identity: a monitor allocated per call excludes nobody.
    bad = dict(good)
    bad[CHAT + "/ChatViewModel.kt"] = (GOOD_VM + "\n" + GOOD_COALESCER).replace(
        "    fun flush(publish: (Window<T>) -> Unit) {\n"
        "        synchronized(lock) {\n"
        "            publishLocked(publish)\n"
        "        }\n"
        "    }",
        "    fun flush(publish: (Window<T>) -> Unit) {\n"
        "        synchronized(Any()) {\n"
        "            publishLocked(publish)\n"
        "        }\n"
        "    }")
    root = make_tree(bad)
    probs = [p for p in rule_guarded_access(
        read(os.path.join(root, CHAT + "/ChatViewModel.kt")),
        find_class_span(read(os.path.join(root, CHAT + "/ChatViewModel.kt")), CLASS_NAME),
        view(read(os.path.join(root, CHAT + "/ChatViewModel.kt"))))
        if "publish-under-lock" in p]
    arms.append(("a per-call monitor is not the class lock",
                 run(root) == 1 and any("not the class lock" in p for p in probs)))
    _rm(root)

    # (b) order: draining the list BEFORE the window copies it.
    bad = dict(good)
    bad[CHAT + "/ChatViewModel.kt"] = (GOOD_VM + "\n" + GOOD_COALESCER).replace(
        "        val drained = Window(firstId = state.firstId, blocks = blocks.toList())\n"
        "        current = null\n"
        "        blocks.clear()",
        "        current = null\n"
        "        blocks.clear()\n"
        "        val drained = Window(firstId = state.firstId, blocks = blocks.toList())")
    root = make_tree(bad)
    probs = [p for p in rule_guarded_access(
        read(os.path.join(root, CHAT + "/ChatViewModel.kt")),
        find_class_span(read(os.path.join(root, CHAT + "/ChatViewModel.kt")), CLASS_NAME),
        view(read(os.path.join(root, CHAT + "/ChatViewModel.kt"))))
        if "no-raw-handout" in p]
    arms.append(("draining before the copy is red",
                 run(root) == 1 and any("BEFORE the window copies it" in p for p in probs)))
    _rm(root)

    # (c) dead branch decided by ARITHMETIC, not by the `false` token.
    bad = dict(good)
    bad[CHAT + "/ChatViewModel.kt"] = (GOOD_VM + "\n" + GOOD_COALESCER).replace(
        "        sysInfoCoalescer.append(text, iconKind) { window -> publish(window) }",
        "        if (1 == 2) { sysInfoCoalescer.append(text, iconKind)"
        " { window -> publish(window) } }")
    root = make_tree(bad)
    arms.append(("a dead coalescer call decided by arithmetic routes nowhere",
                 run(root) == 1))
    _rm(root)

    # (d) the coroutine hop collapses to `run { sharing = false }` — a write on
    # an otherwise non-empty line, invisible to a line-anchored criterion.
    bad = dict(good)
    bad[PKG + "/ui/components/ImageGalleryViewer.kt"] = GOOD_GALLERY.replace(
        SHARE_HOP, "                run { sharing = false }\n")
    root = make_tree(bad)
    probs = f_problems(root)
    arms.append(("a collapsed coroutine hop still writes off-main",
                 len(probs) == 1 and "`sharing`" in probs[0]))
    _rm(root)

    # (e) the invalidation call sits in dead code — substring present, drop gone.
    bad = dict(good)
    bad[FILE_BROWSER_VM] = GOOD_FILE_BROWSER.replace(
        "                invalidateStorageSnapshot()\n",
        "                if (false) invalidateStorageSnapshot()\n")
    root = make_tree(bad)
    probs = [p for p in rule_delete_invalidation(root) if "C-INV" in p]
    arms.append(("a delete whose invalidation sits in dead code is red",
                 run(root) == 1 and any("sits in dead code" in p for p in probs)))
    _rm(root)

    print("")
    print("selftest arms:")
    ok = True
    for name, passed in arms:
        print("  %s %s" % ("PASS" if passed else "FAIL", name))
        ok = ok and passed
    if not ok:
        print("SELFTEST FAIL")
        return 1
    print(SELFTEST_MARKER)
    return 0


def _rm(root):
    import shutil
    shutil.rmtree(root, ignore_errors=True)


def main(argv):
    args = [a for a in argv[1:] if a != "--selftest"]
    if "--selftest" in argv:
        return selftest()
    root = os.path.abspath(args[0]) if args else os.path.abspath(".")
    return run(root)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
