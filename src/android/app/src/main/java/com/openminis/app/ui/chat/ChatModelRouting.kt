package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.service.SessionActivityTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// [FE-5 batch 7] Model selection / routing cluster extracted verbatim from
// ChatViewModel as extension functions (same pattern as ChatPromptAndTools).

fun ChatViewModel.selectGroup(groupId: String) {
    _selectedGroupId.value = groupId
    _selectedGroupName.value = providerRepository.group(groupId)?.name ?: ""
    // [T-recovery] Explicit user selection clears all health state —
    // choosing a group is an explicit "I want to work with this group"
    // signal (also how a re-authed member becomes usable again).
    groupRouter.clearHealth()
    // [T-per-message-load-balance] A group (re-)selection supersedes any
    // pending member pick from a previous selection.
    pendingEntryOverride = null
    val resolved = resolveProviderFromGroup(groupId)
    if (resolved) {
        // [T-per-message-load-balance] resolveProviderFromGroup already
        // advanced the rotation to the member now shown in the top bar —
        // pin it so THIS member serves the next new user turn (otherwise
        // the next send would immediately rotate past it and the member
        // the user just saw selected would never serve a message).
        // While streaming, switchModelAndRerun below already re-serves the
        // current turn on the resolved member; pinning would make it serve
        // the next message too, so only arm while idle.
        if (!_isStreaming.value) _activeEntryId.value?.let { pendingEntryOverride = it }
        persistBinding("""{"type":"group","groupId":"$groupId"}""")
        applyGroupSessionDefaults(groupId)
        // [switchModelAndRerun] Model-switch-during-streaming (Plan A).
        if (_isStreaming.value) switchModelAndRerun("switchModel-group")
    }
}

/** Select a specific entry within a group (keeps group selected). */
fun ChatViewModel.selectGroupEntry(groupId: String, entryId: String) {
    _selectedGroupId.value = groupId
    _selectedGroupName.value = providerRepository.group(groupId)?.name ?: ""
    // [T-recovery] Explicit user pick overrides any recovery/cooldown
    // policy — the user asked for THIS member, deliver it.
    groupRouter.clearHealth()
    val resolved = resolveProviderFromGroup(groupId, entryId)
    if (resolved) {
        // [T-per-message-load-balance] The user hand-picked THIS member. When
        // idle, it serves the NEXT new user turn (one-shot pick consumed by
        // rotateForNewTurn), then rotation resumes from it. When streaming,
        // switchModelAndRerun below already re-serves the current turn on the
        // pick — setting a pending override here would make it serve the next
        // message too (two turns in a row), so only arm it while idle.
        if (!_isStreaming.value) pendingEntryOverride = entryId
        persistBinding("""{"type":"group","groupId":"$groupId","lastEntryId":"$entryId"}""")
        applyGroupSessionDefaults(groupId)
        // [T-newchat-default-model-fallback-android] Record the actually-
        // resolved active entry as last-used (resolveProviderFromGroup may
        // fall back off a disabled member, so _activeEntryId is the truth).
        _activeEntryId.value?.let { providerRepository.lastUsedEntryId = it }
        // [switchModelAndRerun] Model-switch-during-streaming (Plan A).
        if (_isStreaming.value) switchModelAndRerun("switchModel-groupEntry")
    }
}

/** Select a specific model entry (bypasses group selection). */
fun ChatViewModel.selectEntry(entryId: String) {
    val config = providerRepository.config.value
    val entry = config.modelEntries.find { it.id == entryId } ?: return
    val instance = providerRepository.instance(entry.providerInstanceId) ?: return
    val apiKey = providerRepository.loadApiKey(instance.id) ?: return

    // Apply the new model's state + persisted binding. This runs in BOTH
    // the idle and the streaming cases (the streaming case additionally
    // cancels + restarts the loop on this provider via switchModelAndRerun).
    currentModel = entry.model
    _modelName.value = entry.model.displayName
    _providerName.value = instance.label.ifEmpty { entry.model.provider }
    _selectedGroupId.value = null
    _selectedGroupName.value = ""
    _activeEntryId.value = entry.id
    // [T-provider-key-roulette] Rotation happens inside ProviderFactory.create —
    // no call site touches the stored key directly.
    currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context)
    persistBinding("""{"type":"entry","entryId":"$entryId"}""")
    // [T-per-message-load-balance] Leaving the group for a direct entry pick
    // invalidates any pending in-group member override.
    pendingEntryOverride = null
    // [T-newchat-default-model-fallback-android] Remember this as the
    // global last-used model so the NEXT new chat (when no default group
    // is set) defaults back to it. Tier 2 of the new-chat fallback chain.
    providerRepository.lastUsedEntryId = entryId

    // [switchModelAndRerun] Model-switch-during-streaming (Plan A): the UI
    // picker is intentionally left active during streaming — a switch here
    // cancels the in-flight turn and re-answers the CURRENT user message
    // with the newly selected model (no more "UI shows B but the stream is
    // still calling A" split state).
    if (_isStreaming.value) switchModelAndRerun("switchModel-entry")
}

/**
 * [switchModelAndRerun] Model-switch-during-streaming (Plan A: cancel +
 * restart). Called by [selectEntry] / [selectGroup] / [selectGroupEntry]
 * AFTER the caller has already applied the new model's fields
 * (currentModel / _modelName / _providerName / _selectedGroup* /
 * _activeEntryId / currentProvider) and persisted the binding.
 *
 * This function cancels the in-flight agent loop, rolls back the
 * incomplete assistant turn, re-syncs DB + agentHistory to the last
 * committed user message, then restarts the loop on the newly-selected
 * provider so the CURRENT user message is answered by the new model.
 *
 * Note: enqueued prompts (_promptQueue) are intentionally left untouched —
 * they stay as dashed bubbles the user can retry after the switch; the
 * restart answers the current turn only.
 */
internal fun ChatViewModel.switchModelAndRerun(label: String) {
    AppLogger.info(
        ChatViewModel.TAG,
        "switchModelAndRerun($label): cancelling in-flight loop, restarting on " +
            "${currentProvider?.model?.displayName}",
    )
    // ── Phase 1: cancel current stream (light cancel — do NOT kick the
    // queue-drain tail; we restart in place). ──
    streamJob?.cancel()
    flushAllStreamingDeltas()
    ExecutionCoordinator.stopCurrentCommand(activeSessionId)
    SessionActivityTracker.clearToolRunning(com.openminis.app.service.ToolOutcome.Cancelled)
    SessionActivityTracker.setInactive(activeSessionId)
    if (isDraft && realSessionId.isNotEmpty() && activeSessionId != sessionId) {
        SessionActivityTracker.setInactive(sessionId)
        ExecutionCoordinator.stopCurrentCommand(sessionId)
    }

    // ── Phase 2: roll back the incomplete assistant turn in UI + history ──
    rollbackIncompleteTurn()

    // ── Phase 3: re-sync DB + agentHistory to the last committed user
    // message, then restart the loop on the (already-switched) provider. ──
    val provider = currentProvider ?: run {
        AppLogger.warning(ChatViewModel.TAG, "switchModelAndRerun: no currentProvider after switch — aborting restart")
        return
    }
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId
            val dbMessages = chatRepository.loadMessages(sid)
            val lastUserSortOrder = dbMessages.findLast { it.role == "user" }?.sortOrder
            if (lastUserSortOrder != null) {
                chatRepository.deleteMessagesAfter(sid, lastUserSortOrder + 1)
            }
            // Rebuild agentHistory from the trimmed DB so the retried loop
            // starts from committed context only (defense in depth against
            // any partial tool_result / assistant rows the cancelled loop
            // may have persisted).
            agentHistory.clear()
            toolLoopDetector.reset()
            val remaining = chatRepository.loadMessages(sid)
            for (entity in remaining) agentHistory.add(entity.toLLMMessage())
            // Drop stream-flush side-channel state for messages the rollback
            // removed, so no stale delta can resurrect on a kept bubble
            // (mirrors rerunFromToolBlock).
            val keptIds = _messages.value.mapTo(mutableSetOf()) { it.id }
            retainStreamFlushStates(keptIds)
            if (_streamingById.value.isNotEmpty()) {
                _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
            }
        } catch (e: Exception) {
            Log.w(ChatViewModel.TAG, "switchModelAndRerun: DB re-sync failed: ${e.message}")
        }
        // Restart the loop on the switched provider (mirrors retryFromMessage).
        _error.value = null
        _canResume.value = false
        AppLogger.info(ChatViewModel.TAG_STREAM, "$label _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true
        streamEpoch++
        var streamLaunched = false
        try {
            streamLaunched = runRerunStreamTail(provider, label)
        } finally {
            if (!streamLaunched) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "$label _isStreaming=false (setup aborted)")
                _isStreaming.value = false
            }
        }
    }
}

/** Persist the model binding to the DB session (no-op for draft sessions). */
internal fun ChatViewModel.persistBinding(bindingJson: String) {
    val sid = realSessionId.takeIf { it.isNotEmpty() } ?: return
    val modelId = currentModel?.id ?: return
    viewModelScope.launch {
        chatRepository.updateSessionBinding(sid, bindingJson, modelId)
    }
}

internal fun ChatViewModel.findModelEntry(modelId: String) =
    providerRepository.allVisibleEntries().find { it.model.id == modelId }

/**
 * Build the ordered list of fallback providers for the current group,
 * starting AFTER the primary provider in the member list and cycling around.
 * This ensures that models already tried (before the primary) are at the end,
 * not the beginning — so retry doesn't re-trigger the same fallback chain.
 */
/**
 * A fallback candidate = the resolved [provider] plus the model-group
 * [entryId] it was built from. Carrying the entryId (instead of re-finding
 * it later by modelId) is what makes fallback landing precise: a group can
 * hold several entries for the SAME modelId behind different provider
 * instances/endpoints (e.g. deepseek-v4-flash via a dead hub.oaifree.com
 * key + via api.deepseek.com). A naive `modelEntries.find { it.model.id ==
 * modelId }` would return the FIRST matching entry regardless of which
 * instance we actually used — corrupting the model picker highlight, the
 * provider label and the effective context window.
 * [P0-x-fallback-entry-precision]
 */
// [FE-5 route C] FallbackCandidate moved to top-level internal
// (AgentLoopState.kt) so the engine layer can reference it.

internal fun ChatViewModel.buildFallbackProviders(primaryProvider: LLMProvider): List<FallbackCandidate> {
    val groupId = _selectedGroupId.value ?: return emptyList()
    val config = providerRepository.config.value
    val group = config.modelGroups.find { it.id == groupId } ?: return emptyList()
    // [P0-fallback-anchor] Ordering delegated to GroupRouter.fallbackOrder —
    // anchors by the ACTUAL active entry id, not by model.id: a group can
    // hold several entries for the SAME modelId behind different
    // instances/endpoints (e.g. deepseek-v4-flash via a dead
    // hub.oaifree.com key + via api.deepseek.com), and matching by modelId
    // returns the FIRST such entry, which may sit earlier than the entry
    // actually in use — the chain would start from the wrong point and
    // even re-include the failing entry itself. The router returns pure
    // ordering; the filtering below (disabled instance / missing
    // credential / provider creation failure) stays here.
    val order = groupRouter.fallbackOrder(
        group = group,
        activeEntryId = _activeEntryId.value,
        primaryModelId = primaryProvider.model.id,
        modelIdOf = { entryId -> config.modelEntries.find { it.id == entryId }?.model?.id },
        // [T-recovery] cheapestFirst needs cost tier lookup to order the
        // fallback chain in ascending cost.
        costTierOf = { entryId -> config.modelEntries.find { it.id == entryId }?.costTier },
    )
    val result = mutableListOf<FallbackCandidate>()
    for (entryId in order) {
        // [T-recovery] Skip members currently cooling (429) / circuit-open
        // (repeated 5xx) / dead (401) — fallback must not re-try a member
        // the router just demoted; it only cycles HEALTHY candidates.
        if (!groupRouter.isUsable(entryId)) continue
        val entry = config.modelEntries.find { it.id == entryId } ?: continue
        val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
        if (!instance.isEnabled) continue
        val apiKey = providerRepository.loadApiKey(instance.id) ?: continue
        val p = try {
            ProviderFactory.create(instance, apiKey, entry.model, context)
        } catch (_: Exception) { continue }
        result.add(FallbackCandidate(p, entryId))
    }
    return result
}

/**
 * [T-per-message-load-balance] Advance the loadBalance rotation for ONE new
 * user turn. Called from exactly two places — [ChatViewModel.sendMessage]
 * (fresh send) and [drainQueuedPrompts] (each queued prompt is its own new
 * user turn) — NEVER from retry / rerun / resume paths: replaying an
 * existing turn must not advance the rotation.
 *
 * Semantics:
 *  - Only loadBalance groups with 2+ enabled members rotate; everything
 *    else is a no-op (fallback / cheapestFirst keep their own semantics).
 *  - The FIRST message of a session does not rotate: the session-binding
 *    resolution already picked a member (via group resolution or the user's
 *    explicit pick), so the new turn keeps it. From the second completed
 *    assistant turn onwards, each new message advances one step.
 *  - A one-shot user pick ([pendingEntryOverride], set by selectGroupEntry)
 *    overrides the rotation for exactly one turn, then rotation resumes
 *    from that member.
 *  - The anchor is the session's actually-active entry (NOT the global
 *    lastUsedEntryId): a restored old session can leave the global anchor
 *    pointing outside this group, which would make `(idx+1) % n` skip or
 *    clamp instead of advancing.
 *
 * On success this mirrors a member switch: currentModel / _modelName /
 * _providerName / _activeEntryId / currentProvider are rebuilt, and the
 * session binding's lastEntryId is re-persisted so reload keeps the new
 * member. Rotation state stays recoverable across restarts.
 */
internal fun ChatViewModel.rotateForNewTurn(label: String) {
    val groupId = _selectedGroupId.value ?: return
    val group = providerRepository.group(groupId) ?: return
    val currentEntryId = _activeEntryId.value
    // Consume a pending one-shot pick (set by selectGroupEntry) BEFORE the
    // first-message check: on a fresh session the pick already resolved the
    // member (pending == current, consuming is a no-op) — but consuming late
    // would let the pick leak into the SECOND message and serve it twice.
    val pick = pendingEntryOverride
    pendingEntryOverride = null
    // Keep the just-resolved member for the session's first message:
    // rotateForNewTurn runs BEFORE the new user row is appended, so any
    // assistant row already in the message list belongs to a PRIOR
    // completed turn. (Draft sessions start with an empty list.)
    if (_messages.value.none { it.role == "assistant" }) return
    val members = providerRepository.enabledMemberEntries(group)
    val targetId = groupRouter.nextLoadBalanceMember(
        group = group,
        currentEntryId = currentEntryId,
        pendingEntryId = pick,
        members = members,
    ) ?: return
    if (targetId == currentEntryId) return
    val targetEntry = members.firstOrNull { it.id == targetId } ?: return
    val instance = providerRepository.instance(targetEntry.providerInstanceId) ?: return
    val apiKey = providerRepository.loadApiKey(instance.id) ?: return
    currentModel = targetEntry.model
    _modelName.value = targetEntry.model.displayName
    _providerName.value = instance.label.ifEmpty { targetEntry.model.provider }
    _activeEntryId.value = targetEntry.id
    // [T-provider-key-roulette] Rotation happens inside ProviderFactory.create.
    currentProvider = ProviderFactory.create(instance, apiKey, targetEntry.model, context)
    persistBinding("""{"type":"group","groupId":"$groupId","lastEntryId":"$targetId"}""")
    AppLogger.info(
        "ChatVMRouting",
        "🔄LB $label rotate entry=$currentEntryId -> $targetId model=${targetEntry.model.id}",
    )
}

/**
 * Group members that fallback skipped (disabled instance / missing
 * credential / hidden entry), with reasons. Mirrors iOS
 * ModelGroupRouter.unavailableMembers: when fallback exhausts, the user
 * needs to know WHY the other group members never got tried — e.g. the
 * Claude subscription was logged out, so every Anthropic entry was
 * silently filtered and fallback kept cycling OpenAI-only.
 */
internal fun ChatViewModel.unavailableGroupMembers(): List<String> {
    val groupId = _selectedGroupId.value ?: return emptyList()
    val config = providerRepository.config.value
    val group = config.modelGroups.find { it.id == groupId } ?: return emptyList()
    val result = mutableListOf<String>()
    for (entryId in group.memberEntryIds) {
        val entry = config.modelEntries.find { it.id == entryId } ?: continue
        val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
        val label = instance.label.ifEmpty { entry.model.provider }
        val reason = when {
            entry.isHidden -> "Hidden"
            !instance.isEnabled -> "Disabled"
            providerRepository.loadApiKey(instance.id) == null -> "Not logged in"
            else -> continue
        }
        result.add("⚠️ ${entry.model.displayName} ($label): $reason")
    }
    return result
}
