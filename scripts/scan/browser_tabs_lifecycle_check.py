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

[batch5-t01] What the criteria can no longer be fooled by. Five witnesses
were run against the previous version; each one kept the defect and passed:

  * `if (!true) { if (isDisposed) return }` — the guard was matched as a
    substring and its position, so wrapping it in a statically-dead block
    deleted the decision and kept the spelling (C4f);
  * `stamped.copy(text = stamped.text, …)` — C5 accepted ANY `text =` write
    after the renderer call, so the copy could re-project the source text
    while an earlier `val text = … notice …` (still on screen) satisfied the
    substring test; the criterion now reads the returned action's own text
    argument and follows the value into it (one local hop allowed);
  * `… any { it.inUse } && !true` — C9b compared top-level operands against
    the literal strings "true"/"false", so `!true` read as a live conjunct;
  * `run { if (isContextOnlySession) return@run }` — a return that leaves the
    lambda is not a guard: C11 now requires the guard to exit the FUNCTION
    (shared `returns_exiting`), the same rule C4f uses;
  * `if (false) downloadScope.cancel()` — C4e counted cancelled scope names
    without asking whether the call was reachable.

Hardening applied to every arm rather than to the one that was attacked:

  * structure runs on the SHARED lexer (`code_view`, length preserving, string
    and char literals masked) — the gate's own `strip_comments` kept string
    contents, so a `"}"` or `"/*"` inside a literal could hand a criterion a
    body that was not the body (parser-death axis). Literal CONTENT (the
    `browser_tabs` path) is read from `literal_view`, never from the
    structural channel;
  * one dead-branch decision (`_guard_dead` → shared `const_bool` /
    `guard_feasible` + the absorbing-literal layer + `X == X`), used by every
    dead-span test instead of per-arm literal whitelists;
  * one guard decision (`_live_guard_positions`: not dead + `returns_exiting`)
    shared by C4f and C11 — an arm cannot be hardened while its sibling keeps
    matching on spelling.

[R3-t03] Third-round witness attack (dual-gate seams + one symmetry axis).
Three more "the tokens are all here, the effect is not" holes, each derived from
the tree:
  C3b the orphan sweep's delete must be gated on the stem being *absent* from
      the derived live-set parameter (`… !in live` / `!live.contains(…)` / a
      preceding `if (… in live) return/continue`). Witness 81 flips the
      polarity to `stem in liveSessionIds`: every token C3 greps for is still
      present, and one reclaim pass wipes every LIVE session's saved tabs.
  C6b the `withPath = { entry, path -> … }` callback handed to
      `remapDownloadDestinations(` must write the remapped path into the same
      field `pathOf` reads (derived). Witness 79 returns the entry untouched —
      downloads keep pointing at the deleted `__new__<uuid>` workspace.
  C6c `loadSavedState` must dispatch the restore entry point (derived: the
      member that both reads `savedURLs` and calls `createTab(`) on a reachable
      path. Witness 80 rewrites the guard to `false && savedURLs.isNotEmpty()`:
      the call is still spelled, and the tab strip comes back empty.

Usage: python3 browser_tabs_lifecycle_check.py <repo_root>
       python3 browser_tabs_lifecycle_check.py --root <repo_root>
       python3 browser_tabs_lifecycle_check.py --selftest
Exit 0 = pass, 1 = violation, 2 = usage error.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Shared primitives (batch-5 discipline: this gate keeps its criteria but no
# longer carries its own lexer / bracket matcher / dead-branch regex).
from _kotlin_ast import (const_bool, enclosing_conditions, if_blocks, match_bracket,
                         split_top, strip_parens)
from _kotlin_valueflow import (KotlinLexError, brace_depth, call_args, code_view,
                               contains_ident, expr_end, guard_feasible,
                               literal_view, path_feasible, returns_exiting,
                               value_sources)

PKG = "src/android/app/src/main/java/com/rikkaminis/app/data/storage"
TARGET = "SessionFileStore.kt"
# [fix/dv14-B2] C4's scan target: ONLY the pool file (scope discipline —
# the store criteria C1-C3 read SessionFileStore.kt and stay untouched).
POOL_REL = "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserTabPool.kt"
# [da1040-B2] C9's consumer face: the sheet is where a non-reactive busy read
# freezes the affordances, so the criterion reads it too.
SHEET_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/browser/BrowserSheet.kt"
MARKER = "BROWSER_TABS_SELFTEST=PASS"


def _structure(src: str):
    """(code, lit, None) or (None, None, error).

    `code` is the shared `code_view` (comments AND string/char literal
    interiors blanked, length preserved) — the only channel structural
    analysis may use. `lit` is `literal_view` (comments stripped, literals
    kept) and exists solely for criteria that must READ a path literal
    (`browser_tabs`), never for structure.

    Fail-closed: an unlexable source is a violation. The old self-written
    `strip_comments` kept string contents, so `"{"` / `"}"` / `"/*"` inside a
    literal could open or close blocks and hand the criteria a body that is
    not the body it thinks it is (batch-5 `parser-death` axis).
    """
    try:
        return code_view(src), literal_view(src), None
    except KotlinLexError as exc:
        return None, None, (f"source not lexable ({exc}) — refusing to fall "
                            f"back to raw scanning")


# [wide3-06 C4f] A next-line token that can only begin a new class member —
# it stops the return-type scan from wandering into the next declaration when
# the function under test has no body at all.
_MEMBER_HEAD_RE = re.compile(
    r"^(?:@|(?:public|private|internal|protected|override|suspend|inline|operator|"
    r"open|abstract|fun|val|var|class|object|init)\b)"
)


# ── [R3-t03 W81] 孤儿回收的布尔极性（对称轴：`!in live` vs `in live`）───────
ORPHAN_DELETE = re.compile(r"\.\s*delete\s*\(\s*\)")
LIVE_EXIT = re.compile(r"\breturn\b|\bcontinue\b|\bbreak\b")


def _live_set_param(code: str, header: str):
    """`fun deleteOrphanBrowserTabFiles(live: Set<String>)` 的集合形参名。

    从签名派生，不写死 `liveSessionIds` —— 改名不该让判据失明，删掉这个形参
    才该报红（fail-closed）。
    """
    idx = code.find(header)
    if idx < 0:
        return None
    op = code.find("(", idx)
    if op < 0:
        return None
    cp = match_bracket(code, op)
    if cp < 0:
        return None
    for a, b in split_top(code[op + 1:cp], (",",)):
        decl = code[op + 1 + a:op + 1 + b]
        name, _, typ = decl.partition(":")
        if re.search(r"\b(?:Set|Collection|MutableSet)\s*<", typ):
            nm = name.strip().split()[-1] if name.strip() else ""
            if re.fullmatch(r"[A-Za-z_]\w*", nm or ""):
                return nm
    return None


def _membership_kind(conjunct: str, live: str):
    """一个合取项对 live 集合的归属判定 -> 'pos' | 'neg' | None。

    `neg` 表示「不在活集合里」。`else` 分支的条件由 `enclosing_conditions`
    前置 `!`，所以先剥一层否定再看形态。
    """
    c = strip_parens(conjunct).strip()
    neg = False
    if c.startswith("!"):
        neg = True
        c = strip_parens(c[1:]).strip()
    if re.search(r"!\s*in\s+%s\b" % re.escape(live), c):
        return "neg"
    if re.search(r"\bin\s+%s\b" % re.escape(live), c):
        return "neg" if neg else "pos"
    if re.search(r"%s\s*\??\s*\.\s*contains\s*\(" % re.escape(live), c):
        return "neg" if neg else "pos"
    return None


def _preceding_live_exit(body: str, pos: int, live: str) -> bool:
    """`if (stem in live) return/continue` 形态的早退守卫（等价写法）。

    删除语句本身不带条件（`forEach` 体内），极性住在前面那条早退守卫里。
    """
    for cond, t_start, t_end, _, _ in if_blocks(body):
        if t_end > pos:
            continue
        kinds = [_membership_kind(cond[a:b], live)
                 for a, b in split_top(cond, ("&&",))]
        if "pos" not in kinds:
            continue
        if LIVE_EXIT.search(body[t_start:t_end]):
            return True
    return False


def _orphan_delete_gate(body: str, live: str) -> list:
    """W81 — 删除必须发生在「不在活集合里」时，且必须真的发生。"""
    sites = list(ORPHAN_DELETE.finditer(body))
    if not sites:
        return ["C3b: the orphan sweep no longer deletes anything (`file.delete()` is "
                "gone from deleteOrphanBrowserTabFiles) — orphan browser_tabs state "
                "accumulates on disk forever (fail-closed)"]
    out = []
    for m in sites:
        kinds = []
        conds = [c for _, c in enclosing_conditions(body, m.start())]
        for cond in conds:
            for a, b in split_top(cond, ("&&",)):
                k = _membership_kind(cond[a:b], live)
                if k:
                    kinds.append(k)
        if "pos" in kinds:
            out.append(
                "C3b: the orphan sweep deletes a tab-state file when the session IS live "
                "(`%s` membership positively guards the delete) — the polarity is "
                "inverted: one reclaim pass wipes every live session's saved tabs and "
                "viewport, while the real orphans survive (W81)" % live)
        elif not any(k == "neg" for k in kinds):
            if _preceding_live_exit(body, m.start(), live):
                continue
            out.append(
                "C3b: the delete is not gated on the live-set at all (no `… !in %s` / "
                "`!%s.contains(…)` guard and no preceding `if (… in %s) return`) — a "
                "reclaim pass cannot tell a live session from an orphan (W81)" % (live, live, live))
    return out


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
    close_paren = match_bracket(src, open_paren)
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
            end = match_bracket(src, i)
            return src[i:end + 1] if end > 0 else ""
        if c == "=" and src[i + 1:i + 2] != "=":
            return src[i:expr_end(src, i + 1)]
        if c == "}":
            return ""
        i += 1
    return ""


def _guard_dead(cond: str) -> bool:
    """True when `cond` can never be true — the ONE decision every arm of this
    gate uses for "is this branch dead / is this literal absorbing".

    Two layers, both spelling-independent:

    * `_kotlin_ast.const_bool` folds constants arithmetically and logically
      under Kotlin precedence, so `!true`, `1 == 2`, `(0 > 1)` and
      `!true || false` are decided without a literal whitelist;
    * the absorbing-literal layer runs per top-level operator, because
      const_bool deliberately refuses to fold as soon as an unknown identifier
      appears: `false && !isDisposed` is still never true, and
      `absence || true` is still always true. The previous version compared
      each top-level operand against the strings "true"/"false", so
      `!true` (witness G01-A1 / G01-A3 / G01-A5 family) sailed through.
    """
    c = strip_parens((cond or "").strip())
    if not c:
        return False
    parts = split_top(c, ("||",))
    if len(parts) > 1:
        return all(_guard_dead(c[a:b]) for a, b in parts)
    parts = split_top(c, ("&&",))
    if len(parts) > 1:
        for a, b in parts:
            if const_bool(c[a:b].strip()) is False:
                return True  # absorbing conjunct: the whole body is dead
    # `X == X` / `X != X` is decided by the operands being the same text, not
    # by any literal (the same rule the C11 fixture used in wide3).
    same = re.fullmatch(r"(.+?)\s*(==|!=)\s*(.+)", c)
    if same and strip_parens(same.group(1).strip()) == strip_parens(same.group(3).strip()):
        return same.group(2) == "!="
    # Leaf decision: shared feasibility (handles `0 > 1`, `2 == 2`, …), which
    # is also what the value-flow arms use — one predicate, no literal lists.
    return not guard_feasible(c)


def _dead_spans(code: str) -> list:
    """Spans of statically-unreachable `if (…)` / `while (…)` bodies.

    The condition goes through `_guard_dead`, so the dead-branch family is
    decided once for the whole gate (`if (false)`, `if (!true)`,
    `if (false && X)`, `if (1 == 2)` …) instead of per-arm literal matching.
    """
    spans = []
    for m in re.finditer(r"\b(?:if|while)\s*\(", code):
        open_idx = m.end() - 1
        close_idx = match_bracket(code, open_idx)
        if close_idx < 0 or not _guard_dead(code[open_idx + 1:close_idx]):
            continue
        j = close_idx + 1
        while j < len(code) and code[j] in " \t":
            j += 1
        if j < len(code) and code[j] == "{":
            end = match_bracket(code, j)
            if end > 0:
                spans.append((j, end + 1))
                continue
        eol = code.find("\n", j)
        spans.append((j, len(code) if eol < 0 else eol))
    return spans


def _pos_dead(code: str, pos: int, spans=None) -> bool:
    spans = _dead_spans(code) if spans is None else spans
    return any(a <= pos < b for a, b in spans)


def _stmt_tail(src: str, start: int, limit: int = 600) -> str:
    """The expression starting at [start] — the shared `expr_end` decides where
    it stops (depth-0 newline, continuation aware)."""
    stop = expr_end(src, start)
    return src[start:min(stop, start + limit)]


def _live_guard_positions(body: str, cond_re: str) -> list:
    """Positions of `if (<cond matching cond_re>) …` guards that DECIDE.

    A guard decides only when both hold:

    * it is not nested inside a statically-dead block — `if (!true) { if
      (isDisposed) return }` keeps the spelling and drops the decision
      (witness G01-A1);
    * the guarded statement carries a return that exits the enclosing
      FUNCTION. `run { if (isContextOnlySession) return@run }` returns from
      the `run` block and execution continues straight into the persist path
      (witness G01-A4), and a bare return inside any lambda is equally
      useless as a fail-closed guard.

    `returns_exiting` (shared) gives the brace-depth verdict, so this is the
    same decision the C4f latch and the C11 borrow latch both use — one arm
    cannot be hardened while its sibling keeps matching on spelling.
    """
    spans = _dead_spans(body)
    out = []
    for m in re.finditer(r"\bif\s*\(" + cond_re + r"\s*\)", body):
        if _pos_dead(body, m.start(), spans):
            continue
        open_paren = body.index("(", m.start())
        close_paren = match_bracket(body, open_paren)
        if close_paren < 0:
            continue
        j = close_paren + 1
        while j < len(body) and body[j] in " \t":
            j += 1
        if j >= len(body):
            continue
        if body[j] == "{":
            end = match_bracket(body, j)
            if end < 0:
                continue
            seg = (j, end + 1)
        else:
            eol = body.find("\n", j)
            seg = (j, len(body) if eol < 0 else eol)
        if returns_exiting(body, seg[0], seg[1],
                           fn_depth=max(1, brace_depth(body, seg[0]))):
            out.append(m.start())
    return out


def check_source(source: str) -> list:
    src, lit, err = _structure(source)
    if err is not None:
        return [f"C1: {err}"]
    errs = []

    # C1: root + definition (fail-closed)
    if "browser_tabs" not in lit:
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
        # ── [R3-t03 W81] 删了 ≠ 删对了：布尔极性 ─────────────────────────
        # C3 此前只问「函数体里有没有 looksLikeSessionId(」。把
        # `stem !in liveSessionIds` 翻成 `stem in liveSessionIds`，谓词还在、
        # 调用还在、门全绿 —— 而后果是**所有活会话**的 browser_tabs 状态与
        # 视口设置被删光、孤儿反而留下。判据落到决定：deletes 必须只在
        # 「stem 不在活集合里」时发生。
        live_param = _live_set_param(src, "fun deleteOrphanBrowserTabFiles")
        if not live_param:
            errs.append(
                "C3b: deleteOrphanBrowserTabFiles declares no `Set<…>` live-set parameter "
                "(fail-closed: the sweep can no longer tell live from orphan)")
        else:
            errs.extend(_orphan_delete_gate(orphan_body, live_param))

    # --- [da1040-A3] C7: the session-shape predicate carries BOTH shapes ---
    if not re.search(r"const val DRAFT_ID_PREFIX\s*=\s*\"__new__\"", lit):
        errs.append("C7: the __new__ draft prefix is not declared as a single constant (fail-closed)")
    occurrences = len(re.findall(r'"__new__"', lit))
    if occurrences != 1:
        errs.append(
            f'C7: the "__new__" namespace literal appears {occurrences} times in SessionFileStore '
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
        spans = _dead_spans(body)
        for m in re.finditer(r"\b([A-Za-z_]\w*)\s*\.\s*cancel\s*\(", body):
            # [batch5-t01 G01-A5] `if (false) downloadScope.cancel()` keeps the
            # call's spelling and deletes its effect — disposing then leaves
            # the scope running against a destroyed pool. Same dead-branch
            # decision as every other arm in this gate.
            if _pos_dead(body, m.start(), spans):
                continue
            cancelled.add(m.group(1))
        for m in re.finditer(r"\b([A-Za-z_]\w*)\s*\(", body):
            name = m.group(1)
            if name in bodies and bodies[name] not in seen:
                stack.append(bodies[name])
    return cancelled


def _c4_guard_pos(body: str, latch: str) -> int:
    """Position of a LIVE, function-exiting `if (<latch>) …` guard, or -1.

    Delegates to `_live_guard_positions`, the same decision C11 uses: a guard
    wrapped in a dead block (witness G01-A1) or one whose return only leaves a
    lambda (`run { if (latch) return@run }`, witness G01-A4) is spelled but
    never decides.
    """
    pos = _live_guard_positions(body, re.escape(latch))
    return pos[0] if pos else -1


def _result_text_args(action: str, since: int) -> list:
    """[(pos, value)] for every `text = …` named argument of a `.copy(` call
    that the action assembles after `since` — i.e. the candidates for the
    returned BrowserActionResult's text. Later entries win."""
    out = []
    for m in re.finditer(r"\.\s*copy\s*\(", action[since:]):
        open_paren = since + m.end() - 1
        close = match_bracket(action, open_paren)
        if close < 0:
            continue
        base = open_paren + 1
        inner = action[base:close]
        for a, b in split_top(inner, (",",)):
            seg = inner[a:b]
            am = re.match(r"\s*text\s*=(?!=)\s*(.*)$", seg, re.S)
            if am:
                out.append((base + a, am.group(1).strip()))
    return out


def _flows_from(text: str, expr: str, name: str, pos: int, depth: int = 0) -> bool:
    """Does `expr` read `name` — directly, or through one local binding?"""
    e = strip_parens(expr.strip())
    if contains_ident(e, name):
        return True
    if depth >= 2:
        return False
    m = re.fullmatch(r"[A-Za-z_`]\w*", e)
    if not m:
        return False
    ident = re.escape(e.strip("`"))
    best = None
    for d in re.finditer(r"\b(?:val|var)\s+" + ident + r"\s*(?::[^=\n]+)?=(?!=)",
                         text):
        if d.start() > pos:
            continue
        if best is None or d.start() > best.start():
            best = d
    if best is None:
        return False
    rhs_pos = best.end()
    return _flows_from(text, text[rhs_pos:expr_end(text, rhs_pos)], name, best.start(),
                       depth + 1)


def check_pool_source(pool_src: str) -> list:
    """[fix/dv14-B2] C4 — dispose() must not re-persist; the evicted tab's
    URL must be stored AFTER destroyTab. Fail-closed throughout."""
    code, lit, err = _structure(pool_src)
    if err is not None:
        return [f"C4: {err}"]
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
            save_lit = (slice_body(lit, "private fun saveState")
                        or slice_body(lit, "fun saveState"))
            if not save:
                errs.append("C4f: fun saveState body not extractable (fail-closed)")
            else:
                pos = _c4_guard_pos(save, latch)
                touch = save_lit.find("browser_tabs")
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
                # [batch5-t01 G01-A2] The carrier is the returned action's own
                # `text =` argument, not "any `text =` written after the call":
                # `stamped.copy(text = stamped.text, …)` left the earlier
                # `val text = … notice …` binding untouched on screen and threw
                # its value away, so the substring test kept passing while the
                # notice never reached the agent.
                products = _result_text_args(action, notice_pos)
                if not products:
                    errs.append(
                        "C5: the returned action copies `stamped` without a `text =` "
                        "argument after the renderer call — the notice cannot reach "
                        "result.text, the only field the agent reads (fail-closed)")
                else:
                    pos, rhs = products[-1]
                    rhs = rhs.strip().rstrip(",")
                    if re.fullmatch(r"[A-Za-z_`]\w*\s*\.\s*text", rhs):
                        errs.append(
                            f"C5: the result copy re-projects its own source text "
                            f"(`text = {rhs}`) — the rendered risk notice `{name}` is "
                            "computed and dropped, so the agent still cannot read it")
                    elif not _flows_from(action, rhs, name, pos):
                        errs.append(
                            f"C5: the rendered risk notice `{name}` never flows into "
                            f"the returned action's text (the winning `text = {rhs}` "
                            "does not read it, directly or through one local binding — "
                            "the notice is computed and dropped)")
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
        # ── [R3-t03 W79] 重映射回调必须真的写回新路径 ────────────────────
        # C6 只判「remapDownloadDestinations( 在这、_downloads.update 在这」。
        # 把回调改成 `{ entry, _ -> entry }`，两边都在、`_downloads` 也确实
        # 走了原子更新 —— 而每条记录的 destination 仍指向已被迁移删除的旧
        # 草稿目录，下载面板的「打开」在 file.exists() 上静默失效。
    errs.extend(_remap_callback_effect(code))

    # ── [R3-t03 W80] 恢复标签页的派发必须真的发生 ─────────────────────────
    # C4f 判的是 restoreSavedTabs 内部的处置闩；C6 判的是 loadSavedState 是
    # setSession 的最后一步。中间那一步——loadSavedState 到底有没有把重建
    # 派发出去——两门都不管：`if (false && savedURLs.isNotEmpty())` 保留了
    # 全部 token，切回会话/重启后标签栏一片空白。
    errs.extend(_restore_dispatch_effect(code))
    return errs


# ── [R3-t03] W79 / W80 helpers ─────────────────────────────────────────────
WITH_PATH = re.compile(r"\bwithPath\s*=\s*\{")
PATH_OF = re.compile(r"\bpathOf\s*=\s*\{")


def _lambda_parts(text: str, open_pos: int):
    """`{ params -> body }` -> (params, body) 或 (None, None)。"""
    close = match_bracket(text, open_pos)
    if close < 0:
        return None, None
    inner = text[open_pos + 1:close]
    arrow = inner.find("->")
    if arrow < 0:
        return None, None
    params = [p.strip() for p in inner[:arrow].strip().split(",") if p.strip()]
    return params, inner[arrow + 2:].strip()


def _lambda_tail(body: str) -> str:
    """lambda 的结果表达式（最后一个裸 return，或最后一条语句）。"""
    rets = [m for m in re.finditer(r"\breturn\b(?!@)", body)]
    if rets:
        return body[rets[-1].end():expr_end(body, rets[-1].end())].strip()
    parts = split_top(body, (";",))
    if parts:
        return body[parts[-1][0]:parts[-1][1]].strip()
    return body.strip()


def _remap_path_field(body: str) -> str:
    """`pathOf = { entry -> entry.destination }` 读的字段名（取不到返回 ''）。"""
    m = PATH_OF.search(body)
    if not m:
        return ""
    _, lam = _lambda_parts(body, m.end() - 1)
    if not lam:
        return ""
    tail = _lambda_tail(lam)
    f = re.search(r"\.\s*([A-Za-z_]\w*)\s*$", tail)
    return f.group(1) if f else ""


def _enclosing_fun_body(code: str, pos: int) -> str:
    """覆盖 [pos] 的函数体（按 `fun NAME(` 逐个切片派生，取不到返回 ''）。"""
    for m in re.finditer(r"\bfun\s+([A-Za-z_]\w*)\s*\(", code):
        if m.start() > pos:
            break
        body = slice_body(code, "fun " + m.group(1))
        if not body:
            continue
        start = code.find(body, m.end())
        if 0 <= start <= pos < start + len(body):
            return body
    return ""


def _remap_callback_effect(code: str) -> list:
    """W79 — `withPath` 必须把重映射后的新路径写进记录本身。

    判据锚在**供出回调的那个函数**（`remapDownloadDestinations(` 的调用点
    所在函数体），而不是写死 `setSession`：把重映射抽成 helper 是正当重构，
    判据必须跟着回调走。
    """
    sites = [m.start() for m in re.finditer(r"\bremapDownloadDestinations\s*\(", code)]
    if not sites:
        return ["C6b: no `remapDownloadDestinations(` call site — the download re-point "
                "moved to an API this criterion does not know (ROT: re-pin it on the new "
                "path-writeback decision)"]
    out = []
    for pos in sites:
        body = _enclosing_fun_body(code, pos)
        if not body:
            out.append("C6b: the `remapDownloadDestinations(` call at line %d is not "
                       "inside a function body (fail-closed)"
                       % (code.count("\n", 0, pos) + 1))
            continue
        out.extend(_remap_callback_effect_in(body))
    return out


def _remap_callback_effect_in(body: str) -> list:
    m = WITH_PATH.search(body)
    if not m:
        return ["C6b: the remap call no longer supplies a `withPath = { entry, path -> … }` "
                "callback — remapDownloadDestinations is handed no way to rewrite a "
                "destination, so every record keeps the old path (fail-closed)"]
    params, lam = _lambda_parts(body, m.end() - 1)
    if not params or len(params) < 2:
        return ["C6b: the `withPath` callback does not take the remapped path as its "
                "second parameter (fail-closed: the write-back cannot be judged)"]
    path_param = params[1]
    field = _remap_path_field(body)
    tail = _lambda_tail(lam or "")
    if not tail:
        return ["C6b: the `withPath` callback has no result expression (fail-closed)"]
    writes_field = bool(field) and bool(
        re.search(r"\b%s\s*=(?!=)" % re.escape(field), tail))
    if not (contains_ident(tail, path_param) and writes_field):
        return [
            "C6b: the `withPath` callback returns `%s` without writing its own `%s` "
            "parameter into `%s` — every download record keeps pointing at the deleted "
            "`__new__<uuid>` workspace, so the panel's open button still dies on a path "
            "that no longer exists while the file sits intact in the new directory (W79)"
            % (tail[:70], path_param, field or "<destination>")]
    return []


def _restore_fn_name(code: str):
    """既读 `savedURLs` 又调 `createTab(` 的那个成员（恢复入口，派生不写死）。"""
    for m in re.finditer(r"\bfun\s+([A-Za-z_]\w*)\s*\(", code):
        name = m.group(1)
        body = slice_body(code, "fun " + name)
        if body and "savedURLs" in body and re.search(r"\bcreateTab\s*\(", body):
            return name
    return None


def _restore_dispatch_effect(code: str) -> list:
    """W80 — `loadSavedState` 必须把标签页重建真的派发出去。"""
    body = slice_body(code, "fun loadSavedState")
    if not body:
        return ["C6c: loadSavedState body not extractable (fail-closed)"]
    restore = _restore_fn_name(code)
    if not restore:
        return ["C6c: no member both reads `savedURLs` and calls `createTab(` — the tab "
                "restore entry point this criterion anchors on is gone (ROT: re-pin)"]
    hits = [m.start() for m in re.finditer(r"\b%s\s*\(" % re.escape(restore), body)]
    if not hits:
        return ["C6c: loadSavedState never dispatches `%s(` — the saved URLs are parsed "
                "and the viewport is replayed, but no tab is ever rebuilt: switching back "
                "to a session (or restarting) shows an empty tab strip while the state "
                "file still holds every URL (W80)" % restore]
    if not any(not _pos_dead(body, p) for p in hits):
        return ["C6c: every `%s(` in loadSavedState sits on a statically-dead path "
                "(`if (false && savedURLs.isNotEmpty())` …) — the restore is never "
                "dispatched, so the tab strip comes back empty (W80)" % restore]
    return []


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

    // [R3-t03 W80] the restore dispatch the compliant shape must carry: the
    // saved URLs are parsed, then the rebuild is dispatched (async on Main).
    private fun loadSavedState() {
        val sid = sessionId ?: return
        val file = File(context.filesDir, "browser_tabs/$sid.json")
        if (!file.exists()) return
        val json = JSONObject(file.readText())
        savedURLs.clear()
        val urlsJson = json.optJSONObject("tabURLs")
        if (urlsJson != null) {
            val keys = urlsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                savedURLs[key.toInt()] = urlsJson.getString(key)
            }
        }
        if (savedURLs.isNotEmpty()) {
            evictionScope.launch { withContext(Dispatchers.Main) { restoreSavedTabs() } }
        }
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

# ── [R3-t03 W79] 重映射回调留下的三种「拼写都在、效果不在」─────────────
# 见证原文：回调原样返回 entry（目的路径从不写回）。
POOL_V_REMAP_ENTRY_ONLY = POOL_COMPLIANT.replace(
    "                    withPath = { entry, path -> entry.copy(destination = path) },\n",
    "                    withPath = { entry, path -> entry },\n",
)
# 见证原文的第二种拼写：参数名换成 `_`（连「看得见新路径」都不成立）
POOL_V_REMAP_UNDERSCORE = POOL_COMPLIANT.replace(
    "                    withPath = { entry, path -> entry.copy(destination = path) },\n",
    "                    withPath = { entry, _ -> entry },\n",
)
# 看得见 path，却写进了别的字段（destination 仍是旧路径）
POOL_V_REMAP_WRONG_FIELD = POOL_COMPLIANT.replace(
    "                    withPath = { entry, path -> entry.copy(destination = path) },\n",
    "                    withPath = { entry, path -> entry.copy(seen = path.isNotEmpty()) },\n",
)
# clean：参数改名 + 拆行（同一个决定的另一种拼写）
POOL_C_REMAP_RENAMED = POOL_COMPLIANT.replace(
    "                    withPath = { entry, path -> entry.copy(destination = path) },\n",
    "                    withPath = { entry, dest ->\n"
    "                        entry.copy(\n"
    "                            destination = dest,\n"
    "                        )\n"
    "                    },\n",
)

# ── [R3-t03 W80] 恢复派发被摘掉 / 被恒假闸门吞掉 ──────────────────────────
POOL_V_LOAD_NO_DISPATCH = POOL_COMPLIANT.replace(
    "        if (savedURLs.isNotEmpty()) {\n"
    "            evictionScope.launch { withContext(Dispatchers.Main) { restoreSavedTabs() } }\n"
    "        }\n", "")
# 见证原文：`if (false && savedURLs.isNotEmpty())`
POOL_V_LOAD_DEAD_DISPATCH = POOL_COMPLIANT.replace(
    "        if (savedURLs.isNotEmpty()) {\n"
    "            evictionScope.launch { withContext(Dispatchers.Main) { restoreSavedTabs() } }",
    "        if (false && savedURLs.isNotEmpty()) {\n"
    "            evictionScope.launch { withContext(Dispatchers.Main) { restoreSavedTabs() } }",
)
# clean：多加一个条件（`&& !isDisposed`）不是同一个决定，必须仍绿
POOL_C_LOAD_EXTRA_GUARD = POOL_COMPLIANT.replace(
    "        if (savedURLs.isNotEmpty()) {",
    "        if (savedURLs.isNotEmpty() && !isDisposed) {",
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


# --- [batch5-t01] the shapes five external witnesses used to pass --------
# G01-A1: the disposal guard is SPELLED inside a statically-dead block — the
# substring and its position survive, the decision does not.
POOL_V_DEAD_WRAPPED_GUARD = POOL_COMPLIANT.replace(
    "    private fun restoreSavedTabs() {\n        if (isDisposed) return\n",
    "    private fun restoreSavedTabs() {\n        if (!true) { if (isDisposed) return }\n",
)

# G01-A1b: a guard whose return only leaves a lambda is not a guard at all.
POOL_V_LAMBDA_GUARD = POOL_COMPLIANT.replace(
    "    private fun restoreSavedTabs() {\n        if (isDisposed) return\n",
    "    private fun restoreSavedTabs() {\n        run { if (isDisposed) return@run }\n",
)

# G01-A5: the scope is "cancelled" inside a dead block (single-statement form).
POOL_V_DEAD_CANCEL = POOL_COMPLIANT.replace(
    "        downloadScope.cancel()\n",
    "        if (false) downloadScope.cancel()\n",
)

# G01-A5b: both scopes are cancelled inside one dead block.
POOL_V_DEAD_CANCEL_BLOCK = POOL_COMPLIANT.replace(
    "        evictionScope.cancel()\n        downloadScope.cancel()\n",
    "        if (!true) { evictionScope.cancel(); downloadScope.cancel() }\n",
)

# G01-A2: the result copy re-projects the SOURCE text — the merged one is
# bound and thrown away, so the agent reads the un-annotated text.
POOL_V_RISK_SELF_PROJECTION = POOL_COMPLIANT.replace(
    "            return stamped.copy(text = text, riskChallengeDetected = true).text",
    "            return stamped.copy(text = stamped.text, riskChallengeDetected = true).text",
)

# negative control: merging through one extra local is still the notice.
POOL_OK_RISK_TWO_HOP = POOL_COMPLIANT.replace(
    "            return stamped.copy(text = text, riskChallengeDetected = true).text",
    "            val merged = text\n"
    "            return stamped.copy(text = merged, riskChallengeDetected = true).text",
)

# --- [da1040-B2] C9: the busy signal must be observable -------------------
# The defect: `Tab.inUse` flips in place (audit-b21 forbids the copy that would
# make the list change), so `tabs` stays content-equal and StateFlow swallows
# the emission; a sheet reading a plain getter composed once and froze.

def check_busy_source(pool_src: str, sheet_src: str) -> list:
    """[da1040-B2] C9 — one funnel for in-use writes, one reactive carrier, and
    a consumer that subscribes. Fail-closed throughout."""
    code, _lit, err = _structure(pool_src)
    if err is not None:
        return [f"C9: {err}"]
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
        elif _guard_dead(rhs):
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
    sheet, _sheet_lit, sheet_err = _structure(sheet_src)
    if sheet_err is not None:
        errs.append(f"C9: {sheet_err}")
        sheet = ""
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
    code, _lit, err = _structure(pool_src)
    if err is not None:
        return [f"C10: {err}"]
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
# [batch5-t01 G01-A3] `&& !true` is the same absorption with a different
# literal — the old literal whitelist ("true"/"false" as a whole operand) read
# it as a live conjunction.
BUSY_V_NOT_TRUE = BUSY_COMPLIANT.replace(
    "        _agentBusy.value = _tabs.value.any { it.inUse }",
    "        _agentBusy.value = _tabs.value.any { it.inUse } && !true")

busy_arms = [
    ("C9 compliant busy signal", BUSY_COMPLIANT, BUSY_SHEET_COMPLIANT, []),
    ("C9a reactive face removed", BUSY_V_NO_FLOW, BUSY_SHEET_COMPLIANT, ["C9"]),
    ("C9a non-reactive getter restored", BUSY_V_GETTER_BACK, BUSY_SHEET_COMPLIANT, ["C9"]),
    ("C9b publish no longer re-derived", BUSY_V_STALE_PUBLISH, BUSY_SHEET_COMPLIANT, ["C9"]),
    ("C9b publish short-circuited by a literal", BUSY_V_SHORT_CIRCUIT, BUSY_SHEET_COMPLIANT, ["C9"]),
    ("C9b publish absorbed by `&& !true`", BUSY_V_NOT_TRUE, BUSY_SHEET_COMPLIANT, ["C9"]),
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

# [batch5-t01 G01-A4] `return@run` leaves the lambda, not saveState: the
# borrow guard keeps its spelling and its position and still persists.
CTX_V_LAMBDA_GUARD = CTX_COMPLIANT.replace(
    "        if (isContextOnlySession) return\n",
    "        run { if (isContextOnlySession) return@run }\n",
)


def check_context_only_persist(pool_src: str) -> list:
    """[dv23-B4 B-04] C11 — the borrow latch is set on the context-only bind,
    cleared by a full bind, and consulted before saveState touches the file.
    Anchored on the DECISION (return before the write), not on spelling of the
    surrounding code; fail-closed everywhere."""
    code, lit, err = _structure(pool_src)
    if err is not None:
        return [f"C11: {err}"]
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
        # [batch5-t01 G01-A4] The guard has to DECIDE, not merely be spelled:
        # `run { if (isContextOnlySession) return@run }` leaves the lambda and
        # control falls straight through to `val sid = sessionId ?: return` and
        # the write. `_live_guard_positions` is the same predicate C4f uses
        # (dead spans + returns_exiting), so both latches are judged alike.
        guards = _live_guard_positions(save, "isContextOnlySession")
        guard = guards[0] if guards else -1
        if guard < 0:
            errs.append(
                "C11: saveState writes regardless of the borrow latch (no live, "
                "function-exiting `if (isContextOnlySession) return` before the tab file)")
        else:
            save_lit = (slice_body(lit, "private fun saveState")
                        or slice_body(lit, "fun saveState"))
            touches = [
                i for i in (save_lit.find("browser_tabs"),
                            save.find("getSharedPreferences"))
                if i >= 0
            ]
            first = min(touches) if touches else -1
            if first < 0 or guard > first:
                errs.append("C11: the borrow guard sits after the tab file is touched")
    return errs


# ── [witness-1027 W-12] C12: the storage page's MANUAL orphan assembly ───────
# `StorageManagementScreen.reload` does not call `SessionFileStore.scanOrphans`
# (one media walk, not two), it assembles the same `ReclaimReport` by hand:
# `scanOrphanSessionDirs(liveIds)` + `scanMediaOnce(liveIds)` +
# `scanOrphanBrowserTabFiles(liveIds)`. C1-C3 judge the STORE, and the store
# unit tests call the store — so this call site was outside every layer: pass
# `emptySet()` instead of `liveIds` and nothing fails, because the delete half
# (`reclaimOrphans(live)`) is a different call in a different lambda. The
# banner then counts every LIVE session's `browser_tabs/<sid>.json` as
# reclaimable residue and, since the delete half uses the real ids, "clean up"
# removes nothing and the banner returns on the next reload — forever.
#
# C12 scans ui/** (derived walk, no hardcoded file list) for the scan call:
#   C12a  the argument must not be a constant empty / literal-only collection
#         (`emptySet()`, `setOf()`, `listOf("x")` …) — that is the witness;
#   C12b  the file's delete half (`reclaimOrphans(…)`) must use the SAME source
#         (both arguments canonicalised: local `val` bindings inlined, then
#         whitespace dropped). Different symbols are fine as long as they
#         resolve to the same expression — the shipped tree binds `liveIds` and
#         `live` to the same `listSessions().map { it.id }.toSet()`;
#   C12c  a scan with no delete call at all is red: two predicates assembled
#         separately cannot be trusted to agree.
# Fail-closed: unlexable source, a missing UI tree, an empty argument = red.
UI_DIR = "src/android/app/src/main/java/com/rikkaminis/app/ui"
SCAN_CALL = "scanOrphanBrowserTabFiles"
DELETE_CALL = "reclaimOrphans"
EMPTY_CTORS = {
    "emptySet", "setOf", "mutableSetOf", "hashSetOf", "linkedSetOf", "sortedSetOf",
    "emptyList", "listOf", "mutableListOf", "arrayListOf", "setOfNotNull",
}
_CTOR_RE = re.compile(r"^(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*(?:<[^<>]*>)?\s*\((.*)\)$", re.S)
_IDENT_RUN = re.compile(r"(?<![\w.])[A-Za-z_]\w*(?![\w(])")
_LAMBDA_PARAM = re.compile(r"\{\s*([A-Za-z_]\w*)\s*->")
_CANON_DEPTH = 4


def _ln(code, pos):
    return code.count("\n", 0, pos) + 1


def _call_arg_lists(code, name):
    """[(pos, [raw argument texts])] for every real call to `name(…)`."""
    out = []
    for m in re.finditer(r"\b%s\s*\(" % re.escape(name), code):
        if re.search(r"\bfun\s+$", code[:m.start()]):
            continue
        spans = call_args(code, m.end() - 1)
        out.append((m.start(), [code[a:b].strip() for a, b in spans]))
    return out


def _val_before(code, name, pos):
    """(pos, initializer) of the last `val|var <name> = …` before `pos`."""
    pat = re.compile(r"(?<![\w.])(?:val|var)\s+" + re.escape(name) + r"\s*(?::[^=\n]+?)?=(?!=)")
    last = None
    for m in pat.finditer(code):
        if m.start() >= pos:
            break
        last = (m.start(), code[m.end():expr_end(code, m.end())].strip())
    return last


def _canonical(code, expr, pos, depth=0):
    """Inline local `val` bindings, then drop whitespace — the "same source" key.

    Symbol names are NOT compared: two halves may bind different names to the
    same expression, and requiring textual identity of the spelling would flag
    the shipped (correct) tree. What must match is the expression the ids come
    from.
    """
    e = strip_parens(" ".join(expr.split()))
    if depth >= _CANON_DEPTH or not e:
        return e
    lambda_params = {m.group(1) for m in _LAMBDA_PARAM.finditer(e)}

    def repl(m):
        nm = m.group(0)
        if nm in lambda_params or nm in ("it", "this", "true", "false", "null"):
            return nm
        decl = _val_before(code, nm, pos)
        if decl is None:
            return nm
        # No wrapping parens: both halves are compared as expressions with
        # whitespace dropped, and a stray pair would make identical sources
        # look different (a false RED on the shipped tree).
        return _canonical(code, decl[1], decl[0], depth + 1)

    return strip_parens(re.sub(r"\s+", "", _IDENT_RUN.sub(repl, e)))


def _collection_ctor(expr):
    """(ctor_name, inner_args) when the expression is a single constructor call."""
    m = _CTOR_RE.match(strip_parens(expr.strip()))
    if m is None:
        return None
    return m.group(1), m.group(2)


def _constant_empty(expr):
    c = _collection_ctor(expr)
    return bool(c) and c[0] in EMPTY_CTORS and not c[1].strip()


def _literal_only(expr):
    c = _collection_ctor(expr)
    return bool(c) and c[0] in EMPTY_CTORS and not _IDENT_RUN.search(c[1].strip())


def check_ui_source(src, label=""):
    """C12 findings for one UI source file (empty list = clean / not a site)."""
    tag = label or "ui source"
    try:
        code = code_view(src)
    except KotlinLexError as exc:
        return ["C12: %s is not lexable (%s) — fail closed" % (tag, exc)]
    scans = _call_arg_lists(code, SCAN_CALL)
    if not scans:
        return []
    deletes = _call_arg_lists(code, DELETE_CALL)
    # Messages READ the arguments, so they come from the literal view (a string
    # literal is blanked in `code`, and `setOf("a")` would print as `setOf(" ")`).
    shown = {p: a for p, a in _call_arg_lists(literal_view(src), SCAN_CALL)}
    shown_del = {p: a for p, a in _call_arg_lists(literal_view(src), DELETE_CALL)}
    findings = []
    for pos, args in scans:
        # JUDGMENT always on the code view (a literal here is just `"   "`, which
        # is what makes `setOf("a")` visibly literal-only); DISPLAY uses `shown`.
        raw_code = args[0] if args else ""
        raw = (shown.get(pos, args)[0] if shown.get(pos, args) else "")
        line = _ln(code, pos)
        if not raw_code.strip():
            findings.append(
                "C12: %s:%d — `%s(` has no argument; the live-id set cannot be "
                "established (fail closed)" % (tag, line, SCAN_CALL))
            continue
        canon = _canonical(code, raw_code, pos)
        if _constant_empty(canon) or _literal_only(canon):
            findings.append(
                "C12: %s:%d — `%s(%s)` measures orphans against a CONSTANT set: every live "
                "session's browser_tabs/<sid>.json counts as reclaimable residue, and since "
                "the delete half uses the real live ids the banner shows junk that a reclaim "
                "can never remove (it returns on the next reload)"
                % (tag, line, SCAN_CALL, " ".join(raw.split())))
            continue
        if not deletes:
            findings.append(
                "C12: %s:%d — the file scans orphans with `%s(` but never calls `%s(`; the "
                "banner's predicate and the delete's predicate are assembled separately, so "
                "nothing keeps them in agreement"
                % (tag, line, SCAN_CALL, DELETE_CALL))
            continue
        delete_canons = [(d[0], (shown_del.get(d[0], d[1])[0] if d[1] else "")) for d in deletes]
        if not any(arg.strip() and _canonical(code, arg, dpos) == canon
                   for dpos, arg in delete_canons):
            findings.append(
                "C12: %s:%d — the scan half derives its ids from `%s` while the delete half "
                "uses %s; the two halves disagree on what an orphan is, so the banner's count "
                "never matches what a reclaim pass removes"
                % (tag, line, " ".join(raw.split()),
                   ", ".join("`%s`" % " ".join(a.split()) for _, a in delete_canons)))
    return findings


def check_ui_tree(root):
    """C12 across the whole UI tree (derived walk — no hardcoded file list)."""
    base = os.path.join(root, UI_DIR)
    if not os.path.isdir(base):
        return ["C12: `%s` is missing — the manual assembly point cannot be checked "
                "(fail closed)" % UI_DIR]
    findings = []
    for dirpath, _dirs, files in os.walk(base):
        for name in sorted(files):
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            try:
                with open(path, encoding="utf-8") as fh:
                    src = fh.read()
            except OSError as exc:
                findings.append("C12: cannot read %s (%s) — fail closed"
                                % (os.path.relpath(path, root), exc))
                continue
            findings += check_ui_source(src, os.path.relpath(path, root))
    return findings



# ── C12 fixtures ([witness-1027 W-12]) ──────────────────────────────────────
UI_OK = """package p

@Composable
fun StorageManagementScreen(chatRepository: ChatRepository, sessionFiles: SessionFileStore) {
    val scope = rememberCoroutineScope()
    scope.launch {
        withContext(Dispatchers.IO) {
            val allSessions = chatRepository.dao.listSessions()
            val liveIds = allSessions.map { it.id }.toSet()
            val orphanSessionPart = sessionFiles.scanOrphanSessionDirs(liveIds)
            val orphanTabsPart = sessionFiles.scanOrphanBrowserTabFiles(liveIds)
            val orphans = sessionFiles.buildReport(orphanSessionPart, orphanTabsPart)
        }
    }
    MinisTextButton(onClick = {
        scope.launch {
            withContext(Dispatchers.IO) {
                val live = chatRepository.dao.listSessions().map { it.id }.toSet()
                sessionFiles.reclaimOrphans(live)
            }
        }
    })
}
"""

# W-12's witness: the banner measures against a constant empty set.
UI_W12 = UI_OK.replace("scanOrphanBrowserTabFiles(liveIds)",
                       "scanOrphanBrowserTabFiles(emptySet())")
# a literal-only set is just as constant, and just as stable-looking
UI_W12_LITERAL = UI_OK.replace("scanOrphanBrowserTabFiles(liveIds)",
                               'scanOrphanBrowserTabFiles(setOf("a"))').replace(
    "reclaimOrphans(live)", 'reclaimOrphans(setOf("a"))')
# the two halves resolve to DIFFERENT sources (the banner can never clear)
UI_HALVES_DISAGREE = UI_OK.replace(
    "sessionFiles.reclaimOrphans(live)",
    "sessionFiles.reclaimOrphans(liveIds.minus(\"a\"))")
# no delete half in the file at all
UI_NO_DELETE = UI_OK.replace("                sessionFiles.reclaimOrphans(live)\n", "")

# clean equivalents
UI_OK_SAME_SYMBOL = UI_OK.replace(
    """                val live = chatRepository.dao.listSessions().map { it.id }.toSet()
                sessionFiles.reclaimOrphans(live)""",
    "                sessionFiles.reclaimOrphans(liveIds)")
UI_OK_INLINE_BOTH = UI_OK.replace(
    "scanOrphanBrowserTabFiles(liveIds)",
    "scanOrphanBrowserTabFiles(allSessions.map { it.id }.toSet())")


def _c12_arms():
    """(name, source, needles, clean) — a violating arm must be red on C12."""
    return [
        ("C12 shipped shape is green", UI_OK, [], True),
        ("C12 both halves on one symbol is green", UI_OK_SAME_SYMBOL, [], True),
        ("C12 both halves inlined is green", UI_OK_INLINE_BOTH, [], True),
        ("W-12 emptySet() scan half is red", UI_W12,
         ["measures orphans against a CONSTANT set"], False),
        ("W-12b literal-only set is red", UI_W12_LITERAL,
         ["measures orphans against a CONSTANT set"], False),
        ("W-12c the two halves disagree is red", UI_HALVES_DISAGREE,
         ["disagree on what an orphan is"], False),
        ("W-12d no delete half at all is red", UI_NO_DELETE,
         ["never calls `reclaimOrphans(`"], False),
    ]


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

    # ── [R3-t03 W81] 极性：谓词在、调用在、删除发生在反的那一侧 ──────────
    # 见证原文逐字：`stem !in liveSessionIds` → `stem in liveSessionIds`。
    # C3 只问「函数体里有没有 looksLikeSessionId(」，翻转布尔它毫无知觉 ——
    # 后果是一次回收把**每个活会话**的浏览器状态与视口设置删光。
    v11 = compliant.replace(
        "if (isSessionShaped && stem !in liveSessionIds) file.delete()",
        "if (isSessionShaped && stem in liveSessionIds) file.delete()",
    )
    # W81 self-doubt：`!in live` 整体删掉 —— 谓词与调用都在，永不删除
    v12 = compliant.replace(
        "            if (isSessionShaped && stem !in liveSessionIds) file.delete()\n",
        "            if (isSessionShaped) Unit\n",
    )
    # clean：等价的极性写法 —— 守卫用正向成员测试 + 早退，删除留给其余分支
    v13 = compliant.replace(
        "            if (isSessionShaped && stem !in liveSessionIds) file.delete()\n",
        "            if (isSessionShaped && stem in liveSessionIds) return@forEach\n"
        "            if (isSessionShaped) file.delete()\n",
    )
    # clean：`!liveSessionIds.contains(stem)` 是同一个决定的另一种拼写
    v14 = compliant.replace(
        "if (isSessionShaped && stem !in liveSessionIds) file.delete()",
        "if (isSessionShaped && !liveSessionIds.contains(stem)) file.delete()",
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
        ("W81 V11 orphan delete polarity inverted", v11, ["C3b"]),
        ("W81 V12 orphan delete never runs (predicate kept)", v12, ["C3b"]),
        ("W81 V13 clean: early-exit guard spelling", v13, []),
        ("W81 V14 clean: !contains( spelling)", v14, []),
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
        ("C4f guard wrapped in a dead block", POOL_V_DEAD_WRAPPED_GUARD, ["C4f"]),
        ("C4f guard returns from a lambda only", POOL_V_LAMBDA_GUARD, ["C4f"]),
        ("C4e scope.cancel inside a dead block", POOL_V_DEAD_CANCEL, ["C4e"]),
        ("C4e both cancels inside one dead block", POOL_V_DEAD_CANCEL_BLOCK, ["C4e"]),
        ("C5 risk notice rendered but dropped", POOL_V_RISK_NOT_MERGED, ["C5"]),
        ("C5 risk notice bound then dropped", POOL_V_RISK_DROPPED, ["C5"]),
        ("C5 risk notice never rendered", POOL_V_RISK_NO_CALL, ["C5"]),
        ("C5 result copy re-projects the source text", POOL_V_RISK_SELF_PROJECTION, ["C5"]),
        ("C5 notice merged through one extra local stays green", POOL_OK_RISK_TWO_HOP, []),
        ("C6 setSession forgets to re-point downloads", POOL_V_SESSION_NO_REMAP, ["C6"]),
        ("C6 setSession rewrites _downloads by assignment", POOL_V_SESSION_PLAIN_ASSIGN, ["C6"]),
        ("W79 remap callback returns the entry untouched", POOL_V_REMAP_ENTRY_ONLY, ["C6b"]),
        ("W79 remap callback discards the new path (`_`)", POOL_V_REMAP_UNDERSCORE, ["C6b"]),
        ("W79 remap callback writes a different field", POOL_V_REMAP_WRONG_FIELD, ["C6b"]),
        ("W79 clean: renamed param + multi-line copy", POOL_C_REMAP_RENAMED, []),
        ("W80 loadSavedState drops the restore dispatch", POOL_V_LOAD_NO_DISPATCH, ["C6c"]),
        ("W80 restore dispatch dead-wrapped by `false &&`", POOL_V_LOAD_DEAD_DISPATCH, ["C6c"]),
        ("W80 clean: an extra live guard on the dispatch", POOL_C_LOAD_EXTRA_GUARD, []),
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
        ("C11 borrow guard returns from a lambda only", CTX_V_LAMBDA_GUARD, ["C11"]),
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
    destroy_names = [n for n, _ in _c10_destroy_callers(code_view(DESTROY_COMPLIANT))]
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

    # --- [witness-1027 W-12] C12 arms: the storage page's manual assembly ---
    for name, sample, expect, clean in _c12_arms():
        errs = check_ui_source(sample, "Fixture.kt")
        messages = "\n".join(errs)
        if clean:
            if errs:
                print(f"  FAIL {name}: expected green, got {errs}")
                failures += 1
            else:
                print(f"  OK {name}: green")
        elif not errs:
            print(f"  FAIL {name}: expected red, got green")
            failures += 1
        elif not all(e in messages for e in expect):
            print(f"  FAIL {name}: expected {expect}, got {errs}")
            failures += 1
        else:
            print(f"  OK {name}: {errs}")

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
    # [witness-1027 W-12] C12 — the UI-side manual orphan assembly (derived walk).
    errs += check_ui_tree(root)
    if errs:
        print("browser_tabs_lifecycle_check RED:")
        for e in errs:
            print(f"  - {e}")
        return 1
    print("browser_tabs_lifecycle_check OK (criteria: C1-C3 store lifecycle + C4/C4d pool teardown + window.open/close persistence + C4e disposal cancels every pool-owned scope + C4f the disposal latch guards saveState/restoreSavedTabs + C5 risk notice reaches the agent text + C6 setSession re-points downloads atomically + C7 the session-shape predicate alone carries the draft alias + C8 every orphan entry folds the composer's active draft in + C9 the pool's busy signal is funnelled and observable + C10 every destroyTab caller re-derives it after settling the list + C3b the orphan sweep deletes only non-live sessions (polarity) + C6b the remap callback writes the remapped path back + C6c loadSavedState actually dispatches the restore + C12 the UI-side manual orphan assembly uses the live ids and the SAME source as the delete half)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
