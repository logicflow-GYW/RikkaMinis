package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.AgentContentPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1016c ISSUE-01] The agent loop's persistence slice start.
 *
 * Defect: `allToolBlocks` is append-only for the whole run, but five no-tool
 * continuation branches (`continue` with no persist: EOF-stub, length-wall
 * prefill / reminder, verify nudge, residue refill) leave the text they
 * already streamed unpersisted. The next iteration re-captures the per-turn
 * `turnStartBlockIndex` at the CURRENT end of the list, and every persistence
 * slice started there — so the skipped segment never reached the DB: the
 * in-memory bubble showed it, a reload did not.
 *
 * Fix: a run-scoped `AgentLoopState.unpersistedBlockStart` advances ONLY on a
 * successful persist; persistence slices start there, while `rollbackTurnBlocksTo`
 * and the per-turn visibility check keep using `turnStartBlockIndex`.
 *
 * Every expectation is a literal (evidence discipline). The replay below is
 * driven by the slice expression actually present in the production source, so
 * reverting that expression flips this suite red instead of moving with it.
 */
class LoopPersistSliceTest {

    private val enginePath = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/AgentLoopEngine.kt"
    private val statePath = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/AgentLoopState.kt"

    private fun text(id: String, content: String) = AssistantBlock(id = id, kind = "text", content = content)

    // ── §5.1 replay: turn 1 streams text, continues, turn 2 completes ───────

    @Test
    fun `terminal persistence covers the unpersisted continuation prefix`() {
        val engine = readRepoFile(enginePath)
        val sliceStart = terminalSliceStart(engine)

        // Engine bookkeeping, mirrored from the production loop:
        //   turn 0 — streams TURN1-TEXT, hits a continuation branch (continue, no persist)
        //   turn 1 — streams TURN2-TEXT, finishes with no tool call (terminal persist)
        val blocks = mutableListOf<AssistantBlock>()
        var turnStartBlockIndex = 0
        val unpersistedBlockStart = 0 // never advanced: no persist landed yet

        turnStartBlockIndex = blocks.size
        blocks.add(text("text_0_0_0", "TURN1-TEXT"))
        // (continuation branch: no persist, unpersistedBlockStart untouched)

        turnStartBlockIndex = blocks.size
        blocks.add(text("text_1_1_1", "TURN2-TEXT"))

        val start = when (sliceStart) {
            "turnStartBlockIndex" -> turnStartBlockIndex
            "loopState.unpersistedBlockStart" -> unpersistedBlockStart
            else -> throw AssertionError("unrecognised persistence slice start: $sliceStart")
        }

        val texts = buildTurnPartsPure(blocks, start, emptyMap())
            .filterIsInstance<AgentContentPart.Text>()
            .map { it.text }

        assertEquals(listOf("TURN1-TEXT", "TURN2-TEXT"), texts)
    }

    @Test
    fun `every persistence slice reads the run-scoped unpersisted start`() {
        val engine = readRepoFile(enginePath)
        val callLines = engine.lines().filter { it.contains("buildTurnParts(loopState.allToolBlocks,") }
        assertEquals("expected three persistence slices (terminal / early / tool terminal)", 3, callLines.size)
        for (line in callLines) {
            assertTrue(
                "persistence slice must start at the run-scoped unpersisted index: $line",
                line.contains("loopState.allToolBlocks, loopState.unpersistedBlockStart,"),
            )
        }
    }

    @Test
    fun `every recovery path rolls back its own partial blocks`() {
        val engine = readRepoFile(enginePath)
        // Rollback must remove ONLY the failed attempt's partial blocks — a
        // continuation prefix belongs to an earlier turn and must survive.
        //
        // [incr1113 F-05] Role-based, not count-based. The previous assertion
        // was `rollbackSites >= 2` with a comment claiming "a path that silently
        // disappears still trips the floor". It did not: the tree had three
        // sites (context-overshoot / transient retry / provider fallback), so
        // deleting any ONE of them stayed green — the comment was a false claim
        // about the check, and the check is what a reader trusts. Raising the
        // floor to 3 would only replace a false claim with a fragile exact count
        // (the next legitimate recovery path would go red for the wrong reason,
        // and a delete plus an add still cancels out).
        //
        // Each role below must be carried by a rollback site whose ENCLOSING
        // BRANCH mentions the role's marker. Markers are identifiers the branch
        // cannot work without, so a re-spelling keeps them and a deletion drops
        // them. Comments are stripped first: `// rollbackTurnBlocksTo(…)` must
        // not satisfy any of this.
        val code = stripComments(engine)
        assertEquals(
            "one rollback site per recovery path (context-overshoot / transient-retry / " +
                "provider-fallback); a missing role means a failed attempt's partial blocks " +
                "would survive into the retried turn",
            // Alphabetical — the actual list is `.sorted()` so the assertion cannot
            // depend on the order the sites happen to appear in the file.
            listOf("context-overshoot", "provider-fallback", "transient-retry"),
            rollbackRoles(code).sorted(),
        )
        // …and no rollback may use the run-scoped index instead.
        assertFalse(
            "a rollback uses the run-scoped unpersisted start — it would delete an " +
                "earlier turn's unpersisted prefix",
            code.contains("rollbackTurnBlocksTo(loopState.allToolBlocks, loopState.unpersistedBlockStart)"),
        )
        assertTrue(
            "per-turn visibility still scopes to this turn",
            code.contains("val turnVisibleBlocks = loopState.allToolBlocks.drop(turnStartBlockIndex)"),
        )
        assertFalse(
            "visibility must not switch to the run-scoped start (it would never be empty again)",
            code.contains("val turnVisibleBlocks = loopState.allToolBlocks.drop(loopState.unpersistedBlockStart)"),
        )
    }

    /**
     * The recovery role of every `rollbackTurnBlocksTo(` site in [code].
     *
     * The answer depends on ENCLOSURE — identical call text is a different role
     * in a different branch — so this walks outward from each call to the
     * nearest `if` and looks for the role's marker in that branch's condition
     * and body. `"unclassified"` is returned rather than skipped: a site on no
     * known path is exactly the shape a silent deletion leaves behind.
     */
    private fun rollbackRoles(code: String): List<String> {
        val roles = listOf(
            "context-overshoot" to "overshootRetry",
            "transient-retry" to "retryDelays",
            "provider-fallback" to "FallbackSelected",
        )
        return Regex("rollbackTurnBlocksTo\\s*\\(").findAll(code).map { m ->
            val pos = m.range.first
            // Nearest preceding `if (` whose brace block contains `pos`.
            var role = "unclassified"
            var searchFrom = pos
            while (true) {
                val ifAt = code.lastIndexOf("if (", searchFrom)
                if (ifAt < 0) break
                val open = code.indexOf('{', ifAt)
                if (open in 0 until pos) {
                    var depth = 0
                    var k = open
                    var close = -1
                    while (k < code.length) {
                        if (code[k] == '{') depth++
                        if (code[k] == '}') {
                            depth--
                            if (depth == 0) { close = k; break }
                        }
                        k++
                    }
                    if (close > pos) {
                        val text = code.substring(ifAt, close)
                        val hit = roles.firstOrNull { (_, marker) -> text.contains(marker) }
                        if (hit != null) { role = hit.first; break }
                    }
                }
                searchFrom = ifAt - 1
                if (searchFrom <= 0) break
            }
            role
        }.toList()
    }

    /** Strip `//` and block comments, keeping line structure and literals. */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var inLine = false
        var inBlock = false
        var inString = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else '\u0000'
            when {
                inLine -> {
                    if (c == '\n') { inLine = false; out.append(c) }
                    i++
                }
                inBlock -> {
                    if (c == '*' && next == '/') { inBlock = false; i += 2 } else i++
                }
                inString -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < src.length) { out.append(src[i + 1]); i += 2 }
                    else {
                        if (c == '"') inString = false
                        i++
                    }
                }
                c == '/' && next == '/' -> { inLine = true; i += 2 }
                c == '/' && next == '*' -> { inBlock = true; i += 2 }
                c == '"' -> { inString = true; out.append(c); i++ }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    // ── ISSUE-01/B: the no-tool terminal backfills dbMessageId ─────────────

    @Test
    fun `the no-tool terminal backfills the assistant dbMessageId`() {
        val engine = readRepoFile(enginePath)
        val guardBlock = blockAfter(engine, "if (persistedRowId != null) {")
        assertTrue(
            "the terminal persist must advance the unpersisted start on success",
            guardBlock.contains("loopState.unpersistedBlockStart = loopState.allToolBlocks.size"),
        )
        assertTrue(
            "the terminal persist must backfill dbMessageId (same shape as the tool path)",
            guardBlock.contains("it.role == LLMMessage.Role.ASSISTANT && it.dbMessageId == null") &&
                guardBlock.contains(".copy(dbMessageId = persistedRowId)"),
        )
    }

    @Test
    fun `the tool terminal advances the unpersisted start inside its dbId guard`() {
        val engine = readRepoFile(enginePath)
        val guard = "if (assistantDbId != null) {"
        assertTrue("tool-path dbId guard not found", engine.contains(guard))
        val guardBlock = blockAfter(engine, guard)
        assertTrue(
            "the tool terminal must advance the unpersisted start once its row is durable",
            guardBlock.contains("loopState.unpersistedBlockStart = loopState.allToolBlocks.size"),
        )
        assertTrue(
            "the tool-path dbId backfill must stay",
            guardBlock.contains(".copy(dbMessageId = assistantDbId)"),
        )
    }

    @Test
    fun `the queue-interrupt reset keeps the two indices in lockstep`() {
        val engine = readRepoFile(enginePath)
        val clearAt = engine.indexOf("loopState.allToolBlocks.clear()")
        assertTrue("clear site not found", clearAt > 0)
        val after = engine.substring(clearAt, minOf(engine.length, clearAt + 400))
        assertTrue(
            "clearing the block list must reset the unpersisted start to 0",
            after.contains("loopState.unpersistedBlockStart = 0"),
        )
    }

    @Test
    fun `the new state field exists with the documented default`() {
        val state = readRepoFile(statePath)
        assertTrue(
            "AgentLoopState must declare the run-scoped unpersisted start",
            state.contains("var unpersistedBlockStart: Int = 0"),
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** The slice expression used at the no-tool terminal persist, as source text. */
    private fun terminalSliceStart(engine: String): String {
        val line = engine.lines().first { it.contains("val turnParts = host.buildTurnParts(loopState.allToolBlocks,") }
        val m = Regex("buildTurnParts\\(loopState\\.allToolBlocks,\\s*([^,]+),").find(line)
            ?: throw AssertionError("slice expression not found: $line")
        return m.groupValues[1].trim()
    }

    /** Verbatim body of the brace-balanced block that opens with [anchor]. */
    private fun blockAfter(source: String, anchor: String): String {
        val at = source.indexOf(anchor)
        if (at < 0) throw AssertionError("anchor not found: $anchor")
        val open = source.indexOf('{', at)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced block for anchor: $anchor")
    }

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}
