package com.rikkaminis.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1034 FIX-B3 ISSUE-05] Wiring tests for inline math inside list
 * items.
 *
 * Two independent halves:
 *
 *  **Parse half** — drives the REAL [MarkdownParser.parseWithMath] on the JVM
 *  (the parser is pure Kotlin) and pins the premise of the defect:
 *  `Block.BulletList` / `Block.NumberedList` items keep their U+FFFC math
 *  placeholders in `ListItem.content` (the restore pass only re-walks
 *  paragraphs), so the renderer must be handed the span list to resolve them.
 *  It also reproduces what the OLD path did to those items — the
 *  `InlineContent` placeholder-cleanup pass silently deleted the placeholder,
 *  i.e. the math simply disappeared.
 *
 *  **Shape half** — source-shape pins on `MarkdownText.kt`. The renderers are
 *  composables, so the wiring cannot be driven on the JVM; like
 *  `AgentLoopBudgetStopExitTest`, this half pins the invariants so a Gradle
 *  build still fails if the wiring is unwound:
 *   - [BlockContent] hands `mathSpans` to BOTH list views;
 *   - both list views take a `mathSpans` parameter and route item content
 *     through `MathAwareInlineContent` (NOT through bare `InlineContent`);
 *   - `MathAwareInlineContent` splits on placeholders and emits non-display
 *     KaTeX, and falls through to `InlineContent` when there is nothing to
 *     resolve (plain lists must not pay for an extra Column);
 *   - `HeadingBlock` still calls bare `InlineContent` — headings keep their
 *     placeholders at parse time but have no span list to resolve them, so
 *     the cleanup pass inside `InlineContent` must stay.
 *
 *  Code assertions run against a masked copy of the source (comments and
 *  string/char literals blanked, offsets preserved) so a commented-out call
 *  can never satisfy a pin; the one assertion about the placeholder regex
 *  text itself reads the raw slice.
 */
class MarkdownListMathWiringTest {

    private val orc = '￼'

    private val legacyStripRegex = Regex("\\uFFFCMATH\\d+\\uFFFC")

    // ── parse half: the defect's premise, pinned on the real parser ────────

    @Test fun `bullet item keeps its math placeholder and span`() {
        val result = MarkdownParser.parseWithMath("- 公式 \$x^2\$ 结尾")
        val list = result.blocks.filterIsInstance<MarkdownParser.Block.BulletList>().single()
        assertEquals(1, list.items.size)
        val content = list.items[0].content
        assertTrue("expected a U+FFFC placeholder in: $content", content.contains(orc))
        assertEquals(1, result.mathSpans.size)
        assertEquals("x^2", result.mathSpans[0].latex)
        assertFalse(result.mathSpans[0].isBlock)
        // The span's placeholder must actually appear in the item text.
        assertTrue(content.contains(result.mathSpans[0].placeholder))
    }

    @Test fun `numbered item keeps its math placeholder and span`() {
        val result = MarkdownParser.parseWithMath("1. 方程 \$a+b\$ 等等")
        val list = result.blocks.filterIsInstance<MarkdownParser.Block.NumberedList>().single()
        assertTrue(list.items[0].content.contains(orc))
        assertEquals(1, result.mathSpans.size)
        assertEquals("a+b", result.mathSpans[0].latex)
    }

    @Test fun `bullet item nested in blockquote keeps its placeholder`() {
        val result = MarkdownParser.parseWithMath("> - 嵌套 \$x\$")
        val quote = result.blocks.filterIsInstance<MarkdownParser.Block.Blockquote>().single()
        val inner = quote.blocks.filterIsInstance<MarkdownParser.Block.BulletList>().single()
        assertTrue(inner.items[0].content.contains(orc))
        assertEquals("x", result.mathSpans[0].latex)
    }

    @Test fun `multiple math items keep order`() {
        val result = MarkdownParser.parseWithMath("- one \$a\$\n- two \$b\$\n- plain")
        val list = result.blocks.filterIsInstance<MarkdownParser.Block.BulletList>().single()
        assertEquals(listOf(true, true, false), list.items.map { it.content.contains(orc) })
        assertEquals(listOf("a", "b"), result.mathSpans.map { it.latex })
    }

    @Test fun `the old path silently deleted the math`() {
        // What InlineContent's cleanup pass did to a list item before the fix —
        // pinned so the data loss is not folklore: the placeholder AND the
        // formula vanish from the rendered string.
        val result = MarkdownParser.parseWithMath("- 公式 \$x^2\$ 结尾")
        val content = result.blocks
            .filterIsInstance<MarkdownParser.Block.BulletList>().single().items[0].content
        val renderedByOldPath = content.replace(legacyStripRegex, "")
        assertFalse(renderedByOldPath.contains("x^2"))
        assertEquals("公式  结尾", renderedByOldPath)
    }

    @Test fun `headings keep their placeholder but have no span list to resolve it`() {
        // Documents why InlineContent's cleanup pass must stay: a heading is
        // emitted by the restore pass with its inline placeholder intact, and
        // HeadingBlock does not carry the span list.
        val result = MarkdownParser.parseWithMath("## 标题 \$x\$")
        val heading = result.blocks.filterIsInstance<MarkdownParser.Block.Heading>().single()
        assertTrue(heading.content.contains(orc))
        assertEquals("x", result.mathSpans[0].latex)
    }

    // ── shape half: wiring pins on MarkdownText.kt ─────────────────────────

    private val sourcePath =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/markdown/MarkdownText.kt"

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    /** Blank out comments and string/char literals, preserving offsets. */
    private fun mask(src: String): String {
        val out = StringBuilder(src)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                src.startsWith("//", i) -> {
                    var j = src.indexOf('\n', i)
                    if (j < 0) j = src.length
                    for (k in i until j) out[k] = ' '
                    i = j
                }
                src.startsWith("/*", i) -> {
                    var j = src.indexOf("*/", i + 2)
                    j = if (j < 0) src.length else j + 2
                    for (k in i until j) if (out[k] != '\n') out[k] = ' '
                    i = j
                }
                src.startsWith("\"\"\"", i) -> {
                    var j = src.indexOf("\"\"\"", i + 3)
                    j = if (j < 0) src.length else j + 3
                    for (k in i until j) if (out[k] != '\n') out[k] = ' '
                    i = j
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < src.length && src[j] != c) {
                        j += if (src[j] == '\\') 2 else 1
                    }
                    for (k in i until minOf(j + 1, src.length)) out[k] = ' '
                    i = j + 1
                }
                else -> i++
            }
        }
        return out.toString()
    }

    /** (raw, masked) slice of the named function, braces matched on masked text. */
    private fun functionSlice(src: String, name: String): Pair<String, String> {
        val masked = mask(src)
        val header = Regex("fun\\s+$name\\s*\\(").find(masked)
            ?: error("function $name not found in $sourcePath")
        var depth = 0
        var i = masked.indexOf('{', header.range.first)
        val start = i
        while (i < masked.length) {
            when (masked[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(start, i + 1) to masked.substring(start, i + 1)
                }
            }
            i++
        }
        error("unbalanced braces in $name")
    }

    @Test fun `BlockContent hands mathSpans to both list views`() {
        val (raw, _) = functionSlice(readRepoFile(sourcePath), "BlockContent")
        assertTrue(
            "BulletListView must receive mathSpans",
            Regex("BulletListView\\s*\\(\\s*block\\s*,\\s*color\\s*,\\s*baseStyle\\s*,\\s*mathSpans\\s*\\)")
                .containsMatchIn(raw),
        )
        assertTrue(
            "NumberedListView must receive mathSpans",
            Regex("NumberedListView\\s*\\(\\s*block\\s*,\\s*color\\s*,\\s*baseStyle\\s*,\\s*mathSpans\\s*\\)")
                .containsMatchIn(raw),
        )
    }

    @Test fun `both list views accept mathSpans and route items through MathAwareInlineContent`() {
        val src = readRepoFile(sourcePath)
        val masked = mask(src)
        for (name in listOf("BulletListView", "NumberedListView")) {
            val header = Regex("private fun $name\\s*\\(").find(masked) ?: error("$name not found")
            val headerText = src.substring(header.range.first, src.indexOf('{', header.range.first))
            assertTrue(
                "$name must declare a mathSpans parameter",
                Regex("mathSpans\\s*:\\s*List<MarkdownParser\\.MathSpan>").containsMatchIn(headerText),
            )
            val (raw, body) = functionSlice(src, name)
            assertTrue(
                "$name must route item content through MathAwareInlineContent",
                Regex("MathAwareInlineContent\\s*\\(").containsMatchIn(body),
            )
            assertFalse(
                "$name must not call bare InlineContent for item content",
                Regex("(?<!MathAware)InlineContent\\s*\\(").containsMatchIn(body),
            )
            assertTrue("$name must pass the spans on", Regex("mathSpans\\s*=\\s*mathSpans").containsMatchIn(raw))
        }
    }

    @Test fun `MathAwareInlineContent resolves placeholders with non-display KaTeX`() {
        val src = readRepoFile(sourcePath)
        val masked = mask(src)
        val header = Regex("private fun MathAwareInlineContent\\s*\\(").find(masked)
            ?: error("MathAwareInlineContent not found")
        val headerText = src.substring(header.range.first, src.indexOf('{', header.range.first))
        for (param in listOf("text", "color", "style", "mathSpans", "modifier")) {
            assertTrue(
                "MathAwareInlineContent must declare `$param`",
                Regex("\\b$param\\s*:").containsMatchIn(headerText),
            )
        }
        val (raw, body) = functionSlice(src, "MathAwareInlineContent")
        // fast path: nothing to resolve -> plain InlineContent, no extra Column
        assertTrue(
            "fast path must fall through to InlineContent",
            Regex("mathSpans\\.isEmpty\\(\\)\\s*\\|\\|\\s*!text\\.contains").containsMatchIn(body),
        )
        // split path: same placeholder regex + lookup as ParagraphBlock/TableCellContent
        assertTrue(raw.contains("\\\\uFFFC\" + \"MATH(\\\\d+)\" + \"\\\\uFFFC"))
        assertTrue(
            "resolved spans must render as NON-display KaTeX",
            Regex("KaTeXRenderView\\s*\\(\\s*latex\\s*=\\s*span\\.latex\\s*,\\s*displayMode\\s*=\\s*false\\s*\\)")
                .containsMatchIn(raw),
        )
    }

    @Test fun `InlineContent still strips placeholders for paths without spans`() {
        val src = readRepoFile(sourcePath)
        val (rawInline, _) = functionSlice(src, "InlineContent")
        assertTrue(
            "the cleanup pass must stay for headings",
            rawInline.contains("\"\\\\uFFFCMATH\\\\d+\\\\uFFFC\""),
        )
        val (_, headingBody) = functionSlice(src, "HeadingBlock")
        assertTrue(
            "HeadingBlock must keep calling InlineContent",
            Regex("(?<!MathAware)InlineContent\\s*\\(").containsMatchIn(headingBody),
        )
    }
}
