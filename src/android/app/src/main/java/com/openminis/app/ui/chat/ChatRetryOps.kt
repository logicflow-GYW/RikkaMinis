package com.openminis.app.ui.chat

// [refactor/split-vm] Batch 4: retryLast moved VERBATIM from ChatViewModel.kt
// (was lines 3199-3347), converted to an internal extension function — the
// same pattern as ChatViewModelSlashExt / ChatQueueInterruption. Every VM
// member it touches (_messages/_isStreaming/streamJob/traceObserver/
// agentHistory/...) is already internal, so no visibility changes needed.

import android.util.Log
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import com.openminis.app.tools.AgentTraceRecorder
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

internal fun ChatViewModel.retryLast() {
        if (_isStreaming.value) return
        // T-streaming-side-channel: belt-and-suspenders flush in case any
        // delta survived an earlier abnormal exit; retryLast is gated on
        // !isStreaming so this is normally a no-op.
        flushAllStreamingDeltas()
        // T7-A: 观察 —— 用户请求重试上一轮（开启新 run）
        traceObserver.t7Retry(
            operationType = "user_retry",
            operationName = null,
            safetyLevel = null,
            outcome = AgentTraceRecorder.OUTCOME_SAFE_TO_RETRY,
            reason = "retryLast",
            attempt = null,
            maxAttempts = null,
            willRetry = true,
        )
        val poppedAssistant = rollbackIncompleteTurn()
        if (poppedAssistant == null) return

        val initialProvider = currentProvider ?: return
        var provider: LLMProvider = initialProvider
        _error.value = null

        // T145: claim _isStreaming synchronously — see retryFromMessage for rationale.
        AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true
        streamEpoch++
        // [T-stale-finally-vs-new-claim] Publish the claim so an older job's
        // finally can't flip _isStreaming off during the setup window.
        streamingClaimEpoch = streamEpoch
        val sendEpoch = streamingClaimEpoch

        viewModelScope.launch(Dispatchers.IO) {
            var streamLaunched = false
            try {
            val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

            // T258: only sync the DB when step 2 popped a trailing assistant
            // entry from agentHistory. In that case the persisted partial-
            // assistant row would resurrect the failed turn on next session
            // load — drop it (and only it) by deleting from its sort_order.
            // Completed assistant + tool_result rows for earlier turns are
            // unchanged and stay persisted, so retry preserves their cards.
            // toolLoopDetector keeps its accumulated state — completed tools
            // shouldn't be unlearned just because the next turn errored.
            // (poppedAssistant is non-null Boolean here — the null case was
            // returned above.)
            if (poppedAssistant) {
                val dbMessages = chatRepository.loadMessages(sid)
                val trailingAssistantSortOrder = dbMessages
                    .lastOrNull { it.role == "assistant" }?.sortOrder
                if (trailingAssistantSortOrder != null) {
                    chatRepository.deleteMessagesAfter(sid, trailingAssistantSortOrder)
                    AppLogger.info(
                        ChatViewModel.TAG_STREAM,
                        "retryLast: deleted trailing assistant row sortOrder=$trailingAssistantSortOrder, kept ${trailingAssistantSortOrder} prior rows",
                    )
                }
            } else {
                AppLogger.info(
                    ChatViewModel.TAG_STREAM,
                    "retryLast: agentHistory tail was user(tool_result) — no DB cleanup needed",
                )
            }

            val baseSystemPrompt = systemPromptForSession()
            val systemPrompt = baseSystemPrompt

            // _isStreaming was already set synchronously at the top.
            streamLaunched = true
            streamJob = launch(Dispatchers.IO) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast streamJob ENTER sid=$activeSessionId")
                try {
                    SessionConcurrencyManager.acquireSlot(activeSessionId)
                    AppLogger.debug(ChatViewModel.TAG_STREAM, "retryLast streamJob slot acquired")
                    SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })
                    val activeFallbackStrategy = run {
                        val groupId = _selectedGroupId.value
                        groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                            ?: com.openminis.app.data.model.FallbackStrategy.default
                    }
                    val fallbackProviders = buildFallbackProviders(provider)
                    try {
                        AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast runAgentLoop CALL")
                        runAgentLoop(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            fallbackProviders = fallbackProviders,
                            fallbackStrategy = activeFallbackStrategy,
                        )
                        AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast runAgentLoop RETURN normal")
                        drainQueuedPrompts(provider, systemPrompt, activeFallbackStrategy)
                        AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast drainQueuedPrompts RETURN")
                    } catch (e: CancellationException) {
                        AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast runAgentLoop CANCELLED")
                        Log.d(ChatViewModel.TAG, "Agent loop cancelled")
                    } catch (e: Exception) {
                        AppLogger.error(ChatViewModel.TAG_STREAM, "retryLast runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                        Log.e(ChatViewModel.TAG, "Agent loop error (retryLast)", e)
                        reportAgentLoopError(e)
                        // T298: completion notifier should show the ❌ variant.
                        SessionActivityTracker.markStreamError(activeSessionId)
                    } finally {
                        AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast streamJob FINALLY enter")
                        // [audit-0907 B2] Reset queue-position state — same
                        // rationale as the send path's finally: whatever the
                        // worker reported while queued must not leak into the
                        // next run's typing indicator.
                        resetQueueWaitingAhead()
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
                        AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast streamJob FINALLY exit")
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast streamJob CANCELLED waiting for slot")
                    Log.d(ChatViewModel.TAG, "Cancelled while waiting for concurrency slot")
                }
                // [T-android-stale-streamjob-clears-isstreaming] guard +
                // epoch gate (see runRerunStreamTail).
                if (streamJob === coroutineContext[Job] && sendEpoch == streamingClaimEpoch) {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast _isStreaming=false (about to set)")
                    _isStreaming.value = false
                } else {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast _isStreaming SKIPPED (stale job; sendEpoch=$sendEpoch claimEpoch=$streamingClaimEpoch)")
                }
                AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast streamJob EXIT")
            }
            } finally {
                if (!streamLaunched) {
                    // [T-stale-finally-vs-new-claim] Only clear under our own claim.
                    if (sendEpoch == streamingClaimEpoch) {
                        AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast _isStreaming=false (setup aborted)")
                        _isStreaming.value = false
                    } else {
                        AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast _isStreaming=false SKIPPED (superseded; sendEpoch=$sendEpoch claimEpoch=$streamingClaimEpoch)")
                    }
                }
            }
        }
    }
