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
 *
 * [r5-d2 B-F-02] Two write-side gaps closed here, both invisible while the
 * store looked like a plain "last id" file:
 *  - entering a composer draft now CLEARS the record instead of keeping the
 *    chat the user just left (see [slotWriteFor]);
 *  - the draft's promotion to a real row ([ChatViewModel.ensureSession]) is a
 *    second write site: the nav listener fired while the route was still
 *    `__new__<uuid>`, and promotion renames only the ViewModel alias, so no
 *    further back-stack emission ever carries the real id.
 */
internal object CrashRecoveryStore {

    const val PREFS_NAME = "minis_crash_recovery"
    const val KEY_SESSION_ID = "last_chat_session_id"
    const val KEY_WRITTEN_AT = "last_chat_session_at"

    fun sessionId(context: Context): String? =
        prefs(context).getString(KEY_SESSION_ID, null)

    fun writtenAtMs(context: Context): Long =
        prefs(context).getLong(KEY_WRITTEN_AT, 0L)

    /** What [record] does with a chat-route id. */
    internal enum class SlotWrite {
        /** A real session — persist it as the restore target. */
        WRITE,

        /** A composer draft — the user LEFT the recorded chat; drop the slot. */
        CLEAR,

        /** Not a chat route at all (blank / null) — leave the slot untouched. */
        IGNORE,
    }

    /**
     * [r5-d2 B-F-02①] The decision table behind [record].
     *
     * The pre-fix rule was "persist, else return": a draft route wrote nothing
     * and KEPT the old record, so a native crash / LMK restore reopened the
     * conversation the user had already walked away from — the draft they were
     * actually typing in was never a restore target either way (there is no row
     * behind `__new__<uuid>`), so the only correct record for "user is in a
     * brand-new chat" is NO record. Leaving it also made the stale id
     * indistinguishable from a live one for the AUTO freshness window.
     */
    fun slotWriteFor(sessionId: String?): SlotWrite = when {
        ChatRoutePresence.shouldPersistCrashRecovery(sessionId) -> SlotWrite.WRITE
        ChatRoutePresence.isDraftSessionId(sessionId) -> SlotWrite.CLEAR
        else -> SlotWrite.IGNORE
    }

    /**
     * Records [sessionId] as the last-open chat. Single `edit()` commit on
     * purpose: id and timestamp must never be observed out of step (a stale id
     * paired with a fresh timestamp would defeat the AUTO window).
     */
    fun record(context: Context, sessionId: String, nowMs: Long = System.currentTimeMillis()) {
        when (slotWriteFor(sessionId)) {
            SlotWrite.CLEAR -> {
                clear(context)
                return
            }
            SlotWrite.IGNORE -> return
            SlotWrite.WRITE -> Unit
        }
        prefs(context).edit()
            .putString(KEY_SESSION_ID, sessionId)
            .putLong(KEY_WRITTEN_AT, nowMs)
            .apply()
    }

    /** Drops whatever the record points at. */
    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_SESSION_ID)
            .remove(KEY_WRITTEN_AT)
            .apply()
    }

    /** Drops the record when it still points at [sessionId] (a deleted session). */
    fun clearIfMatches(context: Context, sessionId: String) {
        if (prefs(context).getString(KEY_SESSION_ID, null) != sessionId) return
        clear(context)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
