package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.EnvVarPrivacyStore
import com.rikkaminis.app.data.EnvVarRedactor
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage

/** Only derived context-summary blocks are projected, never direct user prose or pinned text. */
internal fun redactDerivedSummaryText(
    text: String,
    snapshot: EnvVarRedactor.SecretSnapshot? = null,
): String {
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
        out.append(redactUnpinnedSummary(text.substring(bodyStart, bodyEnd), snapshot))
        cursor = bodyEnd
        if (end < 0) break
        out.append(endTag)
        cursor += endTag.length
    }
    return out.toString()
}

/** The existing pinned section is user-authored data, not generated summary text. */
internal fun redactUnpinnedSummary(
    text: String,
    snapshot: EnvVarRedactor.SecretSnapshot? = null,
): String {
    val startTag = "<pinned-user-messages>"
    val endTag = "</pinned-user-messages>"
    val start = text.indexOf(startTag)
    if (start < 0) return redactText(text, snapshot)
    val end = text.indexOf(endTag, start + startTag.length)
    if (end < 0) return redactText(text.substring(0, start), snapshot) + text.substring(start)
    val after = end + endTag.length
    return redactText(text.substring(0, start), snapshot) + text.substring(start, after) +
        redactText(text.substring(after), snapshot)
}

/**
 * [fix/session-load-redaction-cost-1123] One redaction through the shared
 * credential snapshot when the caller has one, else the single-shot path.
 */
private fun redactText(text: String, snapshot: EnvVarRedactor.SecretSnapshot?): String =
    if (snapshot != null) EnvVarRedactor.redactForModel(text, snapshot).text
    else EnvVarRedactor.redactForModel(text).text

/**
 * Recheck immediately before a chat request and before context offload.
 * Compare with the ORIGINAL rows so only an actually changed replay group loses
 * its capsule. No new Room/IPC field; signatures and image bytes stay untouched.
 */
internal fun redactToolHistoryForModel(messages: List<LLMMessage>): List<LLMMessage> {
    if (!EnvVarPrivacyStore.isEnabled) return messages
    // [fix/session-load-redaction-cost-1123] ONE credential read for the whole
    // history. The single-shot form decrypts every env var and every provider
    // key per call, so a 266-part session paid that 266 times: 935-1375 ms on
    // device, against ~30 ms of actual rule matching. The snapshot lives only
    // for this call, so a rotated credential is still picked up next turn.
    val snapshot = EnvVarRedactor.snapshot()
    var changed = false
    val projected = messages.map { message ->
        val content = redactDerivedSummaryText(message.content, snapshot)
        val parts = message.contentParts.map { part ->
            when (part) {
                is AgentContentPart.ToolResult -> {
                    val redaction = EnvVarRedactor.redactForModel(part.content, snapshot)
                    if (redaction.text == part.content) part else part.copy(content = redaction.text)
                }
                // [fix/privacy-reenable-tooluse-1124] The switch can be turned ON
                // after a row was saved while it was OFF — and
                // `buildAssistantTurnPartsJson` correctly stored the real arguments
                // in that state. This function is the LAST projection before the
                // model reads a reloaded row, so it has to re-check the arguments
                // too. Without this arm a credential travelled in the tool
                // arguments, and when the tool result was clean the still-eligible
                // native replay capsule carried the same bytes.
                is AgentContentPart.ToolUse -> {
                    val rawInput = part.input.toString()
                    val redaction = EnvVarRedactor.redactForModel(rawInput, snapshot)
                    if (redaction.text == rawInput) part
                    else part.copy(input = projectedToolInput(redaction.text))
                }
                is AgentContentPart.Text -> {
                    val text = redactDerivedSummaryText(part.text, snapshot)
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

/**
 * Re-parse a projected tool-argument document for the model payload.
 *
 * Masking substitutes credential VALUES inside valid JSON, so the projected
 * document normally still parses. If it does not, the raw bytes must never come
 * back — the caller IS the model payload — and throwing here would fail a whole
 * session load. A marker object keeps the payload valid JSON while carrying the
 * already-masked text, so nothing unprojected survives and the provider still
 * receives an object for its `input` / `arguments` field.
 *
 * Deliberately a separate function: the fail-closed branch is unit-tested, and
 * the scan gate judges the arm that calls it.
 */
internal fun projectedToolInput(projected: String): org.json.JSONObject =
    runCatching { org.json.JSONObject(projected) }.getOrElse {
        org.json.JSONObject().put("_redactedArguments", projected)
    }
