package com.rikkaminis.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R4-U F-07] Table-driven contract of the trim ladder's two rules.
 *
 * Rules (both were violated before this fix):
 *  1. "work in flight" = any session STREAMING or a tool executing. `MinisApp`
 *     handed the memory governor only `isToolRunning`, so a second session
 *     streaming with no tool running looked idle.
 *  2. at most ONE synchronous `System.gc()` per trim callback, and none while
 *     work is in flight. `TRIM_MEMORY_RUNNING_CRITICAL` used to have two
 *     independent GC sites: an idle process collected twice, a busy one still
 *     collected once — mid-generation, which is exactly the stall the older
 *     `[P2-active-session-guard]` comment was written to prevent.
 */
class TrimReclaimPolicyTest {

    @Test
    fun hasActiveWorkTable() {
        data class Row(
            val label: String,
            val sessions: Set<String>,
            val toolRunning: Boolean,
            val expectBusy: Boolean,
        )
        val rows = listOf(
            Row("idle process", emptySet(), false, expectBusy = false),
            Row("streaming session only", setOf("s1"), false, expectBusy = true),
            Row("second session streaming, no tool", setOf("s1", "s2"), false, expectBusy = true),
            Row("tool running, no stream", emptySet(), true, expectBusy = true),
            Row("both in flight", setOf("s1"), true, expectBusy = true),
        )
        rows.forEach { row ->
            val actual = TrimReclaimPolicy.hasActiveWork(row.sessions, row.toolRunning)
            if (row.expectBusy) {
                assertTrue(row.label, actual)
            } else {
                assertFalse(row.label, actual)
            }
        }
    }

    @Test
    fun shouldSyncGcTable() {
        data class Row(
            val reclaimsShells: Boolean,
            val engagesGate: Boolean,
            val activeWork: Boolean,
            val expectGc: Boolean,
        )
        val rows = listOf(
            // Foreground pressure only, idle → collect (legacy behaviour kept).
            Row(reclaimsShells = true, engagesGate = false, activeWork = false, expectGc = true),
            // Memory gate only, idle → collect.
            Row(reclaimsShells = false, engagesGate = true, activeWork = false, expectGc = true),
            // BOTH (the TRIM_MEMORY_RUNNING_CRITICAL foreground case): ONE
            // collection, not two.
            Row(reclaimsShells = true, engagesGate = true, activeWork = false, expectGc = true),
            // Neither branch fired → no GC.
            Row(reclaimsShells = false, engagesGate = false, activeWork = false, expectGc = false),
            // Work in flight always wins, whichever branch fired.
            Row(reclaimsShells = true, engagesGate = false, activeWork = true, expectGc = false),
            Row(reclaimsShells = false, engagesGate = true, activeWork = true, expectGc = false),
            Row(reclaimsShells = true, engagesGate = true, activeWork = true, expectGc = false),
            Row(reclaimsShells = false, engagesGate = false, activeWork = true, expectGc = false),
        )
        rows.forEach { row ->
            val label = "shells=${row.reclaimsShells} gate=${row.engagesGate} busy=${row.activeWork}"
            val actual = TrimReclaimPolicy.shouldSyncGc(
                reclaimsShells = row.reclaimsShells,
                engagesGate = row.engagesGate,
                hasActiveWork = row.activeWork,
            )
            if (row.expectGc) {
                assertTrue(label, actual)
            } else {
                assertFalse(label, actual)
            }
        }
    }
}
