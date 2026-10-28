package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.R
import com.rikkaminis.app.ui.components.MinisTextButton

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.rikkaminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile

/**
 * Log management screen — adopts the SettingsSection/SettingsRow toolkit
 * shared with the rest of Settings (T81). Sections:
 *   - Logging — Enable Logging switch + footer explaining capture
 *   - Log Files — daily files list (each row → LogDetailScreen)
 *   - Storage — Delete All Logs (only when files exist) + total-size footer
 *
 * Mirrors iOS LogManagementView's grouped-card layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogManagementScreen(
    onBack: () -> Unit,
    onLogFileClick: (fileName: String) -> Unit = {},
) {
    val context = LocalContext.current
    // Delete-all runs clearLogs() (writer close + directory scan + unlink of
    // every daily/crash file) plus a directory rescan. Both are host I/O and
    // must not sit on the Main thread — `readonly_mount_write_guard.py` pins
    // the call into Dispatchers.IO [deep-audit-1025 ISSUE-08].
    val scope = rememberCoroutineScope()
    // T-logs-perf: capped, separated daily/crash lists. Pre-T-logs-perf the
    // screen called AppLogger.listLogFiles() + totalSize() synchronously on
    // the main thread inside a LaunchedEffect, AND each row called
    // file.length() on every recomposition. With ~14 daily logs + dozens
    // of crash files in long-running installs the directory scan + per-row
    // stats stalled the screen for noticeable seconds. Now: stat once on
    // Dispatchers.IO, cap each kind at 100, render LogFileMeta directly
    // (no further stat calls).
    val dailyLogs = remember { mutableStateListOf<AppLogger.LogFileMeta>() }
    val crashLogs = remember { mutableStateListOf<AppLogger.LogFileMeta>() }
    var totalSize by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    var loggingEnabled by remember { mutableStateOf(AppLogger.isEnabled(context)) }

    // [da1028-B / ISSUE-04] Re-read the switch when `logs.enabled` is written
    // OUTSIDE this screen — `minis-config set logs.enabled …` (ConfigBuiltins
    // registers the same prefs file + key via AppLogger) or a backup-restore.
    // Without it the Switch keeps rendering the value captured at open time and
    // the user then toggles from a stale state. Same shape as
    // MemoryManagementScreen's memory.global.enabled listener; the prefs file
    // is process-scoped, so onDispose must unregister.
    DisposableEffect(context) {
        val prefs = context.applicationContext.getSharedPreferences(
            AppLogger.PREF_NAME,
            Context.MODE_PRIVATE,
        )
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == AppLogger.KEY_ENABLED) {
                    loggingEnabled = AppLogger.isEnabled(context)
                }
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val refreshTrigger = remember { mutableStateOf(0) }
    LaunchedEffect(refreshTrigger.value) {
        loading = true
        // Daily logs are named `minis-YYYY-MM-DD.log`. Crash files are
        // `crash-…` (Java/Kotlin) and `native-crash-…` (NDK signal handler);
        // grouped together as one "crash" list so the user sees one
        // chronological stream regardless of which side trapped the fault.
        // 100 of each is far past any reasonable inspection horizon.
        // ponytail: 列表只列 minis-/crash-/native-crash- 三类；debug-<date>.log
        // 与 error-snapshot-*.log（T-logging-full-coverage 新增通道）在磁盘上
        // 但不在此页可见，要看需走文件管理或调试 RPC。| 天花板: 用户想在设置
        // 里直接翻 debug/快照文件时失效。| 升级触发: 用户提出"设置里看不到
        // debug 日志"，或需要新增分区标题（要动 7+1 语言文件）。
        val (daily, crash, total) = withContext(Dispatchers.IO) {
            val d = AppLogger.listLogFileMetas(prefix = "minis-", limit = 100)
            val cJ = AppLogger.listLogFileMetas(prefix = "crash-", limit = 100)
            val cN = AppLogger.listLogFileMetas(prefix = "native-crash-", limit = 100)
            // [meta-audit-1028 E5 / B06 F-09] Merge Java + native crashes by the
            // REAL timestamp (the mtime captured by listLogFileMetas), never by
            // file name: the two families do not share a prefix (`crash-…` vs
            // `native-crash-…`) nor a stamp format (local `_` vs UTC `T…Z`), so
            // a name sort put every native report above every Java one and the
            // cap then dropped the newest Java crashes first. Pure helper below
            // (JVM-tested) so the ordering + cap are pinned without a device.
            val merged = newestFirstCapped(
                cJ + cN,
                limit = 100,
                nameOf = { it.name },
                modifiedOf = { it.lastModified },
            )
            // totalSize sums only the displayed rows. Older / over-cap files
            // are still on disk but excluded from the cap-100 view; the
            // footer wording matches what the user actually sees here.
            val sum = (d.sumOf { it.sizeBytes }) + (merged.sumOf { it.sizeBytes })
            Triple(d, merged, sum)
        }
        dailyLogs.clear(); dailyLogs.addAll(daily)
        crashLogs.clear(); crashLogs.addAll(crash)
        totalSize = total
        loading = false
    }
    fun refresh() { refreshTrigger.value++ }

    SettingsScaffold(
        title = stringResource(R.string.log_title),
        onBack = null, // top-level page: rely on system back gesture / bottom nav
        scrollable = false,
    ) {
        LogsBody(
            context = context,
            dailyLogs = dailyLogs,
            crashLogs = crashLogs,
            totalSize = totalSize,
            loading = loading,
            loggingEnabled = loggingEnabled,
            onToggleLogging = {
                loggingEnabled = it
                AppLogger.setEnabled(context, it)
            },
            onLogFileClick = onLogFileClick,
            onDeleteAll = { showDeleteAllConfirm = true },
        )
    }

    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = { Text(stringResource(R.string.log_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.log_delete_confirm_text, Formatter.formatFileSize(context, totalSize)))
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    scope.launch {
                        // [deep-audit-1025 ISSUE-08] clearLogs() closes the
                        // writers and unlinks every log file; on a long-running
                        // install that is a directory listing plus up to ~100
                        // unlinks — a Main-thread freeze for the duration.
                        withContext(Dispatchers.IO) { AppLogger.clearLogs() }
                        refresh()
                        showDeleteAllConfirm = false
                    }
                }) {
                    Text(stringResource(R.string.log_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showDeleteAllConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** Logs body — the LogManagementScreen content. */
@Composable
private fun LogsBody(
    context: Context,
    dailyLogs: List<AppLogger.LogFileMeta>,
    crashLogs: List<AppLogger.LogFileMeta>,
    totalSize: Long,
    loading: Boolean,
    loggingEnabled: Boolean,
    onToggleLogging: (Boolean) -> Unit,
    onLogFileClick: (fileName: String) -> Unit,
    onDeleteAll: () -> Unit,
) {
    val anyFiles = dailyLogs.isNotEmpty() || crashLogs.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        // ── Logging ─────────────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.log_section_logging),
            footer = stringResource(R.string.log_section_logging_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.log_enable_switch),
                checked = loggingEnabled,
                onCheckedChange = onToggleLogging,
                showDivider = false,
            )
        }

        // While the IO load is in flight (single frame in practice, but the
        // dir scan + 100×stat can spike on slow eMMC), show a centered
        // spinner instead of the empty-state row — otherwise the screen
        // briefly reads as "no logs yet" before the real list pops in.
        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Column
        }

        // ── Log Files (daily) ───────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.log_section_files),
            footer = stringResource(R.string.log_section_files_footer),
        ) {
            if (dailyLogs.isEmpty()) {
                SettingsValueRow(
                    title = stringResource(R.string.log_no_files),
                    value = "",
                    valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    showDivider = false,
                )
            } else {
                dailyLogs.forEachIndexed { index, meta ->
                    SettingsValueRow(
                        title = meta.name,
                        value = Formatter.formatFileSize(context, meta.sizeBytes),
                        onClick = { onLogFileClick(meta.name) },
                        showDivider = index < dailyLogs.size - 1,
                    )
                }
            }
        }

        // ── Crash Logs (T283) — only show when present ──────────────────
        if (crashLogs.isNotEmpty()) {
            SettingsSection(
                header = stringResource(R.string.log_section_crash),
                footer = stringResource(R.string.log_section_crash_footer, crashLogs.size),
            ) {
                crashLogs.forEachIndexed { index, meta ->
                    SettingsValueRow(
                        title = meta.name,
                        value = Formatter.formatFileSize(context, meta.sizeBytes),
                        onClick = { onLogFileClick(meta.name) },
                        showDivider = index < crashLogs.size - 1,
                    )
                }
            }
        }

        // ── Storage / Delete All (only when files exist) ───────────────
        if (anyFiles) {
            SettingsSection(
                header = stringResource(R.string.log_section_storage),
                footer = stringResource(R.string.log_section_storage_footer, Formatter.formatFileSize(context, totalSize)),
            ) {
                SettingsRow(
                    title = stringResource(R.string.log_delete_all_title),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = onDeleteAll,
                    showChevron = false,
                    showDivider = false,
                    trailing = {
                        Text(
                            Formatter.formatFileSize(context, totalSize),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─── Log Detail Screen ─────────────────────────────────────────────────────────

// ─── [sweep1014-B35] raw log line decoding ────────────────────────────────────

/**
 * Per-line render cap for the log viewer. A pathological single-line log
 * (no '\n' for megabytes) must not be materialised and handed to a
 * LazyColumn row.
 */
internal const val MAX_LOG_LINE_BYTES = 16 * 1024

/** Marker appended to a line that hit [MAX_LOG_LINE_BYTES]. */
internal const val LOG_LINE_TRUNCATED_SUFFIX = " …[truncated]"

/**
 * Decode one raw log line as UTF-8. [raw] holds the line's bytes up to (but
 * excluding) its terminating '\n'; [truncated] says the line hit
 * [MAX_LOG_LINE_BYTES] and was cut short.
 *
 * [sweep1014-B35] Kept pure so a JVM harness can pin the boundary behaviour:
 * the old reader decoded every 8KB chunk on its own
 * (`String(buf, 0, n, UTF_8)`), so any multi-byte character straddling a
 * chunk boundary — routine in the CJK message bodies this viewer exists to
 * show — was emitted as U+FFFD on both halves. Decoding the whole line once
 * is what makes that impossible.
 */
internal fun decodeLogLine(raw: ByteArray, truncated: Boolean): String {
    val bytes = if (truncated && raw.size > MAX_LOG_LINE_BYTES) {
        raw.copyOf(MAX_LOG_LINE_BYTES)
    } else {
        raw
    }
    val text = String(bytes, Charsets.UTF_8)
    return if (truncated) text + LOG_LINE_TRUNCATED_SUFFIX else text
}

/**
 * T176: lazy line-index loader for the log detail viewer. The previous
 * `file.readText()` approach allocated the entire file as a single
 * String + a single `Text` Composable, which ANR'd / OOM'd at the user's
 * 33MB log. Strategy:
 *
 *  1. One pass through the file to record byte-offset of every newline.
 *     Yields a `LongArray` of ~30-50 K entries for a 33MB log
 *     (≈ 4MB heap), well below any practical limit.
 *  2. `readLine(i)` seeks to `offsets[i]` and reads a single line on
 *     demand. Cached `RandomAccessFile` so each lookup is one I/O.
 *  3. `LazyColumn` renders only viewport-visible rows + overscan, so
 *     scroll is bounded by the visible rows count, not the file size.
 *  4. `DisposableEffect.onDispose` closes the RAF when the user leaves
 *     the screen so we don't leak descriptors across navigations.
 *
 * Trade-off: the indexing pass IS a full file read, but it's
 * sequential bytewise and runs on Dispatchers.IO — measured ~200-400 ms
 * for 33MB on Pixel 4a, vs the old `readText()` path which never
 * recovered.
 */
private class LazyLogFile private constructor(
    private val raf: RandomAccessFile,
    val lineOffsets: LongArray,
) {
    val lineCount: Int get() = lineOffsets.size

    @Synchronized
    fun readLine(index: Int): String {
        if (index !in 0 until lineCount) return ""
        return try {
            raf.seek(lineOffsets[index])
            // [sweep1014-B35] Collect the whole line's raw bytes first and
            // decode UTF-8 exactly once (see decodeLogLine): RAF.readLine
            // reads bytes, so non-ASCII (CJK) gets mangled, and decoding
            // chunk-by-chunk mangled it a second time at chunk boundaries.
            val raw = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var truncated = false
            while (true) {
                val n = raf.read(buf)
                if (n <= 0) break
                var newlineAt = -1
                for (i in 0 until n) {
                    if (buf[i] == '\n'.code.toByte()) {
                        newlineAt = i
                        break
                    }
                }
                if (newlineAt >= 0) {
                    raw.write(buf, 0, newlineAt)
                    break
                }
                raw.write(buf, 0, n)
                if (raw.size() >= MAX_LOG_LINE_BYTES) {
                    truncated = true
                    break
                }
            }
            decodeLogLine(raw.toByteArray(), truncated)
        } catch (e: Exception) {
            ""
        }
    }

    fun close() = try { raf.close() } catch (_: Exception) {}

    companion object {
        fun open(file: File): LazyLogFile {
            val raf = RandomAccessFile(file, "r")
            // First-line offset is always 0; subsequent offsets sit
            // immediately after each newline. Dynamic LongArray growth
            // via mutableList is fine — 30-50 K Long boxings is a few MB
            // of churn that the GC handles easily.
            val offsets = mutableListOf(0L)
            val buf = ByteArray(64 * 1024)
            var pos = 0L
            while (true) {
                val n = raf.read(buf)
                if (n <= 0) break
                for (i in 0 until n) {
                    if (buf[i] == '\n'.code.toByte()) {
                        offsets.add(pos + i + 1)
                    }
                }
                pos += n
            }
            // Drop a trailing empty offset if the file ends with \n
            // (the last "line" past the final newline is empty, but
            // logs typically end with a newline so this prevents
            // showing a blank ghost row at the bottom).
            val arr = if (offsets.size > 1 && offsets.last() >= raf.length()) {
                offsets.dropLast(1).toLongArray()
            } else {
                offsets.toLongArray()
            }
            // Reset cursor — readLine will seek per call.
            raf.seek(0)
            return LazyLogFile(raf, arr)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogDetailScreen(
    fileName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lazyLog by remember(fileName) { mutableStateOf<LazyLogFile?>(null) }
    var loadError by remember(fileName) { mutableStateOf<String?>(null) }
    var loading by remember(fileName) { mutableStateOf(true) }

    LaunchedEffect(fileName) {
        loading = true
        loadError = null
        // [fix/audit-b20 / T6-L3] LazyLogFile.open() indexes the whole file
        // (200-400ms) and is not interruptible. If this effect is cancelled
        // while it runs (fileName changed / screen left), withContext throws on
        // the way out and the handle never reaches lazyLog — so its file
        // descriptor leaked. Hold it locally and close it on cancellation.
        var opened: LazyLogFile? = null
        // [deep-audit-1048 B-01] IO computes, Main publishes: the stat + open
        // stay on Dispatchers.IO, and the two Compose states are written after
        // `withContext` returns (on the composition's dispatcher) instead of
        // from inside the IO block, which had no happens-before with the frame.
        val outcome = try {
            withContext(Dispatchers.IO) {
                try {
                    val file = File(File(context.filesDir, "logs"), fileName)
                    if (!file.exists()) {
                        LogOpenResult(null, context.getString(R.string.log_not_found))
                    } else {
                        val handle = LazyLogFile.open(file)
                        opened = handle
                        LogOpenResult(handle, null)
                    }
                } catch (e: Exception) {
                    LogOpenResult(null, context.getString(R.string.log_error_reading, e.message ?: ""))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            opened?.close()
            throw e
        }
        loadError = outcome.error
        lazyLog = outcome.file
        loading = false
    }

    DisposableEffect(fileName) {
        onDispose { lazyLog?.close() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // [deep-audit-1041 B-07] Both the existence stat and the
                        // prep are disk-bound; `shareLogFile` takes them to
                        // Dispatchers.IO and starts the chooser back here.
                        val file = File(File(context.filesDir, "logs"), fileName)
                        scope.launch {
                            // [deep-audit-1048 B-04] A failed share used to be
                            // silent; report it on the same toast channel the
                            // other export paths use.
                            val launched = shareLogFile(context, file)
                            if (!launched) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.file_share_failed_toast, fileName),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.common_share))
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            loadError != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        loadError ?: "",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            lazyLog != null -> {
                val log = lazyLog!!
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    items(log.lineCount) { i ->
                        Text(
                            text = log.readLine(i),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

// ─── Share Utilities ───────────────────────────────────────────────────────────

/**
 * [deep-audit-1041 B-07] Upper bound on the EXTRA_TEXT fallback body.
 *
 * The previous spelling — `file.readText().take(100_000)` — read the WHOLE file
 * into a single `String` before trimming it. This file's own KDoc a screen up
 * records what a 33 MB day of agent traffic did to that pattern in the log
 * viewer; the share fallback was still doing it here. Named constant so the
 * bound is visible where the reader is written, instead of buried in a call.
 */
internal const val LOG_SHARE_MAX_CHARS = 100_000

/**
 * [deep-audit-1048 B-01] Outcome of the off-main open in [LogDetailScreen]:
 * either a handle or the message to show. Produced on Dispatchers.IO, consumed
 * (into Compose state) after `withContext` returns.
 */
private data class LogOpenResult(val file: LazyLogFile?, val error: String?)

/**
 * Read at most [maxChars] characters of [file] for the EXTRA_TEXT fallback —
 * streamed, so the peak allocation is the cap and not the file size.
 *
 * Returns `null` when the file cannot be read at all: the outcome the old
 * `catch (_: Exception) { return }` produced (share nothing). Character-based,
 * so `maxChars` keeps the exact meaning `.take(100_000)` had for the
 * Binder-sized text payload.
 */
internal fun readLogShareText(file: File, maxChars: Int = LOG_SHARE_MAX_CHARS): String? =
    try {
        FileInputStream(file).use { input ->
            val reader = InputStreamReader(input, Charsets.UTF_8)
            val builder = StringBuilder(minOf(maxChars, 64 * 1024))
            val buffer = CharArray(8 * 1024)
            while (builder.length < maxChars) {
                val read = reader.read(buffer, 0, minOf(buffer.size, maxChars - builder.length))
                if (read <= 0) break
                builder.append(buffer, 0, read)
            }
            builder.toString()
        }
    } catch (_: Exception) {
        null
    }

/**
 * Build the share chooser for [file] off the caller's thread, or `null` when
 * there is nothing to share (file gone, or the fallback read failed).
 *
 * [deep-audit-1041 B-07] Every step here is disk-bound — `File.exists()`,
 * `FileProvider.getUriForFile` and the fallback read — so all of it runs inside
 * `withContext(Dispatchers.IO)`. Starting the chooser deliberately does NOT
 * happen here: `startActivity` must run on the caller's (Main) thread, which is
 * why this returns an Intent for [shareLogFile] to send.
 */
private suspend fun buildLogShareChooser(context: Context, file: File): Intent? =
    withContext(Dispatchers.IO) {
        // [deep-audit-1041 B-07] This existence stat used to run in the
        // composable's onClick, on Main.
        if (!file.exists()) return@withContext null
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                // Clip carries the URI in a way that downstream chooser
                // hosts can introspect for the grant when EXTRA_STREAM
                // alone isn't enough (Issue #17 — SecurityException
                // "Permission Denial: opening provider … requires the
                // provider be exported, or grantUriPermission()").
                clipData = android.content.ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, context.getString(R.string.log_share_chooser))
            // The chooser itself starts the receiver; on some OEM builds
            // the grant flag has to live on the outer intent too or the
            // receiver hits SecurityException reading the content://
            // FileProvider URI.
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // [deep-audit-1048 B-04] …and NEW_TASK, like the sibling share
            // paths (provider export / MCP export / markdown): `context` is
            // whatever LocalContext hands the screen, and starting an activity
            // from a non-Activity context without this flag throws
            // AndroidRuntimeException — an uncatchable crash on the share tap.
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            chooser
        } catch (e: Exception) {
            // Log the FileProvider failure — earlier this catch silently dropped
            // the exception and fell back to EXTRA_TEXT, masking config bugs
            // (e.g. a missing files-path root) as "share works but pastes the
            // whole file as text body" UX. Now any future regression is one
            // logcat line away from being root-caused.
            AppLogger.warning(
                "LogShare",
                "FileProvider share failed for ${file.name}: ${e.message} — falling back to EXTRA_TEXT",
            )
            // [deep-audit-1041 B-07] Bounded: the old spelling read the whole
            // file into the heap here and only then trimmed it.
            val text = readLogShareText(file) ?: return@withContext null
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            // [deep-audit-1048 B-04] Same NEW_TASK requirement as the
            // FileProvider branch above.
            Intent.createChooser(intent, context.getString(R.string.log_share_chooser)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

/**
 * [deep-audit-1048 B-04] Returns whether the chooser was actually started.
 * Every failure exit used to be a bare `return` — a log file that had been
 * rotated away, a FileProvider misconfiguration and a device with no share
 * target all produced the same nothing-on-screen, indistinguishable from a
 * successful share.
 */
private suspend fun shareLogFile(context: Context, file: File): Boolean {
    // Prep runs on Dispatchers.IO inside the builder; the launch stays on Main.
    val chooser = buildLogShareChooser(context, file) ?: return false
    // No handler installed throws ActivityNotFoundException; it used to escape
    // the caller's coroutine scope and take the process down.
    return runCatching { context.startActivity(chooser) }.isSuccess
}
