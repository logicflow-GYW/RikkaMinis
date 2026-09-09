package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage

/**
 * Neutral placeholder injected when a history slice carries an assistant
 * tool_use whose tool_result never landed (interrupted turn / compact slice
 * boundary). Wording deliberately does NOT tell the model to re-run the call —
 * the tool may or may not have completed.
 */
internal const val SANITIZE_PLACEHOLDER_RESULT_CONTENT =
    "Tool execution was interrupted; the tool may or may not have completed. Do not blindly re-issue the same tool call — first check the conversation and any prior results."

/**
 * [T-compact-slice-tool-pairing] Repairs the tool_use / tool_result pairing
 * on any message list (full history OR compacted slice) so no outgoing
 * request carries a dangling tool message.
 *
 * Pure + JVM-testable (no ViewModel/Android dependencies). The optional
 * [log] sink keeps the pure function Android-free (default no-op); the
 * production shell in ChatViewModel injects Log.w so the sanitize action is
 * observable in logcat under the ChatViewModel tag.
 */
internal fun sanitizeAgentHistoryMessagesImpl(
    messages: MutableList<LLMMessage>,
    log: (String) -> Unit = {},
) {
    // Walk through history sequentially, checking each assistant message.
    // For each assistant message with tool_use blocks, verify the NEXT message
    // is a user message with matching tool_result blocks. If not, inject them.
    var i = 0
    while (i < messages.size) {
        val msg = messages[i]
        if (msg.role != LLMMessage.Role.ASSISTANT) { i++; continue }

        val toolUses = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
        if (toolUses.isEmpty()) { i++; continue }

        val toolUseIds = toolUses.map { it.id }.toSet()

        // Check next message for matching tool_results
        val next = messages.getOrNull(i + 1)
        val nextResultIds = next?.contentParts
            ?.filterIsInstance<AgentContentPart.ToolResult>()
            ?.map { it.id }?.toSet() ?: emptySet()

        val missingIds = toolUseIds - nextResultIds
        if (missingIds.isEmpty()) { i++; continue }

        // Some tool_uses have no matching tool_result in the next message.
        // If next message is a user message, add the missing results to it.
        // Otherwise, inject a new user message with placeholder results.
        val placeholders = toolUses.filter { it.id in missingIds }.map { use ->
            AgentContentPart.ToolResult(
                id = use.id, name = use.name,
                content = SANITIZE_PLACEHOLDER_RESULT_CONTENT,
                isError = true,
            )
        }
        log("sanitize: injecting ${placeholders.size} placeholder tool_result(s) after history[$i]")

        if (next != null && next.role == LLMMessage.Role.USER &&
            next.contentParts.any { it is AgentContentPart.ToolResult }) {
            // Append missing results to the existing user message
            messages[i + 1] = next.copy(
                contentParts = next.contentParts + placeholders
            )
        } else {
            // Insert a new user message with just the placeholder results
            messages.add(i + 1, LLMMessage(
                role = LLMMessage.Role.USER, content = "",
                contentParts = placeholders,
            ))
        }
        i++
    }

    // Remove orphaned tool_results (result IDs not found in any tool_use)
    val allToolUseIds = messages.flatMap { it.contentParts }
        .filterIsInstance<AgentContentPart.ToolUse>().map { it.id }.toSet()
    val iter = messages.listIterator()
    while (iter.hasNext()) {
        val msg = iter.next()
        if (msg.role != LLMMessage.Role.USER) continue
        val cleaned = msg.contentParts.filter { part ->
            part !is AgentContentPart.ToolResult || part.id in allToolUseIds
        }
        if (cleaned.isEmpty() && msg.content.isBlank()) {
            iter.remove()
        } else if (cleaned.size < msg.contentParts.size) {
            iter.set(msg.copy(contentParts = cleaned))
        }
    }
}

/**
 * [T-consecutive-user-bridge] Enforce "roles must alternate" just before a
 * fresh user message is appended to history from an entry point that is NOT
 * inside the agent-loop tool-result cycle ([sendMessage] /
 * [drainQueuedPrompts]).
 *
 * Pure + JVM-testable (no ViewModel dependencies).
 */
internal fun ensureRoleAlternationBeforeUserAppend(
    history: MutableList<LLMMessage>,
    bridgeText: String = "(Interrupted mid-task by a new user message. Decide based on the new message and overall context whether the prior task should continue — do not forget or abandon it unless the user explicitly says to stop, or the new message makes clear it is no longer needed.)",
) {
    if (history.lastOrNull()?.role == LLMMessage.Role.USER) {
        history.add(
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(AgentContentPart.Text(bridgeText)),
            ),
        )
    }
}