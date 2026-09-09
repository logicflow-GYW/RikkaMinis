package com.rikkaminis.app.ui.chat

/**
 * [T-token-usage-live-loop-count] Agent-loop iteration count for the Token
 * Usage sheet ("Agent Loop → Total Loops"), computed the way iOS
 * `sessionTokenStats` does: `max(tool-call blocks, assistant messages)`.
 *
 * ── Why this is a separate pure function ────────────────────────────────
 * The canonical message list is deliberately FROZEN while a turn streams:
 * `ChatViewModel.updateAssistantMessage(isStreaming = true)` writes the
 * high-frequency fields (content / toolBlocks / awaiting flag) into the
 * `_streamingById` side channel instead of `_messages`, and ChatScreen merges
 * that overlay for rendering ([mergeStreamingOverlay]). The point of that
 * architecture is to keep the `messages` StateFlow reference stable so the
 * 9k-line ChatScreen composable doesn't recompose per token.
 *
 * A stats reader that counts the canonical list alone therefore reports the
 * value as of the *last completed* turn for the entire run — for a fresh
 * session, the only canonical assistant message is the empty streaming
 * placeholder, so "Total Loops" reads **1** from the first token until the
 * run ends (or the user pauses), then jumps to the final number when the
 * delta is drained back into `_messages`. That is exactly the reported
 * symptom; the 1s sheet poll (feat/runtime-limits-ux) refreshed a snapshot
 * that could not change, which is why that fix did not help.
 *
 * Live deltas therefore win over the canonical blocks of the same message id,
 * but only when their epoch matches the active stream — a delta left over
 * from a cancelled turn must never inflate the count. Same rule as
 * [mergeStreamingOverlay], so the number matches what the transcript shows.
 *
 * Counting is per tool block (not per turn) to preserve the shipped
 * end-of-run value: at stream end the delta is drained into the canonical
 * message, so `countAgentLoops` before and after the drain return the same
 * number (continuity — the displayed value never jumps).
 */
internal fun countAgentLoops(
    messages: List<ChatMessage>,
    streaming: Map<String, StreamingDelta>,
    currentEpoch: Long,
): Int {
    var assistantCount = 0
    var toolCalls = 0
    for (message in messages) {
        if (message.role != "assistant") continue
        assistantCount++
        val delta = streaming[message.id]
        val blocks = if (delta != null && delta.epoch == currentEpoch) delta.toolBlocks else message.toolBlocks
        toolCalls += blocks.count { it.kind != "text" && it.kind != "info" }
    }
    return maxOf(toolCalls, assistantCount)
}
