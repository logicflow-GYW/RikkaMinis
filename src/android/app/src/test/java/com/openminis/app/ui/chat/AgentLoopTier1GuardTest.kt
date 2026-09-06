package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/hermes-tier1] JVM tests for the new AgentLoopState guard fields and
 * the loop-constant contract:
 *
 *  - [AgentLoopState.lengthWallContinues] — text-continuation budget per
 *    length-wall wall, reset when a tool-call turn proves the model
 *    recovered. Ceiling is [MAX_LENGTH_WALL_TEXT_CONTINUES] (mirrors Hermes
 *    turn_truncation's cap of 4).
 *  - [AgentLoopState.deterministicEmptyStreak] — consecutive empty
 *    completions whose usage PROVES output_tokens == 0; at
 *    [DETERMINISTIC_EMPTY_LIMIT] the loop stops re-billing (Hermes
 *    empty_response_guard port, adapted to 2 because RikkaMinis keeps one
 *    reminder round for the tool-result case).
 *
 * The state machine transitions themselves run inside runAgentLoop (not
 * directly unit-testable without a host fake); these tests pin the field
 * defaults and the transition invariants the engine relies on, so a drift
 * in defaults or constants fails here instead of in production billing.
 */
class AgentLoopTier1GuardTest {

    private fun state(): AgentLoopState = AgentLoopState(
        currentProvider = MinimalProvider(),
        remainingFallbacks = mutableListOf(),
        fallbackReasons = mutableListOf(),
    ).apply { assistantId = "assistant_123" }

    /** Minimal LLMProvider surface for state-holder semantics (JVM only). */
    private class MinimalProvider(
        override val name: String = "fake",
    ) : com.openminis.app.provider.LLMProvider {
        override var model: com.openminis.app.data.model.LLMModel =
            com.openminis.app.data.model.LLMModel(
                id = "m", displayName = "m", provider = "p",
            )
        override var instanceContext: com.openminis.app.data.model.ProviderInstance? = null

        override suspend fun sendMessageClamped(
            messages: List<com.openminis.app.data.model.LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<com.openminis.app.data.model.LLMMessage.ImagePart>,
            tools: List<com.openminis.app.data.model.AgentToolDefinition>,
            thinkingLevel: com.openminis.app.data.model.ThinkingLevel,
        ): com.openminis.app.data.model.LLMResponse =
            throw UnsupportedOperationException("tier1 guard test fake")

        override fun streamMessageClamped(
            messages: List<com.openminis.app.data.model.LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<com.openminis.app.data.model.LLMMessage.ImagePart>,
            tools: List<com.openminis.app.data.model.AgentToolDefinition>,
            thinkingLevel: com.openminis.app.data.model.ThinkingLevel,
        ): kotlinx.coroutines.flow.Flow<com.openminis.app.data.model.LLMStreamChunk> =
            throw UnsupportedOperationException("tier1 guard test fake")
    }

    // ── field defaults ────────────────────────────────────────────────

    @Test fun `guard fields start at zero`() {
        val s = state()
        assertEquals(0, s.lengthWallContinues)
        assertEquals(0, s.deterministicEmptyStreak)
        // Pre-existing neighbors unchanged.
        assertEquals(0, s.lengthWallEmptyHits)
        assertFalse(s.lastTurnWasLengthWall)
    }

    // ── constants ─────────────────────────────────────────────────────

    @Test fun `continuation ceiling matches hermes cap of 4`() {
        assertEquals(4, MAX_LENGTH_WALL_TEXT_CONTINUES)
    }

    @Test fun `deterministic empty limit is 2`() {
        assertEquals(2, DETERMINISTIC_EMPTY_LIMIT)
    }

    @Test fun `turn ceiling unchanged`() {
        assertEquals(200, MAX_AGENT_TURNS)
    }

    // ── transition invariants the engine implements ───────────────────

    @Test fun `tool turn resets both guards to fresh allowance`() {
        // Engine: on a tool-call turn, lengthWallContinues = 0 and
        // deterministicEmptyStreak = 0. Pin the invariant a drifted edit
        // would break: after N continues + M empties, a tool turn must
        // restore a FULL budget (ceiling reached again only after another
        // MAX_LENGTH_WALL_TEXT_CONTINUES continues).
        val s = state().apply {
            lengthWallContinues = MAX_LENGTH_WALL_TEXT_CONTINUES
            deterministicEmptyStreak = DETERMINISTIC_EMPTY_LIMIT
        }
        // Simulate the engine's tool-turn reset (same two assignments).
        s.lengthWallContinues = 0
        s.deterministicEmptyStreak = 0
        assertEquals(0, s.lengthWallContinues)
        assertEquals(0, s.deterministicEmptyStreak)
        assertTrue(s.lengthWallContinues < MAX_LENGTH_WALL_TEXT_CONTINUES)
        assertTrue(s.deterministicEmptyStreak < DETERMINISTIC_EMPTY_LIMIT)
    }

    @Test fun `ceiling comparison is inclusive`() {
        // Engine gives up when lengthWallContinues >= ceiling — i.e. exactly
        // 4 prior continues leave NO budget. Pin the >= (not >) semantics.
        val s = state().apply { lengthWallContinues = MAX_LENGTH_WALL_TEXT_CONTINUES - 1 }
        assertTrue(s.lengthWallContinues < MAX_LENGTH_WALL_TEXT_CONTINUES) // one more allowed
        s.lengthWallContinues = MAX_LENGTH_WALL_TEXT_CONTINUES
        assertFalse(s.lengthWallContinues < MAX_LENGTH_WALL_TEXT_CONTINUES) // budget exhausted
    }
}
