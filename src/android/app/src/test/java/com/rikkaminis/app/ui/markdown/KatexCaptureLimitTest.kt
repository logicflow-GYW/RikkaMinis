package com.rikkaminis.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [internalFitCaptureSize] — the cap that keeps a content-sized
 * WebView→Bitmap capture (two KaTeX sites) from becoming an unbounded native
 * allocation. 2026-09-13 context: native heap 38MB → 1141MB in ~4s between
 * shell commands, exactly the shape `Bitmap.createBitmap(w, h, ARGB_8888)`
 * takes when `w`/`h` come from rendered content.
 */
class KatexCaptureLimitTest {

    @Test
    fun `a normal formula passes through untouched`() {
        val fit = internalFitCaptureSize(600, 200)
        assertTrue(fit.ok)
        assertEquals(600, fit.width)
        assertEquals(200, fit.height)
        assertEquals(1f, fit.scale)
    }

    @Test
    fun `the cap boundary itself passes through`() {
        val fit = internalFitCaptureSize(CAPTURE_MAX_EDGE_PX, 1)
        assertTrue(fit.ok)
        assertEquals(CAPTURE_MAX_EDGE_PX, fit.width)
        assertEquals(1f, fit.scale)
    }

    @Test
    fun `an over-wide capture is scaled to the edge cap keeping aspect`() {
        val fit = internalFitCaptureSize(40_000, 1_000)
        assertTrue(fit.ok)
        assertEquals(CAPTURE_MAX_EDGE_PX, fit.width)
        assertTrue(fit.height <= CAPTURE_MAX_EDGE_PX)
        // Aspect ratio preserved to within a pixel of truncation.
        val expectedH = (1_000.0 * CAPTURE_MAX_EDGE_PX / 40_000.0).toInt()
        assertEquals(expectedH, fit.height)
        assertTrue(fit.scale < 1f)
    }

    @Test
    fun `an over-tall capture is scaled to the edge cap`() {
        val fit = internalFitCaptureSize(500, 100_000)
        assertTrue(fit.ok)
        assertEquals(CAPTURE_MAX_EDGE_PX, fit.height)
        assertTrue(fit.width >= 1)
    }

    @Test
    fun `a pathological 100k by 100k request is bounded by bytes not just the edge`() {
        // Even after fitting to 8192×8192 the bitmap would be ~268 MB, so the
        // byte cap must bind as well. This is the "one formula kills the app"
        // case the guard exists for.
        val fit = internalFitCaptureSize(100_000, 100_000)
        assertTrue(fit.ok)
        assertTrue(fit.width <= CAPTURE_MAX_EDGE_PX)
        assertTrue(fit.height <= CAPTURE_MAX_EDGE_PX)
        assertTrue(fit.width.toLong() * fit.height.toLong() * 4L <= CAPTURE_MAX_BYTES)
    }

    @Test
    fun `every fitted size stays inside both caps across a wide sweep`() {
        val edges = listOf(1, 17, 600, 8192, 8193, 20_000, 200_000)
        for (w in edges) {
            for (h in edges) {
                val fit = internalFitCaptureSize(w, h)
                assertTrue("degenerate for ${w}x$h", fit.ok)
                assertTrue("edge ${w}x$h → ${fit.width}x${fit.height}", fit.width <= CAPTURE_MAX_EDGE_PX)
                assertTrue("edge ${w}x$h → ${fit.width}x${fit.height}", fit.height <= CAPTURE_MAX_EDGE_PX)
                assertTrue(
                    "bytes ${w}x$h → ${fit.width}x${fit.height}",
                    fit.width.toLong() * fit.height.toLong() * 4L <= CAPTURE_MAX_BYTES,
                )
                assertTrue(fit.width >= 1 && fit.height >= 1)
                assertTrue(fit.scale in 0.0001f..1f)
            }
        }
    }

    @Test
    fun `degenerate requests are refused rather than allocated`() {
        assertFalse(internalFitCaptureSize(0, 100).ok)
        assertFalse(internalFitCaptureSize(100, 0).ok)
        assertFalse(internalFitCaptureSize(-5, -5).ok)
    }

    @Test
    fun `custom caps are honoured`() {
        val fit = internalFitCaptureSize(1000, 1000, maxEdgePx = 100, maxBytes = 100L * 100L * 4L)
        assertTrue(fit.ok)
        assertTrue(fit.width <= 100 && fit.height <= 100)
    }
}
