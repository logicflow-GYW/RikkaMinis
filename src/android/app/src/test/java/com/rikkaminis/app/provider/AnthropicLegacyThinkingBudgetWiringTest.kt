package com.rikkaminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [FULLSCAN-1119 F-10] Wiring half: the clamped budget must be the number that
 * reaches the request body, and it must be written under a guard that reads
 * BOTH bounds.
 *
 * Pure source anchoring (no production classes imported) so it runs in the
 * sandbox as well as in Gradle. The value tables live in
 * `AnthropicLegacyThinkingBudgetTest`, the body the provider actually sends in
 * `AnthropicThinkingBudgetWireTest` — all three must agree.
 *
 * Negative control per test: the pre-fix shape is restored in memory and the
 * same assertion is required to REJECT it, so the anchor cannot be blind.
 */
class AnthropicLegacyThinkingBudgetWiringTest {

    private companion object {
        const val PROVIDER = "app/src/main/java/com/rikkaminis/app/provider/anthropic/AnthropicProvider.kt"
    }

    @Test
    fun `the budget that is written is the clamped one`() {
        val src = readRepoFile(PROVIDER)
        assertClampedValue(src)

        // Negative control: the pre-fix read — the raw ladder value, unclamped.
        assertProbeRejects(
            src.replace(
                "val budgetTokens = legalLegacyBudgetTokens(thinkingBudget(cappedMaxTokens, thinkingLevel), cappedMaxTokens)",
                "val budgetTokens = thinkingBudget(cappedMaxTokens, thinkingLevel)",
            ),
            ::assertClampedValue,
        )
    }

    @Test
    fun `budget_tokens is written in exactly one place`() {
        val src = readRepoFile(PROVIDER)
        assertSingleProducer(src)

        // Negative control: a second writer of the field = a path around the
        // clamp, which must fail the count.
        assertProbeRejects(
            src.replace(
                "put(\"budget_tokens\", budgetTokens)",
                "put(\"budget_tokens\", budgetTokens)\n                    body.put(\"budget_tokens\", 1)",
            ),
            ::assertSingleProducer,
        )
    }

    @Test
    fun `the provider delegates the window instead of re-implementing it`() {
        val src = readRepoFile(PROVIDER)
        assertDelegation(src)

        // Negative control: an inline window (the shape this fix avoided) must
        // be rejected — two copies of `1024 <= b < max` is how the floor got
        // forgotten the first time.
        assertProbeRejects(
            src.replace(
                "AnthropicLegacyThinkingBudget.legalBudgetTokens(requested, maxTokens) ?: 0",
                "minOf(maxOf(requested, 1024), maxTokens - 1).takeIf { it >= 1024 } ?: 0",
            ),
            ::assertDelegation,
        )
    }

    // -- assertions -----------------------------------------------------------

    private fun assertClampedValue(src: String) {
        assertTrue(
            "the emitted budget must be the clamped one",
            src.contains(
                "val budgetTokens = legalLegacyBudgetTokens(thinkingBudget(cappedMaxTokens, thinkingLevel), cappedMaxTokens)",
            ),
        )
        // The guard must still read both bounds: a clamp whose result is written
        // unconditionally would ship budget_tokens=0 at OFF.
        assertTrue(
            "the write must stay under a two-sided guard",
            src.contains("if (budgetTokens > 0 && budgetTokens < cappedMaxTokens) {"),
        )
    }

    private fun assertSingleProducer(src: String) {
        assertEquals(
            "budget_tokens must have ONE producer so a clamp cannot be bypassed",
            1,
            Regex("""put\(\s*"budget_tokens"""").findAll(src).count(),
        )
    }

    private fun assertDelegation(src: String) {
        // The delegate is an EXPRESSION body (`= AnthropicLegacy…`), so brace
        // matching would run past it into the next declaration; slice between
        // the two declaration headers instead.
        val start = src.indexOf("fun legalLegacyBudgetTokens(")
        assertTrue("the delegation helper must exist", start >= 0)
        val body = src.substring(start, start + 400)
        assertTrue(
            "the window must come from AnthropicLegacyThinkingBudget: $body",
            body.contains("AnthropicLegacyThinkingBudget.legalBudgetTokens("),
        )
        assertFalse(
            "no second copy of the 1024 window in the provider",
            body.substringBefore(") ?: 0").contains("1024"),
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
