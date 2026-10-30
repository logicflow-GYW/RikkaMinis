package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.AgentToolDefinition
import com.rikkaminis.app.data.model.LLMError
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.LLMResponse
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.ui.chat.TruncatedToolCallPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach

interface LLMProvider {
    val name: String
    var model: LLMModel

    /**
     * The ProviderInstance this provider was created from, when known (set by ProviderFactory).
     * Used by Direction-A offload (chat stream to :modelservice) to rebuild the provider
     * in the service process. Null for hand-constructed/mock providers → offload skipped.
     */
    var instanceContext: com.rikkaminis.app.data.model.ProviderInstance?

    /**
     * Effective max output tokens ceiling for the given model.
     * Priority: model.maxOutputTokens > provider-level default.
     * Used as the upper bound in dynamicMaxTokens().
     */
    fun effectiveMaxOutputTokens(model: LLMModel): Int =
        model.maxOutputTokens ?: defaultMaxOutputTokens

    /** Provider-level fallback when model.maxOutputTokens is unknown. */
    val defaultMaxOutputTokens: Int get() = 16_384

    /**
     * [T-android-tool-splits-reply-fix] True when the streamed assistant text
     * is one monolithic `content` string per response (OpenAI Chat
     * Completions): text deltas carry NO positional relationship to
     * tool_calls deltas — a non-streaming materialisation is always
     * {content, tool_calls} with content first — so ALL text deltas of one
     * streamed response belong to a single text block that precedes the tool
     * blocks. Some endpoints (qwen) flush trailing content chunks after
     * tool_calls deltas purely as a chunking artifact; reconstructing those
     * chronologically fabricates an order the wire format cannot express.
     * False for formats with genuinely ordered output blocks (Responses API
     * output items, Anthropic content blocks), where arrival order IS the
     * semantic block order.
     */
    val streamTextIsMonolithic: Boolean get() = false

    /**
     * [T-length-wall-prefill] True when the provider accepts an ASSISTANT
     * message as the FINAL message of a request, so a truncated
     * (finish_reason="length"/max_tokens) reply can be continued by prefill —
     * re-sending the incomplete assistant text as the last message with NO
     * synthetic user message after it. The model is then forced to continue
     * the unfinished assistant turn and has no room to back up and re-emit
     * already-output text (the root cause of length-wall seam duplication).
     *
     * False (default) for strict third-party relays that require the last
     * message to be USER — those fall back to the reminder + seam-trim path.
     */
    val supportsPrefill: Boolean get() = false

    /**
     * [T-android-thinking-level-arch] PUBLIC entry — this is what every caller
     * (agent loop, fallback, quick test, model-use, …) invokes. It is NOT
     * overridden by providers: the default implementation clamps the requested
     * thinking level to the current [model]'s ceiling ONCE, then delegates to
     * [sendMessageClamped]. Making the clamp structural (rather than a call each
     * impl must remember) means no code path can send an over-range level — a
     * fallback that swapped [model] mid-flight clamps to the NEW model's limit
     * automatically. Mirrors iOS AgentProvider.streamAgentMessage → …Clamped.
     */
    suspend fun sendMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double? = null,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): LLMResponse {
        // [TF-E] Runtime process-domain guard: refuse to make a provider network
        // call outside the :modelservice worker. JVM tests (null processName)
        // pass through; the static unit-test guard owns test enforcement.
        ProviderBoundary.enforce(ProviderBoundary.currentProcessName())
        // [provider-rss v2] 打点定位：聊天直连 provider 的非流式调用，捕获主进程
        // VmRSS 增量 + 调用期间峰值采样 + 入参/出参字节估算。零副作用，不影响调用结果。
        val beforeKb = ProviderRssProbe.rssKb()
        val peakHandle = ProviderRssProbe.startPeakSampling()
        var response: LLMResponse? = null
        try {
            response = sendMessageClamped(
                messages, systemPrompt, maxTokens, temperature, imageParts, tools,
                clampThinkingLevel(thinkingLevel),
            )
            return response
        } finally {
            val afterKb = ProviderRssProbe.rssKb()
            val peakKb = peakHandle.stop()
            ProviderRssProbe.record(
                ProviderRssProbe.ProbeRecord(
                    kind = "sendMessage:$name",
                    beforeRss = beforeKb,
                    afterRss = afterKb,
                    peakRss = peakKb,
                    inputBytes = approxInputBytes(messages, imageParts),
                    outputBytes = response?.let { approxOutputBytes(it) } ?: -1L,
                )
            )
        }
    }

    /** See [sendMessage] — the clamped, provider-implemented counterpart. */
    fun streamMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double? = null,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): Flow<LLMStreamChunk> = flow {
        // [TF-E] Runtime process-domain guard: refuse to make a provider network
        // call outside the :modelservice worker. JVM tests (null processName)
        // pass through; the static unit-test guard owns test enforcement.
        ProviderBoundary.enforce(ProviderBoundary.currentProcessName())
        // [provider-rss v2] 打点定位：聊天直连 provider 的流式调用。streamMessage
        // 是冷 Flow，打点放在真正被 collect（发出请求）的前后；峰值采样 + 入参/出参字节估算。
        val beforeKb = ProviderRssProbe.rssKb()
        val peakHandle = ProviderRssProbe.startPeakSampling()
        var outBytes = 0L
        try {
            emitAll(
                streamMessageClamped(
                    messages, systemPrompt, maxTokens, temperature, imageParts, tools,
                    clampThinkingLevel(thinkingLevel),
                ).onEach { chunk ->
                    outBytes += approxOutputChunkBytes(chunk)
                }
            )
        } finally {
            val afterKb = ProviderRssProbe.rssKb()
            val peakKb = peakHandle.stop()
            ProviderRssProbe.record(
                ProviderRssProbe.ProbeRecord(
                    kind = "streamMessage:$name",
                    beforeRss = beforeKb,
                    afterRss = afterKb,
                    peakRss = peakKb,
                    inputBytes = approxInputBytes(messages, imageParts),
                    outputBytes = outBytes,
                )
            )
        }
    }

    /**
     * [T-android-thinking-level-arch] Provider implementations override THIS
     * (not [sendMessage]). The `thinkingLevel` received here has already been
     * clamped to the model's ceiling by [sendMessage] — implementations must NOT
     * re-clamp. Mirrors iOS `streamAgentMessageClamped`.
     */
    suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse

    /** See [sendMessageClamped]. */
    fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk>

    /**
     * [T-android-thinking-level-arch] Cap a requested thinking level to the
     * current [model]'s ceiling by rank. Used by the [sendMessage]/[streamMessage]
     * default entries above; the catalog ceiling is resolved off the live
     * `model`.
     */
    fun clampThinkingLevel(level: ThinkingLevel): ThinkingLevel {
        // [T-thinking-auto-level] AUTO expresses no intensity and is not a wire
        // tier — the per-model ceiling must not touch it (its appended rank is
        // an artifact, not an intensity). Leave it verbatim.
        if (level == ThinkingLevel.AUTO) return level
        val ceiling = model.catalogMaxThinkingLevel
        return if (level.rank > ceiling.rank) ceiling else level
    }
}

/**
 * [T-android-empty-stream-retry] Detect a silently-truncated stream.
 *
 * When a relay/upstream drops the SSE connection without an error status,
 * the provider flow completes "normally" having emitted no content and no
 * finish reason — the chat just stops mid-air with no error (user report).
 * iOS treats this as a transient error at its stream-collection layer
 * (AIChatViewModel.isEmptyResponse → LLMError.transientError) and
 * auto-retries; this operator is the Android provider-layer equivalent.
 *
 * Empty = no text / thinking / reasoning / tool-call / media chunk AND no
 * finish reason. A stream that finished WITH a stop reason but no content
 * ("stop"/"end_turn") is deliberately let through — the agent loop's
 * empty-after-tool-result reminder path (ChatViewModel) owns that case, and
 * a "length"/"max_tokens" cut is a legitimate empty. Cancellation and
 * thrown errors propagate before the check runs (code after `collect`
 * only executes on normal completion).
 */
fun Flow<LLMStreamChunk>.failOnSilentEmptyCompletion(providerName: String): Flow<LLMStreamChunk> = flow {
    var sawContent = false
    var sawFinishReason = false
    collect { chunk ->
        when (chunk) {
            is LLMStreamChunk.Text -> if (chunk.text.isNotEmpty()) sawContent = true
            is LLMStreamChunk.ThinkingDelta -> if (chunk.text.isNotEmpty()) sawContent = true
            is LLMStreamChunk.ReasoningContent -> if (chunk.content.isNotEmpty()) sawContent = true
            is LLMStreamChunk.ToolUseStart,
            is LLMStreamChunk.ToolInputDelta,
            is LLMStreamChunk.ToolCallComplete,
            is LLMStreamChunk.MediaAttachment -> sawContent = true
            is LLMStreamChunk.Finished -> if (chunk.stopReason != null) sawFinishReason = true
            else -> {}
        }
        emit(chunk)
    }
    if (!sawContent && !sawFinishReason) {
        android.util.Log.w(
            "LLMProvider",
            "$providerName: stream completed with no content and no finish reason — treating as transient upstream failure",
        )
        throw LLMError.TransientError("Server returned an empty response (connection dropped or upstream error)")
    }
}

// [provider-rss v2] 入参估算（kB 粒度即可，观测用不追求精确；含 image/audio 字节）。
//
// [r3-b08 F-14] The estimate walked `content` + per-message media only, and since
// T132 moved tool calls / tool results / history images into `contentParts`,
// `content` is EMPTY on exactly the heavy turns the probe exists to explain — so
// a multi-MB tool-result round logged `in=0` and could not be correlated with an
// RSS jump. `contentParts` and the REQUEST-level images (attached to the last
// user turn only) are now counted. Units: UTF-16 chars stay ×2 ("~2B/char"), the
// same convention the media branches already use in bytes.
internal fun approxInputBytes(
    messages: List<LLMMessage>,
    turnImageParts: List<LLMMessage.ImagePart>,
): Long {
    var bytes = 0L
    for (m in messages) {
        bytes += m.content.length.toLong() * 2L
        for (part in m.contentParts) bytes += approxContentPartBytes(part)
        for (img in m.imageParts) bytes += img.data.size.toLong()
        for (aud in m.audioParts) bytes += aud.base64Data.length.toLong()
    }
    for (img in turnImageParts) bytes += img.data.size.toLong()
    return bytes
}

/** [r3-b08 F-14] One structured part's wire size. Pure — see ProviderRssEstimateTest. */
internal fun approxContentPartBytes(part: AgentContentPart): Long = when (part) {
    is AgentContentPart.Text -> part.text.length.toLong() * 2L
    is AgentContentPart.ToolUse -> (part.name.length + part.input.toString().length).toLong() * 2L
    is AgentContentPart.ToolResult ->
        part.content.length.toLong() * 2L + (part.imageData?.size?.toLong() ?: 0L)
    is AgentContentPart.ImageData -> part.data.size.toLong()
}

// [provider-rss v2] 非流式响应体估算（文本 UTF-16 长度近似）。
// [r3-b08 F-14] Media the model GENERATED (image generation via the Codex
// backend) is part of the response's memory footprint; the old text-only
// formula logged `out=0` for a turn that materialised a multi-MB image.
internal fun approxOutputBytes(r: LLMResponse): Long =
    r.text.length.toLong() * 2L + r.mediaAttachments.sumOf { it.data.size.toLong() }

/**
 * [r3-b08 F-14] Per-chunk output estimate for the streaming probe. Tool-call
 * deltas and model-generated media are output too — the previous version counted
 * only text/thinking, so a tool-only or image-only stream logged `out=0`.
 * `ToolInputDelta.accumulated` is the CUMULATIVE argument text (not the delta), so
 * it is counted once at `ToolCallComplete` and the delta contributes nothing —
 * counting both would square the argument size.
 */
internal fun approxOutputChunkBytes(chunk: LLMStreamChunk): Long = when (chunk) {
    is LLMStreamChunk.Text -> chunk.text.length.toLong() * 2L
    is LLMStreamChunk.ThinkingDelta -> chunk.text.length.toLong() * 2L
    is LLMStreamChunk.ReasoningContent -> chunk.content.length.toLong() * 2L
    is LLMStreamChunk.ToolCallComplete -> chunk.args.toString().length.toLong() * 2L
    is LLMStreamChunk.MediaAttachment -> chunk.attachment.data.size.toLong()
    else -> 0L
}

/**
 * [FIX-FW-NONSTREAM C2] True when a NON-STREAMING response's `stopReason` says
 * the provider stopped at the output ceiling — i.e. the visible text is a
 * truncated prefix, not a finished answer.
 *
 * ## Why this delegates instead of declaring a second spelling table
 * The app already owns ONE table of ceiling spellings across every protocol it
 * speaks: [TruncatedToolCallPolicy.isTruncatedFinish] (`length`,
 * `max_tokens`, `max_output_tokens`, `max_tokens_exceeded`, relay variants),
 * which guards the truncated-tool-call contract. Three non-streaming arms had no
 * signal at all — OpenAI's `sendMessageClamped` only promoted
 * `Finished.truncated` (an EOF signal, never `finish_reason = length`),
 * Anthropic passed `max_tokens` through, Gemini passed `MAX_TOKENS` (normalised
 * to `max_tokens` by `extractFinishReason`) through — so "the reply stopped at
 * the ceiling" was indistinguishable from "the model finished". Adding a second
 * table here would let the two drift exactly the way the currency/pricing pair
 * did (R5-EF F-F-07); the table's only home stays the policy.
 *
 * Pure + top-level so the spelling table is JVM-testable.
 */
internal fun nonStreamOutputCeilingCut(stopReason: String?): Boolean =
    TruncatedToolCallPolicy.isTruncatedFinish(stopReason)

/**
 * [FIX-FW-NONSTREAM C2] The ONE log line every non-streaming arm emits when
 * [nonStreamOutputCeilingCut] fires.
 *
 * Nothing here may identify the conversation: the provider name, the provider's
 * own stop spelling, the number of output characters and whether the text is
 * empty. NOT the text, not a prefix of it, not a hash of it — the ceiling
 * usually cuts mid-sentence in a user's private prompt echo.
 *
 * `emptyText=true` is the case worth having a log for: with zero text and a
 * ceiling stop reason the turn is a *complete* failure (thinking-only or an
 * immediate cut), and the user sees a blank answer with no error card.
 */
internal fun nonStreamCeilingCutLogLine(
    providerName: String,
    stopReason: String?,
    outputChars: Int,
): String = "non-streaming reply hit the output ceiling:" +
    " provider=$providerName stopReason=${stopReason ?: "null"}" +
    " outputChars=$outputChars emptyText=${outputChars <= 0}"
