package com.rikkaminis.app.tools

import android.content.Context
import com.rikkaminis.app.data.model.AgentToolDefinition
import com.rikkaminis.app.data.model.AgentToolParam
import com.rikkaminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File

object FileReadTool {

    private const val MAX_INPUT_BYTES = 32L * 1024 * 1024
    const val NAME = "file_read"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read a file from the Linux filesystem. Faster than shell_execute for reading files — no shell overhead. Returns file content with metadata. Rejects binary files.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Read Python script contents', 'Check system configuration file'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to read (e.g. /var/minis/workspace/data.csv)"),
            "offset" to AgentToolParam("integer", "1-based line number to start reading from (default: 1). Ignored when direction is 'tail'."),
            "lines" to AgentToolParam("integer", "Maximum number of lines to return (default: all lines up to max_length)"),
            "max_length" to AgentToolParam("integer", "Maximum character length of returned content (default: 15000)"),
            "direction" to AgentToolParam("string", "Read direction: 'head' (from start, default) or 'tail' (from end of file)"),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path", "offset", "lines", "direction", "max_length"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        // [fix/da1022-G] Resolve the title BEFORE the try (FileWriteTool pattern):
        // a try-scoped val left the catch arm with no title to report.
        val toolTitle = resolveToolTitle(argsJson, NAME)
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val offset = args.optInt("offset", 1).coerceAtLeast(1)
            // T-FILEREAD-CAP: hard upper bound on returned content length.
            // Pre-cap, the agent could ask for `max_length=1_000_000` and we
            // would happily inline a 400 KB base64 image into a tool_result —
            // which then renders as a single user-message bubble and locks
            // up Compose's StaticLayout / LineBreaker for tens of seconds
            // (see HangDetector report for session
            // e84882d7-2087-47f8-9300-ff2c897fe0b4: 820 KB partsJson, 43 s
            // hang in nComputeLineBreaks). Cap at 80 KB regardless of
            // requested value; the truncation tail below tells the agent the
            // full file size so it can paginate with offset/lines if needed.
            // iOS mirrors this cap in AIChatViewModel.executeFileRead.
            val MAX_LENGTH_HARD_CAP = 80_000
            val maxLength = args.optInt("max_length", 15000).coerceIn(1, MAX_LENGTH_HARD_CAP)
            val direction = args.optString("direction", "head")

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            // T123: per-session resolver — see FileWriteTool for rationale.
            val file = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)

            if (!file.exists()) {
                return ToolExecutionResult("Error: File not found: $path", false, toolTitle = toolTitle)
            }

            if (file.isDirectory) {
                return ToolExecutionResult("Error: Path is a directory: $path", false, toolTitle = toolTitle)
            }

            if (file.length() > MAX_INPUT_BYTES) {
                return ToolExecutionResult(
                    "Error: file too large for file_read; use shell pagination (max 32 MiB)",
                    false,
                    toolTitle = toolTitle,
                )
            }

            val size = file.length()

            // Binary detection: check first 8192 bytes for null bytes
            val isBinary = file.inputStream().use { input ->
                val buf = ByteArray(minOf(8192, size.toInt()))
                val read = input.read(buf)
                if (read > 0) buf.take(read).any { it == 0.toByte() } else false
            }

            if (isBinary) {
                return ToolExecutionResult(
                    "[$path | $size bytes | binary file — cannot display contents]",
                    true, toolTitle = toolTitle
                )
            }

            // [sweep1014-fix9] Streamed selection: never materializes all lines.
            // The old path (file.readLines() + subList) built a full
            // List<String> of the file even for a `lines=100` paged read —
            // up to the 32 MiB input cap held in memory at once (plus the
            // joined content string), spiking Java heap past the executor
            // watermark and triggering innocent PRoot shell reclaims.
            // Output is byte-identical to the old readLines path.
            // [WAVE-1118 D8-F03] `has` + `optInt` read `{"lines": null}` as 0 —
            // "return zero lines" for a caller that asked for no paging. The
            // reader helper treats an explicit null exactly like an absent key.
            val requestedLines = args.optArgIntOrNull("lines")
            val isTail = direction == "tail"

            var totalLines = 0
            val collected: List<String>
            if (requestedLines != null && requestedLines < 0) {
                // Replicate the old subList failure shape byte-for-byte:
                // ArrayList.subList threw IllegalArgumentException with the
                // concrete indices, surfaced by the catch below.
                totalLines = file.bufferedReader().use { countLines(it) }
                val msg = if (isTail) {
                    val from = totalLines - requestedLines
                    "fromIndex($from) > toIndex($totalLines)"
                } else {
                    val from = (offset - 1).coerceIn(0, totalLines)
                    val to = from + requestedLines
                    "fromIndex($from) > toIndex($to)"
                }
                throw IllegalArgumentException(msg)
            } else if (isTail && requestedLines != null) {
                // Two-pass: the tail window start depends on totalLines.
                // Pass 1 counts lines only; pass 2 reads just the window.
                totalLines = file.bufferedReader().use { countLines(it) }
                val skip = (totalLines - requestedLines).coerceAtLeast(0)
                collected = file.bufferedReader().use { streamWindow(it, skip, requestedLines, maxLength).lines }
            } else {
                val skip = if (isTail) 0 else offset - 1
                val limit = if (isTail) null else requestedLines
                val window = file.bufferedReader().use { streamWindow(it, skip, limit, maxLength) }
                totalLines = window.totalLines
                collected = window.lines
            }

            val bounds = windowBounds(direction, offset, requestedLines, totalLines)
            val capped = buildCappedContent(collected, maxLength)

            val header = "[$path | $size bytes | $totalLines lines | showing ${bounds.showStart}-${bounds.showEnd} of $totalLines" +
                // [audit-0917] Tell the agent the range was cut mid-way: the old header
                // kept claiming "showing X-Y" even when only the capped chars
                // survived, so a paginating agent silently saw partial content.
                (if (capped.truncated) " | content truncated by the max_length cap" else "") + "]"
            val content = if (capped.truncated) capped.content + "\n... (truncated)" else capped.content
            ToolExecutionResult("$header\n$content", true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error reading file: ${e.message}", false, toolTitle = toolTitle)
        }
    }
}

// [sweep1014-fix9] ---- streaming primitives (pure; extracted verbatim for JVM tests) ----

/**
 * Counts lines using [BufferedReader.readLine] semantics — exactly what
 * [java.io.File.readLines] uses (\n, \r\n, \r; no trailing empty line).
 */
internal fun countLines(reader: BufferedReader): Int {
    var n = 0
    while (reader.readLine() != null) n++
    return n
}

/**
 * One streamed window of lines: the collected prefix of the selection plus
 * the full-file line count. Lines beyond the window are counted but never
 * retained, so peak memory stays bounded by the window size instead of the
 * whole file.
 */
internal class StreamedWindow(val lines: List<String>, val totalLines: Int)

/**
 * Single streaming pass over [reader]: skips [skip] lines, then collects up
 * to [limitLines] lines (null = unlimited), stopping early once the joined
 * length of the collected lines exceeds [maxLength] — content beyond that is
 * cut by the max_length cap anyway, and the remaining lines are still counted
 * for the header's totalLines without being retained.
 */
internal fun streamWindow(reader: BufferedReader, skip: Int, limitLines: Int?, maxLength: Int): StreamedWindow {
    val out = ArrayList<String>()
    var joined = 0
    var total = 0
    var toSkip = skip
    var collecting = true
    while (true) {
        val line = reader.readLine() ?: break
        total++
        if (toSkip > 0) {
            toSkip--
            continue
        }
        if (!collecting) continue
        if (limitLines != null && out.size >= limitLines) {
            collecting = false
            continue
        }
        out.add(line)
        joined += line.length + (if (out.size > 1) 1 else 0)
        if (joined > maxLength) collecting = false
    }
    return StreamedWindow(out, total)
}

/**
 * Header window bounds, computed from the parameters and totalLines so they
 * describe the FULL selection even when the streamed collection stopped early
 * (byte-identical to the old subList-based header).
 */
internal class WindowBounds(val selSize: Int, val showStart: Int, val showEnd: Int)

internal fun windowBounds(direction: String, offset: Int, requestedLines: Int?, totalLines: Int): WindowBounds {
    return if (direction == "tail") {
        val selSize = (requestedLines ?: totalLines).coerceAtMost(totalLines)
        val showStart = totalLines - selSize + 1
        WindowBounds(selSize, showStart, showStart + selSize - 1)
    } else {
        val startIdx = (offset - 1).coerceIn(0, totalLines)
        val endIdx = (if (requestedLines != null) startIdx + requestedLines else totalLines).coerceAtMost(totalLines)
        val selSize = endIdx - startIdx
        WindowBounds(selSize, offset, offset + selSize - 1)
    }
}

internal class CappedContent(val content: String, val truncated: Boolean)

/**
 * Joins [lines] with "\n" capped at [maxLength] chars — identical to the old
 * `joinToString("\n").take(maxLength)`. [CappedContent.truncated] mirrors the
 * old truncation flag (full selection longer than maxLength); the caller
 * appends the `... (truncated)` marker.
 */
internal fun buildCappedContent(lines: List<String>, maxLength: Int): CappedContent {
    val sb = StringBuilder()
    var first = true
    for (line in lines) {
        // Budget full with more lines to come: the full join exceeds maxLength,
        // and take(maxLength) is exactly what we already have.
        if (sb.length >= maxLength) return CappedContent(sb.toString(), true)
        // Separator is tracked by line index, not by sb emptiness — an empty
        // line still consumes its "\n" in the old joinToString().take(maxLength).
        if (!first) sb.append('\n')
        first = false
        val remaining = maxLength - sb.length
        if (line.length <= remaining) {
            sb.append(line)
        } else {
            if (remaining > 0) sb.append(line, 0, remaining)
            return CappedContent(sb.toString(), true)
        }
    }
    return CappedContent(sb.toString(), sb.length > maxLength)
}

// [sweep1014-fix9] ---- end streaming primitives ----
