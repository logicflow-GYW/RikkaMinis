package com.openminis.app.ui.chat

// [FE-5 batch 4/5] Queue-interruption & stream-lifecycle functions extracted
// verbatim from ChatViewModel as extension functions: queued-prompt injection
// as a standalone turn, queue draining (incl. the drain-loop that re-enters
// the agent loop), resume-after-cancel, cancelled-turn cleanup, and edit-mode
// history truncation. Same pattern as ChatViewModelUiStateExt — the functions
// still operate on the VM's own queue/state members, only their file location
// changed. No logic change.

import kotlinx.coroutines.withContext
import com.openminis.app.provider.LLMProvider
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.service.SessionConcurrencyManager
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import androidx.lifecycle.viewModelScope
import com.openminis.app.data.model.FallbackStrategy



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

    val deletedMessages = messages.subList(index, messages.size).toList()
    val kept = messages.subList(0, index)
    _messages.value = kept
    if (_streamingById.value.isNotEmpty()) {
        val keptIds = kept.mapTo(mutableSetOf()) { it.id }
        retainStreamFlushStates(keptIds)
        _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
    }
    revokeMemoryWritesInDeletedMessages(deletedMessages)

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
            val hasText = try {
                val arr = org.json.JSONArray(entity.partsJson)
                (0 until arr.length()).any { i ->
                    val o = arr.getJSONObject(i)
                    // [T-android-retry-attachment-loss] Exclude the now-
                    // persisted <user-attached-files> XML text part so this
                    // "is this a visible user bubble?" count stays identical
                    // to pre-XML-persistence behaviour. An attachments-only
                    // turn must NOT flip to hasText just because the XML
                    // inventory is now a text part — that would shift the
                    // retry/edit cutoff onto the wrong message.
                    // [T-ios-retry-anchor-synthetic-user] Likewise exclude
                    // resume()'s synthetic stop-continue <system-reminder>
                    // user row — it has no UI bubble, so counting it shifts
                    // the cutoff one user message too early.
                    o.optString("type") == "text" &&
                        stripAttachedFilesXml(o.optString("value", "")).isNotBlank() &&
                        !o.optString("value", "").trimStart().startsWith("<system-reminder>")
                }
            } catch (_: Exception) { true }
            if (hasText) {
                if (visibleUserCount == visibleUserIndex) {
                    // ChatDao.deleteMessagesAfter is `sort_order >= keepCount`
                    // → passing this row's sortOrder deletes IT and everything
                    // after, which is exactly what edit semantics want.
                    cutoffSortOrder = entity.sortOrder
                    break
                }
                visibleUserCount++
            }
        }
    }
    if (cutoffSortOrder >= 0) {
        chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
    }
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
    val msgsAfterUnqueue = _messages.value.filterNot { m ->
        m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)
    }

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
        val path = prepared.imageUploadPaths.getOrNull(idx)
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
        _messages.value = msgsAfterUnqueue
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
            thinkingLevel = _thinkingLevel.value,
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
    fallbackStrategy: com.openminis.app.data.model.FallbackStrategy,
): String? {
    while (_promptQueue.value.isNotEmpty()) {
        val queued = _promptQueue.value
        _promptQueue.value = emptyList()
        Log.i(ChatViewModel.TAG, "📨[DRAIN] Draining ${queued.size} queued prompt(s): " +
            queued.joinToString(", ") { "${it.id}=\"${it.text.take(20)}...\"" })

        // Flip isQueued=false on corresponding chat messages so they render as sent.
        // T189: also clear queuedPromptId so a later retry of this bubble
        // doesn't try to drop a phantom queue entry (and so the field state
        // matches what retryFromMessage's truncate path now produces).
        val queuedIds = queued.map { it.id }.toSet()
        _messages.value = _messages.value.map { m ->
            if (m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)) {
                m.copy(isQueued = false, queuedPromptId = null)
            } else m
        }

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
            val path = prepared.imageUploadPaths.getOrNull(idx)
            if (path != null) combinedParts.add(AgentContentPart.Text("[attached image: $path]"))
            combinedParts.add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path))
        }
        prepared.attachedFilesXml?.let { combinedParts.add(AgentContentPart.Text(it)) }

        val userText = combinedText.toString()
        val userPartsJson = buildUserPartsJson(userText, prepared.mediaRefPartsJson, prepared.attachedFilesXml)
        chatRepository.appendMessage(sid, "user", userPartsJson)

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
        ))

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
            val drainedProvider = currentProvider ?: provider
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
        if (_isCompacting.value) {
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

        val baseSystemPrompt = buildSystemPrompt()
        val systemPrompt = baseSystemPrompt

        // T145: claim the streaming flag synchronously before launching
        // the streamJob so a concurrent send/retry tap is rejected by the
        // entry guard. Mirrors sendMessage discipline.
        AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true
        streamEpoch++
        _canResume.value = false
        _error.value = null

        streamJob = launch(Dispatchers.IO) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel streamJob ENTER sid=$activeSessionId")
            try {
                SessionConcurrencyManager.acquireSlot(activeSessionId)
                AppLogger.debug(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel streamJob slot acquired")
                SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })

                val activeFallbackStrategy = run {
                    val groupId = _selectedGroupId.value
                    groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                        ?: com.openminis.app.data.model.FallbackStrategy.default
                }
                try {
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
                    // [T-android-overlay-reply-status-34599] Surface
                    // the assistant's most recent reply text to the
                    // overlay BEFORE setInactive so the post-completion
                    // overlay state (no-running, has-outcome) carries a
                    // non-null excerpt. Reading _messages here is safe:
                    // we're in the finally block of the agent loop and
                    // the stream has already flushed its last delta.
                    publishOverlayReplyExcerpt(activeSessionId)
                    SessionActivityTracker.setInactive(activeSessionId)
                    SessionConcurrencyManager.releaseSlot(activeSessionId)
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel streamJob FINALLY exit")
                }
            } catch (e: CancellationException) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel streamJob CANCELLED waiting for slot")
            }
            // [T-android-stale-streamjob-clears-isstreaming] guard.
            if (streamJob === coroutineContext[Job]) {
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
 *  - Case 2: if there was partial assistant text streamed (and no tool
 *    cancel), commit the partial text + a truncation `<system-reminder>`
 *    to agentHistory so the model knows the prior turn was cut short.
 *
 * Always sets [_canResume] = true when there is something to resume from.
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
    val partialText = buildString {
        if (last.content.isNotEmpty()) append(last.content)
        for (b in last.toolBlocks) {
            if (b.kind == "text" && b.content.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(b.content)
            }
        }
    }
    val historyEndsWithAssistant =
        agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT

    // Case 0 (T-ios-stop-clear-thinking-and-partial — Android port):
    // Stop fired while still in the pre-first-chunk thinking gap (no
    // partial text, no tool_use emitted, no committed history for this
    // turn). The placeholder ChatMessage runAgentLoop pushed at L5248 is
    // not in the DB and would otherwise render as an empty "Minis" header
    // bubble with no body. Drop it so the UI snaps back to idle the
    // instant the user taps Stop. Mirrors the iOS #566/#569 boundary:
    // a candidate WITH real text or any emitted tool_use is kept (handled
    // by Case 1 / Case 2 below); a thinking-only placeholder is not.
    val hasAnyToolUse = last.toolBlocks.any { it.kind == "tool_use" }
    if (partialText.isEmpty() && !hasAnyToolUse && !historyEndsWithAssistant) {
        msgs.removeAt(lastIdx)
        _messages.value = msgs
        return
    }

    if (partialText.isNotEmpty() && !historyEndsWithAssistant) {
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
        _canResume.value = true
    } else if (historyEndsWithAssistant) {
        // Already committed (tool cancel path above handled or prior turn
        // wrote an assistant row). Still allow resume.
        _canResume.value = true
    }
}
