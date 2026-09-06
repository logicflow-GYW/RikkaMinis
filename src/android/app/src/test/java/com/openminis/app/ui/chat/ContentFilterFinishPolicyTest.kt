package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/content-filter-fallback] Classification of blocked-turn finish
 * reasons. Pure function — no Android deps.
 */
class ContentFilterFinishPolicyTest {

    @Test
    fun `chat completions content_filter is blocked`() {
        assertTrue(ContentFilterFinishPolicy.isBlockedFinish("content_filter"))
    }

    @Test
    fun `gemini safety family is blocked`() {
        for (r in listOf("safety", "prohibited_content", "blocklist", "recitation",
                         "spii", "image_safety", "image_prohibited_content", "image_other")) {
            assertTrue("expected blocked: $r", ContentFilterFinishPolicy.isBlockedFinish(r))
        }
    }

    @Test
    fun `anthropic refusal is blocked`() {
        assertTrue(ContentFilterFinishPolicy.isBlockedFinish("refusal"))
    }

    @Test
    fun `case insensitive and whitespace tolerant`() {
        assertTrue(ContentFilterFinishPolicy.isBlockedFinish("Content_Filter"))
        assertTrue(ContentFilterFinishPolicy.isBlockedFinish(" SAFETY "))
        assertTrue(ContentFilterFinishPolicy.isBlockedFinish("Recitation"))
    }

    @Test
    fun `normal finish reasons are NOT blocked`() {
        for (r in listOf("stop", "end_turn", "length", "max_tokens", "tool_use",
                         "tool_calls", null, "", "unknown_reason")) {
            assertFalse("expected NOT blocked: $r", ContentFilterFinishPolicy.isBlockedFinish(r))
        }
    }

    @Test
    fun `null and blank are never blocked - conservative default`() {
        assertFalse(ContentFilterFinishPolicy.isBlockedFinish(null))
        assertFalse(ContentFilterFinishPolicy.isBlockedFinish(""))
        assertFalse(ContentFilterFinishPolicy.isBlockedFinish("   "))
    }

    @Test
    fun `gemini raw uppercase forms blocked after provider lowercase`() {
        // GeminiProvider.extractFinishReason lowercases unknown values; make
        // sure the raw forms still classify if they ever arrive un-lowercased.
        assertTrue(ContentFilterFinishPolicy.isBlockedFinish("SAFETY"))
        assertTrue(ContentFilterFinishPolicy.isBlockedFinish("RECITATION"))
        assertTrue(ContentFilterFinishPolicy.isBlockedFinish("PROHIBITED_CONTENT"))
    }

    @Test
    fun `lookalike reasons are not blocked`() {
        // Prefix/suffix lookalikes must not match (exact set membership only).
        assertFalse(ContentFilterFinishPolicy.isBlockedFinish("content_filter_v2"))
        assertFalse(ContentFilterFinishPolicy.isBlockedFinish("safety_check"))
        assertFalse(ContentFilterFinishPolicy.isBlockedFinish("xrefusal"))
    }

    // ── [fix/finish-reason-network-error] error-shaped pseudo-finishes ─────

    @Test
    fun `error-shaped finish reasons classify`() {
        for (r in listOf("network_error", "server_error", "provider_error",
                         "service_unavailable", "upstream_error", "bad_gateway",
                         "timeout", "gateway_timeout", "Network_Error")) {
            assertTrue("expected error-shaped: $r", ContentFilterFinishPolicy.isErrorShapedFinish(r))
        }
    }

    @Test
    fun `normal and blocked reasons are not error-shaped`() {
        for (r in listOf("stop", "end_turn", "length", "tool_calls", "max_tokens",
                         "content_filter", "safety", "refusal", null, "", "unknown")) {
            assertFalse("expected NOT error-shaped: $r", ContentFilterFinishPolicy.isErrorShapedFinish(r))
        }
    }
}
