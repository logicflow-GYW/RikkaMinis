package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Psychology
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.R

// [FE-5 batch 8] Slash command + session-token cluster extracted verbatim from
// ChatViewModel as top-level data classes + extension functions (receiver
// ChatViewModel). Same pattern as ChatPromptAndTools / ChatErrorHandling.


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

/** Read-only view of the current thinking configuration for the model. */
fun ChatViewModel.thinkingInfo(): ThinkingInfo? {
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
suspend fun ChatViewModel.loadSessionTokenStats(): SessionTokenStats {
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
fun ChatViewModel.executeSlashCommand(cmd: SlashCommand, currentInput: String = ""): String {
    val saved = savedInputBeforeSlash
    // [T-skill-slash a88ea8f9] Skill rows aren't directly executable —
    // they're a typing aid. Fill the composer with the literal slash
    // command; the user then taps Send and the model handles the skill via
    // the existing SKILL.md fragment injection in runAgentLoop.
    if (cmd.isSkill) {
        AppLogger.info(ChatViewModel.TAG, "[Slash] tap skill id=${cmd.id} title=${cmd.title} → composer fill only")
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
    AppLogger.info(ChatViewModel.TAG, "[Slash] tap id=${cmd.id} title=${cmd.title} streaming=${_isStreaming.value} compacting=${_isCompacting.value}")
    savedInputBeforeSlash = null
    _showSlashMenu.value = false
    _slashMenuSelectedIndex.value = -1

    when (cmd.id) {
        "memory" -> toggleMemoryEnabled()
        "clear" -> _clearChatConfirmRequested.value = true
        else -> AppLogger.info(ChatViewModel.TAG, "[Slash] unrecognized id=${cmd.id} — no dispatch")
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
private fun ChatViewModel.toggleMemoryEnabled() {
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
internal fun ChatViewModel.toggleThinking() {
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
fun ChatViewModel.setThinkingLevel(level: ThinkingLevel) {
    if (!currentModelSupportsReasoning) return
    // [T-android-thinking-level-arch] Double-safety clamp: the composer UI
    // already filters to availableThinkingLevels, but never fully trust the
    // caller — cap to the current model's ceiling so a stale/over-range
    // request can't persist a level the model can't reach.
    // [T-thinking-auto-level] AUTO is exempt: vendor-default, not an intensity.
    val ceiling = currentModelMaxThinkingLevel
    val clamped = if (level == ThinkingLevel.AUTO || level.rank <= ceiling.rank) level else ceiling
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
private fun ChatViewModel.persistThinkingOverride(level: ThinkingLevel) {
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
fun ChatViewModel.tryExecuteInputAsSlashCommand(text: String): Boolean {
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
