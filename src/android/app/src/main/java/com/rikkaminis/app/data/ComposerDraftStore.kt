package com.rikkaminis.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Durable home for the single unsent "new chat" draft.
 *
 * A draft session (`__new__<uuid>`) deliberately has NO row in the sessions
 * table until the first message is sent — creating rows eagerly was what
 * caused the empty-session residue bug. The flip side was that the composer
 * text lived only inside the ChatViewModel: switching sessions popped the
 * draft route, and the next "New Chat" minted a brand-new uuid, so the
 * typed-but-unsent text was unreachable (orphaned VM) and lost on process
 * death.
 *
 * This store keeps ONE stable draft id + text in SharedPreferences:
 *  - every "New Chat" entry point resolves through [nextDraftId], which
 *    returns the SAME id while a draft slot exists, so the draft is resumed
 *    instead of replaced;
 *  - ChatViewModel pushes composer changes via [saveText] and releases the
 *    slot via [clearDraft] (send, manual discard, or wiping the composer);
 *  - the process-wide [_snapshot] StateFlow drives the history drawer's
 *    "Draft" row.
 *
 * The pure core operates on a tiny [KV] interface so the id-resolution /
 * stale-id-guard / clear semantics are exercised by real JVM unit tests
 * (same code path the Android adapter uses — no mirror implementations).
 */
object ComposerDraftStore {

    /** Minimal key-value surface implemented by SharedPreferences. */
    interface KV {
        fun getString(key: String): String?
        fun putString(key: String, value: String)
        fun remove(key: String)
    }

    data class DraftSnapshot(val id: String, val text: String)

    internal const val KEY_ID = "draft_id"
    internal const val KEY_TEXT = "draft_text"
    internal const val KEY_UPDATED_AT = "draft_updated_at"

    // ---- pure core (JVM-testable; production code path) ----

    /** Returns the existing draft id, or persists a fresh one. */
    fun nextDraftId(kv: KV, generateId: () -> String): String {
        kv.getString(KEY_ID)?.let { return it }
        val fresh = generateId()
        kv.putString(KEY_ID, fresh)
        return fresh
    }

    /**
     * Persists text for [draftId].
     *
     * The slot is claimed when it is FREE (no id stored) so that typing again
     * after blanking the composer re-arms persistence — [clearDraft] releases
     * the slot on every blank, and without re-claiming here the second round
     * of text would silently not be saved.
     *
     * Writes are ignored when the slot belongs to a DIFFERENT draft, so a
     * stale route can never overwrite the live draft.
     */
    fun saveText(kv: KV, draftId: String, text: String) {
        val current = kv.getString(KEY_ID)
        if (current != null && current != draftId) return
        kv.putString(KEY_ID, draftId)
        kv.putString(KEY_TEXT, text)
        kv.putString(KEY_UPDATED_AT, System.currentTimeMillis().toString())
    }

    /** Restores the draft text for [draftId] if it is still the active draft. */
    fun restoreText(kv: KV, draftId: String): String =
        if (kv.getString(KEY_ID) == draftId) kv.getString(KEY_TEXT) ?: "" else ""

    /** Clears the slot only if it still belongs to [draftId]. */
    fun clearDraft(kv: KV, draftId: String) {
        if (kv.getString(KEY_ID) != draftId) return
        kv.remove(KEY_ID)
        kv.remove(KEY_TEXT)
        kv.remove(KEY_UPDATED_AT)
    }

    // ---- Android binding ----

    private class PrefsKV(private val prefs: SharedPreferences) : KV {
        override fun getString(key: String): String? = prefs.getString(key, null)
        override fun putString(key: String, value: String) =
            prefs.edit().putString(key, value).apply()
        override fun remove(key: String) = prefs.edit().remove(key).apply()
    }

    private val _snapshot = MutableStateFlow<DraftSnapshot?>(null)
    private var loaded = false

    /** Android entry for "New Chat": stable draft id or a fresh one. */
    fun nextDraftId(context: Context): String {
        val kv = PrefsKV(prefs(context))
        val id = nextDraftId(kv) { "__new__${UUID.randomUUID()}" }
        _snapshot.value = DraftSnapshot(id, restoreText(kv, id))
        loaded = true
        return id
    }

    fun saveText(context: Context, draftId: String, text: String) {
        val kv = PrefsKV(prefs(context))
        saveText(kv, draftId, text)
        if (kv.getString(KEY_ID) == draftId) _snapshot.value = DraftSnapshot(draftId, text)
    }

    fun restoreText(context: Context, draftId: String): String =
        restoreText(PrefsKV(prefs(context)), draftId)

    fun clearDraft(context: Context, draftId: String) {
        val kv = PrefsKV(prefs(context))
        clearDraft(kv, draftId)
        if (_snapshot.value?.id == draftId) _snapshot.value = null
    }

    /** Live draft snapshot for the history drawer's "Draft" row. */
    fun observeDraftSnapshot(context: Context): StateFlow<DraftSnapshot?> {
        if (!loaded) {
            val kv = PrefsKV(prefs(context))
            kv.getString(KEY_ID)?.let { id ->
                _snapshot.value = DraftSnapshot(id, restoreText(kv, id))
            }
            loaded = true
        }
        return _snapshot
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "composer_draft"
}
