package com.rikkaminis.app.ui.chat

// [FE-5 batch 4/5] Queue-interruption & stream-lifecycle functions extracted
// verbatim from ChatViewModel as extension functions: queued-prompt injection
// as a standalone turn, queue draining (incl. the drain-loop that re-enters
// the agent loop), resume-after-cancel, cancelled-turn cleanup, and edit-mode
// history truncation. Same pattern as ChatViewModelUiStateExt — the functions
// still operate on the VM's own queue/state members, only their file location
// changed. No logic change.

import kotlinx.coroutines.withContext
import com.rikkaminis.app.provider.LLMProvider
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.service.SessionConcurrencyManager
import com.rikkaminis.app.service.SessionActivityTracker
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.logging.AppLogger
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import androidx.lifecycle.viewModelScope
import com.rikkaminis.app.data.model.FallbackStrategy



/**
 * T187: drop the message at [messageId] *and* every later message
 * (in UI, in agentHistory, and on disk) so the new sendMessage()
 * call below this can persist the edited text as a fresh user
 * turn at the same position. Reuses the cutoff-search machinery
 * from retryFromMessage but offsets by `entity.sortOrder` (not
 * +1) — retry preserves the original turn, edit replaces it.
 */
internal suspend fun ChatViewModel.truncateBeforeEdit(messageId: String) {
    val messages = _messages.value
    val index = messages.indexOfFirst { it.id == messageId }
    if (index < 0) return

    val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId
    // Visible-user index of the *edited* message — count user turns
    // strictly before `index`, which is the 0-based ordinal of the
    // edited turn itself.
    val visibleUserIndex = messages.subList(0, index).count { it.role == "user" }
    val dbMessages = chatRepository.loadMessages(sid)
    var visibleUserCount = 0
    var cutoffSortOrder = -1
    for (entity in dbMessages) {
        if (entity.role == "user") {
            // [T-android-retry-attachment-loss] The XML inventory text part
            // does NOT make a row visible; [T-ios-retry-anchor-synthetic-user]
            // the resume() synthetic <system-reminder> row does not either.
            // [B-05 deep-audit-1037] A mediaRef part (an attachment) DOES.
            // One shared predicate with the retry walk (ChatViewModel
            // retryFromMessage) — the old text-only copy disagreed with it for
            // pure-attachment rows, so the two cutoffs landed on different
            // rows and the truncation cut the wrong turn.
            val hasText = userPartsJsonHasVisibleContent(entity.partsJson)
            if (hasText) {
                if (visibleUserCount == visibleUserIndex) {
                    // ChatDao.deleteMessagesAfter is `sort_order >= <bound>` —
                    // the parameter is the DELETE-FROM boundary, NOT a
                    // "how many to keep" count (its name reads like a count and
                    // that is exactly what F-234 flagged). → passing this row's
                    // sortOrder deletes IT and everything after, which is exactly
                    // what edit semantics want.
                    cutoffSortOrder = entity.sortOrder
                    break
                }
                visibleUserCount++
            }
        }
    }
    // [audit-0917] Fail-closed: if the DB row for the edited turn cannot be
    // resolved (the visible-user count runs past the persisted rows), STOP
    // before mutating the UI. The previous order truncated _messages anyway and
    // then rebuilt agentHistory from the UNMODIFIED DB, so the model kept
    // receiving the very turns the user had just rewound past - a silent
    // UI/history divergence. Refusing leaves the conversation intact; the send
    // path then appends the edited text as a normal new turn (it is logged).
    if (cutoffSortOrder < 0) {
        AppLogger.warning(
            ChatViewModel.TAG_STREAM,
            "truncateBeforeEdit: no DB cutoff row for the edited turn - UI and history left untouched",
        )
        return
    }

    val deletedMessages = messages.subList(index, messages.size).toList()
    val kept = messages.subList(0, index)
    _messages.value = kept
    if (_streamingById.value.isNotEmpty()) {
        val keptIds = kept.mapTo(mutableSetOf()) { it.id }
        retainStreamFlushStates(keptIds)
        _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
    }
    revokeMemoryWritesInDeletedMessages(deletedMessages)

    chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
    agentHistory.clear()
    toolLoopDetector.reset()
    val remaining = chatRepository.loadMessages(sid)
    for (entity in remaining) {
        agentHistory.add(entity.toLLMMessage())
    }
    AppLogger.info(
        ChatViewModel.TAG_STREAM,
        "✏️ truncateBeforeEdit cutoffSortOrder=$cutoffSortOrder remaining=${remaining.size}"
)
}


internal suspend fun ChatViewModel.injectQueuedPromptsAsNewTurn(
    finishedAssistantId: String,
    finishedAccumulatedText: String,
    finishedAllToolBlocks: List<AssistantBlock>,
): InjectedTurn? {
    if (_promptQueue.value.isEmpty()) return null
    val queued = _promptQueue.value
    _promptQueue.value = emptyList()

    // [T-android-queued-message-duplicated-on-inject] REMOVE the queued
    // placeholder bubbles (the ones enqueuePrompt added with
    // id="queued_msg_…") for the prompts we're injecting. Step (c) below
    // appends a single combined user bubble (id=userEntity.id) for the same
    // text — so flipping isQueued=false and KEEPING the placeholders (the
    // old behaviour) rendered the message TWICE: once as the un-queued
    // placeholder, once as the injected bubble. drainQueuedPrompts reuses
    // its placeholders and never re-appends, so it didn't dupe; this mid-
    // loop inject path appends a fresh bubble, so the placeholders must go.
    val queuedIds = queued.map { it.id }.toSet()

    // Build the combined user message from all queued prompts.
    val sid = ensureSession()
    val combinedAttachments = queued.flatMap { it.attachments }
    val prepared = prepareUserAttachments(combinedAttachments, sid)

    val combinedParts = mutableListOf<AgentContentPart>()
    val combinedText = StringBuilder()
    for (prompt in queued) {
        if (prompt.text.isNotEmpty()) {
            if (combinedText.isNotEmpty()) combinedText.append("\n\n")
            combinedText.append(prompt.text)
            combinedParts.add(AgentContentPart.Text(prompt.text))
        }
    }
    prepared.imageParts.forEachIndexed { idx, part ->
        val path = part.linuxPath
        if (path != null) combinedParts.add(AgentContentPart.Text("[attached image: $path]"))
        combinedParts.add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path))
    }
    prepared.attachedFilesXml?.let { combinedParts.add(AgentContentPart.Text(it)) }

    // Guard: every queued prompt produced no content (no text, no
    // image). An empty user msg is a 400 from every provider. Skip —
    // the caller falls through to a normal next-turn dispatch so the
    // loop doesn't spin.
    if (combinedParts.isEmpty()) {
        AppLogger.warning(
            ChatViewModel.TAG_STREAM,
            "injectQueuedPromptsAsNewTurn: ${queued.size} queued prompt(s) produced no content, skipping",
        )
        // [audit-0917] The queue was already emptied above, so the queued
        // placeholder bubbles must go too - otherwise they stay on screen
        // as dashed rows with no queue entry behind them: never drained,
        // never withdrawable (the withdraw button lives on the bubble).
        withContext(Dispatchers.Main) {
            removeQueuedPlaceholders(queuedIds)
        }
        return null
    }

    // Bridge entry into agentHistory ONLY (not persisted). The tail
    // before this call is user(tool_result); without the bridge the
    // queued user message becomes two consecutive user roles and the
    // provider merges them — exactly the regression iOS hit at #579.
    // Empty/whitespace-only bridge text would itself be merged out by
    // some sanitizers; keep a small visible string for parity with iOS.
    agentHistory.add(
        LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "(Interrupted mid-task by a new user message. Decide based on the new message and overall context whether the prior task should continue — do not forget or abandon it unless the user explicitly says to stop, or the new message makes clear it is no longer needed.)",
            contentParts = listOf(
                AgentContentPart.Text("(Interrupted mid-task by a new user message. Decide based on the new message and overall context whether the prior task should continue — do not forget or abandon it unless the user explicitly says to stop, or the new message makes clear it is no longer needed.)"),
            ),
        ),
)

    // Persist the queued user message as its own DB row + append to
    // agentHistory so the next API call carries it.
    val userText = combinedText.toString()
    val userPartsJson = buildUserPartsJson(userText, prepared.mediaRefPartsJson, prepared.attachedFilesXml)
    val userEntity = chatRepository.appendMessage(sid, "user", userPartsJson)
    agentHistory.add(
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = userText,
            imageParts = prepared.imageParts,
            contentParts = combinedParts,
            dbMessageId = userEntity.id,
        ),
)

    // Finalize the just-finished assistant bubble in the UI on Main:
    // (a) un-queue the queued chat bubbles, (b) flush the side-channel
    // delta into the canonical row and clear isStreaming /
    // isAwaitingModelResponse, then (c) append the freshly-created
    // queued user ChatMessage + a NEW empty assistant placeholder so
    // the next iteration's streaming writes target the new bubble.
    val newAssistantId = "assistant_${System.currentTimeMillis()}"
    withContext(Dispatchers.Main) {
        // (a) + (b) one emit: build the post-finalize list.
        // [audit-0917] Re-filter from the CURRENT _messages: the old snapshot was
        // captured before the suspending ensureSession()/prepareUserAttachments()
        // calls, so a message appended in that window (another send, streaming
        // writes) was silently overwritten by this assignment.
        removeQueuedPlaceholders(queuedIds)
        updateAssistantMessage(
            finishedAssistantId,
            finishedAccumulatedText,
            false,
            finishedAllToolBlocks,
            isAwaitingModelResponse = false,
        )
        // [T-android-cancel-sidechannel] The interrupted assistant may
        // still hold a live streaming delta in `_streamingById` (thinking
        // streams through the side-channel; `finishedAccumulatedText`
        // only carries the formal text, not the thinking delta). If we
        // leave that entry behind, ChatScreen's `n(msgs, streamingById)`
        // overlay re-merges the delta and `mergeStreamingOverlay` forces
        // `isStreaming = true` again — so the old "Thinking…" breadcrumb
        // keeps spinning even though the tool itself reached a terminal
        // state (SUCCESS/CANCELLED) above. Evict the entry now: the
        // terminal canonical row has already been written by the
        // updateAssistantMessage call, so we only drop the stale overlay
        // without touching the just-written content.
        _streamingById.value = _streamingById.value - finishedAssistantId
        // (c) — append the queued user bubble + the new assistant
        // placeholder. Mirrors sendMessage's user-bubble append shape so
        // attachments / images / file chips render the same.
        val queuedUserMsg = ChatMessage(
            id = userEntity.id,
            role = "user",
            content = userText,
            imageUris = prepared.imageUris,
            attachmentNames = prepared.attachmentNames,
            attachmentUris = prepared.nonImageUris,
        )
        val nextAssistantMsg = ChatMessage(
            id = newAssistantId,
            role = "assistant",
            content = "",
            isStreaming = true,
            isAwaitingModelResponse = true,
            // [T-thinking-effective-level] Snapshot the EFFECTIVE level — this
            // value drives whether the Deep Thinking block renders for the
            // turn, so it must match what the request actually carried.
            thinkingLevel = effectiveThinkingLevel,
        )
        _messages.value = _messages.value + queuedUserMsg + nextAssistantMsg
        // Note: ChatScreen's `lastUserAppendMs` (the trailing-row
        // ScrollPin send-grace window) is updated reactively by
        // ChatScreen's `LaunchedEffect(messages.size)` user-send hook
        // when messages.size grows — appending the queuedUserMsg above
        // bumps the size, so the pin window opens just like a normal
        // send. No direct write needed from here (and we couldn't —
        // `lastUserAppendMs` lives in ChatScreen's composition scope).
    }

    AppLogger.info(
        ChatViewModel.TAG_STREAM,
        "injectQueuedPromptsAsNewTurn: injected ${queued.size} queued prompt(s) as new turn, " +
            "finishedId=$finishedAssistantId newId=$newAssistantId",
)
    return InjectedTurn(newAssistantId)
}


/**
 * Drain queued prompts after an agent loop finishes. Each queued prompt is
 * appended to agentHistory, persisted, and re-runs the agent loop.
 * Mirrors iOS AIChatViewModel.drainQueuedPrompts().
 *
 * The re-entered loop is anchored to the class-level `currentProvider`
 * (whatever the prior runAgentLoop settled on after fallback), and its
 * fallback candidates are rebuilt from that provider — the initial
 * provider/fallback snapshots are intentionally NOT carried in, so queued
 * prompts never replay a chain the main loop already resolved away from.
 */
internal suspend fun ChatViewModel.drainQueuedPrompts(
    provider: LLMProvider,
    systemPrompt: String?,
    fallbackStrategy: com.rikkaminis.app.data.model.FallbackStrategy,
): String? {
    while (_promptQueue.value.isNotEmpty()) {
        val queued = _promptQueue.value
        _promptQueue.value = emptyList()
        Log.i(ChatViewModel.TAG, "📨[DRAIN] Draining ${queued.size} queued prompt(s): " +
            queued.joinToString(", ") { "${it.id}=\"${it.text.take(20)}...\"" })

        // [B-04 deep-audit-1037] The placeholder bubbles are NOT un-queued in
        // place anymore: once the merged row is persisted they are REMOVED and
        // replaced by the single user bubble below. The old path flipped the
        // placeholders to isQueued=false and kept them — N rows on screen
        // where the DB had 1 — so the retry/edit cutoff counts (which walk
        // user rows) disagreed with the DB and truncated the wrong turn.
        val queuedIds = queued.map { it.id }.toSet()

        // Build a combined user message (text + images from all queued prompts).
        // Persist as a single row.
        val sid = ensureSession()
        val combinedAttachments = queued.flatMap { it.attachments }
        val prepared = prepareUserAttachments(combinedAttachments, sid)

        // T132: same shape as sendMessage — caption(s) first, then for each
        // image emit "[attached image: <path>]" + ImageData, finally the
        // <user-attached-files> XML. Keeps caption adjacent to image and
        // lets the agent re-read the file via read_image.
        val combinedParts = mutableListOf<AgentContentPart>()
        val combinedText = StringBuilder()
        for (prompt in queued) {
            if (prompt.text.isNotEmpty()) {
                if (combinedText.isNotEmpty()) combinedText.append("\n\n")
                combinedText.append(prompt.text)
                combinedParts.add(AgentContentPart.Text(prompt.text))
            }
        }
        prepared.imageParts.forEachIndexed { idx, part ->
            val path = part.linuxPath
            if (path != null) combinedParts.add(AgentContentPart.Text("[attached image: $path]"))
            combinedParts.add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path))
        }
        prepared.attachedFilesXml?.let { combinedParts.add(AgentContentPart.Text(it)) }

        // [B-04 deep-audit-1037] Empty-content guard — same shape as the twin
        // injectQueuedPromptsAsNewTurn's guard. Every queued prompt produced
        // no content (no text, no attachment survived prepareUserAttachments);
        // persisting an empty user row is a deterministic provider 400. The
        // queue was already emptied above, so the placeholder bubbles must go
        // too (leaving them behind produced dashes with no queue entry —
        // never drained, never withdrawable otherwise).
        if (combinedParts.isEmpty()) {
            AppLogger.warning(
                ChatViewModel.TAG_STREAM,
                "drainQueuedPrompts: ${queued.size} queued prompt(s) produced no content, skipping",
            )
            withContext(Dispatchers.Main) {
                removeQueuedPlaceholders(queuedIds)
            }
            return null
        }

        val userText = combinedText.toString()
        val userPartsJson = buildUserPartsJson(userText, prepared.mediaRefPartsJson, prepared.attachedFilesXml)
        // [B-04 deep-audit-1037] Bind the appended entity: its id is what the
        // user bubble and the agentHistory entry carry, so the retry/edit
        // cutoff walk and the compaction anchors can resolve this turn.
        val userEntity = chatRepository.appendMessage(sid, "user", userPartsJson)

        // [T-consecutive-user-bridge] The prior runAgentLoop may have
        // exited with agentHistory ending on user(tool_result) — e.g. the
        // MAX_AGENT_TURNS ceiling was hit between a tool result landing
        // and the next assistant turn. Appending another user would make
        // two consecutive user roles → deterministic 400 (Anthropic must
        // alternate) / merged-away (OpenAI). Inject an assistant bridge
        // (agentHistory-only, never persisted) exactly like
        // injectQueuedPromptsAsNewTurn does for the mid-loop interrupt.
        ensureTrailingRoleAlternativeBeforeUserAppend()

        agentHistory.add(LLMMessage(
            role = LLMMessage.Role.USER,
            content = userText,
            imageParts = prepared.imageParts,
            contentParts = combinedParts,
            // [B-04 deep-audit-1037] The twin inject path's agentHistory entry
            // carries dbMessageId — without it the compaction anchors skip this
            // turn (isPersistedUserPrompt resolves through the id).
            dbMessageId = userEntity.id,
        ))

        // [B-04 deep-audit-1037] Converge the UI with the DB: the N queued
        // placeholder bubbles collapse into the ONE merged user row that was
        // just persisted (id = userEntity.id, isQueued=false by default, no
        // queue entry to go stale). Same shape as the inject path's finalize
        // step — on Main, placeholders dropped through the single funnel.
        withContext(Dispatchers.Main) {
            removeQueuedPlaceholders(queuedIds)
            _messages.value = _messages.value + ChatMessage(
                id = userEntity.id,
                role = "user",
                content = userText,
                imageUris = prepared.imageUris,
                attachmentNames = prepared.attachmentNames,
                attachmentUris = prepared.nonImageUris,
            )
        }

        try {
            // [P0-fallback-reentry] Re-anchor to the class-level
            // `currentProvider` before the queued prompt re-enters the
            // agent loop. The prior `runAgentLoop` may have fallback-
            // resolved to a different group entry (e.g. the active instance
            // 401'd and the loop transparently moved to a same-model
            // endpoint), so the class-level provider — not the initial
            // `provider` snapshot captured at send time — is the current
            // truth. Replaying the stale snapshot here would re-trigger the
            // SAME failed chain for EVERY queued prompt (the failing entry
            // fails once, fallback churns to the working endpoint, repeat
            // per queued message) — observed as the working provider being
            // "continuously called" while the top-bar capsule shows the
            // earlier entry. Rebuild the fallback candidates from the
            // active provider too, so the drain chain continues AFTER the
            // current entry (and, with the fixed entry-anchor, never
            // re-includes the active entry itself).
            val drainedProvider = run {
                // [T-per-message-load-balance] Each queued prompt is its own
                // new user turn — advance the loadBalance rotation before
                // re-anchoring, so the drain serves queued messages across
                // the group's members too.
                rotateForNewTurn("drain")
                currentProvider ?: provider
            }
            val drainFallbacks = buildFallbackProviders(drainedProvider)
            // [P0-fallback-reentry] Log the drain anchor so the user can
            // verify a queued prompt continues on the ACTUAL active entry
            // (post-fallback) rather than replaying a stale chain.
            AppLogger.info(
                ChatViewModel.TAG_STREAM,
                "drain re-entry anchored provider=${drainedProvider.model.id} " +
                    "entryId=${_activeEntryId.value} staleSnapshot=${provider.model.id} candidates=${drainFallbacks.map { it.entryId }}",
            )
            runAgentLoop(
                provider = drainedProvider,
                systemPrompt = systemPrompt,
                fallbackProviders = drainFallbacks,
                fallbackStrategy = fallbackStrategy,
            )
        } catch (e: CancellationException) {
            Log.d(ChatViewModel.TAG, "Agent loop (queued-drain) cancelled")
            // Cancel mid-drain: cancelStream() will check _promptQueue
            // and call resumeQueueAfterCancel() if anything's still pending,
            // so just propagate.
            throw e
        } catch (e: Exception) {
            Log.e(ChatViewModel.TAG, "Agent loop (queued-drain) error", e)
            reportAgentLoopError(e)
            // [fix/da1015-sched-errorprop] Every run boundary flags the
            // tracker, not just the send/retry/resume ones — a drain that dies
            // here still unwinds through the same setInactive, and its
            // completion signal must not read as a clean finish. (Idempotent
            // with the flag set by reportAgentLoopError → setInlineError; kept
            // explicit because this is the one boundary the audit found
            // missing it.)
            SessionActivityTracker.markStreamError(activeSessionId)
            break
        }
    }
    return null
}


/**
 * T189: spawn a fresh agent loop to drain whatever the user queued during
 * the cancelled stream. 200ms delay matches iOS resumeQueueAfterCancel
 * (Task.sleep(200_000_000)) — gives the cancelled streamJob's finally block
 * room to release the concurrency slot + write back state. Race-guards on
 * entry: empty queue (user withdrew) or already streaming (user manually
 * retried) → noop return.
 *
 * Provider / systemPrompt / fallback resolution mirrors [sendMessage]
 * verbatim, so a queued prompt drain after cancel uses the same plumbing
 * as a fresh send.
 */
internal fun ChatViewModel.resumeQueueAfterCancel() {
    viewModelScope.launch(Dispatchers.IO) {
        kotlinx.coroutines.delay(200)
        if (_promptQueue.value.isEmpty()) return@launch
        if (_isStreaming.value) return@launch
        // [T-android-compact-queued-drain] Defer while a compact is in
        // flight — draining would mutate agentHistory mid-marker-write.
        // Safe to just return: every SUCCESSFUL compact re-kicks this
        // function from its own tail, so a deferred drain is never lost
        // (and a failed compact leaves the queue pending by design).
        // [fix/compact-cancel-on-stop-1002] …except when the in-flight
        // compact was just cancelled by this stop: it never reaches its
        // success kick, so deferring on the stale _isCompacting flag would
        // stall the drain forever. Predicate is a top-level pure function —
        // see queueDrainShouldDeferForCompact for the null case.
        if (queueDrainShouldDeferForCompact(_isCompacting.value, compactJob?.isActive)) {
            AppLogger.info(ChatViewModel.TAG, "resumeQueueAfterCancel: compact in flight — deferring to its completion kick")
            return@launch
        }

        val initialProvider = currentProvider
        if (initialProvider == null) {
            AppLogger.warning(ChatViewModel.TAG, "resumeQueueAfterCancel: no provider, dropping queue")
            _promptQueue.value = emptyList()
            _messages.value = _messages.value.filterNot { it.isQueued }
            return@launch
        }
        var provider: LLMProvider = initialProvider

        val baseSystemPrompt = systemPromptForSession()
        val systemPrompt = baseSystemPrompt

        // T145: claim the streaming flag synchronously before launching
        // the streamJob so a concurrent send/retry tap is rejected by the
        // entry guard. Mirrors sendMessage discipline.
        AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel _isStreaming=true (sync, sid=$activeSessionId)")
        // [T-claim-token-vs-stale-finally] Mint the claim token BEFORE the flag
        // flips — see streamingClaimToken KDoc.
        streamingClaimToken = Any()
        _isStreaming.value = true
        streamEpoch++
        // [T-stale-finally-vs-new-claim] Publish the claim so an older job's
        // finally can't flip _isStreaming off during the setup window.
        streamingClaimEpoch = streamEpoch
        _canResume.value = false
        _error.value = null

        // [T-stale-finally-vs-new-claim] Capture BEFORE launching so the
        // finally gate compares against THIS takeover's claim even if the
        // coroutine body starts late.
        val launchEpoch = streamingClaimEpoch
        // [T-claim-token-vs-stale-finally] Capture the claim identity this job
        // was launched under.
        val claimToken = streamingClaimToken
        streamJob = launch(Dispatchers.IO) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel streamJob ENTER sid=$activeSessionId")
            try {
                SessionConcurrencyManager.acquireSlot(activeSessionId)
                AppLogger.debug(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel streamJob slot acquired")

                try {
                    // [audit-0920] Guarded region widened — same fix as the
                    // send/resume/retry paths: setActive (tracker callback
                    // registration) and the strategy read must sit inside the
                    // try that owns the releaseSlot finally. Outside it, a
                    // non-CE throw leaks the concurrency slot and escapes the
                    // coroutine.
                    SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })

                    val activeFallbackStrategy = run {
                        val groupId = _selectedGroupId.value
                        groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                            ?: com.rikkaminis.app.data.model.FallbackStrategy.default
                    }
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel drainQueuedPrompts CALL")
                    drainQueuedPrompts(
                        provider = provider,
                        systemPrompt = systemPrompt,
                        fallbackStrategy = activeFallbackStrategy,
                    )
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel drainQueuedPrompts RETURN")
                } catch (e: CancellationException) {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel drain CANCELLED")
                } catch (e: Exception) {
                    AppLogger.error(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel drain EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(ChatViewModel.TAG, "Queued drain error (resumeQueueAfterCancel)", e)
                    reportAgentLoopError(e)
                } finally {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel streamJob FINALLY enter")
                    if (claimToken === streamingClaimToken) {
                        // [audit-0907 B2] Reset queue-position state — same
                        // rationale as the send path's finally: whatever the
                        // worker reported while queued must not leak into the
                        // next run's typing indicator.
                        resetQueueWaitingAhead()
                        // [fix/zero-chunk-cancel] Same rationale for the network-wait clock.
                        resetAwaitingResponseSince()
                        // [T-android-overlay-reply-status-34599] Surface
                        // the assistant's most recent reply text to the
                        // overlay BEFORE setInactive so the post-completion
                        // overlay state (no-running, has-outcome) carries a
                        // non-null excerpt. Reading _messages here is safe:
                        // we're in the finally block of the agent loop and
                        // the stream has already flushed its last delta.
                        publishOverlayReplyExcerpt(activeSessionId)
                        SessionActivityTracker.setInactive(activeSessionId)
                    }
                    SessionConcurrencyManager.releaseSlot(activeSessionId)
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel streamJob FINALLY exit")
                }
            } catch (e: CancellationException) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel streamJob CANCELLED waiting for slot")
            }
            // [T-android-stale-streamjob-clears-isstreaming] guard + epoch
            // gate (see runRerunStreamTail).
            if (streamJob === coroutineContext[Job] && launchEpoch == streamingClaimEpoch && claimToken === streamingClaimToken) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel _isStreaming=false (about to set)")
                _isStreaming.value = false
            } else {
                AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel _isStreaming SKIPPED (stale job)")
            }
            AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel streamJob EXIT")
        }
    }
}


/**
 * After the user stops a streaming turn, reconcile UI + agentHistory so
 * the conversation is valid on the next API call and resumable via
 * [resume]. Mirrors iOS AIChatViewModel.handleUserCancelledCleanup
 * (Case 1: tool cancel, Case 2: text cancel).
 *
 *  - Case 1: any in-flight tool block is flipped to [ToolBlockStatus.CANCELLED]
 *    and a synthetic tool_result with [CANCELLED_MARKER] is persisted so
 *    tool_use/tool_result stays paired.
 *  - Case 2: if the turn still in flight streamed partial assistant text (and
 *    no tool cancel), commit that text + a truncation `<system-reminder>` to
 *    agentHistory so the model knows the prior turn was cut short.
 *    [meta-audit-1028 C2] "That text" is the in-flight turn's slice only —
 *    every text block after the bubble's last `tool_use` — because both the
 *    bubble's `content` and its `toolBlocks` accumulate across the run's
 *    rounds. Slice + arm selection live in `StopCleanupPlan.kt`.
 *
 * Every bubble it KEEPS becomes resumable ([_canResume]); the only bubble it
 * removes is the thinking-only placeholder that carries neither text nor tool
 * activity.
 */
internal fun ChatViewModel.handleUserCancelledCleanup() {
    val msgs = _messages.value.toMutableList()
    val lastIdx = msgs.indexOfLast { it.role == "assistant" }
    if (lastIdx < 0) return
    var last = msgs[lastIdx]

    // T73: clear "Minis is thinking…" the moment the user taps Stop.
    // isAwaitingModelResponse is set true at runAgentLoop entry (≈ line
    // 2785) so the typing indicator shows during the initial request
    // gap before the first stream chunk. The cancel paths below didn't
    // reset it, so after Stop the indicator stayed live forever even
    // though the streamJob was already torn down. Reset before either
    // case runs so both tool-cancel and text-cancel paths benefit.
    //
    // [T-android-cancel-isstreaming] The per-message `isStreaming` flag
    // is the run-group's liveness source for a thinking block in flight
    // (run-group isRunning = "thinking && toolStatus==null && message
    // .isStreaming"). A cancel tears the stream down, so this message is
    // by definition no longer streaming — but the flag was never cleared
    // here, leaving the old "Thinking…" breadcrumb spinning after the
    // tool (whose own status DID converge to SUCCESS/CANCELLED) stopped.
    // Reset it unconditionally (NOT gated on isAwaitingModelResponse —
    // that flag flips false the moment the first thinking chunk lands,
    // so the gated reset alone left the thinking sticky for messages
    // that actually streamed content).
    if (last.isAwaitingModelResponse) {
        last = last.copy(isAwaitingModelResponse = false, isStreaming = false)
        msgs[lastIdx] = last
        _messages.value = msgs
    } else if (last.isStreaming) {
        last = last.copy(isStreaming = false)
        msgs[lastIdx] = last
        _messages.value = msgs
    }

    // Case 1: cancel during tool execution. Flip in-flight tool blocks to
    // CANCELLED and persist matching tool_result rows.
    val cancelledIds = mutableListOf<Pair<String, String>>() // (toolUseId, toolName)
    val updatedBlocks = last.toolBlocks.map { b ->
        val s = b.toolStatus
        if (s == ToolBlockStatus.STREAMING || s == ToolBlockStatus.PENDING || s == ToolBlockStatus.RUNNING) {
            if (b.kind == "tool_use") cancelledIds.add(b.id to b.toolName)
            b.copy(toolStatus = ToolBlockStatus.CANCELLED)
        } else b
    }
    val hadInflightTools = cancelledIds.isNotEmpty()
    if (hadInflightTools) {
        msgs[lastIdx] = last.copy(toolBlocks = updatedBlocks)
        _messages.value = msgs
        val parts = cancelledIds.map { (id, name) ->
            AgentContentPart.ToolResult(
                id = id,
                name = name,
                content = CANCELLED_MARKER,
                isError = true,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            persistToolResultMessage(parts)
        }
        _canResume.value = true
        return
    }

    // Case 2: cancel during text streaming. If partial assistant text
    // exists and agentHistory does not already end with the assistant
    // turn we're on, commit the partial text + truncation marker so the
    // model sees an interrupted prior turn on the next call.
    // [meta-audit-1028 C2] Decide from the turn that is still in flight, not
    // from the bubble's cross-turn carriers. Both carriers are run-scoped:
    // `last.content` is `loopState.accumulatedText + turnText` at every flush
    // site (accumulatedText = "cross-turn accumulated visible text of the
    // current bubble") and `last.toolBlocks` is the run's append-only block
    // list. Collecting them re-committed every earlier round's text as a new
    // assistant row on each multi-round Stop (duplicate transcript + duplicate
    // model context). The slice + the arm selection are one pure decision
    // (`StopCleanupPlan.kt`, pinned by `StopCleanupPlanTest`) so the three
    // shapes — multi-round tool Stop / pure-tool first round Stop / single-turn
    // text Stop — are testable without the ViewModel.
    val historyEndsWithAssistant =
        agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT
    // [R5-C C-C-06] Distinguish "history genuinely ends with a user/tool_result
    // turn" from "history ends with resume()'s continuation reminder". The
    // reminder IS a USER row, so without this probe the placeholder window
    // (stop after the reminder but before the first packet) read
    // historyEndsWithAssistant=false for the WRONG reason and the plan kept a
    // dead reminder row while killing _canResume. Pure predicate in
    // StopCleanupPlan.kt, single-sourced with the writer via
    // CONTINUATION_REMINDER_TEXT.
    val reminderTail = agentHistory.lastOrNull()
        ?.let { isContinuationReminderTail(it.role, it.content) } == true
    val stopPlan = planStopCleanup(last.toolBlocks, historyEndsWithAssistant, reminderTail)

    // Case 0 (T-ios-stop-clear-thinking-and-partial — Android port):
    // Stop fired while still in the pre-first-chunk thinking gap (no
    // partial text, no tool_use emitted, no committed history for this
    // turn). The placeholder ChatMessage runAgentLoop pushed at L5248 is
    // not in the DB and would otherwise render as an empty "Minis" header
    // bubble with no body. Drop it so the UI snaps back to idle the
    // instant the user taps Stop. Mirrors the iOS #566/#569 boundary:
    // a candidate WITH real text or any emitted tool_use is kept (handled
    // by Case 1 / Case 2 below); a thinking-only placeholder is not.
    // The predicate itself lives in `planStopCleanup` (all three cells:
    // text / tool_use / committed-history).
    if (stopPlan.dropPlaceholder) {
        // [R5-C C-C-06] The placeholder window can fire while the transcript's
        // tail is resume()'s continuation reminder (Stop after the reminder
        // write, before the first packet). Pre-fix that row stayed in the DB
        // and in agentHistory — a USER turn with no answer behind it, which
        // every future transcript read as "the user asked for a continuation
        // that never came" — and `_canResume` stayed false, killing the Resume
        // banner. The plan now says when the tail IS the reminder: remove the
        // row, restore resumability (with the reminder gone the transcript is
        // exactly the pre-resume shape), then drop the placeholder.
        if (stopPlan.dropContinuationReminder) dropContinuationReminderTail()
        if (stopPlan.canResume) _canResume.value = true
        msgs.removeAt(lastIdx)
        _messages.value = msgs
        return
    }

    val partialText = stopPlan.persistText
    if (partialText != null) {
        val parts = listOf<AgentContentPart>(
            AgentContentPart.Text(partialText),
            AgentContentPart.Text(
                "<system-reminder>The user stopped this response. Content may be incomplete.</system-reminder>"
            ),
        )
        agentHistory.add(
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = partialText,
                contentParts = parts,
            )
        )
        viewModelScope.launch(Dispatchers.IO) {
            val partsJson = buildTextOnlyAssistantPartsJson(parts)
            chatRepository.appendMessage(activeSessionId, "assistant", partsJson)
        }
    }
    // [meta-audit-1028 C2 defect B] Every KEPT bubble is resumable — the old
    // chain ended with `else if (historyEndsWithAssistant)`, so the shape
    // "round 1 was a pure tool_use round, user stopped in round 2's thinking
    // gap" (no text of its own, tool activity present, history ending on
    // USER(tool_result)) matched no arm at all: `_canResume` stayed false, the
    // Resume banner never appeared and the interrupted multi-step task could
    // not be continued. `resume()` already treats a trailing
    // USER(tool_result) as a valid anchor ("no reminder needed"), so nothing
    // else is required to make that shape resumable.
    if (stopPlan.canResume) _canResume.value = true
}

/**
 * [R5-C C-C-06] Remove the orphaned continuation reminder from the tail of
 * `agentHistory` and from the DB.
 *
 * Only called from the stop-cleanup placeholder arm, and only when
 * [planStopCleanup] matched the tail against [isContinuationReminderTail] — so
 * the row being removed is always the one `resume()` itself wrote for the turn
 * that just produced nothing (never a real user message: real user text can
 * never equal [CONTINUATION_REMINDER_TEXT]).
 *
 * The DB delete is guarded on "this row is still the last row": if anything
 * landed after the reminder the run was not in the pre-first-packet window
 * after all, and a partial delete (`deleteMessagesAfter(sortOrder)`) would take
 * the newer rows with it. In that case the row is left in place and logged —
 * divergence between memory and disk is worse than a logged orphan.
 */
internal fun ChatViewModel.dropContinuationReminderTail() {
    val tail = agentHistory.lastOrNull() ?: return
    if (!isContinuationReminderTail(tail.role, tail.content)) return
    val dbId = tail.dbMessageId
    agentHistory.removeAt(agentHistory.lastIndex)
    if (dbId.isNullOrEmpty()) return
    val sid = activeSessionId
    viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val rows = chatRepository.loadMessages(sid)
            if (rows.lastOrNull()?.id == dbId) {
                chatRepository.deleteMessagesAfter(sid, rows.last().sortOrder)
                AppLogger.info(
                    ChatViewModel.TAG,
                    "stop-cleanup: removed orphan continuation reminder row ${dbId.take(8)}",
                )
            } else {
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "stop-cleanup: continuation reminder ${dbId.take(8)} is not the DB tail — kept (no partial delete)",
                )
            }
        }.onFailure {
            Log.w(ChatViewModel.TAG, "stop-cleanup: reminder delete failed: ${it.message}")
        }
    }
}


/**
 * [audit-0917] Drop the queued placeholder bubbles (id="queued_msg_...") for
 * queuedIds from the UI list. Single funnel for the two paths that un-queue
 * prompts: the mid-loop inject (which then appends a fresh combined bubble, so
 * keeping the placeholders would render the same text twice) and the
 * empty-content early return (where leaving them behind produced bubbles with
 * no queue entry behind them - never drained, never withdrawable).
 */
internal fun ChatViewModel.removeQueuedPlaceholders(queuedIds: Set<String>) {
    _messages.value = _messages.value.filterNot { m ->
        m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)
    }
}
