package com.rikkaminis.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRingBufferTest {

    @Test
    fun `appends in order and keeps all levels`() {
        val b = LogRingBuffer()
        b.append("[1] [INFO] a")
        b.append("[2] [DEBUG] b")
        b.append("[LOGCAT] c")
        assertEquals(listOf("[1] [INFO] a", "[2] [DEBUG] b", "[LOGCAT] c"), b.content())
    }

    @Test
    fun `evicts oldest when full`() {
        val b = LogRingBuffer(capacity = 3)
        b.append("1"); b.append("2"); b.append("3"); b.append("4")
        assertEquals(listOf("2", "3", "4"), b.content())
    }

    @Test
    fun `first snapshot allowed then deduped within window`() {
        val b = LogRingBuffer()
        assertTrue(b.shouldSnapshot(1_000))
        assertFalse(b.shouldSnapshot(1_000 + LogRingBuffer.SNAPSHOT_MIN_INTERVAL_MS - 1))
        assertTrue(b.shouldSnapshot(1_000 + LogRingBuffer.SNAPSHOT_MIN_INTERVAL_MS))
    }

    @Test
    fun `dedup window moved only by shouldSnapshot - mark-before-write contract`() {
        // shouldSnapshot marks the window itself; a false return must not move it.
        val b = LogRingBuffer()
        assertTrue(b.shouldSnapshot(0))
        // Failed snapshot attempts (false returns) don't extend the window.
        assertFalse(b.shouldSnapshot(1))
        assertEquals(false, b.shouldSnapshot(LogRingBuffer.SNAPSHOT_MIN_INTERVAL_MS - 1))
    }

    @Test
    fun `clear resets window and content`() {
        val b = LogRingBuffer()
        b.append("x")
        b.clear()
        assertEquals(emptyList<String>(), b.content())
        assertTrue(b.shouldSnapshot(5_000))
    }
}
