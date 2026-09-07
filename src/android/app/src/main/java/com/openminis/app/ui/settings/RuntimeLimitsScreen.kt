package com.openminis.app.ui.settings

import com.openminis.app.R

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
import com.openminis.app.data.AgentRuntimeLimitsPrefs
import com.openminis.app.data.ConcurrencyPrefs
import com.openminis.app.data.SubagentPrefs
import com.openminis.app.ui.components.MinisTextButton

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
        initialValue = com.openminis.app.service.SessionConcurrencyManager.occupancy(),
    ) {
        while (true) {
            value = com.openminis.app.service.SessionConcurrencyManager.occupancy()
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

// ── local building blocks (mirror SettingsScreen's private ones) ─────────

@Composable
private fun LimitsSectionCard(title: String, content: @Composable () -> Unit) {
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
private fun LimitsSectionFooter(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        lineHeight = 16.sp,
    )
}

@Composable
private fun LimitsSwitchRow(
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

/**
 * One slider row: title, optional description (the DETAIL the user asked to
 * keep off the Settings list and INSIDE this page), current value on the
 * right, slider beneath. Sliding commits immediately to local state; the
 * page-level Save persists.
 */
@Composable
private fun LimitsSliderRow(
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
            steps = (max - min - 1).coerceAtLeast(0),
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
