package com.rikkaminis.app.share

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real production checkpoint, ledger and copy core; no ContentProvider or user files. */
class ShareLifecycleRegressionTest {
    private val root = java.nio.file.Files.createTempDirectory("share-lifecycle-").toFile()
    private val names = mutableListOf<String>()
    private fun item(name: String) = PendingShare.Item(PendingShare.Item.Kind.ATTACHMENT, name)
    private fun restore(p: ShareExtractionProgress) = ShareExtractionProgress.restore(
        listOf("A", "B", "C"), JSONObject(p.pauseAndSnapshot().toString()),
    )
    private fun finish(p: ShareExtractionProgress, calls: MutableList<String>) {
        for ((i, id) in p.inputIds.withIndex()) {
            if (!p.isComplete(i)) { calls += id; p.complete(i, item(id)) }
        }
    }
    @After fun cleanup() { StagedInFlightFiles.endAll(names); root.deleteRecursively() }

    @Test fun `restore after A stages only B C and delivers all three in order`() {
        val p = ShareExtractionProgress(listOf("A", "B", "C"))
        var credits = 0
        p.complete(0, item("A")) { credits++ }
        val q = restore(p)
        val calls = mutableListOf<String>(); finish(q, calls)
        assertEquals(listOf("B", "C"), calls)
        assertEquals(listOf("A", "B", "C"), q.items().map { it.value })
        assertFalse(p.complete(1, item("B")) { credits++ })
        assertEquals(1, credits)
    }
    @Test fun `B interrupted by saved state retries B and C without crediting old B`() {
        val p = ShareExtractionProgress(listOf("A", "B", "C"))
        p.complete(0, item("A"))
        val q = restore(p)
        var staleCredits = 0
        assertFalse(p.complete(1, item("old-B")) { staleCredits++ })
        val calls = mutableListOf<String>(); finish(q, calls)
        assertEquals(listOf("B", "C"), calls)
        assertEquals(0, staleCredits)
        assertEquals(listOf("A", "B", "C"), q.items().map { it.value })
    }
    @Test fun `restore completed batch copies nothing`() {
        val p = ShareExtractionProgress(listOf("A", "B", "C"))
        val first = mutableListOf<String>(); finish(p, first)
        val q = restore(p); val calls = mutableListOf<String>(); finish(q, calls)
        assertTrue(q.isFinished()); assertEquals(emptyList<String>(), calls)
        assertEquals(3, q.items().size)
    }
    @Test fun `failed A has terminal slot so restore does not shift B onto A`() {
        val p = ShareExtractionProgress(listOf("A", "B", "C"))
        p.complete(0, null); p.complete(1, item("B"))
        val q = restore(p); val calls = mutableListOf<String>(); finish(q, calls)
        assertEquals(listOf("C"), calls)
        assertEquals(listOf("B", "C"), q.items().map { it.value })
    }
    @Test fun `empty partial snapshot still restores unfinished input identities`() {
        val p = ShareExtractionProgress(listOf("A", "B", "C"))
        val q = restore(p); val calls = mutableListOf<String>(); finish(q, calls)
        assertEquals(listOf("A", "B", "C"), calls)
    }
    @Test fun `different original input identity does not inherit completion`() {
        val p = ShareExtractionProgress(listOf("A", "B", "C")); p.complete(0, item("A"))
        val q = ShareExtractionProgress.restore(listOf("X", "B", "C"), p.pauseAndSnapshot())
        assertFalse(q.isComplete(0)); assertTrue(q.items().isEmpty())
    }
    @Test fun `same activity resumes frozen state without repeating successes`() {
        val p = ShareExtractionProgress(listOf("A", "B", "C")); p.complete(0, item("A"))
        p.pauseAndSnapshot(); p.resume(); val calls = mutableListOf<String>(); finish(p, calls)
        assertEquals(listOf("B", "C"), calls)
    }

    private fun stage(name: String, size: Int, ledger: StagedBytesLedger, committed: Boolean = true): File {
        val file = File(root, name); names += name
        StagedInFlightFiles.begin(name); file.writeBytes(ByteArray(size))
        if (committed) StagedInFlightFiles.credit(name, size.toLong()) { ledger.credit(size.toLong()) }
        return file
    }
    private fun clean(ledger: StagedBytesLedger, referenced: Set<String> = emptySet()) {
        val listed = root.listFiles().orEmpty()
        StagedInFlightFiles.withLock {
            cleanStagedDirectory(listed, referenced, StagedInFlightFiles.snapshot())
            StagedInFlightFiles.reconcileLedger(root, referenced, ledger::set)
        }
    }
    @Test fun `completed in-flight 80 survives cleanup and real 30 copy is refused at 100`() {
        val ledger = StagedBytesLedger(); val file = stage("eighty.bin", 80, ledger)
        clean(ledger); assertTrue(file.exists()); assertEquals(80L, ledger.current())
        val dest = File(root, "thirty.bin")
        val result = copySharedStream({ ByteArrayInputStream(ByteArray(30)) }, { dest.outputStream() },
            { dest.delete() }, ledger::current, ledger::credit, 100, 100, 1000, { 0 })
        assertEquals(SharedStreamFailure.ExceededCumulative, (result as SharedStreamCopy.Failed).reason)
        assertFalse(dest.exists()); assertEquals(80L, ledger.current())
    }
    @Test fun `copying partial length is not counted then commit credits exactly once`() {
        val ledger = StagedBytesLedger(); val f = stage("partial.bin", 40, ledger, false)
        clean(ledger); assertTrue(f.exists()); assertEquals(0L, ledger.current())
        f.writeBytes(ByteArray(80))
        StagedInFlightFiles.credit(f.name, 80) { ledger.credit(80) }
        clean(ledger); clean(ledger)
        assertEquals(80L, ledger.current())
        assertFalse(StagedInFlightFiles.credit(f.name, 80) { ledger.credit(80) })
        assertEquals(80L, ledger.current())
    }
    @Test fun `pending and completed in-flight overlap is counted only once`() {
        val ledger = StagedBytesLedger(); val f = stage("overlap.bin", 80, ledger)
        clean(ledger, setOf(f.name)); assertEquals(80L, ledger.current())
        StagedInFlightFiles.end(f.name)
        clean(ledger, setOf(f.name)); assertEquals(80L, ledger.current())
    }
    @Test fun `restore existing completed claim does not credit twice`() {
        val ledger = StagedBytesLedger(); val f = stage("restored.bin", 80, ledger)
        StagedInFlightFiles.begin(f.name)
        assertFalse(StagedInFlightFiles.restoreCommitted(f.name, 80) { ledger.credit(80) })
        assertEquals(80L, ledger.current())
    }
    @Test fun `cold restore credits surviving file once and cleanup preserves it`() {
        val ledger = StagedBytesLedger(); val f = File(root, "cold.bin"); names += f.name
        f.writeBytes(ByteArray(80)); StagedInFlightFiles.begin(f.name)
        assertTrue(StagedInFlightFiles.restoreCommitted(f.name, 80) { ledger.credit(80) })
        clean(ledger); assertEquals(80L, ledger.current()); assertTrue(f.exists())
    }
    @Test fun `orphan deletion clears committed bytes only after release`() {
        val ledger = StagedBytesLedger(); val f = stage("orphan.bin", 80, ledger)
        StagedInFlightFiles.end(f.name); clean(ledger)
        assertFalse(f.exists()); assertEquals(0L, ledger.current())
    }

    private fun copy(source: () -> InputStream?, cancellation: SharedStreamCancellation, timeout: Long = 70): SharedStreamCopy {
        val dest = File(root, "blocked.bin")
        return copySharedStream(source, { dest.outputStream() }, { dest.delete() }, { 0 },
            { fail("failed or interrupted copy must not credit") }, 100, 100, timeout,
            { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }, cancellation)
    }
    @Test fun `blocking read returns at deadline closes input and removes partial`() {
        val entered = CountDownLatch(1); val released = CountDownLatch(1); val closed = AtomicBoolean()
        val stream = object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                while (true) { try { released.await(); return -1 } catch (_: InterruptedException) { } }
            }
            override fun close() { closed.set(true); released.countDown() }
        }
        val start = System.nanoTime()
        val result = copy({ stream }, SharedStreamCancellation())
        assertEquals(SharedStreamFailure.TimedOut, (result as SharedStreamCopy.Failed).reason)
        assertEquals(0L, entered.count); assertTrue(closed.get())
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
        assertFalse(File(root, "blocked.bin").exists())
    }
    @Test fun `blocking open uses cancellation callback and reclaims worker`() {
        val entered = CountDownLatch(1); val released = CountDownLatch(1); val finished = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        val handle = SharedStreamCancellation { cancelled.set(true); released.countDown() }
        val result = copy({ entered.countDown(); try { released.await(); null } finally { finished.countDown() } }, handle)
        assertEquals(SharedStreamFailure.TimedOut, (result as SharedStreamCopy.Failed).reason)
        assertTrue(cancelled.get()); assertEquals(0L, entered.count)
        assertTrue(finished.await(1, TimeUnit.SECONDS)); assertFalse(File(root, "blocked.bin").exists())
    }
    @Test fun `late open result after timeout is closed and cannot create destination`() {
        val released = CountDownLatch(1); val closed = CountDownLatch(1)
        val handle = SharedStreamCancellation { released.countDown() }
        val result = copy({
            while (true) { try { released.await(); break } catch (_: InterruptedException) { } }
            object : ByteArrayInputStream(ByteArray(3)) { override fun close() { closed.countDown() } }
        }, handle)
        assertEquals(SharedStreamFailure.TimedOut, (result as SharedStreamCopy.Failed).reason)
        assertTrue(closed.await(1, TimeUnit.SECONDS)); assertFalse(File(root, "blocked.bin").exists())
    }
    @Test fun `explicit cancellation interrupts blocked read and gives no credit`() {
        val entered = CountDownLatch(1); val released = CountDownLatch(1)
        val handle = SharedStreamCancellation()
        var result: SharedStreamCopy? = null
        val caller = Thread {
            result = copy({ object : InputStream() {
                override fun read(): Int { entered.countDown(); released.await(); return -1 }
                override fun close() { released.countDown() }
            } }, handle, 5000)
        }
        caller.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); handle.cancel(); caller.join(1500)
        assertFalse(caller.isAlive); assertTrue(result is SharedStreamCopy.Failed)
        assertFalse(File(root, "blocked.bin").exists())
    }
    @Test fun `normal small stream stays synchronous and credits after close exactly once`() {
        val closed = AtomicBoolean(); var credits = 0; var bytes = 0L
        val caller = Thread.currentThread(); var creditThread: Thread? = null
        val out = ByteArrayOutputStream()
        val result = copySharedStream({ object : ByteArrayInputStream(byteArrayOf(1,2,3)) {
            override fun close() { closed.set(true) }
        } }, { out }, {}, { 0 }, {
            assertTrue(closed.get()); credits++; bytes += it; creditThread = Thread.currentThread()
        }, 100, 100, 1000, { 0 })
        assertTrue(result is SharedStreamCopy.Staged); assertEquals(1, credits)
        assertEquals(3L, bytes); assertSame(caller, creditThread); assertArrayEquals(byteArrayOf(1,2,3), out.toByteArray())
    }
    @Test fun `two producers and repeated cleanup cannot erase or double committed bytes`() {
        val ledger = StagedBytesLedger()
        val a = stage("producer-a.bin", 40, ledger, false)
        val b = stage("producer-b.bin", 30, ledger, false)
        val start = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val threads = listOf(
            Thread { try { start.await(); StagedInFlightFiles.credit(a.name, 40) { ledger.credit(40) } } catch (e: Throwable) { failure.set(e) } },
            Thread { try { start.await(); StagedInFlightFiles.credit(b.name, 30) { ledger.credit(30) } } catch (e: Throwable) { failure.set(e) } },
            Thread { try { start.await(); repeat(40) { clean(ledger) } } catch (e: Throwable) { failure.set(e) } },
        )
        threads.forEach { it.start() }; start.countDown(); threads.forEach { it.join(2000) }
        assertTrue(threads.none { it.isAlive }); failure.get()?.let { throw it }
        clean(ledger); assertEquals(70L, ledger.current()); assertTrue(a.exists() && b.exists())
    }
    @Test fun `copy failure deletes partial without debiting another completed producer`() {
        val ledger = StagedBytesLedger(); val a = stage("other-completed.bin", 80, ledger)
        names += "failed.bin"; StagedInFlightFiles.begin("failed.bin")
        val dest = File(root, "failed.bin")
        val result = copySharedStream({ object : InputStream() {
            override fun read(): Int = throw IOException("broken")
        } }, { dest.outputStream() }, { dest.delete() }, ledger::current,
            { StagedInFlightFiles.credit(dest.name, it) { ledger.credit(it) } }, 100, 100, 1000, { 0 })
        StagedInFlightFiles.end(dest.name); clean(ledger)
        assertTrue(result is SharedStreamCopy.Failed); assertFalse(dest.exists())
        assertTrue(a.exists()); assertEquals(80L, ledger.current())
    }
    @Test fun `repeated normal copies leave no live per-copy workers`() {
        repeat(12) {
            val result = copySharedStream({ ByteArrayInputStream(byteArrayOf(1)) },
                { ByteArrayOutputStream() }, {}, { 0 }, {}, 100, 100, 1000, { 0 })
            assertTrue(result is SharedStreamCopy.Staged)
        }
        assertEquals(0, Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "ShareStreamCopy" })
    }
    @Test fun `null and throwing open never creates destination`() {
        for (source in listOf<() -> InputStream?>({ null }, { throw IOException("open failed") })) {
            assertTrue(copy(source, SharedStreamCancellation()) is SharedStreamCopy.Failed)
            assertFalse(File(root, "blocked.bin").exists())
        }
    }
}
