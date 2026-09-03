package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.sandbox.offload.ModelStreamErrorException
import com.openminis.app.sandbox.offload.ProviderExecutionGateway
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.flow.Flow

// [FE-5 batch 8] Offloaded stream turn (streamChatTurnOffloaded) extracted
// verbatim from ChatViewModel as an extension function.


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
internal fun ChatViewModel.streamChatTurnOffloaded(
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
        ChatViewModel.TAG_STREAM,
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
