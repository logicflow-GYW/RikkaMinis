package com.rikkaminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.rikkaminis.app.agent.runtime.AgentRunEvent
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.provider.LLMProvider
import com.rikkaminis.app.provider.ProviderFactory
import com.rikkaminis.app.sandbox.ExecutionCoordinator
import com.rikkaminis.app.service.SessionActivityTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// [FE-5 batch 7] Model selection / routing cluster extracted verbatim from
// ChatViewModel as extension functions (same pattern as ChatPromptAndTools).

/**
 * [feat/compact-model-pin-1005] Prepend the user-pinned compaction model
 * (Settings → Runtime Limits → 压缩模型) to the compaction attempt chain as
 * head, dropping any duplicate — when the pin IS the active member or a
 * group fallback, the chain it would have produced is kept unchanged
 * (prepend-then-dedup: the pinned occurrence is the one that survives, but
 * positions relative to the rest are identical because a duplicate removed
 * from the tail re-tightens onto the pinned head slot).
 *
 * Pure ordering; budgets, the 120s chain deadline, the truncation guard and
 * the "compaction results are never recorded into the group router" rule
 * all live at the call site unchanged. A null/blank pin or an unknown
 * entryId yields the input chain unchanged (stale pins degrade silently to
 * follow-the-session; the Settings row shows the ⚠ stale label).
 *
 * The pin rides the FRONT of the chain regardless of its
 * declaresNoEffortTiers value: the user picked this model deliberately, and
 * per-candidate budgets already give cannot-silence members 60s — the pin
 * only changes WHO is tried first, never the failure semantics.
 *
 * Top-level (not a ChatViewModel extension) so JVM tests call it without
 * instantiating the VM — same pattern as [ordersCompactionCandidates].
 *
 * ponytail: pin 只重排不建拓扑（解析复用组内候选或全局 entry 过滤，不新建
 * 健康视图/组） | 天花板: pin 指向的成员如果被 GroupRouter 冷却/熔断，它
 * 照样被过滤掉（用户钉住≠绕过健康策略——刻意） | 升级触发: 用户报告"我钉的
 * 模型冷却期间压缩总是落到旧链"且希望冷却期跳过而非换链时，再谈 pin 专属
 * 冷却旁路
 */
internal fun prependCompactionPin(
    pinEntryId: String?,
    activeEntryId: String?,
    active: LLMProvider,
    fallbacks: List<FallbackCandidate>,
    resolvePin: (String) -> LLMProvider? = { null },
): List<Pair<LLMProvider, String?>> {
    val id = pinEntryId?.trim().orEmpty()
    if (id.isEmpty()) return ordersCompactionCandidates(active, fallbacks)
    if (id == activeEntryId) {
        // Pin IS the active member: honor the pin by leading the chain with
        // the active slot, deduped from the reordered remainder — the user's
        // explicit pick outranks the quiet-first demotion the catalog would
        // apply (pin = local override, not a re-derivation).
        val base = ordersCompactionCandidates(active, fallbacks)
        val head = base.firstOrNull { it.second == null } ?: return base
        return listOf(head) + base.filter { it !== head }
    }
    val pinned = fallbacks.firstOrNull { it.entryId == id }
    if (pinned != null) {
        val rest = ordersCompactionCandidates(
            active,
            fallbacks.filterNot { it.entryId == id },
        )
        return listOf(pinned.provider to id) + rest
    }
    // Pin is outside the session's group (or the session is in direct-entry
    // mode): resolve it globally — same enable/credential filters as
    // [buildFallbackProviders], via the injected resolver. This is what
    // makes the pin work in BOTH modes (group members AND a model picked
    // straight from a provider).
    val resolved = resolvePin(id) ?: return ordersCompactionCandidates(active, fallbacks)
    return listOf(resolved to id) + ordersCompactionCandidates(active, fallbacks)
}

fun ChatViewModel.selectGroup(groupId: String) {
    // [T-recovery] Explicit user selection clears all health state —
    // choosing a group is an explicit "I want to work with this group"
    // signal (also how a re-authed member becomes usable again).
    //
    // [deep-audit-1047 B-04] KEPT AHEAD of the resolution on purpose: clearing
    // health is what makes a cooling / circuit-open member usable again, so it
    // has to happen BEFORE the router is asked to pick one — moving it into the
    // success branch would make a fully-demoted group unrecoverable by
    // re-selection. (Health is routing state, not the display state this fix is
    // about.)
    groupRouter.clearHealth()
    // [deep-audit-1047 B-04] Everything below is display + execution state and
    // is written only once the group actually resolved. When it does not (every
    // member disabled or credential-less, or a group holding nothing but System
    // voice sentinels), the previous selection is left exactly as it was:
    // writing the group id/name first used to leave the top bar showing group B
    // while the session kept running on group A's provider + binding.
    val resolved = resolveProviderFromGroup(groupId)
    if (resolved) {
        _selectedGroupId.value = groupId
        _selectedGroupName.value = providerRepository.group(groupId)?.name ?: ""
        // [T-per-message-load-balance] A group (re-)selection supersedes any
        // pending member pick from a previous selection.
        pendingEntryOverride = null
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
    // [T-recovery] Explicit user pick overrides any recovery/cooldown
    // policy — the user asked for THIS member, deliver it. (Ahead of the
    // resolution for the same reason as in [selectGroup]: demoted members
    // become usable again only because the health map was just cleared.)
    groupRouter.clearHealth()
    val resolved = resolveProviderFromGroup(groupId, entryId)
    // [deep-audit-1047 B-04] ONE id for every consumer of this pick.
    // `resolveProviderFromGroup` falls back off a member it cannot use
    // (disabled instance / no credential), so the entry the user asked for is
    // NOT necessarily the entry that is now serving: `_activeEntryId` is the
    // truth. Persisting the raw `entryId` used to bind the session — and the
    // next `rotateForNewTurn` with it — to a member that was just skipped.
    val plan = planGroupSelect(
        resolved = resolved,
        resolvedId = _activeEntryId.value,
        requestedId = entryId,
    )
    if (plan.applyUi) {
        val resolvedEntryId = plan.effectiveEntryId ?: return
        _selectedGroupId.value = groupId
        _selectedGroupName.value = providerRepository.group(groupId)?.name ?: ""
        // [T-per-message-load-balance] The user hand-picked THIS member. When
        // idle, it serves the NEXT new user turn (one-shot pick consumed by
        // rotateForNewTurn), then rotation resumes from it. When streaming,
        // switchModelAndRerun below already re-serves the current turn on the
        // pick — setting a pending override here would make it serve the next
        // message too (two turns in a row), so only arm it while idle.
        if (!_isStreaming.value) pendingEntryOverride = resolvedEntryId
        persistBinding("""{"type":"group","groupId":"$groupId","lastEntryId":"$resolvedEntryId"}""")
        applyGroupSessionDefaults(groupId)
        // [T-newchat-default-model-fallback-android] Record the actually-
        // resolved active entry as last-used (resolveProviderFromGroup may
        // fall back off a disabled member, so _activeEntryId is the truth).
        providerRepository.lastUsedEntryId = resolvedEntryId
        // [switchModelAndRerun] Model-switch-during-streaming (Plan A).
        if (_isStreaming.value) switchModelAndRerun("switchModel-groupEntry")
    }
}

/** Select a specific model entry (bypasses group selection). */
fun ChatViewModel.selectEntry(entryId: String) {
    val config = providerRepository.config.value
    val entry = config.modelEntries.find { it.id == entryId } ?: return
    // [deep-audit-1047 B-04] The direct-pick path used to re-derive
    // "instance exists and has a key" inline and stopped there, so an entry
    // whose provider instance was DISABLED but still carried a key was
    // activated anyway — the disable switch was bypassable from the picker and
    // from `HeadlessChatRunner.selectModel`. It now goes through the same
    // single decision point the restore / load paths already use.
    val instance = providerRepository.instance(entry.providerInstanceId)
    val apiKey = instance?.let { providerRepository.loadApiKey(it.id) }
    val decision = decideSessionModelResolution(entry, instance, apiKey)
    if (decision !is SessionModelResolution.Use) {
        if (decision is SessionModelResolution.Unusable) {
            logSessionModelUnusable("selectEntry", entry.id, decision.reason)
        }
        return
    }

    // Apply the new model's state + persisted binding. This runs in BOTH
    // the idle and the streaming cases (the streaming case additionally
    // cancels + restarts the loop on this provider via switchModelAndRerun).
    // [audit-0917] Resolve the provider BEFORE mutating any field: if
    // ProviderFactory.create throws (bad key / dead endpoint), the old order
    // left the model fields already flipped while currentProvider was still the
    // previous one - a UI/provider split state. Doing the throwing call first
    // means a failure leaves the whole selection untouched.
    // [deep-audit-1044 B-03] Resolved through the single enriched exit, so the
    // provider and the `currentModel` snapshot agree AND carry models.dev
    // capabilities (`entry.model` is the raw base + overrides only).
    val effectiveModel = providerModelFor(entry)
    val newProvider = ProviderFactory.create(decision.instance, decision.apiKey, effectiveModel, context)
    currentModel = effectiveModel
    _modelName.value = effectiveModel.displayName
    _providerName.value = decision.instance.label.ifEmpty { effectiveModel.provider }
    _selectedGroupId.value = null
    _selectedGroupName.value = ""
    _activeEntryId.value = entry.id
    // [T-provider-key-roulette] Rotation happens inside ProviderFactory.create —
    // no call site touches the stored key directly.
    currentProvider = newProvider
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
 * [dv24-A05] The fallback a session uses when it has NO usable model binding.
 *
 * `loadSession()` resolves a session's model in three priorities: (1) the
 * persisted `model_binding`, (2) the persisted `model_id`, (3) the default
 * group and then the new-chat default. Clearing the binding therefore means
 * "run priority 3 now", not "pick one arbitrary entry".
 *
 * The config surface (`minis-config set session.primaryModel ""`) has to run
 * the same fallback on the RESIDENT view model, otherwise the chat keeps
 * showing the pin that was just cleared while the row already says "no
 * binding" — the two disagree and the next picker tap writes the stale pin
 * back.
 *
 * `applyNewChatDefaultModel` alone is not the same fallback: it skips the
 * default GROUP (priority 3's first half). This is the shared entry both
 * sides use.
 */
internal fun ChatViewModel.applyDefaultModelFallback(): Boolean {
    val defaultGroupId = providerRepository.defaultPrimaryGroupId
    if (defaultGroupId != null) {
        val resolved = resolveProviderFromGroup(defaultGroupId)
        if (resolved) {
            _selectedGroupId.value = defaultGroupId
            return true
        }
    }
    return applyNewChatDefaultModel()
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
    // [audit-0914] Announce the cancel to the run reducer BEFORE the loop
    // unwinds. The unwinding loop emits RunFinalized from its `finally`, and
    // without a preceding termination signal the reducer is still in
    // CALLING_MODEL, so that event is rejected ("T7-D reducer REJECTED …
    // requires FINALIZING"). Both occurrences in the 2026-09-14 logs sit
    // immediately after this function. Guarded on a live job: a
    // UserCancelled against an idle reducer parks it in FINALIZING, which
    // would then refuse the next RunStarted.
    if (streamJob?.isActive == true) {
        traceObserver.t7Reduce(AgentRunEvent.UserCancelled("switch_model"))
    }
    // [dv29-B01] Also kill the predecessor's SETUP shell: sendMessage/resume
    // hold their whole setup in sendShellJob, and only `streamJob?.cancel()`
    // was called here — the old shell could survive this claim and reach its
    // own `streamJob = launch`, i.e. two live streams under one _isStreaming.
    sendShellJob?.cancel()
    streamJob?.cancel()
    flushAllStreamingDeltas()
    ExecutionCoordinator.stopCurrentCommand(activeSessionId)
    SessionActivityTracker.clearToolRunning(com.rikkaminis.app.service.ToolOutcome.Cancelled)
    SessionActivityTracker.setInactive(activeSessionId)
    if (isDraft && realSessionId.isNotEmpty() && activeSessionId != sessionId) {
        SessionActivityTracker.setInactive(sessionId)
        ExecutionCoordinator.stopCurrentCommand(sessionId)
    }

    // ── Phase 2: roll back the incomplete assistant turn in UI + history ──
    rollbackIncompleteTurn()

    // ── Phase 3: claim synchronously, then re-sync DB + agentHistory to the
    // last committed user message and restart the loop on the (already
    // switched) provider. ──
    val provider = currentProvider ?: run {
        AppLogger.warning(ChatViewModel.TAG, "switchModelAndRerun: no currentProvider after switch — aborting restart")
        return
    }
    // [dv29-B01] The claim is published HERE, before any suspension point: it
    // used to be written inside the coroutine below, i.e. only after three
    // DB round-trips. During that window `_isStreaming` read false (the
    // cancelled predecessor's finally had already cleared it), so the entry
    // guards of send/retry let a second turn in, and cancelStream() had no
    // handle to stop this one: the user's Stop was a no-op and the turn came
    // back to life once the DB work finished.
    //
    // [T-claim-token-vs-stale-finally] Mint the claim token BEFORE the flag
    // flips. This path is the one that failed in the field (2026-10-12
    // 14:40:12): the cancel() above unwinds the predecessor while this
    // claim is still running, and the epoch snapshot below lands too late
    // to stop it. The token lands first and makes that finally a no-op.
    streamingClaimToken = Any()
    _isStreaming.value = true
    streamEpoch++
    // [T-stale-finally-vs-new-claim] Publish the claim SYNCHRONOUSLY here
    // (not inside runRerunStreamTail, which runs after the system-prompt
    // build): the epoch gate must already cover the whole claim window
    // starting at _isStreaming=true.
    streamingClaimEpoch = streamEpoch
    val switchEpoch = streamingClaimEpoch
    val claimToken = streamingClaimToken
    AppLogger.info(ChatViewModel.TAG_STREAM, "$label _isStreaming=true (sync, sid=$activeSessionId)")
    _canResume.value = false

    // [dv29-B01] Hold the setup shell in sendShellJob so cancelStream() can
    // kill the DB re-sync window (before this, Stop reached only `streamJob`,
    // which is still null here).
    sendShellJob = viewModelScope.launch(Dispatchers.IO) {
        var streamLaunched = false
        try {
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
                val remaining = chatRepository.loadMessages(sid)
                agentHistory.clear()
                toolLoopDetector.reset()
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
                // [audit-0917] Fail-closed: the re-sync half-applied (the DB trim
                // and/or the history rebuild may be partial). Restarting the loop on a
                // half-synced history would silently answer the CURRENT user message
                // with wrong or empty context - surface the failure and leave the turn
                // unanswered instead of guessing.
                Log.w(ChatViewModel.TAG, "switchModelAndRerun: DB re-sync failed: ${e.message}")
                _error.value = "Model switch failed: ${e.message}"
                return@launch
            }
            // Restart the loop on the switched provider (mirrors retryFromMessage).
            _error.value = null
            streamLaunched = runRerunStreamTail(provider, label, switchEpoch, claimToken)
        } finally {
            // [dv29-B01] The claim moved out of this coroutine, so every early
            // return above (re-sync failure) must release it — otherwise
            // _isStreaming stays true forever and every later send is refused.
            if (!streamLaunched) {
                // [T-stale-finally-vs-new-claim] Only clear under our own claim.
                if (switchEpoch == streamingClaimEpoch && claimToken === streamingClaimToken) {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "$label _isStreaming=false (setup aborted)")
                    _isStreaming.value = false
                } else {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "$label _isStreaming=false SKIPPED (superseded)")
                }
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
            // [deep-audit-1044 B-03] Fallback providers answer the same
            // capability questions as the primary, so they get the enriched
            // model too (raw `entry.model` lost vision + effort tiers here).
            ProviderFactory.create(instance, apiKey, providerModelFor(entry), context)
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
    // [deep-audit-1047 B-04] Same credential filter as
    // [resolveProviderFromGroup]: a member with no API key must not be picked
    // by the rotation only to abort the whole rotation at
    // `loadApiKey(...) ?: return` below (which silently froze the group on the
    // member it happened to land on, skipping usable members behind it).
    val members = usableGroupMembers(providerRepository.enabledMemberEntries(group))
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
    // [deep-audit-1044 B-03] Rotated-in member takes the same enriched path as
    // an explicit pick (raw base model = no modalities / effort tiers on the wire).
    val effectiveModel = providerModelFor(targetEntry)
    // [meta-audit-1028 F-06] The throwing call comes FIRST, and its outcome is a
    // value ([planTurnRotation]). These four fields used to be published BEFORE
    // `ProviderFactory.create`, so a creation failure left the top bar / picker /
    // `_activeEntryId` claiming the rotated-in member while `currentProvider`
    // still answered with the previous one — rotation runs unattended on every
    // message, so the split state could persist for the rest of the session.
    // The direct-pick path fixed exactly this shape in `[audit-0917]`; rotation
    // never got the same treatment.
    var created: LLMProvider? = null
    var createError: String? = null
    try {
        created = ProviderFactory.create(instance, apiKey, effectiveModel, context)
    } catch (e: Exception) {
        createError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }
    val plan = planTurnRotation(
        current = SessionModelSelection(
            modelId = currentModel?.id,
            modelName = _modelName.value,
            providerName = _providerName.value,
            activeEntryId = currentEntryId,
            selectedGroupId = groupId,
            selectedGroupName = _selectedGroupName.value,
            // The one-shot pick was consumed above on purpose: it armed THIS
            // turn, and re-arming it after an abort would retry a creation that
            // just failed on every following turn.
            pendingEntryOverride = null,
        ),
        targetEntryId = targetEntry.id,
        targetModelId = effectiveModel.id,
        targetModelName = effectiveModel.displayName,
        targetProviderName = instance.label.ifEmpty { effectiveModel.provider },
        created = created,
        createError = createError,
    )
    if (!plan.applied) {
        AppLogger.warning(
            "ChatVMRouting",
            "🔄LB $label rotate $currentEntryId -> $targetId ABORTED, session keeps " +
                "the current member: ${plan.reason}",
        )
        return
    }
    currentModel = effectiveModel
    _modelName.value = plan.state.modelName
    _providerName.value = plan.state.providerName
    _activeEntryId.value = plan.state.activeEntryId
    // [T-provider-key-roulette] Rotation happens inside ProviderFactory.create.
    currentProvider = plan.provider
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

/**
 * [fix/compact-quiet-first-1001] Ordered attempt chain for the compaction
 * summary — `(provider, fallbackEntryId?)` pairs, a null entryId marks the
 * active member. Pure ordering; budgets live at the call site
 * (generateCompactSummary). Top-level (not a ChatViewModel extension) so JVM
 * unit tests call it without instantiating the VM; the companion const it
 * reads is compile-time inlined and loads no Android classes.
 *
 * Why: measured 2026-10-01 14:09 (error-snapshot-141020) — the summary call
 * went out with ThinkingLevel.OFF, but the active member's model declares no
 * effort tiers, so OFF has no wire expression and the server kept thinking:
 * 71s of reasoning-only (3476 events, 10486 reasoning chars, ZERO content)
 * until the gateway reset the stream (`stream was reset: CANCEL`); the first
 * fallback then succeeded in 11s. A summary does not need the active member's
 * persona — the fallback chain already admits swapping models — so a member
 * known to be un-silenceable goes LAST, not first.
 *
 * Partition key is `declaresNoEffortTiers != true`: null (catalog never said)
 * counts as quiet — demotion requires positive evidence, absence of evidence
 * keeps today's behavior (the per-attempt budget still bounds it). Stable
 * within each partition: router order (cheapest-first, cooling/dead filtered)
 * survives; among noisy members the active one still leads.
 *
 * ponytail: quiet 判据只认 declaresNoEffortTiers 一个 catalog 字段 | 天花板:
 * models.dev 漏标“实际关不掉思考”的模型不会被排后(仍被每候选 30s 预算兜住) |
 * 升级触发: 同一未标成员在日志里反复 TIMEOUT → 加本地覆盖名单
 */
internal fun ordersCompactionCandidates(
    active: LLMProvider,
    fallbacks: List<FallbackCandidate>,
): List<Pair<LLMProvider, String?>> {
    val all: List<Pair<LLMProvider, String?>> =
        listOf(active to null) + fallbacks.map { it.provider to it.entryId }
    val (quiet, noisy) = all.partition { (provider, _) ->
        provider.model.declaresNoEffortTiers != true
    }
    return (quiet + noisy).take(1 + ChatViewModel.COMPACT_SUMMARY_FALLBACK_LIMIT)
}
