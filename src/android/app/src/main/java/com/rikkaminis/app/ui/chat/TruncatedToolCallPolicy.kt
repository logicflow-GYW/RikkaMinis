package com.rikkaminis.app.ui.chat

/**
 * [T-truncated-tool-call-guard] Truncated-turn tool-call guard.
 *
 * finish_reason = length / max_tokens means the provider cut the turn off at
 * the output ceiling. The *visible text* of such a turn is already handled by
 * the length-wall continuation path — but a turn that ALSO carries tool calls
 * was, until this guard, dispatched straight to the executors. Root cause:
 * `turnFinishReason` is only ever consumed inside the `toolCalls.isEmpty()`
 * branch of runAgentLoop, while the dispatch site (`dispatching N tool
 * call(s)`) sits outside it. A tool call whose arguments were still streaming
 * when the ceiling hit therefore reached the tool with whatever prefix had
 * arrived.
 *
 * [ToolJsonRepair] sharpens this rather than softening it: its truncation
 * strategy deliberately closes a cut-off JSON object ("if args is empty but
 * rawTail looks like a JSON object that just got cut, retry parsing with a
 * small set of closure suffixes appended"). Closing `{"path": "/data/local/
 * tmp/fo` yields a syntactically valid, semantically WRONG argument — exactly
 * the silent-failure class this project treats as worse than a loud error.
 * A refused call costs one re-planned turn; an executed truncated call costs
 * an unknown side effect.
 *
 * Mirrors [ContentFilterFinishPolicy]'s shape: string-in, verdict-out, pure,
 * JVM-testable, and conservative by default — a null or unrecognised reason is
 * NEVER treated as truncated, so only spellings we actually know can block a
 * call.
 */
object TruncatedToolCallPolicy {

    /**
     * Finish reasons meaning "the provider stopped at the output ceiling",
     * across every protocol this app speaks. Compared case-insensitively
     * (GeminiProvider.extractFinishReason already lowercases its values;
     * Anthropic emits snake_case `max_tokens`; OpenAI emits `length`).
     */
    private val TRUNCATED_REASONS = setOf(
        // OpenAI Chat Completions
        "length",
        // OpenAI Responses / newer surfaces
        "max_output_tokens",
        // Anthropic Messages
        "max_tokens",
        // Relay + normalisation variants observed across OpenAI-compatible gates
        "maxtokens",
        "max_tokens_exceeded",
        "token_limit",
        "output_limit",
    )

    /**
     * True when the turn was cut off at the output ceiling. Null, blank and
     * unknown reasons return false (conservative: never block on a reason we
     * do not recognise).
     */
    fun isTruncatedFinish(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return false
        return reason.trim().lowercase() in TRUNCATED_REASONS
    }

    /**
     * The synthetic tool result to hand back instead of executing, or null
     * when the call may run normally. Keeping this in one place means the
     * guard site and its test share the same wording and the same trigger.
     */
    fun rejectionReason(toolName: String, finishReason: String?): String? {
        if (!isTruncatedFinish(finishReason)) return null
        return "[TRUNCATED] Tool '$toolName' was NOT executed: the model reached " +
            "the output limit (${finishReason?.trim()}) while this turn was still " +
            "streaming, so the arguments may be an incomplete prefix. Re-issue " +
            "the call with the full arguments."
    }
}
