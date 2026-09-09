package com.rikkaminis.app.harness

import com.rikkaminis.app.harness.contract.*
import com.rikkaminis.app.harness.fakes.*
import org.junit.Test

class HarnessFakesTest {

    // ── FakeClock ──────────────────────────────────────────────────────────

    @Test
    fun `FakeClock starts at 0`() {
        val clock = FakeClock()
        assertTrue(clock.now() == 0L, "clock starts at 0")
    }

    @Test
    fun `FakeClock advance`() {
        val clock = FakeClock()
        clock.advance(100)
        assertTrue(clock.now() == 100L, "advance to 100")
        clock.advance(50)
        assertTrue(clock.now() == 150L, "advance to 150")
    }

    @Test
    fun `FakeClock set`() {
        val clock = FakeClock()
        clock.set(999)
        assertTrue(clock.now() == 999L, "set to 999")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `FakeClock negative advance rejected`() {
        val clock = FakeClock()
        clock.advance(-1)
    }

    // ── FakeProvider ───────────────────────────────────────────────────────

    @Test
    fun `FakeProvider records attempts`() {
        val p = FakeProvider("p1")
        assertTrue(p.attemptCount == 0, "attempt 0")
        p.recordAttempt()
        assertTrue(p.attemptCount == 1, "attempt 1")
        p.recordAttempt()
        assertTrue(p.attemptCount == 2, "attempt 2")
    }

    @Test
    fun `FakeProvider cooldown`() {
        val p = FakeProvider("p1")
        assertTrue(!p.isCooling(0), "not cooling at 0")
        p.markCooldown(0, 60_000)
        assertTrue(p.isCooling(30_000), "cooling at 30000")
        assertTrue(!p.isCooling(60_000), "not cooling at 60000")
        assertTrue(!p.isCooling(61_000), "not cooling at 61000")
    }

    @Test
    fun `FakeProvider reset`() {
        val p = FakeProvider("p1")
        p.recordAttempt()
        p.markCooldown(0, 60_000)
        p.reset()
        assertTrue(p.attemptCount == 0, "attempt count reset")
        assertTrue(p.cooldownUntilMs == null, "cooldown reset")
    }

    // ── FakeToolExecutor ───────────────────────────────────────────────────

    @Test
    fun `FakeToolExecutor success`() {
        val scripts = mapOf("read" to ToolCallScript("read", ToolBehavior.SUCCESS))
        val exe = FakeToolExecutor(scripts)
        val result = exe.execute("read", "op1")
        assertTrue(result.success, "success")
        assertTrue(!result.sideEffectPerformed, "no side effect")
        assertTrue(exe.totalExecutions() == 1, "1 execution")
    }

    @Test
    fun `FakeToolExecutor failure`() {
        val scripts = mapOf("fail" to ToolCallScript("fail", ToolBehavior.FAILURE))
        val exe = FakeToolExecutor(scripts)
        val result = exe.execute("fail", "op1")
        assertTrue(!result.success, "failure")
        assertTrue(exe.totalExecutions() == 1, "1 execution")
    }

    @Test
    fun `FakeToolExecutor side effect counts`() {
        val scripts = mapOf("write" to ToolCallScript(
            "write", ToolBehavior.SIDE_EFFECT_THEN_NO_RESULT,
            sideEffectLevel = SideEffectLevel.NON_IDEMPOTENT_WRITE,
        ))
        val exe = FakeToolExecutor(scripts)
        val result = exe.execute("write", "op1")
        assertTrue(result.outcomeUnknown, "outcome unknown")
        assertTrue(result.sideEffectPerformed, "side effect performed")
        assertTrue(exe.sideEffectCount == 1, "1 side effect")
        assertTrue(exe.duplicateSideEffectCount == 0, "0 duplicates")
    }

    @Test
    fun `FakeToolExecutor duplicate side effect`() {
        val scripts = mapOf("write" to ToolCallScript(
            "write", ToolBehavior.SIDE_EFFECT_THEN_NO_RESULT,
            sideEffectLevel = SideEffectLevel.NON_IDEMPOTENT_WRITE,
        ))
        val exe = FakeToolExecutor(scripts)
        exe.execute("write", "op1")
        assertTrue(exe.sideEffectCount == 1, "1 side effect")
        assertTrue(exe.duplicateSideEffectCount == 0, "0 duplicates")
        exe.execute("write", "op1")
        assertTrue(exe.sideEffectCount == 2, "2 side effects")
        assertTrue(exe.duplicateSideEffectCount == 1, "1 duplicate")
    }

    @Test
    fun `FakeToolExecutor block until cancelled`() {
        val scripts = mapOf("block" to ToolCallScript("block", ToolBehavior.BLOCK_UNTIL_CANCELLED))
        val exe = FakeToolExecutor(scripts)
        val result = exe.execute("block", "op1")
        assertTrue(result.cancelled, "blocked until cancelled")
        assertTrue(exe.totalExecutions() == 1, "1 execution")
    }

    @Test
    fun `FakeToolExecutor spawn rejected`() {
        val scripts = mapOf("spawn" to ToolCallScript("spawn", ToolBehavior.SPAWN, spawnDepth = 1))
        val exe = FakeToolExecutor(scripts)
        val result = exe.execute("spawn", "op1")
        assertTrue(result.spawnRejected, "spawn rejected")
        assertTrue(exe.spawnRejectedCount == 1, "1 rejection")
    }

    @Test
    fun `FakeToolExecutor unknown tool returns success`() {
        val exe = FakeToolExecutor(emptyMap())
        val result = exe.execute("unknown", "op1")
        assertTrue(result.success, "unknown tool should return success")
        assertTrue(exe.totalExecutions() == 1, "1 execution")
    }

    @Test
    fun `FakeToolExecutor reset`() {
        val scripts = mapOf("t" to ToolCallScript("t", ToolBehavior.SUCCESS))
        val exe = FakeToolExecutor(scripts)
        exe.execute("t", "op1")
        exe.reset()
        assertTrue(exe.totalExecutions() == 0, "reset executions")
        assertTrue(exe.sideEffectCount == 0, "reset side effects")
    }

    // ── FakeShell ──────────────────────────────────────────────────────────

    @Test
    fun `FakeShell success`() {
        val shell = FakeShell(ShellScript(ShellBehavior.SUCCESS))
        val result = shell.execute("op1")
        assertTrue(result.success, "shell success")
        assertTrue(shell.executionCount == 1, "1 execution")
    }

    @Test
    fun `FakeShell death after side effect`() {
        val shell = FakeShell(ShellScript(ShellBehavior.SHELL_DEATH_AFTER_SIDE_EFFECT, SideEffectLevel.UNKNOWN))
        val result = shell.execute("op1")
        assertTrue(!result.success, "shell died")
        assertTrue(result.sideEffectPerformed, "side effect performed")
        assertTrue(result.outcomeUnknown, "outcome unknown")
        assertTrue(shell.sideEffectCount == 1, "1 side effect")
        assertTrue(shell.duplicateSideEffectCount == 0, "0 duplicates")
    }

    @Test
    fun `FakeShell timeout`() {
        val shell = FakeShell(ShellScript(ShellBehavior.TIMEOUT))
        val result = shell.execute("op1")
        assertTrue(!result.success, "timeout")
        assertTrue(result.timedOut, "timedOut flag")
    }

    @Test
    fun `FakeShell truncated`() {
        val shell = FakeShell(ShellScript(ShellBehavior.TRUNCATED_OUTPUT))
        val result = shell.execute("op1")
        assertTrue(result.success, "truncated but alive")
        assertTrue(result.truncated, "truncated flag")
    }

    // ── FakePersistence ────────────────────────────────────────────────────

    @Test
    fun `FakePersistence write success`() {
        val p = FakePersistence(PersistenceScript())
        val ok = p.write(PersistenceMark.COMPLETED)
        assertTrue(ok, "write ok")
        assertTrue(p.finalMark == PersistenceMark.COMPLETED, "mark completed")
    }

    @Test
    fun `FakePersistence finalize fails`() {
        val p = FakePersistence(PersistenceScript(failOnFinalize = true))
        val ok = p.finalize(PersistenceMark.COMPLETED)
        assertTrue(!ok, "finalize fails")
        assertTrue(p.writeFailed, "writeFailed flag")
    }

    // ── FakeTraceSink ──────────────────────────────────────────────────────

    @Test
    fun `FakeTraceSink emit and count`() {
        val sink = FakeTraceSink()
        sink.emit(TraceEventType.RUN_START, 0)
        sink.emit(TraceEventType.RUN_FINALIZED, 100)
        assertTrue(sink.count(TraceEventType.RUN_START) == 1, "1 run_start")
        assertTrue(sink.count(TraceEventType.RUN_FINALIZED) == 1, "1 run_finalized")
        assertTrue(sink.events.size == 2, "2 events")
    }

    @Test
    fun `FakeTraceSink fail on append`() {
        val sink = FakeTraceSink(failOnAppend = true)
        val ok = sink.emit(TraceEventType.RUN_START, 0)
        assertTrue(!ok, "emit fails")
        assertTrue(sink.events.isEmpty(), "no events")
        assertTrue(sink.dropCount == 1, "1 drop")
    }

    // ── FakeSessionSlots ───────────────────────────────────────────────────

    @Test
    fun `FakeSessionSlots acquire within limit`() {
        val slots = FakeSessionSlots(3)
        assertTrue(slots.acquire("a"), "acquire a")
        assertTrue(slots.acquire("b"), "acquire b")
        assertTrue(slots.acquire("c"), "acquire c")
        assertTrue(slots.activeCount() == 3, "3 active")
        assertTrue(slots.waitingCount() == 0, "0 waiting")
    }

    @Test
    fun `FakeSessionSlots queue when full`() {
        val slots = FakeSessionSlots(2)
        assertTrue(slots.acquire("a"), "acquire a")
        assertTrue(slots.acquire("b"), "acquire b")
        assertTrue(!slots.acquire("c"), "c queued")
        assertTrue(slots.activeCount() == 2, "2 active")
        assertTrue(slots.waitingCount() == 1, "1 waiting")
        assertTrue(slots.waitingOrder().first() == "c", "c first in queue")
    }

    @Test
    fun `FakeSessionSlots FIFO release`() {
        val slots = FakeSessionSlots(2)
        slots.acquire("a"); slots.acquire("b"); slots.acquire("c")
        slots.release("a")
        assertTrue(slots.isActive("c"), "c promoted")
        assertTrue(!slots.isActive("a"), "a released")
        assertTrue(slots.activeCount() == 2, "2 active")
        assertTrue(slots.waitingCount() == 0, "0 waiting")
    }

    @Test
    fun `FakeSessionSlots cancel waiting`() {
        val slots = FakeSessionSlots(2)
        slots.acquire("a"); slots.acquire("b"); slots.acquire("c")
        assertTrue(slots.cancelWaiting("c"), "cancel c")
        assertTrue(!slots.isActive("c"), "c not active")
        assertTrue(slots.waitingCount() == 0, "0 waiting")
        slots.release("a")
        assertTrue(slots.activeCount() == 1, "1 active")
        assertTrue(!slots.isActive("c"), "c not revived")
    }

    @Test
    fun `FakeSessionSlots all released`() {
        val slots = FakeSessionSlots(3)
        slots.acquire("a"); slots.acquire("b"); slots.acquire("c")
        slots.release("a"); slots.release("b"); slots.release("c")
        assertTrue(slots.activeCount() == 0, "0 active")
        assertTrue(slots.isReleased("a"), "a released")
        assertTrue(slots.isReleased("b"), "b released")
        assertTrue(slots.isReleased("c"), "c released")
    }

    private fun assertTrue(condition: Any, message: String) {
        if (condition != true) throw AssertionError(message)
    }
}