package com.rikkaminis.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * [feat/chat-tuning-panel] User-tunable chat reading & composer knobs — the
 * single source of truth behind Settings → Appearance → Chat Tuning.
 *
 * Everything here was previously a hard-coded literal inside ChatScreen /
 * StreamingMarkdownText / ChatInputArea. The shipped literals become the
 * DEFAULTs below, so an untouched install behaves byte-identically to the
 * pre-panel build — the panel only lets the user deviate deliberately.
 *
 * Storage: its own `minis_chat_tuning_prefs` file (mirroring the
 * minis_concurrency_prefs / minis_runtime_limits_prefs pattern) so the
 * config layer can reference these keys without importing UI classes.
 * Unlike AgentRuntimeLimitsPrefs there is deliberately NO primed cache:
 * every reader has a Context — ChatScreen recomposes off an
 * OnSharedPreferenceChangeListener on this file and hands the values down
 * via parameters / composition locals — so writes are visible immediately
 * with no cache-invalidation step.
 *
 * Every key is also registered in ConfigBuiltins (`chat.*` paths) so
 * minis-config can read/write them and the in-app backup carries them.
 */
object ChatTuningPrefs {

    /** Dedicated prefs file — see the Storage note in the class KDoc. */
    const val PREFS = "minis_chat_tuning_prefs"

    // ── 1. Scroll: bottom-stick threshold ────────────────────────────────
    /** Px source: "how close to the bottom still counts as at-bottom". */
    const val KEY_SCROLL_NEAR_BOTTOM_DP = "chat_scroll_near_bottom_dp"
    /** ChatScreen T128 value — tightened from 90dp; ≈ half the floating tool bar. */
    const val SCROLL_NEAR_BOTTOM_DEFAULT = 32
    const val SCROLL_NEAR_BOTTOM_MIN = 8
    const val SCROLL_NEAR_BOTTOM_MAX = 128

    // ── 2. Cold-open viewport prewarm ────────────────────────────────────
    const val KEY_PREWARM_ROW_LIMIT = "chat_prewarm_row_limit"
    const val PREWARM_ROW_LIMIT_DEFAULT = 16
    const val PREWARM_ROW_LIMIT_MIN = 4
    const val PREWARM_ROW_LIMIT_MAX = 64

    // ── 3. Markdown fold thresholds ──────────────────────────────────────
    const val KEY_CODE_PREVIEW_LINES = "chat_code_preview_lines"
    const val CODE_PREVIEW_LINES_DEFAULT = 20
    const val CODE_PREVIEW_LINES_MIN = 5
    const val CODE_PREVIEW_LINES_MAX = 100

    const val KEY_TABLE_PREVIEW_ROWS = "chat_table_preview_rows"
    const val TABLE_PREVIEW_ROWS_DEFAULT = 10
    const val TABLE_PREVIEW_ROWS_MIN = 3
    const val TABLE_PREVIEW_ROWS_MAX = 50

    // ── 4. Markdown line height (body text) ──────────────────────────────
    const val KEY_MARKDOWN_LINE_HEIGHT_SP = "chat_markdown_line_height_sp"
    const val MARKDOWN_LINE_HEIGHT_DEFAULT = 24
    const val MARKDOWN_LINE_HEIGHT_MIN = 16
    const val MARKDOWN_LINE_HEIGHT_MAX = 40

    // ── 5. Composer ──────────────────────────────────────────────────────
    const val KEY_INPUT_MAX_LINES = "chat_input_max_lines"
    const val INPUT_MAX_LINES_DEFAULT = 6
    const val INPUT_MAX_LINES_MIN = 1
    const val INPUT_MAX_LINES_MAX = 20

    const val KEY_SEND_SWIPE_THRESHOLD_DP = "chat_send_swipe_threshold_dp"
    const val SEND_SWIPE_THRESHOLD_DEFAULT = 120
    const val SEND_SWIPE_THRESHOLD_MIN = 60
    const val SEND_SWIPE_THRESHOLD_MAX = 240

    /** Every key this object owns — the change-listener filter. */
    val ALL_KEYS: Set<String> = setOf(
        KEY_SCROLL_NEAR_BOTTOM_DP,
        KEY_PREWARM_ROW_LIMIT,
        KEY_CODE_PREVIEW_LINES,
        KEY_TABLE_PREVIEW_ROWS,
        KEY_MARKDOWN_LINE_HEIGHT_SP,
        KEY_INPUT_MAX_LINES,
        KEY_SEND_SWIPE_THRESHOLD_DP,
    )

    // ── readers (each clamps to its bounds, so a corrupt value can't
    //    escape into layout math) ────────────────────────────────────────

    fun scrollNearBottomDp(context: Context): Int = readClamped(
        context, KEY_SCROLL_NEAR_BOTTOM_DP,
        SCROLL_NEAR_BOTTOM_DEFAULT, SCROLL_NEAR_BOTTOM_MIN, SCROLL_NEAR_BOTTOM_MAX,
    )

    fun prewarmRowLimit(context: Context): Int = readClamped(
        context, KEY_PREWARM_ROW_LIMIT,
        PREWARM_ROW_LIMIT_DEFAULT, PREWARM_ROW_LIMIT_MIN, PREWARM_ROW_LIMIT_MAX,
    )

    fun codePreviewLines(context: Context): Int = readClamped(
        context, KEY_CODE_PREVIEW_LINES,
        CODE_PREVIEW_LINES_DEFAULT, CODE_PREVIEW_LINES_MIN, CODE_PREVIEW_LINES_MAX,
    )

    fun tablePreviewRows(context: Context): Int = readClamped(
        context, KEY_TABLE_PREVIEW_ROWS,
        TABLE_PREVIEW_ROWS_DEFAULT, TABLE_PREVIEW_ROWS_MIN, TABLE_PREVIEW_ROWS_MAX,
    )

    fun markdownLineHeightSp(context: Context): Int = readClamped(
        context, KEY_MARKDOWN_LINE_HEIGHT_SP,
        MARKDOWN_LINE_HEIGHT_DEFAULT, MARKDOWN_LINE_HEIGHT_MIN, MARKDOWN_LINE_HEIGHT_MAX,
    )

    fun inputMaxLines(context: Context): Int = readClamped(
        context, KEY_INPUT_MAX_LINES,
        INPUT_MAX_LINES_DEFAULT, INPUT_MAX_LINES_MIN, INPUT_MAX_LINES_MAX,
    )

    fun sendSwipeThresholdDp(context: Context): Int = readClamped(
        context, KEY_SEND_SWIPE_THRESHOLD_DP,
        SEND_SWIPE_THRESHOLD_DEFAULT, SEND_SWIPE_THRESHOLD_MIN, SEND_SWIPE_THRESHOLD_MAX,
    )

    /** Snapshot of every knob — what the panel edits and the composer reads. */
    fun readAll(context: Context): ChatTuningValues = ChatTuningValues(
        scrollNearBottomDp = scrollNearBottomDp(context),
        prewarmRowLimit = prewarmRowLimit(context),
        codePreviewLines = codePreviewLines(context),
        tablePreviewRows = tablePreviewRows(context),
        markdownLineHeightSp = markdownLineHeightSp(context),
        inputMaxLines = inputMaxLines(context),
        sendSwipeThresholdDp = sendSwipeThresholdDp(context),
    )

    /** One-shot persist used by the panel's Save action (clamps like the readers). */
    fun save(context: Context, values: ChatTuningValues) {
        prefs(context).edit()
            .putInt(
                KEY_SCROLL_NEAR_BOTTOM_DP,
                values.scrollNearBottomDp.coerceIn(SCROLL_NEAR_BOTTOM_MIN, SCROLL_NEAR_BOTTOM_MAX),
            )
            .putInt(
                KEY_PREWARM_ROW_LIMIT,
                values.prewarmRowLimit.coerceIn(PREWARM_ROW_LIMIT_MIN, PREWARM_ROW_LIMIT_MAX),
            )
            .putInt(
                KEY_CODE_PREVIEW_LINES,
                values.codePreviewLines.coerceIn(CODE_PREVIEW_LINES_MIN, CODE_PREVIEW_LINES_MAX),
            )
            .putInt(
                KEY_TABLE_PREVIEW_ROWS,
                values.tablePreviewRows.coerceIn(TABLE_PREVIEW_ROWS_MIN, TABLE_PREVIEW_ROWS_MAX),
            )
            .putInt(
                KEY_MARKDOWN_LINE_HEIGHT_SP,
                values.markdownLineHeightSp.coerceIn(MARKDOWN_LINE_HEIGHT_MIN, MARKDOWN_LINE_HEIGHT_MAX),
            )
            .putInt(
                KEY_INPUT_MAX_LINES,
                values.inputMaxLines.coerceIn(INPUT_MAX_LINES_MIN, INPUT_MAX_LINES_MAX),
            )
            .putInt(
                KEY_SEND_SWIPE_THRESHOLD_DP,
                values.sendSwipeThresholdDp.coerceIn(SEND_SWIPE_THRESHOLD_MIN, SEND_SWIPE_THRESHOLD_MAX),
            )
            .apply()
    }

    private fun readClamped(context: Context, key: String, default: Int, min: Int, max: Int): Int =
        prefs(context).getInt(key, default).coerceIn(min, max)

    /**
     * The backing store. Exposed (not private) so UI code can register its
     * OnSharedPreferenceChangeListener against the very same file instance.
     */
    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Immutable snapshot of the chat-tuning knobs. Defaults equal the constants
 * above, which equal the previously hard-coded literals — so a default
 * instance is behaviourally identical to the pre-panel build.
 */
data class ChatTuningValues(
    val scrollNearBottomDp: Int = ChatTuningPrefs.SCROLL_NEAR_BOTTOM_DEFAULT,
    val prewarmRowLimit: Int = ChatTuningPrefs.PREWARM_ROW_LIMIT_DEFAULT,
    val codePreviewLines: Int = ChatTuningPrefs.CODE_PREVIEW_LINES_DEFAULT,
    val tablePreviewRows: Int = ChatTuningPrefs.TABLE_PREVIEW_ROWS_DEFAULT,
    val markdownLineHeightSp: Int = ChatTuningPrefs.MARKDOWN_LINE_HEIGHT_DEFAULT,
    val inputMaxLines: Int = ChatTuningPrefs.INPUT_MAX_LINES_DEFAULT,
    val sendSwipeThresholdDp: Int = ChatTuningPrefs.SEND_SWIPE_THRESHOLD_DEFAULT,
)
