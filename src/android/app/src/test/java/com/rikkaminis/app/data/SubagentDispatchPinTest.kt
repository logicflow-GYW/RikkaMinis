package com.rikkaminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `clear forgets every session`() {
        SubagentDispatchPin.begin("a")
        SubagentDispatchPin.begin("b")
        SubagentDispatchPin.clear()
        assertFalse(SubagentDispatchPin.isDispatching("a"))
        assertFalse(SubagentDispatchPin.isDispatching("b"))
    }
}