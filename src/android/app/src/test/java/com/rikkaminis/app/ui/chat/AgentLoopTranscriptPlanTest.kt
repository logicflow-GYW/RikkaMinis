package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.sandbox.offload.ChatStreamJsonl
import com.rikkaminis.app.sandbox.offload.PartialStreamRecovery
import com.rikkaminis.app.sandbox.offload.TranscriptCommit
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [surgery-1120 A-01 review] The ENGINE side of the transcript-commit contract,
 * exercised through the real [AgentLoopEngine] with a real temp run dir.
 *
 * Two rules the first cut left to convention:
 *  1. **A plan that is not on disk must stop the insert.** Otherwise a crash
 *     between the insert and the commit leaves recovery no planned id, and it
 *     inserts the spool a second time. The engine now refuses before writing
 *     the row and settles the spool as abandoned (still recoverable).
 *  2. **A cancelled run is a discard, not a pending commit.** A stop after the
 *     worker wrote DONE left the spool with no marker; the next cold start
 *     would then insert text the user had already abandoned, while the stop
 *     path had already persisted the partial text it showed. The engine now
 *     marks those spools "delivered, nothing to insert".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopTranscriptPlanTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun provider() = ScriptedProvider(model = LLMModel(id = "m", displayName = "m", provider = "test"))

    private fun engine(h: FakeAgentLoopHost) = AgentLoopEngine(
        h,
        ChatAgentTraceObserver(
            traceDirResolver = { null }, wallClockMs = { 0L }, monotonicClockMs = { 0L }, warn = { _, _ -> },
        ),
    )

    private fun done(text: String) = TurnScript(
        listOf(LLMStreamChunk.Started, LLMStreamChunk.Text(text), LLMStreamChunk.Finished("stop")),
    )

    private fun tempRunDir(): File =
        File.createTempFile("run-plan", "").also { it.delete(); it.mkdirs() }

    private fun spool(dir: File, turnId: String) {
        File(dir, "stream.jsonl").writeText(
            listOf("""{"t":"text","v":"answer"}""", ChatStreamJsonl.DONE_LINE).joinToString("\n") + "\n",
        )
        TranscriptCommit.writeMeta(dir, "sess-1", turnId, attempt = 1, rowIndex = 0)
        File(dir, "terminal.json").writeText("{}")
        val t = System.currentTimeMillis() - 20 * 60_000L
        dir.listFiles()?.forEach { it.setLastModified(t) }
        dir.setLastModified(t)
    }

    /**
     * Block the durable plan write deterministically (works as root too): the
     * marker's tmp path is occupied by a DIRECTORY, so the rename target's
     * `FileOutputStream` fails. The dir itself is a real, writable run dir.
     */
    private fun breakPlanWrites(dir: File) {
        File(dir, "${TranscriptCommit.FILE_MARKER}.tmp").mkdirs()
    }

    @Test
    fun `a plan that cannot be made durable stops the insert`() = runTest(dispatcher) {
        val h = FakeAgentLoopHost()
        h.scripts.add(done("answer the user"))
        val dir = tempRunDir()
        try {
            h.onStreamCommitHandle = { handle ->
                handle.attachDir(dir)
                breakPlanWrites(dir)
            }

            runCatching { engine(h).runAgentLoop(provider(), null) }

            assertEquals("no row may be written without a durable plan", 0, h.persistedTurns.size)
            assertFalse("the spool must stay recoverable", TranscriptCommit.isCommitted(dir))
            assertFalse(TranscriptCommit.isCommitted(dir) && TranscriptCommit.plannedRowIds(dir).isNotEmpty())
        } finally {
            runCatching { dir.deleteRecursively() }
        }
    }

    @Test
    fun `a cancelled run settles its spool as discarded, not as pending`() = runTest(dispatcher) {
        val h = FakeAgentLoopHost()
        // A bare CancellationException from the flow is what the collector's
        // own cancellation surfaces as (the engine rethrows it as a real job
        // cancel); nothing was delivered to the user, so the stop path owns the
        // text and the spool must not be recovered later.
        h.scripts.add(TurnScript(listOf(LLMStreamChunk.Started), throwAfter = CancellationException()))
        val dir = tempRunDir()
        val turnId = "t-plan-test"
        try {
            spool(dir, turnId)
            h.onStreamCommitHandle = { handle -> handle.attachDir(dir) }

            runCatching { engine(h).runAgentLoop(provider(), null) }

            val marked = TranscriptCommit.isCommitted(dir) ||
                File(dir, PartialStreamRecovery.RECOVERED_FILE).isFile
            assertTrue("a discarded attempt must record the decision on disk", marked)
            assertTrue(
                "the marker must say 'nothing to insert'",
                TranscriptCommit.readMarker(dir)?.messageIds.orEmpty().isEmpty(),
            )
        } finally {
            runCatching { dir.deleteRecursively() }
        }
    }
}