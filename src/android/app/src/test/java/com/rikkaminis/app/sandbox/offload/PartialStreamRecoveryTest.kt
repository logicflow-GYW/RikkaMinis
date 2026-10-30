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
        cancelDeath: Boolean = false,
        recovered: Boolean = false,
        meta: Boolean = true,
        mtimeAgeMs: Long = 60_000L,
    ) = PartialStreamRecoveryPolicy.decide(
        streamLen = streamLen,
        terminalPresent = terminal,
        cancelPresent = cancel,
        cancelDeathPresent = cancelDeath,
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
    fun deathCancelledRun_withContent_recovers() {
        // [LOOP-1030 R2 M2] `cancel` + `cancel.death` = the client's death
        // cleanup (worker died mid-stream with hadChunks=true → no retry, no
        // fallback, no appendMessage). The stream.jsonl content is real model
        // output the user never saw finish — it must be recovered, not
        // skipped as a user discard.
        assertEquals(
            PartialStreamRecoveryPolicy.Decision.RECOVER,
            decide(cancel = true, cancelDeath = true),
        )
    }

    @Test
    fun deathCancelledRun_stillRespectsGrace() {
        // A frozen worker that thaws may append more before its own cancel
        // takes effect — the death marker must not bypass the write grace.
        assertEquals(
            PartialStreamRecoveryPolicy.Decision.SKIP_ACTIVE,
            decide(cancel = true, cancelDeath = true, mtimeAgeMs = 1_000L),
        )
    }

    @Test
    fun deathCancelledRun_emptyStream_skipped() {
        // Nothing to append: a death with zero visible text is reclaimed like
        // any empty dir (SKIP_EMPTY deletes with the beat-alive guard).
        assertEquals(
            PartialStreamRecoveryPolicy.Decision.SKIP_EMPTY,
            decide(streamLen = 0L, cancel = true, cancelDeath = true),
        )
    }

    @Test
    fun deathCancelledRun_noMeta_skipped() {
        // Unattributable content cannot be appended to any session.
        assertEquals(
            PartialStreamRecoveryPolicy.Decision.SKIP_NO_META,
            decide(meta = false, cancel = true, cancelDeath = true),
        )
    }

    @Test
    fun deathMarkerWithoutCancel_doesNotChangeAnything() {
        // The marker is only meaningful next to the wire cancel; a stray
        // marker alone must not turn a plain death into a discard or vice
        // versa (the plain ladder still applies).
        assertEquals(
            PartialStreamRecoveryPolicy.Decision.RECOVER,
            decide(cancel = false, cancelDeath = true),
        )
        assertEquals(
            PartialStreamRecoveryPolicy.Decision.SKIP_TERMINAL,
            decide(terminal = true, cancel = false, cancelDeath = true),
        )
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
    fun activeEmptyStream_kept_notDeleted() {
        // [fix/stream-recovery-grace-race] A live worker's NON-streaming run
        // (title-gen / QuickTest / compaction dispatch) has no stream.jsonl —
        // the grace must outrank the empty check or recovery deletes a dir
        // being written to.
        assertEquals(
            PartialStreamRecoveryPolicy.Decision.SKIP_ACTIVE,
            decide(streamLen = 0L, mtimeAgeMs = 1_000L),
        )
    }

    @Test
    fun activeNoMeta_kept_notDeleted() {
        // Same family: meta is written by the chat-stream path only; a live
        // non-streaming run has none. Empty/meta-less proves nothing while
        // the beat is fresh.
        assertEquals(
            PartialStreamRecoveryPolicy.Decision.SKIP_ACTIVE,
            decide(meta = false, mtimeAgeMs = 1_000L),
        )
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
