package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OpenMinis#163 / T-xai-empty-effort-tiers] xAI models whose catalog entry
 * affirmatively declares NO effort tiers ("reasons, but takes no effort parameter")
 * must NOT receive `reasoning_effort` on xAI's own endpoint.
 *
 * The value of these tests is mostly in the CONTROLS: the same empty-tier shape
 * appears on 2090 bundled catalog entries, so the tests that must keep passing
 * are the ones proving the skip did NOT widen to them.
 */
class XAIEmptyEffortTiersTest {

    private fun resolve(
        modelId: String,
        declaresNoEffortTiers: Boolean = false,
        declaredEffortValues: List<String>? = null,
        isXAI: Boolean = false,
        unified: Boolean = false,
        supportsReasoning: Boolean? = true,
        level: ThinkingLevel = ThinkingLevel.HIGH,
    ): JSONObject {
        val body = JSONObject()
        ThinkingRuleResolver.apply(
            body,
            ThinkingResolveContext(
                modelId = modelId,
                supportsReasoning = supportsReasoning,
                declaredEffortValues = declaredEffortValues,
                declaresNoEffortTiers = declaresNoEffortTiers,
                level = level,
                maxTokens = 4096,
                isOpenRouter = false,
                usesUnifiedReasoningEffort = unified,
                isMistral = false,
                isDashScope = false,
                isXAI = isXAI,
                offEffort = null,
            ),
        )
        return body
    }

    private fun JSONObject.sendsEffort() = has("reasoning_effort")

    // ---- the reported failure --------------------------------------------

    @Test
    fun `grok-build-0-1 on first-party xAI omits reasoning_effort`() {
        val body = resolve("grok-build-0.1", declaresNoEffortTiers = true, isXAI = true)
        assertFalse(
            "the 400 in #163 was caused by sending this field: $body",
            body.sendsEffort(),
        )
    }

    @Test
    fun `grok-4-20-0309-reasoning has the same catalog shape and is also skipped`() {
        val body = resolve("grok-4.20-0309-reasoning", declaresNoEffortTiers = true, isXAI = true)
        assertFalse(body.sendsEffort())
    }

    // ---- controls: the skip must stay narrow -----------------------------

    @Test
    fun `the same model on a NON-xAI endpoint is unchanged`() {
        val body = resolve("grok-build-0.1", declaresNoEffortTiers = true, isXAI = false)
        assertTrue(
            "a relay serving the same model must keep its previous wire format",
            body.sendsEffort(),
        )
    }

    @Test
    fun `xAI model the catalog is SILENT about stays permissive`() {
        val body = resolve("grok-some-future-model", declaresNoEffortTiers = false, isXAI = true)
        assertTrue(body.sendsEffort())
    }

    @Test
    fun `xAI model that DOES declare tiers still sends the field`() {
        val body = resolve(
            "grok-4.5",
            declaresNoEffortTiers = false,
            declaredEffortValues = listOf("low", "medium", "high"),
            isXAI = true,
        )
        assertTrue(body.sendsEffort())
        assertEquals("high", body.optString("reasoning_effort"))
    }

    @Test
    fun `a declared tier list wins even if the flag is somehow also set`() {
        val body = resolve(
            "grok-weird",
            declaresNoEffortTiers = true,
            declaredEffortValues = listOf("low", "high"),
            isXAI = true,
        )
        assertTrue(body.sendsEffort())
    }

    @Test
    fun `unified-effort gateways stay exempt`() {
        val body = resolve(
            "grok-build-0.1",
            declaresNoEffortTiers = true,
            isXAI = true,
            unified = true,
        )
        assertTrue(body.sendsEffort())
    }

    @Test
    fun `the legacy self-reasoning family skip still fires`() {
        for (id in listOf("deepseek-chat", "glm-4.5-air", "kimi-k2", "minimax-m2")) {
            assertFalse("$id must still skip", resolve(id).sendsEffort())
        }
    }

    @Test
    fun `a non-reasoning model is still skipped as before`() {
        assertFalse(resolve("grok-4.20-0309-non-reasoning", supportsReasoning = false).sendsEffort())
    }
}
