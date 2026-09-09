package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/message-node-item-generator] JVM tests for the message-level aggregate
 * generator [buildAggregateChatItems] and the [FlatChatItem.AssistantMessageItem]
 * item type it produces. Contract under test: **one ChatMessage in → exactly
 * one FlatChatItem out** (bridge messages excepted).
 */
class AggregateChatItemGeneratorTest {

    // ── helpers ──────────────────────────────────────────────────────────

    private fun assistantMessage(
        id: String,
        textBlocks: List<String> = emptyList(),
        withTool: Boolean = false,
        withThinking: Boolean = false,
        content: String = "",
    ): ChatMessage {
        val blocks = mutableListOf<AssistantBlock>()
        textBlocks.forEachIndexed { i, t ->
            blocks.add(AssistantBlock(id = "b$i", kind = "text", content = t))
        }
        if (withTool) {
            blocks.add(AssistantBlock(
                id = "tool1",
                kind = "tool_use",
                content = "{\"name\":\"ls\"}",
                toolStatus = ToolBlockStatus.SUCCESS,
            ))
        }
        if (withThinking) {
            blocks.add(AssistantBlock(id = "think1", kind = "thinking", content = "reasoning…"))
        }
        return ChatMessage(id = id, role = "assistant", content = content, toolBlocks = blocks)
    }

    private fun userMessage(id: String) = ChatMessage(id = id, role = "user", content = "hello")

    // ── 1 message → 1 item ────────────────────────────────────────────────

    @Test
    fun `user plus assistant with text tool and thinking yields two items`() {
        val messages = listOf(
            userMessage("u1"),
            assistantMessage(
                "a1",
                textBlocks = listOf("para one", "para two"),
                withTool = true,
                withThinking = true,
            ),
        )
        val items = buildAggregateChatItems(messages)
        assertEquals(2, items.size)
        assertTrue(items[0] is FlatChatItem.UserBubble)
        assertTrue(items[1] is FlatChatItem.AssistantMessageItem)
    }

    @Test
    fun `a whole multi-block assistant message collapses into a single item`() {
        val m = assistantMessage("a1", textBlocks = listOf("one", "two"), withTool = true, withThinking = true)
        val items = buildAggregateChatItems(listOf(m))
        assertEquals(1, items.size)
        val item = items[0] as FlatChatItem.AssistantMessageItem
        assertEquals("a1", item.messageId)
        assertEquals(m, item.message)
    }

    @Test
    fun `user mapped to UserBubble with precededByUser mirroring existing lookback`() {
        // Back-to-back user messages → second flagged precededByUser.
        val items = buildAggregateChatItems(listOf(userMessage("u1"), userMessage("u2"), userMessage("u3")))
        assertEquals(3, items.size)
        val b1 = items[0] as FlatChatItem.UserBubble
        val b2 = items[1] as FlatChatItem.UserBubble
        val b3 = items[2] as FlatChatItem.UserBubble
        assertFalse(b1.precededByUser)
        assertTrue(b2.precededByUser)
        assertTrue(b3.precededByUser)
    }

    // ── messageMarkdown joining aligns with buildFlatChatItems semantics ──

    @Test
    fun `messageMarkdown joins text blocks with blank line else falls back to content`() {
        val withBlocks = assistantMessage("a1", textBlocks = listOf("one", "two"))
        val itemA = buildAggregateChatItems(listOf(withBlocks))[0] as FlatChatItem.AssistantMessageItem
        assertEquals("one\n\ntwo", itemA.messageMarkdown)

        // Legacy: no text blocks → messageMarkdown == content.
        val legacy = ChatMessage(id = "a2", role = "assistant", content = "plain answer")
        val itemB = buildAggregateChatItems(listOf(legacy))[0] as FlatChatItem.AssistantMessageItem
        assertEquals("plain answer", itemB.messageMarkdown)
    }

    // ── cheap-equals: frozen vs streaming ─────────────────────────────────

    @Test
    fun `frozen message items are equal`() {
        val m = assistantMessage("a1", textBlocks = listOf("frozen text"))
        val i1 = FlatChatItem.AssistantMessageItem("a1", m, "frozen text")
        val i2 = FlatChatItem.AssistantMessageItem("a1", m, "frozen text")
        // Same message instance → identity-equal fast path → equals true.
        assertEquals(i1, i2)
        assertEquals(i1.hashCode(), i2.hashCode())
    }

    @Test
    fun `streaming new instance is not equal`() {
        val frozen = assistantMessage("a1", textBlocks = listOf("streaming"))
        val streamingTick = frozen.copy(isStreaming = true)
        val i1 = FlatChatItem.AssistantMessageItem("a1", frozen, "streaming")
        val i2 = FlatChatItem.AssistantMessageItem("a1", streamingTick, "streaming")
        // Different message instances (streaming arrives as a fresh instance
        // per tick) → equals false → the row recomposes for the live tail.
        assertNotEquals(i1, i2)
    }

    @Test
    fun `same content different message instances are not equal`() {
        // Two distinct ChatMessage instances (data class equals would say
        // "equal" on identical fields) must be unequal at the item level —
        // the identity comparison is what drives streaming recomposition.
        // copy() yields a fresh instance (=== distinct) even though data-class
        // equals sees them as equal.
        val m1 = assistantMessage("a1", textBlocks = listOf("same"))
        val m2 = m1.copy()
        val i1 = FlatChatItem.AssistantMessageItem("a1", m1, "same")
        val i2 = FlatChatItem.AssistantMessageItem("a1", m2, "same")
        // Fresh instance → identity differs → items must be unequal.
        assertNotEquals(i1, i2)
    }

    // ── bridge messages are skipped ───────────────────────────────────────

    @Test
    fun `internal bridge messages are skipped`() {
        val bridgeText =
            "(Interrupted mid-task by a new user message. Decide based on the new " +
                "message and overall context whether the prior task should continue — do " +
                "not forget or abandon it unless the user explicitly says to stop, or the " +
                "new message makes clear it is no longer needed.)"
        val messages = listOf(
            userMessage("u1"),
            ChatMessage(id = "bridge", role = "assistant", content = bridgeText),
            assistantMessage("a2", textBlocks = listOf("real answer")),
        )
        val items = buildAggregateChatItems(messages)
        // u1 + a2 → the bridge assistant row is filtered out.
        assertEquals(2, items.size)
        assertEquals("u1", (items[0] as FlatChatItem.UserBubble).message.id)
        assertEquals("a2", (items[1] as FlatChatItem.AssistantMessageItem).messageId)
    }

    // ── keys are unique (no collisions) ───────────────────────────────────

    @Test
    fun `keys are unique across mixed messages`() {
        val messages = listOf(
            userMessage("u1"),
            assistantMessage("a1", textBlocks = listOf("one"), withTool = true),
            assistantMessage("a2", textBlocks = listOf("two"), withThinking = true),
            userMessage("u2"),
        )
        val items = buildAggregateChatItems(messages)
        val keys = items.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        // Spot-check the mapping shapes.
        assertEquals("user:u1", items[0].key)
        assertEquals("msg:a1", items[1].key)
        assertEquals("msg:a2", items[2].key)
        assertEquals("user:u2", items[3].key)
    }

    @Test
    fun `key content type and no streamed leading artifact`() {
        val m = assistantMessage("a1")
        val item = FlatChatItem.AssistantMessageItem("a1", m, "")
        assertEquals("msg:a1", item.key)
        assertEquals("assistantMessage", item.contentType)
    }

    // ── incremental rebuild (fix/long-session-aggregate-storm) ────────────

    @Test
    fun `incremental cold start equals full build`() {
        val messages = listOf(
            userMessage("u1"),
            assistantMessage("a1", textBlocks = listOf("one"), withTool = true),
            assistantMessage("a2", textBlocks = listOf("two"), withThinking = true),
        )
        val full = buildAggregateChatItems(messages)
        val incr = buildAggregateChatItemsIncremental(emptyList(), emptyList(), messages)
        assertEquals(full.map { it.key }, incr.map { it.key })
    }

    @Test
    fun `incremental identical list returns same reference`() {
        val messages = listOf(
            userMessage("u1"),
            assistantMessage("a1", textBlocks = listOf("one")),
        )
        val first = buildAggregateChatItems(messages)
        val second = buildAggregateChatItemsIncremental(first, messages, messages)
        assertTrue(first === second) // reused by reference, zero rebuild
    }

    @Test
    fun `incremental tail append reuses prefix items by reference`() {
        val base = listOf(
            userMessage("u1"),
            assistantMessage("a1", textBlocks = listOf("one")),
        )
        val appended = base + assistantMessage("a2", textBlocks = listOf("two"))
        val prev = buildAggregateChatItems(base)
        val next = buildAggregateChatItemsIncremental(prev, base, appended)

        // Contract: one message → one item, so appended adds exactly one item.
        assertEquals(prev.size + 1, next.size)
        // Prefix items are the SAME instances (identity reuse).
        for (i in prev.indices) assertTrue(prev[i] === next[i])
        // The new tail is a fresh AssistantMessageItem for a2.
        val tail = next.last() as FlatChatItem.AssistantMessageItem
        assertEquals("a2", tail.messageId)
        // Keys match a full build of the appended list exactly.
        assertEquals(
            buildAggregateChatItems(appended).map { it.key },
            next.map { it.key },
        )
    }

    @Test
    fun `incremental tail mutate rebuilds only the changed message`() {
        val base = listOf(
            userMessage("u1"),
            assistantMessage("a1", textBlocks = listOf("one")),
        )
        // The live tail message is a NEW instance (streaming `copy`), prefix
        // messages are the SAME instances — mirrors mergeStreamingOverlay.
        val mutated = assistantMessage("a1", textBlocks = listOf("one two three"))
        val nextMessages = listOf(base[0], mutated)
        val prev = buildAggregateChatItems(base)
        val next = buildAggregateChatItemsIncremental(prev, base, nextMessages)

        assertEquals(prev.size, next.size)
        // head (user bubble) reused by reference; tail rebuilt.
        assertTrue(prev[0] === next[0])
        assertTrue(prev[1] !== next[1])
        assertEquals(
            buildAggregateChatItems(nextMessages).map { it.key },
            next.map { it.key },
        )
    }

    @Test
    fun `incremental mid-list change falls back to full rebuild semantics`() {
        val base = listOf(
            userMessage("u1"),
            assistantMessage("a1", textBlocks = listOf("one")),
            assistantMessage("a2", textBlocks = listOf("two")),
        )
        // Middle message replaced (compact / edit) — identity breaks at idx 1.
        val edited = assistantMessage("a1", textBlocks = listOf("one REVISED"))
        val nextMessages = listOf(base[0], edited, base[2])
        val prev = buildAggregateChatItems(base)
        val next = buildAggregateChatItemsIncremental(prev, base, nextMessages)

        // Keys equal a full build — correctness preserved even though the
        // prefix reuse stops early.
        assertEquals(
            buildAggregateChatItems(nextMessages).map { it.key },
            next.map { it.key },
        )
    }

    @Test
    fun `incremental preserves precededByUser lookback across boundary`() {
        // End of prefix is a user; the first appended message is also a user
        // → the appended UserBubble must carry precededByUser=true.
        val base = listOf(userMessage("u1"), userMessage("u2"))
        val appended = base + userMessage("u3")
        val prev = buildAggregateChatItems(base)
        val next = buildAggregateChatItemsIncremental(prev, base, appended)
        val tail = next.last() as FlatChatItem.UserBubble
        assertTrue(tail.precededByUser)

        // Full build agrees.
        val fullTail = buildAggregateChatItems(appended).last() as FlatChatItem.UserBubble
        assertEquals(fullTail.precededByUser, tail.precededByUser)
    }
}
