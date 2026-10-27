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
  F4  ui/** sweep: `Uri.parse(...).path` is banned under ui/** — the
      allowlist is EMPTY. The last exempted site (ui/preview/
      WebPreviewShortcut.kt, backlog §1023-H) was fixed in fix/debt-sweep-1026
      and its entry removed; a stale entry is RED, so the exemption cannot
      outlive the debt it names.
  F4c WebPreviewShortcut.pin (the THIRD `file://` consumer) resolves the URL
      through `decodeUriPath(url.removePrefix("file://"))` — the same
      value-flow contract as F1a/F2a. Removing the decode survives F4 (no
      `Uri.parse`) and F4b (the root strip is intact), and leaves
      `Uri.Builder().path(absPath)` to re-encode `%`, so a pinned shortcut for
      `我的 报告#1.html` names a file that does not exist. The pin's own
      `removePrefix("/var/minis")` and trailing-slash trims are permitted
      chains (they do not re-interpret URL delimiters);
  F5  one media decision point (extension table / classifier / `#`-cut scope /
      absolute-path guard on every minis:// decode).
  F6b the prefix-stripped path handed to `File(…)` is DECODED first — the root
      is filesystem semantics, so the encoded URL form must not reach it;
  F6c fromAssetLoaderUrl PUBLISHES the encoded form (`FILE_URL_PREFIX +
      encodeFileUrlPath(…)`), keeping `currentUrl` byte-identical to the URL the
      holder was constructed with;
  F7  no raw path is concatenated into a `file://` URL anywhere under ui/** +
      webapp/** — the operand must be the path encoder (`encodeFileUrlPath(…)`)
      or its single builder (`fileUrlFor(…)`).
  F7b no `decodeUriPath(` call takes an encoder result as its argument: the
      encoded form belongs to the CONSUMERS, not to the site that built it.
      `decodeUriPath(fileUrlFor(file))` passes F7 untouched (the operand is
      still the builder) and breaks two things at once — the holder's
      `currentUrl` equality never holds, so every click rebuilds the WebView,
      and a literal `%XX` file name is decoded twice into a name that exists
      nowhere. The sweep is derived (ui/** + webapp/** walk), never a file list.

[fix/fileurl-contract] The contract these three criteria pin — **encoded at
construction, decoded at every path-semantics consumer** — is what the first
three witnesses of this family kept half-fixing: `§1021-V` (a `#` in the name
truncated the WebView load), `§1023-H` (`Uri.parse(url).path` on the same raw
URL) and `fix/debt-sweep-1026` (a literal `%XX` name — `50%23.html` — arriving
at the decoding consumers as `50#.html`). Every round fixed a consumer while the
construction sites in `ChatScreen` / `WebAppActivity` kept concatenating the raw
absolute path, so the two contracts stayed spliced together. F6b/F6c/F7 are the
construction-side and holder-boundary halves of that same list; the sweep covers
webapp/** too, because `WebAppActivity` was one of the three raw sites.

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
    const_value,
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
    value_sources,
)

SHEET_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/WebPreviewBottomSheet.kt"
MD_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/StreamingMarkdownText.kt"
HOLDER_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/WebViewHolder.kt"
UI_ROOT_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui"
# [fix/fileurl-contract] webapp/ constructs file:// URLs too (WebAppActivity was
# one of the three raw sites this branch fixed), so the construction sweep covers
# it; F4/F5 stay keyed on the merged dict and are green on webapp/.
WEBAPP_ROOT_REL = "src/android/app/src/main/java/com/rikkaminis/app/webapp"
# [dv22/B-06] the markdown media pipeline: one classifier, one parser, the
# gallery collector and the image fetcher.
MEDIA_MODEL_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/MarkdownBlockModel.kt"
MEDIA_PARSER_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/markdown/MarkdownParser.kt"
CHAT_SCREEN_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt"

# relpath (repo-root-relative, forward slashes) -> max allowed occurrences.
# EMPTY by design: every `Uri.parse(…).path` site under ui/** now resolves
# through ui/chat/UriPathDecode.decodeUriPath. The last one (WebPreviewShortcut,
# backlog §1023-H) was fixed in fix/debt-sweep-1026 (#2) — it pinned a deep link
# to a path truncated at a literal `#`/`?` in the file name. F4's "exact" rule
# makes a leftover entry RED, so an exemption cannot outlive its debt; do not
# re-add entries here for new sites, fix them.
ALLOWLIST = {}

# The staleness rule still needs a live probe allowlist: the "entry kept but the
# site is gone" arm has nothing to be stale about otherwise. SELFTEST ONLY —
# the real-tree run never consults it (analyse() defaults to ALLOWLIST).
SELFTEST_ALLOWLIST = {
    "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/WebPreviewShortcut.kt": 1,
}
SELFTEST_ALLOWLIST_REASON = (
    "selftest probe: stands in for the historical WebPreviewShortcut exemption so "
    "the stale-entry arm keeps a subject."
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


def _chain_first_arg(code, lit, pos):
    """Literal text of the first argument of the call chained at [pos].

    [pos] is where `_poison_chain` starts looking (right after the identifier
    that holds a value). Returns None when no call is chained there.
    """
    i, n = pos, len(code)
    while i < n and code[i] in " \t\r\n":
        i += 1
    if i < n and code[i] == "?":
        i += 1
        while i < n and code[i] in " \t\r\n":
            i += 1
    m = re.match(r"\.\s*[A-Za-z_]\w*\s*\(", code[i:])
    if not m:
        return None
    open_pos = i + m.end() - 1
    if match_bracket(code, open_pos) < 0:
        return None
    spans = call_args(code, open_pos)
    if not spans:
        return ""
    return "".join(lit[a:b] for a, b in spans).strip()


ROOT_STRIP_LITERAL = '"/var/minis"'
EDGE_TRIMS = ("trim", "trimEnd", "trimStart")


def _shortcut_chain_permit(code, lit, pos, name):
    """Chains that are LEGAL on WebPreviewShortcut's decoded path (F4c permit).

    `pin` decodes the file URL and then does two things the URL-path resolver
    also does not mind: strips the sandbox root (`removePrefix("/var/minis")`)
    to build `minis://session/<sid>/<resource>` and trims a trailing slash
    before comparing the result against the root itself. Neither re-interprets
    a URL delimiter, so neither can truncate a file name — the truncation
    family this criterion exists for (`substringBefore('#')`, `split`,
    `replace`, …) stays red, and so does a `removePrefix` of anything other
    than the root literal.
    """
    if name in EDGE_TRIMS:
        return True
    if name != "removePrefix":
        return False
    return _chain_first_arg(code, lit, pos) == ROOT_STRIP_LITERAL


def decode_flow_problem(scope_code, scope_lit, arg_marker, permit=None):
    """The value produced by `decodeUriPath(<arg containing arg_marker>)` must
    reach the File construction unchanged.

    `arg_marker` is the literal the argument must remove (`removePrefix("file://")`)
    — it is what pins the criterion to the `file://` resolution site instead of
    any decoder call in the file.

    [permit] is an optional `(code, lit, pos, method_name) -> bool` that can
    bless a specific chained rewrite. F1a/F2a pass none: their decoded path is
    used as-is. F4c passes `_shortcut_chain_permit` because the pin site
    legitimately strips the sandbox root off the decoded path — without the
    permit the criterion would be red on the FIXED tree, which is the failure
    mode that gets a gate disabled instead of trusted.
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
    if name and not (permit and permit(scope_code, scope_lit, close + 1, name)):
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
            if pname and not (permit and permit(scope_code, scope_lit, rm.end(), pname)):
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


# ---------------------------------------------- r3 round-3b (dual-gate) ----
# Four seams the third-round attack landed on, each one "every token the old
# criteria grep for survives, the decision behind it is gone":
#   F2c  01-3  the cross-session `minis://` fallback stopped decoding (the
#              file:// branch F2a anchors still does, so F2a stayed green);
#   F3b  02-1  `toAssetLoaderUrl`'s NAME became a constant — the encoder's
#              round-trip partner (F3) and the call-site accountancy (W2/W3a)
#              are untouched by that;
#   F4b  01-2  the pinned-shortcut deep link stopped stripping `/var/minis`
#              (F4's allowlist only counts `Uri.parse(…).path`);
#   F6   01-1  the holder's asset ROOT was built from the un-stripped URL, so
#              the loader serves nothing (F3 only reads fromAssetLoaderUrl).
FILE_STRIP = 'removePrefix("file://")'
MINIS_STRIP = 'removePrefix("minis://")'
SHORTCUT_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/WebPreviewShortcut.kt"
ROOT_PREFIX_TESTS = ("removePrefix", "substringAfter", "replaceFirst", "replace(")


def _binding_rhs(body, ident, lit=None):
    """RHS of the LAST `val|var <ident> = …` in `body` (one hop), or ''.

    `body` is the STRUCTURE view (spans are derived from it); `lit` is the
    length-preserving LITERAL view the text is read from, so `removePrefix(
    "/var/minis")` keeps its argument.
    """
    rhs, src = "", lit if lit is not None else body
    for m in re.finditer(r"\b(?:val|var)\s+" + re.escape(ident) + r"\s*(?::[^=\n]*)?=\s*",
                         body):
        rhs = src[m.end():expr_end(body, m.end())]
    return rhs.strip()


def _decoder_spans(code):
    """[(open_paren_pos, close_paren_pos)] of every `decodeUriPath(` call."""
    spans = []
    for d in re.finditer(r"\bdecodeUriPath\s*\(", code):
        close = match_bracket(code, d.end() - 1)
        spans.append((d.end() - 1, close))
    return spans


def undecoded_strip_problem(md_fn_code, md_fn_lit):
    """F2c — every `minis://` prefix strip in resolveMdMediaFile is decoded.

    Witness 01-3 dropped `decodeUriPath(…)` from the cross-session fallback
    only: the primary branch and the `file://` branch F2a anchors were left
    verbatim, so the gate kept saying "the file:// path is resolved once and
    used as-is" while `minis://workspace/my%20chart.png` started being searched
    as the literal `my%20chart.png` under every session directory.

    The strip marker lives in the LITERAL view (the structure view blanks the
    string), the call spans in the structure view — both are length-preserving.
    """
    sites = [m.start() for m in re.finditer(re.escape(MINIS_STRIP), md_fn_lit)]
    if not sites:
        return ('no `%s` in resolveMdMediaFile (the minis:// branch moved — '
                "re-point this guard, fail-closed)" % MINIS_STRIP)
    spans = _decoder_spans(md_fn_code)
    if any(close < 0 for _o, close in spans):
        return "unbalanced decodeUriPath(…) (fail-closed)"
    loose = [p for p in sites if not any(o < p < c for o, c in spans)]
    if loose:
        return ("%d `minis://` prefix strip(s) bypass decodeUriPath — a percent-encoded "
                "name (`my%%20chart.png`) is then matched literally, so every "
                "cross-session / multi-session media lookup misses" % len(loose))
    return ""


def encoder_name_problem(holder_code, holder_lit):
    """F3b — the name `toAssetLoaderUrl` encodes is derived from the file URL.

    Witness 02-1 set `val name = ""`: the forward mapping still exists, still
    returns the asset-loader prefix, and `startIfNeeded`'s `?: currentUrl`
    fallback still satisfies W2 — but the encoder now encodes nothing, so the
    `%XX` round-trip F3 pins (and its inverse) is vacuous and every local HTML
    preview silently degrades to a raw file:// load.
    """
    body = _body_text(holder_code, "toAssetLoaderUrl")
    lit_body = _body_text(holder_lit, "toAssetLoaderUrl")
    if not body:
        return "toAssetLoaderUrl not found (fail-closed)"
    enc = list(re.finditer(r"\bencodePathSegment\s*\(", body))
    if len(enc) != 1:
        return ("toAssetLoaderUrl must encode exactly one name segment (found %d) — the "
                "asset-loader URL carries the segment the loader resolves" % len(enc))
    spans = call_args(body, enc[0].end() - 1)
    if not spans:
        return "encodePathSegment(…) has no readable argument (fail-closed)"
    arg = "".join(lit_body[a:b] for a, b in spans).strip()
    if re.fullmatch(r"[A-Za-z_]\w*", arg):
        arg = _binding_rhs(body, arg, lit_body) or arg
    forms = value_sources(arg)
    consts = [f for f in forms if const_value(f) is not None]
    if consts:
        return ("the encoded name is the constant %r — the segment must come from the "
                "file URL, or the loader URL names no file at all" % consts[0].strip()[:40])
    if not any("substringAfterLast" in f for f in forms):
        return ("the encoded name (%r) is not the URL's last path segment — the forward "
                "mapping no longer derives the name from the file URL" % arg[:80])
    return ""


def shortcut_strip_problem(code, lit):
    """F4b — the pinned shortcut's deep link strips the `/var/minis` root.

    Witness 01-2 removed `absPath.removePrefix("/var/minis")` from the
    resource-path assembly: `Uri.parse(url).path` is still counted (F4's
    allowlist entry still matches exactly) and the pin still succeeds, but the
    deep link becomes `minis://session/<sid>/var/minis/browser/x.html`, which
    the session resolver looks up as `/var/minis/var/minis/…` and never finds.

    Derived from the `.path(…)` call of the Uri.Builder chain — no function
    name, no local name: every identifier feeding the deep-link path must have
    a binding that removes the sandbox root (or the argument itself must be a
    prefix-stripped expression). A constant path with no bindings is RED
    (fail-closed): the pinned shortcut would point nowhere.
    """
    calls = list(re.finditer(r"\.\s*path\s*\(", code))
    if not calls:
        return "no `.path(…)` in the pin's Uri.Builder chain (fail-closed)"
    for m in calls:
        spans = call_args(code, m.end() - 1)
        if not spans:
            continue
        arg = "".join(lit[a:b] for a, b in spans)
        if "/var/minis" in arg and any(t in arg for t in ROOT_PREFIX_TESTS):
            return ""
        for ident in sorted(set(re.findall(r"[A-Za-z_]\w*", arg))):
            rhs = _binding_rhs(code, ident, lit)
            if not rhs or "/var/minis" not in rhs:
                continue
            if any(t in rhs for t in ROOT_PREFIX_TESTS):
                return ""
    return ("the deep-link path is assembled from an unstripped sandbox path — the "
            "`/var/minis` root must be removed before the resource is appended to "
            "`minis://session/<sid>`, otherwise the pinned shortcut resolves to "
            "`/var/minis/var/minis/…` and never opens")


def holder_root_problem(holder_code, holder_lit):
    """F6 — the loader root is built from the `file://` URL with the prefix stripped.

    Witness 01-1 changed `File(initialUrl.removePrefix("file://"))` into
    `File(initialUrl)` inside `init`: `assetRootDir` becomes `file:/…` (a
    relative directory), the InternalStoragePathHandler root resolves nowhere,
    and every local HTML preview 404s while the mapping pair F3/W1 pin still
    looks complete.
    """
    args, unbalanced = [], False
    for m in re.finditer(r"\bFile\s*\(", holder_code):
        open_pos = m.end() - 1
        close = match_bracket(holder_code, open_pos)
        if close < 0:
            unbalanced = True
            continue
        for a, b in call_args(holder_code, open_pos):
            if "initialUrl" in holder_lit[a:b]:
                args.append(holder_lit[a:b].strip())
    if unbalanced:
        return "unbalanced File(…) in the holder (fail-closed)"
    if not args:
        return ("no `File(<initial URL>)` construction in the holder — the asset root is "
                "no longer derived from the initial file:// URL (fail-closed)")
    bad = [a for a in args if FILE_STRIP not in a]
    if bad:
        return ("the asset root is built from %r — the `file://` prefix must be stripped "
                "BEFORE File(…): otherwise the root is `file:/…`, the loader serves no "
                "directory and every local HTML preview 404s" % bad[0][:80])
    return ""


def holder_root_decode_problem(holder_code, holder_lit):
    """F6b — the prefix-stripped path handed to `File(…)` is decoded first.

    [fix/fileurl-contract] With the construction contract fixed (the initial
    URL is encoded), `File(initialUrl.removePrefix("file://")).parentFile`
    would look for a directory literally named `%E6%88%91…`: the loader root
    exists nowhere, `InternalStoragePathHandler` serves nothing and every local
    preview 404s while F6 still sees a stripped prefix. Only fires when the
    strip IS present — the missing-strip case is F6's own witness.
    """
    args, unbalanced = [], False
    for m in re.finditer(r"\bFile\s*\(", holder_code):
        open_pos = m.end() - 1
        if match_bracket(holder_code, open_pos) < 0:
            unbalanced = True
            continue
        for a, b in call_args(holder_code, open_pos):
            if "initialUrl" in holder_lit[a:b]:
                args.append(holder_lit[a:b].strip())
    if unbalanced:
        return "unbalanced File(…) in the holder (fail-closed)"
    stripped = [a for a in args if FILE_STRIP in a]
    if not stripped:
        return ""          # no stripped site to grade — F6 owns the missing-strip witness
    bad = [a for a in stripped if "decodeUriPath(" not in a]
    if bad:
        return ("the asset root is built from the ENCODED URL %r — `File(…)` is filesystem "
                "semantics, so the URL must be decoded first: "
                "File(decodeUriPath(initialUrl.removePrefix(\"file://\")))" % bad[0][:80])
    return ""


def holder_publish_encode_problem(holder_code):
    """F6c — `fromAssetLoaderUrl` publishes the ENCODED URL form.

    [fix/fileurl-contract] `currentUrl` is read by three kinds of consumer: the
    holder-reuse comparison in `ChatScreen` (`existing.currentUrl != url`),
    `openExternalFromSheet` and `WebPreviewShortcut.pin`, both of which decode
    (`decodeUriPath`). Publishing the raw resolved path (`FILE_URL_PREFIX +
    resolved`) makes the reuse comparison fail on every repeat tap — the holder
    is destroyed and rebuilt after every page start, which is the da1020-D
    regression — and feeds the decoders a form that mangles a literal `%XX`
    name (`50%23.html` → `50#.html`). Re-encoding with the same encoder keeps
    the round trip byte-stable: `fileUrlFor(file)` → holder → `currentUrl` are
    the identical string.
    """
    body = _body_text(holder_code, "fromAssetLoaderUrl")
    if not body:
        return "fromAssetLoaderUrl not found (fail-closed)"
    rets = re.findall(r"\breturn\s+([^\n;]+)", body)
    publishing = [r.strip() for r in rets if r.strip() not in ("", "null")]
    if not publishing:
        return "fromAssetLoaderUrl never publishes a URL (fail-closed)"
    bad = [r for r in publishing if "encodeFileUrlPath(" not in r]
    if bad:
        return ("fromAssetLoaderUrl publishes %r — the reverse-mapped URL must be re-encoded "
                "(FILE_URL_PREFIX + encodeFileUrlPath(resolved)) so `currentUrl` stays "
                "byte-identical to the requested file:// URL; the raw path breaks the "
                "holder-reuse comparison and the decoding consumers" % bad[0][:80])
    return ""


# A `file://` URL is built for a consumer that parses it as a URL (WebView /
# asset loader / Intent / FileProvider), so its path must be percent-encoded at
# construction. These are the only two sanctioned operands: the encoder itself
# and the single builder that wraps it (`ui.sandbox.fileUrlFor`).
FILE_URL_BUILDERS = ("encodeFileUrlPath(", "fileUrlFor(")
FILE_URL_CONCAT_RE = re.compile(r'"file://"\s*\+')
# Both interpolations: `"file://${expr}"` and the plain `"file://$ident"`.
FILE_URL_TEMPLATE_RE = re.compile(r'"file://\$(?:\{|([A-Za-z_]\w*))')


def _interpolation_at(lit, pos):
    """Text inside `${ … }` starting at [pos] (just past the opening brace)."""
    depth, j = 1, pos
    while j < len(lit) and depth:
        if lit[j] == '{':
            depth += 1
        elif lit[j] == '}':
            depth -= 1
        j += 1
    return "" if depth else lit[pos:j - 1]


def file_url_construction_problem(ui_views):
    """F7 — no raw path may be concatenated into a `file://` URL.

    [fix/fileurl-contract] Third witness of the same family; each previous round
    fixed a *consumer* (§1021-V device-verified `#` truncation, §1023-H
    `Uri.parse(url).path`, fix/debt-sweep-1026 literal-`%XX` double-decode) while
    the construction sites kept `"file://" + file.absolutePath`. Returns
    `(problem, encoded_sites)` — [encoded_sites] is the positive anchor: the
    number of sanctioned construction sites the sweep did see.

    Operates on the LITERAL view (comments blanked, string contents kept), so
    the doc references to `"file://" + absolutePath` do not count. Concatenation
    through the `FILE_URL_PREFIX` constant is out of this criterion's reach — it
    is pinned by F6c at the one place that uses it.
    """
    offenders, encoded = [], 0
    for rel in sorted(ui_views):
        code, lit = ui_views[rel][0], ui_views[rel][1]
        for m in FILE_URL_CONCAT_RE.finditer(lit):
            pos = m.end()
            while pos < len(lit) and lit[pos] in " \t\r\n":
                pos += 1
            if lit[pos:pos + 40].startswith(FILE_URL_BUILDERS):
                encoded += 1
            else:
                offenders.append((rel, lit[pos:pos + 40].split("\n")[0].strip()))
        for m in FILE_URL_TEMPLATE_RE.finditer(lit):
            ident = m.group(1)
            if ident:
                # `"file://$path"` — the operand is one binding hop away.
                rhs = _binding_rhs(code, ident, lit)
                if rhs.startswith(FILE_URL_BUILDERS):
                    encoded += 1
                else:
                    offenders.append((rel, "$%s (bound to %r)" % (ident, rhs[:40])))
                continue
            inner = _interpolation_at(lit, m.end()).strip()
            if inner.startswith(FILE_URL_BUILDERS):
                encoded += 1
            else:
                offenders.append((rel, "${%s}" % inner[:40]))
    if offenders:
        rel, snippet = offenders[0]
        return ("%s builds a file:// URL by concatenating a raw path (%r) — its consumer "
                "parses it as a URL, so the path must be percent-encoded at construction: "
                "use ui.sandbox.fileUrlFor(file) / encodeFileUrlPath(path). A name containing "
                "#, ?, a space or a literal %%XX otherwise resolves to a different file"
                % (os.path.basename(rel), snippet), encoded)
    return "", encoded


# [W-08] The construction contract has a second half that the token sweep cannot
# see. F7 pins HOW a `file://` URL is built (through the encoder); it says
# nothing about what the construction site does with the result one statement
# later. `decodeUriPath(fileUrlFor(file))` keeps every token F7 looks for —
# `fileUrlFor(` is still the operand — and throws the encoding away:
#
#   * the holder compares `existing.currentUrl` (encoded, republished by
#     fromAssetLoaderUrl) against the RAW path this site passed in, so the
#     equality never holds and every second click destroys and rebuilds the
#     WebView (scroll position, form input: gone);
#   * a file literally named `50%23.html` is decoded HERE (`50#.html`) and then
#     decoded AGAIN by `toAssetLoaderUrl` → a name that exists nowhere.
#
# So the rule is directional, not lexical: an encoder's output may only be
# decoded by a CONSUMER (WebViewHolder / the sheet / the markdown resolver),
# never at the site that encoded it. The sweep is the derived ui/** + webapp/**
# walk, not a file list — the same reason F4/F7 are sweeps.
FILE_URL_ENCODERS = ("fileUrlFor(", "encodeFileUrlPath(")


def decode_after_encode_problem(ui_views):
    """F7b — no encoded `file://` URL is decoded back where it was built.

    Returns `(problem, decoded_sites)`: [decoded_sites] is the positive anchor
    (how many `decodeUriPath(` calls the sweep actually looked at), so a sweep
    that stops finding the file entirely cannot read as green.
    """
    decoded = 0
    for rel in sorted(ui_views):
        code, lit = ui_views[rel][0], ui_views[rel][1]
        for m in re.finditer(r"\bdecodeUriPath\s*\(", code):
            open_pos = m.end() - 1
            if match_bracket(code, open_pos) < 0:
                return ("%s: unbalanced `decodeUriPath(` (fail-closed)"
                        % os.path.basename(rel), decoded)
            decoded += 1
            spans = call_args(code, open_pos)
            arg = "".join(lit[a:b] for a, b in spans)
            for enc in FILE_URL_ENCODERS:
                if enc in arg:
                    return ("%s decodes the result of `%s` at the site that built it (%r) — an "
                            "encoded URL is only decoded by its CONSUMERS: here it makes the "
                            "holder's `currentUrl` equality never hold (the WebView is rebuilt "
                            "on every click) and double-decodes a literal %%XX file name into a "
                            "path that does not exist"
                            % (os.path.basename(rel), enc.rstrip("("),
                               " ".join(arg.split())[:80]), decoded)
    return "", decoded


def _dead_guard(cond):
    """Reason a guard decides nothing, or '' — constant folding covers
    `true || x`, `x || true`, `false && x`, `x && false`, `!true`, `1 == 2`."""
    folded = const_bool(strip_parens(cond))
    if folded is None:
        return ""
    return "the guard folds to `%s`" % ("true" if folded else "false")


def analyse(sheet_src, md_src, holder_src, ui_srcs, allowlist=None):
    """ui_srcs: {repo_root_relpath: RAW source} for EVERY .kt under ui/** AND
    webapp/** (main() merges the two walks; fixtures pass their own dict).

    Returns the criteria list [(name, ok, detail, line)]. A source the lexer
    cannot read makes the whole gate red (fail-closed) instead of falling back
    to a raw-text scan.
    """
    try:
        sheet_code, sheet_lit = _views(sheet_src)
        md_code, md_lit = _views(md_src)
        holder_code, holder_lit = _views(holder_src)
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
    problem2c = undecoded_strip_problem(md_fn_code, md_fn_lit) if md_fn_code else \
        "resolveMdMediaFile not found (fail-closed)"
    results.append(("F2c every minis:// strip in resolveMdMediaFile is decoded by decodeUriPath",
                    problem2c == "", problem2c or "all minis:// strips pass through the decoder", None))

    # --- F3: fromAssetLoaderUrl symmetric consumption ------------------
    problem3 = holder_cut_problem(holder_lit)
    results.append(("F3 fromAssetLoaderUrl cuts #/? before decodeUriPath",
                    problem3 == "", problem3 or "cut inside the decoder argument", None))
    problem3b = encoder_name_problem(holder_code, holder_lit)
    results.append(("F3b toAssetLoaderUrl encodes a name derived from the file URL",
                    problem3b == "", problem3b or "the encoded segment comes from the URL", None))
    problem6 = holder_root_problem(holder_code, holder_lit)
    results.append(("F6 holder asset root is derived from the prefix-stripped file:// URL",
                    problem6 == "", problem6 or "assetRootDir = <parent of the stripped path>", None))
    problem6b = holder_root_decode_problem(holder_code, holder_lit)
    results.append(("F6b holder asset root decodes the URL before File(…)",
                    problem6b == "",
                    problem6b or "File(decodeUriPath(initialUrl.removePrefix(\"file://\")))", None))
    problem6c = holder_publish_encode_problem(holder_code)
    results.append(("F6c fromAssetLoaderUrl publishes the encoded file:// form",
                    problem6c == "",
                    problem6c or "currentUrl = FILE_URL_PREFIX + encodeFileUrlPath(resolved)", None))

    # --- F7: construction contract [fix/fileurl-contract] ---------------
    problem7, encoded7 = file_url_construction_problem(ui_views)
    results.append(("F7 every file:// URL under ui/** + webapp/** is built by the path encoder",
                    problem7 == "",
                    problem7 or "%d encoded construction site(s), no raw concatenation"
                    % encoded7, None))

    # --- F7b: the encoder's output is consumed, not decoded, where it is built --
    problem7b, decoded7b = decode_after_encode_problem(ui_views)
    results.append((
        "F7b no encoded file:// URL is decoded back at the site that built it",
        problem7b == "",
        problem7b or "%d decodeUriPath(…) call(s) seen, none of them on an encoder result"
        % decoded7b, None))

    # --- F4: ui/** sweep with allowlist --------------------------------
    allowlist = ALLOWLIST if allowlist is None else allowlist
    for rel in sorted(ui_views):
        code = ui_views[rel][0]
        n = count_uri_parse_path(code)
        limit = allowlist.get(rel, 0)
        if rel in allowlist:
            if n != limit:
                results.append(
                    ("F4 allowlist exact for %s" % os.path.basename(rel), False,
                     ("allowlisted for %d occurrence(s) but found %d — %s"
                      % (limit, n,
                         "fix it and REMOVE the allowlist entry" if n == 0
                         else "raise the entry count consciously or fix the site")), None))
            else:
                results.append(("F4 allowlist exact for %s" % os.path.basename(rel), True,
                                 "exempted occurrence: %s" % SELFTEST_ALLOWLIST_REASON, None))
        elif n > 0:
            results.append(
                ("F4 no Uri.parse(...).path in %s" % os.path.basename(rel), False,
                 "found %d occurrence(s) — route file:// paths through ui.chat.decodeUriPath" % n,
                 None))

    # --- F4b: the exempted pin site still builds a resolvable deep link --
    shortcut = ui_views.get(SHORTCUT_REL)
    if shortcut is None:
        results.append(("F4b WebPreviewShortcut strips the /var/minis root from the pinned path",
                        False,
                        "%s is not under the swept ui/** tree (fail-closed)" % SHORTCUT_REL, None))
    else:
        problem4b = shortcut_strip_problem(shortcut[0], shortcut[1])
        results.append(("F4b WebPreviewShortcut strips the /var/minis root from the pinned path",
                        problem4b == "",
                        problem4b or "deep-link path built from the stripped sandbox path",
                        None))
        # --- F4c: the third file:// consumer decodes too ------------------
        # [W-07] `pin` is the third consumer of the `file://` contract (after
        # openExternalFromSheet and resolveMdMediaFile) and was the only one
        # without a decode-flow criterion: deleting `decodeUriPath(…)` left
        # `Uri.Builder().path(absPath)` to re-encode `%` — a pinned shortcut for
        # `我的 报告#1.html` pointed at a literal `%E6%88%91…` name that exists
        # nowhere. F4b stayed green through that, because the root strip it
        # checks is a different property.
        pin_code = _body_text(shortcut[0], "pin")
        pin_lit = _body_text(shortcut[1], "pin")
        if not pin_code:
            problem4c = "WebPreviewShortcut.pin not found (fail-closed)"
        else:
            problem4c = decode_flow_problem(
                pin_code, pin_lit, 'removePrefix("file://")',
                permit=_shortcut_chain_permit)
        results.append((
            'F4c WebPreviewShortcut.pin resolves the file:// URL via '
            'decodeUriPath(url.removePrefix("file://"))',
            problem4c == "",
            problem4c or "decoded path used as-is (root strip and edge trims permitted)",
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
    if (!stripped.startsWith("minis://")) return null
    val decoded = decodeUriPath(stripped.removePrefix("minis://"))
    val basename = decoded.substringAfterLast('/')
    return File(basename)
}
"""

# 01-3: only the cross-session fallback stops decoding
BREAKER_MD_FALLBACK_UNDECODED = FIXED_MD.replace(
    'val decoded = decodeUriPath(stripped.removePrefix("minis://"))',
    'val decoded = stripped.removePrefix("minis://")')
assert BREAKER_MD_FALLBACK_UNDECODED != FIXED_MD

REVERTED_MD = """package probe
fun resolveMdMediaFile(context: Context, stripped: String): File? {
    val primary: File? = when {
        stripped.startsWith("file://") -> File(Uri.parse(stripped).path ?: return null)
        else -> null
    }
}
"""

FIXED_HOLDER = """package probe
class Holder(private val initialUrl: String) {
    private val parentDir = java.io.File(
        decodeUriPath(initialUrl.removePrefix("file://")),
    ).parentFile
    private val assetRootDir: String? = parentDir?.absolutePath
}
internal fun toAssetLoaderUrl(fileUrl: String): String? {
    if (!fileUrl.startsWith(FILE_URL_PREFIX)) return null
    val name = fileUrl.removePrefix(FILE_URL_PREFIX).substringAfterLast('/')
    if (name.isEmpty()) return null
    return ASSET_LOADER_PREFIX + encodePathSegment(name)
}
internal fun fromAssetLoaderUrl(assetUrl: String, rootDir: String?): String? {
    val relative = decodeUriPath(
        assetUrl.removePrefix(ASSET_LOADER_PREFIX).substringBefore('#').substringBefore('?'),
    )
    return FILE_URL_PREFIX + encodeFileUrlPath(resolved)
}
"""

# 01-1: the init block drops the `file://` strip — the root becomes `file:/…`
BREAKER_HOLDER_ROOT = FIXED_HOLDER.replace(
    'decodeUriPath(initialUrl.removePrefix("file://"))',
    "initialUrl")
assert BREAKER_HOLDER_ROOT != FIXED_HOLDER

# 02-1: the forward mapping's name becomes a constant
BREAKER_HOLDER_NAME = FIXED_HOLDER.replace(
    "val name = fileUrl.removePrefix(FILE_URL_PREFIX).substringAfterLast('/')",
    'val name = ""')
assert BREAKER_HOLDER_NAME != FIXED_HOLDER

# [fix/fileurl-contract] strip kept, decode dropped — F6b alone.
BREAKER_HOLDER_NO_DECODE = FIXED_HOLDER.replace(
    'decodeUriPath(initialUrl.removePrefix("file://"))',
    'initialUrl.removePrefix("file://")')
assert BREAKER_HOLDER_NO_DECODE != FIXED_HOLDER

# [fix/fileurl-contract] publish the raw resolved path — F6c alone.
BREAKER_HOLDER_PUBLISH_RAW = FIXED_HOLDER.replace(
    "return FILE_URL_PREFIX + encodeFileUrlPath(resolved)",
    "return FILE_URL_PREFIX + resolved")
assert BREAKER_HOLDER_PUBLISH_RAW != FIXED_HOLDER

# [fix/fileurl-contract] F7 fixtures: the three construction spellings.
RAW_CONCAT_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/RawFileUrlFixture.kt"
RAW_CONCAT_FILE = """package probe
fun openRaw(file: java.io.File) {
    webView.loadUrl("file://" + file.absolutePath)
}
"""
RAW_TEMPLATE_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/RawFileUrlTemplateFixture.kt"
RAW_TEMPLATE_FILE = """package probe
fun openRaw(file: java.io.File) {
    webView.loadUrl("file://${file.absolutePath}")
}
"""
RAW_DOLLAR_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/RawFileUrlDollarFixture.kt"
RAW_DOLLAR_FILE = """package probe
fun openRaw(file: java.io.File) {
    webView.loadUrl("file://$path")
}
"""
ENCODED_DOLLAR_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/EncodedFileUrlDollarFixture.kt"
ENCODED_DOLLAR_FILE = """package probe
fun openEncoded(file: java.io.File) {
    val encoded = encodeFileUrlPath(file.absolutePath)
    webView.loadUrl("file://$encoded")
}
"""
ENCODED_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/EncodedFileUrlFixture.kt"
ENCODED_FILE = """package probe
fun openEncoded(file: java.io.File) {
    webView.loadUrl("file://" + encodeFileUrlPath(file.absolutePath))
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

ALLOWLIST_REL = next(iter(SELFTEST_ALLOWLIST))
# [W-07] The fixture keeps ONE `Uri.parse(url).path` so the F4 allowlist arms
# still have a subject, and now also carries the decode the real `pin` performs
# — otherwise F4c would be red on the selftest's own "fixed" tree and every arm
# below would be measuring the fixture instead of its mutation.
ALLOWLISTED_FILE = """package probe
internal fun pin(
    context: Context,
    url: String,
    sessionId: String,
    title: String,
) {
    val legacyPath = Uri.parse(url).path.orEmpty()
    val absPath = decodeUriPath(url.removePrefix("file://"))
    if (absPath.isBlank()) return
    if (absPath.trimEnd('/') == "/var/minis") return
    val resourcePath = absPath.removePrefix("/var/minis").let {
        if (it.startsWith("/")) it else "/$it"
    }
    val deepLink = Uri.Builder()
        .scheme("minis")
        .authority("session")
        .path("/$sessionId$resourcePath")
        .build()
}
"""

# W-07: the third `file://` consumer loses the decode — F4b still sees the root
# strip, so nothing but F4c can notice.
BREAKER_SHORTCUT_NO_DECODE = ALLOWLISTED_FILE.replace(
    'val absPath = decodeUriPath(url.removePrefix("file://"))',
    'val absPath = url.removePrefix("file://")')
assert BREAKER_SHORTCUT_NO_DECODE != ALLOWLISTED_FILE

# W-08: the construction site decodes the URL it just encoded. Every token F7
# looks for is intact (`fileUrlFor(` is still the operand).
BREAKER_CHAT_DECODE_WRAP = CHAT_SCREEN_CLEAN + """
internal fun openHtmlPreview(file: java.io.File): String {
    val url = decodeUriPath(com.rikkaminis.app.ui.sandbox.fileUrlFor(file))
    return url
}
"""

# F7b precision: a decoder whose argument is a fileUrl-like PARAMETER (`fileUrl`,
# not the builder call). WebViewHolder.fromAssetLoaderUrl has exactly this shape
# and must never be flagged.
DECODE_PARAM_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/DecodedParamFixture.kt"
DECODE_PARAM_FILE = """package probe
internal fun forward(fileUrl: String): String {
    val name = decodeUriPath(fileUrl.removePrefix(FILE_URL_PREFIX).substringAfterLast('/'))
    return encodeFileUrlPath(name)
}
"""

# 01-2: the deep link stops stripping the sandbox root
BREAKER_SHORTCUT_UNSTRIPPED = ALLOWLISTED_FILE.replace(
    'absPath.removePrefix("/var/minis").let {', "absPath.let {")
assert BREAKER_SHORTCUT_UNSTRIPPED != ALLOWLISTED_FILE
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
F4C = ('F4c WebPreviewShortcut.pin resolves the file:// URL via '
       'decodeUriPath(url.removePrefix("file://"))')
F7B = "F7b no encoded file:// URL is decoded back at the site that built it"


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
        return analyse(sheet, md, holder, ui, SELFTEST_ALLOWLIST)

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

    # ---- r3 round-3b witness arms (01-1 / 01-2 / 01-3 / 02-1) -----------
    # 01-1: the loader root loses the file:// strip (F6 only).
    m = run(FIXED_SHEET, FIXED_MD, BREAKER_HOLDER_ROOT, dict_of({}))
    reds = sorted(c for c, ok, _d, _l in m if not ok)
    expect(reds == ["F6 holder asset root is derived from the prefix-stripped file:// URL"],
           "r3-01-1 holder-root: exactly F6 must be RED, got %s" % reds)

    # 01-2: the pinned deep link stops stripping /var/minis (F4b only).
    m = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER,
            dict_of({ALLOWLIST_REL: BREAKER_SHORTCUT_UNSTRIPPED}))
    reds = sorted(c for c, ok, _d, _l in m if not ok)
    expect(reds == ["F4b WebPreviewShortcut strips the /var/minis root from the pinned path"],
           "r3-01-2 shortcut-unstripped: exactly F4b must be RED, got %s" % reds)

    # 01-3: only the cross-session minis:// fallback stops decoding (F2c only).
    m = run(FIXED_SHEET, BREAKER_MD_FALLBACK_UNDECODED, FIXED_HOLDER, dict_of({}))
    reds = sorted(c for c, ok, _d, _l in m if not ok)
    expect(reds == ["F2c every minis:// strip in resolveMdMediaFile is decoded by decodeUriPath"],
           "r3-01-3 fallback-undecoded: exactly F2c must be RED, got %s" % reds)

    # ---- [fix/fileurl-contract] construction contract arms (F6b / F6c / F7) --
    # 04-1: strip kept, decode dropped — the encoded URL reaches File(…).
    m = run(FIXED_SHEET, FIXED_MD, BREAKER_HOLDER_NO_DECODE, dict_of({}))
    reds = sorted(c for c, ok, _d, _l in m if not ok)
    expect(reds == ["F6b holder asset root decodes the URL before File(…)"],
           "fileurl-04-1 no-decode root: exactly F6b must be RED, got %s" % reds)

    # 04-2: the reverse mapping publishes the raw path again (holder reuse).
    m = run(FIXED_SHEET, FIXED_MD, BREAKER_HOLDER_PUBLISH_RAW, dict_of({}))
    reds = sorted(c for c, ok, _d, _l in m if not ok)
    expect(reds == ["F6c fromAssetLoaderUrl publishes the encoded file:// form"],
           "fileurl-04-2 publish-raw: exactly F6c must be RED, got %s" % reds)

    # 04-3: a chat-side construction site goes back to raw concatenation.
    m = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER, dict_of({RAW_CONCAT_REL: RAW_CONCAT_FILE}))
    reds = sorted(c for c, ok, _d, _l in m if not ok)
    expect(reds == ["F7 every file:// URL under ui/** + webapp/** is built by the path encoder"],
           "fileurl-04-3 raw-concat: exactly F7 must be RED, got %s" % reds)

    # 04-4: the same defect in the string-template spelling.
    m = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER, dict_of({RAW_TEMPLATE_REL: RAW_TEMPLATE_FILE}))
    reds = sorted(c for c, ok, _d, _l in m if not ok)
    expect(reds == ["F7 every file:// URL under ui/** + webapp/** is built by the path encoder"],
           "fileurl-04-4 raw-template: exactly F7 must be RED, got %s" % reds)

    # 04-4b: the plain-interpolation spelling of the same defect.
    m = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER, dict_of({RAW_DOLLAR_REL: RAW_DOLLAR_FILE}))
    reds = sorted(c for c, ok, _d, _l in m if not ok)
    expect(reds == ["F7 every file:// URL under ui/** + webapp/** is built by the path encoder"],
           "fileurl-04-4b raw-$interp: exactly F7 must be RED, got %s" % reds)

    # 04-4c: precision — the same spelling IS sanctioned when the operand is the
    # encoder's result through one binding hop.
    m = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER, dict_of({ENCODED_DOLLAR_REL: ENCODED_DOLLAR_FILE}))
    expect(all_green(m),
           "fileurl-04-4c encoded-$interp: one-hop encoder operand must stay GREEN, got %s"
           % [c for c, ok, _d, _l in m if not ok])

    # 04-5: precision — the encoder's own operand is the sanctioned form.
    m = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER, dict_of({ENCODED_REL: ENCODED_FILE}))
    expect(all_green(m),
           "fileurl-04-5 encoded-operand: the sanctioned form must stay GREEN, got %s"
           % [c for c, ok, _d, _l in m if not ok])

    # 02-1: the forward mapping encodes a constant name (F3b only).
    m = run(FIXED_SHEET, FIXED_MD, BREAKER_HOLDER_NAME, dict_of({}))
    reds = sorted(c for c, ok, _d, _l in m if not ok)
    expect(reds == ["F3b toAssetLoaderUrl encodes a name derived from the file URL"],
           "r3-02-1 constant-name: exactly F3b must be RED, got %s" % reds)

    # ---- 2026-10-27 witness wave (W-07 / W-08) ---------------------------
    # W-07: the THIRD file:// consumer stops decoding. F4b still sees the root
    # strip, F4 still sees no `Uri.parse(...).path`, so F4c is the only subject.
    m = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER,
            dict_of({ALLOWLIST_REL: BREAKER_SHORTCUT_NO_DECODE}))
    reds = sorted(c for c, ok, _d, _l in m if not ok)
    expect(reds == [F4C],
           "w07-shortcut-no-decode: exactly F4c must be RED, got %s" % reds)

    # W-08: the construction site decodes its own encoder output. F7 stays green
    # (the operand IS `fileUrlFor(...)`), F5c stays green — only F7b sees it.
    m = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER,
            dict_of({CHAT_SCREEN_REL: BREAKER_CHAT_DECODE_WRAP}))
    reds = sorted(c for c, ok, _d, _l in m if not ok)
    expect(reds == [F7B],
           "w08-decode-after-encode: exactly F7b must be RED, got %s" % reds)

    # W-08 precision: a decoder fed a `fileUrl` PARAMETER is the real
    # WebViewHolder shape — banning it would make the gate red on the fixed tree.
    m = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER, dict_of({DECODE_PARAM_REL: DECODE_PARAM_FILE}))
    expect(all_green(m),
           "w08-precision: a decode of a fileUrl parameter must stay GREEN, got %s"
           % [c for c, ok, _d, _l in m if not ok])

    # W-07 precision: the pin's root strip and edge trim are legal chains on the
    # decoded path (the fixture above exercises both and must stay green).
    m = run(FIXED_SHEET, FIXED_MD, FIXED_HOLDER, dict_of({}))
    expect(crit(m, F4C) is True,
           "w07-precision: the fixed pin (root strip + trim) must stay GREEN")

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
          "holder-post-cut=RED, raw-string-comment=RED, fail-closed=RED, "
          "fileurl-no-decode-root=RED, fileurl-publish-raw=RED, "
          "fileurl-raw-concat=RED, fileurl-raw-template=RED, "
          "fileurl-raw-dollar=RED, fileurl-encoded-dollar=GREEN, "
          "fileurl-encoded-operand=GREEN, w07-shortcut-no-decode=RED, "
          "w08-decode-after-encode=RED, w08-decoder-precision=GREEN, "
          "w07-pin-permit=GREEN")
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
    for p in (sheet_p, md_p, holder_p):
        if not os.path.isfile(p):
            print("FAIL required file missing: %s" % p)
            return 1

    sheet_code = open(sheet_p, encoding="utf-8").read()
    md_code = open(md_p, encoding="utf-8").read()
    holder_code = open(holder_p, encoding="utf-8").read()

    # [batch-7] raw sources: analyse() derives the structure/literal views
    # itself (mask_code / mask_comments), so a `}` or a `/*` inside a string
    # cannot truncate a body any more.
    ui_codes = {}
    for base_rel in (UI_ROOT_REL, WEBAPP_ROOT_REL):
        base = os.path.join(root, base_rel)
        if not os.path.isdir(base):
            print("FAIL swept root missing: %s" % base_rel)
            return 1
        for dirpath, _dirnames, filenames in os.walk(base):
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
    print("swept %d .kt file(s) under %s + %s" % (len(ui_codes), UI_ROOT_REL, WEBAPP_ROOT_REL))
    if failed:
        print("file uri parse guard: RED — Uri.parse(...).path on local file:// "
              "paths truncates #/? and silently no-ops; resolve via ui.chat.decodeUriPath")
        return 1
    print("GATE=GREEN file_uri_parse_guard")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
