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
 * [fix/composer-post-send-residue-2] The FIRST version of this guard only
 * handled the case where the whole body arrived in one commit and the box was
 * still empty — it returned false as soon as either copy held anything. Real
 * third-party IMEs commit character by character, so the replay disarmed the
 * guard with its first 1-3 characters and the rest streamed in. On-device proof
 * of that shape: `ComposerMirror` logged `field = vm + 1ch` on 14 dictation
 * sessions on 2026-11-18.
 *
 * Every expectation below is a literal string / literal millisecond count.
 * Nothing is derived from the function under test, so a change of the window or
 * the replay floor cannot silently make a case pass.
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

    // ── arm A: the box is still empty ────────────────────────────────────────

    @Test
    fun `the whole cleared body replayed into the emptied composer is a residue`() {
        assertEquals(ComposerResidueVerdict.DROP, verdict(incoming = body))
    }

    @Test
    fun `a fragmented replay that is a long prefix is a residue`() {
        // IME buffers replay the body in pieces; the first piece arrives alone.
        assertEquals(ComposerResidueVerdict.DROP, verdict(incoming = "hello world, this"))
    }

    @Test
    fun `a fragmented replay that is a long suffix is a residue`() {
        assertEquals(ComposerResidueVerdict.DROP, verdict(incoming = "the message I just sent"))
    }

    @Test
    fun `a four character prefix is still a residue`() {
        // The documented floor: four matching characters is a fragment, not a
        // keystroke (the composer is empty, so the user would be typing anew).
        assertEquals(ComposerResidueVerdict.DROP, verdict(incoming = "hell"))
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

    // ── arm B: the replay already landed, the box is no longer empty ─────────

    /**
     * The defect this whole batch exists for. A char-by-char IME replay lands
     * "h" (allowed by the floor), then "he", "hel", "hell"… The first version
     * returned false the moment `fieldText` was non-empty, so nothing after "h"
     * was ever examined and the whole message rebuilt itself in the box.
     */
    @Test
    fun `a replay arriving after its own first fragment is still caught`() {
        assertEquals(
            ComposerResidueVerdict.DROP_AND_CLEAR,
            verdict(incoming = "he", fieldText = "h"),
        )
    }

    @Test
    fun `a long replay onto an already filled box wipes what accumulated`() {
        // The user is left staring at "hello world, this is the message" — that
        // is the reported symptom, so the verdict must also undo it, not just
        // refuse to grow it.
        assertEquals(
            ComposerResidueVerdict.DROP_AND_CLEAR,
            verdict(incoming = body, fieldText = "hello world, this is the mess"),
        )
    }

    @Test
    fun `a replay is caught when only the view model holds the earlier fragments`() {
        // The 150 ms burst flush writes the ViewModel a step ahead of the field;
        // either copy holding replay text means the box is replay-filled.
        assertEquals(
            ComposerResidueVerdict.DROP_AND_CLEAR,
            verdict(incoming = "hello world", vmText = "hel"),
        )
    }

    /**
     * [W1118-COMPOSER F-02] The shape that produced ZERO `ComposerResidue` log
     * lines on the device that still had the bug: `clearComposerInput` stamps the
     * body BEFORE `clearFocus()`, and `clearFocus` is what makes the IME settle
     * its composing region — so the commit that comes back is the stamped body
     * plus one character the IME added (auto-space, full stop, comma).
     *
     * The first version compared with `==` / startsWith / endsWith and returned
     * NONE for every one of these. The zero log count was the tell, not proof
     * that no replay happened.
     */
    @Test
    fun `an english auto-space appended by finishComposingText is still a replay`() {
        assertEquals(ComposerResidueVerdict.DROP, verdict(incoming = "hello world, this is the message I just sent "))
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
     * [W1118-COMPOSER F-02] An IME resuming from its own buffer does not have to
     * start at the beginning of the body, so a MIDDLE piece counts too.
     */
    @Test
    fun `a middle fragment of the cleared body is a replay`() {
        assertEquals(
            ComposerResidueVerdict.DROP,
            verdict(incoming = "lo world, this is", clearedText = "hello world, this is the message I just sent"),
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

    /**
     * The fragment floor also has to hold on arm B (the box already holds an
     * earlier fragment). Without it, any short coincidence inside the body would
     * authorise a wipe of a box that is still legitimately replay-filled — a
     * three-character "o w" occurs inside "hello world" by chance, so `contains`
     * alone must never be enough.
     */
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

    /**
     * The safety property that makes arm B usable: the accumulated text must
     * itself belong to the cleared body. One keystroke of the user's own text
     * and the box is his, so no wipe — a swallowed word is worse than a stale
     * fragment.
     */
    @Test
    fun `typing on top of a landed fragment is never wiped`() {
        assertEquals(
            ComposerResidueVerdict.NONE,
            verdict(incoming = "hello world", fieldText = "hz"),
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
        assertTrue(drop(incoming = "hell"))
        assertTrue(drop(incoming = "he", fieldText = "h"))
        assertFalse(drop(incoming = "hello world", fieldText = "hz"))
    }

    /**
     * Fragment detection is shared with the fingerprint's "do not self-destruct"
     * check, so pin the three shapes both sides depend on.
     */
    @Test
    fun `fragment detection covers whole prefix and suffix`() {
        assertTrue(isFragmentOfClearedBody(body, body))
        assertTrue(isFragmentOfClearedBody("hello", body))
        assertTrue(isFragmentOfClearedBody("just sent", body))
        assertFalse(isFragmentOfClearedBody("hello world, the message", body))
        assertFalse(isFragmentOfClearedBody("", body))
    }

    // ── [W1118-COMPOSER F-01] restamping rule ────────────────────────────────

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
            ),
        )
    }

    @Test
    fun `an empty clear never restamps`() {
        assertFalse(shouldRestampClearedBody(clearedBody = "", previousStamp = body, msSinceStamp = 10L))
    }

    @Test
    fun `the first clear has no previous stamp and always restamps`() {
        assertTrue(
            shouldRestampClearedBody(clearedBody = "hello", previousStamp = null, msSinceStamp = 10L),
        )
    }

    @Test
    fun `a backwards clock does not protect a stale stamp`() {
        assertTrue(
            shouldRestampClearedBody(
                clearedBody = "hel",
                previousStamp = body,
                msSinceStamp = -1L,
            ),
        )
    }
}
