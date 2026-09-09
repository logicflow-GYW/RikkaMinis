package com.rikkaminis.app.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * T2 — AgentExecutionBudget 纯 JVM 测试。
 *
 * 覆盖蓝图测试矩阵：每个计数 0→上限、超限不产生负数、多维独立、
 * deadline 前后、Long 溢出时间、child 配额与释放、重试/fallback/compact 共用计数、
 * 并发不双重成功、token null 不伪造、并发工具槽、构造校验。
 */
class AgentExecutionBudgetTest {

    // ─── 测试夹具 ───────────────────────────────────────────────────────────

    private fun budget(
        maxTurns: Int = 100,
        maxProviderAttempts: Int = 100,
        maxToolCalls: Int = 100,
        maxShellCommands: Int = 100,
        maxCompactionCalls: Int = 100,
        maxConcurrentTools: Int = 5,
        maxEstimatedTokens: Long? = null,
        startedAt: Long = 0L,
        deadline: Long = Long.MAX_VALUE / 2,
        clock: () -> Long = { 0L },
    ) = AgentExecutionBudget(
        startedAtMonotonicMs = startedAt,
        deadlineMonotonicMs = deadline,
        maxTurns = maxTurns,
        maxProviderAttempts = maxProviderAttempts,
        maxToolCalls = maxToolCalls,
        maxShellCommands = maxShellCommands,
        maxCompactionCalls = maxCompactionCalls,
        maxConcurrentTools = maxConcurrentTools,
        maxEstimatedTokens = maxEstimatedTokens,
        monotonicClock = clock,
    )

    // ─── 1. 每个计数从 0 消耗到上限 ─────────────────────────────────────────

    @Test
    fun `each counter consumes from zero to its cap`() {
        val b = budget(maxTurns = 3, maxProviderAttempts = 2, maxToolCalls = 4, maxShellCommands = 2, maxCompactionCalls = 2)

        assertEquals(BudgetDecision.Allowed, b.consumeTurn())
        assertEquals(BudgetDecision.Allowed, b.consumeTurn())
        assertEquals(BudgetDecision.Allowed, b.consumeTurn())
        assertEquals(BudgetDecision.Denied(BudgetExhaustedReason.TURN_LIMIT), b.consumeTurn())

        assertEquals(BudgetDecision.Allowed, b.consumeProviderAttempt())
        assertEquals(BudgetDecision.Allowed, b.consumeProviderAttempt())
        assertEquals(BudgetDecision.Denied(BudgetExhaustedReason.PROVIDER_ATTEMPT_LIMIT), b.consumeProviderAttempt())

        assertEquals(BudgetDecision.Allowed, b.consumeToolCall())
        assertEquals(BudgetDecision.Allowed, b.consumeToolCall())
        assertEquals(BudgetDecision.Allowed, b.consumeToolCall())
        assertEquals(BudgetDecision.Allowed, b.consumeToolCall())
        assertEquals(BudgetDecision.Denied(BudgetExhaustedReason.TOOL_CALL_LIMIT), b.consumeToolCall())

        assertEquals(BudgetDecision.Allowed, b.consumeShellCommand())
        assertEquals(BudgetDecision.Allowed, b.consumeShellCommand())
        assertEquals(BudgetDecision.Denied(BudgetExhaustedReason.SHELL_COMMAND_LIMIT), b.consumeShellCommand())

        assertEquals(BudgetDecision.Allowed, b.consumeCompaction())
        assertEquals(BudgetDecision.Allowed, b.consumeCompaction())
        assertEquals(BudgetDecision.Denied(BudgetExhaustedReason.COMPACTION_CALL_LIMIT), b.consumeCompaction())

        val snap = b.snapshot()
        assertEquals(3, snap.turnsUsed)
        assertEquals(2, snap.providerAttemptsUsed)
        assertEquals(4, snap.toolCallsUsed)
        assertEquals(2, snap.shellCommandsUsed)
        assertEquals(2, snap.compactionCallsUsed)
    }

    // ─── 2. 超限不产生负数 ─────────────────────────────────────────────────

    @Test
    fun `over-limit consumption never produces negative remaining`() {
        val b = budget(maxTurns = 1, maxProviderAttempts = 1, maxToolCalls = 1, maxShellCommands = 1, maxCompactionCalls = 1)
        repeat(50) {
            b.consumeTurn()
            b.consumeProviderAttempt()
            b.consumeToolCall()
            b.consumeShellCommand()
            b.consumeCompaction()
        }
        val remaining = b.remaining()
        assertEquals(0, remaining.turnsRemaining)
        assertEquals(0, remaining.providerAttemptsRemaining)
        assertEquals(0, remaining.toolCallsRemaining)
        assertEquals(0, remaining.shellCommandsRemaining)
        assertEquals(0, remaining.compactionCallsRemaining)
        // snapshot 计数不会超过上限
        assertEquals(1, b.snapshot().turnsUsed)
        assertEquals(1, b.snapshot().toolCallsUsed)
    }

    // ─── 3. 多维预算只耗对应维度 ───────────────────────────────────────────

    @Test
    fun `consuming one dimension does not affect others`() {
        val b = budget(maxTurns = 5, maxToolCalls = 5)
        b.consumeTurn()
        b.consumeTurn()
        assertEquals(2, b.snapshot().turnsUsed)
        assertEquals(0, b.snapshot().toolCallsUsed)
        assertEquals(3, b.remaining().turnsRemaining)
        assertEquals(5, b.remaining().toolCallsRemaining)
    }

    // ─── 4. deadline 前后 ───────────────────────────────────────────────────

    @Test
    fun `expired budget rejects all consumption with DEADLINE_EXPIRED`() {
        var now = 100L
        val b = budget(maxTurns = 5, deadline = 500L, clock = { now })

        assertFalse(b.isExpired())
        assertEquals(BudgetDecision.Allowed, b.consumeTurn())

        now = 499L
        assertFalse(b.isExpired(now))
        assertEquals(BudgetDecision.Allowed, b.consumeTurn())

        now = 500L // 到达 deadline 即过期
        assertTrue(b.isExpired())
        val denied = b.consumeTurn()
        assertTrue(denied is BudgetDecision.Denied)
        assertEquals(BudgetExhaustedReason.DEADLINE_EXPIRED, (denied as BudgetDecision.Denied).reason)
        // 过期后计数不被消耗
        assertEquals(2, b.snapshot().turnsUsed)
    }

    @Test
    fun `expired budget also rejects token and child operations`() {
        var now = 0L
        val b = budget(maxEstimatedTokens = 1000, deadline = 100L, clock = { now })
        now = 101L
        assertEquals(
            BudgetDecision.Denied(BudgetExhaustedReason.DEADLINE_EXPIRED),
            b.consumeEstimatedTokens(10),
        )
        assertEquals(
            BudgetDecision.Denied(BudgetExhaustedReason.DEADLINE_EXPIRED),
            b.tryReserveChildBudget(10),
        )
        assertEquals(
            BudgetDecision.Denied(BudgetExhaustedReason.DEADLINE_EXPIRED),
            b.tryAcquireToolSlot(),
        )
        assertEquals(BudgetDecision.Denied(BudgetExhaustedReason.DEADLINE_EXPIRED), b.consumeChildTokens(5))
    }

    // ─── 5. Long 溢出时间 ──────────────────────────────────────────────────

    @Test
    fun `deadline comparison is overflow-safe at Long extremes`() {
        // now 与 deadline 都在 Long 极值附近：直接比较不依赖差值，不会溢出
        val b = budget(
            startedAt = Long.MIN_VALUE / 2,
            deadline = Long.MAX_VALUE / 2,
        )
        // now = MAX_VALUE 比 deadline = MAX_VALUE/2 大，确实过期了
        assertTrue("now=MAX_VALUE 应过期（大于 deadline=MAX_VALUE/2）", b.isExpired(Long.MAX_VALUE))
        // 时钟回拨到极值：未过期
        assertFalse("now=MIN_VALUE 应未过期（小于 deadline）", b.isExpired(Long.MIN_VALUE))
        // 差值溢出测试：now - deadline 若用减法会溢出，直接比较 correct
        // deadline = MAX_VALUE/2, now = MAX_VALUE/2 + 1 → 精确边界
        assertTrue(b.isExpired(Long.MAX_VALUE / 2))
        assertTrue(b.isExpired(Long.MAX_VALUE / 2 + 1))
    }

    @Test
    fun `remaining millis never negative even far past deadline`() {
        var now = 0L
        val b = budget(startedAt = 0L, deadline = 1000L, clock = { now })
        assertEquals(1000L, b.remaining().millisRemaining)
        now = 10_000L
        assertTrue(b.isExpired())
        assertEquals(0L, b.remaining().millisRemaining)
    }

    // ─── 6. child 预算不能超过 parent ─────────────────────────────────────

    @Test
    fun `child reservation cannot exceed parent remaining`() {
        val b = budget(maxEstimatedTokens = 100L)
        assertEquals(BudgetDecision.Allowed, b.consumeEstimatedTokens(30L))
        // 剩余 70；预留 60 成功
        assertEquals(BudgetDecision.Allowed, b.tryReserveChildBudget(60L))
        // 剩余 10；再预留 20 必须拒绝
        val denied = b.tryReserveChildBudget(20L)
        assertTrue(denied is BudgetDecision.Denied)
        assertEquals(BudgetExhaustedReason.TOKEN_BUDGET_EXCEEDED, (denied as BudgetDecision.Denied).reason)
        // 拒绝的预留不改变状态
        assertEquals(60L, b.snapshot().reservedChildTokens)
        assertEquals(30L, b.snapshot().estimatedTokensUsed)
        assertEquals(10L, b.remaining().estimatedTokensRemaining)
    }

    @Test
    fun `child consumed tokens enter parent ledger and cannot exceed reservation`() {
        val b = budget(maxEstimatedTokens = 100L)
        assertEquals(BudgetDecision.Allowed, b.tryReserveChildBudget(40L))
        assertEquals(BudgetDecision.Allowed, b.consumeChildTokens(40L))
        assertEquals(40L, b.snapshot().estimatedTokensUsed)
        assertEquals(0L, b.snapshot().reservedChildTokens)
        assertEquals(60L, b.remaining().estimatedTokensRemaining)
        // 无预留时 child 消耗被拒
        val denied = b.consumeChildTokens(1L)
        assertTrue(denied is BudgetDecision.Denied)
        assertEquals(BudgetExhaustedReason.TOKEN_BUDGET_EXCEEDED, (denied as BudgetDecision.Denied).reason)
        assertEquals(40L, b.snapshot().estimatedTokensUsed)
    }

    // ─── 7. child 取消释放未消费预留；已消耗不可回退 ───────────────────────

    @Test
    fun `releasing unused child reservation refunds only the unused part`() {
        val b = budget(maxEstimatedTokens = 100L)
        assertEquals(BudgetDecision.Allowed, b.tryReserveChildBudget(50L))
        assertEquals(BudgetDecision.Allowed, b.consumeChildTokens(20L))
        // 已消耗 20，预留剩 30；取消释放 30 → 全部归还
        b.releaseChildBudget(30L)
        assertEquals(20L, b.snapshot().estimatedTokensUsed)
        assertEquals(0L, b.snapshot().reservedChildTokens)
        assertEquals(80L, b.remaining().estimatedTokensRemaining)
        // 已消耗的 20 不可回退
        assertEquals(20L, b.snapshot().estimatedTokensUsed)
    }

    @Test
    fun `over-release is clamped and idempotent`() {
        val b = budget(maxEstimatedTokens = 100L)
        assertEquals(BudgetDecision.Allowed, b.tryReserveChildBudget(10L))
        b.releaseChildBudget(100L) // 超过预留：clamp 到 0，不产生负数
        assertEquals(0L, b.snapshot().reservedChildTokens)
        b.releaseChildBudget(5L) // 重复释放：无副作用
        assertEquals(0L, b.snapshot().reservedChildTokens)
        assertEquals(100L, b.remaining().estimatedTokensRemaining)
    }

    // ─── 8. 重试、fallback、compact 消耗同一计数 ──────────────────────────

    @Test
    fun `retries and fallbacks consume the same provider-attempt counter`() {
        val b = budget(maxProviderAttempts = 3)
        // 首次尝试 + 1 次 retry + 2 次 fallback 尝试 = 4 次，第 4 次被拒
        assertEquals(BudgetDecision.Allowed, b.consumeProviderAttempt()) // 首次
        assertEquals(BudgetDecision.Allowed, b.consumeProviderAttempt()) // retry
        assertEquals(BudgetDecision.Allowed, b.consumeProviderAttempt()) // fallback 1
        assertEquals(BudgetDecision.Denied(BudgetExhaustedReason.PROVIDER_ATTEMPT_LIMIT), b.consumeProviderAttempt()) // fallback 2
        assertEquals(3, b.snapshot().providerAttemptsUsed)
    }

    @Test
    fun `compaction consumes its own counter not tool counter`() {
        val b = budget(maxToolCalls = 5, maxCompactionCalls = 2)
        b.consumeCompaction()
        b.consumeCompaction()
        assertEquals(2, b.snapshot().compactionCallsUsed)
        assertEquals(0, b.snapshot().toolCallsUsed)
        assertEquals(BudgetDecision.Denied(BudgetExhaustedReason.COMPACTION_CALL_LIMIT), b.consumeCompaction())
    }

    // ─── 9. 并发调用时决策不会双重成功 ─────────────────────────────────────

    @Test
    fun `concurrent consumeTurn never double-succeeds beyond cap`() {
        val cap = 5
        val b = budget(maxTurns = cap)
        val threads = 16
        val executor = Executors.newFixedThreadPool(threads)
        val startGate = CountDownLatch(1)
        val allowed = AtomicInteger(0)
        val denied = AtomicInteger(0)
        try {
            repeat(threads * 10) {
                executor.submit {
                    startGate.await()
                    when (b.consumeTurn()) {
                        is BudgetDecision.Allowed -> allowed.incrementAndGet()
                        is BudgetDecision.Denied -> denied.incrementAndGet()
                    }
                }
            }
            startGate.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
        assertEquals("只有 cap 次成功", cap, allowed.get())
        assertEquals(threads * 10 - cap, denied.get())
        assertEquals(cap, b.snapshot().turnsUsed)
        assertEquals(0, b.remaining().turnsRemaining)
    }

    @Test
    fun `concurrent tool-slot acquisition never exceeds maxConcurrentTools`() {
        val maxConcurrent = 4
        val b = budget(maxConcurrentTools = maxConcurrent)
        val threads = 20
        val executor = Executors.newFixedThreadPool(threads)
        val startGate = CountDownLatch(1)
        val acquired = AtomicInteger(0)
        try {
            repeat(threads) {
                executor.submit {
                    startGate.await()
                    if (b.tryAcquireToolSlot() is BudgetDecision.Allowed) {
                        val now = b.snapshot().concurrentToolsActive
                        assertTrue("active slots must never exceed cap, saw $now", now <= maxConcurrent)
                        acquired.incrementAndGet()
                    }
                }
            }
            startGate.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
        assertEquals(maxConcurrent, acquired.get())
        assertEquals(maxConcurrent, b.snapshot().concurrentToolsActive)
        // 全部释放后回到 0
        repeat(maxConcurrent) { b.releaseToolSlot() }
        assertEquals(0, b.snapshot().concurrentToolsActive)
        assertEquals(maxConcurrent, b.remaining().concurrentToolSlotsRemaining)
    }

    @Test
    fun `concurrent token consumption never exceeds token cap`() {
        val cap = 100L
        val b = budget(maxEstimatedTokens = cap)
        val threads = 8
        val executor = Executors.newFixedThreadPool(threads)
        val startGate = CountDownLatch(1)
        val allowed = AtomicInteger(0)
        try {
            repeat(threads * 50) {
                executor.submit {
                    startGate.await()
                    if (b.consumeEstimatedTokens(1L) is BudgetDecision.Allowed) allowed.incrementAndGet()
                }
            }
            startGate.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
        assertEquals(cap, allowed.get().toLong())
        assertEquals(cap, b.snapshot().estimatedTokensUsed)
        assertEquals(0L, b.remaining().estimatedTokensRemaining)
    }

    // ─── 10. token 预算 null：不伪造精确值 ─────────────────────────────────

    @Test
    fun `null token budget allows consumption but never fabricates a count`() {
        val b = budget(maxEstimatedTokens = null)
        assertEquals(BudgetDecision.Allowed, b.consumeEstimatedTokens(12345L))
        assertEquals(BudgetDecision.Allowed, b.consumeEstimatedTokens(99999L))
        // snapshot 保持 null：调用方知道 token 计数不可靠
        assertNull("token 预算未启用时不得伪造精确值", b.snapshot().estimatedTokensUsed)
        assertNull(b.remaining().estimatedTokensRemaining)
        // child reserve 不做配额审计但同样不记账
        assertEquals(BudgetDecision.Allowed, b.tryReserveChildBudget(50L))
        assertNull(b.snapshot().estimatedTokensUsed)
        assertEquals(BudgetDecision.Allowed, b.consumeChildTokens(50L))
        assertNull(b.snapshot().estimatedTokensUsed)
    }

    // ─── 11. 工具槽 acquire/release 语义 ───────────────────────────────────

    @Test
    fun `tool slot release is idempotent and never frees someone elses slot`() {
        val b = budget(maxConcurrentTools = 2)
        assertEquals(BudgetDecision.Allowed, b.tryAcquireToolSlot())
        assertEquals(BudgetDecision.Allowed, b.tryAcquireToolSlot())
        assertEquals(BudgetDecision.Denied(BudgetExhaustedReason.CONCURRENT_TOOLS_LIMIT), b.tryAcquireToolSlot())

        b.releaseToolSlot()
        assertEquals(1, b.snapshot().concurrentToolsActive)
        b.releaseToolSlot()
        assertEquals(0, b.snapshot().concurrentToolsActive)
        b.releaseToolSlot() // 重复释放：clamp 到 0
        assertEquals(0, b.snapshot().concurrentToolsActive)
    }

    @Test
    fun `failed slot acquisition does not change state`() {
        val b = budget(maxConcurrentTools = 1)
        assertEquals(BudgetDecision.Allowed, b.tryAcquireToolSlot())
        val denied = b.tryAcquireToolSlot()
        assertTrue(denied is BudgetDecision.Denied)
        assertEquals(1, b.snapshot().concurrentToolsActive)
        assertEquals(0, b.remaining().concurrentToolSlotsRemaining)
    }

    // ─── 12. 构造校验与非法输入 ────────────────────────────────────────────

    @Test
    fun `constructor rejects invalid caps`() {
        listOf(
            { budget(maxTurns = -1) },
            { budget(maxProviderAttempts = -1) },
            { budget(maxToolCalls = -1) },
            { budget(maxShellCommands = -1) },
            { budget(maxCompactionCalls = -1) },
            { budget(maxConcurrentTools = -1) },
            { budget(maxEstimatedTokens = -1L) },
            { budget(startedAt = 10L, deadline = 5L) },
        ).forEach {
            try {
                it()
                fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `negative token operations are rejected`() {
        val b = budget(maxEstimatedTokens = 100L)
        try {
            b.consumeEstimatedTokens(-1L)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            b.tryReserveChildBudget(-1L)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            b.consumeChildTokens(-1L)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        // 非法输入不影响状态
        assertEquals(0L, b.snapshot().estimatedTokensUsed)
        assertEquals(100L, b.remaining().estimatedTokensRemaining)
    }

    // ─── 13. snapshot / remaining / isExpired 一致性 ───────────────────────

    @Test
    fun `snapshot and remaining are consistent views`() {
        var now = 0L
        val b = budget(maxTurns = 10, maxEstimatedTokens = 50L, deadline = 1000L, clock = { now })
        b.consumeTurn()
        b.consumeTurn()
        b.consumeTurn()
        b.consumeEstimatedTokens(20L)

        val snap = b.snapshot()
        val rem = b.remaining()
        assertEquals(3, snap.turnsUsed)
        assertEquals(7, rem.turnsRemaining)
        assertEquals(20L, snap.estimatedTokensUsed)
        assertEquals(30L, rem.estimatedTokensRemaining)
        assertFalse(snap.isExpired)
        assertEquals(1000L, rem.millisRemaining)

        now = 1001L
        assertTrue(b.snapshot().isExpired)
        assertEquals(0L, b.remaining().millisRemaining)
    }

    @Test
    fun `expired flag follows injected clock`() {
        var now = 0L
        val b = budget(deadline = 100L, clock = { now })
        assertFalse(b.isExpired())
        now = 99L
        assertFalse(b.isExpired())
        now = 100L
        assertTrue(b.isExpired())
        now = 1000L
        assertTrue(b.isExpired())
    }

    // ─── 14. 默认时钟可用（nanoTime 单调源，非 wall clock） ────────────────

    @Test
    fun `default monotonic clock advances`() {
        val c1 = AgentExecutionBudget.DEFAULT_MONOTONIC_CLOCK()
        val c2 = AgentExecutionBudget.DEFAULT_MONOTONIC_CLOCK()
        // nanoTime 单调不减；两次调用之间允许相等（分辨率内）
        assertTrue("nanoTime 必须单调", c2 >= c1)
    }
}
