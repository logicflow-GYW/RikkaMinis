package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.db.compositeEntryKey
import com.rikkaminis.app.data.model.AgentToolDefinition
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
    // One shot per credential per turn (source feature's bound) — the cheap
    // rung must stay cheap; without it a health-blind router walk could spin.
    var rotationsDone = 0

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
            // Single-credential instances keep the pre-rotation behavior
            // byte-for-byte: no health record, no sticky write, no notice.
            val credentialKind = credentialScopedKind(e)?.takeIf { keyCount > 1 }
            if (credentialKind != null) {
                // Park the credential that just failed BEFORE asking for a
                // successor. [GroupRouter.rotateCredential] walks the health
                // map, so an unrecorded failure means the walk can hand the next
                // attempt straight back to this same slot (two keys: A → B → A
                // bounce) — the sibling the user paid for would never get a
                // second look. Park ONLY this credential: the bare entry keeps
                // its health record, because a sibling key that could still
                // serve must not be taken down with the one that was spent.
                groupRouter.recordResult(
                    groupRouter.routeId(entryId, index),
                    credentialOutcomeFor(credentialKind, e),
                )
            }
            val decision = decideCredentialRotation(
                kind = credentialKind,
                emittedContent = emittedContent,
                rotationsDone = rotationsDone,
                keyCount = keyCount,
            ) {
                groupRouter.rotateCredential(entryId, index, keyCount)
            }
            if (decision !is RotationDecision.Rotate) {
                if (credentialKind != null) {
                    // Persist the sticky memory BEFORE rethrowing, so a user
                    // who retries manually lands on the credential that last
                    // worked instead of paying the same doomed attempt again.
                    providerRepository.saveStickyKeys(groupRouter.stickyKeysSnapshot())
                }
                throw e
            }
            // ── Rotate ──
            val next = decision.nextIndex
            rotationsDone++
            // Sticky memory migrates WITH the rotation (GroupRouter's KDoc: "on
            // turn success AND on every rotation"). Recording only the success
            // path made the memory a stale trap: after a rotation the map still
            // pointed at the spent slot, and preferredCredential re-validates
            // health at use time, so the next turn would start on the dead key
            // and burn one attempt before finding the sibling again.
            groupRouter.rememberCredential(entryId, next, keyCount)
            AppLogger.info(
                ChatViewModel.TAG,
                "credential rotate on $entryId: slot $index -> $next (kind=$credentialKind, rotations=$rotationsDone/$keyCount)",
            )
            notifyCredentialRotated(index, next, keyCount)
            index = next
        }
    }
}
