package com.rikkaminis.app.ui.chat

import org.json.JSONObject
import java.net.URLConnection

/**
 * Stable same-turn identity for tool calls. UI-only fields are excluded using
 * the same source of truth as ToolLoopDetector's cross-turn hash.
 */
internal fun toolCallDedupeFingerprint(name: String, args: JSONObject): String {
    val filtered = linkedMapOf<String, Any?>()
    val keys = args.keys().asSequence()
        .filter { it !in com.rikkaminis.app.agent.ToolLoopDetector.ARGS_HASH_IGNORED_KEYS }
        .sorted()
        .toList()
    for (key in keys) {
        filtered[key] = stableToolCallValue(args.get(key))
    }
    return "$name|${stableToolCallJson(filtered)}"
}

private fun stableToolCallValue(value: Any?): Any? = when (value) {
    JSONObject.NULL -> null
    is JSONObject -> {
        val nested = linkedMapOf<String, Any?>()
        value.keys().asSequence().sorted().forEach { key ->
            nested[key] = stableToolCallValue(value.get(key))
        }
        nested
    }
    is org.json.JSONArray -> (0 until value.length()).map { stableToolCallValue(value.get(it)) }
    else -> value
}

private fun stableToolCallJson(value: Any?): String = buildString {
    appendStableToolCallJson(value)
}

private fun StringBuilder.appendStableToolCallJson(value: Any?) {
    when (value) {
        null -> append("null")
        is Map<*, *> -> {
            append('{')
            value.entries.forEachIndexed { index, entry ->
                if (index > 0) append(',')
                append(JSONObject.quote(entry.key.toString()))
                append(':')
                appendStableToolCallJson(entry.value)
            }
            append('}')
        }
        is List<*> -> {
            append('[')
            value.forEachIndexed { index, item ->
                if (index > 0) append(',')
                appendStableToolCallJson(item)
            }
            append(']')
        }
        is String -> append(JSONObject.quote(value))
        is Number, is Boolean -> append(value.toString())
        else -> append(JSONObject.quote(value.toString()))
    }
}

/**
 * Pure utility functions extracted from [ChatViewModel] so they can be
 * JVM-unit-tested without Android dependencies.
 *
 * Each function's original location in [ChatViewModel] is noted. The original
 * private method now delegates to this file.
 */

// ── Throttle helpers ───────────────────────────────────────────────────────

/**
 * (was ChatViewModel.runAgentLoop → textDeltaThrottleMs)
 * Adaptive throttle delay (ms) for text delta streaming, keyed on the
 * length of the pending delta text. Longer deltas = more aggressive batching.
 */
internal fun textDeltaThrottleMs(len: Int): Long = when {
    len < 500 -> 150L
    len < 2_000 -> 300L
    len < 32_000 -> 500L
    len < 64_000 -> 1_000L
    len < 128_000 -> 1_500L
    else -> 2_000L
}

/**
 * (was ChatViewModel.streamFlushThrottleMs)
 * Adaptive throttle delay (ms) for stream-flush batching, keyed on the
 * length of the pending delta text. Longer deltas = more aggressive batching.
 */
internal fun streamFlushThrottleMs(len: Int): Long = when {
    len < 500 -> 200L
    len < 2_000 -> 300L
    len < 32_000 -> 500L
    len < 64_000 -> 1_000L
    len < 128_000 -> 1_500L
    else -> 2_000L
}

// ── Tool display helpers ──────────────────────────────────────────────────

/**
 * (was ChatViewModel.friendlyToolTitle)
 * Humanize a snake_case tool name into a Title-Case label for pill headers.
 */
internal fun friendlyToolTitle(toolName: String): String = when (toolName) {
    "shell_execute" -> "Execute Shell"
    "file_read" -> "Read File"
    "file_write" -> "Write File"
    "file_edit" -> "Edit File"
    "browser_use" -> "Browse Web"
    "read_image" -> "Read Image"
    "memory_write" -> "Write Memory"
    "memory_get" -> "Read Memory"
    "web_search" -> "Search Web"
    else -> toolName
        .split('_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
}

/**
 * (was ChatViewModel.parseToolParams)
 * Parse the JSON tool-arguments string into a plain Map. Malformed JSON
 * degrades gracefully to an empty map.
 */
internal fun parseToolParams(argsJson: String): Map<String, Any?> {
    if (argsJson.isBlank()) return emptyMap()
    return try {
        val obj = JSONObject(argsJson)
        val out = HashMap<String, Any?>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.get(k)
            out[k] = if (v == JSONObject.NULL) null else v
        }
        out
    } catch (_: Exception) {
        emptyMap()
    }
}

// ── JSON string helpers ────────────────────────────────────────────────────

/**
 * (was ChatViewModel.escapeJson)
 * Escape a plain text string into a JSON string literal (including surrounding
 * double-quotes).
 */
internal fun escapeJson(text: String): String {
    val sb = StringBuilder("\"")
    for (c in text) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> {
                if (c.code < 0x20) sb.append("\\u%04x".format(c.code))
                else sb.append(c)
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

/**
 * (was ChatViewModel.extractPartialStringValue)
 * Extract the value of a top-level JSON string key without a full JSON parser.
 * Handles partial/streaming JSON where the string may be truncated after the
 * opening quote. Returns null when the key is not found.
 */
internal fun extractPartialStringValue(key: String, json: String): String? {
    val patterns = listOf("\"$key\": \"", "\"$key\":\"")
    for (p in patterns) {
        val at = json.indexOf(p)
        if (at < 0) continue
        val after = json.substring(at + p.length)
        return unescapePartialJsonString(findUnescapedEnd(after))
    }
    return null
}

/**
 * (was ChatViewModel.findUnescapedEnd)
 * Return substring up to the first unescaped `"`, or the whole string if none.
 */
internal fun findUnescapedEnd(s: String): String {
    var i = 0
    val n = s.length
    while (i < n) {
        val c = s[i]
        if (c == '\\') {
            // Skip escaped character (could be `\"`, `\\`, `\n`, etc.)
            i += 2
            continue
        }
        if (c == '"') return s.substring(0, i)
        i++
    }
    return s
}

/**
 * (was ChatViewModel.unescapePartialJsonString)
 * Unescape common JSON string escapes.
 */
internal fun unescapePartialJsonString(s: String): String =
    s.replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\/", "/")
        .replace("\\\\", "\\")

// ── Message text sanitizers ────────────────────────────────────────────────

/** Regex matching `<system-reminder>…</system-reminder>` blocks (DOTALL). */
internal val systemReminderRegex: Regex =
    Regex("\\s*<system-reminder>.*?</system-reminder>\\s*", RegexOption.DOT_MATCHES_ALL)

/**
 * (was ChatViewModel.stripSystemReminders)
 * Remove `<system-reminder>…</system-reminder>` blocks from display text.
 * No-op when the text contains no `<system-reminder>` tag.
 */
internal fun stripSystemReminders(text: String): String =
    if (!text.contains("<system-reminder>")) text
    else systemReminderRegex.replace(text, "")

/**
 * (was ChatViewModel.stripAttachedFilesXml)
 * Remove `<user-attached-files>…</user-attached-files>` XML inventory from
 * display text. No-op when the tag is absent.
 */
internal fun stripAttachedFilesXml(text: String): String {
    val startIdx = text.indexOf("<user-attached-files>")
    if (startIdx < 0) return text
    val endTag = "</user-attached-files>"
    val endIdx = text.indexOf(endTag, startIdx)
    return if (endIdx >= 0) {
        text.substring(0, startIdx) + text.substring(endIdx + endTag.length)
    } else {
        text.substring(0, startIdx)
    }
}