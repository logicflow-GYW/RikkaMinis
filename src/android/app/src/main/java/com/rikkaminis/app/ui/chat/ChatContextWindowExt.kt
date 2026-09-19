package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.data.AgentRuntimeLimitsPrefs
import com.rikkaminis.app.data.ContextPolicy
import com.rikkaminis.app.conversation.ContextCompactor
import com.rikkaminis.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// [FE-5 batch 8] Context management cluster (reloadSessionFromDb /
// checkContextBeforeSend / maybeTriggerAutoCompact / awaitAutoCompactIfNeeded)
// extracted verbatim from ChatViewModel as extension functions.


/**
 * Re-load the current session's UI message list from disk so any
 * cached-marker change (revert) gets re-applied through Phase-2.5-
 * style restore. Defers to the existing [loadSession] entry; that
 * function reads `_cachedLatestMarker` we just refreshed and routes
 * through [applyCompactMarkerGraying] to (re)position the divider.
 */
internal fun ChatViewModel.reloadSessionFromDb() {
    if (realSessionId.isEmpty() && sessionId.isEmpty()) return
    // [fix/same-class-cleanup] The queued-bubble re-attach that used to live
    // here moved into loadSession's tail — the rebuild is the ROOT of the
    // drop, so the fix belongs at the root: every loadSession caller now
    // inherits it (init, safe-mode-cleared retry, this wrapper, and any
    // future one). This wrapper stays as the named entry for the revert
    // path's comment history.
    loadSession()
}

/**
 * Build the UI-only queued bubble for a [QueuedPrompt]. Single source of
 * truth for the queued-bubble shape — enqueuePrompt and the reload
 * re-attach path both render through this so the two can't drift.
 */
internal fun queuedPromptBubble(prompt: QueuedPrompt): ChatMessage {
    val pendingAttachments = prompt.attachments
    val attachmentNames = pendingAttachments.map { it.fileName }
    val imageUris = pendingAttachments.filter { it.isImage }.map { it.uri }
    val attachmentUris = pendingAttachments.filterNot { it.isImage }.map { it.uri }
    return ChatMessage(
        id = "queued_msg_${prompt.id}",
        role = "user",
        content = prompt.text,
        imageUris = imageUris,
        attachmentNames = attachmentNames,
        attachmentUris = attachmentUris,
        isQueued = true,
        queuedPromptId = prompt.id,
    )
}

/**
 * [T-adaptive-compact-window] + [T-adaptive-compact-reserve] The policy that
 * trigger decisions actually use: tier thresholds for [contextWindow], shifted
 * earlier by this session's adaptive growth reserve.
 *
 * Single entry point on purpose — the compact decision, the pre-send advisory
 * and the offload pass must all agree on where the lines are, otherwise a
 * compact can fire before the offload that was supposed to slim the history it
 * is about to summarise.
 *
 * The reserve is 0 until a turn-to-turn delta has been measured (see
 * [ContextGrowthTracker]), so a cold session behaves exactly as before.
 */
internal fun ChatViewModel.effectiveContextPolicy(contextWindow: Int): ContextPolicy =
    ContextPolicy.forContextWindow(contextWindow).reservedForGrowth(
        reserveTokens = contextGrowthTracker.reserveTokens(contextWindow),
        contextWindow = contextWindow,
    )

/**
 * Consult [ContextPolicy] before sending. Returns true to proceed.
 *
 * [T-context-limit-enforce] Behaviour:
 *   - Below the compact line → OK, proceed.
 *   - At/between compact and hard ceiling → NEEDS_COMPACT, warn via
 *     [appendSystemInfo] but still proceed (advisory — the user may keep
 *     going until the hard stop, choosing to /compact when ready).
 *   - At/past the hard window ceiling → EXHAUSTED, warn AND block the
 *     send (`false`). This is what makes the group's `contextLimitTokens`
 *     a genuine hard cap: the request never goes out with more context
 *     than the limit. Small-window tiers also stop earlier at their
 *     `exhaustedOnly` line.
 * The user resolves EXHAUSTED via explicit `/compact` or a new chat.
 */
internal fun ChatViewModel.checkContextBeforeSend(): Boolean {
    val tokens = _lastTurnContextTokens.value
    if (tokens <= 0) return true
    // [T-context-window-live-read] Live window (entry re-resolved + group
    // contextLimitTokens folded in) — not the currentModel snapshot.
    val window = effectiveContextWindowTokens() ?: return true
    val policy = effectiveContextPolicy(window)
    return when (policy.check(tokens, window)) {
        ContextPolicy.CheckResult.OK -> true
        ContextPolicy.CheckResult.NEEDS_COMPACT -> {
            appendSystemInfo(
                text = context.getString(R.string.sysmsg_context_full_hint, tokens, window),
                iconKind = "compact",
            )
            true
        }
        ContextPolicy.CheckResult.EXHAUSTED -> {
            // [T-context-exhausted-dialog] iOS parity: don't inline a
            // "Send blocked" notice here — sendMessage stashes the pending
            // content and shows the New Session / Clear Chat / Cancel
            // dialog instead (see sendMessage). Returning false stops the
            // send; the dialog drives the next action.
            false
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// [T5-auto-compact] Automatic compaction (OmniBot
// AgentConversationContextCompactor parity).
//
// Triggering happens synchronously in sendMessage BEFORE `_isStreaming`
// flips true (compactAll aborts on the in-stream guard); awaiting happens
// inside the send coroutine so the outgoing request sees
// summary + recent tail + the new user message.
// ─────────────────────────────────────────────────────────────────

/**
 * Synchronous decision + fire-and-forget trigger. Must be called from the
 * send path while `_isStreaming` is still false, otherwise compactAll's
 * in-stream guard aborts. Decision is pure logic in [ContextCompactor];
 * this function only enriches it with live state (marker anchor for the
 * tail estimate) and runs the existing compact pipeline.
 */
internal fun ChatViewModel.maybeTriggerAutoCompact() {
    val tokens = _lastTurnContextTokens.value
    // [fix/send-prompt-bloat] Cheap O(1) gates BEFORE the O(history) tail
    // walk. This function runs synchronously on the main thread for every
    // send; `estimateTailTokens` walks the WHOLE agentHistory (summing
    // every contentPart, incl. ToolUse.input.toString()) so it must only
    // run when a compact is genuinely on the table. All the short-circuits
    // below resolve to the same non-AUTO_COMPACT outcome decide() would
    // return — they just avoid paying the O(N) walk on the common OK path.
    if (_isCompacting.value) return // == Decision.COMPACT_IN_FLIGHT
    val window = effectiveContextWindowTokens() ?: return
    if (tokens <= 0 || window <= 0) return // == Decision.OK (no estimate/window)
    val policy = effectiveContextPolicy(window)
    // EXHAUSTED is already handled by checkContextBeforeSend (send blocked);
    // OK means no pressure. Both are non-AUTO_COMPACT. Only NEEDS_COMPACT
    // can possibly trigger an auto-compact, so only that path walks tail.
    if (policy.check(tokens, window) != ContextPolicy.CheckResult.NEEDS_COMPACT) return
    val anchorId = _cachedLatestMarker?.lastCompactedMessageId
    val tail = ContextCompactor.estimateTailTokens(agentHistory, anchorId)
    val decision = ContextCompactor.decide(
        estimatedTokens = tokens,
        contextWindow = window,
        policy = policy,
        tailTokens = tail,
        isCompacting = false, // already gated above
        lastAutoCompactAtMs = lastAutoCompactAtMs,
        // [feat/chat-tuning-panel-b] User-tunable (defaults: 8000 tokens / 5 min).
        minTailTokens = AgentRuntimeLimitsPrefs.autoCompactMinTailTokens().toLong(),
        minIntervalMs = AgentRuntimeLimitsPrefs.autoCompactMinIntervalMin() * 60_000L,
    )
    if (decision != ContextCompactor.Decision.AUTO_COMPACT) {
        // Log at debug-relevant level only when we were actually close —
        // keeps the common OK path from spamming the log.
        if (tokens > 0) {
            AppLogger.info(ChatViewModel.TAG, "[AutoCompact] skipped: $decision tokens=$tokens window=$window tail=$tail compactLine=${policy.compactThreshold}")
        }
        return
    }
    appendSystemInfo(
        text = context.getString(R.string.sysmsg_context_full_auto, tokens, window),
        iconKind = "compact",
    )
    AppLogger.info(
        ChatViewModel.TAG,
        "[AutoCompact] triggering (tokens=$tokens window=$window tail=$tail " +
            "compactLine=${policy.compactThreshold} offloadLine=${policy.offloadThreshold} " +
            "reserve=${contextGrowthTracker.reserveTokens(window)} growth=${contextGrowthTracker.perTurnEstimate}/turn)",
    )
    compactAll() // fire-and-forget; internally launches on Dispatchers.IO
}

/**
 * [T-auto-compact-in-loop] Turn-boundary automatic summarization for the
 * agent loop. Called from runAgentLoop BEFORE the hard
 * [trimContextHistoryWindow] fallback, while `_isStreaming` is still true but
 * the previous turn's collect has completed (no pending delta).
 *
 * Differences from [maybeTriggerAutoCompact] (the sendMessage path):
 *   - Uses the engine's live `lastContextTokens` param (loopState) instead of
 *     the VM's `_lastTurnContextTokens` snapshot.
 *   - Triggers `compactAll(allowInStream = true)` so the in-stream guard
 *     doesn't abort it at a turn boundary.
 *   - AWAITS completion (bounded) so the next provider call sees the summary,
 *     not a half-compacted history. Returns true iff the compact **actually
 *     folded something** (anchor or summary changed) — false means nothing
 *     shrank, so the caller must keep its fallback hard trim enabled this
 *     turn. See the return-site comment.
 */
/**
 * [T-ctx-offload-escalation] Reads and clears the one-shot "this turn's offload
 * could not deliver" flag set by [offloadContextIfNeeded]. Consume-on-read is
 * what keeps it single-turn — a flag can only ever escalate the compact attempt
 * that immediately follows the offload pass that raised it.
 */
private fun ChatViewModel.consumeOffloadUnderDelivery(): Boolean {
    val flagged = offloadUnderDelivered
    offloadUnderDelivered = false
    return flagged
}

internal suspend fun ChatViewModel.maybeAutoCompactInLoop(
    contextWindow: Int,
    lastContextTokens: Int,
): Boolean {
    if (_isCompacting.value) return false
    if (contextWindow <= 0 || lastContextTokens <= 0) return false
    val policy = effectiveContextPolicy(contextWindow)
    // Only fire while we're in the compact band (NEEDS_COMPACT), i.e. BEFORE
    // the hard ceiling forces trimContextHistoryWindow to drop turns verbatim.
    val inCompactBand =
        policy.check(lastContextTokens, contextWindow) == ContextPolicy.CheckResult.NEEDS_COMPACT
    // [T-ctx-offload-escalation] This turn's offload pass may have proved it can
    // no longer shrink the context (candidate pool exhausted). Sitting in the
    // dead band between the offload line and the compact line only lets the
    // context climb, so escalate now rather than waiting for the compact line.
    val escalatedFromOffload = consumeOffloadUnderDelivery()
    if (!inCompactBand && !escalatedFromOffload) {
        return false
    }
    val anchorId = _cachedLatestMarker?.lastCompactedMessageId
    val tail = ContextCompactor.estimateTailTokens(agentHistory, anchorId)
    val decision = ContextCompactor.decide(
        estimatedTokens = lastContextTokens,
        contextWindow = contextWindow,
        policy = policy,
        tailTokens = tail,
        isCompacting = false,
        lastAutoCompactAtMs = lastAutoCompactAtMs,
        // [feat/chat-tuning-panel-b] User-tunable (defaults: 8000 tokens / 5 min).
        minTailTokens = AgentRuntimeLimitsPrefs.autoCompactMinTailTokens().toLong(),
        minIntervalMs = AgentRuntimeLimitsPrefs.autoCompactMinIntervalMin() * 60_000L,
        escalatedFromOffload = escalatedFromOffload && !inCompactBand,
    )
    if (decision != ContextCompactor.Decision.AUTO_COMPACT) {
        AppLogger.info(
            ChatViewModel.TAG,
            "[AutoCompactLoop] skipped: $decision tokens=$lastContextTokens window=$contextWindow tail=$tail " +
                "compactLine=${policy.compactThreshold}",
        )
        return false
    }
    if (!inCompactBand) {
        AppLogger.info(
            ChatViewModel.TAG,
            "[AutoCompactLoop] escalated below the compact line: this turn's offload under-delivered " +
                "tokens=$lastContextTokens window=$contextWindow compactLine=${policy.compactThreshold}",
        )
    }
    // [fix/audit0917-b8] No stamp here — compactAll stamps the retry gate at
    // the point the compact actually starts, so a pre-flight abort no longer
    // disables auto-compaction for the whole minIntervalMs window.
    // [fix/diff-audit-0904-H1] appendSystemInfo is an unlocked
    // read-modify-write over _messages + 5 pendingSysInfo* vars; its KDoc
    // contract is "runs on Main". This extension is called from the agent
    // loop, which runs on Dispatchers.IO — hop to Main for the UI write
    // instead of racing the coalesce-flush job.
    withContext(Dispatchers.Main) {
        appendSystemInfo(
            text = context.getString(R.string.sysmsg_context_full_auto, lastContextTokens, contextWindow),
            iconKind = "compact",
        )
    }
    AppLogger.info(
        ChatViewModel.TAG,
        "[AutoCompactLoop] triggering (tokens=$lastContextTokens window=$contextWindow tail=$tail " +
            "compactLine=${policy.compactThreshold} offloadLine=${policy.offloadThreshold} " +
            "reserve=${contextGrowthTracker.reserveTokens(contextWindow)} growth=${contextGrowthTracker.perTurnEstimate}/turn)",
    )
    val markerBefore = _cachedLatestMarker?.lastCompactedMessageId
    val summaryBefore = _compactSummary.value
    compactAll(allowInStream = true) // fire-and-forget; internally launches on IO
    // Await completion so the next provider call assembles summary + tail.
    awaitAutoCompactIfNeeded()
    // [fix/compact-anchor-resolution] Report whether the compact ACTUALLY
    // folded something, not whether we merely asked for one. The caller
    // (AgentLoopEngine) skips the hard trim whenever this returns true, on the
    // theory that the just-written marker will project summary+tail on the
    // next request. That theory only holds if the compact folded a range —
    // when it early-returns ("already compacted" with a stuck anchor, no
    // persisted anchor, empty range, budget exhausted) the history is
    // untouched, lastContextTokens stays stale, and skipping the trim makes
    // the context grow on every turn with no fallback left. A successful
    // compact always writes a fresh marker (new anchor id) and/or a new
    // summary, so comparing those two is a sufficient "did it fold" signal.
    val folded = _cachedLatestMarker?.lastCompactedMessageId != markerBefore ||
        _compactSummary.value != summaryBefore
    if (!folded) {
        AppLogger.warning(
            ChatViewModel.TAG,
            "[AutoCompactLoop] compact attempted but folded nothing " +
                "(anchor=${markerBefore?.take(8) ?: "nil"} summaryChars=${summaryBefore?.length ?: 0} " +
                "historySize=${agentHistory.size}) — keeping the hard trim enabled this turn",
        )
    }
    return folded
}

/**
 * Called at the top of the send coroutine: if [maybeTriggerAutoCompact]
 * fired (or a compact is otherwise in flight), wait for it to finish so
 * the persisted user message is appended AFTER the compacted range and
 * the request the agent loop assembles is summary + tail + new message.
 * Bounded by [ContextCompactor.AUTO_COMPACT_MAX_WAIT_MS] — on timeout we
 * send anyway (provider-side too-large handling still applies).
 */
internal suspend fun ChatViewModel.awaitAutoCompactIfNeeded() {
    if (!_isCompacting.value) return
    val deadline = System.currentTimeMillis() + ContextCompactor.AUTO_COMPACT_MAX_WAIT_MS
    while (_isCompacting.value) {
        if (System.currentTimeMillis() > deadline) {
            AppLogger.warning(ChatViewModel.TAG, "[AutoCompact] timed out waiting for compact ($deadline); sending without it")
            return
        }
        delay(ContextCompactor.AUTO_COMPACT_POLL_MS)
    }
    AppLogger.info(ChatViewModel.TAG, "[AutoCompact] compact finished; proceeding with send")
}
