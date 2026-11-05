package com.rikkaminis.app.provider.gemini

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [fix/gemini-thought-signature-echo] Real-device repro (2026-11-05 17:11:26
 * `position 4`, 17:12:07 `position 2`), gemini-3.1-flash-lite with a
 * first-party API key. Every second tool round failed with:
 *
 * ```
 * 400 Function call is missing a thought_signature in functionCall parts.
 * ```
 *
 * Live-API control arms (2026-11-05) that this shape is derived from:
 *   no signature / "" / null        -> 400 missing thought_signature
 *   "not-a-real-signature"          -> 400 Corrupted thought signature.
 *   two historical calls, 1 signed  -> 400 (EVERY call needs one)
 *   signature INSIDE functionCall   -> 400 Unknown name "thoughtSignature"
 *   sentinel                        -> 200, model continues normally
 *
 * Expected values below are hand-written literals (evidence-discipline):
 * nothing is read back out of the helper under test.
 */
class GeminiToolReplayTest {

    private val toolName = "file_read"
    private val args = JSONObject("""{"path":"/tmp/a.txt"}""")

    @Test
    fun `replayed part has exactly two keys`() {
        val out = GeminiToolReplay.functionCall(toolName, args)
        assertEquals(setOf("thoughtSignature", "functionCall"), out.keySet().toSet())
    }

    @Test
    fun `signature sits on the part not inside functionCall`() {
        val out = GeminiToolReplay.functionCall(toolName, args)
        assertTrue("sentinel must be a sibling of functionCall", out.has("thoughtSignature"))
        assertTrue(
            "an empty signature is rejected exactly like a missing one (live API 400)",
            out.getString("thoughtSignature").isNotEmpty(),
        )
        val fc = out.getJSONObject("functionCall")
        assertEquals(setOf("name", "args"), fc.keySet().toSet())
        assertFalse(
            "a nested thoughtSignature is rejected as Unknown name",
            fc.has("thoughtSignature"),
        )
    }

    @Test
    fun `sentinel is the exact google documented value`() {
        val out = GeminiToolReplay.functionCall(toolName, args)
        assertEquals(
            "skip_thought_signature_validator",
            out.getString("thoughtSignature"),
        )
    }

    @Test
    fun `functionCall payload keeps the original name and args`() {
        val out = GeminiToolReplay.functionCall(toolName, args)
        assertEquals("file_read", out.getJSONObject("functionCall").getString("name"))
        assertEquals("/tmp/a.txt", out.getJSONObject("functionCall").getJSONObject("args").getString("path"))
    }

    /**
     * A green helper test does not prove the production builder uses it. This
     * pins the one replay site: the builder must delegate, and must not emit
     * any OTHER `functionCall` (an unsigned one anywhere in the history is a
     * deterministic 400 — measured with two historical calls, only one signed).
     */
    @Test
    fun `production builder delegates every functionCall replay to the helper`() {
        val body = geminiBodyBuilderSource()
        assertEquals(
            "the sole functionCall replay site must go through GeminiToolReplay",
            1,
            Regex("GeminiToolReplay\\.functionCall\\(").findAll(body).count(),
        )
        assertFalse(
            "an inline functionCall would be unsigned and 400 on the next turn",
            body.contains("put(\"functionCall\""),
        )
    }

    /**
     * Strongest form: even if the helper is called, bolting on an extra
     * unsigned part or removing the delegation must go red. Scans the WHOLE
     * builder slice so the pin covers assembly, not just the helper shape.
     */
    @Test
    fun `builder carries no hand-rolled thoughtSignature logic`() {
        val body = geminiBodyBuilderSource()
        assertFalse(
            "the sentinel must live in one place (GeminiToolReplay), not inlined",
            body.contains("skip_thought_signature_validator"),
        )
        assertTrue(
            "ToolUse branch must delegate",
            body.contains("GeminiToolReplay.functionCall(part.name, part.input)"),
        )
    }

    /** Slice of `buildRequestBody` up to the next private helper. */
    private fun geminiBodyBuilderSource(): String {
        val text = geminiProviderSource().readText()
        val start = text.indexOf("private fun buildRequestBody(")
        val end = text.indexOf("private fun buildThinkingConfig(", start)
        assertTrue("buildRequestBody not found", start >= 0 && end > start)
        return text.substring(start, end)
    }

    /** Tolerate both the gradle module cwd and a repo-root cwd in the JVM probe. */
    private fun geminiProviderSource(): File {
        val rel = "src/main/java/com/rikkaminis/app/provider/gemini/GeminiProvider.kt"
        val direct = File(rel)
        if (direct.isFile) return direct
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val nested = File(dir, "src/android/app/$rel")
            if (nested.isFile) return nested
            dir = dir.parentFile
        }
        error("GeminiProvider.kt not found from cwd=${File(".").absolutePath}")
    }
}
