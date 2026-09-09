package com.rikkaminis.app.ui.chat

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure formatting helpers extracted from [ChatToolFormatting] so they can be
 * JVM-tested without loading Compose classes.
 *
 * The original functions in [ChatToolFormatting] are removed; callers resolve
 * these from the same package.
 */

internal val stepTimestampFormatter: SimpleDateFormat =
    SimpleDateFormat("HH:mm:ss", Locale.US)

internal fun formatStepTimestamp(epochMs: Long): String =
    stepTimestampFormatter.format(Date(epochMs))

internal fun formatStepDuration(seconds: Long, stillRunning: Boolean): String {
    val safe = seconds.coerceAtLeast(0L)
    val base = when {
        safe < 60L -> "${safe}s"
        safe < 3600L -> {
            val m = safe / 60L
            val s = safe % 60L
            if (s == 0L) "${m}m" else "${m}m${s}s"
        }
        else -> {
            val h = safe / 3600L
            val m = (safe % 3600L) / 60L
            if (m == 0L) "${h}h" else "${h}h${m}m"
        }
    }
    return if (stillRunning) "$base…" else base
}

internal fun toolDisplayName(toolName: String): String = when (toolName) {
    "shell_execute" -> "terminal"
    "file_read" -> "file reader"
    "file_write" -> "file writer"
    "file_edit" -> "file editor"
    "browser_use" -> "browser"
    "read_image" -> "image viewer"
    "memory_write" -> "memory"
    "memory_get" -> "memory"
    "web_search" -> "search"
    else -> toolName
}

internal fun toolTitleLabel(toolName: String): String = when (toolName) {
    "shell_execute" -> "RikkaMinis is using Shell"
    "file_read" -> "RikkaMinis is reading File"
    "file_write" -> "RikkaMinis is using Editor"
    "file_edit" -> "RikkaMinis is editing File"
    "browser_use" -> "RikkaMinis is using Browser"
    "read_image" -> "RikkaMinis is reading Image"
    "memory_write", "memory_get" -> "RikkaMinis is using Memory"
    "web_search" -> "RikkaMinis is using Search"
    else -> "RikkaMinis is using ${toolDisplayName(toolName)}"
}

internal fun formatToolDuration(ms: Long): String {
    val seconds = ms / 1000.0
    return when {
        seconds < 1 -> String.format("%.1fs", seconds)
        seconds < 60 -> String.format("%.0fs", seconds)
        else -> {
            val m = (seconds / 60).toInt()
            val s = (seconds % 60).toInt()
            "${m}m ${s}s"
        }
    }
}