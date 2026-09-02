package com.openminis.app.ui.chat

// [FE-5 batch 4/5] Context-window management extracted verbatim from
// ChatViewModel as extension functions: token estimation (char-based BPE
// approximations), disk offload of large tool outputs, and the hard-cap
// turn-granularity trim. Same pattern as ChatViewModelUiStateExt — the
// functions still operate on the VM's own agentHistory / appendSystemInfo /
// context members, only their file location changed. No logic change.

import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import com.openminis.app.R
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

internal fun ChatViewModel.estimateContextTokens(): Int {
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
internal fun ChatViewModel.countPartTokens(part: AgentContentPart): Int = when (part) {
    is AgentContentPart.Text -> BPETokenizer.countTokens(part.text)
    is AgentContentPart.ToolUse -> BPETokenizer.countTokens(part.input.toString())
    is AgentContentPart.ToolResult -> {
        BPETokenizer.countTokens(part.content) +
            (part.imageData?.let { BPETokenizer.countImageTokens(it) } ?: 0)
    }
    is AgentContentPart.ImageData -> BPETokenizer.countImageTokens(part.data)
}

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
    AppLogger.info(ChatViewModel.TAG, "  Candidates: ${candidates.size} parts (~$totalCandidateTokens tokens total)")
    AppLogger.info(ChatViewModel.TAG, "  Skipped: $skippedAlreadyOffloaded already offloaded, $skippedTooSmall too small")

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
internal fun ChatViewModel.estimateContextHistoryTokens(): Int {
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

internal fun ChatViewModel.estimateHistoryTokens(messages: List<LLMMessage>): Int {
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
