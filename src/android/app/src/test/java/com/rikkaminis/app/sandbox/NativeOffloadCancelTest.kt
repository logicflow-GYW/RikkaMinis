package com.rikkaminis.app.sandbox

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [A02] NativeOffload cancellation/ownership + [C-01] argv redaction.
 *
 * These are the two behaviours the surgery branch claims, exercised on the
 * REAL production classes ([OffloadCallContext], [OffloadSocketMonitor],
 * [BoundedWorkerPool]) with the platform boundaries injected. No Android
 * socket, no rootfs, no provider call: the socket edge is a fake probe and the
 * handler edge is a latch-controlled fake.
 */
class NativeOffloadCancelTest {

    // ---- C-01: the request log must not carry the argv/cwd ---------------

    @Test
    fun `the recv log carries no argv or cwd value`() {
        val src = sourceOf("sandbox/NativeOffload.kt")
        val recvLine = src.lines().firstOrNull { it.contains("\"recv pid=") }
        assertNotNull("the recv log line must exist", recvLine)
        val logCall = src.substring(src.indexOf("\"recv pid="))
            .substringBefore(")")
        assertFalse("the log must not interpolate the argv list", logCall.contains("\$argv"))
        assertFalse("the log must not interpolate the cwd", logCall.contains("\$cwd"))
        assertTrue("the log must keep the handler name", logCall.contains("\$name"))
        assertTrue("the log must keep the arg count", logCall.contains("\$argc"))
        assertTrue(
            "the log must keep the total argument SIZE, so the shape of a request is still visible",
            logCall.contains("argvChars="),
        )
    }

    @Test
    fun `no other offload log line interpolates argv or cwd`() {
        val src = sourceOf("sandbox/NativeOffload.kt")
        val offenders = src.lines().mapIndexedNotNull { i, line ->
            val t = line.trim()
            if (!t.contains("Log.")) return@mapIndexedNotNull null
            if (t.contains("\$argv") || t.contains("\$cwd")) i + 1 to t else null
        }
        assertEquals(
            "no log line may interpolate the raw argv/cwd (the secret may be a positional argument): $offenders",
            emptyList<Any>(),
            offenders,
        )
    }

    // ---- A02: the cancellation token -------------------------------------

    @Test
    fun `cancel is idempotent and notifies every listener once`() {
        val ctx = OffloadCallContext(7, "session-1")
        var notifications = 0
        val listener: () -> Unit = { notifications++ }
        ctx.addCancellationListener(listener)

        assertFalse("a fresh context is not cancelled", ctx.isCancelled)
        assertTrue("the first cancel wins", ctx.cancel())
        assertFalse("a second cancel is a no-op", ctx.cancel())
        assertEquals("the listener ran exactly once", 1, notifications)
        assertTrue(ctx.isCancelled)
    }

    @Test
    fun `a listener registered after cancellation is invoked immediately`() {
        val ctx = OffloadCallContext(8, null)
        ctx.cancel()
        var notified = false
        ctx.addCancellationListener { notified = true }
        assertTrue("a late listener must see the cancellation", notified)
    }

    @Test
    fun `a removed listener is not invoked`() {
        val ctx = OffloadCallContext(9, null)
        var notified = false
        val listener: () -> Unit = { notified = true }
        ctx.addCancellationListener(listener)
        ctx.removeCancellationListener(listener)
        ctx.cancel()
        assertFalse(notified)
    }

    // ---- A02: the socket watcher ------------------------------------------

    @Test
    fun `the watcher cancels the request whose socket went away`() {
        val fd = java.io.FileDescriptor()
        val dead = java.util.concurrent.atomic.AtomicBoolean(false)
        val monitor = OffloadSocketMonitor(pollMillis = 10, probe = { descriptor -> descriptor === fd && !dead.get() })
        val ctx = OffloadCallContext(11, "s")
        monitor.start()
        monitor.watch(fd, ctx)
        try {
            dead.set(true)
            val cancelled = waitUntil { ctx.isCancelled }
            assertTrue("a hung-up socket must cancel its request", cancelled)
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun `the watcher leaves a live socket alone`() {
        val fd = java.io.FileDescriptor()
        val monitor = OffloadSocketMonitor(pollMillis = 10, probe = { true })
        val ctx = OffloadCallContext(12, "s")
        monitor.start()
        monitor.watch(fd, ctx)
        try {
            Thread.sleep(120)
            assertFalse("a live socket must not cancel its request", ctx.isCancelled)
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun `the watcher holds one thread for the whole generation, not one per socket`() {
        val monitor = OffloadSocketMonitor(pollMillis = 10, probe = { true })
        monitor.start()
        try {
            repeat(50) { monitor.watch(java.io.FileDescriptor(), OffloadCallContext(it.toLong(), null)) }
            val threads = Thread.getAllStackTraces().keys.filter { it.name == "native-offload-monitor" }
            assertEquals("one watcher thread for 50 watched sockets", 1, threads.size)
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun `normal completed response ignores a subsequent HUP`() {
        val context = OffloadCallContext(31, null)
        var notified = 0
        context.addCancellationListener { notified++ }
        assertTrue(context.beginWork())
        assertTrue(context.handlerFinished())
        assertTrue(context.beginResponse())
        assertTrue(context.complete())
        assertFalse(context.cancel())
        assertFalse(context.isCancelled)
        assertEquals(0, notified)
    }

    @Test
    fun `monitor stop cancels active requests but preserves completed`() {
        val monitor = OffloadSocketMonitor(pollMillis = 10, probe = { true })
        val active = OffloadCallContext(41, null)
        val completed = OffloadCallContext(42, null)
        monitor.start()
        monitor.watch(java.io.FileDescriptor(), active)
        monitor.watch(java.io.FileDescriptor(), completed)
        completed.beginWork()
        completed.handlerFinished()
        completed.beginResponse()
        completed.complete()
        monitor.stop()
        assertTrue(active.isCancelled)
        assertFalse(completed.isCancelled)
        assertEquals(0, monitor.watchedCount())
    }

    @Test
    fun `watch after stop is cancelled and not retained`() {
        val monitor = OffloadSocketMonitor(pollMillis = 10, probe = { true })
        monitor.start()
        monitor.stop()
        val context = OffloadCallContext(43, null)
        monitor.watch(java.io.FileDescriptor(), context)
        assertTrue(context.isCancelled)
        assertEquals(0, monitor.watchedCount())
    }

    @Test
    fun `poll distinguishes readable bytes from HUP ERR and NVAL`() {
        assertFalse(isTerminalSocketPollResult(1, 0x0001, 0x0018))
        assertTrue(isTerminalSocketPollResult(1, 0x0010, 0x0018))
        assertTrue(isTerminalSocketPollResult(1, 0x0008, 0x0018))
        assertTrue(isTerminalSocketPollResult(1, 0x0020, 0x0038))
        assertFalse(isTerminalSocketPollResult(0, 0, 0x0038))
    }

    @Test
    fun `public LocalSocket fd and Os poll API are used`() {
        val src = sourceOf("sandbox/NativeOffload.kt")
        assertTrue(src.contains("Os.poll(arrayOf(pfd),0)"))
        assertTrue(src.contains("socket.fileDescriptor?.takeIf { it.valid() }"))
        assertFalse(src.contains("getDeclaredField(\"impl\")"))
    }

    // ---- A02: the bounded pool --------------------------------------------

    @Test
    fun `the pool refuses work past its pending limit`() {
        val permits = Semaphore(1, true)
        val pool = BoundedWorkerPool<String>(
            permits = permits,
            threadName = "test-worker",
            spawnThread = { _, _ -> },
            handler = { _, _ -> },
        )
        pool.setMaxPending(1)
        // The first submit takes the permit and spawns a worker that never
        // runs (the spawner is a no-op), so the queue holds one entry.
        assertTrue(pool.submit("a"))
        assertFalse("a second entry must be refused, not parked", pool.submit("b"))
        assertEquals(1, pool.pendingCount)
    }

    @Test
    fun `draining the pending queue hands every dropped item to the owner`() {
        val permits = Semaphore(1, true)
        val pool = BoundedWorkerPool<String>(
            permits = permits,
            threadName = "test-worker",
            spawnThread = { _, _ -> },
            handler = { _, _ -> },
        )
        pool.setMaxPending(2)
        pool.submit("a")
        pool.submit("b")
        val dropped = mutableListOf<String>()
        pool.drainPending { dropped.add(it) }
        assertEquals(listOf("a", "b"), dropped)
        assertEquals(0, pool.pendingCount)
    }

    // ---- A02: the frame decoder must still work ---------------------------

    @Test
    fun `a normal request frame still decodes after the context plumbing`() {
        val magic = 0x46464F4E
        val bos = ByteArrayOutputStream()
        fun writeInt(v: Int) {
            bos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        }
        fun writeStr(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            writeInt(bytes.size)
            bos.write(bytes)
        }
        writeInt(magic)
        writeInt(1)
        writeInt(1234)
        writeInt(2)
        writeStr("minis-config")
        writeStr("set")
        writeInt(1)
        writeStr("MINIS_CHAT_SESSION_ID=s1")
        writeStr("/root")
        val bytes = bos.toByteArray()

        val budget = OffloadRequestBudget(MAX_REQUEST_BYTES)
        val input = java.io.DataInputStream(ByteArrayInputStream(bytes))
        assertEquals(magic, input.readLEInt(budget))
        assertEquals(1, input.readLEInt(budget))
        assertEquals(1234, input.readLEInt(budget))
        val argc = input.readLEInt(budget)
        val argv = (0 until argc).map { input.readLEString(budget) }
        assertEquals(listOf("minis-config", "set"), argv)
        val envc = input.readLEInt(budget)
        val env = (0 until envc).map { input.readLEString(budget) }.associate {
            it.substringBefore('=') to it.substringAfter('=')
        }
        assertEquals("s1", env["MINIS_CHAT_SESSION_ID"])
        assertEquals("/root", input.readLEString(budget))
    }

    // ---- A02: the socket watcher is the ONLY place that flips the token ----

    @Test
    fun `the server flips the token for a socket that hung up`() {
        val src = sourceOf("sandbox/NativeOffload.kt")
        assertTrue(
            "the monitor must cancel the request's context:\n$src",
            Regex("entry\\.context\\.cancel\\(\\)").containsMatchIn(src) ||
                src.contains("entry.context.cancel()"),
        )
        assertTrue(
            "the accept loop must watch the socket it accepted:\n$src",
            src.contains("monitor.watch(descriptor, context)"),
        )
        assertTrue(
            "a request picked up from the queue must be skipped when it was already cancelled:\n$src",
            src.contains("abandoned in queue — skipped"),
        )
        assertTrue("worker must atomically claim queued ownership", src.contains("if (!context.beginWork())"))
        assertTrue("claim must precede any frame read", src.indexOf("if (!context.beginWork())") < src.indexOf("val magic = input.readLEInt(budget)"))

    }

    @Test
    fun `a cancelled request does not leave a response tmpfile behind`() {
        val src = sourceOf("sandbox/NativeOffload.kt")
        assertTrue(
            "a reply written for a cancelled request must be reclaimed:\n$src",
            src.contains("tmpLedger.reclaim(File(delivered.hostPath))"),
        )
    }

    @Test
    fun `the server stops the watcher and drains the queue on shutdown`() {
        val src = sourceOf("sandbox/NativeOffload.kt")
        val stop = src.substring(src.indexOf("fun stop()"), src.indexOf("private fun runAcceptLoop"))
        assertTrue("stop() must stop the watcher:\n$stop", stop.contains("monitor?.stop()"))
        assertTrue(
            "stop() must drain the queued sockets instead of parking their fds:\n$stop",
            stop.contains("drainPending"),
        )
        assertTrue(
            "a dropped queued request must be cancelled, not silently forgotten:\n$stop",
            stop.contains("cancelForShutdown()"),
        )
    }

    // ---- A02: the inner dispatch shares the socket's cancellation ---------

    @Test
    fun `the model-use handler waits on the dispatch through the offload context`() {
        val src = sourceOf("sandbox/offload/ModelUseOffloadHandler.kt")
        assertTrue(
            "both dispatch sites must go through the cancellable wrapper:\n$src",
            src.contains("dispatchCancellable {"),
        )
        // The CALL SITE keeps the literal dispatch shape the existing wiring
        // tests pin (the current instance key must travel with the request):
        // the wrapper takes the dispatch as a lambda, so the call itself stays
        // exactly where it was.
        assertTrue(
            "the standard dispatch must still carry the current instance key:\n$src",
            src.contains(
                "ModelExecutionDispatcher.dispatch(context, remoteRequestJson, " +
                    "apiKey = WorkerKeyHandoff.keyFor(context, instance.id))",
            ),
        )
        assertTrue(
            "the passthrough dispatch must still carry the current instance key:\n$src",
            src.contains(
                "ModelExecutionDispatcher.dispatch(context, ptRequestJson, " +
                    "apiKey = WorkerKeyHandoff.keyFor(context, instance.id))",
            ),
        )
        val bare = src.lines().mapIndexedNotNull { i, line ->
            val t = line.trim()
            if (t.contains("ModelExecutionDispatcher.dispatch(") && !t.startsWith("//")) i + 1 to t else null
        }
        assertTrue(
            "both dispatch sites must exist (the standard one and the passthrough one): $bare",
            bare.size == 2,
        )
        assertTrue(
            "the wrapper must read the socket's context:\n$src",
            src.contains("NativeOffloadServer.currentCallContext()"),
        )
        assertTrue(
            "the wrapper must cancel the inner dispatch when the context is cancelled:\n$src",
            src.contains("dispatch.cancel(CancellationException(\"offload request cancelled\"))"),
        )
    }

    @Test
    fun `the offload server publishes the request context around the handler call`() {
        val src = sourceOf("sandbox/NativeOffload.kt")
        assertTrue(
            "the handler call must run with the request context published:\n$src",
            src.contains("NativeOffloadServer.requestContext.set(context)"),
        )
        assertTrue(
            "and the context must be cleared afterwards:\n$src",
            src.contains("NativeOffloadServer.requestContext.remove()"),
        )
    }

    // ---- helpers -----------------------------------------------------------

    private fun waitUntil(predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(10)
        }
        return predicate()
    }

    /** 1-based line number of [marker] in [text]. */
    private fun String.substringIndexOf(marker: String): Int =
        substring(0, indexOf(marker)).count { it == '\n' } + 1

    /** Reads a production source file by its path under the app source root. */
    private fun sourceOf(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir"))
        while (dir != null) {
            val f = java.io.File(dir, "src/android/app/src/main/java/com/rikkaminis/app/$relative")
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        error("$relative not found")
    }
}
