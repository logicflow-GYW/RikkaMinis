package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.db.compositeEntryKey
import com.rikkaminis.app.data.model.AgentToolDefinition
import com.rikkaminis.app.data.model.LLMError
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.data.routing.GroupRouter
import com.rikkaminis.app.data.routing.RouteOutcome
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.provider.LLMProvider
import com.rikkaminis.app.sandbox.offload.ModelStreamErrorException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

// [T-multi-api-key] Credential rotation for the offloaded chat stream.
//
// Lives as a ChatViewModel EXTENSION (not inside AgentLoopEngine) on purpose:
// the engine's retry/fallback loop is the single owner of the group-fallback
// escalation ladder, and this file only handles the CHEAPEST rung of that
// ladder — "the same provider with a different Authorization header". Rotating
// a credential changes nothing about the model, the context, the tool set or
// the system prompt, so the engine's loop never needs to know it happened.
//
// The wrapper wraps the cold offloaded flow and transparently retries it with
// the next credential BEFORE any content reaches the collector:
//
//   attempt(slot i)  ── fail before first chunk, credential-scoped error ──▶
//   rotate ── attempt(slot i+1) ──▶ ... ──▶ success → sticky memory + notice
//
// Rotation only fires on a ZERO-CHUNK failure. That is the same window the
// engine's own worker-death heuristic uses (hadChunks == false → a resend
// cannot duplicate output), and it is the only window where transparently
// retrying is safe: once the user has seen partial text, a new attempt would
// either duplicate it or require the engine's rollback machinery, which is the
// fallback loop's job — not this rung's.

/**
 * Sticky memory restore at construction time (called from [ChatViewModel]'s
 * init). Adopts the persisted `entryId → keyIndex` map into the process-local
 * router so the FIRST request after an app restart starts on the credential
 * that last worked — which is the entire point of the memory (a sticky
 * preference that dies with the process answers no future request).
 *
 * [T-key-affinity] Also records the restore in the log so a misattributed
 * slot is diagnosable rather than looking like the memory "did nothing".
 */
internal fun ChatViewModel.restoreStickyCredentialMemory() {
    val entries = providerRepository.loadStickyKeys()
    if (entries.isEmpty()) return
    groupRouter.restoreStickyKeys(entries)
    AppLogger.info(ChatViewModel.TAG, "sticky credential memory restored: ${entries.size} entries")
}

/**
 * Rotate the offloaded stream across this entry's credentials.
 *
 * The returned flow is cold and behaves exactly like the plain offloaded flow
 * when the entry carries a single credential (index 0 → the historical slot,
 * no rotation, no notice, no memory write) — the multi-key feature is a pure
 * superset of the old behaviour, and single-key providers are byte-for-byte
 * unchanged on the wire.
 *
 * Success → the serving credential is remembered (sticky memory) so the next
 * request on this entry starts there.
 *
 * Failure → recorded against the credential's composite route id
 * ([GroupRouter.routeId]) so ONLY the spent key is parked; the entry's other
 * credentials keep serving. The router keys its health map by that composite
 * id, so this reuses the existing cooldown / circuit / half-open machinery
 * without touching it.
 */
internal fun ChatViewModel.streamChatTurnWithRotation(
    provider: LLMProvider,
    messages: List<LLMMessage>,
    systemPrompt: String?,
    maxTokens: Int,
    temperature: Double?,
    imageParts: List<LLMMessage.ImagePart>,
    tools: List<AgentToolDefinition>,
    thinkingLevel: ThinkingLevel,
): Flow<LLMStreamChunk> = flow {
    val instance = provider.instanceContext
        ?: throw ModelStreamErrorException(
            "no provider instance context for remote execution",
            hadChunks = false,
        )
    val entryId = compositeEntryKey(instance.id, provider.model.id)
    val keyCount = instance.credentialCount
    var index = groupRouter.preferredCredential(entryId, keyCount)

    while (true) {
        var emittedContent = false
        try {
            streamChatTurnOffloaded(
                provider = provider,
                messages = messages,
                systemPrompt = systemPrompt,
                maxTokens = maxTokens,
                temperature = temperature,
                imageParts = imageParts,
                tools = tools,
                thinkingLevel = thinkingLevel,
                credentialIndex = index,
            ).collect { chunk ->
                if (chunk !is LLMStreamChunk.QueueStatus) emittedContent = true
                emit(chunk)
            }
            // Success: sticky memory + clear the credential's demotion.
            groupRouter.rememberCredential(entryId, index, keyCount)
            if (keyCount > 1) {
                groupRouter.recordResult(
                    groupRouter.routeId(entryId, index),
                    RouteOutcome.Success,
                )
            }
            return@flow
        } catch (e: Throwable) {
            val eligible = errorIsCredentialScoped(e)
            val next = if (!emittedContent && eligible) {
                groupRouter.rotateCredential(entryId, index, keyCount)
            } else {
                null
            }
            if (next == null) {
                if (eligible) {
                    // Park ONLY the spent credential. The bare entry keeps its
                    // health record — the engine's fallback loop records the
                    // entry-level demotion when it gets this failure, and a
                    // sibling key that could have served must not be taken
                    // down with the one that was spent.
                    groupRouter.recordResult(
                        groupRouter.routeId(entryId, index),
                        when (e) {
                            is LLMError.QuotaExhausted -> RouteOutcome.QuotaExhausted
                            is LLMError.RateLimited -> RouteOutcome.RateLimited(e.retryAfterMs)
                            is LLMError.InvalidApiKey -> RouteOutcome.AuthError
                            else -> RouteOutcome.ServerError
                        },
                    )
                    // Persist the sticky memory BEFORE rethrowing, so a user
                    // who retries manually lands on the credential that last
                    // worked instead of paying the same doomed attempt again.
                    providerRepository.saveStickyKeys(groupRouter.stickyKeysSnapshot())
                }
                throw e
            }
            // ── Rotate ──
            AppLogger.info(
                ChatViewModel.TAG,
                "credential rotate on $entryId: slot $index -> $next",
            )
            notifyCredentialRotated(index, next, keyCount)
            index = next
        }
    }
}

/**
 * True when a stream failure actually implicates the CREDENTIAL (as opposed
 * to the endpoint, the network, or the model). Rotation is cheap — one header
 * change — so the predicate is deliberately narrow:
 *
 *  - `true`  → RateLimited / InvalidApiKey / QuotaExhausted.
 *  - `false` → ProviderError / TransientError / NetworkError.
 *
 * [T-rotate-on-any-error] NOTE: this is the gate for rotating *silently*
 * (the user sees the key switch in the top bar only via the notice). A
 * credential-scoped error is the only one that says anything about the
 * credential; a 5xx says nothing about it, so rotating on one would just burn
 * the sibling credentials of an endpoint that was down for everyone — and
 * every key of that endpoint would go dark while only ONE was actually at
 * fault. The cheap retry before a model switch is the group fallback's job.
 */
private fun errorIsCredentialScoped(t: Throwable): Boolean {
    var cur: Throwable? = t
    var depth = 0
    while (cur != null && depth < 6) {
        when (cur) {
            is LLMError.RateLimited,
            is LLMError.InvalidApiKey,
            is LLMError.QuotaExhausted,
            -> return true
        }
        cur = cur.cause
        depth++
    }
    return false
}
