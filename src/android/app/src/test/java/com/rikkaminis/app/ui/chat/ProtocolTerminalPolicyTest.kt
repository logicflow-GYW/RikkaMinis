package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.conversation.compactSummaryIsTruncated
import com.rikkaminis.app.conversation.compactSummaryTruncatedFailure
import org.junit.Assert.*
import org.junit.Test

class ProtocolTerminalPolicyTest {
    @Test fun `context window truncation refuses tools but is not an output length wall`() {
        for (reason in listOf("model_context_window_exceeded", " MODEL_CONTEXT_WINDOW_EXCEEDED ")) {
            assertTrue(TruncatedToolCallPolicy.isContextWindowTruncated(reason))
            assertFalse(TruncatedToolCallPolicy.isTruncatedFinish(reason))
            val refusal = TruncatedToolCallPolicy.rejectionReason("fixture_echo", reason)
            assertNotNull(refusal)
            assertTrue(refusal!!.contains("context window"))
            assertFalse(refusal.contains("output limit"))
        }
    }
    @Test fun `compact shares known truncation reasons across providers without rejecting unknown markers`() {
        for (reason in listOf("length", "max_tokens", "MAX_TOKENS", "max_output_tokens", "model_context_window_exceeded")) {
            assertTrue(reason, compactSummaryIsTruncated(reason))
        }
        for (reason in listOf(null, "", "stop", "end_turn", "future_marker")) {
            assertFalse(compactSummaryIsTruncated(reason))
        }
    }
    @Test fun `compact failure names actual reason not an invented OpenAI length`() {
        val message = compactSummaryTruncatedFailure("fixture model", "max_tokens").message.orEmpty()
        assertTrue(message.contains("stopReason=max_tokens"))
        assertFalse(message.contains("stopReason=length"))
    }
}
