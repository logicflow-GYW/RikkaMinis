package com.rikkaminis.app.service

import com.rikkaminis.app.service.SessionSlotController.AcquireOutcome.Acquired
import com.rikkaminis.app.service.SessionSlotController.AcquireOutcome.Duplicate
import com.rikkaminis.app.service.SessionSlotController.AcquireOutcome.Queued
import com.rikkaminis.app.service.SessionSlotController.CancelOutcome.ActiveRemoved
import com.rikkaminis.app.service.SessionSlotController.CancelOutcome.Noop
import com.rikkaminis.app.service.SessionSlotController.CancelOutcome.WaitingRemoved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * [T1-session-concurrency] JVM tests for the pure slot controller.
 *
 * 覆盖蓝图 T1 测试矩阵的不变量：
 * - active 数永远不超过 maxConcurrent（容量检查与写入同一同步区间）；
 * - 第 5 个边界成功、第 6 个进入 FIFO；
 * - 队首/队中/active 取消；
 * - 重复 release 幂等、不释放他人槽位；
 * - duplicate runId 语义明确（拒绝，不静默折叠）；
 * - acquire 与 release 并发交错下不变量恒成立；
 * - snapshot 与实际 lease 数一致。
 *
 * 不用 Thread.sleep 测并发：多线程测试全部用 CountDownLatch 同步。
 */
class SessionSlotControllerTest {

    // SessionSlotController is a generic, capacity-parameterized pure controller.
    // Its unit tests must NOT couple to the production SessionConcurrencyManager
    // MAX_CONCURRENT (which is a runtime tuning knob, currently 2). Use a fixed
    // local capacity so the FIFO/cancel/snapshot logic assertions stay deterministic.
    private val capacity = 5

    // --- 4 并发全成功 / 第 5 边界 / 第 6 FIFO ---

    @Test
    fun `4 concurrent acquires all succeed immediately`() {
        val c = SessionSlotController(capacity)
        repeat(4) { i -> assertEquals(Acquired("r$i"), c.acquire("r$i")) }
        val s = c.snapshot()
        assertEquals(4, s.activeCount)
        assertEquals(0, s.waitingCount)
    }

    @Test
    fun `5th acquire hits the capacity boundary and succeeds`() {
        val c = SessionSlotController(capacity)
        repeat(capacity) { i -> assertEquals(Acquired("r$i"), c.acquire("r$i")) }
        val s = c.snapshot()
        assertEquals(capacity, s.activeCount)
        assertEquals(0, s.waitingCount)
    }

    @Test
    fun `6th acquire enters the FIFO queue`() {
        val c = SessionSlotController(capacity)
        repeat(capacity) { i -> c.acquire("r$i") }
        assertEquals(Queued("q1"), c.acquire("q1"))
        val s = c.snapshot()
        assertEquals(capacity, s.activeCount)
        assertEquals(1, s.waitingCount)
        assertEquals(listOf("q1"), s.waitingRunIds)
    }

    @Test
    fun `release promotes the FIFO head`() {
        val c = SessionSlotController(capacity)
        repeat(capacity) { i -> c.acquire("r$i") }
        c.acquire("q1")
        c.acquire("q2")
        assertEquals("q1", c.release("r0"))
        val s = c.snapshot()
        assertEquals(listOf("r1", "r2", "r3", "r4", "q1"), s.activeRunIds)
        assertEquals(listOf("q2"), s.waitingRunIds)
    }

    // --- 100 并发 active 永不超过上限 ---

    @Test
    fun `100 concurrent acquires never exceed capacity`() {
        val c = SessionSlotController(capacity)
        val threads = 100
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val maxObserved = AtomicInteger(0)
        val activeNow = AtomicInteger(0)
        val duplicates = AtomicInteger(0)
        val runSeq = AtomicInteger(0)

        repeat(threads) {
            thread(name = "slot-t$it") {
                val runId = "r${runSeq.getAndIncrement()}"
                start.await()
                when (val outcome = c.acquire(runId)) {
                    is Acquired -> {
                        val now = activeNow.incrementAndGet()
                        updateMax(maxObserved, now)
                        c.release(runId)
                        activeNow.decrementAndGet()
                    }
                    is Queued -> {
                        // 等待被提升（轮询，不用 sleep）；提升后计数持有再释放
                        var becameActive = false
                        while (!c.isActive(runId)) {
                            if (c.isWaiting(runId)) {
                                Thread.yield()
                            } else {
                                // 已不在队列且非 active（异常路径），放弃
                                break
                            }
                        }
                        if (c.isActive(runId)) {
                            becameActive = true
                            val now = activeNow.incrementAndGet()
                            updateMax(maxObserved, now)
                            c.release(runId)
                            activeNow.decrementAndGet()
                        }
                        assertTrue("queued run $runId never promoted", becameActive)
                    }
                    is Duplicate -> duplicates.incrementAndGet()
                }
                done.countDown()
            }
        }

        start.countDown()
        assertTrue("100 threads did not finish in 15s", done.await(15, TimeUnit.SECONDS))
        assertEquals("no duplicate runId should be admitted", 0, duplicates.get())
        assertTrue("active count exceeded capacity: max=$maxObserved", maxObserved.get() <= capacity)
        assertEquals("controller must converge to zero active", 0, c.snapshot().activeCount)
        assertEquals("controller must converge to zero waiting", 0, c.snapshot().waitingCount)
    }

    @Test
    fun `active never exceeds capacity under interleaved acquire and release`() {
        val c = SessionSlotController(3)
        val rounds = 200
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val maxObserved = AtomicInteger(0)
        val activeNow = AtomicInteger(0)
        val runSeq = AtomicInteger(0)

        // 两个线程：一个持续 acquire→release，一个也持续 acquire→release，互相交错
        repeat(2) {
            thread(name = "interleave-$it") {
                start.await()
                repeat(rounds) {
                    val runId = "i${runSeq.getAndIncrement()}"
                    when (val outcome = c.acquire(runId)) {
                        is Acquired -> {
                            val now = activeNow.incrementAndGet()
                            updateMax(maxObserved, now)
                            c.release(runId)
                            activeNow.decrementAndGet()
                        }
                        is Queued -> {
                            while (!c.isActive(runId)) {
                                if (c.isWaiting(runId)) Thread.yield() else break
                            }
                            if (c.isActive(runId)) {
                                val now = activeNow.incrementAndGet()
                                updateMax(maxObserved, now)
                                c.release(runId)
                                activeNow.decrementAndGet()
                            }
                        }
                        is Duplicate -> throw AssertionError("duplicate runId $runId")
                    }
                }
                done.countDown()
            }
        }

        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        assertTrue("active exceeded 3: max=$maxObserved", maxObserved.get() <= 3)
        assertEquals(0, c.snapshot().activeCount)
        assertEquals(0, c.snapshot().waitingCount)
    }

    // --- 取消：队首 / 队中 / active ---

    @Test
    fun `cancelling the queue head removes it without promoting anyone`() {
        val c = SessionSlotController(capacity)
        repeat(capacity) { i -> c.acquire("r$i") }
        c.acquire("q1")
        c.acquire("q2")
        assertEquals(WaitingRemoved("q1"), c.cancel("q1"))
        // 释放 r0 → 跳过已取消的 q1，提升 q2
        assertEquals("q2", c.release("r0"))
        val s = c.snapshot()
        assertEquals(listOf("r1", "r2", "r3", "r4", "q2"), s.activeRunIds)
        assertEquals(0, s.waitingCount)
    }

    @Test
    fun `cancelling a middle waiter preserves FIFO order of the rest`() {
        val c = SessionSlotController(capacity)
        repeat(capacity) { i -> c.acquire("r$i") }
        c.acquire("q1")
        c.acquire("q2")
        c.acquire("q3")
        assertEquals(WaitingRemoved("q2"), c.cancel("q2"))
        assertEquals("q1", c.release("r0"))
        assertEquals("q3", c.release("r1"))
        val s = c.snapshot()
        assertEquals(listOf("r2", "r3", "r4", "q1", "q3"), s.activeRunIds)
        assertEquals(0, s.waitingCount)
    }

    @Test
    fun `cancelling an active run frees its slot and promotes the head`() {
        val c = SessionSlotController(capacity)
        repeat(capacity) { i -> c.acquire("r$i") }
        c.acquire("q1")
        assertEquals(ActiveRemoved("r1", "q1"), c.cancel("r1"))
        val s = c.snapshot()
        assertEquals(listOf("r0", "r2", "r3", "r4", "q1"), s.activeRunIds)
        assertEquals(0, s.waitingCount)
    }

    @Test
    fun `cancelling an unknown run is a no-op`() {
        val c = SessionSlotController(capacity)
        repeat(3) { i -> c.acquire("r$i") }
        assertEquals(Noop, c.cancel("ghost"))
        assertEquals(3, c.snapshot().activeCount)
    }

    @Test
    fun `cancelled waiter is never promoted later`() {
        val c = SessionSlotController(capacity)
        repeat(capacity) { i -> c.acquire("r$i") }
        c.acquire("q1")
        c.acquire("q2")
        c.cancel("q1")
        assertFalse(c.isWaiting("q1"))
        assertFalse(c.isActive("q1"))
        // 连续释放直到 q2 提升，q1 始终不在任何位置
        c.release("r0")
        assertFalse(c.isActive("q1"))
        c.release("r1")
        assertFalse(c.isActive("q1"))
        assertEquals(0, c.snapshot().waitingCount)
    }

    // --- 重复 release / duplicate run ---

    @Test
    fun `duplicate release is a no-op and does not release someone else's slot`() {
        val c = SessionSlotController(capacity)
        repeat(capacity) { i -> c.acquire("r$i") }
        c.acquire("q1")
        assertEquals("q1", c.release("r0"))
        assertNull("second release of the same runId must be a no-op", c.release("r0"))
        assertNull("release of an unknown runId must be a no-op", c.release("ghost"))
        // r1..r4 + q1 仍在 active
        assertEquals(listOf("r1", "r2", "r3", "r4", "q1"), c.snapshot().activeRunIds)
    }

    @Test
    fun `duplicate runId acquire is rejected explicitly, not silently folded`() {
        val c = SessionSlotController(capacity)
        assertEquals(Acquired("r1"), c.acquire("r1"))
        assertEquals(Duplicate("r1"), c.acquire("r1"))
        c.release("r1")
        assertEquals("runId is reusable after release", Acquired("r1"), c.acquire("r1"))
    }

    @Test
    fun `duplicate runId in queue is rejected`() {
        val c = SessionSlotController(capacity)
        repeat(capacity) { i -> c.acquire("r$i") }
        c.acquire("q1")
        assertEquals(Duplicate("q1"), c.acquire("q1"))
        assertEquals(1, c.snapshot().waitingCount)
    }

    @Test
    fun `distinct runIds for the same logical session are independent`() {
        // session 语义由 adapter 负责；controller 层面不同的 runId 完全独立，
        // 不存在按 session 折叠——5 个同 session run 各占一槽。
        val c = SessionSlotController(capacity)
        repeat(capacity) { i -> assertEquals(Acquired("s1-run$i"), c.acquire("s1-run$i")) }
        assertEquals(capacity, c.snapshot().activeCount)
        assertEquals(Queued("s1-run$capacity"), c.acquire("s1-run$capacity"))
        assertEquals(1, c.snapshot().waitingCount)
    }

    // --- newRunId 唯一性 ---

    @Test
    fun `newRunId never collides with active or waiting runs`() {
        val c = SessionSlotController(2)
        c.acquire(c.newRunId())
        c.acquire(c.newRunId())
        c.acquire(c.newRunId()) // queued
        val s = c.snapshot()
        val all = s.activeRunIds + s.waitingRunIds
        assertEquals("all runIds must be unique", all.size, all.toSet().size)
        assertEquals(3, s.total)
    }

    @Test
    fun `deterministic generator is used when injected`() {
        var n = 0
        val c = SessionSlotController(2)
        c.runIdGenerator = { "gen-${n++}" }
        assertEquals("gen-0", c.newRunId())
        assertEquals("gen-1", c.newRunId())
    }

    // --- snapshot 一致性 ---

    @Test
    fun `snapshot matches actual lease count`() {
        val c = SessionSlotController(capacity)
        c.acquire("a")
        c.acquire("b")
        assertEquals(2, c.snapshot().activeCount)
        c.acquire("c")
        c.acquire("d")
        c.acquire("e")
        assertEquals(capacity, c.snapshot().activeCount)
        c.acquire("f")
        assertEquals(1, c.snapshot().waitingCount)
        assertEquals(capacity + 1, c.snapshot().total)
        c.release("a")
        assertEquals("f must be promoted into the freed slot", capacity, c.snapshot().activeCount)
        assertEquals(0, c.snapshot().waitingCount)
    }

    @Test
    fun `snapshot lists are immutable copies`() {
        val c = SessionSlotController(2)
        c.acquire("a")
        val s1 = c.snapshot()
        c.acquire("b")
        // 旧快照不被后续变更影响
        assertEquals(listOf("a"), s1.activeRunIds)
        assertEquals(listOf("a", "b"), c.snapshot().activeRunIds)
    }

    // --- 构造约束 ---

    @Test
    fun `non-positive capacity is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SessionSlotController(0) }
        assertThrows(IllegalArgumentException::class.java) { SessionSlotController(-1) }
    }

    @Test
    fun `single-slot controller queues everything beyond one`() {
        val c = SessionSlotController(1)
        assertEquals(Acquired("a"), c.acquire("a"))
        assertEquals(Queued("b"), c.acquire("b"))
        assertEquals(Queued("c"), c.acquire("c"))
        assertEquals("b", c.release("a"))
        assertEquals("c", c.release("b"))
        c.release("c") // c 被提升为 active，需释放
        assertEquals(0, c.snapshot().total)
    }

    private fun updateMax(maxObserved: AtomicInteger, now: Int) {
        while (true) {
            val cur = maxObserved.get()
            if (now <= cur || maxObserved.compareAndSet(cur, now)) return
        }
    }
}
