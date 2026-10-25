#!/usr/bin/env python3
"""[fix/da1025-D] browser_tabs_lifecycle_check

ISSUE-04 gate: the browser tab-pool saved-state files
(filesDir/browser_tabs/<sid>.json — a session's full visited-URL history)
must live and die with the session, same as the bind-mounted session dir and
its media. Before this gate the file was outside both cleanup paths:
  - deleteSessionFiles never deleted it → deleted session's URL history
    stayed on disk forever;
  - reclaimOrphans never scanned it → invisible to the storage page's
    orphan sweep.

Checked in SessionFileStore.kt (code anchors only — line AND block comments
are stripped before judgment, so a KDoc that merely *mentions* browser_tabs
cannot satisfy any criterion):
  C1  `browser_tabs` root + `fun browserTabFile(` definition exist
      (fail-closed: missing anchor = red, never a silent skip);
  C2  `deleteSessionFiles` function body references `browserTabFile(`
      (the delete path actually removes the tab state file);
  C3  `reclaimOrphans` function body calls the orphan cleanup
      (`deleteOrphanBrowserTabFiles(` or equivalent anchor), and the
      orphan browser-tabs cleanup itself is session-shape guarded
      (`looksLikeSessionId` OR `__new__` prefix branch present).

[fix/dv14-B2] C4 — the pool's own teardown ordering, checked in
BrowserTabPool.kt (ONLY the pool file; the store criteria above are
unaffected):
  C4a `dispose()` must NOT call `saveState(` (or re-persist to disk): the
      unconditional saveState() in dispose resurrected a just-deleted
      session's browser_tabs/<sid>.json on disk;
  C4b dispose() must still destroy tabs (release half must survive);
  C4c onTrimMemory / evictIdleTabs must assign the evicted tab's URL into
      savedURLs AFTER `destroyTab(` — destroyTab removes the entry, so an
      assignment made before it was silently wiped and the URL was lost.
  Fail-closed: missing dispose/trim/evict bodies, a missing destroyTab call
  or a missing savedURLs assignment = red, never a silent skip.

[fix/dv16-B7] C4d — window.open / window.close must persist too. The pool
persists on every tab mutation EXCEPT `handleNewWindow` / `handleCloseWindow`
(the only two paths that changed `_tabs` / `_selectedTabId` without calling
saveState()): a JS window.open tab (OAuth popups are the largest traffic) or
a window.close()'d tab resurrected from / vanished out of
browser_tabs/<sid>.json on session reload, and selectedTabId could point at
a destroyed tab. Both bodies must carry `saveState(` (fail-closed: an
unextractable body = red, never a silent skip).

[dv24-B4 B-07] C4e/C4f — disposal owns the pool's ASYNC faces, not just the
eviction ticker. `dispose()` used to cancel only `evictionJob` while the two
instance-owned CoroutineScopes (`evictionScope`, `downloadScope`) and the
pending-restore table lived on:
  C4e every `CoroutineScope(` field declared in the pool must be cancelled
      from `dispose()` — directly or through a helper body in the same file
      (a transitive call closure, so a `destroyScopes()` refactor is
      accepted; the set of scopes is DERIVED from the file, so a future third
      scope is covered by construction). Reason: a restore already dispatched
      to Main rebuilt WebViews on a destroyed pool (never destroyed again:
      main thread + leaked Context) and re-persisted the tab list, recreating
      the `<sid>.json` the session deletion had just removed; in-flight
      downloads kept writing into the deleted session's workspace and posting
      through the cleared ViewModel's callback.
  C4f a disposal latch must exist (@Volatile — dispose may run off-main while
      the Main-dispatched restore reads it), `dispose()` must set it and
      short-circuit on it BEFORE it starts releasing (idempotent release
      half), and BOTH async continuations that can outlive the pool —
      `saveState` (before it touches browser_tabs) and `restoreSavedTabs`
      (before the first createTab) — must return on it. Cancelling a scope
      cannot retract a block already dispatched to Main; that is exactly the
      window this latch covers.
  Fail-closed throughout: an unextractable body or a missing latch is red,
  never a silent skip.

[fix/dv17-1 B-01/B-06] C5/C6 — the same pool file, two "written but never
consumed / never re-pointed" defects (also read from BrowserTabPool.kt):
  C5  a detected risk challenge must actually REACH the agent. The four
      structured fields on BrowserActionResult (riskChallengeDetected /
      riskChallengeKind / recommendedNextAction / throttleDelayMs) had zero
      readers in the tree — ChatToolExecutors consumes `result.text` alone —
      so the renderer must be called in the action-assembly path
      (`runAcquiredAction`) AND its product merged into the returned text
      (a `text =` write after the call). Judged on the CALL SHAPE
      (`BrowserRiskControl.challengeNoticeLine(`), never a bare name, and
      comments are stripped so a KDoc mention cannot satisfy it.
  C6  `setSession` must re-point download records onto the new session's
      workspace when the pool is rebound across a session boundary: the old
      id must be CAPTURED BEFORE `this.sessionId` is overwritten, the remap
      must be guarded by an actual id change, must go through the atomic
      `_downloads` update (never a plain `_downloads.value =` assignment,
      which races the download/URL writers), and `loadSavedState()` must stay
      the last step (fail-closed: unextractable bodies = red).

Usage: python3 browser_tabs_lifecycle_check.py <repo_root>
       python3 browser_tabs_lifecycle_check.py --root <repo_root>
       python3 browser_tabs_lifecycle_check.py --selftest
Exit 0 = pass, 1 = violation, 2 = usage error.
"""
import os
import re
import sys

PKG = "src/android/app/src/main/java/com/rikkaminis/app/data/storage"
TARGET = "SessionFileStore.kt"
# [fix/dv14-B2] C4's scan target: ONLY the pool file (scope discipline —
# the store criteria C1-C3 read SessionFileStore.kt and stay untouched).
POOL_REL = "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserTabPool.kt"
# [da1040-B2] C9's consumer face: the sheet is where a non-reactive busy read
# freezes the affordances, so the criterion reads it too.
SHEET_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/browser/BrowserSheet.kt"
MARKER = "BROWSER_TABS_SELFTEST=PASS"


def strip_comments(src: str) -> str:
    """Remove // line comments AND /* */ block comments, string-aware."""
    out = []
    in_str = False
    in_line = False
    in_block = False
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if in_line:
            if c == "\n":
                in_line = False
                out.append(c)
            i += 1
            continue
        if in_block:
            if c == "*" and nxt == "/":
                in_block = False
                i += 2
                continue
            if c == "\n":
                out.append(c)
            i += 1
            continue
        if in_str:
            if c == "\\":
                out.append(src[i:i + 2])
                i += 2
                continue
            if c == '"':
                in_str = False
            out.append(c)
            i += 1
            continue
        if c == "/" and nxt == "/":
            in_line = True
            i += 2
            continue
        if c == "/" and nxt == "*":
            in_block = True
            i += 2
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def _match_paren(src: str, open_idx: int) -> int:
    """Index of the `)` matching src[open_idx] == `(`, or -1 (string-aware)."""
    if open_idx < 0 or open_idx >= len(src) or src[open_idx] != "(":
        return -1
    depth = 0
    i = open_idx
    while i < len(src):
        c = src[i]
        if c == '"':
            j = i + 1
            while j < len(src):
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == '"':
                    break
                j += 1
            i = j + 1
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def _brace_span(src: str, open_idx: int) -> int:
    """Index just past the `}` matching src[open_idx] == `{`, or -1."""
    if open_idx < 0 or open_idx >= len(src) or src[open_idx] != "{":
        return -1
    depth = 0
    i = open_idx
    while i < len(src):
        c = src[i]
        if c == '"':
            j = i + 1
            while j < len(src):
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == '"':
                    break
                j += 1
            i = j + 1
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return -1


# [wide3-06 C4f] A next-line token that can only begin a new class member —
# it stops the return-type scan from wandering into the next declaration when
# the function under test has no body at all.
_MEMBER_HEAD_RE = re.compile(
    r"^(?:@|(?:public|private|internal|protected|override|suspend|inline|operator|"
    r"open|abstract|fun|val|var|class|object|init)\b)"
)


def _expr_end(src: str, eq_idx: int) -> int:
    """End offset of the expression body that starts after `=` at [eq_idx].

    A depth-0 newline ends it UNLESS the next line is a continuation
    (`.foo()`, `?: x`, `+ x`, `&& x`, …), which is how a wrapped expression is
    written in this tree. Deriving the end this way is what stops an
    expression-bodied function from swallowing the next declaration — the
    blindness C4f's witness exploited (`= null` followed by a sibling fun).
    """
    depth = 0
    i = eq_idx + 1
    n = len(src)
    while i < n:
        c = src[i]
        if c == '"':
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == '"':
                    break
                j += 1
            i = j + 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                return i
            depth -= 1
        elif c == "\n" and depth == 0:
            j = i + 1
            while j < n and src[j] in " \t":
                j += 1
            if j < n and src[j] in ".?:+-*/&|,":
                i = j
                continue
            return i
        i += 1
    return n


def slice_body(src: str, header: str) -> str:
    """Return the function body for `header` (e.g. 'fun deleteSessionFiles').

    [wide3-06 C4f] The body start is derived from the PARAMETER LIST, never
    from `src.find("{", idx)`: a default argument may itself be a lambda
    (`fun restoreSavedTabs(onDone: () -> Unit = { if (isDisposed) return })`),
    and the first `{` after the name sliced THAT lambda — the real body was
    never examined, so deleting its `isDisposed` guard stayed green.
    Expression bodies (`= expr`) run to the end of the expression.

    Fail-closed: returns '' when the header is not found, the parameter list
    never closes, or the body cannot be delimited — callers treat '' as a
    violation, never a silent skip.
    """
    idx = src.find(header)
    if idx < 0:
        return ""
    open_paren = src.find("(", idx)
    if open_paren < 0:
        return ""
    close_paren = _match_paren(src, open_paren)
    if close_paren < 0:
        return ""
    i = close_paren + 1
    while i < len(src):
        c = src[i]
        if c == "\n":
            j = i + 1
            while j < len(src) and src[j] in " \t":
                j += 1
            if _MEMBER_HEAD_RE.match(src[j:j + 16]) or src[j:j + 1] == "}":
                return ""  # declaration without a body on this head
            i = j
            continue
        if c == "{":
            end = _brace_span(src, i)
            return src[i:end] if end > 0 else ""
        if c == "=" and src[i + 1:i + 2] != "=":
            return src[i:_expr_end(src, i)]
        if c == "}":
            return ""
        i += 1
    return ""


def _cond_dead_text(cond: str) -> bool:
    """True when a literal operand decides the condition.

    `false && guard` / `true || guard` keep the guard's spelling and delete
    its decision — the single most common dataflow witness in this wave.
    """
    parts, depth, buf, i = [], 0, "", 0
    while i < len(cond):
        c = cond[i]
        two = cond[i:i + 2]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        if depth == 0 and two in ("&&", "||"):
            parts.append(buf)
            buf = ""
            i += 2
            continue
        buf += c
        i += 1
    parts.append(buf)
    return any(p.strip() in ("true", "false") for p in parts)


def _dead_spans(code: str) -> list:
    """Spans of statically-unreachable `if (false …)` / `while (false)` bodies."""
    spans = []
    for m in re.finditer(r"\b(?:if|while)\s*\(", code):
        open_idx = m.end() - 1
        close_idx = _match_paren(code, open_idx)
        if close_idx < 0 or not _cond_dead_text(code[open_idx + 1:close_idx]):
            continue
        j = close_idx + 1
        while j < len(code) and code[j] in " \t":
            j += 1
        if j < len(code) and code[j] == "{":
            end = _brace_span(code, j)
            if end > 0:
                spans.append((j, end))
                continue
        eol = code.find("\n", j)
        spans.append((j, len(code) if eol < 0 else eol))
    return spans


def _pos_dead(code: str, pos: int, spans=None) -> bool:
    spans = _dead_spans(code) if spans is None else spans
    return any(a <= pos < b for a, b in spans)


def _stmt_tail(src: str, start: int, limit: int = 600) -> str:
    """The expression starting at [start], up to a depth-0 newline / closer."""
    depth = 0
    out = []
    i = start
    n = min(len(src), start + limit)
    while i < n:
        c = src[i]
        if c == '"':
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == '"':
                    break
                j += 1
            out.append(src[i:j + 1])
            i = j + 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                break
            depth -= 1
        elif c == "\n" and depth == 0:
            break
        out.append(c)
        i += 1
    return "".join(out)


def check_source(source: str) -> list:
    src = strip_comments(source)
    errs = []

    # C1: root + definition (fail-closed)
    if "browser_tabs" not in src:
        errs.append("C1: no browser_tabs root in SessionFileStore")
    if "fun browserTabFile(" not in src:
        errs.append("C1: no fun browserTabFile( definition found")

    # C2: delete path removes the tab state file
    del_body = slice_body(src, "fun deleteSessionFiles")
    if not del_body:
        errs.append("C2: deleteSessionFiles body not extractable (fail-closed)")
    elif "browserTabFile(" not in del_body:
        errs.append("C2: deleteSessionFiles does not delete the browser tab state file")

    # C3: orphan reclaim path covers browser_tabs
    reclaim_body = slice_body(src, "fun reclaimOrphans")
    if not reclaim_body:
        errs.append("C3: reclaimOrphans body not extractable (fail-closed)")
    elif "deleteOrphanBrowserTabFiles(" not in reclaim_body:
        errs.append("C3: reclaimOrphans does not reclaim orphan browser_tabs files")
    orphan_body = slice_body(src, "fun deleteOrphanBrowserTabFiles")
    if not orphan_body:
        errs.append("C3: deleteOrphanBrowserTabFiles not found (fail-closed)")
    else:
        if "looksLikeSessionId(" not in orphan_body:
            errs.append("C3: orphan browser_tabs cleanup not session-shape guarded")
        # [da1040-A3] The old C3 also demanded a local `__new__` branch HERE.
        # That spelling is what the defect was made of: four of the five sweep
        # callers never copied it. Draft coverage is now decided by the shape
        # predicate itself (C7) and is fail-closed there; a call-site literal
        # is rejected by C7's single-literal count.

    # --- [da1040-A3] C7: the session-shape predicate carries BOTH shapes ---
    if not re.search(r"const val DRAFT_ID_PREFIX\s*=\s*\"__new__\"", src):
        errs.append("C7: the __new__ draft prefix is not declared as a single constant (fail-closed)")
    lit = len(re.findall(r'"__new__"', src))
    if lit != 1:
        errs.append(
            f'C7: the "__new__" namespace literal appears {lit} times in SessionFileStore '
            "(expected exactly 1 — inside the shape predicate); a per-call-site copy is "
            "how four of five sweep callers kept the narrower notion and deleted an "
            "in-flight draft's files")
    pred = slice_body(src, "private fun looksLikeSessionId")
    if not pred:
        errs.append("C7: looksLikeSessionId body not extractable (fail-closed)")
    else:
        if "DRAFT_ID_PREFIX" not in pred:
            errs.append(
                "C7: looksLikeSessionId does not cover the __new__ draft alias — an active "
                "draft's session dir, media leaf and tab file all look orphaned to the sweep")
        if "36" not in pred or "count {" not in pred:
            errs.append("C7: looksLikeSessionId no longer enforces the UUID shape (fail-closed)")

    # --- [da1040-A3] C8: every public orphan entry folds the active draft in ---
    helper = slice_body(src, "private fun withActiveComposerOwner")
    if not helper:
        errs.append("C8: withActiveComposerOwner body not extractable (fail-closed)")
    elif "activeDraftIds()" not in helper:
        errs.append("C8: the live-set widener no longer consults the active draft provider")
    if "ComposerDraftStore.activeDraftIds(context)" not in src:
        errs.append(
            "C8: the Context constructor does not wire the draft-id provider to "
            "ComposerDraftStore — a hardcoded/empty provider silently turns the whole "
            "widening into a no-op and the draft's files stay reclaimable")
    for fn in ("fun scanMediaOnce", "fun scanOrphanSessionDirs", "fun scanOrphans", "fun reclaimOrphans"):
        body = slice_body(src, fn)
        if not body:
            errs.append(f"C8: {fn[4:]} body not extractable (fail-closed)")
        elif "withActiveComposerOwner(" not in body:
            errs.append(
                f"C8: {fn[4:]} does not widen the live set with the composer's active draft — "
                "an in-flight draft's files are reclaimable through this entry point")

    return errs


# --- [fix/dv14-B2] C4: pool teardown ordering (separate function — the
# C1-C3 criteria read SessionFileStore.kt; C4 reads BrowserTabPool.kt only).

# [dv24-B4 B-07] C4e/C4f helpers. Both judge the DECISION, never a spelling of
# the cleanup: the scope set is derived from the file's own field declarations
# and cancellation is traced through helper bodies (so `destroyScopes()` /
# `releaseAsyncFaces()` refactors stay green, while a third scope nobody
# cancels goes red by construction).

def _c4_scope_fields(code: str) -> list:
    """Every `val name [ : CoroutineScope ] = CoroutineScope(` in the pool."""
    return re.findall(
        r"\bval\s+([A-Za-z_]\w*)\s*(?::\s*CoroutineScope\s*)?=\s*CoroutineScope\s*\(",
        code,
    )


def _c4_cancel_closure(code: str, entry_body: str) -> set:
    """Names receiving `.cancel()` reachable from entry_body, following calls
    into other block-bodied functions declared in the same file.

    (_c10_slice_fun is defined in the C10 section below; resolved at call
    time. It deliberately skips expression-bodied functions, which cannot own
    a multi-statement cancel block anyway.)"""
    bodies = {}
    for m in re.finditer(r"fun\s+([A-Za-z_]\w*)\s*\(", code):
        body = _c10_slice_fun(code, m.group(0))
        if body:
            bodies.setdefault(m.group(1), body)
    cancelled = set()
    seen = set()
    stack = [entry_body]
    while stack:
        body = stack.pop()
        if body in seen:
            continue
        seen.add(body)
        for m in re.finditer(r"\b([A-Za-z_]\w*)\s*\.\s*cancel\s*\(", body):
            cancelled.add(m.group(1))
        for m in re.finditer(r"\b([A-Za-z_]\w*)\s*\(", body):
            name = m.group(1)
            if name in bodies and bodies[name] not in seen:
                stack.append(bodies[name])
    return cancelled


def _c4_guard_pos(body: str, latch: str) -> int:
    """Position of an early `if (<latch>) return` guard in body, or -1.

    [wide3-06 C11] A guard nested inside a statically-dead block
    (`if (false) { if (latch) return }`) keeps the spelling and loses the
    decision, so dead spans are skipped.
    """
    spans = _dead_spans(body)
    if re.search(rf"\bif\s*\(\s*{re.escape(latch)}\s*\)\s*return\b", body):
        for m in re.finditer(rf"\bif\s*\(\s*{re.escape(latch)}\s*\)\s*return\b", body):
            if not _pos_dead(body, m.start(), spans):
                return m.start()
    m = re.search(rf"\bif\s*\(\s*{re.escape(latch)}\s*\)\s*\{{", body)
    if m and not _pos_dead(body, m.start(), spans) and re.search(
        r"\breturn\b", body[m.end():m.end() + 160]
    ):
        return m.start()
    return -1


def check_pool_source(pool_src: str) -> list:
    """[fix/dv14-B2] C4 — dispose() must not re-persist; the evicted tab's
    URL must be stored AFTER destroyTab. Fail-closed throughout."""
    code = strip_comments(pool_src)
    errs = []
    disp = slice_body(code, "fun dispose")
    if not disp:
        errs.append("C4: fun dispose( body not extractable (fail-closed)")
    else:
        if "saveState(" in disp:
            errs.append("C4: dispose() must not call saveState( (deleted sessions were being resurrected)")
        if "destroyTab(" not in disp:
            errs.append("C4: dispose() no longer destroys tabs (fail-closed)")
    for fn in ("fun onTrimMemory", "fun evictIdleTabs"):
        body = slice_body(code, fn)
        label = fn[4:]
        if not body:
            errs.append(f"C4: {label} body not extractable (fail-closed)")
            continue
        dpos = body.find("destroyTab(")
        if dpos < 0:
            errs.append(f"C4: {label} does not call destroyTab( (fail-closed)")
            continue
        w = re.search(r"savedURLs\[[^\]]*\]\s*=", body)
        if not w:
            errs.append(f"C4: {label} never assigns the evicted tab's URL into savedURLs (fail-closed)")
        elif w.start() < dpos:
            errs.append(f"C4: {label} assigns savedURLs before destroyTab( — destroyTab's remove wipes the entry")

    # --- [fix/dv16-B7] C4d: window.open / window.close must persist too ---
    for fn in ("fun handleNewWindow", "fun handleCloseWindow"):
        body = slice_body(code, fn)
        label = fn[4:]
        if not body:
            errs.append(f"C4d: {label} body not extractable (fail-closed)")
            continue
        if "saveState(" not in body:
            errs.append(
                f"C4d: {label} changes tab state but never calls saveState( — "
                "window.open/close tabs resurrect from the stale state file "
                "(and selectedTabId can point at a destroyed tab)")

    # --- [dv24-B4 B-07] C4e: dispose() must cancel every pool-owned scope ---
    scopes = _c4_scope_fields(code)
    if disp and not scopes:
        errs.append(
            "C4e: no `CoroutineScope(` field found in the pool (fail-closed — this "
            "criterion exists to keep disposal owning the pool's async faces)")
    elif disp:
        cancelled = _c4_cancel_closure(code, disp)
        for scope in scopes:
            if scope not in cancelled:
                errs.append(
                    f"C4e: dispose() never cancels `{scope}` — work already queued on it "
                    "keeps running against a destroyed pool (a Main-dispatched restore "
                    "rebuilt WebViews nobody will destroy and re-persisted a just-deleted "
                    "session's tab file)")

    # --- [dv24-B4 B-07] C4f: the disposal latch and its two async guards ---
    latch = ""
    if disp:
        for candidate in re.findall(r"\b([A-Za-z_]\w*)\s*=\s*true\b", disp):
            if _c4_guard_pos(disp, candidate) >= 0:
                latch = candidate
                break
        if not latch:
            errs.append(
                "C4f: dispose() never latches a disposal flag and short-circuits on it — "
                "the release half is not idempotent, and no async continuation can ask "
                "whether the pool is gone")
        else:
            if not re.search(
                rf"@Volatile\b[\s\S]{{0,160}}?\bvar\s+{re.escape(latch)}\b", code
            ):
                errs.append(
                    f"C4f: the disposal latch `{latch}` is not @Volatile — dispose() runs "
                    "on the ViewModel-clearing thread while the Main-dispatched restore "
                    "reads it; a stale read is the bug this latch exists for")
            effects = [
                p for p in (
                    disp.find("cancel("),
                    disp.find("unregisterComponentCallbacks"),
                    disp.find("destroyTab("),
                ) if p >= 0
            ]
            if effects and _c4_guard_pos(disp, latch) > min(effects):
                errs.append(
                    f"C4f: dispose() sets `{latch}` only after it starts releasing — a "
                    "second dispose must be a no-op before any scope is cancelled")
            save = slice_body(code, "private fun saveState") or slice_body(code, "fun saveState")
            if not save:
                errs.append("C4f: fun saveState body not extractable (fail-closed)")
            else:
                pos = _c4_guard_pos(save, latch)
                touch = save.find("browser_tabs")
                if pos < 0:
                    errs.append(
                        f"C4f: saveState does not short-circuit on `{latch}` — a restore "
                        "that already landed on Main writes the tab list back after "
                        "disposal and re-creates the deleted session's browser_tabs file")
                elif touch >= 0 and pos > touch:
                    errs.append(
                        f"C4f: saveState consults `{latch}` only after touching the tab "
                        "file — the write has already happened")
            restore = slice_body(code, "fun restoreSavedTabs")
            if not restore:
                errs.append("C4f: fun restoreSavedTabs body not extractable (fail-closed)")
            else:
                pos = _c4_guard_pos(restore, latch)
                first = restore.find("createTab(")
                if pos < 0:
                    errs.append(
                        f"C4f: restoreSavedTabs does not short-circuit on `{latch}` — a "
                        "restore queued before disposal rebuilds WebViews on a destroyed "
                        "pool (50-100 MB each, plus the Context they hold)")
                elif first >= 0 and pos > first:
                    errs.append(
                        f"C4f: restoreSavedTabs consults `{latch}` only after createTab( — "
                        "the WebViews are already rebuilt")

    # --- [fix/dv17-1 B-01] C5: a detected risk challenge must reach the agent.
    # Bound to the call shape (+ the text merge AFTER it), never a bare name:
    # the defect was exactly that the structured fields were written and never
    # read, and the only exit ChatToolExecutors reads is result.text.
    action = slice_body(code, "private suspend fun runAcquiredAction")
    if not action:
        errs.append("C5: runAcquiredAction body not extractable (fail-closed)")
    else:
        notice_pos = action.find("BrowserRiskControl.challengeNoticeLine(")
        if notice_pos < 0:
            errs.append(
                "C5: runAcquiredAction never renders the detected risk challenge "
                "(no BrowserRiskControl.challengeNoticeLine( call) — the agent "
                "sees the challenge page as undistinguished text and retries blindly")
        else:
            # [wide3-06 C5] "a `text =` write exists after the call" was true of
            # `val text = stamped.text` too — the renderer's product was bound
            # and then dropped. The product must FLOW into the text write.
            bound = re.search(
                r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*=\s*[ \t]*$", action[:notice_pos]
            )
            if not bound:
                errs.append(
                    "C5: the risk renderer's product is not bound to a local "
                    "(`val <notice> = BrowserRiskControl.challengeNoticeLine(`) — "
                    "its result cannot flow anywhere else (fail-closed)")
            else:
                name = bound.group(1)
                merged = False
                for w in re.finditer(r"\btext\s*=", action[notice_pos:]):
                    tail = _stmt_tail(action, notice_pos + w.end())
                    if re.search(rf"\b{re.escape(name)}\b", tail):
                        merged = True
                        break
                if not merged:
                    errs.append(
                        f"C5: the rendered risk notice `{name}` is never merged into "
                        "the result text (no `text =` write whose expression reads it "
                        "after the challengeNoticeLine( call — the notice is computed "
                        "and dropped, so the agent still cannot read it)")
        if "BrowserRiskControl." not in action:
            errs.append("C5: runAcquiredAction no longer consults BrowserRiskControl (fail-closed)")

    # --- [fix/dv17-1 B-06] C6: setSession must re-point download destinations.
    sess = slice_body(code, "fun setSession")
    if not sess:
        errs.append("C6: setSession body not extractable (fail-closed)")
    else:
        capture = re.search(r"val\s+\w+\s*=\s*this\.sessionId", sess)
        assign = re.search(r"this\.sessionId\s*=\s*sessionId", sess)
        if not capture:
            errs.append(
                "C6: setSession never captures the previous session id before rebinding "
                "(download records under the old workspace can no longer be found)")
        elif not assign:
            errs.append("C6: setSession no longer rebinds this.sessionId (fail-closed)")
        elif capture.start() > assign.start():
            errs.append(
                "C6: setSession overwrites this.sessionId BEFORE capturing the old id — "
                "the old session id is lost and no download path can be re-pointed")
        if not re.search(r"\w+\s*!=\s*sessionId", sess):
            errs.append(
                "C6: setSession re-points downloads without checking the session actually "
                "changed (a same-id / first bind must stay a byte-identical no-op)")
        if "_downloads.value =" in sess:
            errs.append("C6: setSession rewrites _downloads by plain assignment (use _downloads.update { })")
        if not (re.search(r"remap\w*Download\w*\s*\(", sess)
                or "updateDownloadEntry(" in sess
                or "_downloads.update {" in sess):
            errs.append(
                "C6: setSession never re-points download destinations onto the new session "
                "workspace — the draft-promotion path leaves every record pointing at the "
                "deleted __new__<uuid> directory and its open button dies silently")
    return errs


# --- [fix/dv14-B2] C4 fixtures (pool teardown ordering) ---
# Mirrors the production shapes at the decision points only: capture url →
# destroyTab → store; dispose releases without re-persisting.

POOL_COMPLIANT = """package com.rikkaminis.app.browser

class BrowserTabPool(context: Context) {
    private val savedURLs = mutableMapOf<Int, String>()
    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())

    // [dv24-B4 B-07] C4e/C4f: the pool's async faces and the latch that
    // disposal owns (mirrors the production shapes at the decision points).
    @Volatile
    private var isDisposed = false

    private val evictionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun destroyTab(tabs: MutableList<Tab>, tab: Tab) {
        tabs.remove(tab)
        // destroyTab removes the entry — storing before this call loses it.
        savedURLs.remove(tab.id)
        tab.manager.destroy()
    }

    fun dispose() {
        if (isDisposed) return
        isDisposed = true
        evictionScope.cancel()
        downloadScope.cancel()
        evictionJob?.cancel()
        evictionJob = null
        val current = _tabs.value
        if (current.isNotEmpty()) {
            val mutable = current.toMutableList()
            for (tab in current) {
                destroyTab(mutable, tab)
            }
            _tabs.value = emptyList()
        }
    }

    private fun saveState() {
        if (isDisposed) return
        val file = File(context.filesDir, "browser_tabs/$sessionId.json")
        file.writeText("{}")
    }

    private fun restoreSavedTabs() {
        if (isDisposed) return
        val currentTabs = _tabs.value.toMutableList()
        for (id in plan.restoreIds) {
            val url = savedURLs[id] ?: continue
            createTab(currentTabs, url, explicitId = id) ?: break
        }
        if (restored > 0) {
            saveState()
        }
    }

    override fun onTrimMemory(level: Int) {
        val victims = _tabs.value.filter { isLongIdle(it) }
        val currentTabs = _tabs.value.toMutableList()
        for (tab in victims) {
            val url = tab.manager.currentURL.value
            destroyTab(currentTabs, tab)
            rememberEvictedUrl(url)?.let { savedURLs[tab.id] = it }
        }
        _tabs.value = currentTabs
        saveState()
    }

    fun evictIdleTabs() {
        val currentTabs = _tabs.value.toMutableList()
        val toRemove = currentTabs.filter { !it.inUse }
        for (tab in toRemove) {
            val url = tab.manager.currentURL.value
            destroyTab(currentTabs, tab)
            rememberEvictedUrl(url)?.let { savedURLs[tab.id] = it }
        }
        if (toRemove.isNotEmpty()) {
            _tabs.value = currentTabs
            saveState()
        }
    }

    // [fix/dv16-B7] C4d: window.open / window.close persist like every other
    // mutation path (mirrors the production shapes at the decision points).
    private fun handleNewWindow(resultMsg: Message) {
        val currentTabs = _tabs.value.toMutableList()
        if (currentTabs.size >= MAX_TABS) {
            return
        }
        val id = nextTabId++
        currentTabs.add(Tab(id = id, manager = manager))
        _tabs.value = currentTabs
        _selectedTabId.value = id
        saveState()
        resultMsg.sendToTarget()
    }

    private fun handleCloseWindow(manager: BrowserUseManager) {
        val currentTabs = _tabs.value.toMutableList()
        val idx = currentTabs.indexOfFirst { it.manager === manager }
        if (idx >= 0) {
            val closedId = currentTabs[idx].id
            destroyTab(currentTabs, currentTabs[idx])
            _tabs.value = currentTabs
            if (_selectedTabId.value == closedId && currentTabs.isNotEmpty()) {
                _selectedTabId.value = currentTabs.first().id
            }
            saveState()
        }
    }

    // [fix/dv17-1 B-01] C5: the challenge must be rendered AND merged into the
    // result text (mirrors the production shapes at the decision points).
    private suspend fun runAcquiredAction(input: String): String {
        val result = execute(input)
        val riskChallenge = detectChallenge(input)
        val stamped = stampTabId(result, 0)
        if (riskChallenge != null) {
            val notice = BrowserRiskControl.challengeNoticeLine(
                kind = riskChallenge.kind,
                recommendedNextAction = riskChallenge.recommendedNextAction,
                throttleDelayMs = 0L,
            )
            val text = if (notice != null) {
                stamped.text + "\\n" + notice
            } else {
                stamped.text
            }
            return stamped.copy(text = text, riskChallengeDetected = true).text
        }
        return stamped.text
    }

    // [fix/dv17-1 B-06] C6: capture the old id BEFORE rebinding, guard on an
    // actual change, re-point through the atomic update; loadSavedState() last.
    fun setSession(sessionId: String) {
        val previous = this.sessionId
        this.sessionId = sessionId
        if (previous != null && previous != sessionId) {
            _downloads.update { current ->
                remapDownloadDestinations(
                    entries = current,
                    oldDir = File(filesDir, "minis-sessions/$previous/workspace"),
                    newDir = File(filesDir, "minis-sessions/$sessionId/workspace"),
                    pathOf = { entry -> entry.destination },
                    withPath = { entry, path -> entry.copy(destination = path) },
                )
            }
        }
        _tabs.value.forEach { it.manager.minisSessionId = sessionId }
        loadSavedState()
    }
}
"""

# C5: the notice renderer is called but its product never reaches the text —
# exactly the "written and dropped" defect this criterion exists for.
POOL_V_RISK_NOT_MERGED = POOL_COMPLIANT.replace(
    """            val text = if (notice != null) {
                stamped.text + "\\n" + notice
            } else {
                stamped.text
            }
            return stamped.copy(text = text, riskChallengeDetected = true).text""",
    "            return stamped.copy(riskChallengeDetected = true).text",
)

# [wide3-06 C5] The renderer's product is BOUND and then dropped: the `text =`
# write still exists, it just no longer reads `notice` (the witness verbatim).
POOL_V_RISK_DROPPED = POOL_COMPLIANT.replace(
    """            val text = if (notice != null) {
                stamped.text + "\\n" + notice
            } else {
                stamped.text
            }""",
    "            val text = stamped.text",
)

# C5: no renderer call at all (regression to the pre-fix shape).
POOL_V_RISK_NO_CALL = POOL_COMPLIANT.replace(
    """            val notice = BrowserRiskControl.challengeNoticeLine(
                kind = riskChallenge.kind,
                recommendedNextAction = riskChallenge.recommendedNextAction,
                throttleDelayMs = 0L,
            )
""", "")

# C6: baseline shape — no capture, no remap (the draft promotion defect).
POOL_V_SESSION_NO_REMAP = POOL_COMPLIANT.replace(
    """        val previous = this.sessionId
        this.sessionId = sessionId
        if (previous != null && previous != sessionId) {
            _downloads.update { current ->
                remapDownloadDestinations(
                    entries = current,
                    oldDir = File(filesDir, "minis-sessions/$previous/workspace"),
                    newDir = File(filesDir, "minis-sessions/$sessionId/workspace"),
                    pathOf = { entry -> entry.destination },
                    withPath = { entry, path -> entry.copy(destination = path) },
                )
            }
        }
""",
    "        this.sessionId = sessionId\n",
)

# C6: the remap exists but goes through a plain assignment (races the writers).
POOL_V_SESSION_PLAIN_ASSIGN = POOL_COMPLIANT.replace(
    "            _downloads.update { current ->\n",
    "            _downloads.value = _downloads.value\n            _downloads.update { current ->\n",
)

# C4a: the pre-fix dispose re-persisted the (possibly just-deleted) session.
POOL_V_DISPOSE_SAVE = POOL_COMPLIANT.replace(
    "            _tabs.value = emptyList()\n        }\n    }\n\n    private fun saveState() {",
    "            _tabs.value = emptyList()\n        }\n        saveState()\n    }\n\n    private fun saveState() {",
)

# C4c: URL stored BEFORE destroyTab — silently wiped by its remove().
POOL_V_TRIM_ORDER = POOL_COMPLIANT.replace(
    "        for (tab in victims) {\n"
    "            val url = tab.manager.currentURL.value\n"
    "            destroyTab(currentTabs, tab)\n"
    "            rememberEvictedUrl(url)?.let { savedURLs[tab.id] = it }\n",
    "        for (tab in victims) {\n"
    "            val url = tab.manager.currentURL.value\n"
    "            rememberEvictedUrl(url)?.let { savedURLs[tab.id] = it }\n"
    "            destroyTab(currentTabs, tab)\n",
)

# C4c: evict path forgets the evicted tab's URL entirely.
POOL_V_EVICT_NO_STORE = POOL_COMPLIANT.replace(
    "        for (tab in toRemove) {\n"
    "            val url = tab.manager.currentURL.value\n"
    "            destroyTab(currentTabs, tab)\n"
    "            rememberEvictedUrl(url)?.let { savedURLs[tab.id] = it }\n"
    "        }\n",
    "        for (tab in toRemove) {\n"
    "            destroyTab(currentTabs, tab)\n"
    "        }\n",
)

# C4b: dispose keeps re-persisting nothing but stops releasing the tabs.
POOL_V_DISPOSE_NO_DESTROY = POOL_COMPLIANT.replace(
    "            for (tab in current) {\n                destroyTab(mutable, tab)\n            }\n",
    "            for (tab in current) {\n                Log.i(TAG, \"dispose(): destroyed tab ${tab.id}\")\n            }\n",
)

# [wide3-06 C4f] A default *lambda* argument carries a decoy guard: the first
# `{` after the name is the parameter's body, not the function's (witness C4f).
POOL_V_RESTORE_LAMBDA_PARAM = POOL_COMPLIANT.replace(
    """    private fun restoreSavedTabs() {
        if (isDisposed) return
""",
    """    private fun restoreSavedTabs(onDone: () -> Unit = { if (isDisposed) return }) {
""",
)

# [fix/dv16-B7] C4d: window.open forgets to persist the new tab state.
POOL_V_NEWWIN_NOSAVE = POOL_COMPLIANT.replace(
    "        _selectedTabId.value = id\n        saveState()\n        resultMsg.sendToTarget()",
    "        _selectedTabId.value = id\n        resultMsg.sendToTarget()",
)

# [fix/dv16-B7] C4d: window.close forgets to persist the teardown.
POOL_V_CLOSEWIN_NOSAVE = POOL_COMPLIANT.replace(
    "            if (_selectedTabId.value == closedId && currentTabs.isNotEmpty()) {\n"
    "                _selectedTabId.value = currentTabs.first().id\n"
    "            }\n"
    "            saveState()\n",
    "            if (_selectedTabId.value == closedId && currentTabs.isNotEmpty()) {\n"
    "                _selectedTabId.value = currentTabs.first().id\n"
    "            }\n",
)

# --- [dv24-B4 B-07] C4e/C4f: disposal owns the pool's async faces ----------
# The defect was a scope that outlived its pool: disposing cancelled only the
# eviction ticker, so a restore already dispatched to Main rebuilt WebViews on
# a destroyed pool and re-persisted a just-deleted session's tab file, and
# in-flight downloads kept writing into the deleted session's workspace.

# C4e: the eviction scope survives disposal (restore queue keeps draining).
POOL_V_DISPOSE_NO_EVICTION_CANCEL = POOL_COMPLIANT.replace(
    "        evictionScope.cancel()\n", "",
)

# C4e: the download scope survives disposal (writes + callbacks keep running).
POOL_V_DISPOSE_NO_DOWNLOAD_CANCEL = POOL_COMPLIANT.replace(
    "        downloadScope.cancel()\n", "",
)

# C4e negative control: cancelling through a helper must stay GREEN — the
# criterion is anchored on which scopes end up cancelled, not on the spelling.
POOL_HELPER_CANCEL = POOL_COMPLIANT.replace(
    "        evictionScope.cancel()\n        downloadScope.cancel()\n",
    "        destroyScopes()\n",
).replace(
    "    fun dispose() {\n        if (isDisposed) return\n",
    "    private fun destroyScopes() {\n"
    "        evictionScope.cancel()\n"
    "        downloadScope.cancel()\n"
    "    }\n\n"
    "    fun dispose() {\n        if (isDisposed) return\n",
)

# C4f: the restore keeps rebuilding on a disposed pool (guard dropped).
POOL_V_RESTORE_NO_GUARD = POOL_COMPLIANT.replace(
    "    private fun restoreSavedTabs() {\n        if (isDisposed) return\n",
    "    private fun restoreSavedTabs() {\n",
)

# C4f: only restore is guarded — the late saveState still resurrects the file.
POOL_V_SAVE_NO_GUARD = POOL_COMPLIANT.replace(
    "    private fun saveState() {\n        if (isDisposed) return\n",
    "    private fun saveState() {\n",
)

# C4f: the latch is not @Volatile — the Main-dispatched restore may read stale.
POOL_V_LATCH_NOT_VOLATILE = POOL_COMPLIANT.replace(
    "    @Volatile\n    private var isDisposed = false\n",
    "    private var isDisposed = false\n",
)

# C4f: no latch at all — dispose re-runs its release half on a second call.
POOL_V_NO_LATCH = POOL_COMPLIANT.replace(
    "    @Volatile\n    private var isDisposed = false\n\n", "",
).replace(
    "        if (isDisposed) return\n        isDisposed = true\n", "",
)


# --- [da1040-B2] C9: the busy signal must be observable -------------------
# The defect: `Tab.inUse` flips in place (audit-b21 forbids the copy that would
# make the list change), so `tabs` stays content-equal and StateFlow swallows
# the emission; a sheet reading a plain getter composed once and froze.

def check_busy_source(pool_src: str, sheet_src: str) -> list:
    """[da1040-B2] C9 — one funnel for in-use writes, one reactive carrier, and
    a consumer that subscribes. Fail-closed throughout."""
    code = strip_comments(pool_src)
    errs = []

    # C9a: the reactive face exists and no non-reactive getter is back
    if not re.search(r"private val _agentBusy = MutableStateFlow\(", code):
        errs.append("C9: no _agentBusy state holder (fail-closed)")
    if "val agentBusy: StateFlow<Boolean> = _agentBusy.asStateFlow()" not in code:
        errs.append(
            "C9: the pool does not expose a reactive agentBusy StateFlow — a busy flip "
            "cannot reach a collector, so the UI composes once and freezes")
    if "val isAgentBusy" in code:
        errs.append(
            "C9: a non-reactive isAgentBusy getter is back — a consumer reading it never "
            "recomposes when the agent starts or stops working")

    # C9b: the predicate has exactly one carrier, re-derived from the live list
    pub = slice_body(code, "private fun publishAgentBusy")
    if not pub:
        errs.append("C9: publishAgentBusy body not extractable (fail-closed)")
    else:
        assign = re.search(r"_agentBusy\s*\.\s*value\s*=", pub)
        rhs = _stmt_tail(pub, assign.end()) if assign else ""
        if not assign:
            errs.append(
                "C9: publishAgentBusy never assigns `_agentBusy.value` (fail-closed)")
        elif "_tabs.value.any { it.inUse }" not in rhs:
            errs.append(
                "C9: publishAgentBusy no longer re-derives from any-tab-in-use — the "
                "published flag can drift from Tab.inUse (fail-closed)")
        elif _cond_dead_text(rhs):
            # [wide3-06 C9] `false && _tabs.value.any { it.inUse }` keeps the
            # derivation's spelling and publishes a constant false: the sheet
            # then offers to close tabs an agent is driving.
            errs.append(
                "C9: the published busy flag is short-circuited by a literal "
                "(`false && …`) — the derivation is spelled but never decides, so "
                "agentBusy is frozen at false while a tab is in use")

    # C9c: exactly one raw inUse write site, and it is inside the funnel
    writes = re.findall(r"\w+\.inUse\s*=\s*\w+", code)
    if len(writes) != 1:
        errs.append(
            f"C9: {len(writes)} raw Tab.inUse write sites in the pool (expected exactly 1, "
            "inside setTabInUse) — a write that bypasses the funnel cannot publish")
    funnel = slice_body(code, "private fun setTabInUse")
    if not funnel:
        errs.append("C9: setTabInUse body not extractable (fail-closed)")
    elif "publishAgentBusy()" not in funnel:
        errs.append("C9: setTabInUse does not publish the busy flag")

    # C9d: list publication re-derives too (membership moves with no inUse write)
    upd = slice_body(code, "private fun updateTabs")
    if not upd:
        errs.append("C9: updateTabs body not extractable (fail-closed)")
    elif "publishAgentBusy()" not in upd:
        errs.append("C9: updateTabs does not re-derive the busy flag")

    # C9e: audit-b21 red line — the release path must keep the live Tab instance
    if re.search(r"copy\([^)]*inUse", code):
        errs.append(
            "C9: the in-use flip copies the Tab (audit-b21: the copy breaks the identity "
            "link to in-flight actions, whose release paths then mutate the object the "
            "pool no longer holds)")

    # C9f: the consumer subscribes
    sheet = strip_comments(sheet_src)
    if not re.search(r"by\s+tabPool\.agentBusy\.collectAsState\(\)", sheet):
        errs.append(
            "C9: BrowserSheet does not subscribe to the pool's busy flow "
            "(expected `by tabPool.agentBusy.collectAsState()`)")
    if "tabPool.isAgentBusy" in sheet:
        errs.append("C9: BrowserSheet still reads the non-reactive busy getter")
    return errs

# --- [dv22-B2] C10: every destroy path re-derives the busy flag ---
# Membership is DERIVED FROM THE TREE, never a hardcoded caller list: every
# function whose body calls destroyTab( is a destroy path and must settle its
# published list (`_tabs.value = …`) and then re-derive the busy flag
# (publishAgentBusy(), or routed through updateTabs()) AFTER that last write.
# A future 7th destroy caller is therefore checked by construction and fails
# closed if it forgets — the gate cannot go stale by omitting a member
# (deep-audit-1041 §8.7 rule 11: a written-out member list is the blindness).
# destroyTab itself stays a pure teardown: it receives the caller's LOCAL list
# (not yet written back), so a publish inside it would derive from the stale
# `_tabs.value`; dispose() also writes its list after the destroy loop, out of
# destroyTab's reach. That division of labour is pinned here too.

C10_PUBLISH_SPELLINGS = ("publishAgentBusy()", "updateTabs(")


def _c10_slice_fun(code: str, header: str) -> str:
    """Body of a `fun` whose body starts with `{` — expression-bodied functions
    (`fun onConfigurationChanged(...): T = Unit`) are SKIPPED, because a
    brace-matching slice would run past them and swallow the rest of the
    class, attributing another function's destroyTab( call to them."""
    idx = code.find(header)
    if idx < 0:
        return ""
    i = idx + len(header)
    # The header ends with an unclosed "(" — skip to its matching ")".
    depth_paren = 1
    while i < len(code) and depth_paren > 0:
        c = code[i]
        if c == "(":
            depth_paren += 1
        elif c == ")":
            depth_paren -= 1
        i += 1
    # Skip a return type / generic tail up to the body brace; an expression
    # body (": T = Unit") has no brace on this line -> not a block body.
    while i < len(code):
        c = code[i]
        if c == "{":
            break
        if c == "\n":
            return ""
        i += 1
    if i >= len(code) or code[i] != "{":
        return ""
    depth = 0
    j = i
    while j < len(code):
        c = code[j]
        if c == '"':
            j += 1
            while j < len(code):
                if code[j] == "\\":
                    j += 2
                    continue
                if code[j] == '"':
                    break
                j += 1
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return code[i:j + 1]
        j += 1
    return ""


def _c10_destroy_callers(code: str) -> list:
    """Every `fun name(` whose body calls destroyTab( — definition excluded."""
    out = []
    for m in re.finditer(r"fun\s+([A-Za-z_]\w*)\s*\(", code):
        name = m.group(1)
        if name == "destroyTab":
            continue  # the teardown itself, not a caller
        body = _c10_slice_fun(code, m.group(0))
        if body and "destroyTab(" in body:
            out.append((name, body))
    return out


def check_destroy_busy_source(pool_src: str) -> list:
    """[dv22-B2] C10 — destroy paths re-derive agentBusy. Fail-closed."""
    code = strip_comments(pool_src)
    errs = []

    callers = _c10_destroy_callers(code)
    if not callers:
        errs.append(
            "C10: no function body calling destroyTab( was found — the destroy "
            "funnel vanished or the bodies stopped being extractable "
            "(fail-closed; update this gate)")
        return errs

    # Coverage cross-check: every destroyTab( call site outside the definition
    # must sit inside one of the caller bodies above. A call in a property
    # initializer or a top-level lambda would otherwise go unchecked while the
    # gate still reports green for the paths it did find.
    total_calls = len(re.findall(r"destroyTab\(", code)) - 1  # minus the definition
    owned_calls = sum(b.count("destroyTab(") for _, b in callers)
    if total_calls != owned_calls:
        errs.append(
            f"C10: {total_calls} destroyTab( call site(s) in the pool but only "
            f"{owned_calls} attributed to a caller body — an unchecked destroy "
            "path exists (fail-closed; keep teardown calls inside a named fun "
            "so this gate can see them)")

    for name, body in callers:
        writes = [m.start() for m in re.finditer(r"_tabs\.value\s*=(?!=)", body)]
        if not writes:
            errs.append(
                f"C10: {name} destroys a tab but never settles the published list "
                "(no `_tabs.value =` write) — the destroyed tab stays in the "
                "flow (fail-closed; update this gate if the list write moved)")
            continue
        last_write = max(writes)
        pubs = [body.find(sp) for sp in C10_PUBLISH_SPELLINGS]
        pubs = [p for p in pubs if p >= 0]
        if not pubs:
            errs.append(
                f"C10: {name} destroys a tab but never re-derives the busy flag "
                "(no publishAgentBusy()/updateTabs() in its body) — agentBusy "
                "freezes at its pre-destroy value, so closing the last in-use "
                "tab leaves the sheet stuck on \"agent working\"")
        elif not any(p > last_write for p in pubs):
            errs.append(
                f"C10: {name} re-derives the busy flag before its last "
                "_tabs.value write — the derivation has to see the post-destroy "
                "list or it publishes a stale value")

    dt = slice_body(code, "fun destroyTab")
    if not dt:
        errs.append("C10: destroyTab body not extractable (fail-closed)")
    elif any(sp in dt for sp in C10_PUBLISH_SPELLINGS):
        errs.append(
            "C10: destroyTab publishes the busy flag itself — it holds the "
            "caller's local list, not _tabs.value, so the derivation is stale "
            "by construction (and dispose() writes its list after the destroy "
            "loop, out of its reach); publish at the caller's list write")
    return errs


# --- [dv22-B2] C10 fixtures: the shipped destroy paths at the decision points
# only (destroy → settle list → re-derive). Breakers are GENERATED from this
# fixture's own derived caller set, so "one arm per destroy path" holds even if
# the fixture grows; each breaker removes exactly one re-derivation.
DESTROY_COMPLIANT = """package com.rikkaminis.app.browser

class BrowserTabPool {
    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    private val _agentBusy = MutableStateFlow(false)

    private fun publishAgentBusy() {
        _agentBusy.value = _tabs.value.any { it.inUse }
    }

    private fun updateTabs() {
        _tabs.value = _tabs.value.toList()
        publishAgentBusy()
    }

    private fun destroyTab(tabs: MutableList<Tab>, tab: Tab) {
        tabs.remove(tab)
        tab.manager.destroy()
    }

    private suspend fun closeTab(tabId: Int) {
        val currentTabs = _tabs.value.toMutableList()
        destroyTab(currentTabs, currentTabs[0])
        _tabs.value = currentTabs
        publishAgentBusy()
    }

    suspend fun closeTabFromUI(tabId: Int) {
        val currentTabs = _tabs.value.toMutableList()
        destroyTab(currentTabs, currentTabs[0])
        _tabs.value = currentTabs
        publishAgentBusy()
    }

    private fun handleCloseWindow(manager: Manager) {
        val currentTabs = _tabs.value.toMutableList()
        destroyTab(currentTabs, currentTabs[0])
        _tabs.value = currentTabs
        publishAgentBusy()
    }

    fun dispose() {
        val current = _tabs.value
        if (current.isNotEmpty()) {
            val mutable = current.toMutableList()
            for (tab in current) {
                destroyTab(mutable, tab)
            }
            _tabs.value = emptyList()
            publishAgentBusy()
        }
    }

    override fun onTrimMemory(level: Int) {
        val currentTabs = _tabs.value.toMutableList()
        for (tab in currentTabs.toList()) {
            destroyTab(currentTabs, tab)
        }
        _tabs.value = currentTabs
        publishAgentBusy()
    }

    fun evictIdleTabs() {
        val currentTabs = _tabs.value.toMutableList()
        for (tab in currentTabs.toList()) {
            destroyTab(currentTabs, tab)
        }
        if (currentTabs.size != _tabs.value.size) {
            _tabs.value = currentTabs
            publishAgentBusy()
        }
    }
}
"""


def _c10_body_span(src: str, name: str):
    m = re.search(r"fun\s+%s\s*\(" % re.escape(name), src)
    if not m:
        raise AssertionError(f"C10 fixture lost `fun {name}(`")
    body = slice_body(src[m.start():], f"fun {name}(")
    if not body:
        raise AssertionError(f"C10 fixture body unextractable: {name}")
    start = src.index(body, m.start())
    return start, start + len(body)


def _c10_strip_publish(src: str, name: str) -> str:
    """Delete the last re-derivation statement inside `name`'s body."""
    start, end = _c10_body_span(src, name)
    body = src[start:end]
    at = body.rfind("publishAgentBusy()")
    if at < 0:
        raise AssertionError(f"C10 fixture has no publish in {name}")
    line_start = body.rfind("\n", 0, at)
    new_body = body[:line_start] + body[at + len("publishAgentBusy()"):]
    if new_body == body:
        raise AssertionError(f"C10 breaker for {name} did not change the source")
    return src[:start] + new_body + src[end:]


def _c10_publish_before_write(src: str, name: str) -> str:
    """Move the re-derivation above the list write (stale-value shape)."""
    start, end = _c10_body_span(src, name)
    body = src[start:end]
    at = body.rfind("publishAgentBusy()")
    line_start = body.rfind("\n", 0, at)
    line_end = body.find("\n", at)
    if line_end < 0:
        line_end = len(body)
    stmt = body[line_start:line_end]
    rest = body[:line_start] + body[line_end:]
    write_at = rest.rfind("_tabs.value =")
    write_line_start = rest.rfind("\n", 0, write_at) + 1
    new_body = rest[:write_line_start] + stmt.lstrip("\n") + "\n" + rest[write_line_start:]
    if new_body == body:
        raise AssertionError(f"C10 order breaker for {name} did not change the source")
    return src[:start] + new_body + src[end:]


# 7th destroy caller added to the fixture without a publish: the derived set
# must catch it although the gate never names it (blindness-by-list regression).
DESTROY_V_NEW_CALLER_BLIND = DESTROY_COMPLIANT.replace(
    "    fun evictIdleTabs() {",
    "    fun closeTabFromTray(tabId: Int) {\n"
    "        val currentTabs = _tabs.value.toMutableList()\n"
    "        destroyTab(currentTabs, currentTabs[0])\n"
    "        _tabs.value = currentTabs\n"
    "    }\n\n    fun evictIdleTabs() {",
)
# A destroy call the gate cannot attribute to any body (property initializer):
# the coverage cross-check must go red instead of quietly checking six paths.
DESTROY_V_UNATTRIBUTED_CALL = DESTROY_COMPLIANT.replace(
    "    private suspend fun closeTab(tabId: Int) {",
    "    private val eagerKill: Unit = destroyTab(_tabs.value.toMutableList(), tab0)\n\n"
    "    private suspend fun closeTab(tabId: Int) {",
)

BUSY_COMPLIANT = """package com.rikkaminis.app.browser

class BrowserTabPool(context: Context) {
    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabs: StateFlow<List<Tab>> = _tabs.asStateFlow()
    private val _agentBusy = MutableStateFlow(false)
    val agentBusy: StateFlow<Boolean> = _agentBusy.asStateFlow()

    private fun publishAgentBusy() {
        _agentBusy.value = _tabs.value.any { it.inUse }
    }

    private fun setTabInUse(tab: Tab, inUse: Boolean) {
        tab.inUse = inUse
        publishAgentBusy()
    }

    fun releaseAllTabs() {
        _tabs.value = _tabs.value.map { tab ->
            setTabInUse(tab, false)
            tab
        }
        publishAgentBusy()
    }

    private fun updateTabs() {
        _tabs.value = _tabs.value.toList()
        publishAgentBusy()
    }
}
"""
BUSY_SHEET_COMPLIANT = """package com.rikkaminis.app.ui.browser

@Composable
fun BrowserSheet(tabPool: BrowserTabPool) {
    val isAgentBusy by tabPool.agentBusy.collectAsState()
    Button(enabled = !isAgentBusy) { Text("x") }
}
"""
BUSY_V_NO_FLOW = BUSY_COMPLIANT.replace(
    "    val agentBusy: StateFlow<Boolean> = _agentBusy.asStateFlow()\n", "")
BUSY_V_GETTER_BACK = BUSY_COMPLIANT.replace(
    "    val agentBusy: StateFlow<Boolean> = _agentBusy.asStateFlow()",
    "    val agentBusy: StateFlow<Boolean> = _agentBusy.asStateFlow()\n"
    "    val isAgentBusy: Boolean get() = _agentBusy.value")
BUSY_V_STALE_PUBLISH = BUSY_COMPLIANT.replace(
    "        _agentBusy.value = _tabs.value.any { it.inUse }",
    "        _agentBusy.value = true")
BUSY_V_WRITE_BYPASSES_FUNNEL = BUSY_COMPLIANT.replace(
    "        _tabs.value = _tabs.value.map { tab ->\n"
    "            setTabInUse(tab, false)\n"
    "            tab\n"
    "        }",
    "        _tabs.value = _tabs.value.map { tab ->\n"
    "            tab.inUse = false\n"
    "            tab\n"
    "        }")
BUSY_V_FUNNEL_NO_PUBLISH = BUSY_COMPLIANT.replace(
    "    private fun setTabInUse(tab: Tab, inUse: Boolean) {\n"
    "        tab.inUse = inUse\n"
    "        publishAgentBusy()\n"
    "    }",
    "    private fun setTabInUse(tab: Tab, inUse: Boolean) {\n"
    "        tab.inUse = inUse\n"
    "    }")
BUSY_V_UPDATETABS_NO_PUBLISH = BUSY_COMPLIANT.replace(
    "    private fun updateTabs() {\n"
    "        _tabs.value = _tabs.value.toList()\n"
    "        publishAgentBusy()\n"
    "    }",
    "    private fun updateTabs() {\n"
    "        _tabs.value = _tabs.value.toList()\n"
    "    }")
BUSY_V_COPY_INSTANCE = BUSY_COMPLIANT.replace(
    "        tab.inUse = inUse\n        publishAgentBusy()",
    "        publishAgentBusy()").replace(
    "            setTabInUse(tab, false)",
    "            val cleared = tab.copy(inUse = false)")
BUSY_V_NO_PUBLISHER = BUSY_COMPLIANT.replace(
    "    private fun publishAgentBusy() {\n"
    "        _agentBusy.value = _tabs.value.any { it.inUse }\n"
    "    }\n\n", "")
BUSY_SHEET_V_GETTER = BUSY_SHEET_COMPLIANT.replace(
    "    val isAgentBusy by tabPool.agentBusy.collectAsState()",
    "    val isAgentBusy = tabPool.isAgentBusy")
# [wide3-06 C9] The derivation is SPELLED and short-circuited: `false && …`
# keeps the exact expression the old criterion grepped for (witness C9).
BUSY_V_SHORT_CIRCUIT = BUSY_COMPLIANT.replace(
    "        _agentBusy.value = _tabs.value.any { it.inUse }",
    "        _agentBusy.value = false && _tabs.value.any { it.inUse }")

busy_arms = [
    ("C9 compliant busy signal", BUSY_COMPLIANT, BUSY_SHEET_COMPLIANT, []),
    ("C9a reactive face removed", BUSY_V_NO_FLOW, BUSY_SHEET_COMPLIANT, ["C9"]),
    ("C9a non-reactive getter restored", BUSY_V_GETTER_BACK, BUSY_SHEET_COMPLIANT, ["C9"]),
    ("C9b publish no longer re-derived", BUSY_V_STALE_PUBLISH, BUSY_SHEET_COMPLIANT, ["C9"]),
    ("C9b publish short-circuited by a literal", BUSY_V_SHORT_CIRCUIT, BUSY_SHEET_COMPLIANT, ["C9"]),
    ("C9c write bypasses the funnel", BUSY_V_WRITE_BYPASSES_FUNNEL, BUSY_SHEET_COMPLIANT, ["C9"]),
    ("C9c funnel stops publishing", BUSY_V_FUNNEL_NO_PUBLISH, BUSY_SHEET_COMPLIANT, ["C9"]),
    ("C9d updateTabs stops re-deriving", BUSY_V_UPDATETABS_NO_PUBLISH, BUSY_SHEET_COMPLIANT, ["C9"]),
    ("C9e release copies the Tab (audit-b21)", BUSY_V_COPY_INSTANCE, BUSY_SHEET_COMPLIANT, ["C9"]),
    ("C9b publisher missing (fail-closed)", BUSY_V_NO_PUBLISHER, BUSY_SHEET_COMPLIANT, ["C9"]),
    ("C9f sheet reads the getter", BUSY_COMPLIANT, BUSY_SHEET_V_GETTER, ["C9"]),
]

# --- [dv23-B4 B-04] C11: a context-only bind must not persist ---------------
# The defect: the process-wide shared pool borrowed the requesting session's id
# through bindSessionContextOnly (so minis:// resolves and downloads land in
# that session) and every saveState() then wrote the driver's cross-session tab
# list into that session's filesDir/browser_tabs/<sid>.json — resurrecting
# deleted sessions and overwriting the chat session's own tabs.

CTX_COMPLIANT = """package com.rikkaminis.app.browser

class BrowserTabPool(private val context: Context) {
    private var isContextOnlySession: Boolean = false

    fun setSession(sessionId: String) {
        isContextOnlySession = false
        val dir = File(context.filesDir, "browser_tabs")
        saveState()
    }

    fun bindSessionContextOnly(sessionId: String) {
        this.sessionId = sessionId
        isContextOnlySession = true
    }

    private fun saveState() {
        if (isContextOnlySession) return
        val dir = File(context.filesDir, "browser_tabs")
    }
}
"""

# C11 breaker: the guard is gone, so a borrowing pool persists again.
CTX_V_PERSIST = CTX_COMPLIANT.replace(
    "        if (isContextOnlySession) return\n", "",
)

# C11 breaker: a full bind never takes ownership back.
CTX_V_NO_CLEAR = CTX_COMPLIANT.replace(
    "        isContextOnlySession = false\n", "",
)

# [wide3-06 C11] The guard is wrapped in a statically-dead block: the substring
# and its position are untouched, the decision is gone (witness C11).
CTX_V_DEAD_GUARD = CTX_COMPLIANT.replace(
    "        if (isContextOnlySession) return\n",
    "        if (false) { if (isContextOnlySession) return }\n",
)


def check_context_only_persist(pool_src: str) -> list:
    """[dv23-B4 B-04] C11 — the borrow latch is set on the context-only bind,
    cleared by a full bind, and consulted before saveState touches the file.
    Anchored on the DECISION (return before the write), not on spelling of the
    surrounding code; fail-closed everywhere."""
    code = strip_comments(pool_src)
    if "isContextOnlySession" not in code:
        return ["C11: the borrow latch is gone — context-only binds persist again"]
    errs = []
    ctx = slice_body(code, "fun bindSessionContextOnly")
    if not ctx:
        errs.append("C11: fun bindSessionContextOnly body not extractable (fail-closed)")
    elif "isContextOnlySession = true" not in ctx:
        errs.append("C11: bindSessionContextOnly no longer latches the borrow")
    full = slice_body(code, "fun setSession(")
    if not full:
        errs.append("C11: fun setSession( body not extractable (fail-closed)")
    else:
        if "isContextOnlySession = false" not in full:
            errs.append("C11: a full session bind does not take ownership back (stale latch)")
        if "isContextOnlySession = true" in full:
            errs.append("C11: a full session bind latches the borrow (it owns the session)")
    save = slice_body(code, "private fun saveState")
    if not save:
        errs.append("C11: fun saveState body not extractable (fail-closed)")
    else:
        # [wide3-06 C11] A guard wrapped in `if (false) { … }` still contains
        # the substring and still precedes the write — skipped.
        spans = _dead_spans(save)
        guard = -1
        for m in re.finditer(r"\bif\s*\(\s*isContextOnlySession\s*\)\s*return\b", save):
            if not _pos_dead(save, m.start(), spans):
                guard = m.start()
                break
        if guard < 0:
            errs.append(
                "C11: saveState writes regardless of the borrow latch (no live "
                "`if (isContextOnlySession) return` before the tab file)")
        else:
            touches = [
                i for i in (save.find("browser_tabs"), save.find("getSharedPreferences"))
                if i >= 0
            ]
            first = min(touches) if touches else -1
            if first < 0 or guard > first:
                errs.append("C11: the borrow guard sits after the tab file is touched")
    return errs


def selftest() -> int:
    compliant = """package com.rikkaminis.app.data.storage

/**
 * Singular owner of the on-disk footprint attached to a chat session.
 * The doc mentions browser_tabs/<sid>.json and browserTabFile( freely —
 * block comments are stripped, so none of this satisfies any criterion.
 */
private const val DRAFT_ID_PREFIX = "__new__"

class SessionFileStore internal constructor(
    private val filesDirForTestingOrProd: File,
    private val activeDraftIds: () -> Set<String> = { emptySet() },
) {
    constructor(context: Context) : this(
        context.filesDir,
        { ComposerDraftStore.activeDraftIds(context) },
    )
    val sessionsRoot: File = File(filesDirForTestingOrProd, "minis-sessions")
    private val mediaRoot: File = File(filesDirForTestingOrProd, "media")
    private val browserTabsRoot: File = File(filesDirForTestingOrProd, "browser_tabs")

    fun sessionDir(sessionId: String): File = File(sessionsRoot, sessionId)
    fun browserTabFile(sessionId: String): File = File(browserTabsRoot, "$sessionId.json")

    private fun looksLikeSessionId(name: String): Boolean {
        val uuid = if (name.startsWith(DRAFT_ID_PREFIX)) name.substring(DRAFT_ID_PREFIX.length) else name
        return uuid.length == 36 && uuid.count { it == '-' } == 4
    }

    private fun withActiveComposerOwner(liveSessionIds: Set<String>): Set<String> {
        val draftIds = activeDraftIds()
        if (draftIds.isEmpty()) return liveSessionIds
        return liveSessionIds + draftIds
    }

    fun scanMediaOnce(liveSessionIds: Set<String>): MediaScan {
        val live = withActiveComposerOwner(liveSessionIds)
        return MediaScan(emptyMap(), 0, 0L)
    }

    fun scanOrphanSessionDirs(liveSessionIds: Set<String>): ReclaimReport {
        val live = withActiveComposerOwner(liveSessionIds)
        return ReclaimReport()
    }

    fun scanOrphans(liveSessionIds: Set<String>): ReclaimReport {
        val live = withActiveComposerOwner(liveSessionIds)
        return scanOrphanSessionDirs(live)
    }

    // delete the tab file alongside the session dir (line comment mentions
    // browserTabFile( — stripped, must not satisfy C2 by itself)
    fun deleteSessionFiles(sessionId: String): DeleteResult {
        val sessionDeleted = deleteRecursivelyChecked(sessionDir(sessionId)) &&
            deleteRecursivelyChecked(browserTabFile(sessionId))
        val mediaDeleted = mediaSessionDirs(sessionId).fold(true) { acc, dir ->
            deleteRecursivelyChecked(dir) && acc
        }
        return DeleteResult(sessionDeleted, mediaDeleted)
    }

    fun reclaimOrphans(liveSessionIds: Set<String>): ReclaimReport {
        val live = withActiveComposerOwner(liveSessionIds)
        val report = scanOrphans(live)
        deleteOrphanMediaLeaves(live)
        deleteOrphanBrowserTabFiles(live)
        return report
    }

    private fun deleteOrphanBrowserTabFiles(liveSessionIds: Set<String>) {
        if (!browserTabsRoot.exists()) return
        browserTabsRoot.listFiles()?.forEach { file ->
            val stem = file.name.removeSuffix(".json")
            val isSessionShaped = looksLikeSessionId(stem)
            if (isSessionShaped && stem !in liveSessionIds) file.delete()
        }
    }
}
"""
    # V1: delete path no longer removes the tab state file
    v1 = compliant.replace(
        "        val sessionDeleted = deleteRecursivelyChecked(sessionDir(sessionId)) &&\n"
        "            deleteRecursivelyChecked(browserTabFile(sessionId))",
        "        val sessionDeleted = deleteRecursivelyChecked(sessionDir(sessionId))",
    )
    # V2: orphan reclaim loses the browser_tabs half
    v2 = compliant.replace("        deleteOrphanBrowserTabFiles(live)\n", "")
    # V3: no browser_tabs at all (regression to the defect status quo)
    v3 = """package com.rikkaminis.app.data.storage

class SessionFileStore internal constructor(
    private val filesDirForTestingOrProd: File,
) {
    val sessionsRoot: File = File(filesDirForTestingOrProd, "minis-sessions")
    private val mediaRoot: File = File(filesDirForTestingOrProd, "media")

    fun sessionDir(sessionId: String): File = File(sessionsRoot, sessionId)

    fun deleteSessionFiles(sessionId: String): DeleteResult {
        val sessionDeleted = deleteRecursivelyChecked(sessionDir(sessionId))
        val mediaDeleted = mediaSessionDirs(sessionId).fold(true) { acc, dir ->
            deleteRecursivelyChecked(dir) && acc
        }
        return DeleteResult(sessionDeleted, mediaDeleted)
    }

    fun reclaimOrphans(liveSessionIds: Set<String>): ReclaimReport {
        val report = scanOrphans(liveSessionIds)
        deleteOrphanMediaLeaves(liveSessionIds)
        return report
    }
}
"""
    # V4: KDoc mentions browser_tabs + browserTabFile( but no code (comment trap)
    v4 = v3.replace(
        "class SessionFileStore",
        "/**\n"
        " * KDoc claims we handle browser_tabs/<sid>.json and call browserTabFile(\n"
        " * and deleteOrphanBrowserTabFiles( — pure documentation, no code.\n"
        " */\n"
        "class SessionFileStore",
    )
    # V5: the shape predicate loses the draft namespace (C7)
    v5 = compliant.replace(
        "        val uuid = if (name.startsWith(DRAFT_ID_PREFIX)) name.substring(DRAFT_ID_PREFIX.length) else name\n"
        "        return uuid.length == 36 && uuid.count { it == '-' } == 4",
        "        return name.length == 36 && name.count { it == '-' } == 4",
    )
    # V6: the draft literal is duplicated back at a call site (C7 single source)
    v6 = compliant.replace(
        "            val isSessionShaped = looksLikeSessionId(stem)",
        "            val isSessionShaped = looksLikeSessionId(stem) || stem.startsWith(\"__new__\")",
    )
    # V7: one orphan entry point stops widening (C8)
    v7 = compliant.replace(
        "    fun scanOrphans(liveSessionIds: Set<String>): ReclaimReport {\n"
        "        val live = withActiveComposerOwner(liveSessionIds)\n",
        "    fun scanOrphans(liveSessionIds: Set<String>): ReclaimReport {\n",
    )
    # V8: the Context constructor wires a hardcoded provider (C8)
    v8 = compliant.replace(
        "        { ComposerDraftStore.activeDraftIds(context) },",
        "        { emptySet() },",
    )
    # V9: the widener keeps its shape but stops consulting the provider (C8)
    v9 = compliant.replace(
        "        val draftIds = activeDraftIds()\n"
        "        if (draftIds.isEmpty()) return liveSessionIds\n"
        "        return liveSessionIds + draftIds",
        "        return liveSessionIds",
    )
    # V10: the orphan body bypasses the shape predicate entirely (C3)
    v10 = compliant.replace(
        "            val isSessionShaped = looksLikeSessionId(stem)",
        "            val isSessionShaped = stem.isNotEmpty()",
    )

    arms = [
        ("compliant", compliant, []),
        ("V1 delete-path missing tab file", v1, ["C2"]),
        ("V2 orphan reclaim missing browser_tabs", v2, ["C3"]),
        ("V3 regression to defect status quo", v3, ["C1"]),
        ("V4 KDoc-only mention (comment trap)", v4, ["C1", "C2", "C3"]),
        ("V5 predicate lost the __new__ draft namespace", v5, ["C7"]),
        ("V6 draft literal duplicated at a call site", v6, ["C7"]),
        ("V7 orphan entry stops widening the live set", v7, ["C8"]),
        ("V8 hardcoded draft provider", v8, ["C8"]),
        ("V9 widener stops consulting the provider", v9, ["C8"]),
        ("V10 orphan body bypasses the shape predicate", v10, ["C3"]),
    ]
    failures = 0
    for name, sample, expect in arms:
        errs = check_source(sample)
        if expect == [] and errs:
            print(f"  FAIL {name}: expected green, got {errs}")
            failures += 1
        elif expect != [] and not errs:
            print(f"  FAIL {name}: expected red, got green")
            failures += 1
        elif expect != [] and not any(err.startswith(c) for c in expect for err in errs):
            print(f"  FAIL {name}: expected {expect}, got {errs}")
            failures += 1
        else:
            print(f"  OK {name}: {errs if errs else 'green'}")

    # --- [fix/dv14-B2] C4 arms: pool teardown ordering (own fixtures) ---
    pool_arms = [
        ("C4 compliant pool", POOL_COMPLIANT, []),
        ("C4a dispose re-persists saveState()", POOL_V_DISPOSE_SAVE, ["C4"]),
        ("C4c trim stores the URL before destroyTab", POOL_V_TRIM_ORDER, ["C4"]),
        ("C4c evict drops the URL assignment", POOL_V_EVICT_NO_STORE, ["C4"]),
        ("C4b dispose no longer destroys tabs", POOL_V_DISPOSE_NO_DESTROY, ["C4"]),
        ("C4d window.open forgets saveState", POOL_V_NEWWIN_NOSAVE, ["C4d"]),
        ("C4d window.close forgets saveState", POOL_V_CLOSEWIN_NOSAVE, ["C4d"]),
        ("C4e dispose stops cancelling the eviction scope", POOL_V_DISPOSE_NO_EVICTION_CANCEL, ["C4e"]),
        ("C4e dispose stops cancelling the download scope", POOL_V_DISPOSE_NO_DOWNLOAD_CANCEL, ["C4e"]),
        ("C4e cancellation through a helper stays green", POOL_HELPER_CANCEL, []),
        ("C4f restoreSavedTabs drops its disposal guard", POOL_V_RESTORE_NO_GUARD, ["C4f"]),
        ("C4f decoy guard in a default lambda argument", POOL_V_RESTORE_LAMBDA_PARAM, ["C4f"]),
        ("C4f saveState drops its disposal guard", POOL_V_SAVE_NO_GUARD, ["C4f"]),
        ("C4f disposal latch is not volatile", POOL_V_LATCH_NOT_VOLATILE, ["C4f"]),
        ("C4f dispose never latches", POOL_V_NO_LATCH, ["C4f"]),
        ("C5 risk notice rendered but dropped", POOL_V_RISK_NOT_MERGED, ["C5"]),
        ("C5 risk notice bound then dropped", POOL_V_RISK_DROPPED, ["C5"]),
        ("C5 risk notice never rendered", POOL_V_RISK_NO_CALL, ["C5"]),
        ("C6 setSession forgets to re-point downloads", POOL_V_SESSION_NO_REMAP, ["C6"]),
        ("C6 setSession rewrites _downloads by assignment", POOL_V_SESSION_PLAIN_ASSIGN, ["C6"]),
    ]
    for name, sample, expect in pool_arms:
        errs = check_pool_source(sample)
        if expect == [] and errs:
            print(f"  FAIL {name}: expected green, got {errs}")
            failures += 1
        elif expect != [] and not errs:
            print(f"  FAIL {name}: expected red, got green")
            failures += 1
        elif expect != [] and not any(err.startswith(c) for c in expect for err in errs):
            print(f"  FAIL {name}: expected {expect}, got {errs}")
            failures += 1
        else:
            print(f"  OK {name}: {errs if errs else 'green'}")

    # --- [dv23-B4 B-04] C11 arms: the borrow latch and its write guard ---
    ctx_arms = [
        ("C11 compliant borrow latch", CTX_COMPLIANT, []),
        ("C11 saveState persists anyway", CTX_V_PERSIST, ["C11"]),
        ("C11 full bind never clears the latch", CTX_V_NO_CLEAR, ["C11"]),
        ("C11 borrow guard wrapped in a dead block", CTX_V_DEAD_GUARD, ["C11"]),
    ]
    for name, sample, expect in ctx_arms:
        errs = check_context_only_persist(sample)
        if expect == [] and errs:
            print(f"  FAIL {name}: expected green, got {errs}")
            failures += 1
        elif expect != [] and not errs:
            print(f"  FAIL {name}: expected red, got green")
            failures += 1
        elif expect != [] and not any(err.startswith(c) for c in expect for err in errs):
            print(f"  FAIL {name}: expected {expect}, got {errs}")
            failures += 1
        else:
            print(f"  OK {name}: {errs if errs else 'green'}")
    # --- [dv22-B2] C10 arms: destroy paths re-derive the busy flag ---
    # Arms are generated from the fixture's OWN derived caller set, so the
    # "one breaker per destroy path" coverage cannot silently shrink.
    destroy_names = [n for n, _ in _c10_destroy_callers(strip_comments(DESTROY_COMPLIANT))]
    destroy_arms = [("C10 compliant destroy paths", DESTROY_COMPLIANT, [])]
    for nm in destroy_names:
        destroy_arms.append((f"C10 {nm} stops re-deriving", _c10_strip_publish(DESTROY_COMPLIANT, nm), ["C10"]))
    destroy_arms.append(("C10 re-derivation moved above the list write",
                         _c10_publish_before_write(DESTROY_COMPLIANT, destroy_names[0]), ["C10"]))
    destroy_arms.append(("C10 destroyTab publishes from the stale local list",
                         DESTROY_COMPLIANT.replace(
                             "    private fun destroyTab(tabs: MutableList<Tab>, tab: Tab) {\n"
                             "        tabs.remove(tab)\n",
                             "    private fun destroyTab(tabs: MutableList<Tab>, tab: Tab) {\n"
                             "        tabs.remove(tab)\n        publishAgentBusy()\n"),
                         ["C10"]))
    destroy_arms.append(("C10 a 7th destroy caller the gate never names",
                         DESTROY_V_NEW_CALLER_BLIND, ["C10"]))
    destroy_arms.append(("C10 destroy call outside any function body",
                         DESTROY_V_UNATTRIBUTED_CALL, ["C10"]))
    if len(destroy_names) != 6:
        print(f"  FAIL C10 fixture caller set is {len(destroy_names)} names, "
              "expected the six shipped destroy paths (fixture drift)")
        failures += 1
    for name, sample, expect in destroy_arms:
        errs = check_destroy_busy_source(sample)
        if expect == [] and errs:
            print(f"  FAIL {name}: expected green, got {errs}")
            failures += 1
        elif expect != [] and not errs:
            print(f"  FAIL {name}: expected red, got green")
            failures += 1
        elif expect != [] and not any(err.startswith(c) for c in expect for err in errs):
            print(f"  FAIL {name}: expected {expect}, got {errs}")
            failures += 1
        else:
            print(f"  OK {name}: {errs if errs else 'green'}")
    # --- [da1040-B2] C9 arms: busy-signal observability (own fixtures) ---
    for name, p, sh, expect in busy_arms:
        errs = check_busy_source(p, sh)
        if expect == [] and errs:
            print(f"  FAIL {name}: expected green, got {errs}")
            failures += 1
        elif expect != [] and not errs:
            print(f"  FAIL {name}: expected red, got green")
            failures += 1
        elif expect != [] and not any(err.startswith(c) for c in expect for err in errs):
            print(f"  FAIL {name}: expected {expect}, got {errs}")
            failures += 1
        else:
            print(f"  OK {name}: {errs if errs else 'green'}")

    if failures:
        print(f"SELFTEST FAILED ({failures} arms)")
        return 1
    print(MARKER)
    # [fix/dv17-1 B-01/B-06] separate marker for the consumer/remap criteria —
    # MARKER above is pinned verbatim by test_scan.py and must not change.
    print("BROWSER_TABS_RISK_NOTICE_AND_REMAP_SELFTEST=PASS")
    return 0


def main() -> int:
    args = [a for a in sys.argv[1:]]
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
    path = os.path.join(root, PKG, TARGET)
    try:
        src = open(path).read()
    except OSError as e:
        print(f"browser_tabs_lifecycle_check RED: cannot read {TARGET}: {e}")
        return 1
    errs = check_source(src)
    # [fix/dv14-B2] C4 — real tree must carry the pool file (fail-closed).
    pool_path = os.path.join(root, POOL_REL)
    try:
        pool_src = open(pool_path).read()
    except OSError as e:
        errs.append(f"C4: cannot read BrowserTabPool.kt: {e}")
        pool_src = None
    else:
        errs += check_pool_source(pool_src)
    # [da1040-B2] C9 — the pool AND its consumer face (fail-closed).
    sheet_path = os.path.join(root, SHEET_REL)
    try:
        sheet_src = open(sheet_path).read()
    except OSError as e:
        errs.append(f"C9: cannot read BrowserSheet.kt: {e}")
    else:
        if pool_src is not None:
            errs += check_busy_source(pool_src, sheet_src)
        # [dv22-B2] C10 — destroy paths re-derive agentBusy (pool file only).
        if pool_src is not None:
            errs += check_destroy_busy_source(pool_src)
    # [dv23-B4 B-04] C11 — a borrowing pool must not persist (fail-closed).
    if pool_src is not None:
        errs += check_context_only_persist(pool_src)
    if errs:
        print("browser_tabs_lifecycle_check RED:")
        for e in errs:
            print(f"  - {e}")
        return 1
    print("browser_tabs_lifecycle_check OK (criteria: C1-C3 store lifecycle + C4/C4d pool teardown + window.open/close persistence + C4e disposal cancels every pool-owned scope + C4f the disposal latch guards saveState/restoreSavedTabs + C5 risk notice reaches the agent text + C6 setSession re-points downloads atomically + C7 the session-shape predicate alone carries the draft alias + C8 every orphan entry folds the composer's active draft in + C9 the pool's busy signal is funnelled and observable + C10 every destroyTab caller re-derives it after settling the list)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
