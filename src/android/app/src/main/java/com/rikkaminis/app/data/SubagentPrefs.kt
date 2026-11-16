package com.rikkaminis.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Global toggle for sub-agent dispatch — an APP-LEVEL capability, ON by
 * default since [rework/spawn-agent-as-real-session 2026-11-17].
 *
 * There is one sub-agent shape: a real chat session. Both entry points run the
 * same agent loop and the same full tool set, so the switch gates ONE
 * capability, not two competing implementations:
 *   - `spawn_agent` (synchronous front door: dispatch + wait for the answer).
 *   - `minis-sessions-cli send` (async / parallel / continue a session).
 *
 * When OFF both are removed (the tool disappears from the schema and `send`
 * refuses with a typed error envelope), so "the agent cannot open sessions"
 * is a single, honest state.
 *
 * Previous default was OFF because the in-process sub-agent was a second,
 * degraded loop; that reason is gone. A user who explicitly turned it off
 * keeps their stored `false` — this constant only seeds devices that never
 * made a choice.

 * This is a side-effectful capability (agent can open N sessions, burn tokens,
 * run long-lived work) so it is OFF by default and deliberately a single,
 * user-visible switch — mirroring the user's "打开则有，不打开则没有" framing.

 * Persisted in its own prefs file and also registered in ConfigBuiltins so it
 * surfaces in Settings → Agent Runtime and travels with in-app backup.
 */
object SubagentPrefs {
    const val PREFS = "minis_subagent_prefs"
    const val KEY_ENABLED = "subagent.enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
