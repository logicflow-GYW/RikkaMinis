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
 *  - shutdown request marker
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
    fun `shutdown request marker`() {
        val d = dir()
        assertFalse(ModelExecutionMailbox.shutdownRequested(d))
        ModelExecutionMailbox.writeShutdownRequest(d)
        assertTrue(ModelExecutionMailbox.shutdownRequested(d))
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