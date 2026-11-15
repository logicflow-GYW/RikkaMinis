package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Timeout/cancel hard teardown must not depend on Process.destroyForcibly's signal. */
class PersistentShellTreeTerminationTest {
    private val processes = linkedMapOf(
        100 to ProcCpu(1, 1),
        200 to ProcCpu(100, 1),
        300 to ProcCpu(200, 1),
        400 to ProcCpu(300, 1),
        500 to ProcCpu(200, 1),
        999 to ProcCpu(1, 1),
    )

    @Test
    fun `teardown signals all descendants before their parents and the tracer last`() {
        val signalled = mutableListOf<Int>()
        val sent = internalKillProcessTree(100, processes, signalled::add)
        assertEquals(5, sent)
        assertEquals(setOf(100, 200, 300, 400, 500), signalled.toSet())
        assertEquals(100, signalled.last())
        for ((child, parent) in listOf(400 to 300, 300 to 200, 500 to 200, 200 to 100)) {
            assertTrue("child $child must be signalled before parent $parent", signalled.indexOf(child) < signalled.indexOf(parent))
        }
        assertFalse("another session is not part of this tree", 999 in signalled)
    }

    @Test
    fun `a vanished child does not suppress sibling or root termination`() {
        val attempted = mutableListOf<Int>()
        val count = internalKillProcessTree(100, processes) { pid ->
            attempted.add(pid)
            if (pid == 400) throw IllegalStateException("process already gone")
        }
        assertEquals(4, count)
        assertEquals(setOf(100, 200, 300, 400, 500), attempted.toSet())
        assertEquals(100, attempted.last())
    }

    @Test
    fun `an unavailable proc snapshot still signals the tracer`() {
        val signalled = mutableListOf<Int>()
        assertEquals(1, internalKillProcessTree(100, emptyMap(), signalled::add))
        assertEquals(listOf(100), signalled)
    }

    @Test
    fun `invalid root cannot signal init or another process`() {
        val signalled = mutableListOf<Int>()
        for (pid in listOf(-1, 0, 1)) {
            assertEquals(0, internalKillProcessTree(pid, processes, signalled::add))
        }
        assertTrue(signalled.isEmpty())
    }

    @Test
    fun `a malformed parent cycle signals each process once with root last`() {
        val signalled = mutableListOf<Int>()
        val cycle = mapOf(100 to ProcCpu(200, 1), 200 to ProcCpu(100, 1))
        assertEquals(2, internalKillProcessTree(100, cycle, signalled::add))
        assertEquals(listOf(200, 100), signalled)
    }

    @Test
    fun `stop wires explicit SIGKILL through the tree before closing pipes without waiting`() {
        val source = sourceFile("PersistentShell.kt").readText()
        val body = source.substringAfter("    fun stop() {").substringBefore("\n}\n")
        val hardKill = body.indexOf("internalKillProcessTree(rootPid, readAllProcStats())")
        val pipeClose = body.indexOf("proc.inputStream.close()")
        assertTrue("stop must select this Process tree", hardKill >= 0)
        assertTrue("stop must use explicit SIGKILL", body.contains("android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL)"))
        assertTrue("tree must be signalled before closing its streams", pipeClose > hardKill)
        assertTrue("root identity must be checked before signalling", body.contains("proc.isAlive"))
        val reaper = body.substringAfter("val reaper = Thread({")
        assertTrue("waitFor stays on the asynchronous reaper", reaper.contains("proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)"))
        assertFalse("stop cannot block the caller waiting for exit", body.substringBefore("val reaper = Thread({").contains("waitFor("))
    }

    private fun sourceFile(name: String): File {
        val relative = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/$name"
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val file = File(dir, relative)
            if (file.isFile) return file
            dir = dir.parentFile
        }
        error("source file not found: $relative")
    }
}
