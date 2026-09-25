package com.rikkaminis.app.diagnostics

/**
 * [Perf] Structured observation for the three force-home circuit breakers
 * (HangDetector / CrashFrequencyDetector / LaunchCycleBeacon beacon
 * restart-count) joined in the launch resolver.
 *
 * Before this object existed the resolver flipped `mode = 3` silently: a
 * user landing on the safe-start draft chat instead of their session had no
 * log line to answer "why did it force home this time" — the three breakers
 * each had their own artifacts (stall logs / crash share dialog / beacon
 * Perf line), but the JOIN point where one of them actually changed the
 * launch destination had no trace, so cold-start recovery loss had to be
 * attributed by guessing which breaker fired.
 *
 * Observation only — the resolver semantics (which breaker wins, what mode 3
 * means) stay untouched. This adds one warning line at each of the two
 * force-home application points:
 *  - the cold-start launch-mode resolver (AppNavigation);
 *  - the synthesized restore-deep-link guard (MainActivity).
 */
object ForceHomeTrace {

    private const val TAG = "ForceHomeTrace"

    /**
     * Pure reason-text builder — JVM-testable. Deterministic given inputs;
     * empty string when no breaker fired (callers only log when at least one
     * did, so an empty result never reaches a log line).
     */
    internal fun reasons(
        hang: Boolean,
        crash: Boolean,
        beacon: Boolean,
        hangCount: Int,
        restartCount: Int,
    ): String = buildString {
        if (hang) append("hangBreaker=yes hangCount=").append(hangCount).append(' ')
        if (crash) append("crashWindow=yes ")
        if (beacon) append("beaconBreaker=yes restartCount=").append(restartCount)
    }.trim()

    /** True when any of the three breakers fires. Pure; JVM-testable. */
    internal fun forced(hang: Boolean, crash: Boolean, beacon: Boolean): Boolean =
        hang || crash || beacon

    /**
     * Cold-start launch-mode resolver side. Returns the effective launch mode
     * (3 = safe start when any breaker fires, otherwise [rawMode]) and logs
     * one structured line when the breakers forced it — carrying every fired
     * breaker's live counter so the cause is mechanically attributable.
     *
     * Deliberately logs whenever a breaker fires, even when [rawMode] is
     * already 3: the user's own safe-start preference and a breaker forcing
     * the same destination are different facts, and only the log can tell
     * them apart afterwards.
     */
    fun resolverMode(
        context: android.content.Context,
        rawMode: Int,
        hang: Boolean,
        crash: Boolean,
        beacon: Boolean,
    ): Int {
        val forcedNow = forced(hang, crash, beacon)
        val mode = if (forcedNow) 3 else rawMode
        if (forcedNow) {
            com.rikkaminis.app.logging.AppLogger.warning(
                TAG,
                "[Perf][LongCtx] step=launchResolver.forceHome rawMode=$rawMode mode=3 " +
                    reasons(
                        hang, crash, beacon,
                        HangDetector.currentHangCount(context),
                        LaunchCycleBeacon.lastRestartCount,
                    ),
            )
        }
        return mode
    }

    /**
     * Restore-deep-link guard side (MainActivity). Returns whether the
     * breakers suppress the synthesized restore link, logging one structured
     * line when they do — this is the path a post-kill cold start takes when
     * the crash-recovery deep link is refused, i.e. the second half of the
     * "cold start lost my session context" symptom.
     */
    fun guardBlocked(
        context: android.content.Context,
        hang: Boolean,
        crash: Boolean,
        beacon: Boolean,
    ): Boolean {
        val blocked = forced(hang, crash, beacon)
        if (blocked) {
            com.rikkaminis.app.logging.AppLogger.warning(
                TAG,
                "[Perf][LongCtx] step=launchResolver.restoreLinkSuppressed " +
                    reasons(
                        hang, crash, beacon,
                        HangDetector.currentHangCount(context),
                        LaunchCycleBeacon.lastRestartCount,
                    ),
            )
        }
        return blocked
    }
}
