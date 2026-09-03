package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Global toggle for the cross-session sub-agent dispatch capability.
 *
 * When OFF (default), the agent has no way to spawn a new chat session or
 * delegate work to an independent sub-agent:
 *   - `spawn_agent` is removed from the tool schema (AgentTools).
 *   - `minis-sessions-cli send` refuses with a typed error envelope.
 *
 * When ON:
 *   - `spawn_agent` is exposed (its own tool set is filtered inside
 *     SubagentSkill.buildFilteredTools).
 *   - `minis-sessions-cli send` can create/continue a session and prompt it
 *     headlessly via ChatMutationMethods (the debug RPC `chat.prompt` path).

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
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
