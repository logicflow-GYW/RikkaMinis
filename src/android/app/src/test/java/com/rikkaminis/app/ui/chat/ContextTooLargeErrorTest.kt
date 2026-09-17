package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the isContextTooLargeError substring set tightening
 * (fix/audit-0917-b9): "max_tokens" was dropped because it appears in OUTPUT
 * parameter errors ("max_tokens must be at most N") which halving the input
 * does NOT fix — classifying them sent the retry loop into repeated
 * non-converging halvings. Deliberate deviation from the iOS mirror.
 */
class ContextTooLargeErrorTest {

    @Test
    fun `output parameter max_tokens error is NOT context too large`() {
        assertFalse(isContextTooLargeError(Exception("max_tokens must be at most 4096")))
        assertFalse(isContextTooLargeError(Exception("max_tokens: 8192 > limit 4096")))
    }

    @Test
    fun `true input overflow errors are classified`() {
        for (msg in listOf(
            "prompt is too long: 200000 tokens",
            "This model's maximum context length is 128000 tokens",
            "request too large",
            "content is too long",
            "too many tokens in the request",
            "context window exceeded",
            "input exceeds the model's maximum",
        )) {
            assertTrue("should classify: $msg", isContextTooLargeError(Exception(msg)))
        }
    }

    @Test
    fun `matching is case insensitive`() {
        assertTrue(isContextTooLargeError(Exception("Prompt Is Too Long")))
        assertTrue(isContextTooLargeError(Exception("CONTEXT LENGTH EXCEEDED")))
    }

    @Test
    fun `null message falls back to toString`() {
        val e = object : RuntimeException(null as String?) {}
        assertFalse(isContextTooLargeError(e)) // "java.lang.RuntimeException" matches nothing
    }

    @Test
    fun `unrelated errors are not classified`() {
        assertFalse(isContextTooLargeError(Exception("connection reset")))
        assertFalse(isContextTooLargeError(Exception("HTTP 401 unauthorized")))
    }
}
