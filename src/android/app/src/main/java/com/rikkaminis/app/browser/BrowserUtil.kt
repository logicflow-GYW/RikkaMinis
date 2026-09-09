package com.rikkaminis.app.browser

/**
 * Pure utility functions extracted from [BrowserUseManager] so they can be
 * JVM-unit-tested without Android dependencies.
 *
 * Each function's original location in [BrowserUseManager] is noted.
 */

/**
 * (was BrowserUseManager.guessMimeType)
 * Guess MIME type from a filename extension. Falls back to
 * `application/octet-stream` for unknown extensions.
 */
internal fun guessMimeType(filename: String): String {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "pdf" -> "application/pdf"
        "txt", "md" -> "text/plain"
        "xml" -> "text/xml"
        else -> "application/octet-stream"
    }
}

/**
 * (was BrowserUseManager.extensionForMimeType)
 * Guess a file extension from a MIME type. Returns `"bin"` for unknown types.
 */
internal fun extensionForMimeType(mime: String): String {
    val lower = mime.lowercase().split(";").firstOrNull()?.trim() ?: ""
    return when (lower) {
        "text/html" -> "html"
        "text/plain" -> "txt"
        "text/css" -> "css"
        "text/csv" -> "csv"
        "application/json" -> "json"
        "application/xml" -> "xml"
        "text/xml" -> "xml"
        "application/pdf" -> "pdf"
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/svg+xml" -> "svg"
        "application/zip" -> "zip"
        "application/gzip" -> "gz"
        else -> "bin"
    }
}

/**
 * (was BrowserUseManager.formatBytes)
 * Format a byte count into a human-readable string (B, KB, MB).
 */
internal fun formatBytes(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

/**
 * (was BrowserUseManager.cookieValue)
 * Look up a value from a cookie map field by trying multiple alias names.
 * Falls back to case-insensitive matching.
 */
internal fun cookieValue(raw: Map<String, Any?>, vararg aliases: String): Any? {
    for (key in aliases) raw[key]?.let { return it }
    val lowered = aliases.map { it.lowercase() }.toSet()
    for ((k, v) in raw) if (k.lowercase() in lowered && v != null) return v
    return null
}

/**
 * (was BrowserUseManager.cookieString)
 * Read a cookie value as String. Numbers are stringified.
 */
internal fun cookieString(raw: Map<String, Any?>, vararg aliases: String): String? =
    when (val v = cookieValue(raw, *aliases)) {
        is String -> v
        is Number -> v.toString()
        else -> null
    }

/**
 * (was BrowserUseManager.cookieBool)
 * Read a cookie value as Boolean. Tolerates JSON bool, 0/1, and
 * stringified "true"/"false"/"1"/"yes".
 */
internal fun cookieBool(raw: Map<String, Any?>, vararg aliases: String): Boolean? =
    when (val v = cookieValue(raw, *aliases)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v.lowercase() in setOf("true", "1", "yes")
        else -> null
    }

/**
 * (was BrowserUseManager.cookieNumber)
 * Read a cookie value as Double (seconds). Accepts JSON number or numeric string.
 */
internal fun cookieNumber(raw: Map<String, Any?>, vararg aliases: String): Double? =
    when (val v = cookieValue(raw, *aliases)) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }