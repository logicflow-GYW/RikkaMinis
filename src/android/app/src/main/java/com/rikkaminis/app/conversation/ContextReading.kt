package com.rikkaminis.app.conversation


/**
 * [fix/prewindow-budget-1015] 上下文读数 —— 带"这个数字从哪来"的标注。
 *
 * 取代散落各处的裸 lastContextTokens 语义：引擎快照、VM 快照、换模型后归零
 * 三本账漂移是 2026-10-15 T321 事故的直接推手。读数来源分三档：
 *
 *  - [Source.PROVIDER_USAGE]     provider 本 turn 上报（口径 = 该 provider）
 *  - [Source.CALIBRATED_ESTIMATE] 本地估算 × 校准系数（跨 provider 可比）
 *  - [Source.RAW_ESTIMATE]       未校准的本地估算（系数 1.0，保守）
 *
 * 决策消费方（预检 / dynamicMaxTokens / compact 决策）拿同一份
 * [ContextReading]，不再各自推断。
 */
data class ContextReading(
    val tokens: Long,
    val source: Source,
    /** provider/estimate 比值快照；未校准为 null。面板与诊断用。 */
    val calibrationRatio: Double? = null,
) {
    enum class Source { PROVIDER_USAGE, CALIBRATED_ESTIMATE, RAW_ESTIMATE }

    companion object {
        fun providerUsage(tokens: Long) = ContextReading(tokens, Source.PROVIDER_USAGE)
        fun rawEstimate(tokens: Long) = ContextReading(tokens, Source.RAW_ESTIMATE)
        fun calibrated(tokens: Long, ratio: Double) =
            ContextReading(tokens, Source.CALIBRATED_ESTIMATE, ratio)
    }
}

/**
 * [fix/prewindow-budget-1015] 发送前预检 —— 不依赖任何 provider 上报的
 * 硬性结构检查，任何请求发出之前必须通过：
 *
 *   estimateRequestTokens + max_tokens + margin ≤ window
 *
 * 2026-10-15 T321 现场形态：provider_usage 记账 191.9K（deepseek 口径），
 * 换 GLM 直连后真实输入 920.6K + max_tokens 顶格 128K = 1,048,577，恰好
 * 超 1M 硬窗口 1 个 token。P0 预检用与 provider 无关的本地估算直接拦下
 * 这一类必然 400，同时给出"超了多少"的 agent 可读诊断。
 *
 * 决策语义：
 *  - PASS                → 照常发送
 *  - SHRINK_OUTPUT       → 输入能装下，但装不下请求的输出预算 → 收缩 max_tokens
 *  - NEEDS_COMPACTION    → 输入本身就装不下 → 先走压缩/offload，禁止直发
 *
 * @param window            有效上下文窗口（effectiveContextWindowTokens）
 * @param estimatedInput    本地估算的请求输入（ContextEstimator 口径）
 * @param requestedMaxTokens 本 turn 拟发送的输出预算
 */
fun preflightContextBudget(
    window: Int,
    estimatedInput: Long,
    requestedMaxTokens: Int,
    outputFloor: Int,
): PreflightVerdict {
    if (window <= 0) return PreflightVerdict.pass(estimatedInput, requestedMaxTokens)
    val margin = ContextEstimator.PREFLIGHT_MARGIN_TOKENS
    val total = estimatedInput + requestedMaxTokens + margin
    if (total <= window) return PreflightVerdict.pass(estimatedInput, requestedMaxTokens)

    // 输出预算可收缩的空间：保住最小输出试一次，不行再谈压缩。
    val roomForOutput = window - estimatedInput - margin
    if (roomForOutput >= outputFloor && requestedMaxTokens > outputFloor) {
        return PreflightVerdict.shrinkOutput(estimatedInput, requestedMaxTokens, roomForOutput.toInt())
    }
    return PreflightVerdict.needsCompaction(estimatedInput, requestedMaxTokens, window)
}

/** 预检裁决（见 [preflightContextBudget]）。 */
data class PreflightVerdict(
    val decision: Decision,
    val estimatedInput: Long,
    val requestedMaxTokens: Int,
    /** SHRINK_OUTPUT 时的建议输出预算；其余 null。 */
    val suggestedMaxTokens: Int? = null,
    /** NEEDS_COMPACTION 时的窗口读数（诊断文案用）。 */
    val windowTokens: Int? = null,
) {
    enum class Decision { PASS, SHRINK_OUTPUT, NEEDS_COMPACTION }

    companion object {
        internal fun pass(input: Long, max: Int) = PreflightVerdict(Decision.PASS, input, max)
        internal fun shrinkOutput(input: Long, requested: Int, suggested: Int) =
            PreflightVerdict(Decision.SHRINK_OUTPUT, input, requested, suggestedMaxTokens = suggested)
        internal fun needsCompaction(input: Long, requested: Int, window: Int) =
            PreflightVerdict(Decision.NEEDS_COMPACTION, input, requested, windowTokens = window)
    }

    /** 单行 agent 可读诊断（进 [com.rikkaminis.app.logging.AppLogger]）。 */
    fun toLogLine(): String = when (decision) {
        Decision.PASS ->
            "est=$estimatedInput max=$requestedMaxTokens → pass"
        Decision.SHRINK_OUTPUT ->
            "est=$estimatedInput max=$requestedMaxTokens → shrink-output to $suggestedMaxTokens"
        Decision.NEEDS_COMPACTION ->
            "est=$estimatedInput max=$requestedMaxTokens window=$windowTokens → needs-compaction (provider-bypass preflight)"
    }
}
