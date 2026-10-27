package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1019 ISSUE-02] Two defects of one root cause: the PENDING →
 * RUNNING flip sat in Pass 1, ABOVE the loop-detector and preflight gates.
 *
 *  1. a call refused with CRITICAL was already published as RUNNING and that
 *     refusal path only edited the block in memory, so the tool card kept its
 *     spinner (the dedupe / truncation / preflight refusals all publish);
 *  2. the sequential branch's "waiting for previous tool(s)" cue is guarded on
 *     PENDING, and Pass 1 had already flipped every queued call — the guard
 *     matched nothing and the cue was dead code.
 *
 * The value-level expectations below are written literals, never derived from
 * [ToolPassTransitionPolicy], so a mutation cannot move an expectation along with
 * it and stay green. The placement contract itself (who publishes, in which
 * order) cannot be expressed as a value at all — those are the source-slice
 * tests.
 */
class ToolPassTransitionPolicyTest {

    private val sourcePath = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/AgentLoopEngine.kt"

    // ── Value level: the cue is for calls QUEUED behind another ──────────────

    @Test
    fun `a single-call batch has nobody to wait for`() {
        assertEquals(emptyList<Int>(), ToolPassTransitionPolicy.sequentialQueueCueIndices(1))
    }

    @Test
    fun `an empty batch produces no cues`() {
        assertEquals(emptyList<Int>(), ToolPassTransitionPolicy.sequentialQueueCueIndices(0))
    }

    @Test
    fun `the head is never cued, every call behind it is`() {
        assertEquals(listOf(1), ToolPassTransitionPolicy.sequentialQueueCueIndices(2))
        assertEquals(listOf(1, 2), ToolPassTransitionPolicy.sequentialQueueCueIndices(3))
        assertEquals(listOf(1, 2, 3, 4), ToolPassTransitionPolicy.sequentialQueueCueIndices(5))
    }

    // ── Value level: only a QUEUED block may be dispatched ───────────────────

    @Test
    fun `a queued call becomes running at dispatch`() {
        assertEquals(ToolBlockStatus.RUNNING, ToolPassTransitionPolicy.statusAtDispatch(ToolBlockStatus.PENDING))
    }

    @Test
    fun `an already-running block is not published twice`() {
        // The parallel batch flips its whole batch up front; the sequential pass
        // must not re-publish a start for a call that already shows one.
        assertNull(ToolPassTransitionPolicy.statusAtDispatch(ToolBlockStatus.RUNNING))
    }

    @Test
    fun `no dispatch may resurrect a published refusal`() {
        assertNull(ToolPassTransitionPolicy.statusAtDispatch(ToolBlockStatus.FAILED))
        assertNull(ToolPassTransitionPolicy.statusAtDispatch(ToolBlockStatus.DEDUPLICATED))
    }

    @Test
    fun `no dispatch may resurrect any other terminal state`() {
        assertNull(ToolPassTransitionPolicy.statusAtDispatch(ToolBlockStatus.SUCCESS))
        assertNull(ToolPassTransitionPolicy.statusAtDispatch(ToolBlockStatus.CANCELLED))
        assertNull(ToolPassTransitionPolicy.statusAtDispatch(ToolBlockStatus.TIMEOUT))
    }

    @Test
    fun `a block with no status at all is left alone`() {
        assertNull(ToolPassTransitionPolicy.statusAtDispatch(null))
    }

    // ── Value level: starting a call retires its cue, keeps everything else ──

    @Test
    fun `the queue cue text is the one the engine publishes`() {
        // Written as a literal, not read back from the constant: a cue that gets
        // localized or reworded must be noticed here, because Pass 3 compares
        // content BY LENGTH and only this exact string is retired at dispatch.
        assertEquals("⏳ Waiting for previous tool(s) to finish…", ToolPassTransitionPolicy.QUEUE_CUE_TEXT)
    }

    @Test
    fun `a call that shows the cue starts with empty content`() {
        val cue = "⏳ Waiting for previous tool(s) to finish…"
        assertEquals("", ToolPassTransitionPolicy.contentAtDispatchFlip(cue, cue))
    }

    @Test
    fun `content that is not the cue survives the flip`() {
        val cue = "⏳ Waiting for previous tool(s) to finish…"
        assertEquals("", ToolPassTransitionPolicy.contentAtDispatchFlip("", cue))
        assertEquals("a\nb\n", ToolPassTransitionPolicy.contentAtDispatchFlip("a\nb\n", cue))
        // The finalisation pass keeps max(existingContent, resultContent) BY
        // LENGTH, so the cue must be strictly longer than the short results it
        // would otherwise smother — that is the whole reason the flip clears it.
        assertTrue(ToolPassTransitionPolicy.QUEUE_CUE_TEXT.length > "12345".length)
    }

    // ── Source contract: Pass 1 decides, Pass 2 publishes ────────────────────

    @Test
    fun `pass 1 never publishes a start`() {
        val engine = readRepoFile(sourcePath)
        val pass1 = engine.substring(pass1Start(engine), pass2Start(engine))
        assertTrue("Pass-1 loop not found — the region anchors moved", pass1.contains("parseToolParams(argsStr)"))
        assertFalse(
            "Pass 1 must not flip any call to RUNNING: a refused call then shows a spinner " +
                "nothing retracts, and the Pass-2 queue cue can never match",
            pass1.contains("ToolBlockStatus.RUNNING"),
        )
    }

    @Test
    fun `the critical loop-detector refusal reaches the ui`() {
        val engine = readRepoFile(sourcePath)
        val src = stripComments(engine)
        val critAt = src.indexOf("if (precheck.level == Level.CRITICAL) {")
        assertTrue("CRITICAL guard not found", critAt > 0)
        val contAt = Regex("\\n[ \\t]*continue[ \\t]*\\n").find(src, critAt)!!.range.first
        val branch = src.substring(critAt, contAt)
        assertTrue("the refusal must still mark the block FAILED", branch.contains("ToolBlockStatus.FAILED"))
        assertTrue(
            "the CRITICAL branch must publish the refusal (updateAssistantMessage), like the " +
                "dedupe / truncation / preflight refusals do",
            branch.contains("host.updateAssistantMessage("),
        )
    }

    @Test
    fun `the queue cue is published before the dispatch flip, all before the call runs`() {
        val engine = readRepoFile(sourcePath)
        val src = stripComments(engine)
        val cueCallAt = src.indexOf("ToolPassTransitionPolicy.sequentialQueueCueIndices(")
        val executeAt = src.indexOf("resultsById[p.id] = host.executeTool(")
        assertTrue("sequential cue-index call not found", cueCallAt > 0)
        assertTrue("sequential execute call not found", executeAt > cueCallAt)
        val sequential = src.substring(cueCallAt, executeAt)
        val cueAt = sequential.indexOf("ToolPassTransitionPolicy.QUEUE_CUE_TEXT")
        val flipAt = sequential.indexOf("ToolPassTransitionPolicy.statusAtDispatch(")
        assertTrue("the sequential branch must publish a waiting cue", cueAt > 0)
        assertTrue("the sequential branch must flip RUNNING at dispatch", flipAt > 0)
        assertTrue(
            "the cue must be published BEFORE the flip — publishing both in the same " +
                "iteration leaves the cue unreadable",
            cueAt < flipAt,
        )
        assertTrue(
            "the cue may only annotate a block that has not started",
            sequential.contains("ToolBlockStatus.PENDING"),
        )
    }

    @Test
    fun `both dispatch sites flip through the policy`() {
        val engine = readRepoFile(sourcePath)
        val src = stripComments(engine)
        val call = "ToolPassTransitionPolicy.statusAtDispatch("
        assertEquals("parallel batch + sequential dispatch must both use the policy", 2, src.windowed(call.length).count { it == call })
        // The sequential flip must retire the queue cue through the policy, not a
        // hard-coded content: the finalisation pass keeps max(existingContent,
        // resultContent) BY LENGTH, so a leftover cue would be shown and persisted
        // as that tool's output.
        assertTrue(
            "the sequential dispatch flip must retire the cue via contentAtDispatchFlip",
            src.contains("ToolPassTransitionPolicy.contentAtDispatchFlip("),
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun pass1Start(engine: String): Int =
        engine.indexOf("for ((id, name, args) in toolCalls) {", engine.indexOf("// Pass 1 (sequential)"))

    private fun pass2Start(engine: String): Int = engine.indexOf("if (pending.size > 1 && pending.all {")

    /** Comment-free view (offsets preserved): a criterion must never count a comment. */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src)
        var i = 0
        while (i < src.length) {
            if (src.startsWith("//", i)) {
                var j = src.indexOf('\n', i)
                if (j < 0) j = src.length
                for (k in i until j) out[k] = ' '
                i = j
            } else if (src.startsWith("/*", i)) {
                var j = src.indexOf("*/", i + 2)
                j = if (j < 0) src.length else j + 2
                for (k in i until j) if (src[k] != '\n') out[k] = ' '
                i = j
            } else {
                i++
            }
        }
        return out.toString()
    }

    /** Walks up from user.dir so the test runs from the module dir or the repo root. */
    private fun readRepoFile(relative: String): String {
        var dir = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}
