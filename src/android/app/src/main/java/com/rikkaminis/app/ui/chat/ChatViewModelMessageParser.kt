package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.db.MessageEntity
import org.json.JSONArray

/**
 * Single parsed JSON part from a persisted message's `partsJson` column.
 * Eliminates repeated JSONArray→JSONObject→field allocations when the same
 * [MessageEntity] row is consumed by both [ChatViewModel.buildChatMessages]
 * (UI rendering) and [ChatViewModel.buildLlmMessages] (LLM history).
 */
sealed interface ParsedPart {
    data class Text(val value: String) : ParsedPart
    data class ToolUse(
        val id: String,
        val name: String,
        val input: String,
        val description: String,
        val pageURL: String?,
        val imageFilePath: String?,
    ) : ParsedPart
    data class ToolResult(
        val toolUseId: String,
        val name: String,
        val output: String,
        val success: Boolean,
    ) : ParsedPart
    data class MediaRef(
        val relativePath: String,
        val mimeType: String,
        val originalFileName: String,
        val linuxPath: String?,
    ) : ParsedPart
}

/**
 * A [MessageEntity] paired with its pre-parsed [parts], so downstream
 * consumers iterate the parsed list once without re-calling [parsePartsJson].
 *
 * [sourceChars] is the raw JSON string length for diagnostic logging.
 * [malformed] is true when the JSON could not be parsed at all (the caller
 * should render a fallback instead of silently skipping the row).
 */
data class ParsedRow(
    val entity: MessageEntity,
    val parts: List<ParsedPart>,
    val sourceChars: Int,
    val malformed: Boolean,
)

/**
 * Parse a single `partsJson` string into [ParsedPart]s.
 * Returns null on malformed JSON so the caller can distinguish
 * "empty content" from "parse failure".
 */
fun tryParsePartsJson(partsJson: String): List<ParsedPart>? {
    if (partsJson.isBlank()) return emptyList()
    return try {
        val array = JSONArray(partsJson)
        val result = ArrayList<ParsedPart>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val part = when (val type = obj.optString("type")) {
                "text" -> ParsedPart.Text(value = obj.optString("value", ""))
                "toolUse" -> {
                    val v = obj.getJSONObject("value")
                    ParsedPart.ToolUse(
                        id = v.optString("toolUseId", ""),
                        name = v.optString("name", ""),
                        input = v.optString("input", "{}"),
                        description = v.optString("description", ""),
                        pageURL = v.optString("pageURL", "").ifEmpty { null },
                        imageFilePath = v.optString("imageFilePath", "").ifEmpty { null },
                    )
                }
                "toolResult" -> {
                    val v = obj.getJSONObject("value")
                    ParsedPart.ToolResult(
                        toolUseId = v.optString("toolUseId", ""),
                        name = v.optString("name", ""),
                        output = v.optString("output", ""),
                        success = v.optBoolean("success", true),
                    )
                }
                "mediaRef" -> {
                    val v = obj.optJSONObject("value")
                    if (v != null) {
                        ParsedPart.MediaRef(
                            relativePath = v.optString("relativePath", ""),
                            mimeType = v.optString("mimeType", "image/jpeg"),
                            originalFileName = v.optString("originalFileName", ""),
                            linuxPath = v.optString("linuxPath", "").ifEmpty { null },
                        )
                    } else null
                }
                else -> null
            }
            if (part != null) result.add(part)
        }
        result
    } catch (_: Exception) {
        null
    }
}

/**
 * Non-null variant of [tryParsePartsJson] for callers that don't need
 * to distinguish malformed input from empty content.
 */
fun parsePartsJson(partsJson: String): List<ParsedPart> =
    tryParsePartsJson(partsJson) ?: emptyList()

/**
 * Parse a batch of [MessageEntity] rows into [ParsedRow]s, parsing each
 * entity's `partsJson` exactly once.
 */
fun parseRows(rows: List<MessageEntity>): List<ParsedRow> {
    return rows.map { entity ->
        val result = tryParsePartsJson(entity.partsJson)
        ParsedRow(
            entity = entity,
            parts = result ?: emptyList(),
            sourceChars = entity.partsJson.length,
            malformed = result == null && entity.partsJson.isNotBlank(),
        )
    }
}