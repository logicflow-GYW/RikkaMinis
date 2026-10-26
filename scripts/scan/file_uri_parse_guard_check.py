#!/usr/bin/env python3
"""file_uri_parse_guard_check — deep-audit-1023 FIX-H (ISSUE-08, P1) gate.

Local file:// URLs are NOT generic parsed URIs at the consumption sites: the
host constructs them by plain concatenation (`"file://" + absolutePath`), so
`Uri.parse(url).path` REParses the string as a generic URI and re-applies
delimiter semantics that the construction side never encoded for:

    file:///data/x/report#1.html   -> .path == "/data/x/report"   (fragment cut)
    file:///data/x/v1?draft.html   -> .path == "/data/x/v1"       (query cut)

The file is exactly `report#1.html` / `v1?draft.html`; exists() is false, the
sheet's "open in browser" resolves to null and the only trace is one warning
log line (silent no-op). Markdown media hit the same cut inside
resolveMdMediaFile, whose own header comment promises `#` survives — the
Uri.parse call cancelled the contract one line below it.

The fix routes every `file://` resolution through the single URI-path exit
(ui/chat/UriPathDecode.kt decodeUriPath: decodes %XX only, `+` is a literal,
malformed `%` verbatim, never throws, no delimiter semantics), and makes
fromAssetLoaderUrl consume the asset-loader URL symmetrically with how
toAssetLoaderUrl produces it: the encoder percent-encodes the whole name
segment (`#` -> %23, `?` -> %3F), so a literal `#`/`?` in a reported asset URL
is always a fragment / query and is cut BEFORE decoding — matching the
androidx WebViewAssetLoader, which resolves the path only.

Criteria (all must hold):
  F1a WebPreviewBottomSheet.openExternalFromSheet resolves the `file://` path
      via `decodeUriPath(url.removePrefix("file://"))` AND the decoded value
      survives to the file construction (no `.substringBefore(…)` chained onto
      the call, no `.let { … }` that ignores its receiver, no rewrite at the
      variable's consumption site);
  F1b no `Uri.parse(...).path` anywhere in WebPreviewBottomSheet.kt;
  F2a StreamingMarkdownText.resolveMdMediaFile same contract as F1a;
  F2b no `Uri.parse(...).path` anywhere in StreamingMarkdownText.kt;
  F3  WebViewHolder.fromAssetLoaderUrl cuts `#` and `?` INSIDE the
      decodeUriPath(…) argument (before decoding) and never after the call —
      round-trip symmetry with toAssetLoaderUrl;
  F4  ui/** sweep: `Uri.parse(...).path` is banned outside the allowlist —
      allowlist: ui/preview/WebPreviewShortcut.kt, exactly 1 occurrence
      (same-family site OUTSIDE this branch's file set, radius discipline:
      registered in backlog §1023-H; when it is fixed, REMOVE the allowlist
      entry — a stale entry is RED so the exemption cannot outlive the debt).
  F5  one media decision point (extension table / classifier / `#`-cut scope /
      absolute-path guard on every minis:// decode).

Batch-7 witness wave — the criterion shape moved from "the token is present"
to "the decision behind the token is live":
  * the decoded path's value is tracked through `.let { … }` value scopes and
    to its consumption sites, instead of only the 80 chars after the call;
  * `Uri.parse(...).path` is counted with paren matching, so a nested call
    (`Uri.parse(stripped.trim()).path`) no longer slips through `[^()]*`;
  * F3 compares the cut against the decoder's ARGUMENT (order), instead of
    asking whether both substrings exist somewhere in the body;
  * dead guards are decided by constant folding (`const_bool`), so `true ||`,
    `|| true`, `false &&` and `&& false` are all the same red;
  * every source is read through the shared lexer (`mask_code` /
    `mask_comments`), so a raw string whose content contains `" /* "` can no
    longer open a fake block comment and hide the code below it.

Usage: python3 file_uri_parse_guard_check.py [ROOT]   (ROOT = repo root)
       python3 file_uri_parse_guard_check.py --selftest
Exit:  0 = gate green, 1 = violations (or selftest failures).
"""
import os
import re
import sys

from _kotlin_ast import (
    const_bool,
    function_body,
    match_bracket,
    split_top,
    strip_parens,
)
from _kotlin_lexer import KotlinLexError, mask_code, mask_comments
from _kotlin_valueflow import (
    call_args,
    contains_ident,
    expr_end,
    scope_calls,
)

SHEET_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/WebPreviewBottomSheet.kt"
MD_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/StreamingMarkdownText.kt"
HOLDER_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/WebViewHolder.kt"
UI_ROOT_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui"
# [dv22/B-06] the markdown media pipeline: one classifier, one parser, the
# gallery collector and the image fetcher.
MEDIA_MODEL_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/MarkdownBlockModel.kt"
MEDIA_PARSER_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/markdown/MarkdownParser.kt"
CHAT_SCREEN_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt"

# relpath (repo-root-relative, forward slashes) -> max allowed occurrences
ALLOWLIST = {
    "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/WebPreviewShortcut.kt": 1,
}
ALLOWLIST_REASON = (
    "ui/preview/WebPreviewShortcut.kt — same-family `Uri.parse(url).path` site "
    "outside fix/da1023-file-uri-decode's file set (radius discipline); "
    "registered in backlog §1023-H. Remove this entry in the same branch "
    "that fixes it."
)

# `Uri.parse` followed by `.path` — counted with paren matching (see
# count_uri_parse_path). A nested argument (`Uri.parse(x.trim()).path`) used to
# walk through the old `[^()]*` regex.
URI_PARSE_RE = re.compile(r"\bUri\s*\.\s*parse\s*\(")
# Calls that REWRITE the value they are chained onto. `decodeUriPath(...)`
# followed by one of these hands a truncated path to `File(…)`.
POST_DECODE_POISON = (
    "substringBefore", "substringAfter", "substring", "replace", "split",
    "trim", "trimEnd", "trimStart", "removePrefix", "removeSuffix",
)
HTTP_TEST_RE = re.compile(r'startsWith\(\s*"https?://')
FILE_CUTS = ("substringBefore('#')", "substringBefore('?')")
VALUE_SCOPES = ("let", "run", "with")


# ------------------------------------------------------------- primitives ---
def _views(src):
    """(structure view, literal view). Both are length-preserving projections of
    the SAME source, so a position found in one is valid in the other.

    structure: comments + string/char literal interiors blanked (brace/paren
               analysis, `Uri.parse` counting);
    literal:   comments blanked, literal contents kept (reading `'#'`, `"mp4"`,
               `"http://"`). A `//` inside "file://" survives, which is exactly
               what the old hand-written strip_comments tried to do — and
               failed at on triple-quoted raw strings.
    """
    return mask_code(src), mask_comments(src)


def _poison_chain(text, pos):
    """Name of the rewriting method chained right after `pos`, or ''."""
    i = pos
    n = len(text)
    while i < n and text[i] in " \t\r\n":
        i += 1
    if i < n and text[i] == "?":
        i += 1
        while i < n and text[i] in " \t\r\n":
            i += 1
    if i >= n or text[i] != ".":
        return ""
    m = re.match(r"\.\s*([A-Za-z_]\w*)", text[i:])
    if not m:
        return ""
    return m.group(1) if m.group(1) in POST_DECODE_POISON else ""


def count_uri_parse_path(code):
    """Occurrences of `Uri.parse(…).path` — paren-aware, so nested arguments
    are seen. Unbalanced calls count as violations (fail-closed)."""
    n = 0
    for m in URI_PARSE_RE.finditer(code):
        close = match_bracket(code, m.end() - 1)
        if close < 0:
            n += 1
            continue
        if re.match(r"\s*\.\s*path(?![\w])", code[close + 1:close + 60]):
            n += 1
    return n


def _body_text(text, name):
    """Body of `fun <name>(…)` (block contents or expression body), or ''."""
    fb = function_body(text, name)
    return text[fb[0]:fb[1]] if fb else ""


def decode_flow_problem(scope_code, scope_lit, arg_marker):
    """The value produced by `decodeUriPath(<arg containing arg_marker>)` must
    reach the File construction unchanged.

    `arg_marker` is the literal the argument must remove (`removePrefix("file://")`)
    — it is what pins the criterion to the `file://` resolution site instead of
    any decoder call in the file.
    """
    calls = []
    for m in re.finditer(r"\bdecodeUriPath\s*\(", scope_code):
        open_pos = m.end() - 1
        close = match_bracket(scope_code, open_pos)
        if close < 0:
            return "unbalanced parentheses after `decodeUriPath(` (fail-closed)"
        args = call_args(scope_code, open_pos)
        arg_text = "".join(scope_lit[a:b] for a, b in args)
        if arg_marker not in arg_text:
            continue
        calls.append((m.start(), open_pos, close))
    if len(calls) != 1:
        return ("the `file://` path must be resolved exactly once through "
                "decodeUriPath(<…>%s) (found %d such call(s))" % (arg_marker, len(calls)))
    start, open_pos, close = calls[0]

    # (1) a rewrite chained straight onto the call
    name = _poison_chain(scope_code, close + 1)
    if name:
        return ("the decoded path is rewritten by `.%s…` — the resolver's value must be "
                "used as-is" % name)

    # (2) a value-style scope whose lambda ignores the receiver: the value that
    # flows on is the lambda's result, not the decoded path.
    chain_end = expr_end(scope_code, start)
    chain = scope_code[start:chain_end]
    for sname, s_start, s_open, s_end in scope_calls(chain):
        if sname not in VALUE_SCOPES:
            continue
        if s_start <= close - start:
            continue
        lam = chain[s_open + 1:s_end - 1]
        named = re.match(r"\s*([A-Za-z_]\w*)\s*->", lam)
        param = named.group(1) if named else "it"
        if contains_ident(lam, param) or contains_ident(lam, "this"):
            continue
        return ("the decoded path is passed into `.%s { … }` whose body never reads its "
                "receiver — the value that flows on is `%s`, not the decoded path"
                % (sname, lam.strip()[:60]))

    # (3) the local that holds the result must not be re-cut where it is read
    bind = re.search(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]*)?=\s*$",
                     scope_code[max(0, start - 200):start])
    if bind:
        local = bind.group(1)
        for rm in re.finditer(r"(?<![\w.`])" + re.escape(local) + r"(?![\w`])", scope_code):
            if rm.start() < start:
                continue
            pname = _poison_chain(scope_code, rm.end())
            if pname:
                return ("`%s` (the decoded path) is rewritten by `.%s…` where it is read — "
                        "the truncation merely moved off the call chain onto the variable"
                        % (local, pname))
    return ""


def holder_cut_problem(holder_lit):
    """`fromAssetLoaderUrl` must cut `#`/`?` INSIDE the decoder's argument."""
    body = _body_text(holder_lit, "fromAssetLoaderUrl")
    if not body:
        return "fromAssetLoaderUrl not found (fail-closed)"
    calls = list(re.finditer(r"\bdecodeUriPath\s*\(", body))
    if len(calls) != 1:
        return ("fromAssetLoaderUrl must decode exactly once through decodeUriPath (found %d)"
                % len(calls))
    open_pos = calls[0].end() - 1
    close = match_bracket(body, open_pos)
    if close < 0:
        return "unbalanced decodeUriPath(…) (fail-closed)"
    args = call_args(body, open_pos)
    arg_text = "".join(body[a:b] for a, b in args)
    missing = [t for t in FILE_CUTS if t not in arg_text]
    if missing:
        return ("the fragment/query cut must run INSIDE decodeUriPath(…) — before decoding "
                "(missing %s); cutting after the decode truncates a legitimate %%23/%%3F"
                % ", ".join(missing))
    name = _poison_chain(body, close + 1)
    if name:
        return ("the path is rewritten by `.%s…` AFTER decodeUriPath — the cut must precede "
                "the decode (the encoder percent-encodes `#`/`?`)" % name)
    return ""


def _enclosing_if_cond(lit, pos):
    """Condition of the innermost `if (…)` that covers `pos`, else None."""
    from _kotlin_ast import enclosing_conditions

    conds = [c for kind, c in enclosing_conditions(lit, pos) if kind == "if"]
    return conds[-1] if conds else None


def _dead_guard(cond):
    """Reason a guard decides nothing, or '' — constant folding covers
    `true || x`, `x || true`, `false && x`, `x && false`, `!true`, `1 == 2`."""
    folded = const_bool(strip_parens(cond))
    if folded is None:
        return ""
    return "the guard folds to `%s`" % ("true" if folded else "false")


def analyse(sheet_src, md_src, holder_src, ui_srcs):
    """ui_srcs: {repo_root_relpath: RAW source} for EVERY .kt under ui/**.

    Returns the criteria list [(name, ok, detail, line)]. A source the lexer
    cannot read makes the whole gate red (fail-closed) instead of falling back
    to a raw-text scan.
    """
    try:
        sheet_code, sheet_lit = _views(sheet_src)
        md_code, md_lit = _views(md_src)
        holder_lit = _views(holder_src)[1]
        ui_views = {rel: _views(src) for rel, src in ui_srcs.items()}
    except KotlinLexError as exc:
        return [("F0 every scanned source lexes as Kotlin (fail-closed)", False,
                 "KotlinLexError: %s" % exc, None)]

    results = []

    # --- F1: openExternalFromSheet ------------------------------------
    sheet_fn_code = _body_text(sheet_code, "openExternalFromSheet")
    sheet_fn_lit = _body_text(sheet_lit, "openExternalFromSheet")
    if not sheet_fn_code:
        ok1, detail1 = False, "openExternalFromSheet not found (fail-closed)"
    else:
        problem = decode_flow_problem(sheet_fn_code, sheet_fn_lit, 'removePrefix("file://")')
        ok1, detail1 = (problem == ""), (problem or "decoded path used as-is")
    results.append(("F1a sheet file:// branch resolves via decodeUriPath(url.removePrefix(\"file://\"))",
                    ok1, detail1, None))
    n1 = count_uri_parse_path(sheet_code)
    results.append(("F1b no Uri.parse(...).path in WebPreviewBottomSheet.kt",
                    n1 == 0, "found %d occurrence(s)" % n1, None))

    # --- F2: resolveMdMediaFile ---------------------------------------
    md_fn_code = _body_text(md_code, "resolveMdMediaFile")
    md_fn_lit = _body_text(md_lit, "resolveMdMediaFile")
    if not md_fn_code:
        ok2, detail2 = False, "resolveMdMediaFile not found (fail-closed)"
    else:
        problem = decode_flow_problem(md_fn_code, md_fn_lit, 'removePrefix("file://")')
        ok2, detail2 = (problem == ""), (problem or "decoded path used as-is")
    results.append(("F2a markdown file:// branch resolves via decodeUriPath(stripped.removePrefix(\"file://\"))",
                    ok2, detail2, None))
    n2 = count_uri_parse_path(md_code)
    results.append(("F2b no Uri.parse(...).path in StreamingMarkdownText.kt",
                    n2 == 0, "found %d occurrence(s)" % n2, None))

    # --- F3: fromAssetLoaderUrl symmetric consumption ------------------
    problem3 = holder_cut_problem(holder_lit)
    results.append(("F3 fromAssetLoaderUrl cuts #/? before decodeUriPath",
                    problem3 == "", problem3 or "cut inside the decoder argument", None))

    # --- F4: ui/** sweep with allowlist --------------------------------
    for rel in sorted(ui_views):
        code = ui_views[rel][0]
        n = count_uri_parse_path(code)
        limit = ALLOWLIST.get(rel, 0)
        if rel in ALLOWLIST:
            if n != limit:
                results.append(
                    ("F4 allowlist exact for %s" % os.path.basename(rel), False,
                     ("allowlisted for %d occurrence(s) but found %d — %s"
                      % (limit, n,
                         "fix it and REMOVE the allowlist entry" if n == 0
                         else "raise the entry count consciously or fix the site")), None))
            else:
                results.append(("F4 allowlist exact for %s" % os.path.basename(rel), True,
                                 "exempted occurrence: %s" % ALLOWLIST_REASON, None))
        elif n > 0:
            results.append(
                ("F4 no Uri.parse(...).path in %s" % os.path.basename(rel), False,
                 "found %d occurrence(s) — route file:// paths through ui.chat.decodeUriPath" % n,
                 None))

    # --- F5: one media classifier [dv22/B-06] ---------------------------
    media_views = {rel: views[1] for rel, views in ui_views.items()}
    results.extend(check_media_single_source(media_views))
    return results


# ------------------------------------------------- media single source [dv22] --
MEDIA_KIND_FUNS = ("mediaKindOfUrl", "mediaPathOfUrl", "mediaFileNameOfUrl")


def _declares_media_set(lit):
    """A hand-written media extension set: a `setOf(` whose text mentions "mp4"."""
    for m in re.finditer(r"setOf\(", lit):
        if '"mp4"' in lit[m.start():m.start() + 240]:
            return True
    return False


def check_media_single_source(ui_lits):
    """[dv22/B-06] Markdown media classification must have ONE decision point.

    All text here is the LITERAL view (comments stripped, string/char contents
    kept): the criteria read `"mp4"`, `'#'`, `"http://"` and `startsWith("/")`,
    so masking the literals would make them unreadable. Brace/structure work
    uses the shared `function_body` / `match_bracket`, so a `}` inside a string
    cannot truncate a body.
    """
    results = []
    cls = ui_lits.get(MEDIA_MODEL_REL, "")
    par = ui_lits.get(MEDIA_PARSER_REL, "")
    cht = ui_lits.get(CHAT_SCREEN_REL, "")

    # F5a: exactly one media extension table, and it lives in the classifier.
    offenders = []
    for rel in sorted(ui_lits):
        if rel == MEDIA_MODEL_REL:
            continue
        if rel in (MEDIA_PARSER_REL,) and _declares_media_set(ui_lits[rel]):
            offenders.append(os.path.basename(rel))
    has_table = ('nativeVideoExts' in cls and '"mp4"' in cls
                 and 'nativeAudioExts' in cls and '"mp3"' in cls)
    ok5a = bool(cls) and has_table and not offenders
    results.append((
        "F5a media extension table declared once, in MarkdownBlockModel.kt",
        ok5a,
        "classifier table=%s; other media-side copies=%s" % (has_table, offenders or "none"),
        None))

    # F5b: one classifier — ext read sits on mediaFileNameOfUrl, which cuts the
    # query first; the parser owns no ext read and delegates by name.
    kind_body = _body_text(cls, "mediaKindOfUrl")
    name_body = _body_text(cls, "mediaFileNameOfUrl")
    path_body = _body_text(cls, "mediaPathOfUrl")
    kind_ok = "mediaFileNameOfUrl(" in kind_body and "substringAfterLast" in kind_body
    name_ok = "mediaPathOfUrl(" in name_body
    path_ok = "substringBefore('?')" in path_body
    parser_own_ext = ('substringAfterLast(".", "")' in par
                      or "substringAfterLast('.', \"\")" in par)
    parser_delegates = "mediaKindOfUrl(" in par
    ok5b = (bool(kind_body) and bool(name_body) and bool(path_body)
            and kind_ok and name_ok and path_ok and not parser_own_ext and parser_delegates)
    results.append((
        "F5b ext read after query cut, parser delegates to mediaKindOfUrl",
        ok5b,
        "kind=%s name=%s path=%s parser_own_ext=%s parser_delegates=%s"
        % (kind_ok, name_ok, path_ok, parser_own_ext, parser_delegates),
        None))

    # F5c: `#` is a fragment only for http(s); in a local/minis path it may be
    # part of the file name (`foo#China.mp4`), so cutting it there mangles files.
    # The branch must be DECIDED by the http(s) test: `… || true` and
    # `true || …` fold to a constant, which is the same red.
    unguarded = []
    for m in re.finditer(r"substringBefore\('#", cls):
        cond = _enclosing_if_cond(cls, m.start())
        if cond is None:
            unguarded.append("no enclosing if")
            continue
        dead = _dead_guard(cond)
        if dead:
            unguarded.append("dead guard: %s (%s)" % (cond.strip()[:48], dead))
        elif not HTTP_TEST_RE.search(cond):
            unguarded.append("not an http(s) branch: %s" % cond.strip()[:48])
    parser_hash = "substringBefore('#')" in par
    chat_chain = "substringBefore('?').substringBefore('#')" in cht
    ok5c = bool(cls) and not unguarded and not parser_hash and not chat_chain
    results.append((
        "F5c '#' cut only inside an http(s) branch; local paths keep it",
        ok5c,
        "unguarded_cls=%s parser_cut=%s chat_chain=%s"
        % (unguarded or "none", parser_hash, chat_chain),
        None))

    # F5d: `minis:///var/minis/x` decodes to an ABSOLUTE guest path; the sites
    # that prepend "/var/minis/" must keep absolute paths untouched. The guard
    # must be the branch's own LIVE condition.
    guarded = 0
    unguarded_sites = []
    for rel in sorted(ui_lits):
        for line in ui_lits[rel].splitlines():
            if '"/var/minis/$decoded"' not in line:
                continue
            m = re.search(r"\bif\s*\(", line)
            cond = None
            if m:
                p = line.find("(", m.start())
                q = match_bracket(line, p) if p >= 0 else -1
                if q > p:
                    cond = line[p + 1:q]
            if cond is None:
                unguarded_sites.append("%s (no readable guard)" % os.path.basename(rel))
                continue
            dead = _dead_guard(cond)
            if dead:
                unguarded_sites.append("%s (dead guard: %s — %s)"
                                       % (os.path.basename(rel), cond.strip()[:40], dead))
            elif 'startsWith("/")' not in cond:
                unguarded_sites.append("%s (guard does not test absoluteness: %s)"
                                       % (os.path.basename(rel), cond.strip()[:40]))
            else:
                guarded += 1
    ok5d = not unguarded_sites and guarded >= 1
    results.append((
        "F5d decoded minis:// path is absolute-guarded before use",
        ok5d,
        "guarded=%d unguarded=%s" % (guarded, unguarded_sites or "none"),
        None))
    return results


FIXED_SHEET = """package probe
fun openExternalFromSheet(context: Context, url: String) {
    val intent = if (url.startsWith("file://")) {
        runCatching {
            val raw = decodeUriPath(url.removePrefix("file://"))
            if (raw.isBlank()) return@runCatching null
            val file = java.io.File(raw)
        }
    }
}
"""

REVERTED_SHEET = """package probe
fun openExternalFromSheet(context: Context, url: String) {
    val intent = if (url.startsWith("file://")) {
        runCatching {
            val raw = Uri.parse(url).path ?: return@runCatching null
            val file = java.io.File(raw)
        }
    }
}
"""

FIXED_MD = """package probe
fun resolveMdMediaFile(context: Context, stripped: String): File? {
    val primary: File? = when {
        stripped.startsWith("minis://") -> File(decodeUriPath(stripped.removePrefix("minis://")))
        stripped.startsWith("file://") ->
            decodeUriPath(stripped.removePrefix("file://")).takeIf { it.isNotBlank() }?.let { File(it) }
        stripped.startsWith("/") -> File(stripped)
        else -> null
    }
}
"""

REVERTED_MD = """package probe
fun resolveMdMediaFile(context: Context, stripped: String): File? {
    val primary: File? = when {
        stripped.startsWith("file://") -> File(Uri.parse(stripped).path ?: return null)
        else -> null
    }
}
"""

FIXED_HOLDER = """package probe
internal fun fromAssetLoaderUrl(assetUrl: String, rootDir: String?): String? {
    val relative = decodeUriPath(
        assetUrl.removePrefix(ASSET_LOADER_PREFIX).substringBefore('#').substringBefore('?'),
    )
    return FILE_URL_PREFIX + resolved
}
"""

CUTS_REMOVED_HOLDER = """package probe
internal fun fromAssetLoaderUrl(assetUrl: String, rootDir: String?): String? {
    val relative = decodeUriPath(assetUrl.removePrefix(ASSET_LOADER_PREFIX))
    return FILE_URL_PREFIX + resolved
}
"""

CLEAN_UI_FILE = """package probe
fun host(url: String): String? = Uri.parse(url).host
fun plain(url: String) { Intent(Intent.ACTION_VIEW, Uri.parse(url)) }
"""

DIRTY_UI_FILE = """package probe
fun host(url: String): String? = Uri.parse(url).path.orEmpty()
"""

ALLOWLISTED_AS_IS = """package probe
fun abs(url: String): String = Uri.parse(url).path.orEmpty()
"""


MEDIA_CLASSIFIER_CLEAN = """package probe

internal enum class MediaKind { IMAGE, VIDEO, AUDIO }

internal val nativeVideoExts = setOf("mp4", "mov", "m4v")
internal val nativeAudioExts = setOf("mp3", "m4a")

internal fun mediaKindOfUrl(url: String): MediaKind {
    val ext = mediaFileNameOfUrl(url).substringAfterLast('.', "").lowercase()
    return when (ext) {
        in nativeVideoExts -> MediaKind.VIDEO
        in nativeAudioExts -> MediaKind.AUDIO
        else -> MediaKind.IMAGE
    }
}

internal fun mediaPathOfUrl(url: String): String {
    val noQuery = url.substringBefore('?')
    return if (noQuery.startsWith("http://") || noQuery.startsWith("https://")) {
        noQuery.substringBefore('#')
    } else {
        noQuery
    }
}

internal fun mediaFileNameOfUrl(url: String): String =
    mediaPathOfUrl(url).substringAfterLast('/')
"""

MEDIA_PARSER_CLEAN = """package probe

internal fun mediaBlockFor(alt: String, url: String) = when (mediaKindOfUrl(url)) {
    MediaKind.VIDEO -> "video"
    MediaKind.AUDIO -> "audio"
    MediaKind.IMAGE -> "image"
}
"""

CHAT_SCREEN_CLEAN = """package probe

internal fun collectImages(src: String): List<String> {
    val path = mediaPathOfUrl(src)
    if (mediaKindOfUrl(src) != MediaKind.IMAGE) return emptyList()
    return listOf(mediaFileNameOfUrl(src))
}
"""

IMAGE_FETCHER_CLEAN = """package probe

internal fun resolve(decoded: String): java.io.File {
    val linuxPath = if (decoded.startsWith("/")) decoded else "/var/minis/$decoded"
    return java.io.File(linuxPath)
}
"""

BREAKER_CLASSIFIER_HASH = MEDIA_CLASSIFIER_CLEAN.replace(
    "    return if (noQuery.startsWith(\"http://\") || noQuery.startsWith(\"https://\")) {\n"
    "        noQuery.substringBefore('#')",
    "    return if (true) {\n"
    "        noQuery.substringBefore('#')")

BREAKER_PARSER_SET = MEDIA_PARSER_CLEAN.replace(
    "internal fun mediaBlockFor",
    "private val parserExts = setOf(\"mp4\", \"mov\")\n\ninternal fun mediaBlockFor")

BREAKER_PARSER_EXT = MEDIA_PARSER_CLEAN.replace(
    "when (mediaKindOfUrl(url))",
    "when (url.substringAfterLast('.', \"\"))")

BREAKER_CHAT_CHAIN = CHAT_SCREEN_CLEAN.replace(
    "val path = mediaPathOfUrl(src)",
    "val path = src.substringBefore('?').substringBefore('#')")

BREAKER_FETCHER_UNGURARDED = IMAGE_FETCHER_CLEAN.replace(
    'val linuxPath = if (decoded.startsWith("/")) decoded else "/var/minis/$decoded"',
    'val linuxPath = "/var/minis/$decoded"')

# [wide-1024] Three rewrites that kept every token the old criteria looked for
# and dropped the decision those tokens were supposed to make.
BREAKER_SHEET_POST_CUT = FIXED_SHEET.replace(
    '            val raw = decodeUriPath(url.removePrefix("file://"))\n',
    '            val raw = decodeUriPath(url.removePrefix("file://"))'
    ".substringBefore('#').substringBefore('?')\n")
assert BREAKER_SHEET_POST_CUT != FIXED_SHEET

BREAKER_CLASSIFIER_DEAD_OR = MEDIA_CLASSIFIER_CLEAN.replace(
    'return if (noQuery.startsWith("http://") || noQuery.startsWith("https://")) {',
    'return if (true || noQuery.startsWith("http://") || noQuery.startsWith("https://")) {')
assert BREAKER_CLASSIFIER_DEAD_OR != MEDIA_CLASSIFIER_CLEAN

BREAKER_FETCHER_DEAD_GUARD = IMAGE_FETCHER_CLEAN.replace(
    'val linuxPath = if (decoded.startsWith("/")) decoded else "/var/minis/$decoded"',
    'val linuxPath = if (false && decoded.startsWith("/")) decoded else "/var/minis/$decoded"')
assert BREAKER_FETCHER_DEAD_GUARD != IMAGE_FETCHER_CLEAN

# ------------------------------------------------------------- new breakers ---
# batch-7 witnesses: every one keeps the token the old criterion grepped for
# and removes the decision behind it.
BREAKER_SHEET_CONSUME_CUT = FIXED_SHEET.replace(
    "            val file = java.io.File(raw)",
    "            val file = java.io.File(raw.substringBefore('#').substringBefore('?'))")
assert BREAKER_SHEET_CONSUME_CUT != FIXED_SHEET

BREAKER_SHEET_RAW_STRING = FIXED_SHEET.replace(
    "            if (raw.isBlank()) return@runCatching null\n"
    "            val file = java.io.File(raw)",
    '            val _openRaw = """ " /* """\n'
    "            val file = java.io.File(Uri.parse(url).path ?: return@runCatching null)\n"
    '            val _closeRaw = """ */ """')
assert BREAKER_SHEET_RAW_STRING != FIXED_SHEET

BREAKER_MD_LET_PARSE = FIXED_MD.replace(
    '            decodeUriPath(stripped.removePrefix("file://")).takeIf { it.isNotBlank() }?.let { File(it) }',
    '            decodeUriPath(stripped.removePrefix("file://")).let { Uri.parse(stripped.trim()).path }?.let { File(it) }')
assert BREAKER_MD_LET_PARSE != FIXED_MD

BREAKER_HOLDER_POST_CUT = FIXED_HOLDER.replace(
    "    val relative = decodeUriPath(\n"
    "        assetUrl.removePrefix(ASSET_LOADER_PREFIX).substringBefore('#').substringBefore('?'),\n"
    "    )",
    "    val relative = decodeUriPath(\n"
    "        assetUrl.removePrefix(ASSET_LOADER_PREFIX),\n"
    "    ).substringBefore('#').substringBefore('?')")
assert BREAKER_HOLDER_POST_CUT != FIXED_HOLDER

# `|| true` on the RIGHT of the disjunction (the mirror image of `true ||`).
BREAKER_CLASSIFIER_DEAD_OR_RIGHT = MEDIA_CLASSIFIER_CLEAN.replace(
    'return if (noQuery.startsWith("http://") || noQuery.startsWith("https://")) {',
    'return if (noQuery.startsWith("http://") || noQuery.startsWith("https://") || true) {')
assert BREAKER_CLASSIFIER_DEAD_OR_RIGHT != MEDIA_CLASSIFIER_CLEAN

# `&& false` on the RIGHT of the conjunction (the mirror image of `false &&`).
BREAKER_FETCHER_DEAD_AND_RIGHT = IMAGE_FETCHER_CLEAN.replace(
    'val linuxPath = if (decoded.startsWith("/")) decoded else "/var/minis/$decoded"',
    'val linuxPath = if (decoded.startsWith("/") && false) decoded else "/var/minis/$decoded"')
assert BREAKER_FETCHER_DEAD_AND_RIGHT != IMAGE_FETCHER_CLEAN

ALLOWLIST_REL = next(iter(ALLOWLIST))
ALLOWLISTED_FILE = """package probe
fun abs(url: String): String = Uri.parse(url).path.orEmpty()
"""
DIRTY_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/DirtyUi.kt"
DIRTY_UI = """package probe
fun abs(url: String): String = Uri.parse(url).path.orEmpty()
"""
FETCHER_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/MinisImageFetcher.kt"

DEFAULT_UI = {
    MEDIA_MODEL_REL: MEDIA_CLASSIFIER_CLEAN,
    MEDIA_PARSER_REL: MEDIA_PARSER_CLEAN,
    CHAT_SCREEN_REL: CHAT_SCREEN_CLEAN,
    FETCHER_REL: IMAGE_FETCHER_CLEAN,
    ALLOWLIST_REL: ALLOWLISTED_FILE,
}

F1A = 'F1a sheet file:// branch resolves via decodeUriPath(url.removePrefix("file://"))'
F1B = "F1b no Uri.parse(...).path in WebPreviewBottomSheet.kt"
F2A = 'F2a markdown file:// branch resolves via decodeUriPath(stripped.removePrefix("file://"))'
F2B = "F2b no Uri.parse(...).path in StreamingMarkdownText.kt"
F3 = "F3 fromAssetLoaderUrl cuts #/? before decodeUriPath"
F5A = "F5a media extension table declared once, in MarkdownBlockModel.kt"
F5B = "F5b ext read after query cut, parser delegates to mediaKindOfUrl"
F5C = "F5c '#' cut only inside an http(s) branch; local paths keep it"
F5D = "F5d decoded minis:// path is absolute-guarded before use"


def dict_of(extra):
    d = dict(DEFAULT_UI)
    d.update(extra)
    return d


def selftest():
    failures = []
    arms = 0

    def expect(cond, what):
        if not cond:
            failures.append(what)

    def run(sheet, md, holder, ui):
        nonlocal arms
        arms += 1
        return analyse(sheet, md, holder, ui)

    def crit(results, name):
        for c, ok, _d, _l in results:
            if c == name:
                return ok
        return None

    def all_green(results):
        return all(ok for _c, ok, _d, _l in results)

    # 1: the fixed tree must be green on every criterion.
    fixed = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER, dict_of({}))
    expect(all_green(fixed),
           "fixed tree must be GREEN, got %s" % [c for c, ok, _d, _l in fixed if not ok])

    # 2: the reverted sheet (Uri.parse(url).path) -> F1a + F1b red.
    m = run(REVERTED_SHEET, FIXED_MD, FIXED_HOLDER, dict_of({}))
    expect(crit(m, F1A) is False, "sheet-revert: F1a must be RED")
    expect(crit(m, F1B) is False, "sheet-revert: F1b must be RED")

    # 3: the reverted markdown branch -> F2a + F2b red.
    m = run(FIXED_SHEET, REVERTED_MD, FIXED_HOLDER, dict_of({}))
    expect(crit(m, F2A) is False, "md-revert: F2a must be RED")
    expect(crit(m, F2B) is False, "md-revert: F2b must be RED")

    # 4: the holder loses the cut -> F3 red.
    m = run(FIXED_SHEET, FIXED_MD, CUTS_REMOVED_HOLDER, dict_of({}))
    expect(crit(m, F3) is False, "holder-cut-removed: F3 must be RED")

    # 5: a new offender under ui/** -> F4 red, and only that file.
    m = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER, dict_of({DIRTY_REL: DIRTY_UI}))
    expect(any(not ok and "DirtyUi.kt" in c for c, ok, _d, _l in m),
           "new-offender: F4 must be RED for DirtyUi.kt")
    expect(crit(m, F5C) is True, "new-offender: F5c must stay GREEN (precision)")

    # 6: a stale allowlist entry (file fixed, entry kept) -> RED.
    m = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER, dict_of({ALLOWLIST_REL: CLEAN_UI_FILE}))
    expect(any(not ok and "allowlist" in c for c, ok, _d, _l in m),
           "stale-allowlist: the exemption must be RED once the site is fixed")

    # 7: host precision — Uri.parse without `.path` is not a violation.
    expect(count_uri_parse_path(mask_code(CLEAN_UI_FILE)) == 0,
           "fixture precision: Uri.parse(url).host parses no Uri path")

    # 8: F1a catches a rewrite chained onto the call, F1b stays green.
    m = run(BREAKER_SHEET_POST_CUT, FIXED_MD, FIXED_HOLDER, dict_of({}))
    expect(crit(m, F1A) is False,
           "sheet-post-cut: F1a must be RED when the decoded path is re-cut")
    expect(crit(m, F1B) is True, "sheet-post-cut: F1b must stay GREEN (precision)")

    # 9: F5a — the parser declaring its own extension set.
    m = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER, dict_of({MEDIA_PARSER_REL: BREAKER_PARSER_SET}))
    expect(crit(m, F5A) is False, "parser-set: F5a must be RED")
    expect(crit(m, F5C) is True, "parser-set: F5c must stay GREEN (precision)")

    # 10: F5b — the parser owning an ext read.
    m = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER, dict_of({MEDIA_PARSER_REL: BREAKER_PARSER_EXT}))
    expect(crit(m, F5B) is False, "parser-ext: F5b must be RED")

    # 11: F5c — the chat side re-growing the query+fragment chain.
    m = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER, dict_of({CHAT_SCREEN_REL: BREAKER_CHAT_CHAIN}))
    expect(crit(m, F5C) is False, "chat-chain: F5c must be RED")

    # 12: F5c — `#` cut outside any http(s) branch (constant `if (true)`).
    m = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER, dict_of({MEDIA_MODEL_REL: BREAKER_CLASSIFIER_HASH}))
    expect(crit(m, F5C) is False, "classifier-if-true: F5c must be RED")
    expect(crit(m, F5A) is True, "classifier-if-true: F5a must stay GREEN (precision)")

    # 13: F5d — the absolute-path guard dropped.
    m = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER, dict_of({FETCHER_REL: BREAKER_FETCHER_UNGURARDED}))
    expect(crit(m, F5D) is False, "fetcher-unguarded: F5d must be RED")

    # 14/15: dead guard spellings — left (`true ||`, `false &&`) and right
    # (`|| true`, `&& false`) placements must all be the same red.
    m = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER,
            dict_of({MEDIA_MODEL_REL: BREAKER_CLASSIFIER_DEAD_OR}))
    expect(crit(m, F5C) is False, "classifier `true ||`: F5c must be RED")
    m = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER,
            dict_of({MEDIA_MODEL_REL: BREAKER_CLASSIFIER_DEAD_OR_RIGHT}))
    expect(crit(m, F5C) is False, "classifier `|| true`: F5c must be RED")
    m = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER,
            dict_of({FETCHER_REL: BREAKER_FETCHER_DEAD_GUARD}))
    expect(crit(m, F5D) is False, "fetcher `false &&`: F5d must be RED")
    m = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER,
            dict_of({FETCHER_REL: BREAKER_FETCHER_DEAD_AND_RIGHT}))
    expect(crit(m, F5D) is False, "fetcher `&& false`: F5d must be RED")

    # ---- batch-7 witness arms (01-1 … 01-4) ----------------------------
    # 01-1: the cut moved from the call chain to the variable's consumption
    # site — every token the 80-char window looked at is untouched.
    m = run(BREAKER_SHEET_CONSUME_CUT, FIXED_MD, FIXED_HOLDER, dict_of({}))
    expect(crit(m, F1A) is False,
           "11-consumption-cut: F1a must be RED at the variable's read site")
    expect(crit(m, F1B) is True, "11-consumption-cut: F1b must stay GREEN (precision)")

    # 01-2: `.let { Uri.parse(...).path }` — the value-scope value is not the
    # decoded path, and the nested parse argument used to walk the `[^()]*`
    # regex.
    m = run(FIXED_SHEET, BREAKER_MD_LET_PARSE, FIXED_HOLDER, dict_of({}))
    expect(crit(m, F2A) is False,
           "md-let-parse: F2a must be RED (the decoded value is discarded)")
    expect(crit(m, F2B) is False,
           "md-let-parse: F2b must be RED (nested `Uri.parse(x.trim()).path`)")

    # 01-3: the cuts survive but moved AFTER the decode (order, not spelling).
    m = run(FIXED_SHEET, FIXED_MD, BREAKER_HOLDER_POST_CUT, dict_of({}))
    expect(crit(m, F3) is False,
           "holder-post-cut: F3 must be RED when the cut follows decodeUriPath")

    # 01-4: `""" " /* """` must not open a block comment over real code.
    m = run(BREAKER_SHEET_RAW_STRING, FIXED_MD, FIXED_HOLDER, dict_of({}))
    expect(crit(m, F1B) is False,
           "raw-string-comment: F1b must be RED (the raw string is not a comment)")

    # fail-closed: an unlexable source turns the gate red instead of being
    # scanned as raw text.
    m = run('fun broken() { val s = "abc\n', FIXED_MD, FIXED_HOLDER, dict_of({}))
    expect(not all_green(m), "unlexable source must be RED (fail-closed)")

    if failures:
        print("SELFTEST FAIL:")
        for f in failures:
            print("  - %s" % f)
        return 0
    print("selftest: fixed-tree=GREEN, sheet-revert=RED, md-revert=RED, "
          "holder-cut-removed=RED, new-offender=RED, stale-allowlist=RED, "
          "host-precision=GREEN, media-hash-cut=RED, media-set-fork=RED, "
          "media-parser-ext=RED, media-chat-chain=RED, media-abs-guard=RED, "
          "dead-guard-both-sides=RED, consumption-cut=RED, md-let-parse=RED, "
          "holder-post-cut=RED, raw-string-comment=RED, fail-closed=RED")
    return arms


def main(argv):
    if "--selftest" in argv:
        arms = selftest()
        if arms == 0:
            return 1
        print("FILE_URI_PARSE_GUARD_SELFTEST=PASS (%d fixtures)" % arms)
        return 0

    args = [a for a in argv[1:] if a != "--selftest"]
    root = args[0] if args else "."

    sheet_p = os.path.join(root, SHEET_REL)
    md_p = os.path.join(root, MD_REL)
    holder_p = os.path.join(root, HOLDER_REL)
    ui_root = os.path.join(root, UI_ROOT_REL)
    for p in (sheet_p, md_p, holder_p):
        if not os.path.isfile(p):
            print("FAIL required file missing: %s" % p)
            return 1
    if not os.path.isdir(ui_root):
        print("FAIL ui root missing: %s" % ui_root)
        return 1

    sheet_code = open(sheet_p, encoding="utf-8").read()
    md_code = open(md_p, encoding="utf-8").read()
    holder_code = open(holder_p, encoding="utf-8").read()

    # [batch-7] raw sources: analyse() derives the structure/literal views
    # itself (mask_code / mask_comments), so a `}` or a `/*` inside a string
    # cannot truncate a body any more.
    ui_codes = {}
    for dirpath, _dirnames, filenames in os.walk(ui_root):
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            ui_codes[rel] = open(full, encoding="utf-8").read()

    results = analyse(sheet_code, md_code, holder_code, ui_codes)
    failed = [r for r in results if not r[1]]
    for crit, ok, detail, line in results:
        if ok:
            print("  [OK] %s%s" % (crit, " — %s" % detail if detail else ""))
        else:
            print("  [FAIL] %s%s — %s" % (crit, " (line %s)" % line if line else "", detail))
    print("Summary: %d passed, %d failed" % (len(results) - len(failed), len(failed)))
    print("swept %d ui/** .kt file(s) under %s" % (len(ui_codes), UI_ROOT_REL))
    if failed:
        print("file uri parse guard: RED — Uri.parse(...).path on local file:// "
              "paths truncates #/? and silently no-ops; resolve via ui.chat.decodeUriPath")
        return 1
    print("GATE=GREEN file_uri_parse_guard")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
