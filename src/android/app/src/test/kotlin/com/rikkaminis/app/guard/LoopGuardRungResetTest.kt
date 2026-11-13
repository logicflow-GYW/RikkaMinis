package com.rikkaminis.app.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R8-1114 A-F05] The Repeat ladder counts a SLIDING WINDOW, so its count can
 * fall from 6 back to 0 without any streak reset. Two consequences the old
 * monotonic, per-SIGNAL watermark got wrong:
 *
 *  1. once a rung had fired it never fired again for the rest of the session —
 *     the model got no reminder and was refused outright the next time the
 *     count reached 12 ("suddenly blocked");
 *  2. the watermark was shared by every signature, so a second target's first
 *     rung was swallowed by the first target's rung.
 *
 * The watermark is now per signature and follows the count down. Streak signals
 * keep their monotonic rung, and the stop rung still stops.
 */
class LoopGuardRungResetTest {

    private fun signature(target: String) = "shell_execute(command=android-a11y-cli tap $target)"

    /** Fill the 16-slot window with unrelated signatures so [sig]'s count drops to 0. */
    private fun evictWindow(guard: LoopGuard, from: Int = 0) {
        repeat(LoopGuard.COUNT_WINDOW) { i ->
            guard.record("filler-${from + i}", ok = true, outcome = Outcome.Effective)
        }
    }

    @Test
    fun aRungThatSlidesOutIsReAnnouncedWhenItComesBack() {
        val guard = LoopGuard()
        val sig = signature("100 200")

        var atSix: Pair<Signal, Int>? = null
        for (i in 1..6) {
            val hit = guard.record(sig, ok = true, outcome = Outcome.Effective)
            if (i == 6) atSix = hit
        }
        assertEquals("the 6th repeat must remind", 6, atSix?.second)

        // The signature slides out of the window: the count is 0 again.
        evictWindow(guard)
        assertNull("count 1 after eviction is below the first rung", guard.record(sig, ok = true, outcome = Outcome.Effective))
        assertNull("count 2 after eviction is below the first rung", guard.record(sig, ok = true, outcome = Outcome.Effective))
        val third = guard.record(sig, ok = true, outcome = Outcome.Effective)
        assertNotNull("the 3rd repeat after eviction must remind again", third)
        assertEquals(Signal.Repeat, third!!.first)
        assertEquals(3, third.second)
    }

    @Test
    fun aPartialFallReAnnouncesTheRungWhenItClimbsBack() {
        val guard = LoopGuard()
        val sig = signature("100 200")

        fun windowCount(): Int =
            guard.storedSignatures().takeLast(LoopGuard.COUNT_WINDOW).count { it == sig }

        // Climb to rung 6 with fillers in between, so the count falls gradually
        // instead of dropping to zero.
        var atSix: Pair<Signal, Int>? = null
        for (i in 1..6) {
            val hit = guard.record(sig, ok = true, outcome = Outcome.Effective)
            if (i == 6) atSix = hit
            guard.record("filler-$i", ok = true, outcome = Outcome.Effective)
        }
        assertEquals("the 6th repeat must remind", 6, atSix?.second)

        // Push the window until the count is back at the FIRST rung. The
        // signature never leaves the window, so this is the "watermark must
        // follow the count DOWN" branch (not the eviction branch).
        var pushed = 0
        while (windowCount() > Signal.Repeat.ladder.first() && pushed < 40) {
            guard.record("push-${pushed++}", ok = true, outcome = Outcome.Effective)
        }
        assertEquals("the count must have fallen to the first rung", 3, windowCount())

        // Climb back: a monotonic watermark (6) would stay silent until the hard
        // stop at 12 — the model would be refused with no reminder in between.
        // The re-announcement must therefore land on rung 6 itself.
        var reAnnounced: Pair<Signal, Int>? = null
        var climbed = 0
        while (reAnnounced == null && climbed < 20) {
            val hit = guard.record(sig, ok = true, outcome = Outcome.Effective)
            if (hit != null) reAnnounced = hit
            climbed++
        }
        assertNotNull("a re-climb after a partial fall must remind again", reAnnounced)
        assertEquals(Signal.Repeat, reAnnounced!!.first)
        assertEquals(
            "the reminder must land on rung 6, not only at the hard stop",
            Signal.Repeat.ladder[1],
            reAnnounced.second,
        )
    }

    @Test
    fun aSecondTargetsFirstRungIsNotSwallowedByTheFirstTarget() {
        val guard = LoopGuard()
        val a = signature("100 200")
        val b = signature("300 400")

        repeat(6) { guard.record(a, ok = true, outcome = Outcome.Effective) }

        // Target B has never repeated before: its 3rd call must remind, not be
        // silenced by A's rung-6 watermark (the old per-signal map's defect).
        guard.record(b, ok = true, outcome = Outcome.Effective)
        guard.record(b, ok = true, outcome = Outcome.Effective)
        val third = guard.record(b, ok = true, outcome = Outcome.Effective)
        assertNotNull("a different target's 3rd repeat must remind on its own", third)
        assertEquals(Signal.Repeat, third!!.first)
        assertEquals(3, third.second)
    }

    @Test
    fun theStopRungStillStops() {
        val guard = LoopGuard()
        val sig = signature("100 200")
        var stopped: Signal? = null
        for (i in 1..Signal.Repeat.stopAt) {
            if (i == Signal.Repeat.stopAt) {
                // check() refuses the would-be stop call before it runs.
                stopped = guard.check(sig)
                break
            }
            guard.record(sig, ok = true, outcome = Outcome.Effective)
        }
        assertEquals(Signal.Repeat, stopped)
        assertEquals(Signal.Repeat, guard.stopped)
    }

    @Test
    fun aNewTurnClearsTheWatermarks() {
        val guard = LoopGuard()
        val sig = signature("100 200")
        repeat(3) { guard.record(sig, ok = true, outcome = Outcome.Effective) }
        guard.reset()
        assertNull("count 1 after reset is below the first rung", guard.record(sig, ok = true, outcome = Outcome.Effective))
        assertNull("count 2 after reset is below the first rung", guard.record(sig, ok = true, outcome = Outcome.Effective))
        val third = guard.record(sig, ok = true, outcome = Outcome.Effective)
        assertEquals("a new turn must let the 3rd repeat remind again", 3, third?.second)
    }

    @Test
    fun streakSignalsKeepTheirMonotonicRung() {
        val guard = LoopGuard()
        // Failure ladder: 2, 4, 8. A success resets the streak, so the next
        // climb speaks again — the existing contract, unchanged by this fix.
        assertNull("1 failure is below the first rung", guard.record(null, ok = false, outcome = Outcome.None))
        assertEquals(2, guard.record(null, ok = false, outcome = Outcome.None)?.second)
        guard.record(null, ok = true, outcome = Outcome.Effective)
        assertNull("the success reset the streak", guard.record(null, ok = false, outcome = Outcome.None))
        assertEquals(2, guard.record(null, ok = false, outcome = Outcome.None)?.second)
    }
}
