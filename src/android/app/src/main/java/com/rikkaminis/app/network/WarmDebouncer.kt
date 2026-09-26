package com.rikkaminis.app.network

/**
 * Pure-ish debounce/recent-origin state machine behind [ConnectionWarmer].
 * Extracted so the debounce semantics (success window, failure rollback,
 * network-change re-arm) are JVM-testable without an OkHttp client — the
 * caller injects a [clock]. Thread safety: one monitor for both maps;
 * `synchronized` here gives the same "concurrent warm() calls collapse to
 * exactly one warmup" guarantee the Filterrr source got from a CAS on an
 * AtomicLong (ConcurrentHashMap + AtomicLong can't be unit-driven with a
 * fake clock, hence this extraction).
 */
internal class WarmDebouncer(private val clock: () -> Long = System::currentTimeMillis) {

    companion object {
        /** Re-arm at most this often per origin after a SUCCESSFUL warmup. */
        const val DEBOUNCE_MS = 60_000L

        /**
         * [FIX-audit-P1-warmup] Short re-arm window after a FAILED warmup.
         * The original (Filterrr) code stamped the failure by zeroing the
         * stamp — immediately eligible again — while its own constant +
         * comment claimed an 8s window; the constant was dead. Here the
         * documented semantics are implemented: failure → retry eligible
         * after [FAILURE_DEBOUNCE_MS], so an outage sprays at most one HEAD
         * per 8s per origin instead of one per request.
         */
        const val FAILURE_DEBOUNCE_MS = 8_000L

        /**
         * How many distinct origins [onNetworkChanged] re-warms, most recent
         * first. Provider switches are user-paced, so the realistic window
         * between a network flap and the next send covers 2-3 origins at most;
         * re-warming every origin ever seen would spray HEADs at stale hosts.
         */
        const val REWARM_ORIGINS = 3
    }

    /** Debounce key = origin string ("scheme://host:port"). Guarded by `this`. */
    private val lastWarmedAtMs = HashMap<String, Long>()

    /** Recently warmed origins, newest first. Guarded by `this`. */
    private val recentOrigins = ArrayDeque<String>()

    /**
     * Attempt to begin a warmup for [origin]: `true` (and stamps it) if the
     * success debounce window allows it, `false` if a warmup already happened
     * inside the window.
     */
    fun tryBegin(origin: String): Boolean = synchronized(this) {
        val now = clock()
        val last = lastWarmedAtMs[origin]
        // Guard `last != null` instead of a Long.MIN_VALUE sentinel: the
        // sentinel form (`now - Long.MIN_VALUE`) overflows and would make
        // the very FIRST warmup fail the debounce check forever.
        if (last != null && now - last < DEBOUNCE_MS) return false
        lastWarmedAtMs[origin] = now
        rememberOriginLocked(origin)
        true
    }

    /**
     * [FIX-audit-P1-warmup] Failure rollback: stamp the origin as warmed
     * [DEBOUNCE_MS] - [FAILURE_DEBOUNCE_MS] ago, so the next warm() is
     * eligible after the SHORT failure window instead of the full 60s.
     */
    fun markFailure(origin: String) = synchronized(this) {
        lastWarmedAtMs[origin] = clock() - DEBOUNCE_MS + FAILURE_DEBOUNCE_MS
    }

    /**
     * Network transition: clear all success stamps (the pool was just
     * evicted — any origin is worth re-warming) and return the most recent
     * origins, newest first.
     */
    fun onNetworkChanged(): List<String> = synchronized(this) {
        lastWarmedAtMs.clear()
        recentOrigins.take(REWARM_ORIGINS)
    }

    /** Track recency (newest first, deduplicated, bounded). Caller holds the monitor. */
    private fun rememberOriginLocked(origin: String) {
        recentOrigins.remove(origin)
        recentOrigins.addFirst(origin)
        while (recentOrigins.size > REWARM_ORIGINS * 2) recentOrigins.removeLast()
    }
}
