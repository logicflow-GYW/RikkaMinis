package com.rikkaminis.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [R5-A3 F-07] JVM tests for the two halves of the staged-share race fix.
 *
 * The defect: the keep set of `SharedShareStore.cleanSharedFiles` was "the
 * pending share's references", but the pending share is only written at
 * hand-off — after every attachment has been copied and after the
 * provider-import dialog was answered. Everything a producer staged inside that
 * window looked exactly like an orphan of an earlier share, so a cleanup
 * triggered by the PREVIOUS share being consumed deleted the CURRENT share's
 * bytes.
 *
 * The fix has a decision (pure, tested here) and a registry (stateful,
 * process-lifetime, tested here). The wiring — "claim before creating",
 * "list the directory before snapshotting", "release only at hand-off/discard"
 * — needs a Context and is shape-asserted in [ShareStagedRaceWiringTest].
 *
 * Expectations are literals on purpose: deriving them from the production sets
 * would let a broken keep rule keep the suite green.
 */
class ShareStagedInFlightTest {

    // ─── the keep rule, table-driven ─────────────────────────────────────────

    @Test
    fun `keep rule - referenced or in flight survives, everything else goes`() {
        val referenced = setOf("shared-abc.bin")
        val inFlight = setOf("shared-copying.bin")
        val table = mapOf(
            "shared-abc.bin" to true,      // referenced by the pending share
            "shared-copying.bin" to true,  // a producer is still staging it
            "shared-old.bin" to false,     // orphan from the PREVIOUS share
            "shared-tmp.tmp" to false,     // nobody's file
        )
        table.forEach { (name, expected) ->
            assertEquals("keep decision for $name", expected, shouldKeepStagedFile(name, referenced, inFlight))
        }
    }

    @Test
    fun `keep rule - in flight alone is enough to survive`() {
        // The whole point of the fix: no pending-share reference exists yet.
        assertTrue(shouldKeepStagedFile("shared-new.bin", emptySet(), setOf("shared-new.bin")))
    }

    @Test
    fun `keep rule - referenced alone is enough to survive`() {
        // The pre-fix rule must keep working: hand-off files are referenced and
        // no longer in flight.
        assertTrue(shouldKeepStagedFile("shared-abc.bin", setOf("shared-abc.bin"), emptySet()))
    }

    @Test
    fun `keep rule - both sets empty deletes everything`() {
        assertFalse(shouldKeepStagedFile("shared-abc.bin", emptySet(), emptySet()))
        assertFalse(shouldKeepStagedFile("whatever", emptySet(), emptySet()))
    }

    @Test
    fun `keep rule - an inline text value never keeps a file`() {
        // INLINE_TEXT values are text, not filenames; only ATTACHMENT values may
        // enter either set. (The store filters by kind before building
        // `referenced`; this pins why a text value can't accidentally protect a
        // same-named file.)
        val text = "Look at https://example.com"
        assertFalse(shouldKeepStagedFile("shared-abc.bin", setOf(text), setOf(text)))
    }

    // ─── the registry ────────────────────────────────────────────────────────

    @Test
    fun `registry - a claimed name is protected until released`() {
        val name = "shared-claim-test-${System.nanoTime()}.bin"
        assertFalse(StagedInFlightFiles.snapshot().contains(name))

        StagedInFlightFiles.begin(name)
        assertTrue("claimed before the file exists", StagedInFlightFiles.snapshot().contains(name))
        assertTrue(shouldKeepStagedFile(name, emptySet(), StagedInFlightFiles.snapshot()))

        StagedInFlightFiles.end(name)
        assertFalse("released after hand-off / discard", StagedInFlightFiles.snapshot().contains(name))
        assertFalse(shouldKeepStagedFile(name, emptySet(), StagedInFlightFiles.snapshot()))
    }

    @Test
    fun `registry - endAll releases a handed-off set in one call`() {
        val a = "shared-batch-a-${System.nanoTime()}.bin"
        val b = "shared-batch-b-${System.nanoTime()}.bin"
        StagedInFlightFiles.begin(a)
        StagedInFlightFiles.begin(b)

        StagedInFlightFiles.endAll(listOf(a, b))

        val snap = StagedInFlightFiles.snapshot()
        assertFalse(snap.contains(a))
        assertFalse(snap.contains(b))
    }

    @Test
    fun `registry - releasing an unknown or empty name is a no-op`() {
        val witness = "shared-witness-${System.nanoTime()}.bin"
        StagedInFlightFiles.begin(witness)

        StagedInFlightFiles.begin("")
        StagedInFlightFiles.end("")
        StagedInFlightFiles.end("never-claimed.bin")

        assertTrue("an empty name must not be claimable", !StagedInFlightFiles.snapshot().contains(""))
        assertTrue("unrelated claims survive", StagedInFlightFiles.snapshot().contains(witness))
        StagedInFlightFiles.end(witness)
    }

    @Test
    fun `registry - a snapshot is a copy, not a live view`() {
        val name = "shared-copy-${System.nanoTime()}.bin"
        StagedInFlightFiles.begin(name)
        val snap = StagedInFlightFiles.snapshot()

        StagedInFlightFiles.end(name)

        assertTrue("a cleanup pass must decide on the snapshot it took", snap.contains(name))
        assertFalse(StagedInFlightFiles.snapshot().contains(name))
    }

    @Test
    fun `registry - concurrent claim release leaves no phantom names`() {
        val threads = 4
        val perThread = 50
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val names = (0 until threads).flatMap { t -> (0 until perThread).map { i -> "shared-c$t-i$i.bin" } }
        try {
            val futures = (0 until threads).map { t ->
                pool.submit {
                    start.await()
                    for (i in 0 until perThread) {
                        val n = "shared-c$t-i$i.bin"
                        StagedInFlightFiles.begin(n)
                        StagedInFlightFiles.snapshot()
                        StagedInFlightFiles.end(n)
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        val snap = StagedInFlightFiles.snapshot()
        val leaked = names.filter { snap.contains(it) }
        assertEquals("no claim may survive its own release: $leaked", 0, leaked.size)
    }
}
