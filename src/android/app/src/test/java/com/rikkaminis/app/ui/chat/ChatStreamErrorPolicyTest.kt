package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.sandbox.offload.ChatStreamErrorPolicyKind
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

    // ---------------------------------------------------------------- §17
    // [fix/permanent-4xx-retried-as-transient]: the engine's "0-chunk is safe
    // to resend" heuristic must NOT override a kind the worker stamped itself.
    // (Before the fix, a permanent `[400] model not found` — kind=provider,
    // hadChunks=false because the relay rejects before any chunk — was retried
    // 3x on the same member, burning 1+2+4s before the fallback.)

    private fun failure(hasChunks: Boolean, kind: String?) =
        ChatStreamErrorPolicy.decideStreamFailure(hasChunks = hasChunks, kind = kind)

    @Test
    fun `stamped provider failure is not retried on the same member`() {
        val d = failure(hasChunks = false, kind = ChatStreamErrorPolicyKind.KIND_PROVIDER)
        assertEquals(false, d.isTransient)
        assertEquals(true, d.fallbackNow)
    }

    @Test
    fun `stamped provider failure mid-stream is not retried either`() {
        val d = failure(hasChunks = true, kind = ChatStreamErrorPolicyKind.KIND_PROVIDER)
        assertEquals(false, d.isTransient)
        assertEquals(true, d.fallbackNow)
    }

    @Test
    fun `stamped rate limit falls back without same-member retries`() {
        val d = failure(hasChunks = false, kind = ChatStreamErrorPolicyKind.KIND_RATE_LIMITED)
        assertEquals(false, d.isTransient)
        assertEquals(true, d.fallbackNow)
    }

    @Test
    fun `stamped invalid key falls back without same-member retries`() {
        val d = failure(hasChunks = false, kind = ChatStreamErrorPolicyKind.KIND_INVALID_KEY)
        assertEquals(false, d.isTransient)
        assertEquals(true, d.fallbackNow)
    }

    @Test
    fun `stamped network failure still auto-retries`() {
        // The absorb-able direction must survive: a relay reset before any
        // chunk is still worth one same-member resend.
        val d = failure(hasChunks = false, kind = ChatStreamErrorPolicyKind.KIND_NETWORK)
        assertEquals(true, d.isTransient)
        assertEquals(false, d.fallbackNow)
    }

    @Test
    fun `stamped transient failure still auto-retries`() {
        val d = failure(hasChunks = true, kind = ChatStreamErrorPolicyKind.KIND_TRANSIENT)
        assertEquals(true, d.isTransient)
        assertEquals(false, d.fallbackNow)
    }

    @Test
    fun `unstamped zero chunk keeps the pre-stamping retry behavior`() {
        // Legaci worker / real worker death / first-chunk timeout: byte-identical
        // to the behavior that existed before kind stamping landed.
        val d = failure(hasChunks = false, kind = null)
        assertEquals(true, d.isTransient)
        assertEquals(false, d.fallbackNow)
    }

    @Test
    fun `unstamped mid stream is neither retried nor fallen back`() {
        val d = failure(hasChunks = true, kind = null)
        assertEquals(false, d.isTransient)
        assertEquals(false, d.fallbackNow)
    }

    @Test
    fun `a stamp from a newer worker is not guessed at`() {
        val d = failure(hasChunks = false, kind = "some_future_kind")
        assertEquals(false, d.isTransient)
        assertEquals(false, d.fallbackNow)
    }

    @Test
    fun `basis names the judgement that fired`() {
        // Audibility: the retry log prints this, so a future misclassification
        // is settle-able from the log alone instead of re-derived from source.
        assertEquals(
            "stamped permanent kind=provider",
            failure(hasChunks = false, kind = ChatStreamErrorPolicyKind.KIND_PROVIDER).basis,
        )
        assertEquals(
            "unstamped zero-chunk",
            failure(hasChunks = false, kind = null).basis,
        )
    }
}
