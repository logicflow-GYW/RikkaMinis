package com.rikkaminis.app.sandbox

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class OffloadOwnershipRaceTest {
    @Test fun `queued cancellation prevents work from starting`() {
        val context = OffloadCallContext(100, null)
        assertTrue(context.cancel())
        assertFalse(context.beginWork())
        assertFalse(context.handlerFinished())
        assertFalse(context.beginResponse())
    }

    @Test fun `work may only be claimed once`() {
        val context = OffloadCallContext(101, null)
        assertTrue(context.beginWork())
        assertFalse(context.beginWork())
        assertTrue(context.cancel())
        assertFalse(context.handlerFinished())
    }

    @Test fun `cancel racing response admission has exactly one owner`() {
        repeat(80) {
            val context = OffloadCallContext(102, null)
            assertTrue(context.beginWork())
            assertTrue(context.handlerFinished())
            val start = CountDownLatch(1)
            val cancelled = AtomicInteger()
            val responding = AtomicInteger()
            val a = Thread { start.await(); if (context.cancel()) cancelled.incrementAndGet() }
            val b = Thread { start.await(); if (context.beginResponse()) responding.incrementAndGet() }
            a.start(); b.start(); start.countDown(); a.join(2000); b.join(2000)
            assertFalse(a.isAlive); assertFalse(b.isAlive)
            assertEquals(1, cancelled.get() + responding.get())
            if (responding.get() == 1) {
                assertFalse(context.cancel())
                assertTrue(context.complete())
            } else assertTrue(context.isCancelled)
        }
    }

    @Test fun `listener registration racing cancel is notified exactly once`() {
        repeat(80) {
            val context = OffloadCallContext(103, null)
            context.beginWork()
            val start = CountDownLatch(1)
            val calls = AtomicInteger()
            val a = Thread { start.await(); context.addCancellationListener { calls.incrementAndGet() } }
            val b = Thread { start.await(); context.cancel() }
            a.start(); b.start(); start.countDown(); a.join(2000); b.join(2000)
            assertEquals(1, calls.get())
        }
    }

    @Test fun `old request cancellation cannot cancel a different context`() {
        val old = OffloadCallContext(104, "same-session")
        val replacement = OffloadCallContext(105, "same-session")
        old.beginWork(); replacement.beginWork(); old.cancel()
        assertTrue(old.isCancelled)
        assertFalse(replacement.isCancelled)
        assertTrue(replacement.handlerFinished())
    }

    @Test fun `cancellation during result file creation prevents response and reclaims file`() {
        val root = Files.createTempDirectory("offload-owner-").toFile()
        val ledger = OffloadTmpFileLedger()
        val context = OffloadCallContext(106, null)
        context.beginWork(); context.handlerFinished()
        try {
            val response = deliverResponseFile("ok", root, 106, 1, ledger) { file, value ->
                file.writeText(value)
                context.cancel()
            }.getOrThrow()
            assertFalse(context.beginResponse())
            ledger.reclaim(File(response.hostPath))
            assertFalse(File(response.hostPath).exists())
            assertEquals(0, ledger.size)
        } finally { root.deleteRecursively() }
    }

    @Test fun `normal native close after response begins does not delete its result`() {
        val root = Files.createTempDirectory("offload-reply-").toFile()
        val ledger = OffloadTmpFileLedger()
        val context = OffloadCallContext(107, null)
        context.beginWork(); context.handlerFinished()
        try {
            val response = deliverResponseFile("readable", root, 107, 1, ledger).getOrThrow()
            assertTrue(context.beginResponse())
            assertFalse(context.cancel())
            assertTrue(context.complete())
            assertFalse(context.cancelForShutdown())
            assertEquals("readable", File(response.hostPath).readText())
        } finally { ledger.clearAll(); root.deleteRecursively() }
    }

    @Test fun `shutdown may abort a blocked response and triggers its own socket close`() {
        val context = OffloadCallContext(108, null)
        val closed = AtomicInteger()
        context.addCancellationListener { closed.incrementAndGet() }
        context.beginWork(); context.handlerFinished(); context.beginResponse()
        assertTrue(context.cancelForShutdown())
        assertFalse(context.complete())
        assertEquals(1, closed.get())
    }

    @Test fun `pool shutdown rejects late accept and returns permits after drain`() {
        val permits = Semaphore(1)
        val workers = mutableListOf<() -> Unit>()
        val handled = mutableListOf<String>()
        val pool = BoundedWorkerPool<String>(permits, "owner-test", { _, body -> workers.add(body) }, { item, _ -> handled.add(item) })
        assertTrue(pool.submit("queued"))
        val dropped = mutableListOf<String>()
        pool.drainPending { dropped.add(it) }
        assertFalse(pool.submit("late-after-stop"))
        workers.single().invoke()
        assertEquals(listOf("queued"), dropped)
        assertEquals(emptyList<String>(), handled)
        assertEquals(1, permits.availablePermits())
    }

    @Test fun `null descriptor remains shutdown-owned without false disconnect`() {
        val context = OffloadCallContext(109, null)
        val monitor = OffloadSocketMonitor(pollMillis = 10, probe = { error("null must not be probed") })
        monitor.start()
        try {
            monitor.watch(null, context)
            assertFalse(context.isCancelled)
        } finally { monitor.stop() }
        assertTrue(context.isCancelled)
        assertEquals(0, monitor.watchedCount())
    }

    @Test fun `ModelUse installs cancellation listener before starting its lazy dispatch`() {
        val source = sourceOf("sandbox/offload/ModelUseOffloadHandler.kt")
        val body = source.substringAfter("private fun dispatchCancellable(").substringBefore("private fun cmdList(")
        assertTrue(body.contains("CoroutineStart.LAZY"))
        assertTrue(body.indexOf("addCancellationListener(listener)") < body.indexOf("dispatch.start()"))
        assertTrue(body.contains("CoroutineScope(coroutineContext + supervisor)"))
        assertFalse(body.contains("if (callContext.isCancelled) return null"))
        val dispatcher = sourceOf("sandbox/offload/ModelExecutionDispatcher.kt")
        assertTrue(dispatcher.contains("val offloadContext = NativeOffloadServer.currentCallContext()"))
        assertTrue(dispatcher.contains("shouldContinue = { offloadContext?.isCancelled != true }"))
    }

    private fun sourceOf(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val file = File(dir, "src/android/app/src/main/java/com/rikkaminis/app/$relative")
            if (file.isFile) return file.readText()
            dir = dir.parentFile
        }
        error("$relative not found")
    }
}
