package com.rikkaminis.app.sandbox

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [C7] The switch that owns [ProtectedZoneGuard]'s protection, default **ON**.
 *
 * ## Why it is not the auto-approve switch
 *
 * The guard used to yield to `AutoApproveStore.isEnabled`. That was wrong in
 * the one direction that matters: auto-approve exists so routine actions stop
 * asking for confirmation, and users turn it on for the convenience — not to
 * give up protection of the directories the backup covers. Because
 * auto-approve is also what makes deletes flow without a prompt, the guard
 * degraded to a pure logging pass at exactly the moment most deletes happen:
 * **protection rate 0% with the switch on.** Two intents, two switches.
 *
 * ## Why it cannot be turned off by the agent
 *
 * Deliberately NOT registered in `ConfigRegistry`, so `minis-config get/set`
 * has no path that reaches this value. An agent that has just been refused is
 * exactly the caller that would otherwise flip its own guard off — a guard the
 * guarded party can disable is not a guard.
 *
 * The value lives in the app's private SharedPreferences
 * (`/data/data/<pkg>/shared_prefs`), which the PRoot guest cannot read or
 * write. The only writer is the settings UI.
 *
 * ## Default ON
 *
 * Protection is the default and turning it off is an explicit act, so a fresh
 * install (and an upgrade from a build that had no such flag) is protected.
 * The default is also what [isEnabled] answers before [init] runs — a read
 * from a hot path during startup must never report "unprotected".
 *
 * Mirrors the shape of [com.rikkaminis.app.config.AutoApproveStore]
 * (StateFlow + eagerly persisted, pre-init toggles queued rather than dropped).
 */
object ZoneDeleteGuardStore {
    private const val PREFS = "zone_delete_guard_prefs"

    @VisibleForTesting internal const val KEY_ENABLED = "enabled"

    /** Protection is on unless the user turned it off. */
    @VisibleForTesting internal const val DEFAULT_ENABLED = true

    private var prefs: SharedPreferences? = null

    /** A toggle that arrived before [init]/[install]. */
    private var pending: Boolean? = null

    private val _enabled = MutableStateFlow(DEFAULT_ENABLED)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /**
     * Hot-path read used by [ExecutionCoordinator] (per shell command) and
     * `FastioOffloadHandler` (per delete). Both must consult THIS, so the two
     * enforcement paths can never disagree about policy.
     */
    val isEnabled: Boolean get() = _enabled.value

    /** Call once early — from `MinisApp.onCreate`. Idempotent. */
    fun init(context: Context) {
        install(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }

    /**
     * Test seam: same effect as [init] without a Context. `internal` so the
     * production surface stays the single [init] entry point.
     */
    @VisibleForTesting
    internal fun install(store: SharedPreferences) {
        if (prefs != null) return
        prefs = store
        pending?.let { store.edit().putBoolean(KEY_ENABLED, it).apply() }
        pending = null
        _enabled.value = store.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
    }

    /** UI toggle. Persists eagerly. */
    fun setEnabled(value: Boolean) {
        val store = prefs
        if (store == null) pending = value else store.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }
}
