#!/usr/bin/env python3
"""backup_streaming_export_check — deep-audit-1023 FIX-I (ISSUE-10, P1) gate.

Pins the automatic-backup streaming + fetch-hardening contract:

  arm1 (streaming export): AutoBackupManager.runLocked must stream the
        document into the local file via `ConfigBackup.exportToWriter(` and
        must NOT assemble the whole document as a String via
        `ConfigBackup.export(` — the auto payload (skills + memory + shared/
        artifacts + knowledge graph) reaches tens of MB on a heavy install
        and the String path materializes it ×2 in UTF-16 before the first
        byte hits disk. runLocked must also rotate through the shared
        `rotateLocalAutoBackups(` helper, and that call must be REACHABLE.
  arm2 (WebDavSync surface): WebDavSync must expose a `backupAuto` File
        overload (callers that already have the document on disk must not
        rebuild a String to upload it) and the pure
        `sanitizeAutoBackupStamp(` token reducer — whose BODY must actually
        refuse the dangerous names (declaration alone is not a reducer).
  arm3 (screen fetch hardening): BackupSettingsScreen's fetch-to-local path
        must sanitize the remote display name through
        `WebDavSync.sanitizeAutoBackupStamp(`, reject a null answer, and build
        the destination `File(…)` from the value the sanitizer RETURNED (via
        real value flow, not token presence), and must join the fetched copy
        into the shared local rotation via `rotateLocalAutoBackups(`.
  arm4 (no main-thread dir listing): every `listLocal(` call must sit INSIDE
        a `withContext(Dispatchers.IO) { … }` block. `Dispatchers.IO` merely
        appearing on the same line (e.g. inside a discarding
        `.also { Dispatchers.IO }`) does not move the call off the main
        thread, so containment — not token co-occurrence — is the criterion.
  arm5 (secret-warning routing flag, deep-audit-1027 ISSUE-03): the
        `webDavUploadPending` marker is read once, inside the shared secret
        confirmation (`runExport`), to decide whether a confirmed export goes
        to WebDAV or through the local SAF picker. Any path that can leave a
        stale `true` behind silently re-routes a later LOCAL export to a
        remote upload (API keys included). The flag must therefore be cleared
        on >= 3 REACHABLE sites: the local-export entry, the `runExport`
        consumption and the dialog's `onDismissRequest`; and no clear may be
        overwritten by a later `= true` in the SAME block (only the last write
        in a block survives). Anchor is fail-closed: if the flag declaration
        disappears (renamed/moved), the arm goes RED instead of silently
        counting zero and passing — re-point the arm at the new name.
  arm6 (fetch-to-local busy gate + tail liveness, deep-audit-1040 B-08): the
        `onFetch` handler pulls a full auto-backup payload into memory and then
        writes + rotates `filesDir/backup-autos` — the same directory the
        nightly run, the SAF export and every restore touch — but it used to
        raise only the dialog-local `autoRemoteLoading`, so dismissing the
        dialog mid-flight left "Run Now" / "Restore" / "Export" live and two
        full payloads could land on that directory at once. It also ran its
        post-fetch refresh after the composition (and with it the coroutine
        scope) was gone. Three decisions are pinned on the handler REGION
        (located by brace matching, not by a line pattern): the fetch path
        claims the shared `operationBusy` gate, releases it inside the
        `finally` of the same region, and only refreshes the list while the
        coroutine is still alive. Fail-closed: a handler region that cannot be
        located — or that no longer carries the refresh call — is RED, not a
        silent zero-count pass; re-point the arm, do not delete it.

batch-5 hardening (six witnesses passed this gate unchanged)

  * 01-1 `parser-death` — the gate's own `strip_comments` kept string CONTENT
    while its brace counter was quote-blind, so a `}` inside a `Log.w` message
    closed `runLocked` early: the function body was truncated, the (banned)
    `ConfigBackup.export(` after the cut was never seen and the rotation call
    "found" inside the log string counted as the real one. All structure now
    comes from the shared `_kotlin_lexer` (`mask_code`) + `_kotlin_ast`
    (`function_body`, `match_bracket`): strings and comments are blanks, offsets
    are preserved, and a lex failure is fail-closed.
  * 01-2 `family-hole` — `if (1 == 2) rotateLocalAutoBackups(app)` defeated a
    5-item literal regex (`false` / `!true` / `0 > 1` …). Reachability is now
    the shared `path_feasible` / `guard_feasible` (constant folding with Kotlin
    precedence, plus `when` branches).
  * 01-3 `anchor` — arm2 only asked whether `fun sanitizeAutoBackupStamp(`
    exists, so a body that returns the raw name unchanged stayed green. The
    criterion now reads the BODY (`function_body`) and requires a reachable
    `return null` plus an actual refusal of `/`, `\\` and `..`.
  * 01-4 `dataflow` — `File(dir, (prefix + rawStamp + ".json").also { stamp })`
    contains the token `stamp` while evaluating the RAW name. Destination
    arguments are now resolved through `value_sources` / `peel_discarding`, so a
    discarded lambda cannot stand in for the value.
  * 01-5 `dataflow` — `listLocal(…).also { Dispatchers.IO }` satisfied a
    same-line substring test. Containment inside the `withContext(` lambda is
    now the criterion.
  * 01-6 `order` — `webDavUploadPending = false; webDavUploadPending = true`
    kept the `= false` count intact while the truthy write won. Clears must be
    reachable AND survive to the end of their block.

Usage:
  python3 backup_streaming_export_check.py <repo_root>   # real tree
  python3 backup_streaming_export_check.py --selftest    # fixtures

Selftest marker: BACKUP_STREAMING_EXPORT_SELFTEST=PASS
"""

import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import KotlinLexError, mask_comments  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    function_body,
    match_bracket,
)
from _kotlin_valueflow import (  # noqa: E402
    call_args,
    code_view,
    contains_ident,
    path_feasible,
    value_sources,
)

AUTO_MGR_REL = "src/android/app/src/main/java/com/rikkaminis/app/backup/AutoBackupManager.kt"
SYNC_REL = "src/android/app/src/main/java/com/rikkaminis/app/backup/WebDavSync.kt"
SCREEN_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/BackupSettingsScreen.kt"

FILE_OVERLOAD_PAT = re.compile(
    r"fun\s+backupAuto\(\s*config:\s*WebDavConfig\s*,\s*file:\s*File")


def squash(expr):
    """Whitespace-free, outer-paren-stripped form of an expression."""
    e = re.sub(r"\s+", "", expr or "")
    while (len(e) >= 2 and e[0] == "(" and e[-1] == ")"
           and match_bracket(e, 0) == len(e) - 1):
        e = e[1:-1]
    return e


def read_views(root, rel):
    """(structural view, literal view) — both length-preserving.

    structural: comments AND string/char literal interiors blanked (brace and
                paren analysis, call-argument slicing, identifier lookups);
    literal:    comments only (what a string/char literal actually SAYS — the
                sanitizer's forbidden-character refusals live inside literals).
    A missing file reads as (None, None); a lex failure propagates so the
    caller can fail closed.
    """
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return None, None
    with open(path, encoding="utf-8", errors="replace") as fh:
        src = fh.read()
    return code_view(src), mask_comments(src)


def block_span(text, pos):
    """(start, end) of the innermost `{ … }` block containing `pos`."""
    stack = []
    for i, ch in enumerate(text):
        if ch == "{":
            stack.append(i)
        elif ch == "}":
            if not stack:
                continue
            start = stack.pop()
            if start < pos <= i:
                return start, i + 1
    return 0, len(text)


def braced_from(text, start):
    """Text from `start` through the brace closing the first `{` at/after it."""
    open_i = text.find("{", start)
    if open_i < 0:
        return None
    close = match_bracket(text, open_i)
    return None if close < 0 else text[start:close + 1]


def assign_target(code, pos):
    """The variable `val X =` / `X =` whose value starts at `pos`, or None."""
    head = code[max(0, pos - 240):pos]
    m = re.search(r"(?:val\s+|var\s+)?([A-Za-z_]\w*)\s*(?::\s*[\w<>?,. ]+)?=\s*$", head)
    return m.group(1) if m else None


STREAM_CALL = re.compile(r"ConfigBackup\s*\.\s*exportToWriter\s*\(")
STRING_CALL = re.compile(r"ConfigBackup\s*\.\s*export\s*\(")
ROTATE_APP = re.compile(r"rotateLocalAutoBackups\s*\(\s*app\s*\)")


def check_arm1(auto_code):
    """AutoBackupManager: streaming export + shared rotation, and the rotation
    must be REACHABLE.

    wide-1024: the old criterion was a whole-file substring match, so
    `if (false) rotateLocalAutoBackups(app)` read exactly like the real call —
    `filesDir/backup-autos` then grows without bound until the device is full,
    which is the defect [deep-audit-1027 B-04] closed. The check is now scoped
    to runLocked's body and the call site is checked for a dead guard.

    batch-5: the body is derived from the shared lexer's masked view, so a `}`
    inside a log string can no longer truncate the region (witness 01-1), and
    reachability comes from the shared `path_feasible` instead of a five-item
    literal regex (witness 01-2).
    """
    if auto_code is None:
        return "AutoBackupManager.kt not found"
    span = function_body(auto_code, "runLocked")
    if span is None:
        return ("AutoBackupManager.runLocked not found (anchor drift) — re-point arm 1 "
                "at the renamed writer; do not delete it")
    body_start, body_end, kind = span
    if kind != "block":
        return ("AutoBackupManager.runLocked is no longer a block-bodied function — its "
                "streaming writer cannot be audited (fail-closed)")
    region = auto_code[body_start:body_end]
    if not STREAM_CALL.search(region):
        return "runLocked must stream via ConfigBackup.exportToWriter( (no full-document String)"
    if STRING_CALL.search(region):
        return "runLocked must not assemble the whole document via ConfigBackup.export("
    sites = [m.start() for m in ROTATE_APP.finditer(region)]
    if not sites:
        return "runLocked must rotate through the shared rotateLocalAutoBackups(app) helper"
    if not any(path_feasible(region, pos) for pos in sites):
        return ("runLocked's shared rotation is DEAD-GUARDED (a constant condition can "
                "never hold) — the call is there, the rotation never runs, and "
                "filesDir/backup-autos grows without bound until the device is full")
    return None


SANITIZER = "sanitizeAutoBackupStamp"
SLASH_TOK = re.compile(r"""['"]/['"]""")
BACKSLASH_TOK = re.compile(r"""['"]\\\\['"]""")
DOTDOT_TOK = re.compile(r"""['"]\.\.['"]""")
RETURN_NULL = re.compile(r"\breturn\s+null\b")


def check_arm2(sync_code, sync_literal=None):
    """WebDavSync: File overload for backupAuto + a sanitizer that REFUSES.

    batch-5 (witness 01-3): the criterion used to stop at the declaration, so
    replacing the body with `if (trimmed.isNotEmpty()) return trimmed` kept the
    arm green — and put the path-traversal defect [deep-audit-1031 A-07] back
    in place. The body is now read (from the literal view, so quoted
    characters stay visible) and must reject: a reachable `return null` plus an
    explicit refusal of `/`, `\\` and `..`.
    """
    if sync_code is None:
        return "WebDavSync.kt not found"
    if not FILE_OVERLOAD_PAT.search(sync_code):
        return "WebDavSync.backupAuto needs a File overload (streaming callers must not rebuild a String)"
    span = function_body(sync_code, SANITIZER)
    if span is None:
        return "WebDavSync must define sanitizeAutoBackupStamp( (pure filename-token reducer)"
    if sync_literal is None:
        sync_literal = sync_code
    body_start, body_end, kind = span
    body = sync_literal[body_start:body_end]
    body_struct = sync_code[body_start:body_end]
    rejects = [m.start() for m in RETURN_NULL.finditer(body_struct)]
    if not any(path_feasible(body_struct, pos) for pos in rejects):
        return ("sanitizeAutoBackupStamp keeps its signature but never REJECTS a name (no "
                "reachable `return null`) — a remote display name is passed straight through "
                "into the local backup path")
    missing = [name for name, pat in (("/", SLASH_TOK), ("\\", BACKSLASH_TOK), ("..", DOTDOT_TOK))
               if not pat.search(body)]
    if missing:
        return ("sanitizeAutoBackupStamp's body no longer refuses %s — the declaration is "
                "still there, the refusal is not, so a display name carrying it lands in "
                "filesDir/backup-autos unchanged" % ", ".join("'%s'" % m for m in missing))
    return None


SANITIZE_CALL_RE = re.compile(r"WebDavSync\s*\.\s*sanitizeAutoBackupStamp\s*\(")
ROTATE_SCREEN = re.compile(r"rotateLocalAutoBackups\s*\(\s*context\s*\)")


def _dest_uses(region, var):
    """Does some `File(…)` argument's REAL value derive from `var`?

    Resolved through `value_sources`, so a discarding scope function
    (`.also { var }`, `.apply { … }`) cannot masquerade as a use: those return
    their receiver, not the lambda's value.
    """
    for f in re.finditer(r"\bFile\s*\(", region):
        for a_start, a_end in call_args(region, f.end() - 1):
            for src in value_sources(region[a_start:a_end]):
                if contains_ident(src, var):
                    return True
    return False


def check_arm3(screen_code):
    """Fetch-to-local path: sanitize, and USE what the sanitizer returned.

    wide-1024: the old criterion proved only that the call appears somewhere in
    the screen. Deleting the null-guard and building the destination from the
    RAW remote display name keeps the call — while writing a
    remote-controlled path (`../../../…`, absolute `/data/…`) into
    `filesDir/backup-autos`, i.e. the path-traversal / overwrite defect
    [deep-audit-1031 A-07] re-opened with one line.

    Criterion (per call site, on its enclosing block):
      1. the result is BOUND to a variable (not discarded);
      2. the sanitizer's null result is REJECTED (guard or `?:`);
      3. the destination `File(…)` is built from the sanitized value's REAL
         value flow, never from the raw token alone (batch-5, witness 01-4).
    An unparseable call site fail-closes.
    """
    if screen_code is None:
        return "BackupSettingsScreen.kt not found"
    calls = list(SANITIZE_CALL_RE.finditer(screen_code))
    if not calls:
        return "fetch-to-local must sanitize the remote display name via WebDavSync.sanitizeAutoBackupStamp("
    if not ROTATE_SCREEN.search(screen_code):
        return "fetch-to-local must join the shared local rotation via rotateLocalAutoBackups(context)"
    for m in calls:
        if re.search(r"\bfun\s+$", screen_code[max(0, m.start() - 40):m.start()]):
            continue  # the declaration itself
        close = match_bracket(screen_code, m.end() - 1)
        if close < 0:
            return ("WebDavSync.sanitizeAutoBackupStamp( … ) does not balance at offset %d "
                    "(fail-closed)" % m.start())
        call_end = close + 1
        var = assign_target(screen_code, m.start())
        if not var:
            return ("the sanitized remote name is DISCARDED at offset %d (result not bound "
                    "to a variable) — the file name is still derived from the raw, "
                    "remote-controlled display name (path traversal / overwrite into "
                    "filesDir/backup-autos)" % m.start())
        b_start, b_end = block_span(screen_code, m.start())
        after = screen_code[call_end:b_end]
        guarded = (re.match(r"\s*\?:", after) is not None
                   or re.search(r"\b%s\s*==\s*null\b" % re.escape(var), after) is not None
                   or re.search(r"\bnull\s*==\s*%s\b" % re.escape(var), after) is not None
                   or re.search(r"requireNotNull\s*\(\s*%s\b" % re.escape(var), after) is not None)
        if not guarded:
            return ("the sanitizer's rejection is IGNORED (`%s` is never null-checked) — a "
                    "remote display name that the reducer refuses is then written as-is "
                    "into filesDir/backup-autos" % var)
        region = screen_code[b_start:b_end]
        if not _dest_uses(region, var):
            return ("no `File(…)` in the fetch block is built from the sanitized value `%s` — the "
                    "sanitizer's result is decorative and the destination is derived from "
                    "something else (a discarded `.also { %s }` still evaluates the RAW, "
                    "remote-controlled name)" % (var, var))
    return None


IO_SCOPE = re.compile(r"\bwithContext\s*\(")
LIST_LOCAL = re.compile(r"\blistLocal\s*\(")


def io_scope_spans(code):
    """Spans of `withContext(Dispatchers.IO) { … }` calls (masked view)."""
    out = []
    for m in IO_SCOPE.finditer(code):
        args = call_args(code, m.end() - 1)
        if not args or squash(code[args[0][0]:args[0][1]]) != "Dispatchers.IO":
            continue
        close = match_bracket(code, m.end() - 1)
        if close < 0:
            continue
        i = close + 1
        while i < len(code) and code[i] in " \t\r\n":
            i += 1
        if i >= len(code) or code[i] != "{":
            continue  # no trailing lambda ⇒ no IO body to be inside of
        end = match_bracket(code, i)
        if end < 0:
            continue
        out.append((m.start(), end + 1))
    return out


def check_arm4(screen_code):
    """Every listLocal( call must sit INSIDE a withContext(Dispatchers.IO) block.

    batch-5 (witness 01-5): the old criterion only required `Dispatchers.IO` to
    appear somewhere on the same line, so `listLocal(context).also {
    Dispatchers.IO }` — a discarding lambda that does not dispatch anything —
    read exactly like a properly wrapped call while the directory walk ran on
    the main thread. Containment, not token co-occurrence, is the contract.
    """
    if screen_code is None:
        return "BackupSettingsScreen.kt not found"
    spans = io_scope_spans(screen_code)
    bad = []
    for m in LIST_LOCAL.finditer(screen_code):
        if not any(s <= m.start() < e for s, e in spans):
            bad.append(screen_code.count("\n", 0, m.start()) + 1)
    if bad:
        return (
            "main-thread local auto-backup listing: listLocal( call(s) outside a "
            "`withContext(Dispatchers.IO) { … }` block — the `Dispatchers.IO` token appearing "
            "on the same line (e.g. inside a discarding `.also { Dispatchers.IO }`) does not "
            f"move the call off the main thread: lines {bad}"
        )
    return None


FLAG_DECL = re.compile(r"\bvar\s+webDavUploadPending\b")
FLAG_WRITE = re.compile(r"(?<![\w])webDavUploadPending\s*=\s*(true|false)\b")


def check_arm5(screen_code):
    """Secret-warning routing flag must survive to the point where it is read.

    A leaked `webDavUploadPending = true` (dialog dismissed via back press /
    tap outside, or the clear getting overwritten right away) re-routes the NEXT
    local export into a WebDAV upload — silently shipping a secrets-included
    payload to the remote server.

    batch-5 (witness 01-6): counting `webDavUploadPending = false` occurrences
    was satisfied by `= false; = true;` in the same lambda, because only the
    LAST write to a field survives. Clears must now be reachable and must be
    the last write of their own block.
    """
    if screen_code is None:
        # Drift-probe / mini-tree fixture without the screen: nothing to pin.
        return None
    if not FLAG_DECL.search(screen_code):
        return (
            "BackupSettingsScreen must declare `var webDavUploadPending` — the "
            "secret-warning routing flag vanished (anchor drift). Re-point this "
            "arm at the renamed flag; do not delete the arm."
        )
    writes = [(m.start(), m.group(1)) for m in FLAG_WRITE.finditer(screen_code)]
    clears = [pos for pos, val in writes if val == "false" and path_feasible(screen_code, pos)]
    if len(clears) < 3:
        return (
            "webDavUploadPending must be cleared on >= 3 REACHABLE sites (local-export "
            "entry / runExport consumption / dialog onDismissRequest) — a stale "
            "`true` redirects a later local export to a remote WebDAV upload "
            f"(secrets included): found {len(clears)}"
        )
    by_block = {}
    for pos, val in writes:
        by_block.setdefault(block_span(screen_code, pos), []).append((pos, val))
    for (b_start, b_end), sites in sorted(by_block.items()):
        sites.sort()
        if any(val == "false" for _p, val in sites) and sites[-1][1] == "true":
            line = screen_code.count("\n", 0, b_start) + 1
            return (
                "the clear at block line %d is OVERWRITTEN by a later "
                "`webDavUploadPending = true` in the SAME block (offset %d) — only the last "
                "write survives, so a confirmed LOCAL export reads a stale `true` and ships "
                "the secrets-included payload to WebDAV instead of the SAF picker"
                % (line, sites[-1][0])
            )
    return None


def fetch_handler_region(screen_code):
    """The `onFetch` handler body, located by brace matching rather than by a
    whole-line pattern (any of `onFetch = {`, `fun onFetch(...) {` matches).

    Every `onFetch` token whose block opens is considered; the candidate that
    carries the post-fetch list refresh wins. Returns None when no such region
    exists — arm6 reports that as anchor drift instead of passing on a zero
    count.
    """
    for m in re.finditer(r"\bonFetch\b", screen_code):
        region = braced_from(screen_code, m.start())
        if region and "openAutoRemoteList(" in region:
            return region
    return None


LIVENESS_PAT = re.compile(r"\b(?:isActive|isCancelled)\b|\bensureActive\(")


def check_arm6(screen_code):
    """Fetch-to-local rides the shared busy gate; its tail refresh is liveness-guarded.

    deep-audit-1040 B-08 (P1). Three decisions, each pinned on the handler
    region (brace-located) instead of on a single line:

      1. the fetch path CLAIMS the shared `operationBusy` gate (dismissing the
         dialog must not leave "Run Now" / "Restore" / "Export" clickable while
         a full payload is being written into `filesDir/backup-autos`);
      2. it RELEASES the gate inside the `finally` of the same region, so the
         release sits on the exit path rather than on the happy path only;
      3. the tail `openAutoRemoteList()` runs only while the coroutine is still
         alive (`isActive` / `ensureActive()`), otherwise a torn-down screen
         re-arms its spinner from a dead scope.

    Fail-closed: an unlocatable handler region, or one that lost the refresh
    call, is RED — re-point the arm rather than deleting it.
    """
    if screen_code is None:
        return "BackupSettingsScreen.kt not found"
    if not re.search(r"\bonFetch\b", screen_code):
        return (
            "the onFetch fetch-to-local handler vanished (anchor drift) — "
            "re-point this arm at the renamed handler; do not delete it"
        )
    region = fetch_handler_region(screen_code)
    if region is None:
        return (
            "could not locate the onFetch handler region (no block carrying "
            "openAutoRemoteList() — anchor drift). Re-point this arm; a zero "
            "count must not read as a pass."
        )
    if "operationBusy = true" not in region:
        return (
            "fetch-to-local must claim the shared operationBusy gate (the "
            "payload is written + rotated into filesDir/backup-autos while "
            "Run Now / Restore / Export stay clickable otherwise)"
        )
    finally_i = region.find("finally")
    if finally_i < 0:
        return (
            "fetch-to-local must release the shared operationBusy gate on its "
            "exit path (no `finally` in the handler region)"
        )
    finally_region = braced_from(region, finally_i) or ""
    # wide-1024 (order axis): a release that is followed by another claim is
    # not a release — the LAST write in the exit path is the one that survives.
    writes = re.findall(r"operationBusy\s*=\s*(true|false)", finally_region)
    if not writes:
        return (
            "fetch-to-local must release the shared operationBusy gate inside "
            "its `finally` (a release on the happy path only leaks the gate)"
        )
    if writes[-1] != "false":
        return (
            "fetch-to-local's `finally` ends with `operationBusy = %s` — the last write "
            "on the exit path wins, so the shared gate stays CLAIMED after the fetch "
            "finishes and Run Now / Restore / Export are dead until the screen is "
            "recreated (a release followed by a re-claim is not a release)" % writes[-1]
        )
    refresh_i = region.find("openAutoRemoteList(")
    window = region[max(0, refresh_i - 200):refresh_i]
    if not LIVENESS_PAT.search(window):
        return (
            "the post-fetch openAutoRemoteList() must be liveness-guarded "
            "(isActive / ensureActive()) — a refresh fired from an already "
            "cancelled scope re-arms the dialog spinner on a dead composition"
        )
    return None


def check_all(root):
    try:
        auto, _auto_lit = read_views(root, AUTO_MGR_REL)
        sync, sync_lit = read_views(root, SYNC_REL)
        screen, _screen_lit = read_views(root, SCREEN_REL)
    except KotlinLexError as exc:
        return ["source is not lexable (%s) — fail-closed" % exc]
    failures = []
    for name, err in (
        ("arm1", check_arm1(auto)),
        ("arm2", check_arm2(sync, sync_lit)),
        ("arm3", check_arm3(screen)),
        ("arm4", check_arm4(screen)),
        ("arm5", check_arm5(screen)),
        ("arm6", check_arm6(screen)),
    ):
        if err:
            failures.append(f"{name}: {err}")
    return failures


# --- compliant / violation fixture snippets (code-level, not comments) ---

COMPLIANT_AUTO = """package com.rikkaminis.app.backup

object AutoBackupManager {
    private suspend fun runLocked(app: MinisApp) {
        val local = File(app.filesDir, "x").apply { mkdirs() }
        val unreadable = local.bufferedWriter().use { writer ->
            ConfigBackup.exportToWriter(
                providerRepo = app.providerRepository,
                includeSecrets = false,
                writer = writer,
            )
        }
        rotateLocalAutoBackups(app)
        WebDavSync.backupAuto(cfg, local)
    }
}
"""

COMPLIANT_SYNC = """package com.rikkaminis.app.backup

import java.io.File

object WebDavSync {
    fun backupAuto(config: WebDavConfig, payload: String, client: OkHttpClient = d()): String =
        putAutoBackup(config, payload.toByteArray(Charsets.UTF_8), client)

    fun backupAuto(
        config: WebDavConfig,
        file: File,
        client: OkHttpClient = d(),
    ): String = putAutoBackup(config, file.inputStream().use { it.readBytes() }, client)

    private fun putAutoBackup(config: WebDavConfig, bytes: ByteArray, client: OkHttpClient): String = "x"

    fun sanitizeAutoBackupStamp(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains('/') || trimmed.contains('\\\\')) return null
        if (trimmed.contains("..")) return null
        return trimmed
    }
}
"""

COMPLIANT_SCREEN = """package com.rikkaminis.app.ui.settings

@Composable
fun BackupSettingsScreen(context: Context) {
    var showSecretWarning by remember { mutableStateOf(false) }
    var webDavUploadPending by remember { mutableStateOf(false) }
    var autoBackupFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(Unit) {
        autoBackupFiles = withContext(Dispatchers.IO) { AutoBackupManager.listLocal(context) }
    }
    SettingsRow(
        onClick = if (operationBusy) null else ({ webDavUploadPending = false; showSecretWarning = true }),
    )
    SettingsRow(
        onClick = if (webDavConfig != null && !operationBusy) {
            { webDavUploadPending = true; showSecretWarning = true }
        } else {
            null
        },
    )
    if (showSecretWarning) {
        val runExport: (Boolean) -> Unit = runExport@{ withSecrets ->
            operationBusy = true
            showSecretWarning = false
            val toWebDav = webDavUploadPending
            webDavUploadPending = false
            if (toWebDav) uploadRemote() else exportWithSecrets = withSecrets
        }
        AlertDialog(
            onDismissRequest = {
                showSecretWarning = false
                webDavUploadPending = false
            },
        )
    }
    fun onFetch(entry: WebDavSync.AutoBackupEntry) {
        if (!operationBusy) {
            operationBusy = true
            autoRemoteLoading = true
            try {
                val stamp = WebDavSync.sanitizeAutoBackupStamp(entry.item.displayName) ?: return
                val local = File(dir, AutoBackupManager.LOCAL_FILE_PREFIX + stamp + ".json")
                withContext(Dispatchers.IO) { local.writeText(json) }
                withContext(Dispatchers.IO) { AutoBackupManager.rotateLocalAutoBackups(context) }
                autoBackupFiles = withContext(Dispatchers.IO) { AutoBackupManager.listLocal(context) }
            } finally {
                autoRemoteLoading = false
                operationBusy = false
            }
            if (scope.isActive) openAutoRemoteList()
        }
    }
}
"""

VIOLATION_EXPORT_STRING = COMPLIANT_AUTO.replace(
    "            ConfigBackup.exportToWriter(\n"
    "                providerRepo = app.providerRepository,\n"
    "                includeSecrets = false,\n"
    "                writer = writer,\n"
    "            )",
    "            ConfigBackup.export(\n"
    "                providerRepo = app.providerRepository,\n"
    "                includeSecrets = false,\n"
    "            )",
)

VIOLATION_INLINE_ROTATION = COMPLIANT_AUTO.replace(
    "        rotateLocalAutoBackups(app)",
    "        listLocal(app).drop(3).forEach { runCatching { it.delete() } }",
)

VIOLATION_NO_FILE_OVERLOAD = COMPLIANT_SYNC.replace(
    "    fun backupAuto(\n"
    "        config: WebDavConfig,\n"
    "        file: File,\n"
    "        client: OkHttpClient = d(),\n"
    "    ): String = putAutoBackup(config, file.inputStream().use { it.readBytes() }, client)\n",
    "",
)

SANITIZER_FN = """    fun sanitizeAutoBackupStamp(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains('/') || trimmed.contains('\\\\')) return null
        if (trimmed.contains("..")) return null
        return trimmed
    }
"""

VIOLATION_NO_SANITIZER = COMPLIANT_SYNC.replace(SANITIZER_FN, "")

VIOLATION_FETCH_NO_SANITIZE = COMPLIANT_SCREEN.replace(
    "val stamp = WebDavSync.sanitizeAutoBackupStamp(entry.item.displayName) ?: return",
    "val stamp = entry.item.displayName.removePrefix(\"rikkaminis-backup-auto-\")",
)

VIOLATION_FETCH_NO_ROTATION = COMPLIANT_SCREEN.replace(
    "                withContext(Dispatchers.IO) { AutoBackupManager.rotateLocalAutoBackups(context) }\n",
    "",
)

VIOLATION_MAIN_THREAD_REMEMBER = COMPLIANT_SCREEN.replace(
    "    var autoBackupFiles by remember { mutableStateOf<List<File>>(emptyList()) }\n"
    "    LaunchedEffect(Unit) {\n"
    "        autoBackupFiles = withContext(Dispatchers.IO) { AutoBackupManager.listLocal(context) }\n"
    "    }",
    "    var autoBackupFiles by remember { mutableStateOf(AutoBackupManager.listLocal(context)) }",
)

VIOLATION_MAIN_THREAD_REFRESH = COMPLIANT_SCREEN.replace(
    "autoBackupFiles = withContext(Dispatchers.IO) { AutoBackupManager.listLocal(context) }",
    "autoBackupFiles = withContext(Dispatchers.Main) { AutoBackupManager.listLocal(context) }",
)

# arm5 (deep-audit-1027 ISSUE-03) — the secret-warning routing flag.
VIOLATION_NO_ENTRY_CLEAR = COMPLIANT_SCREEN.replace(
    "({ webDavUploadPending = false; showSecretWarning = true })",
    "({ showSecretWarning = true })",
)

VIOLATION_NO_DISMISS_CLEAR = COMPLIANT_SCREEN.replace(
    "            onDismissRequest = {\n"
    "                showSecretWarning = false\n"
    "                webDavUploadPending = false\n"
    "            },",
    "            onDismissRequest = { showSecretWarning = false },",
)

VIOLATION_FLAG_RENAMED = COMPLIANT_SCREEN.replace(
    "webDavUploadPending",
    "webDavUploadQueued",
)

# arm6 (deep-audit-1040 B-08) — fetch-to-local gate + tail liveness.
VIOLATION_FETCH_NO_BUSY_CLAIM = COMPLIANT_SCREEN.replace(
    "            operationBusy = true\n            autoRemoteLoading = true\n",
    "            autoRemoteLoading = true\n",
)

VIOLATION_FETCH_NO_BUSY_RELEASE = COMPLIANT_SCREEN.replace(
    "                autoRemoteLoading = false\n                operationBusy = false\n",
    "                autoRemoteLoading = false\n",
)

VIOLATION_FETCH_UNGUARDED_REFRESH = COMPLIANT_SCREEN.replace(
    "            if (scope.isActive) openAutoRemoteList()",
    "            openAutoRemoteList()",
)

VIOLATION_FETCH_HANDLER_RENAMED = COMPLIANT_SCREEN.replace(
    "fun onFetch(entry: WebDavSync.AutoBackupEntry) {",
    "fun pullRemote(entry: WebDavSync.AutoBackupEntry) {",
)

# ── wide-1024 witnesses (mechanically reproduced on the real tree: pre-fix
# rc=0, post-fix rc=1) ────────────────────────────────────────────────────

# W1 (dataflow): the call and the helper both survive — the call can never run,
# so the rotation is dead and filesDir/backup-autos grows unbounded.
VIOLATION_ROTATION_DEAD_GUARD = COMPLIANT_AUTO.replace(
    "        rotateLocalAutoBackups(app)",
    "        if (false) rotateLocalAutoBackups(app)",
)

# W2 (dataflow, spelling→effect): the sanitizer's rejection is dropped and the
# path is built from the raw, remote-controlled display name.
VIOLATION_FETCH_SANITIZER_DEAD = COMPLIANT_SCREEN.replace(
    '                val stamp = WebDavSync.sanitizeAutoBackupStamp(entry.item.displayName) ?: return\n'
    '                val local = File(dir, AutoBackupManager.LOCAL_FILE_PREFIX + stamp + ".json")',
    '                val stamp = WebDavSync.sanitizeAutoBackupStamp(entry.item.displayName)\n'
    '                val local = File(dir, AutoBackupManager.LOCAL_FILE_PREFIX'
    ' + entry.item.displayName + ".json")',
)

# W2b: the guard is kept, the writer ignores the guarded value.
VIOLATION_FETCH_RAW_PATH = COMPLIANT_SCREEN.replace(
    'AutoBackupManager.LOCAL_FILE_PREFIX + stamp + ".json"',
    'AutoBackupManager.LOCAL_FILE_PREFIX + entry.item.displayName + ".json"',
)

# W3 (order): the release is followed by a re-claim — last write wins, the gate
# is left claimed.
VIOLATION_FETCH_GATE_RECLAIM = COMPLIANT_SCREEN.replace(
    '                autoRemoteLoading = false\n                operationBusy = false\n',
    '                autoRemoteLoading = false\n                operationBusy = false\n'
    '                operationBusy = true\n',
)


# A correctly WRAPPED sanitize call is a legal spelling: the arms must anchor on
# structure (call/argument identity), not on a single-line style.
COMPLIANT_SANITIZER_WRAPPED = COMPLIANT_SCREEN.replace(
    'val stamp = WebDavSync.sanitizeAutoBackupStamp(entry.item.displayName) ?: return',
    'val stamp = WebDavSync.sanitizeAutoBackupStamp(\n'
    '                    entry.item.displayName,\n'
    '                ) ?: return',
)

# ── batch-5 witnesses (each one passed this gate unchanged) ───────────────

# 01-1 (parser-death): a `}` INSIDE a log string closed the quote-blind brace
# counter, truncating runLocked's body — the banned String export after the cut
# was never inspected and the rotation call "found" inside the message text.
VIOLATION_ROTATION_STRING_ESCAPE = COMPLIANT_AUTO.replace(
    "        rotateLocalAutoBackups(app)",
    '        Log.w(TAG, "auto backup wrote rotateLocalAutoBackups(app) } unreadable field(s)")\n'
    "        val leakedFullString = ConfigBackup.export(\n"
    "            providerRepo = app.providerRepository,\n"
    "            includeSecrets = false,\n"
    "        )",
)

# 01-2 (family-hole): the rotation keeps its exact text and is gated on a
# constant comparison the literal whitelist (`false` / `!true` / `0 > 1`) never
# knew.
VIOLATION_ROTATION_CONST_COMPARE = COMPLIANT_AUTO.replace(
    "        rotateLocalAutoBackups(app)",
    "        if (1 == 2) rotateLocalAutoBackups(app)",
)

# 01-3 (anchor): the sanitizer keeps its signature and hands the raw name back,
# so "does WebDavSync define sanitizeAutoBackupStamp(" stays true.
VIOLATION_SANITIZER_HOLLOW = COMPLIANT_SYNC.replace(
    SANITIZER_FN,
    """    fun sanitizeAutoBackupStamp(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isNotEmpty()) return trimmed
        return null
    }
""",
)

# 01-4 (dataflow): the sanitized token appears in the expression while the RAW,
# remote-controlled name is what gets evaluated — the classic
# `.also { stamp }` (discarding scope) disguise.
VIOLATION_FETCH_ALSO_DISCARD = COMPLIANT_SCREEN.replace(
    'val local = File(dir, AutoBackupManager.LOCAL_FILE_PREFIX + stamp + ".json")',
    'val local = File(dir, (AutoBackupManager.LOCAL_FILE_PREFIX'
    ' + entry.item.displayName + ".json").also { stamp })',
)

# 01-5 (dataflow): `Dispatchers.IO` appears on the line, but inside a
# discarding lambda — the directory walk still runs on the main thread.
VIOLATION_MAIN_THREAD_ALSO = COMPLIANT_SCREEN.replace(
    "autoBackupFiles = withContext(Dispatchers.IO) { AutoBackupManager.listLocal(context) }",
    "autoBackupFiles = AutoBackupManager.listLocal(context).also { Dispatchers.IO }",
)

# 01-6 (order): the clear is undone in the SAME lambda — counts stay intact,
# only the last write survives, and a later local export ships the secrets.
VIOLATION_ENTRY_OVERWRITE = COMPLIANT_SCREEN.replace(
    "({ webDavUploadPending = false; showSecretWarning = true })",
    "({ webDavUploadPending = false; webDavUploadPending = true; showSecretWarning = true })",
)


def write_tree(tmp, auto=None, sync=None, screen=None):
    for rel, content in (
        (AUTO_MGR_REL, auto),
        (SYNC_REL, sync),
        (SCREEN_REL, screen),
    ):
        if content is None:
            continue
        path = os.path.join(tmp, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write(content)


def run_fixture(name, expect_ok, auto=None, sync=None, screen=None, contains=None):
    """`contains` pins WHICH criterion fired — a dirty fixture that goes red on
    an unrelated arm must not count as evidence for the arm under test."""
    with tempfile.TemporaryDirectory() as tmp:
        write_tree(
            tmp,
            auto=auto if auto is not None else COMPLIANT_AUTO,
            sync=sync if sync is not None else COMPLIANT_SYNC,
            screen=screen if screen is not None else COMPLIANT_SCREEN,
        )
        failures = check_all(tmp)
        ok = (not failures) == expect_ok
        if ok and contains is not None:
            ok = any(contains in f for f in failures)
        status = "PASS" if ok else "FAIL"
        print(f"  [{status}] fixture {name}: failures={failures}")
        return ok


def selftest():
    print("backup_streaming_export_check selftest:")
    ok = True
    ok &= run_fixture("compliant", expect_ok=True)
    ok &= run_fixture("violation-export-string", expect_ok=False, auto=VIOLATION_EXPORT_STRING)
    ok &= run_fixture("violation-inline-rotation", expect_ok=False, auto=VIOLATION_INLINE_ROTATION)
    ok &= run_fixture("violation-no-file-overload", expect_ok=False, sync=VIOLATION_NO_FILE_OVERLOAD)
    ok &= run_fixture("violation-no-sanitizer", expect_ok=False, sync=VIOLATION_NO_SANITIZER)
    ok &= run_fixture("violation-fetch-no-sanitize", expect_ok=False, screen=VIOLATION_FETCH_NO_SANITIZE)
    ok &= run_fixture("violation-fetch-no-rotation", expect_ok=False, screen=VIOLATION_FETCH_NO_ROTATION)
    ok &= run_fixture("violation-main-thread-remember", expect_ok=False, screen=VIOLATION_MAIN_THREAD_REMEMBER)
    ok &= run_fixture("violation-main-thread-refresh", expect_ok=False, screen=VIOLATION_MAIN_THREAD_REFRESH)
    ok &= run_fixture("violation-entry-no-flag-clear", expect_ok=False, screen=VIOLATION_NO_ENTRY_CLEAR)
    ok &= run_fixture("violation-dismiss-no-flag-clear", expect_ok=False, screen=VIOLATION_NO_DISMISS_CLEAR)
    ok &= run_fixture("violation-flag-renamed-anchor-drift", expect_ok=False, screen=VIOLATION_FLAG_RENAMED)
    ok &= run_fixture("violation-fetch-no-busy-claim", expect_ok=False, screen=VIOLATION_FETCH_NO_BUSY_CLAIM)
    ok &= run_fixture("violation-fetch-no-busy-release", expect_ok=False, screen=VIOLATION_FETCH_NO_BUSY_RELEASE)
    ok &= run_fixture("violation-fetch-unguarded-refresh", expect_ok=False, screen=VIOLATION_FETCH_UNGUARDED_REFRESH)
    ok &= run_fixture("violation-fetch-handler-renamed", expect_ok=False, screen=VIOLATION_FETCH_HANDLER_RENAMED)
    # wide-1024: each witness must trip the arm it was written against.
    ok &= run_fixture("violation-rotation-dead-guard", expect_ok=False,
                      auto=VIOLATION_ROTATION_DEAD_GUARD, contains="DEAD-GUARDED")
    ok &= run_fixture("violation-fetch-sanitizer-dead", expect_ok=False,
                      screen=VIOLATION_FETCH_SANITIZER_DEAD, contains="rejection is IGNORED")
    ok &= run_fixture("violation-fetch-raw-path", expect_ok=False,
                      screen=VIOLATION_FETCH_RAW_PATH, contains="is decorative")
    ok &= run_fixture("violation-fetch-gate-reclaim", expect_ok=False,
                      screen=VIOLATION_FETCH_GATE_RECLAIM, contains="last write")
    # batch-5: each witness must trip the arm it was written against.
    ok &= run_fixture("violation-rotation-string-escape", expect_ok=False,
                      auto=VIOLATION_ROTATION_STRING_ESCAPE, contains="must not assemble")
    ok &= run_fixture("violation-rotation-const-compare", expect_ok=False,
                      auto=VIOLATION_ROTATION_CONST_COMPARE, contains="DEAD-GUARDED")
    ok &= run_fixture("violation-sanitizer-hollow", expect_ok=False,
                      sync=VIOLATION_SANITIZER_HOLLOW, contains="no longer refuses")
    ok &= run_fixture("violation-fetch-also-discard", expect_ok=False,
                      screen=VIOLATION_FETCH_ALSO_DISCARD, contains="is decorative")
    ok &= run_fixture("violation-main-thread-also", expect_ok=False,
                      screen=VIOLATION_MAIN_THREAD_ALSO, contains="outside a `withContext(Dispatchers.IO)")
    ok &= run_fixture("violation-entry-overwrite", expect_ok=False,
                      screen=VIOLATION_ENTRY_OVERWRITE, contains="OVERWRITTEN by a later")
    # a wrapped-but-correct call / a wrapped File build is a legal spelling
    ok &= run_fixture("compliant-sanitizer-wrapped", expect_ok=True,
                      screen=COMPLIANT_SANITIZER_WRAPPED)
    print("BACKUP_STREAMING_EXPORT_SELFTEST=" + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


def main():
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    if len(sys.argv) < 2:
        print("usage: python3 backup_streaming_export_check.py <repo_root>", file=sys.stderr)
        sys.exit(2)
    root = sys.argv[1]
    failures = check_all(root)
    if failures:
        for f in failures:
            print("FAIL:", f)
        sys.exit(1)
    print("backup_streaming_export_check: OK (arm1 + arm2 + arm3 + arm4 + arm5 + arm6 green)")


if __name__ == "__main__":
    main()
