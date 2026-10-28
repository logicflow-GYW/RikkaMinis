package com.rikkaminis.app.provider.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [r3-b08 F-12] Doubao v3 TTS reports business errors INSIDE a 200 stream.
 *
 * The frame loop used to `continue` on any non-zero code, so an invalid
 * `speaker`/resource id produced a half-length clip that the caller received as
 * a success. `code == 20000000` is the vendor's "stream finished OK" sentinel
 * and must not be treated as an error.
 */
class DoubaoFrameErrorTest {

    @Test
    fun `a zero code frame is not an error`() {
        assertNull(doubaoFrameError(0, ""))
    }

    @Test
    fun `a frame with no code field reads as zero`() {
        assertNull(doubaoFrameError(0, "ok"))
    }

    @Test
    fun `the stream-finished sentinel is not an error`() {
        assertNull(doubaoFrameError(20000000, "ok"))
    }

    @Test
    fun `an invalid speaker is surfaced with its code and message`() {
        assertEquals(
            "Doubao v3 TTS error [30000001]: invalid voice_type",
            doubaoFrameError(30000001, "invalid voice_type"),
        )
    }

    @Test
    fun `a blank message still names the code`() {
        assertEquals(
            "Doubao v3 TTS error [45000001]: unknown error",
            doubaoFrameError(45000001, "   "),
        )
    }
}
