package com.rikkaminis.app.data.repository

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * [debt-1026 B5] One snapshot, two values.
 *
 * `buildSystemPrompt()` asked for BOTH `dailyLogSizeSummary()` and
 * `largestDailyLogBytes()`, and each of those walked `dailyLogSizes()` — which
 * reads MEMORY-ROLLUP.md in full plus the full text of every candidate daily
 * log ([dv28-A3] needs the content to judge "still distillable"). Every
 * system-prompt build therefore read the whole memory directory twice and ran
 * the rollup classification twice over the same bytes.
 *
 * The numbers below are HARDCODED, not derived from the code under test: the
 * point of the arm is that the merged snapshot produces the exact strings and
 * byte counts the two old accessors produced, so an arm that recomputed the
 * expected value from the same helper would pass even if both paths drifted.
 */
class DailyLogRollupMetricsTest {

    private val tempDir = File(
        System.getProperty("java.io.tmpdir"),
        "mem-metrics-${UUID.randomUUID().toString().take(8)}",
    )
    private val memoryDir = File(tempDir, "minis-global/memory")
    private lateinit var previousLocale: Locale

    // formatFileSize uses the default locale ("%.1f KB"), so pin it: otherwise
    // a comma-decimal locale would turn "1.0 KB" into "1,0 KB" and the arm
    // would fail for a reason that has nothing to do with the code under test.
    @Before
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        memoryDir.mkdirs()
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
        tempDir.deleteRecursively()
    }

    /** A distillable entry of exactly [padding] + 9 bytes (`## entry\n` = 9). */
    private fun log(name: String, padding: Int) {
        File(memoryDir, name).writeText("## entry\n" + "a".repeat(padding))
    }

    @Test
    fun `one snapshot reproduces both legacy values`() {
        log("2026-01-01.md", 100)   // 109 B
        log("2026-01-02.md", 400)   // 409 B  ← largest
        log("2026-01-03.md", 200)   // 209 B

        val repo = MemoryRepository(memoryDir)
        val metrics = repo.dailyLogRollupMetrics()

        assertEquals(
            "largest daily log 2026-01-02.md (409 B), total 3 logs 727 B",
            metrics.summary,
        )
        assertEquals(409L, metrics.largestBytes)

        // The two public accessors stay available (other callers) and now read
        // from the same snapshot — their output must not drift from it.
        assertEquals(metrics.summary, repo.dailyLogSizeSummary())
        assertEquals(metrics.largestBytes, repo.largestDailyLogBytes())
    }

    @Test
    fun `empty memory dir answers null and zero`() {
        val repo = MemoryRepository(memoryDir)

        val metrics = repo.dailyLogRollupMetrics()

        assertNull(metrics.summary)
        assertEquals(0L, metrics.largestBytes)
        assertNull(repo.dailyLogSizeSummary())
        assertEquals(0L, repo.largestDailyLogBytes())
    }

    @Test
    fun `already distilled logs are not candidates and disappear from both fields`() {
        log("2026-01-01.md", 100)
        log("2026-01-02.md", 400)
        // A rollup section naming 2026-01-02 removes it from the candidate set
        // (dv15-A4), so the largest must fall back to the other log.
        File(memoryDir, "MEMORY-ROLLUP.md").writeText("## Rollup 2026-01-02\n- rule\n")

        val metrics = MemoryRepository(memoryDir).dailyLogRollupMetrics()

        assertEquals("largest daily log 2026-01-01.md (109 B), total 1 logs 109 B", metrics.summary)
        assertEquals(109L, metrics.largestBytes)
    }
}
