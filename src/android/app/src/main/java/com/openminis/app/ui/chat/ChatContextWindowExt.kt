package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger
import com.openminis.app.data.ContextPolicy
import com.openminis.app.conversation.ContextCompactor
import com.openminis.app.R
import kotlinx.coroutines.delay

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
    loadSession()
}

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
    val policy = ContextPolicy.forContextWindow(window)
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
    val policy = ContextPolicy.forContextWindow(window)
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
    )
    if (decision != ContextCompactor.Decision.AUTO_COMPACT) {
        // Log at debug-relevant level only when we were actually close —
        // keeps the common OK path from spamming the log.
        if (tokens > 0) {
            AppLogger.info(ChatViewModel.TAG, "[AutoCompact] skipped: $decision tokens=$tokens window=$window tail=$tail")
        }
        return
    }
    lastAutoCompactAtMs = System.currentTimeMillis()
    appendSystemInfo(
        text = context.getString(R.string.sysmsg_context_full_auto, tokens, window),
        iconKind = "compact",
    )
    AppLogger.info(ChatViewModel.TAG, "[AutoCompact] triggering (tokens=$tokens window=$window tail=$tail)")
    compactAll() // fire-and-forget; internally launches on Dispatchers.IO
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
