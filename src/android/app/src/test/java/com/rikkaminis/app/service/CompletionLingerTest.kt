package com.rikkaminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R4-U F-05] Table-driven contract of the bounded completion-linger window.
 *
 * The defect this pins: a background turn end set the overlay's completion
 * state, but the tracker's 500 ms idle stop tore the service — and the capsule
 * with it — down before the state was ever visible. The window must be armed
 * ONLY for a background turn end with a showable capsule, and it must be
 * FINITE (an unresolved capsule must not pin a foreground service forever).
 */
class CompletionLingerTest {

    private data class ArmRow(
        val label: String,
        val wasActive: Boolean,
        val appForeground: Boolean,
        val eligible: Boolean,
        val expectArmed: Boolean,
    )

    @Test
    fun armingTable() {
        val rows = listOf(
            // The case that used to be broken: background turn end + capsule.
            ArmRow("background turn end arms", wasActive = true, appForeground = false, eligible = true, expectArmed = true),
            // A foreground turn end shows the reply in-app — nothing to linger for.
            ArmRow("foreground turn end does not arm", wasActive = true, appForeground = true, eligible = true, expectArmed = false),
            // Overlay toggle off / no permission: the notification row would
            // just show "Idle" for 30 s for nothing.
            ArmRow("ineligible capsule does not arm", wasActive = true, appForeground = false, eligible = false, expectArmed = false),
            // A non-owner call (session never active) must not arm.
            ArmRow("defensive no-op does not arm", wasActive = false, appForeground = false, eligible = true, expectArmed = false),
        )
        rows.forEach { row ->
            val deadline = CompletionLinger.deadlineOnTurnEnd(
                nowMs = 1_000L,
                wasActive = row.wasActive,
                appForeground = row.appForeground,
                eligible = row.eligible,
            )
            if (row.expectArmed) {
                assertEquals(row.label, 1_000L + CompletionLinger.LINGER_MS, deadline)
            } else {
                assertEquals(row.label, 0L, deadline)
            }
        }
    }

    @Test
    fun windowIsFiniteAndSane() {
        // 30 s: long enough to read a capsule, short enough that OEM battery
        // dashboards keep treating the service as a normal transition.
        assertEquals(30_000L, CompletionLinger.LINGER_MS)
    }

    @Test
    fun remainingTable() {
        // Never armed → no time left, stop now.
        assertEquals(0L, CompletionLinger.remainingMs(0L, nowMs = 5_000L))
        // Armed at 1_000, checked at 31_000 → 0 (expired exactly at the edge).
        assertEquals(0L, CompletionLinger.remainingMs(31_000L, nowMs = 31_000L))
        // Armed at 1_000, checked at 16_000 → 15_000 left.
        assertEquals(15_000L, CompletionLinger.remainingMs(31_000L, nowMs = 16_000L))
        // Clock jumped backwards (reboot / manual change): never report negative.
        assertEquals(0L, CompletionLinger.remainingMs(31_000L, nowMs = 40_000L))
    }

    @Test
    fun shouldStopNowTable() {
        assertFalse("inside window keeps the service", CompletionLinger.shouldStopNow(31_000L, 16_000L))
        assertTrue("expired window stops", CompletionLinger.shouldStopNow(31_000L, 31_000L))
        assertTrue("no window stops", CompletionLinger.shouldStopNow(0L, 1_000L))
    }

    @Test
    fun insaneClockDoesNotArm() {
        assertEquals(0L, CompletionLinger.deadlineOnTurnEnd(nowMs = 0L, wasActive = true, appForeground = false, eligible = true))
        assertEquals(0L, CompletionLinger.deadlineOnTurnEnd(nowMs = -1L, wasActive = true, appForeground = false, eligible = true))
    }
}
