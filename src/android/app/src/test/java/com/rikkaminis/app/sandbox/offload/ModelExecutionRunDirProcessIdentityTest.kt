package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TF-H: injectable proc-root tests for the process-identity liveness probe.
 * [ModelExecutionRunDir.readProcIdentity] accepts a proc root so a JVM test
 * can simulate ALIVE / MISSING / UNREADABLE / PID-reused without touching the
 * real /proc.
 */
class ModelExecutionRunDirProcessIdentityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeProcEntry(
        procRoot: File,
        pid: Int,
        comm: String,
        uid: Int = 10123,
        startTicks: Long = 424242L,
    ) {
        val dir = File(procRoot, pid.toString())
        dir.mkdirs()
        File(dir, "comm").writeText(comm + "\n")
        File(dir, "status").writeText(
            "Name:\t$comm\nUid:\t$uid\t$uid\t$uid\t$uid\n",
        )
        // `/proc/<pid>/stat`: `pid (comm) state ppid ... starttime(field 22)`.
        // After the closing paren the tokens are field 3 onward. starttime is
        // field 22 → token index 19.
        val tokens = mutableListOf("S")            // field 3 (state), index 0
        repeat(18) { tokens.add("0") }             // fields 4..21, index 1..18
        tokens.add(startTicks.toString())          // field 22, index 19
        repeat(6) { tokens.add("0") }              // fields 23+ padding
        File(dir, "stat").writeText("$pid ($comm) ${tokens.joinToString(" ")}\n")
    }

    private fun dir(): File = tmp.newFolder("run-${System.nanoTime()}")

    private fun writeRef(d: File, pid: Int, processName: String = ":modelservice", uid: Int = 10123, startTicks: Long = 424242L) {
        ModelExecutionRunDir.writeWorkerPid(
            d,
            ModelExecutionRunDir.WorkerProcessRef(
                pid = pid,
                runId = "abc",
                nonce = "n1",
                processName = processName,
                startedAtMs = 0L,
                procStartTicks = startTicks,
                uid = uid,
            ),
        )
    }

    @Test
    fun `matching proc identity is ALIVE`() {
        val procRoot = tmp.newFolder("proc1")
        writeProcEntry(procRoot, pid = 999, comm = "com.rikkaminis.app.lab", startTicks = 42L)
        // Naming: probe uses comm fallback / cmdline; write a matching comm+stat.
        val d = dir()
        writeRef(d, pid = 999, processName = "com.rikkaminis.app.lab", startTicks = 42L)
        assertEquals(
            ModelExecutionRunDir.WorkerLiveness.ALIVE,
            ModelExecutionRunDir.probeLiveness(d, "abc", procRoot),
        )
    }

    @Test
    fun `missing proc is DEAD`() {
        val procRoot = tmp.newFolder("proc2")
        val d = dir()
        writeRef(d, pid = 998, processName = ":modelservice")
        assertEquals(
            ModelExecutionRunDir.WorkerLiveness.DEAD,
            ModelExecutionRunDir.probeLiveness(d, "abc", procRoot),
        )
    }

    @Test
    fun `unreadable proc is UNKNOWN`() {
        val procRoot = tmp.newFolder("proc3")
        val d = dir()
        // The proc dir exists but is missing status/stat/comm → unreadable.
        File(procRoot, "997").mkdirs()
        writeRef(d, pid = 997, processName = ":modelservice")
        assertEquals(
            ModelExecutionRunDir.WorkerLiveness.UNKNOWN,
            ModelExecutionRunDir.probeLiveness(d, "abc", procRoot),
        )
    }

    @Test
    fun `reused pid with different starttime is not ALIVE`() {
        val procRoot = tmp.newFolder("proc4")
        // The old worker recorded startTicks 100000, but the pid now belongs
        // to a different process with startTicks 200000 (pid reuse).
        writeProcEntry(procRoot, pid = 996, comm = "com.rikkaminis.app.lab", startTicks = 200000L)
        val d = dir()
        writeRef(d, pid = 996, processName = "com.rikkaminis.app.lab", startTicks = 100000L)
        assertTrue(
            "reused pid must not be considered ALIVE",
            ModelExecutionRunDir.probeLiveness(d, "abc", procRoot) != ModelExecutionRunDir.WorkerLiveness.ALIVE,
        )
    }

    @Test
    fun `wrong process name is not ALIVE`() {
        val procRoot = tmp.newFolder("proc5")
        writeProcEntry(procRoot, pid = 995, comm = "totally.different.process", uid = 10124, startTicks = 77L)
        val d = dir()
        writeRef(d, pid = 995, processName = ":modelservice", uid = 10123, startTicks = 77L)
        assertTrue(
            "wrong process name/uid must not be ALIVE",
            ModelExecutionRunDir.probeLiveness(d, "abc", procRoot) != ModelExecutionRunDir.WorkerLiveness.ALIVE,
        )
    }
}