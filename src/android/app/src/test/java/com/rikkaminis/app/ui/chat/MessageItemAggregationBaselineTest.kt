package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * JVM regression baseline for the CURRENT "message-level flattening" behavior,
 * frozen BEFORE stages C/D migrate a single message's many [FlatChatItem]s back
 * into one message-level item.
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * Stages C/D (`fix/message-node-item-generator` / `fix/message-node-item-renderer`)
 * will change how `buildFlatChatItems` turns one assistant `ChatMessage` into
 * rows. Before any production code moves, this test locks in the "before"
 * numbers and key-stability contract so C/D can prove *zero behaviour
 * regression* (identical key sequence for unchanged inputs) and quantify the
 * item-count delta they introduce.
 *
 * All assertions target PURE LOGIC only — no Compose UI class is instantiated.
 * The production functions under test are the verbatim sources of this baseline.
 *
 * ⚠️ IMPORTANT — "N tool_use → N+const items" is a STALE hypothesis:
 * The current code already aggregates every tool_use block of a message into a
 * single [FlatChatItem.AssistantToolRunGroup] (`[T-android-tool-run-collapse]`).
 * N tool_use blocks therefore produce exactly ONE toolrun row, not N. These
 * tests assert the TRUE current behaviour (the "before"), so C/D can measure
 * the real delta. The per-tool [FlatChatItem.AssistantToolUse] is no longer
 * emitted by the builder at all.
 */
class MessageItemAggregationBaselineTest {

    // ── helpers (mirror StableChatRowLedgerTest) ─────────────────────────

    private fun assistantMessage(
        id: String,
        content: String = "",
        blocks: List<AssistantBlock> = emptyList(),
        isStreaming: Boolean = false,
        isAwaitingModelResponse: Boolean = false,
        error: String? = null,
        thinkingLevel: com.rikkaminis.app.data.model.ThinkingLevel? = null,
    ) = ChatMessage(
        id = id,
        role = "assistant",
        content = content,
        isStreaming = isStreaming,
        isAwaitingModelResponse = isAwaitingModelResponse,
        toolBlocks = blocks,
        error = error,
        thinkingLevel = thinkingLevel,
    )

    private fun userMessage(id: String, content: String = "hello") =
        ChatMessage(id = id, role = "user", content = content)

    private fun textBlock(id: String, content: String) =
        AssistantBlock(id = id, kind = "text", content = content)

    private fun thinkingBlock(id: String, content: String, status: ToolBlockStatus? = null) =
        AssistantBlock(id = id, kind = "thinking", content = content, toolStatus = status)

    private fun toolBlock(id: String, status: ToolBlockStatus, title: String = "tool") =
        AssistantBlock(id = id, kind = "tool_use", toolStatus = status, toolTitle = title, toolName = "run")

    private fun infoBlock(id: String, content: String) =
        AssistantBlock(id = id, kind = "info", content = content)

    private fun keysOf(rows: List<FlatChatItem>): List<String> = rows.map { it.key }

    // ═════════════════════════════════════════════════════════════════════
    // 1. KEY STABILITY — deterministic, prefix-stable key sequence
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `key sequence is deterministic and prefix-stable across repeated builds`() {
        val msg = assistantMessage(
            "a1",
            blocks = listOf(
                thinkingBlock("th1", "Reasoning", ToolBlockStatus.SUCCESS),
                toolBlock("tool_1", ToolBlockStatus.SUCCESS, title = "run_tool"),
                textBlock("text_1", "First paragraph\n\nSecond paragraph"),
                textBlock("text_2", "Trailing paragraph"),
            ),
        )
        // The message is last assistant turn, so the LAST text block stays
        // fine-grained. text_1 is NOT the last text block, so its two
        // paragraphs are coalesced into ONE fragment; text_2 (last text, single
        // paragraph) stays one. → header + 1 thinking + 1 toolrun + 2 mdblocks.
        val keys1 = keysOf(buildFlatChatItems(listOf(userMessage("u1"), msg)))
        val keys2 = keysOf(buildFlatChatItems(listOf(userMessage("u1"), msg)))

        assertEquals("repeated build must yield the exact same key list", keys1, keys2)

        // Before-baseline expected sequence: user + header + 1 thinking row +
        // 1 toolrun group + text_1 (coalesced single fragment) + text_2 (one).
        assertEquals(
            listOf(
                "user:u1",
                "header:a1",
                "thinking:a1:th1",
                "toolrun:a1",
                "mdblock:a1:text_1:0",
                "mdblock:a1:text_2:0",
            ),
            keys1,
        )
        // Prefix stability: rebuild after a *structural append* keeps the old prefix.
        val grown = assistantMessage(
            "a1",
            blocks = listOf(
                thinkingBlock("th1", "Reasoning", ToolBlockStatus.SUCCESS),
                toolBlock("tool_1", ToolBlockStatus.SUCCESS, title = "run_tool"),
                textBlock("text_1", "First paragraph\n\nSecond paragraph"),
                textBlock("text_2", "Trailing paragraph"),
                textBlock("text_3", "More detail"),
            ),
        )
        val keysGrown = keysOf(buildFlatChatItems(listOf(userMessage("u1"), grown)))
        assertTrue("growing the message must keep old keys as a prefix", keysGrown.subList(0, keys1.size) == keys1)
        assertEquals("user:u1", keysGrown.first())
        assertEquals("mdblock:a1:text_3:0", keysGrown.last())
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. STREAMING TAIL ISOLATION — frozen prefix keys stable, only tail grows
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `streaming overlay keeps frozen-prefix keys while live tail content grows`() {
        // The streaming delta carries the message's live text block (the real
        // side-channel carries toolBlocks in the delta). Merged message stays
        // an mdblock whose content grows as the delta content grows.
        val base = listOf(
            userMessage("u1"),
            assistantMessage("a1", blocks = listOf(textBlock("tx1", "Intro\n\n"))),
        )

        fun delta(content: String) = StreamingDelta(
            content = content,
            toolBlocks = listOf(textBlock("tx1", content)),
            isAwaitingModelResponse = false,
            epoch = 0L,
        )
        val mergedA = mergeStreamingOverlay(base, mapOf("a1" to delta("Intro\n\nBody so far")), currentEpoch = 0L)
        val mergedB = mergeStreamingOverlay(base, mapOf("a1" to delta("Intro\n\nBody so far with more words")), currentEpoch = 0L)

        val keysA = keysOf(buildFlatChatItems(mergedA))
        val keysB = keysOf(buildFlatChatItems(mergedB))

        // Frozen prefix (user + header + first text fragment) keeps its keys;
        // the trailing split likewise keeps its fragment key as the text grows
        // (par. boundary count unchanged). Expect e.g.
        // [user:u1, header:a1, mdblock:a1:tx1:0, mdblock:a1:tx1:1] both times.
        assertEquals("prefix must be identical across stream growth", keysA, keysB)
        assertEquals("user:u1", keysA.first())
        assertEquals("header:a1", keysA[1])
        assertEquals("user:u1", keysB.first())
        // "Intro\n\nBody so far" splits on the blank line → fragments [0],[1];
        // the live tail is the LAST fragment (index 1) whose content grows.
        assertEquals("mdblock:a1:tx1:1", keysA.last())
        assertEquals("mdblock:a1:tx1:1", keysB.last())
        // The live tail fragment's content is what grows (not the key set).
        val tailA = buildFlatChatItems(mergedA).last() as FlatChatItem.AssistantMarkdownBlock
        val tailB = buildFlatChatItems(mergedB).last() as FlatChatItem.AssistantMarkdownBlock
        assertTrue("live tail content must grow while its key stays put", tailB.rawText.length > tailA.rawText.length)
        assertEquals(tailA.key, tailB.key)
    }

    @Test
    fun `streaming delta flips message to streaming but keeps key identity`() {
        val base = listOf(
            userMessage("u1"),
            assistantMessage("a1", blocks = listOf(textBlock("tx1", "Alpha"))),
        )
        val merged = mergeStreamingOverlay(
            base,
            mapOf("a1" to StreamingDelta(
                content = "Alphabet",
                // Carry the text block so the merged message still renders as an mdblock.
                toolBlocks = listOf(textBlock("tx1", "Alphabet")),
                isAwaitingModelResponse = false,
                epoch = 1L,
            )),
            currentEpoch = 1L,
        )
        assertTrue("overlay must mark the live message streaming", merged[1].isStreaming)
        // Keys are derived from id + block id + index, not content — the streaming
        // flip must not change the key sequence.
        assertEquals(keysOf(buildFlatChatItems(merged)), keysOf(buildFlatChatItems(base)))
    }

    @Test
    fun `before baseline streaming delta with empty toolBlocks falls back to legacy rendering`() {
        // Crucial baseline nuance: mergeStreamingOverlay *replaces* the message's
        // toolBlocks with the delta's. When the delta carries no blocks, the merged
        // message loses its text blocks and renders via AssistantLegacyContent
        // (single `legacy:` row) instead of an mdblock. C/D must preserve this —
        // or state clearly they are changing it.
        val base = listOf(
            userMessage("u1"),
            assistantMessage("a1", blocks = listOf(textBlock("tx1", "Alpha"))),
        )
        val merged = mergeStreamingOverlay(
            base,
            mapOf("a1" to StreamingDelta(content = "Alphabet", toolBlocks = emptyList(), isAwaitingModelResponse = false, epoch = 1L)),
            currentEpoch = 1L,
        )
        assertEquals(listOf("user:u1", "header:a1", "legacy:a1"), keysOf(buildFlatChatItems(merged)))
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. owningMessageId COMPLETENESS (incl. dedupe-suffix stripping)
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `every item owningMessageId maps back to its message id`() {
        val rows = buildFlatChatItems(
            listOf(
                userMessage("u1"),
                assistantMessage("a1", blocks = listOf(
                    thinkingBlock("th1", "Why", ToolBlockStatus.SUCCESS),
                    toolBlock("t1", ToolBlockStatus.SUCCESS),
                    textBlock("tx1", "Answer text"),
                    infoBlock("inf1", "system notice"),
                )),
                assistantMessage("a2", content = "legacy only"),
            ),
        )
        for (row in rows) {
            val owner = originalMessageId(row.owningMessageId())
            assertNotNull("owningMessageId must never be blank", owner)
            // Every owner is one of the two real message ids (dedupe suffix stripped).
            assertTrue(
                "row '${row.key}' owns unknown message '$owner'",
                owner == "u1" || owner == "a1" || owner == "a2",
            )
        }
        // Each message id is actually the owner of at least one row (completeness).
        val owners = rows.map { originalMessageId(it.owningMessageId()) }.toSet()
        assertEquals(setOf("u1", "a1", "a2"), owners)
    }

    @Test
    fun `dedupe-suffix stripping maps suffixed id back to base id`() {
        // Simulate a deduped key (message id gets a #N suffix once keys collide).
        assertEquals("a1", originalMessageId("a1"))
        assertEquals("a1", originalMessageId("a1#2"))
        assertEquals("a1", originalMessageId("a1#3"))
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. ITEM-COUNT BASELINE (the "before" numbers C/D will optimize against)
    // ═════════════════════════════════════════════════════════════════════
    //
    // ⚠️ Stale-task-hypothesis note (see class KDoc): tools are ALREADY
    // aggregated into ONE AssistantToolRunGroup per message. So "N tool_us"
    // does NOT produce N rows today — it produces a constant row count
    // (header + 1 toolrun). We freeze this as the true "before".

    @Test
    fun `N tool_us produce ONE toolrun row (constant), not N`() {
        // 2 tool_us, no text/thinking -> exactly 2 rows: header + toolrun group.
        val twoTools = assistantMessage(
            "a1",
            blocks = listOf(
                toolBlock("t1", ToolBlockStatus.SUCCESS, title = "first"),
                toolBlock("t2", ToolBlockStatus.SUCCESS, title = "second"),
            ),
        )
        val rows2 = buildFlatChatItems(listOf(twoTools))
        assertEquals(
            "n=2: header + 1 toolrun group (tools already aggregated)",
            listOf("header:a1", "toolrun:a1"),
            keysOf(rows2),
        )
        assertEquals(2, rows2.size)
        assertEquals(2, (rows2[1] as FlatChatItem.AssistantToolRunGroup).count)

        // 4 tool_us -> STILL 2 rows: header + 1 toolrun group.
        val fourTools = assistantMessage(
            "a1",
            blocks = listOf(
                toolBlock("t1", ToolBlockStatus.SUCCESS, title = "first"),
                toolBlock("t2", ToolBlockStatus.SUCCESS, title = "second"),
                toolBlock("t3", ToolBlockStatus.SUCCESS, title = "third"),
                toolBlock("t4", ToolBlockStatus.SUCCESS, title = "fourth"),
            ),
        )
        val rows4 = buildFlatChatItems(listOf(fourTools))
        assertEquals(listOf("header:a1", "toolrun:a1"), keysOf(rows4))
        assertEquals(2, rows4.size)
        assertEquals(4, (rows4[1] as FlatChatItem.AssistantToolRunGroup).count)
    }

    @Test
    fun `header plus toolrun plus thinking plus text is the full baseline shape`() {
        // A message with thinking + 2 tool_us + text -> 1 header + 1 thinking
        // + 1 toolrun + N text fragments. This is the "before" granularity:
        // a single message splits into header/thinking/toolrun/text* rows.
        val msg = assistantMessage(
            "a1",
            blocks = listOf(
                thinkingBlock("th1", "Reasoning"),
                toolBlock("t1", ToolBlockStatus.SUCCESS, title = "one"),
                toolBlock("t2", ToolBlockStatus.SUCCESS, title = "two"),
                textBlock("tx1", "Simple sentence"),
            ),
        )
        val rows = buildFlatChatItems(listOf(msg))
        assertEquals(
            listOf("header:a1", "thinking:a1:th1", "toolrun:a1", "mdblock:a1:tx1:0"),
            keysOf(rows),
        )
        // Fixed overhead (header + thinking + toolrun) before the answer text.
        assertEquals(3, rows.filterNot { it.contentType == "mdblock" }.size)
        assertEquals(1, rows.count { it.contentType == "mdblock" })
        // stage-C/D target: this same message should become a SMALLER set of rows.
        // Baseline total = 4 items for one message.
        assertEquals("before-baseline item count for one compound message is 4", 4, rows.size)
    }

    @Test
    fun `multiple text blocks split into independent mdblock rows`() {
        val msg = assistantMessage(
            "a1",
            blocks = listOf(
                textBlock("tx1", "First para\n\nSecond para"),
                textBlock("tx2", "Third para\n\nFourth para"),
            ),
        )
        val rows = buildFlatChatItems(listOf(msg))
        // header + tx1 + tx2. tx1 is NOT the last text block, so its two
        // paragraphs are COALESCED into one fragment; tx2 IS the last text
        // block, so it stays fine-grained (2 fragments). → 4 rows.
        assertEquals(
            listOf(
                "header:a1",
                "mdblock:a1:tx1:0",
                "mdblock:a1:tx2:0",
                "mdblock:a1:tx2:1",
            ),
            keysOf(rows),
        )
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. contentType STABILITY — contentType prefix matches the key prefix
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `contentType is the stable, key-prefix-consistent tag`() {
        val rows = buildFlatChatItems(
            listOf(
                userMessage("u1"),
                assistantMessage("a1", blocks = listOf(
                    thinkingBlock("th1", "R"),
                    toolBlock("t1", ToolBlockStatus.SUCCESS),
                    textBlock("tx1", "Answer"),
                    infoBlock("inf1", "notice"),
                )),
                assistantMessage("a2", error = "boom"),
                assistantMessage("a3", content = "legacy"),
            ),
        )
        // Complete type→key-prefix mapping for every item contentType.
        val expectedPrefix = mapOf(
            "user" to "user:u1",
            "header" to "header:a1",
            "thinking" to "thinking:a1:th1",
            "toolrun" to "toolrun:a1",
            "mdblock" to "mdblock:a1:tx1:0",
            "info" to "info:a1:inf1",
            "error" to "error:a2",
            "legacy" to "legacy:a3",
        )
        // Every produced contentType is one of the known set.
        val producedTypes = rows.map { it.contentType }.toSet()
        assertEquals(expectedPrefix.keys, producedTypes)
        // And it's stable under rebuild (same list => same contentTypes).
        val rows2 = buildFlatChatItems(
            listOf(
                userMessage("u1"),
                assistantMessage("a1", blocks = listOf(
                    thinkingBlock("th1", "R"),
                    toolBlock("t1", ToolBlockStatus.SUCCESS),
                    textBlock("tx1", "Answer"),
                    infoBlock("inf1", "notice"),
                )),
                assistantMessage("a2", error = "boom"),
                assistantMessage("a3", content = "legacy"),
            ),
        )
        assertEquals(rows.map { it.contentType }, rows2.map { it.contentType })
    }
}
