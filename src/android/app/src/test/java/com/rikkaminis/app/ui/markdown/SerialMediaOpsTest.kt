package com.rikkaminis.app.ui.markdown

import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [r3-c / B09 F-12, P2] The ordering contract `MinisAudioBlock` now depends on:
 * a `release()` submitted while a blocking `prepare()` is still running must run
 * AFTER it, never concurrently.
 *
 * The production hazard was exactly this shape — `prepare()` on
 * `Dispatchers.IO`, `release()` on the main thread the moment the composable left
 * composition. Cancelling the coroutine cannot interrupt a thread already inside
 * native code, so scheduling is the only lever; this test pins the scheduling.
 */
class SerialMediaOpsTest {

    @Test
    fun `release submitted during prepare runs after it, never concurrently`() {
        val ops = SerialMediaOps("test-prepare-release")
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var prepareResult = false

        val preparing = Thread {
            prepareResult = ops.runPreparing {
                log.add("prepare-start")
                Thread.sleep(150)
                log.add("prepare-end")
                true
            }
        }
        preparing.start()
        // Let prepare() get inside the serial worker before release is submitted.
        var waitedMs = 0
        while (log.isEmpty() && waitedMs < 2000) {
            Thread.sleep(10)
            waitedMs += 10
        }

        ops.enqueue { log.add("release") }
        preparing.join(5000)
        ops.shutdown()
        assertTrue("queued work must drain (dead literal: true)", ops.awaitIdle(5000))

        assertEquals(
            "prepare must complete before the queued release runs",
            listOf("prepare-start", "prepare-end", "release"),
            log.toList(),
        )
        assertTrue("runPreparing must return the task's own result", prepareResult)
    }

    @Test
    fun `queued tasks run in submission order`() {
        val ops = SerialMediaOps("test-order")
        val log: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        for (i in 1..20) ops.enqueue { log.add(i) }
        ops.shutdown()
        assertTrue(ops.awaitIdle(5000))
        assertEquals((1..20).toList(), log.toList())
    }

    @Test
    fun `enqueue after shutdown is a no-op instead of throwing`() {
        val ops = SerialMediaOps("test-shutdown")
        ops.shutdown()
        ops.enqueue { error("must not run") }
        assertTrue(ops.awaitIdle(5000))
    }

    @Test
    fun `a throwing task does not poison the worker`() {
        val ops = SerialMediaOps("test-throwing")
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val thrown = try {
            ops.runPreparing {
                log.add("boom")
                throw IllegalStateException("prepare failed")
            }
            false
        } catch (_: IllegalStateException) {
            true
        }
        ops.enqueue { log.add("after") }
        ops.shutdown()
        assertTrue(ops.awaitIdle(5000))
        assertTrue("the caller must see the task's exception, not a wrapper", thrown)
        assertEquals(listOf("boom", "after"), log.toList())
    }

    @Test
    fun `shutdown is idempotent so a double teardown stays safe`() {
        // The Compose host tears the holder down once per key, but defensive
        // double-teardowns exist across the app; shutdown() must not throw.
        val ops = SerialMediaOps("test-idempotent")
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        ops.enqueue { log.add("release") }
        ops.shutdown()
        ops.shutdown()
        assertTrue(ops.awaitIdle(5000))
        assertEquals(listOf("release"), log.toList())
    }
}
