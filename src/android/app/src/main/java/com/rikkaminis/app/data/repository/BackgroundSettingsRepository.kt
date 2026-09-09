package com.rikkaminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * T180-bg-notif: persistence for background-related toggles. Currently
 * only "Task Notifications" lives here; iOS exposes the same toggle in
 * `EnhancedBackgroundSettingsView` bound to
 * `BackgroundKeepAliveManager.backgroundNotificationsEnabled`.
 *
 * Default value is `true` to match iOS, where the toggle ships ON so
 * Live Activity and task-completion notifications work out-of-the-box
 * on first install. The user can opt out from Settings.
 */
class BackgroundSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _taskNotificationsEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_TASK_NOTIFICATIONS, DEFAULT_TASK_NOTIFICATIONS))

    /**
     * Live state of the toggle. Compose surfaces collect this so flipping
     * the switch in Settings is reflected immediately at every consumer
     * (notifier, FG service status text, etc).
     */
    val taskNotificationsEnabled: StateFlow<Boolean> =
        _taskNotificationsEnabled.asStateFlow()

    fun setTaskNotificationsEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_TASK_NOTIFICATIONS, value).apply()
        _taskNotificationsEnabled.value = value
    }

    /**
     * T-bg-overlay phase 2: "show floating tool-status overlay while the
     * app is backgrounded" toggle. Defaults to OFF — the overlay needs
     * SYSTEM_ALERT_WINDOW which is a separate system permission flow, so
     * we won't surface anything until the user opts in.
     */
    private val _backgroundOverlayEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_BG_OVERLAY_ENABLED, false))
    val backgroundOverlayEnabled: StateFlow<Boolean> =
        _backgroundOverlayEnabled.asStateFlow()

    fun setBackgroundOverlayEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_BG_OVERLAY_ENABLED, value).apply()
        _backgroundOverlayEnabled.value = value
    }

    /**
     * Last persisted overlay position (window x/y in pixels) from the
     * previous drag. -1 means "no remembered position — let the overlay
     * controller pick a default near the bottom-left, 10 dp from each
     * edge" ([T-bg-overlay-polish]).
     */
    fun getOverlayX(): Int = prefs.getInt(KEY_BG_OVERLAY_X, -1)
    fun getOverlayY(): Int = prefs.getInt(KEY_BG_OVERLAY_Y, -1)
    fun setOverlayPosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_BG_OVERLAY_X, x).putInt(KEY_BG_OVERLAY_Y, y).apply()
    }

    companion object {
        private const val PREFS_NAME = "background_settings"
        private const val KEY_TASK_NOTIFICATIONS = "taskNotificationsEnabled"
        private const val DEFAULT_TASK_NOTIFICATIONS = true
        private const val KEY_BG_OVERLAY_ENABLED = "backgroundOverlayEnabled"
        private const val KEY_BG_OVERLAY_X = "backgroundOverlayX"
        private const val KEY_BG_OVERLAY_Y = "backgroundOverlayY"
    }
}
