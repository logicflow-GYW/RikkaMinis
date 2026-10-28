package com.rikkaminis.app.provider.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [r3-b08 F-08] Tier resolution for the Responses API `reasoning.effort`.
 *
 * The defect: the Responses builder clamped only the MiMo/Agnes family and
 * ignored both the gateway's own measured enum (`EffortTierLearner`) and the
 * catalog's declared set — so a gateway that had already answered 400 with its
 * legal tiers kept receiving the same illegal tier on every retry.
 *
 * Literal expectations: the ladder used here is "walk down to the strongest
 * declared tier, else the weakest declared tier" (ThinkingEffortLadder).
 */
class ResponsesEffortClampTest {

    @Test
    fun `nothing requested stays nothing`() {
        assertNull(clampResponsesEffort(null, "gpt-5.1", null, null))
    }

    @Test
    fun `no declaration leaves the tier untouched`() {
        assertEquals(
            "xhigh",
            clampResponsesEffort("xhigh", "gpt-5.1", null, null),
        )
    }

    @Test
    fun `a declared tier is kept verbatim`() {
        assertEquals(
            "medium",
            clampResponsesEffort("medium", "glm-5.2", null, listOf("low", "medium", "high")),
        )
    }

    @Test
    fun `an undeclared tier walks down to the strongest declared one`() {
        assertEquals(
            "medium",
            clampResponsesEffort("xhigh", "glm-5.2", null, listOf("low", "medium")),
        )
    }

    @Test
    fun `a tier below the whole declaration walks up to the weakest one`() {
        assertEquals(
            "high",
            clampResponsesEffort("minimal", "some-model", null, listOf("high", "max")),
        )
    }

    @Test
    fun `the learned enum outranks the catalog declaration`() {
        // The gateway told us (via a 400 body) that this model accepts only
        // "low"; the catalog still claims the full ladder. The measured answer
        // wins, so MAX degrades to low instead of 400-ing again.
        assertEquals(
            "low",
            clampResponsesEffort(
                effort = "max",
                modelId = "deepseek-v4.1-flash",
                learnedEffortValues = listOf("low"),
                declaredEffortValues = listOf("low", "medium", "high", "xhigh", "max"),
            ),
        )
    }

    @Test
    fun `the mimo family clamp still applies before the ladder`() {
        // MiMo rejects xhigh outright; with nothing declared the family clamp is
        // the only thing standing between the user and a 400/422.
        assertEquals(
            "high",
            clampResponsesEffort("xhigh", "mimo-v2.5-pro", null, null),
        )
    }

    @Test
    fun `family clamp then ladder - agnes with a high-capped declaration`() {
        assertEquals(
            "high",
            clampResponsesEffort("xhigh", "Agnes-2.5", emptyList(), listOf("low", "high")),
        )
    }
}
