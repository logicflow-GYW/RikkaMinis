package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [ChatMessage.Companion.isInternalBridgeText].
 *
 * Pure Kotlin function with no Android dependency.
 */
class ChatMessageCompanionTest {

    @Test fun `isInternalBridgeText matches current wording`() {
        val current = "(Interrupted mid-task by a new user message. Decide based on the new " +
            "message and overall context whether the prior task should continue — do " +
            "not forget or abandon it unless the user explicitly says to stop, or the " +
            "new message makes clear it is no longer needed.)"
        assertTrue(ChatMessage.isInternalBridgeText(current))
    }

    @Test fun `isInternalBridgeText matches old wording`() {
        val old = "(Interrupted mid-task to handle your new message. Will return to the prior task after.)"
        assertTrue(ChatMessage.isInternalBridgeText(old))
    }

    @Test fun `isInternalBridgeText trims whitespace`() {
        val text = "  (Interrupted mid-task by a new user message. Decide based on the new " +
            "message and overall context whether the prior task should continue — do " +
            "not forget or abandon it unless the user explicitly says to stop, or the " +
            "new message makes clear it is no longer needed.)  "
        assertTrue(ChatMessage.isInternalBridgeText(text))
    }

    @Test fun `isInternalBridgeText trims trailing newline`() {
        val text = "(Interrupted mid-task to handle your new message. Will return to the prior task after.)\n"
        assertTrue(ChatMessage.isInternalBridgeText(text))
    }

    @Test fun `isInternalBridgeText rejects empty string`() {
        assertFalse(ChatMessage.isInternalBridgeText(""))
    }

    @Test fun `isInternalBridgeText rejects similar text`() {
        assertFalse(ChatMessage.isInternalBridgeText("(Interrupted mid-task by a user message.)"))
    }

    @Test fun `isInternalBridgeText rejects blank string`() {
        assertFalse(ChatMessage.isInternalBridgeText("   "))
    }

    @Test fun `isInternalBridgeText rejects null-like text`() {
        assertFalse(ChatMessage.isInternalBridgeText("null"))
    }

    @Test fun `isInternalBridgeText case sensitivity`() {
        val lower = "(interrupted mid-task by a new user message. decide based on the new " +
            "message and overall context whether the prior task should continue — do " +
            "not forget or abandon it unless the user explicitly says to stop, or the " +
            "new message makes clear it is no longer needed.)"
        assertFalse(ChatMessage.isInternalBridgeText(lower))
    }

    @Test fun `isInternalBridgeText partial prefix does not match`() {
        assertFalse(ChatMessage.isInternalBridgeText("(Interrupted mid-task"))
    }

    @Test fun `isInternalBridgeText partial suffix does not match`() {
        assertFalse(ChatMessage.isInternalBridgeText("no longer needed.)"))
    }
}