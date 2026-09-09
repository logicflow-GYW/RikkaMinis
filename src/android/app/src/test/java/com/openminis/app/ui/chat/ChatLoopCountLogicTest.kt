package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Token Usage sheet's "Agent Loop → Total Loops" counter against the
 * exact reported symptom: a run that executes N tool calls must make the
 * counter grow DURING the run, not jump at the end.
 */
class ChatLoopCountLogicTest {

    private fun tool(id: String) = AssistantBlock(id = id, kind = "tool_use")
    private fun text(id: String) = AssistantBlock(id = id, kind = "text", content = "hi")
    private fun info(id: String) = AssistantBlock(id = id, kind = "info")

    /** The regression: canonical frozen (empty streaming placeholder) while the
     *  live delta accumulates tool blocks → count must follow the delta. */
    @Test
    fun liveDeltaDrivesCountWhileCanonicalIsFrozen() {
        val placeholder = ChatMessage(content = "", id = "a1", role = "assistant", isStreaming = true)
        val canonical = listOf(ChatMessage(content = "", id = "u1", role = "user"), placeholder)

        for (n in 1..5) {
            val delta = StreamingDelta(
                content = "",
                toolBlocks = (1..n).map { tool("t$it") },
                isAwaitingModelResponse = true,
                epoch = 7L,
            )
            val count = countAgentLoops(canonical, mapOf("a1" to delta), currentEpoch = 7L)
            assertEquals("after $n tool calls the live count must be $n", n, count)
        }
    }

    /** Continuity: at stream end the delta is drained into the canonical
     *  message — the number must not jump when that happens. */
    @Test
    fun drainIntoCanonicalKeepsTheSameNumber() {
        val blocks = (1..4).map { tool("t$it") }
        val duringRun = listOf(
            ChatMessage(content = "", id = "u1", role = "user"),
            ChatMessage(content = "", id = "a1", role = "assistant", isStreaming = true),
        )
        val live = countAgentLoops(
            duringRun,
            mapOf("a1" to StreamingDelta("", blocks, true, epoch = 3L)),
            currentEpoch = 3L,
        )
        val afterDrain = countAgentLoops(
            listOf(
                ChatMessage(content = "", id = "u1", role = "user"),
                ChatMessage(content = "", id = "a1", role = "assistant", toolBlocks = blocks),
            ),
            emptyMap(),
            currentEpoch = 3L,
        )
        assertEquals(4, live)
        assertEquals(4, afterDrain)
    }

    /** A stale delta from a cancelled turn must never inflate the count. */
    @Test
    fun staleEpochDeltaIsIgnored() {
        val canonical = listOf(
            ChatMessage(content = "", id = "a1", role = "assistant", toolBlocks = listOf(tool("old1"))),
        )
        val stale = StreamingDelta("", (1..9).map { tool("s$it") }, true, epoch = 1L)
        assertEquals(1, countAgentLoops(canonical, mapOf("a1" to stale), currentEpoch = 2L))
    }

    /** text / info blocks are not loop iterations (shipped semantics). */
    @Test
    fun textAndInfoBlocksAreNotCounted() {
        val canonical = listOf(
            ChatMessage(
                content = "",
                id = "a1",
                role = "assistant",
                toolBlocks = listOf(text("x"), info("y"), tool("z")),
            ),
        )
        assertEquals(1, countAgentLoops(canonical, emptyMap(), currentEpoch = 0L))
    }

    /** max(toolCalls, assistantCount) — the iOS parity floor: a tool-free
     *  reply still counts as one assistant message. */
    @Test
    fun assistantMessageFloorIsPreserved() {
        val canonical = listOf(
            ChatMessage(id = "a1", role = "assistant", content = "done"),
            ChatMessage(id = "a2", role = "assistant", content = "again"),
        )
        assertEquals(2, countAgentLoops(canonical, emptyMap(), currentEpoch = 0L))
    }

    /** Prior completed turns stay counted while a new run streams. */
    @Test
    fun priorTurnsPlusLiveRunAccumulate() {
        val canonical = listOf(
            ChatMessage(content = "", id = "a0", role = "assistant", toolBlocks = (1..3).map { tool("p$it") }),
            ChatMessage(content = "", id = "a1", role = "assistant", isStreaming = true),
        )
        val delta = StreamingDelta("", (1..2).map { tool("l$it") }, true, epoch = 5L)
        assertEquals(5, countAgentLoops(canonical, mapOf("a1" to delta), currentEpoch = 5L))
    }

    /** User messages never contribute to the count. */
    @Test
    fun userMessagesAreIgnored() {
        val canonical = listOf(ChatMessage(id = "u1", role = "user", content = "hello"))
        assertEquals(0, countAgentLoops(canonical, emptyMap(), currentEpoch = 0L))
    }
}
