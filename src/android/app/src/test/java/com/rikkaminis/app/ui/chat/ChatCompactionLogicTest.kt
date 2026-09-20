package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.db.CompactMarkerEntity
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure compaction helpers extracted in FE-4 route A
 * ([resolveCompactAnchorIdx] / [resolveCompactStartIdx]).
 *
 * These mirror the exact branch semantics of the former inline logic in
 * ChatViewModel.compactAll (fcf9470).
 */
class ChatCompactionLogicTest {

    // ── helpers ────────────────────────────────────────────────

    private fun msg(
        role: LLMMessage.Role,
        id: String? = null,
        parts: List<AgentContentPart> = listOf(AgentContentPart.Text("x")),
    ) = LLMMessage(role = role, content = "x", contentParts = parts, dbMessageId = id)

    private fun toolResultUser(id: String? = null): LLMMessage =
        msg(LLMMessage.Role.USER, id, listOf(AgentContentPart.ToolResult("t", "n", "r")))

    private fun user(id: String? = null): LLMMessage = msg(LLMMessage.Role.USER, id)

    private fun assistant(id: String? = null): LLMMessage = msg(LLMMessage.Role.ASSISTANT, id)

    private fun marker(
        version: Int = 2,
        lastCompacted: String? = null,
        firstKept: String? = null,
        boundary: String? = null,
    ) = CompactMarkerEntity(
        id = "m",
        sessionId = "s",
        summary = "",
        firstKeptSortOrder = 0,
        compactedCount = 0,
        createdAt = 0L,
        boundaryMessageId = boundary,
        firstKeptMessageId = firstKept,
        lastCompactedMessageId = lastCompacted,
        version = version,
    )

    // ── resolveCompactAnchorIdx ────────────────────────────────

    @Test
    fun `empty history returns minus one`() {
        assertEquals(-1, resolveCompactAnchorIdx(emptyList(), null))
        assertEquals(-1, resolveCompactAnchorIdx(emptyList(), 0))
    }

    @Test
    fun `override walks back to closest persisted entry`() {
        val h = listOf(user("u1"), user(null), assistant("a1"))
        // override at index 2 (persisted) → stays 2
        assertEquals(2, resolveCompactAnchorIdx(h, 2))
        // override at index 1 (not persisted) → walks back to 0
        assertEquals(0, resolveCompactAnchorIdx(h, 1))
    }

    @Test
    fun `override clamps to history bounds`() {
        val h = listOf(user("u1"))
        assertEquals(0, resolveCompactAnchorIdx(h, 999))   // clamped to lastIndex 0
        assertEquals(0, resolveCompactAnchorIdx(h, -5))    // clamped to 0
    }

    @Test
    fun `tail walk back picks last persisted user not tool result`() {
        // Real shape: ... user prompt (persisted), tool result (user/tool-only),
        // assistant answer (persisted). keep-answer-active should anchor at the
        // last persisted USER prompt, skipping the tool-result and the answer.
        val h = listOf(
            user("u1"),
            toolResultUser("tr1"),
            assistant("a1"),
        )
        assertEquals(0, resolveCompactAnchorIdx(h, null))
    }

    @Test
    fun `tail walk back skips pure tool result user entries`() {
        val h = listOf(
            user("u1"),
            toolResultUser(null),   // unpersisted tool result
            assistant("a1"),
        )
        // state the walk back from tail: a1 is ASSISTANT → skip; tr1 (idx1) USER but
        // all ToolResult → skip; u1 (idx0) USER, not tool-only, persisted → anchor 0
        assertEquals(0, resolveCompactAnchorIdx(h, null))
    }

    @Test
    fun `tail walk back returns minus one when nothing persisted`() {
        val h = listOf(user(null), assistant(null))
        assertEquals(-1, resolveCompactAnchorIdx(h, null))
    }

    // ── [fix/compact-anchor-resolution] plain-text user prompts ─
    //
    // Every helper above builds a message WITH a Text part, which is exactly
    // why the empty-parts case went unnoticed: production user messages are
    // built as `LLMMessage(role = USER, content = <typed text>)` with
    // contentParts EMPTY (ChatSessionLifecycle send path). Kotlin's `all {}`
    // is vacuously true on an empty list, so the anchor walk-back classified
    // every typed prompt as a tool-result carrier and skipped it — collapsing
    // the anchor onto message 0 (or -1) and freezing every later compact in
    // the "already compacted" early return.

    @Test
    fun `isToolResultOnly is false for an empty part list`() {
        assertFalse(
            LLMMessage(role = LLMMessage.Role.USER, content = "hi").isToolResultOnly(),
        )
        assertTrue(toolResultUser("tr1").isToolResultOnly())
    }

    @Test
    fun `plain text prompt is anchored on instead of skipped`() {
        fun plain(id: String) =
            LLMMessage(role = LLMMessage.Role.USER, content = "a typed prompt", dbMessageId = id)
        val h = listOf(
            plain("u1"),
            assistant("a1"),
            plain("u2"),
            assistant("a2"),
        )
        // Before the fix u2 was skipped → anchor 0. Now the newest plain prompt
        // is the boundary and keep-instruction-active backs off one turn → 1.
        assertEquals(1, resolveCompactAnchorIdx(h, null))
    }

    @Test
    fun `all plain text history no longer aborts with minus one`() {
        val h = listOf(
            LLMMessage(role = LLMMessage.Role.USER, content = "prompt", dbMessageId = "u1"),
            assistant("a1"),
        )
        // Before the fix every candidate was skipped → -1 and compactAll
        // reported "no persisted messages yet" on a perfectly healthy session.
        assertEquals(0, resolveCompactAnchorIdx(h, null))
    }

    @Test
    fun `second compact with no new user turn folds nothing`() {
        // Minimal machine reproduction of the reported symptom: a long tool
        // loop under a single prompt. The anchor resolves to message 0, the
        // marker records it, and the next pass starts at anchor + 1 → empty
        // range → "already compacted", every turn, while the summary is still
        // re-injected. The write side still reports it this way — the fallback
        // lives in `compactAll` (budget anchor, see the
        // [compact-budget-anchor] section below) and leaves
        // resolveCompactStartIdx untouched.
        val h = mutableListOf(
            LLMMessage(role = LLMMessage.Role.USER, content = "prompt", dbMessageId = "u0"),
        )
        for (k in 1..4) {
            h += assistant("a$k")
            h += toolResultUser("r$k")
        }
        val anchor = resolveCompactAnchorIdx(h, null)
        assertEquals(0, anchor)
        val start = resolveCompactStartIdx(h, marker(version = 2, lastCompacted = h[anchor].dbMessageId))
        assertTrue("expected an empty range after the marker", start > anchor)
    }

    // ── [compact-budget-anchor] write-side budget anchor ───────
    //
    // A long agent run is ONE user turn, so the turn-boundary anchor is
    // always message 0 and the fold range stays empty forever. The fallback
    // keeps a token budget of tail verbatim and folds everything before it.

    /** [rounds] tool rounds under a single plain-text prompt. */
    private fun toolLoopSession(rounds: Int): MutableList<LLMMessage> {
        val h = mutableListOf(
            LLMMessage(role = LLMMessage.Role.USER, content = "prompt", dbMessageId = "u0"),
        )
        for (k in 1..rounds) {
            h += assistant("a$k")
            h += toolResultUser("r$k")
        }
        return h
    }

    private val flatEstimate: (LLMMessage) -> Long = { 1_000L }

    @Test
    fun `budget anchor lands near the tail of a single prompt tool loop`() {
        val h = toolLoopSession(rounds = 60)
        val start = resolveCompactStartIdx(h, marker(version = 2, lastCompacted = "u0"))
        // The stuck state this fallback exists for: nothing new to fold.
        assertTrue("expected the turn anchor to be exhausted", start > resolveCompactAnchorIdx(h, null))
        val anchor = resolveBudgetAnchorIdx(h, startIdx = start, keepTailTokens = 20_000L, estimate = flatEstimate)
        // 20 messages × 1000 tokens kept verbatim, rest folded.
        assertEquals(h.size - 21, anchor)
        assertFalse(h[anchor + 1].isToolResultOnly())
    }

    @Test
    fun `budget anchor never leaves an orphan tool result in the kept region`() {
        val h = mutableListOf<LLMMessage>(user("u0"))
        for (k in 1..5) {
            h += assistant("a$k")
            h += toolResultUser("r$k")
        }
        // Budget keeps indices 8..10 (r4, a5, r5); the raw candidate would be
        // index 7 (a4), leaving r4 without its tool_use → step back to r3.
        val anchor = resolveBudgetAnchorIdx(h, startIdx = 0, keepTailTokens = 3_000L, estimate = flatEstimate)
        assertEquals(6, anchor)
        assertFalse(h[anchor + 1].isToolResultOnly())
    }

    @Test
    fun `budget anchor skips entries without a persisted id`() {
        val h = mutableListOf<LLMMessage>(user(null), assistant(null))
        // Nothing persisted to anchor on → no foldable range.
        assertEquals(-1, resolveBudgetAnchorIdx(h, startIdx = 0, keepTailTokens = 500L, estimate = flatEstimate))
    }

    @Test
    fun `budget anchor reports nothing to fold when the tail already fits`() {
        val h = toolLoopSession(rounds = 3)
        assertEquals(-1, resolveBudgetAnchorIdx(h, startIdx = 0, keepTailTokens = 1_000_000L, estimate = flatEstimate))
    }

    // ── [compact-budget-anchor] keep/trigger threshold ordering ─────
    //
    // The budget anchor's "nothing to fold" test and ContextCompactor.decide's
    // "the tail grew enough" test are two gates over the SAME region, so their
    // thresholds must be ordered. When keepTail >= minTail a dead zone opens
    // where the loop asks for a compact every turn and the anchor refuses every
    // time (2026-09-20: 61 consecutive no-op compacts, tail in [10510, 17275]
    // against a hard-coded 20_000 keep budget).

    @Test
    fun `kept tail budget stays strictly below the trigger threshold`() {
        // The invariant that closes the dead zone, checked across the whole
        // user-tunable range (AgentRuntimeLimitsPrefs.COMPACT_TAIL_TOKENS_*).
        for (minTail in 1L..40_000L step 500L) {
            val keep = compactBudgetTailKeepTokens(minTail)
            assertTrue(
                "keep=$keep must stay below minTail=$minTail (dead zone otherwise)",
                keep < minTail,
            )
        }
    }

    @Test
    fun `kept tail budget is monotonic and proportional`() {
        // Monotonic: a larger trigger threshold must never shrink the budget.
        assertTrue(compactBudgetTailKeepTokens(20_000L) > compactBudgetTailKeepTokens(8_000L))
        // Proportional: 0.8 × the threshold, so the retained tail leaves ~20%
        // of headroom before the next compact is admitted.
        assertEquals(6_400L, compactBudgetTailKeepTokens(8_000L))
        assertEquals(16_000L, compactBudgetTailKeepTokens(20_000L))
        assertEquals(25_600L, compactBudgetTailKeepTokens(32_000L))
    }

    @Test
    fun `kept tail budget degrades safely for degenerate thresholds`() {
        // Non-positive threshold: no verbatim tail is reserved.
        assertEquals(0L, compactBudgetTailKeepTokens(0L))
        assertEquals(0L, compactBudgetTailKeepTokens(-5L))
        // A threshold of 1 floors to 0 — still strictly below, so the
        // invariant survives the degenerate end of the range.
        assertEquals(0L, compactBudgetTailKeepTokens(1L))
    }

    @Test
    fun `dead zone case folds again once the kept budget tracks the threshold`() {
        // Reproduction of the production shape: one prompt, 9 tool rounds,
        // 18k tokens of active tail. Under the old hard-coded 20_000 keep
        // budget the anchor refused (-1) while the trigger gate admitted the
        // compact -> the 2026-09-20 no-op compact loop.
        val h = toolLoopSession(rounds = 9)
        val start = resolveCompactStartIdx(h, marker(version = 2, lastCompacted = "u0"))
        val tail = (start until h.size).sumOf { flatEstimate(h[it]) }
        val minTail = 8_000L
        val keep = compactBudgetTailKeepTokens(minTail)

        assertEquals(18_000L, tail)
        assertTrue("tail=$tail must be below the old hard-coded budget", tail < 20_000L)
        assertTrue("tail=$tail must be above the trigger threshold", tail >= minTail)

        val oldKeep = resolveBudgetAnchorIdx(h, startIdx = start, keepTailTokens = 20_000L, estimate = flatEstimate)
        assertEquals("old hard-coded budget reproduces the dead zone", -1, oldKeep)

        val newKeep = resolveBudgetAnchorIdx(h, startIdx = start, keepTailTokens = keep, estimate = flatEstimate)
        assertTrue("derived budget folds the same history", newKeep >= start)
    }

    // ── [compact-budget-anchor] read-side pre-anchor clamp ─────

    @Test
    fun `pre anchor slice is clamped to the token budget`() {
        val h = toolLoopSession(rounds = 60)
        val start = clampSliceStartByBudget(h, startIdx = 0, anchorIdx = h.size - 1, maxTokens = 6_000L, estimate = flatEstimate)
        // Six messages fit; the slice must not open on a lone tool result.
        assertEquals(h.size - 6, start)
        assertFalse(h[start].isToolResultOnly())
    }

    @Test
    fun `pre anchor slice is left alone when it already fits the budget`() {
        val h = toolLoopSession(rounds = 2)
        assertEquals(0, clampSliceStartByBudget(h, startIdx = 0, anchorIdx = 1, maxTokens = 12_000L, estimate = flatEstimate))
    }

    // ── resolveCompactStartIdx ─────────────────────────────────

    @Test
    fun `null marker starts at zero`() {
        assertEquals(0, resolveCompactStartIdx(listOf(user("u1")), null))
    }

    @Test
    fun `v2 marker starts after prev anchor`() {
        val h = listOf(user("u1"), assistant("a1"), user("u2"))
        val m = marker(version = 2, lastCompacted = "a1")
        // a1 at index 1 → start = 1 + 1 = 2
        assertEquals(2, resolveCompactStartIdx(h, m))
    }

    @Test
    fun `v1 marker starts at prev anchor inclusive`() {
        val h = listOf(user("u1"), assistant("a1"), user("u2"))
        val m = marker(version = 1, firstKept = "a1")
        // a1 at index 1 → start = 1
        assertEquals(1, resolveCompactStartIdx(h, m))
    }

    @Test
    fun `v1 marker falls back to boundary message id`() {
        val h = listOf(user("u1"), assistant("a1"), user("u2"))
        val m = marker(version = 1, firstKept = null, boundary = "u2")
        assertEquals(2, resolveCompactStartIdx(h, m))
    }

    @Test
    fun `prev anchor not in history restarts from top`() {
        val h = listOf(user("u1"), assistant("a1"))
        val m = marker(version = 2, lastCompacted = "ghost")
        assertEquals(0, resolveCompactStartIdx(h, m))
    }

    // ── buildConversationTextForSummary ────────────────────────

    @Test
    fun `buildConversationTextForSummary renders role and content lines`() {
        val h = listOf(
            LLMMessage(role = LLMMessage.Role.USER, content = "hello", dbMessageId = "u1"),
            LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "world", dbMessageId = "a1"),
        )
        val out = buildConversationTextForSummary(h)
        assertTrue(out.contains("user: hello"))
        assertTrue(out.contains("assistant: world"))
    }

    @Test
    fun `buildConversationTextForSummary truncates content to 500`() {
        val long = "x".repeat(1000)
        val h = listOf(LLMMessage(role = LLMMessage.Role.USER, content = long))
        val out = buildConversationTextForSummary(h)
        assertTrue(out.contains("x".repeat(500)))
        assertTrue(!out.contains("x".repeat(501)))
    }

    @Test
    fun `buildConversationTextForSummary renders tool result part`() {
        val h = listOf(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(AgentContentPart.ToolResult("t", "toolname", "the output")),
            ),
        )
        val out = buildConversationTextForSummary(h)
        assertTrue(out.contains("[result:toolname]: the output"))
    }

    // ── isContextTooLargeError ─────────────────────────────────

    @Test
    fun `isContextTooLargeError matches known too-large substrings`() {
        assertTrue(isContextTooLargeError(IllegalStateException("context length exceeded")))
        assertTrue(isContextTooLargeError(IllegalStateException("request too large")))
        assertTrue(isContextTooLargeError(IllegalStateException("message too many tokens")))
        assertTrue(isContextTooLargeError(IllegalStateException("exceeds the model's context window")))
    }

    @Test
    fun `isContextTooLargeError is case insensitive and rejects unrelated`() {
        assertTrue(isContextTooLargeError(IllegalStateException("CONTEXT WINDOW limit")))
        assertFalse(isContextTooLargeError(IllegalStateException("network timeout")))
        assertFalse(isContextTooLargeError(IllegalStateException("rate limited")))
    }

    // ── walkBackUserTurnsBounded ───────────────────────────────

    @Test
    fun `walkBack invalid anchor returns invalidAnchor`() {
        assertEquals("invalidAnchor", walkBackUserTurnsBounded(emptyList(), 0, 2, 100).stopReason)
        assertEquals("invalidAnchor", walkBackUserTurnsBounded(listOf(user("u1")), 5, 2, 100).stopReason)
    }

    @Test
    fun `walkBack collects user text turns until target met`() {
        val h = listOf(
            user("u1"), assistant("a1"),
            user("u2"), assistant("a2"),
            user("u3"),
        )
        val r = walkBackUserTurnsBounded(h, anchorIdx = 4, maxUserTextTurns = 2, maxMessages = 100)
        assertEquals("userTextTargetMet", r.stopReason)
        assertEquals(2, r.userTextTurnsFound)
        assertEquals(2, r.priorIdx)   // u2 at index 2 is the 2nd user turn
    }

    @Test
    fun `walkBack stops at message cap without splitting a round`() {
        // 5 messages, cap=3 → walking back from index 4 would take (4-0+1)=5 > 3,
        // so it must stop before including the oldest round.
        val h = listOf(
            user("u1"), assistant("a1"),   // round 1
            user("u2"), assistant("a2"),   // round 2
            user("u3"),                    // round 3
        )
        val r = walkBackUserTurnsBounded(h, anchorIdx = 4, maxUserTextTurns = 10, maxMessages = 3)
        assertEquals("messageCapWouldExceed", r.stopReason)
        // accepted: u2 at idx2 and u3 at idx4 are within cap (3 messages = idx2..4)
        assertEquals(2, r.userTextTurnsFound)
        assertEquals(2, r.priorIdx)
    }

    @Test
    fun `walkBack reaches start when history is short`() {
        val h = listOf(user("u1"), assistant("a1"))
        val r = walkBackUserTurnsBounded(h, anchorIdx = 1, maxUserTextTurns = 10, maxMessages = 100)
        assertEquals("reachedStart", r.stopReason)
        assertEquals(1, r.userTextTurnsFound)   // only u1 has text
    }

    // ── resolveCompactAnchorIdx: instruction-keep walk-back ────────────

    @Test
    fun `anchor walk back kepps an in-flight instruction behind a role bridge`() {
        // [u1, (bridge: not persisted), u2 = CURRENT in-flight] — the bridge
        // stops the walk and the anchor lands on u1, so u2 stays on the
        // active side.
        val h = listOf(user("u1"), assistant(null), user("u2"))
        assertEquals(0, resolveCompactAnchorIdx(h, null))
    }

    @Test
    fun `anchor walk back aborts when only persisted user prompts precede the tail`() {
        // [audit-0916] Role bridges never persist, so a reload can collapse
        // history to consecutive persisted user prompts. The walk reaches the
        // start: nothing settled to anchor on, and the tail may be the CURRENT
        // instruction — abort instead of swallowing it.
        assertEquals(-1, resolveCompactAnchorIdx(listOf(user("u1"), user("u2")), null))
        assertEquals(-1, resolveCompactAnchorIdx(listOf(user("u1"), user("u2"), user("u3")), null))
    }

    @Test
    fun `anchor walk back kepps the whole last settled turn active`() {
        // [u1, a1, u2, a2] — the anchor lands on a1: the last whole turn
        // (instruction + answer) stays outside the compacted range.
        val h = listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2"))
        assertEquals(1, resolveCompactAnchorIdx(h, null))
    }

    @Test
    fun `anchor walk back skips a non-persisted tail and anchors on the privious answer`() {
        // [u1, a1, u2 = current, assistant streaming (no db id)]
        val h = listOf(user("u1"), assistant("a1"), user("u2"), assistant(null))
        assertEquals(1, resolveCompactAnchorIdx(h, null))
    }

    @Test
    fun `anchor walk back stops at a tool-result turn mid-run`() {
        // [u1, a1, u2 = current, tool results] — the tool-result user entry
        // breaks the walk, so the anchor stays on a1.
        val h = listOf(user("u1"), assistant("a1"), user("u2"), toolResultUser("tr1"))
        assertEquals(1, resolveCompactAnchorIdx(h, null))
    }

    @Test
    fun `anchor walk back aborts when only synthetic rows precede the current prompt`() {
        assertEquals(-1, resolveCompactAnchorIdx(listOf(assistant(null), user("u2")), null))
    }
}
