package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.RandomAccessFile
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * `minis-fastio find` / `grep` walk engines ([T-minis-fastio] phase 2).
 *
 * Expectations are hard literals derived from the fixtures (sizes read back
 * from the fixture itself where the platform decides them), never from the
 * constants under test — a changed default must turn these red rather than
 * silently follow along.
 */
class FastioSearchTest {

    /** root/{a.txt, b.log, sub/{c.log, d.txt}} — 4 files, 2 dirs. */
    private fun findFixture(): Path {
        val root = Files.createTempDirectory("fastio-find")
        Files.write(root.resolve("a.txt"), "alpha\n".toByteArray())
        Files.write(root.resolve("b.log"), "beta\n".toByteArray())
        val sub = Files.createDirectory(root.resolve("sub"))
        Files.write(sub.resolve("c.log"), "gamma\n".toByteArray())
        Files.write(sub.resolve("d.txt"), "delta\n".toByteArray())
        return root
    }

    @Test
    fun `find counts the whole tree and reconstructs guest paths`() {
        val root = findFixture()
        val out = FindOutcome()

        findTree(root, "/ws", matcher = null, typeFilter = null, limit = 100, maxEntries = 1_000, out = out)

        assertEquals(4L, out.files)
        assertEquals(2L, out.dirs)
        assertEquals(6L, out.matched)
        assertEquals(
            listOf("/ws", "/ws/a.txt", "/ws/b.log", "/ws/sub", "/ws/sub/c.log", "/ws/sub/d.txt"),
            out.matches.map { it.guestPath }.sorted(),
        )
        assertFalse(out.matchesCapped)
        assertFalse(out.truncated)
        assertTrue(out.errors.isEmpty())
    }

    @Test
    fun `find -name matches the entry name only`() {
        val root = findFixture()
        val matcher = FileSystems.getDefault().getPathMatcher("glob:*.log")
        val out = FindOutcome()

        findTree(root, "/ws", matcher, null, limit = 100, maxEntries = 1_000, out = out)

        assertEquals(listOf("/ws/b.log", "/ws/sub/c.log"), out.matches.map { it.guestPath }.sorted())
        assertEquals(2L, out.matched)
    }

    @Test
    fun `find -type filters by entry type`() {
        val root = findFixture()
        val out = FindOutcome()

        findTree(root, "/ws", null, typeFilter = 'd', limit = 100, maxEntries = 1_000, out = out)

        assertEquals(listOf("/ws", "/ws/sub"), out.matches.map { it.guestPath }.sorted())
        assertEquals(2L, out.matched)
    }

    @Test
    fun `find caps the listing but keeps the totals over the whole tree`() {
        val root = findFixture()
        val out = FindOutcome()

        findTree(root, "/ws", null, null, limit = 2, maxEntries = 1_000, out = out)

        assertEquals(2, out.matches.size)
        assertTrue(out.matchesCapped)
        assertEquals(6L, out.matched)   // authoritative even though only 2 were listed
    }

    @Test
    fun `find reports the traversal budget as truncation`() {
        val root = findFixture()
        val out = FindOutcome()

        findTree(root, "/ws", null, null, limit = 100, maxEntries = 1, out = out)

        assertTrue(out.truncated)
        assertEquals(1L, out.dirs)   // the root is charged before the budget stops the walk
        assertEquals(0L, out.files)
    }

    @Test
    fun `find reports a symlink as type l and never follows it`() {
        val root = Files.createTempDirectory("fastio-find-link")
        val outside = Files.createTempDirectory("fastio-find-outside")
        Files.write(outside.resolve("victim.txt"), "v".toByteArray())
        Files.createSymbolicLink(root.resolve("link"), outside)
        val out = FindOutcome()

        findTree(root, "/ws", null, typeFilter = 'l', limit = 100, maxEntries = 1_000, out = out)

        assertEquals(listOf("/ws/link"), out.matches.map { it.guestPath }.sorted())
        assertEquals(1L, out.files)   // counters are filter-independent: the link is a file entry
    }

    // ── grep ────────────────────────────────────────────────────────────────

    /** root/{a.txt, b.txt, bin.dat, link.txt, sub/c.txt} */
    private fun grepFixture(): Path {
        val root = Files.createTempDirectory("fastio-grep")
        Files.write(root.resolve("a.txt"), "one\ntwo\nthree\n".toByteArray())
        Files.write(root.resolve("b.txt"), "nothing here\n".toByteArray())
        val sub = Files.createDirectory(root.resolve("sub"))
        Files.write(sub.resolve("c.txt"), "two again\n".toByteArray())
        Files.write(root.resolve("bin.dat"), byteArrayOf(0, 1, 2, 65, 66))
        Files.createSymbolicLink(root.resolve("link.txt"), root.resolve("a.txt"))
        return root
    }

    @Test
    fun `grep reports file, line number and text and counts the skips`() {
        val root = grepFixture()
        val out = GrepOutcome()

        grepTree(root, "/ws", Regex("two"), limit = 100, maxEntries = 1_000, out = out)

        assertEquals(
            listOf("/ws/a.txt:2", "/ws/sub/c.txt:1"),
            out.matches.map { "${it.guestPath}:${it.lineNumber}" }.sorted(),
        )
        assertEquals("two", out.matches.first { it.guestPath == "/ws/a.txt" }.text)
        assertEquals(3L, out.filesScanned)          // a.txt, b.txt, sub/c.txt
        assertEquals(1L, out.filesSkippedBinary)    // bin.dat (NUL in the first block)
        assertEquals(1L, out.filesSkippedLink)      // link.txt
        assertEquals(0L, out.filesSkippedLarge)
        assertFalse(out.matchesCapped)
        assertTrue(out.errors.isEmpty())
    }

    @Test
    fun `grep honours a case-insensitive pattern from the caller`() {
        val root = grepFixture()
        val out = GrepOutcome()

        grepTree(root, "/ws", Regex("TWO", RegexOption.IGNORE_CASE), limit = 100, maxEntries = 1_000, out = out)

        assertEquals(2, out.matches.size)
        assertEquals("two", out.matches.first { it.guestPath == "/ws/a.txt" }.text)
    }

    @Test
    fun `grep stops at the match budget and flags it`() {
        val root = Files.createTempDirectory("fastio-grep-cap")
        Files.write(root.resolve("many.txt"), "hit\nhit\nhit\nhit\nhit\n".toByteArray())
        val out = GrepOutcome()

        grepTree(root, "/ws", Regex("hit"), limit = 2, maxEntries = 1_000, out = out)

        assertEquals(2, out.matches.size)
        assertEquals(listOf(1, 2), out.matches.map { it.lineNumber })
        assertTrue(out.matchesCapped)
    }

    @Test
    fun `grep clips a long matching line to 200 chars plus the ellipsis`() {
        val root = Files.createTempDirectory("fastio-grep-long")
        Files.write(root.resolve("long.txt"), ("x".repeat(500) + "NEEDLE\n").toByteArray())
        val out = GrepOutcome()

        grepTree(root, "/ws", Regex("NEEDLE"), limit = 100, maxEntries = 1_000, out = out)

        assertEquals(1, out.matches.size)
        assertEquals(201, out.matches[0].text.length)
        assertTrue(out.matches[0].text.endsWith("…"))
    }

    @Test
    fun `grep skips files above the size ceiling without reading them`() {
        val root = Files.createTempDirectory("fastio-grep-big")
        val big = root.resolve("big.txt")
        RandomAccessFile(big.toFile(), "rw").use { it.setLength(8L * 1024 * 1024 + 1) }
        Files.write(root.resolve("small.txt"), "needle\n".toByteArray())
        val out = GrepOutcome()

        grepTree(root, "/ws", Regex("needle"), limit = 100, maxEntries = 1_000, out = out)

        assertEquals(1L, out.filesSkippedLarge)
        assertEquals(1L, out.filesScanned)
        assertEquals(listOf("/ws/small.txt"), out.matches.map { it.guestPath })
    }

    // ── [dv19-A1 A-03] the budget is spent per ROOT, not per tree ────────────
    //
    // `grep` (the handler) carries ONE match budget across several roots:
    // `remaining -= outcome.matches.size`, and the next root is walked with
    // whatever is left. A root that opens with `limit == 0` used to be walked
    // end to end — the walk only learned the budget was gone at the first
    // MATCHING line (grepFile:220), so a root of non-matching files was read in
    // full (up to 8 MiB per file) to find nothing. The invariant these cases
    // pin: once the budget is spent, no file is opened and no directory is
    // entered.

    @Test
    fun `a root reached with a spent budget is never scanned`() {
        val first = Files.createTempDirectory("fastio-grep-first")
        // Three hits for a budget of two: the third is what sets matchesCapped
        // ("exactly limit matches" is deliberately NOT capped — see the KDoc).
        Files.write(first.resolve("hits.txt"), "hit\nhit\nhit\n".toByteArray())
        val second = Files.createTempDirectory("fastio-grep-second")
        Files.write(second.resolve("a.txt"), "alpha\n".toByteArray())
        val sub = Files.createDirectory(second.resolve("sub"))
        Files.write(sub.resolve("b.txt"), "beta\n".toByteArray())

        val firstOut = GrepOutcome()
        grepTree(first, "/one", Regex("hit"), limit = 2, maxEntries = 1_000, out = firstOut)
        assertEquals(2, firstOut.matches.size)
        assertTrue(firstOut.matchesCapped)

        // Exactly what FastioOffloadHandler.grep does with `remaining`.
        val remaining = 2 - firstOut.matches.size
        assertEquals(0, remaining)
        val secondOut = GrepOutcome()
        grepTree(second, "/two", Regex("hit"), limit = remaining, maxEntries = 1_000, out = secondOut)

        assertTrue(secondOut.matches.isEmpty())
        assertTrue(secondOut.matchesCapped)
        assertEquals(0L, secondOut.filesScanned)
        assertEquals(0L, secondOut.bytesScanned)
        assertEquals(0L, secondOut.visited)
    }

    @Test
    fun `a zero budget lists nothing and opens no file`() {
        val root = grepFixture()
        val out = GrepOutcome()

        grepTree(root, "/ws", Regex("two"), limit = 0, maxEntries = 1_000, out = out)

        assertTrue(out.matches.isEmpty())
        assertTrue(out.matchesCapped)
        assertEquals(0L, out.filesScanned)
        assertEquals(0L, out.bytesScanned)
        assertEquals(0L, out.visited)
        assertFalse(out.truncated)
    }

    @Test
    fun `the file that fills the budget is the last entry charged`() {
        // [INCR-1113-A] ORDER-INDEPENDENT FIXTURE. The previous shape put the
        // budget-filling files AND a sibling subdirectory directly under the
        // root and assumed the walk reached `a.txt` before `sub`. `Files.walkFileTree`
        // enumerates a directory in the filesystem's own order (creation order
        // on tmpfs, HASHED on ext4), so on the CI runner `sub` sometimes came
        // first: c.txt was scanned (filesScanned=2), then a.txt filled the
        // budget — `expected:<1> but was:<2>`. Measured on GitHub run 3048
        // (`FastioSearchTest.kt:275`).
        //
        // Now the root holds ONLY a subdirectory and the two budget-filling
        // files live inside it: whatever order they are enumerated in, the
        // FIRST of them fills the budget and the second is never opened.
        val root = Files.createTempDirectory("fastio-grep-stop")
        val sub = Files.createDirectory(root.resolve("sub"))
        Files.write(sub.resolve("a.txt"), "hit\nhit\nhit\n".toByteArray())
        Files.write(sub.resolve("b.txt"), "hit\nhit\nhit\n".toByteArray())
        val out = GrepOutcome()

        grepTree(root, "/ws", Regex("hit"), limit = 2, maxEntries = 1_000, out = out)

        // Charged: the root, the subdirectory, and the one file that filled the
        // budget. The other file is never opened, and no further entry is
        // charged — that is the property this test owns.
        assertEquals(2, out.matches.size)
        assertTrue(out.matchesCapped)
        assertEquals(1L, out.filesScanned)
        assertEquals(3L, out.visited)
        assertFalse(out.truncated)
    }

    @Test
    fun `an already capped outcome never enters a directory`() {
        val root = grepFixture()
        val out = GrepOutcome()
        out.matchesCapped = true // as set by the file that filled the budget

        grepTree(root, "/ws", Regex("two"), limit = 100, maxEntries = 1_000, out = out)

        assertEquals(0L, out.visited)
        assertEquals(0L, out.filesScanned)
        assertFalse(out.truncated)
    }

    // ── [dv23-A6] §1043-A-07: special files are never opened ────────────────
    //
    // `visitFile` fires for EVERY non-directory entry, so a named pipe reaches
    // the engines like any other file. `Files.newInputStream` on a pipe with no
    // writer blocks in open(2) with no timeout (measured on this JVM: no return
    // in 4 s), and the offload pool is 2 threads — two such entries stall every
    // other tool call. `find` opens nothing, but it used to label the pipe `f`,
    // which the help text defines as "regular file".

    /**
     * Create a named pipe and return it, or null where `mkfifo` is not
     * available (a POSIX binary; the JVM has no FIFO API). Callers `Assume` on
     * it, so a platform without mkfifo skips instead of failing.
     */
    private fun mkfifo(dir: Path, name: String): Path? {
        val fifo = dir.resolve(name)
        return try {
            val ok = ProcessBuilder("mkfifo", fifo.toString())
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0
            if (ok && Files.exists(fifo, LinkOption.NOFOLLOW_LINKS)) fifo else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Run [block] on a daemon thread and fail if it has not returned within
     * [seconds]. A slow walk is allowed; blocking in `open(2)` is not — and a
     * plain call would hang the test JVM instead of failing the case.
     */
    private fun <T> completesWithin(seconds: Long, block: () -> T): T {
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r).also { it.isDaemon = true } }
        try {
            return pool.submit(block).get(seconds, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw AssertionError("the engine did not return within ${seconds}s — a special file was opened")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `find types a named pipe as o and never lists it under type f`() {
        val root = findFixture()
        val fifo = mkfifo(root, "pipe")
        Assume.assumeTrue("mkfifo is not available on this platform", fifo != null)

        val all = FindOutcome()
        findTree(root, "/ws", null, null, limit = 100, maxEntries = 1_000, out = all)

        val pipeHit = all.matches.single { it.guestPath == "/ws/pipe" }
        assertEquals("a pipe is not a regular file: 'o' is the type find must report", 'o', pipeHit.type)
        assertEquals("counts stay filter-independent: the pipe is still a file entry", 5L, all.files)
        assertEquals(0L, pipeHit.size)

        val regularOnly = FindOutcome()
        findTree(root, "/ws", null, typeFilter = 'f', limit = 100, maxEntries = 1_000, out = regularOnly)

        assertEquals(
            "`-type f` means REGULAR files (the help text's own words): the pipe must not appear",
            listOf("/ws/a.txt", "/ws/b.log", "/ws/sub/c.log", "/ws/sub/d.txt"),
            regularOnly.matches.map { it.guestPath }.sorted(),
        )
    }

    @Test
    fun `grep skips a named pipe instead of blocking on it`() {
        val root = Files.createTempDirectory("fastio-grep-fifo")
        Files.write(root.resolve("a.txt"), "needle\n".toByteArray())
        val fifo = mkfifo(root, "pipe")
        Assume.assumeTrue("mkfifo is not available on this platform", fifo != null)
        val out = GrepOutcome()

        completesWithin(30) {
            grepTree(root, "/ws", Regex("needle"), limit = 100, maxEntries = 1_000, out = out)
        }

        assertEquals(listOf("/ws/a.txt"), out.matches.map { it.guestPath })
        assertEquals(1L, out.filesSkippedSpecial)
        assertEquals(1L, out.filesScanned)      // only the regular file
        assertEquals(7L, out.bytesScanned)      // "needle\n"
        assertEquals(0L, out.filesSkippedLink)
        assertFalse(out.matchesCapped)
        assertTrue("a skipped pipe is not an error, it is reported", out.errors.isEmpty())
    }
}
