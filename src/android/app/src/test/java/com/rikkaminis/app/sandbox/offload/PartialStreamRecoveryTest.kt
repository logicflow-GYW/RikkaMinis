package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the pure halves of partial-stream recovery
 * (backlog §27b): the staging-dir decision table and the visible-text
 * delta join. The Android-side [PartialStreamRecovery.recover] consumes
 * these verbatim; its DB-write path is exercised by CI's full suite +
 * on-device verification.
 */
class PartialStreamRecoveryTest {

    private fun decide(
        streamLen: Long = 100L,
        terminal: Boolean = false,
        cancel: Boolean = false,
        recovered: Boolean = false,
        meta: Boolean = true,
        mtimeAgeMs: Long = 60_000L,
    ) = PartialStreamRecoveryPolicy.decide(
        streamLen = streamLen,
        terminalPresent = terminal,
        cancelPresent = cancel,
        recoveredPresent = recovered,
        hasSessionMeta = meta,
        mtimeAgeMs = mtimeAgeMs,
    )

    @Test
    fun killedRun_withMeta_recovers() {
        assertEquals(PartialStreamRecoveryPolicy.Decision.RECOVER, decide())
    }

    @Test
    fun terminalRun_skipped() {
        assertEquals(PartialStreamRecoveryPolicy.Decision.SKIP_TERMINAL, decide(terminal = true))
    }

    @Test
    fun cancelledRun_skipped() {
        assertEquals(PartialStreamRecoveryPolicy.Decision.SKIP_CANCELLED, decide(cancel = true))
    }

    @Test
    fun emptyStream_skipped() {
        assertEquals(PartialStreamRecoveryPolicy.Decision.SKIP_EMPTY, decide(streamLen = 0L))
    }

    @Test
    fun missingMeta_skipped() {
        assertEquals(PartialStreamRecoveryPolicy.Decision.SKIP_NO_META, decide(meta = false))
    }

    @Test
    fun recentlyTouchedDir_skipped() {
        assertEquals(PartialStreamRecoveryPolicy.Decision.SKIP_ACTIVE, decide(mtimeAgeMs = 1_000L))
    }

    @Test
    fun graceBoundary_exactlyAtGrace_recovers() {
        assertEquals(
            PartialStreamRecoveryPolicy.Decision.RECOVER,
            decide(mtimeAgeMs = PartialStreamRecoveryPolicy.RECENT_WRITE_GRACE_MS),
        )
    }

    @Test
    fun recoveredBarrier_wins_overEverything() {
        assertEquals(
            PartialStreamRecoveryPolicy.Decision.SKIP_RECOVERED,
            decide(recovered = true, terminal = false, streamLen = 500L),
        )
    }

    @Test
    fun joinRecoveredText_concatenatesTextDeltas() {
        val lines = listOf(
            """{"t":"started"}""",
            """{"t":"text","v":"Hel"}""",
            """{"t":"text","v":"lo wo"}""",
            """{"t":"text","v":"rld"}""",
        )
        assertEquals("Hello world", joinRecoveredText(lines))
    }

    @Test
    fun joinRecoveredText_ignoresThinkingAndUsage() {
        val lines = listOf(
            """{"t":"td","v":"thinking..."}""",
            """{"t":"rc","v":"reasoning"}""",
            """{"t":"text","v":"answer"}""",
            """{"t":"usage","in":1,"out":2,"cache_creation":0,"cache_read":0,"ctx":0}""",
            """{"t":"tu_start","id":"t1","name":"shell"}""",
        )
        assertEquals("answer", joinRecoveredText(lines))
    }

    @Test
    fun joinRecoveredText_blankAndUnparseableLines_skipped() {
        val lines = listOf(
            "",
            "   ",
            "not json at all",
            """{"t":"text","v":"ok"}""",
        )
        assertEquals("ok", joinRecoveredText(lines))
    }

    @Test
    fun joinRecoveredText_emptyStream_emptyResult() {
        assertEquals("", joinRecoveredText(emptyList()))
    }

    @Test
    fun buildRecoveredPartsJson_textPlusReminder() {
        val json = buildRecoveredPartsJson("partial")
        assertTrue("starts with array", json.startsWith("["))
        assertTrue("contains escaped text", json.contains("\"value\":\"partial\""))
        assertTrue("contains reminder", json.contains("system-reminder"))
        assertTrue("two parts", json.split("\"type\":\"text\"").size - 1 == 2)
    }
}
