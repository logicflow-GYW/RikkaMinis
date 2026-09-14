package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-truncated-tool-call-guard] Truncated-turn tool-call guard.
 *
 * The guard only makes the app safer if it fires on exactly the ceiling
 * reasons and on nothing else: a false positive refuses a legitimate tool
 * call and burns a turn, a false negative lets a prefix argument execute.
 * Both directions are pinned here.
 */
class TruncatedToolCallPolicyTest {

    private val truncated = listOf(
        "length",              // OpenAI Chat Completions
        "max_output_tokens",   // OpenAI Responses
        "max_tokens",          // Anthropic Messages
        "maxtokens",
        "max_tokens_exceeded",
        "token_limit",
        "output_limit",
    )

    /** Clean finishes AND every reason another policy owns. None may be read
     *  as truncation, or the guard would start refusing healthy calls. */
    private val notTruncated = listOf(
        "stop", "end_turn", "tool_calls", "tool_use", "stop_sequence",
        "content_filter", "refusal", "safety", "network_error", "timeout",
        "unknown_reason_we_do_not_know",
    )

    @Test
    fun `every protocol ceiling spelling is truncated`() {
        for (r in truncated) {
            assertTrue("expected truncated: $r", TruncatedToolCallPolicy.isTruncatedFinish(r))
        }
    }

    @Test
    fun `clean finishes and other policies' reasons are never truncated`() {
        for (r in notTruncated) {
            assertFalse("must not be truncated: $r", TruncatedToolCallPolicy.isTruncatedFinish(r))
        }
    }

    @Test
    fun `null and blank are not truncated`() {
        assertFalse(TruncatedToolCallPolicy.isTruncatedFinish(null))
        assertFalse(TruncatedToolCallPolicy.isTruncatedFinish(""))
        assertFalse(TruncatedToolCallPolicy.isTruncatedFinish("   "))
    }

    @Test
    fun `case and surrounding whitespace are tolerated`() {
        assertTrue(TruncatedToolCallPolicy.isTruncatedFinish("LENGTH"))
        assertTrue(TruncatedToolCallPolicy.isTruncatedFinish("  Max_Tokens  "))
        assertTrue(TruncatedToolCallPolicy.isTruncatedFinish("MAX_OUTPUT_TOKENS"))
    }

    @Test
    fun `truncated turn refuses the call and names the tool`() {
        val reason = TruncatedToolCallPolicy.rejectionReason("shell_execute", "length")
        assertNotNull(reason)
        assertTrue("should name the tool: $reason", reason!!.contains("shell_execute"))
        assertTrue("should carry the ceiling: $reason", reason.contains("length"))
    }

    @Test
    fun `healthy turn returns null so the call executes`() {
        assertNull(TruncatedToolCallPolicy.rejectionReason("shell_execute", "tool_calls"))
        assertNull(TruncatedToolCallPolicy.rejectionReason("shell_execute", "stop"))
        assertNull(TruncatedToolCallPolicy.rejectionReason("shell_execute", null))
    }

    @Test
    fun `gate triggers exactly when the finish is truncated`() {
        for (r in truncated) assertNotNull(TruncatedToolCallPolicy.rejectionReason("file_edit", r))
        for (r in notTruncated) assertNull(TruncatedToolCallPolicy.rejectionReason("file_edit", r))
    }

    @Test
    fun `rejection message stays stable enough to assert on`() {
        val reason = TruncatedToolCallPolicy.rejectionReason("file_edit", "max_tokens")!!
        assertEquals(1, Regex("\\[TRUNCATED\\]").findAll(reason).count())
        assertTrue(reason.startsWith("[TRUNCATED]"))
    }
}
