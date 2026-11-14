package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.db.MessageEntity
import com.rikkaminis.app.provider.openai.stripOrphanThinkClosers
import org.json.JSONArray

/**
 * Single parsed JSON part from a persisted message's `partsJson` column.
 * Eliminates repeated JSONArray→JSONObject→field allocations when the same
 * [MessageEntity] row is consumed by both [ChatViewModel.buildChatMessages]
 * (UI rendering) and [ChatViewModel.buildLlmMessages] (LLM history).
 */
sealed interface ParsedPart {
    /**
     * [fix/fold-paste-split-1114] [pasteStart] is the extra `pasteStart` key
     * that `buildUserPartsJson` writes onto the user row's first text part: the
     * offset in [value] where the pasted block begins. Null for every row
     * written before this change and for every assistant text part.
     *
     * It is carried here (not in a separate part) so every existing reader of
     * `ParsedPart.Text` keeps working unchanged — the LLM history builder, the
     * debug RPC and the backup exporter all read [value] and ignore the offset.
     */
    data class Text(val value: String, val pasteStart: Int? = null) : ParsedPart
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
                "text" -> ParsedPart.Text(
                    value = obj.optString("value", ""),
                    // [fix/fold-paste-split-1114] Absent (legacy row) or
                    // out-of-range → null, which the renderer treats as "one
                    // body, fold it whole" (the pre-fix behaviour).
                    pasteStart = if (obj.has("pasteStart")) {
                        obj.optInt("pasteStart", 0).takeIf { it > 0 }
                    } else {
                        null
                    },
                )
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
 * [T-think-tag-orphan-close] Drop wire-artifact think closers from PERSISTED
 * assistant text before either consumer sees it — [ChatViewModel.buildChatMessages]
 * (UI transcript) and [ChatViewModel.buildLlmMessages] (LLM history) both read
 * these parts, so cleaning at this boundary fixes read AND send in one place.
 *
 * Assistant rows only: a user row carries tool output, where a `</thinking>`
 * inside command stdout is evidence, not an artifact. The stored row is never
 * rewritten — the strip runs on every read, so it is idempotent and needs no
 * migration (rule + its two guards: [stripOrphanThinkClosers]).
 */
internal fun List<ParsedPart>.stripOrphanThinkClosersForRole(role: String): List<ParsedPart> {
    if (role != "assistant") return this
    var changed = false
    val cleaned = map { part ->
        if (part is ParsedPart.Text) {
            val value = stripOrphanThinkClosers(part.value)
            if (value != part.value) {
                changed = true
                ParsedPart.Text(value)
            } else {
                part
            }
        } else {
            part
        }
    }
    return if (changed) cleaned else this
}

/**
 * Parse a batch of [MessageEntity] rows into [ParsedRow]s, parsing each
 * entity's `partsJson` exactly once.
 */
fun parseRows(rows: List<MessageEntity>): List<ParsedRow> {
    return rows.map { entity ->
        val result = tryParsePartsJson(entity.partsJson)
        ParsedRow(
            entity = entity,
            parts = result?.stripOrphanThinkClosersForRole(entity.role) ?: emptyList(),
            sourceChars = entity.partsJson.length,
            malformed = result == null && entity.partsJson.isNotBlank(),
        )
    }
}