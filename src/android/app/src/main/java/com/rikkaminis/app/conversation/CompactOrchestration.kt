package com.rikkaminis.app.conversation

import android.os.SystemClock
import android.util.Log
import com.rikkaminis.app.data.AgentRuntimeLimitsPrefs
import com.rikkaminis.app.data.db.CompactMarkerEntity
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMResponse
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.provider.LLMProvider
import com.rikkaminis.app.sandbox.offload.ProviderExecutionGateway
import com.rikkaminis.app.provider.ProviderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import com.rikkaminis.app.R
import com.rikkaminis.app.agent.runtime.AgentRunEvent
import com.rikkaminis.app.agent.runtime.AgentRunPhase
import com.rikkaminis.app.tools.AgentTraceRecorder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.rikkaminis.app.ui.chat.ChatAgentTraceObserver
import com.rikkaminis.app.ui.chat.ChatViewModel
import com.rikkaminis.app.ui.chat.applyCompactGreyedRange
import com.rikkaminis.app.ui.chat.buildConversationTextForSummary
import com.rikkaminis.app.ui.chat.buildFallbackProviders
import com.rikkaminis.app.ui.chat.compactBudgetTailKeepTokens
import com.rikkaminis.app.ui.chat.isContextTooLargeError
import com.rikkaminis.app.ui.chat.isPersistedUserPrompt
import com.rikkaminis.app.ui.chat.isToolResultOnly
import com.rikkaminis.app.ui.chat.neutralizeCompactArtifacts
import com.rikkaminis.app.ui.chat.prependCompactionPin
import com.rikkaminis.app.ui.chat.ordersCompactionCandidates
import com.rikkaminis.app.ui.chat.resolveBudgetAnchorIdx
import com.rikkaminis.app.ui.chat.resolveCompactAnchorIdx
import com.rikkaminis.app.ui.chat.resolveCompactStartIdx
import com.rikkaminis.app.ui.chat.resumeQueueAfterCancel

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
    // ── silent 契约（[fix/compact-silent-abort-cards-1016]）──────────────
    // silent=true 的调用方只有两个：发送前的 maybeTriggerAutoCompact 与
    // 长跑回合边界的 maybeAutoCompactInLoop —— 都是 agent 侧的上下文管理，
    // 不是用户主动动作，对话记录必须看起来没被动过。所以 compactAll 内部
    // 的**每一条** appendSystemInfo 都必须是「manual-only」，即包在
    // `if (!silent)` 里（分栏 divider 是唯一的例外：它本来就在 else 分支，
    // 只在非 silent 路径出现）。判据不是「silent 模式下不许有任何输出」，
    // 而是「能被重复触发、会在回合中反复出现的卡片 = 泄漏；一次性的、用户
    // 需要知道的失败通知 = 保留」。逐点判定（2026-10-16，FIX-D）：
    //  - 已 gate 的 9 处（busy/no-provider/empty-session/no-persisted/
    //    already-done/nothing/budget-exhausted/anchor-failed/empty-summary）：
    //    无事发生或可重复的失败，silent 不打印，日志保留诊断；
    //  - anchor_id_missing：刻意不 gate —— LLMMessage.dbMessageId 是 val、
    //    且上游两条路都保证非空，这条分支证明不可达；若真的触发，说明内存
    //    不变量本身坏了，属于「用户需要看一次」的失败通知（见该处注释）；
    //  - compact_failed 横幅：gate —— 3 天真机日志（10-14/15/16）11 次
    //    "Compact failed"，同窗口 134 次 compactAll 全部 silent=true，且失败在回合间会复发
    //    （provider 超时持续存在），正是会重复的卡片；下游仍有 trim 通知、
    //    发送前 context-full 提示与 Log.w，信息不丢。
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
        // [fix/sweep1014-b13] Was ungated — the other 7 abort branches all
        // guard on !silent; an in-loop maybeAutoCompactInLoop(silent=true)
        // re-emitted this card every turn once the budget was exhausted.
        if (!silent) appendSystemInfo(context.getString(R.string.sysmsg_compact_budget_exhausted), "compact")
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
    // turns, estimated tokens. Accumulating this answers, with two weeks of
    // real data, whether a relevance-aware selection (semantic / BM25) would
    // fold a different set than the positional range — the gate for deciding
    // whether to build one, before any such machinery is paid for.
    // [fix/stream-recovery-grace-race] estTokens uses the compactor's own
    // token estimate (content + contentParts) instead of raw content chars:
    // tool-result text lives in contentParts, so the old chars figure
    // under-counted tool-heavy sessions to near-zero.
    AppLogger.info(
        ChatViewModel.TAG,
        "[Compact] range composition: msgs=${toCompact.size} " +
            "toolResultOnly=${toCompact.count { it.isToolResultOnly() }} " +
            "userTextTurns=${toCompact.count { it.isPersistedUserPrompt() }} " +
            "estTokens=${toCompact.sumOf { ContextCompactor.estimateMessageTokens(it) }} " +
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
    // [fix/compact-cancel-on-stop-1002] Store the job so cancelStream can
    // kill the compact (see compactJob KDoc). CancellationException already
    // propagates cleanly through this launch: the catch rethrows, the
    // finally resets _isCompacting + traces, and nothing is persisted before
    // the commit block — a cancelled compact leaves the transcript unfolded
    // and writes no marker. [§47 N-2] The commit block BELOW is the boundary
    // of that promise: a cancel landing on insertCompactMarker either keeps
    // the row out (no trace) or leaves the row without its in-memory mirror
    // (the next session load rebuilds the summary from it) — never a second
    // write, and never "zero trace" once the insert has committed.
    compactJob = viewModelScope.launch(Dispatchers.IO) {
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
                // [fix/compact-silent-abort-cards-1016] Gated with !silent like
                // every other abort branch above: a silent auto-compact must
                // leave the transcript untouched, and this branch can be
                // re-entered on each eligible attempt (an empty summary is a
                // provider behaviour, not a one-off), so the card would recur.
                // Observability moves to the log — the branch had none before,
                // and the card used to be the only trace of "summary came back
                // empty". Manual /compact still shows it.
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "[Compact] summary came back empty (silent=$silent) — nothing folded",
                )
                if (!silent) {
                    withContext(Dispatchers.Main) {
                        appendSystemInfo(context.getString(R.string.sysmsg_compact_empty_summary), "compact")
                    }
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
                chatRepository.loadMessages(sid).map { it.id }.toSet()
            } catch (e: CancellationException) {
                // [fix/clearchat-compact-ce-1002] A stop press cancels
                // compactJob (fix/compact-cancel-on-stop-1002). The summary
                // LLM call is the first suspend point and usually unwinds
                // first, but a cancel can also land on this read —
                // swallowing CE here would misreport cancellation as "DB
                // verify failed" and fall back to the in-memory anchor,
                // continuing a cancelled compact on stale state. Rethrow so
                // the coroutine unwinds exactly like every other cancel
                // point; other failures keep the best-effort semantics.
                throw e
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
                // [fix/compact-silent-abort-cards-1016] Gated with !silent like
                // its :139 pre-summary twin (same stuck-anchor family — when
                // reachable it recurs every eligible attempt, so a silent
                // auto-compact must not print it). Manual /compact keeps it.
                if (!silent) {
                    withContext(Dispatchers.Main) {
                        appendSystemInfo(context.getString(R.string.sysmsg_compact_anchor_failed), "compact")
                    }
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
                    // [fix/compact-silent-abort-cards-1016] Deliberately NOT
                    // silent-gated, unlike the three sibling abort cards. Criterion:
                    // a silent auto-compact must stay invisible only for cards that
                    // can be re-emitted; this branch is provably unreachable —
                    // LLMMessage.dbMessageId is a `val` on an immutable data class,
                    // and the guard above only lets a non-empty id through (both
                    // the rawDbIds walk-back and resolveCompactAnchorIdx require
                    // "not null and not empty"). If it ever fires the in-memory
                    // invariant itself broke, and that is a one-time notice the
                    // user should see rather than a repeatable card.
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
            // [fix/compact-cancel-on-stop-1002] runCatching around a suspend
            // call swallows CancellationException — with stop now able to
            // cancel this coroutine mid-insert, that would set the in-memory
            // marker/summary while the DB row never landed (the exact
            // [audit-0917] "compacted in memory, full history after reload"
            // inconsistency this guard exists to prevent). Rethrow
            // cancellation; other failures keep the best-effort semantics.
            // [fix/r3-b11-compact-F02] The contract above was quoted but not
            // enforced: a failed insert only logged a warning, then
            // _compactSummary/_cachedLatestMarker were published anyway, the
            // UI was greyed and a divider + "compacted N bubbles" card
            // announced success, and compactSucceeded still kicked the queued
            // drain. Reload then silently replayed the full history (no row),
            // and the divider's Revert affordance failed with
            // sysmsg_revert_failed_db (deleteCompactMarker → 0 rows). A
            // non-persisted marker is now a COMPACT FAILURE: it throws into
            // compactAll's own catch, so nothing in-memory is published, no
            // greying/divider is painted, the queue is not kicked, and the
            // caller falls back to the hard trim exactly like every other
            // compact failure (the auto path detects `folded == false`).
            // Manual /compact sees sysmsg_compact_failed with the underlying
            // DB error; silent auto-compacts stay silent, same !silent policy
            // as the sibling failure branches.
            try {
                chatRepository.dao.insertCompactMarker(marker)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(ChatViewModel.TAG, "Failed to persist compact marker: ${e.message}")
                throw IllegalStateException(
                    "compact marker not persisted: ${e.message ?: e.javaClass.simpleName}",
                    e,
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
            // [fix/compact-silent-abort-cards-1016] Gated with !silent: this is
            // the most frequent compact-failure path (3 days of device logs:
            // 11 "Compact failed" emissions, and all 134 compactAll() attempts
            // in that window were silent=true), and it re-fires on every
            // eligible attempt (provider timeouts persist across turns), so in
            // silent mode it is exactly the "repeated card mid-answer" leak
            // shape the 9 already-gated abort branches were closed for. The
            // failure still surfaces downstream: in-loop the caller falls back
            // to trimContextHistoryWindow (its trim notice is a separate
            // feature the strip pass must not eat), the send entry gets the
            // context-full advisory, and Log.w + the caller's
            // "[AutoCompactLoop] folded=nothing" INFO stay. Manual /compact
            // keeps the banner.
            if (!silent) {
                withContext(Dispatchers.Main) {
                    appendSystemInfo(
                        text = context.getString(R.string.sysmsg_compact_failed, e.message ?: e.javaClass.simpleName),
                        iconKind = "compact",
                    )
                }
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
    // [feat/compact-pin-v0-1005] Pin v0: user verbatim text never passes
    // through a rewrite. Extraction + strip happen at the depth-0 entry
    // (BEFORE any LLM call); the re-append happens at the one exit below
    // (AFTER the LLM). The split/merge prompts never see a pin block: the
    // payload is stripped at the depth-0 entry and the recursion only ever
    // receives the STRIPPED summary.
    // previousSummary comes from the previous round's summary and may carry
    // a `<pinned-user-messages>` block: strip it here, carry the payload,
    // and re-merge it with this fold's user text at the exit.
    val pinNew = if (depth == 0) extractPinnedUserMessages(messages) else emptyList()
    val pinCarried = if (depth == 0) pinnedSectionInner(previousSummary) else null
    val prevSummaryStripped = if (depth == 0) stripPinnedSection(previousSummary) else previousSummary
    val transcript = buildConversationTextForSummary(messages)
    val conversationText = ContextCompactor.compactInputText(prevSummaryStripped, transcript)
    val summary = try {
        generateCompactSummary(conversationText)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!isContextTooLargeError(e) || messages.size < 2 || depth >= 3) {
            throw e
        }
        // equivalent-mutant arm: comment only
        val mid = messages.size / 2
        val firstHalf = messages.subList(0, mid).toList()
        val secondHalf = messages.subList(mid, messages.size).toList()
        AppLogger.info(
            ChatViewModel.TAG,
            "[Compact] Splitting ${messages.size} messages into ${firstHalf.size} + ${secondHalf.size} (depth=$depth)",
        )
        // [fix/r3-b11-compact-F01] The first half is the OLDER material, so it
        // is exactly the half that must be summarized ON TOP OF the previous
        // rounds' summary. Passing `null` here dropped prevSummaryStripped the
        // moment the depth-0 pass hit ContextTooLarge: summary1 then covered
        // only this fold's first half, the merge prompt merged those two
        // halves, and the returned string replaced the previous summary in the
        // marker (`compactAll` persists it) — erasing every earlier compact
        // round, tool conclusion and decision, irreversibly. The second half
        // keeps `null`: it has no older context to sit on (the previous
        // summary was already incorporated by the first half, and feeding it
        // to both halves would double-count it in the merge). At depth > 0
        // prevSummaryStripped is the pushed-down summary, so a re-split inside
        // the first half keeps the chain intact instead of cutting it again.
        val summary1 = generateCompactSummaryWithSplitting(firstHalf, prevSummaryStripped, depth + 1)
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
    // [feat/compact-pin-v0-1005] Re-append AFTER the LLM — the pinned user
    // verbatim text never passes through a rewrite. Empty pin + no carried
    // block → today's behavior, byte-identical.
    return if (pinNew.isEmpty() && pinCarried == null) {
        summary
    } else {
        appendPinnedSection(summary, pinNew, pinCarried)
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
    // [fix/compact-quiet-first-1001] Ordering: quiet-capable members first —
    // measured 2026-10-01 14:09 (error-snapshot-141020): the summary ask went
    // to the ACTIVE member with ThinkingLevel.OFF; the relay model has no
    // wire way to express OFF (declaresNoEffortTiers) and thought for 71s
    // producing ZERO content until the gateway RST the stream. The whole
    // compact then burned 82s per halving round with the user's chat queue
    // behind it. A summary does not need the active member's persona, so
    // members that cannot silence thinking go LAST; every attempt gets a
    // per-candidate wall budget — 30s, or 60s for the cannot-silence members
    // ([fix/compact-truncation-guard-1005]) — and the whole chain a 120s
    // deadline (rationale on the ChatViewModel companion constants). A
    // success whose stopReason was cut at the output ceiling ("length")
    // counts as a failure and tries the next candidate — a truncated
    // summary must never replace the context. withTimeoutOrNull swallows ONLY
    // its own TimeoutCancellationException — a user cancel still propagates
    // (termination path unchanged) — and an exhausted chain throws lastFailure
    // exactly as before, so the splitter's halving retry is
    // untouched.
    // [fix/compact-lastfailure-pin-1016] "exactly as before" 有一个刻意的例外：
    // 链上出现过 ContextTooLarge 分类的失败时，抛出的必须是它，而不是链尾的
    // 最后一个普通失败（超时 / 5xx / unavailable）—— 否则外层 catch 的
    // isContextTooLargeError 分支永远看不到容量证据，「halving retry is
    // untouched」就只是注释里的空话（真实日志：Splitting 事件 3 天 0 次）。
    // 普通失败链的语义不变：仍然抛最后一条。See ordersCompactionCandidates
    // for the ordering logic.
    // [feat/compact-model-pin-1005] The user-pinned compaction model
    // (Settings → Model Groups → 压缩模型, moved from Runtime Limits) rides
    // the chain head. It works
    // in BOTH selection modes: inside the current group (matched against
    // buildFallbackProviders' filtered candidates) or outside it (direct
    // global resolution — e.g. the session runs a group but the pin points
    // at a provider-level entry, or the session runs a single model at all).
    // Resolution applies the SAME filters as buildFallbackProviders (router
    // health / enabled instance / stored key / provider construction) — the
    // pin never bypasses a demoted member. The pin does NOT change budgets, deadline,
    // truncation guard, or the no-health-writeback rule; it only reorders
    // who is tried first. Unresolvable pin = silent follow-session (one
    // INFO line), identical to the pre-pin chain.
    val chain = run {
        val pinId = AgentRuntimeLimitsPrefs.compactModelEntryId()
        if (pinId.isBlank()) {
            ordersCompactionCandidates(provider, buildFallbackProviders(provider))
        } else {
            val base = buildFallbackProviders(provider)
            prependCompactionPin(
                pinId,
                _activeEntryId.value,
                provider,
                base,
            ) { entryId -> resolveCompactionPinProvider(entryId) }
                .also { pinned ->
                    // The pin rides the head as the active slot (contract:
                    // second == null) when it IS the active member — count
                    // that as reached too, or a healthy pin on the session's
                    // own model would log a false "not reachable".
                    val found = pinId == _activeEntryId.value ||
                        pinned.any { it.second == pinId }
                    if (!found) {
                        AppLogger.info(
                            ChatViewModel.TAG,
                            "[Compact] pinned model $pinId not reachable — following session chain",
                        )
                    }
                }
        }
    }
    val deadlineAt = SystemClock.elapsedRealtime() + ChatViewModel.COMPACT_SUMMARY_TOTAL_BUDGET_MS
    var lastFailure: Exception = IllegalStateException("compaction failed")
    // [fix/compact-lastfailure-pin-1016] ContextTooLarge 的原始失败必须钉住：
    // 二分拆解（recursive halving）只认 `isContextTooLargeError`，而判定发生在
    // **外层** catch —— 本函数把异常抛出去之后。若 TooLarge 被后续候选的普通
    // 失败（超时 / 5xx / unavailable / truncated）覆盖，链上唯一的容量证据就
    // 丢了：外层按普通失败处理 → 不拆解、不重试，整条链当失败收场，而这正是
    // :691 注释里「halving retry is untouched」契约的反面。真实时序量过：
    // 候选 1/2 回 400 context length、候选 2/2 超时，修复后外层看到的必须是 400。
    var tooLargeFailure: Exception? = null
    // [fix/compact-timeout-dedup-1022] A TIMEOUT says something about the
    // ROUTE (host + model), not about the account behind it. 2026-10-22 device
    // log: three tierflow entries sharing https://tierflow.cn and model
    // `tierflow` each died at exactly 30000ms inside ONE chain, so the chain
    // paid the same wall three times and handed the genuinely different member
    // (codebuddy-cn/hy4-preview) only what the deadline had left — 25 such
    // chains that day. Remember the routes that timed out in THIS chain and
    // skip their siblings; the freed deadline goes to a candidate that can
    // still answer. Route failures (401 / 429 / 5xx) are deliberately NOT
    // recorded: those are per-credential, and the sibling accounts exist for
    // exactly them — only the latency verdict is a route property.
    val timedOutRoutes = mutableSetOf<String>()
    for ((index, step) in chain.withIndex()) {
        val (candidate, entryId) = step
        val label = if (entryId != null) "entry=$entryId" else "active"
        val route = compactionCandidateKey(
            customBaseUrl = candidate.instanceContext?.customBaseURL,
            instanceId = candidate.instanceContext?.id,
            modelId = candidate.model.id,
        )
        if (route in timedOutRoutes) {
            AppLogger.info(
                ChatViewModel.TAG,
                "[Compact] summary skip candidate=${index + 1}/${chain.size} " +
                    "($label ${candidate.model.displayName}) — route $route already timed out in this chain",
            )
            continue
        }
        val budgetMs = compactionCandidateBudgetMs(
            candidate.model.declaresNoEffortTiers,
            estimatedInput,
            SystemClock.elapsedRealtime(),
            deadlineAt,
        )
        if (budgetMs <= 0) {
            lastFailure = IllegalStateException(
                "compaction exceeded the ${ChatViewModel.COMPACT_SUMMARY_TOTAL_BUDGET_MS}ms chain budget",
            )
            AppLogger.info(
                ChatViewModel.TAG,
                "[Compact] summary chain budget exhausted — skipping candidate ${index + 1}/${chain.size}",
            )
            break
        }
        val started = SystemClock.elapsedRealtime()
        val r = withTimeoutOrNull(budgetMs) { sendVia(candidate) }
        val ms = SystemClock.elapsedRealtime() - started
        if (r == null) {
            AppLogger.info(
                ChatViewModel.TAG,
                "[Compact] summary TIMEOUT after ${ms}ms (budget ${budgetMs}ms) " +
                    "candidate=${index + 1}/${chain.size} ($label ${candidate.model.displayName})",
            )
            lastFailure = IllegalStateException(
                "compaction summary timed out after ${budgetMs}ms on ${candidate.model.displayName}",
            )
            // [fix/compact-timeout-dedup-1022] Latency verdict → route-level:
            // see the declaration of timedOutRoutes. Sibling entries on the
            // same host+model are skipped from here on within this chain.
            timedOutRoutes.add(route)
            continue
        }
        when (r) {
            is ProviderExecutionGateway.SendResult.Success -> {
                if (compactSummaryIsTruncated(r.response.stopReason)) {
                    AppLogger.info(
                        ChatViewModel.TAG,
                        "[Compact] summary TRUNCATED on ${candidate.model.displayName} " +
                            "candidate=${index + 1}/${chain.size} ($label) — trying next",
                    )
                    lastFailure = compactSummaryTruncatedFailure(candidate.model.displayName)
                    continue
                }
                AppLogger.info(
                    ChatViewModel.TAG,
                    "[Compact] summary ${if (entryId != null) "fallback " else ""}SUCCESS " +
                        "candidate=${index + 1}/${chain.size} ($label ${candidate.model.displayName}) in ${ms}ms" +
                        // [fix/compact-telemetry-superseded-1005] horizon telemetry on
                        // the same INFO line (SUCCESS is low-frequency): summary
                        // length + model-reported output tokens, usage-null-safe.
                        compactSummaryTelemetrySuffix(r.response),
                )
                return r.response.text
            }
            is ProviderExecutionGateway.SendResult.RemoteFailure -> {
                AppLogger.info(
                    ChatViewModel.TAG,
                    "[Compact] summary failed on ${if (entryId != null) "fallback $label" else "active member"} " +
                        "(${r.code}: ${r.message}) — trying next of ${chain.size - index - 1} candidate(s)",
                )
                // [fix/compact-lastfailure-pin-1016] 归约规则单源在
                // [ContextCompactor.chainFailureAfter]：链上唯一的容量证据在此
                // 钉住，任何后续候选的普通失败都不得覆盖它（见 tooLargeFailure
                // 声明处的时序注释）。
                val candidateFailure = IllegalStateException("compaction failed (${r.code}): ${r.message}")
                val reduced = ContextCompactor.chainFailureAfter(
                    lastOrdinary = lastFailure,
                    pinnedTooLarge = tooLargeFailure,
                    candidate = candidateFailure,
                    isTooLarge = ::isContextTooLargeError,
                )
                lastFailure = reduced.first
                tooLargeFailure = reduced.second
            }
            is ProviderExecutionGateway.SendResult.Unavailable -> {
                AppLogger.info(
                    ChatViewModel.TAG,
                    "[Compact] summary unavailable on $label (${r.reason}) — trying next of ${chain.size - index - 1} candidate(s)",
                )
                lastFailure = IllegalStateException("compaction unavailable: ${r.reason}")
            }
        }
    }
    // [fix/compact-lastfailure-pin-1016] 链全败收场时：TooLarge 优先于普通失败
    // 抛出 —— 让外层 catch 的 isContextTooLargeError 分支（二分拆解重试）拿到
    // 它该看到的证据，而不是被链尾的最后一个普通失败冲掉。终态规则单源在
    // [ContextCompactor.chainTerminalFailure]。
    lastFailure = ContextCompactor.chainTerminalFailure(lastFailure, tooLargeFailure)
    throw lastFailure
}

/**
 * [feat/compact-model-pin-1005] Global resolver for a pinned compaction
 * model entry: config lookup + the SAME filters [buildFallbackProviders]
 * applies (router health / enabled instance / stored API key /
 * ProviderFactory success). Reads the router's health gate
 * (groupRouter.isUsable) so a cooling (429) / circuit-open (5xx) / dead
 * (401) member is never re-tried — the chain follows the session instead.
 * Returns null for an unknown/stale/demoted/disabled/credential-less
 * entry — the caller then logs one INFO line and follows the session
 * chain. Never writes group health, never persists anything.
 */
internal fun ChatViewModel.resolveCompactionPinProvider(entryId: String): LLMProvider? {
    val config = providerRepository.config.value
    val entry = config.modelEntries.find { it.id == entryId } ?: return null
    // Same health gate as buildFallbackProviders — never re-try a member
    // the router just demoted; the pin only rides the head while healthy.
    if (!groupRouter.isUsable(entryId)) return null
    val instance = config.instances.find { it.id == entry.providerInstanceId } ?: return null
    if (!instance.isEnabled) return null
    val apiKey = providerRepository.loadApiKey(instance.id) ?: return null
    return try {
        // [dv24-FIX-8 R3] Enriched model, same as every other create site:
        // `entry.model` is the raw base model, whose modality/effort fields are
        // null for built-in entries. :modelservice never initialises
        // [ModelsDevApi], so the provider re-decides capabilities from the
        // serialised object — an un-enriched model downgrades an attachment to
        // `[attached image: …]` and clamps a MAX thinking pick (same reasoning
        // as [ChatViewModel.providerModelFor]).
        ProviderFactory.create(instance, apiKey, providerModelFor(entry), context)
    } catch (_: Exception) {
        null
    }
}

/**
 * [fix/compact-truncation-guard-1005] Per-candidate wall budget for the
 * compaction summary chain: candidates the relay cannot silence
 * ([LLMModel.declaresNoEffortTiers] == true) get the 60s budget, everything
 * else the 30s quiet floor; both are clamped by the chain deadline so the
 * whole operation never exceeds COMPACT_SUMMARY_TOTAL_BUDGET_MS. A spent
 * deadline yields a non-positive budget and the caller's `budgetMs <= 0`
 * branch ends the chain.
 *
 * [fix/compact-timeout-budget-1022] [materialTokens] is the estimated size of
 * the summary material this chain will send; above
 * COMPACT_SUMMARY_LARGE_MATERIAL_TOKENS it opens the same 60s budget the
 * noisy members get, because the measured success latency on those relays
 * crowds the 30s floor as soon as the fold material grows.
 *
 * Top-level (not a ChatViewModel extension) so JVM unit tests call it
 * without instantiating the VM — same pattern as [ordersCompactionCandidates]
 * (the companion consts it reads are compile-time inlined, no Android
 * classes are loaded).
 */
internal fun compactionCandidateBudgetMs(
    declaresNoEffortTiers: Boolean?,
    materialTokens: Int,
    nowMs: Long,
    deadlineAt: Long,
): Long {
    // [fix/compact-timeout-budget-1022] Two independent reasons to admit the
    // member cannot answer inside the quiet floor: the catalog says its relay
    // cannot be silenced ([LLMModel.declaresNoEffortTiers] == true), or the
    // summary material is large enough that the measured success latency
    // itself crowds the floor (2026-10-22: 25.0s / 28.2s / 29.1s successes on
    // the same members whose larger folds timed out at exactly 30000ms). See
    // ChatViewModel.COMPACT_SUMMARY_LARGE_MATERIAL_TOKENS for the measurement.
    val largeMaterial = materialTokens > ChatViewModel.COMPACT_SUMMARY_LARGE_MATERIAL_TOKENS
    val perCandidate = if (declaresNoEffortTiers == true || largeMaterial) {
        ChatViewModel.COMPACT_SUMMARY_NOISY_CANDIDATE_BUDGET_MS
    } else {
        ChatViewModel.COMPACT_SUMMARY_CANDIDATE_BUDGET_MS
    }
    return minOf(perCandidate, deadlineAt - nowMs)
}

/**
 * [fix/compact-timeout-dedup-1022] Route identity of a compaction candidate:
 * the host it actually talks to plus the model it asks for. Two entries backed
 * by the same relay host and the same model are the same route — a timeout on
 * one predicts the timeout on the other, because the latency comes from the
 * upstream model, not from the credential (2026-10-22: three tierflow entries
 * sharing https://tierflow.cn + model `tierflow` all died at 30000ms in one
 * chain; the account-swapping fallback exists for credential failures, which
 * arrive as RemoteFailure/Unavailable and are therefore never recorded here).
 *
 * [customBaseUrl] is the instance's raw user field; the host is parsed out of
 * it so `https://tierflow.cn/v1` and `https://tierflow.cn/` collapse together.
 * Built-in provider types carry no custom base URL — there the instance id
 * stands in for the host, which keeps distinct instances distinct (their hosts
 * are not comparable from here, so no cross-instance claim is made). Model
 * parsing failure falls back to the raw string rather than skipping the field.
 */
internal fun compactionCandidateKey(
    customBaseUrl: String?,
    instanceId: String?,
    modelId: String,
): String {
    val host = customBaseUrl
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { url -> runCatching { java.net.URI(url).host }.getOrNull() }
        ?: instanceId
    return "${host ?: "?"}|$modelId"
}

/**
 * [fix/compact-truncation-guard-1005] True when the summary response was cut
 * at the output ceiling and must NOT be adopted. The provider passes
 * `finish_reason` through verbatim; the output-ceiling probe over 8
 * OpenAI-compatible relays (compact-exp-1004/capprobe) showed silent
 * truncation arrives as the OpenAI spelling "length" (WorkBuddy), while
 * relays that error out go through SendResult.RemoteFailure instead — so
 * only that one spelling is intercepted. Null / "end_turn" / "stop" /
 * unrecognised values pass (conservative: a channel's non-standard
 * completion marker must never cost a good summary its candidacy).
 * Deliberately narrower than [TruncatedToolCallPolicy.isTruncatedFinish],
 * which guards a different contract (turn tool-calls) with a wider set.
 */
internal fun compactSummaryIsTruncated(stopReason: String?): Boolean =
    stopReason?.trim()?.lowercase() == "length"

/**
 * [fix/compact-truncation-guard-1005] The failure recorded when a candidate's
 * summary came back truncated — assigned to `lastFailure`, so an
 * all-truncated chain throws the same shape the timeout/failure paths use.
 */
internal fun compactSummaryTruncatedFailure(modelDisplayName: String): IllegalStateException =
    IllegalStateException("compaction summary truncated (stopReason=length) on $modelDisplayName")

/**
 * [fix/compact-telemetry-superseded-1005] Telemetry segment appended to the
 * SUCCESS log line: the adopted summary's character count plus the
 * model-reported output tokens, so "how far into the summary horizon is this
 * conversation" (compact-exp-1004 D-hold: the compactor hit the 4096 output
 * cap six times in a row before the truncation guard existed; E: low-density
 * material saturates ~2.1k tok) becomes a log reading instead of a surprise.
 * usage is null on channels that don't report it — the outTok segment is
 * omitted then, never logged as a placeholder 0 (absent reads as "unknown",
 * 0 would read as "empty"). Pure function over [LLMResponse], JVM-testable
 * like the guard helpers above; the leading space keeps
 * `in ${ms}ms chars=…` single-spaced at the call site.
 */
internal fun compactSummaryTelemetrySuffix(response: LLMResponse): String =
    buildString {
        append(" chars=")
        append(response.text.length)
        response.usage?.let { usage ->
            append(" outTok=")
            append(usage.outputTokens)
        }
    }
