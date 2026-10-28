package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure helpers behind F-10 / F-12 / F-13 / F-14 of the
 * meta-audit-1028 batch (tool-detail sheet + thinking block).
 *
 * What is *not* covered here: the Compose rendering itself (no Android runtime
 * in a JVM unit test). The rendering side of F-12/F-14 is pinned by shape
 * assertions in `probe-c5/shape.py` — see FIX-META1028-C5.md §5/§6.
 */
class ChatToolDetailUiLogicTest {

    private fun tool(
        id: String,
        name: String,
        args: String = "",
        url: String? = null,
    ) = AssistantBlock(id = id, kind = "tool_use", toolName = name, toolArgs = args, browserURL = url)

    // ── F-10: a browser_use block's resolved URL ────────────────────────────

    @Test
    fun `F-10 a preceding non browser tool url is never inherited`() {
        // The defect: web_fetch carries a `url` arg too, so the sheet's URL pill
        // showed a page the browser never loaded — while the globe button (which
        // had this guard) refused to open it.
        val blocks = listOf(
            tool("a", "web_fetch", """{"url":"https://example.com/fetched"}"""),
            tool("b", "browser_use", """{"action":"click"}"""),
        )
        assertEquals("", resolveBrowserUseUrl(blocks[1], blocks))
    }

    @Test
    fun `F-10 inherits from the nearest preceding browser_use block`() {
        val blocks = listOf(
            tool("a", "browser_use", url = "https://example.com/page"),
            tool("b", "browser_use", """{"action":"click"}"""),
        )
        assertEquals("https://example.com/page", resolveBrowserUseUrl(blocks[1], blocks))
    }

    @Test
    fun `F-10 inherits a predecessor url that only exists in its args`() {
        val blocks = listOf(
            tool("a", "browser_use", """{"action":"open","url":"https://args.example/x"}"""),
            tool("b", "browser_use", """{"action":"screenshot"}"""),
        )
        assertEquals("https://args.example/x", resolveBrowserUseUrl(blocks[1], blocks))
    }

    @Test
    fun `F-10 an intervening non browser tool is skipped, not inherited`() {
        // Two distinct invariants in one shape:
        //  - the web_fetch url is NEVER used (that was the defect), and
        //  - the last browser_use page still wins, because a web_fetch does not
        //    navigate the in-app browser.
        // (firstNotNullOfOrNull cannot "stop" at a null result — the guard
        // filters candidates, it doesn't break the scan.)
        val blocks = listOf(
            tool("a", "browser_use", url = "https://example.com/old"),
            tool("b", "web_fetch", """{"url":"https://elsewhere.example/doc"}"""),
            tool("c", "browser_use", """{"action":"click"}"""),
        )
        assertEquals("https://example.com/old", resolveBrowserUseUrl(blocks[2], blocks))
    }

    @Test
    fun `F-10 a non browser tool url is never used even as the only candidate`() {
        val blocks = listOf(
            tool("a", "web_fetch", """{"url":"https://elsewhere.example/doc"}"""),
            tool("b", "browser_use", """{"action":"click"}"""),
        )
        assertEquals("", resolveBrowserUseUrl(blocks[1], blocks))
    }

    @Test
    fun `F-10 own browserURL wins over args and predecessors`() {
        val blocks = listOf(
            tool("a", "browser_use", url = "https://prev.example/p"),
            tool("b", "browser_use", """{"url":"https://args.example/a"}""", "https://live.example/l"),
        )
        assertEquals("https://live.example/l", resolveBrowserUseUrl(blocks[1], blocks))
    }

    @Test
    fun `F-10 own args url wins over the predecessor`() {
        val blocks = listOf(
            tool("a", "browser_use", url = "https://prev.example/p"),
            tool("b", "browser_use", """{"url":"https://args.example/a"}"""),
        )
        assertEquals("https://args.example/a", resolveBrowserUseUrl(blocks[1], blocks))
    }

    @Test
    fun `F-10 no predecessor yields empty`() {
        val blocks = listOf(tool("a", "browser_use", """{"action":"click"}"""))
        assertEquals("", resolveBrowserUseUrl(blocks[0], blocks))
    }

    @Test
    fun `F-10 a block that is not in the list yields empty`() {
        val blocks = listOf(tool("a", "browser_use", url = "https://example.com/page"))
        assertEquals("", resolveBrowserUseUrl(tool("zz", "browser_use", """{"action":"click"}"""), blocks))
    }

    @Test
    fun `F-10 malformed args do not throw and do not resolve`() {
        val unterminated = "{\"action\":\"click"
        val brokenUrl = "{\"url\":\"https://args.example/a"
        val blocks = listOf(
            tool("a", "browser_use", unterminated),
            tool("b", "browser_use", brokenUrl),
        )
        assertEquals("", resolveBrowserUseUrl(blocks[0], blocks))
        assertEquals("", resolveBrowserUseUrl(blocks[1], blocks))
    }

    @Test
    fun `F-10 parseToolArgUrl ignores an empty url`() {
        assertEquals(null, parseToolArgUrl("""{"url":""}"""))
        assertEquals(null, parseToolArgUrl(""))
        assertEquals(null, parseToolArgUrl("not json"))
    }

    // ── F-13: partial-JSON unescape ─────────────────────────────────────────

    @Test
    fun `F-13 a literal backslash-n survives as backslash-n`() {
        // JSON text: {"command":"echo \\n"}  → the *value* keeps one backslash
        // and an `n` (think `sed s/x/\\n/`, a regex, a diff hunk).
        val json = "{\"command\":\"echo \\\\n\"}"
        assertEquals("echo \\n", extractPartialJsonString("command", json))
    }

    @Test
    fun `F-13 a real newline escape still becomes a newline`() {
        val json = "{\"command\":\"echo \\n\"}"
        assertEquals("echo \n", extractPartialJsonString("command", json))
    }

    @Test
    fun `F-13 a literal backslash-t survives as backslash-t`() {
        val json = "{\"command\":\"printf '%s' \\\\t\"}"
        assertEquals("printf '%s' \\t", extractPartialJsonString("command", json))
    }

    @Test
    fun `F-13 escaped quote and escaped slash decode`() {
        val json = "{\"command\":\"echo \\\"hi\\\" http:\\/\\/a\\/b\"}"
        assertEquals("echo \"hi\" http://a/b", extractPartialJsonString("command", json))
    }

    @Test
    fun `F-13 an escaped backslash before n decodes to backslash n`() {
        // Four characters `\\\\n` in JSON = two backslashes then n... i.e. the
        // value is `\\n`. The old chain ate one backslash in the \\ pass after
        // the \n pass had already turned the tail into a newline.
        val json = "{\"command\":\"a\\\\\\\\nb\"}"
        assertEquals("a\\\\nb", extractPartialJsonString("command", json))
    }

    @Test
    fun `F-13 a half streamed trailing backslash is preserved`() {
        val json = "{\"command\":\"echo \\"
        assertEquals("echo \\", extractPartialJsonString("command", json))
    }

    @Test
    fun `F-13 unknown escapes are passed through verbatim`() {
        // Same as the pre-fix chain (\r and \uXXXX were never decoded) — kept
        // deliberately so this fix changes only the literal-escape behaviour.
        assertEquals("a\\rb", unescapeJsonStringBody("a\\rb"))
        assertEquals("a\\u4e2db", unescapeJsonStringBody("a\\u4e2db"))
    }

    @Test
    fun `F-13 unescape round trips against the org json encoder`() {
        val samples = listOf(
            "plain text",
            "line1\nline2\ttabbed",
            "C:\\Users\\me\\dir",
            "regex \\d+\\s*",
            "echo \"quoted\"",
            "url http://a/b",
            "back\\\\slash",
            "trailing backslash \\",
            "\\n literal pair",
        )
        for (s in samples) {
            val quoted = org.json.JSONObject.quote(s)
            val body = quoted.substring(1, quoted.length - 1)
            assertEquals("round-trip failed for <$s>", s, unescapeJsonStringBody(body))
        }
    }

    @Test
    fun `F-13 text without escapes is returned unchanged`() {
        assertEquals("plain", unescapeJsonStringBody("plain"))
    }

    // ── F-12: file_edit diff render cap ─────────────────────────────────────

    @Test
    fun `F-12 a short diff renders exactly like lines`() {
        // Shape-preservation assertion: the pre-fix renderer walked `lines()`.
        val text = "one\ntwo\nthree"
        assertEquals(text.lines(), buildDiffPreview(text).lines)
        assertEquals(0, buildDiffPreview(text).hiddenLines)
    }

    @Test
    fun `F-12 a trailing newline keeps the empty last line`() {
        assertEquals("a\n".lines(), buildDiffPreview("a\n").lines)
    }

    @Test
    fun `F-12 the rendered window is capped and reports the remainder`() {
        val text = (1..1000).joinToString("\n") { "line$it" }
        val preview = buildDiffPreview(text)
        assertEquals(DIFF_PREVIEW_MAX_LINES, preview.lines.size)
        assertEquals(1000 - DIFF_PREVIEW_MAX_LINES, preview.hiddenLines)
        assertEquals("line1", preview.lines.first())
        assertEquals("line${DIFF_PREVIEW_MAX_LINES}", preview.lines.last())
    }

    @Test
    fun `F-12 exactly at the cap hides nothing`() {
        val text = (1..DIFF_PREVIEW_MAX_LINES).joinToString("\n") { "l$it" }
        val preview = buildDiffPreview(text)
        assertEquals(DIFF_PREVIEW_MAX_LINES, preview.lines.size)
        assertEquals(0, preview.hiddenLines)
    }

    @Test
    fun `F-12 an oversized single line is clipped`() {
        val text = "x".repeat(DIFF_PREVIEW_MAX_CHARS_PER_LINE * 3)
        val preview = buildDiffPreview(text)
        assertEquals(1, preview.lines.size)
        assertEquals(DIFF_PREVIEW_MAX_CHARS_PER_LINE + 1, preview.lines[0].length) // + ellipsis
        assertTrue(preview.lines[0].endsWith("…"))
        assertEquals(0, preview.hiddenLines)
    }

    @Test
    fun `F-12 empty text renders no rows`() {
        val preview = buildDiffPreview("")
        assertTrue(preview.lines.isEmpty())
        assertEquals(0, preview.hiddenLines)
    }

    @Test
    fun `F-12 a huge body stays bounded in both rows and work`() {
        val text = (1..20_000).joinToString("\n") { "some long-ish source line $it" }
        val preview = buildDiffPreview(text)
        assertEquals(DIFF_PREVIEW_MAX_LINES, preview.lines.size)
        assertEquals(20_000 - DIFF_PREVIEW_MAX_LINES, preview.hiddenLines)
    }

    // ── F-14: thinking window ───────────────────────────────────────────────

    @Test
    fun `F-14 short content is not windowed`() {
        val content = "a\nb\nc"
        assertEquals(0, thinkingTailWindowStart(content, 8_000))
        assertEquals(content, thinkingWindowSlice(content, 0, 8_000))
        assertEquals(content, thinkingWindowSlice(content, 0, 8_000, frozen = true))
    }

    @Test
    fun `F-14 tail start skips the head of a long block`() {
        val content = "x".repeat(10_000)
        assertEquals(10_000 - 8_000, thinkingTailWindowStart(content, 8_000))
    }

    @Test
    fun `F-14 tail start snaps to a nearby newline`() {
        // Cut lands at 4000; the next newline is 10 chars later → start after
        // it, so the reader never sees a half line at the top.
        val content = "x".repeat(4_010) + "\n" + "y".repeat(12_000 - 4_011)
        assertEquals(4_011, thinkingTailWindowStart(content, 8_000))
        assertTrue(thinkingWindowSlice(content, 4_011, 8_000).startsWith("y"))
    }

    @Test
    fun `F-14 tail start does not snap to a far away newline`() {
        // Newline 5000 chars before the cut (> 200 slack) → keep the exact cut.
        val content = "x".repeat(12_000) + "\n" + "y".repeat(1_000)
        assertEquals(content.length - 8_000, thinkingTailWindowStart(content, 8_000))
    }

    @Test
    fun `F-14 a frozen window does not move while content grows`() {
        // The defect: pausing only stopped `scrollTo`; the window head kept
        // advancing, so the reader's lines slid up out of the viewport.
        val content = (1..2_000).joinToString("\n") { "line $it " + "y".repeat(20) }
        val start = thinkingTailWindowStart(content, 8_000)
        val pinned = thinkingWindowSlice(content, start, 8_000, frozen = true)

        // Five successive tokens: the head must never move. (The tail may gain
        // at most the newline-snap slack, which is appended *below* the
        // reader's viewport and so cannot shift what they are looking at.)
        var live = content
        repeat(5) { step ->
            live = live + "\n" + "new $step " + "z".repeat(20)
            val afterGrowth = thinkingWindowSlice(live, start, 8_000, frozen = true)
            assertTrue("head moved after step $step", afterGrowth.startsWith(pinned))
            assertEquals(pinned.lines().first(), afterGrowth.lines().first())
        }
        val grown = live
        val afterGrowth = thinkingWindowSlice(grown, start, 8_000, frozen = true)

        // …and the following (unfrozen) window does move, so this is not just
        // "the slice never changes": a tail-following window drops the head on
        // every token, which is exactly the shift the freeze prevents.
        val tailAfterGrowth = thinkingWindowSlice(grown, thinkingTailWindowStart(grown, 8_000), 8_000)
        assertNotEquals(pinned, tailAfterGrowth)
        assertTrue(tailAfterGrowth.endsWith("z".repeat(20)))
        assertTrue(
            "the tail-following window must have dropped the frozen head",
            !tailAfterGrowth.startsWith(pinned),
        )
    }

    @Test
    fun `F-14 releasing the freeze snaps back to the live tail`() {
        val content = "x".repeat(12_000)
        val start = thinkingTailWindowStart(content, 8_000)
        val grown = content + "y"
        val released = thinkingWindowSlice(grown, thinkingTailWindowStart(grown, 8_000), 8_000)
        assertEquals(grown.length - 8_000, thinkingTailWindowStart(grown, 8_000))
        assertTrue(released.endsWith("y"))
        // A stale frozen offset is still clamped, never thrown.
        assertTrue(thinkingWindowSlice(grown, start, 8_000, frozen = true).length <= 8_000)
    }

    @Test
    fun `F-14 a frozen window is bounded by the window size`() {
        val content = "a".repeat(5_000)
        val slice = thinkingWindowSlice(content, 4_000, 8_000, frozen = true)
        assertEquals(1_000, slice.length)      // clamped to the content end
        val big = "a".repeat(20_000)
        assertEquals(8_000, thinkingWindowSlice(big, 500, 8_000, frozen = true).length)
    }

    @Test
    fun `F-14 a stale frozen start past the end is clamped`() {
        assertEquals("", thinkingWindowSlice("short", 99, 8_000, frozen = true))
        assertEquals("t", thinkingWindowSlice("short", 4, 8_000, frozen = true))
    }
}
