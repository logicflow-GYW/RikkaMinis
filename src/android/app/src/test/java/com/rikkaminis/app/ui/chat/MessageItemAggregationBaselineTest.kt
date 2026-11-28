package com.rikkaminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

/** Message-level contracts after retirement of the old fragment pipeline. */
class MessageItemAggregationBaselineTest {
    private fun user(id: String) = ChatMessage(id, "user", "hello")
    private fun assistant(id: String, content: String = "fallback") = ChatMessage(
        id, "assistant", content, toolBlocks = listOf(
            AssistantBlock("text-$id", "text", "first"),
            AssistantBlock("tool-$id", "tool_use", toolStatus = ToolBlockStatus.SUCCESS),
            AssistantBlock("think-$id", "thinking", "reasoning"),
            AssistantBlock("text2-$id", "text", "second"),
        ),
    )

    @Test fun `full builder equals from zero and incremental prefix retains identity`() {
        val base = listOf(user("u1"), assistant("a1"))
        val appended = base + listOf(user("u2"), assistant("a2"))
        for (input in listOf(emptyList(), base, appended)) {
            val full = buildAggregateChatItems(input)
            val fromZero = buildAggregateChatItemsFrom(input, 0)
            assertEquals(fromZero, full)
            assertEquals(fromZero.map { it.key to it.contentType }, full.map { it.key to it.contentType })
            assertEquals(fromZero.filterIsInstance<FlatChatItem.AssistantMessageItem>().map { it.messageMarkdown },
                full.filterIsInstance<FlatChatItem.AssistantMessageItem>().map { it.messageMarkdown })
        }
        val previous = buildAggregateChatItems(base)
        val incremental = buildAggregateChatItemsIncremental(previous, base, appended)
        assertEquals(buildAggregateChatItems(appended), incremental)
        previous.indices.forEach { assertSame(previous[it], incremental[it]) }
        assertEquals(listOf("user:u1", "msg:a1", "user:u2", "msg:a2"), incremental.map { it.key })
        assertEquals("first\n\nsecond", (incremental.last() as FlatChatItem.AssistantMessageItem).messageMarkdown)
    }

    @Test fun `overlay preserves frozen prefix and replaces live fields including empty blocks`() {
        val base = listOf(user("u1"), assistant("a1"))
        val delta = StreamingDelta("grown", emptyList(), true, 7L)
        val merged = mergeStreamingOverlay(base, mapOf("a1" to delta), currentEpoch = 7L)
        assertSame(base[0], merged[0])
        assertNotSame(base[1], merged[1])
        assertEquals("fallback", base[1].content)
        assertEquals("grown", merged[1].content)
        assertTrue(merged[1].isStreaming)
        assertTrue(merged[1].isAwaitingModelResponse)
        assertTrue(merged[1].toolBlocks.isEmpty())
        val rows = buildAggregateChatItems(merged)
        assertEquals(listOf("user:u1", "msg:a1"), rows.map { it.key })
        assertEquals(listOf("u1", "a1"), rows.map { it.owningMessageId() })
        assertEquals("grown", (rows[1] as FlatChatItem.AssistantMessageItem).messageMarkdown)
    }

    @Test fun `repeated builds preserve keys and row types`() {
        val input = listOf(user("u1"), user("u1"), assistant("a1"))
        val first = buildAggregateChatItems(input)
        val second = buildAggregateChatItems(input)
        assertEquals(first, second)
        assertEquals(listOf("user:u1", "msg:a1"), first.map { it.key })
        assertEquals(listOf("user", "assistantMessage"), first.map { it.contentType })
        assertEquals(listOf("u1", "a1"), first.map { it.owningMessageId() })
    }
}
