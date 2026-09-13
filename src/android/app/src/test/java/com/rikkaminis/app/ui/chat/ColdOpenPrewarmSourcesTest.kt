package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/open-row-first-frame-final] JVM tests for the cold-open markdown prewarm
 * source selection ([ChatColdOpenPrewarm.kt]).
 *
 * Contract under test: the sources handed to the prewarm are exactly the
 * strings the rows will later feed to the markdown parser — for the row type
 * the pipeline ACTUALLY emits (`AGGREGATE_MESSAGE_ITEMS = true` →
 * [FlatChatItem.AssistantMessageItem]) as well as the legacy row types.
 *
 * The regression this pins: the old inline expression
 * `(item as? FlatChatItem.AssistantMarkdownBlock)?.rawText` matched nothing
 * once the aggregate generator became the default, so the whole prewarm pass
 * silently parsed nothing (no `coldPrewarm.done` line in any device log, and
 * every session open's newest row grew after the opening snap).
 */
class ColdOpenPrewarmSourcesTest {

    // ── helpers ──────────────────────────────────────────────────────────

    private fun assistantMessage(
        id: String,
        textBlocks: List<String> = emptyList(),
        content: String = "",
        toolBlocks: Boolean = true,
    ): ChatMessage {
        val blocks = mutableListOf<AssistantBlock>()
        textBlocks.forEachIndexed { i, t ->
            blocks.add(AssistantBlock(id = "b$i", kind = "text", content = t))
        }
        if (toolBlocks) {
            blocks.add(AssistantBlock(id = "tool1", kind = "tool_use", content = "{\"name\":\"ls\"}"))
            blocks.add(AssistantBlock(id = "think1", kind = "thinking", content = "reasoning…"))
        }
        return ChatMessage(
            id = id,
            role = "assistant",
            content = content,
            toolBlocks = blocks,
        )
    }

    private fun aggregateRow(
        id: String,
        textBlocks: List<String> = emptyList(),
        content: String = "",
    ): FlatChatItem.AssistantMessageItem {
        val message = assistantMessage(id = id, textBlocks = textBlocks, content = content)
        return FlatChatItem.AssistantMessageItem(
            messageId = id,
            message = message,
            messageMarkdown = textBlocks.joinToString("\n\n"),
        )
    }

    // ── per-row extraction ───────────────────────────────────────────────

    @Test
    fun `aggregate row yields its text blocks in order`() {
        val row = aggregateRow("m1", textBlocks = listOf("first paragraph", "second paragraph"))

        assertEquals(listOf("first paragraph", "second paragraph"), markdownSourcesForRow(row))
    }

    @Test
    fun `aggregate row falls back to message content when it has no text block`() {
        // Legacy / pre-text-block sessions persist only message.content.
        val row = aggregateRow("m1", textBlocks = emptyList(), content = "legacy body")

        assertEquals(listOf("legacy body"), markdownSourcesForRow(row))
    }

    @Test
    fun `aggregate row ignores empty text blocks and empty content`() {
        val emptyBlocks = aggregateRow("m1", textBlocks = listOf("", ""), content = "")
        assertTrue(markdownSourcesForRow(emptyBlocks).isEmpty())

        // A message that HAS a non-empty text block never falls back to
        // content — even when content itself is empty (renderer parity with
        // AssistantMessageView's `hasAnyTextBlock` branch).
        val textOnly = aggregateRow("m2", textBlocks = listOf("body"), content = "")
        assertEquals(listOf("body"), markdownSourcesForRow(textOnly))
    }

    @Test
    fun `legacy flat rows still yield their markdown`() {
        val markdownBlock = FlatChatItem.AssistantMarkdownBlock(
            messageId = "m1",
            parentBlockId = "b1",
            rawText = "fragment body",
            blockIndex = 0,
            isLastBlockOfMessage = true,
            messageIsStreaming = false,
            messageMarkdown = "fragment body",
        )
        assertEquals(listOf("fragment body"), markdownSourcesForRow(markdownBlock))

        val textRow = FlatChatItem.AssistantText(
            messageId = "m1",
            block = AssistantBlock(id = "b1", kind = "text", content = "text row body"),
            isStreaming = false,
            messageMarkdown = "text row body",
        )
        assertEquals(listOf("text row body"), markdownSourcesForRow(textRow))

        val legacy = FlatChatItem.AssistantLegacyContent(
            messageId = "m1",
            content = "legacy row body",
            isStreaming = false,
        )
        assertEquals(listOf("legacy row body"), markdownSourcesForRow(legacy))
    }

    @Test
    fun `rows that render no markdown body yield nothing`() {
        val user = FlatChatItem.UserBubble(
            message = ChatMessage(id = "u1", role = "user", content = "hello"),
        )
        val runningTool = AssistantBlock(
            id = "t1",
            kind = "tool_use",
            content = "{\"name\":\"ls\"}",
            toolStatus = ToolBlockStatus.RUNNING,
        )
        val rows = listOf(
            user,
            FlatChatItem.AssistantHeader("m1"),
            FlatChatItem.AssistantThinking(
                messageId = "m1",
                block = AssistantBlock(id = "th1", kind = "thinking", content = "…"),
                isLast = false,
                messageIsStreaming = false,
            ),
            FlatChatItem.AssistantToolUse("m1", runningTool, listOf(runningTool)),
            FlatChatItem.AssistantInfo("m1", AssistantBlock(id = "i1", kind = "info", content = "note")),
            FlatChatItem.AssistantTyping("m1"),
            FlatChatItem.AssistantError("m1", "boom"),
        )

        rows.forEach { row ->
            assertTrue(
                "expected no markdown sources for ${row::class.java.simpleName}",
                markdownSourcesForRow(row).isEmpty(),
            )
        }
    }

    /**
     * The bug, pinned: the pre-aggregate extraction found nothing in an
     * aggregate row list, so `raws` was empty and the pass was a no-op.
     */
    @Test
    fun `old inline extraction found nothing in an aggregate row list`() {
        val rows: List<FlatChatItem> = listOf(
            aggregateRow("m1", textBlocks = listOf("first answer body")),
            aggregateRow("m2", textBlocks = listOf("second answer body")),
        )

        val oldStyle = rows.mapNotNull { (it as? FlatChatItem.AssistantMarkdownBlock)?.rawText }
        assertTrue("the legacy extraction must be empty — that WAS the bug", oldStyle.isEmpty())
        assertEquals(
            listOf("second answer body", "first answer body"),
            collectColdOpenPrewarmSources(rows.asReversed(), maxSources = 8, charBudget = 1_000),
        )
    }

    // ── newest-row selection ─────────────────────────────────────────────

    @Test
    fun `newest row sources come from the last row`() {
        val rows: List<FlatChatItem> = listOf(
            aggregateRow("m1", textBlocks = listOf("oldest")),
            FlatChatItem.AssistantTyping("m2"),
            aggregateRow("m3", textBlocks = listOf("newest one", "newest two")),
        )

        assertEquals(listOf("newest one", "newest two"), newestRowMarkdownSources(rows))
        assertTrue(newestRowMarkdownSources(emptyList()).isEmpty())
        // Bounded: the awaited warm never parses more than the budget.
        assertTrue(newestRowMarkdownSources(rows, charBudget = 0).isEmpty())
    }

    // ── collector ────────────────────────────────────────────────────────

    @Test
    fun `collector walks newest first and keeps fragment order inside a row`() {
        val rows: List<FlatChatItem> = listOf(
            aggregateRow("old", textBlocks = listOf("old-1", "old-2")),
            aggregateRow("new", textBlocks = listOf("new-1", "new-2")),
        )

        assertEquals(
            listOf("new-1", "new-2", "old-1", "old-2"),
            collectColdOpenPrewarmSources(rows.asReversed(), maxSources = 16, charBudget = 10_000),
        )
    }

    @Test
    fun `collector stops at the source cap`() {
        val rows: List<FlatChatItem> = listOf(
            aggregateRow("old", textBlocks = listOf("old-1", "old-2")),
            aggregateRow("new", textBlocks = listOf("new-1", "new-2")),
        )

        assertEquals(
            listOf("new-1", "new-2", "old-1"),
            collectColdOpenPrewarmSources(rows.asReversed(), maxSources = 3, charBudget = 10_000),
        )
    }

    @Test
    fun `collector stops at the character budget`() {
        val rows: List<FlatChatItem> = listOf(
            aggregateRow("old", textBlocks = listOf("0123456789")),
            aggregateRow("new", textBlocks = listOf("0123456789")),
        )

        // Budget is checked against the accumulated distinct characters, and
        // the first source always fits (a zero budget yields nothing).
        assertEquals(
            listOf("0123456789"),
            collectColdOpenPrewarmSources(rows.asReversed(), maxSources = 16, charBudget = 10),
        )
        assertTrue(
            collectColdOpenPrewarmSources(rows.asReversed(), maxSources = 16, charBudget = 0).isEmpty(),
        )
        assertTrue(
            collectColdOpenPrewarmSources(rows.asReversed(), maxSources = 0, charBudget = 1_000).isEmpty(),
        )
    }

    @Test
    fun `collector de duplicates identical fragments`() {
        // A duplicated body must not be prewarmed (or budgeted) twice.
        val rows: List<FlatChatItem> = listOf(
            aggregateRow("a", textBlocks = listOf("same body")),
            aggregateRow("b", textBlocks = listOf("same body")),
        )

        assertEquals(
            listOf("same body"),
            collectColdOpenPrewarmSources(rows.asReversed(), maxSources = 16, charBudget = 10_000),
        )
    }
}
