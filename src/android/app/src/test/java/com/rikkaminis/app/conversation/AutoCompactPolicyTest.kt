package com.rikkaminis.app.conversation

import com.rikkaminis.app.data.ContextPolicy
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T5-auto-compact] JVM tests for [ContextCompactor] — the automatic-compaction
 * decision engine (OmniBot AgentConversationContextCompactor parity).
 *
 * The decision must be conservative by construction:
 *  - only fires when ContextPolicy says NEEDS_COMPACT (at/over the compact line,
 *    still below the hard ceiling);
 *  - never re-fires too soon (RECENT_AUTO_COMPACT debounce);
 *  - never fires when there's nothing meaningful to compact (TAIL_TOO_SMALL);
 *  - never stacks on top of an in-flight compact (COMPACT_IN_FLIGHT);
 *  - never fires at/over the hard ceiling (EXHAUSTED — manual /compact or a new
 *    chat is the user's move there).
 */
class AutoCompactPolicyTest {

    private val window = 128_000
    private val policy = ContextPolicy.forContextWindow(window) // compact line = 108K

    private fun decide(
        tokens: Int,
        tail: Long = 40_000,
        isCompacting: Boolean = false,
        lastAuto: Long = Long.MIN_VALUE,
        now: Long = 1_000_000L,
    ) = ContextCompactor.decide(
        estimatedTokens = tokens,
        contextWindow = window,
        policy = policy,
        tailTokens = tail,
        isCompacting = isCompacting,
        lastAutoCompactAtMs = lastAuto,
        nowMs = now,
    )

    // ── trigger conditions ────────────────────────────────────────────────

    @Test
    fun `OK below the compact line`() {
        assertEquals(ContextCompactor.Decision.OK, decide(tokens = 100_000))
    }

    @Test
    fun `AUTO_COMPACT at the compact line with healthy tail`() {
        assertEquals(ContextCompactor.Decision.AUTO_COMPACT, decide(tokens = 120_000))
    }

    @Test
    fun `AUTO_COMPACT fires for the 64K tier too`() {
        val w = 77_000
        val p = ContextPolicy.forContextWindow(w) // compact line = 67K
        val d = ContextCompactor.decide(
            estimatedTokens = 70_000, contextWindow = w, policy = p,
            tailTokens = 20_000, isCompacting = false,
        )
        assertEquals(ContextCompactor.Decision.AUTO_COMPACT, d)
    }

    // ── debounce & yield guards ───────────────────────────────────────────

    @Test
    fun `RECENT_AUTO_COMPACT when less than the min interval has elapsed`() {
        val lastAuto = 500_000L
        val now = lastAuto + ContextCompactor.DEFAULT_AUTO_COMPACT_MIN_INTERVAL_MS - 1
        assertEquals(ContextCompactor.Decision.RECENT_AUTO_COMPACT, decide(tokens = 120_000, lastAuto = lastAuto, now = now))
    }

    @Test
    fun `AUTO_COMPACT again once the min interval has elapsed`() {
        val lastAuto = 500_000L
        val now = lastAuto + ContextCompactor.DEFAULT_AUTO_COMPACT_MIN_INTERVAL_MS
        assertEquals(ContextCompactor.Decision.AUTO_COMPACT, decide(tokens = 120_000, lastAuto = lastAuto, now = now))
    }

    @Test
    fun `TAIL_TOO_SMALL when the tail is below the minimum`() {
        // Just auto-compacted: tail is tiny → don't re-compact.
        assertEquals(ContextCompactor.Decision.TAIL_TOO_SMALL, decide(tokens = 120_000, tail = 1_000))
    }

    @Test
    fun `COMPACT_IN_FLIGHT when a compact is already running`() {
        assertEquals(ContextCompactor.Decision.COMPACT_IN_FLIGHT, decide(tokens = 120_000, isCompacting = true))
    }

    @Test
    fun `EXHAUSTED at the hard ceiling - never auto compact past the window`() {
        assertEquals(ContextCompactor.Decision.EXHAUSTED, decide(tokens = window))
    }

    @Test
    fun `OK when no token estimate or window is unknown`() {
        assertEquals(ContextCompactor.Decision.OK, decide(tokens = 0))
        val d = ContextCompactor.decide(
            estimatedTokens = 50_000, contextWindow = 0, policy = policy,
            tailTokens = 40_000, isCompacting = false,
        )
        assertEquals(ContextCompactor.Decision.OK, d)
    }

    // ── token estimation ──────────────────────────────────────────────────

    @Test
    fun `estimateTokens is chars divided by 4`() {
        assertEquals(0L, ContextCompactor.estimateTokens("abc")) // 3/4 = 0
        assertEquals(1L, ContextCompactor.estimateTokens("abcd"))
        assertEquals(10L, ContextCompactor.estimateTokens("a".repeat(40)))
    }

    @Test
    fun `estimateMessageTokens counts content plus content parts`() {
        val msg = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "a".repeat(40), // 10 tokens
            contentParts = listOf(
                AgentContentPart.Text("b".repeat(80)),        // 20 tokens
                AgentContentPart.ToolResult("t1", "tool", "c".repeat(160)), // content 40 tokens
                AgentContentPart.ToolUse("t2", "tool", JSONObject()), // {} → 0 tokens
            ),
        )
        // 10 (content) + 20 (Text) + 40 (ToolResult.content) + 0 (empty ToolUse input)
        assertEquals(70, ContextCompactor.estimateMessageTokens(msg).toInt())
    }

    @Test
    fun `estimateTailTokens without anchor covers the whole history`() {
        val msgs = listOf(
            LLMMessage(LLMMessage.Role.USER, "a".repeat(40)),
            LLMMessage(LLMMessage.Role.ASSISTANT, "b".repeat(80)),
        )
        assertEquals(10L + 20L, ContextCompactor.estimateTailTokens(msgs, null))
    }

    @Test
    fun `estimateTailTokens counts only messages after the anchor`() {
        val anchorId = "db-1"
        val msgs = listOf(
            LLMMessage(LLMMessage.Role.USER, "a".repeat(40), dbMessageId = "db-0"), // 10
            LLMMessage(LLMMessage.Role.USER, "b".repeat(40), dbMessageId = anchorId),   // 10 anchor
            LLMMessage(LLMMessage.Role.ASSISTANT, "c".repeat(80), dbMessageId = "db-2"), // 20 tail
        )
        assertEquals(20L, ContextCompactor.estimateTailTokens(msgs, anchorId))
    }

    @Test
    fun `estimateTailTokens falls back to full history when anchor is missing`() {
        val msgs = listOf(
            LLMMessage(LLMMessage.Role.USER, "a".repeat(40), dbMessageId = "db-0"),
            LLMMessage(LLMMessage.Role.ASSISTANT, "b".repeat(80), dbMessageId = "db-2"),
        )
        // anchor id not present in history → full history (mirrors effectiveAgentHistory fallback)
        assertEquals(30L, ContextCompactor.estimateTailTokens(msgs, "ghost-anchor"))
    }

    // ── MUST PRESERVE wording (T5 acceptance 4) ───────────────────────────

    @Test
    fun `compact prompt pins verbatim preservation of paths URLs and UUIDs`() {
        val prompt = ContextCompactor.COMPACT_SUMMARY_SYSTEM_PROMPT.lowercase()
        assertTrue("must contain MUST PRESERVE section", prompt.contains("must preserve"))
        assertTrue("must demand verbatim copy of paths/URLs/UUIDs", prompt.contains("copy verbatim"))
        assertTrue("must cover file paths", prompt.contains("file paths"))
        assertTrue("must cover URLs", prompt.contains("urls"))
        assertTrue("must cover UUIDs", prompt.contains("uuids"))
        assertTrue("must forbid altering code snippets", prompt.contains("do not translate or alter code"))
    }
}