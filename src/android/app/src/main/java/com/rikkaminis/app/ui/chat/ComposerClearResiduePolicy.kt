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
 * [W1118-COMPOSER F-02] Characters an IME appends when `finishComposingText()`
 * settles a composing region, and which a byte-for-byte prefix test therefore
 * treats as "different text".
 *
 * `clearComposerInput` stamps the body BEFORE `clearFocus()`, and `clearFocus`
 * is what makes the IME settle its composing region — so the commit that comes
 * back routinely differs from the stamp by one of these: an English auto-space
 * ("hello world "), a Chinese full stop ("今天天气真好。"), a comma. The first
 * version of this guard compared with `==` / startsWith / endsWith, returned
 * NONE for every one of those, and produced ZERO `ComposerResidue` log lines on
 * the very device that still had the bug. That zero was the tell: the replay
 * was arriving, just never recognised.
 *
 * Kept deliberately narrow. Dropping ordinary characters would make the guard
 * eat genuine typing; punctuation and whitespace at the EDGES only is the shape
 * an IME adds on its own.
 */
internal const val IME_TRAILING_NOISE_CHARS = " \t\n\r。，、；：！？.,;:!?…—·"

/** [W1118-COMPOSER F-02] Strip the IME's own trailing punctuation / whitespace. */
internal fun stripImeTrailingNoise(text: String): String =
    text.trimEnd { it in IME_TRAILING_NOISE_CHARS }

/**
 * [W1118-COMPOSER F-01] Should a composer clear (re)stamp the body it removed?
 *
 * `clearComposerInput` is the ONE funnel every clear entrance uses — including
 * the one the replay guard calls when it decides the box is replay-filled. On
 * that path the box holds a FRAGMENT of the message ("hel"), and a blind stamp
 * records the fragment as "the body this clear removed", forgetting the full
 * body. One frame later the IME continues with "hello", which is no longer a
 * fragment of "hel", the guard returns NONE, and the rest of the sent message
 * walks back into the box, the ViewModel and the persisted draft slot. That is
 * the regression the shipped c2a69fe9 carried.
 *
 * So a clear that is really UNWINDING A REPLAY must keep the previous stamp and
 * its timestamp. Two conditions make that state recognizable:
 *  * the previous stamp is still inside its own window, and
 *  * what sits in the box is a contiguous piece of that stamp.
 *
 * Outside the window the old stamp is dead anyway, so a real clear must (and
 * does) overwrite it — without the window bound, a user who sends "hello world"
 * and then "hello" ten seconds later would leave the DEAD "hello world" stamp in
 * place, and "hello"'s own replay would never be caught.
 *
 * Pure and Android-free so the decision is pinned on the JVM: a shape test can
 * only prove the helper is CALLED, never that its answer is used.
 */
internal fun shouldRestampClearedBody(
    clearedBody: String,
    previousStamp: String?,
    msSinceStamp: Long,
    windowMs: Long = COMPOSER_CLEAR_RESIDUE_WINDOW_MS,
): Boolean {
    if (clearedBody.isEmpty()) return false
    val unwindingReplay = previousStamp != null &&
        msSinceStamp in 0L until windowMs &&
        isFragmentOfClearedBody(clearedBody, previousStamp)
    return !unwindingReplay
}

/**
 * [fix/composer-post-send-residue-2] Is [incoming] a contiguous piece of
 * [clearedText]?
 *
 * Shared by the verdict and by the fingerprint's "do not self-destruct" check so
 * the two can never disagree about what belongs to the body a clear removed.
 *
 * [W1118-COMPOSER F-02] Two relaxations, both driven by a real device report:
 *  * trailing IME punctuation / whitespace is ignored on both sides, because
 *    `finishComposingText` adds it AFTER the stamp is taken;
 *  * a MIDDLE piece of the body counts too (`"lo wor"`), not just a prefix or a
 *    suffix — an IME that resumes from its own buffer does not have to start at
 *    the beginning, and the old prefix/suffix-only rule let those through.
 *
 * The middle-piece rule is bounded by [minFragmentChars] so a two-character
 * coincidence inside a long message is not treated as a replay.
 */
internal fun isFragmentOfClearedBody(
    incoming: String,
    clearedText: String,
    minFragmentChars: Int = COMPOSER_CLEAR_RESIDUE_MIN_REPLAY_CHARS,
): Boolean {
    if (incoming.isEmpty() || clearedText.isEmpty()) return false
    val incomingNorm = stripImeTrailingNoise(incoming)
    val clearedNorm = stripImeTrailingNoise(clearedText)
    if (incomingNorm.isEmpty() || clearedNorm.isEmpty()) return false
    if (incomingNorm == clearedNorm) return true
    if (clearedNorm.startsWith(incomingNorm)) return true
    if (clearedNorm.endsWith(incomingNorm)) return true
    // A middle fragment: the IME resumed from partway through its own buffer.
    return incomingNorm.length >= minFragmentChars && clearedNorm.contains(incomingNorm)
}

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
