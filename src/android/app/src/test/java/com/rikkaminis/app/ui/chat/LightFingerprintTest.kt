package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [lightFingerprint] — the streaming-tick dirty check.
 *
 * Contract: fingerprint equality implies "no rendering-relevant change", so
 * the ChatScreen tick loop can skip the reconcile; any field that actually
 * renders (text growth, live flags, tool statuses, row-set size, tool-card
 * duration) must flip the fingerprint. Payload-only fields (toolArgs JSON
 * internals, attachment URIs, errorDetail) may be ignored — they never
 * render.
 */
class LightFingerprintTest {

    private fun textBlock(id: String, content: String) =
        AssistantBlock(id = id, kind = "text", content = content)

    private fun toolBlock(id: String, status: ToolBlockStatus, title: String = "tool") =
        AssistantBlock(id = id, kind = "tool_use", toolStatus = status, toolTitle = title, toolName = "run")

    private fun msg(
        id: String = "a1",
        content: String = "",
        blocks: List<AssistantBlock> = emptyList(),
        isStreaming: Boolean = false,
        isAwaitingModelResponse: Boolean = false,
        error: String? = null,
        isQueued: Boolean = false,
    ) = ChatMessage(
        id = id,
        role = "assistant",
        content = content,
        isStreaming = isStreaming,
        isAwaitingModelResponse = isAwaitingModelResponse,
        toolBlocks = blocks,
        error = error,
        isQueued = isQueued,
    )

    @Test fun `identical messages produce identical fingerprints`() {
        val a = msg(content = "Hello world", blocks = listOf(textBlock("t1", "Body")), isStreaming = true)
        val b = msg(content = "Hello world", blocks = listOf(textBlock("t1", "Body")), isStreaming = true)
        assertEquals(lightFingerprint(listOf(a)), lightFingerprint(listOf(b)))
        assertEquals(lightFingerprint(emptyList()), lightFingerprint(emptyList()))
    }

    @Test fun `same-length rewrite keeps fingerprint stable (owned by turn-end verify)`() {
        val a = msg(content = "Hello world", blocks = listOf(textBlock("t1", "Oldte")), isStreaming = true)
        val b = msg(content = "Hello world", blocks = listOf(textBlock("t1", "Newte")), isStreaming = true)
        // Same id / same lengths / same flags -> same fingerprint (this is the
        // documented blind spot that reconcileAndVerifyTerminalText closes).
        assertEquals(lightFingerprint(listOf(a)), lightFingerprint(listOf(b)))
    }

    @Test fun `content growth flips the fingerprint`() {
        val small = msg(content = "abc", blocks = listOf(textBlock("t1", "ab")))
        val grown = msg(content = "abcd", blocks = listOf(textBlock("t1", "ab")))
        val blockGrown = msg(content = "abc", blocks = listOf(textBlock("t1", "abc")))
        assertNotEquals(lightFingerprint(listOf(small)), lightFingerprint(listOf(grown)))
        assertNotEquals(lightFingerprint(listOf(small)), lightFingerprint(listOf(blockGrown)))
    }

    @Test fun `live flags flip the fingerprint`() {
        val base = msg(isStreaming = true)
        assertNotEquals(lightFingerprint(listOf(base)), lightFingerprint(listOf(base.copy(isStreaming = false))))
        assertNotEquals(lightFingerprint(listOf(base)), lightFingerprint(listOf(base.copy(isAwaitingModelResponse = true))))
        assertNotEquals(lightFingerprint(listOf(base)), lightFingerprint(listOf(base.copy(error = "boom"))))
        assertNotEquals(lightFingerprint(listOf(base)), lightFingerprint(listOf(base.copy(isQueued = true))))
    }

    @Test fun `tool status and block count flips the fingerprint`() {
        val base = msg(blocks = listOf(toolBlock("t1", ToolBlockStatus.RUNNING)))
        assertNotEquals(lightFingerprint(listOf(base)), lightFingerprint(listOf(base.copy(toolBlocks = listOf(toolBlock("t1", ToolBlockStatus.SUCCESS))))))
        assertNotEquals(lightFingerprint(listOf(base)), lightFingerprint(listOf(base.copy(toolBlocks = listOf(toolBlock("t1", ToolBlockStatus.RUNNING), toolBlock("t2", ToolBlockStatus.PENDING))))))
        assertNotEquals(lightFingerprint(listOf(base)), lightFingerprint(listOf(base.copy(toolBlocks = listOf(toolBlock("t1", ToolBlockStatus.RUNNING, title = "renamed"))))))
    }

    @Test fun `message id order is part of the fingerprint`() {
        val a = msg(id = "a1", content = "x")
        val b = msg(id = "a2", content = "x")
        assertFalse(lightFingerprint(listOf(a, b)) == lightFingerprint(listOf(b, a)))
        assertTrue(lightFingerprint(listOf(a, b)).size == 2)
    }
}