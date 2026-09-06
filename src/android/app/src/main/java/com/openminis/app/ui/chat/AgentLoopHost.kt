package com.openminis.app.ui.chat

import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.flow.Flow

/**
 * FE-5 route C step 3: the surface of ChatViewModel that
 * [AgentLoopEngine.runAgentLoop] actually needs, expressed as an interface so
 * the engine body is a verbatim lift of the original loop (member references
 * become `host.` calls) and can be JVM-tested against a fake host.
 *
 * Grouped by concern:
 *  - chat history / tools (read + sanitize + offload/trim)
 *  - assistant-message presentation (update/persist)
 *  - tool dispatch
 *  - inline error / banner state
 *  - provider-adjacent helpers (dynamic max tokens, offload stream, budget)
 *  - run lifecycle extras (title generation, queued-prompt injection/drain)
 *
 * Anything NOT here stays on the VM — the engine cannot reach it by design.
 */
internal interface AgentLoopHost {
    // ── identity & session ────────────────────────────────────────────────
    val activeSessionId: String

    /** Localized string lookup (the loop surfaces several i18n errors). */
    fun string(resId: Int, vararg args: Any): String

    /** One-shot transient toast event (model-switch notice). */
    fun emitFallbackToast(text: String)

    /** Session-list preview update after a completed turn. */
    fun updateSessionPreview(text: String)

    // ── history & tools ───────────────────────────────────────────────────
    val agentHistory: MutableList<LLMMessage>
    val agentTools: List<AgentToolDefinition>
    fun sanitizeAgentHistory()
    fun effectiveContextWindowTokens(): Int?
    fun effectiveAgentHistory(): List<LLMMessage>
    fun applyRequestImageBudget(messages: List<LLMMessage>): List<LLMMessage>
    fun checkContextBeforeSend(): Boolean
    fun offloadContextIfNeeded(contextWindow: Int, lastContextTokens: Int, force: Boolean = false)
    fun trimContextHistoryWindow(contextWindow: Int, lastContextTokens: Int)
    /**
     * [T-auto-compact-in-loop] Turn-boundary automatic summarization: before
     * the loop falls back to the hard [trimContextHistoryWindow] (which drops
     * the oldest turns verbatim and breaks semantic continuity), ask the host
     * to summarise the old turns into a `<context-summary>` instead. Returns
     * true when a compact was actually started (the host gates on
     * _isCompacting / in-stream state), so the loop can await it.
     */
    suspend fun maybeAutoCompactInLoop(contextWindow: Int, lastContextTokens: Int): Boolean
    fun unavailableGroupMembers(): List<String>

    // ── assistant message presentation ────────────────────────────────────
    /**
     * Append the empty assistant placeholder bubble to the UI message list
     * before the first stream chunk arrives (mirrors iOS isAwaitingModelResponse
     * "thinking" gap). Must run on Main so the message is present before the
     * first streaming delta — otherwise [com.openminis.app.ui.chat.mergeStreamingOverlay]
     * cannot match the assistantId and the live reply never renders.
     */
    suspend fun addAssistantPlaceholder(assistantId: String, thinkingLevel: ThinkingLevel?)
    fun updateAssistantMessage(
        assistantId: String,
        text: String,
        isStreaming: Boolean,
        blocks: List<AssistantBlock>,
        isAwaitingModelResponse: Boolean = false,
    )
    fun buildTurnParts(
        allToolBlocks: List<AssistantBlock>,
        turnStartBlockIndex: Int,
        toolCallInputs: Map<String, String>,
    ): List<AgentContentPart>
    fun buildAssistantPartsJson(
        parts: List<AgentContentPart>,
        toolBlockMeta: Map<String, AssistantBlock>,
    ): String
    suspend fun persistAssistantTurn(
        parts: List<AgentContentPart>,
        usage: LLMUsage?,
        reasoningContent: String? = null,
        toolBlockMeta: Map<String, AssistantBlock> = emptyMap(),
        modelId: String? = null,
        entryId: String? = null,
    ): String?
    suspend fun persistToolResultMessage(parts: List<AgentContentPart>): String?

    // ── tool dispatch ─────────────────────────────────────────────────────
    suspend fun executeTool(
        name: String,
        argsJson: String,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>,
        assistantId: String,
        currentText: String,
    ): ToolExecutionResult
    fun preflightValidateToolCall(
        name: String,
        args: org.json.JSONObject,
        tools: List<AgentToolDefinition>,
    ): String?

    // ── inline error / banner ─────────────────────────────────────────────
    fun setInlineError(errorText: String, detail: String? = null)
    fun setTransientInlineError(errorText: String)
    fun clearInlineError()

    // ── provider-adjacent ─────────────────────────────────────────────────
    fun dynamicMaxTokens(provider: LLMProvider, lastContextTokens: Int = 0): Int
    fun streamChatTurnOffloaded(
        provider: LLMProvider,
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk>

    // ── run lifecycle extras ──────────────────────────────────────────────
    fun generateSessionTitleIfNeeded()
    suspend fun injectQueuedPromptsAsNewTurn(
        finishedAssistantId: String,
        finishedAccumulatedText: String,
        finishedAllToolBlocks: List<AssistantBlock>,
    ): InjectedTurn?
    suspend fun drainQueuedPrompts(): String?
    fun finalizeAtTurnLimit(
        assistantId: String,
        text: String,
        blocks: List<AssistantBlock>,
    )

    /**
     * [fix/budget-stop-silent-exit] Finalize a run interrupted by an execution
     * budget stop (provider-attempt limit / turn limit / deadline). Same shape
     * as [finalizeAtTurnLimit] — keep accumulated content, drop streaming
     * state, attach an inline banner, mark resumable — but with a distinct
     * message so the user can tell "ran out of runway" from "hit the 200-turn
     * ceiling". [reason] is the t7BudgetStopReason string (e.g.
     * "provider_attempt_limit").
     */
    fun finalizeBudgetStop(
        assistantId: String,
        text: String,
        blocks: List<AssistantBlock>,
        reason: String,
    )

    // ── routing / retry bookkeeping ───────────────────────────────────────
    val toolLoopDetector: ToolLoopDetector
    val groupRouter: com.openminis.app.data.routing.GroupRouter
    val thinkingLevel: ThinkingLevel
    val isStreaming: Boolean
    val enhancedCacheEnabled: Boolean
    val currentModelSupportsReasoning: Boolean
    val autoRetryAttempt: Int
    val autoRetryCountdown: Int
    val activeEntryId: String?
    val promptQueueDepth: Int
    val activeConfigModelEntries: List<com.openminis.app.data.model.ModelEntry>
    fun providerInstanceLabel(instanceId: String): String?
    fun setAutoRetry(attempt: Int, countdownSec: Int)
    fun setAutoRetryCountdown(countdownSec: Int)
    fun resetAutoRetry()
    fun noteModelNames(modelName: String, providerName: String?, entryId: String?)
    fun setActiveEntryId(entryId: String)
    fun setCanResume(value: Boolean)
    fun bumpFallbackTrigger()
    fun setLastTurnContextTokens(tokens: Int)
    fun setEnhancedCache(enabled: Boolean)
    fun updateCurrentModel(model: com.openminis.app.data.model.LLMModel)
    fun setCurrentProvider(provider: LLMProvider)
    fun setProviderName(label: String)
}

/** Result of injecting queued prompts as a new turn mid-loop (was a VM nested type). */
internal data class InjectedTurn(val newAssistantId: String)
