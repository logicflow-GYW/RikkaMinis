package com.rikkaminis.app.agent.runtime

/**
 * T8 — Interrupted / OutcomeUnknown 恢复语义（纯 JVM 决策引擎）。
 *
 * 决定进程死亡、shell 死亡、provider 断流、持久化失败后的恢复边界。
 * 不依赖 T7 adapter 或 reducer 修改——只使用已有的终端状态和证据。
 *
 * 本引擎只做**安全判定**，不执行实际恢复操作。T7 adapter 根据 [RecoveryOutcome]：
 * - [SafeToResume] → 分配新 budget，启动 recovery run（新 runId，引用原 runId）
 * - [RequiresVerification] → 调用外部状态检查（如 shell 状态、文件系统），再决定
 * - [DoNotResume] → 展示 interrupted run，不自动恢复（副作用已确认）
 * - [ReportInterrupted] → 展示原始 interrupted 状态，不执行任何恢复动作
 *
 * 恢复规则（蓝图 §T8）：
 * 1. 进程死亡时，未完成 Run 不能在重启后默认为成功——必须经过 recovery decision。
 * 2. 能确定没有副作用的操作可以安全恢复（SafeToResume）。
 * 3. 结果未知的非幂等工具必须先做状态检查，不能直接重跑（RequiresVerification）。
 * 4. partial assistant output 要有明确的 interrupted 标志（evidence 中的 hasPartialOutputPersisted）。
 * 5. 恢复动作本身消耗新 budget，生成新 run/attempt 记录（T7 adapter 负责）。
 * 6. 恢复失败不能覆盖原始 interrupted 证据（决策不修改原始证据，只读）。
 * 7. 原始消息和 trace 不删除、不静默改写（决策不修改任何持久化数据）。
 */

/**
 * 中断 Run 的已知证据。仅包含可客观观察的事实，不包含推断。
 * 由 T7 adapter 在重启/检测到中断时构建。
 *
 * 字段来源说明：
 * - `terminalReason` + `hasOutcomeUnknownTool` + `persistenceFailed` → 直接从 [AgentRunState] 读取
 * - `toolStartedCount` / `toolFinishedCount` / `providerAttemptStarted` → 从 [AgentRunState] 计数器读取
 * - `hasPartialOutputPersisted` → 由 T7 adapter 从持久化存储判断（是否有 partial output marker）
 */
data class InterruptedRunEvidence(
    /** 原始 run 标识。 */
    val originalRunId: String,
    /** 原始终态原因。 */
    val terminalReason: AgentTerminalReason,
    /** 是否已输出部分 assistant 内容并持久化（有 interrupted marker 而非 completed）。 */
    val hasPartialOutputPersisted: Boolean,
    /** 已开始的工具调用次数（只增）。 */
    val toolStartedCount: Int,
    /** 已结束的工具调用次数。toolStartedCount - toolFinishedCount = 进行中工具数。 */
    val toolFinishedCount: Int,
    /** 是否存在结果未知的工具（OutcomeUnknown）。 */
    val hasOutcomeUnknownTool: Boolean,
    /** 是否发生过必需持久化失败。 */
    val persistenceFailed: Boolean,
    /** 是否发起了 provider attempt。 */
    val providerAttemptStarted: Boolean,
) {
    /** 是否有进行中的工具调用（发起了但未完成）。 */
    val hasOutstandingToolCalls: Boolean get() = toolStartedCount > toolFinishedCount

    /** 是否没有任何工具调用（既未发起也未完成）。 */
    val hasNoToolActivity: Boolean get() = toolStartedCount == 0 && toolFinishedCount == 0
}

/**
 * 恢复决策结果。
 *
 * 由 [AgentRecoveryPolicy.decide] 根据 [InterruptedRunEvidence] 计算得出。
 * 安全等级从高到低：SafeToResume > RequiresVerification > DoNotResume > ReportInterrupted。
 */
sealed class RecoveryOutcome {
    /**
     * 安全恢复。证据表明：
     * - 无工具调用，或工具调用全部已知结果；
     * - 无持久化失败；
     * - 可安全分配新 budget 重新执行。
     *
     * T7 应：分配新 budget，创建新 runId，以 recovery run 重新执行；
     * 若 [hasPartialOutput] 为 true，新 run 从续写位置开始（retry/continuation）。
     */
    data class SafeToResume(
        val reason: String,
        val hasPartialOutput: Boolean,
    ) : RecoveryOutcome()

    /**
     * 需要外部状态检查。证据表明副作用可能已发生，但无法从纯证据推断。
     *
     * `checkKey` 描述需检查什么（如 "shell_status"、"tool_state"、"provider_state"）；
     * T7 应根据 checkKey 执行外部检查，再根据结果决定是否恢复。
     */
    data class RequiresVerification(
        val checkKey: String,
        val description: String,
    ) : RecoveryOutcome()

    /**
     * 不应自动恢复。证据表明副作用已发生（如持久化确认了非幂等操作的结果）
     * 或恢复风险过高。需要用户介入决定是否重新执行。
     */
    data class DoNotResume(
        val reason: String,
    ) : RecoveryOutcome()

    /**
     * 无法证明安全。展示 interrupted 状态，不执行任何恢复动作。
     * 适用于：持久化失败导致无法确定任何状态、预算耗尽、或证据不足。
     * 用户看到 interrupted run 后可手动决定是否重新发送消息。
     */
    data object ReportInterrupted : RecoveryOutcome()
}

/**
 * 恢复 Run 引用。记录 recovery run 与原始 interrupted run 的关系。
 * 由 T7 adapter 在启动 recovery run 时创建，供 trace、UI 和重复恢复检测使用。
 */
data class RecoveryRunReference(
    /** 原始中断 run 的标识。 */
    val originalRunId: String,
    /** 恢复 run 的新标识。 */
    val recoveryRunId: String,
    /** 原始终态原因。 */
    val originalTerminalReason: AgentTerminalReason,
    /** 恢复决策结果。 */
    val outcome: RecoveryOutcome,
)

/**
 * 恢复决策引擎。纯函数：相同的 [InterruptedRunEvidence] 恒返回相同的 [RecoveryOutcome]。
 *
 * 决策规则按优先级排列（从高到低匹配，首个匹配即返回）：
 *
 * 1. `persistenceFailed` → **ReportInterrupted**
 *    持久化失败意味着任何证据（partial output、tool results）都不可信。
 *    无法证明任何状态，只能展示 interrupted。
 *
 * 2. `hasOutcomeUnknownTool` + `hasOutstandingToolCalls` → **RequiresVerification(shell_status)**
 *    工具副作用可能已发生（shell 死亡前执行了命令），需要检查 shell 状态。
 *    不能透明重跑——结果未知的非幂等工具必须先做状态检查（蓝图 §T8 规则 3）。
 *
 * 3. `hasOutcomeUnknownTool` → **RequiresVerification(tool_state)**
 *    工具已完成但结果未知。需要检查工具的目标状态（文件存在？写入成功？）。
 *
 * 4. `hasOutstandingToolCalls` → **RequiresVerification(tool_status)**
 *    工具已发起但未完成（且结果已知——否则已归入规则 2）。
 *    需要检查工具的执行状态。
 *
 * 5. `toolFinishedCount > 0` → **SafeToResume**
 *    有工具调用且全部已知结果。副作用已记账，可安全恢复。
 *    若 [hasPartialOutputPersisted] 则从续写位置开始。
 *
 * 6. `!providerAttemptStarted` → **SafeToResume(fresh)**
 *    未发起任何 provider 调用。没有任何操作发生过，可安全全新执行
 *    （蓝图 §T8 规则 2：能确定没有副作用的操作可以安全恢复）。
 *
 * 7. `hasPartialOutputPersisted` → **SafeToResume(continuation)**
 *    输出了部分 assistant 内容但无工具调用。无副作用，可安全续写
 *    （蓝图 §T8 规则 4：partial output 要有明确的 interrupted 标志）。
 *
 * 8. 默认 → **RequiresVerification(provider_state)**
 *    provider 调用已发起但无任何可观察结果。需要检查 provider 端状态。
 */
object AgentRecoveryPolicy {

    /**
     * 根据中断证据做出恢复决策。
     * 纯函数：相同的 `evidence` 恒返回相同的 `RecoveryOutcome`。
     */
    fun decide(evidence: InterruptedRunEvidence): RecoveryOutcome {
        // 规则 1: 持久化失败 → 证据不可信
        if (evidence.persistenceFailed) {
            return RecoveryOutcome.ReportInterrupted
        }

        // 规则 2: 有 OutcomeUnknown 工具 + 有进行中工具
        if (evidence.hasOutcomeUnknownTool && evidence.hasOutstandingToolCalls) {
            return RecoveryOutcome.RequiresVerification(
                checkKey = "shell_status",
                description = "OutcomeUnknown tool(s) in progress: " +
                    "toolStarted=${evidence.toolStartedCount}, " +
                    "toolFinished=${evidence.toolFinishedCount}. " +
                    "Side effects may have occurred before shell death. " +
                    "Check whether commands actually executed before resuming."
            )
        }

        // 规则 3: 有 OutcomeUnknown 工具（无进行中）
        if (evidence.hasOutcomeUnknownTool) {
            return RecoveryOutcome.RequiresVerification(
                checkKey = "tool_state",
                description = "OutcomeUnknown tool(s) completed but result unknown. " +
                    "Verify tool target state (e.g. file existence, process status) " +
                    "before resuming to avoid duplicate side effects."
            )
        }

        // 规则 4: 有进行中工具（无 OutcomeUnknown）
        if (evidence.hasOutstandingToolCalls) {
            return RecoveryOutcome.RequiresVerification(
                checkKey = "tool_status",
                description = "Tool(s) in progress (${evidence.toolStartedCount - evidence.toolFinishedCount} " +
                    "outstanding, results known). Verify tool execution status before resuming."
            )
        }

        // 规则 5: 有工具调用且全部已知结果
        if (evidence.toolFinishedCount > 0) {
            return RecoveryOutcome.SafeToResume(
                reason = "All ${evidence.toolFinishedCount} tool(s) completed with known results. " +
                    "Side effects are accounted for.",
                hasPartialOutput = evidence.hasPartialOutputPersisted,
            )
        }

        // 规则 6: 无工具活动 + 无 provider 调用
        if (!evidence.providerAttemptStarted) {
            return RecoveryOutcome.SafeToResume(
                reason = "No provider attempt was started. No side effects possible. " +
                    "Safe to re-execute as a fresh run.",
                hasPartialOutput = false,
            )
        }

        // 规则 7: 无工具活动 + 有 partial output
        if (evidence.hasPartialOutputPersisted) {
            return RecoveryOutcome.SafeToResume(
                reason = "Partial output persisted but no tool activity. " +
                    "Safe to resume with continuation from the interrupted position.",
                hasPartialOutput = true,
            )
        }

        // 规则 8: 默认 — provider 调用已发起但无任何可观察结果
        return RecoveryOutcome.RequiresVerification(
            checkKey = "provider_state",
            description = "Provider attempt started (${evidence.terminalReason}) " +
                "but no partial output persisted and no tool activity. " +
                "Provider response state is unknown."
        )
    }

    /**
     * 判断 recovery run 引用是否有效。
     *
     * 防止对同一 original run 的重复恢复（蓝图 §T8 测试矩阵：
     * "repeated resume does not duplicate side effect"）。
     *
     * 纯函数，不依赖外部状态。
     *
     * @param existingRecoveries 已存在的 recovery run 引用列表。
     *   T7 adapter 负责维护此列表（从 trace 或持久化存储读取）。
     * @param newRecovery 新 recovery run 引用。
     * @return false 表示该 original run 已被恢复过，不允许重复恢复。
     */
    fun isRecoveryValid(
        existingRecoveries: Collection<RecoveryRunReference>,
        newRecovery: RecoveryRunReference,
    ): Boolean {
        // 检查 same originalRunId 是否已被恢复
        val alreadyRecovered = existingRecoveries.any { it.originalRunId == newRecovery.originalRunId }
        return !alreadyRecovered
    }
}