package com.rikkaminis.app.ui.chat

// [FE-5 batch 4/5] Context-window management extracted verbatim from
// ChatViewModel as extension functions: token estimation (char-based BPE
// approximations), disk offload of large tool outputs, and the hard-cap
// turn-granularity trim. Same pattern as ChatViewModelUiStateExt — the
// functions still operate on the VM's own agentHistory / appendSystemInfo /
// context members, only their file location changed. No logic change.

import com.rikkaminis.app.data.BPETokenizer
import com.rikkaminis.app.data.ContextOffload
import com.rikkaminis.app.data.ContextPolicy
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.R
import org.json.JSONObject

/** Keep at least this many newest complete turns when hard-trimming (verbatim from VM companion). */
internal const val MIN_CONTEXT_TURNS_TO_KEEP = 6

/** Offload candidate descriptor (verbatim from the old VM private nested class). */
private data class OffloadCandidate(
    val msgIdx: Int,
    val partIdx: Int,
    val tokens: Int,
    val bytes: Int,
    val toolId: String,
    val toolName: String,
)

internal fun ChatViewModel.estimateContextTokens(): Int = estimateHistoryTokens(agentHistory)

/**
 * [audit-0920] Single source of truth for the char-based token scale.
 *
 * These two helpers are the ONLY place the "how many tokens is this part"
 * conversion is defined. They exist because the same conversion used to be
 * written twice with different divisors — the budget side
 * ([estimateHistoryTokens]) divides by 3.5, while the offload candidate
 * ranking ([countPartTokens]) divided by 3. The offload loop starts
 * `currentTokens` from the budget scale and then subtracts candidate-scale
 * values, so it believed it had freed ~1.167× more than it had (3.5/3) and
 * stopped while the real budget was still ~17% above target — the opposite of
 * "offload fires strictly before the compact line". Both sides now read the
 * same divisor through these helpers.
 */
private fun partCharContribution(part: AgentContentPart): Int = when (part) {
    is AgentContentPart.Text -> part.text.length
    is AgentContentPart.ToolUse -> part.input.toString().length
    is AgentContentPart.ToolResult -> part.content.length
    is AgentContentPart.ImageData -> 0
}

/** Image-token half of the same scale (grid-cell heuristic, not char-based). */
private fun partImageContribution(part: AgentContentPart): Int = when (part) {
    is AgentContentPart.ToolResult -> part.imageData?.let { BPETokenizer.countImageTokens(it) } ?: 0
    is AgentContentPart.ImageData -> BPETokenizer.countImageTokens(part.data)
    else -> 0
}

/**
 * Approximate token count for a single agent content part. Used to rank
 * offload candidates by size. Shares [partCharContribution] /
 * [partImageContribution] with [estimateHistoryTokens] so the two scales can
 * never drift apart again.
 */
internal fun ChatViewModel.countPartTokens(part: AgentContentPart): Int =
    (partCharContribution(part) / 3.5).toInt() + partImageContribution(part)

/**
 * [fix/offload-payload-stub] Whether [part] may be replaced, in the history
 * sent to the provider, by a disk stub of the form
 * `[CONTEXT OFFLOADED] … saved to: <path>`.
 *
 * Only **observations** are eligible. Replacing a large `ToolResult` (or a
 * bare image) with a stub is lossless in the sense that matters: the stub
 * names a path, the bytes are on disk, and a model that needs them again can
 * `file_read` them.
 *
 * `ToolUse` parts are **not** eligible. Their arguments are *instructions*,
 * and for `file_write` the `content` argument **is the payload** — not a
 * reference to one. Stubbing it puts a pointer where the data belongs, and
 * the model's own history then reads "the content I sent last time was this
 * stub text", which it reproduces the next time it plans the same write.
 *
 * Field evidence (2026-09-14, session 051f8698, build 1.0.0+1522):
 * a `file_write` of `SensitiveCommandPolicy.kt` carried
 * `content = "[CONTEXT OFFLOADED] Content (~1103 tokens, 3936 bytes) saved
 * to: …/file_write_0lBaMiNz5926.txt"`; the tool reported `success=true` and
 * the file held ~150 bytes instead of 3714. That session had 23 `file_write`
 * payloads offloaded and 97 parts already stubbed — i.e. 23 chances per day
 * to silently truncate a file. The old rule also read `input["content"]` for
 * both tools, but `file_edit` carries `old_string`/`new_string`, so that half
 * never matched anything.
 *
 * Trade-off, deliberate: large `file_write` payloads now stay in the context
 * window. They are still reclaimable at whole-turn granularity by
 * [trimContextHistoryWindow] and auto-compaction, which drop turns instead of
 * rewriting an instruction's payload. Correctness of a write beats the token
 * saving — and [com.rikkaminis.app.data.OffloadedPayloadGuard] is the second
 * line of defence in case a stub reaches a payload slot anyway.
 */
internal fun isOffloadEligible(part: AgentContentPart): Boolean = when (part) {
    is AgentContentPart.ToolResult ->
        part.content.length > 500 || (part.imageData?.size ?: 0) > 1024
    is AgentContentPart.ImageData -> part.data.size > 1024
    is AgentContentPart.ToolUse -> false
    is AgentContentPart.Text -> false
}

/**
 * [fix/offload-protected-tools] Tools whose output must NEVER be offloaded —
 * parity with OpenCode's compaction `PRUNE_PROTECTED_TOOLS = ["skill"]`.
 * These tools' results are reference material the agent is expected to keep
 * consulting verbatim; stubbing them swaps knowledge for a pointer and the
 * only recovery is another tool call (a full turn of latency + tokens).
 *
 * ponytail: name-list protection, not a per-tool capability axis |
 * 天花板: a tool whose output is huge AND disposable would waste window
 * space by staying in context | 升级触发: a session where memory lookups
 * dominate the context budget → make the list configurable per tool.
 */
internal val OFFLOAD_PROTECTED_TOOLS = setOf("memory_get")

/** Pure check: is this ToolResult protected from offload/prune by tool name? */
internal fun isProtectedToolResult(toolName: String?): Boolean =
    toolName != null && toolName in OFFLOAD_PROTECTED_TOOLS

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
internal fun ChatViewModel.offloadContextIfNeeded(
    contextWindow: Int,
    lastContextTokens: Int,
    force: Boolean = false,
) {
    val sid = activeSessionId
    // [T-adaptive-compact-reserve] Same shifted policy the compact decision
    // uses, so offload always fires strictly before the compact line it is
    // meant to feed (a compact that summarises un-offloaded tool output is both
    // expensive and lossy — see effectiveContextPolicy's KDoc).
    val policy = effectiveContextPolicy(contextWindow)

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

    AppLogger.info(ChatViewModel.TAG, "━━━ Context Offload Triggered ━━━")
    AppLogger.info(ChatViewModel.TAG, "  Window: $contextWindow tokens")
    AppLogger.info(ChatViewModel.TAG, "  Before: $beforeTokens tokens ($pct% of window, ~$remaining remaining)")
    if (force) {
        AppLogger.info(ChatViewModel.TAG, "  Mode: FORCE — offloading all eligible candidates")
    } else {
        AppLogger.info(ChatViewModel.TAG, "  Threshold: ${policy.offloadThreshold} → Target: $targetTokens")
        AppLogger.info(ChatViewModel.TAG, "  Need to free: ~${beforeTokens - targetTokens} tokens")
    }
    AppLogger.info(ChatViewModel.TAG, "  Agent history: ${agentHistory.size} messages")

    val protectedCount = minOf(4, agentHistory.size)
    val candidateUpper = agentHistory.size - protectedCount
    AppLogger.info(ChatViewModel.TAG, "  Scanning messages 0..<$candidateUpper (last $protectedCount protected)")

    val candidates = mutableListOf<OffloadCandidate>()
    var skippedAlreadyOffloaded = 0
    var skippedTooSmall = 0
    // [fix/offload-protected-tools] Counted separately from `too small`:
    // protected tool results are excluded by *name*, not by size.
    var skippedProtected = 0
    // [fix/offload-payload-stub] Counted separately from `too small`: these are
    // tool-call payloads, which are excluded by *kind*, not by size.
    var skippedPayloadParts = 0

    for (msgIdx in 0 until candidateUpper) {
        val msg = agentHistory[msgIdx]
        for ((partIdx, part) in msg.contentParts.withIndex()) {
            when (part) {
                is AgentContentPart.ToolResult -> {
                    if (part.content.startsWith(ContextOffload.OFFLOADED_PREFIX)) {
                        skippedAlreadyOffloaded++
                        continue
                    }
                    // [fix/offload-protected-tools] Protected tool results are
                    // never offload candidates, regardless of size — counted
                    // separately so the reason is greppable in logs.
                    if (isProtectedToolResult(part.name)) {
                        skippedProtected++
                        continue
                    }
                    if (!isOffloadEligible(part)) {
                        skippedTooSmall++
                        continue
                    }
                    val tokens = countPartTokens(part)
                    val bytes = part.content.toByteArray(Charsets.UTF_8).size +
                        (part.imageData?.size ?: 0)
                    candidates.add(OffloadCandidate(msgIdx, partIdx, tokens, bytes, part.id, part.name))
                }
                // [fix/offload-payload-stub] ToolUse parts are deliberately NOT
                // offload candidates any more — see [isOffloadEligible] for the
                // field evidence and the reasoning. Kept as an explicit arm (not
                // a silent `continue`) so the reason is greppable.
                is AgentContentPart.ToolUse -> {
                    skippedPayloadParts++
                    continue
                }
                is AgentContentPart.ImageData -> {
                    if (!isOffloadEligible(part)) {
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
    AppLogger.info(ChatViewModel.TAG, "  Candidates: ${candidates.size} parts (~$totalCandidateTokens tokens total)")
    AppLogger.info(ChatViewModel.TAG, "  Skipped: $skippedAlreadyOffloaded already offloaded, $skippedTooSmall too small, $skippedPayloadParts tool-call payloads (never offloaded), $skippedProtected protected tool results")

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
                // [fix/offload-payload-stub] Unreachable by construction:
                // ToolUse parts are never added as candidates ([isOffloadEligible]
                // returns false for them). Kept as an explicit dead arm rather
                // than deleted so that anyone re-introducing payload offload has
                // to argue with this comment — and so the sealed `when` stays
                // exhaustive without an `else` that could swallow a new arm.
                //
                // History: this arm rewrote the model's own `file_write.content`
                // argument into a `[CONTEXT OFFLOADED] …` stub. The model then
                // reproduced that stub as the payload of a later write, silently
                // truncating a 3714-byte file to ~150 bytes while file_write
                // reported success. It also read `input["content"]` for both
                // tools, but `file_edit` carries `old_string`/`new_string`, so
                // that half never matched anything.
                null
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
            ChatViewModel.TAG,
            "  ✂ Offloaded #$offloadedCount: [${candidate.toolName}] id:${candidate.toolId.take(8)} ~${candidate.tokens} tokens (${candidate.bytes} bytes) → $linuxPath [now $currentTokens ($afterPct%)]",
        )
}

    if (offloadedCount > 0) {
        val afterPct = (currentTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
        AppLogger.info(ChatViewModel.TAG, "━━━ Context Offload Complete ━━━")
        AppLogger.info(ChatViewModel.TAG, "  Parts offloaded: $offloadedCount")
        AppLogger.info(ChatViewModel.TAG, "  Tokens freed: ~$freedTokens")
        AppLogger.info(ChatViewModel.TAG, "  Before: $beforeTokens/$contextWindow ($pct%)")
        AppLogger.info(ChatViewModel.TAG, "  After:  $currentTokens/$contextWindow ($afterPct%)")
        AppLogger.info(ChatViewModel.TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    // [T-ctx-offload-escalation] Record how much of the target this pass
    // actually delivered. In a long session the candidate pool runs dry —
    // every large tool result already carries a stub and what remains is
    // conversation text the offloader structurally cannot touch — so the pass
    // frees (almost) nothing, still costs a full scan, and the context keeps
    // climbing until the much higher compact line is reached. Measured on
    // 2026-09-14: 35 consecutive triggers freed 0–8.5 k tokens against a
    // 25–51 k shortfall over 25 minutes; auto-compact eventually fired at 84 %
    // of the window. The flag lets the loop escalate on the same turn instead
    // of waiting for that line.
    val neededTokens = (beforeTokens - targetTokens).coerceAtLeast(0)
    offloadUnderDelivered = neededTokens > 0 && freedTokens < neededTokens / 2
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
internal fun ChatViewModel.trimContextHistoryWindow(
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
        ChatViewModel.TAG,
        "[ContextTrim] dropped $droppedCount messages (~$droppedTokens tokens) to fit $contextWindow limit; " +
        "history ${kept.size + droppedCount}→${kept.size} msgs, kept $keepTurns newest turn(s)"
)
    appendSystemInfo(
        text = context.getString(R.string.sysmsg_context_trimmed, contextWindow, droppedCount),
        iconKind = "compact",
)
}
internal fun ChatViewModel.estimateContextHistoryTokens(): Int = estimateHistoryTokens(agentHistory)

internal fun ChatViewModel.estimateHistoryTokens(messages: List<LLMMessage>): Int {
    var totalChars = 0
    var imageTokens = 0
    for (msg in messages) {
        for (part in msg.contentParts) {
            // [audit-0920] Same helpers as [countPartTokens] — the offload loop
            // mixes this scale with the candidate scale, so the two MUST use
            // one conversion (see the KDoc on those helpers).
            totalChars += partCharContribution(part)
            imageTokens += partImageContribution(part)
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
internal fun ChatViewModel.findTurnStartIndexFromEnd(turnsToKeep: Int): Int {
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
