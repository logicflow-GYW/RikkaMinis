package com.rikkaminis.app.sandbox.offload

import android.content.Context
import com.rikkaminis.app.data.model.AgentToolDefinition
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.LLMResponse
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Main-process SINGLE entry point for every LLM / provider call.
 *
 * TF-D core: the app process (`:app`) must NEVER create a provider or call
 * `provider.sendMessage` / `provider.streamMessage` / `generateImage`
 * directly. All such calls route through [ModelExecutionService] living in
 * the short-lived `:modelservice` process, whose native heap (DirectByteBuffer
 * response buffers, JSON/image decode) returns to the OS when the worker
 * self-reaps — the only reliable leak containment that in-process GC cannot
 * achieve.
 *
 * Responsibilities (single chokepoint):
 *   - build the serialised request (TF-A observation, TF-B lifecycle protocol,
 *     TF-C file-reference pressure are all consumed here)
 *   - start `:modelservice` and read back the streamed chunks / result file
 *   - classify failures into a typed result — NEVER silently fall back to a
 *     main-process provider call.
 *
 * The only legal place that instantiates a provider is [ModelExecutionService]
 * (the worker). This file deliberately holds no reference to any concrete
 * `LLMProvider` implementation and never imports `ProviderFactory`.
 *
 * A request that cannot be dispatched returns [SendResult.Unavailable] /
 * throws a typed stream exception — the caller retries / switches provider /
 * surfaces the error. It does NOT drop back into in-process execution.
 */
object ProviderExecutionGateway {

    /**
     * Typed non-streaming outcome. There is deliberately NO "fallback to
     * in-process provider" branch — a failure here is surfaced to the caller
     * so it can retry via the gateway again, switch provider, or show an error.
     */
    sealed class SendResult {
        /** The worker wrote a result.json we could parse. */
        data class Success(
            val response: LLMResponse,
            val rawJson: String,
        ) : SendResult()

        /**
         * The worker ran but produced a typed error (missing_api_key,
         * model_use_failed, audio_input_unsupported_provider, …).
         * [exitCode] matches the protocol's non-zero exit codes.
         */
        data class RemoteFailure(
            val code: String,
            val message: String,
            val exitCode: Int,
        ) : SendResult()

        /**
         * The worker could not even be dispatched / its result could not be
         * read before timeout (staging dir issue, startService failed, the
         * result never materialised, or an unparseable result). The caller
         * may retry via the gateway.
         */
        data class Unavailable(val reason: String) : SendResult()
    }

    /**
     * Build the serialised request JSON for a non-streaming (or streaming)
     * run, routing through [ModelExecutionDispatcher.buildRequestJson] so all
     * four-way-synced fields / TF-A observation / TF-B protocol stay in one
     * place. Pure function — JVM-testable, no Context needed.
     */
    fun buildRequest(
        instance: ProviderInstance,
        model: LLMModel,
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        inputJson: String,
        outputExt: String?,
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
        streaming: Boolean = false,
    ): String = ModelExecutionDispatcher.buildRequestJson(
        instance = instance,
        model = model,
        messages = messages,
        systemPrompt = systemPrompt,
        maxTokens = maxTokens,
        temperature = temperature,
        imageParts = imageParts,
        inputJson = inputJson,
        outputExt = outputExt,
        tools = tools,
        thinkingLevel = thinkingLevel,
        streaming = streaming,
    )

    /**
     * Non-streaming send: dispatch to `:modelservice` and parse the worker's
     * result into an [LLMResponse]. Returns a typed [SendResult].
     *
     * On success the worker already committed result.json atomically and
     * (per TF-B) waited for our client.ack before self-reaping, so the native
     * heap is returning to the OS. On any dispatch failure we return
     * [SendResult.Unavailable] — the caller must NOT fabricate an in-process
     * provider call.
     */
    suspend fun send(
        context: Context,
        instance: ProviderInstance,
        model: LLMModel,
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        inputJson: String = "",
        outputExt: String? = null,
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): SendResult {
        val requestJson = buildRequest(
            instance = instance,
            model = model,
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            temperature = temperature,
            imageParts = imageParts,
            inputJson = inputJson,
            outputExt = outputExt,
            tools = tools,
            thinkingLevel = thinkingLevel,
            streaming = false,
        )
        val raw = ModelExecutionDispatcher.dispatch(context, requestJson)
            ?: return SendResult.Unavailable("model service dispatch failed or timed out")
        return parseNonStreamingResult(raw)
    }

    /**
     * Parse a worker result.json string into a typed [SendResult].
     * Standalone pure function so JVM tests can pin failure classification
     * without a Context / worker.
     */
    fun parseNonStreamingResult(raw: String): SendResult {
        val obj = try { JSONObject(raw) } catch (_: Throwable) {
            return SendResult.Unavailable("result JSON unparseable")
        }
        val errorCode = obj.optString("error", "")
        if (errorCode.isNotEmpty()) {
            return SendResult.RemoteFailure(
                code = errorCode,
                message = obj.optString("message", ""),
                exitCode = obj.optInt("exit_code", 1),
            )
        }
        val media = (obj.optJSONArray("media_files") ?: JSONArray()).let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                val b64 = m.optString("data", "")
                if (b64.isEmpty()) return@mapNotNull null
                com.rikkaminis.app.data.model.LLMMediaAttachment(
                    type = com.rikkaminis.app.data.model.LLMMediaAttachment.MediaType.values()
                        .firstOrNull { it.value == m.optString("type", "") }
                        ?: com.rikkaminis.app.data.model.LLMMediaAttachment.MediaType.IMAGE,
                    mimeType = m.optString("mime_type", "application/octet-stream"),
                    data = java.util.Base64.getDecoder().decode(b64),
                )
            }
        }
        val usage = obj.optJSONObject("usage")?.let { u ->
            com.rikkaminis.app.data.model.LLMUsage(
                inputTokens = u.optInt("input_tokens", 0),
                outputTokens = u.optInt("output_tokens", 0),
            )
        }
        return SendResult.Success(
            response = LLMResponse(
                text = obj.optString("text", ""),
                stopReason = obj.optString("stop_reason", "").ifEmpty { null },
                usage = usage,
                mediaAttachments = media,
            ),
            rawJson = raw,
        )
    }

    /**
     * Streaming send: dispatch a `"streaming":true` request to `:modelservice`
     * and return its chunks as a [Flow]. Failures surface as typed
     * [ModelExecutionStreamException] subclasses (0-chunk → caller MAY retry;
     * has-chunk → caller MUST NOT re-send). There is no in-process fallback:
     * a remote failure is a real failure.
     */
    fun stream(
        context: Context,
        instance: ProviderInstance,
        model: LLMModel,
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
        inputJson: String = "",
        outputExt: String? = null,
    ): Flow<LLMStreamChunk> {
        val requestJson = buildRequest(
            instance = instance,
            model = model,
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            temperature = temperature,
            imageParts = imageParts,
            inputJson = inputJson,
            outputExt = outputExt,
            tools = tools,
            thinkingLevel = thinkingLevel,
            streaming = true,
        )
        return ChatStreamOffloadHandler.stream(context, requestJson, thinkingLevel.isEnabled)
    }

    /**
     * Image-generation send: dispatch an OpenAI-Compat image request to
     * `:modelservice` (which routes to /v1/images/generations when the request
     * carries a media outputExt) and return the generated attachments wrapped
     * in an [LLMResponse] (media-first), or a typed failure. No main-process
     * provider creation.
     */
    suspend fun generateImage(
        context: Context,
        instance: ProviderInstance,
        model: LLMModel,
        prompt: String,
        n: Int = 1,
        size: String? = null,
        quality: String? = null,
    ): SendResult {
        val genInput = JSONObject().apply {
            put("prompt", prompt)
            put("n", maxOf(1, n))
            size?.takeIf { it.isNotBlank() }?.let { put("size", it) }
            quality?.takeIf { it.isNotBlank() }?.let { put("quality", it) }
        }.toString()
        return send(
            context = context,
            instance = instance,
            model = model,
            messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
            systemPrompt = null,
            maxTokens = 4096,
            temperature = null,
            imageParts = emptyList(),
            inputJson = genInput,
            outputExt = "png",
        )
    }
}