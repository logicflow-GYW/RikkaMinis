package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMUsage

/**
 * Pure parts_json / usage-JSON serializers extracted from ChatViewModel
 * (FE-5 route A, byte-stable wire formats).
 *
 * These build the exact on-disk `parts_json` / `token usage` JSON shapes that
 * [com.openminis.app.data.repository.ChatRepository] persists and
 * [ChatViewModelMessageParser.parsePartsJson] reads back. Keeping them as
 * top-level pure functions means the wire format is directly JVM-testable
 * and round-trips (build → parse) can be asserted without a ViewModel.
 *
 * All functions are verbatim moves from ChatViewModel — behavior is
 * byte-for-byte identical to the inline logic they replace.
 */

/**
 * Serialize a turn's [AgentContentPart] list into the on-disk parts_json
 * shape (text + toolUse blocks). Shared by [ChatViewModel.persistAssistantTurn]
 * (the authoritative per-turn row write) and the live session-list preview
 * update ([T-android-session-last-message-live-tool-call]) so both produce
 * an identical payload that [com.openminis.app.data.repository.ChatRepository.extractTextPreview]
 * understands.
 */
internal fun buildAssistantTurnPartsJson(
    parts: List<AgentContentPart>,
    toolBlockMeta: Map<String, AssistantBlock>,
): String = buildString {
    append("[")
    parts.forEachIndexed { index, part ->
        if (index > 0) append(",")
        when (part) {
            is AgentContentPart.Text -> {
                append("""{"type":"text","value":${escapeJson(part.text)}}""")
            }
            is AgentContentPart.ToolUse -> {
                // Skip tool_use with blank name — upstream bug guard.
                val name = part.name
                if (name.isBlank()) return@forEachIndexed
                val inputStr = part.input.toString()
                val meta = toolBlockMeta[part.id]
                val desc = meta?.toolTitle ?: ""
                val pageURL = meta?.browserURL ?: ""
                val imgPath = meta?.imageFilePath ?: ""
                append("""{"type":"toolUse","value":{"toolUseId":${escapeJson(part.id)},"name":${escapeJson(name)},"input":${escapeJson(inputStr)},"description":${escapeJson(desc)},"pageURL":${escapeJson(pageURL)},"imageFilePath":${escapeJson(imgPath)},"thoughtSignature":null}}""")
            }
            else -> { /* tool_result is persisted via persistToolResultMessage */ }
        }
    }
    append("]")
}

/**
 * Build a JSON parts array matching the ChatRepository schema so a
 * committed interrupted-assistant turn round-trips across app restarts.
 * Only emits text parts — tool_use / tool_result paths are handled by
 * the existing persistence code in the agent loop.
 *
 * (Interrupted-turn variant of [buildAssistantPartsJson]: text-only, no
 * toolBlockMeta — used by the user-Stop cleanup path.)
 */
internal fun buildTextOnlyAssistantPartsJson(parts: List<AgentContentPart>): String {
    val sb = StringBuilder("[")
    var first = true
    for (p in parts) {
        if (p !is AgentContentPart.Text) continue
        if (!first) sb.append(',') else first = false
        sb.append("""{"type":"text","value":""")
        sb.append(escapeJson(p.text))
        sb.append('}')
    }
    sb.append(']')
    return sb.toString()
}

/**
 * Serialize [LLMUsage] into the compact token-usage JSON persisted alongside
 * a turn row (persistAssistantTurn). Nulls are coalesced to 0 to keep the
 * wire shape stable across providers that omit cache fields.
 */
internal fun buildUsageJson(usage: LLMUsage): String =
    """{"inputTokens":${usage.inputTokens},"outputTokens":${usage.outputTokens},"cacheCreationTokens":${usage.cacheCreationInputTokens ?: 0},"cacheReadTokens":${usage.cacheReadInputTokens ?: 0},"latestContextTokens":${usage.latestContextTokens}}"""

/**
 * Serialize a list of tool results into the user-role parts_json persisted by
 * persistToolResultMessage. Mirrors the inline buildString it replaces.
 * The legacy per-result "snapshot" preview field was dropped [fix/audit-s1l1]:
 * it duplicated the tail of `output`, no parser/UI code reads it (verified
 * via grep + ConfigBackup already strips it on export), and it inflated every
 * persisted tool-result row by up to 30 lines of redundant JSON.
 */
internal fun buildToolResultPartsJson(results: List<AgentContentPart.ToolResult>): String = buildString {
    append("[")
    results.forEachIndexed { index, result ->
        if (index > 0) append(",")
        append("""{"type":"toolResult","value":{"toolUseId":${escapeJson(result.id)},"name":${escapeJson(result.name)},"output":${escapeJson(result.content)},"success":${!result.isError}}}""")
    }
    append("]")
}

// NOTE (FE-5): extractPartialStringValue / findUnescapedEnd /
// unescapePartialJsonString already live in ChatViewModelUtils.kt as
// top-level internal functions — the VM private copies deleted in this
// extraction were historical leftovers of an earlier partial split.
