package com.rikkaminis.app.tools

import org.json.JSONObject

/**
 * Resolves the user-facing `tool_title` from the raw tool-call arguments.
 *
 * [fix/da1022-G] Callers must invoke this BEFORE entering their `try` block:
 * the title used to be a try-scoped val, so the `catch (e: Exception)` arm
 * had no title to report and degraded the tool capsule to the bare tool name
 * (or an empty title) on every error path. Mirrors the inline pattern in
 * [FileWriteTool] (`[fix/audit-0917-b9]`).
 *
 * Pure and total: malformed JSON or any parse failure falls back to
 * [fallback] instead of throwing.
 */
internal fun resolveToolTitle(argsJson: String, fallback: String): String =
    // [WAVE-1118 D8-F03] optArgString, not optString: on Android an explicit
    // `{"tool_title": null}` was read back as the literal "null" and the tool
    // capsule showed `null` as its title.
    runCatching { JSONObject(argsJson).optArgString("tool_title", fallback) }
        .getOrDefault(fallback)
