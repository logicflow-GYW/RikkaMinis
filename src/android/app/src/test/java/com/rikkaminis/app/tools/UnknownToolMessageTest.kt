package com.rikkaminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [audit-0916] Guards the corrective unknown-tool message.
 *
 * Motivating evidence is in [UnknownToolMessage]'s doc comment: a session on
 * 2026-09-16 burned eight round-trips on hallucinated tool names, each round
 * re-sending a ~420k-char conversation, because the rejection named nothing the
 * model could correct toward.
 */
class UnknownToolMessageTest {

    @Test
    fun `lists the real tool names so the model can correct itself`() {
        assertEquals(
            "Unknown tool: bash. This agent can use: file_read, shell_execute.",
            UnknownToolMessage.message("bash", listOf("shell_execute", "file_read")),
        )
    }

    @Test
    fun `names are sorted so repeated rejections are byte-identical`() {
        val a = UnknownToolMessage.message("x", listOf("b", "a", "c"))
        val b = UnknownToolMessage.message("x", listOf("c", "b", "a"))
        assertEquals(a, b)
    }

    @Test
    fun `subagent wording marks the tool as forbidden`() {
        val m = UnknownToolMessage.message("bash", listOf("file_read"), forbidden = true)
        assertTrue(m.startsWith("Error: Unknown or forbidden tool: bash"))
        assertTrue(m.contains("file_read"))
    }

    @Test
    fun `echoes markup residue but drops control characters`() {
        // Names arriving with markup stuck to them are real (see the doc
        // comment); echoing them is fine, injecting a newline is not.
        val m = UnknownToolMessage.message("shell_execute</arg_value>\nls /tmp", listOf("a"))
        assertFalse(m.contains("\n"))
        assertTrue(m.startsWith("Unknown tool: shell_execute</arg_value>ls /tmp"))
    }

    @Test
    fun `caps an absurdly long name`() {
        val m = UnknownToolMessage.message("x".repeat(500), listOf("a"))
        assertTrue(m.length < 200)
    }

    @Test
    fun `no known names still produces a clean rejection`() {
        assertEquals("Unknown tool: bash.", UnknownToolMessage.message("bash", listOf()))
    }

    @Test
    fun `blank names are ignored`() {
        assertEquals(
            "Unknown tool: b. This agent can use: a.",
            UnknownToolMessage.message("b", listOf("", "a", "")),
        )
    }
}
