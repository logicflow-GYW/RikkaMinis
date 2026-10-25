#!/usr/bin/env python3
"""Legacy flat-chat pipeline guard — RikkaMinis CI scan gate.

## Why this gate exists

`AGGREGATE_MESSAGE_ITEMS = true` (ChatScreen.kt) makes the pre-aggregate
render pipeline dead at runtime, but the code is deliberately kept as the
Stage-E fallback (docs/scroll-follow-simplification.md). The dead code is not
the hazard. The hazard is that a NEW call site picks the legacy path by
accident and fails SILENTLY.

2026-09-13, for real: the cold-open prewarm extracted its source markdown with
`(item as? FlatChatItem.AssistantMarkdownBlock)?.rawText`, but the aggregate
generator emits `AssistantMessageItem` — so the list was always empty, the
prewarm never ran, and no error, warning or log line ever appeared. It was
only found by device forensics months later (`prewarmMs=-1` on every open).

A green unit test does not catch this (StableChatRowLedger's own header says
so): the tests target the dead implementation. This gate makes the mistake
loud instead — referencing a legacy symbol from a file that is not allowed to
is a build failure.

## Allow-list (file level)

  ui/chat/legacy/**        the isolated legacy package itself
  src/test/**              the tests that pin the legacy behaviour
  ui/chat/ChatFlatItems.kt         the 10 legacy row classes must stay here
                                   (Kotlin sealed subclasses cannot live in a
                                   different file outside the package) plus
                                   owningMessageId's dispatch
  ui/chat/ChatScreen.kt            the dead legacy collect段 (kept verbatim as
                                   the Stage-E fallback)
  ui/chat/ChatScreenUtils.kt       isCompacted dispatch
  ui/chat/ChatAssistantMessageUI.kt  the ToolCallRunGroup definition

Anything else that needs to touch a legacy symbol must justify it inline with
a `legacy-ok: <reason>` comment on the same or the previous line — the
intended use is a single-point source function that must understand both
pipelines (see markdownSourcesForRow in ChatColdOpenPrewarm.kt).

Usage: python3 scripts/scan/legacy_pipeline_guard.py <repo_root>
Exit:  0 = clean, 1 = violation(s) found
"""
import os
import re
import sys

# [wide4/10] Comment/string masking is the shared lexer's job. This gate used
# `BLOCK_COMMENT_RE` (a bare `/*...*/` regex) and its own character state
# machine in two different places; a string containing `/*` blanked hundreds of
# real lines in one and mis-nested block comments in the other.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import analyse as _lex_analyse  # noqa: E402
from _kotlin_ast import match_bracket as _match_bracket  # noqa: E402

# Legacy row classes: no production code path constructs them any more
# (buildAggregateChatItems emits only UserBubble + AssistantMessageItem).
LEGACY_ROWS = [
    "AssistantHeader", "AssistantMarkdownBlock", "AssistantThinking",
    "AssistantToolRunGroup", "AssistantInfo", "AssistantTyping",
    "AssistantError", "AssistantLegacyContent",
]

# Symbols defined outside the legacy package that belong to the dead path.
EXTRA_SYMBOLS = ["ToolCallRunGroup"]

# The legacy package is runtime-dead and FROZEN. This is the inventory the
# declaration parser must reproduce, checked in both directions: a name that
# stops being parsed is a scan surface that silently collapsed (wide3 witness
# C2_legacy_symbols), and a name that appears is new dead code that needs a
# human look. Both are red on purpose — dead modules do not grow.
FROZEN_LEGACY_SYMBOLS = frozenset({
    "AppendOnlyMarkdownSegmenter", "AudioWaveformView",
    "MINIMUM_STREAMING_OVERLAP_LENGTH", "StableChatRowLedger",
    "StableMarkdownSlot", "buildFlatChatItems", "coalesceMarkdownFragments",
    "commonPrefixLength", "isFenceFragment", "longestSuffixPrefixOverlap",
    "looksLikeDivergentStreamingSnapshot", "mergeAgentTextSnapshot",
    "mergeLegacyStreamingText", "shouldIgnoreRegressiveStreamingSnapshot",
    "splitMarkdownIntoBlockTexts",
})

# The frozen package's file set. Adding a file to a runtime-dead module is the
# same hazard as adding a symbol; both stop being checked the moment the scan
# surface quietly changes shape.
FROZEN_LEGACY_FILES = frozenset({
    "AppendOnlyMarkdownSegmenter.kt", "AudioWaveformView.kt",
    "LegacyFlatChatBuilder.kt", "MarkdownStreamMerge.kt", "StableChatRowLedger.kt",
})

ALLOWED_FILES = {
    "com/rikkaminis/app/ui/chat/ChatFlatItems.kt",
    "com/rikkaminis/app/ui/chat/ChatScreen.kt",
    "com/rikkaminis/app/ui/chat/ChatScreenUtils.kt",
    "com/rikkaminis/app/ui/chat/ChatAssistantMessageUI.kt",
}

ALLOWED_PREFIXES = [
    "com/rikkaminis/app/ui/chat/legacy/",
]

ESCAPE_RE = re.compile(r"legacy-ok\s*:")


def _views(text):
    """(comments-blanked view, code view, reason) from the shared Kotlin lexer.

    `comments` keeps string literals (a reference spelled in a string is still a
    reference); `code` blanks both (structure: brace depth, block headers).

    [wide4/10] The old `strip_comments` ran `BLOCK_COMMENT_RE` — a bare regex
    with no idea what a string literal is. A file holding `val open = "/*"` and,
    a few hundred lines later, `val close = "*/"` had everything between them —
    including a live reference to a dead-pipeline row class — replaced by blank
    lines, and the gate reported "no new references" (the classic `"*/*"` /
    `"video/*"` swallow, already documented inside this repo's own gate
    comments). The shared lexer masks real comments only; a source it cannot lex
    is a violation, never a silently emptied scan.
    """
    ok, reason, views = _lex_analyse(text)
    if not ok:
        return None, None, reason
    return views["comments"], views["code"], None



_DECL_MODIFIERS = (
    "public", "internal", "private", "protected", "open", "final", "abstract",
    "sealed", "data", "enum", "value", "annotation", "inline", "suspend",
    "operator", "infix", "tailrec", "external", "const", "lateinit", "override",
    "actual", "expect", "companion", "noinline", "crossinline", "vararg",
)
_DECL_ANNOTATION_RE = re.compile(r"(?m)^[ \t]*@[\w.]+(?:\([^()\n]*\))?[ \t]*$")
_DECL_ANNOT = r"@[\w.]+(?:\((?:[^()]|\([^()]*\))*\))?"
_DECL_RE = re.compile(
    r"(?m)^[ \t]*(?:(?:%s|%s)\s+)*(?:fun|class|interface|object|val|var|typealias)\s+"
    r"(?:<[^>\n]*>\s*)?(?:[\w.]+\.)?([A-Za-z_]\w*)"
    % (_DECL_ANNOT, "|".join(_DECL_MODIFIERS))
)
# A declaration head that OPENS a new legacy-holding surface (top-level or
# member). The escape hatch is for *use sites* inside a body, never for a
# definition that binds the dead types (wide3 witness C3_escape_hatch).
_DECL_SITE_RE = re.compile(
    r"^[ \t]*(?:(?:%s|%s)\s+)*(?:val|var|fun|class|interface|object|typealias)\b"
    % (_DECL_ANNOT, "|".join(_DECL_MODIFIERS))
)
_MEMBER_DECL_RE = re.compile(
    r"\b(?:val|var|fun|class|object|interface|typealias)\s+[A-Za-z_`]")
_FUN_HEAD_RE = re.compile(r"\bfun\b")
_TYPE_HEAD_RE = re.compile(r"\b(?:class|object|interface|companion)\b")


def _blocks(masked):
    """(open, close, kind) for every `{ … }`; kind ∈ {'fun','type','other'}.

    `kind` comes from the block HEADER (text between the previous statement
    boundary and the `{`): `fun … {` opens a function body, `class … {` a type
    body, everything else (`run {`, a lambda, `when {`, an initializer) is
    'other'.

    [wide4/10] The escape hatch is only meaningful for a USE SITE inside a
    FUNCTION BODY. The old criterion was `depths[start] > 0` — a raw brace count
    — and any top-level property initializer forges it:
    `internal val leaked = run {` + `// legacy-ok:` + the lambda body exempted a
    brand-new TOP-LEVEL declaration that binds a dead-pipeline type.
    """
    out = []
    n = len(masked)
    for i, ch in enumerate(masked):
        if ch != "{":
            continue
        head_start = max(masked.rfind("\n", 0, i), masked.rfind(";", 0, i),
                         masked.rfind("{", 0, i), masked.rfind("}", 0, i)) + 1
        head = masked[head_start:i]
        if _FUN_HEAD_RE.search(head):
            kind = "fun"          # also `fun f() = when (x) { … }`
        elif _TYPE_HEAD_RE.search(head):
            kind = "type"
        else:
            kind = "other"
        close = _match_bracket(masked, i)
        out.append((i, close if close > 0 else n - 1, kind))
    return out


def escape_regions(raw_lines, masked):
    """[(start, end)] line ranges (0-based, inclusive) a `legacy-ok:` justifies.

    Rules, all fail-closed:
      * the marker lives on the code line itself, or anywhere in the contiguous
        run of comment lines directly ABOVE the escaped code (a multi-line
        justification is the shipped shape) — a blank line breaks the run;
      * the escaped code must be a USE SITE inside a FUNCTION BODY: a top-level
        line, a class-body member, a declaration head, or any line that
        introduces a declaration is never exempted (that is a new
        legacy-holding surface, which is the hazard this gate exists for);
      * the region ends at the first blank line, the moment brace depth drops
        below the escaped line's own depth, or the first line that would add a
        declaration — it can no longer swallow a paragraph of unrelated code.
    """
    text = "\n".join(masked)
    blocks = _blocks(text)
    depths, depth = [], 0
    for line in masked:
        depths.append(depth)
        depth += line.count("{") - line.count("}")
    offsets = []
    pos = 0
    for line in masked:
        offsets.append(pos)
        pos += len(line) + 1

    def use_site(idx):
        if not any(o < offsets[idx] < c and k == "fun" for o, c, k in blocks):
            return False          # not inside a function body
        if _DECL_SITE_RE.match(masked[idx]) or _MEMBER_DECL_RE.search(masked[idx]):
            return False          # opens a new declaration surface
        return True

    regions = []
    n = len(raw_lines)
    i = 0
    while i < n:
        line = raw_lines[i]
        if not line.strip():
            i += 1
            continue
        is_comment = line.lstrip().startswith("//")
        if is_comment and ESCAPE_RE.search(line):
            j = i
            while (j + 1 < n and raw_lines[j + 1].lstrip().startswith("//")
                   and raw_lines[j + 1].strip()):
                j += 1
            start = j + 1
            if start >= n or not raw_lines[start].strip() \
                    or raw_lines[start].lstrip().startswith("//"):
                i = j + 1
                continue
            if not use_site(start):
                i = j + 1
                continue
            base = depths[start]
            end = start
            k = start
            while k < n and masked[k].strip() and depths[k] >= base:
                if k > start and not use_site(k):
                    break
                end = k
                k += 1
            regions.append((start, end))
            i = end + 1
            continue
        if not is_comment and ESCAPE_RE.search(line) and use_site(i):
            # A trailing `// legacy-ok:` is the same justification as a leading
            # one — but ONLY on a use site. It used to be appended blindly, so
            # `internal val accidental = … // legacy-ok: x` exempted a brand-new
            # TOP-LEVEL declaration (wide4/10 witness), and a run of lines after
            # such a marker was exempted wholesale.
            regions.append((i, i))
        i += 1
    return regions



def scan(root):
    """Violations for references to the runtime-dead pipeline.

    [wide4/10] Completeness is now enforced in BOTH directions: the parsed
    declarations are compared with `FROZEN_LEGACY_SYMBOLS`, and the legacy
    package's file set with `FROZEN_LEGACY_FILES`. The constant existed for a
    whole round with no reader, so the frozen package could grow a new dead
    function — or a symbol could stop being parsed — in complete silence.
    """
    violations = []
    derived, per_file, drift = _legacy_scan(root)
    violations.extend(drift)
    if per_file:
        extra_files = sorted(set(per_file) - FROZEN_LEGACY_FILES)
        if extra_files:
            violations.append(
                "ui/chat/legacy: the frozen (runtime-dead) package gained file(s) %s — "
                "dead modules do not grow; delete the file or unfreeze the package "
                "deliberately" % extra_files)
        if set(per_file) == set(FROZEN_LEGACY_FILES):
            missing = sorted(FROZEN_LEGACY_SYMBOLS - derived)
            extra = sorted(derived - FROZEN_LEGACY_SYMBOLS)
            if missing or extra:
                violations.append(
                    "ui/chat/legacy: parsed declarations and the frozen inventory "
                    "disagree — missing=%s extra=%s. A name that stops being parsed is "
                    "a scan surface that silently collapsed; a name that appears is new "
                    "dead code that needs a human look." % (missing, extra))
    for fn, found in sorted(per_file.items()):
        if not found:
            # Fail-closed per file: this file still declares something, yet the
            # parser harvested no symbol from it. A silently empty set makes
            # every reference in it look legal, which is the failure mode of the
            # old head-anchored scan.
            violations.append(
                "ui/chat/legacy/%s declared no parseable top-level symbol — the scan "
                "surface collapsed for that file (fail-closed)" % fn
            )
    symbols = derived | set(LEGACY_ROWS) | set(EXTRA_SYMBOLS)
    # ONE pattern family for every symbol, bare name included: `import
    # …FlatChatItem.*` + `AssistantMarkdownBlock` is the same reference as
    # `FlatChatItem.AssistantMarkdownBlock`, and the old split (qualified
    # patterns for the row classes, bare patterns for everything EXCEPT them)
    # left exactly that spelling unguarded (wide3 witness C1_legacy_rows).
    patterns = [
        (re.compile(r"\b" + re.escape(s) + r"\b"), s)
        for s in sorted(symbols)
    ]

    for base, _dirs, files in os.walk(os.path.join(root, "src/android/app/src")):
        for fn in sorted(files):
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(base, fn)
            rel = os.path.relpath(path, os.path.join(root, "src/android/app/src"))
            posix = rel.replace(os.sep, "/")
            if "/test/" in posix or posix.startswith("test/"):
                continue                      # tests pin the legacy behaviour
            if any(posix.endswith(a) for a in ALLOWED_FILES):
                continue
            if any(a in posix for a in ALLOWED_PREFIXES):
                continue
            raw = open(path, encoding="utf-8", errors="replace").read()
            lit, masked_text, reason = _views(raw)
            if lit is None:
                violations.append(
                    "%s  this gate cannot lex the file (%s) — it refuses to scan a "
                    "source whose string/comment structure it cannot resolve"
                    % (posix, reason))
                continue
            raw_lines = raw.split("\n")
            lines = lit.split("\n")
            masked = masked_text.split("\n")
            exempt = set()
            for start, end in escape_regions(raw_lines, masked):
                exempt.update(range(start, end + 1))
            for i, line in enumerate(lines):
                if i in exempt or not line.strip():
                    continue
                hit = set()
                for pat, label in patterns:
                    if label in hit:
                        continue
                    if pat.search(line):
                        hit.add(label)
                        violations.append(f"{posix}:{i + 1}  {label}  ->  {line.strip()[:100]}")
    return violations



_MODIFIER_ONLY_RE = re.compile(
    r"^(?:(?:@[\w.]+(?:\((?:[^()]|\([^()]*\))*\))?)|(?:%s))\s*$"
    % "|".join(_DECL_MODIFIERS)
)


def _legacy_scan(root):
    """(names, per_file, drift) for ui/chat/legacy/*.kt.

    `names` is the union of every TOP-LEVEL declaration (auto-tracked);
    `per_file` maps file name -> set, so the caller can tell "this file parsed
    to nothing" (parser rot) from "this package has no declarations";
    `drift` lists declaration heads that are not at column 0 — the legacy
    package is frozen and runtime-dead, so a reformat of a declaration head is
    an edit of a dead module that nobody would otherwise notice.

    [wide4/10] Two revisions: the masked view is the shared lexer's (the local
    state machine mis-nested block comments), and a modifier on its own line is
    carried onto the declaration below it — the inventory comparison that now
    guards this package is only as good as the parser feeding it.
    """
    legacy_dir = os.path.join(
        root, "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/legacy"
    )
    names = set()
    per_file = {}
    drift = []
    if not os.path.isdir(legacy_dir):
        return names, per_file, drift
    for fn in sorted(os.listdir(legacy_dir)):
        if not fn.endswith(".kt"):
            continue
        src = open(os.path.join(legacy_dir, fn), encoding="utf-8", errors="replace").read()
        _lit, clean_src, reason = _views(src)
        if clean_src is None:
            per_file[fn] = set()
            drift.append(
                "ui/chat/legacy/%s  cannot be lexed (%s) — the declaration inventory "
                "is unknowable (fail-closed)" % (fn, reason))
            continue
        clean = _DECL_ANNOTATION_RE.sub("", clean_src)
        found = set()
        # Only TOP-LEVEL declarations count: a local `val out = …` inside a
        # function is not a symbol anyone can reference, and harvesting those
        # would turn common names (`out`, `i`, `parts`) into gate patterns.
        depth = paren = 0
        pending = ""
        for idx, line in enumerate(clean.split("\n")):
            probe = (pending + " " + line) if pending else line
            if depth == 0 and paren == 0:
                m = _DECL_RE.match(probe)
                if m:
                    found.add(m.group(1))
                    if line[:1] in (" ", "\t"):
                        drift.append(
                            "%s:%d  declaration head is indented (%s) — the frozen "
                            "legacy package was edited; confirm the reference is "
                            "still tracked" % (fn, idx + 1, m.group(1))
                        )
            stripped = line.strip()
            pending = stripped if (stripped and _MODIFIER_ONLY_RE.match(stripped)) else ""
            # Paren depth matters too: the parameter list of
            # `data class Slot(\n    val ordinal: Int,\n)` sits at brace depth 0
            # and would otherwise be harvested as a top-level declaration.
            depth += line.count("{") - line.count("}")
            paren += line.count("(") - line.count(")")
        per_file[fn] = found
        names |= found
    return names, per_file, drift



def legacy_exported_symbols(root):
    names, per_file, _drift = _legacy_scan(root)
    return names, len(per_file)

def main():
    root = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")
    violations = scan(root)
    if violations:
        print(f"❌ Legacy pipeline guard: {len(violations)} violation(s)")
        print("   The pre-aggregate pipeline is RUNTIME-DEAD (AGGREGATE_MESSAGE_ITEMS")
        print("   = true). Reference it only from ui/chat/legacy/, tests, or the")
        print("   allow-listed files — or justify inline with `legacy-ok: <reason>`.")
        for v in violations:
            print("     " + v)
        return 1
    print("✅ Legacy pipeline guard: no new references to the dead path")
    return 0


def _fixture(files):
    import tempfile
    root = tempfile.mkdtemp(prefix="legacy-selftest-")
    for rel, content in files.items():
        path = os.path.join(root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
    return root


LEGACY_PKG = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/legacy"
CHAT_PKG = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat"

_SELFTEST_LEGACY_PLAIN = (
    "package com.rikkaminis.app.ui.chat.legacy\n"
    "internal class StableChatRowLedger {\n"
    "    fun record() {}\n"
    "}\n"
)

_SELFTEST_LEGACY_ANNOTATED = (
    "package com.rikkaminis.app.ui.chat.legacy\n"
    "\n"
    " @Deprecated(\"dead pipeline\")\n"
    " fun AudioWaveformView() {}\n"
)


_FROZEN_GROUPS = {
    "AppendOnlyMarkdownSegmenter.kt": [
        "AppendOnlyMarkdownSegmenter", "commonPrefixLength", "longestSuffixPrefixOverlap"],
    "AudioWaveformView.kt": ["AudioWaveformView", "MINIMUM_STREAMING_OVERLAP_LENGTH"],
    "LegacyFlatChatBuilder.kt": [
        "buildFlatChatItems", "splitMarkdownIntoBlockTexts", "coalesceMarkdownFragments"],
    "MarkdownStreamMerge.kt": [
        "mergeLegacyStreamingText", "isFenceFragment",
        "looksLikeDivergentStreamingSnapshot", "mergeAgentTextSnapshot"],
    "StableChatRowLedger.kt": [
        "StableChatRowLedger", "StableMarkdownSlot",
        "shouldIgnoreRegressiveStreamingSnapshot"],
}


def _frozen_fixture():
    """A legacy package whose declarations reproduce FROZEN_LEGACY_SYMBOLS.

    The completeness arm needs a tree that is BOTH the full frozen file set and
    the full symbol inventory, otherwise the comparison is skipped by design.
    """
    files = {}
    for fn, syms in _FROZEN_GROUPS.items():
        body = ["package com.rikkaminis.app.ui.chat.legacy", ""]
        for sym in syms:
            if sym[:1].isupper():
                body.append("internal class %s" % sym)
            else:
                body.append("internal fun %s() {}" % sym)
        files[os.path.join(LEGACY_PKG, fn)] = "\n".join(body) + "\n"
    return files


def _consumer(body):
    return (
        "package com.rikkaminis.app.ui.chat\n"
        "class ChatViewModel {\n"
        "    fun send() {\n"
        + body +
        "    }\n"
        "}\n"
    )


def _top_level(body):
    return "package com.rikkaminis.app.ui.chat\n" + body


def _selftest():
    import shutil

    cases = [
        ("comment-only mention is legal (control)",
         {os.path.join(LEGACY_PKG, "StableChatRowLedger.kt"): _SELFTEST_LEGACY_PLAIN,
          os.path.join(CHAT_PKG, "ChatViewModel.kt"): _consumer(
              "        // StableChatRowLedger mentioned in a comment\n")},
         0),
        ("qualified live reference is caught (control)",
         {os.path.join(LEGACY_PKG, "StableChatRowLedger.kt"): _SELFTEST_LEGACY_PLAIN,
          os.path.join(CHAT_PKG, "ChatViewModel.kt"): _consumer(
              "        StableChatRowLedger().record()\n")},
         1),
        ("wildcard import + unqualified row name is caught (spelling)",
         {os.path.join(CHAT_PKG, "ChatColdOpenPrewarm.kt"): _top_level(
             "import com.rikkaminis.app.ui.chat.FlatChatItem.*\n"
             "internal fun isLegacyBlock(x: FlatChatItem): Boolean = "
             "x is AssistantMarkdownBlock\n")},
         1),
        ("indented @Deprecated declaration is still tracked (parser-death)",
         {os.path.join(LEGACY_PKG, "AudioWaveformView.kt"): _SELFTEST_LEGACY_ANNOTATED,
          os.path.join(CHAT_PKG, "ChatViewModel.kt"): _consumer(
              "        AudioWaveformView()\n")},
         1),
        ("legacy-ok above a USE SITE inside a body still exempts (control)",
         {os.path.join(CHAT_PKG, "ChatColdOpenPrewarm.kt"): _top_level(
             "internal fun sources(item: FlatChatItem): Int = when (item) {\n"
             "    // legacy-ok: [fix/x] single-point source function for BOTH pipelines,\n"
             "    // the legacy rows are listed here on purpose\n"
             "    is FlatChatItem.AssistantMarkdownBlock -> 1\n"
             "    else -> 0\n"
             "}\n")},
         0),
        ("legacy-ok cannot launder a NEW top-level declaration (scope)",
         {os.path.join(CHAT_PKG, "ChatColdOpenPrewarm.kt"): _top_level(
             "// legacy-ok: harmless note\n"
             "internal val accidentalLegacyCheck: (FlatChatItem) -> Boolean = "
             "{ it is FlatChatItem.AssistantMarkdownBlock }\n")},
         1),
        ("a blank line ends the justification (scope)",
         {os.path.join(CHAT_PKG, "ChatColdOpenPrewarm.kt"): _top_level(
             "internal fun sources(item: FlatChatItem): Int = when (item) {\n"
             "    // legacy-ok: harmless note\n"
             "\n"
             "    is FlatChatItem.AssistantMarkdownBlock -> 1\n"
             "    else -> 0\n"
             "}\n")},
         1),
    ]
    # --- [wide4/10] the escape hatch must be a USE SITE inside a FUNCTION ---
    cases += [
        ("w4-trailing legacy-ok cannot launder a top-level declaration (scope)",
         {os.path.join(CHAT_PKG, "ChatColdOpenPrewarm.kt"): _top_level(
             "internal const val COLD_OPEN_CHAR_BUDGET = 96_000\n"
             "internal val accidentalLegacyCheck: (FlatChatItem) -> Boolean = "
             "{ it is FlatChatItem.AssistantMarkdownBlock } // legacy-ok: inline bypass\n")},
         1),
        ("w4-`run { }` initializer cannot forge depth (scope)",
         {os.path.join(CHAT_PKG, "ChatColdOpenPrewarm.kt"): _top_level(
             "internal val leakedExtractor = run {\n"
             "    // legacy-ok: bypass the top-level declaration check via run block\n"
             "    ({ it: FlatChatItem -> "
             "(it as? FlatChatItem.AssistantMarkdownBlock)?.rawText })\n"
             "}\n")},
         1),
        ("w4-a declaration inside a multi-line escape region is still judged (scope)",
         {os.path.join(CHAT_PKG, "ChatColdOpenPrewarm.kt"): _top_level(
             "internal fun sources(item: FlatChatItem): Int = when (item) {\n"
             "    // legacy-ok: single-point source function for BOTH pipelines\n"
             "    is FlatChatItem.AssistantMarkdownBlock -> 1\n"
             "    else -> { val leaked = { x: FlatChatItem -> "
             "x is FlatChatItem.AssistantMarkdownBlock }; 0 }\n"
             "}\n")},
         1),
        ("w4-a `\"/*\"` string cannot blank the code below it (parser-death)",
         {os.path.join(CHAT_PKG, "ChatColdOpenPrewarm.kt"): _top_level(
             "internal fun collect(item: FlatChatItem): Int {\n"
             "    val open = \"/*\"\n"
             "    val hits = listOfNotNull((item as? "
             "FlatChatItem.AssistantMarkdownBlock)?.rawText)\n"
             "    val close = \"*/\" + \"\"\n"
             "    return hits.size\n"
             "}\n")},
         1),
    ]
    # --- [wide4/10] completeness: the frozen inventory is now READ ---
    # It existed for a whole round with no reader: the dead package could grow a
    # new helper, or a symbol could stop being parsed (a `internal`/`fun` split
    # across two lines), and nothing noticed.
    _frozen = _frozen_fixture()
    assert set(sum(_FROZEN_GROUPS.values(), [])) == FROZEN_LEGACY_SYMBOLS, \
        "the selftest fixture drifted from FROZEN_LEGACY_SYMBOLS"
    _stub = os.path.join(LEGACY_PKG, "LegacyFlatChatBuilder.kt")
    _audio = os.path.join(LEGACY_PKG, "AudioWaveformView.kt")
    cases += [
        ("w4-frozen inventory matches the package (control)",
         dict(_frozen), 0),
        ("w4-new dead symbol in the frozen package is caught (completeness)",
         dict(_frozen, **{_audio: _frozen[_audio]
                          + "internal fun newUntrackedLegacyHelper(): Int = 0\n"}),
         1),
        ("w4-a modifier split across two lines does not hide a new symbol (completeness)",
         dict(_frozen, **{_audio: _frozen[_audio]
                          + "internal\nfun newSplitLegacyHelper(): Int = 0\n"}),
         1),
        ("w4-a frozen symbol that disappears is caught (completeness)",
         dict(_frozen, **{_stub: _frozen[_stub].replace(
             "internal fun coalesceMarkdownFragments() {}\n", "")}),
         1),
        ("w4-a dead file added to the frozen package is caught (completeness)",
         dict(_frozen, **{os.path.join(LEGACY_PKG, "ExtraDeadSurface.kt"):
                          "package com.rikkaminis.app.ui.chat.legacy\n"
                          "internal fun extra() {}\n"}),
         1),
    ]
    passed = failed = 0
    for name, files, want in cases:
        root = _fixture(files)
        try:
            violations = scan(root)
        finally:
            shutil.rmtree(root, ignore_errors=True)
        rc = 1 if violations else 0
        if rc == want:
            passed += 1
            print("  PASS %s" % name)
        else:
            failed += 1
            print("  FAIL %s" % name)
            print("    expected rc=%s got rc=%s; %s" % (want, rc, violations[:3]))
    print("\n%s (%d fixtures, %d mismatched)"
          % ("LEGACY_PIPELINE_SELFTEST=PASS" if not failed
             else "LEGACY_PIPELINE_SELFTEST=FAIL", passed, failed))
    return 1 if failed else 0


if __name__ == "__main__":
    if any(a == "--selftest" for a in sys.argv[1:]):
        sys.exit(_selftest())
    sys.exit(main())
