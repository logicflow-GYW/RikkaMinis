package com.rikkaminis.app.provider.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * [fix/responses-replay-item-id] Real-device repro (2026-11-05 00:47 / 00:49 /
 * 00:54): a /responses tool round on an Azure multi-resource relay failed with
 * `400 The requested item was created under a different Azure OpenAI resource`,
 * and retryFromMessage re-sent the same poisoned history and failed identically.
 * The cause was the replayed `function_call` carrying the server-assigned item
 * id; rikkahub replays the same item on the same relay without it.
 *
 * Expected values are hand-written literals (evidence-discipline): nothing in
 * this class is read back out of the helper under test.
 */
class OpenAIResponsesToolReplayTest {

    private val callId = "call_9f5bf4357cf993e4"
    private val toolName = "shell_execute"
    private val args = "{\"command\":\"pwd\"}"

    @Test
    fun `replay emits exactly the four required protocol fields`() {
        val out = OpenAIResponsesToolReplay.functionCall(callId, toolName, args)
        assertEquals(
            setOf("type", "call_id", "name", "arguments"),
            out.keySet().toSet(),
        )
    }

    @Test
    fun `replay never carries a server-assigned item id`() {
        val out = OpenAIResponsesToolReplay.functionCall(callId, toolName, args)
        assertFalse(
            "an item id makes stateless relays look the item up by id and 400",
            out.has("id"),
        )
    }

    @Test
    fun `field values are exactly the arguments passed in`() {
        val out = OpenAIResponsesToolReplay.functionCall(callId, toolName, args)
        assertEquals("function_call", out.getString("type"))
        assertEquals("call_9f5bf4357cf993e4", out.getString("call_id"))
        assertEquals("shell_execute", out.getString("name"))
        assertEquals("{\"command\":\"pwd\"}", out.getString("arguments"))
    }

    /**
     * A green unit test on the helper alone does not prove the production
     * /responses builder actually uses it. This pins the one call site and
     * keeps the removed synthetic-id fallback from quietly coming back.
     */
    @Test
    fun `production replay site delegates to the helper and has no synthetic id fallback`() {
        val text = openAIProviderSource().readText()
        assertEquals(
            "the /responses assistant tool-use replay must go through the helper",
            1,
            Regex("OpenAIResponsesToolReplay\\.functionCall\\(").findAll(text).count(),
        )
        assertFalse(
            "the synthetic item-id fallback is the defect being removed",
            text.contains("fc_syn_"),
        )
    }

    /** Tolerate both the gradle module cwd and a repo-root cwd in the JVM probe. */
    private fun openAIProviderSource(): File {
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
