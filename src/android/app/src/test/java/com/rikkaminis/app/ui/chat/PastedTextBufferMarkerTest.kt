package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [r8-C-F07] The send-blocking guard and the expander must agree on what
 * "known" means.
 *
 * A DETACHED block (`inlineMarker = false`) is pasted into an EMPTY field: it
 * has no literal in the composer, and the chip row is its only trace. The
 * expander therefore ignores it (`expandPastePlaceholders` filters on
 * `inlineMarker`), but the guard's `known` set contained EVERY buffer id — so a
 * hand-typed `[Pasted#1]` for a detached block #1 was treated as expandable,
 * the send was allowed, and the model received the literal `[Pasted#1]` plus
 * the block appended at the end (the block was duplicated and the placeholder
 * was never resolved).
 *
 * Fail-closed direction: the guard is the side that must over-report, so its
 * "known" set is now the same set the expander can actually resolve.
 */
class PastedTextBufferMarkerTest {

    @Test
    fun `a detached block id does not make a hand-typed marker expandable`() {
        val buffer = listOf(PastedText(1, "the long report", inlineMarker = false))
        assertEquals(
            listOf("[Pasted#1]"),
            unexpandedPasteMarkers("[Pasted#1]", buffer),
        )
    }

    @Test
    fun `a detached block does not silence the guard for a mixed buffer`() {
        val buffer = listOf(
            PastedText(1, "detached", inlineMarker = false),
            PastedText(2, "inline", inlineMarker = true),
        )
        assertEquals(
            listOf("[Pasted#1]"),
            unexpandedPasteMarkers("[Pasted#1] and [Pasted#2]", buffer),
        )
        // #2 really is expandable, so it must NOT be reported.
        assertEquals(emptyList<String>(), unexpandedPasteMarkers("[Pasted#2]", buffer))
    }

    @Test
    fun `an inline block id is still expandable and not reported`() {
        val buffer = listOf(PastedText(3, "inline", inlineMarker = true))
        assertEquals(emptyList<String>(), unexpandedPasteMarkers("[Pasted#3]", buffer))
    }

    @Test
    fun `an id absent from the buffer is still reported`() {
        val buffer = listOf(PastedText(1, "detached", inlineMarker = false))
        assertEquals(listOf("[Pasted#9]"), unexpandedPasteMarkers("[Pasted#9]", buffer))
    }

    @Test
    fun `an empty buffer reports every marker`() {
        assertEquals(
            listOf("[Pasted#1]", "[Pasted#2]"),
            unexpandedPasteMarkers("[Pasted#1] [Pasted#2]", emptyList()),
        )
    }

    /**
     * The guard and the expander must partition the same way: whatever the
     * guard calls expandable, the expander must actually expand.
     */
    @Test
    fun `every id the guard accepts is one the expander resolves`() {
        val buffer = listOf(
            PastedText(1, "detached-A", inlineMarker = false),
            PastedText(2, "inline-B", inlineMarker = true),
            PastedText(3, "detached-C", inlineMarker = false),
        )
        for (id in listOf(1, 2, 3)) {
            val marker = "[Pasted#$id]"
            val reported = unexpandedPasteMarkers(marker, buffer)
            val (expanded, consumed) = expandPastePlaceholders(marker, buffer)
            if (reported.isEmpty()) {
                assertEquals("guard let #$id through but the expander skipped it", setOf(id), consumed)
                assertTrue("guard let #$id through but nothing was substituted", expanded != marker)
            } else {
                assertEquals("guard reported #$id but the expander consumed it", emptySet<Int>(), consumed)
                assertEquals(marker, expanded)
            }
        }
    }

    @Test
    fun `detached blocks are still appended after the body`() {
        val buffer = listOf(PastedText(1, "material", inlineMarker = false))
        assertEquals("my question\n\nmaterial", appendUnlinkedPastes("my question", buffer))
    }
}
