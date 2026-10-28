package com.rikkaminis.app.sandbox

import org.junit.Assert.*
import org.junit.Test

/**
 * [meta-audit-1028 E3 / F-08] Terminal output cleaning — the shapes the B04
 * report said leave debris, pinned as literal expectations.
 *
 * Escape sequences are spelled with Kotlin's \u001B escape rather than a raw
 * ESC byte inside the string literal. Algebraic padding test included.
 *
 * Every case below was reproduced against the PRE-FIX implementation first
 * (`probe-e3/F08Probe.kt` printed F08_PROBE=FAIL(4) on the unchanged tree for
 * cases a / b1 / b2 / c). That is what stops these from being "expectations
 * that happen to match whatever the code does".
 *
 * Idempotency is asserted too: [TerminalSanitizer.sanitize] runs on already
 * sanitized text at least once more downstream, so a second pass must never
 * change a result that is already clean.
 */
class TrackedTerminalSanitizerTest {

    private val ESC = "\u001B"
    private val BEL = "\u0007"

    private fun assertClean(raw: String, expected: String) {
        val once = TerminalSanitizer.sanitize(raw)
        assertEquals("input=${raw.toVisible()}", expected, once)
        val twice = TerminalSanitizer.sanitize(once)
        assertEquals("not idempotent: ${raw.toVisible()}", once, twice)
    }

    /** Show escape bytes instead of applying them to the test runner's console. */
    private fun String.toVisible(): String = replace(ESC, "\\e")
        .replace("\r", "\\r").replace("\n", "\\n").replace(BEL, "\\a")

    // ── (a) OSC 8 hyperlinks: greedy match used to delete the visible text ──

    @Test
    fun `two OSC 8 hyperlinks keep the text between them`() {
        // ls --hyperlink=always, gcc/clang/cargo diagnostics. The old pattern's
        // OSC body `[^\u0007]*` ran from the first ESC] to the LAST terminator
        // in the output, deleting everything in between.
        assertClean(
            "before ${ESC}]8;;https://example.com${ESC}\\IMPORTANT_LINK_TEXT${ESC}]8;;${ESC}\\ after",
            "before IMPORTANT_LINK_TEXT after",
        )
    }

    @Test
    fun `OSC 8 hyperlink keeps its label across a newline`() {
        assertClean(
            "file ${ESC}]8;;file:///root/a.txt${ESC}\\a.txt${ESC}]8;;${ESC}\\\nplain line\n",
            "file a.txt\nplain line\n",
        )
    }

    @Test
    fun `OSC terminated by BEL does not eat the next line`() {
        assertClean("${ESC}]0;window title${BEL}first\nsecond", "first\nsecond")
    }

    // ── (b) CR folding must operate on VISIBLE columns ─────────────────────

    @Test
    fun `colored progress line overwritten by a longer plain line`() {
        // 9 invisible bytes used to push the visible text 9 columns right, so
        // the 15-char overwrite landed at column 15 and left "g 50%" behind.
        assertClean(
            "${ESC}[33mDownloading 50%${ESC}[0m\rDone!          ",
            "Done!          ",
        )
    }

    @Test
    fun `overwrite shorter than the color prefix leaves no CSI debris`() {
        // Two chars overwrote the ESC and the '[' of ESC[33m, so the rest of
        // the escape lost its introducer and survived to the output as "33m".
        assertClean("${ESC}[33mABC\rOK", "OKC")
    }

    @Test
    fun `uncolored CR overwrite semantics are unchanged`() {
        // Historical behaviour this file already pinned — kept so the pass
        // reordering cannot silently change plain cases.
        assertClean("  50%[=====>    ]\r 100%[=========>]", " 100%[=========>]")
        assertClean("AAAA\rBB", "BBAA")
        assertClean("line1\rLINE\nline2", "LINE1\nline2")
    }

    // ── (c) DEC private-mode sequences (cursor show/hide) ──────────────────

    @Test
    fun `DEC private mode cursor hide and show are stripped`() {
        // top, tqdm, npm, pip: ESC[?25l / ESC[?25h. Without the '?' parameter
        // byte the old CSI branch never matched and "[?25l" stayed visible.
        assertClean("${ESC}[?25lLoading...\nDone${ESC}[?25h", "Loading...\nDone")
    }

    @Test
    fun `private mode with parameters is stripped`() {
        assertClean("${ESC}[?1049h${ESC}[?25hidden${ESC}[?1049lEND", "iddenEND")
    }

    // ── boundary fixtures required by the task book ────────────────────────

    @Test
    fun `empty input stays empty`() {
        assertEquals("", TerminalSanitizer.sanitize(""))
    }

    @Test
    fun `value containing a single quote survives every pass`() {
        assertClean("it's fine\r", "it's fine")
    }

    @Test
    fun `NUL and DEL bytes are dropped`() {
        assertClean("A\u0000B\u007FC", "AB\u007FC")
    }

    @Test
    fun `CRLF line endings normalize to LF`() {
        assertClean("line1\r\nline2\r\nline3", "line1\nline2\nline3")
    }

    @Test
    fun `mixed truecolor plus progress plus private mode in one stream`() {
        assertClean(
            "${ESC}[38;2;12;34;56mreal${ESC}[39mwork ${ESC}]8;;http://x${ESC}\\here${ESC}]8;;${ESC}\\ " +
                "${ESC}[?25l50%\r100%${ESC}[?25h\n",
            "100%work here 50%\n",
        )
    }

    @Test
    fun `run of blank lines collapses to two`() {
        assertClean("a\n\n\n\n\nb", "a\n\nb")
    }

    @Test
    fun `legitimate null line survives`() {
        // [verify-1015 BUG-02] collateral damage that must not come back.
        assertClean("BEGIN\nnull\nEND", "BEGIN\nnull\nEND")
    }

    @Test
    fun `trailing padding after an overwrite is preserved`() {
        // Deliberately no trim() in sanitize: trailing padding is what erases
        // the tail of a previously longer line. "long line here" (14 chars,
        // 7 visible) overwritten by "short    " (9 chars) leaves columns
        // 9..13 of the old line intact.
        assertClean("long line here\rshort    ", "short     here")
    }
}
