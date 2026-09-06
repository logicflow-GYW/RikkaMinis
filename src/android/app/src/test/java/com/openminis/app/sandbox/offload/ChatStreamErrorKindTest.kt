package com.openminis.app.sandbox.offload

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the typed stream-error line protocol
 * (fix/stream-error-silent-recovery).
 *
 * Contract under test:
 *  - errorLine(msg, kind) writes `{"t":"error","m":msg,"k":kind}`;
 *    null/blank kind omits the field (backward compatible)
 *  - errorKind() round-trips; missing k → null
 *  - ChatStreamErrorPolicyKind.of walks the cause chain: a provider-layer
 *    `cancel("Stream error", cause=NetworkError(IOException))` resolves to
 *    "network" — this is the exact production shape of the user-reported
 *    "Stream error" banner.
 *  - worker-side kind constants match what ChatStreamErrorPolicy.classify
 *    consumes (shared wire contract)
 */
class ChatStreamErrorKindTest {

    @Test
    fun `typed error line carries kind field`() {
        val line = ChatStreamJsonl.errorLine("proxy dropped", ChatStreamErrorPolicyKind.KIND_NETWORK)
        val obj = JSONObject(line)
        assertEquals("error", obj.optString("t"))
        assertEquals("proxy dropped", obj.optString("m"))
        assertEquals(ChatStreamErrorPolicyKind.KIND_NETWORK, obj.optString("k"))
        assertTrue(ChatStreamJsonl.isError(line))
        assertEquals(ChatStreamErrorPolicyKind.KIND_NETWORK, ChatStreamJsonl.errorKind(line))
        assertEquals("proxy dropped", ChatStreamJsonl.errorMessage(line))
    }

    @Test
    fun `null kind omits field - old line shape byte-compatible`() {
        val line = ChatStreamJsonl.errorLine("Stream error", null)
        val obj = JSONObject(line)
        assertFalse(obj.has("k"))
        assertNull(ChatStreamJsonl.errorKind(line))
        // isError / errorMessage semantics unchanged for the legacy shape.
        assertTrue(ChatStreamJsonl.isError(line))
        assertEquals("Stream error", ChatStreamJsonl.errorMessage(line))
    }

    @Test
    fun `blank kind is treated as absent`() {
        val line = ChatStreamJsonl.errorLine("x", "  ")
        assertFalse(JSONObject(line).has("k"))
        assertNull(ChatStreamJsonl.errorKind(line))
    }

    @Test
    fun `cause chain resolves wrapped network error`() {
        // Production shape: OpenAIProvider cancels its flow with
        // cancel("Stream error", mapError(e)) where mapError(IOException) =
        // NetworkError. The handler sees the CancellationException; the
        // kind classifier must walk the cause chain to find it.
        val cause = java.io.IOException("Connection reset")
        val wrapped = kotlinx.coroutines.CancellationException("Stream error", cause)
        assertEquals(ChatStreamErrorPolicyKind.KIND_NETWORK, ChatStreamErrorPolicyKind.of(wrapped))
    }

    @Test
    fun `direct llm errors classify to their kind`() {
        assertEquals(
            ChatStreamErrorPolicyKind.KIND_RATE_LIMITED,
            ChatStreamErrorPolicyKind.of(com.openminis.app.data.model.LLMError.RateLimited()),
        )
        assertEquals(
            ChatStreamErrorPolicyKind.KIND_INVALID_KEY,
            ChatStreamErrorPolicyKind.of(com.openminis.app.data.model.LLMError.InvalidApiKey()),
        )
        assertEquals(
            ChatStreamErrorPolicyKind.KIND_TRANSIENT,
            ChatStreamErrorPolicyKind.of(
                com.openminis.app.data.model.LLMError.TransientError("server busy"),
            ),
        )
        assertEquals(
            ChatStreamErrorPolicyKind.KIND_PROVIDER,
            ChatStreamErrorPolicyKind.of(com.openminis.app.data.model.LLMError.ProviderError("502 bad gateway")),
        )
    }

    @Test
    fun `unclassifiable throwables have null kind`() {
        assertNull(ChatStreamErrorPolicyKind.of(IllegalStateException("unexpected")))
        assertNull(ChatStreamErrorPolicyKind.of(RuntimeException("boom", Error("deep"))))
    }

    @Test
    fun `cancellation without cause chain classifies null`() {
        // Real user cancellation (no cause) must NOT classify as network —
        // the cancelled flag is handled separately by the cancel contract.
        assertNull(ChatStreamErrorPolicyKind.of(kotlinx.coroutines.CancellationException("job cancelled")))
    }

    @Test
    fun `worker kinds are strings the policy recognizes`() {
        // Wire-contract pin: every worker-side constant must classify to a
        // non-FATAL action. If someone renames a constant without updating
        // both sides, this fails.
        for (kind in listOf(
            ChatStreamErrorPolicyKind.KIND_NETWORK,
            ChatStreamErrorPolicyKind.KIND_TRANSIENT,
            ChatStreamErrorPolicyKind.KIND_RATE_LIMITED,
            ChatStreamErrorPolicyKind.KIND_INVALID_KEY,
            ChatStreamErrorPolicyKind.KIND_PROVIDER,
        )) {
            val action = com.openminis.app.ui.chat.ChatStreamErrorPolicy.classify(kind)
            assertTrue(
                "kind $kind classified as $action — wire contract drift",
                action != com.openminis.app.ui.chat.ChatStreamErrorPolicy.Action.FATAL,
            )
        }
    }
}
