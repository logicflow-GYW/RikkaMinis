#!/usr/bin/env python3
"""da1022-F + dv25-B1 gate: MirrorSpeedTestViewModel cross-thread snapshot
writes, main-thread IO, speed-test auto-select wiring, and first-boot
auto-detection ownership.

Usage (repo root, positional arg supported):
    python3 scripts/scan/mirror_vm_thread_check.py [ROOT]

PASS  = exit 0, prints MIRROR_VM_THREAD_CHECK=PASS
        (--selftest prints MIRROR_VM_THREAD_SELFTEST=PASS (N arms))
FAIL  = exit 1, prints violating arm descriptions

Judged invariants (deep-audit-1022 ISSUE-06 + deep-audit-1045 B-01 + batch-7
witness wave; every anchor is a CODE anchor on MirrorSettingsScreen.kt — the
source is read through the shared `mask_code()` projection, so a call that only
survives inside a line comment / block comment / string literal cannot satisfy
or trip a criterion):
  1. ensureLoaded: EVERY write of the SnapshotStateMaps
     (selectedMirrorId/useCustomMirror) sits inside a
     `Snapshot.withMutableSnapshot { }` transaction — the function is entered
     from background threads (boot path) and the batch must land atomically.
  2. autoDetectOnceIfNeeded: `applyMirror(` must NOT sit inside any
     `withContext(Dispatchers.Main)` block (it is disk IO), and every call site
     must be REACHABLE (no `if (false)`, no `for (x in y.take(0))`), and the
     list that drives the apply loop must be filled (`toApply += …`) on the
     settlement path — a loop over an always-empty list writes nothing.
  3. speed-test auto-select: the shared test body writes
     `useCustomMirror[cat] = true` and `persist(context, cat)`, calls
     `applyMirror(` outside the Main block, and the applied list it iterates is
     appended to inside the settlement block (otherwise the conclusion is
     persisted and never applied).
  4. retryApkWorldWithMirrorFallback: EVERY write of the two ALPINE
     snapshot-state entries sits inside a `Snapshot.withMutableSnapshot { }`
     transaction.
  5. [dv25-B1] the auto-detect settlement preserves a user selection: the loop
     that writes `selectedMirrorId[...] =` must reject categories that already
     have a selection (inline, or through a local decision function on the
     settlement path), and the value carried into that decision must DERIVE
     from the prior-selection read (`selectedMirrorId.keys.toSet()`, not
     `…let { emptySet() }` / `emptySet().also { … }`).
  6. [dv25-B1] the auto-detect coroutine owns a cancellable slot: its launch is
     assigned to a `@Volatile` field, and both user entry points
     (runAllTests / runTest) cancel that field.
  7. [dv25-B1] the settlement is ownership-checked before it writes: the Main
     block holding the settlement tests the owning slot (or liveness), the
     check's condition is FEASIBLE (folds to neither `true` nor `false`), and
     its guarded branch actually leaves the block.
"""
import re
import sys
import tempfile
from pathlib import Path

from _kotlin_ast import (
    const_bool,
    function_body,
    match_bracket,
    split_top,
)
from _kotlin_lexer import KotlinLexError
from _kotlin_valueflow import (
    code_view,
    contains_ident,
    expr_end,
    guard_feasible,
    peel_discarding,
    scope_calls,
    value_sources,
)

REL_SCREEN = "src/android/app/src/main/java/com/rikkaminis/app/ui/sandbox/MirrorSettingsScreen.kt"

VIOLATIONS = []

# Texts that mark "this decision reads a prior user selection". A *write*
# (`selectedMirrorId[cat] = …`) must not count — that is the settlement
# itself, and counting it made every unguarded loop look guarded.
PRIOR_SELECTION_RE = re.compile(
    r"\balreadySelected\b"
    r"|\bhasPriorSelection\s*\("
    r"|selectedMirrorId\[\w+\]\s*!=\s*null"
    r"|selectedMirrorId\[\w+\]\s*==\s*null"
    r"|selectedMirrorId\.keys"
)
# Texts that mark "and it rejects those entries".
REJECTION_RE = re.compile(r"\bcontinue\b|\breturn@\w+|\breturn\b")
# `map[key] =` — a state write (the `(?!=)` keeps `==` out).
WRITE_RE = re.compile(r"\b(selectedMirrorId|useCustomMirror)\s*\[([^\]\n]*)\]\s*=(?!=)")
# Scope calls that MUTATE their receiver — used to accept the legitimate
# `mutableSetOf().apply { addAll(selectedMirrorId.keys) }` rebuild.
MUTATING_CALL_RE = re.compile(r"(?:\.\s*)?(?:addAll|add|putAll|put|plusAssign)\s*\(")
# Iterables that can never yield an element.
EMPTY_ITER_RE = re.compile(r"\.\s*(?:take|takeLast|takeWhile)\s*\(\s*(?:0|false)\s*\)")
EMPTY_LITERAL_RE = re.compile(
    r"^(?:emptyList|emptySet|emptyMap|emptySequence|sequenceOf|listOf|setOf|mapOf)"
    r"\s*(?:<[^>]*>)?\s*\(\s*\)$")
LOOP_RE = re.compile(r"\b(for|while)\s*\(")


# ── shared-primitive adapters ─────────────────────────────────────────────
# Everything below reads the `mask_code()` projection of the file (comments and
# string/char literal interiors blanked, length preserved), so a `}` inside a
# string cannot truncate a body and an `applyMirror` inside a line comment is
# not a call. A file the lexer cannot read is a violation (fail-closed).

def fn_body(src: str, name: str):
    """Body text of `fun <name>(...)` — the braces' contents, or the
    expression of an expression-bodied function."""
    fb = function_body(src, name)
    if fb is None:
        return None
    return src[fb[0]:fb[1]]


def fn_slice(src: str, name: str):
    """Whole declaration (`fun …` through the end of the body)."""
    fb = function_body(src, name)
    if fb is None:
        return None
    head = None
    for m in re.finditer(
            r"\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?" + re.escape(name) + r"\s*\(",
            src[: fb[0]]):
        head = m
    return src[(head.start() if head else 0): fb[1]]


def body_extent(text: str, i: int):
    """(start, end) of the statement/block that starts at/after `i`."""
    n = len(text)
    while i < n and text[i] in " \t\r\n":
        i += 1
    if i >= n:
        return (i, i)
    if text[i] == "{":
        j = match_bracket(text, i)
        return (i + 1, j) if j > 0 else (i, n)
    depth = 0
    j = i
    while j < n:
        ch = text[j]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                return (i, j)
            depth -= 1
        elif ch == "\n" and depth == 0:
            return (i, j)
        j += 1
    return (i, n)


def loops(text: str):
    """[(kind, head, head_pos, body_start, body_end)] for every for/while.

    `head` is the text inside the parentheses, `head_pos` the position of the
    `for`/`while` keyword — position matters for order criteria.
    """
    out = []
    for m in LOOP_RE.finditer(text):
        p = m.end() - 1
        close = match_bracket(text, p)
        if close < 0:
            continue
        b_start, b_end = body_extent(text, close + 1)
        out.append((m.group(1), text[p + 1:close], m.start(), b_start, b_end))
    return out


def call_blocks(text: str, needle: str):
    """Spans of the trailing lambda of every `<needle> { … }` call."""
    out = []
    i = 0
    while True:
        i = text.find(needle, i)
        if i < 0:
            return out
        b = text.find("{", i + len(needle))
        if b < 0:
            return out
        e = match_bracket(text, b)
        if e < 0:
            return out
        out.append((b + 1, e))
        i = e


def main_blocks(body: str):
    """Spans of every `withContext(Dispatchers.Main) { … }` block."""
    return call_blocks(body, "withContext(Dispatchers.Main)")


def in_spans(pos: int, spans) -> bool:
    return any(a <= pos < b for a, b in spans)


def iterable_dead(expr: str) -> bool:
    """An iterable that can never yield an element."""
    e = expr.strip()
    if not e:
        return False
    if const_bool(e) is False:
        return True
    if EMPTY_ITER_RE.search(e):
        return True
    if EMPTY_LITERAL_RE.match(e):
        return True
    return False


def unreachable_reason(text: str, pos: int) -> str:
    """Why the statement at `pos` can never run, or '' when it is reachable.

    Walks the enclosing `if`/`when` conditions (constant folding via
    `guard_feasible`) and the enclosing `for`/`while` heads (empty iterable /
    false condition). This is the "effect" replacement for the old regex
    whitelist: `if (false && g)`, `if (g && false)`, `if (!true)`, `if (1 == 2)`,
    `while (false)` and `for (x in y.take(0))` all fold to unreachable.
    """
    for kind, cond in _enclosing_if_conditions(text, pos):
        if kind == "if" and guard_feasible(cond) is False:
            return "if (" + cond.strip()[:70] + ")"
    for kind, head, _hp, b_start, b_end in loops(text):
        if not (b_start <= pos < b_end):
            continue
        if kind == "while":
            if const_bool(head) is False:
                return "while (" + head.strip()[:70] + ")"
            continue
        parts = split_top(head, (" in ",))
        if len(parts) != 2:
            continue
        iterable = head[parts[1][0]:parts[1][1]]
        if iterable_dead(iterable):
            return "for (%s)" % head.strip()[:70]
    return ""


def _enclosing_if_conditions(text: str, pos: int):
    """[(kind, cond)] of the if/when constructs covering `pos` (else negated)."""
    from _kotlin_ast import enclosing_conditions

    return enclosing_conditions(text, pos)


def dead_context(text: str, pos: int) -> str:
    """Head of the construct that makes `pos` unreachable, or ''."""
    return unreachable_reason(text, pos)


def local_calls(text: str):
    """Function-call names in `text` (candidates for a decision hop)."""
    return set(re.findall(r"([A-Za-z_][A-Za-z0-9_]*)\s*\(", text))


def launch_slot(body: str):
    """The field a launch's Job is published to, plus the local it goes through.

    Two spellings are accepted, both used in this file:
      - `slot = <scope>.launch { ... }`                  -> (slot, None)
      - `val job = <scope>.launch { ... }; slot = job`     -> (slot, "job")
    Returns (None, None) when the coroutine is never published to a field —
    that is the shipped defect ("bare scope.launch", cancelling the inner run
    leaves the settlement alive). A local `val` alone is *not* an ownership
    slot: it cannot be cancelled from another entry point.
    """
    for m in re.finditer(r"([A-Za-z_]\w*)\s*=\s*[\w.]*launch\b", body):
        line_start = body.rfind("\n", 0, m.start()) + 1
        prefix = body[line_start:m.start()]
        lhs = m.group(1)
        if re.search(r"\b(?:val|var)\s+$", prefix):
            pub = re.search(
                r"(?m)^\s*([A-Za-z_]\w*)\s*=\s*" + re.escape(lhs) + r"\b", body[m.end():]
            )
            if pub:
                return pub.group(1), lhs
            continue
        return lhs, None
    return None, None


def has_prior_selection_marker(text: str) -> bool:
    return PRIOR_SELECTION_RE.search(text) is not None


def has_rejection(text: str) -> bool:
    return REJECTION_RE.search(text) is not None


def strip_signature(slice_text: str) -> str:
    """Drop the `fun name(...)` head (and its parameter list) from a function
    slice. Declaring a parameter called `alreadySelected` is not evidence that
    the decision *reads* a prior selection — only its body is."""
    p = slice_text.find("(")
    if p < 0:
        return slice_text
    close = match_bracket(slice_text, p)
    return slice_text[close + 1:] if close >= 0 else slice_text


def settlement_guard_ok(src: str, body: str) -> str:
    """Return '' when the auto-detect settlement rejects already-selected
    categories, else a reason string. Accepts an inline guard or a decision
    function reachable from the loop's iterable (two hops max). Only *reads* of
    a prior selection count — the settlement's own writes must not satisfy it.
    """
    found = [(kind, head, pos, b_start, b_end) for (kind, head, pos, b_start, b_end) in loops(body)
             if kind == "for" and re.search(r"selectedMirrorId\[\w+\]\s*=", body[b_start:b_end])]
    if not found:
        return "no settlement loop writes selectedMirrorId[...]"
    reasons = []
    for _kind, header, _pos, b_start, b_end in found:
        block = body[b_start:b_end]
        # inline guard: the loop body itself consults the selection and skips
        if has_prior_selection_marker(block) and has_rejection(block):
            return ""
        hop1 = {}
        for n in local_calls(header):
            sl = fn_slice(src, n)
            if sl:
                hop1[n] = strip_signature(sl)
        # single hop: the decision itself reads and rejects
        if any(has_prior_selection_marker(t) and has_rejection(t) for t in hop1.values()):
            return ""
        # delegation: hop1 reads the selection, hop2 rejects against it
        for name, text in hop1.items():
            if not has_prior_selection_marker(text):
                continue
            for n2 in local_calls(text) - {name}:
                sl2 = fn_slice(src, n2)
                if not sl2:
                    continue
                b2 = strip_signature(sl2)
                if has_rejection(b2) and has_prior_selection_marker(b2):
                    return ""
        reasons.append(
            "settlement loop over `%s` does not skip already-selected categories"
            % (header.strip()[:60],)
        )
    return "; ".join(reasons) if reasons else "settlement guard missing"


def _rebuilds_from_selection(expr: str) -> bool:
    """`mutableSetOf().apply { addAll(selectedMirrorId.keys) }` — the receiver
    is mutated with the prior selection, so the carried value DOES derive from
    it even though `value_sources` (correctly) reports the receiver."""
    for name, _start, open_pos, end in scope_calls(expr):
        if name not in ("apply", "also"):
            continue
        lam = expr[open_pos + 1:end - 1]
        if MUTATING_CALL_RE.search(lam) and contains_ident(lam, "selectedMirrorId"):
            return True
    return False


def _outside_braces(expr: str) -> str:
    """`expr` with every `{ … }` interior blanked.

    A lambda body that happens to mention `selectedMirrorId` deep inside
    (`scope.launch { … settlement … }`) is not a prior-selection READ at the
    binding's own value level — without this filter the coroutine builder was
    reported as "the prior selection is lost" and the real offender
    (`alreadySelected`) never got named.
    """
    chars = list(expr)
    depth = 0
    for i, ch in enumerate(expr):
        if ch == "{":
            depth += 1
            chars[i] = " "
        elif ch == "}":
            depth = max(0, depth - 1)
            chars[i] = " "
        elif depth:
            chars[i] = " "
    return "".join(chars)


def prior_selection_value_problem(detect: str) -> str:
    """Every binding that reads `selectedMirrorId` as the prior-selection set
    must CARRY that read. The old version asked "is the read inside
    `.also`/`.apply`?" — `selectedMirrorId.keys.toSet().let { emptySet() }`
    walks straight through it. Now the question is what the expression
    evaluates to: `value_sources()` peels discard-style scopes (value ==
    receiver) and follows value-style scopes (value == lambda tail), so a read
    whose result is replaced by an unrelated expression is RED.
    """
    found = False
    for m in re.finditer(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]*)?=\s*", detect):
        rhs_start = m.end()
        expr = detect[rhs_start:expr_end(detect, rhs_start)].strip()
        if not contains_ident(_outside_braces(expr), "selectedMirrorId"):
            # a receiver rebuild (`mutableSetOf().apply { addAll(<selection>) }`)
            # reads the selection inside the lambda but DOES carry it
            if _rebuilds_from_selection(expr):
                found = True
            continue
        found = True
        sources = value_sources(expr)
        if any(contains_ident(s, "selectedMirrorId") for s in sources):
            continue
        if _rebuilds_from_selection(expr):
            continue
        return ("`%s` reads selectedMirrorId but the value it carries is `%s`, not the "
                "prior selection (the read is discarded by a scope call)"
                % (m.group(1), sources[0].strip()[:60]))
    if not found:
        return ("no local carries `selectedMirrorId` into the settlement decision "
                "(fail-closed) — prior selection cannot be honoured")
    return ""


def apply_wiring_problem(text: str) -> str:
    """`for (cat in applied) applyMirror(…)` must iterate a list the settlement
    actually filled. A gate that only greps for `applyMirror(` passes while the
    loop walks an always-empty list: the fastest mirror is selected in memory,
    persisted, and never written to the container config.
    """
    for kind, head, head_pos, b_start, b_end in loops(text):
        if kind != "for":
            continue
        if "applyMirror(" not in text[b_start:b_end]:
            continue
        parts = split_top(head, (" in ",))
        if len(parts) != 2:
            continue
        iterable = head[parts[1][0]:parts[1][1]].strip()
        if not re.match(r"^[A-Za-z_]\w*$", iterable):
            continue  # not a local collection (e.g. MirrorCategory.entries)
        name = iterable
        appends = [m.start() for m in re.finditer(
            r"(?<![\w.])" + re.escape(name) + r"\s*\+=(?!=)"
            r"|(?<![\w.])" + re.escape(name) + r"\s*\.\s*(?:add|addAll|addElement)\s*\(",
            text)]
        if not appends:
            return ("`for (… in %s) applyMirror(…)` iterates a list that is never filled — "
                    "the auto-selected mirror is persisted but never written to the container "
                    "config (add `%s += cat` inside the settlement block)" % (name, name))
        if min(appends) > head_pos:
            return ("`%s` is only filled after the apply loop that consumes it — the loop "
                    "iterates the empty list on the settlement path" % name)
    return ""


def snapshot_atomic(fn_text: str, label: str, want) -> None:
    """EVERY mirror state-map write in `fn_text` must sit inside the
    `Snapshot.withMutableSnapshot { … }` transaction, and the transaction must
    cover the named writes. `want` = [(map_name, key_substring_or_None)].

    Asking "does the block contain both names?" is how the batch-7 witness got
    through: two dummy reads inside the block plus the real writes right below
    it kept every token the old arm looked for.
    """
    spans = call_blocks(fn_text, "Snapshot.withMutableSnapshot")
    if not spans:
        fail(REL_SCREEN,
             "%s state-map writes are not inside Snapshot.withMutableSnapshot" % label)
        return
    start, end = spans[0]
    writes = list(WRITE_RE.finditer(fn_text))
    if not writes:
        fail(REL_SCREEN, "%s never writes the mirror state maps — the settlement is dead" % label)
        return
    outside = [m for m in writes if not (start <= m.start() < end)]
    if outside:
        m = outside[0]
        fail(REL_SCREEN,
             "%s writes the state maps outside Snapshot.withMutableSnapshot (`%s`) — the batch "
             "must land atomically; this function runs off the main thread"
             % (label, m.group(0).strip()[:60]))
        return
    for map_name, key in want:
        ok = any(m.group(1) == map_name and (key is None or key in m.group(2))
                 for m in writes)
        if not ok:
            what = "%s[%s]" % (map_name, key) if key else map_name
            fail(REL_SCREEN, "%s snapshot block does not cover the %s write" % (label, what))


def fail(*parts):
    VIOLATIONS.append(": ".join(str(x) for x in parts))


def check(root: Path) -> None:
    p = root / REL_SCREEN
    if not p.exists():
        fail(f"{REL_SCREEN}: missing source file")
        return
    try:
        src = code_view(p.read_text(encoding="utf-8"))
    except KotlinLexError as exc:
        # fail-closed: an unlexable file must not be scanned as raw text, or a
        # string literal could restore the dead code the projection removed.
        fail(REL_SCREEN, "cannot lex the Kotlin source (%s) — fail-closed" % exc)
        return

    # --- arm 1: ensureLoaded wraps its map writes in a mutable snapshot ---
    body = fn_body(src, "ensureLoaded")
    if body is None:
        fail(REL_SCREEN, "ensureLoaded function not found")
    else:
        snapshot_atomic(body, "ensureLoaded",
                        [("selectedMirrorId", None), ("useCustomMirror", None)])

    # --- arm 2: autoDetectOnceIfNeeded keeps applyMirror off the main thread ---
    detect = fn_body(src, "autoDetectOnceIfNeeded")
    if detect is None:
        fail(REL_SCREEN, "autoDetectOnceIfNeeded function not found")
    else:
        sites = [m.start() for m in re.finditer(r"\bapplyMirror\s*\(", detect)]
        if not sites:
            fail(REL_SCREEN, "autoDetectOnceIfNeeded does not call applyMirror")
        else:
            mains = main_blocks(detect)
            for pos in sites:
                if in_spans(pos, mains):
                    fail(REL_SCREEN,
                         "autoDetectOnceIfNeeded calls applyMirror inside "
                         "withContext(Dispatchers.Main) — disk IO on the main thread")
                    break
            for pos in sites:
                dead = dead_context(detect, pos)
                if dead:
                    fail(REL_SCREEN,
                         "autoDetectOnceIfNeeded calls applyMirror under a constant-false "
                         "construct (`%s`) — the auto-selected mirror is never written to the "
                         "container config" % dead[:70])
                    break
            problem = apply_wiring_problem(detect)
            if problem:
                fail(REL_SCREEN, "autoDetectOnceIfNeeded: " + problem)

    # --- arm 3: speed-test auto-select writes useCustomMirror + persists + applies ---
    entry = fn_body(src, "runAllTests")
    if entry is None:
        fail(REL_SCREEN, "runAllTests function not found")
    else:
        # [dv25-B1] the body may live in the private shared body
        # (startAllTests) so the public entry point can stay free of the
        # auto-detect preemption it must not perform on the boot path.
        shared = fn_body(src, "startAllTests") or ""
        tail = entry + shared
        if shared:
            if "startAllTests(" not in entry:
                fail(REL_SCREEN,
                     "runAllTests does not reach the shared speed-test body (startAllTests)")
        if "useCustomMirror[cat] = true" not in tail:
            fail(REL_SCREEN,
                 "runAllTests auto-select does not set useCustomMirror[cat] = true "
                 "(speed-test conclusion never takes effect)")
        if "persist(context, cat)" not in tail:
            fail(REL_SCREEN, "runAllTests auto-select does not persist the selection")
        sites = [m.start() for m in re.finditer(r"\bapplyMirror\s*\(", tail)]
        if not sites:
            fail(REL_SCREEN, "runAllTests never applies the auto-selected mirror")
        else:
            mains = main_blocks(tail)
            for pos in sites:
                if in_spans(pos, mains):
                    fail(REL_SCREEN,
                         "runAllTests calls applyMirror inside withContext(Dispatchers.Main) "
                         "— disk IO on the main thread")
                    break
            # the collected list must actually be filled: dropping
            # `autoApplied += cat` leaves the apply loop walking an empty list
            # while every token this arm used to grep for stays in place.
            problem = apply_wiring_problem(tail)
            if problem:
                fail(REL_SCREEN, "runAllTests: " + problem)

    # --- arm 4: retryApkWorld wraps the ALPINE writes in a mutable snapshot ---
    body = fn_body(src, "retryApkWorldWithMirrorFallback")
    if body is None:
        fail(REL_SCREEN, "retryApkWorldWithMirrorFallback function not found")
    else:
        snapshot_atomic(body, "retryApkWorldWithMirrorFallback",
                        [("selectedMirrorId", "ALPINE"), ("useCustomMirror", "ALPINE")])

    # --- arm 5: the settlement preserves a user selection ---
    if detect is not None:
        reason = settlement_guard_ok(src, detect)
        if reason:
            fail(REL_SCREEN,
                 f"autoDetectOnceIfNeeded: {reason} — a first-boot settlement would overwrite "
                 "the mirror the user picked by hand")
        value_problem = prior_selection_value_problem(detect)
        if value_problem:
            fail(REL_SCREEN,
                 f"autoDetectOnceIfNeeded: prior selection lost — {value_problem}; the "
                 "'prior selection wins' contract is not met")

        # --- arm 6: the detector owns a cancellable slot the user entry points cancel ---
        slot, local = launch_slot(detect)
        if slot is None:
            fail(REL_SCREEN,
                 "autoDetectOnceIfNeeded launches with no ownership slot (bare scope.launch) "
                 "— cancelling the inner run leaves the settlement alive")
        else:
            if not re.search(r"@Volatile\s+private\s+var\s+" + re.escape(slot) + r"\b", src):
                fail(REL_SCREEN, f"the auto-detect job slot `{slot}` is not a @Volatile field")
            if local is not None and f"{local}.start()" not in detect:
                fail(REL_SCREEN,
                     f"the auto-detect job local `{local}` is never started "
                     "(lazy launch without start() never runs)")
            for name, label in (("runAllTests", "runAllTests"), ("runTest", "runTest")):
                b = fn_body(src, name)
                if b is None:
                    fail(REL_SCREEN, f"{label} function not found")
                    continue
                if f"{slot}?.cancel()" not in b:
                    fail(REL_SCREEN,
                         f"{label} does not cancel the auto-detect job (`{slot}?.cancel()`) — "
                         "a user test leaves the boot settlement running")

            # --- arm 7: settlement is ownership-checked ---
            settlement = None
            for a, b in main_blocks(detect):
                if re.search(r"selectedMirrorId\[\w+\]\s*=", detect[a:b]):
                    settlement = detect[a:b]
                    break
            if settlement is None:
                fail(REL_SCREEN,
                     "autoDetectOnceIfNeeded has no main-thread settlement block writing "
                     "selectedMirrorId[...]")
            else:
                live = None
                for gm in re.finditer(r"\bif\s*\(", settlement):
                    close = match_bracket(settlement, gm.end() - 1)
                    if close < 0:
                        continue
                    cond = settlement[gm.end():close]
                    if contains_ident(cond, slot) or "isActive" in cond:
                        live = (cond.strip(), gm.start(), close)
                        break
                if live is None:
                    fail(REL_SCREEN,
                         f"autoDetectOnceIfNeeded settles without an ownership check "
                         f"(`{slot} !== self` / isActive)")
                elif guard_feasible(live[0]) is False:
                    # both spellings of the dead conjunct: `false && g` (which
                    # short-circuits to false) and `g && false`.
                    fail(REL_SCREEN,
                         "autoDetectOnceIfNeeded's ownership check `if (%s)` can never fire "
                         "(constant-false conjunct) — a superseded detector still settles over "
                         "the mirror the user just picked" % live[0][:70])
                else:
                    then_span = body_extent(settlement, live[2] + 1)
                    branch = settlement[then_span[0]:then_span[1]]
                    if not re.search(r"\b(?:return|throw)\b", branch):
                        fail(REL_SCREEN,
                             "autoDetectOnceIfNeeded's ownership check `if (%s)` does not "
                             "leave the settlement block (no return/throw) — the guard decides "
                             "nothing" % live[0][:70])
                    first_write = re.search(r"selectedMirrorId\[\w+\]\s*=", settlement)
                    if first_write and live[1] > first_write.start():
                        fail(REL_SCREEN,
                             "autoDetectOnceIfNeeded checks ownership AFTER it already wrote "
                             "the settlement — the guard must run before the writes")


# ---------------- selftest fixtures (minimal synthetic repo) ----------------

FIXTURE_SCREEN = '''package com.rikkaminis.app.ui.sandbox

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

object MirrorSpeedTestViewModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var currentJob: Job? = null
    @Volatile private var autoDetectJob: Job? = null
    val results: SnapshotStateMap<String, List<String>> = mutableStateMapOf()
    val selectedMirrorId: SnapshotStateMap<String, String> = mutableStateMapOf()
    val useCustomMirror: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    @Volatile private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = prefs(context)
            Snapshot.withMutableSnapshot {
                for (cat in MirrorCategory.entries) {
                    val savedUrl = p.getString("mirror.selected.${cat.key}", null)
                    if (savedUrl != null) selectedMirrorId[cat] = entry.id
                    useCustomMirror[cat] = p.getBoolean("mirror.useCustom.${cat.key}", false)
                }
            }
            loaded = true
        }
    }

    fun runAllTests(context: Context) {
        autoDetectJob?.cancel()
        startAllTests(context)
    }

    private fun startAllTests(context: Context) {
        currentJob?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            val autoApplied = mutableListOf<MirrorCategory>()
            withContext(Dispatchers.Main) {
                if (currentJob !== self) return@withContext
                results.clear()
                results.putAll(sorted)
                for ((cat, fastest) in autoSelectFastest(sorted) { selectedMirrorId[it] != null }) {
                    selectedMirrorId[cat] = fastest.mirror.id
                    useCustomMirror[cat] = true
                    persist(context, cat)
                    autoApplied += cat
                }
            }
            for (cat in autoApplied) applyMirror(context, cat)
        }
        currentJob = job
        job.start()
    }

    fun runTest(context: Context, category: MirrorCategory) {
        autoDetectJob?.cancel()
        currentJob?.cancel()
        currentJob = scope.launch {
            val self = coroutineContext[Job]
            val autoApplied = mutableListOf<MirrorCategory>()
            withContext(Dispatchers.Main) {
                results[category] = sorted
                for ((cat, fastest) in autoSelectFastest(mapOf(category to sorted)) { selectedMirrorId[it] != null }) {
                    selectedMirrorId[cat] = fastest.mirror.id
                    useCustomMirror[cat] = true
                    persist(context, cat)
                    autoApplied += cat
                }
            }
            for (cat in autoApplied) applyMirror(context, cat)
        }
    }

    fun autoDetectOnceIfNeeded(context: Context) {
        ensureLoaded(context)
        autoDetectJob?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            startAllTests(context)
            currentJob?.join()
            val toApply = mutableListOf<MirrorCategory>()
            withContext(Dispatchers.Main) {
                if (autoDetectJob !== self) return@withContext
                val alreadySelected = selectedMirrorId.keys.toSet()
                for ((cat, fastest) in decideAutoApply(results.toMap(), alreadySelected)) {
                    selectedMirrorId[cat] = fastest.mirror.id
                    useCustomMirror[cat] = true
                    persist(context, cat)
                    toApply += cat
                }
            }
            for (cat in toApply) applyMirror(context, cat)
        }
        autoDetectJob = job
        job.start()
    }

    suspend fun retryApkWorldWithMirrorFallback(
        context: Context,
        attemptOnce: suspend () -> Boolean,
    ): Boolean {
        ensureLoaded(context)
        var ok = attemptOnce()
        if (ok) return true
        Snapshot.withMutableSnapshot {
            selectedMirrorId[MirrorCategory.ALPINE] = next.id
            useCustomMirror[MirrorCategory.ALPINE] = true
        }
        persist(context, MirrorCategory.ALPINE)
        applyMirror(context, MirrorCategory.ALPINE)
        return ok
    }
}

internal fun autoSelectFastest(
    sorted: Map<MirrorCategory, List<MirrorTestResult>>,
    hasPriorSelection: (MirrorCategory) -> Boolean,
): List<Pair<MirrorCategory, MirrorTestResult>> =
    MirrorCategory.entries.mapNotNull { cat ->
        if (hasPriorSelection(cat)) return@mapNotNull null
        sorted[cat]?.firstOrNull { it.isSuccess }?.let { cat to it }
    }

internal fun decideAutoApply(
    results: Map<MirrorCategory, List<MirrorTestResult>>,
    alreadySelected: Set<MirrorCategory>,
): List<Pair<MirrorCategory, MirrorTestResult>> =
    autoSelectFastest(results) { it in alreadySelected }
        .filterNot { (_, fastest) -> fastest.mirror.isOfficial }
'''


def write_fixture(root: Path, screen: str) -> None:
    p = root / REL_SCREEN
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(screen, encoding="utf-8")


def mutate(fixture: str, old: str, new: str, label: str) -> str:
    """Exact single-occurrence replacement; a miss is a selftest failure, not a
    silently skipped arm."""
    if fixture.count(old) != 1:
        sys.exit(f"MUTATION ANCHOR BROKEN ({label}): expected exactly 1 occurrence, got {fixture.count(old)}")
    return fixture.replace(old, new)


def baseline_fixture() -> str:
    """The shipped (pre-dv25-B1) shape: bare `scope.launch`, no ownership slot,
    and a settlement that walks every category unconditionally. Arms 1-4 must
    stay green on it so the two new arms own the red."""
    f = FIXTURE_SCREEN
    f = mutate(f, "    fun runAllTests(context: Context) {\n        autoDetectJob?.cancel()\n",
               "    fun runAllTests(context: Context) {\n", "baseline-runAllTests")
    f = mutate(f, "        autoDetectJob?.cancel()\n        val job = scope.launch(start = CoroutineStart.LAZY) {\n            val self = coroutineContext[Job]\n            startAllTests(context)",
               "        scope.launch(start = CoroutineStart.LAZY) {\n            val self = coroutineContext[Job]\n            runAllTests(context)", "baseline-launch")
    f = mutate(f, "            for (cat in toApply) applyMirror(context, cat)\n        }\n        autoDetectJob = job\n        job.start()\n    }",
               "            for (cat in toApply) applyMirror(context, cat)\n        }\n    }", "baseline-tail")
    f = mutate(f, "                if (autoDetectJob !== self) return@withContext\n                val alreadySelected = selectedMirrorId.keys.toSet()\n                for ((cat, fastest) in decideAutoApply(results.toMap(), alreadySelected)) {",
               "                for (cat in MirrorCategory.entries) {\n                    val fastest = results[cat]?.firstOrNull { it.isSuccess } ?: continue\n                    if (fastest.mirror.isOfficial) continue",
               "baseline-settlement")
    return f


def selftest() -> int:
    ok = True
    arms = 0
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)

        def run(label: str, screen: str, expect: tuple):
            nonlocal ok, arms
            arms += 1
            write_fixture(root, screen)
            VIOLATIONS.clear()
            check(root)
            got = tuple(sorted(VIOLATIONS))
            if len(expect) and not all(any(x in v for v in VIOLATIONS) for x in expect):
                ok = False
                print(f"SELFTEST {label}: expected red on {expect}, got {got}")
            elif len(expect) == 0 and VIOLATIONS:
                ok = False
                print(f"SELFTEST {label}: expected GREEN, got {got}")
            else:
                print(f"SELFTEST {label}: {'RED as expected' if expect else 'GREEN'}")

        # A: the shipped post-fix shape must pass every arm
        run("A (compliant)", FIXTURE_SCREEN, ())

        # V1: drop useCustomMirror write from the auto-select -> arm 3 red
        run("V1 (drop useCustomMirror)", mutate(
            FIXTURE_SCREEN,
            "                    useCustomMirror[cat] = true\n                    persist(context, cat)\n                    autoApplied += cat\n                }\n            }\n            for (cat in autoApplied) applyMirror(context, cat)\n        }\n        currentJob = job",
            "                    persist(context, cat)\n                    autoApplied += cat\n                }\n            }\n            for (cat in autoApplied) applyMirror(context, cat)\n        }\n        currentJob = job", "V1"),
            ("useCustomMirror[cat] = true",))

        # V2: put applyMirror back inside the auto-detection Main block -> arm 2 red
        run("V2 (applyMirror in Main)", mutate(
            FIXTURE_SCREEN,
            "                    toApply += cat\n                }",
            "                    toApply += cat\n                    applyMirror(context, cat)\n                }", "V2"),
            ("autoDetectOnceIfNeeded calls applyMirror inside",))

        # V3: remove the snapshot transaction from ensureLoaded -> arm 1 red
        run("V3 (ensureLoaded unwrapped)", mutate(
            FIXTURE_SCREEN,
            "Snapshot.withMutableSnapshot {\n                for (cat in MirrorCategory.entries)",
            "for (cat in MirrorCategory.entries)", "V3"),
            ("ensureLoaded",))

        # V4: remove the snapshot transaction from retryApkWorld -> arm 4 red
        run("V4 (retryApkWorld unwrapped)", mutate(
            FIXTURE_SCREEN,
            "Snapshot.withMutableSnapshot {\n            selectedMirrorId[MirrorCategory.ALPINE] = next.id\n            useCustomMirror[MirrorCategory.ALPINE] = true\n        }",
            "selectedMirrorId[MirrorCategory.ALPINE] = next.id\n        useCustomMirror[MirrorCategory.ALPINE] = true", "V4"),
            ("retryApkWorldWithMirrorFallback",))

        # V5: the settlement decision stops consulting the user selection -> arm 5 red
        run("V5 (guard dropped)", mutate(
            FIXTURE_SCREEN,
            "autoSelectFastest(results) { it in alreadySelected }",
            "autoSelectFastest(results) { false }", "V5"),
            ("does not skip already-selected categories",))

        # V5b: the rejection is dropped from the decision hop -> arm 5 red
        run("V5b (rejection dropped)", mutate(
            FIXTURE_SCREEN,
            "MirrorCategory.entries.mapNotNull { cat ->\n        if (hasPriorSelection(cat)) return@mapNotNull null\n        sorted[cat]?.firstOrNull { it.isSuccess }?.let { cat to it }\n    }",
            "MirrorCategory.entries.mapNotNull { cat ->\n        sorted[cat]?.firstOrNull { it.isSuccess }?.let { cat to it }\n    }", "V5b"),
            ("does not skip already-selected categories",))

        # V6: back to a bare scope.launch (no ownership slot) -> arm 6 red
        bare = mutate(
            FIXTURE_SCREEN,
            "        autoDetectJob?.cancel()\n        val job = scope.launch(start = CoroutineStart.LAZY) {\n            val self = coroutineContext[Job]\n            startAllTests(context)",
            "        scope.launch(start = CoroutineStart.LAZY) {\n            val self = coroutineContext[Job]\n            startAllTests(context)", "V6a")
        bare = mutate(
            bare,
            "            for (cat in toApply) applyMirror(context, cat)\n        }\n        autoDetectJob = job\n        job.start()\n    }",
            "            for (cat in toApply) applyMirror(context, cat)\n        }\n    }", "V6b")
        run("V6 (bare launch)", bare, ("no ownership slot",))

        # V7: the single-category entry point stops cancelling detection -> arm 6 red
        run("V7 (runTest forgets cancel)", mutate(
            FIXTURE_SCREEN,
            "    fun runTest(context: Context, category: MirrorCategory) {\n        autoDetectJob?.cancel()\n",
            "    fun runTest(context: Context, category: MirrorCategory) {\n", "V7"),
            ("runTest does not cancel the auto-detect job",))

        # V8: the settlement loses its ownership check -> arm 7 red
        run("V8 (ownership check dropped)", mutate(
            FIXTURE_SCREEN,
            "                if (autoDetectJob !== self) return@withContext\n",
            "", "V8"),
            ("settles without an ownership check",))

        # V9: the shipped baseline shape -> arms 5+6 red, arms 1-4 stay green
        run("V9 (baseline shape)", baseline_fixture(),
            ("no ownership slot", "does not skip already-selected categories"))

        # V10: the slot is not volatile -> arm 6 red
        run("V10 (slot not volatile)", mutate(
            FIXTURE_SCREEN,
            "    @Volatile private var autoDetectJob: Job? = null",
            "    private var autoDetectJob: Job? = null", "V10"),
            ("is not a @Volatile field",))

        # ---- wide-1024 witnesses -------------------------------------------
        # W11: the transaction still contains both map names (dummy reads) but
        # the real writes moved BELOW it, onto the background thread.
        run("W11 (ensureLoaded writes outside the snapshot)", mutate(
            FIXTURE_SCREEN,
            "            Snapshot.withMutableSnapshot {\n"
            "                for (cat in MirrorCategory.entries) {\n"
            "                    val savedUrl = p.getString(\"mirror.selected.${cat.key}\", null)\n"
            "                    if (savedUrl != null) selectedMirrorId[cat] = entry.id\n"
            "                    useCustomMirror[cat] = p.getBoolean(\"mirror.useCustom.${cat.key}\", false)\n"
            "                }\n"
            "            }",
            "            Snapshot.withMutableSnapshot {\n"
            "                val cat = MirrorCategory.ALPINE\n"
            "                val dummy = selectedMirrorId[cat] ?: useCustomMirror[cat]\n"
            "            }\n"
            "            for (cat in MirrorCategory.entries) {\n"
            "                val savedUrl = p.getString(\"mirror.selected.${cat.key}\", null)\n"
            "                if (savedUrl != null) selectedMirrorId[cat] = entry.id\n"
            "                useCustomMirror[cat] = p.getBoolean(\"mirror.useCustom.${cat.key}\", false)\n"
            "            }", "W11"),
            ("outside Snapshot.withMutableSnapshot",))

        # W12: the apply call survives verbatim but can never run.
        run("W12 (applyMirror behind if (false))", mutate(
            FIXTURE_SCREEN,
            "            for (cat in toApply) applyMirror(context, cat)",
            "            if (false) { for (cat in toApply) applyMirror(context, cat) }", "W12"),
            ("constant-false",))

        # W13: the ownership check and the prior-selection set both survive as
        # tokens and both became no-ops (`&& false`, `emptySet().also { … }`).
        run("W13 (guard negated + empty prior set)", mutate(
            FIXTURE_SCREEN,
            "                if (autoDetectJob !== self) return@withContext\n"
            "                val alreadySelected = selectedMirrorId.keys.toSet()",
            "                if (autoDetectJob === self && false) return@withContext\n"
            "                val alreadySelected = emptySet<MirrorCategory>().also { selectedMirrorId.keys }",
            "W13"),
            ("constant-false conjunct", "prior selection"))

        # W14: an equivalent, differently spelled live guard must stay GREEN —
        # the arm grades "can the check fire", not one spelling of it.
        run("W14 (live guard spelled via isActive)", mutate(
            FIXTURE_SCREEN,
            "                if (autoDetectJob !== self) return@withContext",
            "                if (!self.isActive) return@withContext", "W14"),
            ())

        # ---- batch-7 witnesses (11-1 … 11-5) ------------------------------
        # Each one keeps the token the old spelling-based criterion grepped for
        # and removes the effect behind it.

        # 11-1 [parser-death] the call survives only in a TRAILING line comment.
        # The old code_only() only dropped lines that START with `//`, so
        # `for (cat in toApply) Unit // applyMirror(…)` kept the token.
        run("N1 (applyMirror only inside a trailing comment)", mutate(
            FIXTURE_SCREEN,
            "            for (cat in toApply) applyMirror(context, cat)",
            "            for (cat in toApply) Unit // applyMirror(context, cat)", "N1"),
            ("does not call applyMirror",))

        # 11-1b [parser-death] the call survives only inside a raw string.
        run("N1b (applyMirror only inside a raw string)", mutate(
            FIXTURE_SCREEN,
            "            for (cat in toApply) applyMirror(context, cat)",
            '            val note = """applyMirror(context, cat)"""', "N1b"),
            ("does not call applyMirror",))

        # 11-2 [dataflow] the transaction keeps both names (as dummy READS) and
        # the real writes move below it, onto the background thread.
        run("N2 (retryApkWorld writes outside the snapshot)", mutate(
            FIXTURE_SCREEN,
            "        Snapshot.withMutableSnapshot {\n"
            "            selectedMirrorId[MirrorCategory.ALPINE] = next.id\n"
            "            useCustomMirror[MirrorCategory.ALPINE] = true\n"
            "        }",
            "        Snapshot.withMutableSnapshot {\n"
            "            val _unusedA = selectedMirrorId[MirrorCategory.ALPINE]\n"
            "            val _unusedB = useCustomMirror[MirrorCategory.ALPINE]\n"
            "        }\n"
            "        selectedMirrorId[MirrorCategory.ALPINE] = next.id\n"
            "        useCustomMirror[MirrorCategory.ALPINE] = true", "N2"),
            ("retryApkWorldWithMirrorFallback", "outside Snapshot.withMutableSnapshot"))

        # 11-3 [family-hole] the dead conjunct moves to the LEFT of `&&` — the
        # mirror image of the `g && false` shape W13 already covers.
        run("N3 (ownership check `false && guard`)", mutate(
            FIXTURE_SCREEN,
            "                if (autoDetectJob !== self) return@withContext",
            "                if (false && autoDetectJob !== self) return@withContext", "N3"),
            ("constant-false conjunct",))

        # 11-4 [dataflow] the prior-selection read is discarded by `.let`, whose
        # value (not the receiver) is what the settlement carries.
        run("N4 (prior selection discarded by .let)", mutate(
            FIXTURE_SCREEN,
            "                val alreadySelected = selectedMirrorId.keys.toSet()",
            "                val alreadySelected = selectedMirrorId.keys.toSet().let { emptySet<MirrorCategory>() }",
            "N4"),
            ("prior selection",))

        # 11-5 [dataflow] every token of the auto-select stays, but the link
        # between the settlement block and the apply loop is cut.
        run("N5 (autoApplied never filled)", mutate(
            FIXTURE_SCREEN,
            "                for ((cat, fastest) in autoSelectFastest(sorted) { selectedMirrorId[it] != null }) {\n"
            "                    selectedMirrorId[cat] = fastest.mirror.id\n"
            "                    useCustomMirror[cat] = true\n"
            "                    persist(context, cat)\n"
            "                    autoApplied += cat\n"
            "                }\n"
            "            }\n"
            "            for (cat in autoApplied) applyMirror(context, cat)",
            "                    selectedMirrorId[cat] = fastest.mirror.id\n"
            "                    useCustomMirror[cat] = true\n"
            "                    persist(context, cat)\n"
            "                }\n"
            "            }\n"
            "            for (cat in autoApplied) applyMirror(context, cat)", "N5"),
            ("never filled",))

        # 11-6 (already caught before this wave, must STAY caught): the loop
        # head is constant-false via `.take(0)` — braced and braceless.
        run("N6 (applyMirror behind for (x in y.take(0)))", mutate(
            FIXTURE_SCREEN,
            "            for (cat in toApply) applyMirror(context, cat)",
            "            for (cat in toApply.take(0)) { applyMirror(context, cat) }", "N6"),
            ("constant-false",))
        run("N6b (braceless .take(0) loop)", mutate(
            FIXTURE_SCREEN,
            "            for (cat in toApply) applyMirror(context, cat)",
            "            for (cat in toApply.take(0)) applyMirror(context, cat)", "N6b"),
            ("constant-false",))

        # ---- precision arms: legitimate shapes must stay GREEN --------------
        # N7: rebuilding the set by mutating a receiver with the selection.
        run("N7 (prior selection rebuilt via apply { addAll })", mutate(
            FIXTURE_SCREEN,
            "                val alreadySelected = selectedMirrorId.keys.toSet()",
            "                val alreadySelected = mutableSetOf<MirrorCategory>().apply { addAll(selectedMirrorId.keys) }",
            "N7"),
            ())
        # N8: the applied list filled with `.add(…)` instead of `+=`.
        run("N8 (applied list filled with .add)", mutate(
            FIXTURE_SCREEN,
            "                for ((cat, fastest) in autoSelectFastest(sorted) { selectedMirrorId[it] != null }) {\n"
            "                    selectedMirrorId[cat] = fastest.mirror.id\n"
            "                    useCustomMirror[cat] = true\n"
            "                    persist(context, cat)\n"
            "                    autoApplied += cat\n"
            "                }\n"
            "            }\n"
            "            for (cat in autoApplied) applyMirror(context, cat)",
            "                for ((cat, fastest) in autoSelectFastest(sorted) { selectedMirrorId[it] != null }) {\n"
            "                    selectedMirrorId[cat] = fastest.mirror.id\n"
            "                    useCustomMirror[cat] = true\n"
            "                    persist(context, cat)\n"
            "                    autoApplied.add(cat)\n"
            "                }\n"
            "            }\n"
            "            for (cat in autoApplied) applyMirror(context, cat)", "N8"),
            ())
        # N9: a `.take(0)` guard on something else must not red the apply site.
        run("N9 (take(0) on an unrelated loop)", mutate(
            FIXTURE_SCREEN,
            "            val toApply = mutableListOf<MirrorCategory>()",
            "            for (unused in emptyList<MirrorCategory>()) persist(context, unused)\n"
            "            val toApply = mutableListOf<MirrorCategory>()", "N9"),
            ())


    if not ok:
        sys.exit("MIRROR_VM_THREAD_SELFTEST=FAIL")
    return arms


def main() -> None:
    args = sys.argv[1:]
    if "--selftest" in args:
        arms = selftest()
        print(f"MIRROR_VM_THREAD_SELFTEST=PASS ({arms} arms)")
        return
    root = Path(args[0]) if args else Path.cwd()
    if not root.exists():
        sys.exit(f"FAIL: root {root} does not exist")
    check(root)
    if VIOLATIONS:
        print(f"FAIL: {len(VIOLATIONS)} violation(s)")
        for v in VIOLATIONS:
            print(f"  {v}")
        sys.exit(1)
    print("MIRROR_VM_THREAD_CHECK=PASS")


if __name__ == "__main__":
    main()
