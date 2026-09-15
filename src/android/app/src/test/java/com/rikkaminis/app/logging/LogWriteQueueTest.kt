package com.rikkaminis.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LogWriteQueue] — the pure-JVM async writer behind AppLogger.
 * No Android dependencies; runnable with kotlinc + JUnitCore (see repo
 * verification ladder level 1).
 */
class LogWriteQueueTest {

    private class Recorder {
        val lines = mutableListOf<String>()
        val dates = mutableListOf<String>()
        val notices = mutableListOf<Long>()

        fun queue(capacity: Int): LogWriteQueue = LogWriteQueue(
            sink = { date, line ->
                dates.add(date)
                lines.add(line)
            },
            notice = { n -> notices.add(n) },
            capacity = capacity,
        )
    }

    @Test
    fun fifo_order_preserved_and_flush_drains_all() {
        val rec = Recorder()
        // No drain thread started — flushAndStop must still drain the backlog
        // via its caller-side fallback, and order must be FIFO.
        val q = rec.queue(capacity = 2048)
        repeat(1000) { i -> q.enqueue("2026-09-15", "line-$i", keep = false) }
        q.flushAndStop(1_000)

        assertEquals(1000, rec.lines.size)
        assertEquals((0 until 1000).map { "line-$it" }, rec.lines)
        assertTrue(rec.notices.isEmpty())
    }

    @Test
    fun droppable_dropped_under_backlog_keep_survives() {
        val rec = Recorder()
        val q = rec.queue(capacity = 4)
        repeat(4) { i -> q.enqueue("d", "drop-$i", keep = false) }
        q.enqueue("d", "keep-1", keep = true) // full → evicts the oldest droppable
        q.flushAndStop(1_000)

        assertTrue("keep line must survive", rec.lines.contains("keep-1"))
        assertTrue(
            "droppable count must shrink",
            rec.lines.count { it.startsWith("drop-") } < 4,
        )
        assertEquals(4, rec.lines.size) // evict 1 + offer 1 → still full
        assertEquals(listOf(1L), rec.notices)
    }

    @Test
    fun keep_line_evicts_oldest_when_full() {
        val rec = Recorder()
        val q = rec.queue(capacity = 2)
        q.enqueue("d", "k1", keep = true)
        q.enqueue("d", "k2", keep = true)
        q.enqueue("d", "k3", keep = true) // full → evicts k1
        q.flushAndStop(1_000)

        assertEquals(listOf("k2", "k3"), rec.lines)
        assertEquals(listOf(1L), rec.notices)
    }

    @Test
    fun drop_notice_emitted_once_with_exact_count() {
        val rec = Recorder()
        val q = rec.queue(capacity = 4)
        repeat(10) { i -> q.enqueue("d", "x-$i", keep = false) } // drops 6
        q.flushAndStop(1_000)

        assertEquals(4, rec.lines.size)
        assertEquals(1, rec.notices.size)
        assertEquals(6L, rec.notices[0])
    }

    @Test
    fun enqueue_after_stop_is_noop() {
        val rec = Recorder()
        val q = rec.queue(capacity = 8)
        q.enqueue("d", "before", keep = true)
        q.flushAndStop(1_000)

        val linesAfterStop = rec.lines.size
        q.enqueue("d", "after", keep = true)
        q.enqueue("d", "after-droppable", keep = false)
        assertEquals(linesAfterStop, rec.lines.size)
        assertFalse(rec.lines.contains("after"))
        assertEquals(1, rec.lines.size)
    }

    @Test
    fun line_content_and_date_pass_through_verbatim() {
        val rec = Recorder()
        val q = rec.queue(capacity = 8)
        val tricky = "[12:00:00.000] [WARN] [X] 中文 ✓ \$dollar {brace} \\slash"
        q.enqueue("2026-09-15", tricky, keep = true)
        q.flushAndStop(1_000)

        assertEquals(listOf(tricky), rec.lines)
        assertEquals(listOf("2026-09-15"), rec.dates)
    }

    @Test
    fun flush_stop_is_idempotent() {
        val rec = Recorder()
        val q = rec.queue(capacity = 8)
        q.enqueue("d", "a", keep = true)
        q.flushAndStop(1_000)
        q.flushAndStop(1_000) // second call must not throw or duplicate
        assertEquals(listOf("a"), rec.lines)
    }

    @Test
    fun live_drain_conserves_lines_plus_dropped() {
        val rec = Recorder()
        val q = rec.queue(capacity = 64)
        q.start()
        repeat(5000) { i -> q.enqueue("d", "l-$i", keep = false) }
        q.flushAndStop(2_000)

        // Conservation invariant: every enqueued line was either delivered or
        // counted as dropped — under any drain/producer interleaving.
        val droppedTotal = rec.notices.sum()
        assertEquals(5000L, rec.lines.size + droppedTotal)
        // FIFO: delivered lines preserve enqueue order (strictly increasing
        // indexes, no duplicates). They are NOT a contiguous prefix — when the
        // full queue drops l-N, a later l-(N+1) still gets through.
        val seq = rec.lines.map { it.removePrefix("l-").toInt() }
        assertEquals("delivered order must be strictly increasing", seq.sorted(), seq)
        assertEquals("no duplicate deliveries", seq.distinct().size, seq.size)
    }

    @Test
    fun live_drain_delivers_most_lines_without_backlog() {
        val rec = Recorder()
        val q = rec.queue(capacity = 8192)
        q.start()
        repeat(500) { i -> q.enqueue("d", "m-$i", keep = true) }
        q.flushAndStop(2_000)

        // 500 lines into an 8192 queue: no loss expected even with a racing
        // drain thread (any loss would fail the exact-order assertion below).
        assertEquals(500, rec.lines.size)
        assertEquals((0 until 500).map { "m-$it" }, rec.lines)
        assertTrue(rec.notices.isEmpty())
    }
}
