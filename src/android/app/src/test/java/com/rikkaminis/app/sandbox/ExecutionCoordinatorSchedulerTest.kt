package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the shell generation scheduler pure functions extracted from
 * [ExecutionCoordinator].
 *
 * The scheduler is a progressive-degradation model inspired by connection
 * schedulers running under hard resource ceilings: instead of a single
 * failure cliff (the old 350MB hard cap freezing ALL commands), memory
 * pressure is shed in tiers — shrinking the shell generation budget,
 * recycling leaky/heavy commands earlier, and preserving lightweight command
 * availability so the agent can self-recover.
 *
 * All functions under test are pure (no Context / Debug / Process deps).
 */
class ExecutionCoordinatorSchedulerTest {

    // ── classifyCommand ───────────────────────────────────────────────

    @Test
    fun `classify empty command as light`() {
        assertEquals(CommandClass.LIGHT, classifyCommand(""))
    }

    @Test
    fun `classify ls as light`() {
        assertEquals(CommandClass.LIGHT, classifyCommand("ls -la"))
    }

    @Test
    fun `classify file tools as light`() {
        assertEquals(CommandClass.LIGHT, classifyCommand("cat /etc/hostname"))
        assertEquals(CommandClass.LIGHT, classifyCommand("grep -r foo /var/minis"))
        assertEquals(CommandClass.LIGHT, classifyCommand("echo hello"))
        assertEquals(CommandClass.LIGHT, classifyCommand("which python3"))
    }

    @Test
    fun `classify minis-model-use as leaky regardless of args`() {
        assertEquals(CommandClass.LEAKY, classifyCommand("minis-model-use list"))
        assertEquals(CommandClass.LEAKY, classifyCommand("minis-model-use run --model x --input /tmp/i.json"))
    }

    @Test
    fun `classify minis-model-use with leading whitespace as leaky`() {
        assertEquals(CommandClass.LEAKY, classifyCommand("  minis-model-use search foo"))
    }

    @Test
    fun `classify python3 as heavy`() {
        assertEquals(CommandClass.HEAVY, classifyCommand("python3 /tmp/script.py"))
        assertEquals(CommandClass.HEAVY, classifyCommand("python3 --version"))
    }

    @Test
    fun `classify apk as heavy`() {
        assertEquals(CommandClass.HEAVY, classifyCommand("apk add py3-numpy"))
        assertEquals(CommandClass.HEAVY, classifyCommand("apk"))
        assertEquals(CommandClass.HEAVY, classifyCommand("apk update"))
    }

    @Test
    fun `classify pip npm go as heavy`() {
        assertEquals(CommandClass.HEAVY, classifyCommand("pip install pandas"))
        assertEquals(CommandClass.HEAVY, classifyCommand("pip3 install torch"))
        assertEquals(CommandClass.HEAVY, classifyCommand("npm install"))
        assertEquals(CommandClass.HEAVY, classifyCommand("go build ./..."))
    }

    @Test
    fun `classify chained command by its leading token`() {
        // Classifier is deliberately prefix-based (cheap, predictable):
        // a chained command is classified by its FIRST token. `cd /tmp &&`
        // classifies LIGHT, so chained heavy work is NOT recycled by class —
        // the generation budget and memory thresholds still bound it, and
        // misclassification errs toward keeping the shell (safe).
        assertEquals(CommandClass.LIGHT, classifyCommand("cd /tmp && ls"))
        assertEquals(CommandClass.LIGHT, classifyCommand("cd /tmp && python3 run.py"))
        assertEquals(CommandClass.HEAVY, classifyCommand("python3 /tmp/run.py && echo done"))
    }

    // ── internalDegradationPhase ──────────────────────────────────────

    @Test
    fun `phase is normal below 50MB`() {
        assertEquals(ShellPhase.NORMAL, internalDegradationPhase(0))
        assertEquals(ShellPhase.NORMAL, internalDegradationPhase(49))
    }

    @Test
    fun `phase is mild at 50MB boundary and below 80`() {
        assertEquals(ShellPhase.MILD, internalDegradationPhase(50))
        assertEquals(ShellPhase.MILD, internalDegradationPhase(79))
    }

    @Test
    fun `phase is moderate at 80MB boundary and below 100`() {
        assertEquals(ShellPhase.MODERATE, internalDegradationPhase(80))
        assertEquals(ShellPhase.MODERATE, internalDegradationPhase(99))
    }

    @Test
    fun `phase is severe at 100MB boundary and below 120`() {
        assertEquals(ShellPhase.SEVERE, internalDegradationPhase(100))
        assertEquals(ShellPhase.SEVERE, internalDegradationPhase(119))
    }

    @Test
    fun `phase is critical at 120MB boundary and below 350`() {
        assertEquals(ShellPhase.CRITICAL, internalDegradationPhase(120))
        assertEquals(ShellPhase.CRITICAL, internalDegradationPhase(200))
        assertEquals(ShellPhase.CRITICAL, internalDegradationPhase(349))
    }

    @Test
    fun `phase is locked at 350MB and above`() {
        assertEquals(ShellPhase.LOCKED, internalDegradationPhase(350))
        assertEquals(ShellPhase.LOCKED, internalDegradationPhase(1000))
    }

    // ── internalGenerationBudget ──────────────────────────────────────

    @Test
    fun `budget is 30 below 50MB`() {
        assertEquals(30, internalGenerationBudget(0))
        assertEquals(30, internalGenerationBudget(49))
    }

    @Test
    fun `budget is 15 between 50 and 80MB`() {
        assertEquals(15, internalGenerationBudget(50))
        assertEquals(15, internalGenerationBudget(79))
    }

    @Test
    fun `budget is 5 between 80 and 100MB`() {
        assertEquals(5, internalGenerationBudget(80))
        assertEquals(5, internalGenerationBudget(99))
    }

    @Test
    fun `budget is 2 between 100 and 120MB`() {
        assertEquals(2, internalGenerationBudget(100))
        assertEquals(2, internalGenerationBudget(119))
    }

    @Test
    fun `budget keeps light at 8 while heavy and leaky drop to 1 at 120MB and above`() {
        assertEquals(8, internalGenerationBudget(120, CommandClass.LIGHT))
        assertEquals(8, internalGenerationBudget(350, CommandClass.LIGHT))
        assertEquals(8, internalGenerationBudget(1000, CommandClass.LIGHT))
        assertEquals(1, internalGenerationBudget(120, CommandClass.HEAVY))
        assertEquals(1, internalGenerationBudget(350, CommandClass.LEAKY))
        assertEquals(1, internalGenerationBudget(1000, CommandClass.HEAVY))
    }

    @Test
    fun `budget is class-agnostic below 120MB`() {
        assertEquals(30, internalGenerationBudget(0, CommandClass.HEAVY))
        assertEquals(15, internalGenerationBudget(50, CommandClass.LEAKY))
        assertEquals(5, internalGenerationBudget(80, CommandClass.HEAVY))
        assertEquals(2, internalGenerationBudget(100, CommandClass.LEAKY))
    }

    // ── shouldRecycleByClass ──────────────────────────────────────────

    @Test
    fun `leaky command always recycles regardless of memory`() {
        assertTrue(shouldRecycleByClass(CommandClass.LEAKY, 0))
        assertTrue(shouldRecycleByClass(CommandClass.LEAKY, 30))
        assertTrue(shouldRecycleByClass(CommandClass.LEAKY, 120))
        assertTrue(shouldRecycleByClass(CommandClass.LEAKY, 999))
    }

    @Test
    fun `heavy command recycles only above 80MB threshold`() {
        assertEquals(false, shouldRecycleByClass(CommandClass.HEAVY, 0))
        assertEquals(false, shouldRecycleByClass(CommandClass.HEAVY, 80))
        assertEquals(true, shouldRecycleByClass(CommandClass.HEAVY, 81))
        assertEquals(true, shouldRecycleByClass(CommandClass.HEAVY, 120))
    }

    @Test
    fun `light command never recycles by class`() {
        assertEquals(false, shouldRecycleByClass(CommandClass.LIGHT, 0))
        assertEquals(false, shouldRecycleByClass(CommandClass.LIGHT, 120))
        assertEquals(false, shouldRecycleByClass(CommandClass.LIGHT, 999))
    }

    @Test
    fun `heavy threshold is configurable`() {
        assertEquals(true, shouldRecycleByClass(CommandClass.HEAVY, 60, heavyRecycleThresholdMB = 50))
        assertEquals(false, shouldRecycleByClass(CommandClass.HEAVY, 40, heavyRecycleThresholdMB = 50))
    }

    // ── preExecRejectionMessage ───────────────────────────────────────

    @Test
    fun `normal and mild phases never reject`() {
        assertNull(preExecRejectionMessage(ShellPhase.NORMAL, CommandClass.LIGHT, 30))
        assertNull(preExecRejectionMessage(ShellPhase.NORMAL, CommandClass.HEAVY, 30))
        assertNull(preExecRejectionMessage(ShellPhase.NORMAL, CommandClass.LEAKY, 30))
        assertNull(preExecRejectionMessage(ShellPhase.MILD, CommandClass.HEAVY, 60))
    }

    @Test
    fun `moderate phase never rejects either`() {
        assertNull(preExecRejectionMessage(ShellPhase.MODERATE, CommandClass.LIGHT, 90))
        assertNull(preExecRejectionMessage(ShellPhase.MODERATE, CommandClass.LEAKY, 90))
        assertNull(preExecRejectionMessage(ShellPhase.MODERATE, CommandClass.HEAVY, 90))
    }

    @Test
    fun `severe phase rejects nothing since model calls are isolated`() {
        assertNull(preExecRejectionMessage(ShellPhase.SEVERE, CommandClass.LIGHT, 110))
        assertNull(preExecRejectionMessage(ShellPhase.SEVERE, CommandClass.HEAVY, 110))
        assertNull(preExecRejectionMessage(ShellPhase.SEVERE, CommandClass.LEAKY, 110))
    }

    @Test
    fun `critical phase rejects heavy and leaky but allows light`() {
        assertNull(preExecRejectionMessage(ShellPhase.CRITICAL, CommandClass.LIGHT, 150))
        val heavyMsg = preExecRejectionMessage(ShellPhase.CRITICAL, CommandClass.HEAVY, 150)
        assertTrue(heavyMsg != null && heavyMsg.contains("lightweight"))
        val leakyMsg = preExecRejectionMessage(ShellPhase.CRITICAL, CommandClass.LEAKY, 150)
        assertTrue(leakyMsg != null && leakyMsg.contains("lightweight"))
    }

    @Test
    fun `locked phase rejects everything`() {
        assertTrue(preExecRejectionMessage(ShellPhase.LOCKED, CommandClass.LIGHT, 400) != null)
        assertTrue(preExecRejectionMessage(ShellPhase.LOCKED, CommandClass.HEAVY, 400) != null)
        assertTrue(preExecRejectionMessage(ShellPhase.LOCKED, CommandClass.LEAKY, 400) != null)
    }

    // ── [memory-dynamic-budget] 动态边界（MemAvailable 感知） ─────────

    @Test
    fun `phase defaults to conservative baseline when memory unknown`() {
        // 默认 memAvailableMB=0（未知/紧张）→ 与旧版基线行为完全一致
        assertEquals(ShellPhase.LOCKED, internalDegradationPhase(350))
        assertEquals(ShellPhase.CRITICAL, internalDegradationPhase(120))
        assertEquals(ShellPhase.CRITICAL, internalDegradationPhase(200))
        assertEquals(ShellPhase.NORMAL, internalDegradationPhase(0))
    }

    @Test
    fun `phase raises rejection boundaries when memory is ample`() {
        // MemAvailable 4GB：CRITICAL 120→512，LOCKED 350→1536
        // 350MB native + 4GB 可用 → 只到 SEVERE（渐进降级，heavy 仍可跑）
        assertEquals(ShellPhase.SEVERE, internalDegradationPhase(350, memAvailableMB = 4096))
        assertEquals(ShellPhase.CRITICAL, internalDegradationPhase(512, memAvailableMB = 4096))
        assertEquals(ShellPhase.CRITICAL, internalDegradationPhase(1000, memAvailableMB = 4096))
        assertEquals(ShellPhase.LOCKED, internalDegradationPhase(1536, memAvailableMB = 4096))
        assertEquals(ShellPhase.LOCKED, internalDegradationPhase(2000, memAvailableMB = 4096))
    }

    @Test
    fun `phase keeps baseline when memory is tight`() {
        assertEquals(ShellPhase.LOCKED, internalDegradationPhase(350, memAvailableMB = 512))
        assertEquals(ShellPhase.CRITICAL, internalDegradationPhase(120, memAvailableMB = 512))
        assertEquals(ShellPhase.CRITICAL, internalDegradationPhase(200, memAvailableMB = 1024))
    }

    @Test
    fun `lower tiers never relax regardless of available memory`() {
        // 泄漏防护不放松：低 tier 边界与 MemAvailable 无关
        assertEquals(ShellPhase.NORMAL, internalDegradationPhase(49, memAvailableMB = 8192))
        assertEquals(ShellPhase.MILD, internalDegradationPhase(50, memAvailableMB = 8192))
        assertEquals(ShellPhase.MODERATE, internalDegradationPhase(80, memAvailableMB = 8192))
        assertEquals(ShellPhase.SEVERE, internalDegradationPhase(100, memAvailableMB = 8192))
    }

    // ── [memory-dynamic-budget] 动态高水位 ────────────────────────────

    @Test
    fun `child rss mark scales with available memory`() {
        assertEquals(256L, childRssHighWaterMarkMB(0))
        assertEquals(256L, childRssHighWaterMarkMB(512))
        assertEquals(256L, childRssHighWaterMarkMB(1023))
        assertEquals(512L, childRssHighWaterMarkMB(1024))
        assertEquals(1024L, childRssHighWaterMarkMB(2048))
        assertEquals(1536L, childRssHighWaterMarkMB(4096))
        assertEquals(1536L, childRssHighWaterMarkMB(8192))
    }

    @Test
    fun `app native mark is dynamic only when ample`() {
        assertEquals(120L, appNativeHighWaterMarkMB(0))
        assertEquals(120L, appNativeHighWaterMarkMB(1024))
        assertEquals(512L, appNativeHighWaterMarkMB(2048))
        assertEquals(512L, appNativeHighWaterMarkMB(8192))
    }

    // ── [memory-dynamic-budget] heavy 通道 ────────────────────────────

    @Test
    fun `critical heavy is allowed when memory is ample`() {
        // 内存充裕：CRITICAL + HEAVY 放行（heavy 通道，跑完强制回收）
        assertNull(preExecRejectionMessage(ShellPhase.CRITICAL, CommandClass.HEAVY, 150, memAvailableMB = 4096))
        assertNull(preExecRejectionMessage(ShellPhase.CRITICAL, CommandClass.HEAVY, 400, memAvailableMB = 4096))
    }

    @Test
    fun `critical light always allowed and leaky never allowed even when ample`() {
        assertNull(preExecRejectionMessage(ShellPhase.CRITICAL, CommandClass.LIGHT, 150, memAvailableMB = 4096))
        // LEAKY：DirectByteBuffer 泄漏不靠 recycle 解决，即使内存充裕也拒绝
        val leakyMsg = preExecRejectionMessage(ShellPhase.CRITICAL, CommandClass.LEAKY, 150, memAvailableMB = 4096)
        assertTrue(leakyMsg != null && leakyMsg.contains("lightweight"))
    }

    @Test
    fun `critical heavy still rejected when memory is not ample`() {
        assertTrue(preExecRejectionMessage(ShellPhase.CRITICAL, CommandClass.HEAVY, 150, memAvailableMB = 1024) != null)
        assertTrue(preExecRejectionMessage(ShellPhase.CRITICAL, CommandClass.HEAVY, 150, memAvailableMB = 0) != null)
    }

    @Test
    fun `locked rejects everything regardless of available memory`() {
        assertTrue(preExecRejectionMessage(ShellPhase.LOCKED, CommandClass.LIGHT, 1600, memAvailableMB = 8192) != null)
        assertTrue(preExecRejectionMessage(ShellPhase.LOCKED, CommandClass.HEAVY, 1600, memAvailableMB = 8192) != null)
        assertTrue(preExecRejectionMessage(ShellPhase.LOCKED, CommandClass.LEAKY, 1600, memAvailableMB = 8192) != null)
    }
}