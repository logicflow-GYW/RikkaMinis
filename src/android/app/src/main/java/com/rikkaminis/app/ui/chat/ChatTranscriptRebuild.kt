package com.rikkaminis.app.ui.chat

import android.net.Uri
import java.io.File

/**
 * Pure DB-rows → UI transcript rebuild extracted from ChatViewModel
 * (FE-5 route A). The original [ChatViewModel.buildChatMessages] had
 * exactly two impure dependencies — [mediaStore.mediaBaseDir] (a File) and
 * [Uri.fromFile] — both parameterizable, so the whole transform is now a
 * top-level function taking the media base dir.
 *
 * Behavior is byte-for-byte identical to the inline logic it replaces,
 * including the two-pass tool-result merge and the consecutive-assistant
 * message coalescing.
 */

/** First-pass tool-result lookup key (toolUseId → output/success). */
internal data class ToolResultData(val output: String, val success: Boolean)

/**
 * [B-05 deep-audit-1037] Single source of truth for "does this user row show
 * anything?" — any of the three visible surfaces (caption text, restored
 * images, restored non-image attachments) counts. Before this predicate the
 * five entry points that ask the question each carried their own copy: the
 * transcript rebuild only checked images, the retry guards only checked
 * `content.isBlank()`, and the two DB truncation-count walks only counted
 * non-blank text parts — so a pure-attachment (no caption) user message
 * disappeared on session reload, could not be retried, and shifted the
 * retry/edit cutoff onto the wrong row.
 *
 * The collections are typed [Collection] so the UI-side call sites can pass
 * `List<Uri>` and the test harness can pass plain lists — the decision is
 * "any surface non-empty", never about the element type.
 */
internal fun userRowHasVisibleContent(
    content: String,
    imageUris: Collection<*>,
    attachmentUris: Collection<*>,
): Boolean =
    content.isNotBlank() || imageUris.isNotEmpty() || attachmentUris.isNotEmpty()

/**
 * parts_json variant of [userRowHasVisibleContent] for the two DB
 * truncation-count walks (the edit/retry cutoff search). A persisted user
 * row is a "visible user turn" iff it carries a `mediaRef` part (an
 * attachment survived — [T-android-retry-attachment-loss] and
 * [B-05 deep-audit-1037]) or a text part that is non-blank AFTER the
 * `<user-attached-files>` XML inventory is stripped and is not the resume()
 * synthetic `<system-reminder>` row (which has no UI bubble — counting it
 * shifts the cutoff one user message too early, the
 * [T-ios-retry-anchor-synthetic-user] contract). toolResult-only rows carry
 * neither surface and keep not counting.
 *
 * Malformed parts keep the pre-fix fail-open semantic of both walks (an
 * unparsable row still counts as a visible user turn).
 */
internal fun userPartsJsonHasVisibleContent(partsJson: String): Boolean {
    return try {
        val arr = org.json.JSONArray(partsJson)
        (0 until arr.length()).any { i ->
            val o = arr.getJSONObject(i)
            when (o.optString("type")) {
                "mediaRef" -> true
                "text" -> {
                    val v = o.optString("value", "")
                    v.isNotBlank() &&
                        stripAttachedFilesXml(v).isNotBlank() &&
                        !v.trimStart().startsWith("<system-reminder>")
                }
                else -> false
            }
        }
    } catch (_: Exception) {
        true
    }
}

/**
 * Rebuild the ordered UI [ChatMessage] list from parsed DB rows.
 *
 * Pass 1: collect toolResult outputs keyed by toolUseId.
 * Pass 2: convert rows, merging tool results into the preceding tool_use
 * block and skipping user rows with no visible content. Then coalesce
 * consecutive assistant messages into one (historical per-row writes from
 * the legacy persist path rendered as separate bubbles otherwise).
 *
 * @param mediaBaseDir base dir for restoring persisted mediaRef files.
 * @param log optional log sink (production wires Log.w; JVM tests no-op).
 */
internal fun buildChatMessagesTranscript(
    parsed: List<ParsedRow>,
    mediaBaseDir: File,
    log: (String) -> Unit = {},
): List<ChatMessage> {
    // First pass: extract all toolResult data keyed by toolUseId.
    // No JSON parsing — parts are already parsed.
    val toolResultMap = mutableMapOf<String, ToolResultData>()
    for (row in parsed) {
        if (row.entity.role != "user") continue
        for (part in row.parts) {
            if (part is ParsedPart.ToolResult) {
                if (part.toolUseId.isNotEmpty()) {
                    toolResultMap[part.toolUseId] = ToolResultData(
                        output = part.output,
                        success = part.success,
                    )
                }
            }
        }
    }

    // Second pass: convert messages, merging tool results into blocks.
    // Filter out user messages that only contain toolResult parts (no visible text).
    return parsed.mapNotNull { row ->
        val entity = row.entity
        var text = ""
        val blocks = mutableListOf<AssistantBlock>()
        val restoredImageUris = mutableListOf<Uri>()
        val restoredAttachmentNames = mutableListOf<String>()
        val restoredAttachmentUris = mutableListOf<Uri>()

        if (entity.role == "assistant" && !entity.reasoningContent.isNullOrEmpty()) {
            blocks.add(AssistantBlock(
                id = "thinking_restored_${entity.id}",
                kind = "thinking",
                content = entity.reasoningContent,
                toolTitle = "Thinking",
                toolStatus = ToolBlockStatus.SUCCESS,
            ))
        }

        if (row.malformed) {
            // T-PARTS-FALLBACK: short placeholder so the row still appears
            // (so the user can delete or scroll past it) but no longer
            // pulls megabytes through the layout pass.
            log(
                "buildChatMessages: failed to parse partsJson for id=${entity.id} " +
                    "len=${row.sourceChars} role=${entity.role}",
            )
            text = "(message could not be parsed: ${row.sourceChars} bytes)"
        } else {
            var textBlockCounter = 0
            for (part in row.parts) {
                when (part) {
                    is ParsedPart.Text -> {
                        val raw = part.value
                        // Strip <system-reminder> and <user-attached-files>
                        // from UI display only. The DB row + agentHistory
                        // keep the raw text so the LLM still sees it.
                        val t = stripDisplayOnlyArtifacts(raw)
                        if (t.isEmpty()) continue
                        text += t
                        if (entity.role == "assistant") {
                            blocks.add(AssistantBlock(
                                id = "text_restored_${entity.id}_${textBlockCounter++}",
                                kind = "text",
                                content = t,
                            ))
                        }
                    }
                    is ParsedPart.ToolUse -> {
                        val toolId = part.id
                        if (toolId.startsWith("thinking_")) continue
                        val result = toolResultMap[toolId]
                        blocks.add(AssistantBlock(
                            id = toolId,
                            kind = "tool_use",
                            toolName = part.name,
                            toolTitle = part.description,
                            toolArgs = part.input,
                            content = result?.output?.lines()?.takeLast(80)?.joinToString("\n") ?: "",
                            toolStatus = when {
                                result == null -> ToolBlockStatus.SUCCESS
                                !result.success && (
                                    result.output.startsWith(CANCELLED_MARKER) ||
                                        result.output.startsWith(LEGACY_CANCELLED_MARKER)
                                ) -> ToolBlockStatus.CANCELLED
                                // [T-dedup-neutral-status] Same-turn dedup
                                // drops carry a success-flagged synthetic
                                // "Deduplicated: …" result — restore the
                                // neutral DEDUPLICATED pill (was: SUCCESS,
                                // which made the block's status visually
                                // drift FAILED→SUCCESS across a reload).
                                result.success && result.output.startsWith("Deduplicated:") ->
                                    ToolBlockStatus.DEDUPLICATED
                                result.success -> ToolBlockStatus.SUCCESS
                                else -> ToolBlockStatus.FAILED
                            },
                            browserURL = part.pageURL,
                            imageFilePath = part.imageFilePath,
                        ))
                    }
                    is ParsedPart.MediaRef -> {
                        // T128: restore persisted media file:// URIs so images and
                        // attachments survive a session reload.
                        // T150: non-image attachments are streamed separately;
                        // only image files are inlined below.
                        if (entity.role != "user") continue
                        val rel = part.relativePath
                        if (rel.isEmpty()) continue
                        val file = File(mediaBaseDir, rel)
                        if (!file.exists()) continue
                        val name = part.originalFileName.ifEmpty { file.name }
                        if (part.mimeType.startsWith("image/")) {
                            restoredImageUris.add(Uri.fromFile(file))
                        } else {
                            restoredAttachmentUris.add(Uri.fromFile(file))
                        }
                        restoredAttachmentNames.add(name)
                    }
                    is ParsedPart.ToolResult -> {
                        // handled in first pass (toolResultMap)
                    }
                }
            }
        }

        // Skip user messages with no visible content.
        // [B-05 deep-audit-1037] The old guard only checked images — a pure
        // non-image attachment user message (restoredAttachmentUris filled,
        // :151) vanished on session reload. The verdict now goes through the
        // ONE shared predicate every visible-content entry point uses.
        if (entity.role == "user" && !userRowHasVisibleContent(text, restoredImageUris, restoredAttachmentUris)) return@mapNotNull null
        // Skip assistant messages that became empty
        if (entity.role == "assistant" && text.isBlank() && blocks.isEmpty()) return@mapNotNull null
        ChatMessage(
            id = entity.id,
            role = entity.role,
            content = text,
            imageUris = restoredImageUris,
            attachmentNames = restoredAttachmentNames,
            attachmentUris = restoredAttachmentUris,
            toolBlocks = blocks,
            sourceDbIds = listOf(entity.id),
            error = entity.errorInfo?.takeIf { it.isNotBlank() },
        )
    }.let { messages ->
        // Merge consecutive assistant messages into one:
        val merged = mutableListOf<ChatMessage>()
        for (msg in messages) {
            val prev = merged.lastOrNull()
            if (msg.role == "assistant" && prev?.role == "assistant") {
                val seen = mutableSetOf<String>()
                val combinedBlocks = (prev.toolBlocks + msg.toolBlocks)
                    .asReversed()
                    .filter { seen.add(it.id) }
                    .asReversed()
                val combinedText = when {
                    prev.content.isBlank() -> msg.content
                    msg.content.isBlank() -> prev.content
                    else -> prev.content + "\n\n" + msg.content
                }
                merged[merged.lastIndex] = prev.copy(
                    id = msg.id,
                    content = combinedText,
                    toolBlocks = combinedBlocks,
                    sourceDbIds = prev.sourceDbIds + msg.sourceDbIds,
                    error = msg.error ?: prev.error,
                )
            } else {
                merged.add(msg)
            }
        }
        merged
    }
}
