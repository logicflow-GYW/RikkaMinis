package com.openminis.app.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.openminis.app.agent.runtime.AgentRunEvent
import com.openminis.app.agent.runtime.AgentRunPhase
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.offload.ProviderExecutionGateway
import com.openminis.app.tools.AgentTraceRecorder
import com.openminis.app.tools.FileEditTool
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.FileWriteTool
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.tools.SubagentSkill
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// [FE-5 batch 6] Tool dispatch / prompt assembly / attachment prep /
// title generation cluster extracted verbatim from ChatViewModel as
// extension functions (same pattern as ChatSessionLifecycle / ChatToolExecutors).

internal fun ChatViewModel.streamFlushThrottleMs(len: Int): Long = when {
    len < 500 -> 200L
    len < 2_000 -> 300L
    len < 32_000 -> 500L
    len < 64_000 -> 1_000L
    len < 128_000 -> 1_500L
    else -> 2_000L
}

/**
 * Composer draft. Owned by VM so it survives navigation (e.g. push EnvVars
 * and pop back) — `ChatViewModelStore` keeps the VM alive across screen
 * pushes, but `remember { … }` inside `ChatScreen` does not. Mirrors iOS
 * `AIChatView` which binds against `vm.inputText`.
 */

/**
 * Apply the request-level image-byte budget to a fully-resolved
 * message list before handing it to a provider. Images that don't
 * fit under [ImageBudget.MAX_REQUEST_BYTES] (oldest first) are
 * replaced in-place with a text placeholder that, when the original
 * bytes were offloaded to disk, points the model back to the linux
 * path so it can re-fetch via `read_image` if needed. Images that
 * never had a linuxPath are spilled to
 * `attachments/spillover/<sha1>.<ext>` lazily so the placeholder
 * still carries an addressable reference.
 *
 * Returns the budgeted message list. When nothing was elided this
 * is the same instance as [messages].
 *
 * Emits a one-shot [requestBudgetEvent] for the UI Snackbar so the
 * user knows older images were compacted into placeholders.
 */
internal fun ChatViewModel.applyRequestImageBudget(messages: List<LLMMessage>): List<LLMMessage> {
    // Collect every image in chronological order so the planner can
    // walk in reverse and protect the most recent images.
    data class ImageRef(val msgIdx: Int, val partIdx: Int, val image: ImageBudget.BudgetImage)
    val images = mutableListOf<ImageRef>()
    messages.forEachIndexed { mi, msg ->
        msg.contentParts.forEachIndexed { pi, part ->
            when (part) {
                is AgentContentPart.ImageData -> {
                    images.add(
                        ImageRef(
                            mi, pi,
                            ImageBudget.BudgetImage(part.data, part.linuxPath, part.mimeType),
                        )
                    )
                }
                is AgentContentPart.ToolResult -> {
                    val img = part.imageData
                    if (img != null) {
                        images.add(
                            ImageRef(
                                mi, pi,
                                ImageBudget.BudgetImage(
                                    img,
                                    part.imageLinuxPath,
                                    part.imageMimeType ?: "image/jpeg",
                                ),
                            )
                        )
                    }
                }
                else -> Unit
            }
        }
    }
    if (images.isEmpty()) return messages

    val plan = ImageBudget.planRequestBudget(images.map { it.image })
    if (!plan.mutated) return messages

    // For dropped images without a linuxPath, lazily spill to disk so
    // the placeholder still gives the model an addressable reference.
    val attachmentsRoot = activeSessionId?.let { sid ->
        java.io.File(context.filesDir, "minis-sessions/$sid/attachments")
    }
    val resolvedPaths = HashMap<ImageBudget.ImagePartId, String?>()
    for (ref in images) {
        val id = ImageBudget.ImagePartId.of(ref.image.data)
        if (id !in plan.droppedIds) continue
        val existing = ref.image.linuxPath
        if (existing != null) {
            resolvedPaths[id] = existing
        } else if (attachmentsRoot != null) {
            resolvedPaths[id] = ImageBudget.ensureSpillover(
                attachmentsRoot, ref.image.data, ref.image.mimeType,
            )
        } else {
            resolvedPaths[id] = null
        }
    }

    // Build a new message list with dropped image parts replaced by
    // text placeholders. Same-message multiple drops collapse cleanly
    // because we never touch parts whose ids weren't in droppedIds.
    val byMsg = images.groupBy { it.msgIdx }
    val mutated = messages.toMutableList()
    for ((mi, refs) in byMsg) {
        val msg = mutated[mi]
        val newParts = msg.contentParts.toMutableList()
        for (ref in refs) {
            val id = ImageBudget.ImagePartId.of(ref.image.data)
            if (id !in plan.droppedIds) continue
            val path = resolvedPaths[id]
            val placeholder = AgentContentPart.Text(ImageBudget.elidedImagePlaceholder(path))
            val originalPart = newParts[ref.partIdx]
            newParts[ref.partIdx] = when (originalPart) {
                is AgentContentPart.ImageData -> placeholder
                is AgentContentPart.ToolResult -> originalPart.copy(
                    // Strip the bytes but keep the structural ToolResult
                    // role; append the elision marker into content so
                    // the model sees it next to the rest of the tool
                    // output. linux path remains in the part for any
                    // subsequent diagnostic round-trip.
                    imageData = null,
                    imageMimeType = null,
                    content = originalPart.content +
                        (if (originalPart.content.isEmpty()) "" else "\n") +
                        ImageBudget.elidedImagePlaceholder(path),
                )
                else -> originalPart
            }
        }
        mutated[mi] = msg.copy(contentParts = newParts)
    }

    _requestBudgetEvent.tryEmit(plan)
    AppLogger.info(
        ChatViewModel.TAG,
        "applyRequestImageBudget: dropped=${plan.droppedCount}/${plan.totalCount} keptBytes=${plan.keptBytes}B elidedBytes=${plan.elidedBytes}B",
    )
    return mutated
}


/** Latest in-memory compact marker, used by [effectiveAgentHistory] to
 * resolve boundaries the same way iOS `cachedLatestMarker` does. Refreshed
 * on every compactAll write and on session reload. */
@Volatile

/**
 * Show a transient error on the last assistant message while keeping isStreaming=true
 * so the "thinking" indicator and streaming UI stay intact during auto-retry countdowns.
 * Mirrors iOS streamWithAutoRetry: `chatMessage?.error = desc` without dropping the loop.
 */
internal fun ChatViewModel.setTransientInlineError(errorText: String) {
    val msgs = _messages.value.toMutableList()
    val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
    if (lastAssistantIdx < 0) return
    val msg = msgs[lastAssistantIdx]
    msgs[lastAssistantIdx] = msg.copy(error = errorText)
    _messages.value = msgs
}

/** Clear any inline error on the last assistant message (used after successful retry). */
internal fun ChatViewModel.clearInlineError() {
    val msgs = _messages.value.toMutableList()
    val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
    if (lastAssistantIdx < 0) return
    val msg = msgs[lastAssistantIdx]
    if (msg.error == null) return
    msgs[lastAssistantIdx] = msg.copy(error = null, errorDetail = null)
    _messages.value = msgs
    // [T-error-persist-android] Clear the persisted sticker too, so a
    // recovered turn doesn't resurrect the error banner on the next reload.
    // Clear by the message's source DB rows when known (the in-memory bubble
    // maps to one or more persisted rows via sourceDbIds); fall back to the
    // last-assistant-row update otherwise.
    val sid = realSessionId.ifEmpty { sessionId }
    if (sid.isNotEmpty()) {
        val dbIds = msg.sourceDbIds
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (dbIds.isNotEmpty()) {
                    dbIds.forEach { chatRepository.updateMessageErrorInfo(it, null) }
                } else {
                    chatRepository.updateLastAssistantError(sid, null)
                }
            } catch (e: Exception) { Log.w(ChatViewModel.TAG, "clear error_info failed: ${e.message}") }
        }
    }
}

/**
 * [T-error-persist-android] Fire-and-forget: clear the persisted error
 * sticker on the session's last assistant row. Called from the resume / retry
 * entrypoints that drop the in-memory error but don't go through
 * [clearInlineError], so a recovered turn can't merge-resurrect the old
 * banner on the next reload. No-op when there's no session/row yet.
 */

/**
 * Instance entry point used by the tool-dispatch path. The real logic lives
 * in the companion so tests can reach it without a ChatViewModel.
 */
internal fun ChatViewModel.preflightValidateToolCall(
    name: String,
    args: JSONObject,
    tools: List<AgentToolDefinition>,
): String? = preflightValidateToolCallImpl(name, args, tools)

internal suspend fun ChatViewModel.executeTool(
    name: String,
    argsJson: String,
    toolId: String,
    toolBlocks: MutableList<AssistantBlock>,
    assistantId: String,
    currentText: String,
): ToolExecutionResult {
    // T330: tri-state permission gating moved into the offload IPC
    // handler (OffloadGate). The CLIs land there whether the LLM
    // emitted a named tool call or a raw shell command, so the gate
    // is consistent across both paths. The pre-check that lived here
    // (`permissionTools = {calendar, location, …}`) was effectively
    // dead since these tools have no native ChatViewModel executor
    // — they always fall through to shell_execute or the offload
    // bridge, which is now where checkPermission runs.
    val toolTitle = try { JSONObject(argsJson).optString("tool_title", name) } catch (_: Exception) { name }

    // T9: record tool call event
    val etStartMs = System.currentTimeMillis()
    traceObserver.agentTraceRecorder.toolCall(traceObserver.activeTraceTurn, toolId, name, argsJson)
    // T7-A: 观察 —— 工具调用消耗 tool_calls 预算（advisory，不阻断）
    // T7-C: tool_calls 预算耗尽 → 不执行工具，返回明确错误给 LLM（不是静默失败）
    if (!traceObserver.t7ConsumeAndTrace(AgentTraceRecorder.DIMENSION_TOOL_CALLS) { it.consumeToolCall() }) {
        traceObserver.t7BudgetStopReason = "tool_call_limit"
        // T7-D: 旁路验证 —— 计数耗尽进入收尾
        traceObserver.t7Reduce(AgentRunEvent.ProcessInterrupted("budget_exhausted(tool_calls)"))
        return ToolExecutionResult("Error: Agent budget exhausted (tool_calls)", false, toolTitle = toolTitle)
    }
    traceObserver.t7State(ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.EXECUTING_TOOLS), ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.EXECUTING_TOOLS), "ToolStarted($name)")
    // T7-D: 旁路验证 —— 工具开始
    traceObserver.t7Reduce(AgentRunEvent.ToolStarted(name))

    // T7-B: tool slot lease —— 工具执行期间占用一个并发工具槽位
    // （budget 的 tryAcquireToolSlot，advisory），trace 侧登记 acquire；
    // finally 无条件 release（成功/异常/取消都释放，不泄漏槽位）。
    val toolLease = "tool-$toolId-${traceObserver.activeRunId ?: "norun"}"
    traceObserver.activeRunBudget?.tryAcquireToolSlot()
    traceObserver.t7ResourceAcquire(
        resourceType = AgentTraceRecorder.RESOURCE_TOOL_SLOT,
        resourceId = toolId,
        leaseToken = toolLease,
    )
    val result = try {
        when (name) {
            FileReadTool.NAME -> {
                val result = FileReadTool.execute(argsJson, activeSessionId, context)
                // Record skill usage when SKILL.md under /var/minis/skills/<id>/ is read.
                if (result.success) {
                    runCatching {
                        val readPath = JSONObject(argsJson).optString("path", "")
                        if (readPath.isNotEmpty()) {
                            skillRepository?.skillIdFromPath(readPath)?.let { sid ->
                                skillRepository.recordSkillUse(sid)
                            }
                        }
                    }
                }
                result
            }
            FileWriteTool.NAME -> FileWriteTool.execute(argsJson, activeSessionId, context).also {
                if (it.success) maybeReloadSkillsForPath(argsJson)
            }
            FileEditTool.NAME -> FileEditTool.execute(argsJson, activeSessionId, context).also {
                if (it.success) maybeReloadSkillsForPath(argsJson)
            }
            // T178: pass sessionId + context so read_image routes through
            // resolveSessionHostPath like file_read/write/edit do — without
            // these, the tool consults the global last-writer-wins
            // bindMounts map and would surface another session's
            // /var/minis/{workspace,attachments,offloads,browser} files.
            ReadImageTool.NAME -> ReadImageTool.execute(argsJson, activeSessionId, context)
            "shell_execute" -> executeShellCommand(argsJson, toolId, toolBlocks, assistantId, currentText)
            "browser_use" -> executeBrowserUseTool(argsJson)
            "memory_write" -> executeMemoryWriteTool(argsJson)
            "memory_get" -> executeMemoryGetTool(argsJson)
            "memory_rollup" -> executeMemoryRollupTool()
            // [T7-subagent] spawn_agent: delegate to an independent sub-agent
            // instance running the named skill.
            SubagentSkill.NAME -> executeSpawnAgentTool(argsJson)
            else -> ToolExecutionResult("Unknown tool: $name", false)
        }
    } finally {
        // T7-B: 无条件释放 tool slot —— 覆盖成功、普通异常、CancellationException
        traceObserver.activeRunBudget?.releaseToolSlot()
        traceObserver.t7ResourceRelease(
            resourceType = AgentTraceRecorder.RESOURCE_TOOL_SLOT,
            resourceId = toolId,
            leaseToken = toolLease,
            releasedBy = AgentTraceRecorder.RELEASED_FINALIZE,
        )
    }

    // T9: record tool result event
    traceObserver.agentTraceRecorder.toolResult(
        turn = traceObserver.activeTraceTurn,
        toolId = toolId,
        name = name,
        success = result.success,
        output = result.output,
        durationMs = System.currentTimeMillis() - etStartMs,
    )
    // T7-A: 观察 —— 工具结束（ToolFinished 语义）
    traceObserver.t7State(ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.EXECUTING_TOOLS), ChatAgentTraceObserver.t7PhaseSchema(AgentRunPhase.EXECUTING_TOOLS), "ToolFinished($name)")
    // T7-D: 旁路验证 —— 工具结束（resultKnown = 结果已到达）
    traceObserver.t7Reduce(AgentRunEvent.ToolFinished(name, resultKnown = true))

    // T3: failure-learning automation hook. Side-channel only — the
    // failed result still flows to the LLM exactly as before; this just
    // appends a structured, deduplicated block to the session's
    // `.learnings/ERRORS.md` so later agent turns can learn from it
    // without relying on the agent remembering to check the skill.
    if (!result.success) {
        runCatching {
            toolFailureHook.recordFailure(name, result.output, argsJson, activeSessionId)
        }
        // Deliberately swallowed: a logging failure must never break the
        // tool-result path back to the model.
    }
    return result
}

/**
 * [T7-subagent] Execute [SubagentSkill.NAME] — spawn an independent
 * sub-agent using the named skill. The sub-agent gets its own system
 * prompt (from the skill body), a filtered tool set, and runs its own
 * independent loop with its own budget. Context is fully isolated from
 * the main agent's [agentHistory].
 */
internal suspend fun ChatViewModel.executeSpawnAgentTool(argsJson: String): ToolExecutionResult {
    val args = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
    val skillName = args.optString("skill_name", "").trim()
    val query = args.optString("query", "").trim()
    val title = args.optString("tool_title", "Sub-agent").ifBlank { "Sub-agent" }

    if (skillName.isBlank()) {
        return ToolExecutionResult("Error: spawn_agent requires 'skill_name'", false, toolTitle = title)
    }
    if (query.isBlank()) {
        return ToolExecutionResult("Error: spawn_agent requires 'query'", false, toolTitle = title)
    }

    val repo = skillRepository ?: return ToolExecutionResult(
        "Error: Skill system unavailable", false, toolTitle = title,
    )

    // 1. Look up the skill
    val skill = repo.skills.value.find {
        it.name == skillName || it.id == skillName || it.name.equals(skillName, ignoreCase = true)
    } ?: return ToolExecutionResult(
        "Error: Skill '$skillName' not found. Make sure it is installed and the name is correct.",
        false, toolTitle = title,
    )

    if (!repo.isEnabledForSession(skill.id, activeSessionId)) {
        return ToolExecutionResult(
            "Error: Skill '$skillName' is disabled for this session",
            false, toolTitle = title,
        )
    }

    // 2. Parse subagent config
    val config = SubagentSkill.parseSubagentConfig(skill)
    if (!config.isSubagent) {
        return ToolExecutionResult(
            "Error: Skill '$skillName' is not a sub-agent skill. " +
                "Add `subagent: true` to its SKILL.md frontmatter to enable sub-agent mode.",
            false, toolTitle = title,
        )
    }

    // 3. Build filtered tool set
    val subagentTools = SubagentSkill.buildFilteredTools(agentTools, config.allowedTools)
    if (subagentTools.isEmpty()) {
        return ToolExecutionResult(
            "Error: Skill '$skillName' has no usable tools (all filtered out by forbidden/allowlist)",
            false, toolTitle = title,
        )
    }

    // 4. Build system prompt + history
    val systemPrompt = SubagentSkill.buildSystemPrompt(skill)
    val provider = currentProvider ?: return ToolExecutionResult(
        "Error: No active provider available", false, toolTitle = title,
    )

    // 5. Run the sub-agent loop (extracted engine). Guard first: the
    // original returned this exact error when the provider had no
    // instance context (setup failure, not a mid-run error).
    if (provider.instanceContext == null) {
        return ToolExecutionResult(
            "Error: No provider instance context for sub-agent remote execution",
            false, toolTitle = title,
        )
    }
    return com.openminis.app.ui.chat.runSubagentLoop(
        skillName = skillName,
        query = query,
        title = "Sub-agent: ${skill.name}",
        config = config,
        systemPrompt = systemPrompt,
        streamProvider = { messages ->
            val instance = provider.instanceContext!!
            com.openminis.app.sandbox.offload.ProviderExecutionGateway.stream(
                context = context,
                instance = instance,
                model = provider.model,
                messages = messages,
                systemPrompt = systemPrompt,
                maxTokens = config.maxOutputTokens,
                temperature = null,
                tools = subagentTools,
                thinkingLevel = ThinkingLevel.OFF,
            )
        },
        executeSubTool = { name, subArgs -> executeSubagentTool(name, subArgs) },
        log = { AppLogger.warning(ChatViewModel.TAG, it) },
    )
}

/**
 * [T7-subagent] Execute a tool inside a sub-agent's loop. Mirrors the
 * main [executeTool] dispatch but without UI updates (toolBlocks,
 * assistantId, etc.) — the sub-agent produces file/memory results only.
 * Tools that are FORBIDDEN for sub-agents never reach this method
 * because [SubagentSkill.buildFilteredTools] excludes them.
 */
internal fun ChatViewModel.executeSubagentTool(name: String, argsJson: String): ToolExecutionResult = when (name) {
    FileReadTool.NAME -> FileReadTool.execute(argsJson, activeSessionId, context)
    FileWriteTool.NAME -> FileWriteTool.execute(argsJson, activeSessionId, context).also {
        if (it.success) maybeReloadSkillsForPath(argsJson)
    }
    FileEditTool.NAME -> FileEditTool.execute(argsJson, activeSessionId, context).also {
        if (it.success) maybeReloadSkillsForPath(argsJson)
    }
    ReadImageTool.NAME -> ReadImageTool.execute(argsJson, activeSessionId, context)
    "memory_write" -> executeMemoryWriteTool(argsJson)
    "memory_get" -> executeMemoryGetTool(argsJson)
    "memory_rollup" -> executeMemoryRollupTool()
    else -> ToolExecutionResult("Error: Unknown or forbidden tool: $name", false)
}

/**
 * Mirror of iOS AIChatViewModel post-tool hook (Agent/Chat/AIChatViewModel.swift:5387 / :5408):
 * when the agent writes or edits a SKILL.md inside a `/skills/` directory
 * we ask SkillRepository to re-scan disk so the new skill is visible
 * immediately, without waiting for app restart.
 */
/**
 * Persist a tool-failure block into this session's `.learnings/ERRORS.md`
 * (host path resolved via PRootKernel so it lands in the session's own
 * workspace, not the global bind-mount map). Mirrors OmniBot writing into
 * its app data dir: the RikkaMinis equivalent of "app data" is the
 * per-session workspace, which the agent's shell can read back.
 */
internal fun ChatViewModel.appendToolFailureBlock(block: String) {
    runCatching {
        val file = PRootKernel.resolveSessionHostPath(
            activeSessionId,
            "/var/minis/workspace/.learnings/ERRORS.md",
            context,
        ) ?: return
        file.parentFile?.mkdirs()
        file.appendText(block)
    }
}

internal fun ChatViewModel.maybeReloadSkillsForPath(argsJson: String) {
    com.openminis.app.ui.chat.maybeReloadSkillsForPath(argsJson, skillRepository)
}

internal suspend fun ChatViewModel.executeShellCommand(
    argsJson: String,
    toolId: String,
    toolBlocks: MutableList<AssistantBlock>,
    assistantId: String,
    currentText: String,
): ToolExecutionResult {
    // T7-A: 观察 —— shell 命令消耗 shell_commands 预算（advisory，不阻断）
    // T7-C: shell_commands 预算耗尽 → 不执行命令，返回明确错误
    if (!traceObserver.t7ConsumeAndTrace(AgentTraceRecorder.DIMENSION_SHELL_COMMANDS) { it.consumeShellCommand() }) {
        return ToolExecutionResult("Error: Agent budget exhausted (shell_commands)", false)
    }
    // T7-B: shell lease —— 执行期间占用 shell 资源，finally 无条件释放
    // （覆盖成功、异常、取消路径，不泄漏 shell 槽位）
    val shellLease = "shell-${toolId}-${traceObserver.activeRunId ?: "norun"}"
    traceObserver.t7ResourceAcquire(
        resourceType = AgentTraceRecorder.RESOURCE_SHELL,
        resourceId = "shell_execute",
        leaseToken = shellLease,
    )
    // FE-5 route B: the engine owns bashism detection / coordinator
    // dispatch / URL brokering / env redaction; this wrapper only owns
    // the T7 lease lifecycle and the toolBlocks UI side channel.
    try {
        resetDisplayBuffer(toolId)
        val result = try {
            executeShellCommandEngine(
                argsJson = argsJson,
                dispatchSessionId = activeSessionId,
                context = context,
                toolKey = toolId,
                onBlockUpdate = { displayContent ->
                    val idx = toolBlocks.indexOfFirst { it.id == toolId }
                    if (idx >= 0) {
                        toolBlocks[idx] = toolBlocks[idx].copy(content = displayContent)
                        viewModelScope.launch(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, currentText, true, toolBlocks)
                        }
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolExecutionResult("Error: ${e.message}", false)
        } finally {
            resetDisplayBuffer(toolId)
        }
        return ToolExecutionResult(
            output = result.output,
            success = result.success,
            toolTitle = result.toolTitle,
            timedOut = result.timedOut,
        )
    } finally {
        // T7-B: 无条件释放 shell lease —— 覆盖成功、异常、取消路径
        traceObserver.t7ResourceRelease(
            resourceType = AgentTraceRecorder.RESOURCE_SHELL,
            resourceId = "shell_execute",
            leaseToken = shellLease,
            releasedBy = AgentTraceRecorder.RELEASED_FINALIZE,
        )
    }
}

internal suspend fun ChatViewModel.executeBrowserUseTool(argsJson: String): ToolExecutionResult =
    com.openminis.app.ui.chat.executeBrowserUseTool(
        argsJson = argsJson,
        tabPool = browserTabPool,
        artifactWriter = { filename, data ->
            persistBrowserArtifact(filename, data)
        },
        resizeJpeg = { raw, edge -> resizeJpegToMaxEdge(raw, edge) },
    )

/**
 * Write bytes to <filesDir>/minis-sessions/<sessionId>/browser/<filename>.
 * That directory is bind-mounted to `/var/minis/browser/` so the agent can
 * read it back via file_read / file_write / minis:// URLs.
 * Returns the host absolute path on success, null otherwise.
 */
internal fun ChatViewModel.persistBrowserArtifact(filename: String, data: ByteArray): String? =
    com.openminis.app.ui.chat.persistBrowserArtifact(activeSessionId, filename, data, context)

internal fun ChatViewModel.linuxPathToMinisURL(path: String): String? =
    com.openminis.app.ui.chat.linuxPathToMinisURL(path)

/**
 * Resize a JPEG so its longest edge is at most `maxEdge` px. Returns null
 * if already within bounds. Mirrors iOS AIChatViewModel.resizedImageData.
 */
internal fun ChatViewModel.resizeJpegToMaxEdge(data: ByteArray, maxEdge: Int): ByteArray? {
    val bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
    val longest = maxOf(bmp.width, bmp.height)
    if (longest <= maxEdge) { bmp.recycle(); return null }
    val scale = maxEdge.toFloat() / longest
    val w = (bmp.width * scale).toInt()
    val h = (bmp.height * scale).toInt()
    val resized = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
    bmp.recycle()
    val out = java.io.ByteArrayOutputStream()
    resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
    resized.recycle()
    return out.toByteArray()
}

internal fun ChatViewModel.executeMemoryWriteTool(argsJson: String): ToolExecutionResult {
    val repo = memoryRepository ?: return ToolExecutionResult("Error: Memory not available", false)
    return com.openminis.app.ui.chat.executeMemoryWriteTool(argsJson, repo, _memoryEnabled.value) { record ->
        _memoryToolRecords.value = _memoryToolRecords.value + record
    }
}

internal fun ChatViewModel.executeMemoryGetTool(argsJson: String): ToolExecutionResult {
    val repo = memoryRepository ?: return ToolExecutionResult("Error: Memory not available", false)
    return com.openminis.app.ui.chat.executeMemoryGetTool(argsJson, repo) { record ->
        _memoryToolRecords.value = _memoryToolRecords.value + record
    }
}

// [T6-rollup] On-demand memory rollup: distills the previous day's daily
// log into MEMORY-ROLLUP.md. Uses the same memory dir as the repository.
internal fun ChatViewModel.executeMemoryRollupTool(): ToolExecutionResult {
    val repo = memoryRepository ?: return ToolExecutionResult("Error: Memory not available", false)
    return com.openminis.app.ui.chat.executeMemoryRollupTool(repo) { record ->
        _memoryToolRecords.value = _memoryToolRecords.value + record
    }
}

// ─── UI Helpers ──────────────────────────────────────────────────────


internal fun ChatViewModel.updateAssistantMessage(
    id: String,
    content: String,
    isStreaming: Boolean,
    toolBlocks: List<AssistantBlock>,
    isAwaitingModelResponse: Boolean = false,
) {
    // T-streaming-side-channel: during a live turn, write high-frequency
    // fields into [_streamingById] instead of mutating the canonical
    // message list. This keeps the `messages` StateFlow reference stable
    // across the turn so ChatScreen's top-level reads
    // (`messages.any/.associate/.isNotEmpty/.lastOrNull`) don't trigger
    // a full recompose of the 8980-line composable on every token.
    //
    // On stream end (isStreaming=false), drain the accumulated delta
    // back into the canonical message in a single `_messages` emit, then
    // clear the side-channel entry so post-turn reads (history rebuild,
    // persist, agent loop) see the canonical truth.
    if (isStreaming) {
        val toolBlocksImmutable = toolBlocks.toList()

        // [T-android-stream-flush-dualpath] Dual-path flush at the
        // message-accumulation layer (NOT per-fragment, which never
        // throttled). Decide whether to publish this delta now:
        //   • structural change (toolBlocks count / awaiting flag) →
        //     publish immediately — these drive tool-bubble UI and must
        //     never be coalesced away or the bubble state stalls.
        //   • else time-path: enough ms since last publish for this length.
        //   • else newline fast-path: a line break in the newly-streamed
        //     chunk + ≥50 new chars, gated to short docs (iOS parity).
        // When none fire, stash the latest as a trailing publish so the
        // final chunk before a pause still lands; a fresh delta cancels
        // and replaces it.
        val st = streamFlushStates.getOrPut(id) {
            StreamFlushState().also { it.lastFlushedLen = 0 }
        }
        val prev = _streamingById.value[id]
        // [T-android-stream-flush-review] Structural change also covers an
        // in-place tool-block STATUS flip (running → success), not just a
        // count change — otherwise a spinner→checkmark could lag up to one
        // throttle tier. Compare a cheap (kind,status) fingerprint.
        val toolStatusChanged = prev != null &&
            prev.toolBlocks.size == toolBlocksImmutable.size &&
            toolBlocksImmutable.indices.any { i ->
                prev.toolBlocks[i].toolStatus != toolBlocksImmutable[i].toolStatus
            }
        val structuralChange = prev == null ||
            prev.toolBlocks.size != toolBlocksImmutable.size ||
            prev.isAwaitingModelResponse != isAwaitingModelResponse ||
            toolStatusChanged
        val now = System.currentTimeMillis()
        val elapsed = now - st.lastFlushMs
        val throttle = streamFlushThrottleMs(content.length)
        val newChunk = if (content.length > st.lastFlushedLen) {
            content.substring(st.lastFlushedLen.coerceAtMost(content.length))
        } else ""
        val unflushed = content.length - st.lastFlushedLen
        val newlineFlush = content.length < ChatViewModel.NEWLINE_FLUSH_MAX_LEN &&
            newChunk.contains('\n') &&
            unflushed >= ChatViewModel.NEWLINE_FLUSH_MIN_CHARS

        fun publish(text: String, blocks: List<AssistantBlock>, awaiting: Boolean) {
            // [T-streamlining-thinking-fix] Monotonic terminal guard: a tool
            // block published in a terminal state (SUCCESS/FAILED/TIMEOUT/
            // CANCELLED) must never regress to an alive state (RUNNING/
            // STREAMING/PENDING) in a later snapshot — otherwise the tool card
            // can get stuck "being called" indefinitely. Reads prev blocks
            // fresh from the side-channel (not the outer `prev`, which may be
            // stale across trailing publishes).
            val prevBlocks = _streamingById.value[id]?.toolBlocks
            val guarded = ToolBlockMonotonicGuard.guard(prevBlocks, blocks)
            guarded.regressions.forEach { r ->
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "ToolMonotonic block id=${r.blockId} regressed " +
                        "${r.prevStatus} -> ${r.nextStatus} (messageId=$id); clamped",
                )
            }
            _streamingById.value = _streamingById.value + (
                id to StreamingDelta(
                    content = text,
                    toolBlocks = guarded.blocks,
                    isAwaitingModelResponse = awaiting,
                    epoch = streamEpoch,
                )
            )
            st.lastFlushMs = System.currentTimeMillis()
            st.lastFlushedLen = text.length
        }

        if (structuralChange || elapsed >= throttle || newlineFlush) {
            st.trailingJob?.cancel()
            st.trailingJob = null
            st.pendingContent = null
            publish(content, toolBlocksImmutable, isAwaitingModelResponse)
        } else {
            // Throttled: always record this delta as the freshest pending
            // value, so whenever the trailing job fires it publishes the
            // latest text — not whatever was captured when it was first
            // scheduled (review #2). Schedule the job only once.
            st.pendingContent = content
            st.pendingBlocks = toolBlocksImmutable
            st.pendingAwaiting = isAwaitingModelResponse
            if (st.trailingJob == null) {
                val wait = (throttle - elapsed).coerceAtLeast(16L)
                st.trailingJob = viewModelScope.launch {
                    kotlinx.coroutines.delay(wait)
                    val pc = st.pendingContent
                    if (pc != null) {
                        publish(pc, st.pendingBlocks, st.pendingAwaiting)
                        st.pendingContent = null
                    }
                    st.trailingJob = null
                }
            }
        }
        // [T-android-timeout-while-running] If a transient banner
        // (`message.error`) is still on the canonical assistant message
        // when a fresh streaming event arrives, the banner is stale —
        // the model is producing again, by construction the prior
        // transient timeout / retry / fallback has been resolved.
        // Clear it in the same mutation. setTransientInlineError /
        // setInlineError are the only paths that write `error`; the
        // terminal path (setInlineError) sets isStreaming=false on the
        // same message in the same emit, so it cannot reach this
        // branch and the clear is safe.
        //
        // 𝙓𝙄𝙉 TG36302 (0.10): user saw a red "timeout / retry" banner
        // glued to the bottom of the conversation while the agent
        // continued running (LM Studio tool loop on 30/30, "Minis is
        // thinking" indicator). Caused by (a) the fallback-switch branch in
        // runAgentLoop not calling clearInlineError(), and (b) the
        // streaming-side-channel writing every subsequent delta into
        // _streamingById without ever touching _messages where
        // `error` lives. (a) is fixed at the fallback site; (b) is
        // fixed here defensively so any future write-path that forgets
        // to clear can't strand a stale banner across the rest of
        // the turn.
        val canonical = _messages.value
        val canonicalIdx = canonical.indexOfLast { it.id == id }
        if (canonicalIdx >= 0 && canonical[canonicalIdx].error != null) {
            val updated = canonical.toMutableList()
            updated[canonicalIdx] = canonical[canonicalIdx].copy(error = null)
            _messages.value = updated
        }
        return
    }
    // [T-android-stream-flush-dualpath] Stream end → cancel any pending
    // trailing flush and drop the throttle accumulator for this message;
    // the canonical drain below publishes the final, complete text.
    clearStreamFlushState(id)
    // Stream end → sync delta into canonical message + clear side-channel.
    val current = _messages.value
    val idx = current.indexOfLast { it.id == id }
    if (idx < 0) {
        // The message itself is gone (e.g. clearChat raced ahead) —
        // just clear any leftover stream delta and bail.
        if (_streamingById.value.containsKey(id)) {
            _streamingById.value = _streamingById.value - id
        }
        return
    }
    val updated = current.toMutableList()
    updated[idx] = current[idx].copy(
        content = content,
        isStreaming = false,
        toolBlocks = toolBlocks.toList(),
        isAwaitingModelResponse = isAwaitingModelResponse,
    )
    _messages.value = updated
    if (_streamingById.value.containsKey(id)) {
        _streamingById.value = _streamingById.value - id
    }
}

/**
 * Read a message's content + toolBlocks honoring any active streaming
 * delta. Use this from non-render code that needs the "current" view of
 * a message during a live turn (e.g. agent history builders, persistence
 * snapshots) without forcing the render layer to consult the delta map.
 */

/**
 * Build the "内置集成" prompt fragment: the list of bundled platform
 * skills (semantic-memory / github-ops / cloudflare-fullright-ops)
 * with their *current* capability tier, derived from each skill's
 * `requirements.json`.
 *
 * Unlike the static `<available_skills>` block, this tells the agent
 * what it can actually do right now — so it never needs to guess from
 * trial-and-error whether a token is configured before attempting an
 * operation. Returns null when no bundled platform skill applies to
 * this session.
 */
internal fun ChatViewModel.buildIntegrationStatus(): String? {
    val repo = skillRepository ?: return null
    val platformIds = listOf("semantic-memory", "github-ops", "cloudflare-fullright-ops")
    val rows = mutableListOf<String>()

    for (id in platformIds) {
        val skill = repo.skills.value.find { it.id == id } ?: continue
        if (!repo.isEnabledForSession(id, activeSessionId)) continue
        val reqs = repo.loadSkillRequirements(id)
        val tier = determineIntegrationTier(reqs)
        val declaredVars = reqs?.env?.keys ?: emptySet()
        val foundVars = envVarsSnapshot().keys.intersect(declaredVars)
        // A platform is only "available" if it defines an explicit
        // capability description for its current tier. If the tier key
        // is absent (e.g. no entry for "0"), the platform has no
        // capability at that tier — don't label it "zero-config usable".
        val ops = reqs?.tiers?.get(tier.toString())
        // Diagnostic: surface exactly which declared env vars were found
        // in the store at prompt-build time, so a "以为配了却显示需配置"
        // mismatch is greppable in logcat instead of being a silent guess.
        AppLogger.info(
            ChatViewModel.TAG,
            "[IntegrationStatus] ${skill.name}: tier=$tier " +
                "declared=${declaredVars.sorted()} found=${foundVars.sorted()} " +
                "hasCapability=${ops != null} enabled=${repo.isEnabledForSession(id, activeSessionId)}"
        )
        if (ops == null) {
            // No capability described for this tier → no free tier.
            rows.add("| ${skill.name} | 🔒 需配置 | 暂无可用能力（未定义 Tier $tier 能力） |")
            continue
        }
        val status = when (tier) {
            2 -> "✅ 完整"
            1 -> "⚠️ 只读"
            else -> "⚡ 零配置"
        }
        rows.add("| ${skill.name} | $status | $ops |")
    }

    if (rows.isEmpty()) return null

    return buildString {
        append("## 内置集成\n\n")
        append("以下平台技能已内置，无需手动安装。Tier 0 零配置即可使用；Tier 1/2 需配置对应环境变量（Settings → Environments 或 minis-config envvars）。\n\n")
        append("| 集成 | 状态 | 可用操作 |\n")
        append("|------|------|--------|\n")
        rows.forEach { append(it).append("\n") }
        append("\n")
        append("使用涉及环境变量的操作前，请先检查对应变量是否已设置。")
    }
}
/**
 * Map a platform skill's `requirements.json` to a tier 0/1/2, based on
 * which of its declared env vars are present in the app's environment
 * config. Every requirement present → Tier 2; a partial subset → Tier 1;
 * none → Tier 0. A skill with no declared `env` stays at Tier 0 (its
 * operations are all zero-config).
 */
internal fun ChatViewModel.determineIntegrationTier(reqs: com.openminis.app.data.repository.SkillRepository.SkillRequirements?): Int {
    val declared = reqs?.env?.keys ?: return 0
    if (declared.isEmpty()) return 0
    val configured = countConfiguredEnvVars(declared)
    return when {
        configured == declared.size -> 2
        configured > 0 -> 1
        else -> 0
    }
}

/**
 * Count how many of [names] exist as configured environment variables.
 * Reads from minis-config's envvar store (the sandbox's exported env)
 * — a variable exists iff it is non-blank. Robust: never throws; a
 * missing/unreadable store counts every var as unconfigured.
 */
internal fun ChatViewModel.countConfiguredEnvVars(names: Set<String>): Int {
    if (names.isEmpty()) return 0
    return try {
        val env = envVarsSnapshot()
        names.count { env.containsKey(it) && !env[it].isNullOrBlank() }
    } catch (e: Exception) {
        Log.w(ChatViewModel.TAG, "countConfiguredEnvVars: ${e.message}")
        0
    }
}

/**
 * Read the current environment-variable store as a snapshot map.
 * Queries [com.openminis.app.data.repository.EnvVarRepository] which is
 * the same encrypted store the sandbox injects. Only keys with a non-null
 * stored value are returned — configured-but-blank vars don't count as
 * present. Values are read internally by the repo but never surfaced
 * outside `countConfiguredEnvVars` (we only test `containsKey`).
 */
internal fun ChatViewModel.envVarsSnapshot(): Map<String, String> =
    try {
        // Reuse the app-wide singleton (wired in MinisApp.onCreate via
        // EnvVarRedactor.envVarRepository) instead of constructing a fresh
        // EnvVarRepository per call. A fresh instance re-runs loadMetadata()
        // (JSON parse + StateFlow rebuild) on every snapshot, which is pure
        // duplicated IO; the singleton caches metadata in its StateFlow and
        // reads the same encrypted prefs. Fallback keeps headless/debug
        // callers (ChatMutationMethods / HeadlessChatRunner) working even
        // before the singleton is wired.
        val repo = com.openminis.app.data.EnvVarRedactor.envVarRepository
            ?: com.openminis.app.data.repository.EnvVarRepository(context)
        repo.allAsDict()
    } catch (e: Exception) {
        Log.w(ChatViewModel.TAG, "envVarsSnapshot: ${e.message}")
        emptyMap()
    }

internal fun ChatViewModel.buildSystemPrompt(): String? {
    // Cache-friendly layout: keep `base` byte-stable by stripping out anything
    // that varies per request, then append a "Runtime context" suffix at the
    // very end with all the dynamic bits (date, timezone, locale, configured
    // minis-model-use count). OpenAI / DeepSeek prompt caching is prefix-
    // based, so the longer the static head, the better the hit rate.
    // Pre-T122 the prompt embedded `Current time: yyyy-MM-dd HH:mm` mid-base,
    // which guaranteed cache misses across minute boundaries — even a quick
    // follow-up could land on a different minute and pay full ingestion.
    val today = java.time.LocalDate.now()
    val dateStr = today.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
    val tzId = java.util.TimeZone.getDefault().id
    val lang = context.resources.configuration.locales[0].toLanguageTag()

    // Count of agent-loop-visible models for the `minis-model-use` CLI
    // (exposed as a shell command via the native_offload handler).
    val modelUseCount = try { providerRepository.resolvedAgentLoopEntries().size } catch (_: Exception) { 0 }

    // [T-soul-md] Layer 1 is rendered by SystemPromptBuilder, which
    // owns the "You are <name>, a capable AI assistant running on an
    // Android device ..." identity sentence (parametric on SOUL.md's
    // `name` field) and optionally appends a clearly-labeled
    // Personality section from SOUL.md's body. The original wording
    // is preserved inside SystemPromptBuilder.IDENTITY_TEMPLATE so we
    // don't regress model behavior that depended on it. When SOUL.md
    // has no personality body, identitySection() returns the identity
    // sentence with its original single trailing space — the full
    // assembled prompt then matches the pre-SOUL prompt byte-for-byte.
    val identitySection = com.openminis.app.agent.SystemPromptBuilder.identitySection(context)
    // [T-memory-toggle-gates-injection-and-tools-android] Mirror the iOS
    // gate: when memory is disabled for this session, replace the
    // "memory_write / memory_get" tool bullets and the "Memory system:"
    // guidance block with a single explicit DISABLED notice. The model
    // never sees the tools either (filtered in agentTools above), but
    // surfacing the state in the prompt lets it explain why memories
    // aren't reachable when the user asks. The fragment / tool dual
    // gate is symmetrical: enable both or disable both, never mismatch.
    val memoryOn = _memoryEnabled.value
    val toolListMemoryBullets = if (memoryOn) {
        """
- memory_write: Save a memory entry to today's daily log (YYYY-MM-DD.md). Use proactively to note user preferences, project patterns, and important context.
- memory_get: Recall memories with keyword search. Check memory at the start of new topics to leverage past knowledge.
- memory_rollup: On-demand memory distillation — run this when daily logs are large to extract stable rules into MEMORY-ROLLUP.md. Idempotent."""
    } else {
        // Empty — no memory_write / memory_get bullets when disabled.
        // The "Memory system:" section below also collapses, so the
        // model gets a coherent picture rather than half-mentioned
        // tools it can't actually call.
        ""
    }
    val memorySystemSection = if (memoryOn) {
        """

Memory system (currently ENABLED):
- memory_write writes to today's daily log (YYYY-MM-DD.md) — use it for session notes, key facts, project context, things learned, and action items.
- memory_rollup: Distill stable rules from the previous day's daily log into MEMORY-ROLLUP.md. Call this when daily logs are growing large — it surfaces reusable knowledge concisely. Idempotent; skips dates already rolled up.
- GLOBAL.md (/var/minis/memory/GLOBAL.md) stores persistent preferences, settings, and general-purpose conventions. To read it, use file_read (NOT memory_get). To update it, use file_read first then file_edit. If GLOBAL.md does not exist yet, use file_write to create it directly.
- IMPORTANT: Only write to GLOBAL.md when the user explicitly asks (e.g. 'remember this globally', 'save to global memory'). Before editing, deduplicate and clean up — avoid ambiguity, repetition, or daily-log-style entries. GLOBAL.md should contain only concise, reusable knowledge (preferences, settings, conventions), NOT session logs or transient context.
- Use memory_get to recall past knowledge before starting tasks — check if there are relevant memories that can help.
- Proactively save memories (via memory_write to daily log) when you discover user preferences or important patterns — don't wait to be asked.
- When the user says 'remember this' or similar, use memory_write to persist to the daily log. Only write to GLOBAL.md if the user specifically asks for global/persistent storage.
- What NOT to remember: passwords, API keys, tokens, secrets, or any sensitive credentials. Warn the user about the risk first; only proceed if they explicitly confirm.
- Keep memories concise, factual, and general-purpose — avoid noise that won't be useful later."""
    } else {
        """

Memory system (currently DISABLED):
- The user has turned OFF memory injection and memory tools for this session. GLOBAL.md and recent daily logs are NOT included in this prompt, and the memory_write / memory_get tools are NOT available — do not attempt to call them.
- If the user asks why earlier memories aren't visible, or asks you to save something, tell them memory is currently disabled and point them at the /memory slash command or [Settings → Memory](minis://settings/memory) to re-enable it.
- SOUL.md (personality / identity) is unaffected by this toggle; the persona section above still applies."""
    }
    val base = identitySection + """You should proactively use shell commands to accomplish the user's tasks — installing packages (apk add), writing and running scripts, managing files, networking, and any other operations a Linux terminal can perform.

Available tools:
- shell_execute: Run any shell command. Each invocation is an isolated process with stdout/stderr captured. Prefer this for most tasks — it is a real Linux environment with persistent filesystem. Common tools (python3, pip, curl, wget, git, ssh, etc.) can be installed via apk add; Python packages via pip install. Use `which <cmd>` to check if a tool is already installed before running apk add — many packages persist across sessions. When you need to wait before checking results (e.g. polling, waiting for a process), use the `delay` parameter instead of `sleep` in the command — delay blocks the agent flow without occupying the shell, so other concurrent tasks can use it during the wait. This avoids resource contention. Execution discipline for long-running or dispatched work: make tool calls immediately instead of describing intentions, and keep working until the task is complete. Without a scheduler or timed-callback tool, `delay` is your ONLY wait mechanism within a turn — to follow up on something still running, chain delay-then-check calls at a task-appropriate interval until you have the result or hit a sensible retry cap. NEVER end a turn with a promise of future action: 'I'll keep monitoring', 'will sync the result later', and ending right after a single still-running status check with 'let's keep waiting' are all the same violation — once your turn ends, NOTHING runs until the user's next message. If polling to completion is genuinely not worth blocking the turn, close honestly instead: state that the task keeps running in the background, that you will only learn its outcome when the user next messages (or they ask you to check), and — if something must fire on a schedule beyond this conversation — point them to the options under 'Scheduled tasks' later in this prompt (native alarm reminder or a system-level schedule; those notify the USER, they do not wake you).
- file_read: Read file contents (faster than cat).
- file_write: Create new files or overwrite existing files (faster than echo/tee).
- file_edit: Edit existing files with exact string replacement (old_string → new_string). Preferred over file_write for modifications — always file_read first.
- browser_use: Web browsing (navigate, screenshot, click, type, get_text, scroll, scroll_and_collect, get_readable, get_backbone, fetch, etc.). Starts with a desktop Chrome user agent. Use screenshot to see the page.
  当 browser_use 触达 Google 登录 / OAuth 页（accounts.google.com、signin.google.com、myaccount.google.com、oauth2.googleapis.com 等）或网页返回 "disallowed_useragent" / 403 包含 "browser is not secure" 字样时，**不要重试或尝试登录** — Google 永久禁止 in-app WebView 完成登录，重试只会浪费 turn。改为告诉用户："此页面需要在系统 Chrome 完成登录" 并给出可点击的 Markdown link [在 Chrome 中打开](https://accounts.google.com/...)。点该 link 时 app 会跳出 Custom Tab；用户在 Chrome 完成操作后，请他**把所需结果（邮件正文 / 文档摘要 / 表格数据）粘贴回 chat**，你再继续帮他处理。这是 Android 平台限制，不是 bug。${toolListMemoryBullets}

Shared directory /var/minis/ (bidirectional read/write between shell and app):
  /var/minis/attachments/ — Media files (images, audio, video). Display inline with ![desc](minis://attachments/filename).
  /var/minis/workspace/   — Working files (scripts, data, configs). Link with [name](minis://workspace/filename).
  /var/minis/offloads/    — Auto-saved large outputs. Read with file_read.
  /var/minis/browser/     — Browser screenshots and extracts.
  /var/minis/shared/      — Cross-session shared storage for artifacts and documents. Organize by project or topic (e.g. shared/myproject/, shared/datasets/). Do NOT store temporary files here.
  /var/minis/memory/GLOBAL.md    — Persistent global memory (read-only, user-maintained via Settings).
  /var/minis/memory/YYYY-MM-DD.md — Daily memory log.
  /var/minis/mounts/<name>/      — User-mounted external folders from Settings → Mount External Folders. Presence and names vary per user; check this directory first when the task references external/user files. Some mounts may be read-only — file_write / file_edit will reject writes with a clear error message.

The minis:// URL scheme:
  minis://attachments/file.png  →  /var/minis/attachments/file.png
  minis://workspace/data.csv    →  /var/minis/workspace/data.csv
  minis://shared/project/f.txt  →  /var/minis/shared/project/f.txt

IMPORTANT: minis:// URLs are app-internal — they are NOT web URLs. Do NOT pass minis:// action URLs (open_terminal, views, settings) to browser_use — those are app deep links, use Markdown links in chat instead. However, minis:// resource URLs CAN be opened in browser_use with navigate. All directories under /var/minis/ are accessible: workspace, attachments, offloads, shared, etc. The built-in browser fully supports minis:// — HTML pages and all sub-resources (JS, CSS, images, fonts, etc.) referenced via minis:// absolute URLs or relative paths resolve correctly within the current session. When building multi-file web projects, use file_write to create files in the same directory (e.g. /var/minis/workspace/myapp/), then reference sub-resources with relative paths in HTML (e.g. <link href="style.css">, <script src="app.js">, <img src="logo.png">). The browser resolves relative paths against the minis:// base URL automatically. Cross-directory references also work with absolute minis:// URLs (e.g. <img src="minis://attachments/photo.png"> from a workspace HTML page). Navigate to the entry HTML to preview, e.g. minis://workspace/myapp/index.html.
To display a minis:// URL in chat, write it as a Markdown link or image (e.g. [name](minis://...)) — the app handles it when the user taps it.
IMPORTANT: minis:// URLs MUST be percent-encoded. Non-ASCII characters (Chinese, emoji, spaces, etc.) in filenames will break Markdown rendering if not encoded. Use the minis_url from tool results directly — it is already encoded. If you construct a minis:// URL manually, percent-encode the filename (e.g. %E4%B8%AD%E6%96%87 for non-ASCII characters).
When you write files to /var/minis/, the tool result includes a minis_url you can embed directly in Markdown.
Inline media — use the ![desc](minis://...) image syntax for ALL of images, audio, AND video. The same ![]() syntax renders an inline audio player or video player, not just images:
  - Images: ![chart](minis://attachments/chart.png)   → inline image (.png/.jpg/.gif/.webp)
  - Audio:  ![song](minis://attachments/song.mp3)     → inline audio player (.mp3/.m4a/.wav)
  - Video:  ![clip](minis://attachments/clip.mp4)     → inline video player (.mp4/.mov/.m4v)
Do NOT use the [text](url) link form for audio/video when you want them to play inline — that only produces a tappable link. Use ![]() to embed an actual player.
For non-media files, use Markdown links: [filename](minis://workspace/filename).
Tappable link previews: text/code (.py/.json/.md/etc), images, audio, video, HTML, and PDF files open native previews when the user taps a [name](minis://...) link.
Use Markdown links for all non-media minis:// files — the user can tap to preview them directly in chat.

File creation guidelines:
- Use file_write to CREATE new files. Use file_edit to MODIFY existing files. The shell is BusyBox ash: heredoc syntax (cat << EOF, python3 << 'EOF') may mis-parse braces, quotes, or special characters and execute abnormally — avoid it whenever possible, and prefer file_write over echo/printf for writing file contents. When you hit escaping or parsing errors with long inline content, write the content to a file first (file_write), then pass or execute the file (e.g. `python3 /tmp/script.py`).
- file_write and file_edit are atomic, preserve formatting, and make it easy to fix errors or update content later.
- shell_execute is for RUNNING commands, not for writing files.
- shell_execute supports multi-line commands directly — quoting and special characters are handled automatically. However, commands MUST NOT exceed 1000 characters. If longer, write a script file with file_write first, then run it.
- ICMP is blocked by the PRoot sandbox — `ping` will hang indefinitely. Use `curl` or `wget` to test network connectivity instead.
- Also (BusyBox ash, NOT bash): `**` recursive glob (globstar) is NOT supported. Use `find <dir> -name '*.ext'` for recursive file search, and pipe to `xargs` for tools like `wc`. Brace expansion ({a,b,c}) and bash arrays (arr=(...), ${'$'}{arr[@]}) are also unsupported — use space-separated strings with a for loop or multiple arguments instead.
- Python packages: many PyPI packages (numpy, pandas, scipy, pillow, etc.) lack musllinux_aarch64 wheels and will fail to build from source. Use Alpine's native packages instead: `apk search py3-<name>` then `apk add py3-numpy py3-pandas py3-matplotlib py3-pillow py3-scipy py3-requests`. Only fall back to `pip install` for pure-Python packages not available via apk. For matplotlib, always set `matplotlib.use('Agg')` before importing pyplot — there is no display server in the sandbox.
- Background services: each shell_execute runs in an isolated process. When starting a background server (e.g. `python3 -m http.server &`), you MUST redirect stdout/stderr to avoid SIGPIPE when the shell exits: `python3 -m http.server 8765 > /dev/null 2>&1 &`. Without redirection the server dies silently after the command finishes.
- File search: when looking for user files, do NOT scan the whole filesystem. Search under /var/minis/ first (workspace/attachments/shared for the current session, mounts/* for user-provided external folders). Only widen the scope if the file is clearly not under /var/minis/.

Tool call style:
- Default: do not narrate routine, low-risk tool calls — just call the tool directly.
- Narrate only when it helps: multi-step work, complex problems, sensitive actions, or when the user explicitly asks.
- Keep narration brief and value-dense; avoid repeating obvious steps.
- When a tool exists for an action, use it directly instead of explaining what you plan to do or asking the user to confirm.
- Use reasonable defaults and contextual inference to fill in missing details (e.g. 'tonight' means today, 'remind me' implies creating a reminder immediately). Only ask for clarification when genuinely ambiguous.

Tone and style:
- Reply in the language that best matches the user's input. Only switch languages when the user explicitly asks.
- Be concise. Prefer action over explanation — when the user asks for something that can be done via shell, do it directly.

Android-only tools (android-* CLIs):
CLI tools at /usr/local/bin with the `android-` prefix give you access to Android framework capabilities and on-device control. Invoke them from shell_execute like any other binary — they are already on PATH. Each tool prints JSON (or a short human-readable line) and supports --help for full usage. Tools gated by Shizuku or AccessibilityService return permission_denied when not granted — handle that gracefully and point the user at [Settings → Permissions](minis://settings/permissions).
- android-alarm — schedule alarms/timers in the system Clock app (`schedule <HH:MM> --label <L> [--repeat ONCE|DAILY|WEEKDAYS]`, `timer <seconds> --label <L>`, `open`). Alarms/timers are saved into the user's Android Clock — list/cancel are not supported (no system query API); tell the user to manage them from the Clock app's Alarms/Timers tabs (or `android-alarm open` / minis://views/alarm).
- android-calendar — read/write the device calendar (`list --start YYYY-MM-DD [--end ...] [--max N]`; `create --title <T> --start <ISO> [--end <ISO>] [--description <D>] [--location <L>] [--all-day]`).
- android-clipboard — `get | set <text> [--label L] | clear`.
- android-contacts — `list [--max N] | search <query> [--max N] | get <id> | delete <id>`. Requires READ_CONTACTS (delete also needs WRITE_CONTACTS).
- android-device — `[all|info|battery|storage]` — model, OS version, battery, storage (JSON).
- android-location — `current` for device location with reverse-geocoded address; `geocode <lat> <lon>` for reverse, `forward --address "<addr>"` for forward geocoding.
- android-notification — `send --title <T> [--body <B>] | clear | list [--max N]`. `send` triggers the system permission prompt on Android 13+ if POST_NOTIFICATIONS isn't granted. `list` reads active status-bar notifications and requires Notification Access (one-time setup; the first `list` call opens that page automatically).
- android-open <url> — open a URL via the system handler (http/https, tel:, mailto:, geo:, market:, intent:, etc.). Use this to open something immediately. To offer a tappable link instead, write a standard Markdown link with the URL directly — the app handles system URL schemes natively.
- android-photos — `list [--max N] | stats | near <lat> <lon> [--radius KM] [--max N]` — query the device photo library via MediaStore.
- android-player — audio playback sessions (`play <session> <path>`, `pause/resume/seek/stop/status <session>`, `list`).
- android-speak — device TTS (`<text> [--rate F] [--pitch F] [--volume F]`; `--stop | --status`).
- android-speech — microphone transcription (`listen [--language BCP47] [--max N] [--timeout SEC]`; `status`). Requires RECORD_AUDIO.
- android-weather <latitude> <longitude> — Open-Meteo forecast (current + hourly + daily). No API key needed.
- android-shizuku-cli — invoke privileged Android system APIs (package management, settings, system commands) via Shizuku when granted. Curated subcommands return structured JSON; for anything not covered, fall back to `android-shizuku-cli exec <any shell command>` which runs the command via `sh -c` with Shizuku privilege (same surface as `adb shell`). Run with no args (or --help) for the subcommand list.
- android-a11y-cli — drive system UI (read screen, tap, type, swipe, scroll) via the Android AccessibilityService when enabled. Run with no args (or --help) for the subcommand list.
- minis-open <url-or-path>: Opens a resource inside Minis without leaving the chat. Accepts http/https URLs (→ built-in WebKit preview) and chat-resource file paths under /var/minis/** (→ built-in file preview, routed by extension: images to the image viewer, .md to markdown preview, .html to HTML preview, .pdf/office docs to QuickLook, audio/video to the media player, else share sheet). Examples: minis-open https://example.com, minis-open /var/minis/workspace/report.md, minis-open /var/minis/attachments/chart.png. Prefer this over android-open for anything that can be previewed in-app so the user doesn't lose conversation context. Use android-open for non-web schemes (tel:, mailto:, geo:, intent:, etc.) or when the user explicitly wants the system handler.
- minis-sessions-cli: Manage chat sessions. `list` recent or by date range, `search --keywords` cross-session, `messages --id` to read, `send` to create/continue a session, `retry` to re-run, `status` to check, `open` to navigate the app UI. Run --help for full options.
- minis-model-use: Invoke other LLM models pre-configured by the user. Use `minis-model-use list` to see them (includes each model's modality capabilities like image_output, audio_output, etc.), `minis-model-use search <query>` to filter by name/provider. `minis-model-use run --model <id_or_name>` sends an OpenAI-compatible messages request; pass input via --input <json_file> or stdin, output goes to stdout or --output <path>. The OpenAI shape is the PRIMARY input for every model and modality; standard params are auto-converted to the underlying provider, so do not hand-write provider-native bodies as the primary input. For provider-specific extras the standard schema doesn't model (web-search plugins, image-to-image fields, TTS/video or other custom endpoints), escape hatches exist for OpenAI-compatible providers (they error or are ignored on Anthropic/Gemini models): `extra_body` (object merged verbatim into the request body), a custom `endpoint` path, and a top-level `passthrough` envelope for fully verbatim requests with RAW (unparsed) responses. Results may carry `warnings` (fields that were ignored/downgraded and why) and `applied_extras` (which extras actually took effect) — read them to self-correct. Run --help for the full contract before using these. Models may support multimodal output (image generation, TTS/audio, video) — check the modalities field in list output. For image_output models, pass generation params in the input JSON: top-level `n`/`size`/`quality`/`prompt` (OpenAI /images/generations style) or `generation_config.{aspect_ratio,image_size,number_of_images,person_generation}` (Gemini). Run with --help for full usage.
- minis-config: Read or change Minis settings programmatically. Run `minis-config --help` for subcommands and `minis-config topic-help <topic>` for details on a specific area. For array-valued fields (e.g. `models`, `groups`, `envvars`, `defaults.agentLoopEntries`) the `get` subcommand accepts `--filter <keywords>` (whitespace-AND, case-insensitive substring match against each element's JSON) and `--page <N> --page-size <N>` (default 20, max 100) — use these instead of dumping the full list when you only need a subset, and check the response's `pagination` / `agent_hint` fields for the next-page command. Every write triggers an in-app confirmation sheet and is logged to a revertable audit (1000-entry rolling log). After a successful change the response includes a `user_message` field — relay it (or paraphrase) so the user knows how to review or revert via Settings → Logs → Config Changes. If the call returns `permission_denied`, the user has disabled minis-config in [Settings → Permissions](minis://settings/permissions); relay that message and don't retry. You CAN add new providers and write their `apiKey` (literal string OR a `${'$'}${'$'}ENV_VAR` reference to copy from an env var at write time), but `get` never echoes API keys / OAuth tokens / env var values back — those reads return `permission_denied` by design. OAuth tokens and env var values are not settable via this tool; for an env var, point the user at [Set ENV_NAME](minis://settings/environments?create_key=ENV_NAME&create_value=) so they enter the value themselves.
Interactive terminal: minis://open_terminal opens a terminal for tasks that require interactive stdin (passwords, ssh, TUI apps like htop/vi). Write it as a Markdown link in your response — the app opens it when tapped. The optional init_command parameter pre-fills (NOT executes) a command; it MUST be fully percent-encoded (spaces → %20, & → %26, | → %7C, etc.). Only use this for genuinely interactive sessions — for everything else, use shell_execute. Examples: [Open Terminal](minis://open_terminal), [Login to SSH](minis://open_terminal?init_command=ssh%20user%40host).

Environment variables:
- Shell environment variables may contain sensitive API keys, tokens, or passwords. NEVER echo, print, cat, or otherwise output their values to stdout/stderr. Always reference them by variable name (e.g. ${'$'}API_KEY) inside scripts or commands — never inline the literal value.
- When a skill or task requires an environment variable that is not set, tell the user which variable is missing and provide a tappable deep link to create it: [Set ENV_NAME](minis://settings/environments?create_key=ENV_NAME&create_value=) — the user can tap it to open the Environment Variables page with the key pre-filled.
- Settings deep links: when you tell the user "go to Settings → X" or want to point them at a specific setting, prefer a Markdown link `[Label](minis://settings/<path>)` over plain prose. Available paths: providers (list), providers/<instanceId> (one provider), model-groups (incl. Agent Loop), model-groups/<groupId>, usage (token usage), skills, memory, storage, shared-folders (Shared Folders: /var/minis/{shared,skills,memory}), mount-external (Mount External Folders), logs, appearance, background, about, permissions, environments[?create_key=K&create_value=V[&create_note=N]], rootfs (also reachable as mirrors). Unknown paths fall back to Settings home, but prefer the exact path so users land where they want. These settings/action links are app deep links — render them as Markdown links in chat (same action-vs-resource rule as the minis:// section above: only /var/minis resource URLs may go to browser_use).
- To check if a variable is set, use `[ -n "${'$'}VAR" ] && echo 'set' || echo 'not set'`. NEVER use echo ${'$'}VAR, printenv VAR, or any command that would output the actual value into the conversation context.${memorySystemSection}
"""

    // Match iOS order exactly: skills → global memory → recent daily memory.
    // See ios/Agent/Chat/AIChatViewModel.swift:4375-4387. Each fragment is
    // appended only when non-null; absent fragments leave no separator.
    // T-skillscan: rescan disk before reading the fragment so a skill
    // that an earlier turn dropped via shell `git clone` (which bypasses
    // the file_write hook below) becomes visible on the very next user
    // turn instead of "after kill app". Cheap: loadAll is a SQLite
    // SELECT + listFiles, no network.
    skillRepository?.reloadFromDisk()
    val skillFragment = skillRepository?.skillPromptFragment(activeSessionId)
    // [T-mcp-integration-android] Re-read servers.json (the CLI / file
    // browser may have changed it out-of-band) then build the Top-20
    // enabled-MCP disclosure, injected right after the skills fragment.
    mcpRepository?.reloadFromDisk()
    val mcpFragment = mcpRepository?.mcpPromptFragment(activeSessionId)
    // Bundled platform integrations (semantic-memory / github-ops /
    // cloudflare-fullright-ops) with their current capability tier. Injected
    // right after the skills fragment so the model knows what it can do with
    // each platform before it tries anything.
    val integrationFragment = buildIntegrationStatus()
    // [T-memory-toggle-gates-injection-and-tools-android] Skip loading
    // GLOBAL.md + recent daily logs entirely when the user has turned
    // memory off for this session. Cheaper (no disk read) and — more
    // importantly — keeps the model from seeing stale persistent state
    // it can't tell the user how to manage. Skills and SOUL.md are
    // intentionally NOT gated by this toggle: skills are part of the
    // tool surface and SOUL.md is part of identity, both orthogonal
    // to the memory feature.
    val globalMemoryFragment = if (memoryOn) memoryRepository?.loadGlobalMemoryFragment() else null
    val dailyMemoryFragment = if (memoryOn) memoryRepository?.loadRecentDailyMemoryFragment() else null
    // [T6-rollup] Daily log size hint + MEMORY-ROLLUP.md injection.
    // When the largest daily log is large, suggest the agent run
    // memory_rollup to distill stable rules. MEMORY-ROLLUP.md (if it
    // exists) is injected as a compact alternative to raw logs.
    // [fix/send-prompt-bloat] Injection now goes through
    // MemoryRepository.loadRollupFragment() (tail-preferring byte cap)
    // instead of a verbatim readText() — the rollup grows monotonically
    // and previously inflated the send/retry prompt prefix unboundedly.
    val rollupSizeHint = if (memoryOn) memoryRepository?.dailyLogSizeSummary() else null
    val rollupBytes = if (memoryOn) memoryRepository?.largestDailyLogBytes() ?: 0L else 0L
    val rollupFragment = if (memoryOn) memoryRepository?.loadRollupFragment() else null

    return buildString {
        append(base)
        if (skillFragment != null) {
            append("\n\n")
            append(skillFragment)
        }
        if (integrationFragment != null) {
            append("\n\n")
            append(integrationFragment)
        }
        if (mcpFragment != null) {
            append("\n\n")
            append(mcpFragment)
        }
        if (globalMemoryFragment != null) {
            append("\n\n")
            append(globalMemoryFragment)
        }
        if (dailyMemoryFragment != null) {
            append("\n\n")
            append(dailyMemoryFragment)
        }
        // [T6-rollup] Inject MEMORY-ROLLUP.md (distilled stable rules)
        // as a compact memory fragment, plus a size hint to trigger
        // on-demand rollup when daily logs grow large.
        if (rollupFragment != null) {
            append("\n\nMemory rollup (MEMORY-ROLLUP.md — stable rules distilled from daily logs):\n")
            append(rollupFragment)
        }
        if (rollupSizeHint != null && rollupBytes >= 50_000L) {
            append("\n\nNote: Daily logs are large ($rollupSizeHint). ")
            append("memory_rollup selects the largest eligible old log that has not been distilled yet; ")
            append("call it to surface stable rules without waiting for the calendar to advance. ")
            append("It is idempotent and leaves source logs unchanged.")
        }
        // Runtime context goes last so the prefix above stays byte-stable
        // across requests within the same day. Keep ordering deterministic
        // (date → tz → lang → model count) — any reorder defeats the cache.
        append("\n\nRuntime context:\n")
        append("- Current date: ").append(dateStr).append(" (").append(tzId).append(")\n")
        append("- Device language: ").append(lang).append("\n")
        append("- minis-model-use models available: ").append(modelUseCount)
    }
}

// ─── Legacy tool execution methods (kept for compatibility) ───────────


/**
 * T209: resize image bytes for the LLM inference payload only — the
 * full-resolution original is preserved on disk (mediaStore + uploads
 * dir) so chat history fullscreen view, agent shell `cat`, and
 * `read_image` all see the user's original picture, matching iOS.
 *
 * Returns null when the source already fits within [maxEdge] (caller
 * should fall back to [rawBytes]) or on any decode/compress failure.
 */
internal fun ChatViewModel.resizeImageBytes(
    rawBytes: ByteArray,
    mimeType: String,
    maxEdge: Int = 2000,
): ByteArray? {
    return try {
        val original = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
        if (original.width <= maxEdge && original.height <= maxEdge) {
            original.recycle()
            return null
        }
        val scale = maxEdge.toFloat() / maxOf(original.width, original.height)
        val w = (original.width * scale).toInt()
        val h = (original.height * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(original, w, h, true)
        val out = ByteArrayOutputStream()
        val format = if (mimeType.contains("png")) Bitmap.CompressFormat.PNG
        else Bitmap.CompressFormat.JPEG
        scaled.compress(format, 85, out)
        if (scaled !== original) scaled.recycle()
        original.recycle()
        out.toByteArray()
    } catch (_: Exception) {
        null
    }
}

/**
 * Bundle of everything derived from a user-message's input attachments:
 * the resized in-memory image bytes for the LLM, file:// URIs of the
 * persisted copies (for stable rendering across app restarts), the
 * filenames in original attachment order (images first, then non-image
 * files — matches the rendering convention in UserAttachmentList), and
 * the mediaRef JSON parts that need to be embedded in parts_json so the
 * attachments survive a session reload (T128).
 */
internal data class PreparedAttachments(
    val imageParts: List<LLMMessage.ImagePart>,
    val imageUris: List<Uri>,
    val attachmentNames: List<String>,
    val mediaRefPartsJson: List<String>,
    // T132: iOS-parity additions so the model sees the attachment as
    // a real file in the agent's sandbox (read_image / shell_execute can
    // open these paths).
    //   imageUploadPaths: one /var/minis/attachments/uploads/<safe> per
    //     inlined image, in the same order as `imageParts`.
    //   attachedFilesXml:  null when no attachments, otherwise the
    //     <user-attached-files> XML block iOS appends to the user turn.
    val imageUploadPaths: List<String>,
    val attachedFilesXml: String?,
    // T150: file:// URIs of persisted non-image attachments, in the same
    // order as the non-image suffix of `attachmentNames`. Carried into
    // ChatMessage so the user-bubble file chip can route a tap directly
    // to FilePreviewScreen without re-resolving by filename.
    val nonImageUris: List<Uri>,
)

/**
 * Resize each image attachment, copy the bytes into MediaStore (private
 * filesDir/media/<date>/<sessionId>/<id>.<ext>), and return both the
 * in-memory bytes (for the LLM) and a stable file:// URI + mediaRef JSON
 * part (for persistence + reload). T150: non-image attachments take the
 * same persistence + uploadsHostDir path so they survive session reload
 * and remain visible to the agent's shell tools — but their content is
 * NOT inlined into the LLM payload (parity with iOS processAttachments,
 * AIChatViewModel.swift L1552-1645).
 */
internal fun ChatViewModel.prepareUserAttachments(
    attachments: List<InputAttachment>,
    sessionId: String,
): PreparedAttachments {
    val imageParts = mutableListOf<LLMMessage.ImagePart>()
    val imageUris = mutableListOf<Uri>()
    val imageNames = mutableListOf<String>()
    val nonImageNames = mutableListOf<String>()
    val nonImageUris = mutableListOf<Uri>()
    // T150: separate buffers so the persisted mediaRefPartsJson is
    // image-first, matching the on-screen UserAttachmentList ordering
    // and `attachmentNames = imageNames + nonImageNames`. On restore,
    // `loadSessionMessages` walks parts_json in array order — keeping
    // the persisted order image-first means restoredAttachmentNames
    // and restoredAttachmentUris also come out image-first/non-image-suffix.
    val imageMediaRefPartsJson = mutableListOf<String>()
    val nonImageMediaRefPartsJson = mutableListOf<String>()
    val imageUploadPaths = mutableListOf<String>()
    // T132: also write the resized bytes into the session's iSH-bound
    // attachments dir (filesDir/minis-sessions/<sid>/attachments/uploads/),
    // which is mounted at /var/minis/attachments/ inside iSH. This makes
    // the same image accessible to the agent via shell tools (read_image
    // / cat / file) and matches the iOS uploads-directory convention.
    val uploadsHostDir = java.io.File(
        context.filesDir,
        "minis-sessions/$sessionId/attachments/uploads",
    ).apply { mkdirs() }
    // Metadata captured per attachment for the <user-attached-files> XML.
    data class UploadMeta(val linuxPath: String, val size: Long, val modifiedIso: String)
    val metas = mutableListOf<UploadMeta>()
    val nowMs = System.currentTimeMillis()
    val isoFormatter = java.text.SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        java.util.Locale.US,
    ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    val nowStr = isoFormatter.format(java.util.Date(nowMs))

    for (attachment in attachments) {
        if (attachment.isImage) {
            // T209: read the original image bytes once and reuse them
            // for storage + uploads dir; only the LLM inference payload
            // gets the resized copy. Pre-T209 the resized JPEG was used
            // for all three, so chat history fullscreen view and agent
            // shell tools (read_image / cat) saw a 1024px JPEG instead
            // of the user's original picture. Matches iOS canonical
            // (AIChatViewModel.swift L1595-1617).
            val rawBytes = try {
                context.contentResolver.openInputStream(attachment.uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                Log.w(ChatViewModel.TAG, "image read failed for ${attachment.fileName}: ${e.message}")
                null
            } ?: continue
            val ref = try {
                mediaStore.saveMedia(
                    data = rawBytes,
                    mimeType = attachment.mimeType,
                    sessionId = sessionId,
                    originalFileName = attachment.fileName,
                )
            } catch (e: Exception) {
                Log.e(ChatViewModel.TAG, "Failed to persist image attachment ${attachment.fileName}", e)
                continue
            }
            // Resize only for the LLM payload — token-efficient and a
            // close-enough sketch of the picture for the model. Falls
            // back to raw bytes if the source is already small or the
            // decode/compress step fails.
            val inferenceBytes = resizeImageBytes(rawBytes, attachment.mimeType, maxEdge = 2000)
                ?: rawBytes

            // Mirror ORIGINAL bytes into the iSH uploads dir under a
            // unique safe name so agent shell tools see the full-res
            // image. Don't fail the send if this write fails —
            // image_url in the request still carries (resized) bytes;
            // the model just won't be able to ask the agent to re-read
            // the same file from shell.
            //
            // Done BEFORE ImagePart construction so the linuxPath is
            // attached to the part — request-level image budgeting
            // uses it to emit a re-fetchable text placeholder when
            // the cumulative payload would exceed the per-request cap.
            val safeName = uniqueUploadFileName(uploadsHostDir, attachment.fileName)
            val dest = java.io.File(uploadsHostDir, safeName)
            val uploadOk = try { dest.writeBytes(rawBytes); true } catch (e: Exception) {
                Log.w(ChatViewModel.TAG, "uploads write failed for ${attachment.fileName}: ${e.message}")
                false
            }
            val linuxPath = if (uploadOk) "/var/minis/attachments/uploads/$safeName" else null
            if (linuxPath != null) {
                imageUploadPaths.add(linuxPath)
                metas.add(UploadMeta(linuxPath = linuxPath, size = rawBytes.size.toLong(), modifiedIso = nowStr))
            }

            imageParts.add(LLMMessage.ImagePart(inferenceBytes, attachment.mimeType, linuxPath = linuxPath))
            val savedFile = java.io.File(mediaStore.mediaBaseDir, ref.relativePath)
            imageUris.add(Uri.fromFile(savedFile))
            imageNames.add(attachment.fileName)
            imageMediaRefPartsJson.add(buildMediaRefPartJson(ref, linuxPath = linuxPath))
            continue
        }

        // T150: non-image attachment — stream-copy to disk (no
        // resize), persist a mediaRef so the chip survives session
        // reload (T151), and put a copy in the iSH uploads dir so
        // the agent can `cat` it via shell tools. iOS parity: the
        // file content is NOT inlined into the LLM payload — it
        // only appears in <user-attached-files> XML metadata, the
        // model fetches content on demand.
        //
        // CRITICAL: we deliberately do NOT `readBytes()` the
        // attachment here. A 400MB APK shared in by the user would
        // OOM on a low-RAM device (heap growth limit ~500MB on
        // Pixel 4a); the file's not even going into the LLM
        // payload, so loading the full byte array is pointless.
        // Stream-copy to the uploads dest first, then hand that
        // file to MediaStore.saveMediaStreamed so a second
        // streaming pass produces the durable mediaRef.
        nonImageNames.add(attachment.fileName)
        val safeName = uniqueUploadFileName(uploadsHostDir, attachment.fileName)
        val dest = java.io.File(uploadsHostDir, safeName)
        val uploadOk = try {
            context.contentResolver.openInputStream(attachment.uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } != null
        } catch (e: Exception) {
            Log.w(ChatViewModel.TAG, "non-image upload write failed for ${attachment.fileName}: ${e.message}")
            runCatching { dest.delete() }
            false
        }
        if (!uploadOk) continue

        val ref = try {
            dest.inputStream().use { input ->
                mediaStore.saveMediaStreamed(
                    source = input,
                    mimeType = attachment.mimeType,
                    sessionId = sessionId,
                    originalFileName = attachment.fileName,
                )
            }
        } catch (e: Exception) {
            Log.e(ChatViewModel.TAG, "Failed to persist non-image attachment ${attachment.fileName}", e)
            null
        }
        if (ref != null) {
            nonImageMediaRefPartsJson.add(buildMediaRefPartJson(ref))
            nonImageUris.add(Uri.fromFile(java.io.File(mediaStore.mediaBaseDir, ref.relativePath)))
        }

        val linuxPath = "/var/minis/attachments/uploads/$safeName"
        metas.add(UploadMeta(linuxPath = linuxPath, size = dest.length(), modifiedIso = nowStr))
    }

    // T-imgsize: byte-level budget enforcement. The resizeImageBytes pass
    // above caps *resolution* at 2000px but does nothing for the JPEG byte
    // size when the source is a 12-megapixel photo — Anthropic 413s once
    // cumulative inline image payload crosses ~30MB. ImageBudget walks
    // every image part, re-encodes oversize ones via the quality ladder,
    // and drops the tail when cumulative bytes would exceed 20MB. Result
    // is surfaced to the UI through _imageBudgetEvent so the Snackbar can
    // tell the user we touched their attachments.
    if (imageParts.isNotEmpty()) {
        val budgetResult = ImageBudget.applyMessageBudget(imageParts.map { it.data })
        // budgetResult.keptBytes.size <= imageParts.size; tail-drop the
        // parallel image-only lists symmetrically. Re-encoded bytes always
        // come out as JPEG so flip the mimeType on any part whose bytes
        // changed size (cheap proxy — never a false positive that hurts
        // semantics because the byte stream itself is the JPEG header).
        val newImageParts = budgetResult.keptBytes.mapIndexed { idx, kept ->
            val orig = imageParts[idx]
            if (kept === orig.data) orig
            else LLMMessage.ImagePart(kept, "image/jpeg", linuxPath = orig.linuxPath)
        }
        val newSize = newImageParts.size
        imageParts.clear()
        imageParts.addAll(newImageParts)
        while (imageUris.size > newSize) imageUris.removeAt(imageUris.size - 1)
        while (imageNames.size > newSize) imageNames.removeAt(imageNames.size - 1)
        while (imageMediaRefPartsJson.size > newSize) imageMediaRefPartsJson.removeAt(imageMediaRefPartsJson.size - 1)
        while (imageUploadPaths.size > newSize) imageUploadPaths.removeAt(imageUploadPaths.size - 1)
        if (budgetResult.mutated) {
            AppLogger.info(
                ChatViewModel.TAG,
                "[ImageBudget] compose: in=${budgetResult.keptBytes.size + budgetResult.droppedCount} kept=${budgetResult.keptBytes.size} compressed=${budgetResult.compressedCount} dropped=${budgetResult.droppedCount} totalBytes=${budgetResult.totalBytes}",
            )
            _imageBudgetEvent.tryEmit(budgetResult)
        }
    }

    // Build the <user-attached-files> XML block (iOS parity). One <file>
    // per attachment (image and non-image) that successfully landed in
    // the iSH uploads dir — gives the model a metadata-only inventory
    // it can resolve via shell tools when content is needed.
    val xml = if (metas.isEmpty()) null else buildString {
        append("<user-attached-files>\n")
        for (m in metas) {
            val urlPath = m.linuxPath.removePrefix("/var/minis/")
            append("  <file path=\"")
            append(m.linuxPath)
            append("\" url=\"minis://")
            append(urlPath)
            append("\" size=\"")
            append(m.size)
            append("\" modified=\"")
            append(m.modifiedIso)
            append("\" />\n")
        }
        append("</user-attached-files>")
    }

    // Order matches UserAttachmentList convention: images first, then files.
    return PreparedAttachments(
        imageParts = imageParts,
        imageUris = imageUris,
        attachmentNames = imageNames + nonImageNames,
        mediaRefPartsJson = imageMediaRefPartsJson + nonImageMediaRefPartsJson,
        imageUploadPaths = imageUploadPaths,
        attachedFilesXml = xml,
        nonImageUris = nonImageUris,
    )
}

/**
 * Compute a unique-on-disk filename inside [dir] for [original]. Strips
 * path separators, falls back to "image.jpg" if the input is empty, and
 * appends `_N` before the extension when the target already exists.
 */
internal fun ChatViewModel.uniqueUploadFileName(dir: java.io.File, original: String): String {
    val raw = original.substringAfterLast('/').substringAfterLast('\\').ifBlank { "image.jpg" }
    // Sanitize control / path-hostile chars without going overboard;
    // safe POSIX path chars are kept.
    val sanitized = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
    if (!java.io.File(dir, sanitized).exists()) return sanitized
    val dot = sanitized.lastIndexOf('.')
    val base = if (dot > 0) sanitized.substring(0, dot) else sanitized
    val ext = if (dot > 0) sanitized.substring(dot) else ""
    var n = 1
    while (true) {
        val candidate = "${base}_$n$ext"
        if (!java.io.File(dir, candidate).exists()) return candidate
        n++
    }
}

/** LLM-based title + category generation, mirrors iOS generateSessionTitleIfNeeded(). */

internal fun ChatViewModel.generateSessionTitleIfNeeded() {
    // [T-android-titlegen-diag-logging] Unified "TitleGen" trail across
    // every path of this function — XIN 40454 reported sessions silently
    // staying "New Chat" and the failure paths were under-logged.
    // Logging only; no logic change.
    AppLogger.info(
        "TitleGen",
        "enter session=${realSessionId.ifEmpty { sessionId }} attempts=$titleGenerationAttempts/$TITLE_MAX_ATTEMPTS " +
            "inFlight=$titleGenerationInFlight currentTitle='${_sessionTitle.value.take(200)}'",
    )
    if (titleGenerationInFlight || titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
        AppLogger.info(
            "TitleGen",
            "skip guard=${if (titleGenerationInFlight) "inFlight" else "max-attempts ($titleGenerationAttempts/$TITLE_MAX_ATTEMPTS)"}",
        )
        return
    }
    // Skip if title already set (not "New Chat")
    if (_sessionTitle.value != "New Chat" && _sessionTitle.value.isNotEmpty()) {
        AppLogger.info("TitleGen", "skip guard=title-already-set title='${_sessionTitle.value.take(200)}'")
        return
    }
    // Prefer a dedicated sub-model (cheap, non-OAuth) — mirrors iOS resolveSubEntry.
    // Falls back to the primary provider if no sub-group is configured.
    // [T-title-gen-fallback-first-message-android] If no provider can be
    // resolved at all, the session would silently stay "New Chat". Log the
    // reason and fall back to the first user message as the title.
    val subProvider = resolveTitleProvider()
    if (subProvider == null) {
        AppLogger.info("TitleGen", "resolveTitleProvider=null — falling back to currentProvider")
    }
    val provider = subProvider ?: currentProvider
    if (provider == null) {
        AppLogger.warning("TitleGen", "no provider available (sub + current both null) — fallback-to-first-message path")
        viewModelScope.launch(Dispatchers.IO) {
            applyFallbackTitleFromFirstMessage("no provider available")
        }
        return
    }

    titleGenerationInFlight = true
    titleGenerationAttempts++

    // [T-titlegen-context-first-last-pair] Build the summary from the first
    // user + first assistant message, and — when the session has more than
    // one user turn — also the last user + last assistant message, each
    // truncated to 200 chars. This lets the title adapt when the topic
    // shifts later in a long session, instead of only seeing the opener.
    val msgs = _messages.value
    val userMessages = msgs.filter { it.role == "user" }
    val firstUser = userMessages.firstOrNull()
    if (firstUser == null) {
        AppLogger.warning("TitleGen", "skip guard=no-user-message (nothing to summarize)")
        titleGenerationInFlight = false
        return
    }
    val userText = firstUser.content.take(200)
    // First/last assistant *text* message — skip tool-only capsules whose
    // content is blank so the summary carries real assistant prose.
    val assistantTextMessages = msgs.filter { it.role == "assistant" && it.content.isNotBlank() }
    val firstAssistantText = assistantTextMessages.firstOrNull()?.content?.take(200) ?: ""
    // Only append the last pair when there is more than one user turn (i.e.
    // the first and last user messages differ) — avoids duplicating the
    // opener when the session is a single exchange.
    val hasMultipleUserTurns = userMessages.size > 1
    val lastUserText = if (hasMultipleUserTurns) userMessages.lastOrNull()?.content?.take(200) ?: "" else ""
    val lastAssistantText = if (hasMultipleUserTurns) assistantTextMessages.lastOrNull()?.content?.take(200) ?: "" else ""

    viewModelScope.launch(Dispatchers.IO) {
        try {
            // Mirror iOS callSubModelForTitle prompt shape: short cacheable system
            // prompt + user-message payload. Using the exact iOS strings keeps the
            // Anthropic prompt cache warm across title-gen calls.
            val prompt = buildString {
                append("Based on the following conversation, generate a short title (max 6 words) that captures the topic. ")
                append("Also pick a task category from: code, writing, research, analysis, creative, chat, math, translation, health, finance, travel, education, design, productivity, support, other.\n\n")
                append("You MUST respond with valid JSON only. Example:\n")
                append("{\"title\": \"Debug Login Page Issue\", \"category\": \"code\"}\n\n")
                append("Conversation:\n")
                append("User: $userText\n")
                if (firstAssistantText.isNotEmpty()) append("Assistant: $firstAssistantText\n")
                if (lastUserText.isNotEmpty()) append("User: $lastUserText\n")
                if (lastAssistantText.isNotEmpty()) append("Assistant: $lastAssistantText\n")
                append(titleLanguageDirective())
            }
            // [T-android-titlegen-systemprompt-unify] Shared with the manual
            // Regenerate path (SessionListViewModel.regenerateTitle) via the
            // single TITLE_GEN_SYSTEM_PROMPT constant so the two never drift.
            // Passed bare: for OAuth Anthropic instances,
            // AnthropicProvider.resolveSystemPrompt force-prepends the Claude
            // Code prefix block at the provider layer (and strips a
            // caller-supplied one), so no caller-side prepend is needed — the
            // previous manual prefix branch here was redundant.
            val effectiveSystemPrompt = TITLE_GEN_SYSTEM_PROMPT

            AppLogger.info(
                "TitleGen",
                "dispatch attempt=$titleGenerationAttempts provider=${provider.javaClass.simpleName} model=${provider.model.id}",
            )
            // [T-android-titlegen-reasoning] Match iOS callSubModelForTitle:
            // explicitly disable thinking (thinkingLevel = OFF). The provider
            // layer's injectThinkingParams honors OFF — e.g. DeepSeek V4 gets
            // an explicit {"thinking":{"type":"disabled"}}, o-series/gpt-5
            // omit reasoning_effort, Anthropic sends no thinking block — so a
            // reasoning sub-model doesn't burn the whole budget on hidden
            // thinking and return empty text. As a belt-and-suspenders for
            // models where OFF is still a no-op (e.g. Qwen3, which thinks by
            // default), keep the T334 budget bump so it can finish thinking
            // and still emit the JSON. Unified with regenerateTitle's ladder.
            val titleMaxTokens = if (provider.model.supportsReasoning == true) 2048 else 100
            val titleInstance = provider.instanceContext ?: run {
                // Cannot dispatch a provider-created title call without an
                // instance — surface as a typed error (never in-process).
                AppLogger.warning("TitleGen", "outcome=no-instance-context attempt=$titleGenerationAttempts")
                if (titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
                    applyFallbackTitleFromFirstMessage("no provider instance context")
                }
                return@launch
            }
            val titleResult = ProviderExecutionGateway.send(
                context = context,
                instance = titleInstance,
                model = provider.model,
                messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
                systemPrompt = effectiveSystemPrompt,
                maxTokens = titleMaxTokens,
                // Mirror iOS AIChatViewModel.swift:11244 — pass null so
                // gpt-5.x family doesn't reject the request (only
                // temperature=1 allowed there). buildRequestBody omits
                // the field when null.
                temperature = null,
                thinkingLevel = ThinkingLevel.OFF,
            )
            val response = when (titleResult) {
                is ProviderExecutionGateway.SendResult.Success -> titleResult.response
                is ProviderExecutionGateway.SendResult.RemoteFailure -> {
                    AppLogger.warning(
                        "TitleGen",
                        "outcome=remote-failure attempt=$titleGenerationAttempts (${titleResult.code}): ${titleResult.message.take(200)}",
                    )
                    if (titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
                        applyFallbackTitleFromFirstMessage("remote failure ${titleResult.code}")
                    }
                    return@launch
                }
                is ProviderExecutionGateway.SendResult.Unavailable -> {
                    AppLogger.warning(
                        "TitleGen",
                        "outcome=unavailable attempt=$titleGenerationAttempts: ${titleResult.reason}",
                    )
                    if (titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
                        applyFallbackTitleFromFirstMessage("title service unavailable: ${titleResult.reason}")
                    }
                    return@launch
                }
            }

            AppLogger.info(
                "TitleGen",
                "response stopReason=${response.stopReason} textLen=${response.text.length} " +
                    "raw='${response.text.take(200).replace("\n", "\\n")}'",
            )
            val (title, category) = parseTitleResponse(response.text)
            if (title.isNotEmpty()) {
                val sid = realSessionId.ifEmpty { sessionId }
                chatRepository.updateSessionTitleAndCategory(sid, title, category)
                withContext(Dispatchers.Main) {
                    _sessionTitle.value = title
                    _sessionCategory.value = category
                }
                AppLogger.info("TitleGen", "outcome=set title='$title' category='$category'")
            } else {
                // [T-title-gen-fallback-first-message-android] The request
                // succeeded but yielded no usable title — empty body or a
                // response parseTitleResponse couldn't extract a title from
                // (e.g. reasoning model that spent its whole budget thinking,
                // or non-JSON output). Previously this was silent and left
                // the session as "New Chat". Log the real cause and, on the
                // final attempt, fall back to the first user message.
                AppLogger.warning(
                    "TitleGen",
                    "outcome=no-title attempt=$titleGenerationAttempts/$TITLE_MAX_ATTEMPTS " +
                        "(empty / unparseable response) stopReason=${response.stopReason} " +
                        "textLen=${response.text.length}",
                )
                if (titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
                    AppLogger.warning("TitleGen", "outcome=gave-up ($titleGenerationAttempts/$TITLE_MAX_ATTEMPTS) — applying first-message fallback title")
                    applyFallbackTitleFromFirstMessage("empty/unparseable title response")
                }
            }
        } catch (e: Exception) {
            // [T-title-gen-fallback-first-message-android] Request error /
            // timeout / provider failure. Log the concrete cause (was
            // already logged, kept) and fall back to the first user message
            // on the final attempt.
            AppLogger.warning(
                "TitleGen",
                "outcome=exception attempt=$titleGenerationAttempts/$TITLE_MAX_ATTEMPTS " +
                    "${e.javaClass.simpleName}: ${e.message?.take(200)}",
            )
            if (titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
                AppLogger.warning("TitleGen", "outcome=gave-up ($titleGenerationAttempts/$TITLE_MAX_ATTEMPTS) — applying first-message fallback title")
                applyFallbackTitleFromFirstMessage("request failed: ${e.message?.take(200)}")
            }
        } finally {
            titleGenerationInFlight = false
        }
    }
}

/**
 * [T-title-gen-fallback-first-message-android] Set the session title to a
 * cleaned-up truncation of the first user message when LLM title generation
 * fails (request error / timeout / empty / parse failure / model
 * unavailable). Strips the trailing `<user-attached-files>` XML block,
 * collapses whitespace/newlines to single spaces, and clamps to ~30 chars
 * with an ellipsis — matching the title norm (single-line, short). No-op
 * (logged) when there's no usable first-message text.
 */
internal suspend fun ChatViewModel.applyFallbackTitleFromFirstMessage(reason: String) {
    val raw = _messages.value.firstOrNull { it.role == "user" }?.content
    var text = raw ?: ""
    // Drop the <user-attached-files> XML the composer appends so the title
    // reflects what the user actually typed, not the attachment manifest.
    val startIdx = text.indexOf("<user-attached-files>")
    if (startIdx >= 0) {
        val endTag = "</user-attached-files>"
        val endIdx = text.indexOf(endTag, startIdx)
        text = if (endIdx >= 0) {
            text.substring(0, startIdx) + text.substring(endIdx + endTag.length)
        } else {
            text.substring(0, startIdx)
        }
    }
    // Collapse all whitespace (incl. newlines) to single spaces, trim.
    val cleaned = text.replace(Regex("\\s+"), " ").trim()
    if (cleaned.isEmpty()) {
        Log.w(ChatViewModel.TAG, "Title fallback skipped ($reason): first user message has no text")
        return
    }
    val fallbackTitle = if (cleaned.length > 30) cleaned.take(30).trimEnd() + "…" else cleaned
    val sid = realSessionId.ifEmpty { sessionId }
    chatRepository.updateSessionTitle(sid, fallbackTitle)
    withContext(Dispatchers.Main) {
        _sessionTitle.value = fallbackTitle
    }
    Log.i(ChatViewModel.TAG, "Title fallback applied ($reason): '$fallbackTitle'")
}

/**
 * Resolve the provider used for title generation. Mirrors iOS resolveSubEntry:
 * prefer an explicitly configured sub-model (cheap, non-OAuth) so title
 * generation doesn't hit the expensive primary model or fail under the
 * OAuth-Anthropic Claude-Code-only gate. Falls back to null if no sub is
 * configured — caller uses the primary provider then.
 */
internal fun ChatViewModel.resolveTitleProvider(): LLMProvider? {
    // [T-disabled-provider-via-group-android] Resolve the dedicated
    // title-generation sub-model (first enabled member of defaultSubGroupId).
    // [T-android-regenerate-title-submodel] Shares
    // ProviderRepository.resolveTitleSubEntry with the manual Regenerate
    // path so both prefer the same sub-model. Silently degrades (caller
    // falls back to the primary provider) when no sub-group is configured or
    // every member sits behind a disabled provider.
    val entry = providerRepository.resolveTitleSubEntry() ?: return null
    val instance = providerRepository.instance(entry.providerInstanceId) ?: return null
    val apiKey = providerRepository.loadApiKey(instance.id) ?: return null

    return ProviderFactory.create(instance, apiKey, entry.model, context)
}

/** Parse LLM response for title/category JSON. Multiple fallback strategies. */
/**
 * [T-android-overlay-reply-status-34599] Pull the most recent
 * assistant text out of `_messages` and hand it to
 * [SessionActivityTracker.publishLastReply]. The tracker truncates
 * to a fixed-width excerpt and pairs it with [sessionId] so the
 * floating overlay can render a "tap to open this chat" capsule
 * after the stream completes. No-op when no assistant message has
 * content yet (e.g. fail during the very first turn).
 */
