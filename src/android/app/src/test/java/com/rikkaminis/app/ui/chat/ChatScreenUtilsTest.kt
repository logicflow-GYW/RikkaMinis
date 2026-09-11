package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for pure functions extracted from [ChatScreen]:
 * [originalMessageId] and [isCompactedItem].
 */
class ChatScreenUtilsTest {

    // ── originalMessageId ─────────────────────────────────────────────

    @Test
    fun `originalMessageId returns plain id unchanged`() {
        assertEquals("msg_abc123", originalMessageId("msg_abc123"))
    }

    @Test
    fun `originalMessageId strips numeric dedupe suffix`() {
        assertEquals("msg_abc123", originalMessageId("msg_abc123#2"))
    }

    @Test
    fun `originalMessageId strips double digit suffix`() {
        assertEquals("msg_abc123", originalMessageId("msg_abc123#42"))
    }

    @Test
    fun `originalMessageId returns empty string for empty input`() {
        assertEquals("", originalMessageId(""))
    }

    @Test
    fun `originalMessageId handles hash with no number`() {
        assertEquals("", originalMessageId("#"))
    }

    @Test
    fun `originalMessageId strips at first hash`() {
        // substringBefore('#') drops everything from the FIRST hash onward
        assertEquals("a", originalMessageId("a#b"))
    }

    @Test
    fun `originalMessageId strips all suffixes after first hash`() {
        assertEquals("a", originalMessageId("a#b#2"))
    }

    // ── isCompactedItem ───────────────────────────────────────────────

    private val grayed = mapOf(
        "m_user" to true,
        "m_header" to true,
        "m_text" to true,
        "m_mdblock" to true,
        "m_think" to true,
        "m_tool" to true,
        "m_toolrun" to true,
        "m_info" to true, // system row — should still never gray
        "m_typing" to true, // system row — should still never gray
        "m_error" to true,
        "m_legacy" to true,
        "m_false" to false, // explicitly false → not grayed
    )

    private fun userBubble(id: String) =
        FlatChatItem.UserBubble(ChatMessage(id = id, role = "user", content = "hi"))

    private fun block(id: String, kind: String) = AssistantBlock(id = id, kind = kind)

    @Test
    fun `user bubble grayed when id in map`() {
        assertTrue(isCompactedItem(userBubble("m_user"), grayed))
    }

    @Test
    fun `user bubble not grayed when id absent`() {
        assertFalse(isCompactedItem(userBubble("absent"), grayed))
    }

    @Test
    fun `dedupe suffix id resolves to grayed base id`() {
        // buildFlatChatItems appends `#N` dedupe suffixes; lookup strips them.
        assertTrue(isCompactedItem(userBubble("m_user#2"), grayed))
    }

    @Test
    fun `explicit false value is not grayed`() {
        assertFalse(isCompactedItem(userBubble("m_false"), grayed))
    }

    @Test
    fun `assistant header grayed when id in map`() {
        assertTrue(isCompactedItem(FlatChatItem.AssistantHeader("m_header"), grayed))
    }

    @Test
    fun `assistant text grayed when id in map`() {
        assertTrue(
            isCompactedItem(
                FlatChatItem.AssistantText("m_text", block("b1", "text"), isStreaming = false, messageMarkdown = "md"),
                grayed,
            ),
        )
    }

    @Test
    fun `assistant markdown block grayed when id in map`() {
        assertTrue(
            isCompactedItem(
                FlatChatItem.AssistantMarkdownBlock(
                    messageId = "m_mdblock",
                    parentBlockId = "b1",
                    rawText = "t",
                    blockIndex = 0,
                    isLastBlockOfMessage = true,
                    messageIsStreaming = false,
                    messageMarkdown = "md",
                ),
                grayed,
            ),
        )
    }

    @Test
    fun `assistant thinking grayed when id in map`() {
        assertTrue(
            isCompactedItem(
                FlatChatItem.AssistantThinking("m_think", block("b1", "thinking"), isLast = false, messageIsStreaming = false),
                grayed,
            ),
        )
    }

    @Test
    fun `assistant tool use grayed when id in map`() {
        assertTrue(
            isCompactedItem(
                FlatChatItem.AssistantToolUse("m_tool", block("b1", "tool_use"), allToolBlocks = emptyList()),
                grayed,
            ),
        )
    }

    @Test
    fun `assistant tool run group grayed when id in map`() {
        assertTrue(
            isCompactedItem(
                FlatChatItem.AssistantToolRunGroup(
                    messageId = "m_toolrun",
                    tools = listOf(block("b1", "tool_use")),
                    isRunning = false,
                    isLastCancelled = false,
                ),
                grayed,
            ),
        )
    }

    @Test
    fun `assistant info never grayed even when id in map`() {
        assertFalse(isCompactedItem(FlatChatItem.AssistantInfo("m_info", block("b1", "info")), grayed))
    }

    @Test
    fun `assistant typing never grayed even when id in map`() {
        assertFalse(isCompactedItem(FlatChatItem.AssistantTyping("m_typing"), grayed))
    }

    @Test
    fun `assistant error grayed when id in map`() {
        assertTrue(isCompactedItem(FlatChatItem.AssistantError("m_error", "err"), grayed))
    }

    @Test
    fun `assistant legacy content grayed when id in map`() {
        assertTrue(isCompactedItem(FlatChatItem.AssistantLegacyContent("m_legacy", content = "c", isStreaming = false), grayed))
    }

    @Test
    fun `empty grayed map never grays`() {
        assertFalse(isCompactedItem(userBubble("m_user"), emptyMap()))
        assertFalse(isCompactedItem(FlatChatItem.AssistantInfo("m_info", block("b1", "info")), emptyMap()))
    }

    // ── shouldDebounceImeBurst ────────────────────────────────────────

    @Test
    fun `ordinary typing is never debounced`() {
        assertFalse(shouldDebounceImeBurst("", "a"))
        assertFalse(shouldDebounceImeBurst("ab", "abc"))
        assertFalse(shouldDebounceImeBurst("hello worl", "hello world"))
    }

    @Test
    fun `delta exactly at threshold is not debounced`() {
        // growth of exactly 8 chars — boundary is "greater than", not ">="
        assertFalse(shouldDebounceImeBurst("1234", "123456789012"))
    }

    @Test
    fun `delta just past threshold is debounced`() {
        assertTrue(shouldDebounceImeBurst("1234", "1234567890123"))
    }

    @Test
    fun `large voice dictation burst is debounced`() {
        assertTrue(shouldDebounceImeBurst("", "The quick brown fox jumps over the lazy dog"))
    }

    @Test
    fun `deletion is never a burst`() {
        assertFalse(shouldDebounceImeBurst("a long sentence", "a"))
        assertFalse(shouldDebounceImeBurst("ab", "a"))
    }

    @Test
    fun `replacement with no net growth is not debounced`() {
        assertFalse(shouldDebounceImeBurst("abcdefgh", "ABCDEFGH"))
    }

    // ── shouldCommitImeBurst ─────────────────────────────────────────

    @Test
    fun `burst commits while it is still the live field text`() {
        assertTrue(shouldCommitImeBurst("pasted text", "old", "pasted text"))
    }

    @Test
    fun `burst dropped when newer edits landed in the window`() {
        // User typed a prefix or a trailing word after the burst: the live
        // text moved on, and the immediate-commit path already delivered it.
        // Committing the snapshot now would overwrite those edits — the
        // exact "composer ate my text" bug this predicate exists to prevent.
        assertFalse(shouldCommitImeBurst("pasted text", "old", "pasted text + tail"))
        assertFalse(shouldCommitImeBurst("pasted text", "old", "prefix + pasted text"))
        assertFalse(shouldCommitImeBurst("pasted text", "old", "pasted"))
    }

    @Test
    fun `burst dropped when already committed`() {
        assertFalse(shouldCommitImeBurst("same", "same", "same"))
    }
}
