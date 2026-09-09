package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.MediaRef
import org.json.JSONObject

/**
 * Pure JSON/text serialization helpers extracted from ChatViewModel (FE-4
 * route B, fcf9470). State-free and JVM-testable: they take plain values and
 * return strings. [org.json.JSONObject] is available in unit tests via the
 * `org.json:json` test dependency.
 *
 * Note: [escapeJson] is intentionally NOT redefined here — it already lives in
 * [ChatViewModelUtils] (an earlier FE split) and is reused directly by
 * [buildUserPartsJson] / [buildMediaRefPartJson]. This removes a long-standing
 * duplicate `private fun escapeJson` that used to live inside ChatViewModel.
 */

/**
 * Serializes a [MediaRef] into a `{"type":"mediaRef","value":{...}}` JSON
 * fragment. [linuxPath], when present, carries the iSH-visible uploads path
 * through persistence so restored history can reconstruct
 * [com.rikkaminis.app.data.model.AgentContentPart.ImageData] with its original
 * linuxPath.
 */
fun buildMediaRefPartJson(
    ref: MediaRef,
    linuxPath: String? = null,
): String {
    val value = JSONObject()
        .put("id", ref.id)
        .put("relativePath", ref.relativePath)
        .put("mimeType", ref.mimeType)
    if (ref.originalFileName != null) value.put("originalFileName", ref.originalFileName)
    if (linuxPath != null) value.put("linuxPath", linuxPath)
    return JSONObject().put("type", "mediaRef").put("value", value).toString()
}

/**
 * Builds the parts_json array for a user message: a `text` part (omitted when
 * the user only sent attachments with no caption) followed by one `mediaRef`
 * part per persisted image. Mirrors the single-part shape when no attachments.
 *
 * [attachedFilesXml] is the `<user-attached-files>` inventory (non-image file
 * paths/sizes), persisted as a trailing text part so it round-trips through
 * retry / rerun / session-reload (iOS parity).
 */
fun buildUserPartsJson(
    text: String,
    mediaRefPartsJson: List<String>,
    attachedFilesXml: String? = null,
): String {
    val parts = mutableListOf<String>()
    if (text.isNotEmpty() || mediaRefPartsJson.isEmpty()) {
        parts.add("""{"type":"text","value":${escapeJson(text)}}""")
    }
    parts.addAll(mediaRefPartsJson)
    attachedFilesXml?.let { parts.add("""{"type":"text","value":${escapeJson(it)}}""") }
    return parts.joinToString(prefix = "[", postfix = "]", separator = ",")
}

/**
 * Parses the LLM title-generation response into a `(title, category)` pair.
 *
 * Tries JSON first (a `{"title": "...", "category": "..."}` object), then a
 * regex fallback over the raw text, then a plain first-line fallback truncated
 * to 50 chars. Returns `null` category when none is present.
 */
fun parseTitleResponse(text: String): Pair<String, String?> {
    val cleaned = text.trim()
        .removePrefix("```json").removePrefix("```")
        .removeSuffix("```").trim()
    // Try JSON parse
    try {
        val json = JSONObject(cleaned)
        val title = json.optString("title", "").trim()
        val category = json.optString("category", "").trim().ifEmpty { null }
        if (title.isNotEmpty()) return title to category
    } catch (_: Exception) {}
    // Regex fallback: extract "title" value
    val titleMatch = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
    val catMatch = Regex("\"category\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
    if (titleMatch != null) {
        return titleMatch.groupValues[1].trim() to catMatch?.groupValues?.getOrNull(1)?.trim()
    }
    // Plain text fallback: use first line
    val firstLine = cleaned.lines().firstOrNull()?.trim() ?: ""
    return firstLine.take(50) to null
}
