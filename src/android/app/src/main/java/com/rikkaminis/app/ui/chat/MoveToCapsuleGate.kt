package com.rikkaminis.app.ui.chat

/**
 * [fix/move-to-capsule-empty-1116] Visibility / action gate for the "Move to…"
 * capsule that floats on the composer's top-right after a system share seeds
 * the draft (T51 producer → T185 capsule).
 *
 * **Why this exists.** The capsule used to be gated on
 * `hasInjectedShareContent` alone, which is armed by the share drain and
 * cleared only three ways: send, clear chat, or a completed move. Deliberately
 * deleting the shared attachments — or clearing the text — left the flag on, so
 * the pill kept floating over an EMPTY draft: an offer with nothing behind it,
 * with no way to dismiss it (the Popup takes no back press, no outside tap, and
 * `onDismissRequest` is a no-op).
 *
 * Worse, tapping it in that state still executed the transfer: the composer
 * navigated to the target session and [ChatScreen]'s transfer drain called
 * `markShareInjected()` there, so the pill *followed* the user instead of
 * going away (real-device log: `[MoveTo] draining transfer … text=0ch
 * attachments=0`).
 *
 * **The rule.** "Worth showing" and "worth moving" are the same predicate:
 * the draft must still hold something that actually travels — non-blank text,
 * or at least one attachment. Folded pastes that are not referenced by the
 * field text do not travel (the paste buffer is per-ViewModel and stays
 * behind), so they do not count.
 *
 * Pure by design: the composer cannot be composed in a JVM unit test, so the
 * decision lives here and the Compose call site only reads it.
 */
internal object MoveToCapsuleGate {

    /** True when the composer holds content that a move would actually carry. */
    fun canMove(draftText: String, attachmentCount: Int): Boolean =
        draftText.isNotBlank() || attachmentCount > 0

    /**
     * True when the capsule should be on screen: a share armed it AND the
     * draft still has something to move. A share that was fully undone (all
     * chips deleted, text cleared) drops the capsule with no extra
     * bookkeeping — the two pieces of composer state are the reference.
     */
    fun shouldShow(
        shareInjected: Boolean,
        draftText: String,
        attachmentCount: Int,
    ): Boolean = shareInjected && canMove(draftText, attachmentCount)
}
