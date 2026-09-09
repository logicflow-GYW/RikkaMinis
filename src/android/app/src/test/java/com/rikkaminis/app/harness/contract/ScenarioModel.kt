package com.rikkaminis.app.harness.contract

/**
 * 场景定义：一组可脚本化的故障注入 + 期望断言。
 * 每个场景 = 一个 FaultScenario，由 HarnessRunner 执行后经 ScenarioVerifier 验证。
 */

/** Provider 一次 attempt 的脚本结果。 */
enum class AttemptResult {
    SUCCESS,
    HTTP_429,
    STREAM_RESET,
    DROP_AFTER_FIRST_CHUNK,  // 首 chunk 后断流
    HARD_FAILURE,
    LENGTH_FINISH,           // finish_reason=length，需续写
}

/** Provider 一次 attempt 的脚本。 */
data class AttemptScript(
    val result: AttemptResult,
    val delayMs: Long = 0,
    /** 当 result=SUCCESS 时，这次 attempt 产出的工具调用名列表。 */
    val toolCalls: List<String> = emptyList(),
    /** 是否是该 turn 的最终答案（无工具）。 */
    val finalAnswer: Boolean = false,
    /** 429 后的冷却时长（默认 60s）。 */
    val cooldownMs: Long = 60_000,
)

/** 一轮模型交互（turn）的脚本。 */
data class ModelTurnScript(
    /** fallback 链：按顺序尝试，直到成功或耗尽。 */
    val attempts: List<AttemptScript>,
    /** 如果本 turn 中有 compact 调用，紧凑延迟 ms。 */
    val compactDelayMs: Long = 0,
)

/** 工具行为脚本。 */
enum class ToolBehavior {
    SUCCESS,
    FAILURE,
    SIDE_EFFECT_THEN_NO_RESULT,
    BLOCK_UNTIL_CANCELLED,
    SPAWN,
}

/** 工具调用脚本。 */
data class ToolCallScript(
    val toolName: String,
    val behavior: ToolBehavior,
    val sideEffectLevel: SideEffectLevel = SideEffectLevel.UNKNOWN,
    /** 仅当 behavior=SPAWN 时，当前子 agent 深度。 */
    val spawnDepth: Int = 0,
)

/** Shell 行为脚本。 */
enum class ShellBehavior {
    SUCCESS,
    SHELL_DEATH_AFTER_SIDE_EFFECT,
    TIMEOUT,
    TRUNCATED_OUTPUT,
}

/** Shell 脚本。 */
data class ShellScript(
    val behavior: ShellBehavior,
    val sideEffectLevel: SideEffectLevel = SideEffectLevel.UNKNOWN,
)

/** 持久化脚本。 */
data class PersistenceScript(
    val failOnWrite: Boolean = false,
    val failOnFinalize: Boolean = false,
)

/** 场景期望值。 */
data class ScenarioExpectations(
    val terminal: TerminalState,
    val providerAttempts: Int,
    val toolExecutions: Int = 0,
    val duplicateSideEffects: Int = 0,
    val budget: Map<String, Int> = emptyMap(),
    val leasesReleased: Boolean = true,
    val traceTerminalEvents: Int = 1,
    val persistence: PersistenceMark = PersistenceMark.COMPLETED,
    val recoverable: Boolean = false,
    val cooldownCount: Int = 0,
    val providerCancellations: Int = 0,
    val toolCancellations: Int = 0,
    val spawnRejected: Int = 0,
    val compactCalls: Int = 0,
    val historyIntact: Boolean = true,
)

/** 完整故障场景。 */
data class FaultScenario(
    val id: String,
    val description: String,
    /** Provider fallback 链脚本（每个 turn 共用）。 */
    val turns: List<ModelTurnScript>,
    /** 工具脚本（按 toolName 索引）。 */
    val toolScripts: Map<String, ToolCallScript> = emptyMap(),
    /** Shell 脚本（可选）。 */
    val shellScript: ShellScript? = null,
    /** 持久化脚本。 */
    val persistence: PersistenceScript = PersistenceScript(),
    /** 用户在哪个单调时间点取消（null=不取消）。 */
    val userCancelAtMs: Long? = null,
    /** 总 deadline（相对 run 开始）。 */
    val deadlineMs: Long = Long.MAX_VALUE / 2,
    /** 紧凑超时时间（>0 时启用）。 */
    val compactTimeoutMs: Long = 0,
    /** 进程死亡时间点（null=不发生）。 */
    val processDeathAtMs: Long? = null,
    /** 最大并发会话数（用于 F10）。 */
    val maxConcurrent: Int = 5,
    /** 场景期望值。 */
    val expect: ScenarioExpectations,
)

/**
 * 渲染为 failure-matrix.md §2 格式的协议文本。
 * 可用于验证文档 ↔ 代码一致性。
 */
fun FaultScenario.toProtocolText(): String = buildString {
    appendLine("scenario ${id}:")
    appendLine("  description: $description")
    appendLine("  turns:")
    turns.forEachIndexed { i, turn ->
        appendLine("    turn$i:")
        turn.attempts.forEachIndexed { j, a ->
            val resultStr = when (a.result) {
                AttemptResult.HTTP_429 -> "429 (with Retry-After absent)"
                AttemptResult.SUCCESS -> "success, stream ok"
                AttemptResult.STREAM_RESET -> "stream reset"
                AttemptResult.DROP_AFTER_FIRST_CHUNK -> "drop after first chunk"
                AttemptResult.HARD_FAILURE -> "hard failure"
                AttemptResult.LENGTH_FINISH -> "finish_reason=length"
            }
            val toolSuffix = if (a.toolCalls.isNotEmpty()) " (toolCalls=${a.toolCalls})" else ""
            val finalSuffix = if (a.finalAnswer) " (final)" else ""
            appendLine("      - attempt$j: $resultStr$toolSuffix$finalSuffix")
        }
        if (turn.compactDelayMs > 0) {
            appendLine("      compact_delay: ${turn.compactDelayMs}ms")
        }
    }
    if (shellScript != null) {
        appendLine("  shell: ${shellScript.behavior}")
    }
    if (userCancelAtMs != null) {
        appendLine("  user_cancel_at: ${userCancelAtMs}ms")
    }
    appendLine("  deadline: ${if (deadlineMs > 1_000_000_000) "far future" else "${deadlineMs}ms"}")
    appendLine("  expect:")
    appendLine("    terminal: ${expect.terminal}")
    appendLine("    provider_attempts: ${expect.providerAttempts}")
    appendLine("    tool_executions: ${expect.toolExecutions}")
    appendLine("    duplicate_side_effects: ${expect.duplicateSideEffects}")
    appendLine("    budget: ${expect.budget.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
    appendLine("    leases_released: ${expect.leasesReleased}")
    appendLine("    trace_terminal_events: ${expect.traceTerminalEvents}")
    appendLine("    persistence: ${expect.persistence}")
    appendLine("    recoverable: ${expect.recoverable}")
}