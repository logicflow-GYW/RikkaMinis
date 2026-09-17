package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.db.CompactMarkerEntity
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage

/**
 * Pure compaction-domain helpers extracted from [ChatViewModel.compactAll].
 *
 * These are deliberately side-effect-free and state-free: they take the
 * agent history (plus the caller's explicit inputs) and return an index.
 * This makes them trivially JVM-testable, and is the first step of the
 * FE-4 ChatViewModel split (route A: pull out pure logic before any
 * state-access interface is designed).
 *
 * Extracted verbatim from ChatViewModel.compactAll (fcf9470) — behavior is
 * byte-for-byte identical to the inline logic it replaces.
 */

/**
 * Resolves the compact anchor index within [history].
 *
 * Mirrors iOS `AIChatViewModel+Compaction.swift` tail-walk-back logic:
 *
 *  - When [anchorIdxOverride] is supplied (compact-before path), walk back
 *    from that index to the closest entry with a non-empty [LLMMessage.dbMessageId],
 *    bounded to `[0..override]`.
 *  - Otherwise (compact-all path), walk back from the tail to the closest
 *    persisted entry, then apply the `[Compact-keep-answer-active]` rule:
 *    fall back to the last persisted USER prompt (skipping pure tool-result
 *    entries) so the just-delivered answer stays in the active region.
 *
 * @return the anchor index, or -1 when no persisted message exists.
 */
fun resolveCompactAnchorIdx(
    history: List<LLMMessage>,
    anchorIdxOverride: Int?,
): Int {
    if (history.isEmpty()) return -1
    return if (anchorIdxOverride != null) {
        // compactBefore() supplied a specific anchor — walk back from
        // there to the closest entry with a dbMessageId.
        var i = anchorIdxOverride.coerceIn(0, history.lastIndex)
        while (i >= 0 && history[i].dbMessageId.isNullOrEmpty()) i -= 1
        i
    } else {
        // compactAll() — walk back from the tail to the closest
        // persisted entry.
        var i = history.lastIndex
        while (i >= 0 && history[i].dbMessageId.isNullOrEmpty()) i -= 1
        // [Compact-keep-answer-active] keep the answer OUTSIDE the compaction
        // — fall back to the last persisted USER prompt so everything after it
        // (tool work + the final answer) stays in the active, un-grayed region.
        // Skip pure tool-result entries (role=USER but contentParts all ToolResult).
        if (i > 0) {
            while (i >= 0 && (history[i].role != LLMMessage.Role.USER ||
                    history[i].contentParts.all { p -> p is AgentContentPart.ToolResult } ||
                    history[i].dbMessageId.isNullOrEmpty())
            ) i -= 1
        }
        // [fix/compact-keep-instruction-active] The USER prompt we just landed
        // on may be the CURRENT turn's driving instruction (run in progress, or
        // a just-sent message with no answer yet). Anchoring ON it puts the
        // instruction inside the compacted range: the summary replaces it, the
        // model loses the instruction (the "swallowed instruction" report), and
        // the run continues on the summary alone. Walk back past the trailing
        // block of user-text prompts so the whole current turn (instruction +
        // tool work + answer) stays on the ACTIVE side of the divider. Manual
        // compact-before (anchorIdxOverride) is unaffected — an explicit anchor
        // is the user's own choice.
        //
        // [audit-0915] SCOPE — stated because the block above describes only
        // half of it. This walk-back is UNCONDITIONAL on the compact-all path:
        // it does NOT test whether a run is in flight, so it also applies to a
        // manual compact of a settled session. Two visible consequences, both
        // deliberate:
        //   1. the compacted range ends one turn earlier than before (the
        //      PREVIOUS answer is compacted along with the rest, the LAST whole
        //      turn stays active). The instruction is the thing least safe to
        //      replace with a summary, so this direction is the safer one.
        //   2. a second compact with no new turn in between resolves to the
        //      same anchor, so `effectiveStartIdx > anchorIdx` reports
        //      "already compacted" instead of re-compacting the last prompt.
        // If in-flight-only scope is ever wanted, gate this block on the run
        // state at the CALL SITE — never read `_isStreaming` from in here: this
        // helper is side-effect-free and JVM-tested.
        if (i > 0) {
            var j = i
            while (j > 0) {
                val prev = history[j - 1]
                if (prev.role != LLMMessage.Role.USER ||
                    prev.contentParts.all { p -> p is AgentContentPart.ToolResult } ||
                    prev.dbMessageId.isNullOrEmpty()
                ) break
                j -= 1
            }
            if (j > 0) {
                var k = j - 1
                while (k >= 0 && history[k].dbMessageId.isNullOrEmpty()) k -= 1
                i = k // may end at -1 when nothing persisted precedes → caller aborts
            } else {
                // [audit-0916] The walk reached the very start: every entry
                // before the tail is a persisted user-text prompt with no
                // assistant reply in between. A role bridge does not persist,
                // so a reload that collapses bridges produces exactly this
                // shape — and the tail prompt may be the CURRENT instruction,
                // still unanswered. Nothing settled exists to anchor on, and
                // anchoring on the tail would swallow the instruction (the
                // failure this walk-back exists to prevent). Abort, mirroring
                // the sole-prompt branch below.
                return -1
            }
        } else if (i == 0 &&
            history[0].role == LLMMessage.Role.USER &&
            history[0].contentParts.any { p -> p !is AgentContentPart.ToolResult } &&
            history.drop(1).none { it.role == LLMMessage.Role.ASSISTANT }
        ) {
            // Sole unanswered user prompt and nothing anchored before it —
            // compacting [0..0] would swallow the only instruction. Abort.
            return -1
        }
        i
    }
}

/**
 * Resolves the compact range's starting index (inclusive) from the previous
 * compact marker.
 *
 *  - `null` marker → start at 0.
 *  - v2 marker → prev anchor is [CompactMarkerEntity.lastCompactedMessageId];
 *    start at `prevIdx + 1` (anchor was inclusive).
 *  - v1 marker → prev anchor is `firstKeptMessageId ?: boundaryMessageId`;
 *    start AT `prevIdx` (right edge was exclusive).
 *  - prev anchor not present in current history → restart from top (0).
 *
 * @return the effective start index (>= 0).
 */
fun resolveCompactStartIdx(
    history: List<LLMMessage>,
    prevMarker: CompactMarkerEntity?,
): Int {
    if (prevMarker == null) return 0
    val prevAnchorOrFirstKept: String? = if (prevMarker.version >= 2) {
        prevMarker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
    } else {
        prevMarker.firstKeptMessageId?.takeIf { it.isNotEmpty() }
            ?: prevMarker.boundaryMessageId?.takeIf { it.isNotEmpty() }
    }
    val prevIdx = prevAnchorOrFirstKept?.let { id ->
        history.indexOfFirst { it.dbMessageId == id }
    } ?: -1
    return if (prevIdx < 0) 0   // prev anchor not in current history — restart from top
    else if (prevMarker.version >= 2) prevIdx + 1
    else prevIdx
}

/**
 * Builds the plain-text transcript fed to the compaction-summary model.
 *
 * Pure and side-effect-free (extracted verbatim from ChatViewModel,
 * fcf9470): each message is rendered as `role: <content>` plus one line per
 * structured content part (text / tool-use / tool-result / image), truncated
 * to bound the summarizer input.
 */
fun buildConversationTextForSummary(history: List<LLMMessage>): String = buildString {
    for (msg in history) {
        val role = msg.role.name.lowercase()
        val text = msg.content.take(500)
        if (text.isNotEmpty()) {
            append(role).append(": ").append(text).append('\n')
        }
        for (part in msg.contentParts) {
            when (part) {
                is AgentContentPart.Text -> {
                    append(role).append(": ").append(part.text.take(500)).append('\n')
                }
                is AgentContentPart.ToolUse -> {
                    val preview = part.input.toString().take(200)
                    append(role).append(" [tool:").append(part.name).append("]: ")
                        .append(preview).append('\n')
                }
                is AgentContentPart.ToolResult -> {
                    append(role).append(" [result:").append(part.name).append("]: ")
                        .append(part.content.take(500)).append('\n')
                }
                is AgentContentPart.ImageData -> {
                    append(role).append(" [image: ").append(part.mimeType).append("]\n")
                }
            }
        }
    }
}

/**
 * Match provider error text against the substring set iOS
 * `isContextTooLargeError` uses (AIChatViewModel+Compaction.swift:879).
 * When true, the splitter halves the input and retries.
 */
fun isContextTooLargeError(error: Throwable): Boolean {
    val desc = (error.message ?: error.toString()).lowercase()
    // [fix/audit-0917-b9] "max_tokens" was dropped: it appears in OUTPUT
    // parameter errors ("max_tokens must be at most N") which halving the
    // input does NOT fix — classifying them here sent the retry loop into
    // repeated non-converging halvings. True input-overflow texts are all
    // covered by the remaining substrings (context window / context length /
    // too many tokens / prompt is too long / …). Deliberate deviation from
    // the iOS implementation this was mirrored from.
    return desc.contains("too many tokens") ||
        desc.contains("context length") ||
        desc.contains("content is too long") ||
        desc.contains("exceeds the model") ||
        desc.contains("request too large") ||
        desc.contains("prompt is too long") ||
        desc.contains("token limit") ||
        desc.contains("context window")
}

/** Result of [walkBackUserTurnsBounded]. */
data class WalkBackResult(
    val priorIdx: Int?,
    val userTextTurnsFound: Int,
    val messageCount: Int,
    /** "userTextTargetMet" | "messageCapWouldExceed" | "reachedStart" | "invalidAnchor" */
    val stopReason: String,
)

/**
 * Walk back from [anchorIdx] toward 0, deciding ONLY at user-message
 * boundaries whether to include the next round. Stops when:
 *  - we've collected [maxUserTextTurns] user-text turns (success), OR
 *  - including the next user round would push total messages over
 *    [maxMessages] (cap reason — don't split a user/assistant/tool round
 *    in the middle, otherwise a tool_use would be orphaned without its
 *    tool_result), OR
 *  - we hit index 0 (start of history).
 *
 * Port of iOS `walkBackUserTurnsBounded` (AIChatViewModel.swift, 8b76cd74).
 * Pure after parameterizing [agentHistory] as [history]; extracted verbatim.
 */
fun walkBackUserTurnsBounded(
    history: List<LLMMessage>,
    anchorIdx: Int,
    maxUserTextTurns: Int,
    maxMessages: Int,
): WalkBackResult {
    if (anchorIdx < 0 || anchorIdx >= history.size) {
        return WalkBackResult(null, 0, 0, "invalidAnchor")
    }
    var acceptedPriorIdx: Int? = null
    var acceptedUserTextTurns = 0
    var acceptedMessageCount = 0

    var i = anchorIdx
    while (i >= 0) {
        val msg = history[i]
        if (msg.role != LLMMessage.Role.USER) {
            i -= 1
            continue
        }
        val candidateMessageCount = anchorIdx - i + 1
        if (candidateMessageCount > maxMessages) {
            return WalkBackResult(
                priorIdx = acceptedPriorIdx,
                userTextTurnsFound = acceptedUserTextTurns,
                messageCount = acceptedMessageCount,
                stopReason = "messageCapWouldExceed",
            )
        }
        // Accept this user as the new tentative priorIdx.
        acceptedPriorIdx = i
        acceptedMessageCount = candidateMessageCount
        val hasText = msg.content.isNotBlank() ||
            msg.contentParts.any { it is AgentContentPart.Text && it.text.isNotBlank() }
        if (hasText) {
            acceptedUserTextTurns += 1
            if (acceptedUserTextTurns >= maxUserTextTurns) {
                return WalkBackResult(
                    priorIdx = acceptedPriorIdx,
                    userTextTurnsFound = acceptedUserTextTurns,
                    messageCount = acceptedMessageCount,
                    stopReason = "userTextTargetMet",
                )
            }
        }
        i -= 1
    }
    return WalkBackResult(
        priorIdx = acceptedPriorIdx,
        userTextTurnsFound = acceptedUserTextTurns,
        messageCount = acceptedMessageCount,
        stopReason = "reachedStart",
    )
}
