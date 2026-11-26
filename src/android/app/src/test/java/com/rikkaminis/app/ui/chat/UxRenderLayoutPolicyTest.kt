package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UxRenderLayoutPolicyTest {
    // Structural stand-ins for layout input/size. The production call passes
    // actual Compose keys; no fake TextLayoutResult or layout engine is used.
    private data class Input(
        val text: String = "same",
        val fontSize: Int = 15,
        val lineHeight: Int = 22,
        val width: Int = 300,
        val density: Float = 1f,
        val color: Int = 0,
    )
    private data class Size(val width: Int = 300, val height: Int = 22)
    private val paragraph = Any()

    @Test fun `first layout and a reentered composition always publish`() {
        val input = Input()
        repeat(2) {
            assertTrue(uxRenderLayoutNeedsRefresh(null, input, null, paragraph, null, Size()))
        }
    }

    @Test fun `identical re-layouts preserve cached selection and code rect generation`() {
        val input = Input()
        repeat(100) {
            assertFalse(uxRenderLayoutNeedsRefresh(input, input.copy(), paragraph, paragraph, Size(), Size()))
        }
    }

    @Test fun `same-length text replacement is not a cache hit`() {
        assertTrue(uxRenderLayoutNeedsRefresh(Input(), Input(text = "else"), paragraph, paragraph, Size(), Size()))
    }

    @Test fun `font line height width and density invalidate even with the same outer size`() {
        val input = Input()
        for (changed in listOf(
            input.copy(fontSize = 16), input.copy(lineHeight = 24),
            input.copy(width = 280), input.copy(density = 1.2f),
        )) {
            assertTrue(uxRenderLayoutNeedsRefresh(input, changed, paragraph, paragraph, Size(), Size()))
        }
    }

    @Test fun `new paragraph geometry invalidates even when all inputs compare equal`() {
        assertTrue(uxRenderLayoutNeedsRefresh(Input(), Input(), paragraph, Any(), Size(), Size()))
    }

    @Test fun `different measured size invalidates even with reused paragraph`() {
        assertTrue(uxRenderLayoutNeedsRefresh(Input(), Input(), paragraph, paragraph, Size(), Size(height = 44)))
    }

    @Test fun `theme flip with equal text remains observable`() {
        assertTrue(uxRenderLayoutNeedsRefresh(Input(), Input(color = 1), paragraph, paragraph, Size(), Size()))
    }
}
