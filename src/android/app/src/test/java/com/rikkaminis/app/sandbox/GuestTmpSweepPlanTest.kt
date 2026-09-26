package com.rikkaminis.app.sandbox

import java.io.File
import java.nio.file.Files
import org.junit.After
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

    // ---- [guest-tmp-activity-probe] / [guest-tmp-keep-marker] ----
    //
    // Fixtures are real directories: the predicates under test take a File and
    // read mtimes, so a fake would only re-test the fake.

    private val probeTrees = mutableListOf<File>()

    @After
    fun removeProbeTrees() {
        probeTrees.forEach { runCatching { it.deleteRecursively() } }
    }

    /** Fresh tree under the JVM temp dir; removed by [removeProbeTrees]. */
    private fun tree(): File =
        Files.createTempDirectory("guest-tmp-probe").toFile().also { probeTrees.add(it) }

    private fun write(path: File, mtime: Long) {
        path.parentFile?.mkdirs()
        path.writeText("x")
        assertTrue("could not set mtime on ${path.name}", path.setLastModified(mtime))
    }

    /**
     * Stamp every directory *after* its children exist: creating a child moves
     * its parent's mtime, so a fixture that wants "stale shell, live content"
     * has to age the directories last.
     */
    private fun ageDirectories(root: File, mtime: Long) {
        root.walkBottomUp().filter { it.isDirectory }.forEach { it.setLastModified(mtime) }
    }

    private fun probe(root: File): Long =
        internalGuestTmpNewestActivityMs(root, GUEST_TMP_ACTIVITY_MAX_DEPTH, GUEST_TMP_ACTIVITY_MAX_ENTRIES)

    @Test
    fun `liveness boundary mirrors the age boundary`() {
        assertTrue(internalGuestTmpLooksLive(now - hour + 1, now, hour))
        assertFalse(
            "an mtime exactly at the threshold is aged, so it cannot also be live",
            internalGuestTmpLooksLive(now - hour, now, hour),
        )
        assertFalse(internalGuestTmpLooksLive(now - 3 * hour, now, hour))
        assertFalse("unreadable mtime", internalGuestTmpLooksLive(0L, now, hour))
        assertFalse(internalGuestTmpLooksLive(-1L, now, hour))
    }

    @Test
    fun `a write one level inside keeps an aged entry`() {
        val root = tree()
        write(File(root, "work.log"), now)
        ageDirectories(root, now - 3 * hour)
        assertTrue("the entry itself is aged", internalGuestTmpIsAged(root.lastModified(), now, hour))
        assertTrue("but something wrote inside it", internalGuestTmpLooksLive(probe(root), now, hour))
    }

    @Test
    fun `a checkout-shaped tree stays alive`() {
        // `.git/index` is rewritten by every git command that touches the index,
        // and it sits two levels down. If the production depth budget cannot
        // reach it, a live checkout looks exactly like abandoned junk.
        val root = tree()
        write(File(root, "src/app.kt"), now - 3 * hour)
        write(File(root, ".git/index"), now)
        ageDirectories(root, now - 3 * hour)
        assertTrue(internalGuestTmpLooksLive(probe(root), now, hour))
    }

    @Test
    fun `the probe reaches the documented depth and no further`() {
        val reachable = tree()
        write(File(reachable, "a/b/c/file"), now)
        ageDirectories(reachable, now - 3 * hour)
        assertTrue(
            "four levels below the entry are still seen",
            internalGuestTmpLooksLive(probe(reachable), now, hour),
        )

        val beyond = tree()
        write(File(beyond, "a/b/c/d/file"), now)
        ageDirectories(beyond, now - 3 * hour)
        assertFalse(
            "five levels down is past the budget, so the entry looks abandoned again",
            internalGuestTmpLooksLive(probe(beyond), now, hour),
        )
        assertTrue(
            "…and one more level of budget would have caught it",
            internalGuestTmpLooksLive(
                internalGuestTmpNewestActivityMs(
                    beyond,
                    GUEST_TMP_ACTIVITY_MAX_DEPTH + 1,
                    GUEST_TMP_ACTIVITY_MAX_ENTRIES,
                ),
                now,
                hour,
            ),
        )
    }

    @Test
    fun `a quiet aged tree is still reclaimable`() {
        val root = tree()
        write(File(root, "sub/leftover.bin"), now - 5 * hour)
        ageDirectories(root, now - 5 * hour)
        assertFalse(internalGuestTmpLooksLive(probe(root), now, hour))
    }

    @Test
    fun `an empty aged directory is reclaimable`() {
        val root = tree()
        ageDirectories(root, now - 5 * hour)
        assertEquals(0L, probe(root))
        assertFalse(internalGuestTmpLooksLive(probe(root), now, hour))
    }

    @Test
    fun `the entry cap is respected`() {
        val root = tree()
        write(File(root, "hot.txt"), now)
        ageDirectories(root, now - 3 * hour)
        assertEquals("a zero cap opens nothing", 0L, internalGuestTmpNewestActivityMs(root, 3, 0))
        assertTrue(internalGuestTmpLooksLive(internalGuestTmpNewestActivityMs(root, 3, 1), now, hour))
    }

    @Test
    fun `the pin marker is honoured at the top level only`() {
        val pinned = tree()
        write(File(pinned, ".minis-keep"), now - 5 * hour)
        ageDirectories(pinned, now - 5 * hour)
        assertTrue(internalGuestTmpIsPinned(pinned))

        val nested = tree()
        write(File(nested, "inner/.minis-keep"), now - 5 * hour)
        ageDirectories(nested, now - 5 * hour)
        assertFalse("a marker inside a child does not pin the entry", internalGuestTmpIsPinned(nested))

        write(File(pinned, "work.log"), now)
        assertFalse("a regular file cannot carry a marker", internalGuestTmpIsPinned(File(pinned, "work.log")))
    }

    @Test
    fun `an aged pinned tree survives on the pin alone`() {
        // Both halves of the sweeper's decision on one fixture: this entry is
        // aged and shows no recent write, so only the marker saves it.
        val root = tree()
        write(File(root, "scratch.bin"), now - 5 * hour)
        write(File(root, ".minis-keep"), now - 5 * hour)
        ageDirectories(root, now - 5 * hour)
        assertFalse(internalGuestTmpLooksLive(probe(root), now, hour))
        assertTrue(internalGuestTmpIsPinned(root))
    }
}
