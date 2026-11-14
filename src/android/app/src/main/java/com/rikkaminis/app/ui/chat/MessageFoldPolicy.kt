package com.rikkaminis.app.ui.chat

/**
 * [fix/user-msg-fold-1114] Fold thresholds for the two bubble families.
 *
 * Background: folded pastes are a COMPOSER-only affordance. `[Pasted#N]` chips
 * live in `PastedTextBuffer` (UI state), and `ChatViewModel.sendMessage` expands
 * every marker into the full body before the text reaches the transcript — so
 * the bubble renders what the MODEL received, i.e. the whole 15K paste. The only
 * render-side collapse was [LARGE_MESSAGE_THRESHOLD_CHARS] (32K chars), chosen to
 * stop the markdown GC storm on legacy 200KB tool_result rows, not as a fold for
 * ordinary reading. A 15K paste therefore sailed under it and filled the screen.
 *
 * Assistant bodies keep the 32K performance floor: folding a long answer by
 * default would hide the very thing the user asked for, and the guard's cost
 * model (markdown re-parse) is why the floor exists.
 *
 * User bodies fold much earlier — 4K chars is already ~60 rendered lines, well
 * past "I can see what I sent", and the 600-char preview is enough to recognise
 * the paste without re-reading it.
 *
 * Pure data + predicate, no Compose imports, so the thresholds are JVM-testable
 * in the sandbox instead of only in an instrumented build.
 */

/** Above this many chars a frozen USER bubble folds behind [LargeContentBadge]. */
internal const val USER_MESSAGE_COLLAPSE_THRESHOLD_CHARS = 4_000

/** How many leading chars the folded USER badge shows as a preview. */
internal const val USER_MESSAGE_PREVIEW_CHARS = 600

/**
 * Render-cost floor for ASSISTANT / tool bodies — unchanged.
 *
 * This is NOT a reading affordance: it exists because re-opening a legacy
 * session with 200KB+ tool_result rows fed the markdown parser (and its regex
 * scan) on the main thread, which escalated into a GC storm + ANR restart loop
 * (Issue #17 / commit 86c55a8). Fold anything above it behind the badge.
 *
 * Moved here (from LargeContentGuard.kt) so the whole fold policy — both
 * families' thresholds, the preview length and the predicate — is plain Kotlin
 * that a JVM test can compile and run in the sandbox.
 */
internal const val LARGE_MESSAGE_THRESHOLD_CHARS = 32_000

/** How many leading chars the folded assistant/tool badge shows as a preview. */
internal const val LARGE_MESSAGE_PREVIEW_CHARS = 8_000

/**
 * True when a frozen body should be folded instead of rendered inline.
 *
 * Streaming bodies are never folded: the buffer grows past the threshold
 * mid-turn and a fold/unfold flip on every growth tick is worse than the cost
 * the fold was protecting against (see [LargeContentGuard]).
 *
 * `>` not `>=`: a body of exactly [thresholdChars] renders inline, which keeps
 * the boundary literal and cheap to test.
 */
internal fun shouldCollapseMessage(
    contentLength: Int,
    isStreaming: Boolean,
    thresholdChars: Int,
): Boolean = !isStreaming && contentLength > thresholdChars

/**
 * [fix/fold-paste-split-1114] How a USER body splits into "what I typed" and
 * "what I pasted".
 *
 * The fold defect this fixes: the bubble folded the WHOLE message, so a 48-char
 * prompt plus a 14,929-char paste became one 12 KB card whose 600-char preview
 * was 48 chars of the user's own instruction followed by paste. The user's
 * words disappeared into a card labelled "content too large" — they could not
 * see what they had asked.
 *
 * The split point is PERSISTED (`paste` flag on the parts_json text part), not
 * re-derived: after `expandPastedText` flattens the block into one string there
 * is no boundary left to find, and heuristics over blank lines / "looks like a
 * log" would be guessing.
 *
 * Compatibility: rows written before this change carry no flag, so
 * [pasteStartIndex] is null and the caller folds the whole body — exactly the
 * previous behaviour. No migration, no field on `MessageEntity`.
 */
internal data class MessageBodySplit(
    /** What the user typed. Always rendered in full — never folded. */
    val prompt: String,
    /** The pasted material, or null when this message has no paste part. */
    val paste: String?,
)

/**
 * Split [content] at [pasteStartIndex].
 *
 * Returns the whole body as the prompt and null paste when the index is
 * missing/out of range — a damaged or absent index must degrade to "one body",
 * never to a lost half. The separator produced at send time is `"\n\n"`
 * (`appendUnlinkedPastes`), so the prompt is trimmed of the trailing newlines
 * the join added.
 */
internal fun splitMessageBody(content: String, pasteStartIndex: Int?): MessageBodySplit {
    if (pasteStartIndex == null) return MessageBodySplit(content, null)
    if (pasteStartIndex <= 0 || pasteStartIndex >= content.length) {
        return MessageBodySplit(content, null)
    }
    val prompt = content.substring(0, pasteStartIndex).trimEnd('\n')
    val paste = content.substring(pasteStartIndex)
    if (paste.isEmpty()) return MessageBodySplit(content, null)
    return MessageBodySplit(prompt, paste)
}
