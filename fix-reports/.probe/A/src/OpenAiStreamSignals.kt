package com.rikkaminis.app.provider.openai

/**
 * [dv28 A-01] SINGLE definition of "this SSE payload represents tool-call
 * activity", shared by both OpenAI streaming flavours.
 *
 * The EOF truncation guard in `OpenAIProvider.rawStreamMessage` emits a
 * `Finished(truncated = true)` when the stream dies without `[DONE]` **and**
 * something was accumulated. Its tool-call arm is fed by a turn-level counter,
 * and that counter used to be incremented only from the Chat Completions
 * `choices[0].delta.tool_calls` shape — the Responses API announces tool calls
 * through `response.output_item.added` + `response.function_call_arguments.delta`
 * instead, so a tool-call-ONLY Responses turn cut by EOF produced
 * `contentLen == reasoningLen == toolCallEventCount == 0`, no Finished at all,
 * and a half-streamed argument object reached the executor (`turnTruncated`
 * stayed false downstream, so `TruncatedToolCallPolicy` never fired).
 *
 * Both flavours therefore ask these functions how much progress an event
 * represents, and add the answer to the SAME counter. Keeping the mapping here
 * (pure, Android-free) is what makes it testable: the SSE loop itself can
 * never be executed without the Android runtime.
 *
 * Why a weight instead of a boolean: the Chat Completions shape can announce
 * several calls in one event, and the pre-existing counter was documented as an
 * EVENT count (it feeds the `[T321] toolCallEvents=` triage line). Returning the
 * announced count keeps that reading intact while the guard only ever compares
 * against zero.
 */
internal fun chatCompletionsToolCallEventWeight(toolCallsInDelta: Int): Int =
    if (toolCallsInDelta > 0) toolCallsInDelta else 0

/** Responses-API counterpart of [chatCompletionsToolCallEventWeight]. */
internal fun responsesToolCallEventWeight(
    eventType: String,
    itemType: String?,
    argsDeltaLen: Int,
): Int = when {
    // The item announcement is the FIRST proof that this turn produced a tool
    // call — it arrives before any argument delta, so a server that cuts the
    // connection right after it must still be read as tool-call progress.
    eventType == "response.output_item.added" && itemType == "function_call" -> 1
    // ...and the authoritative final item repeats the same fact.
    eventType == "response.output_item.done" && itemType == "function_call" -> 1
    // Argument deltas: an empty delta carries no progress (some relays emit a
    // keep-alive delta with an empty string).
    eventType == "response.function_call_arguments.delta" -> if (argsDeltaLen > 0) 1 else 0
    else -> 0
}

/**
 * [dv28 A-08] HTTP-semantics status for an error that arrived INSIDE a 200
 * stream, derived from the tokens relays actually put there.
 *
 * Both flavours used to lose the family: Chat Completions did
 * `inlineError.optInt("code", 0)` (a STRING code — `"rate_limit_exceeded"`, or
 * a `type`-only payload — coerces to 0) and then `mapHttpError(0, …)` fell
 * through to the final `ProviderError`, i.e. "not retryable, not fallbackable";
 * the Responses arm hard-coded `rate_limit_exceeded` into `TransientError`, so a
 * throttled request retried on the SAME model instead of backing off / falling
 * back. Both now produce a status and go through the one `mapHttpError`
 * decision table, the same way `anthropicStreamErrorStatus` already does for
 * Anthropic.
 *
 * @param codeToken the event's `error.code` (numeric `"429"` on some relays,
 *   symbolic `"rate_limit_exceeded"` on others) — empty when absent.
 * @param typeToken the event's `error.type` / `error.status`, consulted only
 *   when [codeToken] carries no family.
 * @return an HTTP status code, or 0 when nothing is recognisable — `mapHttpError(0)`
 *   keeps the historical `ProviderError` outcome for unknown payloads.
 */
internal fun openAiStreamErrorStatus(codeToken: String, typeToken: String): Int {
    // A relay that sends a real numeric code keeps it verbatim.
    val numeric = codeToken.trim().toIntOrNull()
    if (numeric != null && numeric in 400..599) return numeric
    openAiErrorFamilyStatus(codeToken)?.let { return it }
    openAiErrorFamilyStatus(typeToken)?.let { return it }
    return 0
}

/**
 * Prefix-family mapping, mirroring [anthropicStreamErrorStatus]'s intent (map
 * the provider's symbolic token onto the HTTP semantics `mapHttpError` already
 * knows) — the vocabulary differs, the target table does not.
 *
 * `insufficient_quota` is deliberately 429 (→ `RateLimited`, i.e. fall back to
 * another key/model) rather than "no status": an exhausted quota on THIS key is
 * exactly "this route cannot help right now", and `ProviderError` would have
 * made it permanently unrecoverable.
 */
internal fun openAiErrorFamilyStatus(token: String): Int? {
    val t = token.trim().lowercase()
    if (t.isEmpty()) return null
    return when {
        t.startsWith("rate_limit") -> 429
        t == "insufficient_quota" || t == "quota_exceeded" -> 429
        t.startsWith("authentication") || t.startsWith("invalid_api_key") -> 401
        t.startsWith("permission") -> 403
        t.startsWith("not_found") || t == "model_not_found" -> 404
        t.startsWith("context_length") || t.startsWith("invalid_request") ||
            t.startsWith("invalid_prompt") -> 400
        t == "server_error" || t == "api_error" || t == "overloaded_error" ||
            t.startsWith("internal") -> 500
        else -> null
    }
}
