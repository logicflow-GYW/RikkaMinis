package com.rikkaminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rikkaminis.app.R
import com.rikkaminis.app.data.ChatTuningPrefs
import com.rikkaminis.app.data.ChatTuningValues

/**
 * [feat/chat-tuning-panel] Chat Tuning — the knobs that shape how the chat
 * reads and how the composer feels, all previously hard-coded literals:
 *
 *   1. Reading & scrolling — bottom-stick threshold, cold-open prewarm,
 *      markdown fold thresholds (code/table), body line height.
 *   2. Composer            — input box max lines, swipe-up-to-send distance.
 *
 * Design rules (same as RuntimeLimitsScreen, user's explicit asks):
 *   - The Settings entry row stays SHORT; detailed notes live HERE as
 *     per-row subtitles / section footers.
 *   - Defaults == the previously hard-coded literals, so an untouched
 *     install behaves identically to the pre-panel build.
 *   - [live-apply-1116] Every knob persists the moment its slider is released
 *     (single-key write, no Save, no draft buffer). The chat screen listens on
 *     this prefs file through [rememberChatTuning], so a committed value is
 *     live the next time the chat surface composes — no restart, no re-entry.
 */
@Composable
fun ChatTuningScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var scrollNearBottomDp by remember { mutableStateOf(ChatTuningPrefs.scrollNearBottomDp(context)) }
    var prewarmRowLimit by remember { mutableStateOf(ChatTuningPrefs.prewarmRowLimit(context)) }
    var codePreviewLines by remember { mutableStateOf(ChatTuningPrefs.codePreviewLines(context)) }
    var tablePreviewRows by remember { mutableStateOf(ChatTuningPrefs.tablePreviewRows(context)) }
    var markdownLineHeightSp by remember { mutableStateOf(ChatTuningPrefs.markdownLineHeightSp(context)) }
    var inputMaxLines by remember { mutableStateOf(ChatTuningPrefs.inputMaxLines(context)) }
    var sendSwipeThresholdDp by remember { mutableStateOf(ChatTuningPrefs.sendSwipeThresholdDp(context)) }
    var composerGlassBlurDp by remember { mutableStateOf(ChatTuningPrefs.composerGlassBlurDp(context)) }
    var composerGlassTintPct by remember { mutableStateOf(ChatTuningPrefs.composerGlassTintPct(context)) }

    // [FIX-6 / F-241] Re-read the snapshots when the prefs file changes
    // OUTSIDE this screen (`minis-config set chat.*`, backup-restore, the
    // agent). Same rationale as AppearanceScreen's [T-backup-import-refresh]
    // listener. [live-apply-1116] With no Save left there is nothing to write
    // back, so the listener's job is now purely display: an external write
    // must land in this panel's buffer instead of being overwritten the next
    // time the matching slider is touched.
    // [fix/dv22-b1-prefs-listener-filter] One arm per key: an external
    // single-key write must only overwrite the buffer for THAT key, never
    // clear the whole group of unsaved edits (dv22-B-01 family).
    DisposableEffect(context) {
        val prefs = context.applicationContext
            .getSharedPreferences(ChatTuningPrefs.PREFS, android.content.Context.MODE_PRIVATE)
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                when (key) {
                    ChatTuningPrefs.KEY_SCROLL_NEAR_BOTTOM_DP ->
                        scrollNearBottomDp = ChatTuningPrefs.scrollNearBottomDp(context)
                    ChatTuningPrefs.KEY_PREWARM_ROW_LIMIT ->
                        prewarmRowLimit = ChatTuningPrefs.prewarmRowLimit(context)
                    ChatTuningPrefs.KEY_CODE_PREVIEW_LINES ->
                        codePreviewLines = ChatTuningPrefs.codePreviewLines(context)
                    ChatTuningPrefs.KEY_TABLE_PREVIEW_ROWS ->
                        tablePreviewRows = ChatTuningPrefs.tablePreviewRows(context)
                    ChatTuningPrefs.KEY_MARKDOWN_LINE_HEIGHT_SP ->
                        markdownLineHeightSp = ChatTuningPrefs.markdownLineHeightSp(context)
                    ChatTuningPrefs.KEY_INPUT_MAX_LINES ->
                        inputMaxLines = ChatTuningPrefs.inputMaxLines(context)
                    ChatTuningPrefs.KEY_SEND_SWIPE_THRESHOLD_DP ->
                        sendSwipeThresholdDp = ChatTuningPrefs.sendSwipeThresholdDp(context)
                    ChatTuningPrefs.KEY_COMPOSER_GLASS_BLUR_DP ->
                        composerGlassBlurDp = ChatTuningPrefs.composerGlassBlurDp(context)
                    ChatTuningPrefs.KEY_COMPOSER_GLASS_TINT_PCT ->
                        composerGlassTintPct = ChatTuningPrefs.composerGlassTintPct(context)
                }
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    SettingsScaffold(
        title = stringResource(R.string.chat_tuning_title),
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {

            // [live-apply-1116] No Save action exists any more — say so where the
            // user lands, not in a footer three sections down.
            LimitsSectionFooter(stringResource(R.string.settings_live_apply_hint))

            // ── 1. Reading & scrolling ───────────────────────────────────
            LimitsSectionCard(title = stringResource(R.string.chat_tuning_section_reading)) {
                LimitsSliderRow(
                    title = stringResource(R.string.chat_tuning_scroll_threshold),
                    subtitle = stringResource(R.string.chat_tuning_scroll_threshold_desc),
                    valueLabel = "$scrollNearBottomDp dp",
                    value = scrollNearBottomDp,
                    min = ChatTuningPrefs.SCROLL_NEAR_BOTTOM_MIN,
                    max = ChatTuningPrefs.SCROLL_NEAR_BOTTOM_MAX,
                    onCommit = { scrollNearBottomDp = it },
                    onCommitFinished = { ChatTuningPrefs.setInt(context, ChatTuningPrefs.KEY_SCROLL_NEAR_BOTTOM_DP, scrollNearBottomDp) },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.chat_tuning_prewarm),
                    subtitle = stringResource(R.string.chat_tuning_prewarm_desc),
                    valueLabel = "$prewarmRowLimit",
                    value = prewarmRowLimit,
                    min = ChatTuningPrefs.PREWARM_ROW_LIMIT_MIN,
                    max = ChatTuningPrefs.PREWARM_ROW_LIMIT_MAX,
                    onCommit = { prewarmRowLimit = it },
                    onCommitFinished = { ChatTuningPrefs.setInt(context, ChatTuningPrefs.KEY_PREWARM_ROW_LIMIT, prewarmRowLimit) },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.chat_tuning_code_preview),
                    subtitle = stringResource(R.string.chat_tuning_code_preview_desc),
                    valueLabel = "$codePreviewLines",
                    value = codePreviewLines,
                    min = ChatTuningPrefs.CODE_PREVIEW_LINES_MIN,
                    max = ChatTuningPrefs.CODE_PREVIEW_LINES_MAX,
                    onCommit = { codePreviewLines = it },
                    onCommitFinished = { ChatTuningPrefs.setInt(context, ChatTuningPrefs.KEY_CODE_PREVIEW_LINES, codePreviewLines) },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.chat_tuning_table_preview),
                    subtitle = stringResource(R.string.chat_tuning_table_preview_desc),
                    valueLabel = "$tablePreviewRows",
                    value = tablePreviewRows,
                    min = ChatTuningPrefs.TABLE_PREVIEW_ROWS_MIN,
                    max = ChatTuningPrefs.TABLE_PREVIEW_ROWS_MAX,
                    onCommit = { tablePreviewRows = it },
                    onCommitFinished = { ChatTuningPrefs.setInt(context, ChatTuningPrefs.KEY_TABLE_PREVIEW_ROWS, tablePreviewRows) },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.chat_tuning_line_height),
                    subtitle = stringResource(R.string.chat_tuning_line_height_desc),
                    valueLabel = "$markdownLineHeightSp sp",
                    value = markdownLineHeightSp,
                    min = ChatTuningPrefs.MARKDOWN_LINE_HEIGHT_MIN,
                    max = ChatTuningPrefs.MARKDOWN_LINE_HEIGHT_MAX,
                    onCommit = { markdownLineHeightSp = it },
                    onCommitFinished = { ChatTuningPrefs.setInt(context, ChatTuningPrefs.KEY_MARKDOWN_LINE_HEIGHT_SP, markdownLineHeightSp) },
                    showDivider = false,
                )
            }
            LimitsSectionFooter(stringResource(R.string.chat_tuning_reading_footer))

            // ── 2. Composer ──────────────────────────────────────────────
            LimitsSectionCard(title = stringResource(R.string.chat_tuning_section_input)) {
                LimitsSliderRow(
                    title = stringResource(R.string.chat_tuning_input_max_lines),
                    subtitle = stringResource(R.string.chat_tuning_input_max_lines_desc),
                    valueLabel = "$inputMaxLines",
                    value = inputMaxLines,
                    min = ChatTuningPrefs.INPUT_MAX_LINES_MIN,
                    max = ChatTuningPrefs.INPUT_MAX_LINES_MAX,
                    onCommit = { inputMaxLines = it },
                    onCommitFinished = { ChatTuningPrefs.setInt(context, ChatTuningPrefs.KEY_INPUT_MAX_LINES, inputMaxLines) },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.chat_tuning_swipe_threshold),
                    subtitle = stringResource(R.string.chat_tuning_swipe_threshold_desc),
                    valueLabel = "$sendSwipeThresholdDp dp",
                    value = sendSwipeThresholdDp,
                    min = ChatTuningPrefs.SEND_SWIPE_THRESHOLD_MIN,
                    max = ChatTuningPrefs.SEND_SWIPE_THRESHOLD_MAX,
                    onCommit = { sendSwipeThresholdDp = it },
                    onCommitFinished = { ChatTuningPrefs.setInt(context, ChatTuningPrefs.KEY_SEND_SWIPE_THRESHOLD_DP, sendSwipeThresholdDp) },
                    showDivider = false,
                )
            }
            LimitsSectionFooter(stringResource(R.string.chat_tuning_composer_footer))

            // ── 3. Composer background effect [composer-glass-1115] ──────
            //
            // rikkahub parity: their Settings → Display has a switch plus a
            // Blur / Glass picker. We ship a switch plus the two numbers that
            // actually exist in haze 1.x — the AGSL "glass" preset needs haze
            // 2.x (Compose 1.12 / Kotlin 2.4), which this app cannot take yet.
            // Both sliders stay visible while the master switch is off, greyed
            // by their own value labels: a user should be able to see what
            // they'd be tuning before turning it on.
            LimitsSectionCard(title = stringResource(R.string.chat_tuning_section_composer_glass)) {
                LimitsSliderRow(
                    title = stringResource(R.string.chat_tuning_glass_blur),
                    subtitle = stringResource(R.string.chat_tuning_glass_blur_desc),
                    valueLabel = "$composerGlassBlurDp dp",
                    value = composerGlassBlurDp,
                    min = ChatTuningPrefs.COMPOSER_GLASS_BLUR_MIN,
                    max = ChatTuningPrefs.COMPOSER_GLASS_BLUR_MAX,
                    onCommit = { composerGlassBlurDp = it },
                    onCommitFinished = { ChatTuningPrefs.setInt(context, ChatTuningPrefs.KEY_COMPOSER_GLASS_BLUR_DP, composerGlassBlurDp) },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.chat_tuning_glass_tint),
                    subtitle = stringResource(R.string.chat_tuning_glass_tint_desc),
                    valueLabel = "$composerGlassTintPct%",
                    value = composerGlassTintPct,
                    min = ChatTuningPrefs.COMPOSER_GLASS_TINT_MIN,
                    max = ChatTuningPrefs.COMPOSER_GLASS_TINT_MAX,
                    onCommit = { composerGlassTintPct = it },
                    onCommitFinished = { ChatTuningPrefs.setInt(context, ChatTuningPrefs.KEY_COMPOSER_GLASS_TINT_PCT, composerGlassTintPct) },
                    showDivider = false,
                )
            }
            LimitsSectionFooter(stringResource(R.string.chat_tuning_glass_footer))

        }
    }
}

/**
 * Live snapshot of the chat-tuning values, re-read whenever any of
 * [ChatTuningPrefs.ALL_KEYS] changes in the chat-tuning prefs file. Call from the
 * chat screen; the returned values feed layout math (scroll threshold),
 * composition locals (markdown folds / line height) and the composer
 * parameters. One listener for all ten keys keeps ChatScreen's existing
 * per-key listener block from growing ten more `when` arms.
 */
@Composable
internal fun rememberChatTuning(context: android.content.Context): ChatTuningValues {
    val prefs = remember(context) { ChatTuningPrefs.prefs(context) }
    var values by remember { mutableStateOf(ChatTuningPrefs.readAll(context)) }
    DisposableEffect(prefs) {
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key in ChatTuningPrefs.ALL_KEYS) {
                    values = ChatTuningPrefs.readAll(context)
                }
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return values
}
