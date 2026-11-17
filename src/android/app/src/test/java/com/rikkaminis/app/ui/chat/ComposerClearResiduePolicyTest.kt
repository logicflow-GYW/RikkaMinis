package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/composer-post-send-residue] Behavioural tests for
 * [isComposerClearResidue].
 *
 * The reported field symptom: a message is sent (visible in the transcript) but
 * the same text is still sitting in the composer. The send path clears both
 * copies (ViewModel string + field `TextFieldValue`); the text comes back when
 * the IME commits its own copy of what the user was typing AFTER that clear, and
 * `onValueChange` writes the field unconditionally before comparing it to the
 * ViewModel.
 *
 * Every expectation below is a literal string / literal millisecond count.
 * Nothing is derived from the function under test, so a change of the window or
 * the replay floor cannot silently make a case pass.
 */
class ComposerClearResiduePolicyTest {

    private fun verdict(
        incoming: String,
        clearedText: String? = "hello world, this is the message I just sent",
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

    @Test
    fun `the whole cleared body replayed into the emptied composer is a residue`() {
        assertTrue(verdict(incoming = "hello world, this is the message I just sent"))
    }

    @Test
    fun `a fragmented replay that is a long prefix is a residue`() {
        // IME buffers replay the body in pieces; the first piece arrives alone.
        assertTrue(verdict(incoming = "hello world, this"))
    }

    @Test
    fun `a fragmented replay that is a long suffix is a residue`() {
        assertTrue(verdict(incoming = "the message I just sent"))
    }

    @Test
    fun `a four character prefix is still a residue`() {
        // The documented floor: four matching characters is a fragment, not a
        // keystroke (the composer is empty, so the user would be typing anew).
        assertTrue(verdict(incoming = "hell"))
    }

    @Test
    fun `a three character prefix is not a residue`() {
        // Below the floor the match is indistinguishable from the user typing
        // those characters into the empty box. Dropping nothing here means the
        // worst case is a short fragment left over — never a swallowed key.
        assertFalse(verdict(incoming = "hel"))
    }

    @Test
    fun `one character is never a residue`() {
        assertFalse(verdict(incoming = "h"))
    }

    @Test
    fun `an edit outside the post-clear window is not a residue`() {
        assertFalse(verdict(incoming = "hello world, this is the message I just sent", msSinceClear = 1500L))
    }

    @Test
    fun `the last millisecond inside the window is still a residue`() {
        assertTrue(verdict(incoming = "hello world, this is the message I just sent", msSinceClear = 1499L))
    }

    @Test
    fun `a clock that went backwards never drops an edit`() {
        assertFalse(verdict(incoming = "hello world, this is the message I just sent", msSinceClear = -1L))
    }

    @Test
    fun `an edit arriving after the user typed something is never a residue`() {
        // The field is no longer the emptied one: the replay guard is off.
        assertFalse(verdict(incoming = "hello world, this is the message I just sent", fieldText = "x"))
    }

    @Test
    fun `an edit while the view model holds text is never a residue`() {
        assertFalse(verdict(incoming = "hello world, this is the message I just sent", vmText = "x"))
    }

    @Test
    fun `unrelated text is never a residue`() {
        assertFalse(verdict(incoming = "completely different words"))
    }

    @Test
    fun `a replay with nothing to compare against is not a residue`() {
        assertFalse(verdict(incoming = "hello world", clearedText = null))
        assertFalse(verdict(incoming = "hello world", clearedText = ""))
    }

    @Test
    fun `an empty incoming edit is not a residue`() {
        assertFalse(verdict(incoming = ""))
    }

    @Test
    fun `the window is one and a half seconds`() {
        // Contract pin, not a derivation: the constant is what the caller's
        // "post-clear" means on a real device.
        assertEquals(1500L, COMPOSER_CLEAR_RESIDUE_WINDOW_MS)
        assertEquals(4, COMPOSER_CLEAR_RESIDUE_MIN_REPLAY_CHARS)
    }
}
