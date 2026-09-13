package com.rikkaminis.app.ui.chat.legacy

// [refactor/legacy-pipeline-isolation] The pre-aggregate flat-chat-item
// generator, moved here VERBATIM from ChatFlatItems.kt (its only callers are
// the RUNTIME-DEAD ledger path in ChatScreen.kt and StableChatRowLedger).
//
// RUNTIME-DEAD while AGGREGATE_MESSAGE_ITEMS = true: the aggregate collect
// branch early-returns before any call site, so nothing in this file runs in
// the shipped app. Kept as the Stage-E fallback (see
// docs/scroll-follow-simplification.md). Do not call it from new code — the
// scan gate (scripts/scan/check_legacy_pipeline.py) rejects references
// outside the legacy allow-list.

import com.rikkaminis.app.ui.chat.ChatMessage
import com.rikkaminis.app.ui.chat.FlatChatItem
import com.rikkaminis.app.ui.chat.ToolBlockStatus

internal fun buildFlatChatItems(
    messages: List<ChatMessage>,
    // [T-android-perf-logging] Optional — when supplied, emit a progress
    // breadcrumb every 100 messages so a low-memory repro shows which batch
    // drives the heap up. Null (default) skips all progress logging, so the
    // hot per-token streaming rebuild path stays log-free.
    sessionId: String? = null,
    // [T-android-stream-pipeline-incremental] Build rows only for
    // messages[fromIndex, size). Neighbor lookbacks (precededByUser /
    // isResumeContinuation) still read the FULL list, so a suffix built with
    // fromIndex > 0 is row-for-row identical to the same span of a full
    // build — rows never depend on later messages, only earlier ones.
    fromIndex: Int = 0,
    // Dedupe continuity for split builds: pass the key set of the frozen
    // prefix so the defensive key-collision suffixing behaves exactly as a
    // single full build would.
    seedKeys: Set<String> = emptySet(),
    // [fix/long-session-flatten-storm] Skip the expensive Pass 2 text-block
    // markdown split entirely. Used by the StableChatRowLedger's live-tail
    // reconcile path, where per-block text is ALWAYS owned by the
    // AppendOnlyMarkdownSegmenter (pass 3) and the freshly-split
    // AssistantMarkdownBlock rows would be immediately discarded by
    // `filterNot { it is AssistantMarkdownBlock }` — a full re-split paid for
    // twice, with one result thrown away. Turning it off eliminates that
    // dual-split allocation storm on every 80ms streaming tick without
    // changing any published row: non-text rows (header / tool group /
    // thinking / info / typing / error) are byte-identical either way, and
    // text rows never derive from this builder in the ledger path.
    skipTextBlocks: Boolean = false,
): List<FlatChatItem> {
    val out = mutableListOf<FlatChatItem>()
    val usedKeys = if (seedKeys.isEmpty()) mutableSetOf() else seedKeys.toMutableSet()
    fun dedupe(item: FlatChatItem): FlatChatItem {
        // Defensive: duplicated keys crash LazyColumn. If any slip through, suffix
        // a counter until unique. This should never fire if upstream dedup is correct.
        if (usedKeys.add(item.key)) return item
        var n = 2
        while (!usedKeys.add("${item.key}#$n")) n++
        return when (item) {
            is FlatChatItem.UserBubble -> FlatChatItem.UserBubble(item.message.copy(id = "${item.message.id}#$n"), item.precededByUser)
            is FlatChatItem.AssistantHeader -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantMarkdownBlock -> FlatChatItem.AssistantMarkdownBlock(
                messageId = "${item.messageId}#$n",
                parentBlockId = item.parentBlockId,
                rawText = item.rawText,
                blockIndex = item.blockIndex,
                isLastBlockOfMessage = item.isLastBlockOfMessage,
                messageIsStreaming = item.messageIsStreaming,
                messageMarkdown = item.messageMarkdown,
            )
            is FlatChatItem.AssistantThinking -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantToolRunGroup -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantInfo -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantTyping -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantError -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantLegacyContent -> FlatChatItem.AssistantLegacyContent(
                messageId = "${item.messageId}#$n",
                content = item.content,
                isStreaming = item.isStreaming,
                messageMarkdown = item.messageMarkdown,
            )
            is FlatChatItem.AssistantMessageItem -> FlatChatItem.AssistantMessageItem(
                messageId = "${item.messageId}#$n",
                message = item.message,
                messageMarkdown = item.messageMarkdown,
            )
        }
    }
    for (idx in fromIndex until messages.size) {
        val message = messages[idx]
        // [T-android-perf-logging] Per-100-message progress breadcrumb.
        // `out.size` is the running row count, so a sudden jump between two
        // progress lines localizes the heavy batch. Only fires on the
        // full-build path (sessionId != null), never per streaming token.
        if (sessionId != null && idx > 0 && idx % 100 == 0) {
            com.rikkaminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "buildFlatChatItems.progress",
                "msgIdx=$idx of=${messages.size} rowsSoFar=${out.size}",
            )
        }
        if (message.role == "user") {
            // [T-android-candidate-bubble-gap] Flag when the previous message
            // is also a user message so the bubble can add a separating top
            // gap — back-to-back candidate / queued sends otherwise have no
            // AssistantHeader between them and visually merge.
            val prevIsUser = idx > 0 && messages[idx - 1].role == "user"
            out.add(dedupe(FlatChatItem.UserBubble(message, precededByUser = prevIsUser)))
            continue
        }
        // System messages (slash-command notices, compact divider, etc.) render
        // as horizontal-divider rows — no "Minis" attribution, no card. Skip
        // the assistant header so each info block stands on its own. Mirrors
        // iOS systemDividerRow / compactDividerRow.
        val isSystem = message.role == "system"
        val joinedMarkdown = run {
            val parts = message.toolBlocks
                .filter { it.kind == "text" && it.content.isNotEmpty() }
                .joinToString("\n\n") { it.content }
            if (parts.isNotEmpty()) parts else message.content
        }
        // T83: when Resume creates a fresh assistant bubble after the user
        // stopped a streaming turn, the previous (cancelled) assistant
        // message is right before this one in the list. Visually they should
        // read as one continuous turn — suppress the duplicate "Minis"
        // header. Skip system rows when looking back since they render as
        // dividers, not as separate speaker turns. iOS achieves this by
        // reusing the existing ChatMessage in runAgentLoop(resumingAt:);
        // we reach the same end-result at the render layer.
        val prevNonSystem = (idx - 1 downTo 0).asSequence()
            .map { messages[it] }
            .firstOrNull { it.role != "system" }
        val isResumeContinuation = prevNonSystem?.role == "assistant"
        if (!isSystem && !isResumeContinuation) {
            out.add(dedupe(FlatChatItem.AssistantHeader(message.id)))
        }

        val blocks = message.toolBlocks
        val toolPillBlocks = blocks.filter { it.kind == "tool_use" }
        val lastTextIdx = blocks.indexOfLast { it.kind == "text" }
        val hasAnyTextBlock = lastTextIdx >= 0
        // Only the last cancelled tool_use in the message gets the Retry button —
        // retryLast() re-runs the whole turn, so one button is enough.
        val lastCancelledToolId = blocks.lastOrNull { it.kind == "tool_use" && it.toolStatus == ToolBlockStatus.CANCELLED }?.id
        // [T-android-tool-run-collapse] First tool_use block index — the group
        // row is emitted exactly once, at the first tool, and covers the rest.
        val firstToolIndex = blocks.indexOfFirst { it.kind == "tool_use" }

        // ─────────────────────────────────────────────────────────────────
        // [T-android-run-group-first] Emit the rows of one assistant message
        // in a FIXED order regardless of the order the model produced the
        // blocks in: thinking row → tool run card → answer text. Rows come
        // FIRST (process before result reads naturally); because thinking and
        // the tool card are collapsed by default, they never push the answer
        // around. Then the answer text, then info rows.
        //
        // [T-thinking-split-row] Thinking is emitted as ONE independent
        // AssistantThinking row (all same-message thinking blocks merged),
        // BEFORE the tool run group. This drops the old double-fold where
        // thinking was hidden inside a group that was itself collapsed: a
        // thinking turn now needs ONE tap to reveal, collapses independently
        // of the tools, and never fights the run-group expand state.
        // Default-collapsed, tap-to-expand remains the ThinkingBlock contract.
        val thinkingBlocks = blocks.filter { it.kind == "thinking" }
        if (thinkingBlocks.isNotEmpty()) {
            // The row carries ONE thinking block whose content is the merged
            // reasoning of all same-message blocks (keeps the first id for
            // stable ThinkingBlock state). Whether it's the message's last
            // *visible* block of any kind drives the streaming auto-fold
            // signal: if a sibling text/tool_use exists, thinking is already
            // "done" and the row collapses the moment that sibling arrives.
            val lastRealBlock = blocks.lastOrNull { !(it.kind == "text" && it.content.isEmpty()) }
            val thinkingIsTrailing = thinkingBlocks.any { it === lastRealBlock }
            val mergedThinking = thinkingBlocks.first().copy(
                content = thinkingBlocks.joinToString("\n") { it.content },
            )
            out.add(dedupe(FlatChatItem.AssistantThinking(
                messageId = message.id,
                block = mergedThinking,
                isLast = true,
                messageIsStreaming = message.isStreaming,
                messageThinkingLevel = message.thinkingLevel,
                isLastBlockOverall = thinkingIsTrailing,
            )))
        }

        // Tool run group — tools ONLY now (thinking lives in its own row above).
        if (firstToolIndex >= 0) {
            out.add(dedupe(FlatChatItem.AssistantToolRunGroup(
                messageId = message.id,
                tools = toolPillBlocks,
                // No thinking blocks folded in — they are a separate
                // AssistantThinking row emitted above.
                thinkingBlocks = emptyList(),
                isRunning = toolPillBlocks.any {
                    it.toolStatus == ToolBlockStatus.STREAMING ||
                        it.toolStatus == ToolBlockStatus.PENDING ||
                        it.toolStatus == ToolBlockStatus.RUNNING
                },
                isLastCancelled = lastCancelledToolId != null &&
                    lastCancelledToolId == toolPillBlocks.lastOrNull()?.id,
                messageThinkingLevel = message.thinkingLevel,
            )))
        }

        // Pass 2 — text blocks (the answer), in model order.
        if (!skipTextBlocks) {
        blocks.forEachIndexed { index, block ->
            if (block.kind != "text") return@forEachIndexed
            if (block.content.isNotEmpty()) {
                val isLastText = index == lastTextIdx
                        // Pattern A: split this text block's content into
                        // independent markdown fragments so each becomes its
                        // own LazyColumn item. Frozen prefix fragments are
                        // anchored separately by LazyList; only the trailing
                        // live fragment can change height during streaming.
                        //
                        // [T-android-defensive-fragment-merge] For a FROZEN
                        // (non-streaming) message, coalesce adjacent
                        // plain-text fragments so a long reply produces a
                        // handful of rows instead of dozens — cuts cold-open
                        // full-build row count ~8x and eases GC pressure on
                        // low-memory devices. The live streaming tail message
                        // keeps fine-grained fragments so only the trailing
                        // paragraph re-parses per token (Pattern A jank
                        // optimization preserved). Code fences stay standalone
                        // either way.
                        val rawFragments = splitMarkdownIntoBlockTexts(block.content)
                        // [T-android-stream-end-reflow-flicker-v18] Preserve
                        // per-fragment FlatChatItem keys across the
                        // streaming→idle boundary. Previously the trailing
                        // text block kept rawFragments only while
                        // `message.isStreaming==true`; the moment it flipped
                        // false the fragments coalesced into fewer rows, all
                        // mdblock:msgId:parentBlockId:N keys for N >= K
                        // suddenly vanished from the flatItems list. That
                        // wipe-and-rebuild was the "整个页面像被重刷" the
                        // user reported — LazyColumn lost every key it was
                        // using to anchor the viewport, fell back to numeric
                        // firstVisibleItemIndex, and parked the viewport on
                        // whatever row happened to take that numeric slot
                        // (often the previous assistant message).
                        //
                        // Fix: keep the live (== last in the list) text block
                        // on rawFragments regardless of isStreaming. The
                        // boundary that actually warrants coalesce is "a
                        // NEWER message exists below this one" — i.e. a
                        // subsequent user turn pushed this assistant turn
                        // into history. Until then, the same key set the
                        // user was scrolled into stays valid.
                        // [T-android-flatitems-sublist-cme] Index-based scan
                        // instead of messages.subList(idx+1, size).all{} — a
                        // subList is a live view sharing the parent's modCount,
                        // which threw ConcurrentModificationException when the
                        // backing list changed under it. A plain index loop
                        // touches no view.
                        val isLastAssistantTurn = idx == messages.lastIndex ||
                            (idx + 1 until messages.size).all { messages[it].role != "assistant" }
                        val fragments = if (isLastText && isLastAssistantTurn) {
                            rawFragments
                        } else {
                            coalesceMarkdownFragments(rawFragments)
                        }
                        if (fragments.isEmpty()) {
                            // Defensive: if the splitter returns nothing for
                            // a non-empty input (shouldn't happen), fall back
                            // to a single fragment so content isn't dropped.
                            out.add(dedupe(FlatChatItem.AssistantMarkdownBlock(
                                messageId = message.id,
                                parentBlockId = block.id,
                                rawText = block.content,
                                blockIndex = 0,
                                isLastBlockOfMessage = isLastText && message.isStreaming,
                                messageIsStreaming = message.isStreaming && isLastText,
                                messageMarkdown = joinedMarkdown,
                            )))
                        } else {
                            fragments.forEachIndexed { fragIdx, raw ->
                                val isLastFragOfText = fragIdx == fragments.lastIndex
                                out.add(dedupe(FlatChatItem.AssistantMarkdownBlock(
                                    messageId = message.id,
                                    parentBlockId = block.id,
                                    rawText = raw,
                                    blockIndex = fragIdx,
                                    isLastBlockOfMessage = isLastText && isLastFragOfText,
                                    messageIsStreaming = message.isStreaming && isLastText,
                                    messageMarkdown = joinedMarkdown,
                                )))
                            }
                        }
                    }
                }
        }
        // Pass 3 — info blocks (inline system notices), in model order.
        blocks.forEach { block ->
            if (block.kind == "info") {
                out.add(dedupe(FlatChatItem.AssistantInfo(
                    messageId = message.id,
                    block = block,
                )))
            }
        }

        // Typing indicator: show only while streaming and NO visible content
        // has arrived yet. Once any answer content is on screen (or the
        // message has any blocks), the indicator is redundant — the thinking
        // already lives in the run-group card above the answer, and an extra
        // "thinking…" row below the text reads as a stray duplicate.
        // Mirrors iOS more strictly: iOS shows it during the initial network
        // gap only (`isActiveMessage && !hasVisibleContent`); the
        // isAwaitingModelResponse window (waiting after tool results were
        // sent back) previously re-inserted the row under finished text —
        // precisely the duplicate the user wants gone.
        val hasRealBlocks = blocks.any { it.kind != "info" }
        val hasVisibleContent = hasRealBlocks || message.content.isNotEmpty()
        if (message.isStreaming && !hasVisibleContent) {
            out.add(dedupe(FlatChatItem.AssistantTyping(message.id)))
        }

        // Legacy fallback: pre-migration sessions stored all text in message.content
        // with no text-kind blocks. Render it after blocks in that case only.
        if (!hasAnyTextBlock && message.content.isNotEmpty()) {
            out.add(dedupe(FlatChatItem.AssistantLegacyContent(
                messageId = message.id,
                content = message.content,
                isStreaming = message.isStreaming,
            )))
        }

        // Inline error banner
        message.error?.let {
            out.add(dedupe(FlatChatItem.AssistantError(message.id, it, message.errorDetail)))
        }
    }
    return out
}
