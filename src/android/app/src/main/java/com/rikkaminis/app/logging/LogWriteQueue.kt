package com.rikkaminis.app.logging

import java.util.ArrayList
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * [T-android-log-async-writer] Single-consumer async write queue for AppLogger.
 *
 * Callers (UI thread, LogcatTailer reader, Dispatchers.Default workers) enqueue
 * pre-formatted, pre-timestamped lines and never touch the file or take a lock
 * on the hot path — the global `@Synchronized` contention that used to sit on
 * AppLogger.getWriter() is gone entirely. One daemon drain thread batches lines
 * and hands them to [sink], which owns the (already buffered) PrintWriter.
 *
 * Bound & drop policy: the queue is bounded at [capacity] items (~2.4MB worst
 * case at 8192 × ~300B). When full, incoming non-keep lines (DEBUG / logcat
 * echo) are dropped and counted; keep lines (INFO/WARN/ERROR/STDOUT) evict the
 * oldest queued item instead — even a stalled disk cannot make the logger grow
 * without bound or block a caller. A single "N log lines dropped" resume notice
 * is emitted via [notice] when the backlog clears.
 *
 * Timestamps are taken at ENQUEUE time (caller thread), so queued lines keep
 * accurate times even if the drain thread lags; cross-thread ordering is FIFO
 * by enqueue (the same guarantee the old lock gave, minus the blocking).
 *
 * Pure JVM — no Android imports; unit-tested in LogWriteQueueTest.
 */
internal class LogWriteQueue(
    private val sink: (date: String, line: String) -> Unit,
    private val notice: (count: Long) -> Unit,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    companion object {
        const val DEFAULT_CAPACITY = 8192
        private const val POLL_IDLE_MS = 200L
        private const val DRAIN_BATCH = 64
    }

    private class Item(val date: String, val line: String)

    private val queue = ArrayBlockingQueue<Item>(capacity)

    /**
     * Single loss counter, drained via getAndSet(0) when a notice is emitted.
     * One counter (not a pending-flag pair) so the conservation invariant
     * `lines delivered + notices sum == lines enqueued` holds under any
     * producer/emitter interleaving — see LogWriteQueueTest.
     */
    private val dropped = AtomicLong(0)
    private val noticeClaim = AtomicBoolean(false)

    @Volatile private var stopping = false

    /** Once true, [enqueue] is a no-op — set before the drain thread is joined. */
    @Volatile private var stopped = false

    private var started = false

    private val thread = Thread({ drainLoop() }, "AppLogger-Writer").apply {
        isDaemon = true
    }

    fun start() {
        // [audit-0917] Guard against a second call: thread.start() on an
        // already-started Thread throws IllegalThreadStateException, and
        // `started` was assigned but never read. Callers (AppLogger.init on a
        // re-init, tests) could trip this.
        synchronized(this) {
            if (started) return
            started = true
        }
        thread.start()
    }

    /**
     * Enqueue one pre-formatted line. Never blocks and never throws; under a
     * full queue, [keep]=true lines evict the oldest queued item while
     * [keep]=false lines are dropped outright. Both paths count the loss so a
     * later notice can surface it.
     */
    fun enqueue(date: String, line: String, keep: Boolean) {
        if (stopped) return
        val item = Item(date, line)
        if (queue.offer(item)) return
        if (!keep) {
            dropped.incrementAndGet()
            return
        }
        // Keep line: make room by evicting the oldest queued item (whatever it
        // is). [T-log-queue-evict-policy] Level-pooled eviction (drop DEBUG
        // first, then non-keep, then anything) was considered and REJECTED for
        // now: a correct implementation needs a second pool or an atomic
        // scan-and-requeue, both racy or O(capacity) per eviction under
        // concurrent producers. The backlog entry stays as an accepted
        // tradeoff; revisit only if "backlog ate my ERROR lines" ever bites.
        while (!stopped) {
            val evicted = queue.poll()
            if (evicted == null) {
                // Another producer drained a slot before we could evict; try a
                // plain offer and fall back to evicting again.
                if (queue.offer(item)) return
                continue
            }
            dropped.incrementAndGet()
            if (queue.offer(item)) return
        }
        // [T-log-queue-stop-race] stopped flipped mid-eviction: count the loss
        // so the conservation invariant `delivered + notices == enqueued`
        // holds even in the stop-race window (the item is neither delivered
        // nor queued, and the caller never sees it).
        dropped.incrementAndGet()
    }

    /**
     * Stop the drain thread and flush everything still queued. [stopped] is set
     * first so no producer can slip a line in behind the join; the caller-side
     * [drainRemaining] then guarantees any pre-stop line still reaches [sink]
     * even if the thread itself already exited or timed out. After this returns,
     * the loss counter is final and the last notice (if any) has been emitted.
     */
    fun flushAndStop(timeoutMs: Long) {
        stopped = true
        stopping = true
        if (started) {
            try {
                thread.join(timeoutMs)
            } catch (_: InterruptedException) {
            }
        }
        drainRemaining()
        maybeEmitDropNotice(force = true)
    }

    private fun drainLoop() {
        val batch = ArrayList<Item>(DRAIN_BATCH)
        while (true) {
            batch.clear()
            val first = try {
                queue.poll(POLL_IDLE_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }
            if (first != null) {
                batch.add(first)
                queue.drainTo(batch, DRAIN_BATCH - 1)
                for (item in batch) {
                    deliver(item)
                }
            }
            if (stopping && queue.isEmpty()) break
            if (first == null) maybeEmitDropNotice(force = false)
        }
        maybeEmitDropNotice(force = true)
    }

    private fun drainRemaining() {
        while (true) {
            val item = queue.poll() ?: return
            deliver(item)
        }
    }

    private fun deliver(item: Item) {
        try {
            sink(item.date, item.line)
        } catch (_: Throwable) {
            // The sink must never take the logger down with it.
        }
    }

    private fun maybeEmitDropNotice(force: Boolean) {
        if (dropped.get() == 0L) return
        // While backlogged, wait — the notice is a resume signal, not a panic.
        if (!force && queue.size > capacity / 2) return
        if (!noticeClaim.compareAndSet(false, true)) return // another emitter is on it
        try {
            val n = dropped.getAndSet(0)
            if (n > 0) {
                try {
                    notice(n)
                } catch (_: Throwable) {
                }
            }
        } finally {
            noticeClaim.set(false)
        }
    }
}
