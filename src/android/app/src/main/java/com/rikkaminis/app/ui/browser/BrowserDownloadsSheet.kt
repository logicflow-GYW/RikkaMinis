package com.rikkaminis.app.ui.browser

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.rikkaminis.app.R
import com.rikkaminis.app.browser.BrowserTabPool
import com.rikkaminis.app.ui.theme.ChatColors
import com.rikkaminis.app.util.ExternalIntent

/**
 * [T-android-browser-download-ux] Downloads panel — Android port of iOS
 * BrowserDownloadPanelSheet (T-browser-download-ux v2/v3): entries
 * newest-first; in-flight rows show live progress + a cancel button;
 * completed rows open the file via ACTION_VIEW (the Android "Show in Files"
 * equivalent — same FileProvider path FilePreviewScreen uses); failed rows
 * show the reason. "Clear" drops ALL finished records (completed AND failed,
 * v3 semantics); in-flight rows stay. Opening the panel marks terminal
 * entries seen, which zeroes the toolbar badge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserDownloadsSheet(
    tabPool: BrowserTabPool,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val downloads by tabPool.downloads.collectAsState()

    // Terminal entries stop counting toward the badge once the user has
    // actually looked at the panel.
    //
    // [dv15-B3] ...and that must hold for as long as the panel is open, not
    // only at the instant it opens. Before: a one-shot `LaunchedEffect(Unit)`
    // marked whatever was terminal right then; a download that finished while
    // the panel was up stayed `seen = false`, so the toolbar badge lit up
    // behind the open panel and stayed lit after closing it (until the next
    // open). The effect now re-runs whenever a pass is owed — the decision is
    // pure and JVM-tested (`pendingDownloadSeenPass`) — and every pass is the
    // same idempotent `markDownloadsSeen()`, so a list with nothing left to
    // mark yields an equal list and cannot loop.
    val needsSeenPass = remember(downloads) { pendingDownloadSeenPass(downloads) }
    LaunchedEffect(needsSeenPass) { if (needsSeenPass) tabPool.markDownloadsSeen() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.browser_downloads_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (downloads.any { it.state != BrowserTabPool.DownloadState.DOWNLOADING }) {
                    TextButton(onClick = { tabPool.clearFinishedDownloads() }) {
                        Text(stringResource(R.string.browser_downloads_clear))
                    }
                }
            }
            if (downloads.isEmpty()) {
                Text(
                    stringResource(R.string.browser_downloads_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn {
                    items(downloads, key = { it.id }) { entry ->
                        DownloadRow(
                            entry = entry,
                            onCancel = { tabPool.cancelDownload(entry.id) },
                            onOpen = { openDownloadedFile(context, entry) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    entry: BrowserTabPool.DownloadEntry,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        val (icon, tint) = when (entry.state) {
            BrowserTabPool.DownloadState.DOWNLOADING ->
                Icons.Default.Download to MaterialTheme.colorScheme.primary
            BrowserTabPool.DownloadState.COMPLETED ->
                Icons.Default.CheckCircle to ChatColors.success
            BrowserTabPool.DownloadState.FAILED ->
                Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // Middle truncation keeps the extension visible (iOS v3).
                BrowserTabPool.middleTruncated(entry.filename, head = 24, tail = 12),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            when (entry.state) {
                BrowserTabPool.DownloadState.DOWNLOADING -> {
                    val context = LocalContext.current
                    val doneText = android.text.format.Formatter
                        .formatShortFileSize(context, entry.bytesDone)
                    val statusText = if (entry.totalBytes > 0) {
                        val totalText = android.text.format.Formatter
                            .formatShortFileSize(context, entry.totalBytes)
                        val pct = (entry.bytesDone * 100 / entry.totalBytes).toInt()
                        "$pct% · $doneText / $totalText"
                    } else {
                        doneText
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (entry.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = {
                                (entry.bytesDone.toFloat() / entry.totalBytes).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                BrowserTabPool.DownloadState.COMPLETED -> {
                    val context = LocalContext.current
                    Text(
                        android.text.format.Formatter
                            .formatShortFileSize(context, entry.bytesDone),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BrowserTabPool.DownloadState.FAILED -> {
                    Text(
                        entry.failureReason
                            ?: stringResource(R.string.browser_downloads_failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        when (entry.state) {
            BrowserTabPool.DownloadState.DOWNLOADING -> {
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Default.Cancel,
                        contentDescription = stringResource(R.string.browser_downloads_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            BrowserTabPool.DownloadState.COMPLETED -> {
                IconButton(onClick = onOpen) {
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = stringResource(R.string.browser_downloads_open),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            BrowserTabPool.DownloadState.FAILED -> Unit
        }
    }
}

/** ACTION_VIEW via FileProvider — same pattern as FilePreviewScreen's
 *  openExternally; the `minis-sessions/` root is a declared provider path. */
private fun openDownloadedFile(context: Context, entry: BrowserTabPool.DownloadEntry) {
    val file = entry.destination ?: return
    if (!file.exists()) return
    try {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        val ext = file.extension.lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // [deep-audit-1049 B-03] Shared launcher: NEW_TASK moves onto the
        // chooser (the intent that is actually started — a chooser does not
        // inherit the inner intent's flags), and the failure is no longer a
        // logcat line the user cannot see.
        ExternalIntent.share(context, intent, null) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.filepreview_no_app),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    } catch (e: Exception) {
        android.util.Log.w("BrowserDownloads", "open failed: ${e.message}")
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.filepreview_no_app),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}

/**
 * [dv15-B3] Does the open panel owe the store a "mark terminal entries seen"
 * pass for this list?
 *
 * True iff some terminal entry is still unseen. Top-level and free of
 * Compose/Android on purpose: this is the decision that was missing — the
 * panel used to make exactly one pass, at open time — so it is the part a JVM
 * test can run for real (`BrowserDownloadsSeenTest`), instead of asserting on
 * source text.
 *
 * In-flight entries are ignored: they are not terminal, `markDownloadsSeen()`
 * would not touch them, and keying on them would re-run the pass on every
 * progress byte.
 */
internal fun pendingDownloadSeenPass(downloads: List<BrowserTabPool.DownloadEntry>): Boolean =
    downloads.any { it.state != BrowserTabPool.DownloadState.DOWNLOADING && !it.seen }
