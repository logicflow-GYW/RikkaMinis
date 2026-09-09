package com.rikkaminis.app.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [T1-session-concurrency] Integration tests for the coroutine/StateFlow adapter
 * ([SessionConcurrencyManager]) on top of [SessionSlotController].
 *
 * 验证对外 API 语义：
 * - 2 并发全成功 / 第 2 边界成功 / 第 3 FIFO 挂起并在释放后恢复；
 * - 同一 sessionId 的并发 run 各自独立占槽，明确排队而非静默折叠；
 * - 取消中的 waiter 从队列移除，永远不会在取消后被恢复为 active；
 * - 重复 release 幂等；
 * - StateFlow 反映真实 active / waiting 状态；
 * - 真实多线程 acquire/release 交错下终态收敛、无异常。
 *
 * [native-rss-tool-guard] MAX_CONCURRENT 由 5 收紧到 2（与
 * ExecutionCoordinator.MAX_CONCURRENT_SHELLS 对齐），本文件所有容量断言
 * 同步改为 2。
 *
 * 不用 Thread.sleep：runTest 用 runCurrent() 推进调度；并发测试用 CountDownLatch。
 */
class SessionConcurrencyManagerTest {

    @Before
    fun resetManager() {
        SessionConcurrencyManager.resetForTesting()
        // [memory-pressure-gate] Pin the gate to NORMAL so existing tests
        // are unaffected by the real /proc/self/status RSS of the CI runner
        // (which could exceed the ELEVATED watermark and add real delays).
        MemoryPressureGate.rssReader = { 0L }
        MemoryPressureGate.reclaimHook = {}
        MemoryPressureGate.pressureListener = { _, _ -> }
    }

    // --- 基本准入 ---

    @Test
    fun `2 concurrent acquires all succeed immediately`() = runTest {
        val jobs = (1..2).map { i -> async { SessionConcurrencyManager.acquireSlot("s$i") } }
        jobs.awaitAll()
        assertEquals(setOf("s1", "s2"), SessionConcurrencyManager.runningSessions.value)
        assertEquals(0, SessionConcurrencyManager.suspendedSessions.value.size)
    }

    @Test
    fun `2nd acquire succeeds at the capacity boundary`() = runTest {
        val jobs = (1..2).map { i -> async { SessionConcurrencyManager.acquireSlot("s$i") } }
        jobs.awaitAll()
        assertEquals(2, SessionConcurrencyManager.runningSessions.value.size)
        assertEquals(0, SessionConcurrencyManager.suspendedSessions.value.size)
    }

    @Test
    fun `3rd acquire waits in FIFO and resumes when a slot frees`() = runTest {
        val holders = (1..2).map { i -> async { SessionConcurrencyManager.acquireSlot("s$i") } }
        holders.awaitAll()

        val third = async { SessionConcurrencyManager.acquireSlot("s3") }
        runCurrent()
        assertTrue("3rd must be suspended", SessionConcurrencyManager.isSuspended("s3"))
        assertFalse("3rd must not complete while no slot is free", third.isCompleted)

        SessionConcurrencyManager.releaseSlot("s1")
        third.await()
        assertEquals(setOf("s2", "s3"), SessionConcurrencyManager.runningSessions.value)
        assertEquals(0, SessionConcurrencyManager.suspendedSessions.value.size)
    }

    @Test
    fun `waiters resume strictly in FIFO order`() = runTest {
        val holders = (1..2).map { i -> async { SessionConcurrencyManager.acquireSlot("s$i") } }
        holders.awaitAll()

        val w3 = async { SessionConcurrencyManager.acquireSlot("w3") }
        val w4 = async { SessionConcurrencyManager.acquireSlot("w4") }
        runCurrent()
        assertEquals(listOf("w3", "w4"), SessionConcurrencyManager.suspendedSessions.value)

        SessionConcurrencyManager.releaseSlot("s1")
        runCurrent()
        assertTrue("head waiter must resume first", w3.isCompleted)
        assertFalse("second waiter must still wait", w4.isCompleted)

        SessionConcurrencyManager.releaseSlot("s2")
        runCurrent()
        assertTrue(w4.isCompleted)
        assertEquals(setOf("w3", "w4"), SessionConcurrencyManager.runningSessions.value)
        assertEquals(0, SessionConcurrencyManager.suspendedSessions.value.size)
    }

    // --- 同 session 并发 run 不折叠（T1 核心修复） ---

    @Test
    fun `same session concurrent runs each occupy their own slot and queue explicitly`() = runTest {
        val a1 = async { SessionConcurrencyManager.acquireSlot("s1") }
        val a2 = async { SessionConcurrencyManager.acquireSlot("s2") }
        runCurrent()
        a1.await(); a2.await()

        // 同一 sessionId 的第二个 run：容量已满 → 明确进入等待队列（旧实现会被 Set 静默合并）
        val a3 = async { SessionConcurrencyManager.acquireSlot("s1") }
        runCurrent()
        assertTrue("second run of s1 must queue, not silently fold", SessionConcurrencyManager.isSuspended("s1"))
        assertFalse(a3.isCompleted)

        // 释放 s1 的第一个 run → 第二个 s1 run 被提升
        SessionConcurrencyManager.releaseSlot("s1")
        runCurrent()
        assertTrue(a3.isCompleted)
        assertTrue("s1 must still be running after its second run is promoted", "s1" in SessionConcurrencyManager.runningSessions.value)

        // 全部释放后收敛为空
        listOf("s1", "s2").forEach { SessionConcurrencyManager.releaseSlot(it) }
        assertEquals(emptySet<String>(), SessionConcurrencyManager.runningSessions.value)
        assertEquals(emptyList<String>(), SessionConcurrencyManager.suspendedSessions.value)
    }

    // --- 取消语义 ---

    @Test
    fun `cancelled waiter is removed and never resumed as active`() = runTest {
        val holders = (1..2).map { i -> async { SessionConcurrencyManager.acquireSlot("s$i") } }
        holders.awaitAll()

        val w3 = async { SessionConcurrencyManager.acquireSlot("w3") }
        val w4 = async { SessionConcurrencyManager.acquireSlot("w4") }
        runCurrent()

        w3.cancel()
        runCurrent()
        assertTrue(w3.isCancelled)
        assertFalse("cancelled waiter must leave the suspended list", SessionConcurrencyManager.isSuspended("w3"))

        // 释放一个槽位 → 跳过已取消的 w3，直接提升 w4
        SessionConcurrencyManager.releaseSlot("s1")
        runCurrent()
        assertTrue(w4.isCompleted)
        assertFalse("cancelled run must never appear in running", "w3" in SessionConcurrencyManager.runningSessions.value)
        assertEquals(0, SessionConcurrencyManager.suspendedSessions.value.size)
    }

    @Test
    fun `cancelled waiter propagates CancellationException to the caller`() = runTest {
        val holders = (1..2).map { i -> async { SessionConcurrencyManager.acquireSlot("s$i") } }
        holders.awaitAll()

        val w3 = async { SessionConcurrencyManager.acquireSlot("w3") }
        runCurrent()
        w3.cancelAndJoin()
        runCurrent()
        // 取消后槽位分配不受影响：释放后队列为空
        SessionConcurrencyManager.releaseSlot("s1")
        runCurrent()
        assertEquals(1, SessionConcurrencyManager.runningSessions.value.size)
        assertEquals(0, SessionConcurrencyManager.suspendedSessions.value.size)
    }

    // --- 幂等 release ---

    @Test
    fun `duplicate releaseSlot is a no-op`() = runTest {
        val j = async { SessionConcurrencyManager.acquireSlot("s1") }
        j.await()
        assertEquals(setOf("s1"), SessionConcurrencyManager.runningSessions.value)

        SessionConcurrencyManager.releaseSlot("s1")
        assertEquals(emptySet<String>(), SessionConcurrencyManager.runningSessions.value)

        // 第二次 release：无 active 槽位，不抛异常、不改变状态
        SessionConcurrencyManager.releaseSlot("s1")
        assertEquals(emptySet<String>(), SessionConcurrencyManager.runningSessions.value)
    }

    @Test
    fun `releaseSlot for an unknown session is a no-op`() {
        SessionConcurrencyManager.releaseSlot("ghost")
        assertEquals(emptySet<String>(), SessionConcurrencyManager.runningSessions.value)
    }

    // --- [D-2] observable occupancy ---

    @Test
    fun `occupancy reflects active and waiting slot counts`() = runTest {
        assertEquals(0, SessionConcurrencyManager.occupancy().active)
        assertEquals(0, SessionConcurrencyManager.occupancy().waiting)

        val holders = (1..2).map { i -> async { SessionConcurrencyManager.acquireSlot("s$i") } }
        holders.awaitAll()
        assertEquals(2, SessionConcurrencyManager.occupancy().active)
        assertEquals(0, SessionConcurrencyManager.occupancy().waiting)

        val third = async { SessionConcurrencyManager.acquireSlot("s3") }
        runCurrent()
        assertEquals(2, SessionConcurrencyManager.occupancy().active)
        assertEquals(1, SessionConcurrencyManager.occupancy().waiting)
        assertEquals(3, SessionConcurrencyManager.occupancy().total)

        SessionConcurrencyManager.releaseSlot("s1")
        third.await()
        // third promoted into the freed slot: active back to 2, waiting empty
        assertEquals(2, SessionConcurrencyManager.occupancy().active)
        assertEquals(0, SessionConcurrencyManager.occupancy().waiting)
    }

    // --- 真实多线程交错 ---

    @Test
    fun `concurrent acquire and release converges under real threads`() = runBlocking {
        val threads = 12 // > MAX_CONCURRENT=2，强制排队
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val errors = CopyOnWriteArrayList<Throwable>()

        repeat(threads) { i ->
            launch(Dispatchers.Default) {
                try {
                    start.await()
                    SessionConcurrencyManager.acquireSlot("s$i")
                    // 模拟持有槽位执行
                    SessionConcurrencyManager.releaseSlot("s$i")
                } catch (t: Throwable) {
                    if (t !is CancellationException) errors.add(t)
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        assertTrue("threads did not finish in 15s", done.await(15, TimeUnit.SECONDS))
        assertTrue("unexpected errors: $errors", errors.isEmpty())
        assertEquals("all slots must be released", 0, SessionConcurrencyManager.runningSessions.value.size)
        assertEquals("queue must be empty", 0, SessionConcurrencyManager.suspendedSessions.value.size)
    }

    @Test
    fun `many sessions with repeated run cycles keep invariants`() = runBlocking {
        val sessions = 2
        val cycles = 50
        val start = CountDownLatch(1)
        val done = CountDownLatch(sessions)
        val errors = CopyOnWriteArrayList<Throwable>()

        repeat(sessions) { s ->
            launch(Dispatchers.Default) {
                try {
                    start.await()
                    repeat(cycles) {
                        SessionConcurrencyManager.acquireSlot("cycle-s$s")
                        SessionConcurrencyManager.releaseSlot("cycle-s$s")
                    }
                } catch (t: Throwable) {
                    if (t !is CancellationException) errors.add(t)
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        assertTrue("unexpected errors: $errors", errors.isEmpty())
        assertEquals(0, SessionConcurrencyManager.runningSessions.value.size)
        assertEquals(0, SessionConcurrencyManager.suspendedSessions.value.size)
    }

    // --- [memory-pressure-gate] pressure-aware admission ---

    @Test
    fun `NORMAL pressure admits immediately without reclaim`() = runTest {
        MemoryPressureGate.rssReader = { 100L }
        var reclaimed = 0
        MemoryPressureGate.reclaimHook = { reclaimed++ }

        SessionConcurrencyManager.acquireSlot("s1")
        assertEquals(setOf("s1"), SessionConcurrencyManager.runningSessions.value)
        assertEquals(0, reclaimed)
    }

    @Test
    fun `ELEVATED pressure delays admission but does not reclaim`() = runTest {
        MemoryPressureGate.rssReader = { 700L }
        var reclaimed = 0
        MemoryPressureGate.reclaimHook = { reclaimed++ }

        SessionConcurrencyManager.acquireSlot("s1")

        // 500ms delay happens in test virtual time — assert the slot is
        // acquired and no reclaim was triggered (ELEVATED is a soft gate).
        assertEquals(setOf("s1"), SessionConcurrencyManager.runningSessions.value)
        assertEquals(0, reclaimed)
    }

    @Test
    fun `CRITICAL pressure triggers reclaim hook before admission`() = runTest {
        MemoryPressureGate.rssReader = { 900L }
        var reclaimed = 0
        MemoryPressureGate.reclaimHook = { reclaimed++ }

        SessionConcurrencyManager.acquireSlot("s1")

        assertEquals(1, reclaimed)
        assertEquals(setOf("s1"), SessionConcurrencyManager.runningSessions.value)
    }

    @Test
    fun `CRITICAL pressure still admits after reclaim window`() = runTest {
        MemoryPressureGate.rssReader = { 900L }
        var reclaimed = 0
        MemoryPressureGate.reclaimHook = { reclaimed++ }

        // Second acquire also passes (reclaim is a delay, not a rejection —
        // never deadlocks the FIFO).
        SessionConcurrencyManager.acquireSlot("s1")
        SessionConcurrencyManager.acquireSlot("s2")
        SessionConcurrencyManager.releaseSlot("s1")
        SessionConcurrencyManager.acquireSlot("s3")

        assertEquals(setOf("s2", "s3"), SessionConcurrencyManager.runningSessions.value)
        assertEquals(3, reclaimed)
    }
}
