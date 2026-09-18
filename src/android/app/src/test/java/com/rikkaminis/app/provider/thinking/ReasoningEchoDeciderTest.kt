package com.rikkaminis.app.provider.thinking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-deepseek-v4-thinking-echo] Truth table for the echo decision.
 *
 * The regression this pins (2026-09-18, `deepseek-v4-flash` behind an OpenAI-compatible
 * relay): the local thinking level decided whether the echo happened at all, so a
 * tool-call turn reached the vendor with no `reasoning_content` and the NEXT request was
 * rejected — `[400] The content[].thinking in the thinking mode must be passed back to
 * the API`. The rule table had declared `AFTER_TOOL_USE_ONLY` for `*deepseek-v4*` all
 * along; the provider just never read it.
 */
class ReasoningEchoDeciderTest {

    private val deepseekStyle =
        ReasoningEchoPolicy("reasoning_content", ReasoningEchoPolicy.Timing.AFTER_TOOL_USE_ONLY)
    private val everyTurn =
        ReasoningEchoPolicy("reasoning_content", ReasoningEchoPolicy.Timing.EVERY_TURN)
    private val never =
        ReasoningEchoPolicy("reasoning_content", ReasoningEchoPolicy.Timing.NEVER)

    private fun decide(
        policy: ReasoningEchoPolicy?,
        hasToolCalls: Boolean,
        captured: String? = null,
        gate: Boolean = false,
        placeholder: Boolean = false,
    ) = ReasoningEchoDecider.decide(policy, hasToolCalls, captured, gate, placeholder)

    // ── the fix: tool-call turns echo whether or not the LOCAL level asked for thinking ──

    @Test
    fun `tool-call turn with thinking OFF still emits the placeholder`() {
        // level OFF → legacyGate=false, legacyPlaceholder=false. This is the field report.
        assertEquals(
            ReasoningEchoDecider.Action.PLACEHOLDER,
            decide(deepseekStyle, hasToolCalls = true, captured = null, gate = false, placeholder = false),
        )
    }

    @Test
    fun `tool-call turn with thinking AUTO emits the placeholder`() {
        // AUTO → gate=true, placeholder=false (the deliberate AUTO suppression).
        assertEquals(
            ReasoningEchoDecider.Action.PLACEHOLDER,
            decide(deepseekStyle, hasToolCalls = true, captured = null, gate = true, placeholder = false),
        )
    }

    @Test
    fun `tool-call turn echoes captured reasoning verbatim, including empty`() {
        assertEquals(
            ReasoningEchoDecider.Action.CAPTURED,
            decide(deepseekStyle, hasToolCalls = true, captured = "let me check", gate = false),
        )
        assertEquals(
            ReasoningEchoDecider.Action.CAPTURED,
            decide(deepseekStyle, hasToolCalls = true, captured = "", gate = false),
        )
    }

    // ── no collateral damage: everything the legacy gate already did is unchanged ──

    @Test
    fun `non-tool turns keep the legacy decision on both sides of the gate`() {
        assertEquals(
            ReasoningEchoDecider.Action.OMIT,
            decide(deepseekStyle, hasToolCalls = false, captured = null, gate = false),
        )
        assertEquals(
            ReasoningEchoDecider.Action.OMIT,
            decide(deepseekStyle, hasToolCalls = false, captured = null, gate = true, placeholder = false),
        )
        assertEquals(
            ReasoningEchoDecider.Action.PLACEHOLDER,
            decide(deepseekStyle, hasToolCalls = false, captured = null, gate = true, placeholder = true),
        )
        assertEquals(
            ReasoningEchoDecider.Action.CAPTURED,
            decide(deepseekStyle, hasToolCalls = false, captured = "abc", gate = true),
        )
    }

    @Test
    fun `no policy at all reproduces the legacy gate byte for byte`() {
        assertEquals(ReasoningEchoDecider.Action.OMIT, decide(null, true, null, gate = false, placeholder = true))
        assertEquals(ReasoningEchoDecider.Action.PLACEHOLDER, decide(null, true, null, gate = true, placeholder = true))
        assertEquals(ReasoningEchoDecider.Action.CAPTURED, decide(null, true, "x", gate = true, placeholder = false))
        assertEquals(ReasoningEchoDecider.Action.OMIT, decide(null, false, "x", gate = false, placeholder = true))
    }

    // ── the other two timings ──

    @Test
    fun `NEVER outranks every gate (Mistral closed schema)`() {
        for (toolCalls in listOf(true, false)) {
            for (gate in listOf(true, false)) {
                for (captured in listOf(null, "", "secret")) {
                    assertEquals(
                        "toolCalls=$toolCalls gate=$gate captured=$captured",
                        ReasoningEchoDecider.Action.OMIT,
                        decide(never, toolCalls, captured, gate = gate, placeholder = true),
                    )
                }
            }
        }
    }

    @Test
    fun `EVERY_TURN follows the capture gate but never the AUTO placeholder suppression`() {
        assertEquals(
            ReasoningEchoDecider.Action.OMIT,
            decide(everyTurn, hasToolCalls = false, captured = null, gate = false, placeholder = true),
        )
        assertEquals(
            ReasoningEchoDecider.Action.PLACEHOLDER,
            decide(everyTurn, hasToolCalls = false, captured = null, gate = true, placeholder = false),
        )
        assertEquals(
            ReasoningEchoDecider.Action.CAPTURED,
            decide(everyTurn, hasToolCalls = false, captured = "r", gate = true),
        )
    }

    // ── the provider writes the rule's declared spelling; keep the two in sync ──

    @Test
    fun `deepseek-v4 and mistral rules declare the reasoning_content spelling`() {
        // The OpenAI builder writes "reasoning_content" unconditionally. If a rule is
        // ever changed to `reasoning` / `reasoning_text` (GH OpenMinis#171 spellings),
        // this assertion fails instead of the field silently going missing.
        val ctx = ThinkingResolveContext(
            modelId = "deepseek-v4-flash",
            instanceId = null,
            supportsReasoning = null,
            declaredEffortValues = null,
            level = com.rikkaminis.app.data.model.ThinkingLevel.OFF,
            maxTokens = 8192,
            isOpenRouter = false,
            usesUnifiedReasoningEffort = false,
            isMistral = false,
            isDashScope = false,
            isOfficialDeepSeek = false,
            offEffort = null,
        )
        assertEquals(
            "reasoning_content",
            ThinkingRuleResolver.echoPolicyFor(ctx)?.fieldName,
        )
    }
}
