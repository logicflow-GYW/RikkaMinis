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
 * (was BrowserUseManager.navigate / .loadURL inline)
 * Prefix a bare host with `https://` while leaving URLs that already carry a
 * scheme alone.
 *
 * [audit-0916] The inline spelling was `if (!contains("://")) "https://$it"`,
 * which mangles every scheme-less URL: `about:blank` became
 * `https://about:blank` and was then rejected by Chromium ("Refusing to load
 * for invalid virtual URL: https://about:blank/") after burning the full
 * navigation timeout — while `navigate` still reported success. Same for
 * `data:` / `file:` / `javascript:` inputs.
 *
 * A scheme is `<alpha><alnum+.-+>*:` per RFC 3986 — matching it (rather than
 * looking for `://`) is what keeps opaque schemes like `about:blank` and
 * `data:text/html,…` intact. The `host:port` form has the same shape, though,
 * so the scheme must be followed by something that is not a digit: `8080` is a
 * port, while `blank` in `about:blank` is a scheme body.
 */
internal fun normalizeBrowserUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return trimmed
    val scheme = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:($|[^0-9])")
    if (scheme.containsMatchIn(trimmed)) return trimmed
    return "https://$trimmed"
}

/**
 * [audit-0916b] How an awaited navigation ended.
 *
 * (was implicit in `BrowserUseManager.navigationDeferred`, which completed with
 * a bare `Unit` on *any* of onPageFinished / onReceivedError / timeout, leaving
 * the caller no way to tell those apart.)
 */
internal enum class NavigationOutcome { LOADED, FAILED, TIMED_OUT, ROUTED_EXTERNALLY }

/**
 * [audit-0916b] What a completed navigation wait carries back to its awaiter.
 *
 * @param errorDescription WebView's description for a main-frame failure.
 * @param routedTarget the URL handed to another app instead of being loaded.
 */
internal data class NavigationResult(
    val outcome: NavigationOutcome,
    val errorDescription: String? = null,
    val routedTarget: String? = null,
)

/** [audit-0916b] A navigation's tool-result text and the success flag it must carry. */
internal data class NavigationReport(val text: String, val success: Boolean)

/**
 * [audit-0916b] Build the tool result for an awaited navigation.
 *
 * The defect this exists to prevent (§20b): `navigationDeferred` completed with
 * `Unit` for onPageFinished, onReceivedError and timeout alike, so `navigate()`
 * unconditionally answered `success = true` / `"Navigated to <url>"`. A load
 * Chromium refuses outright — `Refusing to load for invalid virtual URL:
 * https://about:blank/`, 2026-09-16 10:40:55 — fires *neither* callback, so it
 * surfaced as a success after burning the entire navigation timeout
 * (10:41:26, `success=true output=Navigated to about:blank`): the agent believed
 * it was on a page that had never loaded. Same lie for a main-frame
 * `onReceivedError`, which is immediately followed by `onPageFinished` for the
 * WebView's own error page.
 *
 * [details] carries the title / viewport / scroll lines and is appended in every
 * branch, so the agent still sees what the WebView is actually showing.
 * [LOADED] keeps the historical text byte-for-byte.
 */
internal fun navigationReport(
    outcome: NavigationOutcome,
    url: String,
    details: String,
    errorDescription: String? = null,
    routedTarget: String? = null,
    timeoutSec: Int = 0,
): NavigationReport = when (outcome) {
    NavigationOutcome.LOADED ->
        NavigationReport("Navigated to $url\n$details", true)

    // [audit-0916b] The scheme was handed to another app (Chrome Custom Tab for
    // Google auth, the system handler for tel:/mailto:/intent:/…). The in-app
    // page deliberately does not change, and no page-load event will ever
    // arrive — the old code waited out the full timeout and then claimed to
    // have navigated. The routing itself did succeed, so this stays success.
    NavigationOutcome.ROUTED_EXTERNALLY ->
        NavigationReport(
            "Handed to another app by the system: ${routedTarget ?: url}" +
                " — the in-app page did not change.\n$details",
            true,
        )

    NavigationOutcome.FAILED ->
        NavigationReport(
            "Error: navigation to $url failed" +
                (errorDescription?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "") +
                ". The page did not load (the WebView is showing its own error page).\n$details",
            false,
        )

    NavigationOutcome.TIMED_OUT ->
        NavigationReport(
            "Error: navigation to $url did not complete" +
                (if (timeoutSec > 0) " within ${timeoutSec}s" else "") +
                " — the page never reported that it finished loading. It may be " +
                "blank, refused, unreachable, or still loading.\n$details",
            false,
        )
}

/**
 * [audit-0916b] Which URL a navigation result should name.
 *
 * A successful load is reported by where the page *ended up* (redirects
 * included) — the historical behavior. A failed or timed-out load must name the
 * URL that was *requested*: the live "current URL" still points at the previous
 * page, so echoing it would blame a URL that was never even attempted.
 */
internal fun navigationReportedUrl(
    outcome: NavigationOutcome,
    currentUrl: String,
    requestedUrl: String,
): String = if (outcome == NavigationOutcome.LOADED) currentUrl else requestedUrl

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