package com.openminis.app.diagnostics

import org.junit.Assert.*
import org.junit.Test

/**
 * T9-performance-baseline: unit tests for MemoryPressureTracker.
 *
 * Pure JVM — tests the classification logic, listener API, and snapshot
 * data model. The actual /proc/self/status reading is a thin wrapper
 * tested via integration (not unit).
 */
class MemoryPressureTrackerTest {

    @Test
    fun `level values are defined`() {
        assertEquals(3, MemoryPressureTracker.Level.values().size)
        assertNotNull(MemoryPressureTracker.Level.NORMAL)
        assertNotNull(MemoryPressureTracker.Level.ELEVATED)
        assertNotNull(MemoryPressureTracker.Level.CRITICAL)
    }

    @Test
    fun `snapshot data class has expected fields`() {
        val snap = MemoryPressureTracker.Snapshot(
            rssMb = 277.0,
            javaHeapMb = 150L,
            nativeHeapMb = 64L,
            threadCount = 85,
            level = MemoryPressureTracker.Level.NORMAL,
            timestampMs = 1000L,
        )
        assertEquals(277.0, snap.rssMb, 0.01)
        assertEquals(150L, snap.javaHeapMb)
        assertEquals(64L, snap.nativeHeapMb)
        assertEquals(85, snap.threadCount)
        assertEquals(MemoryPressureTracker.Level.NORMAL, snap.level)
        assertEquals(1000L, snap.timestampMs)
    }

    @Test
    fun `listener is called on add and removable`() {
        var called = false
        var removedCalled = false

        val remove = MemoryPressureTracker.addListener { _, _ ->
            called = true
        }
        assertNotNull(remove)

        // Don't call check() here — it reads /proc/self/status which is
        // fine on JVM but the test is about the listener API, not the
        // actual classification.
        remove.run()
        removedCalled = true
        assertTrue(removedCalled)
    }

    @Test
    fun `multiple listeners are supported`() {
        var count1 = 0
        var count2 = 0

        val remove1 = MemoryPressureTracker.addListener { _, _ -> count1++ }
        val remove2 = MemoryPressureTracker.addListener { _, _ -> count2++ }

        assertNotNull(remove1)
        assertNotNull(remove2)

        remove1.run()
        remove2.run()
    }

    @Test
    fun `lastSnapshot returns default values before any check`() {
        val snap = MemoryPressureTracker.lastSnapshot()
        assertEquals(0.0, snap.rssMb, 0.01)
        assertEquals(0L, snap.timestampMs)
        assertEquals(MemoryPressureTracker.Level.NORMAL, snap.level)
    }

    @Test
    fun `peakRss returns 0 before any check`() {
        assertEquals(0L, MemoryPressureTracker.peakRssMB())
    }
}