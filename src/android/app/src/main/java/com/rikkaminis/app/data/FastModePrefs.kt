package com.rikkaminis.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * [T-codex-fast-mode] App-level persisted Fast Mode toggle (mirrors iOS
 * UserDefaults key `codexFastModeEnabled`, commit fb671083). Unlike Enhanced
 * Cache the flag IS durable and shared across sessions (user-confirmed on
 * iOS): switching to another chat reads the same enabled state.
 *
 * The provider layer has no Context, and iOS deliberately reads the flag at
 * REQUEST-BUILD time so a flip applies to the very next request of an ongoing
 * session — including offload / title-gen calls that never pass through the
 * ChatViewModel. To reproduce that, [prime] captures the value once at app
 * startup (MinisApp.onCreate) and warms a volatile cache; the context-free
 * [isEnabled] is then safe to call from any request builder.
 *
 * [F-235] The class used to also retain an `appContext` field that was written
 * by [prime] and never read anywhere (isEnabled reads `cachedEnabled`). It has
 * been removed: `ConcurrencyPrefs` cited "Mirrors the FastModePrefs pattern:
 * [prime] captures the application context" as its template, so leaving the
 * dead field in place was propagating the defect to the next copy.
 */
object FastModePrefs {
    private const val PREFS = "minis_fast_mode_prefs"
    private const val KEY_ENABLED = "codexFastModeEnabled"

    @Volatile
    private var cachedEnabled: Boolean = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Warm the cache from persisted state. Called from MinisApp.onCreate. */
    fun prime(context: Context) {
        cachedEnabled = prefs(context).getBoolean(KEY_ENABLED, false)
    }

    /**
     * Context-free read for request builders. Falls back to the warm cache
     * value (false before [prime] has ever run — matches an un-toggled fresh
     * install, and prime runs before any request can be built).
     */
    fun isEnabled(): Boolean {
        return cachedEnabled
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
