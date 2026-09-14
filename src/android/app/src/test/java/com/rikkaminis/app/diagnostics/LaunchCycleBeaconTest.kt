package com.rikkaminis.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [audit-0914] JVM tests for the crash-cycle gauge behind the Safe Start gate.
 *
 * The regression this pins: on a production device `clean_exit` is only written
 * from `Application.onTerminate()`, which Android skips, so the old
 * `launches − clean_exits` gauge collapsed into "how many times has this app
 * been started" (2026-09-14 field data: 9 launch lines, 0 clean_exit lines, and
 * the logged counter tracked cold starts exactly, 2 → 7). A single
 * already-recovered 3 s stall inside the window was then enough to trip the
 * gate, and the next launch opened a blank session instead of the one the user
 * had been working in.
 */
class LaunchCycleBeaconTest {

    private fun launch(verdict: String) =
        "[2026-09-14T18:34:06+0800] launch pid=8762 verdict=$verdict"

    @Test
    fun `no history means no crash cycles`() {
        assertEquals(0, LaunchCycleBeacon.consecutiveCrashLaunches(emptyList()))
    }

    @Test
    fun `consecutive crash cycles are counted`() {
        val lines = listOf(launch("silent_kill"), launch("crash_or_stall"), launch("crash_or_stall"))
        assertEquals(2, LaunchCycleBeacon.consecutiveCrashLaunches(lines))
    }

    @Test
    fun `a recovered cycle ends the run`() {
        val lines = listOf(launch("crash_or_stall"), launch("silent_kill"), launch("crash_or_stall"))
        assertEquals(1, LaunchCycleBeacon.consecutiveCrashLaunches(lines))
    }

    @Test
    fun `legacy lines without a verdict end the run`() {
        // Upgrading must not inherit a phantom crash loop: a history whose
        // newest entry predates the verdict field ends the run immediately.
        val lines = listOf(launch("crash_or_stall"), "<2026-09-13T20:00:00+0800> launch pid=1234")
        assertEquals(0, LaunchCycleBeacon.consecutiveCrashLaunches(lines))
    }

    @Test
    fun `crash verdict carrying a detail suffix still counts`() {
        val lines = listOf(launch("crash_or_stall(detail=stall-2026-09-14.log)"))
        assertEquals(1, LaunchCycleBeacon.consecutiveCrashLaunches(lines))
    }

    @Test
    fun `non-launch lines are ignored`() {
        val lines = listOf("garbage", launch("crash_or_stall"), "more garbage")
        assertEquals(1, LaunchCycleBeacon.consecutiveCrashLaunches(lines))
    }
}
