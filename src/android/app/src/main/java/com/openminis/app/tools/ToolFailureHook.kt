package com.openminis.app.tools

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Programmatic tool-failure logger — the "failure learning automation hook"
 * ported from OmniBot's SelfImprovingSkillFailureHook.
 *
 * Writes structured error blocks (aligned with [minis_auto_log.sh] log_error
 * format) into a per-session ERRORS.md when a tool fails. Deduplicates writes
 * by (toolName + summary) within a configurable time window so the same
 * recurring failure doesn't flood the file.
 *
 * Usage:
 * ```kotlin
 * val hook = ToolFailureHook(
 *     writeErrorBlock = { block -> file.appendText(block) },
 * )
 * hook.recordFailure("shell_execute", "command not found: foo", "sess-abc")
 * ```
 *
 * Pure JVM — no Android dependency. All time-dependent behaviour is
 * controlled by the injectable [clock] so unit tests can advance time
 * deterministically.
 */
class ToolFailureHook(
    /** Callback that persists a single formatted error block (appended to file). */
    private val writeErrorBlock: (block: String) -> Unit,
    /** Clock in epoch-millis (default: System.currentTimeMillis). */
    private val clock: () -> Long = System::currentTimeMillis,
    /** Deduplication window — same (toolName + summary) within this window is skipped. */
    private val dedupeWindowMs: Long = DEDUPE_WINDOW_MS,
) {
    // ── constants ──────────────────────────────────────────────────────────
    companion object {
        /** 10 minutes in milliseconds — the default dedupe window. */
        const val DEDUPE_WINDOW_MS = 10 * 60 * 1000L

        /** Maximum length of the summary extracted from the tool output. */
        const val SUMMARY_MAX_LENGTH = 120

        /** Maximum length of the full error body written into the Error block. */
        const val ERROR_BODY_MAX_LENGTH = 2000

        /** Maximum length of the args context written into the Context block. */
        const val ARGS_CONTEXT_MAX_LENGTH = 300
    }

    // ── state ──────────────────────────────────────────────────────────────
    // ConcurrentHashMap for thread-safe, atomic keyed ops. The dedupe
    // decision (check + reserve) runs inside compute(), which is atomic
    // per-key — so concurrent recordFailure() calls for the same key cannot
    // both observe "no recent entry" and double-write.
    private val lastWriteByKey = ConcurrentHashMap<String, Long>()

    // ── public API ─────────────────────────────────────────────────────────
    /**
     * Record a tool failure. Writes a formatted block via [writeErrorBlock]
     * unless the same (toolName + summary) was written within the last
     * [dedupeWindowMs] milliseconds.
     *
     * @return true when a block was actually written, false when deduplicated.
     */
    fun recordFailure(
        toolName: String,
        output: String,
        argsJson: String? = null,
        sessionId: String? = null,
    ): Boolean {
        val summary = summarize(output)
        val key = "$toolName\u0000$summary"
        val now = clock()

        // Atomic check-and-reserve: compute() runs under the map's per-key
        // lock, so concurrent callers cannot both read "none/expired" and
        // double-write within the dedupe window. Only the thread that wins
        // the reservation actually writes; the rest are deduplicated.
        var shouldWrite = false
        lastWriteByKey.compute(key) { _, existing ->
            if (existing == null || now - existing >= dedupeWindowMs) {
                shouldWrite = true   // we claim the slot with our timestamp
                now
            } else {
                existing             // within window → keep timestamp, skip
            }
        }
        if (!shouldWrite) return false

        val block = buildBlock(toolName, summary, output, argsJson, sessionId, now)
        writeErrorBlock(block)
        return true
    }

    // ── internal ───────────────────────────────────────────────────────────
    /** Extract a one-line summary from the tool output. */
    internal fun summarize(output: String): String {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return "(empty)"
        // Take the first non-empty line, or the full string if no line-break.
        val firstLine = trimmed.lines().firstOrNull()?.trim() ?: trimmed
        // [fix/audit-s6l2] Normalize volatile tokens (timestamps, request ids,
        // hex/digit runs) out of the dedupe key — otherwise the same root error
        // with a fresh timestamp/requestId in the first line produces a DIFFERENT
        // summary and defeats the 10-min dedupe window, re-writing the block.
        return firstLine
            .replace(Regex("""\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:?\d{2})?"""), "<ts>")
            .replace(Regex("""\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""", RegexOption.IGNORE_CASE), "<uuid>")
            .replace(Regex("""\b\d{10,}\b"""), "<n>")
            .take(SUMMARY_MAX_LENGTH)
    }

    /** Build a complete formatted error block. */
    internal fun buildBlock(
        toolName: String,
        summary: String,
        output: String,
        argsJson: String?,
        sessionId: String?,
        nowMs: Long,
    ): String {
        val id = generateId(nowMs)
        val ts = formatIso(nowMs)
        val errorBody = output.take(ERROR_BODY_MAX_LENGTH)
        val argsContext = argsJson?.take(ARGS_CONTEXT_MAX_LENGTH)
        val sessionLine = if (sessionId != null) "会话: $sessionId" else "（无会话信息）"

        return buildString {
            appendLine("## [$id] $toolName")
            appendLine()
            appendLine("**记录时间**: $ts")
            appendLine("**优先级**: high")
            appendLine("**状态**: pending")
            appendLine("**领域**: infra")
            appendLine()
            appendLine("### 摘要")
            appendLine(summary)
            appendLine()
            appendLine("### Error")
            appendLine("```")
            appendLine(errorBody)
            appendLine("```")
            appendLine()
            appendLine("### Context")
            appendLine("- 尝试的命令/操作：$toolName")
            appendLine("- 输入或参数：${argsContext ?: "（无参数）"}")
            appendLine("- 环境细节：$sessionLine")
            appendLine()
            appendLine("### 建议修复")
            appendLine("（待补充）")
            appendLine()
            appendLine("### 元数据")
            appendLine("- 可复现: unknown")
            appendLine("- 作用域: session")
            appendLine("- 工具: $toolName")
            appendLine("- 关联文件: (可选)")
            appendLine()
            appendLine("---")
        }
    }

    /** Generate an ID like ERR-20260812-ABC. */
    internal fun generateId(nowMs: Long): String {
        val datePart = formatDate(nowMs)
        val randomPart = generateRandom()
        return "ERR-${datePart}-${randomPart}"
    }

    // ── helpers ────────────────────────────────────────────────────────────
    private fun formatIso(ms: Long): String {
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        df.timeZone = TimeZone.getTimeZone("UTC")
        return df.format(Date(ms))
    }

    private fun formatDate(ms: Long): String {
        val df = SimpleDateFormat("yyyyMMdd", Locale.US)
        df.timeZone = TimeZone.getTimeZone("UTC")
        return df.format(Date(ms))
    }

    private fun generateRandom(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..3).map { chars[kotlin.random.Random.nextInt(chars.length)] }.joinToString("")
    }
}