package com.rikkaminis.app.conversation

import com.rikkaminis.app.data.ContextPolicy
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.AgentToolDefinition
import com.rikkaminis.app.data.model.AgentToolParam
import com.rikkaminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/prewindow-budget-1015] JVM 回归测试。现场：2026-10-15 T321 session
 * 16c39966 —— 同一份 ~4.9MB 历史，deepseek 中转口径 191,856 tokens，GLM
 * 直连口径 920,577；本地账本持 191.9K → dynamicMaxTokens 顶格 128K →
 * 1,048,577 > 1,048,576 必然 400，且无任何本地闸门可见。
 */
class PrewindowBudgetTest {

    // ── ContextEstimator 结构感知 ─────────────────────────────────────

    @Test
    fun contentPartsNonEmptyIgnoresLegacyContent() {
        // wire 只发 parts（OpenAIProvider.kt:2070 分支），估算必须同样互斥
        val m = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "legacy-field-that-should-be-ignored".repeat(50),
            contentParts = listOf(AgentContentPart.Text("hello world")),
        )
        val est = ContextEstimator.estimateMessageTokensFull(m)
        assertEquals(2L, est) // "hello world" = 11 chars → cjk 0 + 11/4 = 2
    }

    @Test
    fun legacyPathCountsContentAndTopLevelImages() {
        val bytes = ByteArray(3_000) // → base64 4000 chars → 1000 tokens 上界
        val m = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "plain text",
            imageParts = listOf(LLMMessage.ImagePart(bytes, "image/png")),
        )
        val est = ContextEstimator.estimateMessageTokensFull(m)
        assertEquals(1002L, est) // 4000/4 + 10/4
    }

    @Test
    fun toolSchemaAndSystemCountTowardRequestTotal() {
        val tool = AgentToolDefinition(
            name = "shell_execute",
            description = "Execute a shell command in an isolated Linux process. ".repeat(20),
            parameters = mapOf("command" to AgentToolParam(type = "string", description = "The shell command to execute.")),
        )
        val msgs = listOf(
            LLMMessage(LLMMessage.Role.USER, "hi", contentParts = listOf(AgentContentPart.Text("hi"))),
        )
        val withTools = ContextEstimator.estimateRequestTokens(msgs, null, listOf(tool))
        val noTools = ContextEstimator.estimateRequestTokens(msgs, null, emptyList())
        assertTrue("tool schema 应占大头: $withTools vs $noTools", withTools - noTools > 100)
        val withSys = ContextEstimator.estimateRequestTokens(
            msgs, "You are a helpful assistant. ".repeat(30), emptyList(),
        )
        assertTrue("system 应计入", withSys > noTools + 50)
    }

    @Test
    fun cjkCountsConservativelyOneTokenPerChar() {
        val cjk = "这是一段用于验证中文估算口径的文本，长度大约二十个字。"
        val m = LLMMessage(LLMMessage.Role.USER, cjk, contentParts = listOf(AgentContentPart.Text(cjk)))
        val est = ContextEstimator.estimateMessageTokensFull(m)
        assertTrue("中文应 ≈1字1token（含标点），got $est for ${cjk.length} chars", est >= cjk.length - 2)
    }

    @Test
    fun reasoningContentEchoCountsTowardInput() {
        val m = LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = listOf(AgentContentPart.ToolUse("t1", "shell_execute", org.json.JSONObject("{\"command\":\"ls\"}"))),
            reasoningContent = "需要先列出目录内容再决定下一步操作。".repeat(10),
        )
        val est = ContextEstimator.estimateMessageTokensFull(m)
        assertTrue("reasoning 回显应计入: got $est", est > 100)
    }

    // ── preflight 三档裁决 ───────────────────────────────────────────

    @Test
    fun t321FieldScenarioShrinksOutputOn1MWindow() {
        // GLM 直连口径（诚实记账）：920,577 + 128,000 = 1,048,577 > 1,048,576
        // —— 超窗 1 token 的必然 400。预检收缩输出到 123,999
        // （= 1,048,576 − 920,577 − 4,000 margin），最小干预消除 400。
        val verdict = preflightContextBudget(
            window = 1_048_576,
            estimatedInput = 920_577,
            requestedMaxTokens = 128_000,
            outputFloor = 1_024,
        )
        assertEquals(PreflightVerdict.Decision.SHRINK_OUTPUT, verdict.decision)
        assertEquals(123_999, verdict.suggestedMaxTokens)
    }

    @Test
    fun needsCompactionWhenEvenMinOutputCannotFit() {
        val verdict = preflightContextBudget(
            window = 1_048_576,
            estimatedInput = 1_045_000,
            requestedMaxTokens = 128_000,
            outputFloor = 1_024,
        )
        assertEquals(PreflightVerdict.Decision.NEEDS_COMPACTION, verdict.decision)
    }

    @Test
    fun shrinksOutputOnGroupClampedWindow() {
        // 220K 窗口（组限制）：220K − 191.9K − 4K = 24.1K ≥ 1024 → 收缩输出
        val verdict = preflightContextBudget(
            window = 220_000,
            estimatedInput = 191_900,
            requestedMaxTokens = 128_000,
            outputFloor = 1_024,
        )
        assertEquals(PreflightVerdict.Decision.SHRINK_OUTPUT, verdict.decision)
        assertEquals(24_100, verdict.suggestedMaxTokens)
    }

    @Test
    fun passesWithComfortableHeadroom() {
        val verdict = preflightContextBudget(
            window = 220_000,
            estimatedInput = 50_000,
            requestedMaxTokens = 128_000,
            outputFloor = 1_024,
        )
        assertEquals(PreflightVerdict.Decision.PASS, verdict.decision)
    }

    @Test
    fun passesWhenWindowUnknown() {
        val verdict = preflightContextBudget(
            window = 0,
            estimatedInput = 10_000_000,
            requestedMaxTokens = 128_000,
            outputFloor = 1_024,
        )
        assertEquals(PreflightVerdict.Decision.PASS, verdict.decision)
    }

    // ── CalibrationStore 学习与失真 ──────────────────────────────────

    @Test
    fun ratioStaysConservativeUntilMinSamples() {
        val s = CalibrationStore()
        s.observe("k", 300, 100) // ratio 3.0 —— 带内（边界含），收
        s.observe("k", 300, 100)
        assertEquals(1.0, s.ratio("k"), 1e-9) // 2 < DEFAULT_MIN_SAMPLES(3)
    }

    @Test
    fun ratioIsWindowMedianOnceReady() {
        val s = CalibrationStore()
        s.observe("k", 240, 100) // 2.4
        s.observe("k", 480, 100) // 4.8 → 带外拒收
        s.observe("k", 250, 100) // 2.5
        s.observe("k", 260, 100) // 2.6
        assertEquals(2.5, s.ratio("k"), 1e-9) // 中位数(2.4, 2.5, 2.6)
    }

    @Test
    fun outOfBandRatiosRejectedAsDistorted() {
        val s = CalibrationStore()
        assertEquals(CalibrationStore.ObserveResult.DISTORTED, s.observe("k", 500, 100)) // 5.0 > 3.0
        assertEquals(CalibrationStore.ObserveResult.DISTORTED, s.observe("k", 30, 100))  // 0.3 < 1/3
        assertEquals(0, s.sampleCount("k"))
    }

    @Test
    fun slidingWindowKeepsOnlyRecentSamples() {
        val s = CalibrationStore()
        // 全带内：ratios 2.00, 2.05, …, 2.55（12 个）→ 只留最后 8 个
        for (i in 0 until 12) s.observe("k", 200L + 5L * i, 100)
        assertEquals(CalibrationStore.DEFAULT_WINDOW_SIZE, s.sampleCount("k"))
        val r = s.ratio("k")
        assertTrue("ratio=$r", r in 2.0..2.6)
    }

    @Test
    fun restoreRecoversRatioColdStart() {
        val s = CalibrationStore()
        s.observe("k", 250, 100)
        s.observe("k", 240, 100)
        s.observe("k", 260, 100)
        val s2 = CalibrationStore()
        s2.restore(mapOf("k" to s.snapshot()["k"]!!))
        assertEquals(s.ratio("k"), s2.ratio("k"), 1e-9)
        assertTrue(s2.isReady("k"))
    }

    @Test
    fun inBandLargeRatioIsLearnedNotRejected() {
        // 设计语义（T321 现场校准过）：端点间的系统性口径差异（如 2.8×）是
        // 校准要学的东西，不是垃圾 —— 只有带外（>3×）才拒收。
        val s = CalibrationStore()
        s.observe("k", 280, 100)
        s.observe("k", 285, 100)
        s.observe("k", 275, 100)
        assertEquals(2.8, s.ratio("k"), 1e-9)
    }

    // ── ContextPolicy 独立性防御 ─────────────────────────────────────

    @Test
    fun contextPolicyBandUnchanged() {
        // 220K 窗口的 compact 线语义不应因本次改动漂移
        val p = ContextPolicy.forContextWindow(220_000)
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, p.check(191_856, 220_000))
    }
}
