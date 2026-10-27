package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Semaphore

/**
 * [ISSUE-12b] deep-audit-1018 (P2) — bounded thread creation for the native
 * offload server.
 *
 * Baseline behavior (pre-fix): the accept loop spawned one `thread {}` per
 * accepted connection and let that thread block on the concurrency semaphore,
 * so a burst of N offloads created N OS threads (each parking on a ~1 MB
 * native stack) while only `permits` made progress. [BoundedWorkerPool] now
 * spawns a worker only while a permit is free and queues excess clients as
 * cheap FIFO entries.
 *
 * Device shape: the thread spawner is injected, so "how many OS threads were
 * created" is counted directly and workers are driven deterministically by the
 * test instead of racing real threads. Expected values are literals.
 */
class NativeOffloadWorkerCapTest {

    private class Rig(cap: Int) {
        val permits = Semaphore(cap, true)
        val spawns = mutableListOf<() -> Unit>()
        val handled = mutableListOf<String>()
        var handler: (String) -> Unit = { handled.add(it) }
        val queueMsSeen = mutableListOf<Long>()

        val pool = BoundedWorkerPool<String>(
            permits = permits,
            threadName = "test-worker",
            spawnThread = { _, body -> spawns.add(body) },
            handler = { item, queueMs ->
                queueMsSeen.add(queueMs)
                handler(item)
            },
        )

        fun runWorker(index: Int) = spawns[index]()
    }

    @Test
    fun oneSubmit_spawnsOneWorkerThatHandlesTheClientAndReturnsThePermit() {
        val rig = Rig(2)

        rig.pool.submit("a")
        assertEquals("exactly one worker thread for one client", 1, rig.spawns.size)
        assertEquals("the live worker holds one permit", 1, rig.permits.availablePermits())
        assertEquals("the entry waits in FIFO until the worker polls it", 1, rig.pool.pendingCount)

        rig.runWorker(0)
        assertEquals(listOf("a"), rig.handled)
        assertEquals("worker exit returns the permit", 2, rig.permits.availablePermits())
        assertEquals(1, rig.spawns.size)
    }

    @Test
    fun burstBeyondCap_createsAtMostCapThreadsAndQueuesTheRestInArrivalOrder() {
        val rig = Rig(2)

        listOf("a", "b", "c", "d", "e", "f").forEach { rig.pool.submit(it) }

        assertEquals("thread creation is capped, not one-per-connection", 2, rig.spawns.size)
        assertEquals("the rest wait as cheap queue entries", 6, rig.pool.pendingCount)
        assertEquals(0, rig.permits.availablePermits())

        // one worker drains the whole backlog, in arrival order
        rig.runWorker(0)
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), rig.handled)
        assertEquals("draining worker returned its permit", 1, rig.permits.availablePermits())

        // the second worker finds nothing and exits, returning its permit too
        rig.runWorker(1)
        assertEquals(2, rig.permits.availablePermits())
        assertEquals(0, rig.pool.pendingCount)
        assertEquals(2, rig.spawns.size)
    }

    @Test
    fun drainedPool_spawnsAFreshWorkerForTheNextSubmit() {
        val rig = Rig(2)

        rig.pool.submit("a")
        rig.runWorker(0)
        assertEquals(2, rig.permits.availablePermits())

        rig.pool.submit("b")
        assertEquals("a drained pool spawns again", 2, rig.spawns.size)
        rig.runWorker(1)

        assertEquals(listOf("a", "b"), rig.handled)
        assertEquals(2, rig.permits.availablePermits())
    }

    /**
     * The lost-item invariant: a client enqueued while every permit is held
     * must be picked up by a live worker (here: submitted from inside a
     * handler, with cap 1 and no free permit). If the exit path were not
     * atomic with the submit path, this entry would be stranded forever.
     */
    @Test
    fun submitWhileAllPermitsAreHeld_isPickedUpByTheLiveWorker_notLost() {
        val rig = Rig(1)
        rig.handler = { item ->
            rig.handled.add(item)
            if (item == "a") rig.pool.submit("b")
        }

        rig.pool.submit("a")
        assertEquals(1, rig.spawns.size)
        rig.runWorker(0)

        assertEquals(listOf("a", "b"), rig.handled)
        assertEquals("no extra thread was created", 1, rig.spawns.size)
        assertEquals(1, rig.permits.availablePermits())
        assertEquals(0, rig.pool.pendingCount)
    }

    /**
     * The old worker released its permit in a `finally`, so a throwing handler
     * could not leak it. The pool must keep that property, must not strand
     * queued clients behind one bad client, and must not swallow the failure.
     */
    @Test
    fun throwingHandler_neitherStrandsTheQueueNorLeaksThePermitNorSwallowsTheError() {
        val rig = Rig(1)
        rig.handler = { item ->
            if (item == "boom") throw IllegalStateException("handler failed")
            rig.handled.add(item)
        }

        rig.pool.submit("boom")
        rig.pool.submit("ok")
        assertEquals(1, rig.spawns.size)
        assertEquals(2, rig.pool.pendingCount)

        var thrown: Throwable? = null
        try {
            rig.runWorker(0)
        } catch (t: Throwable) {
            thrown = t
        }

        assertEquals("the failure surfaces", "handler failed", thrown?.message)
        assertEquals("the queued client is still served", listOf("ok"), rig.handled)
        assertEquals("the permit comes back", 1, rig.permits.availablePermits())
        assertEquals("the queue is drained", 0, rig.pool.pendingCount)
    }

    @Test
    fun handlerReceivesQueueWaitMillis_neverNegative() {
        val rig = Rig(1)
        rig.pool.submit("a")
        rig.pool.submit("b")
        rig.runWorker(0)

        assertEquals(listOf("a", "b"), rig.handled)
        assertEquals(2, rig.queueMsSeen.size)
        assertTrue("queueMs is a non-negative wait measurement", rig.queueMsSeen.all { it >= 0L })
    }

    // ---- wiring: the accept loop must no longer spawn per connection ----

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    @Test
    fun acceptLoop_noLongerSpawnsAPerConnectionThread() {
        val src = readRepoFile(NATIVE_OFFLOAD_REL)

        val loop = src.substringAfter("private fun runAcceptLoop(")
            .substringBefore("private fun handleClient(")
        assertEquals("the accept loop must not spawn threads", 0, Regex("\\bthread\\(").findAll(loop).count())
        assertTrue("it hands sockets to the bounded pool", loop.contains("pool.submit(client)"))

        // the bound is the semaphore itself: tryAcquire at submit, release at exit
        assertTrue(src.contains("permits.tryAcquire()"))
        assertTrue(src.contains("permits.release()"))
        assertTrue("MAX_CONCURRENT_WORKERS stays the default cap", src.contains("const val MAX_CONCURRENT_WORKERS = 2"))
    }

    private companion object {
        const val NATIVE_OFFLOAD_REL = "app/src/main/java/com/rikkaminis/app/sandbox/NativeOffload.kt"
    }
}
