package com.openminis.app.sandbox.offload

import com.openminis.app.data.model.LLMStreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/provider-exec-concurrency] Queue-frame codec round-trip tests (A:
 * queue observability). The worker writes {"t":"queue","w":N} lines into
 * stream.jsonl while waiting for an execution slot; the client must decode
 * them back into QueueStatus chunks, and the frame must NOT be terminal.
 */
class QueueFrameCodecTest {

    @Test
    fun `queue frame round trips`() {
        val encoded = ChatStreamJsonl.encode(LLMStreamChunk.QueueStatus(3))
        val decoded = ChatStreamJsonl.decode(encoded)
        assertEquals(LLMStreamChunk.QueueStatus(3), decoded)
    }

    @Test
    fun `queue frame is not terminal`() {
        val encoded = ChatStreamJsonl.encode(LLMStreamChunk.QueueStatus(3))
        assertFalse(ChatStreamJsonl.isTerminal(encoded))
        assertFalse(ChatStreamJsonl.isDone(encoded))
        assertFalse(ChatStreamJsonl.isError(encoded))
    }

    @Test
    fun `queue frame zero decodes`() {
        val encoded = ChatStreamJsonl.encode(LLMStreamChunk.QueueStatus(0))
        assertEquals(LLMStreamChunk.QueueStatus(0), ChatStreamJsonl.decode(encoded))
    }

    @Test
    fun `decodeLine carries queue chunk without correlation`() {
        val encoded = ChatStreamJsonl.encode(LLMStreamChunk.QueueStatus(2))
        val line = ChatStreamJsonl.decodeLine(encoded)
        assertEquals(LLMStreamChunk.QueueStatus(2), line.chunk)
        assertEquals(null, line.runId)
        assertEquals(-1, line.seq)
    }

    @Test
    fun `correlation fields still work alongside queue frames`() {
        val encoded = ChatStreamJsonl.encodeWithCorrelation(
            LLMStreamChunk.QueueStatus(5),
            runId = "abc",
            seq = 7,
        )
        val line = ChatStreamJsonl.decodeLine(encoded)
        assertEquals(LLMStreamChunk.QueueStatus(5), line.chunk)
        assertEquals("abc", line.runId)
        assertEquals(7, line.seq)
    }
}
