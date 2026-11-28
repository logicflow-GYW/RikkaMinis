package com.rikkaminis.app.ui.chat

// [T-android-split-chat] Flat-chat-item data model + flatten/merge transforms
// extracted verbatim from ChatScreen.kt: FlatChatItem (sealed), mergeStreamingOverlay,
// message-level aggregate builders. All row types and transforms are internal.

import androidx.compose.runtime.Immutable

@Immutable
internal sealed class FlatChatItem {
    abstract val key: String
    abstract val contentType: String

    /**
     * Cheap-equals — compare by the wrapped message. User messages are short and don't
     * stream, but during a streaming overlay rebuild we still re-create the
     * entire FlatChatItem list, and LazyColumn calls equals to decide skip.
     * Compare by id + reference identity of the wrapped ChatMessage.
     */
    /**
     * [T-android-candidate-bubble-gap] `precededByUser` is true when the
     * immediately-preceding flat item is also a user bubble (e.g. two
     * candidate / queued messages sent back to back). Consecutive user
     * bubbles have no intervening assistant message row to create visual
     * separation, and the LazyColumn's `spacedBy(2.dp)` alone is too tight
     * — the two bubbles read as one. When set, UserMessageBubble adds extra
     * top padding so the pair is clearly two distinct messages.
     */
    @Immutable
    class UserBubble(
        val message: ChatMessage,
        val precededByUser: Boolean = false,
    ) : FlatChatItem() {
        override val key = "user:${message.id}"
        override val contentType = "user"
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UserBubble) return false
            // ChatMessage is a data class; reuse its equals (cheap for user
            // bubbles which carry short content and small attachment lists).
            return message == other.message && precededByUser == other.precededByUser
        }
        override fun hashCode(): Int = message.hashCode() * 31 + precededByUser.hashCode()
    }

    /**
     * [fix/message-node-item-generator] Message-level aggregated item —
     * the "one node, one card" row used by the aggregate pipeline. A whole assistant message
     * collapses into a single item instead of being flattened into 6-7 rows
     * (header + fragment blocks + tool run group + thinking). Stage D renders
     * THIS item via the reused AssistantMessageView.
     *
     * [T-android-cheap-equals-aggregate] Its equals is
     * hand-rolled cheap: a *frozen* message (same instance every tick —
     * the ledger path reuses frozen instances by reference) returns the
     * identity-equal fast path so LazyColumn stable-skips; a *streaming*
     * message arrives as a fresh instance each emit, so `message !==` is
     * true and the row recomposes — precisely the live-tail behavior wanted
     * for the active turn. Never a char-by-char walk of content / blocks.
     */
    @Immutable
    class AssistantMessageItem(
        val messageId: String,
        val message: ChatMessage,
        /** Joined raw markdown of the whole message — selection toolbar Copy Markdown. */
        val messageMarkdown: String,
    ) : FlatChatItem() {
        override val key = "msg:$messageId"
        override val contentType = "assistantMessage"
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AssistantMessageItem) return false
            return messageId == other.messageId && message === other.message
        }
        override fun hashCode(): Int = messageId.hashCode() * 31 + System.identityHashCode(message)
    }
}

/**
 * [P0-0] The owning message id for any flat row.
 *
 * User rows carry the id inside the message; assistant rows expose it directly.
 * This mapping is shared by focus, pagination and incremental prefix reuse.
 */
internal fun FlatChatItem.owningMessageId(): String = when (this) {
    is FlatChatItem.UserBubble -> message.id
    is FlatChatItem.AssistantMessageItem -> messageId
}

/**
 * T-streaming-side-channel: overlay any active [StreamingDelta]s on top of
 * the canonical [messages] list, producing the snapshot
 * [buildAggregateChatItems] should fold over. The original [messages] list is
 * never mutated; affected entries are replaced via `copy()` so downstream
 * keying / equality stays correct. When [streaming] is empty the input is
 * returned as-is to skip the per-element walk on idle frames.
 */
internal fun mergeStreamingOverlay(
    messages: List<ChatMessage>,
    streaming: Map<String, StreamingDelta>,
    currentEpoch: Long = 0L,
): List<ChatMessage> {
    if (streaming.isEmpty()) return messages
    return messages.map { m ->
        val delta = streaming[m.id] ?: return@map m
        // [T-android-thinking-indicator-linger] Epoch filter: a delta from a
        // previous turn (cancelled-then-resent, trailing flush that survived
        // streamJob.cancel) carries an older epoch — ignore it here so it can
        // never force a stale message back to isStreaming=true, which is what
        // rendered the residual second "thinking" row.
        if (delta.epoch != currentEpoch) return@map m
        m.copy(
            content = delta.content,
            isStreaming = true,
            toolBlocks = delta.toolBlocks,
            isAwaitingModelResponse = delta.isAwaitingModelResponse,
        )
    }
}

/**
 * [fix/message-node-item-generator] Message-level aggregate generator — the
 * aggregate builder. Semantic contract: **one
 * [ChatMessage] in → exactly one [FlatChatItem] out**.
 *
 *  - user role      → [FlatChatItem.UserBubble], `precededByUser` mirrors the
 *                     existing lookback (previous message is also user).
 *  - assistant role → [FlatChatItem.AssistantMessageItem] carrying the whole
 *                     message; `messageMarkdown` is the joined raw markdown
 *                     of the message's text-kind blocks (falling back to
 *                     `content`), byte-consistent with the `joinedMarkdown`
 *                     used by the selection toolbar for Copy Markdown.
 *  - `isInternalBridge` messages are always skipped (defensive, mirrors the
 *    uiMessages sink's bridge filter) — a bridge must never render.
 *
 * No ledger / segmenter / skipTextBlocks machinery: the whole list is built
 * in one pass, rendered by AssistantMessageView.
 */
internal fun buildAggregateChatItems(messages: List<ChatMessage>): List<FlatChatItem> =
    buildAggregateChatItemsFrom(messages, 0)

/**
 * [fix/long-session-aggregate-storm] Incremental aggregate rebuild — the
 * AGGREGATE_MESSAGE_ITEMS counterpart of the ledger's frozen/live split.
 *
 * The aggregate path had NO increment: every collect tick (and every
 * pause/cancel that drains the side-channel and restarts the messages-keyed
 * effect) rebuilt the WHOLE list via [buildAggregateChatItems], O(N) per
 * tick. In a long session that is the same flatten storm the ledger path
 * already fixed — but the aggregate branch short-circuited around it.
 *
 * Because one message maps to exactly one item (bridge messages skipped),
 * the increment is simple and prefix-stable:
 *  1. Identity-prefix scan: frozen messages are the SAME instance across
 *     ticks ([mergeStreamingOverlay] only `copy()`s the streamed tail), so
 *     we find the first `===` mismatch and reuse the matching item prefix
 *     by reference.
 *  2. Full match → return `prevItems` as-is (zero allocation, zero rebuild).
 *  3. Tail mismatch → rebuild only the suffix from the first changed
 *     message, keeping every earlier item's identity so LazyColumn's
 *     key+equals skip path sees no change for already-composed rows.
 *
 * `precededByUser` lookback reads the previous RAW message (even a bridge),
 * so the suffix rebuild must know the role of the last prefix message; a
 * full `[]`/`null` prev degenerates to a plain [buildAggregateChatItems].
 */
internal fun buildAggregateChatItemsIncremental(
    prevItems: List<FlatChatItem>,
    prevMessages: List<ChatMessage>,
    messages: List<ChatMessage>,
): List<FlatChatItem> {
    // Cold start / first publish → full build.
    if (prevItems.isEmpty() || prevMessages.isEmpty()) return buildAggregateChatItems(messages)

    // 1) Identity prefix length (in MESSAGES). Frozen messages are the same
    //    instance; only the streamed/live tail (and any structural change)
    //    breaks identity.
    var prefix = 0
    val n = minOf(prevMessages.size, messages.size)
    while (prefix < n && prevMessages[prefix] === messages[prefix]) prefix++

    // 2) Everything identical → reuse by reference.
    if (prefix == prevMessages.size && prefix == messages.size) return prevItems

    // 3) Map message-prefix → item-prefix. Each non-bridge prefix message
    //    produced exactly one item, so the item prefix count = number of
    //    non-bridge messages in messages 0..<prefix. We walk prevItems and
    //    stop at the first item whose owning id is NOT in the prefix set
    //    (robust against future builder changes; ids are unique).
    val prefixIds = HashSet<String>(prefix * 2)
    for (i in 0 until prefix) prefixIds.add(prevMessages[i].id)
    var prefixItems = 0
    while (prefixItems < prevItems.size &&
        prefixIds.contains(prevItems[prefixItems].owningMessageId())
    ) {
        prefixItems++
    }

    // 4) Rebuild the suffix from messages prefix..size. Builder is pure; we only
    //    need `precededByUser` correct for the suffix's first user message,
    //    which looks back at the last prefix message.
    val suffix = buildAggregateChatItemsFrom(messages, fromIndex = prefix)
    val out = ArrayList<FlatChatItem>(prefixItems + suffix.size)
    for (i in 0 until prefixItems) out.add(prevItems[i])
    out.addAll(suffix)
    return out
}

/**
 * Build the aggregate items for messages fromIndex..size, used by the
 * incremental path's suffix rebuild. Semantics identical to
 * [buildAggregateChatItems] restricted to the suffix; `precededByUser` for
 * the suffix's first element still looks back at messages[fromIndex - 1]
 * (the caller guarantees fromIndex ≤ size; a fromIndex of 0 yields the full
 * build).
 */
internal fun buildAggregateChatItemsFrom(
    messages: List<ChatMessage>,
    fromIndex: Int,
): List<FlatChatItem> {
    val out = mutableListOf<FlatChatItem>()
    val usedKeys = mutableSetOf<String>()
    for (idx in fromIndex until messages.size) {
        val message = messages[idx]
        if (message.isInternalBridge) continue
        if (message.role == "user") {
            val prevIsUser = idx > 0 && messages[idx - 1].role == "user"
            val key = "user:${message.id}"
            if (usedKeys.add(key)) {
                out.add(FlatChatItem.UserBubble(message, precededByUser = prevIsUser))
            }
            continue
        }
        val messageMarkdown = run {
            val parts = message.toolBlocks
                .filter { it.kind == "text" && it.content.isNotEmpty() }
                .joinToString("\n\n") { it.content }
            if (parts.isNotEmpty()) parts else message.content
        }
        out.add(FlatChatItem.AssistantMessageItem(
            messageId = message.id,
            message = message,
            messageMarkdown = messageMarkdown,
        ))
    }
    return out
}
