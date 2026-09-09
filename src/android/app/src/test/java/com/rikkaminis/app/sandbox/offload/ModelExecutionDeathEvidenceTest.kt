package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TF-I P0-B/P0-D: tests for the fine-grained death probe
 * [ModelExecutionRunDir.probeDeathEvidence].
 *
 * The client death trigger must only treat a *confirmed* MISSING pid as death;
 * an identity-mismatch is suspicious (logged for diagnostics) but NOT proof the
 * worker died — it may be alive but drifted (the TF-H open question), and must
 * be allowed to revert to ALIVE across polls.
 */
class ModelExecutionDeathEvidenceTest {

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
        File(dir, "status").writeText("Name:\t$comm\nUid:\t$uid\t$uid\t$uid\t$uid\n")
        val tokens = mutableListOf("S")
        repeat(18) { tokens.add("0") }
        tokens.add(startTicks.toString())          // field 22 → index 19
        repeat(6) { tokens.add("0") }
        File(dir, "stat").writeText("$pid ($comm) ${tokens.joinToString(" ")}\n")
    }

    private fun dir(): File = tmp.newFolder("run-${System.nanoTime()}")

    private fun writeRef(
        d: File,
        pid: Int,
        processName: String = ":modelservice",
        uid: Int = 10123,
        startTicks: Long = 424242L,
    ) {
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
    fun `no pid ref is NO_REF (not death)`() {
        val d = dir()
        val ev = ModelExecutionRunDir.probeDeathEvidence(d, "abc", tmp.newFolder("p1"))
        assertEquals(ModelExecutionRunDir.DeathKind.NO_REF, ev.kind)
        assertNull(ev.pid)
    }

    @Test
    fun `matching identity is ALIVE`() {
        val procRoot = tmp.newFolder("p2")
        writeProcEntry(procRoot, pid = 1, comm = ":modelservice", uid = 10123, startTicks = 424242L)
        val d = dir()
        writeRef(d, pid = 1, processName = ":modelservice", uid = 10123, startTicks = 424242L)
        val ev = ModelExecutionRunDir.probeDeathEvidence(d, "abc", procRoot)
        assertEquals(ModelExecutionRunDir.DeathKind.ALIVE, ev.kind)
    }

    @Test
    fun `missing proc is MISSING (confirmed death)`() {
        val procRoot = tmp.newFolder("p3")
        val d = dir()
        writeRef(d, pid = 999, processName = ":modelservice")
        val ev = ModelExecutionRunDir.probeDeathEvidence(d, "abc", procRoot)
        assertEquals(ModelExecutionRunDir.DeathKind.MISSING, ev.kind)
        assertEquals(999, ev.pid)
    }

    @Test
    fun `unreadable proc is UNKNOWN (not death)`() {
        val procRoot = tmp.newFolder("p4")
        writeProcEntry(procRoot, pid = 7, comm = ":modelservice")
        // Break the status file so uid read fails → UNREADABLE.
        File(procRoot, "7/status").writeText("not-uid-format\n")
        val d = dir()
        writeRef(d, pid = 7, processName = ":modelservice", uid = 10123)
        val ev = ModelExecutionRunDir.probeDeathEvidence(d, "abc", procRoot)
        assertEquals(ModelExecutionRunDir.DeathKind.UNKNOWN, ev.kind)
    }

    @Test
    fun `identity mismatch is NOT death and names the drift field`() {
        val procRoot = tmp.newFolder("p5")
        // Proc says pid 8 is a different process name than the ref records.
        writeProcEntry(procRoot, pid = 8, comm = "com.android.systemui", uid = 10123, startTicks = 424242L)
        val d = dir()
        writeRef(d, pid = 8, processName = ":modelservice", uid = 10123, startTicks = 424242L)
        val ev = ModelExecutionRunDir.probeDeathEvidence(d, "abc", procRoot)
        // The worker process may be gone and the pid recycled to systemUI — but
        // the probe has not confirmed OUR worker died; it returns a named
        // mismatch so the client can weigh it (never an instant death verdict).
        assertEquals(ModelExecutionRunDir.DeathKind.IDENTITY_MISMATCH, ev.kind)
        assertEquals(true, ev.detail?.contains("name"))
    }

    @Test
    fun `uid drift is named but not death`() {
        val procRoot = tmp.newFolder("p6")
        writeProcEntry(procRoot, pid = 9, comm = ":modelservice", uid = 11111, startTicks = 424242L)
        val d = dir()
        writeRef(d, pid = 9, processName = ":modelservice", uid = 10123, startTicks = 424242L)
        val ev = ModelExecutionRunDir.probeDeathEvidence(d, "abc", procRoot)
        assertEquals(ModelExecutionRunDir.DeathKind.IDENTITY_MISMATCH, ev.kind)
        assertEquals(true, ev.detail?.contains("uid"))
    }

    @Test
    fun `startTicks drift is named but not death`() {
        val procRoot = tmp.newFolder("p7")
        writeProcEntry(procRoot, pid = 10, comm = ":modelservice", uid = 10123, startTicks = 99999L)
        val d = dir()
        writeRef(d, pid = 10, processName = ":modelservice", uid = 10123, startTicks = 424242L)
        val ev = ModelExecutionRunDir.probeDeathEvidence(d, "abc", procRoot)
        assertEquals(ModelExecutionRunDir.DeathKind.IDENTITY_MISMATCH, ev.kind)
        assertEquals(true, ev.detail?.contains("startTicks"))
    }

    @Test
    fun `blank ref processName is a wildcard - ALIVE for any matching uid+ticks`() {
        val procRoot = tmp.newFolder("p8")
        writeProcEntry(procRoot, pid = 11, comm = "what.ever", uid = 10123, startTicks = 424242L)
        val d = dir()
        // Blank processName in ref → no name check; uid & ticks match → alive.
        writeRef(d, pid = 11, processName = "", uid = 10123, startTicks = 424242L)
        val ev = ModelExecutionRunDir.probeDeathEvidence(d, "abc", procRoot)
        assertEquals(ModelExecutionRunDir.DeathKind.ALIVE, ev.kind)
    }
}