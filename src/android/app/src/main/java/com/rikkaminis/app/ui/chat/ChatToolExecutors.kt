package com.rikkaminis.app.ui.chat

import android.content.Context
import com.rikkaminis.app.browser.BrowserActionInput
import com.rikkaminis.app.browser.BrowserTabPool
import com.rikkaminis.app.data.repository.SkillRepository
import com.rikkaminis.app.tools.ToolExecutionResult
import com.rikkaminis.app.tools.resolveToolTitle
import kotlinx.coroutines.CancellationException
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
