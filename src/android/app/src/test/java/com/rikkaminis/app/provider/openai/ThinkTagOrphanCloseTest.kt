package com.rikkaminis.app.provider.openai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [R8-B B-F08] A closer whose opener is already consumed is an ORPHAN.
 *
 * `stripOrphanThinkClosers` counted openers without ever consuming them, so once
 * ANY `<thinking>…</thinking>` had gone by, every later closer — including a bare
 * wire artifact in the body — looked "paired" and was KEPT. The counter is now a
 * pairing DEPTH: an opener +1, its closer -1, and a closer at depth 0 is dropped.
 *
 * The existing ThinkTagExtractionTest arms all stay valid (they are one-region
 * texts); these arms are the ones the old code could not pass.
 */
class ThinkTagOrphanCloseTest {

    @Test
    fun `a closer after a balanced region is dropped`() {
        assertEquals(
            "<thinking>a</thinking> b  c",
            stripOrphanThinkClosers("<thinking>a</thinking> b </thinking> c"),
        )
    }

    @Test
    fun `every orphan after a region is dropped`() {
        // Only the TOKEN is dropped, never the text before it (documented
        // boundary: those deltas were already streamed as visible, a scanner
        // cannot retract them). My first expectation deleted "one two" as well —
        // that was the test being wrong, not the product.
        assertEquals(
            "<thinking>a</thinking> one two tail",
            stripOrphanThinkClosers("<thinking>a</thinking> one</thinking> two</thinking> tail"),
        )
    }

    @Test
    fun `two balanced regions keep both closers`() {
        val text = "<thinking>a</thinking> mid <thinking>b</thinking>"
        assertEquals(text, stripOrphanThinkClosers(text))
    }

    @Test
    fun `an orphan between two balanced regions is dropped`() {
        assertEquals(
            "<thinking>a</thinking> orphan mid <thinking>b</thinking>",
            stripOrphanThinkClosers("<thinking>a</thinking> orphan</thinking> mid <thinking>b</thinking>"),
        )
    }

    @Test
    fun `nested openers need as many closers`() {
        // Depth semantics, not a boolean: two openers leave depth 2, so BOTH
        // following closers are paired; the third is an orphan.
        assertEquals(
            "<thinking><thinking>x</thinking></thinking> y",
            stripOrphanThinkClosers("<thinking><thinking>x</thinking></thinking> y</thinking>"),
        )
    }

    @Test
    fun `the pre-fix arm still holds - a lone closer is dropped`() {
        assertEquals("plain text", stripOrphanThinkClosers("plain text</thinking>"))
    }

    @Test
    fun `case insensitive and bracket aliases pair and drain`() {
        assertEquals(
            "<THINKING>a</THINKING> tail",
            stripOrphanThinkClosers("<THINKING>a</THINKING> tail</thinking>"),
        )
        assertEquals(
            "[think]a[/think] tail",
            stripOrphanThinkClosers("[think]a[/think] tail[/think]"),
        )
    }

    @Test
    fun `a closer in a code span never drains an opener`() {
        // Backticked tokens are quoted prose, not tag syntax — they must not
        // consume depth, otherwise the real closer after them would look orphan.
        val text = "`</thinking>` in code, then <thinking>x</thinking>"
        assertEquals(text, stripOrphanThinkClosers(text))
    }
}
