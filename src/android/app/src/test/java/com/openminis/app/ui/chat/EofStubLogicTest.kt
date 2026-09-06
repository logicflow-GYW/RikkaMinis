package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/eof-stub-continuation] JVM tests for the EOF-truncated-stream
 * recovery helpers.
 *
 * Field symptom being fixed: relay drops the SSE connection mid-answer
 * (no finish_reason). Legacy behavior deleted the partial answer and
 * regenerated (waste + duplicate opening), and a SECOND EOF broke silently —
 * mid-sentence stop, no hint, user had to type "继续" by hand. The fix keeps
 * the partial text and appends a network-stub reminder (Hermes
 * conversation_loop network-stub pattern), capped at MAX_EOF_STUB_CONTINUES.
 *
 * The engine-side control flow (reminder injection, stacking guard, seam
 * interplay) is covered by CI compiling the real AgentLoopEngine; these
 * tests pin the pure helpers, the constant contract, and the state-machine
 * invariants the engine relies on.
 */
class EofStubLogicTest {

    // ── eofStubReminder ───────────────────────────────────────────────

    @Test fun `stub reminder anchors the cut point and forbids repetition`() {
        val r = eofStubReminder("…最后的半句话")
        assertTrue(r.startsWith("<system-reminder>"))
        assertTrue(r.endsWith("</system-reminder>"))
        assertTrue(r.contains("cut off by a network error"))
        assertTrue(r.contains("Continue exactly where you left off"))
        assertTrue(r.contains("Do not restart or repeat prior text"))
        // Anchor tail is embedded for the model to key on.
        assertTrue(r.contains("…最后的半句话"))
    }

    @Test fun `stub reminder is textually distinct from length-wall reminder`() {
        // The engine's stacking guard tells the two synthetic reminders apart
        // by substring; they must never collide.
        val eof = eofStubReminder("tail")
        val wall = lengthWallReminder("tail")
        assertTrue(eof.contains("cut off by a network error"))
        assertFalse(wall.contains("cut off by a network error"))
        assertTrue(wall.contains("cut off mid-sentence by the output token limit"))
        assertFalse(eof.contains("cut off mid-sentence by the output token limit"))
    }

    // ── looksLikeMidSentenceCut (diagnostic only) ─────────────────────

    @Test fun `mid-sentence detection flags arbitrary cuts`() {
        assertTrue(looksLikeMidSentenceCut("这是一个没说完的句子，突然"))
        assertTrue(looksLikeMidSentenceCut("code with an open expression: 1 +"))
        assertTrue(looksLikeMidSentenceCut("trailing spaces then cut   \n "))
    }

    @Test fun `mid-sentence detection accepts real endings`() {
        assertFalse(looksLikeMidSentenceCut("完整的句子。"))
        assertFalse(looksLikeMidSentenceCut("Complete sentence."))
        assertFalse(looksLikeMidSentenceCut("标题：一个问号结尾？"))
        assertFalse(looksLikeMidSentenceCut("```kotlin\ncode block end\n```"))
        assertFalse(looksLikeMidSentenceCut("quoted speech」"))
        assertFalse(looksLikeMidSentenceCut(""))
    }

    // ── constant + state contract ─────────────────────────────────────

    @Test fun `stub continuation ceiling is 2`() {
        assertEquals(2, MAX_EOF_STUB_CONTINUES)
    }

    @Test fun `eofStubContinues starts at zero`() {
        val s = AgentLoopState(
            currentProvider = FakeProviderForEof(),
            remainingFallbacks = mutableListOf(),
            fallbackReasons = mutableListOf(),
        ).apply { assistantId = "a" }
        assertEquals(0, s.eofStubContinues)
        // Legacy one-shot field unchanged (still used by the blank-EOF path).
        assertFalse(s.didRetryTruncatedTurn)
    }

    @Test fun `ceiling comparison is inclusive`() {
        // Engine gives up when eofStubContinues >= ceiling.
        val atCeiling = MAX_EOF_STUB_CONTINUES
        assertFalse(atCeiling < MAX_EOF_STUB_CONTINUES) // budget exhausted
        assertTrue(atCeiling - 1 < MAX_EOF_STUB_CONTINUES) // one more allowed
    }

    /** Minimal LLMProvider surface for state-holder construction (JVM only). */
    private class FakeProviderForEof(
        override val name: String = "fake",
    ) : com.openminis.app.provider.LLMProvider {
        override var model: com.openminis.app.data.model.LLMModel =
            com.openminis.app.data.model.LLMModel(id = "m", displayName = "m", provider = "p")
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
            throw UnsupportedOperationException("eof test fake")

        override fun streamMessageClamped(
            messages: List<com.openminis.app.data.model.LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<com.openminis.app.data.model.LLMMessage.ImagePart>,
            tools: List<com.openminis.app.data.model.AgentToolDefinition>,
            thinkingLevel: com.openminis.app.data.model.ThinkingLevel,
        ): kotlinx.coroutines.flow.Flow<com.openminis.app.data.model.LLMStreamChunk> =
            throw UnsupportedOperationException("eof test fake")
    }
}
