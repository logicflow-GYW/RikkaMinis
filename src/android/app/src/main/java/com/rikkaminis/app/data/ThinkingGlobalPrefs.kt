package com.rikkaminis.app.data

import android.content.Context
import android.content.SharedPreferences
import com.rikkaminis.app.data.model.ThinkingLevel

/**
 * Cross-conversation "last tuned thinking level", persisted in SharedPreferences.
 *
 * Two-layer model (mirrors [MemoryGlobalPrefs]):
 *   - **Global** (this file): what the user last EXPLICITLY tuned via the
 *     composer picker / toggle, regardless of which session they were in.
 *     Seeds every fresh draft chat AND any existing session whose row has no
 *     per-session override (null = never chose) — so the tuned level survives
 *     cold start even when the launch preference opens a new chat.
 *   - **Per-session** (`ChatSessionEntity.thinking_override`): explicit
 *     per-chat choice; once a session's row carries one, it wins over this.
 *
 * A group's `defaultThinkingLevel` still takes priority over this on a fresh
 * draft — it is an explicit per-group configuration, more specific than the
 * cross-conversation last-used value.
 *
 * ponytail: last-value pref, no UI | 天花板: no way to say "start new chats at
 * OFF without remembering" — a deliberate OFF is remembered as OFF | 升级触发:
 * a user asks for a separate "default level" setting distinct from last-used.
 */
object ThinkingGlobalPrefs {
    private const val PREFS = "minis_thinking_prefs"
    private const val KEY_LAST_LEVEL = "thinking.global.last_level"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** null = the user never explicitly tuned thinking (drafts start OFF). */
    fun lastLevel(context: Context): ThinkingLevel? =
        prefs(context).getString(KEY_LAST_LEVEL, null)
            ?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() }

    fun setLastLevel(context: Context, level: ThinkingLevel) {
        prefs(context).edit().putString(KEY_LAST_LEVEL, level.name).apply()
    }
}
