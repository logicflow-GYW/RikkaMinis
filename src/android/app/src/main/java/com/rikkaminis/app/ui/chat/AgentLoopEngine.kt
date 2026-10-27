package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.agent.Level
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.AgentToolDefinition
import com.rikkaminis.app.provider.ToolJsonRepair
import com.rikkaminis.app.service.SessionActivityTracker
import com.rikkaminis.app.tools.ToolConcurrencyPolicy
import com.rikkaminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.rikkaminis.app.agent.runtime.AgentExecutionBudget
import com.rikkaminis.app.agent.runtime.AgentRunBudgetCeilings
import com.rikkaminis.app.agent.runtime.AgentRunEvent
import com.rikkaminis.app.agent.runtime.AgentRunPhase
import com.rikkaminis.app.agent.runtime.AgentRunState
import com.rikkaminis.app.agent.runtime.AgentTerminal
import com.rikkaminis.app.agent.runtime.AgentTerminalReason
import com.rikkaminis.app.agent.runtime.ProviderAttemptOutcome
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.model.LLMUsage
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.conversation.ContextCalibration
import com.rikkaminis.app.conversation.ContextEstimator
import com.rikkaminis.app.conversation.PreflightVerdict
import com.rikkaminis.app.conversation.preflightContextBudget
import com.rikkaminis.app.provider.LLMProvider
import com.rikkaminis.app.tools.AgentTraceRecorder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.rikkaminis.app.R

/**
 * FE-5 route C step 3: the agent loop engine. The ~1940-line body of
 * ChatViewModel.runAgentLoop lifted VERBATIM — every VM member reference is
 * now an [AgentLoopHost] call; the run-scoped mutable state lives on
 * [AgentLoopState] and the T7/T9 observation on [ChatAgentTraceObserver],
 * both injected per run. The VM remains the host implementation (it owns the
 * UI state flows and persistence); this file owns loop mechanics only.
 *
 * Pure-function helpers (mergeLengthWallSeam, rollbackTurnBlocksTo,
 * buildTurnPartsPure, friendlyToolTitle, parseToolParams,
 * extractPartialStringValue, toolCallDedupeFingerprint, textDeltaThrottleMs)
 * stay top-level in their existing files and are called directly.
 */

/** Hard cap on agent-loop turns (moved from ChatViewModel companion, FE-5 route C).
 *  [feat/runtime-limits-panel] the loop bound now reads the user-tunable
 *  AgentRuntimeLimitsPrefs.maxTurns() (default 256 == this constant); the
 *  const stays as the documented default + banner fallback. */
internal const val MAX_AGENT_TURNS = 256

/**
 * [feat/hermes-tier1] Max text-continuation rounds per length-wall wall.
 * A truncated (finish_reason="length") turn with visible text is continued
 * at most this many times; past the cap the loop stops continuing, rolls
 * the seam back to the last clean fold, and surfaces the truncation error.
 * Mirrors Hermes turn_truncation's continuation ceiling of 4.
 * [feat/runtime-limits-panel] runtime truth is
 * AgentRuntimeLimitsPrefs.lengthWallContinues() (default 4 == this); const
 * kept as the documented default.
 */
internal const val MAX_LENGTH_WALL_TEXT_CONTINUES = 4

/**
 * [feat/hermes-tier1] Consecutive deterministic-empty completions (usage
 * proves output_tokens == 0) after which the loop gives up instead of
 * re-billing. Mirrors Hermes empty_response_guard's skip-retries-on-2-
 * deterministic-empties (adapted: RikkaMinis keeps one reminder round for
 * the tool-result case, so the streak limit is 2 here).
 * [feat/runtime-limits-panel] runtime truth is
 * AgentRuntimeLimitsPrefs.deterministicEmptyLimit() (default 2 == this).
 */
internal const val DETERMINISTIC_EMPTY_LIMIT = 2

/**
 * [fix/eof-stub-continuation] Max continuation rounds for EOF-truncated
 * streams (stream ended with NO finish_reason). The partial answer is KEPT
 * and a network-stub reminder asks the model to continue; past this cap the
 * loop gives up with a visible error instead of a silent mid-sentence stop.
 * [feat/runtime-limits-panel] runtime truth is
 * AgentRuntimeLimitsPrefs.eofStubContinues() (default 2 == this).
 */
internal const val MAX_EOF_STUB_CONTINUES = 2

/** Per-tool ring cap for ToolInputDelta snapshots (moved from ChatViewModel companion). */
internal const val TOOL_INPUT_CHUNK_RING_MAX = 10

/** Transient auto-retry backoff seconds (moved from ChatViewModel companion).
 *  [feat/runtime-limits-panel] superseded at runtime by
 *  AgentRuntimeLimitsPrefs.transientRetryDelaysSec() (count-tunable, same
 *  1/2/4s cadence at the default 3); kept as the documented default. */
internal val AUTO_RETRY_DELAYS_SEC = intArrayOf(1, 2, 4)

/**
 * [deep-audit-1016 6a] Re-derive a turn's request budget for the member that is
 * about to serve it — the ONE definition of "what a provider switch does to the
 * output budget and to the calibration".
 *
 * Why it must exist (field shape: HTTP 400 right after a fallback): a turn
 * captures its budget ONCE, before the retry loop, from the member that serves
 * the *first* attempt. Both fallback switches then replace
 * `loopState.currentProvider` mid-turn and re-enter the retry loop, which
 * re-uses that captured `maxTokens` — sized for the member we just left. A
 * group whose first entry is a 64K-output model falling back to a 4K-output
 * one therefore sent `max_tokens = 64000` to the 4K member, and the
 * calibration sample was charged to the OLD member's per-(provider, model)
 * key. [audit2 BUG-02] fixed exactly this for the turn-entry read; the two
 * fallback switches are the same defect one level down (this audit's 6a).
 *
 * Pure by construction — every provider-dependent value is an argument, so the
 * decision table is JVM-testable without an Android host
 * (see `RequestBudgetRecomputeTest`).
 */
internal object RequestBudgetRecompute {

    /** The recalculation for one provider: estimates + the output budget it implies. */
    data class Result(
        /** Provider-neutral estimate of the request input (before calibration). */
        val rawEstimateTokens: Long,
        /** [rawEstimateTokens] scaled by the provider's calibration coefficient. */
        val calibratedEstimateTokens: Long,
        val verdict: PreflightVerdict,
        /** Output budget to send with the request. */
        val maxTokens: Int,
    )

    /**
     * @param messages the exact message list the request will carry.
     * @param systemPrompt the same system prompt the request will carry.
     * @param tools the same tool schemas the request will carry.
     * @param calibrationRatioPercent the NEW provider's coefficient (100 = calibrated-equal).
     * @param windowTokens the NEW provider's effective context window (0/negative = unknown).
     * @param providerMaxTokens `dynamicMaxTokens(newProvider, …)` — what the member can emit.
     * @param outputFloor minimum output budget (`MIN_MAX_TOKENS_CONST`).
     */
    fun forProvider(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        tools: List<AgentToolDefinition>,
        calibrationRatioPercent: Long,
        windowTokens: Int,
        providerMaxTokens: Int,
        outputFloor: Int,
    ): Result {
        val rawEstimate = ContextEstimator.estimateRequestTokens(
            messages = messages,
            systemPrompt = systemPrompt,
            tools = tools,
        )
        val calibrated = rawEstimate * calibrationRatioPercent / 100
        val verdict = preflightContextBudget(
            window = windowTokens,
            estimatedInput = calibrated,
            requestedMaxTokens = providerMaxTokens,
            outputFloor = outputFloor,
        )
        val maxTokens = when (verdict.decision) {
            PreflightVerdict.Decision.PASS -> providerMaxTokens
            PreflightVerdict.Decision.SHRINK_OUTPUT ->
                verdict.suggestedMaxTokens ?: providerMaxTokens
            // Mid-turn we do NOT re-run the offload → auto-compact → hard-trim
            // pipeline (that is the turn entry's job and it needs the host).
            // Floor the output instead — the same terminal answer the pipeline
            // gives once the history has been shrunk as far as it goes; the
            // next turn entry re-runs the full pipeline while still over.
            PreflightVerdict.Decision.NEEDS_COMPACTION -> outputFloor
        }
        return Result(
            rawEstimateTokens = rawEstimate,
            calibratedEstimateTokens = calibrated,
            verdict = verdict,
            maxTokens = maxTokens,
        )
    }
}

/**
 * [deep-audit-1017 ISSUE-03] Per-turn visible-content predicate for the
 * `toolCalls.isEmpty()` tail of `runAgentLoop`.
 *
 * The tail used to ask this question of the RUN-scoped accumulators
 * (`loopState.accumulatedText` / `loopState.allToolBlocks`): both are
 * append-only for the whole run (cleared only by the queued-prompt interrupt
 * checkpoint), so once ANY earlier turn streamed text or dispatched a tool
 * they stayed non-empty forever and `if (!hasVisibleContent && finishedCleanly)`
 * could never fire again. That made the empty-turn recovery — including
 * `EmptyTurnRecovery.Plan.NudgeExistingPart(isToolResult = true)`, the arm the
 * reminder exists for ("model stalled after a tool result") — unreachable from
 * the engine: the run silently stopped on an empty reply with no nudge, no
 * retry, no hint.
 *
 * The question is per turn, so the inputs are per turn: `turnText` is this
 * turn's text (the seam-trimmed message body) and `turnBlocks` is this turn's
 * block slice (`allToolBlocks.drop(turnStartBlockIndex)`). Mirrors iOS
 * AIChatViewModel.swift, which asks the question of the current turn.
 *
 * Top-level + pure so the decision is directly unit-testable
 * (`TurnVisibleContentTest`), same as [RequestBudgetRecompute] above.
 */
internal object TurnVisibleContent {

    /** This turn showed the user something: streamed text or a rendered block. */
    fun hasVisibleContent(turnText: String, turnBlocks: List<AssistantBlock>): Boolean =
        turnText.isNotBlank() ||
            turnBlocks.any { it.kind == "tool_use" || (it.kind == "text" && it.content.isNotBlank()) }

    /**
     * [T-truncated-stream-retry] A truncated turn (EOF without a
     * `finish_reason`) is NOT a clean finish even when the stop reason is null
     * or "stop" — the EOF retry branch owns it, so the empty-turn path must not
     * swallow it with an error hint. Same for any non-terminal finish reason.
     */
    fun finishedCleanly(turnFinishReason: String?, turnTruncated: Boolean): Boolean =
        (turnFinishReason == null ||
            turnFinishReason == "stop" || turnFinishReason == "end_turn") &&
            !turnTruncated

    /** The empty-turn trigger: nothing visible this turn AND a clean finish. */
    fun isEmptyTurn(
        turnText: String,
        turnBlocks: List<AssistantBlock>,
        turnFinishReason: String?,
        turnTruncated: Boolean,
    ): Boolean = !hasVisibleContent(turnText, turnBlocks) &&
        finishedCleanly(turnFinishReason, turnTruncated)
}

/**
 * [deep-audit-1018 ISSUE-12c] Single source of truth for the stub-reminder
 * STACKING guard, shared by the two eofStubReminder injection sites in the
 * agent loop.
 *
 * The bug class (same family as the length-wall reminder guard): a second
 * consecutive error-shaped / EOF-truncated turn re-enters the continuation
 * branch. The previous iteration's reminder is still sitting in history —
 * behind THIS turn's assistant message — so a blind add piles reminder on
 * reminder: the model sees a stack of identical "continue where you left
 * off" instructions (context noise, repeated-anchor confusion) instead of
 * exactly one.
 *
 * The first stub branch (error-shaped, finish-path) shipped WITHOUT the
 * guard while its EOF-stub sibling had it — this object is the extracted,
 * testable form of that sibling's inline predicate. Judgement is shape-only:
 * a USER message whose single part is Text containing the eofStubReminder
 * marker string (`"cut off by a network error"`, from [eofStubReminder]'s
 * literal wording — greppable, and textually distinct from
 * [lengthWallReminder]'s "output token limit" wording so the two reminder
 * families never cross-match).
 */
internal object EofStubReminderStackGuard {

    /** Marker unique to eofStubReminder (vs lengthWallReminder). */
    internal const val REMINDER_MARKER = "cut off by a network error"

    /**
     * True when [prevTail] is a stale stub reminder from a previous
     * error-shaped/EOF continuation and must be removed before the new one is
     * appended. Callers pass `agentHistory[size - 2]` — the entry BEHIND this
     * turn's assistant message (the reminder always sits immediately behind
     * it, which is also why a null/short history is a clean no-match).
     */
    fun isStaleEofStubReminder(prevTail: LLMMessage?): Boolean =
        prevTail != null &&
            prevTail.role == LLMMessage.Role.USER &&
            prevTail.contentParts.size == 1 &&
            (prevTail.contentParts.first() as? AgentContentPart.Text)?.text
                ?.contains(REMINDER_MARKER) == true
}

/**
 * [deep-audit-1019 ISSUE-02] Pure transition table for the tool-block status a
 * dispatch publishes, for the three-pass tool dispatch in
 * [AgentLoopEngine.runAgentLoop].
 *
 * Pass 1 decides whether a queued call may run at all (same-turn dedupe /
 * truncation guard / loop detector / preflight); Pass 2 hands the survivors to
 * `host.executeTool`. The PENDING → RUNNING flip belongs to Pass 2 — the moment
 * a call is really dispatched — because until then the call is still QUEUED.
 * Publishing it in Pass 1 (where it used to live, above BOTH refusal gates)
 * broke two things at once:
 *
 *  * a call the loop detector refused with CRITICAL had already been shown as
 *    RUNNING, and that refusal path edited the block in memory only — so the
 *    card kept its spinner until the turn's next bulk publish, while every
 *    sibling refusal path (dedupe / truncation / preflight) publishes its flip;
 *  * the sequential branch's "waiting for previous tool(s)" cue is guarded on
 *    PENDING, and Pass 1 had already flipped every queued call, so the guard
 *    could never match — the cue was dead code.
 *
 * Unit-test expectations for these functions are written literals, never derived
 * from them, so a mutation cannot move an expectation along with it and stay
 * green.
 */
internal object ToolPassTransitionPolicy {

    /** What a call queued behind an earlier one shows while the head still runs. */
    const val QUEUE_CUE_TEXT = "⏳ Waiting for previous tool(s) to finish…"

    /**
     * Indices, within a SEQUENTIAL batch, of the calls that announce they are
     * queued behind an earlier one. The head starts as soon as the batch does
     * (no cue for it) and a single-call batch has nobody to wait for.
     */
    fun sequentialQueueCueIndices(pendingCount: Int): List<Int> =
        if (pendingCount > 1) (1 until pendingCount).toList() else emptyList()

    /**
     * The status a block takes when the execution pass dispatches it, given the
     * status it has as the pass reaches it — or null to leave it untouched.
     *
     * PENDING is the only state a dispatch may consume: anything else is either
     * already published as RUNNING (the parallel branch flips its whole batch up
     * front) or a terminal refusal Pass 1 published, which a dispatch must never
     * resurrect into a spinner.
     */
    fun statusAtDispatch(current: ToolBlockStatus?): ToolBlockStatus? =
        if (current == ToolBlockStatus.PENDING) ToolBlockStatus.RUNNING else null

    /**
     * The content a block carries the moment the execution pass flips it to
     * RUNNING: [QUEUE_CUE_TEXT] is dispatch bookkeeping, not tool output, and the
     * finalisation pass keeps `max(existingContent, resultContent)` **by length** —
     * a leftover 38-character cue would out-length a short result and then be
     * shown, and persisted, as that tool's output. Anything else (the empty
     * content a freshly streamed call carries, or live output a tool has already
     * written into its own block) is preserved.
     */
    fun contentAtDispatchFlip(current: String, queueCue: String): String =
        if (current == queueCue) "" else current
}

internal class AgentLoopEngine(
    private val host: AgentLoopHost,
    private val traceObserver: ChatAgentTraceObserver,
) {
    private companion object {
        private const val TAG_STREAM = "ChatVMStream"
        private const val TAG = "ChatViewModel"

        /** [fix/prewindow-budget-1015] 预检输出下限（= ChatViewModel.MIN_MAX_TOKENS）。 */
        private const val MIN_MAX_TOKENS_CONST = 1024

        /** [fix/prewindow-budget-1015] 校准百分比换算（ratio 存 100×便于 Long 运算）。 */
        private const val CALIB_PCT = 100L

        /**
         * [fix/prewindow-budget-1015] per-(providerType, baseUrl, model) 校准
         * 系数。0 未校准 / 100 已校准且一致 / >100 provider 口径更大。估算
         * 只乘一次，避免每消息重复读 prefs。
         */
        private fun calibrationRatioFor(provider: LLMProvider): Long {
            val inst = provider.instanceContext ?: return CALIB_PCT
            val key = ContextCalibration.keyFor(
                providerType = inst.providerType.name,
                baseUrl = inst.customBaseURL ?: "",
                modelId = provider.model.id,
            )
            return (ContextCalibration.ratio(key) * CALIB_PCT).toLong().coerceAtLeast(CALIB_PCT)
        }

        /**
         * [fix/prewindow-budget-1015] 当前 provider 的校准 key；instanceContext
         * 缺失（mock provider / 非-offload 路径）返回 null —— 调用方跳过
         * 失效检查与校准回填，行为退回旧世界。
         */
        private fun contextKeyOf(provider: LLMProvider): String? {
            val inst = provider.instanceContext ?: return null
            return ContextCalibration.keyFor(
                providerType = inst.providerType.name,
                baseUrl = inst.customBaseURL ?: "",
                modelId = provider.model.id,
            )
        }

    }

    /** Verbatim lift of ChatViewModel.unwrapFlowException (used by the retry path). */
    private fun unwrapFlowExceptionImpl(e: Throwable): Throwable {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is com.rikkaminis.app.data.model.LLMError) return cause
            cause = cause.cause
        }
        return e
    }

    /**
     * [T-android-thinking-delta-main-thread-throttle] Flush a throttled thinking
     * tail so the reasoning block is never left frozen mid-sentence.
     *
     * WHY: the ThinkingDelta branch now skips its UI push while inside the
     * `textDeltaThrottleMs` window. The next flush would normally come from a
     * later thinking delta, but the stream can transition away from thinking at
     * any moment (text starts, a tool call starts, or the stream just ends). In
     * those cases no further thinking delta arrives, so without this the block
     * would keep the content it had at the last tick and the user would watch a
     * truncated reasoning panel for the rest of the turn.
     *
     * This mirrors the existing T154 pre-tool-use flush for text ("first push the
     * latest accumulated text *unthrottled* so the text block freezes at its
     * complete value").
     *
     * Cheap: `block.content` is compared by reference first, so a turn whose
     * thinking block was already up to date costs one field read.
     */
    private suspend fun flushThinkingTailIfPending(
        loopState: AgentLoopState,
        turn: Int,
        turnThinking: StringBuilder,
        turnTextSb: StringBuilder,
    ) {
        if (turnThinking.isEmpty()) return
        val latest = turnThinking.toString()
        val thinkIdx = loopState.allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
        if (thinkIdx >= 0 && loopState.allToolBlocks[thinkIdx].content == latest) return
        if (thinkIdx < 0) {
            // No block yet: the whole thinking phase fit inside one throttle
            // window, so no delta ever published. Create it here rather than
            // bailing out — otherwise a short reasoning phase would render no
            // panel at all (a regression vs the pre-throttle behaviour, where
            // the first delta always published because lastThinkingUiUpdateMs
            // is reset to 0 and System.currentTimeMillis() is far larger than
            // any throttle tier).
            loopState.allToolBlocks.add(AssistantBlock(
                id = "thinking_$turn",
                kind = "thinking",
                content = latest,
                toolTitle = "Thinking",
                // [T-thinking-duration] Stamped so finishThinkingBlock() can
                // freeze the phase duration for the header. startTimeMs is a
                // rough "first published delta" here (this path only runs when
                // the throttle swallowed the whole phase), which is why the
                // header falls back to the char count when the value is 0.
                startTimeMs = System.currentTimeMillis(),
            ))
        } else {
            loopState.allToolBlocks[thinkIdx] = loopState.allToolBlocks[thinkIdx].copy(content = latest)
        }
        loopState.lastThinkingUiUpdateMs = System.currentTimeMillis()
        withContext(Dispatchers.Main) {
            host.updateAssistantMessage(
                loopState.assistantId,
                loopState.accumulatedText + turnTextSb.toString(),
                true,
                loopState.allToolBlocks,
            )
        }
    }

    /**
     * [T-thinking-duration] Mark this turn's thinking block finished and freeze
     * its duration — the thinking-phase counterpart of the tool pills, which
     * stamp `startTimeMs` at block creation and `durationMs` when execution
     * ends. Called from the two places the phase can end (text starts, a tool
     * call starts); before this the same two-site `copy(toolStatus = SUCCESS)`
     * was inlined in both, which is exactly how one site drifts from the other.
     *
     * Idempotent: the guard leaves an already-finished block untouched, so the
     * second caller cannot overwrite the first duration with a later stamp.
     */
    private fun finishThinkingBlock(loopState: AgentLoopState, turn: Int) {
        val thinkIdx = loopState.allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
        if (thinkIdx < 0) return
        val block = loopState.allToolBlocks[thinkIdx]
        if (block.toolStatus == ToolBlockStatus.SUCCESS) return
        loopState.allToolBlocks[thinkIdx] = block.copy(
            toolStatus = ToolBlockStatus.SUCCESS,
            durationMs = if (block.startTimeMs > 0L) {
                (System.currentTimeMillis() - block.startTimeMs).coerceAtLeast(0L)
            } else {
                0L
            },
        )
    }

    /**
     * §24a Zero the five per-stream throttle clocks so the next stream's
     * first delta publishes immediately instead of coalescing against the
     * previous stream's baseline.
     *
     * Extracted (change-ladder level 2) because three sites needed the same
     * five assignments and only two had them: the retry path and the normal
     * completion path reset all five, while the provider-fallback path reset
     * none — so the first delta after a fallback could be swallowed by a
     * stale gate and the reply visibly stalled at its head. Keeping one copy
     * is what stops a fourth site from drifting the same way.
     *
     * Values are the [AgentLoopState] declaration defaults (`0L` / `0`), and
     * `System.currentTimeMillis() - 0` exceeds every throttle tier, so a zeroed
     * clock always opens the gate.
     */
    private fun resetStreamThrottle(loopState: AgentLoopState) {
        loopState.lastUiUpdateMs = 0L
        loopState.lastFlushedLen = 0
        loopState.lastThinkingUiUpdateMs = 0L
        loopState.lastFileToolInputMs = 0L
        loopState.lastOtherToolInputMs = 0L
    }

    /** Verbatim lift of ChatViewModel.runAgentLoop entry (FE-5 route C step 3). */
    internal suspend fun runAgentLoop(
        provider: LLMProvider,
        systemPrompt: String?,
        fallbackProviders: List<FallbackCandidate> = emptyList(),
        fallbackStrategy: com.rikkaminis.app.data.model.FallbackStrategy = com.rikkaminis.app.data.model.FallbackStrategy.default,
    ) {
        AppLogger.info(TAG_STREAM, "runAgentLoop ENTER provider=${provider.javaClass.simpleName} historySize=${host.agentHistory.size}")
        // T9: start a fresh trace for this run. The file is captured once so
        // every event of the run lands in the same JSONL file; the loop's
        // per-turn / per-tool hooks below append to it.
        val traceStartMs = System.currentTimeMillis()
        // T7-A: 本轮 run 的观察上下文 —— runId + advisory 预算。
        // runId 先取局部 UUID（T7-B 接 SessionSlotController 后改为槽位 runId）；
        // 预算只做观察（consume 并记录，不阻断），T7-C 再启用 enforced。
        val runId = java.util.UUID.randomUUID().toString()
        traceObserver.activeRunId = runId
        // [feat/runtime-limits-panel] 预算上限改读用户可调的 AgentRuntimeLimitsPrefs
        // （Settings → Agent Runtime → Runtime Limits）。每轮 run 入口读取一次：
        // 改动在下一条消息生效，进行中的 run 维持启动时的预算（预算中途变更
        // 会让剩余量记账失去基准）。未 prime 的 JVM 测试路径回落到与 T7_*_DEFAULT
        // 相同的默认值，行为不变。
        val runtimeLimits = com.rikkaminis.app.data.AgentRuntimeLimitsPrefs
        // [feat/scheduled-tasks-l0] A scheduled run registers a per-session
        // ceiling (AgentRunBudgetCeilings) right before dispatching. The run's
        // budget is the STRICTER of the user's runtime limits and that ceiling
        // (min semantics, guardrail §5.1). deadline fields hold absolute
        // monotonic instants, so the ceiling's wall-clock duration converts as
        // now + min(user deadline, ceiling deadline) — brief B3 conversion.
        // Token enforcement note: the engine never calls consumeEstimatedTokens
        // today, so maxEstimatedTokens is snapshot-only either way; it is still
        // carried here so the cap becomes active the moment enforcement lands.
        val ceiling = AgentRunBudgetCeilings.lookup(host.activeSessionId)
        val userDeadlineMs = runtimeLimits.runDeadlineMinutes() * 60L * 1000L
        val observeBudget = AgentExecutionBudget(
            startedAtMonotonicMs = SystemClock.elapsedRealtime(),
            deadlineMonotonicMs = SystemClock.elapsedRealtime() +
                (if (ceiling != null) minOf(userDeadlineMs, ceiling.deadlineMs) else userDeadlineMs),
            maxTurns = runtimeLimits.maxTurns().let { user ->
                if (ceiling != null) minOf(user, ceiling.maxTurns) else user
            },
            maxProviderAttempts = runtimeLimits.maxProviderAttempts(),
            maxToolCalls = runtimeLimits.maxToolCalls(),
            maxShellCommands = runtimeLimits.maxShellCommands(),
            maxCompactionCalls = runtimeLimits.maxCompactionCalls(),
            maxConcurrentTools = runtimeLimits.maxConcurrentTools(),
            maxEstimatedTokens = ceiling?.maxEstimatedTokens, // token 计数不稳定，观察期不强制
            monotonicClock = { SystemClock.elapsedRealtime() },
        )
        traceObserver.activeRunBudget = observeBudget
        traceObserver.traceRunFile = traceObserver.newTraceFile(host.activeSessionId)
        traceObserver.releaseSessionId = host.activeSessionId
        traceObserver.activeTraceTurn = -1
        traceObserver.agentTraceRecorder.beginRun(
            runId = runId,
            sessionId = host.activeSessionId,
            provider = provider.javaClass.simpleName,
            prompt = host.agentHistory.lastOrNull { it.role == LLMMessage.Role.USER && it.content.isNotBlank() }?.content.orEmpty(),
            providerCount = fallbackProviders.size + 1,
            toolCount = host.agentTools.size,
            initialBudgetJson = t7InitialBudgetJson(observeBudget),
        )
        // T7-A: 状态机观察 —— run 开始（Idle → Preparing）
        traceObserver.t7ObservedPhase = ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.PREPARING)
        traceObserver.agentTraceRecorder.stateTransition(
            from = ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.IDLE),
            to = ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.PREPARING),
            reason = "RunStarted",
        )
        // T7-D: 旁路验证 —— RunStarted 事件
        // TF-G P1-3 fix: the reducer state machine MUST be initialised BEFORE
        // issuing RunStarted, or traceObserver.t7Reduce() no-ops (traceObserver.t7ReducerState==null → the
        // leading `?: return`) and the FIRST event is silently dropped. Then
        // every later event (ProviderAttemptStarted / ToolStarted / …) hits a
        // fresh IDLE reducer that has never seen RunStarted → "requires
        // RunStarted first" → spammy REJECTED in normal production paths.
        traceObserver.t7ReducerState = AgentRunState.initial()
        traceObserver.t7Reduce(AgentRunEvent.RunStarted(runId))
        // T7-B: session slot lease 观察 —— streamJob 在进入 runAgentLoop 前
        // 已经成功 acquireSlot；此处登记 lease（trace 侧），语义是
        // "run 持有会话并发槽位"。释放统一在 traceObserver.t7EndRun(finalize) 发出，
        // 保证任何终态（正常/取消/异常）都有对应的 release 事件。
        traceObserver.t7ResourceAcquire(
            resourceType = AgentTraceRecorder.RESOURCE_SESSION_SLOT,
            resourceId = host.activeSessionId,
            leaseToken = "slot-$runId",
        )
        // [T-android-queued-message-interrupt-on-toolclose] `loopState.assistantId` is
        // normally a single message id for the whole agent loop (iOS-parity:
        // multiple tool/text turns folded into one bubble). It is reassigned
        // ONLY when a queued mid-loop prompt is injected as a new turn: the
        // just-finished bubble is sealed and a fresh loopState.assistantId starts so the
        // queued user message renders BETWEEN them. `loopState.allToolBlocks` and
        // `loopState.accumulatedText` are also reset at that point so the new bubble
        // starts empty and the persistence slice continues to cover only the current
        // bubble's unpersisted blocks (both the run-scoped unpersisted start and the
        // per-turn turn-start index are reset to 0 right after the clear).
        val loopState = AgentLoopState(
            currentProvider = provider,
            remainingFallbacks = fallbackProviders.toMutableList(),
            fallbackReasons = mutableListOf(),
        )
        loopState.assistantId = "assistant_${System.currentTimeMillis()}"

        // [T-android-stream-flush-review] Restore the placeholder-bubble append
        // lost in the FE-5 route C extraction (be7d3a5). The pre-extraction loop
        // created an empty assistant message (isStreaming=true,
        // isAwaitingModelResponse=true) on Main BEFORE the turn loop, so the
        // "Minis is thinking" indicator shows during the first-request gap AND
        // the streaming side-channel has a canonical message id to overlay onto.
        // Without it the live reply never renders (only appears after a cold
        // reload re-builds the transcript from DB).
        host.addAssistantPlaceholder(loopState.assistantId, host.thinkingLevel)

        // T7-D: 终态 reducer 状态机入口已在 RunStarted 前初始化（见上）；
        // 此处不再重复 init —— 重复 `AgentRunState.initial()` 会重置已经把
        // RunStarted 消费掉的 reducer 回 IDLE，导致后续事件再次 REJECTED。

        try {
        // [feat/runtime-limits-panel] 循环上界从 prefs 读（默认 256）。取一次局部
        // 值保证整个 run 使用同一边界（与预算快照同理由：run 内一致性）。
        // [audit-1015 BUG-03D] 与 run 实际边界同源：observeBudget.maxTurns 已按
        // scheduled-task ceiling 钳制（min(user, ceiling.maxTurns)，见上方
        // AgentExecutionBudget 构造），run 内 t7ConsumeAndTrace→consumeTurn
        // 也按它拒绝。循环上界改用同一值后，两条停机路径（for 上界耗尽 /
        // 预算拒绝）永远指向同一个数字，退出横幅与 finalizeAtTurnLimit /
        // finalizeBudgetStop 引用的 MAX_AGENT_TURNS 不再是裸 prefs 值
        // （ceiling=30 的定时任务 run 退出时不会误报 "256 turns"）。
        // 无 ceiling 时两者相等，行为不变。循环边界一致性：for(turn in 0
        // until N) 恰好 consumeTurn N 次，全部 Allowed —— 上界不会比预算更
        // 松也不会更紧。
        val maxTurnsThisRun = observeBudget.maxTurns
        // [audit3 F-02] LABELED loop — do not drop the `turnLoop@`.
        // Two execution-budget gates live INSIDE this turn body's retry loop
        // (`while (!collectDone)` further down): the deadline gate and the
        // provider-attempt gate. Both set `t7BudgetStopReason` and then exit —
        // but a bare `break` there only left the *retry* loop, so the turn fell
        // through to the "the turn's stream completed without error" tail:
        // it recorded `RouteOutcome.Success` for the very member whose attempt
        // budget had just refused it (clearing that member's cooldown / circuit
        // state, i.e. marking a throttled provider healthy), appended an empty
        // ASSISTANT entry to agentHistory, and kept burning turns against a
        // reducer already parked in FINALIZING (field log 2026-09-07: "3 empty
        // turns burned in 4ms against a refused provider-attempt gate").
        // `break@turnLoop` makes a budget stop exit the RUN, exactly like the
        // two turn-entry gates just below (deadline / turn_limit), which sit at
        // for-body depth and were therefore already correct.
        // Machine-checked by scripts/scan/budget_stop_break_guard.py: a `break`
        // inside a nested loop whose branch sets t7BudgetStopReason must carry
        // the `@turnLoop` label.
        turnLoop@ for (turn in 0 until maxTurnsThisRun) {
            // T7-C: deadline 到达后不发新 provider/tool 请求 —— turn 循环入口检查。
            // 中断标记后走统一 finalize（BudgetExhausted 不是静默失败）。
            if (traceObserver.activeRunBudget?.isExpired() == true) {
                traceObserver.t7BudgetStopReason = "deadline_reached"
                traceObserver.t7State(
                    traceObserver.t7ObservedPhase ?: ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.EXECUTING_TOOLS),
                    ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.FINALIZING),
                    "DeadlineReached",
                )
                // T7-D: 旁路验证 —— deadline 到达
                traceObserver.t7Reduce(AgentRunEvent.DeadlineReached())
                break
            }
            // Sanitize history before each API call (mirrors iOS pre-API validation)
            host.sanitizeAgentHistory()

            // T9: per-turn trace hook
            val turnStartMs = System.currentTimeMillis()
            traceObserver.activeTraceTurn = turn
            traceObserver.agentTraceRecorder.turnStart(turn)
            // T7-A: 每轮消耗 turn 预算（advisory 观察，不阻断）
            // T7-C: turn 计数耗尽 → 中断本轮 run
            if (!traceObserver.t7ConsumeAndTrace(AgentTraceRecorder.DIMENSION_TURNS) { it.consumeTurn() }) {
                traceObserver.t7BudgetStopReason = "turn_limit"
                // T7-D: 旁路验证 —— 计数耗尽进入收尾
                traceObserver.t7Reduce(AgentRunEvent.ProcessInterrupted("budget_exhausted(turn_limit)"))
                traceObserver.t7State(
                    traceObserver.t7ObservedPhase ?: ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.EXECUTING_TOOLS),
                    ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.FINALIZING),
                    "BudgetExhausted(turn_limit)",
                )
                break
            }

            // Context window management: offload large tool outputs in older
            // messages to disk when the policy threshold for this model's
            // context window is crossed. Stubs in host.agentHistory still tell the
            // model where to file_read the original content. Mirrors iOS
            // AIChatViewModel.swift:4549.
            // [T-anthropic-context-window] Use contextWindowTokens (heuristic-
            // backed) instead of the raw nullable field, so offload triggers at
            // the correct fraction for heuristic-only Claude/Gemini models (1M)
            // rather than never firing when contextWindow is unset.
            // [T-context-window-live-read] Live read per loop turn — a stale
            // snapshot inside a long-running agent turn is exactly the iOS
            // fcc22b66 item-3 bug.
            host.effectiveContextWindowTokens()?.takeIf { it > 0 }?.let { window ->
                // [fix/offload-stale-token-compact] When the offload pass
                // actually shrunk the history, it returns the post-offload
                // estimate (same accounting basis as lastContextTokens); use
                // that for the compact + hard-trim decisions below instead of
                // the pre-offload figure. 2026-09-25 real-session evidence:
                // two runs offloaded down to 117470/250000 and 91107/200000 —
                // both already BELOW their compact lines (162457 / 131639) —
                // yet still triggered a summary wait (92 s / 38 s) because
                // the decisions ran on the stale 166806 / 147380. Null (no
                // offload happened) falls back to the previous behavior.
                val postOffloadTokens = host.offloadContextIfNeeded(
                    contextWindow = window,
                    lastContextTokens = loopState.lastContextTokens,
                )
                val decisionTokens = postOffloadTokens ?: loopState.lastContextTokens
                // [T-auto-compact-in-loop] Before falling back to the hard trim
                // (which drops the oldest turns verbatim and inserts a jarring
                // "trimmed N messages" line mid-answer), try to summarise the
                // old turns into a `<context-summary>` instead. Summarising
                // preserves semantic continuity; the hard trim remains the
                // last-resort structural shrink only when a compact can't fire
                // (still-compacting / debounced / tail too small / at the hard
                // ceiling). This is what makes "context reached the limit"
                // feel like a clean fold instead of an abrupt cut while the
                // model is mid-task.
                val compacted = host.maybeAutoCompactInLoop(
                    contextWindow = window,
                    lastContextTokens = decisionTokens,
                )
                // [fix/diff-audit-0904-F3] When auto-compact just fired, SKIP the
                // trim this turn. Compact does NOT shrink agentHistory (it only
                // writes the marker; effectiveAgentHistory does the summary+tail
                // projection at request time), so trimContextHistoryWindow would
                // still see baseTokens > budget with the SAME stale
                // lastContextTokens and drop the oldest turns — the very turns
                // the just-written marker anchors on. Losing the anchor makes
                // effectiveAgentHistory degrade to full history (summary silently
                // discarded), and next turn re-compacts + re-trims in a loop,
                // burning one summary API call per turn. The hard cap is still
                // honored: effectiveAgentHistory's summary+tail projection is what
                // is actually sent, and the next turn's Usage chunk refreshes
                // lastContextTokens so a genuinely over-budget history still
                // trims on the next iteration.
                // [fix/compact-anchor-resolution] `compacted` now means the
                // compact ACTUALLY folded a range (see
                // ChatContextWindowExt.maybeAutoCompactInLoop) — not merely that
                // one was requested. A compact that early-returns
                // ("already compacted" / no anchor / empty range) leaves the
                // history untouched, so the reasoning above (skip the trim to
                // keep the marker's anchor alive) does not apply: nothing is
                // preserving anything, and skipping the trim would let the
                // context grow on every turn with no fallback left.
                if (!compacted) {
                    host.trimContextHistoryWindow(
                        contextWindow = window,
                        lastContextTokens = decisionTokens,
                    )
                } else {
                    AppLogger.info(TAG_STREAM, "auto-compact folded old turns; skipping hard trim this turn (anchor preserved)")
                }

                // [fix/compact-stale-context-tokens-1010] Both rewrites above
                // change what the NEXT request actually carries — the offload
                // pass replaces tool results with disk stubs (postOffloadTokens
                // != null ⇒ it did free parts) and the auto-compact fold
                // replaces whole turns with a summary — yet
                // `loopState.lastContextTokens` still describes the PRE-rewrite
                // request. Two consumers read it within seconds and both fail
                // in the same direction:
                //   * dynamicMaxTokens() computes `remaining = window − input`
                //     from it, so a pre-rewrite figure at/over the window pins
                //     the output budget at MIN_MAX_TOKENS; and
                //   * isContextExhausted() reads the same figure (via
                //     _lastTurnContextTokens) to refuse the empty-length retry
                //     with "context is at/over the window ceiling".
                // Field evidence 2026-10-10 23:22:30 (session efbb18b6): the
                // rescue compact had just folded 24 entries (anchor 328→365) yet
                // the next request went out with input=467879 vs window=220000
                // → maxTokens=1024 → the model spent the entire budget on
                // reasoning, returned finish=length with an EMPTY body, and the
                // retry gate re-read the same stale figure and gave up — the run
                // died silently ~2 min later (no reply, no error). The
                // offload-only turn just before it (23:22:07, input figure
                // already over window, no compact) survived only because the
                // model happened to fit its tool calls inside 1024.
                // The figure is provider-reported (it is the only writer of that
                // field, :1023/:1029), so re-tuning the local char estimate
                // would not have prevented this. Clearing to 0 = "unknown,
                // re-estimate": offload falls back to a fresh estimate,
                // dynamicMaxTokens sends its full (still ceiling-bounded)
                // budget — the same request shape every first turn of a run
                // already sends today — and the retry gate stops claiming a
                // ceiling it cannot substantiate. The next Usage chunk (this
                // turn's stream) repopulates the figure from the provider.
                if (postOffloadTokens != null || compacted) {
                    loopState.lastContextTokens = 0
                    host.setLastTurnContextTokens(0)
                }
            }

            // Mark where this turn's blocks start in loopState.allToolBlocks so we can persist
            // only the NEW parts from this turn (not the full accumulated history).
            // Matches iOS's per-turn RawMessage persistence.
            val turnStartBlockIndex = loopState.allToolBlocks.size
            // T307: per-delta StringBuilder for the running turn text + the
            // currently-open trailing text block. `turnText` snapshots are
            // taken (via .toString()) at flush boundaries only, never per
            // delta. `currentTextBlockSb` mirrors the trailing text block's
            // growing content; reset to a fresh builder whenever a new text
            // block opens (which happens after a tool_use / thinking break
            // interrupts the text run).
            val turnTextSb = StringBuilder()
            var currentTextBlockSb: StringBuilder? = null
            // [T-android-tool-splits-reply-fix] Index (into loopState.allToolBlocks) of
            // THIS turn's single text block, used only when the provider's
            // streamed content is monolithic (streamTextIsMonolithic — OpenAI
            // Chat Completions). -1 until the turn's first text delta. The
            // merge scope is ONE streamed response: text arriving after a
            // tool RESULT round-trip belongs to the NEXT agent-loop turn,
            // which is a separate assistant message — so genuine
            // multi-segment turns are unaffected by the merge.
            var turnTextBlockIdx = -1
            // One-shot observability: future endpoints that adopt qwen-style
            // post-tool_calls content chunking show up in the log.
            var loggedPostToolTextMerge = false
            // Materialise the active text block's StringBuilder into its
            // immutable content. Monolithic mode targets the tracked turn
            // text block — which may NOT be the last block once trailing
            // content arrived after tool_calls; ordered mode keeps the
            // original trailing-block behaviour.
            /**
             * [fix/tool-call-copy-suppress] Fix 1 — the text a user / the next
             * request may see, with any restated tool call taken out. Remaining
             * markup is remembered on the loop state for the refill branch in
             * `toolCalls.isEmpty()` (Fix 2): the stripped text can no longer
             * answer "did this turn try to call a tool?".
             */
            fun visibleToolCallText(raw: String): String {
                if (raw.takeIf { it.contains("<") } == null) return raw
                val knownToools = host.agentTools.map { it.name }
                val seen = ToolCallResiduePolicy.firstResidue(raw, knownToools)
                if (seen == null) return raw
                loopState.toolCallResidueRaw = raw
                return ToolCallResiduePolicy.stripResidue(raw, knownToools)
            }

            fun materializeActiveTextBlock() {
                val sb = currentTextBlockSb ?: return
                val idx = if (loopState.currentProvider.streamTextIsMonolithic) turnTextBlockIdx else loopState.allToolBlocks.lastIndex
                if (idx >= 0 && idx < loopState.allToolBlocks.size && loopState.allToolBlocks[idx].kind == "text") {
                    loopState.allToolBlocks[idx] = loopState.allToolBlocks[idx].copy(content = visibleToolCallText(sb.toString()))
                }
            }
            val turnThinking = StringBuilder()
            // Opaque reasoning_content blob captured from the provider's
            // ReasoningContent stream chunk. When set (including empty string),
            // takes precedence over turnThinking concatenation so the exact
            // server-emitted value round-trips on the next request — DeepSeek V4
            // emits "" legitimately and fabricated text would be in-context-learned.
            var turnReasoningBlob: String? = null
            // T321: capture finish_reason from LLMStreamChunk.Finished so we can
            // log it at turn-end alongside the empty-turn warning.
            var turnFinishReason: String? = null
            var turnTruncated = false
            var lastUsage: LLMUsage? = null
            // [fix/prewindow-budget-1015] 2026-10-15 T321（session 16c39966）：
            // 同一份 ~4.9MB 历史在 deepseek 中转口径下记 191.9K，GLM 直连口径
            // 记 920.6K。旧链路此时 lastContextTokens 仍持 deepseek 的 191.9K
            // → dynamicMaxTokens 按 220K−191.9K 顶格放 128K 输出 →
            // 920,577+128,000 > 1,048,576 必然 400，且本地所有闸门（offload/
            // compact/trim）都看不见这次超支。预检用 provider 无关的本地估算
            // 在请求组装完成后做一次硬性结构检查，裁决三档：PASS / 收缩输出
            // 预算 / 禁止直发（走下方 compact+trim 管线再重估）。
            // [fix/prewindow-budget-1015] 反向漏洞：换到"记账更大"的端点后，旧
            // provider 的 lastContextTokens 偏大 → dynamicMaxTokens 把输出饿死
            // （空 length-wall）。读数来源 key 与当前 provider 不匹配 → 视为
            // 未知（0），本 turn 的输出预算按"无输入信息"分配；下一 turn 的
            // usage 回包会以新端点口径重建读数。必须在 dynamicMaxTokens 之前
            // 做 —— 清零晚了等于没清。
            // [audit2 BUG-02] Compare against the provider this turn will
            // ACTUALLY call (`loopState.currentProvider`), not the `provider`
            // formal parameter: the parameter is never re-assigned, so after
            // any fallback it names the member we already left. The writer
            // further down (`lastContextTokensSourceKey = contextKeyOf(
            // loopState.currentProvider)`) already uses the live one — using
            // the parameter here made every post-fallback turn compare two
            // different providers and wipe the reading.
            val currentCtxKey = contextKeyOf(loopState.currentProvider)
            if (loopState.lastContextTokensSourceKey != null &&
                loopState.lastContextTokensSourceKey != currentCtxKey
            ) {
                AppLogger.info(
                    TAG_STREAM,
                    "[Preflight] turn=$turn context reading from stale provider " +
                        "(${loopState.lastContextTokensSourceKey} → $currentCtxKey); treating as unknown",
                )
                loopState.lastContextTokens = 0
                host.setLastTurnContextTokens(0)
            }
            // [fix/prewindow-budget-1015] var（非 val）：NEEDS_COMPACTION 分支在
            // 下方管线里实际收缩历史后必须重捕——compact 只写 marker、trim 砍
            // agentHistory，都不触碰这里已捕获的快照实例；不重捕则本 turn 请求
            // 仍带全量未压缩历史 + 最小输出 → 仍然必然 400（该缺口会让
            // "裁剪后按最小输出重试"的承诺在本 turn 落空）。SHRINK_OUTPUT /
            // PASS 不进管线，值与 val 时代逐字一致。
            var preflightMessages = host.applyRequestImageBudget(host.effectiveAgentHistory())
            // [audit2 BUG-02] Window / output budget follow the provider that
            // will actually serve this turn, same reason as `currentCtxKey`.
            val preflightMaxTokens = host.dynamicMaxTokens(loopState.currentProvider, loopState.lastContextTokens)
            // rawEstimate：provider 无关的本地原始口径 —— 校准观察（流结束/
            // 400-teacher）只用它，否则校准学到"系数化的自己"，闭环坍缩到 1.0。
            // [fix/prewindow-budget-1015] var：NEEDS_COMPACTION 分支重捕消息后
            // 必须重算——400-teacher 与 usage 校准回填都拿它做 localEstimate，
            // 若仍持压缩前的估算，观测比值 = 压缩后 usage / 压缩前估算，
            // 系统性偏低且带内不可拒，会拖偏校准中位数。
            var rawEstimate = ContextEstimator.estimateRequestTokens(
                messages = preflightMessages,
                systemPrompt = systemPrompt,
                tools = host.agentTools,
            )
            // [audit2 BUG-02] Calibration is per (providerType, baseUrl, model)
            // — charging this turn the OLD member's coefficient after a
            // fallback both mis-estimates the window and re-observes samples
            // under the wrong key.
            var preflightEstimate = rawEstimate * calibrationRatioFor(loopState.currentProvider) / 100
            val preflightVerdict = preflightContextBudget(
                window = host.effectiveContextWindowTokens() ?: 0,
                estimatedInput = preflightEstimate,
                requestedMaxTokens = preflightMaxTokens,
                outputFloor = MIN_MAX_TOKENS_CONST,
            )
            AppLogger.info(
                TAG_STREAM,
                "[Preflight] turn=$turn ${preflightVerdict.toLogLine()} " +
                    "window=${host.effectiveContextWindowTokens() ?: 0} " +
                    "providerEst=${loopState.lastContextTokens} calib=${calibrationRatioFor(loopState.currentProvider)}",
            )
            var maxTokens = when (preflightVerdict.decision) {
                PreflightVerdict.Decision.SHRINK_OUTPUT -> preflightVerdict.suggestedMaxTokens ?: preflightMaxTokens
                PreflightVerdict.Decision.NEEDS_COMPACTION -> {
                    // 输入本身就装不下：先走既有的 offload → auto-compact →
                    // hard-trim 管线（和上方窗口管理同一段），然后用裁剪后的
                    // 历史重估一次；仍装不下 → 硬 trim 兜底后按最小输出发。
                    // 这个分支替代旧世界"发出必然 400"的死法。
                    // 读数用预检估算（loopState.lastContextTokens 可能刚被
                    // window-mgmt 清零，而预检估算恰恰是此刻唯一可信的压力
                    // 证据 —— 本地口径，与 provider 无关）。
                    val windowNow = host.effectiveContextWindowTokens() ?: 0
                    val pressureReading = preflightEstimate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val postOffload = host.offloadContextIfNeeded(
                        contextWindow = windowNow,
                        lastContextTokens = pressureReading,
                        force = true,
                    )
                    val compacted = host.maybeAutoCompactInLoop(
                        contextWindow = windowNow,
                        lastContextTokens = postOffload ?: pressureReading,
                    )
                    if (!compacted) {
                        host.trimContextHistoryWindow(
                            contextWindow = windowNow,
                            lastContextTokens = postOffload ?: pressureReading,
                        )
                    }
                    // 裁剪后按最小输出重试一次：trim 的预算本身按 window 收，
                    // 若裁后仍超（尾部太大），下一轮预检会再次禁发并走同样
                    // 管线 —— 不会死循环（每轮都实际收缩历史）。
                    // [fix/prewindow-budget-1015] 重捕请求消息：管线写入的
                    // compact marker（effectiveAgentHistory 在请求时做
                    // summary+tail 投影）与 trim 砍掉的历史，只有重新调用
                    // effectiveAgentHistory 才能取到 —— 上面 :643 捕获的快照
                    // 是管线前的投影，直接发出去等于压缩白跑（本 turn 仍 400）。
                    // 重捕后同步重算本地估算：400-teacher 与流结束的 usage
                    // 校准回填都以 rawEstimate 为 localEstimate，必须对应
                    // 实际发出的（压缩后）请求，否则观测比值系统性失真。
                    preflightMessages = host.applyRequestImageBudget(host.effectiveAgentHistory())
                    rawEstimate = ContextEstimator.estimateRequestTokens(
                        messages = preflightMessages,
                        systemPrompt = systemPrompt,
                        tools = host.agentTools,
                    )
                    preflightEstimate = rawEstimate * calibrationRatioFor(loopState.currentProvider) / 100
                    MIN_MAX_TOKENS_CONST
                }
                PreflightVerdict.Decision.PASS -> preflightMaxTokens
            }

            // [deep-audit-1016 6a] ONE definition, consumed by both fallback
            // switches below. A switch replaces the member that serves the next
            // attempt, so everything the preflight captured from the OLD member
            // has to be re-derived from the NEW one: the calibration key (its
            // per-(provider, model) coefficient) and the output budget
            // (`dynamicMaxTokens` reads the member's own output ceiling and the
            // now-updated window). Leaving `maxTokens` as captured is what sent
            // a 64K `max_tokens` to a 4K-output fallback member (HTTP 400).
            //
            // `preflightMessages` is deliberately NOT re-derived here: the image
            // budget is provider-independent and the var already holds the exact
            // list the next attempt will carry (it is what the request site
            // reads), so re-fetching the history mid-retry could only introduce
            // a divergence between the estimate and the bytes on the wire.
            fun rebudgetForSwitchedProvider(site: String) {
                // The member we left reported ITS input口径; carrying that
                // reading into the new member's window is the T321 shape. The
                // turn-entry gate treats a reading whose source key does not
                // match the current provider as unknown (0) — a mid-turn switch
                // must do the same, and it must happen BEFORE dynamicMaxTokens
                // sizes the output from it.
                loopState.lastContextTokens = 0
                host.setLastTurnContextTokens(0)
                val rebudget = RequestBudgetRecompute.forProvider(
                    messages = preflightMessages,
                    systemPrompt = systemPrompt,
                    tools = host.agentTools,
                    calibrationRatioPercent = calibrationRatioFor(loopState.currentProvider),
                    windowTokens = host.effectiveContextWindowTokens() ?: 0,
                    providerMaxTokens = host.dynamicMaxTokens(
                        loopState.currentProvider,
                        loopState.lastContextTokens,
                    ),
                    outputFloor = MIN_MAX_TOKENS_CONST,
                )
                // Re-derived from the same message list, so it stays the
                // invariant `rawEstimate ⇔ preflightMessages` the calibration
                // backfill and the 400-teacher both read.
                rawEstimate = rebudget.rawEstimateTokens
                preflightEstimate = rebudget.calibratedEstimateTokens
                maxTokens = rebudget.maxTokens
                AppLogger.info(
                    TAG_STREAM,
                    "[Preflight] re-budget after provider switch ($site) turn=$turn " +
                        "${rebudget.verdict.toLogLine()} " +
                        "window=${host.effectiveContextWindowTokens() ?: 0} " +
                        "providerEst=${loopState.lastContextTokens} " +
                        "calib=${calibrationRatioFor(loopState.currentProvider)} " +
                        "member=${loopState.currentProvider?.model?.id ?: "?"}",
                )
            }

            val toolCalls = mutableListOf<Triple<String, String, JSONObject>>() // id, name, args

            // [T-dedupe-toolcallid 03fbcbfd] Per-turn dedupe of tool_call_id.
            // Some upstream OpenAI-compatible gateways occasionally emit
            // multiple parallel tool_calls with the SAME id but different
            // name/args. Sending both back unchanged trips the receiver's
            // uniqueness check (HTTP 400 "duplicate tool_call_id"). Mirror
            // the iOS fix: the FIRST occurrence keeps the raw id, second
            // becomes "<id>-2", third "<id>-3", etc.
            //
            // Three pieces of state because Android routes ToolInputDelta
            // by chunk.id (iOS routes by name) and OpenAI emits ALL completes
            // together after finish_reason — so we can't drop the
            // "currently in-flight" map by the time completes arrive.
            //
            //   dedupeStartCounts    raw id → # ToolUseStart events seen
            //   dedupeCompleteCounts raw id → # ToolCallComplete events seen
            //   inFlightRenamedId    raw id → renamed id of the tool currently
            //                        streaming deltas (overwritten on each start)
            //
            // Start/complete ordering match: OpenAI streams emit tools in
            // `index` order at finish_reason, mirroring start order.
            val dedupeStartCounts = mutableMapOf<String, Int>()
            val dedupeCompleteCounts = mutableMapOf<String, Int>()
            val inFlightRenamedId = mutableMapOf<String, String>()
            fun dedupeToolStartId(raw: String): String {
                val n = (dedupeStartCounts[raw] ?: 0) + 1
                dedupeStartCounts[raw] = n
                val renamed = if (n == 1) raw else "$raw-$n"
                if (n > 1) {
                    AppLogger.warning(TAG_STREAM, "[ToolDedupe] duplicate tool_call id on stream start: '$raw' #$n -> renamed '$renamed'")
                }
                inFlightRenamedId[raw] = renamed
                return renamed
            }
            fun dedupeToolInputId(raw: String): String =
                inFlightRenamedId[raw] ?: raw
            fun dedupeToolCompleteId(raw: String): String {
                val n = (dedupeCompleteCounts[raw] ?: 0) + 1
                dedupeCompleteCounts[raw] = n
                return if (n == 1) raw else "$raw-$n"
            }

            // Stream the response — with auto-retry on transient errors, then fallback.
            // callbackFlow wraps throws into CancellationException(cause=LLMError),
            // so we catch at collect level and unwrap.
            var collectDone = false
            var retryAttempt = 0  // per-turn auto-retry counter (resets on each new turn)
            while (!collectDone) {
                // T7-C: deadline 到达后不发新 provider 请求
                if (traceObserver.activeRunBudget?.isExpired() == true) {
                    traceObserver.t7BudgetStopReason = "deadline_reached"
                    traceObserver.t7State(
                        traceObserver.t7ObservedPhase ?: ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.CALLING_MODEL),
                        ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.FINALIZING),
                        "DeadlineReached",
                    )
                    // T7-D: 旁路验证 —— deadline 到达
                    traceObserver.t7Reduce(AgentRunEvent.DeadlineReached())
                    // [audit3 F-02] `break@turnLoop`, not `break`: leave the RUN,
                    // not just this retry loop (see the labeled turn loop for
                    // the fall-through damage: bogus route Success + empty
                    // assistant entry + burned turns).
                    // Skipping the post-while fold (`accumulatedText +=
                    // turnTextSb`) loses nothing — this gate sits at the TOP of
                    // the while body, and every path that re-enters the while
                    // clears turnTextSb first (the retry path and the fallback
                    // path both call `turnTextSb.setLength(0)` before looping),
                    // so the buffer is empty here and the fold would be a no-op.
                    // Pinned by AgentLoopBudgetStopExitTest.
                    break@turnLoop
                }
                try {
                    // T7-A: provider attempt 开始（每次 retry/fallback 都会重新进入）
                    // T7-C: provider attempt 预算耗尽 → 不再尝试（不走 fallback）
                    if (!traceObserver.t7ConsumeAndTrace(AgentTraceRecorder.DIMENSION_PROVIDER_ATTEMPTS) { it.consumeProviderAttempt() }) {
                        traceObserver.t7BudgetStopReason = "provider_attempt_limit"
                        // T7-D: 旁路验证 —— 计数耗尽进入收尾
                        traceObserver.t7Reduce(AgentRunEvent.ProcessInterrupted("budget_exhausted(provider_attempts)"))
                        traceObserver.t7State(
                            traceObserver.t7ObservedPhase ?: ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.CALLING_MODEL),
                            ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.FINALIZING),
                            "BudgetExhausted(provider_attempts)",
                        )
                        collectDone = true
                        // [audit3 F-02] Same as the deadline gate above: exit the
                        // RUN. The while-exit assignment stays (it is the loop
                        // condition's state, and the labeled break leaves the
                        // loop either way) — the label is what stops the turn
                        // from running on to the success tail.
                        break@turnLoop
                    }
                    // T7-D: 旁路验证 —— ProviderAttemptStarted
                    traceObserver.t7Reduce(AgentRunEvent.ProviderAttemptStarted)
                    // [T-android-enhanced-cache] Stamp the per-turn Enhanced
                    // Cache flag onto the active provider here — the single
                    // choke point every turn passes through, regardless of how
                    // loopState.currentProvider was (re)assigned by the fallback loop.
                    // Non-Anthropic providers ignore it (cast fails silently).
                    (loopState.currentProvider as? com.rikkaminis.app.provider.anthropic.AnthropicProvider)
                        ?.enhancedCache = host.enhancedCacheEnabled
                    // Route through host.effectiveAgentHistory() so a populated
                    // [host.compactSummary] is prepended as a `<context-summary>`
                    // user message. Falls through to the raw host.agentHistory when
                    // no compact has happened, so the common path stays zero-copy.
                    // [stream-timing 2026-09-14] Consumer-side latency marks. The
                    // model call runs off-process (:modelservice) and its logcat
                    // never reaches the tailer, so these two lines are the only
                    // end-to-end timing the on-device log can carry. Cost: 2
                    // lines per turn (~0.1% of log volume).
                    val streamT0Ms = SystemClock.elapsedRealtime()
                    var streamStartedMs = -1L
                    var streamFirstContentMs = -1L
                    var streamChunkCount = 0
                    host.streamChatTurnOffloaded(
                        provider = loopState.currentProvider,
                        messages = preflightMessages,
                        systemPrompt = systemPrompt,
                        maxTokens = maxTokens,
                        temperature = null,
                        imageParts = emptyList(),
                        tools = host.agentTools,
                        // [T-thinking-effective-level] host.thinkingLevel is
                        // already the EFFECTIVE level (supportsReasoning + ceiling
                        // folded in by the host), so no guard belongs here. The
                        // old `if (host.currentModelSupportsReasoning) … else OFF`
                        // was a SECOND, independent answer to the same question —
                        // and the one that let the navbar badge disagree with the
                        // wire. One expression, one answer.
                        thinkingLevel = host.thinkingLevel,
                    ).collect { chunk ->
                // [stream-timing 2026-09-14] one-shot marks: Started (server
                // accepted the request) and the first user-visible content chunk.
                if (streamStartedMs < 0 && chunk is LLMStreamChunk.Started) {
                    streamStartedMs = SystemClock.elapsedRealtime() - streamT0Ms
                }
                if (streamFirstContentMs < 0 && (
                        chunk is LLMStreamChunk.Text ||
                        chunk is LLMStreamChunk.ThinkingDelta ||
                        chunk is LLMStreamChunk.ReasoningContent ||
                        chunk is LLMStreamChunk.ToolUseStart)) {
                    streamFirstContentMs = SystemClock.elapsedRealtime() - streamT0Ms
                    AppLogger.info(TAG_STREAM, "stream first-content after=${streamFirstContentMs}ms started=${streamStartedMs}ms")
                }
                streamChunkCount++
                when (chunk) {
                    is LLMStreamChunk.ThinkingDelta -> {
                        turnThinking.append(chunk.text)
                        // [T-android-thinking-delta-main-thread-throttle] Gate the
                        // UI push here, on the engine side, BEFORE any O(n) work —
                        // exactly like the sibling Text branch below does with
                        // textDeltaThrottleMs.
                        //
                        // Previously this branch had no gate at all, so every
                        // thinking delta (91.8% of all deltas; up to 1,052/s and
                        // 14,258 per turn on device) posted to the main Looper and
                        // ran an O(accumulated-text) Utf16Sanitizer.sanitize()
                        // inside updateAssistantMessage — before that function's
                        // own throttle gate could reject it. Main thread measured
                        // at 59.8% of one core => input-box lag.
                        val thinkNowMs = System.currentTimeMillis()
                        if (thinkNowMs - loopState.lastThinkingUiUpdateMs <
                            textDeltaThrottleMs(turnThinking.length)
                        ) {
                            // Skipped: the block content stays in `turnThinking`
                            // (append-only), and the throttle tail is flushed by
                            // the Text / ToolUseStart / stream-end paths below.
                            return@collect
                        }
                        loopState.lastThinkingUiUpdateMs = thinkNowMs
                        // Update thinking block in UI
                        val thinkIdx = loopState.allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                        if (thinkIdx < 0) {
                            loopState.allToolBlocks.add(AssistantBlock(
                                id = "thinking_$turn",
                                kind = "thinking",
                                content = turnThinking.toString(),
                                toolTitle = "Thinking",
                                // [T-thinking-duration] Phase start for the header
                                // duration; read the throttle's own timestamp so
                                // the stamp matches the delta that created it.
                                startTimeMs = thinkNowMs,
                            ))
                        } else {
                            loopState.allToolBlocks[thinkIdx] = loopState.allToolBlocks[thinkIdx].copy(content = turnThinking.toString())
                        }
                        withContext(Dispatchers.Main) {
                            host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText + turnTextSb.toString(), true, loopState.allToolBlocks)
                        }
                    }
                    is LLMStreamChunk.Text -> {
                        // Mark thinking block as done when text starts flowing
                        finishThinkingBlock(loopState, turn)
                        // [T-android-thinking-delta-main-thread-throttle] The
                        // thinking phase is over — drain any tail the throttle
                        // skipped so the reasoning panel shows the full text.
                        flushThinkingTailIfPending(loopState, turn, turnThinking, turnTextSb)
                        // T307: append-only on the StringBuilder; .toString()
                        // is taken once below at flush time, not per delta.
                        turnTextSb.append(chunk.text)
                        // [fix/tool-call-copy-suppress] Fix 1 on the turn
                        // accuмulator: every display flush below reads
                        // `accumulatedText + turnTextSb.toString()`, and the
                        // persised turn / the next request read it too. Scanned
                        // on a tag-bearing delta, on every ~300 new characters,
                        // and whenever the accuмulator shrank (new turn) — so a
                        // copy split across deltas is still taken out once its
                        // closing tag lands.
                        if (turnTextSb.length < loopState.residueScanAt ||
                            turnTextSb.length > loopState.residueScanAt + 300 ||
                            chunk.text.contains("<")) {
                            val visibleTurn = visibleToolCallText(turnTextSb.toString())
                            if (visibleTurn.length != turnTextSb.length) {
                                turnTextSb.setLength(0)
                                turnTextSb.append(visibleTurn)
                            }
                            loopState.residueScanAt = turnTextSb.length
                        }
                        // Append to the trailing text block — or open a new one if the last
                        // block isn't a text block (i.e. a tool call or thinking was in between).
                        // This preserves the chronological interleaving of text and tool calls
                        // across a single assistant turn. The block's `content` field stays
                        // immutable String — we keep a parallel StringBuilder for the active
                        // block and materialise via .toString() only on flush.
                        val lastIdx = loopState.allToolBlocks.lastIndex
                        val monolithic = loopState.currentProvider.streamTextIsMonolithic
                        val activeSb = if (monolithic && turnTextBlockIdx >= 0 && currentTextBlockSb != null) {
                            // [T-android-tool-splits-reply-fix] Chat Completions
                            // content is ONE string per response — a content
                            // delta arriving after tool_calls deltas (qwen
                            // chunking artifact) is still part of the same
                            // pre-tool sentence. Merge it back instead of
                            // fabricating a post-tool text block, which split
                            // sentences mid-word in the chat UI. Scope: this
                            // streamed response only (see turnTextBlockIdx).
                            if (!loggedPostToolTextMerge &&
                                loopState.allToolBlocks.subList(turnTextBlockIdx + 1, loopState.allToolBlocks.size).any { it.kind == "tool_use" }
                            ) {
                                loggedPostToolTextMerge = true
                                AppLogger.info(
                                    TAG_STREAM,
                                    "[T-android-tool-splits-reply-fix] post-tool_calls content delta merged into pre-tool text block (model=${loopState.currentProvider.model.id})",
                                )
                            }
                            currentTextBlockSb!!.append(chunk.text)
                            currentTextBlockSb!!
                        } else if (!monolithic && lastIdx >= 0 && loopState.allToolBlocks[lastIdx].kind == "text" && currentTextBlockSb != null) {
                            currentTextBlockSb!!.append(chunk.text)
                            currentTextBlockSb!!
                        } else {
                            // New text run — either first text after a tool_use/thinking
                            // break, or first text in this turn. Open a fresh block AND
                            // a fresh accumulator. The new block's content carries the
                            // first delta verbatim; subsequent deltas append to the SB.
                            val freshSb = StringBuilder(chunk.text)
                            currentTextBlockSb = freshSb
                            val block = AssistantBlock(
                                id = "text_${turn}_${loopState.allToolBlocks.size}_${loopState.blockSeq++}",
                                kind = "text",
                                content = chunk.text,
                            )
                            if (monolithic) {
                                // Single text block per response. If tool blocks
                                // already arrived (content-after-tool_calls
                                // chunking with no preface text), insert BEFORE
                                // the first tool block of this turn so the
                                // persisted order matches the canonical
                                // {content, tool_calls} message shape.
                                val firstToolIdx = (turnStartBlockIndex until loopState.allToolBlocks.size)
                                    .firstOrNull { loopState.allToolBlocks[it].kind == "tool_use" }
                                if (firstToolIdx != null) {
                                    loopState.allToolBlocks.add(firstToolIdx, block)
                                    turnTextBlockIdx = firstToolIdx
                                } else {
                                    loopState.allToolBlocks.add(block)
                                    turnTextBlockIdx = loopState.allToolBlocks.lastIndex
                                }
                            } else {
                                loopState.allToolBlocks.add(block)
                            }
                            freshSb
                        }
                        // T94 fix 2 + T256: tiered text-delta throttle. Mutate local
                        // state every delta (above) so block boundaries stay correct
                        // for ToolUseStart / ToolInputDelta which read loopState.allToolBlocks
                        // directly. Only push to _messages when the length-aware gate
                        // opens (or a newline lands during a short reply). Pending
                        // text lives in `loopState.pendingChunkSb` so the stream-end final
                        // flush at line ~3580 can drain it.
                        loopState.pendingChunkSb.append(chunk.text)
                        val len = turnTextSb.length
                        val unflushed = len - loopState.lastFlushedLen
                        val throttle = textDeltaThrottleMs(len)
                        val newlineFlush = len < 5_000 && chunk.text.contains('\n') && unflushed >= 50
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - loopState.lastUiUpdateMs >= throttle || newlineFlush) {
                            loopState.lastUiUpdateMs = nowMs
                            loopState.lastFlushedLen = len
                            loopState.pendingChunkSb.setLength(0)
                            // Materialise SB → String for both the active block's
                            // content (so Compose sees an immutable snapshot) and
                            // for the assistant message body. These are O(n) calls
                            // but happen at throttled cadence, not per delta.
                            // (activeSb === currentTextBlockSb by construction.)
                            materializeActiveTextBlock()
                            val turnSnap = turnTextSb.toString()
                            withContext(Dispatchers.Main) {
                                host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText + turnSnap, true, loopState.allToolBlocks)
                            }
                        }
                    }
                    is LLMStreamChunk.ToolUseStart -> {
                        // [T-dedupe-toolcallid] Rewrite duplicate id ASAP — the
                        // renamed value drives the AssistantBlock.id used by
                        // ToolCallComplete / ToolInputDelta lookups and ends
                        // up as the persisted tool_call_id on the next request.
                        val toolUseId = dedupeToolStartId(chunk.id)
                        android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolUseStart id=$toolUseId name=${chunk.name}")
                        // Mark thinking block as done when tool use starts
                        finishThinkingBlock(loopState, turn)
                        // [T-android-thinking-delta-main-thread-throttle] Same as
                        // the Text branch: the thinking phase ends here, so drain
                        // the throttled tail before the tool block is appended.
                        flushThinkingTailIfPending(loopState, turn, turnThinking, turnTextSb)
                        // T154: when the last few text deltas landed inside the 50ms throttle
                        // window, the UI hadn't yet been pushed with the trailing text — and
                        // adding the tool_use block before that push freezes the preceding
                        // text fragment in StreamingMarkdownText (its `messageIsStreaming`
                        // flag flips off the next layout pass) with chars chopped off the
                        // end. Mirror iOS AnthropicAgentProvider.swift Step 1 / Step 2:
                        // first push the latest accumulated text *unthrottled* so the text
                        // block freezes at its complete value, yield to let Compose render
                        // it, then add the tool_use block in a separate transaction. The
                        // pendingChunkText/loopState.lastUiUpdateMs reset mirrors the throttle path
                        // so the next text delta doesn't try to flush stale state.
                        if (turnTextSb.isNotEmpty() && loopState.pendingChunkSb.isNotEmpty()) {
                            loopState.pendingChunkSb.setLength(0)
                            loopState.lastUiUpdateMs = System.currentTimeMillis()
                            loopState.lastFlushedLen = turnTextSb.length
                            // T307: pre-tool-use flush also materialises the
                            // active text block + a turn-text snapshot.
                            materializeActiveTextBlock()
                            // [T-android-tool-splits-reply-fix] Ordered mode:
                            // the tool block breaks the text run, so the next
                            // text delta opens a new block. Monolithic mode
                            // keeps the accumulator alive — same-response
                            // content deltas arriving after tool_calls merge
                            // back into the pre-tool text block instead.
                            if (!loopState.currentProvider.streamTextIsMonolithic) {
                                currentTextBlockSb = null
                            }
                            val turnSnap = turnTextSb.toString()
                            withContext(Dispatchers.Main) {
                                host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText + turnSnap, true, loopState.allToolBlocks)
                            }
                            yield()
                        }
                        // T256 tier 2: force the next ToolInputDelta to flush
                        // immediately by zeroing both gate timestamps. iOS does the
                        // same in .startToolUse (AIChatViewModel.swift:6075-6116) so
                        // the user sees the pill name/title arrive without waiting
                        // out the 1s/200ms gate.
                        loopState.lastFileToolInputMs = 0L
                        loopState.lastOtherToolInputMs = 0L
                        // Guard: only add if not already present (prevent duplicate blocks from repeated ToolUseStart)
                        if (loopState.allToolBlocks.none { it.id == toolUseId }) {
                            loopState.allToolBlocks.add(AssistantBlock(
                                id = toolUseId,
                                kind = "tool_use",
                                toolName = chunk.name,
                                toolStatus = ToolBlockStatus.STREAMING,
                                toolTitle = friendlyToolTitle(chunk.name),
                                startTimeMs = System.currentTimeMillis(),
                            ))
                            withContext(Dispatchers.Main) {
                                host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText + turnTextSb.toString(), true, loopState.allToolBlocks)
                            }
                        }
                    }
                    is LLMStreamChunk.ToolInputDelta -> {
                        // [T-dedupe-toolcallid] Translate to the currently-in-flight
                        // renamed id so the per-tool ring + block lookup match
                        // the block that ToolUseStart created.
                        val toolInputId = dedupeToolInputId(chunk.id)
                        // [log-diet 2026-09-14] This per-delta line ran at ~85k
                        // lines/day (~60% of the on-device log by line count,
                        // ~half its bytes) while carrying almost no post-hoc
                        // signal — release summaries live in ToolCallComplete
                        // (`len=`) and the consumer-side stream timing lines.
                        // Debug builds keep the raw trace.
                        if (com.rikkaminis.app.BuildConfig.DEBUG) {
                            android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolInputDelta id=$toolInputId len=${chunk.accumulated.length}")
                        }
                        // Maintain a per-tool ring of the most recent `accumulated`
                        // snapshots so the preflight validator below can dump them
                        // when an empty/invalid call is detected. Cheap (single
                        // append + bounded trim) and lives outside any throttle so
                        // every delta lands here.
                        val ring = loopState.toolInputChunkRings.getOrPut(toolInputId) { mutableListOf() }
                        ring.add(chunk.accumulated)
                        if (ring.size > TOOL_INPUT_CHUNK_RING_MAX) {
                            // Drop from the front so we keep the most recent N.
                            ring.subList(0, ring.size - TOOL_INPUT_CHUNK_RING_MAX).clear()
                        }
                        val idx = loopState.allToolBlocks.indexOfFirst { it.id == toolInputId }
                        if (idx >= 0) {
                            val prev = loopState.allToolBlocks[idx]
                            // Stream-parse partial JSON (mirrors iOS extractPartialStringValue):
                            //   - pull "tool_title" out early so the pill header updates live
                            //   - keep the raw accumulated JSON in toolArgs so detail-sheet
                            //     renderers (extractShellCommand, args.optString("command"), …)
                            //     can pick up fields as they appear.
                            //   - leave content empty during streaming (real output arrives
                            //     after ToolCallComplete).
                            val partialTitle = extractPartialStringValue("tool_title", chunk.accumulated)
                            val liveTitle = when {
                                !partialTitle.isNullOrEmpty() -> partialTitle
                                prev.toolTitle.isNotEmpty() && prev.toolTitle != prev.toolName -> prev.toolTitle
                                else -> friendlyToolTitle(prev.toolName)
                            }
                            loopState.allToolBlocks[idx] = prev.copy(
                                toolArgs = chunk.accumulated,
                                toolTitle = liveTitle,
                                content = "",
                            )
                            // T256 tier 2: gate UI push by tool kind. file_write/file_edit
                            // pump multi-KB JSON through the SSE — pushing every delta
                            // pegs the UI thread for no readable benefit (the user can't
                            // skim a partial JSON blob anyway). Mirrors iOS
                            // AIChatViewModel.swift:6229-6259 (1s file / 200ms other).
                            // Local state above is mutated unconditionally so when the
                            // gate eventually opens — or ToolCallComplete force-flushes —
                            // the latest accumulated args are pushed.
                            val toolName = prev.toolName
                            val isHeavyFileTool = toolName == "file_write" || toolName == "file_edit"
                            val gateMs = if (isHeavyFileTool) 1_000L else 200L
                            val nowMs = System.currentTimeMillis()
                            val lastTs = if (isHeavyFileTool) loopState.lastFileToolInputMs else loopState.lastOtherToolInputMs
                            if (nowMs - lastTs >= gateMs) {
                                if (isHeavyFileTool) loopState.lastFileToolInputMs = nowMs
                                else loopState.lastOtherToolInputMs = nowMs
                                withContext(Dispatchers.Main) {
                                    host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText + turnTextSb.toString(), true, loopState.allToolBlocks)
                                }
                            }
                        }
                    }
                    is LLMStreamChunk.ToolCallComplete -> {
                        // [T-dedupe-toolcallid] Rewrite duplicate id so the
                        // persisted tool_calls list, the block lookup, and
                        // the downstream tool-result join all key on the
                        // same value (matches the rename applied at start).
                        val toolCompleteId = dedupeToolCompleteId(chunk.id)
                        // [log-diet 2026-09-14] `len=` preserves the full-size
                        // signal that the now-debug-gated per-delta trace used
                        // to carry.
                        val argsJson = chunk.args.toString()
                        android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolCallComplete id=$toolCompleteId name=${chunk.name} args=${argsJson.take(300)} len=${argsJson.length}")
                        toolCalls.add(Triple(toolCompleteId, chunk.name, chunk.args))
                        val idx = loopState.allToolBlocks.indexOfFirst { it.id == toolCompleteId }
                        if (idx >= 0) {
                            val providedTitle = chunk.args.optString("tool_title", "").takeIf { it.isNotEmpty() }
                            val title = providedTitle ?: friendlyToolTitle(chunk.name)
                            // PENDING — JSON params fully received, waiting for execution
                            // dispatcher to invoke the tool. host.executeTool() flips to RUNNING.
                            loopState.allToolBlocks[idx] = loopState.allToolBlocks[idx].copy(
                                toolStatus = ToolBlockStatus.PENDING,
                                toolTitle = title,
                                toolArgs = chunk.args.toString(),
                                content = "", // Clear ToolInputDelta JSON accumulation before real output arrives
                            )
                            withContext(Dispatchers.Main) {
                                host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText + turnTextSb.toString(), true, loopState.allToolBlocks)
                            }
                        }
                    }
                    is LLMStreamChunk.Usage -> {
                        // [FIX-1 / F-190] Anthropic sends a COMPLETE usage on
                        // message_start and a PARTIAL one (output side only) on
                        // message_delta. Unconditional assignment let the partial
                        // one overwrite the complete one, so inputTokens /
                        // latestContextTokens landed as 0 every turn — which made
                        // contextNearFull permanently false (see usedCtx below) and
                        // the empty-reply hint always take the generic branch.
                        // Field-wise merge: a zero from the later chunk means
                        // "not reported", not "reported as zero".
                        lastUsage = lastUsage?.let { prev ->
                            LLMUsage(
                                inputTokens = if (chunk.usage.inputTokens != 0) chunk.usage.inputTokens else prev.inputTokens,
                                outputTokens = chunk.usage.outputTokens,
                                cacheCreationInputTokens = chunk.usage.cacheCreationInputTokens ?: prev.cacheCreationInputTokens,
                                cacheReadInputTokens = chunk.usage.cacheReadInputTokens ?: prev.cacheReadInputTokens,
                                latestContextTokens = if (chunk.usage.latestContextTokens != 0) chunk.usage.latestContextTokens else prev.latestContextTokens,
                            )
                        } ?: chunk.usage
                        // [T-adaptive-compact-reserve] Snapshot BEFORE the update:
                        // the difference between two consecutive Usage readings is
                        // exactly one turn's growth (this turn's answer + its tool
                        // results), which is what the auto-compact reserve must be
                        // sized against. A `prev` of 0 means this is the run's
                        // first reading (loopState starts empty), and the delta
                        // would be the whole context — not a growth step — so it
                        // is deliberately not reported.
                        val prevContextTokens = loopState.lastContextTokens
                        // Update context token count for next turn's host.dynamicMaxTokens()
                        // and publish to _lastTurnContextTokens so the ContextPolicy
                        // gate in [host.checkContextBeforeSend] can see the latest pressure
                        // without a DB round-trip.
                        if (chunk.usage.latestContextTokens > 0) {
                            loopState.lastContextTokens = chunk.usage.latestContextTokens
                        } else if (chunk.usage.inputTokens > 0) {
                            // Fallback when a provider omits latestContextTokens: inputTokens is
                            // now fresh-only (cached portion subtracted in the parser), so add the
                            // cache back to recover the true context size — otherwise a high
                            // cache-hit turn would under-report context pressure and skip offload.
                            loopState.lastContextTokens = chunk.usage.inputTokens +
                                (chunk.usage.cacheReadInputTokens ?: 0) +
                                (chunk.usage.cacheCreationInputTokens ?: 0)
                        }
                        if (loopState.lastContextTokens > 0) {
                            loopState.lastContextTokensSourceKey = contextKeyOf(loopState.currentProvider)
                            host.setLastTurnContextTokens(loopState.lastContextTokens)
                            if (prevContextTokens > 0 && loopState.lastContextTokens > prevContextTokens) {
                                host.recordContextGrowth(loopState.lastContextTokens - prevContextTokens)
                            }
                        }
                    }
                    is LLMStreamChunk.ReasoningContent -> {
                        // Opaque reasoning blob (DeepSeek/Kimi reasoning_content) — record
                        // on the last assistant turn so it echoes back on the next request.
                        // Empty strings are preserved (DeepSeek V4 emits "" on non-thinking
                        // turns and we must round-trip exactly that). No live UI surface;
                        // the thinking panel is driven by ThinkingDelta events above.
                        turnReasoningBlob = chunk.content
                    }
                    is LLMStreamChunk.Finished -> {
                        // T321: stash for empty-turn diagnostic logging below.
                        turnFinishReason = chunk.stopReason
                        turnTruncated = chunk.truncated
                    }
                    is LLMStreamChunk.Started -> { /* no-op */ }
                    is LLMStreamChunk.QueueStatus -> {
                        // [feat/provider-exec-concurrency] Queue-position frame
                        // from the worker: forward to the host so the UI can
                        // show "queued behind N" instead of an indistinguishable
                        // silent wait while another session holds the slots.
                        withContext(Dispatchers.Main) {
                            host.onQueueStatus(chunk.waiting)
                        }
                    }
                    is LLMStreamChunk.MediaAttachment -> {
                        // [T-codex-gpt-image2-oauth-android] Model-generated
                        // media (gpt-image-2 image). Inline chat display is out
                        // of scope for this change — the image is delivered via
                        // sendMessage→LLMResponse.mediaAttachments for the
                        // minis-model-use CLI path. No-op here so the chat agent
                        // loop compiles with the new chunk variant.
                    }
                }
                    }  // end collect
                    // [stream-timing 2026-09-14] Summary for the consumer-visible
                    // stream: `dur` = request → last chunk, `firstContent` = the
                    // user-visible latency (first Text / Thinking / ToolStart).
                    AppLogger.info(TAG_STREAM, "stream done dur=${SystemClock.elapsedRealtime() - streamT0Ms}ms chunks=$streamChunkCount started=${streamStartedMs}ms firstContent=${streamFirstContentMs}ms")
                    // T94 fix 2: flush any text that landed in the throttle
                    // window after the last UI tick. The retry-rollback /
                    // turn-finalize paths below assume _messages reflects all
                    // accumulated text-deltas, so we must not leave the last
                    // 0-50ms worth on the floor.
                    if (loopState.pendingChunkSb.isNotEmpty()) {
                        loopState.pendingChunkSb.setLength(0)
                        // T307: also flush the active text block's pending
                        // tail and snapshot turnText.
                        materializeActiveTextBlock()
                        val turnSnap = turnTextSb.toString()
                        withContext(Dispatchers.Main) {
                            host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText + turnSnap, true, loopState.allToolBlocks)
                        }
                    }
                    // [T-android-thinking-delta-main-thread-throttle] A stream can
                    // end while still in the thinking phase (reasoning-only turn,
                    // or the user stopped it mid-thought). Drain the throttled
                    // thinking tail so the persisted reasoning matches what the
                    // model actually produced. Unconditional: the pending-text
                    // flush above is keyed on pendingChunkSb, which is empty on a
                    // pure-thinking turn.
                    flushThinkingTailIfPending(loopState, turn, turnThinking, turnTextSb)
                    // [T-thinking-duration] Freeze the phase when the stream ends
                    // *inside* the thinking phase: a reasoning-only turn (no text
                    // and no tool call to hand the phase over to) or a stop
                    // mid-thought. Neither Text nor ToolUseStart fires on those
                    // paths, so without this the row keeps toolStatus != SUCCESS
                    // and shows no duration, unlike the tool pills which always
                    // stamp one. Idempotent: the SUCCESS guard leaves a block the
                    // two sibling hooks already finished — and its earlier, correct
                    // stamp — untouched.
                    finishThinkingBlock(loopState, turn)
                    // T256: reset throttle bookkeeping for the next turn so the
                    // first delta of the next assistant message fires immediately
                    // rather than coalescing against this turn's stale baseline.
                    resetStreamThrottle(loopState)
                    collectDone = true
                    // T7-A: 观察 —— provider 尝试成功（T5 ProviderAttemptFinished(SUCCESS)）
                    traceObserver.t7State(ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.CALLING_MODEL), ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.EXECUTING_TOOLS), "ProviderAttemptFinished(SUCCESS)")
                    // T7-D: 旁路验证 —— provider 成功
                    traceObserver.t7Reduce(AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS))
                    // Stream completed without error — clear any lingering retry UI state.
                    if (host.autoRetryAttempt != 0 || host.autoRetryCountdown != 0) {
                        host.resetAutoRetry()
                    }
                } catch (e: Exception) {
                    if (e is CancellationException && e.cause == null) throw e  // real job cancellation
                    val actual = unwrapFlowExceptionImpl(e)
                    // [fix/prewindow-budget-1015] 400-teacher：上下文超限 400 的
                    // 正文带着该端点自己报的精确 input_tokens（"your prompt
                    // contains at least 920577 input tokens"）。预检的本地估算
                    // 对同一请求算过 —— 这个比值是教科书级校准样本（带错误
                    // 历史的估算必然偏低，正好把累计偏差一次学进去）。
                    if (actual is com.rikkaminis.app.data.model.LLMError.ProviderError &&
                        isContextTooLargeError(actual) && preflightEstimate > 0
                    ) {
                        Regex("""(\d{3,})\s*input tokens""").find(actual.detail)?.groupValues?.get(1)
                            ?.toLongOrNull()?.let { reportedInput ->
                                val inst = loopState.currentProvider.instanceContext
                                if (inst != null) {
                                    val ck = ContextCalibration.keyFor(
                                        providerType = inst.providerType.name,
                                        baseUrl = inst.customBaseURL ?: "",
                                        modelId = loopState.currentProvider.model.id,
                                    )
                                    val obs = ContextCalibration.observe(ck, reportedInput, rawEstimate)
                                    AppLogger.warning(
                                        TAG_STREAM,
                                        "[Calibration] $ck 400-teacher observed=$obs reported=$reportedInput " +
                                            "localEst=$preflightEstimate ratio=${ContextCalibration.ratio(ck)}",
                                    )
                                }
                            }
                    }
                    val isRateLimit = actual is com.rikkaminis.app.data.model.LLMError.RateLimited
                    val is5xx = actual is com.rikkaminis.app.data.model.LLMError.ProviderError &&
                        actual.detail.contains(Regex("\\b[5][0-9]{2}\\b"))
                    // Auto-retry on transient network/5xx/transient errors on the SAME provider
                    // before considering a fallback (mirrors iOS streamWithAutoRetry).
                    // Rate limits are provider-level signals that should trigger fallback immediately,
                    // not retry on the same provider.
                    // TF-B: a worker that died BEFORE emitting any chunk
                    // (ModelWorkerDiedException/ModelStreamErrorException, hadChunks=false)
                    // is safe to retry through the gateway — nothing was sent to the user yet.
                    // A worker that died mid-stream (hadChunks=true) must NOT be re-sent:
                    // it falls through to the fatal path below (no auto-retry, no fallback
                    // re-send) so the user never gets a duplicate answer.
                    // 2026-08-24 (diag/first-chunk-timeout): the original implementation
                    // only matched ModelWorkerDiedException — ModelStreamErrorException
                    // (which first_chunk_timeout throws, and the stream-error line path in
                    // ChatStreamOffloadHandler rethrows) fell through the transient check
                    // and was misclassified as FATAL: no same-model retry, no fallback
                    // (unless strategy=always). With a proxy route whose first chunk
                    // legitimately takes 20-60s, every 30s guard hit surfaced as a hard
                    // user-visible error. Both 0-chunk types are equally safe to retry —
                    // **as long as the worker did not classify the failure itself**.
                    // §17 [fix/permanent-4xx-retried-as-transient]: that condition used
                    // to be missing, so a permanent `[400] model not found` (worker
                    // stamps kind=provider; hadChunks=false because the relay rejects
                    // before any chunk) was retried 3x on the same member — 1+2+4s
                    // of dead wait before a fallback that always succeeded. The
                    // judgement now lives in one place (decideStreamFailure): a stamped
                    // kind speaks for itself, and the 0-chunk heuristic only covers
                    // UNSTAMPED failures (legacy worker / real worker death / timeout).
                    // [fix/stream-error-silent-recovery] A mid-stream failure
                    // (hadChunks=true) used to fall through to the fatal path:
                    // the worker→client error line carried no type info, so the
                    // engine couldn't tell a proxy blip from a fatal error and
                    // surfaced "Stream error" + a manual retry button. The
                    // worker now stamps a machine-readable kind on the error
                    // line; classify() maps it. AUTO_RETRY is safe even with
                    // partial output on screen — rollbackTurnBlocksTo (below)
                    // rewinds the half-delivered text before the resend, so no
                    // duplicate is possible. FALLBACK_NOW skips same-provider
                    // retries (rate-limit / bad-key members can't self-heal).
                    // Null kind (legacy worker) → FATAL, byte-identical to the
                    // old behavior.
                    // §17: one entry point decides recovery for a failure that
                    // crossed the worker boundary (worker death / worker-reported
                    // stream error). It reads the stamped kind when there is one,
                    // and only falls back to the 0-chunk heuristic when there is not.
                    val streamFailure =
                        (actual as? com.rikkaminis.app.sandbox.offload.ModelExecutionStreamException)
                            ?.let {
                                ChatStreamErrorPolicy.decideStreamFailure(
                                    hasChunks = it.hadChunks,
                                    kind = (it as? com.rikkaminis.app.sandbox.offload.ModelStreamErrorException)?.kind,
                                )
                            }
                    val streamErrorAutoRetry = streamFailure?.isTransient == true
                    val streamErrorFallbackNow = streamFailure?.fallbackNow == true
                    val isTransient = actual is com.rikkaminis.app.data.model.LLMError.NetworkError ||
                        actual is com.rikkaminis.app.data.model.LLMError.TransientError ||
                        is5xx ||
                        streamErrorAutoRetry
                    // [T-fallback-retry-original] Restored original behavior: all members
                    // (including fallback chain members) get bounded retries on transient
                    // errors. This absorbs intermittent stream resets that the fallback
                    // member would otherwise immediately expose as a "all fallbacks
                    // exhausted" banner. See 3b3a12f for the revert context.
                    // [feat/runtime-limits-panel] 重试上限/延迟改读 prefs（默认 3 次
                    // 延迟 1/2/4s，与旧 AUTO_RETRY_DELAYS_SEC 行为一致）。
                    val retryDelays = com.rikkaminis.app.data.AgentRuntimeLimitsPrefs.transientRetryDelaysSec()
                    if (isTransient && retryAttempt < retryDelays.size) {
                        val delaySec = retryDelays[retryAttempt]
                        retryAttempt += 1
                        val errDesc = actual.message ?: actual.javaClass.simpleName
                        // §17: which judgement fired rides along in the log — a
                        // misclassification must be settle-able from the log alone,
                        // not re-derived from source (that re-derivation is exactly what
                        // this backlog item cost).
                        val retryBasis = streamFailure?.basis ?: "llm-error:${actual.javaClass.simpleName}"
                        Log.w("ChatViewModel", "🔁 Transient error on ${loopState.currentProvider.model.displayName}, retry $retryAttempt/${retryDelays.size} in ${delaySec}s: $errDesc [$retryBasis]")
                        // T7-A: 观察 —— provider 瞬态失败（T5 ProviderAttemptFinished(TRANSIENT_FAILURE)）
                        traceObserver.t7State(ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.CALLING_MODEL), ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.RETRYING), "ProviderAttemptFinished(TRANSIENT_FAILURE)")
                        // T7-D: 旁路验证 —— provider 瞬态失败
                        traceObserver.t7Reduce(AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.TRANSIENT_FAILURE))
                        // T7-A: 观察 —— provider 瞬态失败决定重试（T3 语义：provider
                        // 调用视为 READ_ONLY 级，透明重试在预算内允许）
                        traceObserver.t7Retry(
                            operationType = "provider_attempt",
                            operationName = loopState.currentProvider.model.displayName,
                            safetyLevel = AgentTraceRecorder.SAFETY_READ_ONLY,
                            outcome = AgentTraceRecorder.OUTCOME_SAFE_TO_RETRY,
                            reason = errDesc,
                            attempt = retryAttempt,
                            maxAttempts = retryDelays.size,
                            willRetry = true,
                        )
                        // [T-error-no-permanent-scars] The transient banner shows a
                        // human summary too ("Connection failed — retrying 1/3…"),
                        // not the raw "stream was reset: CANCEL" text. The log line
                        // above keeps the full detail for debugging.
                        val errSummary = (actual as? com.rikkaminis.app.data.model.LLMError)?.userMessage
                            ?: actual.message?.takeIf { it.isNotBlank() }
                            ?: actual.javaClass.simpleName
                        withContext(Dispatchers.Main) {
                            host.setAutoRetry(retryAttempt, 0)
                            // Show the error inline on the streaming assistant message during countdown.
                            // Keeps isStreaming=true so the UI doesn't tear down the streaming state.
                            host.setTransientInlineError("$errSummary — retrying ($retryAttempt/${retryDelays.size})…")
                        }
                        try {
                            // [absorb-network-pack: P0-2-offline-retry-hold]
                            // Wait for connectivity ONCE per retry attempt, before
                            // the countdown starts, so the attempt is not burned
                            // into a dead network (Wi-Fi↔cellular swap / elevator).
                            // The visible countdown simply does not start until
                            // the link is back.
                            //
                            // Placement is deliberate: `awaitConnected` is bounded
                            // at 90s per call, so calling it once per attempt caps
                            // the stall at 90s. Calling it inside the per-second
                            // loop below would re-arm that 90s budget on every
                            // second and stretch a single attempt to
                            // `delaySec × 90s` (10.5 min on the default ladder),
                            // which is exactly the failure path the bound exists
                            // to avoid. The probe fails-open when no NetworkMonitor
                            // was ever started.
                            com.rikkaminis.app.network.OfflineRetryHold.awaitConnected {
                                com.rikkaminis.app.network.NetworkMonitor.activeMonitor
                                    ?.let { it.status.value == com.rikkaminis.app.network.NetworkMonitor.NetworkStatus.DISCONNECTED }
                                    ?: false
                            }
                            for (remaining in delaySec downTo 1) {
                                host.setAutoRetryCountdown(remaining)
                                kotlinx.coroutines.delay(1000)
                            }
                        } finally {
                            host.setAutoRetryCountdown(0)
                        }
                        // Clear inline error so the retry attempt can start cleanly.
                        withContext(Dispatchers.Main) {
                            host.clearInlineError()
                        }
                        // Roll back partial blocks from the failed stream attempt so the retried
                        // stream's deltas don't double-append on top of stale content. Previous
                        // turns (everything before turnStartBlockIndex) are preserved.
                        // RC3: shared production helper — the retry path and the fallback path
                        // must apply the same "no fake blocks survive a failed attempt" semantic,
                        // or they drift (historically fallback missed this; see F-T01-01).
                        val hadPartialBlocks = rollbackTurnBlocksTo(loopState.allToolBlocks, turnStartBlockIndex)
                        if (hadPartialBlocks) {
                            // [T-android-fallback-text-rewind] Keep this turn's
                            // already-streamed text on screen across the rollback.
                            // `loopState.accumulatedText` only folds in `turnTextSb` after the
                            // while loop completes successfully, so passing bare
                            // `loopState.accumulatedText` here would visibly rewind everything
                            // the user already read this turn. The next attempt
                            // streams into a fresh `turnTextSb` and re-publishes
                            // `loopState.accumulatedText + newTurnText`, so this transient
                            // value is overwritten cleanly (no duplication).
                            withContext(Dispatchers.Main) {
                                host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText + turnTextSb.toString(), true, loopState.allToolBlocks)
                            }
                        }
                        // T307: SB-based per-turn accumulators reset.
                        turnTextSb.setLength(0)
                        currentTextBlockSb = null
                        // [T-android-tool-splits-reply-fix] The tracked turn
                        // text block was just rolled back with the rest of
                        // this turn's partial blocks.
                        turnTextBlockIdx = -1
                        turnThinking.clear()
                        toolCalls.clear()
                        // T94 fix 2 + T256: throttle bookkeeping is per-stream
                        // attempt; reset alongside the partial-block rollback so
                        // the next attempt's first delta fires through immediately
                        // rather than coalescing against stale baselines.
                        loopState.pendingChunkSb.setLength(0)
                        resetStreamThrottle(loopState)
                        // T7-A: 观察 —— 决定重试（T5 RetryRequested：RETRYING → CALLING_MODEL）
                        traceObserver.t7State(ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.RETRYING), ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.CALLING_MODEL), "RetryRequested(provider_attempt)")
                        // T7-D: 旁路验证 —— 重试请求
                        traceObserver.t7Reduce(AgentRunEvent.RetryRequested("transient"))
                        continue  // retry on same provider
                    }
                    // Retries exhausted or non-retryable — proceed to fallback / throw.
                    host.resetAutoRetry()
                    // [T-android-timeout-while-running] Clear any transient
                    // inline error from the prior retry attempts before we
                    // either fall back (loop continues with a new provider)
                    // or throw (terminal host.setInlineError below re-sets it
                    // with the final non-retryable message). Without this,
                    // a transient banner from the previous attempt could
                    // linger as the new provider starts streaming — the
                    // host.updateAssistantMessage(isStreaming=true) defense
                    // catches it on the next delta, but clearing here
                    // makes the intent explicit and avoids a one-frame
                    // flash of the stale banner.
                    withContext(Dispatchers.Main) { host.clearInlineError() }
                    // Fallback classification mirrors iOS and the model layer's
                    // LLMError.isFallbackable contract: anything that says "this
                    // member can't help" falls back to the next member of the
                    // group immediately — rate limits (429), bad/expired API keys
                    // (401) and provider errors (4xx/5xx, incl. per-provider 403
                    // quota). `always` additionally falls back on every error.
                    // [fix/stream-error-silent-recovery] TYPED stream errors
                    // mirror the LLMError semantics they were classified from:
                    //  - rate_limited / invalid_key / provider kinds skip
                    //    same-provider retries entirely (retrying a member
                    //    that answered "you can't use me" is wasted latency);
                    //  - network / transient kinds retry first, then fall
                    //    back after exhaustion — exactly how a NetworkError
                    //    flows through this catch block.
                    val shouldFallback =
                        streamErrorFallbackNow ||
                        streamErrorAutoRetry ||
                        (actual as? com.rikkaminis.app.data.model.LLMError)?.isFallbackable == true ||
                        fallbackStrategy == com.rikkaminis.app.data.model.FallbackStrategy.always
                    // T7-A: 观察 —— provider 尝试失败需 fallback（T5 ProviderAttemptFinished(FALLBACK_FAILURE)）
                    if (shouldFallback) {
                        traceObserver.t7State(ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.CALLING_MODEL), ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.FALLING_BACK), "ProviderAttemptFinished(FALLBACK_FAILURE)")
                        // T7-D: 旁路验证 —— provider fallback 失败
                        traceObserver.t7Reduce(AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.FALLBACK_FAILURE))
                    } else {
                        // 非 fallback 错误（如终止性错误）—— 观察为致命失败
                        traceObserver.t7State(ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.CALLING_MODEL), ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.FINALIZING), "ProviderAttemptFinished(FATAL_FAILURE)")
                        // T7-D: 旁路验证 —— provider 致命失败
                        traceObserver.t7Reduce(AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.FATAL_FAILURE))
                    }
                    val next = if (shouldFallback) loopState.remainingFallbacks.removeFirstOrNull() else null
                    if (next != null) {
                        val reason = when {
                            isRateLimit -> "Rate limited"
                            actual is com.rikkaminis.app.data.model.LLMError.ProviderError -> actual.detail
                            else -> actual.message ?: "Error"
                        }
                        // [T-android-model-indicator-flash-on-endpoint-retry]
                        // Same-model recovery is a TRANSPARENT retry, not a real
                        // model switch. A model group can hold several entries
                        // for the SAME modelId behind different provider
                        // instances/endpoints (e.g. deepseek-v4-flash via a dead
                        // hub.oaifree.com key + via api.deepseek.com). When the
                        // first 401s, group-fallback moves to the next instance —
                        // same modelId, different endpoint — which should recover
                        // silently. Only flash the model capsule when the
                        // resolved modelId ACTUALLY changes; an endpoint/instance-
                        // only change must not surface to the UI.
                        val isRealModelChange = next.provider.model.id != loopState.currentProvider.model.id
                        loopState.fallbackReasons.add("⚠️ ${loopState.currentProvider.model.displayName}: $reason")
                        Log.i(TAG, "🔀 $reason on ${loopState.currentProvider.model.displayName}, switching to ${next.provider.model.displayName} (realModelChange=$isRealModelChange)")
                        // T7-A: 观察 —— fallback 选中新成员（T5 FallbackSelected 语义）
                        traceObserver.t7State(ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.FALLING_BACK), ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.CALLING_MODEL), "FallbackSelected(${next.provider.model.displayName})")
                        // T7-D: 旁路验证 —— fallback 选中
                        traceObserver.t7Reduce(AgentRunEvent.FallbackSelected(fallbackMemberIndex = next.entryId.hashCode()))
                        traceObserver.t7Retry(
                            operationType = "provider_fallback",
                            operationName = next.provider.model.displayName,
                            safetyLevel = AgentTraceRecorder.SAFETY_READ_ONLY,
                            outcome = AgentTraceRecorder.OUTCOME_SAFE_TO_RETRY,
                            reason = reason,
                            attempt = null,
                            maxAttempts = null,
                            willRetry = true,
                        )
                        loopState.currentProvider = next.provider
                        // [audit2 BUG-03] Give the fallback member its own full
                        // transient-retry allowance. `retryAttempt` is
                        // per-turn and survives the member switch, so without
                        // this the new member inherits a budget the failed
                        // member already burned and a single transient blip on
                        // it skipped straight to the next fallback. The
                        // content-filter fallback path below already did this;
                        // this path is the one that was missing it (only two
                        // fallback switch points exist — see the report).
                        retryAttempt = 0
                        // Also update class-level provider so the next sendMessage() starts from here
                        host.setCurrentProvider(next.provider)
                        // Update top bar model info + active entry. (For a same-
                        // model endpoint recovery these are no-ops on the visible
                        // model name, but still keep activeEntryId / provider name
                        // in sync with the instance we actually used.)
                        host.noteModelNames(modelName = loopState.currentProvider.model.displayName, providerName = null, entryId = null)
                        // [P0-x-fallback-entry-precision] Resolve the group ENTRY
                        // we actually fell back to by its id (carried on the
                        // candidate), NOT by re-searching `modelEntries` with
                        // `it.model.id == loopState.currentProvider.model.id`. A group can
                        // hold several entries for the same modelId behind
                        // different instances; a modelId-only find returns the
                        // FIRST match, which may be a different instance than the
                        // one we are now using — corrupting _activeEntryId /
                        // _providerName (model picker highlight, provider label)
                        // and host.effectiveContextWindowTokens (context window of the
                        // wrong instance).
                        val newEntry = host.activeConfigModelEntries.find {
                            it.id == next.entryId
                        }
                        // [T-recovery] Capture the ENTRY we are falling back OFF of
                        // BEFORE _activeEntryId gets overwritten below with the new
                        // member (the fallback target). The health update must be
                        // keyed by the failed entry, not the one we moved to.
                        val failedEntryId = host.activeEntryId
                        // [T-recovery] Demote the failed entry so selection /
                        // fallback skip it until it recovers. Outcome taxonomy:
                        // 429 → Cooling (Retry-After when available), 5xx →
                        // circuit-breaker counter, 401/403 → Dead (until
                        // re-auth). Network/transient errors deliberately do NOT
                        // demote — a wifi blip is the user's side, not this
                        // member's fault, and churning the whole group over it
                        // would manufacture instability.
                        failedEntryId?.let { failed ->
                            when {
                                isRateLimit -> host.groupRouter.recordResult(
                                    failed,
                                    com.rikkaminis.app.data.routing.RouteOutcome.RateLimited(
                                        retryAfterMs = (actual as? com.rikkaminis.app.data.model.LLMError.RateLimited)?.retryAfterMs,
                                    ),
                                )
                                actual is com.rikkaminis.app.data.model.LLMError.InvalidApiKey ->
                                    host.groupRouter.recordResult(
                                        failed,
                                        com.rikkaminis.app.data.routing.RouteOutcome.AuthError,
                                    )
                                is5xx -> host.groupRouter.recordResult(
                                    failed,
                                    com.rikkaminis.app.data.routing.RouteOutcome.ServerError,
                                )
                            }
                        }
                        if (newEntry != null) {
                            host.setActiveEntryId(newEntry.id)
                            host.updateCurrentModel(newEntry.model)
                            val newLabel = host.providerInstanceLabel(newEntry.providerInstanceId)
                            if (newLabel != null) {
                                host.setProviderName(newLabel.ifEmpty { newEntry.model.provider })
                            }
                        }
                        // Flash ONLY on a genuine model switch — never on a
                        // transparent same-model endpoint retry.
                        if (isRealModelChange) host.bumpFallbackTrigger()

                        // [T-error-no-permanent-scars] Instead of inserting an
                        // info block into the message stream (which becomes part
                        // of the chat record), emit a one-shot event for the UI
                        // to show a transient Snackbar ("已切换至 xxx") that
                        // auto-dismisses after a few seconds. The user sees the
                        // switch happen but it leaves no permanent trace.
                        host.emitFallbackToast(
                            host.string(R.string.fallback_switched_to, loopState.currentProvider.model.displayName)
                        )

                        // [T-android-fallback-text-rewind] Same as the retry-
                        // rollback path above: preserve this turn's streamed text
                        // (`turnTextSb`) on screen while we switch providers.
                        // `loopState.accumulatedText` hasn't folded it in yet, so bare
                        // `loopState.accumulatedText` would rewind the visible reply. The new
                        // provider streams into a fresh `turnTextSb` (reset just
                        // below) and re-publishes `loopState.accumulatedText + newTurnText`.
                        // RC3 (F-T01-01): BEFORE switching to the fallback provider,
                        // roll back this turn's partial blocks — a failed provider may
                        // have emitted one or more fake `tool_use` blocks (PENDING) that
                        // must not survive into the new provider's completed turn, the
                        // persisted parts, or the next request's sanitize-injected
                        // placeholder tool_result. Mirrors the retry path's rollback so
                        // the two paths cannot drift.
                        rollbackTurnBlocksTo(loopState.allToolBlocks, turnStartBlockIndex)
                        withContext(Dispatchers.Main) {
                            host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText + turnTextSb.toString(), true, loopState.allToolBlocks)
                        }
                        // Reset turn state for retry with new provider
                        turnTextSb.setLength(0)
                        currentTextBlockSb = null
                        // [T-android-tool-splits-reply-fix] Fresh stream from a
                        // different provider — and the add(0, info) above
                        // shifted every block index anyway.
                        turnTextBlockIdx = -1
                        turnThinking.clear()
                        toolCalls.clear()
                        // §24a Same per-stream throttle reset as the retry and
                        // completion paths — without it the fallback provider's
                        // first delta coalesces against the failed attempt's
                        // clocks and the reply head stalls visibly.
                        resetStreamThrottle(loopState)
                        // [deep-audit-1016 6a] The member that will serve the
                        // next attempt is a different one — re-derive the
                        // budget/calibration from IT before the retry loop
                        // re-issues with the captured values.
                        rebudgetForSwitchedProvider("error-fallback")
                        // loop continues — will retry collect with loopState.currentProvider
                    } else {
                        // All fallbacks exhausted. Surface the trail of tried
                        // models AND the group members that were silently
                        // skipped (disabled / not logged in / hidden) so the
                        // user can see why fallback never reached them —
                        // mirrors iOS streamWithGroupFallback exhausted path.
                        if (shouldFallback) {
                            val skipped = host.unavailableGroupMembers()
                            if (loopState.fallbackReasons.isNotEmpty() || skipped.isNotEmpty()) {
                                val trail = (loopState.fallbackReasons + skipped).joinToString("\n")
                                // [T-error-no-permanent-scars] Throw a summary/detail
                                // split: the banner shows the human summary ("tried N
                                // models"), the raw per-model trail (with the original
                                // error codes) is carried as `detail` for the collapsed
                                // technical-details disclosure — it never becomes the
                                // primary visible error text.
                                val triedCount = loopState.fallbackReasons.size + 1  // primary + fallback members
                                val summary = when (actual) {
                                    is com.rikkaminis.app.data.model.LLMError.NetworkError,
                                    is com.rikkaminis.app.data.model.LLMError.TransientError ->
                                        host.string(R.string.error_all_models_failed, triedCount)
                                    is com.rikkaminis.app.data.model.LLMError.RateLimited ->
                                        host.string(R.string.error_all_models_rate_limited)
                                    is com.rikkaminis.app.data.model.LLMError.InvalidApiKey ->
                                        host.string(R.string.error_all_models_bad_key)
                                    else -> (actual as? com.rikkaminis.app.data.model.LLMError)?.userMessage
                                        ?: actual.message?.takeIf { it.isNotBlank() }
                                        ?: "Unknown error"
                                }
                                // TF-H: reducer must leave FALLING_BACK before
                                // finalizing — otherwise RunFinalized is REJECTED
                                // with "requires FINALIZING (current=FALLING_BACK)".
                                traceObserver.t7Reduce(AgentRunEvent.FallbackExhausted)
                                throw com.rikkaminis.app.data.model.FallbackExhaustedError(
                                    summary = summary,
                                    detail = "$trail\n${actual.message ?: actual.toString()}",
                                )
                            }
                        }
                        // TF-H: even when the error is not fallbackable, make sure
                        // the reducer has left the running phases before the outer
                        // finalizer sends RunFinalized.
                        if (!shouldFallback) {
                            traceObserver.t7Reduce(AgentRunEvent.ProcessInterrupted("provider_fatal_not_fallbackable"))
                        }
                        throw actual  // re-throw unwrapped, all fallbacks exhausted
                    }
                }
            }  // end while (!collectDone)

            // [fix/prewindow-budget-1015] usage → 校准窗口回填，流结束后恰好
            // 一次（移出 chunk 处理器：一次流可能吐多帧 usage，会把窗口灌成
            // 近似重复样本）。用 lastUsage 的最终帧；本地端用预检时对同一
            // wire 请求算的原始估算（未乘校准）。
            run {
                val usageNow = lastUsage
                val latest = usageNow?.latestContextTokens ?: 0
                if (latest > 0 && preflightEstimate > 0) {
                    val inst = loopState.currentProvider.instanceContext
                    if (inst != null) {
                        val ck = ContextCalibration.keyFor(
                            providerType = inst.providerType.name,
                            baseUrl = inst.customBaseURL ?: "",
                            modelId = loopState.currentProvider.model.id,
                        )
                        val obs = ContextCalibration.observe(
                            key = ck,
                            providerUsage = latest.toLong(),
                            localEstimate = rawEstimate,
                        )
                        AppLogger.info(
                            TAG_STREAM,
                            "[Calibration] $ck observed=$obs usage=$latest " +
                                "localEst=$preflightEstimate ratio=${ContextCalibration.ratio(ck)}",
                        )
                    }
                }
            }

            // [deep-audit-1016 6b] A content-filter / safety-block finish with NO
            // usable output is a member-level refusal, not a healthy serve — the
            // branch further down consumes the fallback chain for it. Recording
            // `Success` for it cleared that member's cooldown and closed its
            // half-open circuit, i.e. it marked a REFUSING member healthy.
            // `RouteOutcome` has no member for "the member refused this prompt"
            // (Success / RateLimited / ServerError / AuthError are all transport
            // or credential verdicts, see its KDoc: outcomes that should not
            // affect a member's health are deliberately absent), so the correct
            // record is none — the member keeps whatever health it had, and a
            // later genuinely-served turn restores it as usual.
            // One definition, two consumers: this health record and the fallback
            // branch that acts on it. It reads the raw turn buffer, which is the
            // value `turnTextRaw` takes a few lines below — nothing writes the
            // buffer between the two points (only the streaming collect above
            // does), so the two spellings can never disagree.
            val blockedEmptyReply = toolCalls.isEmpty() &&
                turnTextSb.isEmpty() &&
                ContentFilterFinishPolicy.isBlockedFinish(turnFinishReason)

            // [T-recovery] The turn's stream completed without error — the
            // member that served it is healthy. Clears any prior cooldown /
            // circuit state (also closes a half-open circuit: a successful
            // probe restores the member).
            host.activeEntryId?.let { entryId ->
                // [deep-audit-1016 6b] …except a blocked-empty reply, as above.
                if (!blockedEmptyReply) {
                    host.groupRouter.recordResult(
                        entryId,
                        com.rikkaminis.app.data.routing.RouteOutcome.Success,
                    )
                }
            }

            // T307: materialise the per-turn StringBuilder ONCE at the
            // turn boundary. After this point everything is plain String
            // semantics — `turnText` participates in cross-turn accumulation
            // and gets persisted into host.agentHistory below.
            //
            // [T-length-wall-seam-dedup] When the PREVIOUS turn was truncated
            // by the output-token wall, this turn's text is a continuation —
            // models frequently back up to an earlier semantic anchor and
            // re-emit a phrase they already output, which used to be kept
            // verbatim on every layer and produced the field-observed
            // mid-sentence duplication like
            // `…已经站在一个，是因为它确实已经站在一个一个比较高的…`.
            // The seam (suffix-of-accumulated ∩ prefix-of-continuation) is
            // trimmed ONCE here and applied consistently to all three
            // representations that must stay in sync:
            //   1. loopState.accumulatedText (message body / host.updateAssistantMessage)
            //   2. this turn's text blocks in loopState.allToolBlocks (renderer reads
            //      kind=="text" blocks — the actual UI source of truth)
            //   3. turnText → host.agentHistory Text part + DB persistence
            // A trim that only patched one layer would leave the duplicated
            // seam in the others (e.g. history keeping the dup would teach
            // the model to keep duplicating on the next request).
            val turnTextRaw = turnTextSb.toString()
            var turnText = turnTextRaw
            var trimmedSeamChars = 0
            if (loopState.lastTurnWasLengthWall && turnTextRaw.isNotEmpty()) {
                val merged = mergeLengthWallSeam(loopState.accumulatedText, turnTextRaw)
                trimmedSeamChars = loopState.accumulatedText.length + turnTextRaw.length - merged.length
                loopState.accumulatedText = merged
                if (trimmedSeamChars > 0) {
                    turnText = turnTextRaw.substring(minOf(trimmedSeamChars, turnTextRaw.length))
                    // Re-base this turn's text blocks: consume the duplicated
                    // seam chars from the head of the turn's text blocks
                    // (dropping blocks that are pure seam). Non-text blocks
                    // (tool cards from interleaved tool_use) are skipped in
                    // place — they carry no seam.
                    var remaining = trimmedSeamChars
                    var bi = turnStartBlockIndex
                    while (remaining > 0 && bi < loopState.allToolBlocks.size) {
                        val b = loopState.allToolBlocks[bi]
                        if (b.kind != "text" || b.content.isEmpty()) { bi++; continue }
                        if (remaining >= b.content.length) {
                            remaining -= b.content.length
                            loopState.allToolBlocks.removeAt(bi)
                        } else {
                            loopState.allToolBlocks[bi] = b.copy(content = b.content.substring(remaining))
                            remaining = 0
                        }
                    }
                    AppLogger.info(
                        TAG_STREAM,
                        "[T-length-wall-seam-dedup] trimmed $trimmedSeamChars duplicated seam char(s) across text/blocks/history",
                    )
                }
            } else {
                loopState.accumulatedText += turnTextRaw
            }
            // [fix/eof-stub-continuation] EOF-stub continuations behave like
            // length-wall continuations for seam-dedup purposes: the next
            // turn's head may repeat the truncated tail. The stub branch
            // (below, finish-path) sets this flag when it injects the
            // network-stub reminder; here it must NOT be cleared when the
            // previous turn was an EOF stub (turnFinishReason is null on the
            // next streamed turn, which would reset the flag before the seam
            // merge could use it). Cleared only on a NORMAL finish (stop /
            // end_turn / tool-call turn) — those are clean turn boundaries
            // where head-overlap trimming would be wrong.
            loopState.lastTurnWasLengthWall = when {
                turnTextRaw.isEmpty() -> false
                TruncatedToolCallPolicy.isTruncatedFinish(turnFinishReason) -> true
                turnFinishReason == null -> loopState.lastTurnWasLengthWall
                else -> false // stop / end_turn / tool-call turns: clean boundary
            }

            // [fix/finish-reason-network-error] Field-observed (2026-09-06
            // log, user-uploaded): a relay (agentrouter.org / glm-5.3) turned
            // ITS OWN upstream failure into a normal SSE finish frame —
            // finish_reason="network_error", zero content, clean [DONE]. No
            // EOF, no error line, no exception: every existing guard (EOF
            // stub, stream-error kind) is keyed on the ABNORMAL paths, so
            // this pseudo-finish sailed through as "no tool calls → break"
            // and the user saw the reply die mid-answer with NO banner and
            // NO retry. Treat error-shaped finishes as transient stream
            // failures:
            //  - with visible partial content → same recovery as an
            //    EOF-truncated stream (network-stub continuation, bounded);
            //  - with no content → one-shot retry (drop the empty turn),
            //    then the normal empty-turn hint path takes over.
            if (toolCalls.isEmpty() && ContentFilterFinishPolicy.isErrorShapedFinish(turnFinishReason)) {
                if (turnTextRaw.isNotEmpty()) {
                    // [feat/runtime-limits-panel] EOF/错误形态续写上限改读 prefs（默认 2）。
                    val eofStubMax = com.rikkaminis.app.data.AgentRuntimeLimitsPrefs.eofStubContinues()
                    if (loopState.eofStubContinues < eofStubMax) {
                        loopState.eofStubContinues++
                        AppLogger.warning(
                            TAG_STREAM,
                            "runAgentLoop turn=$turn finish=$turnFinishReason (error-shaped) with ${turnTextRaw.length} chars — network-stub continuation ${loopState.eofStubContinues}/$eofStubMax",
                        )
                        // [audit-0909 T1-H1] The assistant turn for this
                        // partial reply has NOT been added yet — the loop's
                        // single `agentHistory.add(ASSISTANT)` sits far below
                        // (after this branch, at the assistantParts build).
                        // Without this add, the text the model just streamed
                        // (already on screen) never enters history: the
                        // continuation request carries the previous USER turn
                        // immediately followed by our stub USER message (the
                        // model loses its own output and the turn is never
                        // persisted — reload drops it). Same shape as the
                        // EOF-stub branch below, which runs after the add.
                        host.agentHistory.add(
                            LLMMessage(
                                role = LLMMessage.Role.ASSISTANT,
                                content = turnText,
                                contentParts = listOf(AgentContentPart.Text(turnText)),
                                reasoningContent = turnReasoningBlob
                                    ?: turnThinking.toString().takeIf { it.isNotEmpty() },
                            )
                        )
                        // [deep-audit-1018 ISSUE-12c] Stacking guard — the
                        // EOF-stub branch below has had this since its own
                        // introduction, but this error-shaped twin shipped
                        // without it: consecutive error-shaped continuations
                        // piled identical reminders into history (the model
                        // saw N stacked "continue where you left off"
                        // instructions). Drop the stale reminder sitting
                        // behind this turn's assistant message before adding
                        // the fresh one. Same off-by-one note as the EOF-stub
                        // sibling: the previous reminder lives at size - 2,
                        // BEHIND this turn's assistant message added just above.
                        val errPrevTailIdx = host.agentHistory.size - 2
                        val errPrevTail = host.agentHistory.getOrNull(errPrevTailIdx)
                        if (EofStubReminderStackGuard.isStaleEofStubReminder(errPrevTail)) {
                            host.agentHistory.removeAt(errPrevTailIdx)
                        }
                        val stubReminder = eofStubReminder(turnText.takeLast(80))
                        host.agentHistory.add(
                            LLMMessage(
                                role = LLMMessage.Role.USER,
                                content = stubReminder,
                                contentParts = listOf(AgentContentPart.Text(stubReminder)),
                            )
                        )
                        loopState.lastTurnWasLengthWall = true
                        continue
                    }
                    AppLogger.warning(
                        TAG_STREAM,
                        "runAgentLoop turn=$turn finish=$turnFinishReason (error-shaped) stub ceiling hit — giving up with visible error",
                    )
                    // [deep-audit-1015 BUG-01b] Deferred, not inline: this
                    // branch falls through to the turn-tail persist, and an
                    // inline setInlineError persists through
                    // updateLastAssistantError = "the session's LAST assistant
                    // row" — with no row for this turn yet, that is the
                    // PREVIOUS turn's row. The turn tail flushes the banner
                    // once this turn's row exists.
                    loopState.pendingInlineError = host.string(R.string.error_stream_interrupted)
                    // [audit-0907 B5] Failure-terminal with a specific banner
                    // already on screen — flag so the exit side does not
                    // misclassify this as a MAX_AGENT_TURNS runaway and
                    // overwrite the banner via finalizeAtTurnLimit.
                    loopState.terminalErrorSurfaced = true
                    // fall through to normal persist + exit (partial kept)
                } else {
                    // Empty + error-shaped: the relay failed BEFORE emitting
                    // anything. One-shot retry (fresh turn re-reads history),
                    // then the empty-turn hint path reports it visibly.
                    if (!loopState.didRetryTruncatedTurn) {
                        loopState.didRetryTruncatedTurn = true
                        AppLogger.warning(
                            TAG_STREAM,
                            "runAgentLoop turn=$turn finish=$turnFinishReason (error-shaped) with no content — one-shot retry",
                        )
                        // [audit-0909 T1-H1] Nothing to drop here: this turn's
                        // assistant message is added far below, AFTER this
                        // branch, so the old removeAt(size-1) deleted the
                        // PREVIOUS real message — the user's original question
                        // on a first turn, or the USER(tool_results) carrying
                        // the just-executed tool output on a tool turn (the
                        // latter then got a "Tool execution was interrupted"
                        // placeholder injected by sanitizeAgentHistory). The
                        // retry request re-reads history as-is, which is
                        // exactly the [audit-0907 B1] intent.
                        continue
                    }
                    AppLogger.warning(
                        TAG_STREAM,
                        "runAgentLoop turn=$turn finish=$turnFinishReason (error-shaped) empty after retry — surfacing error",
                    )
                    // [audit-0909 T1-H1] Persist an (empty) assistant row for
                    // THIS turn before surfacing the error. setInlineError →
                    // updateLastAssistantError addresses the session's LAST
                    // assistant row, and this turn has none (the placeholder
                    // bubble is in-memory only), so without this the banner
                    // was written onto the PREVIOUS turn's row and survived a
                    // reload as a permanent mislabel of a successful turn.
                    // Text("") keeps the parts list non-empty for
                    // persistAssistantTurn's guard; the row is the same empty
                    // assistant message the user sees.
                    host.persistAssistantTurn(
                        listOf(AgentContentPart.Text("")),
                        lastUsage,
                        null,
                        emptyMap(),
                        modelId = loopState.currentProvider.model.id,
                        entryId = host.activeEntryId,
                    )
                    withContext(Dispatchers.Main) {
                        host.setInlineError(host.string(R.string.error_stream_interrupted))
                    }
                    // [audit-0909 T1-L1] Failure-terminal: do NOT set
                    // loopExitedNormally (this is not a clean completion).
                    // terminalErrorSurfaced keeps the exit side from
                    // classifying it as a MAX_AGENT_TURNS runaway, and the
                    // trace now records FAILED/EXECUTION_FAILED instead of
                    // SUCCEEDED/COMPLETED.
                    loopState.terminalErrorSurfaced = true
                    break
                }
            }

            // [feat/content-filter-fallback] A content-filter / safety-block
            // finish (content_filter, Gemini SAFETY/RECITATION/…, Anthropic
            // refusal) is a DETERMINISTIC member-level refusal: this member
            // will answer the same way on every retry. Instead of falling
            // through to the blank-bubble path below, consume the fallback
            // chain immediately — a different member may have a different
            // safety posture and answer fine. Guard rails:
            //  - only when there is no usable output (a content_filter WITH
            //    partial text is a finished answer for our purposes — the
            //    model said what it was allowed to say);
            //  - the empty assistant turn added above is dropped before
            //    continuing (mirrors the empty-after-toolresult retry path);
            //  - the loopExitedNormally flow below is skipped entirely via
            //    `continue`.
            if (blockedEmptyReply) {
                // [feat/content-filter-fallback] A content-filter / safety-
                // block finish with NO usable output is a DETERMINISTIC
                // member-level refusal: this member will answer the same way
                // on every retry. Consume the fallback chain immediately — a
                // different member may have a different safety posture and
                // answer fine. (A content_filter WITH partial text falls
                // through: the model said what it was allowed to say, which
                // is a finished answer.)
                val next = loopState.remainingFallbacks.removeFirstOrNull()
                if (next != null) {
                    AppLogger.warning(
                        TAG_STREAM,
                        "runAgentLoop turn=$turn finish=$turnFinishReason (content filter / safety block) on ${loopState.currentProvider.model.displayName} — falling back to ${next.provider.model.displayName}",
                    )
                    loopState.fallbackReasons.add("⚠️ ${loopState.currentProvider.model.displayName}: $turnFinishReason")
                    val failedProvider = loopState.currentProvider
                    loopState.currentProvider = next.provider
                    host.setCurrentProvider(next.provider)
                    host.noteModelNames(modelName = next.provider.model.displayName, providerName = null, entryId = null)
                    // Same entry-precision rule as the error-path fallback:
                    // resolve the group ENTRY by id, not by modelId (a group
                    // can hold several entries for the same modelId behind
                    // different instances).
                    host.activeConfigModelEntries.find { it.id == next.entryId }?.let { newEntry ->
                        host.setActiveEntryId(newEntry.id)
                        host.updateCurrentModel(newEntry.model)
                        val newLabel = host.providerInstanceLabel(newEntry.providerInstanceId)
                        if (newLabel != null) {
                            host.setProviderName(newLabel.ifEmpty { newEntry.model.provider })
                        }
                    }
                    if (next.provider.model.id != failedProvider.model.id) host.bumpFallbackTrigger()
                    host.emitFallbackToast(
                        host.string(R.string.fallback_switched_to, next.provider.model.displayName)
                    )
                    // NOTE: no rollback of allToolBlocks needed here — the
                    // blocked turn produced no text and no tool blocks
                    // (guard requires turnTextRaw.isEmpty()); nothing was
                    // streamed to the screen. Reset the per-attempt retry
                    // budget so the fallback member gets its own full
                    // transient-retry allowance.
                    retryAttempt = 0
                    // [deep-audit-1016 6a] Same as the error-path fallback: the
                    // next attempt is served by a different member, so the
                    // captured budget/calibration must be re-derived from it.
                    rebudgetForSwitchedProvider("content-filter-fallback")
                    // skip the rest of this turn's post-processing (history
                    // add / persist) — `continue` targets the OUTER for(turn)
                    // loop, same as the length-wall and EOF-stub branches.
                    continue
                }
                // Fallback chain exhausted or absent (single-model) — surface
                // a human-readable error instead of the silent blank bubble
                // this path produced before.
                AppLogger.warning(
                    TAG_STREAM,
                    "runAgentLoop turn=$turn finish=$turnFinishReason (content filter / safety block) — no fallback available, surfacing error",
                )
                // [audit-0909 T1-H1] Same row-before-banner fix as the
                // error-shaped empty branch: this turn has no persisted row
                // yet, so updateLastAssistantError would stamp the previous
                // turn's row. See that branch for the full rationale.
                host.persistAssistantTurn(
                    listOf(AgentContentPart.Text("")),
                    lastUsage,
                    null,
                    emptyMap(),
                    modelId = loopState.currentProvider.model.id,
                    entryId = host.activeEntryId,
                )
                withContext(Dispatchers.Main) {
                    host.setInlineError(host.string(R.string.error_content_filtered))
                }
                // [audit-0909 T1-L1] failure-terminal, not a clean completion —
                // see the error-shaped branch above.
                loopState.terminalErrorSurfaced = true
                break
            }

            // Build assistant contentParts for history
            val assistantParts = mutableListOf<AgentContentPart>()
            if (turnText.isNotEmpty()) {
                assistantParts.add(AgentContentPart.Text(turnText))
            }
            for ((id, name, args) in toolCalls) {
                assistantParts.add(AgentContentPart.ToolUse(id, name, args))
            }

            // Map toolUseId -> input JSON string for persistence (accumulated across turns)
            toolCalls.forEach { (id, _, args) -> loopState.allToolInputs[id] = args.toString() }
            // [audit2 BUG-01] The three continuation-counter resets that used
            // to live HERE were moved down into the tool-call path (see the
            // `if (toolCalls.isNotEmpty())` guard after the empty-turn branch).
            // Sitting at this spot they ran unconditionally on every turn —
            // including the length-wall / EOF-stub turns they were supposed to
            // leave alone — so every counter was pinned back to 0 before it
            // could reach its limit and all three guards were dead.
            // Guarded so this exact regression cannot come back:
            // scripts/scan/loop_counter_reset_guard.py.
            val toolInputMap = loopState.allToolInputs
            // Prefer the opaque blob from LLMStreamChunk.ReasoningContent when the
            // provider emitted one — that path preserves empty strings (DeepSeek V4
            // `reasoning_content: ""` on non-thinking turns). Fall back to the
            // ThinkingDelta concatenation only when no blob arrived; in that case
            // an empty buffer becomes null (no field to round-trip).
            val turnReasoningContent: String? = turnReasoningBlob
                ?: turnThinking.toString().takeIf { it.isNotEmpty() }

            host.agentHistory.add(LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = turnText,
                contentParts = assistantParts,
                reasoningContent = turnReasoningContent,
            ))

            // T321: empty-turn diagnostic — fires when GPT-5.5 (or any other
            // provider) returns a turn with no visible text AND no tool calls.
            // Log only; UI behavior unchanged. Pair with OpenAIProvider SSE
            // logs to triage server-empty vs parser-drop vs swallowed-exception.
            if (turnText.isEmpty() && toolCalls.isEmpty()) {
                AppLogger.warning(
                    TAG_STREAM,
                    "empty turn detected: turn=$turn finishReason=$turnFinishReason " +
                        "reasoningLen=${turnThinking.length} reasoningBlobLen=${turnReasoningBlob?.length ?: -1} " +
                        "model=${provider.model.id} provider=${provider.name}"
                )
            }

            // If no tool calls, we're done
            if (toolCalls.isEmpty()) {
                // [T-length-wall-continue] finish_reason="length" means the
                // output was truncated mid-stream, NOT that the model finished.
                // The truncated content is already in host.agentHistory (added above)
                // and loopState.accumulatedText, so continuing the loop makes the next
                // API call present it as the model's own partial reply — the
                // model just picks up where it cut off. Previously this fell
                // through to break and the user got a silently truncated answer
                // (observed in the field: "task just stops mid-stage; raising
                // the context limit makes it continue again" — which only
                // pushed the wall further out, it did not fix the break).
                if (TruncatedToolCallPolicy.isTruncatedFinish(turnFinishReason)) {
                    if (turnText.isEmpty()) {
                        // Empty + length: the model burned the whole budget
                        // producing nothing usable. Give it up to 3 tries (a
                        // fresh turn re-reads history and may shape a new
                        // answer), then give up with the normal empty-turn hint
                        // instead of spinning against the same wall. Mirrors
                        // AgentNodeTimeout.shouldRetryAfterTimeout's "retrying
                        // a node that burned its whole budget just pays for the
                        // same wall again".
                        loopState.lengthWallEmptyHits++
                        // [feat/hermes-tier1] Deterministic-empty fast-exit:
                        // when the usage block PROVES zero output tokens
                        // (outputTokens==0) on consecutive empty length-walls,
                        // the provider is deterministically returning nothing —
                        // retrying re-bills the full input for a provably
                        // identical result (Hermes empty_response_guard port).
                        // Whitespace-only output or a missing usage block keeps
                        // the legacy 3-hit budget (fail-open).
                        val usageForEmptyCheck = lastUsage
                        val usageProvesEmpty = usageForEmptyCheck != null &&
                            usageForEmptyCheck.outputTokens == 0
                        if (usageProvesEmpty) {
                            loopState.deterministicEmptyStreak++
                            // [feat/runtime-limits-panel] 快出阈值改读 prefs（默认 2）。
                            if (loopState.deterministicEmptyStreak >= com.rikkaminis.app.data.AgentRuntimeLimitsPrefs.deterministicEmptyLimit()) {
                                AppLogger.warning(
                                    TAG_STREAM,
                                    "runAgentLoop turn=$turn finish=length empty ×$loopState.deterministicEmptyStreak with usage.outputTokens==0 — deterministic empty, skipping remaining retries",
                                )
                                // [deep-audit-1015 BUG-01b] Deferred to the
                                // turn tail (this branch falls through to the
                                // persist): an inline setInlineError would
                                // write the banner onto the PREVIOUS turn's
                                // row. See the error-shaped stub-ceiling
                                // branch above for the full rationale.
                                loopState.pendingInlineError = host.string(R.string.error_output_truncated_repeated)
                                // [audit-0907 B5] Failure-terminal (specific
                                // banner shown) — see the flag's KDoc; prevents
                                // the exit-side runaway misclassification.
                                loopState.terminalErrorSurfaced = true
                                // Fall through to the normal break path
                                // (persist + exit).
                                //
                                // [deep-audit-1015 BUG-01a] This branch does
                                // NOT claim a clean completion: the old
                                // warning here said breaking directly "would
                                // skip loopExitedNormally = true and
                                // misclassify as a MAX_AGENT_TURNS runaway" —
                                // true before audit-0907 B5, false since.
                                // terminalErrorSurfaced is what keeps
                                // finalizeAtTurnLimit away (see
                                // AgentLoopExitDecision.shouldFinalizeAsRunaway),
                                // and the tail now sets the clean-completion
                                // flag only when no terminal error was
                                // surfaced. The fall-through is still
                                // REQUIRED — it is what persists the partial
                                // reply — it just no longer lies about the
                                // outcome.
                            }
                        } else {
                            loopState.deterministicEmptyStreak = 0
                        }
                        // [fix/context-exhausted-loop] A retry only pays for
                        // itself when the next request could produce
                        // DIFFERENT output. At/over the hard window ceiling
                        // the per-request output budget is pinned at
                        // MIN_MAX_TOKENS no matter how many times we ask
                        // (remaining = window − input is negative and
                        // dynamicMaxTokens floors it at the minimum), so each
                        // retry re-bills the whole input for the same empty
                        // result — the same reasoning the usageProvesEmpty
                        // fast-exit above already applies, one level up.
                        // Field evidence 2026-09-20 (build 27eced19):
                        // input=405694 vs window=200000, three consecutive
                        // empty length-wall turns 40s apart (reasoningLen
                        // 1940 → 3477 → 3916, body empty), each preceded by
                        // `[AutoCompactLoop] skipped: EXHAUSTED` and each
                        // followed by a fresh `chat stream offload ->
                        // :modelservice`. 2m33s of "thinking" for an error.
                        val contextExhausted = host.isContextExhausted()
                        // [audit-1015 BUG-03A] The deterministic-empty threshold
                        // above may already have surfaced the terminal banner
                        // (terminalErrorSurfaced=true, the ONLY setter that
                        // falls through to this gate — every other setter in the
                        // turn either breaks or continues before reaching it).
                        // Letting the retry gate fire after the banner re-bills
                        // the full input for one more provably identical empty
                        // request — the fuse must actually stop the run.
                        if (shouldContinueAfterDeterministicFuse(
                                terminalErrorSurfaced = loopState.terminalErrorSurfaced,
                                hits = loopState.lengthWallEmptyHits,
                                contextExhausted = contextExhausted,
                            )
                        ) {
                            // T9: log the wasted empty-length iteration
                            traceObserver.agentTraceRecorder.turnEnd(
                                turn = turn,
                                tokensIn = lastUsage?.inputTokens,
                                tokensOut = lastUsage?.outputTokens,
                                finishReason = turnFinishReason,
                                durationMs = System.currentTimeMillis() - turnStartMs,
                            )
                            AppLogger.warning(
                                TAG_STREAM,
                                "runAgentLoop turn=$turn finish=length with empty output (wall hit $loopState.lengthWallEmptyHits/${LENGTH_WALL_EMPTY_MAX_HITS}), continuing",
                            )
                            // [audit-1015 BUG-03B] The just-added ASSISTANT turn
                            // (content="" + no parts) must not ride into the
                            // retry request: the provider filters empty
                            // assistant messages out (contentParts+content both
                            // empty), so the shell is invisible to the model
                            // anyway — and consecutive empty shells accrete in
                            // history across retries. Same three-field predicate
                            // as the nudge paths (a shell carrying
                            // reasoningContent is NOT blank and is kept).
                            dropTrailingBlankAssistantShell(host.agentHistory)
                            continue
                        }
                        if (contextExhausted) {
                            AppLogger.warning(
                                TAG_STREAM,
                                "runAgentLoop turn=$turn finish=length with empty output — NOT retrying: " +
                                    "context is at/over the window ceiling, so the retry would re-bill the " +
                                    "same input for the same empty result (wall hit $loopState.lengthWallEmptyHits/${LENGTH_WALL_EMPTY_MAX_HITS})",
                            )
                        } else {
                            AppLogger.warning(TAG_STREAM, "runAgentLoop turn=$turn finish=length ×${LENGTH_WALL_EMPTY_MAX_HITS} empty output — giving up")
                        }
                        // length is NOT a clean finish, so the empty-turn hint
                        // below (gated on finishedCleanly) won't fire — surface
                        // a visible error explicitly so the user isn't left
                        // staring at a silent blank bubble.
                        //
                        // [deep-audit-1015 BUG-01b] Deferred to the turn tail
                        // (this branch falls through to the persist): an inline
                        // setInlineError would write the banner onto the
                        // PREVIOUS turn's row. This is also the branch whose
                        // turn has NO parts (empty output) — TerminalErrorPlacement
                        // makes the tail persist an empty-text row for it, so
                        // the banner has a row of its own to land on.
                        loopState.pendingInlineError = host.string(R.string.error_output_truncated_repeated)
                        // [audit-0907 B5] Failure-terminal (specific banner
                        // shown) — prevents the exit-side runaway
                        // misclassification from overwriting it.
                        loopState.terminalErrorSurfaced = true
                        // Fall through to the normal break path (persist +
                        // exit) — the fall-through is what persists whatever
                        // the turn produced. [deep-audit-1015 BUG-01a] It no
                        // longer claims a clean completion; see the
                        // deterministic-empty branch above.
                    } else {
                        // Truncated mid-answer: continue so the model finishes.
                        // [feat/hermes-tier1] Repetition guard FIRST (Hermes
                        // turn_truncation order: abort BEFORE continuing): if
                        // the visible output is dominated by a degenerate
                        // repetition loop, continuing only stitches more
                        // repeated text into the reply. Also enforce the
                        // continuation ceiling — a model that re-truncates on
                        // every attempt burns billed calls without bound.
                        loopState.lengthWallEmptyHits = 0
                        loopState.deterministicEmptyStreak = 0
                        if (isRepetitionDominated(turnText)) {
                            AppLogger.warning(
                                TAG_STREAM,
                                "runAgentLoop turn=$turn finish=length but output is repetition-dominated (${turnText.length} chars) — aborting instead of continuing the degenerate response",
                            )
                            // [deep-audit-1015 BUG-01b] Deferred to the turn
                            // tail (this branch falls through to the persist) —
                            // see the error-shaped stub-ceiling branch above.
                            loopState.pendingInlineError = host.string(R.string.error_output_truncated_repeated)
                            // [audit-0907 B5] Failure-terminal (specific banner
                            // shown) — prevents the exit-side runaway
                            // misclassification from overwriting it.
                            loopState.terminalErrorSurfaced = true
                            // Fall through to the normal break path (persist +
                            // exit). NOT a clean completion (this is an
                            // abort), but also NOT a runaway misclassification:
                            // see the deterministic-empty branch above for the
                            // same pattern.
                        } else if (loopState.lengthWallContinues >= com.rikkaminis.app.data.AgentRuntimeLimitsPrefs.lengthWallContinues()) {
                            // [feat/runtime-limits-panel] 续写上限改读 prefs（默认 4）。
                            val lwMax = com.rikkaminis.app.data.AgentRuntimeLimitsPrefs.lengthWallContinues()
                            AppLogger.warning(
                                TAG_STREAM,
                                "runAgentLoop turn=$turn finish=length — continuation ceiling $lwMax reached, giving up (accumulated ${loopState.accumulatedText.length} chars)",
                            )
                            // [deep-audit-1015 BUG-01b] Deferred to the turn
                            // tail (this branch falls through to the persist) —
                            // see the error-shaped stub-ceiling branch above.
                            loopState.pendingInlineError = host.string(R.string.error_output_truncated_repeated)
                            // [audit-0907 B5] Failure-terminal (specific banner
                            // shown) — prevents the exit-side runaway
                            // misclassification from overwriting it.
                            loopState.terminalErrorSurfaced = true
                            // Fall through to the normal break path (persist +
                            // exit) — see the deterministic-empty branch above
                            // for why the fall-through is required and why it
                            // no longer claims a clean completion.
                        } else {
                            loopState.lengthWallContinues++
                            // T9: log the truncated turn before continuing
                            traceObserver.agentTraceRecorder.turnEnd(
                                turn = turn,
                                tokensIn = lastUsage?.inputTokens,
                                tokensOut = lastUsage?.outputTokens,
                                finishReason = turnFinishReason,
                                durationMs = System.currentTimeMillis() - turnStartMs,
                            )
                            AppLogger.warning(
                                TAG_STREAM,
                                "runAgentLoop turn=$turn finish=length — truncated (${turnText.length} chars), continuing loop to let the model finish (${loopState.lengthWallContinues}/${com.rikkaminis.app.data.AgentRuntimeLimitsPrefs.lengthWallContinues()})",
                            )
                        // [T-length-wall-prefill] When the provider accepts
                        // an assistant-final prefill, the truncated assistant
                        // text is ALREADY the last message in host.agentHistory
                        // (added above), so continuing the loop re-sends it as
                        // the final message with NO synthetic user message —
                        // the model is forced to continue the unfinished
                        // assistant turn and has no room to back up and
                        // re-emit already-output text (the ROOT cause of
                        // length-wall seam duplication, which the reminder +
                        // seam-trim below could only patch after the fact).
                        // mergeLengthWallSeam stays as belt-and-braces for
                        // models that repeat even under prefill.
                        if (loopState.currentProvider?.supportsPrefill == true) {
                            AppLogger.info(
                                TAG_STREAM,
                                "runAgentLoop turn=$turn finish=length — prefill continuation (no reminder) via ${loopState.currentProvider.name}",
                            )
                            continue
                        }
                        // [T-length-wall-reminder] Prefill NOT supported
                        // (strict relay requiring a final USER message): inject
                        // a continuation instruction as a synthetic USER message
                        // (same delivery pattern as resume()'s stop-continue
                        // reminder). Without it the next request presents the
                        // truncated reply as bare context and models frequently
                        // back up to an earlier semantic anchor, re-emitting a
                        // phrase they already output — the field-observed
                        // mid-sentence duplication. The reminder anchors the
                        // exact cut point and forbids repetition.
                        // mergeLengthWallSeam below remains the belt-and-
                        // braces guard for models that repeat anyway.
                        //
                        // Not persisted to DB (unlike resume()'s reminder):
                        // this is a transient in-loop instruction. Guard
                        // against stacking: if the history tail already
                        // carries one of these reminders (double wall), drop
                        // the old one first so consecutive length-walls do
                        // not pile up reminder turns.
                        // [fix/audit-b20 / T1-LOW-2] The reminder from a previous
                        // length-wall sits *behind* this turn's assistant
                        // message (added above), so the tail check could never
                        // match and consecutive walls stacked. Look one entry
                        // back and drop it there.
                        val prevTailIdx = host.agentHistory.size - 2
                        val prevTail = host.agentHistory.getOrNull(prevTailIdx)
                        val tailIsLengthWallReminder = prevTail != null &&
                            prevTail.role == LLMMessage.Role.USER &&
                            prevTail.contentParts.size == 1 &&
                            (prevTail.contentParts.first() as? AgentContentPart.Text)?.text
                                ?.contains("cut off mid-sentence") == true
                        if (tailIsLengthWallReminder) {
                            host.agentHistory.removeAt(prevTailIdx)
                        }
                        val reminder = lengthWallReminder(turnText.takeLast(80))
                        host.agentHistory.add(
                            LLMMessage(
                                role = LLMMessage.Role.USER,
                                content = reminder,
                                contentParts = listOf(AgentContentPart.Text(reminder)),
                            )
                        )
                        continue
                        } // [feat/hermes-tier1] else — continuation budget branch
                    }
                }
                // [feat/verification-stop] Turn-end verification guard
                // (Hermes verification_stop port, Tier-2 #3). The model is
                // about to finish its reply. If this run edited CODE files
                // and the newest passing verification evidence predates the
                // last edit, inject ONE bounded follow-up nudge instead of
                // letting the turn close unverified — the nudge tells the
                // model to run the relevant check, read failures, repair,
                // and summarize what actually passed. Policy-only: nothing
                // runs a check here (VerificationStopPolicy owns the shape
                // classification; the engine only tracks edit/verify order).
                // [audit-0909 T1-M2] A failure-terminal banner is already on
                // screen when one of the branches above set
                // terminalErrorSurfaced and fell through to here (length-wall
                // ceiling / deterministic-empty / repetition abort /
                // EOF-stub ceiling). Injecting a verify nudge would `continue`
                // the loop and REVIVE a run the user was just told failed —
                // and if the revived turn then succeeds, the banner and the
                // trace contradict the outcome. "Giving up" must not be
                // interrupted by the turn-end guard.
                // [fix/verify-nudges-default-off] Read the live limit once and
                // hand it to the policy — the log's denominator and the gate
                // now provably share one read (0 = guard off, nudge is null).
                // [deep-audit-1015 BUG-04] The gate the policy's KDoc always
                // described: a PASSING verification newer than the last edit
                // already answers the nudge, so do not demand it again.
                // Without this the two evidence stamps were pure bookkeeping
                // (write-only) and buildNudge fired on code edits alone —
                // nudging for verification the run had just completed.
                val verifyNudgeLimit = com.rikkaminis.app.data.AgentRuntimeLimitsPrefs.verifyNudges()
                val verifyNudge = if (loopState.terminalErrorSurfaced ||
                    VerificationStopPolicy.hasFreshVerification(
                        lastEditStamp = loopState.lastEditStamp,
                        lastVerifyStamp = loopState.lastVerifyStamp,
                    )
                ) {
                    null
                } else {
                    VerificationStopPolicy.buildNudge(
                        changedPaths = loopState.changedCodePaths.toList(),
                        attempts = loopState.verifyNudgeAttempts,
                        lastEvidenceDetail = loopState.lastVerificationDetail,
                        nudgeLimit = verifyNudgeLimit,
                    )
                }
                if (verifyNudge != null) {
                    loopState.verifyNudgeAttempts++
                    // [fix/runtime-limits-audit] The log's denominator reads the
                    // SAME live limit buildNudge just gated on, so the counter
                    // can't claim "2/2" while the cap is actually 3.
                    // [fix/verify-nudges-default-off] Now literally the same
                    // value (one read), not a second read of the same cache.
                    AppLogger.warning(
                        TAG_STREAM,
                        "runAgentLoop turn=$turn finish=$turnFinishReason but unverified code edits " +
                            "(${loopState.changedCodePaths.size} path(s)) — injecting verify nudge " +
                            "${loopState.verifyNudgeAttempts}/$verifyNudgeLimit",
                    )
                    val nudgeMsg = LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = verifyNudge,
                        contentParts = listOf(AgentContentPart.Text(verifyNudge)),
                    )
                    // [fix/same-class-cleanup] Same invariant the entry points
                    // already enforce ([ensureRoleAlternationBeforeUserAppend]):
                    // tool results are persisted to history as role=USER
                    // messages, so the tail here can be user(tool_result) when
                    // the PREVIOUS turn dispatched tools — a blind USER append
                    // yields two consecutive user roles (Anthropic hard 400
                    // `roles must alternate` / OpenAI silent merge that
                    // swallows the tool_result pairing). Bridge first.
                    // [audit-1015 BUG-03C] A trailing BLANK assistant shell
                    // (empty content + no parts + no reasoning) would be
                    // filtered out of the outgoing request by the provider's
                    // empty-message filter, turning [..., ASSISTANT-shell,
                    // USER-nudge] into [..., USER, USER] — the exact Anthropic
                    // 400 the bridge below exists to prevent. Drop the blank
                    // shell BEFORE bridging so the bridge sees the real tail.
                    dropTrailingBlankAssistantShell(host.agentHistory)
                    ensureRoleAlternationBeforeUserAppend(host.agentHistory)
                    host.agentHistory.add(nudgeMsg)
                    continue
                }

                // [fix/tool-call-copy-suppress] Fix 2 — refill instead of
                // failing silent. When the turn's visible text carried a
                // tool-call markup but nothing parsed as a call, the model DID
                // try to call a tool: the markup arrived as text (character
                // drift on the model / relai side drops the name past every
                // parser). The old path fell through to the break below, which
                // reads as a clean completion — the user sees a run stop
                // mid-task with no banner and no retry. Hand the turn back
                // instead, bounded so a markup-shaped false positive cannot
                // loop forever. Consume-on-read: the flag is per turn, or a
                // later turn that carried no resique would refill on a stale
                // one.
                val resiqueRaw = loopState.toolCallResidueRaw
                loopState.toolCallResidueRaw = null
                if (resiqueRaw != null && loopState.toolCallResidueNudges < ToolCallResiduePolicy.MAX_RESIDUE_REFILL_NUDGES &&
                    // [fix/audit0917-b8] Same gate as the verify nudge above, for
                    // the same reason: a terminal error means the user has
                    // ALREADY been told the run failed (length wall /
                    // deterministic-empty / repetition abort / EOF stub).
                    // `continue` here would revive that run, and a revived turn
                    // that then succeeds leaves the banner and the trace
                    // contradicting the outcome. The refill's own trigger is
                    // markup-shaped text with nothing parsed, which is exactly
                    // what those terminal states produce — so this path is the
                    // LIKELIEST to fire after a terminal error, not the least.
                    !loopState.terminalErrorSurfaced
                ) {
                    val seen = ToolCallResiduePolicy.firstResidue(resiqueRaw, host.agentTools.map { it.name })
                    if (seen != null) {
                        loopState.toolCallResidueNudges++
                        val refill = ToolCallResiduePolicy.refillMessage(seen)
                        AppLogger.warning(
                            TAG_STREAM,
                            "runAgentLoop turn=$turn tool-call resique in visible text " +
                                "(name=${seen.rawName}, sugested=${seen.sugestedName}) — " +
                                "refilling ${loopState.toolCallResidueNudges}/${ToolCallResiduePolicy.MAX_RESIDUE_REFILL_NUDGES} " +
                                "instead of failing through to the break",
                        )
                        val nudgeMsg = LLMMessage(
                            role = LLMMessage.Role.USER,
                            content = refill,
                            contentParts = listOf(AgentContentPart.Text(refill)),
                        )
                        // [fix/same-class-cleanup] Same role-alternation guard
                        // as the verify nudge above — the refill's trigger
                        // (tool-call-shaped markup, nothing parsed) is MOST
                        // common right after a tool-dispatch turn, when the
                        // tail is user(tool_result). A blind USER append would
                        // yield two consecutive user roles (Anthropic hard 400
                        // / OpenAI silent merge).
                        // [audit-1015 BUG-03C] Same trailing blank-shell drop
                        // as the verify nudge (see that block) — the provider
                        // filters blank assistant shells out of the request,
                        // so an undropped shell yields consecutive USER roles.
                        dropTrailingBlankAssistantShell(host.agentHistory)
                        ensureRoleAlternationBeforeUserAppend(host.agentHistory)
                        host.agentHistory.add(nudgeMsg)
                        continue
                    }
                }

                AppLogger.info(TAG_STREAM, "runAgentLoop turn=$turn no tool calls → break (finishReason=$turnFinishReason)")
                // [deep-audit-1017 ISSUE-02] The row finalize (isStreaming=false)
                // used to sit HERE, ~260 lines above the `break`, i.e. BEFORE the
                // four `continue` branches below (empty-turn recovery ×2, EOF-stub
                // continuation, EOF one-shot retry). Every one of those keeps
                // streaming into THIS row — `loopState.assistantId` is minted once
                // per run — and a finalized row rejects every later delta
                // (StaleStreamPublishPolicy.DROP_FINALIZED: canonical
                // isStreaming=false + the side channel drained by this very call,
                // and nothing in `runAgentLoop` ever flips the row back — the
                // isStreaming=true writers only touch `_streamingById`). So the
                // bubble froze at the pre-recovery text for the rest of the run
                // and only refreshed at the next terminal event. It now runs once,
                // immediately before the `break` below.
                // [deep-audit-1016c ISSUE-01] Slice from the run-scoped
                // unpersisted start, NOT from this turn's start: a continuation
                // branch above (EOF-stub / length-wall / verify nudge / residue
                // refill) may have streamed blocks that no persist ever picked
                // up — they ride along in this terminal row instead of being
                // skipped forever.
                val turnParts = host.buildTurnParts(loopState.allToolBlocks, loopState.unpersistedBlockStart, toolInputMap)
                val blockMeta = loopState.allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
                // [deep-audit-1015 BUG-01b] A failure branch above may have
                // queued the turn's terminal banner. Persist THIS turn's row
                // first and surface the banner from the row id that came back:
                // setInlineError persists through updateLastAssistantError =
                // "the session's LAST assistant row", so surfacing before the
                // row exists stamps the banner onto the PREVIOUS turn (and,
                // when the model streamed nothing, onto a turn the user knows
                // succeeded). The empty-text filler keeps the row-present
                // precondition true even for an empty-output failure — the
                // same shape the two early-persist branches above already use.
                val pendingInlineError = loopState.pendingInlineError
                // Consume-once: the slot belongs to THIS turn. Clearing it
                // before the persist (rather than after) means a throwing
                // insert cannot leak the banner into the NEXT turn's row —
                // a DB failure surfaces through the caller's
                // reportAgentLoopError instead, which is the more urgent
                // truth anyway.
                loopState.pendingInlineError = null
                val persistedRowId = host.persistAssistantTurn(
                    TerminalErrorPlacement.partsForTerminalError(turnParts, pendingInlineError),
                    lastUsage, turnReasoningContent, blockMeta,
                    modelId = loopState.currentProvider.model.id,
                    entryId = host.activeEntryId,
                )
                // [deep-audit-1016c ISSUE-01/A] Every block up to now is durable
                // (the row above was written with parts covering the full
                // unpersisted range). Advance only on success — if the DB write
                // produced no row id, the next persist must re-cover this range
                // rather than silently assume it landed.
                if (persistedRowId != null) {
                    loopState.unpersistedBlockStart = loopState.allToolBlocks.size
                    // [deep-audit-1016c ISSUE-01/B] dbId backfill, same shape as
                    // the tool-path terminal below — without it the assistant
                    // message reloads with a null DB anchor and compaction falls
                    // back to a degraded walk. Skip (do NOT invent an entry) when
                    // no matching message exists.
                    val lastAssistantIdx = host.agentHistory.indexOfLast {
                        it.role == LLMMessage.Role.ASSISTANT && it.dbMessageId == null
                    }
                    if (lastAssistantIdx >= 0) {
                        host.agentHistory[lastAssistantIdx] =
                            host.agentHistory[lastAssistantIdx].copy(dbMessageId = persistedRowId)
                    }
                }
                TerminalErrorPlacement
                    .terminalErrorToSurface(pendingInlineError, persistedRowId)
                    ?.let { banner ->
                        withContext(Dispatchers.Main) { host.setInlineError(banner) }
                    }
                // [T-error-persist-android] Empty-response hint: the model ended a
                // turn (finish=stop/end_turn) with no visible text anywhere in the
                // reply and no tool blocks — the user just sees a blank bubble.
                // Surface a hint instead. When the context is near full, point at
                // compaction; otherwise suggest retry/switch. host.setInlineError
                // attaches + persists onto the (empty) assistant row so the hint
                // survives a reload too.
                // [deep-audit-1017 ISSUE-03] Per-TURN visible content, not the
                // run-scoped accumulators. `loopState.accumulatedText` /
                // `loopState.allToolBlocks` are append-only for the whole run,
                // so reading them here answered "did ANY turn show something" —
                // true from the first tool-call turn onward — and the empty-turn
                // recovery below could never fire again. See [TurnVisibleContent].
                val turnVisibleBlocks = loopState.allToolBlocks.drop(turnStartBlockIndex)
                val hasVisibleContent = TurnVisibleContent.hasVisibleContent(turnText, turnVisibleBlocks)
                // [T-truncated-stream-retry] a truncated turn (EOF without
                // finish_reason) is NOT a clean finish even though stopReason is
                // null — the retry branch below owns it, so the empty-turn path
                // must not swallow it with an error hint. That rule lives inside
                // TurnVisibleContent.finishedCleanly; the trigger itself is the
                // one extracted decision:
                if (TurnVisibleContent.isEmptyTurn(turnText, turnVisibleBlocks, turnFinishReason, turnTruncated)) {
                    // [T-android-empty-after-toolresult-reminder] Special case: the
                    // server returned an empty turn. The model owed a follow-up
                    // but stalled — the user sees a blank bubble with no explanation.
                    // Inject a one-shot <system-reminder> into the history tail and
                    // retry ONE round. The guard fires at most once per run, so it
                    // can never loop; the SECOND empty falls through to the error
                    // hint below. Mirrors iOS AIChatViewModel.swift empty-after-
                    // tool-result path.
                    //
                    // [port-v114 S4 / T-android-empty-turn-reminder-any-tail] The
                    // placement decision (which tail part carries the reminder) is
                    // delegated to EmptyTurnRecovery.plan(): prefer the trailing
                    // tool_result (original behaviour, byte-identical for that
                    // case), else a trailing TEXT part (statement / notice tails
                    // the old all-ToolResult guard skipped — those fell through to
                    // the error hint with no retry), else a standalone reminder
                    // turn; an assistant tail (or an already-fired guard) gives up.
                    val recoveryPlan = if (loopState.didInjectEmptyToolReminder) EmptyTurnRecovery.Plan.GiveUp
                        else EmptyTurnRecovery.plan(host.agentHistory, alreadyFired = false)
                    when (val plan = recoveryPlan) {
                        is EmptyTurnRecovery.Plan.NudgeExistingPart -> {
                            loopState.didInjectEmptyToolReminder = true
                            AppLogger.warning(TAG_STREAM, "empty turn — injecting <system-reminder> into the history tail and retrying one round (turn=$turn)")
                            // The empty assistant turn was just appended (above) — drop it
                            // so the nudge lands on the tail being replied to and the model
                            // gets a clean "continue from here" prompt on the retry.
                            host.agentHistory.removeAt(host.agentHistory.size - 1)
                            val tailMsg = host.agentHistory[plan.tailIndex]
                            val reminder = EmptyTurnRecovery.REMINDER
                            val newParts = tailMsg.contentParts.toMutableList()
                            newParts[plan.partIndex] = when (val part = newParts[plan.partIndex]) {
                                is AgentContentPart.ToolResult -> part.copy(content = part.content + reminder)
                                is AgentContentPart.Text -> AgentContentPart.Text(part.text + reminder)
                                else -> part
                            }
                            host.agentHistory[plan.tailIndex] = tailMsg.copy(contentParts = newParts)
                            // Retry a fresh model round with the nudged history.
                            continue
                        }
                        is EmptyTurnRecovery.Plan.AppendStandalone -> {
                            loopState.didInjectEmptyToolReminder = true
                            AppLogger.warning(TAG_STREAM, "empty turn — appending standalone reminder turn and retrying one round (turn=$turn)")
                            // [fix/rv1014-g2] The mutation lives in
                            // EmptyTurnRecovery so it is JVM-testable: it drops
                            // the empty assistant turn, bridges the USER tail
                            // (a blind USER append yields two consecutive user
                            // roles — Gemini maps messages 1:1 with no
                            // consecutive-role merge on the request side),
                            // then appends the standalone reminder turn.
                            EmptyTurnRecovery.appendStandaloneReminderTurn(host.agentHistory, EmptyTurnRecovery.REMINDER)
                            continue
                        }
                        EmptyTurnRecovery.Plan.GiveUp -> {
                            // Fall through to the error hint below.
                        }
                    }
                    val window = host.effectiveContextWindowTokens()
                    val usedCtx = lastUsage?.latestContextTokens ?: 0
                    val contextNearFull = window != null && window > 0 && usedCtx > 0 &&
                        usedCtx.toDouble() / window.toDouble() > 0.70
                    val hint = when {
                        // Reminder already fired and the retry was ALSO empty — this
                        // is a genuine stall, not a transient blank. Point the user
                        // at retry/switch explicitly.
                        loopState.didInjectEmptyToolReminder ->
                            host.string(R.string.error_empty_response_after_tool)
                        contextNearFull ->
                            host.string(R.string.error_empty_response_context_large)
                        else ->
                            host.string(R.string.error_empty_response_generic)
                    }
                    // [deep-audit-1015 BUG-01b — KNOWN RESIDUAL] NOT deferred:
                    // this block runs AFTER the turn tail's flush point, so
                    // queuing here would never be surfaced. Routing it through
                    // pendingInlineError requires moving the flush, which the
                    // retry `continue`s between the persist and the end of the
                    // turn (empty-turn recovery, EOF one-shot retry) rule out.
                    // Same mislabel class as the fixed sites though: a blank
                    // turn persists NO row, so this hint's DB write lands on
                    // the previous turn. Registered in backlog §116.
                    withContext(Dispatchers.Main) { host.setInlineError(hint) }
                }
                // [fix/rv1014-g1] Auto-title: semantic condition, not
                // positional. The old `turn == 0` guard fired the title
                // generation only when the FIRST model round completed without
                // recovery — but a first turn that came back empty (clean
                // finish) goes through the recovery retry as turn 1, so by the
                // time a clean round completed `turn == 0` was already false
                // and the title was skipped until the next send (or forever if
                // the user never sent again). The semantic condition fires
                // while the title is genuinely ungenerated; the host
                // function's own guards keep it idempotent.
                if (host.sessionTitleNeedsGeneration) host.generateSessionTitleIfNeeded()

                // [T-truncated-stream-retry] The provider signalled the model
                // turn ended WITHOUT a server finish_reason (EOF / connection
                // drop mid-stream). The user may have seen a partial answer
                // (or a blank bubble) that never got a proper end.
                //
                // [fix/eof-stub-continuation] Hermes network-stub pattern
                // (agent/conversation_loop.py): KEEP the partial text as the
                // model's own last turn — it is already in agentHistory and
                // accumulatedText, and it is perfectly good content — then
                // append a synthetic user-role reminder anchoring the exact
                // cut point and continue. The legacy behavior DELETED the
                // partial turn and regenerated from scratch (wasting every
                // streamed token; the regeneration frequently re-emitted a
                // different opening — screen-level duplication), and a SECOND
                // EOF broke silently: mid-sentence stop, no hint, user had to
                // type "继续" by hand. Now: continue in-loop up to
                // MAX_EOF_STUB_CONTINUES, then give up with a VISIBLE error.
                // The stacking guard drops a stale stub reminder so consecutive
                // EOFs do not pile up reminder turns (same pattern as the
                // length-wall reminder below).
                // [deep-audit-1017 ISSUE-03] `hasVisibleContent` is THIS turn's
                // (see [TurnVisibleContent]) — the EOF ladder asks "did this
                // truncated turn stream anything", and the run-scoped read made
                // the `else if` below ("blank stream → nothing worth keeping")
                // unreachable for any run whose earlier turns had shown text.
                if (turnTruncated && hasVisibleContent) {
                    // [feat/runtime-limits-panel] 同上：上限改读 prefs（默认 2）。
                    val eofStubMax = com.rikkaminis.app.data.AgentRuntimeLimitsPrefs.eofStubContinues()
                    if (loopState.eofStubContinues < eofStubMax) {
                        loopState.eofStubContinues++
                        AppLogger.warning(
                            TAG_STREAM,
                            "runAgentLoop turn=$turn EOF-truncated stream (${turnText.length} chars kept, mid-sentence=${looksLikeMidSentenceCut(turnText)}) — network-stub continuation ${loopState.eofStubContinues}/$eofStubMax",
                        )
                        // Drop any stale stub reminder from a previous EOF so
                        // reminders never stack (guard mirrors length-wall).
                        // [fix/audit-b20 / T1-LOW-2] Same off-by-one as the
                        // length-wall guard: the previous stub reminder lives
                        // behind this turn's assistant message.
                        // [deep-audit-1018 ISSUE-12c] Predicate extracted to
                        // [EofStubReminderStackGuard] — the error-shaped branch
                        // above now shares it (it had no guard at all).
                        val prevTailIdx = host.agentHistory.size - 2
                        val prevTail = host.agentHistory.getOrNull(prevTailIdx)
                        if (EofStubReminderStackGuard.isStaleEofStubReminder(prevTail)) {
                            host.agentHistory.removeAt(prevTailIdx)
                        }
                        val stubReminder = eofStubReminder(turnText.takeLast(80))
                        host.agentHistory.add(
                            LLMMessage(
                                role = LLMMessage.Role.USER,
                                content = stubReminder,
                                contentParts = listOf(AgentContentPart.Text(stubReminder)),
                            )
                        )
                        // Reset the seam-dedup marker: the NEXT turn is a
                        // continuation of THIS truncation, exactly like a
                        // length-wall continuation (head-overlap trim applies).
                        // lastTurnWasLengthWall was already set by the shared
                        // seam logic above only for finish_reason=="length";
                        // EOF truncation needs the same treatment.
                        loopState.lastTurnWasLengthWall = true
                        continue
                    }
                    AppLogger.warning(
                        TAG_STREAM,
                        "runAgentLoop turn=$turn EOF-truncated ×$loopState.eofStubContinues (ceiling $MAX_EOF_STUB_CONTINUES) — giving up with visible error",
                    )
                    withContext(Dispatchers.Main) {
                        host.setInlineError(host.string(R.string.error_output_truncated_repeated))
                    }
                    // [audit-0907 B5] Failure-terminal (specific banner shown)
                    // — prevents the exit-side runaway misclassification from
                    // overwriting it.
                    loopState.terminalErrorSurfaced = true
                    // Fall through to the normal break path (persist + exit)
                    // so the partial answer the user already saw is persisted.
                } else if (turnTruncated && !loopState.didRetryTruncatedTurn) {
                    // EOF with NO visible content (blank/whitespace stream):
                    // there is nothing worth keeping, so the legacy one-shot
                    // retry (drop the empty assistant turn, regenerate) is
                    // still the right move. didRetryTruncatedTurn keeps its
                    // original one-shot semantics on this path.
                    loopState.didRetryTruncatedTurn = true
                    AppLogger.warning(
                        TAG_STREAM,
                        "runAgentLoop turn=$turn EOF-truncated stream with no visible content — one-shot retry (drop empty turn)",
                    )
                    host.agentHistory.removeAt(host.agentHistory.size - 1)
                    continue
                }

                // T9: close out the final turn (no tool calls → normal completion)
                traceObserver.agentTraceRecorder.turnEnd(
                    turn = turn,
                    tokensIn = lastUsage?.inputTokens,
                    tokensOut = lastUsage?.outputTokens,
                    finishReason = turnFinishReason,
                    durationMs = System.currentTimeMillis() - turnStartMs,
                )
                // [deep-audit-1015 BUG-01a] A clean completion is claimed only
                // when the turn did not already surface a terminal error. The
                // failure-terminal branches above deliberately fall through to
                // this tail (that is how their partial reply gets persisted),
                // and the old unconditional assignment rewrote their outcome as
                // "the model finished its reply" — T7 then recorded
                // SUCCEEDED/COMPLETED for a run the user was told had failed.
                // The fall-through is preserved; only the claim is gated. The
                // audit-0907 B5 runaway guard is unaffected: it reads
                // terminalErrorSurfaced, not this flag (see
                // AgentLoopExitDecision.shouldFinalizeAsRunaway).
                // [deep-audit-1017 ISSUE-02] The single row finalize for this
                // turn — after ALL four `continue` branches above, so the row
                // stays streaming for the recovery/continuation that follows
                // them (see the note at the head of this block). Ordering is
                // unchanged for every path that reaches here: the turn was
                // persisted above (persistAssistantTurn), banners
                // (pendingInlineError / empty-response hint / EOF give-up)
                // were surfaced above, and `updateAssistantMessage(false)` does
                // not touch `error`, so a banner stamped on the row survives it.
                //
                // It sits ABOVE the clean-completion gate, not below the guard's
                // closing brace: TerminalErrorSurfacedTest's negative control
                // mutates the source by the exact adjacency of the guard's close
                // followed by the break, so anything parked between that `}` and
                // the `break` would silently disarm that control (2026-10-16 CI
                // #2168: the control failed with "mutation must change the
                // source" for exactly this reason).
                withContext(Dispatchers.Main) {
                    host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, false, loopState.allToolBlocks)
                }
                if (AgentLoopExitDecision.cleanCompletionFlag(loopState.terminalErrorSurfaced)) {
                    loopState.loopExitedNormally = true
                    // T7-D: 旁路验证 —— 工具序列完成，进入收尾
                    traceObserver.t7Reduce(AgentRunEvent.WorkCompleted)
                }
                break
            }
            // [audit2 BUG-01] Only a turn that ACTUALLY produced tool calls may
            // clear the continuation budgets — that is what the comments below
            // always claimed ("a tool-call turn …"), and what this guard now
            // enforces mechanically. Before the move, the three resets ran
            // unconditionally ~640 lines above (before the empty-turn branch),
            // so `lengthWallContinues` / `deterministicEmptyStreak` /
            // `eofStubContinues` never got past 1 and the length-wall,
            // deterministic-empty and EOF-stub guards were all dead; only
            // `maxTurns` (default 256) still bounded the loop.
            // The condition is written out rather than assumed: most paths
            // through the empty-turn branch above leave the loop early
            // (`continue`, e.g. the EOF one-shot retry at the tail) but not all
            // of them — the normal-completion tail ends in `break` — so the
            // reset must be guarded explicitly. The guard is what
            // scripts/scan/loop_counter_reset_guard.py machine-checks; do not
            // "simplify" it away on the strength of a comment.
            if (toolCalls.isNotEmpty()) {
                // [feat/hermes-tier1] A tool-call turn clears the length-wall
                // continuation budget AND the deterministic-empty streak: the
                // model recovered and is doing new work, so future walls get a
                // fresh allowance (mirrors Hermes resetting per-wall retry state).
                loopState.lengthWallContinues = 0
                loopState.deterministicEmptyStreak = 0
                // [fix/eof-stub-continuation] A tool-call turn is proof the model
                // produced new work after any EOF — reset the stub-continuation
                // budget so long tool-heavy runs keep full allowance.
                loopState.eofStubContinues = 0
            }
            AppLogger.info(TAG_STREAM, "runAgentLoop turn=$turn dispatching ${toolCalls.size} tool call(s), continuing")
            // [fix/tool-call-copy-suppress] The turn DID dispatch: whatever
            // markup its text carried was a copy of a call that went through,
            // so forget it — only a turn that dispatched NOTHING may refill.
            loopState.toolCallResidueRaw = null

            // [T-android-session-last-message-live-tool-call] Push a live
            // preview to the session list NOW, before the (possibly long-
            // running) tools execute. The authoritative assistant row isn't
            // written until turn end (host.persistAssistantTurn below), so without
            // this the home list shows a stale preview — or "No messages yet"
            // for a turn that opened with a tool call and no prior text —
            // for the entire tool duration. extractTextPreview prefers the
            // assistant's partial text and falls back to the tool summary, so
            // the list reflects exactly what the model just emitted. Mirrors
            // iOS overlaying the live VM's last message over the DB value.
            // [fix/early-turn-persist] Row id written BEFORE the tools run (see
            // the dispatch block below); the post-tool persist site updates it
            // in place instead of inserting a second row for the same turn.
            var earlyAssistantDbId: String? = null
            run {
                // [deep-audit-1016c ISSUE-01] Live preview + early persist carry the
            // same unpersisted range as the terminal slice: on a turn whose run
            // already has unpersisted continuation blocks, the early row must
            // include them (kill-during-tool-exec keeps the earlier text), and
            // the terminal update below rewrites the row from the same basis.
            val livePreviewParts = host.buildTurnParts(loopState.allToolBlocks, loopState.unpersistedBlockStart, toolInputMap)
                val liveMeta = loopState.allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
                if (livePreviewParts.isNotEmpty()) {
                    host.updateSessionPreview(
                        host.buildAssistantPartsJson(livePreviewParts, liveMeta),
                    )
                    // [fix/early-turn-persist] Make the turn durable *now*
                    // rather than after the tools return. Tool execution is the
                    // longest single stretch of a turn (minutes for sandbox
                    // calls) and it used to hold the ONLY copy of text the user
                    // had already read: a kill inside that window (app update,
                    // swipe-away, OOM) erased the whole turn and left the
                    // session tail on a tool_result row, which cold start then
                    // reported as "paused". Worst case now is a missing tool
                    // outcome on a row whose text is intact.
                    earlyAssistantDbId = host.persistAssistantTurn(
                        livePreviewParts, lastUsage, turnReasoningContent, liveMeta,
                        modelId = loopState.currentProvider.model.id,
                        entryId = host.activeEntryId,
                    )
                    earlyAssistantDbId?.let { earlyId ->
                        val earlyIdx = host.agentHistory.indexOfLast {
                            it.role == LLMMessage.Role.ASSISTANT && it.dbMessageId == null
                        }
                        if (earlyIdx >= 0) {
                            host.agentHistory[earlyIdx] = host.agentHistory[earlyIdx].copy(dbMessageId = earlyId)
                        }
                    }
                }
            }

            // Execute all tool calls
            val resultParts = mutableListOf<AgentContentPart>()
            // [T-sensitive-transcript] tool-call id -> replacement text for the
            // PERSISTED copy only. Populated while the call is in flight (the
            // command line exists nowhere else), consumed by the
            // persistToolResultMessage call at the end of the turn.
            val transcriptRedactions = mutableMapOf<String, String>()

            // ------------------------------------------------------------------
            // Tool dispatch — split into passes so a batch of read-only tools
            // (file_read / read_image) can run concurrently.
            //
            // Pass 1 (sequential): per-call preflight + loop-detect. CRITICAL /
            // preflight rejections synthesize their tool_result right here and
            // `continue` — exactly as the original loop did. Calls that pass are
            // collected into `pending`. Pass 1 publishes ONLY refusals: it never
            // flips a call to RUNNING ([deep-audit-1019 ISSUE-02] — see
            // ToolPassTransitionPolicy for why the flip lives in Pass 2).
            // Pass 2 (execute): a batch of ONLY parallel-safe tools runs
            // concurrently (async, awaited in original order). Anything else runs
            // sequentially — identical to the old single-loop behavior. This is
            // where PENDING → RUNNING is published, at the moment each call is
            // actually dispatched.
            // Pass 3 (sequential): per-call post-execution — loop-detect.record,
            // block content/status update, resultParts, UI refresh — kept in the
            // original tool-call order.
            //
            // Observable behavior is unchanged vs the old loop; only the
            // wall-clock time of Pass 2 varies. Pass 1/3 stay sequential so
            // loop-detect ordering and block update semantics never race. Pass 2
            // parallel tools are pure reads that never mutate shared state.
            // ------------------------------------------------------------------
            data class PendingTool(
                val id: String,
                val name: String,
                val args: JSONObject,
                val argsStr: String,
                val paramsMap: Map<String, Any?>,
            )
            val pending = mutableListOf<PendingTool>()

            // ============================ Pass 1 ============================
            // [T-android-tool-dedupe] Same-turn dedupe of identical tool
            // calls (same toolName + same args, ignoring cosmetic UI fields
            // like tool_title). A model occasionally emits the SAME tool call
            // twice in one turn — previously each call executed independently:
            // parallel-safe tools (file_read/read_image) ran twice
            // concurrently, everything else serialized in the queue with no
            // visible "waiting" cue. Now the FIRST occurrence executes and
            // every identical duplicate is dropped with a synthetic
            // tool_result (same id) so tool_use/tool_result pairing stays
            // balanced and the model is told not to re-issue. Cross-turn
            // duplicates remain ToolLoopDetector's job (10-warn / 20-block).
            val sameTurnFingerprints = mutableMapOf<String, String>()
            for ((id, name, args) in toolCalls) {
                // [T-android-tool-dedupe] Same-turn dedupe check FIRST —
                // identical calls are dropped before any preflight, tool
                // status flip, or loop-detector bookkeeping runs.
                val dedupeFingerprint = toolCallDedupeFingerprint(name, args)
                val firstId = sameTurnFingerprints[dedupeFingerprint]
                if (firstId != null && firstId != id) {
                    AppLogger.warning(
                        TAG_STREAM,
                        "[ToolDedupe] same-call duplicate tool dropped: name=$name id=$id dup-of=$firstId",
                    )
                    // Skip ALL Pass 1 logic for the duplicate — no preflight,
                    // no pending, no loop-detector record.
                    val dupBlockIdx = loopState.allToolBlocks.indexOfFirst { it.id == id }
                    if (dupBlockIdx >= 0) {
                        loopState.allToolBlocks[dupBlockIdx] = loopState.allToolBlocks[dupBlockIdx].copy(
                            // [T-dedup-neutral-status] DEDUPLICATED (not FAILED):
                            // the call was dropped on purpose because an
                            // identical call already ran — rendering it as a
                            // red error misled users into thinking the tool
                            // broke. Neutral grey "skipped" styling matches
                            // the intent; the model-facing synthetic
                            // tool_result below is unchanged.
                            toolStatus = ToolBlockStatus.DEDUPLICATED,
                            content = host.string(R.string.tool_dedup_skipped),
                            durationMs = 0,
                        )
                        // [T-dedup-neutral-status] PUSH the flip immediately —
                        // the loop-detector and preflight blocked branches both
                        // publish their terminal flip, but this branch used to
                        // fall through silently, leaving the dropped block
                        // stuck on PENDING (spinner / "waiting") on screen
                        // until the turn's next bulk publish — the exact
                        // "occupying space, never runs" symptom.
                        withContext(Dispatchers.Main) {
                            host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                        }
                    }
                    resultParts.add(AgentContentPart.ToolResult(
                        id = id,
                        name = name,
                        content = "Deduplicated: identical tool call already executed as $firstId (its result was returned above). Do not re-issue this tool call.",
                        isError = false,
                    ))
                    continue
                }
                sameTurnFingerprints[dedupeFingerprint] = id
                // [T-truncated-tool-call-guard] A turn that hit the output
                // ceiling (finish_reason=length / max_tokens) may have been cut
                // off WHILE this call's arguments were still streaming. Until
                // this guard the dispatch path never consulted turnFinishReason
                // — it is read only inside the `toolCalls.isEmpty()` branch
                // above — so a prefix payload reached the executor. And
                // ToolJsonRepair's truncation strategy makes that sharper, not
                // safer: it deliberately CLOSES a cut-off JSON object, turning
                // `{"path": "/data/local/tmp/fo` into a syntactically valid,
                // semantically wrong argument. Refusing costs one re-planned
                // turn; executing costs an unknown side effect. Same shape as
                // the dedupe / loop-detector refusals below so that tool_use
                // and tool_result stay paired for the next request.
                //
                // Place it BEFORE ToolJsonRepair: refusing on the raw payload
                // means the refusal can never depend on a repaired one.
                //
                // [deep-audit-1018 ISSUE-01] The guard consults BOTH truncation
                // sources: turnFinishReason (the provider declared the ceiling)
                // and this turn's `turnTruncated` flag (stream died mid-flight
                // with no finish_reason — see OpenAIProvider's EOF guard, which
                // had to learn to count tool-call events before a tool-call-only
                // turn could set it). finishReason alone missed the EOF class
                // entirely, which is exactly the half-streamed-argument case.
                //
                // Accepted residual risk: this branch does NOT charge the
                // length-wall continuation budget (that counter is only
                // advanced on the `toolCalls.isEmpty()` side), so a model that
                // re-issues a call on every ceiling hit is bounded only by
                // maxTurnsThisRun. Buying that with a new shared counter is not
                // worth the extra state: the refusal message tells the model to
                // re-issue with FULL arguments, and executing a prefix argument
                // is strictly worse than spending turns.
                val truncatedRefusal = TruncatedToolCallPolicy.rejectionReason(name, turnFinishReason, turnTruncated)
                if (truncatedRefusal != null) {
                    AppLogger.warning(
                        TAG_STREAM,
                        "runAgentLoop turn=$turn truncated tool call REFUSED name=$name id=$id finish=$turnFinishReason eofTruncated=$turnTruncated",
                    )
                    val truncatedIdx = loopState.allToolBlocks.indexOfFirst { it.id == id }
                    if (truncatedIdx >= 0) {
                        loopState.allToolBlocks[truncatedIdx] = loopState.allToolBlocks[truncatedIdx].copy(
                            toolStatus = ToolBlockStatus.FAILED,
                            content = truncatedRefusal,
                            durationMs = 0,
                        )
                        withContext(Dispatchers.Main) {
                            host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                        }
                    }
                    resultParts.add(AgentContentPart.ToolResult(
                        id = id,
                        name = name,
                        content = truncatedRefusal,
                        isError = true,
                    ))
                    continue
                }
                // [T-sensitive-transcript] Record this call's provenance while
                // the command line is still in hand — ToolResult carries no
                // command and the persisted parts JSON never sees one, so
                // deferring this to backup-export time would leave nothing to
                // filter on. Placed after the truncation guard on purpose:
                // a call that will not execute produces an error message, not
                // a payload worth withholding. See SensitiveCommandPolicy.
                if (name == "shell_execute") {
                    val command = if (args.has("command")) args.optString("command") else null
                    if (SensitiveCommandPolicy.shouldRedactFromTranscript(command)) {
                        transcriptRedactions[id] = SensitiveCommandPolicy.redactionPlaceholder(command)
                    }
                }
                // [T-android-overlay-tool-title] Pull tool_title uniformly
                // from args for ALL tools — without this browser_use's
                // tool_title never reached the overlay (only shell_execute
                // had a per-tool status override that surfaced it). Reading
                // it here also means new tools added later automatically
                // get title-in-overlay behavior without per-call plumbing.
                val dispatchToolTitle = try {
                    args.optString("tool_title", "").takeIf { it.isNotBlank() }
                } catch (_: Exception) { null }
                SessionActivityTracker.updateToolStatus(
                    status = "Running: $name",
                    toolName = name,
                    isRunning = true,
                    toolTitle = dispatchToolTitle,
                )
                // JSON repair (T-tool-json-repair b2c4f8a6): salvage truncated /
                // type-mismatched / typo'd args BEFORE preflight rejects them.
                // Mutates `args` in place; downstream argsStr and preflight see
                // the repaired payload. Mirrors iOS repairToolArgs in
                // AIChatViewModel.swift.
                val repairs = com.rikkaminis.app.provider.ToolJsonRepair.repair(
                    name, args, loopState.toolInputChunkRings[id]?.lastOrNull(), host.agentTools,
                )
                if (repairs.isNotEmpty()) {
                    AppLogger.warning(
                        "ToolPreflight",
                        "[ToolRepair] REPAIRED tool=$name id=$id strategies=[${repairs.joinToString(", ")}] " +
                            "argsKeys=[${args.keys().asSequence().toList().sorted().joinToString(",")}] " +
                            "rawTail=<<<${loopState.toolInputChunkRings[id]?.lastOrNull()?.take(500) ?: ""}>>>"
                    )
                }
                val argsStr = args.toString()
                val paramsMap = parseToolParams(argsStr)
                // [deep-audit-1019 ISSUE-02] No status flip here any more. Pass 1
                // only DECIDES whether a call may run; the PENDING → RUNNING flip
                // belongs to the pass that actually dispatches it, because
                // publishing RUNNING above the loop detector + preflight gates
                // left a refused call spinning (the CRITICAL path retracts only in
                // memory) and consumed the PENDING state that Pass 2's waiting cue
                // is guarded on. See ToolPassTransitionPolicy.

                // Loop-detector check BEFORE execution. CRITICAL outcomes short-circuit
                // the call: synthesize an error result so the tool_use/tool_result pair
                // stays balanced and the LLM sees the block reason.
                val precheck = host.toolLoopDetector.check(name, paramsMap)
                if (precheck.level == Level.CRITICAL) {
                    val blockedMsg = precheck.message ?: "[LOOP BLOCKED] tool execution blocked"
                    android.util.Log.w("ToolChain[VM]",
                        "[turn=$turn] tool BLOCKED by loop detector name=$name msg=$blockedMsg")
                    AppLogger.warning("ChatViewModel",
                        "tool blocked by loop detector name=$name reason=$blockedMsg")
                    val blockIdx = loopState.allToolBlocks.indexOfFirst { it.id == id }
                    if (blockIdx >= 0) {
                        val elapsed = System.currentTimeMillis() - loopState.allToolBlocks[blockIdx].startTimeMs
                        loopState.allToolBlocks[blockIdx] = loopState.allToolBlocks[blockIdx].copy(
                            toolStatus = ToolBlockStatus.FAILED,
                            content = blockedMsg,
                            durationMs = elapsed,
                        )
                        // [deep-audit-1019 ISSUE-02] Publish the refusal. Every
                        // sibling Pass-1 refusal path does (dedupe / truncation /
                        // preflight); this one published nothing, so with the flip
                        // that used to run above it the card kept spinning and the
                        // user never saw that the call had been blocked.
                        withContext(Dispatchers.Main) {
                            host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                        }
                    }
                    // Record the blocked attempt so consecutive blocks still
                    // count toward the unknown-tool / circuit-breaker windows.
                    host.toolLoopDetector.record(name, paramsMap,
                        result = null, errorMessage = blockedMsg, toolCallId = id)
                    resultParts.add(AgentContentPart.ToolResult(
                        id = id, name = name,
                        content = blockedMsg,
                        isError = true,
                    ))
                    continue
                }

                // Preflight: reject empty / missing-required-field tool calls
                // BEFORE the UI flips to RUNNING and BEFORE host.executeTool() does
                // any actual work. Mirrors iOS host.preflightValidateToolCall in
                // AIChatViewModel.swift. Synthesizes a tool_result error so the
                // model can self-correct on the next turn without us spawning
                // shells or touching the filesystem on `{}` args.
                //
                // [deep-audit-1019 ISSUE-02] That ordering promise is true again:
                // the flip runs in Pass 2, below both refusal gates. Until this
                // audit the flip sat ABOVE this check, so "before the UI flips to
                // RUNNING" described a state the code never reached.
                val preflightError = host.preflightValidateToolCall(name, args, host.agentTools)
                if (preflightError != null) {
                    val chunkRing: List<String> = loopState.toolInputChunkRings.remove(id) ?: emptyList()
                    AppLogger.warning(
                        "ToolPreflight",
                        "BLOCKED tool=$name id=$id reason=\"$preflightError\" " +
                            "argsKeys=[${args.keys().asSequence().toList().sorted().joinToString(",")}] " +
                            "chunkCount=${chunkRing.size} " +
                            "lastChunk=<<<${chunkRing.lastOrNull()?.take(500) ?: ""}>>>"
                    )
                    chunkRing.forEachIndexed { i, snap ->
                        AppLogger.warning(
                            "ToolPreflight",
                            "  chunk[$i] bytes=${snap.toByteArray(Charsets.UTF_8).size} raw=<<<${snap.take(500)}>>>"
                        )
                    }
                    // English literal — string resource lookup intentionally
                    // avoided to keep this commit independent of any in-flight
                    // strings.xml refactor in other sessions. Promote to a
                    // localized R.string entry in a follow-up if needed.
                    val uiMessage = "Blocked invalid tool call"
                    val modelMessage = "Error: Tool call rejected before execution. $preflightError " +
                        "Correct the arguments so they match the tool's published schema, then re-issue the call. " +
                        "Do not retry with the same arguments."
                    val blockIdxPre = loopState.allToolBlocks.indexOfFirst { it.id == id }
                    if (blockIdxPre >= 0) {
                        val elapsedPre = System.currentTimeMillis() - loopState.allToolBlocks[blockIdxPre].startTimeMs
                        loopState.allToolBlocks[blockIdxPre] = loopState.allToolBlocks[blockIdxPre].copy(
                            toolStatus = ToolBlockStatus.FAILED,
                            content = uiMessage,
                            durationMs = elapsedPre,
                        )
                    }
                    host.toolLoopDetector.record(
                        toolName = name, params = paramsMap,
                        result = null, errorMessage = modelMessage, toolCallId = id
                    )
                    resultParts.add(AgentContentPart.ToolResult(
                        id = id, name = name,
                        content = modelMessage,
                        isError = true,
                    ))
                    withContext(Dispatchers.Main) {
                        host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                    }
                    continue
                }

                pending.add(PendingTool(id, name, args, argsStr, paramsMap))
            }

            // ============================ Pass 2 ============================
            val resultsById = LinkedHashMap<String, ToolExecutionResult>()
            // [T-tool-duration-per-call] Wall time measured around each actual
            // host.executeTool call. Pass 3 used to derive durationMs as
            // (now − block.startTimeMs), but startTimeMs is stamped when the
            // model *starts streaming the tool_use args* and Pass 3 runs after
            // the whole batch, so every tool in a batch was charged the model's
            // streaming tail PLUS the entire batch's runtime — a sub-second
            // command in a batch containing one slow tool displayed that slow
            // tool's duration (measured: 5.4s shown for a 5ms command).
            val execMsById = java.util.concurrent.ConcurrentHashMap<String, Long>()
            if (pending.size > 1 && pending.all { ToolConcurrencyPolicy.isParallelSafe(it.name, it.argsStr) }) {
                // All pending tools are parallel-safe pure reads. Launch them
                // concurrently, then pull each result in the original order so
                // Pass 3 observes the same sequence as the old sequential loop.
                // [audit2 DESIGN-03] Each call gets its OWN block-list copy —
                // the shared `loopState.allToolBlocks` is never handed to a
                // coroutine on the IO pool. Edits (none today: the whitelist is
                // read-only) are merged back once, in `pending` order, on this
                // thread after the batch. See ParallelToolBatchBlocks.
                val sharedBlocks = loopState.allToolBlocks
                // [deep-audit-1019 ISSUE-02] A parallel batch dispatches every
                // queued call at once, so the whole batch is RUNNING *here*, before
                // the forks are taken — the baseline snapshot must carry the flip
                // (Pass 3 finalises blocks that never showed a start otherwise),
                // and it is published once from this thread because the point of
                // the forks is that no coroutine touches this list.
                var flippedForBatch = false
                for (p in pending) {
                    val flipIdx = sharedBlocks.indexOfFirst { it.id == p.id }
                    if (flipIdx < 0) continue
                    val flippedStatus = ToolPassTransitionPolicy.statusAtDispatch(sharedBlocks[flipIdx].toolStatus)
                    if (flippedStatus != null) {
                        sharedBlocks[flipIdx] = sharedBlocks[flipIdx].copy(toolStatus = flippedStatus)
                        flippedForBatch = true
                    }
                }
                if (flippedForBatch) {
                    withContext(Dispatchers.Main) {
                        host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                    }
                }
                val baseline = ParallelToolBatchBlocks.snapshot(sharedBlocks)
                val deferred = coroutineScope {
                    pending.map { p ->
                        val callBlocks = ParallelToolBatchBlocks.forkForCall(baseline)
                        Triple(p.id, callBlocks, async {
                            val execStart = System.currentTimeMillis()
                            val r = host.executeTool(p.name, p.argsStr, p.id, callBlocks, loopState.assistantId, loopState.accumulatedText)
                            execMsById[p.id] = System.currentTimeMillis() - execStart
                            r
                        })
                    }
                }
                val workedLists = ArrayList<List<AssistantBlock>>(deferred.size)
                for ((pid, callBlocks, d) in deferred) {
                    resultsById[pid] = d.await()
                    workedLists.add(callBlocks)
                }
                ParallelToolBatchBlocks.mergeBack(sharedBlocks, baseline, workedLists)
            } else {
                // Any non-parallel tool (or a single call) → sequential, exactly
                // like the original loop.
                //
                // [deep-audit-1019 ISSUE-02] A queued call is published TWICE and
                // the order carries the contract:
                //   1. at batch entry — every call behind the head keeps PENDING
                //      and says it is waiting, so the batch reads as a queue while
                //      the head runs;
                //   2. at that call's own turn — it flips PENDING → RUNNING and the
                //      cue is retracted.
                // Step 1 is the branch that used to be dead: Pass 1 flipped every
                // queued call to RUNNING before Pass 2 ran, so the PENDING guard
                // below matched nothing and the cue never appeared.
                val cueIndices = ToolPassTransitionPolicy.sequentialQueueCueIndices(pending.size)
                if (cueIndices.isNotEmpty()) {
                    var cued = false
                    for (index in cueIndices) {
                        val queued = pending.getOrNull(index)
                        if (queued == null) continue
                        val cueIdx = loopState.allToolBlocks.indexOfFirst { it.id == queued.id }
                        if (cueIdx < 0) continue
                        val cueBlock = loopState.allToolBlocks[cueIdx]
                        if (cueBlock.toolStatus != ToolBlockStatus.PENDING) continue
                        loopState.allToolBlocks[cueIdx] = cueBlock.copy(
                            content = ToolPassTransitionPolicy.QUEUE_CUE_TEXT,
                        )
                        cued = true
                    }
                    if (cued) {
                        withContext(Dispatchers.Main) {
                            host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                        }
                    }
                }
                pending.forEach { p ->
                    // Flip PENDING → RUNNING right before this call's own execute
                    // dispatch so the UI (tool pill spinner) shows the exact moment
                    // execution begins. Moved here from Pass 1 by
                    // [deep-audit-1019 ISSUE-02] — see ToolPassTransitionPolicy; the
                    // flip also retires this call's queue cue (contentAtDispatchFlip).
                    val flipIdx = loopState.allToolBlocks.indexOfFirst { it.id == p.id }
                    if (flipIdx >= 0) {
                        val flippingBlock = loopState.allToolBlocks[flipIdx]
                        val flippedStatus = ToolPassTransitionPolicy.statusAtDispatch(flippingBlock.toolStatus)
                        if (flippedStatus != null) {
                            loopState.allToolBlocks[flipIdx] = flippingBlock.copy(
                                toolStatus = flippedStatus,
                                content = ToolPassTransitionPolicy.contentAtDispatchFlip(
                                    flippingBlock.content,
                                    ToolPassTransitionPolicy.QUEUE_CUE_TEXT,
                                ),
                            )
                            withContext(Dispatchers.Main) {
                                host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                            }
                        }
                    }
                    val execStart = System.currentTimeMillis()
                    resultsById[p.id] = host.executeTool(p.name, p.argsStr, p.id, loopState.allToolBlocks, loopState.assistantId, loopState.accumulatedText)
                    execMsById[p.id] = System.currentTimeMillis() - execStart
                }
            }

            // ============================ Pass 3 ============================
            for (p in pending) {
                val id = p.id
                val name = p.name
                val paramsMap = p.paramsMap
                val result = resultsById[id]!!
                android.util.Log.d("ToolChain[VM]", "[turn=$turn] host.executeTool END name=$name success=${result.success} title=${result.toolTitle} outputLen=${result.output.length} output=${result.output.take(200)}")

                // Record post-execution. WARNING text is appended to the tool
                // result so the model sees it on its next turn. No block here —
                // CRITICAL only fires from check() and we already returned above.
                val errMsgForDetector = if (!result.success) result.output else null
                val postRecord = host.toolLoopDetector.record(
                    toolName = name,
                    params = paramsMap,
                    result = if (result.success) result.output else null,
                    errorMessage = errMsgForDetector,
                    toolCallId = id,
                )
                val outputForLLM = if (postRecord.level == Level.WARNING && postRecord.message != null) {
                    AppLogger.debug("ChatViewModel",
                        "appending loop-warning to tool result name=$name key=${postRecord.warningKey}")
                    "${result.output}\n\n${postRecord.message}"
                } else {
                    result.output
                }

                // [feat/verification-stop] edit/verify bookkeeping. Edit side:
                // a SUCCESSFUL file_write/file_edit on a code path records the
                // edit. Verify side: a verification-shaped shell command
                // records its outcome and, when it PASSED, records the
                // verification. Both stamps are readings of ONE monotonic clock
                // so the turn-end guard can order them (see
                // AgentLoopState.evidenceClock for why two independent
                // counters could not answer "was the verification newer?").
                // Non-code paths (prose/config) are filtered by the policy so
                // a SKILL.md edit never demands a verification script.
                when {
                    result.success && (name == "file_write" || name == "file_edit") -> {
                        VerificationStopPolicy.changedPathFromArgs(name, p.argsStr)?.let { path ->
                            if (!VerificationStopPolicy.isNonCodePath(path)) {
                                loopState.changedCodePaths.add(path)
                                loopState.evidenceClock++
                                loopState.lastEditStamp = loopState.evidenceClock
                            }
                        }
                    }
                    name == "shell_execute" -> {
                        val cmd = try { JSONObject(p.argsStr).optString("command", "") } catch (_: Exception) { "" }
                        val kind = VerificationStopPolicy.verificationKind(cmd)
                        if (kind != null) {
                            val outcome = when {
                                result.success -> "PASSED"
                                result.timedOut -> "TIMED OUT"
                                else -> "FAILED"
                            }
                            loopState.lastVerificationDetail = "${cmd.take(120)} → $outcome"
                            if (result.success) {
                                loopState.evidenceClock++
                                loopState.lastVerifyStamp = loopState.evidenceClock
                            }
                            AppLogger.info(TAG_STREAM, "[verification-stop] evidence: kind=$kind outcome=$outcome cmd=${cmd.take(80)}")
                        }
                    }
                }

                val blockIdx = loopState.allToolBlocks.indexOfFirst { it.id == id }
                if (blockIdx >= 0) {
                    // Measured around this tool's own host.executeTool call —
                    // NOT (now − startTimeMs), which is stamped at tool_use
                    // streaming start and would charge this tool the whole
                    // batch's runtime plus the model's streaming tail.
                    val elapsed = execMsById[id]
                        ?: (System.currentTimeMillis() - loopState.allToolBlocks[blockIdx].startTimeMs)
                    // Keep live-streamed content if it has more data than the truncated result.
                    // T263: takeLast(80) was applied uniformly, but it was sized for
                    // shell_execute (long stdout streams where the tail is what
                    // matters). For tools whose first line carries metadata —
                    // file_read's `[path | N bytes | M lines | showing A-B of M]`
                    // banner, file_write/file_edit confirmations, memory_* /
                    // browser_use structured headers — clipping the head dropped
                    // the banner entirely. iOS routes file_read through a
                    // dedicated branch (AIChatViewModel.swift:5229) and avoids
                    // this; mirror that intent by gating the trim to shell_execute.
                    val existingContent = loopState.allToolBlocks[blockIdx].content
                    val resultContent = if (name == "shell_execute") {
                        result.output.lines().takeLast(80).joinToString("\n")
                    } else {
                        result.output
                    }
                    val finalContent = if (existingContent.length > resultContent.length) existingContent else resultContent
                    val finalStatus = when {
                        result.success -> ToolBlockStatus.SUCCESS
                        result.timedOut -> ToolBlockStatus.TIMEOUT
                        else -> ToolBlockStatus.FAILED
                    }
                    // T-bg-overlay phase 1: tool finished — drop the
                    // notification's indeterminate progress bar so the
                    // user can tell streaming has paused (LLM step) vs
                    // a tool is in flight.
                    // [T-overlay-glyph-typed-outcome] Pass the typed
                    // outcome so the bg overlay glyph reflects the real
                    // SUCCESS / TIMEOUT / FAILED result instead of
                    // text-sniffing the stale "Running: foo" status.
                    val toolOutcome = when (finalStatus) {
                        ToolBlockStatus.SUCCESS -> com.rikkaminis.app.service.ToolOutcome.Success
                        ToolBlockStatus.TIMEOUT -> com.rikkaminis.app.service.ToolOutcome.Timeout
                        ToolBlockStatus.FAILED -> com.rikkaminis.app.service.ToolOutcome.Error
                        else -> com.rikkaminis.app.service.ToolOutcome.Unknown
                    }
                    SessionActivityTracker.clearToolRunning(toolOutcome)
                    android.util.Log.d("ToolChain[VM]", "[turn=$turn] block[$blockIdx] status→$finalStatus title=${result.toolTitle} contentLen=${finalContent.length}")
                    loopState.allToolBlocks[blockIdx] = loopState.allToolBlocks[blockIdx].copy(
                        toolStatus = finalStatus,
                        content = finalContent,
                        toolTitle = result.toolTitle.ifEmpty { loopState.allToolBlocks[blockIdx].toolTitle },
                        durationMs = elapsed,
                        browserURL = result.pageURL ?: loopState.allToolBlocks[blockIdx].browserURL,
                        imageFilePath = result.imageFilePath ?: loopState.allToolBlocks[blockIdx].imageFilePath,
                    )
                }

                resultParts.add(AgentContentPart.ToolResult(
                    id = id,
                    name = name,
                    content = outputForLLM,
                    isError = !result.success,
                    imageData = result.imageData,
                    imageMimeType = result.imageMimeType,
                    imageLinuxPath = result.imageLinuxPath,
                ))
            }

            // Update UI with tool statuses. Mark as awaiting the next model
            // response so "Minis is thinking" shows during the network gap
            // between tool results being sent and the next turn's first chunk.
            // Mirrors iOS isAwaitingModelResponse.
            withContext(Dispatchers.Main) {
                host.updateAssistantMessage(
                    loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks,
                    isAwaitingModelResponse = true,
                )
            }

            // Persist the assistant+tools turn (with full input JSON and thinking).
            // Capture the persisted DB id so we can back-fill host.agentHistory's last
            // assistant entry — compact-marker boundary resolution depends on it.
            // [Diag-appendMessage] Boundary markers around the persist block so a
            // hang between tool-END and the next REQ can be attributed to the
            // persist phase vs the next-turn dispatch.
            android.util.Log.i("ChatVMStream", "runAgentLoop turn=$turn persist-begin blocks=${loopState.allToolBlocks.size}")
            // [deep-audit-1016c ISSUE-01] Same unpersisted-start basis as the
            // no-tool terminal slice (see that site).
            val turnParts = host.buildTurnParts(loopState.allToolBlocks, loopState.unpersistedBlockStart, toolInputMap)
            val blockMeta = loopState.allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
            // [fix/early-turn-persist] When the row already exists (written
            // before the tools ran) refresh it with the finished tool blocks
            // instead of inserting a second row for the same turn. Copy to a
            // local val first: the var is captured by the dispatch block
            // above, so it no longer smart-casts.
            val earlyId = earlyAssistantDbId
            val assistantDbId = if (earlyId != null) {
                host.updatePersistedAssistantTurn(earlyId, turnParts, blockMeta)
                earlyId
            } else {
                host.persistAssistantTurn(
                    turnParts, lastUsage, turnReasoningContent, blockMeta,
                    modelId = loopState.currentProvider.model.id,
                    entryId = host.activeEntryId,
                )
            }
            if (assistantDbId != null) {
                val lastIdx = host.agentHistory.indexOfLast { it.role == LLMMessage.Role.ASSISTANT && it.dbMessageId == null }
                if (lastIdx >= 0) {
                    host.agentHistory[lastIdx] = host.agentHistory[lastIdx].copy(dbMessageId = assistantDbId)
                }
                // [deep-audit-1016c ISSUE-01/A] The row now durably carries every
                // block in the unpersisted range (its slice starts there) — the
                // next turn's persistence must not re-send them.
                loopState.unpersistedBlockStart = loopState.allToolBlocks.size
            }

            // Persist tool results as user-role message (mirrors iOS)
            android.util.Log.i("ChatVMStream", "runAgentLoop turn=$turn persist assistant done (dbId=$assistantDbId), toolResult-begin")
            val toolResultDbId = host.persistToolResultMessage(resultParts, transcriptRedactions)
            android.util.Log.i("ChatVMStream", "runAgentLoop turn=$turn persist-both done (toolDbId=$toolResultDbId)")

            // Add tool results to history
            host.agentHistory.add(LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = resultParts,
                dbMessageId = toolResultDbId,
            ))

            // [fix/rv1014-g1] Auto-title after first exchange — semantic
            // condition (mirrors iOS host.generateSessionTitleIfNeeded). Same
            // rationale as the no-tool path above: `turn == 0` skipped the
            // title whenever the first round went through recovery or
            // EOF-truncation continuation (turn already advanced); the
            // semantic condition fires whenever the title is ungenerated and
            // the host function's own guards keep it idempotent.
            if (host.sessionTitleNeedsGeneration) {
                host.generateSessionTitleIfNeeded()
            }

            // T9: close out this tool-running turn (tokens + finish + elapsed)
            traceObserver.agentTraceRecorder.turnEnd(
                turn = turn,
                tokensIn = lastUsage?.inputTokens,
                tokensOut = lastUsage?.outputTokens,
                finishReason = turnFinishReason,
                durationMs = System.currentTimeMillis() - turnStartMs,
            )

            // [T-android-queued-message-interrupt-on-toolclose] iOS d14174d3
            // parity. User report: "怎么样了" queued bubble (dashed border,
            // red X) stayed pending behind a long sync→export→read→gh-issue
            // tool chain — host.drainQueuedPrompts() only fires when the WHOLE
            // tool loop converges, so the queued prompt waited for the
            // entire plan to finish even though the user wanted to
            // interrupt the moment a tool closed.
            //
            // Fix: at the post-tool-result boundary (we just appended the
            // tool_result to host.agentHistory above), if there's anything in
            // the queue, abandon the rest of the running plan and inject
            // the queued prompt as a fresh user turn — the next iteration
            // makes a brand-new API call whose response targets the
            // queued prompt directly.
            //
            // Why not just append-and-continue: the host.agentHistory tail is
            // user(tool_result). Anthropic's mergeConsecutiveSameRole would
            // fold a directly-appended user(queued_text) into that
            // tool_result, so the model would read the queued prompt as
            // in-loop context for the previous turn (#579 / iOS regression).
            // Inject a minimal assistant bridge first so the sequence is
            //   …user(tool_result) → assistant(bridge) → user(queued) →
            //   …assistant(responds-to-queued).
            // The bridge lives in host.agentHistory only (NOT persisted) —
            // it's purely a wire-format spacer for the API call.
            if (host.promptQueueDepth > 0) {
                AppLogger.info(
                    TAG_STREAM,
                    "📨[QueueInterrupt] turn=$turn ${host.promptQueueDepth} queued prompt(s) — interrupting after current tool call to start a standalone turn",
                )
                val handled = try {
                    host.injectQueuedPromptsAsNewTurn(
                        finishedAssistantId = loopState.assistantId,
                        finishedAccumulatedText = loopState.accumulatedText,
                        finishedAllToolBlocks = loopState.allToolBlocks,
                    )
                } catch (e: Exception) {
                    AppLogger.warning(TAG_STREAM, "host.injectQueuedPromptsAsNewTurn failed: ${e.message}")
                    null
                }
                if (handled != null) {
                    // Switch loop-scope state to the new bubble. Subsequent
                    // iterations populate `handled.newAssistantId` and slice
                    // `loopState.allToolBlocks` from the freshly-zeroed start index
                    // (turnStartBlockIndex captures loopState.allToolBlocks.size at
                    // iteration top, so clearing means new turn's blocks
                    // span [0..size).
                    loopState.assistantId = handled.newAssistantId
                    loopState.accumulatedText = ""
                    loopState.allToolBlocks.clear()
                    // [deep-audit-1016c ISSUE-01/A] New bubble ⇒ unpersisted range
                    // restarts from 0, in lockstep with the cleared list.
                    loopState.unpersistedBlockStart = 0
                    loopState.allToolInputs.clear()
                    loopState.toolInputChunkRings.clear()
                    host.setCanResume(false)
                    continue
                }
                // null return = empty-after-build / drain rejected; fall
                // through to normal next-turn dispatch so the queue doesn't
                // pin the loop indefinitely.
            }
        }
        // Two ways to leave the for-loop above:
        //   (a) `break` from the "no tool calls" happy-path → loopState.loopExitedNormally=true,
        //       host.updateAssistantMessage(...false...) already cleared streaming state.
        //   (b) `for (turn in 0 until MAX_AGENT_TURNS)` exhausted → flag stays false,
        //       which means the model kept asking for tool calls past the ceiling.
        //
        // (b) is the only case that needs the inline-error/Resume hand-holding;
        // (a) must NOT be touched or every normal completion gets a fake "hit
        // 200 turns" sticker (the bug user hit at v1.4.0-dev tip).
        if (AgentLoopExitDecision.shouldFinalizeAsRunaway(
                loopExitedNormally = loopState.loopExitedNormally,
                terminalErrorSurfaced = loopState.terminalErrorSurfaced,
                budgetStopReason = traceObserver.t7BudgetStopReason,
            )
        ) {
            AppLogger.warning(
                TAG_STREAM,
                "runAgentLoop EXIT — hit MAX_AGENT_TURNS=$maxTurnsThisRun, finalizing as resumable",
            )
            withContext(Dispatchers.Main) {
                host.finalizeAtTurnLimit(loopState.assistantId, loopState.accumulatedText, loopState.allToolBlocks, maxTurnsThisRun)
            }
        } else if (traceObserver.t7BudgetStopReason != null) {
            // T7-C: 预算耗尽（deadline / 计数上限）—— 显式终态，不是静默失败。
            // [fix/budget-stop-silent-exit] A budget stop is NOT a clean
            // completion: the run was interrupted mid-work (the model still
            // owed the user a next tool call or a final answer). The old path
            // only logged a WARN and returned "normal" — the UI tore down the
            // streaming state with NO inline error and NO resume affordance,
            // which users experience as "the reply just stopped" (field log
            // 2026-09-07: 3 empty turns burned in 4ms against a refused
            // provider-attempt gate, then a silent exit). Finalize EXACTLY
            // like the turn-limit path: keep the accumulated text/blocks on
            // screen, attach a human banner, and mark resumable so "Resume"
            // (or a new message) continues with the budget reset.
            AppLogger.warning(TAG_STREAM, "runAgentLoop EXIT — budget stop: $traceObserver.t7BudgetStopReason, finalizing as resumable")
            withContext(Dispatchers.Main) {
                host.finalizeBudgetStop(
                    loopState.assistantId,
                    loopState.accumulatedText,
                    loopState.allToolBlocks,
                    reason = traceObserver.t7BudgetStopReason ?: "unknown",
                    // [fix/runtime-limits-audit] Pass THIS run's budget limits
                    // so the banner quotes what actually stopped the run, not
                    // a live re-read that can drift mid-run.
                    maxProviderAttemptsThisRun = observeBudget.maxProviderAttempts,
                    maxTurnsThisRun = maxTurnsThisRun,
                )
            }
        } else {
            AppLogger.info(TAG_STREAM, "runAgentLoop EXIT (loop body ended naturally)")
        }
        // T9: close the trace for this run
        // T7-A: 2.0 终态收尾 —— 正常退出 / 达到轮数上限都走这里
        // T7-C: 预算中断 → deadline 走 Interrupted(DEADLINE_EXCEEDED)，
        //       计数耗尽走 Failed(EXECUTION_FAILED) + error 标注具体维度
        val budgetStop = traceObserver.t7BudgetStopReason
        // [deep-audit-1015 BUG-01a] The decision table lives in
        // AgentLoopExitDecision (same package, JVM-testable) and is CALLED
        // here rather than mirrored in the test — the old test carried a copy
        // of these `when` blocks, so a change in either one left the other
        // green and this defect stayed invisible.
        traceObserver.t7EndRun(
            terminal = AgentLoopExitDecision.terminal(budgetStop, loopState.loopExitedNormally),
            reason = AgentLoopExitDecision.reason(budgetStop, loopState.loopExitedNormally),
            durationMs = System.currentTimeMillis() - traceStartMs,
            // [audit-0907 B5] A failure-terminal error was already surfaced
            // inline (EOF stub ceiling / repetition abort / …) — label it as
            // such, NOT as a runaway turn-loop (which it isn't: the loop
            // exited via a typed failure branch).
            error = AgentLoopExitDecision.errorLabel(
                budgetStopReason = budgetStop,
                terminalErrorSurfaced = loopState.terminalErrorSurfaced,
                loopExitedNormally = loopState.loopExitedNormally,
            ),
        )
        traceObserver.traceRunFile = null
        traceObserver.t7BudgetStopReason = null
        } catch (e: CancellationException) {
            // T9: cancel is intentional — trace the interruption
            runCatching {
                traceObserver.agentTraceRecorder.error(turn = traceObserver.activeTraceTurn, phase = "cancel", message = "runAgentLoop cancelled")
                // T7-A: 2.0 终态 —— 用户取消
                traceObserver.t7EndRun(
                    terminal = AgentTerminal.CANCELLED,
                    reason = AgentTerminalReason.USER_CANCELLED,
                    durationMs = System.currentTimeMillis() - traceStartMs,
                    error = "cancelled",
                )
            }
            traceObserver.traceRunFile = null
            // Job cancelled mid-task (user stop / session switch / queue
            // takeover): rethrow so cancellation propagates as before (e.g.
            // the queue switch handler depends on it).
            throw e
        } catch (e: Exception) {
            // T9: log the unexpected error, then rethrow
            runCatching {
                traceObserver.agentTraceRecorder.error(turn = traceObserver.activeTraceTurn, phase = "exception", message = "${e.javaClass.simpleName}: ${e.message}")
                // T7-A: 2.0 终态 —— 执行失败
                traceObserver.t7EndRun(
                    terminal = AgentTerminal.FAILED,
                    reason = AgentTerminalReason.EXECUTION_FAILED,
                    durationMs = System.currentTimeMillis() - traceStartMs,
                    error = "${e.javaClass.simpleName}: ${e.message}",
                )
            }
            traceObserver.traceRunFile = null
            // Unexpected failure: rethrow so the caller's error handling
            // behaves exactly as before.
            throw e
        }
    }
}
