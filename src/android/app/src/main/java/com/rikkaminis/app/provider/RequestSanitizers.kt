package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage

/**
 * Defense-in-depth sanitizers for outbound provider payloads (D-class in the
 * request-construction error audit, 2026-08-14).
 *
 * Upstream (ChatViewModel.effectiveAgentHistory + compact slicing + per-turn
 * sanitizeAgentHistoryMessages) is the FIRST line of defense; these are the
 * LAST line, applied at serialization time so a broken history can never reach
 * the wire as a deterministic 400 — even when a caller bypasses the upstream
 * layers (synthesized requests, sub-agent flows, fallback paths).
 *
 * All functions are pure JVM (no Android dependencies) and operate on copies —
 * the caller's stored history is never mutated.
 */

/**
 * Cap a requested `max_tokens` into [1, ceiling] where `ceiling` is the
 * model's claimed output ceiling (min of the model's own maxOutputTokens-or-
 * provider-default and the shared 128K global cap).
 *
 * Upstream `dynamicMaxTokens()` already produces an in-range value; this
 * guards out-of-band callers (sub-agent frontmatter budgets, synthesized
 * requests) that bypass it. An over-range `max_tokens` is a deterministic 400
 * on every provider family; a non-positive value is equally rejected.
 */
fun clampOutboundMaxTokens(requested: Int, ceiling: Int): Int {
    val safeCeiling = ceiling.coerceIn(1, GLOBAL_MAX_OUTPUT_CEILING)
    return requested.coerceIn(1, safeCeiling)
}

/** Shared 128K cap for outbound max_tokens — mirrors ChatViewModel.GLOBAL_MAX_TOKENS_CEILING. */
const val GLOBAL_MAX_OUTPUT_CEILING = 128_000

/** Clamp temperature into the standard [0, max] range (OpenAI/Gemini: 2, Anthropic: 1). */
fun clampOutboundTemperature(value: Double, max: Double = 2.0): Double =
    value.coerceIn(0.0, max)

/**
 * Sanitize tool-use/tool-result pairing in an outbound message list.
 *
 * Pass 1 — orphan tool_use: an assistant message's [AgentContentPart.ToolUse]
 *   that is NOT answered by a matching [AgentContentPart.ToolResult] in the
 *   IMMEDIATELY following user message is dropped (e.g. history truncated
 *   mid-tool-turn, user interrupted, or a compact slice cut between a tool_use
 *   and its result). Anthropic / OpenAI / Gemini all reject an unanswered tool
 *   call with a deterministic 400.
 *
 * Pass 2 — orphan tool_result: a user message's [AgentContentPart.ToolResult]
 *   whose id does not match a [AgentContentPart.ToolUse] in the most recent
 *   assistant message is dropped. Anthropic rejects with 400 (`unexpected
 *   tool_use_id ... no corresponding tool_use block`); OpenAI rejects a
 *   `role:"tool"` message with an unknown `tool_call_id`; Gemini rejects a
 *   `functionResponse` with no preceding `functionCall`.
 *
 * Pass 3 — drop messages left empty after stripping. An empty content array is
 *   itself a 400 on every provider family.
 *
 * Pairing is by [AgentContentPart.ToolUse.id] / [AgentContentPart.ToolResult.id],
 * which are session-local and consistent across providers (Gemini's wire
 * protocol matches functionCall↔functionResponse by name, but the local ids
 * round-trip the same pair, so id matching is equivalent here).
 *
 * @param log invoked with a human-readable description each time a block is
 *   stripped; callers wire this to android.util.Log.
 */
fun sanitizeToolPairing(
    messages: List<LLMMessage>,
    // [FIX-1 / F-211] Drop messages that stripping left completely empty.
    //
    // This was documented as deliberate ("callers apply it themselves") and
    // then applied by exactly ONE of the four production call sites
    // (AnthropicProvider:547). OpenAIProvider's two call sites passed no
    // filter, so when an assistant turn's only content was a tool_use whose
    // result had been stripped, the legacy serializer emitted
    // {"role":"assistant","content":""} — a shape OpenAI rejects, i.e. the
    // sanitizer created the 400 it exists to prevent.
    //
    // Default true because "send an empty message" is not a thing any
    // OpenAI-shaped API accepts; Gemini is the one caller that must keep them,
    // because its serializer turns "" into " " (a valid prefill / empty turn)
    // and a test pins that behaviour. Opt out there rather than opt in
    // everywhere, so a fifth call site cannot inherit the hole.
    dropEmpty: Boolean = true,
    // MUST stay last: three production call sites pass the logger as a
    // TRAILING LAMBDA, and a trailing lambda binds to the final parameter
    // whatever its type is. Putting dropEmpty after this one silently
    // retargeted those lambdas at a Boolean (CI compileReleaseKotlin caught it:
    // "actual type is kotlin.Function1<...>, but kotlin.Boolean was expected").
    log: (String) -> Unit = {},
): List<LLMMessage> {
    val result = ArrayList<LLMMessage>(messages.size)
    // Tool-use ids from the most recent assistant message that are still
    // awaiting their tool_result answer.
    var liveToolUseIds: Set<String> = emptySet()

    for (i in messages.indices) {
        val msg = messages[i]
        when (msg.role) {
            LLMMessage.Role.ASSISTANT -> {
                val toolUses = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
                var kept = msg.contentParts
                if (toolUses.isNotEmpty()) {
                    // The answer to this assistant's tool calls must arrive in
                    // the immediately following user message.
                    val next = messages.getOrNull(i + 1)
                    val answeredIds = if (next != null && next.role == LLMMessage.Role.USER) {
                        next.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
                            .map { it.id }
                            .toSet()
                    } else {
                        emptySet()
                    }
                    kept = msg.contentParts.filter { part ->
                        part !is AgentContentPart.ToolUse || answeredIds.contains(part.id)
                    }
                    if (kept.size != msg.contentParts.size) {
                        val dropped = msg.contentParts.size - kept.size
                        log("Stripped $dropped orphan tool_use block(s) from outbound payload (idx=$i)")
                    }
                }
                // Only tool calls that survived pass 1 can be answered later.
                liveToolUseIds = kept.filterIsInstance<AgentContentPart.ToolUse>()
                    .map { it.id }
                    .toSet()
                result.add(if (kept === msg.contentParts) msg else msg.copy(contentParts = kept))
            }
            LLMMessage.Role.USER -> {
                val original = msg.contentParts
                if (original.isNotEmpty()) {
                    val kept = original.filter { part ->
                        if (part is AgentContentPart.ToolResult) {
                            liveToolUseIds.contains(part.id)
                        } else true
                    }
                    if (kept.size != original.size) {
                        val dropped = original.size - kept.size
                        log("Stripped $dropped orphan tool_result block(s) from outbound payload (idx=$i)")
                        result.add(msg.copy(contentParts = kept))
                    } else {
                        result.add(msg)
                    }
                } else {
                    result.add(msg)
                }
                // A later user turn can't answer an earlier assistant's calls.
                liveToolUseIds = emptySet()
            }
        }
    }

    // Drop messages that became empty (only orphan tool parts). Kept messages
    // still have either non-empty contentParts or a non-empty `content`
    // string (string-only messages never had parts to strip in the first
    // place).
    //
    // [FIX-1 / F-211] Applied here by default so every caller gets it; the
    // Gemini adapter opts out via dropEmpty=false (its serializer replaces ""
    // with " ", which is a valid empty turn there). AnthropicProvider still
    // applies its own filter afterwards — harmless, and left in place so this
    // change cannot regress that path.
    if (!dropEmpty) return result
    return result.filter { m -> m.contentParts.isNotEmpty() || m.content.isNotEmpty() }
}
