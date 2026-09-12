package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rikkaminis.app.data.AgentRuntimeLimitsPrefs
import com.rikkaminis.app.data.ConcurrencyPrefs
import com.rikkaminis.app.data.SubagentPrefs
import com.rikkaminis.app.ui.components.MinisTextButton

/**
 * [feat/runtime-limits-panel] Runtime Limits — the ONE page that groups every
 * user-tunable agent runtime knob:
 *
 *   1. Sessions & dispatch  — sub-agent switch, concurrent-session cap (+live
 *      running/waiting occupancy).
 *   2. Agent-loop budget    — turns / provider attempts / tool calls / shell
 *                             commands / compaction / concurrent tools / run
 *                             deadline (all enforced, run-resumable).
 *   3. Stream recovery      — length-wall & EOF continuations, deterministic
 *                             empty fast-exit, transient retries, verify
 *                             nudges.
 *   4. Network & worker     — generation hard wall, first-chunk budgets,
 *                             worker slot pool + queue admission.
 *
 * Design rules (user's explicit asks):
 *   - The Settings entry row stays SHORT (one-line summary only); everything
 *     detailed — risk notes, effective-when notes — lives HERE, inside the
 *     page, as section footers / per-row subtitles.
 *   - Defaults == the previously hard-coded constants, so an untouched
 *     install behaves identically to the pre-panel build.
 *
 * All edits are local state; the single Save action persists every group in
 * one shot (ConcurrencyPrefs + SubagentPrefs + AgentRuntimeLimitsPrefs).
 */
@Composable
fun RuntimeLimitsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // ── Group 1 state ──
    var subagentEnabled by remember { mutableStateOf(SubagentPrefs.isEnabled(context)) }
    var maxSessions by remember { mutableStateOf(ConcurrencyPrefs.maxConcurrentSessions()) }
    val occupancy by produceState(
        initialValue = com.rikkaminis.app.service.SessionConcurrencyManager.occupancy(),
    ) {
        while (true) {
            value = com.rikkaminis.app.service.SessionConcurrencyManager.occupancy()
            kotlinx.coroutines.delay(1000L)
        }
    }

    // ── Group 2 state ──
    var maxTurns by remember { mutableStateOf(AgentRuntimeLimitsPrefs.maxTurns()) }
    var maxProviderAttempts by remember { mutableStateOf(AgentRuntimeLimitsPrefs.maxProviderAttempts()) }
    var maxToolCalls by remember { mutableStateOf(AgentRuntimeLimitsPrefs.maxToolCalls()) }
    var maxShellCommands by remember { mutableStateOf(AgentRuntimeLimitsPrefs.maxShellCommands()) }
    var maxCompactionCalls by remember { mutableStateOf(AgentRuntimeLimitsPrefs.maxCompactionCalls()) }
    var maxConcurrentTools by remember { mutableStateOf(AgentRuntimeLimitsPrefs.maxConcurrentTools()) }
    var runDeadlineMin by remember { mutableStateOf(AgentRuntimeLimitsPrefs.runDeadlineMinutes()) }

    // ── Group 3 state ──
    var lengthWallContinues by remember { mutableStateOf(AgentRuntimeLimitsPrefs.lengthWallContinues()) }
    var eofStubContinues by remember { mutableStateOf(AgentRuntimeLimitsPrefs.eofStubContinues()) }
    var deterministicEmptyLimit by remember { mutableStateOf(AgentRuntimeLimitsPrefs.deterministicEmptyLimit()) }
    var transientRetries by remember { mutableStateOf(AgentRuntimeLimitsPrefs.transientRetries()) }
    var verifyNudges by remember { mutableStateOf(AgentRuntimeLimitsPrefs.verifyNudges()) }

    // ── Group 4 state ──
    var generationTimeoutMin by remember { mutableStateOf(AgentRuntimeLimitsPrefs.generationTimeoutMinutes()) }
    var firstChunkDirectSec by remember { mutableStateOf(AgentRuntimeLimitsPrefs.firstChunkDirectSec()) }
    var firstChunkProxySec by remember { mutableStateOf(AgentRuntimeLimitsPrefs.firstChunkProxySec()) }
    var providerSlots by remember { mutableStateOf(AgentRuntimeLimitsPrefs.providerSlots()) }
    var queueAdmission by remember { mutableStateOf(AgentRuntimeLimitsPrefs.queueAdmission()) }
    // [feat/chat-tuning-panel-b] Group 5 + 6.
    var autoCompactMinTailTokens by remember { mutableStateOf(AgentRuntimeLimitsPrefs.autoCompactMinTailTokens()) }
    var autoCompactMinIntervalMin by remember { mutableStateOf(AgentRuntimeLimitsPrefs.autoCompactMinIntervalMin()) }
    var memoryInjectLines by remember { mutableStateOf(AgentRuntimeLimitsPrefs.memoryInjectLines()) }
    var memoryRollupInjectKb by remember { mutableStateOf(AgentRuntimeLimitsPrefs.memoryRollupInjectKb()) }
    var memorySearchLines by remember { mutableStateOf(AgentRuntimeLimitsPrefs.memorySearchLines()) }
    var memoryLookbackDays by remember { mutableStateOf(AgentRuntimeLimitsPrefs.memoryLookbackDays()) }
    var imageMaxPerImageMb by remember { mutableStateOf(AgentRuntimeLimitsPrefs.imageMaxPerImageMb()) }
    var imageMaxTotalMb by remember { mutableStateOf(AgentRuntimeLimitsPrefs.imageMaxTotalMb()) }
    var imageMaxRequestMb by remember { mutableStateOf(AgentRuntimeLimitsPrefs.imageMaxRequestMb()) }
    var imageMaxEdgePx by remember { mutableStateOf(AgentRuntimeLimitsPrefs.imageMaxEdgePx()) }
    var imageJpegQuality by remember { mutableStateOf(AgentRuntimeLimitsPrefs.imageJpegQuality()) }
    var browserNavTimeoutSec by remember { mutableStateOf(AgentRuntimeLimitsPrefs.browserNavTimeoutSec()) }
    var browserDomStableSec by remember { mutableStateOf(AgentRuntimeLimitsPrefs.browserDomStableSec()) }
    var browserScreenshotQuality by remember { mutableStateOf(AgentRuntimeLimitsPrefs.browserScreenshotQuality()) }
    var shellOutputKb by remember { mutableStateOf(AgentRuntimeLimitsPrefs.shellOutputKb()) }
    var shellTimeoutSec by remember { mutableStateOf(AgentRuntimeLimitsPrefs.shellTimeoutSec()) }

    SettingsScaffold(
        title = stringResource(R.string.runtime_limits_title),
        onBack = onBack,
        // scaffold already wraps content in verticalScroll (default true) —
        // no nested scrolling here.
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {

            // ── 1. Sessions & dispatch ───────────────────────────────────
            LimitsSectionCard(title = stringResource(R.string.runtime_limits_section_sessions)) {
                LimitsSwitchRow(
                    title = stringResource(R.string.settings_subagent_dispatch),
                    subtitle = stringResource(R.string.settings_subagent_dispatch_subtitle),
                    checked = subagentEnabled,
                    onCheckedChange = { subagentEnabled = it },
                    showDivider = true,
                )
                LimitsSliderRow(
                    title = stringResource(R.string.settings_max_concurrent),
                    valueLabel = stringResource(
                        R.string.settings_max_concurrent_subtitle,
                        maxSessions, occupancy.active, occupancy.waiting,
                    ),
                    value = maxSessions,
                    min = ConcurrencyPrefs.MIN,
                    max = ConcurrencyPrefs.MAX,
                    onCommit = { maxSessions = it },
                    showDivider = false,
                )
            }
            LimitsSectionFooter(stringResource(R.string.runtime_limits_sessions_footer))

            // ── 2. Agent-loop budget ─────────────────────────────────────
            LimitsSectionCard(title = stringResource(R.string.runtime_limits_section_budget)) {
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_turns),
                    subtitle = stringResource(R.string.runtime_limits_turns_desc),
                    value = maxTurns,
                    min = AgentRuntimeLimitsPrefs.TURNS_MIN,
                    max = AgentRuntimeLimitsPrefs.TURNS_MAX,
                    onCommit = { maxTurns = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_provider_attempts),
                    subtitle = stringResource(R.string.runtime_limits_provider_attempts_desc),
                    value = maxProviderAttempts,
                    min = AgentRuntimeLimitsPrefs.PROVIDER_ATTEMPTS_MIN,
                    max = AgentRuntimeLimitsPrefs.PROVIDER_ATTEMPTS_MAX,
                    onCommit = { maxProviderAttempts = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_tool_calls),
                    subtitle = stringResource(R.string.runtime_limits_tool_calls_desc),
                    value = maxToolCalls,
                    min = AgentRuntimeLimitsPrefs.TOOL_CALLS_MIN,
                    max = AgentRuntimeLimitsPrefs.TOOL_CALLS_MAX,
                    onCommit = { maxToolCalls = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_shell_commands),
                    subtitle = stringResource(R.string.runtime_limits_shell_commands_desc),
                    value = maxShellCommands,
                    min = AgentRuntimeLimitsPrefs.SHELL_COMMANDS_MIN,
                    max = AgentRuntimeLimitsPrefs.SHELL_COMMANDS_MAX,
                    onCommit = { maxShellCommands = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_compaction),
                    subtitle = stringResource(R.string.runtime_limits_compaction_desc),
                    value = maxCompactionCalls,
                    min = AgentRuntimeLimitsPrefs.COMPACTION_CALLS_MIN,
                    max = AgentRuntimeLimitsPrefs.COMPACTION_CALLS_MAX,
                    onCommit = { maxCompactionCalls = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_concurrent_tools),
                    subtitle = stringResource(R.string.runtime_limits_concurrent_tools_desc),
                    value = maxConcurrentTools,
                    min = AgentRuntimeLimitsPrefs.CONCURRENT_TOOLS_MIN,
                    max = AgentRuntimeLimitsPrefs.CONCURRENT_TOOLS_MAX,
                    onCommit = { maxConcurrentTools = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_deadline),
                    subtitle = stringResource(R.string.runtime_limits_deadline_desc),
                    value = runDeadlineMin,
                    min = AgentRuntimeLimitsPrefs.DEADLINE_MIN_MIN,
                    max = AgentRuntimeLimitsPrefs.DEADLINE_MAX_MIN,
                    onCommit = { runDeadlineMin = it },
                    showDivider = false,
                )
            }
            LimitsSectionFooter(stringResource(R.string.runtime_limits_budget_footer))

            // ── 3. Stream recovery ───────────────────────────────────────
            LimitsSectionCard(title = stringResource(R.string.runtime_limits_section_recovery)) {
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_length_wall),
                    subtitle = stringResource(R.string.runtime_limits_length_wall_desc),
                    value = lengthWallContinues,
                    min = AgentRuntimeLimitsPrefs.LENGTH_WALL_MIN,
                    max = AgentRuntimeLimitsPrefs.LENGTH_WALL_MAX,
                    onCommit = { lengthWallContinues = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_eof_stub),
                    subtitle = stringResource(R.string.runtime_limits_eof_stub_desc),
                    value = eofStubContinues,
                    min = AgentRuntimeLimitsPrefs.EOF_STUB_MIN,
                    max = AgentRuntimeLimitsPrefs.EOF_STUB_MAX,
                    onCommit = { eofStubContinues = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_det_empty),
                    subtitle = stringResource(R.string.runtime_limits_det_empty_desc),
                    value = deterministicEmptyLimit,
                    min = AgentRuntimeLimitsPrefs.DET_EMPTY_MIN,
                    max = AgentRuntimeLimitsPrefs.DET_EMPTY_MAX,
                    onCommit = { deterministicEmptyLimit = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_transient_retries),
                    subtitle = stringResource(R.string.runtime_limits_transient_retries_desc),
                    value = transientRetries,
                    min = AgentRuntimeLimitsPrefs.TRANSIENT_RETRIES_MIN,
                    max = AgentRuntimeLimitsPrefs.TRANSIENT_RETRIES_MAX,
                    onCommit = { transientRetries = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_verify_nudges),
                    subtitle = stringResource(R.string.runtime_limits_verify_nudges_desc),
                    value = verifyNudges,
                    min = AgentRuntimeLimitsPrefs.VERIFY_NUDGES_MIN,
                    max = AgentRuntimeLimitsPrefs.VERIFY_NUDGES_MAX,
                    onCommit = { verifyNudges = it },
                    showDivider = false,
                )
            }
            LimitsSectionFooter(stringResource(R.string.runtime_limits_recovery_footer))

            // ── 4. Network & worker ──────────────────────────────────────
            LimitsSectionCard(title = stringResource(R.string.runtime_limits_section_network)) {
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_generation_wall),
                    subtitle = stringResource(R.string.runtime_limits_generation_wall_desc),
                    value = generationTimeoutMin,
                    min = AgentRuntimeLimitsPrefs.GENERATION_TIMEOUT_MIN_MIN,
                    max = AgentRuntimeLimitsPrefs.GENERATION_TIMEOUT_MAX_MIN,
                    onCommit = { generationTimeoutMin = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_first_chunk_direct),
                    subtitle = stringResource(R.string.runtime_limits_first_chunk_direct_desc),
                    value = firstChunkDirectSec,
                    min = AgentRuntimeLimitsPrefs.FIRST_CHUNK_DIRECT_MIN_SEC,
                    max = AgentRuntimeLimitsPrefs.FIRST_CHUNK_DIRECT_MAX_SEC,
                    onCommit = { firstChunkDirectSec = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_first_chunk_proxy),
                    subtitle = stringResource(R.string.runtime_limits_first_chunk_proxy_desc),
                    value = firstChunkProxySec,
                    min = AgentRuntimeLimitsPrefs.FIRST_CHUNK_PROXY_MIN_SEC,
                    max = AgentRuntimeLimitsPrefs.FIRST_CHUNK_PROXY_MAX_SEC,
                    onCommit = { firstChunkProxySec = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_provider_slots),
                    subtitle = stringResource(R.string.runtime_limits_provider_slots_desc),
                    value = providerSlots,
                    min = AgentRuntimeLimitsPrefs.PROVIDER_SLOTS_MIN,
                    max = AgentRuntimeLimitsPrefs.PROVIDER_SLOTS_MAX,
                    onCommit = { providerSlots = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_queue_admission),
                    subtitle = stringResource(R.string.runtime_limits_queue_admission_desc),
                    value = queueAdmission,
                    min = AgentRuntimeLimitsPrefs.QUEUE_ADMISSION_MIN,
                    max = AgentRuntimeLimitsPrefs.QUEUE_ADMISSION_MAX,
                    onCommit = { queueAdmission = it },
                    showDivider = false,
                )
            }
            LimitsSectionFooter(stringResource(R.string.runtime_limits_network_footer))

            // ── [feat/chat-tuning-panel-b] 5. Context & memory budget ─────
            LimitsSectionCard(title = stringResource(R.string.runtime_limits_section_context)) {
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_compact_tail),
                    subtitle = stringResource(R.string.runtime_limits_compact_tail_desc),
                    valueLabel = "$autoCompactMinTailTokens",
                    value = autoCompactMinTailTokens,
                    min = AgentRuntimeLimitsPrefs.COMPACT_TAIL_TOKENS_MIN,
                    max = AgentRuntimeLimitsPrefs.COMPACT_TAIL_TOKENS_MAX,
                    onCommit = { autoCompactMinTailTokens = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_compact_interval),
                    subtitle = stringResource(R.string.runtime_limits_compact_interval_desc),
                    valueLabel = "$autoCompactMinIntervalMin",
                    value = autoCompactMinIntervalMin,
                    min = AgentRuntimeLimitsPrefs.COMPACT_INTERVAL_MIN_MIN,
                    max = AgentRuntimeLimitsPrefs.COMPACT_INTERVAL_MAX_MIN,
                    onCommit = { autoCompactMinIntervalMin = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_memory_inject),
                    subtitle = stringResource(R.string.runtime_limits_memory_inject_desc),
                    valueLabel = "$memoryInjectLines",
                    value = memoryInjectLines,
                    min = AgentRuntimeLimitsPrefs.MEMORY_INJECT_LINES_MIN,
                    max = AgentRuntimeLimitsPrefs.MEMORY_INJECT_LINES_MAX,
                    onCommit = { memoryInjectLines = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_memory_rollup),
                    subtitle = stringResource(R.string.runtime_limits_memory_rollup_desc),
                    valueLabel = "$memoryRollupInjectKb",
                    value = memoryRollupInjectKb,
                    min = AgentRuntimeLimitsPrefs.MEMORY_ROLLUP_KB_MIN,
                    max = AgentRuntimeLimitsPrefs.MEMORY_ROLLUP_KB_MAX,
                    onCommit = { memoryRollupInjectKb = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_memory_search),
                    subtitle = stringResource(R.string.runtime_limits_memory_search_desc),
                    valueLabel = "$memorySearchLines",
                    value = memorySearchLines,
                    min = AgentRuntimeLimitsPrefs.MEMORY_SEARCH_LINES_MIN,
                    max = AgentRuntimeLimitsPrefs.MEMORY_SEARCH_LINES_MAX,
                    onCommit = { memorySearchLines = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_memory_lookback),
                    subtitle = stringResource(R.string.runtime_limits_memory_lookback_desc),
                    valueLabel = "$memoryLookbackDays",
                    value = memoryLookbackDays,
                    min = AgentRuntimeLimitsPrefs.MEMORY_LOOKBACK_DAYS_MIN,
                    max = AgentRuntimeLimitsPrefs.MEMORY_LOOKBACK_DAYS_MAX,
                    onCommit = { memoryLookbackDays = it },
                    showDivider = false,
                )
            }
            LimitsSectionFooter(stringResource(R.string.runtime_limits_context_footer))

            // ── [feat/chat-tuning-panel-b] 6. Media & tool budgets ────────
            LimitsSectionCard(title = stringResource(R.string.runtime_limits_section_media)) {
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_image_per_image),
                    subtitle = stringResource(R.string.runtime_limits_image_per_image_desc),
                    valueLabel = "$imageMaxPerImageMb",
                    value = imageMaxPerImageMb,
                    min = AgentRuntimeLimitsPrefs.IMAGE_PER_IMAGE_MB_MIN,
                    max = AgentRuntimeLimitsPrefs.IMAGE_PER_IMAGE_MB_MAX,
                    onCommit = { imageMaxPerImageMb = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_image_total),
                    subtitle = stringResource(R.string.runtime_limits_image_total_desc),
                    valueLabel = "$imageMaxTotalMb",
                    value = imageMaxTotalMb,
                    min = AgentRuntimeLimitsPrefs.IMAGE_TOTAL_MB_MIN,
                    max = AgentRuntimeLimitsPrefs.IMAGE_TOTAL_MB_MAX,
                    onCommit = { imageMaxTotalMb = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_image_request),
                    subtitle = stringResource(R.string.runtime_limits_image_request_desc),
                    valueLabel = "$imageMaxRequestMb",
                    value = imageMaxRequestMb,
                    min = AgentRuntimeLimitsPrefs.IMAGE_REQUEST_MB_MIN,
                    max = AgentRuntimeLimitsPrefs.IMAGE_REQUEST_MB_MAX,
                    onCommit = { imageMaxRequestMb = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_image_edge),
                    subtitle = stringResource(R.string.runtime_limits_image_edge_desc),
                    valueLabel = "$imageMaxEdgePx",
                    value = imageMaxEdgePx,
                    min = AgentRuntimeLimitsPrefs.IMAGE_EDGE_MIN,
                    max = AgentRuntimeLimitsPrefs.IMAGE_EDGE_MAX,
                    onCommit = { imageMaxEdgePx = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_image_quality),
                    subtitle = stringResource(R.string.runtime_limits_image_quality_desc),
                    valueLabel = "$imageJpegQuality",
                    value = imageJpegQuality,
                    min = AgentRuntimeLimitsPrefs.IMAGE_QUALITY_MIN,
                    max = AgentRuntimeLimitsPrefs.IMAGE_QUALITY_MAX,
                    onCommit = { imageJpegQuality = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_browser_nav),
                    subtitle = stringResource(R.string.runtime_limits_browser_nav_desc),
                    valueLabel = "$browserNavTimeoutSec",
                    value = browserNavTimeoutSec,
                    min = AgentRuntimeLimitsPrefs.BROWSER_NAV_TIMEOUT_MIN_SEC,
                    max = AgentRuntimeLimitsPrefs.BROWSER_NAV_TIMEOUT_MAX_SEC,
                    onCommit = { browserNavTimeoutSec = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_browser_dom),
                    subtitle = stringResource(R.string.runtime_limits_browser_dom_desc),
                    valueLabel = "$browserDomStableSec",
                    value = browserDomStableSec,
                    min = AgentRuntimeLimitsPrefs.BROWSER_DOM_STABLE_MIN_SEC,
                    max = AgentRuntimeLimitsPrefs.BROWSER_DOM_STABLE_MAX_SEC,
                    onCommit = { browserDomStableSec = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_browser_screenshot),
                    subtitle = stringResource(R.string.runtime_limits_browser_screenshot_desc),
                    valueLabel = "$browserScreenshotQuality",
                    value = browserScreenshotQuality,
                    min = AgentRuntimeLimitsPrefs.BROWSER_SCREENSHOT_Q_MIN,
                    max = AgentRuntimeLimitsPrefs.BROWSER_SCREENSHOT_Q_MAX,
                    onCommit = { browserScreenshotQuality = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_shell_output),
                    subtitle = stringResource(R.string.runtime_limits_shell_output_desc),
                    valueLabel = "$shellOutputKb",
                    value = shellOutputKb,
                    min = AgentRuntimeLimitsPrefs.SHELL_OUTPUT_KB_MIN,
                    max = AgentRuntimeLimitsPrefs.SHELL_OUTPUT_KB_MAX,
                    onCommit = { shellOutputKb = it },
                )
                LimitsSliderRow(
                    title = stringResource(R.string.runtime_limits_shell_timeout),
                    subtitle = stringResource(R.string.runtime_limits_shell_timeout_desc),
                    valueLabel = "$shellTimeoutSec",
                    value = shellTimeoutSec,
                    min = AgentRuntimeLimitsPrefs.SHELL_TIMEOUT_MIN_SEC,
                    max = AgentRuntimeLimitsPrefs.SHELL_TIMEOUT_MAX_SEC,
                    onCommit = { shellTimeoutSec = it },
                    showDivider = false,
                )
            }
            LimitsSectionFooter(stringResource(R.string.runtime_limits_media_footer))

            // ── Save ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                MinisTextButton(
                    onClick = {
                        SubagentPrefs.setEnabled(context, subagentEnabled)
                        ConcurrencyPrefs.setMaxConcurrentSessions(context, maxSessions)
                        AgentRuntimeLimitsPrefs.save(
                            context = context,
                            maxTurns = maxTurns,
                            maxProviderAttempts = maxProviderAttempts,
                            maxToolCalls = maxToolCalls,
                            maxShellCommands = maxShellCommands,
                            maxCompactionCalls = maxCompactionCalls,
                            maxConcurrentTools = maxConcurrentTools,
                            runDeadlineMinutes = runDeadlineMin,
                            lengthWallContinues = lengthWallContinues,
                            eofStubContinues = eofStubContinues,
                            deterministicEmptyLimit = deterministicEmptyLimit,
                            transientRetries = transientRetries,
                            verifyNudges = verifyNudges,
                            generationTimeoutMinutes = generationTimeoutMin,
                            firstChunkDirectSec = firstChunkDirectSec,
                            firstChunkProxySec = firstChunkProxySec,
                            providerSlots = providerSlots,
                            queueAdmission = queueAdmission,
                            // [feat/chat-tuning-panel-b] Group 5 + 6.
                            autoCompactMinTailTokens = autoCompactMinTailTokens,
                            autoCompactMinIntervalMin = autoCompactMinIntervalMin,
                            memoryInjectLines = memoryInjectLines,
                            memoryRollupInjectKb = memoryRollupInjectKb,
                            memorySearchLines = memorySearchLines,
                            memoryLookbackDays = memoryLookbackDays,
                            imageMaxPerImageMb = imageMaxPerImageMb,
                            imageMaxTotalMb = imageMaxTotalMb,
                            imageMaxRequestMb = imageMaxRequestMb,
                            imageMaxEdgePx = imageMaxEdgePx,
                            imageJpegQuality = imageJpegQuality,
                            browserNavTimeoutSec = browserNavTimeoutSec,
                            browserDomStableSec = browserDomStableSec,
                            browserScreenshotQuality = browserScreenshotQuality,
                            shellOutputKb = shellOutputKb,
                            shellTimeoutSec = shellTimeoutSec,
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

// ── Shared building blocks (used by RuntimeLimitsScreen AND ChatTuningScreen;
//    originally mirrored SettingsScreen's private ones) ────────────────────

@Composable
internal fun LimitsSectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(color = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            content()
        }
    }
}

@Composable
internal fun LimitsSectionFooter(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        lineHeight = 16.sp,
    )
}

@Composable
internal fun LimitsSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

// [fix/tuning-slider-density] Keep every pre-existing tuning row on its
// original 1-unit stepping (the widest shipped span is 983), and collapse
// only wider spans.
internal const val MAX_FINE_SPAN = 1024
/** Slot target for wide spans; the actual count is `span / width`. */
internal const val WIDE_TARGET_SLOTS = 64
/** Below this many slots a wide span falls back to a continuous slider. */
internal const val MIN_SLIDE_SLOTS = 16

/**
 * [fix/tuning-slider-density] Tick count for [Slider]'s `steps` parameter.
 *
 * Material3 draws one tick shape per step on EVERY frame of a drag and
 * linearly scans all of them per pointer move, so `steps = span - 1` on the
 * widest tuning rows was heavy: the 2000..32000 token row would draw
 * ~30 000 circles per frame (shipped maximum before this fix: 983 steps,
 * which stayed affordable).
 *
 * Spans up to [MAX_FINE_SPAN] keep exact 1-unit stepping; wider spans snap
 * to ≈[WIDE_TARGET_SLOTS] slots. The slot width is chosen as the smallest
 * exact divisor of the span (preferring a round multiple of 5), so dragged
 * values stay nice: 2000..32000 lands on 500-token steps, 800..4000 on
 * 50-px steps, 60..1800 on 30-s steps. Spans with no usable divisor fall
 * back to a continuous slider (0): no ticks, no scan cost.
 */
internal fun sliderStepsFor(min: Int, max: Int): Int {
    val span = max - min
    if (span <= 1) return 0
    if (span <= MAX_FINE_SPAN) return span - 1
    val start = (span + WIDE_TARGET_SLOTS - 1) / WIDE_TARGET_SLOTS
    // Slots = span / width; requiring MIN_SLIDE_SLOTS slots caps the scan.
    val scanLimit = span / MIN_SLIDE_SLOTS
    var width = start
    var firstDivisor = -1
    var roundDivisor = -1
    while (width <= scanLimit) {
        if (span % width == 0) {
            if (firstDivisor < 0) firstDivisor = width
            if (width % 5 == 0) {
                roundDivisor = width
                break
            }
        }
        width++
    }
    val pick = if (roundDivisor > 0) roundDivisor else firstDivisor
    if (pick <= 0) return 0
    return span / pick - 1
}

/**
 * One slider row: title, optional description (the DETAIL the user asked to
 * keep off the Settings list and INSIDE this page), current value on the
 * right, slider beneath. Sliding commits immediately to local state; the
 * page-level Save persists.
 */
@Composable
internal fun LimitsSliderRow(
    title: String,
    subtitle: String? = null,
    valueLabel: String? = null,
    value: Int,
    min: Int,
    max: Int,
    onCommit: (Int) -> Unit,
    showDivider: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp,
                    )
                }
            }
            Text(
                text = valueLabel ?: value.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onCommit(it.toInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = remember(min, max) { sliderStepsFor(min, max) },
        )
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}
