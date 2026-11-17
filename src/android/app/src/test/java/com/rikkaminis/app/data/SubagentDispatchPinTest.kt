package com.rikkaminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The auto-pin that keeps a dispatching session on top while its children
 * update their own rows. The contract that matters is the reference count: the
 * FIRST dispatch pins, the LAST one restores, and a parent running several
 * sub-agents concurrently must not be unpinned by whichever child finishes
 * first.
 */
class SubagentDispatchPinTest {

    @Before
    fun reset() = SubagentDispatchPin.clear()

    @Test
    fun `first of one dispatch reports depth one`() {
        assertEquals(1, SubagentDispatchPin.begin("s1"))
        assertTrue(SubagentDispatchPin.isDispatching("s1"))
    }

    @Test
    fun `second concurrent dispatch reports depth two`() {
        SubagentDispatchPin.begin("s1")
        assertEquals(2, SubagentDispatchPin.begin("s1"))
    }

    @Test
    fun `only the last end reports zero`() {
        SubagentDispatchPin.begin("s1")
        SubagentDispatchPin.begin("s1")
        assertEquals(1, SubagentDispatchPin.end("s1"))
        assertTrue("a still-running dispatch must keep the pin", SubagentDispatchPin.isDispatching("s1"))
        assertEquals(0, SubagentDispatchPin.end("s1"))
        assertFalse(SubagentDispatchPin.isDispatching("s1"))
    }

    @Test
    fun `ending an unknown session is harmless and reports zero`() {
        assertEquals(0, SubagentDispatchPin.end("never-started"))
        assertFalse(SubagentDispatchPin.isDispatching("never-started"))
    }

    @Test
    fun `sessions are counted independently`() {
        SubagentDispatchPin.begin("a")
        SubagentDispatchPin.begin("a")
        SubagentDispatchPin.begin("b")
        assertEquals(0, SubagentDispatchPin.end("b"))
        assertEquals(1, SubagentDispatchPin.end("a"))
        assertTrue(SubagentDispatchPin.isDispatching("a"))
        assertFalse(SubagentDispatchPin.isDispatching("b"))
    }

    @Test
    fun `the last end can undo a pin written by another dispatch`() {
        // [INCR-1117 A-F02] Two overlapping dispatches on one parent: the
        // depth-1 dispatch writes the pin, the OTHER one finishes last and is
        // the only one still running when the window closes. Its `end` must
        // find the value the first dispatch wrote — a call-site local (the
        // pre-fix `var ourPin`) is null there and restores nothing, leaving the
        // parent pinned for good.
        SubagentDispatchPin.begin("s1")
        SubagentDispatchPin.noteWrittenPin("s1", 111L)
        SubagentDispatchPin.begin("s1")

        assertEquals(1, SubagentDispatchPin.end("s1"))
        assertTrue("a still-running dispatch keeps the window open", SubagentDispatchPin.isDispatching("s1"))
        assertEquals(0, SubagentDispatchPin.end("s1"))

        assertEquals(
            "the finishing dispatch must read the value it did not write",
            111L,
            SubagentDispatchPin.takeWrittenPin("s1") ?: -1L,
        )
        assertNull(
            "consumed — a later window must not undo a pin it never wrote",
            SubagentDispatchPin.takeWrittenPin("s1"),
        )
    }

    @Test
    fun `a window that never pinned records nothing`() {
        SubagentDispatchPin.begin("s1")
        assertEquals(0, SubagentDispatchPin.end("s1"))
        assertNull(SubagentDispatchPin.takeWrittenPin("s1"))
    }

    @Test
    fun `clear forgets every session`() {
        SubagentDispatchPin.begin("a")
        SubagentDispatchPin.begin("b")
        SubagentDispatchPin.noteWrittenPin("a", 7L)
        SubagentDispatchPin.clear()
        assertFalse(SubagentDispatchPin.isDispatching("a"))
        assertFalse(SubagentDispatchPin.isDispatching("b"))
        assertNull("a teardown must not leave a restore value behind", SubagentDispatchPin.takeWrittenPin("a"))
    }
}