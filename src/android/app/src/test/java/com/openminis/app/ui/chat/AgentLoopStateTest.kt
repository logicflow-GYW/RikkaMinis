package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FE-5 route C step 2: JVM tests for [AgentLoopState] — the run-scoped
 * mutable state holder lifted from runAgentLoop's local `var` block.
 *
 * These pin the INITIAL VALUES of every field as the loop entry sets them,
 * so a later step that moves construction can't silently drift defaults
 * (e.g. loopExitedNormally must start false, or every normal completion
 * would take the runaway-turn path).
 *
 * Provider typing: [AgentLoopState.currentProvider] is exercised via a
 * minimal fake implementing just the surface AgentLoopState touches
 * (name/model/instanceContext); the full LLMProvider surface is irrelevant
 * to state-holder semantics.
 */
class AgentLoopStateTest {

    private fun state(): AgentLoopState = AgentLoopState(
        currentProvider = FakeProvider("fake"),
        remainingFallbacks = mutableListOf(),
        fallbackReasons = mutableListOf(),
    ).apply {
        assistantId = "assistant_123"
    }

    @Test fun `defaults — bubble fields empty`() {
        val s = state()
        assertEquals("assistant_123", s.assistantId)
        assertTrue(s.allToolBlocks.isEmpty())
        assertEquals(0, s.blockSeq)
        assertTrue(s.toolInputChunkRings.isEmpty())
        assertEquals("", s.accumulatedText)
        assertEquals(0, s.lastContextTokens)
        assertTrue(s.allToolInputs.isEmpty())
    }

    @Test fun `defaults — throttle fields reset`() {
        val s = state()
        assertEquals(0L, s.lastUiUpdateMs)
        assertEquals(0, s.lastFlushedLen)
        assertEquals(0, s.pendingChunkSb.length)
        assertEquals(0L, s.lastFileToolInputMs)
        assertEquals(0L, s.lastOtherToolInputMs)
    }

    @Test fun `defaults — one-shot guards false and counters zero`() {
        val s = state()
        assertFalse(s.loopExitedNormally)
        assertFalse(s.didInjectEmptyToolReminder)
        assertFalse(s.didRetryTruncatedTurn)
        assertEquals(0, s.lengthWallEmptyHits)
        assertFalse(s.lastTurnWasLengthWall)
    }

    @Test fun `fallback lists are independent per state`() {
        val a = state()
        val b = state()
        a.remainingFallbacks.clear()
        a.fallbackReasons.add("boom")
        assertTrue(b.remainingFallbacks.isEmpty())
        assertTrue(b.fallbackReasons.isEmpty())
        assertEquals(1, a.fallbackReasons.size)
    }

    @Test fun `currentProvider is reassignable`() {
        val s = state()
        s.currentProvider = FakeProvider("fake2")
        assertEquals("fake2", s.currentProvider.name)
    }

    @Test fun `blocks can be appended and grown like the loop does`() {
        val s = state()
        s.allToolBlocks.add(AssistantBlock(id = "text_0_0_0", kind = "text", content = "hello"))
        s.blockSeq = 1
        s.accumulatedText += "hello"
        s.allToolInputs["tool_1"] = """{"a":1}"""
        s.toolInputChunkRings["tool_1"] = mutableListOf("""{"a":1}""")
        assertEquals(1, s.allToolBlocks.size)
        assertEquals("hello", s.allToolBlocks[0].content)
        assertTrue(s.allToolBlocks[0].isText)
        assertEquals(1, s.blockSeq)
        assertEquals("hello", s.accumulatedText)
        assertEquals("""{"a":1}""", s.allToolInputs["tool_1"])
        assertEquals(1, s.toolInputChunkRings["tool_1"]!!.size)
    }

    /** Minimal LLMProvider surface for state-holder semantics (JVM only). */
    private class FakeProvider(
        override val name: String,
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
            throw UnsupportedOperationException("state-holder test fake")

        override fun streamMessageClamped(
            messages: List<com.openminis.app.data.model.LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<com.openminis.app.data.model.LLMMessage.ImagePart>,
            tools: List<com.openminis.app.data.model.AgentToolDefinition>,
            thinkingLevel: com.openminis.app.data.model.ThinkingLevel,
        ): kotlinx.coroutines.flow.Flow<com.openminis.app.data.model.LLMStreamChunk> =
            throw UnsupportedOperationException("state-holder test fake")
    }
}
