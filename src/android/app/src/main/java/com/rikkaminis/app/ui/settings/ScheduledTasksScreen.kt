package com.rikkaminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.rikkaminis.app.R
import com.rikkaminis.app.scheduled.ScheduledTask
import com.rikkaminis.app.scheduled.ScheduledTaskPolicy
import com.rikkaminis.app.scheduled.ScheduledTasksFile
import com.rikkaminis.app.scheduled.ScheduledTasksStore
import com.rikkaminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.launch

/**
 * [feat/scheduled-tasks-l0] Scheduled tasks list — Settings top-level sub-page
 * (brief §4). Factory-off everywhere (§5.4): the global master switch starts
 * disabled and every task starts disabled, so nothing fires until the user
 * flips both.
 *
 * Per README's settings conventions, a top-level sub-page passes
 * `onBack = null` (no redundant top-bar arrow — system back gesture / bottom
 * nav handle returning); only the editor keeps explicit Cancel/Save.
 *
 * Row refresh follows the MountedFoldersScreen pattern: re-load on every
 * ON_RESUME (returning from the editor must show its saved state).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTasksScreen(
    onEditTask: (String) -> Unit,
    onNewTask: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var reloadKey by remember { mutableIntStateOf(0) }
    // [deep-audit-1046 S-11] The store publishes a change ticker on every
    // successful atomic write. Without it this list was frozen at whatever
    // document it loaded first: a background run settling (lastRunDate /
    // lastResult / failStreak) and a fuse trip (suspendedByFuse → the Resume
    // button) both stayed invisible until the screen was left and re-entered.
    // reloadKey stays: it forces a re-read for this screen's OWN actions, whose
    // revision bump lands one frame later than the click's feedback.
    val storeRevision by ScheduledTasksStore.revision.collectAsState()
    val file by produceState<ScheduledTasksFile?>(null, context, reloadKey, storeRevision) {
        value = ScheduledTasksStore.load(context)
    }
    // Re-read on resume: mirrors MountedFoldersScreen — returning from the
    // editor or from the app switcher must reflect persisted state.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reloadKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsScaffold(
        title = stringResource(R.string.scheduled_tasks_title),
        onBack = null, // top-level sub-page — system back handles it (README)
        actions = {
            MinisTextButton(onClick = onNewTask) {
                Text(stringResource(R.string.scheduled_new_task))
            }
        },
    ) {
        SettingsSection(
            header = stringResource(R.string.settings_section_agent_runtime),
            footer = stringResource(R.string.scheduled_global_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.scheduled_global_switch),
                subtitle = stringResource(R.string.scheduled_global_switch_subtitle),
                checked = file?.globalEnabled == true,
                onCheckedChange = { on ->
                    scope.launch {
                        ScheduledTasksStore.setGlobalEnabled(context, on)
                        reloadKey++
                    }
                },
            )
        }

        SettingsSection(
            header = stringResource(R.string.scheduled_list_header),
            footer = stringResource(R.string.scheduled_list_footer),
        ) {
            val tasks = file?.tasks.orEmpty()
            if (tasks.isEmpty()) {
                SettingsCardBlock {
                    Text(
                        text = stringResource(R.string.scheduled_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                tasks.forEachIndexed { index, task ->
                    ScheduledTaskRow(
                        task = task,
                        onClick = { onEditTask(task.id) },
                        onToggle = { on ->
                            scope.launch {
                                ScheduledTasksStore.setTaskEnabled(context, task.id, on)
                                reloadKey++
                            }
                        },
                        onResume = {
                            scope.launch {
                                ScheduledTasksStore.resumeTask(context, task.id)
                                reloadKey++
                            }
                        },
                        showDivider = index < tasks.lastIndex,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                MinisTextButton(onClick = onNewTask) {
                    Text(stringResource(R.string.scheduled_new_task))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun scheduledTaskTitle(task: ScheduledTask): String =
    task.title.ifBlank { stringResource(R.string.scheduled_default_task_title) }

@Composable
private fun taskSubtitle(task: ScheduledTask): String = buildString {
    append(task.windowStart).append("–").append(task.windowEnd)
    append(" · ")
    when {
        task.suspendedByFuse ->
            append(
                stringResource(
                    R.string.scheduled_state_suspended,
                    ScheduledTaskPolicy.FUSE_THRESHOLD,
                ),
            )
        task.lastResult == ScheduledTask.RESULT_OK ->
            append(stringResource(R.string.scheduled_state_ok, task.lastRunDate ?: ""))
        task.lastResult != null ->
            append(stringResource(R.string.scheduled_state_failed, task.lastResult ?: ""))
        else -> append(stringResource(R.string.scheduled_state_never_ran))
    }
}

@Composable
private fun ScheduledTaskRow(
    task: ScheduledTask,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onResume: () -> Unit,
    showDivider: Boolean,
) {
    SettingsRow(
        title = scheduledTaskTitle(task),
        subtitle = taskSubtitle(task),
        icon = Icons.Outlined.Schedule,
        onClick = onClick,
        showDivider = showDivider,
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                Switch(
                    checked = task.enabled,
                    onCheckedChange = onToggle,
                )
                if (task.suspendedByFuse) {
                    MinisTextButton(onClick = onResume) {
                        Text(stringResource(R.string.scheduled_resume))
                    }
                }
            }
        },
    )
}
