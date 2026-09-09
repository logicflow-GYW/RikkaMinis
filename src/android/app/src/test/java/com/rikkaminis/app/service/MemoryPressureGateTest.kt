package com.rikkaminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [memory-pressure-gate] Unit tests for [MemoryPressureGate] pure logic:
 * VmRSS parsing + threshold classification.
 */
class MemoryPressureGateTest {

    // --- VmRSS parsing ---

    @Test
    fun `parseVmRss extracts kibibytes and converts to MB`() {
        val status = """
            Name:   com.rikkaminis.app
            State:  S (sleeping)
            VmRSS:	    286912 kB
            VmSize:	  1200000 kB
        """.trimIndent()
        assertEquals(286912L / 1024L, MemoryPressureGate.parseVmRss(status))
    }

    @Test
    fun `parseVmRss returns 0 when VmRSS line missing`() {
        val status = "Name:   test\nVmSize: 100 kB\n"
        assertEquals(0L, MemoryPressureGate.parseVmRss(status))
    }

    @Test
    fun `parseVmRss handles zero and malformed values`() {
        assertEquals(0L, MemoryPressureGate.parseVmRss("VmRSS: 0 kB"))
        assertEquals(0L, MemoryPressureGate.parseVmRss("VmRSS: nope"))
        assertEquals(0L, MemoryPressureGate.parseVmRss(""))
    }

    // --- Threshold classification ---

    @Test
    fun `levelFor below 600MB is NORMAL`() {
        assertEquals(MemoryPressureLevel.NORMAL, MemoryPressureGate.levelFor(100L))
        assertEquals(MemoryPressureLevel.NORMAL, MemoryPressureGate.levelFor(599L))
    }

    @Test
    fun `levelFor at 600-799MB is ELEVATED`() {
        assertEquals(MemoryPressureLevel.ELEVATED, MemoryPressureGate.levelFor(600L))
        assertEquals(MemoryPressureLevel.ELEVATED, MemoryPressureGate.levelFor(700L))
        assertEquals(MemoryPressureLevel.ELEVATED, MemoryPressureGate.levelFor(799L))
    }

    @Test
    fun `levelFor at or above 800MB is CRITICAL`() {
        assertEquals(MemoryPressureLevel.CRITICAL, MemoryPressureGate.levelFor(800L))
        assertEquals(MemoryPressureLevel.CRITICAL, MemoryPressureGate.levelFor(900L))
    }

    @Test
    fun `level() uses injected rssReader`() {
        MemoryPressureGate.rssReader = { 150L }
        assertEquals(MemoryPressureLevel.NORMAL, MemoryPressureGate.level())
        MemoryPressureGate.rssReader = { 700L }
        assertEquals(MemoryPressureLevel.ELEVATED, MemoryPressureGate.level())
        MemoryPressureGate.rssReader = { 850L }
        assertEquals(MemoryPressureLevel.CRITICAL, MemoryPressureGate.level())
        MemoryPressureGate.rssReader = { MemoryPressureGate.readRssFromProc() }
    }

    // --- Listener / reclaim hooks ---

    @Test
    fun `notify fires listener only for non-NORMAL levels`() {
        var fired = mutableListOf<Pair<MemoryPressureLevel, Long>>()
        MemoryPressureGate.rssReader = { 900L }
        MemoryPressureGate.pressureListener = { level, rss -> fired.add(level to rss) }

        MemoryPressureGate.notify(MemoryPressureLevel.NORMAL)
        assertEquals(0, fired.size)
        MemoryPressureGate.notify(MemoryPressureLevel.ELEVATED)
        MemoryPressureGate.notify(MemoryPressureLevel.CRITICAL)
        assertEquals(2, fired.size)
        assertEquals(MemoryPressureLevel.ELEVATED, fired[0].first)
        assertEquals(MemoryPressureLevel.CRITICAL, fired[1].first)

        MemoryPressureGate.pressureListener = { _, _ -> }
        MemoryPressureGate.rssReader = { MemoryPressureGate.readRssFromProc() }
    }

    @Test
    fun `reclaimAndWait invokes reclaim hook`() = kotlinx.coroutines.test.runTest {
        var reclaimed = 0
        MemoryPressureGate.reclaimHook = { reclaimed++ }
        MemoryPressureGate.reclaimAndWait(waitMs = 1L)
        assertEquals(1, reclaimed)
        MemoryPressureGate.reclaimHook = {}
    }

    @Test
    fun `readRssFromProc returns a plausible value on a real device`() {
        // In sandbox / CI the file may not exist — 0 is the safe NORMAL side.
        val rss = MemoryPressureGate.readRssFromProc()
        assertTrue("rss=$rss should be >= 0", rss >= 0L)
    }

    // --- [native-rss-tool-guard] post-reclaim rejection ---

    @Test
    fun `shouldRejectAfterReclaim is false below critical and true at or above`() {
        assertTrue(MemoryPressureGate.shouldRejectAfterReclaim(800L))
        assertTrue(MemoryPressureGate.shouldRejectAfterReclaim(900L))
        assertFalse(MemoryPressureGate.shouldRejectAfterReclaim(799L))
        assertFalse(MemoryPressureGate.shouldRejectAfterReclaim(0L))
    }

    @Test
    fun `shouldRejectAfterReclaim rejects when reader reports critical after reclaim`() {
        MemoryPressureGate.rssReader = { 850L }
        assertTrue(
            MemoryPressureGate.shouldRejectAfterReclaim(MemoryPressureGate.rssReader())
        )
        MemoryPressureGate.rssReader = { 400L }
        assertFalse(
            MemoryPressureGate.shouldRejectAfterReclaim(MemoryPressureGate.rssReader())
        )
        MemoryPressureGate.rssReader = { MemoryPressureGate.readRssFromProc() }
    }
}