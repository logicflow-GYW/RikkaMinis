package com.rikkaminis.app.ui.chat

/**
 * Pure utility functions extracted from [ChatScreen] for JVM testability.
 *
 * [ChatScreen] itself is a 6000-line @Composable function with Android
 * dependencies. These functions are pure logic extracted so they can be
 * tested without any Android or Compose runtime.
 */

/**
 * Strip dedupe suffix from a flat-chat-item message id.
 * `"msgId#2"` → `"msgId"`, `"msgId"` → `"msgId"`.
 */
internal fun originalMessageId(id: String): String =
    id.substringBefore('#')

/**
 * Whether a flat chat item should render grayed-out (compacted history).
 * System rows ([FlatChatItem.AssistantInfo], [FlatChatItem.AssistantTyping])
 * are never grayed; every other row looks up its message id (dedupe suffix
 * stripped via [originalMessageId]) in [grayedMap].
 *
 * Extracted from ChatScreen's local `FlatChatItem.isCompacted()` extension so
 * the decision is JVM-testable without composing the screen.
 */
internal fun isCompactedItem(item: FlatChatItem, grayedMap: Map<String, Boolean>): Boolean = when (item) {
    is FlatChatItem.UserBubble -> grayedMap[originalMessageId(item.message.id)] == true
    is FlatChatItem.AssistantHeader -> grayedMap[originalMessageId(item.messageId)] == true
    is FlatChatItem.AssistantText -> grayedMap[originalMessageId(item.messageId)] == true
    is FlatChatItem.AssistantMarkdownBlock -> grayedMap[originalMessageId(item.messageId)] == true
    is FlatChatItem.AssistantThinking -> grayedMap[originalMessageId(item.messageId)] == true
    is FlatChatItem.AssistantToolUse -> grayedMap[originalMessageId(item.messageId)] == true
    is FlatChatItem.AssistantToolRunGroup -> grayedMap[originalMessageId(item.messageId)] == true
    is FlatChatItem.AssistantInfo -> false // system rows never grayed
    is FlatChatItem.AssistantTyping -> false
    is FlatChatItem.AssistantError -> grayedMap[originalMessageId(item.messageId)] == true
    is FlatChatItem.AssistantLegacyContent -> grayedMap[originalMessageId(item.messageId)] == true
    is FlatChatItem.AssistantMessageItem -> grayedMap[originalMessageId(item.messageId)] == true
}

// ── Forward-list index mapping (post-reverseLayout migration) ──────────────

/**
 * Index of the first row belonging to [messageId] in a FORWARD (oldest-first)
 * row list, or null if the message has no published rows.
 *
 * The reverseLayout-era code searched `flatItems.asReversed()` and worked in
 * mirror space; forward lists need no reversal — this is the one lookup that
 * replaced that whole class of conversions.
 */
internal fun firstRowIndexOfMessage(rows: List<FlatChatItem>, messageId: String): Int? {
    for (i in rows.indices) {
        if (rows[i].owningMessageId() == messageId) return i
    }
    return null
}

/** Index of the bottom sentinel row in a forward list (always the last slot). */
internal fun bottomSentinelIndex(rowCount: Int): Int = rowCount

/**
 * Where a previously-visible row lands after [headInsertedCount] rows were
 * prepended (load-older pagination). Key-anchored lists keep the same key;
 * only its numeric slot shifts — this is the load-older stability promise.
 */
internal fun shiftedIndexAfterHeadInsert(index: Int, headInsertedCount: Int): Int =
    index + headInsertedCount

/** Reverse-space → forward-space conversion for legacy call sites. */
internal fun forwardIndexFromReversed(reversedIndex: Int, rowCount: Int): Int =
    rowCount - 1 - reversedIndex

// ── IME burst debounce (voice dictation memory-pressure relief) ────────────

/**
 * Threshold (in characters) above which a single IME-driven text edit is
 * treated as a "burst" rather than ordinary typing. Voice dictation pushes
 * large blocks (often tens/hundreds of chars) in one onValueChange, each
 * triggering a full [ChatViewModel.setInputText] (draft serialization +
 * slash/mention recompute + history reconcile) on the main thread. Typing
 * advances 1-2 chars per event and must never be debounced.
 */
const val IME_BURST_DELTA_THRESHOLD = 8

/**
 * Whether an IME onValueChange edit from [oldText] to [newText] is a *large
 * incremental burst* that should be debounced before committing to the
 * ViewModel. Mirrors the composer's `newText.length - oldText.length > 8`
 * detection but as a pure, JVM-testable function.
 *
 * Rules:
 *  - A net *deletion* (new shorter than old) is never a burst — deletes are
 *    cheap single-pass edits and the user expects instant backspace feedback.
 *  - A net growth of more than [IME_BURST_DELTA_THRESHOLD] characters is a
 *    burst candidate (voice dictation, large paste).
 *  - Anything else (ordinary typing, small paste) is NOT debounced.
 *
 * Note: this only *classifies* the edit. The debounce scheduling (150 ms
 * buffer + flush) lives in the composable so it can own a coroutine scope;
 * the pure classifier is what's unit-tested here.
 */
internal fun shouldDebounceImeBurst(oldText: String, newText: String): Boolean {
    val delta = newText.length - oldText.length
    return delta > IME_BURST_DELTA_THRESHOLD
}

/**
 * [fix/ttfb-thinktag-composer] Whether a debounced IME-burst snapshot should
 * still be committed to the ViewModel when its 150 ms flush fires.
 *
 * Commit only while BOTH hold:
 *  - [snapshot] differs from the committed VM text (otherwise the commit is
 *    a no-op), and
 *  - the field's live text still equals the snapshot — newer edits that
 *    landed during the window (ordinary typing / deletes commit immediately)
 *    must not be overwritten by the stale snapshot.
 *
 * The caller must clear its buffer regardless of the answer: a snapshot that
 * outlives its flush must never be picked up by a later send (that stale
 * pickup was the "composer ate my text" bug).
 */
internal fun shouldCommitImeBurst(
    snapshot: String,
    committedVmText: String,
    liveUiText: String,
): Boolean = snapshot != committedVmText && liveUiText == snapshot