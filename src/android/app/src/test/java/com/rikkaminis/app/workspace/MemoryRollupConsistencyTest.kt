package com.rikkaminis.app.workspace

import com.rikkaminis.app.workspace.MemoryRollupEngine.Entry
import com.rikkaminis.app.workspace.MemoryRollupEngine.RollupClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-R3-E F-11] The candidacy/section consistency contract: there is ONE
 * judging list — the DEDUPED entries — behind `isRollupCandidate`, the
 * executor's picker, and `buildRollupText`.
 *
 * Defect being pinned: the two classify sites saw different lists. The
 * candidacy side classified the RAW entries, the section side the MERGED ones,
 * and merging can flip the verdict — two same-title entries, the first a
 * converged lesson, the second carrying a weak unfinished word ("待验证") with
 * no convergence word, merge into a body that classifies as TRANSIENT. The log
 * then stayed the top pick forever while every rollup call answered
 * NOTHING_TO_DISTILL, starving the smaller logs behind it.
 */
class MemoryRollupConsistencyTest {

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun entry(title: String?, body: String) = Entry(
        timestamp = "2026-10-28 09:00:00",
        body = "## $title\n$body",
        title = title,
    )

    /** First entry: titled, converged (LESSON on its own). Second: SAME title,
     *  body carries a weak unfinished word and no convergence word. */
    private val mergedIntoTransient = listOf(
        entry("接口重构", "按域拆分调用点，把公共函数提到上层。"),
        entry("接口重构", "接口联调待验证，覆盖率还没跑。"),
    )

    // ─── the flip, pinned ────────────────────────────────────────────────────

    @Test
    fun `raw side saw a LESSON - that was the defect's candidacy half`() {
        // The OLD behavior at the candidacy site (raw list): the first entry
        // alone classified as LESSON → "distillable" → the log was picked.
        // Literal expectation, kept as documentation of the mechanism.
        assertEquals(RollupClass.LESSON, MemoryRollupEngine.classify(mergedIntoTransient.first()))
    }

    @Test
    fun `merging the pair flips the verdict to TRANSIENT`() {
        val deduped = MemoryRollupEngine.dedupeEntries(mergedIntoTransient)
        assertEquals(1, deduped.size)
        assertEquals(RollupClass.TRANSIENT, MemoryRollupEngine.classify(deduped.single()))
    }

    // ─── the fix: one judging list everywhere ────────────────────────────────

    @Test
    fun `distillableEntries dedupes internally - the pair is judged TRANSIENT`() {
        // On the RAW list this used to return the first entry (LESSON);
        // the single judging list answers from the merged body: TRANSIENT.
        assertTrue(MemoryRollupEngine.distillableEntries(mergedIntoTransient).isEmpty())
    }

    @Test
    fun `candidacy and section now agree on the same log`() {
        val logBody = """
            <!-- 2026-10-28 09:00:00 -->
            ## 接口重构
            按域拆分调用点，把公共函数提到上层。

            <!-- 2026-10-28 10:00:00 -->
            ## 接口重构
            接口联调待验证，覆盖率还没跑。

        """.trimIndent()
        val entries = MemoryRollupEngine.extractEntries(logBody)
        // The section builder and the shared predicate must answer identically:
        // both empty (the log yields nothing distillable).
        assertTrue(MemoryRollupEngine.distillableEntries(entries).isEmpty())
        assertEquals("", MemoryRollupEngine.buildRollupText("2026-10-28", entries, generatedAt = "auto"))
    }

    @Test
    fun `a log that is genuinely distillable still passes the same gate`() {
        val logBody = """
            <!-- 2026-10-28 09:00:00 -->
            ## 接口重构
            按域拆分调用点，把公共函数提到上层。

        """.trimIndent()
        val entries = MemoryRollupEngine.extractEntries(logBody)
        assertEquals(1, MemoryRollupEngine.distillableEntries(entries).size)
        // The section renders from the SAME list — no double dedupe drift.
        val section = MemoryRollupEngine.buildRollupText("2026-10-28", entries, generatedAt = "auto")
        assertTrue(section.contains("接口重构"))
    }
}
