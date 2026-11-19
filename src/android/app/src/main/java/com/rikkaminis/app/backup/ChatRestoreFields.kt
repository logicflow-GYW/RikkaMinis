package com.rikkaminis.app.backup

import org.json.JSONObject

/**
 * [FULLSCAN-1119 A / F-20] Tri-state reads for the nullable columns Stage 8
 * restores, Android-free so the exact null semantics are pinned on a plain JVM.
 *
 * The defect was one shape, three times:
 *
 * ```
 * pinnedAt = if (s.has("pinnedAt")) s.optLong("pinnedAt") else null
 * ```
 *
 * `has()` answers "was the key written", not "does it carry a value" — it is
 * TRUE for an explicit JSON null, and `optLong` / `optString` then fold that to
 * `0` / `""`. A backup written by anything that emits `"pinnedAt": null` (this
 * app's own exporter writes `JSONObject.NULL` for an unpinned session, and the
 * `.minisbak` converter passes such documents through) therefore restored a
 * session pinned at the epoch, with an empty `thinkingOverride`, and a message
 * with an empty-but-present `reasoningContent` instead of none.
 *
 * The rule here: absent and explicit null BOTH mean "not set"; only a present,
 * non-null value is a value.
 */
internal object ChatRestoreFields {

    /** The two nullable session scalars Stage 8 writes. */
    internal data class SessionScalars(
        val pinnedAt: Long?,
        val thinkingOverride: String?,
    )

    internal fun readSessionScalars(s: JSONObject): SessionScalars =
        SessionScalars(
            pinnedAt = s.optLongOrNullIfPresent("pinnedAt"),
            thinkingOverride = s.optStringOrNullIfPresent("thinkingOverride"),
        )

    /** The raw (uncapped) reasoning blob of one message row; the caller applies
     *  [com.rikkaminis.app.data.repository.ChatRepository.capReasoningContentForRow]
     *  so the row keeps taking the same ceiling the runtime writers apply. */
    internal fun readReasoningContent(m: JSONObject): String? =
        m.optStringOrNullIfPresent("reasoningContent")
}

/**
 * [FULLSCAN-1119 A / F-20] Tri-state read of a nullable numeric column.
 *
 * Kept as an extension so the call sites read like the old `optLong` and the
 * difference is the guard, not the shape.
 */
internal fun JSONObject.optLongOrNullIfPresent(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

/** [FULLSCAN-1119 A / F-20] String twin of [optLongOrNullIfPresent]. */
internal fun JSONObject.optStringOrNullIfPresent(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null
