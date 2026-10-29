package com.rikkaminis.app.diagnostics

import org.junit.Assert.assertEquals
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
}
