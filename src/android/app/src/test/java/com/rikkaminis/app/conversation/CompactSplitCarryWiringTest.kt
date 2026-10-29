package com.rikkaminis.app.conversation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [meta-audit-1029 R3 B11] F-01 — the halving branch must carry the previous
 * rounds' summary into the first half.
 *
 * The defect was pure wiring (the recursion was called with `null`), so the
 * call site is what has to be pinned; the framing itself is covered by
 * [ContextCompactorCompactInputTest]. Comments are stripped before the
 * assertions run — the rationale block next to the call names the buggy
 * spelling, and a shape test that a comment can satisfy has no teeth.
 */
class CompactSplitCarryWiringTest {

    @Test
    fun firstHalfIsSummarizedOnTopOfThePreviousSummary() {
        val code = code(ORCH_REL)
        assertTrue(
            "the halving branch no longer exists — re-anchor this test",
            "val firstHalf = messages.subList(0, mid)" in code,
        )
        assertTrue(
            "the first (older) half is summarized with a null previous summary — "
                + "everything distilled by earlier compact rounds is dropped from the "
                + "new summary the moment a pass hits ContextTooLarge",
            "generateCompactSummaryWithSplitting(firstHalf, prevSummaryStripped, depth + 1)" in code,
        )
        assertTrue(
            "the pre-fix spelling (null instead of prevSummaryStripped) is back",
            "generateCompactSummaryWithSplitting(firstHalf, null, depth + 1)" !in code,
        )
    }

    @Test
    fun secondHalfDoesNotDoubleCountTheSummary() {
        val code = code(ORCH_REL)
        assertTrue(
            "the second (newer) half must NOT receive the previous summary as well — "
                + "the first half already incorporated it, so both halves would put it "
                + "in the merge prompt twice",
            "generateCompactSummaryWithSplitting(secondHalf, null, depth + 1)" in code,
        )
    }

    @Test
    fun depthZeroAssemblyGoesThroughTheSharedFramer() {
        val code = code(ORCH_REL)
        assertTrue(
            "the depth-0 summarizer input must be built by the shared framer, not by an "
                + "inline copy that can drift from the recursion",
            "ContextCompactor.compactInputText(prevSummaryStripped, transcript)" in code,
        )
        assertTrue(
            "the previous summary is no longer stripped of its pinned block before it "
                + "reaches the model",
            "val prevSummaryStripped = if (depth == 0) stripPinnedSection(previousSummary) "
                + "else previousSummary" in code,
        )
    }

    // ── helpers (independent of the scan gates on purpose) ────────────

    private fun code(relative: String): String = stripComments(readRepoFile(relative))

    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inBlock = false
        var inLine = false
        var inString = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else ' '
            when {
                inLine -> if (c == '\n') { inLine = false; sb.append(c) }
                inBlock -> if (c == '*' && next == '/') { inBlock = false; i += 1 }
                inString -> {
                    sb.append(c)
                    if (c == '\\') { sb.append(next); i += 1 } else if (c == '"') inString = false
                }
                else -> when {
                    c == '"' -> { inString = true; sb.append(c) }
                    c == '/' && next == '/' -> { inLine = true; i += 1 }
                    c == '/' && next == '*' -> { inBlock = true; i += 1 }
                    else -> sb.append(c)
                }
            }
            i += 1
        }
        return sb.toString()
    }

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    private companion object {
        const val ORCH_REL = "app/src/main/java/com/rikkaminis/app/conversation/CompactOrchestration.kt"
    }
}
