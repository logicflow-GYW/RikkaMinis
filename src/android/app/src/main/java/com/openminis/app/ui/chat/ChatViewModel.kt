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
    internal val chatRepository: ChatRepository,
    internal val providerRepository: ProviderRepository,
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
        internal const val NEWLINE_FLUSH_MIN_CHARS = 50
        internal const val NEWLINE_FLUSH_MAX_LEN = 5_000
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
        internal val IN_FLIGHT_TOOL_STATUSES = setOf(
            ToolBlockStatus.STREAMING,
            ToolBlockStatus.PENDING,
            ToolBlockStatus.RUNNING,
        )
        // T145 phase 1: dedicated tag so the streaming-state debug pipeline
        // can be filtered with `adb logcat -s Minis.ChatVMStream:D`.
        // Removed once the retry-state regression is rooted out.
        internal const val TAG_STREAM = "ChatVMStream"
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
        internal const val MIN_MAX_TOKENS = 1024
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
        internal const val GLOBAL_MAX_TOKENS_CEILING = 128_000
        /**
         * Number of recent user-text turns kept verbatim as inference anchors when
         * compactAll runs. The summary stands in for everything older; the LLM
         * still sees the last N user-text turns + their assistant replies + tool
         * I/O so it can answer follow-ups that need verbatim detail rather than
         * the summary's distilled form. Mirrors iOS `compactKeepRecentUserTurns`.
         */
        internal const val COMPACT_KEEP_RECENT_USER_TURNS = 3
        /// [T-context-limit-enforce] Minimum number of newest complete turns the
        /// hard-cap trim preserves. Smaller = more aggressive trimming (cheaper),
        /// larger = safer for the current task's context. Chosen to mirror the
        /// `COMPACT_KEEP_RECENT_USER_TURNS` philosophy (current task stays warm)
        /// while trimming much more greedily than compact's 3-turn lookback.
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

    internal val mediaStore = com.openminis.app.data.storage.MediaStore(context)

    internal val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
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

    internal val _visibleMessageCap = MutableStateFlow(INITIAL_VISIBLE_MESSAGE_CAP)
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
    internal val _streamingById = MutableStateFlow<Map<String, StreamingDelta>>(emptyMap())
    val streamingById: StateFlow<Map<String, StreamingDelta>> = _streamingById.asStateFlow()

    /** 单调递增回合纪元：每开一个新回合 +1，旧回合晚到 delta 由渲染层按 epoch 忽略。 */
    internal var streamEpoch = 0L

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
    internal class StreamFlushState {
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
    internal val streamFlushStates = HashMap<String, StreamFlushState>()

    /**
     * [T-android-stream-flush-review] Cancel a message's pending trailing flush
     * and drop its throttle accumulator. Call from EVERY stream-termination
     * path (natural end, cancel, turn-limit, retry-truncate, clearChat) so a
     * trailing coroutine — which runs on viewModelScope, NOT streamJob, and is
     * therefore NOT cancelled by streamJob.cancel() — can't fire after the
     * side channel was drained and re-revive a stale "thinking" overlay row.
     */
    internal fun clearStreamFlushState(id: String) {
        streamFlushStates.remove(id)?.trailingJob?.cancel()
    }
    internal fun clearAllStreamFlushStates() {
        streamFlushStates.values.forEach { it.trailingJob?.cancel() }
        streamFlushStates.clear()
    }
    /** Cancel + drop flush states for any message id NOT in [keptIds] (retry/truncate). */
    internal fun retainStreamFlushStates(keptIds: Set<String>) {
        val drop = streamFlushStates.keys.filter { it !in keptIds }
        for (id in drop) streamFlushStates.remove(id)?.trailingJob?.cancel()
    }

    // Dual-path flush thresholds — ported from iOS. Time tiers scale with total
    // length; the newline fast-path flushes immediately on a line break once
    // enough new chars have accumulated, gated to short docs so dense
    // box-drawing streams don't pin the flush rate to the per-token cadence.
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

    internal val _isStreaming = MutableStateFlow(false)
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
    internal val _canResume = MutableStateFlow(false)
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

    internal val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    internal val _modelName = MutableStateFlow("")
    val modelName: StateFlow<String> = _modelName.asStateFlow()

    /** T201: gate the init-time `config.collect` re-resolver so the StateFlow's
     *  replay cache can't beat [loadSession] to setting `_modelName`. Without
     *  this, opening a session that previously fell back mid-run flashes the
     *  default model name for one frame before the persisted binding settles. */
    internal val _sessionLoaded = MutableStateFlow(false)

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

    internal val _sessionTitle = MutableStateFlow("New Chat")
    val sessionTitle: StateFlow<String> = _sessionTitle.asStateFlow()

    /** T-chat-title-pill: category drives the icon shown in the sticky title
     *  pill (mirrors SessionRow's categoryStyle lookup). Null on draft sessions
     *  and until LLM title-generation tags the session. */
    internal val _sessionCategory = MutableStateFlow<String?>(null)
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
    internal val _imageBudgetEvent = MutableSharedFlow<ImageBudget.BudgetResult>(extraBufferCapacity = 4)
    val imageBudgetEvent: SharedFlow<ImageBudget.BudgetResult> = _imageBudgetEvent.asSharedFlow()

    /**
     * Request-level image-budget events (T-request-imgsize). Emitted by
     * [applyRequestImageBudget] when the cumulative history image payload
     * exceeds [ImageBudget.MAX_REQUEST_BYTES] and older images had to be
     * elided to text placeholders. Distinct from [imageBudgetEvent] so the
     * UI Snackbar can show a different message ("older images compacted")
     * and the two events don't race.
     */
    internal val _requestBudgetEvent = MutableSharedFlow<ImageBudget.RequestBudgetPlan>(extraBufferCapacity = 4)
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
    internal val _forceScrollToBottom = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val forceScrollToBottom: SharedFlow<Unit> = _forceScrollToBottom.asSharedFlow()

    internal val _availableGroups = MutableStateFlow<List<ModelGroup>>(emptyList())
    val availableGroups: StateFlow<List<ModelGroup>> = _availableGroups.asStateFlow()

    internal val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()

    internal val _selectedGroupName = MutableStateFlow("")
    val selectedGroupName: StateFlow<String> = _selectedGroupName.asStateFlow()

    internal val _providerName = MutableStateFlow("")
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

    internal val _activeEntryId = MutableStateFlow<String?>(null)
    val activeEntryId: StateFlow<String?> = _activeEntryId.asStateFlow()

    /** Prompts enqueued while the agent loop is running. Drained after the loop finishes. */
    internal val _promptQueue = MutableStateFlow<List<QueuedPrompt>>(emptyList())
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
    internal val _compactSummary = MutableStateFlow<String?>(null)
    val compactSummary: StateFlow<String?> = _compactSummary.asStateFlow()

    /** True when a compact-summary LLM call is in flight (UI disables further sends). */
    internal val _isCompacting = MutableStateFlow(false)
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
    internal var streamJob: Job? = null
    internal var currentProvider: LLMProvider? = null
    internal var currentModel: LLMModel? = null

    /** Structured agent history for the agent loop (contentParts-based). */
    internal val agentHistory = mutableListOf<LLMMessage>()

    /**
     * All agent tool definitions, recomputed on each read so the memory
     * toggle gate (see [_memoryEnabled]) takes effect immediately when
     * the user flips /memory mid-session without forcing a VM rebuild.
     * The cost is negligible — [AgentTools.makeAgentTools] just builds a
     * fixed list of definition objects, no I/O.
     */
    internal val agentTools: List<AgentToolDefinition>
        get() = AgentTools.makeAgentTools(memoryEnabled = _memoryEnabled.value)

    /**
     * Per-session loop detector. Reset alongside [agentHistory] whenever the
     * conversation is rewound (edit/regenerate) so a stale tool-call window
     * can't bleed warnings into a fresh prompt.
     */
    internal val toolLoopDetector = ToolLoopDetector()

    /**
     * Programmatic tool-failure logger (T3, ported from OmniBot's
     * SelfImprovingSkillFailureHook). Side-channel only: records a structured
     * block into the session's `.learnings/ERRORS.md` when a tool fails,
     * deduplicated by (toolName + summary) within a 10-minute window. Never
     * touches the ToolExecutionResult returned to the LLM.
     */
    internal val toolFailureHook = ToolFailureHook(writeErrorBlock = { block -> appendToolFailureBlock(block) })

    /**
     * Pure-JVM group routing engine (model-group strategy redesign, Phase 1).
     * Owns the "which member to use / in which order to fall back" decisions
     * that previously lived inline in resolveProviderFromGroup and
     * buildFallbackProviders, plus per-member runtime health (wired in
     * Phase 2). Same pattern as ToolFailureHook: no Android deps, injectable
     * clock, unit-testable.
     */
    internal val groupRouter = com.openminis.app.data.routing.GroupRouter()

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
    internal val traceObserver = ChatAgentTraceObserver(
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
            viewModelScope.launch { chatRepository.updateSessionPreview(realSessionId.ifEmpty { sessionId }, text) }
        }
        override val agentHistory: MutableList<LLMMessage> get() = this@ChatViewModel.agentHistory
        override val agentTools: List<AgentToolDefinition> get() = this@ChatViewModel.agentTools
        override fun sanitizeAgentHistory() = this@ChatViewModel.sanitizeAgentHistory()
        override fun effectiveContextWindowTokens(): Int? = this@ChatViewModel.effectiveContextWindowTokens()
        override fun effectiveAgentHistory(): List<LLMMessage> = effectiveAgentHistory()
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
        ): InjectedTurn? = injectQueuedPromptsAsNewTurn(
            finishedAssistantId, finishedAccumulatedText, finishedAllToolBlocks)
        override suspend fun drainQueuedPrompts(): String? {
            // The engine-facing surface is argless; the VM implementation
            // takes (provider, systemPrompt, fallbackStrategy) — those are
            // the values the original loop captured at send/retry/resume
            // entry. Rebuild them here exactly the way those callers did.
            val provider = currentProvider ?: return null
            val systemPrompt = buildSystemPrompt()
            val activeFallbackStrategy = run {
                val groupId = _selectedGroupId.value
                groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                    ?: com.openminis.app.data.model.FallbackStrategy.default
            }
            return this@ChatViewModel.drainQueuedPrompts(provider, systemPrompt, activeFallbackStrategy)
        }
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

    internal val _memoryToolRecords = MutableStateFlow<List<MemoryToolRecord>>(emptyList())
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
    internal fun revokeMemoryWritesInDeletedMessages(deletedMessages: List<ChatMessage>) {
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
    internal fun appendSystemInfo(text: String, iconKind: String, payload: String? = null) {
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
    internal var _cachedLatestMarker: com.openminis.app.data.db.CompactMarkerEntity? = null

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
    internal fun walkBackUserTurnsBounded(
        anchorIdx: Int,
        maxUserTextTurns: Int,
        maxMessages: Int,
    ): WalkBackResult = walkBackUserTurnsBounded(agentHistory, anchorIdx, maxUserTextTurns, maxMessages)



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
    internal val compactSummarySystemPrompt: String
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
    internal val isDraft: Boolean = sessionId.startsWith("__new__")

    /** Model group ID from long-press FAB, encoded in the draft session ID. */
    internal val initialGroupId: String? = sessionId.substringAfter("__grp__", "").takeIf { it.isNotEmpty() }

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
    internal suspend fun ensureSession(): String {
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
    internal fun ensureTrailingRoleAlternativeBeforeUserAppend() {
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
    // [FE-5 route C step 3] Loop-session queue types — InjectedTurn moved to
    // AgentLoopHost.kt (the engine returns/consumes it); the VM keeps its own
    // alias-free references. The old private nested data class is gone.

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
    internal fun setInlineError(errorText: String, detail: String? = null) {
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

    internal fun clearPersistedLastAssistantError() {
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try { chatRepository.updateLastAssistantError(sid, null) }
            catch (e: Exception) { Log.w(TAG, "clear error_info (persisted) failed: ${e.message}") }
        }
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

    internal suspend fun runAgentLoop(
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

    internal var titleGenerationAttempts = 0
    internal var titleGenerationInFlight = false
    internal val TITLE_MAX_ATTEMPTS = 3

    /**
     * [T-android-overlay-reply-status-34599] Pull the most recent
     * assistant text out of `_messages` and hand it to
     * [SessionActivityTracker.publishLastReply]. The tracker truncates
     * to a fixed-width excerpt and pairs it with [sessionId] so the
     * floating overlay can render a "tap to open this chat" capsule
     * after the stream completes. No-op when no assistant message has
     * content yet (e.g. fail during the very first turn).
     */
    internal fun publishOverlayReplyExcerpt(sessionId: String) {
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
    internal fun buildChatMessages(parsed: List<ParsedRow>): List<ChatMessage> =
        buildChatMessagesTranscript(parsed, mediaStore.mediaBaseDir) { msg ->
            Log.w(TAG, msg)
        }
    internal fun MessageEntity.toLLMMessage(): LLMMessage {
        val parts = parsePartsJson(partsJson)
        val malformed = parts.isEmpty() && partsJson.isNotBlank()
        return buildSingleLlmMessage(this, partsJson, parts, malformed, mediaStore.mediaBaseDir)
    }

    internal fun buildLlmMessages(parsed: List<ParsedRow>): List<LLMMessage> =
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
