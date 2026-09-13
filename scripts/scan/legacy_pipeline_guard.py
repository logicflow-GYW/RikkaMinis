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
    text = BLOCK_COMMENT_RE.sub("", text)
    out = []
    for line in text.splitlines():
        if line.lstrip().startswith("//"):
            # keep the justification; neutralise other comment lines WITHOUT
            # making them blank (a blank line ends a legacy-ok block)
            out.append(line if ESCAPE_RE.search(line) else "//")
        else:
            out.append(re.sub(r"//.*$", "", line))
    return out


def legacy_exported_symbols(root):
    """Every top-level declaration in ui/chat/legacy/*.kt (auto-tracked)."""
    legacy_dir = os.path.join(
        root, "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/legacy"
    )
    names = set()
    if not os.path.isdir(legacy_dir):
        return names
    for fn in sorted(os.listdir(legacy_dir)):
        if not fn.endswith(".kt"):
            continue
        src = open(os.path.join(legacy_dir, fn), encoding="utf-8", errors="replace").read()
        for m in re.finditer(
            r"^(?:@\w+\s+)?(?:internal |private |public )?(?:data |sealed |enum |value |abstract )?"
            r"(?:class|interface|object|fun|val|var|typealias)\s+([A-Za-z_]\w*)",
            src,
            re.M,
        ):
            names.add(m.group(1))
    return names


def scan(root):
    violations = []
    symbols = legacy_exported_symbols(root) | set(LEGACY_ROWS) | set(EXTRA_SYMBOLS)
    row_patterns = [
        (re.compile(r"\bFlatChatItem\." + re.escape(r) + r"\b"), "FlatChatItem." + r)
        for r in LEGACY_ROWS
    ]
    other_patterns = [
        (re.compile(r"\b" + re.escape(s) + r"\b"), s)
        for s in sorted(symbols - set(LEGACY_ROWS))
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
            lines = strip_comments(open(path, encoding="utf-8", errors="replace").read())
            exempt = False
            for i, line in enumerate(lines):
                if not line.strip():
                    exempt = False
                    continue
                prev = lines[i - 1] if i > 0 else ""
                if ESCAPE_RE.search(line) or ESCAPE_RE.search(prev):
                    # a `legacy-ok:` comment exempts the following block until
                    # the next blank line, so one justification can cover a
                    # small group of deliberate references
                    exempt = True
                    continue
                if exempt:
                    continue
                for pat, label in row_patterns + other_patterns:
                    if pat.search(line):
                        violations.append(f"{posix}:{i + 1}  {label}  ->  {line.strip()[:100]}")
    return violations


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


if __name__ == "__main__":
    sys.exit(main())
