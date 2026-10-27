package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.db.MessageEntity
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.ui.settings.readAtMost
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
 * @param resizeForInference [debt-1026 B1] what the model may actually be
 *   sent for a restored image: `(rawBytes, mimeType) -> downscaledBytes?`
 *   (null = "this source already fits / could not be re-encoded, keep the raw
 *   bytes"). Injected instead of called so this file stays Android-free — the
 *   composer's own `resizeImageBytes` is the production implementation, and a
 *   JVM arm can pass a plain transform. Before the parameter existed the
 *   reload path inlined the ORIGINAL bytes: the composer downscales the model
 *   payload, so the first reload re-sent every historical photo at full
 *   resolution (a 12 MP JPEG is ~4 MB of base64 per turn, per image).
 *
 *   [W-1027 W-04] REQUIRED, no default. The parameter used to default to
 *   `{ _, _ -> null }`, i.e. "do not downscale": a call site that forgot the
 *   third argument compiled, passed every gate and every unit test (the JVM
 *   arms inject their own lambda, so they exercised the lambda they passed,
 *   never the wiring), and silently re-inlined full-resolution originals after
 *   a session reload. Removing the default turns that omission into a compile
 *   error — the only gate that cannot be forgotten at a new call site.
 */
internal fun buildLlmMessagesFromParsed(
    parsed: List<ParsedRow>,
    mediaBaseDir: File,
    resizeForInference: (ByteArray, String) -> ByteArray?,
): List<LLMMessage> {
    val result = ArrayList<LLMMessage>(parsed.size)
    for (row in parsed) {
        result.add(
            buildSingleLlmMessage(
                row.entity, row.entity.partsJson, row.parts, row.malformed, mediaBaseDir,
                resizeForInference,
            )
        )
    }
    return result
}

internal fun buildSingleLlmMessage(
    entity: MessageEntity,
    partsJson: String,
    parts: List<ParsedPart>,
    malformed: Boolean,
    mediaBaseDir: File,
    // [W-1027 W-04] Same contract as buildLlmMessagesFromParsed: no default,
    // so a direct call site cannot silently opt out of inference downscaling.
    resizeForInference: (ByteArray, String) -> ByteArray?,
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
                    // [debt-1026 B1] Bounded read: the previous bare
                    // `file.readBytes()` materialised the ORIGINAL asset with
                    // no ceiling, and the bytes then went straight to the
                    // provider.
                    val raw = try { readRestoredImageBytes(file) } catch (_: Exception) { null } ?: continue
                    // Downscale for the model payload only; the original stays
                    // on disk for the UI viewer / shell tools / re-fetch.
                    val bytes = resizeForInference(raw, mime) ?: raw
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

/**
 * [debt-1026 B1] Ceiling on the raw bytes a restored mediaRef may hand to the
 * model path.
 *
 * Same order as the SAF import caps and the home-icon picker: far above a real
 * photo (a 200 MP JPEG is ~30 MB) but low enough that a corrupt / mis-typed
 * asset in the attachments dir cannot OOM the heap before the bounded DECODE
 * runs — that bound only limits the bitmap, not the encoded array feeding it.
 */
internal const val MAX_RESTORED_IMAGE_BYTES = 64L * 1024 * 1024

/**
 * Read [file] but refuse anything above [cap] bytes (null = too large / read
 * failure). Thin wrapper over the shared [readAtMost] so the session-reload
 * path and the SAF imports enforce their ceiling with the same primitive, and
 * so a JVM arm can exercise the boundary with a small cap.
 */
internal fun readRestoredImageBytes(file: File, cap: Long = MAX_RESTORED_IMAGE_BYTES): ByteArray? =
    file.inputStream().use { readAtMost(it, cap) }
