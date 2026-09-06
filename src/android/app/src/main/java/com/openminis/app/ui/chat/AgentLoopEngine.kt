package com.openminis.app.ui.chat

import com.openminis.app.agent.Level
import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.ToolJsonRepair
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.tools.ToolConcurrencyPolicy
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.openminis.app.agent.runtime.AgentExecutionBudget
import com.openminis.app.agent.runtime.AgentRunEvent
import com.openminis.app.agent.runtime.AgentRunPhase
import com.openminis.app.agent.runtime.AgentRunState
import com.openminis.app.agent.runtime.AgentTerminal
import com.openminis.app.agent.runtime.AgentTerminalReason
import com.openminis.app.agent.runtime.ProviderAttemptOutcome
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.tools.AgentTraceRecorder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.openminis.app.R

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

/** Hard cap on agent-loop turns (moved from ChatViewModel companion, FE-5 route C). */
internal const val MAX_AGENT_TURNS = 200

/**
 * [feat/hermes-tier1] Max text-continuation rounds per length-wall wall.
 * A truncated (finish_reason="length") turn with visible text is continued
 * at most this many times; past the cap the loop stops continuing, rolls
 * the seam back to the last clean fold, and surfaces the truncation error.
 * Mirrors Hermes turn_truncation's continuation ceiling of 4.
 */
internal const val MAX_LENGTH_WALL_TEXT_CONTINUES = 4

/**
 * [feat/hermes-tier1] Consecutive deterministic-empty completions (usage
 * proves output_tokens == 0) after which the loop gives up instead of
 * re-billing. Mirrors Hermes empty_response_guard's skip-retries-on-2-
 * deterministic-empties (adapted: RikkaMinis keeps one reminder round for
 * the tool-result case, so the streak limit is 2 here).
 */
internal const val DETERMINISTIC_EMPTY_LIMIT = 2

/**
 * [fix/eof-stub-continuation] Max continuation rounds for EOF-truncated
 * streams (stream ended with NO finish_reason). The partial answer is KEPT
 * and a network-stub reminder asks the model to continue; past this cap the
 * loop gives up with a visible error instead of a silent mid-sentence stop.
 */
internal const val MAX_EOF_STUB_CONTINUES = 2

/** Per-tool ring cap for ToolInputDelta snapshots (moved from ChatViewModel companion). */
internal const val TOOL_INPUT_CHUNK_RING_MAX = 10

/** Transient auto-retry backoff seconds (moved from ChatViewModel companion). */
internal val AUTO_RETRY_DELAYS_SEC = intArrayOf(1, 2, 4)

internal class AgentLoopEngine(
    private val host: AgentLoopHost,
    private val traceObserver: ChatAgentTraceObserver,
) {
    private companion object {
        private const val TAG_STREAM = "ChatVMStream"
        private const val TAG = "ChatViewModel"
    }

    /** Verbatim lift of ChatViewModel.unwrapFlowException (used by the retry path). */
    private fun unwrapFlowExceptionImpl(e: Throwable): Throwable {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is com.openminis.app.data.model.LLMError) return cause
            cause = cause.cause
        }
        return e
    }

    /** Verbatim lift of ChatViewModel.runAgentLoop entry (FE-5 route C step 3). */
    internal suspend fun runAgentLoop(
        provider: LLMProvider,
        systemPrompt: String?,
        fallbackProviders: List<FallbackCandidate> = emptyList(),
        fallbackStrategy: com.openminis.app.data.model.FallbackStrategy = com.openminis.app.data.model.FallbackStrategy.default,
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
        val observeBudget = AgentExecutionBudget(
            startedAtMonotonicMs = SystemClock.elapsedRealtime(),
            deadlineMonotonicMs = SystemClock.elapsedRealtime() + ChatAgentTraceObserver.T7_OBSERVE_DEADLINE_MS,
            maxTurns = ChatAgentTraceObserver.T7_OBSERVE_MAX_TURNS,
            maxProviderAttempts = ChatAgentTraceObserver.T7_OBSERVE_MAX_PROVIDER_ATTEMPTS,
            maxToolCalls = ChatAgentTraceObserver.T7_OBSERVE_MAX_TOOL_CALLS,
            maxShellCommands = ChatAgentTraceObserver.T7_OBSERVE_MAX_SHELL_COMMANDS,
            maxCompactionCalls = ChatAgentTraceObserver.T7_OBSERVE_MAX_COMPACTION_CALLS,
            maxConcurrentTools = ChatAgentTraceObserver.T7_OBSERVE_MAX_CONCURRENT_TOOLS,
            maxEstimatedTokens = null, // token 计数不稳定，观察期不强制
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
        // starts empty and `host.buildTurnParts(loopState.allToolBlocks, turnStartBlockIndex,
        // toolInputMap)` continues to slice only the current turn's blocks
        // (turnStartBlockIndex is captured at iteration start to 0 after reset).
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
        for (turn in 0 until MAX_AGENT_TURNS) {
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
                host.offloadContextIfNeeded(
                    contextWindow = window,
                    lastContextTokens = loopState.lastContextTokens,
                )
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
                    lastContextTokens = loopState.lastContextTokens,
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
                if (!compacted) {
                    host.trimContextHistoryWindow(
                        contextWindow = window,
                        lastContextTokens = loopState.lastContextTokens,
                    )
                } else {
                    AppLogger.info(TAG_STREAM, "auto-compact folded old turns; skipping hard trim this turn (anchor preserved)")
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
            fun materializeActiveTextBlock() {
                val sb = currentTextBlockSb ?: return
                val idx = if (loopState.currentProvider.streamTextIsMonolithic) turnTextBlockIdx else loopState.allToolBlocks.lastIndex
                if (idx >= 0 && idx < loopState.allToolBlocks.size && loopState.allToolBlocks[idx].kind == "text") {
                    loopState.allToolBlocks[idx] = loopState.allToolBlocks[idx].copy(content = sb.toString())
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
            val maxTokens = host.dynamicMaxTokens(provider, loopState.lastContextTokens)
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
                    break
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
                        break
                    }
                    // T7-D: 旁路验证 —— ProviderAttemptStarted
                    traceObserver.t7Reduce(AgentRunEvent.ProviderAttemptStarted)
                    // [T-android-enhanced-cache] Stamp the per-turn Enhanced
                    // Cache flag onto the active provider here — the single
                    // choke point every turn passes through, regardless of how
                    // loopState.currentProvider was (re)assigned by the fallback loop.
                    // Non-Anthropic providers ignore it (cast fails silently).
                    (loopState.currentProvider as? com.openminis.app.provider.anthropic.AnthropicProvider)
                        ?.enhancedCache = host.enhancedCacheEnabled
                    // Route through host.effectiveAgentHistory() so a populated
                    // [host.compactSummary] is prepended as a `<context-summary>`
                    // user message. Falls through to the raw host.agentHistory when
                    // no compact has happened, so the common path stays zero-copy.
                    host.streamChatTurnOffloaded(
                        provider = loopState.currentProvider,
                        messages = host.applyRequestImageBudget(host.effectiveAgentHistory()),
                        systemPrompt = systemPrompt,
                        maxTokens = host.dynamicMaxTokens(loopState.currentProvider, loopState.lastContextTokens),
                        temperature = null,
                        imageParts = emptyList(),
                        tools = host.agentTools,
                        thinkingLevel = if (host.currentModelSupportsReasoning) host.thinkingLevel else ThinkingLevel.OFF,
                    ).collect { chunk ->
                when (chunk) {
                    is LLMStreamChunk.ThinkingDelta -> {
                        turnThinking.append(chunk.text)
                        // Update thinking block in UI
                        val thinkIdx = loopState.allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                        if (thinkIdx < 0) {
                            loopState.allToolBlocks.add(AssistantBlock(
                                id = "thinking_$turn",
                                kind = "thinking",
                                content = turnThinking.toString(),
                                toolTitle = "Thinking",
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
                        val thinkIdx = loopState.allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                        if (thinkIdx >= 0 && loopState.allToolBlocks[thinkIdx].toolStatus != ToolBlockStatus.SUCCESS) {
                            loopState.allToolBlocks[thinkIdx] = loopState.allToolBlocks[thinkIdx].copy(toolStatus = ToolBlockStatus.SUCCESS)
                        }
                        // T307: append-only on the StringBuilder; .toString()
                        // is taken once below at flush time, not per delta.
                        turnTextSb.append(chunk.text)
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
                        val thinkIdx = loopState.allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                        if (thinkIdx >= 0 && loopState.allToolBlocks[thinkIdx].toolStatus != ToolBlockStatus.SUCCESS) {
                            loopState.allToolBlocks[thinkIdx] = loopState.allToolBlocks[thinkIdx].copy(toolStatus = ToolBlockStatus.SUCCESS)
                        }
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
                        android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolInputDelta id=$toolInputId len=${chunk.accumulated.length}")
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
                        android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolCallComplete id=$toolCompleteId name=${chunk.name} args=${chunk.args.toString().take(300)}")
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
                        lastUsage = chunk.usage
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
                            host.setLastTurnContextTokens(loopState.lastContextTokens)
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
                    // T256: reset throttle bookkeeping for the next turn so the
                    // first delta of the next assistant message fires immediately
                    // rather than coalescing against this turn's stale baseline.
                    loopState.lastFlushedLen = 0
                    loopState.lastUiUpdateMs = 0L
                    loopState.lastFileToolInputMs = 0L
                    loopState.lastOtherToolInputMs = 0L
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
                    val isRateLimit = actual is com.openminis.app.data.model.LLMError.RateLimited
                    val is5xx = actual is com.openminis.app.data.model.LLMError.ProviderError &&
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
                    // user-visible error. Both 0-chunk types are equally safe to retry.
                    val workerDiedZeroChunk =
                        ((actual is com.openminis.app.sandbox.offload.ModelWorkerDiedException) ||
                            (actual is com.openminis.app.sandbox.offload.ModelStreamErrorException)) &&
                        (actual as? com.openminis.app.sandbox.offload.ModelExecutionStreamException)?.hadChunks == false
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
                    val streamErrorAction =
                        (actual as? com.openminis.app.sandbox.offload.ModelStreamErrorException)
                            ?.let { ChatStreamErrorPolicy.classify(it.kind) }
                    val streamErrorAutoRetry = streamErrorAction == ChatStreamErrorPolicy.Action.AUTO_RETRY
                    val streamErrorFallbackNow =
                        streamErrorAction == ChatStreamErrorPolicy.Action.FALLBACK_NOW
                    val isTransient = actual is com.openminis.app.data.model.LLMError.NetworkError ||
                        actual is com.openminis.app.data.model.LLMError.TransientError ||
                        is5xx ||
                        workerDiedZeroChunk ||
                        streamErrorAutoRetry
                    // [T-fallback-retry-original] Restored original behavior: all members
                    // (including fallback chain members) get bounded retries on transient
                    // errors. This absorbs intermittent stream resets that the fallback
                    // member would otherwise immediately expose as a "all fallbacks
                    // exhausted" banner. See 3b3a12f for the revert context.
                    if (isTransient && retryAttempt < AUTO_RETRY_DELAYS_SEC.size) {
                        val delaySec = AUTO_RETRY_DELAYS_SEC[retryAttempt]
                        retryAttempt += 1
                        val errDesc = actual.message ?: actual.javaClass.simpleName
                        Log.w("ChatViewModel", "🔁 Transient error on ${loopState.currentProvider.model.displayName}, retry $retryAttempt/${AUTO_RETRY_DELAYS_SEC.size} in ${delaySec}s: $errDesc")
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
                            maxAttempts = AUTO_RETRY_DELAYS_SEC.size,
                            willRetry = true,
                        )
                        // [T-error-no-permanent-scars] The transient banner shows a
                        // human summary too ("Connection failed — retrying 1/3…"),
                        // not the raw "stream was reset: CANCEL" text. The log line
                        // above keeps the full detail for debugging.
                        val errSummary = (actual as? com.openminis.app.data.model.LLMError)?.userMessage
                            ?: actual.message?.takeIf { it.isNotBlank() }
                            ?: actual.javaClass.simpleName
                        withContext(Dispatchers.Main) {
                            host.setAutoRetry(retryAttempt, 0)
                            // Show the error inline on the streaming assistant message during countdown.
                            // Keeps isStreaming=true so the UI doesn't tear down the streaming state.
                            host.setTransientInlineError("$errSummary — retrying ($retryAttempt/${AUTO_RETRY_DELAYS_SEC.size})…")
                        }
                        try {
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
                        loopState.lastUiUpdateMs = 0L
                        loopState.lastFlushedLen = 0
                        loopState.lastFileToolInputMs = 0L
                        loopState.lastOtherToolInputMs = 0L
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
                        (actual as? com.openminis.app.data.model.LLMError)?.isFallbackable == true ||
                        fallbackStrategy == com.openminis.app.data.model.FallbackStrategy.always
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
                            actual is com.openminis.app.data.model.LLMError.ProviderError -> actual.detail
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
                                    com.openminis.app.data.routing.RouteOutcome.RateLimited(
                                        retryAfterMs = (actual as? com.openminis.app.data.model.LLMError.RateLimited)?.retryAfterMs,
                                    ),
                                )
                                actual is com.openminis.app.data.model.LLMError.InvalidApiKey ->
                                    host.groupRouter.recordResult(
                                        failed,
                                        com.openminis.app.data.routing.RouteOutcome.AuthError,
                                    )
                                is5xx -> host.groupRouter.recordResult(
                                    failed,
                                    com.openminis.app.data.routing.RouteOutcome.ServerError,
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
                                    is com.openminis.app.data.model.LLMError.NetworkError,
                                    is com.openminis.app.data.model.LLMError.TransientError ->
                                        host.string(R.string.error_all_models_failed, triedCount)
                                    is com.openminis.app.data.model.LLMError.RateLimited ->
                                        host.string(R.string.error_all_models_rate_limited)
                                    is com.openminis.app.data.model.LLMError.InvalidApiKey ->
                                        host.string(R.string.error_all_models_bad_key)
                                    else -> (actual as? com.openminis.app.data.model.LLMError)?.userMessage
                                        ?: actual.message?.takeIf { it.isNotBlank() }
                                        ?: "Unknown error"
                                }
                                // TF-H: reducer must leave FALLING_BACK before
                                // finalizing — otherwise RunFinalized is REJECTED
                                // with "requires FINALIZING (current=FALLING_BACK)".
                                traceObserver.t7Reduce(AgentRunEvent.FallbackExhausted)
                                throw com.openminis.app.data.model.FallbackExhaustedError(
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

            // [T-recovery] The turn's stream completed without error — the
            // member that served it is healthy. Clears any prior cooldown /
            // circuit state (also closes a half-open circuit: a successful
            // probe restores the member).
            host.activeEntryId?.let { entryId ->
                host.groupRouter.recordResult(
                    entryId,
                    com.openminis.app.data.routing.RouteOutcome.Success,
                )
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
                turnFinishReason == "length" -> true
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
                    if (loopState.eofStubContinues < MAX_EOF_STUB_CONTINUES) {
                        loopState.eofStubContinues++
                        AppLogger.warning(
                            TAG_STREAM,
                            "runAgentLoop turn=$turn finish=$turnFinishReason (error-shaped) with ${turnTextRaw.length} chars — network-stub continuation ${loopState.eofStubContinues}/$MAX_EOF_STUB_CONTINUES",
                        )
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
                    withContext(Dispatchers.Main) {
                        host.setInlineError(host.string(R.string.error_stream_interrupted))
                    }
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
                        continue
                    }
                    AppLogger.warning(
                        TAG_STREAM,
                        "runAgentLoop turn=$turn finish=$turnFinishReason (error-shaped) empty after retry — surfacing error",
                    )
                    withContext(Dispatchers.Main) {
                        host.setInlineError(host.string(R.string.error_stream_interrupted))
                    }
                    loopState.loopExitedNormally = true
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
            if (toolCalls.isEmpty() && turnTextRaw.isEmpty() &&
                ContentFilterFinishPolicy.isBlockedFinish(turnFinishReason)
            ) {
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
                withContext(Dispatchers.Main) {
                    host.setInlineError(host.string(R.string.error_content_filtered))
                }
                loopState.loopExitedNormally = true
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
                if (turnFinishReason == "length") {
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
                            if (loopState.deterministicEmptyStreak >= DETERMINISTIC_EMPTY_LIMIT) {
                                AppLogger.warning(
                                    TAG_STREAM,
                                    "runAgentLoop turn=$turn finish=length empty ×$loopState.deterministicEmptyStreak with usage.outputTokens==0 — deterministic empty, skipping remaining retries",
                                )
                                withContext(Dispatchers.Main) {
                                    host.setInlineError(host.string(R.string.error_output_truncated_repeated))
                                }
                                // Fall through to the normal break path (persist + exit).
                                // Do NOT `break` here directly: it would skip
                                // `loopState.loopExitedNormally = true` and misclassify as a
                                // MAX_AGENT_TURNS runaway.
                            }
                        } else {
                            loopState.deterministicEmptyStreak = 0
                        }
                        if (loopState.lengthWallEmptyHits < 3) {
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
                                "runAgentLoop turn=$turn finish=length with empty output (wall hit $loopState.lengthWallEmptyHits/3), continuing",
                            )
                            continue
                        }
                        AppLogger.warning(TAG_STREAM, "runAgentLoop turn=$turn finish=length ×3 empty output — giving up")
                        // length is NOT a clean finish, so the empty-turn hint
                        // below (gated on finishedCleanly) won't fire — surface
                        // a visible error explicitly so the user isn't left
                        // staring at a silent blank bubble.
                        withContext(Dispatchers.Main) {
                            host.setInlineError(host.string(R.string.error_output_truncated_repeated))
                        }
                        // Fall through to the normal break path (persist + exit).
                        // Do NOT `break` here directly: it would skip
                        // `loopState.loopExitedNormally = true` and misclassify as a
                        // MAX_AGENT_TURNS runaway.
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
                            withContext(Dispatchers.Main) {
                                host.setInlineError(host.string(R.string.error_output_truncated_repeated))
                            }
                            // Fall through to the normal break path (persist +
                            // exit). NOT `loopExitedNormally` (this is an
                            // abort), but also NOT a runaway misclassification:
                            // see the deterministic-empty branch above for the
                            // same pattern.
                        } else if (loopState.lengthWallContinues >= MAX_LENGTH_WALL_TEXT_CONTINUES) {
                            AppLogger.warning(
                                TAG_STREAM,
                                "runAgentLoop turn=$turn finish=length — continuation ceiling $MAX_LENGTH_WALL_TEXT_CONTINUES reached, giving up (accumulated ${loopState.accumulatedText.length} chars)",
                            )
                            withContext(Dispatchers.Main) {
                                host.setInlineError(host.string(R.string.error_output_truncated_repeated))
                            }
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
                                "runAgentLoop turn=$turn finish=length — truncated (${turnText.length} chars), continuing loop to let the model finish (${loopState.lengthWallContinues}/$MAX_LENGTH_WALL_TEXT_CONTINUES)",
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
                        val prevTail = host.agentHistory.lastOrNull()
                        val tailIsLengthWallReminder = prevTail != null &&
                            prevTail.role == LLMMessage.Role.USER &&
                            prevTail.contentParts.size == 1 &&
                            (prevTail.contentParts.first() as? AgentContentPart.Text)?.text
                                ?.contains("cut off mid-sentence") == true
                        if (tailIsLengthWallReminder) {
                            host.agentHistory.removeAt(host.agentHistory.size - 1)
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
                val verifyNudge = VerificationStopPolicy.buildNudge(
                    changedPaths = loopState.changedCodePaths.toList(),
                    attempts = loopState.verifyNudgeAttempts,
                    lastEvidenceDetail = loopState.lastVerificationDetail,
                )
                if (verifyNudge != null) {
                    loopState.verifyNudgeAttempts++
                    AppLogger.warning(
                        TAG_STREAM,
                        "runAgentLoop turn=$turn finish=$turnFinishReason but unverified code edits " +
                            "(${loopState.changedCodePaths.size} path(s)) — injecting verify nudge " +
                            "${loopState.verifyNudgeAttempts}/${VerificationStopPolicy.MAX_VERIFY_NUDGES}",
                    )
                    val nudgeMsg = LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = verifyNudge,
                        contentParts = listOf(AgentContentPart.Text(verifyNudge)),
                    )
                    host.agentHistory.add(nudgeMsg)
                    continue
                }

                AppLogger.info(TAG_STREAM, "runAgentLoop turn=$turn no tool calls → break (finishReason=$turnFinishReason)")
                withContext(Dispatchers.Main) {
                    host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, false, loopState.allToolBlocks)
                }
                val turnParts = host.buildTurnParts(loopState.allToolBlocks, turnStartBlockIndex, toolInputMap)
                val blockMeta = loopState.allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
                host.persistAssistantTurn(
                    turnParts, lastUsage, turnReasoningContent, blockMeta,
                    modelId = loopState.currentProvider.model.id,
                    entryId = host.activeEntryId,
                )
                // [T-error-persist-android] Empty-response hint: the model ended a
                // turn (finish=stop/end_turn) with no visible text anywhere in the
                // reply and no tool blocks — the user just sees a blank bubble.
                // Surface a hint instead. When the context is near full, point at
                // compaction; otherwise suggest retry/switch. host.setInlineError
                // attaches + persists onto the (empty) assistant row so the hint
                // survives a reload too.
                val hasVisibleContent = loopState.accumulatedText.isNotBlank() ||
                    loopState.allToolBlocks.any { it.kind == "tool_use" || (it.kind == "text" && it.content.isNotBlank()) }
                val finishedCleanly = (turnFinishReason == null ||
                    turnFinishReason == "stop" || turnFinishReason == "end_turn") &&
                    // [T-truncated-stream-retry] a truncated turn (EOF without
                    // finish_reason) is NOT a clean finish even though
                    // stopReason is null — the retry branch below owns it, so
                    // the empty-turn path must not swallow it with an error hint.
                    !turnTruncated
                if (!hasVisibleContent && finishedCleanly) {
                    // [T-android-empty-after-toolresult-reminder] Special case: the
                    // server returned an empty turn right after a tool result. The
                    // model owes a follow-up (next tool call or a final answer) but
                    // stalled — the user sees a blank bubble with no explanation.
                    // Inject a one-shot <system-reminder> into that tool result and
                    // retry ONE round. The guard fires at most once per run, so it
                    // can never loop; the SECOND empty falls through to the error
                    // hint below. Mirrors iOS AIChatViewModel.swift empty-after-
                    // tool-result path.
                    //
                    // The empty assistant turn was just appended (above) — drop it
                    // so the tool result is the last message and the model gets a
                    // clean "continue from here" prompt on the retry.
                    val priorIsToolResult = host.agentHistory.size >= 2 &&
                        host.agentHistory[host.agentHistory.size - 2].contentParts.isNotEmpty() &&
                        host.agentHistory[host.agentHistory.size - 2].contentParts.all { it is AgentContentPart.ToolResult }
                    if (!loopState.didInjectEmptyToolReminder && priorIsToolResult) {
                        loopState.didInjectEmptyToolReminder = true
                        AppLogger.warning(TAG_STREAM, "empty turn after tool result — injecting <system-reminder> and retrying one round (turn=$turn)")
                        // Remove the empty assistant turn we just added.
                        host.agentHistory.removeAt(host.agentHistory.size - 1)
                        // Inject the reminder into the last tool result's content.
                        val trIdx = host.agentHistory.size - 1
                        val trMsg = host.agentHistory[trIdx]
                        val reminder = "\n\n<system-reminder>The previous response was empty. A tool result was just provided and you MUST continue: respond with the next tool call(s) if more work is needed, or a final text answer for the user. Do not return an empty response.</system-reminder>"
                        val newParts = trMsg.contentParts.toMutableList()
                        val lastTrPartIdx = newParts.indexOfLast { it is AgentContentPart.ToolResult }
                        if (lastTrPartIdx >= 0) {
                            val part = newParts[lastTrPartIdx] as AgentContentPart.ToolResult
                            newParts[lastTrPartIdx] = part.copy(content = part.content + reminder)
                            host.agentHistory[trIdx] = trMsg.copy(contentParts = newParts)
                        }
                        // Retry a fresh model round with the nudged history.
                        continue
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
                    withContext(Dispatchers.Main) { host.setInlineError(hint) }
                }
                // Auto-title after first exchange
                if (turn == 0) host.generateSessionTitleIfNeeded()

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
                if (turnTruncated && hasVisibleContent) {
                    if (loopState.eofStubContinues < MAX_EOF_STUB_CONTINUES) {
                        loopState.eofStubContinues++
                        AppLogger.warning(
                            TAG_STREAM,
                            "runAgentLoop turn=$turn EOF-truncated stream (${turnText.length} chars kept, mid-sentence=${looksLikeMidSentenceCut(turnText)}) — network-stub continuation ${loopState.eofStubContinues}/$MAX_EOF_STUB_CONTINUES",
                        )
                        // Drop any stale stub reminder from a previous EOF so
                        // reminders never stack (guard mirrors length-wall).
                        val prevTail = host.agentHistory.lastOrNull()
                        val tailIsEofStubReminder = prevTail != null &&
                            prevTail.role == LLMMessage.Role.USER &&
                            prevTail.contentParts.size == 1 &&
                            (prevTail.contentParts.first() as? AgentContentPart.Text)?.text
                                ?.contains("cut off by a network error") == true
                        if (tailIsEofStubReminder) {
                            host.agentHistory.removeAt(host.agentHistory.size - 1)
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
                loopState.loopExitedNormally = true
                // T7-D: 旁路验证 —— 工具序列完成，进入收尾
                traceObserver.t7Reduce(AgentRunEvent.WorkCompleted)
                break
            }
            AppLogger.info(TAG_STREAM, "runAgentLoop turn=$turn dispatching ${toolCalls.size} tool call(s), continuing")

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
            run {
                val livePreviewParts = host.buildTurnParts(loopState.allToolBlocks, turnStartBlockIndex, toolInputMap)
                val liveMeta = loopState.allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
                if (livePreviewParts.isNotEmpty()) {
                    host.updateSessionPreview(
                        host.buildAssistantPartsJson(livePreviewParts, liveMeta),
                    )
                }
            }

            // Execute all tool calls
            val resultParts = mutableListOf<AgentContentPart>()

            // ------------------------------------------------------------------
            // Tool dispatch — split into passes so a batch of read-only tools
            // (file_read / read_image) can run concurrently.
            //
            // Pass 1 (sequential): per-call preflight + loop-detect. CRITICAL /
            // preflight rejections synthesize their tool_result right here and
            // `continue` — exactly as the original loop did. Calls that pass are
            // collected into `pending`.
            // Pass 2 (execute): a batch of ONLY parallel-safe tools runs
            // concurrently (async, awaited in original order). Anything else runs
            // sequentially — identical to the old single-loop behavior.
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
                val repairs = com.openminis.app.provider.ToolJsonRepair.repair(
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
                // Flip PENDING → RUNNING right before the execute dispatch so the UI
                // (tool pill spinner) shows the exact moment execution begins.
                val preIdx = loopState.allToolBlocks.indexOfFirst { it.id == id }
                if (preIdx >= 0 && loopState.allToolBlocks[preIdx].toolStatus == ToolBlockStatus.PENDING) {
                    loopState.allToolBlocks[preIdx] = loopState.allToolBlocks[preIdx].copy(toolStatus = ToolBlockStatus.RUNNING)
                    withContext(Dispatchers.Main) {
                        host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                    }
                }

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
                    val modelMessage = "Error: Tool call rejected before execution. $preflightError The arguments your client sent were empty or missing required fields — re-issue the call with all required parameters filled in. Do not retry with the same empty arguments."
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
            if (pending.size > 1 && pending.all { ToolConcurrencyPolicy.isParallelSafe(it.name, it.argsStr) }) {
                // All pending tools are parallel-safe pure reads. Launch them
                // concurrently, then pull each result in the original order so
                // Pass 3 observes the same sequence as the old sequential loop.
                val deferred = coroutineScope {
                    pending.map { p ->
                        p.id to async {
                            host.executeTool(p.name, p.argsStr, p.id, loopState.allToolBlocks, loopState.assistantId, loopState.accumulatedText)
                        }
                    }
                }
                for ((pid, d) in deferred) {
                    resultsById[pid] = d.await()
                }
            } else {
                // Any non-parallel tool (or a single call) → sequential, exactly
                // like the original loop. Each queued call gets a non-blocking
                // "waiting" cue so the UI doesn't look hung while earlier tools
                // still run — pure visibility, no status flip, no semantics.
                pending.forEachIndexed { index, p ->
                    if (index > 0) {
                        val waitIdx = loopState.allToolBlocks.indexOfFirst { it.id == p.id }
                        if (waitIdx >= 0) {
                            val waitBlock = loopState.allToolBlocks[waitIdx]
                            if (waitBlock.toolStatus == ToolBlockStatus.PENDING) {
                                loopState.allToolBlocks[waitIdx] = waitBlock.copy(
                                    content = "⏳ Waiting for previous tool(s) to finish…",
                                )
                                withContext(Dispatchers.Main) {
                                    host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, true, loopState.allToolBlocks)
                                }
                            }
                        }
                    }
                    resultsById[p.id] = host.executeTool(p.name, p.argsStr, p.id, loopState.allToolBlocks, loopState.assistantId, loopState.accumulatedText)
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
                // a SUCCESSFUL file_write/file_edit on a code path marks the
                // run's evidence stale (bumps lastEditSeq). Verify side: a
                // verification-shaped shell command records its outcome and,
                // when it PASSED, bumps lastVerifySeq — the turn-end guard
                // compares the two stamps. Non-code paths (prose/config) are
                // filtered by the policy so a SKILL.md edit never demands a
                // verification script.
                when {
                    result.success && (name == "file_write" || name == "file_edit") -> {
                        VerificationStopPolicy.changedPathFromArgs(name, p.argsStr)?.let { path ->
                            if (!VerificationStopPolicy.isNonCodePath(path)) {
                                loopState.changedCodePaths.add(path)
                                loopState.lastEditSeq++
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
                            if (result.success) loopState.lastVerifySeq++
                            AppLogger.info(TAG_STREAM, "[verification-stop] evidence: kind=$kind outcome=$outcome cmd=${cmd.take(80)}")
                        }
                    }
                }

                val blockIdx = loopState.allToolBlocks.indexOfFirst { it.id == id }
                if (blockIdx >= 0) {
                    val elapsed = System.currentTimeMillis() - loopState.allToolBlocks[blockIdx].startTimeMs
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
                        ToolBlockStatus.SUCCESS -> com.openminis.app.service.ToolOutcome.Success
                        ToolBlockStatus.TIMEOUT -> com.openminis.app.service.ToolOutcome.Timeout
                        ToolBlockStatus.FAILED -> com.openminis.app.service.ToolOutcome.Error
                        else -> com.openminis.app.service.ToolOutcome.Unknown
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
            val turnParts = host.buildTurnParts(loopState.allToolBlocks, turnStartBlockIndex, toolInputMap)
            val blockMeta = loopState.allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
            val assistantDbId = host.persistAssistantTurn(
                turnParts, lastUsage, turnReasoningContent, blockMeta,
                modelId = loopState.currentProvider.model.id,
                entryId = host.activeEntryId,
            )
            if (assistantDbId != null) {
                val lastIdx = host.agentHistory.indexOfLast { it.role == LLMMessage.Role.ASSISTANT && it.dbMessageId == null }
                if (lastIdx >= 0) {
                    host.agentHistory[lastIdx] = host.agentHistory[lastIdx].copy(dbMessageId = assistantDbId)
                }
            }

            // Persist tool results as user-role message (mirrors iOS)
            android.util.Log.i("ChatVMStream", "runAgentLoop turn=$turn persist assistant done (dbId=$assistantDbId), toolResult-begin")
            val toolResultDbId = host.persistToolResultMessage(resultParts)
            android.util.Log.i("ChatVMStream", "runAgentLoop turn=$turn persist-both done (toolDbId=$toolResultDbId)")

            // Add tool results to history
            host.agentHistory.add(LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = resultParts,
                dbMessageId = toolResultDbId,
            ))

            // Auto-title after first exchange (mirrors iOS host.generateSessionTitleIfNeeded)
            if (turn == 0) {
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
        if (!loopState.loopExitedNormally && traceObserver.t7BudgetStopReason == null) {
            AppLogger.warning(
                TAG_STREAM,
                "runAgentLoop EXIT — hit MAX_AGENT_TURNS=$MAX_AGENT_TURNS, finalizing as resumable",
            )
            withContext(Dispatchers.Main) {
                host.finalizeAtTurnLimit(loopState.assistantId, loopState.accumulatedText, loopState.allToolBlocks)
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
        traceObserver.t7EndRun(
            terminal = when {
                budgetStop == "deadline_reached" -> AgentTerminal.INTERRUPTED
                budgetStop != null -> AgentTerminal.FAILED
                loopState.loopExitedNormally -> AgentTerminal.SUCCEEDED
                else -> AgentTerminal.FAILED
            },
            reason = when {
                budgetStop == "deadline_reached" -> AgentTerminalReason.DEADLINE_EXCEEDED
                budgetStop != null -> AgentTerminalReason.EXECUTION_FAILED
                loopState.loopExitedNormally -> AgentTerminalReason.COMPLETED
                else -> AgentTerminalReason.EXECUTION_FAILED
            },
            durationMs = System.currentTimeMillis() - traceStartMs,
            error = when {
                budgetStop != null -> "budget_exhausted($budgetStop)"
                !loopState.loopExitedNormally -> "MAX_AGENT_TURNS"
                else -> null
            },
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
