package com.rikkaminis.app.diagnostics

/**
 * [W1118-T6 D9 F-06 / R6 F-03] Pure decisions behind [HangDetector]'s
 * self-heal path.
 *
 * The detector keeps its counter in `SharedPreferences` and touches it from two
 * threads: the watchdog thread counts a hang ([HangDetector.recordHang]) and the
 * main thread (or the watchdog's healthy branch) may reset it
 * ([HangDetector.markHealthyTick]). The rules below used to live inline — one
 * as a check-then-act outside the lock, one as a flag test that made a
 * carried-over counter un-healable — so they are extracted here to be pinned by
 * plain JVM tests instead of by source shape.
 */
internal object QuietHealPolicy {

    /**
     * [D9 F-06] May a healthy tick zero the persisted counter?
     *
     * `markHealthyTick` reads `hang_count` / `last_hang_at_ms`, decides, and
     * only then enters [HangDetector]'s `hangCounterLock`. `recordHang` writes
     * the same two keys under that lock. Sequence that used to lose a hang:
     *
     *   1. main thread: count = 1, last hang 20 s ago → passes both outer checks;
     *   2. watchdog: `recordHang` takes the lock, writes count = 2 and
     *      `last_hang_at = now`, trips the render breaker;
     *   3. main thread: enters the lock, re-checks only `count == 0` (false),
     *      and zeroes the counter + clears the breaker.
     *
     * The freshly recorded hang is gone 0 ms after it was recorded, and the
     * breaker is torn down while the same hang is still the reason it exists.
     * Re-evaluating the quiet window INSIDE the lock closes it: step 3 now sees
     * a timestamp 0 ms old and returns.
     *
     * A counter with no timestamp (`last_hang_at_ms <= 0`) is a record from an
     * older build; it may be reset — refusing would pin it forever, the exact
     * failure [mayAttemptHeal] fixes from the other end.
     */
    fun mayResetOnHealthyTick(
        hangCount: Int,
        lastHangAtMs: Long,
        nowMs: Long,
        quietMs: Long,
    ): Boolean {
        if (hangCount <= 0) return false
        if (lastHangAtMs <= 0L) return true
        return nowMs - lastHangAtMs >= quietMs
    }

    /**
     * [R6 F-03] May the watchdog attempt a heal on this healthy tick?
     *
     * Two sources put a count on disk: a hang this process counted
     * (`countedThisProcess`) and a count inherited from the previous process
     * (`carriedOverCount`, seeded in [HangDetector.start]).
     *
     * The original gate — "only heal counters this process owns" — protected
     * the cold-start breakers (they read the inherited count right after
     * `start`) but had no exit: `healQuietHang` returned unconditionally while
     * the flag was false, so an inherited `count >= 3` was never cleared by
     * anything. The user stayed force-landed on Home (the launch resolver's
     * "previous launches kept hanging" breaker) and streaming markdown stayed
     * degraded for every launch until a NEW hang happened to be counted in the
     * same process — i.e. the breaker could only be cleared by hanging again.
     *
     * The fix keeps the protection for the window that needs it and drops it
     * afterwards: an inherited count may be healed once this process has been
     * up for a full quiet window. That is a strictly later moment than every
     * cold-start read (`MinisApp.onCreate` → `MainActivity.onCreate` →
     * launch resolver all run inside it, and a main thread too busy to land
     * heartbeats never reaches the healthy branch at all), and the reset itself
     * still has to pass [mayResetOnHealthyTick] against the inherited
     * `last_hang_at_ms`. [HangDetector] clears the flag once the count reads 0,
     * so steady-state healthy ticks stay free of `SharedPreferences` reads.
     */
    fun mayAttemptHeal(
        countedThisProcess: Boolean,
        carriedOverCount: Boolean,
        processAliveMs: Long,
        quietMs: Long,
    ): Boolean = when {
        countedThisProcess -> true
        carriedOverCount -> processAliveMs >= quietMs
        else -> false
    }
}
