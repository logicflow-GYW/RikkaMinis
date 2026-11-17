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
 * or at least one attachment. Folded pastes do not count here: a detached chip
 * alone is not worth waking the capsule for, because the capsule is a MOVE
 * offer and a chip-only draft has nothing to say (an edit to this predicate
 * would change what the pill offers — see the incr1118 report). They DO travel
 * with a draft that qualifies: [ChatViewModelStore.PendingTransfer.pastedTexts]
 * carries the blocks the text cannot ([pastesCarriedByMove]), so a move no
 * longer drops them.
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

    /**
     * [incr1117-B F-05] One composer frame's verdict on the share arm.
     *
     * @property sawContent the latch to carry into the next frame: the share's
     *   content has been observed in the composer at least once since arming.
     * @property disarm true when the arm is SPENT — the draft this share seeded
     *   has been emptied by the user, so the flag must be cleared.
     */
    internal data class ShareArmFrame(val sawContent: Boolean, val disarm: Boolean)

    /**
     * Decide what the composer does with the share arm on this frame.
     *
     * **The bug this closes.** `_hasInjectedShareContent` was cleared in exactly
     * two places — a send, and a completed "Move to…". Deleting the shared
     * content only HID the capsule (`shouldShow` reads the draft), so the flag
     * stayed armed: the user then typed anything at all and the pill came back,
     * offering to move a draft that had nothing to do with the share.
     *
     * **Why the verdict is not simply `shareInjected && !canMove(...)`.** The
     * share drain appends its text through the composer command queue
     * ([ChatScreen] → `ComposerCommand.AppendText`), so there are frames — and,
     * for an attachment-less share, whole composition passes — where the flag is
     * armed and the draft is still empty. Disarming on those frames would drop
     * the capsule for every text-only share before its text ever landed. Hence
     * the [sawContent] latch: an arm can only be spent after the content it
     * seeded was actually seen. Attachments need no latch (they are added
     * synchronously before `markShareInjected()`), the text arm needs it.
     *
     * `shareInjected = false` resets the latch — the next share starts a fresh
     * arm, never one already marked as spent.
     */
    fun observeFrame(
        shareInjected: Boolean,
        draftText: String,
        attachmentCount: Int,
        sawContent: Boolean,
    ): ShareArmFrame {
        if (!shareInjected) return ShareArmFrame(sawContent = false, disarm = false)
        if (canMove(draftText, attachmentCount)) return ShareArmFrame(sawContent = true, disarm = false)
        return ShareArmFrame(sawContent = sawContent, disarm = sawContent)
    }
}
