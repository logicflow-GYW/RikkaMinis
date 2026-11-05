package com.rikkaminis.app.provider.openai

import com.rikkaminis.app.data.model.LLMError
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.model.LLMUsage
import com.rikkaminis.app.provider.TOOL_LOG_INPUT_STEP_CHARS
import com.rikkaminis.app.provider.extractHttpErrorMessage
import com.rikkaminis.app.provider.safeOptString
import org.json.JSONObject

/**
 * SHADOW PROBE for FIX-A (F-01 / F-02 / F-06).
 *
 * Decision logic is NOT hand-copied: every block between the marker lines below
 * is extracted verbatim from the real OpenAIProvider.kt by /tmp/probeA2/build.py,
 * dedented only. The harness around it (state declarations, payload loop, send())
 * is glue and holds no decision.
 */

private class StubModel(val id: String)

// ======================= F-01 / F-02 : Responses path =======================

private fun responsesStream(payloads: List<String>): List<LLMStreamChunk> {
    val out = mutableListOf<LLMStreamChunk>()
    fun send(c: LLMStreamChunk) { out.add(c) }

    val model = StubModel("stub-model")
    val responsesToolCalls = mutableMapOf<String, ResponsesToolCallAccumulator>()
    val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>()
    var finishReason: String? = null
    var sawFinishReason = false
    var sawReasoningField = false
    var sawReasoningDelta = false
    val reasoningAccum = StringBuilder()
    var contentLen = 0
    var reasoningLen = 0
    var toolCallEventCount = 0
    var sseEventCount = 0
    var finishedSent = false
    val hasThinkTags = true
    val thinkState = ThinkTagState()
    var lastUsageJson: JSONObject? = null
    var sawUsageBlock = false

    for (payload in payloads) {
if (payload == "[DONE]") {
    // Flush any remaining buffered content from think tag extraction
    if (hasThinkTags) {
        flushThinkTags(thinkState)?.let { remaining ->
            if (thinkState.insideTag) {
                send(LLMStreamChunk.ThinkingDelta(remaining))
            } else {
                send(LLMStreamChunk.Text(remaining))
            }
        }
    }
    if (sawReasoningField) {
        send(LLMStreamChunk.ReasoningContent(reasoningAccum.toString()))
    }
    send(LLMStreamChunk.Finished(finishReason, truncated = !sawFinishReason))
    finishedSent = true
    break
}

        val event = try { JSONObject(payload) } catch (e: Exception) { continue }
        val type = event.optString("type", "")
        when {
type == "response.output_item.added" -> {
    val item = event.optJSONObject("item") ?: continue
    val itemType = item.optString("type", "")
    if (itemType == "function_call") {
        val itemId = item.optString("id", "")
        val callId = item.optString("call_id", "")
        val name = item.optString("name", "")
        val outputIndex = event.optInt("output_index", -1)
        if (callId.isNotEmpty() && name.isNotEmpty()) {
            val key = if (itemId.isNotEmpty()) itemId else callId
            val acc = ResponsesToolCallAccumulator(
                callId = callId,
                name = name,
                itemId = itemId,
                outputIndex = outputIndex,
            )
            responsesToolCalls[key] = acc
            val combined = combineResponsesAPIIds(callId, itemId)
            android.util.Log.d("ToolChain[Provider]", "→ ToolUseStart (Responses) id=$combined name=$name")
            send(LLMStreamChunk.ToolUseStart(combined, name))
            acc.started = true
        }
    }
}
// [T-rhub1105-F01] The authoritative end of the argument stream.
// The API may deliver the complete arguments in this frame (some
// relays emit no `.delta` at all) — treat it as the truth and
// finalize the call here. Without this arm the tool call was
// only ever completed by `response.output_item.done`.
type == "response.function_call_arguments.done" -> {
    val itemId = event.optString("item_id", "")
    val callId = event.optString("call_id", "")
    val outputIndex = event.optInt("output_index", -1)
    val arguments = event.optString("arguments", "")
    val acc = resolveResponsesAccumulator(
        responsesToolCalls.values,
        itemId,
        callId,
        outputIndex,
    )
    if (acc != null) {
        if (arguments.isNotEmpty()) {
            // Authoritative value — replace the streamed buffer.
            acc.args.setLength(0)
            acc.args.append(arguments)
        }
        val key = responsesToolCalls.entries.firstOrNull { it.value === acc }?.key
        if (key != null) responsesToolCalls.remove(key)
        val args = try { JSONObject(acc.args.toString()) } catch (_: Exception) { JSONObject() }
        val combined = combineResponsesAPIIds(acc.callId, acc.itemId)
        android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete (Responses) id=$combined name=${acc.name} args=${args.toString().take(300)}")
        send(LLMStreamChunk.ToolCallComplete(combined, acc.name, args))
    }
}
type == "response.function_call_arguments.delta" -> {
    val itemId = event.optString("item_id", "")
    val callId = event.optString("call_id", "")
    val outputIndex = event.optInt("output_index", -1)
    val delta = event.optString("delta", "")
    val acc = resolveResponsesAccumulator(
        responsesToolCalls.values,
        itemId,
        callId,
        outputIndex,
    )
    if (acc != null && delta.isNotEmpty()) {
        acc.args.append(delta)
        val combined = combineResponsesAPIIds(acc.callId, acc.itemId)
        // [T-toolinput-sample-1012] Sampled like the chat-completions path:
        // first delta + one per TOOL_LOG_INPUT_STEP_CHARS of growth.
        val n = acc.args.length
        if (acc.lastLoggedInputChars < 0 || n - acc.lastLoggedInputChars >= TOOL_LOG_INPUT_STEP_CHARS) {
            acc.lastLoggedInputChars = n
            android.util.Log.d("ToolChain[Provider]", "→ ToolInputDelta (Responses) id=$combined accumulated=${n}chars")
        }
        send(LLMStreamChunk.ToolInputDelta(combined, acc.args.toString()))
    } else if (acc == null) {
        // Pre-T107 this branch silently dropped the entire tool call
        // because no accumulator was set up — leaving the model with
        // no real tool channel and provoking <tool_call>{...} text
        // hallucinations. Keep a warn so any future regression here
        // surfaces in the daily log instead of a silent failure.
        com.rikkaminis.app.logging.AppLogger.warning(
            "OpenAIProvider",
            "Responses API: function_call_arguments.delta for unknown item_id=$itemId — dropping"
        )
    }
}
// The accumulator is finalized at response.output_item.done, when the
// arguments stream has flushed. The completed item carries `arguments`
// as a JSON string — we prefer that authoritative value over our own
// streamed buffer in case the API ever emits a corrected payload.
type == "response.output_item.done" -> {
    val item = event.optJSONObject("item") ?: continue
    val itemType = item.optString("type", "")
    if (itemType == "function_call") {
        val itemId = item.optString("id", "")
        val callId = item.optString("call_id", "")
        val outputIndex = event.optInt("output_index", -1)
        val acc = resolveResponsesAccumulator(
            responsesToolCalls.values,
            itemId,
            callId,
            outputIndex,
        ) ?: continue
        val key = responsesToolCalls.entries.firstOrNull { it.value === acc }?.key
        if (key != null) responsesToolCalls.remove(key)
        // [T-rhub1105-F01] `optString(key, fallback)` returns the
        // PRESENT value even when it is the empty string — so a
        // `.done` frame carrying `"arguments": ""` used to WIPE the
        // streamed buffer and produce `{}`. An empty string is
        // not authoritative: fall back to what we accumulated.
        // A non-empty `arguments` IS authoritative (the server
        // may have corrected the payload).
        val authoritative = item.optString("arguments", "")
        val argsStr = if (authoritative.isNotEmpty()) authoritative else acc.args.toString()
        val args = try { JSONObject(argsStr) } catch (_: Exception) { JSONObject() }
        val combined = combineResponsesAPIIds(acc.callId, acc.itemId)
        android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete (Responses) id=$combined name=${acc.name} args=${args.toString().take(300)}")
        send(LLMStreamChunk.ToolCallComplete(combined, acc.name, args))
    }
}
// [T-rhub1105-F02] Top-level `type == "error"` frame and
// inline `error` object on the Responses API. The Chat
// Completions branch has this arm, the Responses branch did
// NOT: such a frame fell through the `when`, the stream ended
// normally and the caller saw a clean (but empty) finish
// instead of the real error. Same single-source mapping as
// every other error site: [dv28 A-08] symbolic code → HTTP
// semantics → mapHttpError (no new mapping table here).
type == "error" || event.optJSONObject("error") != null -> {
    val err = event.optJSONObject("error") ?: event
    val code = err.optString("code", "")
    val errType = err.optString("type", "")
    val message = err.optString("message", "")
        .ifEmpty { "Responses API error frame without message" }
    com.rikkaminis.app.logging.AppLogger.error(
        "OpenAIProvider",
        "Responses API error frame — code=$code type=$errType message=$message"
    )
    throw mapHttpError(
        openAiStreamErrorStatus(code, errType),
        err.toString(),
    )
}

type == "response.failed" -> {
    // [T-responses-terminal-events] Explicit terminal
    // handling instead of the generic fallthrough: pull
    // the structured error off the response object so
    // the thrown LLMError carries the real reason (and
    // so retry/fallback classification can act on it).
    // Official shape: response.status == "failed",
    // response.error = {code, message}. Mirrors iOS 637cd890.
    val resp = event.optJSONObject("response")
    val err = resp?.optJSONObject("error")
    val code = err?.optString("code")?.takeIf { it.isNotEmpty() } ?: "unknown"
    val message = err?.optString("message")?.takeIf { it.isNotEmpty() }
        ?: "response.failed with no error detail"
    com.rikkaminis.app.logging.AppLogger.error(
        "OpenAIProvider",
        "Responses API response.failed — code=$code message=$message"
    )
    // [dv28 A-08] Same single-source mapping as the Chat
    // Completions inline-error arm: the symbolic code is turned
    // into HTTP semantics first, so `rate_limit_exceeded` becomes
    // RateLimited (back off / fall back to another route) instead
    // of the TransientError it used to share with server_error
    // (retry on the SAME model). Response body handed over is the
    // error object itself, whose `message` field is what
    // extractHttpErrorMessage reads.
    throw mapHttpError(
        openAiStreamErrorStatus(code, err?.optString("type", "") ?: ""),
        err?.toString() ?: event.toString(),
    )
}
type == "response.incomplete" -> {
    // [T-responses-terminal-events] The server ended the
    // response early; incomplete_details.reason is
    // "max_output_tokens" or "content_filter". Partial
    // output has already been streamed — surface WHY it
    // stopped instead of silently ending the stream.
    val reason = event.optJSONObject("response")
        ?.optJSONObject("incomplete_details")
        ?.optString("reason")?.takeIf { it.isNotEmpty() }
        ?: "unknown"
    com.rikkaminis.app.logging.AppLogger.error(
        "OpenAIProvider",
        "Responses API response.incomplete — reason=$reason"
    )
    throw LLMError.ProviderError(
        "Response ended incomplete (reason: $reason)" +
            if (reason == "max_output_tokens") {
                " — output hit max_output_tokens; raise the model's Max Output Tokens or shorten the request."
            } else ""
    )
}
type == "response.completed" -> {
    val resp = event.optJSONObject("response")
    val status = resp?.optString("status", "")
    // When the model emitted tool calls the API returns status=completed
    // with no stop_reason; surface "tool_use" so the agent loop knows to
    // dispatch the calls instead of treating the turn as final.
    val sawToolCalls = responsesToolCalls.isNotEmpty() ||
        (resp?.optJSONArray("output")?.let { out ->
            var found = false
            for (i in 0 until out.length()) {
                if (out.optJSONObject(i)?.optString("type") == "function_call") { found = true; break }
            }
            found
        } ?: false)
    finishReason = when {
        sawToolCalls -> "tool_use"
        status == "completed" -> "stop"
        else -> status
    }
    if (!sawFinishReason) {
        sawFinishReason = true
        // [T-codex-fast-mode] The response object inside
        // response.completed echoes the EFFECTIVE
        // service_tier — "priority" here is definitive
        // proof Fast Mode was honored; "default"/absent
        // means requested-but-downgraded (OpenAI silently
        // downgrades ineligible accounts). Mirrors iOS
        // 63a71146.
        val serviceTier = resp?.optString("service_tier", "")
            ?.takeIf { it.isNotEmpty() } ?: "n/a"
        com.rikkaminis.app.logging.AppLogger.info(
            "OpenAIProvider",
            "[T321] Responses finish_reason=$finishReason status=$status service_tier=$serviceTier contentLen=$contentLen reasoningLen=$reasoningLen toolCallAccumulators=${responsesToolCalls.size}"
        )
    }
    resp?.optJSONObject("usage")?.let { usage ->
        sawUsageBlock = true
        lastUsageJson = usage
        send(LLMStreamChunk.Usage(parseResponsesAPIUsage(usage)))
    }
}

            else -> {}
        }
    }

// Emit ToolCallComplete for all accumulated tool calls
for ((_, acc) in toolCallAccumulators) {
    if (acc.id.isNotEmpty() && acc.name.isNotEmpty()) {
        val args = try { JSONObject(acc.args.toString()) } catch (_: Exception) { JSONObject() }
        android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete id=${acc.id} name=${acc.name} args=${args.toString().take(300)}")
        send(LLMStreamChunk.ToolCallComplete(acc.id, acc.name, args))
    }
}
// Drain Responses-API tool accumulators that didn't get an output_item.done
// before the stream closed. Without this, mid-tool-call truncation (server
// closes connection while function_call_arguments is still streaming) leaves
// ChatViewModel.toolCalls empty: the agent loop sees no tool calls, exits,
// and the UI hangs with the tool thumbnail spinning while the stop button
// disappears (T247 root cause; same path hit by T237 DeepSeek truncation).
for ((itemId, acc) in responsesToolCalls) {
    if (acc.callId.isNotEmpty() && acc.name.isNotEmpty()) {
        val args = try { JSONObject(acc.args.toString()) } catch (_: Exception) { JSONObject() }
        val combined = combineResponsesAPIIds(acc.callId, itemId)
        com.rikkaminis.app.logging.AppLogger.warning(
            "OpenAIProvider",
            "Stream ended mid-tool-call id=$combined name=${acc.name} argsLen=${acc.args.length} — flushing as ToolCallComplete (T248)",
        )
        send(LLMStreamChunk.ToolCallComplete(combined, acc.name, args))
    }
}
responsesToolCalls.clear()

// [T-rhub1105-F06] The `[DONE]` branch emits ReasoningContent, but the
// EOF path (stream cut without `[DONE]`) did not — so a turn whose
// reasoning arrived and whose connection died lost its reasoning
// payload entirely (only ThinkingDelta was emitted). This is an
// INDEPENDENT payload, not a termination signal: it must be sent
// even when the EOF guard below decides there is nothing to finish.
if (!finishedSent && sawReasoningField) {
    send(LLMStreamChunk.ReasoningContent(reasoningAccum.toString()))
}

// [RC2-truncated-detection] EOF without [DONE] / finish_reason but
// with accumulated content — the model reply was cut mid-stream
// (connection drop / server truncation). The `[DONE]` branch above
// already signalled a (possibly truncated) finish; reaching here via
// EOF means NO Finished was emitted, which would otherwise let
// ChatViewModel treat the stub as a clean finish (finishedCleanly=true)
// and silently save a half answer. Signal truncated so the
// turnTruncated retry owns it. Only fires for non-empty EOF: a fully
// empty EOF is already handled by failOnSilentEmptyCompletion.
// [deep-audit-1018 ISSUE-01] `toolCallEventCount` joins the guard
// (Anthropic's `toolChars` counterpart — same-family 2nd site): a
// tool-call-only turn (zero text/reasoning) cut by EOF used to emit
// no Finished at all, so `turnTruncated` stayed false downstream and
// the truncated-tool-call guard never fired — a half-streamed
// argument object then reached the executor (and ToolJsonRepair
// would happily close it into a valid-but-wrong payload).
if (!finishedSent && (contentLen > 0 || reasoningLen > 0 || toolCallEventCount > 0)) {
    send(LLMStreamChunk.Finished(finishReason, truncated = !sawFinishReason))
}

    return out
}

// ======================= F-06 : Chat Completions path =======================

private fun chatStream(payloads: List<String>): List<LLMStreamChunk> {
    val out = mutableListOf<LLMStreamChunk>()
    fun send(c: LLMStreamChunk) { out.add(c) }

    val model = StubModel("stub-model")
    val responsesToolCalls = mutableMapOf<String, ResponsesToolCallAccumulator>()
    val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>()
    var finishReason: String? = null
    var sawFinishReason = false
    var sawReasoningField = false
    var sawReasoningDelta = false
    val reasoningAccum = StringBuilder()
    var contentLen = 0
    var reasoningLen = 0
    var toolCallEventCount = 0
    var sseEventCount = 0
    var finishedSent = false
    val hasThinkTags = true
    val thinkState = ThinkTagState()
    var lastUsageJson: JSONObject? = null
    var sawUsageBlock = false

    for (payload in payloads) {
if (payload == "[DONE]") {
    // Flush any remaining buffered content from think tag extraction
    if (hasThinkTags) {
        flushThinkTags(thinkState)?.let { remaining ->
            if (thinkState.insideTag) {
                send(LLMStreamChunk.ThinkingDelta(remaining))
            } else {
                send(LLMStreamChunk.Text(remaining))
            }
        }
    }
    if (sawReasoningField) {
        send(LLMStreamChunk.ReasoningContent(reasoningAccum.toString()))
    }
    send(LLMStreamChunk.Finished(finishReason, truncated = !sawFinishReason))
    finishedSent = true
    break
}

        val event = try { JSONObject(payload) } catch (e: Exception) { continue }
        val choices = event.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val choice = choices.getJSONObject(0)
            val delta = choice.optJSONObject("delta")
            contentLen += chatCompletionsDeltaText(delta).length
            reasoningLen += chatCompletionsDeltaThinkingBlocks(delta).sumOf { it.length }

            delta?.let { d ->
    val hasRcKey = d.has("reasoning_content")
    val hasReasoningKey = d.has("reasoning")
    if (hasRcKey || hasReasoningKey) {
        sawReasoningField = true
    }
    val rc = d.safeOptString("reasoning_content", "")
        .ifEmpty { d.safeOptString("reasoning", "") }
    if (rc.isNotEmpty()) {
        reasoningAccum.append(rc)
        if (!sawReasoningDelta) {
            sawReasoningDelta = true
            com.rikkaminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "Chat Completions: first reasoning_content delta arrived on ${model.id} — streaming Thinking content"
            )
        }
        send(LLMStreamChunk.ThinkingDelta(rc))
    }

    // [T-relay-host-adaptation] Mistral reasoning dialect:
    // reasoning arrives inside `delta.content[]` as
    // {"type":"thinking","thinking":[{"type":"text","text":"…"}]}
    // (NOT reasoning_content/reasoning). Absorbed from
    // RikkaHub's ChatCompletionsStreamDecoder.
    //
    // [R5-E1 E-C-01] The blocks come from the shared
    // [chatCompletionsDeltaThinkingBlocks] reader so the
    // [T321] counters above book exactly what is emitted
    // here — one reader, two consumers, no drift.
    for (thinkingText in chatCompletionsDeltaThinkingBlocks(d)) {
        reasoningAccum.append(thinkingText)
        // [T-rhub1105-F06] Mistral's `content[].type == "thinking"`
        // is a SERVER-NATIVE reasoning field — semantically the
        // same as `reasoning_content`, so it must arm the
        // ReasoningContent emitter. It is NOT the `<think>`
        // tag path (extractThinkTags), which deliberately does
        // NOT set this flag.
        sawReasoningField = true
        if (!sawReasoningDelta) {
            sawReasoningDelta = true
        }
        send(LLMStreamChunk.ThinkingDelta(thinkingText))
    }
}

            choice.safeOptString("finish_reason", "").let {
                if (it.isNotEmpty()) {
                    finishReason = it
                    if (!sawFinishReason) sawFinishReason = true
                }
            }
        }
    }

// Emit ToolCallComplete for all accumulated tool calls
for ((_, acc) in toolCallAccumulators) {
    if (acc.id.isNotEmpty() && acc.name.isNotEmpty()) {
        val args = try { JSONObject(acc.args.toString()) } catch (_: Exception) { JSONObject() }
        android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete id=${acc.id} name=${acc.name} args=${args.toString().take(300)}")
        send(LLMStreamChunk.ToolCallComplete(acc.id, acc.name, args))
    }
}
// Drain Responses-API tool accumulators that didn't get an output_item.done
// before the stream closed. Without this, mid-tool-call truncation (server
// closes connection while function_call_arguments is still streaming) leaves
// ChatViewModel.toolCalls empty: the agent loop sees no tool calls, exits,
// and the UI hangs with the tool thumbnail spinning while the stop button
// disappears (T247 root cause; same path hit by T237 DeepSeek truncation).
for ((itemId, acc) in responsesToolCalls) {
    if (acc.callId.isNotEmpty() && acc.name.isNotEmpty()) {
        val args = try { JSONObject(acc.args.toString()) } catch (_: Exception) { JSONObject() }
        val combined = combineResponsesAPIIds(acc.callId, itemId)
        com.rikkaminis.app.logging.AppLogger.warning(
            "OpenAIProvider",
            "Stream ended mid-tool-call id=$combined name=${acc.name} argsLen=${acc.args.length} — flushing as ToolCallComplete (T248)",
        )
        send(LLMStreamChunk.ToolCallComplete(combined, acc.name, args))
    }
}
responsesToolCalls.clear()

// [T-rhub1105-F06] The `[DONE]` branch emits ReasoningContent, but the
// EOF path (stream cut without `[DONE]`) did not — so a turn whose
// reasoning arrived and whose connection died lost its reasoning
// payload entirely (only ThinkingDelta was emitted). This is an
// INDEPENDENT payload, not a termination signal: it must be sent
// even when the EOF guard below decides there is nothing to finish.
if (!finishedSent && sawReasoningField) {
    send(LLMStreamChunk.ReasoningContent(reasoningAccum.toString()))
}

// [RC2-truncated-detection] EOF without [DONE] / finish_reason but
// with accumulated content — the model reply was cut mid-stream
// (connection drop / server truncation). The `[DONE]` branch above
// already signalled a (possibly truncated) finish; reaching here via
// EOF means NO Finished was emitted, which would otherwise let
// ChatViewModel treat the stub as a clean finish (finishedCleanly=true)
// and silently save a half answer. Signal truncated so the
// turnTruncated retry owns it. Only fires for non-empty EOF: a fully
// empty EOF is already handled by failOnSilentEmptyCompletion.
// [deep-audit-1018 ISSUE-01] `toolCallEventCount` joins the guard
// (Anthropic's `toolChars` counterpart — same-family 2nd site): a
// tool-call-only turn (zero text/reasoning) cut by EOF used to emit
// no Finished at all, so `turnTruncated` stayed false downstream and
// the truncated-tool-call guard never fired — a half-streamed
// argument object then reached the executor (and ToolJsonRepair
// would happily close it into a valid-but-wrong payload).
if (!finishedSent && (contentLen > 0 || reasoningLen > 0 || toolCallEventCount > 0)) {
    send(LLMStreamChunk.Finished(finishReason, truncated = !sawFinishReason))
}

    return out
}

// ======================= production declarations / helpers (extracted) ======

data class ToolCallAccumulator(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var started: Boolean = false, var lastLoggedInputChars: Int = -1)

internal data class ResponsesToolCallAccumulator(
    var callId: String = "",
    var name: String = "",
    val args: StringBuilder = StringBuilder(),
    var started: Boolean = false,
    var lastLoggedInputChars: Int = -1,
    /** Server item id (`fc_…`); empty when the relay did not send one. */
    var itemId: String = "",
    /** Position of the item in the response output array; -1 when unknown. */
    var outputIndex: Int = -1,
)

/**
 * [T-rhub1105-F01] Single lookup rule for a Responses tool-call accumulator.
 *
 * Priority: `item_id` → `call_id` → `output_index`. The first two are exact
 * identity; the third is positional and therefore only consulted when the
 * server did not identify the item at all.
 */

internal fun resolveResponsesAccumulator(
    accumulators: Collection<ResponsesToolCallAccumulator>,
    itemId: String,
    callId: String,
    outputIndex: Int,
): ResponsesToolCallAccumulator? =
    accumulators.firstOrNull { itemId.isNotEmpty() && it.itemId == itemId }
        ?: accumulators.firstOrNull { callId.isNotEmpty() && it.callId == callId }
        ?: accumulators.firstOrNull { outputIndex >= 0 && it.outputIndex == outputIndex }

private fun combineResponsesAPIIds(callId: String, fcId: String): String =
    if (fcId.isEmpty()) callId else "$callId|$fcId"

private fun mapHttpError(statusCode: Int, body: String, retryAfterMs: Long? = null): LLMError {
    if (statusCode == 401 || statusCode == 403) return LLMError.InvalidApiKey()
    if (statusCode == 429) return LLMError.RateLimited(retryAfterMs = retryAfterMs)

    val message = "[$statusCode] ${extractHttpErrorMessage(body)}"

    val transientCodes = setOf(500, 502, 503, 504, 529)
    if (statusCode in transientCodes) {
        // 503 with permanent failure indicators → ProviderError (trigger group fallback)
        if (statusCode == 503 && (body.contains("no_available_providers") || body.contains("model_not_found"))) {
            return LLMError.ProviderError(message)
        }
        return LLMError.TransientError(message)
    }
    return LLMError.ProviderError(message)
}

private fun parseChatCompletionsUsage(usage: JSONObject): LLMUsage {
    val promptTokens = usage.optInt("prompt_tokens", 0)
    // DeepSeek reports cache hits at `usage.prompt_cache_hit_tokens` instead
    // of the OpenAI-native `prompt_tokens_details.cached_tokens`. Mirrors
    // iOS OpenAIProvider.swift:629-630. Without this fallback, DeepSeek V4
    // looked like it never cached even when it did, masking T122's win.
    val cacheRead = usage.optJSONObject("prompt_tokens_details")
        ?.optInt("cached_tokens")?.takeIf { it > 0 }
        ?: usage.optInt("prompt_cache_hit_tokens", 0).takeIf { it > 0 }
    // OpenAI/DeepSeek `prompt_tokens` is the FULL input (cached + fresh), so
    // subtract the cached portion to keep `inputTokens` meaning fresh-only —
    // matching the Anthropic convention. Otherwise the cached tokens are
    // counted twice in `input + cacheRead` (deflates the cache-hit rate;
    // DeepSeek 99% hit showed as ~48%). Guard: only subtract when it stays
    // non-negative; no cache field (cacheRead == null) → unchanged.
    // latestContextTokens stays the full prompt (that IS the context size).
    val freshInput = cacheRead?.let { (promptTokens - it).takeIf { d -> d >= 0 } } ?: promptTokens
    return LLMUsage(
        inputTokens = freshInput,
        outputTokens = usage.optInt("completion_tokens", 0),
        cacheReadInputTokens = cacheRead,
        latestContextTokens = promptTokens,
    )
}

private fun parseResponsesAPIUsage(usage: JSONObject): LLMUsage {
    val inputTokens = usage.optInt("input_tokens", 0)
    // Responses API reports cache hits at `input_tokens_details.cached_tokens`
    // (distinct from Chat Completions' `prompt_tokens_details.cached_tokens`).
    // Mirrors iOS OpenAIProvider.swift:640. Without this, even a perfectly
    // cached Responses-API request showed cacheRead=0 in usage stats —
    // making T126's prompt_cache_key wiring look like it had no effect.
    val cacheRead = usage.optJSONObject("input_tokens_details")
        ?.optInt("cached_tokens")?.takeIf { it > 0 }
    // `input_tokens` is the FULL input (cached subset included); subtract the
    // cached portion so `inputTokens` is fresh-only, matching Anthropic — else
    // the cache is counted twice in `input + cacheRead` (deflates hit rate).
    // Guard: only subtract when non-negative; no cache field → unchanged.
    // latestContextTokens stays the full input (that IS the context size).
    val freshInput = cacheRead?.let { (inputTokens - it).takeIf { d -> d >= 0 } } ?: inputTokens
    return LLMUsage(
        inputTokens = freshInput,
        outputTokens = usage.optInt("output_tokens", 0),
        cacheReadInputTokens = cacheRead,
        latestContextTokens = inputTokens,
    )
}

private data class ThinkExtractResult(val visible: String, val thinking: String)

private class ThinkTagState {
    var buffer = StringBuilder()
    var insideTag = false
    var currentFormat: ThinkTagDef? = null

    fun reset() {
        buffer = StringBuilder()
        insideTag = false
        currentFormat = null
    }
}

private fun extractThinkTags(text: String, state: ThinkTagState): ThinkExtractResult {
    state.buffer.append(text)
    val result = scanThinkTags(state.buffer.toString(), state.insideTag, state.currentFormat, THINK_TAG_FORMATS)
    state.buffer = StringBuilder(result.remainingBuffer)
    state.insideTag = result.insideTag
    state.currentFormat = result.currentFormat
    return ThinkExtractResult(result.visible, result.thinking)
}

private fun flushThinkTags(state: ThinkTagState): String? {
    if (state.buffer.isEmpty()) return null
    val remaining = state.buffer.toString()
    state.buffer = StringBuilder()
    return remaining
}

internal fun chatCompletionsDeltaText(delta: JSONObject?): String {
    if (delta == null) return ""
    if (delta.isNull("content")) return ""
    val array = delta.optJSONArray("content") ?: return delta.optString("content", "")
    val out = StringBuilder()
    for (i in 0 until array.length()) {
        val block = array.optJSONObject(i) ?: continue
        if (block.optString("type", "") != "text") continue
        out.append(block.optString("text", ""))
    }
    return out.toString()
}

internal fun chatCompletionsDeltaThinkingBlocks(delta: JSONObject?): List<String> {
    val array = delta?.optJSONArray("content") ?: return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until array.length()) {
        val block = array.optJSONObject(i) ?: continue
        if (block.optString("type", "") != "thinking") continue
        val thinking = block.optJSONArray("thinking") ?: continue
        for (j in 0 until thinking.length()) {
            val item = thinking.optJSONObject(j) ?: continue
            val text = item.safeOptString("text", "")
            if (text.isNotEmpty()) out.add(text)
        }
    }
    return out
}

// ======================= fixtures =======================

private var failures = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("PASS  $name")
    else { failures++; println("FAIL  $name ${if (detail.isNotEmpty()) "— $detail" else ""}") }
}

private fun toolCompletes(chunks: List<LLMStreamChunk>) =
    chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>()

// --- F-01a: empty `arguments` on output_item.done must not wipe the buffer ----
private fun f01EmptyArgumentsAtDone() {
    val payloads = listOf(
        """{"type":"response.output_item.added","item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"shell","arguments":""}}""",
        """{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\"command\":\"pwd\"}"}""",
        """{"type":"response.output_item.done","item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"shell","arguments":""}}""",
        "[DONE]",
    )
    val completes = toolCompletes(responsesStream(payloads))
    check("F-01a one ToolCallComplete emitted", completes.size == 1, "got ${completes.size}")
    check(
        "F-01a args survive empty arguments on output_item.done",
        completes.firstOrNull()?.args?.optString("command") == "pwd",
        "args=${completes.firstOrNull()?.args}",
    )
}

// --- F-01b: `response.function_call_arguments.done` is authoritative ----------
private fun f01ArgumentsDoneArm() {
    val payloads = listOf(
        """{"type":"response.output_item.added","item":{"id":"fc_2","type":"function_call","call_id":"call_2","name":"shell","arguments":""}}""",
        """{"type":"response.function_call_arguments.done","item_id":"fc_2","call_id":"call_2","output_index":0,"arguments":"{\"command\":\"ls\"}"}""",
        "[DONE]",
    )
    val completes = toolCompletes(responsesStream(payloads))
    check(
        "F-01b function_call_arguments.done arms the call",
        completes.any { it.args.optString("command") == "ls" },
        "completes=${completes.map { it.args.toString() }}",
    )
}

// --- F-01c: optional item.id (call_id fallback) ------------------------------
private fun f01MissingItemId() {
    val payloads = listOf(
        """{"type":"response.output_item.added","item":{"type":"function_call","call_id":"call_3","name":"shell","arguments":""}}""",
        """{"type":"response.function_call_arguments.delta","call_id":"call_3","output_index":0,"delta":"{\"command\":\"whoami\"}"}""",
        """{"type":"response.output_item.done","item":{"type":"function_call","call_id":"call_3","name":"shell","arguments":"{\"command\":\"whoami\"}"}}""",
        "[DONE]",
    )
    val completes = toolCompletes(responsesStream(payloads))
    check(
        "F-01c item without id still yields ToolCallComplete(call_3)",
        completes.any { it.id.startsWith("call_3") && it.args.optString("command") == "whoami" },
        "completes=${completes.map { it.id to it.args.toString() }}",
    )
}

// --- F-01d: no output_item.done at all → EOF drain must still emit -----------
private fun f01EofDrain() {
    val payloads = listOf(
        """{"type":"response.output_item.added","item":{"id":"fc_4","type":"function_call","call_id":"call_4","name":"shell","arguments":""}}""",
        """{"type":"response.function_call_arguments.delta","item_id":"fc_4","delta":"{\"command\":\"id\"}"}""",
    )
    val completes = toolCompletes(responsesStream(payloads))
    check(
        "F-01d EOF drain emits the half-finished call",
        completes.any { it.args.optString("command") == "id" },
        "completes=${completes.map { it.args.toString() }}",
    )
}

// --- F-02: top-level type:"error" frame must map to a real LLMError ----------
private fun f02InlineErrorFrame() {
    val payloads = listOf(
        """{"type":"error","error":{"message":"Quota exceeded","code":"insufficient_quota"}}""",
        "[DONE]",
    )
    var thrown: LLMError? = null
    var chunks: List<LLMStreamChunk> = emptyList()
    try {
        chunks = responsesStream(payloads)
    } catch (e: LLMError) {
        thrown = e
    }
    check("F-02 error frame throws instead of finishing silently", thrown != null,
        "chunks=${chunks.map { it::class.simpleName }}")
    check("F-02 mapped to the rate-limit family (not TransientError)",
        thrown is LLMError.RateLimited, "thrown=${thrown?.let { it::class.simpleName }}")
    check("F-02 no Finished emitted after the error frame",
        chunks.none { it is LLMStreamChunk.Finished })
}

// --- F-06a: Mistral content[].type=="thinking" must set sawReasoningField ----
private fun f06MistralThinkingBlocks() {
    val payloads = listOf(
        """{"choices":[{"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"step 1"}]}]}}]}""",
        """{"choices":[{"delta":{"content":"answer"},"finish_reason":"stop"}]}""",
        "[DONE]",
    )
    val reasoning = chatStream(payloads).filterIsInstance<LLMStreamChunk.ReasoningContent>()
    check("F-06a ReasoningContent emitted for Mistral thinking blocks",
        reasoning.any { it.content == "step 1" },
        "got=${reasoning.map { it.content }}")
}

// --- F-06b: scalar reasoning_content + EOF (no [DONE]) must still emit -------
private fun f06ScalarReasoningAtEof() {
    val payloads = listOf(
        """{"choices":[{"delta":{"reasoning_content":"think"}}]}""",
        """{"choices":[{"delta":{"content":"hi"},"finish_reason":"stop"}]}""",
    )
    val reasoning = chatStream(payloads).filterIsInstance<LLMStreamChunk.ReasoningContent>()
    check("F-06b ReasoningContent emitted on EOF without [DONE]",
        reasoning.any { it.content == "think" },
        "got=${reasoning.map { it.content }}")
}

fun main() {
    f01EmptyArgumentsAtDone()
    f01ArgumentsDoneArm()
    f01MissingItemId()
    f01EofDrain()
    f02InlineErrorFrame()
    f06MistralThinkingBlocks()
    f06ScalarReasoningAtEof()
    println(if (failures == 0) "ALL PASS" else "$failures FAILURE(S)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
