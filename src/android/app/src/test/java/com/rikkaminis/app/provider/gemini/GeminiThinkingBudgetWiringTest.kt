package com.rikkaminis.app.provider.gemini

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [FULLSCAN-1119 F-11] Wiring half: the clamp runs on the object that is put
 * into `generationConfig`, and it is bounded by the SAME value that goes on the
 * wire as `maxOutputTokens`.
 *
 * Pure source anchoring (no production classes imported) so it runs in the
 * sandbox as well as in Gradle. The value tables are in
 * `GeminiThinkingBudgetClampTest`; the body the provider actually sends is in
 * `GeminiThinkingBudgetWireTest`.
 *
 * Negative control per test: restore the pre-fix shape in memory and require
 * the same assertion to REJECT it, so the anchor cannot be blind.
 */
class GeminiThinkingBudgetWiringTest {

    private companion object {
        const val PROVIDER = "app/src/main/java/com/rikkaminis/app/provider/gemini/GeminiProvider.kt"
    }

    @Test
    fun `the clamp runs on the config that is put into generationConfig`() {
        val src = readRepoFile(PROVIDER)
        assertClampWiring(src)

        assertProbeRejects(
            src.replace(
                "val thinkingConfig = clampGeminiThinkingBudget(rawConfig, wireMaxOutputTokens)",
                "val thinkingConfig = rawConfig",
            ),
            ::assertClampWiring,
        )
    }

    @Test
    fun `the clamp is bounded by the SAME value that is sent as maxOutputTokens`() {
        val src = readRepoFile(PROVIDER)
        assertSingleMaxOutputSource(src)

        // Negative control: clamping against the RAW request value is exactly
        // the split the deep-audit-1046 S-03 fix removed — the emitted
        // maxOutputTokens can be lower, so the pair could be illegal again.
        assertProbeRejects(
            src.replace(
                "clampGeminiThinkingBudget(rawConfig, wireMaxOutputTokens)",
                "clampGeminiThinkingBudget(rawConfig, maxTokens)",
            ),
            ::assertSingleMaxOutputSource,
        )
    }

    // -- assertions -----------------------------------------------------------

    private fun assertClampWiring(src: String) {
        assertTrue(
            "the thinking config must be clamped before it is stored",
            src.contains("val thinkingConfig = clampGeminiThinkingBudget(rawConfig, wireMaxOutputTokens)"),
        )
        assertTrue(
            "the clamped object is what reaches the request body",
            src.contains("config.put(\"thinkingConfig\", thinkingConfig)"),
        )
    }

    private fun assertSingleMaxOutputSource(src: String) {
        assertTrue(
            "maxOutputTokens must be computed once into a named value",
            src.contains("val wireMaxOutputTokens = clampOutboundMaxTokens("),
        )
        assertTrue(
            "the named value is what goes on the wire",
            src.contains("config.put(\"maxOutputTokens\", wireMaxOutputTokens)"),
        )
        assertTrue(
            "the clamp reads that same value (not the raw request)",
            src.contains("clampGeminiThinkingBudget(rawConfig, wireMaxOutputTokens)"),
        )
        assertFalse(
            "no second maxOutputTokens computation may exist",
            Regex("""config\.put\("maxOutputTokens",\s*clampOutboundMaxTokens\(""").containsMatchIn(src),
        )
    }

    // -- helpers --------------------------------------------------------------

    private fun assertProbeRejects(mutated: String, assertion: (String) -> Unit) {
        val satisfied = try {
            assertion(mutated)
            true
        } catch (_: AssertionError) {
            false
        }
        assertFalse("negative control was NOT rejected — the assertion is blind to it", satisfied)
    }

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from ${System.getProperty("user.dir")}")
    }
}
