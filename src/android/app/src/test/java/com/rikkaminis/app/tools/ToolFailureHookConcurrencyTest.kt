package com.rikkaminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Concurrency regression test for the ToolFailureHook dedupe P0.
 *
 * Guards: concurrent recordFailure() calls for the same (toolName + summary)
 * must produce exactly ONE block within the dedupe window, even when many
 * threads race check-then-set simultaneously. This is the case that used to
 * be broken (HashMap + non-atomic check-then-set → up to 32 duplicate blocks
 * under a 32-thread race).
 */
class ToolFailureHookConcurrencyTest {

    private val TOOL = "shell_execute"
    private val OUTPUT = "command not found: this-is-a-shared-failure-AAAAAAAAAA"

    /**
     * 32 threads fire the exact same failure concurrently, released from a
     * common barrier so they line up at the dedupe window check.
     */
    @Test fun concurrentSameKey_writesExactlyOneBlock() {
        val threads = 32
        val writes = CopyOnWriteArrayList<String>()
        // Fixed clock → every thread sees the same "present" time, maximising
        // the chance the old non-atomic path would double-write.
        val hook = ToolFailureHook(
            writeErrorBlock = { block -> writes.add(block) },
            clock = { 0L },
            dedupeWindowMs = ToolFailureHook.DEDUPE_WINDOW_MS,
        )

        val pool = Executors.newFixedThreadPool(threads)
        try {
            val go = CountDownLatch(1)
            val done = CountDownLatch(threads)
            repeat(threads) {
                pool.execute {
                    go.await() // line everyone up before firing
                    hook.recordFailure(TOOL, OUTPUT, sessionId = "race-session")
                    done.countDown()
                }
            }
            go.countDown()
            assertTrue(
                "all 32 threads must finish",
                done.await(30, TimeUnit.SECONDS),
            )
        } finally {
            pool.shutdownNow()
        }

        assertEquals(
            "concurrent identical failures must be deduplicated to exactly 1 block",
            1,
            writes.size,
        )
    }

    /**
     * Sanity: after the dedupe window lapses, a concurrent burst of the same
     * key re-writes — but only ONE new block for that burst (the previous
     * window's block is gone).
     */
    @Test fun concurrentSameKey_afterWindowExpiry_writesOneNewBlock() {
        val threads = 16
        val writes = CopyOnWriteArrayList<String>()
        val windowMs = 10_000L
        var now = 0L
        val hook = ToolFailureHook(
            writeErrorBlock = { block -> writes.add(block) },
            clock = { now },
            dedupeWindowMs = windowMs,
        )

        // First burst at t=0 → 1 block.
        fireAll(hook, threads)
        assertEquals("first burst writes exactly 1", 1, writes.size)

        // Advance past the window, then second burst → exactly 1 more block.
        now = windowMs + 1
        fireAll(hook, threads)
        assertEquals("second burst writes exactly 1 more", 2, writes.size)
    }

    /** Release [threads] identical concurrent failures and wait for them. */
    private fun fireAll(hook: ToolFailureHook, threads: Int) {
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val go = CountDownLatch(1)
            val done = CountDownLatch(threads)
            repeat(threads) {
                pool.execute {
                    go.await()
                    hook.recordFailure(TOOL, OUTPUT, sessionId = "race-session")
                    done.countDown()
                }
            }
            go.countDown()
            assertTrue("all threads finish", done.await(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
    }
}