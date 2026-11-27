package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatProcessPresentationTest {
    private fun tool(id: String, status: ToolBlockStatus? = null) =
        AssistantBlock(id, "tool_use", toolStatus = status, toolName = "shell_execute")

    private fun text(id: String) = AssistantBlock(id, "text", content = "One line of reply")
    private fun thinking(id: String) = AssistantBlock(id, "thinking", content = "Reasoning text")
    private fun inlineKeys(blocks: List<AssistantBlock>) = blocks.filterNot { it.isChatProcessBlock() }.map { it.id }

    @Test fun `a later tool does not reclassify text notices or unknown renderable blocks`() {
        val prefix = listOf(text("a"), AssistantBlock("i", "info"), AssistantBlock("u", "future"))
        val withWork = prefix + listOf(thinking("h"), tool("t", ToolBlockStatus.SUCCESS), text("b"))
        assertEquals(listOf("a", "i", "u"), inlineKeys(prefix))
        assertEquals(listOf("a", "i", "u", "b"), inlineKeys(withWork))
        assertEquals(listOf("h", "t"), chatProcessBlocks(withWork, showThinking = true).map { it.id })
    }

    @Test fun `every parallel pending streaming running and settled tool is detail only`() {
        val all = ToolBlockStatus.entries.map { tool(it.name, it) } + tool("legacy")
        val work = listOf(text("intro")) + all + text("last")
        assertEquals(listOf("intro", "last"), inlineKeys(work))
        assertEquals(all, chatProcessBlocks(work, showThinking = false))
        val status = chatProcessStatus(work, showThinking = false)
        assertEquals(9, status.calls)
        assertEquals(4, status.running)
        assertEquals(2, status.failed)
        assertEquals(1, status.cancelled)
        assertEquals(1, status.skipped)
        assertFalse(status.allSucceeded)
    }

    @Test fun `out of order completion preserves details identity and the same visible body`() {
        val before = listOf(text("a"), tool("x", ToolBlockStatus.RUNNING), text("b"), tool("y", ToolBlockStatus.PENDING))
        val after = before.map { if (it.id == "y") it.copy(toolStatus = ToolBlockStatus.SUCCESS) else it }
        val done = after.map { if (it.id == "x") it.copy(toolStatus = ToolBlockStatus.SUCCESS) else it }
        for (version in listOf(before, after, done)) {
            assertEquals(listOf("a", "b"), inlineKeys(version))
            assertEquals(listOf("x", "y"), chatProcessBlocks(version, showThinking = true).map { it.id })
            assertTrue(chatProcessStatus(version, showThinking = true).hasDetails)
        }
        assertEquals(2, chatProcessStatus(before, true).running)
        assertEquals(1, chatProcessStatus(after, true).running)
        assertEquals(0, chatProcessStatus(done, true).running)
        assertTrue(chatProcessStatus(done, true).allSucceeded)
    }

    @Test fun `thinking only keeps details available and respects disabled thinking`() {
        val work = listOf(thinking("h"), AssistantBlock("notice", "info"))
        val shown = chatProcessStatus(work, showThinking = true)
        assertTrue(shown.hasDetails)
        assertTrue(shown.isThinkingNow)
        assertEquals(listOf("h"), chatProcessBlocks(work, showThinking = true).map { it.id })
        assertFalse(chatProcessStatus(work, showThinking = false).hasDetails)
        assertTrue(chatProcessBlocks(work, showThinking = false).isEmpty())
        assertFalse(chatProcessStatus(work + text("reply"), showThinking = true).isThinkingNow)
    }

    @Test fun `the same historical and current thinking preference decides both displays`() {
        val legacy = ChatMessage(id = "m", role = "assistant", content = "old")
        assertTrue(legacy.canShowProcessThinking())
        assertFalse(legacy.copy(thinkingLevel = ThinkingLevel.OFF).canShowProcessThinking())
        assertTrue(legacy.copy(thinkingLevel = ThinkingLevel.MEDIUM).canShowProcessThinking())
    }

    @Test fun `floating bar cannot claim its old padding in compact mode`() {
        for (hasTools in listOf(false, true)) for (enabled in listOf(false, true)) {
            assertFalse(showsLegacyToolBar(compactProcess = true, enabled = enabled, hasTools = hasTools))
            assertEquals(enabled && hasTools, showsLegacyToolBar(compactProcess = false, enabled = enabled, hasTools = hasTools))
        }
    }
}
