package com.rikkaminis.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [R5-E F-03] Wiring shape for `performWriteBatch` — the parts a pure-function
 * test cannot reach (the apply loop's catch arm and the tail call), pinned by
 * reading the real source.
 *
 * Owner resolution uses INDENT DOMAINS, not fixed character windows (the E5
 * 900-char and C4 1200-char windows both false-roma once an implementation
 * inserted a required line and the distance drifted past the window): the
 * owner of a line is found by walking UP from it to the first line with
 * strictly shallower indentation.
 */
class ConfigWriteBatchWiringTest {

    private val src: String by lazy {
        val relative = "src/android/app/src/main/java/com/rikkaminis/app/config/ConfigBridge.kt"
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return@lazy f.readText()
            dir = dir.parentFile
        }
        error("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    private val lines = src.lines()

    /** Index of the first line containing [needle] at or after [from] (0-based). */
    private fun findLine(needle: String, from: Int = 0): Int =
        lines.indices.firstOrNull { it >= from && lines[it].contains(needle) }
            ?: error("line not found: $needle")

    /**
     * Lines owned by the same block as line [anchor] (inclusive), discovered by
     * walking up to the first line whose indent is strictly less than the
     * anchor's (the block's opening statement) and down to the first line whose
     * indent equals the opener's at a statement boundary.
     */
    private fun indentDomain(anchor: Int): IntRange {
        val anchorIndent = lines[anchor].takeWhile { it == ' ' }.length
        var top = anchor
        while (top > 0 && lines[top].takeWhile { it == ' ' }.length >= anchorIndent) top--
        // `top` is now the block's opening line (shallower indent).
        val topIndent = lines[top].takeWhile { it == ' ' }.length
        var bottom = anchor + 1
        while (bottom < lines.size) {
            val line = lines[bottom]
            if (line.isNotBlank() && line.takeWhile { it == ' ' }.length <= topIndent) break
            bottom++
        }
        return top..(bottom - 1)
    }

    @Test
    fun applyLoopCatchClassifiesRethrowsOnCancellationAndRecordsFailed() {
        // Anchor on the classifier call (the only `classifyWriteFailure(e)` in
        // the file) and take its indent domain: walking UP from it lands on the
        // catch header that owns the call. Anchoring on "} catch (e: Throwable)"
        // directly is ambiguous — the read path has its own generic catch.
        val anchor = findLine("val failure = classifyWriteFailure(e)")
        val domain = indentDomain(anchor)
        val body = lines.slice(domain).joinToString("\n")

        assertTrue(
            "the classifier call must be owned by a catch arm",
            body.contains("} catch (e: Throwable) {"),
        )
        assertTrue(
            "catch must classify via the shared classifier",
            body.contains("classifyWriteFailure(e)"),
        )
        assertTrue(
            "cancellation (null classification) must rethrow, never be recorded",
            body.contains("if (failure == null) throw e"),
        )
        assertTrue(
            "every classified failure must land in the failed[] list",
            body.contains("failed.put(writeFailureEntry("),
        )
        assertFalse(
            "the arm must not invent its own error codes (single source in classifier)",
            Regex("""put\("error", "(?!partial_write)""").containsMatchIn(body),
        )
    }

    @Test
    fun batchTailDelegatesTheVerdictToBuildWriteEnvelope() {
        val tail = findLine("buildWriteEnvelope(applied = applied, failed = failed, earlyError = earlyError)")
        // The call sits inside the Approved branch of the confirmation outcome.
        val domain = indentDomain(tail)
        assertTrue(
            "the envelope call must be owned by the Approved branch",
            lines.slice(domain).joinToString("\n").contains("is ConfirmOutcome.Approved"),
        )
    }

    @Test
    fun oldInlineVerdictIsGone() {
        // The old tail built `ok:true` / `all_rejected` inline right after the
        // loop; the envelope builder is now the only place that shape exists
        // (ConfigWriteEnvelopeTest pins it behaviourally over there).
        val loopEnd = findLine("buildWriteEnvelope(applied = applied, failed = failed, earlyError = earlyError)")
        val domain = indentDomain(loopEnd)
        val body = lines.slice(domain).joinToString("\n")
        assertFalse(
            "Approved branch must not hand-build the success envelope",
            body.contains("put(\"ok\", true)"),
        )
        assertFalse(
            "Approved branch must not hand-build all_rejected (the prose comment "
                + "inside the catch arm is allowed to MENTION it; only the code "
                + "shape is forbidden)",
            body.contains("put(\"error\", \"all_rejected\")"),
        )
    }

    @Test
    fun failedListIsDeclaredBesideApplied() {
        val failedDecl = findLine("val failed = JSONArray()")
        val domain = indentDomain(failedDecl)
        val body = lines.slice(domain).joinToString("\n")
        assertTrue("failed[] belongs to the same batch as applied[]", body.contains("val applied = JSONArray()"))
        assertTrue(
            "the KDoc at the declaration must point at the contract",
            body.contains("[R5-E F-03]"),
        )
    }

    @Test
    fun envelopeBuilderLivesOnceInTheFile() {
        assertEquals(
            "buildWriteEnvelope must be defined exactly once (pure, top-level)",
            1,
            lines.count { it.contains("internal fun buildWriteEnvelope(") },
        )
        assertEquals(
            "classifyWriteFailure must be defined exactly once",
            1,
            lines.count { it.contains("internal fun classifyWriteFailure(") },
        )
    }
}
