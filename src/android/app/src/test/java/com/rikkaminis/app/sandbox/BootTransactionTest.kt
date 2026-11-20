package com.rikkaminis.app.sandbox

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class BootTransactionTest {
    @Test fun `permit remains valid over real dispatcher switches and suspension`() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { first ->
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { second ->
                val gate = BootTransaction()
                withContext(first) {
                    val firstThread = Thread.currentThread().id
                    gate.runExclusive { permit ->
                        gate.requirePermit(permit)
                        withContext(second) {
                            assertNotEquals(firstThread, Thread.currentThread().id)
                            CompletableDeferred(Unit).await()
                            gate.requirePermit(permit)
                        }
                        gate.requirePermit(permit)
                    }
                }
            }
        }
    }

    @Test fun `unrelated coroutine on the same thread cannot enter a suspended transaction`() = runBlocking {
        val gate = BootTransaction()
        val release = CompletableDeferred<Unit>()
        var resetEntered = false
        val owner = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.runExclusive { release.await() }
        }
        val reset = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.runReset { resetEntered = true }
        }
        try {
            assertFalse(resetEntered)
            release.complete(Unit)
            withTimeout(3000) { owner.join(); reset.join() }
            assertTrue(resetEntered)
        } finally { release.complete(Unit); owner.cancelAndJoin(); reset.cancelAndJoin() }
    }

    @Test fun `child coroutine does not implicitly inherit permission to reacquire gate`() = runBlocking {
        val gate = BootTransaction()
        val release = CompletableDeferred<Unit>()
        var childEntered = false
        val child = gate.runExclusive {
            val pending = launch(start = CoroutineStart.UNDISPATCHED) {
                gate.runExclusive { childEntered = true }
            }
            assertFalse(childEntered)
            pending
        }
        withTimeout(3000) { child.join() }
        assertTrue(childEntered)
        release.complete(Unit)
        Unit
    }

    @Test fun `expired foreign and forged permits are rejected`() = runBlocking {
        val gate = BootTransaction()
        val other = BootTransaction()
        val expired = gate.runExclusive { it }
        assertThrows(IllegalStateException::class.java) { gate.requirePermit(expired) }
        other.runExclusive { foreign ->
            assertThrows(IllegalStateException::class.java) { gate.requirePermit(foreign) }
        }
        // Permit implementations are sealed; the only implementation is private.
        val src = sourceOf("PRootKernel.kt")
        assertTrue(src.contains("internal sealed interface Permit"))
        assertTrue(src.contains("private class HeldPermit : Permit"))
    }

    @Test fun `queued cancelled reset never enters or leaves mutex locked`() = runBlocking {
        val gate = BootTransaction()
        val release = CompletableDeferred<Unit>()
        var entered = false
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { gate.runExclusive { release.await() } }
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { gate.runReset { entered = true } }
        cancelled.cancelAndJoin()
        release.complete(Unit)
        withTimeout(3000) { owner.join(); gate.runReset { } }
        assertFalse(entered)
    }

    @Test fun `exception releases transaction and expires permit`() = runBlocking {
        val gate = BootTransaction()
        var saved: BootTransaction.Permit? = null
        try {
            gate.runExclusive<Unit> { permit -> saved = permit; error("expected install failure") }
            fail("body must throw")
        } catch (_: IllegalStateException) { }
        assertThrows(IllegalStateException::class.java) { gate.requirePermit(saved!!) }
        withTimeout(3000) { gate.runInstall { } }
    }

    @Test fun `transaction locks the actual manager lifecycle mutex even before first boot`() = runBlocking {
        val lifecycle = Mutex()
        val gate = BootTransaction(lifecycle)
        gate.runReset { permit ->
            assertTrue(lifecycle.holdsLock(permit))
            gate.requirePermit(permit)
        }
        assertFalse(lifecycle.isLocked)
        val src = sourceOf("RootfsManager.kt")
        assertTrue(src.contains("private val bootTransaction = BootTransaction(lifecycleMutex)"))
        assertFalse(src.contains("installBootTransaction"))
    }

    @Test fun `public asset restore waits for the entire boot scope`() = runBlocking {
        val gate = BootTransaction()
        val release = CompletableDeferred<Unit>()
        val entered = AtomicBoolean(false)
        val boot = launch(start = CoroutineStart.UNDISPATCHED) { gate.runExclusive { release.await() } }
        val restore = launch(start = CoroutineStart.UNDISPATCHED) { gate.runExclusive { entered.set(true) } }
        try {
            assertFalse(entered.get())
            release.complete(Unit)
            withTimeout(3000) { boot.join(); restore.join() }
            assertTrue(entered.get())
        } finally { release.complete(Unit); boot.cancelAndJoin(); restore.cancelAndJoin() }
    }

    @Test fun `all external lifecycle routes acquire the same permanent transaction`() {
        val src = sourceOf("RootfsManager.kt")
        for ((entry, route, locked) in listOf(
            Triple("installIfNeeded(forceReinstall: Boolean = false): Unit", "runInstall", "installIfNeededLocked(forceReinstall)"),
            Triple("autoRepair(): Boolean", "runExclusive", "autoRepairLocked()"),
            Triple("restoreCriticalFromAssets(): Boolean", "runExclusive", "restoreCriticalFromAssetsLocked()"),
            Triple("reset(): Unit", "runReset", "resetLocked()"),
        )) {
            val body = src.substringAfter("suspend fun $entry = ").substringBefore("\n    }")
            assertTrue(entry, body.startsWith("bootTransaction.$route"))
            assertTrue(entry, body.contains(locked))
            assertFalse(entry, body.contains("lifecycleMutex.withLock"))
        }
    }

    @Test fun `boot explicitly passes permit only to already locked internal routes`() {
        val kernel = sourceOf("PRootKernel.kt")
        assertTrue(kernel.contains("manager.runBootTransaction { permit ->"))
        assertTrue(kernel.contains("bootGate.run { bootCriticalSection(context, permit) }"))
        assertTrue(kernel.contains("installIfNeededWithinBootTransaction(permit)"))
        assertTrue(kernel.contains("autoRepairWithinBootTransaction(permit)"))
        val manager = sourceOf("RootfsManager.kt")
        assertEquals(2, Regex("bootTransaction\\.requirePermit\\(permit\\)").findAll(manager).count())
        assertFalse(manager.contains("lifecycleMutex.withLock"))
        assertFalse(kernel.contains("ThreadLocal"))
        assertFalse(kernel.contains("ownerThread"))
        assertFalse(kernel.contains("BootTransaction.Lease"))
    }

    @Test fun `Stage3 repair and reset remain on locked call graph with nonlocking invalidation`() {
        val src = sourceOf("RootfsManager.kt")
        val repair = src.substringAfter("internal suspend fun autoRepairLocked()").substringBefore("suspend fun restoreCriticalFromAssets()")
        assertTrue(repair.contains("runCatching { resetLocked() }"))
        assertFalse(Regex("(?<![A-Za-z])reset\\(\\)").containsMatchIn(repair))
        val reset = src.substringAfter("internal suspend fun resetLocked()").substringBefore("suspend fun getRootfsSize()")
        assertTrue(reset.contains("installIfNeededLocked(forceReinstall = true)"))
        assertTrue(reset.contains("afterRootfsReinstalled?.invoke()"))
        assertFalse(reset.contains("bootTransaction.run"))
        val kernel = sourceOf("PRootKernel.kt")
        val callback = kernel.substringAfter("private fun onRootfsReinstalled(").substringBefore("fun addBindMount(")
        assertTrue(callback.contains("bootGate.invalidate()"))
        assertFalse(callback.contains("boot("))
    }

    private fun sourceOf(name: String): String {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val file = File(dir, "src/android/app/src/main/java/com/rikkaminis/app/sandbox/$name")
            if (file.isFile) return file.readText()
            dir = dir.parentFile
        }
        error("$name not found")
    }
}
