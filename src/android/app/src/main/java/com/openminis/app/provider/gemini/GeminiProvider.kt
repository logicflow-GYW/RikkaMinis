package com.openminis.app.provider.gemini

import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.parseRetryAfterMs
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.safeOptString
import com.openminis.app.provider.sanitizeToolPairing
import com.openminis.app.provider.clampOutboundMaxTokens
import com.openminis.app.provider.clampOutboundTemperature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import com.openminis.app.sandbox.offload.FirstChunkTimeoutPolicy
import java.util.concurrent.TimeUnit
import com.openminis.app.provider.failOnSilentEmptyCompletion

class GeminiProvider(
    private val apiKey: String,
    override var model: LLMModel = LLMModel.gemini25Flash,
    private val basePath: String = "https://generativelanguage.googleapis.com/v1beta",
) : LLMProvider {
    override val name = "Google"
    override var instanceContext: com.openminis.app.data.model.ProviderInstance? = null
    // Gemini contents: a final role="model" content part is a valid prefill —
    // continueContent / partial model turn is natively supported.
    override val supportsPrefill: Boolean get() = true

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(FirstChunkTimeoutPolicy.GENERATION_TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // [T-android-stale-conn-retry-hang] Shared pool — see NetworkMonitor.
        // Network-transition eviction must reach provider connections.
        .connectionPool(com.openminis.app.network.NetworkMonitor.sharedLLMConnectionPool)
        .build()

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val body = buildRequestBody(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
        val url = "$basePath/models/${model.id}:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            // [T-android-default-ua] Brand the outbound UA so server logs
            // can trace the request back to the Minis build. Gemini has no
            // SDK-specific UA requirement, so the helper's default kicks in.
            .applyUserAgentOverride(null)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw mapHttpError(
                response.code,
                responseBody,
                parseRetryAfterMs(response.headers["Retry-After"], System.currentTimeMillis()),
            )
        }

        val json = JSONObject(responseBody)
        val text = extractText(json)
        val finishReason = extractFinishReason(json)
        val usage = extractUsage(json)
        val mediaAttachments = extractInlineMedia(json)
        LLMResponse(text, finishReason ?: "end_turn", usage, mediaAttachments)
    }

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = rawStreamMessage(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
    ).failOnSilentEmptyCompletion(name)

    private fun rawStreamMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = callbackFlow {
        val body = buildRequestBody(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
        val url = "$basePath/models/${model.id}:streamGenerateContent?alt=sse&key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            // [T-android-default-ua] same intent as the non-streaming
            // branch above — brand outbound requests with Minis/<version>.
            .applyUserAgentOverride(null)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            val retryAfterMs = parseRetryAfterMs(response.headers["Retry-After"], System.currentTimeMillis())
            response.close()
            throw mapHttpError(response.code, errorBody, retryAfterMs)
        }

        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
        try {
            var started = false
            var lastFinishReason: String? = null
            // [RC2-truncated-detection] Accumulated text+thinking chars. Used to
            // distinguish "server cut a partial reply" (EOF, no finish_reason,
            // content present → truncated) from a genuinely empty EOF (left to
            // failOnSilentEmptyCompletion).
            var contentChars = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val payload = l.removePrefix("data: ")

                val json = try { JSONObject(payload) } catch (_: Exception) { continue }

                if (!started) {
                    send(LLMStreamChunk.Started)
                    started = true
                }

                // Separate thought parts from text parts
                val (text, thinking) = extractTextAndThinking(json)
                if (thinking.isNotEmpty()) {
                    contentChars += thinking.length
                    send(LLMStreamChunk.ThinkingDelta(thinking))
                }
                if (text.isNotEmpty()) {
                    contentChars += text.length
                    send(LLMStreamChunk.Text(text))
                }

                // Extract function calls from streaming response
                val functionCalls = extractFunctionCalls(json)
                for ((fcName, fcArgs) in functionCalls) {
                    // [audit-s4l2] id is client-minted: Gemini's streamGenerateContent
                    // gives functionCall NO server-side id (unlike OpenAI's
                    // tool_call_id). We use nanoTime because the SSE is INCREMENTAL
                    // (extractTextAndThinking appends deltas without dedupe, and text
                    // visibly never duplicates) — each functionCall's full args
                    // arrive once in a single chunk, so a fresh id per occurrence is
                    // safe and unique. NOTE: if a future Gemini stream mode ever
                    // switches to cumulative candidate snapshots, this must become a
                    // stable name-indexed id so the engine's per-turn dedupe
                    // (dedupeToolStartId) can collapse repeats.
                    val toolId = "gemini_${System.nanoTime()}"
                    send(LLMStreamChunk.ToolUseStart(toolId, fcName))
                    send(LLMStreamChunk.ToolCallComplete(toolId, fcName, fcArgs))
                }

                // [fix/audit-s4m4] The request side sets responseModalities
                // (buildThinkingLevelConfig/Gemini image+audio generation),
                // so the model may stream inlineData parts back. The
                // non-streaming path extracts them via extractInlineMedia
                // (:94) — the SSE loop previously only read text/thinking
                // parts, silently dropping media bytes on streaming turns.
                extractInlineMedia(json).forEach { media ->
                    send(LLMStreamChunk.MediaAttachment(media))
                }

                extractUsage(json)?.let { usage ->
                    send(LLMStreamChunk.Usage(usage))
                }

                extractFinishReason(json)?.let { reason ->
                    lastFinishReason = reason
                }
            }
            // [RC2-truncated-detection] EOF without a finish_reason but with
            // accumulated content = the server cut the reply mid-stream.
            // Previously this fell through to `Finished("end_turn")`, making
            // ChatViewModel treat a partial answer as a clean finish. Now we
            // signal truncated so the turnTruncated retry path owns it. A fully
            // empty EOF (contentChars == 0) is left to failOnSilentEmptyCompletion
            // and is not flagged here.
            if (lastFinishReason == null && contentChars > 0) {
                send(LLMStreamChunk.Finished(null, truncated = true))
            } else {
                send(LLMStreamChunk.Finished(lastFinishReason ?: "end_turn"))
            }
        } catch (e: Exception) {
            cancel("Stream error", mapError(e))
        } finally {
            reader.close()
            response.close()
        }
        channel.close()
        awaitClose()
    }

    private fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition> = emptyList(),
        // [T-android-thinking-level-arch] Already clamped to the model ceiling by
        // LLMProvider.streamMessage/sendMessage before reaching here.
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject {
        val body = JSONObject()

        // Defense-in-depth: strip orphan tool_use/tool_result pairing before
        // serialization. Gemini rejects a `functionCall` with no following
        // `functionResponse` (and vice versa) with a deterministic 400.
        val sanitizedMessages = sanitizeToolPairing(messages) { detail ->
            android.util.Log.i("GeminiProvider", detail)
        }

        val contents = JSONArray()
        val lastUserIndex = sanitizedMessages.indexOfLast { it.role == LLMMessage.Role.USER }
        for ((index, msg) in sanitizedMessages.withIndex()) {
            val role = if (msg.role == LLMMessage.Role.USER) "user" else "model"
            val content = JSONObject()
            content.put("role", role)

            val parts = JSONArray()

            if (msg.contentParts.isNotEmpty()) {
                for (part in msg.contentParts) {
                    when (part) {
                        is AgentContentPart.Text -> {
                            // Gemini rejects {"text": ""} with oneof 400; skip empty text parts.
                            if (part.text.isNotEmpty()) {
                                parts.put(JSONObject().put("text", part.text))
                            }
                        }
                        is AgentContentPart.ToolUse -> {
                            parts.put(JSONObject().apply {
                                put("functionCall", JSONObject().apply {
                                    put("name", part.name)
                                    put("args", part.input)
                                })
                            })
                        }
                        is AgentContentPart.ToolResult -> {
                            val responseObj = JSONObject()
                            responseObj.put("name", part.name)
                            val responseContent = JSONObject()
                            val safeContent = part.content.ifEmpty { " " }
                            responseContent.put("result", safeContent)
                            if (part.isError) responseContent.put("error", true)
                            responseObj.put("response", responseContent)
                            parts.put(JSONObject().put("functionResponse", responseObj))
                        }
                        is AgentContentPart.ImageData -> {
                            parts.put(JSONObject().put("inlineData", JSONObject().apply {
                                put("mimeType", part.mimeType)
                                put("data", Base64.encodeToString(part.data, Base64.NO_WRAP))
                            }))
                        }
                    }
                }
            } else {
                // Legacy: plain text with optional images
                if (index == lastUserIndex && imageParts.isNotEmpty()) {
                    for (part in imageParts) {
                        val inlineData = JSONObject()
                        inlineData.put("mimeType", part.mimeType)
                        inlineData.put("data", Base64.encodeToString(part.data, Base64.NO_WRAP))
                        parts.put(JSONObject().put("inlineData", inlineData))
                    }
                }
                val legacyText = msg.content.ifEmpty { " " }
                parts.put(JSONObject().put("text", legacyText))
            }
            // [T-gemini-empty-part-oneof-400] Parity with iOS convertMessages: a
            // turn whose only content was an empty .Text (skipped above) would
            // otherwise emit an empty parts[] → Gemini 400 ("contents[N].parts
            // must not be empty"). Fall back to a placeholder so parts is never
            // empty. Matches iOS's "(empty)" fallback.
            if (parts.length() == 0) {
                parts.put(JSONObject().put("text", "(empty)"))
            }
            content.put("parts", parts)
            contents.put(content)
        }
        body.put("contents", contents)

        if (systemPrompt != null) {
            body.put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", systemPrompt))
            ))
        }

        // Tools
        if (tools.isNotEmpty()) {
            val funcDecls = JSONArray()
            for (tool in tools) {
                funcDecls.put(tool.toGeminiJson())
            }
            body.put("tools", JSONArray().put(JSONObject().put("function_declarations", funcDecls)))
        }

        val config = JSONObject()
        // Defense-in-depth clamp (see AnthropicProvider) — an over-range
        // maxOutputTokens is a deterministic 400 on Gemini.
        config.put("maxOutputTokens", clampOutboundMaxTokens(maxTokens, effectiveMaxOutputTokens(model)))
        if (temperature != null) {
            config.put("temperature", clampOutboundTemperature(temperature))
        }

        // Thinking configuration (model-specific)
        buildThinkingConfig(thinkingLevel)?.let { thinkingConfig ->
            config.put("thinkingConfig", thinkingConfig)
        }

        // Response modalities — required for Gemini to actually emit inlineData
        // image/audio parts. Without this, image-generation models return only
        // text tokens and the caller's --output file stays empty. Mirrors iOS
        // GeminiProvider.swift:401-407.
        val outputs = model.outputModalities.orEmpty()
        when {
            "audio" in outputs -> config.put("responseModalities", JSONArray().put("AUDIO"))
            "image" in outputs -> config.put(
                "responseModalities",
                JSONArray().put("TEXT").put("IMAGE"),
            )
        }

        body.put("generationConfig", config)

        return body
    }

    /**
     * Build model-specific thinking config.
     * - Gemini 3.x: uses thinkingLevel string + includeThoughts
     * - Gemini 2.5 Pro: uses thinkingBudget (128-16384)
     * - Gemini 2.5 Flash: uses thinkingBudget (0-8192)
     * - Gemini 2.5 Flash Lite: no thinking support
     */
    private fun buildThinkingConfig(level: ThinkingLevel): JSONObject? {
        val modelId = model.id
        val isGemini3 = modelId.contains("gemini-3")
        val is25Pro = modelId.contains("gemini-2.5-pro")
        val is25Flash = modelId.contains("gemini-2.5-flash") && !modelId.contains("lite")
        val is25FlashLite = modelId.contains("gemini-2.5-flash-lite")

        if (is25FlashLite) return null  // No thinking support

        return when {
            isGemini3 -> {
                JSONObject().apply {
                    if (level == ThinkingLevel.OFF) {
                        // 3.x Pro can't fully disable; use "minimal" for Flash, "low" for Pro
                        put("thinkingLevel", if (modelId.contains("flash")) "minimal" else "low")
                    } else {
                        put("thinkingLevel", when (level) {
                            ThinkingLevel.LOW -> "low"
                            ThinkingLevel.MEDIUM -> "medium"
                            ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA -> "high"
                            else -> "low"
                        })
                        put("includeThoughts", true)
                    }
                }
            }
            is25Pro -> {
                JSONObject().apply {
                    put("thinkingBudget", when (level) {
                        ThinkingLevel.OFF -> 128      // minimum
                        ThinkingLevel.LOW -> 2048
                        ThinkingLevel.MEDIUM -> 8192
                        ThinkingLevel.HIGH -> 16384
                        // [T-android-thinking-level-arch] Gemini 2.5 Pro caps at
                        // 32768; XHIGH and above all take the ceiling.
                        ThinkingLevel.XHIGH,
                        ThinkingLevel.MAX,
                        ThinkingLevel.ULTRA -> 32768
                    })
                    if (level.isEnabled) put("includeThoughts", true)
                }
            }
            is25Flash -> {
                JSONObject().apply {
                    put("thinkingBudget", when (level) {
                        ThinkingLevel.OFF -> 0
                        ThinkingLevel.LOW -> 1024
                        ThinkingLevel.MEDIUM -> 4096
                        ThinkingLevel.HIGH -> 8192
                        // [T-android-thinking-level-arch] Gemini 2.5 Flash caps at
                        // 16384; XHIGH and above all take the ceiling.
                        ThinkingLevel.XHIGH,
                        ThinkingLevel.MAX,
                        ThinkingLevel.ULTRA -> 16384
                    })
                    if (level.isEnabled) put("includeThoughts", true)
                }
            }
            else -> null
        }
    }

    /** Extract text from all non-thought parts. */
    private fun extractText(json: JSONObject): String {
        return extractTextAndThinking(json).first
    }

    /** Separate thought parts (thought=true) from regular text parts. */
    private fun extractTextAndThinking(json: JSONObject): Pair<String, String> {
        val candidates = json.optJSONArray("candidates") ?: return "" to ""
        val first = candidates.optJSONObject(0) ?: return "" to ""
        val content = first.optJSONObject("content") ?: return "" to ""
        val parts = content.optJSONArray("parts") ?: return "" to ""

        val textBuilder = StringBuilder()
        val thinkingBuilder = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            val text = part.safeOptString("text", "")
            if (text.isEmpty()) continue
            if (part.optBoolean("thought", false)) {
                thinkingBuilder.append(text)
            } else {
                textBuilder.append(text)
            }
        }
        return textBuilder.toString() to thinkingBuilder.toString()
    }

    /**
     * Extract inline binary media (images/audio) from response candidate parts.
     * Mirrors iOS GeminiProvider.extractResponseContent (GeminiProvider.swift:529-549) —
     * Gemini returns generated images as `inlineData: { mimeType, data (base64) }`.
     */
    private fun extractInlineMedia(json: JSONObject): List<LLMMediaAttachment> {
        val candidates = json.optJSONArray("candidates") ?: return emptyList()
        val first = candidates.optJSONObject(0) ?: return emptyList()
        val content = first.optJSONObject("content") ?: return emptyList()
        val parts = content.optJSONArray("parts") ?: return emptyList()

        val out = mutableListOf<LLMMediaAttachment>()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val inline = part.optJSONObject("inlineData") ?: continue
            val mime = inline.safeOptString("mimeType", "")
            val b64 = inline.safeOptString("data", "")
            if (mime.isEmpty() || b64.isEmpty()) continue
            val bytes = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (e: Throwable) {
                android.util.Log.d("ModelUseImage", "gemini inlineData base64 decode failed: ${e.message}")
                continue
            }
            val type = when {
                mime.startsWith("image/") -> LLMMediaAttachment.MediaType.IMAGE
                mime.startsWith("audio/") -> LLMMediaAttachment.MediaType.AUDIO
                mime.startsWith("video/") -> LLMMediaAttachment.MediaType.VIDEO
                else -> LLMMediaAttachment.MediaType.IMAGE  // iOS fallback
            }
            android.util.Log.d("ModelUseImage", "gemini inlineData received: mime=$mime bytes=${bytes.size}")
            out.add(LLMMediaAttachment(type, mime, bytes))
        }
        return out
    }

    private fun extractFunctionCalls(json: JSONObject): List<Pair<String, JSONObject>> {
        val candidates = json.optJSONArray("candidates") ?: return emptyList()
        val first = candidates.optJSONObject(0) ?: return emptyList()
        val content = first.optJSONObject("content") ?: return emptyList()
        val parts = content.optJSONArray("parts") ?: return emptyList()

        val calls = mutableListOf<Pair<String, JSONObject>>()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            val fc = part.optJSONObject("functionCall") ?: continue
            val name = fc.safeOptString("name", "")
            val args = fc.optJSONObject("args") ?: JSONObject()
            if (name.isNotEmpty()) calls.add(name to args)
        }
        return calls
    }

    private fun extractFinishReason(json: JSONObject): String? {
        val candidates = json.optJSONArray("candidates") ?: return null
        val first = candidates.optJSONObject(0) ?: return null
        val reason = first.safeOptString("finishReason", "").ifEmpty { return null }
        return when (reason) {
            "STOP" -> "end_turn"
            "MAX_TOKENS" -> "max_tokens"
            else -> reason.lowercase()
        }
    }

    private fun extractUsage(json: JSONObject): LLMUsage? {
        val usage = json.optJSONObject("usageMetadata") ?: return null
        val totalInput = usage.optInt("promptTokenCount", 0)
        val cacheRead = usage.optInt("cachedContentTokenCount").takeIf { it > 0 }
        val freshInput = (totalInput - (cacheRead ?: 0)).coerceAtLeast(0)
        return LLMUsage(
            inputTokens = freshInput,
            outputTokens = usage.optInt("candidatesTokenCount", 0),
            cacheCreationInputTokens = null,
            cacheReadInputTokens = cacheRead,
            latestContextTokens = totalInput,
        )
    }

    private fun mapHttpError(statusCode: Int, body: String, retryAfterMs: Long? = null): LLMError {
        if (statusCode == 401 || statusCode == 403) return LLMError.InvalidApiKey()
        if (statusCode == 429) return LLMError.RateLimited(retryAfterMs = retryAfterMs)
        val message = "Gemini API error $statusCode: ${body.take(200)}"
        val transientCodes = setOf(500, 502, 503, 504, 529)
        if (statusCode in transientCodes) return LLMError.TransientError(message)
        return LLMError.ProviderError(message)
    }

    private fun mapError(error: Throwable): LLMError {
        if (error is LLMError) return error
        if (error is java.io.IOException) return LLMError.NetworkError(error)
        return LLMError.Unknown(error)
    }
}
