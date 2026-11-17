package com.rikkaminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.rikkaminis.app.diagnostics.SessionIdAliases
import com.rikkaminis.app.logging.AppLogger

/**
 * Process-level cache of ChatViewModels keyed by sessionId. Mirrors iOS
 * `ViewModelCache` — a session's agent loop keeps running even if the user
 * leaves the chat screen, and the row in the sessions list shows a spinning
 * indicator while streaming is in flight.
 *
 * Without this, scoping the ChatViewModel to a NavBackStackEntry means
 * `popBackStack()` would cancel `viewModelScope` and kill the streaming job.
 */
object ChatViewModelStore {

    private const val TAG = "ChatVMStore"

    /**
     * One ViewModelStore per canonical sessionId. Each store contains at most
     * one ChatViewModel (the one created by our factory). When we want to drop
     * a session's VM, we call `clear()` on its store which triggers
     * `onCleared`.
     */
    private val stores = mutableMapOf<String, ViewModelStore>()

    /**
     * Draft → canonical mapping. When a draft ("__new__...") session is
     * persisted, we add `draftKey -> realId` here so lookups via the old key
     * (from a ChatScreen whose `sessionId` parameter is still the draft)
     * continue to hit the same live store.
     */
    private val aliases = mutableMapOf<String, String>()

    /**
     * [da1019-ISSUE-07] Read side of [aliases]. Every mutation of the map
     * (`rename` / `release`) already runs under this object's monitor, but the
     * lookups did not — `activeSessionId` is read from the offload worker
     * thread (`minis-config session.*` → ConfigBuiltins' readers), so an
     * unsynchronized `aliases[sessionId]` raced the main thread's
     * `LinkedHashMap` mutation with no happens-before edge. A reader could
     * miss a just-registered draft alias and resolve a draft key to itself
     * (the config layer then reads the wrong session row), and concurrent
     * structural mutation of a plain `LinkedHashMap` is undefined behaviour.
     * Same lock as the writers, so reads and writes serialize.
     */
    @Synchronized
    private fun resolveKey(sessionId: String): String =
        aliases[sessionId] ?: sessionId

    /**
     * [da1023-F] Public view of [resolveKey] for callers that hold the nav-arg
     * session id instead of the VM.
     *
     * A draft chat's route arg stays on `__new__<uuid>` for the lifetime of
     * the screen even after the first send promotes it to a real session row
     * (`ChatSessionEntryExt.ensureSession` → [rename]). In-chat callers read
     * the live id through `viewModel.currentSessionId`; callers outside the
     * screen (`AppNavigation` route builders) only have the route arg, so they
     * need the same alias hop or they navigate session-scoped destinations
     * into the orphan draft key. Unknown / canonical ids pass through
     * unchanged, so non-draft callers see identical behaviour.
     */
    @Synchronized
    fun resolveSessionId(sessionId: String): String =
        resolveKey(sessionId)

    @Synchronized
    fun ownerFor(sessionId: String): ViewModelStoreOwner {
        val key = resolveKey(sessionId)
        val store = stores.getOrPut(key) {
            AppLogger.info(TAG, "allocate store for $key (total=${stores.size + 1})")
            ViewModelStore()
        }
        return object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
    }

    /**
     * [dv24-A05] NON-CREATING lookup of a session's resident [ChatViewModel].
     *
     * [ownerFor] cannot be used for this: it `getOrPut`s a fresh
     * [ViewModelStore], so calling it for a session that is not on screen
     * allocates an owner that never receives a VM (leak) — or, once a caller
     * resolves the VM through `ViewModelProvider`, materialises a whole
     * ChatViewModel (agent-loop scope, repositories, provider) for a chat the
     * user closed. Callers that merely want to *refresh* whatever is already
     * resident must not have that side effect, and this is the only such entry
     * point: it walks the existing stores and answers null otherwise.
     *
     * Used by `minis-config`'s `session.thinkingLevel` / `session.primaryModel`
     * writers, which persist to Room and then have to push the same value into
     * the live UI (see `ConfigBuiltins.writeSessionConfig`).
     *
     * @return the resident VM, or null when this session has no live VM.
     */
    @Synchronized
    fun peekViewModel(sessionId: String): ChatViewModel? {
        val store = stores[resolveKey(sessionId)] ?: return null
        for (key in store.keys()) {
            val vm = store.get(key)
            if (vm is ChatViewModel) return vm
        }
        return null
    }

    /**
     * Drop the cached VM for this session (cancels `viewModelScope`, triggers
     * `ChatViewModel.onCleared`). Call when the session is deleted. Also
     * clears any draft alias pointing at this canonical id.
     */
    @Synchronized
    fun release(sessionId: String) {
        val key = resolveKey(sessionId)
        aliases.entries.removeAll { it.value == key }
        SessionIdAliases.unregisterByCanonical(key)
        stores.remove(key)?.let {
            it.clear()
            AppLogger.info(TAG, "release store for $key (remaining=${stores.size})")
        }
    }

    /**
     * Mark `fromSessionId` (a draft key) as an alias for `toSessionId` (the
     * real, persisted id). The live store stays under the real id; future
     * `ownerFor(draftKey)` lookups resolve to the same store so a ChatScreen
     * still rendering with the draft route continues to see the running VM.
     */
    /**
     * T311: id of the chat the user has on screen right now. Set by
     * `ChatScreen`'s lifecycle hook on enter, cleared on dispose.
     * `minis-config session.*` reads this so reads/writes target the
     * "current session" the same way iOS `AIChatViewModel.activeSessionId`
     * does. `null` = no chat is foregrounded → reads return empty / writes
     * throw `No active session`. Resolves through `aliases` so a draft id
     * still maps to the persisted row.
     */
    @Volatile
    private var activeSessionIdInternal: String? = null

    val activeSessionId: String?
        // [da1019-ISSUE-07] Same monitor as `setActiveSession` / `rename` /
        // `release`: the offload thread that serves `minis-config session.*`
        // must not read the alias table while the main thread mutates it.
        @Synchronized get() = activeSessionIdInternal?.let { resolveKey(it) }

    @Synchronized
    fun setActiveSession(sessionId: String?) {
        activeSessionIdInternal = sessionId
    }

    /**
     * [audit-0909 T2-H1] Compare-and-clear. Chat→Chat navigation composes the
     * NEW ChatScreen (setActiveSession(B)) before the OLD one is disposed —
     * NavHost's AnimatedContent keeps the outgoing destination alive for the
     * ~300ms transition — so an unconditional `setActiveSession(null)` in
     * onDispose always ran last and left the app with NO active session while
     * chat B was on screen. ConfigBuiltins' `session.*` family then read
     * `ConfigValue.Null` / threw "No active session — open a chat first" until
     * the user happened to pop back from a non-chat screen. Only clear when
     * the exiting screen still owns the slot.
     */
    @Synchronized
    fun clearActiveSession(sessionId: String) {
        if (activeSessionIdInternal == sessionId) activeSessionIdInternal = null
    }

    /**
     * [T-empty-session-residue] Every session id that still has a live VM,
     * plus the foregrounded one and any draft aliases pointing at them.
     *
     * Used by the startup empty-session sweep as an exclusion list. A cached
     * store means the agent loop may still be running (streaming survives
     * leaving the chat screen by design), so those rows must never be swept
     * even while they momentarily have zero messages.
     */
    @Synchronized
    fun activeSessionIds(): List<String> =
        (stores.keys + aliases.keys + aliases.values + listOfNotNull(activeSessionIdInternal))
            .toList()

    @Synchronized
    fun rename(fromSessionId: String, toSessionId: String) {
        if (fromSessionId == toSessionId) return
        val store = stores.remove(fromSessionId)
        if (store != null) {
            stores[toSessionId] = store
        }
        aliases[fromSessionId] = toSessionId
        SessionIdAliases.register(fromSessionId, toSessionId)
        AppLogger.info(TAG, "rename store $fromSessionId -> $toSessionId (alias kept)")
    }

    /**
     * One-shot stash for the "Move to…" capsule flow. The source session
     * writes (inputText + attachments) here, navigates to the target,
     * and the target's ChatScreen drains it via [consumePendingTransfer].
     * Mirrors iOS `ViewModelCache.pendingTransfer`. Volatile + simple
     * read/write — only ever touched from the main thread.
     */
    data class PendingTransfer(
        val inputText: String,
        val attachments: List<InputAttachment>,
        /**
         * [incr1118 D2-F02] Folded paste chips the source could not carry in
         * [inputText]: the transfer expands resolvable `[Pasted#N]` literals
         * into the text, so anything still in the buffer is a block with no
         * literal (detached paste / orphan row). They travel here and the
         * target's COMPOSER re-stashes them as detached rows (which appends
         * them after the body on send — the same "body, then material" shape a
         * fresh paste produces). Default empty so a caller with nothing to
         * carry compiles unchanged.
         *
         * Named `carriedPastes`, not `pastedTexts`, on purpose: the paste
         * buffer is the composer's, and PasteFoldWiringTest pins that
         * [ChatScreen] never names it. The screen only raises the intent.
         */
        val carriedPastes: List<PastedText> = emptyList(),
    )

    @Volatile
    private var pendingTransfer: PendingTransfer? = null

    fun stashPendingTransfer(transfer: PendingTransfer) {
        pendingTransfer = transfer
        Log.d(TAG, "stashPendingTransfer: text=${transfer.inputText.length}ch attachments=${transfer.attachments.size}")
    }

    /** Drain the pending-transfer slot exactly once. */
    fun consumePendingTransfer(): PendingTransfer? {
        val t = pendingTransfer
        pendingTransfer = null
        if (t != null) Log.d(TAG, "consumePendingTransfer: text=${t.inputText.length}ch attachments=${t.attachments.size}")
        return t
    }
}
