package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Timeout/cancel hard teardown must not depend on Process.destroyForcibly's signal. */
class PersistentShellTreeTerminationTest {
    private val processes = linkedMapOf(
        100 to ProcCpu(1, 1, 1000),
        200 to ProcCpu(100, 1, 2000),
        300 to ProcCpu(200, 1, 3000),
        400 to ProcCpu(300, 1, 4000),
        500 to ProcCpu(200, 1, 5000),
        999 to ProcCpu(1, 1, 9990),
    )

    private fun kill(
        root: Int,
        snapshot: Map<Int, ProcCpu>,
        current: Map<Int, ProcCpu> = snapshot,
        alive: Boolean = true,
        signal: (Int) -> Unit,
    ): Int = internalKillProcessTree(
        root, snapshot, currentProcess = current::get, rootIsAlive = { alive }, signalPid = signal,
    )

    @Test
    fun `teardown signals all descendants before their parents and the tracer last`() {
        val signalled = mutableListOf<Int>()
        val sent = kill(100, processes, signal = signalled::add)
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
        val count = kill(100, processes) { pid ->
            attempted.add(pid)
            if (pid == 400) throw IllegalStateException("process already gone")
        }
        assertEquals(4, count)
        assertEquals(setOf(100, 200, 300, 400, 500), attempted.toSet())
        assertEquals(100, attempted.last())
    }

    @Test
    fun `an unavailable proc snapshot cannot authorize numeric pid signals`() {
        val signalled = mutableListOf<Int>()
        assertEquals(0, kill(100, emptyMap(), signal = signalled::add))
        assertTrue(signalled.isEmpty())
    }

    @Test
    fun `invalid root cannot signal init or another process`() {
        val signalled = mutableListOf<Int>()
        for (pid in listOf(-1, 0, 1)) {
            assertEquals(0, kill(pid, processes, signal = signalled::add))
        }
        assertTrue(signalled.isEmpty())
    }

    @Test
    fun `a malformed parent cycle signals each process once with root last`() {
        val signalled = mutableListOf<Int>()
        val cycle = mapOf(100 to ProcCpu(200, 1, 1000), 200 to ProcCpu(100, 1, 2000))
        assertEquals(2, kill(100, cycle, signal = signalled::add))
        assertEquals(listOf(200, 100), signalled)
    }

    @Test
    fun `reused child pid is skipped without suppressing owned siblings`() {
        val current = processes.toMutableMap().apply { put(400, ProcCpu(300, 1, 4400)) }
        val sent = mutableListOf<Int>()
        assertEquals(4, kill(100, processes, current = current, signal = sent::add))
        assertEquals(setOf(100, 200, 300, 500), sent.toSet())
    }

    @Test
    fun `reused root pid is not signalled even if a handle liveness check races`() {
        val current = processes.toMutableMap().apply { put(100, ProcCpu(1, 1, 1001)) }
        val sent = mutableListOf<Int>()
        assertEquals(4, kill(100, processes, current = current, signal = sent::add))
        assertFalse(100 in sent)
    }

    @Test
    fun `unknown child starttime cannot authorize a numeric signal`() {
        val snapshot = processes.toMutableMap().apply { put(400, ProcCpu(300, 1)) }
        val sent = mutableListOf<Int>()
        assertEquals(4, kill(100, snapshot, signal = sent::add))
        assertFalse(400 in sent)
    }

    @Test
    fun `unreadable child identity is skipped without suppressing root cleanup`() {
        val current = processes.toMutableMap().apply { remove(400) }
        val sent = mutableListOf<Int>()
        assertEquals(4, kill(100, processes, current = current, signal = sent::add))
        assertEquals(100, sent.last())
    }

    @Test
    fun `same owned child survives reparenting in the snapshot to signal gap`() {
        val current = processes.toMutableMap().apply { put(400, ProcCpu(1, 1, 4000)) }
        val sent = mutableListOf<Int>()
        assertEquals(5, kill(100, processes, current = current, signal = sent::add))
        assertTrue(400 in sent)
    }

    @Test
    fun `dead root handle refuses the entire numeric pid plan`() {
        val sent = mutableListOf<Int>()
        assertEquals(0, kill(100, processes, alive = false, signal = sent::add))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `root dying during descendant cleanup prevents signalling its numeric pid`() {
        val sent = mutableListOf<Int>()
        var alive = true
        val count = internalKillProcessTree(100, processes, processes::get, { alive }) { pid ->
            sent.add(pid)
            alive = false
        }
        assertEquals(4, count)
        assertFalse(100 in sent)
    }

    @Test
    fun `identity read failure cannot prevent remaining owned processes being signalled`() {
        val sent = mutableListOf<Int>()
        val count = internalKillProcessTree(100, processes, { pid ->
            if (pid == 400) throw java.io.IOException("proc stat became unreadable")
            processes[pid]
        }, { true }, sent::add)
        assertEquals(4, count)
        assertFalse(400 in sent)
        assertEquals(100, sent.last())
    }

    @Test
    fun `stop wires explicit SIGKILL through the tree before closing pipes without waiting`() {
        val source = sourceFile("PersistentShell.kt").readText()
        val body = source.substringAfter("    fun stop() {").substringBefore("\n}\n")
        val snapshot = body.indexOf("val processSnapshot = if (")
        val hardKill = body.indexOf("internalKillProcessTree(")
        val writerClose = body.indexOf("writer?.close()")
        val pipeClose = body.indexOf("proc.inputStream.close()")
        assertTrue("stop must snapshot this Process tree", snapshot >= 0)
        assertTrue("snapshot must precede the hard kill", hardKill > snapshot)
        assertTrue("snapshot must precede EOF on the shell input", writerClose > hardKill)
        assertTrue("stop must use explicit SIGKILL", body.contains("android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL)"))
        assertTrue("tree must be signalled before closing its streams", pipeClose > hardKill)
        assertTrue("root identity must use the captured Process handle", body.contains("rootIsAlive = { proc.isAlive }"))
        assertTrue("current PID identity must be parsed afresh", body.contains("internalParseProcStat(File(\"/proc/\$pid/stat\").readText())"))
        val reaper = body.substringAfter("val reaper = Thread({")
        // [WAVE-1118 R3-F01] The anchor moved from the literal
        // `proc.waitFor(2, TimeUnit.SECONDS)` to the DECISION it encoded: the
        // bounded wait for the killed tree still lives on the asynchronous
        // reaper (never on the caller), and its budget is the named
        // [PersistentShell.REAP_WAIT_MS] rather than an inline literal — the
        // reaper now also CONSUMES the answer instead of discarding it (see
        // PersistentShellReapTest for that half).
        assertTrue(
            "the bounded wait for exit stays on the asynchronous reaper",
            reaper.contains("internalAwaitProcessGone(proc, REAP_WAIT_MS)"),
        )
        assertTrue(
            "the reap budget must stay a named constant, not an inline literal",
            source.contains("internal const val REAP_WAIT_MS = 2_000L"),
        )
        assertFalse("stop cannot block the caller waiting for exit", body.substringBefore("val reaper = Thread({").contains("waitFor("))
        assertFalse(
            "the bare discarded wait must not come back",
            reaper.contains("try { proc.waitFor("),
        )
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
