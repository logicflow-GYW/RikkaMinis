package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.EnvVarPrivacyStore
import com.rikkaminis.app.data.EnvVarRedactor
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage

/** Only derived context-summary blocks are projected, never direct user prose or pinned text. */
internal fun redactDerivedSummaryText(text: String): String {
    if (!EnvVarPrivacyStore.isEnabled || !text.contains("<context-summary>")) return text
    val startTag = "<context-summary>"
    val endTag = "</context-summary>"
    val out = StringBuilder()
    var cursor = 0
    while (cursor < text.length) {
        val start = text.indexOf(startTag, cursor)
        if (start < 0) { out.append(text, cursor, text.length); break }
        out.append(text, cursor, start + startTag.length)
        val bodyStart = start + startTag.length
        val end = text.indexOf(endTag, bodyStart)
        val bodyEnd = if (end < 0) text.length else end
        out.append(redactUnpinnedSummary(text.substring(bodyStart, bodyEnd)))
        cursor = bodyEnd
        if (end < 0) break
        out.append(endTag)
        cursor += endTag.length
    }
    return out.toString()
}

/** The existing pinned section is user-authored data, not generated summary text. */
internal fun redactUnpinnedSummary(text: String): String {
    val startTag = "<pinned-user-messages>"
    val endTag = "</pinned-user-messages>"
    val start = text.indexOf(startTag)
    if (start < 0) return EnvVarRedactor.redactForModel(text).text
    val end = text.indexOf(endTag, start + startTag.length)
    if (end < 0) return EnvVarRedactor.redactForModel(text.substring(0, start)).text + text.substring(start)
    val after = end + endTag.length
    return EnvVarRedactor.redactForModel(text.substring(0, start)).text + text.substring(start, after) +
        EnvVarRedactor.redactForModel(text.substring(after)).text
}

/**
 * Recheck immediately before a chat request and before context offload.
 * Compare with the ORIGINAL rows so only an actually changed replay group loses
 * its capsule. No new Room/IPC field; signatures and image bytes stay untouched.
 */
internal fun redactToolHistoryForModel(messages: List<LLMMessage>): List<LLMMessage> {
    if (!EnvVarPrivacyStore.isEnabled) return messages
    var changed = false
    val projected = messages.map { message ->
        val content = redactDerivedSummaryText(message.content)
        val parts = message.contentParts.map { part ->
            when (part) {
                is AgentContentPart.ToolResult -> {
                    val redaction = EnvVarRedactor.redactForModel(part.content)
                    if (redaction.text == part.content) part else part.copy(content = redaction.text)
                }
                is AgentContentPart.Text -> {
                    val text = redactDerivedSummaryText(part.text)
                    if (text == part.text) part else part.copy(text = text)
                }
                else -> part
            }
        }
        if (content == message.content && parts == message.contentParts) message
        else { changed = true; message.copy(content = content, contentParts = parts) }
    }
    return if (changed) guardAnthropicReplayHistory(projected, messages) else messages
}
