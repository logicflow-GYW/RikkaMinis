package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-rootfs-event-log] Pure-JVM tests for the host-side rootfs event log:
 * line formatting, append behavior, size cap truncation, boot-id
 * generation persistence, and the swallow-all failure contract (a broken
 * log destination must never break the rootfs operation being observed).
 */
class RootfsEventLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── formatLine ─────────────────────────────────────────────────────

    @Test
    fun formatLine_withDetail_includesEventAndDetail() {
        val line = RootfsEventLog.formatLine("REPAIR_STAGE3_RESET", "trigger=apkDatabaseUnusable", 1720000000000L)
        assertTrue(line.startsWith("["))
        assertTrue(line.contains("] REPAIR_STAGE3_RESET trigger=apkDatabaseUnusable"))
    }

    @Test
    fun formatLine_emptyDetail_omitsTrailingSpace() {
        val line = RootfsEventLog.formatLine("MANUAL_RESET", "  ", 1720000000000L)
        assertTrue(line.endsWith("] MANUAL_RESET"))
        assertFalse(line.endsWith(" "))
    }

    @Test
    fun formatLine_timestampIsIsoLocal() {
        val line = RootfsEventLog.formatLine("INSTALL", "", 0L)
        // Epoch 0 in UTC is 1970-01-01T00:00:00 (local tz may shift the
        // clock part, but the ISO shape with date + 'T' + time holds).
        assertTrue(Regex("\\[\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\] INSTALL").containsMatchIn(line))
    }

    // ── logEvent append + truncation ───────────────────────────────────

    @Test
    fun logEvent_appendsOneLinePerCall() {
        val dir = tmp.newFolder("logs")
        RootfsEventLog.logEvent(dir, "INSTALL", "gen=1", atMs = 1000L)
        RootfsEventLog.logEvent(dir, "MANUAL_RESET", "", atMs = 2000L)
        val lines = File(dir, "rootfs-events.log").readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("INSTALL gen=1"))
        assertTrue(lines[1].endsWith("MANUAL_RESET"))
    }

    @Test
    fun logEvent_truncatesWhenOverCap_keepingTail() {
        val dir = tmp.newFolder("logs2")
        // 129 KB of 'x' pushes past the 128 KB cap in one shot.
        val huge = "x".repeat(129 * 1024)
        RootfsEventLog.logEvent(dir, "INSTALL", huge, atMs = 1L)
        RootfsEventLog.logEvent(dir, "MANUAL_RESET", "after-truncate", atMs = 2L)
        val file = File(dir, "rootfs-events.log")
        val text = file.readText()
        assertTrue(text.length < 129 * 1024)
        // The most recent event must have survived truncation.
        assertTrue(text.contains("MANUAL_RESET after-truncate"))
    }

    @Test
    fun logEvent_brokenDestination_neverThrows() {
        // A *file* where the logs directory should be — mkdirs() fails,
        // every write path throws; the contract is to swallow all of it.
        val notADir = tmp.newFile("blocker")
        RootfsEventLog.logEvent(notADir, "INSTALL", "should not throw")
        RootfsEventLog.writeBootId(notADir, 7L)
        assertEquals(0L, RootfsEventLog.readBootId(notADir))
    }

    // ── boot id generation counter ─────────────────────────────────────

    @Test
    fun bootId_roundTrips_andDefaultsToZero() {
        val rootfs = tmp.newFolder("rootfs")
        assertEquals(0L, RootfsEventLog.readBootId(rootfs))
        RootfsEventLog.writeBootId(rootfs, 42L)
        assertEquals(42L, RootfsEventLog.readBootId(rootfs))
        // Garbage content must not throw — falls back to 0.
        File(rootfs, "rootfs-boot-id").writeText("not-a-number")
        assertEquals(0L, RootfsEventLog.readBootId(rootfs))
    }
}
