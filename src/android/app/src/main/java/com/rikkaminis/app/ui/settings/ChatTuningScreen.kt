package com.rikkaminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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
import com.rikkaminis.app.ui.components.MinisTextButton

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
 *   - All edits are local state; the single Save action persists in one shot.
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

    SettingsScaffold(
        title = stringResource(R.string.chat_tuning_title),
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {

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
                )
                LimitsSliderRow(
                    title = stringResource(R.string.chat_tuning_prewarm),
                    subtitle = stringResource(R.string.chat_tuning_prewarm_desc),
                    valueLabel = "$prewarmRowLimit",
                    value = prewarmRowLimit,
                    min = ChatTuningPrefs.PREWARM_ROW_LIMIT_MIN,
                    max = ChatTuningPrefs.PREWARM_ROW_LIMIT_MAX,
                    onCommit = { prewarmRowLimit = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.chat_tuning_code_preview),
                    subtitle = stringResource(R.string.chat_tuning_code_preview_desc),
                    valueLabel = "$codePreviewLines",
                    value = codePreviewLines,
                    min = ChatTuningPrefs.CODE_PREVIEW_LINES_MIN,
                    max = ChatTuningPrefs.CODE_PREVIEW_LINES_MAX,
                    onCommit = { codePreviewLines = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.chat_tuning_table_preview),
                    subtitle = stringResource(R.string.chat_tuning_table_preview_desc),
                    valueLabel = "$tablePreviewRows",
                    value = tablePreviewRows,
                    min = ChatTuningPrefs.TABLE_PREVIEW_ROWS_MIN,
                    max = ChatTuningPrefs.TABLE_PREVIEW_ROWS_MAX,
                    onCommit = { tablePreviewRows = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.chat_tuning_line_height),
                    subtitle = stringResource(R.string.chat_tuning_line_height_desc),
                    valueLabel = "$markdownLineHeightSp sp",
                    value = markdownLineHeightSp,
                    min = ChatTuningPrefs.MARKDOWN_LINE_HEIGHT_MIN,
                    max = ChatTuningPrefs.MARKDOWN_LINE_HEIGHT_MAX,
                    onCommit = { markdownLineHeightSp = it },
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
                )
                LimitsSliderRow(
                    title = stringResource(R.string.chat_tuning_swipe_threshold),
                    subtitle = stringResource(R.string.chat_tuning_swipe_threshold_desc),
                    valueLabel = "$sendSwipeThresholdDp dp",
                    value = sendSwipeThresholdDp,
                    min = ChatTuningPrefs.SEND_SWIPE_THRESHOLD_MIN,
                    max = ChatTuningPrefs.SEND_SWIPE_THRESHOLD_MAX,
                    onCommit = { sendSwipeThresholdDp = it },
                    showDivider = false,
                )
            }
            LimitsSectionFooter(stringResource(R.string.chat_tuning_composer_footer))

            // ── Save ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                MinisTextButton(
                    onClick = {
                        ChatTuningPrefs.save(
                            context,
                            ChatTuningValues(
                                scrollNearBottomDp = scrollNearBottomDp,
                                prewarmRowLimit = prewarmRowLimit,
                                codePreviewLines = codePreviewLines,
                                tablePreviewRows = tablePreviewRows,
                                markdownLineHeightSp = markdownLineHeightSp,
                                inputMaxLines = inputMaxLines,
                                sendSwipeThresholdDp = sendSwipeThresholdDp,
                            ),
                        )
                        onBack()
                    },
                ) {
                    Text(stringResource(R.string.common_save))
                }
            }
        }
    }
}

/**
 * Live snapshot of the chat-tuning values, re-read whenever any of
 * [ChatTuningPrefs.ALL_KEYS] changes in the chat-tuning prefs file. Call from the
 * chat screen; the returned values feed layout math (scroll threshold),
 * composition locals (markdown folds / line height) and the composer
 * parameters. One listener for all seven keys keeps ChatScreen's existing
 * per-key listener block from growing seven more `when` arms.
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
