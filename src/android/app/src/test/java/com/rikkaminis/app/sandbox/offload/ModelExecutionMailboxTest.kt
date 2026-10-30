package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for the Phase 2 mailbox protocol ([ModelExecutionMailbox]):
 *  - cancel marker + cancel.ack round-trip
 *  - lifecycle state dump round-trip
 *
 * Also tests the stream-failure taxonomy ([ModelExecutionStreamException]):
 *  - 0-chunk worker death is safe to retry/fallback
 *  - has-chunk worker death / stream error must NOT be re-sent (duplicate
 *    answer protection)
 */
class ModelExecutionMailboxTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(): File = tmp.newFolder("run-${System.nanoTime()}")

    @Test
    fun `cancel and ack round trip`() {
        val d = dir()
        assertFalse(File(d, ModelExecutionMailbox.FILE_CANCEL).exists())
        ModelExecutionMailbox.writeCancel(d)
        assertTrue(File(d, ModelExecutionMailbox.FILE_CANCEL).exists())
        assertFalse(File(d, ModelExecutionMailbox.FILE_CANCEL_ACK).exists())
        ModelExecutionMailbox.writeCancelAck(d)
        assertTrue(File(d, ModelExecutionMailbox.FILE_CANCEL_ACK).exists())
    }

    @Test
    fun `death cancel writes the wire cancel AND the death marker`() {
        // [LOOP-1030 R2 M2] Two markers, two audiences: the plain `cancel`
        // stays the wire signal (a thawing frozen worker must still abort),
        // `cancel.death` is the semantic tag recovery + reaper read.
        val d = dir()
        ModelExecutionMailbox.writeCancelDeath(d)
        assertTrue(File(d, ModelExecutionMailbox.FILE_CANCEL).exists())
        assertTrue(File(d, ModelExecutionMailbox.FILE_CANCEL_DEATH).exists())
    }

    @Test
    fun `plain cancel does not write the death marker`() {
        // A user stop must keep discard-by-design semantics — no death tag.
        val d = dir()
        ModelExecutionMailbox.writeCancel(d)
        assertTrue(File(d, ModelExecutionMailbox.FILE_CANCEL).exists())
        assertFalse(File(d, ModelExecutionMailbox.FILE_CANCEL_DEATH).exists())
    }

    @Test
    fun `dead shutdown lever stays deleted`() {
        // [FIX-FW-DEAD-SHUTDOWN-LEVER] The `shutdown` marker was a dead lever:
        // the main process wrote it, the worker's transition accepted the flag
        // and never read it, so "ask :modelservice to drain under memory
        // pressure" freed nothing (0 `shutdown REQUESTED` lines in 3 days of
        // production logs). The marker + its write/read API were DELETED.
        //
        // This pins the deletion, not a behaviour: if anyone re-adds the
        // half-wired pair (writer + reader but no consumer), they must delete
        // this test on purpose — which is the moment to answer "who reads it
        // this time, and which branch does it change?".
        //
        // Java reflection (not KClass.members) on purpose: kotlin-reflect is
        // not guaranteed on the unit-test classpath, and a KotlinReflection-
        // NotSupportedError would be swallowed by a try/catch into a
        // permanently-green no-op test.
        val cls = ModelExecutionMailbox::class.java
        val methods = cls.declaredMethods.map { it.name }
        assertFalse(
            "writeShutdownRequest is back — is there a consumer that acts on it?",
            methods.contains("writeShutdownRequest"),
        )
        assertFalse(
            "shutdownRequested is back — is there a consumer that acts on it?",
            methods.contains("shutdownRequested"),
        )
        assertFalse(
            "FILE_SHUTDOWN marker constant is back — who deletes/consumes the file?",
            cls.declaredFields.map { it.name }.contains("FILE_SHUTDOWN"),
        )
    }

    @Test
    fun `client ack marker`() {
        val d = dir()
        ModelExecutionMailbox.writeClientAck(d)
        assertTrue(File(d, ModelExecutionMailbox.FILE_CLIENT_ACK).exists())
    }

    @Test
    fun `state dump round trips`() {
        val d = dir()
        ModelExecutionMailbox.writeState(d, ModelExecutionWorkerState.ACTIVE, active = 2)
        assertEquals("ACTIVE", ModelExecutionMailbox.readStateName(d))
        assertTrue(ModelExecutionMailbox.readState(d)!!.contains("\"active\":2"))

        ModelExecutionMailbox.writeState(d, ModelExecutionWorkerState.DRAINED, active = 0)
        assertEquals("DRAINED", ModelExecutionMailbox.readStateName(d))
    }

    @Test
    fun `state read on absent file is null`() {
        val d = dir()
        assertEquals(null, ModelExecutionMailbox.readStateName(d))
        assertEquals(null, ModelExecutionMailbox.readState(d))
    }

    @Test
    fun `0-chunk worker death is safe to re-send`() {
        val ex = ModelWorkerDiedException(hadChunks = false)
        assertFalse(ex.hadChunks)
    }

    @Test
    fun `has-chunk worker death must not re-send`() {
        val ex = ModelWorkerDiedException(hadChunks = true)
        assertTrue(ex.hadChunks)
    }

    @Test
    fun `stream error carries chunk state`() {
        val early = ModelStreamErrorException("bad key", hadChunks = false)
        assertFalse(early.hadChunks)
        val late = ModelStreamErrorException("connection reset", hadChunks = true)
        assertTrue(late.hadChunks)
    }
}