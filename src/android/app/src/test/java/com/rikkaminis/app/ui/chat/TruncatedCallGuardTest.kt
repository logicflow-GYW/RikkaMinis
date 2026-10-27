package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1018 ISSUE-01] P0 — the truncated-tool-call guard must fire on
 * BOTH truncation sources, and every site that dispatches a tool call must
 * consult it.
 *
 * Bug class: a turn cut off mid-stream by a network EOF carries no
 * finish_reason at all. The provider's EOF detector used to gate its
 * `Finished(truncated = true)` on text/reasoning content only, so a
 * tool-call-ONLY turn (zero prose) got `turnTruncated = false` forever; the
 * guard — which read `finishReason` alone — passed the call through, and
 * [com.rikkaminis.app.provider.ToolJsonRepair] Strategy 1 then closed the
 * half-streamed JSON object into a syntactically valid, semantically wrong
 * argument. Executing that costs an unknown side effect.
 *
 * The behaviour half of this test is pure JVM. The wiring half (engine call
 * site, provider EOF guard, sub-agent loop) is asserted against the source
 * text, because none of those three sites can run without the Android
 * runtime — the same readRepoFile pattern the repo already uses for
 * call-site shape (see NodeRegistryEvictionTest).
 */
class TruncatedCallGuardTest {

    // ------------------------------------------------------------------
    // Behaviour: the new EOF/truncated-flag source
    // ------------------------------------------------------------------

    @Test
    fun `eof flag alone with no finish reason refuses the call`() {
        val reason = TruncatedToolCallPolicy.rejectionReason("shell_execute", null, true)
        assertNotNull("EOF-truncated turn must NOT execute a tool call", reason)
        assertTrue(reason!!.startsWith("[TRUNCATED] Tool 'shell_execute' was NOT executed:"))
        // Names the EOF source, not the ceiling source.
        assertTrue(reason.contains("stream ended"))
        assertFalse(reason.contains("output limit"))
    }

    @Test
    fun `eof flag refuses even when the finish reason is a clean one`() {
        // A provider that emitted finish_reason="stop" and THEN died before
        // [DONE] still signals truncated=true downstream; the flag is the
        // stream's own verdict and outranks a stale reason.
        for (clean in listOf("stop", "end_turn", "tool_calls", "unknown_reason")) {
            val reason = TruncatedToolCallPolicy.rejectionReason("file_write", clean, true)
            assertNotNull("clean finish '$clean' + truncated flag must refuse", reason)
            assertTrue(reason!!.contains("stream ended"))
        }
    }

    @Test
    fun `no flag and no ceiling reason lets the call run`() {
        assertNull(TruncatedToolCallPolicy.rejectionReason("shell_execute", null, false))
        assertNull(TruncatedToolCallPolicy.rejectionReason("shell_execute", "stop", false))
        assertNull(TruncatedToolCallPolicy.rejectionReason("shell_execute", "tool_calls", false))
        assertNull(TruncatedToolCallPolicy.rejectionReason("shell_execute", "   ", false))
    }

    @Test
    fun `ceiling wording wins when both sources hold`() {
        // finishReason is the more specific diagnostic (it names the provider's
        // own reason string), so the combined case keeps the ORIGINAL message.
        val reason = TruncatedToolCallPolicy.rejectionReason("shell_execute", "length", true)!!
        assertTrue(reason.contains("output limit (length)"))
        assertFalse(reason.contains("stream ended"))
    }

    @Test
    fun `ceiling wording unchanged for the finish-reason source`() {
        val reason = TruncatedToolCallPolicy.rejectionReason("browser_use", "max_tokens", false)!!
        assertTrue(reason.startsWith("[TRUNCATED] Tool 'browser_use' was NOT executed:"))
        assertTrue(reason.contains("output limit (max_tokens)"))
        assertEquals(1, Regex("\\[TRUNCATED\\]").findAll(reason).count())
    }

    @Test
    fun `default parameter preserves the two-argument call shape`() {
        // Existing callers (and the original 8-case test) pass two arguments;
        // the third parameter must default to exactly the old behaviour.
        assertEquals(
            TruncatedToolCallPolicy.rejectionReason("x", "length"),
            TruncatedToolCallPolicy.rejectionReason("x", "length", false),
        )
        assertNull(TruncatedToolCallPolicy.rejectionReason("x", null))
        assertEquals(
            TruncatedToolCallPolicy.rejectionReason("x", "network_error"),
            TruncatedToolCallPolicy.rejectionReason("x", "network_error", false),
        )
    }

    @Test
    fun `refusal prefix is identical across both sources`() {
        // Callers and log greps key on this prefix; a divergent EOF message
        // that lost it would silently dodge every downstream match.
        val ceiling = TruncatedToolCallPolicy.rejectionReason("t", "length", false)!!
        val eof = TruncatedToolCallPolicy.rejectionReason("t", null, true)!!
        val prefix = "[TRUNCATED] Tool 't' was NOT executed:"
        assertTrue(ceiling.startsWith(prefix))
        assertTrue(eof.startsWith(prefix))
    }

    // ------------------------------------------------------------------
    // Wiring: the three sites that must actually USE the new judgement
    // ------------------------------------------------------------------

    @Test
    fun `engine dispatch site passes this turn's truncated flag`() {
        val src = readRepoFile(ENGINE)
        assertTrue(
            "AgentLoopEngine must feed turnTruncated into the guard",
            src.contains("TruncatedToolCallPolicy.rejectionReason(name, turnFinishReason, turnTruncated)"),
        )
    }

    @Test
    fun `openai eof guard counts tool-call events like anthropic counts tool chars`() {
        val src = readRepoFile(OPENAI)
        assertTrue(
            "EOF-without-Finished must also fire for tool-call-only turns",
            src.contains("if (!finishedSent && (contentLen > 0 || reasoningLen > 0 || toolCallEventCount > 0)) {"),
        )
        // Same-family sibling (already correct) pinned so a future edit cannot
        // quietly drop the Anthropic half.
        val anthropic = readRepoFile(ANTHROPIC)
        assertTrue(anthropic.contains("if (!sawClearFinish && (contentChars > 0 || toolChars > 0)) {"))
    }

    @Test
    fun `sub-agent loop consults the same policy instead of executing blind`() {
        val src = readRepoFile(TOOL_EXECUTORS)
        val body = src.substring(src.indexOf("internal suspend fun runSubagentLoop("))
        assertTrue(
            "runSubagentLoop must refuse truncated tool calls",
            body.contains("TruncatedToolCallPolicy.rejectionReason("),
        )
        // Single source of truth: the loop must not re-implement the wording.
        assertFalse(
            "runSubagentLoop must not hand-write a second [TRUNCATED] message",
            body.contains("\"[TRUNCATED]"),
        )
        // The refusal still produces a tool_result (pairing contract).
        val refusalIdx = body.indexOf("if (truncatedRefusal != null) {")
        assertTrue(refusalIdx >= 0)
        val refusalBlock = body.substring(refusalIdx, refusalIdx + 2000)
        assertTrue(refusalBlock.contains("AgentContentPart.ToolResult("))
        assertTrue(refusalBlock.contains("isError = true"))
        // …and the executable branch is its else-arm, so a refusal never runs.
        assertTrue(refusalBlock.contains("} else {"))
        assertTrue(refusalBlock.indexOf("executeSubTool(") > refusalBlock.indexOf("} else {"))
    }

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

    companion object {
        private const val ENGINE =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/AgentLoopEngine.kt"
        private const val OPENAI =
            "src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt"
        private const val ANTHROPIC =
            "src/android/app/src/main/java/com/rikkaminis/app/provider/anthropic/AnthropicProvider.kt"
        private const val TOOL_EXECUTORS =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatToolExecutors.kt"
    }
}
