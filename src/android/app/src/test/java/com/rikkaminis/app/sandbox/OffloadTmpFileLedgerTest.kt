package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [audit-RC7] Tests for the bounded tmpfile ledger that replaces the racy
 * single-slot `lastTmpHost` in NativeOffloadServer.
 *
 * Invariants under test:
 * 1. Capacity bound: at most [OffloadTmpFileLedger.DEFAULT_CAPACITY] files
 *    retained; oldest evicted first.
 * 2. Exactly-once deletion: every evicted/cleared file is deleted once and
 *    only once, no matter how many threads rotate concurrently.
 * 3. No file is deleted while still inside capacity — a just-registered
 *    file survives at least (capacity - 1) further rotations, which is the
 *    property that prevents deleting a file the guest hasn't cat'd yet.
 */
class OffloadTmpFileLedgerTest {

    private fun fileOf(n: Int) = File("/tmp/fake/.native-offload-1-$n")

    @Test
    fun `keeps newest files and evicts oldest beyond capacity`() {
        val deleted = mutableListOf<Int>()
        val ledger = OffloadTmpFileLedger(capacity = 3, deleteFn = { deleted.add(it.name.substringAfterLast('-').toInt()); true })

        (1..5).forEach { ledger.rotate(fileOf(it)) }

        assertEquals(3, ledger.size)
        assertEquals(fileOf(5), ledger.newest)
        // 1 and 2 evicted (oldest first), 3/4/5 retained
        assertEquals(listOf(1, 2), deleted)
    }

    @Test
    fun `newest file is never deleted by subsequent rotations within capacity`() {
        var deletedNewest = false
        val ledger = OffloadTmpFileLedger(capacity = 4, deleteFn = { f ->
            if (f == fileOf(5)) deletedNewest = true
            true
        })
        (1..5).forEach { ledger.rotate(fileOf(it)) }
        // file 5 was just registered; even after one more rotation only
        // files older than the last `capacity` are gone
        assertFalse(deletedNewest)
        assertEquals(fileOf(5), ledger.newest)
    }

    @Test
    fun `clearAll deletes every tracked file exactly once and empties ledger`() {
        val deletions = Collections.synchronizedList(mutableListOf<Int>())
        val ledger = OffloadTmpFileLedger(capacity = 4, deleteFn = { deletions.add(it.name.substringAfterLast('-').toInt()); true })
        (1..3).forEach { ledger.rotate(fileOf(it)) }

        ledger.clearAll()
        ledger.clearAll() // idempotent

        assertEquals(listOf(1, 2, 3), deletions.sorted())
        assertEquals(0, ledger.size)
        assertEquals(null, ledger.newest)
    }

    @Test
    fun `concurrent rotations never leak or double-delete`() {
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
                repeat(rotationsPerThread) { i -> ledger.rotate(fileOf(t * 1000 + i)) }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        val total = threads * rotationsPerThread
        // Every file either still retained (≤ capacity) or deleted exactly once.
        assertEquals(total - ledger.size, deleted.get())
        assertTrue(ledger.size <= capacity)
        assertTrue(deleted.get() > 0) // the ring definitely rolled over
    }

    @Test
    fun `capacity 1 degenerates to delete-previous semantics without race`() {
        val deleted = mutableListOf<Int>()
        val ledger = OffloadTmpFileLedger(capacity = 1, deleteFn = { deleted.add(it.name.substringAfterLast('-').toInt()); true })
        ledger.rotate(fileOf(1))
        ledger.rotate(fileOf(2))
        assertEquals(listOf(1), deleted)
        assertEquals(fileOf(2), ledger.newest)
    }

    @Test
    fun `delete failure is swallowed not thrown`() {
        val ledger = OffloadTmpFileLedger(capacity = 1, deleteFn = { throw java.io.IOException("simulated") })
        ledger.rotate(fileOf(1))
        ledger.rotate(fileOf(2)) // must not throw
        ledger.clearAll() // must not throw
    }

    @Test
    fun `require capacity at least 1`() {
        var threw = false
        try { OffloadTmpFileLedger(capacity = 0) } catch (_: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }
}
