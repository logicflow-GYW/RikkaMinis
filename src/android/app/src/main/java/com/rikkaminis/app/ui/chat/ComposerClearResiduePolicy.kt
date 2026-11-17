package com.rikkaminis.app.ui.chat

/**
 * [fix/composer-post-send-residue] How long after a composer clear a late IME
 * commit can still belong to the cleared body.
 *
 * Hardware calibration, not a guess: the clear runs `keyboardController.hide()`
 * + `focusManager.clearFocus()`, and `clearFocus` synchronously asks the IME to
 * `finishComposingText()`. The commit that comes back is an InputConnection
 * callback — it can only be delivered on the next main-thread pass, and third
 * party IMEs (Sogou / iFlytek / Baidu / voice engines) may replay the WHOLE
 * body from their own buffer instead of just the composing tail. Measured
 * delivery on-device is a frame to a few hundred ms; 1.5 s leaves room for a
 * slow device while staying shorter than "user sent, then pasted the same text
 * again", which is the only legitimate way to reproduce this shape.
 */
internal const val COMPOSER_CLEAR_RESIDUE_WINDOW_MS = 1_500L

/**
 * [fix/composer-post-send-residue] Shortest replay fragment worth dropping.
 *
 * A one-to-three character match is indistinguishable from the user typing
 * those characters into the freshly emptied box (they get the composer with no
 * text, so a prefix like "好" is a normal first keystroke). Dropping nothing
 * below this floor means the worst case of a missed replay is one short
 * fragment left in the box — never a swallowed keystroke.
 */
internal const val COMPOSER_CLEAR_RESIDUE_MIN_REPLAY_CHARS = 4

/**
 * [fix/composer-post-send-residue] Is this `onValueChange` a late IME replay of
 * the text a clear (send / slash / clear-chat / move-to) just removed?
 *
 * The composer clears itself by writing BOTH copies of the text (the
 * ViewModel's `inputText` and the field's `TextFieldValue`), which is correct
 * — but the IME owns its own copy of what the user was typing. When it commits
 * that copy after the clear, `onValueChange` writes the field unconditionally
 * (`inputFieldValue = tfv`) and then re-commits it to the ViewModel, so a
 * message that has already been sent reappears in the box (and, on the way,
 * re-fills the persisted draft slot). The pre-existing guard
 * (`ChatInputArea` T217-2) only covers commits that still carry a composing
 * region inside 500 ms; third party IMEs replay the body with
 * `composition == null` (or later) and slip through it.
 *
 * Every term is a hard requirement, and they are ordered cheapest-first:
 *  - a clear happened at all ([clearedText] non-null and non-empty);
 *  - the composer is STILL the emptied one — the field and the ViewModel both
 *    hold nothing. Once the user has typed anything, the field is non-empty
 *    and this predicate is off for good, so a genuine keystroke can never be
 *    mistaken for a replay;
 *  - the commit lands inside the post-clear window (a negative delta means the
 *    clock moved backwards — refuse rather than drop);
 *  - the incoming text IS the cleared body, or a long enough prefix/suffix of
 *    it (IME buffers replay the body in fragments; a fragment that is not a
 *    contiguous piece of it is the user's own text).
 *
 * Pure and Android-free so the decision is pinned on the JVM rather than in a
 * comment — see ComposerClearResiduePolicyTest.
 */
internal fun isComposerClearResidue(
    incoming: String,
    fieldText: String,
    vmText: String,
    clearedText: String?,
    msSinceClear: Long,
    windowMs: Long = COMPOSER_CLEAR_RESIDUE_WINDOW_MS,
    minReplayChars: Int = COMPOSER_CLEAR_RESIDUE_MIN_REPLAY_CHARS,
): Boolean {
    if (clearedText.isNullOrEmpty()) return false
    if (incoming.isEmpty()) return false
    if (fieldText.isNotEmpty() || vmText.isNotEmpty()) return false
    if (msSinceClear < 0L || msSinceClear >= windowMs) return false
    if (incoming.length < minReplayChars) return false
    return incoming == clearedText ||
        clearedText.startsWith(incoming) ||
        clearedText.endsWith(incoming)
}
