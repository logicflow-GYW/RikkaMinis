#!/usr/bin/env python3
"""Fail closed if the retired fragment/ledger chat pipeline is resurrected.

There is no legacy package, file allow-list or legacy-ok escape hatch anymore.
Comments and literal strings may describe history; Kotlin code may not bind or
consume retired symbols. The live AssistantHeader composable is not a row type.
Missing live anchors and unlexable Kotlin are errors, not an empty clean scan.
"""
import os
from pathlib import Path
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import analyse  # noqa: E402

CHAT = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/"
RETIRED = frozenset({
    "AssistantMarkdownBlock", "AssistantThinking", "AssistantToolRunGroup",
    "AssistantInfo", "AssistantTyping", "AssistantError", "AssistantLegacyContent",
    "AppendOnlyMarkdownSegmenter", "AudioWaveformView", "StableChatRowLedger",
    "StableMarkdownSlot", "MINIMUM_STREAMING_OVERLAP_LENGTH", "buildFlatChatItems",
    "coalesceMarkdownFragments", "commonPrefixLength", "isFenceFragment",
    "longestSuffixPrefixOverlap", "looksLikeDivergentStreamingSnapshot",
    "mergeAgentTextSnapshot", "mergeLegacyStreamingText",
    "shouldIgnoreRegressiveStreamingSnapshot", "splitMarkdownIntoBlockTexts",
    "lightFingerprint", "ToolCallRunGroup", "MarkdownBlock", "MarkdownBlockBody",
    "LIVE_FRAGMENT_DEGRADE_CHARS", "LIVE_FRAGMENT_TAIL_CHARS", "COLD_PARSE_PREVIEW_CHARS",
    "BorderedMarkdownTable", "tableSeparatorLineRegex", "parseInlineMarkdown",
})
BANNED = re.compile(r"\b(?:" + "|".join(sorted(RETIRED)) + r")\b")
ANCHORS = {
    "ChatFlatItems.kt": (
        r"\bsealed\s+class\s+FlatChatItem\b", r"\bclass\s+UserBubble\b",
        r"\bclass\s+AssistantMessageItem\b", r"\bfun\s+buildAggregateChatItems\s*\(",
        r"\bfun\s+buildAggregateChatItemsFrom\s*\(",
        r"\bfun\s+buildAggregateChatItemsIncremental\s*\(",
        r"\bfun\s+mergeStreamingOverlay\s*\(",
    ),
    "ChatScreen.kt": (r"\bfun\s+ChatScreen\s*\(", r"\bis\s+FlatChatItem\s*\.\s*AssistantMessageItem\b"),
    "StreamingMarkdownText.kt": (r"\bfun\s+StreamingMarkdownText\s*\(", r"\bobject\s+StreamingBlockLedger\b"),
}


def scan(root):
    root = Path(root)
    src = root / "src/android/app/src"
    violations = []
    views = {}
    if not src.is_dir():
        return ["ANCHOR: Android source tree is missing (fail-closed)"]
    for path in sorted(src.rglob("*.kt")):
        rel = path.relative_to(root).as_posix()
        if "/ui/chat/legacy/" in "/" + rel:
            violations.append(f"PACKAGE: {rel}: retired package must remain absent")
        try:
            raw = path.read_text(encoding="utf-8")
            ok, reason, parsed = analyse(raw)
        except (OSError, UnicodeError) as exc:
            violations.append(f"LEX: {rel}: cannot read Kotlin ({type(exc).__name__})")
            continue
        if not ok:
            violations.append(f"LEX: {rel}: cannot lex Kotlin ({reason})")
            continue
        # Kotlin backtick names are single identifiers. A JUnit name such as
        # `AssistantHeader has one call` is not a reference to AssistantHeader;
        # an escaped `StableChatRowLedger` still is a retired identifier.
        code = re.sub(r"`[^`\n]+`", lambda m: m.group() if
                      re.fullmatch(r"`[A-Za-z_]\w*`", m.group()) else
                      " " * len(m.group()), parsed["code"])
        views[rel] = code
        if re.search(r"\b(?:package|import)\s+[\w.]*ui\s*\.\s*chat\s*\.\s*legacy\b", code):
            violations.append(f"PACKAGE: {rel}: retired package declaration/import")
        for match in BANNED.finditer(code):
            line = code.count("\n", 0, match.start()) + 1
            violations.append(f"SYMBOL: {rel}:{line}: {match.group()}")
        for match in re.finditer(r"\bAssistantHeader\b", code):
            # Only the live composable definition/call is legal. Row access,
            # class/typealias declarations, bare type annotations and imports
            # are not calls and may not resurrect the old sealed subtype.
            before = code[max(0, match.start() - 100):match.start()]
            after = code[match.end():]
            if (re.search(r"\bFlatChatItem\s*\.\s*$", before)
                    or re.search(r"\b(?:class|typealias|import)\s+[\w.]*$", before)
                    or not re.match(r"\s*\(", after)):
                line = code.count("\n", 0, match.start()) + 1
                violations.append(f"ROW: {rel}:{line}: AssistantHeader row resurrected")
    for name, anchors in ANCHORS.items():
        rel = CHAT + name
        code = views.get(rel, "")
        for anchor in anchors:
            if not re.search(anchor, code):
                violations.append(f"ANCHOR: {rel}: missing live anchor {anchor}")
    return violations


def _fixture():
    return {
        CHAT + "ChatFlatItems.kt": """package com.rikkaminis.app.ui.chat
internal sealed class FlatChatItem {
    class UserBubble : FlatChatItem()
    class AssistantMessageItem : FlatChatItem()
}
fun buildAggregateChatItems() {}
fun buildAggregateChatItemsFrom() {}
fun buildAggregateChatItemsIncremental() {}
fun mergeStreamingOverlay() {}
""",
        CHAT + "ChatScreen.kt": """package com.rikkaminis.app.ui.chat
fun ChatScreen(item: FlatChatItem) = when(item) {
    is FlatChatItem.AssistantMessageItem -> AssistantHeader()
    is FlatChatItem.UserBubble -> Unit
}
""",
        CHAT + "StreamingMarkdownText.kt": """package com.rikkaminis.app.ui.chat
fun StreamingMarkdownText() {}
internal object StreamingBlockLedger {}
""",
        CHAT + "ChatAssistantMessageUI.kt": "fun AssistantHeader() {}\n",
    }


def _selftest():
    cases = [("retired package absent", {}, None),
             ("comments and strings legal", {CHAT + "Note.kt":
              '// StableChatRowLedger\n/* AssistantMarkdownBlock */\nval note = "ToolCallRunGroup"\n'}, None),
             ("legacy-ok no longer escapes", {CHAT + "Note.kt":
              'fun leaked() {\n// legacy-ok: historical bypass\nStableChatRowLedger()\n}\n'}, "SYMBOL:"),
             ("retired directory", {CHAT + "legacy/New.kt": 'fun harmless() {}'}, "PACKAGE:"),
             ("moved package declaration", {CHAT + "Note.kt":
              'package com.rikkaminis.app.ui.chat.legacy\nfun harmless() {}'}, "PACKAGE:"),
             ("missing anchor file", {CHAT + "ChatFlatItems.kt": None}, "ANCHOR:"),
             ("comment cannot replace anchor", {CHAT + "StreamingMarkdownText.kt":
              '// fun StreamingMarkdownText() {}\n// object StreamingBlockLedger {}\n'}, "ANCHOR:"),
             ("unlexable Kotlin", {CHAT + "Note.kt": 'val text = "unterminated'}, "LEX:"),
             ("row-qualified header", {CHAT + "Note.kt": 'val row = FlatChatItem.AssistantHeader("a")'}, "ROW:"),
             ("bare header declaration", {CHAT + "Note.kt": 'class AssistantHeader : FlatChatItem()'}, "ROW:"),
             ("bare header type", {CHAT + "Note.kt": 'fun consume(row: AssistantHeader) {}'}, "ROW:"),
             ("wildcard row import", {CHAT + "Note.kt":
              'import com.rikkaminis.app.ui.chat.FlatChatItem.*\nfun consume(row: AssistantMarkdownBlock) {}'}, "SYMBOL:"),
             ("nested comments cannot hide code", {CHAT + "Note.kt":
              '/* outer /* nested */ end */\nfun leak() = StableChatRowLedger()'}, "SYMBOL:"),
             ("comment delimiter in string", {CHAT + "Note.kt":
              'val open = "/*"\nval bad = StableChatRowLedger()\nval close = "*/"'}, "SYMBOL:")]
    for symbol in sorted(RETIRED):
        cases.append(("resurrect " + symbol, {CHAT + "Note.kt": f"fun leaked() = {symbol}()\n"}, "SYMBOL:"))
    failed = 0
    for name, patch, tag in cases:
        files = _fixture()
        files.update(patch)
        with tempfile.TemporaryDirectory(prefix="legacy-retired-") as temp:
            for rel, text in files.items():
                if text is not None:
                    path = Path(temp) / rel
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(text, encoding="utf-8")
            violations = scan(temp)
        passed = not violations if tag is None else any(v.startswith(tag) for v in violations)
        failed += not passed
        print(f"  {'PASS' if passed else 'FAIL'} {name}" + (f" {violations[:2]}" if not passed else ""))
    print(f"LEGACY_PIPELINE_SELFTEST={'FAIL' if failed else 'PASS'} ({len(cases)} fixtures, {failed} mismatched)")
    return int(bool(failed))


def main():
    violations = scan(sys.argv[1] if len(sys.argv) > 1 else ".")
    if violations:
        print(f"❌ Legacy pipeline guard: {len(violations)} violation(s)")
        for violation in violations:
            print("  " + violation)
        return 1
    print("✅ Legacy pipeline guard: retired pipeline absent; live anchors intact")
    return 0


if __name__ == "__main__":
    sys.exit(_selftest() if "--selftest" in sys.argv else main())
