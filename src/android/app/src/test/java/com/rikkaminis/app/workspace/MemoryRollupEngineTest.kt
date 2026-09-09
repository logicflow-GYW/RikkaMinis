package com.rikkaminis.app.workspace

import com.rikkaminis.app.workspace.MemoryRollupEngine.Entry
import com.rikkaminis.app.workspace.MemoryRollupEngine.RollupClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for MemoryRollupEngine — the pure distillation logic behind
 * the T6 daily memory rollup. Guards entry splitting, classification
 * heuristics, dedup, output shape, and idempotency.
 */
class MemoryRollupEngineTest {

    // ─── extractEntries ──────────────────────────────────────────────────

    @Test fun extractEntries_splitsOnTimestampMarkers() {
        val text = """
            <!-- 2026-08-11 09:00:00 -->
            ## 第一条
            内容甲

            <!-- 2026-08-11 10:00:00 -->
            ## 第二条
            内容乙

        """.trimIndent()

        val entries = MemoryRollupEngine.extractEntries(text)
        assertEquals(2, entries.size)
        assertEquals("2026-08-11 09:00:00", entries[0].timestamp)
        assertEquals("## 第一条\n内容甲", entries[0].body)
        assertEquals("第一条", entries[0].title)
        assertEquals("2026-08-11 10:00:00", entries[1].timestamp)
        assertEquals("第二条", entries[1].title)
    }

    @Test fun extractEntries_markerlessFile_isSingleEntry() {
        val text = "## 无标记文件\n正文"
        val entries = MemoryRollupEngine.extractEntries(text)
        assertEquals(1, entries.size)
        assertNull(entries[0].timestamp)
        assertEquals("## 无标记文件\n正文", entries[0].body)
    }

    @Test fun extractEntries_blankFile_yieldsNothing() {
        assertTrue(MemoryRollupEngine.extractEntries("").isEmpty())
        assertTrue(MemoryRollupEngine.extractEntries("   \n  \n").isEmpty())
    }

    @Test fun extractEntries_skipsEmptyBodies() {
        val text = """
            <!-- 2026-08-11 09:00:00 -->

            <!-- 2026-08-11 10:00:00 -->
            ## 有内容
            正文

        """.trimIndent()
        val entries = MemoryRollupEngine.extractEntries(text)
        assertEquals(1, entries.size)
        assertEquals("有内容", entries[0].title)
    }

    @Test fun extractTitle_picksFirstHeading() {
        val body = "前导文本\n## 真正的标题\n## 第二个标题\n正文"
        assertEquals("真正的标题", MemoryRollupEngine.extractTitle(body))
    }

    @Test fun extractTitle_returnsNullWithNoHeading() {
        assertNull(MemoryRollupEngine.extractTitle("没有标题的正文"))
    }

    // ─── classify ────────────────────────────────────────────────────────

    @Test fun classify_unfinishedWithoutConvergence_isTransient() {
        val entry = Entry("t", "**待办**：CI 绿 → ff 合并 main → 删分支", null)
        assertEquals(RollupClass.TRANSIENT, MemoryRollupEngine.classify(entry))

        val entry2 = Entry("t", "尚未实施（待用户确认方案再动）", null)
        assertEquals(RollupClass.TRANSIENT, MemoryRollupEngine.classify(entry2))
    }

    @Test fun classify_unfinishedButConverged_isKept() {
        // Both signals present: the convergence wins (the note records a
        // finished fact even though it also mentions a pending follow-up).
        val entry = Entry("t", "修复已合并 main（fc6d8e6），待验证真机行为", "裸 valueOf 修复")
        assertEquals(RollupClass.LESSON, MemoryRollupEngine.classify(entry))
    }

    @Test fun classify_conventionSignal_isConvention() {
        val entry = Entry("t", "任何代码修改必须在独立分支上完成，不得直接在 main 工作", "分支隔离纪律")
        assertEquals(RollupClass.CONVENTION, MemoryRollupEngine.classify(entry))
    }

    @Test fun classify_userDecisionSignal_isUserDecision() {
        val entry = Entry("t", "用户批评\"逐个等 CI 太慢\"，要求任务分解、独立分支并行推进", "多任务并行模式")
        assertEquals(RollupClass.USER_DECISION, MemoryRollupEngine.classify(entry))
    }

    @Test fun classify_titledBody_isLesson() {
        val entry = Entry("t", "Room DAO @Transaction 内嵌套 suspend DAO 方法合法，CI 编译通过", "DAO 经验")
        assertEquals(RollupClass.LESSON, MemoryRollupEngine.classify(entry))
    }

    @Test fun classify_shortNoise_isTransient() {
        assertEquals(RollupClass.TRANSIENT, MemoryRollupEngine.classify(Entry(null, "记忆已保存", null)))
        assertEquals(RollupClass.TRANSIENT, MemoryRollupEngine.classify(Entry(null, "ok", null)))
    }

    @Test fun classify_blankBody_isTransient() {
        assertEquals(RollupClass.TRANSIENT, MemoryRollupEngine.classify(Entry(null, "  ", null)))
    }

    // ─── dedupeEntries ───────────────────────────────────────────────────

    @Test fun dedupe_mergesSameTitle_keepingFirstOrder() {
        val entries = listOf(
            Entry("2026-08-11 09:00:00", "## T1 工具并发白名单\n白名单 file_read", "T1 工具并发白名单"),
            Entry("2026-08-11 10:00:00", "## T1 工具并发白名单\nbrowser_use 不白名单", "T1 工具并发白名单"),
        )
        val deduped = MemoryRollupEngine.dedupeEntries(entries)
        assertEquals(1, deduped.size)
        assertEquals("2026-08-11 09:00:00", deduped[0].timestamp)
        assertTrue(deduped[0].body.contains("白名单 file_read"))
        assertTrue(deduped[0].body.contains("browser_use 不白名单"))
    }

    @Test fun dedupe_distinctTitles_staySeparate() {
        val entries = listOf(
            Entry("2026-08-11 09:00:00", "## A\n内容", "A"),
            Entry("2026-08-11 10:00:00", "## B\n内容", "B"),
        )
        assertEquals(2, MemoryRollupEngine.dedupeEntries(entries).size)
    }

    // ─── buildRollupText ─────────────────────────────────────────────────

    @Test fun buildRollupText_groupsByClass_inExpectedOrder() {
        val entries = listOf(
            Entry("t", "必须在独立分支工作", "分支纪律"),
            Entry("t", "用户确认了方案 A", "用户决策"),
            Entry("t", "根因是跨层 gap", "踩坑"),
        )
        val text = MemoryRollupEngine.buildRollupText("2026-08-11", entries, "2026-08-12 03:00")

        assertTrue(text.startsWith("## Rollup 2026-08-11"))
        assertTrue(text.contains("### 约定与纪律"))
        assertTrue(text.contains("### 用户反馈与决定"))
        assertTrue(text.contains("### 经验与知识点"))
        // Section order: convention before decision before lesson
        assertTrue(text.indexOf("### 约定与纪律") < text.indexOf("### 用户反馈与决定"))
        assertTrue(text.indexOf("### 用户反馈与决定") < text.indexOf("### 经验与知识点"))
        // Bullets carry the bold title
        assertTrue(text.contains("- **分支纪律**"))
        assertTrue(text.contains("- **用户决策**"))
        assertTrue(text.contains("- **踩坑**"))
    }

    @Test fun buildRollupText_filtersTransientEntries() {
        val entries = listOf(
            Entry("t", "待办：合并到 main", "待办"),
            Entry("t", "修复完成，CI 绿", "修复"),
        )
        val text = MemoryRollupEngine.buildRollupText("2026-08-11", entries, "auto")
        assertFalse(text.contains("待办"))
        assertTrue(text.contains("修复完成"))
    }

    @Test fun buildRollupText_allTransient_yieldsEmpty() {
        val entries = listOf(Entry("t", "待办：X", "待办"))
        assertEquals("", MemoryRollupEngine.buildRollupText("2026-08-11", entries, "auto"))
    }

    @Test fun buildRollupText_removesHeadingLinesFromBody() {
        val entries = listOf(
            Entry("t", "## 标题行\n正文第一行\n正文第二行", "标题行"),
        )
        val text = MemoryRollupEngine.buildRollupText("2026-08-11", entries, "auto")
        // The title appears exactly once as the bold bullet; the body's own
        // "## 标题行" line must not be duplicated.
        assertEquals(1, Regex("标题行").findAll(text).count())
        assertTrue(text.contains("正文第一行"))
        assertTrue(text.contains("正文第二行"))
    }

    // ─── hasRollupForDate ────────────────────────────────────────────────

    @Test fun hasRollupForDate_matchesOwnSection() {
        val content = "## Rollup 2026-08-11\n\n### 经验与知识点\n- 条目\n"
        assertTrue(MemoryRollupEngine.hasRollupForDate(content, "2026-08-11"))
        assertFalse(MemoryRollupEngine.hasRollupForDate(content, "2026-08-10"))
        assertFalse(MemoryRollupEngine.hasRollupForDate("", "2026-08-11"))
    }

    @Test fun hasRollupForDate_doesNotMatchSimilarDates() {
        // 2026-08-1 must not match 2026-08-11 (Regex.escape + boundary).
        val content = "## Rollup 2026-08-11\n"
        assertFalse(MemoryRollupEngine.hasRollupForDate(content, "2026-08-1"))
        assertFalse(MemoryRollupEngine.hasRollupForDate(content, "2026-08-110"))
    }
}