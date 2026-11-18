package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/composer-post-send-residue] Behavioural tests for the composer's
 * post-clear replay guard.
 *
 * The reported field symptom: a message is sent (visible in the transcript) but
 * the same text is still sitting in the composer. The send path clears both
 * copies (ViewModel string + field `TextFieldValue`); the text comes back when
 * the IME commits its own copy of what the user was typing AFTER that clear.
 *
 * [W1118-COMPOSER A1] The second round of this guard over-corrected: it wiped
 * the box as soon as it held ANY fragment of the cleared body, which is exactly
 * what the user's first keystrokes look like when he sends "hello world" and
 * then starts typing "he". Both of them are contiguous pieces of the cleared
 * body, so content alone can never separate "IME replay" from "user typing".
 * The guard now separates them by RATE — a replay materialises the body faster
 * than any human types — and the arms below pin that trade explicitly, in both
 * directions: the replay is still caught, the user's keystrokes are not.
 *
 * Every expectation is a literal string / literal millisecond count. Nothing is
 * derived from the function under test, so a change of the window, the replay
 * floor or the typeable rate cannot silently make a case pass.
 */
class ComposerClearResiduePolicyTest {

    private val body = "hello world, this is the message I just sent"

    private fun verdict(
        incoming: String,
        clearedText: String? = body,
        fieldText: String = "",
        vmText: String = "",
        msSinceClear: Long = 0L,
    ): ComposerResidueVerdict = composerResidueVerdict(
        incoming = incoming,
        fieldText = fieldText,
        vmText = vmText,
        clearedText = clearedText,
        msSinceClear = msSinceClear,
    )

    private fun drop(
        incoming: String,
        clearedText: String? = body,
        fieldText: String = "",
        vmText: String = "",
        msSinceClear: Long = 0L,
    ): Boolean = isComposerClearResidue(
        incoming = incoming,
        fieldText = fieldText,
        vmText = vmText,
        clearedText = clearedText,
        msSinceClear = msSinceClear,
    )

    // ── the rate primitive ───────────────────────────────────────────────────

    @Test
    fun `a body materialising in one frame is faster than a human types`() {
        // 42 characters in 0 ms — no keyboard produces that.
        assertTrue(isSuperhumanTypingRate(chars = body.length, msSinceClear = 0L))
        assertTrue(isSuperhumanTypingRate(chars = body.length, msSinceClear = 500L))
    }

    @Test
    fun `a word typed at a human pace is not a superhuman rate`() {
        // 6 characters in 800 ms ≈ 7.5 chars/s, inside the 12 chars/s ceiling.
        assertFalse(isSuperhumanTypingRate(chars = 6, msSinceClear = 800L))
        // ...and the same six characters a full second later are certainly fine.
        assertFalse(isSuperhumanTypingRate(chars = 6, msSinceClear = 1_000L))
    }

    @Test
    fun `the rate ceiling is twelve characters per second`() {
        // Contract pin, not a derivation: 12 chars in exactly 1000 ms is the
        // boundary, and the boundary is INCLUSIVE of "typeable" (strict `>`), so
        // the guard errs towards letting the edit through.
        assertEquals(12, COMPOSER_REPLAY_MAX_TYPEABLE_CHARS_PER_SEC)
        assertFalse(isSuperhumanTypingRate(chars = 12, msSinceClear = 1_000L))
        assertTrue(isSuperhumanTypingRate(chars = 13, msSinceClear = 1_000L))
    }

    @Test
    fun `an empty or backwards clock never looks superhuman`() {
        assertFalse(isSuperhumanTypingRate(chars = 0, msSinceClear = 0L))
        assertFalse(isSuperhumanTypingRate(chars = 0, msSinceClear = -5L))
        // A backwards clock is coerced to zero, so a whole body still reads as
        // instantaneous — which is right: the guard's own caller refuses a
        // negative delta before it ever asks.
        assertTrue(isSuperhumanTypingRate(chars = body.length, msSinceClear = -5L))
    }

    // ── [W1118-COMPOSER C-A F-02] the post-send IME window ────────────────────

    /**
     * The T217-2 window drops an IME commit that still carries a composing region.
     * It is armed by the SEND path only, so the question this pins is what happens
     * once a refusal has handed the text back: `commitComposerBody` resets the send
     * time to 0, the delta becomes the device uptime, and the window stands down —
     * otherwise the user's first Pinyin / Japanese / Korean word into the restored
     * body would vanish.
     */
    @Test
    fun `a restored body does not arm the post-send IME window`() {
        assertEquals(500L, COMPOSER_POST_SEND_IME_WINDOW_MS)
        // lastSendTimeMs = 0 after a composer commit: the delta is uptime-scale.
        assertFalse(
            isPostSendImeResidue(
                hasComposingRegion = true,
                msSinceSend = 42_000L,
                incomingEmpty = false,
            ),
        )
        // A genuinely armed window still drops the commit, boundary included.
        assertTrue(isPostSendImeResidue(hasComposingRegion = true, msSinceSend = 0L, incomingEmpty = false))
        assertTrue(isPostSendImeResidue(hasComposingRegion = true, msSinceSend = 499L, incomingEmpty = false))
        assertFalse(isPostSendImeResidue(hasComposingRegion = true, msSinceSend = 500L, incomingEmpty = false))
        // ...and the two conditions that were never about timing are unchanged.
        assertFalse(isPostSendImeResidue(hasComposingRegion = false, msSinceSend = 100L, incomingEmpty = false))
        assertFalse(isPostSendImeResidue(hasComposingRegion = true, msSinceSend = 100L, incomingEmpty = true))
    }

    // ── arm A: the box is still empty ────────────────────────────────────────


    @Test
    fun `the whole cleared body replayed into the emptied composer is a residue`() {
        assertEquals(ComposerResidueVerdict.DROP, verdict(incoming = body))
    }

    @Test
    fun `a fragmented replay that is a long prefix is a residue`() {
        // IME buffers replay the body in pieces; the first piece arrives alone,
        // and 18 characters in one edit is not a keystroke.
        assertEquals(ComposerResidueVerdict.DROP, verdict(incoming = "hello world, this"))
    }

    @Test
    fun `a fragmented replay that is a long suffix is a residue`() {
        assertEquals(ComposerResidueVerdict.DROP, verdict(incoming = "the message I just sent"))
    }

    /**
     * [W1118-COMPOSER C-A F-01] The rate test cannot stand alone for a SINGLE
     * edit: `onValueChange` cannot tell five characters delivered by a suggestion
     * chip at 350 ms from five characters replayed by the IME — both are "14
     * chars/s". Coverage can: a chip hands over one word, a replay hands over the
     * message. So a short fragment of a long body is no longer dropped by arm A;
     * arm B wipes the box once the replay converges, which is what keeps the
     * original symptom fixed.
     */
    @Test
    fun `a four character prefix of a long body is no longer dropped`() {
        // 4 of 42 characters is a word the user might tap, not the body.
        assertEquals(ComposerResidueVerdict.NONE, verdict(incoming = "hell"))
    }

    /**
     * [W1118-COMPOSER C-A F-01] The reported false positive, verbatim: send
     * "hello world", tap the suggestion chip / glide the word "hello" 350 ms
     * later. 5 characters in 350 ms is 14 chars/s — "superhuman" by the rate rule
     * — so the shipped guard swallowed the word.
     */
    @Test
    fun `a chip word covering under half the body is not a residue`() {
        assertEquals(
            ComposerResidueVerdict.NONE,
            verdict(incoming = "hello", clearedText = "hello world", msSinceClear = 350L),
        )
    }

    /** [W1118-COMPOSER C-A F-01] ...while the replay of that same body still is. */
    @Test
    fun `the whole body replayed at the same delay is still a residue`() {
        assertEquals(
            ComposerResidueVerdict.DROP,
            verdict(incoming = "hello world", clearedText = "hello world", msSinceClear = 350L),
        )
    }

    /**
     * [W1118-COMPOSER C-A F-01] The documented cost of the coverage rule, pinned
     * so nobody "fixes" it back into a swallowed chip tap: a 5-character body
     * replayed whole inside the first few hundred milliseconds is STILL dropped.
     * Here the edit covers 100 % of the body, so coverage cannot save it and the
     * rate rule decides — which is the intended answer for a short body: the user
     * who genuinely retypes it needs ~700 ms, and past that the rate test stands
     * down on its own.
     */
    @Test
    fun `a short body replayed whole is still dropped`() {
        assertEquals(
            ComposerResidueVerdict.DROP,
            verdict(incoming = "hello", clearedText = "hello", msSinceClear = 350L),
        )
    }

    /**
     * [W1118-COMPOSER C-A F-01] Coverage on the boundary: exactly half the body
     * counts, one character less does not. The denominator ignores the IME's own
     * trailing punctuation, otherwise "hello worl。" would measure 12 characters
     * and demand 6, instead of measuring 10 and accepting 5.
     */
    @Test
    fun `the coverage floor is half the body ignoring IME trailing noise`() {
        assertEquals(50, COMPOSER_SINGLE_EDIT_MIN_COVERAGE_PERCENT)
        // The reported case: 5 of 11 characters is a word out of the message.
        assertFalse(coversEnoughOfClearedBody("hello", "hello world"))         // 5 / 11
        assertTrue(coversEnoughOfClearedBody("hello wor", "hello world"))      // 9 / 11
        assertFalse(coversEnoughOfClearedBody("hell", "hello world"))          // 4 / 11
        assertTrue(coversEnoughOfClearedBody("hello", "hello worl。"))         // 5 / 10, not 5 / 11
        assertFalse(coversEnoughOfClearedBody("moment", "moment please"))      // 6 / 13
        assertFalse(coversEnoughOfClearedBody("hello", body))                  // 5 / 42
        assertFalse(coversEnoughOfClearedBody("", body))
        assertFalse(coversEnoughOfClearedBody("hello", ""))
    }

    @Test
    fun `a three character prefix is not a residue`() {
        // Below the floor the match is indistinguishable from the user typing
        // those characters into the empty box. Dropping nothing here means the
        // worst case is a short fragment left over — never a swallowed key.
        assertEquals(ComposerResidueVerdict.NONE, verdict(incoming = "hel"))
    }

    @Test
    fun `one character is never a residue`() {
        assertEquals(ComposerResidueVerdict.NONE, verdict(incoming = "h"))
    }

    /**
     * [W1118-COMPOSER A3] A four-character `contains` hit is NOT enough while the
     * box is empty: send "I'll be there in a moment", then glide-type or tap the
     * suggestion chip for a word out of the MIDDLE of that body, and the old
     * `contains` rule silently dropped the word. Arm A is prefix / suffix only.
     */
    @Test
    fun `a suggestion chip word from the middle of the body is not a residue`() {
        // A genuine MIDDLE word — neither prefix nor suffix — which is the only
        // shape arm A must refuse now.
        assertEquals(
            ComposerResidueVerdict.NONE,
            verdict(incoming = "there", clearedText = "I'll be there in a moment"),
        )
        assertEquals(
            ComposerResidueVerdict.NONE,
            verdict(incoming = "there in", clearedText = "I'll be there in a moment"),
        )
    }

    /**
     * [W1118-COMPOSER A3] A prefix / suffix word is still a legitimate input-method
     * commit — a suggestion chip can easily land the FIRST word of the message the
     * user just sent, because that is where his sentence started. Only a phrase
     * (or an implausible rate) makes it a replay.
     */
    @Test
    fun `a single suggestion chip word that prefixes the body is not a residue`() {
        assertEquals(
            ComposerResidueVerdict.NONE,
            verdict(incoming = "moment", clearedText = "moment please", msSinceClear = 700L),
        )
        assertEquals(
            ComposerResidueVerdict.NONE,
            verdict(incoming = "hello", clearedText = "hello there", msSinceClear = 800L),
        )
    }

    /**
     * [W1118-COMPOSER A1] The phrase rule is what keeps the ORIGINAL symptom fixed
     * regardless of delivery timing: no input method commits twelve characters in
     * one edit, so a single edit of that size into an empty composer is a replay
     * from the IME's own buffer — even when the commit took long enough that the
     * RATE alone would have called it human typing.
     */
    @Test
    fun `a single edit carrying a phrase is a residue even at a human-looking delay`() {
        // 12 characters 1.2 s after the clear is 10 chars/s — inside the typeable
        // ceiling, so only the phrase rule can catch it. That is the point.
        assertEquals(
            ComposerResidueVerdict.DROP,
            verdict(incoming = "hello world,", msSinceClear = 1_200L),
        )
        assertEquals(12, COMPOSER_SINGLE_EDIT_PHRASE_CHARS)
    }

    /**
     * [W1118-COMPOSER A3] The middle-fragment rule still applies where it is safe
     * — arm B, i.e. when the box already holds a fragment. An IME resuming from
     * partway through its own buffer never converges, so nothing is wiped; this
     * pins that the fragment is still RECOGNISED there (it keeps the stamp alive
     * instead of being treated as the user taking the composer back).
     */
    @Test
    fun `a middle fragment keeps the stamp while the box already holds a fragment`() {
        assertTrue(
            isFragmentOfClearedBody(
                incoming = "lo world, this is",
                clearedText = "hello world, this is the message I just sent",
                allowMiddleFragment = true,
            ),
        )
        assertFalse(
            "the middle rule must not be available to the empty-box arm",
            isFragmentOfClearedBody(
                incoming = "lo world, this is",
                clearedText = "hello world, this is the message I just sent",
                allowMiddleFragment = false,
            ),
        )
    }

    // ── arm B: the replay already landed, the box is no longer empty ─────────

    /**
     * [W1118-COMPOSER A1] THE regression this batch exists for. Sending "hello
     * world" and then typing "he" is the single most common user action after a
     * send, and the previous version wiped it: `fieldText = "h"` was a fragment
     * (so arm B engaged) and `incoming = "he"` was a fragment too. Nothing about
     * the CONTENT distinguishes this from a replay — the rate does: two
     * keystrokes take a human a few hundred ms, while a replay covers the same
     * ground in a frame.
     */
    @Test
    fun `typing a prefix of the sent message right after the send is never wiped`() {
        assertEquals(
            ComposerResidueVerdict.NONE,
            verdict(incoming = "he", fieldText = "h", clearedText = "hello world"),
        )
        assertEquals(
            ComposerResidueVerdict.NONE,
            verdict(incoming = "hel", fieldText = "he", clearedText = "hello world"),
        )
    }

    /**
     * The same keystrokes, at a speed no keyboard produces: this is a replay
     * continuing where it left off, so it converges and the box is wiped.
     */
    @Test
    fun `a replay that rebuilds the whole body faster than a human is wiped`() {
        assertEquals(
            ComposerResidueVerdict.DROP_AND_CLEAR,
            verdict(incoming = body, fieldText = "hello world, this is the mess", msSinceClear = 100L),
        )
    }

    /**
     * [W1118-COMPOSER A1] Intermediate replay states are allowed through ON
     * PURPOSE: the box keeps a growing prefix of the sent message, which is the
     * cheap failure, and the wipe happens the moment the body is complete. The
     * previous rule wiped at the first fragment, which is the expensive failure.
     */
    @Test
    fun `a partial replay is allowed to accumulate until it converges`() {
        assertEquals(
            ComposerResidueVerdict.NONE,
            verdict(incoming = "hello", fieldText = "hell", msSinceClear = 60L),
        )
        assertEquals(
            ComposerResidueVerdict.DROP_AND_CLEAR,
            verdict(incoming = body, fieldText = "hello world, this is", msSinceClear = 60L),
        )
    }

    /**
     * A user who deliberately retypes the whole message inside the window at a
     * human pace keeps it. For this 42-character body that is only possible if
     * the text was pasted or typed at ~28 chars/s, so the case that matters is
     * the SHORT body below.
     */
    @Test
    fun `retyping the whole short message at a human pace is not wiped`() {
        assertEquals(
            ComposerResidueVerdict.NONE,
            verdict(incoming = "hello", clearedText = "hello", fieldText = "hell", msSinceClear = 900L),
        )
    }

    @Test
    fun `retyping a short message instantly is still a replay`() {
        // Five characters with the clock at zero cannot be typing — the same
        // content, a different producer, and only the rate can say so.
        assertEquals(
            ComposerResidueVerdict.DROP_AND_CLEAR,
            verdict(incoming = "hello", clearedText = "hello", fieldText = "hell", msSinceClear = 0L),
        )
    }

    @Test
    fun `a replay is caught when only the view model holds the earlier fragments`() {
        // The 150 ms burst flush writes the ViewModel a step ahead of the field; the
        // field is empty here, so the ViewModel copy is the only replay evidence — and
        // it is also the copy that would refill the box, which is why the verdict is
        // the wiping one (see the [W1119-2D F-04] cases above for the arm that must
        // NOT be chosen when the box is genuinely empty and the copy is blank).
        assertEquals(
            ComposerResidueVerdict.DROP_AND_CLEAR,
            verdict(incoming = body, vmText = "hello world, this is", msSinceClear = 100L),
        )
    }

    /**
     * The ViewModel copy is written one dispatch behind the field: `clearComposerInput`
     * empties `inputFieldValue` synchronously, but its `setInputText("")` only reaches
     * the `collectAsState()` copy read by the composer on a later frame. Everything
     * below is that lagging copy's shape, and it must not veto arm A.
     *
     * [W1119-2D F-04] Before the fix the pair `(fieldText = "", vmText = body)`
     * reached arm B, whose only drop is the full-body convergence test — so a phrase
     * replayed in one frame was allowed through and landed in the box.
     */
    @Test
    fun `a stale view model copy does not route a phrase replay into the non-wiping arm`() {
        // A 20-character phrase is 20 of the 44 cleared characters: phrase-length, so
        // arm A's "no input method commits a phrase in one edit" rule decides it.
        assertEquals(
            ComposerResidueVerdict.DROP_AND_CLEAR,
            verdict(
                incoming = "hello world, this is",
                fieldText = "",
                vmText = body,
                msSinceClear = 400L,
            ),
        )
    }

    @Test
    fun `a lagging prefix copy does not route a phrase replay into the non-wiping arm`() {
        // The ViewModel copy lagging a burst behind is a PREFIX of the body, not the
        // body itself — still the clear's own lag, so still arm A.
        assertEquals(
            ComposerResidueVerdict.DROP_AND_CLEAR,
            verdict(
                incoming = "hello world, this is",
                fieldText = "",
                vmText = "hello world, this is",
                msSinceClear = 400L,
            ),
        )
    }

    @Test
    fun `a whole body replayed against a stale view model copy is still wiped`() {
        assertEquals(
            ComposerResidueVerdict.DROP_AND_CLEAR,
            verdict(incoming = body, fieldText = "", vmText = body, msSinceClear = 100L),
        )
    }

    @Test
    fun `a stale view model copy never makes a chip word look like a replay`() {
        // "moment" is 6 characters — below the phrase floor and well under half the
        // body. The stale copy is not evidence that the box is replay-filled, so arm
        // A's coverage test still has to fail it.
        assertEquals(
            ComposerResidueVerdict.NONE,
            verdict(incoming = "moment", fieldText = "", vmText = body, msSinceClear = 400L),
        )
    }

    @Test
    fun `an unrelated view model copy still keeps the box out of arm A`() {
        // The mirror ran at some point and the ViewModel holds text that is NOT the
        // cleared body: that is a real, non-empty box, and arm A must not touch it.
        assertEquals(
            ComposerResidueVerdict.NONE,
            verdict(
                incoming = body,
                fieldText = "",
                vmText = "totally different words",
                msSinceClear = 100L,
            ),
        )
    }

    /**
     * [W1119-2D F-04] The drop half of arm A is unchanged when the ViewModel copy is
     * already blank — the lag described above is the only reason a DROP escalates.
     */
    @Test
    fun `an empty view model copy drops the phrase without escalating to a wipe`() {
        assertEquals(
            ComposerResidueVerdict.DROP,
            verdict(incoming = "hello world, this is", fieldText = "", vmText = "", msSinceClear = 400L),
        )
    }

    /**
     * [W1118-COMPOSER A5] The comment said "only while EVERYTHING in the box is a
     * piece of the cleared body" while the code used `||`. With the field holding
     * the user's own "hz" and the ViewModel still lagging one burst behind on
     * "hel", the disjunction authorised a wipe of "hz". The conjunction refuses —
     * and an EMPTY copy is vacuously consistent, so the burst-lag case that
     * motivated the disjunction still works (previous test).
     */
    @Test
    fun `a lagging view model copy never authorises a wipe of the users own text`() {
        assertEquals(
            ComposerResidueVerdict.NONE,
            verdict(incoming = body, fieldText = "hz", vmText = "hel", msSinceClear = 100L),
        )
    }

    /**
     * [W1118-COMPOSER F-02] The shape that produced ZERO `ComposerResidue` log
     * lines on the device that still had the bug: `clearComposerInput` stamps the
     * body BEFORE `clearFocus()`, and `clearFocus` is what makes the IME settle
     * its composing region — so the commit that comes back is the stamped body
     * plus one character the IME added (auto-space, full stop, comma).
     */
    @Test
    fun `an english auto-space appended by finishComposingText is still a replay`() {
        assertEquals(
            ComposerResidueVerdict.DROP,
            verdict(incoming = "hello world, this is the message I just sent "),
        )
    }

    @Test
    fun `a chinese full stop appended by finishComposingText is still a replay`() {
        assertEquals(ComposerResidueVerdict.DROP, verdict(incoming = "今天天气真好。", clearedText = "今天天气真好"))
    }

    @Test
    fun `a comma appended by finishComposingText is still a replay`() {
        assertEquals(ComposerResidueVerdict.DROP, verdict(incoming = "今天天气真好，", clearedText = "今天天气真好"))
    }

    /**
     * Convergence compares the noise-stripped texts, so a replay that comes back
     * with the IME's trailing punctuation still converges.
     */
    @Test
    fun `a replay with the IME punctuation converges and is wiped`() {
        assertEquals(
            ComposerResidueVerdict.DROP_AND_CLEAR,
            verdict(incoming = "hello world。", clearedText = "hello world", fieldText = "hello", msSinceClear = 50L),
        )
    }

    @Test
    fun `a two character middle coincidence is not a replay`() {
        // Below the fragment floor a `contains` hit is a coincidence, not a
        // replay — and dropping it would eat a genuine keystroke pair.
        assertEquals(
            ComposerResidueVerdict.NONE,
            verdict(incoming = "o w", clearedText = "hello world, this is the message I just sent"),
        )
    }

    @Test
    fun `a short middle coincidence on a replay filled box does not authorise a wipe`() {
        assertEquals(
            ComposerResidueVerdict.NONE,
            verdict(
                incoming = "o w",
                clearedText = "hello world, this is the message I just sent",
                fieldText = "h",
            ),
        )
    }

    @Test
    fun `a fresh sentence in the emptied composer is never wiped`() {
        assertEquals(
            ComposerResidueVerdict.NONE,
            verdict(incoming = "completely different words", fieldText = "com"),
        )
    }

    // ── the shared preconditions ─────────────────────────────────────────────

    @Test
    fun `an edit outside the post-clear window is not a residue`() {
        assertEquals(ComposerResidueVerdict.NONE, verdict(incoming = body, msSinceClear = 1500L))
    }

    @Test
    fun `the last millisecond inside the window is still a residue`() {
        assertEquals(ComposerResidueVerdict.DROP, verdict(incoming = body, msSinceClear = 1499L))
    }

    @Test
    fun `a clock that went backwards never drops an edit`() {
        assertEquals(ComposerResidueVerdict.NONE, verdict(incoming = body, msSinceClear = -1L))
    }

    @Test
    fun `unrelated text is never a residue`() {
        assertEquals(ComposerResidueVerdict.NONE, verdict(incoming = "completely different words"))
    }

    @Test
    fun `a replay with nothing to compare against is not a residue`() {
        assertEquals(ComposerResidueVerdict.NONE, verdict(incoming = "hello world", clearedText = null))
        assertEquals(ComposerResidueVerdict.NONE, verdict(incoming = "hello world", clearedText = ""))
    }

    /**
     * [W1119-2D F-05] The fingerprint drop clears `lastClearedText` and leaves
     * `lastClearedAtMs` alone. That is inert, and this arm is the proof rather than
     * the assertion: with NO stamp, every reader of the timestamp is behind a null
     * check — the verdict returns early on a null body, and `shouldRestampClearedBody`
     * requires `previousStamp != null` before it looks at the delta. So a leftover
     * timestamp can only be read as "0 ms since a clear that has no body", which must
     * never drop anything.
     */
    @Test
    fun `a dropped stamp is never a residue even when its timestamp is still fresh`() {
        assertEquals(
            ComposerResidueVerdict.NONE,
            verdict(incoming = body, clearedText = null, fieldText = "", vmText = "", msSinceClear = 1L),
        )
        assertEquals(
            ComposerResidueVerdict.NONE,
            verdict(incoming = body, clearedText = "", fieldText = body, vmText = "", msSinceClear = 1L),
        )
        // ...and a stale stamp cannot protect itself: no stamp means every clear restamps.
        assertTrue(
            shouldRestampClearedBody(
                clearedBody = "hello",
                previousStamp = null,
                msSinceStamp = 1L,
                guardClear = true,
            ),
        )
    }

    @Test
    fun `an empty incoming edit is not a residue`() {
        assertEquals(ComposerResidueVerdict.NONE, verdict(incoming = ""))
    }

    // ── contracts ────────────────────────────────────────────────────────────

    @Test
    fun `the window is one and a half seconds`() {
        // Contract pin, not a derivation: the constant is what the caller's
        // "post-clear" means on a real device.
        assertEquals(1500L, COMPOSER_CLEAR_RESIDUE_WINDOW_MS)
        assertEquals(4, COMPOSER_CLEAR_RESIDUE_MIN_REPLAY_CHARS)
    }

    /** The boolean facade must agree with the verdict it wraps, on every arm. */
    @Test
    fun `the boolean facade agrees with the verdict`() {
        assertFalse(drop(incoming = "hel"))
        // [W1118-COMPOSER C-A F-01] "hell" is 4 characters of a 42-character
        // body — a word, not the body, so arm A leaves it alone (see
        // `a four character prefix of a long body is no longer dropped`).
        assertFalse(drop(incoming = "hell"))
        // The whole body is still dropped, on either arm.
        assertTrue(drop(incoming = body))
        assertTrue(drop(incoming = body, clearedText = body, msSinceClear = 350L))
        // [W1118-COMPOSER A1] The fragment-in-box case is allowed now; the
        // converged case is not.
        assertFalse(drop(incoming = "he", fieldText = "h", clearedText = "hello world"))
        assertTrue(drop(incoming = body, fieldText = "hello world, this", msSinceClear = 100L))
        assertFalse(drop(incoming = body, fieldText = "hz", vmText = "hel", msSinceClear = 100L))
    }

    /**
     * Fragment detection is shared with the fingerprint's "do not self-destruct"
     * check, so pin the shapes both sides depend on.
     */
    @Test
    fun `fragment detection covers whole prefix and suffix`() {
        assertTrue(isFragmentOfClearedBody(body, body))
        assertTrue(isFragmentOfClearedBody("hello", body))
        assertTrue(isFragmentOfClearedBody("just sent", body))
        assertFalse(isFragmentOfClearedBody("hello world, the message", body))
        assertFalse(isFragmentOfClearedBody("", body))
    }

    // ── [W1118-COMPOSER A2] the stamp survives the user's own text ───────────

    /**
     * A refusal writes the user's body back through `commitComposerBody`. The
     * composer drops the stamp there; this pins the pure half of that decision so
     * a later edit that happens to match the OLD stamp cannot be swallowed.
     */
    @Test
    fun `the stamp is dropped when the box holds text that is not part of the body`() {
        assertFalse(shouldKeepClearedStamp("hello zzz", "hello world"))
        assertFalse(shouldKeepClearedStamp("goodbye", "hello world"))
    }

    @Test
    fun `the stamp survives the fragments arm A lets through`() {
        assertTrue(shouldKeepClearedStamp("h", "hello world"))
        assertTrue(shouldKeepClearedStamp("hello", "hello world"))
        assertTrue(shouldKeepClearedStamp("", "hello world"))
        assertTrue(shouldKeepClearedStamp("anything", null))
    }

    // ── [W1118-COMPOSER F-01 / A4] restamping rule ───────────────────────────

    /**
     * A clear that is unwinding a landed replay must NOT restamp: the box holds a
     * fragment, and recording it as "the body this clear removed" forgets the
     * full message, so the rest of the replay is no longer recognizable.
     */
    @Test
    fun `a clear that unwinds a landed replay keeps the previous stamp`() {
        assertFalse(
            shouldRestampClearedBody(
                clearedBody = "hel",
                previousStamp = body,
                msSinceStamp = 120L,
                guardClear = true,
            ),
        )
    }

    /**
     * [W1118-COMPOSER A4] THE defect: the state test cannot tell an un-winding
     * replay from the user sending a second message that happens to be a piece of
     * the first. Sending "hello world" and then "hello" 1.2 s later is NOT
     * unwinding anything — "hello" is the second message. Before the source flag,
     * this returned false, so the second message inherited the FIRST message's
     * stamp and a window already 1.2 s spent: its own replay then arrived outside
     * the window and walked back into the box.
     */
    @Test
    fun `a user send that happens to be a prefix of the previous message restamps`() {
        assertTrue(
            shouldRestampClearedBody(
                clearedBody = "hello",
                previousStamp = body,
                msSinceStamp = 1_200L,
            ),
        )
        assertTrue(
            shouldRestampClearedBody(
                clearedBody = "hello",
                previousStamp = "hello world",
                msSinceStamp = 1_200L,
            ),
        )
    }

    @Test
    fun `a clear of genuinely new text restamps`() {
        assertTrue(
            shouldRestampClearedBody(
                clearedBody = "completely different words",
                previousStamp = body,
                msSinceStamp = 120L,
                guardClear = true,
            ),
        )
    }

    /**
     * The window bound is load-bearing, not decoration: ten seconds later the old
     * stamp is dead, so a real clear MUST overwrite it — otherwise the next
     * message's own replay would never be recognised.
     */
    @Test
    fun `a prefix cleared after the window restamps`() {
        assertTrue(
            shouldRestampClearedBody(
                clearedBody = "hello",
                previousStamp = body,
                msSinceStamp = 1500L,
                guardClear = true,
            ),
        )
    }

    @Test
    fun `an empty clear never restamps`() {
        assertFalse(shouldRestampClearedBody(clearedBody = "", previousStamp = body, msSinceStamp = 10L))
        assertFalse(
            shouldRestampClearedBody(
                clearedBody = "",
                previousStamp = body,
                msSinceStamp = 10L,
                guardClear = true,
            ),
        )
    }

    @Test
    fun `the first clear has no previous stamp and always restamps`() {
        assertTrue(
            shouldRestampClearedBody(clearedBody = "hello", previousStamp = null, msSinceStamp = 10L),
        )
        assertTrue(
            shouldRestampClearedBody(
                clearedBody = "hello",
                previousStamp = null,
                msSinceStamp = 10L,
                guardClear = true,
            ),
        )
    }

    @Test
    fun `a backwards clock does not protect a stale stamp`() {
        assertTrue(
            shouldRestampClearedBody(
                clearedBody = "hel",
                previousStamp = body,
                msSinceStamp = -1L,
                guardClear = true,
            ),
        )
    }
}
