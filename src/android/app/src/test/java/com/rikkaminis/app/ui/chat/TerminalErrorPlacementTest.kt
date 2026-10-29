package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.AgentContentPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1015 BUG-01b] A turn's terminal error banner must land on THIS
 * turn's assistant row.
 *
 * The mechanism being pinned: `setInlineError` persists through
 * `ChatDao.updateLastAssistantError`, whose SQL targets the session's LAST
 * assistant row. A failure branch that runs before this turn persists a row
 * therefore labels the PREVIOUS turn — and for the empty-output failures
 * (`persistAssistantTurn` refuses an empty part list) there is no row of this
 * turn to address at all, so the banner survives a reload as a permanent
 * mislabel of a turn that succeeded.
 *
 * Coverage boundary: the Room SQL itself cannot run on the JVM in this
 * project, so [the last-assistant rule] pins the DAO statement and
 * [MessageTable] models exactly that statement's semantics. Everything the
 * engine actually decides — the filler row, and whether a banner may be
 * surfaced at all — comes from the production
 * [TerminalErrorPlacement], not from the model.
 */
class TerminalErrorPlacementTest {

    // ── the two production decisions ──────────────────────────────────────

    @Test
    fun `a banner for a turn with nothing to persist writes the empty filler row`() {
        assertEquals(
            listOf(AgentContentPart.Text("")),
            TerminalErrorPlacement.partsForTerminalError(
                parts = emptyList(),
                pendingInlineError = "stream interrupted",
            ),
        )
    }

    @Test
    fun `no banner means no filler row`() {
        assertEquals(
            emptyList<AgentContentPart>(),
            TerminalErrorPlacement.partsForTerminalError(
                parts = emptyList(),
                pendingInlineError = null,
            ),
        )
    }

    @Test
    fun `a turn with real parts keeps them untouched`() {
        val parts = listOf(AgentContentPart.Text("partial answer"))
        assertEquals(
            parts,
            TerminalErrorPlacement.partsForTerminalError(parts, pendingInlineError = "banner"),
        )
        assertEquals(
            parts,
            TerminalErrorPlacement.partsForTerminalError(parts, pendingInlineError = null),
        )
    }

    @Test
    fun `a banner is surfaced only once this turn's row exists`() {
        assertEquals("banner", TerminalErrorPlacement.terminalErrorToSurface("banner", "row-2"))
        // No row of this turn → surfacing would persist onto the previous
        // turn's row. This is the arm the fix adds.
        assertEquals(null, TerminalErrorPlacement.terminalErrorToSurface("banner", null))
        assertEquals(null, TerminalErrorPlacement.terminalErrorToSurface(null, "row-2"))
        assertEquals(null, TerminalErrorPlacement.terminalErrorToSurface(null, null))
    }

    // ── the two-turn sequence ─────────────────────────────────────────────

    @Test
    fun `turn 1 keeps a clean error field when turn 2 fails on an empty stream`() {
        val db = MessageTable()

        // Turn 1 — an ordinary reply: parts persisted, no banner queued.
        var pending: String? = null
        var rowId = db.append(TerminalErrorPlacement.partsForTerminalError(
            listOf(AgentContentPart.Text("hello")), pending,
        ))
        TerminalErrorPlacement.terminalErrorToSurface(pending, rowId)?.let { db.markLastAssistantError(it) }
        assertEquals("r1", rowId)
        assertEquals(null, db.errorOf("r1"))

        // Turn 2 — the stream dies before emitting anything: empty parts AND a
        // banner. Before the fix the banner went out first and, because this
        // turn never wrote a row, landed on r1.
        pending = "stream interrupted"
        rowId = db.append(TerminalErrorPlacement.partsForTerminalError(emptyList(), pending))
        TerminalErrorPlacement.terminalErrorToSurface(pending, rowId)?.let { db.markLastAssistantError(it) }

        assertEquals("r2", rowId)
        assertEquals("turn 1 must stay clean", null, db.errorOf("r1"))
        assertEquals("turn 2 carries the banner", "stream interrupted", db.errorOf("r2"))
    }

    @Test
    fun `pre-fix arm - surfacing before the persist mislabels the previous turn`() {
        val db = MessageTable()
        // Turn 1 persisted normally.
        val r1 = db.append(listOf(AgentContentPart.Text("hello")))
        assertEquals("r1", r1)

        // Turn 2, OLD order: setInlineError first (it targets the last
        // existing assistant row), the persist after — and for an empty stream
        // there is no row to persist, so nothing ever corrects it.
        db.markLastAssistantError("stream interrupted")
        val r2 = db.append(TerminalErrorPlacement.partsForTerminalError(emptyList(), null))

        assertEquals(null, r2)
        assertEquals("this is the bug the fix removes", "stream interrupted", db.errorOf("r1"))
    }

    // ── anchors: the SQL rule and the engine's call order ─────────────────

    @Test
    fun `the last-assistant rule the fix relies on is still the DAO's`() {
        val dao = readRepoFile("app/src/main/java/com/rikkaminis/app/data/db/ChatDao.kt")
        assertTrue(
            "updateLastAssistantError must keep targeting the newest assistant row",
            dao.contains("UPDATE messages SET error_info = :errorInfo") &&
                dao.contains("WHERE session_id = :sessionId AND role = 'assistant'") &&
                dao.contains("ORDER BY sort_order DESC LIMIT 1"),
        )
    }

    @Test
    fun `no turn-level banner is written before its own row is persisted`() {
        val lines = readRepoFile(ENGINE).split("\n")
        val spans = assertBannersFollowTheirPersist(lines)
        // Self-check: the probe must really be looking at the engine's banner
        // sites, and at least one of them must be a deferred (post-tail) one.
        // [FIX-R4-Q F-01] Floor 4 -> 3: F-01 moved the turn-tail persist BELOW
        // the continuation `continue`s, so the two fall-through banners
        // (empty-response hint, EOF give-up) now reach setInlineError through
        // the deferred slot this probe's `second` component checks — a direct
        // write at their branch sites is exactly what the negative control
        // below rejects. The three remaining direct sites are the two
        // "persist an empty row first" branches and the tail flush.
        assertTrue("expected the engine's banner sites, found ${spans.size}", spans.size >= 3)
        assertTrue(
            "expected at least one banner deferred past the turn tail",
            spans.any { it.second },
        )
    }

    @Test
    fun `negative control - a banner before this turn's row is rejected`() {
        val lines = readRepoFile(ENGINE).split("\n").toMutableList()
        // Re-create the pre-fix shape: surface the length-wall banner inline,
        // in a branch that reaches the turn tail without having persisted.
        val idx = lines.indexOfFirst { it.trim() == "loopState.pendingInlineError = host.string(R.string.error_output_truncated_repeated)" }
        assertTrue("anchor not found", idx > 0)
        val indent = lines[idx].takeWhile { it == ' ' }
        lines.add(idx + 1, "${indent}host.setInlineError(host.string(R.string.error_output_truncated_repeated))")
        expectProbeFailure(lines)
    }

    @Test
    fun `negative control - a queue site below the flush is rejected`() {
        val lines = readRepoFile(ENGINE).split("\n").toMutableList()
        val flushIdx = lines.indexOfFirst {
            it.contains(".terminalErrorToSurface(pendingInlineError, persistedRowId)")
        }
        assertTrue("flush anchor not found", flushIdx > 0)
        // The mistake this half of the probe exists to prevent: the
        // empty-response hint block sits BELOW the flush point, so queuing
        // there would drop the banner instead of deferring it.
        lines.add(flushIdx + 12, "                    loopState.pendingInlineError = \"empty response\"")
        expectProbeFailure(lines)
    }

    @Test
    fun `negative control - a probe that finds no banner fails`() {
        val lines = readRepoFile(ENGINE).split("\n")
            .filterNot { it.contains("host.setInlineError(") }
        expectProbeFailure(lines)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun expectProbeFailure(lines: List<String>) {
        var caught = false
        try {
            assertBannersFollowTheirPersist(lines)
        } catch (_: AssertionError) {
            caught = true
        }
        assertTrue("negative control: the probe passed on mutated source", caught)
    }

    /**
     * Every `host.setInlineError(` in the engine must be preceded by a persist
     * for the SAME turn: either a persist within [LOCAL_PERSIST_WINDOW] lines
     * above (the two branches that write their empty row up front), or the
     * turn-tail persist, which every fall-through failure branch reaches
     * before the banner is flushed.
     *
     * Returns each banner's line index plus whether it was the deferred kind.
     */
    private fun assertBannersFollowTheirPersist(lines: List<String>): List<Pair<Int, Boolean>> {
        // [R5-C2] The two empty-reply terminals now write their row through the
        // shared `flushUnpersistedTurnRow(` helper instead of calling
        // `persistAssistantTurn` inline — the helper's body is that same call,
        // plus the un-persisted continuation slice those terminals used to
        // drop. Counting it as a persist keeps this probe measuring what it
        // was written to measure ("does a row for THIS turn exist before the
        // banner?"), not the spelling of the call.
        val persists = lines.withIndex()
            .filter { (_, l) ->
                l.contains("host.persistAssistantTurn(") || l.contains("flushUnpersistedTurnRow(")
            }
            .map { it.index }
        val banners = lines.withIndex().filter { (_, l) -> l.contains("host.setInlineError(") }.map { it.index }
        assertTrue("probe found no banner sites — it is not looking at the engine", banners.isNotEmpty())

        // The turn tail: the one persist whose payload goes through the
        // placement decision. Its presence IS half of the mechanism under
        // test, so a missing anchor is a failure, not a skip.
        val tailPersist = persists.firstOrNull { p ->
            lines.subList(maxOf(0, p - TAIL_ANCHOR_WINDOW), minOf(lines.size, p + TAIL_ANCHOR_WINDOW))
                .any { it.contains("TerminalErrorPlacement.partsForTerminalError(") }
        }
        assertTrue(
            "no persist routes its parts through TerminalErrorPlacement.partsForTerminalError — " +
                "the turn tail, and with it the deferral, is gone",
            tailPersist != null,
        )

        val out = mutableListOf<Pair<Int, Boolean>>()
        for (b in banners) {
            val local = persists.any { it < b && b - it <= LOCAL_PERSIST_WINDOW }
            val deferred = b > tailPersist!!
            assertTrue(
                "banner at line ${b + 1} is written before this turn's row exists " +
                    "(no persist above it, and it sits before the turn tail at line ${tailPersist + 1})",
                local || deferred,
            )
            out += b to deferred
        }

        // The flush point sits UPSTREAM of the empty-response hint block, so a
        // queue site below it is never surfaced — the banner would be dropped
        // silently rather than deferred. Pin the order.
        val flushIdx = lines.indexOfFirst {
            it.contains(".terminalErrorToSurface(pendingInlineError, persistedRowId)")
        }
        assertTrue("deferred flush site not found in the turn tail", flushIdx > 0)
        val queueSites = lines.withIndex()
            .filter { (_, l) -> l.trim().startsWith("loopState.pendingInlineError = ") && !l.trim().endsWith("= null") }
            .map { it.index }
        assertTrue("probe found no queue sites — it is not looking at the engine", queueSites.isNotEmpty())
        for (q in queueSites) {
            assertTrue(
                "queue site at line ${q + 1} sits below the flush at line ${flushIdx + 1} — " +
                    "the banner would never be surfaced",
                q < flushIdx,
            )
        }
        return out
    }

    /**
     * Models `MessageTable` + the two writes the engine performs, with the
     * DAO's documented semantics: append a row; stamp the error onto the
     * newest assistant row.
     */
    private class MessageTable {
        private val rows = mutableListOf<Pair<String, String?>>() // id to error

        /** Mirrors `persistAssistantTurn`: an empty part list inserts nothing. */
        fun append(parts: List<AgentContentPart>): String? {
            if (parts.isEmpty()) return null
            val id = "r${rows.size + 1}"
            rows += id to null
            return id
        }

        /** Mirrors `updateLastAssistantError`: newest assistant row, or no-op. */
        fun markLastAssistantError(error: String?) {
            val last = rows.indexOfLast { true }
            if (last >= 0) rows[last] = rows[last].first to error
        }

        fun errorOf(rowId: String): String? = rows.first { it.first == rowId }.second
    }

    /** Walks up from user.dir so the test runs from the module dir or the repo root. */
    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    private companion object {
        const val ENGINE = "app/src/main/java/com/rikkaminis/app/ui/chat/AgentLoopEngine.kt"

        /** Lines a banner may sit below its own branch's persist call. */
        const val LOCAL_PERSIST_WINDOW = 12

        /** Lines either side of a persist call scanned for the tail marker. */
        const val TAIL_ANCHOR_WINDOW = 4
    }
}
