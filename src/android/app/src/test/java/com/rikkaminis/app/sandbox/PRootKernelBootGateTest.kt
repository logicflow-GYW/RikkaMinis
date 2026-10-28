package com.rikkaminis.app.sandbox

import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1016 FIX-C 3a] — `PRootKernel.boot()` used to be guarded by a
 * bare `if (isBooted) return` on a non-volatile flag. Two callers race on cold
 * start (`ExecutionCoordinator`'s auto-boot, `TerminalSession`'s
 * open-a-terminal), and the body calls `RootfsManager.installIfNeeded`, which
 * *deletes* the rootfs and re-extracts it — the second entrant destroyed the
 * tree the first was unpacking.
 *
 * `boot()` itself needs a `Context`, so the mutual-exclusion contract lives in
 * [BootOnceGate] (real production class, no Android deps) and is exercised
 * here with real threads; the wiring assertions below pin that `boot()` uses
 * it and that [PRootKernel.isBooted] is *that same fact* rather than a second
 * flag.
 */
class PRootKernelBootGateTest {

    @Test
    fun `concurrent entrants run the body exactly once`() = runBlocking {
        val gate = BootOnceGate()
        val ran = java.util.concurrent.atomic.AtomicInteger(0)
        val results = (1..16).map {
            async(Dispatchers.Default) {
                // The sleep widens the window a pre-fix `if (flagged) return`
                // would walk through: without the lock all 16 run the body.
                gate.run {
                    ran.incrementAndGet()
                    Thread.sleep(50)
                }
            }
        }.awaitAll()

        assertEquals("body must run exactly once", 1, ran.get())
        assertEquals("exactly one caller may report that it performed the work", 1, results.count { it })
    }

    @Test
    fun `late callers skip the body and report they did not run it`() = runBlocking {
        val gate = BootOnceGate()
        var ran = 0
        assertTrue(gate.run { ran++ })
        assertTrue(gate.isDone)
        delay(1) // let the gate settle on a different dispatcher tick
        assertFalse(gate.run { ran++ })
        assertEquals(1, ran)
    }

    @Test
    fun `a failing body does not mark the gate done`() = runBlocking {
        val gate = BootOnceGate()
        var attempts = 0
        runCatching {
            gate.run {
                attempts++
                throw IllegalStateException("install failed")
            }
        }
        // A retry must be allowed to run — otherwise a transient install
        // failure would leave the sandbox permanently "booted".
        assertFalse(gate.isDone)
        assertTrue(gate.run { attempts++ })
        assertEquals(2, attempts)
        assertTrue(gate.isDone)
    }

    // ---- [meta1028 D3-F02] the reset edge -------------------------------

    @Test
    fun `invalidate re-arms the gate and the body runs again`() = runBlocking {
        val gate = BootOnceGate()
        var runs = 0
        assertTrue(gate.run { runs++ })
        assertTrue(gate.isDone)
        assertFalse("a second caller skips", gate.run { runs++ })
        assertEquals(1, runs)

        gate.invalidate()  // RootfsManager.reset() deleted the tree this body built

        assertFalse("isBooted must read false again", gate.isDone)
        assertTrue("the next caller must re-run the body", gate.run { runs++ })
        assertEquals(2, runs)
        assertFalse("and it is one-shot again, not every-caller", gate.run { runs++ })
        assertEquals(2, runs)
    }

    @Test
    fun `invalidate inside the body neither deadlocks nor sticks open`() = runBlocking {
        // The autoRepair Stage-3 shape: the reset happens INSIDE the critical
        // section, i.e. while this gate's own mutex is held by the body that
        // calls invalidate(). A lock-taking invalidate would self-deadlock; the
        // volatile store does not, and `run` re-asserts the flag when the body
        // returns — which is the right reading, because the tail of that same
        // body (stubs / overlay / mounts) re-materialized what the reset took.
        val gate = BootOnceGate()
        var inside = 0
        val performed = gate.run { gate.invalidate(); inside++ }
        assertTrue(performed)
        assertEquals(1, inside)
        assertTrue("the body completing must close the gate again", gate.isDone)
        assertFalse("so the next caller still skips", gate.run { inside++ })
        assertEquals(1, inside)
    }

    @Test
    fun `boot wiring - gate owns the flag and boot goes through it`() {
        val src = readRepoFile(PROOT_KERNEL)
        val bootBody = declarationSlice(src, "suspend fun boot(")
        assertTrue("boot() must run its body through the gate:\n$bootBody", bootBody.contains("bootGate.run {"))
        assertTrue(
            "boot() must keep its lock-free fast path:\n$bootBody",
            bootBody.contains("if (isBooted)"),
        )
        assertTrue(
            "isBooted must BE the gate's flag, not a second copy:\n$src",
            Regex("""val isBooted: Boolean\s*\n\s*get\(\) = bootGate\.isDone""").containsMatchIn(src),
        )
        assertFalse(
            "a leftover `isBooted = ...` assignment would split the flag from the lock",
            Regex("""(?m)^\s*isBooted\s*=""").containsMatchIn(src),
        )
        assertTrue(
            "the gate's flag must be volatile (read from other threads):\n$src",
            src.contains("@Volatile\n    var isDone: Boolean = false"),
        )
        assertTrue(
            "the critical section must be private — only the gate may run it:\n$src",
            src.contains("private suspend fun bootCriticalSection(context: Context)"),
        )
        // Suspend-ness is part of the contract, not a detail: the body awaits
        // RootfsManager's suspend API, and the first version of this refactor
        // declared BOTH this helper and the gate's lambda parameter non-suspend
        // — `:app:compileReleaseKotlin` rejected the file ("Suspend function
        // 'installIfNeeded' should be called only from a coroutine…"). The
        // extraction harness can't catch it: it slices fragments, so a
        // signature-level error in a body it never extracts is invisible.
        val criticalSection = declarationSlice(src, "fun bootCriticalSection(")
        val suspendCalls = listOf(
            "installIfNeeded(", "installProotIfNeeded(", "autoRepair(",
            "applyDefaultMountOverlay(", "retryFailedApkWorld(", "dumpApkWorld(",
        ).filter { criticalSection.contains(it) }
        assertTrue(
            "bootCriticalSection must be `suspend` — its body awaits ${suspendCalls.ifEmpty { listOf("the rootfs API") }}:\n$criticalSection",
            criticalSection.contains("suspend fun bootCriticalSection("),
        )
        assertTrue(
            "bootCriticalSection no longer calls any suspend rootfs API — if that is now true, " +
                "drop the `suspend` requirement in the same change (this assertion exists to keep the two in sync):\n$criticalSection",
            suspendCalls.isNotEmpty(),
        )
        assertTrue(
            "BootOnceGate.run must accept a suspend body (its production caller awaits inside):\n$src",
            Regex("""suspend fun run\(body: suspend \(\) -> Unit\)""").containsMatchIn(src),
        )
        // [meta1028 D3-F02] A reset edge that nothing installs is not a fix: the
        // handler has to be wired where RootfsManager is first obtained, and it
        // has to be the thing that re-arms the gate.
        assertTrue(
            "bootCriticalSection must install the rebuild hook:\n$criticalSection",
            criticalSection.contains("afterRootfsReinstalled ="),
        )
        val handler = declarationSlice(src, "private fun onRootfsReinstalled(")
        assertTrue(
            "the rebuild handler must re-arm the gate:\n$handler",
            handler.contains("bootGate.invalidate()"),
        )
        assertTrue(
            "the hook must be installed BEFORE the first installIfNeeded(), or a reset during " +
                "the very first boot has nobody to notify:\n$criticalSection",
            criticalSection.indexOf("afterRootfsReinstalled =") < criticalSection.indexOf("installIfNeeded()"),
        )
    }

    @Test
    fun `the critical section is not reachable except through boot`() {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        var root: File? = null
        while (dir != null) {
            val candidate = File(dir, "src/android/app/src/main/java/com/rikkaminis/app")
            if (candidate.isDirectory) {
                root = candidate
                break
            }
            dir = dir.parentFile
        }
        assertTrue("app source root not found", root != null)
        val offenders = root!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "PRootKernel.kt" }
            .filter { it.readText().contains("bootCriticalSection") }
            .map { it.name }
            .toList()
        assertTrue("bootCriticalSection must only be referenced from PRootKernel.kt: $offenders", offenders.isEmpty())
    }

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    /**
     * Source lines of the declaration at [marker].
     *
     * Ends at the first non-blank line that is back at (or above) the
     * declaration's own indent **while both the parameter list's parens and the
     * body's braces are balanced**. The two simpler rules both truncate a real
     * declaration — the "next member" cut stops at a body statement 8 spaces
     * deep (`val normalized = …`), the indent cut stops at a multi-line
     * top-level signature's closing `): File? {`, and a plain brace count stops
     * at an expression body's lambda (`…?.let { File(it, …) },` balances to
     * zero on its own line). Each failure hid the very line the assertions
     * check, which is why this rule is stated as a conjunction.
     */
    private fun declarationSlice(src: String, marker: String): String {
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains(marker) }
        assertTrue("marker not found: $marker", start >= 0)
        val indent = lines[start].takeWhile { it == ' ' }.length
        var paren = 0
        var brace = 0
        var end = start
        var k = start
        while (k < lines.size) {
            val s = stripStrings(lines[k])
            paren += s.count { it == '(' } - s.count { it == ')' }
            brace += s.count { it == '{' } - s.count { it == '}' }
            end = k
            k++
            var peek = k
            while (peek < lines.size && lines[peek].isBlank()) peek++
            if (peek >= lines.size) { end = lines.size - 1; break }
            if (paren <= 0 && brace <= 0 && lines[peek].takeWhile { it == ' ' }.length <= indent) break
        }
        return lines.subList(start, end + 1).joinToString("\n")
    }

    /** Drops quoted spans so braces inside string literals do not unbalance the count. */
    private fun stripStrings(line: String): String {
        val sb = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < line.length) {
            val c = line[i]
            if (quote != null) {
                if (c == '\\') { i += 2; continue }
                if (c == quote) quote = null
                i++
                continue
            }
            if (c == '\'' || c == '"') quote = c
            sb.append(c)
            i++
        }
        return sb.toString()
    }
    private companion object {
        private const val PROOT_KERNEL =
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/PRootKernel.kt"
    }
}
