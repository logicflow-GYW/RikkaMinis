package com.rikkaminis.app.workspace

import com.rikkaminis.app.workspace.MemoryRollupRunner.Outcome
import com.rikkaminis.app.workspace.MemoryRollupEngine.ROLLUP_FILE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * JVM integration tests for [MemoryRollupRunner] against a real temp directory.
 * Verifies the full I/O pipeline: reading yesterday's log, distilling, writing
 * the rollup file, and idempotency. The source log is never modified.
 *
 * Dates are DERIVED from the fixed clock through the same formatter the runner
 * uses, so the tests stay correct in any default timezone (CI runs in UTC).
 */
class MemoryRollupRunnerTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "t6-test-${UUID.randomUUID().toString().take(8)}")
    private val memoryDir = File(tempDir, "minis-global/memory")

    /** Fixed clock: 2026-08-12 00:00:00 UTC. */
    private val fixedNow: Date = Date(1786492800000L)
    private val fixedClock: () -> Date = { fixedNow }
    private val dayMs = 86_400_000L
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Yesterday's file name as the runner computes it (same formatter, same timezone). */
    private fun yesterdayFile(): String = "${dateFmt.format(Date(fixedNow.time - dayMs))}.md"

    /** Today's file name (used for negative tests). */
    private fun todayFile(): String = "${dateFmt.format(fixedNow)}.md"

    private fun yesterdayRollupHeader(): String = "## Rollup ${dateFmt.format(Date(fixedNow.time - dayMs))}"

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ─── ROLLED_UP ────────────────────────────────────────────────────

    @Test fun rolledUp_producesRollupFile_withStableRules() {
        memoryDir.mkdirs()
        File(memoryDir, yesterdayFile()).writeText("""
            <!-- 2026-08-11 09:00:00 -->
            ## 分支隔离纪律
            任何代码修改必须在独立分支上完成，不得直接在 main 工作

            <!-- 2026-08-11 10:00:00 -->
            ## 踩坑
            顶层扩展函数 toProviderConfig 需要显式 import

            <!-- 2026-08-11 11:00:00 -->
            ## 待办事项
            待用户确认方案后再动

        """.trimIndent())

        val runner = MemoryRollupRunner(memoryDir, clock = fixedClock)
        val outcome = runner.runOnce()

        assertEquals(Outcome.ROLLED_UP, outcome)

        // Rollup file written
        val rollupFile = File(memoryDir, ROLLUP_FILE)
        assertTrue(rollupFile.exists())
        val content = rollupFile.readText()

        // Contains the stable rules (convention + lesson)
        assertTrue(content.contains(yesterdayRollupHeader()))
        assertTrue(content.contains("### 约定与纪律"))
        assertTrue(content.contains("分支隔离纪律"))
        assertTrue(content.contains("### 经验与知识点"))
        assertTrue(content.contains("toProviderConfig"))

        // Does NOT contain the transient to-do
        assertFalse(content.contains("待办事项"))

        // Original log untouched
        assertEquals(
            "<!-- 2026-08-11 09:00:00 -->\n## 分支隔离纪律\n任何代码修改必须在独立分支上完成，不得直接在 main 工作\n\n<!-- 2026-08-11 10:00:00 -->\n## 踩坑\n顶层扩展函数 toProviderConfig 需要显式 import\n\n<!-- 2026-08-11 11:00:00 -->\n## 待办事项\n待用户确认方案后再动",
            File(memoryDir, yesterdayFile()).readText().trim(),
        )
    }

    // ─── SKIPPED_ALREADY ──────────────────────────────────────────────

    @Test fun alreadyRolledUp_skipsIdempotently() {
        memoryDir.mkdirs()
        File(memoryDir, yesterdayFile()).writeText("## 纪律\n必须使用分支")
        // Pre-existing rollup file with the date section
        File(memoryDir, ROLLUP_FILE).writeText("${yesterdayRollupHeader()}\n\n### 约定与纪律\n- **纪律**：必须使用分支\n")

        val runner = MemoryRollupRunner(memoryDir, clock = fixedClock)
        assertEquals(Outcome.SKIPPED_ALREADY, runner.runOnce())

        // File unchanged (no second append)
        assertEquals(
            "${yesterdayRollupHeader()}\n\n### 约定与纪律\n- **纪律**：必须使用分支\n",
            File(memoryDir, ROLLUP_FILE).readText(),
        )
    }

    // ─── NO_LOG_YESTERDAY ─────────────────────────────────────────────

    @Test fun noLogYesterday_returnsNoLog() {
        memoryDir.mkdirs()
        // Only today's log, no yesterday's
        File(memoryDir, todayFile()).writeText("## 今天\n内容")

        val runner = MemoryRollupRunner(memoryDir, clock = fixedClock)
        assertEquals(Outcome.NO_LOG_YESTERDAY, runner.runOnce())

        // No rollup file created
        assertFalse(File(memoryDir, ROLLUP_FILE).exists())
    }

    @Test fun emptyLogYesterday_returnsNoLog() {
        memoryDir.mkdirs()
        File(memoryDir, yesterdayFile()).writeText("")
        assertEquals(Outcome.NO_LOG_YESTERDAY, MemoryRollupRunner(memoryDir, clock = fixedClock).runOnce())
    }

    // ─── NOTHING_TO_DISTILL ───────────────────────────────────────────

    @Test fun nothingToDistill_whenAllEntriesAreTransient() {
        memoryDir.mkdirs()
        File(memoryDir, yesterdayFile()).writeText("""
            <!-- 2026-08-11 09:00:00 -->
            **待办**：CI 绿 → 合并

            <!-- 2026-08-11 10:00:00 -->
            未实施：待用户确认方案

        """.trimIndent())

        val runner = MemoryRollupRunner(memoryDir, clock = fixedClock)
        assertEquals(Outcome.NOTHING_TO_DISTILL, runner.runOnce())

        // No rollup file created (nothing to write)
        assertFalse(File(memoryDir, ROLLUP_FILE).exists())
    }

    // ─── Source log invariants ────────────────────────────────────────

    @Test fun sourceLog_neverModified_afterRollup() {
        memoryDir.mkdirs()
        val log = "<!-- 2026-08-11 08:00:00 -->\n## 规则\n必须使用分支\n"
        File(memoryDir, yesterdayFile()).writeText(log)

        MemoryRollupRunner(memoryDir, clock = fixedClock).runOnce()
        assertEquals(log, File(memoryDir, yesterdayFile()).readText())
    }

    @Test fun largestOldEligibleLog_isSelectedOverSmallerYesterday() {
        memoryDir.mkdirs()
        val yesterday = yesterdayFile()
        val oldDate = dateFmt.format(Date(fixedNow.time - 3 * dayMs))
        File(memoryDir, yesterday).writeText("## 小日志\n必须使用分支")
        File(memoryDir, "$oldDate.md").writeText(
            "## 大旧日志\n" + "根因是旧日志不可达，必须按最大未蒸馏日志选择。\n".repeat(20),
        )

        assertEquals(Outcome.ROLLED_UP, MemoryRollupRunner(memoryDir, clock = fixedClock).runOnce())
        val rollup = File(memoryDir, ROLLUP_FILE).readText()
        assertTrue(rollup.contains("## Rollup $oldDate"))
        assertFalse(rollup.contains("## Rollup ${yesterday.removeSuffix(".md")}"))
    }

    @Test fun alreadyRolledLargestLog_isSkippedWithoutChangingRollup() {
        memoryDir.mkdirs()
        val oldDate = dateFmt.format(Date(fixedNow.time - 3 * dayMs))
        File(memoryDir, "$oldDate.md").writeText("## 纪律\n必须使用分支")
        val existing = "## Rollup $oldDate\n\n### 约定与纪律\n- **纪律**：必须使用分支\n"
        File(memoryDir, ROLLUP_FILE).writeText(existing)

        assertEquals(Outcome.SKIPPED_ALREADY, MemoryRollupRunner(memoryDir, clock = fixedClock).runOnce())
        assertEquals(existing, File(memoryDir, ROLLUP_FILE).readText())
    }

    @Test fun emptyLog_returnsNoLogAndLeavesSourceUntouched() {
        memoryDir.mkdirs()
        val yesterday = File(memoryDir, yesterdayFile())
        yesterday.writeText("")

        assertEquals(Outcome.NO_LOG_YESTERDAY, MemoryRollupRunner(memoryDir, clock = fixedClock).runOnce())
        assertEquals("", yesterday.readText())
        assertFalse(File(memoryDir, ROLLUP_FILE).exists())
    }

    @Test fun multipleRuns_idempotent() {
        memoryDir.mkdirs()
        File(memoryDir, yesterdayFile()).writeText("## 规则\n必须使用分支")
        val runner = MemoryRollupRunner(memoryDir, clock = fixedClock)

        assertEquals(Outcome.ROLLED_UP, runner.runOnce())
        assertEquals(Outcome.SKIPPED_ALREADY, runner.runOnce())
        assertEquals(Outcome.SKIPPED_ALREADY, runner.runOnce())
    }

    @Test fun rollupFile_accumulatesMultipleDays() {
        memoryDir.mkdirs()

        // Day 1: clock = fixedNow (yesterday = day A)
        val dayA = yesterdayFile()
        File(memoryDir, dayA).writeText("## 纪律\n使用分支")
        assertEquals(Outcome.ROLLED_UP, MemoryRollupRunner(memoryDir, clock = fixedClock).runOnce())

        // Day 2: clock advanced by 24h (yesterday = day B)
        val clock2 = { Date(fixedNow.time + dayMs) }
        val dayB = dateFmt.format(Date(fixedNow.time)) + ".md"
        File(memoryDir, dayB).writeText("## 经验\n根因是跨层 gap")
        assertEquals(Outcome.ROLLED_UP, MemoryRollupRunner(memoryDir, clock = clock2).runOnce())

        val content = File(memoryDir, ROLLUP_FILE).readText()
        assertTrue(content.contains("## Rollup ${dayA.removeSuffix(".md")}"))
        assertTrue(content.contains("## Rollup ${dayB.removeSuffix(".md")}"))
        assertTrue(content.contains("使用分支"))
        assertTrue(content.contains("根因是跨层 gap"))
    }
}