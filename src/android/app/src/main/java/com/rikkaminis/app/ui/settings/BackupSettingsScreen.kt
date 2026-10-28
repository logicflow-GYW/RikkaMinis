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
import com.rikkaminis.app.backup.OpenMinisBackupCompat
import com.rikkaminis.app.scheduled.ScheduledTasksStore
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
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
    // [fix-minisbak-import] OpenMinis .minisbak 恢复的两段式状态：先把包字节
    // 挂在屏上，弹出令框，拿到口令后再转换。加密包的 PBKDF2 是 600k 轮，
    // 必须放到 Dispatchers.IO。
    var pendingMinisbakBytes by remember { mutableStateOf<ByteArray?>(null) }
    var passphraseText by remember { mutableStateOf("") }
    // [fix-minisbak-import] .minisbak 转换窗口的专用忙碌标记（与 operationBusy
    // 双重门禁：转换期挡导入重入）。不能在恢复前一直占着 operationBusy——
    // restoreWithSnapshot 的互斥守卫见 busy 就拒绝（backup_err_busy），那是
    // 加密包导入"永远 busy"的根因；转换结束须在同一帧内让锁再交给
    // restoreWithSnapshot（详见 convertAndRestore 注释）。
    var minisbakConverting by remember { mutableStateOf(false) }

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
    // [deep-audit-1033 I08] Starts null: `webDavStore.load()` opens
    // EncryptedSharedPreferences (Keystore unwrap) and must not run on the
    // composition (Main) thread. The LaunchedEffect below fills it.
    var webDavConfig by remember { mutableStateOf<WebDavConfig?>(null) }
    // [T-auto-backup-assets] Persist a restored WebDAV server config and
    // refresh Compose state. Declared AFTER webDavConfig so the lambda can
    // reference it (local-variable ordering).
    // [deep-audit-1033 I08] The save is a blocking EncryptedSharedPreferences
    // commit() and this callback runs inside the config-import path
    // (ConfigBackup.import), which may outlive this screen — so it runs on the
    // app-scoped scope (a screen scope cancelled by navigation would silently
    // drop a restored server config) and hops back to Main for the Compose
    // state write, which used to happen on the caller's thread.
    val applyWebDavConfig: (WebDavConfig) -> Unit = { cfg ->
        application.applicationScope.launch {
            withContext(Dispatchers.IO) { runCatching { webDavStore.save(cfg) } }
            withContext(Dispatchers.Main) { webDavConfig = cfg }
        }
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
    // [deep-audit-1033 I08] Both of these read SharedPreferences, which parses
    // the XML file on first access — off the composition thread, filled by the
    // entry LaunchedEffect below (same shape as the two list loads).
    var autoBackupEnabled by remember { mutableStateOf(false) }
    var autoBackupRunning by remember { mutableStateOf(false) }
    // [fix/da1023-backup-streaming-fetch] `listLocal` lists filesDir — it must
    // not run inside `remember` on the composition (Main) thread. Start empty
    // and load on IO, same shape as the snapshot list below.
    var autoBackupFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    // [fix-autobackup-keep-3] Local auto-backup copies collapse by default —
    // same treatment as the pre-restore snapshot list: they are a rollback
    // utility, not a primary surface. Tap the toggle row to expand the list.
    var autoBackupsExpanded by remember { mutableStateOf(false) }
    var autoBackupLastRun by remember { mutableStateOf<String?>(null) }
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
    // [audit-0917] No disk IO in composition: start empty and let the
    // LaunchedEffect below populate the list on the IO dispatcher.
    var snapshotFiles by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    var snapshotRestoreTarget by remember { mutableStateOf<File?>(null) }
    // [fix-snapshot-keep-1] Snapshot section collapses by default — it is a
    // rollback utility, not a primary backup surface. Auto-backup pulls from
    // the remote; local backup navigates to the file. Tap the toggle row to
    // expand the list.
    var snapshotsExpanded by remember { mutableStateOf(false) }

    // Refresh the snapshot list on entry (and after restore writes a new one).
    LaunchedEffect(Unit) {
        snapshotFiles = withContext(Dispatchers.IO) { ConfigBackup.listSnapshots(snapshotDir) }
    }

    // [fix/da1023-backup-streaming-fetch] Entry load for the local automatic
    // backup list. Listing filesDir is disk IO, so it runs on IO — the state
    // starts empty — instead of inside the `remember` on the composition
    // (Main) thread it used to sit in. Same shape as the snapshot refresh.
    LaunchedEffect(Unit) {
        autoBackupFiles = withContext(Dispatchers.IO) { com.rikkaminis.app.backup.AutoBackupManager.listLocal(context) }
    }

    // [deep-audit-1033 I08] Entry load for the WebDAV server config and the
    // auto-backup row. `webDavStore.load()` opens EncryptedSharedPreferences
    // (Keystore unwrap, blocking) and the other two are SharedPreferences
    // reads — all three used to run inline in `remember { … }`, i.e. on the
    // composition (Main) thread, on every entry to this screen. Start from the
    // "not configured / never ran" defaults and load them here.
    LaunchedEffect(Unit) {
        webDavConfig = withContext(Dispatchers.IO) { webDavStore.load() }
        val autoRow = withContext(Dispatchers.IO) {
            com.rikkaminis.app.backup.AutoBackupManager.isEnabled(context) to
                com.rikkaminis.app.backup.AutoBackupManager.lastRunLabel(context)
        }
        autoBackupEnabled = autoRow.first
        autoBackupLastRun = autoRow.second
    }

    val refreshSnapshots: () -> Unit = {
        // [audit-0917] listSnapshots reads the dir - off the Main thread
        // like the entry refresh above.
        scope.launch {
            snapshotFiles = withContext(Dispatchers.IO) { ConfigBackup.listSnapshots(snapshotDir) }
        }
    }

    val savedToast = stringResource(R.string.backup_saved)
    val errWriteFmt = stringResource(R.string.backup_err_write)
    val errRead = stringResource(R.string.backup_err_read)
    val errImport = stringResource(R.string.backup_err_import)
    val errUnknown = stringResource(R.string.backup_err_unknown)

    // Restore-with-safety-net: before applying an imported config (local file
    // or WebDAV) snapshot the CURRENT config so the user can always roll back
    // after a mistaken restore.
    // [FIX-P1A / RT-S3] This used to be "best-effort: a failed snapshot never
    // blocks the restore". That inverted the point of a rollback point: the
    // import went on to overwrite the config with NO way back, while the
    // "one-tap rollback" list was populated from whatever half-written file
    // the failed snapshot had left behind. Writes are atomic now (no truncated
    // JSON can appear), and a failure ABORTS the restore instead — the user is
    // told, nothing was applied, and retrying after freeing space is safe.
    // Declared before the launchers below because their callbacks invoke it.
    // [meta-audit-1028 D4 F-02] [preClaimed] says the caller already holds the shared
    // gate (see [claimRestorePayload]): every restore that has to BUILD a payload — a
    // WebDAV download, a local snapshot/auto-backup read, the SAF import — now claims it
    // BEFORE the download/read and hands the claim over here. Without the flag this
    // function's own check would refuse the very restore that already holds the lock —
    // the self-collision the [refactor-backup-gate] comment further down works around.
    //
    // (A lambda, not a nested `fun`, on purpose: `ime_inset_guard`'s B01-CALLS registry
    // attributes a `SettingsScaffold(` call to the nearest preceding `fun`, so a nested
    // one would orphan this screen's own exemption entry.)
    val restoreWithSnapshot: (String, Boolean) -> Unit = doRestore@{ json, preClaimed ->
        // [fix restore-mutex] Reject a restore while any backup/restore path is
        // already in flight. The pre-restore snapshot export builds another full
        // 70MB+ payload; two such exports concurrently (e.g. restoring while a
        // local backup upload is running) stacks two payloads and blows the
        // 512MB heap. This is the last line of defense behind the button-level
        // guards — the snapshot-restore dialog and WebDAV sheet reach here too.
        if (!preClaimed && operationBusy) {
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
        application.applicationScope.launch snapshotBeforeRestore@{
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
                // [fix-audit-p0-3] Second-precision base name + keep SNAPSHOT_KEEP
                // newest (see ConfigBackup.writeSnapshot) so two restores in
                // the same minute no longer overwrite the first rollback point.
                // [FIX-P1A / RT-S3] The write itself is atomic and the name is
                // resolved against the directory, so a same-second restore can
                // neither truncate nor overwrite the existing rollback point.
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
                        scheduledTasks = ScheduledTasksStore.exportDocument(context),
                        writer = w,
                    )
                }
                withContext(Dispatchers.Main) {
                    snapshotNote = context.getString(R.string.backup_snapshot_local)
                    refreshSnapshots()
                }
            } catch (t: Throwable) {
                // [FIX-P1A / RT-S3] No rollback point ⇒ no restore. Continuing
                // would overwrite a config the user cannot get back, which is
                // strictly worse than "fix the cause and retry" (typically
                // ENOSPC, or a document over the payload cap). The failure is
                // reported through the existing error dialog + tray, so this
                // adds no UI surface of its own.
                val failed = context.getString(R.string.backup_snapshot_failed)
                withContext(Dispatchers.Main) {
                    snapshotNote = failed
                    errorMessage = failed
                    operationBusy = false
                }
                notifier.notifyWorkCompleted(
                    tag = "webdav-restore",
                    title = context.getString(R.string.webdav_notify_title_failed),
                    body = failed,
                )
                return@snapshotBeforeRestore
            }
            var restoredOk = true
            var restoredMsg: String? = null
            try {
                val report = withContext(Dispatchers.Default) {
                    // [D-B fix/backup-bootstrap-size] Read the CURRENT server
                    // config first (EncryptedSharedPreferences → IO): the merge
                    // in ConfigBackup.import has to run against what is actually
                    // stored, not against Compose state that may still be null.
                    // WebDavConfigStore.save() reads a blank password as "clear
                    // the stored one", so a secrets-free backup's missing
                    // password must be filled in from here — otherwise applying
                    // it silently deletes the password the user just retyped.
                    val existingWebDav = withContext(Dispatchers.IO) {
                        runCatching { webDavStore.load() }.getOrNull()
                    }
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
                        onScheduledTasks = { doc ->
                            ScheduledTasksStore.importDocument(context, doc)
                        },
                        existingWebDavConfig = existingWebDav,
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

    // [meta-audit-1028 D4 F-02] The single claim entry for a restore that has to BUILD a
    // payload first: the WebDAV manual/auto download, the local snapshot / auto-backup
    // file read, and the SAF import. Claiming HERE — not inside restoreWithSnapshot — is
    // what keeps two payloads from being resident at once, because the download/read
    // window IS the window in which a second 70MB would be allocated. It is also why a
    // download that finished second used to be thrown away: nothing had stopped it from
    // starting, so restoreWithSnapshot's own check met "busy" and reported failure after
    // the bytes were already in the heap.
    //
    // Returns false when the caller must not proceed; the reason has been reported.
    val claimRestorePayload: () -> Boolean = claimRestorePayload@{
        if (!canStartRestorePayload(
                operationBusy = operationBusy,
                minisbakConverting = minisbakConverting,
            )
        ) {
            errorMessage = context.getString(R.string.backup_err_busy)
            return@claimRestorePayload false
        }
        operationBusy = true
        true
    }

    /** Lower the claim from [claimRestorePayload]. Never call it after a handoff. */
    val releaseRestorePayload: () -> Unit = { operationBusy = false }

    // [fix-minisbak-import] .minisbak 转换 + 恢复交接的唯一入口：文件选择器
    //（明文包）与口令对话框（加密包）都走这里。
    //
    // 为什么持有 operationBusy 又要在恢复前让出去：restoreWithSnapshot 的
    // 互斥守卫见 busy 即拒（backup_err_busy）。原先加密路径在转换完成后仍占
    // 着全局锁，恢复必然被拒——解密成果被"备份或恢复正在进行中"整个吞掉。
    // 转换期间保持双重门禁（与本屏其他操作的既有语义一致）：minisbakConverting
    // 挡导入重入，operationBusy 挡其他备份/恢复操作；恢复前在同一帧内让锁，
    // 让出与调用之间没有挂起点，主线程上不会被打断，锁不外泄。
    val convertAndRestore: (ByteArray, CharArray?) -> Unit = convertAndRestore@{ pkg, pw ->
        if (operationBusy || minisbakConverting) return@convertAndRestore
        minisbakConverting = true
        operationBusy = true
        scope.launch {
            var handedOff = false
            try {
                // pw != null：口令对话框确认后的直接转换；pw == null：首次
                // 嗅探（加密 → 弹口令框；明文 → 直接转换）。嗅探要流式读
                // ZIP（扫到 manifest.json 才停），是 I/O，连同 PBKDF2 600k
                // 轮 + 全包解密一起留在 Dispatchers.IO；状态写回在 Main。
                val sniff = withContext(Dispatchers.IO) {
                    if (pw != null) MinisbakImport.Plain(OpenMinisBackupCompat.convert(pkg, pw))
                    else if (OpenMinisBackupCompat.requiresPassphrase(pkg)) MinisbakImport.Encrypted
                    else MinisbakImport.Plain(OpenMinisBackupCompat.convert(pkg, null))
                }
                when (sniff) {
                    MinisbakImport.Encrypted -> {
                        passphraseText = ""
                        pendingMinisbakBytes = pkg
                    }
                    is MinisbakImport.Plain -> {
                        // [meta-audit-1028 D4 F-02] Handoff, not release-then-recheck:
                        // the old shape dropped the gate here and let restoreWithSnapshot
                        // re-acquire it, which is a real (if narrow) window — another
                        // restore could take the lock between the two statements and the
                        // converted payload would be refused after it was already built.
                        // Same Main frame, no suspension, so the claim never escapes.
                        handedOff = true
                        restoreWithSnapshot(sniff.json, true)
                    }
                }
            } catch (t: Throwable) {
                errorMessage = t.message ?: errImport
            } finally {
                if (!handedOff) operationBusy = false
                pw?.fill(' ')
                minisbakConverting = false
            }
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
                                scheduledTasks = ScheduledTasksStore.exportDocument(context),
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
                    // [audit-0917] Show the body (it carries the incomplete-backup
                    // warning when fields failed to serialize), not just the
                    // success string.
                    Toast.makeText(context, body, Toast.LENGTH_SHORT).show()
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
        // dialog (568d87a). errorMessage is Compose state, so it is written
        // back on this Main-dispatcher scope.
        //
        // [deep-audit-1047 B-02] Two holes were left by that hop:
        //  * the `OpenableColumns.SIZE` query (a cross-process IPC) stayed
        //    inline on Main — it is now inside the same IO block as the read;
        //  * the declared size was the ONLY bound. A provider that reports no
        //    size (or lies about it) fell through to `readBytes()` and pulled
        //    the whole file into the heap, and a lying `SIZE` under the cap did
        //    the same. The read now goes through [readSafBytes], which keeps
        //    the declared-size precheck as a cheap early reject AND caps the
        //    stream itself one byte past MAX_PAYLOAD_BYTES.
        scope.launch {
            try {
                // [T-backup-import-memory] Reject oversized files BEFORE pulling
                // them into memory. The exact check inside ConfigBackup.import
                // only runs after the whole file has been read into a String
                // (UTF-16 doubles the allocation) and a full pre-restore
                // snapshot export has been built — an OOM window for large
                // backups.
                //
                // [D-C fix/backup-bootstrap-size] This cap and the one inside
                // import() are now the SAME unit (UTF-8 bytes) and the same
                // value, and the export budget packs against that unit too —
                // so "export succeeded" implies "this read accepts it". Before
                // the unit fix this read was bytes while export/import compared
                // chars: a 71.4MB Chinese backup exported fine and was then
                // rejected here on every attempt.
                val bytes = withContext(Dispatchers.IO) {
                    when (val read = readSafBytes(
                        context.contentResolver,
                        uri,
                        ConfigBackup.MAX_PAYLOAD_BYTES.toLong(),
                    )) {
                        is SafRead.Ok -> read.bytes
                        SafRead.TooLarge -> {
                            // The verdict is already made on the STREAM; the
                            // declared size is re-read only for the wording, and
                            // falls back to the cap when the provider reported
                            // nothing (that is exactly the case a declared-only
                            // check used to let through).
                            val declared = querySafSize(context.contentResolver, uri)
                            val mb = (declared ?: ConfigBackup.MAX_PAYLOAD_BYTES.toLong()) / (1024 * 1024)
                            val maxMb = ConfigBackup.MAX_PAYLOAD_BYTES / (1024 * 1024)
                            throw IllegalStateException(
                                context.getString(R.string.backup_import_too_large, mb, maxMb),
                            )
                        }
                        SafRead.Failed -> throw IllegalStateException(errRead)
                    }
                }
                // [fix-minisbak-import] 读**字节**，不是文本。
                //
                // OpenMinis 的 .minisbak 是 ZIP 二进制包。原代码用
                // bufferedReader().readText()：org.json 的 JSONTokener 是宽松
                // 解析器，ZIP 头 PK\x03\x04 会被当成一个裸字符串字面量返回
                // （不抛异常），as? JSONObject 得 null，于是 ConfigBackup.import
                // 抛 "Backup root is not a JSON object" —— 真机上就是这个错。
                // 兼容层之前根本没被任何地方调用，属于死代码。
                //
                // 字节不能先 String 化：UTF-8 解码 ZIP 会丢信息。
                // [deep-audit-1047 B-02] 上面的 readSafBytes 就是这次读取——
                // 字节已按 MAX_PAYLOAD_BYTES 封顶，这里不再有第二个读点。
                // [meta-audit-1028 D4 F-02] `String(bytes, …)` lands on Main right here
                // and is the exact same tens-of-MB UTF-16 resident as a snapshot read:
                // one copy is the ByteArray, the other is the String, and the restore's
                // own snapshot export is a THIRD. Claim the gate before decoding so the
                // two-payload heap cannot be reached from this entry either (the import
                // row's `enabled` guard covers only the tap, and the picker callback
                // arrives after it has been checked).
                if (OpenMinisBackupCompat.looksLikeMinisBak(bytes)) {
                    // [fix-minisbak-import] 嗅探/转换/恢复交接统一走
                    // convertAndRestore（IO 线程 + 忙碌门禁 + 锁交接）。
                    convertAndRestore(bytes, null)
                } else if (claimRestorePayload()) {
                    try {
                        restoreWithSnapshot(String(bytes, Charsets.UTF_8), true)
                    } catch (t: Throwable) {
                        // The claim is ours and the handoff did not happen (the decode
                        // itself can fail on a big payload) — lower it before rethrowing,
                        // or the whole backup screen stays wedged behind a gate nobody
                        // holds.
                        releaseRestorePayload()
                        throw t
                    }
                }
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
                            // [fix/da1023-backup-streaming-fetch] runNow and
                            // the post-run local listing are both disk IO —
                            // the listing used to run inside the
                            // Dispatchers.Main block below; do it on IO and
                            // hand only the result back to Main.
                            val autoManager = com.rikkaminis.app.backup.AutoBackupManager
                            withContext(Dispatchers.IO) { autoManager.runNow(application) }
                            val refreshedFiles = withContext(Dispatchers.IO) { autoManager.listLocal(context) }
                            withContext(Dispatchers.Main) {
                                autoBackupFiles = refreshedFiles
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
                // [fix-autobackup-keep-3] Collapsed by default: the local copy
                // list is a rollback utility (auto-backup restores from the
                // remote; local backup navigates to the file), so it no longer
                // pushes every row into the section. Toggle row reuses the
                // auto_backup_local string — mirrors auto_backup_remote above.
                SettingsRow(
                    title = stringResource(R.string.auto_backup_local),
                    icon = Icons.Filled.Restore,
                    onClick = { autoBackupsExpanded = !autoBackupsExpanded },
                    showDivider = autoBackupsExpanded,
                )
                if (autoBackupsExpanded) {
                    Text(
                        stringResource(R.string.auto_backup_restore_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    autoBackupFiles.take(com.rikkaminis.app.backup.WebDavSync.AUTO_BACKUP_KEEP).forEach { file ->
                        SettingsRow(
                            title = file.name.removePrefix(com.rikkaminis.app.backup.AutoBackupManager.LOCAL_FILE_PREFIX)
                                .removeSuffix(".json"),
                            // [FIX-1028 / B-12] Same key this list is ORDERED
                            // by (the stamp in the local backup's name), so a
                            // rolled-back clock cannot make the row's time
                            // contradict its position. Falls back to mtime only
                            // for a fetched copy that keeps a foreign stamp.
                            subtitle = java.text.SimpleDateFormat(
                                "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
                            ).format(
                                java.util.Date(
                                    com.rikkaminis.app.backup.AutoBackupManager.localStampMillis(file)
                                        ?: file.lastModified(),
                                ),
                            ),
                            icon = Icons.Filled.Restore,
                            // [meta-audit-1028 D4 F-02] A restore row cannot open the
                            // confirm dialog while a restore payload is already being
                            // built: the dialog's read IS the second payload. `null` is
                            // this row's own "disabled" (SettingsRow). The claim inside
                            // the dialog is the real gate — this only stops the tap from
                            // queueing work that would be refused.
                            onClick = if (operationBusy || minisbakConverting) null else ({
                                snapshotRestoreTarget = file
                            }),
                        )
                    }
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
                onClick = if (operationBusy) null else ({ webDavUploadPending = false; showSecretWarning = true }),
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
                onClick = if (operationBusy || minisbakConverting) null else ({ importLauncher.launch(arrayOf("application/json", "*/*")) }),
                showDivider = false,
            )
        }
        // [fix-audit-p0-2] Local pre-restore snapshots with a rollback entry
        // point. Before this the snapshots were written but unreachable — the
        // UI promised "snapshot saved" yet nothing could restore from it.
        // [fix-snapshot-keep-1] Collapsed by default: the snapshot list is a
        // rollback utility, not a primary backup surface. The toggle row
        // expands it. Reuses the section-title string — no new i18n keys.
        SettingsSection(
            footer = stringResource(R.string.backup_snapshot_section_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.backup_snapshot_section_title),
                icon = Icons.Filled.Restore,
                onClick = { snapshotsExpanded = !snapshotsExpanded },
                showDivider = snapshotsExpanded,
            )
            if (snapshotsExpanded) {
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
                            // [FIX-1028 / B-12] Display the key this list is
                            // ORDERED by (the stamp in the file name), else the
                            // mtime. Showing mtime on a name-ordered list made a
                            // rolled-back clock look like the newest rollback
                            // point was the oldest — and the one-tap rollback on
                            // the fatal-restore dialog takes the FIRST row.
                            subtitle = java.text.SimpleDateFormat(
                                "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
                            ).format(java.util.Date(ConfigBackup.snapshotDisplayMillis(file))),
                            icon = Icons.Filled.Restore,
                            // [meta-audit-1028 D4 F-02] A restore row cannot open the
                            // confirm dialog while a restore payload is already being
                            // built: the dialog's read IS the second payload. `null` is
                            // this row's own "disabled" (SettingsRow). The claim inside
                            // the dialog is the real gate — this only stops the tap from
                            // queueing work that would be refused.
                            onClick = if (operationBusy || minisbakConverting) null else ({
                                snapshotRestoreTarget = file
                            }),
                            showDivider = index < snapshotFiles.size - 1,
                        )
                    }
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
                                            scheduledTasks = ScheduledTasksStore.exportDocument(context),
                                            writer = w,
                                        )
                                    }
                                }
                            }
                            // [deep-audit-1049 B-02] Resolve config ON Main: the
                            // comment that used to sit here always claimed Main,
                            // but the read sat on the applicationScope dispatcher
                            // (Dispatchers.IO) and so did the `errorMessage` write
                            // in the bail arm — both are Compose state. One hop
                            // covers the read and the "not configured" report.
                            val cfg = withContext(Dispatchers.Main) {
                                val current = webDavConfig
                                if (current == null) {
                                    errorMessage = context.getString(
                                        R.string.webdav_server_not_configured,
                                    )
                                }
                                current
                            }
                            if (cfg == null) {
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
            onDismissRequest = {
                showSecretWarning = false
                webDavUploadPending = false
            },
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
                // [deep-audit-1033 I08] save() commits to EncryptedSharedPreferences
                // and the reload below reads it back — both are blocking keystore
                // IO and used to run inline in this click handler, on the main
                // thread. App-scoped for the same reason as applyWebDavConfig:
                // navigating away must not lose a just-typed server config.
                application.applicationScope.launch {
                    val reloaded = withContext(Dispatchers.IO) {
                        runCatching { webDavStore.save(cfg) }
                        webDavStore.load()
                    }
                    withContext(Dispatchers.Main) {
                        webDavConfig = reloaded
                        showWebDavConfig = false
                        Toast.makeText(
                            context,
                            context.getString(R.string.webdav_saved),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
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
                    // [meta-audit-1028 D4 F-02] Claim the shared gate for the WHOLE
                    // download and hand it to restoreWithSnapshot (see
                    // [claimRestorePayload]) instead of checking it only after the
                    // download. The old shape left the window unguarded and used the
                    // dialog-local `remoteLoading` for exclusion — which
                    // `openRemoteList` clears in its own `finally`, so re-opening the
                    // sheet mid-download re-enabled Restore and stacked a second 70MB
                    // payload. Holding the gate also stops a download from finishing
                    // into `backup_err_busy` (bytes paid for, restore refused).
                    if (claimRestorePayload()) {
                        remoteLoading = true
                        application.applicationScope.launch {
                            var handedOff = false
                            try {
                                val json = withContext(Dispatchers.IO) {
                                    WebDavSync.restore(cfg, item, webDavHttpClient)
                                }
                                // [FIX-6 / F-242] applicationScope runs on
                                // Dispatchers.IO — every Compose state write in
                                // this block must hop back to Main, exactly like
                                // the sibling paths in this file (see
                                // restoreWithSnapshot's [fix-audit-p1-1] note).
                                // The handoff happens in the same Main frame as the
                                // read result: no suspension between the download
                                // ending and restoreWithSnapshot taking over the
                                // claim, so it can never be dropped on the floor.
                                withContext(Dispatchers.Main) {
                                    showRemoteList = false
                                    handedOff = true
                                    restoreWithSnapshot(json, true)
                                }
                            } catch (t: Throwable) {
                                withContext(Dispatchers.Main) {
                                    errorMessage = webDavErrorMessage(context, t)
                                }
                                notifier.notifyWorkCompleted(
                                    tag = "webdav-restore",
                                    title = context.getString(R.string.webdav_notify_title_failed),
                                    body = webDavErrorMessage(context, t),
                                )
                            } finally {
                                withContext(Dispatchers.Main) {
                                    remoteLoading = false
                                    if (!handedOff) releaseRestorePayload()
                                }
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
                    // [meta-audit-1028 D4 F-02] Same claim-before-download shape as the
                    // manual sheet above (twin site, same defect, same fix): the gate is
                    // held across the network gap and handed to restoreWithSnapshot via
                    // [claimRestorePayload] / preClaimed.
                    if (claimRestorePayload()) {
                        autoRemoteLoading = true
                        application.applicationScope.launch {
                            var handedOff = false
                            try {
                                val json = withContext(Dispatchers.IO) {
                                    WebDavSync.restoreAuto(cfg, entry, webDavHttpClient)
                                }
                                // [FIX-6 / F-242] Same Main-hop discipline as
                                // the manual sheet above — this twin site was
                                // missed by the same batch of fixes.
                                withContext(Dispatchers.Main) {
                                    autoRemoteOpen = false
                                    handedOff = true
                                    restoreWithSnapshot(json, true)
                                }
                            } catch (t: Throwable) {
                                withContext(Dispatchers.Main) {
                                    errorMessage = webDavErrorMessage(context, t)
                                }
                                notifier.notifyWorkCompleted(
                                    tag = "webdav-restore",
                                    title = context.getString(R.string.webdav_notify_title_failed),
                                    body = webDavErrorMessage(context, t),
                                )
                            } finally {
                                withContext(Dispatchers.Main) {
                                    autoRemoteLoading = false
                                    if (!handedOff) releaseRestorePayload()
                                }
                            }
                        }
                    }
                },
                onFetch = { entry ->
                    if (!operationBusy) {
                        // [deep-audit-1040 B-08] Claim the SAME single gate as
                        // every other backup path (local SAF export, WebDAV
                        // upload, restore, delete). This fetch pulls a full
                        // payload into memory and then writes + rotates
                        // `filesDir/backup-autos` — the very directory the
                        // nightly run and every restore also touch — yet it
                        // used to flip only the dialog-local
                        // `autoRemoteLoading`. Dismissing the dialog mid-flight
                        // (its OK button and the back gesture are never gated)
                        // left "Run Now" / "Restore" / "Export" clickable and
                        // let two full payloads land on that directory at once
                        // (and stack on the 512MB heap).
                        operationBusy = true
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
                                // [fix/da1023-backup-streaming-fetch] The
                                // display name comes from the WebDAV server
                                // and is NOT under our control — a hostile or
                                // just odd name must never be concatenated
                                // into a local path unfiltered
                                // (`../../evil` would write through the
                                // backup-autos directory). Sanitize
                                // fail-closed: refuse to write on anything
                                // that is not a clean filename token.
                                val rawStamp = entry.item.displayName
                                    .removePrefix(WebDavSync.AUTO_BACKUP_PREFIX)
                                    .removeSuffix(WebDavSync.BACKUP_SUFFIX)
                                val stamp = WebDavSync.sanitizeAutoBackupStamp(rawStamp)
                                if (stamp == null) {
                                    // ponytail: English-only rejection message for a hostile-input corner | 天花板: 非英文用户看到英文报错（极罕见：服务器侧名字被污染才会触发） | 升级触发: strings.xml 解冻或同类"拒绝输入"文案积累
                                    throw IllegalArgumentException(
                                        "Rejected unsafe auto-backup filename: ${entry.item.displayName}",
                                    )
                                }
                                val local = File(
                                    dir,
                                    com.rikkaminis.app.backup.AutoBackupLocalStore.fileName(
                                        com.rikkaminis.app.backup.AutoBackupManager.LOCAL_FILE_PREFIX,
                                        stamp,
                                    ),
                                )
                                // [FIX-P1B / FINDING-14] Second writer into the
                                // same directory: a plain writeText leaves a
                                // truncated document after a kill mid-write, and
                                // it looks like a legal backup to listLocal (the
                                // nightly path had the same hole). Route it
                                // through the repo's existing atomic write.
                                withContext(Dispatchers.IO) {
                                    com.rikkaminis.app.tools.AtomicFileWrite.write(local, json)
                                }
                                // [fix/da1023-backup-streaming-fetch] The
                                // fetched copy joins the SAME local rotation
                                // as the nightly writes — before this, every
                                // fetch added an unbounded local copy with no
                                // AUTO_BACKUP_KEEP cap applied.
                                //
                                // [FIX-R3-D / F-01] …and the fetched copy is
                                // protected: a copy fetched from an OLDER date
                                // sorts as the oldest by name, so the rotation
                                // deleted it in the same breath as writing it
                                // (the Toast said "fetched", the list showed
                                // nothing new).
                                withContext(Dispatchers.IO) {
                                    com.rikkaminis.app.backup.AutoBackupManager.withFreshlyWritten(local) {
                                        com.rikkaminis.app.backup.AutoBackupManager.rotateLocalAutoBackups(context)
                                    }
                                }
                                // Refresh the local auto-backup list so the
                                // fetched copy shows up immediately (state
                                // write back on Main — scope.launch is Main).
                                autoBackupFiles = withContext(Dispatchers.IO) { com.rikkaminis.app.backup.AutoBackupManager.listLocal(context) }
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.auto_backup_fetched),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            } catch (t: CancellationException) {
                                // [deep-audit-1040 B-08] The screen went away
                                // mid-flight and cancelled this launch. Not a
                                // failure: rethrow so the coroutine really ends
                                // here instead of falling through to the list
                                // refresh below (that refresh re-arms
                                // autoRemoteLoading and fires its own load from
                                // a scope that is already dead, leaving the
                                // dialog on a spinner nobody can clear).
                                throw t
                            } catch (t: Throwable) {
                                errorMessage = webDavErrorMessage(context, t)
                            } finally {
                                autoRemoteLoading = false
                                operationBusy = false
                            }
                            // Refresh the remote list only AFTER the busy flag
                            // is cleared — openAutoRemoteList() re-arms it and
                            // fires its own async load, so ordering matters
                            // (finally above would otherwise clobber it).
                            // `isActive` covers the window between the last
                            // suspension point and this line: the parent scope
                            // can be cancelled at any moment, and a refresh
                            // started from a cancelled scope never reaches its
                            // own `finally` (stuck spinner again).
                            if (isActive) openAutoRemoteList()
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

    // [fix-minisbak-import] 加密 .minisbak 的口令输入。只在 manifest 里确实
    // 有 encryption 段时才出现；明文包不打断用户。
    pendingMinisbakBytes?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingMinisbakBytes = null; passphraseText = "" },
            title = { Text(stringResource(R.string.backup_passphrase_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.backup_passphrase_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.padding(top = 12.dp))
                    OutlinedTextField(
                        value = passphraseText,
                        onValueChange = { passphraseText = it },
                        label = { Text(stringResource(R.string.backup_passphrase_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = passphraseText.isNotEmpty(),
                    onClick = {
                        val pw = passphraseText.toCharArray()
                        pendingMinisbakBytes = null
                        passphraseText = ""
                        // [fix-minisbak-import] 转换与恢复交接统一走
                        // convertAndRestore：门禁、互斥锁、pw 清零都在那里收口。
                        // 根因回顾：原先在这里预置 operationBusy = true，而
                        // restoreWithSnapshot 的互斥守卫见 busy 即拒（
                        // backup_err_busy）——解密成果被"备份或恢复正在进行中"
                        // 整个吞掉，加密包导入永远到不了恢复那一步。
                        convertAndRestore(pending, pw)
                    },
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingMinisbakBytes = null; passphraseText = "" }) {
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
                        // [meta-audit-1028 D4 F-02] Claim the gate BEFORE the read, not
                        // after it: `file.readText()` is the same tens-of-MB heap
                        // resident as a WebDAV download, and the row this dialog was
                        // opened from (`onClick = { snapshotRestoreTarget = file }`) is
                        // still on screen behind it — a second row could be tapped while
                        // the first file was in memory. The claim is handed to
                        // restoreWithSnapshot in the same Main frame as the read result,
                        // so a read that succeeds can never be refused by the gate it
                        // was waiting on.
                        if (!claimRestorePayload()) return@TextButton
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
                            withContext(Dispatchers.Main) {
                                if (content != null) {
                                    restoreWithSnapshot(content, true)
                                } else {
                                    releaseRestorePayload()
                                    errorMessage = errRead
                                }
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
                    // [review D-4] Secret-less restores hit more than
                    // providers: MCP headers/env/args and envVar values are
                    // blanked too (sweep1013-D1/F-4 posture). A pure-MCP or
                    // env-var restore from an auto backup previously showed
                    // NO refill hint — the blanked values looked like
                    // successful config. One line, shown whenever anything
                    // credential-bearing was restored from a secret-less
                    // payload.
                    if (!report.hadSecrets && report.providersImported == 0 &&
                        (report.mcpServersImported > 0 || report.envVarsImported > 0)
                    ) {
                        Text(
                            stringResource(R.string.backup_done_no_keys_mcp),
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
                            if (snap != null && claimRestorePayload()) {
                                // [audit-0909 T6-M3] Same off-main-thread read
                                // as the snapshot-restore dialog above
                                // (audit-0908): auto-backup snapshots embed the
                                // artifact zip and can be tens of MB, and this
                                // one sits on the "restore just failed, roll me
                                // back NOW" path — freezing it is the worst
                                // possible moment. Last sibling of the trio.
                                // [meta-audit-1028 D4 F-02] Claimed before the
                                // read like every other restore payload: this
                                // button is reachable while a WebDAV download is
                                // still in flight, which is exactly the two-payload
                                // heap the gate exists to prevent.
                                scope.launch {
                                    val content = withContext(Dispatchers.IO) {
                                        runCatching { snap.readText() }.getOrNull()
                                    }
                                    withContext(Dispatchers.Main) {
                                        if (content != null) {
                                            restoreWithSnapshot(content, true)
                                        } else {
                                            releaseRestorePayload()
                                            errorMessage = errRead
                                        }
                                    }
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
    // [da1034-B4] The four form states are seeded from `initial`, which arrives
    // ASYNCHRONOUSLY: the parent starts from `null` and fills it in from
    // `webDavStore.load()` inside a LaunchedEffect ([deep-audit-1033 I08] — the
    // load used to run inline in `remember { }`, i.e. on the Main thread). A
    // keyless `remember` therefore froze the boxes at whatever `initial` held on
    // the FIRST composition: opening the dialog in the null window showed four
    // empty fields, and Save wrote those empties back over the real config. Same
    // shape as the 1028-C form states and the 1033-A dialog seeds — every state
    // seeded from `initial` re-seeds when `initial` moves. `testing` /
    // `testResult` / `confirmClearPassword` derive from nothing and stay keyless.
    var url by remember(initial) { mutableStateOf(initial?.url.orEmpty()) }
    var username by remember(initial) { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember(initial) { mutableStateOf(initial?.password.orEmpty()) }
    var path by remember(initial) {
        mutableStateOf(initial?.path ?: WebDavConfig.DEFAULT_BACKUP_DIR)
    }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var confirmClearPassword by remember { mutableStateOf(false) }

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
                    if (password.isBlank() && !initial?.password.isNullOrBlank()) {
                        // A blank field is the only way to remove the stored
                        // password (WebDavConfigStore.save treats blank as
                        // "clear"), so make that explicit before saving.
                        confirmClearPassword = true
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

    if (confirmClearPassword) {
        AlertDialog(
            onDismissRequest = { confirmClearPassword = false },
            title = { Text(stringResource(R.string.webdav_clear_pw_title)) },
            text = { Text(stringResource(R.string.webdav_clear_pw_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearPassword = false
                    // url/username were validated before the prompt; the
                    // blank password is the deliberate "clear" action.
                    onSave(
                        WebDavConfig(
                            url = url,
                            username = username,
                            password = "",
                            path = path,
                        )
                    )
                }) {
                    Text(stringResource(R.string.webdav_clear_pw_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearPassword = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
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

/** [fix-minisbak-import] .minisbak 嗅探的两路结果（在 IO 线程上判定）。 */
private sealed interface MinisbakImport {
    /** 包已加密，需要先向用户要口令再转换。 */
    data object Encrypted : MinisbakImport

    /** 明文包（或口令确认后），[json] 是转换完成的 RikkaMinis 备份文档。 */
    data class Plain(val json: String) : MinisbakImport
}
