package com.openminis.app.ui.chat

// [FE-5 batch 5] Session lifecycle & compaction cluster extracted verbatim
// from ChatViewModel as extension functions: session load/restore, context
// compaction, the compaction-marker graying heal pass, and provider/group
// state resolution. Same pattern as ChatViewModelUiStateExt — the functions
// operate on the VM's own members via extension receivers, no logic change.
// The thin delegating shells (walkBackUserTurnsBounded / buildChatMessages /
// buildLlmMessages / findModelEntry) stay in ChatViewModel.

import android.util.Log
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.routing.RoutingStrategy
import com.openminis.app.logging.AppLogger
import com.openminis.app.perf.PerfLongCtx
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderExecutionGateway
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.sandbox.ExecutionCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import androidx.lifecycle.viewModelScope
import com.openminis.app.R
import com.openminis.app.agent.runtime.AgentRunEvent
import com.openminis.app.agent.runtime.AgentRunPhase
import com.openminis.app.tools.AgentTraceRecorder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import com.openminis.app.tools.AgentTraceRecorder
import com.openminis.app.agent.runtime.AgentRunPhase
import com.openminis.app.agent.runtime.AgentRunEvent
import com.openminis.app.R
import androidx.lifecycle.viewModelScope


internal fun ChatViewModel.compactAll(anchorIdxOverride: Int? = null) {
    AppLogger.info(ChatViewModel.TAG, "[Compact] compactAll() invoked streaming=${_isStreaming.value} compacting=${_isCompacting.value} historySize=${agentHistory.size} anchorOverride=$anchorIdxOverride")
    if (_isStreaming.value) {
        AppLogger.info(ChatViewModel.TAG, "[Compact] aborted: stream in progress")
        appendSystemInfo(
            text = context.getString(R.string.sysmsg_compact_busy_turn),
            iconKind = "compact",
        )
        return
    }
    if (_isCompacting.value) {
        AppLogger.info(ChatViewModel.TAG, "[Compact] aborted: another compact already in flight")
        appendSystemInfo(
            text = context.getString(R.string.sysmsg_compact_busy),
            iconKind = "compact",
        )
        return
    }
    val provider = currentProvider ?: run {
        appendSystemInfo(context.getString(R.string.sysmsg_compact_no_provider), "compact")
        return
    }
    val history = agentHistory.toList()
    if (history.isEmpty()) {
        appendSystemInfo(context.getString(R.string.sysmsg_compact_empty_session), "compact")
        return
    }
    // ─── v2 unified anchor model ───────────────────────────────────
    //
    // anchor = last active agentHistory entry. The compacted range is
    // `[prev marker anchor + 1, anchor]` (or `[0, anchor]` if no prev),
    // so each compact "extends" the latest summary forward to cover all
    // new turns. effectiveAgentHistory then re-injects the LAST N
    // user-text turns LEADING UP TO the anchor as fresh context, so the
    // model still sees recent verbatim content alongside the summary.
    //
    // Mirrors iOS post-Phase-v2: anchor = last active message, no
    // "auto-keep tail" baked into the compacted range — that's a
    // read-side decoration done by effectiveAgentHistory.
    //
    // anchor must be a persisted entry (have a non-null dbMessageId).
    // The strict iOS check also requires id ∈ rawMessages DB, but DAO
    // is suspend and we'd have to relocate range calculation into the
    // launch below. As a compromise we do the dbMessageId-non-empty
    // pre-check here (catches most stale-id cases at this stage), and
    // do the rawDbIds-membership check inside the launch before the
    // marker is written. Mirrors iOS AIChatViewModel+Compaction.swift:
    // 644-657 "walk back through agentHistory looking for dbMessageId
    // AND allRaw.contains" — split across two phases to honor suspend
    // boundaries.
    val anchorIdx: Int = resolveCompactAnchorIdx(history, anchorIdxOverride)
    if (anchorIdx < 0) {
        appendSystemInfo(context.getString(R.string.sysmsg_compact_no_persisted), "compact")
        return
    }

    // Slice to compact = (prev marker's anchor + 1) … anchorIdx inclusive
    // (v2/v1 boundary resolution delegated to resolveCompactStartIdx).
    val effectiveStartIdx: Int = resolveCompactStartIdx(history, _cachedLatestMarker)
    if (effectiveStartIdx > anchorIdx) {
        appendSystemInfo(context.getString(R.string.sysmsg_compact_already_done), "compact")
        return
    }
    val toCompact = history.subList(effectiveStartIdx, anchorIdx + 1)
    if (toCompact.isEmpty()) {
        appendSystemInfo(context.getString(R.string.sysmsg_compact_nothing), "compact")
        return
    }
    _isCompacting.value = true
    // T7-A: 观察 —— compact 开始（advisory）
    // T7-C: compaction 预算耗尽 → 跳过 compact，不改变历史
    if (!traceObserver.t7ConsumeAndTrace(AgentTraceRecorder.DIMENSION_COMPACTION_CALLS) { it.consumeCompaction() }) {
        _isCompacting.value = false
        appendSystemInfo(context.getString(R.string.sysmsg_compact_budget_exhausted), "compact")
        return
    }
    traceObserver.t7State(
        traceObserver.t7ObservedPhase ?: ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.EXECUTING_TOOLS),
        ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.COMPACTING),
        "CompactionStarted",
    )
    // T7-D: 旁路验证 —— compact 开始
    traceObserver.t7Reduce(AgentRunEvent.CompactionStarted("compact_all"))
    viewModelScope.launch(Dispatchers.IO) {
        // [T-android-compact-queued-drain] Only a SUCCESSFUL compact kicks
        // the queued-prompt drain below; failure/cancel/empty-summary paths
        // keep today's behavior (queued bubbles stay pending + cancellable).
        var compactSucceeded = false
        try {
            val existing = _compactSummary.value
            // Mirrors iOS `generateCompactSummaryWithSplitting` — when the
            // joined transcript exceeds the model's context window, halve
            // the message list and summarize each half independently, then
            // merge. depth cap=3 prevents pathological recursion.
            val summary = generateCompactSummaryWithSplitting(
                messages = toCompact,
                previousSummary = existing,
                depth = 0,
            ).trim()
            if (summary.isEmpty()) {
                withContext(Dispatchers.Main) {
                    appendSystemInfo(context.getString(R.string.sysmsg_compact_empty_summary), "compact")
                }
                return@launch
            }

            val sid = realSessionId.ifEmpty { sessionId }
            // v2 marker: lastCompactedMessageId IS the anchor — single
            // source of truth. The anchor we resolved above is guaranteed
            // to have a persisted dbMessageId. Legacy fields (firstKept /
            // boundary / sortOrder) stay null/MAX so a downgraded reader
            // sees "everything compacted, nothing kept" as a graceful
            // fallback rather than a stale boundary.
            // Re-resolve anchor: now that we're inside an IO coroutine
            // we can read the messages DB to verify the dbMessageId is
            // actually persisted, not just set on the in-memory
            // LLMMessage. iOS does this belt-and-suspenders check
            // (AIChatViewModel+Compaction.swift:644-657). Walk back from
            // the original anchorIdx until we find an entry whose id is
            // both non-empty AND present in rawDbIds.
            val rawDbIds: Set<String> = try {
                chatRepository.dao.loadMessages(sid).map { it.id }.toSet()
            } catch (e: Exception) {
                Log.w(ChatViewModel.TAG, "[Compact] loadMessages for raw-id verify failed: ${e.message}")
                emptySet()
            }
            val verifiedAnchorIdx: Int = if (rawDbIds.isEmpty()) {
                // DB read failed; trust the in-memory walk-back result.
                anchorIdx
            } else {
                var i = anchorIdx
                while (i >= 0) {
                    val id = history[i].dbMessageId
                    if (!id.isNullOrEmpty() && id in rawDbIds) break
                    i -= 1
                }
                i
            }
            if (verifiedAnchorIdx < 0) {
                Log.w(ChatViewModel.TAG, "[Compact] No agentHistory entry has a DB-persisted dbMessageId; aborting")
                withContext(Dispatchers.Main) {
                    appendSystemInfo(context.getString(R.string.sysmsg_compact_anchor_failed), "compact")
                }
                return@launch
            }
            if (verifiedAnchorIdx != anchorIdx) {
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "[Compact] anchor walked back from idx=$anchorIdx to idx=$verifiedAnchorIdx " +
                        "(closest with id in rawDbIds). Unsynced tail entries will fall on the active side of the divider.",
                )
            }
            val lastCompactedDbId = history[verifiedAnchorIdx].dbMessageId
                ?: run {
                    Log.w(ChatViewModel.TAG, "[Compact] verified anchor at idx=$verifiedAnchorIdx lost dbMessageId; aborting")
                    withContext(Dispatchers.Main) {
                        appendSystemInfo(context.getString(R.string.sysmsg_compact_anchor_id_missing), "compact")
                    }
                    return@launch
                }
            val marker = CompactMarkerEntity(
                id = java.util.UUID.randomUUID().toString(),
                sessionId = sid,
                summary = summary,
                firstKeptSortOrder = Int.MAX_VALUE,   // legacy field; v2 ignores
                compactedCount = toCompact.size,
                createdAt = System.currentTimeMillis(),
                uiBoundarySortOrder = null,
                boundaryMessageId = null,
                firstKeptMessageId = null,
                lastCompactedMessageId = lastCompactedDbId,
                version = 2,
            )
            runCatching { chatRepository.dao.insertCompactMarker(marker) }
                .onFailure {
                    Log.w(ChatViewModel.TAG, "Failed to persist compact marker: ${it.message}")
                }
            _compactSummary.value = summary
            // Keep the marker in memory so effectiveAgentHistory() can
            // resolve the boundary on the very next outgoing turn.
            // Mirrors iOS `cachedLatestMarker = marker`.
            _cachedLatestMarker = marker
            withContext(Dispatchers.Main) {
                // Gray out everything in the compacted range; the kept
                // tail (last N user turns + tool/assistant follow-ups)
                // stays full opacity. Determined by walking _messages
                // until we pass the row whose id == lastCompactedDbId.
                //
                // Also drop any prior compact-divider system rows — a
                // session shows at most one divider (the latest marker).
                // Those old dividers are stored as system messages with
                // a "compact" iconKind in toolBlocks[0].toolName.
                val cutoffId: String = lastCompactedDbId
                var passedCutoff = false   // anchor is guaranteed non-null in v2
                val cleaned = _messages.value
                    .filterNot { msg ->
                        // Drop prior compact-divider rows; appendSystemInfo
                        // below will re-add the new one.
                        msg.role == "system" &&
                            msg.toolBlocks.firstOrNull()?.toolName == "compact"
                    }
                    .map { msg ->
                        if (msg.role == "system") msg
                        else if (passedCutoff) msg
                        else {
                            val grayed = if (msg.isCompactedHistory) msg
                                else msg.copy(isCompactedHistory = true)
                            if (msg.id == cutoffId) passedCutoff = true
                            grayed
                        }
                    }
                // T84: count UI bubbles in this pass's compacted range.
                // Filters: role != system (dividers/notices don't count).
                // Range: everything up to and including the cutoff row,
                // since the kept-tail starts immediately after.
                // Falls back to "all non-system" when cutoffId is null
                // (compact-everything path), matching iOS dividerInsertIdx
                // == messages.count behavior.
                //
                // We deliberately do NOT exclude `isCompactedHistory` rows.
                // Back-to-back compacts (or compact after restoring a prior
                // marker on session reload) leave the in-range rows already
                // grayed; excluding them produced "0 messages compacted"
                // even though `toCompact.size` was nonzero. The divider's
                // count should reflect the size of THIS pass's range, not
                // the delta of newly-grayed rows.
                val cutoffIdx = cleaned.indexOfLast { it.id == cutoffId }
                val compactedUICount = if (cutoffIdx < 0) {
                    cleaned.count { it.role != "system" }
                } else {
                    cleaned.take(cutoffIdx + 1).count { it.role != "system" }
                }
                _messages.value = cleaned
                AppLogger.info(ChatViewModel.TAG, "[Compact] divider: $compactedUICount UI bubbles compacted (history entries: ${toCompact.size})")
                appendSystemInfo(
                    text = context.getString(R.string.sysmsg_compacted_count, compactedUICount),
                    iconKind = "compact",
                    payload = summary,
                )
            }
            compactSucceeded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(ChatViewModel.TAG, "Compact failed", e)
            withContext(Dispatchers.Main) {
                appendSystemInfo(
                    text = context.getString(R.string.sysmsg_compact_failed, e.message ?: e.javaClass.simpleName),
                    iconKind = "compact",
                )
            }
        } finally {
            _isCompacting.value = false
            // T7-A: 观察 —— compact 结束（无论成败都回到调用模型阶段）
            traceObserver.t7State(
                ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.COMPACTING),
                ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.CALLING_MODEL),
                "CompactionFinished",
            )
            // T7-D: 旁路验证 —— compact 结束
            traceObserver.t7Reduce(AgentRunEvent.CompactionFinished())
        }
        // [T-android-compact-queued-drain] A successful compact must let
        // any queued prompts proceed — previously nothing re-triggered the
        // drain after compact (loop-end / cancel / tool-boundary are the
        // only drain triggers), so a prompt sitting in the queue when a
        // compact ran stayed in the dashed "queued" state forever. Reuse
        // resumeQueueAfterCancel: it re-checks queue-non-empty + not-
        // streaming + not-compacting after its grace delay (so an ✕ tap at
        // the compact-finish instant is a clean no-op), refreshes OAuth,
        // and drains through the normal stream-slot machinery — no new
        // reentrancy path. Runs after `finally` so isCompacting is already
        // false. Mirrors the iOS fix for the same report.
        if (compactSucceeded && _promptQueue.value.isNotEmpty()) {
            AppLogger.info(ChatViewModel.TAG, "[Compact] success with ${_promptQueue.value.size} queued prompt(s) — kicking drain")
            resumeQueueAfterCancel()
        }
    }
}

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
        val firstTextUserOffset = postAnchor.indexOfFirst {
            it.role == LLMMessage.Role.USER &&
                !it.contentParts.all { p -> p is AgentContentPart.ToolResult }
        }
        if (firstTextUserOffset >= 0) {
            if (firstTextUserOffset > 0) {
                result.addAll(postAnchor.subList(0, firstTextUserOffset))
            }
            val target = postAnchor[firstTextUserOffset]
            // Prepend `<context-summary>...` to the user content. We
            // edit `content` directly because Android LLMMessage uses
            // `content: String` as the canonical text payload; any
            // contentParts the message also carries get preserved.
            val injected = target.copy(
                content = summaryWrappedText + "\n\n" + target.content,
            )
            result.add(injected)
            if (firstTextUserOffset + 1 < postAnchor.size) {
                result.addAll(postAnchor.subList(firstTextUserOffset + 1, postAnchor.size))
            }
        } else {
            // Rare: no user message after anchor (or all are tool_result-only).
            // Append everything post-anchor (typically empty) then a standalone
            // summary user turn. Safe — no later user follows it to break
            // alternation.
            result.addAll(postAnchor)
            result.add(LLMMessage(role = LLMMessage.Role.USER, content = summaryWrappedText))
        }
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
        sanitizeAgentHistoryMessages(result)
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
            sanitizeAgentHistoryMessages(result1)
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
        sanitizeAgentHistoryMessages(result2)
        return result2
    }

    Log.w(ChatViewModel.TAG, "[Compact] effectiveAgentHistory: marker ${marker.id.take(8)} unresolvable in agentHistory (size=${agentHistory.size}); returning full history")
    return agentHistory.toList()
}

/**
 * Summarize [messages], recursively halving and merging when the input
 * exceeds the model's context window. Mirrors iOS
 * `generateCompactSummaryWithSplitting` (AIChatViewModel+Compaction.swift:820).
 *
 * Depth cap = 3 (matches iOS) so a pathologically large conversation
 * still terminates instead of fanning out indefinitely. At each split we
 * halve by message count, summarize each half independently, then ask the
 * LLM to merge the two partial summaries into one — prioritizing Part 2
 * (more recent) when space is tight, again matching iOS behavior.
 */
internal suspend fun ChatViewModel.generateCompactSummaryWithSplitting(
    messages: List<LLMMessage>,
    previousSummary: String? = null,
    depth: Int = 0,
    ): String {
    val transcript = buildConversationTextForSummary(messages)
    val conversationText = if (previousSummary.isNullOrBlank()) {
        transcript
    } else {
        "Previous context summary:\n$previousSummary\n\n" +
            "New conversation to merge:\n$transcript"
    }
    return try {
        generateCompactSummary(conversationText)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!isContextTooLargeError(e) || messages.size < 2 || depth >= 3) {
            throw e
        }
        val mid = messages.size / 2
        val firstHalf = messages.subList(0, mid).toList()
        val secondHalf = messages.subList(mid, messages.size).toList()
        AppLogger.info(
            ChatViewModel.TAG,
            "[Compact] Splitting ${messages.size} messages into ${firstHalf.size} + ${secondHalf.size} (depth=$depth)",
        )
        val summary1 = generateCompactSummaryWithSplitting(firstHalf, null, depth + 1)
        val summary2 = generateCompactSummaryWithSplitting(secondHalf, null, depth + 1)
        val mergeInput = buildString {
            append("Merge these partial summaries into a single cohesive context summary. ")
            append("Frame everything as past events (what was asked, what was done) rather than as ")
            append("ongoing goals or todos — the user's next message will set the current task.\n\n")
            append("MUST PRESERVE:\n")
            append("- What was done and what was tried, with outcomes (record as past events)\n")
            append("- The last thing the user requested in this conversation, and how it was handled\n")
            append("- All file paths, identifiers, URLs — copy verbatim\n")
            append("- Decisions made and their rationale\n")
            append("- Constraints, rules, and user preferences mentioned\n\n")
            append("Do NOT carry forward \"pending\" or \"todo\" lists that imply standing work — if the user ")
            append("still wants those, they will say so in their next message.\n\n")
            append("PRIORITIZE Part 2 (more recent) over Part 1 (older) when space is tight.\n\n")
            append("Part 1:\n").append(summary1).append("\n\n")
            append("Part 2:\n").append(summary2)
        }
        generateCompactSummary(mergeInput)
    }
}

/**
 * Single-shot LLM call that turns [conversationText] into a structured
 * summary. Throws on provider error so the splitter above can detect
 * context-too-large failures and retry with halved input.
 */
internal suspend fun ChatViewModel.generateCompactSummary(conversationText: String): String {
    // Wrap the transcript in explicit BEGIN/END framing so the model
    // treats it as material to summarize rather than as a chat turn to
    // continue. Mirrors iOS AIChatViewModel+Compaction.swift
    // `compactUserMessage` construction. Without this wrapper, fast models
    // (e.g. deepseek-v4-flash) tend to "answer" whatever the last user
    // turn in the transcript said — producing a single-line continuation
    // instead of a structured summary.
    val userMessage = buildString {
        append("Compact this conversation into a context summary:\n\n")
        append(conversationText)
        append("\n\n---\nEND OF CONVERSATION TO COMPACT.\n\n")
        append(
            "Now generate a structured context summary following the system prompt " +
                "instructions. Do NOT continue the conversation above — summarize it. " +
                "Write everything in past tense, framed as \"what was discussed / what " +
                "was done\", NOT as an ongoing goal or todo list."
        )
    }
    val model = currentModel
    val contextWindow = model?.contextWindow ?: 128_000
    val estimatedInput = userMessage.length / 4
    val maxOut = maxOf(1024, minOf(8192, contextWindow - estimatedInput))
    val provider = currentProvider
        ?: throw IllegalStateException("No LLM provider available for compaction")
    val instance = provider.instanceContext
        ?: throw IllegalStateException("No provider instance context for compaction")
    // TF-D: compaction runs through :modelservice via the gateway — the main
    // process never calls provider.sendMessage. A remote failure (typed)
    // throws so the splitter can halve the input and retry.
    return when (val r = ProviderExecutionGateway.send(
        context = context,
        instance = instance,
        model = provider.model,
        messages = listOf(
            LLMMessage(role = LLMMessage.Role.USER, content = userMessage)
        ),
        systemPrompt = compactSummarySystemPrompt,
        maxTokens = maxOut,
        // Mirror iOS AIChatViewModel.swift:12926 — null lets the
        // provider/model use its default. gpt-5.x family rejects any
        // temperature != 1 with HTTP 400, and Android
        // OpenAIProvider.buildRequestBody omits the field entirely when
        // temperature is null.
        temperature = null,
        imageParts = emptyList(),
        tools = emptyList(),
        thinkingLevel = ThinkingLevel.OFF,
    )) {
        is ProviderExecutionGateway.SendResult.Success -> r.response.text
        is ProviderExecutionGateway.SendResult.RemoteFailure ->
            throw IllegalStateException("compaction failed (${r.code}): ${r.message}")
        is ProviderExecutionGateway.SendResult.Unavailable ->
            throw IllegalStateException("compaction unavailable: ${r.reason}")
    }
}

internal fun ChatViewModel.loadSession() {
    // T-android-crash-detected-halt: when CrashFrequencyDetector
    // tripped (#459, ≥3 crashes in last hour), skip the heavy
    // session-restore path entirely. Re-running the same persisted
    // state is exactly what produced the burst, so we'd just feed
    // a re-crash loop while the user is staring at the share dialog.
    // The flag clears the moment the dialog closes (share / dismiss /
    // cancel) — see CrashFrequencyDetector.maybeShowOnActivity.
    if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
        android.util.Log.w(ChatViewModel.TAG, "loadSession: safe-mode active, skipping session restore")
        // [T-android-perf-logging] Surface the skip on the Perf timeline
        // too — when a crash_or_stall recovery loop is suspected, this
        // distinguishes "loadSession ran and was slow" from "loadSession
        // was skipped (safe-mode), so the stall is elsewhere".
        com.openminis.app.diagnostics.PerfLongCtx.step(
            sessionId,
            "loadSession.skipped",
            "reason=safeMode",
        )
        return
    }
    viewModelScope.launch {
        // [T-HANG-DIAG] timing markers to localise where session entry
        // stalls. Sentinel-tagged so a single grep -v can strip them
        // when this diagnostic is removed. Declared OUTSIDE the try
        // block so the EXIT log in `finally` can still read it after
        // an early-return / exception path.
        val tHangDiagStart = System.currentTimeMillis()
        println("[T-HANG-DIAG] loadSession ENTER session=$sessionId isDraft=$isDraft")
        com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "loadSession.enter", "isDraft=$isDraft")
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
        // T239: hydrate persisted thinking-mode override. null = unset
        // (use OFF as the legacy default); non-null = explicit user
        // choice persisted across cold-start. runCatching guards against
        // a stale enum name from a future rename — fall back silently
        // rather than crashing the session load.
        _thinkingLevel.value = session.thinkingOverride
            ?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() }
            ?: ThinkingLevel.OFF

        // Priority 1: restore from persisted model_binding (group or entry)
        var resolved = restoreFromBinding(session.modelBinding)

        // Priority 2: fall back to stored model_id
        if (!resolved) {
            val entry = findModelEntry(session.modelId)
            if (entry != null) {
                currentModel = entry.model
                _modelName.value = entry.model.displayName
                _activeEntryId.value = entry.id
                val instance = providerRepository.instance(entry.providerInstanceId)
                if (instance != null) {
                    val apiKey = providerRepository.loadApiKey(instance.id)
                    if (apiKey != null) {
                        currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context)
                        _providerName.value = instance.label.ifEmpty { entry.model.provider }
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
                    }
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
            val messages: List<com.openminis.app.data.db.MessageEntity>,
            val ordered: List<ChatMessage>,
            val llmHistory: List<LLMMessage>,
            val loadMs: Long,
            val transformMs: Long,
        )
        com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "db.query.begin")
        val loaded = withContext(Dispatchers.IO) {
            val tIoBeforeLoad = System.currentTimeMillis()
            val rows = chatRepository.loadMessages(sessionId)
            val tIoAfterLoad = System.currentTimeMillis()
            com.openminis.app.diagnostics.PerfLongCtx.step(
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
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "toChatMessages.end",
                "count=${chatUi.size}",
            )
            val llm = buildLlmMessages(parsed)
            var totalPartsChars = 0L
            for (row in parsed) {
                totalPartsChars += row.sourceChars
            }
            com.openminis.app.diagnostics.PerfLongCtx.step(
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
            "[T-HANG-DIAG] loadMessages session=$sessionId count=${messages.size} " +
                "tookMs=${loaded.loadMs}",
        )
        println(
            "[T-HANG-DIAG] toChatMessages session=$sessionId tookMs=${loaded.transformMs}",
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
                if (m.partsJson.contains("\"tool_use\"") || m.partsJson.contains("\"tool_result\"")) {
                    withTools++
                }
                if (m.partsJson.contains("\"image\"") || m.partsJson.contains("\"attachment\"")) {
                    withAttachments++
                }
            }
            println(
                "[T-HANG-DIAG] messages-shape session=$sessionId total=${messages.size} " +
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
            val ChatViewModel.OVERSIZE_THRESHOLD = 50_000
            val oversized = messages.filter { it.partsJson.length >= ChatViewModel.OVERSIZE_THRESHOLD }
            if (oversized.isNotEmpty()) {
                println(
                    "[T-HANG-DIAG] oversized-messages session=$sessionId " +
                        "count=${oversized.size} threshold=${ChatViewModel.OVERSIZE_THRESHOLD}",
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
        // above to avoid re-parsing partsJson on the UI thread. Safe to
        // bulk-addAll here because loadSession runs once at init before
        // any sender writes into agentHistory.
        agentHistory.addAll(loaded.llmHistory)
        val tHangDiagAfterAgentHistory = System.currentTimeMillis()
        println(
            "[T-HANG-DIAG] agentHistory rebuilt session=$sessionId tookMs=${tHangDiagAfterAgentHistory - tHangDiagAfterTransform}",
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

        com.openminis.app.diagnostics.PerfLongCtx.step(
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

        // Cold-start interrupt detection: an agent loop that was killed by
        // the OS (or app force-quit) leaves agentHistory in one of three
        // tell-tale shapes. Detecting any of them lets the user tap
        // Resume to pick up where the model left off — the in-memory
        // [_canResume] flag set by [handleUserCancelledCleanup] is lost
        // across cold starts so we have to re-derive it from the DB.
        // Mirrors iOS AIChatViewModel.loadSession lines 3546-3581.
        //   Case A: last entry is user with all-toolResult parts —
        //           tools completed but the next model call never fired.
        //   Case B: last entry is assistant with any tool_use parts —
        //           the model requested tools that never executed.
        //   Case C: last entry is user with the synthetic "Continue"
        //           reminder text — text-cancel handler committed it
        //           but [resume] never re-entered the agent loop.
        val lastEntry = agentHistory.lastOrNull()
        if (lastEntry != null && !_isStreaming.value) {
            val isInterrupted = when (lastEntry.role) {
                LLMMessage.Role.USER -> {
                    val parts = lastEntry.contentParts
                    val allToolResults = parts.isNotEmpty() &&
                        parts.all { it is AgentContentPart.ToolResult }
                    val isContinueReminder = parts.size == 1 &&
                        (parts.first() as? AgentContentPart.Text)?.text
                            ?.contains("The user stopped the previous response") == true
                    allToolResults || isContinueReminder
                }
                LLMMessage.Role.ASSISTANT -> {
                    lastEntry.contentParts.any { it is AgentContentPart.ToolUse }
                }
                else -> false
            }
            if (isInterrupted) {
                _canResume.value = true
                Log.i(ChatViewModel.TAG, "loadSession: detected interrupted agent loop, canResume=true (lastRole=${lastEntry.role})")
            }
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
                "[T-HANG-DIAG] loadSession EXIT session=$sessionId " +
                    "totalMs=${System.currentTimeMillis() - tHangDiagStart}",
            )
            com.openminis.app.diagnostics.PerfLongCtx.step(
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
    marker: com.openminis.app.data.db.CompactMarkerEntity,
    rawMessages: List<com.openminis.app.data.db.MessageEntity>,
    historyDbIds: Set<String>,
    ): List<ChatMessage> {
    // Some legacy rows have empty-string boundaries instead of NULL —
    // treat both as "no boundary" so the compactAll path below kicks in.
    val firstKeptId = (marker.firstKeptMessageId?.takeIf { it.isNotEmpty() })
        ?: (marker.boundaryMessageId?.takeIf { it.isNotEmpty() })
    val lcmId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }

    // ─── Resolve insertIdx ────────────────────────────────────────
    //
    // insertIdx semantics: messages[0 until insertIdx] become grayed
    // (isCompactedHistory=true); the divider sits at insertIdx;
    // messages[insertIdx..] stay active.
    //
    // Special value -1 → "unresolved": skip the rewrite below and
    // return the messages untouched with no divider (the marker is
    // effectively invisible until the user reverts or self-heals).
    // Used when even createdAt fallback fails — better to show no
    // divider than to incorrectly gray live messages.
    var insertIdx = -1
    var healedMarker: com.openminis.app.data.db.CompactMarkerEntity? = null

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
            val heal = anchorByCreatedAt(rawMessages, marker.createdAt, historyDbIds)
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
            val heal = anchorByCreatedAt(rawMessages, marker.createdAt, historyDbIds)
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

    // ─── Apply graying ────────────────────────────────────────────
    val grayed: List<ChatMessage> = if (insertIdx <= 0) {
        // No graying — either explicit no-gray branch or boundary at
        // index 0 (nothing to gray).
        messages
    } else {
        messages.mapIndexed { idx, msg ->
            if (idx >= insertIdx) msg
            else if (msg.role == "system") msg
            else if (msg.isCompactedHistory) msg
            else msg.copy(isCompactedHistory = true)
        }
    }

    // ─── Insert divider row ───────────────────────────────────────
    // T126-marker: match iOS `"\(insertIdx) messages compacted"`
    // (AIChatViewModel.swift:3432). Count = number of UI bubbles
    // above the divider, not marker.compactedCount (which counts raw
    // agentHistory entries — tool_use/tool_result pairs that never
    // appear as their own UI bubble).
    val compactedUICount = (0 until insertIdx.coerceIn(0, grayed.size))
        .count { grayed[it].role != "system" }
    val dividerLabel = "$compactedUICount messages compacted"
    val markerForDivider = healedMarker ?: marker
    val dividerBlock = AssistantBlock(
        id = "compact-divider-${markerForDivider.id}",
        kind = "info",
        content = dividerLabel,
        toolName = "compact",
        toolArgs = markerForDivider.summary,
    )
    val dividerMsg = ChatMessage(
        id = "compact-divider-msg-${markerForDivider.id}",
        role = "system",
        content = "",
        toolBlocks = listOf(dividerBlock),
    )
    val withDivider = grayed.toMutableList()
    withDivider.add(insertIdx.coerceIn(0, withDivider.size), dividerMsg)
    return withDivider
}

/**
 * createdAt self-heal: return the LAST raw message whose
 * `createdAt < markerCreatedAt` AND whose id is still represented in
 * agentHistory (filtered via [historyDbIds]). When [historyDbIds] is
 * empty (no dbIds collected — unusual), the filter degrades to "just
 * the createdAt predicate" so we still recover SOMETHING.
 *
 * Mirrors iOS AIChatViewModel+Compaction.swift:125.
 */
internal fun ChatViewModel.anchorByCreatedAt(
    rawMessages: List<com.openminis.app.data.db.MessageEntity>,
    markerCreatedAt: Long,
    historyDbIds: Set<String>,
    ): com.openminis.app.data.db.MessageEntity? {
    return rawMessages.lastOrNull { raw ->
        raw.createdAt < markerCreatedAt &&
            (historyDbIds.isEmpty() || raw.id in historyDbIds)
    }
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
    original: com.openminis.app.data.db.CompactMarkerEntity,
    newAnchor: com.openminis.app.data.db.MessageEntity,
    lastRaw: com.openminis.app.data.db.MessageEntity?,
    ): com.openminis.app.data.db.CompactMarkerEntity {
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
                val instance = providerRepository.instance(entry.providerInstanceId) ?: return false
                val apiKey = providerRepository.loadApiKey(instance.id) ?: return false
                currentModel = entry.model
                _modelName.value = entry.model.displayName
                _providerName.value = instance.label.ifEmpty { entry.model.provider }
                _selectedGroupId.value = null
                _selectedGroupName.value = ""
                _activeEntryId.value = entry.id
                currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context)
                true
            }
            else -> false
        }
    } catch (_: Exception) {
        false
    }
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
    val enabledMembers = providerRepository.enabledMemberEntries(group)
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
    if (preferredEntryId == null && group.strategy == com.openminis.app.data.model.RoutingStrategy.loadBalance) {
        providerRepository.lastUsedEntryId = targetId
    }
    val targetEntry = enabledMembers.first { it.id == targetId }
    val instance = providerRepository.instance(targetEntry.providerInstanceId) ?: return false
    val apiKey = providerRepository.loadApiKey(instance.id) ?: return false

    currentModel = targetEntry.model
    _modelName.value = targetEntry.model.displayName
    _providerName.value = instance.label.ifEmpty { targetEntry.model.provider }
    _selectedGroupName.value = group.name
    _activeEntryId.value = targetEntry.id
    currentProvider = ProviderFactory.create(instance, apiKey, targetEntry.model, context)
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
    val instance = providerRepository.instance(entry.providerInstanceId) ?: return false
    currentModel = entry.model
    _modelName.value = entry.model.displayName
    _activeEntryId.value = entry.id
    _providerName.value = instance.label.ifEmpty { entry.model.provider }
    val apiKey = providerRepository.loadApiKey(instance.id)
    if (apiKey != null) {
        currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context)
    }
    return true
}
