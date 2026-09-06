package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider

// [FE-5 batch 7] Error handling / token sizing cluster extracted verbatim from
// ChatViewModel as extension functions (same pattern as ChatPromptAndTools).

/**
 * Unwrap exceptions thrown inside callbackFlow.
 * callbackFlow wraps internal throws into CancellationException(cause=original).
 * This extracts the original LLMError if present.
 */
/**
 * Sanitize agentHistory before each API call to ensure tool_use/tool_result pairing.
 * Mirrors iOS AIChatViewModel pre-API validation.
 *
 * Ensures: every assistant message with tool_use is immediately followed by a user
 * message containing the matching tool_result(s). Handles:
 * - Duplicate tool IDs across messages (from provider fallback/retry)
 * - Orphaned tool_use without any tool_result
 * - Orphaned tool_result without matching tool_use
 * - Assistant text after tool_use in the same message (Anthropic rejects this)
 */
internal fun ChatViewModel.sanitizeAgentHistory() {
    sanitizeAgentHistoryMessages(agentHistory)
}

/**
 * [T-compact-slice-tool-pairing] Core tool_use/tool_result pairing repair,
 * extracted from [sanitizeAgentHistory] so it can also be applied to the
 * compacted-slice result returned by [effectiveAgentHistory]. The compact
 * slice (walkBack cap / preAnchor prune / postAnchor splice) can split a
 * tool round across the boundary, leaving an orphan tool_result whose
 * tool_use was cut off — the API then rejects the request with
 * "Messages with role 'tool' must be a response to a preceding message
 * with 'tool_calls'". Running the same repair on the FINAL outgoing slice
 * closes that gap regardless of where the boundary lands.
 *
 * Mirrors iOS AIChatViewModel pre-API validation. Ensures: every assistant
 * message with tool_use is immediately followed by a user message with the
 * matching tool_result(s). Handles:
 * - Duplicate tool IDs across messages (from provider fallback/retry)
 * - Orphaned tool_use without any tool_result
 * - Orphaned tool_result without matching tool_use
 * - Assistant text after tool_use in the same message (Anthropic rejects this)
 */

internal fun ChatViewModel.unwrapFlowException(e: Throwable): Throwable {
    var cause: Throwable? = e
    while (cause != null) {
        if (cause is com.openminis.app.data.model.LLMError) return cause
        cause = cause.cause
    }
    return e
}

/**
 * [T-error-no-permanent-scars] Uniform terminal-error reporter for every
 * runAgentLoop call site (send / retryLast / resume / queued-drain). Shows
 * a human summary on the banner and keeps the raw error text (fallback
 * trail, original error codes) in the collapsed `errorDetail` disclosure.
 */
internal fun ChatViewModel.reportAgentLoopError(e: Exception) {
    if (e is com.openminis.app.data.model.FallbackExhaustedError) {
        setInlineError(e.summary, e.detail)
    } else {
        val errActual = unwrapFlowException(e)
        // [fix/stream-error-silent-recovery] A stream interruption reaching the
        // banner means auto-retry AND fallback both failed — show a human
        // summary, not the raw "Stream error" wrap message. The original
        // failure stays visible in logcat (Minis.ModelExecution* tags).
        val errSummary = when (errActual) {
            is com.openminis.app.sandbox.offload.ModelStreamErrorException ->
                context.getString(R.string.error_stream_interrupted)
            is com.openminis.app.data.model.LLMError -> errActual.userMessage
            else -> errActual.message?.takeIf { it.isNotBlank() } ?: "Unknown error"
        }
        setInlineError(errSummary, errActual.message)
    }
}

/**
 * Compute max output tokens that fits within the remaining context window.
 * Logic mirrors iOS's dynamicMaxTokens():
 *   result = min(provider.defaultMaxTokens, max(contextWindow - inputTokens, MIN_MAX_TOKENS))
 *
 * @param provider The current LLM provider (carries defaultMaxTokens).
 * @param lastContextTokens API-reported input token count from the last call (0 = first call).
 */
internal fun ChatViewModel.dynamicMaxTokens(provider: LLMProvider, lastContextTokens: Int = 0): Int {
    val model = currentModel ?: return minOf(ChatViewModel.GLOBAL_MAX_TOKENS_CEILING, provider.defaultMaxOutputTokens)
    // Ceiling: min(global cap, model.maxOutputTokens-or-provider-default).
    // The global cap means we never send more than 128K regardless of
    // what the model claims it can output.
    val maxOutputCeiling = minOf(ChatViewModel.GLOBAL_MAX_TOKENS_CEILING, provider.effectiveMaxOutputTokens(model))
    // [T-context-window-sources] Single source of truth for the context
    // window: route through effectiveContextWindowTokens() (group-priority
    // when the model window is heuristic, minOf clamp when explicit) so
    // output sizing uses the SAME window as offload/trim/block — not the
    // raw model guess, which for a 1M model silently reported as 128K
    // would cap output alongside capping the budget. Falls back to the
    // model's own window when effective resolution fails (no live model).
    val contextWindow = effectiveContextWindowTokens() ?: model.contextWindowTokens
    if (contextWindow <= 0) return maxOutputCeiling
    val inputTokens = if (lastContextTokens > 0) lastContextTokens else 0
    val remaining = contextWindow - inputTokens
    val clamped = maxOf(remaining, ChatViewModel.MIN_MAX_TOKENS)
    val result = minOf(maxOutputCeiling, clamped)
    if (result < maxOutputCeiling) {
        android.util.Log.i(ChatViewModel.TAG, "dynamicMaxTokens: $result (remaining=$remaining, ceiling=$maxOutputCeiling, window=$contextWindow, input=$inputTokens, model=${model.id})")
    }
    return result
}
