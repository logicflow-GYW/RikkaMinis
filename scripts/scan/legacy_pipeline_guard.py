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
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.S)


def strip_comments(text):
    """Remove block comments and //-comments; keep line structure.

    Lines carrying a `legacy-ok:` justification are kept verbatim — the escape
    hatch has to survive comment stripping to be seen by the scanner."""
    # keep the newlines a block comment spanned, so line numbers stay aligned
    text = BLOCK_COMMENT_RE.sub(lambda m: "\n" * m.group(0).count("\n"), text)
    out = []
    for line in text.splitlines():
        if line.lstrip().startswith("//"):
            # keep the justification; neutralise other comment lines WITHOUT
            # making them blank (a blank line ends a legacy-ok block)
            out.append(line if ESCAPE_RE.search(line) else "//")
        else:
            stripped = re.sub(r"//.*$", "", line)
            if ESCAPE_RE.search(line):
                # keep the justification visible even when it trails code
                stripped += " // legacy-ok: "
            out.append(stripped)
    return out


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
def mask_code(text):
    """Blank comments AND literals, newline/offset preserving.

    `strip_comments` is line-oriented and has no idea about string literals, so
    a URL (`"https://…"`) truncates its own line. Structure decisions (brace
    depth, which line opens a declaration) must not be made on that text — a
    single mismatched brace pushes local `val`s to depth 0 and turns them into
    gate-wide patterns (`rawText`, `ordinal`, …).
    """
    out = []
    i, n = 0, len(text)
    state = None
    while i < n:
        c = text[i]
        two = text[i:i + 2]
        if state is None:
            if two == "//":
                state = "line"; out.append("  "); i += 2; continue
            if two == "/*":
                state = "block"; out.append("  "); i += 2; continue
            if text[i:i + 3] == '"""':
                state = "raw"; out.append("   "); i += 3; continue
            if c == '"':
                state = "str"; out.append(" "); i += 1; continue
            if c == "'":
                state = "char"; out.append(" "); i += 1; continue
            out.append(c); i += 1; continue
        if state == "line":
            out.append("\n" if c == "\n" else " ")
            if c == "\n":
                state = None
            i += 1; continue
        if state == "block":
            if two == "*/":
                state = None; out.append("  "); i += 2; continue
            out.append("\n" if c == "\n" else " "); i += 1; continue
        if state == "raw":
            if text[i:i + 3] == '"""':
                state = None; out.append("   "); i += 3; continue
            out.append("\n" if c == "\n" else " "); i += 1; continue
        if state == "str":
            if c == "\\":
                out.append("  "); i += 2; continue
            if c == '"':
                state = None
            out.append(" "); i += 1; continue
        if state == "char":
            if c == "\\":
                out.append("  "); i += 2; continue
            if c == "'":
                state = None
            out.append(" "); i += 1; continue
    return "".join(out)


def escape_regions(raw_lines, masked):
    """[(start, end)] line ranges (0-based, inclusive) a `legacy-ok:` justifies.

    Rules, all fail-closed:
      * the marker lives on the code line itself, or anywhere in the contiguous
        run of comment lines directly ABOVE the escaped code (a multi-line
        justification is the shipped shape) — a blank line breaks the run;
      * the escaped code must be a USE SITE inside a body: a top-level line, or
        a line that opens a declaration, is never exempted (that is a new
        legacy surface, not a deliberate dual-pipeline statement);
      * the region ends at the first blank line or the moment brace depth drops
        below the escaped line's own depth — it can no longer swallow a whole
        paragraph of unrelated new code.
    """
    depths, depth = [], 0
    for line in masked:
        depths.append(depth)
        depth += line.count("{") - line.count("}")
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
            if depths[start] <= 0 or _DECL_SITE_RE.match(masked[start]):
                i = j + 1
                continue
            base = depths[start]
            end = start
            k = start
            while k < n and masked[k].strip() and depths[k] >= base:
                end = k
                k += 1
            regions.append((start, end))
            i = end + 1
            continue
        if not is_comment and ESCAPE_RE.search(line):
            regions.append((i, i))
        i += 1
    return regions


def scan(root):
    violations = []
    derived, per_file, drift = _legacy_scan(root)
    violations.extend(drift)
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
        for fn in files:
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
            raw_lines = raw.split("\n")
            lines = strip_comments(raw)
            masked = mask_code(raw).split("\n")
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


def _legacy_scan(root):
    """(names, per_file, drift) for ui/chat/legacy/*.kt.

    `names` is the union of every TOP-LEVEL declaration (auto-tracked);
    `per_file` maps file name -> set, so the caller can tell "this file parsed
    to nothing" (parser rot) from "this package has no declarations";
    `drift` lists declaration heads that are not at column 0 — the legacy
    package is frozen and runtime-dead, so a reformat of a declaration head is
    an edit of a dead module that nobody would otherwise notice.
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
        clean = _DECL_ANNOTATION_RE.sub("", mask_code(src))
        found = set()
        # Only TOP-LEVEL declarations count: a local `val out = …` inside a
        # function is not a symbol anyone can reference, and harvesting those
        # would turn common names (`out`, `i`, `parts`) into gate patterns.
        depth = paren = 0
        for idx, line in enumerate(clean.split("\n")):
            if depth == 0 and paren == 0:
                m = _DECL_RE.match(line)
                if m:
                    found.add(m.group(1))
                    if line[:1] in (" ", "\t"):
                        drift.append(
                            "%s:%d  declaration head is indented (%s) — the frozen "
                            "legacy package was edited; confirm the reference is "
                            "still tracked" % (fn, idx + 1, m.group(1))
                        )
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
