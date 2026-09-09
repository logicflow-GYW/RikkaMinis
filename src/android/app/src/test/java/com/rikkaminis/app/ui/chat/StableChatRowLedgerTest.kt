package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [StableChatRowLedger] — the session-lifetime stable row list.
 *
 * Contract under test: once a row key is published it is never deleted or
 * reordered while its message is the active turn; growth is prefix-append at
 * the tail only.
 */
class StableChatRowLedgerTest {

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun userMessage(id: String, content: String = "hello", isQueued: Boolean = false) =
        ChatMessage(id = id, role = "user", content = content, isQueued = isQueued)

    private fun assistantMessage(
        id: String,
        content: String = "",
        blocks: List<AssistantBlock> = emptyList(),
        isStreaming: Boolean = false,
        isAwaitingModelResponse: Boolean = false,
        error: String? = null,
    ) = ChatMessage(
        id = id,
        role = "assistant",
        content = content,
        isStreaming = isStreaming,
        isAwaitingModelResponse = isAwaitingModelResponse,
        toolBlocks = blocks,
        error = error,
    )

    private fun textBlock(id: String, content: String) =
        AssistantBlock(id = id, kind = "text", content = content)

    private fun thinkingBlock(id: String, content: String, status: ToolBlockStatus? = null) =
        AssistantBlock(id = id, kind = "thinking", content = content, toolStatus = status)

    private fun toolBlock(id: String, status: ToolBlockStatus, title: String = "tool") =
        AssistantBlock(id = id, kind = "tool_use", toolStatus = status, toolTitle = title, toolName = "run")

    private fun keysOf(rows: List<FlatChatItem>): List<String> = rows.map { it.key }

    // ── cold open ───────────────────────────────────────────────────────────

    @Test fun `seed then reconcile keeps historical rows byte-identical`() {
        val history = listOf(userMessage("u1"), assistantMessage("a1", content = "Old answer"))
        val seedRows = buildFlatChatItems(history)
        val ledger = StableChatRowLedger()
        ledger.seed(seedRows, history.size)
        val snapshot = ledger.reconcile(history)
        assertEquals(keysOf(seedRows), keysOf(snapshot))
        assertEquals(
            seedRows.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>().map { it.rawText },
            snapshot.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>().map { it.rawText },
        )
    }

    @Test fun `reconcile without seed falls back to full canonical build`() {
        val messages = listOf(userMessage("u1"), assistantMessage("a1", content = "Answer"))
        val ledger = StableChatRowLedger()
        val rows = ledger.reconcile(messages)
        assertTrue(rows.isNotEmpty())
        assertEquals(listOf("user:u1", "header:a1", "legacy:a1"), keysOf(rows))
    }

    // ── thinking → tool → result → text flow ────────────────────────────────

    @Test fun `thinking then tool then text appends rows without reordering`() {
        val msgId = "a1"
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)

        // Turn starts: thinking streams (no status yet).
        val t1 = listOf(userMessage("u1"), assistantMessage(msgId, blocks = listOf(thinkingBlock("th1", "Reasoning")), isStreaming = true))
        val k1 = keysOf(ledger.reconcile(t1))
        // header + one independent thinking row (thinking no longer folded
        // into a tool run group — no tool yet).
        assertEquals(listOf("user:u1", "header:a1", "thinking:a1:th1"), k1)

        // Tool appears → the tool run group row appears alongside thinking.
        val t2 = listOf(userMessage("u1"), assistantMessage(msgId,
            blocks = listOf(thinkingBlock("th1", "Reasoning", ToolBlockStatus.SUCCESS), toolBlock("tool_1", ToolBlockStatus.RUNNING)),
            isStreaming = true))
        val k2 = keysOf(ledger.reconcile(t2))
        // thinking row + new toolrun row; no key churn on the prefix.
        assertEquals(listOf("user:u1", "header:a1", "thinking:a1:th1", "toolrun:a1"), k2)

        // Tool finished + text starts.
        val t3 = listOf(userMessage("u1"), assistantMessage(msgId,
            blocks = listOf(
                thinkingBlock("th1", "Reasoning", ToolBlockStatus.SUCCESS),
                toolBlock("tool_1", ToolBlockStatus.SUCCESS),
                textBlock("text_1_2", "The answer is"),
            ),
            isStreaming = true))
        val k3 = keysOf(ledger.reconcile(t3))
        // New text row appended at tail; previous keys unchanged prefix.
        assertTrue(k3.take(k2.size) == k2)
        assertEquals("mdblock:a1:text_1_2:0", k3.last())

        // Text grows across a paragraph boundary: one more slot appended.
        val t4 = listOf(userMessage("u1"), assistantMessage(msgId,
            blocks = listOf(
                thinkingBlock("th1", "Reasoning", ToolBlockStatus.SUCCESS),
                toolBlock("tool_1", ToolBlockStatus.SUCCESS),
                textBlock("text_1_2", "The answer is yes.\n\nMore detail here"),
            ),
            isStreaming = true))
        val k4 = keysOf(ledger.reconcile(t4))
        assertTrue(k4.take(k3.size) == k3)
        assertEquals(listOf("mdblock:a1:text_1_2:0", "mdblock:a1:text_1_2:1"), k4.takeLast(2))
    }

    @Test fun `stream end keeps every key identical`() {
        val msgId = "a1"
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)
        val before = keysOf(ledger.reconcile(listOf(
            userMessage("u1"),
            assistantMessage(msgId,
                blocks = listOf(textBlock("text_1_0", "P1\n\nP2 tail")),
                isStreaming = true),
        )))
        val after = keysOf(ledger.reconcile(listOf(
            userMessage("u1"),
            assistantMessage(msgId,
                blocks = listOf(textBlock("text_1_0", "P1\n\nP2 tail")),
                isStreaming = false),
        )))
        assertEquals(before, after)
    }

    @Test fun `next user turn leaves previous turn keys byte-identical`() {
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)
        val turn1 = listOf(
            userMessage("u1"),
            assistantMessage("a1", blocks = listOf(textBlock("text_1_0", "Answer one")), isStreaming = false),
        )
        val k1 = keysOf(ledger.reconcile(turn1))
        // Next user message + new turn.
        val turn2 = listOf(
            userMessage("u1"),
            assistantMessage("a1", blocks = listOf(textBlock("text_1_0", "Answer one")), isStreaming = false),
            userMessage("u2"),
            assistantMessage("a2", blocks = listOf(textBlock("text_2_1", "Answer two")), isStreaming = false),
        )
        val k2 = keysOf(ledger.reconcile(turn2))
        assertTrue(k2.take(k1.size) == k1)
        // New rows: u2 bubble + a2 header + a2 text slot.
        assertEquals(listOf("user:u2", "header:a2", "mdblock:a2:text_2_1:0"), k2.drop(k1.size))
    }

    // ── text → tool → text (tool arrives late) ──────────────────────────────

    @Test fun `tool appearing after text appends without re-inserting before text`() {
        val msgId = "a1"
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)
        val t1 = listOf(userMessage("u1"), assistantMessage(msgId, blocks = listOf(textBlock("text_1_0", "Preface")), isStreaming = true))
        val k1 = keysOf(ledger.reconcile(t1))
        assertEquals(listOf("user:u1", "header:a1", "mdblock:a1:text_1_0:0"), k1)

        // Tool arrives AFTER text — first-appearance order: toolrun appends.
        val t2 = listOf(userMessage("u1"), assistantMessage(msgId,
            blocks = listOf(
                textBlock("text_1_0", "Preface"),
                toolBlock("tool_2", ToolBlockStatus.RUNNING),
            ),
            isStreaming = true))
        val k2 = keysOf(ledger.reconcile(t2))
        assertTrue(k2.take(k1.size) == k1)
        assertEquals("toolrun:a1", k2.last())
    }

    // ── transient rows ──────────────────────────────────────────────────────

    @Test fun `typing row may disappear without moving anchored rows`() {
        val msgId = "a1"
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)
        // Streaming with no visible content yet → typing row.
        val t1 = listOf(userMessage("u1"), assistantMessage(msgId, content = "", isStreaming = true))
        val k1 = keysOf(ledger.reconcile(t1))
        assertTrue(k1.contains("typing:a1"))

        // Content arrives → typing dropped, text row appended, nothing else moved.
        val t2 = listOf(userMessage("u1"), assistantMessage(msgId,
            content = "visible", blocks = listOf(textBlock("text_1_1", "visible")), isStreaming = true))
        val k2 = keysOf(ledger.reconcile(t2))
        assertFalse(k2.contains("typing:a1"))
        assertTrue(k2.contains("header:a1"))
        assertTrue(k2.last() == "mdblock:a1:text_1_1:0")
    }

    @Test fun `error banner row may disappear after retry`() {
        val msgId = "a1"
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)
        val failed = listOf(userMessage("u1"), assistantMessage(msgId, content = "text", error = "boom"))
        val k1 = keysOf(ledger.reconcile(failed))
        assertTrue(k1.contains("error:a1"))

        val retried = listOf(userMessage("u1"), assistantMessage(msgId, content = "text"))
        val k2 = keysOf(ledger.reconcile(retried))
        assertFalse(k2.contains("error:a1"))
        assertTrue(k2.contains("legacy:a1"))
    }

    // ── retry / text-structure reset ────────────────────────────────────────

    @Test fun `retry with new block ids resets only that message text rows`() {
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)
        val turn1 = listOf(
            userMessage("u1"),
            assistantMessage("a1", blocks = listOf(textBlock("text_1_0", "Old content")), isStreaming = false),
            userMessage("u2"),
            assistantMessage("a2", blocks = listOf(textBlock("text_2_1", "Second")), isStreaming = false),
        )
        val kBefore = keysOf(ledger.reconcile(turn1))

        // Retry rewrites turn 2 with a NEW turn counter in the block id.
        val retried = listOf(
            userMessage("u1"),
            assistantMessage("a1", blocks = listOf(textBlock("text_1_0", "Old content")), isStreaming = false),
            userMessage("u2"),
            assistantMessage("a2", blocks = listOf(textBlock("text_3_2", "Fresh retry answer")), isStreaming = false),
        )
        val kAfter = keysOf(ledger.reconcile(retried))
        // Turn 1 keys identical.
        assertEquals(kBefore.take(3), kAfter.take(3))
        // Turn 2: old text row gone, new text row present.
        assertFalse(kAfter.contains("mdblock:a2:text_2_1:0"))
        assertTrue(kAfter.contains("mdblock:a2:text_3_2:0"))
        assertTrue(kAfter.contains("header:a2"))
    }

    // ── neighbour lookback fixes ────────────────────────────────────────────

    @Test fun `back-to-back user messages get precededByUser flag`() {
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)
        val rows = ledger.reconcile(listOf(userMessage("u1"), userMessage("u2")))
        val u2 = rows.filterIsInstance<FlatChatItem.UserBubble>().last()
        assertTrue("second user bubble should carry precededByUser", u2.precededByUser)
    }

    @Test fun `resume continuation suppresses duplicate assistant header`() {
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)
        // Assistant turn, then a resume creates a NEW assistant message right after.
        val rows = ledger.reconcile(listOf(
            userMessage("u1"),
            assistantMessage("a1", content = "First"),
            assistantMessage("a2", content = "Resumed continuation"),
        ))
        val headers = rows.filterIsInstance<FlatChatItem.AssistantHeader>()
        assertEquals("only one header for a resumed turn", 1, headers.size)
    }

    // ── info rows append in first-appearance order ──────────────────────────

    @Test fun `info row appends at tail and updates in place`() {
        val msgId = "a1"
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)
        val t1 = listOf(userMessage("u1"), assistantMessage(msgId, blocks = listOf(textBlock("text_1_0", "Body")), isStreaming = true))
        val k1 = keysOf(ledger.reconcile(t1))
        val t2 = listOf(userMessage("u1"), assistantMessage(msgId,
            blocks = listOf(
                textBlock("text_1_0", "Body"),
                AssistantBlock(id = "info_9", kind = "info", content = "notice"),
            ),
            isStreaming = true))
        val k2 = keysOf(ledger.reconcile(t2))
        assertTrue(k2.take(k1.size) == k1)
        assertEquals("info:a1:info_9", k2.last())
        // No key churn on subsequent ticks.
        val k3 = keysOf(ledger.reconcile(t2))
        assertEquals(k2, k3)
    }

    // ── interrupt / queued-prompt handling (fix) ────────────────────────────

    @Test fun `interrupted thinking message drop is detected as incompatible`() {
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)

        // Turn 1 streaming: thinking only, no content yet.
        val turn1 = listOf(
            userMessage("u1"),
            assistantMessage("a1", blocks = listOf(thinkingBlock("th1", "Reasoning")), isStreaming = true),
        )
        val k1 = keysOf(ledger.reconcile(turn1))
        assertTrue(k1.contains("thinking:a1:th1")) // thinking is its own row (no tool yet)

        // User interrupts while streaming: queued bubble appears (enqueuePrompt),
        // A1 is still in the list.
        val interrupted = turn1 + userMessage("q2", content = "stop", isQueued = true)
        val k2 = keysOf(ledger.reconcile(interrupted))
        assertEquals(listOf("user:q2"), k2.drop(k1.size))

        // handleUserCancelledCleanup Case 0: A1 (no content, no tool) is REMOVED
        // from _messages. The count shrinks below the reconciled index — the
        // append-only contract no longer holds → the ledger must demand a full
        // rebuild (otherwise the stale thinking row would linger forever).
        val afterCancel = listOf(
            userMessage("u1"),
            userMessage("q2", content = "stop", isQueued = true),
        )
        assertFalse(
            "message removal must be detected as structurally incompatible",
            ledger.isIncrementallyCompatible(afterCancel),
        )

        // What ChatScreen does on incompatibility: full seed → stale rows gone.
        ledger.seed(buildFlatChatItems(afterCancel), afterCancel.size)
        val k3 = keysOf(ledger.snapshot())
        assertFalse("stale thinking row must be gone", k3.contains("thinking:a1:th1"))
        assertTrue(k3.contains("user:q2"))
    }

    @Test fun `queued user bubble flips to sent in place`() {
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)
        val queued = listOf(
            userMessage("u1"),
            userMessage("q2", content = "stop", isQueued = true),
        )
        val k1 = keysOf(ledger.reconcile(queued))
        assertTrue(
            "queued bubble must render dashed",
            ledger.snapshot().filterIsInstance<FlatChatItem.UserBubble>().last().message.isQueued,
        )

        // drainQueuedPrompts flips the SAME message id — count and head unchanged,
        // so the append-only contract still holds…
        val sent = listOf(
            userMessage("u1"),
            userMessage("q2", content = "stop", isQueued = false),
        )
        assertTrue(ledger.isIncrementallyCompatible(sent))
        // …but the published row must update in place (no key churn).
        val k2 = keysOf(ledger.reconcile(sent))
        assertEquals("no key churn on queued→sent flip", k1, k2)
        assertFalse(
            "sent bubble must lose dashed styling",
            ledger.snapshot().filterIsInstance<FlatChatItem.UserBubble>().last().message.isQueued,
        )
    }

    @Test fun `head anchors after first reconcile and detects prepended history`() {
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)
        val base = listOf(userMessage("u1"), assistantMessage("a1", content = "Answer"))
        ledger.reconcile(base)

        // Head is now anchored at u1: prepending older history must invalidate.
        val older = listOf(userMessage("u0"), userMessage("u1"), assistantMessage("a1", content = "Answer"))
        assertFalse("prepended history must be detected", ledger.isIncrementallyCompatible(older))

        // Same head, grown tail → still compatible.
        val grown = base + userMessage("u2")
        assertTrue(ledger.isIncrementallyCompatible(grown))
    }

    // ── interrupted-turn status sync (fix) ─────────────────────────────────

    @Test fun `running tool group of an interrupted turn is drained to terminal in place`() {
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)

        // Turn 1: assistant runs ONE tool (RUNNING), then queued interrupt.
        val turn1 = listOf(
            userMessage("u1"),
            assistantMessage("a1", blocks = listOf(toolBlock("tool_1", ToolBlockStatus.RUNNING)), isStreaming = true),
        )
        ledger.reconcile(turn1)
        val toolRun = ledger.snapshot().filterIsInstance<FlatChatItem.AssistantToolRunGroup>()
            .firstOrNull { it.messageId == "a1" }!!
        assertTrue("tool group must start RUNNING", toolRun.isRunning)

        // User interrupts in the middle of the tool call (enqueuePrompt →
        // new user bubble appended). The RUNNING tool then finishes naturally:
        // canonical flips it to SUCCESS and isStreaming=false. This is the
        // final single frame the UI observes; the ledger must drain the
        // published RUNNING pill without rebuilding/touching anything else.
        val finalFrame = listOf(
            userMessage("u1"),
            assistantMessage("a1",
                blocks = listOf(toolBlock("tool_1", ToolBlockStatus.SUCCESS)),
                isStreaming = false,
            ),
            userMessage("u2", content = "stop"),
        )
        val kBefore = keysOf(ledger.snapshot())
        val kAfter = keysOf(ledger.reconcile(finalFrame))
        // Only the new user bubble is appended; the interrupted turn keeps its
        // rows (key + order stable, no reseed).
        assertEquals(kBefore + listOf("user:u2"), kAfter)
        val afterRun = ledger.snapshot().filterIsInstance<FlatChatItem.AssistantToolRunGroup>()
            .firstOrNull { it.messageId == "a1" }!!
        assertFalse("tool group of interrupted turn must stop running", afterRun.isRunning)
        assertEquals(
            "tool PILL must reflect terminal SUCCESS",
            ToolBlockStatus.SUCCESS,
            afterRun.tools.single().toolStatus,
        )
    }

    @Test fun `thinking placeholder of an interrupted awaiting turn is dropped when terminal`() {
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)

        // Turn 1: assistant is awaiting model response, no content yet → typing.
        val turn1 = listOf(
            userMessage("u1"),
            assistantMessage("a1", content = "", isStreaming = true, isAwaitingModelResponse = true),
        )
        val k1 = keysOf(ledger.reconcile(turn1))
        assertTrue(k1.contains("typing:a1"))

        // User interrupts; a1 never produced content (handleUserCancelledCleanup
        // would normally drop it — but when the turn DID produce, it stays).
        // Here: a1 finished streaming with no content → canonical has only a
        // legacy/void state; the typing placeholder must be dropped, not pinned.
        val terminal = listOf(
            userMessage("u1"),
            assistantMessage("a1", content = "", isStreaming = false),
            userMessage("u2", content = "stop"),
        )
        val k2 = keysOf(ledger.reconcile(terminal))
        assertTrue("new user bubble must append", k2.contains("user:u2"))
        assertFalse("stale typing placeholder must be dropped", k2.contains("typing:a1"))
    }

    @Test fun `queued placeholder replaced by real user message at same index is incompatible`() {
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)

        // Turn 1 running; queued bubble occupies index 1.
        val turn1 = listOf(
            userMessage("u1"),
            assistantMessage("a1", blocks = listOf(toolBlock("tool_1", ToolBlockStatus.RUNNING)), isStreaming = true),
            userMessage("q2", content = "stop", isQueued = true),
        )
        ledger.reconcile(turn1)

        // The queued placeholder is replaced at the SAME index by a real user
        // message (different id). Count stays 3, head unchanged — but the
        // already-published a1 → q2 prefix changed, so plain head+count check
        // would wrongly pass. The ledger must demand a full rebuild.
        val replaced = listOf(
            userMessage("u1"),
            assistantMessage("a1", blocks = listOf(toolBlock("tool_1", ToolBlockStatus.SUCCESS)), isStreaming = false),
            userMessage("u2", content = "stop", isQueued = false),
        )
        assertFalse(
            "same-index replacement within the published prefix must be incompatible",
            ledger.isIncrementallyCompatible(replaced),
        )
    }

    @Test fun `status sync leaves non-target rows completely untouched`() {
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)

        // Two recorded turns + a queued interrupt in flight.
        val base = listOf(
            userMessage("u1"),
            assistantMessage("a1", blocks = listOf(textBlock("text_1_0", "Answer one"), toolBlock("tool_1", ToolBlockStatus.RUNNING)), isStreaming = true),
            userMessage("u2"),
            assistantMessage("a2", blocks = listOf(textBlock("text_2_1", "Answer two")), isStreaming = false),
        )
        val kBase = keysOf(ledger.reconcile(base))

        // a1 drains to terminal (its tool finishes) while a2 stays frozen.
        val drained = listOf(
            userMessage("u1"),
            assistantMessage("a1", blocks = listOf(textBlock("text_1_0", "Answer one"), toolBlock("tool_1", ToolBlockStatus.SUCCESS)), isStreaming = false),
            userMessage("u2"),
            assistantMessage("a2", blocks = listOf(textBlock("text_2_1", "Answer two")), isStreaming = false),
        )
        val kDrained = keysOf(ledger.reconcile(drained))
        // Zero structural change: identical key sequence the whole way.
        assertEquals(kBase, kDrained)
        val a1Group = ledger.snapshot().filterIsInstance<FlatChatItem.AssistantToolRunGroup>()
            .firstOrNull { it.messageId == "a1" }
        assertFalse("a1 tool must stop running", a1Group!!.isRunning)
    }

    @Test
    fun `appended live assistant with pending tool is registered and converges`() {
        // Seed a settled conversation history first (no live assistant).
        val settled = listOf(
            userMessage("u0"),
            assistantMessage("a0", blocks = listOf(textBlock("t0", "Old answer")), isStreaming = false),
        )
        val ledger = StableChatRowLedger()
        ledger.seed(buildFlatChatItems(settled, "s1"), settled.size)
        val kSettled = keysOf(ledger.snapshot())
        assertTrue(kSettled.isNotEmpty())

        // Append-only reconcile: new user message + new assistant in PENDING state.
        // This is the real-life flow after the conversation is already on screen:
        // the live assistant is appended, never re-seeded.
        val live = settled + listOf(
            userMessage("u1"),
            assistantMessage("a1", blocks = listOf(toolBlock("tool_1", ToolBlockStatus.PENDING)), isStreaming = true),
        )
        val kV1 = keysOf(ledger.reconcile(live))
        // Sanity: PENDING tool must render as running in the initial appended row.
        val a1Running = ledger.snapshot().filterIsInstance<FlatChatItem.AssistantToolRunGroup>()
            .firstOrNull { it.messageId == "a1" }
        assertNotNull("a1 must be tracked as live", a1Running)
        assertTrue("PENDING tool must render running", a1Running!!.isRunning)

        // Converge the same appended assistant to terminal in the next frame.
        val terminal = settled + listOf(
            userMessage("u1"),
            assistantMessage("a1", blocks = listOf(toolBlock("tool_1", ToolBlockStatus.SUCCESS)), isStreaming = false),
        )
        val kV2 = keysOf(ledger.reconcile(terminal))
        // Row topology stays identical; only the group status flips.
        assertEquals(kV1, kV2)
        val a1Terminal = ledger.snapshot().filterIsInstance<FlatChatItem.AssistantToolRunGroup>()
            .firstOrNull { it.messageId == "a1" }
        assertFalse("a1 tool must stop running after converge", a1Terminal!!.isRunning)
    }

    @Test
    fun `interrupted thinking rows are fully replaced on converge`() {
        // Seed a conversation with a prior assistant answer.
        val settled = listOf(
            userMessage("u0"),
            assistantMessage("a0", blocks = listOf(textBlock("t0", "Prior answer")), isStreaming = false),
        )
        val ledger = StableChatRowLedger()
        ledger.seed(buildFlatChatItems(settled, "s1"), settled.size)
        assertTrue(keysOf(ledger.snapshot()).isNotEmpty())

        // User asks a new question → assistant starts thinking (streaming, no
        // tools yet, only a thinking text block).
        val thinking = settled + listOf(
            userMessage("u1"),
            assistantMessage("a1", blocks = listOf(textBlock("t1", "Let me think…")), isStreaming = true),
        )
        val kV1 = keysOf(ledger.reconcile(thinking))
        // The thinking message should have live rows with a markdown block.
        val a1Rows = ledger.snapshot().filter { it.owningMessageId() == "a1" }
        assertTrue("a1 must have published rows during thinking", a1Rows.isNotEmpty())
        assertTrue("a1 must have a markdown block during thinking",
            a1Rows.any { it is FlatChatItem.AssistantMarkdownBlock })

        // User interrupts — the assistant is cancelled, streaming stops, and
        // the canonical message now has only the text that arrived before the
        // interrupt (or none if cancelled before any content arrived).
        val interrupted = settled + listOf(
            userMessage("u1"),
            assistantMessage("a1", blocks = listOf(textBlock("t1", "Let me think…")), isStreaming = false),
        )
        val kV2 = keysOf(ledger.reconcile(interrupted))
        // After converge the rows should still be present (the content is
        // kept), but the message is no longer tracked as active.
        assertEquals("row topology must not change on converge", kV1, kV2)
        // The ledger's internal activeAssistantIds should be empty.
        assertEquals("no live assistant ids after converge", 0,
            ledger.activeAssistantIdsCount())
    }

    @Test
    fun `running tool flips to cancelled on interrupt without staying live`() {
        // Seed settled history.
        val settled = listOf(
            userMessage("u0"),
            assistantMessage("a0", blocks = listOf(textBlock("t0", "Prior answer")), isStreaming = false),
        )
        val ledger = StableChatRowLedger()
        ledger.seed(buildFlatChatItems(settled, "s1"), settled.size)
        assertTrue(keysOf(ledger.snapshot()).isNotEmpty())

        // Assistant turn with a RUNNING tool (streaming in flight).
        val running = settled + listOf(
            userMessage("u1"),
            assistantMessage(
                "a1",
                blocks = listOf(toolBlock("tool_1", ToolBlockStatus.RUNNING)),
                isStreaming = true,
            ),
        )
        val kV1 = keysOf(ledger.reconcile(running))
        val runGroup = ledger.snapshot().filterIsInstance<FlatChatItem.AssistantToolRunGroup>()
            .firstOrNull { it.messageId == "a1" }
        assertNotNull("running tool must be published as a run group", runGroup)
        assertTrue("RUNNING tool must render isRunning", runGroup!!.isRunning)
        assertTrue("a1 must be tracked live", ledger.activeAssistantIdsCount() >= 1)

        // User interrupts — cancelStream flips the in-flight tool to
        // CANCELLED and isStreaming to false in the canonical message.
        val cancelled = settled + listOf(
            userMessage("u1"),
            assistantMessage(
                "a1",
                blocks = listOf(toolBlock("tool_1", ToolBlockStatus.CANCELLED)),
                isStreaming = false,
            ),
        )
        val kV2 = keysOf(ledger.reconcile(cancelled))
        assertEquals("row topology must stay stable across the flip", kV1, kV2)
        val afterFlip = ledger.snapshot().filterIsInstance<FlatChatItem.AssistantToolRunGroup>()
            .firstOrNull { it.messageId == "a1" }
        assertNotNull("cancelled tool row must still exist", afterFlip)
        assertFalse("CANCELLED tool must NOT render isRunning", afterFlip!!.isRunning)
        assertEquals("no tracked live assistant after cancel", 0,
            ledger.activeAssistantIdsCount())
    }

    // ── [fix/long-session-flatten-storm] P0: text rows stay segmenter-owned ──

    @Test
    fun `reconcile text rows match canonical build (dual-split removal is lossless)`() {
        val msgId = "a1"
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)

        // Streaming assistant message with mature multi-paragraph text — the
        // exact shape that previously triggered a double markdown split per
        // 80ms tick (buildNewMessageRows split the text, reconcileMessage
        // threw those rows away, then the segmenter split it AGAIN). After the
        // fix the segmenter is the single owner: text rows must still match
        // the canonical full build byte-for-byte, including the paragraph
        // split boundaries and the block/raw content.
        val frames = listOf(
            assistantMessage(msgId, blocks = listOf(textBlock("text_1_0", "Intro line")), isStreaming = true),
            assistantMessage(msgId, blocks = listOf(textBlock("text_1_0", "Intro line\n\nPara two… and a tail")), isStreaming = true),
        )
        for (frame in frames) {
            val live = listOf(userMessage("u1"), frame)
            val rows = ledger.reconcile(live)
            val md = rows.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
            val expectedRaw = buildFlatChatItems(live)
                .filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
                .map { it.rawText }
            assertEquals(
                "ledger text rows must remain byte-identical to the canonical split",
                expectedRaw,
                md.map { it.rawText },
            )
        }

        // Terminal frame: split fully settled, keys stable, content intact.
        val settled = listOf(
            userMessage("u1"),
            assistantMessage(msgId, blocks = listOf(textBlock("text_1_0", "Intro line\n\nPara two… and a tail")), isStreaming = false),
        )
        val rows = ledger.reconcile(settled)
        val md = rows.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
        assertEquals(2, md.size)
        assertEquals(listOf("Intro line", "Para two… and a tail"), md.map { it.rawText })
    }

    // ── [fix/chat-render-turnend-settle] turn-end settle: no re-seed, key
    //    stability, and same-length-rewrite convergence ────────────────────

    @Test
    fun `turn-end settle keeps every key identical after reconcileAndVerify`() {
        val msgId = "a1"
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)

        val streaming = listOf(
            userMessage("u1"),
            assistantMessage(msgId, blocks = listOf(textBlock("text_1_0", "P1\n\nP2 tail")), isStreaming = true),
        )
        val kLive = keysOf(ledger.reconcile(streaming))

        val terminal = listOf(
            userMessage("u1"),
            assistantMessage(msgId, blocks = listOf(textBlock("text_1_0", "P1\n\nP2 tail")), isStreaming = false),
        )
        val kSettled = keysOf(ledger.reconcile(terminal))
        ledger.reconcileAndVerifyTerminalText(terminal)

        assertEquals("turn-end must NOT change any published key", kLive, kSettled)
        assertEquals("verify pass must keep keys stable too", kSettled, keysOf(ledger.snapshot()))
        val md = ledger.snapshot().filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
        assertEquals(listOf("P1", "P2 tail"), md.map { it.rawText })
    }

    @Test
    fun `turn-end same-length rewrite is converged by verify pass`() {
        val msgId = "a1"
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)

        // Live turn streams "AAAA"; the terminal snapshot rewrites the SAME
        // length text to "BBBB". AssistantMarkdownBlock.equals only compares
        // lengths — without the verify pass the rendered text would stay
        // "AAAA" forever.
        val streaming = listOf(
            userMessage("u1"),
            assistantMessage(msgId, blocks = listOf(textBlock("text_1_0", "AAAA")), isStreaming = true),
        )
        ledger.reconcile(streaming)

        val terminal = listOf(
            userMessage("u1"),
            assistantMessage(msgId, blocks = listOf(textBlock("text_1_0", "BBBB")), isStreaming = false),
        )
        ledger.reconcile(terminal)
        // BEFORE the guard: same key set, same LENGTHs → LazyColumn skips.
        assertEquals("keys are unchanged (settle)", keysOf(ledger.snapshot()), keysOf(ledger.reconcile(terminal)))

        ledger.reconcileAndVerifyTerminalText(terminal)
        val md = ledger.snapshot().filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
        assertEquals("terminal rewrite must be published", listOf("BBBB"), md.map { it.rawText })
        assertEquals(
            "converged keys stay stable (user row + header + mdblock)",
            listOf("user:u1", "header:a1", "mdblock:a1:text_1_0:0"),
            keysOf(ledger.snapshot()),
        )
    }

    @Test
    fun `settled then same-length rewrite is converged by verify pass`() {
        // [fix/chat-render-verify-p1] Regression for the REAL blind spot: the
        // earlier test only exercised a rewrite while the block was still LIVE
        // (isStreaming=true, settledCount=0), where the segmenter's
        // absorbDivergence shrink branch had no frozen slot to keep, so
        // reconcile itself converged and verify never had to fire. The actual
        // production failure is a Same-length rewrite AFTER the block settled:
        // "AAAA\n\nP2" (two paragraph slots) is published+settled, then the
        // terminal snapshot replaces it with same-length "BBBB\n\nP2" — the
        // segmenter refuses to drop the frozen "AAAA" slot (fresh.size <=
        // settledCount), and the old verify pass compared segmenter output vs
        // published rows (both stale "AAAA"), so BBBB never reached the screen.
        //
        // start with a streamed (live) short block, then settle it.
        val msgId = "a1"
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)

        val streaming = listOf(
            userMessage("u1"),
            assistantMessage(msgId, blocks = listOf(textBlock("text_1_0", "AAAA")), isStreaming = true),
        )
        ledger.reconcile(streaming)
        // settle: same text, isStreaming=false → segmenter freezes "AAAA".
        val settled = listOf(
            userMessage("u1"),
            assistantMessage(msgId, blocks = listOf(textBlock("text_1_0", "AAAA")), isStreaming = false),
        )
        ledger.reconcile(settled)
        assertEquals("AAAA settled", listOf("AAAA"),
            ledger.snapshot().filterIsInstance<FlatChatItem.AssistantMarkdownBlock>().map { it.rawText })

        // Same-length rewrite of the settled block. The key set is unchanged
        // (settle keeps keys), and LazyColumn's length-only equals would skip
        // — only the verify pass can republish BBBB.
        val rewritten = listOf(
            userMessage("u1"),
            assistantMessage(msgId, blocks = listOf(textBlock("text_1_0", "BBBB")), isStreaming = false),
        )
        ledger.reconcile(rewritten)
        ledger.reconcileAndVerifyTerminalText(rewritten)
        val md = ledger.snapshot().filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
        assertEquals("settled-then-rewrite must publish BBBB", listOf("BBBB"), md.map { it.rawText })
        assertEquals(
            "converged keys stay stable",
            listOf("user:u1", "header:a1", "mdblock:a1:text_1_0:0"),
            keysOf(ledger.snapshot()),
        )
    }

    @Test
    fun `settled multiline rewrite publish is converged by verify pass`() {
        // The same rewrite blind spot for a block that had already split into
        // MANY settled slots: stream "P1\n\nP2" (published+settled as two
        // slots), then rewrite to a same-length-pair "P3\n\nQ3" — the
        // segmenter shrink branch refuses to replace the frozen "P1"/"P2"
        // slots, so only verify (comparing the canonical split directly
        // against published) can publish the new text. Also guards that the
        // canonical split's slot ordering matches rebuildBlockRows' map order.
        val msgId = "a1"
        val ledger = StableChatRowLedger()
        ledger.seed(emptyList(), 0)

        val streaming = listOf(
            userMessage("u1"),
            assistantMessage(msgId, blocks = listOf(textBlock("t1", "P1\n\nP2")), isStreaming = true),
        )
        ledger.reconcile(streaming)
        val settled = listOf(
            userMessage("u1"),
            assistantMessage(msgId, blocks = listOf(textBlock("t1", "P1\n\nP2")), isStreaming = false),
        )
        ledger.reconcile(settled)
        assertEquals(listOf("P1", "P2"),
            ledger.snapshot().filterIsInstance<FlatChatItem.AssistantMarkdownBlock>().map { it.rawText })

        val rewritten = listOf(
            userMessage("u1"),
            assistantMessage(msgId, blocks = listOf(textBlock("t1", "P3\n\nQ3")), isStreaming = false),
        )
        ledger.reconcile(rewritten)
        ledger.reconcileAndVerifyTerminalText(rewritten)
        val md = ledger.snapshot().filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
        assertEquals("each settled slot must be republished in canonical order", listOf("P3", "Q3"), md.map { it.rawText })
        // Same key count (two mdblock slots), stable keys.
        assertEquals(
            listOf("user:u1", "header:a1", "mdblock:a1:t1:0", "mdblock:a1:t1:1"),
            keysOf(ledger.snapshot()),
        )
    }

    // ── [fix/chat-render-tick-scan] light fingerprint + scoped sync ────────

    @Test
    fun `fingerprint is unchanged when rendering-relevant state is identical`() {
        val m1 = assistantMessage("a1", content = "Hello world", blocks = listOf(textBlock("t1", "Body")), isStreaming = true)
        val m2 = assistantMessage("a1", content = "Hello world", blocks = listOf(textBlock("t1", "Body")), isStreaming = true)
        assertEquals("identical messages must produce identical fingerprints", lightFingerprint(listOf(m1)), lightFingerprint(listOf(m2)))

        // Content changed but length identical — the blind spot the turn-end
        // verify pass owns; the fingerprint correctly reports "unchanged".
        val m3 = assistantMessage("a1", content = "Hello world", blocks = listOf(textBlock("t1", "Bodx")), isStreaming = true)
        assertEquals("same-length rewrite must not trip the fingerprint",
            lightFingerprint(listOf(m1)), lightFingerprint(listOf(m3)))
    }

    @Test
    fun `fingerprint changes when any live flag or tool status flips`() {
        val base = assistantMessage("a1", content = "x", blocks = listOf(toolBlock("tool_1", ToolBlockStatus.RUNNING)), isStreaming = true)
        val flips = listOf(
            base.copy(isStreaming = false),
            base.copy(isAwaitingModelResponse = true),
            base.copy(error = "boom"),
            base.copy(isQueued = true),
            base.copy(toolBlocks = listOf(toolBlock("tool_1", ToolBlockStatus.SUCCESS))),
            base.copy(toolBlocks = listOf(toolBlock("tool_1", ToolBlockStatus.RUNNING), toolBlock("tool_2", ToolBlockStatus.RUNNING))),
            base.copy(toolBlocks = listOf(toolBlock("tool_1", ToolBlockStatus.RUNNING, title = "different"))),
        )
        for ((i, flipped) in flips.withIndex()) {
            assertFalse("flip #$i must change the fingerprint",
                lightFingerprint(listOf(base)) == lightFingerprint(listOf(flipped)))
        }
    }

    @Test
    fun `syncActiveAssistantStatus only walks the active window`() {
        val ledger = StableChatRowLedger()
        val msgs = mutableListOf<ChatMessage>()
        val nHistory = 8
        for (i in 0 until nHistory) {
            msgs.add(userMessage("u$i", content = "q"))
            msgs.add(assistantMessage("a$i", content = "answer $i", blocks = listOf(textBlock("t_$i", "A$i"))))
        }
        val seedRows = buildFlatChatItems(msgs)
        ledger.seed(seedRows, msgs.size)

        // Append a streaming active turn.
        msgs.add(userMessage("uNew"))
        msgs.add(assistantMessage("aNew", blocks = listOf(textBlock("t_new", "live")), isStreaming = true))
        ledger.reconcile(msgs)

        val fullRows = ledger.snapshot()
        val firstOfActive = fullRows.indexOfFirst { it.owningMessageId() == "aNew" }
        assertTrue("active turn must be published", firstOfActive >= 0)
        assertTrue("active window must NOT span the whole ledger (history is frozen)",
            firstOfActive < fullRows.size - 1 && firstOfActive > 0)
        assertTrue("only the appended live turn is tracked",
            ledger.activeAssistantIdsCount() <= 1)

        // Reconcile again with the same topology — the scoped sync must not
        // disturb any history row.
        val before = fullRows.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>().map { it.rawText }
        ledger.reconcile(msgs)
        val after1 = ledger.snapshot().filterIsInstance<FlatChatItem.AssistantMarkdownBlock>().map { it.rawText }
        assertEquals("historical text rows must be untouched by the scoped sync", before, after1)
    }
}
