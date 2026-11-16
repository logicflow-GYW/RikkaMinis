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

    // ── 6. Composer background effect ([composer-glass-1115]) ────────────
    //
    // The rikkahub input-bar effect: the card stops painting itself and draws a
    // blurred sample of the message rows scrolling behind it instead.
    //
    // Three knobs, not one, because the ONE thing the effect cannot do without
    // is a legibility check on the actual device: a blur radius that reads as
    // "frosted" in one theme can make the draft text swim in another. Shipping
    // only a switch would have meant shipping our guess as the only setting.
    //
    // OFF by default — same call rikkahub makes (`enableBlurEffect = false`):
    // the blur records the whole message list every frame, and a user who never
    // asked for it pays that cost for a look they may not want.
    //
    // The switch lives in Settings → APPEARANCE (it is a look, not a tuning
    // knob); only the two numbers live in Chat Tuning.

    /** Master switch for the frosted-glass composer card. */
    const val KEY_COMPOSER_GLASS_ENABLED = "chat_composer_glass_enabled"
    const val COMPOSER_GLASS_ENABLED_DEFAULT = false

    /**
     * Backdrop blur radius, dp. Default 12 = rikkahub's Blur preset (its Glass
     * preset is 16; we have no Glass arm — see ComposerGlass).
     */
    const val KEY_COMPOSER_GLASS_BLUR_DP = "chat_composer_glass_blur_dp"
    const val COMPOSER_GLASS_BLUR_DEFAULT = 12
    const val COMPOSER_GLASS_BLUR_MIN = 4
    const val COMPOSER_GLASS_BLUR_MAX = 40

    /**
     * How much of the card colour covers the blur, in percent (0 = pure blur,
     * 100 = only the card colour, i.e. a solid card).
     * Default 30 = rikkahub's Glass tint (containerColor at alpha 0.3); its Blur
     * arm has no tint at all, but haze 1.x needs an opaque base or the rows
     * under the card show through as a double image.
     */
    const val KEY_COMPOSER_GLASS_TINT_PCT = "chat_composer_glass_tint_pct"
    const val COMPOSER_GLASS_TINT_DEFAULT = 30
    const val COMPOSER_GLASS_TINT_MIN = 0
    const val COMPOSER_GLASS_TINT_MAX = 100

    /** Every key this object owns — the change-listener filter. */
    val ALL_KEYS: Set<String> = setOf(
        KEY_SCROLL_NEAR_BOTTOM_DP,
        KEY_PREWARM_ROW_LIMIT,
        KEY_CODE_PREVIEW_LINES,
        KEY_TABLE_PREVIEW_ROWS,
        KEY_MARKDOWN_LINE_HEIGHT_SP,
        KEY_INPUT_MAX_LINES,
        KEY_SEND_SWIPE_THRESHOLD_DP,
        KEY_COMPOSER_GLASS_ENABLED,
        KEY_COMPOSER_GLASS_BLUR_DP,
        KEY_COMPOSER_GLASS_TINT_PCT,
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

    fun composerGlassEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COMPOSER_GLASS_ENABLED, COMPOSER_GLASS_ENABLED_DEFAULT)

    fun composerGlassBlurDp(context: Context): Int = readClamped(
        context, KEY_COMPOSER_GLASS_BLUR_DP,
        COMPOSER_GLASS_BLUR_DEFAULT, COMPOSER_GLASS_BLUR_MIN, COMPOSER_GLASS_BLUR_MAX,
    )

    fun composerGlassTintPct(context: Context): Int = readClamped(
        context, KEY_COMPOSER_GLASS_TINT_PCT,
        COMPOSER_GLASS_TINT_DEFAULT, COMPOSER_GLASS_TINT_MIN, COMPOSER_GLASS_TINT_MAX,
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
        composerGlassEnabled = composerGlassEnabled(context),
        composerGlassBlurDp = composerGlassBlurDp(context),
        composerGlassTintPct = composerGlassTintPct(context),
    )

    /**
     * [live-apply-1116] Single-key write for the live-apply panel: one slider
     * commit persists exactly its own knob and nothing else. That is strictly
     * safer than the batched all-ten-keys write this object used to expose —
     * a stale edit buffer can no longer clobber a concurrent write to a
     * DIFFERENT key (the batched path had to re-read every field at save time
     * to stay honest).
     *
     * Range checking stays in the readers (each clamps on the way out), so a
     * value outside its bounds — only reachable by a caller that bypassed the
     * slider's own min/max — still cannot escape into layout math.
     */
    fun setInt(context: Context, key: String, value: Int) {
        prefs(context).edit().putInt(key, value).apply()
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
    // [composer-glass-1115] Defaults == the pre-glass build: OFF, and the two
    // sliders parked on rikkahub's own values so enabling the switch alone
    // reproduces their look.
    val composerGlassEnabled: Boolean = ChatTuningPrefs.COMPOSER_GLASS_ENABLED_DEFAULT,
    val composerGlassBlurDp: Int = ChatTuningPrefs.COMPOSER_GLASS_BLUR_DEFAULT,
    val composerGlassTintPct: Int = ChatTuningPrefs.COMPOSER_GLASS_TINT_DEFAULT,
)
