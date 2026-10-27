package com.rikkaminis.app.ui.chat

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.rikkaminis.app.R
import com.rikkaminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * T-android-gc-storm-issue17 — render-side safe mode.
 *
 * Wraps a message-body renderer (typically [StreamingMarkdownText] or
 * [MarkdownBlock]) with a content-length guard. Frozen messages whose raw text
 * exceeds [LARGE_MESSAGE_THRESHOLD_CHARS] are NOT fed to the markdown parser by
 * default — instead a CollapsedBadge is shown with size + Expand / Export
 * actions. This prevents the GC storm + UI hang observed when re-opening legacy
 * sessions with 200KB+ tool_result rows (Issue #17 follow-up to commit 86c55a8,
 * which fixed DB load but not render).
 *
 * Streaming messages are never collapsed — collapsing mid-stream would flicker
 * as the buffer grows past the threshold. The guard only kicks in for frozen
 * content.
 *
 * Expand state is per-message (keyed by [stableKey]) and survives recomposition
 * via rememberSaveable, but resets on session reopen (no persistence). This is
 * intentional: the cost of rendering 200KB markdown is incurred once per
 * explicit user opt-in per session view.
 */

internal const val LARGE_MESSAGE_THRESHOLD_CHARS = 32_000
internal const val LARGE_MESSAGE_PREVIEW_CHARS = 8_000

/**
 * [T-android-streaming-regex-hang] Above this live-buffer size, an actively
 * streaming message degrades to a bounded plain-text tail (no per-tick markdown
 * regex) until the turn finishes. Well below LARGE_MESSAGE_THRESHOLD_CHARS
 * because the streaming cost is O(buffer) PER TICK, not once — a 32K frozen
 * render is fine, but re-parsing 32K on every throttle tick is what hangs the
 * main thread. Matches the per-fragment LIVE_FRAGMENT_DEGRADE_CHARS heuristic in
 * StreamingMarkdownText so both live paths degrade at the same point.
 */
internal const val STREAM_DEGRADE_CHARS = 8_000

/**
 * Returns true when [content] should be rendered through the collapse path.
 * Streaming content always renders inline (guard is bypassed) to avoid the
 * threshold flicker described above.
 */
internal fun shouldCollapse(content: String, isStreaming: Boolean): Boolean =
    !isStreaming && content.length > LARGE_MESSAGE_THRESHOLD_CHARS

@Composable
internal fun LargeContentBadge(
    content: String,
    stableKey: String,
    onExpand: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sizeLabel = remember(content.length) { formatByteSize(content.length) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp),
            )
            .border(
                0.5.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Preview lines — plain Text, not markdown. Capped at LARGE_MESSAGE_PREVIEW_CHARS.
        val preview = remember(content) {
            content.take(LARGE_MESSAGE_PREVIEW_CHARS) + if (content.length > LARGE_MESSAGE_PREVIEW_CHARS) "…" else ""
        }
        Text(
            text = preview,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.chat_message_large_collapsed_title, sizeLabel),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )

            TextButton(
                onClick = {
                    AppLogger.info(
                        "LargeContentGuard",
                        "expand requested key=$stableKey chars=${content.length}",
                    )
                    onExpand()
                },
                modifier = Modifier.padding(0.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.UnfoldMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.chat_message_large_expand),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            TextButton(
                onClick = {
                    // [fix/da1023-A] deep-audit-1023 ISSUE-01: this button used to
                    // run the whole export — mkdirs + a multi-MB writeText + the
                    // FileProvider root lookup — synchronously on the UI thread.
                    // The write is bounded only by message size (the guard
                    // triggers at 32K chars, legacy rows reach 200KB+), so a tap
                    // could stall a frame. exportLargeContentToFile is now
                    // suspending and moves the disk work to Dispatchers.IO; the
                    // toast and its two string resources are unchanged.
                    scope.launch {
                        val ok = exportLargeContentToFile(context, content, stableKey)
                        Toast.makeText(
                            context,
                            if (ok) {
                                context.getString(R.string.chat_message_large_export_ok)
                            } else {
                                context.getString(R.string.chat_message_large_export_fail)
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                modifier = Modifier.padding(0.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.IosShare,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.chat_message_large_export),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

/**
 * Wraps a body renderer. When the content is frozen and over-size, renders the
 * CollapsedBadge instead; user tap toggles to inline render.
 *
 * @param stableKey   Used to scope the expand-state to a particular block. The
 *                    caller supplies messageId+blockId so two large messages
 *                    don't share expand-state across recompositions.
 * @param renderer    The actual body composable; called when content is small
 *                    or after user explicitly expands.
 */
@Composable
internal fun LargeContentGuard(
    content: String,
    isStreaming: Boolean,
    stableKey: String,
    renderer: @Composable () -> Unit,
) {
    // [T-android-streaming-regex-hang] Proactive streaming degrade. While a
    // message is actively streaming, its live tail block re-runs the full
    // inline/math regex scan (Matcher alloc + GC churn) on the WHOLE accumulated
    // text every throttle tick. On a long turn this stacks into a main-thread
    // hang that escalates to 30s+ and an ANR restart loop
    // (minis-2026-07-09-longctx-hang.log: every JankDiag stack in
    // java.util.regex.Matcher/Pattern). Once the streaming buffer crosses
    // STREAM_DEGRADE_CHARS we stop feeding it to the markdown renderer and show a
    // bounded plain-text tail instead — no regex, no AnnotatedString, one cheap
    // Text layout per publish. The full markdown render happens exactly ONCE when
    // the turn finishes (isStreaming flips false → this branch is skipped and the
    // real renderer runs), so the user sees a single smooth swap at stream-end
    // rather than a mid-stream flicker.
    //
    // The render-breaker path stays as a lower-bound safety net: if a hang is
    // *already* detected we degrade regardless of size (covers a giant unsplit
    // table below the char threshold that still re-parses expensively per tick).
    // [T-android-content-perf-diag] Track whether this block spent its streaming
    // life degraded, so we can log the one-time full-markdown swap (+ its parse
    // cost) when the turn ends.
    val wasDegraded = remember(stableKey) { mutableStateOf(false) }
    if (isStreaming) {
        val breakerActive by com.rikkaminis.app.diagnostics.HangDetector
            .renderBreakerActive.collectAsState()
        if (breakerActive || content.length > STREAM_DEGRADE_CHARS) {
            wasDegraded.value = true
            // [T-android-liveness-census] The live-degrade branch on the path
            // the aggregate pipeline actually takes — the old wiring sat in
            // MarkdownBlockBody's legacy per-fragment degrade, which cannot
            // run while AGGREGATE_MESSAGE_ITEMS is true.
            com.rikkaminis.app.diagnostics.Liveness.record(
                com.rikkaminis.app.diagnostics.RenderPathCensus.Branch.LIVE_DEGRADE,
            )
            // [T-android-content-perf-diag] Log the degrade with a structural
            // fingerprint (once per ~2K growth) so a hang report shows exactly
            // what shape the tail had. Only summarize the tail window we scan.
            remember(content.length / 2000) {
                val s = com.rikkaminis.app.diagnostics.ContentDiag.summarize(content)
                AppLogger.info(
                    "Perf",
                    "[Perf][ContentDiag] stream-degrade key=$stableKey breaker=$breakerActive " +
                        "threshold=$STREAM_DEGRADE_CHARS ${s.asLogFields()}",
                )
                Unit
            }
            DegradedStreamingTail(content)
            return
        }
    }
    if (!shouldCollapse(content, isStreaming)) {
        // [T-android-content-perf-diag] Stream-end swap: a block that streamed
        // degraded now renders full markdown ONCE. Log its shape so we know what
        // the deferred render is about to parse (the thing the degrade was
        // protecting against). The actual off-main markdown parse cost is logged
        // separately by MarkdownParseCaches ([Perf][ColdParse] / [JankDiag] slow
        // markdown parse). Fires once via the wasDegraded latch.
        if (!isStreaming && wasDegraded.value) {
            wasDegraded.value = false
            remember(stableKey, content.length) {
                val s = com.rikkaminis.app.diagnostics.ContentDiag.summarize(content)
                AppLogger.info(
                    "Perf",
                    "[Perf][ContentDiag] stream-end-swap key=$stableKey ${s.asLogFields()}",
                )
                Unit
            }
        }
        // [T-android-content-perf-diag] Register this body as the currently-
        // rendering large message so a HangDetector JankDiag sample taken while
        // its markdown parse is on the main thread can name it + its structure.
        // Only for large bodies — small ones never hang and the churn isn't worth
        // it. messageId is the first segment of stableKey after its type prefix.
        if (content.length >= com.rikkaminis.app.diagnostics.CONTENT_DIAG_MIN_CHARS) {
            val msgId = remember(stableKey) { stableKey.substringAfter(':').substringBefore(':') }
            androidx.compose.runtime.DisposableEffect(stableKey, content.length) {
                com.rikkaminis.app.diagnostics.ContentDiag.setCurrentRender(
                    sessionId = "",
                    messageId = msgId,
                    summary = com.rikkaminis.app.diagnostics.ContentDiag.summarize(content),
                )
                onDispose { com.rikkaminis.app.diagnostics.ContentDiag.clearCurrentRender(msgId) }
            }
        }
        renderer()
        return
    }

    var expanded by rememberSaveable(stableKey) { mutableStateOf(false) }

    // Diagnostic — emitted on first composition per large block.
    remember(stableKey) {
        AppLogger.info(
            "LargeContentGuard",
            "collapse key=$stableKey chars=${content.length} threshold=$LARGE_MESSAGE_THRESHOLD_CHARS",
        )
        Unit
    }

    if (expanded) {
        renderer()
    } else {
        LargeContentBadge(
            content = content,
            stableKey = stableKey,
            onExpand = { expanded = true },
        )
    }
}

/**
 * [T-android-render-breaker] Bounded plain-text rendering for a LIVE block
 * while the render breaker is active. Shows a one-line notice plus the tail of
 * the content (the part the user is watching grow). No markdown parse, no
 * regex, no AnnotatedString construction — a single Text layout of at most
 * [DEGRADED_TAIL_CHARS] characters per publish.
 */
private const val DEGRADED_TAIL_CHARS = 4_000

@Composable
private fun DegradedStreamingTail(content: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.chat_stream_degraded_notice),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (content.length > DEGRADED_TAIL_CHARS) {
                "…" + content.takeLast(DEGRADED_TAIL_CHARS)
            } else {
                content
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Format a byte count as a short human-readable string (e.g. "32 KB", "1.2 MB").
 * Inputs here are character counts; we treat each char as 1 byte for display,
 * which is approximate but accurate enough for the "this thing is big" hint.
 */
private fun formatByteSize(chars: Int): String {
    val bytes = chars.toLong()
    return when {
        bytes >= 1_000_000L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000L -> "${bytes / 1_000L} KB"
        else -> "$bytes B"
    }
}

/**
 * How long an exported oversized-message file survives in
 * `cacheDir/large-messages`. 24h, matching the
 * [com.rikkaminis.app.ui.components.pruneShareDir] precedent — the file is a
 * throwaway hand-off to another app behind a temporary FileProvider grant;
 * nothing in-app reads it back.
 */
internal const val LARGE_MESSAGE_EXPORT_TTL_MS = 24L * 60L * 60L * 1000L

/**
 * Delete exported large-message files older than [maxAgeMs] and return how
 * many went away.
 *
 * Until [fix/da1023-A] nothing ever deleted
 * `cacheDir/large-messages/msg-*.txt`, so every export was a permanent
 * orphan (deep-audit-1023 ISSUE-01). Prune-before-write, like
 * [com.rikkaminis.app.debug.ChatMutationMethods.pruneStaleAttachments] and
 * [com.rikkaminis.app.ui.settings.LogManagementScreen.pruneStaleDiagnosticZips].
 *
 * Deliberately free of Android types so a JVM unit test can drive a real temp
 * dir — see [com.rikkaminis.app.ui.chat.LargeMessageExportPruneTest].
 *
 * Expiry test matches [com.rikkaminis.app.ui.components.pruneShareDir]:
 * strictly-older dies, so a file whose age equals [maxAgeMs] exactly
 * survives. A failed delete is counted as kept, never as an exception.
 *
 * @param exportDir `cacheDir/large-messages`. A missing (or non-directory)
 *   path yields `0` — first-run before any export, or a dir the OS already
 *   reclaimed, is not an error.
 * @param nowMs clock reading, injected so the TTL boundary is testable.
 * @param maxAgeMs retention window; defaults to [LARGE_MESSAGE_EXPORT_TTL_MS].
 *
 * ponytail: 只在导出前顺带清扫（无后台巡检）| 天花板: 导出一次后再不导出的人，
 * 那份文件会留到 cache 被系统回收为止 | 升级触发: cacheDir/large-messages 在存储
 * 诊断里成为可增长目录，或单次点击会累积多份孤儿文件
 */
internal fun pruneExpiredLargeMessageFiles(
    exportDir: File,
    nowMs: Long,
    maxAgeMs: Long = LARGE_MESSAGE_EXPORT_TTL_MS,
): Int {
    val children = exportDir.listFiles() ?: return 0
    val cutoff = nowMs - maxAgeMs
    var deleted = 0
    for (f in children) {
        if (f.isFile && f.lastModified() < cutoff && runCatching { f.delete() }.getOrDefault(false)) {
            deleted++
        }
    }
    return deleted
}

/**
 * Dump the content to a temp file under cacheDir/large-messages and fire an
 * ACTION_SEND chooser. Returns true if the chooser was launched.
 *
 * Suspending: everything that touches the disk — `mkdirs`, the TTL prune
 * (`listFiles` is a directory walk) and the multi-MB `writeText` — runs on
 * [Dispatchers.IO]. The caller ([LargeContentBadge]) invokes this from
 * `rememberCoroutineScope()`, so the `FileProvider` lookup and the chooser
 * below still happen on the main thread, after the write has completed.
 */
private suspend fun exportLargeContentToFile(
    context: android.content.Context,
    content: String,
    stableKey: String,
): Boolean = try {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "large-messages").apply { mkdirs() }
        // [fix/da1023-A] deep-audit-1023 ISSUE-01: nothing deleted these
        // exports, so every tap left a permanent orphan (the dir is not the
        // system cache — the OS has no reason to reclaim it). Prune before
        // writing, the same shape as ChatMutationMethods.pruneStaleAttachments
        // and LogManagementScreen.pruneStaleDiagnosticZips. A prune failure
        // must never block the export the user asked for.
        runCatching { pruneExpiredLargeMessageFiles(dir, System.currentTimeMillis()) }
            .onFailure { AppLogger.warning("LargeContentGuard", "prune failed: ${it.message}") }
        val safeKey = stableKey.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64)
        val f = File(dir, "msg-$safeKey-${System.currentTimeMillis()}.txt")
        f.writeText(content)
        f
    }
    val authority = context.packageName + ".fileprovider"
    val uri = FileProvider.getUriForFile(context, authority, file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooser = Intent.createChooser(send, context.getString(R.string.chat_message_large_export))
    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
    true
} catch (t: Throwable) {
    AppLogger.warning("LargeContentGuard", "export failed: ${t.message}")
    false
}
