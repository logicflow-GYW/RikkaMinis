package com.openminis.app.ui.chat

import androidx.annotation.VisibleForTesting

/**
 * Session-lifetime stable row ledger for the chat transcript.
 *
 * Lifecycle: one instance per [ChatViewModel] lifetime (survives forward/back
 * navigation together with the LazyListState). Dropped when the VM dies — the
 * next cold open rebuilds rows from persisted messages via the canonical
 * [buildFlatChatItems].
 *
 * The contract (the reason this class exists):
 *  - Once a row key has been published it is NEVER deleted or reordered while
 *    its message stays the active turn. Only prefix appends happen.
 *  - New user/assistant messages append their rows at the tail.
 *  - The active assistant message's text is split by [AppendOnlyMarkdownSegmenter]
 *    per text block: settled fragments freeze forever, only the live tail slot
 *    grows. Stream end only settles — no re-split, no coalesce, no key churn.
 *  - thinking / tool / info / error rows are appended in FIRST-APPEARANCE
 *    order and never re-inserted by the fixed pass order of the builder.
 *  - Transient rows (typing indicator, error banner) may be dropped when they
 *    disappear — they live at the absolute tail and their removal does not
 *    move any anchored row. This is the single documented exception.
 *  - A retry / edit / resume that rewrites the turn's text-block structure
 *    (block ids changed) resets only THAT message's text rows.
 *
 * All methods are pure state transitions over [FlatChatItem] / [ChatMessage] —
 * no Android dependencies, fully JVM-testable.
 */
internal class StableChatRowLedger(
    // [fix/stream-segmenter-duplication] Optional sink for divergence events,
    // injected by the Android layer (ChatScreen) as AppLogger-backed. Defaults
    // to a no-op so this class stays 100% JVM-testable with zero Android
    // imports — unit tests construct it with no arguments and assert pure
    // state transitions; production passes a lambda that forwards to AppLogger.
    private val onDivergence: (messageId: String, blockId: String, count: Int, snippet: String) -> Unit = { _, _, _, _ -> },
) {

    private val rows = mutableListOf<FlatChatItem>()
    private var lastMessageIndex = -1

    /** Id of messages[0] at seed / first reconcile — the incremental-append
     *  contract only holds while the HEAD is unchanged (load-older prepends
     *  or a message deletion invalidates it and forces a full rebuild). */
    private var headMessageId: String? = null

    /** messageId → (textBlockId → segmenter). Cleared per message on text reset. */
    private val segmenters = mutableMapOf<String, MutableMap<String, AppendOnlyMarkdownSegmenter>>()

    /** [fix/chat-render-tick-scan] Cursor into [rows] for
     *  [syncActiveAssistantStatus]: monotonically advances across reconciles,
     *  so scanning the rows of the active assistant messages does not re-walk
     *  the whole list (typically a few hundred rows) from index 0 on every
     *  80ms tick. Invalidated on seed / structural rebuild / row removal. */
    private var activeScanCursor = 0

    /**
     * messageIds whose text rows are segmenter-owned (mdslot-style mdblock
     * rows produced inside [reconcileMessage]). Attached on first text-block
     * update; detached on text-reset. This is the authoritative "already
     * segmented" signal — key prefixes are NOT a reliable discriminator
     * because AssistantMarkdownBlock.key is fixed to "mdblock:...".
     */
    private val segmentedMessages = mutableSetOf<String>()

    /**
     * The ordered ids of messages already published by [seed] / [reconcile],
     * so far. [isIncrementallyCompatible] matches the FULL published prefix
     * against the latest canonical list — not just the head and a count — so
     * a same-index replacement or mid-list deletion that keeps count and head
     * unchanged still forces a reseed instead of silently leaving stale rows.
     */
    private val reconciledMessageIds = mutableListOf<String>()

    /**
     * messageIds of assistant messages that are still "live" in the UI
     * (streaming / awaiting model response / a RUNNING tool block). Once a
     * turn is no longer the last message it still gets status-synced against
     * the latest canonical snapshot so stale RUNNING/typing state is drained;
     * when the terminal state is written the id leaves this set.
     */
    private val activeAssistantIds = mutableSetOf<String>()

    /** Test-only: count of tracked live assistant message ids. */
    @VisibleForTesting internal fun activeAssistantIdsCount(): Int = activeAssistantIds.size

    /** Current published rows. Treat the result as immutable. */
    fun snapshot(): List<FlatChatItem> = rows.toList()

    /**
     * Whether [messages] can be reconciled incrementally against the current
     * ledger state. False when the message list was structurally changed in
     * a way the append-only model cannot follow: a new head (load-older,
     * compact, deletion), a truncated tail, or a same-index replacement
     * within the already-published prefix (queued placeholder → real user
     * message, or an interrupted assistant message dropped/reshaped).
     */
    fun isIncrementallyCompatible(messages: List<ChatMessage>): Boolean {
        val head = headMessageId ?: return true
        if (messages.firstOrNull()?.id != head) return false
        if (messages.size < reconciledMessageIds.size) return false
        // Full prefix match — catches same-index replacement / mid-list
        // deletion the head+count check alone cannot see.
        for (i in reconciledMessageIds.indices) {
            if (messages[i].id != reconciledMessageIds[i]) return false
        }
        return true
    }

    /**
     * Cold-open seed: hand over the canonical full build (callers run
     * [buildFlatChatItems] over the full history, including coalescing for
     * historical messages). [messageCount] anchors the incremental reconcile.
     */
    fun seed(initialRows: List<FlatChatItem>, messageCount: Int) {
        rows.clear()
        rows.addAll(initialRows)
        lastMessageIndex = messageCount - 1
        activeScanCursor = 0
        headMessageId = null // caller passes the new head on next reconcile
        segmenters.clear()
        activeAssistantIds.clear()
        // Reconcile re-derives the prefix (first reconcile after seed anchors
        // the head and snapshots the published ids).
        reconciledMessageIds.clear()
    }

    /**
     * Incremental reconcile against the latest canonical message list.
     * - Appends rows for messages beyond the last reconciled index.
     * - Reconciles the LAST message in place when it is an assistant message
     *   (live-tail text via segmenters; tool/thinking/info/error updates in
     *   place; new rows appended in first-appearance order).
     *
     * @return the refreshed snapshot (same as calling [snapshot]).
     */
    fun reconcile(messages: List<ChatMessage>): List<FlatChatItem> {
        if (messages.isEmpty()) return snapshot()
        if (lastMessageIndex < 0) {
            // No seed provided — fall back to a full canonical build.
            rows.clear()
            rows.addAll(buildFlatChatItems(messages))
            lastMessageIndex = messages.size - 1
            activeScanCursor = 0
            headMessageId = messages.firstOrNull()?.id
            reconciledMessageIds.clear()
            reconciledMessageIds.addAll(messages.map { it.id })
            seedActiveAssistantIds(messages)
            ensureLastMessageSegmented(messages)
            return snapshot()
        }

        // Anchor the head on the first incremental reconcile after seed().
        // seed() deliberately nulls it ("caller passes the new head on next
        // reconcile") but nothing ever did — so isIncrementallyCompatible()
        // always returned true and structural changes (an interrupted
        // assistant message dropped by handleUserCancelledCleanup, deletions,
        // truncation) were silently reconciled as tail appends, leaving stale
        // rows behind (e.g. a thinking placeholder whose message is gone).
        if (headMessageId == null) {
            headMessageId = messages.firstOrNull()?.id
            // First reconcile after a cold-open seed: the already-published
            // prefix is the full current history. Snapshot it so the prefix
            // check below is meaningful from the very first incremental step.
            reconciledMessageIds.clear()
            reconciledMessageIds.addAll(messages.take(lastMessageIndex + 1).map { it.id })
            seedActiveAssistantIds(messages)
        }

        // Sync queued→sent flips on already-published user rows. A message
        // published as isQueued=true (dashed bubble) keeps its id when the
        // prompt queue drains — only the flag flips. Count and head stay
        // identical, so neither the append branch nor
        // isIncrementallyCompatible() notices; the row would keep its dashed
        // styling forever.
        syncQueuedFlips(messages)

        // Sync stale live-state (RUNNING tool pills / typing / awaiting)
        // of assistant messages that were once the active turn but have since
        // become HISTORY (a new user turn was appended below them by
        // enqueuePrompt → injectQueuedPromptsAsNewTurn). Their canonical rows
        // reflect the terminal state (tool SUCCESS/FAILED/CANCELLED,
        // isStreaming=false), and the viewport froze on the old snapshot, so
        // without this pass a "thinking…"/"Running" ghost stays pinned to a
        // finished turn (the interrupted-turn leftover). Only rows belonging
        // to those specific messages are touched — everything else is left
        // untouched to preserve stable scrolling.
        syncActiveAssistantStatus(messages)

        // 1. Append rows for brand-new messages (tail), keeping neighbour
        //    lookbacks (precededByUser / resume header suppression) correct.
        //    Text rows of new assistant messages are NOT taken from the
        //    builder — they go through the segmenters inside
        //    reconcileMessage so their keys are stable mdslot keys.
        if (messages.size > lastMessageIndex + 1) {
            for (idx in lastMessageIndex + 1 until messages.size) {
                val msg = messages[idx]
                val prevRole = prevNonSystemRole(messages, idx)
                if (msg.role == "assistant") {
                    // [fix/long-session-flatten-storm] Non-text rows only — the
                    // text rows of this new assistant message are produced by
                    // reconcileMessage's segmenters (pass 3) right below, so
                    // splitting the text here and filtering it away was a wasted
                    // full markdown parse per append.
                    val nonText = buildNewMessageNonTextRows(msg, prevRole)
                    rows.addAll(nonText)
                    reconcileMessage(msg, prevRole)
                } else {
                    rows.addAll(buildNewMessageRows(msg, prevRole))
                }
                if (isLiveAssistant(msg)) activeAssistantIds.add(msg.id)
                reconciledMessageIds.add(msg.id)
            }
            lastMessageIndex = messages.size - 1
            pruneActiveAssistantIds(messages)
            return snapshot()
        }

        // 2. Live-tail reconcile of the last message when it is an assistant
        //    message (streaming or a freshly-finished turn).
        ensureLastMessageSegmented(messages)
        return snapshot()
    }

    /**
     * The LAST assistant message's text rows are always owned by segmenters
     * (mdslot keys) — it is the candidate live turn. Everything before it is
     * canonical history (mdblock keys, coalesced) and stays frozen.
     */
    private fun ensureLastMessageSegmented(messages: List<ChatMessage>) {
        val last = messages.lastOrNull() ?: return
        if (last.role != "assistant") return
        reconcileMessage(last, prevNonSystemRole(messages, messages.lastIndex))
    }

    /**
     * [fix/chat-render-turnend-settle] Turn-end convergence guard.
     *
     * Call right after [reconcile] on the turn-end tick (stream empty, side
     * channel drained). The reconcile keeps every published key — by design —
     * but [FlatChatItem.AssistantMarkdownBlock.equals] compares rawText by
     * LENGTH only (a cheap stable-skip proxy for LazyColumn), so a same-length
     * content rewrite between the last streaming tick and the terminal
     * snapshot ("AAAA" → "BBBB") would be invisible to the key+equals skip
     * decision and the stale text would stay rendered forever. This pass
     * re-derives each owned segmenter's slots from the canonical terminal
     * text and force-publishes any slot whose CONTENT differs (exact string
     * equality — not length) on the same key. When the canonical text
     * diverges so far the slot count changes (a "AAAA|BBBB" two-paragraph
     * rewrite), the segmenter is reset and the text rows re-attached once —
     * a deliberate, bounded re-render for a true model rewrite, the only case
     * where the immutable-settled-slot invariant cannot represent the
     * terminal truth.
     *
     * No-op for non-assistant tails, messages without text rows, or when the
     * canonical and segmenter views already agree — so streaming ticks and
     * ordinary settles pay zero cost.
     */
    fun reconcileAndVerifyTerminalText(messages: List<ChatMessage>) {
        val last = messages.lastOrNull() ?: return
        if (last.role != "assistant") return
        val blocks = last.toolBlocks.filter { it.kind == "text" && it.content.isNotEmpty() }
        if (blocks.isEmpty()) return
        val perMsg = segmenters[last.id] ?: return
        val start = rows.indexOfFirst { it.owningMessageId() == last.id }
        if (start < 0) return

        val published = rows.subList(start, rows.size)
            .filterIsInstance<FlatChatItem.AssistantMarkdownBlock>().toList()
        if (published.isEmpty()) return

        val canonicalByBlock = blocks.associate { it.id to it.content }
        val joinedMarkdown = blocks.joinToString("\n\n") { it.content }
        val publishedByBlock = published.groupBy { it.parentBlockId }

        for (block in blocks) {
            val canonical = canonicalByBlock.getValue(block.id)
            val canonicalSlots = publishedByBlock[block.id]?.map { it.rawText } ?: continue
            // [fix/chat-render-verify-p1] The authoritative comparison is
            // "canonical terminal text's own split" vs "published rows" — NOT
            // the segmenter's output. Once a block has settled, the
            // segmenter's absorbDivergence shrink branch (fresh.size <=
            // settledCount) refuses to adopt a rewritten canonical ("AAAA" →
            // "BBBB"), so seg.update's returned slots stay equal to the stale
            // published rows and a same-length rewrite is never republished.
            // splitMarkdownIntoBlockTexts is the production splitter
            // (StreamingMarkdownText.kt) — identical semantics to the
            // segmenter's internal split, so when it disagrees with what's on
            // screen we must trust it over both segmenter and LazyColumn's
            // length-only equals.
            val canonicalSplit = splitMarkdownIntoBlockTexts(canonical)
            if (canonicalSplit != canonicalSlots) {
                // Canonical terminal diverges from published → force re-attach.
                // 1) Reset this block's segmenter (drops the frozen stale
                //    settled slots the absorbDivergence shrink branch kept).
                // 2) Rebuild rows directly from canonicalSplit — no dependence
                //    on seg.update's acceptance.
                perMsg.remove(block.id)
                val freshSlots = canonicalSplit.mapIndexed { idx, text ->
                    StableMarkdownSlot(ordinal = idx, rawText = text, settled = true)
                }
                rebuildBlockRows(last.id, block.id, freshSlots, joinedMarkdown)
                // A stale typing indicator would now sit under published
                // text — retire it.
                val itr = rows.listIterator(start)
                while (itr.hasNext()) {
                    val row = itr.next()
                    if (row is FlatChatItem.AssistantTyping && row.owningMessageId() == last.id) {
                        itr.remove()
                    }
                }
            }
        }
    }

    /** Replace one block's text rows with [slots] on the same message span. */
    private fun rebuildBlockRows(
        messageId: String,
        blockId: String,
        slots: List<StableMarkdownSlot>,
        joinedMarkdown: String,
    ) {
        val start = rows.indexOfFirst { it.owningMessageId() == messageId }
        if (start < 0) return
        val fresh = slots.map { slot ->
            FlatChatItem.AssistantMarkdownBlock(
                messageId = messageId,
                parentBlockId = blockId,
                rawText = slot.rawText,
                blockIndex = slot.ordinal,
                isLastBlockOfMessage = false,
                messageIsStreaming = false,
                messageMarkdown = joinedMarkdown,
            )
        }
        // Drop the old rows of this block only (other blocks / non-text rows
        // keep their position), then splice the fresh ones in at the same
        // place (first old row of the block).
        var spliceAt = -1
        val itr = rows.listIterator(start)
        while (itr.hasNext()) {
            val row = itr.next()
            if (row is FlatChatItem.AssistantMarkdownBlock && row.parentBlockId == blockId) {
                if (spliceAt < 0) spliceAt = itr.previousIndex()
                itr.remove()
            }
        }
        if (spliceAt >= 0) rows.addAll(spliceAt, fresh)
        // [fix/chat-render-verify-p1] P2: this method deletes + inserts rows,
        // which can change the row count and shift indices mid-list — the
        // activeScanCursor's monotonic-advance assumption (reused across
        // reconciles by syncActiveAssistantStatus) no longer holds. Reset it
        // so the next scoped sync re-walks from the active window's start.
        activeScanCursor = 0
    }

    // ── internals ───────────────────────────────────────────────────────────

    private fun buildNewMessageRows(
        message: ChatMessage,
        prevNonSystemRole: String?,
    ): List<FlatChatItem> {
        // NOTE: deliberately NO seedKeys passed to buildFlatChatItems. The
        // dedupe suffixing (key#2) must only guard against collisions WITHIN
        // this build; the ledger's own published keys are the merge target
        // (same message id → same keys expected), not collisions to avoid.
        val built = buildFlatChatItems(listOf(message), seedKeys = emptySet())
        return annotateRows(built, message, prevNonSystemRole)
    }

    /**
     * [fix/long-session-flatten-storm] Same as [buildNewMessageRows] but skips
     * the expensive Pass 2 text-block markdown split entirely
     * (`buildFlatChatItems(..., skipTextBlocks = true)`).
     *
     * Used by the live-tail paths (message [append] branch and
     * [reconcileMessage]) where per-block answer text is ALWAYS owned by the
     * [AppendOnlyMarkdownSegmenter] in pass 3 — emitting freshly-split
     * AssistantMarkdownBlock rows here would only have them thrown away by
     * `filterNot { it is AssistantMarkdownBlock }` a moment later. That was a
     * full `splitMarkdownIntoBlockTexts` paid for twice per 80ms streaming
     * tick, one result discarded (the GC/CPU storm this fix targets).
     *
     * Non-text rows (header / tool run group / thinking / info / typing /
     * error) are emitted identically either way; the typing-indicator
     * retirement decision only reads `AssistantTyping` presence, which is
     * computed independently of text-row emission.
     */
    private fun buildNewMessageNonTextRows(
        message: ChatMessage,
        prevNonSystemRole: String?,
    ): List<FlatChatItem> {
        val built = buildFlatChatItems(
            listOf(message),
            seedKeys = emptySet(),
            skipTextBlocks = true,
        )
        return annotateRows(built, message, prevNonSystemRole)
    }

    /** Shared header-suppression + precededByUser annotation for single-message builds. */
    private fun annotateRows(
        built: List<FlatChatItem>,
        message: ChatMessage,
        prevNonSystemRole: String?,
    ): List<FlatChatItem> {
        val suppressHeader = message.role == "assistant" && prevNonSystemRole == "assistant"
        return built.mapNotNull { row ->
            when {
                row is FlatChatItem.AssistantHeader && suppressHeader -> null
                row is FlatChatItem.UserBubble && prevNonSystemRole == "user" && !row.precededByUser ->
                    // UserBubble is a plain class (no copy) — reconstruct.
                    FlatChatItem.UserBubble(row.message, precededByUser = true)
                else -> row
            }
        }
    }

    private fun prevNonSystemRole(messages: List<ChatMessage>, index: Int): String? {
        for (i in index - 1 downTo 0) {
            if (messages[i].role != "system") return messages[i].role
        }
        return null
    }

    /**
     * In-place update of already-published [FlatChatItem.UserBubble] rows whose
     * message's queued state flipped (enqueuePrompt publishes isQueued=true,
     * drainQueuedPrompts flips it to false on the same message id). Cheap: only
     * runs when user bubbles exist and compares one boolean per row.
     */
    private fun syncQueuedFlips(messages: List<ChatMessage>) {
        if (rows.none { it is FlatChatItem.UserBubble }) return
        val freshById = messages.associateBy { it.id }
        // [fix/chat-render-tick-scan] The queued→sent flip is UNCONDITIONAL:
        // a message published as isQueued=true keeps its id when the queue
        // drains (flag flips to false) — the current snapshot has no queued
        // message, but the published row must still be refreshed. No fast
        // path here; the scan is bounded to UserBubble rows only.
        for (i in rows.indices) {
            val row = rows[i]
            if (row is FlatChatItem.UserBubble) {
                val fresh = freshById[row.message.id] ?: continue
                if (fresh.isQueued != row.message.isQueued) {
                    rows[i] = FlatChatItem.UserBubble(fresh, row.precededByUser)
                }
            }
        }
    }

    /**
     * Harvest the set of assistant messages that are currently "live" in the
     * UI (streaming / awaiting model response / any RUNNING tool) so that
     * [syncActiveAssistantStatus] keeps them updated even once they stop being
     * the last message (a queued prompt appended a new user turn below them).
     * Runs on seed / the first reconcile after a cold-open seed.
     */
    private fun seedActiveAssistantIds(messages: List<ChatMessage>) {
        activeAssistantIds.clear()
        for (msg in messages) {
            if (msg.role != "assistant") continue
            if (isLiveAssistant(msg)) activeAssistantIds.add(msg.id)
        }
    }

    /**
     * Drop ids of messages that moved to history and whose headline states are
     * terminal. Kept tiny so the status-sync pass stays O(active rows).
     */
    private fun pruneActiveAssistantIds(messages: List<ChatMessage>) {
        if (activeAssistantIds.isEmpty()) return
        val byId = messages.associateBy { it.id }
        activeAssistantIds.removeAll { id ->
            val msg = byId[id]
            msg == null || !isLiveAssistant(msg) || !hasLiveRowsOwnedBy(msg.id)
        }
    }

    private fun isLiveAssistant(msg: ChatMessage): Boolean =
        msg.isStreaming || msg.isAwaitingModelResponse ||
            msg.toolBlocks.any { it.toolStatus == ToolBlockStatus.STREAMING ||
                it.toolStatus == ToolBlockStatus.PENDING ||
                it.toolStatus == ToolBlockStatus.RUNNING }

    private fun hasLiveRowsOwnedBy(messageId: String): Boolean =
        rows.any {
            it.owningMessageId() == messageId &&
                (it is FlatChatItem.AssistantTyping ||
                    (it is FlatChatItem.AssistantToolRunGroup && it.isRunning))
        }

    /**
     * In-place sync of stale live-state rows (thinking placeholder, RUNNING
     * tool pills) of assistant messages that became history — the
     * interrupted-turn leftover. For each such message, compare its published
     * rows against the canonical fresh rows of the SAME message and replace
     * on the same keys, WITHOUT touching rows of other messages, re-segmenting
     * published markdown, or reordering anything — so stable scrolling is
     * preserved. Rows that legitimately disappear from canonical history
     * (typing placeholder) are dropped as transient.
     */
    private fun syncActiveAssistantStatus(messages: List<ChatMessage>) {
        // ── Pass 0: converged run-group cards, independent of the active
        // set. The user's interrupt (send while streaming) converges the
        // message (isStreaming=false, tools CANCELLED) and prune() removes
        // it from activeAssistantIds on the very next reconcile — so a
        // card-dependent pass would never reach it. But the card itself is
        // still published. Scan the published rows directly: for every
        // AssistantToolRunGroup whose message is no longer live, rebuild the
        // canonical card and swap it in place. Only that row moves; nothing
        // else (text rows, other turns) is touched, and the key
        // ("toolrun:<id>") is byte-identical, so the LazyColumn slot is
        // updated in place with zero churn — the local refresh the user
        // asked for ("刷新那个折叠思考和工具的框").
        val freshById = messages.associateBy { it.id }
        for (i in rows.indices) {
            val row = rows[i]
            if (row !is FlatChatItem.AssistantToolRunGroup) continue
            val freshMsg = freshById[row.messageId] ?: continue
            if (isLiveAssistant(freshMsg)) continue
            // Message converged. Build its canonical rows and find the
            // fresh card with the same key.
            val freshAll = buildNewMessageRows(freshMsg, null)
            val freshCard = freshAll.firstOrNull { it.key == row.key } ?: continue
            if (freshCard !is FlatChatItem.AssistantToolRunGroup) continue
            if (!sameLiveView(row, freshCard)) {
                rows[i] = freshCard
            }
        }
        if (activeAssistantIds.isEmpty()) return
        // [fix/chat-render-tick-scan] Only the ACTIVE assistant messages'
        // rows are scanned, from a monotonic cursor; converged non-live
        // messages are not re-walked every tick. Invalidate the cursor when
        // rows changed size/order since the last pass.
        if (activeScanCursor >= rows.size || rows[activeScanCursor].owningMessageId() != activeAssistantIds.firstOrNull()) {
            activeScanCursor = 0
        }
        for (messageId in activeAssistantIds.toList()) {
            val freshMsg = freshById[messageId] ?: continue
            val start = indexInRowsFromCursor(messageId)
            if (start < 0) continue
            val freshAll = buildNewMessageRows(freshMsg, null)
            val freshByKey = freshAll.associateBy { it.key }
            var rowsTouched = false
            for (i in start until rows.size) {
                val row = rows[i]
                if (row.owningMessageId() != messageId) break
                // Never touch frozen text rows: their segmenter-owned content
                // is authoritative. Only sync live-status rows that flip a
                // boolean/tool state (RUNNING→终态, typing→gone).
                if (row is FlatChatItem.AssistantMarkdownBlock) continue
                if (row is FlatChatItem.UserBubble) continue
                val fresh = freshByKey[row.key] ?: continue
                if (!sameLiveView(row, fresh)) {
                    rows[i] = fresh
                    rowsTouched = true
                    if (fresh is FlatChatItem.AssistantToolRunGroup && !fresh.isRunning) {
                        activeAssistantIds.remove(messageId)
                    }
                }
            }
            // If the message has fully converged (no live state), do a full
            // replacement of ALL rows owned by this message — including
            // text/markdown blocks that the live pass deliberately skips.
            // This catches the "interrupted thinking" leftover: the stream is
            // torn down, isStreaming flips to false, but the old thinking
            // markdown block stays in the ledger because the main reconcile
            // append branch didn't run (message count unchanged) and the live
            // pass skips markdown rows. Once converged, the canonical fresh
            // rows ARE the terminal truth (thinking collapsed to a stopped
            // summary, tools flipped to CANCELLED), so swap in the full set
            // regardless of whether the live pass touched anything.
            if (!isLiveAssistant(freshMsg)) {
                // Check whether the published rows actually differ from the
                // canonical ones — if they're already identical, skip.
                val publishedEnd = start + rows.subList(start, rows.size)
                    .takeWhile { it.owningMessageId() == messageId }.size
                val publishedTake = rows.subList(start, publishedEnd)
                if (publishedTake != freshAll) {
                    rows.subList(start, publishedEnd).clear()
                    rows.addAll(start, freshAll)
                }
                activeAssistantIds.remove(messageId)
            } else if (!rowsTouched) {
                // Canonical has no live rows for it anymore — drop the stale
                // typing placeholder if any.
                var removed = false
                val itr = rows.listIterator(start)
                while (itr.hasNext()) {
                    val row = itr.next()
                    if (row.owningMessageId() != messageId) break
                    if (row is FlatChatItem.AssistantTyping) {
                        itr.remove(); removed = true
                    }
                }
                if (removed) activeAssistantIds.remove(messageId)
            }
        }
    }

    /**
     * [fix/chat-render-tick-scan] Find the first row owned by [messageId],
     * scanning forward from the current [activeScanCursor]. Rows belonging
     * to a given message are contiguous and message ids appear in ledger
     * order, so the cursor stays valid across reconciles and each active
     * message is located in amortized O(rows owned by it) — never a full
     * list re-walk per tick.
     */
    private fun indexInRowsFromCursor(messageId: String): Int {
        var i = activeScanCursor.coerceIn(0, rows.size)
        while (i < rows.size) {
            if (rows[i].owningMessageId() == messageId) {
                activeScanCursor = i
                return i
            }
            i++
        }
        return -1
    }

    /** Whether two rows that share a key differ in their live-state fields. */
    private fun sameLiveView(a: FlatChatItem, b: FlatChatItem): Boolean = when {
        a is FlatChatItem.AssistantToolRunGroup && b is FlatChatItem.AssistantToolRunGroup ->
            a.isRunning == b.isRunning && sameBlockRefs(a.tools, b.tools)
        else -> a == b
    }

    private fun reconcileMessage(message: ChatMessage, prevNonSystemRole: String?) {
        val start = rows.indexOfFirst { it.owningMessageId() == message.id }
        if (start < 0) {
            // Defensive: no published rows yet (shouldn't happen — new messages
            // go through the append path). Build and append.
            rows.addAll(buildNewMessageRows(message, prevNonSystemRole))
            return
        }
        // [fix/long-session-flatten-storm] Non-text rows only. The text rows
        // of this LAST assistant message are owned by the per-block segmenters
        // in pass 3 below; the freshly-split AssistantMarkdownBlock rows the
        // old full build produced were thrown away by the filterNot. Building
        // WITHOUT the text-block markdown split halves the per-tick parse cost.
        val freshAll = buildNewMessageNonTextRows(message, prevNonSystemRole)
        val freshNonText = freshAll

        // ── typing indicator retirement ──
        // The freshly-built canonical rows carry NO AssistantTyping row once
        // any visible content exists (buildFlatChatItems emits it only for
        // the `isStreaming && !hasVisibleContent` window). If the message
        // already shows content, a previously-published typing row is now a
        // redundant "thinking…" under the answer text — retire it here so it
        // never lingers through the isAwaitingModelResponse gap.
        if (freshAll.none { it is FlatChatItem.AssistantTyping }) {
            val itr = rows.listIterator(start)
            while (itr.hasNext()) {
                if (itr.next() is FlatChatItem.AssistantTyping) itr.remove()
            }
        }

        // ── text-structure reset detection (first segmenter attach / retry) ──
        val currentTextIds = message.toolBlocks
            .filter { it.kind == "text" && it.content.isNotEmpty() }
            .map { it.id }
        val publishedTextIds = if (message.id in segmentedMessages) {
            rows.subList(start, rows.size)
                .filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
                .map { it.parentBlockId }
                .distinct()
        } else {
            // Not yet segmented → reset state needed; avoid building the set.
            emptyList()
        }
        // The message's text rows are segmenter-owned iff this ledger already
        // attached a segmenter for it (see [segmentedMessages]). Until then
        // (cold-open canonical build) they are plain canonical mdblock rows
        // that must be replaced on first attach. Key prefixes are NOT a
        // reliable discriminator: AssistantMarkdownBlock.key is fixed to
        // "mdblock:..." for both canonical and segmenter-owned rows.
        val textReset = message.id !in segmentedMessages || currentTextIds != publishedTextIds
        if (textReset) {
            segmenters.remove(message.id)
            segmentedMessages.remove(message.id)
            val itr = rows.listIterator(start)
            while (itr.hasNext()) {
                if (itr.next() is FlatChatItem.AssistantMarkdownBlock) itr.remove()
            }
        }

        val oldKeys = rows.subList(start, rows.size).map { it.key }.toSet()

        // ── pass 1+2: non-text rows — update in place, append new ──
        // [render-churn-2] Conditional in-place update: only replace a row
        // when its live view actually changed (sameLiveView). The previous
        // unconditional `rows[i] = fresh` re-created every non-text row
        // instance on EVERY tick even when nothing changed, defeating
        // LazyColumn's key+equals skip and recomposing the whole run card
        // (incl. completed pills) at 1Hz during tool progress churn.
        val freshNonTextByKey = freshNonText.associateBy { it.key }
        for (i in start until rows.size) {
            freshNonTextByKey[rows[i].key]?.let { fresh ->
                if (!sameLiveView(rows[i], fresh)) rows[i] = fresh
            }
        }
        for (row in freshNonText) {
            if (row.key !in oldKeys) rows.add(row)
        }

        // ── pass 3: text rows via per-block segmenters ──
        val joinedMarkdown = joinedMarkdownFor(message)
        val textBlocks = message.toolBlocks.filter { it.kind == "text" && it.content.isNotEmpty() }
        if (textBlocks.isNotEmpty()) {
            // Segmenter ownership begins now: subsequent ticks reuse the
            // segmenter (no text reset) until block ids change.
            segmentedMessages.add(message.id)
        }
        for (block in textBlocks) {
            val seg = segmenters.getOrPut(message.id) { mutableMapOf() }
                .getOrPut(block.id) { AppendOnlyMarkdownSegmenter() }
            val slots = seg.update(block.content, streamEnded = !message.isStreaming)
            // [fix/stream-segmenter-duplication] Surface the divergence counter
            // so a real-device repro leaves an actionable breadcrumb. The old
            // absorb path was the source of token-level duplication; the
            // rewrite path should now rarely fire (only on true model rewrites),
            // so ANY non-zero count during normal streaming is worth logging
            // with enough context to confirm the trigger and rule out a live
            // regression while we read the fix back on-device.
            if (seg.invariantErrorCount > 0) {
                val snippet = slots.joinToString("¦") { it.rawText }.take(60)
                onDivergence(message.id, block.id, seg.invariantErrorCount, snippet)
            }
            val isLastTextBlock = block.id == textBlocks.lastOrNull()?.id
            val freshTextRows = slots.map { slot ->
                FlatChatItem.AssistantMarkdownBlock(
                    messageId = message.id,
                    parentBlockId = block.id,
                    rawText = slot.rawText,
                    blockIndex = slot.ordinal,
                    isLastBlockOfMessage = message.isStreaming && isLastTextBlock && slot.ordinal == slots.last().ordinal,
                    messageIsStreaming = message.isStreaming && isLastTextBlock,
                    messageMarkdown = joinedMarkdown,
                )
            }
            val publishedTextKeys = rows.subList(start, rows.size)
                .filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
                .map { it.key }
                .toSet()
            val freshTextByKey = freshTextRows.associateBy { it.key }
            for (i in start until rows.size) {
                val row = rows[i]
                if (row is FlatChatItem.AssistantMarkdownBlock) {
                    val fresh = freshTextByKey[row.key] ?: continue
                    // [render-churn-2] Conditional replace: same-content
                    // ticks (tool-progress churn, unchanged streamed text)
                    // keep the published row INSTANCE — LazyColumn's
                    // key+equals skip then freezes completed fragments. Only
                    // content growth / streaming-flag flips replace.
                    if (row.rawText != fresh.rawText ||
                        row.isLastBlockOfMessage != fresh.isLastBlockOfMessage ||
                        row.messageIsStreaming != fresh.messageIsStreaming
                    ) {
                        rows[i] = fresh
                    }
                }
            }
            for (row in freshTextRows) {
                if (row.key !in publishedTextKeys) rows.add(row)
            }
        }

        // ── pass 4: drop transient rows that no longer appear ──
        val freshKeys = freshAll.mapTo(HashSet()) { it.key }
        val itr = rows.listIterator(start)
        while (itr.hasNext()) {
            val row = itr.next()
            if (row.key in freshKeys) continue
            when (row) {
                // Text rows are fully owned by segmenters — never touched here.
                is FlatChatItem.AssistantMarkdownBlock -> Unit
                // Transient tail rows may disappear (typing → first content,
                // error → retry). Documented exception to append-only.
                is FlatChatItem.AssistantTyping,
                is FlatChatItem.AssistantError,
                -> itr.remove()
                else -> Unit // anything else stays — append-only invariant
            }
        }
    }

    private fun joinedMarkdownFor(message: ChatMessage): String {
        val parts = message.toolBlocks
            .filter { it.kind == "text" && it.content.isNotEmpty() }
            .joinToString("\n\n") { it.content }
        return if (parts.isNotEmpty()) parts else message.content
    }
}
