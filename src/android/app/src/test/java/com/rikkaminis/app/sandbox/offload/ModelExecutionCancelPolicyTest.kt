package com.rikkaminis.app.sandbox.offload

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ModelExecutionCancelPolicyTest {
    @Test
    fun `cancelled queued run never reaches the production sender funnel`() {
        val slots = Semaphore(1, true)
        assertTrue(slots.tryAcquire())
        val cancelled = AtomicBoolean(false)
        val senderCalls = AtomicInteger()
        val queueSettled = AtomicInteger()
        val result = CompletableDeferred<String>()
        val enteredQueue = CompletableDeferred<Unit>()
        val thread = Thread {
            val outcome = runWithModelExecutionSlot(
                slots = slots,
                cancelRequested = { cancelled.get() },
                pollMs = 5L,
                onQueuePoll = {
                    enteredQueue.complete(Unit)
                    cancelled.set(true)
                },
                onQueueSettled = { queueSettled.incrementAndGet() },
                onCancelled = { "cancelled" },
                sender = { senderCalls.incrementAndGet(); "sent" },
            )
            result.complete(outcome)
        }.apply { isDaemon = true; start() }

        runBlocking { withTimeout(1_000L) { enteredQueue.await() } }
        thread.join(1_000L)

        assertFalse("queue waiter must settle promptly", thread.isAlive)
        assertEquals("explicit cancellation must complete this run", "cancelled", result.getCompleted())
        assertEquals("cancelled waiter must never invoke the real sender callback", 0, senderCalls.get())
        assertEquals("queue ownership is released exactly once", 1, queueSettled.get())
        assertEquals("the other request still owns the only permit", 0, slots.availablePermits())
        slots.release()
        assertEquals(1, slots.availablePermits())

        val healthy = runWithModelExecutionSlot(
            slots = slots,
            cancelRequested = { false },
            pollMs = 5L,
            onCancelled = { "cancelled" },
            sender = { senderCalls.incrementAndGet(); "healthy" },
        )
        assertEquals("healthy", healthy)
        assertEquals(1, senderCalls.get())
        assertEquals("the production funnel releases exactly one permit", 1, slots.availablePermits())

        val lease = acquireModelExecutionSlot(slots, { false }, 5L)
        assertTrue("a healthy lease must be acquirable", lease != null)
        lease!!.releaseOnce()
        lease.releaseOnce()
        assertEquals("idempotent release must not inflate the slot count", 1, slots.availablePermits())
    }

    @Test
    fun `cancel in semaphore handoff returns acquired permit before sender`() {
        val slots = Semaphore(1, true)
        val reads = AtomicInteger()
        val senderCalls = AtomicInteger()
        val queueSettled = AtomicInteger()
        val outcome = runWithModelExecutionSlot(
            slots = slots,
            cancelRequested = { reads.incrementAndGet() > 1 },
            pollMs = 5L,
            onQueueSettled = { queueSettled.incrementAndGet() },
            onCancelled = { "cancelled" },
            sender = { senderCalls.incrementAndGet(); "sent" },
        )
        assertEquals("cancelled", outcome)
        assertEquals(0, senderCalls.get())
        assertEquals(1, queueSettled.get())
        assertEquals("raced permit is restored exactly once", 1, slots.availablePermits())
    }

    @Test
    fun `cancel file during provider wait cancels owner and suppresses response publisher`() = runBlocking {
        val dir = createTempDir(prefix = "worker-cancel-")
        val cancel = File(dir, "cancel")
        val entered = CompletableDeferred<Unit>()
        val race = ModelExecutionCancellationRace()
        var published = false
        val call = async(Dispatchers.IO) {
            withModelExecutionCancellation(
                cancelFile = cancel,
                pollMs = 5L,
                cancellation = { ModelExecutionStopException() },
                race = race,
                block = { ensureBeforeDispatch ->
                    ensureBeforeDispatch()
                    entered.complete(Unit)
                    awaitCancellation()
                },
                publish = { published = true },
            )
        }
        try {
            withTimeout(1_000L) { entered.await() }
            cancel.createNewFile()
            val outcome = runCatching { withTimeout(1_000L) { call.await() } }.exceptionOrNull()
            assertTrue("watcher must win the run-local cancellation CAS", race.cancellationWon)
            assertTrue("real stop reason, not timeout, must escape", outcome is ModelExecutionStopException)
            assertFalse("cancelled response must never publish success", published)
        } finally {
            call.cancelAndJoin()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `provider cancellation exception without this run stop remains an ordinary failure`() = runBlocking {
        val dir = createTempDir(prefix = "worker-provider-error-")
        val cancel = File(dir, "cancel")
        val race = ModelExecutionCancellationRace()
        val providerCause = java.io.IOException("upstream reset while reading response body")
        var published = false
        try {
            val outcome = runCatching {
                withModelExecutionCancellation(
                    cancelFile = cancel,
                    pollMs = 5L,
                    cancellation = { ModelExecutionStopException() },
                    race = race,
                    block = { ensureBeforeDispatch ->
                        ensureBeforeDispatch()
                        throw CancellationException("provider stream failed").apply { initCause(providerCause) }
                    },
                    publish = { published = true },
                )
            }.exceptionOrNull()
            assertTrue("the provider CE must remain visible", outcome is CancellationException)
            assertTrue("the cause must be preserved", outcome?.cause is java.io.IOException)
            assertFalse("no marker means no user-stop classification", race.cancellationWon)
            assertFalse("failed response must not publish success", published)
            assertFalse("no fake cancel ack may be authorized", isExplicitWorkerCancellation(race))
            val envelope = org.json.JSONObject(nonStreamingFailureResult(outcome!!, race, "imagesGenerations"))
            assertEquals("model_use_failed", envelope.getString("error"))
            assertTrue(envelope.getString("message").contains("provider stream failed"))
            assertEquals("imagesGenerations", envelope.getString("image_endpoint_resolved"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `normal response wins race and late marker cannot change ownership`() = runBlocking {
        val dir = createTempDir(prefix = "worker-complete-")
        val cancel = File(dir, "cancel")
        val race = ModelExecutionCancellationRace()
        val published = AtomicInteger()
        try {
            assertTrue(race.tryComplete(cancel))
            cancel.createNewFile()
            assertFalse(race.tryCancel(cancel))
            assertTrue(race.completionWon)
            assertFalse(race.cancellationWon)
            published.incrementAndGet()
            assertEquals(1, published.get())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `explicit abandonment ACK follows result visibility in deterministic order`() = runBlocking {
        val abandonAck = AtomicBoolean(false)
        val result = AtomicBoolean(false)
        val clientAck = AtomicInteger()
        val resultMayCommit = CompletableDeferred<Unit>()
        val abandonVisible = CompletableDeferred<Unit>()
        val clientAckWritten = CompletableDeferred<Unit>()
        val worker = async(Dispatchers.IO) {
            abandonAck.set(true)
            abandonVisible.complete(Unit)
            resultMayCommit.await()
            result.set(true)
        }
        val handshake = async(Dispatchers.IO) {
            awaitExplicitAbandonmentHandshake(
                abandonAckPresent = { abandonAck.get() },
                resultPresent = { result.get() },
                writeClientAck = {
                    clientAck.incrementAndGet()
                    clientAckWritten.complete(Unit)
                },
                timeoutMs = 1_000L,
                pollMs = 5L,
            )
        }
        try {
            withTimeout(500L) { abandonVisible.await() }
            assertEquals("abandon ACK alone cannot be consumed", 0, clientAck.get())
            resultMayCommit.complete(Unit)
            withTimeout(500L) { clientAckWritten.await() }
            assertTrue(withTimeout(500L) { handshake.await() })
            assertTrue("result must precede the client ACK", result.get())
            assertEquals(1, clientAck.get())
        } finally {
            resultMayCommit.cancel()
            worker.cancelAndJoin()
            handshake.cancelAndJoin()
        }
    }

    @Test
    fun `cancel ack alone is insufficient to write client ack`() = runBlocking {
        val wroteClientAck = AtomicBoolean(false)
        val ok = awaitExplicitAbandonmentHandshake(
            abandonAckPresent = { true },
            resultPresent = { false },
            writeClientAck = { wroteClientAck.set(true) },
            timeoutMs = 30L,
            pollMs = 5L,
        )
        assertFalse(ok)
        assertFalse("ACK without result cannot authorize consumption", wroteClientAck.get())
    }

    @Test
    fun `worker wiring uses cancellable queue lease and atomic response race`() {
        val source = appSource("sandbox/offload/ModelExecutionService.kt").readText()
        val policy = appSource("sandbox/offload/ModelExecutionCancelPolicy.kt").readText()
        assertTrue("the funnel must own the permit acquisition", policy.contains("acquireModelExecutionSlot("))
        assertTrue(source.contains("cancelRequested = { cancelFile.isFile }"))
        assertTrue(source.contains("runWithModelExecutionSlot("))
        assertTrue(source.contains("onQueueSettled = {"))
        assertTrue(source.contains("onCancelled = { throw ModelExecutionStopException() }"))
        assertTrue(source.contains("if (cancelFile.isFile) throw ModelExecutionStopException()"))
        assertTrue(
            "the queue counter must be released exactly once through the funnel",
            Regex("""onQueueSettled\s*=\s*\{[^}]*queuedRequests\.decrementAndGet\(\)""")
                .containsMatchIn(source),
        )
        assertTrue(source.contains("withModelExecutionCancellation("))
        assertTrue(source.contains("publish = { completed -> writeResultAtomically(dir, completed) }"))
        assertTrue(source.contains("private fun executeRun("))
        // [frozen gate] the non-streaming provider call must stay lexically
        // inside executeRun — modeluse_proto_single_source_check is fail-closed.
        val executor = source.substringAfter("private fun executeRun(")
            .substringBefore("private fun executeStreamingRun(")
        assertTrue("executeRun must own the provider.sendMessage( call", executor.contains("provider.sendMessage("))
        assertTrue("executeRun must own the cancel watcher", executor.contains("withModelExecutionCancellation("))
        assertTrue("pre-dispatch rechecks must survive in the body",
            executor.split("ensureBeforeDispatch()").size - 1 >= 4)
        assertTrue(
            "no bare synchronous sendMessage outside the cancellation block",
            !executor.contains("runBlocking {\n                provider.sendMessage("),
        )
        assertTrue(source.contains("nonStreamingFailureResult(e, cancelRace, imageEndpointCache?.name)"))
        assertTrue(source.contains("writeCancelledRun(dir, isStreaming)"))
    }

    private fun appSource(relative: String): File {
        val candidates = listOf(
            "src/main/java/com/rikkaminis/app/$relative",
            "src/android/app/src/main/java/com/rikkaminis/app/$relative",
        )
        for (c in candidates) {
            val f = File(c)
            if (f.exists()) return f
        }
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (c in candidates) {
                val f = File(dir, c)
                if (f.exists()) return f
            }
            dir = dir.parentFile
        }
        throw AssertionError("$relative not found from ${File("").absolutePath}")
    }
}
