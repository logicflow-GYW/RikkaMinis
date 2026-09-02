package com.openminis.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import com.openminis.app.agent.runtime.AgentExecutionBudget
import com.openminis.app.agent.runtime.AgentRunEvent
import com.openminis.app.agent.runtime.AgentRunPhase
import com.openminis.app.agent.runtime.AgentRunState
import com.openminis.app.agent.runtime.AgentTerminal
import com.openminis.app.agent.runtime.AgentTerminalReason
import com.openminis.app.agent.runtime.ProviderAttemptOutcome
import com.openminis.app.agent.Level
import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.db.MessageEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.ContextPolicy
import com.openminis.app.conversation.ContextCompactor
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.sandbox.offload.ModelStreamErrorException
import com.openminis.app.sandbox.offload.ProviderExecutionGateway
import com.openminis.app.R
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.catalogMaxThinkingLevel
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.tools.AgentTraceRecorder
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.FileEditTool
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.FileWriteTool
import com.openminis.app.tools.MemoryRollupTool
import com.openminis.app.tools.MemoryTools
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.tools.SubagentSkill
import com.openminis.app.tools.SubagentToolCall
import com.openminis.app.tools.ToolConcurrencyPolicy
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.ToolFailureHook
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// [T-android-split-chat] StreamingDelta / ChatMessage / QueuedPrompt /
// ToolBlockStatus / SlashCommand / AssistantBlock moved verbatim to ChatModels.kt.

class ChatViewModel(
    internal val sessionId: String,
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    internal val context: Context,
    val memoryRepository: MemoryRepository? = null,
    val skillRepository: com.openminis.app.data.repository.SkillRepository? = null,
    val mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
) : ViewModel() {

    companion object {
        internal const val TAG = "ChatViewModel"
        // [T-preflight-tool-title-nonblocking] Fields kept in each tool's
        // `required` list (so the schema keeps nudging the model to emit them —
        // tool_title drives the live pill header) but which must NOT block the
        // call when absent: they carry no execution semantics, so rejecting the
        // whole call over a missing one is pure downside. Preflight skips these
        // when checking for missing required fields. Mirrors iOS
        // AIChatViewModel.preflightNonBlockingFields.
        private val PREFLIGHT_NON_BLOCKING_FIELDS = setOf("tool_title")

        /**
         * (tool name → field names) where an EMPTY STRING is a semantically
         * valid value and must not be treated as "missing".
         *
         * Distinct from [PREFLIGHT_NON_BLOCKING_FIELDS], which skips the
         * missing-field check entirely: these fields must still be PRESENT in
         * args — they are just allowed to hold "" as their content.
         *
         * The canonical case is `file_edit.new_string`, whose schema documents
         * "Use empty string to delete old_string". Blocking it broke a promised
         * deletion workflow and pushed the model into shell_execute + python
         * file-rewrite workarounds. Mirrors iOS
         * AIChatViewModel.preflightEmptyStringAllowedFields.
         * [T-preflight-empty-string-allowed]
         */
        /** T9: trace retention cap per session (oldest pruned first).
         *  Moved to ChatAgentTraceObserver.Companion — kept here as an alias
         *  because runAgentLoop's inline docs reference it. */
        const val MAX_TRACE_FILES_PER_SESSION =
            com.openminis.app.ui.chat.ChatAgentTraceObserver.MAX_TRACE_FILES_PER_SESSION


        

        private val PREFLIGHT_EMPTY_STRING_ALLOWED_FIELDS: Map<String, Set<String>> = mapOf(
            "file_edit" to setOf("new_string"),
        )

        /** True when "" is a legal value for this exact (tool, field) pair. */
        internal fun preflightEmptyStringAllowed(tool: String, field: String): Boolean =
            PREFLIGHT_EMPTY_STRING_ALLOWED_FIELDS[tool]?.contains(field) == true

        /**
         * Reject tool calls that have empty args or are missing required fields
         * BEFORE [executeTool] runs. Returns null when the call is well-formed,
         * or a human-readable reason string when it should be blocked.
         *
         * Driven off the canonical [AgentToolDefinition.required] list so the
         * validator never drifts from the schema published to the model. For
         * string fields we additionally require non-blank content — the model
         * occasionally emits `{"path": ""}` which passes the "key exists" check
         * but is just as broken as a missing key. We do NOT validate type beyond
         * string-emptiness here; richer schema checks (enum, regex, integer
         * range) belong in each tool's own helper because they need tool-specific
         * context.
         *
         * Mirror of iOS preflightValidateToolCall in AIChatViewModel.swift.
         *
         * Lives in the companion (and is `internal`) because it is PURE — it reads
         * only its parameters and companion constants — so unit tests can exercise
         * it without constructing a ChatViewModel and its dependency graph. Mirrors
         * the same `nonisolated static` move on iOS.
         */
        internal fun preflightValidateToolCallImpl(
            name: String,
            args: JSONObject,
            tools: List<AgentToolDefinition>,
        ): String? {
            // Unknown tool names go through to the existing `else` branch in
            // executeTool() which returns "Unknown tool: …". Preflight stays
            // silent so we don't double-fail.
            val toolDef = tools.firstOrNull { it.name == name } ?: return null
            // Required fields that actually gate execution (everything except the
            // non-blocking ones like tool_title — see PREFLIGHT_NON_BLOCKING_FIELDS).
            val enforced = toolDef.required.filter { it !in PREFLIGHT_NON_BLOCKING_FIELDS }
            // Empty args on a tool that requires anything → block. Gate on
            // `enforced` so a tool whose only required field is non-blocking isn't
            // rejected for empty args, and the message lists only real blockers.
            if (args.length() == 0 && enforced.isNotEmpty()) {
                return "Tool '$name' was called with empty arguments {} but requires: ${enforced.joinToString(", ")}."
            }
            val missing = mutableListOf<String>()
            for (field in enforced) {
                // Absent — or present as an explicit JSON null. org.json reports
                // has() == true for `{"x": null}` and opt() hands back
                // JSONObject.NULL, which is not a String, so a null previously
                // slipped through BOTH checks and reached the tool as a non-String
                // value. Both spellings are genuinely missing.
                if (!args.has(field) || args.isNull(field)) {
                    missing.add(field)
                    continue
                }
                val raw = args.opt(field)
                // Only the truly-empty literal "" is rejected — NOT whitespace.
                // The earlier `.trim().isEmpty()` over-rejected legitimate payloads,
                // most notably file_edit with `new_string: "\n"` (replace a block
                // with a newline) or `old_string: "  "` (match consecutive spaces).
                // Both are valid edits, neither is stream corruption.
                //
                // And even "" is legal for whitelisted (tool, field) pairs:
                // file_edit.new_string == "" is the documented "delete old_string"
                // form, not a missing value. [T-preflight-empty-string-allowed]
                if (raw is String && raw.isEmpty() &&
                    !preflightEmptyStringAllowed(name, field)
                ) {
                    missing.add(field)
                }
            }
            if (missing.isNotEmpty()) {
                return "Tool '$name' is missing required parameter(s): ${missing.joinToString(", ")}."
            }
            return null
        }
        // [T-android-stream-flush-dualpath] Newline fast-path thresholds (iOS parity).
        private const val NEWLINE_FLUSH_MIN_CHARS = 50
        private const val NEWLINE_FLUSH_MAX_LEN = 5_000
        // [T-android-larky-longsession-followup] see uiMessages / hasOlderMessages.
        /** Tail window size used by [uiMessages] when a session exceeds it. */
        const val INITIAL_VISIBLE_MESSAGE_CAP: Int = 200
        /** Each "load older" tap grows the cap by this many messages. */
        const val VISIBLE_MESSAGE_CAP_STEP: Int = 100
        /**
         * Sessions with this many or fewer messages bypass the windowing
         * machinery entirely — the derived `uiMessages` returns the same
         * list reference as `messages`, so Compose sees identity-equal
         * snapshots and the existing flat/stream pipeline is untouched.
         */
        const val LONG_SESSION_THRESHOLD: Int = 300
        // T258: tool block statuses with no committed tool_result. retryLast()
        // drops blocks in any of these states because they would orphan the
        // assistant tool_use entry on retry (the API rejects unmatched
        // tool_use_ids). SUCCESS / FAILED / TIMEOUT / CANCELLED all have a
        // matching tool_result row already persisted and survive the retry.
        private val IN_FLIGHT_TOOL_STATUSES = setOf(
            ToolBlockStatus.STREAMING,
            ToolBlockStatus.PENDING,
            ToolBlockStatus.RUNNING,
        )
        // T145 phase 1: dedicated tag so the streaming-state debug pipeline
        // can be filtered with `adb logcat -s Minis.ChatVMStream:D`.
        // Removed once the retry-state regression is rooted out.
        private const val TAG_STREAM = "ChatVMStream"
        /**
         * Hard ceiling on agent loop iterations within a single user turn.
         * Backstop against runaway tool-call cycles that slip past
         * [ToolLoopDetector] (e.g. visited args/results vary just enough to
         * dodge the global circuit breaker). On reaching the limit the loop
         * finalizes as resumable — see runAgentLoop's tail and
         * [finalizeAtTurnLimit] — so the user gets an inline explanation +
         * Resume button rather than a silently stuck "thinking" indicator.
         * Mirrors iOS AIChatViewModel.maxAgentTurns.
         */
        private const val MIN_MAX_TOKENS = 1024
        // [fix/voice-crash-observability] Generous cap on the persisted draft
        // string (see syncComposerDraft). Covers the vast majority of voice
        // dictations while bounding per-keystroke SharedPreferences serialization
        // cost during high-frequency IME bursts.
        private const val MAX_PERSISTED_DRAFT_CHARS = 5000
        /**
         * Hard ceiling on max_tokens we ever send to a provider, regardless
         * of what the model itself claims. Some models advertise 128K+
         * output windows that in practice produce wandering, low-signal
         * responses and burn through context budget; cap so a single turn
         * can't run away. Mirrors iOS AIChatViewModel.globalMaxTokensCeiling.
         * [T-android-global-max-tokens-128k] Raised 64K → 128K (iOS 8a401ab6):
         * 64K clipped newer large-output models AND the number-budget thinking
         * tiers whose budget is carved out of max_tokens (Anthropic legacy
         * high/xhigh/max, Qwen thinking_budget — DashScope clamps it strictly
         * below max_completion_tokens). Raising only lifts the upper bound —
         * the value is still clamped by the model's own maxOutputTokens and
         * the remaining context window in dynamicMaxTokens().
         */
        private const val GLOBAL_MAX_TOKENS_CEILING = 128_000
        /**
         * Number of recent user-text turns kept verbatim as inference anchors when
         * compactAll runs. The summary stands in for everything older; the LLM
         * still sees the last N user-text turns + their assistant replies + tool
         * I/O so it can answer follow-ups that need verbatim detail rather than
         * the summary's distilled form. Mirrors iOS `compactKeepRecentUserTurns`.
         */
        private const val COMPACT_KEEP_RECENT_USER_TURNS = 3
        /// [T-context-limit-enforce] Minimum number of newest complete turns the
        /// hard-cap trim preserves. Smaller = more aggressive trimming (cheaper),
        /// larger = safer for the current task's context. Chosen to mirror the
        /// `COMPACT_KEEP_RECENT_USER_TURNS` philosophy (current task stays warm)
        /// while trimming much more greedily than compact's 3-turn lookback.
        private const val MIN_CONTEXT_TURNS_TO_KEEP = 6
        /// Max per-tool-call retained `accumulated` JSON snapshots from
        /// `ToolInputDelta`. Drained on preflight failure for diagnosis.
        /** Auto-retry backoff schedule (seconds). Mirrors iOS retryDelays, scaled to task spec: 1s → 2s → 4s. */

        /**
         * Factory for use with `viewModel(factory = ...)`. Binds the ChatViewModel
         * to a NavBackStackEntry's ViewModelStore so the streaming job survives
         * configuration changes (rotation) and re-entering the chat screen while
         * the backstack entry is alive.
         */
        fun factory(
            sessionId: String,
            chatRepository: ChatRepository,
            providerRepository: ProviderRepository,
            appContext: Context,
            memoryRepository: MemoryRepository?,
            skillRepository: com.openminis.app.data.repository.SkillRepository?,
            mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(
                    sessionId = sessionId,
                    chatRepository = chatRepository,
                    providerRepository = providerRepository,
                    context = appContext,
                    memoryRepository = memoryRepository,
                    skillRepository = skillRepository,
                    mcpRepository = mcpRepository,
                ) as T
            }
        }
    }

    private val mediaStore = com.openminis.app.data.storage.MediaStore(context)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // [T-chat-sysinfo-coalesce] Pending coalesce window for appendSystemInfo.
    // Same-iconKind calls within SYSINFO_COALESCE_WINDOW_MS are merged into one
    // ChatMessage with accumulated toolBlocks; different iconKind flushes the
    // current window immediately. Runs on Main so the single _messages.value
    // write is atomic from the UI's perspective.
    private var pendingSysInfoJob: Job? = null
    private var pendingSysInfoIconKind: String? = null
    private var pendingSysInfoBlocks: MutableList<AssistantBlock> = mutableListOf()
    private var pendingSysInfoPayload: String? = null
    private var pendingSysInfoFirstId: String? = null

    // ── Long-session window cap ────────────────────────────────────────
    //
    // [T-android-larky-longsession-followup] On sessions with hundreds of
    // ChatMessage entries (Larky's 612-row monster, totalChars ~1.9MB)
    // feeding the whole list into the LazyColumn pipeline caused cascading
    // main-thread cost: per-frame regex/matcher churn from streaming-side
    // detection, repeated AnnotatedString construction for re-anchored
    // items, and LRU thrash on the markdown caches. The list-virtualization
    // is fine on its own, but the streaming pipeline (combine + sample) and
    // the FlatChat flattening both walk the full list every tick.
    //
    // Strategy: keep `_messages` as the canonical full list (every legacy
    // caller — compact / fork / regenerate / agentHistory / send pipeline —
    // still sees the whole thing) and expose a derived `uiMessages` that
    // takes the TAIL N. ChatScreen consumes `uiMessages`; everything else
    // keeps reading `messages`. When the list is short (<= cap) the derived
    // value IS the source list (same reference), so this is zero-overhead
    // for normal sessions.
    //
    // Users scroll up through the windowed slice; when they reach the top
    // of the tail-window AND older messages exist, [loadOlderMessages]
    // bumps the cap by [WINDOW_STEP] and the derived flow re-emits with
    // the older slice included.
    //
    // Reset on session load (different sessionId) is wired in loadSession.

    private val _visibleMessageCap = MutableStateFlow(INITIAL_VISIBLE_MESSAGE_CAP)
    /**
     * Current tail cap. Reflective via [uiMessages]; bump with
     * [loadOlderMessages] when the user scrolls past the windowed top.
     * Reset to [INITIAL_VISIBLE_MESSAGE_CAP] each time [loadSession]
     * (re)mounts a session — different sessions shouldn't inherit each
     * other's caps.
     */
    val visibleMessageCap: StateFlow<Int> = _visibleMessageCap.asStateFlow()

    /**
     * Tail-windowed view of [messages] for ChatScreen's LazyColumn. For
     * sessions with `count <= LONG_SESSION_THRESHOLD` or `count <= cap`
     * this returns the EXACT SAME list reference as `_messages.value` —
     * Compose / collectAsState gets identity-equal snapshots, no extra
     * allocation, no behavior change for normal sessions.
     */
    val uiMessages: StateFlow<List<ChatMessage>> =
        kotlinx.coroutines.flow.combine(_messages, _visibleMessageCap) { raw, cap ->
            // [T-bridge-message-ui-leak-android] Single UI-collection sink for
            // EVERY path that pushes messages to the list (loadSession, live
            // stream append, compact rebuild, snapshot reload, sync refresh…).
            // Filter the internal role-alternation bridge here so it can never
            // surface as a chat bubble regardless of which path produced it —
            // the Android analog of iOS applySnapshot (T-bridge-message-ui-leak).
            // Today the bridge lives in agentHistory only (never in _messages),
            // so this is defensive; it guards against a future refactor routing
            // the bridge into _messages. Only allocate a new list when a bridge
            // is actually present, keeping the identity-equal fast path intact.
            val full = if (raw.any { it.isInternalBridge }) raw.filterNot { it.isInternalBridge } else raw
            if (full.size <= LONG_SESSION_THRESHOLD || full.size <= cap) full
            else full.subList(full.size - cap, full.size)
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            emptyList(),
        )

    /**
     * Whether the current session has older messages above the window.
     * ChatScreen uses this to show / hide the "Load older messages" header
     * pill on the LazyColumn.
     */
    val hasOlderMessages: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(_messages, _visibleMessageCap) { full, cap ->
            full.size > LONG_SESSION_THRESHOLD && full.size > cap
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    /**
     * Bump the visible cap by [VISIBLE_MESSAGE_CAP_STEP], saturating at
     * the total message count. Safe to call when there are no older
     * messages — it's a no-op (cap clamps to size). Called by the
     * LazyColumn's "load older" header when the user reaches the top of
     * the windowed slice.
     */
    fun loadOlderMessages() {
        val totalNow = _messages.value.size
        if (totalNow <= LONG_SESSION_THRESHOLD) return
        val next = (_visibleMessageCap.value + VISIBLE_MESSAGE_CAP_STEP).coerceAtMost(totalNow)
        if (next != _visibleMessageCap.value) {
            _visibleMessageCap.value = next
        }
    }

    /**
     * Streaming side-channel — see [StreamingDelta]. During a live agent
     * turn, [updateAssistantMessage] writes delta-bearing fields here
     * INSTEAD of mutating the messages list. This isolates per-token
     * updates from ChatScreen's top-level recompose scope (the 8980-line
     * mega-composable was being walked at full slot-table cost on every
     * token, costing ~94 ms per recompose). Top-level subscribers
     * (`messages.any/.associate/.isNotEmpty/.lastOrNull`) only see a new
     * list reference at turn *boundaries* — at start (message added) and
     * end (final content synced back).
     *
     * Renderers that need streaming content (AssistantText, Thinking,
     * tool pills, etc.) read this flow per-item inside their composable
     * scope so Compose's stable-skip restricts the recompose blast radius
     * to that one item.
     *
     * The map is keyed by the assistant message id; absent ⇒ no live
     * stream (turn either hasn't started or has already flushed).
     */
    private val _streamingById = MutableStateFlow<Map<String, StreamingDelta>>(emptyMap())
    val streamingById: StateFlow<Map<String, StreamingDelta>> = _streamingById.asStateFlow()

    /** 单调递增回合纪元：每开一个新回合 +1，旧回合晚到 delta 由渲染层按 epoch 忽略。 */
    private var streamEpoch = 0L

    /**
     * 当前活跃回合的 epoch，供 ChatScreen 传入 [mergeStreamingOverlay] 做过滤。
     * 新回合入口递增后，旧回合的 trailing-flush / 残余 delta 因 epoch 不匹配被忽略，
     * 不再产生第二条"正在思考…"残留行。
     */
    fun currentStreamEpoch(): Long = streamEpoch

    /**
     * [T-android-stream-flush-dualpath] Per-message streaming-flush state for
     * the dual-path throttle in [updateAssistantMessage]. Keyed by messageId so
     * the throttle accumulator survives the high-frequency token calls (the
     * earlier per-fragment produceState version reset every fragment rebuild and
     * so never actually throttled — diagnostics showed every tick flushing).
     * Mirrors iOS AIChatViewModel+SSEStream's lastTextDeltaFlush/…Length.
     */
    private class StreamFlushState {
        var lastFlushMs: Long = 0L
        var lastFlushedLen: Int = 0
        var trailingJob: Job? = null
        // [T-android-stream-flush-review] Freshest suppressed delta. Updated on
        // EVERY throttled tick so the trailing job publishes the latest content
        // (not the stale value captured when the job was first scheduled) — a
        // burst of sub-throttle deltas followed by a pause would otherwise leave
        // the side channel several deltas behind.
        var pendingContent: String? = null
        var pendingBlocks: List<AssistantBlock> = emptyList()
        var pendingAwaiting: Boolean = false
    }
    private val streamFlushStates = HashMap<String, StreamFlushState>()

    /**
     * [T-android-stream-flush-review] Cancel a message's pending trailing flush
     * and drop its throttle accumulator. Call from EVERY stream-termination
     * path (natural end, cancel, turn-limit, retry-truncate, clearChat) so a
     * trailing coroutine — which runs on viewModelScope, NOT streamJob, and is
     * therefore NOT cancelled by streamJob.cancel() — can't fire after the
     * side channel was drained and re-revive a stale "thinking" overlay row.
     */
    private fun clearStreamFlushState(id: String) {
        streamFlushStates.remove(id)?.trailingJob?.cancel()
    }
    private fun clearAllStreamFlushStates() {
        streamFlushStates.values.forEach { it.trailingJob?.cancel() }
        streamFlushStates.clear()
    }
    /** Cancel + drop flush states for any message id NOT in [keptIds] (retry/truncate). */
    private fun retainStreamFlushStates(keptIds: Set<String>) {
        val drop = streamFlushStates.keys.filter { it !in keptIds }
        for (id in drop) streamFlushStates.remove(id)?.trailingJob?.cancel()
    }

    // Dual-path flush thresholds — ported from iOS. Time tiers scale with total
    // length; the newline fast-path flushes immediately on a line break once
    // enough new chars have accumulated, gated to short docs so dense
    // box-drawing streams don't pin the flush rate to the per-token cadence.
    private fun streamFlushThrottleMs(len: Int): Long = when {
        len < 500 -> 200L
        len < 2_000 -> 300L
        len < 32_000 -> 500L
        len < 64_000 -> 1_000L
        len < 128_000 -> 1_500L
        else -> 2_000L
    }

    /**
     * Composer draft. Owned by VM so it survives navigation (e.g. push EnvVars
     * and pop back) — `ChatViewModelStore` keeps the VM alive across screen
     * pushes, but `remember { … }` inside `ChatScreen` does not. Mirrors iOS
     * `AIChatView` which binds against `vm.inputText`.
     */
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    // [T-context-exhausted-dialog] iOS-parity prompt for send-at-capacity. When
    // the context reaches EXHAUSTED the send is NOT silently dropped (Android
    // pre-fix behaviour) nor hard-blocked behind an inline message — instead
    // the pending message is stashed and the user is asked what to do
    // (New Session / Clear Chat / Cancel), mirroring iOS AIChatView's
    // "Context Full" alert. Cancel restores the stashed text+attachments to
    // the input field.
    private val _showContextExhaustedDialog = MutableStateFlow(false)
    val showContextExhaustedDialog: StateFlow<Boolean> = _showContextExhaustedDialog.asStateFlow()
    private var pendingExhaustedText: String = ""
    private var pendingExhaustedHasAttachments = false

    /**
     * [T-android-slash-menu-align-ios-prepend] One-shot caret position the
     * composer should apply on the NEXT inputText emission, mirroring iOS
     * `pendingCaret`. Null means "no override — caret to end" (the existing
     * default). Set when the slash flow prepends "/ " (caret lands at 1, right
     * after the slash, so typing filters the menu) or inserts "/<skill> "
     * (caret after the prefix, before the preserved body). The composer reads
     * it once in its inputText LaunchedEffect and clears it via [consumePendingCaret].
     */
    internal val _pendingCaret = MutableStateFlow<Int?>(null)
    val pendingCaret: StateFlow<Int?> = _pendingCaret.asStateFlow()

    /** Read-and-clear the pending caret so it applies exactly once. */
    fun consumePendingCaret(): Int? {
        val c = _pendingCaret.value
        _pendingCaret.value = null
        return c
    }

    /**
     * Chat list scroll state. Hoisted onto the VM so it survives ChatScreen
     * recomposition / disposal triggered by forward navigation (file preview,
     * env-vars push, etc.). `rememberSaveable` was insufficient because the
     * surrounding composition is re-entered on pop and the SaveableStateHolder
     * scope doesn't always restore in time — keeping the LazyListState on the
     * session-scoped VM (kept alive by ChatViewModelStore) guarantees both the
     * firstVisibleItemIndex/offset and the layoutInfo cache survive intact, so
     * the LazyColumn paints its previous viewport on the first frame instead of
     * remeasuring from index 0 (white flash).
     */
    val listState: LazyListState = LazyListState(0, 0)

    /**
     * [fix/setinputtext-caret-intent] Replaces the composer text and, when the
     * caller knows where the caret must land, tags an explicit one-shot caret.
     *
     * [caretOverride] is authoritative: when non-null it is written to
     * [_pendingCaret] (wiping any stale value) so the consuming LaunchedEffect
     * positions the selection exactly there. Use it for EVERY external rewrite
     * that must control the cursor (mention insert, draft restore, slash
     * response). Omit it only when the caller is NOT touching the caret intent
     * at all (IME onValueChange pass-through) — then [_pendingCaret] stays
     * untouched and the editor preserves the user's current cursor via
     * lastTrueCaretEnd.
     */
    fun setInputText(value: String, caretOverride: Int? = null) {
        _inputText.value = value
        if (caretOverride != null) _pendingCaret.value = caretOverride
        syncComposerDraft(value)
    }

    /**
     * [composer-draft-v1] Mirror the composer text of a draft session
     * (__new__<id>) into [ComposerDraftStore] so it survives session switches
     * and process death. A draft has NO row in the sessions table (that was
     * the empty-session residue bug), so the store is its only durable copy.
     * Blanking the composer (manual clear or send) frees the draft slot.
     */
    private fun syncComposerDraft(value: String) {
        // Only the plain, still-unsent draft owns the persistent slot:
        //  - isDraft: real sessions keep the in-memory composer behavior.
        //  - realSessionId empty: after the first send this route is an alias
        //    of a real conversation. Re-claiming the slot here would make the
        //    next "New Chat" resolve back into the sent chat instead of a
        //    fresh draft.
        //  - no __grp__ suffix: group-bound drafts stay transient (v1 scope);
        //    letting them claim the slot would bind the next "New Chat" to
        //    that group.
        if (!isDraft || realSessionId.isNotEmpty() || sessionId.contains("__grp__")) return
        if (value.isBlank()) {
            com.openminis.app.data.ComposerDraftStore.clearDraft(context, sessionId)
        } else {
            // [fix/voice-crash-observability] Cap the persisted draft length.
            // IME voice dictation drives onValueChange with high-frequency,
            // large text bursts; persisting the FULL draft on every burst means
            // re-serializing a multi-KB string into SharedPreferences on every
            // keystroke — pure memory/GC pressure on the main thread (the same
            // thread that is also reconciling the whole history list). Truncating
            // the persisted copy to a generous ceiling bounds that cost without
            // changing any user-visible behavior except recovering a (rare,
            // >MAX_PERSISTED_DRAFT_CHARS long) draft slightly shortened after
            // process death. The in-memory composer is untouched.
            val persisted = if (value.length > MAX_PERSISTED_DRAFT_CHARS) {
                value.substring(0, MAX_PERSISTED_DRAFT_CHARS)
            } else {
                value
            }
            com.openminis.app.data.ComposerDraftStore.saveText(context, sessionId, persisted)
        }
    }

    /**
     * [T-selection-add-to-input] Append [snippet] to the chat composer
     * with a single trailing space:
     *   - composer empty → `"<snippet> "`
     *   - composer non-empty → `"<existing> <snippet> "`
     *
     * Whitespace between [existing] and [snippet] is normalized to a
     * single space so we never produce `"foo  bar "` when the user's
     * draft happens to end in a trailing space already.
     */
    fun appendToInputText(snippet: String) {
        val cleaned = snippet.trim()
        if (cleaned.isEmpty()) return
        val current = _inputText.value
        val joined = if (current.isBlank()) {
            "$cleaned "
        } else {
            current.trimEnd() + " " + cleaned + " "
        }
        setInputText(joined, caretOverride = joined.length)
    }

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /**
     * T261: tool detail sheet visibility, persistent across LazyColumn
     * recomposition / item disposal so a streaming tool's sheet doesn't
     * snap shut when its pill scrolls out of viewport. Stable key = tool
     * block id (server-assigned tool_use_id). Null = closed.
     *
     * Lifecycle: opened by [openToolDetail], closed by [closeToolDetail]
     * (user dismiss) or by ChatScreen's existence-guard LaunchedEffect when
     * the underlying block is gone (T258 retry-preserve drops in-flight
     * tools, session switch, etc.). Not persisted to disk — sheet is a
     * transient UI state.
     */
    internal val _selectedToolDetailId = MutableStateFlow<String?>(null)
    val selectedToolDetailId: StateFlow<String?> = _selectedToolDetailId.asStateFlow()

    // [T-android-split-chat] openToolDetail / closeToolDetail moved to ChatViewModelUiStateExt.kt.

    /**
     * True when the user cancelled mid-turn and the conversation can be
     * resumed by re-prompting the model to pick up where it left off.
     * Mirrors iOS AIChatViewModel.canResume. Cleared by [resume], by the
     * next real [sendMessage], or on error.
     */
    private val _canResume = MutableStateFlow(false)
    val canResume: StateFlow<Boolean> = _canResume.asStateFlow()

    /**
     * T187: id of a user message currently being re-edited via the
     * long-press → Edit context menu. While non-null, the composer
     * shows an "Exit Edit Mode" pill, and the next sendMessage()
     * call truncates the conversation from this message (inclusive)
     * before persisting the new content as a fresh user turn.
     * Mirrors iOS AIChatViewModel.editingMessageIndex.
     */
    private val _editingMessageId = MutableStateFlow<String?>(null)
    val editingMessageId: StateFlow<String?> = _editingMessageId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _modelName = MutableStateFlow("")
    val modelName: StateFlow<String> = _modelName.asStateFlow()

    /** T201: gate the init-time `config.collect` re-resolver so the StateFlow's
     *  replay cache can't beat [loadSession] to setting `_modelName`. Without
     *  this, opening a session that previously fell back mid-run flashes the
     *  default model name for one frame before the persisted binding settles. */
    private val _sessionLoaded = MutableStateFlow(false)

    /**
     * [fix/history-open-at-bottom-04] Public read-only "data is ready" signal.
     * Flipped to true in [loadSession]'s `finally` (covers every path: normal
     * completion, early `return@launch` for draft/missing session, and
     * exception). Exposed for the init-time config re-resolver gate (below)
     * and any future data-ready consumers. NOTE: the INITIAL_OPEN scroll no
     * longer keys off this — that scroll is owned by the flatten collector's
     * first non-empty flatItems publish, because this signal flips BEFORE the
     * async flatten chain actually builds the rows.
     */
    val sessionLoaded: StateFlow<Boolean> = _sessionLoaded.asStateFlow()

    private val _sessionTitle = MutableStateFlow("New Chat")
    val sessionTitle: StateFlow<String> = _sessionTitle.asStateFlow()

    /** T-chat-title-pill: category drives the icon shown in the sticky title
     *  pill (mirrors SessionRow's categoryStyle lookup). Null on draft sessions
     *  and until LLM title-generation tags the session. */
    private val _sessionCategory = MutableStateFlow<String?>(null)
    val sessionCategory: StateFlow<String?> = _sessionCategory.asStateFlow()

    internal val _attachments = MutableStateFlow<List<InputAttachment>>(emptyList())
    val attachments: StateFlow<List<InputAttachment>> = _attachments.asStateFlow()

    /**
     * One-shot composer-side image-budget events (T-imgsize). Emitted by
     * [prepareUserAttachments] when [ImageBudget.applyMessageBudget] either
     * re-encodes oversize local attachments or drops images that would push
     * the message over the cumulative cap. ChatScreen collects this flow
     * and surfaces a localized Snackbar — provider-boundary compression
     * (history images) does not emit here to keep history-replay silent.
     */
    private val _imageBudgetEvent = MutableSharedFlow<ImageBudget.BudgetResult>(extraBufferCapacity = 4)
    val imageBudgetEvent: SharedFlow<ImageBudget.BudgetResult> = _imageBudgetEvent.asSharedFlow()

    /**
     * Request-level image-budget events (T-request-imgsize). Emitted by
     * [applyRequestImageBudget] when the cumulative history image payload
     * exceeds [ImageBudget.MAX_REQUEST_BYTES] and older images had to be
     * elided to text placeholders. Distinct from [imageBudgetEvent] so the
     * UI Snackbar can show a different message ("older images compacted")
     * and the two events don't race.
     */
    private val _requestBudgetEvent = MutableSharedFlow<ImageBudget.RequestBudgetPlan>(extraBufferCapacity = 4)
    val requestBudgetEvent: SharedFlow<ImageBudget.RequestBudgetPlan> = _requestBudgetEvent.asSharedFlow()

    /**
     * [T-android-tool-autoscroll] Fire-and-forget edge events that ask the
     * ChatScreen to scroll the LazyColumn to the visual bottom (index 0 under
     * reverseLayout). Distinct from the streaming-auto-follow collector — that
     * pipeline needs growth ticks to advance its distinctUntilChanged tuple,
     * but agent-loop START events (sendMessage, resume / "Continue", retry)
     * produce only a brief thinking placeholder before any content streams.
     * Without an explicit edge signal, the placeholder + composer interaction
     * area sits behind the input bar until the model's first token arrives
     * and the regular auto-follow finally fires. Each ViewModel entry that
     * starts a fresh agent-loop turn emits to this flow.
     */
    private val _forceScrollToBottom = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val forceScrollToBottom: SharedFlow<Unit> = _forceScrollToBottom.asSharedFlow()

    private val _availableGroups = MutableStateFlow<List<ModelGroup>>(emptyList())
    val availableGroups: StateFlow<List<ModelGroup>> = _availableGroups.asStateFlow()

    private val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()

    private val _selectedGroupName = MutableStateFlow("")
    val selectedGroupName: StateFlow<String> = _selectedGroupName.asStateFlow()

    private val _providerName = MutableStateFlow("")
    val providerName: StateFlow<String> = _providerName.asStateFlow()

    /** Incremented when a model fallback occurs — UI observes this to flash the model capsule. */
    private val _fallbackTrigger = MutableStateFlow(0)
    val fallbackTrigger: StateFlow<Int> = _fallbackTrigger.asStateFlow()

    /**
     * [T-error-no-permanent-scars] One-shot event for the UI to show a
     * transient snackbar/toast when a model-group fallback switches models.
     * The event is consumed by ChatScreen's LaunchedEffect and displayed as a
     * temporary Snackbar (auto-dismisses after a few seconds). Unlike the info
     * block that used to be inserted into the message stream, this leaves no
     * permanent trace in the chat record.
     */
    private val _fallbackToastEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val fallbackToastEvent: SharedFlow<String> = _fallbackToastEvent.asSharedFlow()

    /**
     * [T-recovery] Per-member runtime health (429 cooldown / circuit breaker /
     * dead) lives in GroupRouter now — this class used to own an
     * `entryId → cooldown-until` map here that was declared and cleared but
     * never written or read (dead scaffolding; the recovery dimension was
     * designed in 08-08, partially migrated into a DB column, then rolled
     * back). The real implementation: GroupRouter.recordResult demotes on
     * failure, selection/fallback skip unhealthy members, and recovery is
     * automatic when Cooling / OpenCircuit expire.
     */

    private val _activeEntryId = MutableStateFlow<String?>(null)
    val activeEntryId: StateFlow<String?> = _activeEntryId.asStateFlow()

    /** Prompts enqueued while the agent loop is running. Drained after the loop finishes. */
    private val _promptQueue = MutableStateFlow<List<QueuedPrompt>>(emptyList())
    val promptQueue: StateFlow<List<QueuedPrompt>> = _promptQueue.asStateFlow()

    /**
     * Input-token count reported by the most recent API call, used by
     * [ContextPolicy] as the "estimated tokens" gate before sending. Zero
     * means either we've never called the model or the provider didn't return
     * a usage payload — in which case we treat the turn as low-pressure.
     */
    private val _lastTurnContextTokens = MutableStateFlow(0)
    val lastTurnContextTokens: StateFlow<Int> = _lastTurnContextTokens.asStateFlow()

    /**
     * Latest compact summary for the current session, loaded from the DB on
     * [loadSession] and re-populated after [compactAll] finishes. When non-null,
     * [effectiveAgentHistory] prepends it as a `<context-summary>` user message
     * so the model sees a condensed recap of the turns we folded away while
     * keeping the full [agentHistory] on disk as an audit trail. Mirrors iOS
     * Phase-B compact semantics (summary synthesized at inference time, never
     * baked back into agentHistory).
     */
    private val _compactSummary = MutableStateFlow<String?>(null)
    val compactSummary: StateFlow<String?> = _compactSummary.asStateFlow()

    /** True when a compact-summary LLM call is in flight (UI disables further sends). */
    private val _isCompacting = MutableStateFlow(false)
    val isCompacting: StateFlow<Boolean> = _isCompacting.asStateFlow()

    /** Current auto-retry attempt number (0 = not retrying, 1..MAX = nth retry in flight). */
    private val _autoRetryAttempt = MutableStateFlow(0)
    val autoRetryAttempt: StateFlow<Int> = _autoRetryAttempt.asStateFlow()

    /** Seconds remaining in the current auto-retry countdown (0 = not counting down). */
    private val _autoRetryCountdown = MutableStateFlow(0)
    val autoRetryCountdown: StateFlow<Int> = _autoRetryCountdown.asStateFlow()

    // [T-android-stale-streamjob-clears-isstreaming] @Volatile so cross-coroutine
    // reads (the orphaned previous streamJob's tail block running on a different
    // dispatcher) see the latest assignment. Without it, an old job's
    // `if (streamJob === thisJob)` guard could read a cached reference and
    // wrongly reset _isStreaming on the new live job — the exact race XIN hit
    // 2026-06-12 20:22:26 / 20:23:25 (cancel → resume → cancel → retry, where
    // the cancelled resume's finally fired ~2s after the new retry was already
    // streaming, hiding the Stop button while the new turn was live).
    @Volatile
    private var streamJob: Job? = null
    private var currentProvider: LLMProvider? = null
    private var currentModel: LLMModel? = null

    /** Structured agent history for the agent loop (contentParts-based). */
    private val agentHistory = mutableListOf<LLMMessage>()

    /**
     * All agent tool definitions, recomputed on each read so the memory
     * toggle gate (see [_memoryEnabled]) takes effect immediately when
     * the user flips /memory mid-session without forcing a VM rebuild.
     * The cost is negligible — [AgentTools.makeAgentTools] just builds a
     * fixed list of definition objects, no I/O.
     */
    private val agentTools: List<AgentToolDefinition>
        get() = AgentTools.makeAgentTools(memoryEnabled = _memoryEnabled.value)

    /**
     * Per-session loop detector. Reset alongside [agentHistory] whenever the
     * conversation is rewound (edit/regenerate) so a stale tool-call window
     * can't bleed warnings into a fresh prompt.
     */
    private val toolLoopDetector = ToolLoopDetector()

    /**
     * Programmatic tool-failure logger (T3, ported from OmniBot's
     * SelfImprovingSkillFailureHook). Side-channel only: records a structured
     * block into the session's `.learnings/ERRORS.md` when a tool fails,
     * deduplicated by (toolName + summary) within a 10-minute window. Never
     * touches the ToolExecutionResult returned to the LLM.
     */
    private val toolFailureHook = ToolFailureHook(writeErrorBlock = { block -> appendToolFailureBlock(block) })

    /**
     * Pure-JVM group routing engine (model-group strategy redesign, Phase 1).
     * Owns the "which member to use / in which order to fall back" decisions
     * that previously lived inline in resolveProviderFromGroup and
     * buildFallbackProviders, plus per-member runtime health (wired in
     * Phase 2). Same pattern as ToolFailureHook: no Android deps, injectable
     * clock, unit-testable.
     */
    private val groupRouter = com.openminis.app.data.routing.GroupRouter()

    /**
     * T9: agent execution trace recorder + T7 observation state, extracted to
     * [ChatAgentTraceObserver] (FE-5 route C step 1). Side-channel only —
     * records one JSONL line per event into the session's
     * `workspace/.traces/agent-<ts>.jsonl` so a full agent run (turns → tool
     * calls → results → token usage) can be replayed / filtered / exported
     * afterwards. The trace NEVER alters the LLM result path; a write failure
     * is swallowed like the failure hook.
     *
     * The trace dir resolution runs through PRootKernel per session; the
     * reducer-rejection warnings go through AppLogger with the original
     * TAG_STREAM tag so logcat filtering is unchanged.
     */
    private val traceObserver = ChatAgentTraceObserver(
        traceDirResolver = { sid ->
            PRootKernel.resolveSessionHostPath(sid, "/var/minis/workspace/.traces", context)
        },
        wallClockMs = System::currentTimeMillis,
        monotonicClockMs = System::nanoTime,
        warn = { tag, msg -> AppLogger.warning(tag, msg) },
    )

    /**
     * [FE-5 route C step 3] Adapter exposing exactly the surface
     * [AgentLoopEngine] needs (AgentLoopHost) over this ViewModel's private
     * members. Lives as an inner class so it can call the private
     * implementations without widening their visibility. Every method is a
     * one-line delegation — behavior identical to the pre-extraction inline
     * references.
     */
    private inner class LoopHostAdapter : AgentLoopHost {
        override val activeSessionId: String get() = this@ChatViewModel.activeSessionId
        override fun string(resId: Int, vararg args: Any): String = context.getString(resId, *args)
        override fun emitFallbackToast(text: String) { _fallbackToastEvent.tryEmit(text) }
        override fun updateSessionPreview(text: String) {
            chatRepository.updateSessionPreview(realSessionId.ifEmpty { sessionId }, text)
        }
        override val agentHistory: MutableList<LLMMessage> get() = this@ChatViewModel.agentHistory
        override val agentTools: List<AgentToolDefinition> get() = this@ChatViewModel.agentTools
        override fun sanitizeAgentHistory() = this@ChatViewModel.sanitizeAgentHistory()
        override fun effectiveContextWindowTokens(): Int? = this@ChatViewModel.effectiveContextWindowTokens()
        override fun effectiveAgentHistory(): List<LLMMessage> = this@ChatViewModel.effectiveAgentHistory()
        override fun applyRequestImageBudget(messages: List<LLMMessage>): List<LLMMessage> =
            this@ChatViewModel.applyRequestImageBudget(messages)
        override fun checkContextBeforeSend(): Boolean = this@ChatViewModel.checkContextBeforeSend()
        override fun offloadContextIfNeeded(contextWindow: Int, lastContextTokens: Int, force: Boolean) =
            this@ChatViewModel.offloadContextIfNeeded(contextWindow, lastContextTokens, force)
        override fun trimContextHistoryWindow(contextWindow: Int, lastContextTokens: Int) =
            this@ChatViewModel.trimContextHistoryWindow(contextWindow, lastContextTokens)
        override fun unavailableGroupMembers(): List<String> = this@ChatViewModel.unavailableGroupMembers()
        override fun updateAssistantMessage(
            assistantId: String, text: String, isStreaming: Boolean,
            blocks: List<AssistantBlock>, isAwaitingModelResponse: Boolean,
        ) = this@ChatViewModel.updateAssistantMessage(assistantId, text, isStreaming, blocks, isAwaitingModelResponse)
        override fun buildTurnParts(
            allToolBlocks: List<AssistantBlock>, turnStartBlockIndex: Int,
            toolCallInputs: Map<String, String>,
        ): List<AgentContentPart> = this@ChatViewModel.buildTurnParts(allToolBlocks, turnStartBlockIndex, toolCallInputs)
        override fun buildAssistantPartsJson(
            parts: List<AgentContentPart>, toolBlockMeta: Map<String, AssistantBlock>,
        ): String = this@ChatViewModel.buildAssistantPartsJson(parts, toolBlockMeta)
        override suspend fun persistAssistantTurn(
            parts: List<AgentContentPart>, usage: LLMUsage?, reasoningContent: String?,
            toolBlockMeta: Map<String, AssistantBlock>, modelId: String?, entryId: String?,
        ): String? = this@ChatViewModel.persistAssistantTurn(
            parts, usage, reasoningContent, toolBlockMeta, modelId, entryId)
        override suspend fun persistToolResultMessage(parts: List<AgentContentPart>): String? =
            this@ChatViewModel.persistToolResultMessage(parts)
        override suspend fun executeTool(
            name: String, argsJson: String, toolId: String,
            toolBlocks: MutableList<AssistantBlock>, assistantId: String, currentText: String,
        ): ToolExecutionResult = this@ChatViewModel.executeTool(name, argsJson, toolId, toolBlocks, assistantId, currentText)
        override fun preflightValidateToolCall(
            name: String, args: JSONObject, tools: List<AgentToolDefinition>,
        ): String? = this@ChatViewModel.preflightValidateToolCall(name, args, tools)
        override fun setInlineError(errorText: String, detail: String?) =
            this@ChatViewModel.setInlineError(errorText, detail)
        override fun setTransientInlineError(errorText: String) =
            this@ChatViewModel.setTransientInlineError(errorText)
        override fun clearInlineError() = this@ChatViewModel.clearInlineError()
        override fun dynamicMaxTokens(provider: LLMProvider, lastContextTokens: Int): Int =
            this@ChatViewModel.dynamicMaxTokens(provider, lastContextTokens)
        override fun streamChatTurnOffloaded(
            provider: LLMProvider, messages: List<LLMMessage>, systemPrompt: String?,
            maxTokens: Int, temperature: Double?, imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
        ): Flow<LLMStreamChunk> = this@ChatViewModel.streamChatTurnOffloaded(
            provider, messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
        override fun generateSessionTitleIfNeeded() = this@ChatViewModel.generateSessionTitleIfNeeded()
        override suspend fun injectQueuedPromptsAsNewTurn(
            finishedAssistantId: String,
            finishedAccumulatedText: String,
            finishedAllToolBlocks: List<AssistantBlock>,
        ): InjectedTurn? = this@ChatViewModel.injectQueuedPromptsAsNewTurn(
            finishedAssistantId, finishedAccumulatedText, finishedAllToolBlocks)
        override suspend fun drainQueuedPrompts(): String? = this@ChatViewModel.drainQueuedPrompts()
        override fun finalizeAtTurnLimit(assistantId: String, text: String, blocks: List<AssistantBlock>) =
            this@ChatViewModel.finalizeAtTurnLimit(assistantId, text, blocks)
        override val toolLoopDetector: ToolLoopDetector get() = this@ChatViewModel.toolLoopDetector
        override val groupRouter: com.openminis.app.data.routing.GroupRouter get() = this@ChatViewModel.groupRouter
        override val thinkingLevel: ThinkingLevel get() = _thinkingLevel.value
        override val isStreaming: Boolean get() = _isStreaming.value
        override val currentModelSupportsReasoning: Boolean get() = this@ChatViewModel.currentModelSupportsReasoning
        override val enhancedCacheEnabled: Boolean get() = _enhancedCacheEnabled.value
        override val autoRetryAttempt: Int get() = _autoRetryAttempt.value
        override val autoRetryCountdown: Int get() = _autoRetryCountdown.value
        override val activeEntryId: String? get() = _activeEntryId.value
        override val promptQueueDepth: Int get() = _promptQueue.value.size
        override val activeConfigModelEntries: List<com.openminis.app.data.model.ModelEntry>
            get() = providerRepository.config.value.modelEntries.toList()
        override fun providerInstanceLabel(instanceId: String): String? =
            providerRepository.instance(instanceId)?.label
        override fun setAutoRetry(attempt: Int, countdownSec: Int) {
            _autoRetryAttempt.value = attempt
            _autoRetryCountdown.value = countdownSec
        }
        override fun setAutoRetryCountdown(countdownSec: Int) { _autoRetryCountdown.value = countdownSec }
        override fun resetAutoRetry() {
            _autoRetryAttempt.value = 0
            _autoRetryCountdown.value = 0
        }
        override fun noteModelNames(modelName: String, providerName: String?, entryId: String?) {
            _modelName.value = modelName
            if (providerName != null) _providerName.value = providerName
        }
        override fun setActiveEntryId(entryId: String) { _activeEntryId.value = entryId }
        override fun setCanResume(value: Boolean) { _canResume.value = value }
        override fun bumpFallbackTrigger() { _fallbackTrigger.value++ }
        override fun setLastTurnContextTokens(tokens: Int) { _lastTurnContextTokens.value = tokens }
        override fun setEnhancedCache(enabled: Boolean) { _enhancedCacheEnabled.value = enabled }
        override fun updateCurrentModel(model: com.openminis.app.data.model.LLMModel) {
            currentModel = model
        }
        override fun setCurrentProvider(provider: LLMProvider) {
            this@ChatViewModel.currentProvider = provider
        }
        override fun setProviderName(label: String) { _providerName.value = label }
    }

    /** Host adapter for the agent-loop engine (see [LoopHostAdapter]). */
    private val loopHost: AgentLoopHost by lazy { LoopHostAdapter() }


    /**
     * Cached reference to the lazily-created [BrowserTabPool] so
     * [ensureSession] can re-point it at the real session id after a rename.
     * Read only through [browserTabPool]; the backing `by lazy` fills this in.
     */
    @Volatile
    private var _browserTabPoolRef: BrowserTabPool? = null

    /** Browser tab pool for browser_use tool. Lazily created on first access. */
    val browserTabPool: BrowserTabPool by lazy {
        BrowserTabPool(context).also {
            it.setSession(activeSessionId)
            // Surface download start/finish/failure as system-info notices in
            // this chat. May fire from the pool's IO scope — hop to Main since
            // appendSystemInfo does a read-modify-write on _messages.
            it.onDownloadEvent = { text ->
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    appendSystemInfo(text, "info")
                }
            }
            _browserTabPoolRef = it
        }
    }

    internal val _showBrowserSheet = MutableStateFlow(false)
    val showBrowserSheet: StateFlow<Boolean> = _showBrowserSheet.asStateFlow()

    // [T-android-split-chat] toggleBrowserSheet / dismissBrowserSheet /
    // openBrowserSheetForUrl moved to ChatViewModelUiStateExt.kt.

    internal val _showMemorySheet = MutableStateFlow(false)
    val showMemorySheet: StateFlow<Boolean> = _showMemorySheet.asStateFlow()

    /** Set true by the slash-command "/clear" handler so ChatScreen can mirror
     *  it into the local Compose state that drives the existing
     *  showClearChatDialog confirmation. ChatScreen calls
     *  [ackClearChatConfirmRequest] after observing to reset back to false. */
    private val _clearChatConfirmRequested = MutableStateFlow(false)
    val clearChatConfirmRequested: StateFlow<Boolean> = _clearChatConfirmRequested.asStateFlow()

    fun ackClearChatConfirmRequest() {
        _clearChatConfirmRequested.value = false
    }

    private val _memoryToolRecords = MutableStateFlow<List<MemoryToolRecord>>(emptyList())
    val memoryToolRecords: StateFlow<List<MemoryToolRecord>> = _memoryToolRecords.asStateFlow()

    /**
     * Revoke a previously recorded memory_write by removing its entry from
     * today's or yesterday's daily log on disk, and dropping the row from
     * [memoryToolRecords] so the SessionMemorySheet reflects the removal.
     *
     * Returns the repository result so the UI can show a success / not-found
     * / I/O error dialog. The original ChatMessage tool block stays in the
     * conversation history untouched — only the on-disk entry and the
     * op-log row are mutated.
     */
    fun revokeMemoryRecord(record: MemoryToolRecord): com.openminis.app.data.repository.MemoryRepository.EntryMutationResult {
        val repo = memoryRepository
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.IOError("Memory not available")
        val written = record.writtenContent
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.NotFound
        val result = repo.revokeEntry(written)
        if (result is com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.Success) {
            _memoryToolRecords.value = _memoryToolRecords.value - record
        }
        return result
    }

    /**
     * T149: revoke every `memory_write` tool block embedded in the supplied
     * messages. Used when a retry path truncates the conversation — the
     * deleted assistant turns may have written entries to today's daily
     * memory log, and leaving them on disk after the conversation rewinds
     * means user-visible history is gone but the side effects remain.
     *
     * We match by the `content` field of the tool args against
     * [MemoryToolRecord.writtenContent] (which is what `revokeMemoryRecord`
     * keys on). If multiple records share the same content body — possible
     * if the agent wrote the same note twice — we revoke them in the
     * reverse insertion order so the most recent disk write is removed
     * first; the repository's revokeEntry only removes the first match
     * each call, so subsequent records may end up NotFound on disk but
     * still get pulled from the in-memory record list.
     */
    private fun revokeMemoryWritesInDeletedMessages(deletedMessages: List<ChatMessage>) {
        if (memoryRepository == null) return
        val deletedContents = mutableListOf<String>()
        for (msg in deletedMessages) {
            for (block in msg.toolBlocks) {
                if (block.kind != "tool_use") continue
                if (block.toolName != "memory_write") continue
                val content = try {
                    JSONObject(block.toolArgs).optString("content", "")
                } catch (_: Exception) { "" }
                if (content.isNotBlank()) deletedContents.add(content)
            }
        }
        if (deletedContents.isEmpty()) return
        Log.i(TAG, "revokeMemoryWritesInDeletedMessages: ${deletedContents.size} write(s) to revoke")
        for (content in deletedContents.asReversed()) {
            // Find the latest matching record so revoke targets the most
            // recent disk entry first. Snapshot value because revoke mutates
            // the flow.
            val record = _memoryToolRecords.value.lastOrNull {
                it.isWrite && it.writtenContent == content
            } ?: continue
            val result = revokeMemoryRecord(record)
            Log.i(TAG, "  revoke result: ${result::class.simpleName}")
        }
    }

    /**
     * Replace the body of a previously recorded memory_write with
     * [newContent]. Mirrors iOS `MemoryWriteDetailView.replaceEntryInLog`.
     * On success, also updates the in-memory [MemoryToolRecord] so a
     * subsequent revoke or revisit sees the new body.
     */
    fun replaceMemoryRecord(
        record: MemoryToolRecord,
        newContent: String,
    ): com.openminis.app.data.repository.MemoryRepository.EntryMutationResult {
        val repo = memoryRepository
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.IOError("Memory not available")
        val old = record.writtenContent
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.NotFound
        val result = repo.replaceEntryBody(old, newContent)
        if (result is com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.Success) {
            _memoryToolRecords.value = _memoryToolRecords.value.map {
                if (it === record) it.copy(
                    writtenContent = newContent,
                    preview = newContent.lines().firstOrNull { line -> line.isNotBlank() }?.take(100) ?: "",
                ) else it
            }
        }
        return result
    }

    // ── Slash commands (mirrors iOS AIChatViewModel) ────────────────────

    // [T-memory-global-toggle-settings-ui-android] Seed from the global
    // pref so a fresh draft VM honors the user's "memory off by default"
    // choice from Settings. For loaded sessions, `loadSession()` later
    // overwrites this with the per-session DB value, which takes
    // precedence — the global pref only applies to drafts.
    internal val _memoryEnabled =
        MutableStateFlow(com.openminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(context))
    val memoryEnabled: StateFlow<Boolean> = _memoryEnabled.asStateFlow()

    internal val _thinkingLevel = MutableStateFlow(ThinkingLevel.OFF)
    val thinkingLevel: StateFlow<ThinkingLevel> = _thinkingLevel.asStateFlow()

    /**
     * [T-android-enhanced-cache] Enhanced Cache (1-hour Anthropic cache TTL)
     * toggle. Per-VM memory state, NOT persisted — mirrors iOS
     * `AIChatViewModel.enhancedCacheEnabled`. When true, the active turn's
     * AnthropicProvider is stamped with `enhancedCache = true` just before the
     * request (see the streamMessage choke point).
     */
    internal val _enhancedCacheEnabled = MutableStateFlow(false)
    val enhancedCacheEnabled: StateFlow<Boolean> = _enhancedCacheEnabled.asStateFlow()

    /**
     * [T-android-enhanced-cache] Whether the Enhanced Cache menu item is shown.
     * Mirrors iOS `showEnhancedCacheToggle` (commit 57aaf122): only visible when
     * the current session's resolved provider instance is the *official*
     * Anthropic API (`providerType == anthropic` AND `customBaseURL` is
     * blank) — relays / other providers hide it because they don't honor the
     * 1-hour cache TTL. Recomputes whenever the active entry or provider config
     * changes so switching model/provider updates visibility instantly.
     */
    val showEnhancedCacheToggle: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(
            _activeEntryId,
            providerRepository.config,
        ) { entryId, config ->
            val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
            val instance = entry?.let { e -> config.instances.find { it.id == e.providerInstanceId } }
            instance != null &&
                instance.providerType == com.openminis.app.data.model.ProviderType.anthropic &&
                instance.customBaseURL.isNullOrBlank()
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    /** [T-android-enhanced-cache] True once the user accepted the one-time warning. */
    fun isEnhancedCacheConfirmed(): Boolean =
        com.openminis.app.data.EnhancedCachePrefs.isConfirmed(context)

    /**
     * [T-android-enhanced-cache] Enable Enhanced Cache after the confirmation
     * dialog was accepted (records the durable acknowledgement) and flips the
     * in-memory toggle on.
     */
    fun confirmAndEnableEnhancedCache() {
        com.openminis.app.data.EnhancedCachePrefs.setConfirmed(context)
        _enhancedCacheEnabled.value = true
    }

    /**
     * [T-android-enhanced-cache] Toggle the switch when confirmation is not
     * required (turning it OFF, or turning it ON after the user already
     * acknowledged). The confirmation-gated first enable is handled in the UI.
     */
    fun setEnhancedCacheEnabled(enabled: Boolean) {
        _enhancedCacheEnabled.value = enabled
    }

    /**
     * [T-codex-fast-mode] Fast Mode toggle state. APP-LEVEL and persisted
     * (FastModePrefs / iOS UserDefaults "codexFastModeEnabled") — unlike
     * Enhanced Cache it survives across sessions and process restarts; every
     * chat reads the same flag. The provider reads FastModePrefs directly at
     * request-build time, so this flow only drives the menu row + nav badge.
     */
    internal val _fastModeEnabled =
        MutableStateFlow(com.openminis.app.data.FastModePrefs.isEnabled())
    val fastModeEnabled: StateFlow<Boolean> = _fastModeEnabled.asStateFlow()

    fun setFastModeEnabled(enabled: Boolean) {
        com.openminis.app.data.FastModePrefs.setEnabled(context, enabled)
        _fastModeEnabled.value = enabled
    }

    /**
     * [T-codex-fast-mode] Whether the Fast Mode menu row (and, when enabled,
     * the nav ⚡ badge) is shown. Mirrors iOS activeModelSupportsFastMode
     * (838ba929): the active model id contains "gpt" (case-insensitive —
     * matches the official fast catalog gpt-5.6-sol/terra/luna, gpt-5.5,
     * gpt-5.4) AND the request travels the Responses path — the instance has
     * useResponsesAPI on (any credential/base; Responses relays like sub2api
     * pass the tier through). Chat-completions providers stay excluded.
     * Recomputes on entry/config changes like the Enhanced Cache gate above.
     */
    val showFastModeToggle: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(
            _activeEntryId,
            providerRepository.config,
        ) { entryId, config ->
            val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
            val instance = entry?.let { e -> config.instances.find { it.id == e.providerInstanceId } }
            entry != null && instance != null &&
                entry.model.id.contains("gpt", ignoreCase = true) &&
                instance.useResponsesAPI
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    internal val _showSlashMenu = MutableStateFlow(false)
    val showSlashMenu: StateFlow<Boolean> = _showSlashMenu.asStateFlow()

    internal val _slashFilter = MutableStateFlow("")
    val slashFilter: StateFlow<String> = _slashFilter.asStateFlow()

    internal val _slashMenuSelectedIndex = MutableStateFlow(-1)
    val slashMenuSelectedIndex: StateFlow<Int> = _slashMenuSelectedIndex.asStateFlow()

    /**
     * [T-android-slash-menu-align-ios-prepend] The user's ORIGINAL composer
     * text, saved when the slash menu is opened via the "/" button over
     * existing content. Non-null ⇒ "over-content" mode; null ⇒ the menu was
     * opened by typing a leading "/" (the input itself is the slash query).
     *
     * Mirrors iOS `savedInputBeforeSlash`. On open we PREPEND "/ " to the
     * composer so it reads `/ <original>`; the user's subsequent typing edits
     * only the `/<filter>` token (see [updateSlashMenuState]), while
     * `<original>` is preserved here. Every exit path restores/uses this saved
     * original — never the live `/ <original>` string — so the injected "/ "
     * prefix is always stripped and the body text is never lost.
     *
     * This is the iOS-parity replacement for the earlier boolean marker. It
     * does NOT regress e48fe7a0 ("don't clear input"): the original body is
     * saved and faithfully restored on dismiss / prepended on skill select; it
     * is never discarded. The only behavioral change is that the body now sits
     * AFTER the slash token (iOS semantics) instead of being edited live.
     */
    internal var savedInputBeforeSlash: String? = null

    // ── @ file-mention picker (mirrors iOS AIChatViewModel mention*) ─────
    /**
     * Per-app singleton — scans /var/minis/{workspace,attachments,shared,
     * skills,memory}/<sessionId>/ on demand, ranks matches by basename
     * fuzzy score + scope priority. The composer hooks update*MentionMenu*
     * on every keystroke; the popup composes against [mentionEntries].
     */
    val fileMentionIndex: FileMentionIndex by lazy {
        // T219: provide the SAF-mounted external folders so `@<mountName>`
        // resolves to /var/minis/mounts/<name>/... in the chat composer.
        // PRootKernel holds the MountedFoldersStore reference (set at app
        // launch by MinisApp); reading via a closure means the index sees
        // an up-to-date snapshot on every rescan without a manual refresh.
        FileMentionIndex(
            filesDir = java.io.File(context.applicationContext.filesDir, "minis-global"),
            mountsProvider = {
                com.openminis.app.sandbox.PRootKernel
                    .mountEntriesForIndex(context.applicationContext)
            },
        )
    }

    internal val _showMentionMenu = MutableStateFlow(false)
    val showMentionMenu: StateFlow<Boolean> = _showMentionMenu.asStateFlow()

    internal val _mentionFilter = MutableStateFlow("")
    val mentionFilter: StateFlow<String> = _mentionFilter.asStateFlow()

    /** Caret index of the active `@` in [inputText], or -1 when no token is open. */
    internal val _mentionAnchor = MutableStateFlow(-1)

    /** Live-filtered candidate list. Combines the index's [FileMentionIndex.entries]
     * with [mentionFilter] so matches refresh as the user types and as the
     * background scan emits more entries. Capped at 50 like iOS. */
    val mentionEntries: StateFlow<List<FileMentionIndex.Entry>> = combine(
        fileMentionIndex.entries,
        _mentionFilter,
    ) { _, filter -> fileMentionIndex.matches(filter, limit = 50) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isMentionScanning: StateFlow<Boolean>
        get() = fileMentionIndex.isScanning

    /**
     * T-at-filepicker-keyboard: highlighted row in the @-mention picker. -1 when
     * the menu is closed or the filtered list is empty. Mirrors iOS
     * `mentionSelectedIndex` so a hardware-keyboard user can Up/Down through
     * candidates and hit Return to commit the highlighted entry. Touch users
     * still tap rows directly — the highlight just shows which row Return
     * would land on.
     */
    internal val _mentionSelectedIndex = MutableStateFlow(-1)
    val mentionSelectedIndex: StateFlow<Int> = _mentionSelectedIndex.asStateFlow()

    val currentModelSupportsReasoning: Boolean
        get() = currentModel?.supportsReasoning != false

    /**
     * [T-android-thinking-level-arch] The thinking ceiling the currently-bound
     * model actually supports. Prefers the active ModelEntry's
     * effectiveMaxThinkingLevel (so a user override on the entry is honored);
     * falls back to the resolved model's catalog default when no entry is
     * pinned (e.g. a group-resolved turn) or the model isn't known.
     */
    private val currentModelMaxThinkingLevel: ThinkingLevel
        get() {
            val entry = _activeEntryId.value?.let { id ->
                providerRepository.config.value.modelEntries.find { it.id == id }
            }
            if (entry != null) {
                return entry.effectiveMaxThinkingLevel
            }
            val model = currentModel ?: return ThinkingLevel.XHIGH
            return model.catalogMaxThinkingLevel
        }

    /**
     * [T-android-thinking-level-arch] Levels the chat composer picker should
     * offer: everything up to the current model's ceiling, EXCLUDING OFF —
     * mirrors iOS availableThinkingLevels (`filter { $0 != .off && $0 <= max }`).
     * There is no standalone "Off" capsule; tapping the already-selected level
     * toggles thinking off (see ThinkingLevelPicker). setThinkingLevel
     * additionally clamps as a belt-and-suspenders defense.
     */
    val availableThinkingLevels: List<ThinkingLevel>
        get() {
            val ceiling = currentModelMaxThinkingLevel
            return ThinkingLevel.entries.filter { it != ThinkingLevel.OFF && it.rank <= ceiling.rank }
        }

    // [T-anthropic-context-window] Token Usage sheet's context-window row.
    // Route through contextWindowTokens (heuristic-backed) so models without an
    // explicit contextWindow — e.g. heuristic-only Claude/Gemini — still report
    // their real 1M window instead of showing blank.
    val currentModelContextWindow: Int?
        get() = effectiveContextWindowTokens()

    /**
     * [T-context-window-sources] Effective context window for capacity
     * judgment (compaction warnings, tool-output offload, empty-response
     * heuristic, Token Usage sheet). Reads LIVE state on every call instead of
     * the `currentModel` snapshot, so editing the model's context window or
     * the bound group's `contextLimitTokens` takes effect on the very next
     * judgment without re-picking the model/group (mirrors iOS fcc22b66):
     *   1. the active entry's model is re-resolved from the current repository
     *      config (folds ModelOverrides live), falling back to the snapshot
     *      only when the entry can't be found (e.g. synced sessions before
     *      config finished loading);
     *   2. the result is clamped by the bound group's `contextLimitTokens`
     *      (null / <=0 = unlimited).
     *
     * [T-context-window-sources] GROUP-PRIORITY policy: when the model's
     * context window is only a heuristic id-guess (`[LLMModel.ContextWindowSource].HEURISTIC` —
     * no real metadata from models.dev / catalog / user override), the guess
     * has NO authority — a 1M-context model silently landing on the 128K guess
     * would waste paid context by capping offload/trim at ⅛ of real capacity.
     * In that case the user's explicit group `contextLimitTokens` IS the
     * authoritative budget (the user's deliberate expression of how much they
     * want to spend), so we use it directly instead of `minOf(guess, group)`.
     * The guess is only kept as a display/capacity fallback when the group has
     * no limit set either (unlimited). When the model window is EXPLICIT (real
     * value), we keep the minOf clamp — never assume a window larger than the
     * model physically supports.
     */
    private fun effectiveContextWindowTokens(): Int? {
        val config = providerRepository.config.value
        val liveModel = _activeEntryId.value
            ?.let { id -> config.modelEntries.find { it.id == id }?.model }
            ?: currentModel
        val window = liveModel?.contextWindowTokens ?: return null
        val groupLimit = _selectedGroupId.value
            ?.let { gid -> config.modelGroups.find { it.id == gid }?.contextLimitTokens }
            ?.takeIf { it > 0 }
        // Group-priority: if the model window is only guessed and the user set
        // an explicit group limit, the group limit IS the budget.
        if (liveModel?.contextWindowSource == LLMModel.ContextWindowSource.HEURISTIC && groupLimit != null) {
            // "Unlimited" means "no override — use the model's own window", so
            // honour that intent by falling back to the model's (heuristic)
            // window rather than returning Int.MAX_VALUE as a real capacity.
            if (groupLimit == Int.MAX_VALUE) return window
            return groupLimit
        }
        return if (groupLimit != null) minOf(window, groupLimit) else window
    }

    /**
     * [T-context-window-sources] Source of the *model-side* context window,
     * so the Token Usage sheet can flag heuristic guesses (a 1M model whose
     * metadata wasn't reported silently lands on the 128K id-guess and wastes
     * paid context) and steer the user to correct the value in the model's
     * details screen. Mirrors [effectiveContextWindowTokens]'s live-model
     * resolution: re-resolve the active entry from the current repository
     * config (folding `ModelOverrides` in), so a user-set override classifies
     * as explicit. The group-limit clamp is intentionally NOT folded in here —
     * group clamping is a deliberate user decision, not a metadata gap, so it
     * must not raise the "heuristic guess" red flag.
     */
    val currentModelContextWindowSource: LLMModel.ContextWindowSource?
        get() {
            val config = providerRepository.config.value
            val liveModel = _activeEntryId.value
                ?.let { id -> config.modelEntries.find { it.id == id }?.model }
                ?: currentModel
            return liveModel?.contextWindowSource
        }

    val currentModelMaxOutputTokens: Int?
        get() = currentModel?.maxOutputTokens

    /**
     * The bound group's configured context limit for the current session,
     * exposed for the Token Usage sheet's transparency annotation. Lets the
     * sheet explain when the effective window is smaller than the group
     * limit — the model's physical window is the binding constraint (see
     * [effectiveContextWindowTokens]'s minOf clamp), which would otherwise
     * look like the sheet "disagrees" with the group editor. `unlimited` is
     * true when the group's limit is the "Unlimited" sentinel
     * (Int.MAX_VALUE), in which case the sheet shows the model's native
     * window without a numeric annotation.
     */
    val currentGroupContextLimit: GroupContextLimit?
        get() {
            val tokens = _selectedGroupId.value
                ?.let { gid -> providerRepository.config.value.modelGroups.find { it.id == gid }?.contextLimitTokens }
                ?: return null
            if (tokens <= 0) return null
            return GroupContextLimit(
                tokens = tokens,
                unlimited = tokens >= UNLIMITED_GROUP_CONTEXT_LIMIT,
            )
        }

    // ── Session token usage (iOS parity: TokenUsageSheet data) ─────────────

    /**
     * Aggregated token usage for this session, computed from all persisted
     * `token_usage` JSON rows. Mirrors iOS [sessionTokenStats].
     *
     * @param context the most recent [LLMUsage.latestContextTokens] — reflects
     * how much of the model's context window was consumed at the last turn.
     * @param loopCount number of agent loop iterations (approximated by
     * max(tool_use blocks, assistant message count), matching iOS).
     */
    data class SessionTokenStats(
        val input: Long,
        val output: Long,
        val cacheRead: Long,
        val cacheWrite: Long,
        val context: Int,
        val loopCount: Int,
    )

    data class ThinkingInfo(
        val supported: Boolean,
        val enabled: Boolean,
        val level: String,
    )

    /**
     * The bound group's context-limit configuration for the current session,
     * as read live by [currentGroupContextLimit]. `tokens` is the raw
     * `contextLimitTokens` value; `unlimited` is true when it is the
     * "Unlimited" slider stop (Int.MAX_VALUE sentinel — the runtime consumer
     * treats it as "no override, use the model's native window").
     */
    data class GroupContextLimit(
        val tokens: Int,
        val unlimited: Boolean,
    )

    /** Sentinel used by the group editor's "Unlimited" stop; mirrors
     * ModelGroupDetailScreen.CONTEXT_LIMIT_UNLIMITED_SENTINEL. */
    private val UNLIMITED_GROUP_CONTEXT_LIMIT: Int = Int.MAX_VALUE

    /** Read-only view of the current thinking configuration for the model. */
    fun thinkingInfo(): ThinkingInfo? {
        val model = currentModel ?: return null
        val supported = model.supportsReasoning != false
        val level = _thinkingLevel.value
        val enabled = supported && level.isEnabled
        val levelText = if (enabled) level.displayName else "—"
        return ThinkingInfo(supported, enabled, levelText)
    }

    /**
     * Load session-level token aggregates from the database. Suspend so the
     * Token Usage sheet can fetch on demand without keeping a live subscription
     * — token data rarely changes mid-view, and we want to avoid reactive
     * overhead per token chunk.
     */
    suspend fun loadSessionTokenStats(): SessionTokenStats {
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return SessionTokenStats(0, 0, 0, 0, 0, 0)
        val usages = chatRepository.sessionTokenUsages(sid)
        var input = 0L
        var output = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var context = 0
        for (json in usages) {
            try {
                val obj = org.json.JSONObject(json)
                input += obj.optLong("inputTokens", 0L)
                output += obj.optLong("outputTokens", 0L)
                cacheRead += obj.optLong("cacheReadTokens", 0L)
                cacheWrite += obj.optLong("cacheCreationTokens", 0L)
                val ctx = obj.optInt("latestContextTokens", 0)
                if (ctx > 0) context = ctx
            } catch (_: Exception) { /* skip malformed row */ }
        }
        val snapshot = _messages.value
        val assistantCount = snapshot.count { it.role == "assistant" }
        val toolCalls = snapshot.filter { it.role == "assistant" }
            .sumOf { msg -> msg.toolBlocks.count { it.kind != "text" && it.kind != "info" } }
        val loops = maxOf(toolCalls, assistantCount)
        return SessionTokenStats(input, output, cacheRead, cacheWrite, context, loops)
    }

    // [T-android-split-chat] toggleMemorySheet / dismissMemorySheet moved to ChatViewModelUiStateExt.kt.

    // ── Slash command API (mirrors iOS AIChatViewModel) ─────────────────

    /** Static catalogue of available slash commands, in display order.
     *  Subtitles are placeholders here — [filteredSlashCommands] always
     *  rebuilds them with the current localized state.
     *
     *  Compact and Thinking are NOT here anymore: they moved to the
     *  customizable chat action pool (ChatMenuPrefs.COMPACT / THINKING —
     *  top-right "..." menu + history-drawer footer) because they are
     *  frequent session-level operations rather than input aids. */
    internal val availableSlashCommands: List<SlashCommand> = listOf(
        SlashCommand(
            id = "clear",
            icon = Icons.Default.Delete,
            title = "Clear",
            subtitle = "",
        ),
        SlashCommand(
            id = "memory",
            icon = Icons.Default.Psychology,
            title = "Memory",
            subtitle = "",
        ),
    )

    // [T-android-split-chat] filteredSlashCommands / updateSlashMenuState /
    // showSlashMenuOverInput / dismissSlashMenu / slashMenuSetSelectedIndex moved
    // to ChatViewModelSlashExt.kt as ChatViewModel extension functions.

    // ── @ file-mention picker driver ──────────────────────────────────────
    // [T-android-split-chat] updateMentionMenuState / dismissMentionMenu /
    // mentionMenuUp / mentionMenuDown / executeSelectedMention / selectMention
    // moved to ChatViewModelMentionExt.kt as ChatViewModel extension functions.

    /**
     * Execute a slash command. Returns the text the composer should hold
     * afterward (caret via [pendingCaret] when relevant).
     *
     * [T-android-slash-menu-align-ios-prepend] Over-content (the menu was
     * opened via the "/" button, so [savedInputBeforeSlash] holds the user's
     * original text): a skill row prepends "/<skill> " to the original; an
     * action command (clear/compact/…) runs as a side effect and restores the
     * original (stripping the injected "/ "). Typed-"/" (no saved original):
     * a skill fills "/<skill> ", an action clears the input. The original body
     * is always preserved — never discarded (no regression of e48fe7a0).
     *
     * [currentInput] is retained for call-site compatibility; the body text is
     * sourced from [savedInputBeforeSlash], not the live string.
     */
    fun executeSlashCommand(cmd: SlashCommand, currentInput: String = ""): String {
        val saved = savedInputBeforeSlash
        // [T-skill-slash a88ea8f9] Skill rows aren't directly executable —
        // they're a typing aid. Fill the composer with the literal slash
        // command; the user then taps Send and the model handles the skill via
        // the existing SKILL.md fragment injection in runAgentLoop.
        if (cmd.isSkill) {
            AppLogger.info(TAG, "[Slash] tap skill id=${cmd.id} title=${cmd.title} → composer fill only")
            savedInputBeforeSlash = null
            _showSlashMenu.value = false
            _slashMenuSelectedIndex.value = -1
            val prefix = "/${cmd.title} "
            // [T-android-slash-menu-align-ios-prepend] iOS parity: over-content
            // (saved != null) → PREPEND "/<skill> " to the original, so the
            // composer reads "/<skill> <original>" with the original as args,
            // caret right after the prefix (before the original). Typed-"/"
            // (saved == null) → just "/<skill> " (the input WAS the partial
            // command). Trailing space lets the user type "/<skill> <args>".
            return if (saved != null) {
                _pendingCaret.value = prefix.length
                prefix + saved
            } else {
                prefix
            }
        }
        AppLogger.info(TAG, "[Slash] tap id=${cmd.id} title=${cmd.title} streaming=${_isStreaming.value} compacting=${_isCompacting.value}")
        savedInputBeforeSlash = null
        _showSlashMenu.value = false
        _slashMenuSelectedIndex.value = -1

        when (cmd.id) {
            "memory" -> toggleMemoryEnabled()
            "clear" -> _clearChatConfirmRequested.value = true
            else -> AppLogger.info(TAG, "[Slash] unrecognized id=${cmd.id} — no dispatch")
        }
        // [T-android-slash-menu-align-ios-prepend] Action command: restore the
        // saved ORIGINAL (stripping the injected "/ " prefix) so the body text
        // survives — never the live "/ <original>". Typed-"/" path → clear.
        if (saved != null) {
            _pendingCaret.value = saved.length
            return saved
        }
        return ""
    }

    /** Toggle memory writes on/off, persist to DB, and append a system-info message. */
    private fun toggleMemoryEnabled() {
        val newValue = !_memoryEnabled.value
        _memoryEnabled.value = newValue
        viewModelScope.launch {
            // [T-empty-session-residue] Don't materialise a row just to store
            // this toggle. On a draft chat (no message yet) realSessionId is
            // empty and the value already lives in _memoryEnabled, which
            // ensureSession() folds into the row at insert time
            // (createSession(memoryEnabled = …)). Forcing ensureSession() here
            // was a root cause of message-less "ghost" sessions. Only write
            // through when the row already exists.
            val sid = realSessionId
            if (sid.isNotEmpty()) {
                chatRepository.dao.updateMemoryEnabled(sid, if (newValue) 1 else 0)
            }
        }
        appendSystemInfo(
            text = if (newValue) {
                context.getString(R.string.sysmsg_memory_writes_on)
            } else {
                context.getString(R.string.sysmsg_memory_writes_off)
            },
            iconKind = "memory",
        )
    }

    /** Toggle thinking between OFF and MEDIUM (matches iOS default toggle semantics).
     *  internal: invoked from the chat-action menu/footer entry (menu_thinking),
     *  no longer from the slash picker (which uses [setThinkingLevel] picker). */
    internal fun toggleThinking() {
        if (!currentModelSupportsReasoning) {
            appendSystemInfo(
                text = context.getString(R.string.sysmsg_thinking_unsupported),
                iconKind = "thinking",
            )
            return
        }
        val newLevel = if (_thinkingLevel.value.isEnabled) ThinkingLevel.OFF else ThinkingLevel.MEDIUM
        _thinkingLevel.value = newLevel
        persistThinkingOverride(newLevel)
        appendSystemInfo(
            text = context.getString(R.string.sysmsg_thinking_set, newLevel.localizedName(context)),
            iconKind = "thinking",
        )
    }

    /**
     * Set thinking level explicitly. Used by the inline level picker in the
     * `/thinking` slash row. Mirrors iOS `setThinkingLevel(_:)` — silently
     * ignored when the current model doesn't support reasoning.
     */
    fun setThinkingLevel(level: ThinkingLevel) {
        if (!currentModelSupportsReasoning) return
        // [T-android-thinking-level-arch] Double-safety clamp: the composer UI
        // already filters to availableThinkingLevels, but never fully trust the
        // caller — cap to the current model's ceiling so a stale/over-range
        // request can't persist a level the model can't reach.
        val ceiling = currentModelMaxThinkingLevel
        val clamped = if (level.rank > ceiling.rank) ceiling else level
        if (_thinkingLevel.value == clamped) return
        _thinkingLevel.value = clamped
        persistThinkingOverride(clamped)
    }

    /**
     * T239: write the user's explicit thinking-level choice back to the
     * sessions row so it survives cold-start. Stored as enum name; null
     * means "no override" (legacy behaviour). We always store a non-null
     * value here — including OFF — because the user's explicit "turn it
     * off for this session" must persist as distinct from "never set".
     *
     * Uses [ensureSession] so toggling on a draft (no DB row yet) first
     * materialises the row, mirroring how toggleMemoryEnabled lands its
     * preference on the persisted id rather than the `__new__…` draft key.
     */
    private fun persistThinkingOverride(level: ThinkingLevel) {
        viewModelScope.launch {
            // [T-empty-session-residue] Do NOT materialise a row just to store
            // a thinking preference. On a draft chat (no message sent yet)
            // realSessionId is empty; the choice already lives in
            // _thinkingLevel and ensureSession() folds it into the row at
            // insert time (createSession(thinkingLevel = …)). Forcing
            // ensureSession() here was a root cause of message-less "ghost"
            // sessions: flip /thinking, leave, and a persisted empty row
            // remained. Only write through when the row already exists.
            val sid = realSessionId
            if (sid.isEmpty()) return@launch
            chatRepository.dao.updateThinkingOverride(sid, level.name)
        }
    }

    /**
     * If `text` is a slash command literal (e.g. "/compact"), run it and
     * return true so the caller can skip the normal send path. Mirrors iOS
     * `tryExecuteInputAsSlashCommand()`. Recognized titles are matched
     * case-insensitively against [availableSlashCommands].
     *
     * Accepts both ASCII `/` and the full-width `／` (U+FF0F): some Chinese/
     * Japanese IMEs auto-substitute the full-width form when the user types
     * `/` while a CJK keyboard layout is active. We treat them identically.
     */
    fun tryExecuteInputAsSlashCommand(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val first = trimmed[0]
        if (first != '/' && first != '／') return false
        val name = trimmed.drop(1).lowercase()
        // [menu-compact-thinking] Compact/Thinking left the slash ROSTER
        // (they live in the "..." menu + drawer footer now), but keep the
        // typed-"/" aliases working so muscle memory doesn't send "/compact"
        // to the model as a plain message. Anything else routes through the
        // roster as before.
        if (name == "compact") {
            compactAll()
            return true
        }
        if (name == "thinking") {
            toggleThinking()
            return true
        }
        val cmd = availableSlashCommands.firstOrNull { it.title.lowercase() == name }
            ?: return false
        executeSlashCommand(cmd)
        return true
    }

    /**
     * Append a system-info block to the conversation. Not persisted — matches the
     * iOS `appendSystemInfo` behavior which surfaces a local notice in the chat
     * stream.
     *
     * [T-chat-sysinfo-coalesce] Consecutive same-iconKind calls within
     * [SYSINFO_COALESCE_WINDOW_MS] are merged into ONE ChatMessage whose
     * toolBlocks accumulate in call order; payload takes the last non-null
     * value. Different iconKind flushes the current window first. The merge
     * runs on Main via viewModelScope so the single _messages.value write is
     * atomic from the UI's perspective and avoids the per-call
     * read-modify-write that used to recompose the entire LazyColumn on every
     * system notice during compact/revert failure chains.
     */
    private fun appendSystemInfo(text: String, iconKind: String, payload: String? = null) {
        val block = AssistantBlock(
            id = "sysinfo_${System.currentTimeMillis()}",
            kind = "info",
            content = text,
            toolName = iconKind,
            // Reuse toolArgs as a freeform payload slot — for `iconKind="compact"`
            // this carries the full summary text so the UI can show an info-icon
            // affordance opening a detail sheet (mirrors iOS CompactSummarySheet).
            toolArgs = payload.orEmpty(),
        )
        // Different iconKind → flush any pending window first (preserve ordering).
        if (pendingSysInfoIconKind != null && pendingSysInfoIconKind != iconKind) {
            flushPendingSysInfo()
        }
        // Start or extend the coalesce window.
        if (pendingSysInfoIconKind == null) {
            pendingSysInfoIconKind = iconKind
            pendingSysInfoFirstId = block.id
            pendingSysInfoBlocks.clear()
            pendingSysInfoPayload = payload
        } else {
            // Same kind: accumulate. Last non-null payload wins.
            if (payload != null) pendingSysInfoPayload = payload
        }
        pendingSysInfoBlocks.add(block)
        // Schedule (or reschedule) the flush at the end of the window.
        pendingSysInfoJob?.cancel()
        pendingSysInfoJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            kotlinx.coroutines.delay(SYSINFO_COALESCE_WINDOW_MS)
            flushPendingSysInfo()
        }
    }

    /** Flush the pending coalesce window into a single ChatMessage. Idempotent. */
    private fun flushPendingSysInfo() {
        pendingSysInfoJob?.cancel()
        pendingSysInfoJob = null
        pendingSysInfoIconKind ?: return
        val blocks = pendingSysInfoBlocks.toList()
        val id = pendingSysInfoFirstId ?: "sysinfo_${System.currentTimeMillis()}"
        pendingSysInfoIconKind = null
        pendingSysInfoBlocks.clear()
        pendingSysInfoPayload = null
        pendingSysInfoFirstId = null
        if (blocks.isEmpty()) return
        _messages.value = _messages.value + ChatMessage(
            id = id,
            role = "system",
            content = "",
            toolBlocks = blocks,
        )
    }

    /**
     * Fold the current session history into a single summary stored in
     * `compact_markers`. Mirrors iOS `compactAll()` + Phase-B semantics:
     *
     *   1. Build a compact conversation transcript (role + parts preview).
     *   2. Call the **current provider's non-streaming `sendMessage`** with a
     *      hardcoded summarization system prompt that emphasises preserving
     *      paths/commands/IDs/decisions/errors/open tasks.
     *   3. Persist a `CompactMarkerEntity` via the DAO; publish via
     *      [_compactSummary] so [effectiveAgentHistory] starts injecting it.
     *   4. agentHistory itself is NOT truncated — the audit trail stays.
     *
     * Concurrency: gated by [_isCompacting] so the slash command can't
     * overlap with an in-flight streaming turn (`_isStreaming`) or another
     * compact. Runs on [Dispatchers.IO].
     */
    /**
     * Public entrypoint used by the debug RPC (`chat.session.compact`) to
     * trigger compaction without going through the ChatScreen slash-command
     * UI path. Mirrors what [executeSlashCommand]("compact") does — just
     * calls [compactAll]. RPC callers can then observe [isCompacting] flipping
     * back to false to know the run finished, and read [compactSummary] for
     * the resulting summary text.
     */
    fun runCompactNow() {
        compactAll()
    }

    /**
     * Public entrypoint for "compact up through this message" (mirrors iOS
     * AIChatViewModel.compactBefore). The chat list's long-press menu and
     * the debug RPC `chat.compact.before` route through here.
     *
     * @param dbMessageId the DB message id to use as the new marker's
     *   anchor. agentHistory range to compact = `[prevAnchor+1, anchorIdx]`
     *   where anchorIdx is the agentHistory position of this id.
     * @param includesBoundary accepted for ABI compatibility with iOS, but
     *   in v2 the anchor IS the caller-supplied message regardless — the
     *   flag is logged and ignored. (iOS made the same simplification.)
     *
     * If the id can't be resolved to an agentHistory entry, this falls
     * back to compactAll() behaviour so the user's gesture isn't lost.
     */
    fun compactBefore(dbMessageId: String, includesBoundary: Boolean = false) {
        AppLogger.info(
            TAG,
            "[Compact] compactBefore() id=${dbMessageId.take(8)} includesBoundary=$includesBoundary " +
                "(v2: includesBoundary ignored — caller-supplied id becomes the anchor)",
        )
        val history = agentHistory.toList()
        val idx = history.indexOfLast { it.dbMessageId == dbMessageId }
        if (idx < 0) {
            AppLogger.warning(
                TAG,
                "[Compact] compactBefore: id=${dbMessageId.take(8)} not in agentHistory — falling back to compactAll()",
            )
            compactAll(anchorIdxOverride = null)
            return
        }
        compactAll(anchorIdxOverride = idx)
    }

    private fun compactAll(anchorIdxOverride: Int? = null) {
        AppLogger.info(TAG, "[Compact] compactAll() invoked streaming=${_isStreaming.value} compacting=${_isCompacting.value} historySize=${agentHistory.size} anchorOverride=$anchorIdxOverride")
        if (_isStreaming.value) {
            AppLogger.info(TAG, "[Compact] aborted: stream in progress")
            appendSystemInfo(
                text = context.getString(R.string.sysmsg_compact_busy_turn),
                iconKind = "compact",
            )
            return
        }
        if (_isCompacting.value) {
            AppLogger.info(TAG, "[Compact] aborted: another compact already in flight")
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
                    Log.w(TAG, "[Compact] loadMessages for raw-id verify failed: ${e.message}")
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
                    Log.w(TAG, "[Compact] No agentHistory entry has a DB-persisted dbMessageId; aborting")
                    withContext(Dispatchers.Main) {
                        appendSystemInfo(context.getString(R.string.sysmsg_compact_anchor_failed), "compact")
                    }
                    return@launch
                }
                if (verifiedAnchorIdx != anchorIdx) {
                    AppLogger.warning(
                        TAG,
                        "[Compact] anchor walked back from idx=$anchorIdx to idx=$verifiedAnchorIdx " +
                            "(closest with id in rawDbIds). Unsynced tail entries will fall on the active side of the divider.",
                    )
                }
                val lastCompactedDbId = history[verifiedAnchorIdx].dbMessageId
                    ?: run {
                        Log.w(TAG, "[Compact] verified anchor at idx=$verifiedAnchorIdx lost dbMessageId; aborting")
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
                        Log.w(TAG, "Failed to persist compact marker: ${it.message}")
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
                    AppLogger.info(TAG, "[Compact] divider: $compactedUICount UI bubbles compacted (history entries: ${toCompact.size})")
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
                Log.w(TAG, "Compact failed", e)
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
                AppLogger.info(TAG, "[Compact] success with ${_promptQueue.value.size} queued prompt(s) — kicking drain")
                resumeQueueAfterCancel()
            }
        }
    }

    /**
     * Revert the most recent compact on this session.
     *
     * Drops the latest CompactMarker (its summary is discarded), refreshes
     * [_cachedLatestMarker] / [_compactSummary] to whatever's left (or
     * null), and rebuilds the message list so the UI reflects the new (or
     * absent) divider. Effect by design:
     *   - If a previous (older) marker exists, divider snaps back to that
     *     marker's anchor; effectiveAgentHistory replays that summary.
     *   - If no previous marker exists, divider disappears, full history
     *     flows to the model again.
     *
     * Mirrors iOS `revertCompact()`. Refuses to run mid-stream.
     */
    fun revertCompact() {
        if (_isStreaming.value) {
            appendSystemInfo(context.getString(R.string.sysmsg_revert_busy_stream), "compact")
            return
        }
        if (_isCompacting.value) {
            appendSystemInfo(context.getString(R.string.sysmsg_revert_busy_compacting), "compact")
            return
        }
        val current = _cachedLatestMarker ?: run {
            appendSystemInfo(context.getString(R.string.sysmsg_revert_nothing), "compact")
            return
        }
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            AppLogger.info(TAG, "[Compact] ━━━ REVERT ━━━ session=${sid.take(8)} markerId=${current.id.take(8)} v=${current.version}")
            val removed = runCatching { chatRepository.dao.deleteCompactMarker(current.id) }.getOrNull() ?: 0
            if (removed <= 0) {
                Log.w(TAG, "[Compact] revert: deleteCompactMarker returned 0 rows for id=${current.id.take(8)}")
                withContext(Dispatchers.Main) {
                    appendSystemInfo(context.getString(R.string.sysmsg_revert_failed_db), "compact")
                }
                return@launch
            }

            // Refresh cache to next-most-recent marker (or null).
            val next = chatRepository.dao.latestCompactMarker(sid)
            _cachedLatestMarker = next
            _compactSummary.value = next?.summary

            // Rebuild UI from DB so the previous marker's divider re-emerges
            // (or all dividers vanish if there are no remaining markers).
            // Drop any stale compact-divider system rows first; the reload
            // path will re-insert one only if the new latest marker calls
            // for it.
            withContext(Dispatchers.Main) {
                _messages.value = _messages.value.filterNot { msg ->
                    msg.role == "system" &&
                        msg.toolBlocks.firstOrNull()?.toolName == "compact"
                }
            }

            // Reload session messages — the existing path runs Phase 2.5
            // graying via applyCompactMarkerGraying() with the new cached
            // marker, so divider position falls back to the previous one
            // (or disappears entirely). loadSession() launches its own
            // viewModelScope job, so call from the Main thread.
            withContext(Dispatchers.Main) {
                reloadSessionFromDb()
            }

            if (next != null) {
                AppLogger.info(TAG, "[Compact] revert DONE: now showing previous marker id=${next.id.take(8)} v=${next.version}")
            } else {
                AppLogger.info(TAG, "[Compact] revert DONE: no remaining markers, full history active")
            }
        }
    }

    /**
     * Re-load the current session's UI message list from disk so any
     * cached-marker change (revert) gets re-applied through Phase-2.5-
     * style restore. Defers to the existing [loadSession] entry; that
     * function reads `_cachedLatestMarker` we just refreshed and routes
     * through [applyCompactMarkerGraying] to (re)position the divider.
     */
    private fun reloadSessionFromDb() {
        if (realSessionId.isEmpty() && sessionId.isEmpty()) return
        loadSession()
    }

    /**
     * Produce the LLM-facing view of agentHistory. Mirrors iOS
     * `effectiveAgentHistory` (AIChatViewModel.swift:3843-3876):
     *
     *   1) No marker / no summary → full agentHistory (zero-copy).
     *   2) Marker has a `firstKeptMessageId` (compactBefore at boundary) →
     *      `[summary] + agentHistory[boundaryIdx ...]`. The boundary message
     *      itself is the first kept entry.
     *   3) compactAll marker (`firstKeptMessageId = null`) → only summary +
     *      messages persisted AFTER the marker, located by
     *      `lastCompactedMessageId`. Messages inserted post-compact (the
     *      user's follow-up turn + the assistant's response) survive; the
     *      summary stands in for everything older.
     *   4) Marker present but no boundary resolvable in current history (e.g.
     *      the boundary message was deleted) → fall through to full history,
     *      same safety net iOS uses.
     *
     * Critically, we do NOT include `agentHistory[< boundaryIdx]` for case
     * (2/3) — that's how the model context stays clean after compact.
     * Earlier behaviour was [summary] + entire agentHistory, which both
     * over-stuffed the context AND duplicated tool_use/tool_result pairs the
     * marker had already replaced; that's what made follow-up turns appear
     * to lose continuity (the model got confused by the dual representation).
     */
    /**
     * Apply the request-level image-byte budget to a fully-resolved
     * message list before handing it to a provider. Images that don't
     * fit under [ImageBudget.MAX_REQUEST_BYTES] (oldest first) are
     * replaced in-place with a text placeholder that, when the original
     * bytes were offloaded to disk, points the model back to the linux
     * path so it can re-fetch via `read_image` if needed. Images that
     * never had a linuxPath are spilled to
     * `attachments/spillover/<sha1>.<ext>` lazily so the placeholder
     * still carries an addressable reference.
     *
     * Returns the budgeted message list. When nothing was elided this
     * is the same instance as [messages].
     *
     * Emits a one-shot [requestBudgetEvent] for the UI Snackbar so the
     * user knows older images were compacted into placeholders.
     */
    private fun applyRequestImageBudget(messages: List<LLMMessage>): List<LLMMessage> {
        // Collect every image in chronological order so the planner can
        // walk in reverse and protect the most recent images.
        data class ImageRef(val msgIdx: Int, val partIdx: Int, val image: ImageBudget.BudgetImage)
        val images = mutableListOf<ImageRef>()
        messages.forEachIndexed { mi, msg ->
            msg.contentParts.forEachIndexed { pi, part ->
                when (part) {
                    is AgentContentPart.ImageData -> {
                        images.add(
                            ImageRef(
                                mi, pi,
                                ImageBudget.BudgetImage(part.data, part.linuxPath, part.mimeType),
                            )
                        )
                    }
                    is AgentContentPart.ToolResult -> {
                        val img = part.imageData
                        if (img != null) {
                            images.add(
                                ImageRef(
                                    mi, pi,
                                    ImageBudget.BudgetImage(
                                        img,
                                        part.imageLinuxPath,
                                        part.imageMimeType ?: "image/jpeg",
                                    ),
                                )
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
        if (images.isEmpty()) return messages

        val plan = ImageBudget.planRequestBudget(images.map { it.image })
        if (!plan.mutated) return messages

        // For dropped images without a linuxPath, lazily spill to disk so
        // the placeholder still gives the model an addressable reference.
        val attachmentsRoot = activeSessionId?.let { sid ->
            java.io.File(context.filesDir, "minis-sessions/$sid/attachments")
        }
        val resolvedPaths = HashMap<ImageBudget.ImagePartId, String?>()
        for (ref in images) {
            val id = ImageBudget.ImagePartId.of(ref.image.data)
            if (id !in plan.droppedIds) continue
            val existing = ref.image.linuxPath
            if (existing != null) {
                resolvedPaths[id] = existing
            } else if (attachmentsRoot != null) {
                resolvedPaths[id] = ImageBudget.ensureSpillover(
                    attachmentsRoot, ref.image.data, ref.image.mimeType,
                )
            } else {
                resolvedPaths[id] = null
            }
        }

        // Build a new message list with dropped image parts replaced by
        // text placeholders. Same-message multiple drops collapse cleanly
        // because we never touch parts whose ids weren't in droppedIds.
        val byMsg = images.groupBy { it.msgIdx }
        val mutated = messages.toMutableList()
        for ((mi, refs) in byMsg) {
            val msg = mutated[mi]
            val newParts = msg.contentParts.toMutableList()
            for (ref in refs) {
                val id = ImageBudget.ImagePartId.of(ref.image.data)
                if (id !in plan.droppedIds) continue
                val path = resolvedPaths[id]
                val placeholder = AgentContentPart.Text(ImageBudget.elidedImagePlaceholder(path))
                val originalPart = newParts[ref.partIdx]
                newParts[ref.partIdx] = when (originalPart) {
                    is AgentContentPart.ImageData -> placeholder
                    is AgentContentPart.ToolResult -> originalPart.copy(
                        // Strip the bytes but keep the structural ToolResult
                        // role; append the elision marker into content so
                        // the model sees it next to the rest of the tool
                        // output. linux path remains in the part for any
                        // subsequent diagnostic round-trip.
                        imageData = null,
                        imageMimeType = null,
                        content = originalPart.content +
                            (if (originalPart.content.isEmpty()) "" else "\n") +
                            ImageBudget.elidedImagePlaceholder(path),
                    )
                    else -> originalPart
                }
            }
            mutated[mi] = msg.copy(contentParts = newParts)
        }

        _requestBudgetEvent.tryEmit(plan)
        AppLogger.info(
            TAG,
            "applyRequestImageBudget: dropped=${plan.droppedCount}/${plan.totalCount} keptBytes=${plan.keptBytes}B elidedBytes=${plan.elidedBytes}B",
        )
        return mutated
    }

    private fun effectiveAgentHistory(): List<LLMMessage> {
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
        //   1. last [COMPACT_KEEP_RECENT_USER_TURNS] user-text turns BEFORE
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
                Log.w(TAG, "[Compact] effectiveAgentHistory v2: anchorId=${anchorId?.take(8) ?: "nil"} not in agentHistory(size=${agentHistory.size}) — degrading to full history (no summary)")
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
            val keepN = COMPACT_KEEP_RECENT_USER_TURNS
            val preAnchorCap = 100
            val walkBack = walkBackUserTurnsBounded(
                anchorIdx = anchorIdx,
                maxUserTextTurns = keepN,
                maxMessages = preAnchorCap,
            )
            val priorIdxResolved: Int? = walkBack.priorIdx
            var priorIdx = walkBack.priorIdx ?: (anchorIdx + 1) // empty preAnchor sentinel
            if (walkBack.stopReason != "userTextTargetMet") {
                AppLogger.info(TAG, "[CompactDiag] eAH v2 walkBack stopped: reason=${walkBack.stopReason} priorIdx=$priorIdx userTextTurnsFound=${walkBack.userTextTurnsFound} preAnchorMsgs=${walkBack.messageCount}")
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
                AppLogger.info(TAG, "[CompactDiag] eAH v2 boundary guard: priorIdx=${walkBack.priorIdx} → $priorIdx (included paired tool_use)")
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
                AppLogger.info(TAG, "[CompactDiag] eAH v2 preAnchor prune: dropped $droppedToolResultCount toolResult(>1kc) + paired toolUse, ${preAnchorRaw.size - preAnchorPruned.size} messages emptied; pruned slice=${preAnchorPruned.size}")
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
            AppLogger.info(TAG, "[CompactDiag] eAH v2 slice: priorIdx=$priorIdx anchorIdx=$anchorIdx agentHistory.size=${agentHistory.size} → preAnchorRaw=$preAnchorRawCount preAnchorSent=${preAnchorPruned.size} postAnchor=${postAnchor.size} summaryChars=${summary.length} priorIdxSource=$priorIdxSource markerId=${marker.id.take(8)}")

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

        Log.w(TAG, "[Compact] effectiveAgentHistory: marker ${marker.id.take(8)} unresolvable in agentHistory (size=${agentHistory.size}); returning full history")
        return agentHistory.toList()
    }

    /** Latest in-memory compact marker, used by [effectiveAgentHistory] to
     * resolve boundaries the same way iOS `cachedLatestMarker` does. Refreshed
     * on every compactAll write and on session reload. */
    @Volatile
    private var _cachedLatestMarker: com.openminis.app.data.db.CompactMarkerEntity? = null

    /**
     * [T5-auto-compact] Session-scoped timestamp of the last AUTO compact
     * (manual /compact does not touch it). Backs the
     * `RECENT_AUTO_COMPACT` debounce in [ContextCompactor.decide] so a
     * session near the compact line doesn't re-compact on every send.
     * Not persisted across cold starts on purpose: the compact marker
     * (lastCompactedMessageId) IS persisted, and after reload the tail-token
     * estimator naturally sees a small tail → TAIL_TOO_SMALL → no repeat.
     */
    @Volatile
    private var lastAutoCompactAtMs = Long.MIN_VALUE

    /**
     * Result of a bounded walk-back. `priorIdx` is the agentHistory index
     * the caller should use as the start of preAnchor; `null` means even
     * the first user turn including anchor would exceed `maxMessages`, so
     * preAnchor should be empty.
     *
     * Mirrors iOS `WalkBackResult` in AIChatViewModel.swift (8b76cd74).
     */
    private fun walkBackUserTurnsBounded(
        anchorIdx: Int,
        maxUserTextTurns: Int,
        maxMessages: Int,
    ): WalkBackResult = walkBackUserTurnsBounded(agentHistory, anchorIdx, maxUserTextTurns, maxMessages)

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
    private suspend fun generateCompactSummaryWithSplitting(
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
                TAG,
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
    private suspend fun generateCompactSummary(conversationText: String): String {
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

    /**
     * Match provider error text against the substring set iOS
     * `isContextTooLargeError` uses (AIChatViewModel+Compaction.swift:879).
     * When true, the splitter halves the input and retries.
     */
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
    private fun checkContextBeforeSend(): Boolean {
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
    private fun maybeTriggerAutoCompact() {
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
                AppLogger.info(TAG, "[AutoCompact] skipped: $decision tokens=$tokens window=$window tail=$tail")
            }
            return
        }
        lastAutoCompactAtMs = System.currentTimeMillis()
        appendSystemInfo(
            text = context.getString(R.string.sysmsg_context_full_auto, tokens, window),
            iconKind = "compact",
        )
        AppLogger.info(TAG, "[AutoCompact] triggering (tokens=$tokens window=$window tail=$tail)")
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
    private suspend fun awaitAutoCompactIfNeeded() {
        if (!_isCompacting.value) return
        val deadline = System.currentTimeMillis() + ContextCompactor.AUTO_COMPACT_MAX_WAIT_MS
        while (_isCompacting.value) {
            if (System.currentTimeMillis() > deadline) {
                AppLogger.warning(TAG, "[AutoCompact] timed out waiting for compact ($deadline); sending without it")
                return
            }
            delay(ContextCompactor.AUTO_COMPACT_POLL_MS)
        }
        AppLogger.info(TAG, "[AutoCompact] compact finished; proceeding with send")
    }

    /**
     * System prompt for the single-shot summarisation call. Matches iOS
     * wording so cross-device summaries stay stylistically aligned.
     *
     * [T5-auto-compact] Single source of truth moved to
     * `ContextCompactor.COMPACT_SUMMARY_SYSTEM_PROMPT` so the auto-compact
     * path, the manual /compact path, and the unit test all pin the same
     * MUST PRESERVE wording (file paths / URLs / UUIDs verbatim).
     */
    private val compactSummarySystemPrompt: String
        get() = ContextCompactor.COMPACT_SUMMARY_SYSTEM_PROMPT

    // T203 part 2: these MUST be declared before `init { loadSession() }` below.
    // viewModelScope.launch defaults to Dispatchers.Main.immediate, which runs
    // the launch body synchronously up to the first suspend point — and the
    // launch body reads `isDraft` before its first suspend. If `isDraft` is
    // declared further down the class, its property initializer hasn't run yet,
    // so the read returns the JVM default (`false`), routing every draft
    // session through the load-from-DB branch. The DB lookup misses (no row
    // for `__new__…` keys), the function returns early, and no model name /
    // group name is ever set on the draft chat — exactly the bug T203 was
    // chasing through the wrong layer.
    /** Whether this is a draft session (not yet persisted to DB). */
    private val isDraft: Boolean = sessionId.startsWith("__new__")

    /** Model group ID from long-press FAB, encoded in the draft session ID. */
    private val initialGroupId: String? = sessionId.substringAfter("__grp__", "").takeIf { it.isNotEmpty() }

    /** The real session ID (same as sessionId for existing sessions, generated on first message for drafts). */
    internal var realSessionId: String = if (isDraft) "" else sessionId

    init {
        loadSession()
        // [composer-draft-v1] Restore the persisted unsent text of a resumed
        // draft session (__new__<id>) after a cold start. Non-draft sessions
        // keep the in-memory behavior (their VM survives in the store while
        // the process lives). The stale-id guard inside restoreText means a
        // draft whose slot was freed (sent / discarded) never resurrects.
        if (isDraft) {
            val restored = com.openminis.app.data.ComposerDraftStore.restoreText(context, sessionId)
            if (restored.isNotEmpty()) _inputText.value = restored
        }
        // [T-session-paused-badge-active-false-positive] Drive the session-list
        // PAUSED badge directly off canResume — the authoritative "this session
        // is interrupted (tap Resume)" flag. This is the single chokepoint over
        // every _canResume setter (background-suspend cleanup, cancel cleanup,
        // loadSession DB detection, …): canResume true → badge on; false
        // (resumed / new send / completed) → badge off. Replaces both the old
        // foreground heuristic AND clear-on-open, so a session the user merely
        // glanced at but didn't resume keeps its badge, and a running/resolved
        // session never shows one.
        viewModelScope.launch {
            canResume.collect { interrupted ->
                if (interrupted) {
                    com.openminis.app.service.SessionBadgeStore.push(
                        sessionId,
                        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED,
                    )
                } else {
                    com.openminis.app.service.SessionBadgeStore.remove(
                        sessionId,
                        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED,
                    )
                }
            }
        }
        // T-android-crash-safe-mode-v2: when the user dismisses the
        // safe-mode dialog, retry the restore that we skipped during
        // cold start. loadSession() is idempotent (re-checks isSafeMode
        // on entry; sessionLoaded gate prevents double-population), so
        // this is a clean "now finish the work you skipped" hook.
        com.openminis.app.crash.CrashFrequencyDetector
            .registerSafeModeClearedListener {
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    runCatching { loadSession() }
                        .onFailure {
                            android.util.Log.w(
                                TAG,
                                "safe-mode-cleared retry loadSession failed: ${it.message}",
                            )
                        }
                }
            }
        // Re-resolve provider when config changes (models may load async)
        viewModelScope.launch {
            // T306: wait for loadSession to finish BEFORE observing config.
            //
            // Pre-T306 we used a "skip first replay" trick that broke under
            // a real race: loadSession suspends inside `chatRepository.getSession`,
            // so when ProviderRepository finishes its async config load and
            // emits the populated value, the collector can fire BEFORE
            // loadSession's `restoreFromBinding(session.modelBinding)` runs.
            // The collector then resolves to the default group's first
            // entry (X), `_modelName` flips to X, and seconds later
            // restoreFromBinding finds Y and re-sets `_modelName` to Y —
            // exactly the "top model picker first shows X, then flickers and switches to Y"
            // the user reported after a fallback persisted Y.
            //
            // Awaiting `sessionLoaded == true` here means loadSession has
            // already had its turn at the persisted binding (success or
            // failure). After that, the `currentProvider == null` guard
            // below correctly captures BOTH the draft case (no binding,
            // currentProvider may still be null because config hadn't
            // loaded yet during loadSession) AND the existing-session
            // case where binding restore failed, while leaving alone any
            // session whose binding successfully resolved to its target.
            _sessionLoaded.first { it }
            providerRepository.config.collect { config ->
                // T278: _availableGroups feeds the model picker sheet — it must
                // track the latest config on every emission, even after the user
                // has selected a model (currentProvider != null). The guard below
                // is for the fallback-resolution path which CAN trample the user's
                // selection; _availableGroups has no such risk because the sheet
                // re-reads it on each open.
                _availableGroups.value = config.modelGroups
                // [T-android-disabled-provider-still-selectable-via-group #34]
                // Runtime re-resolution when a GROUP-bound session's currently
                // active member has its provider DISABLED mid-session. The
                // selection paths (resolveProviderFromGroup → enabledMemberEntries)
                // already skip disabled members, but they only run while
                // currentProvider == null (cold start / fallback). Once a group
                // member is resolved, currentProvider is cached and the guard
                // below short-circuits — so if the user then disables that
                // member's provider (e.g. a Coding Plan whose quota ran out,
                // turned off to force fallback to the next provider), the stale
                // currentProvider keeps routing to the disabled provider's
                // pay-as-you-go model and bills them. Mirror iOS resolveCurrentEntry
                // (a306ce08): when the active entry's provider is no longer
                // enabled, re-resolve the group to its next enabled member. Only
                // for group bindings — a deliberate direct-entry pick is left
                // untouched (it has no in-group alternative to fall back to).
                val groupBound = _selectedGroupId.value
                val activeEntry = _activeEntryId.value
                if (currentProvider != null && groupBound != null && activeEntry != null &&
                    config.modelEntries.isNotEmpty() &&
                    !providerRepository.isEntryProviderEnabled(activeEntry)
                ) {
                    val before = activeEntry
                    if (resolveProviderFromGroup(groupBound)) {
                        AppLogger.info(
                            TAG,
                            "🔀RESOLVE group=$groupBound active entry=$before provider disabled — re-resolved to entry=${_activeEntryId.value} model=${currentModel?.id}",
                        )
                        // Persist the re-resolved member so a reload doesn't snap
                        // back to the disabled one. resolveProviderFromGroup set
                        // _activeEntryId to the actually-resolved member.
                        _activeEntryId.value?.let {
                            persistBinding("""{"type":"group","groupId":"$groupBound","lastEntryId":"$it"}""")
                        }
                    } else {
                        // Whole group is now unavailable (all members disabled /
                        // credential-less) — fall through to the default group /
                        // new-chat fallback chain by clearing the cached provider
                        // so the guard below re-runs the standard resolution.
                        AppLogger.warning(
                            TAG,
                            "🔀RESOLVE group=$groupBound active entry=$before provider disabled and group has no enabled member — falling back",
                        )
                        currentProvider = null
                    }
                }
                // [T-provider-live-route-edit] Route-field drift detection. Editing
                // a provider's route fields (custom base URL / v1 suffix / Responses
                // API / Azure / custom UA / image endpoint) in Settings updates the
                // repo config, but the cached [currentProvider] still holds the OLD
                // [ProviderInstance] snapshot that [ProviderFactory.create] captured
                // into instanceContext at creation time. Detect that drift and rebuild
                // the provider IN PLACE for the same entry (model + group binding
                // unchanged — we only refresh the route snapshot), so route edits take
                // effect without a process restart. Distinct from the disabled-provider
                // re-resolution above, which legitimately re-selects a member.
                val cachedProvider = currentProvider
                val cachedInstance = cachedProvider?.instanceContext
                if (cachedProvider != null && cachedInstance != null) {
                    val freshInstance = providerRepository.instance(cachedInstance.id)
                    if (freshInstance != null && providerRouteChanged(cachedInstance, freshInstance)) {
                        val freshKey = providerRepository.loadApiKey(freshInstance.id)
                        if (freshKey != null) {
                            currentProvider = ProviderFactory.create(
                                freshInstance,
                                freshKey,
                                cachedProvider.model,
                                context,
                            )
                            AppLogger.info(
                                TAG,
                                "🔀RESOLVE route fields changed for provider=${freshInstance.label} — rebuilt cached provider in place",
                            )
                        } else {
                            // Credential removed mid-session: drop the cached provider so
                            // the standard resolution/fallback chain decides what's next.
                            currentProvider = null
                        }
                    }
                }
                if (currentProvider == null && config.modelEntries.isNotEmpty()) {
                    // T306: re-attempt the persisted binding now that config
                    // has entries. For an existing session whose loadSession
                    // ran before config finished (so restoreFromBinding fell
                    // through), the binding pointed at the right entry all
                    // along — we just couldn't resolve it. Try it again
                    // before falling back to the default group, so the
                    // fallback target survives a cold start that races
                    // ProviderRepository's async load.
                    val sid = realSessionId.takeIf { it.isNotEmpty() }
                    if (sid != null) {
                        val session = runCatching { chatRepository.getSession(sid) }.getOrNull()
                        if (session?.modelBinding != null && restoreFromBinding(session.modelBinding)) {
                            return@collect
                        }
                    }
                    val effectiveGroupId = initialGroupId ?: providerRepository.defaultPrimaryGroupId
                    var resolved = false
                    if (effectiveGroupId != null) {
                        resolved = resolveProviderFromGroup(effectiveGroupId)
                        if (resolved) {
                            _selectedGroupId.value = effectiveGroupId
                        }
                    }
                    if (!resolved) {
                        // [T-newchat-default-model-fallback-android] Same
                        // new-chat fallback chain as the draft branch in
                        // loadSession: last-used → newest-provider/newest-text.
                        // Was allVisibleEntries().firstOrNull().
                        applyNewChatDefaultModel()
                    }
                }
            }
        }
    }

    /**
     * Session ID that disk/shell-bound resources must use. Until the user sends
     * the first message, `realSessionId` is empty and we fall back to the draft
     * key. After `ensureSession()` runs, this returns the persisted id so
     * `/var/minis/{attachments,workspace,...}` mounts, browser artifacts, and
     * the PersistentShell all land in a single directory that survives re-entry.
     */
    internal val activeSessionId: String
        get() = realSessionId.ifEmpty { sessionId }

    /** Public accessor used by ChatScreen to resolve session-scoped minis:// links. */
    val currentSessionId: String
        get() = activeSessionId

    /** T-chat-title-pill-edit: load the persisted [ChatSessionEntity] for the
     *  current session so the shared edit-title sheet (reused from the session
     *  list) can be opened from the in-chat title pill. Returns null for
     *  drafts that haven't been persisted yet. */
    suspend fun loadSessionEntity(): com.openminis.app.data.db.ChatSessionEntity? {
        val sid = realSessionId.ifEmpty { return null }
        return runCatching { chatRepository.getSession(sid) }.getOrNull()
    }

    /** T-chat-title-pill-edit: update title + category from the in-chat
     *  edit sheet. Mirrors SessionListViewModel.updateTitleAndCategory but
     *  also refreshes the local StateFlows so the pill updates immediately
     *  without waiting for a session reload. */
    fun updateTitleAndCategory(title: String, category: String?) {
        val sid = realSessionId.ifEmpty { return }
        viewModelScope.launch {
            chatRepository.updateSessionTitleAndCategory(sid, title, category)
            _sessionTitle.value = title.ifBlank { "New Chat" }
            _sessionCategory.value = category
        }
    }

    /**
     * [promote-draft-on-new-chat] If the user is on a draft with unsent text
     * and taps "New Chat", promote the current draft to a real session so the
     * typed text isn't silently lost. The slot is freed synchronously so the
     * next `ComposerDraftStore.nextDraftId` returns a fresh id for the new
     * draft; the DB row + title write happens asynchronously in viewModelScope
     * (local DB, ~50ms — no need to block the UI).
     *
     * Returns true when promotion was triggered — the caller should let
     * onNewChat proceed (the slot is already freed either way).
     */
    fun promoteDraftIfNeeded(): Boolean {
        if (!isDraft || realSessionId.isNotEmpty()) return false
        val text = _inputText.value
        if (text.isBlank()) return false

        // Free the draft slot synchronously — the text is captured in `text`,
        // and nextDraftId must return a fresh ID before the navigation fires.
        _inputText.value = ""
        com.openminis.app.data.ComposerDraftStore.clearDraft(context, sessionId)

        // Create a real session row + set its title asynchronously.
        viewModelScope.launch {
            val sid = ensureSession()
            if (sid.isNotEmpty()) {
                val title = text.take(50).trim()
                chatRepository.updateSessionTitleAndCategory(sid, title, null)
                _sessionTitle.value = title
            }
        }
        return true
    }

    /** Ensure the session exists in the database. Called before first message. */
    private suspend fun ensureSession(): String {
        if (realSessionId.isNotEmpty()) return realSessionId
        val modelId = currentModel?.id ?: providerRepository.allVisibleEntries().firstOrNull()?.model?.id ?: "unknown"
        // [T-memory-global-toggle-settings-ui-android] Snapshot the
        // current in-memory `_memoryEnabled` into the new row. For a
        // draft VM this matches the global default we seeded at
        // construction; if the user flipped /memory on the draft
        // before first send, that choice wins.
        val session = chatRepository.createSession(
            modelId = modelId,
            memoryEnabled = _memoryEnabled.value,
            // [T-empty-session-residue] Same reasoning for the thinking
            // override: fold it into the insert so flipping /thinking on a
            // draft no longer needs a pre-materialising write of its own.
            thinkingLevel = _thinkingLevel.value.name,
        )
        realSessionId = session.id
        // Move our cached VM from the draft key ("__new__...") to the real
        // sessionId so re-entering the session reuses the same instance.
        if (isDraft) {
            ChatViewModelStore.rename(sessionId, session.id)
            // Bring every disk/shell resource that was opened with the draft
            // id over to the real id *before* agent tools start running against
            // the persisted session — otherwise the first tool call (e.g.
            // yt-dlp writing into /var/minis/attachments) would land in
            // minis-sessions/__new__*/… and be orphaned when the user
            // re-enters the session and everything is resolved via the real
            // id. See debug report 2026-04-21 (TikTok Chinese filename).
            migrateDraftResources(fromDraft = sessionId, toReal = session.id)
            // [T-android-session-skill-override-init-timing] Re-point any
            // session_skill_overrides / mcp_session_overrides rows written
            // pre-first-message (against `__new__<uuid>`) onto the real
            // session id, mirroring the disk-resource hop above. Without
            // this, a skill or MCP server the user toggled on the draft
            // session sheet vanishes the next time the same chat is opened
            // (the prop carries the real id by then, but the override row
            // is still stranded under the draft key). Aligns with iOS
            // ed861471 (T-ios-session-skill-override-init-timing). Cheap
            // no-op when no rows match.
            skillRepository?.renameSessionOverrides(fromDraft = sessionId, toReal = session.id)
            mcpRepository?.renameSessionOverrides(fromDraft = sessionId, toReal = session.id)
            // Re-point the lazily-created BrowserTabPool if it was already
            // instantiated against the draft key (e.g. user opened the browser
            // sheet before sending a message). Without this, cookies and
            // downloads keep flowing into the draft directory.
            _browserTabPoolRef?.setSession(session.id)
        }
        // Persist the current model binding so it survives re-entry
        val groupId = _selectedGroupId.value
        val entryId = _activeEntryId.value
        val binding = when {
            groupId != null && entryId != null -> """{"type":"group","groupId":"$groupId","lastEntryId":"$entryId"}"""
            groupId != null -> """{"type":"group","groupId":"$groupId"}"""
            entryId != null -> """{"type":"entry","entryId":"$entryId"}"""
            else -> null
        }
        if (binding != null) {
            chatRepository.updateSessionBinding(realSessionId, binding, modelId)
        }
        return realSessionId
    }

    /**
     * Move every per-session disk resource from the draft directory to the
     * real one, and tear down any shell that was started against the draft id.
     *
     * The draft key leaks into persistent shells (`ExecutionCoordinator`),
     * browser artifacts (`persistBrowserArtifact`), and the `BrowserTabPool`'s
     * cookie/state store. Before this migration ran, a tool invocation that
     * happened before the user's first message would write into the draft's
     * `minis-sessions/__new__{uuid}` directory and become invisible the
     * moment the VM was recreated under the real id — exactly the symptom
     * observed with the Chinese-named TikTok download that appeared to
     * "disappear" after `yt-dlp` reported success.
     */
    private fun migrateDraftResources(fromDraft: String, toReal: String) {
        // Stop any shell that was already spun up against the draft id; its
        // -b mount arguments were frozen to the draft directory at launch, so
        // we can't reuse it after the migration.
        runCatching { ExecutionCoordinator.sessionDidTerminate(fromDraft) }

        val base = java.io.File(context.filesDir, "minis-sessions")
        val draftBase = java.io.File(base, fromDraft)
        if (!draftBase.isDirectory) return
        val realBase = java.io.File(base, toReal).apply { mkdirs() }

        listOf("attachments", "offloads", "workspace", "browser").forEach { subdir ->
            val src = java.io.File(draftBase, subdir)
            if (!src.isDirectory) return@forEach
            val dst = java.io.File(realBase, subdir).apply { mkdirs() }
            src.listFiles()?.forEach { child ->
                val target = java.io.File(dst, child.name)
                runCatching {
                    if (!target.exists() && !child.renameTo(target)) {
                        copyRecursive(child, target)
                    }
                }.onFailure {
                    android.util.Log.w("ChatViewModel",
                        "migrateDraftResources: failed to move ${child.absolutePath} -> ${target.absolutePath}: ${it.message}")
                }
            }
        }
        runCatching { draftBase.deleteRecursively() }

        // Also rename the BrowserTabPool saved-state file (filesDir/browser_tabs/<sid>.json).
        // Otherwise the pool will load empty state on the next re-entry and the
        // user loses their open tabs even though the URLs never truly "went away".
        val tabsDir = java.io.File(context.filesDir, "browser_tabs")
        val draftTabs = java.io.File(tabsDir, "$fromDraft.json")
        if (draftTabs.exists()) {
            val realTabs = java.io.File(tabsDir, "$toReal.json")
            runCatching {
                if (!realTabs.exists()) {
                    if (!draftTabs.renameTo(realTabs)) {
                        draftTabs.copyTo(realTabs, overwrite = false)
                        draftTabs.delete()
                    }
                }
            }
        }
    }

    private fun copyRecursive(src: java.io.File, dst: java.io.File): Boolean = runCatching {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.all { copyRecursive(it, java.io.File(dst, it.name)) } ?: true
        } else {
            src.copyTo(dst, overwrite = false)
            src.delete()
            true
        }
    }.getOrDefault(false)

    private fun loadSession() {
        // T-android-crash-detected-halt: when CrashFrequencyDetector
        // tripped (#459, ≥3 crashes in last hour), skip the heavy
        // session-restore path entirely. Re-running the same persisted
        // state is exactly what produced the burst, so we'd just feed
        // a re-crash loop while the user is staring at the share dialog.
        // The flag clears the moment the dialog closes (share / dismiss /
        // cancel) — see CrashFrequencyDetector.maybeShowOnActivity.
        if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            android.util.Log.w(TAG, "loadSession: safe-mode active, skipping session restore")
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
                val OVERSIZE_THRESHOLD = 50_000
                val oversized = messages.filter { it.partsJson.length >= OVERSIZE_THRESHOLD }
                if (oversized.isNotEmpty()) {
                    println(
                        "[T-HANG-DIAG] oversized-messages session=$sessionId " +
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
                .onFailure { Log.w(TAG, "latestCompactMarker failed: ${it.message}") }
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
            _visibleMessageCap.value = INITIAL_VISIBLE_MESSAGE_CAP
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
                    Log.i(TAG, "loadSession: detected interrupted agent loop, canResume=true (lastRole=${lastEntry.role})")
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
    private suspend fun applyCompactMarkerGraying(
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
                        TAG,
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
                        TAG,
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
                        TAG,
                        "[Compact] Phase2.5 v1→v2 heal: firstKeptId=${firstKeptId.take(8)} orphaned " +
                            "→ newAnchor=${heal.id.take(8)} → uiIdx=$healUiIdx",
                    )
                } else {
                    insertIdx = 0
                    AppLogger.warning(
                        TAG,
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
                .onFailure { Log.w(TAG, "updateCompactMarker (self-heal) failed: ${it.message}") }
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
    private fun anchorByCreatedAt(
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
    private fun rewriteMarkerForHeal(
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
    private fun restoreFromBinding(bindingJson: String?): Boolean {
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

    private fun resolveProviderFromGroup(
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

    fun selectGroup(groupId: String) {
        _selectedGroupId.value = groupId
        _selectedGroupName.value = providerRepository.group(groupId)?.name ?: ""
        // [T-recovery] Explicit user selection clears all health state —
        // choosing a group is an explicit "I want to work with this group"
        // signal (also how a re-authed member becomes usable again).
        groupRouter.clearHealth()
        val resolved = resolveProviderFromGroup(groupId)
        if (resolved) {
            persistBinding("""{"type":"group","groupId":"$groupId"}""")
            applyGroupSessionDefaults(groupId)
            // [switchModelAndRerun] Model-switch-during-streaming (Plan A).
            if (_isStreaming.value) switchModelAndRerun("switchModel-group")
        }
    }

    /** Select a specific entry within a group (keeps group selected). */
    fun selectGroupEntry(groupId: String, entryId: String) {
        _selectedGroupId.value = groupId
        _selectedGroupName.value = providerRepository.group(groupId)?.name ?: ""
        // [T-recovery] Explicit user pick overrides any recovery/cooldown
        // policy — the user asked for THIS member, deliver it.
        groupRouter.clearHealth()
        val resolved = resolveProviderFromGroup(groupId, entryId)
        if (resolved) {
            persistBinding("""{"type":"group","groupId":"$groupId","lastEntryId":"$entryId"}""")
            applyGroupSessionDefaults(groupId)
            // [T-newchat-default-model-fallback-android] Record the actually-
            // resolved active entry as last-used (resolveProviderFromGroup may
            // fall back off a disabled member, so _activeEntryId is the truth).
            _activeEntryId.value?.let { providerRepository.lastUsedEntryId = it }
            // [switchModelAndRerun] Model-switch-during-streaming (Plan A).
            if (_isStreaming.value) switchModelAndRerun("switchModel-groupEntry")
        }
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
    private fun applyGroupSessionDefaults(groupId: String) {
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
    private fun applyNewChatDefaultModel(): Boolean {
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

    /** Select a specific model entry (bypasses group selection). */
    fun selectEntry(entryId: String) {
        val config = providerRepository.config.value
        val entry = config.modelEntries.find { it.id == entryId } ?: return
        val instance = providerRepository.instance(entry.providerInstanceId) ?: return
        val apiKey = providerRepository.loadApiKey(instance.id) ?: return

        // Apply the new model's state + persisted binding. This runs in BOTH
        // the idle and the streaming cases (the streaming case additionally
        // cancels + restarts the loop on this provider via switchModelAndRerun).
        currentModel = entry.model
        _modelName.value = entry.model.displayName
        _providerName.value = instance.label.ifEmpty { entry.model.provider }
        _selectedGroupId.value = null
        _selectedGroupName.value = ""
        _activeEntryId.value = entry.id
        currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context)
        persistBinding("""{"type":"entry","entryId":"$entryId"}""")
        // [T-newchat-default-model-fallback-android] Remember this as the
        // global last-used model so the NEXT new chat (when no default group
        // is set) defaults back to it. Tier 2 of the new-chat fallback chain.
        providerRepository.lastUsedEntryId = entryId

        // [switchModelAndRerun] Model-switch-during-streaming (Plan A): the UI
        // picker is intentionally left active during streaming — a switch here
        // cancels the in-flight turn and re-answers the CURRENT user message
        // with the newly selected model (no more "UI shows B but the stream is
        // still calling A" split state).
        if (_isStreaming.value) switchModelAndRerun("switchModel-entry")
    }

    /**
     * [switchModelAndRerun] Model-switch-during-streaming (Plan A: cancel +
     * restart). Called by [selectEntry] / [selectGroup] / [selectGroupEntry]
     * AFTER the caller has already applied the new model's fields
     * (currentModel / _modelName / _providerName / _selectedGroup* /
     * _activeEntryId / currentProvider) and persisted the binding.
     *
     * This function cancels the in-flight agent loop, rolls back the
     * incomplete assistant turn, re-syncs DB + agentHistory to the last
     * committed user message, then restarts the loop on the newly-selected
     * provider so the CURRENT user message is answered by the new model.
     *
     * Note: enqueued prompts (_promptQueue) are intentionally left untouched —
     * they stay as dashed bubbles the user can retry after the switch; the
     * restart answers the current turn only.
     */
    private fun switchModelAndRerun(label: String) {
        AppLogger.info(
            TAG,
            "switchModelAndRerun($label): cancelling in-flight loop, restarting on " +
                "${currentProvider?.model?.displayName}",
        )
        // ── Phase 1: cancel current stream (light cancel — do NOT kick the
        // queue-drain tail; we restart in place). ──
        streamJob?.cancel()
        flushAllStreamingDeltas()
        ExecutionCoordinator.stopCurrentCommand(activeSessionId)
        SessionActivityTracker.clearToolRunning(com.openminis.app.service.ToolOutcome.Cancelled)
        SessionActivityTracker.setInactive(activeSessionId)
        if (isDraft && realSessionId.isNotEmpty() && activeSessionId != sessionId) {
            SessionActivityTracker.setInactive(sessionId)
            ExecutionCoordinator.stopCurrentCommand(sessionId)
        }

        // ── Phase 2: roll back the incomplete assistant turn in UI + history ──
        rollbackIncompleteTurn()

        // ── Phase 3: re-sync DB + agentHistory to the last committed user
        // message, then restart the loop on the (already-switched) provider. ──
        val provider = currentProvider ?: run {
            AppLogger.warning(TAG, "switchModelAndRerun: no currentProvider after switch — aborting restart")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId
                val dbMessages = chatRepository.loadMessages(sid)
                val lastUserSortOrder = dbMessages.findLast { it.role == "user" }?.sortOrder
                if (lastUserSortOrder != null) {
                    chatRepository.deleteMessagesAfter(sid, lastUserSortOrder + 1)
                }
                // Rebuild agentHistory from the trimmed DB so the retried loop
                // starts from committed context only (defense in depth against
                // any partial tool_result / assistant rows the cancelled loop
                // may have persisted).
                agentHistory.clear()
                toolLoopDetector.reset()
                val remaining = chatRepository.loadMessages(sid)
                for (entity in remaining) agentHistory.add(entity.toLLMMessage())
                // Drop stream-flush side-channel state for messages the rollback
                // removed, so no stale delta can resurrect on a kept bubble
                // (mirrors rerunFromToolBlock).
                val keptIds = _messages.value.mapTo(mutableSetOf()) { it.id }
                retainStreamFlushStates(keptIds)
                if (_streamingById.value.isNotEmpty()) {
                    _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
                }
            } catch (e: Exception) {
                Log.w(TAG, "switchModelAndRerun: DB re-sync failed: ${e.message}")
            }
            // Restart the loop on the switched provider (mirrors retryFromMessage).
            _error.value = null
            _canResume.value = false
            AppLogger.info(TAG_STREAM, "$label _isStreaming=true (sync, sid=$activeSessionId)")
            _isStreaming.value = true
            streamEpoch++
            var streamLaunched = false
            try {
                streamLaunched = runRerunStreamTail(provider, label)
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "$label _isStreaming=false (setup aborted)")
                    _isStreaming.value = false
                }
            }
        }
    }

    /** Persist the model binding to the DB session (no-op for draft sessions). */
    private fun persistBinding(bindingJson: String) {
        val sid = realSessionId.takeIf { it.isNotEmpty() } ?: return
        val modelId = currentModel?.id ?: return
        viewModelScope.launch {
            chatRepository.updateSessionBinding(sid, bindingJson, modelId)
        }
    }

    private fun findModelEntry(modelId: String) =
        providerRepository.allVisibleEntries().find { it.model.id == modelId }

    /**
     * Build the ordered list of fallback providers for the current group,
     * starting AFTER the primary provider in the member list and cycling around.
     * This ensures that models already tried (before the primary) are at the end,
     * not the beginning — so retry doesn't re-trigger the same fallback chain.
     */
    /**
     * A fallback candidate = the resolved [provider] plus the model-group
     * [entryId] it was built from. Carrying the entryId (instead of re-finding
     * it later by modelId) is what makes fallback landing precise: a group can
     * hold several entries for the SAME modelId behind different provider
     * instances/endpoints (e.g. deepseek-v4-flash via a dead hub.oaifree.com
     * key + via api.deepseek.com). A naive `modelEntries.find { it.model.id ==
     * modelId }` would return the FIRST matching entry regardless of which
     * instance we actually used — corrupting the model picker highlight, the
     * provider label and the effective context window.
     * [P0-x-fallback-entry-precision]
     */
    // [FE-5 route C] FallbackCandidate moved to top-level internal
    // (AgentLoopState.kt) so the engine layer can reference it.

    private fun buildFallbackProviders(primaryProvider: LLMProvider): List<FallbackCandidate> {
        val groupId = _selectedGroupId.value ?: return emptyList()
        val config = providerRepository.config.value
        val group = config.modelGroups.find { it.id == groupId } ?: return emptyList()
        // [P0-fallback-anchor] Ordering delegated to GroupRouter.fallbackOrder —
        // anchors by the ACTUAL active entry id, not by model.id: a group can
        // hold several entries for the SAME modelId behind different
        // instances/endpoints (e.g. deepseek-v4-flash via a dead
        // hub.oaifree.com key + via api.deepseek.com), and matching by modelId
        // returns the FIRST such entry, which may sit earlier than the entry
        // actually in use — the chain would start from the wrong point and
        // even re-include the failing entry itself. The router returns pure
        // ordering; the filtering below (disabled instance / missing
        // credential / provider creation failure) stays here.
        val order = groupRouter.fallbackOrder(
            group = group,
            activeEntryId = _activeEntryId.value,
            primaryModelId = primaryProvider.model.id,
            modelIdOf = { entryId -> config.modelEntries.find { it.id == entryId }?.model?.id },
            // [T-recovery] cheapestFirst needs cost tier lookup to order the
            // fallback chain in ascending cost.
            costTierOf = { entryId -> config.modelEntries.find { it.id == entryId }?.costTier },
        )
        val result = mutableListOf<FallbackCandidate>()
        for (entryId in order) {
            // [T-recovery] Skip members currently cooling (429) / circuit-open
            // (repeated 5xx) / dead (401) — fallback must not re-try a member
            // the router just demoted; it only cycles HEALTHY candidates.
            if (!groupRouter.isUsable(entryId)) continue
            val entry = config.modelEntries.find { it.id == entryId } ?: continue
            val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            if (!instance.isEnabled) continue
            val apiKey = providerRepository.loadApiKey(instance.id) ?: continue
            val p = try {
                ProviderFactory.create(instance, apiKey, entry.model, context)
            } catch (_: Exception) { continue }
            result.add(FallbackCandidate(p, entryId))
        }
        return result
    }

    /**
     * Group members that fallback skipped (disabled instance / missing
     * credential / hidden entry), with reasons. Mirrors iOS
     * ModelGroupRouter.unavailableMembers: when fallback exhausts, the user
     * needs to know WHY the other group members never got tried — e.g. the
     * Claude subscription was logged out, so every Anthropic entry was
     * silently filtered and fallback kept cycling OpenAI-only.
     */
    private fun unavailableGroupMembers(): List<String> {
        val groupId = _selectedGroupId.value ?: return emptyList()
        val config = providerRepository.config.value
        val group = config.modelGroups.find { it.id == groupId } ?: return emptyList()
        val result = mutableListOf<String>()
        for (entryId in group.memberEntryIds) {
            val entry = config.modelEntries.find { it.id == entryId } ?: continue
            val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            val label = instance.label.ifEmpty { entry.model.provider }
            val reason = when {
                entry.isHidden -> "Hidden"
                !instance.isEnabled -> "Disabled"
                providerRepository.loadApiKey(instance.id) == null -> "Not logged in"
                else -> continue
            }
            result.add("⚠️ ${entry.model.displayName} ($label): $reason")
        }
        return result
    }

    // [T-android-split-chat] addAttachment / removeAttachment / clearAttachments
    // moved to ChatViewModelUiStateExt.kt (extension functions).

    /**
     * [T-context-exhausted-dialog] Dismiss the 'Context Full' dialog.
     *
     * @param restoreInput true = Cancel: put the stashed pending message back
     *   into the input field so nothing the user typed is lost. false = the
     *   dialog led to New Session / Clear Chat, so the stash is discarded.
     */
    fun dismissContextExhaustedDialog(restoreInput: Boolean) {
        _showContextExhaustedDialog.value = false
        if (restoreInput) {
            setInputText(pendingExhaustedText)
        }
        pendingExhaustedText = ""
        pendingExhaustedHasAttachments = false
    }

    /**
     * T137: Wipe in-memory and on-disk message state for the current session
     * without touching the session's chat files (workspace/, attachments/,
     * offloads/). Mirrors iOS [AIChatViewModel.clearChat] — same surface area,
     * same "files survive" guarantee.
     *
     * Cancels any in-flight stream first so the UI doesn't race the wipe.
     */
    fun clearChat() {
        if (_isStreaming.value) cancelStream()
        val sid = activeSessionId
        // T-streaming-side-channel: ensure no stale stream delta survives a
        // session wipe; the messages list is about to be cleared, so any
        // pending key would be orphaned.
        // [T-android-stream-flush-review] also cancel pending trailing flushes
        // so none re-adds an orphan side-channel entry after the wipe.
        clearAllStreamFlushStates()
        _streamingById.value = emptyMap()
        // Memory state — match iOS clearChat() field list one-for-one.
        _messages.value = emptyList()
        agentHistory.clear()
        _error.value = null
        _cachedLatestMarker = null
        toolLoopDetector.reset()
        _canResume.value = false
        _attachments.value = emptyList()
        _promptQueue.value = emptyList()
        _hasInjectedShareContent.value = false
        // T261: tool-detail sheet is per-session UI state — clear it so a
        // newly cleared chat doesn't briefly flash a stale tool's sheet
        // before the existence-guard catches up.
        _selectedToolDetailId.value = null
        // Drop any browser tabs the agent spawned for this session, and
        // delete the persisted tab snapshot so a future open starts clean.
        // iOS calls BrowserTabPool.deletePersistedData(for:) +
        // BrowserUseOffloadBridge.releasePool(forSession:); on Android the
        // pool is per-VM (lazy), so releasing tabs here is sufficient.
        _browserTabPoolRef?.releaseAllTabs()
        runCatching {
            java.io.File(context.filesDir, "browser_tabs/$sid.json").delete()
        }
        // Persist: drop messages + compact markers. Files (workspace,
        // attachments, offloads) intentionally retained.
        viewModelScope.launch {
            chatRepository.dao.deleteMessages(sid)
            chatRepository.dao.deleteCompactMarkers(sid)
            Log.i(TAG, "clearChat: session=$sid wiped (files preserved)")
        }
    }

    // ─── Share Injection (T51) ────────────────────────────────────────────

    /**
     * Whether the current input was seeded from a system share intent.
     * The "Move to…" capsule above the chat list is gated on this — once
     * the user starts a new turn or moves the share elsewhere we flip it
     * back to false. Mirrors iOS AIChatView.hasInjectedShareContent.
     */
    private val _hasInjectedShareContent = kotlinx.coroutines.flow.MutableStateFlow(false)
    val hasInjectedShareContent: kotlinx.coroutines.flow.StateFlow<Boolean> =
        _hasInjectedShareContent.asStateFlow()

    fun markShareInjected() { _hasInjectedShareContent.value = true }
    fun clearShareInjectedFlag() { _hasInjectedShareContent.value = false }

    /**
     * Convert a staged share file (under filesDir/share_extension/) into
     * an [InputAttachment] and add it to the composer. Called by
     * ChatScreen when draining a [com.openminis.app.share.PendingShare].
     */
    fun addAttachmentFromStagedShare(file: java.io.File): InputAttachment? {
        if (!file.exists()) return null
        val ext = file.extension.lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
        val kind = if (mime.startsWith("image/")) InputAttachment.Kind.IMAGE
                   else InputAttachment.Kind.DOCUMENT
        // T185 fix: ChatScreen wipes the share-extension directory right
        // after this call returns (`SharedShareStore.cleanSharedFiles`),
        // so a `Uri.fromFile(<staged file>)` would dangle by the time the
        // user actually sends — the byte-read in prepareUserAttachments
        // then fails to open the stream and the image never makes it into
        // the LLM payload, leaving the model staring at "what is this?" with no
        // picture. Copy the staged bytes into our own private dir so the
        // attachment outlives the share-extension cleanup.
        val durableDir = java.io.File(context.cacheDir, "share_inbound").apply { mkdirs() }
        val durable = java.io.File(durableDir, "${java.util.UUID.randomUUID()}-${file.name}")
        try {
            file.inputStream().use { input ->
                durable.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to copy staged share file ${file.name}: ${e.message}")
            return null
        }
        val attachment = InputAttachment(
            fileName = file.name,
            uri = android.net.Uri.fromFile(durable),
            mimeType = mime,
            kind = kind,
        )
        addAttachment(attachment)
        return attachment
    }

    // ─── Message Sending & Agent Loop ─────────────────────────────────────

    /**
     * [T-android-rerun-from-tool-block-position] Resolve the live UI assistant
     * bubble id that currently owns the tool block with [blockId] (== its
     * tool_use id). Returns null when no live bubble holds it. Used by the
     * debug RPC ([com.openminis.app.debug.HeadlessChatRunner.rerunFromToolBlock])
     * because the in-memory bubble id is a volatile `assistant_<ts>` runtime id
     * (not the DB row id a caller would read from `chat.messages.list`), so the
     * harness can't supply it directly.
     */
    fun assistantMessageIdForToolBlock(blockId: String): String? =
        _messages.value.firstOrNull { m ->
            m.role == "assistant" && m.toolBlocks.any { it.id == blockId }
        }?.id

    /**
     * [T-android-rerun-from-tool-block-position] Re-run the conversation from
     * the exact point a specific tool_use block was about to be issued —
     * BLOCK-boundary, not turn-boundary. Keeps the blocks BEFORE the target
     * tool_use in the same assistant turn; drops the target block + every
     * later block in that turn + its tool_result + all later turns, then
     * re-runs so the model re-decides from that point.
     *
     * Ported from iOS `retryFromToolBlock` (commit 0149457e). Anchor is the
     * block's tool_use id ([blockId], which for a tool_use [AssistantBlock]
     * equals its `id`) — stable + unique, NOT a positional count, so streaming
     * / merged-turn alignment can't drift the cut point.
     *
     * Degenerate case: when the target is the FIRST real block of its turn
     * (nothing precedes it), this is equivalent to truncating at the preceding
     * user message — delegate to [retryFromMessage] (the existing whole-turn
     * path) and skip the sub-message DB rewrite.
     *
     * Android does the cut DB-first (delete rows after the trimmed assistant
     * row, then rewrite that row's parts in place via
     * [ChatRepository.updateMessageParts]) and rebuilds agentHistory from the
     * trimmed DB state. The UI is trimmed in-memory (same as
     * [retryFromMessage]'s `retainedHead`, so compact-marker graying isn't
     * disturbed). Because the agent loop persists each turn as its own row and
     * `toChatMessages` merges consecutive assistant rows into one bubble, the
     * surviving trimmed turn and the new generation coalesce on the next
     * reload — no duplicate header (iOS needed an explicit resume-into-turn
     * fix for the same; Android gets it from the merge). The thinking
     * indicator shows immediately via [runAgentLoop]'s awaiting placeholder.
     *
     * No-op (returns false) when streaming, when the message/block isn't
     * found, or when the block isn't a tool_use. The caller gates the menu
     * item with the same `!isStreaming` rule, but the guard here is the source
     * of truth.
     */
    fun rerunFromToolBlock(assistantMessageId: String, blockId: String): Boolean {
        if (_isStreaming.value) return false
        val messages = _messages.value
        val asstIdx = messages.indexOfFirst { it.id == assistantMessageId }
        if (asstIdx < 0) return false
        val asstMsg = messages[asstIdx]
        val blockIdx = asstMsg.toolBlocks.indexOfFirst { it.id == blockId }
        if (blockIdx < 0) return false
        val targetBlock = asstMsg.toolBlocks[blockIdx]
        // Only a real tool_use block anchors a block cut — its id is the
        // tool_use id we match against in agentHistory / parts_json.
        if (targetBlock.kind != "tool_use" || targetBlock.id.isBlank()) return false
        // [T-android-tool-autoscroll] Start-of-turn snap — see resume().
        _forceScrollToBottom.tryEmit(Unit)
        val targetToolUseId = targetBlock.id

        // Degenerate: nothing of substance precedes the target in this turn —
        // a block cut here is identical to truncating at the preceding user
        // message, so reuse the existing whole-turn path. "Substance" = any
        // earlier block that isn't an empty text block (mirrors iOS
        // hasPrecedingContent).
        val hasPrecedingContent = asstMsg.toolBlocks.take(blockIdx).any { blk ->
            if (blk.isText) blk.content.isNotEmpty() else true
        }
        // [T-android-rerun-from-tool-deletes-earlier-turns] The degenerate
        // shortcut is ONLY equivalent to truncating at the preceding user
        // message when there is NOTHING between that user message and this
        // assistant turn. If an EARLIER assistant turn/bubble sits right before
        // this one (asstIdx-1 is also assistant), retryFromMessage(precedingUser)
        // would delete that earlier turn's tools too — exactly the "rerun from
        // the last tool wiped the tools above it / re-ran from the very start"
        // bug (logged: historySize 29 → 3 on the 2nd consecutive rerun). In
        // that case fall through to the DB-precise cut below, which keeps every
        // row before the target row (its cutPartIdx==0 branch deletes only the
        // target row onward) and preserves the earlier turns.
        val precededByUserOnly = asstIdx == 0 || messages[asstIdx - 1].role != "assistant"
        if (!hasPrecedingContent && precededByUserOnly) {
            val userMsg = (asstIdx - 1 downTo 0).asSequence()
                .map { messages[it] }
                .firstOrNull { it.role == "user" && it.content.isNotBlank() }
                ?: return false
            Log.i(TAG, "rerunFromToolBlock degenerate → retryFromMessage(precedingUser) tuId=${targetToolUseId.take(12)}")
            retryFromMessage(userMsg.id)
            return true
        }

        val initialProvider = currentProvider
        if (initialProvider == null) {
            _error.value = "No provider configured"
            return false
        }
        _canResume.value = false
        _error.value = null

        // T149 parity: revoke memory_writes in the parts we're about to drop
        // so the on-disk daily log doesn't keep entries the user rewound past.
        // The dropped range is: the target turn's blocks FROM the target
        // onward (the target tool_use itself + any later same-turn blocks) +
        // every later message. The surviving earlier blocks of the target turn
        // are kept, so they're excluded.
        val droppedTargetTail = asstMsg.copy(
            toolBlocks = asstMsg.toolBlocks.drop(blockIdx),
        )
        val deletedMessages = listOf(droppedTargetTail) +
            messages.subList(asstIdx + 1, messages.size).toList()

        // Claim the streaming flag synchronously so a rapid second tap is
        // rejected by the entry guard (same rationale as retryFromMessage T145).
        AppLogger.info(TAG_STREAM, "rerunFromToolBlock _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true
        streamEpoch++

        viewModelScope.launch(Dispatchers.IO) {
            var streamLaunched = false
            try {
                val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

                // Locate the DB assistant row holding the target tool_use, and
                // the parts-array index of that tool_use within it.
                val dbMessages = chatRepository.loadMessages(sid)
                var cutRow: MessageEntity? = null
                var cutPartIdx = -1
                outer@ for (entity in dbMessages) {
                    if (entity.role != "assistant") continue
                    val arr = try { org.json.JSONArray(entity.partsJson) } catch (_: Exception) { continue }
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optString("type") != "toolUse") continue
                        val tuId = o.optJSONObject("value")?.optString("toolUseId") ?: ""
                        if (tuId == targetToolUseId) {
                            cutRow = entity
                            cutPartIdx = i
                            break@outer
                        }
                    }
                }
                val row = cutRow
                if (row == null || cutPartIdx < 0) {
                    // Anchor not in DB (shouldn't happen for a rendered tool
                    // block). Abort cleanly without a half-applied truncation.
                    Log.w(TAG, "rerunFromToolBlock: toolUseId ${targetToolUseId.take(12)} not found in DB — aborting")
                    return@launch
                }

                // Trim the row's parts to those strictly before the target
                // tool_use, preserving array order (parts_json mirrors block
                // order). An assistant turn may hold text + several tool_use
                // parts; we keep everything ahead of the matched index.
                val srcArr = org.json.JSONArray(row.partsJson)
                val keptArr = org.json.JSONArray()
                for (i in 0 until cutPartIdx) keptArr.put(srcArr.get(i))

                if (cutPartIdx == 0) {
                    // Nothing precedes the target in its DB row — trimming would
                    // leave an empty assistant row. Drop the whole row instead
                    // (keepCount = its sort_order). The UI degenerate guard
                    // above normally catches this, but a merged-bubble layout
                    // could route a first-in-row tool_use here; handle it so we
                    // never persist a phantom empty assistant message.
                    chatRepository.deleteMessagesAfter(sid, row.sortOrder)
                    Log.i(TAG, "rerunFromToolBlock cut at row start (empty trim) tuId=${targetToolUseId.take(12)} keepCount=${row.sortOrder} row=${row.id.take(8)}")
                } else {
                    // Delete every row after the trimmed assistant row, then
                    // rewrite the trimmed row in place. deleteMessagesAfter
                    // keeps rows with sort_order < keepCount, so keepCount =
                    // thisRow.sortOrder + 1 drops the following tool_result row
                    // + all later turns while keeping (then overwriting) this one.
                    chatRepository.deleteMessagesAfter(sid, row.sortOrder + 1)
                    chatRepository.updateMessageParts(row.id, keptArr.toString())
                    Log.i(TAG, "rerunFromToolBlock sub-message cut tuId=${targetToolUseId.take(12)} keepCount=${row.sortOrder + 1} partIdx=$cutPartIdx trimmedRow=${row.id.take(8)}")
                }

                // T149 parity: revoke memory writes in the dropped range.
                revokeMemoryWritesInDeletedMessages(deletedMessages)

                // Trim the UI in-memory (same approach as retryFromMessage's
                // `_messages.value = retainedHead`, which doesn't reload from
                // DB and so doesn't disturb compact-marker graying): keep the
                // target assistant message with only its blocks BEFORE the
                // target, and drop every later message. Block trim mirrors the
                // parts trim above so UI ↔ history stay in lockstep.
                withContext(Dispatchers.Main) {
                    val cur = _messages.value
                    val ai = cur.indexOfFirst { it.id == assistantMessageId }
                    if (ai >= 0) {
                        val keptBlocks = cur[ai].toolBlocks.take(blockIdx)
                        if (keptBlocks.isEmpty()) {
                            // [T-android-rerun-from-tool-deletes-earlier-turns]
                            // Target was the first block of its bubble — the DB
                            // side dropped the whole row (cutPartIdx==0). Drop
                            // the bubble in the UI too instead of leaving an
                            // empty assistant message; earlier bubbles (the
                            // turns that precede this one) are preserved by
                            // subList(0, ai).
                            _messages.value = cur.subList(0, ai).toList()
                        } else {
                            // Recompute `content` from the surviving text blocks
                            // so it doesn't keep text the renderer just dropped.
                            // The chat list renders ordering from toolBlocks, but
                            // `content` feeds previews / copy, so keep it in sync.
                            val keptText = keptBlocks.filter { it.isText }
                                .joinToString("") { it.content }
                            val trimmed = cur[ai].copy(
                                content = keptText,
                                toolBlocks = keptBlocks,
                                isStreaming = false,
                            )
                            _messages.value = cur.subList(0, ai).toList() + trimmed
                        }
                    }
                }
                val keptIds = _messages.value.mapTo(mutableSetOf()) { it.id }
                retainStreamFlushStates(keptIds)
                if (_streamingById.value.isNotEmpty()) {
                    _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
                }

                // Rebuild agentHistory from the trimmed DB state.
                agentHistory.clear()
                toolLoopDetector.reset()
                for (entity in chatRepository.loadMessages(sid)) {
                    agentHistory.add(entity.toLLMMessage())
                }

                streamLaunched = runRerunStreamTail(initialProvider, "rerunFromToolBlock")
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "rerunFromToolBlock _isStreaming=false (setup aborted)")
                    _isStreaming.value = false
                }
            }
        }
        return true
    }

    /**
     * Retry from a specific user message: truncate all messages after it
     * (including the assistant response), rebuild agent history, and resend.
     * Mirrors iOS's edit/retry behavior — no duplicate user messages.
     */
    fun retryFromMessage(messageId: String) {
        if (_isStreaming.value) return
        _canResume.value = false
        // T7-A: 观察 —— 用户请求重试消息（开启新 run；旧 run 若已关闭则事件落空无害）
        traceObserver.t7Retry(
            operationType = "user_retry",
            operationName = null,
            safetyLevel = null,
            outcome = AgentTraceRecorder.OUTCOME_SAFE_TO_RETRY,
            reason = "retryFromMessage",
            attempt = null,
            maxAttempts = null,
            willRetry = true,
        )
        val messages = _messages.value
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val message = messages[index]
        // [T-android-tool-autoscroll] Start-of-turn snap — see resume().
        _forceScrollToBottom.tryEmit(Unit)
        if (message.role != "user" || message.content.isBlank()) return

        val initialProvider = currentProvider
        if (initialProvider == null) {
            _error.value = "No provider configured"
            return
        }
        val provider: LLMProvider = initialProvider
        _error.value = null

        // T149: snapshot messages about to be truncated so we can revoke any
        // memory_write tool blocks they contain. Without this, a retry leaves
        // the on-disk daily log with entries the user has just rewound past.
        val deletedMessages = messages.subList(index + 1, messages.size).toList()

        // Truncate UI messages: keep up to and including this user message.
        // T189: if the retried bubble was still in the queued state (manual
        // retry of a queued message before resumeQueueAfterCancel's grace
        // window — or fallback when auto-resume is disabled), flip it out of
        // queued visuals and drop its queue entry so the upcoming send
        // doesn't double up against a later auto-drain.
        val retainedHead = messages.subList(0, index + 1).map { m ->
            if (m.id == messageId && m.isQueued) {
                m.queuedPromptId?.let { pid ->
                    _promptQueue.value = _promptQueue.value.filterNot { it.id == pid }
                }
                m.copy(isQueued = false, queuedPromptId = null)
            } else m
        }
        _messages.value = retainedHead
        // T-streaming-side-channel: scrub stream deltas pointing at
        // messages we just truncated so they can't resurface later.
        val keptIds = retainedHead.mapTo(mutableSetOf()) { it.id }
        retainStreamFlushStates(keptIds)
        if (_streamingById.value.isNotEmpty()) {
            _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
        }

        revokeMemoryWritesInDeletedMessages(deletedMessages)

        // T145: claim the streaming flag SYNCHRONOUSLY so a rapid second tap
        // (or any concurrent send/retry attempt) is rejected by the entry
        // guard. Previously this was set inside the suspended outer launch,
        // leaving a multi-second window during DB cleanup + OAuth refresh
        // where two retries could slip through and spawn duplicate streamJobs.
        // The orphaned first job's `_isStreaming = false` at completion would
        // then flip the UI to "stopped" while the second job was still running.
        AppLogger.info(TAG_STREAM, "retry _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true
        streamEpoch++

        viewModelScope.launch(Dispatchers.IO) {
            // If setup throws before the inner streamJob is launched, the
            // streaming flag would be stuck true forever. Reset on the
            // unhappy paths; happy path resets in the streamJob's tail.
            var streamLaunched = false
            try {
            val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

            // Find the DB sort_order cutoff for this user message.
            // UI visible user messages are the N-th user msg with actual text content.
            // Count which visible user message this is (0-based).
            val visibleUserIndex = messages.subList(0, index + 1).count { it.role == "user" } - 1
            val dbMessages = chatRepository.loadMessages(sid)
            // Walk DB rows, counting visible user messages (those with non-toolResult text)
            var visibleUserCount = 0
            var cutoffSortOrder = -1
            for (entity in dbMessages) {
                if (entity.role == "user") {
                    // Check if this user message has visible text (not toolResult-only).
                    // [T-ios-retry-anchor-synthetic-user] Synthetic user rows the
                    // agent loop persists WITHOUT a UI bubble — resume()'s
                    // stop-continue "<system-reminder>" message — must not count,
                    // or the cutoff anchors one user message too early and the
                    // retried bubble (plus the whole last turn) is silently
                    // dropped from the rebuilt history (mirrors the iOS fix).
                    val hasText = try {
                        val arr = org.json.JSONArray(entity.partsJson)
                        (0 until arr.length()).any { i ->
                            val o = arr.getJSONObject(i)
                            val v = o.optString("value", "")
                            o.optString("type") == "text" && v.isNotBlank() &&
                                !v.trimStart().startsWith("<system-reminder>")
                        }
                    } catch (_: Exception) { true }
                    if (hasText) {
                        if (visibleUserCount == visibleUserIndex) {
                            cutoffSortOrder = entity.sortOrder + 1
                            break
                        }
                        visibleUserCount++
                    }
                }
            }
            if (cutoffSortOrder >= 0) {
                chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
            }

            // Rebuild agentHistory from remaining DB messages
            agentHistory.clear()
            toolLoopDetector.reset()
            val remaining = chatRepository.loadMessages(sid)
            for (entity in remaining) {
                agentHistory.add(entity.toLLMMessage())
            }

            streamLaunched = runRerunStreamTail(provider, "retryFromMessage")
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "retry _isStreaming=false (setup aborted)")
                    _isStreaming.value = false
                }
            }
        }
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
    private suspend fun runRerunStreamTail(
        initialProvider: LLMProvider,
        label: String,
    ): Boolean {
        var provider = initialProvider

        val baseSystemPrompt = buildSystemPrompt()
        val systemPrompt = baseSystemPrompt

        // _isStreaming was already set synchronously by the caller.
        val launchedProvider = provider
        streamJob = viewModelScope.launch(Dispatchers.IO) {
            AppLogger.info(TAG_STREAM, "$label streamJob ENTER sid=$activeSessionId")
            try {
                SessionConcurrencyManager.acquireSlot(activeSessionId)
                AppLogger.debug(TAG_STREAM, "$label streamJob slot acquired")
                SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })
                val activeFallbackStrategy = run {
                    val groupId = _selectedGroupId.value
                    groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                        ?: com.openminis.app.data.model.FallbackStrategy.default
                }
                val fallbackProviders = buildFallbackProviders(launchedProvider)
                try {
                    AppLogger.info(TAG_STREAM, "$label runAgentLoop CALL")
                    runAgentLoop(
                        provider = launchedProvider,
                        systemPrompt = systemPrompt,
                        fallbackProviders = fallbackProviders,
                        fallbackStrategy = activeFallbackStrategy,
                    )
                    AppLogger.info(TAG_STREAM, "$label runAgentLoop RETURN normal")
                } catch (e: CancellationException) {
                    AppLogger.info(TAG_STREAM, "$label runAgentLoop CANCELLED")
                    Log.d(TAG, "Agent loop cancelled")
                } catch (e: Exception) {
                    AppLogger.error(TAG_STREAM, "$label runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(TAG, "Agent loop error ($label)", e)
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
                    AppLogger.info(TAG_STREAM, "$label streamJob FINALLY enter")
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
                    AppLogger.info(TAG_STREAM, "$label streamJob FINALLY exit")
                }
            } catch (e: CancellationException) {
                AppLogger.info(TAG_STREAM, "$label streamJob CANCELLED waiting for slot")
                Log.d(TAG, "Cancelled while waiting for concurrency slot")
            }
            // [T-android-stale-streamjob-clears-isstreaming] Only the current
            // streamJob is allowed to flip _isStreaming false. An orphaned
            // earlier job (cancelled but its finally still draining downstream
            // I/O) reaching this tail AFTER a fresh send/resume/retry has
            // already taken over would otherwise hide the Stop button while
            // the new turn is still streaming. See `var streamJob` KDoc and
            // XIN 2026-06-12 log (20:22:26 / 20:23:25).
            if (streamJob === coroutineContext[Job]) {
                AppLogger.info(TAG_STREAM, "$label _isStreaming=false (about to set)")
                _isStreaming.value = false
            } else {
                AppLogger.info(TAG_STREAM, "$label _isStreaming SKIPPED (stale job; current=${streamJob?.hashCode()} this=${coroutineContext[Job]?.hashCode()})")
            }
            AppLogger.info(TAG_STREAM, "$label streamJob EXIT")
        }
        return true
    }

    /**
     * T187: enter edit mode for [messageId]. Returns the cleaned text the
     * caller should drop into the composer (with any
     * `<user-attached-files>` XML stripped), or null when the message
     * cannot be edited (streaming in progress, message missing, or not
     * a user turn). Setting `_editingMessageId` is what flips the
     * composer into edit-mode UI; the next sendMessage call sees the
     * non-null id and truncates the conversation from that point.
     * Mirrors iOS AIChatViewModel.editMessage(_:) (L2468).
     */
    fun editMessage(messageId: String): String? {
        if (_isStreaming.value) return null
        val msg = _messages.value.firstOrNull { it.id == messageId } ?: return null
        if (msg.role != "user") return null
        var text = msg.content
        val startIdx = text.indexOf("<user-attached-files>")
        if (startIdx >= 0) {
            val endTag = "</user-attached-files>"
            val endIdx = text.indexOf(endTag, startIdx)
            text = if (endIdx >= 0) {
                (text.substring(0, startIdx) + text.substring(endIdx + endTag.length)).trim()
            } else {
                text.substring(0, startIdx).trim()
            }
        }
        _editingMessageId.value = messageId
        AppLogger.info(TAG_STREAM, "✏️ editMessage id=${messageId.take(8)} text=${text.length}ch")
        return text
    }

    /**
     * T187: leave edit mode without sending. Just clears the id flag —
     * caller (ChatScreen) is responsible for clearing inputText. iOS
     * parity: AIChatViewModel.cancelEdit (L2522).
     */
    fun cancelEdit() {
        if (_editingMessageId.value != null) {
            AppLogger.info(TAG_STREAM, "✏️ cancelEdit")
        }
        _editingMessageId.value = null
    }

    /**
     * T187: drop the message at [messageId] *and* every later message
     * (in UI, in agentHistory, and on disk) so the new sendMessage()
     * call below this can persist the edited text as a fresh user
     * turn at the same position. Reuses the cutoff-search machinery
     * from retryFromMessage but offsets by `entity.sortOrder` (not
     * +1) — retry preserves the original turn, edit replaces it.
     */
    private suspend fun truncateBeforeEdit(messageId: String) {
        val messages = _messages.value
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return

        val deletedMessages = messages.subList(index, messages.size).toList()
        val kept = messages.subList(0, index)
        _messages.value = kept
        if (_streamingById.value.isNotEmpty()) {
            val keptIds = kept.mapTo(mutableSetOf()) { it.id }
            retainStreamFlushStates(keptIds)
            _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
        }
        revokeMemoryWritesInDeletedMessages(deletedMessages)

        val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId
        // Visible-user index of the *edited* message — count user turns
        // strictly before `index`, which is the 0-based ordinal of the
        // edited turn itself.
        val visibleUserIndex = messages.subList(0, index).count { it.role == "user" }
        val dbMessages = chatRepository.loadMessages(sid)
        var visibleUserCount = 0
        var cutoffSortOrder = -1
        for (entity in dbMessages) {
            if (entity.role == "user") {
                val hasText = try {
                    val arr = org.json.JSONArray(entity.partsJson)
                    (0 until arr.length()).any { i ->
                        val o = arr.getJSONObject(i)
                        // [T-android-retry-attachment-loss] Exclude the now-
                        // persisted <user-attached-files> XML text part so this
                        // "is this a visible user bubble?" count stays identical
                        // to pre-XML-persistence behaviour. An attachments-only
                        // turn must NOT flip to hasText just because the XML
                        // inventory is now a text part — that would shift the
                        // retry/edit cutoff onto the wrong message.
                        // [T-ios-retry-anchor-synthetic-user] Likewise exclude
                        // resume()'s synthetic stop-continue <system-reminder>
                        // user row — it has no UI bubble, so counting it shifts
                        // the cutoff one user message too early.
                        o.optString("type") == "text" &&
                            stripAttachedFilesXml(o.optString("value", "")).isNotBlank() &&
                            !o.optString("value", "").trimStart().startsWith("<system-reminder>")
                    }
                } catch (_: Exception) { true }
                if (hasText) {
                    if (visibleUserCount == visibleUserIndex) {
                        // ChatDao.deleteMessagesAfter is `sort_order >= keepCount`
                        // → passing this row's sortOrder deletes IT and everything
                        // after, which is exactly what edit semantics want.
                        cutoffSortOrder = entity.sortOrder
                        break
                    }
                    visibleUserCount++
                }
            }
        }
        if (cutoffSortOrder >= 0) {
            chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
        }
        agentHistory.clear()
        toolLoopDetector.reset()
        val remaining = chatRepository.loadMessages(sid)
        for (entity in remaining) {
            agentHistory.add(entity.toLLMMessage())
        }
        AppLogger.info(
            TAG_STREAM,
            "✏️ truncateBeforeEdit cutoffSortOrder=$cutoffSortOrder remaining=${remaining.size}"
        )
    }

    /**
     * Enqueue a prompt to be injected into the currently running agent loop.
     * The message appears immediately in the chat with isQueued=true; when the
     * current agent loop finishes, drainQueuedPrompts() consumes the queue.
     * Mirrors iOS AIChatViewModel.enqueuePrompt().
     */
    fun enqueuePrompt(text: String) {
        val trimmed = text.trim()
        val pendingAttachments = _attachments.value
        if ((trimmed.isBlank() && pendingAttachments.isEmpty()) || !_isStreaming.value) return

        val prompt = QueuedPrompt(
            id = "queued_${System.currentTimeMillis()}_${(Math.random() * 1_000_000).toInt()}",
            text = trimmed,
            attachments = pendingAttachments,
        )
        _promptQueue.value = _promptQueue.value + prompt

        val attachmentNames = pendingAttachments.map { it.fileName }
        val imageUris = pendingAttachments.filter { it.isImage }.map { it.uri }
        val attachmentUris = pendingAttachments.filterNot { it.isImage }.map { it.uri }
        val chatMsg = ChatMessage(
            id = "queued_msg_${prompt.id}",
            role = "user",
            content = trimmed,
            imageUris = imageUris,
            attachmentNames = attachmentNames,
            attachmentUris = attachmentUris,
            isQueued = true,
            queuedPromptId = prompt.id,
        )
        _messages.value = _messages.value + chatMsg
        clearAttachments()
        Log.i(TAG, "Enqueued prompt (${trimmed.length}ch, ${pendingAttachments.size} attachments), queue=${_promptQueue.value.size}")
    }

    /** Remove a queued prompt and its chat message by prompt id. */
    fun removeQueuedPrompt(promptId: String) {
        _promptQueue.value = _promptQueue.value.filterNot { it.id == promptId }
        _messages.value = _messages.value.filterNot { it.queuedPromptId == promptId }
    }

    /** Withdraw a queued message before it gets injected into the agent loop. */
    fun withdrawQueuedMessage(messageId: String) {
        val msg = _messages.value.firstOrNull { it.id == messageId } ?: return
        if (!msg.isQueued) return
        val pid = msg.queuedPromptId ?: return
        _promptQueue.value = _promptQueue.value.filterNot { it.id == pid }
        _messages.value = _messages.value.filterNot { it.id == messageId }
        Log.i(TAG, "Withdrew queued message, queue=${_promptQueue.value.size}")
    }

    /**
     * [T-consecutive-user-bridge] Enforce the provider protocol invariant
     * "roles must alternate" *) just before appending a user message to
     * [agentHistory] from a *fresh* entry point ([sendMessage] or
     * [drainQueuedPrompts]).
     *
     * Normally those entry points follow a completed assistant turn, so the
     * tail is already an assistant message and this is a no-op. But when the
     * preceding agent loop was interrupted (user Stop) or capped
     * (MAX_AGENT_TURNS) *after* a tool_result landed — tool results are
     * persisted to agentHistory as role=USER messages — the tail can be a
     * user(tool_result). Blindly appending another user then yields:
     *
     *   - Anthropic: hard 400 `roles: must alternate between "user" and
     *     "assistant"`.
     *   - OpenAI: two consecutive "user" roles merged into one message,
     *     silently swallowing the tool_result's pairing semantics.
     *
     * Fix: if the tail is a user message, inject a lightweight assistant
     * bridge (agentHistory-only, never persisted — same pattern as
     * [injectQueuedPromptsAsNewTurn], which guards the mid-loop queued
     * interrupt for exactly this reason) so the appended user starts a clean
     * turn. Pure logic lives in the top-level
     * [ensureRoleAlternationBeforeUserAppend] so it is JVM-testable.
     */
    private fun ensureTrailingRoleAlternativeBeforeUserAppend() {
        if (agentHistory.lastOrNull()?.role == LLMMessage.Role.USER) {
            Log.w(TAG, "append user whose history tail is user (tool_result likely) — injecting assistant bridge")
        }
        ensureRoleAlternationBeforeUserAppend(agentHistory)
    }

    /**
     * [T-android-queued-message-interrupt-on-toolclose] Mid-tool-loop
     * interrupt: take everything in [_promptQueue] right now, finalize the
     * just-finished assistant bubble in the UI, persist a fresh user
     * message carrying the queued text + attachments, append an assistant
     * "bridge" entry into [agentHistory] (so Anthropic's
     * mergeConsecutiveSameRole doesn't fold the queued user msg into the
     * preceding tool_result), and spawn a new assistant placeholder for
     * the next iteration's response.
     *
     * Returns an [InjectedTurn] carrying the new assistantId (which the
     * caller swaps into its loop-scope `assistantId` before `continue`-ing
     * the agent loop), or `null` if every queued prompt was empty after
     * attachment processing (caller falls through to a normal next-turn
     * dispatch in that case).
     *
     * Mirrors iOS `injectQueuedPromptsAsNewTurn`
     * (AIChatViewModel.swift:2794). Unlike iOS we don't persist the bridge
     * entry — its sole purpose is to break up the consecutive-user run for
     * the next API call; chat history reconstruction would just hide it.
     */
    private data class InjectedTurn(val newAssistantId: String)

    private suspend fun injectQueuedPromptsAsNewTurn(
        finishedAssistantId: String,
        finishedAccumulatedText: String,
        finishedAllToolBlocks: List<AssistantBlock>,
    ): InjectedTurn? {
        if (_promptQueue.value.isEmpty()) return null
        val queued = _promptQueue.value
        _promptQueue.value = emptyList()

        // [T-android-queued-message-duplicated-on-inject] REMOVE the queued
        // placeholder bubbles (the ones enqueuePrompt added with
        // id="queued_msg_…") for the prompts we're injecting. Step (c) below
        // appends a single combined user bubble (id=userEntity.id) for the same
        // text — so flipping isQueued=false and KEEPING the placeholders (the
        // old behaviour) rendered the message TWICE: once as the un-queued
        // placeholder, once as the injected bubble. drainQueuedPrompts reuses
        // its placeholders and never re-appends, so it didn't dupe; this mid-
        // loop inject path appends a fresh bubble, so the placeholders must go.
        val queuedIds = queued.map { it.id }.toSet()
        val msgsAfterUnqueue = _messages.value.filterNot { m ->
            m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)
        }

        // Build the combined user message from all queued prompts.
        val sid = ensureSession()
        val combinedAttachments = queued.flatMap { it.attachments }
        val prepared = prepareUserAttachments(combinedAttachments, sid)

        val combinedParts = mutableListOf<AgentContentPart>()
        val combinedText = StringBuilder()
        for (prompt in queued) {
            if (prompt.text.isNotEmpty()) {
                if (combinedText.isNotEmpty()) combinedText.append("\n\n")
                combinedText.append(prompt.text)
                combinedParts.add(AgentContentPart.Text(prompt.text))
            }
        }
        prepared.imageParts.forEachIndexed { idx, part ->
            val path = prepared.imageUploadPaths.getOrNull(idx)
            if (path != null) combinedParts.add(AgentContentPart.Text("[attached image: $path]"))
            combinedParts.add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path))
        }
        prepared.attachedFilesXml?.let { combinedParts.add(AgentContentPart.Text(it)) }

        // Guard: every queued prompt produced no content (no text, no
        // image). An empty user msg is a 400 from every provider. Skip —
        // the caller falls through to a normal next-turn dispatch so the
        // loop doesn't spin.
        if (combinedParts.isEmpty()) {
            AppLogger.warning(
                TAG_STREAM,
                "injectQueuedPromptsAsNewTurn: ${queued.size} queued prompt(s) produced no content, skipping",
            )
            return null
        }

        // Bridge entry into agentHistory ONLY (not persisted). The tail
        // before this call is user(tool_result); without the bridge the
        // queued user message becomes two consecutive user roles and the
        // provider merges them — exactly the regression iOS hit at #579.
        // Empty/whitespace-only bridge text would itself be merged out by
        // some sanitizers; keep a small visible string for parity with iOS.
        agentHistory.add(
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "(Interrupted mid-task by a new user message. Decide based on the new message and overall context whether the prior task should continue — do not forget or abandon it unless the user explicitly says to stop, or the new message makes clear it is no longer needed.)",
                contentParts = listOf(
                    AgentContentPart.Text("(Interrupted mid-task by a new user message. Decide based on the new message and overall context whether the prior task should continue — do not forget or abandon it unless the user explicitly says to stop, or the new message makes clear it is no longer needed.)"),
                ),
            ),
        )

        // Persist the queued user message as its own DB row + append to
        // agentHistory so the next API call carries it.
        val userText = combinedText.toString()
        val userPartsJson = buildUserPartsJson(userText, prepared.mediaRefPartsJson, prepared.attachedFilesXml)
        val userEntity = chatRepository.appendMessage(sid, "user", userPartsJson)
        agentHistory.add(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = userText,
                imageParts = prepared.imageParts,
                contentParts = combinedParts,
                dbMessageId = userEntity.id,
            ),
        )

        // Finalize the just-finished assistant bubble in the UI on Main:
        // (a) un-queue the queued chat bubbles, (b) flush the side-channel
        // delta into the canonical row and clear isStreaming /
        // isAwaitingModelResponse, then (c) append the freshly-created
        // queued user ChatMessage + a NEW empty assistant placeholder so
        // the next iteration's streaming writes target the new bubble.
        val newAssistantId = "assistant_${System.currentTimeMillis()}"
        withContext(Dispatchers.Main) {
            // (a) + (b) one emit: build the post-finalize list.
            _messages.value = msgsAfterUnqueue
            updateAssistantMessage(
                finishedAssistantId,
                finishedAccumulatedText,
                false,
                finishedAllToolBlocks,
                isAwaitingModelResponse = false,
            )
            // [T-android-cancel-sidechannel] The interrupted assistant may
            // still hold a live streaming delta in `_streamingById` (thinking
            // streams through the side-channel; `finishedAccumulatedText`
            // only carries the formal text, not the thinking delta). If we
            // leave that entry behind, ChatScreen's `n(msgs, streamingById)`
            // overlay re-merges the delta and `mergeStreamingOverlay` forces
            // `isStreaming = true` again — so the old "Thinking…" breadcrumb
            // keeps spinning even though the tool itself reached a terminal
            // state (SUCCESS/CANCELLED) above. Evict the entry now: the
            // terminal canonical row has already been written by the
            // updateAssistantMessage call, so we only drop the stale overlay
            // without touching the just-written content.
            _streamingById.value = _streamingById.value - finishedAssistantId
            // (c) — append the queued user bubble + the new assistant
            // placeholder. Mirrors sendMessage's user-bubble append shape so
            // attachments / images / file chips render the same.
            val queuedUserMsg = ChatMessage(
                id = userEntity.id,
                role = "user",
                content = userText,
                imageUris = prepared.imageUris,
                attachmentNames = prepared.attachmentNames,
                attachmentUris = prepared.nonImageUris,
            )
            val nextAssistantMsg = ChatMessage(
                id = newAssistantId,
                role = "assistant",
                content = "",
                isStreaming = true,
                isAwaitingModelResponse = true,
                thinkingLevel = _thinkingLevel.value,
            )
            _messages.value = _messages.value + queuedUserMsg + nextAssistantMsg
            // Note: ChatScreen's `lastUserAppendMs` (the trailing-row
            // ScrollPin send-grace window) is updated reactively by
            // ChatScreen's `LaunchedEffect(messages.size)` user-send hook
            // when messages.size grows — appending the queuedUserMsg above
            // bumps the size, so the pin window opens just like a normal
            // send. No direct write needed from here (and we couldn't —
            // `lastUserAppendMs` lives in ChatScreen's composition scope).
        }

        AppLogger.info(
            TAG_STREAM,
            "injectQueuedPromptsAsNewTurn: injected ${queued.size} queued prompt(s) as new turn, " +
                "finishedId=$finishedAssistantId newId=$newAssistantId",
        )
        return InjectedTurn(newAssistantId)
    }

    /**
     * Drain queued prompts after an agent loop finishes. Each queued prompt is
     * appended to agentHistory, persisted, and re-runs the agent loop.
     * Mirrors iOS AIChatViewModel.drainQueuedPrompts().
     *
     * The re-entered loop is anchored to the class-level `currentProvider`
     * (whatever the prior runAgentLoop settled on after fallback), and its
     * fallback candidates are rebuilt from that provider — the initial
     * provider/fallback snapshots are intentionally NOT carried in, so queued
     * prompts never replay a chain the main loop already resolved away from.
     */
    private suspend fun drainQueuedPrompts(
        provider: LLMProvider,
        systemPrompt: String?,
        fallbackStrategy: com.openminis.app.data.model.FallbackStrategy,
    ) {
        while (_promptQueue.value.isNotEmpty()) {
            val queued = _promptQueue.value
            _promptQueue.value = emptyList()
            Log.i(TAG, "📨[DRAIN] Draining ${queued.size} queued prompt(s): " +
                queued.joinToString(", ") { "${it.id}=\"${it.text.take(20)}...\"" })

            // Flip isQueued=false on corresponding chat messages so they render as sent.
            // T189: also clear queuedPromptId so a later retry of this bubble
            // doesn't try to drop a phantom queue entry (and so the field state
            // matches what retryFromMessage's truncate path now produces).
            val queuedIds = queued.map { it.id }.toSet()
            _messages.value = _messages.value.map { m ->
                if (m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)) {
                    m.copy(isQueued = false, queuedPromptId = null)
                } else m
            }

            // Build a combined user message (text + images from all queued prompts).
            // Persist as a single row.
            val sid = ensureSession()
            val combinedAttachments = queued.flatMap { it.attachments }
            val prepared = prepareUserAttachments(combinedAttachments, sid)

            // T132: same shape as sendMessage — caption(s) first, then for each
            // image emit "[attached image: <path>]" + ImageData, finally the
            // <user-attached-files> XML. Keeps caption adjacent to image and
            // lets the agent re-read the file via read_image.
            val combinedParts = mutableListOf<AgentContentPart>()
            val combinedText = StringBuilder()
            for (prompt in queued) {
                if (prompt.text.isNotEmpty()) {
                    if (combinedText.isNotEmpty()) combinedText.append("\n\n")
                    combinedText.append(prompt.text)
                    combinedParts.add(AgentContentPart.Text(prompt.text))
                }
            }
            prepared.imageParts.forEachIndexed { idx, part ->
                val path = prepared.imageUploadPaths.getOrNull(idx)
                if (path != null) combinedParts.add(AgentContentPart.Text("[attached image: $path]"))
                combinedParts.add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path))
            }
            prepared.attachedFilesXml?.let { combinedParts.add(AgentContentPart.Text(it)) }

            val userText = combinedText.toString()
            val userPartsJson = buildUserPartsJson(userText, prepared.mediaRefPartsJson, prepared.attachedFilesXml)
            chatRepository.appendMessage(sid, "user", userPartsJson)

            // [T-consecutive-user-bridge] The prior runAgentLoop may have
            // exited with agentHistory ending on user(tool_result) — e.g. the
            // MAX_AGENT_TURNS ceiling was hit between a tool result landing
            // and the next assistant turn. Appending another user would make
            // two consecutive user roles → deterministic 400 (Anthropic must
            // alternate) / merged-away (OpenAI). Inject an assistant bridge
            // (agentHistory-only, never persisted) exactly like
            // injectQueuedPromptsAsNewTurn does for the mid-loop interrupt.
            ensureTrailingRoleAlternativeBeforeUserAppend()

            agentHistory.add(LLMMessage(
                role = LLMMessage.Role.USER,
                content = userText,
                imageParts = prepared.imageParts,
                contentParts = combinedParts,
            ))

            try {
                // [P0-fallback-reentry] Re-anchor to the class-level
                // `currentProvider` before the queued prompt re-enters the
                // agent loop. The prior `runAgentLoop` may have fallback-
                // resolved to a different group entry (e.g. the active instance
                // 401'd and the loop transparently moved to a same-model
                // endpoint), so the class-level provider — not the initial
                // `provider` snapshot captured at send time — is the current
                // truth. Replaying the stale snapshot here would re-trigger the
                // SAME failed chain for EVERY queued prompt (the failing entry
                // fails once, fallback churns to the working endpoint, repeat
                // per queued message) — observed as the working provider being
                // "continuously called" while the top-bar capsule shows the
                // earlier entry. Rebuild the fallback candidates from the
                // active provider too, so the drain chain continues AFTER the
                // current entry (and, with the fixed entry-anchor, never
                // re-includes the active entry itself).
                val drainedProvider = this@ChatViewModel.currentProvider ?: provider
                val drainFallbacks = buildFallbackProviders(drainedProvider)
                // [P0-fallback-reentry] Log the drain anchor so the user can
                // verify a queued prompt continues on the ACTUAL active entry
                // (post-fallback) rather than replaying a stale chain.
                AppLogger.info(
                    TAG_STREAM,
                    "drain re-entry anchored provider=${drainedProvider.model.id} " +
                        "entryId=${_activeEntryId.value} staleSnapshot=${provider.model.id} candidates=${drainFallbacks.map { it.entryId }}",
                )
                runAgentLoop(
                    provider = drainedProvider,
                    systemPrompt = systemPrompt,
                    fallbackProviders = drainFallbacks,
                    fallbackStrategy = fallbackStrategy,
                )
            } catch (e: CancellationException) {
                Log.d(TAG, "Agent loop (queued-drain) cancelled")
                // Cancel mid-drain: cancelStream() will check _promptQueue
                // and call resumeQueueAfterCancel() if anything's still pending,
                // so just propagate.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Agent loop (queued-drain) error", e)
                reportAgentLoopError(e)
                break
            }
        }
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        // While streaming, enqueue instead of silently dropping (iOS: send vs enqueuePrompt).
        if (_isStreaming.value) {
            enqueuePrompt(text)
            // [T-user-message-preempts-agent] User messages win over the
            // running agent task: immediately cancel the in-flight stream so
            // the queued prompt drains as a fresh turn (cancelStream() ->
            // resumeQueueAfterCancel()) instead of sitting behind however
            // many tool calls the current plan still has.
            //
            // Previously the queued bubble waited for the agent loop to reach
            // its post-tool-result QueueInterrupt checkpoint, which is far
            // from "now" when a single tool call is long-running (yt-dlp,
            // gradle, gh release upload...) — so the user's message could be
            // visibly delayed for minutes while the task kept going. The user
            // had to manually tap Stop first, THEN the queue drained.
            // Now sending IS the preempt: same cancel semantics as the Stop
            // button (stream job killed, current shell stopped), and the
            // injected bridge tells the model to decide for itself whether
            // the abandoned task should continue after addressing the new
            // message.
            cancelStream()
            return
        }
        // T180: allow attachments-only sends (no caption). Mirrors iOS, where
        // an empty text + non-empty attachments still produces a valid user
        // message. Without this an image-only "look at this" send dropped.
        if (trimmed.isBlank() && _attachments.value.isEmpty()) return
        if (_isCompacting.value) {
            appendSystemInfo(
                text = context.getString(R.string.sysmsg_wait_compact),
                iconKind = "compact",
            )
            return
        }
        // Context pressure check — warns at the needsCompact threshold but
        // BLOCKS the send at the exhausted threshold (mirroring iOS's
        // compact-before-send dialog). /compact folds history to continue.
        // [T-context-exhausted-dialog] On EXHAUSTED we don't just drop the
        // send: stash the pending message and ask the user via dialog whether
        // to start a new session / clear chat / cancel (iOS 'Context Full'
        // alert parity) instead of leaving them stranded behind an inline
        // "Send blocked" notice.
        if (!checkContextBeforeSend()) {
            pendingExhaustedText = trimmed
            pendingExhaustedHasAttachments = _attachments.value.isNotEmpty()
            _showContextExhaustedDialog.value = true
            return
        }
        // [T5-auto-compact] At the compact line but below the hard ceiling —
        // trigger the existing compact pipeline automatically instead of only
        // warning (OmniBot AgentConversationContextCompactor parity). Must
        // happen BEFORE `_isStreaming` flips true below, or compactAll aborts
        // on the in-stream guard; the send coroutine awaits completion before
        // persisting the user message (see awaitAutoCompactIfNeeded).
        maybeTriggerAutoCompact()
        // A fresh send supersedes any pending resume — mirror iOS which clears
        // canResume at the top of send().
        _canResume.value = false
        // T185: clear the share-injected flag the moment the user actually
        // sends. Without this, the "Move to…" capsule (gated on
        // hasInjectedShareContent) keeps floating over the user-message row
        // after the share content has been committed — it then visually
        // collides with the user-attachment chips, which renders as the
        // "image attachment shows up as Move to" symptom in T185. Mirrors
        // iOS AIChatView.swift:2255 (`hasInjectedShareContent = false`
        // inside the send button's tap closure).
        if (_hasInjectedShareContent.value) _hasInjectedShareContent.value = false

        val initialProvider = currentProvider
        if (initialProvider == null) {
            _error.value = "No provider configured"
            return
        }
        var provider: LLMProvider = initialProvider

        _error.value = null

        val currentAttachments = _attachments.value
        clearAttachments()

        // T145: claim _isStreaming synchronously so a rapid second tap can't
        // slip past the entry guard during DB/OAuth setup. See retryFromMessage.
        AppLogger.info(TAG_STREAM, "send _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true

        // [T-android-thinking-indicator-linger] Invariant sweep: a fresh send
        // only reaches here when no turn is streaming (the _isStreaming guard
        // at the top routes mid-stream sends to enqueuePrompt). So any residual
        // _streamingById entry is an orphan stranded by a prior turn that
        // exited without draining it (e.g. a late delta re-added the entry
        // after finalizeAtTurnLimit / cancel cleared it). mergeStreamingOverlay
        // forces isStreaming=true on any message holding such an entry, so an
        // orphan would render a second "thinking" row alongside the new turn's.
        // Flush them into the canonical messages (isStreaming=false) before the
        // new streaming message is created — no two messages ever stream at once.
        if (_streamingById.value.isNotEmpty()) {
            AppLogger.warning(TAG_STREAM, "send: sweeping ${_streamingById.value.size} orphan streaming delta(s) before new turn")
            flushAllStreamingDeltas()
        }

        // [T-android-thinking-indicator-linger] Monotonic epoch: after the
        // orphan sweep, bump the turn epoch so any trailing-flush / residual
        // delta that re-adds an old entry LATER (flush coroutine survives
        // streamJob.cancel) carries the old epoch and is ignored by
        // mergeStreamingOverlay. Must happen AFTER the sweep — the sweep
        // handles the old turn's remnants, the epoch seals this turn.
        streamEpoch++

        // T187: when the user is editing a previous message, truncate the
        // conversation from that message (inclusive) before persisting the
        // edited text as a fresh user turn. Snapshot + clear the id here so
        // any error in the truncate path doesn't leave the composer stuck
        // in edit mode.
        val editingId = _editingMessageId.value
        if (editingId != null) _editingMessageId.value = null

        viewModelScope.launch(Dispatchers.IO) {
            var streamLaunched = false
            try {
            // [T5-auto-compact] If maybeTriggerAutoCompact() fired a compact
            // above, wait for it to finish so the persisted user message is
            // appended AFTER the compacted range. The outgoing request then
            // sees: summary + recent tail + the new user message.
            awaitAutoCompactIfNeeded()
            // Ensure session exists in DB (creates on first message for draft sessions)
            val activeSessionId = ensureSession()

            if (editingId != null) {
                truncateBeforeEdit(editingId)
            }

            val prepared = prepareUserAttachments(currentAttachments, activeSessionId)

            // Save user message — text + persisted mediaRef parts so images survive
            // a session reload (T128). Non-image attachments still only contribute
            // their name (rendered as a file tile) and are not persisted.
            val userPartsJson = buildUserPartsJson(trimmed, prepared.mediaRefPartsJson, prepared.attachedFilesXml)
            val persistedUser = chatRepository.appendMessage(activeSessionId, "user", userPartsJson)

            val userMsg = ChatMessage(
                id = persistedUser.id,
                role = "user",
                content = trimmed,
                imageUris = prepared.imageUris,
                attachmentNames = prepared.attachmentNames,
                attachmentUris = prepared.nonImageUris,
            )
            _messages.value = _messages.value + userMsg
            val imageParts = prepared.imageParts

            // T132: build the user contentParts in iOS order — caption first
            // (only if non-empty), then per image emit
            //   text("[attached image: /var/minis/attachments/uploads/<f>]")
            //   ImageData(<bytes>, <mime>)
            // so the caption sits adjacent to the image in the wire payload,
            // and the agent's read_image tool can resolve the same path back
            // to bytes. Trailing <user-attached-files> XML block lets the
            // model see filenames/sizes without needing tool calls.
            val userContentParts = mutableListOf<AgentContentPart>()
            if (trimmed.isNotEmpty()) userContentParts.add(AgentContentPart.Text(trimmed))
            imageParts.forEachIndexed { idx, part ->
                val path = prepared.imageUploadPaths.getOrNull(idx)
                if (path != null) userContentParts.add(AgentContentPart.Text("[attached image: $path]"))
                userContentParts.add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path))
            }
            prepared.attachedFilesXml?.let { userContentParts.add(AgentContentPart.Text(it)) }

            // [T-consecutive-user-bridge] A fresh send usually follows a
            // completed assistant turn (tail = assistant). But if the prior
            // agent loop was interrupted/capped after a tool_result landed
            // (tail = user(tool_result)), appending this user would create two
            // consecutive user roles → deterministic 400 on Anthropic /
            // folded-away on OpenAI. Inject an assistant bridge first
            // (agentHistory-only, mirrors injectQueuedPromptsAsNewTurn).
            ensureTrailingRoleAlternativeBeforeUserAppend()

            agentHistory.add(LLMMessage(
                role = LLMMessage.Role.USER,
                content = trimmed,
                imageParts = imageParts,
                contentParts = userContentParts,
                dbMessageId = persistedUser.id,
            ))

            // Build system prompt
            val baseSystemPrompt = buildSystemPrompt()
            val systemPrompt = baseSystemPrompt

            // Start agent loop with fallback. _isStreaming was set synchronously at top.
            streamLaunched = true
            streamJob = launch(Dispatchers.IO) {
                AppLogger.info(TAG_STREAM, "send streamJob ENTER sid=$activeSessionId")
                try {
                    // Acquire concurrency slot (suspends if at max)
                    SessionConcurrencyManager.acquireSlot(activeSessionId)
                    AppLogger.debug(TAG_STREAM, "send streamJob slot acquired")
                    SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })

                    // Resolve the active group's fallback strategy
                    val activeFallbackStrategy = run {
                        val groupId = _selectedGroupId.value
                        groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                            ?: com.openminis.app.data.model.FallbackStrategy.default
                    }

                    // Build full fallback provider list upfront (mirrors iOS triedEntries approach)
                    val fallbackProviders = buildFallbackProviders(provider)

                    try {
                        AppLogger.info(TAG_STREAM, "send runAgentLoop CALL")
                        runAgentLoop(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            fallbackProviders = fallbackProviders,
                            fallbackStrategy = activeFallbackStrategy,
                        )
                        AppLogger.info(TAG_STREAM, "send runAgentLoop RETURN normal")
                        // Drain any prompts the user queued while this loop was running.
                        // Skipped on cancel: cancelled job won't reach here.
                        drainQueuedPrompts(provider, systemPrompt, activeFallbackStrategy)
                        AppLogger.info(TAG_STREAM, "send drainQueuedPrompts RETURN")
                    } catch (e: CancellationException) {
                        AppLogger.info(TAG_STREAM, "send runAgentLoop CANCELLED")
                        Log.d(TAG, "Agent loop cancelled")
                    } catch (e: Exception) {
                        AppLogger.error(TAG_STREAM, "send runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                        Log.e(TAG, "Agent loop error (all fallbacks exhausted)", e)
                        reportAgentLoopError(e)
                        // T298: completion notifier should show the ❌ variant.
                        SessionActivityTracker.markStreamError(activeSessionId)
                    } finally {
                        AppLogger.info(TAG_STREAM, "send streamJob FINALLY enter")
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
                        AppLogger.info(TAG_STREAM, "send streamJob FINALLY exit")
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(TAG_STREAM, "send streamJob CANCELLED waiting for slot")
                    Log.d(TAG, "Cancelled while waiting for concurrency slot")
                }
                // [T-android-stale-streamjob-clears-isstreaming] guard — see
                // `var streamJob` KDoc; identical pattern as runRerunStreamTail.
                if (streamJob === coroutineContext[Job]) {
                    AppLogger.info(TAG_STREAM, "send _isStreaming=false (about to set)")
                    _isStreaming.value = false
                } else {
                    AppLogger.info(TAG_STREAM, "send _isStreaming SKIPPED (stale job)")
                }
                AppLogger.info(TAG_STREAM, "send streamJob EXIT")
            }
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "send _isStreaming=false (setup aborted)")
                    _isStreaming.value = false
                }
            }
        }
    }

    /** Set error inline on the last assistant message (iOS: message.error).
     *
     *  Also clears [ChatMessage.isAwaitingModelResponse] — without this, an
     *  exception thrown after a tool turn (which sets isAwaitingModelResponse=
     *  true at runAgentLoop ~4015) leaves the "Minis is thinking" indicator
     *  on screen even though streaming is over. The flag is per-message and
     *  is not implicitly cleared by isStreaming=false. */
    private fun setInlineError(errorText: String, detail: String? = null) {
        // [T-error-persist-android] Never let an empty/blank error string reach
        // the banner. The UI gate is `message.error?.let { … }` — a non-null ""
        // would render an EMPTY error banner, and (now that errors persist) it
        // would stick across reloads. An exception with a blank `message`
        // (`e.message ?: "Unknown error"` only guards null, not "") is the
        // realistic source. Coalesce to a generic non-empty message.
        val safeError = errorText.ifBlank { context.getString(R.string.error_empty_response_generic) }
        // T-streaming-side-channel: before mutating the canonical message,
        // drain any in-flight streaming delta so the error frame carries
        // the actual accumulated content (otherwise the user sees content
        // snap back to a pre-stream prefix when the error banner appears).
        flushAllStreamingDeltas()
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx >= 0) {
            val msg = msgs[lastAssistantIdx]
            msgs[lastAssistantIdx] = msg.copy(
                error = safeError,
                // [T-error-no-permanent-scars] errorDetail is in-memory only
                // (never persisted) — see ChatMessage.errorDetail.
                errorDetail = detail,
                isStreaming = false,
                isAwaitingModelResponse = false,
            )
            _messages.value = msgs
            // [T-error-persist-android] Persist the terminal error onto the
            // session's last assistant DB row so the inline error + Retry button
            // survive a session reload. This is a targeted UPDATE (not a fresh
            // insert): the in-memory bubble id differs from the persisted row id,
            // so we address the row by "last assistant" — matching the load-side
            // merge that keeps the last assistant row's identity. No-op when the
            // failing turn never persisted a row (first-turn failure).
            val sid = realSessionId.ifEmpty { sessionId }
            if (sid.isNotEmpty()) {
                viewModelScope.launch(Dispatchers.IO) {
                    try { chatRepository.updateLastAssistantError(sid, safeError) }
                    catch (e: Exception) { Log.w(TAG, "persist error_info failed: ${e.message}") }
                }
            }
        } else {
            // No assistant message yet — fall back to top-level error
            _error.value = safeError
        }
    }

    /**
     * Show a transient error on the last assistant message while keeping isStreaming=true
     * so the "thinking" indicator and streaming UI stay intact during auto-retry countdowns.
     * Mirrors iOS streamWithAutoRetry: `chatMessage?.error = desc` without dropping the loop.
     */
    private fun setTransientInlineError(errorText: String) {
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx < 0) return
        val msg = msgs[lastAssistantIdx]
        msgs[lastAssistantIdx] = msg.copy(error = errorText)
        _messages.value = msgs
    }

    /** Clear any inline error on the last assistant message (used after successful retry). */
    private fun clearInlineError() {
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx < 0) return
        val msg = msgs[lastAssistantIdx]
        if (msg.error == null) return
        msgs[lastAssistantIdx] = msg.copy(error = null, errorDetail = null)
        _messages.value = msgs
        // [T-error-persist-android] Clear the persisted sticker too, so a
        // recovered turn doesn't resurrect the error banner on the next reload.
        // Clear by the message's source DB rows when known (the in-memory bubble
        // maps to one or more persisted rows via sourceDbIds); fall back to the
        // last-assistant-row update otherwise.
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isNotEmpty()) {
            val dbIds = msg.sourceDbIds
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    if (dbIds.isNotEmpty()) {
                        dbIds.forEach { chatRepository.updateMessageErrorInfo(it, null) }
                    } else {
                        chatRepository.updateLastAssistantError(sid, null)
                    }
                } catch (e: Exception) { Log.w(TAG, "clear error_info failed: ${e.message}") }
            }
        }
    }

    /**
     * [T-error-persist-android] Fire-and-forget: clear the persisted error
     * sticker on the session's last assistant row. Called from the resume / retry
     * entrypoints that drop the in-memory error but don't go through
     * [clearInlineError], so a recovered turn can't merge-resurrect the old
     * banner on the next reload. No-op when there's no session/row yet.
     */
    private fun clearPersistedLastAssistantError() {
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try { chatRepository.updateLastAssistantError(sid, null) }
            catch (e: Exception) { Log.w(TAG, "clear error_info (persisted) failed: ${e.message}") }
        }
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
    private fun rollbackIncompleteTurn(): Boolean? {
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
            block.toolStatus !in IN_FLIGHT_TOOL_STATUSES
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

    fun retryLast() {
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
        AppLogger.info(TAG_STREAM, "retryLast _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true
        streamEpoch++

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
                        TAG_STREAM,
                        "retryLast: deleted trailing assistant row sortOrder=$trailingAssistantSortOrder, kept ${trailingAssistantSortOrder} prior rows",
                    )
                }
            } else {
                AppLogger.info(
                    TAG_STREAM,
                    "retryLast: agentHistory tail was user(tool_result) — no DB cleanup needed",
                )
            }

            val baseSystemPrompt = buildSystemPrompt()
            val systemPrompt = baseSystemPrompt

            // _isStreaming was already set synchronously at the top.
            streamLaunched = true
            streamJob = launch(Dispatchers.IO) {
                AppLogger.info(TAG_STREAM, "retryLast streamJob ENTER sid=$activeSessionId")
                try {
                    SessionConcurrencyManager.acquireSlot(activeSessionId)
                    AppLogger.debug(TAG_STREAM, "retryLast streamJob slot acquired")
                    SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })
                    val activeFallbackStrategy = run {
                        val groupId = _selectedGroupId.value
                        groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                            ?: com.openminis.app.data.model.FallbackStrategy.default
                    }
                    val fallbackProviders = buildFallbackProviders(provider)
                    try {
                        AppLogger.info(TAG_STREAM, "retryLast runAgentLoop CALL")
                        runAgentLoop(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            fallbackProviders = fallbackProviders,
                            fallbackStrategy = activeFallbackStrategy,
                        )
                        AppLogger.info(TAG_STREAM, "retryLast runAgentLoop RETURN normal")
                        drainQueuedPrompts(provider, systemPrompt, activeFallbackStrategy)
                        AppLogger.info(TAG_STREAM, "retryLast drainQueuedPrompts RETURN")
                    } catch (e: CancellationException) {
                        AppLogger.info(TAG_STREAM, "retryLast runAgentLoop CANCELLED")
                        Log.d(TAG, "Agent loop cancelled")
                    } catch (e: Exception) {
                        AppLogger.error(TAG_STREAM, "retryLast runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                        Log.e(TAG, "Agent loop error (retryLast)", e)
                        reportAgentLoopError(e)
                        // T298: completion notifier should show the ❌ variant.
                        SessionActivityTracker.markStreamError(activeSessionId)
                    } finally {
                        AppLogger.info(TAG_STREAM, "retryLast streamJob FINALLY enter")
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
                        AppLogger.info(TAG_STREAM, "retryLast streamJob FINALLY exit")
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(TAG_STREAM, "retryLast streamJob CANCELLED waiting for slot")
                    Log.d(TAG, "Cancelled while waiting for concurrency slot")
                }
                // [T-android-stale-streamjob-clears-isstreaming] guard.
                if (streamJob === coroutineContext[Job]) {
                    AppLogger.info(TAG_STREAM, "retryLast _isStreaming=false (about to set)")
                    _isStreaming.value = false
                } else {
                    AppLogger.info(TAG_STREAM, "retryLast _isStreaming SKIPPED (stale job)")
                }
                AppLogger.info(TAG_STREAM, "retryLast streamJob EXIT")
            }
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "retryLast _isStreaming=false (setup aborted)")
                    _isStreaming.value = false
                }
            }
        }
    }

    /**
     * Unwrap exceptions thrown inside callbackFlow.
     * callbackFlow wraps internal throws into CancellationException(cause=original).
     * This extracts the original LLMError if present.
     */
    /**
     * Sanitize agentHistory before each API call to ensure tool_use/tool_result pairing.
     * Mirrors iOS AIChatViewModel pre-API validation.
     *
     * Ensures: every assistant message with tool_use is immediately followed by a user
     * message containing the matching tool_result(s). Handles:
     * - Duplicate tool IDs across messages (from provider fallback/retry)
     * - Orphaned tool_use without any tool_result
     * - Orphaned tool_result without matching tool_use
     * - Assistant text after tool_use in the same message (Anthropic rejects this)
     */
    private fun sanitizeAgentHistory() {
        sanitizeAgentHistoryMessages(agentHistory)
    }

    /**
     * [T-compact-slice-tool-pairing] Core tool_use/tool_result pairing repair,
     * extracted from [sanitizeAgentHistory] so it can also be applied to the
     * compacted-slice result returned by [effectiveAgentHistory]. The compact
     * slice (walkBack cap / preAnchor prune / postAnchor splice) can split a
     * tool round across the boundary, leaving an orphan tool_result whose
     * tool_use was cut off — the API then rejects the request with
     * "Messages with role 'tool' must be a response to a preceding message
     * with 'tool_calls'". Running the same repair on the FINAL outgoing slice
     * closes that gap regardless of where the boundary lands.
     *
     * Mirrors iOS AIChatViewModel pre-API validation. Ensures: every assistant
     * message with tool_use is immediately followed by a user message with the
     * matching tool_result(s). Handles:
     * - Duplicate tool IDs across messages (from provider fallback/retry)
     * - Orphaned tool_use without any tool_result
     * - Orphaned tool_result without matching tool_use
     * - Assistant text after tool_use in the same message (Anthropic rejects this)
     */

    private fun unwrapFlowException(e: Throwable): Throwable {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is com.openminis.app.data.model.LLMError) return cause
            cause = cause.cause
        }
        return e
    }

    /**
     * [T-error-no-permanent-scars] Uniform terminal-error reporter for every
     * runAgentLoop call site (send / retryLast / resume / queued-drain). Shows
     * a human summary on the banner and keeps the raw error text (fallback
     * trail, original error codes) in the collapsed `errorDetail` disclosure.
     */
    private fun reportAgentLoopError(e: Exception) {
        if (e is com.openminis.app.data.model.FallbackExhaustedError) {
            setInlineError(e.summary, e.detail)
        } else {
            val errActual = unwrapFlowException(e)
            val errSummary = (errActual as? com.openminis.app.data.model.LLMError)?.userMessage
                ?: errActual.message?.takeIf { it.isNotBlank() }
                ?: "Unknown error"
            setInlineError(errSummary, errActual.message)
        }
    }

    /**
     * Compute max output tokens that fits within the remaining context window.
     * Logic mirrors iOS's dynamicMaxTokens():
     *   result = min(provider.defaultMaxTokens, max(contextWindow - inputTokens, MIN_MAX_TOKENS))
     *
     * @param provider The current LLM provider (carries defaultMaxTokens).
     * @param lastContextTokens API-reported input token count from the last call (0 = first call).
     */
    private fun dynamicMaxTokens(provider: LLMProvider, lastContextTokens: Int = 0): Int {
        val model = currentModel ?: return minOf(GLOBAL_MAX_TOKENS_CEILING, provider.defaultMaxOutputTokens)
        // Ceiling: min(global cap, model.maxOutputTokens-or-provider-default).
        // The global cap means we never send more than 128K regardless of
        // what the model claims it can output.
        val maxOutputCeiling = minOf(GLOBAL_MAX_TOKENS_CEILING, provider.effectiveMaxOutputTokens(model))
        // [T-context-window-sources] Single source of truth for the context
        // window: route through effectiveContextWindowTokens() (group-priority
        // when the model window is heuristic, minOf clamp when explicit) so
        // output sizing uses the SAME window as offload/trim/block — not the
        // raw model guess, which for a 1M model silently reported as 128K
        // would cap output alongside capping the budget. Falls back to the
        // model's own window when effective resolution fails (no live model).
        val contextWindow = effectiveContextWindowTokens() ?: model.contextWindowTokens
        if (contextWindow <= 0) return maxOutputCeiling
        val inputTokens = if (lastContextTokens > 0) lastContextTokens else 0
        val remaining = contextWindow - inputTokens
        val clamped = maxOf(remaining, MIN_MAX_TOKENS)
        val result = minOf(maxOutputCeiling, clamped)
        if (result < maxOutputCeiling) {
            android.util.Log.i(TAG, "dynamicMaxTokens: $result (remaining=$remaining, ceiling=$maxOutputCeiling, window=$contextWindow, input=$inputTokens, model=${model.id})")
        }
        return result
    }

    // ─── Context Window Offload ──────────────────────────────────────────────
    //
    // Mirrors iOS `AIChatViewModel.swift`:
    //   - estimateContextTokens()        (line 7451)
    //   - offloadContextIfNeeded()       (line 7481)
    // Per-tool writers live in [com.openminis.app.data.ContextOffload].
    //
    // The agent loop calls [offloadContextIfNeeded] once per turn just before
    // the next API call. When token usage crosses the policy threshold, large
    // tool outputs in older messages are written to disk under
    // `filesDir/minis-sessions/<sid>/offloads/tools/` and replaced in
    // [agentHistory] by `[CONTEXT OFFLOADED] … <linux path>` stubs. The model
    // can later `file_read` the path to retrieve the original content.
    //
    // Why this matters: without offloading, a session that runs many large
    // shell tools fills the context window and either trips compact (lossy)
    // or hits the model's context-exhausted error. Offload is lossless —
    // the data still exists, just on disk instead of in-prompt.

    /**
     * Char-based fallback estimate when the API hasn't reported a token
     * baseline yet (first call in a turn). Mirrors iOS line 7451.
     *
     * Uses ~3.5 chars per token for mixed text + adds the tokenizer's
     * image-aware count for image bytes. Underestimates JSON-heavy tool
     * inputs slightly but is adequate as a "should we offload" gate —
     * offload itself uses precise [BPETokenizer.countTokens] per-part
     * for the candidate ranking.
     */
    private fun estimateContextTokens(): Int {
        var totalChars = 0
        var imageTokens = 0
        for (msg in agentHistory) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> totalChars += part.text.length
                    is AgentContentPart.ToolUse -> totalChars += part.input.toString().length
                    is AgentContentPart.ToolResult -> {
                        totalChars += part.content.length
                        part.imageData?.let { imageTokens += BPETokenizer.countImageTokens(it) }
                    }
                    is AgentContentPart.ImageData -> {
                        imageTokens += BPETokenizer.countImageTokens(part.data)
                    }
                }
            }
        }
        return (totalChars / 3.5).toInt() + imageTokens
    }

    /**
     * Approximate token count for a single agent content part. Used to rank
     * offload candidates by size. Matches iOS `BPETokenizer.countPartTokens`
     * — text uses BPE, images use the grid-cell heuristic.
     */
    private fun countPartTokens(part: AgentContentPart): Int = when (part) {
        is AgentContentPart.Text -> BPETokenizer.countTokens(part.text)
        is AgentContentPart.ToolUse -> BPETokenizer.countTokens(part.input.toString())
        is AgentContentPart.ToolResult -> {
            BPETokenizer.countTokens(part.content) +
                (part.imageData?.let { BPETokenizer.countImageTokens(it) } ?: 0)
        }
        is AgentContentPart.ImageData -> BPETokenizer.countImageTokens(part.data)
    }

    /**
     * Offload candidate descriptor. `msgIdx` and `partIdx` index back into
     * [agentHistory] so we can mutate the part in place after writing the
     * stub to disk.
     */
    private data class OffloadCandidate(
        val msgIdx: Int,
        val partIdx: Int,
        val tokens: Int,
        val bytes: Int,
        val toolId: String,
        val toolName: String,
    )

    /**
     * Walk [agentHistory], identify large tool outputs in the older
     * (non-protected) message range, and offload the highest-token ones to
     * disk until we're back under [ContextPolicy.offloadTarget]. Mirrors iOS
     * `offloadContextIfNeeded(model:lastContextTokens:force:)` (line 7481).
     *
     * Protection rules (parity with iOS line 7535):
     *   - Last 4 messages are never offloaded — the model needs them
     *     verbatim to plan the current turn coherently.
     *   - Already-offloaded parts (prefix [ContextOffload.OFFLOADED_PREFIX])
     *     are skipped — second pass would rewrite the stub uselessly.
     *
     * Eligibility (parity with iOS lines 7556-7596):
     *   - `ToolResult` with content > 500 chars OR image data > 1 KB
     *   - `ToolUse` for `file_write` / `file_edit` whose `content` arg > 500 chars
     *   - bare `ImageData` part > 1 KB
     *
     * Candidates are sorted by token count descending and offloaded greedily
     * until current usage drops below [policy.offloadTarget] (or all
     * candidates are exhausted). When [force] is true, all eligible
     * candidates are offloaded regardless of remaining headroom — used by
     * post-compact code paths to slim down the kept-tail aggressively.
     */
    private fun offloadContextIfNeeded(
        contextWindow: Int,
        lastContextTokens: Int,
        force: Boolean = false,
    ) {
        val sid = activeSessionId
        val policy = ContextPolicy.forContextWindow(contextWindow)

        if (!force && policy.offloadThreshold == 0) {
            // Small-window tier: offload disabled — UI surfaces "exhausted"
            // when the user crosses the threshold. Nothing to do here.
            return
        }

        val effectiveTokens =
            if (lastContextTokens > 0) lastContextTokens else estimateContextTokens()

        if (!force && effectiveTokens < policy.offloadThreshold) {
            // Below threshold — no work needed. Caller logs at debug level
            // via dynamicMaxTokens; we stay silent to keep logs readable.
            return
        }

        val targetTokens = if (force) 0 else policy.offloadTarget
        val beforeTokens = effectiveTokens
        var currentTokens = effectiveTokens
        val pct = (effectiveTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
        val remaining = contextWindow - beforeTokens

        AppLogger.info(TAG, "━━━ Context Offload Triggered ━━━")
        AppLogger.info(TAG, "  Window: $contextWindow tokens")
        AppLogger.info(TAG, "  Before: $beforeTokens tokens ($pct% of window, ~$remaining remaining)")
        if (force) {
            AppLogger.info(TAG, "  Mode: FORCE — offloading all eligible candidates")
        } else {
            AppLogger.info(TAG, "  Threshold: ${policy.offloadThreshold} → Target: $targetTokens")
            AppLogger.info(TAG, "  Need to free: ~${beforeTokens - targetTokens} tokens")
        }
        AppLogger.info(TAG, "  Agent history: ${agentHistory.size} messages")

        val protectedCount = minOf(4, agentHistory.size)
        val candidateUpper = agentHistory.size - protectedCount
        AppLogger.info(TAG, "  Scanning messages 0..<$candidateUpper (last $protectedCount protected)")

        val candidates = mutableListOf<OffloadCandidate>()
        var skippedAlreadyOffloaded = 0
        var skippedTooSmall = 0

        for (msgIdx in 0 until candidateUpper) {
            val msg = agentHistory[msgIdx]
            for ((partIdx, part) in msg.contentParts.withIndex()) {
                when (part) {
                    is AgentContentPart.ToolResult -> {
                        if (part.content.startsWith(ContextOffload.OFFLOADED_PREFIX)) {
                            skippedAlreadyOffloaded++
                            continue
                        }
                        val hasLargeContent = part.content.length > 500
                        val hasLargeImage = (part.imageData?.size ?: 0) > 1024
                        if (!hasLargeContent && !hasLargeImage) {
                            skippedTooSmall++
                            continue
                        }
                        val tokens = countPartTokens(part)
                        val bytes = part.content.toByteArray(Charsets.UTF_8).size +
                            (part.imageData?.size ?: 0)
                        candidates.add(OffloadCandidate(msgIdx, partIdx, tokens, bytes, part.id, part.name))
                    }
                    is AgentContentPart.ToolUse -> {
                        if (part.name != "file_write" && part.name != "file_edit") continue
                        val content = part.input.optString("content", "")
                        if (content.length <= 500) continue
                        val tokens = countPartTokens(part)
                        val bytes = content.toByteArray(Charsets.UTF_8).size
                        candidates.add(OffloadCandidate(msgIdx, partIdx, tokens, bytes, part.id, part.name))
                    }
                    is AgentContentPart.ImageData -> {
                        if (part.data.size <= 1024) {
                            skippedTooSmall++
                            continue
                        }
                        val tokens = countPartTokens(part)
                        // Synthesize a tool id since bare images don't carry one.
                        val synthId = "img${msgIdx}_$partIdx"
                        candidates.add(OffloadCandidate(msgIdx, partIdx, tokens, part.data.size, synthId, "image"))
                    }
                    is AgentContentPart.Text -> Unit
                }
            }
        }

        candidates.sortByDescending { it.tokens }
        val totalCandidateTokens = candidates.sumOf { it.tokens }
        AppLogger.info(TAG, "  Candidates: ${candidates.size} parts (~$totalCandidateTokens tokens total)")
        AppLogger.info(TAG, "  Skipped: $skippedAlreadyOffloaded already offloaded, $skippedTooSmall too small")

        var offloadedCount = 0
        var freedTokens = 0

        for (candidate in candidates) {
            if (currentTokens <= targetTokens) break

            val msg = agentHistory[candidate.msgIdx]
            val parts = msg.contentParts.toMutableList()
            val part = parts[candidate.partIdx]
            var linuxPath = ""

            val newPart: AgentContentPart? = when (part) {
                is AgentContentPart.ToolResult -> {
                    if (part.content.length > 500) {
                        linuxPath = ContextOffload.offloadContent(
                            context, sid, part.content,
                            toolId = part.id, toolName = part.name,
                        )
                    }
                    val imgPath = part.imageData?.let { data ->
                        if (data.size > 1024) {
                            ContextOffload.offloadImage(
                                context, sid, data,
                                toolId = part.id,
                                mimeType = part.imageMimeType ?: "image/png",
                            )
                        } else ""
                    } ?: ""
                    if (linuxPath.isEmpty()) linuxPath = imgPath
                    val stub = ContextOffload.stub(candidate.tokens, candidate.bytes, linuxPath)
                    part.copy(content = stub, imageData = null, imageMimeType = null)
                }
                is AgentContentPart.ToolUse -> {
                    val content = part.input.optString("content", "")
                    linuxPath = ContextOffload.offloadContent(
                        context, sid, content,
                        toolId = part.id, toolName = part.name,
                    )
                    val newInput = org.json.JSONObject(part.input.toString())
                    newInput.put(
                        "content",
                        ContextOffload.stub(candidate.tokens, candidate.bytes, linuxPath),
                    )
                    part.copy(input = newInput)
                }
                is AgentContentPart.ImageData -> {
                    linuxPath = ContextOffload.offloadImage(
                        context, sid, part.data,
                        toolId = candidate.toolId,
                        mimeType = part.mimeType,
                    )
                    // Bare ImageData has no toolUseId pairing — replace with a
                    // text part carrying the stub. Mirrors iOS line 7653.
                    AgentContentPart.Text(
                        ContextOffload.stub(candidate.tokens, candidate.bytes, linuxPath),
                    )
                }
                is AgentContentPart.Text -> null
            }

            if (newPart == null) continue
            parts[candidate.partIdx] = newPart
            agentHistory[candidate.msgIdx] = msg.copy(contentParts = parts)

            currentTokens -= candidate.tokens
            freedTokens += candidate.tokens
            offloadedCount++
            val afterPct = (currentTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
            AppLogger.info(
                TAG,
                "  ✂ Offloaded #$offloadedCount: [${candidate.toolName}] id:${candidate.toolId.take(8)} ~${candidate.tokens} tokens (${candidate.bytes} bytes) → $linuxPath [now $currentTokens ($afterPct%)]",
            )
        }

        if (offloadedCount > 0) {
            val afterPct = (currentTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
            AppLogger.info(TAG, "━━━ Context Offload Complete ━━━")
            AppLogger.info(TAG, "  Parts offloaded: $offloadedCount")
            AppLogger.info(TAG, "  Tokens freed: ~$freedTokens")
            AppLogger.info(TAG, "  Before: $beforeTokens/$contextWindow ($pct%)")
            AppLogger.info(TAG, "  After:  $currentTokens/$contextWindow ($afterPct%)")
            AppLogger.info(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }
    }

    /**
     * [T-context-limit-enforce] Hard-cap fallback after offload: if the
     * estimate of [agentHistory] still exceeds the effective context window
     * (which is clamped by the group's `contextLimitTokens`), drop complete
     * turns from the OLDEST end until we're back under budget.
     *
     * Why this is safe & loss-minimal:
     *   - Ordering guarantee: `offloadContextIfNeeded` runs BEFORE this in
     *     `runAgentLoop`, so large tool outputs are already replaced by short
     *     disk stubs — what gets dropped here is mostly already-slimmed.
     *   - Turn-granularity: we never split a tool_use / tool_result pair or
     *     slice inside a user/assistant round. A "turn" = one real user prompt
     *     plus every following assistant / synthetic tool_result carrier until
     *     the next real user prompt.
     *   - Recent context preserved: at least [MIN_CONTEXT_TURNS_TO_KEEP]
     *     newest turns survive untouched, so the model always sees the current
     *     task's active region.
     *   - Audit trail intact: only [agentHistory] (the LLM-facing working copy)
     *     is trimmed — the UI message list `_messages` keeps the full history
     *     the user can still scroll and read.
     *
     * @param contextWindow the effective window (group `contextLimitTokens`
     *   clamped against the model's real window). A hard cap of 0 means
     *   "unlimited" — caller skips us entirely.
     * @param lastContextTokens API-reported context from the previous turn,
     *   0 on the first turn.
     */
    private fun trimContextHistoryWindow(
        contextWindow: Int,
        lastContextTokens: Int,
    ) {
        if (contextWindow <= 0 || agentHistory.isEmpty()) return
        // Headroom: trim to 95% of window so local underestimation (char-based
        // estimate vs real tokenizer) doesn't immediately blow past the cap on
        // the very call we're about to send.
        val budget = (contextWindow.toLong() * 95 / 100).toInt()

        // Prefer the API-reported count over local estimation when available;
        // both are imperfect but API truth is closer for long formed content.
        val baseTokens =
            if (lastContextTokens > 0) lastContextTokens else estimateContextHistoryTokens()
        if (baseTokens <= 0 || baseTokens <= budget) return

        // Walk back from the newest real user prompt to find the boundary of
        // the NEWEST complete turn — we always keep at least that many.
        val keepTurns = MIN_CONTEXT_TURNS_TO_KEEP
        val keepFrom = findTurnStartIndexFromEnd(keepTurns)
        if (keepFrom <= 0) return // whole history is within keep window — nothing to trim

        // Drop messages [0, keepFrom) — each message is a whole turn's message
        // so no tool pair is ever split.
        // Copy the slices BEFORE mutating — `subList` is a live view and would
        // be invalidated by clear(). droppedTokens is estimated on the copy.
        val dropped = agentHistory.take(keepFrom)
        val kept = agentHistory.drop(keepFrom)
        val droppedCount = dropped.size
        val droppedTokens = estimateHistoryTokens(dropped)
        agentHistory.clear()
        agentHistory.addAll(kept)

        AppLogger.info(
            TAG,
            "[ContextTrim] dropped $droppedCount messages (~$droppedTokens tokens) to fit $contextWindow limit; " +
            "history ${kept.size + droppedCount}→${kept.size} msgs, kept $keepTurns newest turn(s)"
        )
        appendSystemInfo(
            text = context.getString(R.string.sysmsg_context_trimmed, contextWindow, droppedCount),
            iconKind = "compact",
        )
    }

    /**
     * Estimate the token count of [agentHistory] from the start through all
     * messages (text chars / 3.5 + image tokens) — mirrors [estimateContextTokens]
     * but without the assumption that every current message matters for offload.
     */
    private fun estimateContextHistoryTokens(): Int {
        var totalChars = 0
        var imageTokens = 0
        for (msg in agentHistory) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> totalChars += part.text.length
                    is AgentContentPart.ToolUse -> totalChars += part.input.toString().length
                    is AgentContentPart.ToolResult -> {
                        totalChars += part.content.length
                        part.imageData?.let { imageTokens += BPETokenizer.countImageTokens(it) }
                    }
                    is AgentContentPart.ImageData -> imageTokens += BPETokenizer.countImageTokens(part.data)
                }
            }
        }
        return (totalChars / 3.5).toInt() + imageTokens
    }

    /** Estimate tokens for an explicit message slice (used for the dropped portion). */
    private fun estimateHistoryTokens(messages: List<LLMMessage>): Int {
        var totalChars = 0
        var imageTokens = 0
        for (msg in messages) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> totalChars += part.text.length
                    is AgentContentPart.ToolUse -> totalChars += part.input.toString().length
                    is AgentContentPart.ToolResult -> {
                        totalChars += part.content.length
                        part.imageData?.let { imageTokens += BPETokenizer.countImageTokens(it) }
                    }
                    is AgentContentPart.ImageData -> imageTokens += BPETokenizer.countImageTokens(part.data)
                }
            }
        }
        return (totalChars / 3.5).toInt() + imageTokens
    }

    /**
     * Find the index in [agentHistory] from which to keep the newest
     * [turnsToKeep] complete turns. A "real user prompt" is a user message
     * carrying text or non-ToolResult parts — synthetic tool_result carriers
     * (user messages whose only parts are ToolResult) belong to the preceding
     * assistant's turn and don't count as a new turn.
     *
     * @return the index of the oldest kept turn's first message (i.e. drop
     *   indices [0, return)). Returns 0 when the entire history is needed to
     *   keep [turnsToKeep] turns.
     */
    private fun findTurnStartIndexFromEnd(turnsToKeep: Int): Int {
        if (turnsToKeep <= 0) return 0
        var turnsSeen = 0
        // Walk from the newest message backward, counting real user prompts.
        for (i in agentHistory.indices.reversed()) {
            val msg = agentHistory[i]
            if (msg.role != LLMMessage.Role.USER) continue
            // Real user prompt? (anything other than a pure ToolResult carrier)
            val hasRealContent = msg.content.isNotBlank() ||
                msg.contentParts.any { p ->
                    p is AgentContentPart.Text ||
                    p is AgentContentPart.ImageData ||
                    (p is AgentContentPart.ToolUse)
                }
            // A user message with ONLY ToolResult parts is a synthetic carrier.
            val onlyToolResults = msg.contentParts.isNotEmpty() &&
                msg.contentParts.all { it is AgentContentPart.ToolResult } &&
                msg.content.isBlank()
            if (hasRealContent && !onlyToolResults) {
                turnsSeen++
                if (turnsSeen >= turnsToKeep) {
                    // i is the first message (the user prompt) of a kept turn.
                    // Anything before i (indices < i) belongs to older turns.
                    return i
                }
            }
        }
        // Fewer turns than we want to keep → inspect @return by walking forward:
        // return index of the first real user prompt (or 0 if none).
        for (i in 0 until agentHistory.size) {
            val msg = agentHistory[i]
            if (msg.role != LLMMessage.Role.USER) continue
            val hasRealContent = msg.content.isNotBlank() ||
                msg.contentParts.any { p -> p is AgentContentPart.Text || p is AgentContentPart.ImageData }
            if (hasRealContent) return i
        }
        return 0
    }

    /**
     * Direction A: stream a chat turn through the [ProviderExecutionGateway]
     * (:modelservice process) so native heap from the LLM call is reclaimed
     * when the worker self-reaps.
     *
     * TF-D: the app process NEVER falls back to an in-process provider call.
     * A cold Flow is returned; failure surfaces when collected as a typed
     * [ModelExecutionStreamException] (0-chunk → caller MAY retry, has-chunk →
     * caller MUST NOT re-send). There is no silent in-process fallback.
     */
    private fun streamChatTurnOffloaded(
        provider: LLMProvider,
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> {
        val instance = provider.instanceContext
            ?: throw ModelStreamErrorException(
                "no provider instance context for remote execution",
                hadChunks = false,
            )
        AppLogger.info(
            TAG_STREAM,
            "chat stream offload -> :modelservice provider=${provider.name} model=${provider.model.id}",
        )
        // Single gateway path — no in-process fallback exists by design.
        return ProviderExecutionGateway.stream(
            context = context,
            instance = instance,
            model = provider.model,
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            temperature = temperature,
            imageParts = imageParts,
            tools = tools,
            thinkingLevel = thinkingLevel,
        )
    }

    private suspend fun runAgentLoop(
        provider: LLMProvider,
        systemPrompt: String?,
        fallbackProviders: List<FallbackCandidate> = emptyList(),
        fallbackStrategy: com.openminis.app.data.model.FallbackStrategy = com.openminis.app.data.model.FallbackStrategy.default,
    ) {
        // [FE-5 route C step 3] The ~1940-line loop body now lives in
        // AgentLoopEngine (verbatim lift; VM member refs became host calls).
        // The VM stays the AgentLoopHost implementation so UI state,
        // persistence and routing semantics are unchanged.
        AgentLoopEngine(host = loopHost, traceObserver = traceObserver)
            .runAgentLoop(provider, systemPrompt, fallbackProviders, fallbackStrategy)
    }

    private fun finalizeAtTurnLimit(
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

    /**
     * Instance entry point used by the tool-dispatch path. The real logic lives
     * in the companion so tests can reach it without a ChatViewModel.
     */
    private fun preflightValidateToolCall(
        name: String,
        args: JSONObject,
        tools: List<AgentToolDefinition>,
    ): String? = preflightValidateToolCallImpl(name, args, tools)

    private suspend fun executeTool(
        name: String,
        argsJson: String,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>,
        assistantId: String,
        currentText: String,
    ): ToolExecutionResult {
        // T330: tri-state permission gating moved into the offload IPC
        // handler (OffloadGate). The CLIs land there whether the LLM
        // emitted a named tool call or a raw shell command, so the gate
        // is consistent across both paths. The pre-check that lived here
        // (`permissionTools = {calendar, location, …}`) was effectively
        // dead since these tools have no native ChatViewModel executor
        // — they always fall through to shell_execute or the offload
        // bridge, which is now where checkPermission runs.
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", name) } catch (_: Exception) { name }

        // T9: record tool call event
        val etStartMs = System.currentTimeMillis()
        traceObserver.agentTraceRecorder.toolCall(traceObserver.activeTraceTurn, toolId, name, argsJson)
        // T7-A: 观察 —— 工具调用消耗 tool_calls 预算（advisory，不阻断）
        // T7-C: tool_calls 预算耗尽 → 不执行工具，返回明确错误给 LLM（不是静默失败）
        if (!traceObserver.t7ConsumeAndTrace(AgentTraceRecorder.DIMENSION_TOOL_CALLS) { it.consumeToolCall() }) {
            traceObserver.t7BudgetStopReason = "tool_call_limit"
            // T7-D: 旁路验证 —— 计数耗尽进入收尾
            traceObserver.t7Reduce(AgentRunEvent.ProcessInterrupted("budget_exhausted(tool_calls)"))
            return ToolExecutionResult("Error: Agent budget exhausted (tool_calls)", false, toolTitle = toolTitle)
        }
        traceObserver.t7State(ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.EXECUTING_TOOLS), ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.EXECUTING_TOOLS), "ToolStarted($name)")
        // T7-D: 旁路验证 —— 工具开始
        traceObserver.t7Reduce(AgentRunEvent.ToolStarted(name))

        // T7-B: tool slot lease —— 工具执行期间占用一个并发工具槽位
        // （budget 的 tryAcquireToolSlot，advisory），trace 侧登记 acquire；
        // finally 无条件 release（成功/异常/取消都释放，不泄漏槽位）。
        val toolLease = "tool-$toolId-${traceObserver.activeRunId ?: "norun"}"
        traceObserver.activeRunBudget?.tryAcquireToolSlot()
        traceObserver.t7ResourceAcquire(
            resourceType = AgentTraceRecorder.RESOURCE_TOOL_SLOT,
            resourceId = toolId,
            leaseToken = toolLease,
        )
        val result = try {
            when (name) {
                FileReadTool.NAME -> {
                    val result = FileReadTool.execute(argsJson, activeSessionId, context)
                    // Record skill usage when SKILL.md under /var/minis/skills/<id>/ is read.
                    if (result.success) {
                        runCatching {
                            val readPath = JSONObject(argsJson).optString("path", "")
                            if (readPath.isNotEmpty()) {
                                skillRepository?.skillIdFromPath(readPath)?.let { sid ->
                                    skillRepository.recordSkillUse(sid)
                                }
                            }
                        }
                    }
                    result
                }
                FileWriteTool.NAME -> FileWriteTool.execute(argsJson, activeSessionId, context).also {
                    if (it.success) maybeReloadSkillsForPath(argsJson)
                }
                FileEditTool.NAME -> FileEditTool.execute(argsJson, activeSessionId, context).also {
                    if (it.success) maybeReloadSkillsForPath(argsJson)
                }
                // T178: pass sessionId + context so read_image routes through
                // resolveSessionHostPath like file_read/write/edit do — without
                // these, the tool consults the global last-writer-wins
                // bindMounts map and would surface another session's
                // /var/minis/{workspace,attachments,offloads,browser} files.
                ReadImageTool.NAME -> ReadImageTool.execute(argsJson, activeSessionId, context)
                "shell_execute" -> executeShellCommand(argsJson, toolId, toolBlocks, assistantId, currentText)
                "browser_use" -> executeBrowserUseTool(argsJson)
                "memory_write" -> executeMemoryWriteTool(argsJson)
                "memory_get" -> executeMemoryGetTool(argsJson)
                "memory_rollup" -> executeMemoryRollupTool()
                // [T7-subagent] spawn_agent: delegate to an independent sub-agent
                // instance running the named skill.
                SubagentSkill.NAME -> executeSpawnAgentTool(argsJson)
                else -> ToolExecutionResult("Unknown tool: $name", false)
            }
        } finally {
            // T7-B: 无条件释放 tool slot —— 覆盖成功、普通异常、CancellationException
            traceObserver.activeRunBudget?.releaseToolSlot()
            traceObserver.t7ResourceRelease(
                resourceType = AgentTraceRecorder.RESOURCE_TOOL_SLOT,
                resourceId = toolId,
                leaseToken = toolLease,
                releasedBy = AgentTraceRecorder.RELEASED_FINALIZE,
            )
        }

        // T9: record tool result event
        traceObserver.agentTraceRecorder.toolResult(
            turn = traceObserver.activeTraceTurn,
            toolId = toolId,
            name = name,
            success = result.success,
            output = result.output,
            durationMs = System.currentTimeMillis() - etStartMs,
        )
        // T7-A: 观察 —— 工具结束（ToolFinished 语义）
        traceObserver.t7State(ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.EXECUTING_TOOLS), ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.EXECUTING_TOOLS), "ToolFinished($name)")
        // T7-D: 旁路验证 —— 工具结束（resultKnown = 结果已到达）
        traceObserver.t7Reduce(AgentRunEvent.ToolFinished(name, resultKnown = true))

        // T3: failure-learning automation hook. Side-channel only — the
        // failed result still flows to the LLM exactly as before; this just
        // appends a structured, deduplicated block to the session's
        // `.learnings/ERRORS.md` so later agent turns can learn from it
        // without relying on the agent remembering to check the skill.
        if (!result.success) {
            runCatching {
                toolFailureHook.recordFailure(name, result.output, argsJson, activeSessionId)
            }
            // Deliberately swallowed: a logging failure must never break the
            // tool-result path back to the model.
        }
        return result
    }

    /**
     * [T7-subagent] Execute [SubagentSkill.NAME] — spawn an independent
     * sub-agent using the named skill. The sub-agent gets its own system
     * prompt (from the skill body), a filtered tool set, and runs its own
     * independent loop with its own budget. Context is fully isolated from
     * the main agent's [agentHistory].
     */
    private suspend fun executeSpawnAgentTool(argsJson: String): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        val skillName = args.optString("skill_name", "").trim()
        val query = args.optString("query", "").trim()
        val title = args.optString("tool_title", "Sub-agent").ifBlank { "Sub-agent" }

        if (skillName.isBlank()) {
            return ToolExecutionResult("Error: spawn_agent requires 'skill_name'", false, toolTitle = title)
        }
        if (query.isBlank()) {
            return ToolExecutionResult("Error: spawn_agent requires 'query'", false, toolTitle = title)
        }

        val repo = skillRepository ?: return ToolExecutionResult(
            "Error: Skill system unavailable", false, toolTitle = title,
        )

        // 1. Look up the skill
        val skill = repo.skills.value.find {
            it.name == skillName || it.id == skillName || it.name.equals(skillName, ignoreCase = true)
        } ?: return ToolExecutionResult(
            "Error: Skill '$skillName' not found. Make sure it is installed and the name is correct.",
            false, toolTitle = title,
        )

        if (!repo.isEnabledForSession(skill.id, activeSessionId)) {
            return ToolExecutionResult(
                "Error: Skill '$skillName' is disabled for this session",
                false, toolTitle = title,
            )
        }

        // 2. Parse subagent config
        val config = SubagentSkill.parseSubagentConfig(skill)
        if (!config.isSubagent) {
            return ToolExecutionResult(
                "Error: Skill '$skillName' is not a sub-agent skill. " +
                    "Add `subagent: true` to its SKILL.md frontmatter to enable sub-agent mode.",
                false, toolTitle = title,
            )
        }

        // 3. Build filtered tool set
        val subagentTools = SubagentSkill.buildFilteredTools(agentTools, config.allowedTools)
        if (subagentTools.isEmpty()) {
            return ToolExecutionResult(
                "Error: Skill '$skillName' has no usable tools (all filtered out by forbidden/allowlist)",
                false, toolTitle = title,
            )
        }

        // 4. Build system prompt + history
        val systemPrompt = SubagentSkill.buildSystemPrompt(skill)
        val provider = currentProvider ?: return ToolExecutionResult(
            "Error: No active provider available", false, toolTitle = title,
        )

        // 5. Run the sub-agent loop (extracted engine). Guard first: the
        // original returned this exact error when the provider had no
        // instance context (setup failure, not a mid-run error).
        if (provider.instanceContext == null) {
            return ToolExecutionResult(
                "Error: No provider instance context for sub-agent remote execution",
                false, toolTitle = title,
            )
        }
        return com.openminis.app.ui.chat.runSubagentLoop(
            skillName = skillName,
            query = query,
            title = "Sub-agent: ${skill.name}",
            config = config,
            systemPrompt = systemPrompt,
            streamProvider = { messages ->
                val instance = provider.instanceContext!!
                com.openminis.app.sandbox.offload.ProviderExecutionGateway.stream(
                    context = context,
                    instance = instance,
                    model = provider.model,
                    messages = messages,
                    systemPrompt = systemPrompt,
                    maxTokens = config.maxOutputTokens,
                    temperature = null,
                    tools = subagentTools,
                    thinkingLevel = ThinkingLevel.OFF,
                )
            },
            executeSubTool = { name, subArgs -> executeSubagentTool(name, subArgs) },
            log = { AppLogger.warning(TAG, it) },
        )
    }

    /**
     * [T7-subagent] Execute a tool inside a sub-agent's loop. Mirrors the
     * main [executeTool] dispatch but without UI updates (toolBlocks,
     * assistantId, etc.) — the sub-agent produces file/memory results only.
     * Tools that are FORBIDDEN for sub-agents never reach this method
     * because [SubagentSkill.buildFilteredTools] excludes them.
     */
    private fun executeSubagentTool(name: String, argsJson: String): ToolExecutionResult = when (name) {
        FileReadTool.NAME -> FileReadTool.execute(argsJson, activeSessionId, context)
        FileWriteTool.NAME -> FileWriteTool.execute(argsJson, activeSessionId, context).also {
            if (it.success) maybeReloadSkillsForPath(argsJson)
        }
        FileEditTool.NAME -> FileEditTool.execute(argsJson, activeSessionId, context).also {
            if (it.success) maybeReloadSkillsForPath(argsJson)
        }
        ReadImageTool.NAME -> ReadImageTool.execute(argsJson, activeSessionId, context)
        "memory_write" -> executeMemoryWriteTool(argsJson)
        "memory_get" -> executeMemoryGetTool(argsJson)
        "memory_rollup" -> executeMemoryRollupTool()
        else -> ToolExecutionResult("Error: Unknown or forbidden tool: $name", false)
    }

    /**
     * Mirror of iOS AIChatViewModel post-tool hook (Agent/Chat/AIChatViewModel.swift:5387 / :5408):
     * when the agent writes or edits a SKILL.md inside a `/skills/` directory
     * we ask SkillRepository to re-scan disk so the new skill is visible
     * immediately, without waiting for app restart.
     */
    /**
     * Persist a tool-failure block into this session's `.learnings/ERRORS.md`
     * (host path resolved via PRootKernel so it lands in the session's own
     * workspace, not the global bind-mount map). Mirrors OmniBot writing into
     * its app data dir: the RikkaMinis equivalent of "app data" is the
     * per-session workspace, which the agent's shell can read back.
     */
    private fun appendToolFailureBlock(block: String) {
        runCatching {
            val file = PRootKernel.resolveSessionHostPath(
                activeSessionId,
                "/var/minis/workspace/.learnings/ERRORS.md",
                context,
            ) ?: return
            file.parentFile?.mkdirs()
            file.appendText(block)
        }
    }

    private fun maybeReloadSkillsForPath(argsJson: String) {
        com.openminis.app.ui.chat.maybeReloadSkillsForPath(argsJson, skillRepository)
    }

    private suspend fun executeShellCommand(
        argsJson: String,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>,
        assistantId: String,
        currentText: String,
    ): ToolExecutionResult {
        // T7-A: 观察 —— shell 命令消耗 shell_commands 预算（advisory，不阻断）
        // T7-C: shell_commands 预算耗尽 → 不执行命令，返回明确错误
        if (!traceObserver.t7ConsumeAndTrace(AgentTraceRecorder.DIMENSION_SHELL_COMMANDS) { it.consumeShellCommand() }) {
            return ToolExecutionResult("Error: Agent budget exhausted (shell_commands)", false)
        }
        // T7-B: shell lease —— 执行期间占用 shell 资源，finally 无条件释放
        // （覆盖成功、异常、取消路径，不泄漏 shell 槽位）
        val shellLease = "shell-${toolId}-${traceObserver.activeRunId ?: "norun"}"
        traceObserver.t7ResourceAcquire(
            resourceType = AgentTraceRecorder.RESOURCE_SHELL,
            resourceId = "shell_execute",
            leaseToken = shellLease,
        )
        // FE-5 route B: the engine owns bashism detection / coordinator
        // dispatch / URL brokering / env redaction; this wrapper only owns
        // the T7 lease lifecycle and the toolBlocks UI side channel.
        try {
            resetDisplayBuffer(toolId)
            val result = try {
                executeShellCommandEngine(
                    argsJson = argsJson,
                    dispatchSessionId = activeSessionId,
                    context = context,
                    toolKey = toolId,
                    onBlockUpdate = { displayContent ->
                        val idx = toolBlocks.indexOfFirst { it.id == toolId }
                        if (idx >= 0) {
                            toolBlocks[idx] = toolBlocks[idx].copy(content = displayContent)
                            viewModelScope.launch(Dispatchers.Main) {
                                updateAssistantMessage(assistantId, currentText, true, toolBlocks)
                            }
                        }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return ToolExecutionResult("Error: ${e.message}", false)
            } finally {
                resetDisplayBuffer(toolId)
            }
            return ToolExecutionResult(
                output = result.output,
                success = result.success,
                toolTitle = result.toolTitle,
                timedOut = result.timedOut,
            )
        } finally {
            // T7-B: 无条件释放 shell lease —— 覆盖成功、异常、取消路径
            traceObserver.t7ResourceRelease(
                resourceType = AgentTraceRecorder.RESOURCE_SHELL,
                resourceId = "shell_execute",
                leaseToken = shellLease,
                releasedBy = AgentTraceRecorder.RELEASED_FINALIZE,
            )
        }
    }

    private suspend fun executeBrowserUseTool(argsJson: String): ToolExecutionResult =
        com.openminis.app.ui.chat.executeBrowserUseTool(
            argsJson = argsJson,
            tabPool = browserTabPool,
            artifactWriter = { filename, data ->
                persistBrowserArtifact(filename, data)
            },
            resizeJpeg = { raw, edge -> resizeJpegToMaxEdge(raw, edge) },
        )

    /**
     * Write bytes to <filesDir>/minis-sessions/<sessionId>/browser/<filename>.
     * That directory is bind-mounted to `/var/minis/browser/` so the agent can
     * read it back via file_read / file_write / minis:// URLs.
     * Returns the host absolute path on success, null otherwise.
     */
    private fun persistBrowserArtifact(filename: String, data: ByteArray): String? =
        com.openminis.app.ui.chat.persistBrowserArtifact(activeSessionId, filename, data, context)

    private fun linuxPathToMinisURL(path: String): String? =
        com.openminis.app.ui.chat.linuxPathToMinisURL(path)

    /**
     * Resize a JPEG so its longest edge is at most `maxEdge` px. Returns null
     * if already within bounds. Mirrors iOS AIChatViewModel.resizedImageData.
     */
    private fun resizeJpegToMaxEdge(data: ByteArray, maxEdge: Int): ByteArray? {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxEdge) { bmp.recycle(); return null }
        val scale = maxEdge.toFloat() / longest
        val w = (bmp.width * scale).toInt()
        val h = (bmp.height * scale).toInt()
        val resized = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
        bmp.recycle()
        val out = java.io.ByteArrayOutputStream()
        resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        resized.recycle()
        return out.toByteArray()
    }

    private fun executeMemoryWriteTool(argsJson: String): ToolExecutionResult {
        val repo = memoryRepository ?: return ToolExecutionResult("Error: Memory not available", false)
        return com.openminis.app.ui.chat.executeMemoryWriteTool(argsJson, repo, _memoryEnabled.value) { record ->
            _memoryToolRecords.value = _memoryToolRecords.value + record
        }
    }

    private fun executeMemoryGetTool(argsJson: String): ToolExecutionResult {
        val repo = memoryRepository ?: return ToolExecutionResult("Error: Memory not available", false)
        return com.openminis.app.ui.chat.executeMemoryGetTool(argsJson, repo) { record ->
            _memoryToolRecords.value = _memoryToolRecords.value + record
        }
    }

    // [T6-rollup] On-demand memory rollup: distills the previous day's daily
    // log into MEMORY-ROLLUP.md. Uses the same memory dir as the repository.
    private fun executeMemoryRollupTool(): ToolExecutionResult {
        val repo = memoryRepository ?: return ToolExecutionResult("Error: Memory not available", false)
        return com.openminis.app.ui.chat.executeMemoryRollupTool(repo) { record ->
            _memoryToolRecords.value = _memoryToolRecords.value + record
        }
    }

    // ─── UI Helpers ──────────────────────────────────────────────────────

    private fun updateAssistantMessage(
        id: String,
        content: String,
        isStreaming: Boolean,
        toolBlocks: List<AssistantBlock>,
        isAwaitingModelResponse: Boolean = false,
    ) {
        // T-streaming-side-channel: during a live turn, write high-frequency
        // fields into [_streamingById] instead of mutating the canonical
        // message list. This keeps the `messages` StateFlow reference stable
        // across the turn so ChatScreen's top-level reads
        // (`messages.any/.associate/.isNotEmpty/.lastOrNull`) don't trigger
        // a full recompose of the 8980-line composable on every token.
        //
        // On stream end (isStreaming=false), drain the accumulated delta
        // back into the canonical message in a single `_messages` emit, then
        // clear the side-channel entry so post-turn reads (history rebuild,
        // persist, agent loop) see the canonical truth.
        if (isStreaming) {
            val toolBlocksImmutable = toolBlocks.toList()

            // [T-android-stream-flush-dualpath] Dual-path flush at the
            // message-accumulation layer (NOT per-fragment, which never
            // throttled). Decide whether to publish this delta now:
            //   • structural change (toolBlocks count / awaiting flag) →
            //     publish immediately — these drive tool-bubble UI and must
            //     never be coalesced away or the bubble state stalls.
            //   • else time-path: enough ms since last publish for this length.
            //   • else newline fast-path: a line break in the newly-streamed
            //     chunk + ≥50 new chars, gated to short docs (iOS parity).
            // When none fire, stash the latest as a trailing publish so the
            // final chunk before a pause still lands; a fresh delta cancels
            // and replaces it.
            val st = streamFlushStates.getOrPut(id) {
                StreamFlushState().also { it.lastFlushedLen = 0 }
            }
            val prev = _streamingById.value[id]
            // [T-android-stream-flush-review] Structural change also covers an
            // in-place tool-block STATUS flip (running → success), not just a
            // count change — otherwise a spinner→checkmark could lag up to one
            // throttle tier. Compare a cheap (kind,status) fingerprint.
            val toolStatusChanged = prev != null &&
                prev.toolBlocks.size == toolBlocksImmutable.size &&
                toolBlocksImmutable.indices.any { i ->
                    prev.toolBlocks[i].toolStatus != toolBlocksImmutable[i].toolStatus
                }
            val structuralChange = prev == null ||
                prev.toolBlocks.size != toolBlocksImmutable.size ||
                prev.isAwaitingModelResponse != isAwaitingModelResponse ||
                toolStatusChanged
            val now = System.currentTimeMillis()
            val elapsed = now - st.lastFlushMs
            val throttle = streamFlushThrottleMs(content.length)
            val newChunk = if (content.length > st.lastFlushedLen) {
                content.substring(st.lastFlushedLen.coerceAtMost(content.length))
            } else ""
            val unflushed = content.length - st.lastFlushedLen
            val newlineFlush = content.length < NEWLINE_FLUSH_MAX_LEN &&
                newChunk.contains('\n') &&
                unflushed >= NEWLINE_FLUSH_MIN_CHARS

            fun publish(text: String, blocks: List<AssistantBlock>, awaiting: Boolean) {
                // [T-streamlining-thinking-fix] Monotonic terminal guard: a tool
                // block published in a terminal state (SUCCESS/FAILED/TIMEOUT/
                // CANCELLED) must never regress to an alive state (RUNNING/
                // STREAMING/PENDING) in a later snapshot — otherwise the tool card
                // can get stuck "being called" indefinitely. Reads prev blocks
                // fresh from the side-channel (not the outer `prev`, which may be
                // stale across trailing publishes).
                val prevBlocks = _streamingById.value[id]?.toolBlocks
                val guarded = ToolBlockMonotonicGuard.guard(prevBlocks, blocks)
                guarded.regressions.forEach { r ->
                    AppLogger.warning(
                        TAG,
                        "ToolMonotonic block id=${r.blockId} regressed " +
                            "${r.prevStatus} -> ${r.nextStatus} (messageId=$id); clamped",
                    )
                }
                _streamingById.value = _streamingById.value + (
                    id to StreamingDelta(
                        content = text,
                        toolBlocks = guarded.blocks,
                        isAwaitingModelResponse = awaiting,
                        epoch = streamEpoch,
                    )
                )
                st.lastFlushMs = System.currentTimeMillis()
                st.lastFlushedLen = text.length
            }

            if (structuralChange || elapsed >= throttle || newlineFlush) {
                st.trailingJob?.cancel()
                st.trailingJob = null
                st.pendingContent = null
                publish(content, toolBlocksImmutable, isAwaitingModelResponse)
            } else {
                // Throttled: always record this delta as the freshest pending
                // value, so whenever the trailing job fires it publishes the
                // latest text — not whatever was captured when it was first
                // scheduled (review #2). Schedule the job only once.
                st.pendingContent = content
                st.pendingBlocks = toolBlocksImmutable
                st.pendingAwaiting = isAwaitingModelResponse
                if (st.trailingJob == null) {
                    val wait = (throttle - elapsed).coerceAtLeast(16L)
                    st.trailingJob = viewModelScope.launch {
                        kotlinx.coroutines.delay(wait)
                        val pc = st.pendingContent
                        if (pc != null) {
                            publish(pc, st.pendingBlocks, st.pendingAwaiting)
                            st.pendingContent = null
                        }
                        st.trailingJob = null
                    }
                }
            }
            // [T-android-timeout-while-running] If a transient banner
            // (`message.error`) is still on the canonical assistant message
            // when a fresh streaming event arrives, the banner is stale —
            // the model is producing again, by construction the prior
            // transient timeout / retry / fallback has been resolved.
            // Clear it in the same mutation. setTransientInlineError /
            // setInlineError are the only paths that write `error`; the
            // terminal path (setInlineError) sets isStreaming=false on the
            // same message in the same emit, so it cannot reach this
            // branch and the clear is safe.
            //
            // 𝙓𝙄𝙉 TG36302 (0.10): user saw a red "timeout / retry" banner
            // glued to the bottom of the conversation while the agent
            // continued running (LM Studio tool loop on 30/30, "Minis is
            // thinking" indicator). Caused by (a) the fallback-switch branch in
            // runAgentLoop not calling clearInlineError(), and (b) the
            // streaming-side-channel writing every subsequent delta into
            // _streamingById without ever touching _messages where
            // `error` lives. (a) is fixed at the fallback site; (b) is
            // fixed here defensively so any future write-path that forgets
            // to clear can't strand a stale banner across the rest of
            // the turn.
            val canonical = _messages.value
            val canonicalIdx = canonical.indexOfLast { it.id == id }
            if (canonicalIdx >= 0 && canonical[canonicalIdx].error != null) {
                val updated = canonical.toMutableList()
                updated[canonicalIdx] = canonical[canonicalIdx].copy(error = null)
                _messages.value = updated
            }
            return
        }
        // [T-android-stream-flush-dualpath] Stream end → cancel any pending
        // trailing flush and drop the throttle accumulator for this message;
        // the canonical drain below publishes the final, complete text.
        clearStreamFlushState(id)
        // Stream end → sync delta into canonical message + clear side-channel.
        val current = _messages.value
        val idx = current.indexOfLast { it.id == id }
        if (idx < 0) {
            // The message itself is gone (e.g. clearChat raced ahead) —
            // just clear any leftover stream delta and bail.
            if (_streamingById.value.containsKey(id)) {
                _streamingById.value = _streamingById.value - id
            }
            return
        }
        val updated = current.toMutableList()
        updated[idx] = current[idx].copy(
            content = content,
            isStreaming = false,
            toolBlocks = toolBlocks.toList(),
            isAwaitingModelResponse = isAwaitingModelResponse,
        )
        _messages.value = updated
        if (_streamingById.value.containsKey(id)) {
            _streamingById.value = _streamingById.value - id
        }
    }

    /**
     * Read a message's content + toolBlocks honoring any active streaming
     * delta. Use this from non-render code that needs the "current" view of
     * a message during a live turn (e.g. agent history builders, persistence
     * snapshots) without forcing the render layer to consult the delta map.
     */
    internal fun effectiveContent(id: String): String? {
        val delta = _streamingById.value[id]
        if (delta != null) return delta.content
        return _messages.value.firstOrNull { it.id == id }?.content
    }

    /**
     * Force-drain any outstanding streaming delta for [id] back into the
     * canonical message and clear the side-channel slot. Called from turn
     * exit paths (cancel / error / retry / resume / clearChat) so the
     * canonical message reflects all accumulated content even if the last
     * [updateAssistantMessage] call had isStreaming=true.
     */
    private fun flushStreamingDelta(id: String) {
        val delta = _streamingById.value[id] ?: return
        val current = _messages.value
        val idx = current.indexOfLast { it.id == id }
        if (idx >= 0) {
            val updated = current.toMutableList()
            updated[idx] = current[idx].copy(
                content = delta.content,
                isStreaming = false,
                toolBlocks = delta.toolBlocks,
                isAwaitingModelResponse = delta.isAwaitingModelResponse,
            )
            _messages.value = updated
        }
        // [T-android-stream-flush-review] Cancel the pending trailing flush
        // BEFORE clearing the side channel — otherwise its viewModelScope
        // coroutine (not cancelled by streamJob.cancel) fires later and
        // re-adds the orphan side-channel entry, reviving a stale "thinking"
        // row after the turn was stopped/drained.
        clearStreamFlushState(id)
        _streamingById.value = _streamingById.value - id
    }

    /** Drain ALL outstanding streaming deltas (called on global resets). */
    private fun flushAllStreamingDeltas() {
        clearAllStreamFlushStates()
        val pending = _streamingById.value
        if (pending.isEmpty()) return
        val current = _messages.value.toMutableList()
        var changed = false
        for ((id, delta) in pending) {
            val idx = current.indexOfLast { it.id == id }
            if (idx < 0) continue
            current[idx] = current[idx].copy(
                content = delta.content,
                isStreaming = false,
                toolBlocks = delta.toolBlocks,
                isAwaitingModelResponse = delta.isAwaitingModelResponse,
            )
            changed = true
        }
        if (changed) _messages.value = current
        _streamingById.value = emptyMap()
    }

    /**
     * Build the ordered AgentContentPart list for this turn by walking the slice of
     * `allToolBlocks` that belongs to the current turn (from `turnStartBlockIndex` to
     * the end). Text blocks become `Text`, tool_use blocks become `ToolUse` — the
     * original stream order is preserved by the list slice order. Thinking and info
     * blocks are skipped (they're persisted via `reasoningContent` or not at all).
     */
    internal fun buildTurnParts(
        allToolBlocks: List<AssistantBlock>,
        turnStartBlockIndex: Int,
        toolCallInputs: Map<String, String>,
    ): List<AgentContentPart> =
        // RC3: delegate to the top-level pure builder (production-used) so the
        // turn-persistence semantics are directly JVM-testable and cannot drift
        // from its tests. See F-T01-01 acceptance invariant.
        buildTurnPartsPure(allToolBlocks, turnStartBlockIndex, toolCallInputs)

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
    private fun buildAssistantPartsJson(
        parts: List<AgentContentPart>,
        toolBlockMeta: Map<String, AssistantBlock>,
    ): String = buildAssistantTurnPartsJson(parts, toolBlockMeta)

    private suspend fun persistAssistantTurn(
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
    private suspend fun persistToolResultMessage(parts: List<AgentContentPart>): String? {
        val results = parts.filterIsInstance<AgentContentPart.ToolResult>()
        if (results.isEmpty()) return null
        val partsJson = buildToolResultPartsJson(results)
        val entity = chatRepository.appendMessage(realSessionId.ifEmpty { sessionId }, "user", partsJson)
        return entity.id
    }

    /**
     * Build the "内置集成" prompt fragment: the list of bundled platform
     * skills (semantic-memory / github-ops / cloudflare-fullright-ops)
     * with their *current* capability tier, derived from each skill's
     * `requirements.json`.
     *
     * Unlike the static `<available_skills>` block, this tells the agent
     * what it can actually do right now — so it never needs to guess from
     * trial-and-error whether a token is configured before attempting an
     * operation. Returns null when no bundled platform skill applies to
     * this session.
     */
    private fun buildIntegrationStatus(): String? {
        val repo = skillRepository ?: return null
        val platformIds = listOf("semantic-memory", "github-ops", "cloudflare-fullright-ops")
        val rows = mutableListOf<String>()

        for (id in platformIds) {
            val skill = repo.skills.value.find { it.id == id } ?: continue
            if (!repo.isEnabledForSession(id, activeSessionId)) continue
            val reqs = repo.loadSkillRequirements(id)
            val tier = determineIntegrationTier(reqs)
            val declaredVars = reqs?.env?.keys ?: emptySet()
            val foundVars = envVarsSnapshot().keys.intersect(declaredVars)
            // A platform is only "available" if it defines an explicit
            // capability description for its current tier. If the tier key
            // is absent (e.g. no entry for "0"), the platform has no
            // capability at that tier — don't label it "zero-config usable".
            val ops = reqs?.tiers?.get(tier.toString())
            // Diagnostic: surface exactly which declared env vars were found
            // in the store at prompt-build time, so a "以为配了却显示需配置"
            // mismatch is greppable in logcat instead of being a silent guess.
            AppLogger.info(
                TAG,
                "[IntegrationStatus] ${skill.name}: tier=$tier " +
                    "declared=${declaredVars.sorted()} found=${foundVars.sorted()} " +
                    "hasCapability=${ops != null} enabled=${repo.isEnabledForSession(id, activeSessionId)}"
            )
            if (ops == null) {
                // No capability described for this tier → no free tier.
                rows.add("| ${skill.name} | 🔒 需配置 | 暂无可用能力（未定义 Tier $tier 能力） |")
                continue
            }
            val status = when (tier) {
                2 -> "✅ 完整"
                1 -> "⚠️ 只读"
                else -> "⚡ 零配置"
            }
            rows.add("| ${skill.name} | $status | $ops |")
        }

        if (rows.isEmpty()) return null

        return buildString {
            append("## 内置集成\n\n")
            append("以下平台技能已内置，无需手动安装。Tier 0 零配置即可使用；Tier 1/2 需配置对应环境变量（Settings → Environments 或 minis-config envvars）。\n\n")
            append("| 集成 | 状态 | 可用操作 |\n")
            append("|------|------|--------|\n")
            rows.forEach { append(it).append("\n") }
            append("\n")
            append("使用涉及环境变量的操作前，请先检查对应变量是否已设置。")
        }
    }

    /**
     * Map a platform skill's `requirements.json` to a tier 0/1/2, based on
     * which of its declared env vars are present in the app's environment
     * config. Every requirement present → Tier 2; a partial subset → Tier 1;
     * none → Tier 0. A skill with no declared `env` stays at Tier 0 (its
     * operations are all zero-config).
     */
    private fun determineIntegrationTier(reqs: com.openminis.app.data.repository.SkillRepository.SkillRequirements?): Int {
        val declared = reqs?.env?.keys ?: return 0
        if (declared.isEmpty()) return 0
        val configured = countConfiguredEnvVars(declared)
        return when {
            configured == declared.size -> 2
            configured > 0 -> 1
            else -> 0
        }
    }

    /**
     * Count how many of [names] exist as configured environment variables.
     * Reads from minis-config's envvar store (the sandbox's exported env)
     * — a variable exists iff it is non-blank. Robust: never throws; a
     * missing/unreadable store counts every var as unconfigured.
     */
    private fun countConfiguredEnvVars(names: Set<String>): Int {
        if (names.isEmpty()) return 0
        return try {
            val env = envVarsSnapshot()
            names.count { env.containsKey(it) && !env[it].isNullOrBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "countConfiguredEnvVars: ${e.message}")
            0
        }
    }

    /**
     * Read the current environment-variable store as a snapshot map.
     * Queries [com.openminis.app.data.repository.EnvVarRepository] which is
     * the same encrypted store the sandbox injects. Only keys with a non-null
     * stored value are returned — configured-but-blank vars don't count as
     * present. Values are read internally by the repo but never surfaced
     * outside `countConfiguredEnvVars` (we only test `containsKey`).
     */
    private fun envVarsSnapshot(): Map<String, String> =
        try {
            // Reuse the app-wide singleton (wired in MinisApp.onCreate via
            // EnvVarRedactor.envVarRepository) instead of constructing a fresh
            // EnvVarRepository per call. A fresh instance re-runs loadMetadata()
            // (JSON parse + StateFlow rebuild) on every snapshot, which is pure
            // duplicated IO; the singleton caches metadata in its StateFlow and
            // reads the same encrypted prefs. Fallback keeps headless/debug
            // callers (ChatMutationMethods / HeadlessChatRunner) working even
            // before the singleton is wired.
            val repo = com.openminis.app.data.EnvVarRedactor.envVarRepository
                ?: com.openminis.app.data.repository.EnvVarRepository(context)
            repo.allAsDict()
        } catch (e: Exception) {
            Log.w(TAG, "envVarsSnapshot: ${e.message}")
            emptyMap()
        }

    private fun buildSystemPrompt(): String? {
        // Cache-friendly layout: keep `base` byte-stable by stripping out anything
        // that varies per request, then append a "Runtime context" suffix at the
        // very end with all the dynamic bits (date, timezone, locale, configured
        // minis-model-use count). OpenAI / DeepSeek prompt caching is prefix-
        // based, so the longer the static head, the better the hit rate.
        // Pre-T122 the prompt embedded `Current time: yyyy-MM-dd HH:mm` mid-base,
        // which guaranteed cache misses across minute boundaries — even a quick
        // follow-up could land on a different minute and pay full ingestion.
        val today = java.time.LocalDate.now()
        val dateStr = today.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val tzId = java.util.TimeZone.getDefault().id
        val lang = context.resources.configuration.locales[0].toLanguageTag()

        // Count of agent-loop-visible models for the `minis-model-use` CLI
        // (exposed as a shell command via the native_offload handler).
        val modelUseCount = try { providerRepository.resolvedAgentLoopEntries().size } catch (_: Exception) { 0 }

        // [T-soul-md] Layer 1 is rendered by SystemPromptBuilder, which
        // owns the "You are <name>, a capable AI assistant running on an
        // Android device ..." identity sentence (parametric on SOUL.md's
        // `name` field) and optionally appends a clearly-labeled
        // Personality section from SOUL.md's body. The original wording
        // is preserved inside SystemPromptBuilder.IDENTITY_TEMPLATE so we
        // don't regress model behavior that depended on it. When SOUL.md
        // has no personality body, identitySection() returns the identity
        // sentence with its original single trailing space — the full
        // assembled prompt then matches the pre-SOUL prompt byte-for-byte.
        val identitySection = com.openminis.app.agent.SystemPromptBuilder.identitySection(context)
        // [T-memory-toggle-gates-injection-and-tools-android] Mirror the iOS
        // gate: when memory is disabled for this session, replace the
        // "memory_write / memory_get" tool bullets and the "Memory system:"
        // guidance block with a single explicit DISABLED notice. The model
        // never sees the tools either (filtered in agentTools above), but
        // surfacing the state in the prompt lets it explain why memories
        // aren't reachable when the user asks. The fragment / tool dual
        // gate is symmetrical: enable both or disable both, never mismatch.
        val memoryOn = _memoryEnabled.value
        val toolListMemoryBullets = if (memoryOn) {
            """
- memory_write: Save a memory entry to today's daily log (YYYY-MM-DD.md). Use proactively to note user preferences, project patterns, and important context.
- memory_get: Recall memories with keyword search. Check memory at the start of new topics to leverage past knowledge.
- memory_rollup: On-demand memory distillation — run this when daily logs are large to extract stable rules into MEMORY-ROLLUP.md. Idempotent."""
        } else {
            // Empty — no memory_write / memory_get bullets when disabled.
            // The "Memory system:" section below also collapses, so the
            // model gets a coherent picture rather than half-mentioned
            // tools it can't actually call.
            ""
        }
        val memorySystemSection = if (memoryOn) {
            """

Memory system (currently ENABLED):
- memory_write writes to today's daily log (YYYY-MM-DD.md) — use it for session notes, key facts, project context, things learned, and action items.
- memory_rollup: Distill stable rules from the previous day's daily log into MEMORY-ROLLUP.md. Call this when daily logs are growing large — it surfaces reusable knowledge concisely. Idempotent; skips dates already rolled up.
- GLOBAL.md (/var/minis/memory/GLOBAL.md) stores persistent preferences, settings, and general-purpose conventions. To read it, use file_read (NOT memory_get). To update it, use file_read first then file_edit. If GLOBAL.md does not exist yet, use file_write to create it directly.
- IMPORTANT: Only write to GLOBAL.md when the user explicitly asks (e.g. 'remember this globally', 'save to global memory'). Before editing, deduplicate and clean up — avoid ambiguity, repetition, or daily-log-style entries. GLOBAL.md should contain only concise, reusable knowledge (preferences, settings, conventions), NOT session logs or transient context.
- Use memory_get to recall past knowledge before starting tasks — check if there are relevant memories that can help.
- Proactively save memories (via memory_write to daily log) when you discover user preferences or important patterns — don't wait to be asked.
- When the user says 'remember this' or similar, use memory_write to persist to the daily log. Only write to GLOBAL.md if the user specifically asks for global/persistent storage.
- What NOT to remember: passwords, API keys, tokens, secrets, or any sensitive credentials. Warn the user about the risk first; only proceed if they explicitly confirm.
- Keep memories concise, factual, and general-purpose — avoid noise that won't be useful later."""
        } else {
            """

Memory system (currently DISABLED):
- The user has turned OFF memory injection and memory tools for this session. GLOBAL.md and recent daily logs are NOT included in this prompt, and the memory_write / memory_get tools are NOT available — do not attempt to call them.
- If the user asks why earlier memories aren't visible, or asks you to save something, tell them memory is currently disabled and point them at the /memory slash command or [Settings → Memory](minis://settings/memory) to re-enable it.
- SOUL.md (personality / identity) is unaffected by this toggle; the persona section above still applies."""
        }
        val base = identitySection + """You should proactively use shell commands to accomplish the user's tasks — installing packages (apk add), writing and running scripts, managing files, networking, and any other operations a Linux terminal can perform.

Available tools:
- shell_execute: Run any shell command. Each invocation is an isolated process with stdout/stderr captured. Prefer this for most tasks — it is a real Linux environment with persistent filesystem. Common tools (python3, pip, curl, wget, git, ssh, etc.) can be installed via apk add; Python packages via pip install. Use `which <cmd>` to check if a tool is already installed before running apk add — many packages persist across sessions. When you need to wait before checking results (e.g. polling, waiting for a process), use the `delay` parameter instead of `sleep` in the command — delay blocks the agent flow without occupying the shell, so other concurrent tasks can use it during the wait. This avoids resource contention. Execution discipline for long-running or dispatched work: make tool calls immediately instead of describing intentions, and keep working until the task is complete. Without a scheduler or timed-callback tool, `delay` is your ONLY wait mechanism within a turn — to follow up on something still running, chain delay-then-check calls at a task-appropriate interval until you have the result or hit a sensible retry cap. NEVER end a turn with a promise of future action: 'I'll keep monitoring', 'will sync the result later', and ending right after a single still-running status check with 'let's keep waiting' are all the same violation — once your turn ends, NOTHING runs until the user's next message. If polling to completion is genuinely not worth blocking the turn, close honestly instead: state that the task keeps running in the background, that you will only learn its outcome when the user next messages (or they ask you to check), and — if something must fire on a schedule beyond this conversation — point them to the options under 'Scheduled tasks' later in this prompt (native alarm reminder or a system-level schedule; those notify the USER, they do not wake you).
- file_read: Read file contents (faster than cat).
- file_write: Create new files or overwrite existing files (faster than echo/tee).
- file_edit: Edit existing files with exact string replacement (old_string → new_string). Preferred over file_write for modifications — always file_read first.
- browser_use: Web browsing (navigate, screenshot, click, type, get_text, scroll, scroll_and_collect, get_readable, get_backbone, fetch, etc.). Starts with a desktop Chrome user agent. Use screenshot to see the page.
  当 browser_use 触达 Google 登录 / OAuth 页（accounts.google.com、signin.google.com、myaccount.google.com、oauth2.googleapis.com 等）或网页返回 "disallowed_useragent" / 403 包含 "browser is not secure" 字样时，**不要重试或尝试登录** — Google 永久禁止 in-app WebView 完成登录，重试只会浪费 turn。改为告诉用户："此页面需要在系统 Chrome 完成登录" 并给出可点击的 Markdown link [在 Chrome 中打开](https://accounts.google.com/...)。点该 link 时 app 会跳出 Custom Tab；用户在 Chrome 完成操作后，请他**把所需结果（邮件正文 / 文档摘要 / 表格数据）粘贴回 chat**，你再继续帮他处理。这是 Android 平台限制，不是 bug。${toolListMemoryBullets}

Shared directory /var/minis/ (bidirectional read/write between shell and app):
  /var/minis/attachments/ — Media files (images, audio, video). Display inline with ![desc](minis://attachments/filename).
  /var/minis/workspace/   — Working files (scripts, data, configs). Link with [name](minis://workspace/filename).
  /var/minis/offloads/    — Auto-saved large outputs. Read with file_read.
  /var/minis/browser/     — Browser screenshots and extracts.
  /var/minis/shared/      — Cross-session shared storage for artifacts and documents. Organize by project or topic (e.g. shared/myproject/, shared/datasets/). Do NOT store temporary files here.
  /var/minis/memory/GLOBAL.md    — Persistent global memory (read-only, user-maintained via Settings).
  /var/minis/memory/YYYY-MM-DD.md — Daily memory log.
  /var/minis/mounts/<name>/      — User-mounted external folders from Settings → Mount External Folders. Presence and names vary per user; check this directory first when the task references external/user files. Some mounts may be read-only — file_write / file_edit will reject writes with a clear error message.

The minis:// URL scheme:
  minis://attachments/file.png  →  /var/minis/attachments/file.png
  minis://workspace/data.csv    →  /var/minis/workspace/data.csv
  minis://shared/project/f.txt  →  /var/minis/shared/project/f.txt

IMPORTANT: minis:// URLs are app-internal — they are NOT web URLs. Do NOT pass minis:// action URLs (open_terminal, views, settings) to browser_use — those are app deep links, use Markdown links in chat instead. However, minis:// resource URLs CAN be opened in browser_use with navigate. All directories under /var/minis/ are accessible: workspace, attachments, offloads, shared, etc. The built-in browser fully supports minis:// — HTML pages and all sub-resources (JS, CSS, images, fonts, etc.) referenced via minis:// absolute URLs or relative paths resolve correctly within the current session. When building multi-file web projects, use file_write to create files in the same directory (e.g. /var/minis/workspace/myapp/), then reference sub-resources with relative paths in HTML (e.g. <link href="style.css">, <script src="app.js">, <img src="logo.png">). The browser resolves relative paths against the minis:// base URL automatically. Cross-directory references also work with absolute minis:// URLs (e.g. <img src="minis://attachments/photo.png"> from a workspace HTML page). Navigate to the entry HTML to preview, e.g. minis://workspace/myapp/index.html.
To display a minis:// URL in chat, write it as a Markdown link or image (e.g. [name](minis://...)) — the app handles it when the user taps it.
IMPORTANT: minis:// URLs MUST be percent-encoded. Non-ASCII characters (Chinese, emoji, spaces, etc.) in filenames will break Markdown rendering if not encoded. Use the minis_url from tool results directly — it is already encoded. If you construct a minis:// URL manually, percent-encode the filename (e.g. %E4%B8%AD%E6%96%87 for non-ASCII characters).
When you write files to /var/minis/, the tool result includes a minis_url you can embed directly in Markdown.
Inline media — use the ![desc](minis://...) image syntax for ALL of images, audio, AND video. The same ![]() syntax renders an inline audio player or video player, not just images:
  - Images: ![chart](minis://attachments/chart.png)   → inline image (.png/.jpg/.gif/.webp)
  - Audio:  ![song](minis://attachments/song.mp3)     → inline audio player (.mp3/.m4a/.wav)
  - Video:  ![clip](minis://attachments/clip.mp4)     → inline video player (.mp4/.mov/.m4v)
Do NOT use the [text](url) link form for audio/video when you want them to play inline — that only produces a tappable link. Use ![]() to embed an actual player.
For non-media files, use Markdown links: [filename](minis://workspace/filename).
Tappable link previews: text/code (.py/.json/.md/etc), images, audio, video, HTML, and PDF files open native previews when the user taps a [name](minis://...) link.
Use Markdown links for all non-media minis:// files — the user can tap to preview them directly in chat.

File creation guidelines:
- Use file_write to CREATE new files. Use file_edit to MODIFY existing files. The shell is BusyBox ash: heredoc syntax (cat << EOF, python3 << 'EOF') may mis-parse braces, quotes, or special characters and execute abnormally — avoid it whenever possible, and prefer file_write over echo/printf for writing file contents. When you hit escaping or parsing errors with long inline content, write the content to a file first (file_write), then pass or execute the file (e.g. `python3 /tmp/script.py`).
- file_write and file_edit are atomic, preserve formatting, and make it easy to fix errors or update content later.
- shell_execute is for RUNNING commands, not for writing files.
- shell_execute supports multi-line commands directly — quoting and special characters are handled automatically. However, commands MUST NOT exceed 1000 characters. If longer, write a script file with file_write first, then run it.
- ICMP is blocked by the PRoot sandbox — `ping` will hang indefinitely. Use `curl` or `wget` to test network connectivity instead.
- Also (BusyBox ash, NOT bash): `**` recursive glob (globstar) is NOT supported. Use `find <dir> -name '*.ext'` for recursive file search, and pipe to `xargs` for tools like `wc`. Brace expansion ({a,b,c}) and bash arrays (arr=(...), ${'$'}{arr[@]}) are also unsupported — use space-separated strings with a for loop or multiple arguments instead.
- Python packages: many PyPI packages (numpy, pandas, scipy, pillow, etc.) lack musllinux_aarch64 wheels and will fail to build from source. Use Alpine's native packages instead: `apk search py3-<name>` then `apk add py3-numpy py3-pandas py3-matplotlib py3-pillow py3-scipy py3-requests`. Only fall back to `pip install` for pure-Python packages not available via apk. For matplotlib, always set `matplotlib.use('Agg')` before importing pyplot — there is no display server in the sandbox.
- Background services: each shell_execute runs in an isolated process. When starting a background server (e.g. `python3 -m http.server &`), you MUST redirect stdout/stderr to avoid SIGPIPE when the shell exits: `python3 -m http.server 8765 > /dev/null 2>&1 &`. Without redirection the server dies silently after the command finishes.
- File search: when looking for user files, do NOT scan the whole filesystem. Search under /var/minis/ first (workspace/attachments/shared for the current session, mounts/* for user-provided external folders). Only widen the scope if the file is clearly not under /var/minis/.

Tool call style:
- Default: do not narrate routine, low-risk tool calls — just call the tool directly.
- Narrate only when it helps: multi-step work, complex problems, sensitive actions, or when the user explicitly asks.
- Keep narration brief and value-dense; avoid repeating obvious steps.
- When a tool exists for an action, use it directly instead of explaining what you plan to do or asking the user to confirm.
- Use reasonable defaults and contextual inference to fill in missing details (e.g. 'tonight' means today, 'remind me' implies creating a reminder immediately). Only ask for clarification when genuinely ambiguous.

Tone and style:
- Reply in the language that best matches the user's input. Only switch languages when the user explicitly asks.
- Be concise. Prefer action over explanation — when the user asks for something that can be done via shell, do it directly.

Android-only tools (android-* CLIs):
CLI tools at /usr/local/bin with the `android-` prefix give you access to Android framework capabilities and on-device control. Invoke them from shell_execute like any other binary — they are already on PATH. Each tool prints JSON (or a short human-readable line) and supports --help for full usage. Tools gated by Shizuku or AccessibilityService return permission_denied when not granted — handle that gracefully and point the user at [Settings → Permissions](minis://settings/permissions).
- android-alarm — schedule alarms/timers in the system Clock app (`schedule <HH:MM> --label <L> [--repeat ONCE|DAILY|WEEKDAYS]`, `timer <seconds> --label <L>`, `open`). Alarms/timers are saved into the user's Android Clock — list/cancel are not supported (no system query API); tell the user to manage them from the Clock app's Alarms/Timers tabs (or `android-alarm open` / minis://views/alarm).
- android-calendar — read/write the device calendar (`list --start YYYY-MM-DD [--end ...] [--max N]`; `create --title <T> --start <ISO> [--end <ISO>] [--description <D>] [--location <L>] [--all-day]`).
- android-clipboard — `get | set <text> [--label L] | clear`.
- android-contacts — `list [--max N] | search <query> [--max N] | get <id> | delete <id>`. Requires READ_CONTACTS (delete also needs WRITE_CONTACTS).
- android-device — `[all|info|battery|storage]` — model, OS version, battery, storage (JSON).
- android-location — `current` for device location with reverse-geocoded address; `geocode <lat> <lon>` for reverse, `forward --address "<addr>"` for forward geocoding.
- android-notification — `send --title <T> [--body <B>] | clear | list [--max N]`. `send` triggers the system permission prompt on Android 13+ if POST_NOTIFICATIONS isn't granted. `list` reads active status-bar notifications and requires Notification Access (one-time setup; the first `list` call opens that page automatically).
- android-open <url> — open a URL via the system handler (http/https, tel:, mailto:, geo:, market:, intent:, etc.). Use this to open something immediately. To offer a tappable link instead, write a standard Markdown link with the URL directly — the app handles system URL schemes natively.
- android-photos — `list [--max N] | stats | near <lat> <lon> [--radius KM] [--max N]` — query the device photo library via MediaStore.
- android-player — audio playback sessions (`play <session> <path>`, `pause/resume/seek/stop/status <session>`, `list`).
- android-speak — device TTS (`<text> [--rate F] [--pitch F] [--volume F]`; `--stop | --status`).
- android-speech — microphone transcription (`listen [--language BCP47] [--max N] [--timeout SEC]`; `status`). Requires RECORD_AUDIO.
- android-weather <latitude> <longitude> — Open-Meteo forecast (current + hourly + daily). No API key needed.
- android-shizuku-cli — invoke privileged Android system APIs (package management, settings, system commands) via Shizuku when granted. Curated subcommands return structured JSON; for anything not covered, fall back to `android-shizuku-cli exec <any shell command>` which runs the command via `sh -c` with Shizuku privilege (same surface as `adb shell`). Run with no args (or --help) for the subcommand list.
- android-a11y-cli — drive system UI (read screen, tap, type, swipe, scroll) via the Android AccessibilityService when enabled. Run with no args (or --help) for the subcommand list.
- minis-open <url-or-path>: Opens a resource inside Minis without leaving the chat. Accepts http/https URLs (→ built-in WebKit preview) and chat-resource file paths under /var/minis/** (→ built-in file preview, routed by extension: images to the image viewer, .md to markdown preview, .html to HTML preview, .pdf/office docs to QuickLook, audio/video to the media player, else share sheet). Examples: minis-open https://example.com, minis-open /var/minis/workspace/report.md, minis-open /var/minis/attachments/chart.png. Prefer this over android-open for anything that can be previewed in-app so the user doesn't lose conversation context. Use android-open for non-web schemes (tel:, mailto:, geo:, intent:, etc.) or when the user explicitly wants the system handler.
- minis-sessions-cli: Manage chat sessions. `list` recent or by date range, `search --keywords` cross-session, `messages --id` to read, `send` to create/continue a session, `retry` to re-run, `status` to check, `open` to navigate the app UI. Run --help for full options.
- minis-model-use: Invoke other LLM models pre-configured by the user. Use `minis-model-use list` to see them (includes each model's modality capabilities like image_output, audio_output, etc.), `minis-model-use search <query>` to filter by name/provider. `minis-model-use run --model <id_or_name>` sends an OpenAI-compatible messages request; pass input via --input <json_file> or stdin, output goes to stdout or --output <path>. The OpenAI shape is the PRIMARY input for every model and modality; standard params are auto-converted to the underlying provider, so do not hand-write provider-native bodies as the primary input. For provider-specific extras the standard schema doesn't model (web-search plugins, image-to-image fields, TTS/video or other custom endpoints), escape hatches exist for OpenAI-compatible providers (they error or are ignored on Anthropic/Gemini models): `extra_body` (object merged verbatim into the request body), a custom `endpoint` path, and a top-level `passthrough` envelope for fully verbatim requests with RAW (unparsed) responses. Results may carry `warnings` (fields that were ignored/downgraded and why) and `applied_extras` (which extras actually took effect) — read them to self-correct. Run --help for the full contract before using these. Models may support multimodal output (image generation, TTS/audio, video) — check the modalities field in list output. For image_output models, pass generation params in the input JSON: top-level `n`/`size`/`quality`/`prompt` (OpenAI /images/generations style) or `generation_config.{aspect_ratio,image_size,number_of_images,person_generation}` (Gemini). Run with --help for full usage.
- minis-config: Read or change Minis settings programmatically. Run `minis-config --help` for subcommands and `minis-config topic-help <topic>` for details on a specific area. For array-valued fields (e.g. `models`, `groups`, `envvars`, `defaults.agentLoopEntries`) the `get` subcommand accepts `--filter <keywords>` (whitespace-AND, case-insensitive substring match against each element's JSON) and `--page <N> --page-size <N>` (default 20, max 100) — use these instead of dumping the full list when you only need a subset, and check the response's `pagination` / `agent_hint` fields for the next-page command. Every write triggers an in-app confirmation sheet and is logged to a revertable audit (1000-entry rolling log). After a successful change the response includes a `user_message` field — relay it (or paraphrase) so the user knows how to review or revert via Settings → Logs → Config Changes. If the call returns `permission_denied`, the user has disabled minis-config in [Settings → Permissions](minis://settings/permissions); relay that message and don't retry. You CAN add new providers and write their `apiKey` (literal string OR a `${'$'}${'$'}ENV_VAR` reference to copy from an env var at write time), but `get` never echoes API keys / OAuth tokens / env var values back — those reads return `permission_denied` by design. OAuth tokens and env var values are not settable via this tool; for an env var, point the user at [Set ENV_NAME](minis://settings/environments?create_key=ENV_NAME&create_value=) so they enter the value themselves.
Interactive terminal: minis://open_terminal opens a terminal for tasks that require interactive stdin (passwords, ssh, TUI apps like htop/vi). Write it as a Markdown link in your response — the app opens it when tapped. The optional init_command parameter pre-fills (NOT executes) a command; it MUST be fully percent-encoded (spaces → %20, & → %26, | → %7C, etc.). Only use this for genuinely interactive sessions — for everything else, use shell_execute. Examples: [Open Terminal](minis://open_terminal), [Login to SSH](minis://open_terminal?init_command=ssh%20user%40host).

Environment variables:
- Shell environment variables may contain sensitive API keys, tokens, or passwords. NEVER echo, print, cat, or otherwise output their values to stdout/stderr. Always reference them by variable name (e.g. ${'$'}API_KEY) inside scripts or commands — never inline the literal value.
- When a skill or task requires an environment variable that is not set, tell the user which variable is missing and provide a tappable deep link to create it: [Set ENV_NAME](minis://settings/environments?create_key=ENV_NAME&create_value=) — the user can tap it to open the Environment Variables page with the key pre-filled.
- Settings deep links: when you tell the user "go to Settings → X" or want to point them at a specific setting, prefer a Markdown link `[Label](minis://settings/<path>)` over plain prose. Available paths: providers (list), providers/<instanceId> (one provider), model-groups (incl. Agent Loop), model-groups/<groupId>, usage (token usage), skills, memory, storage, shared-folders (Shared Folders: /var/minis/{shared,skills,memory}), mount-external (Mount External Folders), logs, appearance, background, about, permissions, environments[?create_key=K&create_value=V[&create_note=N]], rootfs (also reachable as mirrors). Unknown paths fall back to Settings home, but prefer the exact path so users land where they want. These settings/action links are app deep links — render them as Markdown links in chat (same action-vs-resource rule as the minis:// section above: only /var/minis resource URLs may go to browser_use).
- To check if a variable is set, use `[ -n "${'$'}VAR" ] && echo 'set' || echo 'not set'`. NEVER use echo ${'$'}VAR, printenv VAR, or any command that would output the actual value into the conversation context.${memorySystemSection}
"""

        // Match iOS order exactly: skills → global memory → recent daily memory.
        // See ios/Agent/Chat/AIChatViewModel.swift:4375-4387. Each fragment is
        // appended only when non-null; absent fragments leave no separator.
        // T-skillscan: rescan disk before reading the fragment so a skill
        // that an earlier turn dropped via shell `git clone` (which bypasses
        // the file_write hook below) becomes visible on the very next user
        // turn instead of "after kill app". Cheap: loadAll is a SQLite
        // SELECT + listFiles, no network.
        skillRepository?.reloadFromDisk()
        val skillFragment = skillRepository?.skillPromptFragment(activeSessionId)
        // [T-mcp-integration-android] Re-read servers.json (the CLI / file
        // browser may have changed it out-of-band) then build the Top-20
        // enabled-MCP disclosure, injected right after the skills fragment.
        mcpRepository?.reloadFromDisk()
        val mcpFragment = mcpRepository?.mcpPromptFragment(activeSessionId)
        // Bundled platform integrations (semantic-memory / github-ops /
        // cloudflare-fullright-ops) with their current capability tier. Injected
        // right after the skills fragment so the model knows what it can do with
        // each platform before it tries anything.
        val integrationFragment = buildIntegrationStatus()
        // [T-memory-toggle-gates-injection-and-tools-android] Skip loading
        // GLOBAL.md + recent daily logs entirely when the user has turned
        // memory off for this session. Cheaper (no disk read) and — more
        // importantly — keeps the model from seeing stale persistent state
        // it can't tell the user how to manage. Skills and SOUL.md are
        // intentionally NOT gated by this toggle: skills are part of the
        // tool surface and SOUL.md is part of identity, both orthogonal
        // to the memory feature.
        val globalMemoryFragment = if (memoryOn) memoryRepository?.loadGlobalMemoryFragment() else null
        val dailyMemoryFragment = if (memoryOn) memoryRepository?.loadRecentDailyMemoryFragment() else null
        // [T6-rollup] Daily log size hint + MEMORY-ROLLUP.md injection.
        // When the largest daily log is large, suggest the agent run
        // memory_rollup to distill stable rules. MEMORY-ROLLUP.md (if it
        // exists) is injected as a compact alternative to raw logs.
        // [fix/send-prompt-bloat] Injection now goes through
        // MemoryRepository.loadRollupFragment() (tail-preferring byte cap)
        // instead of a verbatim readText() — the rollup grows monotonically
        // and previously inflated the send/retry prompt prefix unboundedly.
        val rollupSizeHint = if (memoryOn) memoryRepository?.dailyLogSizeSummary() else null
        val rollupBytes = if (memoryOn) memoryRepository?.largestDailyLogBytes() ?: 0L else 0L
        val rollupFragment = if (memoryOn) memoryRepository?.loadRollupFragment() else null

        return buildString {
            append(base)
            if (skillFragment != null) {
                append("\n\n")
                append(skillFragment)
            }
            if (integrationFragment != null) {
                append("\n\n")
                append(integrationFragment)
            }
            if (mcpFragment != null) {
                append("\n\n")
                append(mcpFragment)
            }
            if (globalMemoryFragment != null) {
                append("\n\n")
                append(globalMemoryFragment)
            }
            if (dailyMemoryFragment != null) {
                append("\n\n")
                append(dailyMemoryFragment)
            }
            // [T6-rollup] Inject MEMORY-ROLLUP.md (distilled stable rules)
            // as a compact memory fragment, plus a size hint to trigger
            // on-demand rollup when daily logs grow large.
            if (rollupFragment != null) {
                append("\n\nMemory rollup (MEMORY-ROLLUP.md — stable rules distilled from daily logs):\n")
                append(rollupFragment)
            }
            if (rollupSizeHint != null && rollupBytes >= 50_000L) {
                append("\n\nNote: Daily logs are large ($rollupSizeHint). ")
                append("memory_rollup selects the largest eligible old log that has not been distilled yet; ")
                append("call it to surface stable rules without waiting for the calendar to advance. ")
                append("It is idempotent and leaves source logs unchanged.")
            }
            // Runtime context goes last so the prefix above stays byte-stable
            // across requests within the same day. Keep ordering deterministic
            // (date → tz → lang → model count) — any reorder defeats the cache.
            append("\n\nRuntime context:\n")
            append("- Current date: ").append(dateStr).append(" (").append(tzId).append(")\n")
            append("- Device language: ").append(lang).append("\n")
            append("- minis-model-use models available: ").append(modelUseCount)
        }
    }

    // ─── Legacy tool execution methods (kept for compatibility) ───────────

    fun executeMemoryWrite(argsJson: String): MemoryTools.ToolResult {
        val repo = memoryRepository ?: return MemoryTools.ToolResult("Error: Memory not available", false)
        if (!_memoryEnabled.value) {
            return MemoryTools.ToolResult(
                "Memory writes are disabled for this session. Reads are still available. The user can re-enable writes via the /memory slash command.",
                false,
            )
        }
        val result = MemoryTools.executeMemoryWrite(argsJson, repo)
        val content = try {
            JSONObject(argsJson).optString("content", "")
        } catch (_: Exception) { "" }
        _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
            title = result.toolTitle,
            isWrite = true,
            preview = content.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: "",
            output = result.output,
            writtenContent = content,
        )
        return result
    }

    fun executeMemoryGet(argsJson: String): MemoryTools.ToolResult {
        val repo = memoryRepository ?: return MemoryTools.ToolResult("Error: Memory not available", false)
        val result = MemoryTools.executeMemoryGet(argsJson, repo)
        val keywords = try {
            JSONObject(argsJson).optString("keywords", "")
        } catch (_: Exception) { "" }
        _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
            title = result.toolTitle,
            isWrite = false,
            preview = if (keywords.isNotBlank()) "Search: $keywords" else result.output.take(100),
            output = result.output,
            keywords = keywords,
        )
        return result
    }

    suspend fun executeBrowserUse(argsJson: String): BrowserToolResult {
        val input = BrowserActionInput.parse(argsJson)
            ?: return BrowserToolResult(text = "Error: Invalid browser_use input. Required: 'action' parameter.", success = false)

        return try {
            val result = browserTabPool.execute(input)
            BrowserToolResult(
                text = result.text,
                success = result.success,
                base64Image = result.base64Image,
                imageFilePath = result.imageFilePath,
                pageURL = result.pageURL,
            )
        } catch (e: Exception) {
            BrowserToolResult(text = "Error: ${e.message}", success = false)
        }
    }

    data class BrowserToolResult(
        val text: String,
        val success: Boolean,
        val base64Image: String? = null,
        val imageFilePath: String? = null,
        val pageURL: String? = null,
    )

    // ─── Misc Helpers ────────────────────────────────────────────────────

    /**
     * T209: resize image bytes for the LLM inference payload only — the
     * full-resolution original is preserved on disk (mediaStore + uploads
     * dir) so chat history fullscreen view, agent shell `cat`, and
     * `read_image` all see the user's original picture, matching iOS.
     *
     * Returns null when the source already fits within [maxEdge] (caller
     * should fall back to [rawBytes]) or on any decode/compress failure.
     */
    private fun resizeImageBytes(
        rawBytes: ByteArray,
        mimeType: String,
        maxEdge: Int = 2000,
    ): ByteArray? {
        return try {
            val original = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
            if (original.width <= maxEdge && original.height <= maxEdge) {
                original.recycle()
                return null
            }
            val scale = maxEdge.toFloat() / maxOf(original.width, original.height)
            val w = (original.width * scale).toInt()
            val h = (original.height * scale).toInt()
            val scaled = Bitmap.createScaledBitmap(original, w, h, true)
            val out = ByteArrayOutputStream()
            val format = if (mimeType.contains("png")) Bitmap.CompressFormat.PNG
            else Bitmap.CompressFormat.JPEG
            scaled.compress(format, 85, out)
            if (scaled !== original) scaled.recycle()
            original.recycle()
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Bundle of everything derived from a user-message's input attachments:
     * the resized in-memory image bytes for the LLM, file:// URIs of the
     * persisted copies (for stable rendering across app restarts), the
     * filenames in original attachment order (images first, then non-image
     * files — matches the rendering convention in UserAttachmentList), and
     * the mediaRef JSON parts that need to be embedded in parts_json so the
     * attachments survive a session reload (T128).
     */
    private data class PreparedAttachments(
        val imageParts: List<LLMMessage.ImagePart>,
        val imageUris: List<Uri>,
        val attachmentNames: List<String>,
        val mediaRefPartsJson: List<String>,
        // T132: iOS-parity additions so the model sees the attachment as
        // a real file in the agent's sandbox (read_image / shell_execute can
        // open these paths).
        //   imageUploadPaths: one /var/minis/attachments/uploads/<safe> per
        //     inlined image, in the same order as `imageParts`.
        //   attachedFilesXml:  null when no attachments, otherwise the
        //     <user-attached-files> XML block iOS appends to the user turn.
        val imageUploadPaths: List<String>,
        val attachedFilesXml: String?,
        // T150: file:// URIs of persisted non-image attachments, in the same
        // order as the non-image suffix of `attachmentNames`. Carried into
        // ChatMessage so the user-bubble file chip can route a tap directly
        // to FilePreviewScreen without re-resolving by filename.
        val nonImageUris: List<Uri>,
    )

    /**
     * Resize each image attachment, copy the bytes into MediaStore (private
     * filesDir/media/<date>/<sessionId>/<id>.<ext>), and return both the
     * in-memory bytes (for the LLM) and a stable file:// URI + mediaRef JSON
     * part (for persistence + reload). T150: non-image attachments take the
     * same persistence + uploadsHostDir path so they survive session reload
     * and remain visible to the agent's shell tools — but their content is
     * NOT inlined into the LLM payload (parity with iOS processAttachments,
     * AIChatViewModel.swift L1552-1645).
     */
    private fun prepareUserAttachments(
        attachments: List<InputAttachment>,
        sessionId: String,
    ): PreparedAttachments {
        val imageParts = mutableListOf<LLMMessage.ImagePart>()
        val imageUris = mutableListOf<Uri>()
        val imageNames = mutableListOf<String>()
        val nonImageNames = mutableListOf<String>()
        val nonImageUris = mutableListOf<Uri>()
        // T150: separate buffers so the persisted mediaRefPartsJson is
        // image-first, matching the on-screen UserAttachmentList ordering
        // and `attachmentNames = imageNames + nonImageNames`. On restore,
        // `loadSessionMessages` walks parts_json in array order — keeping
        // the persisted order image-first means restoredAttachmentNames
        // and restoredAttachmentUris also come out image-first/non-image-suffix.
        val imageMediaRefPartsJson = mutableListOf<String>()
        val nonImageMediaRefPartsJson = mutableListOf<String>()
        val imageUploadPaths = mutableListOf<String>()
        // T132: also write the resized bytes into the session's iSH-bound
        // attachments dir (filesDir/minis-sessions/<sid>/attachments/uploads/),
        // which is mounted at /var/minis/attachments/ inside iSH. This makes
        // the same image accessible to the agent via shell tools (read_image
        // / cat / file) and matches the iOS uploads-directory convention.
        val uploadsHostDir = java.io.File(
            context.filesDir,
            "minis-sessions/$sessionId/attachments/uploads",
        ).apply { mkdirs() }
        // Metadata captured per attachment for the <user-attached-files> XML.
        data class UploadMeta(val linuxPath: String, val size: Long, val modifiedIso: String)
        val metas = mutableListOf<UploadMeta>()
        val nowMs = System.currentTimeMillis()
        val isoFormatter = java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            java.util.Locale.US,
        ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val nowStr = isoFormatter.format(java.util.Date(nowMs))

        for (attachment in attachments) {
            if (attachment.isImage) {
                // T209: read the original image bytes once and reuse them
                // for storage + uploads dir; only the LLM inference payload
                // gets the resized copy. Pre-T209 the resized JPEG was used
                // for all three, so chat history fullscreen view and agent
                // shell tools (read_image / cat) saw a 1024px JPEG instead
                // of the user's original picture. Matches iOS canonical
                // (AIChatViewModel.swift L1595-1617).
                val rawBytes = try {
                    context.contentResolver.openInputStream(attachment.uri)?.use { it.readBytes() }
                } catch (e: Exception) {
                    Log.w(TAG, "image read failed for ${attachment.fileName}: ${e.message}")
                    null
                } ?: continue
                val ref = try {
                    mediaStore.saveMedia(
                        data = rawBytes,
                        mimeType = attachment.mimeType,
                        sessionId = sessionId,
                        originalFileName = attachment.fileName,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist image attachment ${attachment.fileName}", e)
                    continue
                }
                // Resize only for the LLM payload — token-efficient and a
                // close-enough sketch of the picture for the model. Falls
                // back to raw bytes if the source is already small or the
                // decode/compress step fails.
                val inferenceBytes = resizeImageBytes(rawBytes, attachment.mimeType, maxEdge = 2000)
                    ?: rawBytes

                // Mirror ORIGINAL bytes into the iSH uploads dir under a
                // unique safe name so agent shell tools see the full-res
                // image. Don't fail the send if this write fails —
                // image_url in the request still carries (resized) bytes;
                // the model just won't be able to ask the agent to re-read
                // the same file from shell.
                //
                // Done BEFORE ImagePart construction so the linuxPath is
                // attached to the part — request-level image budgeting
                // uses it to emit a re-fetchable text placeholder when
                // the cumulative payload would exceed the per-request cap.
                val safeName = uniqueUploadFileName(uploadsHostDir, attachment.fileName)
                val dest = java.io.File(uploadsHostDir, safeName)
                val uploadOk = try { dest.writeBytes(rawBytes); true } catch (e: Exception) {
                    Log.w(TAG, "uploads write failed for ${attachment.fileName}: ${e.message}")
                    false
                }
                val linuxPath = if (uploadOk) "/var/minis/attachments/uploads/$safeName" else null
                if (linuxPath != null) {
                    imageUploadPaths.add(linuxPath)
                    metas.add(UploadMeta(linuxPath = linuxPath, size = rawBytes.size.toLong(), modifiedIso = nowStr))
                }

                imageParts.add(LLMMessage.ImagePart(inferenceBytes, attachment.mimeType, linuxPath = linuxPath))
                val savedFile = java.io.File(mediaStore.mediaBaseDir, ref.relativePath)
                imageUris.add(Uri.fromFile(savedFile))
                imageNames.add(attachment.fileName)
                imageMediaRefPartsJson.add(buildMediaRefPartJson(ref, linuxPath = linuxPath))
                continue
            }

            // T150: non-image attachment — stream-copy to disk (no
            // resize), persist a mediaRef so the chip survives session
            // reload (T151), and put a copy in the iSH uploads dir so
            // the agent can `cat` it via shell tools. iOS parity: the
            // file content is NOT inlined into the LLM payload — it
            // only appears in <user-attached-files> XML metadata, the
            // model fetches content on demand.
            //
            // CRITICAL: we deliberately do NOT `readBytes()` the
            // attachment here. A 400MB APK shared in by the user would
            // OOM on a low-RAM device (heap growth limit ~500MB on
            // Pixel 4a); the file's not even going into the LLM
            // payload, so loading the full byte array is pointless.
            // Stream-copy to the uploads dest first, then hand that
            // file to MediaStore.saveMediaStreamed so a second
            // streaming pass produces the durable mediaRef.
            nonImageNames.add(attachment.fileName)
            val safeName = uniqueUploadFileName(uploadsHostDir, attachment.fileName)
            val dest = java.io.File(uploadsHostDir, safeName)
            val uploadOk = try {
                context.contentResolver.openInputStream(attachment.uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } != null
            } catch (e: Exception) {
                Log.w(TAG, "non-image upload write failed for ${attachment.fileName}: ${e.message}")
                runCatching { dest.delete() }
                false
            }
            if (!uploadOk) continue

            val ref = try {
                dest.inputStream().use { input ->
                    mediaStore.saveMediaStreamed(
                        source = input,
                        mimeType = attachment.mimeType,
                        sessionId = sessionId,
                        originalFileName = attachment.fileName,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist non-image attachment ${attachment.fileName}", e)
                null
            }
            if (ref != null) {
                nonImageMediaRefPartsJson.add(buildMediaRefPartJson(ref))
                nonImageUris.add(Uri.fromFile(java.io.File(mediaStore.mediaBaseDir, ref.relativePath)))
            }

            val linuxPath = "/var/minis/attachments/uploads/$safeName"
            metas.add(UploadMeta(linuxPath = linuxPath, size = dest.length(), modifiedIso = nowStr))
        }

        // T-imgsize: byte-level budget enforcement. The resizeImageBytes pass
        // above caps *resolution* at 2000px but does nothing for the JPEG byte
        // size when the source is a 12-megapixel photo — Anthropic 413s once
        // cumulative inline image payload crosses ~30MB. ImageBudget walks
        // every image part, re-encodes oversize ones via the quality ladder,
        // and drops the tail when cumulative bytes would exceed 20MB. Result
        // is surfaced to the UI through _imageBudgetEvent so the Snackbar can
        // tell the user we touched their attachments.
        if (imageParts.isNotEmpty()) {
            val budgetResult = ImageBudget.applyMessageBudget(imageParts.map { it.data })
            // budgetResult.keptBytes.size <= imageParts.size; tail-drop the
            // parallel image-only lists symmetrically. Re-encoded bytes always
            // come out as JPEG so flip the mimeType on any part whose bytes
            // changed size (cheap proxy — never a false positive that hurts
            // semantics because the byte stream itself is the JPEG header).
            val newImageParts = budgetResult.keptBytes.mapIndexed { idx, kept ->
                val orig = imageParts[idx]
                if (kept === orig.data) orig
                else LLMMessage.ImagePart(kept, "image/jpeg", linuxPath = orig.linuxPath)
            }
            val newSize = newImageParts.size
            imageParts.clear()
            imageParts.addAll(newImageParts)
            while (imageUris.size > newSize) imageUris.removeAt(imageUris.size - 1)
            while (imageNames.size > newSize) imageNames.removeAt(imageNames.size - 1)
            while (imageMediaRefPartsJson.size > newSize) imageMediaRefPartsJson.removeAt(imageMediaRefPartsJson.size - 1)
            while (imageUploadPaths.size > newSize) imageUploadPaths.removeAt(imageUploadPaths.size - 1)
            if (budgetResult.mutated) {
                AppLogger.info(
                    TAG,
                    "[ImageBudget] compose: in=${budgetResult.keptBytes.size + budgetResult.droppedCount} kept=${budgetResult.keptBytes.size} compressed=${budgetResult.compressedCount} dropped=${budgetResult.droppedCount} totalBytes=${budgetResult.totalBytes}",
                )
                _imageBudgetEvent.tryEmit(budgetResult)
            }
        }

        // Build the <user-attached-files> XML block (iOS parity). One <file>
        // per attachment (image and non-image) that successfully landed in
        // the iSH uploads dir — gives the model a metadata-only inventory
        // it can resolve via shell tools when content is needed.
        val xml = if (metas.isEmpty()) null else buildString {
            append("<user-attached-files>\n")
            for (m in metas) {
                val urlPath = m.linuxPath.removePrefix("/var/minis/")
                append("  <file path=\"")
                append(m.linuxPath)
                append("\" url=\"minis://")
                append(urlPath)
                append("\" size=\"")
                append(m.size)
                append("\" modified=\"")
                append(m.modifiedIso)
                append("\" />\n")
            }
            append("</user-attached-files>")
        }

        // Order matches UserAttachmentList convention: images first, then files.
        return PreparedAttachments(
            imageParts = imageParts,
            imageUris = imageUris,
            attachmentNames = imageNames + nonImageNames,
            mediaRefPartsJson = imageMediaRefPartsJson + nonImageMediaRefPartsJson,
            imageUploadPaths = imageUploadPaths,
            attachedFilesXml = xml,
            nonImageUris = nonImageUris,
        )
    }

    /**
     * Compute a unique-on-disk filename inside [dir] for [original]. Strips
     * path separators, falls back to "image.jpg" if the input is empty, and
     * appends `_N` before the extension when the target already exists.
     */
    private fun uniqueUploadFileName(dir: java.io.File, original: String): String {
        val raw = original.substringAfterLast('/').substringAfterLast('\\').ifBlank { "image.jpg" }
        // Sanitize control / path-hostile chars without going overboard;
        // safe POSIX path chars are kept.
        val sanitized = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (!java.io.File(dir, sanitized).exists()) return sanitized
        val dot = sanitized.lastIndexOf('.')
        val base = if (dot > 0) sanitized.substring(0, dot) else sanitized
        val ext = if (dot > 0) sanitized.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "${base}_$n$ext"
            if (!java.io.File(dir, candidate).exists()) return candidate
            n++
        }
    }

    /** LLM-based title + category generation, mirrors iOS generateSessionTitleIfNeeded(). */
    private var titleGenerationAttempts = 0
    private var titleGenerationInFlight = false
    private val TITLE_MAX_ATTEMPTS = 3

    private fun generateSessionTitleIfNeeded() {
        // [T-android-titlegen-diag-logging] Unified "TitleGen" trail across
        // every path of this function — XIN 40454 reported sessions silently
        // staying "New Chat" and the failure paths were under-logged.
        // Logging only; no logic change.
        AppLogger.info(
            "TitleGen",
            "enter session=${realSessionId.ifEmpty { sessionId }} attempts=$titleGenerationAttempts/$TITLE_MAX_ATTEMPTS " +
                "inFlight=$titleGenerationInFlight currentTitle='${_sessionTitle.value.take(200)}'",
        )
        if (titleGenerationInFlight || titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
            AppLogger.info(
                "TitleGen",
                "skip guard=${if (titleGenerationInFlight) "inFlight" else "max-attempts ($titleGenerationAttempts/$TITLE_MAX_ATTEMPTS)"}",
            )
            return
        }
        // Skip if title already set (not "New Chat")
        if (_sessionTitle.value != "New Chat" && _sessionTitle.value.isNotEmpty()) {
            AppLogger.info("TitleGen", "skip guard=title-already-set title='${_sessionTitle.value.take(200)}'")
            return
        }
        // Prefer a dedicated sub-model (cheap, non-OAuth) — mirrors iOS resolveSubEntry.
        // Falls back to the primary provider if no sub-group is configured.
        // [T-title-gen-fallback-first-message-android] If no provider can be
        // resolved at all, the session would silently stay "New Chat". Log the
        // reason and fall back to the first user message as the title.
        val subProvider = resolveTitleProvider()
        if (subProvider == null) {
            AppLogger.info("TitleGen", "resolveTitleProvider=null — falling back to currentProvider")
        }
        val provider = subProvider ?: currentProvider
        if (provider == null) {
            AppLogger.warning("TitleGen", "no provider available (sub + current both null) — fallback-to-first-message path")
            viewModelScope.launch(Dispatchers.IO) {
                applyFallbackTitleFromFirstMessage("no provider available")
            }
            return
        }

        titleGenerationInFlight = true
        titleGenerationAttempts++

        // [T-titlegen-context-first-last-pair] Build the summary from the first
        // user + first assistant message, and — when the session has more than
        // one user turn — also the last user + last assistant message, each
        // truncated to 200 chars. This lets the title adapt when the topic
        // shifts later in a long session, instead of only seeing the opener.
        val msgs = _messages.value
        val userMessages = msgs.filter { it.role == "user" }
        val firstUser = userMessages.firstOrNull()
        if (firstUser == null) {
            AppLogger.warning("TitleGen", "skip guard=no-user-message (nothing to summarize)")
            titleGenerationInFlight = false
            return
        }
        val userText = firstUser.content.take(200)
        // First/last assistant *text* message — skip tool-only capsules whose
        // content is blank so the summary carries real assistant prose.
        val assistantTextMessages = msgs.filter { it.role == "assistant" && it.content.isNotBlank() }
        val firstAssistantText = assistantTextMessages.firstOrNull()?.content?.take(200) ?: ""
        // Only append the last pair when there is more than one user turn (i.e.
        // the first and last user messages differ) — avoids duplicating the
        // opener when the session is a single exchange.
        val hasMultipleUserTurns = userMessages.size > 1
        val lastUserText = if (hasMultipleUserTurns) userMessages.lastOrNull()?.content?.take(200) ?: "" else ""
        val lastAssistantText = if (hasMultipleUserTurns) assistantTextMessages.lastOrNull()?.content?.take(200) ?: "" else ""

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Mirror iOS callSubModelForTitle prompt shape: short cacheable system
                // prompt + user-message payload. Using the exact iOS strings keeps the
                // Anthropic prompt cache warm across title-gen calls.
                val prompt = buildString {
                    append("Based on the following conversation, generate a short title (max 6 words) that captures the topic. ")
                    append("Also pick a task category from: code, writing, research, analysis, creative, chat, math, translation, health, finance, travel, education, design, productivity, support, other.\n\n")
                    append("You MUST respond with valid JSON only. Example:\n")
                    append("{\"title\": \"Debug Login Page Issue\", \"category\": \"code\"}\n\n")
                    append("Conversation:\n")
                    append("User: $userText\n")
                    if (firstAssistantText.isNotEmpty()) append("Assistant: $firstAssistantText\n")
                    if (lastUserText.isNotEmpty()) append("User: $lastUserText\n")
                    if (lastAssistantText.isNotEmpty()) append("Assistant: $lastAssistantText\n")
                    append(titleLanguageDirective())
                }
                // [T-android-titlegen-systemprompt-unify] Shared with the manual
                // Regenerate path (SessionListViewModel.regenerateTitle) via the
                // single TITLE_GEN_SYSTEM_PROMPT constant so the two never drift.
                // Passed bare: for OAuth Anthropic instances,
                // AnthropicProvider.resolveSystemPrompt force-prepends the Claude
                // Code prefix block at the provider layer (and strips a
                // caller-supplied one), so no caller-side prepend is needed — the
                // previous manual prefix branch here was redundant.
                val effectiveSystemPrompt = TITLE_GEN_SYSTEM_PROMPT

                AppLogger.info(
                    "TitleGen",
                    "dispatch attempt=$titleGenerationAttempts provider=${provider.javaClass.simpleName} model=${provider.model.id}",
                )
                // [T-android-titlegen-reasoning] Match iOS callSubModelForTitle:
                // explicitly disable thinking (thinkingLevel = OFF). The provider
                // layer's injectThinkingParams honors OFF — e.g. DeepSeek V4 gets
                // an explicit {"thinking":{"type":"disabled"}}, o-series/gpt-5
                // omit reasoning_effort, Anthropic sends no thinking block — so a
                // reasoning sub-model doesn't burn the whole budget on hidden
                // thinking and return empty text. As a belt-and-suspenders for
                // models where OFF is still a no-op (e.g. Qwen3, which thinks by
                // default), keep the T334 budget bump so it can finish thinking
                // and still emit the JSON. Unified with regenerateTitle's ladder.
                val titleMaxTokens = if (provider.model.supportsReasoning == true) 2048 else 100
                val titleInstance = provider.instanceContext ?: run {
                    // Cannot dispatch a provider-created title call without an
                    // instance — surface as a typed error (never in-process).
                    AppLogger.warning("TitleGen", "outcome=no-instance-context attempt=$titleGenerationAttempts")
                    if (titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
                        applyFallbackTitleFromFirstMessage("no provider instance context")
                    }
                    return@launch
                }
                val titleResult = ProviderExecutionGateway.send(
                    context = context,
                    instance = titleInstance,
                    model = provider.model,
                    messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
                    systemPrompt = effectiveSystemPrompt,
                    maxTokens = titleMaxTokens,
                    // Mirror iOS AIChatViewModel.swift:11244 — pass null so
                    // gpt-5.x family doesn't reject the request (only
                    // temperature=1 allowed there). buildRequestBody omits
                    // the field when null.
                    temperature = null,
                    thinkingLevel = ThinkingLevel.OFF,
                )
                val response = when (titleResult) {
                    is ProviderExecutionGateway.SendResult.Success -> titleResult.response
                    is ProviderExecutionGateway.SendResult.RemoteFailure -> {
                        AppLogger.warning(
                            "TitleGen",
                            "outcome=remote-failure attempt=$titleGenerationAttempts (${titleResult.code}): ${titleResult.message.take(200)}",
                        )
                        if (titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
                            applyFallbackTitleFromFirstMessage("remote failure ${titleResult.code}")
                        }
                        return@launch
                    }
                    is ProviderExecutionGateway.SendResult.Unavailable -> {
                        AppLogger.warning(
                            "TitleGen",
                            "outcome=unavailable attempt=$titleGenerationAttempts: ${titleResult.reason}",
                        )
                        if (titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
                            applyFallbackTitleFromFirstMessage("title service unavailable: ${titleResult.reason}")
                        }
                        return@launch
                    }
                }

                AppLogger.info(
                    "TitleGen",
                    "response stopReason=${response.stopReason} textLen=${response.text.length} " +
                        "raw='${response.text.take(200).replace("\n", "\\n")}'",
                )
                val (title, category) = parseTitleResponse(response.text)
                if (title.isNotEmpty()) {
                    val sid = realSessionId.ifEmpty { sessionId }
                    chatRepository.updateSessionTitleAndCategory(sid, title, category)
                    withContext(Dispatchers.Main) {
                        _sessionTitle.value = title
                        _sessionCategory.value = category
                    }
                    AppLogger.info("TitleGen", "outcome=set title='$title' category='$category'")
                } else {
                    // [T-title-gen-fallback-first-message-android] The request
                    // succeeded but yielded no usable title — empty body or a
                    // response parseTitleResponse couldn't extract a title from
                    // (e.g. reasoning model that spent its whole budget thinking,
                    // or non-JSON output). Previously this was silent and left
                    // the session as "New Chat". Log the real cause and, on the
                    // final attempt, fall back to the first user message.
                    AppLogger.warning(
                        "TitleGen",
                        "outcome=no-title attempt=$titleGenerationAttempts/$TITLE_MAX_ATTEMPTS " +
                            "(empty / unparseable response) stopReason=${response.stopReason} " +
                            "textLen=${response.text.length}",
                    )
                    if (titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
                        AppLogger.warning("TitleGen", "outcome=gave-up ($titleGenerationAttempts/$TITLE_MAX_ATTEMPTS) — applying first-message fallback title")
                        applyFallbackTitleFromFirstMessage("empty/unparseable title response")
                    }
                }
            } catch (e: Exception) {
                // [T-title-gen-fallback-first-message-android] Request error /
                // timeout / provider failure. Log the concrete cause (was
                // already logged, kept) and fall back to the first user message
                // on the final attempt.
                AppLogger.warning(
                    "TitleGen",
                    "outcome=exception attempt=$titleGenerationAttempts/$TITLE_MAX_ATTEMPTS " +
                        "${e.javaClass.simpleName}: ${e.message?.take(200)}",
                )
                if (titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
                    AppLogger.warning("TitleGen", "outcome=gave-up ($titleGenerationAttempts/$TITLE_MAX_ATTEMPTS) — applying first-message fallback title")
                    applyFallbackTitleFromFirstMessage("request failed: ${e.message?.take(200)}")
                }
            } finally {
                titleGenerationInFlight = false
            }
        }
    }

    /**
     * [T-title-gen-fallback-first-message-android] Set the session title to a
     * cleaned-up truncation of the first user message when LLM title generation
     * fails (request error / timeout / empty / parse failure / model
     * unavailable). Strips the trailing `<user-attached-files>` XML block,
     * collapses whitespace/newlines to single spaces, and clamps to ~30 chars
     * with an ellipsis — matching the title norm (single-line, short). No-op
     * (logged) when there's no usable first-message text.
     */
    private suspend fun applyFallbackTitleFromFirstMessage(reason: String) {
        val raw = _messages.value.firstOrNull { it.role == "user" }?.content
        var text = raw ?: ""
        // Drop the <user-attached-files> XML the composer appends so the title
        // reflects what the user actually typed, not the attachment manifest.
        val startIdx = text.indexOf("<user-attached-files>")
        if (startIdx >= 0) {
            val endTag = "</user-attached-files>"
            val endIdx = text.indexOf(endTag, startIdx)
            text = if (endIdx >= 0) {
                text.substring(0, startIdx) + text.substring(endIdx + endTag.length)
            } else {
                text.substring(0, startIdx)
            }
        }
        // Collapse all whitespace (incl. newlines) to single spaces, trim.
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) {
            Log.w(TAG, "Title fallback skipped ($reason): first user message has no text")
            return
        }
        val fallbackTitle = if (cleaned.length > 30) cleaned.take(30).trimEnd() + "…" else cleaned
        val sid = realSessionId.ifEmpty { sessionId }
        chatRepository.updateSessionTitle(sid, fallbackTitle)
        withContext(Dispatchers.Main) {
            _sessionTitle.value = fallbackTitle
        }
        Log.i(TAG, "Title fallback applied ($reason): '$fallbackTitle'")
    }

    /**
     * Resolve the provider used for title generation. Mirrors iOS resolveSubEntry:
     * prefer an explicitly configured sub-model (cheap, non-OAuth) so title
     * generation doesn't hit the expensive primary model or fail under the
     * OAuth-Anthropic Claude-Code-only gate. Falls back to null if no sub is
     * configured — caller uses the primary provider then.
     */
    private fun resolveTitleProvider(): LLMProvider? {
        // [T-disabled-provider-via-group-android] Resolve the dedicated
        // title-generation sub-model (first enabled member of defaultSubGroupId).
        // [T-android-regenerate-title-submodel] Shares
        // ProviderRepository.resolveTitleSubEntry with the manual Regenerate
        // path so both prefer the same sub-model. Silently degrades (caller
        // falls back to the primary provider) when no sub-group is configured or
        // every member sits behind a disabled provider.
        val entry = providerRepository.resolveTitleSubEntry() ?: return null
        val instance = providerRepository.instance(entry.providerInstanceId) ?: return null
        val apiKey = providerRepository.loadApiKey(instance.id) ?: return null

        return ProviderFactory.create(instance, apiKey, entry.model, context)
    }

    /** Parse LLM response for title/category JSON. Multiple fallback strategies. */
    /**
     * [T-android-overlay-reply-status-34599] Pull the most recent
     * assistant text out of `_messages` and hand it to
     * [SessionActivityTracker.publishLastReply]. The tracker truncates
     * to a fixed-width excerpt and pairs it with [sessionId] so the
     * floating overlay can render a "tap to open this chat" capsule
     * after the stream completes. No-op when no assistant message has
     * content yet (e.g. fail during the very first turn).
     */
    private fun publishOverlayReplyExcerpt(sessionId: String) {
        val snapshot = _messages.value
        val text = snapshot.asReversed().firstOrNull { msg ->
            msg.role == "assistant" && msg.content.isNotBlank()
        }?.content
        SessionActivityTracker.publishLastReply(sessionId, text)
    }

    fun cancelStream() {
        AppLogger.info(TAG_STREAM, "cancelStream invoked _isStreaming=false (sid=$activeSessionId)")
        streamJob?.cancel()
        _isStreaming.value = false
        // T7-A: 观察 —— 用户取消（T5 UserCancelled 语义，进入收尾）
        traceObserver.t7State(
            traceObserver.t7ObservedPhase ?: ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.CALLING_MODEL),
            ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.FINALIZING),
            "UserCancelled",
        )
        // T7-D: 旁路验证 —— 用户取消
        traceObserver.t7Reduce(AgentRunEvent.UserCancelled("user_stop"))
        // T-streaming-side-channel: flush any in-flight delta back into the
        // canonical message so the rest of cancelStream's cleanup (publish
        // overlay excerpt, persist, retry-eligible state) sees the real
        // content rather than a stale pre-stream snapshot.
        flushAllStreamingDeltas()
        // T171: drop activity tracker immediately, don't wait for the
        // streamJob's finally block. When OkHttp is wedged in a blocking
        // execute() call.cancel() may unwind eventually but the finally
        // doesn't run until then — meanwhile RPC chat.session.status would
        // still report isRunning=true and the user thinks the stop button
        // did nothing.
        // [T-android-overlay-reply-status-34599] User-initiated cancel:
        // surface any reply we already streamed + tag outcome as
        // Cancelled so the overlay's glyph reflects the actual end
        // state (⊘) instead of carrying over the prior tool's outcome.
        publishOverlayReplyExcerpt(activeSessionId)
        SessionActivityTracker.clearToolRunning(com.openminis.app.service.ToolOutcome.Cancelled)
        SessionActivityTracker.setInactive(activeSessionId)
        if (isDraft && realSessionId.isNotEmpty() && activeSessionId != sessionId) {
            SessionActivityTracker.setInactive(sessionId)
        }
        // Stop whichever shell the agent loop is actually dispatching against.
        // Before `ensureSession()` that is the draft id; after, the real id.
        // Stopping the wrong one leaves a runaway yt-dlp/ffmpeg alive.
        ExecutionCoordinator.stopCurrentCommand(activeSessionId)
        if (isDraft && realSessionId.isNotEmpty() && activeSessionId != sessionId) {
            // Mid-turn rename: sweep any lingering draft shell too.
            ExecutionCoordinator.stopCurrentCommand(sessionId)
        }
        handleUserCancelledCleanup()

        // T189: iOS parity (AIChatViewModel.swift L2592-2610). If the user
        // enqueued prompts during the cancelled stream, auto-resume the drain
        // instead of leaving them stuck as dashed bubbles waiting for a manual
        // long-press retry.
        val pending = _promptQueue.value
        if (pending.isNotEmpty()) {
            AppLogger.info(TAG_STREAM, "cancel — ${pending.size} queued prompt(s) remain, restarting drain")
            resumeQueueAfterCancel()
        }
    }

    /**
     * T189: spawn a fresh agent loop to drain whatever the user queued during
     * the cancelled stream. 200ms delay matches iOS resumeQueueAfterCancel
     * (Task.sleep(200_000_000)) — gives the cancelled streamJob's finally block
     * room to release the concurrency slot + write back state. Race-guards on
     * entry: empty queue (user withdrew) or already streaming (user manually
     * retried) → noop return.
     *
     * Provider / systemPrompt / fallback resolution mirrors [sendMessage]
     * verbatim, so a queued prompt drain after cancel uses the same plumbing
     * as a fresh send.
     */
    private fun resumeQueueAfterCancel() {
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(200)
            if (_promptQueue.value.isEmpty()) return@launch
            if (_isStreaming.value) return@launch
            // [T-android-compact-queued-drain] Defer while a compact is in
            // flight — draining would mutate agentHistory mid-marker-write.
            // Safe to just return: every SUCCESSFUL compact re-kicks this
            // function from its own tail, so a deferred drain is never lost
            // (and a failed compact leaves the queue pending by design).
            if (_isCompacting.value) {
                AppLogger.info(TAG, "resumeQueueAfterCancel: compact in flight — deferring to its completion kick")
                return@launch
            }

            val initialProvider = currentProvider
            if (initialProvider == null) {
                AppLogger.warning(TAG, "resumeQueueAfterCancel: no provider, dropping queue")
                _promptQueue.value = emptyList()
                _messages.value = _messages.value.filterNot { it.isQueued }
                return@launch
            }
            var provider: LLMProvider = initialProvider

            val baseSystemPrompt = buildSystemPrompt()
            val systemPrompt = baseSystemPrompt

            // T145: claim the streaming flag synchronously before launching
            // the streamJob so a concurrent send/retry tap is rejected by the
            // entry guard. Mirrors sendMessage discipline.
            AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel _isStreaming=true (sync, sid=$activeSessionId)")
            _isStreaming.value = true
            streamEpoch++
            _canResume.value = false
            _error.value = null

            streamJob = launch(Dispatchers.IO) {
                AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel streamJob ENTER sid=$activeSessionId")
                try {
                    SessionConcurrencyManager.acquireSlot(activeSessionId)
                    AppLogger.debug(TAG_STREAM, "resumeQueueAfterCancel streamJob slot acquired")
                    SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })

                    val activeFallbackStrategy = run {
                        val groupId = _selectedGroupId.value
                        groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                            ?: com.openminis.app.data.model.FallbackStrategy.default
                    }
                    try {
                        AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel drainQueuedPrompts CALL")
                        drainQueuedPrompts(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            fallbackStrategy = activeFallbackStrategy,
                        )
                        AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel drainQueuedPrompts RETURN")
                    } catch (e: CancellationException) {
                        AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel drain CANCELLED")
                    } catch (e: Exception) {
                        AppLogger.error(TAG_STREAM, "resumeQueueAfterCancel drain EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                        Log.e(TAG, "Queued drain error (resumeQueueAfterCancel)", e)
                        reportAgentLoopError(e)
                    } finally {
                        AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel streamJob FINALLY enter")
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
                        AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel streamJob FINALLY exit")
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel streamJob CANCELLED waiting for slot")
                }
                // [T-android-stale-streamjob-clears-isstreaming] guard.
                if (streamJob === coroutineContext[Job]) {
                    AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel _isStreaming=false (about to set)")
                    _isStreaming.value = false
                } else {
                    AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel _isStreaming SKIPPED (stale job)")
                }
                AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel streamJob EXIT")
            }
        }
    }

    /**
     * After the user stops a streaming turn, reconcile UI + agentHistory so
     * the conversation is valid on the next API call and resumable via
     * [resume]. Mirrors iOS AIChatViewModel.handleUserCancelledCleanup
     * (Case 1: tool cancel, Case 2: text cancel).
     *
     *  - Case 1: any in-flight tool block is flipped to [ToolBlockStatus.CANCELLED]
     *    and a synthetic tool_result with [CANCELLED_MARKER] is persisted so
     *    tool_use/tool_result stays paired.
     *  - Case 2: if there was partial assistant text streamed (and no tool
     *    cancel), commit the partial text + a truncation `<system-reminder>`
     *    to agentHistory so the model knows the prior turn was cut short.
     *
     * Always sets [_canResume] = true when there is something to resume from.
     */
    private fun handleUserCancelledCleanup() {
        val msgs = _messages.value.toMutableList()
        val lastIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastIdx < 0) return
        var last = msgs[lastIdx]

        // T73: clear "Minis is thinking…" the moment the user taps Stop.
        // isAwaitingModelResponse is set true at runAgentLoop entry (≈ line
        // 2785) so the typing indicator shows during the initial request
        // gap before the first stream chunk. The cancel paths below didn't
        // reset it, so after Stop the indicator stayed live forever even
        // though the streamJob was already torn down. Reset before either
        // case runs so both tool-cancel and text-cancel paths benefit.
        //
        // [T-android-cancel-isstreaming] The per-message `isStreaming` flag
        // is the run-group's liveness source for a thinking block in flight
        // (run-group isRunning = "thinking && toolStatus==null && message
        // .isStreaming"). A cancel tears the stream down, so this message is
        // by definition no longer streaming — but the flag was never cleared
        // here, leaving the old "Thinking…" breadcrumb spinning after the
        // tool (whose own status DID converge to SUCCESS/CANCELLED) stopped.
        // Reset it unconditionally (NOT gated on isAwaitingModelResponse —
        // that flag flips false the moment the first thinking chunk lands,
        // so the gated reset alone left the thinking sticky for messages
        // that actually streamed content).
        if (last.isAwaitingModelResponse) {
            last = last.copy(isAwaitingModelResponse = false, isStreaming = false)
            msgs[lastIdx] = last
            _messages.value = msgs
        } else if (last.isStreaming) {
            last = last.copy(isStreaming = false)
            msgs[lastIdx] = last
            _messages.value = msgs
        }

        // Case 1: cancel during tool execution. Flip in-flight tool blocks to
        // CANCELLED and persist matching tool_result rows.
        val cancelledIds = mutableListOf<Pair<String, String>>() // (toolUseId, toolName)
        val updatedBlocks = last.toolBlocks.map { b ->
            val s = b.toolStatus
            if (s == ToolBlockStatus.STREAMING || s == ToolBlockStatus.PENDING || s == ToolBlockStatus.RUNNING) {
                if (b.kind == "tool_use") cancelledIds.add(b.id to b.toolName)
                b.copy(toolStatus = ToolBlockStatus.CANCELLED)
            } else b
        }
        val hadInflightTools = cancelledIds.isNotEmpty()
        if (hadInflightTools) {
            msgs[lastIdx] = last.copy(toolBlocks = updatedBlocks)
            _messages.value = msgs
            val parts = cancelledIds.map { (id, name) ->
                AgentContentPart.ToolResult(
                    id = id,
                    name = name,
                    content = CANCELLED_MARKER,
                    isError = true,
                )
            }
            viewModelScope.launch(Dispatchers.IO) {
                persistToolResultMessage(parts)
            }
            _canResume.value = true
            return
        }

        // Case 2: cancel during text streaming. If partial assistant text
        // exists and agentHistory does not already end with the assistant
        // turn we're on, commit the partial text + truncation marker so the
        // model sees an interrupted prior turn on the next call.
        val partialText = buildString {
            if (last.content.isNotEmpty()) append(last.content)
            for (b in last.toolBlocks) {
                if (b.kind == "text" && b.content.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(b.content)
                }
            }
        }
        val historyEndsWithAssistant =
            agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT

        // Case 0 (T-ios-stop-clear-thinking-and-partial — Android port):
        // Stop fired while still in the pre-first-chunk thinking gap (no
        // partial text, no tool_use emitted, no committed history for this
        // turn). The placeholder ChatMessage runAgentLoop pushed at L5248 is
        // not in the DB and would otherwise render as an empty "Minis" header
        // bubble with no body. Drop it so the UI snaps back to idle the
        // instant the user taps Stop. Mirrors the iOS #566/#569 boundary:
        // a candidate WITH real text or any emitted tool_use is kept (handled
        // by Case 1 / Case 2 below); a thinking-only placeholder is not.
        val hasAnyToolUse = last.toolBlocks.any { it.kind == "tool_use" }
        if (partialText.isEmpty() && !hasAnyToolUse && !historyEndsWithAssistant) {
            msgs.removeAt(lastIdx)
            _messages.value = msgs
            return
        }

        if (partialText.isNotEmpty() && !historyEndsWithAssistant) {
            val parts = listOf<AgentContentPart>(
                AgentContentPart.Text(partialText),
                AgentContentPart.Text(
                    "<system-reminder>The user stopped this response. Content may be incomplete.</system-reminder>"
                ),
            )
            agentHistory.add(
                LLMMessage(
                    role = LLMMessage.Role.ASSISTANT,
                    content = partialText,
                    contentParts = parts,
                )
            )
            viewModelScope.launch(Dispatchers.IO) {
                val partsJson = buildAssistantPartsJson(parts)
                chatRepository.appendMessage(activeSessionId, "assistant", partsJson)
            }
            _canResume.value = true
        } else if (historyEndsWithAssistant) {
            // Already committed (tool cancel path above handled or prior turn
            // wrote an assistant row). Still allow resume.
            _canResume.value = true
        }
    }

    /**
     * Build a JSON parts array matching the ChatRepository schema so a
     * committed interrupted-assistant turn round-trips across app restarts.
     * Only emits text parts — tool_use / tool_result paths are handled by
     * the existing persistence code in the agent loop.
     */
    private fun buildAssistantPartsJson(parts: List<AgentContentPart>): String =
        buildTextOnlyAssistantPartsJson(parts)

    /**
     * Resume an interrupted agent loop. Injects a `<system-reminder>` into
     * agentHistory so the model picks up where it left off, then re-enters
     * the agent loop in a fresh [streamJob]. Mirrors iOS
     * AIChatViewModel.resume().
     *
     * Safe to call only when [canResume] is true and [isStreaming] is false.
     * Clears [_canResume] on entry so repeated taps don't stack.
     */
    fun resume() {
        if (_isStreaming.value || !_canResume.value) return
        val provider = currentProvider ?: run {
            _error.value = "No provider configured"
            return
        }
        _canResume.value = false
        _error.value = null
        // [T-error-persist-android] resume() follows finalizeAtTurnLimit's
        // setInlineError (which persisted an error sticker on the last assistant
        // row). Clear it now so a successful resume doesn't merge-resurrect the
        // turn-limit banner on the next reload.
        clearPersistedLastAssistantError()
        AppLogger.info(TAG, "▶️ resume: continuing partial assistant message (no new header emitted)")
        // [T-android-tool-autoscroll] Start-of-turn snap. The thinking
        // placeholder is the only visible delta until the model's first
        // token, and the auto-follow tuple won't advance until content
        // streams — ChatScreen would otherwise leave the placeholder
        // behind the input bar.
        _forceScrollToBottom.tryEmit(Unit)

        // If history ends with assistant (Case 2: text-cancel committed a
        // partial assistant turn), append a continue reminder as a user
        // message. If it ends with user tool_result (Case 1), it's already
        // a valid starting point for the next API call — no reminder needed.
        val historyEndsWithAssistant =
            agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT
        if (historyEndsWithAssistant) {
            val reminder =
                "<system-reminder>The user stopped the previous response but now wants to continue. Pick up exactly where you left off.</system-reminder>"
            val parts = listOf<AgentContentPart>(AgentContentPart.Text(reminder))
            agentHistory.add(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = reminder,
                    contentParts = parts,
                )
            )
            viewModelScope.launch(Dispatchers.IO) {
                val partsJson = """[{"type":"text","value":${escapeJson(reminder)}}]"""
                chatRepository.appendMessage(activeSessionId, "user", partsJson)
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            val baseSystemPrompt = buildSystemPrompt()
            val systemPrompt = baseSystemPrompt

            AppLogger.info(TAG_STREAM, "resume _isStreaming=true (sid=$activeSessionId)")
            _isStreaming.value = true
            streamEpoch++
            streamJob = launch(Dispatchers.IO) {
                AppLogger.info(TAG_STREAM, "resume streamJob ENTER sid=$activeSessionId")
                try {
                    SessionConcurrencyManager.acquireSlot(activeSessionId)
                    AppLogger.debug(TAG_STREAM, "resume streamJob slot acquired")
                    SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })
                    val activeFallbackStrategy = run {
                        val groupId = _selectedGroupId.value
                        groupId?.let {
                            providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy
                        } ?: com.openminis.app.data.model.FallbackStrategy.default
                    }
                    val fallbackProviders = buildFallbackProviders(provider)
                    try {
                        AppLogger.info(TAG_STREAM, "resume runAgentLoop CALL")
                        runAgentLoop(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            fallbackProviders = fallbackProviders,
                            fallbackStrategy = activeFallbackStrategy,
                        )
                        AppLogger.info(TAG_STREAM, "resume runAgentLoop RETURN normal")
                        drainQueuedPrompts(provider, systemPrompt, activeFallbackStrategy)
                        AppLogger.info(TAG_STREAM, "resume drainQueuedPrompts RETURN")
                    } catch (e: CancellationException) {
                        AppLogger.info(TAG_STREAM, "resume runAgentLoop CANCELLED")
                        Log.d(TAG, "Agent loop cancelled (resume)")
                    } catch (e: Exception) {
                        AppLogger.error(TAG_STREAM, "resume runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                        Log.e(TAG, "Agent loop error (resume)", e)
                        reportAgentLoopError(e)
                    } finally {
                        AppLogger.info(TAG_STREAM, "resume streamJob FINALLY enter")
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
                        AppLogger.info(TAG_STREAM, "resume streamJob FINALLY exit")
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(TAG_STREAM, "resume streamJob CANCELLED waiting for slot")
                    Log.d(TAG, "Cancelled while waiting for concurrency slot (resume)")
                }
                // [T-android-stale-streamjob-clears-isstreaming] guard.
                if (streamJob === coroutineContext[Job]) {
                    AppLogger.info(TAG_STREAM, "resume _isStreaming=false (about to set)")
                    _isStreaming.value = false
                } else {
                    AppLogger.info(TAG_STREAM, "resume _isStreaming SKIPPED (stale job)")
                }
                AppLogger.info(TAG_STREAM, "resume streamJob EXIT")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // [T-chat-sysinfo-coalesce] Flush any pending coalesce window so the
        // last system notice isn't lost when the ViewModel is destroyed.
        flushPendingSysInfo()
        // Tear down whichever shell was actually serving this VM. Terminate
        // both ids when the rename happened, since a draft shell may still
        // linger if the agent ran a tool before `ensureSession()`.
        ExecutionCoordinator.sessionDidTerminate(activeSessionId)
        if (activeSessionId != sessionId) {
            ExecutionCoordinator.sessionDidTerminate(sessionId)
        }
        // [T-android-trim-memory] Permanently dispose the browser tab pool:
        // unregister ComponentCallbacks2 and destroy every WebView so renderer
        // processes are freed when the ViewModel goes away.
        _browserTabPoolRef?.dispose()
        _browserTabPoolRef = null
    }

    /**
     * T-android-new-chat-empty-residue: when the user leaves the chat screen,
     * drop sessions that were materialised in the DB (e.g. via a thinking /
     * memory toggle in `ensureSession()`) but never received a real message.
     * Without this hook, tapping "New chat" → toggling a session-scoped
     * setting → exiting leaves an empty row at the top of the session list.
     *
     * Called from ChatScreen's onDispose. Gates:
     *   - realSessionId must be non-empty (a row was actually inserted)
     *   - not currently streaming (background agent work would be lost)
     *   - persisted message count == 0 (authoritative DB check — `_messages`
     *     also contains ephemeral system-info bubbles that aren't persisted,
     *     so a state-only check would over-count).
     *
     * Safe to call multiple times; the row-existence + count gates make it
     * idempotent. After deletion we release the cached VM so a stale entry
     * doesn't linger in `ChatViewModelStore`.
     */
    fun cleanupIfEmptyOnExit() {
        val sid = realSessionId
        if (sid.isEmpty()) return
        if (_isStreaming.value) return
        if (_attachments.value.isNotEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val count = chatRepository.messageCount(sid)
                if (count > 0) return@launch
                AppLogger.info(
                    TAG,
                    "cleanupIfEmptyOnExit: deleting empty session $sid (no persisted messages)",
                )
                // Row-only: an empty session's dir may hold user files uploaded
                // before ever sending — never destroy those on an auto-sweep.
                chatRepository.deleteSessionRowOnly(sid)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    ChatViewModelStore.release(sid)
                }
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "cleanupIfEmptyOnExit failed for $sid: ${t.message}")
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Convert a flat list of MessageEntity into ChatMessages, merging toolResult
     * data from user-role messages back into their corresponding AssistantBlocks.
     * This mirrors iOS's toChatMessage() which reads both toolUse and toolResult parts.
     */
    private fun buildChatMessages(parsed: List<ParsedRow>): List<ChatMessage> =
        buildChatMessagesTranscript(parsed, mediaStore.mediaBaseDir) { msg ->
            Log.w(TAG, msg)
        }
    private fun MessageEntity.toLLMMessage(): LLMMessage {
        val parts = parsePartsJson(partsJson)
        val malformed = parts.isEmpty() && partsJson.isNotBlank()
        return buildSingleLlmMessage(this, partsJson, parts, malformed, mediaStore.mediaBaseDir)
    }

    private fun buildLlmMessages(parsed: List<ParsedRow>): List<LLMMessage> =
        buildLlmMessagesFromParsed(parsed, mediaStore.mediaBaseDir)

}

internal fun sanitizeAgentHistoryMessages(messages: MutableList<LLMMessage>) {
    // [T-compact-slice-tool-pairing] Pure implementation extracted to
    // SanitizeAgentHistory.kt so JVM unit tests exercise the exact
    // production code path. Logger injected here (Android side) so the pure
    // impl stays JVM-testable while the action stays observable in logcat.
    com.openminis.app.ui.chat.sanitizeAgentHistoryMessagesImpl(messages) {
        Log.w("ChatViewModel", it)
    }
}

/**
 * [T-consecutive-user-bridge] Enforce "roles must alternate" just before a
 * fresh user message is appended to history from an entry point that is NOT
 * inside the agent-loop tool-result cycle ([sendMessage] /
 * [drainQueuedPrompts]).
 *
 * Normally the tail is a completed assistant turn and this is a no-op. But
 * if a prior agent loop was interrupted (user Stop) or capped (MAX_AGENT_TURNS)
 * *after* a tool_result landed — tool results live in history as role=USER
 * messages — the tail is user(tool_result). Appending another user then
 * yields a deterministic 400 on Anthropic (`roles: must alternate`) or a
 * silently merged-away payload on OpenAI. Injecting a lightweight assistant
 * bridge (history-only, never persisted — same pattern as the queue-interrupt
 * bridge in `injectQueuedPromptsAsNewTurn`) breaks the consecutive-user run.
 *
 * Pure + JVM-testable (no ViewModel dependencies).
 */

/**
 * RC3: Roll the current turn's assistant blocks back to [turnStartBlockIndex],
 * dropping every block added since the current stream attempt began (the
 * failed attempt's partial / fake blocks). Blocks from earlier turns (all
 * indices before [turnStartBlockIndex]) are preserved.
 *
 * This is the canonical "no fake `tool_use` blocks survive a failed attempt"
 * semantic that BOTH the retry path and the fallback path of [runAgentLoop]
 * must honor. Extracted into a single production helper so the two paths
 * cannot drift (historically the fallback path missed this rollback and leaked
 * a failed provider's PENDING tool_use blocks into the completed turn's
 * persisted parts and the next request's sanitize-injected placeholder — see
 * F-T01-01). It operates on the mutable shared list in place and mirrors the
 * original `while (size > index) removeAt(last)` truncation.
 *
 * Pure + JVM-testable (no ViewModel/Android dependencies).
 *
 * @return true if any block was removed (i.e. there were partial blocks).
 */
internal fun rollbackTurnBlocksTo(
    blocks: MutableList<AssistantBlock>,
    turnStartBlockIndex: Int,
): Boolean {
    if (blocks.size <= turnStartBlockIndex) return false
    while (blocks.size > turnStartBlockIndex) {
        blocks.removeAt(blocks.size - 1)
    }
    return true
}

/**
 * RC3: Pure builder for a turn's persisted `AgentContentPart` list, walking the
 * slice of `allToolBlocks` that belongs to the current turn (from
 * [turnStartBlockIndex] to the end). Text blocks become `Text`, tool_use blocks
 * become `ToolUse` preserving stream order; thinking/info blocks are skipped.
 *
 * Extracted from the production instance method [ChatViewModel.buildTurnParts]
 * (which now delegates here) so the turn-persistence semantics are directly
 * JVM-testable and cannot drift from their tests. This is the seam that proves
 * the F-T01-01 acceptance invariant: after the fallback path rolls back a failed
 * provider's fake blocks via [rollbackTurnBlocksTo], the completed turn's parts
 * contain only tool_use blocks that were actually executed.
 *
 * Pure + JVM-testable (no ViewModel/Android dependencies).
 */
internal fun buildTurnPartsPure(
    allToolBlocks: List<AssistantBlock>,
    turnStartBlockIndex: Int,
    toolCallInputs: Map<String, String>,
): List<AgentContentPart> {
    if (turnStartBlockIndex >= allToolBlocks.size) return emptyList()
    val out = mutableListOf<AgentContentPart>()
    for (i in turnStartBlockIndex until allToolBlocks.size) {
        val block = allToolBlocks[i]
        when (block.kind) {
            "text" -> if (block.content.isNotEmpty()) {
                out.add(AgentContentPart.Text(block.content))
            }
            "tool_use" -> {
                val name = block.toolName
                if (name.isBlank()) continue
                val inputStr = toolCallInputs[block.id] ?: "{}"
                val inputJson = try { JSONObject(inputStr) } catch (_: Exception) { JSONObject() }
                out.add(AgentContentPart.ToolUse(block.id, name, inputJson))
            }
            // "thinking" / "info" → not persisted in parts
            else -> { /* skip */ }
        }
    }
    return out
}

// [T-chat-sysinfo-coalesce] Window in which consecutive same-iconKind
// appendSystemInfo calls are merged into one ChatMessage. Chosen to cover
// compact/revert failure chains (5+ calls typically fire within <100ms)
// without delaying a genuinely spaced user-facing notice.
private const val SYSINFO_COALESCE_WINDOW_MS = 200L

/** Pure: identity pass-through today; reserved for future dedup/trim rules. */
internal fun coalesceSystemInfoBlocks(blocks: List<AssistantBlock>): List<AssistantBlock> = blocks

/** Pure: last non-null payload wins; all-null → null. */
internal fun resolveCoalescedPayload(payloads: List<String?>): String? =
    payloads.lastOrNull { it != null }
