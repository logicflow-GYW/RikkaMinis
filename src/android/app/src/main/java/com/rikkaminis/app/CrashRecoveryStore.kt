package com.rikkaminis.app

import android.content.Context
import android.content.SharedPreferences

/**
 * [P3-crash-recovery] Process-level "the chat the user was in" record.
 *
 * A native SIGABRT never runs `Activity.onSaveInstanceState`, so the saved-state
 * bundle carries nothing on exactly the crash we most want to recover from. This
 * file is written on every chat-route change instead, and read on a true cold
 * start as the `OpenSession` deep link.
 *
 * [R4-U F-01] Two rules it enforces at its only write entry point:
 *  - a composer draft key (`__new__<uuid>`) is never persisted — there is no row
 *    behind it, so restoring it lands the user in a synthetic draft;
 *  - the write carries a timestamp, so a restore can honour AUTO's
 *    fresh-within-15-minutes contract (see [ChatRoutePresence.decideColdStartRestore]).
 *
 * [R4-U F-02] [clearIfMatches] is the delete-side counterpart: a session deleted
 * from a list would otherwise stay the restore target until the next chat-route
 * change.
 */
internal object CrashRecoveryStore {

    const val PREFS_NAME = "minis_crash_recovery"
    const val KEY_SESSION_ID = "last_chat_session_id"
    const val KEY_WRITTEN_AT = "last_chat_session_at"

    fun sessionId(context: Context): String? =
        prefs(context).getString(KEY_SESSION_ID, null)

    fun writtenAtMs(context: Context): Long =
        prefs(context).getLong(KEY_WRITTEN_AT, 0L)

    /**
     * Records [sessionId] as the last-open chat. Single `edit()` commit on
     * purpose: id and timestamp must never be observed out of step (a stale id
     * paired with a fresh timestamp would defeat the AUTO window).
     */
    fun record(context: Context, sessionId: String, nowMs: Long = System.currentTimeMillis()) {
        if (!ChatRoutePresence.shouldPersistCrashRecovery(sessionId)) return
        prefs(context).edit()
            .putString(KEY_SESSION_ID, sessionId)
            .putLong(KEY_WRITTEN_AT, nowMs)
            .apply()
    }

    /** Drops the record when it still points at [sessionId] (a deleted session). */
    fun clearIfMatches(context: Context, sessionId: String) {
        if (prefs(context).getString(KEY_SESSION_ID, null) != sessionId) return
        prefs(context).edit()
            .remove(KEY_SESSION_ID)
            .remove(KEY_WRITTEN_AT)
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
