package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [mergeStreamingOverlay]'s epoch filter
 * ([T-android-thinking-indicator-linger] fix: cancelled-then-resent turns
 * must not resurrect a stale "thinking" row via a late delta).
 *
 * The epoch guard makes the merge path immune to out-of-order deltas: a
 * delta written by a PREVIOUS turn (carried by a trailing-flush coroutine
 * that survives streamJob.cancel) never merges into the current snapshot.
 */
class MergeStreamingOverlayEpochTest {

    private fun msg(id: String, content: String = "canonical") =
        ChatMessage(id = id, role = "assistant", content = content)

    private fun delta(
        id: String,
        content: String = "streamed",
        epoch: Long = 42L,
        isAwaitingModelResponse: Boolean = false,
    ) = StreamingDelta(
        content = content,
        toolBlocks = emptyList(),
        isAwaitingModelResponse = isAwaitingModelResponse,
        epoch = epoch,
    )

    @Test
    fun `same epoch delta merges (normal streaming unchanged)`() {
        val messages = listOf(msg("m1"))
        val merged = mergeStreamingOverlay(messages, mapOf("m1" to delta("m1", epoch = 7L)), currentEpoch = 7L)
        assertEquals("streamed", merged[0].content)
        assertTrue(merged[0].isStreaming)
    }

    @Test
    fun `stale epoch delta is ignored`() {
        val messages = listOf(msg("m1", content = "canonical"))
        // Old turn's trailing-flush delta arrives after epoch moved to 8.
        val merged = mergeStreamingOverlay(messages, mapOf("m1" to delta("m1", epoch = 6L)), currentEpoch = 8L)
        assertEquals("canonical", merged[0].content)
        assertFalse("stale delta must not flip isStreaming", merged[0].isStreaming)
    }

    @Test
    fun `stale delta cannot resurrect thinking row via awaiting flag`() {
        val messages = listOf(msg("m1"))
        val stale = delta("m1", epoch = 3L, isAwaitingModelResponse = true)
        val merged = mergeStreamingOverlay(messages, mapOf("m1" to stale), currentEpoch = 9L)
        assertFalse("stale awaiting delta ignored", merged[0].isAwaitingModelResponse)
    }

    @Test
    fun `mixed map merges only current-epoch entries`() {
        val messages = listOf(msg("m1"), msg("m2"), msg("m3"))
        val streaming = mapOf(
            "m1" to delta("m1", epoch = 4L),
            "m2" to delta("m2", epoch = 5L, content = "fresh"),
            "m3" to delta("m3", epoch = 4L),
        )
        val merged = mergeStreamingOverlay(messages, streaming, currentEpoch = 5L)
        // m1/m3: stale -> canonical unchanged, not streaming
        assertEquals("canonical", merged[0].content)
        assertFalse(merged[0].isStreaming)
        assertEquals("canonical", merged[2].content)
        assertFalse(merged[2].isStreaming)
        // m2: current -> merged
        assertEquals("fresh", merged[1].content)
        assertTrue(merged[1].isStreaming)
    }

    @Test
    fun `empty streaming map short-circuits regardless of epoch`() {
        val messages = listOf(msg("m1"))
        val merged = mergeStreamingOverlay(messages, emptyMap(), currentEpoch = 1L)
        assertEquals(merged, messages) // same list reference (short-circuit)
    }

    @Test
    fun `default currentEpoch 0 merges only epoch-0 deltas`() {
        // Back-compat: callers that don't pass an epoch only merge deltas
        // whose epoch is the default 0 — which is what old-construction
        // deltas (no epoch passed) carry. Epoch-0 is the initial value so
        // pre-epoch code paths keep working verbatim.
        val messages = listOf(msg("m1"))
        val oldStyle = delta("m1", epoch = 0L, content = "legacy")
        val merged = mergeStreamingOverlay(messages, mapOf("m1" to oldStyle))
        assertEquals("legacy", merged[0].content)

        val newStyle = delta("m1", epoch = 3L)
        val merged2 = mergeStreamingOverlay(messages, mapOf("m1" to newStyle))
        assertEquals("canonical", merged2[0].content)
    }
}