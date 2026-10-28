package com.rikkaminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.rikkaminis.app.sandbox.ExecutionCoordinator
import kotlinx.coroutines.launch

// [FE-5 batch 8] Session-lifecycle entry cluster (loadSessionEntity /
// updateTitleAndCategory / promoteDraftIfNeeded / ensureSession /
// migrateDraftResources) extracted verbatim from
// ChatViewModel as extension functions.
//
// [meta-audit-1028 C1] The filesystem half of `migrateDraftResources` (the
// rename-or-merge per entry, previously the inline `copyRecursive`) now lives in
// `DraftResourceMigration.kt`, where the JVM can drive it against real
// directories.


/** T-chat-title-pill-edit: load the persisted [ChatSessionEntity] for the
 *  current session so the shared edit-title sheet (reused from the session
 *  list) can be opened from the in-chat title pill. Returns null for
 *  drafts that haven't been persisted yet. */
suspend fun ChatViewModel.loadSessionEntity(): com.rikkaminis.app.data.db.ChatSessionEntity? {
    val sid = realSessionId.ifEmpty { return null }
    return runCatching { chatRepository.getSession(sid) }.getOrNull()
}

/** T-chat-title-pill-edit: update title + category from the in-chat
 *  edit sheet. Mirrors SessionListViewModel.updateTitleAndCategory but
 *  also refreshes the local StateFlows so the pill updates immediately
 *  without waiting for a session reload. */
fun ChatViewModel.updateTitleAndCategory(title: String, category: String?) {
    val sid = realSessionId.ifEmpty { return }
    // [audit-0917] Persist the SAME value that is shown locally. The DB used to
    // get the raw (possibly blank) title while the pill displayed the "New
    // Chat" fallback — a blank title survived a reload as an empty row label.
    val effective = title.ifBlank { "New Chat" }
    viewModelScope.launch {
        chatRepository.updateSessionTitleAndCategory(sid, effective, category)
        _sessionTitle.value = effective
        _sessionCategory.value = category
    }
}

/** [feat/drawer-context-menu] Manual title regeneration from the drawer
 *  context menu. The auto path (generateSessionTitleIfNeeded) skips when a
 *  title is already set and stops after TITLE_MAX_ATTEMPTS — both guards
 *  exist to stop runaway auto-retry, not user intent. An explicit regenerate
 *  resets the attempt counter and forces past the title-already-set guard;
 *  the in-flight guard stays (no concurrent dispatch). Acts on the CURRENT
 *  session's ViewModel only — callers must not wire it for non-current
 *  drawer rows (the VM has no per-session regeneration path).
 *  ponytail: 无 loading 态反馈，靠标题 StateFlow 刷新可见 | 天花板: 用户点了没反应感知 | 升级触发: 用户报"点了重新生成没变化" */
fun ChatViewModel.regenerateSessionTitle() {
    if (isDraft || realSessionId.isEmpty()) return
    titleGenerationAttempts = 0
    generateSessionTitleIfNeeded(force = true)
}

/**
 * [promote-draft-on-new-chat] If the user is on a draft with unsent text
 * and taps "New Chat", promote the current draft to a real session so the
 * typed text isn't silently lost. The slot is freed synchronously so the
 * next `ComposerDraftStore.nextDraftId` returns a fresh id for the new
 * draft; the DB row + title write happens asynchronously in viewModelScope
 * (local DB, ~50ms — no need to block the UI).
 *
 * [dv24-b3] The body is a per-session durable artifact, not a 50-char title
 * prefix. Promotion used to write ONLY `sessions.title = text.take(50)` and
 * then drop both in-memory copies — and a promoted row has no `messages`
 * rows, so `ChatHistoryDrawer`'s `pinnedAt / messageCounts` filter hid it,
 * `ChatViewModel.cleanupIfEmptyOnExit` deleted it the moment the user opened
 * and left it, and `ChatRepository.deleteEmptySessions` swept it at the next
 * cold start. Net effect of tapping "New Chat" with a long draft: the text
 * was destroyed, 100% of the time. The fix persists the captured text as the
 * promoted session's first `user` row (`appendMessage`, the same single
 * source `send()` uses — it also writes `last_message` + `updated_at`), so
 * the row carries a message count (never swept), is visible in the drawer and
 * re-opens with the full text in the transcript.
 *
 * The composer copy is cleared only AFTER that durable write lands, and it is
 * deliberate: keeping it (as an earlier revision of this fix did) made the
 * returned-to session show the same text twice (transcript bubble + composer)
 * and invited a double send. Ordering is the safety net — a cancellation or a
 * DB failure in between leaves the text in the composer, so the two copies are
 * never both destroyed.
 *
 * The row is intentionally an *unanswered user turn*: that is what
 * `InterruptedTailDetector` Case D calls it, so the session surfaces the
 * Resume affordance (send it for real) instead of a phantom "No messages yet"
 * chat. `_messages` is updated in place because the VM is cached in
 * `ChatViewModelStore` and would otherwise keep rendering the pre-promotion
 * (empty) transcript until the next process start.
 *
 * [dv25-B08] Two more things promotion has to own, both on the same "the user's
 * input must survive" line:
 *
 *  - **Attachments** ride along. The pre-dv25 body was persisted as
 *    `buildUserPartsJson(text, emptyList())`, so a draft that carried images /
 *    files promoted a text-only row and the user's staged uploads evaporated when
 *    the composer was cleared. The promotion now takes the SAME single source
 *    `send()` takes (`prepareUserAttachments` → mediaRef parts + attached-files
 *    inventory), and the in-memory attachment list is cleared with the text.
 *  - **The newest text**, not the last committed one. `_inputText` is committed per
 *    keystroke for ordinary typing, but a >8-char IME burst is buffered in the
 *    composer for 150 ms; reading only the ViewModel string promoted the body as it
 *    was BEFORE the burst. `composerTextForPromotion` prefers the pending snapshot.
 *
 * [meta-audit-1028 C1] Two more entry conditions, both on the same line:
 *
 *  - **An attachments-only draft is promotable.** The text guard alone skipped it
 *    (the attachments live only in this ViewModel's `_attachments`, so nothing
 *    survived the abandoned draft) while `send()` accepts exactly that message;
 *    the row is then labelled by the first attachment's file name.
 *  - **The composer copies are cleared on the main thread and only for the
 *    snapshot captured here.** The IO half can take hundreds of milliseconds, so
 *    anything typed or attached during it belongs to the NEXT draft.
 *
 * Returns true when promotion was triggered — the caller should let
 * onNewChat proceed (the slot is already freed either way).
 */
fun ChatViewModel.promoteDraftIfNeeded(): Boolean {
    if (!isDraft || realSessionId.isNotEmpty()) return false
    // [dv25-B08] A second tap while the first promotion is still running must not
    // capture the same draft again: it would append a SECOND user row for the same
    // body. Both New-Chat entrances ignore the return value, so returning false
    // here only says "no new promotion was triggered" — the caller still navigates.
    if (promoteInFlight) return false
    // [meta-audit-1028 C1/F-01] Read the composer's staged uploads and BOTH text
    // copies BEFORE the guard: an attachments-only draft is valid input (`send()`
    // takes it verbatim, `shouldSkipCleanup` has the matching `hasAttachments`
    // bit), and the two New-Chat entrances ignore this return value and navigate
    // anyway — so the old `if (text.isBlank()) return false` dropped a draft that
    // held only images/files, with the attachments living nowhere but this
    // ViewModel's memory.
    val stagedAttachments = _attachments.value
    val committedAtCapture = _inputText.value
    val pendingAtCapture = _pendingComposerText.value
    // [dv25-B08] `_inputText` commits ordinary typing per keystroke but buffers a
    // large IME burst (voice dictation / pinyin) for 150 ms; the pending snapshot
    // IS the field's live text, so it is the newest copy of what the user typed.
    val text = composerTextForPromotion(pendingAtCapture, committedAtCapture)
    if (!hasPromotableInput(text, stagedAttachments.size)) return false
    // Captured before `ensureSession()`: that call renames the ViewModel onto the
    // real id and the draft slot is released right below.
    val draftId = sessionId

    // [dv25-B08] Claim the session BEFORE anything is destroyed. `ChatScreen`'s
    // onDispose fires `cleanupIfEmptyOnExit()` on the very next frame (the top-bar
    // New Chat navigates immediately, the drawer path after its 200 ms settle), and
    // in the window between `ensureSession()` — which inserts the row but no
    // message — and the `appendMessage()` below, that sweep read
    // `messageCount(sid) == 0`, deleted the brand-new row and called
    // `ChatViewModelStore.release(sid)`, which cancels THIS coroutine after the
    // composer copy is already gone. The bit is a session-ownership claim, not a
    // grace period: it is cleared in `finally`, so a failed promotion releases it.
    promoteInFlight = true

    // Free the draft slot synchronously — the text is captured in `text` and still
    // sits in the composer, so nextDraftId may mint a fresh ID before the
    // navigation fires without anything being destroyed.
    //
    // [dv25-B08] This ordering is deliberate and NOT the "clear after the durable
    // write" shape used for the in-memory copies below: the slot doubles as the id
    // allocator for the next New Chat (`ComposerDraftStore.nextDraftId` hands back an
    // existing slot), so a deferred clear would make the next draft reuse this
    // draft's id — and the deferred clear would then wipe ITS text. Durability
    // against cancellation is provided by `promoteInFlight` + the restore below,
    // not by holding the slot hostage.
    com.rikkaminis.app.data.ComposerDraftStore.clearDraft(context, draftId)

    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        var persisted = false
        try {
            val sid = ensureSession()
            if (sid.isEmpty()) return@launch
            val title = promoteTitleForDraft(text, stagedAttachments.firstOrNull()?.fileName)
                // Same fallback the shared title editor applies (`updateTitleAndCategory`):
                // a blank title must not reach the row label.
                .ifBlank { "New Chat" }
            chatRepository.updateSessionTitleAndCategory(sid, title, null)
            // [dv25-B08] Attachments take the SAME single-source path `send()` uses:
            // copied into the promoted session's media dir, with their mediaRef parts
            // riding the transcript row (so the bubble re-renders after a reload
            // instead of collapsing to a bodyless text bubble).
            val prepared = prepareUserAttachments(stagedAttachments, sid)
            // The transcript row is the durable home for the body: `appendMessage`
            // also refreshes `last_message` + `updated_at`, which is what makes the
            // row visible (and un-sweepable) without a second write.
            val promoted = chatRepository.appendMessage(
                sid,
                "user",
                buildUserPartsJson(text, prepared.mediaRefPartsJson, prepared.attachedFilesXml),
            )
            // [meta-audit-1028 C1/F-04] Everything above is IO; everything below is
            // Compose state. The state writes are hopped onto the main thread, and
            // each one is scoped to WHAT THIS PROMOTION CAPTURED: the IO half can
            // take tens to hundreds of milliseconds (attachment scaling + disk
            // writes), and the pre-fix body unconditionally blanked the composer at
            // the end of it — discarding whatever the user typed or attached in the
            // meantime, on a screen they can still see. `_messages` is a
            // read-modify-write as well, so doing it off the main thread raced the
            // streaming writers that publish on it.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                _sessionTitle.value = title
                _messages.value = _messages.value + ChatMessage(
                    id = promoted.id,
                    role = "user",
                    content = text,
                    imageUris = prepared.imageUris,
                    attachmentNames = prepared.attachmentNames,
                    attachmentUris = prepared.nonImageUris,
                )
                // Only now are the composer copies redundant. See the KDoc above.
                // Each copy is dropped only if it still holds the snapshot captured
                // on entry — a newer keystroke, a newer pending burst or an extra
                // attachment belongs to the next draft, not to this promotion.
                if (_inputText.value == committedAtCapture) _inputText.value = ""
                if (_pendingComposerText.value == pendingAtCapture) publishPendingComposerText(null)
                val stagedIds = stagedAttachments.mapTo(HashSet()) { it.id }
                if (stagedIds.isNotEmpty()) {
                    val current = _attachments.value
                    val remaining = current.filterNot { it.id in stagedIds }
                    if (remaining.size != current.size) _attachments.value = remaining
                }
                persisted = true
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            // Termination path: a cancelled scope must stay cancelled (the restore
            // below still runs in `finally`).
            throw c
        } catch (t: Throwable) {
            com.rikkaminis.app.logging.AppLogger.warning(
                "ChatViewModel",
                "promoteDraftIfNeeded failed for $draftId: ${t.message}",
            )
        } finally {
            promoteInFlight = false
            if (!persisted) {
                // The durable write did not land: hand the text back to the draft
                // slot so the next New Chat / process start still finds it.
                //
                // Two cases, and they need different targets:
                //
                //  - the slot is still OURS (the promotion failed before the
                //    navigation minted a new draft): writing back under [draftId]
                //    restores the text exactly where it was.
                //  - the slot has already been re-claimed by the NEW draft the
                //    navigation opened (the slot doubles as the id allocator, see
                //    the clearDraft comment above): writing under [draftId] would
                //    be dropped by the store's stale-id rule, and the text would
                //    be lost with the abandoned ViewModel. In that case the text
                //    goes to the CURRENT owner — but only if that owner is still
                //    empty, so a draft the user has already typed into is never
                //    overwritten.
                val currentOwner = com.rikkaminis.app.data.ComposerDraftStore
                    .activeDraftIds(context)
                    .firstOrNull()
                val restoreTarget = currentOwner
                    ?.takeIf {
                        com.rikkaminis.app.data.ComposerDraftStore
                            .restoreText(context, it)
                            .isEmpty()
                    }
                    ?: draftId
                com.rikkaminis.app.data.ComposerDraftStore.saveText(context, restoreTarget, text)
            }
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
    // [feat/hermes-tier1] Draft materialised a NEW session id: re-key the
    // frozen system prompt cache so the prompt built for the draft (if any)
    // is not silently reused under the real id. Session-scoped anchors are
    // the cache's invalidation contract.
    invalidateSystemPromptCache()
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
        // [da1030-A ISSUE-01] Same hop for the pinned WebApp shortcuts. A
        // shortcut created from this chat while it was still a draft froze
        // `__new__<uuid>` into both scope columns (AddToHomeSheet writes
        // `source.sessionId`); `migrateDraftResources` above just moved the
        // bytes to the real id and deleted the draft directory, so
        // WebAppPathResolver.resolveSession() resolves `htmlPath` under a path
        // that no longer exists and the launcher icon reports "Source
        // Missing". Contained on purpose: a failed DB write must not abort the
        // promotion (the typed text is already captured in the draft slot).
        runCatching {
            (context.applicationContext as? com.rikkaminis.app.MinisApp)
                ?.webAppShortcutRepository
                ?.renameSessionShortcuts(fromDraft = sessionId, toReal = session.id)
        }
        // [da1031-B ISSUE-02 裂缝B] Same hop for the session-scoped offload
        // permission verdicts (sessionGrants / sessionDenials). A tool the user
        // allowed on this draft chat was keyed under `__new__<uuid>`; after the
        // promotion nothing resolves that key any more, so the decision was
        // silently dropped and the same tool asked again under the real id.
        // Contained like the shortcut hop above: a failure here must not abort
        // the promotion.
        runCatching {
            com.rikkaminis.app.offload.OffloadPermissionManager
                .renameSessionGrants(fromDraft = sessionId, toReal = session.id)
        }
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

    // [meta-audit-1028 C1/F-03] "Nothing to move" and "could not read" are
    // different answers: an unlistable subdirectory leaves the draft tree in place
    // (it is one of the reasons the `leftover` check below exists), it must not be
    // reported as a clean migration.
    var complete = true
    listOf("attachments", "offloads", "workspace", "browser").forEach { subdir ->
        val src = java.io.File(draftBase, subdir)
        if (!src.isDirectory) return@forEach
        val dst = java.io.File(realBase, subdir).apply { mkdirs() }
        val children = src.listFiles()
        if (children == null) {
            complete = false
            android.util.Log.w("ChatViewModel",
                "migrateDraftResources: cannot list ${src.absolutePath} — draft copy kept")
            return@forEach
        }
        children.forEach { child ->
            val target = java.io.File(dst, child.name)
            // [meta-audit-1028 C1/F-03] `migrateDraftEntry` is the whole decision:
            // rename when the name is free, MERGE when the real session already has
            // that name (the pre-fix `!target.exists() && !child.renameTo(target)`
            // skipped both branches in exactly that case), and `false` whenever
            // anything is still sitting in the source — including a directory that
            // could not be read, which the old `copyRecursive` folded into success.
            val moved = runCatching { migrateDraftEntry(child, target) }.getOrElse { t ->
                android.util.Log.w("ChatViewModel",
                    "migrateDraftResources: failed to move ${child.absolutePath} -> ${target.absolutePath}: ${t.message}")
                false
            }
            // [meta-audit-1028 C1/F-03] The return value used to be dropped, so a
            // failed entry was only ever noticed by the `leftover` check at the end.
            // Name the entry: the leftover line says the draft is kept, this says why.
            if (!moved) {
                complete = false
                android.util.Log.w("ChatViewModel",
                    "migrateDraftResources: ${child.absolutePath} stays in the draft directory " +
                        "(name already present under ${target.absolutePath} with different " +
                        "content, or the copy failed)")
            }
        }
    }
    // [audit-0917] Only drop the draft tree when nothing failed to migrate.
    // The old unconditional deleteRecursively() destroyed exactly the files
    // whose move had just failed — the user's attachment was logged as
    // "failed to move" and then deleted anyway.
    val leftover = runCatching { draftBase.walkTopDown().any { it.isFile } }.getOrDefault(true)
    if (!leftover && complete) {
        runCatching { draftBase.deleteRecursively() }
    } else {
        android.util.Log.w(
            "ChatViewModel",
            "migrateDraftResources: keeping ${draftBase.absolutePath} — un-migrated file(s) remain")
    }

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
            } else {
                // [audit-0917] The real tab file already exists, so the draft
                // file was left behind forever — a stale __new__* entry in
                // browser_tabs that nothing ever prunes. The real file wins
                // (it is the live session's state); drop the draft copy.
                draftTabs.delete()
            }
        }
    }
}
