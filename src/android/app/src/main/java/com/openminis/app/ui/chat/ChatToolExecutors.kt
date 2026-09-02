package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.tools.SubagentSkill
import com.openminis.app.tools.SubagentToolCall
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/**
 * FE-5 route B: the tool-execution layer extracted from ChatViewModel.
 *
 * These executors were private members of ChatViewModel (total ~900 lines).
 * They are moved here as top-level functions with their ViewModel state
 * dependencies **explicitly parameterized** — no `this` capture, no
 * StateFlow access:
 *
 *  - [executeBrowserUseTool] — pure given (tabPool, artifactWriter): reads
 *    BrowserActionInput, caps the text result, resizes + persists
 *    screenshots, appends minis_url lines.
 *  - [executeMemoryWriteTool] / [executeMemoryGetTool] /
 *    [executeMemoryRollupTool] — pure given (repo, memoryEnabled, sink):
 *    the record-sink callback replaces the _memoryToolRecords side channel.
 *  - [persistBrowserArtifact] / [linuxPathToMinisURL] — persistence helpers
 *    parameterized by the session browser dir.
 *  - [runSubagentLoop] — the spawn_agent multi-turn loop, with provider
 *    stream + tool execution injected as functions.
 *
 * Behavior is byte-for-byte identical to the inline logic they replace.
 */

/**
 * Write bytes to `<filesDir>/minis-sessions/<sessionId>/browser/<filename>`
 * (host dir resolved via [PRootKernel], bind-mounted to `/var/minis/browser/`
 * so the agent can read it back via file_read / file_write / minis:// URLs).
 * Returns the host absolute path on success, null otherwise.
 */
internal fun persistBrowserArtifact(
    sessionId: String,
    filename: String,
    data: ByteArray,
    context: Context,
): String? {
    val sid = sessionId.takeIf { it.isNotEmpty() } ?: return null
    return try {
        val dir = java.io.File(context.filesDir, "minis-sessions/$sid/browser").apply { mkdirs() }
        val file = java.io.File(dir, filename)
        file.writeBytes(data)
        file.absolutePath
    } catch (e: Exception) {
        android.util.Log.w("ChatViewModel", "persistBrowserArtifact failed: ${e.message}")
        null
    }
}

/**
 * Convert a Linux path under /var/minis/ to a percent-encoded minis:// URL.
 * Mirrors iOS AIChatViewModel.linuxPathToMinisURL.
 */
internal fun linuxPathToMinisURL(path: String): String? {
    val prefix = "/var/minis/"
    if (!path.startsWith(prefix)) return null
    val rest = path.removePrefix(prefix)
    val slash = rest.indexOf('/')
    if (slash < 0) return null
    val namespace = rest.substring(0, slash)
    val filename = rest.substring(slash + 1)
    val encoded = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
    return "minis://$namespace/$encoded"
}

/**
 * browser_use executor. [artifactWriter] persists bytes and returns the host
 * path (production wires persistBrowserArtifact with the session id).
 */
internal suspend fun executeBrowserUseTool(
    argsJson: String,
    tabPool: BrowserTabPool,
    artifactWriter: (filename: String, data: ByteArray) -> String?,
    resizeJpeg: (ByteArray, Int) -> ByteArray?,
): ToolExecutionResult {
    val input = BrowserActionInput.parse(argsJson)
        ?: return ToolExecutionResult("Error: Invalid browser_use input", false)

    return try {
        val result = tabPool.execute(input)
        val toolTitle = try {
            JSONObject(argsJson).optString("tool_title", "browser_use")
        } catch (_: Exception) { "browser_use" }

        var output = result.text
        // [T-android-browser-toolresult-guard] Bound browser tool result text
        // before it enters ToolExecutionResult → message → renderer/LLM context.
        // A 900KiB get_text (Fix-03 cap) made the main thread hang (ANR) when
        // the toolResult message rendered full-width, and no LLM context can
        // use 900K chars anyway. Truncate to a readable bound with an explicit
        // notice so the agent knows it was cut (truncated flag already flows
        // from the bridge; this is the final belt-and-suspenders bound).
        val browserToolResultMaxChars = 64 * 1024
        if (output.length > browserToolResultMaxChars) {
            val truncatedNotice = "\n\n…[tool result truncated: ${output.length} chars > $browserToolResultMaxChars — re-run get_text with a selector/scroll to read the rest]"
            output = output.take(browserToolResultMaxChars) + truncatedNotice
        }
        var persistentImagePath: String? = result.imageFilePath
        var inferenceBytes: ByteArray? = null

        // Persist browser screenshots to /var/minis/browser/<session>/ so the
        // agent can reference them via minis:// in subsequent tool calls
        // (mirrors iOS AIChatViewModel case "browser_use").
        val base64 = result.base64Image
        var linuxImagePath: String? = null
        if (base64 != null) {
            val raw = try {
                android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            } catch (_: Exception) { null }
            if (raw != null) {
                // Anthropic supports up to 8000×8000 / 5MB; we standardize at 2000
                // long edge across attachments / browser / read_image.
                inferenceBytes = resizeJpeg(raw, 2000) ?: raw
                val filename = "screenshot_${System.currentTimeMillis() / 1000}.jpg"
                val persistPath = artifactWriter(filename, raw)
                if (persistPath != null) {
                    persistentImagePath = persistPath
                    linuxImagePath = "/var/minis/browser/$filename"
                    linuxPathToMinisURL(linuxImagePath)?.let {
                        output = "$output\nminis_url: $it"
                    }
                }
            }
        }

        // Persist fetched files (fetch action) and append minis_url
        val fetchData = result.fetchedFileData
        val fetchName = result.fetchedFileName
        if (fetchData != null && fetchName != null) {
            artifactWriter(fetchName, fetchData)
            linuxPathToMinisURL("/var/minis/browser/$fetchName")?.let {
                output = "$output\nminis_url: $it"
            }
        }

        ToolExecutionResult(
            output = output,
            success = result.success,
            imageData = inferenceBytes,
            imageMimeType = if (inferenceBytes != null) "image/jpeg" else null,
            toolTitle = toolTitle,
            pageURL = result.pageURL,
            imageFilePath = persistentImagePath,
            imageLinuxPath = linuxImagePath,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolExecutionResult("Error: ${e.message}", false)
    }
}

/**
 * memory_write executor. [recordSink] replaces the _memoryToolRecords side
 * channel (production appends to the StateFlow).
 */
internal fun executeMemoryWriteTool(
    argsJson: String,
    repo: com.openminis.app.data.repository.MemoryRepository,
    memoryEnabled: Boolean,
    recordSink: (MemoryToolRecord) -> Unit,
): ToolExecutionResult {
    if (!memoryEnabled) {
        val msg = "Memory writes are disabled for this session (user toggled /memory off). Reads remain available."
        return ToolExecutionResult(msg, false, toolTitle = "Memory (disabled)")
    }
    val result = com.openminis.app.tools.MemoryTools.executeMemoryWrite(argsJson, repo)
    // Record for SessionMemorySheet
    val content = try {
        JSONObject(argsJson).optString("content", "")
    } catch (_: Exception) { "" }
    recordSink(MemoryToolRecord(
        title = result.toolTitle,
        isWrite = true,
        preview = content.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: "",
        output = result.output,
        writtenContent = content,
    ))
    return ToolExecutionResult(result.output, result.success, toolTitle = result.toolTitle)
}

/** memory_get executor (see [executeMemoryWriteTool] for the sink pattern). */
internal fun executeMemoryGetTool(
    argsJson: String,
    repo: com.openminis.app.data.repository.MemoryRepository,
    recordSink: (MemoryToolRecord) -> Unit,
): ToolExecutionResult {
    val result = com.openminis.app.tools.MemoryTools.executeMemoryGet(argsJson, repo)
    val keywords = try {
        JSONObject(argsJson).optString("keywords", "")
    } catch (_: Exception) { "" }
    recordSink(MemoryToolRecord(
        title = result.toolTitle,
        isWrite = false,
        preview = if (keywords.isNotBlank()) "Search: $keywords" else result.output.take(100),
        output = result.output,
        keywords = keywords,
    ))
    return ToolExecutionResult(result.output, result.success, toolTitle = result.toolTitle)
}

/**
 * [T6-rollup] On-demand memory rollup: distills the previous day's daily
 * log into MEMORY-ROLLUP.md. Uses the same memory dir as the repository.
 */
internal fun executeMemoryRollupTool(
    repo: com.openminis.app.data.repository.MemoryRepository,
    recordSink: (MemoryToolRecord) -> Unit,
): ToolExecutionResult {
    val memoryDir = repo.memoryDirectory()
    val result = com.openminis.app.tools.MemoryRollupTool.execute(memoryDir)
    recordSink(MemoryToolRecord(
        title = result.toolTitle,
        isWrite = false,
        preview = result.output.take(100),
        output = result.output,
    ))
    return ToolExecutionResult(result.output, result.success, toolTitle = result.toolTitle)
}

/**
 * [T7-subagent] The spawn_agent multi-turn loop, extracted verbatim from
 * ChatViewModel.executeSpawnAgentTool with the provider stream + sub-tool
 * executor injected as functions (no ViewModel capture).
 *
 * @param streamProvider suspends and streams chunks for one turn
 *   (production: ProviderExecutionGateway.stream).
 * @param executeSubTool runs one tool call inside the sub-agent's loop
 *   (production: executeSubagentTool — file/memory only, no UI updates).
 * @return the final ToolExecutionResult for the main agent.
 */
internal suspend fun runSubagentLoop(
    skillName: String,
    query: String,
    title: String,
    config: SubagentSkill.SubagentConfig,
    systemPrompt: String,
    streamProvider: suspend (messages: List<LLMMessage>) -> kotlinx.coroutines.flow.Flow<LLMStreamChunk>,
    executeSubTool: suspend (name: String, argsJson: String) -> ToolExecutionResult,
    log: (String) -> Unit,
): ToolExecutionResult {
    val resultSb = StringBuilder()
    var turns = 0
    var lastText = ""
    val history = mutableListOf(LLMMessage(role = LLMMessage.Role.USER, content = query))

    try {
        while (turns < config.maxTurns) {
            turns++
            val textSb = StringBuilder()
            val toolCalls = mutableListOf<SubagentToolCall>()

            // TF-D: sub-agent runs through :modelservice via the gateway. Chunks
            // are accumulated incrementally as they stream in — never buffered
            // wholesale via `toList()` (unbounded retention of the whole turn).
            streamProvider(history.toList()).collect { chunk ->
                when (chunk) {
                    is LLMStreamChunk.Text -> textSb.append(chunk.text)
                    is LLMStreamChunk.ToolCallComplete -> {
                        toolCalls.add(SubagentToolCall(chunk.id, chunk.name, chunk.args))
                    }
                    else -> {}
                }
            }

            val text = textSb.toString()
            lastText = text
            if (text.isNotBlank()) {
                if (resultSb.isNotEmpty()) resultSb.append('\n')
                resultSb.append(text)
            }

            if (toolCalls.isEmpty()) {
                // Model finished naturally — no more tool calls
                break
            }

            // Append assistant turn with tool uses to history
            history.add(LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = text,
                contentParts = toolCalls.map { call ->
                    AgentContentPart.ToolUse(id = call.id, name = call.name, input = call.args)
                },
            ))

            // Execute tools sequentially
            for (call in toolCalls) {
                val result = executeSubTool(call.name, call.args.toString())
                val resultContent = if (result.success) {
                    result.output
                } else {
                    "Error: ${result.output}"
                }
                history.add(LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "Result of ${call.name} (${call.id}):\n$resultContent",
                    contentParts = listOf(AgentContentPart.ToolResult(
                        id = call.id, name = call.name,
                        content = resultContent, isError = !result.success,
                    )),
                ))
            }
        }
    } catch (e: Exception) {
        val msg = e.message ?: e.javaClass.simpleName
        log("[Subagent] '$skillName' error after $turns turn(s): $msg")
        val partial = resultSb.toString().ifBlank { "" }
        val summary = buildString {
            append("Sub-agent '$skillName' encountered an error after $turns turn(s).\n")
            if (partial.isNotBlank()) {
                append("\nPartial output:\n---\n$partial\n---\n")
            }
            append("\nError: $msg")
        }
        return ToolExecutionResult(summary, false, toolTitle = title)
    }

    if (turns >= config.maxTurns && lastText.isNotBlank()) {
        resultSb.append("\n\n[Sub-agent reached max turns (${config.maxTurns})]")
    }

    val finalText = resultSb.toString().trim()
    if (finalText.isBlank()) {
        return ToolExecutionResult(
            "Sub-agent '$skillName' completed in $turns turn(s) with no output.",
            true, toolTitle = title,
        )
    }

    val summary = "Sub-agent '$skillName' completed in $turns turn(s).\n\n---\n$finalText"
    return ToolExecutionResult(summary, true, toolTitle = title)
}

/**
 * Mirror of iOS AIChatViewModel post-tool hook: when the agent writes or
 * edits a SKILL.md inside a `/skills/` directory we ask SkillRepository to
 * re-scan disk so the new skill is visible immediately, without waiting
 * for app restart.
 */
internal fun maybeReloadSkillsForPath(
    argsJson: String,
    skillRepository: SkillRepository?,
) {
    runCatching {
        val path = JSONObject(argsJson).optString("path", "")
        if (path.contains("/skills/") && path.endsWith("SKILL.md")) {
            skillRepository?.reloadFromDisk()
        }
    }
}
