package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.tools.SubagentSkill
import com.openminis.app.tools.ToolExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FE-5 route B tests for the tool-execution layer extracted from
 * ChatViewModel (ChatToolExecutors.kt / ChatShellExecution.kt).
 *
 * JVM-testable surface: wrapForBash wire format, linuxPathToMinisURL,
 * the sub-agent loop's turn/termination semantics (with the provider
 * stream + tool executor injected as fakes), and the memory record sink
 * contract. The Android-bound executors (browser, shell coordinator,
 * persistBrowserArtifact) are covered by CI's full suite.
 */
class ChatToolExecutorsTest {

    // ── wrapForBash ───────────────────────────────────────────────

    @Test
    fun `wrapForBash guards on command -v bash with sentinel 119`() {
        val w = wrapForBash("echo hi")
        assertTrue(w.startsWith("( command -v bash >/dev/null 2>&1 || exit 119; "))
        assertTrue(w.contains("| base64 -d > /tmp/.minis-exec-\$\$.sh"))
        assertTrue(w.endsWith("exit " + '$' + "rc )"))
    }

    @Test
    fun `wrapForBash encodes script with guaranteed trailing newline`() {
        val w = wrapForBash("echo hi")
        // decoded payload must end with newline — base64 of "echo hi\n"
        val expected = android.util.Base64.encodeToString(
            "echo hi\n".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        assertTrue(w.contains("printf %s '$expected'"))
    }

    @Test
    fun `wrapForBash keeps already-newlined script unchanged payload`() {
        val w = wrapForBash("echo hi\n")
        val expected = android.util.Base64.encodeToString(
            "echo hi\n".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        assertTrue(w.contains("'$expected'"))
    }

    // ── linuxPathToMinisURL ───────────────────────────────────────

    @Test
    fun `browser path maps to minis url`() {
        assertEquals("minis://browser/s.jpg", linuxPathToMinisURL("/var/minis/browser/s.jpg"))
    }

    @Test
    fun `non-minis path returns null and namespaces are preserved`() {
        assertNull(linuxPathToMinisURL("/tmp/x"))
        assertNull(linuxPathToMinisURL("/var/minis/"))
        assertEquals("minis://workspace/f.txt", linuxPathToMinisURL("/var/minis/workspace/f.txt"))
        assertEquals("minis://browser/s.jpg", linuxPathToMinisURL("/var/minis/browser/s.jpg"))
    }

    // ── streamedLinesForDisplay (per-tool accumulation + 50-line window) ──

    @Test
    fun `display buffer accumulates and trims to last 50 lines`() {
        val key = "test-${System.nanoTime()}"
        resetDisplayBuffer(key)
        var last: String? = null
        for (i in 1..60) {
            last = streamedLinesForDisplay("line$i", key)
        }
        assertEquals(50, last!!.lines().size)
        assertEquals("line60", last.lines().last())
        resetDisplayBuffer(key)
    }

    @Test
    fun `display buffer resets between tools`() {
        val key = "test-${System.nanoTime()}"
        resetDisplayBuffer(key)
        streamedLinesForDisplay("a", key)
        resetDisplayBuffer(key)
        val out = streamedLinesForDisplay("b", key)
        assertEquals("b", out)
        resetDisplayBuffer(key)
    }

    // ── runSubagentLoop ───────────────────────────────────────────

    private fun config(maxTurns: Int = 4) = SubagentSkill.SubagentConfig(
        isSubagent = true, maxTurns = maxTurns, maxOutputTokens = 100,
    )

    @Test
    fun `subagent loop returns after natural finish (no tool calls)`() {
        var streamCalls = 0
        val result = kotlinx.coroutines.runBlocking {
            runSubagentLoop(
                skillName = "s", query = "q", title = "Sub-agent: s", config = config(),
                systemPrompt = "sys",
                streamProvider = { _ ->
                    streamCalls++
                    kotlinx.coroutines.flow.flowOf(LLMStreamChunk.Text("answer text"))
                },
                executeSubTool = { n, _ -> ToolExecutionResult("out-$n", true) },
                log = {},
            )
        }
        assertTrue(result.success)
        assertTrue(result.output.contains("answer text"))
        assertEquals(1, streamCalls)
        assertTrue(result.output.contains("completed in 1 turn"))
    }

    @Test
    fun `subagent loop executes tool calls then finishes`() {
        val executed = mutableListOf<String>()
        val result = kotlinx.coroutines.runBlocking {
            runSubagentLoop(
                skillName = "s", query = "q", title = "t", config = config(),
                systemPrompt = "sys",
                streamProvider = { messages ->
                    // first call emits a tool call, second call finishes
                    if (messages.size == 1) {
                        kotlinx.coroutines.flow.flowOf(
                            LLMStreamChunk.Text("need tool"),
                            LLMStreamChunk.ToolCallComplete("id1", "file_read", org.json.JSONObject()),
                        )
                    } else {
                        kotlinx.coroutines.flow.flowOf(LLMStreamChunk.Text("final answer"))
                    }
                },
                executeSubTool = { n, _ ->
                    executed.add(n)
                    ToolExecutionResult("tool output", true)
                },
                log = {},
            )
        }
        assertTrue(result.success)
        assertEquals(listOf("file_read"), executed)
        assertTrue(result.output.contains("need tool"))
        assertTrue(result.output.contains("final answer"))
        assertTrue(result.output.contains("completed in 2 turn"))
    }

    @Test
    fun `subagent loop caps at max turns`() {
        val result = kotlinx.coroutines.runBlocking {
            runSubagentLoop(
                skillName = "s", query = "q", title = "t",
                config = config(maxTurns = 2), systemPrompt = "sys",
                streamProvider = { _ ->
                    kotlinx.coroutines.flow.flowOf(
                        LLMStreamChunk.Text("t"),
                        LLMStreamChunk.ToolCallComplete("id", "file_read", org.json.JSONObject()),
                    )
                },
                executeSubTool = { _, _ -> ToolExecutionResult("out", true) },
                log = {},
            )
        }
        assertTrue(result.success)
        assertTrue(result.output.contains("[Sub-agent reached max turns (2)]"))
    }

    @Test
    fun `subagent loop surfaces stream exception as failure`() {
        // An exception mid-stream skips the post-collect append (both in the
        // original VM code and the extracted engine) — the emission lands in
        // the local builder but resultSb never sees it. The error shape is:
        // header + cause; no partial line.
        val result = kotlinx.coroutines.runBlocking {
            runSubagentLoop(
                skillName = "s", query = "q", title = "t", config = config(),
                systemPrompt = "sys",
                streamProvider = { _ ->
                    kotlinx.coroutines.flow.flow {
                        emit(LLMStreamChunk.Text("partial"))
                        throw RuntimeException("gateway exploded")
                    }
                },
                executeSubTool = { _, _ -> ToolExecutionResult("x", true) },
                log = {},
            )
        }
        assertTrue("success should be false: " + result.output, !result.success)
        assertTrue("missing error header: " + result.output, result.output.contains("encountered an error after 1 turn"))
        assertTrue("missing cause: " + result.output, result.output.contains("gateway exploded"))
    }

    @Test
    fun `subagent empty output completes success with notice`() {
        val result = kotlinx.coroutines.runBlocking {
            runSubagentLoop(
                skillName = "s", query = "q", title = "t", config = config(),
                systemPrompt = "sys",
                streamProvider = { _ -> kotlinx.coroutines.flow.emptyFlow() },
                executeSubTool = { _, _ -> ToolExecutionResult("x", true) },
                log = {},
            )
        }
        assertTrue(result.success)
        assertTrue(result.output.contains("completed in 1 turn(s) with no output"))
    }
}
