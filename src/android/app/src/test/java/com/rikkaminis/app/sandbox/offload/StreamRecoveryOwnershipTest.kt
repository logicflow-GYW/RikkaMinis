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
 * [surgery-1120 A-01 review] Cold-start recovery's OWNERSHIP + IDENTITY rules,
 * on the REAL sources against REAL temp dirs.
 *
 * The defects these pin (each one was reachable before this pass):
 *  - a dir with no `stream.jsonl` threw inside the decision (`readLines()` ran
 *    before any eligibility gate) and stayed in the staging root forever;
 *  - the delayed second pass ran while the app streamed, and a dir's mtime does
 *    not move when the worker appends inside it — a live run looked abandoned
 *    and its DONE prefix could be inserted while the engine was still going to
 *    write the real row;
 *  - a worker that was alive but had no fresh beat (hidepid ⇒ `/proc` unreadable)
 *    was indistinguishable from a dead one;
 *  - a bubble with several assistant rows (tool turn + final text) was grouped
 *    by bubble alone, folding the later row's spool into row 0;
 *  - a terminal dir recovery will never take was still "reserved" for it by the
 *    reaper, so nothing ever reclaimed it.
 */
class StreamRecoveryOwnershipTest {

    private lateinit var work: File
    private lateinit var root: File
    private lateinit var proc: File

    @Before
    fun setUp() {
        StreamOwnership.clearForTest()
        work = File.createTempFile("rec-owner", "").also { it.delete(); it.mkdirs() }
        root = File(work, StreamOwnership.STAGING_ROOT).also { it.mkdirs() }
        proc = File(work, "proc").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        StreamOwnership.clearForTest()
        runCatching { work.deleteRecursively() }
    }

    private class FakeStore : RecoveryStore {
        val rows = linkedMapOf<String, String>()
        var appendCount = 0
        override suspend fun messageExists(id: String): Boolean = rows.containsKey(id)
        override suspend fun appendMessage(sessionId: String, partsJson: String, stableId: String) {
            appendCount++
            rows[stableId] = partsJson
        }
    }

    private fun runDir(
        name: String,
        session: String = "sess-1",
        turn: String = "bub-1",
        text: String = "hello",
        attempt: Int = 0,
        rowIndex: Int? = 0,
        done: Boolean = true,
        terminal: Boolean = true,
    ): File {
        val d = File(root, name).also { it.mkdirs() }
        TranscriptCommit.writeMeta(d, session, turn, attempt, rowIndex)
        val lines = buildList {
            add("""{"t":"started"}""")
            if (text.isNotEmpty()) add("""{"t":"text","v":"$text"}""")
            if (done) add(ChatStreamJsonl.DONE_LINE)
        }
        File(d, ModelExecutionService.STREAM_FILE).writeText(lines.joinToString("\n") + "\n")
        if (terminal) ModelExecutionRunDir.writeTerminal(d)
        age(d)
        return d
    }

    /** Past the 30 s grace AND the reaper's 10-minute window. */
    private fun age(d: File) {
        val t = System.currentTimeMillis() - 20 * 60_000L
        d.listFiles()?.forEach { it.setLastModified(t) }
        d.setLastModified(t)
    }

    private fun recover(store: RecoveryStore): Int =
        runBlocking { recoverRunDirs(root, store, System.currentTimeMillis(), proc) }

    private fun writeFakeProc(pid: Int, comm: String, uid: Int = 10123, startTicks: Long = 424242L) {
        val d = File(proc, pid.toString()).also { it.mkdirs() }
        File(d, "comm").writeText("$comm\n")
        File(d, "status").writeText("Name:\t$comm\nUid:\t$uid\t$uid\t$uid\t$uid\n")
        val tokens = mutableListOf("S")
        repeat(18) { tokens.add("0") }
        tokens.add(startTicks.toString())
        repeat(6) { tokens.add("0") }
        File(d, "stat").writeText("$pid ($comm) ${tokens.joinToString(" ")}\n")
    }

    // ── cost / crash shapes ───────────────────────────────────────────────

    @Test
    fun `terminal dir without a stream file is reclaimed instead of throwing`() {
        val d = File(root, "run-nostream").also { it.mkdirs() }
        TranscriptCommit.writeMeta(d, "sess-1", "bub-1", 0, 0)
        ModelExecutionRunDir.writeTerminal(d)
        age(d)

        val store = FakeStore()
        assertEquals(0, recover(store))
        assertEquals(0, store.appendCount)
        assertFalse("an unattributable/empty dir must not accumulate forever", d.exists())
    }

    @Test
    fun `an oversized frame is dropped and counted, never loaded whole`() {
        val d = File(root, "run-huge-huge-huge").also { it.mkdirs() }
        val spool = File(d, ModelExecutionService.STREAM_FILE)
        spool.writeText("x".repeat(StreamScan.MAX_LINE_BYTES + 4096))

        val scan = StreamScan.scan(spool)
        assertEquals(1, scan.droppedFrames)
        assertEquals("", scan.text)
    }

    // ── ownership ─────────────────────────────────────────────────────────

    @Test
    fun `a dir this process still owns is never recovered`() {
        val d = runDir("run-live")
        StreamOwnership.register(d)

        val store = FakeStore()
        assertEquals(0, recover(store))
        assertEquals("a live stream's spool must not be inserted", 0, store.appendCount)
        assertTrue(d.exists())
    }

    @Test
    fun `a fresh worker heartbeat keeps the dir out of recovery`() {
        val d = runDir("run-beat")
        ModelExecutionRunDir.touchLivenessBeat(d)

        val store = FakeStore()
        assertEquals(0, recover(store))
        assertEquals(0, store.appendCount)
        assertTrue(d.exists())
    }

    @Test
    fun `a locally visible worker pid keeps the dir out of recovery`() {
        val d = runDir("run-pid")
        writeFakeProc(pid = 4321, comm = "com.rikkaminis.app:modelservice")
        val ref = ModelExecutionRunDir.WorkerProcessRef(
            pid = 4321,
            runId = d.name.removePrefix("run-"),
            nonce = "n",
            processName = "com.rikkaminis.app:modelservice",
            startedAtMs = 0L,
            procStartTicks = 424242L,
            uid = 10123,
        )
        assertTrue(ModelExecutionRunDir.writeWorkerPid(d, ref))

        val store = FakeStore()
        assertEquals(0, recover(store))
        assertEquals(0, store.appendCount)
        assertTrue(d.exists())
        assertTrue(ModelExecutionRunDir.workerMayWrite(d, d.name.removePrefix("run-"), proc))
    }

    @Test
    fun `a dir created after the boot baseline is not a candidate`() {
        StreamOwnership.recordBootBaseline(root) // snapshot taken before any stream
        val d = runDir("run-post-boot")

        val store = FakeStore()
        assertEquals(0, recover(store))
        assertEquals(0, store.appendCount)
        assertTrue("a dir born after boot belongs to a live/unwinding turn", d.exists())
    }

    @Test
    fun `a dir in the boot baseline is still recovered`() {
        val d = runDir("run-pre-boot")
        StreamOwnership.recordBootBaseline(root)

        val store = FakeStore()
        assertEquals(1, recover(store))
        assertEquals(1, store.appendCount)
        assertFalse(d.exists())
    }

    // ── the pure decision table (one arm per ownership fact) ─────────────

    private fun decide(
        ownerLive: Boolean = false,
        workerMayWrite: Boolean = false,
        ageMs: Long = 600_000L,
        terminal: Boolean = true,
        terminalCommitted: Boolean = false,
        done: Boolean = true,
    ) = PartialStreamRecoveryPolicy.decide(
        streamLen = 128,
        terminalPresent = terminal,
        cancelPresent = false,
        recoveredPresent = false,
        hasSessionMeta = true,
        mtimeAgeMs = ageMs,
        terminalCommitted = terminalCommitted,
        readDone = { done },
        ownerLive = ownerLive,
        workerMayWrite = workerMayWrite,
    )

    @Test
    fun `the decision skips a dir this process owns`() {
        assertEquals(
            "a live collector's dir is not a recovery candidate",
            PartialStreamRecoveryPolicy.Decision.SKIP_OWNED,
            decide(ownerLive = true),
        )
    }

    @Test
    fun `the decision skips a dir a worker may still write`() {
        assertEquals(
            "a worker that can still append is not an orphan",
            PartialStreamRecoveryPolicy.Decision.SKIP_ACTIVE,
            decide(workerMayWrite = true),
        )
    }

    @Test
    fun `the decision recovers a clean uncommitted DONE`() {
        assertEquals(
            PartialStreamRecoveryPolicy.Decision.RECOVER,
            decide(),
        )
    }

    // ── identity ──────────────────────────────────────────────────────────

    @Test
    fun `two rows of one bubble recover into two rows`() {
        val r0 = runDir("run-r0", turn = "bub-multi", text = "tool turn text", attempt = 0, rowIndex = 0)
        val r1 = runDir("run-r1", turn = "bub-multi", text = "final text", attempt = 1, rowIndex = 1)

        val store = FakeStore()
        assertEquals(2, recover(store))
        val id0 = TranscriptCommit.rowIdFor("sess-1", "bub-multi", 0)
        val id1 = TranscriptCommit.rowIdFor("sess-1", "bub-multi", 1)
        assertEquals(setOf(id0, id1), store.rows.keys)
        assertFalse("row 0 must not carry row 1's text", store.rows[id0]!!.contains("final text"))
        assertTrue(store.rows[id1]!!.contains("final text"))
        assertFalse(r0.exists() || r1.exists())
    }

    @Test
    fun `length-wall segments of one row still join`() {
        val head = runDir("run-h", turn = "bub-wall", text = "The head. ", attempt = 1, rowIndex = 0)
        val tail = runDir("run-t", turn = "bub-wall", text = "The tail.", attempt = 2, rowIndex = 0)

        val store = FakeStore()
        assertEquals(1, recover(store))
        val body = store.rows.values.single()
        assertTrue(body.indexOf("The head.") < body.indexOf("The tail."))
        assertFalse(head.exists() || tail.exists())
    }

    @Test
    fun `a dir from before the row index keeps the legacy single-row join`() {
        val a = runDir("run-old1", turn = "bub-old", text = "one ", attempt = 0, rowIndex = null)
        val b = runDir("run-old2", turn = "bub-old", text = "two", attempt = 1, rowIndex = null)

        val store = FakeStore()
        assertEquals(1, recover(store))
        assertEquals(setOf(TranscriptCommit.rowIdFor("sess-1", "bub-old", 0)), store.rows.keys)
        assertFalse(a.exists() || b.exists())
    }

    @Test
    fun `a durable plan id wins over the recomputed row id`() {
        val d = runDir("run-plan", turn = "bub-plan", text = "planned", rowIndex = 2)
        val planned = TranscriptCommit.rowIdFor("sess-1", "bub-plan", 7)
        TranscriptCommit.writePlan(
            d,
            TranscriptCommit.Marker("sess-1", "bub-plan", listOf(planned), committed = false),
        )
        age(d)

        val store = FakeStore()
        assertEquals(1, recover(store))
        assertEquals(setOf(planned), store.rows.keys)
    }

    @Test
    fun `a crash between insert and commit inserts nothing`() {
        val d = runDir("run-crash", turn = "bub-crash", text = "already inserted")
        val planned = TranscriptCommit.rowIdFor("sess-1", "bub-crash", 0)
        TranscriptCommit.writePlan(d, TranscriptCommit.Marker("sess-1", "bub-crash", listOf(planned), false))
        age(d)
        val store = FakeStore().also { it.rows[planned] = "{}" }

        recover(store)
        assertEquals(0, store.appendCount)
        assertEquals(1, store.rows.size)
        assertFalse(d.exists())
    }

    // ── old contracts ─────────────────────────────────────────────────────

    @Test
    fun `a committed dir is skipped and left to its owner`() {
        val d = runDir("run-done", turn = "bub-done", text = "delivered")
        val id = TranscriptCommit.rowIdFor("sess-1", "bub-done", 0)
        TranscriptCommit.commit(d, TranscriptCommit.Marker("sess-1", "bub-done", listOf(id), true))
        age(d)

        val store = FakeStore()
        assertEquals(0, recover(store))
        assertEquals(0, store.appendCount)
        assertTrue(d.exists())
    }

    @Test
    fun `a user stop is still discarded`() {
        val d = runDir("run-stop", turn = "bub-stop", text = "stopped")
        ModelExecutionMailbox.writeCancel(d)
        age(d)

        val store = FakeStore()
        assertEquals(0, recover(store))
        assertEquals(0, store.appendCount)
        assertTrue(d.exists())
    }

    @Test
    fun `a death-cancelled run is still recovered`() {
        val d = runDir("run-death", turn = "bub-death", text = "half answer")
        ModelExecutionMailbox.writeCancelDeath(d)
        age(d)

        val store = FakeStore()
        assertEquals(1, recover(store))
        assertTrue(store.rows.values.single().contains("half answer"))
        assertFalse(d.exists())
    }

    @Test
    fun `an error terminal is skipped by recovery and reclaimed by the reaper`() {
        val d = runDir("run-err-err-err", turn = "bub-err", text = "boom", done = false, terminal = true)
        File(d, ModelExecutionService.STREAM_FILE)
            .appendText(ChatStreamJsonl.errorLine("stream_failed", null) + "\n")
        age(d)

        val store = FakeStore()
        assertEquals(0, recover(store))
        assertEquals(0, store.appendCount)
        assertTrue(d.exists())
        assertEquals(1, ModelExecutionOrphanReaper.reapOrphansIn(root))
        assertFalse(d.exists())
    }

    // ── the reaper half ───────────────────────────────────────────────────

    @Test
    fun `the reaper leaves a dir this process owns`() {
        val d = runDir("run-reap-live", turn = "bub-rl", text = "hi")
        val id = TranscriptCommit.rowIdFor("sess-1", "bub-rl", 0)
        TranscriptCommit.commit(d, TranscriptCommit.Marker("sess-1", "bub-rl", listOf(id), true))
        age(d)
        StreamOwnership.register(d)

        assertEquals(0, ModelExecutionOrphanReaper.reapOrphansIn(root))
        assertTrue(d.exists())
    }

    @Test
    fun `the reaper leaves an uncommitted clean spool to recovery`() {
        val d = runDir("run-reap-uncommitted", turn = "bub-ru", text = "hi")

        assertEquals(0, ModelExecutionOrphanReaper.reapOrphansIn(root))
        assertTrue(d.exists())
        val store = FakeStore()
        assertEquals(1, recover(store))
        assertFalse(d.exists())
    }

    @Test
    fun `the reaper still reclaims a delivered dir with a dead worker`() {
        val d = runDir("run-reap-done", turn = "bub-rd", text = "hi")
        val id = TranscriptCommit.rowIdFor("sess-1", "bub-rd", 0)
        TranscriptCommit.commit(d, TranscriptCommit.Marker("sess-1", "bub-rd", listOf(id), true))
        age(d)

        assertEquals(1, ModelExecutionOrphanReaper.reapOrphansIn(root))
        assertFalse(d.exists())
    }

    // ── one delete entry point ────────────────────────────────────────────

    @Test
    fun `delete refuses a path outside the staging root`() {
        val outside = File(work, "run-outside").also { it.mkdirs() }
        File(outside, "x").writeText("x")
        assertFalse(ModelExecutionRunDir.deleteIfUnowned(outside, root, "outside", proc))
        assertTrue(outside.exists())
    }

    @Test
    fun `delete refuses while the worker beats`() {
        val d = runDir("run-keep", turn = "bub-keep", text = "hi")
        ModelExecutionRunDir.touchLivenessBeat(d)
        assertFalse(ModelExecutionRunDir.deleteIfUnowned(d, root, d.name.removePrefix("run-"), proc))
        assertTrue(d.exists())
    }
}