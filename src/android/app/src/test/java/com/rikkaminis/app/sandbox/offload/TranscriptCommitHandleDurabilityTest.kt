package com.rikkaminis.app.sandbox.offload

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [surgery-1120 A-01 review] What happens when the durable marker CANNOT be
 * written.
 *
 * Two silent failures the first cut had:
 *  - `plan()` recorded the row id in memory before the write and returned the
 *    write's boolean, which every caller ignored — so a failed plan looked
 *    exactly like a successful one, and the insert proceeded without the id a
 *    crash would need to avoid duplicating the spool.
 *  - `discard()` ignored the write's boolean and reported "committed" anyway.
 *    The end state (ack + delete) was right, but if the DELETE then failed the
 *    dir stayed with no marker at all — i.e. rolled-back text came back as an
 *    answer on the next start.
 *
 * Both are exercised by making the marker's tmp path unusable (a directory
 * there fails `FileOutputStream` deterministically, including as root — a
 * permission-based fixture does not, since root ignores mode bits).
 */
class TranscriptCommitHandleDurabilityTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        StreamOwnership.clearForTest()
        dir = File.createTempFile("run-durability", "").also { it.delete(); it.mkdirs() }
    }

    @After
    fun tearDown() {
        StreamOwnership.clearForTest()
        runCatching { dir.deleteRecursively() }
    }

    private fun breakMarkerWrites() {
        File(dir, "${TranscriptCommit.FILE_MARKER}.tmp").mkdirs()
    }

    @Test
    fun `a plan that did not reach the disk is not recorded`() {
        val handle = TranscriptCommitHandle("bub-1", "sess-1", attempt = 1, rowIndex = 0)
        handle.attachDir(dir)
        breakMarkerWrites()

        assertFalse("the caller must see the failure", handle.plan("row-1"))
        assertTrue("the spool is still there — an insert would be unrecoverable", handle.spoolPresent())
        assertTrue("nothing was planned", TranscriptCommit.plannedRowIds(dir).isEmpty())
        assertFalse(TranscriptCommit.isCommitted(dir))
    }

    @Test
    fun `a commit without a durable plan abandons instead of claiming success`() = runBlocking {
        val handle = TranscriptCommitHandle("bub-2", "sess-1", attempt = 1, rowIndex = 0)
        handle.attachDir(dir)
        breakMarkerWrites()

        handle.plan("row-2")
        handle.commit("row-2")

        assertEquals(
            "no marker ⇒ the collector must keep the spool",
            TranscriptCommitHandle.Outcome.Abandoned,
            handle.awaitOutcome(200L),
        )
        assertFalse(TranscriptCommit.isCommitted(dir))
    }

    @Test
    fun `discard with an unusable marker still records the decision`() {
        val handle = TranscriptCommitHandle("bub-3", "sess-1", attempt = 1, rowIndex = 0)
        handle.attachDir(dir)
        breakMarkerWrites()

        handle.discard()

        assertFalse("no committed marker could be written", TranscriptCommit.isCommitted(dir))
        assertTrue(
            "the second, independent no-resurrect marker must carry the decision",
            File(dir, PartialStreamRecovery.RECOVERED_FILE).isFile,
        )
        assertEquals(
            PartialStreamRecoveryPolicy.Decision.SKIP_RECOVERED,
            PartialStreamRecoveryPolicy.decide(
                streamLen = 10,
                terminalPresent = true,
                cancelPresent = false,
                recoveredPresent = true,
                hasSessionMeta = true,
                mtimeAgeMs = 600_000L,
                terminalCommitted = false,
                readDone = { true },
            ),
        )
    }
}