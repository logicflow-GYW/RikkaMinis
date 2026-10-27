package com.rikkaminis.app.ui.chat

// [T-android-split-chat] @-mention picker methods extracted from ChatViewModel
// as top-level extension functions (call syntax unchanged). The 5 mention
// state fields they touch were flipped private->internal. Verbatim logic.

import android.util.Log
import com.rikkaminis.app.data.FileMentionIndex

/**
 * Inspect the input + caret position; if the caret sits inside an
 * `@<token>` (preceded by start-of-text or whitespace, no whitespace
 * between `@` and caret) open the mention picker and refresh the
 * filter. Otherwise close it. Mirrors iOS
 * [AIChatViewModel.updateMentionMenuState].
 *
 * Accepts both ASCII `@` and full-width `＠` (U+FF20) so CJK IMEs
 * substituting the full-width form still trigger the picker — same
 * convention as slash commands accepting `／`.
 */
internal fun ChatViewModel.updateMentionMenuState(text: String, caret: Int) {
    // The slash and mention pickers are mutually exclusive (iOS does
    // the same). Slash takes priority.
    if (_showSlashMenu.value) {
        if (_showMentionMenu.value) dismissMentionMenu()
        return
    }
    val safeCaret = caret.coerceIn(0, text.length)
    // Walk back from caret to find an `@` that opens the active token.
    var anchor = -1
    var i = safeCaret
    while (i > 0) {
        val prev = i - 1
        val ch = text[prev]
        if (ch.isWhitespace()) break
        if (ch == '@' || ch == '＠') {
            // Require start-of-text or whitespace before `@` so emails
            // ("foo@bar.com") don't pop the menu.
            if (prev == 0 || text[prev - 1].isWhitespace()) {
                anchor = prev
            }
            break
        }
        i = prev
    }
    if (anchor < 0) {
        if (_showMentionMenu.value) dismissMentionMenu()
        return
    }
    val filter = text.substring(anchor + 1, safeCaret)
    _mentionAnchor.value = anchor
    _mentionFilter.value = filter
    if (!_showMentionMenu.value) {
        _showMentionMenu.value = true
        // Mirror iOS: pre-select row 0 so a hardware-keyboard Return
        // commits the top match without an extra Down press.
        _mentionSelectedIndex.value = 0
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isNotEmpty()) fileMentionIndex.refreshIfNeeded(sid)
        Log.i(ChatViewModel.TAG, "mention menu open anchor=$anchor filter=\"$filter\"")
    } else {
        // Filter changed while open — clamp the highlight back into range
        // so it never points past the end of a shrunk filtered list.
        // Async: the next combine emission for [mentionEntries] will have
        // the new size; we only need to keep the index sane in the
        // interim. Concretely: if the user types past the only remaining
        // match, the filter narrows and on the next list emission the
        // composable's LaunchedEffect resets us back into bounds.
        val current = _mentionSelectedIndex.value
        if (current < 0) _mentionSelectedIndex.value = 0
    }
}

internal fun ChatViewModel.dismissMentionMenu() {
    if (!_showMentionMenu.value) return
    _showMentionMenu.value = false
    _mentionFilter.value = ""
    _mentionAnchor.value = -1
    _mentionSelectedIndex.value = -1
}

/**
 * T-at-filepicker-keyboard: hardware-keyboard navigation helpers. Wraparound
 * matches iOS so Up at row 0 lands at the last row and vice versa.
 */
internal fun ChatViewModel.mentionMenuUp() {
    val count = mentionEntries.value.size
    if (count <= 0) return
    val idx = _mentionSelectedIndex.value
    _mentionSelectedIndex.value = if (idx <= 0) count - 1 else idx - 1
}

internal fun ChatViewModel.mentionMenuDown() {
    val count = mentionEntries.value.size
    if (count <= 0) return
    val idx = _mentionSelectedIndex.value
    _mentionSelectedIndex.value = if (idx >= count - 1) 0 else idx + 1
}

/**
 * Commit the highlighted entry (or the first match) into [currentText],
 * returning the new (text, caret). Returns null when the menu has no
 * matches to commit, so the caller can fall through to its default
 * Return-key handler (e.g. send-on-enter).
 */
internal fun ChatViewModel.executeSelectedMention(
    currentText: String,
    currentCaret: Int,
): Pair<String, Int>? {
    val entries = mentionEntries.value
    if (entries.isEmpty()) return null
    val idx = _mentionSelectedIndex.value.let {
        if (it in entries.indices) it else 0
    }
    return selectMention(entries[idx], currentText, currentCaret)
}

/**
 * Replace the active `@<token>` in [currentText] with `@<linuxPath> ` and
 * return the new text + caret position. The caller writes both back into
 * its TextFieldValue so the cursor lands right after the inserted space
 * — exactly mirroring iOS [AIChatViewModel.selectMention] which sets
 * `inputText` and `pendingCaret` in lockstep.
 *
 * If the menu is not open the call is a no-op and returns the original
 * (text, caret).
 */
internal fun ChatViewModel.selectMention(
    entry: FileMentionIndex.Entry,
    currentText: String,
    currentCaret: Int,
): Pair<String, Int> {
    val anchor = _mentionAnchor.value
    if (anchor < 0 || anchor > currentText.length) {
        dismissMentionMenu()
        return currentText to currentCaret
    }
    // Replacement span: from `@` up to next whitespace (or end).
    var endOffset = anchor + 1
    while (endOffset < currentText.length && !currentText[endOffset].isWhitespace()) {
        endOffset++
    }
    val replacement = "@${entry.linuxPath} "
    val newText = currentText.substring(0, anchor) +
        replacement +
        currentText.substring(endOffset)
    val newCaret = anchor + replacement.length
    // Dismiss before announcing the new text so the consumer's
    // updateMentionMenuState callback (fired on text change) doesn't
    // re-open the menu against the inserted path.
    dismissMentionMenu()
    return newText to newCaret
}
