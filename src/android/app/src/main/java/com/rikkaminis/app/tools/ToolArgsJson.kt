package com.rikkaminis.app.tools

import org.json.JSONObject

/**
 * [WAVE-1118 D8-F03] The one place that reads a model-supplied argument without
 * turning a JSON `null` into a *value*.
 *
 * `{"overwrite_reason": null}` is legal tool-call JSON: schema-constrained
 * models emit an explicit null for an unused optional parameter all the time.
 * What reads it back differs by platform — JSON-java's
 * `optString(name, fallback)` answers the fallback for `JSONObject.NULL`,
 * Android's answers the literal `"null"` (its `JSON.toString(NULL)` is
 * `"null"`). The tool layer runs on Android, so on the device every `isBlank()`
 * / `isEmpty()` guard downstream of a bare `optString` was dead code:
 *
 *  - `file_write` reached [AppendOnlyWriteGuard] with `overwrite_reason = "null"`
 *    → not blank → the `OVERWRITE_NOT_JUSTIFIED` refusal never fired, i.e. the
 *    "say why before replacing a ledger" friction could be skipped by a model
 *    that emitted null instead of omitting the key;
 *  - `file_read` read `{"lines": null}` as `lines = 0` and answered "0 lines".
 *
 * [INCR-1117 A-F01] established the fix shape *and* the reason it belongs on
 * the reader side: **`isNull` means "null or absent" in BOTH implementations**,
 * so this does not touch the wire shape. That ticket converted its own reader
 * ([SubagentSkill.replyText]); the tool-argument readers here were never
 * converted, which is why the same defect kept turning up one entry point over.
 *
 * Do NOT "fix" this by stripping nulls out of the JSON before dispatch: the
 * same `argsJson` is echoed into traces and replayed, and rewriting it would
 * mutate a wire artifact three layers above the bug.
 */

/**
 * [WAVE-1118 D8-F03] `optString` that answers [fallback] for an explicit JSON
 * null. An absent key already answered [fallback] — only the null case changes.
 */
internal fun JSONObject.optArgString(key: String, fallback: String = ""): String =
    if (isNull(key)) fallback else optString(key, fallback)

/**
 * [WAVE-1118 D8-F03] `optInt` that answers null for an absent **or null**
 * value.
 *
 * `has(key)` alone is not enough: `{"lines": null}` has the key, and `optInt`
 * answers 0 — "return zero lines" for a caller that asked for no paging at all.
 */
internal fun JSONObject.optArgIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)
