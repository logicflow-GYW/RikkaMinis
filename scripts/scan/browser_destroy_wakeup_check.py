#!/usr/bin/env python3
"""deep-audit-1019 ISSUE-08 (P1) — browser teardown wake-up + data:-URL payload gate.

Two silent stalls / one silent corruption, all inside
browser/BrowserUseManager.kt:

1. `destroy()` completed neither in-flight waiter. It cleared the file-chooser
   pins only, then swapped the client, removed and destroyed the WebView. After
   that no `onPageFinished` and no `__minis__.resolve`/`.reject` callback can
   ever arrive again, so `navigate()` / `reloadAndWait()` and `executeJS()` /
   `fetch()` each sat out their full 30s / 60s timeout and then reported a
   network timeout / JS timeout for a tab that no longer exists.
2. `onRendererGone` had woken the navigation waiter for a long time but not the
   async-JS waiter sitting next to it — same dead-renderer stall, same 30s/60s.
3. `deliverBlobDownload` decoded a non-base64 `data:` payload with a bare
   `java.net.URLDecoder.decode`, i.e. FORM semantics ('+' -> ' '), corrupting
   every such download that contained a literal '+': `data:text/plain,1+1%3D2`
   landed on disk as `1 1=2`. The sibling decoders in WebDavClient and
   OAuthCallbackServer already pre-escape the '+'.

This gate pins, on the shared lexer/ast/valueflow views:

  * `destroy()` wakes BOTH waiters (`navigationDeferred?.complete(` and an
    `asyncJsSlot.takeCurrent()` whose value reaches `.complete(…)`);
  * `onRendererGone` wakes the async-JS waiter too — same effect anchor, so
    taking the deferred out of the slot and dropping it is RED;
  * `AsyncJsSlot.takeCurrent()` still *takes* — [meta-audit-1024 anchor] the
    criterion used to be the declaration substring `"fun takeCurrent()"`, so
    the external witness rewrote the body to `return null` and the gate stayed
    green while every teardown wake-up became a no-op (the 1019 ISSUE-08 stall
    again). The body must read the slot BEFORE it clears it, clear it
    (`entry = null`) AND return a value derived from the taken entry; an
    unextractable body is RED (fail-closed);
  * the lifecycle latch must actually GATE: `if (destroyed) return` has to be
    feasible, un-inverted and placed before the write it protects
    (`if (destroyed && false) return` gates nothing);
  * every deferred block (`post { }` / `postDelayed { }`) must open with a
    FEASIBLE, correctly-polarised stand-down guard —
    `if (false && !canRunDeferred()) return` never stands down,
    `if (canRunDeferred()) return` never runs;
  * EVERY `URLDecoder.decode(` call site under src/android/app/src/main/java
    decodes a value that actually carries the `.replace("+", "%2B")` escape in
    its FIRST ARGUMENT (no allowlist: the two pre-existing call sites already
    comply, so any new bare call site is a regression by construction).

[deep-audit-1046 S-07] — the offscreen AndroidView hosts (`ui/markdown/KaTeXView.kt`,
`ui/sandbox/FilePreviewScreen.kt`) and the shared teardown policy
(`ui/preview/WebViewReleaseLatch.kt`). Their `onRelease` used
`removeCallbacks(null)` — a documented no-op (AOSP guards on `action != null`) —
with no latch, so a `postDelayed` capture could draw on a destroyed WebView and
cache an EMPTY bitmap under a formula's key (blank for the process lifetime).
Criteria, all derived from code shape: the release path latches the view before
it touches it; it consumes the shared `releasePlan(…)` order; it removes the
tracked runnables by identity; `stopLoading` still precedes `destroy`; every
deferred block opens with the latch guard; every named runnable is registered.

Usage: python3 browser_destroy_wakeup_check.py [ROOT]   (ROOT = repo root, default ".")
       python3 browser_destroy_wakeup_check.py --selftest
Exit:  0 = gate green, 1 = violations found (or selftest failures).
"""
import os
import re
import sys

from _kotlin_ast import (
    function_body as ast_function_body,
    if_blocks,
    match_bracket,
    strip_parens,
)
from _kotlin_lexer import KotlinLexError, mask_code, mask_comments
from _kotlin_valueflow import (
    call_args,
    contains_ident,
    expr_end,
    guard_feasible,
    scope_calls,
    value_sources,
)

MANAGER = "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserUseManager.kt"
HOLDER = "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/WebViewHolder.kt"
SLOT = "src/android/app/src/main/java/com/rikkaminis/app/browser/AsyncJsSlot.kt"
# [deep-audit-1046 S-07] the two offscreen AndroidView hosts + the shared policy
KATEX_VIEW = "src/android/app/src/main/java/com/rikkaminis/app/ui/markdown/KaTeXView.kt"
FILE_PREVIEW = "src/android/app/src/main/java/com/rikkaminis/app/ui/sandbox/FilePreviewScreen.kt"
RELEASE_POLICY = "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/WebViewReleaseLatch.kt"
SRC_ROOT = "src/android/app/src/main/java"

# [batch-7] The async-JS wake-up is no longer a substring arm: "the spelling
# `asyncJsSlot.takeCurrent()` appears" is satisfied by taking the deferred and
# dropping it (witness 07-1). It is graded by `take_wakes_waiter()` below, i.e.
# the taken deferred must reach `.complete(…)` on a live path.
DESTROY_MUST = (
    ("destroy() does not wake the navigation waiter", "navigationDeferred?.complete("),
)
RENDERER_MUST = ()
# [meta-audit-1024 anchor] `SLOT_MUST` was a declaration-shaped anchor: the
# witness rewrote the body to `return null` and the gate stayed green. The three
# regexes below are the EFFECT the teardown path depends on. `SLOT_MUST` is kept
# for the KDoc cross-reference only — it is no longer a criterion by itself.
SLOT_MUST = "fun takeCurrent()"
# reads the slot: any `entry` occurrence that is not a plain assignment target
# (`entry = …`); `entry == x` / `entry?.value` / `val current = entry` all count.
SLOT_READ_RE = re.compile(r"\bentry\b(?!\s*=(?!=))")
# clears it: the take must hand the entry out exactly once.
SLOT_CLEAR_RE = re.compile(r"\bentry\s*=\s*null\b")

# ── [deep-audit-1045 B-02] WebView lifecycle contract ────────────────────────
# Decision points, not spellings: a View-queue post on the manager's own
# WebView; the function that ARMS the one-shot blob bridge credential; any
# runtime UA writer that reloads a live page; both destroy() bodies.
VIEW_POST_RE = re.compile(r"(?<![\w.])\w*webView\.post(?:Delayed)?\s*[({]")
ARM_RE = re.compile(r"blobDownloadActiveToken\s*=\s*(?!null)\w")
UA_WRITE = "settings.userAgentString"
DETACH_TOKENS = ("removeView(", "detach(")
CLEAR_CALLBACKS = "removeCallbacks(null)"
TEARDOWN = "webView.destroy()"
LATCH = "destroyed"

CALL_RE = re.compile(r"URLDecoder\s*\.\s*decode\s*\(")
# the escape, in any spacing — matched against the first argument's VALUE
ESCAPE_RE = re.compile(r'\.\s*replace\s*\(\s*"\+"\s*,\s*"%2B"\s*\)')


# ── shared primitives ────────────────────────────────────────────────────────
# [batch-7] Every criterion below used to lean on this file's own lexer
# (`skip_string` / `skip_template` / `strip_comments` / `brace_body`) plus a
# literal dead-branch blacklist. Those are gone: a `}` inside a string, a
# template hole carrying its own literal, or `if (1 == 2)` each defeated one of
# them — and 5 of this gate's 6 batch-7 witnesses landed in exactly those
# places. Structure is read from `_kotlin_lexer.mask_code` (length preserving,
# so positions port straight back to the source), literals from
# `mask_comments`, control flow / value flow from `_kotlin_ast` /
# `_kotlin_valueflow`.

def code_of(src):
    """Structure view: comments and string interiors blanked, length preserved.

    Raises KotlinLexError on an unterminated literal. Every caller turns that
    into a VIOLATION (fail-closed) — never into a source-text fallback.
    """
    return mask_code(src)


def lit_of(src):
    """Literal view: only comments blanked — string contents stay readable."""
    return mask_comments(src)


def fn_span(doc, name):
    """(body_start, body_end) of `fun <name>(…)` in a code view, or None."""
    return ast_function_body(doc, name)


def fn_body(doc, name):
    """Body text of `fun <name>(…)` (outer braces stripped), or None."""
    r = fn_span(doc, name)
    return None if r is None else doc[r[0]:r[1]]


def fn_bodies(doc, lit, name):
    """(code_body, literal_body) — the SAME span in both views.

    Deciding "what the code does" on the literal view lets a comment vouch for
    a call that is not there; deciding "what the string says" on the code view
    reads blanks. Both are needed, and they are the same length, so the split
    is free.
    """
    r = fn_span(doc, name)
    if r is None:
        return None, None
    return doc[r[0]:r[1]], lit[r[0]:r[1]]


def reachable(text, pos):
    """Is the code at [pos] reachable? Every enclosing condition must be
    feasible, and the `else` side is graded on `!(cond)` — parenthesised.

    `path_feasible()` prefixes a bare `!` onto the condition, so a compound one
    (`a == null || true`) rebinds as `(!a) == null || true` and a dead branch
    looks live. The sites here gate positively on `if (destroyed) return`,
    where both agree, but negating correctly costs nothing.
    """
    for cond, t_start, t_end, e_start, e_end in if_blocks(text):
        if t_start <= pos < t_end:
            if not guard_feasible(cond):
                return False
        elif e_start is not None and e_start <= pos < e_end:
            if not guard_feasible(f"!({cond})"):
                return False
    return True


FUN_HEAD_RE = re.compile(r"\bfun\s+(?:<[^>\n]*>\s*)?(?:[\w.]+\.)?([A-Za-z_]\w*)\s*\(")


def iter_functions(doc):
    """-> [(name, body)] for every BLOCK-bodied `fun` in a code view.

    Derived, not listed: no criterion names the functions it polices, so a
    renamed / newly added UA writer or credential-arming site is covered on the
    day it lands. The parameter list and the body are delimited by the shared
    bracket matcher, so a `}` inside a string, a comment or a template hole can
    no longer close the body early (that is what made the old scanner read the
    wrong text).
    """
    out = []
    n = len(doc)
    for m in FUN_HEAD_RE.finditer(doc):
        p_close = match_bracket(doc, m.end() - 1)
        if p_close < 0:
            continue
        j = p_close + 1
        while j < n and doc[j] in " \t\r\n":
            j += 1
        if j < n and doc[j] == ":":
            while j < n and doc[j] not in "={":
                if doc[j] == "\n":
                    break
                j += 1
        if j >= n or doc[j] != "{":
            continue  # expression-bodied / abstract: nothing to grade
        b_close = match_bracket(doc, j)
        if b_close < 0:
            continue
        out.append((m.group(1), doc[j + 1:b_close]))
    return out


def analyse_lifecycle(manager_src, holder_src):
    """[deep-audit-1045 B-02] -> [(criterion, ok, detail)] for the WebView
    lifecycle contract: injected-script dispatch, runtime UA writers, and the
    teardown callback drain in BOTH owners."""
    mdoc, hdoc = code_of(manager_src), code_of(holder_src)
    mfns, hfns = iter_functions(mdoc), iter_functions(hdoc)
    out = []

    # C1 — the blob: download injection. Arms a one-shot bridge credential and
    # then injects a script: both halves must be there, the latch must gate
    # them, and the dispatch must not be a View-queue post (the pre-fix shape;
    # a detached pool tab never drains that queue, so the FileReader never runs
    # and the download is silently lost).
    armed = [(n, b) for n, b in mfns if ARM_RE.search(b)]
    if not armed:
        out.append((
            "no function arms the one-shot blob bridge credential (download path gone?)",
            False,
            "BrowserUseManager.kt",
        ))
    for name, body in armed:
        out.append((
            "the blob-download injection (%s) is not gated by the destroyed latch" % name,
            latch_guard(body, ARM_RE.search(body).start()),
            "BrowserUseManager.kt %s()" % name,
        ))
        out.append((
            "the blob-download injection (%s) does not dispatch the script" % name,
            "evaluateJavascript(" in body,
            "BrowserUseManager.kt %s()" % name,
        ))
        out.append((
            "the blob-download injection (%s) goes through the WebView's View post queue" % name,
            VIEW_POST_RE.search(body) is None,
            "BrowserUseManager.kt %s()" % name,
        ))

    # C2 — structural: the manager's own WebView must never be scheduled through
    # its View queue at all. `View.post` on a detached view parks the runnable in
    # HandlerActionQueue until a later attach; pool tabs never attach.
    hits = [mdoc[:m.start()].count("\n") + 1 for m in VIEW_POST_RE.finditer(mdoc)]
    out.append((
        "the manager schedules work on its own WebView through the View post queue",
        not hits,
        "BrowserUseManager.kt line(s) %s" % hits if hits else "no webView.post/postDelayed",
    ))

    # C3 — every RUNTIME UA switch (writes the UA *and* reloads a live page)
    # must consult the latch first. Construction-time configuration is exempt by
    # construction: it does not reload, so `configureWebView` stays untouched.
    writers = [
        (label, name, body)
        for label, fns in (("BrowserUseManager.kt", mfns), ("WebViewHolder.kt", hfns))
        for name, body in fns
        if UA_WRITE in body and "reload(" in body
    ]
    if not writers:
        out.append((
            "no runtime user-agent writer found (UA switch path gone?)",
            False,
            "BrowserUseManager.kt + WebViewHolder.kt",
        ))
    for label, name, body in writers:
        out.append((
            "the user-agent switch (%s) writes settings/reload without the destroyed latch" % name,
            latch_guard(body, body.find(UA_WRITE)),
            "%s %s()" % (label, name),
        ))

    # C4 — both destroy() bodies drop this view's queued callbacks BEFORE
    # destroy(), and only after the view is detached (a cleared attachInfo is
    # what keeps `removeCallbacks(null)` from draining the shared window
    # handler's null-token messages).
    for label, fns in (("BrowserUseManager.kt", mfns), ("WebViewHolder.kt", hfns)):
        destroys = [b for n, b in fns if n == "destroy"]
        if not destroys:
            out.append(("%s has no destroy() body" % label, False, label))
            continue
        for body in destroys:
            rb = body.find(CLEAR_CALLBACKS)
            td = body.find(TEARDOWN)
            detach = min([i for i in (body.find(t) for t in DETACH_TOKENS) if i >= 0] or [-1])
            out.append((
                "%s destroy() does not call webView.removeCallbacks(null) before webView.destroy()" % label,
                rb >= 0 and td >= 0 and rb < td,
                "%s destroy() removeCallbacks=%d destroy=%d" % (label, rb, td),
            ))
            out.append((
                "%s destroy() clears view callbacks before detaching the view" % label,
                rb >= 0 and detach >= 0 and detach < rb,
                "%s destroy() detach=%d removeCallbacks=%d" % (label, detach, rb),
            ))
    return out


# ── [deep-audit-1046 S-07] offscreen WebView release contract ────────────────
# Two `AndroidView` hosts (KaTeXView.kt / FilePreviewScreen.kt) schedule work on
# a WebView through its View queue and destroy it in `onRelease`. Their teardown
# called `removeCallbacks(null)` — a documented no-op (AOSP guards the method on
# `action != null`) — and their deferred bodies had no latch, so a capture block
# could draw on a destroyed WebView and cache an EMPTY bitmap under that
# formula's key. The criteria below are derived: the latch call, the removal of
# the tracked runnables, the teardown plan and the guard positions are all read
# from the code, never from a member list.
ONRELEASE_RE = re.compile(r"\bonRelease\s*=\s*\{")
DEFERRED_RE = re.compile(r"(?<![\w.])(?:post|postDelayed)\s*(?:\(\s*)?\{")
NAMED_RUNNABLE_RE = re.compile(r"\bval\s+([A-Za-z_]\w*)\s*=\s*Runnable\s*\{")
NAMED_REMOVE_RE = re.compile(r"removeCallbacks\(\s*(?!null\b)([^)\s])")
NULL_CLEAR = "removeCallbacks(null)"
DETACH_TOKENS_ANY = ("removeView(", "detach(")
POLICY_LISTS_RE = re.compile(r"listOf\(([^)]*)\)")
STEP_RE = re.compile(r"ReleaseStep\.([A-Z_]+)")

def lambda_body(text, brace_at):
    """`{...}` text at `brace_at` (index of a '{'), or None — braces included.

    Bracket matching comes from the shared ast (`match_bracket`), so a brace
    inside a string / comment / template cannot truncate the lambda.
    """
    i = brace_at
    if i < 0 or i >= len(text) or text[i] != "{":
        i = text.find("{", max(brace_at, 0))
        if i < 0:
            return None
    end = match_bracket(text, i)
    if end < 0:
        return None
    return text[i:end + 1]


def latch_guard(body, write_pos):
    """Is there a LIVE `if (<destroyed>…) { return/throw … }` before [write_pos]?

    The old criterion was `LATCH in body` — a spelling anchor. It is green for
    `if (destroyed && false) { return }` (the identifier is there, nothing is
    gated) and for `if (!destroyed) return` (polarity inverted: every live call
    is dropped instead). Both were witnesses.

    Graded here: the condition mentions the latch, is FEASIBLE
    (`guard_feasible` folds `&& false` / `1 == 2` / `!true`), is not negated,
    its branch exits the caller (bare return / throw, not `return@label`), and
    it stands BEFORE the write it is supposed to gate.
    """
    for cond, t_start, t_end, e_start, e_end in if_blocks(body):
        if write_pos >= 0 and t_start > write_pos:
            continue
        if not contains_ident(cond, LATCH):
            continue
        if strip_parens(cond.strip()).startswith("!"):
            continue  # inverted: keeps running exactly when destroyed
        if not guard_feasible(cond):
            continue  # `destroyed && false` gates nothing
        if re.search(r"\b(?:return|throw)\b(?!\s*@)", body[t_start:t_end]):
            return True
    return False

def deferred_body_guarded(body):
    """True iff the body's FIRST statement is a stand-down guard on the latch.

    The old rule only asked "does the first statement start with `if` and
    mention the latch and return" — so `if (false && !canRunDeferred()) return`
    (never taken: the block runs on a destroyed view, which is exactly the
    bug) and `if (canRunDeferred()) return` (polarity inverted: the block never
    runs at all, the formula never renders) both passed.

    Graded here: the condition is FEASIBLE, and its POLARITY is "stand down
    once released" — a negated `canRunDeferred()`, or a positive `isReleased`.
    """
    inner = body[1:]
    i = 0
    while i < len(inner) and inner[i] in " \t\r\n":
        i += 1
    if not inner.startswith("if", i):
        return False
    j = inner.find("(", i)
    if j < 0:
        return False
    close = match_bracket(inner, j)
    if close < 0:
        return False
    cond = inner[j + 1:close]
    if not re.match(r"\s*(?:return|throw)\b", inner[close + 1:close + 120]):
        return False
    c = strip_parens(cond.strip())
    negated = c.startswith("!")
    inner_c = strip_parens(c[1:].strip()) if negated else c
    if negated:
        if "canRunDeferred(" not in inner_c:
            return False
    elif not contains_ident(inner_c, "isReleased"):
        return False
    return guard_feasible(cond)


def analyse_release_latch(sources):
    """-> [(criterion, ok, detail)] for the offscreen WebView release contract.

    `sources` = [(label, source)] — one entry per AndroidView host.
    """
    out = []
    for label, src in sources:
        doc = code_of(src)
        bodies = []
        for m in ONRELEASE_RE.finditer(doc):
            body = lambda_body(doc, m.end() - 1)
            if body:
                bodies.append(body)
        if not bodies:
            out.append((
                "%s has no AndroidView onRelease teardown (the renderer leaks)" % label,
                False,
                label,
            ))
            continue
        for body in bodies:
            latch = body.find(".release()")
            plan = body.find("releasePlan(")
            drop = body.find("removeCallbacks(")
            stop = body.find("stopLoading()")
            destroy = body.find("destroy()")
            touches = [i for i in (plan, drop, stop, destroy) if i >= 0]
            out.append((
                "%s release path touches the WebView before latching it" % label,
                latch >= 0 and touches and latch < min(touches),
                "%s onRelease latch=%d first-touch=%s"
                % (label, latch, min(touches) if touches else "none"),
            ))
            out.append((
                "%s release path does not use the derived teardown plan" % label,
                plan >= 0,
                "%s onRelease" % label,
            ))
            out.append((
                "%s release path does not remove the deferred work by identity" % label,
                NAMED_REMOVE_RE.search(body) is not None,
                "%s onRelease" % label,
            ))
            out.append((
                "%s release path no longer stops and destroys the WebView" % label,
                stop >= 0 and destroy >= 0 and stop < destroy,
                "%s onRelease stop=%d destroy=%d" % (label, stop, destroy),
            ))
            # [r3 03-1] The four touches above are ORDER criteria: they are all
            # satisfied by a teardown that is textually complete and never runs.
            # The witness wrapped the whole plan in `if (false)` — every token
            # stays where it was, and every uncached formula keeps one live
            # WebView + renderer handle for the life of the process.
            dead = [nm for nm, pos in (("releasePlan", plan), ("removeCallbacks", drop),
                                       ("stopLoading", stop), ("destroy", destroy))
                    if pos >= 0 and not reachable(body, pos)]
            out.append((
                "%s release path runs its teardown plan on a live path" % label,
                not dead,
                ("%s onRelease: %s sit under an infeasible condition — the plan parses "
                 "but cannot execute, so the WebView leaks"
                 % (label, ", ".join(dead))) if dead
                else "%s onRelease teardown reachable" % label,
            ))
            # Defensive: a bulk null-clear may only run once the view is detached
            # (that is the only ordering under which it even has a local meaning;
            # as written it removes nothing at all, which is why it is gone).
            null_clear = body.find(NULL_CLEAR)
            if null_clear >= 0:
                detach = min([i for i in (body.find(t) for t in DETACH_TOKENS_ANY) if i >= 0] or [-1])
                out.append((
                    "%s release path clears view callbacks with removeCallbacks(null) "
                    "before detaching" % label,
                    detach >= 0 and detach < null_clear,
                    "%s onRelease detach=%d null-clear=%d" % (label, detach, null_clear),
                ))

        # Every deferred block — inline `post { … }` and named
        # `val x = Runnable { … }` alike — must open with the latch guard.
        blocks = [(m.start(), lambda_body(doc, m.end() - 1)) for m in DEFERRED_RE.finditer(doc)]
        blocks += [(m.start(), lambda_body(doc, m.end() - 1)) for m in NAMED_RUNNABLE_RE.finditer(doc)]
        if not blocks:
            out.append((
                "%s schedules no deferred work on its WebView at all (capture/load path gone?)"
                % label,
                False,
                label,
            ))
        for pos, body in blocks:
            if body is None:
                continue
            line = doc[:pos].count("\n") + 1
            out.append((
                "%s deferred block at line %d does not stand down on release" % (label, line),
                deferred_body_guarded(body),
                "%s:%d" % (label, line),
            ))

        # A named runnable that is never registered can never be removed by the
        # teardown — the registration is half of the contract.
        for m in NAMED_RUNNABLE_RE.finditer(doc):
            name = m.group(1)
            line = doc[:m.start()].count("\n") + 1
            out.append((
                "%s deferred runnable %s (line %d) is never registered with the release latch"
                % (label, name, line),
                re.search(r"\.track\(\s*" + re.escape(name) + r"\s*\)", doc) is not None,
                "%s:%d" % (label, line),
            ))
            # [r3 03-2] Registration is the OTHER half: the witness deleted
            # `post(initialLoad)` and left the guarded Runnable + its `.track(…)`
            # in place, so the load was never queued — the guard became
            # unreachable and every sandbox HTML preview stayed blank.
            scheduled = any(
                any(contains_ident(doc[a:b], name) for a, b in call_args(doc, m.end() - 1))
                for m in re.finditer(r"(?<![\w.])(?:post|postDelayed)\s*\(", doc)
            )
            out.append((
                "%s deferred runnable %s (line %d) is scheduled on the view queue"
                % (label, name, line),
                scheduled,
                ("%s:%d — registered with the latch but never posted: the stand-down "
                 "guard is unreachable and the deferred work never runs" % (label, line))
                if not scheduled else "%s:%d" % (label, line),
            ))
    return out

HANDLER_POST_RE = re.compile(r"\.\s*post(?:Delayed)?\s*\{")
KEYWORD_CALLS = frozenset(["if", "when", "for", "while", "catch", "return", "throw", "fun", "try", "else"])


def analyse_js_chokepoint(manager_src):
    """-> [(criterion, ok, detail)] for the main-thread JS execution chokepoint.

    [r3 03-3] A `Handler(mainLooper).post { … }` hop can land AFTER `destroy()`
    — the dispatch decision was taken before the hop, so it cannot see it. The
    callee of the hop must therefore re-check the latch when it RUNS.

    The witness deleted that re-check from `runJsNow`, and both older groups
    stayed green: it writes neither `blobDownloadActiveToken` nor
    `settings.userAgentString`, so the spelling-anchored C1/C3 never looked at
    it. Derived — every function invoked inside a main-thread `post { … }`
    block must gate its work behind a live latch guard.
    """
    doc = code_of(manager_src)
    out = []
    fns = {}
    for name, body in iter_functions(doc):
        fns.setdefault(name, []).append(body)
    hops = 0
    for m in HANDLER_POST_RE.finditer(doc):
        body = lambda_body(doc, m.end() - 1)
        if not body:
            continue
        for call in re.finditer(r"(?<![\w.])([A-Za-z_]\w*)\s*\(", body):
            fname = call.group(1)
            if fname not in fns:
                continue
            for cbody in fns[fname]:
                # The execution point: the first WebView touch, else the first
                # call the body makes (that is where the handed block runs).
                touches = [c.start() for c in
                           re.finditer(r"(?<![\w.])\w*[Ww]ebView\s*\.", cbody)]
                call2 = None
                for c2 in re.finditer(r"(?<![\w.])([A-Za-z_]\w*)\s*\(", cbody):
                    if c2.group(1) not in KEYWORD_CALLS:
                        call2 = c2
                        break
                pos = min(touches) if touches else (call2.start() if call2 else -1)
                hops += 1
                out.append((
                    "the deferred main-thread hop %s(…) re-checks the destroyed latch "
                    "when it runs" % fname,
                    pos >= 0 and latch_guard(cbody, pos),
                    ("%s(…) executes its work without a live `if (destroyed) … return` "
                     "guard — a destroy that landed during the Looper hop then touches "
                     "a destroyed WebView (the in-flight waiter is never woken, so the "
                     "caller sits out its full timeout)" % fname)
                    if not (pos >= 0 and latch_guard(cbody, pos))
                    else "%s(…) guarded" % fname,
                ))
    if hops == 0:
        out.append((
            "no main-thread JS hop (Handler(...).post { … }) found in BrowserUseManager",
            False,
            "re-point this guard — the execution chokepoint moved (fail-closed)",
        ))
    return out


def analyse_release_policy(src):
    """-> [(criterion, ok, detail)] for WebViewReleaseLatch.kt: the plan ORDER
    is the contract, and the two hosts cannot re-derive it locally."""
    doc = code_of(src)
    plans = []
    for m in POLICY_LISTS_RE.finditer(doc):
        steps = STEP_RE.findall(m.group(1))
        if steps:
            plans.append(steps)
    out = []
    out.append((
        "the release plan is not expressed as two driven step lists (attached / already detached)",
        len(plans) == 2,
        "WebViewReleaseLatch.kt plans=%d" % len(plans),
    ))
    if len(plans) == 2:
        out.append((
            "the attached-view plan does not detach first and destroy last",
            plans[0] == ["DETACH", "DROP_DEFERRED", "STOP_LOADING", "DESTROY"],
            "attached=%s" % plans[0],
        ))
        out.append((
            "the already-detached plan does not drop the detach step (or reorders the rest)",
            plans[1] == ["DROP_DEFERRED", "STOP_LOADING", "DESTROY"],
            "detached=%s" % plans[1],
        ))
    out.append((
        "WebViewReleaseLatch no longer exposes the deferred-work guard the hosts call",
        "fun canRunDeferred(" in doc and "fun release(" in doc and "fun track(" in doc,
        "WebViewReleaseLatch.kt",
    ))
    return out

TAKE_RE = re.compile(r"asyncJsSlot\s*\.\s*takeCurrent\s*\(\s*\)")
COMPLETE_RE = re.compile(r"\.\s*complete\s*\(")


def take_wakes_waiter(body, lit_body):
    """Does some LIVE `asyncJsSlot.takeCurrent()` hand its deferred to `.complete(…)`?

    The old criterion was the substring `asyncJsSlot.takeCurrent()` — taking the
    deferred out of the slot and DROPPING it satisfies that, while the waiter
    keeps burning its full 30s/60s timeout after the renderer died. That is the
    exact regression ISSUE-08 fixed, re-introduced by one deleted `?.complete(…)`.

    Graded here: the take must be on a reachable path and the deferred it
    returned must reach a `.complete(` in the same expression (directly via
    `?.complete(…)`, or through a scope call such as `?.let { it.complete(…) }`).
    """
    for m in TAKE_RE.finditer(body):
        if not reachable(body, m.start()):
            continue
        end = expr_end(body, m.start())
        if end <= m.start():
            continue
        if COMPLETE_RE.search(lit_body[m.start():end]):
            return True
    return False


def analyse_manager(manager_src):
    """-> [(criterion, ok, detail)] for the two teardown bodies."""
    doc = code_of(manager_src)
    lit = lit_of(manager_src)
    out = []
    destroy, destroy_lit = fn_bodies(doc, lit, "destroy")
    for crit, needle in DESTROY_MUST:
        out.append((crit, destroy is not None and needle in destroy, "destroy() body"))
    out.append((
        "destroy() does not wake the async-JS waiter",
        destroy is not None and take_wakes_waiter(destroy, destroy_lit),
        "destroy() body",
    ))
    onboard, onboard_lit = fn_bodies(doc, lit, "onRendererGone")
    for crit, needle in RENDERER_MUST:
        out.append((crit, onboard is not None and needle in onboard, "onRendererGone() body"))
    out.append((
        "onRendererGone does not wake the async-JS waiter",
        onboard is not None and take_wakes_waiter(onboard, onboard_lit),
        "onRendererGone() body",
    ))
    return out


def scan_lines(name, src):
    """-> [(name, lineno)] for every decode site whose FIRST ARGUMENT is not
    the `+`-escaped payload.

    The old rule grepped a five-line WINDOW around the call for the literal
    `.replace("+", "%2B")`, so
        p.replace("+", "%2B").let { URLDecoder.decode(p, "UTF-8") }
    was green (the escape is on the same lines) while the decoded text was the
    RAW payload — every `+` in it silently became a space. Graded here: the
    escape must be in the value the first argument actually evaluates to.
    """
    try:
        code = code_of(src)
        lit = lit_of(src)
    except KotlinLexError:
        return [(name, 0)]
    bad = []
    for m in CALL_RE.finditer(code):
        line = code[:m.start()].count("\n") + 1
        spans = call_args(code, m.end() - 1)
        if not spans:
            bad.append((name, line))
            continue
        a, b = spans[0]
        if not any(ESCAPE_RE.search(src_) for src_ in value_sources(lit[a:b])):
            bad.append((name, line))
    return bad


def url_decode_violations(root):
    """-> [(relpath, lineno)] for every bare form-oriented decode call site."""
    bad = []
    base = os.path.join(root, SRC_ROOT)
    for dirpath, _dirs, files in os.walk(base):
        for fname in sorted(files):
            if not fname.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fname)
            bad += scan_lines(
                os.path.relpath(path, root), open(path, encoding="utf-8").read()
            )
    return bad


def identity_peel(expr):
    """`.let { it }` / `.run { this }` hand the receiver straight through.

    `value_sources()` reads a scope block's tail expression, so an identity
    block reports `it` — which means "the receiver", and a value criterion that
    graded the spelling would call a value-preserving chain a discard.
    """
    for _ in range(4):
        calls = scope_calls(expr)
        if not calls:
            return expr
        name, start, open_pos, end = max(calls, key=lambda c: c[3])
        if name in ("let", "run") and expr[open_pos + 1:end - 1].strip() in ("it", "this"):
            expr = expr[:start].strip()
            continue
        return expr
    return expr


def analyse_slot(slot_src):
    """[meta-audit-1024 anchor] The teardown take must still *take*.

    The old criterion was the substring `"fun takeCurrent()"` on the
    comment-stripped file — a declaration-shaped anchor. The external witness
    rewrote the body to `return null` (never touching the declaration) and the
    gate stayed green, while `destroy()` / `onRendererGone`'s
    `takeCurrent()?.complete(…)` became a no-op and every in-flight
    `executeJS()` / `fetch()` waiter burned its full 30s/60s timeout after a
    tab teardown or a renderer crash (deep-audit-1019 ISSUE-08 again).

    Batch 7 adds two more arms on the same effect:
      * ORDER — the slot must be READ before it is cleared. `entry = null` on
        the first line plus `val current = entry ?: return null` after it
        returns null on every teardown, and every older arm was green.
      * VALUE — the returned value must still derive from the taken entry, not
        from a literal: `return current.let { null }` keeps "`.value` appears"
        false for the regex but true for nothing else.
    A body that cannot be extracted is RED (fail-closed), never a silent pass.
    """
    doc = code_of(slot_src)
    body = fn_body(doc, "takeCurrent")
    if body is None:
        return [
            ("AsyncJsSlot.takeCurrent() teardown entry point is gone",
             False, "AsyncJsSlot.kt"),
            ("AsyncJsSlot.takeCurrent() no longer reads the slot",
             False, "AsyncJsSlot.kt"),
            ("AsyncJsSlot.takeCurrent() no longer clears the slot",
             False, "AsyncJsSlot.kt"),
            ("AsyncJsSlot.takeCurrent() clears the slot before reading it",
             True, "AsyncJsSlot.kt"),
            ("AsyncJsSlot.takeCurrent() no longer returns the taken value",
             False, "AsyncJsSlot.kt"),
        ]
    reads = [m.start() for m in SLOT_READ_RE.finditer(body) if reachable(body, m.start())]
    clears = [m.start() for m in SLOT_CLEAR_RE.finditer(body) if reachable(body, m.start())]
    kept = None
    for m in re.finditer(r"\breturn\b(?![\s]*@)", body):
        if not reachable(body, m.start()):
            continue
        end = expr_end(body, m.end())
        expr = body[m.end():end].strip()
        if not expr:
            continue
        if any(re.search(r"\.value\b", src_) for src_ in value_sources(identity_peel(expr))):
            kept = m.start()
            break
    order_ok = (not reads) or (not clears) or min(reads) < min(clears)
    return [
        ("AsyncJsSlot.takeCurrent() teardown entry point is gone", True, "AsyncJsSlot.kt"),
        ("AsyncJsSlot.takeCurrent() no longer reads the slot", bool(reads), "AsyncJsSlot.kt"),
        ("AsyncJsSlot.takeCurrent() no longer clears the slot", bool(clears), "AsyncJsSlot.kt"),
        ("AsyncJsSlot.takeCurrent() clears the slot before reading it", order_ok, "AsyncJsSlot.kt"),
        ("AsyncJsSlot.takeCurrent() no longer returns the taken value",
         kept is not None and (not clears or kept > min(clears)), "AsyncJsSlot.kt"),
    ]


# --- fixtures ----------------------------------------------------------------
GOOD_MANAGER = """
    fun destroy() {
        runCatching { pendingFileChooser?.onReceiveValue(null) }
        pendingFileChooser = null
        navigationDeferred?.complete(NavigationResult(NavigationOutcome.FAILED))
        navigationDeferred = null
        asyncJsSlot.takeCurrent()?.complete("{\\"error\\":\\"gone\\"}")
        webView.destroy()
    }
    private val x = object : SafeWebViewClient() {
        override fun onRendererGone(view: WebView?) {
            navigationDeferred?.complete(NavigationResult(NavigationOutcome.FAILED))
            asyncJsSlot.takeCurrent()?.complete("{\\"error\\":\\"killed\\"}")
        }
    }
"""

BAD_DESTROY_NAV = GOOD_MANAGER.replace("navigationDeferred?.complete(NavigationResult(NavigationOutcome.FAILED))\n        navigationDeferred = null\n        asyncJsSlot", "asyncJsSlot")
BAD_DESTROY_JS = GOOD_MANAGER.replace('asyncJsSlot.takeCurrent()?.complete("{\\"error\\":\\"gone\\"}")\n', "")
BAD_RENDERER_JS = GOOD_MANAGER.replace('asyncJsSlot.takeCurrent()?.complete("{\\"error\\":\\"killed\\"}")\n', "")


# --- [deep-audit-1045 B-02] lifecycle fixtures -------------------------------
# Shapes mirror the real files: the blob injection arms a one-shot credential
# then dispatches; the UA switch writes and reloads; destroy detaches, stops,
# drops this view's queue, destroys.
GOOD_LIFECYCLE_MANAGER = """
    private fun fetchBlobDownload(url: String) {
        if (destroyed) {
            return
        }
        blobDownloadActiveToken = token
        runJsOnMain("blob-download") { webView.evaluateJavascript(js, null) }
    }

    fun setUserAgent(profile: UserAgentProfile, customUA: String? = null) {
        if (destroyed) {
            return
        }
        webView.settings.userAgentString = ua
        webView.reload()
    }

    fun destroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.removeCallbacks(null)
        webView.destroy()
    }
"""

GOOD_LIFECYCLE_HOLDER = """
    fun toggleDesktopMode() {
        if (destroyed.get()) {
            return
        }
        webView.settings.userAgentString = DESKTOP_UA
        webView.reload()
    }

    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        detach()
        webView.stopLoading()
        webView.removeCallbacks(null)
        webView.destroy()
    }
"""


# --- [deep-audit-1046 S-07] release-latch fixtures ---------------------------
# Shapes mirror the real files: a latch per WebView, both deferred bodies guarded,
# a named capture runnable registered and removed by identity, teardown driven by
# the shared plan.
GOOD_KATEX = """
        key(cacheKey, rendererEpoch) {
            val releaseLatch = remember { WebViewReleaseLatch() }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        addJavascriptInterface(object {
                            fun onRendered(width: Int, height: Int, error: String, token: Long) {
                                if (!releaseLatch.canRunDeferred()) return
                                post {
                                    if (!releaseLatch.canRunDeferred()) return@post
                                    layoutParams = ViewGroup.LayoutParams(bitmapW, bitmapH)
                                    val capture = Runnable {
                                        if (!releaseLatch.canRunDeferred()) return@Runnable
                                        renderedBitmap = bitmap
                                    }
                                    releaseLatch.track(capture)
                                    postDelayed(capture, 100)
                                }
                            }
                        }, "AndroidBridge")
                    }
                },
                onRelease = { wv ->
                    val outstanding = releaseLatch.release()
                    for (step in releasePlan(attached = wv.parent != null)) {
                        when (step) {
                            ReleaseStep.DETACH -> (wv.parent as? ViewGroup)?.removeView(wv)
                            ReleaseStep.DROP_DEFERRED -> for (task in outstanding) wv.removeCallbacks(task)
                            ReleaseStep.STOP_LOADING -> wv.stopLoading()
                            ReleaseStep.DESTROY -> wv.destroy()
                        }
                    }
                },
            )
        }
"""

GOOD_PREVIEW = """
    key(item.file, rendererEpoch) {
    val releaseLatch = remember { WebViewReleaseLatch() }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                val initialLoad = Runnable {
                    if (!releaseLatch.canRunDeferred()) return@Runnable
                    loadUrl(targetUrl)
                }
                releaseLatch.track(initialLoad)
                post(initialLoad)
            }
        },
        onRelease = { wv ->
            val outstanding = releaseLatch.release()
            for (step in releasePlan(attached = wv.parent != null)) {
                when (step) {
                    ReleaseStep.DETACH -> (wv.parent as? ViewGroup)?.removeView(wv)
                    ReleaseStep.DROP_DEFERRED -> for (task in outstanding) wv.removeCallbacks(task)
                    ReleaseStep.STOP_LOADING -> wv.stopLoading()
                    ReleaseStep.DESTROY -> wv.destroy()
                }
            }
        },
    )
"""

# [r3 03-3] the main-thread dispatch chain: the hop callee must re-check the
# latch at execution time (the dispatch decision was taken before the hop).
GOOD_DISPATCH_MANAGER = """
    private fun runJsOnMain(what: String, block: () -> Unit) {
        when (jsDispatchFor(destroyed, Looper.myLooper() == Looper.getMainLooper())) {
            JsDispatch.REJECT_DESTROYED -> Log.w(TAG, "dropped")
            JsDispatch.INLINE -> runJsNow(what, block)
            JsDispatch.HOP_TO_MAIN -> Handler(Looper.getMainLooper()).post { runJsNow(what, block) }
        }
    }

    private fun runJsNow(what: String, block: () -> Unit) {
        if (destroyed) {
            Log.w(TAG, "JS dispatch dropped")
            return
        }
        runCatching(block).onFailure { Log.w(TAG, "failed") }
    }
"""

BAD_DISPATCH_MANAGER = GOOD_DISPATCH_MANAGER.replace(
    '        if (destroyed) {\n            Log.w(TAG, "JS dispatch dropped")\n            return\n        }\n',
    "")
assert BAD_DISPATCH_MANAGER != GOOD_DISPATCH_MANAGER

GOOD_POLICY = """
internal fun releasePlan(attached: Boolean): List<ReleaseStep> =
    if (attached) {
        listOf(ReleaseStep.DETACH, ReleaseStep.DROP_DEFERRED, ReleaseStep.STOP_LOADING, ReleaseStep.DESTROY)
    } else {
        listOf(ReleaseStep.DROP_DEFERRED, ReleaseStep.STOP_LOADING, ReleaseStep.DESTROY)
    }

internal class WebViewReleaseLatch {
    fun canRunDeferred(): Boolean = !released.get()
    fun track(task: Runnable) { }
    fun release(): List<Runnable> = emptyList()
}
"""

# The pre-fix shape, verbatim in structure: unguarded nested `post { postDelayed }`,
# a null-clear teardown, no latch.
BASE_KATEX = """
        key(cacheKey, rendererEpoch) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        addJavascriptInterface(object {
                            fun onRendered(width: Int, height: Int, error: String, token: Long) {
                                post {
                                    layoutParams = ViewGroup.LayoutParams(bitmapW, bitmapH)
                                    postDelayed({
                                        draw(canvas)
                                        KaTeXRendererCache.cache.put(cacheKey, entry)
                                    }, 100)
                                }
                            }
                        }, "AndroidBridge")
                    }
                },
                onRelease = { wv ->
                    wv.removeCallbacks(null)
                    wv.stopLoading()
                    wv.destroy()
                },
            )
        }
"""

# number of selftest arms executed (reported in the closing marker so the CI
# log carries the arm count, not just PASS)
ARMS = 0


def selftest():
    fails = []

    def expect(label, got_ok, want_ok, crit_filter=None):
        global ARMS
        ARMS += 1
        if got_ok != want_ok:
            fails.append(label)
            print("  [FAIL] %s -> ok=%s (want %s)" % (label, got_ok, want_ok))
        else:
            print("  [OK] %s -> ok=%s" % (label, got_ok))

    def manager_ok(src, crit_substr):
        res = analyse_manager(src)
        hit = [ok for c, ok, _ in res if crit_substr in c]
        if len(hit) != 1:
            return None
        return hit[0]

    # 1-3: the three teardown wake-ups, each removed in turn.
    expect("clean fixture: destroy wakes navigation", manager_ok(GOOD_MANAGER, "navigation waiter"), True)
    expect("clean fixture: destroy wakes async-JS", manager_ok(GOOD_MANAGER, "destroy() does not wake the async-JS"), True)
    expect("clean fixture: onRendererGone wakes async-JS", manager_ok(GOOD_MANAGER, "onRendererGone does not"), True)
    expect("breaker: destroy without navigation wake", manager_ok(BAD_DESTROY_NAV, "navigation waiter"), False)
    expect("breaker: destroy without async-JS wake", manager_ok(BAD_DESTROY_JS, "destroy() does not wake the async-JS"), False)
    expect("breaker: onRendererGone without async-JS wake", manager_ok(BAD_RENDERER_JS, "onRendererGone does not"), False)

    # 4: the gate must not be satisfied by a comment that merely mentions it.
    commented = GOOD_MANAGER.replace("navigationDeferred?.complete(", "// navigationDeferred?.complete(")
    expect("breaker: navigation wake commented out", manager_ok(commented, "navigation waiter"), False)

    # 07-1 (batch-7): the deferred is TAKEN and DROPPED — the old substring arm
    # ("`asyncJsSlot.takeCurrent()` appears") is satisfied while the waiter keeps
    # burning its full 30s/60s timeout after a teardown / renderer crash.
    bare_teardown = re.sub(r"asyncJsSlot\.takeCurrent\(\)\?\.complete\([^\n]*",
                           "asyncJsSlot.takeCurrent()", GOOD_MANAGER)
    expect("witness: every async-JS wake-up takes the deferred and drops it",
           manager_ok(bare_teardown, "destroy() does not wake the async-JS"), False)
    expect("witness: onRendererGone takes the deferred and drops it",
           manager_ok(bare_teardown, "onRendererGone does not"), False)
    completed_via_scope = GOOD_MANAGER.replace(
        'asyncJsSlot.takeCurrent()?.complete("{\\"error\\":\\"gone\\"}")',
        'asyncJsSlot.takeCurrent()?.let { it.complete("{\\"error\\":\\"gone\\"}") }',
    )
    expect("precision: completing the take through `?.let { … }` still wakes the waiter",
           manager_ok(completed_via_scope, "destroy() does not wake the async-JS"), True)

    # 5: the escape check (real call into scan_lines, not a sanity string).
    good_escape = 'val s = URLDecoder.decode(p.replace("+", "%2B"), "UTF-8")'
    bad_escape = 'val s = URLDecoder.decode(p, "UTF-8")'
    multi_good = 'val s = URLDecoder.decode(\n    p.replace("+", "%2B"),\n    "UTF-8",\n)'
    multi_bad = 'val s = URLDecoder.decode(\n    p,\n    "UTF-8",\n)'
    expect("clean fixture: escaped call site", scan_lines("f.kt", good_escape) == [], True)
    expect("clean fixture: escape on a continuation line", scan_lines("f.kt", multi_good) == [], True)
    expect("breaker: bare call site", scan_lines("f.kt", bad_escape) == [], False)
    expect("breaker: bare call across lines", scan_lines("f.kt", multi_bad) == [], False)
    expect(
        "precision: a comment mentioning a bare call is not a call",
        scan_lines("f.kt", 'val x = 1 // URLDecoder.decode(p, "UTF-8")') == [],
        True,
    )
    expect(
        "precision: a bare call inside a KDoc block is not a call",
        scan_lines("f.kt", "/** old shape was URLDecoder.decode(p, \"UTF-8\") */\nval y = 2") == [],
        True,
    )
    # 07-4 (batch-7): the escape sits on the same lines as the call but not in
    # the value being decoded — a +/-line window cannot see the difference.
    expect(
        "witness: the escape is applied to a value the call does not decode",
        scan_lines("f.kt",
                   'val s = p.replace("+", "%2B").let { URLDecoder.decode(p, "UTF-8") }') == [],
        False,
    )
    expect(
        "precision: the escape carried through a non-discarding call is accepted",
        scan_lines("f.kt",
                   'val s = URLDecoder.decode(p.replace("+", "%2B").trim(), "UTF-8")') == [],
        True,
    )

    # 7: slot teardown take — [meta-audit-1024 anchor] the BODY, not the
    # declaration. `SLOT_MUST` used to be the whole criterion; the witness
    # (body -> `return null`) kept it true while the teardown wake-up became a
    # no-op. Every arm below states its expected red set up front.
    def slot_reds(src):
        return [c for c, ok, _ in analyse_slot(src) if not ok]

    def slot_expect(label, src, want):
        got = slot_reds(src)
        ok = len(got) == len(want) and all(any(w in c for c in got) for w in want)
        expect("%s -> red set %s" % (label, got or "[]"), ok, True)

    GOOD_SLOT = (
        "internal class AsyncJsSlot<T : Any> {\n"
        "    @Volatile\n"
        "    private var entry: Entry<T>? = null\n"
        "    fun takeCurrent(): T? {\n"
        "        val current = entry ?: return null\n"
        "        entry = null\n"
        "        return current.value\n"
        "    }\n"
        "}\n"
    )
    slot_expect("clean fixture: takeCurrent reads+clears+returns", GOOD_SLOT, [])

    # The witness, verbatim: same class, same declaration, body gutted.
    WITNESS_SLOT = GOOD_SLOT.replace(
        "        val current = entry ?: return null\n"
        "        entry = null\n"
        "        return current.value\n",
        "        return null\n",
    )
    slot_expect(
        "witness fixture: takeCurrent body returns null",
        WITNESS_SLOT,
        ["no longer reads", "no longer clears", "no longer returns"],
    )

    slot_expect(
        "breaker: takeCurrent reads but never clears the slot",
        GOOD_SLOT.replace("        entry = null\n", ""),
        ["no longer clears"],
    )
    # 07-2 (batch-7): the slot is cleared before it is read, so every teardown
    # take hands out null — all three older arms (reads/clears/returns) stay green.
    slot_expect(
        "witness: takeCurrent clears before it reads",
        GOOD_SLOT.replace(
            "        val current = entry ?: return null\n        entry = null\n",
            "        entry = null\n        val current = entry ?: return null\n",
        ),
        ["clears the slot before reading it"],
    )
    # 07-6 (batch-7): the taken value is nullified just before it is returned.
    slot_expect(
        "witness: the taken value is nullified inside `.let { null }`",
        GOOD_SLOT.replace("return current.value", "return current.let { null }"),
        ["no longer returns"],
    )
    slot_expect(
        "witness: `.value` is read and then nullified on the same line",
        GOOD_SLOT.replace("return current.value", "return current.value.let { null }"),
        ["no longer returns"],
    )
    slot_expect(
        "precision: a value-preserving `.let { it }` still returns the take",
        GOOD_SLOT.replace("return current.value", "return current.value.let { it }"),
        [],
    )

    slot_expect(
        "breaker: takeCurrent clears but drops the value",
        GOOD_SLOT.replace("        return current.value\n", "        return null\n"),
        ["no longer returns"],
    )
    # Re-assigning the same reference back is not a clear — the entry is still
    # there for the next teardown to hand out twice.
    slot_expect(
        "breaker: takeCurrent reassigns the entry instead of clearing it",
        GOOD_SLOT.replace("        entry = null\n", "        entry = entry\n"),
        ["no longer clears"],
    )
    slot_expect(
        "breaker: takeCurrent declaration removed",
        GOOD_SLOT.replace("fun takeCurrent()", "fun takeWhatever()"),
        ["entry point is gone", "no longer reads", "no longer clears", "no longer returns"],
    )

    # The real AsyncJsSlot.kt must satisfy all four: the witness arms above are
    # only meaningful if the accepted shape is the shape on disk.
    repo = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    slot_path = os.path.join(repo, SLOT)
    if not os.path.isfile(slot_path):
        fails.append("real tree: %s missing — cannot evaluate the slot criterion" % SLOT)
        print("  [FAIL] real tree: %s missing" % SLOT)
    else:
        real_slot = open(slot_path, encoding="utf-8").read()
        slot_expect("real tree: AsyncJsSlot.takeCurrent", real_slot, [])
        # Verbatim witness from the external audit: exactly one match, and it
        # must red every body criterion. A drifting file (body rewritten) makes
        # this arm FAIL loudly instead of silently proving nothing.
        witness_find = (
            "    fun takeCurrent(): T? {\n"
            "        val current = entry ?: return null\n"
            "        entry = null\n"
            "        return current.value\n"
            "    }"
        )
        n = real_slot.count(witness_find)
        expect("real tree: witness anchor occurs exactly once (found %d)" % n, n == 1, True)
        if n == 1:
            slot_expect(
                "real tree + witness patch: gate reds the gutted body",
                real_slot.replace(witness_find, "    fun takeCurrent(): T? {\n        return null\n    }"),
                ["no longer reads", "no longer clears", "no longer returns"],
            )

    # ── [deep-audit-1045 B-02] lifecycle contract ────────────────────────────
    # Every arm states its EXPECTED RED SET up front (hand-written from the
    # criterion table, never derived from a run): an arm that reddens a
    # neighbouring criterion fails the selftest, which is what keeps "one arm
    # per criterion" honest.
    def expect_red(label, mgr, holder, want):
        global ARMS
        ARMS += 1
        got = [c for c, ok, _ in analyse_lifecycle(mgr, holder) if not ok]
        for w in want:
            if not any(w in c for c in got):
                fails.append(label + " | missing red: " + w)
                print("  [FAIL] %s -> no red for %r" % (label, w))
        for c in got:
            if not any(w in c for w in want):
                fails.append(label + " | unexpected red: " + c)
                print("  [FAIL] %s -> unexpected red %r" % (label, c))
        if all(any(w in c for c in got) for w in want) and all(any(w in c for w in want) for c in got):
            print("  [OK] %s -> red set %d" % (label, len(got)))

    expect_red("clean fixture: lifecycle contract", GOOD_LIFECYCLE_MANAGER, GOOD_LIFECYCLE_HOLDER, [])

    viewpost = GOOD_LIFECYCLE_MANAGER.replace(
        'runJsOnMain("blob-download") { webView.evaluateJavascript(js, null) }',
        "webView.post { webView.evaluateJavascript(js, null) }",
    )
    expect_red(
        "breaker: the injection is a View-queue post again",
        viewpost,
        GOOD_LIFECYCLE_HOLDER,
        ["goes through the WebView's View post queue", "schedules work on its own WebView"],
    )

    no_arm = GOOD_LIFECYCLE_MANAGER.replace("        blobDownloadActiveToken = token\n", "")
    expect_red(
        "breaker: nothing arms the blob bridge credential",
        no_arm,
        GOOD_LIFECYCLE_HOLDER,
        ["no function arms the one-shot blob bridge credential"],
    )

    armed_unlatched = GOOD_LIFECYCLE_MANAGER.replace(
        "    private fun fetchBlobDownload(url: String) {\n        if (destroyed) {\n            return\n        }\n",
        "    private fun fetchBlobDownload(url: String) {\n",
    )
    expect_red(
        "breaker: the blob injection drops the destroyed latch",
        armed_unlatched,
        GOOD_LIFECYCLE_HOLDER,
        ["is not gated by the destroyed latch"],
    )

    commented_latch = GOOD_LIFECYCLE_MANAGER.replace(
        "        if (destroyed) {\n            return\n        }\n        blobDownloadActiveToken = token",
        "        // if (destroyed) { return }\n        blobDownloadActiveToken = token",
    )
    expect_red(
        "precision: a commented-out latch is not a latch",
        commented_latch,
        GOOD_LIFECYCLE_HOLDER,
        ["is not gated by the destroyed latch"],
    )

    ua_unlatched = GOOD_LIFECYCLE_MANAGER.replace(
        "    fun setUserAgent(profile: UserAgentProfile, customUA: String? = null) {\n        if (destroyed) {\n            return\n        }\n",
        "    fun setUserAgent(profile: UserAgentProfile, customUA: String? = null) {\n",
    )
    expect_red(
        "breaker: the manager UA switch drops the latch",
        ua_unlatched,
        GOOD_LIFECYCLE_HOLDER,
        ["user-agent switch (setUserAgent) writes"],
    )

    holder_unlatched = GOOD_LIFECYCLE_HOLDER.replace(
        "    fun toggleDesktopMode() {\n        if (destroyed.get()) {\n            return\n        }\n",
        "    fun toggleDesktopMode() {\n",
    )
    expect_red(
        "breaker: the holder UA switch drops the latch",
        GOOD_LIFECYCLE_MANAGER,
        holder_unlatched,
        ["user-agent switch (toggleDesktopMode) writes"],
    )

    construct_only = GOOD_LIFECYCLE_MANAGER.replace(
        "    fun setUserAgent(profile: UserAgentProfile, customUA: String? = null) {",
        "    fun configureWebView(webView: WebView) {\n"
        "        webView.settings.userAgentString = ua\n"
        "    }\n\n"
        "    fun setUserAgent(profile: UserAgentProfile, customUA: String? = null) {",
    )
    expect_red(
        "precision: a construction-time UA write needs no latch (it does not reload)",
        construct_only,
        GOOD_LIFECYCLE_HOLDER,
        [],
    )

    # 07-5 (batch-7): the latch identifier is present but the guard gates
    # nothing (`&& false`) or runs on the wrong polarity (`!destroyed`). Both were
    # green for the old `LATCH in body` arm.
    dead_ua_latch = GOOD_LIFECYCLE_MANAGER.replace(
        "    fun setUserAgent(profile: UserAgentProfile, customUA: String? = null) {\n"
        "        if (destroyed) {\n",
        "    fun setUserAgent(profile: UserAgentProfile, customUA: String? = null) {\n"
        "        if (destroyed && false) {\n",
    )
    expect_red(
        "witness: the UA latch guard is dead (`destroyed && false`)",
        dead_ua_latch,
        GOOD_LIFECYCLE_HOLDER,
        ["user-agent switch (setUserAgent) writes"],
    )

    inverted_ua_latch = GOOD_LIFECYCLE_MANAGER.replace(
        "    fun setUserAgent(profile: UserAgentProfile, customUA: String? = null) {\n"
        "        if (destroyed) {\n",
        "    fun setUserAgent(profile: UserAgentProfile, customUA: String? = null) {\n"
        "        if (!destroyed) {\n",
    )
    expect_red(
        "witness: the UA latch guard is inverted (`!destroyed`)",
        inverted_ua_latch,
        GOOD_LIFECYCLE_HOLDER,
        ["user-agent switch (setUserAgent) writes"],
    )

    dead_arm_latch = GOOD_LIFECYCLE_MANAGER.replace(
        "    private fun fetchBlobDownload(url: String) {\n"
        "        if (destroyed) {\n",
        "    private fun fetchBlobDownload(url: String) {\n"
        "        if (destroyed && false) {\n",
    )
    expect_red(
        "witness: the blob-arm latch guard is dead (`destroyed && false`)",
        dead_arm_latch,
        GOOD_LIFECYCLE_HOLDER,
        ["is not gated by the destroyed latch"],
    )

    late_ua_latch = GOOD_LIFECYCLE_MANAGER.replace(
        "    fun setUserAgent(profile: UserAgentProfile, customUA: String? = null) {\n"
        "        if (destroyed) {\n"
        "            return\n"
        "        }\n"
        "        webView.settings.userAgentString = ua\n",
        "    fun setUserAgent(profile: UserAgentProfile, customUA: String? = null) {\n"
        "        webView.settings.userAgentString = ua\n"
        "        if (destroyed) {\n"
        "            return\n"
        "        }\n",
    )
    expect_red(
        "witness: the UA latch guard sits after the write it protects",
        late_ua_latch,
        GOOD_LIFECYCLE_HOLDER,
        ["user-agent switch (setUserAgent) writes"],
    )

    mgr_teardown = GOOD_LIFECYCLE_MANAGER.replace("        webView.removeCallbacks(null)\n", "")
    expect_red(
        "breaker: the manager destroy() drops the queued callbacks",
        mgr_teardown,
        GOOD_LIFECYCLE_HOLDER,
        [
            "BrowserUseManager.kt destroy() does not call webView.removeCallbacks(null)",
            "BrowserUseManager.kt destroy() clears view callbacks before detaching",
        ],
    )

    holder_teardown = GOOD_LIFECYCLE_HOLDER.replace("        webView.removeCallbacks(null)\n", "")
    expect_red(
        "breaker: the holder destroy() drops the queued callbacks",
        GOOD_LIFECYCLE_MANAGER,
        holder_teardown,
        [
            "WebViewHolder.kt destroy() does not call webView.removeCallbacks(null)",
            "WebViewHolder.kt destroy() clears view callbacks before detaching",
        ],
    )

    early_clear = GOOD_LIFECYCLE_MANAGER.replace(
        "        (webView.parent as? ViewGroup)?.removeView(webView)\n"
        "        webView.stopLoading()\n"
        "        webView.removeCallbacks(null)\n",
        "        webView.removeCallbacks(null)\n"
        "        (webView.parent as? ViewGroup)?.removeView(webView)\n"
        "        webView.stopLoading()\n",
    )
    expect_red(
        "breaker: callbacks cleared before the view is detached (shared handler drain)",
        early_clear,
        GOOD_LIFECYCLE_HOLDER,
        ["BrowserUseManager.kt destroy() clears view callbacks before detaching"],
    )

    # baseline shape: the pre-fix tree, criterion by criterion.
    baseline_mgr = """
    private fun fetchBlobDownload(url: String) {
        blobDownloadActiveToken = token
        webView.post { webView.evaluateJavascript(js, null) }
    }

    fun setUserAgent(profile: UserAgentProfile, customUA: String? = null) {
        webView.settings.userAgentString = ua
        webView.reload()
    }

    fun destroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
    }
"""
    baseline_holder = """
    fun toggleDesktopMode() {
        webView.settings.userAgentString = DESKTOP_UA
        webView.reload()
    }

    fun destroy() {
        detach()
        webView.stopLoading()
        webView.destroy()
    }
"""
    expect_red(
        "baseline shape: every lifecycle criterion red",
        baseline_mgr,
        baseline_holder,
        [
            "is not gated by the destroyed latch",
            "goes through the WebView's View post queue",
            "schedules work on its own WebView",
            "user-agent switch (setUserAgent) writes",
            "user-agent switch (toggleDesktopMode) writes",
            "BrowserUseManager.kt destroy() does not call webView.removeCallbacks(null)",
            "BrowserUseManager.kt destroy() clears view callbacks before detaching",
            "WebViewHolder.kt destroy() does not call webView.removeCallbacks(null)",
            "WebViewHolder.kt destroy() clears view callbacks before detaching",
        ],
    )

    # ── [deep-audit-1046 S-07] offscreen release latch ───────────────────────
    # Same discipline as `expect_red`: the EXPECTED red set is written out by
    # hand from the criterion table, never derived from a run.
    def expect_latch(label, katex, preview, policy, want, n=None):
        global ARMS
        ARMS += 1
        res = analyse_release_latch([
            ("KaTeXView.kt", katex),
            ("FilePreviewScreen.kt", preview),
        ])
        res += analyse_release_policy(policy)
        got = [c for c, ok, _ in res if not ok]
        missing = [w for w in want if not any(w in c for c in got)]
        unexpected = [c for c in got if not any(w in c for w in want)]
        bad = bool(missing or unexpected) or (n is not None and len(got) != n)
        if bad:
            fails.append(label)
            print(
                "  [FAIL] %s -> red set %d (want %s), missing=%r unexpected=%r"
                % (label, len(got), n if n is not None else len(want), missing, unexpected)
            )
        else:
            print("  [OK] %s -> red set %d" % (label, len(got)))

    expect_latch("clean fixture: release latch contract", GOOD_KATEX, GOOD_PREVIEW, GOOD_POLICY, [], 0)

    # ---- r3 round-3b witness arms (03-1 / 03-2 / 03-3) ------------------
    dead_plan = GOOD_KATEX.replace(
        "                    for (step in releasePlan(attached = wv.parent != null)) {",
        "                    if (false) for (step in releasePlan(attached = wv.parent != null)) {")
    expect_latch(
        "r3-03-1 witness: the teardown plan sits under `if (false)`",
        dead_plan, GOOD_PREVIEW, GOOD_POLICY,
        ["runs its teardown plan on a live path"], 1,
    )
    gated_plan = GOOD_KATEX.replace(
        "                    for (step in releasePlan(attached = wv.parent != null)) {",
        "                    if (wv.parent != null) for (step in releasePlan(attached = wv.parent != null)) {")
    expect_latch(
        "precision: a feasible condition above the plan stays green",
        gated_plan, GOOD_PREVIEW, GOOD_POLICY, [], 0,
    )
    unposted = GOOD_PREVIEW.replace("                post(initialLoad)\n", "")
    expect_latch(
        "r3-03-2 witness: the guarded runnable is never posted",
        GOOD_KATEX, unposted, GOOD_POLICY,
        ["is scheduled on the view queue"], 1,
    )
    disp = [ok for c, ok, _ in analyse_js_chokepoint(GOOD_DISPATCH_MANAGER) if "runJsNow" in c]
    expect("r3-03-3 clean fixture: the hop callee re-checks the latch",
           bool(disp) and all(disp), True)
    disp = [ok for c, ok, _ in analyse_js_chokepoint(BAD_DISPATCH_MANAGER) if "runJsNow" in c]
    expect("r3-03-3 witness: the hop callee runs the block unguarded",
           bool(disp) and all(disp), False)

    # PRE arm — the pre-fix shape: no latch, no plan, null-clear teardown, both
    # deferred blocks unguarded.
    expect_latch(
        "baseline shape: every release-latch criterion red",
        BASE_KATEX,
        GOOD_PREVIEW,
        GOOD_POLICY,
        [
            "touches the WebView before latching it",
            "does not use the derived teardown plan",
            "does not remove the deferred work by identity",
            "clears view callbacks with removeCallbacks(null) before detaching",
            "KaTeXView.kt deferred block",
        ],
        # 4 teardown criteria + 2 unguarded blocks (`post {` + `postDelayed({`)
        6,
    )

    # one arm per criterion, each reddening exactly one
    no_capture_guard = GOOD_KATEX.replace(
        "                                        if (!releaseLatch.canRunDeferred()) return@Runnable\n",
        "",
    )
    expect_latch(
        "breaker: the capture block drops its latch guard",
        no_capture_guard,
        GOOD_PREVIEW,
        GOOD_POLICY,
        ["KaTeXView.kt deferred block"],
        1,
    )

    late_capture_guard = GOOD_KATEX.replace(
        "                                        if (!releaseLatch.canRunDeferred()) return@Runnable\n"
        "                                        renderedBitmap = bitmap\n",
        "                                        renderedBitmap = bitmap\n"
        "                                        if (!releaseLatch.canRunDeferred()) return@Runnable\n",
    )
    expect_latch(
        "breaker: the capture guard sits after the work it was meant to stop",
        late_capture_guard,
        GOOD_PREVIEW,
        GOOD_POLICY,
        ["KaTeXView.kt deferred block"],
        1,
    )

    commented_capture_guard = GOOD_KATEX.replace(
        "                                        if (!releaseLatch.canRunDeferred()) return@Runnable\n",
        "                                        // if (!releaseLatch.canRunDeferred()) return@Runnable\n",
    )
    expect_latch(
        "precision: a commented-out guard is not a guard",
        commented_capture_guard,
        GOOD_PREVIEW,
        GOOD_POLICY,
        ["KaTeXView.kt deferred block"],
        1,
    )

    # 07-3 (batch-7): the guard opens the block but can never fire, or fires on
    # the wrong polarity — the old "first statement is an if" arm stays green.
    dead_capture_guard = GOOD_KATEX.replace(
        "                                        if (!releaseLatch.canRunDeferred()) return@Runnable\n",
        "                                        if (false && !releaseLatch.canRunDeferred()) return@Runnable\n",
    )
    expect_latch(
        "witness: the deferred guard's condition is always false (nothing stands down)",
        dead_capture_guard,
        GOOD_PREVIEW,
        GOOD_POLICY,
        ["KaTeXView.kt deferred block"],
        1,
    )

    inverted_capture_guard = GOOD_KATEX.replace(
        "                                        if (!releaseLatch.canRunDeferred()) return@Runnable\n",
        "                                        if (releaseLatch.canRunDeferred()) return@Runnable\n",
    )
    expect_latch(
        "witness: the deferred guard's polarity is inverted (the block never runs)",
        inverted_capture_guard,
        GOOD_PREVIEW,
        GOOD_POLICY,
        ["KaTeXView.kt deferred block"],
        1,
    )

    released_flag_guard = GOOD_KATEX.replace(
        "                                        if (!releaseLatch.canRunDeferred()) return@Runnable\n",
        "                                        if (releaseLatch.isReleased) return@Runnable\n",
    )
    expect_latch(
        "precision: a positive `isReleased` stand-down guard is accepted",
        released_flag_guard,
        GOOD_PREVIEW,
        GOOD_POLICY,
        [],
        0,
    )

    untracked = GOOD_KATEX.replace("                                    releaseLatch.track(capture)\n", "")
    expect_latch(
        "breaker: the capture runnable is never registered for removal",
        untracked,
        GOOD_PREVIEW,
        GOOD_POLICY,
        ["is never registered with the release latch"],
        1,
    )

    unlatched = GOOD_KATEX.replace("                    val outstanding = releaseLatch.release()\n", "")
    expect_latch(
        "breaker: the release path never latches the view",
        unlatched,
        GOOD_PREVIEW,
        GOOD_POLICY,
        ["touches the WebView before latching it"],
        1,
    )

    inlined_plan = GOOD_KATEX.replace(
        "                    for (step in releasePlan(attached = wv.parent != null)) {\n"
        "                        when (step) {\n"
        "                            ReleaseStep.DETACH -> (wv.parent as? ViewGroup)?.removeView(wv)\n"
        "                            ReleaseStep.DROP_DEFERRED -> for (task in outstanding) wv.removeCallbacks(task)\n"
        "                            ReleaseStep.STOP_LOADING -> wv.stopLoading()\n"
        "                            ReleaseStep.DESTROY -> wv.destroy()\n"
        "                        }\n"
        "                    }\n",
        "                    (wv.parent as? ViewGroup)?.removeView(wv)\n"
        "                    for (task in outstanding) wv.removeCallbacks(task)\n"
        "                    wv.stopLoading()\n"
        "                    wv.destroy()\n",
    )
    expect_latch(
        "breaker: the release path hand-sequences the teardown instead of using the plan",
        inlined_plan,
        GOOD_PREVIEW,
        GOOD_POLICY,
        ["does not use the derived teardown plan"],
        1,
    )

    null_instead_of_named = GOOD_KATEX.replace(
        "ReleaseStep.DROP_DEFERRED -> for (task in outstanding) wv.removeCallbacks(task)",
        "ReleaseStep.DROP_DEFERRED -> for (task in outstanding) wv.removeCallbacks(null)",
    )
    expect_latch(
        "breaker: the tracked runnables are dropped with the null no-op again",
        null_instead_of_named,
        GOOD_PREVIEW,
        GOOD_POLICY,
        ["does not remove the deferred work by identity"],
        1,
    )

    no_destroy = GOOD_KATEX.replace("ReleaseStep.DESTROY -> wv.destroy()", "")
    expect_latch(
        "breaker: the release path no longer destroys the WebView",
        no_destroy,
        GOOD_PREVIEW,
        GOOD_POLICY,
        ["no longer stops and destroys the WebView"],
        1,
    )

    # the second host is covered by the same criteria, not by a copy of them
    preview_unguarded = GOOD_PREVIEW.replace(
        "                    if (!releaseLatch.canRunDeferred()) return@Runnable\n",
        "",
    )
    expect_latch(
        "breaker: the preview load drops its latch guard",
        GOOD_KATEX,
        preview_unguarded,
        GOOD_POLICY,
        ["FilePreviewScreen.kt deferred block"],
        1,
    )

    # policy arms — the plan order is the contract both hosts inherit
    plan_no_detach = GOOD_POLICY.replace(
        "listOf(ReleaseStep.DETACH, ReleaseStep.DROP_DEFERRED, ReleaseStep.STOP_LOADING, ReleaseStep.DESTROY)",
        "listOf(ReleaseStep.DROP_DEFERRED, ReleaseStep.STOP_LOADING, ReleaseStep.DESTROY)",
    )
    expect_latch(
        "breaker: the attached-view plan stops detaching first",
        GOOD_KATEX,
        GOOD_PREVIEW,
        plan_no_detach,
        ["the attached-view plan does not detach first"],
        1,
    )

    plan_single = GOOD_POLICY.replace(
        "    } else {\n"
        "        listOf(ReleaseStep.DROP_DEFERRED, ReleaseStep.STOP_LOADING, ReleaseStep.DESTROY)\n"
        "    }\n",
        "    } else {\n"
        "        ReleaseStep.DESTROY.let { java.util.Collections.singletonList(it) }\n"
        "    }\n",
    )
    expect_latch(
        "breaker: the two plans collapse into one (the detached branch lost its steps)",
        GOOD_KATEX,
        GOOD_PREVIEW,
        plan_single,
        ["not expressed as two driven step lists"],
        1,
    )

    return fails


def run_analysis(fn, *args):
    """Run one analysis; a lexer failure is a RED finding, never a crash.

    `mask_code` refuses an unterminated literal (`KotlinLexError`). Swallowing
    that as "no violation" is how a gate goes blind on a file it cannot read.
    """
    try:
        return list(fn(*args))
    except KotlinLexError as exc:
        return [("%s could not lex its source (%s)" % (getattr(fn, "__name__", "analysis"), exc),
                 False, "fail-closed")]


def main():
    if "--selftest" in sys.argv:
        fails = selftest()
        if fails:
            print("SELFTEST FAIL (%d)" % len(fails))
            return 1
        print("BROWSER_DESTROY_WAKEUP_SELFTEST=PASS (%d arms)" % ARMS)
        return 0

    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    root = args[0] if args else "."
    manager_path = os.path.join(root, MANAGER)
    holder_path = os.path.join(root, HOLDER)
    slot_path = os.path.join(root, SLOT)
    katex_path = os.path.join(root, KATEX_VIEW)
    preview_path = os.path.join(root, FILE_PREVIEW)
    policy_path = os.path.join(root, RELEASE_POLICY)
    for p in (manager_path, holder_path, slot_path, katex_path, preview_path, policy_path):
        if not os.path.isfile(p):
            print("FAIL %s not found under root %s" % (p, root))
            return 1

    manager_src = open(manager_path, encoding="utf-8").read()
    holder_src = open(holder_path, encoding="utf-8").read()
    results = run_analysis(analyse_manager, manager_src)
    results += run_analysis(analyse_lifecycle, manager_src, holder_src)
    results += run_analysis(analyse_slot, open(slot_path, encoding="utf-8").read())
    results += run_analysis(analyse_release_latch, [
        ("KaTeXView.kt", open(katex_path, encoding="utf-8").read()),
        ("FilePreviewScreen.kt", open(preview_path, encoding="utf-8").read()),
    ])
    results += run_analysis(analyse_release_policy, open(policy_path, encoding="utf-8").read())
    results += run_analysis(analyse_js_chokepoint, manager_src)

    violations = url_decode_violations(root)
    results.append((
        "a URLDecoder.decode() call site lacks the '.replace(\"+\", \"%2B\")' escape",
        not violations,
        "src/android/app/src/main/java",
    ))

    failed = [(c, d) for c, ok, d in results if not ok]
    for crit, ok, detail in results:
        if ok:
            print("  [OK] %s" % crit)
        else:
            print("  [FAIL] %s (%s)" % (crit, detail))
    for rel, lineno in violations:
        print("  [FAIL] %s:%d bare URLDecoder.decode() — '+' would be decoded as a space" % (rel, lineno))

    print("Summary: %d passed, %d failed" % (len(results) - len(failed), len(failed)))
    if failed:
        print("browser teardown wake-up gate: RED — the ISSUE-08 contract is violated")
        return 1
    print("GATE=GREEN browser_destroy_wakeup")
    return 0


if __name__ == "__main__":
    sys.exit(main())
