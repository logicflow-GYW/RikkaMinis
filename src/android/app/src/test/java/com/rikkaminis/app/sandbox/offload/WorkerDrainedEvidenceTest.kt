package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [TF-G-ack-evidence] Regression anchor for [ModelExecutionRunDir.workerDrained].
 *
 * On 2026-09-18 the streaming client deleted its run dir the instant IT wrote
 * `client.ack` — i.e. while the worker was still inside its ack barrier; the
 * worker then burned its full 15s ack timeout (42/43 runs) and tripped
 * `protocol_violation=run_dir_missing` when writing terminal into the deleted
 * dir. These cases pin the rule that only WORKER-owned evidence authorizes
 * deletion, and that a live worker (fresh heartbeat, mid-stream) is never
 * "drained".
 */
class WorkerDrainedEvidenceTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun dir(): File = tmp.newFolder("run-test")

    private fun touch(f: File, ageMs: Long = 0L): File {
        f.writeText("x")
        if (ageMs > 0) f.setLastModified(System.currentTimeMillis() - ageMs)
        return f
    }

    /** THE bug: our own handshake must never look like "worker drained". */
    @Test
    fun `client ack alone is not drained evidence`() {
        val d = dir()
        touch(File(d, ModelExecutionMailbox.FILE_CLIENT_ACK))
        assertFalse(ModelExecutionRunDir.workerDrained(d))
    }

    /** The other half of the bug: fresh beat + result means the worker is STILL
     *  running (sitting in its ack barrier), not drained. */
    @Test
    fun `result with a fresh heartbeat is not drained evidence`() {
        val d = dir()
        touch(File(d, ModelExecutionRunDir.FILE_LIVENESS_BEAT))
        touch(File(d, ModelExecutionMailbox.FILE_RESULT))
        assertFalse(ModelExecutionRunDir.workerDrained(d))
    }

    @Test
    fun `terminal is drained evidence`() {
        val d = dir()
        touch(File(d, ModelExecutionRunDir.FILE_TERMINAL))
        assertTrue(ModelExecutionRunDir.workerDrained(d))
    }

    @Test
    fun `terminal wins even with a fresh heartbeat`() {
        val d = dir()
        touch(File(d, ModelExecutionRunDir.FILE_LIVENESS_BEAT))
        touch(File(d, ModelExecutionRunDir.FILE_TERMINAL))
        assertTrue(ModelExecutionRunDir.workerDrained(d))
    }

    @Test
    fun `result plus stale heartbeat is drained evidence (crash tolerance)`() {
        val d = dir()
        touch(
            File(d, ModelExecutionRunDir.FILE_LIVENESS_BEAT),
            ageMs = ModelExecutionRunDir.LIVENESS_STALE_MS + 1_000L,
        )
        touch(File(d, ModelExecutionMailbox.FILE_RESULT))
        assertTrue(ModelExecutionRunDir.workerDrained(d))
    }

    @Test
    fun `result plus missing heartbeat is drained evidence`() {
        val d = dir()
        touch(File(d, ModelExecutionMailbox.FILE_RESULT))
        assertTrue(ModelExecutionRunDir.workerDrained(d))
    }

    @Test
    fun `stale heartbeat without result is not drained evidence`() {
        val d = dir()
        touch(
            File(d, ModelExecutionRunDir.FILE_LIVENESS_BEAT),
            ageMs = ModelExecutionRunDir.LIVENESS_STALE_MS + 1_000L,
        )
        assertFalse(ModelExecutionRunDir.workerDrained(d))
    }

    @Test
    fun `empty dir is not drained evidence`() {
        assertFalse(ModelExecutionRunDir.workerDrained(dir()))
    }

    /** A live worker mid-stream (fresh beat, no result yet) must never be deleted. */
    @Test
    fun `live worker mid-stream is not drained evidence`() {
        val d = dir()
        touch(File(d, ModelExecutionRunDir.FILE_LIVENESS_BEAT))
        assertFalse(ModelExecutionRunDir.workerDrained(d))
    }
}
