package com.rikkaminis.app.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [diag/reentry-latency-anchor] 纯 JVM 单测：钉住"点击锚点"的生命周期契约。
 *
 * 这套埋点唯一的用处是回答"用户从点开会话到看见内容等了多久"，而这个答案
 * 只在 `sinceClickMs` 上。锚点要么在 TAP 处建立、要么在首帧处被回收——
 * 这两条只要有一条破了，日志就会安静地给出错误的数字（=0 或者一段
 * 与用户无关的时间），而不是报错。所以这里把契约钉死：
 *
 *   1. 没有 click() → sinceClickMs = -1（老行为，深链/通知路径不该被伪造成"用户点击"）
 *   2. click() 之后 → sinceClickMs >= 0（真实等待从 TAP 起算）
 *   3. end() 之后 → 锚点被回收，后续 step 回到 -1，且不再持有该 session 的任何 map 条目
 *   4. 不同 session 的锚点互不干扰
 *   5. 放弃的导航（end() 永远到不了）由硬上限兜住，表不可能无界增长
 */
class PerfLongCtxAnchorTest {

    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        PerfLongCtx.resetForTest()
        lines.clear()
        PerfLongCtx.sinkForTest = { lines.add(it) }
    }

    @After
    fun tearDown() {
        PerfLongCtx.sinkForTest = null
        PerfLongCtx.resetForTest()
    }

    /** 从 "… sinceClickMs=N …" 里取 N。取不到就 fail，而不是静默当成 0。 */
    private fun sinceClickMsOf(index: Int): Long {
        val line = lines[index]
        val m = Regex("sinceClickMs=(-?\\d+)").find(line)
        assertTrue("no sinceClickMs in: $line", m != null)
        return m!!.groupValues[1].toLong()
    }

    private fun elapsedMsOf(index: Int): Long {
        val line = lines[index]
        val m = Regex("elapsedMs=(-?\\d+)").find(line)
        assertTrue("no elapsedMs in: $line", m != null)
        return m!!.groupValues[1].toLong()
    }

    // ---------- 1. 无锚点 ----------

    @Test
    fun `step without a click reports minus one and emits nothing extra`() {
        PerfLongCtx.step("s1", "loadSession.enter")
        assertEquals(1, lines.size)
        assertEquals(-1L, sinceClickMsOf(0))
        assertTrue(lines[0].contains("step=loadSession.enter session=s1"))
        assertFalse(PerfLongCtx.isAnchoredForTest("s1"))
    }

    // ---------- 2. 点击建立锚点 ----------

    @Test
    fun `click anchors the session and later steps carry a non negative wait`() {
        PerfLongCtx.click("s1")
        assertTrue(PerfLongCtx.isAnchoredForTest("s1"))
        assertEquals("click 自身也发一行", 1, lines.size)
        assertEquals("click 行自身 sinceClickMs 必须为 0", 0L, sinceClickMsOf(0))

        PerfLongCtx.step("s1", "loadSession.enter")
        assertTrue("点击后的 step 必须带真实等待", sinceClickMsOf(1) >= 0L)
    }

    @Test
    fun `click resets a previous timeline for the same session`() {
        PerfLongCtx.click("s1")
        PerfLongCtx.step("s1", "loadSession.enter")
        PerfLongCtx.click("s1") // 用户又点了一次
        val anchorLine = lines.size - 1
        assertEquals(0L, sinceClickMsOf(anchorLine))

        PerfLongCtx.step("s1", "loadSession.enter")
        // 第二次点击之后，等待重新从 0 起算（不是接着上一轮累积）
        assertTrue(sinceClickMsOf(lines.size - 1) < 1_000L)
    }

    // ---------- 3. end() 回收 ----------

    @Test
    fun `end emits the settled line then reclaims the anchor`() {
        PerfLongCtx.click("s1")
        PerfLongCtx.step("s1", "lazyColumn.firstItem.placed")
        PerfLongCtx.end("s1", "reentry.settled")

        assertEquals("end 必须自己发一行（承载真实的 点→首帧 延迟）", 3, lines.size)
        assertTrue(lines[2].contains("step=reentry.settled"))
        assertTrue(sinceClickMsOf(2) >= 0L)

        assertFalse("anchor must be reclaimed on end()", PerfLongCtx.isAnchoredForTest("s1"))

        // 收尾之后的迟到 step 不能再报出一个"看起来真实"的等待
        PerfLongCtx.step("s1", "late")
        assertEquals(-1L, sinceClickMsOf(3))
    }

    @Test
    fun `end on an unknown session is a no-op that still reports minus one`() {
        PerfLongCtx.end("never-clicked")
        assertEquals(1, lines.size)
        assertEquals(-1L, sinceClickMsOf(0))
        assertFalse(PerfLongCtx.isAnchoredForTest("never-clicked"))
    }

    // ---------- 4. 多会话隔离 ----------

    @Test
    fun `anchors are per session and do not leak across them`() {
        PerfLongCtx.click("s1")
        PerfLongCtx.step("s2", "loadSession.enter") // 另一个会话，从未点击
        assertEquals("未被点击的会话必须保持 -1", -1L, sinceClickMsOf(lines.size - 1))

        PerfLongCtx.end("s1")
        assertFalse(PerfLongCtx.isAnchoredForTest("s1"))
        assertFalse(PerfLongCtx.isAnchoredForTest("s2"))
    }

    // ---------- 5. elapsedMs 从锚点起算 ----------
    @Test
    fun `the first step after a click measures its delta from the click`() {
        PerfLongCtx.click("s1")
        PerfLongCtx.step("s1", "loadSession.enter")
        // click() 也更新 lastNs，因此第一条 step 的 elapsedMs 是"距点击"
        assertTrue("elapsedMs 必须从点击起算", elapsedMsOf(1) >= 0L)
        assertEquals(0L, elapsedMsOf(0))
    }

    // ---------- 6. 锚点表有硬上限 ----------

    @Test
    fun `anchor map stays bounded even when end is never called`() {
        // 放弃的导航（点了 B 又立刻回 A，B 的 ChatScreen 从未 compose）不会走到
        // end()。表必须有硬上限，否则"每个开过的会话一条"会重新长回来。
        repeat(40) { i -> PerfLongCtx.click("s$i") }
        assertFalse("最早的锚点必须已被上限清掉", PerfLongCtx.isAnchoredForTest("s0"))
        assertTrue("最近的锚点必须还在", PerfLongCtx.isAnchoredForTest("s39"))
    }
}
