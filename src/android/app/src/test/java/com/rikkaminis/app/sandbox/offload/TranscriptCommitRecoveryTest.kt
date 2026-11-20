package com.rikkaminis.app.sandbox.offload

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [surgery-1120 A-01] The transcript-commit protocol, end to end on the JVM.
 *
 * The production recovery scan ([recoverRunDirs]) runs against REAL temp dirs
 * and a fake repository — no re-implemented logic and no shape assertions:
 * the run dir, the `meta.json` identity, the `stream.jsonl` spool, the
 * worker's `terminal.json` and the durable `transcript.json` marker are all
 * real files.
 *
 * What it pins:
 *  - a clean DONE with a terminal but NO committed marker is recoverable (the
 *    pre-fix behaviour dropped it: SKIP_TERMINAL);
 *  - a committed marker is skipped as already delivered;
 *  - a crash between the Room insert and the commit marker inserts NOTHING
 *    (the durable plan carries the row id) and still reclaims the dir;
 *  - the normal path inserts exactly one row, with the planned id.
 */
class TranscriptCommitRecoveryTest {

    private class FakeStore : RecoveryStore {
        val rows = linkedMapOf<String, String>()
        var appendCount = 0
        override suspend fun messageExists(id: String): Boolean = rows.containsKey(id)
        override suspend fun appendMessage(sessionId: String, partsJson: String, stableId: String) {
            appendCount++
            rows[stableId] = partsJson
        }
    }

    private fun tempRoot(): File =
        File.createTempFile("tc-root", "").also { it.delete(); it.mkdirs() }

    private fun runDir(
        root: File,
        name: String,
        sessionId: String,
        turnId: String,
        text: String,
    ): File {
        val dir = File(root, name)
        dir.mkdirs()
        TranscriptCommit.writeMeta(dir, sessionId, turnId, attempt = 0)
        File(dir, "stream.jsonl").writeText(
            listOf(
                """{"t":"text","v":"${text.substring(0, text.length / 2)}"}""",
                """{"t":"text","v":"${text.substring(text.length / 2)}"}""",
                """{"t":"done"}""",
            ).joinToString("\n") + "\n"
        )
        return dir
    }

    private fun writeTerminal(dir: File) {
        File(dir, "terminal.json").writeText("""{"ok":true}""")
    }

    @Test
    fun doneWithoutMarker_isRecoverable() {
        val root = tempRoot()
        val dir = runDir(root, "run-aaa", "sess-1", "bubble-1", "hello world")
        writeTerminal(dir)
        assertFalse("no marker yet", TranscriptCommit.isCommitted(dir))
        assertEquals(
            PartialStreamRecoveryPolicy.Decision.RECOVER,
            PartialStreamRecoveryPolicy.decide(
                streamLen = File(dir, "stream.jsonl").length(),
                terminalPresent = true,
                cancelPresent = false,
                recoveredPresent = false,
                hasSessionMeta = true,
                mtimeAgeMs = 120_000L,
                terminalCommitted = false,
                readDone = { true },
            ),
        )
    }

    @Test
    fun committedMarker_isSkipped() {
        val root = tempRoot()
        val dir = runDir(root, "run-bbb", "sess-1", "bubble-2", "hello world")
        writeTerminal(dir)
        TranscriptCommit.commit(
            dir,
            TranscriptCommit.Marker("sess-1", "bubble-2", listOf("row-2"), committed = true),
        )
        assertTrue("marker must be on disk", TranscriptCommit.isCommitted(dir))
        assertEquals(
            PartialStreamRecoveryPolicy.Decision.SKIP_TERMINAL,
            PartialStreamRecoveryPolicy.decide(
                streamLen = File(dir, "stream.jsonl").length(),
                terminalPresent = true,
                cancelPresent = false,
                recoveredPresent = false,
                hasSessionMeta = true,
                mtimeAgeMs = 120_000L,
                terminalCommitted = true,
                readDone = { true },
            ),
        )
    }

    @Test
    fun crashBetweenInsertAndCommit_insertsNothing() = runBlocking {
        val root = tempRoot()
        val dir = runDir(root, "run-ccc", "sess-1", "bubble-3", "hello world")
        writeTerminal(dir)
        val plannedId = TranscriptCommit.rowIdFor("sess-1", "bubble-3", 0)
        assertTrue(
            "the plan is durable before the insert",
            TranscriptCommit.writePlan(
                dir,
                TranscriptCommit.Marker("sess-1", "bubble-3", listOf(plannedId), committed = false),
            ),
        )
        // The insert landed; the commit did not (process death).
        val store = FakeStore().also { it.rows[plannedId] = "{}" }

        recoverRunDirs(root, store, now = System.currentTimeMillis() + 120_000L)

        assertEquals("the answer must not be inserted twice", 0, store.appendCount)
        assertEquals("exactly one row survives", 1, store.rows.size)
        assertFalse("the dir must be reclaimed", dir.exists())
    }

    @Test
    fun crashAfterTheSecondRow_insertsNothing() = runBlocking {
        val root = tempRoot()
        val dir = runDir(root, "run-ggg", "sess-1", "bubble-7", "hello world")
        writeTerminal(dir)
        // The bubble carried TWO rows (a tool turn + the final text turn); the
        // crash happened after the SECOND one was inserted. Only the durable
        // PLAN carries that index — recomputing row 0 would insert a duplicate
        // under the wrong id.
        val plannedId = TranscriptCommit.rowIdFor("sess-1", "bubble-7", 1)
        assertTrue(
            "the plan is durable",
            TranscriptCommit.writePlan(
                dir,
                TranscriptCommit.Marker("sess-1", "bubble-7", listOf(plannedId), committed = false),
            ),
        )
        val store = FakeStore().also { it.rows[plannedId] = "{}" }

        recoverRunDirs(root, store, now = System.currentTimeMillis() + 120_000L)

        assertEquals("the second row must not be inserted twice", 0, store.appendCount)
        assertEquals("exactly one row survives", 1, store.rows.size)
        assertTrue("the surviving row is the planned one", store.rows.containsKey(plannedId))
    }

    @Test
    fun normalPath_insertsExactlyOneRowWithThePlannedId() = runBlocking {
        val root = tempRoot()
        val dir = runDir(root, "run-ddd", "sess-1", "bubble-4", "hello world")
        writeTerminal(dir)
        val plannedId = TranscriptCommit.rowIdFor("sess-1", "bubble-4", 0)
        TranscriptCommit.writePlan(
            dir,
            TranscriptCommit.Marker("sess-1", "bubble-4", listOf(plannedId), committed = false),
        )
        val store = FakeStore()

        val recovered = recoverRunDirs(root, store, now = System.currentTimeMillis() + 120_000L)

        assertEquals("exactly one row is inserted", 1, store.appendCount)
        assertEquals("the row id is the planned one", setOf(plannedId), store.rows.keys)
        assertTrue(
            "the recovered text carries the answer",
            store.rows[plannedId]!!.contains("hello world"),
        )
        assertFalse("the dir must be reclaimed", dir.exists())
        assertEquals("one run dir was recovered", 1, recovered)
    }

    @Test
    fun handle_planThenCommit_publishesTheOutcome() = runBlocking {
        val root = tempRoot()
        val dir = File(root, "run-eee").also { it.mkdirs() }
        val handle = TranscriptCommitHandle("bubble-5", "sess-1", attempt = 0)
        handle.attachDir(dir)
        val rowId = TranscriptCommit.rowIdFor("sess-1", "bubble-5", 0)
        assertTrue("the plan is durable", handle.plan(rowId))
        assertEquals("an unsettled handle reports no outcome", null, handle.awaitOutcome(50L))
        handle.commit(rowId)
        val outcome = handle.awaitOutcome(1_000L)
        assertTrue("the outcome is committed", outcome is TranscriptCommitHandle.Outcome.Committed)
        assertTrue("the marker is committed on disk", TranscriptCommit.isCommitted(dir))
    }

    @Test
    fun handle_abandon_keepsTheSpoolRecoverable() = runBlocking {
        val root = tempRoot()
        val dir = File(root, "run-fff").also { it.mkdirs() }
        val handle = TranscriptCommitHandle("bubble-6", "sess-1", attempt = 0)
        handle.attachDir(dir)
        handle.abandon()
        val outcome = handle.awaitOutcome(1_000L)
        assertEquals(
            "an abandoned attempt has no committed marker",
            TranscriptCommitHandle.Outcome.Abandoned,
            outcome,
        )
        assertFalse("no marker means the spool stays recoverable", TranscriptCommit.isCommitted(dir))
    }

    @Test
    fun rowId_isStableAcrossProcessesAndDiffersPerRow() {
        val first = TranscriptCommit.rowIdFor("sess-1", "bubble-1", 0)
        val second = TranscriptCommit.rowIdFor("sess-1", "bubble-1", 0)
        val other = TranscriptCommit.rowIdFor("sess-1", "bubble-1", 1)
        val otherSession = TranscriptCommit.rowIdFor("sess-2", "bubble-1", 0)
        assertEquals("the id must be recomputable", first, second)
        assertTrue("rows must differ", first != other)
        assertTrue("sessions must differ", first != otherSession)
    }

    @Test
    fun handle_discard_commitsWithNoRowAndSkipsRecovery() = runBlocking {
        val root = tempRoot()
        val dir = runDir(root, "run-hhh", "sess-1", "bubble-8", "rolled back")
        writeTerminal(dir)
        val handle = TranscriptCommitHandle("bubble-8", "sess-1", attempt = 0)
        handle.attachDir(dir)
        handle.discard()
        assertTrue("a rolled-back attempt is settled committed", TranscriptCommit.isCommitted(dir))
        val store = FakeStore()
        val recovered = recoverRunDirs(root, store, now = System.currentTimeMillis() + 120_000L)
        assertEquals("a rolled-back attempt must not be resurrected", 0, store.appendCount)
        // A discarded bubble is marked committed, so recovery SKIPS it (the
        // collector / orphan reaper owns the delete); what matters is that no
        // row is resurrected.
        assertEquals("nothing was inserted", 0, store.appendCount)
        assertEquals("recovery did not treat the bubble as recoverable", 0, recovered)
    }

    @Test
    fun lengthWallSegments_joinIntoOneRow() = runBlocking {
        val root = tempRoot()
        // Two attempts of the SAME bubble (a length-wall continuation streams
        // the head and the tail into separate run dirs; the live path folds
        // them into ONE row).
        val head = runDir(root, "run-i1", "sess-1", "bubble-9", "The head. ")
        File(head, "meta.json").writeText(
            """{"session_id":"sess-1","turn_id":"bubble-9","attempt":0}"""
        )
        val tail = runDir(root, "run-i2", "sess-1", "bubble-9", "The tail.")
        File(tail, "meta.json").writeText(
            """{"session_id":"sess-1","turn_id":"bubble-9","attempt":1}"""
        )
        writeTerminal(head); writeTerminal(tail)
        val store = FakeStore()
        val recovered = recoverRunDirs(root, store, now = System.currentTimeMillis() + 120_000L)
        assertEquals("one row for the whole bubble", 1, store.appendCount)
        assertEquals("one bubble recovered", 1, recovered)
        val body = store.rows.values.first()
        assertTrue("the head is present", body.contains("The head."))
        assertTrue("the tail is present", body.contains("The tail."))
        assertFalse("both dirs reclaimed", head.exists() || tail.exists())
    }
}
