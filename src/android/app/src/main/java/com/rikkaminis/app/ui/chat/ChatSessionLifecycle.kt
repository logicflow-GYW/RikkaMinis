package com.rikkaminis.app.ui.chat

import android.util.Log
import com.rikkaminis.app.conversation.ContextCompactor
import com.rikkaminis.app.data.db.CompactMarkerEntity
import com.rikkaminis.app.data.db.MessageEntity
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.data.model.RoutingStrategy
import com.rikkaminis.app.diagnostics.SessionIdAliases
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.provider.ProviderFactory
import com.rikkaminis.app.service.SessionActivityTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import androidx.lifecycle.viewModelScope
import com.rikkaminis.app.agent.InterruptedTailDetector
import com.rikkaminis.app.agent.InterruptedTailPartKind
import com.rikkaminis.app.agent.InterruptedTailShape
import com.rikkaminis.app.agent.InterruptedTailSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

// [FE-5 batch 5] Session lifecycle & compaction cluster extracted verbatim
// from ChatViewModel as extension functions: session load/restore, context
// compaction, the compaction-marker graying heal pass, and provider/group
// state resolution. Same pattern as ChatViewModelUiStateExt — the functions
// operate on the VM's own members via extension receivers, no logic change.
// The thin delegating shells (walkBackUserTurnsBounded / buildChatMessages /
// buildLlmMessages / findModelEntry) stay in ChatViewModel.

internal fun ChatViewModel.effectiveAgentHistory(): List<LLMMessage> {
    val summary = _compactSummary.value
    val marker = _cachedLatestMarker
    // No compact in play → return full history untouched.
    if (summary.isNullOrBlank() || marker == null) return agentHistory.toList()

    val summaryWrappedText = "<context-summary>\n" +
        "The following is a summary of the earlier conversation that was compacted to save context space.\n" +
        "Treat it as background context only. The user's most recent message (below or in the next turn) takes precedence — if it changes the task, the goal, or any numbers/scope, follow the new instruction and do not resume the old plan from this summary. Do not re-run discovery (reading memory, scanning skills, re-reading files) unless the new instruction requires it.\n\n" +
        summary +
        "\n</context-summary>"

    // ─── v2 markers (id-only anchor model) ─────────────────────────
    //
    // anchor = lastCompactedMessageId. What we send to the model:
    //   1. last [ChatViewModel.COMPACT_KEEP_RECENT_USER_TURNS] user-text turns BEFORE
    //      anchor (inclusive of anchor) — recent verbatim warm-up
    //   2. the summary, INLINED as a `<context-summary>` text part
    //      prepended to the first user message AFTER anchor (preserves
    //      strict role alternation — no synthetic standalone user turn)
    //   3. all messages strictly after anchor (the kept-tail "active"
    //      region — typically empty right after compact, populated as
    //      the user sends new prompts)
    //
    // If anchor unresolvable, degrade to full history (over-inform
    // beats summary-only; the M-Team session bug taught us that a lone
    // summary message paired with hot tools makes the model loop).
    if (marker.version >= 2) {
        val anchorId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
        val anchorIdx = anchorId?.let { id ->
            agentHistory.indexOfLast { it.dbMessageId == id }
        } ?: -1
        if (anchorIdx < 0) {
            Log.w(ChatViewModel.TAG, "[Compact] effectiveAgentHistory v2: anchorId=${anchorId?.take(8) ?: "nil"} not in agentHistory(size=${agentHistory.size}) — degrading to full history (no summary)")
            return agentHistory.toList()
        }

        // Step 1: walk back from anchor collecting user-text turns. Stop
        // when EITHER we've collected N user-text turns OR including the
        // next turn would push preAnchor over 100 messages. Decisions
        // happen only at user-message boundaries so we never split a
        // user/assistant/tool round in half (which would orphan a
        // tool_use with no matching tool_result).
        //
        // [T-compact-preanchor-prune, port iOS 8b76cd74]
        val keepN = ChatViewModel.COMPACT_KEEP_RECENT_USER_TURNS
        val preAnchorCap = 100
        val walkBack = walkBackUserTurnsBounded(
            anchorIdx = anchorIdx,
            maxUserTextTurns = keepN,
            maxMessages = preAnchorCap,
        )
        val priorIdxResolved: Int? = walkBack.priorIdx
        var priorIdx = walkBack.priorIdx ?: (anchorIdx + 1) // empty preAnchor sentinel
        if (walkBack.stopReason != "userTextTargetMet") {
            AppLogger.info(ChatViewModel.TAG, "[CompactDiag] eAH v2 walkBack stopped: reason=${walkBack.stopReason} priorIdx=$priorIdx userTextTurnsFound=${walkBack.userTextTurnsFound} preAnchorMsgs=${walkBack.messageCount}")
        }

        // [T-compact-slice-tool-pairing] Boundary guard: walkBackUserTurnsBounded
        // stops on any USER-role message — including tool_result messages
        // (content="" + ToolResult parts). If the cap lands such that
        // agentHistory[priorIdx] is a tool_result whose assistant tool_use
        // sits at priorIdx-1, the slice would OPEN with an orphan tool
        // message → API 400 "tool must be a response to preceding
        // tool_calls". Extend the boundary backward over any leading
        // tool_result messages to include their paired tool_use(s), so the
        // slice never starts mid-tool-round.
        while (priorIdx in 1 until agentHistory.size) {
            val head = agentHistory[priorIdx]
            val headResultIds = head.contentParts
                .filterIsInstance<AgentContentPart.ToolResult>().map { it.id }.toSet()
            if (headResultIds.isEmpty()) break
            val pairedUseIdx = (priorIdx - 1 downTo 0).firstOrNull { idx ->
                agentHistory[idx].role == LLMMessage.Role.ASSISTANT &&
                    agentHistory[idx].contentParts
                        .filterIsInstance<AgentContentPart.ToolUse>()
                        .any { it.id in headResultIds }
            }
            if (pairedUseIdx == null) break // orphan result — sanitize will drop it
            priorIdx = pairedUseIdx
        }
        if (priorIdx != (walkBack.priorIdx ?: (anchorIdx + 1))) {
            AppLogger.info(ChatViewModel.TAG, "[CompactDiag] eAH v2 boundary guard: priorIdx=${walkBack.priorIdx} → $priorIdx (included paired tool_use)")
        }

        // [compact-budget-anchor] Budget clamp. The walk-back above sizes this
        // slice by user-text turns (N = 3, ≤ 100 messages) — right for a chat,
        // useless in a single-prompt tool loop, where the one user turn sits at
        // the top of the history and the slice swallows the whole compacted
        // region. The summary then saves nothing: everything it summarises is
        // re-sent verbatim right in front of it. Clamp the slice to a token
        // budget, keeping the newest messages.
        val clampedPriorIdx = clampSliceStartByBudget(
            history = agentHistory,
            startIdx = priorIdx,
            anchorIdx = anchorIdx,
            maxTokens = PRE_ANCHOR_MAX_TOKENS,
            estimate = { ContextCompactor.estimateMessageTokens(it) },
        )
        if (clampedPriorIdx != priorIdx) {
            AppLogger.info(ChatViewModel.TAG, "[CompactDiag] eAH v2 preAnchor clamp: priorIdx=$priorIdx → $clampedPriorIdx (budget=$PRE_ANCHOR_MAX_TOKENS tokens)")
            priorIdx = clampedPriorIdx
        }

        // PRE-ANCHOR PRUNE (tool-heavy session fix):
        // The walk-back-N-user-text strategy pulls in everything between
        // the Nth-last and last user-text turn — in a heavy tool-call
        // session that can be many messages of tool_result / tool_use,
        // tens of thousands of tokens that the summary already covers.
        // Drop any tool_result > 1000 chars in the preAnchor slice and
        // strip the matching tool_use part (same id) from the assistant
        // message so the model never sees a dangling tool_use/result.
        val preAnchorRaw: List<LLMMessage> =
            if (priorIdx <= anchorIdx) agentHistory.subList(priorIdx, anchorIdx + 1).toList()
            else emptyList()

        val droppedToolIds = mutableSetOf<String>()
        var droppedToolResultCount = 0
        for (msg in preAnchorRaw) {
            for (part in msg.contentParts) {
                if (part is AgentContentPart.ToolResult && part.content.length > 1000) {
                    droppedToolIds.add(part.id)
                    droppedToolResultCount += 1
                }
            }
        }

        val preAnchorPruned: MutableList<LLMMessage> = ArrayList(preAnchorRaw.size)
        for (msg in preAnchorRaw) {
            if (msg.contentParts.isEmpty()) {
                // Plain text-only message — nothing to prune.
                preAnchorPruned.add(msg)
                continue
            }
            val kept = msg.contentParts.filter { part ->
                when (part) {
                    is AgentContentPart.ToolUse -> !droppedToolIds.contains(part.id)
                    is AgentContentPart.ToolResult -> !droppedToolIds.contains(part.id)
                    else -> true
                }
            }
            if (kept.isEmpty()) continue // skip empty shells
            preAnchorPruned.add(msg.copy(contentParts = kept))
        }

        if (droppedToolResultCount > 0) {
            AppLogger.info(ChatViewModel.TAG, "[CompactDiag] eAH v2 preAnchor prune: dropped $droppedToolResultCount toolResult(>1kc) + paired toolUse, ${preAnchorRaw.size - preAnchorPruned.size} messages emptied; pruned slice=${preAnchorPruned.size}")
        }

        // ROLE ALIGNMENT: the API requires the first message to be `user`.
        // After clamp (cap may land on assistant) and after prune (the
        // head user may have been emptied), peel any leading non-user
        // messages so preAnchor starts on a user turn.
        while (preAnchorPruned.isNotEmpty() && preAnchorPruned.first().role != LLMMessage.Role.USER) {
            preAnchorPruned.removeAt(0)
        }

        // Step 2 & 3: copy the lookback window (post-prune), then splice
        // in the summary as parts[0] of the first post-anchor user msg.
        val result = mutableListOf<LLMMessage>()
        result.addAll(preAnchorPruned)

        val postAnchor = if (anchorIdx + 1 < agentHistory.size) {
            agentHistory.subList(anchorIdx + 1, agentHistory.size)
        } else {
            emptyList()
        }

        // DIAG: explain how the slice was sized using post-prune /
        // post-alignment counts so the log reflects what actually
        // reaches the model.
        val preAnchorRawCount = maxOf(0, anchorIdx - priorIdx + 1)
        val priorIdxSource =
            if (priorIdxResolved == null) "fallback=empty(<$keepN user-text turns before anchor or cap hit)"
            else "userTextWalkBack(N=$keepN)"
        AppLogger.info(ChatViewModel.TAG, "[CompactDiag] eAH v2 slice: priorIdx=$priorIdx anchorIdx=$anchorIdx agentHistory.size=${agentHistory.size} → preAnchorRaw=$preAnchorRawCount preAnchorSent=${preAnchorPruned.size} postAnchor=${postAnchor.size} summaryChars=${summary.length} priorIdxSource=$priorIdxSource markerId=${marker.id.take(8)}")

        // [T-compact-slice-summary-toolresult] Skip tool_result-only messages
        // when choosing the summary injection target: tool_result messages
        // carry `content="" + ToolResult parts`, and serialization uses
        // `contentParts` (ignoring the `content` string when parts are
        // present) — injecting the summary into a tool_result message's
        // content field would be silently swallowed. Find the first USER
        // message that is NOT a tool_result-only message, and inject the
        // summary there.
        // [fix/audit-1015-bug01] Splice the summary into the post-anchor
        // messages via the pure injector (extracted to ChatCompactionLogic
        // for JVM testability). It updates BOTH `content` and `contentParts`:
        // all four serialization exits (OpenAI/Anthropic/Gemini/Responses)
        // ignore `content` whenever contentParts is non-empty, so editing
        // `content` alone was silently swallowing the summary for any
        // message that carries parts.
        result.addAll(injectSummaryIntoPostAnchor(postAnchor, summaryWrappedText))
        // [T-compact-slice-tool-pairing] The slice (walkBack cap /
        // preAnchor prune / postAnchor splice) can split a tool round
        // across a boundary — e.g. cap lands on the tool_result user
        // message while its assistant tool_use was cut off, leaving an
        // orphan tool message that the API rejects with 400 "tool must
        // be a response to preceding tool_calls". Repair pairing on the
        // FINAL outgoing slice (drop orphan results / inject placeholder
        // results for orphan uses) so the request never carries a
        // dangling tool message. This is the same repair that runs on
        // the full agentHistory each loop iteration — the slice is the
        // gap that previously escaped it.
        // [fix/r5-c1-compact-C03①] One call, not a hand-written pair: sanitize
        // and normalize feed each other (see repairSliceShape), so the two
        // entry points must never be invoked in a fixed order again.
        repairSliceShape(result)
        return result
    }

    // ─── v1 (legacy) markers ──────────────────────────────────────
    //
    // Original behavior preserved unchanged so old markers keep
    // rendering / sending data the same way they always did.
    val summaryHead = LLMMessage(role = LLMMessage.Role.USER, content = summaryWrappedText)
    val firstKeptId = (marker.firstKeptMessageId?.takeIf { it.isNotEmpty() })
        ?: (marker.boundaryMessageId?.takeIf { it.isNotEmpty() })

    if (firstKeptId != null) {
        val keepStart = agentHistory.indexOfFirst { it.dbMessageId == firstKeptId }
        if (keepStart >= 0) {
            val result1 = mutableListOf<LLMMessage>()
            result1.add(summaryHead)
            result1.addAll(agentHistory.subList(keepStart, agentHistory.size))
            repairSliceShape(result1)
            return result1
        }
        // Fall through to safety net.
    } else {
        val lcmId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
        val lcmIdx = lcmId?.let { id ->
            agentHistory.indexOfLast { it.dbMessageId == id }
        } ?: -1
        val postCompactStart = lcmIdx + 1
        val result2 = mutableListOf<LLMMessage>()
        result2.add(summaryHead)
        if (postCompactStart < agentHistory.size) {
            result2.addAll(agentHistory.subList(postCompactStart, agentHistory.size))
        }
        repairSliceShape(result2)
        return result2
    }

    Log.w(ChatViewModel.TAG, "[Compact] effectiveAgentHistory: marker ${marker.id.take(8)} unresolvable in agentHistory (size=${agentHistory.size}); returning full history")
    return agentHistory.toList()
}

/**
 * [FIX-R4-P F-04] Repair the two illegal role shapes the compacted slice can
 * still produce AFTER [sanitizeAgentHistoryMessages] has run. Sanitize alone
 * cannot fix them, because the slice guards themselves create the damage:
 *
 * 1. **Leading ASSISTANT** — the pre-anchor peel loop strips non-USER heads to
 *    restore role alternation, but the boundary guard deliberately extends
 *    `priorIdx` BACK to include the paired `tool_use` assistant (so the
 *    tool_result keeps its parent). The peel then removes that assistant,
 *    leaving an orphaned `tool_result` USER at the head, which sanitize drops
 *    (no matching `tool_use` in the slice) — so the head becomes whatever came
 *    next, typically an ASSISTANT. Strict gateways (Anthropic, and OpenAI
 *    gateways that validate alternation) reject a conversation whose first
 *    message is not USER with a 400.
 * 2. **Consecutive USER** — when no plain-text USER exists post-anchor, the
 *    summary is appended as a standalone USER turn; if the last post-anchor
 *    entry is itself a USER (tool_result-only), that yields USER,USER. Same
 *    for the v1 path when `agentHistory[keepStart]` is a USER.
 *
 * The repair MERGES same-role neighbors instead of dropping either one. That is
 * what strict providers do implicitly (OpenAI) or accept via multi-part content
 * (Anthropic) — and appending keeps `tool_result` blocks at the head of their
 * USER message, which is where Anthropic requires them.
 *
 * Peel first (it can create a same-role pair by removing the middle message),
 * then merge left-to-right.
 */
internal fun normalizeSliceRoleAlternation(messages: MutableList<LLMMessage>) {
    // (1) First message must be USER.
    while (messages.isNotEmpty() && messages.first().role != LLMMessage.Role.USER) {
        messages.removeAt(0)
    }
    // (2) Merge same-role neighbors.
    var i = 1
    while (i < messages.size) {
        val prev = messages[i - 1]
        val cur = messages[i]
        if (prev.role == cur.role) {
            messages[i - 1] = mergeSameRoleNeighbors(prev, cur)
            messages.removeAt(i)
        } else {
            i++
        }
    }
}

/**
 * [fix/r5-c1-compact-C03①] Repair a compacted slice to a FIXPOINT: strip
 * orphan tool blocks → normalize roles → strip again, until a whole pass
 * changes nothing.
 *
 * The two repairs are not independent. [sanitizeAgentHistoryMessages] drops a
 * tool_result whose tool_use fell outside the slice, which can expose an
 * ASSISTANT at the head; [normalizeSliceRoleAlternation] then peels that
 * ASSISTANT, which orphans the tool_result right after it — a NEW illegal
 * shape produced by the repair that just ran. Running each exactly once in a
 * fixed order therefore still left the slice head as an orphan tool_result,
 * and strict gateways answer that with a 400 on the next request.
 *
 * Termination: a pass only removes messages, removes parts, or merges two
 * messages into one. The one additive step (placeholder injection for an
 * unmatched tool_use) is idempotent and removes itself from the equation the
 * moment its tool_use is peeled, so message and part counts are monotonically
 * non-increasing from the second pass on. [MAX_SLICE_REPAIR_PASSES] is a
 * safety net for a future edit that breaks that; hitting it is logged rather
 * than silently accepted.
 */
internal fun repairSliceShape(messages: MutableList<LLMMessage>) {
    var pass = 0
    while (pass < MAX_SLICE_REPAIR_PASSES) {
        val before = sliceShapeSignature(messages)
        sanitizeAgentHistoryMessages(messages)
        normalizeSliceRoleAlternation(messages)
        pass++
        if (sliceShapeSignature(messages) == before) return
    }
    Log.w(
        ChatViewModel.TAG,
        "[Compact] slice repair did not stabilise after $MAX_SLICE_REPAIR_PASSES passes " +
            "(size=${messages.size})",
    )
}

private const val MAX_SLICE_REPAIR_PASSES = 4

/**
 * Shape fingerprint for [repairSliceShape]: exactly what the two repairs can
 * change (roles, tool block ids, message count). Content text is deliberately
 * out — a merge concatenates text, which shows up as a part/message count
 * change anyway, and hashing megabytes of transcript every pass is wasteful.
 */
private fun sliceShapeSignature(messages: List<LLMMessage>): String {
    val sb = StringBuilder(messages.size * 8)
    for (m in messages) {
        sb.append(if (m.role == LLMMessage.Role.USER) 'u' else 'a')
        for (p in m.contentParts) {
            when (p) {
                is AgentContentPart.ToolUse -> sb.append("<u:").append(p.id).append('>')
                is AgentContentPart.ToolResult -> sb.append("<r:").append(p.id).append('>')
                else -> sb.append('.')
            }
        }
        if (m.content.isEmpty()) sb.append('_')
        sb.append('|')
    }
    return sb.toString()
}

/**
 * Serialization reads [LLMMessage.contentParts] when present and ignores
 * [LLMMessage.content] — lift a content-only message into a Text part, or a
 * merge would silently swallow its text.
 */
private fun slicePartsOf(m: LLMMessage): List<AgentContentPart> =
    if (m.contentParts.isNotEmpty()) {
        m.contentParts
    } else if (m.content.isNotEmpty()) {
        listOf(AgentContentPart.Text(m.content))
    } else {
        emptyList()
    }

private fun mergeSameRoleNeighbors(a: LLMMessage, b: LLMMessage): LLMMessage {
    val parts = slicePartsOf(a) + slicePartsOf(b)
    val content = listOf(a.content, b.content).filter { it.isNotEmpty() }.joinToString("\n")
    // [fix/r5-c1-compact-C03②] The two multimodal carriers live in their OWN
    // fields (LLMMessage.imageParts / audioParts) — they are not contentParts,
    // so `a.copy()` silently inherited a's and dropped b's. When the injected
    // summary head merges with a first kept message that still carried an
    // attachment (or two same-role turns are merged by the peel), the user's
    // image/audio vanished from the request with no error and no trace. Both
    // sides are concatenated in chronological order: a merge must never lose
    // an attachment.
    return a.copy(
        content = content,
        contentParts = parts,
        imageParts = a.imageParts + b.imageParts,
        audioParts = a.audioParts + b.audioParts,
        reasoningContent = a.reasoningContent ?: b.reasoningContent,
    )
}

/**
 * [FIX-R4-P F-05] True when a cached compaction marker no longer has any
 * anchor among the live transcript — i.e. the user rewound (Retry / Edit /
 * Rerun) past the message the marker was pinned to.
 *
 * Refuses to answer for a marker with no anchor ids at all (legacy/unknown
 * shape): "I cannot tell where this marker sits" must not be read as "it is
 * orphaned" and silently throw away a live summary.
 */
internal fun compactMarkerIsOrphaned(
    anchorIds: List<String>,
    liveDbIds: Set<String>,
): Boolean = anchorIds.isNotEmpty() && anchorIds.none { it in liveDbIds }

/**
 * [FIX-R4-P F-05] Drop the cached (and persisted) compaction markers when the
 * live transcript has been rewound past their anchor.
 *
 * Why this is needed even though `effectiveAgentHistory()` degrades safely for
 * the CURRENT process (an unresolvable anchor falls back to the full history):
 * `loadSession()` runs `applyCompactMarkerGraying`, whose `createdAt` fallback
 * RE-ANCHORS an orphaned marker to the newest surviving message and PERSISTS
 * the rewrite (`version = 2`, fresh `lcmId`). A retry that deleted the anchored
 * turn therefore resurrects the summary — now describing turns the user just
 * deleted — and pins it in front of the truncated transcript from then on.
 *
 * Deleting the markers instead is honest: after a rewind the recap of the old
 * history is no longer trustworthy, and the next auto-compact builds a fresh
 * one from what actually remains.
 */
internal suspend fun ChatViewModel.dropCompactMarkerIfRewound(sid: String) {
    val marker = _cachedLatestMarker ?: return
    val anchors = listOfNotNull(
        marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() },
        marker.firstKeptMessageId?.takeIf { it.isNotEmpty() },
        marker.boundaryMessageId?.takeIf { it.isNotEmpty() },
    )
    val liveIds = agentHistory.mapNotNullTo(mutableSetOf()) {
        it.dbMessageId?.takeIf(String::isNotEmpty)
    }
    if (!compactMarkerIsOrphaned(anchors, liveIds)) return
    val deleted = runCatching { chatRepository.dao.deleteCompactMarkers(sid) }
        .onFailure { Log.w(ChatViewModel.TAG, "[Compact] marker drop failed: ${it.message}") }
        .isSuccess
    _cachedLatestMarker = null
    _compactSummary.value = null
    // [fix/r5-c1-compact-C04③] Clearing the marker is the whole statement
    // "this fold is gone" — the transcript must stop LOOKING folded. What this
    // list still carries are the marker's two visible fragments: half-opacity
    // bubbles (`isCompactedHistory`, ChatScreen's grayedMap) and the divider
    // card, whose detail sheet offers "Revert Compact" — an affordance that now
    // answers sysmsg_revert_nothing, because the marker it would revert from is
    // the null written directly above. Reuse the silent-compact neutraliser
    // (drops divider rows, clears every fade flag) for the same reason it
    // exists there: no marker describes the current transcript any more.
    // Unconditional, mirroring the cache writes: the in-memory truth is what
    // the next revert/lookup reads, so the screen must not disagree with it.
    withContext(Dispatchers.Main) {
        _messages.value = neutralizeCompactArtifacts(_messages.value)
    }
    Log.w(
        ChatViewModel.TAG,
        "[Compact] history rewound past marker anchor (anchors=$anchors, live=${liveIds.size}, deleted=$deleted) — " +
            "marker cleared so its summary cannot be re-injected (or re-anchored by the createdAt heal)",
    )
}

/**
 * [FIX-R4-P F-02] Reset every context-pressure reading that was derived from
 * the PREVIOUS transcript. They are per-conversation state, but three entry
 * points rewrite or replace the transcript without resetting them:
 *
 * - `clearChat()` (the Context-Full dialog's "Clear Chat", and the drawer's
 *   clear action): the transcript is gone, yet `_lastTurnContextTokens` still
 *   holds the last turn's usage, so `contextPressure()` re-derives EXHAUSTED on
 *   the very next send and the dialog comes straight back for the new,
 *   three-message-long conversation.
 * - `loadSession()` when switching to a different session (or re-entering one
 *   after a clear): the reading belongs to the session we just left. A big
 *   session can therefore block sending in a small one.
 * - `retryFromMessage()` / `rerunFromToolBlock()` / edit-truncate: the
 *   transcript shrank, so the old reading is an overestimate that can trigger a
 *   pointless auto-compact of a now-short history.
 *
 * `rescueAttemptedForCompact` is part of the same cluster (it is the "this
 * transcript already burned its one rescue" latch) and is reset here for the
 * same reason: a rewound/cleared transcript deserves a fresh rescue budget.
 */
internal fun ChatViewModel.resetContextPressureState() {
    _lastTurnContextTokens.value = 0
    contextGrowthTracker.reset()
    lastAutoCompactAtMs = Long.MIN_VALUE
    rescueAttemptedForCompact = false
}

/**
 * [SYMPTOM-A F-A4] `[T-HANG-DIAG] messages-shape` 的"含工具"判据。
 *
 * 落盘方言是 **camelCase**：`ContentPart.Tool` 的 `@SerialName("toolUse")` 与
 * `ContentPart.Result` 的 `@SerialName("toolResult")`（ContentPart.kt:46 / :50），
 * 同一函数里 oversized 分支（判 `"toolUse"` / `"toolResult"`）本来就是 camel。
 * 旧判据只查 snake，于是 `toolMessages` **恒 0** —— 10-29 的 52 行
 * messages-shape 全是 `toolMessages=0`，其中包括 352 条消息 / 538K 字符的会话，
 * 已经把一次取证带偏（读成"这个会话没有工具调用"）。
 *
 * snake 保留为兼容臂：备份**导入**同时接受两种方言
 * （ConfigBackup.kt:2971 / :2988 的 `type == "toolUse" || type == "tool_use"`），
 * 所以库里可能有历史/外来行。保留它只会多计不会漏计，丢掉它会把老会话算成
 * 无工具。
 */
internal fun partsJsonHasToolParts(partsJson: String): Boolean =
    partsJson.contains("\"toolUse\"") || partsJson.contains("\"toolResult\"") ||
        partsJson.contains("\"tool_use\"") || partsJson.contains("\"tool_result\"")

/**
 * [SYMPTOM-A F-A4] `messages-shape` 的"含附件"判据。
 *
 * 与 [partsJsonHasToolParts] **不是一个病因**，别混着写：旧判据
 * （`"image"` / `"attachment"`）能命中图片行（`ContentPart` 早已没有 Image
 * 类型，但历史行与 oversized 分支仍在用 `"image"` / `"image_url"`），却完全
 * 漏掉 `ContentPart.Media` 的真实键 `@SerialName("mediaRef")`
 * （ContentPart.kt:42）—— 也就是**所有非图片附件在计数里消失**。
 */
internal fun partsJsonHasAttachmentParts(partsJson: String): Boolean =
    partsJson.contains("\"mediaRef\"") || partsJson.contains("\"image\"") ||
        partsJson.contains("\"attachment\"")

internal fun ChatViewModel.loadSession() {
    // T-android-crash-detected-halt: when CrashFrequencyDetector
    // tripped (#459, ≥3 crashes in last hour), skip the heavy
    // session-restore path entirely. Re-running the same persisted
    // state is exactly what produced the burst, so we'd just feed
    // a re-crash loop while the user is staring at the share dialog.
    // The flag clears the moment the dialog closes (share / dismiss /
    // cancel) — see CrashFrequencyDetector.maybeShowOnActivity.
    if (com.rikkaminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
        android.util.Log.w(ChatViewModel.TAG, "loadSession: safe-mode active, skipping session restore")
        // [T-android-perf-logging] Surface the skip on the Perf timeline
        // too — when a crash_or_stall recovery loop is suspected, this
        // distinguishes "loadSession ran and was slow" from "loadSession
        // was skipped (safe-mode), so the stall is elsewhere".
        com.rikkaminis.app.diagnostics.PerfLongCtx.step(
            sessionId,
            "loadSession.skipped",
            "reason=safeMode",
        )
        return
    }
    // [fix/compact-exhausted-rescue-1005] Opening a session is a user action:
    // re-arm the EXHAUSTED rescue gate (reset #3) so a freshly opened session
    // can rescue-compact once if the restored transcript is already past the
    // hard ceiling. Placed AFTER the safe-mode early return above: that path
    // skips session restore entirely (crash-recovery loop) — the user never
    // actually entered a session there, so it must not re-arm the gate.
    // [FIX-R4-P F-02] The reset is no longer the rescue latch alone: the
    // whole context-pressure cluster (_lastTurnContextTokens / growth tracker /
    // last-auto-compact timestamp) belongs to the transcript we just LEFT.
    // Keeping it across a session switch let a big session's reading block the
    // small one we just opened (and vice versa after clearChat).
    resetContextPressureState()
    viewModelScope.launch {
        // [T-HANG-DIAG] timing markers to localise where session entry
        // stalls. Sentinel-tagged so a single grep -v can strip them
        // when this diagnostic is removed. Declared OUTSIDE the try
        // block so the EXIT log in `finally` can still read it after
        // an early-return / exception path.
        val tHangDiagStart = System.currentTimeMillis()
        println("[T-HANG-DIAG] loadSession ENTER session=${SessionIdAliases.resolve(sessionId)} isDraft=$isDraft")
        com.rikkaminis.app.diagnostics.PerfLongCtx.step(sessionId, "loadSession.enter", "isDraft=$isDraft")
        try {
        val config = providerRepository.config.value
        _availableGroups.value = config.modelGroups

        if (isDraft) {
            // Draft session: just set up provider using default group or first entry
            _sessionTitle.value = "New Chat"
            _sessionCategory.value = null
            val effectiveGroupId = initialGroupId ?: providerRepository.defaultPrimaryGroupId
            var resolved = false
            if (effectiveGroupId != null) {
                resolved = resolveProviderFromGroup(effectiveGroupId)
                if (resolved) {
                    _selectedGroupId.value = effectiveGroupId
                    // T312: pull group session defaults onto the new draft.
                    // ensureSession will persist the override once the
                    // first message is sent and the DB row materialises.
                    applyGroupSessionDefaults(effectiveGroupId)
                }
            }
            if (!resolved) {
                // [T-newchat-default-model-fallback-android] No default
                // group (or it had no usable model) → last-used model, then
                // newest-provider/newest-text-model. Was firstOrNull().
                applyNewChatDefaultModel()
            }
            return@launch
        }

        // Existing session: load from DB
        val session = chatRepository.getSession(sessionId) ?: return@launch
        _sessionTitle.value = session.title ?: "New Chat"
        _sessionCategory.value = session.category
        _memoryEnabled.value = session.memoryEnabled != 0
        // [feat/hermes-tier1] Session switch → the frozen system prompt from
        // the previous conversation must not leak into this one. New
        // sessions materialise their id via ensureSession (draft → real id),
        // which also re-keys the cache; explicit invalidation here covers
        // the "open an existing conversation" path.
        invalidateSystemPromptCache()
        // T239: hydrate persisted thinking-mode override. null = unset
        // (use OFF as the legacy default); non-null = explicit user
        // choice persisted across cold-start. runCatching guards against
        // a stale enum name from a future rename — fall back silently
        // rather than crashing the session load.
        val persistedOverride = session.thinkingOverride
            ?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() }
        // [feat/thinking-global-remember] null override = the session never
        // had an explicit choice → fall through to the cross-conversation
        // last-tuned level (not hard OFF), so reopening an old chat also
        // shows what the user last tuned. A non-null per-session override
        // still wins.
        _thinkingLevel.value = persistedOverride
            ?: com.rikkaminis.app.data.ThinkingGlobalPrefs.lastLevel(context)
            ?: ThinkingLevel.OFF
        // [T-thinking-effective-level] A value already in the DB counts as an
        // explicit choice, so a later group (re-)selection must not clobber it
        // — see applyGroupSessionDefaults. Null override = never chose → the
        // group default may still seed this session.
        //
        // Known limitation (ponytail): we cannot tell a value the USER set from
        // one a group default previously persisted, so a group whose default
        // later changes won't reach this session. Acceptable trade — silently
        // reverting a manual choice is the worse failure. Upgrade path: a
        // thinking_override_source column.
        thinkingLevelUserSet = persistedOverride != null

        // Priority 1: restore from persisted model_binding (group or entry)
        var resolved = restoreFromBinding(session.modelBinding)

        // Priority 2: fall back to stored model_id
        if (!resolved) {
            val entry = findModelEntry(session.modelId)
            if (entry != null) {
                // [da1019-FIX-E] Resolve + validate BEFORE writing any state.
                // The old order wrote currentModel / _modelName / _activeEntryId
                // first and only then looked the instance + credential up, so a
                // disabled provider or a missing API key left the UI reporting
                // "model ready" while currentProvider stayed null — and handed
                // Priority 3 a surface that already looked resolved.
                val instance = providerRepository.instance(entry.providerInstanceId)
                val apiKey = instance?.let { providerRepository.loadApiKey(it.id) }
                val decision = decideSessionModelResolution(entry, instance, apiKey)
                if (decision is SessionModelResolution.Use) {
                    // [deep-audit-1044 B-03] The provider — and the `currentModel`
                    // snapshot the whole session reads — must carry the ENRICHED
                    // model: `entry.model` is `baseModel.withOverrides(…)`, which
                    // never touches models.dev, so 33 of the 39 built-ins lost
                    // their modalities / effort tiers / output cap the moment the
                    // request crossed into `:modelservice` (a process that never
                    // initialises ModelsDevApi).
                    val effectiveModel = providerModelFor(entry)
                    currentModel = effectiveModel
                    _modelName.value = effectiveModel.displayName
                    _activeEntryId.value = entry.id
                    currentProvider =
                        ProviderFactory.create(decision.instance, decision.apiKey, effectiveModel, context)
                    _providerName.value = decision.instance.label.ifEmpty { effectiveModel.provider }
                    resolved = true
                    // No binding row (e.g. a synced session that only
                    // carried model_id). If the entry belongs to the
                    // default group, adopt that group so group fallback
                    // works — otherwise buildFallbackProviders returns
                    // empty and provider errors never fall back. NOT
                    // applied to an explicit "entry" binding (user pin),
                    // which restoreFromBinding handles above. Mirrors
                    // the iOS runAgentLoop group-discovery fix.
                    val defaultGroupId = providerRepository.defaultPrimaryGroupId
                    if (defaultGroupId != null &&
                        providerRepository.group(defaultGroupId)?.memberEntryIds?.contains(entry.id) == true
                    ) {
                        _selectedGroupId.value = defaultGroupId
                    }
                } else if (decision is SessionModelResolution.Unusable) {
                    logSessionModelUnusable("loadSession-priority2", entry.id, decision.reason)
                }
            }
        }

        // Priority 3: fall back to default group
        if (!resolved) {
            val defaultGroupId = providerRepository.defaultPrimaryGroupId
            if (defaultGroupId != null) {
                resolved = resolveProviderFromGroup(defaultGroupId)
                if (resolved) _selectedGroupId.value = defaultGroupId
            }
        }

        // [T-HANG-DIAG] measure DB load + transform separately so a long
        // load on one stage is obvious in the trace.
        //
        // T-android-gc-storm-hang-crash (P0, issue #17): on a 405-message
        // session with one 397KB user row, loadMessages + toChatMessages
        // + the agentHistory rebuild below ran on Main and triggered a
        // GC storm (34MB freed, repeated) that blocked the frame loop for
        // 58s → crash_or_stall restart. Hoist the heavy DB + JSON-parse
        // work off Main so the UI thread stays responsive even when one
        // row is large. Stays inside the existing safe-mode guard above
        // (#466/#470) — we only move work, not gating.
        val tHangDiagBeforeLoad = System.currentTimeMillis()
        data class LoadedSessionData(
            val messages: List<com.rikkaminis.app.data.db.MessageEntity>,
            val ordered: List<ChatMessage>,
            val llmHistory: List<LLMMessage>,
            val loadMs: Long,
            val transformMs: Long,
        )
        com.rikkaminis.app.diagnostics.PerfLongCtx.step(sessionId, "db.query.begin")
        val loaded = withContext(Dispatchers.IO) {
            val tIoBeforeLoad = System.currentTimeMillis()
            val rows = chatRepository.loadMessages(sessionId)
            val tIoAfterLoad = System.currentTimeMillis()
            com.rikkaminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "db.query.end",
                "count=${rows.size}",
            )
            // Parse partsJson once, then build both UI and LLM representations
            // from the parsed data. Eliminates the duplicate JSONArray/JSONObject
            // allocations that were the second contributor to the GC storm
            // (see T-android-gc-storm-hang-crash).
            val parsed = parseRows(rows)
            val chatUi = buildChatMessages(parsed)
            val tIoAfterTransform = System.currentTimeMillis()
            com.rikkaminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "toChatMessages.end",
                "count=${chatUi.size}",
            )
            val llm = buildLlmMessages(parsed)
            var totalPartsChars = 0L
            for (row in parsed) {
                totalPartsChars += row.sourceChars
            }
            com.rikkaminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "toLLMMessage.end",
                "count=${llm.size} totalPartsChars=$totalPartsChars",
            )
            LoadedSessionData(
                messages = rows,
                ordered = chatUi,
                llmHistory = llm,
                loadMs = tIoAfterLoad - tIoBeforeLoad,
                transformMs = tIoAfterTransform - tIoAfterLoad,
            )
        }
        val messages = loaded.messages
        val ordered = loaded.ordered
        val tHangDiagAfterLoad = tHangDiagBeforeLoad + loaded.loadMs
        val tHangDiagAfterTransform = tHangDiagAfterLoad + loaded.transformMs
        println(
            "[T-HANG-DIAG] loadMessages session=${SessionIdAliases.resolve(sessionId)} count=${messages.size} " +
                "tookMs=${loaded.loadMs}",
        )
        println(
            "[T-HANG-DIAG] toChatMessages session=${SessionIdAliases.resolve(sessionId)} tookMs=${loaded.transformMs}",
        )
        // Per-message size sketch + oversize-row scan. Pure diagnostics —
        // does a full second pass over partsJson with several substring
        // searches per row, so on a 405-row session with 1MB total it
        // adds material main-thread time. Fire-and-forget on the IO
        // dispatcher so it can't contribute to the GC-storm hang the
        // rest of this task is trying to fix.
        viewModelScope.launch(Dispatchers.IO) {
            var totalChars = 0L
            var maxChars = 0
            var withTools = 0
            var withAttachments = 0
            for (m in messages) {
                val len = m.partsJson.length
                totalChars += len
                if (len > maxChars) maxChars = len
                if (partsJsonHasToolParts(m.partsJson)) {
                    withTools++
                }
                if (partsJsonHasAttachmentParts(m.partsJson)) {
                    withAttachments++
                }
            }
            println(
                "[T-HANG-DIAG] messages-shape session=${SessionIdAliases.resolve(sessionId)} total=${messages.size} " +
                    "totalChars=$totalChars maxChars=$maxChars toolMessages=$withTools " +
                    "attachmentMessages=$withAttachments",
            )

            // [T-HANG-DIAG] for any message ≥ 50_000 chars, log size /
            // role / createdAt / structural type markers only — NEVER
            // the partsJson content (or any prefix/suffix of it). Earlier
            // versions echoed head500/tail500 to localise the culprit;
            // now that the cause is known (oversized tool_result inlines)
            // and FileReadTool / AIChatViewModel.executeFileRead enforce
            // an 80 KB hard cap upstream, only metadata is needed for
            // future audits.
            val OVERSIZE_THRESHOLD = 50_000
            val oversized = messages.filter { it.partsJson.length >= OVERSIZE_THRESHOLD }
            if (oversized.isNotEmpty()) {
                println(
                    "[T-HANG-DIAG] oversized-messages session=${SessionIdAliases.resolve(sessionId)} " +
                        "count=${oversized.size} threshold=${OVERSIZE_THRESHOLD}",
                )
                for (m in oversized) {
                    val raw = m.partsJson
                    val len = raw.length
                    val hasToolUse = raw.contains("\"toolUse\"")
                    val hasToolResult = raw.contains("\"toolResult\"")
                    val hasImage = raw.contains("\"image\"") || raw.contains("\"image_url\"")
                    val hasBase64 = raw.contains("data:image") || raw.contains(";base64,")
                    println(
                        "[T-HANG-DIAG] oversized id=${m.id} role=${m.role} " +
                            "createdAt=${m.createdAt} len=$len " +
                            "hasToolUse=$hasToolUse hasToolResult=$hasToolResult " +
                            "hasImage=$hasImage hasBase64=$hasBase64 " +
                            "streamInterrupts=${m.streamInterruptCount}",
                    )
                }
            }
        }

        // Rebuild agentHistory from persisted messages.
        // Pre-built off-Main inside the withContext(Dispatchers.IO) block
        // above to avoid re-parsing partsJson on the UI thread.
        //
        // [audit-0920] `clear()` is load-bearing: loadSession() is NOT
        // once-per-VM. `revertCompact()` (ChatViewModel, reached from the
        // "Revert Compact" button on the compact divider in ChatScreen) and
        // the safe-mode-cleared retry both call it again on the SAME VM, and
        // neither the `_sessionLoaded` latch (set in this function's `finally`,
        // awaited exactly once at init) nor anything else gates re-entry. The
        // old comment claimed "loadSession runs once at init" — that has been
        // false since revertCompact landed. Without this clear the second pass
        // appended the whole persisted history on top of the existing one:
        // `_messages` is REPLACE-semantics so the UI looked right, but every
        // subsequent request carried the history twice (duplicate user turns +
        // doubled token estimate, which can trip offload/compact early). The
        // other five rebuild sites in this package (ChatModelRouting,
        // ChatQueueInterruption, ChatViewModel x2, ChatContextWindow's trim)
        // already pair clear()+addAll.
        val inFlightExtras = if (_isStreaming.value) {
            // [fix/send-dispatch-race] Fallback for the §76 race: a dispatch
            // that raced this rebuild already appended its user message to
            // agentHistory (sendMessage is synchronous on Main), while
            // loaded.llmHistory was snapshotted off-Main BEFORE that append
            // committed to the DB. The blind clear() below would then wipe
            // the instruction and the run starts with historySize=0.
            // HeadlessChatRunner now awaits _sessionLoaded first (root fix);
            // this preserves any in-flight send's not-yet-snapshotted
            // messages if a rebuild still races it. Scoped to _isStreaming so
            // revertCompact's second pass (not streaming) keeps its
            // load-bearing clear (audit-0920) and can never resurrect stale
            // pre-compact history.
            val loadedIds = HashSet<String>()
            for (m in loaded.llmHistory) {
                m.dbMessageId?.let { loadedIds.add(it) }
            }
            agentHistory.filter { msg ->
                val id = msg.dbMessageId
                id != null && id !in loadedIds
            }
        } else {
            emptyList()
        }
        agentHistory.clear()
        agentHistory.addAll(loaded.llmHistory)
        if (inFlightExtras.isNotEmpty()) {
            // The in-flight dispatch turn is the newest entry — it belongs
            // at the tail, after the persisted snapshot.
            agentHistory.addAll(inFlightExtras)
            Log.i(
                ChatViewModel.TAG,
                "[fix/send-dispatch-race] loadSession raced an in-flight send: preserved ${inFlightExtras.size} not-yet-persisted message(s)",
            )
        }
        val tHangDiagAfterAgentHistory = System.currentTimeMillis()
        println(
            "[T-HANG-DIAG] agentHistory rebuilt session=${SessionIdAliases.resolve(sessionId)} tookMs=${tHangDiagAfterAgentHistory - tHangDiagAfterTransform}",
        )

        // Restore the most-recent compact summary, if any, so the first
        // outgoing turn after reopening a compacted session still sees
        // the folded-away context via [effectiveAgentHistory]. Also gray
        // out every UI message that falls before the marker's boundary —
        // mirrors iOS Phase 2.5 restore (AIChatViewModel.swift:3360+).
        val marker = runCatching { chatRepository.dao.latestCompactMarker(sessionId) }
            .onFailure { Log.w(ChatViewModel.TAG, "latestCompactMarker failed: ${it.message}") }
            .getOrNull()
        _compactSummary.value = marker?.summary
        _cachedLatestMarker = marker

        com.rikkaminis.app.diagnostics.PerfLongCtx.step(
            sessionId,
            "stateflow.emit.begin",
            "count=${ordered.size}",
        )
        // [T-android-larky-longsession-followup] Reset the tail
        // window to its initial cap on every session (re)load. Without
        // this a freshly opened session would inherit the previous
        // session's enlarged cap (set via loadOlderMessages), defeating
        // the windowing intent on the first paint of every new session.
        _visibleMessageCap.value = ChatViewModel.INITIAL_VISIBLE_MESSAGE_CAP
        _messages.value = if (marker == null) {
            ordered
        } else {
            // Phase 2.5: build the historyDbIds set used by the
            // createdAt self-heal to filter to anchors that are
            // actually represented in agentHistory. Mirrors iOS
            // AIChatViewModel+Persistence.swift:406-408.
            val historyDbIds: Set<String> = buildSet {
                for (m in loaded.llmHistory) {
                    m.dbMessageId?.takeIf { it.isNotEmpty() }?.let { add(it) }
                }
            }
            applyCompactMarkerGraying(ordered, marker, loaded.messages, historyDbIds)
        }

        // [T-android-thinking-indicator-linger] Session (re)load rebuilds
        // _messages from DB rows — any in-memory streaming side-channel
        // entry is a leftover from a previous session/turn (DB messages
        // are always isStreaming=false), so drop it. Without this, the
        // stale delta would render a "thinking" row pinned to a message
        // after switching sessions.
        _streamingById.value = emptyMap()

        // [fix/same-class-cleanup] The rebuild above is the ROOT of the
        // queued-bubble drop (queued prompts are UI-only — they never
        // persist), so the re-attach lives HERE rather than in the
        // reloadSessionFromDb wrapper: every loadSession caller (init,
        // safe-mode-cleared retry, revertCompact's reload, and any future
        // one) inherits it. At init/retry the queue is empty (fresh VM), so
        // this is a no-op there. Mirrors the reload wrapper's re-attach,
        // which this supersedes.
        val droppedQueued = _promptQueue.value.filter { q ->
            _messages.value.none { it.queuedPromptId == q.id }
        }
        if (droppedQueued.isNotEmpty()) {
            _messages.value = _messages.value + droppedQueued.map { queuedPromptBubble(it) }
        }

        // Cold-start interrupt detection: an agent loop that was killed by
        // the OS (or app force-quit) leaves agentHistory in one of four
        // tell-tale shapes. Detecting any of them lets the user tap
        // Resume to pick up where the model left off — the in-memory
        // [_canResume] flag set by [handleUserCancelledCleanup] is lost
        // across cold starts so we have to re-derive it from the DB.
        // Mirrors iOS AIChatViewModel.loadSession lines 3546-3581.
        // The shape rule itself lives in
        // [com.rikkaminis.app.agent.InterruptedTailDetector] (shared with the
        // badge scan); only the liveness gate is session-specific.
        //   Case A: last entry is user with all-toolResult parts —
        //           tools completed but the next model call never fired.
        //   Case B: last entry is assistant with any tool_use parts —
        //           the model requested tools that never executed.
        //   Case C: last entry is user with the synthetic "Continue"
        //           reminder text — text-cancel handler committed it
        //           but [resume] never re-entered the agent loop.
        //   Case D: last entry is a PLAIN-TEXT user turn that never got a
        //           reply at all — see below (GH#262/#263).
        //
        // [S5-resume-guard] Why this predicate deliberately does NOT also
        // check "was the last tool result known?" (audit finding §27c(2)):
        //
        // The two questions live on different layers. This one is
        // "session-level: is there a valid starting point for the next API
        // call?", and the answer is yes whenever the shape holds — no tool is
        // re-executed here (Case A appends nothing — see [resume] — the
        // Continue reminder is only emitted when history ends with the
        // ASSISTANT). The OutcomeUnknown concern quoted at
        // ExecutionCoordinator.internalShouldRetryCommand belongs to
        // "command-level: should the SAME command be re-sent?" — a different
        // decision, made before this one ever runs.
        //
        // The "result may be unknown" warning is already carried to the model
        // inside the tool_result CONTENT, on every path that can produce an
        // interrupted tail: the timeout line written by PersistentShell, the
        // cancellation reminder in ChatModels.CANCELLED_MARKER, and
        // SANITIZE_PLACEHOLDER_RESULT_CONTENT for a tool_use whose result
        // never landed. The model therefore does not resume from a silently
        // false premise — so adding a second check here would duplicate a
        // warning rather than supply a missing one.
        val lastEntry = agentHistory.lastOrNull()
        // [T-android-orphan-user-tail GH#262/#263] `isActive` covers the case
        // this VM cannot see: another VM (or the foreground service) is driving
        // this very session, so `_isStreaming` is false HERE while a request is
        // genuinely in flight THERE. Without it, Case D would light Resume on a
        // turn that is merely still waiting. `activeSessionId` (not
        // `sessionId`) because every tracker write uses it — a draft VM keeps
        // its `__new__` key while the tracker holds the canonical id.
        val trackerActive = SessionActivityTracker.isActive(activeSessionId)
        val tailShape = if (lastEntry == null || _isStreaming.value || trackerActive) {
            null
        } else {
            InterruptedTailDetector.classify(
                InterruptedTailSnapshot(
                    role = lastEntry.role.value,
                    partKinds = lastEntry.contentParts.map { p ->
                        when (p) {
                            is AgentContentPart.Text -> InterruptedTailPartKind.TEXT
                            is AgentContentPart.ToolUse -> InterruptedTailPartKind.TOOL_USE
                            is AgentContentPart.ToolResult -> InterruptedTailPartKind.TOOL_RESULT
                            else -> InterruptedTailPartKind.OTHER
                        }
                    },
                    firstText = (lastEntry.contentParts.firstOrNull() as? AgentContentPart.Text)?.text,
                ),
            )
        }
        if (tailShape != null && tailShape != InterruptedTailShape.NONE) {
            _canResume.value = true
            // Name the shape, not just the role: Case D (reply-less user
            // tail) is the one that used to be invisible, so a field log has
            // to be able to tell it from A/B/C.
            Log.i(ChatViewModel.TAG, "loadSession: detected interrupted agent loop, canResume=true (lastRole=${lastEntry?.role} shape=$tailShape)")
        }
        } finally {
            // T201: open the gate even on early `return@launch` (draft path,
            // missing-session path) and on exception, so the init-time
            // config.collect can never deadlock waiting for us.
            _sessionLoaded.value = true
            // [T-HANG-DIAG] total time spent in loadSession from ENTER to
            // either successful completion or early return. tHangDiagStart
            // was captured just inside `try` so this covers the whole
            // body the user perceives as "loading".
            println(
                "[T-HANG-DIAG] loadSession EXIT session=${SessionIdAliases.resolve(sessionId)} " +
                    "totalMs=${System.currentTimeMillis() - tHangDiagStart}",
            )
            com.rikkaminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "loadSession.exit",
                "totalMs=${System.currentTimeMillis() - tHangDiagStart}",
            )
        }
    }
}

/**
 * Mark every non-system UI message that falls before [marker]'s boundary
 * as [ChatMessage.isCompactedHistory]. Mirrors iOS Phase 2.5 boundary
 * resolution (AIChatViewModel.swift:3380-3411) but with one improvement
 * over iOS for the compactAll case:
 *
 *   1) `firstKeptMessageId` — first kept message (divider goes BEFORE it)
 *   2) `boundaryMessageId`  — legacy alias of firstKeptMessageId
 *   3) Both null → compactAll. iOS naively places the divider at the end
 *      and grays every loaded UI message, which incorrectly gray-scales
 *      messages persisted AFTER the marker (e.g. follow-up turns sent
 *      between compact and reload). We instead use
 *      `lastCompactedMessageId` to find the last message included in the
 *      compacted range — anything after it stays active. The divider is
 *      placed immediately after that boundary.
 */
/**
 * Phase 2.5 marker restore (Android port of iOS
 * AIChatViewModel+Persistence.swift:236+).
 *
 * Resolution order (mirrors iOS exactly):
 *   1. v2 marker (`version >= 2`) — use `lastCompactedMessageId`
 *      via sourceDbIds range → divider AFTER that UI row
 *   2. v1 compactAll-shape (firstKept/boundary both null,
 *      lcmId set) — same as 1
 *   3. v1 compactBefore (firstKeptMessageId / boundaryMessageId
 *      set) — divider BEFORE that boundary row
 *   4. **createdAt self-heal** — find the last raw with
 *      `createdAt < marker.createdAt` whose id is still in
 *      agentHistory, use it as the new anchor, REWRITE the
 *      marker as v2 + write back to DB. Next load takes the
 *      v2 fast path (no heal needed).
 *   5. Final fallback — insert divider at idx=0, gray NOTHING.
 *      This deliberately differs from the pre-T-compact-v2
 *      behaviour of "divider at bottom, gray everything" which
 *      grayed newly-sent messages on every reload (the
 *      user-reported "divider at top, new messages keep
 *      turning gray" symptom).
 *
 * Suspending because the self-heal path writes back through
 * the DAO. Caller (loadSession) is already on a coroutine.
 */
internal suspend fun ChatViewModel.applyCompactMarkerGraying(
    messages: List<ChatMessage>,
    marker: com.rikkaminis.app.data.db.CompactMarkerEntity,
    rawMessages: List<com.rikkaminis.app.data.db.MessageEntity>,
    historyDbIds: Set<String>,
    ): List<ChatMessage> {
    // Some legacy rows have empty-string boundaries instead of NULL —
    // treat both as "no boundary" so the compactAll path below kicks in.
    val firstKeptId = (marker.firstKeptMessageId?.takeIf { it.isNotEmpty() })
        ?: (marker.boundaryMessageId?.takeIf { it.isNotEmpty() })
    val lcmId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }

    // ─── Resolve insertIdx ────────────────────────────────────────
    //
    // [fix/silent-auto-compact] This resolution no longer drives any
    // rendering — graying and the divider row are both gone. `insertIdx` is
    // the field the restore log line below prints, plus the trigger for the
    // createdAt self-heal.
    //
    // [SYMPTOM-A F-A3] The self-heal branch is NOT the load-bearing mechanism
    // this comment used to describe. Measured reality (2026-10-29 full log,
    // corroborated in source):
    //
    //  - `lastCompactedMessageId` usually anchors on a role=user row that
    //    carries only tool carriers (toolUse / toolResult parts; no visible
    //    text, no image, no attachment). `buildChatMessagesTranscript` drops
    //    exactly those rows — ChatTranscriptRebuild.kt:224 returns
    //    `@mapNotNull null` through the shared predicate
    //    `userRowHasVisibleContent` (:36-44) — so `uiIdxForDbId` below answers
    //    -1 for an anchor that is perfectly valid in agentHistory.
    //  - the heal candidate must then pass the SAME UI gate (:1123 requires
    //    `healUiIdx >= 0`) ⇒ `healedMarker` stays null ⇒ nothing is persisted
    //    ⇒ the next session open repeats the identical attempt (10-29: 12
    //    marker restores, 9 unresolved, one marker repeated 6x).
    //  - user-visible consequence: NONE. The summary is injected through
    //    `effectiveAgentHistory()`, which parses the marker against
    //    agentHistory independently (3 days: 1726 slices, 0 degradations).
    //    The price is one WARN per open plus one bounded, wasted heal scan.
    //
    // Deliberately NOT relaxing the :1123 acceptance gate: accepting an anchor
    // that is not in the UI list would turn "one WARN per open" into "one DB
    // write per open" while the anchor stays invisible — write amplification,
    // zero user benefit.
    //
    // UPGRADE TRIGGER: if any UI artefact starts depending on `insertIdx`
    // again (divider, graying, an "already folded" marker), fix the :1123 gate
    // FIRST — accept "present in historyDbIds" instead of "present in the UI
    // list" — or 9 of 12 tool-heavy sessions will silently misplace it.
    //
    // Special value -1 → "unresolved": no heal candidate either.
    var insertIdx = -1
    var healedMarker: com.rikkaminis.app.data.db.CompactMarkerEntity? = null

    // Helper: locate the UI message whose sourceDbIds (or id) contains
    // the given dbId. Matches iOS uiIndexForAnchorRaw, which scans by
    // sourceSortOrder range; Android's equivalent is sourceDbIds.
    fun uiIdxForDbId(dbId: String): Int =
        messages.indexOfLast { msg -> dbId in msg.sourceDbIds || msg.id == dbId }

    if (firstKeptId == null) {
        // v2 OR v1 compactAll-shape — anchored by lcmId.
        val lcmIdx = lcmId?.let { uiIdxForDbId(it) } ?: -1
        if (lcmIdx >= 0) {
            // Happy path: lcmId resolves directly. Divider AFTER anchor.
            insertIdx = lcmIdx + 1
        } else {
            // lcmId missing or orphaned. Try createdAt self-heal.
            val heal = healAnchorByCreatedAt(rawMessages, marker.createdAt, historyDbIds, marker.compactedCount)
            val healUiIdx = heal?.let { uiIdxForDbId(it.id) } ?: -1
            if (heal != null && healUiIdx >= 0) {
                insertIdx = healUiIdx + 1
                healedMarker = rewriteMarkerForHeal(marker, heal, rawMessages.lastOrNull())
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "[Compact] Phase2.5 self-heal: orphaned lcmId=${lcmId?.take(8) ?: "nil"} " +
                        "→ newAnchor=${heal.id.take(8)} (createdAt=${heal.createdAt}) " +
                        "→ uiIdx=$healUiIdx insertIdx=$insertIdx",
                )
            } else {
                // Even createdAt heal failed. Place divider at top
                // with NO graying — this is iOS's "insertIdx=0, no
                // gray" branch (Persistence.swift:350-351). The
                // pre-T-compact-v2 behaviour of "cutoff = lastIndex,
                // gray everything" produced the user-reported bug:
                // every new message also fell within [0..cutoff]
                // and was repeatedly grayed on each reload.
                insertIdx = 0
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "[Compact] Phase2.5 unresolved (heal failed): marker.id=${marker.id.take(8)} " +
                        "lcmId=${lcmId?.take(8) ?: "nil"} — divider at top, no graying",
                )
            }
        }
    } else {
        // v1 compactBefore — anchored by firstKeptId. Divider BEFORE
        // the boundary; boundary is the first active message.
        val bIdx = messages.indexOfFirst { msg ->
            firstKeptId in msg.sourceDbIds || msg.id == firstKeptId
        }
        if (bIdx >= 0) {
            insertIdx = bIdx
        } else {
            // Boundary deleted / orphaned. Try createdAt self-heal —
            // same path as compactAll, then divider AFTER the healed
            // anchor (treating this as an upgrade to v2 compactAll
            // semantics).
            val heal = healAnchorByCreatedAt(rawMessages, marker.createdAt, historyDbIds, marker.compactedCount)
            val healUiIdx = heal?.let { uiIdxForDbId(it.id) } ?: -1
            if (heal != null && healUiIdx >= 0) {
                insertIdx = healUiIdx + 1
                healedMarker = rewriteMarkerForHeal(marker, heal, rawMessages.lastOrNull())
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "[Compact] Phase2.5 v1→v2 heal: firstKeptId=${firstKeptId.take(8)} orphaned " +
                        "→ newAnchor=${heal.id.take(8)} → uiIdx=$healUiIdx",
                )
            } else {
                insertIdx = 0
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "[Compact] Phase2.5 v1 unresolved (heal failed): firstKeptId=${firstKeptId.take(8)} — " +
                        "divider at top, no graying",
                )
            }
        }
    }

    // ─── Persist healed marker (if any) ───────────────────────────
    //
    // Run BEFORE building the UI list so a future loadSession() picks
    // up the v2 fast path. Failure here is non-fatal — UI still
    // renders against the in-memory healed pointer.
    if (healedMarker != null) {
        runCatching { chatRepository.dao.updateCompactMarker(healedMarker) }
            .onFailure { Log.w(ChatViewModel.TAG, "updateCompactMarker (self-heal) failed: ${it.message}") }
        // Refresh in-memory cache so effectiveAgentHistory and the
        // next compact pass see the upgraded marker. The caller
        // (loadSession) sets _cachedLatestMarker = marker BEFORE
        // calling us, so overwrite with the healed one now.
        _cachedLatestMarker = healedMarker
        _compactSummary.value = healedMarker.summary
    }

    // ─── Apply graying + divider ──────────────────────────────────
    // [fix/silent-auto-compact] Neither is applied any more. The marker is
    // an agent-side context boundary; dimming the rows it covers told the
    // user "something happened here" without telling them anything
    // actionable, and the divider card was the visible artefact they
    // objected to. A card that only appeared after a cold reload (but not
    // during the live session) would be a worse surprise than one shown
    // consistently, so the reload path drops it too.
    //
    // `insertIdx` is still resolved above, but not for the reason this comment
    // used to give. [SYMPTOM-A F-A3] The self-heal does NOT keep
    // effectiveAgentHistory() working — that path parses the marker against
    // agentHistory on its own and never reads `insertIdx` (3 days: 1726
    // slices, 0 degradations). For tool-heavy sessions the anchor row is
    // invisible in the UI transcript, so the heal cannot resolve and nothing
    // gets persisted; see the block above ("Resolve insertIdx") for the full
    // chain and for the upgrade trigger that would make this gate load-bearing
    // again.
    // "Revert Compact" now lives in the message long-press menu, so it no
    // longer depends on the divider row existing.
    AppLogger.info(
        ChatViewModel.TAG,
        "[Compact] marker restore: boundaryIdx=$insertIdx healed=${healedMarker != null} " +
            "v=${(healedMarker ?: marker).version} — no graying, no divider (silent)",
    )
    return messages
}

/**
 * createdAt self-heal, BOUNDED — [fix/r5-c1-compact-F02].
 *
 * The marker's `createdAt` is the compact EXECUTION instant, not the anchor's:
 * every kept-tail message that already existed when the compact ran also has
 * `createdAt < marker.createdAt`. The old "last raw before marker.createdAt"
 * answer therefore walked straight past the folded block into that kept tail
 * and folded turns the summary never covered — unbounded, the self-heal could
 * only ever DESTROY (it always over-extended, not occasionally: any compact
 * that leaves a pre-existing tail sets the trap).
 *
 * The one boundary signal a marker still carries is `compactedCount` (the
 * folded block's length). The healed anchor is taken at `compactedCount - 1`
 * among the pre-marker candidates, which can only land AT or BEFORE the true
 * anchor — a later-round block starts after the previous anchor, and
 * [rawMessages] may hold rows the LLM history filters out (system/divider
 * rows), both of which push the index earlier. The heal can only NARROW the
 * folded range, never widen it: the worst case re-sends already-summarised
 * text verbatim, which costs tokens, never data. [historyDbIds] keeps
 * filtering to ids the loaded history still represents.
 *
 * Refusals (the caller falls back to "divider at top, no graying"):
 *  - `compactedCount <= 0` — no block length to bound with;
 *  - fewer pre-marker candidates than `compactedCount` while rows arrived
 *    after the marker — the survivors are no longer provably a prefix of the
 *    folded block, so "the last survivor is inside it" cannot be asserted;
 *  - no candidates at all.
 *
 * Mirrors iOS AIChatViewModel+Compaction.swift:125, which keeps the unbounded
 * form; this port deliberately does not — the unbounded form was falsified by
 * meta-audit-1029 R5-C F-02.
 */
internal fun healAnchorByCreatedAt(
    rawMessages: List<MessageEntity>,
    markerCreatedAt: Long,
    historyDbIds: Set<String>,
    compactedCount: Int,
): MessageEntity? {
    if (compactedCount <= 0) return null
    val candidates = rawMessages.filter { raw ->
        raw.createdAt < markerCreatedAt &&
            (historyDbIds.isEmpty() || raw.id in historyDbIds)
    }
    if (candidates.isEmpty()) return null
    if (candidates.size >= compactedCount) return candidates[compactedCount - 1]
    // Fewer survivors than were folded: the transcript was truncated INTO the
    // folded block. The last survivor is inside it only when nothing arrived
    // after the marker (no kept tail exists to be reached past).
    return if (candidates.size == rawMessages.size) candidates.last() else null
}

/**
 * Build a healed v2 marker that preserves identity (id, sessionId,
 * summary, createdAt, compactedCount) but swaps `lastCompactedMessageId`
 * to the recomputed anchor, zeroes legacy fields, and bumps `version`
 * to 2. Future loads resolve through the corrected lcmId directly
 * without re-running the createdAt fallback.
 *
 * Mirrors iOS AIChatViewModel+Compaction.swift:150.
 */
internal fun ChatViewModel.rewriteMarkerForHeal(
    original: com.rikkaminis.app.data.db.CompactMarkerEntity,
    newAnchor: com.rikkaminis.app.data.db.MessageEntity,
    lastRaw: com.rikkaminis.app.data.db.MessageEntity?,
    ): com.rikkaminis.app.data.db.CompactMarkerEntity {
    // Legacy sort-order fallback writes a past-end sentinel so any
    // hypothetical v1 reader sees "everything compacted, nothing
    // kept" (graceful degradation, no overlap with live tail).
    // Android's MessageEntity doesn't carry a sortOrder column —
    // use Int.MAX_VALUE like the original compactAll write path.
    return original.copy(
        firstKeptSortOrder = Int.MAX_VALUE,
        boundaryMessageId = null,
        firstKeptMessageId = null,
        lastCompactedMessageId = newAnchor.id,
        uiBoundarySortOrder = null,
        version = 2,
    )
}

/**
 * [da1019-FIX-E] Why a session model binding could not be applied. An enum (not
 * a string) so callers and tests assert on the exact cause.
 */
internal enum class UnusableModelReason { MISSING_INSTANCE, PROVIDER_DISABLED, MISSING_API_KEY }

/**
 * [da1019-FIX-E] Outcome of resolving a session's (entry, instance, credential)
 * triple. [Use] carries the validated parts so the caller does not repeat the
 * null checks that the decision already performed.
 */
internal sealed interface SessionModelResolution {
    /** All parts present and the provider is enabled — safe to apply. */
    data class Use(val entry: ModelEntry, val instance: ProviderInstance, val apiKey: String) :
        SessionModelResolution

    /**
     * A precondition failed. The caller must not write any provider state —
     * see the state-pollution note on [decideSessionModelResolution].
     */
    data class Unusable(val reason: UnusableModelReason) : SessionModelResolution
}

/**
 * [da1019-FIX-E] Single decision point for the three session-model resolution
 * sites ([restoreFromBinding]'s `"entry"` branch, [loadSession] priority 2 and
 * [applyNewChatDefaultModel]).
 *
 * Enforces two contracts that used to be applied inconsistently:
 *  1. A disabled provider is never used at runtime. This mirrors
 *     `ProviderRepository.enabledMemberEntries` /
 *     [resolveProviderFromGroup], whose KDoc spells out that "a member whose
 *     provider instance is currently disabled is silently skipped". Resolving
 *     to a disabled instance here would create a provider and send requests to
 *     it, bypassing the enable switch entirely.
 *  2. Missing credentials (or a missing instance) must be decided BEFORE any
 *     state write. Writing currentModel / _modelName / _activeEntryId first
 *     leaves the UI claiming "model ready" while currentProvider stays null or
 *     points at an unusable provider, and it also hands a polluted surface to
 *     the next fallback tier.
 *
 * Pure (no repository, no Android types beyond the two data classes) so the
 * decision is unit-testable without a running ChatViewModel.
 */
internal fun decideSessionModelResolution(
    entry: ModelEntry,
    instance: ProviderInstance?,
    apiKey: String?,
): SessionModelResolution {
    if (instance == null) return SessionModelResolution.Unusable(UnusableModelReason.MISSING_INSTANCE)
    if (!instance.isEnabled) return SessionModelResolution.Unusable(UnusableModelReason.PROVIDER_DISABLED)
    if (apiKey == null) return SessionModelResolution.Unusable(UnusableModelReason.MISSING_API_KEY)
    return SessionModelResolution.Use(entry, instance, apiKey)
}

/**
 * [da1019-FIX-E] Trace why a session-model site declined to resolve. The skip is
 * silent by design (same as `resolveProviderFromGroup` skipping disabled
 * members), which makes it invisible on a device — one INFO line per rejection
 * is the only hook for 「禁用后重开会话到底走了哪条 fallback」.
 * Ids are truncated; nothing credential-shaped is ever logged.
 */
internal fun ChatViewModel.logSessionModelUnusable(
    site: String,
    entryId: String,
    reason: UnusableModelReason,
) {
    AppLogger.info(
        ChatViewModel.TAG,
        "[SessionModel] $site skipped entry=${entryId.take(8)} reason=$reason",
    )
}

/** Restore provider state from a JSON binding string. Returns true if successfully resolved. */
internal fun ChatViewModel.restoreFromBinding(bindingJson: String?): Boolean {
    bindingJson ?: return false
    return try {
        val obj = org.json.JSONObject(bindingJson)
        when (obj.optString("type")) {
            "group" -> {
                val groupId = obj.optString("groupId").takeIf { it.isNotEmpty() } ?: return false
                val lastEntryId = obj.optString("lastEntryId").takeIf { it.isNotEmpty() }
                val resolved = resolveProviderFromGroup(groupId, lastEntryId)
                if (resolved) _selectedGroupId.value = groupId
                resolved
            }
            "entry" -> {
                val entryId = obj.optString("entryId").takeIf { it.isNotEmpty() } ?: return false
                val entry = providerRepository.config.value.modelEntries.find { it.id == entryId } ?: return false
                // [da1019-FIX-E] An explicit "entry" binding is a user pin, but
                // it is a pin on the *entry*, not on the provider being enabled.
                // Without the isEnabled gate, disabling a provider left the
                // binding authoritative: reopening a session pinned to it still
                // created a provider and dispatched requests to it, bypassing
                // the switch. Returning false hands the session to the caller's
                // fallback chain (default group → new-chat default), which is
                // what resolveProviderFromGroup already does for group members.
                val instance = providerRepository.instance(entry.providerInstanceId)
                val apiKey = instance?.let { providerRepository.loadApiKey(it.id) }
                val decision = decideSessionModelResolution(entry, instance, apiKey)
                if (decision !is SessionModelResolution.Use) {
                    if (decision is SessionModelResolution.Unusable) {
                        logSessionModelUnusable("restoreFromBinding-entry", entry.id, decision.reason)
                    }
                    return false
                }
                // [deep-audit-1044 B-03] Enriched model, same single source as
                // every other provider-construction site (see
                // `ChatViewModel.providerModelFor`).
                val effectiveModel = providerModelFor(entry)
                currentModel = effectiveModel
                _modelName.value = effectiveModel.displayName
                _providerName.value = decision.instance.label.ifEmpty { effectiveModel.provider }
                _selectedGroupId.value = null
                _selectedGroupName.value = ""
                _activeEntryId.value = entry.id
                currentProvider = ProviderFactory.create(decision.instance, decision.apiKey, effectiveModel, context)
                true
            }
            else -> false
        }
    } catch (_: Exception) {
        false
    }
}

/**
 * [deep-audit-1047 B-04] The members of a group that can actually serve a
 * turn — [enabledMemberEntries] narrowed by the same single decision point
 * every other resolution site uses ([decideSessionModelResolution]).
 *
 * `enabledMemberEntries` only answers "the provider instance is enabled". It
 * does not answer "there is a credential", so a group whose FIRST member sat
 * behind a key-less (or disabled) instance used to resolve to that member and
 * then bail out at `loadApiKey(...) ?: return false` — the whole group looked
 * unusable while perfectly good members sat behind it. Both group consumers
 * ([resolveProviderFromGroup] and `ChatModelRouting.rotateForNewTurn`) now
 * hand the router this list instead.
 *
 * The credential read is the same synchronous `encryptedPrefs` lookup
 * `buildFallbackProviders` and `ConfigBuiltins.firstUsableGroupMember` already
 * perform inside their candidate loops — this just moves it ahead of the
 * choice instead of after it.
 */
internal fun ChatViewModel.usableGroupMembers(members: List<ModelEntry>): List<ModelEntry> =
    members.filter { member ->
        val instance = providerRepository.instance(member.providerInstanceId)
        decideSessionModelResolution(
            member,
            instance,
            instance?.let { providerRepository.loadApiKey(it.id) },
        ) is SessionModelResolution.Use
    }

internal fun ChatViewModel.resolveProviderFromGroup(
    groupId: String,
    preferredEntryId: String? = null,
    ): Boolean {
    val group = providerRepository.group(groupId) ?: return false
    // [T-disabled-provider-via-group-android] Resolve through
    // enabledMemberEntries so a member whose provider instance is
    // currently disabled is silently skipped. Without this, a disabled
    // provider sitting at the head of memberEntryIds got loaded and
    // ChatViewModel would attempt to call it — the whole point of
    // disabling the provider was to stop that.
    //
    // preferredEntryId comes from a prior session binding ("user picked
    // this entry inside the group last time"). Honor it only if the
    // entry is still enabled; otherwise fall back to the first enabled
    // member so the session can still proceed on a now-degraded group.
    //
    // [deep-audit-1047 B-04] …and [usableGroupMembers] additionally drops
    // members with no credential, so a key-less member at the head of the
    // group can no longer sink the whole resolution (the `?: return false`
    // two lines below now only fires when EVERY member is unusable).
    val enabledMembers = usableGroupMembers(providerRepository.enabledMemberEntries(group))
    if (enabledMembers.isEmpty()) return false
    // Selection decision delegated to GroupRouter (pure JVM, testable) —
    // identical semantics: preferred binding first, then loadBalance
    // rotation anchored on lastUsedEntryId, then first member.
    val targetId = groupRouter.select(
        group = group,
        members = enabledMembers,
        preferredEntryId = preferredEntryId,
        stickyEntryId = providerRepository.lastUsedEntryId,
    ) ?: return false
    // loadBalance rotation advances the sticky anchor. Only when no
    // preferredEntryId was honored — mirrors the previous inline rotation,
    // which wrote lastUsedEntryId = rotated.id exclusively in the
    // `else if (loadBalance)` branch (explicit picks leave the anchor).
    if (preferredEntryId == null && group.strategy == com.rikkaminis.app.data.model.RoutingStrategy.loadBalance) {
        providerRepository.lastUsedEntryId = targetId
    }
    val targetEntry = enabledMembers.first { it.id == targetId }
    val instance = providerRepository.instance(targetEntry.providerInstanceId) ?: return false
    val apiKey = providerRepository.loadApiKey(instance.id) ?: return false

    // [deep-audit-1044 B-03] Group-resolved turns take the same enriched path as
    // an explicit pick — the member entry's base model alone has no capability
    // metadata (models.dev is not reachable from `:modelservice`).
    val effectiveModel = providerModelFor(targetEntry)
    currentModel = effectiveModel
    _modelName.value = effectiveModel.displayName
    _providerName.value = instance.label.ifEmpty { effectiveModel.provider }
    _selectedGroupName.value = group.name
    _activeEntryId.value = targetEntry.id
    currentProvider = ProviderFactory.create(instance, apiKey, effectiveModel, context)
    return true
}

/**
 * T312: mirrors iOS `AIChatViewModel.applyGroupSessionDefaults`.
 * When a session newly binds to a group (user picks the group, or a
 * draft session resolves the default group), copy the group's
 * `defaultThinkingLevel` into the session's persisted thinking_override.
 * Context limit is in-memory only on iOS; Android has no equivalent
 * runtime field yet, so we only handle thinking level here.
 *
 * Skips when the group has no default override (null) — leaves the
 * session's existing override untouched so manual user choices on a
 * pre-bound chat aren't clobbered by a later group re-select that
 * happens to land on the same default state.
 */
internal fun ChatViewModel.applyGroupSessionDefaults(groupId: String) {
    val group = providerRepository.group(groupId) ?: return
    val level = group.defaultThinkingLevel ?: return
    // [T-thinking-effective-level] B3: a group default must NOT clobber a
    // level the user picked by hand. Before this guard, re-selecting a group
    // (even the SAME one) unconditionally overwrote a manual "Max" with the
    // group's "Medium" AND persisted it, so the user's choice was gone for
    // good. The flag is seeded on session load from the persisted override —
    // anything already in the DB counts as an explicit choice.
    //
    // ponytail: flag, not a DB column | 天花板: a group default that was itself
    // persisted reads back as "user-set" on cold start, so later edits to the
    // group's default no longer reach that session | 升级触发: a user reports
    // "I changed the group default but this chat didn't pick it up" — then add
    // a thinking_override_source column (user|group) via a Room migration.
    if (thinkingLevelUserSet) return
    if (_thinkingLevel.value == level) return
    _thinkingLevel.value = level
    viewModelScope.launch {
        // [T-empty-session-residue] Don't materialise a row just to copy a
        // group default onto a draft chat. The value now lives in
        // _thinkingLevel and ensureSession() folds it in at insert time.
        // Binding a draft to a group and leaving without sending must not
        // strand a message-less session. Write through only if it exists.
        val sid = realSessionId
        if (sid.isNotEmpty()) {
            chatRepository.dao.updateThinkingOverride(sid, level.name)
        }
    }
}

/**
 * [T-newchat-default-model-fallback-android] Resolve and apply the default
 * model for a NEW chat when no default group produced a model. Fallback
 * chain tiers 2→3 (tier 1, the default group, is handled by the caller
 * before this runs):
 *
 *   2) last-used model — the entry the user last actively selected / used,
 *      if it still exists, is visible, and its provider is enabled.
 *   3) newest provider's newest text-output model — the final catch-all so
 *      a first-ever chat with providers but no group/last-used still gets a
 *      sensible, text-capable default (image/audio-only models excluded).
 *
 * Sets currentModel / currentProvider / the name + activeEntry state flows.
 * Returns true when a model was applied. Mirrors iOS #636. The legacy
 * behaviour here was `allVisibleEntries().firstOrNull()` (the FIRST entry),
 * which ignored both last-used and add-order — replaced by this chain.
 */
internal fun ChatViewModel.applyNewChatDefaultModel(): Boolean {
    val entry = providerRepository.lastUsedVisibleEntry()
        ?: providerRepository.newestProviderNewestTextEntry()
        ?: return false
    val instance = providerRepository.instance(entry.providerInstanceId)
    // [da1019-FIX-E] The two entry sources above already filter to enabled
    // providers, but the credential is a separate check that used to run AFTER
    // the state writes — so a missing API key still flipped _activeEntryId /
    // _modelName and then reported success (`return true`) while
    // currentProvider stayed null. Decide first, write nothing unless the
    // triple is fully usable, and tell the caller the truth.
    val apiKey = instance?.let { providerRepository.loadApiKey(it.id) }
    val decision = decideSessionModelResolution(entry, instance, apiKey)
    if (decision !is SessionModelResolution.Use) {
        if (decision is SessionModelResolution.Unusable) {
            logSessionModelUnusable("applyNewChatDefaultModel", entry.id, decision.reason)
        }
        return false
    }
    // [deep-audit-1044 B-03/B-04] Same enriched model as every other
    // construction site, and — [B-04] — publish it to `currentModel` too. The
    // KDoc above promised "Sets currentModel / currentProvider / the name +
    // activeEntry state flows" but the body never wrote `currentModel`, so a
    // new chat with no default group left it null: `ensureSession()` then
    // persisted `model_id = "unknown"`, `thinkingInfo()` returned null (the
    // Token Usage "Thinking" block vanished) and `dynamicMaxTokens()` skipped
    // the remaining-context clamp entirely.
    val effectiveModel = providerModelFor(entry)
    currentModel = effectiveModel
    _modelName.value = effectiveModel.displayName
    _activeEntryId.value = entry.id
    _providerName.value = decision.instance.label.ifEmpty { effectiveModel.provider }
    // [meta-audit-1028 F-05] A direct-entry publication LEAVES group mode, so
    // the group state has to go with it. This function is the one place that
    // publishes a serving member without establishing it as a member of a
    // group: every caller reaches it precisely when no group produced a model
    // (draft load with no default group, `applyDefaultModelFallback`'s
    // fall-through, the post-config-load retry). Without these three writes a
    // resident view model kept the PREVIOUS group bound —
    // `selectedGroupId` feeds the top bar, the picker highlight,
    // `buildFallbackProviders` and `rotateForNewTurn`, so the next user turn
    // was rotated onto a member of a group this session is not running under
    // and the top bar named a model that was never asked.
    //
    // Written as the literal inverse operation (`null` / `""`) rather than from
    // a plan value on purpose: clearing a selection is the inverse of a gated
    // publication, and spelling it as a literal is what makes that ungated
    // (scan: session_provider_enabled_check C-SEL-1 — a non-literal group write
    // must be provable as "the resolution said yes", which a clear never is).
    _selectedGroupId.value = null
    _selectedGroupName.value = ""
    // ...and the pending in-group member pick goes with the group: it names a
    // member of the group that just stopped being bound.
    pendingEntryOverride = null
    currentProvider = ProviderFactory.create(decision.instance, decision.apiKey, effectiveModel, context)
    return true
}
