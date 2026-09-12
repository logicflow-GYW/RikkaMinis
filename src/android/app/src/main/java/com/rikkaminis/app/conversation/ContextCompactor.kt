package com.rikkaminis.app.conversation

import com.rikkaminis.app.data.ContextPolicy
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage

/**
 * 上下文压缩引擎 — 自动触发决策（对照 OmniBot AgentConversationContextCompactor）。
 *
 * RikkaMinis 的压缩*执行*本就存在：`compactAll` + `generateCompactSummaryWithSplitting`
 * + `effectiveAgentHistory`（手动 /compact 触发，summary/range 持久化到
 * compact_markers 表）。本对象补齐 OmniBot 的“自动”环节，让压缩在上下文接近
 * 窗口阈值时自动发生，而不只是警告/阻断后等用户手动 /compact：
 *
 *  - [decide]：发送前判断“是否该自动压缩”——纯逻辑、无 Android 依赖、可单测。
 *    ContextPolicy 回答“上下文在哪个压力档位”，本函数叠加会话级状态
 *    （是否已在压缩、距上次自动压缩的间隔、上次压缩后新增了多少内容）回答
 *    “现在该不该自动压缩”。
 *  - [COMPACT_SUMMARY_SYSTEM_PROMPT]：MUST PRESERVE 提示词的单一事实源。
 *    ChatViewModel.compactSummarySystemPrompt 委托到这里，保证“路径/URL/UUID
 *    原文保留”指令既被单测锁定、又不会与执行路径各处漂移。
 *  - [estimateTailTokens]：估算最近一次压缩锚点之后新增内容的大小，用于防
 *    “刚压缩过又压缩”（冷启动恢复后 marker 持久化，尾部估算自然兜住重复触发）。
 *
 * 职责边界：本对象不做任何 IO/流式/UI —— 决策和估算而已，执行仍在
 * ChatViewModel 既有 compact 管线。
 */
object ContextCompactor {

    /** 两次自动压缩的最小间隔：防同一会话高频压缩风暴。 */
    // [feat/chat-tuning-panel-b] Defaults below remain the fallback when a
    // caller doesn't pass the knobs explicitly; ChatContextWindowExt now
    // supplies the user-tunable values from AgentRuntimeLimitsPrefs.
    // Keep these literals in sync with the Prefs defaults (8000 / 5 min).
    const val DEFAULT_AUTO_COMPACT_MIN_INTERVAL_MS = 5 * 60 * 1000L

    /**
     * 压缩尾部最小 token 量。低于此值说明上次压缩后没长出多少新内容，
     * 压缩收益太小（甚至把刚压缩完的状态再压一遍），跳过。
     */
    const val DEFAULT_AUTO_COMPACT_MIN_TAIL_TOKENS = 8_000L

    /** 简易 token 估算（chars/4），与 generateCompactSummary 的估算口径对齐。 */
    const val CHARS_PER_TOKEN = 4

    /** 发送协程等待自动压缩完成时的轮询间隔。 */
    const val AUTO_COMPACT_POLL_MS = 200L

    /**
     * 等待自动压缩完成的上限。压缩是独立 LLM 调用、可能很慢；超时则放弃
     * 等待、按未压缩的上下文直接发送（provider 侧的 too-large 错误仍由
     * 既有重试/拆分机制兜底）。
     */
    const val AUTO_COMPACT_MAX_WAIT_MS = 120_000L

    enum class Decision {
        /** 未达压缩线，无需动作。 */
        OK,

        /** 应自动压缩：达到 ContextPolicy.compactThreshold、未到硬上限、防抖与尾部收益检查均通过。 */
        AUTO_COMPACT,

        /** 已有压缩在途（_isCompacting）——让当前压缩完成即可，不重复触发。 */
        COMPACT_IN_FLIGHT,

        /** 距上次自动压缩太近（< [DEFAULT_AUTO_COMPACT_MIN_INTERVAL_MS]），跳过。 */
        RECENT_AUTO_COMPACT,

        /** 最近一次压缩之后新增内容太少，压缩无收益。 */
        TAIL_TOO_SMALL,

        /**
         * 已到/超过硬窗口上限。自动压缩在这种状态下不应动手——EXHAUSTED 的
         * 阻断与手动处理在发送入口（checkContextBeforeSend）已存在。这里兜底，
         * 防止任何绕过滤音路径误触发自动压缩。
         */
        EXHAUSTED,
    }

    /**
     * 判断是否应在本次发送前自动压缩旧上下文。
     *
     * @param estimatedTokens 当前上下文估算 token 数（上一回合 API usage 或估算值）
     * @param contextWindow 模型/组有效上下文窗口
     * @param policy 该窗口对应的 [ContextPolicy]
     * @param tailTokens 最近一次压缩锚点之后新增内容的估算 token 数（无锚点=整个历史）
     * @param isCompacting 是否已有压缩在途
     * @param lastAutoCompactAtMs 上次自动压缩时间戳（毫秒），Long.MIN_VALUE=从未
     * @param nowMs 当前时间戳（可注入便于测试）
     * @param minIntervalMs 自动压缩最小间隔
     * @param minTailTokens 尾部最小 token 量
     */
    fun decide(
        estimatedTokens: Int,
        contextWindow: Int,
        policy: ContextPolicy,
        tailTokens: Long,
        isCompacting: Boolean,
        lastAutoCompactAtMs: Long = Long.MIN_VALUE,
        nowMs: Long = System.currentTimeMillis(),
        minIntervalMs: Long = DEFAULT_AUTO_COMPACT_MIN_INTERVAL_MS,
        minTailTokens: Long = DEFAULT_AUTO_COMPACT_MIN_TAIL_TOKENS,
    ): Decision {
        if (isCompacting) return Decision.COMPACT_IN_FLIGHT
        // 无估算值或窗口未知时无从判断——视为 OK（发送入口不因此被自动压缩阻塞）。
        if (estimatedTokens <= 0 || contextWindow <= 0) return Decision.OK
        // 硬上限兜底：此刻 sendMessage 入口的 checkContextBeforeSend 已阻断发送，
        // 自动压缩不该在这种状态下动手（需要用户显式 /compact 或新会话）。
        if (estimatedTokens >= contextWindow) return Decision.EXHAUSTED
        // 单一事实源：压缩线只由 ContextPolicy 定义。
        if (policy.check(estimatedTokens, contextWindow) != ContextPolicy.CheckResult.NEEDS_COMPACT) {
            return Decision.OK
        }
        // Guard against Long underflow: `lastAutoCompactAtMs` is
        // Long.MIN_VALUE when never auto-compacted, so `nowMs - last` would
        // wrap negative and spuriously look like "just compacted". Only apply
        // the debounce once we have a real timestamp.
        if (lastAutoCompactAtMs > 0 && nowMs - lastAutoCompactAtMs < minIntervalMs) {
            return Decision.RECENT_AUTO_COMPACT
        }
        if (tailTokens < minTailTokens) return Decision.TAIL_TOO_SMALL
        return Decision.AUTO_COMPACT
    }

    /** 简易 token 估算：每 [CHARS_PER_TOKEN] 字符 ≈ 1 token。 */
    fun estimateTokens(text: String): Long = (text.length / CHARS_PER_TOKEN).toLong()

    /**
     * 估算一条消息完整贡献的 token 数：content 文本 + contentParts 里的
     * Text / ToolResult.content / ToolUse.input。工具结果在 agent 会话里通常
     * 占大头，必须计入，否则尾部估算严重低估 → 刚压缩过又压缩。
     */
    fun estimateMessageTokens(message: LLMMessage): Long {
        var total = estimateTokens(message.content)
        message.contentParts.forEach { part ->
            when (part) {
                is AgentContentPart.Text -> total += estimateTokens(part.text)
                is AgentContentPart.ToolResult -> total += estimateTokens(part.content)
                is AgentContentPart.ToolUse -> total += estimateTokens(part.input.toString())
                is AgentContentPart.ImageData -> Unit // 字节数据不计入文本 token 估算
            }
        }
        return total
    }

    /**
     * 最近一次压缩锚点（marker.lastCompactedMessageId）之后内容的总体积估算。
     * 锚点为空 = 从未压缩 → 返回全量。锚点不在当前历史（冷启动恢复竞态等）
     * → 退化为全量，与 effectiveAgentHistory 的“锚点不可解析 → 全量”策略一致。
     */
    fun estimateTailTokens(messages: List<LLMMessage>, anchorDbId: String?): Long {
        if (anchorDbId.isNullOrEmpty()) {
            return messages.sumOf { estimateMessageTokens(it) }
        }
        val anchorIdx = messages.indexOfLast { it.dbMessageId == anchorDbId }
        if (anchorIdx < 0) {
            return messages.sumOf { estimateMessageTokens(it) }
        }
        return messages.drop(anchorIdx + 1).sumOf { estimateMessageTokens(it) }
    }

    /**
     * MUST PRESERVE 提示词（单一事实源）。
     *
     * 从 ChatViewModel.compactSummarySystemPrompt 原样提取，行为零变化；提到
     * 这里是为了：(1) 单测锁定“路径/URL/UUID 原文保留”指令存在（T5 验收 4）；
     * (2) 自动/手动压缩共用同一提示词，不漂移。
     */
    val COMPACT_SUMMARY_SYSTEM_PROMPT: String = """
        You are a context compaction engine. Your summary will REPLACE the original messages in the conversation context window. The agent will read your summary as past context, then proceed based on the user's NEXT message — your summary is background, not a standing work order. Write the summary in the same language the user used in the conversation.

        MUST PRESERVE (never omit or shorten):
        - All file paths, directory names, URLs, UUIDs, and identifiers — copy verbatim
        - Commands executed and their outcomes (success/failure/output)
        - What was requested and what was done (record as past events, not as ongoing goals)
        - Key decisions made and their rationale
        - Errors encountered and how they were resolved
        - Important constraints, rules, or user preferences mentioned
        - Any tool calls and their results that affect current state

        STRUCTURE:
        1. Start with a one-line description of what the conversation was about (use past tense — "User asked X, agent did Y", NOT "Goal: X").
        2. Then a concise narrative of what happened, preserving technical details.
        3. End with a "What had been done so far" section listing completed work — NOT a "todo" or "pending" list. Do not invent ongoing objectives or carry-over tasks from old turns; if the user wants to continue, they will say so in their next message.

        PRIORITIZE recent context over older history — recent decisions and recent file/path references are most useful for continuity.

        Do NOT translate or alter code snippets, file paths, identifiers, or error messages. Be concise but never lose information the agent needs.
    """.trimIndent()
}