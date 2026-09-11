package com.rikkaminis.app.ui.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rikkaminis.app.MinisApp
import com.rikkaminis.app.R
import com.rikkaminis.app.ui.components.sanitizeSingleLineInput
import com.rikkaminis.app.backup.ConfigBackup
import com.rikkaminis.app.backup.WebDavBackupItem
import com.rikkaminis.app.backup.WebDavClient
import com.rikkaminis.app.backup.WebDavConfig
import com.rikkaminis.app.backup.WebDavConfigStore
import com.rikkaminis.app.backup.WebDavException
import com.rikkaminis.app.backup.WebDavSync
import com.rikkaminis.app.data.repository.EnvVarRepository
import com.rikkaminis.app.data.repository.MCPRepository
import com.rikkaminis.app.data.repository.MemoryRepository
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.data.repository.SkillRepository
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Local backup / restore of app configuration — providers, appearance, and the
 * agent-runtime defaults — plus WebDAV remote backup (upload / list / restore /
 * delete against any WebDAV server: Nextcloud, 坚果云, Synology, …).
 *
 * Chat history is included, but deliberately kept LIGHT: only the last
 * `chatWindowDays` of activity, text-only message parts (media/attachments are
 * dropped), capped per session. This keeps "restore my setup" from becoming an
 * unpredictably heavy operation while still carrying conversations across
 * devices. The window is user-adjustable; 0 disables chat history entirely.
 */
@Composable
fun BackupSettingsScreen(
    providerRepository: ProviderRepository,
    envVarRepository: EnvVarRepository? = null,
    skillRepository: SkillRepository? = null,
    memoryRepository: MemoryRepository? = null,
    mcpRepository: MCPRepository? = null,
    chatRepository: com.rikkaminis.app.data.repository.ChatRepository? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val chatPrefs = remember {
        context.getSharedPreferences("backup_prefs", android.content.Context.MODE_PRIVATE)
    }
    var chatWindowDays by remember {
        mutableStateOf(chatPrefs.getInt("chat_window_days", 90))
    }
    var showWindowDialog by remember { mutableStateOf(false) }
    // [T-backend-export] Local SAF export is picker-first: the launcher
    // callback runs AFTER the user picks a file, so it must remember which
    // confirmation (with/without secrets) triggered this picker instance.
    var exportWithSecrets by remember { mutableStateOf(false) }
    var showSecretWarning by remember { mutableStateOf(false) }
    var importReport by remember { mutableStateOf<ConfigBackup.ImportResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // ---- WebDAV remote backup state ----
    val webDavStore = remember { WebDavConfigStore(context) }
    val webDavHttpClient = remember { WebDavClient.defaultClient() }
    val scope = rememberCoroutineScope()
    // [T-auto-backup-assets] Artifact roots: the agent's *outputs* that
    // manual export, WebDAV upload, pre-restore snapshots and the daily
    // auto-backup all carry — the shared folder (handoff docs / reports /
    // tools / knowledge-graph exports) plus MCP server data files. Chat
    // transcripts stay out (process byproduct, not product) — they already
    // have their own windowed section when chatRepo is passed.
    val artifactRoots = remember {
        listOf(
            File(context.filesDir, "minis-global/shared"),
            File(context.filesDir, "minis-global/mcp-servers"),
        )
    }
    // Transfers that MUST complete even if the user leaves this screen
    // (WebDAV backup upload / restore) run on the app-scoped scope instead,
    // so navigating away cannot cancel them mid-flight. Completion is
    // reported via a system notification if the screen is gone by then.
    val application = remember { context.applicationContext as MinisApp }
    val notifier = remember { application.backgroundTaskNotifier }
    var webDavConfig by remember { mutableStateOf(webDavStore.load()) }
    // [T-auto-backup-assets] Persist a restored WebDAV server config and
    // refresh Compose state. Declared AFTER webDavConfig so the lambda can
    // reference it (local-variable ordering).
    val applyWebDavConfig: (WebDavConfig) -> Unit = { cfg ->
        runCatching { webDavStore.save(cfg) }
        webDavConfig = cfg
    }
    var showWebDavConfig by remember { mutableStateOf(false) }
    var showRemoteList by remember { mutableStateOf(false) }
    // [refactor-backup-gate] SINGLE mutual-exclusion flag for ALL backup paths
    // (local SAF export, WebDAV upload, WebDAV restore, pre-restore snapshot
    // export, WebDAV delete). Previously backupBusy + webDavBusy were two
    // independent bools that every button had to check (`!backupBusy &&
    // !webDavBusy`) — a missed check on one entry (the local restore button)
    // let two full 70MB+ payloads build concurrently and OOM. Consolidating to
    // ONE gate removes the entire class of "forgot to gate this entry" bugs:
    // there is now exactly one flag everyone reads and sets.
    var operationBusy by remember { mutableStateOf(false) }
    // When true, the next secret-warning confirmation uploads to WebDAV
    // instead of launching the SAF file picker.
    var webDavUploadPending by remember { mutableStateOf(false) }
    var remoteItems by remember { mutableStateOf<List<WebDavBackupItem>>(emptyList()) }
    var remoteLoading by remember { mutableStateOf(false) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var deletePending by remember { mutableStateOf<WebDavBackupItem?>(null) }
    var snapshotNote by remember { mutableStateOf<String?>(null) }
    // [T-auto-backup-assets] Daily asset backup state (toggle + last run +
    // local copies). The auto payload deliberately excludes chat transcripts
    // — see AutoBackupManager class doc. Local auto copies restore through
    // the same snapshotRestoreTarget dialog + restoreWithSnapshot path as
    // pre-restore snapshots (both are full JSON payloads with rollback).
    var autoBackupEnabled by remember {
        mutableStateOf(com.rikkaminis.app.backup.AutoBackupManager.isEnabled(context))
    }
    var autoBackupRunning by remember { mutableStateOf(false) }
    var autoBackupFiles by remember { mutableStateOf(com.rikkaminis.app.backup.AutoBackupManager.listLocal(context)) }
    var autoBackupLastRun by remember {
        mutableStateOf(com.rikkaminis.app.backup.AutoBackupManager.lastRunLabel(context))
    }
    // [T-auto-backup-remote-dir] Remote automatic-backup management state:
    // the WebDAV `auto/` subdirectory listing (plus legacy root stragglers),
    // managed from a dedicated dialog inside the auto-backup section so the
    // two backup kinds never mix in one list.
    var autoRemoteOpen by remember { mutableStateOf(false) }
    var autoRemoteItems by remember {
        mutableStateOf<List<WebDavSync.AutoBackupEntry>>(emptyList())
    }
    var autoRemoteLoading by remember { mutableStateOf(false) }
    var autoRemoteError by remember { mutableStateOf<String?>(null) }
    var autoDeletePending by remember { mutableStateOf<WebDavSync.AutoBackupEntry?>(null) }
    // [fix-audit-p0-2] Local pre-restore snapshots, newest first. They used to
    // be written with no UI to list or restore them — a promise of rollback
    // with no way to roll back. Now listed here and restorable via the same
    // restoreWithSnapshot path used for WebDAV.
    val snapshotDir = remember { File(context.filesDir, "backup-snapshots") }
    var snapshotFiles by remember { mutableStateOf(ConfigBackup.listSnapshots(snapshotDir)) }
    var snapshotRestoreTarget by remember { mutableStateOf<File?>(null) }

    // Refresh the snapshot list on entry (and after restore writes a new one).
    LaunchedEffect(Unit) {
        snapshotFiles = withContext(Dispatchers.IO) { ConfigBackup.listSnapshots(snapshotDir) }
    }

    val refreshSnapshots: () -> Unit = {
        snapshotFiles = ConfigBackup.listSnapshots(snapshotDir)
    }

    val savedToast = stringResource(R.string.backup_saved)
    val errWriteFmt = stringResource(R.string.backup_err_write)
    val errRead = stringResource(R.string.backup_err_read)
    val errImport = stringResource(R.string.backup_err_import)
    val errUnknown = stringResource(R.string.backup_err_unknown)

    // Restore-with-safety-net: before applying an imported config (local file
    // or WebDAV) snapshot the CURRENT config so the user can always roll back
    // after a mistaken restore. Best-effort: a failed snapshot never blocks
    // the restore, it only reports via snapshotNote.
    // Declared before the launchers below because their callbacks invoke it.
    val restoreWithSnapshot: (String) -> Unit = doRestore@{ json ->
        // [fix restore-mutex] Reject a restore while any backup/restore path is
        // already in flight. The pre-restore snapshot export builds another full
        // 70MB+ payload; two such exports concurrently (e.g. restoring while a
        // local backup upload is running) stacks two payloads and blows the
        // 512MB heap. This is the last line of defense behind the button-level
        // guards — the snapshot-restore dialog and WebDAV sheet reach here too.
        if (operationBusy) {
            errorMessage = context.getString(R.string.backup_err_busy)
            return@doRestore
        }
        snapshotNote = null
        operationBusy = true
        // Restore must complete even if the user navigates away; run on the
        // app scope and fall back to a tray notification once done.
        // [fix-audit-p1-1] All Compose state writes are hopped back to Main
        // explicitly — the old code wrote snapshotNote / importReport /
        // errorMessage / operationBusy straight from Dispatchers.IO (the
        // applicationScope dispatcher), racing recomposition. The export
        // path in this same file already did withContext(Dispatchers.Main);
        // this restores the same discipline here.
        application.applicationScope.launch {
            try {
                // [T-backup-streaming-export] Stream the pre-restore snapshot
                // straight to disk — the in-memory String variant OOMs on a
                // heavy install (see exportToWriter). writeSnapshotStreaming
                // keeps the same naming / pruning contract as writeSnapshot.
                // Keep the safety snapshot LOCAL only. Uploading it to the
                // WebDAV server on every restore made it look like the restore
                // was actually performing a backup, and -- worse -- it created a
                // real backup file on the server (same rikkaminis-backup-*
                // naming) that pollutes the remote list. The snapshot's only
                // job is rolling back THIS device if the restore goes wrong, so
                // it belongs in local storage, never on the remote.
                // [fix-audit-p0-3] Second-precision name + keep SNAPSHOT_KEEP
                // newest (see ConfigBackup.writeSnapshot) so two restores in
                // the same minute no longer overwrite the first rollback point.
                val dir = File(context.filesDir, "backup-snapshots")
                ConfigBackup.writeSnapshotStreaming(dir) { w ->
                    ConfigBackup.exportToWriter(
                        providerRepo = providerRepository,
                        includeSecrets = true,
                        envVarRepo = envVarRepository,
                        skillRepo = skillRepository,
                        memoryRepo = memoryRepository,
                        mcpRepo = mcpRepository,
                        chatRepo = chatRepository,
                        chatWindowDays = chatWindowDays,
                        artifactRoots = artifactRoots,
                        webDavConfig = webDavConfig,
                        writer = w,
                    )
                }
                withContext(Dispatchers.Main) {
                    snapshotNote = context.getString(R.string.backup_snapshot_local)
                    refreshSnapshots()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    snapshotNote = context.getString(R.string.backup_snapshot_failed)
                }
            }
            var restoredOk = true
            var restoredMsg: String? = null
            try {
                val report = withContext(Dispatchers.Default) {
                    ConfigBackup.import(
                        providerRepo = providerRepository,
                        json = json,
                        envVarRepo = envVarRepository,
                        skillRepo = skillRepository,
                        memoryRepo = memoryRepository,
                        mcpRepo = mcpRepository,
                        chatRepo = chatRepository,
                        artifactRoots = artifactRoots,
                        onWebDavConfig = applyWebDavConfig,
                    )
                }
                withContext(Dispatchers.Main) {
                    importReport = report
                    restoredMsg = context.getString(
                        R.string.backup_restored_notify_body,
                        report?.providersImported ?: 0,
                        report?.groupsImported ?: 0,
                    )
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    errorMessage = t.message ?: errImport
                    restoredOk = false
                    restoredMsg = t.message ?: errImport
                }
            } finally {
                withContext(Dispatchers.Main) { operationBusy = false }
            }
            // Always notify completion from the tray; harmless if the screen is
            // still foregrounded (Toast + inline report already covered it).
            notifier.notifyWorkCompleted(
                tag = "webdav-restore",
                title = context.getString(
                    if (restoredOk) R.string.webdav_notify_title_restored
                    else R.string.webdav_notify_title_failed,
                ),
                body = restoredMsg ?: "",
            )
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        // [T-backend-export] Local SAF export is now picker-first: the file
        // picker opens the moment the user confirms, and the payload is built
        // only after they pick a location — so the picker never waits on the
        // (multi-second) build, and the user can leave the screen while the
        // build+write finishes. Completion/failure lands in the tray as a
        // notification (the user may be off this screen by then).
        if (uri == null) {
            // User cancelled the picker — nothing was built, release the gate.
            operationBusy = false
            return@rememberLauncherForActivityResult
        }
        application.applicationScope.launch {
            try {
                // [T-backup-streaming-export] Stream the document straight
                // into the picked file: on a heavy install the in-memory
                // String variant OOMs at the final toString (payload×2 UTF-16
                // on a 512MB heap). readFailures comes back from exportToWriter.
                var readFailures = 0
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        java.io.OutputStreamWriter(out, Charsets.UTF_8).use { w ->
                            readFailures = ConfigBackup.exportToWriter(
                                providerRepo = providerRepository,
                                includeSecrets = exportWithSecrets,
                                envVarRepo = envVarRepository,
                                skillRepo = skillRepository,
                                memoryRepo = memoryRepository,
                                mcpRepo = mcpRepository,
                                chatRepo = chatRepository,
                                chatWindowDays = chatWindowDays,
                                artifactRoots = artifactRoots,
                                webDavConfig = webDavConfig,
                                writer = w,
                            )
                        }
                    } ?: throw IllegalStateException("no output stream")
                }
                withContext(Dispatchers.Main) {
                    // [T-backup-readfailures] exportToWriter counts fields that
                    // failed to serialize (readFailures); surface it so the
                    // user knows the backup may be incomplete.
                    val failures = readFailures
                    val body = buildString {
                        append(uri.lastPathSegment ?: savedToast)
                        if (failures > 0) {
                            append("\n")
                            append(context.getString(R.string.backup_export_incomplete, failures))
                        }
                    }
                    Toast.makeText(context, savedToast, Toast.LENGTH_SHORT).show()
                    notifier.notifyWorkCompleted(
                        tag = "local-export",
                        title = context.getString(R.string.backup_saved),
                        body = body,
                    )
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    val msg = String.format(errWriteFmt, t.message ?: errUnknown)
                    errorMessage = msg
                    notifier.notifyWorkCompleted(
                        tag = "local-export",
                        title = context.getString(R.string.backup_notify_failed),
                        body = msg,
                    )
                }
                // SAF created an empty file at pick time; best-effort cleanup
                // so a failed export doesn't litter Documents with 0-byte files.
                runCatching { context.contentResolver.delete(uri, null, null) }
            } finally {
                withContext(Dispatchers.Main) { operationBusy = false }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // [audit-0909] SAF pick callback runs on the Main thread; the file
        // read below can be tens of MB (auto-backup files embed the artifact
        // zip), so hop the read to IO — same fix shape as the snapshot-restore
        // dialog (568d87a). The query for byte size is a cheap metadata call
        // and stays inline; errorMessage is Compose state, so it is written
        // back on this Main-dispatcher scope.
        scope.launch {
            try {
                // [T-backup-import-memory] Reject oversized files BEFORE pulling
                // them into memory. The exact char-count check (MAX_PAYLOAD_BYTES)
                // inside ConfigBackup.import only runs after the whole file has
                // been read into a String (UTF-16 doubles the allocation) and a
                // full pre-restore snapshot export has been built — an OOM window
                // for large backups. Byte size is a conservative bound (chars ≤
                // bytes for UTF-8), so a file that passes here still gets the
                // exact char check during import.
                val sizeBytes = context.contentResolver.query(
                    uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else null
                    } else null
                }
                if (sizeBytes != null &&
                    sizeBytes > com.rikkaminis.app.backup.ConfigBackup.MAX_PAYLOAD_BYTES
                ) {
                    val mb = sizeBytes / (1024 * 1024)
                    val maxMb = com.rikkaminis.app.backup.ConfigBackup.MAX_PAYLOAD_BYTES / (1024 * 1024)
                    throw IllegalStateException(
                        context.getString(R.string.backup_import_too_large, mb, maxMb),
                    )
                }
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.readText()
                        ?: throw IllegalStateException(errRead)
                }
                restoreWithSnapshot(json)
            } catch (t: Throwable) {
                errorMessage = t.message ?: errImport
            }
        }
    }

    // Fetch the remote backup list and open the management sheet.
    val openRemoteList: () -> Unit = openRemoteList@{
        val cfg = webDavConfig
        if (cfg == null) {
            errorMessage = context.getString(R.string.webdav_configure_first)
            return@openRemoteList
        }
        showRemoteList = true
        remoteLoading = true
        remoteError = null
        scope.launch {
            try {
                remoteItems = withContext(Dispatchers.IO) {
                    WebDavSync.listBackupFiles(cfg, webDavHttpClient)
                }
            } catch (t: Throwable) {
                remoteError = webDavErrorMessage(context, t)
            } finally {
                remoteLoading = false
            }
        }
    }

    // Fetch the remote automatic-backup list and open its management dialog.
    // Same guard pattern as openRemoteList: the auto folder lives under the
    // SAME configured WebDAV path as manual backups (`<path>/auto/`), so a
    // missing WebDAV config is reported the same way.
    val openAutoRemoteList: () -> Unit = openAutoRemoteList@{
        val cfg = webDavConfig
        if (cfg == null) {
            errorMessage = context.getString(R.string.webdav_configure_first)
            return@openAutoRemoteList
        }
        autoRemoteOpen = true
        autoRemoteLoading = true
        autoRemoteError = null
        scope.launch {
            try {
                autoRemoteItems = withContext(Dispatchers.IO) {
                    WebDavSync.listAutoBackupEntries(cfg, webDavHttpClient)
                }
            } catch (t: Throwable) {
                autoRemoteError = webDavErrorMessage(context, t)
            } finally {
                autoRemoteLoading = false
            }
        }
    }

    // top-level page: rely on system back gesture / bottom nav (no back arrow)
    SettingsScaffold(title = stringResource(R.string.settings_backup), onBack = null) {
        // [T-auto-backup-assets] Daily automatic asset backup — see
        // com.rikkaminis.app.backup.AutoBackupManager. This is the app's ONLY automatic backup: it
        // runs once per day at first foreground and covers capability
        // (config/providers/thinking rules/skills/env vars/MCP/memory) plus
        // outputs (shared/ artifacts + knowledge-graph data) but NOT chat
        // transcripts (process byproduct — a task-runner's chat stream is
        // mostly the agent working, and the distilled memory already keeps
        // what matters).
        SettingsSection(
            header = stringResource(R.string.auto_backup_section_title),
            footer = stringResource(R.string.auto_backup_section_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.auto_backup_enable),
                subtitle = stringResource(R.string.auto_backup_enable_sub),
                trailing = {
                    Switch(
                        checked = autoBackupEnabled,
                        onCheckedChange = { on ->
                            autoBackupEnabled = on
                            com.rikkaminis.app.backup.AutoBackupManager.setEnabled(context, on)
                        },
                    )
                },
                // No row-level onClick: the Switch is the only toggle, so a
                // tap can't double-fire (row ripple + switch) into a no-op.
                showDivider = true,
            )
            SettingsRow(
                title = stringResource(R.string.auto_backup_run_now),
                subtitle = run {
                    val lastRun = autoBackupLastRun
                    if (autoBackupRunning) stringResource(R.string.backup_progress_title)
                    else if (lastRun != null) {
                        stringResource(R.string.auto_backup_last_run, lastRun)
                    } else stringResource(R.string.auto_backup_never)
                },
                icon = Icons.Filled.Refresh,
                onClick = if (operationBusy || autoBackupRunning) null else ({
                    autoBackupRunning = true
                    operationBusy = true
                    application.applicationScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                com.rikkaminis.app.backup.AutoBackupManager.runNow(application)
                            }
                            withContext(Dispatchers.Main) {
                                autoBackupFiles = com.rikkaminis.app.backup.AutoBackupManager.listLocal(context)
                                autoBackupLastRun = com.rikkaminis.app.backup.AutoBackupManager.lastRunLabel(context)
                                android.widget.Toast.makeText(
                                    context, savedToast, android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        } catch (t: Throwable) {
                            withContext(Dispatchers.Main) {
                                errorMessage = t.message ?: errUnknown
                            }
                        } finally {
                            withContext(Dispatchers.Main) {
                                autoBackupRunning = false
                                operationBusy = false
                            }
                        }
                    }
                }),
                showDivider = false,
            )
            SettingsRow(
                title = stringResource(R.string.auto_backup_remote),
                subtitle = stringResource(R.string.auto_backup_remote_sub),
                icon = Icons.Filled.Cloud,
                onClick = if (operationBusy || autoBackupRunning) null else openAutoRemoteList,
                showDivider = true,
            )
            if (autoBackupFiles.isEmpty()) {
                Text(
                    stringResource(R.string.auto_backup_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else {
                Text(
                    stringResource(R.string.auto_backup_restore_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                autoBackupFiles.take(7).forEach { file ->
                    SettingsRow(
                        title = file.name.removePrefix(com.rikkaminis.app.backup.AutoBackupManager.LOCAL_FILE_PREFIX)
                            .removeSuffix(".json"),
                        subtitle = java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
                        ).format(java.util.Date(file.lastModified())),
                        icon = Icons.Filled.Restore,
                        onClick = { snapshotRestoreTarget = file },
                    )
                }
            }
        }
        SettingsSection(
            header = stringResource(R.string.backup_section_local),
            footer = stringResource(R.string.backup_section_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.backup_export),
                subtitle = stringResource(R.string.backup_export_sub),
                icon = Icons.Default.Download,
                onClick = if (operationBusy) null else ({ showSecretWarning = true }),
            )
            SettingsRow(
                title = stringResource(R.string.backup_chat_window_title),
                subtitle = stringResource(R.string.backup_chat_window_sub, chatWindowDays),
                icon = Icons.Outlined.History,
                onClick = { showWindowDialog = true },
            )
            SettingsRow(
                title = stringResource(R.string.backup_import),
                subtitle = stringResource(R.string.backup_import_sub),
                icon = Icons.Default.Upload,
                onClick = if (operationBusy) null else ({ importLauncher.launch(arrayOf("application/json", "*/*")) }),
                showDivider = false,
            )
        }
        // [fix-audit-p0-2] Local pre-restore snapshots with a rollback entry
        // point. Before this the snapshots were written but unreachable — the
        // UI promised "snapshot saved" yet nothing could restore from it.
        SettingsSection(
            header = stringResource(R.string.backup_snapshot_section_title),
            footer = stringResource(R.string.backup_snapshot_section_footer),
        ) {
            if (snapshotFiles.isEmpty()) {
                Text(
                    stringResource(R.string.backup_snapshot_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                snapshotFiles.forEachIndexed { index, file ->
                    SettingsRow(
                        title = file.name,
                        subtitle = java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
                        ).format(java.util.Date(file.lastModified())),
                        icon = Icons.Filled.Restore,
                        onClick = { snapshotRestoreTarget = file },
                        showDivider = index < snapshotFiles.size - 1,
                    )
                }
            }
        }
        SettingsSection(
            header = stringResource(R.string.webdav_section),
            footer = stringResource(R.string.webdav_section_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.webdav_server),
                subtitle = webDavConfig?.url
                    ?: stringResource(R.string.webdav_server_not_configured),
                icon = Icons.Filled.Cloud,
                onClick = { showWebDavConfig = true },
            )
            SettingsRow(
                title = stringResource(R.string.webdav_upload),
                subtitle = stringResource(R.string.webdav_upload_sub),
                icon = Icons.Filled.CloudUpload,
                onClick = if (webDavConfig != null && !operationBusy) {
                    { webDavUploadPending = true; showSecretWarning = true }
                } else {
                    null
                },
            )
            SettingsRow(
                title = stringResource(R.string.webdav_remote),
                subtitle = stringResource(R.string.webdav_remote_sub),
                icon = Icons.Outlined.CloudDownload,
                onClick = if (webDavConfig != null && !operationBusy) openRemoteList else null,
                showDivider = false,
            )
        }
    }

    // Credentials default to INCLUDED — a restore that drops every API key just
    // moves the work back onto the user. The tradeoff is that the file is
    // sensitive, so it gets an explicit confirmation rather than a silent write.
    // The same warning guards WebDAV uploads: the remote copy is as sensitive
    // as the local file. An export with keys goes through the same flow; only
    // the destination differs (SAF picker vs. WebDAV PUT).
    if (showSecretWarning) {
        val runExport: (Boolean) -> Unit = runExport@{ withSecrets ->
            // Claim the mutual-exclusion flag synchronously, BEFORE the async
            // launch below — otherwise a second tap between "confirm dialog
            // closes" and "coroutine starts" starts a second concurrent export
            // (two full payloads in memory → OOM). Every rebuild of the
            // payload (config/chat changed since last time) keeps the value;
            // the flag clears in the shared finally below.
            operationBusy = true
            showSecretWarning = false
            val toWebDav = webDavUploadPending
            webDavUploadPending = false
            if (toWebDav) {
                // WebDAV upload: already fully background — the payload is
                // built off-thread, the PUT runs on IO, and completion/failure
                // lands in the tray. The user can leave the screen immediately.
                application.applicationScope.launch {
                    try {
                        // [T-backup-streaming-export] Stream the document into
                        // a local temp file first, then PUT the file bytes —
                        // the in-memory String variant OOMs at the final
                        // toString on a heavy install (payload×2 UTF-16 on a
                        // 512MB heap). readFailures comes back directly.
                        val tempFile = java.io.File(
                            context.cacheDir,
                            "webdav-upload-${System.currentTimeMillis()}.json",
                        )
                        var readFailures = 0
                        try {
                            withContext(Dispatchers.IO) {
                                java.io.FileOutputStream(tempFile).use { fos ->
                                    java.io.OutputStreamWriter(fos, Charsets.UTF_8).use { w ->
                                        readFailures = ConfigBackup.exportToWriter(
                                            providerRepo = providerRepository,
                                            includeSecrets = withSecrets,
                                            envVarRepo = envVarRepository,
                                            skillRepo = skillRepository,
                                            memoryRepo = memoryRepository,
                                            mcpRepo = mcpRepository,
                                            chatRepo = chatRepository,
                                            chatWindowDays = chatWindowDays,
                                            artifactRoots = artifactRoots,
                                            webDavConfig = webDavConfig,
                                            writer = w,
                                        )
                                    }
                                }
                            }
                            // Resolve config on Main (it reads SharedPreferences and
                            // Compose state); bail with an inline error if unset.
                            val cfg = webDavConfig
                            if (cfg == null) {
                                errorMessage = context.getString(R.string.webdav_server_not_configured)
                                notifier.notifyWorkCompleted(
                                    tag = "webdav-upload",
                                    title = context.getString(R.string.webdav_notify_title_failed),
                                    body = context.getString(R.string.webdav_server_not_configured),
                                )
                            } else {
                                withContext(Dispatchers.IO) {
                                    WebDavSync.backup(
                                        config = cfg,
                                        payload = tempFile.readBytes(),
                                        client = webDavHttpClient,
                                    )
                                }
                                withContext(Dispatchers.Main) {
                                    // [T-backup-readfailures] exportToWriter counts
                                    // fields that failed to serialize into the
                                    // payload (readFailures); surface it so the
                                    // user knows the backup may be incomplete.
                                    val failures = readFailures
                                    val msg = if (failures > 0) {
                                        context.getString(R.string.webdav_uploaded) +
                                            "\n" +
                                            context.getString(R.string.backup_export_incomplete, failures)
                                    } else {
                                        context.getString(R.string.webdav_uploaded)
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    notifier.notifyWorkCompleted(
                                        tag = "webdav-upload",
                                        title = context.getString(R.string.webdav_notify_title),
                                        body = msg,
                                    )
                                }
                            }
                        } finally {
                            runCatching { tempFile.delete() }
                        }
                    } catch (t: Throwable) {
                        withContext(Dispatchers.Main) {
                            errorMessage = webDavErrorMessage(context, t)
                            notifier.notifyWorkCompleted(
                                tag = "webdav-upload",
                                title = context.getString(R.string.webdav_notify_title_failed),
                                body = webDavErrorMessage(context, t),
                            )
                        }
                    } finally {
                        withContext(Dispatchers.Main) { operationBusy = false }
                    }
                }
            } else {
                // Local SAF export: fire the picker immediately — only the
                // suggested filename is needed up front, the full payload is
                // built in the picker callback (after the user chooses a
                // location) so the picker never waits on generation. The
                // callback remembers this request's withSecrets.
                exportWithSecrets = withSecrets
                exportLauncher.launch(ConfigBackup.suggestedFileName())
            }
        }
        AlertDialog(
            onDismissRequest = { showSecretWarning = false },
            title = { Text(stringResource(R.string.backup_secret_title)) },
            text = { Text(stringResource(R.string.backup_secret_body)) },
            confirmButton = {
                TextButton(onClick = { runExport(true) }) {
                    Text(stringResource(R.string.backup_secret_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { runExport(false) }) {
                    Text(stringResource(R.string.backup_secret_without))
                }
            },
        )
    }

    // WebDAV server configuration sheet (URL / username / password / folder).
    if (showWebDavConfig) {
        WebDavConfigDialog(
            initial = webDavConfig,
            httpClient = webDavHttpClient,
            onDismiss = { showWebDavConfig = false },
            onSave = { cfg ->
                webDavStore.save(cfg)
                webDavConfig = webDavStore.load()
                showWebDavConfig = false
                Toast.makeText(
                    context,
                    context.getString(R.string.webdav_saved),
                    Toast.LENGTH_SHORT,
                ).show()
            },
        )
    }

    // Remote backup management sheet: list, restore, delete.
    if (showRemoteList) {
        webDavConfig?.let { cfg ->
            WebDavRemoteDialog(
                config = cfg,
                items = remoteItems,
                loading = remoteLoading,
                error = remoteError,
                busy = operationBusy,
                onDismiss = { showRemoteList = false },
                onRefresh = openRemoteList,
                onRestore = { item ->
                    // [refactor-backup-gate] Do NOT claim the global operationBusy
                    // here. The async download must show progress (sheet busy) but
                    // must not hold the single mutual-exclusion gate — restoreWith
                    // Snapshot() is the one-and-only claim entry for restores, so
                    // its own gate check (reject if a backup is already in flight)
                    // still works. Holding the gate across the network gap would
                    // make that check see "busy" and wrongly refuse this very
                    // restore (self-collision). The download re-enables the sheet
                    // via remoteLoading to prevent double-taps.
                    if (!operationBusy) {
                        remoteLoading = true
                        application.applicationScope.launch {
                            try {
                                val json = withContext(Dispatchers.IO) {
                                    WebDavSync.restore(cfg, item, webDavHttpClient)
                                }
                                showRemoteList = false
                                restoreWithSnapshot(json)
                            } catch (t: Throwable) {
                                errorMessage = webDavErrorMessage(context, t)
                                notifier.notifyWorkCompleted(
                                    tag = "webdav-restore",
                                    title = context.getString(R.string.webdav_notify_title_failed),
                                    body = webDavErrorMessage(context, t),
                                )
                            } finally {
                                remoteLoading = false
                            }
                        }
                    }
                },
                onDelete = { item -> deletePending = item },
            )
        }
    }

    // Remote automatic-backup management dialog: list, restore, fetch,
    // delete. Mirrors the manual remote sheet but scoped to the `auto/`
    // folder (plus legacy root stragglers) so the two backup kinds never
    // mix in one list. Restore goes through the exact same
    // restoreWithSnapshot path as every other restore (snapshot first,
    // rollback-able). Fetch downloads the copy into the local auto-backup
    // list, where it behaves like a locally-generated automatic backup.
    if (autoRemoteOpen) {
        webDavConfig?.let { cfg ->
            WebDavAutoDialog(
                config = cfg,
                items = autoRemoteItems,
                loading = autoRemoteLoading,
                error = autoRemoteError,
                busy = operationBusy,
                onDismiss = { autoRemoteOpen = false },
                onRefresh = openAutoRemoteList,
                onRestore = { entry ->
                    // Same gate rationale as the manual sheet: do NOT claim
                    // the global operationBusy across the network gap —
                    // restoreWithSnapshot is the single claim entry.
                    if (!operationBusy) {
                        autoRemoteLoading = true
                        application.applicationScope.launch {
                            try {
                                val json = withContext(Dispatchers.IO) {
                                    WebDavSync.restoreAuto(cfg, entry, webDavHttpClient)
                                }
                                autoRemoteOpen = false
                                restoreWithSnapshot(json)
                            } catch (t: Throwable) {
                                errorMessage = webDavErrorMessage(context, t)
                                notifier.notifyWorkCompleted(
                                    tag = "webdav-restore",
                                    title = context.getString(R.string.webdav_notify_title_failed),
                                    body = webDavErrorMessage(context, t),
                                )
                            } finally {
                                autoRemoteLoading = false
                            }
                        }
                    }
                },
                onFetch = { entry ->
                    if (!operationBusy) {
                        autoRemoteLoading = true
                        scope.launch {
                            try {
                                val json = withContext(Dispatchers.IO) {
                                    WebDavSync.restoreAuto(cfg, entry, webDavHttpClient)
                                }
                                val dir = File(context.filesDir, "backup-autos").apply { mkdirs() }
                                // Remote `rikkaminis-backup-auto-<stamp>.json`
                                // → local `rikkaminis-auto-<stamp>.json` so it
                                // joins the local auto-backup rotation and can
                                // be restored offline any time.
                                val stamp = entry.item.displayName
                                    .removePrefix(WebDavSync.AUTO_BACKUP_PREFIX)
                                    .removeSuffix(WebDavSync.BACKUP_SUFFIX)
                                val local = File(
                                    dir,
                                    com.rikkaminis.app.backup.AutoBackupManager.LOCAL_FILE_PREFIX + stamp + ".json",
                                )
                                withContext(Dispatchers.IO) { local.writeText(json) }
                                // Refresh the local auto-backup list so the
                                // fetched copy shows up immediately (state
                                // write back on Main — scope.launch is Main).
                                autoBackupFiles =
                                    withContext(Dispatchers.IO) {
                                        com.rikkaminis.app.backup.AutoBackupManager.listLocal(context)
                                    }
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.auto_backup_fetched),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            } catch (t: Throwable) {
                                errorMessage = webDavErrorMessage(context, t)
                            } finally {
                                autoRemoteLoading = false
                            }
                            // Refresh the remote list only AFTER the busy flag
                            // is cleared — openAutoRemoteList() re-arms it and
                            // fires its own async load, so ordering matters
                            // (finally above would otherwise clobber it).
                            openAutoRemoteList()
                        }
                    }
                },
                onDelete = { entry -> autoDeletePending = entry },
            )
        }
    }

    // Auto-backup delete confirmation — destructive, remote, irreversible.
    autoDeletePending?.let { entry ->
        AlertDialog(
            onDismissRequest = { autoDeletePending = null },
            title = { Text(stringResource(R.string.webdav_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.webdav_delete_confirm_body, entry.item.displayName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cfg = webDavConfig ?: return@TextButton
                        autoDeletePending = null
                        operationBusy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    WebDavSync.deleteBackupFile(
                                        cfg, entry.item, webDavHttpClient, subdir = entry.subdir,
                                    )
                                }
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.webdav_deleted),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                openAutoRemoteList()
                            } catch (t: Throwable) {
                                errorMessage = webDavErrorMessage(context, t)
                            } finally {
                                operationBusy = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.webdav_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { autoDeletePending = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Delete confirmation — destructive, remote, irreversible.
    deletePending?.let { item ->
        AlertDialog(
            onDismissRequest = { deletePending = null },
            title = { Text(stringResource(R.string.webdav_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.webdav_delete_confirm_body, item.displayName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cfg = webDavConfig ?: return@TextButton
                        deletePending = null
                        operationBusy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    WebDavSync.deleteBackupFile(cfg, item, webDavHttpClient)
                                }
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.webdav_deleted),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                openRemoteList()
                            } catch (t: Throwable) {
                                errorMessage = webDavErrorMessage(context, t)
                            } finally {
                                operationBusy = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.webdav_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletePending = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // [fix-audit-p0-2] Local-snapshot restore confirmation. Restoring a
    // snapshot goes through the exact same restoreWithSnapshot path as a
    // WebDAV restore — including taking a fresh snapshot of the current
    // config first, so a mistaken rollback is itself rollback-able.
    snapshotRestoreTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { snapshotRestoreTarget = null },
            title = { Text(stringResource(R.string.backup_snapshot_restore_confirm_title)) },
            text = {
                Text(stringResource(R.string.backup_snapshot_restore_confirm_text, file.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        snapshotRestoreTarget = null
                        // [audit-0908] Auto-backup files embed the artifact
                        // zip and can be tens of MB — reading them on the
                        // Main thread froze the dialog dismissal for hundreds
                        // of ms (old snapshots were ~2MB and hid this). Hop
                        // to IO, then hand off to the existing app-scoped
                        // restore path (which itself hops state writes to Main).
                        scope.launch {
                            val content = withContext(Dispatchers.IO) {
                                runCatching { file.readText() }.getOrNull()
                            }
                            if (content != null) {
                                restoreWithSnapshot(content)
                            } else {
                                errorMessage = errRead
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.backup_snapshot_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { snapshotRestoreTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Import is best-effort per item, so the result sheet has to say what did
    // NOT land — otherwise a partially-restored setup looks like a full one.
    importReport?.let { report ->
        AlertDialog(
            onDismissRequest = { importReport = null; snapshotNote = null },
            title = { Text(stringResource(R.string.backup_done_title)) },
            text = {
                Column {
                    snapshotNote?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Text(
                        stringResource(
                            R.string.backup_done_summary,
                            report.fieldsApplied,
                            report.providersImported,
                        )
                    )
                    if (report.groupsImported > 0) {
                        Text(
                            stringResource(R.string.backup_done_groups, report.groupsImported),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (report.envVarsImported > 0) {
                        Text(
                            stringResource(R.string.backup_done_env_vars, report.envVarsImported),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (report.skillsImported > 0) {
                        Text(
                            stringResource(R.string.backup_done_skills, report.skillsImported),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (report.memoryFilesImported > 0) {
                        Text(
                            stringResource(
                                R.string.backup_done_memory_files,
                                report.memoryFilesImported,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (report.mcpServersImported > 0) {
                        Text(
                            stringResource(
                                R.string.backup_done_mcp_servers,
                                report.mcpServersImported,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (report.chatSessionsImported > 0 || report.chatMessagesImported > 0) {
                        Text(
                            stringResource(
                                R.string.backup_done_chat,
                                report.chatSessionsImported,
                                report.chatMessagesImported,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (report.skipped.isNotEmpty()) {
                        Text(
                            stringResource(R.string.backup_done_skipped, report.skipped.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        // Cap the list: a backup restored onto a much older
                        // build can skip dozens of fields, and an unbounded
                        // dialog would run off the screen.
                        for (line in report.skipped.take(8)) {
                            Text("• $line", style = MaterialTheme.typography.bodySmall)
                        }
                        if (report.skipped.size > 8) {
                            Text("…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!report.hadSecrets && report.providersImported > 0) {
                        Text(
                            stringResource(R.string.backup_done_no_keys),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    // [fix-audit-p0-4] A fatal restore is NOT a normal one —
                    // surface the failure loudly and point at the rollback
                    // path instead of letting a half-applied config look ok.
                    report.fatal?.let { fatalMsg ->
                        Text(
                            stringResource(R.string.backup_done_fatal),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            fatalMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.backup_done_restart),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                if (report.fatal != null) {
                    // One-tap rollback to the newest pre-restore snapshot
                    // (which restoreWithSnapshot itself also snapshots first,
                    // so the rollback is itself reversible).
                    TextButton(
                        onClick = {
                            importReport = null
                            snapshotNote = null
                            val snap = snapshotFiles.firstOrNull()
                            if (snap != null) {
                                // [audit-0909 T6-M3] Same off-main-thread read
                                // as the snapshot-restore dialog above
                                // (audit-0908): auto-backup snapshots embed the
                                // artifact zip and can be tens of MB, and this
                                // one sits on the "restore just failed, roll me
                                // back NOW" path — freezing it is the worst
                                // possible moment. Last sibling of the trio.
                                scope.launch {
                                    val content = withContext(Dispatchers.IO) {
                                        runCatching { snap.readText() }.getOrNull()
                                    }
                                    if (content != null) restoreWithSnapshot(content)
                                    else errorMessage = errRead
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.backup_snapshot_restore))
                    }
                }
                TextButton(onClick = { importReport = null; snapshotNote = null }) {
                    Text(stringResource(R.string.backup_ok))
                }
            },
        )
    }

    if (showWindowDialog) {
        AlertDialog(
            onDismissRequest = { showWindowDialog = false },
            title = { Text(stringResource(R.string.backup_chat_window_title)) },
            text = {
                Column {
                    listOf(0, 30, 90, 180, 365).forEach { days ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    chatWindowDays = days
                                    chatPrefs.edit().putInt("chat_window_days", days).apply()
                                    showWindowDialog = false
                                }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                if (days == 0) {
                                    stringResource(R.string.backup_chat_window_off)
                                } else {
                                    stringResource(R.string.backup_chat_window_days, days)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (days == chatWindowDays) {
                                Text("✓", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }

    errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text(stringResource(R.string.backup_err_title)) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) {
                    Text(stringResource(R.string.backup_ok))
                }
            },
        )
    }
}

/**
 * WebDAV server configuration dialog — URL, username, password and the backup
 * folder path. The "Test connection" button PROPFINDs the folder and reports
 * success/failure inline before the user commits to saving.
 */
@Composable
private fun WebDavConfigDialog(
    initial: WebDavConfig?,
    httpClient: OkHttpClient,
    onDismiss: () -> Unit,
    onSave: (WebDavConfig) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var path by remember {
        mutableStateOf(initial?.path ?: WebDavConfig.DEFAULT_BACKUP_DIR)
    }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    val runTest: () -> Unit = runTest@{
        val cfg = WebDavConfig(
            url = url,
            username = username,
            password = password.ifBlank { initial?.password.orEmpty() },
            path = path,
        )
        if (cfg.url.isBlank() || cfg.username.isBlank()) {
            testResult = context.getString(R.string.webdav_err_invalid_url)
            return@runTest
        }
        testing = true
        testResult = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    WebDavSync.testConnection(cfg, httpClient)
                }
                testResult = context.getString(R.string.webdav_test_ok)
            } catch (t: Throwable) {
                testResult = webDavErrorMessage(context, t)
            } finally {
                testing = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.webdav_config_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = sanitizeSingleLineInput(it) },
                    label = { Text(stringResource(R.string.webdav_url_label)) },
                    placeholder = {
                        Text(stringResource(R.string.webdav_url_placeholder))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = sanitizeSingleLineInput(it) },
                    label = { Text(stringResource(R.string.webdav_username_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = sanitizeSingleLineInput(it) },
                    label = { Text(stringResource(R.string.webdav_password_label)) },
                    placeholder = {
                        Text(stringResource(R.string.webdav_password_placeholder))
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = sanitizeSingleLineInput(it) },
                    label = { Text(stringResource(R.string.webdav_path_label)) },
                    placeholder = {
                        Text(stringResource(R.string.webdav_path_hint))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = runTest, enabled = !testing) {
                        Text(
                            if (testing) {
                                stringResource(R.string.webdav_testing)
                            } else {
                                stringResource(R.string.webdav_test)
                            }
                        )
                    }
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .width(16.dp)
                                .padding(start = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    testResult?.let { result ->
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result == context.getString(R.string.webdav_test_ok)) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (url.isBlank() || username.isBlank()) {
                        testResult = context.getString(R.string.webdav_err_invalid_url)
                        return@TextButton
                    }
                    onSave(
                        WebDavConfig(
                            url = url,
                            username = username,
                            password = password,
                            path = path,
                        )
                    )
                },
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * Remote backup management sheet — lists `openminis-backup-*.json` files on the
 * WebDAV server (newest first) with size and timestamp, and offers restore /
 * delete per file. Restore downloads the payload and feeds it straight into
 * [ConfigBackup.import]; delete asks for confirmation first.
 */
@Composable
private fun WebDavRemoteDialog(
    config: WebDavConfig,
    items: List<WebDavBackupItem>,
    loading: Boolean,
    error: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onRestore: (WebDavBackupItem) -> Unit,
    onDelete: (WebDavBackupItem) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.webdav_remote)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (loading) {
                    Row(
                        modifier = Modifier.padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.width(28.dp))
                    }
                } else {
                    error?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onRefresh, enabled = !busy) {
                            Text(stringResource(R.string.webdav_retry))
                        }
                    } ?: if (items.isEmpty()) {
                        Text(
                            stringResource(R.string.webdav_remote_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    } else {
                        LazyColumn {
                            items(items, key = { it.displayName }) { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                        )
                                        Text(
                                            text = "${formatSize(item.size)} · ${formatInstant(item.lastModified)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(
                                        onClick = { onRestore(item) },
                                        enabled = !busy,
                                    ) {
                                        Text(stringResource(R.string.webdav_restore))
                                    }
                                    TextButton(
                                        onClick = { onDelete(item) },
                                        enabled = !busy,
                                    ) {
                                        Text(
                                            stringResource(R.string.webdav_delete),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // Footer: the configured path — the #1 cross-device gotcha is
                // two devices pointing at different WebDAV paths, which makes
                // the other device's files silently invisible.
                Text(
                    stringResource(R.string.webdav_remote_path, config.path),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.backup_ok))
            }
        },
    )
}

@Composable
private fun WebDavAutoDialog(
    config: WebDavConfig,
    items: List<WebDavSync.AutoBackupEntry>,
    loading: Boolean,
    error: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onRestore: (WebDavSync.AutoBackupEntry) -> Unit,
    onFetch: (WebDavSync.AutoBackupEntry) -> Unit,
    onDelete: (WebDavSync.AutoBackupEntry) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auto_backup_remote)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (loading) {
                    Row(
                        modifier = Modifier.padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.width(28.dp))
                    }
                } else {
                    error?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onRefresh, enabled = !busy) {
                            Text(stringResource(R.string.webdav_retry))
                        }
                    } ?: if (items.isEmpty()) {
                        Text(
                            stringResource(R.string.auto_backup_remote_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    } else {
                        LazyColumn {
                            // Same stamp can theoretically exist in both the
                            // auto/ subdir and the legacy root — key on the
                            // location-qualified name.
                            items(items, key = { it.item.displayName + it.subdir }) { entry ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                ) {
                                    Text(
                                        text = entry.item.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = "${formatSize(entry.item.size)} · ${formatInstant(entry.item.lastModified)}" +
                                            if (entry.subdir.isBlank()) " · root" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(
                                        modifier = Modifier.align(androidx.compose.ui.Alignment.End),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    ) {
                                        TextButton(
                                            onClick = { onFetch(entry) },
                                            enabled = !busy,
                                        ) {
                                            Text(stringResource(R.string.auto_backup_remote_fetch))
                                        }
                                        TextButton(
                                            onClick = { onRestore(entry) },
                                            enabled = !busy,
                                        ) {
                                            Text(stringResource(R.string.webdav_restore))
                                        }
                                        TextButton(
                                            onClick = { onDelete(entry) },
                                            enabled = !busy,
                                        ) {
                                            Text(
                                                stringResource(R.string.webdav_delete),
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // Footer: the configured path — the #1 cross-device gotcha is
                // two devices pointing at different WebDAV paths, which makes
                // the other device's files silently invisible.
                Text(
                    stringResource(R.string.auto_backup_remote_path, config.path),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.backup_ok))
            }
        },
    )
}

/** Maps transport failures to user-facing messages by HTTP status / kind. */
private fun webDavErrorMessage(context: Context, t: Throwable): String {
    val dav = t as? WebDavException
    return when {
        dav != null && (dav.statusCode == 401 || dav.statusCode == 403) ->
            context.getString(R.string.webdav_err_auth)
        dav != null && dav.statusCode == 404 ->
            context.getString(R.string.webdav_err_not_found)
        dav != null && dav.statusCode > 0 ->
            context.getString(R.string.webdav_err_server, dav.statusCode)
        t is IOException ->
            context.getString(
                R.string.webdav_err_network,
                t.message ?: context.getString(R.string.webdav_err_unknown),
            )
        else -> t.message ?: context.getString(R.string.webdav_err_unknown)
    }
}

private val INSTANT_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())
        .withZone(ZoneId.systemDefault())

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatInstant(instant: Instant): String = INSTANT_FORMATTER.format(instant)
