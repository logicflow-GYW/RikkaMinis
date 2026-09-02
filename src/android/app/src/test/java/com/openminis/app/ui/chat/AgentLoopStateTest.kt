package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.provider.LLMProvider
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
        s.currentProvider = FakeProvider2()
        assertEquals("fake2", s.currentProvider.name)
    }

    private open class FakeProvider(
        override val name: String,
    ) : LLMProvider {
        override var model: LLMModel = LLMModel()
        override var instanceContext: ProviderInstance? = null
    }

    private class FakeProvider2 : FakeProvider("fake2")
}
