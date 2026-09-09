package com.rikkaminis.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * [native-rss-tool-guard / D-2] App-level persisted "max concurrent agent-loop
 * sessions" preference. Phase 0 hard-coded the cross-session concurrency cap
 * to 2 at three aligned places (SessionConcurrencyManager, ExecutionCoordinator,
 * NativeOffloadServer) to keep the shared main-process native/mmap RSS from
 * climbing to the 5.8–6.0GB SIGABRT seen on 2026-08-19. D-2 makes that cap
 * configurable + observable so the user can run with 2 for a while, read the
 * slot occupancy, then evaluate widening to 3.
 *
 * Mirrors the FastModePrefs pattern: [prime] captures the application context
 * once at app startup (MinisApp.onCreate) and warms a volatile cache; the
 * context-free [maxConcurrentSessions] is then safe to call from any layer
 * (sandbox singletons etc.) that has no Context. The value is read once at
 * prime time and held until the next process start, so a change applies the
 * next time the app process launches — safe, no runtime slot resizing.
 *
 * Deliberately a SINGLE cap shared by all three coordinated gates. Phase 0
 * aligned them to the same bound on purpose; a single knob keeps them aligned
 * (changing one without the other two would silently let one path burst past
 * the budget the rest of the memory machinery was sized for).
 */
object ConcurrencyPrefs {
    const val PREFS = "minis_concurrency_prefs"
    const val KEY_MAX_CONCURRENT_SESSIONS = "maxConcurrentSessions"

    /**
     * Bounds. [MIN] floors at 1 (the slot controller requires strictly
     * positive). [MAX] is a soft upper sanity bound — effectively "uncapped"
     * for any realistic on-device use: with several concurrent agent sessions
     * the real backstop is no longer this knob but the process-RSS hard gate
     * and the heavy-command serializer in [ExecutionCoordinator], which stay
     * in force regardless of how many slots the user opens here.
     */
    const val MIN = 1
    const val MAX = 16

    /** Default matches Phase 0's hard-coded cap (2 aligned gates). */
    const val DEFAULT = 2

    @Volatile
    private var cachedMaxSessions: Int = DEFAULT

    @Volatile
    private var primed = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Capture the app context and warm the cache. Call from MinisApp.onCreate. */
    fun prime(context: Context) {
        cachedMaxSessions = clamp(prefs(context).getInt(KEY_MAX_CONCURRENT_SESSIONS, DEFAULT))
        primed = true
    }

    /** Context-free read for sandbox singletons / coordinators. */
    fun maxConcurrentSessions(): Int = cachedMaxSessions

    /**
     * Persist a new cap and warm the cache. Takes effect on the next process
     * start (lazy controller / semaphores are sized at init, deliberately not
     * resized at runtime — see class doc).
     */
    fun setMaxConcurrentSessions(context: Context, value: Int) {
        val v = clamp(value)
        cachedMaxSessions = v
        prefs(context).edit().putInt(KEY_MAX_CONCURRENT_SESSIONS, v).apply()
    }

    /** Exposed for tests: whether [prime] has run in this process. */
    fun isPrimed(): Boolean = primed

    private fun clamp(v: Int): Int = v.coerceIn(MIN, MAX)
}
