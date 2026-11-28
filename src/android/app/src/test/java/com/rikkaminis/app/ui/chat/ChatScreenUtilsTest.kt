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

    private val grayed = mapOf("user" to true, "assistant" to true, "system" to true, "false" to false)

    private fun userBubble(id: String) =
        FlatChatItem.UserBubble(ChatMessage(id = id, role = "user", content = "hi"))

    private fun assistantRow(id: String, role: String = "assistant") =
        FlatChatItem.AssistantMessageItem(id, ChatMessage(id, role, "answer"), "answer")

    @Test fun `user bubble grayed when id in map`() {
        assertTrue(isCompactedItem(userBubble("user"), grayed))
        assertFalse(isCompactedItem(userBubble("absent"), grayed))
        assertFalse(isCompactedItem(userBubble("false"), grayed))
    }

    @Test fun `assistant message grayed with dedupe suffix stripped`() {
        assertTrue(isCompactedItem(assistantRow("assistant"), grayed))
        assertTrue(isCompactedItem(assistantRow("assistant#2"), grayed))
        assertTrue(isCompactedItem(userBubble("user#2"), grayed))
        assertFalse(isCompactedItem(assistantRow("absent"), grayed))
        assertFalse(isCompactedItem(assistantRow("false"), grayed))
    }

    @Test fun `system role never grayed even with deduped id in map`() {
        assertFalse(isCompactedItem(assistantRow("system", "system"), grayed))
        assertFalse(isCompactedItem(assistantRow("system#2", "system"), grayed))
    }

    @Test fun `empty grayed map never grays`() {
        assertFalse(isCompactedItem(userBubble("user"), emptyMap()))
        assertFalse(isCompactedItem(assistantRow("assistant"), emptyMap()))
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
