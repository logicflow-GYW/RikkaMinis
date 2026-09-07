package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [render-churn-2] Frozen-completion contract tests for the chat render
 * pipeline. The 09-07 incident: a 1Hz tool-progress ticker rewrote a live
 * block's content every second, and the row pipeline re-created EVERY row
 * instance per tick (unconditional ledger replacement + data-class field
 * equals walking multi-KB content strings) → whole-message recompose +
 * re-layout at 1Hz → Skia native-heap climb to LMK kill.
 *
 * These tests pin the three invariants that fix it:
 *  1. sameBlockRefs: a re-created list of UNCHANGED block instances is
 *     "the same view" — no content walk.
 *  2. AssistantToolRunGroup / AssistantToolUse / AssistantMarkdownBlock
 *     equals: reference-based for block payloads, so completed blocks
 *     stay frozen; live (copy()'d) blocks still register as changed.
 *  3. StableChatRowLedger: identical-content reconciles keep the published
 *     row INSTANCES (LazyColumn key+equals skip then freezes them); only
 *     genuinely changed live state replaces rows.
 *
 * Pure JVM — no Android dependencies in these classes.
 */
class RenderChurnFreezeTest {

    private fun toolBlock(id: String, content: String, status: ToolBlockStatus): AssistantBlock =
        AssistantBlock(
            id = id, kind = "tool_use", content = content, toolStatus = status,
            toolTitle = id, toolName = "shell_execute",
        )

    private fun textBlock(id: String, content: String): AssistantBlock =
        AssistantBlock(id = id, kind = "text", content = content)

    // ───────────────────────── sameBlockRefs ─────────────────────────

    @Test
    fun sameBlockRefsSameListIsTrue() {
        val blocks = listOf(toolBlock("t1", "a", ToolBlockStatus.SUCCESS))
        assertTrue(sameBlockRefs(blocks, blocks))
    }

    @Test
    fun sameBlockRefsRebuiltListWithSameInstancesIsTrue() {
        val b1 = toolBlock("t1", "a", ToolBlockStatus.SUCCESS)
        val b2 = toolBlock("t2", "b", ToolBlockStatus.SUCCESS)
        val rebuilt = listOf(b1, b2)
        assertTrue(sameBlockRefs(listOf(b1, b2), rebuilt))
    }

    @Test
    fun sameBlockRefsCopiedLiveBlockIsFalse() {
        val b1 = toolBlock("t1", "a", ToolBlockStatus.RUNNING)
        val b2 = toolBlock("t2", "b", ToolBlockStatus.SUCCESS)
        // The ticker's per-second copy() of the LIVE block:
        val b1Copied = b1.copy(content = "⏳ Waiting 59s before executing...")
        assertFalse(sameBlockRefs(listOf(b1, b2), listOf(b1Copied, b2)))
    }

    @Test
    fun sameBlockRefsDifferentSizeIsFalse() {
        val b1 = toolBlock("t1", "a", ToolBlockStatus.SUCCESS)
        assertFalse(sameBlockRefs(listOf(b1), listOf(b1, b1)))
    }

    // ─────────────────── AssistantToolRunGroup.equals ───────────────────

    @Test
    fun toolRunGroupEqualsIgnoresListRebuildWithSameInstances() {
        val t1 = toolBlock("t1", "big result ".repeat(2000), ToolBlockStatus.SUCCESS)
        val t2 = toolBlock("t2", "done", ToolBlockStatus.SUCCESS)
        val a = FlatChatItem.AssistantToolRunGroup(
            messageId = "m1", tools = listOf(t1, t2),
            isRunning = false, isLastCancelled = false,
        )
        // Per-publish rebuild: same elements, new list instance.
        val b = FlatChatItem.AssistantToolRunGroup(
            messageId = "m1", tools = listOf(t1, t2),
            isRunning = false, isLastCancelled = false,
        )
        assertTrue(a == b)
        assertTrue(a.hashCode() == b.hashCode())
    }

    @Test
    fun toolRunGroupEqualsFalseWhenLiveBlockCopied() {
        val t1 = toolBlock("t1", "old", ToolBlockStatus.RUNNING)
        val t2 = toolBlock("t2", "done", ToolBlockStatus.SUCCESS)
        val a = FlatChatItem.AssistantToolRunGroup(
            messageId = "m1", tools = listOf(t1, t2),
            isRunning = true, isLastCancelled = false,
        )
        val t1Tick = t1.copy(content = "new tick")
        val b = FlatChatItem.AssistantToolRunGroup(
            messageId = "m1", tools = listOf(t1Tick, t2),
            isRunning = true, isLastCancelled = false,
        )
        assertFalse(a == b)
    }

    @Test
    fun toolRunGroupEqualsFalseWhenIsRunningFlips() {
        val t1 = toolBlock("t1", "done", ToolBlockStatus.SUCCESS)
        val a = FlatChatItem.AssistantToolRunGroup(
            messageId = "m1", tools = listOf(t1),
            isRunning = true, isLastCancelled = false,
        )
        val b = FlatChatItem.AssistantToolRunGroup(
            messageId = "m1", tools = listOf(t1),
            isRunning = false, isLastCancelled = false,
        )
        assertFalse(a == b)
    }

    // ───────────────────── AssistantToolUse.equals ─────────────────────

    @Test
    fun toolUseEqualsIgnoresListRebuildWithSameInstances() {
        val t1 = toolBlock("t1", "result", ToolBlockStatus.SUCCESS)
        val a = FlatChatItem.AssistantToolUse("m1", t1, listOf(t1))
        val b = FlatChatItem.AssistantToolUse("m1", t1, listOf(t1))
        assertTrue(a == b)
        assertTrue(a.hashCode() == b.hashCode())
    }

    @Test
    fun toolUseEqualsFalseWhenBlockCopied() {
        val t1 = toolBlock("t1", "old", ToolBlockStatus.RUNNING)
        val a = FlatChatItem.AssistantToolUse("m1", t1, listOf(t1))
        val b = FlatChatItem.AssistantToolUse("m1", t1.copy(content = "new"), listOf(t1))
        assertFalse(a == b)
    }

    // ────────────────── AssistantMarkdownBlock.equals ──────────────────

    @Test
    fun mdblockEqualsIgnoresMessageMarkdownGrowth() {
        val a = FlatChatItem.AssistantMarkdownBlock(
            messageId = "m1", parentBlockId = "x1", rawText = "fragment",
            blockIndex = 0, isLastBlockOfMessage = true, messageIsStreaming = true,
            messageMarkdown = "fragment",
        )
        // The message's joined markdown grows with EVERY streamed chunk —
        // this must NOT invalidate frozen prefix fragments.
        val b = FlatChatItem.AssistantMarkdownBlock(
            messageId = "m1", parentBlockId = "x1", rawText = "fragment",
            blockIndex = 0, isLastBlockOfMessage = true, messageIsStreaming = true,
            messageMarkdown = "fragment plus a much longer tail that keeps growing and growing",
        )
        assertTrue(a == b)
        assertTrue(a.hashCode() == b.hashCode())
    }

    @Test
    fun mdblockEqualsFalseOnRawTextLengthChange() {
        val a = FlatChatItem.AssistantMarkdownBlock(
            messageId = "m1", parentBlockId = "x1", rawText = "fragment",
            blockIndex = 0, isLastBlockOfMessage = true, messageIsStreaming = true,
            messageMarkdown = "fragment",
        )
        val b = FlatChatItem.AssistantMarkdownBlock(
            messageId = "m1", parentBlockId = "x1", rawText = "fragment!!",
            blockIndex = 0, isLastBlockOfMessage = true, messageIsStreaming = true,
            messageMarkdown = "fragment",
        )
        assertFalse(a == b)
    }

    // ───────────────────── ledger freeze behaviour ─────────────────────

    private fun assistantMessage(
        id: String,
        blocks: List<AssistantBlock>,
        isStreaming: Boolean = false,
    ): ChatMessage = ChatMessage(
        id = id, role = "assistant", content = "",
        isStreaming = isStreaming, toolBlocks = blocks,
    )

    @Test
    fun ledgerKeepsRowInstancesWhenContentUnchanged() {
        val ledger = StableChatRowLedger()
        val msg = assistantMessage("m1", listOf(
            toolBlock("t1", "final result", ToolBlockStatus.SUCCESS),
            textBlock("x1", "the answer"),
        ))
        ledger.seed(buildFlatChatItems(listOf(msg)), 1)
        // Reconcile #1: first segmenter attach — mdblock rows are rebuilt by design.
        ledger.reconcile(listOf(msg))
        val afterFirst = ledger.snapshot()
        // Reconcile #2 with identical content: EVERY row instance must be
        // preserved (the LazyColumn key+equals skip then freezes them).
        ledger.reconcile(listOf(msg))
        val afterSecond = ledger.snapshot()
        assertTrue(afterFirst.size == afterSecond.size)
        for (i in afterFirst.indices) {
            assertSame("row[$i] must keep its instance on identical reconcile", afterFirst[i], afterSecond[i])
        }
    }

    @Test
    fun ledgerUpdatesToolRunWhenLiveBlockContentChanges() {
        val ledger = StableChatRowLedger()
        val live = toolBlock("t1", "waiting", ToolBlockStatus.RUNNING)
        val msg = assistantMessage("m1", listOf(
            live,
            textBlock("x1", "answer"),
        ), isStreaming = true)
        ledger.seed(buildFlatChatItems(listOf(msg)), 1)
        ledger.reconcile(listOf(msg))
        val before = ledger.snapshot()
        val toolRunBefore = before.first { it is FlatChatItem.AssistantToolRunGroup }

        // Ticker tick: live block content changes → the run group MUST update.
        val ticked = live.copy(content = "⏳ Waiting 59s before executing...")
        val msgTicked = assistantMessage("m1", listOf(
            ticked,
            textBlock("x1", "answer"),
        ), isStreaming = true)
        ledger.reconcile(listOf(msgTicked))
        val after = ledger.snapshot()
        val toolRunAfter = after.first { it is FlatChatItem.AssistantToolRunGroup }
        assertNotSame("run group must update when the live block ticks", toolRunBefore, toolRunAfter)
    }

    @Test
    fun ledgerFrozenTextRowsSurviveToolChurn() {
        val ledger = StableChatRowLedger()
        val live = toolBlock("t1", "waiting", ToolBlockStatus.RUNNING)
        val msg = assistantMessage("m1", listOf(
            live,
            textBlock("x1", "completed fragment that must never re-layout"),
        ), isStreaming = true)
        ledger.seed(buildFlatChatItems(listOf(msg)), 1)
        ledger.reconcile(listOf(msg))
        val before = ledger.snapshot()
        val textBefore = before.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()

        // Several ticker ticks: live tool content changes every second, the
        // text block does not → its mdblock row instances must stay frozen.
        var current = live
        repeat(3) { i ->
            current = current.copy(content = "⏳ Waiting ${59 - i}s before executing...")
            val ticked = assistantMessage("m1", listOf(
                current,
                textBlock("x1", "completed fragment that must never re-layout"),
            ), isStreaming = true)
            ledger.reconcile(listOf(ticked))
        }
        val after = ledger.snapshot()
        val textAfter = after.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
        assertTrue(textBefore.isNotEmpty())
        assertTrue(textBefore.size == textAfter.size)
        for (i in textBefore.indices) {
            assertSame("completed text row must stay frozen during tool churn", textBefore[i], textAfter[i])
        }
    }
}
