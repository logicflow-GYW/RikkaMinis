package com.openminis.app.ui.chat

import com.openminis.app.sandbox.offload.ChatStreamErrorPolicyKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the stream-error recovery policy
 * (fix/stream-error-silent-recovery).
 *
 * Contract under test:
 *  - typed network/transient kinds  → AUTO_RETRY (the fix: mid-stream proxy
 *    drops self-heal instead of surfacing a banner)
 *  - typed rate_limited/invalid_key/provider → FALLBACK_NOW (mirrors
 *    LLMError.isFallbackable semantics)
 *  - null kind (legacy untyped error lines) / unknown kinds → FATAL
 *    (conservative; byte-identical to pre-fix behavior)
 */
class ChatStreamErrorPolicyTest {

    @Test
    fun `network kind auto-retries`() {
        assertEquals(
            ChatStreamErrorPolicy.Action.AUTO_RETRY,
            ChatStreamErrorPolicy.classify(ChatStreamErrorPolicyKind.KIND_NETWORK),
        )
    }

    @Test
    fun `transient kind auto-retries`() {
        assertEquals(
            ChatStreamErrorPolicy.Action.AUTO_RETRY,
            ChatStreamErrorPolicy.classify(ChatStreamErrorPolicyKind.KIND_TRANSIENT),
        )
    }

    @Test
    fun `rate limited falls back immediately`() {
        assertEquals(
            ChatStreamErrorPolicy.Action.FALLBACK_NOW,
            ChatStreamErrorPolicy.classify(ChatStreamErrorPolicyKind.KIND_RATE_LIMITED),
        )
    }

    @Test
    fun `invalid key falls back immediately`() {
        assertEquals(
            ChatStreamErrorPolicy.Action.FALLBACK_NOW,
            ChatStreamErrorPolicy.classify(ChatStreamErrorPolicyKind.KIND_INVALID_KEY),
        )
    }

    @Test
    fun `provider error kind falls back immediately`() {
        assertEquals(
            ChatStreamErrorPolicy.Action.FALLBACK_NOW,
            ChatStreamErrorPolicy.classify(ChatStreamErrorPolicyKind.KIND_PROVIDER),
        )
    }

    @Test
    fun `null kind stays fatal - legacy worker behavior unchanged`() {
        // THE old-behavior guarantee: an untyped stream error must NOT gain
        // auto-recovery — conservative default for anything unclassifiable.
        assertEquals(
            ChatStreamErrorPolicy.Action.FATAL,
            ChatStreamErrorPolicy.classify(null),
        )
    }

    @Test
    fun `unknown kind from newer worker stays fatal`() {
        // A newer worker may introduce kinds this build doesn't know; never
        // guess — surface the error instead of silently looping.
        assertEquals(
            ChatStreamErrorPolicy.Action.FATAL,
            ChatStreamErrorPolicy.classify("some_future_kind"),
        )
    }

    @Test
    fun `blank kind stays fatal`() {
        assertEquals(
            ChatStreamErrorPolicy.Action.FATAL,
            ChatStreamErrorPolicy.classify(""),
        )
    }
}
