package com.rikkaminis.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [fix/r5-d3] The hang counter has to settle itself.
 *
 * Production symptom this pins: `markHealthyTick` had exactly one caller —
 * ChatScreen's `LaunchedEffect(sessionId)`, a single `delay(10_000)` after
 * mount — and `resetHangCount` has no production caller at all. A hang inside
 * the first 10 s therefore landed ~7 s before that tick and tripped
 * markHealthyTick's own RESET_AFTER_QUIET_MS (10 s) early return; a hang after
 * 10 s had no tick at all. Either way `KEY_HANG_COUNT` stayed > 0 for the rest
 * of the process, so the next launch's `LaunchCycleBeacon.classifyPrevious`
 * read the leftover count as proof the process died at a stall
 * (`crash_or_stall` banner, forced home after four accumulated cycles).
 *
 * The fix is a watchdog-side self-heal, so two things are pinned here:
 *  · the wire — watchLoop's healthy branch calls the heal, and the heal is the
 *    only production call site of markHealthyTick (shape probe on the real
 *    source, same pattern as `AppInitSkipGuardWiringTest`);
 *  · the verdict — with the count back to 0 a recovered stall no longer reads
 *    as an abnormal exit, while an unrecovered one still does (pure function,
 *    literal expectations, no production constant supplies an expected value).
 *
 * Each shape assertion has a negative control that mutates a copy of the real
 * source; without them a vanished call would read as a green test that checks
 * nothing.
 */
class HangDetectorSelfHealTest {

    // ── the guarded text, written out in full ───────────────────────────────

    private val hangSource = "src/main/java/com/rikkaminis/app/diagnostics/HangDetector.kt"

    private val healCall = "healQuietHang()"
    private val healDef = "private fun healQuietHang()"
    private val healthyBranch = "if (since < HANG_THRESHOLD_MS) {"
    private val hangBranch = "if (phase == BackgroundFreezePolicy.Phase.HANG) {"
    private val resetCall = "markHealthyTick()"
    private val flagSet = "hangCountedThisProcess.set(true)"
    private val recordHangDef = "private fun recordHang(durationMs: Long)"
    private val counterLock = "synchronized(hangCounterLock) {"

    private fun source(): String = File(hangSource).readText()

    private fun occurrences(body: String, needle: String): Int {
        var n = 0
        var from = 0
        while (true) {
            val i = body.indexOf(needle, from)
            if (i < 0) return n
            n++
            from = i + needle.length
        }
    }

    /** The CALL site of healQuietHang(), i.e. the occurrence not preceded by `fun `. */
    private fun healCallIndex(body: String): Int {
        var from = 0
        while (true) {
            val i = body.indexOf(healCall, from)
            if (i < 0) return -1
            if (!body.substring(0, i).trimEnd().endsWith("private fun")) return i
            from = i + healCall.length
        }
    }

    /** Index of the `}` that closes the block opened at [openAt]. */
    private fun blockEnd(body: String, openAt: Int): Int {
        var depth = 0
        for (i in openAt until body.length) {
            when (body[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    // ── the three wire checks, as functions so they can be reused as controls ──

    /**
     * The heal must be called from watchLoop's healthy branch — the branch that
     * means "a heartbeat landed, the main thread is responsive right now". Any
     * other placement (the hang branch, the freeze branch, or outside the loop)
     * either never runs after recovery or runs while the thread is stuck.
     */
    private fun assertHealIsWired(body: String) {
        val callAt = healCallIndex(body)
        assertTrue("watchLoop must call healQuietHang():\n$body", callAt >= 0)
        val branchAt = body.indexOf(healthyBranch)
        val hangAt = body.indexOf(hangBranch)
        assertTrue("the healthy heartbeat branch marker is gone:\n$body", branchAt >= 0)
        assertTrue("the counted-hang branch marker is gone:\n$body", hangAt >= 0)
        assertTrue(
            "heal must sit inside the healthy branch, before the hang-phase branch " +
                "(heal@$callAt, healthy@$branchAt, hang@$hangAt)",
            callAt > branchAt && callAt < hangAt,
        )
    }

    /**
     * The heal is the only production call site of markHealthyTick in this
     * file, and that call is inside the heal itself. ChatScreen's tick lives
     * outside this file and is expected; a second call in here would mean the
     * quiet-period rule got a second owner.
     */
    private fun assertHealOwnsTheReset(body: String) {
        assertEquals(
            "markHealthyTick must have exactly one call site in HangDetector (definition + heal's call):\n$body",
            2,
            occurrences(body, resetCall),
        )
        val healAt = body.indexOf(healDef)
        assertTrue("healQuietHang definition is gone:\n$body", healAt >= 0)
        val healEnd = blockEnd(body, body.indexOf('{', healAt))
        assertTrue("healQuietHang has no closing brace", healEnd > healAt)
        val resetAt = body.indexOf(resetCall, healAt)
        assertTrue(
            "the reset must be called from inside healQuietHang (reset@$resetAt, heal@$healAt..$healEnd)",
            resetAt in (healAt + 1) until healEnd,
        )
    }

    /**
     * The process-local flag must be set exactly once, inside recordHang, inside
     * the same critical section as the counter write — the flag and the count
     * have to flip together or the heal can act on a hang it never saw counted.
     */
    private fun assertFlagSetUnderTheCounterLock(body: String) {
        assertEquals(
            "the process-local flag must be set exactly once:\n$body",
            1,
            occurrences(body, flagSet),
        )
        val recordAt = body.indexOf(recordHangDef)
        assertTrue("recordHang definition is gone:\n$body", recordAt >= 0)
        val setAt = body.indexOf(flagSet)
        assertTrue("the flag must be set inside recordHang (record@$recordAt, set@$setAt)", setAt > recordAt)
        val lockAt = body.indexOf(counterLock, recordAt)
        assertTrue("recordHang must still take the counter lock (lock@$lockAt)", lockAt > recordAt)
        // Walk the braces between the `synchronized(…) {` and the flag write:
        // a positive depth means the write is still on the locked path.
        val span = body.substring(lockAt, setAt)
            .lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        var depth = 0
        for (c in span) {
            when (c) {
                '{' -> depth++
                '}' -> depth--
            }
        }
        assertTrue(
            "the flag write must stay inside the counter critical section (depth=$depth):\n$span",
            depth >= 1,
        )
    }

    // ── F-01: the quiet clock restarts when the episode ends ────────────────

    private val postRecoverySample = "writeStallSample(\"post-recovery\""
    private val recoveryStampCall = "stampHangRecovery(now)"
    private val recoveryStampDef = "private fun stampHangRecovery(nowMs: Long)"
    private val wasHangOpen = "if (wasHang) {"
    private val episodeEndedMarker = "hang episode ENDED"

    /**
     * [F-01] A counted hang episode must restart the quiet-period clock when it
     * CLOSES, not when it was first counted.
     *
     * `recordHang` writes `last_hang_at_ms` once, at the threshold crossing, and
     * nothing refreshed it while the episode ran or when it ended. So the quiet
     * window `markHealthyTick` demands was measured from the hang's START: the
     * longer the hang, the more "quiet time" it had already banked, and a
     * ≥ 13 s hang reset its own counter — and cleared the render breaker — on
     * the very first healthy tick after the main thread came back. The hang's
     * own duration was being counted as the quiet period it was supposed to be
     * waiting out.
     *
     * The probe checks the VALUE FLOW, not a token's presence: the stamp call
     * has to sit inside the `wasHang` close-out, after the post-recovery sample
     * and before the "episode ENDED" line — i.e. on the path that only runs when
     * a counted hang actually ended, and before the heal that consumes the
     * clock. The helper it calls has to write `KEY_LAST_HANG_AT` under the
     * counter lock and must not touch the count.
     */
    private fun assertRecoveryStampsTheQuietClock(body: String) {
        val sampleAt = body.indexOf(postRecoverySample)
        assertTrue("the post-recovery sample marker is gone:\n$body", sampleAt >= 0)
        val wasHangAt = body.indexOf(wasHangOpen)
        assertTrue("the wasHang close-out is gone:\n$body", wasHangAt >= 0)
        val endedAt = body.indexOf(episodeEndedMarker, sampleAt)
        assertTrue(
            "the episode-end line must follow the post-recovery sample:\n$body",
            endedAt > sampleAt,
        )
        val stampAt = body.indexOf(recoveryStampCall, sampleAt)
        assertTrue(
            "the recovery stamp must be written inside the wasHang close-out — after the " +
                "post-recovery sample and before the episode-end line " +
                "(sample@$sampleAt, wasHang@$wasHangAt, stamp@$stampAt, ended@$endedAt)",
            stampAt > wasHangAt && stampAt > sampleAt && stampAt < endedAt,
        )

        val defAt = body.indexOf(recoveryStampDef)
        assertTrue("the recovery-stamp helper is gone:\n$body", defAt >= 0)
        val helperEnd = blockEnd(body, body.indexOf('{', defAt))
        assertTrue("the recovery-stamp helper has no closing brace", helperEnd > defAt)
        val helper = body.substring(defAt, helperEnd)
        val lockAt = helper.indexOf(counterLock)
        val writeAt = helper.indexOf("putLong(KEY_LAST_HANG_AT")
        assertTrue("the stamp must take the counter lock (lock@$lockAt):\n$helper", lockAt >= 0)
        assertTrue(
            "the stamp must write the hang timestamp inside that lock " +
                "(lock@$lockAt, write@$writeAt)",
            writeAt > lockAt,
        )
        assertFalse(
            "the stamp must not touch the count — the reset still has to earn the quiet window:\n$helper",
            helper.contains("KEY_HANG_COUNT"),
        )
    }

    // ── the wire ─────────────────────────────────────────────────────────────

    @Test
    fun `watchdog self-heals from its healthy heartbeat branch`() {
        assertHealIsWired(source())
    }

    @Test
    fun `the heal is the only in-file caller of the quiet-period reset`() {
        assertHealOwnsTheReset(source())
    }

    @Test
    fun `the process-local flag flips together with the counter under the lock`() {
        assertFlagSetUnderTheCounterLock(source())
    }

    @Test
    fun `the quiet clock restarts when a counted hang episode ends`() {
        assertRecoveryStampsTheQuietClock(source())
    }

    // ── the verdict, with the count going 1 → 0 ──────────────────────────────

    @Test
    fun `a self-healed stall no longer reads as an abnormal exit`() {
        // The 10 s quiet period elapsed: count is back to 0, the stall file is
        // still on disk. That is a recovered hang, not a death at a stall —
        // the caller then falls through to silent_kill with the uptime detail.
        assertNull(
            LaunchCycleBeacon.crashVerdict(
                crashArtefacts = emptyList(),
                stallArtefacts = listOf("stall-2026-10-29.log"),
                unrecoveredHangCount = 0,
            ),
        )
    }

    @Test
    fun `an unrecovered stall still reads as crash_or_stall`() {
        assertEquals(
            "crash_or_stall (stall-2026-10-29.log)",
            LaunchCycleBeacon.crashVerdict(
                crashArtefacts = emptyList(),
                stallArtefacts = listOf("stall-2026-10-29.log"),
                unrecoveredHangCount = 1,
            ),
        )
    }

    @Test
    fun `a real crash artefact stays abnormal even with a settled counter`() {
        assertEquals(
            "crash_or_stall (crash-2026-10-29.log)",
            LaunchCycleBeacon.crashVerdict(
                crashArtefacts = listOf("crash-2026-10-29.log"),
                stallArtefacts = emptyList(),
                unrecoveredHangCount = 0,
            ),
        )
    }

    // ── negative controls ────────────────────────────────────────────────────

    /**
     * Proves these probes can actually fail. Each arm edits one line out of a
     * copy of the real source and requires the matching assertion to reject it;
     * an arm that stayed green would mean the test no longer checks anything.
     */
    @Test
    fun `the wire checks reject a source with the self-heal removed`() {
        val real = source()

        val noHealCall = real.replaceFirst(
            "\n                healQuietHang()\n                continue",
            "\n                continue",
        )
        assertTrue(
            "the mutation must really remove the call — otherwise this control proves nothing",
            healCallIndex(noHealCall) < 0,
        )

        val noResetInHeal = real.replaceFirst(
            "\n        markHealthyTick()\n        if (currentHangCount(ctx) == 0)",
            "\n        if (currentHangCount(ctx) == 0)",
        )
        assertTrue(
            "the mutation must really remove the reset — otherwise this control proves nothing",
            occurrences(noResetInHeal, resetCall) == 1,
        )

        val noFlag = real.replaceFirst(
            "\n            hangCountedThisProcess.set(true)",
            "",
        )
        assertTrue(
            "the mutation must really remove the flag write",
            occurrences(noFlag, flagSet) == 0,
        )

        val rejected = mutableListOf<String>()
        try {
            assertHealIsWired(noHealCall)
        } catch (e: AssertionError) {
            rejected += "wired"
        }
        try {
            assertHealOwnsTheReset(noResetInHeal)
        } catch (e: AssertionError) {
            rejected += "reset"
        }
        try {
            assertFlagSetUnderTheCounterLock(noFlag)
        } catch (e: AssertionError) {
            rejected += "flag"
        }
        assertEquals(
            "each mutation must be rejected by its own check",
            "wired, reset, flag",
            rejected.joinToString(", "),
        )
    }

    /**
     * Proves the F-01 probe can actually fail. Arm 1 is the pre-fix shape (no
     * stamp at all); arm 2 moves the stamp after the heal, where the clock
     * restarts one quiet window too late and the first post-recovery tick still
     * resets; arm 3 makes the helper zero the counter instead of only restarting
     * the clock. Each arm must be rejected by the probe, and each arm's edit
     * must really change the text — an arm that silently did nothing would read
     * as a control that passes.
     */
    @Test
    fun `the recovery-stamp probe rejects a source without a working recovery stamp`() {
        val real = source()

        val noStamp = real.replaceFirst(
            "\n                        stampHangRecovery(now)\n",
            "\n",
        )
        assertTrue(
            "the mutation must really remove the call — otherwise this control proves nothing",
            noStamp != real && !noStamp.contains(recoveryStampCall),
        )

        val lateStamp = real.replaceFirst(
            "\n                        stampHangRecovery(now)\n",
            "\n",
        ).replaceFirst(
            "\n                healQuietHang()\n",
            "\n                stampHangRecovery(now)\n                healQuietHang()\n",
        )
        assertTrue("the mutation must really move the call", lateStamp != real)

        val stampsCount = real.replace(
            "prefs.edit().putLong(KEY_LAST_HANG_AT, nowMs).apply()",
            "prefs.edit().putInt(KEY_HANG_COUNT, 0).putLong(KEY_LAST_HANG_AT, nowMs).apply()",
        )
        assertTrue("the mutation must really touch the count", stampsCount != real)

        val rejected = mutableListOf<String>()
        runCatching { assertRecoveryStampsTheQuietClock(noStamp) }
            .onFailure { rejected += "no-stamp" }
        runCatching { assertRecoveryStampsTheQuietClock(lateStamp) }
            .onFailure { rejected += "late-stamp" }
        runCatching { assertRecoveryStampsTheQuietClock(stampsCount) }
            .onFailure { rejected += "stamps-count" }
        assertEquals(
            "each mutation must be rejected by its own probe",
            "no-stamp, late-stamp, stamps-count",
            rejected.joinToString(", "),
        )
    }
}
