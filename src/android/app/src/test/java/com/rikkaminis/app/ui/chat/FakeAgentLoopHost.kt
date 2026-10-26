package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.agent.ToolLoopDetector
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.AgentToolDefinition
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.model.LLMUsage
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.data.routing.GroupRouter
import com.rikkaminis.app.provider.LLMProvider
import com.rikkaminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/**
 * One scripted provider attempt. [chunks] are emitted in order;
 * [throwBefore] throws before the first chunk (request-level failure),
 * [throwAfter] throws after the last chunk (mid-stream reset).
 */
internal class TurnScript(
    val chunks: List<LLMStreamChunk> = emptyList(),
    val throwBefore: Throwable? = null,
    val throwAfter: Throwable? = null,
)

/** A recorded stream request (the engine's view of what it asked for). */
internal class StreamRequest(
    val attemptIndex: Int,
    val providerName: String,
    val messageCount: Int,
    val systemPrompt: String?,
    val maxTokens: Int,
    val toolCount: Int,
    val thinkingLevel: ThinkingLevel,
)

internal class ToolExecution(
    val toolId: String,
    val name: String,
    val argsJson: String,
    val currentText: String,
)

internal class PersistedTurn(
    val dbId: String,
    val partKinds: List<String>,
    val usage: LLMUsage?,
    val reasoningContent: String?,
    val modelId: String?,
    val entryId: String?,
)

/**
 * Full-interface fake host for driving [AgentLoopEngine.runAgentLoop] on the JVM.
 *
 * Ground truth = the ordered [calls] log plus the structured recorders
 * ([persistedTurns], [toolExecutions], [assistantSnapshots], [streamRequests],
 * [buildTurnPartsCalls]). Every scenario asserts against these; nothing is
 * inferred from production internals.
 *
 * Deliberately inert by default: every setter records and does nothing else, so
 * each scenario sees exactly the behavior the engine itself produces.
 */
internal class FakeAgentLoopHost(
    override val agentTools: List<AgentToolDefinition> = emptyList(),
    override val thinkingLevel: ThinkingLevel = ThinkingLevel.MEDIUM,
    /** The user turn the loop is answering. Real conversations always start
     *  with one; EmptyTurnRecovery.plan() reads the tail BEFORE the empty
     *  assistant row and gives up when it is not a USER turn. */
    seedUserPrompt: String? = "harness user prompt",
) : AgentLoopHost {

    // ── ordered ground-truth log ──
    val calls = mutableListOf<String>()
    private fun record(label: String) { calls.add(label) }

    // ── structured recorders ──
    val assistantSnapshots = mutableListOf<Pair<String, Boolean>>() // text to isStreaming
    val persistedTurns = mutableListOf<PersistedTurn>()
    val persistedToolResults = mutableListOf<List<String>>()
    val updatedPersistedTurns = mutableListOf<Pair<String, Int>>() // dbId to part count
    val toolExecutions = mutableListOf<ToolExecution>()
    val buildTurnPartsCalls = mutableListOf<Pair<Int, Int>>() // turnStartBlockIndex to allToolBlocks.size
    val inlineErrors = mutableListOf<String>()
    val transientInlineErrors = mutableListOf<String>()
    val autoRetryCalls = mutableListOf<Pair<Int, Int>>() // attempt to countdownSec
    val autoRetryCountdowns = mutableListOf<Int>()
    val fallbackTriggers = mutableListOf<Int>()
    val providerSwitches = mutableListOf<String>()
    val canResumeValues = mutableListOf<Boolean>()
    val previewUpdates = mutableListOf<String>()
    val activeEntryValues = mutableListOf<String>()
    val modelUpdates = mutableListOf<String>()
    val streamRequests = mutableListOf<StreamRequest>()
    val lastTurnContextTokensValues = mutableListOf<Int>()
    /** offloadContextIfNeeded args: contextWindow to lastContextTokens to force. */
    val offloadCalls = mutableListOf<Triple<Int, Int, Boolean>>()
    /** maybeAutoCompactInLoop args: contextWindow to lastContextTokens (decision figure). */
    val compactCalls = mutableListOf<Pair<Int, Int>>()
    /** trimContextHistoryWindow args: contextWindow to lastContextTokens (decision figure). */
    val trimCalls = mutableListOf<Pair<Int, Int>>()

    // ── scripted inputs ──
    /** One [TurnScript] per provider attempt, consumed in order. */
    val scripts = ArrayDeque<TurnScript>()
    /** Tool results consumed in execution order (one per executeTool call). */
    val toolResults = ArrayDeque<ToolExecutionResult>()
    /** Config entries visible to the fallback router (fallback scenarios). */
    var configEntries: List<ModelEntry> = emptyList()
    /** maybeAutoCompactInLoop scripted verdict. */
    var autoCompactVerdict: Boolean = false
    /** Post-offload estimate returned by offloadContextIfNeeded (null = nothing offloaded). */
    var offloadResultTokens: Int? = null
    /** Throwables consumed by executeTool in dispatch order (models a stop pressed mid-tool). */
    val toolThrowables = ArrayDeque<Throwable>()

    // ── internal state the engine reads back through getters ──
    private val history = mutableListOf<LLMMessage>().apply {
        if (seedUserPrompt != null) {
            add(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = seedUserPrompt,
                    contentParts = listOf(AgentContentPart.Text(seedUserPrompt)),
                )
            )
        }
    }
    private var dbCounter = 0
    private var streamCounter = 0
    private var canResume = false

    val lastBlocks: List<AssistantBlock> get() = lastBlocksCache
    private var lastBlocksCache: List<AssistantBlock> = emptyList()

    val finalAssistantText: String
        get() = assistantSnapshots.lastOrNull()?.first ?: ""

    // ── identity / i18n ──
    override val activeSessionId: String = "sess-harness"
    override fun string(resId: Int, vararg args: Any): String =
        if (args.isEmpty()) "R$resId" else "R$resId(${args.joinToString(",")})"
    override fun emitFallbackToast(text: String) { record("emitFallbackToast($text)") }
    override fun updateSessionPreview(text: String) { previewUpdates.add(text); record("updateSessionPreview") }

    // ── history / context ──
    override val agentHistory: MutableList<LLMMessage> get() = history
    override fun sanitizeAgentHistory() { record("sanitizeAgentHistory") }
    override fun effectiveContextWindowTokens(): Int? = 200_000
    override fun effectiveAgentHistory(): List<LLMMessage> = history.toList()
    override fun applyRequestImageBudget(messages: List<LLMMessage>): List<LLMMessage> = messages
    override fun checkContextBeforeSend(): Boolean = true
    override fun isContextExhausted(): Boolean = false
    override fun offloadContextIfNeeded(contextWindow: Int, lastContextTokens: Int, force: Boolean): Int? {
        offloadCalls.add(Triple(contextWindow, lastContextTokens, force))
        record("offloadContextIfNeeded(force=$force)")
        return offloadResultTokens
    }
    override fun trimContextHistoryWindow(contextWindow: Int, lastContextTokens: Int) {
        trimCalls.add(contextWindow to lastContextTokens)
        record("trimContextHistoryWindow")
    }
    override suspend fun maybeAutoCompactInLoop(contextWindow: Int, lastContextTokens: Int): Boolean {
        compactCalls.add(contextWindow to lastContextTokens)
        record("maybeAutoCompactInLoop")
        return autoCompactVerdict
    }
    override fun unavailableGroupMembers(): List<String> = emptyList()

    // ── assistant presentation ──
    override suspend fun addAssistantPlaceholder(assistantId: String, thinkingLevel: ThinkingLevel?) {
        record("addAssistantPlaceholder($assistantId)")
    }
    override fun updateAssistantMessage(
        assistantId: String,
        text: String,
        isStreaming: Boolean,
        blocks: List<AssistantBlock>,
        isAwaitingModelResponse: Boolean,
    ) {
        assistantSnapshots.add(text to isStreaming)
        lastBlocksCache = blocks.toList()
        record("updateAssistantMessage(streaming=$isStreaming,len=${text.length})")
    }
    override fun buildTurnParts(
        allToolBlocks: List<AssistantBlock>,
        turnStartBlockIndex: Int,
        toolCallInputs: Map<String, String>,
    ): List<AgentContentPart> {
        buildTurnPartsCalls.add(turnStartBlockIndex to allToolBlocks.size)
        record("buildTurnParts(start=$turnStartBlockIndex,blocks=${allToolBlocks.size})")
        return buildTurnPartsPure(allToolBlocks, turnStartBlockIndex, toolCallInputs)
    }
    override fun buildAssistantPartsJson(
        parts: List<AgentContentPart>,
        toolBlockMeta: Map<String, AssistantBlock>,
    ): String = "{\"harness\":true,\"parts\":${parts.size}}"

    // ── persistence ──
    override suspend fun persistAssistantTurn(
        parts: List<AgentContentPart>,
        usage: LLMUsage?,
        reasoningContent: String?,
        toolBlockMeta: Map<String, AssistantBlock>,
        modelId: String?,
        entryId: String?,
    ): String? {
        // Production contract (InterruptedTailDetector.kt:30, TurnAnswerPick.kt:10):
        // persistAssistantTurn drops an assistant row that has no parts. Model it,
        // or the harness over-counts rows for empty turns / empty completions.
        if (parts.isEmpty()) {
            record("persistAssistantTurn(skipped: empty parts)")
            return null
        }
        val dbId = "db-${++dbCounter}"
        persistedTurns.add(PersistedTurn(dbId, parts.map { it.kindLabel() }, usage, reasoningContent, modelId, entryId))
        record("persistAssistantTurn#$dbId")
        return dbId
    }
    override suspend fun updatePersistedAssistantTurn(
        dbId: String,
        parts: List<AgentContentPart>,
        toolBlockMeta: Map<String, AssistantBlock>,
    ) {
        updatedPersistedTurns.add(dbId to parts.size)
        record("updatePersistedAssistantTurn($dbId)")
    }
    override suspend fun persistToolResultMessage(
        parts: List<AgentContentPart>,
        transcriptRedactions: Map<String, String>,
    ): String? {
        persistedToolResults.add(parts.map { it.kindLabel() })
        record("persistToolResultMessage")
        return "dbtr-${++dbCounter}"
    }

    // ── tools ──
    override suspend fun executeTool(
        name: String,
        argsJson: String,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>,
        assistantId: String,
        currentText: String,
    ): ToolExecutionResult {
        toolExecutions.add(ToolExecution(toolId, name, argsJson, currentText))
        record("executeTool($name)")
        toolThrowables.removeFirstOrNull()?.let { throw it }
        return toolResults.removeFirstOrNull()
            ?: ToolExecutionResult(output = "harness: no scripted result for $name", success = false, toolTitle = name)
    }
    override fun preflightValidateToolCall(
        name: String,
        args: JSONObject,
        tools: List<AgentToolDefinition>,
    ): String? = if (tools.any { it.name == name }) null else "Unknown tool: $name"

    // ── errors / retries ──
    override fun setInlineError(errorText: String, detail: String?) {
        inlineErrors.add(errorText)
        record("setInlineError($errorText)")
    }
    override fun setTransientInlineError(errorText: String) {
        transientInlineErrors.add(errorText)
        record("setTransientInlineError($errorText)")
    }
    override fun clearInlineError() { record("clearInlineError") }
    override fun dynamicMaxTokens(provider: LLMProvider, lastContextTokens: Int): Int = 8192

    // ── streaming ──
    override fun streamChatTurnOffloaded(
        provider: LLMProvider,
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = flow {
        val attemptIndex = ++streamCounter
        streamRequests.add(
            StreamRequest(attemptIndex, provider.name, messages.size, systemPrompt, maxTokens, tools.size, thinkingLevel)
        )
        record("streamAttempt#$attemptIndex(provider=${provider.name})")
        val script = scripts.removeFirstOrNull()
            ?: error("harness: no TurnScript queued for attempt #$attemptIndex")
        script.throwBefore?.let { throw it }
        for (chunk in script.chunks) emit(chunk)
        script.throwAfter?.let { throw it }
    }
    override fun generateSessionTitleIfNeeded() { record("generateSessionTitleIfNeeded") }
    override val sessionTitleNeedsGeneration: Boolean = false

    // ── queue ──
    override suspend fun injectQueuedPromptsAsNewTurn(
        finishedAssistantId: String,
        finishedAccumulatedText: String,
        finishedAllToolBlocks: List<AssistantBlock>,
    ): InjectedTurn? = null
    override suspend fun drainQueuedPrompts(): String? = null
    override fun onQueueStatus(waitingAhead: Int) { record("onQueueStatus($waitingAhead)") }

    // ── finalize paths ──
    override fun finalizeAtTurnLimit(
        assistantId: String,
        text: String,
        blocks: List<AssistantBlock>,
        maxTurnsThisRun: Int,
    ) {
        record("finalizeAtTurnLimit(maxTurns=$maxTurnsThisRun)")
    }
    override fun finalizeBudgetStop(
        assistantId: String,
        text: String,
        blocks: List<AssistantBlock>,
        reason: String,
        maxProviderAttemptsThisRun: Int,
        maxTurnsThisRun: Int,
    ) {
        record("finalizeBudgetStop(reason=$reason)")
    }

    // ── observable state setters ──
    override fun setAutoRetry(attempt: Int, countdownSec: Int) {
        autoRetryCalls.add(attempt to countdownSec)
        record("setAutoRetry($attempt)")
    }
    override fun setAutoRetryCountdown(countdownSec: Int) {
        autoRetryCountdowns.add(countdownSec)
        record("setAutoRetryCountdown($countdownSec)")
    }
    override fun resetAutoRetry() { record("resetAutoRetry") }
    override fun noteModelNames(modelName: String, providerName: String?, entryId: String?) {
        record("noteModelNames($modelName)")
    }
    override fun setActiveEntryId(entryId: String) {
        activeEntryValues.add(entryId)
        record("setActiveEntryId($entryId)")
    }
    override fun setCanResume(value: Boolean) {
        canResume = value
        canResumeValues.add(value)
        record("setCanResume($value)")
    }
    override fun bumpFallbackTrigger() {
        fallbackTriggers.add((fallbackTriggers.lastOrNull() ?: 0) + 1)
        record("bumpFallbackTrigger")
    }
    override fun setLastTurnContextTokens(tokens: Int) {
        lastTurnContextTokensValues.add(tokens)
        record("setLastTurnContextTokens($tokens)")
    }
    override fun recordContextGrowth(deltaTokens: Int) { record("recordContextGrowth($deltaTokens)") }
    override fun setEnhancedCache(enabled: Boolean) { record("setEnhancedCache($enabled)") }
    override fun updateCurrentModel(model: LLMModel) {
        modelUpdates.add(model.id)
        record("updateCurrentModel(${model.id})")
    }
    override fun setCurrentProvider(provider: LLMProvider) {
        providerSwitches.add(provider.name)
        record("setCurrentProvider(${provider.name})")
    }
    override fun setProviderName(label: String) { record("setProviderName($label)") }

    // ── config-backed getters ──
    override val toolLoopDetector: ToolLoopDetector = ToolLoopDetector()
    override val groupRouter: GroupRouter = GroupRouter()
    override val isStreaming: Boolean = true
    override val enhancedCacheEnabled: Boolean = false
    override val autoRetryAttempt: Int = 0
    override val autoRetryCountdown: Int = 0
    override val activeEntryId: String? = null
    override val promptQueueDepth: Int = 0
    override val activeConfigModelEntries: List<ModelEntry> get() = configEntries
    override fun providerInstanceLabel(instanceId: String): String? = "label-$instanceId"

    /** Throws a real cancellation (cause == null), like a user stopping the run. */
    fun throwRealCancellation(): Nothing = throw CancellationException("user stopped")
}

private fun AgentContentPart.kindLabel(): String = when (this) {
    is AgentContentPart.Text -> "text"
    is AgentContentPart.ToolUse -> "tool_use:${name}"
    else -> this::class.simpleName ?: "unknown"
}

/**
 * Scripted provider: every real model interaction flows through
 * [FakeAgentLoopHost.streamChatTurnOffloaded], so this provider is only touched
 * for its identity attributes. send/stream bodies are harness-contract errors.
 */
internal class ScriptedProvider(
    override val name: String = "scripted",
    override var model: LLMModel,
) : LLMProvider {
    override var instanceContext: com.rikkaminis.app.data.model.ProviderInstance? = null

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): com.rikkaminis.app.data.model.LLMResponse =
        error("harness: all model traffic must go through host.streamChatTurnOffloaded")

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> =
        error("harness: all model traffic must go through host.streamChatTurnOffloaded")
}
