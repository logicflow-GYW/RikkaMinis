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
 * [fix/composer-post-send-residue] Shortest replay fragment worth dropping while
 * the composer is still EMPTY.
 *
 * A one-to-three character match is indistinguishable from the user typing
 * those characters into the freshly emptied box (they get the composer with no
 * text, so a prefix like "好" is a normal first keystroke). Dropping nothing
 * below this floor means the worst case of a missed replay is one short
 * fragment left in the box — never a swallowed keystroke.
 */
internal const val COMPOSER_CLEAR_RESIDUE_MIN_REPLAY_CHARS = 4

/**
 * [fix/composer-post-send-residue] What the composer should do with an
 * `onValueChange` that arrives inside the post-clear window.
 *
 * Three outcomes rather than a boolean, because "this edit is residue" and "the
 * box now HOLDS residue" are different problems:
 *  * a character-by-character IME replay lands the first fragments before the
 *    text is long enough to be suspicious, so by the time the guard recognizes
 *    the replay the box is already partly filled. Dropping only the newest edit
 *    would stop the growth and still leave the user staring at half of the
 *    message he just sent — which is the exact symptom being fixed;
 *  * [DROP_AND_CLEAR] therefore also wipes what accumulated, and it is only
 *    reachable when everything currently in the box is a contiguous piece of
 *    the body that a clear just removed (never when the user typed something
 *    new).
 */
internal enum class ComposerResidueVerdict {
    /** Not a residue — handle the edit normally. */
    NONE,

    /** Residue, and the box was empty: ignore this edit, nothing to undo. */
    DROP,

    /** Residue, and the box already holds earlier fragments: ignore it and wipe them. */
    DROP_AND_CLEAR,
}

/**
 * [fix/composer-post-send-residue-2] Is [incoming] a contiguous piece of
 * [clearedText]?
 *
 * Shared by the verdict and by the fingerprint's "do not self-destruct" check so
 * the two can never disagree about what belongs to the body a clear removed.
 */
internal fun isFragmentOfClearedBody(incoming: String, clearedText: String): Boolean =
    incoming.isNotEmpty() && (
        incoming == clearedText ||
            clearedText.startsWith(incoming) ||
            clearedText.endsWith(incoming)
        )

/**
 * [fix/composer-post-send-residue] Decide what an `onValueChange` arriving right
 * after a composer clear really is.
 *
 * The composer clears itself by writing BOTH copies of the text (the
 * ViewModel's `inputText` and the field's `TextFieldValue`), which is correct —
 * but the IME owns its own copy of what the user was typing. When it commits
 * that copy after the clear, `onValueChange` writes the field unconditionally
 * (`inputFieldValue = tfv`) and then re-commits it to the ViewModel, so a
 * message that has already been sent reappears in the box (and, on the way,
 * re-fills the persisted draft slot). The pre-existing guard
 * (`ChatInputArea` T217-2) only covers commits that still carry a composing
 * region inside 500 ms; third party IMEs replay the body with
 * `composition == null` (or later) and slip through it.
 *
 * Two arms, keyed on whether anything has landed yet:
 *
 *  **Arm A — the box is still EMPTY** ([fieldText] and [vmText] both blank).
 *  A fragment of at least [minReplayChars] is the replay: a fresh keystroke into
 *  an empty box cannot be four characters at once.
 *
 *  **Arm B — the box already holds earlier fragments** ([fieldText] is itself a
 *  contiguous piece of the cleared body). This is the arm the first fix was
 *  missing: its guard returned NONE the moment either copy was non-empty, so a
 *  char-by-char IME replay disarmed it with its FIRST character and the rest
 *  streamed in unopposed. On-device proof the shape is real:
 *  `ComposerMirror` logged `field = vm + 1ch` on 14 dictation sessions on
 *  2026-11-18, i.e. the field is permanently one commit ahead of the ViewModel.
 *  Arm B returns [ComposerResidueVerdict.DROP_AND_CLEAR] — the accumulated text
 *  is all residue, so leaving it would reproduce the reported symptom.
 *
 * Every arm still requires: a clear happened, a non-negative delta inside the
 * window, and the incoming text to be a contiguous piece of the cleared body.
 * Anything else is [ComposerResidueVerdict.NONE] — a swallowed keystroke is a
 * worse failure than a short stale fragment, so every uncertain case is allowed.
 *
 * Pure and Android-free so the decision is pinned on the JVM rather than in a
 * comment — see ComposerClearResiduePolicyTest.
 */
internal fun composerResidueVerdict(
    incoming: String,
    fieldText: String,
    vmText: String,
    clearedText: String?,
    msSinceClear: Long,
    windowMs: Long = COMPOSER_CLEAR_RESIDUE_WINDOW_MS,
    minReplayChars: Int = COMPOSER_CLEAR_RESIDUE_MIN_REPLAY_CHARS,
): ComposerResidueVerdict {
    if (clearedText.isNullOrEmpty()) return ComposerResidueVerdict.NONE
    if (incoming.isEmpty()) return ComposerResidueVerdict.NONE
    // A negative delta means the clock moved backwards — refuse rather than drop.
    if (msSinceClear < 0L || msSinceClear >= windowMs) return ComposerResidueVerdict.NONE
    if (!isFragmentOfClearedBody(incoming, clearedText)) return ComposerResidueVerdict.NONE

    val boxEmpty = fieldText.isEmpty() && vmText.isEmpty()
    if (boxEmpty) {
        // Arm A: nothing has landed, so the four-character floor is safe.
        return if (incoming.length >= minReplayChars) {
            ComposerResidueVerdict.DROP
        } else {
            ComposerResidueVerdict.NONE
        }
    }

    // Arm B. Only treat the box as replay-filled while EVERYTHING in it is a
    // piece of the cleared body: one character of unrelated text means the user
    // has taken the composer back (or the input method inserted something of its
    // own) and wiping the box would destroy his work.
    val boxIsReplay = isFragmentOfClearedBody(fieldText, clearedText) ||
        isFragmentOfClearedBody(vmText, clearedText)
    if (!boxIsReplay) return ComposerResidueVerdict.NONE
    // `clearedText` is known non-null and non-empty here.
    return ComposerResidueVerdict.DROP_AND_CLEAR
}

/**
 * Boolean view of [composerResidueVerdict]: "this edit belongs to the cleared
 * body". Kept for callers that only need the drop decision; the composer uses
 * the full verdict because it must also undo accumulated fragments.
 */
internal fun isComposerClearResidue(
    incoming: String,
    fieldText: String,
    vmText: String,
    clearedText: String?,
    msSinceClear: Long,
    windowMs: Long = COMPOSER_CLEAR_RESIDUE_WINDOW_MS,
    minReplayChars: Int = COMPOSER_CLEAR_RESIDUE_MIN_REPLAY_CHARS,
): Boolean = composerResidueVerdict(
    incoming = incoming,
    fieldText = fieldText,
    vmText = vmText,
    clearedText = clearedText,
    msSinceClear = msSinceClear,
    windowMs = windowMs,
    minReplayChars = minReplayChars,
) != ComposerResidueVerdict.NONE
