package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the forward-list index helpers in [ChatScreenUtils]:
 * [firstRowIndexOfMessage], [bottomSentinelIndex], [shiftedIndexAfterHeadInsert],
 * [forwardIndexFromReversed].
 *
 * These replace the reverseLayout-era reversed-space math; the migration's
 * stability promise is "keys, not numeric slots, are the anchor".
 */
class ChatListIndexMappingTest {

    private val rows = listOf<FlatChatItem>(
        FlatChatItem.UserBubble(ChatMessage(id = "u1", role = "user", content = "hi")),
        FlatChatItem.AssistantHeader("a1"),
        FlatChatItem.AssistantMarkdownBlock(
            messageId = "a1", parentBlockId = "b0", rawText = "A", blockIndex = 0,
            isLastBlockOfMessage = false, messageIsStreaming = false, messageMarkdown = "A",
        ),
        FlatChatItem.AssistantHeader("a2"),
    )

    @Test fun `first row of a message is found in natural order`() {
        assertEquals(0, firstRowIndexOfMessage(rows, "u1"))
        assertEquals(1, firstRowIndexOfMessage(rows, "a1"))
        assertEquals(3, firstRowIndexOfMessage(rows, "a2"))
    }

    @Test fun `absent message returns null`() {
        assertNull(firstRowIndexOfMessage(rows, "ghost"))
    }

    @Test fun `bottom sentinel is always one past the last row`() {
        assertEquals(rows.size, bottomSentinelIndex(rows.size))
        assertEquals(0, bottomSentinelIndex(0))
    }

    @Test fun `head insert shifts every visible key index by the inserted count`() {
        val oldIdx = firstRowIndexOfMessage(rows, "a1")!!
        val inserted = 3 // e.g. three older messages' rows prepended
        val newIdx = shiftedIndexAfterHeadInsert(oldIdx, inserted)
        assertEquals(oldIdx + inserted, newIdx)
        // Key itself is unchanged — this is the load-older stability promise.
        assertEquals("a1", rows[oldIdx].owningMessageId())
    }

    @Test fun `tail append does not move existing indices`() {
        val before = firstRowIndexOfMessage(rows, "a1")!!
        // New rows appended at the tail: index untouched.
        assertEquals(before, shiftedIndexAfterHeadInsert(before, 0))
    }

    @Test fun `forward-from-reversed conversion mirrors correctly`() {
        // reverseLayout-era: newest at index 0. Forward: newest at last index.
        assertEquals(3, forwardIndexFromReversed(0, 4))
        assertEquals(0, forwardIndexFromReversed(3, 4))
        assertEquals(1, forwardIndexFromReversed(2, 4))
    }

    @Test fun `focus target resolution maps message to a valid forward index`() {
        val idx = firstRowIndexOfMessage(rows, "a2") ?: error("target missing")
        assertTrue(idx in rows.indices)
        assertEquals("a2", rows[idx].owningMessageId())
    }
}
