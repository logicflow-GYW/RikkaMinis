package com.rikkaminis.app.conversation

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.AgentToolDefinition

/**
 * [fix/prewindow-budget-1015] 结构感知的请求 token 估算器 —— 本地计量的单一事实源。
 *
 * 存在动机（2026-10-15 T321 现场证据，session 16c39966）：同一份 ~4.9MB 的
 * agent 历史，deepseek 中转口径报 prompt=191,856，GLM 直连口径报
 * input=920,577 —— 相差 4.8×。本地预算链（offload / auto-compact / trim /
 * dynamicMaxTokens / 发送阻断）全部读"上一次 API 调用的 provider 上报"，
 * 换 provider 端点的瞬间账本即失真，920K 输入 + 128K 输出顶格发出，撞死
 * 1,048,576 的硬窗口。本对象让本地对"这份历史值多少 token"有自己的读数，
 * 不再单方面信任任何一家 provider 的口供。
 *
 * 估算口径（有意保守）：
 *  - CJK ≈ 1 字 1 token（复用 [ContextCompactor.estimateTokens] 的分型逻辑）；
 *    拉丁文本 4 字符/token。JSON 重复键、base64 之类按纯文本走 —— 对
 *    中文 + 工具 JSON 为主的 agent 历史，整体系统性偏差由
 *    [ContextCalibration] 的 per-provider 校准系数吸收，这里只负责结构完整。
 *  - 图片 base64（字节 → ~4N/3 base64 字符）按 4 字符/token 计入上界；
 *    各家视觉 token 计法差异极大，宁可高估（早压缩）不可低估（爆窗口）。
 *  - 工具 schema 序列化为 OpenAI wire 格式计入 —— 11 个工具 ≈ 13K tokens，
 *    是恒定大头，漏计会让小窗口模型的预检形同虚设。
 *  - system prompt 与 DeepSeek 系回显的 reasoningContent 同样计入。
 *
 * 纯 Kotlin、无 Android 依赖，与 [ContextPolicy] / [ContextCompactor] 同风格，
 * JVM 单测直接覆盖（AuditP1ContextBudgetTest）。
 */
object ContextEstimator {

    /** 请求前预检保留的输出+记账余量（tokens）。 */
    const val PREFLIGHT_MARGIN_TOKENS = 4_000L

    /**
     * 估算一次完整请求的输入 token 规模（messages + system + tools）。
     * 口径与 wire 请求对齐：调用方必须传 [effectiveAgentHistory] 的输出
     * （含 `<context-summary>` 注入头）与同一个 [systemPrompt]，否则预检
     * 读数与实际请求漂移。
     */
    fun estimateRequestTokens(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        tools: List<AgentToolDefinition> = emptyList(),
    ): Long {
        var total = 0L
        systemPrompt?.let { total += ContextCompactor.estimateTokens(it) }
        total += estimateToolSchemaTokens(tools)
        for (m in messages) total += estimateMessageTokensFull(m)
        return total
    }

    /**
     * 单条消息估算 —— 与 [ContextCompactor.estimateMessageTokens] 的差异：
     * (1) 与 OpenAIProvider 的 wire 序列化对齐（OpenAIProvider 的
     * structured-parts 分支）—— `contentParts` 非空时 wire 只发 parts
     * （`content` 字段被忽略），parts 为空才发 `content` + 顶层图片/音频，
     * 二者互斥不双算；
     * (2) 计入图片 base64 上界与 reasoningContent 回显（DeepSeek 系要求逐轮
     * 回显，这些 token 实打实占用 provider 侧窗口）。
     *
     * [fix/audit2-estimate-1015] 本函数是**消息级估算的唯一实现**：
     * [ContextCompactor.estimateMessageTokens] 已改为委托到这里（此前它是
     * 独立的一份公式，对"content 与同文本 Text part 双填"的消息双计）。改动
     * 这条口径必须只改这里 —— 两个入口名背后不允许再有第二份逻辑。
     *
     * 有意的保守余量（保留，不改精确值）：图片按**未压缩字节**的 base64 上界计
     * （wire 侧 user 图片还会过 `ImageBudget.compressUnderBudget` 再编码，实际
     * 通常更小）、reasoning 回显无条件计入（`putEcho` 在策略为 OMIT 时其实不发
     * 这个字段）。方向都是"宁可高估（早压缩）不可低估（爆窗口）"，见文件头说明。
     */
    fun estimateMessageTokensFull(message: LLMMessage): Long {
        var total = 0L
        if (message.contentParts.isNotEmpty()) {
            message.contentParts.forEach { part ->
                when (part) {
                    is AgentContentPart.Text -> total += ContextCompactor.estimateTokens(part.text)
                    is AgentContentPart.ToolResult -> {
                        total += ContextCompactor.estimateTokens(part.content)
                        // [fullscan-1119 F-12] A tool result can carry an image
                        // (read_image / screenshot): the bytes ride in
                        // `imageData`, and this branch used to count ONLY the
                        // text `content`. A 1 MiB tool screenshot therefore
                        // cost ~0 tokens — the local budget chain (preflight /
                        // auto-compact / trim) read "context still fits" while
                        // the wire request was already over the window and the
                        // provider answered 400. Count it exactly like every
                        // other image path: [imageDataTokenEstimate] is the ONE
                        // formula.
                        part.imageData?.let { total += imageDataTokenEstimate(it.size) }
                    }
                    is AgentContentPart.ToolUse -> total += ContextCompactor.estimateTokens(part.input.toString())
                    is AgentContentPart.ImageData ->
                        total += imageDataTokenEstimate(part.data.size)
                }
            }
        } else {
            total += ContextCompactor.estimateTokens(message.content)
            for (img in message.imageParts) {
                total += imageDataTokenEstimate(img.data.size)
            }
            for (audio in message.audioParts) {
                total += ContextCompactor.estimateTokens(audio.base64Data)
            }
        }
        // putEcho 在 parts/legacy 两个分支都会带上 reasoning 字段
        message.reasoningContent?.let { total += ContextCompactor.estimateTokens(it) }
        return total
    }

    /** 工具 schema 按实际 wire 格式（OpenAI function 定义）估算。 */
    fun estimateToolSchemaTokens(tools: List<AgentToolDefinition>): Long =
        tools.sumOf { ContextCompactor.estimateTokens(it.toOpenAIJson().toString()) }

    /**
     * [fullscan-1119 F-12] Image bytes → token upper bound.
     *
     * base64 = ceil(4N/3) chars; [ContextCompactor.CHARS_PER_TOKEN] chars/token.
     * Deliberately an over-estimate (the file header explains why: early
     * compaction beats a 400 from the window).
     *
     * Every image path goes through THIS function — [AgentContentPart.ImageData],
     * the legacy `imageParts` list, and `ToolResult.imageData`. The object's
     * whole rationale is "one formula, no second copy"; three inline copies is
     * how the tool-result branch silently drifted to zero.
     */
    private fun imageDataTokenEstimate(bytes: Int): Long =
        (bytes.toLong() * 4 / 3 + ContextCompactor.CHARS_PER_TOKEN - 1) /
            ContextCompactor.CHARS_PER_TOKEN
}
