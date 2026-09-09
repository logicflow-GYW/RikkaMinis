package com.rikkaminis.app.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8 — AgentRecoveryPolicy 纯 JVM 测试。
 *
 * 覆盖蓝图 §T8 测试矩阵：
 * - process death before first response
 * - after partial response
 * - after tool side effect before result
 * - persistence failure
 * - restart discovery (open-run detection)
 * - safe read-only resume
 * - unknown outcome requires verify
 * - repeated resume does not duplicate side effect
 *
 * 以及：isRecoveryValid 的重复恢复检测。
 */
class AgentRecoveryPolicyTest {

    // ─── 辅助 ─────────────────────────────────────────────────────────

    private fun evidence(
        originalRunId: String = "r1",
        terminalReason: AgentTerminalReason = AgentTerminalReason.PROCESS_INTERRUPTED,
        hasPartialOutputPersisted: Boolean = false,
        toolStartedCount: Int = 0,
        toolFinishedCount: Int = 0,
        hasOutcomeUnknownTool: Boolean = false,
        persistenceFailed: Boolean = false,
        providerAttemptStarted: Boolean = false,
    ) = InterruptedRunEvidence(
        originalRunId = originalRunId,
        terminalReason = terminalReason,
        hasPartialOutputPersisted = hasPartialOutputPersisted,
        toolStartedCount = toolStartedCount,
        toolFinishedCount = toolFinishedCount,
        hasOutcomeUnknownTool = hasOutcomeUnknownTool,
        persistenceFailed = persistenceFailed,
        providerAttemptStarted = providerAttemptStarted,
    )

    // ─── 1. Process death before first response ───────────────────────

    @Test
    fun `process death before first response — safe to resume as fresh run`() {
        val e = evidence(
            terminalReason = AgentTerminalReason.PROCESS_INTERRUPTED,
            providerAttemptStarted = false,
        )
        val outcome = AgentRecoveryPolicy.decide(e)
        assertTrue("expected SafeToResume", outcome is RecoveryOutcome.SafeToResume)
        val safe = outcome as RecoveryOutcome.SafeToResume
        assertFalse("no partial output expected", safe.hasPartialOutput)
        assertTrue("reason should mention no side effects", safe.reason.contains("No side effects"))
    }

    @Test
    fun `process death before first response — no provider attempt`() {
        val e = evidence(providerAttemptStarted = false)
        val outcome = AgentRecoveryPolicy.decide(e)
        assertTrue("expected SafeToResume", outcome is RecoveryOutcome.SafeToResume)
        assertFalse((outcome as RecoveryOutcome.SafeToResume).hasPartialOutput)
    }

    // ─── 2. After partial response (no tools, no side effects) ────────

    @Test
    fun `after partial response with no tools — safe to resume with continuation`() {
        val e = evidence(
            terminalReason = AgentTerminalReason.PROCESS_INTERRUPTED,
            hasPartialOutputPersisted = true,
            providerAttemptStarted = true,
        )
        val outcome = AgentRecoveryPolicy.decide(e)
        assertTrue("expected SafeToResume", outcome is RecoveryOutcome.SafeToResume)
        val safe = outcome as RecoveryOutcome.SafeToResume
        assertTrue("has partial output", safe.hasPartialOutput)
        assertTrue("reason should mention continuation", safe.reason.contains("continuation"))
    }

    @Test
    fun `partial output with tools completed — safe with partial flag`() {
        val e = evidence(
            hasPartialOutputPersisted = true,
            toolStartedCount = 2,
            toolFinishedCount = 2,
            providerAttemptStarted = true,
        )
        val outcome = AgentRecoveryPolicy.decide(e)
        assertTrue("expected SafeToResume", outcome is RecoveryOutcome.SafeToResume)
        assertTrue((outcome as RecoveryOutcome.SafeToResume).hasPartialOutput)
    }

    // ─── 3. Tool side effect before result (OutcomeUnknown) ───────────

    @Test
    fun `tool side effect started outcome unknown — requires shell status verification`() {
        val e = evidence(
            toolStartedCount = 1,
            toolFinishedCount = 0,
            hasOutcomeUnknownTool = true,
            providerAttemptStarted = true,
        )
        val outcome = AgentRecoveryPolicy.decide(e)
        assertTrue("expected RequiresVerification", outcome is RecoveryOutcome.RequiresVerification)
        val verify = outcome as RecoveryOutcome.RequiresVerification
        assertEquals("shell_status", verify.checkKey)
        assertTrue("description should mention shell death", verify.description.contains("shell"))
    }

    @Test
    fun `outcome unknown tool completed — requires tool state verification`() {
        val e = evidence(
            toolStartedCount = 1,
            toolFinishedCount = 1,
            hasOutcomeUnknownTool = true,
            providerAttemptStarted = true,
        )
        val outcome = AgentRecoveryPolicy.decide(e)
        assertTrue("expected RequiresVerification", outcome is RecoveryOutcome.RequiresVerification)
        val verify = outcome as RecoveryOutcome.RequiresVerification
        assertEquals("tool_state", verify.checkKey)
    }

    @Test
    fun `multiple outcome unknown tools some in progress — requires shell check`() {
        val e = evidence(
            toolStartedCount = 3,
            toolFinishedCount = 1,
            hasOutcomeUnknownTool = true,
            providerAttemptStarted = true,
        )
        val outcome = AgentRecoveryPolicy.decide(e)
        assertTrue("expected RequiresVerification", outcome is RecoveryOutcome.RequiresVerification)
        assertEquals("shell_status", (outcome as RecoveryOutcome.RequiresVerification).checkKey)
    }

    // ─── 4. Tools in progress (known outcome) ─────────────────────────

    @Test
    fun `tools in progress with known outcome — requires tool status verification`() {
        val e = evidence(
            toolStartedCount = 2,
            toolFinishedCount = 1,
            hasOutcomeUnknownTool = false,
            providerAttemptStarted = true,
        )
        val outcome = AgentRecoveryPolicy.decide(e)
        assertTrue("expected RequiresVerification", outcome is RecoveryOutcome.RequiresVerification)
        assertEquals("tool_status", (outcome as RecoveryOutcome.RequiresVerification).checkKey)
    }

    // ─── 5. Tools completed with known results ────────────────────────

    @Test
    fun `all tools completed with known results — safe to resume`() {
        val e = evidence(
            toolStartedCount = 3,
            toolFinishedCount = 3,
            hasOutcomeUnknownTool = false,
            providerAttemptStarted = true,
        )
        val outcome = AgentRecoveryPolicy.decide(e)
        assertTrue("expected SafeToResume", outcome is RecoveryOutcome.SafeToResume)
        assertFalse((outcome as RecoveryOutcome.SafeToResume).hasPartialOutput)
    }

    @Test
    fun `single tool completed with known result — safe to resume`() {
        val e = evidence(
            toolStartedCount = 1,
            toolFinishedCount = 1,
            hasOutcomeUnknownTool = false,
            providerAttemptStarted = true,
        )
        val outcome = AgentRecoveryPolicy.decide(e)
        assertTrue("expected SafeToResume", outcome is RecoveryOutcome.SafeToResume)
        val safe = outcome as RecoveryOutcome.SafeToResume
        assertTrue("reason should mention tools completed", safe.reason.contains("tool(s)"))
    }

    // ─── 6. Persistence failure ───────────────────────────────────────

    @Test
    fun `persistence failure — report interrupted`() {
        val e = evidence(
            persistenceFailed = true,
            providerAttemptStarted = true,
        )
        val outcome = AgentRecoveryPolicy.decide(e)
        assertTrue("expected ReportInterrupted", outcome is RecoveryOutcome.ReportInterrupted)
    }

    @Test
    fun `persistence failure even with completed tools — report interrupted`() {
        val e = evidence(
            persistenceFailed = true,
            toolStartedCount = 5,
            toolFinishedCount = 5,
            hasOutcomeUnknownTool = false,
            providerAttemptStarted = true,
        )
        val outcome = AgentRecoveryPolicy.decide(e)
        // 持久化失败优先于任何其他证据
        assertTrue("persistence failure must dominate all other evidence",
            outcome is RecoveryOutcome.ReportInterrupted)
    }

    @Test
    fun `persistence failure with partial output — report interrupted`() {
        val e = evidence(
            persistenceFailed = true,
            hasPartialOutputPersisted = true,
            providerAttemptStarted = true,
        )
        val outcome = AgentRecoveryPolicy.decide(e)
        assertTrue("persistence failure dominates partial output",
            outcome is RecoveryOutcome.ReportInterrupted)
    }

    // ─── 7. Provider attempt but no observable result ────────────────

    @Test
    fun `provider started but no output and no tools — requires provider state check`() {
        val e = evidence(
            providerAttemptStarted = true,
        )
        val outcome = AgentRecoveryPolicy.decide(e)
        assertTrue("expected RequiresVerification", outcome is RecoveryOutcome.RequiresVerification)
        assertEquals("provider_state", (outcome as RecoveryOutcome.RequiresVerification).checkKey)
    }

    @Test
    fun `provider started deadline exceeded no output — requires provider state check`() {
        val e = evidence(
            terminalReason = AgentTerminalReason.DEADLINE_EXCEEDED,
            providerAttemptStarted = true,
        )
        val outcome = AgentRecoveryPolicy.decide(e)
        assertTrue("expected RequiresVerification", outcome is RecoveryOutcome.RequiresVerification)
        assertEquals("provider_state", (outcome as RecoveryOutcome.RequiresVerification).checkKey)
    }

    @Test
    fun `provider started user cancelled no output — requires provider state check`() {
        val e = evidence(
            terminalReason = AgentTerminalReason.USER_CANCELLED,
            providerAttemptStarted = true,
        )
        val outcome = AgentRecoveryPolicy.decide(e)
        assertTrue("expected RequiresVerification", outcome is RecoveryOutcome.RequiresVerification)
        assertEquals("provider_state", (outcome as RecoveryOutcome.RequiresVerification).checkKey)
    }

    // ─── 8. Safe read-only resume (read-only tools completed) ─────────

    @Test
    fun `read-only tools completed — safe to resume`() {
        val e = evidence(
            toolStartedCount = 2,
            toolFinishedCount = 2,
            hasOutcomeUnknownTool = false,
            providerAttemptStarted = true,
        )
        val outcome = AgentRecoveryPolicy.decide(e)
        assertTrue("expected SafeToResume", outcome is RecoveryOutcome.SafeToResume)
    }

    @Test
    fun `no tools at all — safe to resume`() {
        val e = evidence(
            providerAttemptStarted = false,
        )
        val outcome = AgentRecoveryPolicy.decide(e)
        assertTrue("expected SafeToResume", outcome is RecoveryOutcome.SafeToResume)
        assertFalse((outcome as RecoveryOutcome.SafeToResume).hasPartialOutput)
    }

    // ─── 9. isRecoveryValid: repeated resume detection ────────────────

    @Test
    fun `recovery for new original run is valid`() {
        val existing = listOf(
            RecoveryRunReference("r1", "recovery-r1", AgentTerminalReason.PROCESS_INTERRUPTED, RecoveryOutcome.SafeToResume("ok", false)),
        )
        val newRecovery = RecoveryRunReference("r2", "recovery-r2", AgentTerminalReason.PROCESS_INTERRUPTED, RecoveryOutcome.SafeToResume("ok", false))
        assertTrue(AgentRecoveryPolicy.isRecoveryValid(existing, newRecovery))
    }

    @Test
    fun `repeated recovery of same original run is rejected`() {
        val existing = listOf(
            RecoveryRunReference("r1", "recovery-r1", AgentTerminalReason.PROCESS_INTERRUPTED, RecoveryOutcome.SafeToResume("ok", false)),
        )
        val duplicate = RecoveryRunReference("r1", "recovery-r1-again", AgentTerminalReason.PROCESS_INTERRUPTED, RecoveryOutcome.SafeToResume("ok", false))
        assertFalse("duplicate recovery of same originalRunId must be rejected",
            AgentRecoveryPolicy.isRecoveryValid(existing, duplicate))
    }

    @Test
    fun `empty existing recoveries — any new recovery is valid`() {
        val newRecovery = RecoveryRunReference("r1", "recovery-r1", AgentTerminalReason.PROCESS_INTERRUPTED, RecoveryOutcome.SafeToResume("ok", false))
        assertTrue(AgentRecoveryPolicy.isRecoveryValid(emptyList(), newRecovery))
    }

    @Test
    fun `multiple existing recoveries — only duplicate originalRunId is rejected`() {
        val existing = listOf(
            RecoveryRunReference("r1", "recovery-r1", AgentTerminalReason.PROCESS_INTERRUPTED, RecoveryOutcome.SafeToResume("ok", false)),
            RecoveryRunReference("r2", "recovery-r2", AgentTerminalReason.DEADLINE_EXCEEDED, RecoveryOutcome.RequiresVerification("check", "desc")),
        )
        // r3 是新的 → 有效
        val r3 = RecoveryRunReference("r3", "recovery-r3", AgentTerminalReason.PROCESS_INTERRUPTED, RecoveryOutcome.SafeToResume("ok", false))
        assertTrue(AgentRecoveryPolicy.isRecoveryValid(existing, r3))
        // r1 重复 → 无效
        val r1again = RecoveryRunReference("r1", "recovery-r1-again", AgentTerminalReason.PROCESS_INTERRUPTED, RecoveryOutcome.SafeToResume("ok", false))
        assertFalse(AgentRecoveryPolicy.isRecoveryValid(existing, r1again))
    }

    // ─── 10. Edge cases ───────────────────────────────────────────────

    @Test
    fun `tools finished known result but persistence failed — persistence dominates`() {
        val e = evidence(
            toolStartedCount = 3,
            toolFinishedCount = 3,
            hasOutcomeUnknownTool = false,
            persistenceFailed = true,
            providerAttemptStarted = true,
        )
        val outcome = AgentRecoveryPolicy.decide(e)
        assertTrue("persistence failure dominates even with completed tools",
            outcome is RecoveryOutcome.ReportInterrupted)
    }

    @Test
    fun `no evidence at all — fresh safe resume`() {
        // 默认构造：无任何活动
        val e = InterruptedRunEvidence(
            originalRunId = "r-empty",
            terminalReason = AgentTerminalReason.PROCESS_INTERRUPTED,
            hasPartialOutputPersisted = false,
            toolStartedCount = 0,
            toolFinishedCount = 0,
            hasOutcomeUnknownTool = false,
            persistenceFailed = false,
            providerAttemptStarted = false,
        )
        val outcome = AgentRecoveryPolicy.decide(e)
        assertTrue("empty evidence → SafeToResume", outcome is RecoveryOutcome.SafeToResume)
    }

    @Test
    fun `all tool calls completed with unknown outcome — requires tool state verification`() {
        val e = evidence(
            toolStartedCount = 2,
            toolFinishedCount = 2,
            hasOutcomeUnknownTool = true,
            providerAttemptStarted = true,
        )
        val outcome = AgentRecoveryPolicy.decide(e)
        assertTrue("expected RequiresVerification", outcome is RecoveryOutcome.RequiresVerification)
        assertEquals("tool_state", (outcome as RecoveryOutcome.RequiresVerification).checkKey)
    }

    @Test
    fun `interrupted with outcome unknown reason — no tools, uses default rule`() {
        val e = evidence(
            terminalReason = AgentTerminalReason.OUTCOME_UNKNOWN,
            providerAttemptStarted = true,
        )
        val outcome = AgentRecoveryPolicy.decide(e)
        // 有 provider 调用但无工具、无 output → 规则 8: RequiresVerification
        assertTrue("expected RequiresVerification", outcome is RecoveryOutcome.RequiresVerification)
    }
}