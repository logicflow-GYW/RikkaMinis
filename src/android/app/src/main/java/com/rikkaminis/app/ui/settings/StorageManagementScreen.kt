package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.R
import com.rikkaminis.app.data.storage.SessionFileStore
import com.rikkaminis.app.ui.components.MinisTextButton

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.rikkaminis.app.data.db.ChatDao
import com.rikkaminis.app.data.db.ChatSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import com.rikkaminis.app.ui.theme.ChatColors

private data class SessionStorageInfo(
    val id: String,
    val title: String?,
    val sessionDirSize: Long,
    val mediaSize: Long,
    val topSubdir: Pair<String, Long>?, // largest component inside session dir, e.g. workspace→1.9GB
) {
    val minisSize: Long get() = sessionDirSize
    val totalSize: Long get() = sessionDirSize + mediaSize
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageManagementScreen(
    chatRepository: com.rikkaminis.app.data.repository.ChatRepository,
    onBack: () -> Unit,
    onRootfsClick: () -> Unit,
    onSessionClick: (sessionId: String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionFiles = remember { SessionFileStore(context) }

    var isSizingSessions by remember { mutableStateOf(true) }
    var shellSize by remember { mutableLongStateOf(0L) }
    var shellBreakdown by remember { mutableStateOf<List<com.rikkaminis.app.sandbox.RootfsUsageScanner.Entry>>(emptyList()) }
    var dbSize by remember { mutableLongStateOf(0L) }
    var sessionCount by remember { mutableStateOf(0) }
    var sessions by remember { mutableStateOf<List<SessionStorageInfo>>(emptyList()) }

    // [B: orphan reclamation] Auto-scanned on entry; surfaced as a banner that
    // the user explicitly confirms before anything is deleted. Never silent.
    var orphanInfo by remember {
        mutableStateOf<SessionFileStore.ReclaimReport?>(null)
    }
    var isScanningOrphans by remember { mutableStateOf(false) }
    var isReclaiming by remember { mutableStateOf(false) }
    var showReclaimDialog by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            // Reset state so a re-entry / manual reload shows a fresh skeleton.
            isSizingSessions = true
            sessions = emptyList()
            // [B] fresh scan every reload so the banner reflects reality.
            isScanningOrphans = true
            orphanInfo = null
            withContext(Dispatchers.IO) {
                // [rootfs-usage-v1] Real on-disk footprint (lstat + st_blocks,
                // no symlink following, hardlink dedupe). The old recursive
                // walkTopDown()+length() double-counted versioned .so symlinks
                // and followed symlinked dirs (e.g. default-jvm), overstating
                // the "Terminal Shell" row by ~50%+.
                val report = com.rikkaminis.app.sandbox.RootfsUsageScanner.scan(
                    File(context.filesDir, "alpine-rootfs"),
                    com.rikkaminis.app.sandbox.RootfsUsageScanner.androidStat(),
                )
                shellSize = report.totalBytes
                // Only surface buckets that can actually explain a large
                // footprint. A rootfs has ~15 top-level dirs and most are
                // filesystem scaffolding (/bin, /etc, /run, /srv … a few KB
                // to 1 MB); listing them all buries the two that matter
                // (/tmp scratch files and /usr installed packages).
                shellBreakdown = report.entries
                    .filter { it.bytes >= 8L * 1024 * 1024 }
                    .take(8)
                dbSize = databaseSize(context)

                val allSessions = chatRepository.dao.listSessions()
                val liveIds = allSessions.map { it.id }.toSet()
                val mediaSizes = sessionFiles.mediaSizesBySessionBrief(liveIds)
                sessionCount = allSessions.size

                // [B] Scan for leftover dirs whose session no longer exists
                // (measure only — nothing is deleted until the user confirms).
                orphanInfo = sessionFiles.scanOrphans(liveIds)

                // Size every session directory in parallel (async) instead of
                // the previous serial map, so the whole list appears roughly
                // as fast as the slowest single session. Bound the fan-out with
                // a Semaphore: hundreds of sessions would otherwise launch an
                // equal number of concurrent recursive walks (one per IO thread
                // up to the dispatcher pool), causing a sizing IO storm on
                // entry. Capping at 8 keeps the list fast without saturating IO.
                sessions = coroutineScope {
                    val gate = Semaphore(8)
                    allSessions.map { session ->
                        async {
                            gate.withPermit {
                                val subdirs = sessionFiles.sessionSubdirSizes(session.id)
                                val top = subdirs.maxByOrNull { it.value }?.toPair()
                                SessionStorageInfo(
                                    id = session.id,
                                    title = session.title,
                                    sessionDirSize = subdirs.values.sum(),
                                    mediaSize = mediaSizes[session.id] ?: 0L,
                                    topSubdir = top?.takeIf { it.second > 0 },
                                )
                            }
                        }
                    }.awaitAll()
                }.sortedByDescending { it.totalSize }
            }
            isScanningOrphans = false
            isSizingSessions = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    val totalSessionSize = sessions.sumOf { it.totalSize }

    // top-level page: rely on system back gesture / bottom nav (no back arrow)
    SettingsScaffold(title = stringResource(R.string.storage_title), onBack = null) {
        SettingsSection(header = stringResource(R.string.storage_section_overview)) {
            StorageOverviewRow(
                color = Color(0xFF8E8E93),
                label = stringResource(R.string.storage_overview_shell),
                value = Formatter.formatFileSize(context, shellSize),
                onClick = onRootfsClick,
                showDivider = true,
            )
            StorageOverviewRow(
                color = ChatColors.link,
                label = stringResource(R.string.storage_overview_database),
                value = Formatter.formatFileSize(context, dbSize),
                showDivider = true,
            )
            StorageOverviewRow(
                color = Color(0xFF5856D6),
                label = stringResource(R.string.storage_overview_sessions),
                value = Formatter.formatFileSize(context, totalSessionSize),
                showDivider = false,
            )
        }

        if (shellBreakdown.isNotEmpty()) {
            SettingsSection(header = stringResource(R.string.storage_section_shell_detail)) {
                shellBreakdown.forEachIndexed { index, entry ->
                    StorageOverviewRow(
                        color = ChatColors.success,
                        label = "/${entry.name}",
                        value = Formatter.formatFileSize(context, entry.bytes),
                        showDivider = index != shellBreakdown.lastIndex,
                    )
                }
            }
        }

        // [B: orphan reclamation] Auto-scanned banner. Discovery is automatic,
        // deletion is always an explicit user confirmation — never silent.
        if (orphanInfo != null && orphanInfo!!.totalBytes > 0) {
            SettingsSection(header = stringResource(R.string.storage_section_orphans)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isScanningOrphans && !isReclaiming) {
                            showReclaimDialog = true
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(
                            R.string.storage_orphan_banner,
                            orphanInfo!!.totalDirs,
                            Formatter.formatFileSize(context, orphanInfo!!.totalBytes),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    if (!isScanningOrphans && !isReclaiming) {
                        Text(
                            stringResource(R.string.storage_reclaim_button),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }

        SettingsSection(header = stringResource(R.string.storage_section_sessions)) {
            when {
                // Phase 2 still loading: show skeleton rows matching the
                // known session count so the user sees the page structure
                // immediately instead of a spinner.
                isSizingSessions && sessionCount > 0 -> {
                    val skeletonAlpha = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                    sessionCount.coerceAtMost(20).let { count ->
                        // Show at most 20 skeletons so a user with hundreds
                        // of sessions doesn't scroll forever before the real
                        // data arrives.
                        repeat(count) { index ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(16.dp)
                                        .clip(MaterialTheme.shapes.small)
                                        .background(skeletonAlpha),
                                )
                                Spacer(Modifier.width(12.dp))
                                Box(
                                    modifier = Modifier
                                        .width(64.dp)
                                        .height(16.dp)
                                        .clip(MaterialTheme.shapes.small)
                                        .background(skeletonAlpha),
                                )
                            }
                            if (index < count - 1) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(0.5.dp)
                                        .padding(start = 16.dp)
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)),
                                )
                            }
                        }
                    }
                }
                isSizingSessions -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                sessions.isEmpty() -> Text(
                    stringResource(R.string.storage_no_sessions),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
                else -> sessions.forEachIndexed { index, session ->
                    val valueLabel = buildString {
                        append(Formatter.formatFileSize(context, session.totalSize))
                        val top = session.topSubdir
                        if (top != null) {
                            val ratio = if (session.totalSize > 0) top.second.toFloat() / session.totalSize else 0f
                            if (ratio >= 0.15f) {
                                append(" (${top.first}: ${Formatter.formatFileSize(context, top.second)})")
                            }
                        }
                    }
                    SettingsValueRow(
                        title = session.title ?: "Untitled",
                        value = valueLabel,
                        onClick = { onSessionClick(session.id) },
                        showDivider = index < sessions.size - 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showReclaimDialog) {
        AlertDialog(
            onDismissRequest = { showReclaimDialog = false },
            title = { Text(stringResource(R.string.storage_reclaim_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.storage_reclaim_confirm_text,
                        orphanInfo?.totalDirs ?: 0,
                        Formatter.formatFileSize(context, orphanInfo?.totalBytes ?: 0L),
                    ),
                )
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    showReclaimDialog = false
                    isReclaiming = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val live = chatRepository.dao.listSessions().map { it.id }.toSet()
                            sessionFiles.reclaimOrphans(live)
                        }
                        orphanInfo = null
                        isReclaiming = false
                        reload()
                    }
                }) {
                    Text(stringResource(R.string.storage_reclaim_confirm_button))
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showReclaimDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionStorageDetailScreen(
    sessionId: String,
    chatDao: ChatDao,
    onBack: () -> Unit,
    onBrowseFiles: (rootPath: String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionFiles = remember { SessionFileStore(context) }

    var session by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var minisSize by remember { mutableLongStateOf(0L) }
    var mediaSize by remember { mutableLongStateOf(0L) }
    var isClearing by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            withContext(Dispatchers.IO) {
                session = chatDao.getSession(sessionId)
                minisSize = sessionFiles.sizeOf(sessionFiles.sessionDir(sessionId))
                mediaSize = sessionFiles.mediaSize(sessionId)
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    val totalSize = minisSize + mediaSize
    val hasFiles = totalSize > 0

    SettingsScaffold(title = session?.title ?: "Session", onBack = onBack) {
        SettingsSection(header = stringResource(R.string.storage_section_minis_files)) {
            if (minisSize > 0) {
                SettingsValueRow(
                    title = stringResource(R.string.storage_browse_files),
                    value = Formatter.formatFileSize(context, minisSize),
                    onClick = {
                        onBrowseFiles(sessionFiles.sessionDir(sessionId).absolutePath)
                    },
                    valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    showDivider = false,
                )
            } else {
                Text(
                    stringResource(R.string.storage_no_minis_files),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        SettingsSection(header = stringResource(R.string.storage_section_media)) {
            if (mediaSize > 0) {
                SettingsValueRow(
                    title = "Media",
                    value = Formatter.formatFileSize(context, mediaSize),
                    showDivider = false,
                )
            } else {
                Text(
                    stringResource(R.string.storage_no_media_files),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        SettingsSection(
            footer = stringResource(R.string.storage_clear_session_footer),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = hasFiles && !isClearing) { showClearDialog = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isClearing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.storage_clearing_status),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        stringResource(R.string.storage_clear_session_button),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (hasFiles) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.weight(1f),
                    )
                    if (hasFiles) {
                        Text(
                            Formatter.formatFileSize(context, totalSize),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.storage_clear_confirm_title)) },
            text = {
                Text(stringResource(R.string.storage_clear_confirm_text, Formatter.formatFileSize(context, totalSize)))
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    showClearDialog = false
                    isClearing = true
                    scope.launch {
                        // [Bug 4] Re-measure from disk after delete instead of
                        // blindly zeroing — if a file was held open (or a mount
                        // read-only) and deleteRecursively() partially failed,
                        // the sizes stay truthful rather than showing a fake 0 B.
                        withContext(Dispatchers.IO) {
                            sessionFiles.deleteSessionFiles(sessionId)
                            minisSize = sessionFiles.sizeOf(sessionFiles.sessionDir(sessionId))
                            mediaSize = sessionFiles.mediaSize(sessionId)
                        }
                        isClearing = false
                    }
                }) {
                    Text(
                        "Clear ${Formatter.formatFileSize(context, totalSize)}",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun StorageOverviewRow(
    color: Color,
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(21.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onClick != null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (showDivider) {
            val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
                    .height(0.5.dp)
                    .background(divider),
            )
        }
    }
}

private fun databaseSize(context: Context): Long {
    val dbFile = context.getDatabasePath("minis.db")
    var size = if (dbFile.exists()) dbFile.length() else 0L
    val wal = File(dbFile.path + "-wal")
    val shm = File(dbFile.path + "-shm")
    if (wal.exists()) size += wal.length()
    if (shm.exists()) size += shm.length()
    return size
}
