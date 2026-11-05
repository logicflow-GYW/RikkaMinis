package com.rikkaminis.app.provider.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-rhub1105-F01/F02/F06] Regression cover for the three Responses/Chat
 * streaming protocol defects found by the 2026-11-05 protocol audit.
 *
 * Expected values are hand-written literals (evidence-discipline): nothing here
 * is derived from the constants under test.
 *
 * Scope note: the SSE loop itself cannot run on the JVM (it needs the Android
 * runtime), so the wire-level behaviour is pinned in two layers:
 *   1. the pure lookup rule [resolveResponsesAccumulator] is exercised directly;
 *   2. the production SOURCE is scanned for the wiring that the defects were
 *      about — a shape test on a helper proves nothing if the helper is not
 *      the thing the stream actually calls.
 */
class OpenAIResponsesStreamFixTest {

    // ---------------- F-01: accumulator lookup rule ----------------

    @Test
    fun `lookup prefers the exact item id`() {
        val byItem = ResponsesToolCallAccumulator(callId = "call_a", name = "shell", itemId = "fc_1")
        val byCall = ResponsesToolCallAccumulator(callId = "call_b", name = "shell", itemId = "fc_2")
        val hit = resolveResponsesAccumulator(listOf(byCall, byItem), "fc_1", "call_b", 0)
        assertNotNull(hit)
        assertEquals("call_a", hit!!.callId)
    }

    @Test
    fun `lookup falls back to the call id when the item id is absent`() {
        val acc = ResponsesToolCallAccumulator(callId = "call_c", name = "shell", itemId = "fc_9")
        val hit = resolveResponsesAccumulator(listOf(acc), "", "call_c", -1)
        assertNotNull(hit)
        assertEquals("call_c", hit!!.callId)
    }

    @Test
    fun `lookup falls back to the output index when neither id is known`() {
        val acc = ResponsesToolCallAccumulator(callId = "call_d", name = "shell", itemId = "", outputIndex = 3)
        val hit = resolveResponsesAccumulator(listOf(acc), "", "", 3)
        assertNotNull(hit)
        assertEquals("call_d", hit!!.callId)
    }

    @Test
    fun `lookup returns null when nothing identifies the accumulator`() {
        val acc = ResponsesToolCallAccumulator(callId = "call_e", name = "shell", itemId = "fc_5", outputIndex = 1)
        assertNull(resolveResponsesAccumulator(listOf(acc), "fc_other", "call_other", 7))
    }

    @Test
    fun `lookup ignores an unknown output index instead of matching blindly`() {
        val acc = ResponsesToolCallAccumulator(callId = "call_f", name = "shell", itemId = "", outputIndex = 2)
        assertNull(resolveResponsesAccumulator(listOf(acc), "", "", -1))
    }

    // ---------------- production wiring ----------------

    @Test
    fun `production stream calls the shared lookup rule instead of indexing the map by item id`() {
        val text = providerSource().readText()
        assertTrue(
            "the Responses tool-call lookup must go through resolveResponsesAccumulator",
            text.contains("resolveResponsesAccumulator("),
        )
        assertTrue(
            "the item-id-only map lookup must be gone",
            !Regex("responsesToolCalls\\[itemId\\]").containsMatchIn(text),
        )
    }

    @Test
    fun `production stream handles the function_call_arguments done frame`() {
        val text = providerSource().readText()
        assertTrue(
            "the authoritative end of the argument stream must have its own arm",
            text.contains("\"response.function_call_arguments.done\""),
        )
    }

    @Test
    fun `production stream does not treat an empty arguments string as authoritative`() {
        val text = providerSource().readText()
        assertTrue(
            "an empty `arguments` must fall back to the streamed buffer",
            text.contains(
                "val argsStr = if (authoritative.isNotEmpty()) authoritative else acc.args.toString()",
            ),
        )
    }

    @Test
    fun `production stream accepts a function call without a server item id`() {
        val text = providerSource().readText()
        assertTrue(
            "the `fc_…` item id is optional — call_id alone must be enough",
            text.contains("if (callId.isNotEmpty() && name.isNotEmpty()) {"),
        )
    }

    @Test
    fun `production responses branch throws on an error frame`() {
        val text = providerSource().readText()
        assertTrue(
            "a top-level error frame must be mapped, not silently finished",
            text.contains("type == \"error\" || event.optJSONObject(\"error\") != null -> {"),
        )
        assertTrue(
            "the error arm must reuse the single mapping table",
            text.contains("openAiStreamErrorStatus(code, errType)"),
        )
    }

    @Test
    fun `production chat branch arms the reasoning emitter for Mistral thinking blocks`() {
        val text = providerSource().readText()
        val thinkingLoop = Regex(
            "for \\(thinkingText in chatCompletionsDeltaThinkingBlocks\\(d\\)\\) \\{[\\s\\S]{0,600}?sawReasoningField = true",
        ).find(text)
        assertTrue(
            "Mistral `content[].type == thinking` is a server-native reasoning field",
            thinkingLoop != null,
        )
    }

    @Test
    fun `production eof path emits the reasoning payload`() {
        val text = providerSource().readText()
        assertTrue(
            "the EOF path must emit ReasoningContent like the [DONE] path does",
            text.contains(
                "if (!finishedSent && sawReasoningField) {\n" +
                    "                send(LLMStreamChunk.ReasoningContent(reasoningAccum.toString()))",
            ),
        )
    }

    /** Tolerate both the gradle module cwd and a repo-root cwd in the JVM probe. */
    private fun providerSource(): File {
        val rel = "src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt"
        val direct = File(rel)
        if (direct.isFile) return direct
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val nested = File(dir, "src/android/app/$rel")
            if (nested.isFile) return nested
            dir = dir.parentFile
        }
        error("OpenAIProvider.kt not found from cwd=${File(".").absolutePath}")
    }
}
