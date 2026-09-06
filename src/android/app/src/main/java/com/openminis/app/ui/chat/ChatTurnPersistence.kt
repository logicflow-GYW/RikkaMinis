package com.openminis.app.ui.chat

// [FE-5 batch 4/5] Turn persistence & stream-tail management extracted
// verbatim from ChatViewModel as extension functions: per-turn DB writes
// (persistAssistantTurn / persistToolResultMessage), the parts_json builder
// shell, turn-limit finalization, incomplete-turn rollback, and the
// rerun-stream tail shared by retry/switch/rerunFromToolBlock. Same pattern
// as ChatViewModelUiStateExt — the functions still operate on the VM's own
// repository / state-flow members, only their file location changed. No
// logic change.

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.provider.LLMProvider
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope



/**
 * Persist a single agent turn: the ordered list of AgentContentParts produced
 * in this turn (text segments and tool_use blocks interleaved in the order they
 * were emitted). Mirrors iOS's per-turn `persistAgentMessage` — one DB row per
 * turn, no cross-turn accumulation, preserving `parts` array order.
 *
 * This is the right entry point for the agent loop; the legacy
 * `persistAssistantMessage(text, usage, toolBlocks, ...)` accumulated all history
 * on every call, which caused:
 *   - Duplicate tool_use rows across turns (crashed LazyColumn key uniqueness)
 *   - Orphan tool_result detection thrashing (sanitize injecting placeholders)
 *   - Lost chronological text ↔ tool_use ordering within a single turn
 */
/**
 * Serialize a turn's [AgentContentPart] list into the on-disk parts_json
 * shape (text + toolUse blocks). Shared by [persistAssistantTurn] (the
 * authoritative per-turn row write) and the live session-list preview
 * update ([T-android-session-last-message-live-tool-call]) so both produce
 * an identical payload that [ChatRepository.extractTextPreview] understands.
 */
internal fun ChatViewModel.buildAssistantPartsJson(
    parts: List<AgentContentPart>,
    toolBlockMeta: Map<String, AssistantBlock>,
): String = buildAssistantTurnPartsJson(parts, toolBlockMeta)


internal suspend fun ChatViewModel.persistAssistantTurn(
    parts: List<AgentContentPart>,
    usage: LLMUsage?,
    reasoningContent: String? = null,
    toolBlockMeta: Map<String, AssistantBlock> = emptyMap(),
    // [T-usage-attribution] Actual provider/model identity that produced
    // this turn (fallback-resolved). Optional so legacy call sites are
    // untouched; recorded into the message row for correct usage grouping.
    modelId: String? = null,
    entryId: String? = null,
): String? {
    if (parts.isEmpty()) return null
    val partsJson = buildAssistantPartsJson(parts, toolBlockMeta)
    val tokenJson = usage?.let { buildUsageJson(it) }
    val entity = chatRepository.appendMessage(
        realSessionId.ifEmpty { sessionId }, "assistant", partsJson, tokenJson,
        reasoningContent = reasoningContent,
        usageModelId = modelId,
        usageEntryId = entryId,
)
    return entity.id
}


/** Persist tool results as a user-role message (mirrors iOS behavior). */
internal suspend fun ChatViewModel.persistToolResultMessage(parts: List<AgentContentPart>): String? {
    val results = parts.filterIsInstance<AgentContentPart.ToolResult>()
    if (results.isEmpty()) return null
    val partsJson = buildToolResultPartsJson(results)
    val entity = chatRepository.appendMessage(realSessionId.ifEmpty { sessionId }, "user", partsJson)
    return entity.id
}


internal fun ChatViewModel.finalizeAtTurnLimit(
    assistantId: String,
    text: String,
    blocks: List<AssistantBlock>,
) {
    updateAssistantMessage(
        assistantId, text, false, blocks,
        isAwaitingModelResponse = false,
)
    // [T-android-thinking-indicator-linger] updateAssistantMessage drains
    // _streamingById[assistantId] above, but the agent loop ran on
    // Dispatchers.IO while this finalize hops to Main — a late streaming
    // delta can re-add the side-channel entry AFTER the drain, and since
    // the loop has now exited no further isStreaming=false write will ever
    // clear it. mergeStreamingOverlay (ChatScreen) forces isStreaming=true
    // on any message with a side-channel entry, so that orphan keeps the
    // "thinking" row alive forever. Defensively drop the entry here as the
    // last Main-thread write of this turn.
    // [T-android-stream-flush-review] Cancel the trailing flush too, so it
    // can't re-add this orphan entry after we drop it on the error path.
    clearStreamFlushState(assistantId)
    if (_streamingById.value.containsKey(assistantId)) {
        _streamingById.value = _streamingById.value - assistantId
    }
    setInlineError(
        "Stopped after $MAX_AGENT_TURNS agent turns to prevent runaway " +
        "tool use. The model kept calling tools without finishing — tap " +
        "Resume to continue from here, or send a new message to start over.",
)
    _canResume.value = true
}


/** Retry the last agent turn (triggered by inline error Retry button).
 *
 *  T258: ports iOS AIChatViewModel.retry() (AIChatViewModel.swift:2079).
 *  Earlier behaviour blew away the entire failed assistant ChatMessage —
 *  including its already-completed tool_use cards — and reset
 *  agentHistory back to the last "real" user message, so on Retry every
 *  succeeded tool re-executed from scratch (the bug the user reported).
 *
 *  New behaviour:
 *   - Keep the assistant ChatMessage in the UI; clear its error sticker
 *     and the streaming/awaiting flags. Drop only tool blocks still in
 *     STREAMING / PENDING / RUNNING state — those have no matching
 *     tool_result and would orphan the request body.
 *   - From agentHistory, pop ONLY a trailing assistant entry (i.e. the
 *     turn whose stream errored). If the tail is already user(tool_result),
 *     the failure happened on the NEXT LLM call before any output —
 *     history is already valid, leave it.
 *   - GC orphaned tool_result rows whose tool_use is no longer in
 *     agentHistory (defends against the API "unexpected tool_use_id" 400).
 *   - Sync the DB: if we popped a trailing assistant, drop just its
 *     persisted row so a re-load doesn't resurrect the failed turn.
 */
/**
 * Roll back an incomplete assistant turn before re-running the loop on a
 * different provider. Extracted verbatim from [retryLast] steps 1-3 so
 * the model-switch-during-streaming path (switchModelAndRerun) shares
 * exactly the same rollback semantics.
 *
 * Returns [Boolean]?:
 *   - null  : there is no assistant message in the UI at all — nothing to
 *             roll back. Caller should treat this as "abort the re-run".
 *   - false : an assistant message exists, but agentHistory ends on a
 *             user(tool_result) entry — no trailing assistant was popped.
 *   - true  : a trailing assistant entry was popped from agentHistory and
 *             orphaned tool_result parts were GC'd.
 */
internal fun ChatViewModel.rollbackIncompleteTurn(): Boolean? {
    val msgs = _messages.value.toMutableList()
    val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
    if (lastAssistantIdx < 0) return null
    // [T-android-tool-autoscroll] Start-of-turn snap — see resume().
    _forceScrollToBottom.tryEmit(Unit)

    // 1. Keep the assistant message; clear error + streaming flags + drop
    //    in-flight tool blocks (STREAMING args / PENDING dispatch /
    //    RUNNING execution all have no tool_result, so they'd orphan).
    val lastMsg = msgs[lastAssistantIdx]
    val keptToolBlocks = lastMsg.toolBlocks.filter { block ->
        block.toolStatus !in ChatViewModel.IN_FLIGHT_TOOL_STATUSES
    }
    msgs[lastAssistantIdx] = lastMsg.copy(
        error = null,
        isStreaming = false,
        isAwaitingModelResponse = false,
        toolBlocks = keptToolBlocks,
)
    _messages.value = msgs
    // [T-error-persist-android] Clear the persisted error sticker on the last
    // assistant row up-front. The DB-sync below only DELETES the trailing
    // assistant row when a trailing assistant was popped (Case A); in the
    // Case B path (tail = user(tool_result), next LLM call errored) the
    // stamped row is an EARLIER completed turn that is NOT deleted, so
    // without this clear the new successful turn would merge-resurrect the
    // old error banner on reload (msg.error ?: prev.error). Harmless in
    // Case A too — the row is deleted moments later regardless.
    clearPersistedLastAssistantError()

    // 2. Pop ONLY a trailing assistant entry from agentHistory (mirrors
    //    iOS retry() :2107-2109). If the tail is already user(tool_result),
    //    the next-turn LLM call errored — leave history alone.
    val poppedAssistant = if (agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT) {
        agentHistory.removeAt(agentHistory.size - 1)
        true
    } else false

    // 3. GC orphaned tool_result parts whose tool_use is gone (mirrors
    //    iOS retry() :2114-2128). Walks backward so removeAt is safe.
    val liveToolUseIds = agentHistory.flatMap { m ->
        m.contentParts.filterIsInstance<AgentContentPart.ToolUse>().map { it.id }
    }.toSet()
    for (i in agentHistory.indices.reversed()) {
        val m = agentHistory[i]
        if (m.role != LLMMessage.Role.USER) continue
        val cleanedParts = m.contentParts.filter { p ->
            p !is AgentContentPart.ToolResult || p.id in liveToolUseIds
        }
        when {
            cleanedParts.isEmpty() && m.contentParts.isNotEmpty() ->
                agentHistory.removeAt(i)
            cleanedParts.size < m.contentParts.size ->
                agentHistory[i] = m.copy(contentParts = cleanedParts)
        }
    }
    return poppedAssistant
}


/**
 * [T-android-rerun-from-tool-block-position] Shared streaming tail used by
 * both [retryFromMessage] and [rerunFromToolBlock]: refresh the OAuth
 * token if needed, build the (OAuth-prefixed) system prompt, and launch
 * the agent-loop stream job. Callers must have already (a) claimed
 * `_isStreaming = true` synchronously, (b) truncated UI + DB to the desired
 * re-entry point, and (c) rebuilt [agentHistory]. Returns true once the
 * stream job is launched (the caller's outer `finally` resets
 * `_isStreaming` only when this returns false / throws first).
 */
internal suspend fun ChatViewModel.runRerunStreamTail(
    initialProvider: LLMProvider,
    label: String,
): Boolean {
    var provider = initialProvider

    val baseSystemPrompt = systemPromptForSession()
    val systemPrompt = baseSystemPrompt

    // _isStreaming was already set synchronously by the caller.
    // [T-stale-finally-vs-new-claim] The caller already published the claim
    // epoch right after its synchronous _isStreaming=true (covering the full
    // claim window including the system-prompt build before this call);
    // capture it here for the finally-block gate.
    val launchEpoch = streamingClaimEpoch
    val launchedProvider = provider
    streamJob = viewModelScope.launch(Dispatchers.IO) {
        AppLogger.info(ChatViewModel.TAG_STREAM, "$label streamJob ENTER sid=$activeSessionId")
        try {
            SessionConcurrencyManager.acquireSlot(activeSessionId)
            AppLogger.debug(ChatViewModel.TAG_STREAM, "$label streamJob slot acquired")
            SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })
            val activeFallbackStrategy = run {
                val groupId = _selectedGroupId.value
                groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                    ?: com.openminis.app.data.model.FallbackStrategy.default
            }
            val fallbackProviders = buildFallbackProviders(launchedProvider)
            try {
                AppLogger.info(ChatViewModel.TAG_STREAM, "$label runAgentLoop CALL")
                runAgentLoop(
                    provider = launchedProvider,
                    systemPrompt = systemPrompt,
                    fallbackProviders = fallbackProviders,
                    fallbackStrategy = activeFallbackStrategy,
                )
                AppLogger.info(ChatViewModel.TAG_STREAM, "$label runAgentLoop RETURN normal")
            } catch (e: CancellationException) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "$label runAgentLoop CANCELLED")
                Log.d(ChatViewModel.TAG, "Agent loop cancelled")
            } catch (e: Exception) {
                AppLogger.error(ChatViewModel.TAG_STREAM, "$label runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                Log.e(ChatViewModel.TAG, "Agent loop error ($label)", e)
                // [T-error-no-permanent-scars] The banner shows a human
                // summary; raw error codes / fallback trail go to the
                // collapsed technical-details disclosure (or are dropped
                // on reload since errorDetail is never persisted).
                reportAgentLoopError(e)
                // T298: flag the upcoming setInactive() so the
                // background completion notifier renders the ❌
                // variant instead of a clean success.
                SessionActivityTracker.markStreamError(activeSessionId)
            } finally {
                AppLogger.info(ChatViewModel.TAG_STREAM, "$label streamJob FINALLY enter")
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
                AppLogger.info(ChatViewModel.TAG_STREAM, "$label streamJob FINALLY exit")
            }
        } catch (e: CancellationException) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "$label streamJob CANCELLED waiting for slot")
            Log.d(ChatViewModel.TAG, "Cancelled while waiting for concurrency slot")
        }
        // [T-android-stale-streamjob-clears-isstreaming] Only the current
        // streamJob is allowed to flip _isStreaming false. An orphaned
        // earlier job (cancelled but its finally still draining downstream
        // I/O) reaching this tail AFTER a fresh send/resume/retry has
        // already taken over would otherwise hide the Stop button while
        // the new turn is still streaming. See `var streamJob` KDoc and
        // XIN 2026-06-12 log (20:22:26 / 20:23:25).
        //
        // [T-stale-finally-vs-new-claim] Second gate, epoch-based. The
        // reference-equality guard alone has a claim window: the new turn
        // sets _isStreaming=true synchronously but only reassigns `streamJob`
        // later (after DB sync + system-prompt build). A cancelled previous
        // job's finally landing in that window passes the reference check
        // (streamJob still points at IT) and flips the flag off under the
        // brand-new turn — field-observed 2026-09-06 17:31:16 (third
        // mid-stream switchModel-groupEntry: new turn claimed true at
        // .466, old finally set false at .467, new loop then ran with the
        // Stop button dead until the next state flip). Capturing the claim
        // epoch at takeover and requiring THIS job's launch epoch to match
        // closes that window.
        val jobEpoch = launchEpoch
        if (streamJob === coroutineContext[Job] && jobEpoch == streamingClaimEpoch) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "$label _isStreaming=false (about to set)")
            _isStreaming.value = false
        } else {
            AppLogger.info(ChatViewModel.TAG_STREAM, "$label _isStreaming SKIPPED (stale job; current=${streamJob?.hashCode()} this=${coroutineContext[Job]?.hashCode()} jobEpoch=$jobEpoch claimEpoch=$streamingClaimEpoch)")
        }
        AppLogger.info(ChatViewModel.TAG_STREAM, "$label streamJob EXIT")
    }
    return true
}
