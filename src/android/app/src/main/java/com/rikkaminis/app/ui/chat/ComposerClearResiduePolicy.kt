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
 * [W1118-COMPOSER C-A F-02] How long after a send the composer still treats an
 * IME commit as post-send residue (the T217-2 window).
 *
 * `clearFocus()` makes the IME `finishComposingText()`, which third-party IMEs
 * answer by replaying their composing region — always with a non-null
 * composition, which is what makes this window usable. The window is armed by
 * the SEND path only, and that is the whole of the defect this constant's
 * helper exists for: a refused send hands the text back through
 * `commitComposerBody`, and if the window is still armed the user's first
 * Pinyin / Japanese / Korean word (composition != null) is thrown away.
 */
internal const val COMPOSER_POST_SEND_IME_WINDOW_MS = 500L

/**
 * [W1118-COMPOSER C-A F-02] Should this `onValueChange` edit be dropped as a
 * post-send IME residue commit?
 *
 * Pure so the "window is disarmed after a composer commit" half is pinnable on
 * the JVM: the composer resets the send time to `0L`, which makes the elapsed
 * delta the device uptime — far past the window — and this returns false.
 * `msSinceSend < window` is kept exactly as the inline condition was, including
 * its behaviour on a backwards clock, because `elapsedRealtime` is monotonic and
 * a rewrite of that edge would be a silent behaviour change.
 */
internal fun isPostSendImeResidue(
    hasComposingRegion: Boolean,
    msSinceSend: Long,
    incomingEmpty: Boolean,
): Boolean {
    if (!hasComposingRegion) return false
    if (incomingEmpty) return false
    return msSinceSend < COMPOSER_POST_SEND_IME_WINDOW_MS
}

/**
 * [W1118-COMPOSER A1] Fastest rate at which a human can plausibly TYPE into the
 * composer, in characters per second.
 *
 * This is the whole of arm B's safety argument. A char-by-char IME replay and a
 * human retyping the same characters produce byte-identical `onValueChange`
 * edits, so no content test can tell them apart. What CAN be told apart is
 * VOLUME: how much text is in the box relative to how long the box has been
 * empty. Thumb typing on a phone runs at roughly 3-6 characters per second and
 * even a hardware-keyboard-fast user stays under 10; 12 is deliberately
 * generous so the guard errs on the side of "the user could have typed this".
 *
 * Consequence, and the intended one: a replay that rebuilds the body faster than
 * 12 chars/s converges and is wiped, while a user who genuinely retypes the
 * message he just sent (always slower than that) keeps every keystroke.
 */
internal const val COMPOSER_REPLAY_MAX_TYPEABLE_CHARS_PER_SEC = 12

/**
 * [W1118-COMPOSER A1] Length at which a SINGLE edit stops being attributable to a
 * human input method at all.
 *
 * Arm A sees one `onValueChange` landing in an EMPTY composer, so the question is
 * "could one edit produce this?". Suggestion chips, glide swipes and short
 * dictation all produce a WORD — 4 to ~10 characters. A single edit carrying 12
 * or more characters is a phrase, and no IME commits a phrase in one shot; only a
 * replay from the IME's own buffer does. This keeps the primary symptom (the whole
 * sent message reappearing) caught for bodies of any length without relying on
 * how fast the device delivered the commit.
 */
internal const val COMPOSER_SINGLE_EDIT_PHRASE_CHARS = 12

/**
 * [W1118-COMPOSER C-A F-01] Share of the cleared body a SINGLE edit must carry
 * before arm A's rate test is allowed to call it a replay, in percent.
 *
 * The rate test alone is meaningless for one edit, because a suggestion chip, a
 * glide swipe and a short dictation all deliver a whole WORD in one instant —
 * `onValueChange` cannot tell a 5-character chip tap at 350 ms (14 chars/s) from
 * an IME replaying 5 characters. What it CAN measure is how much of the body the
 * edit represents: a replay restores the message (all of it, or the tail it
 * buffered), while a chip gives the user one word out of it. Half is the line —
 * at 45 % ("hello" out of "hello world") the edit is still just a word, and a
 * quarter or an eighth would have fixed nothing.
 *
 * The trade this buys and its cost are stated at [composerResidueVerdict]: a
 * SHORT fragment of a LONG body (4 characters out of 42) is no longer dropped by
 * arm A. That fragment is not lost work — arm B wipes the box the moment the
 * replay converges on the whole body — so the residue costs a visible few
 * characters inside a 1.5 s window instead of a swallowed chip tap. Phrases
 * (12+ characters) do not need this test at all: no input method commits a
 * phrase in one edit.
 */
internal const val COMPOSER_SINGLE_EDIT_MIN_COVERAGE_PERCENT = 50

/**
 * [W1118-COMPOSER C-A F-01] Does [incoming] carry at least
 * [COMPOSER_SINGLE_EDIT_MIN_COVERAGE_PERCENT] of [clearedText]?
 *
 * Lengths are compared AFTER [stripImeTrailingNoise], so the IME's own trailing
 * space or full stop does not make the body look longer than what the edit can
 * possibly restore. Integer arithmetic only, and `>=` on the boundary: exactly
 * half counts as covering, i.e. the guard errs towards "this could be a replay".
 */
internal fun coversEnoughOfClearedBody(incoming: String, clearedText: String): Boolean {
    if (incoming.isEmpty() || clearedText.isEmpty()) return false
    val incomingNorm = stripImeTrailingNoise(incoming)
    val clearedNorm = stripImeTrailingNoise(clearedText)
    if (incomingNorm.isEmpty() || clearedNorm.isEmpty()) return false
    return incomingNorm.length * 100 >= clearedNorm.length * COMPOSER_SINGLE_EDIT_MIN_COVERAGE_PERCENT
}

/**
 * [W1118-COMPOSER A1] Could a human have produced [chars] characters in
 * [msSinceClear] milliseconds? True means "no — this is faster than any human",
 * which is the only discriminator content alone cannot provide.
 *
 * Integer arithmetic on purpose (no float rounding at the boundary) and strict
 * `>`: at exactly the calibrated rate the edit is treated as typeable, i.e. the
 * guard stays out of the way.
 */
internal fun isSuperhumanTypingRate(chars: Int, msSinceClear: Long): Boolean {
    if (chars <= 0) return false
    val elapsed = msSinceClear.coerceAtLeast(0L)
    return chars.toLong() * 1000L > elapsed * COMPOSER_REPLAY_MAX_TYPEABLE_CHARS_PER_SEC
}

/**
 * [W1118-COMPOSER A1] Has the box rebuilt the WHOLE cleared body at a speed no
 * human could have typed it?
 *
 * This is arm B's convergence criterion, and it replaces "the box holds a
 * fragment, so wipe it". That older rule ate real input: sending "hello world"
 * and then typing "he" put `fieldText = "h"` (arm A's floor lets a 1-character
 * edit through) and `incoming = "he"`, both fragments of the cleared body — so
 * the guard wiped the user's first two keystrokes, and, because the stamp
 * survived, kept wiping every retry for the rest of the window.
 *
 * The code's own stated priority is that a swallowed keystroke is worse than a
 * short stale fragment (see [composerResidueVerdict]), so arm B now waits for
 * the state that can no longer be explained as typing: the ENTIRE body is back,
 * and it got there faster than [COMPOSER_REPLAY_MAX_TYPEABLE_CHARS_PER_SEC].
 *
 * Why "the whole body" and not "most of it": a ratio test is what makes a short
 * message dangerous. For a five-character body, "four fifths of it" is four
 * characters, which a user can type in half a second — so a ratio test would eat
 * exactly the case this batch is fixing. Full reconstruction has no such
 * threshold: the comparison is purely "how fast did that many characters
 * appear", and a human retyping N characters needs at least N/12 seconds, which
 * is why a long body is always implausible inside the 1.5 s window while a short
 * one is only implausible if it lands immediately.
 *
 * [W1118-COMPOSER C-A F-01] Arm A's coverage test
 * ([coversEnoughOfClearedBody]) is a different question and does not contradict
 * this one: it never substitutes "most of the body" for "the whole body" on a
 * box that grew over several edits — it only decides which SINGLE edits are
 * large enough to be the body at all, so that one chip tap is not called a
 * replay. Convergence here still requires every character.
 *
 * Consequence, and the intended one: a character-by-character IME replay leaves
 * a growing prefix in the box (harmless, bounded by the window) and is wiped the
 * moment the body is complete; a user who retypes the message he just sent, at
 * any human speed, keeps every keystroke.
 *
 * Equality after [stripImeTrailingNoise] on both sides, so an IME's auto-space
 * or full stop at the end still counts as "the whole body".
 */
internal fun hasReplayConverged(
    incoming: String,
    clearedText: String,
    msSinceClear: Long,
): Boolean {
    if (!isWholeClearedBody(incoming, clearedText)) return false
    return isSuperhumanTypingRate(stripImeTrailingNoise(incoming).length, msSinceClear)
}

/**
 * [fix/composer-short-body-replay-1122] Is [incoming] the ENTIRE body a clear
 * removed (ignoring the IME's trailing punctuation / space)?
 *
 * Separate from [hasReplayConverged] because the two are asked at different
 * points and answer different questions: convergence additionally requires the
 * body to have arrived faster than a human could type it, while this is the
 * content half alone. Arm A needs the content half by itself — see the floor
 * lift in [composerResidueVerdict].
 *
 * Both use this one definition on purpose: a second hand-rolled comparison is
 * how the two would drift apart on the trailing-noise rule.
 */
internal fun isWholeClearedBody(incoming: String, clearedText: String): Boolean {
    val incomingNorm = stripImeTrailingNoise(incoming)
    val clearedNorm = stripImeTrailingNoise(clearedText)
    if (incomingNorm.isEmpty() || clearedNorm.isEmpty()) return false
    return incomingNorm == clearedNorm
}

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
 *  * [DROP_AND_CLEAR] therefore also wipes what accumulated. Arm B reaches it only
 *    when the box holds a contiguous piece of the body that a clear just removed
 *    (never when the user typed something new) AND the whole body is back faster
 *    than a human could have typed it (see [hasReplayConverged]); arm A reaches it
 *    when the single edit is a residue AND the ViewModel copy still holds the body
 *    the clear removed. The speed test is what keeps a user who starts typing
 *    again after a send from being wiped: content alone cannot tell his keystrokes
 *    from a replay.
 */
internal enum class ComposerResidueVerdict {
    /** Not a residue — handle the edit normally. */
    NONE,

    /** Residue, and the box was empty: ignore this edit, nothing to undo. */
    DROP,

    /**
     * Residue, and something outside this edit holds it too: ignore the edit and
     * wipe it. Either the box already accumulated earlier fragments, or (W1119-2D
     * F-04) the edit landed in an empty field while the ViewModel copy still holds
     * the body the clear removed — that stale copy is what the mirror and the
     * persisted draft slot read, so it is the copy that would refill the box.
     */
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
 *
 * [W1118-COMPOSER A4] [guardClear] separates the two kinds of clear that reach
 * this funnel, because the state test above cannot tell them apart. A clear the
 * REPLAY GUARD performs is unwinding a replay (keep the stamp); a clear the USER
 * causes — send, slash command, clear-chat — is always a new body, even when the
 * new body happens to be a piece of the previous one. Sending "hello world" and
 * then, 1.2 s later, sending "hello" is not unwinding anything: "hello" is the
 * second message, and leaving the first message's stamp and timestamp in place
 * both mislabels it and shortens its window (measured from the FIRST send). The
 * guard path is the only caller that passes `true`.
 */
internal fun shouldRestampClearedBody(
    clearedBody: String,
    previousStamp: String?,
    msSinceStamp: Long,
    windowMs: Long = COMPOSER_CLEAR_RESIDUE_WINDOW_MS,
    guardClear: Boolean = false,
): Boolean {
    if (clearedBody.isEmpty()) return false
    if (!guardClear) return true
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
 *
 * [W1118-COMPOSER A3] The middle-piece rule is now OPT-IN, because it is only
 * safe where the box already tells us a replay is in progress. With the box
 * EMPTY, a four-character `contains` hit is far more likely to be the user's own
 * word: send "I'll be there in a moment" and then glide-type "moment" (or tap a
 * suggestion chip) into the emptied composer, and `contains` says "replay" and
 * arm A silently drops the word. Prefix and suffix stay unconditional — an edit
 * that starts or ends the body exactly, with nothing else in the box, is the
 * replay shape (and the suffix case is what an IME buffering only the tail
 * produces).
 */
internal fun isFragmentOfClearedBody(
    incoming: String,
    clearedText: String,
    minFragmentChars: Int = COMPOSER_CLEAR_RESIDUE_MIN_REPLAY_CHARS,
    allowMiddleFragment: Boolean = true,
): Boolean {
    if (incoming.isEmpty() || clearedText.isEmpty()) return false
    val incomingNorm = stripImeTrailingNoise(incoming)
    val clearedNorm = stripImeTrailingNoise(clearedText)
    if (incomingNorm.isEmpty() || clearedNorm.isEmpty()) return false
    if (incomingNorm == clearedNorm) return true
    if (clearedNorm.startsWith(incomingNorm)) return true
    if (clearedNorm.endsWith(incomingNorm)) return true
    if (!allowMiddleFragment) return false
    // A middle fragment: the IME resumed from partway through its own buffer.
    return incomingNorm.length >= minFragmentChars && clearedNorm.contains(incomingNorm)
}

/**
 * [W1118-COMPOSER A2] Should the cleared-body fingerprint SURVIVE this edit?
 *
 * The fingerprint exists to recognise an IME replaying the body a clear removed.
 * It must die the moment the box holds something that is not part of that body —
 * otherwise a later edit that happens to match would be swallowed for the rest
 * of the window. It must NOT die on the 1-3 character fragments arm A lets
 * through on purpose, or the first fragment of a replay disarms the guard for
 * everything after it (that was the second-round bug).
 *
 * Pure, so both the composer and the tests read the same decision: the shape arm
 * that used to guard this (a substring scan 400 characters before the
 * `lastClearedText = null` write) was satisfied by the OBSERVABILITY log block a
 * few lines above, so deleting the real condition left the suite green.
 */
internal fun shouldKeepClearedStamp(incoming: String, clearedText: String?): Boolean {
    if (clearedText.isNullOrEmpty()) return true
    if (incoming.isEmpty()) return true
    return isFragmentOfClearedBody(incoming, clearedText)
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
 *  **Arm A — the box is still EMPTY**: nothing has landed in the FIELD, and the
 *  ViewModel copy is blank or still only the body the clear removed (a lagging
 *  copy is not evidence of a landed replay — the derivation is at `boxEmpty` in
 *  the body below). A fragment of at least [minReplayChars] is the replay: a fresh
 *  keystroke into an empty box cannot be four characters at once.
 *
 *  **Arm B — the box already holds earlier fragments** ([fieldText] is itself a
 *  contiguous piece of the cleared body). This is the arm the first fix was
 *  missing: its guard returned NONE the moment either copy was non-empty, so a
 *  char-by-char IME replay disarmed it with its FIRST character and the rest
 *  streamed in unopposed. On-device proof the shape is real:
 *  `ComposerMirror` logged `field = vm + 1ch` on 14 dictation sessions on
 *  2026-11-18, i.e. the field is permanently one commit ahead of the ViewModel.
 *  Arm B returns [ComposerResidueVerdict.DROP_AND_CLEAR] — but only once the
 *  replay has CONVERGED (the whole body is back faster than a human could have
 *  typed it, see [hasReplayConverged]). Everything in between is allowed: the
 *  accumulated fragment is a bounded, self-correcting cosmetic problem, while
 *  wiping a keystroke is data loss.
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

    // [W1119-2D F-04] `vmText` is the ViewModel copy as the composition read it.
    // `clearComposerInput` empties the FIELD synchronously (`inputFieldValue` is a
    // `mutableStateOf`, so the write is visible to this same callback), but the
    // `viewModel.setInputText("")` it performs only updates a `StateFlow`: the
    // `collectAsState()` copy read here is refreshed on a LATER dispatch, so for the
    // rest of the frame it still holds the body the clear removed. Two
    // `onValueChange` callbacks can land inside that window — the 150 ms
    // IME-burst debounce exists precisely because dictation commits arrive several
    // events per frame — so a stale copy is a real input, not a corner case.
    //
    // A copy that is blank or still a piece of the body it is being compared
    // against is NOT evidence that anything has landed: it is the lag this
    // function already tolerates in arm B (`vmIsReplay`), so it must not veto arm
    // A. Only a copy holding something unrelated to the cleared body says "the
    // composer is not empty" — and then arm B is the right arm.
    val vmContradictsEmptyBox =
        vmText.isNotEmpty() && !isFragmentOfClearedBody(vmText, clearedText)
    val boxEmpty = fieldText.isEmpty() && !vmContradictsEmptyBox
    // [W1118-COMPOSER A3] The middle-fragment rule is only available to arm B,
    // where the box already proves a replay is in progress. With the box EMPTY a
    // four-character `contains` hit is far more likely to be the user's own word
    // — send "I'll be there in a moment", then glide-type "moment" (or tap a
    // suggestion chip) into the emptied composer, and `contains` would call it a
    // replay and arm A would DROP the word.
    if (!isFragmentOfClearedBody(
            incoming = incoming,
            clearedText = clearedText,
            allowMiddleFragment = !boxEmpty,
        )
    ) {
        return ComposerResidueVerdict.NONE
    }

    // [W1118-COMPOSER A1] Arm A keeps the fragment rule: a single edit of four or
    // more characters landing in an EMPTY box cannot be typing (one keystroke is
    // one edit), so a long prefix / suffix there is the replay shape the first fix
    // was written for. The documented trade stands — inside the window, pasting
    // the text you just sent is treated as residue.
    //
    // ...but "four characters at once" is not by itself impossible input: a glide
    // swipe, a suggestion chip and a short dictation all land as one edit of four
    // or more characters, and dropping one of those silently is the same class of
    // data loss arm B used to cause. Three tests settle it — the first is enough
    // on its own:
    //  * the edit carries a PHRASE (12+ characters) — no input method commits a
    //    phrase in one edit, only a replay from the IME's own buffer does;
    //  * [W1118-COMPOSER C-A F-01] otherwise the edit must COVER at least half the
    //    cleared body, because one edit is one delivery: a chip tap and a replay
    //    of the same length arrive at the same instant, so speed cannot separate
    //    them but coverage can (a chip gives a word, a replay gives the message);
    //  * ...and only then does the rate test get a vote, which is what still
    //    catches a short body replayed in one frame.
    if (boxEmpty) {
        // [fix/composer-short-body-replay-1122] The fragment floor must not
        // filter out a WHOLE-body replay. For a body shorter than the floor
        // ("做", "ok", "好的") the fragment rule can never fire: `len >= 4` is
        // false by construction, so the exact text the user just sent walks
        // straight back into the emptied composer. Device log from the report:
        //   `allowed a short fragment of the cleared body (len=1 of 1,
        //    composition=false, dt=1ms)` — the guard SAW the replay and let it
        // through. Equality with the whole body is the strongest signal
        // available here, so it lifts the floor and leaves the decision to the
        // rate test: a human cannot type a message within a few dozen
        // milliseconds of a clear that hid the keyboard and dropped focus.
        val wholeBody = isWholeClearedBody(incoming, clearedText)
        val longEnough = incoming.length >= minReplayChars || wholeBody
        val phrase = incoming.length >= COMPOSER_SINGLE_EDIT_PHRASE_CHARS
        val covers = coversEnoughOfClearedBody(incoming, clearedText)
        val drop = longEnough &&
            (phrase || (covers && isSuperhumanTypingRate(incoming.length, msSinceClear)))
        // [W1119-2D F-04] Dropping the edit is not enough while the stale ViewModel
        // copy still holds the body: that copy is what the 150 ms mirror and the
        // persisted draft slot read, so it is the copy that refills the box after
        // the drop. Nothing in the FIELD needs wiping, so the verdict escalates to
        // DROP_AND_CLEAR, which runs the same clear funnel as a send.
        return if (drop && vmText.isNotEmpty()) {
            ComposerResidueVerdict.DROP_AND_CLEAR
        } else if (drop) {
            ComposerResidueVerdict.DROP
        } else {
            ComposerResidueVerdict.NONE
        }
    }

    // Arm B. Only treat the box as replay-filled while EVERYTHING in it is a
    // piece of the cleared body: one character of unrelated text means the user
    // has taken the composer back (or the input method inserted something of its
    // own) and wiping the box would destroy his work.
    //
    // [W1118-COMPOSER A5] Conjunction, matching that sentence. It used to be a
    // disjunction, which meant a ViewModel copy lagging one burst behind
    // (`fieldText = "hz"`, `vmText = "hel"`) authorised the wipe of the user's
    // own "hz". An EMPTY copy is vacuously fine — it holds nothing that could
    // contradict the replay — so only a non-empty copy has to prove itself.
    val fieldIsReplay = fieldText.isEmpty() || isFragmentOfClearedBody(fieldText, clearedText)
    val vmIsReplay = vmText.isEmpty() || isFragmentOfClearedBody(vmText, clearedText)
    if (!(fieldIsReplay && vmIsReplay)) return ComposerResidueVerdict.NONE

    // [W1118-COMPOSER A1] ...and only once the replay has CONVERGED. Here the box
    // grew over several edits, which is exactly what a user retyping the message
    // he just sent also does — content cannot tell them apart, speed can (see
    // [hasReplayConverged]). "The box holds a fragment, so clear it" ate real
    // input: after sending "hello world", typing "he" put a fragment in the box
    // (arm A let "h" through by design), which was enough to wipe the user's first
    // two keystrokes — repeatedly, for the rest of the window, because the stamp
    // survived. Intermediate states are allowed through now, so the cost of being
    // wrong is a short stale fragment instead of a lost keystroke.
    return if (hasReplayConverged(incoming, clearedText, msSinceClear)) {
        ComposerResidueVerdict.DROP_AND_CLEAR
    } else {
        ComposerResidueVerdict.NONE
    }
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
