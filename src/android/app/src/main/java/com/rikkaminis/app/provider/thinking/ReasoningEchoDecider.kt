package com.rikkaminis.app.provider.thinking

/**
 * [T-deepseek-v4-thinking-echo] Decides whether one assistant message carries the
 * echoed thinking field, given the rule's declared [ReasoningEchoPolicy].
 *
 * WHY THIS EXISTS — the 2026-09-18 field report:
 *   `[400] The content[].thinking in the thinking mode must be passed back to the API`
 *   (`deepseek-v4-flash` behind an OpenAI-compatible relay).
 *
 * The rule table had declared `ReasoningEchoPolicy("reasoning_content",
 * AFTER_TOOL_USE_ONLY)` for `*deepseek-v4*` since the Phase-2 port, but the OpenAI
 * request builder still gated the echo on the LEGACY `includeReasoning` flag, which is
 * derived from the LOCAL thinking level:
 *
 *   includeReasoning = (thinkingLevel.isEnabled || model.supportsReasoning == true) && …
 *   placeholderAllowed = includeReasoning && thinkingLevel != AUTO
 *
 * So on a relay whose upstream is in thinking mode no matter what the client asked for
 * (DeepSeek V4 via the Anthropic-compatible endpoint thinks BY DEFAULT), two local
 * settings dropped the field entirely:
 *   • thinking OFF  → no echo at all for any turn;
 *   • thinking AUTO → captured reasoning still echoed, but a turn with nothing captured
 *     got no placeholder (deliberate, T-thinking-auto-level).
 * Either way a tool-call turn reached the vendor without `reasoning_content` and was
 * rejected on the NEXT request. A field is the requirement, not the value: the community
 * workaround for the same 400 is to append an EMPTY `reasoning_content` to assistant
 * messages that lack it, and DeepSeek's own OpenAI surface emits `""` on non-thinking
 * turns — which is why the placeholder is a valid answer rather than a lie.
 *
 * This is deliberately a pure function of (policy, turn shape, flags): the provider
 * stays the only place that touches the wire, and the truth table is JVM-testable
 * without Android.
 *
 * NOT a general "always echo" switch: `null` policy keeps the legacy decision
 * byte-for-byte, and [ReasoningEchoPolicy.Timing.NEVER] (Mistral — a closed schema that
 * 422s on extra fields) still wins over everything.
 */
object ReasoningEchoDecider {

    /** What the caller should put on the wire for one assistant message. */
    enum class Action {
        /** Write the captured reasoning verbatim (including `""`). */
        CAPTURED,

        /** Write an empty string — field presence without a value to imitate. */
        PLACEHOLDER,

        /** Write no reasoning field at all. */
        OMIT,
    }

    /**
     * @param policy the matched rule's echo requirement; null = rule has no opinion and
     *        the legacy gates below decide.
     * @param hasToolCalls the turn carries `tool_calls` — the shape DeepSeek documents
     *        as the one that MUST echo.
     * @param captured `LLMMessage.reasoningContent`, already round-tripped exactly as the
     *        server emitted it (may be `""`).
     * @param legacyGate today's `includeReasoning`.
     * @param legacyPlaceholder today's `placeholderAllowed`.
     */
    fun decide(
        policy: ReasoningEchoPolicy?,
        hasToolCalls: Boolean,
        captured: String?,
        legacyGate: Boolean,
        legacyPlaceholder: Boolean,
    ): Action = when (policy?.timing) {
        null -> legacy(captured, legacyGate, legacyPlaceholder)
        ReasoningEchoPolicy.Timing.NEVER -> Action.OMIT
        // DeepSeek's documented rule: only tool-call turns must echo. Non-tool turns
        // keep the legacy decision so every other model's wire output is unchanged.
        ReasoningEchoPolicy.Timing.AFTER_TOOL_USE_ONLY ->
            if (hasToolCalls) echoUnconditionally(captured)
            else legacy(captured, legacyGate, legacyPlaceholder)
        // Gateways that validate once thinking is ACTIVE (nous): gate the whole rule on
        // the legacy capture gate, but never on the AUTO/placeholder distinction.
        ReasoningEchoPolicy.Timing.EVERY_TURN ->
            if (legacyGate) echoUnconditionally(captured)
            else legacy(captured, legacyGate, legacyPlaceholder)
    }

    private fun echoUnconditionally(captured: String?): Action =
        if (captured != null) Action.CAPTURED else Action.PLACEHOLDER

    private fun legacy(captured: String?, gate: Boolean, placeholder: Boolean): Action = when {
        !gate -> Action.OMIT
        captured != null -> Action.CAPTURED
        placeholder -> Action.PLACEHOLDER
        else -> Action.OMIT
    }
}
