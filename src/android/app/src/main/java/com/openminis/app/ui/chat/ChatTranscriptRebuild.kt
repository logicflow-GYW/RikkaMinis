package com.openminis.app.ui.chat

import android.net.Uri
import com.openminis.app.data.db.MessageEntity
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

        // Skip user messages with no visible content
        if (entity.role == "user" && text.isBlank() && restoredImageUris.isEmpty()) return@mapNotNull null
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
