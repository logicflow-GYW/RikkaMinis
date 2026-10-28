package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [meta1028-B04 F-11] The two gaps that survive in the tmpfile ledger.
 *
 * 1. **the write can fail.** `tmpHost.writeText(...)` ran BEFORE
 *    `tmpLedger.rotate(tmpHost)`, so a throwing write (ENOSPC on the rootfs
 *    tmpfs, an interrupted worker) left the file on disk with nobody tracking
 *    it — the exact leak the ledger was written to close, reached through the
 *    one path it never covered. [writeResponseFile] registers BEFORE writing
 *    and reclaims on failure, so the file is owned at every instant.
 *
 * 2. **the process can die.** The ring lives in memory, so a silent kill / OOM
 *    / debug crash leaves every `.native-offload-*` file in the rootfs /tmp —
 *    which is a tmpfs, i.e. RAM the UI process does not get back — with
 *    nothing left to reclaim it. [sweepStaleTmpFiles] closes that across the
 *    process boundary at start().
 *
 * Plus the shared-budget bug behind both: the ledger was sized at a fixed 4
 * while the worker pool is 1..16 ([ConcurrencyPrefs]), so at 16 workers a
 * reply was evicted after TWO newer requests while fourteen were still in
 * flight and un-cat'd — the "deleted a file the guest had not read yet"
 * failure the ledger exists to prevent.
 *
 * Everything here is pure JVM (injected write/delete/list functions), so the
 * failure paths are genuinely exercised instead of reasoned about.
 */
class OffloadTmpLedgerClosureTest {

    private fun fileOf(n: Int) = File("/tmp/fake/${OFFLOAD_TMP_PREFIX}1-$n")

    companion object {
        private const val NATIVE_REL = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/NativeOffload.kt"
    }

    // ── 1: a failed write must not leak ─────────────────────────────────────

    @Test
    fun `a failed write reclaims the file instead of leaking it`() {
        val deleted = Collections.synchronizedList(mutableListOf<File>())
        val ledger = OffloadTmpFileLedger(capacity = 4, deleteFn = { deleted.add(it); true })
        val target = fileOf(1)

        val result = writeResponseFile(target, "hello", ledger) { _, _ -> throw IOException("ENOSPC") }

        assertTrue("a failed write must be reported as a failure", result.isFailure)
        assertTrue("the cause must survive", result.exceptionOrNull() is IOException)
        assertEquals("…and the partial file must be deleted", listOf(target), deleted)
        assertEquals("…and dropped from the ledger", 0, ledger.size)
    }

    @Test
    fun `a successful write keeps exactly one tracked file`() {
        val deleted = mutableListOf<File>()
        val ledger = OffloadTmpFileLedger(capacity = 4, deleteFn = { deleted.add(it); true })
        val written = mutableListOf<String>()

        val result = writeResponseFile(fileOf(1), "hello", ledger) { f, s -> written.add("${f.name}=$s") }

        assertTrue(result.isSuccess)
        assertEquals("the byte count is what the caller reports", 5, result.getOrNull())
        assertEquals(listOf("${OFFLOAD_TMP_PREFIX}1-1=hello"), written)
        assertEquals(1, ledger.size)
        assertEquals(fileOf(1), ledger.newest)
        assertEquals("nothing was deleted", emptyList<File>(), deleted)
    }

    @Test
    fun `a failed write does not delete a file another thread already evicted`() {
        // register() then reclaim() races with an eviction: reclaim must be a
        // no-op when the file is no longer tracked, or it deletes a file that
        // is about to be handed to somebody else's reply.
        val deleted = Collections.synchronizedList(mutableListOf<File>())
        val ledger = OffloadTmpFileLedger(capacity = 1, deleteFn = { deleted.add(it); true })
        val target = fileOf(1)

        val result = writeResponseFile(target, "hello", ledger) { _, _ ->
            // Simulate the eviction happening between register and write.
            ledger.reclaim(target)
            throw IOException("ENOSPC")
        }

        assertTrue(result.isFailure)
        assertEquals("deleted exactly once", 1, deleted.count { it == target })
    }

    @Test
    fun `deliverResponseFile returns no path when the write fails`() {
        val ledger = OffloadTmpFileLedger(capacity = 4)
        val dir = File("/tmp/fake-rootfs/tmp")

        val r = deliverResponseFile("out", dir, pid = 42, seq = 7, ledger) { _, _ -> throw IOException("ENOSPC") }

        assertTrue("the caller must be able to see the failure", r.isFailure)
        assertNull("…and must not get a path it would hand the guest", r.getOrNull()?.guestPath)
        assertEquals("nothing stays tracked", 0, ledger.size)
    }

    @Test
    fun `deliverResponseFile names the file the guest will cat`() {
        val ledger = OffloadTmpFileLedger(capacity = 4)
        val dir = File("/tmp/fake-rootfs/tmp")
        val seq = 7L

        val r = deliverResponseFile("out", dir, pid = 42, seq = seq, ledger) { _, _ -> }

        assertTrue(r.isSuccess)
        val d = r.getOrNull()!!
        assertEquals("the guest path is what the reply carries", "/tmp/${OFFLOAD_TMP_PREFIX}42-7", d.guestPath)
        assertTrue("the host path is the real file", d.hostPath.endsWith("${OFFLOAD_TMP_PREFIX}42-7"))
        assertEquals("and it is tracked", 1, ledger.size)
    }

    // ── 2: the process boundary ─────────────────────────────────────────────

    @Test
    fun `the boot sweep reclaims orphaned files from a dead process`() {
        val deleted = mutableListOf<String>()
        val entries = arrayOf(
            "${OFFLOAD_TMP_PREFIX}1-1", "${OFFLOAD_TMP_PREFIX}1-2", "${OFFLOAD_TMP_PREFIX}1-3",
            "guest-own-file", "minis-something-else", "${OFFLOAD_TMP_PREFIX}x",
        )

        val swept = sweepStaleTmpFiles(
            File("/tmp/fake-rootfs/tmp"),
            deleteFn = { deleted.add(it.name); true },
            names = { entries },
        )

        assertEquals("four orphans reclaimed", 4, swept)
        assertEquals(
            "only OUR prefix is touched — the guest's /tmp content is never collateral",
            listOf(
                "${OFFLOAD_TMP_PREFIX}1-1", "${OFFLOAD_TMP_PREFIX}1-2",
                "${OFFLOAD_TMP_PREFIX}1-3", "${OFFLOAD_TMP_PREFIX}x",
            ),
            deleted,
        )
    }

    @Test
    fun `an unlistable directory sweeps nothing and does not throw`() {
        assertEquals(0, sweepStaleTmpFiles(File("/tmp/gone"), names = { null }))
    }

    @Test
    fun `a delete that fails is not counted but does not stop the sweep`() {
        val entries = arrayOf("${OFFLOAD_TMP_PREFIX}a", "${OFFLOAD_TMP_PREFIX}b")
        val swept = sweepStaleTmpFiles(
            File("/tmp/fake-rootfs/tmp"),
            deleteFn = { false },
            names = { entries },
        )
        assertEquals("a refused delete is not a reclaimed file", 0, swept)
    }

    // ── the capacity gap ────────────────────────────────────────────────────

    @Test
    fun `the ledger is sized from the worker count, not a fixed four`() {
        // 16 concurrent workers at capacity 4: `2n+2` keeps every in-flight
        // reply inside the ring.
        assertEquals(6, OffloadTmpFileLedger.capacityFor(2))
        assertEquals(10, OffloadTmpFileLedger.capacityFor(4))
        assertEquals(34, OffloadTmpFileLedger.capacityFor(16))
        // Degenerate inputs must not take the server down.
        assertEquals(6, OffloadTmpFileLedger.capacityFor(1))
        assertEquals(6, OffloadTmpFileLedger.capacityFor(0))
        assertEquals(6, OffloadTmpFileLedger.capacityFor(-3))
    }

    @Test
    fun `at sixteen workers a reply survives n full cycles`() {
        // The regression, as a NEGATIVE control first: at 16 workers the old
        // fixed capacity 4 evicts a reply after TWO newer requests even though
        // fourteen replies are still in flight and un-cat'd — the ledger
        // deletes the file the guest is about to read.
        val workers = 16
        val evicted = AtomicInteger()
        val old = OffloadTmpFileLedger(capacity = 4, deleteFn = { evicted.incrementAndGet(); true })
        val mine = fileOf(0)
        old.rotate(mine)
        repeat(workers - 1) { i -> old.rotate(fileOf(i + 1)) }
        assertFalse(
            "capacity 4 must NOT retain an in-flight reply at 16 workers (that is the defect)",
            old.discard(mine),
        )
        assertTrue("…because it was evicted and deleted", evicted.get() > 0)

        // The sized ledger keeps it across the same window: 2n+2 = 34.
        val sized = OffloadTmpFileLedger(
            capacity = OffloadTmpFileLedger.capacityFor(workers),
            deleteFn = { evicted.incrementAndGet(); true },
        )
        sized.rotate(mine)
        repeat(workers) { i -> sized.rotate(fileOf(100 + i)) }
        assertTrue("the sized ledger keeps it through every in-flight request", sized.discard(mine))
        // mine was removed by discard(); the 16 newer replies are all retained.
        assertEquals(16, sized.size)
    }

    @Test
    fun `resize keeps what still fits and deletes only the overflow`() {
        val deleted = Collections.synchronizedList(mutableListOf<Int>())
        val ledger = OffloadTmpFileLedger(capacity = 6, deleteFn = { deleted.add(it.name.substringAfterLast('-').toInt()); true })
        (1..6).forEach { ledger.rotate(fileOf(it)) }

        ledger.resize(2)

        assertEquals("only the four oldest overflow", listOf(1, 2, 3, 4), deleted.sorted())
        assertEquals(2, ledger.size)
        assertEquals(fileOf(6), ledger.newest)
    }

    @Test
    fun `resize grows without deleting anything`() {
        val deleted = mutableListOf<Int>()
        val ledger = OffloadTmpFileLedger(capacity = 2, deleteFn = { deleted.add(0); true })
        ledger.rotate(fileOf(1))
        ledger.rotate(fileOf(2))
        ledger.resize(8)
        assertEquals("growth must not delete", emptyList<Int>(), deleted)
        assertEquals(2, ledger.size)
    }

    @Test
    fun `capacity below one is refused`() {
        var threw = false
        try { OffloadTmpFileLedger(capacity = 0) } catch (_: IllegalArgumentException) { threw = true }
        assertTrue(threw)
        var threwResize = false
        try { OffloadTmpFileLedger(capacity = 4).resize(0) } catch (_: IllegalArgumentException) { threwResize = true }
        assertTrue("resize must refuse too", threwResize)
    }

    // ── the invariants the ledger already had, re-run under the new API ─────

    @Test
    fun `concurrent rotations still never leak or double-delete`() {
        val threads = 8
        val rotationsPerThread = 200
        val capacity = 4
        val deleted = AtomicInteger()
        val ledger = OffloadTmpFileLedger(capacity = capacity, deleteFn = { deleted.incrementAndGet(); true })

        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(threads) { t ->
            pool.submit {
                start.await()
                repeat(rotationsPerThread) { i ->
                    ledger.rotate(fileOf(t * 1000 + i))
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        val total = threads * rotationsPerThread
        assertEquals(total - ledger.size, deleted.get())
        assertTrue(ledger.size <= capacity)
    }

    @Test
    fun `clearAll still empties the ledger exactly once`() {
        val deletions = Collections.synchronizedList(mutableListOf<Int>())
        val ledger = OffloadTmpFileLedger(capacity = 4, deleteFn = { deletions.add(it.name.substringAfterLast('-').toInt()); true })
        (1..3).forEach { ledger.rotate(fileOf(it)) }
        ledger.clearAll()
        ledger.clearAll()
        assertEquals(listOf(1, 2, 3), deletions.sorted())
        assertEquals(0, ledger.size)
    }

    // ── wiring: start() must actually close both gaps ───────────────────────

    private fun nativeOffloadSource(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, NATIVE_REL)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$NATIVE_REL not found from user.dir=${System.getProperty("user.dir")}")
    }

    @Test
    fun `start sizes the ledger from the worker pool and sweeps the orphans`() {
        val src = nativeOffloadSource()
        assertTrue("the worker count must be read once", src.contains("val workers = com.rikkaminis.app.data.ConcurrencyPrefs.maxConcurrentSessions()"))
        assertTrue("…and the ledger resized from it", src.contains("tmpLedger.resize(OffloadTmpFileLedger.capacityFor(workers))"))
        assertTrue("…and the orphans of the previous process swept", src.contains("sweepStaleTmpFiles(dir)"))
        // The sweep must run BEFORE any reply can be written, i.e. in start().
        val startIdx = src.indexOf("fun start(rootfsDir: File)")
        val bootIdx = src.indexOf("fun handleClient(")
        assertTrue("start must exist", startIdx >= 0)
        assertTrue("handleClient must exist", bootIdx >= 0)
        assertTrue("the sweep belongs to start, before the first request", src.indexOf("sweepStaleTmpFiles(dir)") < bootIdx)
    }

    @Test
    fun `the reply path goes through the accounting writer`() {
        val src = nativeOffloadSource()
        assertTrue("handleClient must use deliverResponseFile", src.contains("deliverResponseFile("))
        assertTrue("…and branch on its failure", src.contains("getOrElse"))
        assertTrue("a delivery failure is logged with its cause", src.contains("could not write the response tmpfile"))
        // The leak itself: a bare writeText that no ledger call follows.
        assertFalse("tmpHost.writeText must be gone", src.contains("tmpHost.writeText"))
    }
}
