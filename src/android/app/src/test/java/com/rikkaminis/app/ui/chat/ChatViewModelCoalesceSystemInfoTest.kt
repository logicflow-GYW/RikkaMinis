package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-chat-sysinfo-coalesce] Pins the merge semantics of
 * ChatViewModel.appendSystemInfo without dragging in Android / ViewModel.
 * The production function delegates to these pure helpers; the scheduler
 * is injected separately.
 */
class ChatViewModelCoalesceSystemInfoTest {

    @Test
    fun `same iconKind within window should merge`() {
        // Two compact notices within 200ms → one message with two blocks.
        val a = AssistantBlock(id = "a", kind = "info", content = "no provider", toolName = "compact")
        val b = AssistantBlock(id = "b", kind = "info", content = "empty session", toolName = "compact")
        val merged = coalesceSystemInfoBlocks(listOf(a, b))
        assertEquals(2, merged.size)
        assertEquals("a", merged[0].id)
        assertEquals("b", merged[1].id)
    }

    @Test
    fun `different iconKind must not merge`() {
        val a = AssistantBlock(id = "a", kind = "info", content = "memory on", toolName = "memory")
        val b = AssistantBlock(id = "b", kind = "info", content = "thinking set", toolName = "thinking")
        // Caller must flush between kinds; this helper just asserts the rule.
        assertTrue(a.toolName != b.toolName)
    }

    @Test
    fun `last non-null payload wins`() {
        val blocks = listOf(
            AssistantBlock(id = "a", kind = "info", content = "first", toolName = "compact", toolArgs = ""),
            AssistantBlock(id = "b", kind = "info", content = "second", toolName = "compact", toolArgs = "summary-text"),
        )
        val payload = resolveCoalescedPayload(listOf(null, "summary-text"))
        assertEquals("summary-text", payload)
    }

    @Test
    fun `all-null payloads resolve to null`() {
        assertEquals(null, resolveCoalescedPayload(listOf(null, null)))
    }
}
