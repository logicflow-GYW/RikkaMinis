package com.openminis.app.ui.chat

// [FE-5 batch 7] Streaming-delta drain cluster extracted verbatim from
// ChatViewModel as extension functions (same pattern as ChatPromptAndTools).

internal fun ChatViewModel.effectiveContent(id: String): String? {
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
internal fun ChatViewModel.flushStreamingDelta(id: String) {
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
internal fun ChatViewModel.flushAllStreamingDeltas() {
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
