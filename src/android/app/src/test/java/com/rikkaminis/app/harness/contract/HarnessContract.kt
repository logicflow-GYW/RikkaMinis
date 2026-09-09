package com.rikkaminis.app.harness.contract

/**
 * T4-A: 故障注入 Harness 的核心契约模型。
 *
 * 这些类型是 test-only 的，镜像蓝图 §4.1 四终态、§4.3 预算字段、§4.4 副作用等级。
 * 生产类型由 T2/T5 定义；T4-B 负责映射到 T7 的 adapter。
 */

/** 四终态（蓝图 §4.1）。 */
enum class TerminalState { SUCCEEDED, FAILED, CANCELLED, INTERRUPTED }

/** 五类违反（蓝图 T4 验收条件）。 */
enum class ViolationCategory { TERMINAL_STATE, BUDGET, RESOURCE, PERSISTENCE, SIDE_EFFECT }

/** 副作用等级（蓝图 §4.4）。 */
enum class SideEffectLevel { READ_ONLY, IDEMPOTENT_WRITE, NON_IDEMPOTENT_WRITE, UNKNOWN }

/** 持久化标记。 */
enum class PersistenceMark { COMPLETED, PARTIAL, NONE, FAILED }

/** 预算快照，镜像蓝图 §4.3 字段（T2 拥有生产类型）。 */
data class BudgetSnapshot(
    val turnsUsed: Int,
    val maxTurns: Int,
    val providerAttemptsUsed: Int,
    val maxProviderAttempts: Int,
    val toolCallsUsed: Int,
    val maxToolCalls: Int,
    val shellCommandsUsed: Int,
    val maxShellCommands: Int,
    val compactionCallsUsed: Int,
    val maxCompactionCalls: Int,
    val expired: Boolean,
) {
    companion object {
        val DEFAULT_MAX = 100
    }
}

/** 一条违反记录。 */
data class HarnessViolation(val category: ViolationCategory, val message: String)

/** Trace 事件类型（镜像 T5 推荐事件名）。 */
enum class TraceEventType {
    RUN_START,
    PROVIDER_ATTEMPT_STARTED,
    PROVIDER_ATTEMPT_FINISHED,
    RETRY_REQUESTED,
    FALLBACK_SELECTED,
    TOOL_STARTED,
    TOOL_FINISHED,
    COMPACT_STARTED,
    COMPACT_FINISHED,
    USER_CANCELLED,
    DEADLINE_REACHED,
    PROCESS_INTERRUPTED,
    PERSISTENCE_FAILED,
    RUN_FINALIZED,
    SPAWN_REJECTED,
}

/** 一条结构化 trace 事件。 */
data class HarnessTraceEvent(
    val type: TraceEventType,
    val atMs: Long,
    val detail: String? = null,
)

/** 完整的场景运行报告。 */
data class ScenarioReport(
    val terminal: TerminalState,
    val providerAttempts: Int,
    val toolExecutions: Int = 0,
    val duplicateSideEffects: Int,
    val budgetSnapshot: BudgetSnapshot,
    val leaseCount: Int,
    val traceTerminalEvents: Int,
    val persistenceMark: PersistenceMark,
    val recoverable: Boolean,
    val cooldownRecords: List<CooldownRecord> = emptyList(),
    val providerCancellations: Int = 0,
    val toolCancellations: Int = 0,
    val spawnRejected: Int = 0,
    val compactCalls: Int = 0,
    val historyIntact: Boolean = true,
    val traceEvents: List<HarnessTraceEvent> = emptyList(),
    val violations: List<HarnessViolation> = emptyList(),
)

/** 冷却记录（429 后）。 */
data class CooldownRecord(
    val providerId: String,
    val cooldownUntilMs: Long,
)