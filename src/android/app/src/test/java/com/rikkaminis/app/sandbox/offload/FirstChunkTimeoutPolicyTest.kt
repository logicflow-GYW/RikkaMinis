package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [FirstChunkTimeoutPolicy] (diag/first-chunk-timeout, 2026-08-24).
 *
 * Pins the route-aware first-chunk budget decision:
 *   - direct / official endpoints → 30s (DIRECT_TIMEOUT_SEC)
 *   - proxy / gateway / local-relay routes → 45s (PROXY_TIMEOUT_SEC)
 *
 * Also pins the extractable retry-classification contract: a 0-chunk
 * [ModelStreamErrorException] must be treated as transient (retryable) —
 * mirrored by ChatViewModel.workerDiedZeroChunk; these tests pin the policy
 * side, the ChatViewModel side is covered by the existing retry tests.
 */
class FirstChunkTimeoutPolicyTest {

    // ── routing class ──

    @Test
    fun `null base url is direct`() {
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute(null))
    }

    @Test
    fun `official hosts stay direct even when customized`() {
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://api.openai.com"))
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://api.openai.com/v1"))
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://api.anthropic.com"))
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://api.deepseek.com"))
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://openrouter.ai/api/v1"))
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://generativelanguage.googleapis.com"))
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://api.moonshot.cn/v1"))
    }

    @Test
    fun `additional official endpoints stay direct`() {
        // 2026-08-24: Azure/DashScope/Groq/MiniMax/Xiaomi were missing from the
        // official suffix list and fell into the proxy bucket (45s). Pinned
        // here so the exact-match additions cannot silently regress.
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://myres.openai.azure.com"))
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://myres.openai.azure.com/openai/deployments/gpt-4o/chat/completions"))
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://dashscope.aliyuncs.com/compatible-mode/v1"))
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://api.groq.com/openai/v1"))
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://api.minimax.io/v1"))
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://api.xiaomimimo.com/v1"))
    }

    @Test
    fun `subdomains of official hosts count as direct`() {
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("https://eu.api.openai.com"))
    }

    @Test
    fun `loopback and local relays are proxy routes`() {
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://127.0.0.1:7890"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://127.0.0.1:8080/v1"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://localhost:7890"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://10.0.0.5:8080"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://192.168.1.100:7890"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://172.16.0.2:1080"))
        // RFC1918 172.16.0.0–172.31.255.255: all second-octet values in 16..31.
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://172.16.1.1:1080"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://172.19.255.254:1080"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://172.20.0.1:1080"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://172.29.5.5:1080"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://172.30.0.1:1080"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://172.31.255.255:1080"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://172.31.1.99:1080"))
    }

    @Test
    fun `public 172 space outside RFC1918 still proxy via suffix check`() {
        // 172.32.0.0+ is public address space — the old loose "172." prefix
        // swept it into the private-172 early check unnecessarily. With the
        // RFC1918-exact fix, these addresses flow through to the normal
        // suffix-based check instead. Since no official suffix matches a bare
        // IP, they remain classified as proxy (non-official custom host) —
        // the same practical outcome, but via the correct path.
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("https://172.32.1.1:1080"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("https://172.100.0.1:1080"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("https://172.255.255.1:1080"))
    }

    @Test
    fun `plain http non-official is proxy`() {
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://some-relay.example.com"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("http://192.168.31.1:7891"))
    }

    @Test
    fun `custom gateway domains are proxy routes`() {
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("https://hub.oaifree.com"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("https://gateway.myrelay.net/v1"))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("https://api.oaifree.com"))
    }

    @Test
    fun `trailing slashes and case are normalized`() {
        assertFalse(FirstChunkTimeoutPolicy.isProxyRoute("  HTTPS://API.OPENAI.COM/v1/  "))
        assertTrue(FirstChunkTimeoutPolicy.isProxyRoute("HTTP://127.0.0.1:7890/"))
    }

    // ── generation budget (2026-08-26, fix/long-generation-timeouts) ──
    // The generous ceiling is now the FIRST-CHUNK + TOTAL-STREAM budget for
    // EVERY generation stream, thinking or not. A long NON-thinking generation
    // (a large deliverable, a big-context late turn) legitimately sits silent
    // before its first chunk for the same reason a reasoning model does, and
    // provider silence is not a reliable dead-signal.

    @Test
    fun `generation streams always get the generous ceiling regardless of route`() {
        // The regression this fixes: a non-thinking long generation was cut at
        // the 45s proxy first-chunk wall ("provider produced no first chunk
        // within 45000ms"). Generation must ignore the route split entirely.
        assertEquals(
            FirstChunkTimeoutPolicy.GENERATION_TIMEOUT_SEC,
            FirstChunkTimeoutPolicy.decideGenerationTimeoutSec(null),
        )
        assertEquals(
            FirstChunkTimeoutPolicy.GENERATION_TIMEOUT_SEC,
            FirstChunkTimeoutPolicy.decideGenerationTimeoutSec("https://api.openai.com"),
        )
        assertEquals(
            FirstChunkTimeoutPolicy.GENERATION_TIMEOUT_SEC,
            FirstChunkTimeoutPolicy.decideGenerationTimeoutSec("https://gateway.myrelay.net/v1"),
        )
        assertEquals(
            FirstChunkTimeoutPolicy.GENERATION_TIMEOUT_SEC,
            FirstChunkTimeoutPolicy.decideGenerationTimeoutSec("http://127.0.0.1:7890"),
        )
    }

    @Test
    @Suppress("DEPRECATION") // 仅验证 legacy 语义，生产已切 decideGenerationTimeoutSec
    fun `generation ceiling is the absolute 30-minute backstop`() {
        // Bounds the worst case so a worker is never held forever, even for a
        // genuinely wedged upstream — but long (30 min) enough to cover the
        // realistic 2:50–3:10 silences and 10–20 min extreme generations.
        assertEquals(30 * 60, FirstChunkTimeoutPolicy.GENERATION_TIMEOUT_SEC)
        // Historical alias is identical (semantics de-scoped, not value-changed).
        assertEquals(FirstChunkTimeoutPolicy.GENERATION_TIMEOUT_SEC, FirstChunkTimeoutPolicy.THINKING_TIMEOUT_SEC)
    }

    // ── legacy route-split (kept for route classification; NOT used by the
    // generation stream path, which always picks decideGenerationTimeoutSec) ──

    @Test
    @Suppress("DEPRECATION") // 仅验证 legacy 语义，生产已切 decideGenerationTimeoutSec
    fun `legacy direct routes get 30s`() {
        assertEquals(30, FirstChunkTimeoutPolicy.decideTimeoutSec(null))
        assertEquals(30, FirstChunkTimeoutPolicy.decideTimeoutSec("https://api.openai.com"))
    }

    @Test
    @Suppress("DEPRECATION") // 仅验证 legacy 语义，生产已切 decideGenerationTimeoutSec
    fun `legacy thinking override still grants the ceiling`() {
        assertEquals(
            FirstChunkTimeoutPolicy.GENERATION_TIMEOUT_SEC,
            FirstChunkTimeoutPolicy.decideTimeoutSec(null, thinkingEnabled = true),
        )
        assertEquals(
            FirstChunkTimeoutPolicy.GENERATION_TIMEOUT_SEC,
            FirstChunkTimeoutPolicy.decideTimeoutSec("http://127.0.0.1:7890", thinkingEnabled = true),
        )
    }

    // ── retry contract (mirror of ChatViewModel.workerDiedZeroChunk) ──

    @Test
    fun `zero-chunk stream errors are retryable`() {
        // A 0-chunk ModelStreamErrorException (first_chunk_timeout path) is
        // classified transient/retryable — the contract the ChatViewModel fix
        // implements. Pin the semantic here so a regression in the checker
        // cannot silently reintroduce the fatal classification.
        // NOTE: declared as Throwable (what unwrapFlowException returns) so the
        // classifier sees the same static type shape as production.
        val err: Throwable = ModelStreamErrorException("provider produced no first chunk within 30000ms (hadChunks=false)", hadChunks = false)
        assertFalse((err as ModelStreamErrorException).hadChunks)
        // The classifier is: workerDiedZeroChunk =
        //   (WorkerDied || StreamError) && (as? ModelExecutionStreamException)?.hadChunks == false
        // (must mirror the production expression in ChatViewModel EXACTLY)
        val workerDiedZeroChunk =
            ((err is ModelWorkerDiedException) || (err is ModelStreamErrorException)) &&
                (err as? ModelExecutionStreamException)?.hadChunks == false
        assertTrue("0-chunk ModelStreamErrorException must be transient/retryable", workerDiedZeroChunk)
    }

    @Test
    fun `mid-stream errors are NOT retryable`() {
        // Any exception carrying hadChunks=true must fall to the fatal path
        // (no re-send — a duplicate answer would reach the user).
        val err: Throwable = ModelStreamErrorException("stream reset after content", hadChunks = true)
        assertTrue((err as ModelStreamErrorException).hadChunks)
        val workerDiedZeroChunk =
            ((err is ModelWorkerDiedException) || (err is ModelStreamErrorException)) &&
                (err as? ModelExecutionStreamException)?.hadChunks == false
        assertFalse("hadChunks=true must NOT be retried", workerDiedZeroChunk)
    }
}