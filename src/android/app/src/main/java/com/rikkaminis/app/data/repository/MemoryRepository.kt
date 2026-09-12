package com.rikkaminis.app.data.repository

import android.util.Log
import com.rikkaminis.app.data.AgentRuntimeLimitsPrefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages the memory directory (`minis-global/memory/`).
 * Mirrors iOS memory system:
 *   - GLOBAL.md: read-only for agent, user-maintained via Settings
 *   - YYYY-MM-DD.md: daily logs with timestamped entries, agent writes via memory_write
 *   - memory_get: fuzzy keyword search across files
 *   - loadGlobalMemoryFragment() / loadRecentDailyMemoryFragment(): emit
 *     two separate text blocks for the system prompt (mirrors iOS exactly)
 */
class MemoryRepository(private val memoryDir: File) {

    companion object {
        private const val TAG = "MemoryRepository"
        private const val GLOBAL_FILE = "GLOBAL.md"
        // Rollup product file, living next to GLOBAL.md. Never treated as a
        // user-editable daily log; it is a distilled index owned by the
        // memory_rollup tool (see MemoryRollupEngine.ROLLUP_FILE).
        private const val ROLLUP_FILE =
            com.rikkaminis.app.workspace.MemoryRollupEngine.ROLLUP_FILE
        // [feat/chat-tuning-panel-b] MAX_INJECT_LINES → user-tunable
        // (AgentRuntimeLimitsPrefs.memoryInjectLines(), default 200).
        // [fix/send-prompt-bloat] Byte ceiling on the MEMORY-ROLLUP.md system-
        // prompt injection. The rollup grows monotonically with every
        // memory_rollup run (it reached 227 KB / ~1.6 K lines on 2026-08-27),
        // and `buildSystemPrompt()` previously injected it VERBATIM with no
        // cap on every send/retry — blowing up the fixed prompt prefix by tens
        // of thousands of tokens and making each send slower + costlier as the
        // file grows. Counterpart of MAX_INJECT_LINES (daily logs) and
        // MAX_OUTPUT_BYTES (memory_get). 12 KB ≈ 4 K CJK chars ≈ 4 K tokens;
        // the tail is kept preferentially because rollups append
        // chronologically (newest distilled rules live at the end).
        // [feat/chat-tuning-panel-b] MAX_ROLLUP_INJECT_BYTES → user-tunable
        // (AgentRuntimeLimitsPrefs.memoryRollupInjectKb(), default 12 KB).
        // memory_get full-dump (no keywords): cap at 500 lines — matches iOS
        // `maxTotalLines = 500` in AIChatViewModel+MemoryTools.swift.
        private const val MAX_DUMP_LINES = 500
        // memory_get keyword search: cap at 60 lines. iOS caps at 60 *entries*
        // (timestamp-delimited memory_write blocks); Android's keyword search
        // is line-based with ±2 context windows, so we keep the same algorithm
        // and align on the 60 magnitude as the line budget.
        // [feat/chat-tuning-panel-b] MAX_SEARCH_LINES → user-tunable
        // (AgentRuntimeLimitsPrefs.memorySearchLines(), default 60).
        // [feat/chat-tuning-panel-b] MAX_LOOKBACK_DAYS → user-tunable
        // (AgentRuntimeLimitsPrefs.memoryLookbackDays(), default 30).
        private const val MAX_RECENT_FILES = 3
        // [T-memory-get-truncate-android] Hard byte ceiling on memory_get
        // output. Line caps alone (MAX_DUMP_LINES / MAX_SEARCH_LINES) don't
        // bound bandwidth when a single matched line is itself huge — TG
        // 37452 hit a 70KB single-call result that froze the chat UI for
        // several seconds when expanded. 30KB is the comfort budget for
        // an agent tool result that needs to be both rendered AND fed
        // back into the next LLM call. Counted as UTF-8 bytes (matches
        // what the provider sees over the wire).
        private const val MAX_OUTPUT_BYTES = 30 * 1024  // 30 KB
    }

    init {
        memoryDir.mkdirs()
    }

    /** Expose the memory directory (used by the memory_rollup tool). */
    fun memoryDirectory(): File = memoryDir

    /**
     * Compact per-file size summary of daily logs, used by the system prompt
     * to decide whether a memory_rollup is worth triggering. Returns the
     * largest daily log (name + size) plus the total, or null when empty.
     */
    fun dailyLogSizeSummary(): String? {
        val sizes = dailyLogSizes() ?: return null
        val total = sizes.sumOf { it.second }
        val largest = sizes.first()
        return "largest daily log ${largest.first} (${formatFileSize(largest.second)}), " +
            "total ${sizes.size} logs ${formatFileSize(total)}"
    }

    /**
     * Largest daily log size in bytes (0 when no daily logs exist). Used by
     * the system prompt to decide whether to suggest memory_rollup.
     */
    fun largestDailyLogBytes(): Long {
        return dailyLogSizes()?.firstOrNull()?.second ?: 0L
    }

    private fun dailyLogSizes(): List<Pair<String, Long>>? {
        return memoryDir.listFiles()
            ?.filter {
                it.extension == "md" &&
                    it.name != GLOBAL_FILE &&
                    it.name != ROLLUP_FILE
            }
            ?.map { it.name to it.length() }
            ?.sortedByDescending { it.second }
    }

    // -- memory_write --

    /**
     * Append a timestamped entry to today's daily log.
     * New entries are prepended (newest first).
     * Returns a success/error message string.
     */
    fun writeMemory(content: String): String {
        if (content.isBlank()) return "Error: Missing required 'content' parameter"

        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val fileName = "${dateFmt.format(Date())}.md"
        val file = File(memoryDir, fileName)

        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val timestamp = timeFmt.format(Date())
        val entry = "<!-- $timestamp -->\n$content\n\n"

        // [P1-append] Append to end instead of prepending. Prepending
        // (read entire file + concat + write) is O(n²) per write; append
        // is O(1). All read paths (getMemory, revokeEntry, replaceEntryBody)
        // use marker-regex and are order-agnostic. The two paths that care
        // about newest-first (system prompt injection, file list preview)
        // are adapted below.
        return try {
            file.appendText(entry)
            Log.i(TAG, "Memory written to $fileName (${content.length} chars)")
            "Memory saved to $fileName (${content.length} chars)"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write memory", e)
            "Error writing memory: ${e.message}"
        }
    }

    // -- memory_get --

    /**
     * Fuzzy keyword search across memory files.
     * @param keywords space-separated, case-insensitive, ALL must match
     * @param scope "daily" (logs only) or "all" (include GLOBAL.md)
     * @return search results with context lines
     */
    fun getMemory(keywords: String, scope: String): String {
        val keywordList = keywords.trim()
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

        val filesToSearch = mutableListOf<Pair<String, File>>() // label to file

        if (scope == "all") {
            val globalFile = File(memoryDir, GLOBAL_FILE)
            if (globalFile.exists() && globalFile.length() > 0) {
                filesToSearch.add(GLOBAL_FILE to globalFile)
            }
        }

        // Daily logs sorted descending. Exclude the rollup product file — its
        // content is a distillation of the daily logs, so searching it would
        // duplicate every entry and waste the search line/byte budget.
        val dailyFiles = memoryDir.listFiles()
            ?.filter { it.extension == "md" && it.name != GLOBAL_FILE && it.name != ROLLUP_FILE }
            ?.sortedByDescending { it.name }
            ?: emptyList()

        for (file in dailyFiles) {
            filesToSearch.add(file.name to file)
        }

        if (filesToSearch.isEmpty()) {
            return "No memory files found."
        }

        val results = mutableListOf<String>()
        var totalLines = 0
        // [T-memory-get-truncate-android] UTF-8 byte tally — see
        // MAX_OUTPUT_BYTES rationale in the companion object. Bytes are
        // accumulated AFTER appending each result entry; the line check is
        // still consulted first so we never over-allocate slicing windows.
        var totalBytes = 0
        // Total ranges/files matched vs. files actually included in the
        // returned output. Reported in the truncation note so the caller
        // can tell whether the cap dropped further matches.
        var totalMatchedFiles = 0
        var includedFiles = 0
        var byteCapHit = false
        // Two separate caps so a full dump (no keywords) gets enough room to
        // show recent daily logs while keyword searches stay tight enough not
        // to flood agent context.
        // [feat/chat-tuning-panel-b] Search cap is user-tunable now.
        val lineCap = if (keywordList.isEmpty()) MAX_DUMP_LINES else AgentRuntimeLimitsPrefs.memorySearchLines()

        for ((label, file) in filesToSearch) {
            if (totalLines >= lineCap || byteCapHit) break
            val content = try { file.readText() } catch (_: Exception) { continue }
            if (content.isEmpty()) continue
            val budget = lineCap - totalLines

            val entry: String? = if (keywordList.isEmpty()) {
                // Return file preview
                val lines = content.lines()
                val take = minOf(lines.size, budget)
                val preview = lines.take(take).joinToString("\n")
                val truncated = if (lines.size > take) " (showing first $take of ${lines.size} lines)" else ""
                totalLines += take
                totalMatchedFiles += 1
                "[$label$truncated]\n$preview"
            } else {
                // Keyword search with ±2 context window
                val lines = content.lines()
                val matchedRanges = mutableListOf<IntRange>()

                for (i in lines.indices) {
                    val windowStart = maxOf(0, i - 2)
                    val windowEnd = minOf(lines.size - 1, i + 2)
                    val windowText = lines.subList(windowStart, windowEnd + 1)
                        .joinToString(" ").lowercase()

                    if (keywordList.all { windowText.contains(it) }) {
                        matchedRanges.add(windowStart..windowEnd)
                    }
                }

                if (matchedRanges.isEmpty()) {
                    null
                } else {
                    totalMatchedFiles += 1
                    val merged = mergeRanges(matchedRanges)
                    val fileMatches = mutableListOf<String>()

                    for (range in merged) {
                        val chunkLines = range.last - range.first + 1
                        if (totalLines + chunkLines > lineCap) {
                            val remaining = lineCap - totalLines
                            if (remaining > 0) {
                                fileMatches.add(lines.subList(range.first, range.first + remaining).joinToString("\n"))
                                totalLines += remaining
                            }
                            break
                        }
                        fileMatches.add(lines.subList(range.first, range.last + 1).joinToString("\n"))
                        totalLines += chunkLines
                    }

                    if (fileMatches.isNotEmpty())
                        "[$label — ${fileMatches.size} match(es)]\n${fileMatches.joinToString("\n---\n")}"
                    else null
                }
            }

            if (entry != null) {
                results.add(entry)
                includedFiles += 1
                // Byte accounting is conservative: count the entry itself
                // PLUS the "\n\n" separator between entries (added at the
                // joinToString tail). We break AFTER appending so a single
                // large entry never gets silently dropped — the cap acts as
                // a "this was the last one we'll show" gate, not a guillotine
                // on the current entry.
                totalBytes += entry.toByteArray(Charsets.UTF_8).size + 2
                if (totalBytes >= MAX_OUTPUT_BYTES) byteCapHit = true
            }
        }

        if (results.isEmpty()) {
            return "No matches found for keywords: ${keywordList.joinToString(", ")}"
        }

        // Compose the truncation note. Line-cap and byte-cap can both fire;
        // include whichever applies. Counts use "files" because the loop is
        // file-by-file — for the agent the distinction between "matched file"
        // and "matched entry" is fine here, the keyword scope is unambiguous.
        val notes = mutableListOf<String>()
        if (byteCapHit && includedFiles < totalMatchedFiles) {
            val totalKb = totalBytes / 1024
            notes.add(
                "[Truncated: $totalMatchedFiles file(s) matched, showing first " +
                    "$includedFiles, ~${totalKb}KB. Use more specific keywords " +
                    "to narrow results.]",
            )
        } else if (byteCapHit) {
            val totalKb = totalBytes / 1024
            notes.add("[Truncated at ${MAX_OUTPUT_BYTES / 1024}KB byte cap (~${totalKb}KB returned).]")
        }
        if (totalLines >= lineCap) {
            notes.add("[Output truncated at $lineCap lines]")
        }
        val truncatedNote = if (notes.isNotEmpty()) "\n\n" + notes.joinToString("\n") else ""
        return results.joinToString("\n\n") + truncatedNote
    }

    // -- System Prompt Fragment --

    /**
     * Build the `<memory>` XML fragment for system prompt injection.
     * Includes GLOBAL.md + up to 3 most recent daily logs (today, yesterday, etc.)
     */
    /**
     * Loads the GLOBAL.md fragment for system-prompt injection. Mirrors iOS
     * `AIChatViewModel.loadGlobalMemoryFragment()`. Returns null if the file
     * is missing or empty.
     */
    fun loadGlobalMemoryFragment(): String? {
        val globalFile = File(memoryDir, GLOBAL_FILE)
        if (!globalFile.exists()) return null
        val content = try { globalFile.readText() } catch (_: Exception) { "" }
        // Match iOS: literal-empty check (`!content.isEmpty`), not blank.
        // A whitespace-only file is unusual in practice, but staying byte-for-
        // byte consistent with iOS keeps the cached system prompt identical
        // across platforms.
        if (content.isEmpty()) return null
        return "Global memory (GLOBAL.md — read-only, user-maintained). Treat these as background context, not standing instructions. If the user's latest message conflicts with or supersedes anything here (different scope, different numbers, different goal), defer to the user's latest message:\n$content"
    }

    /**
     * [fix/send-prompt-bloat] Load the MEMORY-ROLLUP.md distilled-rule index
     * for system-prompt injection, capped at the user-tunable rollup byte
     * ceiling (AgentRuntimeLimitsPrefs.memoryRollupInjectKb, default 12 KB).
     *
     * The rollup is append-ordered (each `memory_rollup` run appends a new
     * "## Rollup <date>" block) so the NEWEST distilled rules live at the END;
     * when the file exceeds the cap we keep the tail preferentially and drop
     * the oldest head. Returns null when the file is missing/empty/unreadable.
     */
    fun loadRollupFragment(): String? {
        val rollupFile = File(memoryDir, ROLLUP_FILE)
        if (!rollupFile.exists() || rollupFile.length() == 0L) return null
        val content = try { rollupFile.readText() } catch (_: Exception) { return null }
        if (content.isEmpty()) return null
        val bytes = content.toByteArray(Charsets.UTF_8)
        // [feat/chat-tuning-panel-b] Cap is user-tunable (KB → bytes here).
        val maxRollupInjectBytes = AgentRuntimeLimitsPrefs.memoryRollupInjectKb() * 1024
        if (bytes.size <= maxRollupInjectBytes) return content
        // Drop the oldest bytes (keep the tail = newest distilled rules) —
        // after advancing the cut point to a valid UTF-8 boundary so a multi-
        // byte code point split in half never decodes into U+FFFD replacement
        // chars at the head of the injected fragment.
        val headDropped = rollupTailAtBoundary(bytes, bytes.size - maxRollupInjectBytes)
        return "... (older rollup rules truncated: tail of $maxRollupInjectBytes bytes kept, use memory_get to search)\n" +
            headDropped
    }

    /**
     * Decode the byte suffix from `start`, advancing `start` to the next valid
     * UTF-8 boundary first if it lands inside a multi-byte sequence. A raw
     * `String(bytes, start, n)` silently inserts U+FFFD for a split code
     * point; the REPORT-action decoder instead tells us where the first
     * undecodable byte is, and we re-try just past it (UTF-8 continuation
     * bytes are at most 3 leading 0x10xxxxxx bits, so at most a few probes).
     * Falls back to a replacement-free decoder on the rare malformed tail.
     */
    private fun rollupTailAtBoundary(bytes: ByteArray, start: Int): String {
        if (start <= 0) return String(bytes, Charsets.UTF_8)
        var probe = start
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        while (probe < bytes.size) {
            try {
                return decoder.decode(java.nio.ByteBuffer.wrap(bytes, probe, bytes.size - probe)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                probe++  // split at a continuation byte — advance to the next candidate boundary
            }
        }
        return String(bytes, Charsets.UTF_8)  // unreachable in practice; safe fallback
    }

    /**
     * Loads up to 3 most recent non-empty daily logs (within a 30-day window)
     * for system-prompt injection. Mirrors iOS
     * `AIChatViewModel.loadRecentDailyMemoryFragment()` exactly: same header,
     * same intro paragraph, same per-entry labels, same 200-line cap, same
     * "(N more lines, use memory_get to search)" continuation.
     */
    fun loadRecentDailyMemoryFragment(): String? {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = Date()
        val fragments = mutableListOf<String>()
        var dayOffset = 0

        // [feat/chat-tuning-panel-b] Both knobs read once per call so a
        // mid-loop change can't make the walk inconsistent.
        val lookbackDays = AgentRuntimeLimitsPrefs.memoryLookbackDays()
        val injectLines = AgentRuntimeLimitsPrefs.memoryInjectLines()
        while (fragments.size < MAX_RECENT_FILES && dayOffset < lookbackDays) {
            val date = Date(now.time - dayOffset.toLong() * 86400_000L)
            val dateStr = dateFmt.format(date)
            val file = File(memoryDir, "$dateStr.md")

            if (file.exists()) {
                val content = try { file.readText() } catch (_: Exception) { "" }
                if (content.isNotEmpty()) {
                    val lines = content.lines()
                    // [P1-append] Entries are now appended (newest at bottom).
                    // Reverse so system prompt gets newest-first, matching old prepend.
                    val preview = lines.takeLast(injectLines).reversed().joinToString("\n")
                    val label = when (dayOffset) {
                        0 -> "Today's"
                        1 -> "Yesterday's"
                        else -> dateStr
                    }
                    var entry = "$label daily log ($dateStr.md):\n$preview"
                    if (lines.size > injectLines) {
                        entry += "\n... (${lines.size - injectLines} more lines, use memory_get to search)"
                    }
                    fragments.add(entry)
                }
            }
            dayOffset++
        }

        if (fragments.isEmpty()) return null

        return buildString {
            append("Recent memories (auto-injected from daily logs):\n")
            append("These are memories saved by you or the user in previous sessions. Treat them as background context, not standing instructions — they describe past tasks, not the current one. If the user's latest message changes scope, numbers, or goal, follow the latest message and do not resume the old task from these memories. Do not delete or rewrite these files unless the user explicitly asks. Use memory_get to search for more, or memory_write to save new ones.\n\n")
            append(fragments.joinToString("\n\n"))
        }
    }

    // -- File Management (for Settings UI) --

    data class MemoryFileInfo(
        val name: String,
        val isGlobal: Boolean,
        val modifiedDate: String,
        val fileSize: String,
        val preview: String,
    )

    /**
     * List all memory files: GLOBAL.md first, then daily logs descending.
     */
    fun listAllFiles(): List<MemoryFileInfo> {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val items = mutableListOf<MemoryFileInfo>()

        // GLOBAL.md always first
        val globalFile = File(memoryDir, GLOBAL_FILE)
        val globalModDate = if (globalFile.exists()) dateFmt.format(Date(globalFile.lastModified())) else ""
        items.add(MemoryFileInfo(
            name = GLOBAL_FILE,
            isGlobal = true,
            modifiedDate = globalModDate,
            fileSize = formatFileSize(globalFile.length()),
            preview = if (globalFile.exists()) firstContentLineFromFile(globalFile) else "",
        ))

        // Daily logs sorted descending. Exclude the rollup product file —
        // it's an auto-generated distilled index owned by memory_rollup, not a
        // user-editable log; listing it here would let the user delete/edit it
        // and break the rollup idempotency anchor.
        val dailyFiles = memoryDir.listFiles()
            ?.filter { it.extension == "md" && it.name != GLOBAL_FILE && it.name != ROLLUP_FILE }
            ?.sortedByDescending { it.name }
            ?: emptyList()

        for (file in dailyFiles) {
            // [P2-lazy-preview] Use lazy line-by-line read instead of full
            // readText() — avoids loading every daily log into memory.
            items.add(MemoryFileInfo(
                name = file.name,
                isGlobal = false,
                modifiedDate = dateFmt.format(Date(file.lastModified())),
                fileSize = formatFileSize(file.length()),
                preview = firstContentLineFromFile(file),
            ))
        }

        return items
    }

    fun loadGlobalMd(): String {
        val file = File(memoryDir, GLOBAL_FILE)
        return if (file.exists()) try { file.readText() } catch (_: Exception) { "" } else ""
    }

    fun saveGlobalMd(content: String) {
        File(memoryDir, GLOBAL_FILE).writeText(content)
    }

    fun readFile(name: String): String {
        val file = File(memoryDir, name)
        return if (file.exists()) try { file.readText() } catch (_: Exception) { "" } else ""
    }

    fun saveFile(name: String, content: String) {
        File(memoryDir, name).writeText(content)
    }

    fun deleteFile(name: String): Boolean {
        if (name == GLOBAL_FILE) return false // Cannot delete GLOBAL.md
        return File(memoryDir, name).delete()
    }

    // -- Entry-level operations (used by Session Memory revoke/edit) --

    /**
     * Result of [revokeEntry] / [replaceEntryBody]. Mirrors iOS
     * `revokeEntry` / `replaceEntryInLog` return shapes.
     */
    sealed class EntryMutationResult {
        /** Entry found in [dateStr].md and the requested mutation succeeded. */
        data class Success(val dateStr: String) : EntryMutationResult()

        /** Scanned today + yesterday but the body never matched. */
        data object NotFound : EntryMutationResult()

        /** Match found but writing the new file content failed. */
        data class IOError(val message: String) : EntryMutationResult()
    }

    /**
     * Remove a memory_write entry whose body matches [writtenContent] from
     * today's or yesterday's daily log. Mirrors iOS
     * `MemoryWriteDetailView.revokeEntry()`.
     *
     * Each entry on disk is `<!-- YYYY-MM-DD HH:mm:ss -->\n{body}\n\n`. The
     * comment marker is the canonical entry boundary; we split on it via
     * regex, locate the entry whose trimmed body equals the trimmed
     * [writtenContent], then erase the entire range (marker + body +
     * trailing whitespace).
     *
     * Scope is intentionally limited to today + yesterday to match iOS — older
     * entries are presumed already syndicated into the model's longer-term
     * memory and shouldn't be silently mutated by an undo button.
     */
    fun revokeEntry(writtenContent: String): EntryMutationResult {
        val trimmedTarget = writtenContent.trim()
        val candidates = candidateDateStrings()
        val markerRegex = Regex("""<!-- \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} -->\n""")

        for (dateStr in candidates) {
            val file = File(memoryDir, "$dateStr.md")
            if (!file.exists()) continue
            val content = try { file.readText() } catch (_: Exception) { continue }

            val matches = markerRegex.findAll(content).toList()
            if (matches.isEmpty()) continue

            for ((i, match) in matches.withIndex()) {
                val bodyStart = match.range.last + 1
                val entryEnd = matches.getOrNull(i + 1)?.range?.first ?: content.length
                val body = content.substring(bodyStart, entryEnd)
                if (body.trim() != trimmedTarget) continue

                val newContent = content.removeRange(match.range.first, entryEnd)
                return try {
                    file.writeText(newContent)
                    Log.i(TAG, "Revoked memory entry from $dateStr.md")
                    EntryMutationResult.Success(dateStr)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write $dateStr.md after revoke", e)
                    EntryMutationResult.IOError(e.message ?: "Unknown I/O error")
                }
            }
        }
        return EntryMutationResult.NotFound
    }

    /**
     * Replace the body of an existing memory_write entry whose body matches
     * [oldContent], substituting [newContent]. Same scoping/matching rules as
     * [revokeEntry]. Mirrors iOS `MemoryWriteDetailView.replaceEntryInLog()`.
     */
    fun replaceEntryBody(oldContent: String, newContent: String): EntryMutationResult {
        val trimmedOld = oldContent.trim()
        val trimmedNew = newContent.trim()
        val candidates = candidateDateStrings()
        val markerRegex = Regex("""<!-- \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} -->\n""")

        for (dateStr in candidates) {
            val file = File(memoryDir, "$dateStr.md")
            if (!file.exists()) continue
            val content = try { file.readText() } catch (_: Exception) { continue }

            val matches = markerRegex.findAll(content).toList()
            if (matches.isEmpty()) continue

            for ((i, match) in matches.withIndex()) {
                val bodyStart = match.range.last + 1
                val entryEnd = matches.getOrNull(i + 1)?.range?.first ?: content.length
                val body = content.substring(bodyStart, entryEnd)
                if (body.trim() != trimmedOld) continue

                // iOS replaces with `trimmed + "\n\n"` so the on-disk
                // separator between entries stays uniform.
                val replacement = "$trimmedNew\n\n"
                val newFileContent = content.replaceRange(bodyStart, entryEnd, replacement)
                return try {
                    file.writeText(newFileContent)
                    Log.i(TAG, "Replaced memory entry body in $dateStr.md")
                    EntryMutationResult.Success(dateStr)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write $dateStr.md after edit", e)
                    EntryMutationResult.IOError(e.message ?: "Unknown I/O error")
                }
            }
        }
        return EntryMutationResult.NotFound
    }

    private fun candidateDateStrings(): List<String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = Date()
        return listOf(fmt.format(now), fmt.format(Date(now.time - 86400_000L)))
    }

    // -- Internal --

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        return "%.1f MB".format(mb)
    }

    /**
     * Preview line used by [listAllFiles]. Reads only as many lines as needed
     * via [BufferedReader], stopping at the first non-comment, non-blank line.
     * Keeps memory O(1) regardless of file size — avoids loading every daily
     * log into memory when listing files in Settings → Memory.
     *
     * [P5-empty] If the file contains only HTML comments (edge case after
     * revokeEntry removes the last body entry), return a placeholder so the
     * file-list row doesn't look broken.
     */
    private fun firstContentLineFromFile(file: File): String {
        return try {
            java.io.BufferedReader(file.reader()).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (line.isNotBlank() && !line.startsWith("<!--")) {
                        return@use line.take(100)
                    }
                    line = reader.readLine()
                }
                "(empty)"
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val result = mutableListOf(sorted[0])
        for (i in 1 until sorted.size) {
            val last = result.last()
            val current = sorted[i]
            if (current.first <= last.last + 1) {
                result[result.lastIndex] = last.first..maxOf(last.last, current.last)
            } else {
                result.add(current)
            }
        }
        return result
    }
}
