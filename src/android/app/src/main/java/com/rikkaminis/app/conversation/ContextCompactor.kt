package com.rikkaminis.app.conversation

import com.rikkaminis.app.data.ContextPolicy
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
         *
         * [fix/context-exhausted-loop] 上面那句原先只对发送入口成立：agent loop
         * 内部的 `continue` 重试不经过 checkContextBeforeSend，所以
         * 「skipped: EXHAUSTED」之后照样发出请求（2026-09-20 生产日志连续三次）。
         * 现在 loop 侧也读同一个裁决（[AgentLoopHost.isContextExhausted]，与
         * checkContextBeforeSend 共享 ChatViewModel.contextPressure 实现），
         * 该声明对两个落点都为真。
         *
         * [fix/compact-exhausted-rescue-1005]（2026-10-05 拍板）「不应动手」收敛为
         * 「一次救援后停摆」：EXHAUSTED 且 [rescueAttempted]=false → [RESCUE]；
         * true → 停摆兜底。动机保留（防空重试墙，见下），但「零压缩」的代价是
         * summary 冻结、postAnchor 无节制增长（2026-10-04 实测 44–56 万 tokens
         * vs 25 万窗口停摆 77+ 分钟零压缩；2026-10-05 又现 7 次/4 小时）。
         */
        EXHAUSTED,

        /**
         * [fix/compact-exhausted-rescue-1005] EXHAUSTED 状态下的**一次性**救援压缩：
         * EXHAUSTED 且 [rescueAttempted]=false → RESCUE；true → 停摆。防抖与尾部
         * 闸门不适用于救援（早退见 [decide]）——防风暴由一次性门本身承担。
         * 复位是用户动作（新 user 消息发出 / 手动 /compact / 会话打开），由调用方
         * 持有状态、在用户动作处清零——[decide] 保持纯函数，不做任何 IO 或时钟推断。
         */
        RESCUE,
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
     * @param rescueAttempted [fix/compact-exhausted-rescue-1005] EXHAUSTED 状态下的
     *   救援门：false=尚未救援过（→ [Decision.RESCUE]），true=已救援过（→ 停摆
     *   [Decision.EXHAUSTED]）。缺省 **true** = 不接管防风暴门：未改造的调用点
     *   保持「EXHAUSTED 一律跳过」的既有行为。由调用方持有并在用户动作
     *   （新 user 消息发出 / 手动 /compact / 会话打开）处清零。
     * @param escalatedFromOffload 是否因 offload 欠交付而要求升级（唯一允许低于
     *   压缩线触发的例外）
     */
    /**
     * [fix/compact-pregate-exhausted-1016] 循环路径的前置门（纯判定，可单测）：
     * 本轮压力档位 + offload 欠交付标志，是否允许调用方走到 [decide]。
     *
     * 这道门唯一的用途是把 [decide] 里 `estimateTailTokens` 那次 O(N) 历史遍历
     * 从「无压力的常见回合」上摘掉，所以它必须对**所有 decide 会动手的档位**开口：
     *  - `NEEDS_COMPACT` —— 压缩带内（1016 之前的唯一形态）；
     *  - `EXHAUSTED` —— 天花板 / 小窗档 exhaust 线以上，正是 [decide] 回答
     *    RESCUE（每用户动作周期一次）或 EXHAUSTED 停摆兜底的入口；
     *  - `OK` 且 escalated —— [T-ctx-offload-escalation] 的压缩线以下例外。
     *
     * 开口不等于压缩：[decide] 自己重读 [ContextPolicy.check]，防抖/尾门/档位
     * 契约全部照旧，因此放宽前置门不可能造出 decide 会拒绝的压缩。
     *
     * 发送入口（`maybeTriggerAutoCompact`）**刻意不走这道门**：那里的 EXHAUSTED
     * 已由 checkContextBeforeSend 拦下发送，恢复动作归对话框（新会话 / 显式
     * /compact），RESCUE 是长跑任务中途的可选项，不是发送前的可选项。
     */
    fun loopGateOpens(
        verdict: ContextPolicy.CheckResult,
        escalatedFromOffload: Boolean,
    ): Boolean =
        verdict == ContextPolicy.CheckResult.NEEDS_COMPACT ||
            verdict == ContextPolicy.CheckResult.EXHAUSTED ||
            escalatedFromOffload

    /**
     * [fix/compact-lastfailure-pin-1016] 候选链失败的**单步归约**（纯函数，可单测）。
     *
     * 链上的失败分两类：ContextTooLarge 分类的（唯一能让外层 catch 走二分拆解的
     * 容量证据）与普通的（超时 / 5xx / unavailable / truncated）。归约规则：
     *  - TooLarge → 钉住**第一个**出现的（容量证据的时序以首次为准，后续 TooLarge
     *    不覆盖）；
     *  - 普通失败 → 覆盖「最后一个普通失败」槽位（保持既有语义：链尾失败即终态）。
     *
     * 调用方在链尾用 [chainTerminalFailure] 取终态。把规则放在这里而不是散在
     * 循环里，是因为「哪条失败被抛出去」决定了外层要不要二分拆解，必须单源且
     * 可被 JVM 装置按失败序列直接验。
     *
     * @return (新的 lastOrdinary, 新的 pinnedTooLarge)
     */
    fun chainFailureAfter(
        lastOrdinary: Exception,
        pinnedTooLarge: Exception?,
        candidate: Exception,
        isTooLarge: (Throwable) -> Boolean,
    ): Pair<Exception, Exception?> =
        if (isTooLarge(candidate)) lastOrdinary to (pinnedTooLarge ?: candidate)
        else candidate to pinnedTooLarge

    /**
     * [fix/compact-lastfailure-pin-1016] 链全败时的**终态选择**（纯函数）：
     * 钉住的 TooLarge 优先于最后一个普通失败。
     *
     * 外层 catch 只按 `isContextTooLargeError` 判断要不要二分拆解；终态若被链尾
     * 的普通失败（超时 / 5xx）冲掉，链上唯一的容量证据就丢了 —— 不拆解、不重试，
     * 整条链以失败收场（真实日志：`[Compact] Splitting` 3 天 0 次触发）。
     */
    fun chainTerminalFailure(lastOrdinary: Exception, pinnedTooLarge: Exception?): Exception =
        pinnedTooLarge ?: lastOrdinary

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
        // [fix/compact-exhausted-rescue-1005] EXHAUSTED-state rescue gate, owned
        // by the caller (shape mirrors lastAutoCompactAtMs). Defaults to TRUE so
        // call sites that don't opt in keep the legacy "always skip at the hard
        // ceiling" behaviour — the gate only opens for callers that hold the
        // flag and reset it on user actions.
        rescueAttempted: Boolean = true,
        // [T-ctx-offload-escalation] True when the caller knows this turn's
        // offload pass under-delivered and asks to compact below the compact
        // line. Only bypasses the policy band; the debounce and tail gates
        // below still apply, and [Decision.EXHAUSTED] still wins.
        escalatedFromOffload: Boolean = false,
    ): Decision {
        if (isCompacting) return Decision.COMPACT_IN_FLIGHT
        // 无估算值或窗口未知时无从判断——视为 OK（发送入口不因此被自动压缩阻塞）。
        if (estimatedTokens <= 0 || contextWindow <= 0) return Decision.OK
        // 硬上限兜底：此刻 sendMessage 入口的 checkContextBeforeSend 已阻断发送，
        // 自动压缩不该在这种状态下动手（需要用户显式 /compact 或新会话）。
        // [fix/context-exhausted-loop] 「已阻断」现在对 agent loop 的重试路径
        // 也成立——见 Decision.EXHAUSTED 的 KDoc。
        // [fix/compact-exhausted-rescue-1005] 2026-10-05 收敛：允许**一次**救援压缩，
        // 否则长任务里压缩永久停摆（summary 冻结、postAnchor 无节制增长）。
        // 防风暴门 = rescueAttempted（调用方持有，用户动作复位）：未救援过 →
        // RESCUE；已救援过 → 停摆兜底，救援失败不重试风暴。RESCUE 不下穿
        // 防抖/尾部两道闸门：它们为「压缩线以下的常规自动压缩」设计（防刚压
        // 完又压），而救援唯一一次、且本来就受门约束——再叠闸门只会把唯一的
        // 机会吃掉（2026-10-05 实测 7 次 EXHAUSTED 的 tail 8632–17637 全部高于
        // 尾门，字段行为不受该取舍影响）。
        if (estimatedTokens >= contextWindow) {
            return if (!rescueAttempted) Decision.RESCUE else Decision.EXHAUSTED
        }
        // 单一事实源：压缩线只由 ContextPolicy 定义。
        // [T-ctx-offload-escalation] 唯一例外：这一轮的 offload 已证明削不动
        // （候选池耗尽，缺口是 offload 结构上够不到的对话文本），调用方显式
        // 要求升级——否则上下文会卡在 offload 线与压缩线之间的死区里空转，
        // 直到自然涨过压缩线。tail/debounce 闸门仍然生效，升级只放宽这一条。
        // [fix/compact-escalation-tier-contract-1016] 但「只放宽这一条」必须
        // 真的只放宽 band 检查，不能顺手替档位造一条设计上不存在的压缩线：
        // exhaustedOnly 档（compactThreshold = 0，ContextPolicy 文档原文
        // "too small for auto-compact; only surface .exhausted"；本类
        // reservedForGrowth 的小窗豁免用的就是这一对字段）里 check() 永远不会
        // 回答 NEEDS_COMPACT，escalation 若继续放行就等于在小窗档自动压缩——
        // 用真实类量过：window 50 000 / tokens 45 000 / escalated=true ->
        // AUTO_COMPACT，而同一坐标 check()=EXHAUSTED。小窗档仍只允许 EXHAUSTED
        // 语义，恢复动作归对话框（manualCompactAllowed）或新会话。
        val bandReached =
            policy.check(estimatedTokens, contextWindow) == ContextPolicy.CheckResult.NEEDS_COMPACT
        val tierHasNoCompactLine = policy.compactThreshold <= 0 || policy.exhaustedOnly
        if (!bandReached && (!escalatedFromOffload || tierHasNoCompactLine)) {
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

    /**
     * 简易 token 估算：CJK 字符 ≈ 1 token/字，其余每 [CHARS_PER_TOKEN] 字符 ≈ 1 token。
     *
     * [fix/cjk-token-estimate] 为什么分语系：这个估算喂 [estimateTailTokens] 的
     * TAIL_TOO_SMALL 闸门（触发线本身用 provider 上报的真实用量，不走这里）。
     * 旧口径 chars/4 对 CJK 低估 3-4 倍（现代 tokenizer 对汉字/假名/谚文约
     * 1 字 1 token），真实 8000 token 的中文尾部被估成 ~2000 → 永远
     * TAIL_TOO_SMALL → 自动压缩对中文对话实际从未触发过（用户直接看到
     * EXHAUSTED 对话框 / 空响应兜底）。ASCII 行为逐字节不变（既有测试不动）。
     * Emoji 等 astral 区（surrogate pair）按 other 计入，估算语义可接受。
     */
    fun estimateTokens(text: String): Long {
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (isCjkChar(ch)) cjk++ else other++
        }
        return cjk.toLong() + (other / CHARS_PER_TOKEN).toLong()
    }

    /** CJK 表意区（汉字/假名/谚文/全角形式）≈ 1 字 1 token。 */
    private fun isCjkChar(ch: Char): Boolean {
        val c = ch.code
        return c in 0x2E80..0x9FFF ||   // 部首/CJK 标点/假名/注音/CJK 统一表意
            c in 0xAC00..0xD7AF ||      // 谚文音节
            c in 0xF900..0xFAFF ||      // CJK 兼容表意
            c in 0xFF00..0xFFEF         // 全角/半角形式
    }

    /**
     * 估算一条消息完整贡献的 token 数 —— **委托** wire 对齐的权威实现
     * [ContextEstimator.estimateMessageTokensFull]，本函数不再持有自己的公式。
     *
     * [fix/audit2-estimate-1015] 旧实现是 `estimateTokens(content)` 再逐 part 求和
     * —— 对"content 与同文本 Text part 双填"的消息把同一段文本算两遍，而双填是
     * **常态而非边角**：主发送路径的用户消息（`ChatViewModel`：
     * `content = trimmed` + `contentParts = [Text(trimmed), …]`）与每一条
     * assistant 文本轮（`AgentLoopEngine`：`content = turnText` +
     * `assistantParts[0] = Text(turnText)`）都是这个形状。wire 侧在
     * `contentParts` 非空时**只发 parts**（OpenAIProvider 的 structured-parts
     * 分支：两个 role 的 `content` 都由 Text parts 拼出，`msg.content` 本身不发），
     * 所以双计是纯虚高 —— 它同时喂大 [estimateTailTokens] 的 TAIL_TOO_SMALL 闸门
     * 与两处保留预算（`resolveBudgetAnchorIdx` / `clampSliceStartByBudget`）。
     *
     * 为什么保留本函数而不是删掉改名：它有 6 个既有调用点，逐个改名只是把同一次
     * 语义修正摊成更大的 diff。两个入口名背后只有**一份公式**（此处委托，
     * [ContextEstimator] 是 wire 语义 KDoc 与来源记录的持有者）。
     * 委托方向 = "调用点名前向计量权威"；[ContextEstimator] 反向只依赖本对象更低层
     * 的文本原语 [estimateTokens]。两个 object 都没有 `init` 块互相引用，JVM 类
     * 初始化是惰性的，不构成初始化环。
     */
    fun estimateMessageTokens(message: LLMMessage): Long =
        ContextEstimator.estimateMessageTokensFull(message)

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

        SUPERSEDED VALUES: when the same entity received multiple successive values over the conversation (a config changed, a path renamed, a counter re-reported), you MAY collapse the superseded intermediate values into their latest form instead of listing every historical value — but the FINAL value of every identifier, path, URL, number, and command outcome must appear verbatim exactly once. Never drop the latest value; never invent a value not present in the conversation. This does not apply to decisions, errors, or their resolutions — those stay.

        STRUCTURE:
        1. Start with a one-line description of what the conversation was about (use past tense — "User asked X, agent did Y", NOT "Goal: X").
        2. Then a concise narrative of what happened, preserving technical details.
        3. End with a "What had been done so far" section listing completed work — NOT a "todo" or "pending" list. Do not invent ongoing objectives or carry-over tasks from old turns; if the user wants to continue, they will say so in their next message.

        PRIORITIZE recent context over older history — recent decisions and recent file/path references are most useful for continuity.

        Do NOT translate or alter code snippets, file paths, identifiers, or error messages. Be concise but never lose information the agent needs.
    """.trimIndent()

    /**
     * Input text for ONE summarizer pass: the previous rounds' summary
     * (already stripped of its pinned block) followed by this pass's
     * transcript, or just the transcript when there is no previous summary.
     *
     * [fix/r3-b11-compact-F01] Single home for that framing so the depth-0
     * pass and the halving recursion cannot drift apart — the F-01 defect was
     * exactly such a drift: the split branch handed the first half `null`
     * instead of the pushed-down summary, so every earlier compact round
     * vanished from the new summary the moment a pass hit ContextTooLarge.
     *
     * Pure string assembly: no LLM, no Android. The literal framing is pinned
     * by a JVM test because downstream tests and the summarizer prompt assume
     * it.
     */
    fun compactInputText(previousSummaryStripped: String?, transcript: String): String =
        if (previousSummaryStripped.isNullOrBlank()) {
            transcript
        } else {
            "Previous context summary:\n$previousSummaryStripped\n\n" +
                "New conversation to merge:\n$transcript"
        }
}