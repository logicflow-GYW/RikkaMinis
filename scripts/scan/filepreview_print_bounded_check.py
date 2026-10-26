#!/usr/bin/env python3
"""
Gate: FIX-H (deep-audit-1020 ISSUE-08, P2) — the print path of
FilePreviewScreen.kt must not read the whole file into the heap.

ISSUE-08: `buildPrintHtml` used to call `item.file.readBytes()` — the one
remaining unbounded read in the file (the other three preview surfaces had
already been fixed to `readCapped` under [rv1014-E1]). Printing a multi-GB
.log/.txt pulled the whole file into the heap BEFORE decodeUtf8Capped applied
its 512_000-byte cap. The fix routes the read through the same `readCapped`
helper, which returns at most MAX+1 bytes and still yields the truncated
verdict in a single pass; the printed bytes are unchanged by design.

[da1026-F] extended — the `.readBytes()` ban was measured, but measured on the
wrong axis: a *spelling*, not the property. `CsvPreview` read the file with
`bufferedReader(...).readLine()` and a 200-ROW cap, which reads as "bounded"
while `readLine()` still materialises the whole line — a CSV with one
multi-hundred-MB line grew a single String to the file size and died with
`OutOfMemoryError`, an `Error` the caller's `catch (e: Exception)` never saw.
So the file is now checked for bare `bufferedReader(` / `.readText()` /
`.readLines()` as well — deliberately with NO whitelist inside readCapped,
because that body IS the bound and letting it spell a raw read would
reintroduce the very OOM. Same round also pins the second defect:
`TextPreview` and `MarkdownPreview` held their preview state in unkeyed
`remember { }`, so a stale `error` from the previous file survived the switch
and masked the new one — both bodies must key their state on `item.file`.

Checks assert PRESENCE of the corrected routing rather than the absence of a
spelling, so a refactor that keeps the contract is a deliberate edit to this
gate rather than a silent pass. Bodies are located by brace-balanced anchor
and FAIL CLOSED when the anchor is missing or duplicated.

[deep-audit-1045 B-07] extended a third time — the same property on the two
settings screens that import a USER-PICKED document. `ProviderListScreen`'s
non-zip branch read the whole document into one String with no bound, and
`SkillsManagementScreen.readUriBytes` did the same with `readBytes()`; the
32 MiB guard further downstream (`SkillRepository.readZipEntries`) only bounds
EXPANDED zip entries, so it could never help the raw array. Both now route
through `readSafBytes` (SafImportRead.kt). The gate DERIVES its subject set
from every `openInputStream(` under ui/** — a site is bounded when it streams,
caps, or pre-checks the declared size, and everything else needs a registered
exemption with a reason. A stale exemption is a rot failure, not a pass.

Usage: python3 scripts/scan/filepreview_print_bounded_check.py [repo_root]
       python3 scripts/scan/filepreview_print_bounded_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage
"""
import os
import re
import shutil
import sys
import tempfile

# [batch6-t03] One structural layer for the whole gate: the shared Kotlin
# lexer/value-flow primitives. Every hand-rolled view below (comment stripper,
# brace counter, "does the text contain X" judge) is a defect in waiting — the
# witnesses that broke this gate did it by putting a `}` or a `readBytes()`
# exactly where a text scan could not see it.
from _kotlin_lexer import KotlinLexError, mask_code
from _kotlin_ast import (const_bool, enclosing_conditions, if_blocks, match_bracket,
                         split_top, stmt_spans, strip_parens, tail_expr)
from _kotlin_valueflow import (brace_depth, call_args, contains_ident, expr_end,
                               path_feasible, returns_exiting, value_sources)

TARGET_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/sandbox/FilePreviewScreen.kt"

# buildPrintHtml must read through the bounded helper and keep the decode
# boundary handling wired in.
BOUNDED_READ = "readCapped(item.file, MAX_TEXT_PREVIEW_BYTES)"
DECODE_WIRED = "decodeUtf8Capped("
BUILD_ANCHOR = "private fun buildPrintHtml(item: FileItem): String {"

# The whole file (comments stripped) must contain zero unbounded reads —
# :1275 was the last one. The prose in KDoc/line comments mentions
# `readBytes()` on purpose (history notes), so comments are stripped first.
UNBOUNDED_CALL = ".readBytes()"
BOUNDED_READ_RE = re.compile(r"\breadCapped\s*\(\s*item\.file\s*,\s*MAX_TEXT_PREVIEW_BYTES\s*\)")
# [batch6-t03] Whole-file reads through the JDK, same defect as `.readBytes()`.
WHOLE_FILE_READS = (
    (r"\bFiles\s*\.\s*readString\s*\(", "Files.readString"),
    (r"\bFiles\s*\.\s*readAllBytes\s*\(", "Files.readAllBytes"),
    (r"(?<!Files\s\.)\breadAllBytes\s*\(", "readAllBytes"),
)
# A Compose key decides whether the slot is reused by comparing VALUES, so a
# key must be something that differs between two files. `item.file` identifies
# the file; `.parent` identifies its directory.
FILE_IDENTITY_PROPS = frozenset({"name", "path", "absolutePath", "absoluteFile",
                                 "canonicalPath", "canonicalFile"})
KEY_ROOTS = ("item.file", "renderer", "item")


CAP_NUMERIC_RE = re.compile(r"^[\d\s+\-*/%().]+$")


def _numeric_value(expr):
    """Value of a pure numeric expression, or None.

    `1_000_000` (Kotlin underscore literals), `Int.MAX_VALUE` and `Long.MAX_VALUE`
    fold too — a cap written `Long.MAX_VALUE / 2` is a real value and has to be
    compared, not pattern-matched.
    """
    e = re.sub(r"(?<=\d)_", "", (expr or "").strip())
    e = e.replace("_", "")
    e = re.sub(r"\b(?:Int|Long|Short|Byte)\s*\.\s*(?:MAX|MIN)_VALUE\b",
               lambda m: str(2 ** 62), e)
    if not CAP_NUMERIC_RE.fullmatch(e):
        return None
    try:
        return eval(e, {"__builtins__": {}}, {})      # digits + operators only
    except Exception:
        return None


def _cap_bound(expr, known_safe=(), text="", depth=0):
    """Upper bound of a cap expression; 0 == "whatever the caller passed".

    Derived, not spelled: `minOf(cap, 64 * 1024)` bounds at the caller's cap,
    `cap + Long.MAX_VALUE / 2` does not (the caller's cap contributes 0 to the
    sum, the constant is what the read actually allocates), and an unreadable
    expression returns None so the caller fail-closes.
    """
    if depth > 4:
        return None
    e = strip_parens((expr or "").strip())
    if not e:
        return None
    if e in known_safe:
        return 0
    m = re.fullmatch(r"(?:minOf|min|minOfOrNull)\s*\((.*)\)", e, re.S)
    if m:
        bounds = [_cap_bound(a, known_safe, text, depth + 1)
                  for a in _split_args(e, e.index("("), len(e) - 1)]
        if not bounds or any(b is None for b in bounds):
            return None
        return min(bounds)
    parts = split_top(e, ("+",))
    if len(parts) > 1:
        total = 0
        for a, b in parts:
            v = _cap_bound(e[a:b], known_safe, text, depth + 1)
            if v is None:
                return None
            total += v
        return total
    n = _numeric_value(e)
    if n is not None:
        return n
    if re.fullmatch(r"[A-Za-z_]\w*", e):
        decl = re.search(r"\b(?:const\s+)?val\s+" + re.escape(e)
                         + r"\s*(?::[^=\n]+)?=\s*([^\n]+)", text)
        if decl:
            return _cap_bound(decl.group(1), known_safe, text, depth + 1)
        return 0 if e in known_safe else None
    return None


def _cap_violation(where, call_text, cap_arg, limit):
    """None when the cap is bounded, else the violation message."""
    bound = _cap_bound(cap_arg, known_safe=("cap", "maxChars"), text=where)
    if bound is not None and bound <= limit:
        return None
    shown = "%d" % bound if bound is not None else "unbounded/unreadable"
    return ("%s: %s — the bound must be the caller's cap (`cap`, or `minOf(cap, …)` "
            "with a bounded other arm), not an unbounded value; a correct call with a "
            "wrong cap is still an unbounded read (bound = %s)"
            % (call_text, cap_arg, shown))


def _split_args(text, open_idx, close_idx):
    """Top-level comma split of the argument list at (open_idx, close_idx]."""
    args, depth, cur = [], 0, ""
    for ch in text[open_idx + 1: close_idx]:
        if ch in "([{<":
            depth += 1
        elif ch in ")]}>":
            depth -= 1
        if ch == "," and depth == 0:
            args.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        args.append(cur)
    return args


def _is_file_identity_key(key):
    """True when the key's VALUE changes with the file (the slot must restart)."""
    k = (key or "").strip()
    if not k:
        return False
    for src in KEY_ROOTS:
        if k == src:
            return True
        if k.startswith(src + "."):
            if src != "item.file":
                return True
            head = re.split(r"[.\s(]", k[len(src) + 1:], 1)[0]
            return head in FILE_IDENTITY_PROPS
    return False

# [da1026-F] Every other read spelling a preview could reach for. Deliberately
# NOT whitelisted inside readCapped: that body IS the bound, and letting the
# bound itself spell `readBytes()` / `bufferedReader().readLine()` would
# reintroduce the very OOM this gate exists to stop. One rule for the whole
# file, same as the `.readBytes()` criterion above (see BAD_READCAP_BARE, the
# fixture that proves the criterion bites inside readCapped too).
BARE_READS = ("bufferedReader(", ".readText()", ".readLines()")

# [da1026-F] Preview composables whose state must be keyed on the file. An
# unkeyed `remember { }` is reused across a file switch (Compose keys by slot
# position), which is how a failed read's error banner ended up masking the
# next file — the success path never clears `error`.
REMEMBER_ANCHORS = (
    "private fun TextPreview(item: FileItem) {",
    "private fun MarkdownPreview(item: FileItem) {",
)



# ── structural view ───────────────────────────────────────────────────────
#
# [batch6-t03] `mask_code()` blanks comment bodies AND string/char literal
# contents in place (same length, same offsets), so the two ways this gate used
# to be blind are structurally impossible now:
#   * `strip_comments` did not know strings — a `"http://"` literal opened a
#     line comment that swallowed the unconditional write behind it;
#   * `grab_body` walked RAW braces — a `}` inside a `// …` comment closed the
#     function body early and everything after it went unjudged.
# A lexer error is a violation (fail-closed), never a fallback to raw regex.
LEX = {"reason": ""}


def strip_comments(text):
    """The gate's single structural view (name kept for its call sites)."""
    try:
        return mask_code(text)
    except KotlinLexError as exc:
        LEX["reason"] = str(exc)
        return None


FUN_DECL_FULL = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*\(")


def decl_span(text, decl):
    """(body_start, body_end) just inside the `{…}` opened by [decl].

    None when the declaration is missing or duplicated — every caller reports
    that as a violation rather than scanning the wrong bytes. `match_bracket`
    (shared) is the only brace walk left in this gate.
    """
    if not decl or text.count(decl) != 1:
        return None
    i = text.index(decl)
    open_idx = i + len(decl) - 1
    if open_idx >= len(text) or text[open_idx] != "{":
        open_idx = text.find("{", i)
        if open_idx < 0:
            return None
    close = match_bracket(text, open_idx)
    return (open_idx + 1, close) if close > 0 else None


def decl_body(text, decl):
    span = decl_span(text, decl)
    return None if span is None else text[span[0]:span[1]]


def decl_expr_span(text, decl):
    """(start, end) of a single-expression declaration (`… = <expr>`)."""
    if not decl or text.count(decl) != 1:
        return None
    i = text.index(decl)
    eq = text.find("=", i + len(decl) - 1)
    if eq < 0:
        return None
    return (i, expr_end(text, eq + 1))


def fn_spans(text, name):
    """[(body_start, body_end)] for EVERY `fun <name>(…)` — overloads included."""
    out = []
    for m in FUN_DECL_FULL.finditer(text):
        if m.group(1) != name:
            continue
        p_close = match_bracket(text, m.end() - 1)
        if p_close < 0:
            continue
        i, n = p_close + 1, len(text)
        while i < n and text[i] not in "={":
            if text[i] == "\n":
                i = -1
                break
            i += 1
        if i < 0 or i >= n:
            continue
        if text[i] == "{":
            close = match_bracket(text, i)
            if close > 0:
                out.append((i + 1, close))
        else:
            out.append((i + 1, expr_end(text, i + 1)))
    return out


# ── [deep-audit-1049 B-05] the print slot's lifecycle ──────────────────────
#
# `printFile` hands the framework a WebView and holds it in a module-level slot
# because `createPrintDocumentAdapter` reads that view lazily while the job
# spools. Three things did not happen around the slot:
#
#   1. The slot was OVERWRITTEN (`printWebView = webView`) with no release of the
#      previous instance — `releasePrintWebView()`'s own KDoc said "called … at
#      the start of the next print", and nothing called it there. rapid taps on
#      two files left the first renderer alive for the life of the process.
#   2. `onPageFinished` of the superseded view still called
#      `printManager.print(...)`: the old page keeps loading after the slot is
#      re-claimed, so a second print dialog appeared, and its
#      `releasePrintWebView(view)` was a no-op by identity — dialog and renderer
#      both orphaned.
#   3. Leaving the preview screen did nothing at all (the slot is not Compose
#      state), so a printed file kept its renderer until the process died.
#
# The criteria are anchored on the SHAPES that make the lifecycle hold, not on
# "destroy() appears somewhere": the slot declaration, a releaser that both
# nulls and destroys, a release immediately before every overwrite, a
# DisposableEffect that releases on exit, and an identity guard in front of
# `PrintManager.print`.
SLOT_DECL = "private var printWebView: WebView? = null"
SLOT_WRITE_RE = re.compile(r"\bprintWebView\s*=(?!=)")
SLOT_RELEASER_DECL = "private fun releasePrintWebView() {"
SLOT_RELEASE_CALL_RE = re.compile(r"\breleasePrintWebView\w*\s*\(")
SLOT_DESTROY_RE = re.compile(r"\.destroy\s*\(\s*\)")
SLOT_NULL_RE = re.compile(r"\bprintWebView\s*=\s*null\b")
ON_DISPOSE_RE = re.compile(r"\bonDispose\s*\{")
ON_PAGE_FINISHED = "override fun onPageFinished(view: WebView, url: String) {"
PRINT_CALL_RE = re.compile(r"\bprintManager\s*\.\s*print\s*\(")
SLOT_IDENTITY_RE = re.compile(r"\bprintWebView\s*!==\s*view\b")
# [wide-1024] The guard may be spelled through the helper the file already has;
# its body is then checked for a real `===` comparison (anchor rule: a helper is
# only worth accepting when its own body does the work).
SLOT_IDENTITY_HELPER_RE = re.compile(r"\bisCurrentPrintView\s*\(")
SLOT_IDENTITY_HELPER_DECL = "internal fun isCurrentPrintView(owner: Any?, candidate: Any?): Boolean ="
# A literal in front of the comparison makes the guard dead code — the whole
# family of `false && guard` / `true || guard` rewrites.


def _if_statements(text):
    """[(cond, branch_start, branch_end)] for every `if (…)` in [text].

    Positions are absolute and point INSIDE the branch, so the judge can ask
    "does the branch body end control flow" of real bytes instead of searching
    the block text for the word `return` (which `return@run` also satisfies).
    Unbalanced parens/braces are skipped; the caller treats "no usable guard"
    as a violation (fail-closed).
    """
    out = []
    for m in re.finditer(r"\bif\s*\(", text):
        close = match_bracket(text, m.end() - 1)
        if close < 0:
            continue
        cond = text[m.end(): close]
        k = close + 1
        while k < len(text) and text[k] in " \t\r\n":
            k += 1
        if k >= len(text):
            continue
        if text[k] == "{":
            bend = match_bracket(text, k)
            if bend < 0:
                continue
            out.append((cond, k + 1, bend))
        else:
            bend = expr_end(text, k)
            if bend <= k:
                continue
            out.append((cond, k, bend))
    return out


def _decl_text(code, decl):
    """Body of a declaration: `{…}` for block form, the expression for `= …`."""
    if decl.endswith("="):
        span = decl_expr_span(code, decl)
        if span is None:
            return None
        return code[span[0]:span[1]]
    return decl_body(code, decl)


def _block_at(text, open_idx):
    """(start, end) just inside the balanced `{…}` at [open_idx], or None."""
    if open_idx < 0 or open_idx >= len(text) or text[open_idx] != "{":
        return None
    close = match_bracket(text, open_idx)
    return None if close < 0 else (open_idx + 1, close)


def _branch_ends_control_flow(text, start, end):
    """True when the branch's own LAST statement leaves the enclosing function.

    Judged on the statement list, not on a substring: a `return` earlier in the
    branch, or a `return@run` that only exits a lambda, does not stop the
    superseded view from falling through into `printManager.print(...)`.
    """
    body = text[start:end]
    spans = stmt_spans(body)
    if not spans:
        return False
    tail = body[spans[-1][0]:spans[-1][1]].strip()
    if not re.match(r"(?:return|throw)\b", tail):
        return False
    return not re.match(r"return\s*@", tail)


def check_print_slot(src):
    """Contract checks for the print WebView slot. No slot in the file = this
    criterion has nothing to say (the real tree presence check lives in run())."""
    problems = []
    code = strip_comments(src)
    if code is None:
        return ["FilePreviewScreen.kt does not tokenise, so the print-slot contract "
                "cannot be judged (fail-closed): %s" % LEX["reason"]]
    if "printWebView" not in code:
        return problems
    if code.count(SLOT_DECL) != 1:
        problems.append("the print slot must be declared exactly once "
                        "(`%s`) — the print path moved, re-point this criterion" % SLOT_DECL)
    # (1) every overwrite releases what it replaces
    for m in SLOT_WRITE_RE.finditer(code):
        line_start = code.rfind("\n", 0, m.start()) + 1
        line = code[line_start: code.find("\n", m.start())]
        if "var printWebView" in line:
            continue  # the declaration itself
        if not line.strip().startswith("printWebView ="):
            continue  # reassignments inside the releaser (null) are handled by (2)
        window = code[max(0, m.start() - 1200): m.start()]
        if not SLOT_RELEASE_CALL_RE.search(window):
            problems.append("printFile overwrites the print slot without releasing the "
                            "previous WebView — the superseded renderer stays alive and "
                            "its onPageFinished can still pull a second print job")
    # (2) the releaser nulls the slot AND tears the renderer down
    body = decl_body(code, SLOT_RELEASER_DECL)
    if body is None:
        problems.append("the slot releaser `%s` must exist exactly once"
                        % SLOT_RELEASER_DECL)
    else:
        if not SLOT_NULL_RE.search(body):
            problems.append("releasePrintWebView must clear the slot (`printWebView = null`) "
                            "before tearing the view down")
        if not SLOT_DESTROY_RE.search(body):
            # Destroying may sit one call away (a `destroyPrintWebView(view)`
            # helper shared with the superseded-view path) — accept it, but only
            # when that helper's own body really destroys.
            destroyed = False
            for call in re.finditer(r"\b(\w+)\s*\(\s*view\s*\)", body):
                helper_body = decl_body(code, "private fun %s(" % call.group(1))
                if helper_body and SLOT_DESTROY_RE.search(helper_body):
                    destroyed = True
                    break
            if not destroyed:
                problems.append("releasePrintWebView must actually destroy the view "
                                "(`.destroy()`, directly or through a helper that does) — "
                                "dropping the reference is not a release")
    # (3) leaving the preview releases the slot
    disposes = list(ON_DISPOSE_RE.finditer(code))
    if not disposes:
        problems.append("the preview screen has no `onDispose` releasing the print slot — "
                        "a printed file keeps its renderer until the process dies")
    else:
        released = False
        for m in disposes:
            span = _block_at(code, code.find("{", m.start()))
            if span and SLOT_RELEASE_CALL_RE.search(code[span[0]:span[1]]):
                released = True
                break
        if not released:
            problems.append("every `onDispose` in the preview must release the print slot "
                            "(call the releaser) — the slot is module state, not Compose state")
    # (4) a superseded view must not start a print job — and the guard that
    #     decides it must actually END the branch (wide-1024: comparing is not
    #     skipping; `if (false && printWebView !== view)` and a guard body
    #     without `return` both let a superseded view fall through to print).
    page = decl_body(code, ON_PAGE_FINISHED)
    if page is None:
        problems.append("`%s` must exist exactly once (anchor missing or duplicated)"
                        % ON_PAGE_FINISHED)
    else:
        first_print = PRINT_CALL_RE.search(page)
        if first_print is None:
            problems.append("onPageFinished no longer starts a print job — the print path "
                            "moved, re-point this criterion")
        else:
            head = page[: first_print.start()]
            guards = [(cond, s0, s1) for cond, s0, s1 in _if_statements(head)
                      if SLOT_IDENTITY_RE.search(cond) or SLOT_IDENTITY_HELPER_RE.search(cond)]
            if not guards:
                problems.append("onPageFinished starts a print job without first checking that "
                                "`view` is still the slot owner — a superseded view whose page "
                                "finishes late starts a duplicate print dialog")
            live = []
            for cond, s0, s1 in guards:
                # [batch6-t03] The guard must be SATISFIABLE: the old judge only
                # knew the spellings `false &&` / `true ||`, so `(1 == 2) && …`
                # (or a folded `when (false)`) short-circuited the comparison
                # while the criterion stayed green. const_bool folds any
                # constant condition.
                if const_bool(cond) is False:
                    problems.append(
                        "onPageFinished's superseded-view guard is short-circuited (%r) — the "
                        "comparison never runs, so the dead branch is what the slot gets"
                        % cond.strip()[:60]
                    )
                    continue
                if SLOT_IDENTITY_HELPER_RE.search(cond):
                    helper = _decl_text(code, SLOT_IDENTITY_HELPER_DECL)
                    if helper is None or "===" not in helper:
                        problems.append(
                            "onPageFinished delegates the identity guard to a helper that does "
                            "not compare identity (`===`) — the guard has to be a real check"
                        )
                        continue
                live.append((cond, s0, s1))
            # [batch6-t03] "ends control flow" is judged on the branch's own
            # statement list: `return@run` exits the lambda, not the function,
            # and a `return` before a later statement is not the branch's exit.
            if live and not any(_branch_ends_control_flow(head, s0, s1) for _c, s0, s1 in live):
                problems.append(
                    "onPageFinished's superseded-view guard does not end control flow — the "
                    "branch must `return` (or `throw`) so a superseded view cannot fall through "
                    "into `printManager.print(...)`"
                )
            elif guards and not live:
                problems.append(
                    "onPageFinished's superseded-view guard never executes — every identity "
                    "guard in front of `printManager.print(...)` is dead, so the identity "
                    "check the slot depends on is decorative"
                )
    return problems


# ── [R3-t02] the two seams around the print path ────────────────────────────
#
# Round-3 witness 05-1: this gate pins `buildPrintHtml`'s bounded read, and
# never asked whether a non-HTML file ever REACHES it. `if (item.isHtmlFile ||
# true)` widened the HTML bypass to every file, so a 200 MB `.log` went straight
# into `webView.loadUrl(file://…)` with `readCapped`'s 500 KB cap still perfectly
# intact — the criterion measured a function nothing called.
#
# Round-3 witness 04-1 (shared with file_preview_url_encode_check): the print
# WebView's page load can also be killed one level down, `if (preRenderedHtml ==
# null && false)`, which leaves the slot, the releaser and the identity guard
# untouched while every print renders an empty document.
#
# Both criteria are about the decision, not the spelling: which branch a print
# call sits in (const-folded), and whether the load site is on a reachable path.

PRINT_ENTRY_RE = re.compile(r"\bprintFile\s*\(")
HTML_TEST_RE = re.compile(r"\bisHtmlFile\b|\bisHtml\b")


def _local_rhs(code, name, before):
    """RHS of the nearest `val/var name =` declared before [before]."""
    rhs = None
    for m in re.finditer(r"\b(?:val|var)\s+" + re.escape(name) + r"\b[^=\n]*=(?!=)", code):
        if m.start() >= before:
            break
        rhs = code[m.end():expr_end(code, m.end())].strip()
    return rhs


def _origins(code, expr, before):
    """"The call exists" is not "the call's result is the value" (one hop)."""
    out = []
    for src in value_sources(expr):
        out.append(src)
        name = src.strip()
        if re.fullmatch(r"[A-Za-z_]\w*", name):
            rhs = _local_rhs(code, name, before)
            if rhs:
                out.extend(value_sources(rhs))
    return out


def _reachable(code, pos):
    """Is [pos] on a reachable path? Enclosing `if`s folded from the geometry.

    NOTE (shared-primitive gap, registered in FIX-R3-t02 §6): `path_feasible`
    takes its branch conditions from `enclosing_conditions`, which negates an
    `else` arm as `'!' + cond` — so `!a || true` parses as `(!a) || true` and a
    constant-TRUE guard in front of the else arm reads as reachable. The fold is
    therefore done here on the branch extents: then-arm of a constant-false
    guard and else-arm of a constant-true guard are both dead.
    """
    for cond, t_start, t_end, e_start, e_end in if_blocks(code):
        val = const_bool(cond)
        if val is None:
            continue
        if val is False and t_start <= pos < t_end:
            return False
        if val is True and e_start is not None and e_start <= pos < e_end:
            return False
    return True


def check_print_routing(code):
    """05-1 — non-HTML files must actually reach the bounded render."""
    if "printFile(" not in code:
        return []                     # no print path here (presence enforced in run())
    problems = []
    sites = []
    for m in PRINT_ENTRY_RE.finditer(code):
        if re.search(r"\bfun\s+$", code[max(0, m.start() - 16):m.start()]):
            continue                      # the declaration
        args = call_args(code, m.end() - 1)
        if not args:
            continue
        pre = None
        for a, b in args:
            nm = re.match(r"\s*preRenderedHtml\s*=\s*", code[a:b])
            if nm:
                pre = (a + nm.end(), b)
        if pre is None:
            continue
        sites.append((m.start(), pre))
    if not sites:
        return ["no printFile( … ) call with an explicit preRenderedHtml argument — the print "
                "entry point moved, re-point this criterion"]
    unbounded, rendered = [], []
    for at, (a, b) in sites:
        expr = code[a:b].strip()
        if expr == "null":
            unbounded.append(at)
        elif any("buildPrintHtml(" in src for src in _origins(code, expr, at)):
            rendered.append(at)
        else:
            problems.append(
                "printFile( …, preRenderedHtml = %r ) comes from neither the HTML bypass nor "
                "buildPrintHtml( … ) — a third print source appeared and its bound cannot be "
                "judged" % expr[:40])
    if not rendered:
        problems.append(
            "no printFile( … ) call receives buildPrintHtml(item)'s output — the bounded text "
            "render is not wired to the print entry point")
    for at in unbounded:
        conds = [cond for kind, cond in enclosing_conditions(code, at) if kind == "if"]
        html_conds = [c for c in conds if HTML_TEST_RE.search(c)]
        if not html_conds:
            problems.append(
                "the printFile( … preRenderedHtml = null ) path (the unbounded file:// load) is "
                "not guarded by the HTML-file discriminator — text / log / json files would "
                "load their whole content through the WebView and bypass readCapped")
            continue
        dead = [c for c in html_conds if const_bool(c) is not None]
        if dead:
            problems.append(
                "the HTML/text split in front of printFile(...) folds to a constant (%r) — both "
                "kinds take the same branch, so a multi-hundred-MB log either goes through the "
                "unbounded file:// load or never prints at all"
                % dead[0].strip()[:60])
    for at in rendered:
        if not _reachable(code, at):
            problems.append(
                "the buildPrintHtml(...) print call sits in a branch that can never run — the "
                "500 KB readCapped bound is decorative while the raw load takes over")
    return problems


def check_print_file_load(code):
    """04-1 — the print WebView's own page load must be on a reachable path."""
    if "printFile(" not in code:
        return []                     # no print path here (presence enforced in run())
    body = decl_body(code, "private fun printFile(")
    if body is None:
        return ["`private fun printFile(` must exist exactly once — the print path moved, "
                "re-point this criterion"]
    problems = []
    for m in re.finditer(r"\bloadUrl\s*\(", body):
        if _reachable(body, m.start()):
            continue
        problems.append(
            "the print WebView's loadUrl( … ) sits behind a condition that folds to a constant "
            "— the WebView never loads the file it was created for, so every print renders an "
            "empty document while the slot/releaser contract stays intact")
    return problems


def _read_statement_for(body, spans, pos):
    """(start, end) of the top-level statement that contains [pos]."""
    for a, b in spans:
        if a <= pos < b:
            return a, b
    return None


def _bounded_value_used(body):
    """Violations for "the bounded read must be the value the function returns".

    [batch6-t03] The old criterion asked whether the SPELLING
    `readCapped(item.file, MAX_TEXT_PREVIEW_BYTES)` appears in the body — so a
    decoy bounded read bound to an unused local, next to a full
    `Files.readString`, kept every criterion green while the print path read the
    whole file. The judge is now a value-flow question: which name carries the
    bounded bytes, is that name used, and does the returned expression derive
    from it.
    """
    v = []
    spans = stmt_spans(body)
    if not spans:
        return ["buildPrintHtml has no statements — the bounded read cannot be traced"]
    tail = body[spans[-1][0]:spans[-1][1]]
    bound, direct = [], 0
    for m in BOUNDED_READ_RE.finditer(body):
        stmt = _read_statement_for(body, spans, m.start())
        if stmt is None:
            continue
        decl = re.match(r"\s*(?:val|var)\s+([A-Za-z_]\w*)\s*=", body[stmt[0]:stmt[1]])
        if decl:
            bound.append((decl.group(1), stmt))
        elif stmt[0] == spans[-1][0]:
            direct += 1
    if not bound and not direct:
        v.append("buildPrintHtml must consume the value it reads through readCapped — the "
                 "bounded read is neither bound to a name nor part of the returned expression")
        return v
    used = []
    for name, (_a, b) in bound:
        if contains_ident(body[b:], name) or contains_ident(tail, name):
            used.append(name)
        else:
            v.append("buildPrintHtml binds the bounded read to `%s` and never reads it — the "
                     "text that actually gets printed comes from somewhere else (a decoy "
                     "bounded read beside a full one)" % name)
    if bound and used and not any(contains_ident(tail, n) for n in used):
        v.append("buildPrintHtml returns a value that does not derive from the bounded read "
                 "(%s) — the cap has to be on the bytes the caller prints"
                 % ", ".join(used))
    return v


def _remember_violations(fbody, sig):
    """Every `remember` in a preview must be keyed on something file-identifying.

    [batch6-t03] Keyed state is Compose slot state: a bare `remember { }` or a
    constant key (`remember(Unit) { }`) keeps the PREVIOUS file's value, so a
    stale error banner or truncation flag masks the next file. The old judge
    looked for the two spellings `remember {` and `by remember(item.file) {`;
    the judge is now the argument set of every `remember` call in the body.
    """
    v = []
    keyed_on_file = False
    for m in re.finditer(r"\bremember\b", fbody):
        k = m.end()
        while k < len(fbody) and fbody[k] in " \t\r\n":
            k += 1
        if k < len(fbody) and fbody[k] == "(":
            close = match_bracket(fbody, k)
            if close < 0:
                v.append("%s holds a remember whose argument list does not balance — the "
                         "key set cannot be read (fail-closed)" % sig)
                continue
            keys = [a.strip() for a in _split_args(fbody, k, close) if a.strip()]
            if any(_is_file_identity_key(key) for key in keys):
                keyed_on_file = True
            else:
                v.append("%s holds a remember whose key set does not track the file (%s) — a "
                         "constant key such as `remember(Unit)` reuses the previous file's slot, "
                         "so a stale error banner masks the next file"
                         % (sig, ", ".join(keys) if keys else "(no key)"))
        elif k < len(fbody) and fbody[k] == "{":
            v.append("%s holds unkeyed remember { } state — it must be keyed on item.file "
                     "(a stale error banner otherwise survives the file switch)" % sig)
    if not keyed_on_file:
        v.append("%s must key its preview state on item.file "
                 "(no 'by remember(item.file) {{' found)" % sig)
    return v


def check(src):
    v = []
    code = strip_comments(src)
    if code is None:
        return ["FilePreviewScreen.kt does not tokenise, so the print path cannot be judged "
                "(fail-closed): %s" % LEX["reason"]]
    span = decl_span(code, BUILD_ANCHOR)
    if span is None:
        v.append("buildPrintHtml must exist exactly once (anchor missing or duplicated)")
        return v
    body = code[span[0]:span[1]]
    if BOUNDED_READ not in body:
        v.append("buildPrintHtml must read through readCapped(item.file, MAX_TEXT_PREVIEW_BYTES)")
    if DECODE_WIRED not in body:
        v.append("buildPrintHtml must keep decodeUtf8Capped wired in (decode-boundary handling)")
    if UNBOUNDED_CALL in body:
        v.append("buildPrintHtml body must not contain readBytes()")
    v.extend(_bounded_value_used(body))
    hits = code.count(UNBOUNDED_CALL)
    if hits != 0:
        v.append("FilePreviewScreen.kt has %d unbounded .readBytes() call(s) outside comments "
                 "(no whitelist — every call must go through readCapped)" % hits)
    # [batch6-t03] The same class of read, other spellings: `Files.readString` /
    # `readAllBytes` materialise the whole file through the JDK before any cap.
    for pattern, label in WHOLE_FILE_READS:
        n = len(re.findall(pattern, code))
        if n:
            v.append("FilePreviewScreen.kt has %d whole-file %s read(s) — that call "
                     "materialises the entire file before any cap; every preview read must "
                     "go through readCapped/decodeUtf8Capped (no whitelist)"
                     % (n, label))
    # [da1026-F] Bare reader spellings. Computed on the masked view, so the KDoc
    # history notes about the old `bufferedReader(...)` call can neither satisfy
    # nor trip it. No whitelist — see BARE_READS.
    for needle in BARE_READS:
        n = code.count(needle)
        if n:
            v.append("FilePreviewScreen.kt has %d bare %s read(s) "
                     "(every preview read must go through readCapped/decodeUtf8Capped)"
                     % (n, needle))
    # [da1026-F / batch6-t03] Preview state must be keyed on the file.
    for sig in REMEMBER_ANCHORS:
        fbody = decl_body(code, sig)
        if fbody is None:
            v.append("%s must exist exactly once (anchor missing or duplicated)" % sig)
            continue
        v.extend(_remember_violations(fbody, sig))
    # [deep-audit-1047 B-03] C-PDF-KEY — derived preview set, derived key set.
    kv, ksites = check_preview_keys(src)
    v.extend(kv)
    for needle, why in (
        ("private fun readCapped(file: File, maxBytes: Int): ByteArray {",
         "the readCapped helper must stay defined"),
        ("private fun decodeUtf8Capped(bytes: ByteArray, cap: Int = MAX_TEXT_PREVIEW_BYTES): String {",
         "the decodeUtf8Capped helper must stay defined"),
        ("private const val MAX_TEXT_PREVIEW_BYTES = 512_000",
         "the preview cap constant must stay defined"),
    ):
        if needle not in code:
            v.append("%s (required token missing)" % why)
    # [deep-audit-1049 B-05] the print slot's lifecycle (see check_print_slot).
    v.extend(check_print_slot(src))
    # [R3-t02] the decision in front of the print path and the print WebView's
    # own load. Both are silent when the source has no print entry at all, so
    # the buildPrintHtml-only fixtures stay judgeable; the real tree's presence
    # requirement is enforced in run().
    v.extend(check_print_routing(code))
    v.extend(check_print_file_load(code))
    return v


# ── [deep-audit-1041 B-07] the log-share path ──────────────────────────────
#
# Same defect family, second file. `LogManagementScreen.shareLogFile`'s
# FileProvider failure arm ran `file.readText().take(100_000)` — the whole file
# into one `String`, THEN trimmed — and the whole path ran inside the
# composable's `onClick` (a main-thread `File.exists()` too). The KDoc a screen
# up in that very file records what a 33 MB day of agent traffic did to the same
# pattern in the log viewer, so this is not a hypothetical.
#
# The subject is the DECISION, not the spelling: (a) no whole-file read may
# exist in the file, (b) a bounded reader must exist whose body reads through a
# stream and honours a cap, (c) the chooser is built inside a
# `withContext(Dispatchers.IO)` block, (d) `startActivity` is NOT inside one —
# it has to run on the caller's (Main) thread.
LOG_SCREEN_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/LogManagementScreen.kt"
IO_BLOCK_RE = re.compile(r"withContext\s*\(\s*Dispatchers\.IO\s*\)\s*\{")
CAP_TOKEN = re.compile(r"\bmaxChars\b|\bLOG_SHARE_MAX_CHARS\b")
# [batch6-t03] Sanity ceilings for a "bounded" cap argument. The real caps are
# 100_000 chars (log share) and 16/32 MiB (SAF); anything above these is a
# caller-side override, not a bound.
LOG_SHARE_CAP_SANITY_CHARS = 1_000_000
SAF_CAP_SANITY_BYTES = 256 * 1024 * 1024
FUN_DECL_RE = re.compile(r"\bfun\s+\w+\s*\(")


def _log_body_at(text, open_idx):
    """Index just past the `}` closing text[open_idx]; -1 when unbalanced."""
    depth, i = 0, open_idx
    while i < len(text):
        c = text[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return -1


def _log_functions(text):
    """[(signature, body)] for every `fun` in `text`.

    Both halves start at the DECLARATION's line, so `private suspend fun …` keeps
    its modifiers and an expression body (`= withContext(Dispatchers.IO) { … }`)
    keeps the call that opens it — measuring from the `fun` keyword alone drops
    the very token the criteria look for.
    """
    out = []
    for m in FUN_DECL_RE.finditer(text):
        line_start = text.rfind("\n", 0, m.start()) + 1
        brace = text.find("{", m.end())
        if brace < 0:
            continue
        end = _log_body_at(text, brace)
        if end < 0:
            continue
        out.append((text[line_start:brace], text[line_start:end]))
    return out


def _io_regions(text):
    """[(start, end)] covering every `withContext(Dispatchers.IO) { … }` block."""
    regions = []
    for m in IO_BLOCK_RE.finditer(text):
        open_idx = text.find("{", m.start(), m.end())
        if open_idx < 0:
            continue
        end = _log_body_at(text, open_idx)
        if end < 0:
            continue
        regions.append((open_idx, end))
    return regions


def check_log_share(root):
    """(violations, rot, sites, notes) — criterion B-07."""
    violations, rot, notes = [], [], []
    path = os.path.join(root, LOG_SCREEN_REL)
    pkg = os.path.dirname(path)
    if not os.path.isfile(path):
        if os.path.isdir(pkg) and os.listdir(pkg):
            rot.append(
                "%s lost its anchor (the ui/settings package is there, the file is not)"
                % LOG_SCREEN_REL
            )
        else:
            notes.append(
                "[PARTIAL] the log-share anchor — this tree never shipped %s" % LOG_SCREEN_REL
            )
        return violations, rot, 0, notes

    with open(path, encoding="utf-8", errors="replace") as fh:
        code = strip_comments(fh.read())
    sites = 0

    for needle in BARE_READS + (UNBOUNDED_CALL,):
        n = code.count(needle)
        sites += 1
        if n:
            violations.append(
                "%s: %d whole-file read(s) (%s) — the share fallback is the one place a "
                "log file is read as text, and a 33 MB day must not become a single "
                "String on the heap" % (LOG_SCREEN_REL, n, needle)
            )

    bodies = [b for _sig, b in _log_functions(code)]
    sites += 1
    readers = []
    for sig, body in _log_functions(code):
        if "FileInputStream(" in body and CAP_TOKEN.search(body):
            name = re.search(r"\bfun\s+([A-Za-z_]\w*)\s*\(", sig)
            if name:
                readers.append(name.group(1))
    if not readers:
        violations.append(
            "%s: no bounded reader found — the share fallback needs one that streams "
            "through FileInputStream and stops at a cap" % LOG_SCREEN_REL
        )
    # [batch6-t03] A bounded reader called with an unbounded cap is not a bounded
    # read. The witness was `readLogShareText(file, maxChars = Int.MAX_VALUE)`:
    # the helper stays perfect, the caller re-materialises the whole file. Every
    # call site is judged on the VALUE of its cap argument (default = the named
    # constant = fine), so a huge literal, a `MAX_VALUE` arithmetic form or an
    # unreadable expression is a violation.
    for name in readers:
        for m in re.finditer(r"\b" + re.escape(name) + r"\s*\(", code):
            if re.search(r"\bfun\s+$", code[: m.start()]):
                continue                      # the declaration itself
            close = match_bracket(code, m.end() - 1)
            if close < 0:
                violations.append(
                    "%s: cannot read the %s(...) arguments (fail-closed) — the cap argument "
                    "is the whole bound and must stay readable" % (LOG_SCREEN_REL, name)
                )
                continue
            args = [a.strip() for a in _split_args(code, m.end() - 1, close)]
            cap_arg = None
            if len(args) >= 2:
                cap_arg = args[1]
            for a in args:
                named = re.match(r"maxChars\s*=\s*(.+)$", a, re.S)
                if named:
                    cap_arg = named.group(1).strip()
            if not cap_arg:
                continue                      # default parameter = the bounded constant
            msg = _cap_violation(
                code,
                "%s: %s(...) is called with a cap the reader cannot honour"
                % (LOG_SCREEN_REL, name),
                cap_arg, LOG_SHARE_CAP_SANITY_CHARS,
            )
            if msg:
                violations.append(msg)
            sites += 1

    # [R3-t02 05-2] …and the bounded text must be the text that actually gets
    # shared. `putExtra(Intent.EXTRA_TEXT, "")` left the reader, its cap, the IO
    # hop and the chooser flags exactly as every criterion above measures them,
    # while the recipient of the fallback share received an empty body: the cap
    # guarded a value nobody sent. The last hop is judged by ORIGIN.
    extra_sites = 0
    for m in re.finditer(r"\bputExtra\s*\(\s*Intent\.EXTRA_TEXT\s*,", code):
        extra_sites += 1
        sites += 1
        args = call_args(code, code.index("(", m.start()))
        if len(args) < 2:
            violations.append(
                "%s: cannot read the EXTRA_TEXT argument (fail-closed) — the shared body is the "
                "whole point of the fallback branch" % LOG_SCREEN_REL)
            continue
        expr = code[args[1][0]:args[1][1]].strip()
        origins = _origins(code, expr, m.start())
        if not any(re.search(r"\b" + re.escape(name) + r"\s*\(", src)
                   for name in readers for src in origins):
            violations.append(
                "%s: the fallback chooser's EXTRA_TEXT carries %r, which does not derive from "
                "the bounded reader (%s) — the cap is enforced on a value nobody sends, so the "
                "shared body is empty"
                % (LOG_SCREEN_REL, expr[:40], ", ".join(readers) or "none"))
    if not extra_sites:
        violations.append(
            "%s: the share fallback carries no EXTRA_TEXT at all — a chooser with no body and "
            "no stream shares nothing (the criterion lost its subject)" % LOG_SCREEN_REL)

    prep = None
    launch = None
    for sig, body in _log_functions(code):
        if "FileProvider.getUriForFile(" in body:
            prep = (sig, body)
        if "startActivity(" in body:
            launch = (sig, body)
    if prep is None:
        rot.append(
            "%s: no function builds the FileProvider share any more — the off-main "
            "criterion would pass by looking at nothing" % LOG_SCREEN_REL
        )
    else:
        sites += 1
        body = prep[1]
        if not IO_BLOCK_RE.search(body):
            violations.append(
                "%s: the share prep (FileProvider lookup + the fallback read) runs on the "
                "caller's thread — wrap it in withContext(Dispatchers.IO)" % LOG_SCREEN_REL
            )
        if "startActivity(" in body:
            violations.append(
                "%s: startActivity is inside the share prep — it must run on the caller's "
                "(Main) thread, so the prep has to return the chooser" % LOG_SCREEN_REL
            )
    if launch is None:
        rot.append(
            "%s: nothing starts the share chooser any more — the criterion lost its "
            "subject" % LOG_SCREEN_REL
        )
    else:
        sites += 1
        sig, body = launch
        if "suspend" not in sig:
            violations.append(
                "%s: the share entry point is not a `suspend fun` — it cannot hop off the "
                "click's thread to prepare the chooser" % LOG_SCREEN_REL
            )
        offset = code.find(body)
        starts = offset if offset >= 0 else 0
        act = body.find("startActivity(")
        if act >= 0 and any(s <= starts + act < e for s, e in _io_regions(code)):
            violations.append(
                "%s: startActivity sits inside a Dispatchers.IO block — the chooser must be "
                "started on the Main thread" % LOG_SCREEN_REL
            )
    return violations, rot, sites, notes


# ── [deep-audit-1045 B-07] user-picked SAF documents ────────────────────────
#
# Third subject in the same family, one layer up from the file paths above: the
# two settings screens read the picked document straight into the heap before
# anything validated it.
#
#  * `ProviderListScreen`'s non-zip branch ran
#    `openInputStream(uri)?.bufferedReader()?.use { it.readText() }` — no upper
#    bound at all, while the `.zip` branch right beside it is capped by
#    ProviderImportZip.
#  * `SkillsManagementScreen.readUriBytes` ran a bare `use { it.readBytes() }`,
#    and the only bound downstream (`SkillRepository.readZipEntries`,
#    maxExpanded = 32 MiB) applies to EXPANDED entries — it cannot help the raw
#    array that was already materialised upstream.
#
# A user-pickable multi-GB .json/.zip therefore reached OutOfMemoryError. Both
# now route through `readSafBytes` (SafImportRead.kt): one cap per import kind,
# a queried SIZE as a cheap early reject, and a hard stop one byte past the cap.
#
# The subject set is DERIVED, never listed: every `openInputStream(` under ui/**
# is classified as bounded (streaming copy / cap helper / declared-size
# precheck / capped extractor) or it needs an exemption WITH a reason, and an
# exemption that no longer matches a real site is a rot failure — a stale
# whitelist is exactly how the previous generation of these holes stayed green.

APP_PKG_DIR = os.path.join(
    "src", "android", "app", "src", "main", "java", "com", "rikkaminis", "app"
)
SAF_UI_DIR = os.path.join(APP_PKG_DIR, "ui")

# [deep-audit-1047 B-02] The sweep used to walk `…/app/ui` ONLY — a hardcoded
# single directory, so `webapp/AddToHomeSheet.kt` (which reads a user-picked
# image with `readBytes()`) was never even looked at: the gate reported "the
# picked document is bounded" while a second, identical hole sat one package
# over. The domain is now every UI tree under the app package, derived rather
# than listed, and C-SAF-1 asserts the sweep actually COVERED every file that
# carries a site — shrinking the domain back to `ui/` is a red, not a pass.
SAF_SCAN_DIRS = (
    SAF_UI_DIR,
    os.path.join(APP_PKG_DIR, "webapp"),
)

# The SUBJECT set is fixed (every UI tree that reads a user-picked document),
# while SAF_SCAN_DIRS above is what the sweep actually walks. Keeping them
# separate is the whole point of C-SAF-1: a domain that silently shrinks back to
# one directory must be reported (candidates − scanned), which is impossible if
# the candidate set is derived from the same variable being shrunk.
SAF_SUBJECT_DIRS = SAF_SCAN_DIRS
SAF_HELPER_REL = os.path.join(SAF_UI_DIR, "settings", "SafImportRead.kt")
SAF_CONSUMERS = (
    os.path.join(SAF_UI_DIR, "settings", "ProviderListScreen.kt"),
    os.path.join(SAF_UI_DIR, "settings", "SkillsManagementScreen.kt"),
)
SAF_SITE = "openInputStream("
SAF_HELPER_CALLS = ("readSafBytes(", "readAtMost(")
SAF_READER_ANCHOR = "internal fun readAtMost(input: InputStream, cap: Long): ByteArray? {"
SAF_ENTRY_ANCHOR = "internal fun readSafBytes(resolver: ContentResolver, uri: Uri, cap: Long): SafRead {"
SAF_OVERFLOW_RE = re.compile(r">\s*cap\b")
SAF_CAPPED_COPY_RE = re.compile(r"\w*Capped\s*\(")
SAF_CAP_CONST_RE = re.compile(r"MAX_[A-Z0-9_]*BYTES")
SAF_CAP_CMP_RE = re.compile(r">\s*[\w.]*MAX_[A-Z0-9_]*BYTES")
SAF_DECLARED_SIZE_RE = re.compile(r"\.SIZE\b|OpenableColumns")


def _args_of(text, open_idx):
    """Top-level comma-separated arguments of the call whose `(` is at open_idx.

    None when the parentheses are unbalanced: the caller reports that as a
    fail-closed violation instead of guessing the arguments.
    """
    depth, j = 0, open_idx
    while j < len(text):
        c = text[j]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                break
        j += 1
    else:
        return None
    inner = text[open_idx + 1: j]
    parts, depth, cur = [], 0, ""
    for ch in inner:
        if ch in "([{<":
            depth += 1
        elif ch in ")]}>":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        parts.append(cur)
    return [p.strip() for p in parts]
SAF_WHOLE_READS = (".readBytes()", ".readText()", "bufferedReader(", ".readLines()")

# Key = (relative path, needle the site's own chain must contain); an entry
# whose file ships but whose needle no longer matches is ROT.
#
# EMPTY by design. The last entry (chat/ChatPromptAndTools.kt reading an image
# attachment with a bare `readBytes()`) was fixed in fix/debt-sweep-1026 (#3,
# backlog B-07): the branch now routes through readSafBytes() under a named cap,
# so the file carries no `openInputStream(` site at all and the entry rotted. Do
# not re-add entries for new sites — bound them.
SAF_EXEMPTIONS = {}

# The ROT rule still needs a live probe entry: the "exemption outlived its site"
# arm has nothing to go stale otherwise. SELFTEST ONLY — the real-tree run never
# consults it (check_saf_imports() defaults to SAF_EXEMPTIONS).
SELFTEST_SAF_EXEMPTIONS = {
    (os.path.join(SAF_UI_DIR, "chat", "ChatPromptAndTools.kt"),
     "openInputStream(attachment.uri)?.use { it.readBytes()"):
        "selftest probe: stands in for the historical chat image-attachment "
        "exemption so the stale-entry arm keeps a subject",
}


def _saf_brace_blocks(text):
    """[(open, close)] for every balanced `{…}` (comment-stripped text only)."""
    pairs, stack = [], []
    for i, c in enumerate(text):
        if c == "{":
            stack.append(i)
        elif c == "}" and stack:
            pairs.append((stack.pop(), i))
    return pairs


def _saf_snippet(text, idx):
    """The site's own call chain: up to the end of its statement."""
    depth, i, n = 0, idx, len(text)
    while i < n:
        c = text[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                break
            depth -= 1
        elif c == "\n" and depth == 0:
            break
        i += 1
    return text[idx:i]


def _saf_classify(text, idx, blocks):
    """(bounded?, reason, snippet) for one `openInputStream(` site.

    Granularity is the site's own chain first, then the INNERMOST enclosing
    block — an outer block is never consulted, because a helper called three
    functions up has nothing to do with this read.
    """
    snippet = _saf_snippet(text, idx)
    if "copyTo(" in snippet:
        return True, "streaming copy", snippet
    if any(call in snippet for call in SAF_HELPER_CALLS):
        return True, "bounded helper", snippet
    # Enclosing blocks, innermost first. The precheck may sit in an OUTER block
    # (a `try { size check … }` around a `withContext(Dispatchers.IO) { read }`),
    # so every level is consulted, not just the innermost one.
    enclosing = sorted(
        ((open_idx, close_idx) for open_idx, close_idx in blocks if open_idx < idx < close_idx),
        key=lambda pair: -pair[0],
    )
    for open_idx, close_idx in enclosing:
        block = text[open_idx:close_idx]
        before = text[open_idx:idx]
        # [deep-audit-1047 B-02] REMOVED: `declared-size precheck` used to be a
        # "bounded" verdict. It cannot be one: `OpenableColumns.SIZE` is whatever
        # the provider says (frequently null, occasionally a lie), and the site
        # that relied on it then called `readBytes()` — nothing stopped the
        # stream. `BackupSettingsScreen` is exactly that shape: a provider
        # reporting "no size" sailed past the precheck and into the heap. The
        # precheck is still a useful early reject, but the verdict must come from
        # the stream, so only the cap-carrying forms below count.
        if SAF_CAPPED_COPY_RE.search(block) and SAF_CAP_CONST_RE.search(block):
            return True, "capped extractor", snippet
        if any(call in block for call in SAF_HELPER_CALLS):
            return True, "bounded helper", snippet
    return False, "", snippet


# ── [deep-audit-1047 B-03] preview state must be keyed on its DATA SOURCE ──
#
# Same family as the two `REMEMBER_ANCHORS` above (a preview composable is
# reused by slot, so unkeyed state survives a file switch), one generation on:
# `PdfPreview`'s page state was keyed on `idx` ALONE while everything else in
# the same composable (`error` / `pageCount` / `renderer`) was keyed on
# `item.file`. Switching documents in the same preview slot therefore kept the
# previous document's rendered page: the slot's `idx` is unchanged, so the
# producer was not re-run AND its `if (value == null)` guard considered the
# stale bitmap "loaded".
#
# The subject set is derived, not listed:
#   * every `private fun <X>Preview(item: FileItem)` in the screen is a subject
#     (a new preview surface is covered the day it lands);
#   * every `produceState(` inside one is judged on its NAMED key arguments
#     (`key1 = …` / `key2 = …` / positional) — at least one must name the data
#     source the produced value derives from.
# Deriving the anchors also retires the hardcoded pair above: a preview renamed
# or added can no longer quietly drop out of the `remember` criterion either.

PREVIEW_SIG_RE = re.compile(r"^\s*private fun ([A-Za-z0-9_]+)Preview\(item: FileItem\)\s*\{")
PRODUCE_STATE_RE = re.compile(r"produceState\b")
# A key argument must BE a reference chain (`item.file`, `idx`, `renderer.epoch`).
# Anything else — a lambda chain, an arithmetic expression, a literal — is judged
# fail-closed: the criterion cannot prove the runtime key tracks the file.
KEY_REF_RE = re.compile(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*(?:\(\))?)*")


def _call_paren(text, idx):
    """Index of the argument-list `(` for a call starting at [idx].

    Generic type arguments may nest (`produceState<Pair<List<X>, Int>?>(`), so a
    `[^>]*` pattern stops at the FIRST `>` and misses the call entirely. Walk
    forward instead and only accept a `(` once every `<` is closed.
    """
    depth = 0
    i = idx
    n = len(text)
    while i < n:
        c = text[i]
        if c == "<":
            depth += 1
        elif c == ">":
            if depth:
                depth -= 1
        elif c == "(" and depth <= 0:
            return i
        elif c == ")":
            return -1
        elif c == "\n" and depth <= 0:
            return -1
        i += 1
    return -1


def derived_preview_anchors(code):
    """[(signature, (start, end))] for every `private fun *Preview(item: …)`."""
    out = []
    for line_no, line in enumerate(code.split("\n")):
        m = PREVIEW_SIG_RE.match(line)
        if m:
            out.append(("private fun %sPreview(item: FileItem) {" % m.group(1), line_no))
    return out


def _produce_keys(text, idx):
    """The produceState call's own key arguments (named + positional)."""
    start = _call_paren(text, idx)
    if start < 0:
        return []
    n = len(text)
    j, depth = start, 0
    while j < n:
        c = text[j]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                break
        j += 1
    call = text[start + 1:j]
    # Split on TOP-LEVEL commas only: `initialValue = renderer.cached(idx)`
    # carries a comma-free call, but a nested lambda or `Pair(a, b)` would not.
    parts, depth, cur = [], 0, ""
    for ch in call:
        if ch in "([{<":
            depth += 1
        elif ch in ")]}>":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        parts.append(cur)
    keys = []
    for part in parts:
        arg = part.strip()
        if not arg:
            continue
        # `initialValue = …` names the starting value, not a key.
        if re.match(r"initialValue\s*=", arg):
            continue
        if re.match(r"[A-Za-z_][A-Za-z0-9_]*\s*=", arg):
            arg = arg.split("=", 1)[1].strip()
        if arg:
            keys.append(arg)
    return keys


def check_preview_keys(src):
    """(violations, sites) — C-PDF-KEY."""
    violations = []
    sites = 0
    code = strip_comments(src)
    if code is None:
        return (["FilePreviewScreen.kt does not tokenise, so the preview keys cannot be "
                 "judged (fail-closed): %s" % LEX["reason"]], 0)
    for sig, line_no in derived_preview_anchors(code):
        body = decl_body(code, sig)
        if body is None:
            violations.append(
                "%s must exist exactly once (anchor missing or duplicated)" % sig
            )
            continue
        sites += 1
        offset = code.find(body)
        start = offset if offset >= 0 else 0
        pos = 0
        while True:
            m = PRODUCE_STATE_RE.search(body, pos)
            if not m:
                break
            pos = m.end()
            if _call_paren(body, m.end()) < 0:
                continue          # a type reference (`produceState<…>`), not a call
            keys = _produce_keys(body, m.end())
            hit = ", ".join(keys) if keys else "(no key argument at all)"
            # [wide-1024] Compose compares the key VALUE, not its spelling. A key
            # written `item.file.let { 0 }` still contains "item.file" and still
            # compiles, but the runtime key is the constant 0 — the slot then
            # survives every file switch. Accept only a plain reference chain.
            opaque = [k for k in keys if not KEY_REF_RE.fullmatch(k)]
            if opaque:
                violations.append(
                    "%s holds a produceState key that is not a plain data-source reference "
                    "(%s) — the key's VALUE decides whether the producer restarts, so a chain "
                    "call returning a constant freezes the slot across file switches"
                    % (sig, ", ".join(opaque))
                )
            # [batch6-t03] `startswith("item.file.")` accepted ANY property
            # chain, so `key2 = item.file.parent` — identical for two documents
            # in one directory — kept the producer's slot across a file switch
            # and the old page stayed on screen. A key has to carry FILE
            # identity: the file itself, or a property whose value differs
            # between two files.
            if not any(_is_file_identity_key(k) for k in keys):
                violations.append(
                    "%s holds a produceState whose key set misses its data source "
                    "(key set = %s) — the slot is reused across files, so the "
                    "produced value must be keyed on item.file / renderer / item"
                    % (sig, hit)
                )
            weak = [k for k in keys
                    if k.startswith("item.file.") and not _is_file_identity_key(k)]
            if weak:
                violations.append(
                    "%s holds a produceState key that is derived from the file but does not "
                    "identify it (%s) — `item.file.parent` is the same value for two documents "
                    "in one directory, so the producer never restarts and the previous page "
                    "stays on screen" % (sig, ", ".join(weak))
                )
            _ = start
    if sites == 0:
        violations.append(
            "no `private fun *Preview(item: FileItem)` found in FilePreviewScreen.kt — "
            "the preview-key criterion lost its subject"
        )
    return violations, sites


def _saf_short(rel):
    return rel.split(os.sep + "rikkaminis" + os.sep)[-1]


def check_saf_imports(root, exemptions=None):
    """(violations, rot, sites, notes) — criterion B-07.

    `exemptions` defaults to the production table (empty); the selftest passes
    SELFTEST_SAF_EXEMPTIONS so the staleness rule keeps a live subject.
    """
    exemptions = SAF_EXEMPTIONS if exemptions is None else exemptions
    violations, rot, notes = [], [], []
    sites = 0

    helper = os.path.join(root, SAF_HELPER_REL)
    if not os.path.isfile(helper):
        # NOT an early return: the sweep below must still run, so the baseline
        # tree reports the DEFECT (an unbounded read) and not only the missing
        # helper. A gate that stops at the first missing file reports the
        # symptom and hides the disease.
        rot.append(
            "%s is gone — the bounded SAF read has no implementation left"
            % _saf_short(SAF_HELPER_REL)
        )
        helper_code = ""
    else:
        with open(helper, encoding="utf-8", errors="replace") as fh:
            helper_code = strip_comments(fh.read())

    reader = decl_body(helper_code, SAF_READER_ANCHOR)
    sites += 1
    if reader is None:
        violations.append(
            "%s: readAtMost(input, cap) must exist exactly once — it is the only thing "
            "between a picked file and the heap" % _saf_short(SAF_HELPER_REL)
        )
    else:
        if not SAF_OVERFLOW_RE.search(reader):
            violations.append(
                "%s: readAtMost never compares the byte count against its cap — an unbounded "
                "read wearing a cap parameter" % _saf_short(SAF_HELPER_REL)
            )
        if "return null" not in reader:
            violations.append(
                "%s: readAtMost must report an overflow as null, not as a truncated array "
                "(half a document would import silently)" % _saf_short(SAF_HELPER_REL)
            )
    entry = decl_body(helper_code, SAF_ENTRY_ANCHOR)
    sites += 1
    if entry is None:
        violations.append(
            "%s: readSafBytes(resolver, uri, cap) must exist exactly once — the two import "
            "screens have to share ONE bounded entry point" % _saf_short(SAF_HELPER_REL)
        )
    else:
        if "TooLarge" not in entry:
            violations.append(
                "%s: readSafBytes must map an over-cap pick to a TooLarge verdict — a read "
                "failure and an over-cap pick are different user stories" % _saf_short(SAF_HELPER_REL)
            )
        if "readAtMost(" not in entry:
            violations.append(
                "%s: readSafBytes must read through readAtMost — the cap has to be enforced "
                "on the stream, not on a provider-reported size" % _saf_short(SAF_HELPER_REL)
            )
        # [wide-1024] …and it has to be THE cap. A call with the right callee and
        # an unbounded second argument (`Long.MAX_VALUE - 1L`) reads like a bounded
        # read while materialising the whole provider document.
        for m in re.finditer(r"\breadAtMost\s*\(", entry):
            args = _args_of(entry, m.end() - 1)
            if args is None or len(args) < 2:
                violations.append(
                    "%s: cannot read the readAtMost(...) arguments (fail-closed) — the cap "
                    "argument is the whole bound and must stay readable"
                    % _saf_short(SAF_HELPER_REL)
                )
                continue
            cap_arg = args[1]
            # [batch6-t03] The old judge accepted `minOf(cap…)` by SPELLING, so
            # `minOf(cap + Long.MAX_VALUE / 2, Long.MAX_VALUE / 2)` (which really
            # allocates 4 EiB) passed. The bound is now computed: the caller's cap
            # contributes 0, every constant contributes itself, and `minOf` takes
            # the minimum — so a huge arm makes the whole cap huge.
            msg = _cap_violation(
                helper_code,
                "%s: readSafBytes calls readAtMost(%s, %s)"
                % (_saf_short(SAF_HELPER_REL), args[0], cap_arg),
                cap_arg, SAF_CAP_SANITY_BYTES,
            )
            if msg:
                violations.append(msg)
    if not SAF_CAP_CONST_RE.search(helper_code):
        violations.append(
            "%s: the SAF caps must stay named MAX_*_BYTES constants (the reason has to "
            "travel with the number)" % _saf_short(SAF_HELPER_REL)
        )
    if not SAF_DECLARED_SIZE_RE.search(helper_code):
        violations.append(
            "%s: the SAF read must query the provider's declared size — it is a cheap early "
            "reject before the bytes are pulled" % _saf_short(SAF_HELPER_REL)
        )

    for rel in SAF_CONSUMERS:
        path = os.path.join(root, rel)
        short = _saf_short(rel)
        if not os.path.isfile(path):
            rot.append("%s is gone — the bounded-import criterion lost half its subject" % short)
            continue
        with open(path, encoding="utf-8", errors="replace") as fh:
            code = strip_comments(fh.read())
        sites += 1
        for needle in SAF_WHOLE_READS:
            n = code.count(needle)
            if n:
                violations.append(
                    "%s: %d whole-file read(s) (%s) — the picked document must go through "
                    "readSafBytes, not into one array/String" % (short, n, needle)
                )
        n = code.count(SAF_SITE)
        if n:
            violations.append(
                "%s: %d direct openInputStream( site(s) — the SAF read must route through "
                "readSafBytes so there is one cap to audit" % (short, n)
            )
        if "readSafBytes(" not in code:
            violations.append(
                "%s: no readSafBytes( call — the bounded helper is not wired in" % short
            )
        if "TooLarge" not in code:
            violations.append(
                "%s: an over-cap pick must stay visible (no TooLarge verdict found) — reading "
                "it as a read failure sends the user hunting for a corrupt file" % short
            )
        if not SAF_CAP_CONST_RE.search(code):
            violations.append(
                "%s: the import cap must be passed explicitly (no MAX_*_BYTES token)" % short
            )

    # [deep-audit-1047 B-02] C-SAF-1 — the domain is derived: every UI tree
    # under the app package, and every file in it that carries a site must
    # actually be scanned (a domain that shrank back to `ui/` alone is caught
    # here, which is the blindspot this criterion exists for).
    candidates = set()
    for scan_dir in SAF_SUBJECT_DIRS:
        abs_dir = os.path.join(root, scan_dir)
        if not os.path.isdir(abs_dir):
            rot.append("%s/ is gone — the SAF sweep lost part of its subject" % _saf_short(scan_dir))
            continue
        for base, _dirs, files in os.walk(abs_dir):
            for name in sorted(files):
                if not name.endswith(".kt"):
                    continue
                full = os.path.join(base, name)
                with open(full, encoding="utf-8", errors="replace") as fh:
                    if SAF_SITE in strip_comments(fh.read()):
                        candidates.add(os.path.relpath(full, root))
    if not candidates:
        rot.append("no openInputStream( site found anywhere in the derived SAF domain — the sweep lost its subject")

    resolved = set()
    present_files = set()
    scanned_files = set()
    for scan_dir in SAF_SCAN_DIRS:
      ui_root = os.path.join(root, scan_dir)
      if not os.path.isdir(ui_root):
          continue
      for base, _dirs, files in os.walk(ui_root):
        for name in sorted(files):
            if not name.endswith(".kt"):
                continue
            full = os.path.join(base, name)
            rel = os.path.relpath(full, root)
            present_files.add(rel)
            scanned_files.add(rel)
            with open(full, encoding="utf-8", errors="replace") as fh:
                code = strip_comments(fh.read())
            blocks = None
            start = 0
            while True:
                idx = code.find(SAF_SITE, start)
                if idx < 0:
                    break
                start = idx + 1
                if blocks is None:
                    blocks = _saf_brace_blocks(code)
                sites += 1
                bounded, reason, snippet = _saf_classify(code, idx, blocks)
                if bounded:
                    notes.append("[B-07 bounded] %s — %s" % (_saf_short(rel), reason))
                    continue
                hit = None
                for key in exemptions:
                    if key[0] == rel and key[1] in snippet:
                        hit = key
                        break
                if hit is not None:
                    resolved.add(hit)
                    notes.append("[B-07 exempt] %s — %s" % (_saf_short(rel), exemptions[hit]))
                    continue
                line = code.count("\n", 0, idx) + 1
                violations.append(
                    "%s:%d openInputStream( reading the whole document with no bound — cap it "
                    "or register an exemption with a reason" % (_saf_short(rel), line)
                )

    for rel in sorted(candidates - scanned_files):
        violations.append(
            "%s carries an openInputStream( site but is outside the sweep domain — "
            "derive the domain from the tree instead of listing directories"
            % _saf_short(rel)
        )

    for rel, needle in exemptions:
        if (rel, needle) in resolved:
            continue
        if rel not in present_files:
            notes.append(
                "[B-07 skip] exemption for %s — this tree does not ship that file"
                % _saf_short(rel)
            )
            continue
        rot.append(
            "%s: exemption %r no longer matches any site — a stale whitelist would silently "
            "cover the next hole" % (_saf_short(rel), needle)
        )
    if sites == 0:
        rot.append("no openInputStream( site found anywhere under ui/** — the sweep lost its subject")
    return violations, rot, sites, notes


# ── [deep-audit-1045 B-07] fixture: the SAF import decision, written out ────

_SAF_HELPER = (
    "package com.rikkaminis.app.ui.settings\n"
    "\n"
    "import android.content.ContentResolver\n"
    "import android.net.Uri\n"
    "import android.provider.OpenableColumns\n"
    "import java.io.InputStream\n"
    "\n"
    "internal const val MAX_SKILL_IMPORT_BYTES = 32L * 1024 * 1024\n"
    "internal const val MAX_PROVIDER_JSON_BYTES = 16L * 1024 * 1024\n"
    "\n"
    "internal sealed class SafRead {\n"
    "    class Ok(val bytes: ByteArray) : SafRead()\n"
    "    object TooLarge : SafRead()\n"
    "    object Failed : SafRead()\n"
    "}\n"
    "\n"
    "internal fun readAtMost(input: InputStream, cap: Long): ByteArray? {\n"
    "    var total = 0L\n"
    "    while (total <= cap) {\n"
    "        val room = cap - total + 1L\n"
    "        if (room <= 0L) return null\n"
    "        val n = input.read(ByteArray(8 * 1024))\n"
    "        if (n <= 0) return ByteArray(0)\n"
    "        total += n.toLong()\n"
    "        if (total > cap) return null\n"
    "    }\n"
    "    return null\n"
    "}\n"
    "\n"
    "internal fun readSafBytes(resolver: ContentResolver, uri: Uri, cap: Long): SafRead {\n"
    "    val declared = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)\n"
    "    val stream = resolver.openInputStream(uri)\n"
    "    return stream.use { input ->\n"
    "        val bytes = readAtMost(input, cap)\n"
    "        if (bytes == null) SafRead.TooLarge else SafRead.Ok(bytes)\n"
    "    }\n"
    "}\n"
    "\n"
    "internal fun safTooLargeText(capBytes: Long): String = \"too large: $capBytes\"\n"
)

_SAF_HELPER_NOCAP = _SAF_HELPER.replace("        if (total > cap) return null\n", "")

# [wide-1024] The call is right, the cap is not: `readAtMost(input, Long.MAX_VALUE
# - 1L)` still contains a `readAtMost(` token while materialising the whole
# provider document before the heap dies.
# [batch6-t03] `minOf(cap + Long.MAX_VALUE / 2, Long.MAX_VALUE / 2)` really
# bounds at ~4 EiB: the old judge accepted it because it matched `minOf(cap…`.
_SAF_HELPER_MINOF_HUGE = _SAF_HELPER.replace(
    "        val bytes = readAtMost(input, cap)\n",
    "        val bytes = readAtMost(input, minOf(cap + Long.MAX_VALUE / 2, "
    "Long.MAX_VALUE / 2))\n",
)
_SAF_HELPER_UNBOUNDED_CAP = _SAF_HELPER.replace(
    "        val bytes = readAtMost(input, cap)\n",
    "        val bytes = readAtMost(input, Long.MAX_VALUE - 1L)\n",
)
assert _SAF_HELPER_UNBOUNDED_CAP != _SAF_HELPER

_SAF_PROVIDER_FIXED = (
    "package com.rikkaminis.app.ui.settings\n"
    "\n"
    "fun importProviderJson(resolver: ContentResolver, uri: Uri) {\n"
    "    val read = readSafBytes(resolver, uri, MAX_PROVIDER_JSON_BYTES)\n"
    "    if (read !is SafRead.Ok) {\n"
    "        val msg = if (read is SafRead.TooLarge) safTooLargeText(MAX_PROVIDER_JSON_BYTES) else \"r\"\n"
    "        return\n"
    "    }\n"
    "    val jsonStr = String(read.bytes, Charsets.UTF_8)\n"
    "}\n"
)

_SAF_PROVIDER_READTEXT = _SAF_PROVIDER_FIXED.replace(
    "    val read = readSafBytes(resolver, uri, MAX_PROVIDER_JSON_BYTES)\n"
    "    if (read !is SafRead.Ok) {\n"
    "        val msg = if (read is SafRead.TooLarge) safTooLargeText(MAX_PROVIDER_JSON_BYTES) else \"r\"\n"
    "        return\n"
    "    }\n",
    "    val jsonStr = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }\n",
)

_SAF_PROVIDER_SWALLOWED = _SAF_PROVIDER_FIXED.replace(
    "        val msg = if (read is SafRead.TooLarge) safTooLargeText(MAX_PROVIDER_JSON_BYTES) else \"r\"\n",
    "        val msg = \"r\"\n",
)

_SAF_SKILLS_FIXED = (
    "package com.rikkaminis.app.ui.settings\n"
    "\n"
    "fun importSkillFile(resolver: ContentResolver, uri: Uri) {\n"
    "    val read = readSafBytes(resolver, uri, MAX_SKILL_IMPORT_BYTES)\n"
    "    val bytes = (read as? SafRead.Ok)?.bytes\n"
    "    if (bytes == null) {\n"
    "        val msg = if (read is SafRead.TooLarge) safTooLargeText(MAX_SKILL_IMPORT_BYTES) else \"f\"\n"
    "    }\n"
    "}\n"
)

_SAF_SKILLS_BASELINE = (
    "package com.rikkaminis.app.ui.settings\n"
    "\n"
    "fun importSkillFile(resolver: ContentResolver, uri: Uri) {\n"
    "    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }\n"
    "}\n"
)

# The chat file carries BOTH a bounded streaming site and the one registered
# exemption, so the compliant fixture exercises the exemption path too.
_SAF_CHAT_BOTH = (
    "package com.rikkaminis.app.ui.chat\n"
    "\n"
    "fun upload(resolver: ContentResolver, uri: Uri, dest: File) {\n"
    "    resolver.openInputStream(uri)?.use { input ->\n"
    "        dest.outputStream().use { output -> input.copyTo(output) }\n"
    "    }\n"
    "}\n"
    "\n"
    "fun preview(resolver: ContentResolver, uri: Uri) {\n"
    "    val all = resolver.openInputStream(attachment.uri)?.use { it.readBytes() }\n"
    "}\n"
)

# The exemption's site is gone — the whitelist now covers nothing and must be
# reported instead of silently rotting.
_SAF_CHAT_NO_EXEMPT = (
    "package com.rikkaminis.app.ui.chat\n"
    "\n"
    "fun upload(resolver: ContentResolver, uri: Uri, dest: File) {\n"
    "    resolver.openInputStream(uri)?.use { input ->\n"
    "        dest.outputStream().use { output -> input.copyTo(output) }\n"
    "    }\n"
    "}\n"
)

# A NEW unbounded site in a file nobody registered — the sweep must catch it.
_SAF_CHAT_EXTRA = _SAF_CHAT_BOTH + (
    "\n"
    "fun sneaky(resolver: ContentResolver, uri: Uri) {\n"
    "    val all = resolver.openInputStream(uri)?.use { it.readBytes() }\n"
    "}\n"
)


# [deep-audit-1047 B-02] A webapp screen (the half of the domain the old
# hardcoded `…/app/ui` sweep never walked) and its pre-fix shape: a bounded
# icon read and a copy-to-app-storage streaming site.
_SAF_WEBAPP_BOUNDED = (
    "package com.rikkaminis.app.webapp\n"
    "\n"
    "fun loadIcon(resolver: ContentResolver, uri: Uri): ByteArray? {\n"
    "    return resolver.openInputStream(uri)?.use { input -> readAtMost(input, MAX_ICON_SOURCE_BYTES) }\n"
    "}\n"
    "\n"
    "fun copyHtml(resolver: ContentResolver, uri: Uri, target: File) {\n"
    "    resolver.openInputStream(uri)?.use { input ->\n"
    "        target.outputStream().use { out -> input.copyTo(out) }\n"
    "    }\n"
    "}\n"
)

_SAF_WEBAPP_UNBOUNDED = _SAF_WEBAPP_BOUNDED.replace(
    "    return resolver.openInputStream(uri)?.use { input -> readAtMost(input, MAX_ICON_SOURCE_BYTES) }\n",
    "    return resolver.openInputStream(uri)?.use { it.readBytes() }\n",
)

# [R3-t02 05-3] the capped array is bound and then thrown away: the decode gets a
# constant, so the pick can never load while every boundedness arm stays green.
_SAF_WEBAPP_ICON_DROPPED = _SAF_WEBAPP_BOUNDED + (
    "\n"
    "fun loadBitmapFromUri(resolver: ContentResolver, uri: Uri): ByteArray? = runCatching {\n"
    "    val bytes = resolver.openInputStream(uri)?.use { input ->\n"
    "        readAtMost(input, MAX_ICON_SOURCE_BYTES)\n"
    "    } ?: return@runCatching null\n"
    "    ImageDecoding.decodeBytesBounded(ByteArray(0), ICON_MAX_EDGE_PX)\n"
    "}.getOrNull()\n"
)

def _binding_at(code, pos):
    """Name of the enclosing `val NAME = …` whose right-hand side contains [pos]."""
    best = None
    for m in re.finditer(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+?)?=(?!=)", code[:pos]):
        start = m.end()
        if start <= pos < expr_end(code, start):
            best = m.group(1)
    return best


def _consumed_as_value(code, pos, name):
    """Is [name] handed over as a WHOLE argument of some call after [pos]?"""
    for m in re.finditer(r"\b[A-Za-z_][\w.]*\s*\(", code[pos:]):
        for a, b in call_args(code, pos + m.end() - 1):
            if name in [s.strip() for s in value_sources(code[a:b].strip())]:
                return True
    return False


def check_saf_read_consumed(root):
    """(violations, sites, notes) — [R3-t02 05-3] the capped bytes must be used.

    `readAtMost(input, MAX_ICON_SOURCE_BYTES)` bounds the READ; nothing asked
    what happens to the array afterwards. The witness binds the capped bytes and
    then decodes `ByteArray(0)`: the streaming helper, the named cap constant,
    the off-the-composition-thread decode and the sweep's "bounded helper"
    verdict all stay exactly as the criteria above measure them, while a picked
    icon can never load — the cap guarded a value the caller throws away.

    The criterion follows the value: the name the read is bound to has to be
    handed over as a whole call argument (a whole-argument match, not "the
    identifier appears somewhere"), otherwise the capped array is dead.
    """
    violations, notes = [], []
    sites = 0
    for scan_dir in SAF_SCAN_DIRS:
        abs_dir = os.path.join(root, scan_dir)
        if not os.path.isdir(abs_dir):
            continue
        for base, _dirs, files in os.walk(abs_dir):
            for name in sorted(files):
                if not name.endswith(".kt"):
                    continue
                full = os.path.join(base, name)
                with open(full, encoding="utf-8", errors="replace") as fh:
                    code = strip_comments(fh.read())
                if not code or "readAtMost(" not in code:
                    continue
                rel = _saf_short(os.path.relpath(full, root))
                for m in re.finditer(r"\breadAtMost\s*\(", code):
                    if re.search(r"\bfun\s+$", code[max(0, m.start() - 12):m.start()]):
                        continue
                    sites += 1
                    bound = _binding_at(code, m.start())
                    if bound is None:
                        notes.append("[B-07 inline] %s — readAtMost( … ) feeds a value directly"
                                     % rel)
                        continue
                    if _consumed_as_value(code, m.start(), bound):
                        continue
                    violations.append(
                        "%s:%d the bytes read through readAtMost( … ) are bound to `%s` and "
                        "then dropped — the name never reaches another call as an argument, so "
                        "the cap protects a value the caller replaces or ignores"
                        % (rel, code.count("\n", 0, m.start()) + 1, bound))
    if sites == 0:
        notes.append("[PARTIAL] no readAtMost( … ) call site anywhere in the SAF domain")
    return violations, sites, notes


def _saf_fixture(shape="fixed"):
    """A minimal ui/** tree carrying one shape per B-07 criterion."""
    root = tempfile.mkdtemp(prefix="safimport_")

    helper = _SAF_HELPER
    provider = _SAF_PROVIDER_FIXED
    skills = _SAF_SKILLS_FIXED
    chat_src = _SAF_CHAT_BOTH
    webapp_src = _SAF_WEBAPP_BOUNDED

    if shape == "webapp_unbounded":
        webapp_src = _SAF_WEBAPP_UNBOUNDED
    elif shape == "icon_bytes_dropped":
        webapp_src = _SAF_WEBAPP_ICON_DROPPED
    elif shape == "no_helper":
        helper = None
    elif shape == "helper_nocap":
        helper = _SAF_HELPER_NOCAP
    elif shape == "helper_unbounded_cap":
        helper = _SAF_HELPER_UNBOUNDED_CAP
    elif shape == "helper_minof_huge":
        helper = _SAF_HELPER_MINOF_HUGE
    elif shape == "readtext":
        provider = _SAF_PROVIDER_READTEXT
    elif shape == "skills_baseline":
        skills = _SAF_SKILLS_BASELINE
    elif shape == "too_large_swallowed":
        provider = _SAF_PROVIDER_SWALLOWED
    elif shape == "sweep_extra_unregistered":
        chat_src = _SAF_CHAT_EXTRA
    elif shape == "sweep_stale_exemption":
        chat_src = _SAF_CHAT_NO_EXEMPT
    elif shape != "fixed":
        raise SystemExit("unknown SAF fixture shape %r" % shape)

    if helper is not None:
        _write(root, SAF_HELPER_REL, helper)
    _write(root, SAF_CONSUMERS[0], provider)
    _write(root, SAF_CONSUMERS[1], skills)
    _write(root, os.path.join(SAF_UI_DIR, "chat", "ChatPromptAndTools.kt"), chat_src)
    # [deep-audit-1047 B-02] the second half of the derived domain: a webapp
    # screen that reads a user-picked document. The pre-fix shape (a bare
    # readBytes()) is its own arm below.
    _write(root, os.path.join(APP_PKG_DIR, "webapp", "AddToHomeSheet.kt"), webapp_src)
    return root


def selftest_saf_imports():
    """One dirty arm per B-07 criterion; each must turn the criterion red."""
    failures = []

    def arm(name, expect, shape="fixed"):
        root = _saf_fixture(shape)
        v, r, sites, _notes = check_saf_imports(root, SELFTEST_SAF_EXEMPTIONS)
        blob = v + r
        if expect is None:
            if blob:
                failures.append("SAF %s must be clean, got %s" % (name, blob))
            else:
                print("[ok] SAF %s -> clean (%d site(s))" % (name, sites))
        elif not any(expect in item for item in blob):
            failures.append("SAF %s did not trip %r (got %s)" % (name, expect, blob))
        else:
            print("[ok] SAF %s is caught -> %s" % (name, expect))
        shutil.rmtree(root, ignore_errors=True)

    arm("compliant tree", None)
    arm("baseline readText restored", "whole-file read", shape="readtext")
    arm("baseline bare reader", "no readSafBytes( call", shape="skills_baseline")
    arm("helper without a cap", "never compares the byte count against its cap",
        shape="helper_nocap")
    # [wide-1024] a bounded-looking call with an unbounded cap argument.
    arm("helper called with an unbounded cap", "must be the caller's cap",
        shape="helper_unbounded_cap")
    arm("helper cap hidden behind minOf(...) is caught", "must be the caller's cap",
        shape="helper_minof_huge")
    arm("helper gone (fail-closed)", "no implementation left", shape="no_helper")
    arm("over-cap verdict swallowed", "must stay visible", shape="too_large_swallowed")
    arm("unregistered unbounded site", "with no bound", shape="sweep_extra_unregistered")
    arm("stale exemption", "no longer matches any site", shape="sweep_stale_exemption")
    # [deep-audit-1047 B-02] C-SAF-2: the tree the old hardcoded domain never
    # walked, in its pre-fix (unbounded) shape.
    arm("webapp unbounded read", "with no bound", shape="webapp_unbounded")
    # [deep-audit-1047 B-02] C-SAF-1: shrink the domain back to `ui/` and the
    # webapp site must be reported as UNCOVERED, not silently skipped.
    saved = globals()["SAF_SCAN_DIRS"]
    try:
        globals()["SAF_SCAN_DIRS"] = (SAF_UI_DIR,)
        arm("domain shrunk back to ui/ only", "outside the sweep domain")
    finally:
        globals()["SAF_SCAN_DIRS"] = saved

    # [R3-t02 05-3] the capped array must be the array that gets used.
    for shape, needle in (("fixed", None), ("icon_bytes_dropped", "then dropped")):
        root = _saf_fixture(shape)
        try:
            v, sites, _notes = check_saf_read_consumed(root)
            if needle is None:
                if v:
                    failures.append("SAF capped-byte consumption must be clean, got %s" % (v,))
                else:
                    print("[ok] SAF capped bytes consumed -> clean (%d site(s))" % sites)
            elif not any(needle in item for item in v):
                failures.append("SAF capped-bytes-dropped did not trip %r (got %s)"
                                % (needle, v))
            else:
                print("[ok] SAF capped bytes dropped before the decode -> caught")
        finally:
            shutil.rmtree(root, ignore_errors=True)
    return failures


# ── [deep-audit-1041 B-07] fixture: the log-share decision, written out ─────

def _log_share_screen(shape="fixed"):
    """The B-07 subject. [shape]: fixed / readtext / on_main / start_in_io /
    no_bounded_reader / no_reader_at_all / huge_cap_arg."""
    header = (
        "package com.rikkaminis.app.ui.settings\n"
        "\n"
        "import kotlinx.coroutines.Dispatchers\n"
        "import kotlinx.coroutines.withContext\n"
        "\n"
        "internal const val LOG_SHARE_MAX_CHARS = 100_000\n"
        "\n"
    )
    fixed_reader = (
        "internal fun readLogShareText(file: File, maxChars: Int = LOG_SHARE_MAX_CHARS): String? =\n"
        "    try {\n"
        "        FileInputStream(file).use { input ->\n"
        "            val reader = InputStreamReader(input, Charsets.UTF_8)\n"
        "            val builder = StringBuilder(minOf(maxChars, 64 * 1024))\n"
        "            val buffer = CharArray(8 * 1024)\n"
        "            while (builder.length < maxChars) {\n"
        "                val read = reader.read(buffer, 0, minOf(buffer.size, maxChars - builder.length))\n"
        "                if (read <= 0) break\n"
        "                builder.append(buffer, 0, read)\n"
        "            }\n"
        "            builder.toString()\n"
        "        }\n"
        "    } catch (_: Exception) { null }\n"
        "\n"
    )
    if shape == "no_bounded_reader":
        reader = (
            "internal fun readLogShareText(file: File): String? =\n"
            "    try { file.bufferedReader(Charsets.UTF_8).readLine() } catch (_: Exception) { null }\n"
            "\n"
        )
    elif shape == "no_reader_at_all":
        reader = ""
    else:
        reader = fixed_reader

    if shape == "huge_cap_arg":
        # [batch6-t03] the helper stays perfect; the CALLER re-materialises the
        # whole file through the named argument.
        fallback = ("            val text = readLogShareText(file, maxChars = Int.MAX_VALUE) "
                    "?: return\n")
    elif shape == "readtext":
        fallback = ('            val text = try { file.readText().take(100_000) } '
                    'catch (_: Exception) { return }\n')
    else:
        fallback = "            val text = readLogShareText(file) ?: return\n"

    if shape == "empty_text":
        extra = '                putExtra(Intent.EXTRA_TEXT, "")\n'
    else:
        extra = "                putExtra(Intent.EXTRA_TEXT, text)\n"

    inner = (
        "        if (!file.exists()) return null\n"
        "        try {\n"
        "            val uri = FileProvider.getUriForFile(context, \"authority\", file)\n"
        "            Intent.createChooser(Intent(Intent.ACTION_SEND), \"share\")\n"
        "        } catch (e: Exception) {\n"
        + fallback +
        "            val send = Intent(Intent.ACTION_SEND).apply {\n"
        "                type = \"text/plain\"\n"
        + extra +
        "            }\n"
        "            Intent.createChooser(send, \"share\")\n"
        "        }\n"
    )

    if shape == "on_main":
        prep = (
            "private fun shareLogFile(context: Context, file: File) {\n"
            + inner +
            "        context.startActivity(Intent(Intent.ACTION_SEND))\n"
            "}\n"
        )
    elif shape == "start_in_io":
        prep = (
            "private suspend fun buildLogShareChooser(context: Context, file: File): Intent? =\n"
            "    withContext(Dispatchers.IO) {\n"
            + inner +
            "    }\n"
            "\n"
            "private suspend fun shareLogFile(context: Context, file: File) {\n"
            "    withContext(Dispatchers.IO) {\n"
            "        context.startActivity(buildLogShareChooser(context, file))\n"
            "    }\n"
            "}\n"
        )
    else:
        prep = (
            "private suspend fun buildLogShareChooser(context: Context, file: File): Intent? =\n"
            "    withContext(Dispatchers.IO) {\n"
            + inner +
            "    }\n"
            "\n"
            "private suspend fun shareLogFile(context: Context, file: File) {\n"
            "    val chooser = buildLogShareChooser(context, file) ?: return\n"
            "    context.startActivity(chooser)\n"
            "}\n"
        )
    return header + reader + prep


def _write(root, rel, text):
    path = os.path.join(root, rel)
    if not os.path.isdir(os.path.dirname(path)):
        os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


def _log_share_fixture(shape="fixed", drop=False, keep_pkg=True, drop_preview=False):
    root = tempfile.mkdtemp(prefix="logshare_")
    if not drop_preview:
        _write(root, TARGET_REL, GOOD_SRC)
    if not drop:
        _write(root, LOG_SCREEN_REL, _log_share_screen(shape))
    elif keep_pkg:
        _write(root, "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/OtherScreen.kt",
               "package com.rikkaminis.app.ui.settings\n\nclass OtherScreen\n")
    return root


def selftest_log_share():
    """One dirty arm per B-07 criterion; every one must turn the gate red."""
    failures = []

    def arm(name, expect_red, needle=None, **kwargs):
        root = _log_share_fixture(**kwargs)
        try:
            v, rot, sites, _notes = check_log_share(root)
            red = bool(v or rot)
            ok = red == expect_red
            if ok and needle is not None:
                hay = " ".join(v + rot)
                if needle not in hay:
                    ok = False
            failures.append((name, ok, needle or ("violations=%s rot=%s" % (v, rot))))
        finally:
            shutil.rmtree(root, ignore_errors=True)

    arm("the bounded, off-main share path passes", False)
    arm("a whole-file readText() in the share fallback is caught", True,
        "whole-file read(s)", shape="readtext")
    arm("the share prep on the caller's thread is caught", True,
        "runs on the caller's thread", shape="on_main")
    arm("startActivity inside the IO block is caught", True,
        "inside a Dispatchers.IO block", shape="start_in_io")
    arm("a fallback reader with no cap is caught", True,
        "no bounded reader", shape="no_bounded_reader")
    arm("a call site that overrides the cap is caught", True,
        "cap the reader cannot honour", shape="huge_cap_arg")
    # [R3-t02 05-2] the bounded text dropped at the last hop.
    arm("a chooser that carries an empty body is caught", True,
        "does not derive from the bounded reader", shape="empty_text")
    arm("a file that lost its share anchor is a rot failure", True,
        "lost its anchor", drop=True)
    arm("a tree that never shipped ui/settings is skipped, not failed", False,
        drop=True, keep_pkg=False)

    for name, ok, note in failures:
        print("[%s] B-07 %s -> %s" % ("ok" if ok else "FAIL", name, note))
    return len([1 for _n, ok, _x in failures if not ok])


GOOD_SRC = """package probe
// a KDoc mentioning readBytes() on purpose must NOT trip the whole-file count:
// [rv1014-E1] was item.file.readBytes()
private const val MAX_TEXT_PREVIEW_BYTES = 512_000

private fun buildPrintHtml(item: FileItem): String {
    val raw = decodeUtf8Capped(readCapped(item.file, MAX_TEXT_PREVIEW_BYTES))
    return buildString { append(raw) }
}

private fun decodeUtf8Capped(bytes: ByteArray, cap: Int = MAX_TEXT_PREVIEW_BYTES): String {
    val truncated = bytes.size > cap
    return String(bytes.copyOf(cap), Charsets.UTF_8)
}

private fun readCapped(file: File, maxBytes: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(minOf(maxBytes + 1, 64 * 1024))
    val buf = ByteArray(64 * 1024)
    file.inputStream().use { input ->
        var remaining = maxBytes + 1
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size, remaining))
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }
    return out.toByteArray()
}

@Composable
private fun TextPreview(item: FileItem) {
    var content by remember(item.file) { mutableStateOf<String?>(null) }
    var truncated by remember(item.file) { mutableStateOf(false) }
    var error by remember(item.file) { mutableStateOf<String?>(null) }
}

@Composable
private fun MarkdownPreview(item: FileItem) {
    var content by remember(item.file) { mutableStateOf<String?>(null) }
    var error by remember(item.file) { mutableStateOf<String?>(null) }
}

@Composable
private fun PdfPreview(item: FileItem) {
    val renderer = remember(item.file) { Any() }
    val page by produceState<Bitmap?>(initialValue = null, key1 = idx, key2 = item.file) {
        value = null
    }
}

@Composable
private fun GalleryPreview(item: FileItem) {
    val gallery by produceState<Bitmap?>(initialValue = null, item.file.absolutePath) {
        value = null
    }
}
"""

BAD_UNBOUNDED = GOOD_SRC.replace(
    "val raw = decodeUtf8Capped(readCapped(item.file, MAX_TEXT_PREVIEW_BYTES))",
    "val raw = decodeUtf8Capped(item.file.readBytes())",
)

BAD_DECODE_DROPPED = GOOD_SRC.replace(
    "val raw = decodeUtf8Capped(readCapped(item.file, MAX_TEXT_PREVIEW_BYTES))",
    'val raw = String(readCapped(item.file, MAX_TEXT_PREVIEW_BYTES), Charsets.UTF_8)',
)

BAD_STRAY = GOOD_SRC.replace(
    "return buildString { append(raw) }",
    "return buildString { append(raw) }; val extra = File(\"x\").readBytes()",
)

# [da1026-F] The OOM shape the old gate measured past: no .readBytes(), yet an
# unbounded reader (`readLine()` grows a String to the file size).
BAD_BARE_BUFFERED = GOOD_SRC.replace(
    "return buildString { append(raw) }",
    "return buildString { append(raw) }; val s = File(\"x\").bufferedReader(Charsets.UTF_8).readLine()",
)

# [da1026-F] Other bare-read spellings.
BAD_READ_TEXT = GOOD_SRC.replace(
    "return buildString { append(raw) }",
    'return buildString { append(raw) }; val t = File("x").readText()',
)

BAD_READ_LINES = GOOD_SRC.replace(
    "return buildString { append(raw) }",
    'return buildString { append(raw) }; val ls = File("x").readLines()',
)

# [da1026-F] readCapped is deliberately NOT whitelisted: if the bound itself
# reached for a bare reader, it would reintroduce the very OOM this gate
# guards. This fixture proves the criterion bites inside readCapped too.
BAD_READCAP_BARE = GOOD_SRC.replace(
    "val n = input.read(buf, 0, minOf(buf.size, remaining))",
    "val n = buf.size; val all = file.readText()",
)

# [da1026-F] Unkeyed remember in TextPreview (slot reuse across file switch).
BAD_TEXT_UNKEYED = GOOD_SRC.replace(
    "private fun TextPreview(item: FileItem) {\n"
    "    var content by remember(item.file) { mutableStateOf<String?>(null) }\n"
    "    var truncated by remember(item.file) { mutableStateOf(false) }\n"
    "    var error by remember(item.file) { mutableStateOf<String?>(null) }\n"
    "}",
    "private fun TextPreview(item: FileItem) {\n"
    "    var content by remember { mutableStateOf<String?>(null) }\n"
    "    var truncated by remember(item.file) { mutableStateOf(false) }\n"
    "    var error by remember(item.file) { mutableStateOf<String?>(null) }\n"
    "}",
)

# [da1026-F] Unkeyed remember in MarkdownPreview.
BAD_MD_UNKEYED = GOOD_SRC.replace(
    "private fun MarkdownPreview(item: FileItem) {\n"
    "    var content by remember(item.file) { mutableStateOf<String?>(null) }\n"
    "    var error by remember(item.file) { mutableStateOf<String?>(null) }\n"
    "}",
    "private fun MarkdownPreview(item: FileItem) {\n"
    "    var content by remember { mutableStateOf<String?>(null) }\n"
    "    var error by remember(item.file) { mutableStateOf<String?>(null) }\n"
    "}",
)

# [da1026-F] The keys dropped ENTIRELY from a preview body (no
# remember(item.file) at all) must fail closed, not pass on the absence of
# the bare spelling alone.
BAD_TEXT_KEYS_GONE = GOOD_SRC.replace(
    "private fun TextPreview(item: FileItem) {\n"
    "    var content by remember(item.file) { mutableStateOf<String?>(null) }\n"
    "    var truncated by remember(item.file) { mutableStateOf(false) }\n"
    "    var error by remember(item.file) { mutableStateOf<String?>(null) }\n"
    "}",
    "private fun TextPreview(item: FileItem) {\n"
    "    val content = produceState<String?>(null) {}\n"
    "}",
)



# [deep-audit-1047 B-03] The shipped shape: the page producer keys on `idx`
# alone, so the slot survives a file switch with the previous document's bitmap.
BAD_PDF_KEY_ONLY_IDX = GOOD_SRC.replace(
    "initialValue = null, key1 = idx, key2 = item.file) {",
    "initialValue = null, key1 = idx) {",
)
assert BAD_PDF_KEY_ONLY_IDX != GOOD_SRC

# [deep-audit-1047 B-03] The whole preview set vanished (renamed / removed) —
# the derived criterion must fail closed instead of passing on zero subjects.
BAD_NO_PREVIEW_ANCHORS = GOOD_SRC.replace("Preview(item: FileItem) {", "Pane(item: FileItem) {")
assert BAD_NO_PREVIEW_ANCHORS != GOOD_SRC

# [wide-1024] The key still SPELLS `item.file` while the runtime key is the
# constant 0 — the old substring criterion passed this and the slot froze across
# file switches (b.pdf showing a.pdf's cached page).
BAD_PDF_KEY_OPAQUE = GOOD_SRC.replace(
    "initialValue = null, key1 = idx, key2 = item.file) {",
    "initialValue = null, key1 = idx, key2 = item.file.let { 0 }) {",
)
assert BAD_PDF_KEY_OPAQUE != GOOD_SRC

# [R3-t02] The print entry: the fixture GOOD_SRC deliberately stops at
# buildPrintHtml, so the two round-3 criteria (which one file only judges when
# the file actually has a print path) get their own subject. Faithful to
# FilePreviewScreen: an HTML bypass and a bounded buildPrintHtml branch, with the
# print WebView's page load in `printFile`.
PRINT_ENTRY_SRC = GOOD_SRC + """
private fun fileUrlFor(file: File): String = "file://" + encodeFileUrlPath(file.absolutePath)

private fun printFile(context: Context, item: FileItem, preRenderedHtml: String? = null) {
    val webView = WebView(context)
    printWebView = webView
    if (preRenderedHtml == null) {
        webView.loadUrl(fileUrlFor(item.file))
    } else {
        webView.loadDataWithBaseURL(null, preRenderedHtml, "text/html", "utf-8", null)
    }
}

@Composable
private fun PrintBar(item: FileItem, scope: CoroutineScope) {
    IconButton(onClick = {
        if (item.isHtmlFile) {
            printFile(context, item, preRenderedHtml = null)
        } else {
            scope.launch {
                val html = withContext(Dispatchers.IO) { buildPrintHtml(item) }
                printFile(context, item, preRenderedHtml = html)
            }
        }
    }) {
        Icon(Icons.Default.Print, contentDescription = "print")
    }
}
"""


def selftest():
    failures = []

    def expect_clean(name, v):
        if v:
            failures.append("%s must be clean, got %s" % (name, v))

    def expect_trip(name, v, needle):
        if not any(needle in item for item in v):
            failures.append("%s did not trip %r (got %s)" % (name, needle, v))

    good = check(GOOD_SRC)
    expect_clean("compliant fixture", good)

    bad_unbounded = check(BAD_UNBOUNDED)
    expect_trip("reverted-to-readBytes fixture", bad_unbounded, "readCapped(item.file")
    expect_trip("reverted-to-readBytes fixture", bad_unbounded, "must not contain readBytes()")
    expect_trip("reverted-to-readBytes fixture", bad_unbounded, "1 unbounded .readBytes()")

    bad_decode = check(BAD_DECODE_DROPPED)
    expect_trip("decode-dropped fixture", bad_decode, "decodeUtf8Capped wired in")

    bad_stray = check(BAD_STRAY)
    expect_trip("stray-readBytes fixture", bad_stray, "1 unbounded .readBytes()")

    # [da1026-F] fixtures — each trips exactly the criterion built for it.
    bad_buffered = check(BAD_BARE_BUFFERED)
    expect_trip("bare-bufferedReader fixture", bad_buffered, "bare bufferedReader(")

    bad_readtext = check(BAD_READ_TEXT)
    expect_trip("bare-readText fixture", bad_readtext, "bare .readText()")

    bad_readlines = check(BAD_READ_LINES)
    expect_trip("bare-readLines fixture", bad_readlines, "bare .readLines()")

    bad_outside = check(BAD_READCAP_BARE)
    expect_trip("bare-read-inside-readCapped fixture", bad_outside, "bare .readText()")

    bad_text_unkeyed = check(BAD_TEXT_UNKEYED)
    expect_trip("text unkeyed-remember fixture", bad_text_unkeyed, "TextPreview")
    expect_trip("text unkeyed-remember fixture", bad_text_unkeyed, "unkeyed remember { }")

    bad_md_unkeyed = check(BAD_MD_UNKEYED)
    expect_trip("markdown unkeyed-remember fixture", bad_md_unkeyed, "MarkdownPreview")

    bad_keys_gone = check(BAD_TEXT_KEYS_GONE)
    expect_trip("text keys-gone fixture", bad_keys_gone, "key its preview state on item.file")

    # [deep-audit-1047 B-03] C-PDF-KEY arms.
    expect_clean("compliant preview key set", check_preview_keys(GOOD_SRC)[0])
    bad_pdf_idx = check(BAD_PDF_KEY_ONLY_IDX)
    expect_trip("pdf produceState keyed on idx only", bad_pdf_idx, "misses its data source")
    bad_no_preview = check(BAD_NO_PREVIEW_ANCHORS)
    expect_trip("preview set gone (fail-closed)", bad_no_preview, "lost its subject")
    # [wide-1024] key value, not key spelling.
    bad_pdf_opaque = check(BAD_PDF_KEY_OPAQUE)
    expect_trip("pdf produceState key rewritten to a constant", bad_pdf_opaque,
                "not a plain data-source reference")

    # [da1026-F] fail-closed: a body that lost its anchor must be red, not skipped.
    missing_anchor = check(GOOD_SRC.replace(REMEMBER_ANCHORS[0], "private fun TextPreviewX(item: FileItem) {"))
    expect_trip("missing TextPreview anchor (fail-closed)", missing_anchor, "anchor missing or duplicated")
    dup_anchor = check(
        GOOD_SRC.replace("private fun MarkdownPreview(item: FileItem) {", REMEMBER_ANCHORS[0])
    )
    expect_trip("duplicated TextPreview anchor (fail-closed)", dup_anchor, "anchor missing or duplicated")
    missing_readcap = check(GOOD_SRC.replace("private fun readCapped(file: File, maxBytes: Int): ByteArray {", "private fun readCappedX(file: File, maxBytes: Int): ByteArray {"))
    expect_trip("missing readCapped token (fail-closed)", missing_readcap, "the readCapped helper must stay defined")

    print("selftest compliant            -> %d violation(s)" % len(good))
    print("selftest reverted-readBytes   -> %d violation(s)" % len(bad_unbounded))
    print("selftest decode-dropped       -> %d violation(s)" % len(bad_decode))
    print("selftest stray-readBytes      -> %d violation(s)" % len(bad_stray))
    print("selftest bare-bufferedReader  -> %d violation(s)" % len(bad_buffered))
    print("selftest bare-readText        -> %d violation(s)" % len(bad_readtext))
    print("selftest bare-readLines       -> %d violation(s)" % len(bad_readlines))
    print("selftest bare-inside-readCapped -> %d violation(s)" % len(bad_outside))
    print("selftest text-unkeyed         -> %d violation(s)" % len(bad_text_unkeyed))
    print("selftest markdown-unkeyed     -> %d violation(s)" % len(bad_md_unkeyed))
    print("selftest text-keys-gone       -> %d violation(s)" % len(bad_keys_gone))
    print("selftest anchors-fail-closed  -> 3 arms exercised")
    print("selftest pdf-key-only-idx     -> %d violation(s)" % len(bad_pdf_idx))
    print("selftest preview-set-gone     -> %d violation(s)" % len(bad_no_preview))
    print("selftest pdf-key-opaque       -> %d violation(s)" % len(bad_pdf_opaque))

    # ── [batch6-t03] the seven witnesses that used to walk through this gate ─
    def witness(name, src, needles):
        got = check(src)
        miss = [n for n in needles if not any(n in g for g in got)]
        if miss:
            failures.append("%s did not trip %r (got %s)" % (name, miss, got))
            print("  SELFTEST %s: FAIL (missing %r, got %r)" % (name, miss, got))
        else:
            print("  SELFTEST %s: OK (%d violation(s))" % (name, len(got)))

    # 01-1 — a bounded read bound to an unused local, beside a full JDK read.
    witness(
        "t03-01-1 decoy bounded read + Files.readString",
        GOOD_SRC.replace(
            "    val raw = decodeUtf8Capped(readCapped(item.file, MAX_TEXT_PREVIEW_BYTES))",
            "    val ignored = decodeUtf8Capped(readCapped(item.file, MAX_TEXT_PREVIEW_BYTES))\n"
            "    val raw = java.nio.file.Files.readString(item.file.toPath())"),
        ("never reads it", "whole-file Files.readString"),
    )
    # 01-2 — a constant remember key on two of the three TextPreview slots.
    witness(
        "t03-01-2 remember(Unit) key",
        GOOD_SRC.replace(
            "    var truncated by remember(item.file) { mutableStateOf(false) }\n"
            "    var error by remember(item.file) { mutableStateOf<String?>(null) }",
            "    var truncated by remember(Unit) { mutableStateOf(false) }\n"
            "    var error by remember(Unit) { mutableStateOf<String?>(null) }"),
        ("does not track the file",),
    )
    # 01-3 — a produceState key derived from the file but not identifying it.
    witness(
        "t03-01-3 produceState key2 = item.file.parent",
        GOOD_SRC.replace("key2 = item.file)", "key2 = item.file.parent)"),
        ("does not identify it",),
    )
    # 01-7 — a `}` inside a `//` comment in front of the full read.
    witness(
        "t03-01-7 comment-brace + Files.readString",
        GOOD_SRC.replace(
            "    val raw = decodeUtf8Capped(readCapped(item.file, MAX_TEXT_PREVIEW_BYTES))",
            "    // decodeUtf8Capped(readCapped(item.file, MAX_TEXT_PREVIEW_BYTES)) }\n"
            "    val raw = java.nio.file.Files.readString(item.file.toPath())"),
        ("whole-file Files.readString",),
    )

    # ── [R3-t02] print routing + print-load reachability ───────────────────
    entry = strip_comments(PRINT_ENTRY_SRC)
    expect_clean("print entry fixture (routing + load)",
                 check_print_routing(entry) + check_print_file_load(entry))
    widened = strip_comments(PRINT_ENTRY_SRC.replace(
        "if (item.isHtmlFile) {", "if (item.isHtmlFile || true) {"))
    routing_widened = check_print_routing(widened)
    expect_trip("print routing widened (|| true)", routing_widened, "folds to a constant")
    expect_trip("print routing widened (|| true)", routing_widened,
                "sits in a branch that can never run")
    dead_load = strip_comments(PRINT_ENTRY_SRC.replace(
        "if (preRenderedHtml == null) {", "if (preRenderedHtml == null && false) {"))
    expect_trip("print load behind a constant guard", check_print_file_load(dead_load),
                "folds to a constant")
    third_source = strip_comments(PRINT_ENTRY_SRC.replace(
        "printFile(context, item, preRenderedHtml = html)",
        'printFile(context, item, preRenderedHtml = "")'))
    expect_trip("a third print source", check_print_routing(third_source), "third print source")
    # A source with no print path at all stays silent here; the real tree's
    # presence requirement is enforced in run().
    expect_clean("source without a print path",
                 check_print_routing(strip_comments(GOOD_SRC))
                 + check_print_file_load(strip_comments(GOOD_SRC)))

    # [deep-audit-1041 B-07] the second target's arms.
    failures.append("B-07 arms misbehaved" if selftest_log_share() else None)
    # [deep-audit-1045 B-07] the third target's arms (user-picked SAF documents).
    failures.extend(selftest_saf_imports())
    failures = [f for f in failures if f]

    if failures:
        for f in failures:
            print("SELFTEST FAIL " + f)
        return 1

    # ── [deep-audit-1049 B-05] print slot lifecycle (derived checks) ───────
    # The fixture is a faithful mini version of FilePreviewScreen's print path;
    # every expectation below is written by hand (never taken from a run).
    slot_clean = "\n".join([
        "package probe",
        "",
        "private var printWebView: WebView? = null",
        "private var printWebViewSpooling = false",
        "",
        "private fun printFile(context: Context, item: FileItem) {",
        "    releasePrintWebViewIfNotSpooling()",
        "    val webView = WebView(context)",
        "    printWebView = webView",
        "    printWebViewSpooling = false",
        "    webView.webViewClient = object : WebViewClient() {",
        "        override fun onPageFinished(view: WebView, url: String) {",
        "            if (printWebView !== view) {",
        "                destroyPrintWebView(view)",
        "                return",
        "            }",
        "            printWebViewSpooling = true",
        "            val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager",
        '            printManager.print("job", view.createPrintDocumentAdapter("job"), attrs)',
        "        }",
        "    }",
        "}",
        "",
        "private fun releasePrintWebView() {",
        "    val view = printWebView ?: return",
        "    printWebView = null",
        "    printWebViewSpooling = false",
        "    destroyPrintWebView(view)",
        "}",
        "",
        "private fun destroyPrintWebView(view: WebView) {",
        "    runCatching {",
        "        view.stopLoading()",
        "        view.destroy()",
        "    }",
        "}",
        "",
        "@Composable",
        "private fun Preview() {",
        "    DisposableEffect(Unit) {",
        "        onDispose { releasePrintWebViewIfNotSpooling() }",
        "    }",
        "}",
        "",
    ])
    slot_helper = slot_clean.replace(
        "            if (printWebView !== view) {",
        "            if (!isCurrentPrintView(printWebView, view)) {",
    ).replace(
        "private fun releasePrintWebView() {",
        "internal fun isCurrentPrintView(owner: Any?, candidate: Any?): Boolean =\n"
        "    owner != null && owner === candidate\n"
        "\n"
        "private fun releasePrintWebView() {",
    )
    slot_helper_loose = slot_helper.replace("owner === candidate", "owner == candidate")

    slot_arms = (
        ("b05-clean", slot_clean, ()),
        ("b05-overwrite without release", slot_clean.replace(
            "    releasePrintWebViewIfNotSpooling()\n", ""),
         ("overwrites the print slot",)),
        ("b05-releaser keeps the reference", slot_clean.replace(
            "    printWebView = null\n", ""), ("must clear the slot",)),
        ("b05-releaser never destroys", slot_clean.replace(
            "        view.destroy()\n", ""), ("must actually destroy",)),
        ("b05-onDispose does nothing", slot_clean.replace(
            "        onDispose { releasePrintWebViewIfNotSpooling() }",
            "        onDispose { }"), ("must release the print slot",)),
        ("b05-no identity guard", slot_clean.replace(
            "            if (printWebView !== view) {\n"
            "                destroyPrintWebView(view)\n"
            "                return\n"
            "            }\n", ""), ("without first checking",)),
        # [wide-1024] comparing is not skipping: the guard must survive
        # short-circuiting AND must end the branch.
        ("b05-guard short-circuited", slot_clean.replace(
            "            if (printWebView !== view) {",
            "            if (false && printWebView !== view) {"),
         ("short-circuited",)),
        # [batch6-t03] the guard must be SATISFIABLE, not merely free of the
        # spellings `false &&` / `true ||`.
        ("b05-guard unsatisfiable (1 == 2)", slot_clean.replace(
            "            if (printWebView !== view) {",
            "            if ((1 == 2) && printWebView !== view) {"),
         ("short-circuited",)),
        ("b05-guard without return", slot_clean.replace(
            "                destroyPrintWebView(view)\n"
            "                return\n",
            "                destroyPrintWebView(view)\n"),
         ("does not end control flow",)),
        ("b05-helper guard (clean)", slot_helper, ()),
        ("b05-helper guard compares equality", slot_helper_loose,
         ("does not compare identity",)),
        ("b05-slot declaration gone", slot_clean.replace(
            "private var printWebView: WebView? = null\n", ""),
         ("declared exactly once",)),
    )
    for arm_name, sample, want in slot_arms:
        got = check_print_slot(sample)
        hit = all(any(w in g for g in got) for w in want) and (
            bool(want) == bool(got))
        if hit:
            print("  SELFTEST %s: OK (%d problem(s))" % (arm_name, len(got)))
        else:
            print("  SELFTEST %s: FAIL (want %r, got %r)" % (arm_name, want, got))
            failures.append(arm_name)
    if failures:
        for f in failures:
            print("SELFTEST FAIL " + f)
        print("FILEPREVIEW_PRINT_BOUNDED_SELFTEST=FAIL")
        return 1
    print("FILEPREVIEW_PRINT_BOUNDED_SELFTEST=PASS")
    print("FILEPREVIEW_PRINT_BOUNDED_T03_SELFTEST=PASS (7 witness arms: bounded-read value "
          "flow, remember key set, produceState key identity, satisfiable guard, cap call "
          "sites)")
    return 0


def run(root):
    path = os.path.join(root, TARGET_REL)
    if not os.path.isfile(path):
        print("FAIL missing target: %s" % path)
        return 1
    with open(path, encoding="utf-8") as fh:
        src = fh.read()
    v = check(src)
    # [deep-audit-1049 B-05] `check_print_slot` is silent when the file has no
    # slot; on the REAL tree that silence would mean the print path moved, so the
    # presence requirement is enforced here (fixture sources legitimately stop at
    # buildPrintHtml).
    view = strip_comments(src)
    if view is None or "printWebView" not in view:
        v.append("the print WebView slot (printWebView) is gone from the preview — the "
                 "lifecycle criteria below have lost their subject (fail-closed)")
    if view is not None and "printFile(" not in view:
        v.append("the print entry point (printFile) is gone from the preview — the print "
                 "routing criteria have lost their subject (fail-closed)")
    rc = 0
    if v:
        for item in v:
            print("FAIL %s: %s" % (TARGET_REL, item))
        rc = 1
    else:
        print("PASS %s: buildPrintHtml bounded via readCapped, decode wired, 0 unbounded readBytes" % TARGET_REL)
    # [deep-audit-1041 B-07] second target, same family.
    lv, lrot, lsites, lnotes = check_log_share(root)
    for n in lnotes:
        print("  %s" % n)
    if lrot:
        for r in lrot:
            print("ROT %s" % r)
        rc = 1
    if lv:
        for item in lv:
            print("FAIL %s" % item)
        rc = 1
    if not (lv or lrot):
        print("PASS %s: log share bounded and prepared off the caller's thread (%d decision(s) checked)"
              % (LOG_SCREEN_REL, lsites))
    # [deep-audit-1045 B-07] third target, same family: the picked SAF document.
    sv, srot, ssites, snotes = check_saf_imports(root)
    for n in snotes:
        print("  %s" % n)
    if srot:
        for r in srot:
            print("ROT %s" % r)
        rc = 1
    if sv:
        for item in sv:
            print("FAIL %s" % item)
        rc = 1
    if not (sv or srot):
        print("PASS %s: picked SAF documents are read through the bounded helper (%d decision(s) checked)"
              % (_saf_short(SAF_HELPER_REL), ssites))
    # [R3-t02 05-3] …and the capped array must be the array that gets used.
    cv, csites, cnotes = check_saf_read_consumed(root)
    for n in cnotes:
        print("  %s" % n)
    if cv:
        for item in cv:
            print("FAIL %s" % item)
        rc = 1
    else:
        print("PASS capped SAF reads reach their consumer (%d read site(s) checked)" % csites)
    return rc


def main():
    args = [a for a in sys.argv[1:] if a]
    if "--selftest" in args:
        return selftest()
    root = next((a for a in args if not a.startswith("-")), os.getcwd())
    return run(root)


if __name__ == "__main__":
    sys.exit(main())
