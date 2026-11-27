package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.ThinkingLevel

internal fun ChatMessage.canShowProcessThinking(): Boolean = (thinkingLevel ?: ThinkingLevel.MEDIUM).isEnabled

/** Presentation only. No later tool/status is allowed to reclassify a text block. */
internal fun AssistantBlock.isChatProcessBlock(): Boolean = kind == "tool_use" || kind == "thinking"

internal fun chatProcessBlocks(blocks: List<AssistantBlock>, showThinking: Boolean): List<AssistantBlock> =
    blocks.filter { it.isChatProcessBlock() && (showThinking || it.kind != "thinking") }

internal data class ChatProcessStatus(
    val calls: Int,
    val running: Int,
    val failed: Int,
    val cancelled: Int,
    val skipped: Int,
    val hasThinking: Boolean,
    val isThinkingNow: Boolean,
) {
    val hasDetails: Boolean get() = calls > 0 || hasThinking
    val allSucceeded: Boolean get() = running == 0 && failed == 0 && cancelled == 0 && skipped == 0
}

internal fun chatProcessStatus(blocks: List<AssistantBlock>, showThinking: Boolean): ChatProcessStatus {
    var calls = 0
    var running = 0
    var failed = 0
    var cancelled = 0
    var skipped = 0
    var hasThinking = false
    for (block in blocks) {
        when (block.kind) {
            "thinking" -> if (showThinking) hasThinking = true
            "tool_use" -> {
                calls++
                when (block.toolStatus) {
                    ToolBlockStatus.PENDING, ToolBlockStatus.STREAMING, ToolBlockStatus.RUNNING, null -> running++
                    ToolBlockStatus.FAILED, ToolBlockStatus.TIMEOUT -> failed++
                    ToolBlockStatus.CANCELLED -> cancelled++
                    ToolBlockStatus.DEDUPLICATED -> skipped++
                    ToolBlockStatus.SUCCESS -> Unit
                }
            }
        }
    }
    return ChatProcessStatus(
        calls, running, failed, cancelled, skipped, hasThinking,
        isThinkingNow = showThinking && blocks.lastOrNull { it.kind != "info" }?.kind == "thinking",
    )
}

/** One predicate drives the floating bar AND every piece of space it claims. */
internal fun showsLegacyToolBar(compactProcess: Boolean, enabled: Boolean, hasTools: Boolean): Boolean =
    !compactProcess && enabled && hasTools
