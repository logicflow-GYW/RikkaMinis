package com.openminis.app.ui.chat

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import java.io.File

/**
 * LLM-history builders + display-strip composition extracted from
 * ChatViewModel (FE-5 route A). State-free, Android-free (no Uri / Log /
 * context): they take plain values and return values.
 *
 * - [buildLlmMessagesFromParsed] + [buildSingleLlmMessage] are the
 *   DB-rows → LLM history transform, with the media base dir parameterized
 *   (the only impure dependency the original had).
 * - [stripDisplayOnlyArtifacts] composes the two display-only strips that
 *   already live in ChatViewModelUtils.kt.
 *
 * Behavior is byte-for-byte identical to the inline logic they replace.
 */

// NOTE (FE-5): friendlyToolTitle / parseToolParams / stripSystemReminders /
// stripAttachedFilesXml / systemReminderRegex were ALREADY top-level internal
// functions in ChatViewModelUtils.kt — the ChatViewModel private copies deleted
// in this extraction were historical duplicates from an earlier FE-4 partial
// split. Only the composition helper + the LLM-history builders are new here.

/** Merge the two display strips; trim only when something was actually stripped. */
internal fun stripDisplayOnlyArtifacts(text: String): String {
    val out = stripAttachedFilesXml(stripSystemReminders(text))
    return if (out != text) out.trim() else out
}

/**
 * DB rows → [LLMMessage] history, from the single parse pass
 * ([ParsedRow] parts already decoded by [parsePartsJson]).
 *
 * @param mediaBaseDir base dir used to re-inline persisted image mediaRefs
 *   (T128/T150: only image files are re-inlined; non-image attachments are
 *   streamed to disk and never re-inlined into contentParts).
 */
internal fun buildLlmMessagesFromParsed(
    parsed: List<ParsedRow>,
    mediaBaseDir: File,
): List<LLMMessage> {
    val result = ArrayList<LLMMessage>(parsed.size)
    for (row in parsed) {
        result.add(buildSingleLlmMessage(row.entity, row.entity.partsJson, row.parts, row.malformed, mediaBaseDir))
    }
    return result
}

internal fun buildSingleLlmMessage(
    entity: MessageEntity,
    partsJson: String,
    parts: List<ParsedPart>,
    malformed: Boolean,
    mediaBaseDir: File,
): LLMMessage {
    val r = if (entity.role == "user") LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT
    val contentParts = mutableListOf<AgentContentPart>()
    val imageParts = mutableListOf<LLMMessage.ImagePart>()
    val textContent = StringBuilder()

    if (malformed) {
        textContent.append(partsJson)
        contentParts.add(AgentContentPart.Text(partsJson))
    } else {
        for (part in parts) {
            when (part) {
                is ParsedPart.Text -> {
                    val value = part.value
                    if (value.contains("<user-attached-files>")) {
                        contentParts.add(AgentContentPart.Text(value))
                    } else {
                        textContent.append(value)
                        contentParts.add(AgentContentPart.Text(value))
                    }
                }
                is ParsedPart.ToolUse -> {
                    val inputJson = try {
                        org.json.JSONObject(part.input)
                    } catch (_: Exception) {
                        org.json.JSONObject()
                    }
                    contentParts.add(AgentContentPart.ToolUse(
                        id = part.id,
                        name = part.name,
                        input = inputJson,
                    ))
                }
                is ParsedPart.ToolResult -> {
                    contentParts.add(AgentContentPart.ToolResult(
                        id = part.toolUseId,
                        name = part.name,
                        content = part.output,
                        isError = !part.success,
                    ))
                }
                is ParsedPart.MediaRef -> {
                    // T128: restore persisted image files so they survive a
                    // session reload; only image mediaRefs are inlined into the
                    // model request (T150: non-image attachments are streamed
                    // to disk and never re-inlined into contentParts).
                    val rel = part.relativePath
                    if (rel.isEmpty()) continue
                    val mime = part.mimeType
                    if (!mime.startsWith("image/")) continue
                    val file = File(mediaBaseDir, rel)
                    if (!file.exists()) continue
                    val bytes = try { file.readBytes() } catch (_: Exception) { continue }
                    val restoredPath = part.linuxPath
                    imageParts.add(LLMMessage.ImagePart(bytes, mime, linuxPath = restoredPath))
                    contentParts.add(AgentContentPart.ImageData(bytes, mime, linuxPath = restoredPath))
                }
            }
        }
    }

    return LLMMessage(
        role = r,
        content = textContent.toString(),
        imageParts = imageParts,
        contentParts = contentParts,
        dbMessageId = entity.id,
        reasoningContent = entity.reasoningContent,
    )
}
