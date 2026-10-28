package com.rikkaminis.app.ui.chat

import android.content.Context
import com.rikkaminis.app.browser.BrowserActionInput
import com.rikkaminis.app.browser.BrowserTabPool
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.repository.SkillRepository
import com.rikkaminis.app.tools.SubagentSkill
import com.rikkaminis.app.tools.SubagentToolCall
import com.rikkaminis.app.tools.ToolExecutionResult
import com.rikkaminis.app.tools.resolveToolTitle
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
 * [fix/sweep1014-b19] Sub-agent truncation continuation ceiling — aligned with
 * the main loop's length-wall continuation default
 * (AgentRuntimeLimitsPrefs.LENGTH_WALL_DEFAULT = 4). The sub-agent loop is a
 * pure function (no prefs access), so the ceiling is a constant here; each
 * truncated-turn continue also consumes one of config.maxTurns, which bounds
 * the loop regardless.
 */
internal const val MAX_SUBAGENT_TRUNCATION_CONTINUES = 4

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
 * [meta-audit-1028 F-10] Monotonic per-process counter for browser screenshot
 * filenames. Seconds are not a unique key: two `browser_use` calls in the same
 * second produced the SAME `screenshot_<seconds>.jpg`, so the later write
 * overwrote the earlier one — the earlier tool card's thumbnail, its
 * `imageFilePath` and every `minis://browser/screenshot_…` URL the agent had
 * already been handed silently became the newest image. The counter is
 * process-wide and never reset, so a timestamp collision (same millisecond,
 * clock stepped backwards, two calls on different sessions) can no longer
 * alias two screenshots into one file.
 */
private val browserScreenshotSequence = java.util.concurrent.atomic.AtomicLong(0L)

/** Next value of the screenshot uniqueness counter (strictly increasing). */
internal fun nextBrowserScreenshotSequence(): Long = browserScreenshotSequence.incrementAndGet()

/**
 * [meta-audit-1028 F-10] Screenshot filename: millisecond timestamp (was
 * seconds) + the monotonic [sequence]. Pure so the uniqueness rule is a unit
 * test instead of an observation.
 */
internal fun browserScreenshotFilename(timestampMillis: Long, sequence: Long): String =
    "screenshot_${timestampMillis}_$sequence.jpg"

/**
 * browser_use executor. [artifactWriter] persists bytes and returns the host
 * path (production wires persistBrowserArtifact with the session id).
 */
internal suspend fun executeBrowserUseTool(
    argsJson: String,
    tabPool: BrowserTabPool,
    /** [fix/browser-trio-audit] Owning chat session — injected (never parsed
     *  from the model's JSON) so file_upload resolves per-session
     *  /var/minis/... paths via resolveSessionHostPath (T178 pattern).
     *  Caution: never write a slash immediately followed by two glob stars
     *  inside a Kotlin block comment — Kotlin comments nest, that sequence
     *  opens a nested level the single trailing close marker cannot shut
     *  (it broke kspReleaseKotlin on the first CI run of this change). */
    sessionId: String? = null,
    artifactWriter: (filename: String, data: ByteArray) -> String?,
    resizeJpeg: (ByteArray, Int) -> ByteArray?,
): ToolExecutionResult {
    // [fix/da1025-G] Resolve the title BEFORE the parse early-return
    // (ToolTitleResolver's contract: callers must invoke this before their
    // try block). The title used to be a try-scoped val inside the success
    // path, so the invalid-input early return and the catch arm had no title
    // to report — the capsule degraded to the bare tool name on every
    // browser_use error path.
    val toolTitle = resolveToolTitle(argsJson, "browser_use")
    val parsed = BrowserActionInput.parse(argsJson)
        ?: return ToolExecutionResult("Error: Invalid browser_use input", false, toolTitle = toolTitle)
    // copy(sessionId = null) is a no-op when sessionId is absent — callers
    // without a session (rare) keep the global-resolver behavior.
    val input = parsed.copy(sessionId = sessionId)

    return try {
        val result = tabPool.execute(input)

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
                val filename = browserScreenshotFilename(
                    System.currentTimeMillis(),
                    nextBrowserScreenshotSequence(),
                )
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
            // [deep-audit-1048 B-03] Same contract as the screenshot arm above:
            // the writer returns the HOST path and null when the write failed.
            // This arm discarded the return value, so a failed write still
            // appended `minis_url: /var/minis/browser/<name>` — handing the
            // agent a URL for a file that does not exist, with no signal that
            // the fetched bytes were lost (the next read_image / file read then
            // fails for a reason that points at the wrong layer).
            val fetchPersistPath = artifactWriter(fetchName, fetchData)
            if (fetchPersistPath != null) {
                linuxPathToMinisURL("/var/minis/browser/$fetchName")?.let {
                    output = "$output\nminis_url: $it"
                }
            } else {
                output = "$output\n(fetched file not persisted: $fetchName — " +
                    "the bytes could not be written to /var/minis/browser/, " +
                    "do not reference it)"
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
        ToolExecutionResult("Error: ${e.message}", false, toolTitle = toolTitle)
    }
}

/**
 * [fix/da1020-memory-gate-provider-fields] Single source for the memory
 * tool gate: null = allowed, non-null = the rejection message. Both the
 * memory_write and memory_rollup executors consult this so a session with
 * /memory off answers the two write-shaped tools identically (reads remain
 * available — the /memory command only turns off writes + injection).
 */
internal fun memoryToolDisabledMessage(memoryEnabled: Boolean): String? =
    if (memoryEnabled) null
    else "Memory writes are disabled for this session (user toggled /memory off). Reads remain available."

/** Shared toolTitle for a memory tool rejected by [memoryToolDisabledMessage]. */
internal const val MEMORY_DISABLED_TOOL_TITLE = "Memory (disabled)"

/**
 * [meta-audit-1028 F-09] Op-kind labels for the sheet's op-log pill.
 *
 * `MemoryToolRecord.isWrite` is a ROUTING discriminator (Write detail needs
 * `writtenContent`), not "did this touch the disk" — that is why the rollup
 * record keeps `isWrite = false`. It therefore cannot label the row: the
 * rollup pill used to read `memory_get`, i.e. the panel claimed a read-only
 * search for a tool that rewrote MEMORY-ROLLUP.md. The label is now carried
 * explicitly so each op kind names itself.
 */
internal const val MEMORY_TOOL_WRITE = "memory_write"
internal const val MEMORY_TOOL_GET = "memory_get"
internal const val MEMORY_TOOL_ROLLUP = "memory_rollup"

/**
 * [meta-audit-1028 F-09] The body a write record may claim it wrote.
 *
 * Only a SUCCESSFUL write with a non-blank body has an on-disk entry to point
 * at. The record used to carry `writtenContent` unconditionally, so a failed
 * `memory_write` (repo disabled, entry malformed, disk error) still landed a
 * success-shaped row: tapping Edit pre-filled the text that was never written
 * and Save/Revoke hit the repository with a body that does not exist →
 * NotFound, with no hint that the original write had failed. Null here is what
 * the Write detail already treats as "not editable/revocable"
 * (`canEditOrRevoke = writtenContent != null`).
 */
internal fun memoryWriteRecordContent(success: Boolean, content: String): String? =
    if (success && content.isNotBlank()) content else null

/**
 * memory_write executor. [recordSink] replaces the _memoryToolRecords side
 * channel (production appends to the StateFlow).
 */
internal fun executeMemoryWriteTool(
    argsJson: String,
    repo: com.rikkaminis.app.data.repository.MemoryRepository,
    memoryEnabled: Boolean,
    recordSink: (MemoryToolRecord) -> Unit,
): ToolExecutionResult {
    memoryToolDisabledMessage(memoryEnabled)?.let {
        return ToolExecutionResult(it, false, toolTitle = MEMORY_DISABLED_TOOL_TITLE)
    }
    val result = com.rikkaminis.app.tools.MemoryTools.executeMemoryWrite(argsJson, repo)
    // Record for SessionMemorySheet
    val content = try {
        JSONObject(argsJson).optString("content", "")
    } catch (_: Exception) { "" }
    recordSink(MemoryToolRecord(
        title = result.toolTitle,
        isWrite = true,
        preview = content.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: "",
        output = result.output,
        // [meta-audit-1028 F-09] Only a write that really landed carries a
        // body — see [memoryWriteRecordContent].
        writtenContent = memoryWriteRecordContent(result.success, content),
        toolName = MEMORY_TOOL_WRITE,
    ))
    return ToolExecutionResult(result.output, result.success, toolTitle = result.toolTitle)
}

/** memory_get executor (see [executeMemoryWriteTool] for the sink pattern). */
internal fun executeMemoryGetTool(
    argsJson: String,
    repo: com.rikkaminis.app.data.repository.MemoryRepository,
    recordSink: (MemoryToolRecord) -> Unit,
): ToolExecutionResult {
    val result = com.rikkaminis.app.tools.MemoryTools.executeMemoryGet(argsJson, repo)
    val keywords = try {
        JSONObject(argsJson).optString("keywords", "")
    } catch (_: Exception) { "" }
    recordSink(MemoryToolRecord(
        title = result.toolTitle,
        isWrite = false,
        preview = if (keywords.isNotBlank()) "Search: $keywords" else result.output.take(100),
        output = result.output,
        keywords = keywords,
        toolName = MEMORY_TOOL_GET,
    ))
    return ToolExecutionResult(result.output, result.success, toolTitle = result.toolTitle)
}

/**
 * [T6-rollup] On-demand memory rollup: distills the previous day's daily
 * log into MEMORY-ROLLUP.md. Uses the same memory dir as the repository.
 *
 * [fix/da1020-memory-gate-provider-fields] Same gate as memory_write:
 * MemoryRollupTool.execute writes MEMORY-ROLLUP.md, so a session with
 * /memory off must not let a rollup call reach the disk (the schema gate
 * in AgentTools.makeAgentTools drops the tool, but a tool_use block that
 * still arrives — mid-turn toggle, replayed block — would otherwise write).
 */
internal fun executeMemoryRollupTool(
    repo: com.rikkaminis.app.data.repository.MemoryRepository,
    memoryEnabled: Boolean,
    recordSink: (MemoryToolRecord) -> Unit,
): ToolExecutionResult {
    memoryToolDisabledMessage(memoryEnabled)?.let {
        return ToolExecutionResult(it, false, toolTitle = MEMORY_DISABLED_TOOL_TITLE)
    }
    val memoryDir = repo.memoryDirectory()
    val result = com.rikkaminis.app.tools.MemoryRollupTool.execute(memoryDir)
    // [meta-audit-1028 F-09] `isWrite` stays false: it routes the sheet to the
    // Write/Get detail body and a rollup has no per-entry body to show there.
    // The row LABEL no longer follows it — [toolName] does that job, so the
    // rollup row stops claiming to be a read-only `memory_get`.
    recordSink(MemoryToolRecord(
        title = result.toolTitle,
        isWrite = false,
        preview = result.output.take(100),
        output = result.output,
        toolName = MEMORY_TOOL_ROLLUP,
    ))
    return ToolExecutionResult(result.output, result.success, toolTitle = result.toolTitle)
}

/**
 * [fix/da1017-subagent-residue] Record a finished turn's VISIBLE text in the
 * sub-agent [history] as the assistant message the model actually wrote.
 *
 * Every path that hands the turn back to the model (`continue` instead of
 * `break` — residue refill, truncation continuation) MUST call this first.
 * The sub-agent's history is the ONLY thing the next request shows the model:
 * a hand-back that leaves the turn out of history makes the model re-write
 * what it already wrote (the copy lands in `resultSb` a second time, which is
 * the sub-agent's persisted result — the main agent reads the text twice).
 * The MAIN loop has the same contract at `AgentLoopEngine`'s
 * `agentHistory.add(content = turnText)` — this is its sub-agent twin.
 *
 * Conventions mirrored from the main loop / the truncation path:
 *  - [text] is the turn's VISIBLE text (residue already stripped) — see the
 *    call site's `ToolCallResiduePolicy.stripResidue` step. The raw markup
 *    must never reach history (it would re-enter every later request).
 *  - A blank turn appends NOTHING. Both providers filter
 *    `content.isEmpty() && contentParts.isEmpty()` messages out of the
 *    request, so a blank shell is invisible to the model while still
 *    breaking role alternation in memory — the exact shape
 *    [ensureRoleAlternationBeforeUserAppend] then "bridges" into a
 *    synthetic assistant turn (Anthropic hard 400 territory is avoided by
 *    NOT creating the shell in the first place).
 *
 * @return true when an assistant message was appended.
 */
internal fun appendSubagentTurnText(
    history: MutableList<LLMMessage>,
    text: String,
    /**
     * [deep-audit-1018 ISSUE-02] The turn's captured reasoning (opaque
     * ReasoningContent blob, else the ThinkingDelta concatenation) — echoes
     * back on the next request exactly like the main loop's assistant history
     * entries do. Defaults to null so callers with no reasoning channel keep
     * the previous message shape.
     */
    reasoningContent: String? = null,
): Boolean {
    if (text.isBlank()) return false
    history.add(LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = text,
        reasoningContent = reasoningContent,
    ))
    return true
}

/**
 * [deep-audit-1018 ISSUE-02] Per-turn reasoning capture for the sub-agent
 * loop — the sub-agent twin of the main loop's `turnThinking` /
 * `turnReasoningBlob` pair (AgentLoopEngine, per-turn locals).
 *
 * Semantics are copied verbatim from the main loop so the two loops hand back
 * the same shape:
 *  - [LLMStreamChunk.ThinkingDelta] — real-time increments, CONCATENATED.
 *  - [LLMStreamChunk.ReasoningContent] — the provider's full opaque blob;
 *    the LAST one wins (assignment, not append). It takes precedence over the
 *    concatenation, and an EMPTY blob is preserved as "" (DeepSeek V4 emits
 *    `reasoning_content: ""` on non-thinking turns — fabricating text or
 *    collapsing to null would change what the server asked us to round-trip).
 *  - No reasoning chunks at all → null (no field to round-trip).
 *
 * One instance per turn (declared inside the loop body) — that IS the
 * per-turn reset; the main loop gets the same effect from its per-turn locals.
 */
internal class SubagentReasoningAccumulator {
    private val thinking = StringBuilder()
    private var opaqueBlob: String? = null

    /** Feed one stream chunk; ignores everything that is not reasoning. */
    fun accept(chunk: LLMStreamChunk) {
        when (chunk) {
            is LLMStreamChunk.ThinkingDelta -> thinking.append(chunk.text)
            is LLMStreamChunk.ReasoningContent -> opaqueBlob = chunk.content
            else -> {}
        }
    }

    /** Blob wins (including ""); else the concatenation if non-empty; else null. */
    fun value(): String? = opaqueBlob ?: thinking.toString().takeIf { it.isNotEmpty() }
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
 * @param knownToolNames the sub-agent's allowed tool names (production:
 *   the filtered tool list) — the residue policy resolves a drifted tool
 *   call against THESE, so a markup-shaped false positive on an unknown
 *   name is never eaten.
 * @return the final ToolExecutionResult for the main agent.
 */
internal suspend fun runSubagentLoop(
    skillName: String,
    query: String,
    title: String,
    config: SubagentSkill.SubagentConfig,
    // [fix/sweep1014-b19] Verification note: this parameter is deliberately
    // NOT referenced inside the loop body — the production streamProvider
    // (ChatPromptAndTools.executeSpawnAgentTool) closes over the same
    // systemPrompt and already passes it to ProviderExecutionGateway.stream
    // on every turn, so the request DOES carry it. Keep the parameter so the
    // loop's signature stays self-describing; do not "fix" it into the
    // lambda signature without also updating that call site.
    systemPrompt: String,
    streamProvider: suspend (messages: List<LLMMessage>) -> kotlinx.coroutines.flow.Flow<LLMStreamChunk>,
    executeSubTool: suspend (name: String, argsJson: String) -> ToolExecutionResult,
    knownToolNames: List<String>,
    log: (String) -> Unit,
): ToolExecutionResult {
    val resultSb = StringBuilder()
    var turns = 0
    var lastText = ""
    var subResidueNudges = 0
    // [fix/sweep1014-b19] Truncated-turn tracking (main loop's turnFinishReason
    // counterpart): Finished carries both the protocol stopReason and the
    // stream-level truncated flag (EOF / connection drop mid-stream).
    var turnFinishReason: String? = null
    var turnTruncated = false
    var truncationContinues = 0
    var truncatedAtFinish = false
    val history = mutableListOf(LLMMessage(role = LLMMessage.Role.USER, content = query))

    try {
        while (turns < config.maxTurns) {
            turns++
            // [fix/sweep1014-b19] Per-turn reset: a turn without a Finished
            // chunk must not inherit the previous turn's verdict.
            turnFinishReason = null
            turnTruncated = false
            val textSb = StringBuilder()
            // [deep-audit-1018 ISSUE-02] Per-turn reasoning capture — declared
            // inside the loop, so per-turn freshness mirrors the main loop's
            // per-turn `turnThinking`/`turnReasoningBlob` locals. Both chunk
            // kinds were previously dropped on the floor here (`else -> {}`),
            // so every sub-agent assistant history entry went back without
            // reasoning_content.
            val turnReasoning = SubagentReasoningAccumulator()
            val toolCalls = mutableListOf<SubagentToolCall>()

            // TF-D: sub-agent runs through :modelservice via the gateway. Chunks
            // are accumulated incrementally as they stream in — never buffered
            // wholesale via `toList()` (unbounded retention of the whole turn).
            streamProvider(history.toList()).collect { chunk ->
                when (chunk) {
                    is LLMStreamChunk.Text -> textSb.append(chunk.text)
                    // [deep-audit-1018 ISSUE-02] Reasoning chunks were silently
                    // discarded here (they fell into `else -> {}`) while the
                    // main loop has handled them since
                    // [T-android-thinking-delta-main-thread-throttle] /
                    // ReasoningContent. Result: sub-agent assistant history
                    // entries carried no reasoning_content, so a thinking
                    // provider re-read its own turns as if it had never
                    // reasoned (and a hard-validating relay could 400).
                    // The accumulator owns the exact main-loop semantics
                    // (blob wins, empty blob round-trips, thinking concatenates).
                    is LLMStreamChunk.ThinkingDelta -> turnReasoning.accept(chunk)
                    is LLMStreamChunk.ReasoningContent -> turnReasoning.accept(chunk)
                    is LLMStreamChunk.ToolCallComplete -> {
                        toolCalls.add(SubagentToolCall(chunk.id, chunk.name, chunk.args))
                    }
                    // [fix/sweep1014-b19] Was silently ignored — a truncated
                    // turn read as a clean finish. Track both signals.
                    is LLMStreamChunk.Finished -> {
                        turnFinishReason = chunk.stopReason
                        turnTruncated = chunk.truncated
                    }
                    else -> {}
                }
            }

            // [fix/same-class-cleanup] Same policy as the main loop's
            // [fix/tool-call-copy-suppress] Fix 1/2 — second consumer. The
            // reply can carry a RESTATED tool call as plain text (character
            // drift drops the underscores, so no parser matches). Two things,
            // both silent before this change:
            //   1. the copy reached resultSb and the next request (the
            //      sub-agent's result IS its persisted text — the main agent
            //      then reads the markup as its own);
            //   2. when nothing parsed as a call, the break below read as a
            //      clean completion ("Model finished naturally") while the
            //      sub-agent's task was unfinished.
            // Detect on the RAW text (before stripping — a stripped text no
            // longer carries the marker), strip for the visible/result text,
            // and refill instead of finishing. Bounded so a markup-shaped
            // false positive cannot loop forever.
            val rawText = textSb.toString()
            val residue = ToolCallResiduePolicy.firstResidue(rawText, knownToolNames)
            val text = if (residue != null) {
                ToolCallResiduePolicy.stripResidue(rawText, knownToolNames)
            } else {
                rawText
            }
            lastText = text
            if (text.isNotBlank()) {
                if (resultSb.isNotEmpty()) resultSb.append('\n')
                resultSb.append(text)
            }

            if (toolCalls.isEmpty()) {
                if (residue != null && subResidueNudges < ToolCallResiduePolicy.MAX_RESIDUE_REFILL_NUDGES) {
                    subResidueNudges++
                    val refill = ToolCallResiduePolicy.refillMessage(residue)
                    // [fix/da1017-subagent-residue] The residue turn's VISIBLE
                    // text is the assistant turn — record it before the nudge.
                    // Without it the model never sees what it just wrote (the
                    // refill names only the tool, never the prose) and restates
                    // the same text on the retry, so `resultSb` carries it
                    // twice. Same contract as the main loop, where the turn's
                    // stripped text enters agentHistory (AgentLoopEngine) right
                    // before its refill nudge.
                    appendSubagentTurnText(history, text, turnReasoning.value())
                    // Same invariant as the main loop's nudges: tool results
                    // are persisted to history as role=USER messages, so the
                    // tail here can be user(tool_result) — a blind USER append
                    // yields two consecutive user roles (Anthropic hard 400 /
                    // OpenAI silent merge). Bridge first.
                    ensureRoleAlternationBeforeUserAppend(history)
                    history.add(LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = refill,
                        contentParts = listOf(AgentContentPart.Text(refill)),
                    ))
                    log("[Subagent] turn=$turns tool-call residue in reply text (name=${residue.rawName}) " +
                        "— refilling $subResidueNudges/${ToolCallResiduePolicy.MAX_RESIDUE_REFILL_NUDGES} instead of finishing")
                    continue
                }
                // [fix/sweep1014-b19] Truncated sub-agent turn — was silently
                // treated as a clean finish (sub-agent truncation looked like
                // normal completion). Reuse the main loop's
                // [T-length-wall-continue] policy, sub-agent shape: a
                // non-empty truncated turn CONTINUES (assistant partial stays
                // in history as prefill + the same lengthWallReminder the main
                // loop injects, so the model resumes at the exact cut point);
                // an empty truncated turn (whole budget burned producing
                // nothing usable) does not re-bill for the same wall — it
                // finishes but carries a truncation note instead of passing
                // silently. Bounded by config.maxTurns (each continue consumes
                // a turn) plus the explicit ceiling below.
                if (TruncatedToolCallPolicy.isTruncatedFinish(turnFinishReason) || turnTruncated) {
                    if (text.isNotBlank() && truncationContinues < MAX_SUBAGENT_TRUNCATION_CONTINUES) {
                        truncationContinues++
                        // [fix/da1017-subagent-residue] Same hand-back contract
                        // as the residue refill above: the partial turn stays in
                        // history, so the model resumes at the exact cut point
                        // instead of re-writing the part it already emitted.
                        appendSubagentTurnText(history, text, turnReasoning.value())
                        ensureRoleAlternationBeforeUserAppend(history)
                        val reminder = lengthWallReminder(text.takeLast(80))
                        history.add(LLMMessage(
                            role = LLMMessage.Role.USER,
                            content = reminder,
                            contentParts = listOf(AgentContentPart.Text(reminder)),
                        ))
                        log("[Subagent] turn=$turns finish=${turnFinishReason ?: "eof"} truncated " +
                            "(${text.length} chars) — continuing ($truncationContinues/$MAX_SUBAGENT_TRUNCATION_CONTINUES)")
                        continue
                    }
                    truncatedAtFinish = true
                }
                // Model finished naturally — no more tool calls
                break
            }

            // Append assistant turn with tool uses to history
            // [deep-audit-1018 ISSUE-02] Carry the turn's captured reasoning
            // too — same round-trip the main loop performs at its
            // `agentHistory.add(...)` (AgentLoopEngine, turnReasoningContent).
            history.add(LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = text,
                contentParts = toolCalls.map { call ->
                    AgentContentPart.ToolUse(id = call.id, name = call.name, input = call.args)
                },
                reasoningContent = turnReasoning.value(),
            ))

            // Execute tools sequentially. [deep-audit-1018 ISSUE-01] The
            // main loop's truncated-tool-call guard was absent here (this was
            // the family's remaining site): a truncated turn's tool call was
            // dispatched to the executor with whatever argument prefix had
            // streamed — and ToolJsonRepair's truncation strategy closes a
            // cut-off JSON object into a valid-but-wrong payload, so the
            // failure mode is "silently executed half a dangerous command".
            // The judgement comes from TruncatedToolCallPolicy (single source
            // of truth — wording included), covering BOTH sources: the
            // protocol finish reason and the stream-level truncated flag.
            // A refused call still gets a tool_result (pairing holds) and the
            // loop continues so the model can re-issue with full arguments.
            for (call in toolCalls) {
                val truncatedRefusal = TruncatedToolCallPolicy.rejectionReason(
                    call.name,
                    turnFinishReason,
                    turnTruncated,
                )
                if (truncatedRefusal != null) {
                    log("[Subagent] turn=$turns truncated tool call REFUSED name=${call.name} " +
                        "id=${call.id} finish=${turnFinishReason ?: "eof"} eofTruncated=$turnTruncated")
                    history.add(LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "Result of ${call.name} (${call.id}):\n$truncatedRefusal",
                        contentParts = listOf(AgentContentPart.ToolResult(
                            id = call.id, name = call.name,
                            content = truncatedRefusal, isError = true,
                        )),
                    ))
                } else {
                    val result = executeSubTool(call.name, call.args.toString())
                    // [fix/sweep1014-b19] Was "Error: ${result.output}" even when
                    // the sub-tool's own failure output already carries the
                    // "Error: " prefix (budget exhausted, provider errors) — the
                    // next request read "Error: Error: ...".
                    val resultContent = if (result.success) {
                        result.output
                    } else if (result.output.startsWith("Error: ")) {
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
        }
    } catch (e: CancellationException) {
        // [fix/da1017-subagent-residue] A stop / cancellation is not a
        // sub-agent failure — the generic catch below used to swallow it and
        // hand back a "Sub-agent '<name>' encountered an error" result (a
        // stop reads as a crash, and the log line blames the model). Same
        // shape as the other executors in this file. Cancellation is
        // cooperative: rethrow so the caller's own cleanup runs.
        throw e
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
            "Sub-agent '$skillName' completed in $turns turn(s) with no output." +
                // [fix/sweep1014-b19] Empty + truncated: the whole budget was
                // burned producing nothing usable — carry the truncation note
                // instead of passing as a clean empty completion.
                if (truncatedAtFinish) {
                    " The final turn was cut off at the model's output limit " +
                        "(${turnFinishReason ?: "eof"})."
                } else {
                    ""
                },
            true, toolTitle = title,
        )
    }

    val summary = "Sub-agent '$skillName' completed in $turns turn(s).\n\n---\n$finalText" +
        // [fix/sweep1014-b19] The final turn was cut off at the output
        // ceiling — never pass a truncated result as a clean completion.
        if (truncatedAtFinish) {
            "\n\n[Note: the sub-agent's final turn was cut off at the model's output limit " +
                "(${turnFinishReason ?: "eof"}); the result above may be incomplete.]"
        } else {
            ""
        }
    return ToolExecutionResult(summary, true, toolTitle = title)
}

/**
 * Mirror of iOS AIChatViewModel post-tool hook: when the agent writes or
 * edits a SKILL.md inside a `/skills/` directory we ask SkillRepository to
 * re-scan disk so the new skill lands in the repository immediately, without
 * waiting for app restart.
 *
 * NOTE (comment corrected 2026-09-14): the re-scan does NOT make the skill
 * visible to the running model on the next turn. The system prompt is frozen
 * per session by `systemPromptForSession()` (see the T-skillscan note in
 * ChatPromptAndTools.kt), so a skill installed mid-session only reaches the
 * prompt when the next session starts.
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
