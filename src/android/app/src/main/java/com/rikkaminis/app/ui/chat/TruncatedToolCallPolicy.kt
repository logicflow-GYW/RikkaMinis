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
 *
 * [deep-audit-1018 ISSUE-01] finish_reason is only ONE of the two ways a turn
 * gets cut. A stream that dies mid-flight (network EOF / relay drop) carries
 * no finish_reason at all — the providers surface that as
 * [LLMStreamChunk.Finished.truncated] instead. Both sources are accepted by
 * [rejectionReason]; the flag parameter defaults to `false` so existing
 * finishReason-only call sites keep their exact behavior.
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
        // [FIX-FW-NONSTREAM C2] `-` is normalised to `_` before the lookup: the
        // table below already owns the underscore spellings, and a relay that
        // re-joins the words ("max-tokens", "max-output-tokens") is the same
        // condition. Widening here — rather than in a second table at the
        // provider layer — keeps one home for the ceiling vocabulary.
        return reason.trim().lowercase().replace('-', '_') in TRUNCATED_REASONS
    }

    /**
     * The synthetic tool result to hand back instead of executing, or null
     * when the call may run normally. Keeping this in one place means the
     * guard site and its test share the same wording and the same trigger.
     *
     * [deep-audit-1018 ISSUE-01] TWO truncation sources, not one:
     *  - [finishReason] — the provider itself declared the ceiling
     *    (finish_reason=length / max_tokens). The original (and only) source.
     *  - [turnTruncated] — the stream-level flag the providers raise when the
     *    stream ended WITHOUT a finish_reason while content (text, reasoning
     *    OR tool-call events) was still being produced (network EOF /
     *    connection drop / relay cut). OpenAIProvider only started raising it
     *    for tool-call-only turns when its EOF guard learned to count
     *    `toolCallEventCount`; a tool-call turn cut by EOF used to reach the
     *    executor with a half-streamed argument object, because the dispatch
     *    path consulted finishReason alone.
     *
     * When BOTH hold the ceiling wording wins (it names the provider's own
     * reason, the more specific diagnostic). The prefix `[TRUNCATED] Tool '…'
     * was NOT executed:` is stable across both sources — callers and tests
     * grep it; only the explanation clause differs.
     */
    fun rejectionReason(toolName: String, finishReason: String?, turnTruncated: Boolean = false): String? {
        val ceilingDeclared = isTruncatedFinish(finishReason)
        if (!ceilingDeclared && !turnTruncated) return null
        if (ceilingDeclared) {
            return "[TRUNCATED] Tool '$toolName' was NOT executed: the model reached " +
                "the output limit (${finishReason?.trim()}) while this turn was still " +
                "streaming, so the arguments may be an incomplete prefix. Re-issue " +
                "the call with the full arguments."
        }
        return "[TRUNCATED] Tool '$toolName' was NOT executed: the stream ended " +
            "mid-turn (network EOF / connection dropped before a finish reason " +
            "arrived) while this call's arguments were still streaming, so they " +
            "may be an incomplete prefix. Re-issue the call with the full arguments."
    }
}
