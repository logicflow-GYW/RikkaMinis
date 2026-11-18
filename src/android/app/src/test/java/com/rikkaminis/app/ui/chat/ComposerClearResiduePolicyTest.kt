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
}
