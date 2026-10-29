package com.rikkaminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rikkaminis.app.R
import com.rikkaminis.app.scheduled.ScheduledTask
import com.rikkaminis.app.scheduled.ScheduledTaskPolicy
import com.rikkaminis.app.scheduled.ScheduledTaskRevisionPolicy
import com.rikkaminis.app.scheduled.ScheduledTaskTransitions
import com.rikkaminis.app.scheduled.ScheduledTasksCodec
import com.rikkaminis.app.scheduled.ScheduledTasksStore
import com.rikkaminis.app.ui.components.MinisButton
import com.rikkaminis.app.ui.components.MinisTextButton
import com.rikkaminis.app.ui.components.RowLabel
import com.rikkaminis.app.ui.components.SectionTextField
import kotlinx.coroutines.launch

/**
 * [feat/scheduled-tasks-l0] Create/edit one scheduled task (brief §4).
 *
 * Editing screens keep explicit Cancel/Save (AddCustomModelScreen pattern):
 * `onBack` is suppressed on the scaffold and a Cancel text action sits in the
 * navigation slot; Save validates the window (strict HH:MM, start < end) via
 * [ScheduledTaskPolicy.parseWindowMinuteOfDay] and refuses blank prompts.
 *
 * Deleting an existing task is destructive → AlertDialog confirm
 * (BackupSettingsScreen pattern), never a bare swipe.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTaskEditScreen(
    taskId: String?,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isNew = taskId == null

    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var windowStart by remember { mutableStateOf("07:00") }
    var windowEnd by remember { mutableStateOf("23:00") }
    var maxTurns by remember { mutableStateOf(ScheduledTask.DEFAULT_MAX_TURNS.toString()) }
    var deadlineSec by remember { mutableStateOf(ScheduledTask.DEFAULT_DEADLINE_SEC.toString()) }
    var maxEstimatedTokens by remember { mutableStateOf(ScheduledTask.DEFAULT_MAX_TOKENS.toString()) }
    // Run-state fields (lastRunDate / lastResult / failStreak / enabled /
    // suspendedByFuse) are preserved on edit: an edit must never re-arm the
    // same-day gate or silently clear a tripped fuse (resume stays manual).
    var original by remember { mutableStateOf<ScheduledTask?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // [da1034-B2 / B-04] The edit target can already be gone when this screen
    // composes (deleted from the list, or by a concurrent deleteTask on another
    // surface). Without an explicit not-found state the form kept its defaults
    // with Save locked for good (`original == null`) while Delete stayed live
    // on an id that no longer exists.
    var recordMissing by remember { mutableStateOf(false) }
    // [R5-B2 F-04] The delete confirm's result: a refused write (disk full, or
    // the store's unreadable guard) hands back the PRE-delete document, so the
    // task is still there — closing the screen as if it were gone is the
    // "silent close" the report pinned.
    var deleteRefused by remember { mutableStateOf(false) }
    // [deep-audit-1046 S-11] "This screen has finished its initial read." The
    // change watcher below must not speak before it: a refresh that fires while
    // the load effect is still in flight would judge the form against a document
    // it has not loaded from yet.
    var loadedOnce by remember { mutableStateOf(false) }
    // The store's change ticker (bumped after every successful write).
    val storeRevision by ScheduledTasksStore.revision.collectAsState()

    if (!isNew) {
        LaunchedEffect(taskId) {
            val file = ScheduledTasksStore.load(context)
            val loaded = file.tasks.firstOrNull { it.id == taskId }
            if (loaded == null) {
                // [da1034-B2 / B-04] Not-found branch: no record to load, so the
                // screen must say so instead of staying on the inert form.
                recordMissing = true
            } else {
                title = loaded.title
                prompt = loaded.prompt
                windowStart = loaded.windowStart
                windowEnd = loaded.windowEnd
                maxTurns = loaded.maxTurns.toString()
                deadlineSec = loaded.deadlineSec.toString()
                maxEstimatedTokens = loaded.maxEstimatedTokens.toString()
                original = loaded
                recordMissing = false
            }
            loadedOnce = true
        }

        // [deep-audit-1046 S-11] Second half of the load: follow the store.
        // The screen used to read once, so a task deleted (or replaced away by a
        // backup restore) while the form was open stayed editable, and Save
        // appended it straight back — a deleted record resurrected together with
        // the stale run state the form had snapshotted.
        //
        // Deliberately NOT a form refresh: when the target still exists only its
        // run half may move (`original`). The spec fields belong to the text
        // state above — a background run settling mid-typing must never rewrite
        // the title under the user's cursor.
        LaunchedEffect(taskId, storeRevision) {
            if (!loadedOnce) return@LaunchedEffect
            val fresh = ScheduledTasksStore.load(context).tasks.firstOrNull { it.id == taskId }
            if (ScheduledTaskRevisionPolicy.shouldMarkMissing(loadedOnce, stillExists = fresh != null)) {
                recordMissing = true
            } else if (fresh != null) {
                original = original?.let { ScheduledTaskTransitions.refreshRunState(it, fresh) }
                recordMissing = false
            }
        }
    }

    // [da1034-B2 / B-04] Explicit not-found rendering. The sibling detail
    // screens (ProviderDetailScreen / ModelGroupDetailScreen / MountDetailScreen)
    // silently call onBack() when their record is gone, which reads as a dropped
    // tap; here we name the cause and leave exactly one way out — no Save, no
    // Delete (both would either write over nothing or delete a dead id).
    if (recordMissing) {
        SettingsScaffold(
            title = stringResource(R.string.scheduled_edit_title),
            onBack = null, // single explicit action in the navigation slot
            navigation = {
                MinisTextButton(onClick = onDone) {
                    Text(stringResource(R.string.common_back))
                }
            },
        ) {
            SettingsSection(header = stringResource(R.string.scheduled_section_identity)) {
                SettingsCardBlock {
                    Text(
                        text = stringResource(R.string.scheduled_edit_missing_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        return
    }

    // Strict HH:MM both halves, start < end (half-open windows, no midnight
    // spanning in L0 — policy rejects start >= end with INVALID_WINDOW).
    val startOk = ScheduledTaskPolicy.parseWindowMinuteOfDay(windowStart) != null
    val endOk = ScheduledTaskPolicy.parseWindowMinuteOfDay(windowEnd) != null
    val windowOk = startOk && endOk &&
        ScheduledTaskPolicy.parseWindowMinuteOfDay(windowStart)!! <
        ScheduledTaskPolicy.parseWindowMinuteOfDay(windowEnd)!!
    // [da1034-B2 / B-04] Both halves parse but the window is inverted
    // (`start >= end`): the old `isError = !startOk` left BOTH fields unmarked
    // while `windowOk == false` silently kept Save disabled. Flag the start
    // field; the section footer already spells out the "start before end" rule,
    // so no extra error line is needed.
    val windowInverted = startOk && endOk && !windowOk
    val turnsValue = maxTurns.toIntOrNull()
    val deadlineValue = deadlineSec.toIntOrNull()
    val tokensValue = maxEstimatedTokens.toLongOrNull()
    val turnsOk = turnsValue != null && turnsValue > 0
    val deadlineOk = deadlineValue != null && deadlineValue > 0
    val tokensOk = tokensValue != null && tokensValue > 0
    // Save is gated on the stored record being loaded first for an EDIT. The
    // form opens with defaults (07:00-23:00, empty title) while
    // `LaunchedEffect(taskId)` loads the real task; saving in that window would
    // write those defaults over the stored spec (the store merges run state now,
    // but the spec fields would still come from the untouched form). A brand-new
    // task has no record to wait for, so `isNew` keeps it savable.
    // [da1034-B2 / B-04] `!recordMissing` is the belt to `original != null`'s
    // braces: the not-found screen returns before this button is composed, but
    // the enabled flag must never depend on that rendering path staying put.
    val canSave = !recordMissing && prompt.isNotBlank() && windowOk && turnsOk && deadlineOk &&
        tokensOk && (isNew || original != null)

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.scheduled_delete_title)) },
            text = { Text(stringResource(R.string.scheduled_delete_body, title.ifBlank { stringResource(R.string.scheduled_default_task_title) })) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        val after = ScheduledTasksStore.deleteTask(context, taskId ?: return@launch)
                        // [R5-B2 F-04] The store returns the document that is
                        // actually on disk. `deleteTask` now leaves the runner's
                        // single-flight state alone when the write did not land,
                        // so the screen must not pretend it did either: stay
                        // here and say so instead of popping to a list where
                        // the task is still visible.
                        if (after.tasks.any { it.id == taskId }) {
                            deleteRefused = true
                            return@launch
                        }
                        onDone()
                    }
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    SettingsScaffold(
        title = stringResource(
            if (isNew) R.string.scheduled_new_title else R.string.scheduled_edit_title,
        ),
        onBack = null, // edit screen: explicit Cancel/Save pair instead (§#11)
        navigation = {
            MinisTextButton(onClick = onDone) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    ) {
        SettingsSection(
            header = stringResource(R.string.scheduled_section_identity),
            footer = stringResource(R.string.scheduled_identity_footer),
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.scheduled_field_title))
                SectionTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                RowLabel(text = stringResource(R.string.scheduled_field_prompt))
                SectionTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    singleLine = false,
                    isError = prompt.isBlank(),
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.scheduled_section_window),
            footer = stringResource(R.string.scheduled_window_footer),
        ) {
            SettingsCardBlock {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        RowLabel(text = stringResource(R.string.scheduled_field_window_start))
                        SectionTextField(
                            value = windowStart,
                            onValueChange = { windowStart = it },
                            isError = !startOk || windowInverted,
                            singleLine = true,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        RowLabel(text = stringResource(R.string.scheduled_field_window_end))
                        SectionTextField(
                            value = windowEnd,
                            onValueChange = { windowEnd = it },
                            isError = !endOk,
                            singleLine = true,
                        )
                    }
                }
            }
        }

        SettingsSection(
            header = stringResource(R.string.scheduled_section_budget),
            footer = stringResource(R.string.scheduled_budget_footer),
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.scheduled_field_max_turns))
                SectionTextField(
                    value = maxTurns,
                    onValueChange = { maxTurns = it.filter(Char::isDigit) },
                    isError = !turnsOk,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(12.dp))
                RowLabel(text = stringResource(R.string.scheduled_field_deadline))
                SectionTextField(
                    value = deadlineSec,
                    onValueChange = { deadlineSec = it.filter(Char::isDigit) },
                    isError = !deadlineOk,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(12.dp))
                RowLabel(text = stringResource(R.string.scheduled_field_max_tokens))
                SectionTextField(
                    value = maxEstimatedTokens,
                    onValueChange = { maxEstimatedTokens = it.filter(Char::isDigit) },
                    isError = !tokensOk,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        MinisButton(
            onClick = {
                val prior = original
                val effectiveId = taskId ?: ScheduledTasksCodec.newTaskId()
                scope.launch {
                    val saved = ScheduledTasksStore.upsertTask(
                        context,
                        ScheduledTask(
                            id = effectiveId,
                            title = title.trim(),
                            prompt = prompt.trim(),
                            windowStart = windowStart.trim(),
                            windowEnd = windowEnd.trim(),
                            // §5.4: brand-new tasks enter OFF. Editing keeps the
                            // task's current enabled state (the row switch is
                            // the explicit control for that).
                            enabled = prior?.enabled ?: false,
                            maxTurns = turnsValue ?: ScheduledTask.DEFAULT_MAX_TURNS,
                            deadlineSec = deadlineValue ?: ScheduledTask.DEFAULT_DEADLINE_SEC,
                            maxEstimatedTokens = tokensValue ?: ScheduledTask.DEFAULT_MAX_TOKENS,
                            lastRunDate = prior?.lastRunDate,
                            lastResult = prior?.lastResult,
                            failStreak = prior?.failStreak ?: 0,
                            suspendedByFuse = prior?.suspendedByFuse ?: false,
                        ),
                        // [deep-audit-1046 S-11] Only a brand-new task may insert.
                        // An edit whose target vanished must not append it back:
                        // that resurrected a deleted record carrying the form's
                        // stale run state (`enabled`, `lastRunDate`, a cleared
                        // fuse). The store refuses, writes nothing, and the
                        // screen says the record is gone.
                        allowInsert = isNew,
                    )
                    if (
                        ScheduledTaskRevisionPolicy.shouldMarkMissing(
                            loadedOnce,
                            stillExists = saved.tasks.any { it.id == effectiveId },
                        )
                    ) {
                        recordMissing = true
                    } else {
                        onDone()
                    }
                }
            },
            enabled = canSave,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.common_save))
        }

        // Destructive delete: bottom of the editor, mirroring
        // ProviderDetailScreen's pattern (error container color + confirm
        // dialog, never a bare swipe). Hidden for brand-new tasks — there
        // is nothing to delete yet.
        if (!isNew) {
            // [R5-B2 F-04] A refused delete stays on this screen and says why,
            // instead of popping back as if the task were gone. Reuses the
            // existing write-failure wording (the store could not persist the
            // deletion) — a dedicated string would be a 7-locale change, which
            // this branch's file set does not carry.
            if (deleteRefused) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.backup_err_write,
                        ScheduledTasksStore.fileFor(context).name,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(12.dp))
            MinisButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.common_delete))
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
