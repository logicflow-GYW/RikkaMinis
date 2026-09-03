package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.sandbox.ExecutionCoordinator
import kotlinx.coroutines.launch

// [FE-5 batch 8] Session-lifecycle entry cluster (loadSessionEntity /
// updateTitleAndCategory / promoteDraftIfNeeded / ensureSession /
// migrateDraftResources / copyRecursive) extracted verbatim from
// ChatViewModel as extension functions.


/** T-chat-title-pill-edit: load the persisted [ChatSessionEntity] for the
 *  current session so the shared edit-title sheet (reused from the session
 *  list) can be opened from the in-chat title pill. Returns null for
 *  drafts that haven't been persisted yet. */
suspend fun ChatViewModel.loadSessionEntity(): com.openminis.app.data.db.ChatSessionEntity? {
    val sid = realSessionId.ifEmpty { return null }
    return runCatching { chatRepository.getSession(sid) }.getOrNull()
}

/** T-chat-title-pill-edit: update title + category from the in-chat
 *  edit sheet. Mirrors SessionListViewModel.updateTitleAndCategory but
 *  also refreshes the local StateFlows so the pill updates immediately
 *  without waiting for a session reload. */
fun ChatViewModel.updateTitleAndCategory(title: String, category: String?) {
    val sid = realSessionId.ifEmpty { return }
    viewModelScope.launch {
        chatRepository.updateSessionTitleAndCategory(sid, title, category)
        _sessionTitle.value = title.ifBlank { "New Chat" }
        _sessionCategory.value = category
    }
}

/**
 * [promote-draft-on-new-chat] If the user is on a draft with unsent text
 * and taps "New Chat", promote the current draft to a real session so the
 * typed text isn't silently lost. The slot is freed synchronously so the
 * next `ComposerDraftStore.nextDraftId` returns a fresh id for the new
 * draft; the DB row + title write happens asynchronously in viewModelScope
 * (local DB, ~50ms — no need to block the UI).
 *
 * Returns true when promotion was triggered — the caller should let
 * onNewChat proceed (the slot is already freed either way).
 */
fun ChatViewModel.promoteDraftIfNeeded(): Boolean {
    if (!isDraft || realSessionId.isNotEmpty()) return false
    val text = _inputText.value
    if (text.isBlank()) return false

    // Free the draft slot synchronously — the text is captured in `text`,
    // and nextDraftId must return a fresh ID before the navigation fires.
    _inputText.value = ""
    com.openminis.app.data.ComposerDraftStore.clearDraft(context, sessionId)

    // Create a real session row + set its title asynchronously.
    viewModelScope.launch {
        val sid = ensureSession()
        if (sid.isNotEmpty()) {
            val title = text.take(50).trim()
            chatRepository.updateSessionTitleAndCategory(sid, title, null)
            _sessionTitle.value = title
        }
    }
    return true
}

/** Ensure the session exists in the database. Called before first message. */
internal suspend fun ChatViewModel.ensureSession(): String {
    if (realSessionId.isNotEmpty()) return realSessionId
    val modelId = currentModel?.id ?: providerRepository.allVisibleEntries().firstOrNull()?.model?.id ?: "unknown"
    // [T-memory-global-toggle-settings-ui-android] Snapshot the
    // current in-memory `_memoryEnabled` into the new row. For a
    // draft VM this matches the global default we seeded at
    // construction; if the user flipped /memory on the draft
    // before first send, that choice wins.
    val session = chatRepository.createSession(
        modelId = modelId,
        memoryEnabled = _memoryEnabled.value,
        // [T-empty-session-residue] Same reasoning for the thinking
        // override: fold it into the insert so flipping /thinking on a
        // draft no longer needs a pre-materialising write of its own.
        thinkingLevel = _thinkingLevel.value.name,
    )
    realSessionId = session.id
    // Move our cached VM from the draft key ("__new__...") to the real
    // sessionId so re-entering the session reuses the same instance.
    if (isDraft) {
        ChatViewModelStore.rename(sessionId, session.id)
        // Bring every disk/shell resource that was opened with the draft
        // id over to the real id *before* agent tools start running against
        // the persisted session — otherwise the first tool call (e.g.
        // yt-dlp writing into /var/minis/attachments) would land in
        // minis-sessions/__new__*/… and be orphaned when the user
        // re-enters the session and everything is resolved via the real
        // id. See debug report 2026-04-21 (TikTok Chinese filename).
        migrateDraftResources(fromDraft = sessionId, toReal = session.id)
        // [T-android-session-skill-override-init-timing] Re-point any
        // session_skill_overrides / mcp_session_overrides rows written
        // pre-first-message (against `__new__<uuid>`) onto the real
        // session id, mirroring the disk-resource hop above. Without
        // this, a skill or MCP server the user toggled on the draft
        // session sheet vanishes the next time the same chat is opened
        // (the prop carries the real id by then, but the override row
        // is still stranded under the draft key). Aligns with iOS
        // ed861471 (T-ios-session-skill-override-init-timing). Cheap
        // no-op when no rows match.
        skillRepository?.renameSessionOverrides(fromDraft = sessionId, toReal = session.id)
        mcpRepository?.renameSessionOverrides(fromDraft = sessionId, toReal = session.id)
        // Re-point the lazily-created BrowserTabPool if it was already
        // instantiated against the draft key (e.g. user opened the browser
        // sheet before sending a message). Without this, cookies and
        // downloads keep flowing into the draft directory.
        _browserTabPoolRef?.setSession(session.id)
    }
    // Persist the current model binding so it survives re-entry
    val groupId = _selectedGroupId.value
    val entryId = _activeEntryId.value
    val binding = when {
        groupId != null && entryId != null -> """{"type":"group","groupId":"$groupId","lastEntryId":"$entryId"}"""
        groupId != null -> """{"type":"group","groupId":"$groupId"}"""
        entryId != null -> """{"type":"entry","entryId":"$entryId"}"""
        else -> null
    }
    if (binding != null) {
        chatRepository.updateSessionBinding(realSessionId, binding, modelId)
    }
    return realSessionId
}

/**
 * Move every per-session disk resource from the draft directory to the
 * real one, and tear down any shell that was started against the draft id.
 *
 * The draft key leaks into persistent shells (`ExecutionCoordinator`),
 * browser artifacts (`persistBrowserArtifact`), and the `BrowserTabPool`'s
 * cookie/state store. Before this migration ran, a tool invocation that
 * happened before the user's first message would write into the draft's
 * `minis-sessions/__new__{uuid}` directory and become invisible the
 * moment the VM was recreated under the real id — exactly the symptom
 * observed with the Chinese-named TikTok download that appeared to
 * "disappear" after `yt-dlp` reported success.
 */
private fun ChatViewModel.migrateDraftResources(fromDraft: String, toReal: String) {
    // Stop any shell that was already spun up against the draft id; its
    // -b mount arguments were frozen to the draft directory at launch, so
    // we can't reuse it after the migration.
    runCatching { ExecutionCoordinator.sessionDidTerminate(fromDraft) }

    val base = java.io.File(context.filesDir, "minis-sessions")
    val draftBase = java.io.File(base, fromDraft)
    if (!draftBase.isDirectory) return
    val realBase = java.io.File(base, toReal).apply { mkdirs() }

    listOf("attachments", "offloads", "workspace", "browser").forEach { subdir ->
        val src = java.io.File(draftBase, subdir)
        if (!src.isDirectory) return@forEach
        val dst = java.io.File(realBase, subdir).apply { mkdirs() }
        src.listFiles()?.forEach { child ->
            val target = java.io.File(dst, child.name)
            runCatching {
                if (!target.exists() && !child.renameTo(target)) {
                    copyRecursive(child, target)
                }
            }.onFailure {
                android.util.Log.w("ChatViewModel",
                    "migrateDraftResources: failed to move ${child.absolutePath} -> ${target.absolutePath}: ${it.message}")
            }
        }
    }
    runCatching { draftBase.deleteRecursively() }

    // Also rename the BrowserTabPool saved-state file (filesDir/browser_tabs/<sid>.json).
    // Otherwise the pool will load empty state on the next re-entry and the
    // user loses their open tabs even though the URLs never truly "went away".
    val tabsDir = java.io.File(context.filesDir, "browser_tabs")
    val draftTabs = java.io.File(tabsDir, "$fromDraft.json")
    if (draftTabs.exists()) {
        val realTabs = java.io.File(tabsDir, "$toReal.json")
        runCatching {
            if (!realTabs.exists()) {
                if (!draftTabs.renameTo(realTabs)) {
                    draftTabs.copyTo(realTabs, overwrite = false)
                    draftTabs.delete()
                }
            }
        }
    }
}

private fun ChatViewModel.copyRecursive(src: java.io.File, dst: java.io.File): Boolean = runCatching {
    if (src.isDirectory) {
        dst.mkdirs()
        src.listFiles()?.all { copyRecursive(it, java.io.File(dst, it.name)) } ?: true
    } else {
        src.copyTo(dst, overwrite = false)
        src.delete()
        true
    }
}.getOrDefault(false)
