package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * [audit2 DESIGN-03] The parallel tool batch must not hand
 * `loopState.allToolBlocks` to concurrent coroutines.
 *
 * Why the shared instance was a problem: it is a plain `ArrayList` read on the
 * main thread by every `updateAssistantMessage` publish, while the `async {}`
 * bodies in `AgentLoopEngine` inherit the outer `launch(Dispatchers.IO)`. The
 * one writer that exists today (`executeShellCommand`'s `onBlockUpdate`) does a
 * read-modify-write from whatever thread the coroutine runs on. The
 * `ToolConcurrencyPolicy` whitelist is what keeps writers out of the window
 * today — these tests pin the isolation so widening the whitelist cannot turn
 * that into a live race.
 */
class ParallelToolBatchBlocksTest {

    private fun block(id: String, content: String = "") = AssistantBlock(
        id = id,
        kind = "tool_use",
        content = content,
        toolStatus = ToolBlockStatus.PENDING,
        toolName = id,
    )

    private fun fresh(ids: List<String>): MutableList<AssistantBlock> =
        ids.map { block(it) }.toMutableList()

    /** Verbatim writer shape from ChatPromptAndTools.executeShellCommand.onBlockUpdate. */
    private fun writerTick(blocks: MutableList<AssistantBlock>, toolId: String, content: String) {
        val idx = blocks.indexOfFirst { it.id == toolId }
        if (idx >= 0) blocks[idx] = blocks[idx].copy(content = content)
    }

    @Test
    fun `each call forks its own instance, never the shared one`() {
        val shared = fresh(listOf("t1", "t2"))
        val baseline = ParallelToolBatchBlocks.snapshot(shared)
        val a = ParallelToolBatchBlocks.forkForCall(baseline)
        val b = ParallelToolBatchBlocks.forkForCall(baseline)
        assertNotSame(shared, a)
        assertNotSame(shared, b)
        assertNotSame(a, b)
        assertEquals(shared, a)
    }

    @Test
    fun `mergeBack reproduces the sequential oracle`() {
        val ids = listOf("t1", "t2", "t3", "t4")
        val oracle = fresh(ids)
        ids.forEach { writerTick(oracle, it, "done-$it") }

        val shared = fresh(ids)
        val baseline = ParallelToolBatchBlocks.snapshot(shared)
        val copies = ids.map { ParallelToolBatchBlocks.forkForCall(baseline) }
        val written = CountDownLatch(ids.size)
        val release = CountDownLatch(1)
        ids.mapIndexed { i, id ->
            Thread {
                writerTick(copies[i], id, "done-$id")
                written.countDown()
                release.await()
            }
        }.forEach { it.start() }
        written.await()
        // while the workers are parked the shared list must still be pristine —
        // this is the invariant the pre-fix contract violated
        assertEquals(baseline, shared.toList())
        release.countDown()
        ParallelToolBatchBlocks.mergeBack(shared, baseline, copies)
        assertEquals(oracle, shared)
    }

    @Test
    fun `a call that edited nothing must not write a stale block back`() {
        val ids = listOf("t1", "t2")
        val shared = fresh(ids)
        val baseline = ParallelToolBatchBlocks.snapshot(shared)
        val callOne = ParallelToolBatchBlocks.forkForCall(baseline)
        val callTwo = ParallelToolBatchBlocks.forkForCall(baseline)
        writerTick(callOne, "t1", "from-1")
        writerTick(callTwo, "t2", "from-2")
        ParallelToolBatchBlocks.mergeBack(shared, baseline, listOf(callOne, callTwo))
        // callTwo carries t1's pre-edit value; it must not undo callOne's edit.
        assertEquals("from-1", shared[0].content)
        assertEquals("from-2", shared[1].content)
    }

    // ── [r8-C-F03] a structural edit must not shift a sibling's write ───────

    /**
     * The baseline was read by POSITION (`baseline.getOrNull(i)`) while the
     * write target was read by ID. As soon as one call inserted a block, every
     * later block in that copy compared unequal to the wrong baseline entry —
     * so an untouched block looked "edited" and its stale value was written
     * back over a sibling's real edit.
     */
    @Test
    fun `a call that inserts a block cannot roll a sibling's edit back`() {
        val ids = listOf("t1", "t2", "t3")
        val shared = fresh(ids)
        val baseline = ParallelToolBatchBlocks.snapshot(shared)
        val callOne = ParallelToolBatchBlocks.forkForCall(baseline)
        val callTwo = ParallelToolBatchBlocks.forkForCall(baseline)
        writerTick(callOne, "t2", "from-1")
        callTwo.add(0, block("tX", "invented"))
        writerTick(callTwo, "t3", "from-2")
        ParallelToolBatchBlocks.mergeBack(shared, baseline, listOf(callOne, callTwo))
        // callTwo never touched t2; its copy carries t2's pre-edit value, and
        // that value must not undo callOne's edit.
        assertEquals("from-1", shared[1].content)
        assertEquals("from-2", shared[2].content)
    }

    @Test
    fun `a call that removes a block cannot roll a sibling's edit back`() {
        val ids = listOf("t1", "t2", "t3")
        val shared = fresh(ids)
        val baseline = ParallelToolBatchBlocks.snapshot(shared)
        val callOne = ParallelToolBatchBlocks.forkForCall(baseline)
        val callTwo = ParallelToolBatchBlocks.forkForCall(baseline)
        writerTick(callOne, "t2", "from-1")
        callTwo.removeAll { it.id == "t1" }
        writerTick(callTwo, "t3", "from-2")
        ParallelToolBatchBlocks.mergeBack(shared, baseline, listOf(callOne, callTwo))
        assertEquals("from-1", shared[1].content)
        assertEquals("from-2", shared[2].content)
    }

    @Test
    fun `mergeBack is order preserving on a contended block`() {
        val shared = fresh(listOf("t1"))
        val baseline = ParallelToolBatchBlocks.snapshot(shared)
        val first = ParallelToolBatchBlocks.forkForCall(baseline)
        val second = ParallelToolBatchBlocks.forkForCall(baseline)
        writerTick(first, "t1", "from-call-1")
        writerTick(second, "t1", "from-call-2")
        ParallelToolBatchBlocks.mergeBack(shared, baseline, listOf(first, second))
        assertEquals("from-call-2", shared[0].content)
    }

    @Test
    fun `untouched blocks keep their identity`() {
        val shared = fresh(listOf("t1", "t2"))
        val untouched = shared[1]
        val baseline = ParallelToolBatchBlocks.snapshot(shared)
        val copy = ParallelToolBatchBlocks.forkForCall(baseline)
        writerTick(copy, "t1", "x")
        ParallelToolBatchBlocks.mergeBack(shared, baseline, listOf(copy))
        assertEquals("x", shared[0].content)
        assertSame(untouched, shared[1])
    }

    @Test
    fun `hazard - the pre-fix contract loses an update`() {
        // The shape the fix removes: two writers sharing one instance, both
        // reading the pre-state before either writes.
        val shared = fresh(listOf("t1"))
        val barrier = CyclicBarrier(2)
        val bump = { delta: Long ->
            val idx = shared.indexOfFirst { it.id == "t1" }
            val before = shared[idx].durationMs
            barrier.await()
            shared[idx] = shared[idx].copy(durationMs = before + delta)
        }
        val t1 = Thread { bump(10L) }
        val t2 = Thread { bump(20L) }
        t1.start(); t2.start(); t1.join(); t2.join()
        assertTrue(shared[0].durationMs == 10L || shared[0].durationMs == 20L)
        assertTrue("10 + 20 must never survive as 30 — one increment is lost", shared[0].durationMs != 30L)
    }

    @Test
    fun `stress - concurrent calls plus a reader, no loss and no torn state`() {
        val ids = (1..8).map { "t$it" }
        val shared = fresh(ids)
        val baseline = ParallelToolBatchBlocks.snapshot(shared)
        val copies = ids.map { ParallelToolBatchBlocks.forkForCall(baseline) }
        val start = CountDownLatch(1)
        val stop = AtomicBoolean(false)
        val torn = AtomicInteger(0)
        val errors = AtomicInteger(0)

        val reader = Thread {
            start.await()
            while (!stop.get()) {
                try {
                    if (shared.toList().size != ids.size) torn.incrementAndGet()
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                }
            }
        }
        val workers = ids.mapIndexed { i, id ->
            Thread {
                start.await()
                repeat(200) { n -> writerTick(copies[i], id, "$id-$n") }
            }
        }
        reader.start()
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join() }
        stop.set(true)
        reader.join()

        assertEquals(0, torn.get())
        assertEquals(0, errors.get())
        ParallelToolBatchBlocks.mergeBack(shared, baseline, copies)
        ids.forEachIndexed { i, id -> assertEquals("$id-199", shared[i].content) }
    }
}
