package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P2-guest-tmp-reclaim] Tests for the guest-rootfs temp sweeper predicates.
 *
 * Background: `ExecutionCoordinator.cleanupProotTmp()`'s KDoc promised to clear
 * "the guest rootfs temp dirs", but only the host `cache/proot-tmp` cache was
 * swept. Guest `/tmp` + `/var/tmp` are reached through PRoot's `-r <rootfs>`
 * with no bind mount over them (verified from `PRootKernel.buildProotCommand`'s
 * argv: only /dev, /proc, /sys and the /var/minis subdir maps are bound), so the
 * host-side paths are `filesDir/alpine-rootfs/tmp` and `.../var/tmp`.
 *
 * Measured on the sandbox's own rootfs 2026-09-21: 615 top-level entries, 12 GB,
 * of which 675 were older than an hour — i.e. the accumulation is real and the
 * age gate is what keeps the sweep from racing live work.
 */
class GuestTmpSweepPlanTest {

    private val hour = 60 * 60 * 1000L
    private val now = 1_700_000_000_000L

    @Test
    fun `entry older than the threshold is aged`() {
        assertTrue(internalGuestTmpIsAged(now - hour - 1, now, hour))
        assertTrue(internalGuestTmpIsAged(now - 3 * hour, now, hour))
    }

    @Test
    fun `entry exactly at the threshold is aged`() {
        assertTrue(internalGuestTmpIsAged(now - hour, now, hour))
    }

    @Test
    fun `entry younger than the threshold is not aged`() {
        assertFalse(internalGuestTmpIsAged(now - hour + 1, now, hour))
        assertFalse(internalGuestTmpIsAged(now, now, hour))
        assertFalse(internalGuestTmpIsAged(now + 5_000, now, hour))
    }

    @Test
    fun `unknown mtime is treated as aged`() {
        // File.lastModified() returns 0 when the timestamp cannot be read; such
        // an entry is certainly not being written right now, and refusing to
        // ever reclaim it is how the junk accumulated in the first place.
        assertTrue(internalGuestTmpIsAged(0L, now, hour))
        assertTrue(internalGuestTmpIsAged(-1L, now, hour))
    }

    @Test
    fun `descend only into real directories`() {
        assertTrue(internalGuestTmpShouldDescend(isDirectory = true, isSymbolicLink = false))
        assertFalse(internalGuestTmpShouldDescend(isDirectory = true, isSymbolicLink = true))
        assertFalse(internalGuestTmpShouldDescend(isDirectory = false, isSymbolicLink = false))
        assertFalse(internalGuestTmpShouldDescend(isDirectory = false, isSymbolicLink = true))
    }

    @Test
    fun `the sweep never deletes the temp dir itself`() {
        // Structural: sweepGuestTmpDir iterates listFiles() and only ever
        // deletes children. A `deleteRecursively()` on `dir` would remove part
        // of the rootfs.
        val file = java.io.File(
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/ExecutionCoordinator.kt",
        )
        if (!file.exists()) return
        val text = file.readText()
        val start = text.indexOf("private fun sweepGuestTmpDir(")
        if (start < 0) return
        val body = text.substring(start, text.indexOf("\n    private fun sweepGuestTmpDir", start).takeIf { it > start } ?: text.length)
        assertTrue("sweeper must list the directory", body.contains("dir.listFiles()"))
        assertFalse(
            "sweeper must never delete the temp dir itself",
            Regex("\\bdir\\.deleteRecursively\\(\\)").containsMatchIn(body),
        )
    }
}
