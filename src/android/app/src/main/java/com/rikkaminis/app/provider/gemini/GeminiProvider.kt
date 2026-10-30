package com.rikkaminis.app.provider.gemini

import android.util.Base64
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.AgentToolDefinition
import com.rikkaminis.app.data.model.LLMError
import com.rikkaminis.app.data.model.parseRetryAfterMs
import com.rikkaminis.app.provider.LegacyMediaPlan
import com.rikkaminis.app.provider.sseDataPayload
import com.rikkaminis.app.provider.applyUserAgentOverride
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.LLMMediaAttachment
import com.rikkaminis.app.data.model.LLMResponse
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.model.LLMUsage
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.provider.ImageBudget
import com.rikkaminis.app.provider.LLMProvider
import com.rikkaminis.app.provider.extractHttpErrorMessage
import com.rikkaminis.app.provider.safeOptString
import com.rikkaminis.app.provider.sanitizeToolPairing
import com.rikkaminis.app.provider.clampOutboundMaxTokens
import com.rikkaminis.app.provider.clampOutboundTemperature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import com.rikkaminis.app.sandbox.offload.FirstChunkTimeoutPolicy
import java.util.concurrent.TimeUnit
import com.rikkaminis.app.provider.causeChainSummary
import com.rikkaminis.app.provider.failOnSilentEmptyCompletion
import com.rikkaminis.app.provider.asConsumerSideCancellation
import com.rikkaminis.app.provider.StreamTimeouts
import com.rikkaminis.app.provider.StreamCancelBridge
import com.rikkaminis.app.provider.VISION_UNSUPPORTED_PLACEHOLDER
import com.rikkaminis.app.provider.visionPlaceholder
import com.rikkaminis.app.provider.nonStreamCeilingCutLogLine
import com.rikkaminis.app.provider.nonStreamOutputCeilingCut

class GeminiProvider(
    private val apiKey: String,
    override var model: LLMModel = LLMModel.gemini25Flash,
    private val basePath: String = GeminiBasePath.DEFAULT_BASE,
    /**
     * [T-provider-custom-user-agent / deep-audit-1019 ISSUE-04] Per-provider
     * User-Agent override. Both request builders used to hard-code
     * `applyUserAgentOverride(null)` and this class had no parameter for it, so
     * a UA the user set on a Gemini instance was unreachable. null/blank →
     * MinisUserAgent.DEFAULT (the helper's fallback), non-blank → replaces the
     * outbound User-Agent. Defaulted so every existing call site compiles
     * unchanged.
     */
    private val customUserAgent: String? = null,
) : LLMProvider {
    override val name = "Google"
    override var instanceContext: com.rikkaminis.app.data.model.ProviderInstance? = null
    // Gemini contents: a final role="model" content part is a valid prefill —
    // continueContent / partial model turn is natively supported.
    override val supportsPrefill: Boolean get() = true

    /**
     * [FIX-1 / F-194; corrected by deep-audit-1016 ISSUE-01] The request path is built as
     * `$basePath/models/<id>:generateContent`, so the base needs exactly one API-version
     * segment and nothing more. FIX-1 stripped `/v1beta` / `/v1` (relays paste either) but
     * never restored one, which turned "some relay bases 404" into "every base 404s" —
     * measured 404 on the versionless path, 400 on `/v1beta/models/...`.
     *
     * The rule now lives in one place ([GeminiBasePath]) because two copies of it drifting
     * apart is what produced the month-long split shape: model list 200, chat 404.
     */
    private fun normalizedBasePath(): String = GeminiBasePath.normalize(basePath)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(FirstChunkTimeoutPolicy.decideGenerationTimeoutSec(null).toLong(), TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // [T-android-stale-conn-retry-hang] Shared pool — see NetworkMonitor.
        // Network-transition eviction must reach provider connections.
        .connectionPool(com.rikkaminis.app.network.NetworkMonitor.sharedLLMConnectionPool)
        // [absorb-network-pack] Cross-provider network-leg trace + per-model
        // TTFB/health feed — previously only OpenAIProvider attached a
        // listener, so Gemini failures were invisible per-leg.
        .eventListenerFactory { com.rikkaminis.app.network.ProviderHealthTraceListener(model.id, model.displayName) }
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
        val url = "${normalizedBasePath()}/models/${model.id}:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            // [T-android-default-ua] Brand the outbound UA so server logs
            // can trace the request back to the Minis build. Gemini has no
            // SDK-specific UA requirement, so the helper's default kicks in
            // when no override is set ([deep-audit-1019 ISSUE-04]: this used
            // to be a hard-coded `null`, which also masked the user's value).
            .applyUserAgentOverride(customUserAgent)
            .build()

        // [fix/audit-s4m1] response closed via .use{} on all paths.
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                throw mapHttpError(
                    response.code,
                    responseBody,
                    parseRetryAfterMs(response.headers["Retry-After"], System.currentTimeMillis()),
                )
            }

            val json = JSONObject(responseBody)
            // [R5-E2 E-C-02②] A 2xx status is not the same thing as a successful
            // generateContent: Google itself answers `200 {"error":{…}}` on some
            // relay/edge shapes, and a blocked prompt comes back as
            // `{"promptFeedback":{…}}` with NO candidates. Both used to fall
            // through extractText (which returns "" for a missing array) into
            // `finishReason ?: "end_turn"`, i.e. an EMPTY ANSWER REPORTED AS A
            // CLEAN end_turn — the caller persisted a fabricated empty turn and
            // never retried. The verdict below is pure and unit-tested
            // (GeminiNonStreamingVerdictTest); see geminiGenerateContentVerdict
            // for why "no candidates" throws instead of returning a
            // `finishReason = "error"` response.
            when (val verdict = geminiGenerateContentVerdict(json)) {
                GeminiGenerateContentVerdict.Ok -> Unit
                is GeminiGenerateContentVerdict.HttpError ->
                    // Same mapping the streaming arm uses for an inline error
                    // frame (geminiStreamErrorStatus + mapHttpError), so a rate
                    // limit is RateLimited and a revoked key is InvalidApiKey on
                    // both paths instead of two different classifications.
                    throw mapHttpError(verdict.status, responseBody)
                is GeminiGenerateContentVerdict.EmptyCandidates ->
                    throw LLMError.ProviderError(verdict.describe())
            }
            val text = extractText(json)
            val finishReason = extractFinishReason(json)
            val usage = extractUsage(json)
            val mediaAttachments = extractInlineMedia(json)
            // Unchanged: a response that DOES carry a candidate but no
            // finishReason (notably a pure functionCall turn, where text is
            // legitimately empty) still defaults to end_turn.
            //
            // [FIX-FW-NONSTREAM C2] `finishReason = MAX_TOKENS` is normalised to
            // `max_tokens` by extractFinishReason and then carried through
            // untouched — no signal anywhere that the answer is a truncated
            // prefix. Not an exception (the same max_tokens would hit the same
            // ceiling on retry); one structured log line instead. See
            // nonStreamOutputCeilingCut for why nothing new is thrown here.
            if (nonStreamOutputCeilingCut(finishReason)) {
                android.util.Log.w(
                    name,
                    nonStreamCeilingCutLogLine(name, finishReason, text.length),
                )
            }
            LLMResponse(text, finishReason ?: "end_turn", usage, mediaAttachments)
        }
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
        val url = "${normalizedBasePath()}/models/${model.id}:streamGenerateContent?alt=sse&key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            // [T-android-default-ua] same intent as the non-streaming
            // branch above — brand outbound requests with Minis/<version>
            // unless the instance carries an override ([deep-audit-1019 ISSUE-04]).
            .applyUserAgentOverride(customUserAgent)
            .build()

        val call = client.newCall(request)
        val ttfbTimedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val headersArrived = java.util.concurrent.atomic.AtomicBoolean(false)
        val ttfbWatchdog = launch {
            delay(StreamTimeouts.TTFB_TIMEOUT_MS)
            if (!headersArrived.get()) {
                ttfbTimedOut.set(true)
                // [§24b] Log parity with OpenAIProvider — a TTFB kill without a
                // line here is invisible in production logs.
                com.rikkaminis.app.logging.AppLogger.warning(
                    "GeminiProvider",
                    "[T-android-stale-conn-retry-hang] no response headers after ${StreamTimeouts.TTFB_TIMEOUT_MS / 1000}s — cancelling call (stale pooled connection?)",
                )
                call.cancel()
            }
        }
        // [fix/audit2-cancelbridge-1015, placement corrected by audit3 F-05]
        // Cancellation bridge, registered BEFORE the first blocking call —
        // which is `call.execute()` immediately below, then `reader.readLine()`
        // in the loop. The position is load-bearing, not stylistic: `execute()`
        // itself blocks while the connection is established and the response
        // headers are awaited, and a blocking socket read is NOT interruptible
        // by coroutine cancellation. With the bridge registered AFTER
        // `execute()` (where audit2 first put it) a user cancel landing in that
        // connect/header window had no handler to reach, so the coroutine ran
        // on until the headers arrived or the TTFB watchdog
        // (StreamTimeouts.TTFB_TIMEOUT_MS) fired. Measured: with the bridge
        // after execute a cancel during a silent connect window is still parked
        // in the socket read past the 10s probe bound; with it before, the call
        // is torn down in milliseconds (see the connect-window arm of
        // ProviderCancelBridgeAnthropicGeminiTest).
        //
        // Without any bridge at all, a cancel during the body read leaves this
        // coroutine parked until the upstream speaks or the generation
        // readTimeout (30 min, FirstChunkTimeoutPolicy) fires — measured against
        // a silently wedged server: the outer job, the inner job, a wrapping
        // coroutineScope and a withTimeoutOrNull each still ran the full budget,
        // socket still open. OpenAIProvider registers it in exactly this spot.
        // See [com.rikkaminis.app.provider.StreamCancelBridge] for the three
        // semantics and the OpenAI arm's numbers.
        val cancelBridge = StreamCancelBridge(scope = this) { call.cancel() }
        val response = try {
            call.execute()
        } catch (e: IOException) {
            if (ttfbTimedOut.get()) {
                throw LLMError.TransientError(
                    "no response from Gemini after ${StreamTimeouts.TTFB_TIMEOUT_MS / 1000}s — check network/proxy"
                )
            }
            throw e
        } finally {
            headersArrived.set(true)
            ttfbWatchdog.cancel()
        }
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            val retryAfterMs = parseRetryAfterMs(response.headers["Retry-After"], System.currentTimeMillis())
            response.close()
            throw mapHttpError(response.code, errorBody, retryAfterMs)
        }

        // [audit-0917] No explicit charset: Android's platform default is
        // always UTF-8 (Chrome/ART both fix file.encoding=UTF-8; verified), and
        // SSE payloads are UTF-8 by spec. Left as-is deliberately — switching
        // to body.charStream() would change byte handling for the raw stream
        // paths without fixing anything observable.
        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
        try {
            // Headers arrived but the SSE body can still stall forever on a dead
            // tunnel. Mirror OpenAI's first-data guard: cancel the call if no
            // payload row arrives within the configured generation ceiling.
            val firstDataArrived = java.util.concurrent.atomic.AtomicBoolean(false)
            val firstDataWatchdog = launch {
                val budgetMs = FirstChunkTimeoutPolicy.decideGenerationTimeoutSec(null) * 1000L
                delay(budgetMs)
                if (!firstDataArrived.get()) call.cancel()
            }
            var started = false
            var lastFinishReason: String? = null
            // [RC2-truncated-detection] Accumulated text+thinking chars. Used to
            // distinguish "server cut a partial reply" (EOF, no finish_reason,
            // content present → truncated) from a genuinely empty EOF (left to
            // failOnSilentEmptyCompletion).
            var contentChars = 0
            // [deep-audit-1019 ISSUE-04] Gemini's streamGenerateContent hands
            // each functionCall over WHOLE in a single SSE chunk (there is no
            // Anthropic-style input_json_delta stream), so the counterpart of
            // AnthropicProvider's `toolChars` is the call payload itself: a
            // pure-tool turn that is cut after the calls but before any
            // finishReason must still trip the truncated-Finished below instead
            // of looking like a clean end_turn with an incomplete tool set.
            var toolChars = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                // [r3-b08 F-13] `data:{…}` (no space) is legal SSE and relays do
                // emit it; the strict `data: ` prefix dropped every frame.
                val payload = sseDataPayload(l)?.trim() ?: continue
                if (payload.isEmpty()) continue
                firstDataArrived.set(true)
                firstDataWatchdog.cancel()

                val json = try { JSONObject(payload) } catch (_: Exception) { continue }

                // [r3-b08 F-03] A 200 stream can carry the failure IN the body:
                // Gemini/its relays send `data: {"error":{"code":429,…}}`. With
                // no branch for it, the frame fell through `optJSONArray
                // ("candidates")` and the loop ended at EOF — which the EOF
                // guard (correctly, for a cut stream) re-signalled as truncated,
                // so a rate limit became an EOF-continuation retry of a request
                // the server had already refused. Mirrors the two siblings that
                // already do this: AnthropicProvider's `"error"` event arm
                // ([FIX-1 / F-195]) and OpenAIProvider's inline-error branch.
                json.optJSONObject("error")?.let { err ->
                    throw mapHttpError(
                        geminiStreamErrorStatus(
                            code = err.optInt("code", 0),
                            googleStatus = err.optString("status", ""),
                        ),
                        payload,
                    )
                }

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
                    // [deep-audit-1019 ISSUE-04] Count the call payload as stream
                    // activity (name + serialized args — the args JSON text, not
                    // the JSONObject key count) so a tool-only turn that never
                    // saw a finishReason is classified as truncated rather than
                    // clean.
                    toolChars += toolCallActivityChars(fcName, fcArgs.toString())
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
                    // [deep-audit-1019 ISSUE-04] Inline media is streamed output
                    // content too (audit report's companion fix): count its bytes
                    // into the activity counter so a media-only turn cut before
                    // finishReason is not reclassified as a clean end_turn.
                    contentChars += media.data.size
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
            //
            // [deep-audit-1045 A-02] "Left to failOnSilentEmptyCompletion" only
            // holds if we stop INVENTING a stop reason on the way out: that
            // operator decides by `Finished.stopReason != null` (LLMProvider.kt
            // :239), so the old `?: "end_turn"` on a fully empty EOF made it
            // read as a clean finish and no TransientError was ever raised.
            // The terminal decision now lives in [geminiEofTerminal], which
            // passes an absent finishReason through as null.
            val terminal = geminiEofTerminal(lastFinishReason, contentChars, toolChars)
            send(LLMStreamChunk.Finished(terminal.stopReason, truncated = terminal.truncated))
        } catch (e: Exception) {
            // [F-177] same defect as OpenAIProvider: a cause-less
            // CancellationException here means the consumer asked us to stop
            // (user tapped stop), not that the stream broke. Classifying it as
            // a stream error made a cancel look like a provider failure.
            val consumerCancel = e.asConsumerSideCancellation()
                // [fix/audit2-cancelbridge-1015] The bridge closes the socket, so
                // this catch sees `SocketException: Socket closed` — which says
                // the socket closed, not *why*. Re-ask the bridge for the reason
                // (the same second door OpenAIProvider uses), so a user cancel
                // keeps landing in the INFO branch below instead of being logged
                // as a stream error.
                ?: cancelBridge.consumerCancellationCause?.asConsumerSideCancellation()
            if (consumerCancel != null) {
                com.rikkaminis.app.logging.AppLogger.info(
                    "GeminiProvider",
                    "[F-177] stream cancelled by consumer: ${e.causeChainSummary()}",
                )
                cancel(consumerCancel)
            } else {
                cancel("Stream error", mapError(e))
            }
        } finally {
            reader.close()
            response.close()
            // The body left the read loop under its own power: a later scope
            // cancellation is teardown, not "this stream was cancelled".
            cancelBridge.markStreamCompleted()
        }
        cancelBridge.dispose()
        channel.close()
        awaitClose()
        // [fix/provider-stream-flowon] Same starvation as OpenAIProvider: the
        // body blocks in call.execute() + reader.readLine(), and the only
        // collector (ModelExecutionService, :modelservice) runs it under
        // `runBlocking` — so the producer owned the worker thread and both
        // in-flow watchdogs (TTFB 30s / first-data) could never fire. flowOn
        // relocates only the producer + awaitClose to the IO pool; awaitClose
        // here is empty, so nothing thread-sensitive moves.
    }.flowOn(Dispatchers.IO)

    /**
     * [R5-E2 E-F-06] Emit one turn's images as Gemini `inlineData` parts (or a
     * text placeholder when the model has no vision input — [§27a]).
     *
     * Extracted so the two carriers (the turn's structured `contentParts` and
     * the request/message-level `imageParts` lists) go through ONE assembly:
     * the shape that produced this defect was two mutually exclusive branches,
     * each with its own copy of the image assembly, so whichever branch a turn
     * landed in decided which images the wire saw.
     */
    private fun appendImageParts(
        parts: JSONArray,
        images: List<LLMMessage.ImagePart>,
        supportsImages: Boolean,
    ) {
        for (part in images) {
            val visionText = visionPlaceholder(supportsImages, VISION_UNSUPPORTED_PLACEHOLDER)
            if (visionText != null) {
                // [§27a] Non-vision model — text placeholder, mirrors the
                // contentParts branch above.
                parts.put(JSONObject().put("text", visionText))
            } else {
                // T5-L1: same backstop as the contentParts branch above.
                val safeBytes = ImageBudget.compressUnderBudget(part.data)
                val safeMime = if (safeBytes === part.data) part.mimeType else "image/jpeg"
                val inlineData = JSONObject()
                inlineData.put("mimeType", safeMime)
                inlineData.put("data", Base64.encodeToString(safeBytes, Base64.NO_WRAP))
                parts.put(JSONObject().put("inlineData", inlineData))
            }
        }
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
        // [FIX-1 / F-211] Unlike the OpenAI-shaped call sites, Gemini must NOT
        // drop messages the sanitizer emptied: the serializer below replaces ""
        // with " ", and an empty USER text is a legitimate (pinned by test)
        // payload here.
        val sanitizedMessages = sanitizeToolPairing(messages) { detail ->
            android.util.Log.i("GeminiProvider", detail)
        }

        val contents = JSONArray()
        // [§27a] Same predicate as OpenAIProvider.buildRequestBody (verbatim).
        // Gemini answers 400 when an image part reaches a text-only model, so
        // image parts are downgraded to a text placeholder below.
        val supportsImages = "image" in (model.inputModalities ?: emptyList())
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
                            // [FIX-1 / F-192] `read_image` returns its bytes as a
                            // ToolResult imageData part, NOT as an ImageData part
                            // (ReadImageTool emits AgentContentPart.ToolResult with
                            // imageData set). The ImageData branch below has a
                            // backstop for exactly this payload; this branch had
                            // none, so every image the model read back through a
                            // tool call was dropped before it reached Gemini — on
                            // the provider whose whole tool story is inline data.
                            // Emitted as a sibling inlineData part, which is how
                            // Gemini's functionResponse images are expressed.
                            part.imageData?.let { img ->
                                val visionText = visionPlaceholder(supportsImages, VISION_UNSUPPORTED_PLACEHOLDER)
                                if (visionText != null) {
                                    // [§27a] Non-vision model — text placeholder
                                    // instead of an inlineData part.
                                    parts.put(JSONObject().put("text", visionText))
                                } else {
                                    val safeBytes = ImageBudget.compressUnderBudget(img)
                                    val declaredMime = part.imageMimeType ?: "image/png"
                                    val safeMime = if (safeBytes === img) declaredMime else "image/jpeg"
                                    parts.put(JSONObject().put("inlineData", JSONObject().apply {
                                        put("mimeType", safeMime)
                                        put("data", Base64.encodeToString(safeBytes, Base64.NO_WRAP))
                                    }))
                                }
                            }
                        }
                        is AgentContentPart.ImageData -> {
                            val visionText = visionPlaceholder(supportsImages, VISION_UNSUPPORTED_PLACEHOLDER)
                            if (visionText != null) {
                                // [§27a] Non-vision model — text placeholder.
                                parts.put(JSONObject().put("text", visionText))
                            } else {
                                // T5-L1: same provider-boundary backstop as
                                // OpenAI/Anthropic — re-encode oversize history
                                // images (restored sessions, cross-device imports)
                                // before base64-inlining, so a >5MB part can't push
                                // the request past Gemini's inline-data cap.
                                val safeBytes = ImageBudget.compressUnderBudget(part.data)
                                val safeMime = if (safeBytes === part.data) part.mimeType else "image/jpeg"
                                parts.put(JSONObject().put("inlineData", JSONObject().apply {
                                    put("mimeType", safeMime)
                                    put("data", Base64.encodeToString(safeBytes, Base64.NO_WRAP))
                                }))
                            }
                        }
                    }
                }
                // [R5-E2 E-F-06] The image carriers are NOT the content/else
                // branch selector. Before this, a turn whose `contentParts` was
                // non-empty (structured text, tool blocks — the normal shape
                // since T132) read NEITHER the request-level `imageParts` (the
                // list `sendMessage`/`streamMessage` take for "this turn's
                // images") NOR `msg.imageParts`, so the entire image payload of
                // such a turn was silently dropped on the wire.
                //
                // The guard is what keeps the live chat path byte-identical: a
                // chat turn already carries its images as `ImageData` parts of
                // this same `contentParts` (and the SAME arrays again in
                // `msg.imageParts`), so with an inline image present, appending
                // either carrier would send every attachment twice. Only a turn
                // that has NO inlined ImageData needs the other carriers.
                val extraImages = geminiTurnExtraImages(
                    isLastUserTurn = index == lastUserIndex,
                    requestImages = imageParts,
                    messageImages = msg.imageParts,
                    inlineImageCount = msg.contentParts.count { it is AgentContentPart.ImageData },
                )
                if (extraImages.isNotEmpty()) {
                    appendImageParts(parts, extraImages, supportsImages)
                }
            } else {
                // Legacy: plain text with optional images
                // [deep-audit-1047 A-03] `msg.imageParts` is a per-message carrier the
                // payload predicate already weighs (`hasWirePayload`), so it needs a
                // serializer here too. The request-level list keeps its contract
                // (last user turn only); the message's own parts stay on its own turn.
                // No producer feeds this in production today, so every live path is
                // byte-identical to before.
                val legacyImages = (if (index == lastUserIndex) imageParts else emptyList()) + msg.imageParts
                val legacyImageCount = LegacyMediaPlan.imageCount(
                    attachRequestImages = index == lastUserIndex,
                    requestImageCount = imageParts.size,
                    messageImageCount = msg.imageParts.size,
                )
                if (legacyImageCount > 0) {
                    appendImageParts(parts, legacyImages, supportsImages)
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
     *
     * [T-thinking-rules-phase2] The per-family rules live in
     * ThinkingRuleResolver.geminiThinkingConfig so every vendor's thinking contract
     * is described in ONE place. This delegation also absorbed the two upstream
     * guards Android was missing:
     *   • [T-gemini37-minimal-400] Gemini 3.7+ Flash rejects `thinkingLevel:"minimal"`
     *     with a hard 400 at OFF — falls back to "low".
     *   • [T-gemini-tts-thinking-400] -tts/-image/-embedding/-vision models reject ANY
     *     thinking parameter — config is omitted entirely (OpenMinis#226).
     */
    private fun buildThinkingConfig(level: ThinkingLevel): JSONObject? {
        // [T-thinking-auto-level] AUTO = let the vendor decide: omit the whole
        // thinkingConfig object so the model's own default applies.
        if (level == ThinkingLevel.AUTO) return null
        val cfg = com.rikkaminis.app.provider.thinking.ThinkingRuleResolver
            .geminiThinkingConfig(model.id, level)
        com.rikkaminis.app.logging.AppLogger.info(
            "Thinking",
            "[resolve] provider=gemini model=${model.id} level=${level.name} " +
                "keys=[${cfg?.keys()?.asSequence()?.sorted()?.joinToString(",") ?: ""}]",
        )
        return cfg
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
        // [FIX-1 / F-197b] Gemini reports thinking tokens SEPARATELY from
        // candidatesTokenCount (totalTokenCount = prompt + candidates +
        // thoughts). Nothing in the repo read `thoughtsTokenCount` (0 hits
        // before this change), so on every thinking-enabled turn the billed
        // output side was understated: the usage panel showed less than the
        // model produced, and the auto-compact reserve — which is derived from
        // observed output growth — systematically under-reserved.
        // Folded into outputTokens rather than added as a new field because
        // that is what "output" means for billing on this API; adding a field
        // would have needed the four-way sync (model/entity/toSnapshot/reader)
        // for a number no consumer distinguishes today.
        val thoughts = usage.optInt("thoughtsTokenCount", 0)
        return LLMUsage(
            inputTokens = freshInput,
            outputTokens = usage.optInt("candidatesTokenCount", 0) + thoughts,
            cacheCreationInputTokens = null,
            cacheReadInputTokens = cacheRead,
            latestContextTokens = totalInput,
        )
    }

    private fun mapHttpError(statusCode: Int, body: String, retryAfterMs: Long? = null): LLMError {
        if (statusCode == 401 || statusCode == 403) return LLMError.InvalidApiKey()
        if (statusCode == 429) return LLMError.RateLimited(retryAfterMs = retryAfterMs)
        val message = "Gemini API error $statusCode: ${extractHttpErrorMessage(body, fallbackTake = 200)}"
        val transientCodes = setOf(500, 502, 503, 504, 529)
        if (statusCode in transientCodes) return LLMError.TransientError(message)
        return LLMError.ProviderError(message)
    }

    private fun mapError(error: Throwable): LLMError {
        if (error is LLMError) return error
        if (error is java.io.IOException) return LLMError.NetworkError(error)
        return LLMError.Unknown(error)
    }

    /**
     * [deep-audit-1019 ISSUE-04] Stream-activity size of one Gemini functionCall:
     * the tool name plus its args JSON text. Mirrors AnthropicProvider's `toolChars`
     * intent (tool input counts as stream activity) adapted to Gemini's shape,
     * where each call arrives whole in a single SSE chunk instead of as an
     * input_json_delta stream.
     */
    private fun toolCallActivityChars(name: String, argsJson: String): Int =
        name.length + argsJson.length
}

/**
 * [deep-audit-1019 ISSUE-04] Pure verdict for Gemini's SSE EOF path: the loop
 * exited without any finishReason but the stream DID deliver output — text /
 * thinking / media bytes ([contentChars]) or whole functionCall payloads
 * ([toolChars], AnthropicProvider's `toolChars` counterpart). That is a
 * mid-stream cut, so the turn must be re-signalled as truncated instead of
 * falling through to a clean `Finished("end_turn")`.
 *
 * A fully empty EOF (both counters 0) stays FALSE here: that is the
 * "silent empty completion" case owned by failOnSilentEmptyCompletion, and
 * flagging it here would double-handle it. A clear finishReason always
 * wins (FALSE) even when tool calls were delivered.
 *
 * Extracted as a top-level pure function so the boundary table is directly
 * unit-testable (see GeminiEofTruncatedDecisionTest) instead of being buried
 * in the SSE loop.
 */
internal fun shouldFlagEofTruncated(
    lastFinishReason: String?,
    contentChars: Int,
    toolChars: Int,
): Boolean = lastFinishReason == null && (contentChars > 0 || toolChars > 0)

/**
 * [deep-audit-1045 A-02] The terminal chunk Gemini's SSE loop emits once the
 * stream reaches EOF, as a pure decision over the same three inputs as
 * [shouldFlagEofTruncated].
 *
 * Why this exists: the loop's `else` arm used to send
 * `Finished(lastFinishReason ?: "end_turn")`. On a FULLY EMPTY EOF
 * (contentChars == 0 && toolChars == 0) `lastFinishReason` is null, so the
 * arm synthesized a stop reason — and `failOnSilentEmptyCompletion`
 * (LLMProvider.kt:239) treats `Finished` with a non-null stopReason as
 * "the server finished cleanly". A relay that drops the SSE body therefore
 * produced an empty assistant turn that looked like a legitimate end_turn:
 * the KDoc above `shouldFlagEofTruncated` promises that case is "left to
 * failOnSilentEmptyCompletion", and the fabricated stop reason is exactly
 * what made that promise unenforceable.
 *
 * Table (hand-pinned in GeminiEofTerminalDecisionTest):
 *  - no finishReason + any stream activity → truncated, no stop reason (the
 *    turnTruncated retry path owns it — same shape as before);
 *  - no finishReason + no activity       → NOT truncated, NO stop reason, so
 *    failOnSilentEmptyCompletion raises TransientError (retryable) instead of
 *    a silent empty success;
 *  - a real finishReason                 → pass it through verbatim, never
 *    truncated (even when tool calls were delivered).
 *
 * Deliberately NOT expressed as a change to [shouldFlagEofTruncated]: that
 * verdict has other consumers (the media-only / tool-only truncation rules)
 * and widening it would reclassify a fully empty EOF as "truncated" as well,
 * which would double-handle the case with a retry the empty-stream operator
 * already owns.
 */
internal data class GeminiEofTerminal(
    val stopReason: String?,
    val truncated: Boolean,
)

internal fun geminiEofTerminal(
    lastFinishReason: String?,
    contentChars: Int,
    toolChars: Int,
): GeminiEofTerminal =
    if (shouldFlagEofTruncated(lastFinishReason, contentChars, toolChars)) {
        GeminiEofTerminal(stopReason = null, truncated = true)
    } else {
        GeminiEofTerminal(stopReason = lastFinishReason, truncated = false)
    }

/**
 * [r3-b08 F-03] HTTP status to use for a Google-style error body
 * (`{"error":{"code":429,"status":"RESOURCE_EXHAUSTED",…}}`).
 *
 * `code` is already the HTTP status in Google's error model, so it is used
 * verbatim whenever it is a plausible status. `status` is the fallback for the
 * relays that keep the enum but drop/zero the code — that enum is what decides
 * whether the stream error becomes a retryable RateLimited, a fatal
 * InvalidApiKey, or a generic ProviderError in [GeminiProvider.mapHttpError].
 *
 * Pure + top-level so the table is unit-testable (see GeminiStreamErrorStatusTest)
 * without a network stack.
 */
internal fun geminiStreamErrorStatus(code: Int, googleStatus: String): Int {
    if (code in 400..599) return code
    return when (googleStatus.trim().uppercase()) {
        "INVALID_ARGUMENT" -> 400
        "FAILED_PRECONDITION" -> 400
        "OUT_OF_RANGE" -> 400
        "UNAUTHENTICATED" -> 401
        "PERMISSION_DENIED" -> 403
        "NOT_FOUND" -> 404
        "RESOURCE_EXHAUSTED" -> 429
        "DEADLINE_EXCEEDED" -> 504
        "INTERNAL" -> 500
        "UNAVAILABLE" -> 503
        "ABORTED" -> 503
        else -> 500
    }
}

/**
 * [R5-E2 E-F-06] Which image parts belong to a turn whose `contentParts` are
 * non-empty, i.e. the branch that previously emitted no images at all.
 *
 * Returns EMPTY when the turn already carries its images inline as
 * [AgentContentPart.ImageData] parts: those are the same bytes (chat builds
 * both carriers from one `prepared.imageParts` list), so appending the
 * parallel carriers would double every attachment in the request. Otherwise
 * the set is exactly the one the legacy branch uses — the request-level list
 * on the LAST user turn only (`sendMessage`'s documented contract), plus the
 * message's own parts on its own turn.
 *
 * Pure + top-level so the carrier table is unit-testable
 * (see GeminiNonStreamingVerdictTest) instead of hidden in the serializer.
 * Shared with the legacy branch's assembly (`GeminiProvider.appendImageParts`)
 * so the two branches cannot drift into two wire shapes again.
 */
internal fun geminiTurnExtraImages(
    isLastUserTurn: Boolean,
    requestImages: List<LLMMessage.ImagePart>,
    messageImages: List<LLMMessage.ImagePart>,
    inlineImageCount: Int,
): List<LLMMessage.ImagePart> {
    if (inlineImageCount > 0) return emptyList()
    if (requestImages.isEmpty() && messageImages.isEmpty()) return emptyList()
    val out = ArrayList<LLMMessage.ImagePart>(requestImages.size + messageImages.size)
    if (isLastUserTurn) out.addAll(requestImages)
    out.addAll(messageImages)
    return out
}

/**
 * [R5-E2 E-C-02②] Verdict for a NON-STREAMING `generateContent` body that came
 * back with a 2xx status.
 *
 * Gemini (and relays in front of it) can answer 200 with the failure inside the
 * body — `{"error":{"code":429,"status":"RESOURCE_EXHAUSTED",…}}` — exactly the
 * shape the streaming arm already intercepts ([geminiStreamErrorStatus] +
 * `mapHttpError`). The non-streaming arm read it as an empty candidate list and
 * returned `LLMResponse("", "end_turn")`, i.e. a fabricated successful empty
 * answer over a rate limit or a revoked key.
 *
 * ## Why "no candidates" THROWS instead of returning `finishReason = "error"`
 *
 * The callers cannot express the difference any other way:
 *  • nothing in the app gives an `"error"` stop reason any meaning — the only
 *    stop reason ever compared against a literal is `"length"`
 *    (`compactSummaryIsTruncated`), while `text` stays empty, so an "error"
 *    response would still be persisted as an assistant answer that says
 *    nothing, which is the very symptom being fixed;
 *  • throwing is the ONE classification path callers already treat as a
 *    failure (typed [LLMError] → error card + retry), and the streaming arm
 *    reaches the same place by throwing (`mapHttpError` on an inline error
 *    frame, `failOnSilentEmptyCompletion` on a fully empty stream).
 *
 * A response that DOES carry a candidate is left alone: a pure `functionCall`
 * turn with empty text, or a candidate that only carries `finishReason:
 * "SAFETY"`, are legitimate responses whose meaning downstream already owns.
 */
internal sealed class GeminiGenerateContentVerdict {
    /** A candidate is present — parse it the way this arm always has. */
    data object Ok : GeminiGenerateContentVerdict()

    /** The body carries an error object; [status] is its HTTP class. */
    data class HttpError(val status: Int) : GeminiGenerateContentVerdict()

    /** No candidate at all: blocked prompt, relay truncation, empty body. */
    data class EmptyCandidates(val blockReason: String?) : GeminiGenerateContentVerdict() {
        fun describe(): String = buildString {
            append("Gemini returned no candidates")
            if (!blockReason.isNullOrEmpty()) append(" (blocked: $blockReason)")
            append(" — refusing to report an empty answer as a completed turn")
        }
    }
}

internal fun geminiGenerateContentVerdict(json: JSONObject): GeminiGenerateContentVerdict {
    json.optJSONObject("error")?.let { err ->
        return GeminiGenerateContentVerdict.HttpError(
            geminiStreamErrorStatus(
                code = err.optInt("code", 0),
                googleStatus = err.optString("status", ""),
            ),
        )
    }
    val candidates = json.optJSONArray("candidates")
    if (candidates == null || candidates.length() == 0) {
        val blockReason = json.optJSONObject("promptFeedback")
            ?.safeOptString("blockReason", "")
            ?.ifEmpty { null }
        return GeminiGenerateContentVerdict.EmptyCandidates(blockReason)
    }
    return GeminiGenerateContentVerdict.Ok
}
