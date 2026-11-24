package com.rikkaminis.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the user's "Privacy Mode" preference for shell-execute output.
 *
 * When enabled, [EnvVarRedactor] masks any environment variable value
 * that appears verbatim in a shell tool's stdout/stderr before that
 * text is fed back to the model. The user still sees the unmasked
 * output in chat; only the bytes flowing into the agent's context are
 * rewritten.
 *
 * Default OFF — masking is an opt-in. It rewrites what the model reads, so a
 * user who never asked for it can be actively hurt by it: a redacted view of a
 * file that is written back replaces real values with the placeholder, and
 * secret-shaped NAMES in ordinary source code (`SECRET_SEGMENTS = setOf(...)`)
 * are masked as if they were values. Users who handle credentials in their
 * workspace turn it on in Settings → Permissions.
 *
 * The toggle lives in Settings → Permissions next to the other guard switches.
 * Durable diagnostics stay redacted regardless (see `EnvVarRedactor`), so
 * turning this off never turns a trace file into a secret store.
 *
 * Mirrors iOS `EnvVarPrivacyStore`.
 */
object EnvVarPrivacyStore {
    private const val PREFS = "envvar_privacy"
    @VisibleForTesting internal const val KEY = "privacy_mode_enabled"
    /** Default OFF — masking rewrites model-visible content and must be opted into. */
    @VisibleForTesting internal const val DEFAULT_ENABLED = false

    private var prefs: SharedPreferences? = null

    private val _enabled = MutableStateFlow(DEFAULT_ENABLED)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Hot-path read used by the redactor before any work. */
    val isEnabled: Boolean get() = _enabled.value

    /** Call once early — from MinisApp.onCreate. Idempotent. */
    fun init(context: Context) {
        install(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }

    /**
     * Test seam: same effect as [init] but without a Context. Kept `internal`
     * so the production surface stays the single [init] entry point.
     */
    @VisibleForTesting
    internal fun install(store: SharedPreferences) {
        if (prefs != null) return
        prefs = store
        _enabled.value = store.getBoolean(KEY, DEFAULT_ENABLED)
    }

    /** UI toggle. Persists eagerly. */
    fun setEnabled(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY, value)?.apply()
        _enabled.value = value
    }
}
