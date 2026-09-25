package com.rikkaminis.app.ui.chat

import android.util.Log
import com.rikkaminis.app.conversation.ContextCompactor
import com.rikkaminis.app.data.AgentRuntimeLimitsPrefs
import com.rikkaminis.app.data.db.CompactMarkerEntity
import com.rikkaminis.app.data.db.MessageEntity
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.data.model.RoutingStrategy
import com.rikkaminis.app.diagnostics.SessionIdAliases
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.provider.LLMProvider
import com.rikkaminis.app.sandbox.offload.ProviderExecutionGateway
import com.rikkaminis.app.provider.ProviderFactory
import com.rikkaminis.app.sandbox.ExecutionCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import androidx.lifecycle.viewModelScope
import com.rikkaminis.app.R
import com.rikkaminis.app.agent.runtime.AgentRunEvent
import com.rikkaminis.app.agent.runtime.AgentRunPhase
import com.rikkaminis.app.tools.AgentTraceRecorder
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

internal fun ChatViewModel.compactAll(
    anchorIdxOverride: Int? = null,
    allowInStream: Boolean = false,
    silent: Boolean = false,
) {
    AppLogger.info(ChatViewModel.TAG, "[Compact] compactAll() invoked streaming=${_isStreaming.value} compacting=${_isCompacting.value} historySize=${agentHistory.size} anchorOverride=$anchorIdxOverride allowInStream=$allowInStream silent=$silent")
    // [T-auto-compact-in-loop] allowInStream relaxes the in-stream guard for
    // the agent-loop turn boundary: the loop has finished the previous turn's
    // collect (no pending streaming delta) and awaits this compact before the
    // next provider call, so the guard's "don't tear the streaming UI" concern
    // does not apply. All other callers (manual /compact, sendMessage auto-
    // compact) keep the strict guard via the default false.
    if (_isStreaming.value && !allowInStream) {
        AppLogger.info(ChatViewModel.TAG, "[Compact] aborted: stream in progress")
        if (!silent) {
            appendSystemInfo(
                text = context.getString(R.string.sysmsg_compact_busy_turn),
                iconKind = "compact",
            )
        }
        return
    }
    if (_isCompacting.value) {
        AppLogger.info(ChatViewModel.TAG, "[Compact] aborted: another compact already in flight")
        if (!silent) {
            appendSystemInfo(
                text = context.getString(R.string.sysmsg_compact_busy),
                iconKind = "compact",
            )
        }
        return
    }
    val provider = currentProvider ?: run {
        if (!silent) appendSystemInfo(context.getString(R.string.sysmsg_compact_no_provider), "compact")
        return
    }
    val history = agentHistory.toList()
    if (history.isEmpty()) {
        if (!silent) appendSystemInfo(context.getString(R.string.sysmsg_compact_empty_session), "compact")
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
    var anchorIdx: Int = resolveCompactAnchorIdx(history, anchorIdxOverride)
    if (anchorIdx < 0) {
        // [fix/compact-anchor-resolution] This return posted a user-visible
        // notice but wrote nothing to the log, which is why "it keeps trying
        // to compact and nothing happens" had no diagnosable trace. Say which
        // history size / override produced the failure.
        AppLogger.info(
            ChatViewModel.TAG,
            "[Compact] aborted: no persisted anchor (anchorIdx=-1 historySize=${history.size} " +
                "anchorOverride=$anchorIdxOverride firstId=${history.firstOrNull()?.dbMessageId?.take(8)} " +
                "lastId=${history.lastOrNull()?.dbMessageId?.take(8)})",
        )
        // [fix/silent-auto-compact] Gated like the other "nothing to do"
        // aborts (already-compacted / empty range / busy). The commit that
        // introduced the silent contract lists this branch among them, but
        // the gate was never added — and unlike the others it is the branch a
        // STUCK anchor lands in on every turn, so the in-loop path posted a
        // "no persisted anchor" card mid-answer on each auto-compact attempt.
        if (!silent) appendSystemInfo(context.getString(R.string.sysmsg_compact_no_persisted), "compact")
        return
    }

    // Slice to compact = (prev marker's anchor + 1) … anchorIdx inclusive
    // (v2/v1 boundary resolution delegated to resolveCompactStartIdx).
    val effectiveStartIdx: Int = resolveCompactStartIdx(history, _cachedLatestMarker)
    if (effectiveStartIdx > anchorIdx) {
        // [compact-budget-anchor] "No new complete user turn" is the *normal*
        // state of a long agent run (one prompt, hundreds of tool rounds):
        // the turn boundary stays pinned on the first message, start =
        // prevAnchor + 1 > anchor, and every auto-compact would early-return
        // here forever while the history (plus the re-injected summary) keeps
        // growing. Fall back to a budget-based segment anchor instead of
        // giving up. Skipped when the caller pinned an explicit index (manual
        // `/compact <n>`, tests) — an override means "use exactly this".
        // [compact-budget-anchor] Derive the kept-tail budget from the SAME
        // user-tunable threshold the trigger gate uses, so the two can never
        // cross (see [compactBudgetTailKeepTokens]). Hard-coding it re-opened
        // the dead zone whenever the user raised the setting. Hoisted out of
        // the branch below because the "engaged" log line reports it.
        val keepTail = compactBudgetTailKeepTokens(
            AgentRuntimeLimitsPrefs.autoCompactMinTailTokens().toLong(),
        )
        val budgetAnchor = if (anchorIdxOverride == null) {
            resolveBudgetAnchorIdx(
                history = history,
                startIdx = effectiveStartIdx,
                keepTailTokens = keepTail,
                estimate = { ContextCompactor.estimateMessageTokens(it) },
            )
        } else {
            -1
        }
        if (budgetAnchor >= effectiveStartIdx) {
            AppLogger.info(
                ChatViewModel.TAG,
                "[Compact] budget anchor engaged (no new user turn to fold): " +
                    "anchor=$anchorIdx → $budgetAnchor start=$effectiveStartIdx " +
                    "historySize=${history.size} keepTail≈$keepTail tokens",
            )
            anchorIdx = budgetAnchor
        } else {
            // [fix/compact-anchor-resolution] Same silent-early-return problem:
            // this is the branch a *stuck* anchor lands in on every single turn
            // (start = prevAnchor + 1 > anchor means "the range is already
            // folded"), so it is the one that most needs a log line.
            AppLogger.info(
                ChatViewModel.TAG,
                "[Compact] aborted: already compacted (start=$effectiveStartIdx > anchor=$anchorIdx " +
                    "anchorId=${history[anchorIdx].dbMessageId?.take(8)} " +
                    "prevAnchor=${_cachedLatestMarker?.lastCompactedMessageId?.take(8)} " +
                    "prevVersion=${_cachedLatestMarker?.version} historySize=${history.size} " +
                    "summaryChars=${_compactSummary.value?.length ?: 0} budgetAnchor=$budgetAnchor)",
            )
            if (!silent) appendSystemInfo(context.getString(R.string.sysmsg_compact_already_done), "compact")
            return
        }
    }
    val toCompact = history.subList(effectiveStartIdx, anchorIdx + 1)
    if (toCompact.isEmpty()) {
        AppLogger.info(
            ChatViewModel.TAG,
            "[Compact] aborted: empty range (start=$effectiveStartIdx anchor=$anchorIdx)",
        )
        if (!silent) appendSystemInfo(context.getString(R.string.sysmsg_compact_nothing), "compact")
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
    // [fix/audit0917-b8] Stamp the auto-compact retry gate HERE — at the last
    // point before real work starts — instead of in the two callers, which
    // stamped it *before* invoking compactAll. Those callers stamped even when
    // compactAll returned early (stream in flight / no persisted anchor /
    // already compacted / nothing to compact / budget exhausted), so a
    // pre-flight abort left the gate closed for the whole minIntervalMs window
    // with nothing compacted: auto-compaction silently went quiet. Stamping
    // here (not on success) still keeps the anti-thrash interval for genuine
    // failures — repeated provider errors must not re-hammer the model.
    // [feat/compact-range-observation] Seed observation for "compact folds
    // content by POSITION only (no relevance)": record what the folded range
    // actually contains — message count, tool-result-only share, user-text
    // turns, raw chars. Accumulating this answers, with two weeks of real
    // data, whether a relevance-aware selection (semantic / BM25) would fold
    // a different set than the positional range — the gate for deciding
    // whether to build one, before any such machinery is paid for.
    AppLogger.info(
        ChatViewModel.TAG,
        "[Compact] range composition: msgs=${toCompact.size} " +
            "toolResultOnly=${toCompact.count { it.isToolResultOnly() }} " +
            "userTextTurns=${toCompact.count { it.isPersistedUserPrompt() }} " +
            "chars=${toCompact.sumOf { it.content.length }} " +
            "start=$effectiveStartIdx anchor=$anchorIdx",
    )
    lastAutoCompactAtMs = System.currentTimeMillis()
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
            // [audit-0917] Only publish the in-memory compact state when the
            // marker actually persisted. The DB write was best-effort (logged
            // and ignored) while _compactSummary/_cachedLatestMarker were set
            // unconditionally — so a failed insert showed a compacted session
            // that silently reverted (dividers gone, full history replayed)
            // after the next reload. Now the failure is surfaced and the
            // in-memory boundary is not advertised as durable.
            val markerSaved = runCatching { chatRepository.dao.insertCompactMarker(marker) }
                .onFailure {
                    Log.w(ChatViewModel.TAG, "Failed to persist compact marker: ${it.message}")
                }
                .isSuccess
            if (!markerSaved) {
                AppLogger.warning(
                    ChatViewModel.TAG_STREAM,
                    "compact marker not persisted; keeping the summary in memory only",
                )
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
                //
                // [fix/diff-audit-0904-F2] The cutoff row match must cover
                // the LIVE-session id dialect, not just the cold-rebuild
                // one. Cold rebuild (loadSession) sets ChatMessage.id =
                // entity.id (the DB id), so the id match in applyCompactGreyedRange hits.
                // A live in-loop compact runs while the current turn's
                // bubbles still carry runtime ids (`assistant_<ts>` for
                // the streaming assistant row; tool-result carriers have
                // NO UI row at all). With the old id-only predicate the
                // walk never flipped passedCutoff — every non-system row
                // got grayed, INCLUDING the in-flight streaming bubble
                // (which represents the post-anchor current turn), and
                // updateAssistantMessage's copy() then carried the gray
                // flag for the rest of the run. Two changes:
                //   1. Match id OR sourceDbIds containing the anchor —
                //      restored rows carry sourceDbIds, merged rows carry
                //      the union.
                //   2. In-flight rows (isStreaming / isQueued /
                //      isAwaitingModelResponse) are by construction AFTER
                //      the anchor, so the walk flips passedCutoff at the
                //      last settled row even when the anchor row itself
                //      has no UI representation (tool-result carrier).
                // [audit-0916] The greying walk + tail repair moved into
                // [applyCompactGreyedRange] (ChatModels.kt) so the boundary
                // rules are JVM-testable — see that function's doc for the
                // no-op repair this replaces.
                val cleaned = applyCompactGreyedRange(_messages.value, lastCompactedDbId)
                // T84: count UI bubbles in this pass's compacted range.
                // Filters: role != system (dividers/notices don't count).
                // Range: everything up to and including the cutoff row,
                // since the kept-tail starts immediately after.
                // Falls back to "all non-system" when the anchor id is null
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
                val cutoffIdx = cleaned.indexOfLast { it.id == lastCompactedDbId || it.sourceDbIds.contains(lastCompactedDbId) }
                val compactedUICount = if (cutoffIdx < 0) {
                    cleaned.count { it.role != "system" }
                } else {
                    cleaned.take(cutoffIdx + 1).count { it.role != "system" }
                }
                // [fix/silent-auto-compact] AUTO compacts are silent: the UI
                // is left exactly as-is (no divider card, no graying) because
                // compaction is an agent-side context-management detail the
                // user neither triggered nor needs to see. The user-reported
                // symptom was the opposite: during a long agent run the
                // divider landed at the transcript TAIL (the in-loop compact
                // fires at a turn boundary where no row is "live", so
                // flushPendingSysInfo's in-flight anchor missed) and the
                // running answer kept growing ABOVE it. Manual /compact and
                // compact-before still show the divider — there the user
                // asked for it and needs the confirmation + Revert affordance.
                if (silent) {
                    // Strip divider cards + stale graying. See
                    // [neutralizeCompactArtifacts] for why this is a shared
                    // pure function rather than an inline expression.
                    _messages.value = neutralizeCompactArtifacts(_messages.value)
                    AppLogger.info(
                        ChatViewModel.TAG,
                        "[Compact] silent auto-compact: $compactedUICount UI bubbles folded " +
                            "(history entries: ${toCompact.size}); no divider, no graying",
                    )
                } else {
                    _messages.value = cleaned
                    AppLogger.info(ChatViewModel.TAG, "[Compact] divider: $compactedUICount UI bubbles compacted (history entries: ${toCompact.size})")
                    appendSystemInfo(
                        text = context.getString(R.string.sysmsg_compacted_count, compactedUICount),
                        iconKind = "compact",
                        payload = summary,
                    )
                }
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
    //
    // [fix/compact-model-fallback] A RemoteFailure/Unavailable on the ACTIVE
    // member no longer fails the whole compact: the group's healthy fallback
    // candidates get ONE pass each via the same gateway (the group router
    // already ordered them cheapest-first and filtered cooling/dead members).
    // CancellationException is not a result type — it propagates as before,
    // so a user cancel never degrades into a fallback retry. All-fallbacks-
    // failed throws the same typed exceptions the splitter expects, so the
    // halving path is untouched. Fallback outcomes are NOT recorded into the
    // group router: a compaction failure says nothing about the member's
    // chat-traffic health, and recording would demote a healthy member.
    suspend fun sendVia(p: LLMProvider): ProviderExecutionGateway.SendResult {
        // A fallback candidate without an instance context is skipped, not
        // fatal — the chain continues to the next candidate.
        val inst = p.instanceContext
            ?: return ProviderExecutionGateway.SendResult.Unavailable(
                "no instance context for ${p.model.displayName}"
            )
        return ProviderExecutionGateway.send(
            context = context,
            instance = inst,
            model = p.model,
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
        )
    }
    return when (val r = sendVia(provider)) {
        is ProviderExecutionGateway.SendResult.Success -> r.response.text
        is ProviderExecutionGateway.SendResult.RemoteFailure,
        is ProviderExecutionGateway.SendResult.Unavailable -> {
            val fallbacks = buildFallbackProviders(provider)
            AppLogger.info(
                ChatViewModel.TAG,
                "[Compact] summary failed on active member ($r) — trying ${fallbacks.size} fallback candidate(s)",
            )
            var lastFailure: Exception = IllegalStateException("compaction failed")
            for (candidate in fallbacks) {
                when (val fr = sendVia(candidate.provider)) {
                    is ProviderExecutionGateway.SendResult.Success -> {
                        AppLogger.info(
                            ChatViewModel.TAG,
                            "[Compact] summary fallback SUCCESS entry=${candidate.entryId} " +
                                "model=${candidate.provider.model.displayName}",
                        )
                        return fr.response.text
                    }
                    else -> {
                        AppLogger.info(
                            ChatViewModel.TAG,
                            "[Compact] summary fallback failed entry=${candidate.entryId}: $fr",
                        )
                        lastFailure = when (fr) {
                            is ProviderExecutionGateway.SendResult.RemoteFailure ->
                                IllegalStateException("compaction failed (${fr.code}): ${fr.message}")
                            is ProviderExecutionGateway.SendResult.Unavailable ->
                                IllegalStateException("compaction unavailable: ${fr.reason}")
                            else -> lastFailure
                        }
                    }
                }
            }
            throw lastFailure
        }
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
                if (m.partsJson.contains("\"tool_use\"") || m.partsJson.contains("\"tool_result\"")) {
                    withTools++
                }
                if (m.partsJson.contains("\"image\"") || m.partsJson.contains("\"attachment\"")) {
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
        agentHistory.clear()
        agentHistory.addAll(loaded.llmHistory)
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
    // rendering — graying and the divider row are both gone. What it still
    // does is decide whether the marker RESOLVED against the loaded rows, and
    // the branch that fails is the one that runs the createdAt self-heal
    // (rewriting an orphaned marker as v2 and persisting it). That heal is
    // what keeps effectiveAgentHistory() working after messages are deleted
    // or restored from a backup, so the walk stays.
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

    // ─── Apply graying + divider ──────────────────────────────────
    // [fix/silent-auto-compact] Neither is applied any more. The marker is
    // an agent-side context boundary; dimming the rows it covers told the
    // user "something happened here" without telling them anything
    // actionable, and the divider card was the visible artefact they
    // objected to. A card that only appeared after a cold reload (but not
    // during the live session) would be a worse surprise than one shown
    // consistently, so the reload path drops it too.
    //
    // `insertIdx` is still resolved above on purpose: the self-heal branch
    // repairs an orphaned marker (rewriting it as v2 + persisting), which
    // keeps effectiveAgentHistory() — the thing that actually matters —
    // working after messages are deleted or restored from a backup.
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
 * createdAt self-heal: return the LAST raw message whose
 * `createdAt < markerCreatedAt` AND whose id is still represented in
 * agentHistory (filtered via [historyDbIds]). When [historyDbIds] is
 * empty (no dbIds collected — unusual), the filter degrades to "just
 * the createdAt predicate" so we still recover SOMETHING.
 *
 * Mirrors iOS AIChatViewModel+Compaction.swift:125.
 */
internal fun ChatViewModel.anchorByCreatedAt(
    rawMessages: List<com.rikkaminis.app.data.db.MessageEntity>,
    markerCreatedAt: Long,
    historyDbIds: Set<String>,
    ): com.rikkaminis.app.data.db.MessageEntity? {
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
    if (preferredEntryId == null && group.strategy == com.rikkaminis.app.data.model.RoutingStrategy.loadBalance) {
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
