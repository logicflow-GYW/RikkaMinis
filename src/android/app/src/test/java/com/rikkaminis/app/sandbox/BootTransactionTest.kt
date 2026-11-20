package com.rikkaminis.app.sandbox

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [A04] The boot/reset transaction gate.
 *
 * The defect it closes: `RootfsManager.reset()` took only `lifecycleMutex`
 * while `PRootKernel.boot()` took only `BootOnceGate`. A command that
 * auto-booted during a reset could therefore run the delete + re-extract
 * concurrently with the reset's identical delete + re-extract — the exact
 * "two entrants unpack the same tree" shape `BootOnceGate` was introduced for
 * on the boot side.
 *
 * The gate is a plain class (no Android, no PRootKernel state) so the
 * contract is JVM-testable: [BootTransaction].
 */
class BootTransactionTest {

    @Test
    fun `a reset and a boot never overlap`() = runBlocking {
        val gate = BootTransaction()
        val inside = AtomicInteger(0)
        val overlap = AtomicInteger(0)
        val bothEntered = CountDownLatch(2)

        fun body() {
            if (inside.incrementAndGet() > 1) overlap.incrementAndGet()
            bothEntered.countDown()
            Thread.sleep(80)
            inside.decrementAndGet()
        }

        val reset = async(Dispatchers.Default) { gate.runReset { body() } }
        val boot = async(Dispatchers.Default) { gate.runBoot { body() } }
        assertTrue("both must reach the body", bothEntered.await(5, TimeUnit.SECONDS))
        reset.await()
        boot.await()
        assertEquals("reset and boot must not overlap", 0, overlap.get())
    }

    @Test
    fun `a boot queued behind a reset runs after it`() = runBlocking {
        val gate = BootTransaction()
        var boots = 0
        var resets = 0
        val order = mutableListOf<String>()
        val resetStarted = CountDownLatch(1)
        val releaseReset = CountDownLatch(1)

        val reset = async(Dispatchers.Default) {
            gate.runReset {
                resets++
                order.add("reset")
                resetStarted.countDown()
                releaseReset.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(resetStarted.await(5, TimeUnit.SECONDS))
        val boot = async(Dispatchers.Default) {
            gate.runBoot {
                boots++
                order.add("boot")
            }
        }
        // The boot cannot enter while the reset holds the gate.
        Thread.sleep(80)
        assertEquals("the boot must not run inside the reset", 0, boots)
        releaseReset.countDown()
        reset.await()
        boot.await()
        assertEquals("the reset ran once", 1, resets)
        assertEquals("the boot ran once, after the reset", 1, boots)
        assertEquals(listOf("reset", "boot"), order)
    }

    @Test
    fun `a reset inside the boot body does not deadlock`() = runBlocking {
        // The autoRepair Stage-3 shape: the reset happens INSIDE the boot's
        // transaction. Re-entering the gate from the owning thread must not
        // wait on the lock it already holds.
        val gate = BootTransaction()
        var resets = 0
        val performed = gate.runBoot {
            assertTrue("the owning thread owns the transaction", gate.ownsCurrentThread())
            gate.runReset { resets++ }
        }
        assertTrue(performed)
        assertEquals("the nested reset ran", 1, resets)
    }

    @Test
    fun `an install and a reset are serialized`() = runBlocking {
        val gate = BootTransaction()
        val inside = AtomicInteger(0)
        val overlap = AtomicInteger(0)
        fun body() {
            if (inside.incrementAndGet() > 1) overlap.incrementAndGet()
            Thread.sleep(60)
            inside.decrementAndGet()
        }
        val install = async(Dispatchers.Default) { gate.runInstall { body() } }
        val reset = async(Dispatchers.Default) { gate.runReset { body() } }
        awaitAll(install, reset)
        assertEquals("install and reset must not overlap", 0, overlap.get())
    }

    @Test
    fun `the gate counts the resets that went through it`() = runBlocking {
        val gate = BootTransaction()
        assertEquals(0L, gate.resetCount())
        gate.runReset { }
        gate.runReset { }
        assertEquals(2L, gate.resetCount())
    }

    // ---- the wiring: every destructive entry must take the gate -----------

    @Test
    fun `every destructive rootfs entry routes through the boot transaction`() {
        val src = sourceOf("sandbox/RootfsManager.kt")
        assertTrue(
            "reset() must run inside the boot transaction:\n$src",
            src.contains("transaction.runReset { lifecycleMutex.withLock { resetLocked() } }"),
        )
        assertTrue(
            "installIfNeeded() must run inside the boot transaction:\n$src",
            src.contains("transaction.runInstall {"),
        )
        assertTrue(
            "autoRepair() must run inside the boot transaction (its Stage 3 is a full reset):\n$src",
            src.contains("transaction.runExclusive {"),
        )
        assertTrue(
            "the transaction must be the OUTER lock and lifecycleMutex the inner one:\n$src",
            src.contains("transaction.runReset { lifecycleMutex.withLock { resetLocked() } }"),
        )
    }

    @Test
    fun `the boot body installs the transaction and the kernel owns one gate`() {
        val kernel = sourceOf("sandbox/PRootKernel.kt")
        assertTrue(
            "the kernel must own exactly one boot/reset transaction:\n$kernel",
            kernel.contains("private val bootLifecycleGate = BootTransaction()"),
        )
        assertTrue(
            "boot() must run its body inside that transaction:\n$kernel",
            kernel.contains("bootLifecycleGate.runBoot {"),
        )
        assertTrue(
            "the manager must be told which transaction to use:\n$kernel",
            kernel.contains("manager.installBootTransaction(bootLifecycleGate)"),
        )
    }

    @Test
    fun `a foreign thread does not own the transaction`() {
        val gate = BootTransaction()
        assertFalse("no thread owns the gate before it is taken", gate.ownsCurrentThread())
    }

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
