#!/usr/bin/env python3
"""Guard for the two browser-history defects the store and its sheet carry.

A. (da1022-C) BrowserHistoryStore.save() must not iterate the live `entries`
   list outside its monitor.
   Why: save() did `for (entry in entries)` unlocked while record()/clear()
   mutate `entries` under synchronized(entries). save() is itself called from
   both of them right AFTER releasing the monitor, so a record() on another
   thread mid-iteration threw ConcurrentModificationException (swallowed by
   save()'s catch -> the write silently never lands) or persisted a torn view
   of the list. [fix/audit-s6m2] only pinned pruneOldLocked(); this guard
   extends the contract to save().

B. (deep-audit-v14 ISSUE-08) The history sheet must read the store once per
   refresh, and the bucket headers must come from the resources.
   Why: `remember(searchQuery, historyStore.getEntries().size)` called
   getEntries() — a full sort + copy — three times per recomposition (the key,
   the body, the Clear button's `enabled`), and a size-only key cannot see an
   equal-count replacement (a record() that also prunes one entry out of the
   7-day window leaves the count identical, so the list went stale). The bucket
   headers were English literals ("Today" / "Yesterday" / "Results") baked into
   the store and the sheet, which bypassed all 7 shipped locales.

--- View contract (each arm names the view it grades; see da1034-A2) --------

  * code view  = comments AND string literals blanked
                 -> arms A1, A2, B1 (code shapes; a comment or a log message
                    must never satisfy a shape)
  * literal view = comments blanked, string literals KEPT
                 -> arm B2 (the defect IS a literal; the code view cannot see
                    the difference between `"Today"` and `todayLabel`)
  * resource view = the res/values*/strings.xml files on disk
                 -> arm B3 (key parity across locales)

Arms:
  A1. save() touches the live `entries` list ONLY under `synchronized(entries)`.
      Every other reference — whatever call hangs off it — is an unlocked read.
      [batch7-t09] Aliases count: `val live = synchronized(entries) { entries }`
      hands the mutable list out of the monitor, so a later `for (x in live)`
      is the same CME — the block leaked a reference instead of a copy.
  A2. save() takes its snapshot via `synchronized(entries) { entries.toList() }`
      (loose regex: any whitespace; `toMutableList()` also accepted).
  B1. The derived `entries` map in the sheet is keyed on something that can
      tell an equal-count replacement apart (an entry identity, a refresh
      counter, or a call to a helper that reads one), and it references the
      store snapshot memo rather than reading the store in its own key.
      Fail-closed when the declaration, the memo, or both are gone.
  B1p. [wide-1024 + batch7-t09] the producer that advances that signal must
      publish a value that DIFFERS from the one it read (`{ it }`, `{ rev ->
      rev }` and the arithmetic identities `{ it + 0 }` / `{ it * 1 }` all
      fold back onto the parameter), and it must be reachable from every public
      mutator of `entries` — a definition whose call site was dropped leaves
      observers frozen while every spelling-based arm stays green.
  B2. Neither file carries an English bucket literal, and the local handed to
      `groupedByDay` must still CARRY its `stringResource(...)` read — a
      `.let { "To" + "day" }` tail replaces the localised value after the
      read, so the binding exists and the English literal still renders.
  B3. Every locale that ships the `browser_history_*` family carries all three
      bucket keys (the frozen i18n_check only WARNS about a locale that is
      missing active keys, so parity needs its own teeth).
"""
import os
import re
import sys
from pathlib import Path

# [batch7-t09] 共享 Kotlin 词法/语法/值流原语。本门上一轮自己写了
# `strip_comments_and_strings`（没有单引号 char 字面量状态机：写一处
# `val q = '"'` 就能让其后整段真代码“隐身”）、`brace_span`/`paren_span`
# （裸计数，字符串里的 `}` 会提前闭合函数体）、`statement_tail` 和
# `split_args`。外部见证各一击穿 —— 门自己再手写词法器 = 直接违规。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import KotlinLexError, mask_code, mask_comments  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_value,
    function_body as _ast_function_body,
    match_bracket,
    split_top,
    strip_parens,
    tail_expr,
)
from _kotlin_valueflow import (  # noqa: E402
    call_args,
    expr_end,
    path_feasible,
    value_sources,
)

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")

STORE_REL = "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserHistory.kt"
SHEET_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/browser/BrowserHistorySheet.kt"
RES_REL = "src/android/app/src/main/res"
BUCKET_KEYS = ("browser_history_today", "browser_history_yesterday", "browser_history_results")

MARKER = "BROWSER_HISTORY_SNAPSHOT_SELFTEST=PASS"

SNAPSHOT_RE = re.compile(
    r"synchronized\s*\(\s*entries\s*\)\s*\{\s*entries\s*\.\s*to(?:Mutable)?List\s*\(\s*\)\s*\}")
LIVE_ITER_RE = re.compile(
    r"\bfor\s*\(\s*[^)]*\bin\s+entries\s*\)|entries\s*\.\s*forEach\b|"
    r"entries\s*\[\s*\d|entries\s*\.\s*map\s*\{|entries\s*\.\s*filter\s*\{")

# A key can tell an equal-count replacement apart only if it reads an element
# identity or a refresh counter. Graded on that decision, not on a spelling:
# an inline `snapshot.firstOrNull()?.id` and `historySnapshotKey(snapshot)` are
# both accepted, the latter by resolving the helper one level down.
IDENTITY_RE = re.compile(
    r"(?i)\b[A-Za-z_][A-Za-z0-9_]*(?:tick|revision|version)[A-Za-z0-9_]*\b"
    r"|\bfirst(?:OrNull)?\s*\("
    r"|\blast(?:OrNull)?\s*\("
    r"|\bgetOrNull\s*\(\s*0\s*\)"
    r"|\.\s*id\b")

BUCKET_LITERAL_RE = re.compile(r'"(?:Today|Yesterday|Results)"')
KEY_DECL = "entries"
BUCKET_KEY_RE = re.compile(r'name="(browser_history_[A-Za-z0-9_]+)"')


def strip_comments_and_strings(text: str) -> str:
    """Structural view: comments AND string/char literal bodies blanked.

    [batch7-t09] Delegates to the shared lexer. The self-written three-state
    machine this replaced had no char-literal state at all, so one
    `val q = '"'` made every following line invisible to the gate
    (witness 02-4), and it could not handle nested block comments either.
    """
    return mask_code(text)


def strip_comments_only(text: str) -> str:
    """Literal view: comments blanked, string literals KEPT (arm B2)."""
    return mask_comments(text)


def function_body_of(text: str, name: str) -> str:
    """Body text of `fun <name>(...)` — shared matcher, block or expr body."""
    got = _ast_function_body(text, name)
    if not got:
        return ""
    start, end, _kind = got
    return text[start:end]


def function_body(text: str, decl_pattern: str):
    """Body of the `fun` whose name closes [decl_pattern] (call-site adapter).

    The body start comes from the shared primitive, which derives it from the
    matched parameter list instead of counting braces from the first `{`.
    """
    m = re.search(r"fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", decl_pattern)
    if not m:
        return ""
    return function_body_of(text, m.group(1))


FUN_DECL_RE = re.compile(
    r"\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*\(")


def function_bodies(text: str) -> dict:
    """name -> body text for every `fun name(...)` in [text].

    Block AND expression bodies come from the shared primitive, so a
    `fun f(): Int = …` helper no longer vanishes from the resolution map.
    """
    bodies = {}
    for m in FUN_DECL_RE.finditer(text):
        name = m.group(1)
        if name in bodies:
            continue
        body = function_body_of(text, name)
        if body:
            bodies[name] = body
    return bodies


def rhs_text(text: str, start: int) -> str:
    """Initializer text beginning at [start].

    Ends at a depth-0 newline (`expr_end`) *and* at a depth-0 `;`: Kotlin
    allows `;` as a statement separator (`val a = '"'; val b = entries`), and
    the old `statement_tail` swallowed the statements that followed.
    """
    end = expr_end(text, start)
    depth = 0
    for i in range(start, min(end, len(text))):
        ch = text[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth = max(0, depth - 1)
        elif ch == ";" and depth == 0:
            end = i
            break
    return text[start:end]


def paren_span(text: str, open_idx: int) -> tuple:
    """(open, close+1) for the bracket at [open_idx] — shared matcher."""
    close = match_bracket(text, open_idx)
    return (open_idx, close + 1) if close >= 0 else (open_idx, len(text))


def remember_key(sheet: str, name: str):
    """Key text of `val <name> = remember( ... )`.

    Returns the key text, or None for a keyless `remember {`, or the string
    "MISSING" when the declaration is not found at all.
    """
    m = re.search(
        r"\bval\s+" + re.escape(name) + r"\s*(?::[^=\n]*)?(?:by|=)\s*remember\s*", sheet)
    if not m:
        return "MISSING"
    tail = sheet[m.end():]
    if not tail.startswith("("):
        return None
    s, e = paren_span(tail, 0)
    return tail[s + 1:e - 1]


def snapshot_memo(sheet: str):
    """(name, keys) of `val <name> = remember(<keys>) { ... getEntries() ... }`."""
    for m in re.finditer(
            r"\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=\n]*)?=\s*remember\s*\(", sheet):
        name = m.group(1)
        tail = sheet[m.end() - 1:]
        s, e = paren_span(tail, 0)
        keys = tail[s + 1:e - 1]
        rest = tail[e:]
        nxt = rest.lstrip()
        if not nxt.startswith("{"):
            continue
        body_start = len(tail) - len(nxt)
        bclose = match_bracket(tail, body_start)
        if bclose < 0:
            continue
        if "getEntries" in tail[body_start:bclose + 1]:
            return name, keys
    return None, None


def reads_identity(expr: str, bodies: dict, depth: int = 0) -> bool:
    """Does [expr] read an element identity / refresh counter, directly or
    through one of the file-local helpers it calls?"""
    if IDENTITY_RE.search(expr):
        return True
    if depth >= 3:
        return False
    for name in set(re.findall(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(", expr)):
        body = bodies.get(name)
        if body and reads_identity(body, bodies, depth + 1):
            return True
    return False

# ── B1, reversed (dv15-B3) ────────────────────────────────────────────────
#
# The old B1 arm asked a spelling question: "does the snapshot memo's key text
# mention a tick/revision/version identifier?" (IDENTITY_RE). The defect shape
# answered it perfectly:
#
#     var historyRefreshTick by remember { mutableStateOf(0) }
#     val allEntries = remember(historyRefreshTick) { historyStore.getEntries() }
#
# ...a panel-local counter that only this sheet's own callbacks advance, so a
# record() from another browser session (or the 7-day retention prune) never
# invalidated the read. The gate called that green: the criterion pinned the
# defect as legal, same family as `mcp_oauth_lifecycle_check` in the 1035 round.
#
# B1 now judges the DECISION instead of the spelling:
#   (1) the key must NOT name panel-local state declared in this file
#       (`var x by remember { … }` / `remember { mutableStateOf(…) }`) — such a
#       signal can only be advanced from inside the sheet, which is the defect;
#   (2) the key must trace to the store: it reads `<store>.<member>` itself, or
#       names an identifier whose declaration reads one, or names a State-typed
#       parameter / a `collectAsState()`-derived local (a State the store hands
#       in — accepted by contract, and the only branch whose owner this file
#       cannot see);
#   (3) when the trace lands on `<store>.<member>`, the store file must declare
#       that member as observable state (`StateFlow` / `MutableStateFlow` /
#       `State`), so "keyed on a store constant that can never change" cannot
#       pass either.
# Accepted spellings of the same decision (all three are exercised in the
# selftest):
#     val allEntries = remember(historyStore.revision) { historyStore.getEntries() }
#     val revision by historyStore.revision.collectAsState()
#     val allEntries = remember(revision) { historyStore.getEntries() }
# Rejected:
#     var historyRefreshTick by remember { mutableStateOf(0) }; remember(historyRefreshTick) { … }
DECL_RE = re.compile(
    r"\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=\n]*)?\s*(?:by|=)\s*"
    r"([^\n]*(?:\n\s*\.[^\n]*)*)")
PANEL_LOCAL_STATE_RES = (
    r"\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=\n]*)?\s*by\s+remember\s*\{",
    r"\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=\n]*)?\s*=\s*remember(?:Saveable)?\s*"
    r"\{[^}]*\bmutable[A-Za-z]*State\w*Of\b",
    r"\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=\n]*)?\s*(?:by|=)\s*"
    r"mutable[A-Za-z]*State\w*Of\b",
)
OBSERVABLE_STATE_RE = re.compile(
    r"\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*"
    r"(?:MutableStateFlow|StateFlow|MutableState|State)\s*[<(]")

def store_receiver(text: str):
    """The store the sheet reads, taken from `X.getEntries()`."""
    m = re.search(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\.\s*getEntries\s*\(", text)
    return m.group(1) if m else None

def store_member_refs(text: str, receiver: str):
    """`<receiver>.<member>` names read inside [text]."""
    return set(re.findall(
        r"\b" + re.escape(receiver) + r"\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)", text))

def panel_local_state_names(sheet: str):
    """Identifiers this file declares as its own remember/mutableStateOf state."""
    names = set()
    for pat in PANEL_LOCAL_STATE_RES:
        names |= set(re.findall(pat, sheet))
    return names

def derived_state_names(sheet: str):
    """Locals bound to a flow/State read (`… collectAsState()`)."""
    names = set()
    for m in DECL_RE.finditer(sheet):
        if "collectAsState" in m.group(2):
            names.add(m.group(1))
    return names

def state_typed_params(sheet: str):
    """Parameters typed as Compose State — a signal the caller (the store
    owner) hands in."""
    names = set()
    for m in re.finditer(r"fun\s+[A-Za-z_][A-Za-z0-9_]*\s*\(", sheet):
        tail = sheet[m.end() - 1:]
        s, e = paren_span(tail, 0)
        for pm in re.finditer(
                r"\b([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(?:Mutable)?State\s*<", tail[s + 1:e - 1]):
            names.add(pm.group(1))
    return names

def observable_store_members(store: str):
    """Members the store file declares as observable state."""
    return set(OBSERVABLE_STATE_RE.findall(store))

def memo_key_problem(sheet: str, memo_keys: str, store: str):
    """None when the snapshot key is owned by store-provided state, else the
    reason it is not — see the B1 block comment for the criterion."""
    receiver = store_receiver(sheet)
    if receiver is None:
        return ("has no store to belong to (the sheet never calls `x.getEntries()`) "
                "-- re-point this criterion")
    ids = set(re.findall(r"\b([A-Za-z_][A-Za-z0-9_]*)\b", memo_keys))

    local = sorted(ids & panel_local_state_names(sheet))
    if local:
        return ("is panel-local state this file declares (`" + local[0] + "` via "
                "remember/mutableStateOf) -- only this sheet's own callbacks can "
                "advance it, so a record() from another session or the retention "
                "prune cannot invalidate the read")

    members = store_member_refs(memo_keys, receiver)
    via_binding = set()
    for m in DECL_RE.finditer(sheet):
        if m.group(1) in ids:
            refs = store_member_refs(m.group(2), receiver)
            if refs:
                via_binding.add(m.group(1))
                members |= refs
    direct = bool(store_member_refs(memo_keys, receiver))
    provided = ids & (derived_state_names(sheet) | state_typed_params(sheet))
    if not (direct or via_binding or provided):
        return ("does not read state the store owns -- key it on e.g. "
                "`historyStore.<revision>` or a local "
                "`val r by historyStore.<revision>.collectAsState()`")

    observable = observable_store_members(store)
    if members and not (members & observable):
        return ("reads " + ", ".join(sorted(receiver + "." + m for m in members))
                + ", but the store declares none of those as observable state "
                  "(`StateFlow`/`MutableStateFlow`/`State`) -- a key that can "
                  "never change cannot invalidate anything")
    return None


def sync_entries_spans(body: str):
    """[start, end) of every `synchronized(entries) { … }` block (braces in)."""
    spans = []
    for m in re.finditer(r"synchronized\s*\(\s*entries\s*\)\s*\{", body):
        brace = m.end() - 1
        close = match_bracket(body, brace)
        if close > 0:
            spans.append((brace, close + 1))
    return spans


def blank_spans(text: str, spans) -> str:
    """Same-length copy with every [start, end) span blanked (newlines kept so
    offsets/line numbers stay meaningful)."""
    out = list(text)
    for s, e in spans:
        for k in range(s, min(e, len(out))):
            if out[k] != "\n":
                out[k] = " "
    return "".join(out)


# ── wide-1024 A1 / batch7-t09 dataflow ────────────────────────────────────
# The old A1 arm asked a spelling question: "is there a `for (x in entries)` /
# `entries.forEach` / `entries.map {` outside the monitor?" — and the defect
# family's answer was to change the *spelling of the read*, not the read:
#
#     for (entry in entries.iterator())        # ConcurrentModificationException
#     for (entry in entries.subList(0, size))  # unlocked view of the live list
#
# A1 grades the dataflow instead: inside save(), the live list may only be
# *touched* under `synchronized(entries)`.
#
# [batch7-t09 witness 02-1] Blanking the monitor block was not enough: the
# block can LEAK the reference instead of copying it —
#
#     val liveAlias = synchronized(entries) { entries }   # not a copy
#     for (entry in liveAlias) { … }                     # unlocked iteration
#
# The read of `entries` happens inside the lock (so it was blanked away) while
# the iteration happens after the monitor is released. A1 now derives the set
# of identifiers bound to the LIVE list — `val x = entries` and
# `val x = synchronized(entries) { entries }` both hand the mutable list out,
# and bindings chain — and treats any reference to one of them outside the
# monitor as the unlocked read it is. The alias's own declaration site is not
# a read.
LIVE_SYMBOL = "entries"

BINDING_RE = re.compile(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]*?)?=(?!=)")
SYNC_CALL_RE = re.compile(r"synchronized\s*\(")


def _yields_live_list(rhs: str, live_names) -> bool:
    """Is [rhs] the live list itself (not a copy of it)?"""
    e = strip_parens(rhs.strip())
    if not e:
        return False
    m = SYNC_CALL_RE.search(e)
    if m:
        # `synchronized(entries) { <tail> }` — the block yields its tail, and a
        # tail of `entries` is the live reference, not a snapshot.
        close = match_bracket(e, m.end() - 1)
        if close < 0:
            return False
        rest = e[close + 1:].strip()
        if not rest.startswith("{"):
            return False
        bclose = match_bracket(rest, 0)
        if bclose < 0:
            return False
        e = tail_expr(rest[1:bclose]).strip()
    for src in value_sources(e):
        if strip_parens(src.strip()) in live_names:
            return True
    return False


def live_list_bindings(body: str) -> dict:
    """name -> declaration offset for identifiers bound to the LIVE list."""
    found = {}
    live = {LIVE_SYMBOL}
    for _round in range(4):  # transitive, bounded
        grew = False
        for m in BINDING_RE.finditer(body):
            name = m.group(1)
            if name in found or name in live:
                continue
            rhs = rhs_text(body, m.end())
            if _yields_live_list(rhs, live):
                found[name] = m.start(1)
                live.add(name)
                grew = True
        if not grew:
            break
    return found


def live_entries_reads(save_body: str):
    """Offsets of every reference to the live list that is NOT inside a
    `synchronized(entries)` block (the lock's own argument is exempt)."""
    spans = sync_entries_spans(save_body)
    masked = blank_spans(save_body, spans)
    # the monitor expression itself: synchronized(entries) { … }
    masked = re.sub(r"synchronized\s*\(\s*entries\s*\)",
                    lambda m: " " * len(m.group(0)), masked)
    aliases = live_list_bindings(save_body)
    hits = []
    for name in [LIVE_SYMBOL] + sorted(aliases):
        for m in re.finditer(r"(?<![\w`])" + re.escape(name) + r"(?![\w`])", masked):
            if name != LIVE_SYMBOL and aliases[name] == m.start():
                continue  # the alias's own binding site is not a read
            hits.append(m.start())
    return sorted(hits)

# ── wide-1024 B1 (producer side) ──────────────────────────────────────────
# The sheet arm proves the panel KEYS on store-owned state. It cannot see
# whether that state ever changes: a producer written `.update { it }` maps the
# old value onto itself, and StateFlow suppresses equal values — the observable
# exists, is wired, and never fires. So the producer is judged on the same
# axis: the value published must DIFFER from the one it replaced.
#
# [batch7-t09 witness 02-2] "DIFFER" cannot be spelled `^ident$` either: the
# arithmetic identities `{ it + 0 }`, `{ it * 1 }`, `{ it.plus(0) }` return the
# value they read just as surely as `{ it }` does. The body is now folded
# symbolically — the lambda parameter stays a symbol, every other subexpression
# constant-folds — and the producer is red when that fold lands back on the
# parameter.
#
# [batch7-t09 witness 02-3] The other half of "the flow is advanced" is the
# call graph: keeping `bumpRevision()` while dropping its call from `record()`
# leaves the definition (and its `.update { it + 1 }`) in the file, so every
# spelling-based arm stays green while observers are never notified. The
# producer must therefore be reachable from every public mutator of the live
# `entries` list (reachability = a call on a path `path_feasible` accepts).
FLOW_WRITE_RE = re.compile(r"\.\s*(update|emit|tryEmit|compareAndSet|value\s*=)\s*")
_IDENTITY_NUM = {"+": 0.0, "-": 0.0, "*": 1.0, "/": 1.0}
_IDENTITY_METHODS = {"plus": 0.0, "minus": 0.0, "times": 1.0, "div": 1.0}
ENTRIES_MUTATION_RE = re.compile(
    r"\bentries\s*\.\s*(?:add|addAll|clear|remove|removeAll|removeAt|set|sort|sortBy|"
    r"sortWith|retainAll|replaceAll)\b")
VIS_FUN_RE = re.compile(
    r"\b(?:(private|internal|protected|public)\s+)?fun\s+(?:<[^>]*>\s*)?"
    r"(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*\(")


def producer_value_text(text: str, pos: int) -> str:
    """Text of the value handed to a flow write starting at [pos]."""
    m = re.match(r"\s*", text[pos:])
    pos += m.end() if m else 0
    if pos < len(text) and text[pos] == "{":
        close = match_bracket(text, pos)
        if close > 0:
            return text[pos:close + 1]
    return text[pos:expr_end(text, pos)]


def _num(expr: str):
    v = const_value(expr)
    return v[1] if v and v[0] == "num" else None


def _split_binop(expr: str, op: str):
    """(left, right) for a BINARY [op] — rejects the unary-minus split."""
    parts = split_top(expr, (op,))
    if len(parts) != 2:
        return None
    left = expr[parts[0][0]:parts[0][1]].strip()
    right = expr[parts[1][0]:parts[1][1]].strip()
    if not left or not right or left[-1] in "+-*/%&|<>=!":
        return None
    return left, right


def _symbolic(expr: str, param: str):
    """('sym',) / ('num', n) / ('opaque',) — [expr] with [param] symbolic."""
    e = strip_parens(expr.strip())
    if not e:
        return ("opaque",)
    if e == param:
        return ("sym",)
    n = _num(e)
    if n is not None:
        return ("num", n)
    m = re.fullmatch(re.escape(param) + r"\s*\.\s*([A-Za-z_]\w*)\s*\(\s*(.*?)\s*\)", e)
    if m:
        want = _IDENTITY_METHODS.get(m.group(1))
        arg = _num(m.group(2))
        if want is not None and arg is not None and arg == want:
            return ("sym",)
        return ("opaque",)
    for op in ("+", "-", "*", "/"):
        sp = _split_binop(e, op)
        if sp is None:
            continue
        left = _symbolic(sp[0], param)
        right = _symbolic(sp[1], param)
        if left[0] == "num" and right[0] == "num":
            if op == "/" and right[1] == 0:
                return ("opaque",)
            val = {"+": left[1] + right[1], "-": left[1] - right[1],
                   "*": left[1] * right[1],
                   "/": (left[1] / right[1]) if right[1] else 0.0}[op]
            return ("num", val)
        want = _IDENTITY_NUM[op]
        if right == ("num", want) and left[0] == "sym":
            return ("sym",)
        if op in ("+", "*") and left == ("num", want) and right[0] == "sym":
            return ("sym",)
        return ("opaque",)
    return ("opaque",)


def is_identity_producer(value_text: str) -> bool:
    """Does the published value equal the one the producer read?

    `{ it }`, `{ rev -> rev }`, `{ it + 0 }`, `{ it * 1 }`, `{ it.plus(0) }`
    all fold back onto their parameter; `{ it + 1 }` does not.
    """
    t = value_text.strip()
    if not t.startswith("{"):
        return False
    close = match_bracket(t, 0)
    inner = t[1:close] if close > 0 else t[1:]
    parts = split_top(inner, ("->",))
    if len(parts) == 2:
        param = inner[parts[0][0]:parts[0][1]].strip()
        body = inner[parts[1][0]:parts[1][1]].strip()
    else:
        param, body = "it", inner.strip()
    if not param or not body or not re.fullmatch(r"[A-Za-z_]\w*", param):
        return False
    return _symbolic(body, param) == ("sym",)


def function_table(store: str):
    """[(visibility, name, body, body_start)] for every `fun` in [store]."""
    out, seen = [], set()
    for m in VIS_FUN_RE.finditer(store):
        name = m.group(2)
        if name in seen:
            continue
        got = _ast_function_body(store, name)
        if not got:
            continue
        start, end, _kind = got
        seen.add(name)
        out.append((m.group(1), name, store[start:end], start))
    return out


def _writes_flow(body: str, holders) -> bool:
    """A flow write on one of [holders] inside [body]."""
    for m in FLOW_WRITE_RE.finditer(body):
        recv = body[:m.start()].rstrip()[-120:]
        if not holders or any(re.search(r"\b" + re.escape(h) + r"\b", recv)
                              for h in holders):
            return True
    return False


def producer_function_names(store: str, holders):
    """Functions that advance the observable flow."""
    return {name for _vis, name, body, _pos in function_table(store)
            if _writes_flow(body, holders)}


def mutation_entry_points(store: str):
    """Public functions that mutate the live `entries` list."""
    out = []
    for vis, name, body, _pos in function_table(store):
        if vis in ("private", "internal", "protected"):
            continue
        if ENTRIES_MUTATION_RE.search(body):
            out.append((name, body))
    return out


def unreached_mutators(store: str, holders):
    """Public mutators that never advance the flow (directly or via a helper)."""
    producers = producer_function_names(store, holders)
    bad = []
    for name, body in mutation_entry_points(store):
        hit = False
        for p in sorted(producers):
            for m in re.finditer(r"\b" + re.escape(p) + r"\s*\(", body):
                if path_feasible(body, m.start()):
                    hit = True
                    break
            if hit:
                break
        if not hit:
            for m in FLOW_WRITE_RE.finditer(body):
                if path_feasible(body, m.start()):
                    hit = True
                    break
        if not hit:
            bad.append(name)
    return bad, sorted(producers)


def revision_holder_names(store: str):
    """Names that can carry the observable revision flow: the declared member
    plus every identifier named in its initializer/getter (the `_x` /
    placeholder indirection)."""
    holders = set()
    for m in OBSERVABLE_STATE_RE.finditer(store):
        name = m.group(1)
        holders.add(name)
        line_end = store.find("\n", m.end())
        tail = store[m.end():line_end if line_end > 0 else len(store)]
        holders |= set(re.findall(r"\b([A-Za-z_]\w*)\b", tail)) - {"get", "StateFlow",
                                                                  "MutableStateFlow", "State"}
    return {h for h in holders
            if h not in ("Int", "Long", "Boolean", "String", "Byte", "Short", "Unit", "value")}


def check_revision_producer(store: str):
    """The observable mutation counter must be advanced, on a reachable path,
    by a producer that publishes a DIFFERENT value."""
    problems = []
    holders = revision_holder_names(store)
    sites = []
    for m in FLOW_WRITE_RE.finditer(store):
        recv = store[:m.start()].rstrip()
        if holders and not any(re.search(r"\b" + re.escape(h) + r"\b", recv[-120:])
                               for h in holders):
            continue
        sites.append((m.group(1), producer_value_text(store, m.end())))
    if not sites:
        problems.append(
            "B1p: the observable revision flow is declared but never written -- a key on it "
            "can never change (fail-closed)")
        return problems
    for kind, value in sites:
        norm = " ".join(value.split())
        if "value" in kind and re.fullmatch(r"(?:[A-Za-z_]\w*\.)?value", norm):
            is_identity = True
        else:
            is_identity = is_identity_producer(value)
        if is_identity:
            problems.append(
                "B1p: the revision producer publishes the value it read (%r) -- StateFlow "
                "suppresses equal values, so `revision` never emits and every panel keyed "
                "on it stays frozen" % norm[:60])
            break
    bad, producers = unreached_mutators(store, holders)
    for name in bad:
        problems.append(
            "B1p: `%s()` mutates the live `entries` list but never advances the revision "
            "flow (producer%s %s is not called on any reachable path) -- the definition "
            "can stay in the file while the write site drops the call, and every panel "
            "keyed on the flow stays frozen"
            % (name,
               "" if len(producers) == 1 else "s",
               ", ".join("`%s()`" % p for p in producers) or "<none declared>"))
    return problems

DECL_ASSIGN_RE = re.compile(
    r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]*?)?(?:by|=)(?!=)")
RESOURCE_CALL_RE = re.compile(r"stringResource\s*\(\s*R\.string\.([A-Za-z0-9_]+)")
RESOURCE_TOKEN = "stringResource"


def string_resource_bindings(sheet: str):
    """Split the sheet's `stringResource` locals into (clean, overwritten).

    clean       = {local: key} — the resource read is still the value.
    overwritten = {local: key} — a `stringResource` call appears in the
                  initializer, but the value that survives is something else.

    [batch7-t09 witness 02-5] The old arm matched the PREFIX
    `val x = stringResource(R.string.k` and stopped there, so
    `stringResource(R.string.browser_history_today).let { "To" + "day" }`
    registered as a clean binding and the sheet rendered English in all 7
    locales. The binding now has to *carry* the resource: every value source
    `value_sources` resolves for the initializer must still be the same
    `stringResource(R.string.<key>)` call. `.also { log() }` keeps the
    receiver, so it stays clean; `.let { … }` / `.run { … }` / `?: "Today"`
    do not.
    """
    clean, overwritten = {}, {}
    for m in DECL_ASSIGN_RE.finditer(sheet):
        rhs = rhs_text(sheet, m.end())
        hit = RESOURCE_CALL_RE.search(rhs)
        if not hit:
            continue
        key = hit.group(1)
        survives = True
        for src in value_sources(rhs):
            s = strip_parens(src.strip())
            h2 = RESOURCE_CALL_RE.search(s)
            if not s.startswith(RESOURCE_TOKEN) or not h2 or h2.group(1) != key:
                survives = False
                break
        if survives:
            clean[m.group(1)] = key
        else:
            overwritten[m.group(1)] = key
    return clean, overwritten


def check_bucket_label_args(sheet: str):
    """B2's dataflow half: the two labels handed to `groupedByDay` must be the
    localised resources, not a literal (or a literal disguised by `"To" + "day"`),
    and the local they name must still carry the resource read."""
    problems = []
    bindings, overwritten = string_resource_bindings(sheet)
    calls = list(re.finditer(r"\bgroupedByDay\s*\(", sheet))
    if not calls:
        problems.append("B2: no `groupedByDay(` call in the sheet -- re-point this arm "
                        "(the day buckets are no longer built there)")
        return problems
    wanted = ("browser_history_today", "browser_history_yesterday")
    for m in calls:
        for idx, want in enumerate(wanted):
            spans = call_args(sheet, m.end() - 1)
            if idx >= len(spans):
                problems.append("B2: `groupedByDay` is called with fewer than two labels -- "
                                "the day buckets would fall back to whatever the store defaults to")
                break
            arg = sheet[spans[idx][0]:spans[idx][1]].strip()
            if not re.fullmatch(r"[A-Za-z_]\w*", arg):
                problems.append(
                    "B2: `groupedByDay` label #%d is the expression `%s`, not a "
                    "stringResource-bound local -- a literal (also a split one like "
                    "`\"To\" + \"day\"`) renders English in all 7 locales" % (idx + 1, arg[:60]))
            elif arg in overwritten:
                problems.append(
                    "B2: `groupedByDay` label #%d is `%s`, whose initializer reads "
                    "R.string.%s but then REPLACES that value (`…stringResource(…).let/"
                    ".run { … }` or an elvis fallback) -- the resource is bound and "
                    "immediately overwritten, so the English literal still renders"
                    % (idx + 1, arg, overwritten[arg]))
            elif arg not in bindings:
                problems.append(
                    "B2: `groupedByDay` label #%d is `%s`, which is not bound to a "
                    "browser_history_* string resource" % (idx + 1, arg))
            elif bindings[arg] != want:
                problems.append(
                    "B2: `groupedByDay` label #%d is `%s` = R.string.%s, expected R.string.%s "
                    "(the buckets would be swapped)" % (idx + 1, arg, bindings[arg], want))
    return problems

def check_arms(clean: str):
    """A1/A2 — the save() snapshot contract (code view)."""
    problems = []
    save_body = function_body_of(clean, "save")
    if not save_body:
        return ["A0: could not locate `private fun save(...)` in " + STORE_REL
                + " -- the function may have been renamed; re-point this guard"]

    # arm A1: no read of the live `entries` list outside the monitor. Graded
    # on the identifier, not on the iterating call: `.iterator()`,
    # `.subList(...)`, `entries[0]` and a plain `for (x in entries)` are the
    # same unlocked read [wide-1024 A1].
    leaks = live_entries_reads(save_body)
    if leaks:
        problems.append(
            "A1: save() reads the live `entries` list at offset "
            + str(leaks[0])
            + " outside synchronized(entries) -- record()/clear() mutate the "
              "same list under the monitor, so an unlocked read is a CME / torn "
              "persist (save() runs unlocked right after both of them)")

    # arm A2: locked shallow-copy snapshot present
    if not SNAPSHOT_RE.search(save_body):
        problems.append(
            "A2: save() does not take its snapshot via "
            "`synchronized(entries) { entries.toList() }` -- the lock must cover "
            "the copy (and only the copy; JSON building + file write stay outside)")

    return problems


def check_sheet_arms(sheet: str, bodies: dict, store: str = ""):
    """B1 — the derived list is memoized once per store-owned refresh signal.

    The memo key criterion is the reversed one: it must be owned by state the
    store provides, not by panel-local `remember` state that happens to be
    named `…tick`/`…revision` — see the B1 block comment above for the exact
    judgment and the spellings it accepts.
    """
    problems = []

    keys = remember_key(sheet, KEY_DECL)
    if keys == "MISSING":
        return ["B1: no `val " + KEY_DECL + " = remember(...)` in " + SHEET_REL
                + " -- the derived list was reworked; re-derive this criterion"]
    if keys is None:
        return ["B1: `val " + KEY_DECL + "` is keyed by a bare `remember { ... }` "
                "-- it can never observe the store changing"]

    memo_name, memo_keys = snapshot_memo(sheet)
    if memo_name is None:
        problems.append(
            "B1: no `val <snapshot> = remember(<store-owned refresh signal>) { "
            "historyStore.getEntries() }` -- the store is read inside a key instead of "
            "once per refresh, so every recomposition re-sorts the whole history")
    else:
        if memo_name not in keys:
            problems.append(
                "B1: the `" + KEY_DECL + "` key list (%s) does not reference the snapshot "
                "memo `%s`, so it is not derived from the memoized read" % (keys.strip(), memo_name))
        key_problem = memo_key_problem(sheet, memo_keys, store)
        if key_problem:
            problems.append(
                "B1: the snapshot memo `" + memo_name + "` is keyed on `%s`, which %s"
                % (memo_keys.strip(), key_problem))

    if not reads_identity(keys, bodies):
        problems.append(
            "B1: the `" + KEY_DECL + "` key list `" + keys.strip() + "` carries no element "
            "identity or refresh counter -- a replacement that leaves the count unchanged "
            "is invisible to this key")

    return problems


def check_bucket_literals(sheet_lit: str, store_lit: str):
    """B2 — no English bucket literal survives in either file (literal view)."""
    problems = []
    for rel, text in ((SHEET_REL, sheet_lit), (STORE_REL, store_lit)):
        for m in BUCKET_LITERAL_RE.finditer(text):
            problems.append(
                "B2: " + rel + " still hardcodes the English bucket header "
                + m.group(0) + " at offset " + str(m.start())
                + " -- bucket headers must come from stringResource (7 locales ship "
                  "the browser_history_* family)")
            break
    return problems


def locale_dirs(res_dir: Path):
    """Every values*/ dir that ships a strings.xml, with its definition set."""
    out = []
    if not res_dir.is_dir():
        return out
    for folder in sorted(res_dir.iterdir()):
        if not folder.is_dir() or not folder.name.startswith("values"):
            continue
        strings = folder / "strings.xml"
        if not strings.is_file():
            continue
        text = strings.read_text(encoding="utf-8", errors="replace")
        keys = set(BUCKET_KEY_RE.findall(text))
        out.append((folder.name, keys))
    return out


def check_locale_keys(root: Path):
    """B3 — key parity for the bucket family across every shipped locale."""
    problems = []
    dirs = locale_dirs(root / RES_REL)
    if not dirs:
        return ["B3: no res/values*/strings.xml found under " + str(root / RES_REL)
                + " -- the resource tree moved; re-point this guard"]
    carriers = [(name, keys) for name, keys in dirs if "browser_history_" in "".join(keys)]
    if not carriers:
        return ["B3: no locale defines any browser_history_* key -- family renamed?"]
    for name, keys in carriers:
        missing = [k for k in BUCKET_KEYS if k not in keys]
        if missing:
            problems.append(
                "B3: " + name + "/strings.xml is missing " + ", ".join(missing)
                + " -- the bucket headers must be translated in every locale that ships "
                  "the browser_history_* family (the frozen i18n_check only warns)")
    return problems


# ---------------------------------------------------------------------------
# Selftest
# ---------------------------------------------------------------------------

STORE_COMPLIANT = '''
class BrowserHistoryStore {
    private val entries = mutableListOf<Entry>()
    // [dv15-B3 fixture] the observable mutation counter the sheet keys on
    val revision: StateFlow<Int> = MutableStateFlow(0)
    private fun bumpRevision() {
        revision.update { it + 1 }
    }
    fun record(url: String, title: String) {
        synchronized(entries) { entries.add(Entry(url = url, title = title)) }
        bumpRevision()
        save()
    }
    fun groupedByDay(todayLabel: String, yesterdayLabel: String): Map<String, List<Entry>> {
        return getEntries().groupBy { entry -> browserHistoryDayLabel(entry.timestamp, 0L, todayLabel, yesterdayLabel) }
    }
    private fun save() {
        try {
            val snapshot = synchronized(entries) { entries.toList() }
            val array = JSONArray()
            for (entry in snapshot) {
                array.put(JSONObject())
            }
            file.writeText(array.toString())
        } catch (e: Exception) { }
    }
}
fun browserHistoryDayLabel(ts: Long, now: Long, todayLabel: String, yesterdayLabel: String): String {
    return if (ts == now) todayLabel else yesterdayLabel
}
'''

SHEET_COMPLIANT = '''
@Composable
fun BrowserHistorySheet(historyStore: BrowserHistoryStore, onDismiss: () -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    val todayLabel = stringResource(R.string.browser_history_today)
    val yesterdayLabel = stringResource(R.string.browser_history_yesterday)
    val resultsLabel = stringResource(R.string.browser_history_results)
    val revision by historyStore.revision.collectAsState()
    val allEntries = remember(revision) { historyStore.getEntries() }
    val entries = remember(searchQuery, todayLabel, yesterdayLabel, resultsLabel, historySnapshotKey(allEntries)) {
        if (searchQuery.isBlank()) historyStore.groupedByDay(todayLabel, yesterdayLabel)
        else mapOf(resultsLabel to historyStore.search(searchQuery))
    }
    MinisTextButton(onClick = { historyStore.clear() }, enabled = allEntries.isNotEmpty())
}
'''
# the key helper lives with the store, as it does in the real tree
STORE_KEY_HELPER = '''
internal fun historySnapshotKey(entries: List<BrowserHistoryStore.Entry>): Pair<Int, String?> =
    entries.size to entries.firstOrNull()?.id
'''

SHEET_PREFIX = '''
@Composable
fun BrowserHistorySheet(historyStore: BrowserHistoryStore, onDismiss: () -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
'''

SHEET_PREFIX_FIXED = SHEET_PREFIX + '''
    val revision by historyStore.revision.collectAsState()
    val allEntries = remember(revision) { historyStore.getEntries() }
'''

# [dv15-B3] the shape the reversed B1 must reject: the memo key is a counter
# this sheet owns, so nothing the store does on its own can advance it.
SHEET_DEFECT_TICK = SHEET_PREFIX + '''
    var historyRefreshTick by remember { mutableStateOf(0) }
    val allEntries = remember(historyRefreshTick) { historyStore.getEntries() }
    val entries = remember(searchQuery, historySnapshotKey(allEntries)) {
        mapOf("x" to historyStore.search(searchQuery))
    }
}
'''

# [dv15-B3] accepted spelling: the key reads the store member directly.
SHEET_DIRECT_STORE_KEY = SHEET_PREFIX + '''
    val allEntries = remember(historyStore.revision) { historyStore.getEntries() }
    val entries = remember(searchQuery, historySnapshotKey(allEntries)) {
        mapOf("x" to historyStore.search(searchQuery))
    }
}
'''

# [dv15-B3] near-miss: the key names a store member that is NOT observable.
SHEET_NONOBSERVABLE_KEY = SHEET_PREFIX + '''
    val allEntries = remember(historyStore.entriesVersion) { historyStore.getEntries() }
    val entries = remember(searchQuery, historySnapshotKey(allEntries)) {
        mapOf("x" to historyStore.search(searchQuery))
    }
}
'''

# the store variant that pairs with SHEET_NONOBSERVABLE_KEY: same shape, but the
# counter is a plain Int, so a key reading it can never change.
STORE_PLAIN_COUNTER = STORE_COMPLIANT.replace(
    "    val revision: StateFlow<Int> = MutableStateFlow(0)",
    "    val entriesVersion: Int = 0")

# the pre-fix shapes, quoted from the audit
SHEET_PREFIX_BROKEN = SHEET_PREFIX + '''
    val entries = remember(searchQuery, historyStore.getEntries().size) {
        if (searchQuery.isBlank()) historyStore.groupedByDay()
        else mapOf("Results" to historyStore.search(searchQuery))
    }
    MinisTextButton(onClick = { historyStore.clear() }, enabled = historyStore.getEntries().isNotEmpty())
}
'''


def _selftest_store_arms():
    cases = [
        ("A1 compliant", STORE_COMPLIANT, []),
        ("A2 snapshot removed (loop over the local)",
         STORE_COMPLIANT.replace(
             "val snapshot = synchronized(entries) { entries.toList() }\n"
             "            ", ""), ["A2"]),
        ("A3 iterates the live entries list",
         STORE_COMPLIANT.replace("for (entry in snapshot) {", "for (entry in entries) {"), ["A1"]),
        ("A4 unlocked snapshot",
         STORE_COMPLIANT.replace(
             "val snapshot = synchronized(entries) { entries.toList() }",
             "val snapshot = entries.toList()"), ["A1", "A2"]),
        ("A5 compliant variant toMutableList",
         STORE_COMPLIANT.replace(
             "val snapshot = synchronized(entries) { entries.toList() }",
             "val snapshot = synchronized(entries) { entries.toMutableList() }"), []),
    ]
    ok = True
    for name, src, want in cases:
        problems = check_arms(strip_comments_and_strings(src))
        got = sorted({p.split(":")[0] for p in problems})
        mark = "ok" if got == sorted(want) else "FAIL"
        if mark == "FAIL":
            ok = False
        print("  selftest %-46s red=%-12s expect=%-12s %s"
              % (name, got or "[]", sorted(want) or "[]", mark))
        for p in problems:
            print("      - " + p)

    problems = check_arms(strip_comments_and_strings("class BrowserHistoryStore {}"))
    if not problems:
        print("  selftest A0 no-save: expected explicit red, got GREEN -- FAIL")
        ok = False
    else:
        print("  selftest %-46s red=%-12s expect=%-12s ok"
              % ("A0 no-save", sorted({p.split(":")[0] for p in problems}), "['A0']"))
    return ok


def _selftest_sheet_arms():
    store_clean = strip_comments_and_strings(STORE_COMPLIANT + STORE_KEY_HELPER)
    plain_clean = strip_comments_and_strings(STORE_PLAIN_COUNTER + STORE_KEY_HELPER)
    bodies = function_bodies(store_clean)
    cases = [
        ("B1 compliant (store-owned signal, collected)",
         SHEET_COMPLIANT, store_clean, []),
        ("B2 compliant (key reads the store member directly)",
         SHEET_DIRECT_STORE_KEY, store_clean, []),
        ("B3 compliant (inline identity read)",
         SHEET_PREFIX_FIXED + '''
    val entries = remember(searchQuery, allEntries.size, allEntries.firstOrNull()?.id) {
        mapOf("x" to historyStore.search(searchQuery))
    }
}
''', store_clean, []),
        ("B4 pre-fix shape (store read inside the key)",
         SHEET_PREFIX_BROKEN, store_clean, ["B1"]),
        ("B5 memo present, size-only key",
         SHEET_PREFIX_FIXED + '''
    val entries = remember(searchQuery, allEntries.size) {
        mapOf("x" to historyStore.search(searchQuery))
    }
}
''', store_clean, ["B1"]),
        ("B6 memo keyed on a constant (no refresh signal)",
         SHEET_PREFIX + '''
    val allEntries = remember { historyStore.getEntries() }
    val entries = remember(searchQuery, allEntries.firstOrNull()?.id) {
        mapOf("x" to historyStore.search(searchQuery))
    }
}
''', store_clean, ["B1"]),
        ("B7 keyless derived list",
         SHEET_PREFIX_FIXED + '''
    val entries = remember {
        mapOf("x" to historyStore.search(searchQuery))
    }
}
''', store_clean, ["B1"]),
        ("B8 panel-local tick as the memo key (dv15-B3 defect)",
         SHEET_DEFECT_TICK, store_clean, ["B1"]),
        ("B9 memo keyed on a non-observable store member",
         SHEET_NONOBSERVABLE_KEY, plain_clean, ["B1"]),
    ]
    ok = True
    for name, src, store_src, want in cases:
        clean = strip_comments_and_strings(src)
        problems = check_sheet_arms(clean, bodies, store_src)
        got = sorted({p.split(":")[0] for p in problems})
        mark = "ok" if got == sorted(want) else "FAIL"
        if mark == "FAIL":
            ok = False
        print("  selftest %-46s red=%-12s expect=%-12s %s"
              % (name, got or "[]", sorted(want) or "[]", mark))
        for p in problems:
            print("      - " + p)

    problems = check_sheet_arms(
        strip_comments_and_strings("fun other() {}"), bodies, store_clean)
    if not problems:
        print("  selftest B0 no-declaration: expected explicit red, got GREEN -- FAIL")
        ok = False
    else:
        print("  selftest %-46s red=%-12s expect=%-12s ok"
              % ("B0 no-declaration", sorted({p.split(":")[0] for p in problems}), "['B1']"))
    return ok


def _selftest_literals():
    ok = True
    clean_sheet = strip_comments_only('Text(stringResource(R.string.browser_history_today))\n')
    dirty_sheet = strip_comments_only('mapOf("Results" to historyStore.search(q))\n')
    clean_store = strip_comments_only('else -> "$month/$day"\n')
    dirty_store = strip_comments_only('a -> "Today"\nb -> "Yesterday"\n')
    # a folded comment mentioning the literals must NOT trip the arm
    comment_only = strip_comments_only('// legacy: "Today" / "Yesterday"\nval x = 1\n')
    cases = [
        ("B2 literals clean", check_bucket_literals(clean_sheet, clean_store), 0),
        ("B3 sheet carries \"Results\"", check_bucket_literals(dirty_sheet, clean_store), 1),
        ("B4 store carries \"Today\"/\"Yesterday\"",
         check_bucket_literals(clean_sheet, dirty_store), 1),
        ("B5 literals only inside a comment", check_bucket_literals(comment_only, clean_store), 0),
    ]
    for name, problems, want in cases:
        got = len(problems)
        mark = "ok" if got == want else "FAIL"
        if mark == "FAIL":
            ok = False
        print("  selftest %-46s red=%-12d expect=%-12d %s" % (name, got, want, mark))
        for p in problems:
            print("      - " + p)
    return ok


def _selftest_locale_keys():
    import tempfile
    ok = True
    full = "".join('<string name="%s">x</string>' % k for k in BUCKET_KEYS)
    cases = [
        ("B3 all locales complete", {"values": full, "values-de": full}, 0),
        ("B4 one locale missing a key",
         {"values": full, "values-de": full.replace("browser_history_results", "browser_history_other")}, 1),
    ]
    for name, files, want in cases:
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp) / RES_REL
            for loc, body in files.items():
                d = base / loc
                d.mkdir(parents=True)
                (d / "strings.xml").write_text(
                    '<resources>%s<string name="browser_history_search">s</string></resources>' % body,
                    encoding="utf-8")
            problems = check_locale_keys(Path(tmp))
        got = len(problems)
        mark = "ok" if got == want else "FAIL"
        if mark == "FAIL":
            ok = False
        print("  selftest %-46s red=%-12d expect=%-12d %s" % (name, got, want, mark))
        for p in problems:
            print("      - " + p)
    with tempfile.TemporaryDirectory() as tmp:
        problems = check_locale_keys(Path(tmp))
    if not problems:
        print("  selftest B5 no-res-tree: expected explicit red, got GREEN -- FAIL")
        ok = False
    else:
        print("  selftest %-46s red=%-12d expect=%-12d ok"
              % ("B5 no-res-tree", len(problems), 1))
    return ok


def _selftest_wide_1024():
    """wide-1024 witnesses: the three shapes that used to slip through."""
    ok = True

    def report(name, problems, want_prefix):
        nonlocal ok
        tags = sorted({p.split(":")[0] for p in problems})
        good = tags == [want_prefix]
        if not good:
            ok = False
        print("  selftest %-52s red=%-14s expect=%-14s %s"
              % (name, tags or "[]", [want_prefix], "ok" if good else "FAIL"))
        for p in problems:
            print("      - " + p)

    # W-A1 (wide-1024, axis spelling): same unlocked read, different spelling.
    store_w = STORE_COMPLIANT.replace("for (entry in snapshot) {",
                                      "for (entry in entries.iterator()) {")
    report("W-A1 loop over entries.iterator()",
           check_arms(strip_comments_and_strings(store_w)), "A1")

    # W-B1p (wide-1024, axis dataflow): the producer maps the old value onto
    # itself, so the observable never emits and every keyed panel freezes.
    store_b = STORE_COMPLIANT.replace("revision.update { it + 1 }",
                                      "revision.update { it }")
    report("W-B1p producer writes the value it read",
           check_revision_producer(strip_comments_and_strings(store_b)), "B1p")

    # fail-closed: an observable declared with no producer at all.
    store_np = STORE_COMPLIANT.replace(
        "    private fun bumpRevision() {\n        revision.update { it + 1 }\n    }\n", "")
    report("W-B1p observable without a producer",
           check_revision_producer(strip_comments_and_strings(store_np)), "B1p")

    # W-B2 (wide-1024, axis spelling): the literal is split so the "Today"
    # literal arm cannot see it, and the caller drops the localised labels.
    sheet_w = SHEET_COMPLIANT.replace(
        "historyStore.groupedByDay(todayLabel, yesterdayLabel)",
        'historyStore.groupedByDay("To" + "day", "Yester" + "day")')
    report("W-B2 split literal labels",
           check_bucket_label_args(strip_comments_and_strings(sheet_w)), "B2")

    # accepted spelling: a differently-named local bound to the resource.
    sheet_alt = SHEET_COMPLIANT.replace(
        "historyStore.groupedByDay(todayLabel, yesterdayLabel)",
        "historyStore.groupedByDay(selfToday, selfYesterday)").replace(
        'val todayLabel = stringResource(R.string.browser_history_today)',
        'val selfToday = stringResource(R.string.browser_history_today)').replace(
        'val yesterdayLabel = stringResource(R.string.browser_history_yesterday)',
        'val selfYesterday = stringResource(R.string.browser_history_yesterday)')
    alt = check_bucket_label_args(strip_comments_and_strings(sheet_alt))
    if alt:
        ok = False
        print("  selftest W-B2 (localised labels under other names) must stay GREEN: %s" % alt)
    else:
        print("  selftest %-52s red=%-14s expect=%-14s ok" % ("W-B2 (labels under other names)", "[]", "[]"))

    # swapped labels are still a defect: the buckets would be mislabelled.
    sheet_swap = SHEET_COMPLIANT.replace(
        "historyStore.groupedByDay(todayLabel, yesterdayLabel)",
        "historyStore.groupedByDay(yesterdayLabel, todayLabel)")
    report("W-B2 swapped bucket labels",
           check_bucket_label_args(strip_comments_and_strings(sheet_swap)), "B2")

    # fail-closed: the day-bucket call disappeared from the sheet.
    report("W-B2 day-bucket call gone",
           check_bucket_label_args(strip_comments_and_strings("fun other() {}")), "B2")
    return ok


def _selftest_batch7():
    """batch7-t09 witnesses: the five shapes that used to slip through, plus
    the controls that must keep the gate's teeth honest."""
    ok = True
    arms = 0

    def report(name, problems, want):
        nonlocal ok, arms
        arms += 1
        tags = sorted({p.split(":")[0] for p in problems})
        good = tags == sorted(want)
        if not good:
            ok = False
        print("  selftest %-52s red=%-12s expect=%-12s %s"
              % (name, tags or "[]", sorted(want) or "[]", "ok" if good else "FAIL"))
        for p in problems:
            print("      - " + p)

    # 02-1 (axis dataflow): the monitor block LEAKS the reference instead of
    # copying it; the loop below iterates the alias outside the lock.
    store_alias = STORE_COMPLIANT.replace(
        "            val snapshot = synchronized(entries) { entries.toList() }",
        "            val snapshot = synchronized(entries) { entries.toList() }\n"
        "            val liveAlias = synchronized(entries) { entries }").replace(
        "for (entry in snapshot) {", "for (entry in liveAlias) {")
    report("W02-1 alias escapes the monitor",
           check_arms(strip_comments_and_strings(store_alias)), ["A1"])

    # 02-4 (axis parser-death): two char literals `'"'` between the read and
    # the loop; the self-written masker had no char-literal state.
    store_char = STORE_COMPLIANT.replace(
        "            val array = JSONArray()",
        "            val _q1 = '\"'; val unlocked = entries; val _q2 = '\"'\n"
        "            val array = JSONArray()").replace(
        "for (entry in snapshot) {", "for (entry in unlocked) {")
    report("W02-4 char-literal obfuscated alias",
           check_arms(strip_comments_and_strings(store_char)), ["A1"])

    # control: an alias that is only read INSIDE a monitor is not a leak.
    store_alias_in_lock = STORE_COMPLIANT.replace(
        "            val array = JSONArray()",
        "            val count = synchronized(entries) {\n"
        "                val live = entries\n"
        "                live.size\n"
        "            }\n"
        "            val array = JSONArray()")
    report("W02-1c alias read inside the monitor",
           check_arms(strip_comments_and_strings(store_alias_in_lock)), [])

    # 02-2 (axis family-hole): arithmetic identities map the old value onto
    # itself exactly as `{ it }` does.
    for label, lam in (("it + 0", "it + 0"), ("rev -> rev * 1", "rev -> rev * 1"),
                       ("it.plus(0)", "it.plus(0)")):
        store_id = STORE_COMPLIANT.replace(
            "revision.update { it + 1 }", "revision.update { %s }" % lam)
        report("W02-2 arithmetic identity {%s}" % label,
               check_revision_producer(strip_comments_and_strings(store_id)), ["B1p"])

    # 02-6 (control, already caught): explicit-parameter identity.
    store_explicit = STORE_COMPLIANT.replace(
        "revision.update { it + 1 }", "revision.update { rev -> rev }")
    report("W02-6 control: { rev -> rev }",
           check_revision_producer(strip_comments_and_strings(store_explicit)), ["B1p"])

    # control: the same shapes with a real increment stay green.
    store_inc = STORE_COMPLIANT.replace(
        "revision.update { it + 1 }", "revision.update { rev -> rev + 1 }")
    report("W02-2c real increment { rev -> rev + 1 }",
           check_revision_producer(strip_comments_and_strings(store_inc)), [])

    # 02-3 (axis completeness): the producer keeps its definition and its
    # `.update { it + 1 }`, but `record()` no longer calls it.
    store_no_call = STORE_COMPLIANT.replace(
        "        bumpRevision()\n        save()", "        save()")
    report("W02-3 mutator drops the producer call",
           check_revision_producer(strip_comments_and_strings(store_no_call)), ["B1p"])

    # control: the call is still there but only on a dead path.
    store_dead_call = STORE_COMPLIANT.replace(
        "        bumpRevision()\n        save()",
        "        if (1 == 2) bumpRevision()\n        save()")
    report("W02-3c producer call only on a dead path",
           check_revision_producer(strip_comments_and_strings(store_dead_call)), ["B1p"])

    # 02-5 (axis dataflow): the localisation read is bound and then replaced by
    # a `.let` tail, so the English literal still renders.
    sheet_let = SHEET_COMPLIANT.replace(
        "val todayLabel = stringResource(R.string.browser_history_today)",
        'val todayLabel = stringResource(R.string.browser_history_today)'
        '.let { "To" + "day" }')
    report("W02-5 label resource overwritten by .let",
           check_bucket_label_args(strip_comments_and_strings(sheet_let)), ["B2"])

    # control: a `.also` that only logs keeps the resource as the value.
    sheet_also = SHEET_COMPLIANT.replace(
        "val todayLabel = stringResource(R.string.browser_history_today)",
        "val todayLabel = stringResource(R.string.browser_history_today)"
        ".also { log(it) }")
    report("W02-5c .also keeps the resource",
           check_bucket_label_args(strip_comments_and_strings(sheet_also)), [])

    return ok, arms


# arms exercised by the selftest families above (store 6, sheet 10, literals 4,
# locale keys 3, wide-1024 7); `_selftest_batch7` adds its own count.
BASE_ARMS = 30


def run_selftest():
    """(all arms green?, arms executed)."""
    ok = True
    ok = _selftest_store_arms() and ok
    ok = _selftest_sheet_arms() and ok
    ok = _selftest_literals() and ok
    ok = _selftest_locale_keys() and ok
    ok = _selftest_wide_1024() and ok
    batch7_ok, batch7_arms = _selftest_batch7()
    ok = batch7_ok and ok
    return ok, BASE_ARMS + batch7_arms


def main() -> int:
    if "--selftest" in sys.argv:
        print("browser_history_snapshot_check selftest:")
        ok, arms = run_selftest()
        if not ok:
            print("FAIL: selftest")
            return 1
        print("%s (%d arms)" % (MARKER, arms))
        return 0

    problems = []
    store_path = ROOT / STORE_REL
    sheet_path = ROOT / SHEET_REL
    if not store_path.exists():
        print(f"FAIL: missing {STORE_REL}")
        return 1
    if not sheet_path.exists():
        print(f"FAIL: missing {SHEET_REL}")
        return 1

    store_raw = store_path.read_text(encoding="utf-8", errors="replace")
    sheet_raw = sheet_path.read_text(encoding="utf-8", errors="replace")
    try:
        store_code = strip_comments_and_strings(store_raw)
        sheet_code = strip_comments_and_strings(sheet_raw)
    except KotlinLexError as exc:
        # fail-closed: an unlexable source is a violation, never a reason to
        # fall back to raw-text scanning (the string contents would then be
        # graded as code).
        print("FAIL: browser history store/sheet contracts")
        print("  - A0: the lexer refused the source (%s) -- structure cannot be "
              "established, so the snapshot contract is unverified "
              "(fail-closed)" % exc)
        return 1

    problems += check_arms(store_code)
    problems += check_revision_producer(store_code)
    problems += check_sheet_arms(sheet_code, function_bodies(store_code + sheet_code), store_code)
    problems += check_bucket_label_args(sheet_code)
    problems += check_bucket_literals(strip_comments_only(sheet_raw), strip_comments_only(store_raw))
    problems += check_locale_keys(ROOT)

    if problems:
        print("FAIL: browser history store/sheet contracts")
        for p in problems:
            print(f"  - {p}")
        return 1
    print("OK: BrowserHistoryStore.save() snapshots entries inside "
          "synchronized(entries) and never iterates the live list outside "
          "the monitor (JSON build + file write stay outside the lock); "
          "BrowserHistorySheet reads the store once per refresh and keys its "
          "derived list on a refresh signal the STORE owns (a panel-local "
          "tick/revision counter is rejected), with the bucket headers "
          "localized in all locales that ship the family")
    return 0


if __name__ == "__main__":
    sys.exit(main())
