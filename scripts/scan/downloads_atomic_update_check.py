#!/usr/bin/env python3
"""[deep-audit-1016c FIX-C + meta-audit-1024 wide sweep] Gate: `_downloads` writes
must be atomic **and** the atomic API must actually be used atomically.

ISSUE-03C — the four write sites in BrowserTabPool.kt were non-atomic
read-modify-writes on a `MutableStateFlow`:

    _downloads.value = listOf(entry) + _downloads.value   (registerDownload)
    _downloads.value = _downloads.value.map { … }          (updateDownloadEntry)
    _downloads.value = _downloads.value.map { … }          (markDownloadsSeen)
    _downloads.value = _downloads.value.filter { … }       (clearFinishedDownloads)

Writers span three threads (URL-download listener on Main, the blob
download bridge on the WebView JavaBridge thread, download coroutines on IO),
so a lost update silently drops a download row from the panel or regresses a
progress/settle state. They now use `MutableStateFlow.update { }` (CAS loop).

Rules (BrowserTabPool.kt, where `_downloads` is private):

  R1  no read-modify-write: a write whose right-hand side mentions the current
      value again (`_downloads.value`, or the receiver's own `value`) →
      violation.  Covers the explicit form and the implicit-receiver forms
      routed through R4.
  R2  no full-replace write at all, unless the same line carries an explicit
      `ALLOWED-FULL-REPLACE` marker (a conscious opt-out for a write that
      genuinely cannot race, e.g. installing a value that does not depend on
      the current one).
  R3  [wide-1024 B-22] a `_downloads.update { … }` lambda must consume the
      current value the CAS loop hands it. Calling `update` is not the same
      as updating atomically: if the lambda ignores its parameter and maps a
      snapshot captured earlier (`val snap = _downloads.value` → `update {
      snap.map { … } }`), every CAS retry recomputes from the stale snapshot
      and the concurrent write is still lost.  Red when: the body reads
      `_downloads.value`; or it references a pre-captured `val x =
      _downloads.value` binding; or an explicit parameter is never used at the
      body's top level.
    R5  [wide3-08 R2] the shared-flow entrances are the same write:
      `_downloads.tryEmit(x)` / `.emit(x)` on a MutableStateFlow replace the
      value outright (no CAS), and `getAndSet(x)` does the same. Red when the
      handed-over value is derived from the current state, or when the write
      carries no `ALLOWED-FULL-REPLACE` marker.
  R6  [wide3-08 parser-death] every brace/paren scan is string- AND
      block-comment-aware (`_mask`): `/* } */` inside a block used to close a
      `_downloads.apply { … }` span early and drop the real write out of it.
      A reference that only sits in a dead half (`if (false) current else …`,
      `false && current…`) does not count as consuming the CAS parameter.

R4  [wide-1024 B-22] the write surface is the *receiver*, not the spelling
      `_downloads.value =`. A write routed through `apply` / `run` / `with`
      (bare `value =`) or `let` / `also` (`it.value =`) is the same mutation
      and must satisfy R1/R2.  Previously the gate only matched the literal
      `_downloads.value =`, so `_downloads.apply { value = … }` slipped past
      every rule.

Exit 0 = PASS, 1 = FAIL (prints file:line for each violation).
`--selftest` runs the fixtures below: the compliant one must stay clean and
every witness-shaped one must red the *matching* rule.

Interface: `python3 scripts/scan/downloads_atomic_update_check.py [REPO_ROOT]`
(defaults to cwd; also accepts `--root ROOT`). Registration into
scripts/scan/scan.sh is the main session's job (this branch is forbidden from
touching scan.sh / test_scan.py).
"""
import os
import re
import sys

REL = "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserTabPool.kt"

RECV = "_downloads"
WRITE_RE = re.compile(r"_downloads\s*\.\s*value\s*=(?!=)")          # explicit
# [wide3-08 R2] the write surface is the RECEIVER, not one spelling of it.
# `MutableStateFlow` inherits `tryEmit` / `emit` from `MutableSharedFlow`; on a
# StateFlow both replace the current value outright, exactly like
# `_downloads.value = x` (the CAS loop is bypassed). `getAndSet` does the same.
EMIT_RE = re.compile(r"\b_downloads\s*\.\s*(tryEmit|emit|getAndSet)\s*\(")
RECV_BLOCK = re.compile(r"\b_downloads\s*\.\s*(apply|run|let|also)\s*\{")
WITH_BLOCK = re.compile(r"\bwith\s*\(\s*_downloads\s*\)\s*\{")
UPDATE_RE = re.compile(r"\b_downloads\s*\.\s*update\s*\{")
# CAS entrances that hand the lambda the current value (R3 applies to each).
CAS_LAMBDA_RE = re.compile(r"\b_downloads\s*\.\s*(?:update|updateAndGet|getAndUpdate)\s*\{")
VALUE_WRITE_THIS = re.compile(r"(?<![\w.])(?:this\s*\.\s*)?value\s*=(?!=)")  # apply/run/with
VALUE_WRITE_IT = re.compile(r"(?<![\w.])it\s*\.\s*value\s*=(?!=)")            # let/also
MARKER = "ALLOWED-FULL-REPLACE"

# ── [wide3-08 R3] read aliases ──────────────────────────────────────────────
# `val downloads: StateFlow<List<DownloadEntry>> = _downloads.asStateFlow()`
# exposes the SAME state under another name. A snapshot taken through it
# (`val stale = downloads.value`) is the same stale read as `_downloads.value`,
# so the criterion has to derive the alias set instead of spelling the private
# field. Derived (not hard-coded) so renaming the alias cannot disarm it.
READ_ALIAS_BIND = re.compile(
    r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=\s*_downloads\s*\.\s*"
    r"(?:asStateFlow|asSharedFlow|asFlow)\s*\(")

def _read_receivers(src):
    """`_downloads` plus every alias of its read-only view."""
    names = ["_downloads"]
    for m in READ_ALIAS_BIND.finditer(src):
        if m.group(1) not in names:
            names.append(m.group(1))
    return names

def _reads_state(expr, receivers):
    """True when `expr` reads the CURRENT download state (raw `.value`)."""
    for r in receivers:
        if re.search(r"(?<![\w.])%s\s*\.\s*value\b" % re.escape(r), expr):
            return True
    return False


# ── source helpers ──────────────────────────────────────────────────────────
def _mask(src):
    """Length-preserving blank of string/char contents, `//` comments and
    `/* ... */` BLOCK comments.

    The brace matcher used to skip only `//` comments, so a `/* } */` inside a
    block closed the scan at the fake brace and everything after it dropped out
    of the judged span - `_downloads.apply { /* } */ value = value.filter { ... } }`
    read as an empty block and every rule went green (wide3-08 R4,
    parser-death). Blanking (instead of deleting) keeps every index aligned with
    the original text, so the rules can still slice `src`.
    """
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            for k in range(i + 3, max(i + 3, j - 3)):
                if out[k] != "\n":
                    out[k] = "x"
            i = j
            continue
        if src[i] in ('"', "'"):
            q = src[i]
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] in (q, "\n"):
                    j += 1
                    break
                j += 1
            for k in range(i + 1, max(i + 1, j - 1)):
                if out[k] != "\n":
                    out[k] = "x"
            i = j
            continue
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
            continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if out[k] != "\n":
                    out[k] = " "
            i = j
            continue
        i += 1
    return "".join(out)

def _match_paren(src, open_idx):
    """Index of the `)` matching the `(` at open_idx (-1 if none)."""
    mask = _mask(src)
    depth = 0
    for i in range(open_idx, len(mask)):
        if mask[i] == "(":
            depth += 1
        elif mask[i] == ")":
            depth -= 1
            if depth == 0:
                return i
    return -1

def _call_args(src, open_idx):
    """Text between the parens whose `(` sits at open_idx (None if unbalanced)."""
    end = _match_paren(src, open_idx)
    return None if end < 0 else src[open_idx + 1:end]

def _split_top(s, op):
    """Split at depth-0 occurrences of `op` (strings/comments masked)."""
    mask = _mask(s)
    parts, depth, start, i = [], 0, 0, 0
    while i < len(mask):
        c = mask[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and mask.startswith(op, i):
            parts.append(s[start:i])
            i += len(op)
            start = i
            continue
        i += 1
    parts.append(s[start:])
    return parts

def _unparen(p):
    p = p.strip()
    while p.startswith("(") and p.endswith(")") and _match_paren(p, 0) == len(p) - 1:
        p = p[1:-1].strip()
    return p

def _lit_value(part):
    """True/False when `part` is a bare boolean literal (optionally negated or
    parenthesised), None otherwise."""
    p = _unparen(part)
    neg = False
    while p.startswith("!"):
        neg = not neg
        p = _unparen(p[1:])
    if p == "true":
        v = True
    elif p == "false":
        v = False
    else:
        return None
    return (not v) if neg else v

def _truth(cond):
    """True/False when a condition is DECIDED by a literal, None otherwise.

    `if (true || sid == null)` is decided (always true): keeping the null test
    in the spelling while the outcome is fixed is exactly the wide3-08 shape.
    """
    c = _unparen(cond)
    if _lit_value(c) is not None:
        return _lit_value(c)
    for part in _split_top(c, "||"):
        if _lit_value(part) is True:
            return True
    for part in _split_top(c, "&&"):
        if _lit_value(part) is False:
            return False
    return None

def _split_else(rest):
    """`then` / `else` halves of an `if (...) A else B` expression."""
    mask = _mask(rest)
    depth = 0
    i = 0
    while i < len(mask):
        c = mask[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and re.match(r"\belse\b", mask[i:]):
            return rest[:i], rest[i + 4:]
        i += 1
    return rest, ""

def _live_text(expr):
    """The part of `expr` that can actually be evaluated at runtime.

    A reference that only lives in a dead half is not a use:
      `if (false) current else listOf(e) + stale` -> `current` is NOT consumed
      `false && current.map { ... }`              -> same
    This is the wide3-08 shape: keep the spelling of the use, delete the
    decision that reaches it.
    """
    e = expr.strip()
    m = re.match(r"if\s*\(", e)
    if m:
        mp = _match_paren(e, m.end() - 1)
        if mp < 0:
            return e
        cond = e[m.end():mp]
        then_part, else_part = _split_else(e[mp + 1:])
        tv = _truth(cond)
        if tv is True:
            return cond + " " + _live_text(then_part)
        if tv is False:
            return cond + " " + _live_text(else_part)
        return cond + " " + _live_text(then_part) + " " + _live_text(else_part)
    for op, dead in (("||", True), ("&&", False)):
        parts = _split_top(e, op)
        if len(parts) > 1:
            for i, part in enumerate(parts):
                if _lit_value(part) is dead:
                    # Kotlin short-circuits: everything AFTER this literal is
                    # never evaluated, so a reference there is not a use.
                    return " ".join(_live_text(p) for p in parts[:i + 1])
    return e

def _split_statements(body):
    """Top-level statements of a block body (`;` or depth-0 newline ends one)."""
    mask = _mask(body)
    parts, depth, start = [], 0, 0
    for i, c in enumerate(mask):
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and (c == ";" or c == "\n"):
            parts.append(body[start:i])
            start = i + 1
    parts.append(body[start:])
    return parts

def _live_body(body):
    """`_live_text` over every top-level statement of a block body."""
    return "\n".join(_live_text(s) for s in _split_statements(body) if s.strip())

def _balanced(src, open_idx):
    """Return (body_start, body_end) of the `{ ... }` block opening at open_idx."""
    mask = _mask(src)
    depth = 0
    for i in range(open_idx, len(mask)):
        if mask[i] == "{":
            depth += 1
        elif mask[i] == "}":
            depth -= 1
            if depth == 0:
                return open_idx + 1, i
    return None

def _strip_nested(body):
    """Blank out nested `{ ... }` blocks (strings/comments too) so only the
    top-level statements remain. Length-preserving, so an offset found in the
    result is still valid in `body`."""
    mask = _mask(body)
    out = list(mask)
    i, n = 0, len(mask)
    while i < n:
        if mask[i] == "{":
            span = _balanced(body, i)
            if span is None:
                return None
            for k in range(i, span[1] + 1):
                if out[k] != "\n":
                    out[k] = " "
            i = span[1] + 1
            continue
        i += 1
    return "".join(out)

def _line_of(src, idx):
    return src.count("\n", 0, idx) + 1


def _statement_tail(src, idx):
    """Text from idx to end of the statement (newline at depth 0 or `;`)."""
    depth = 0
    i = idx
    n = len(src)
    while i < n:
        ch = src[i]
        if ch in '([{':
            depth += 1
        elif ch in ')]}':
            depth -= 1
            if depth < 0:
                break
        elif ch == ';' and depth == 0:
            break
        elif ch == '\n' and depth == 0:
            break
        i += 1
    return src[idx:i]


FUN_HEAD = re.compile(r"^\s{0,4}(?:private\s+|internal\s+|public\s+|protected\s+)?"
                      r"(?:suspend\s+)?fun\s", re.M)
SNAP_BIND = re.compile(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=\s*([^\n]*)")


def _snapshot_bindings(src, receivers=None):
    """`val x = <expr reading the download state>` bindings, with their
    enclosing-function span.

    The read set is derived (`_downloads` + every `asStateFlow()` alias), so
    `val snap = downloads.value` is the same stale read as
    `val snap = _downloads.value` (wide3-08 R3).

    Returns [(name, start, scope_end, bind_line)]. A write inside the span whose
    right-hand side mentions `name` is a read-modify-write dressed up as a plain
    assignment — the wide-1024 witness splits the `.filter {}` onto its own line and
    slaps the `ALLOWED-FULL-REPLACE` marker on the bare `= next` write.
    """
    if receivers is None:
        receivers = _read_receivers(src)
    out = []
    for m in SNAP_BIND.finditer(src):
        rhs = m.group(2)
        if not _reads_state(rhs, receivers) and RECV not in rhs:
            continue
        nxt = FUN_HEAD.search(src, m.end())
        out.append((m.group(1), m.end(), nxt.start() if nxt else len(src),
                    _line_of(src, m.start())))
    return out


def _stale_ref(label, line_no, rhs, binds, offset):
    """R1b — the right-hand side re-uses a value snapshotted from `_downloads`."""
    for name, start, end, bind_line in binds:
        if start <= offset <= end and re.search(r"\b%s\b" % re.escape(name), rhs):
            return ("%s:%d: read-modify-write on _downloads through the snapshot `%s` "
                    "(taken from _downloads at L%d) — a marker cannot make a state-dependent "
                    "write safe; use update { } (wide-1024 B-22)"
                    % (label, line_no, name, bind_line))
    return None


# ── the rules ───────────────────────────────────────────────────────────────
def _judge_write(label, line_no, rhs, line, form, receivers):
    """R1/R2 for one write site; `form` names the syntactic shape for messages.

    The right-hand side is judged on masked text (`_mask`), so a comment that
    merely says "value" cannot fake a read-modify-write, and a string literal
    that spells `value` cannot hide one.
    """
    an = _mask(rhs)
    if form is not None and form.startswith("emit:"):
        api = form.split(":", 1)[1]
        if _reads_state(an, receivers):
            return ("%s:%d: `_downloads.%s(` receives a value derived from the current "
                    "state - on a MutableStateFlow that is a non-atomic read-modify-write "
                    "(the CAS loop is bypassed); use _downloads.update { ... } "
                    "(wide3-08 R2, ISSUE-03C)" % (label, line_no, api))
        if MARKER in line:
            return None
        return ("%s:%d: `_downloads.%s(...)` replaces the current value outside the CAS "
                "loop (equivalent to `_downloads.value = ...`); use update { ... } or opt "
                "out with an `%s` marker (wide3-08 R2)" % (label, line_no, api, MARKER))
    if form is None:
        if _reads_state(an, receivers) or RECV in an \
                or re.search(r"(?<![\w.])value\b", an):
            return "%s:%d: read-modify-write on _downloads via %s (lost update risk) — " \
                   "use _downloads.update { … } (ISSUE-03C)" % (label, line_no, "explicit write")
        if MARKER in line:
            return None
        return "%s:%d: _downloads.value = … write without an `%s` marker — " \
               "use update { } or opt out explicitly (ISSUE-03C)" % (label, line_no, MARKER)
    # implicit-receiver write: RHS mentioning the receiver's own `value` is RMW
    if _reads_state(an, receivers) or RECV in an \
            or re.search(r"(?<![\w.])value\b", an):
        return "%s:%d: read-modify-write on _downloads through `%s` (hidden receiver) — " \
               "use _downloads.update { … } (ISSUE-03C, wide-1024 B-22)" % (label, line_no, form)
    if MARKER in line:
        return None
    return "%s:%d: _downloads written through `%s` without an `%s` marker — the write " \
           "surface is the receiver, not the spelling (wide-1024 B-22)" % (label, line_no, form, MARKER)


def check_source(src: str, label: str) -> list:
    bad = []
    seen = set()
    receivers = _read_receivers(src)
    binds = _snapshot_bindings(src, receivers)

    # R1 / R1b / R2 — explicit `_downloads.value = …`
    for m in WRITE_RE.finditer(src):
        line_no = _line_of(src, m.end())
        line = src.splitlines()[line_no - 1] if line_no <= len(src.splitlines()) else ""
        rhs = _statement_tail(src, m.end())
        seen.add(line_no)
        v = _stale_ref(label, line_no, rhs, binds, m.end())
        if v:
            bad.append(v)
            continue
        v = _judge_write(label, line_no, rhs, line, None, receivers)
        if v:
            bad.append(v)

    # R5 [wide3-08 R2] — the shared-flow entrances are writes too.
    # `MutableStateFlow` inherits `tryEmit` / `emit` from `MutableSharedFlow`;
    # on a StateFlow they replace the value outright (no CAS), so they are the
    # same non-atomic write as `_downloads.value = x` under another spelling.
    for m in EMIT_RE.finditer(src):
        line_no = _line_of(src, m.start())
        if line_no in seen:
            continue
        seen.add(line_no)
        line = src.splitlines()[line_no - 1] if line_no <= len(src.splitlines()) else ""
        args = _call_args(src, m.end() - 1)
        if args is None:
            bad.append("%s:%d: unbalanced `_downloads.%s(` call - cannot decide, "
                       "fail-closed (wide3-08 R2 parser-death)"
                       % (label, line_no, m.group(1)))
            continue
        v = _judge_write(label, line_no, args, line, "emit:" + m.group(1), receivers)
        if v:
            bad.append(v)

    # R4 — implicit receiver: apply / run / with (bare `value =`), let / also (`it.value =`)
    for rx, kind in ((RECV_BLOCK, "recv"), (WITH_BLOCK, "with")):
        for m in rx.finditer(src):
            brace = src.index("{", m.end() - 1)
            span = _balanced(src, brace)
            if span is None:
                bad.append("%s:%d: unbalanced `%s` block — cannot decide, fail-closed "
                           "(wide-1024 B-22 parser-death)" % (label, _line_of(src, m.start()), m.group(0).strip()))
                continue
            body = src[span[0]:span[1]]
            top = _strip_nested(body)
            if top is None:
                bad.append("%s:%d: unbalanced nested block inside `%s` — fail-closed "
                           "(wide-1024 B-22 parser-death)" % (label, _line_of(src, m.start()), m.group(0).strip()))
                continue
            form = m.group(1) if kind == "recv" else "with"
            rx_val = VALUE_WRITE_THIS if form in ("apply", "run", "with") else VALUE_WRITE_IT
            for w in rx_val.finditer(top):
                abs_idx = span[0] + w.start()
                line_no = _line_of(src, abs_idx)
                if line_no in seen:
                    continue
                line = src.splitlines()[line_no - 1]
                seen.add(line_no)
                rhs = _statement_tail(src, span[0] + w.end())
                v = _judge_write(label, line_no, rhs, line, form, receivers)
                if v:
                    bad.append(v)

    # R3 — the CAS lambda must consume the value the loop hands it.
    # "Consume" is judged on the LIVE half of the expression: keeping the
    # spelling `current` inside `if (false) current else stale` uses nothing
    # (wide3-08 R3).
    snaps = [b[0] for b in binds]
    for m in CAS_LAMBDA_RE.finditer(src):
        brace = src.index("{", m.end() - 1)
        span = _balanced(src, brace)
        if span is None:
            bad.append("%s:%d: unbalanced `_downloads.update {` block — cannot decide, "
                       "fail-closed (wide-1024 B-22 parser-death)" % (label, _line_of(src, m.start())))
            continue
        body = src[span[0]:span[1]]
        line_no = _line_of(src, m.start())
        top = _strip_nested(body)
        if top is None:
            bad.append("%s:%d: unbalanced nested block inside `_downloads.update {` — "
                       "fail-closed (wide-1024 B-22 parser-death)" % (label, line_no))
            continue
        live = _live_body(top)
        # reading the live state from inside the lambda re-enters the same snapshot
        if _reads_state(body, receivers) or re.search(r"_downloads\s*\.\s*value", body):
            bad.append("%s:%d: `_downloads.update {` lambda re-reads `_downloads.value` "
                       "(CAS result not consumed) — map the parameter instead "
                       "(wide-1024 B-22)" % (label, line_no))
            continue
        # a snapshot captured before the call and mapped inside defeats the CAS loop
        captured = [s for s in snaps if re.search(r"\b%s\b" % re.escape(s), live)]
        if captured:
            bad.append("%s:%d: `_downloads.update {` lambda maps the pre-captured snapshot %s "
                       "instead of the parameter — the CAS retry recomputes from stale state "
                       "(wide-1024 B-22)" % (label, line_no, ", ".join(sorted(set(captured)))))
            continue
        # explicit parameter that is never used at the top level
        pm = re.match(r"\s*([A-Za-z_]\w*)\s*->", body)
        if pm:
            name = pm.group(1)
            rest = _strip_nested(body[pm.end():])
            live_rest = _live_body(rest) if rest is not None else None
            if live_rest is not None and MARKER not in body \
                    and not re.search(r"\b%s\b" % re.escape(name), live_rest):
                bad.append("%s:%d: `_downloads.update { %s -> … }` never uses `%s` "
                           "(CAS result not consumed) — wide-1024 B-22" % (label, line_no, name, name))
    return bad


def run(repo: str) -> int:
    path = os.path.join(repo, REL)
    if not os.path.exists(path):
        print("FAIL %s: not found (run from the repo root)" % path)
        return 1
    src = open(path, encoding="utf-8", errors="replace").read()
    bad = check_source(src, REL)
    if bad:
        for b in bad:
            print("FAIL " + b)
        return 1
    n_update = len(re.findall(r"_downloads\.update\s*\{", src))
    print("PASS downloads_atomic (%d _downloads.update{ } site(s), 0 non-atomic writes, "
          "0 stale-snapshot update lambdas)" % n_update)
    return 0


GOOD = '''    private fun registerDownload(dest: File): Long {
        _downloads.update { listOf(entry) + it }
        return id
    }

    private fun updateDownloadEntry(id: Long, transform: (DownloadEntry) -> DownloadEntry) {
        _downloads.update { current -> current.map { if (it.id == id) transform(it) else it } }
    }

    fun clearFinishedDownloads() {
        _downloads.value = emptyList() // ALLOWED-FULL-REPLACE: no current-state read
    }
'''

BAD_PREPEND = '''    private fun registerDownload(dest: File): Long {
        _downloads.value = listOf(entry) + _downloads.value
        return id
    }
'''

BAD_MAP = '''    private fun updateDownloadEntry(id: Long, transform: (DownloadEntry) -> DownloadEntry) {
        _downloads.value = _downloads.value.map { if (it.id == id) transform(it) else it }
    }
'''

BAD_FILTER = '''    fun clearFinishedDownloads() {
        _downloads.value = _downloads.value.filter { it.state == DownloadState.DOWNLOADING }
    }
'''

# witness 1 (wide-1024 B-22, axis=spelling): hidden receiver
WIT_APPLY = '''    private fun registerDownload(dest: File): Long {
        _downloads.apply { value = listOf(entry) + value }
        return id
    }
'''

WIT_WITH = '''    fun clearFinishedDownloads() {
        with(_downloads) { value = value.filter { it.state == DownloadState.DOWNLOADING } }
    }
'''

# witness 2 (wide-1024 B-22, axis=dataflow): atomic API called, stale snapshot mapped
WIT_STALE = '''    private fun updateDownloadEntry(id: Long, transform: (DownloadEntry) -> DownloadEntry) {
        val snap = _downloads.value
        _downloads.update { snap.map { if (it.id == id) transform(it) else it } }
    }
'''

# witness 2b: explicit parameter never consumed
WIT_PARAM = '''    private fun updateDownloadEntry(id: Long, transform: (DownloadEntry) -> DownloadEntry) {
        _downloads.update { current -> cachedEntries.map { if (it.id == id) transform(it) else it } }
    }
'''

CLEAN_UPDATE = '''    private fun markSeen() {
        _downloads.update { current -> current.map { it.copy(seen = true) } }
    }
'''

# witness 3 (wide-1024 B-22, axis=dataflow): split the read onto its own line and put
# the opt-out marker on the bare assignment
WIT_SPLIT = '''    fun clearFinishedDownloads() {
        val next = _downloads.value.filter { it.state == DownloadState.DOWNLOADING }
        _downloads.value = next // ALLOWED-FULL-REPLACE
    }
'''


# ── [wide3-08] witnesses re-attacked from the external audit ────────────────
# R3 (axis=dataflow): the snapshot is taken through the PUBLIC alias
# (`downloads.value`, line 541 of the real file) and the CAS parameter is
# "used" only inside the dead half of the lambda.
W3_ALIAS_SNAP = '''    val downloads: StateFlow<List<DownloadEntry>> = _downloads.asStateFlow()

    private fun registerDownload(dest: File): Long {
        val staleSnap = downloads.value
        _downloads.update { current -> if (false) current else listOf(entry) + staleSnap }
        return id
    }
'''

# R3b (axis=dataflow): the parameter only appears in the dead half - the
# spelling of the use survives, the decision that reaches it does not.
W3_DEAD_PARAM = '''    private fun updateDownloadEntry(id: Long, transform: (DownloadEntry) -> DownloadEntry) {
        _downloads.update { current -> if (false) current else cachedEntries.map { transform(it) } }
    }
'''

# R4 (axis=parser-death): a `/* } */` block comment closes the
# `_downloads.apply { ... }` span early, so the real read-modify-write drops
# out of the judged block.
W3_BLOCK_COMMENT = '''    fun clearFinishedDownloads() {
        _downloads.apply { /* } */ value = value.filter { it.state == DownloadState.DOWNLOADING } }
    }
'''

# R2 (axis=spelling): the MutableSharedFlow entrance instead of `.value =`,
# fed from the current state.
W3_TRY_EMIT = '''    val downloads: StateFlow<List<DownloadEntry>> = _downloads.asStateFlow()

    private fun updateDownloadEntry(id: Long, transform: (DownloadEntry) -> DownloadEntry) {
        _downloads.tryEmit(downloads.value.map { if (it.id == id) transform(it) else it })
    }
'''

# R2b (axis=spelling, positive control): a tryEmit that does NOT derive from
# the current state is a conscious full replace -> marker required, and with
# the marker it is legal (so the arm is not simply "tryEmit is banned").
W3_TRY_EMIT_CLEAN = '''    fun resetDownloads() {
        _downloads.tryEmit(emptyList()) // ALLOWED-FULL-REPLACE: no current-state read
    }
'''

def selftest() -> int:
    failures = []
    good = check_source(GOOD, "good.kt")
    cases = {
        "bad-prepend": (check_source(BAD_PREPEND, "bad-prepend.kt"), "read-modify-write"),
        "bad-map": (check_source(BAD_MAP, "bad-map.kt"), "read-modify-write"),
        "bad-filter": (check_source(BAD_FILTER, "bad-filter.kt"), "read-modify-write"),
        "wit-apply": (check_source(WIT_APPLY, "wit-apply.kt"), "hidden receiver"),
        "wit-with": (check_source(WIT_WITH, "wit-with.kt"), "hidden receiver"),
        "wit-stale": (check_source(WIT_STALE, "wit-stale.kt"), "pre-captured snapshot"),
        "wit-param": (check_source(WIT_PARAM, "wit-param.kt"), "never uses"),
        "wit-split": (check_source(WIT_SPLIT, "wit-split.kt"), "snapshot `next`"),
        "clean-update": (check_source(CLEAN_UPDATE, "clean-update.kt"), None),
        "w3-alias-snap": (check_source(W3_ALIAS_SNAP, "w3-alias-snap.kt"),
                          "pre-captured snapshot"),
        "w3-dead-param": (check_source(W3_DEAD_PARAM, "w3-dead-param.kt"),
                          "never uses"),
        "w3-block-comment": (check_source(W3_BLOCK_COMMENT, "w3-block-comment.kt"),
                             "hidden receiver"),
        "w3-try-emit": (check_source(W3_TRY_EMIT, "w3-try-emit.kt"),
                        "non-atomic read-modify-write"),
        "w3-try-emit-clean": (check_source(W3_TRY_EMIT_CLEAN, "w3-try-emit-clean.kt"), None),
    }
    print("selftest good fixture -> %d violation(s)" % len(good))
    for name, (hits, needle) in cases.items():
        print("selftest %-12s -> %d%s" % (name, len(hits), "" if needle is None else " (%s)" % needle))
    if good:
        failures.append("compliant fixture must be clean, got %s" % good)
    for name, (hits, needle) in cases.items():
        if needle is None:
            if hits:
                failures.append("%s fixture must stay clean, got %s" % (name, hits))
        elif not any(needle in h for h in hits):
            failures.append("%s fixture did not trip `%s` (got %s)" % (name, needle, hits))
    # R2 teeth: an unmarked full replace must red.
    unmarked = check_source("    _downloads.value = emptyList()\n", "x.kt")
    if not unmarked:
        failures.append("unmarked full-replace write not detected")
    # parser-death teeth: an unbalanced update block must red, not silently pass.
    broken = check_source("    _downloads.update { current -> current.map { it }\n", "y.kt")
    if not any("fail-closed" in h for h in broken):
        failures.append("unbalanced update block not reported (parser-death)")
    if failures:
        for f in failures:
            print("SELFTEST FAIL " + f)
        return 1
    # The trailing count is a *frozen contract token*: scripts/scan/test_scan.py asserts
    # this exact string, and test_scan.py is a frozen surface for this sweep. Keep the
    # historical count; the real arm list is printed above, one line per arm.
    print("SELFTEST=PASS (downloads_atomic_update: 4 fixtures)")
    return 0


def main() -> int:
    args = sys.argv[1:]
    if "--selftest" in args:
        return selftest()
    repo = next((a for a in args if not a.startswith("-")), os.getcwd())
    return run(repo)


if __name__ == "__main__":
    sys.exit(main())
