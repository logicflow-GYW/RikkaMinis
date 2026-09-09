package com.rikkaminis.app.harness.scenarios

import com.rikkaminis.app.harness.contract.*

/**
 * 故障矩阵 F01-F14 场景定义。
 *
 * 每个场景对应 failure-matrix.md 中的一行。
 * 期望值基于参考 HarnessRunner 的确定性行为推导。
 */
object FaultScenarios {

    /**
     * 返回所有 14 个场景。
     */
    fun all(): List<FaultScenario> = listOf(
        F01, F02, F03, F04, F05, F06, F07, F08, F09, F10, F11, F12, F13, F14
    )

    // ── F01: 第一个 provider 429，fallback 成功 ────────────────────────────
    val F01 = FaultScenario(
        id = "F01",
        description = "第一个 provider 返回 429，fallback 成功",
        turns = listOf(
            ModelTurnScript(
                attempts = listOf(
                    AttemptScript(result = AttemptResult.HTTP_429, delayMs = 0),
                    AttemptScript(result = AttemptResult.SUCCESS, finalAnswer = true),
                )
            )
        ),
        deadlineMs = Long.MAX_VALUE / 2,
        expect = ScenarioExpectations(
            terminal = TerminalState.SUCCEEDED,
            providerAttempts = 2,
            toolExecutions = 0,
            budget = mapOf("provider_attempts_consumed" to 2),
            persistence = PersistenceMark.COMPLETED,
            cooldownCount = 1,
            recoverable = false,
        ),
    )

    // ── F02: 所有 provider 失败 ────────────────────────────────────────────
    val F02 = FaultScenario(
        id = "F02",
        description = "所有 provider 都失败（fallback 链耗尽）",
        turns = listOf(
            ModelTurnScript(
                attempts = listOf(
                    AttemptScript(AttemptResult.HTTP_429),
                    AttemptScript(AttemptResult.HARD_FAILURE),
                    AttemptScript(AttemptResult.STREAM_RESET),
                )
            )
        ),
        deadlineMs = Long.MAX_VALUE / 2,
        expect = ScenarioExpectations(
            terminal = TerminalState.FAILED,
            providerAttempts = 3,
            toolExecutions = 0,
            budget = mapOf("provider_attempts_consumed" to 3),
            persistence = PersistenceMark.NONE,
            cooldownCount = 1,
            recoverable = false,
        ),
    )

    // ── F03: stream reset → retry/fallback 次数有限 ────────────────────────
    val F03 = FaultScenario(
        id = "F03",
        description = "stream reset（HTTP/2 流重置），fallback 成功",
        turns = listOf(
            ModelTurnScript(
                attempts = listOf(
                    AttemptScript(AttemptResult.STREAM_RESET),
                    AttemptScript(AttemptResult.SUCCESS, finalAnswer = true),
                )
            )
        ),
        deadlineMs = Long.MAX_VALUE / 2,
        expect = ScenarioExpectations(
            terminal = TerminalState.SUCCEEDED,
            providerAttempts = 2,
            persistence = PersistenceMark.COMPLETED,
            recoverable = false,
        ),
    )

    // ── F04: 首 chunk 后 provider 断流 ─────────────────────────────────────
    val F04 = FaultScenario(
        id = "F04",
        description = "首个 chunk 之后 provider 断流",
        turns = listOf(
            ModelTurnScript(
                attempts = listOf(
                    AttemptScript(AttemptResult.DROP_AFTER_FIRST_CHUNK),
                )
            )
        ),
        deadlineMs = Long.MAX_VALUE / 2,
        expect = ScenarioExpectations(
            terminal = TerminalState.INTERRUPTED,
            providerAttempts = 1,
            persistence = PersistenceMark.PARTIAL,
            recoverable = true,
        ),
    )

    // ── F05: 工具执行失败 → tool_result 回传 → loop 收敛 ──────────────────
    val F05 = FaultScenario(
        id = "F05",
        description = "工具执行失败，tool_result 回传 LLM，loop 收敛",
        turns = listOf(
            ModelTurnScript(
                attempts = listOf(
                    AttemptScript(AttemptResult.SUCCESS, toolCalls = listOf("failing_tool")),
                )
            ),
            ModelTurnScript(
                attempts = listOf(
                    AttemptScript(AttemptResult.SUCCESS, finalAnswer = true),
                )
            ),
        ),
        toolScripts = mapOf(
            "failing_tool" to ToolCallScript(
                toolName = "failing_tool",
                behavior = ToolBehavior.FAILURE,
            )
        ),
        deadlineMs = Long.MAX_VALUE / 2,
        expect = ScenarioExpectations(
            terminal = TerminalState.SUCCEEDED,
            providerAttempts = 2,
            toolExecutions = 1,
            persistence = PersistenceMark.COMPLETED,
            recoverable = false,
        ),
    )

    // ── F06: 工具副作用已发生，返回前 shell 死亡 ──────────────────────────
    val F06 = FaultScenario(
        id = "F06",
        description = "工具副作用已发生，返回前 shell 死亡",
        turns = listOf(
            ModelTurnScript(
                attempts = listOf(
                    AttemptScript(AttemptResult.SUCCESS, toolCalls = listOf("side_effect_tool")),
                )
            ),
        ),
        toolScripts = mapOf(
            "side_effect_tool" to ToolCallScript(
                toolName = "side_effect_tool",
                behavior = ToolBehavior.SIDE_EFFECT_THEN_NO_RESULT,
                sideEffectLevel = SideEffectLevel.NON_IDEMPOTENT_WRITE,
            )
        ),
        deadlineMs = Long.MAX_VALUE / 2,
        expect = ScenarioExpectations(
            terminal = TerminalState.INTERRUPTED,
            providerAttempts = 1,
            toolExecutions = 1,
            duplicateSideEffects = 0,
            persistence = PersistenceMark.PARTIAL,
            recoverable = true,
        ),
    )

    // ── F07: 用户在 provider 调用中取消 ────────────────────────────────────
    val F07 = FaultScenario(
        id = "F07",
        description = "用户在 provider 调用中取消",
        turns = listOf(
            ModelTurnScript(
                attempts = listOf(
                    AttemptScript(AttemptResult.HTTP_429, delayMs = 10000, cooldownMs = 60000),
                )
            ),
        ),
        userCancelAtMs = 5000,
        deadlineMs = Long.MAX_VALUE / 2,
        expect = ScenarioExpectations(
            terminal = TerminalState.CANCELLED,
            providerAttempts = 1,
            persistence = PersistenceMark.PARTIAL,
            providerCancellations = 1,
            recoverable = false,
        ),
    )

    // ── F08: 用户在工具调用中取消 ──────────────────────────────────────────
    val F08 = FaultScenario(
        id = "F08",
        description = "用户在工具调用中取消",
        turns = listOf(
            ModelTurnScript(
                attempts = listOf(
                    AttemptScript(AttemptResult.SUCCESS, toolCalls = listOf("blocking_tool")),
                )
            ),
        ),
        toolScripts = mapOf(
            "blocking_tool" to ToolCallScript(
                toolName = "blocking_tool",
                behavior = ToolBehavior.BLOCK_UNTIL_CANCELLED,
            )
        ),
        userCancelAtMs = 10,
        deadlineMs = Long.MAX_VALUE / 2,
        expect = ScenarioExpectations(
            terminal = TerminalState.CANCELLED,
            providerAttempts = 1,
            toolExecutions = 1,
            persistence = PersistenceMark.PARTIAL,
            toolCancellations = 1,
            recoverable = false,
        ),
    )

    // ── F09: compact 超时 ──────────────────────────────────────────────────
    val F09 = FaultScenario(
        id = "F09",
        description = "compact 超时，原始历史不损坏",
        turns = listOf(
            ModelTurnScript(
                attempts = listOf(
                    AttemptScript(AttemptResult.SUCCESS, finalAnswer = true),
                ),
                compactDelayMs = 5000,
            )
        ),
        compactTimeoutMs = 1000,
        deadlineMs = Long.MAX_VALUE / 2,
        expect = ScenarioExpectations(
            terminal = TerminalState.INTERRUPTED,
            providerAttempts = 0,  // compact 超时在 provider 尝试之前
            compactCalls = 1,
            persistence = PersistenceMark.PARTIAL,
            historyIntact = true,
            recoverable = true,
        ),
    )

    // ── F10: 五会话并发，第六个排队 ──────────────────────────────────────
    // 注意：F10 不走完整 runner 的 turn loop，而是走 FakeSessionSlots 专用测试。
    // 以下是场景定义，用于测试框架。
    val F10 = FaultScenario(
        id = "F10",
        description = "五个会话并发，第六个排队",
        turns = emptyList(),
        deadlineMs = Long.MAX_VALUE / 2,
        expect = ScenarioExpectations(
            terminal = TerminalState.SUCCEEDED,
            providerAttempts = 0,
            toolExecutions = 0,
            persistence = PersistenceMark.COMPLETED,
            recoverable = false,
        ),
    )

    // ── F11: deadline 到达 ─────────────────────────────────────────────────
    val F11 = FaultScenario(
        id = "F11",
        description = "deadline 到达，不再发新 provider/tool 请求",
        turns = listOf(
            ModelTurnScript(
                attempts = listOf(
                    AttemptScript(AttemptResult.HTTP_429, delayMs = 15000),
                )
            ),
        ),
        deadlineMs = 10000,
        expect = ScenarioExpectations(
            terminal = TerminalState.INTERRUPTED,
            providerAttempts = 1,  // attempt 已启动，deadline 在延迟期间到达
            providerCancellations = 1,  // deadline 打断进行中的 attempt
            persistence = PersistenceMark.PARTIAL,
            recoverable = true,
        ),
    )

    // ── F12: persistence 写入失败 ──────────────────────────────────────────
    val F12 = FaultScenario(
        id = "F12",
        description = "persistence 写入失败，不伪装成功",
        turns = listOf(
            ModelTurnScript(
                attempts = listOf(
                    AttemptScript(AttemptResult.SUCCESS, finalAnswer = true),
                )
            ),
        ),
        persistence = PersistenceScript(failOnFinalize = true),
        deadlineMs = Long.MAX_VALUE / 2,
        expect = ScenarioExpectations(
            terminal = TerminalState.FAILED,
            providerAttempts = 1,
            persistence = PersistenceMark.FAILED,
            recoverable = false,
        ),
    )

    // ── F13: 子 agent 试图递归 spawn ──────────────────────────────────────
    val F13 = FaultScenario(
        id = "F13",
        description = "子 agent 试图递归 spawn，被拒绝",
        turns = listOf(
            ModelTurnScript(
                attempts = listOf(
                    AttemptScript(AttemptResult.SUCCESS, toolCalls = listOf("spawn_child")),
                )
            ),
            ModelTurnScript(
                attempts = listOf(
                    AttemptScript(AttemptResult.SUCCESS, finalAnswer = true),
                )
            ),
        ),
        toolScripts = mapOf(
            "spawn_child" to ToolCallScript(
                toolName = "spawn_child",
                behavior = ToolBehavior.SPAWN,
                spawnDepth = 1,  // 子 agent 内部再 spawn → 拒绝
            )
        ),
        deadlineMs = Long.MAX_VALUE / 2,
        expect = ScenarioExpectations(
            terminal = TerminalState.SUCCEEDED,
            providerAttempts = 2,
            toolExecutions = 1,
            spawnRejected = 1,
            persistence = PersistenceMark.COMPLETED,
            recoverable = false,
        ),
    )

    // ── F14: process death/restart 模拟 ────────────────────────────────────
    val F14 = FaultScenario(
        id = "F14",
        description = "process death/restart 模拟",
        turns = listOf(
            ModelTurnScript(
                attempts = listOf(
                    AttemptScript(AttemptResult.SUCCESS, toolCalls = listOf("side_effect_tool_f14")),
                )
            ),
        ),
        toolScripts = mapOf(
            "side_effect_tool_f14" to ToolCallScript(
                toolName = "side_effect_tool_f14",
                behavior = ToolBehavior.SIDE_EFFECT_THEN_NO_RESULT,
                sideEffectLevel = SideEffectLevel.NON_IDEMPOTENT_WRITE,
            )
        ),
        processDeathAtMs = 20,
        deadlineMs = Long.MAX_VALUE / 2,
        expect = ScenarioExpectations(
            terminal = TerminalState.INTERRUPTED,
            providerAttempts = 1,
            toolExecutions = 1,
            duplicateSideEffects = 0,
            persistence = PersistenceMark.PARTIAL,
            recoverable = true,
        ),
    )
}