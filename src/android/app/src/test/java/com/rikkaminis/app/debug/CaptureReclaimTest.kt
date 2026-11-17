package com.rikkaminis.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [WAVE-1118 D9-F08] A `PixelCopy`-refused frame must be released before the
 * canvas replacement is allocated.
 *
 * Both screenshot channels (DebugRPCHandler.handleScreenshot and
 * DebugScreenshotRing.capture) allocate a full-screen bitmap, ask PixelCopy to
 * fill it, and on a refusal draw the view onto a *second* bitmap — the first
 * one used to be abandoned there, one full-screen frame per refused capture.
 *
 * The capture type is generic so the decision can be tested without a `Bitmap`
 * (which is a throwing stub outside a device). Expectations are literals.
 */
class CaptureReclaimTest {

    private class Frame(val id: String)

    private class Log {
        val events = mutableListOf<String>()
    }

    @Test
    fun `releases the refused frame before the fallback allocates a new one`() {
        val log = Log()
        val replacement = discardFailedCapture(
            Frame("refused"),
            { log.events += "release ${it.id}" },
        ) {
            log.events += "fallback canvas"
            Frame("canvas")
        }
        assertEquals("canvas", replacement.id)
        assertEquals(listOf("release refused", "fallback canvas"), log.events)
    }

    @Test
    fun `a release failure still yields the fallback frame`() {
        val replacement = discardFailedCapture(
            Frame("refused"),
            { throw IllegalStateException("already recycled") },
        ) { Frame("canvas") }
        assertEquals("canvas", replacement.id)
    }

    @Test
    fun `a throwing fallback is not swallowed`() {
        val log = Log()
        try {
            discardFailedCapture(Frame("refused"), { log.events += "release ${it.id}" }) {
                throw OutOfMemoryError("alloc")
            }
            fail("expected the fallback's failure to propagate")
        } catch (expected: OutOfMemoryError) {
            assertEquals("alloc", expected.message)
        }
        assertEquals("the refused frame is still released first", listOf("release refused"), log.events)
    }
}
