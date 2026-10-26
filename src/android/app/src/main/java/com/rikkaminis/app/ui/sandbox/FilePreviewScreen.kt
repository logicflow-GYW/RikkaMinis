package com.rikkaminis.app.ui.sandbox

import com.rikkaminis.app.R
import androidx.compose.ui.res.stringResource
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.LruCache
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.rikkaminis.app.browser.SafeWebViewClient
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.ui.components.ImageGalleryItem
import com.rikkaminis.app.ui.components.rememberIosBounceOverscrollEffect
import com.rikkaminis.app.ui.markdown.MarkdownText
import com.rikkaminis.app.ui.media.InlineAudioPlayer
import com.rikkaminis.app.ui.media.InlineVideoPlayer
import com.rikkaminis.app.ui.preview.ReleaseStep
import com.rikkaminis.app.ui.preview.WebViewReleaseLatch
import com.rikkaminis.app.ui.preview.releasePlan
import com.rikkaminis.app.util.ExternalIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import com.rikkaminis.app.ui.components.MinisTextButton

private const val MAX_TEXT_PREVIEW_BYTES = 512_000 // 500 KB

// [da1026-F] Row cap for the CSV/TSV table preview — unchanged from the
// original loop (`if (read >= 200)`). A row cap alone is NOT a memory bound;
// see [readCsvPreviewSlice].
private const val MAX_CSV_PREVIEW_ROWS = 200

// [rv1014-E1] Fixed read buffer for [readCapped]: one 64 KB chunk per read()
// syscall, no size surprises for small or huge files alike.
private const val READ_BUFFER_BYTES = 64 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewScreen(
    item: FileItem,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // [deep-audit-1049 B-05] The print slot is module-level state, so nothing
    // tore it down when the preview was left: a printed file kept its renderer
    // alive for the rest of the process. Releasing here bounds it to the
    // screen; a job that is still spooling is skipped (see the releaser).
    DisposableEffect(Unit) {
        onDispose { releasePrintWebViewIfNotSpooling() }
    }

    // T-android-preview-title-toggle-path: tap the title to swap between
    // file name and absolute path. Mirrors the iOS preview's tap-to-toggle
    // behavior so the user can read the source location without leaving
    // the preview. Long paths use TextOverflow.Ellipsis (the TopAppBar
    // title slot is single-line by spec).
    var showFullPath by remember(item.file) { mutableStateOf(false) }

    // T144: SAF Save-As for non-image files (image keeps T142 MediaStore).
    val mimeType = remember(item.file) {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            item.file.extension.lowercase(),
        ) ?: "application/octet-stream"
    }
    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(mimeType),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val ok = try {
                // [deep-audit-1047 B-01] `openOutputStream` returns null (never
                // throws) when the document provider refuses or died; `?.use`
                // then skipped the copy and the old code returned `true` anyway
                // — a "saved" toast over an empty document. Null now lands in
                // the same failure branch as an I/O exception.
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    item.file.inputStream().use { it.copyTo(out) }
                } ?: throw java.io.IOException("openOutputStream returned null")
                true
            } catch (e: Exception) {
                AppLogger.warning("FilePreview", "Save-As failed: ${e.message}")
                false
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(if (ok) R.string.file_saved_toast else R.string.file_save_failed_toast),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // T-imgswipe-4f446d83: image files take the swipe-able gallery route
    // (siblings from the same directory) instead of the in-place
    // ImagePreview column. Gallery owns its own close / save / share /
    // copy chrome, so we skip the Scaffold + TopAppBar entirely.
    if (item.isImageFile) {
        // [fix/audit-b17 / T10-L9] collectImageGallery lists the parent
        // directory and stats every sibling — that used to run inside
        // remember{} on the main thread during composition.
        // [deep-audit-1036 B-01] The initial value used to be an EMPTY list,
        // which is indistinguishable from "collected, nothing to show": the
        // first frame rendered ImageGalleryViewer with zero items, and its
        // defensive empty-items branch (LaunchedEffect { onDismiss() }) closed
        // the preview before the off-main scan ever landed. `null` = "still
        // collecting", a distinct state from any loaded result. And a loaded
        // result is never empty: collectImageGallery falls back to a 1-item
        // list (just the tapped file) when the parent dir can't be listed, so
        // the viewer's empty branch stays reachable only for its other
        // callers, never from this route.
        val galleryState = produceState<Pair<List<ImageGalleryItem>, Int>?>(
            initialValue = null,
            item.file.absolutePath,
        ) {
            value = withContext(Dispatchers.IO) { collectImageGallery(item.file) }
        }.value
        if (galleryState == null) {
            // Loading placeholder — same full-bleed dark surface as the viewer
            // itself, so the hand-off does not flash the file browser behind
            // it. Never handed to the gallery as an empty list.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
            }
            return
        }
        com.rikkaminis.app.ui.components.ImageGalleryViewer(
            items = galleryState.first,
            startIndex = galleryState.second,
            onDismiss = onBack,
        )
        return
    }

    // T279: mirror FileBrowserScreen — vanilla Scaffold + vanilla TopAppBar.
    // Earlier attempts (custom containerColor, contentWindowInsets=0,
    // windowInsets=statusBars on TopAppBar, body windowInsetsPadding +
    // background, DisposableEffect setting statusBarColor / isAppearanceLight)
    // all left visible gray bands above the bar / below the gesture bar
    // because they fought the Activity's edge-to-edge transparent-scrim
    // setup instead of cooperating with it. The "Browse Chat Files" screen
    // (FileBrowserScreen) renders correctly with zero overrides; do the same.
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (showFullPath) item.file.absolutePath else item.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.clickable { showFullPath = !showFullPath },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // T142: Share works for any file — FileProvider URI +
                    // ACTION_SEND + FLAG_GRANT_READ_URI_PERMISSION. iOS parity.
                    IconButton(onClick = { shareFile(context, item) }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.filepreview_share))
                    }
                    // Print: HTML renders via WebView; markdown / plain text /
                    // json / csv print their raw text wrapped in a WebView so we
                    // reuse the single createPrintDocumentAdapter path (Android
                    // has no UISimpleTextPrintFormatter equivalent). Image files
                    // take the gallery route above and never reach this bar.
                    if (item.isHtmlFile || item.isMarkdownFile || item.isTextFile ||
                        item.isJsonFile || item.isCsvFile
                    ) {
                        // T10-M4: text-ish files are read and wrapped into a
                        // printable HTML document on Dispatchers.IO — the click
                        // handler used to readBytes() the whole file (the 500 KB
                        // cap only applied afterwards) on the UI thread. HTML
                        // files keep loading straight through the WebView.
                        IconButton(onClick = {
                            if (item.isHtmlFile) {
                                printFile(context, item, preRenderedHtml = null)
                            } else {
                                scope.launch {
                                    val html = withContext(Dispatchers.IO) { buildPrintHtml(item) }
                                    printFile(context, item, preRenderedHtml = html)
                                }
                            }
                        }) {
                            Icon(Icons.Default.Print, contentDescription = stringResource(R.string.action_print))
                        }
                    }
                    // [fix/audit-b22 / T10-L1] No isImageFile branch here: the
                    // gate above returns into ImageGalleryViewer for images, so
                    // the old "Save to Gallery" button (and saveImageToGallery)
                    // was unreachable dead code. Gallery owns that chrome.
                    // T144 → SAF Save-As (user picks location).
                    IconButton(onClick = { saveAsLauncher.launch(item.name) }) {
                        Icon(Icons.Default.Download, contentDescription = stringResource(R.string.filepreview_save_as))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Order matters: markdown / html before generic text — both are
            // technically text but warrant richer renderers.
            when {
                item.isMarkdownFile -> MarkdownPreview(item)
                item.isHtmlFile -> HtmlPreview(item)
                item.isAudioFile -> AudioPreview(item)
                item.isVideoFile -> VideoPreview(item)
                item.isPdfFile -> PdfPreview(item)
                item.isCsvFile -> CsvPreview(item)
                item.isJsonFile -> JsonPreview(item)
                item.isArchiveFile -> ArchivePreview(item)
                item.isOfficeFile -> OfficeOpenExternal(item)
                item.isTextFile -> TextPreview(item)
                else -> FileInfoView(item)
            }
        }
    }
}

// ==================== Text/Code Preview ====================

@Composable
private fun TextPreview(item: FileItem) {
    // [fix/render-ui F-276] Error fallbacks below are user-visible; resolve
    // them through resources like the rest of this screen.
    val context = androidx.compose.ui.platform.LocalContext.current
    // [da1026-F] These three had no `remember` key, so Compose reused the slot
    // across file switches: `error` in particular stayed non-null until the new
    // read finished (and if the new read succeeded it was never cleared at
    // all, because the success path only assigns `content`), leaving the error
    // banner permanently masking the newly opened file. Keyed on item.file the
    // state is discarded on switch — same form CsvPreview/JsonPreview already
    // used.
    var content by remember(item.file) { mutableStateOf<String?>(null) }
    var truncated by remember(item.file) { mutableStateOf(false) }
    var error by remember(item.file) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.file) {
        // [deep-audit-1048 B-01] IO computes, Main publishes: the capped read +
        // decode stay on Dispatchers.IO; only the RESULT crosses back, and the
        // Compose state is assigned after `withContext` returns (on the
        // composition's dispatcher) instead of from inside the IO block.
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                // [rv1014-E1] was item.file.readBytes() — the full file was
                // read into memory before any cap applied, so a 2 GB log file
                // OOMed the preview. readCapped reads at most MAX+1 bytes and
                // still yields the truncated verdict in one pass. (And it was
                // briefly readNBytes(MAX+1) — API 33+, an uncatchable
                // NoSuchMethodError on Android 8–12.)
                val capped = readCapped(item.file, MAX_TEXT_PREVIEW_BYTES)
                decodeUtf8Capped(capped) to (capped.size > MAX_TEXT_PREVIEW_BYTES)
            }
        }
        loaded.fold(
            onSuccess = { (text, clipped) ->
                content = text
                truncated = clipped
            },
            onFailure = { e ->
                error = e.message ?: context.getString(R.string.filepreview_file_read_error)
            },
        )
    }

    when {
        error != null -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
        }

        content != null -> {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                if (truncated) {
                    Text(
                        text = stringResource(R.string.filepreview_truncated_kb, MAX_TEXT_PREVIEW_BYTES / 1000, item.formattedSize),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    HorizontalDivider()
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Box(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = content!!,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                            ),
                            softWrap = false,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }

        else -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.filepreview_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ==================== Markdown Preview ====================

@Composable
private fun MarkdownPreview(item: FileItem) {
    // [fix/render-ui F-276] Error fallbacks below are user-visible; resolve
    // them through resources like the rest of this screen.
    val context = androidx.compose.ui.platform.LocalContext.current
    // [da1026-F] Same unkeyed-remember defect as TextPreview above: a stale
    // `error` from the previous file masked the next one (the success path
    // never clears it).
    var content by remember(item.file) { mutableStateOf<String?>(null) }
    var error by remember(item.file) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.file) {
        // [deep-audit-1048 B-01] IO computes, Main publishes (see TextPreview).
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                // [rv1014-E1] capped read via readCapped — see TextPreview above.
                val capped = readCapped(item.file, MAX_TEXT_PREVIEW_BYTES)
                decodeUtf8Capped(capped)
            }
        }
        loaded.fold(
            onSuccess = { text -> content = text },
            onFailure = { e ->
                error = e.message ?: context.getString(R.string.filepreview_file_read_error)
                AppLogger.warning("FilePreview", "markdown read failed for ${item.name}: ${e.message}")
            },
        )
    }

    when {
        error != null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        content != null -> com.rikkaminis.app.ui.chat.MarkdownDocument(
            // T285-md: was StreamingMarkdownText inside verticalScroll{}, which
            // composed the full document up-front and stalled the chat-tap →
            // preview transition with main-thread parseInline scans across
            // all blocks (~150-300ms for a multi-KB markdown). MarkdownDocument
            // uses LazyColumn so only viewport-visible blocks compose on the
            // first frame — transition completes before the off-screen blocks
            // are touched.
            content = content!!,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp, vertical = 12.dp,
            ),
        )
        else -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.filepreview_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ==================== HTML Preview (WebView) ====================

@Composable
private fun HtmlPreview(item: FileItem) {
    // [GH#341] Bumped when the renderer dies, so `key` below discards the dead
    // WebView and re-runs the factory with a fresh one. Without this the
    // preview would be permanently blank after a renderer kill (the app itself
    // survives, which is the point of SafeWebViewClient).
    var rendererEpoch by remember(item.file) { mutableStateOf(0) }
    // [dv17-3 B-03] `item.file` must stay in the key: `remember(item.file)`
    // resets `rendererEpoch` to 0 when the file changes, so a bare
    // `key(rendererEpoch)` never changes (0→0) and the factory does not
    // re-run — the screen kept showing the *previous* file's HTML.
    key(item.file, rendererEpoch) {
    // [deep-audit-1046 S-07] One latch per WebView instance; `key(...)` recreates
    // this block (and this `remember`) whenever the renderer is replaced.
    val releaseLatch = remember { WebViewReleaseLatch() }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = true
                // T-webview-popup-d3c6e10f: mirror ffc85ad's WebPreviewBottomSheet
                // fix. Pages using `height: 100vh` + `overflow: hidden` were
                // collapsing to a 0-height clipped box (white screen) on first
                // compose because Blink resolved CSS viewport units against a
                // 0×0 measured container. useWideViewPort + loadWithOverviewMode
                // decouple the CSS viewport from initial measured size, and
                // deferring loadUrl via `post {}` guarantees the WebView has
                // been laid out (positive width/height) before Blink resolves
                // viewport units.
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                webViewClient = object : SafeWebViewClient() {
                    /**
                     * [GH#341] Renderer died: rebuild through the `key` above.
                     * The dead instance is destroyed by this AndroidView's
                     * `onRelease` as the key change drops it.
                     */
                    override fun onRendererGone(view: WebView?) {
                        rendererEpoch += 1
                    }
                }
                val targetUrl = fileUrlFor(item.file)
                // [deep-audit-1046 S-07] `post` parks the load in this view's run
                // queue until the first layout — and on a view that never gets
                // attached it stays parked. If the preview is closed first, the
                // load must not be replayed onto the destroyed WebView; named so
                // `onRelease` can remove it by identity (`removeCallbacks(null)`
                // is a no-op: AOSP guards it on `action != null`).
                val initialLoad = Runnable {
                    if (!releaseLatch.canRunDeferred()) return@Runnable
                    loadUrl(targetUrl)
                }
                releaseLatch.track(initialLoad)
                post(initialLoad)
            }
        },
        // [fix/render-ui F-274] This WebView had no release path: every preview
        // of an HTML file left a live renderer process handle behind, and the
        // queued `post { loadUrl }` could still fire after the composable left.
        // [deep-audit-1046 S-07] That queued load was never actually cancelled:
        // `removeCallbacks(null)` removes nothing (AOSP: `if (action != null) {
        // … } return false;`), so a preview closed before its first layout still
        // loaded the file into a destroyed WebView. The load is now registered
        // with the latch and removed by identity, and the latch is flipped first
        // so a re-post during teardown stands down too.
        // Order (releasePlan, shared with KaTeXView.kt): latch → detach → drop
        // tracked work → stopLoading → destroy.
        onRelease = { wv ->
            val outstanding = releaseLatch.release()
            for (step in releasePlan(attached = wv.parent != null)) {
                when (step) {
                    ReleaseStep.DETACH -> (wv.parent as? ViewGroup)?.removeView(wv)
                    ReleaseStep.DROP_DEFERRED -> {
                        for (task in outstanding) wv.removeCallbacks(task)
                    }
                    ReleaseStep.STOP_LOADING -> wv.stopLoading()
                    ReleaseStep.DESTROY -> wv.destroy()
                }
            }
        },
    )
    }
}

// ==================== Audio Preview ====================

@Composable
private fun AudioPreview(item: FileItem) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Top,
    ) {
        InlineAudioPlayer(filePath = item.file.absolutePath)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = item.formattedSize,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ==================== Video Preview ====================

@Composable
private fun VideoPreview(item: FileItem) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        InlineVideoPlayer(filePath = item.file.absolutePath)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = item.formattedSize,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ==================== PDF (native PdfRenderer) ====================

/**
 * [audit-0909 T10-H1] In-app PDF viewer on Android's stock [PdfRenderer],
 * rendering pages ON DEMAND.
 *
 * The previous implementation rendered every page (up to 50) up front into
 * a `List<Bitmap>`: an A4 page at targetW=1600 is 1600x2263x4B ~= 14 MB of
 * native heap, so a 50-page document pinned ~700 MB before the first
 * scroll. The LazyColumn only defers *composition* — it never releases the
 * bitmaps the list already holds, so the old "so 100-page PDFs don't OOM"
 * comment described an assumption that does not hold, and low-memory
 * devices were killed by the LMK mid-preview.
 *
 * Now each page renders when it enters the composition (produceState),
 * serialised on a Mutex because PdfRenderer allows one open page at a
 * time, with a byte-bounded LRU keeping recent neighbours warm. Capped at
 * 50 pages — the full document stays available via Save-As / external open.
 */
@Composable
private fun PdfPreview(item: FileItem) {
    val context = LocalContext.current
    var error by remember(item.file) { mutableStateOf<String?>(null) }
    var pageCount by remember(item.file) { mutableStateOf<Int?>(null) }
    val renderer = remember(item.file) { PdfPageRenderer() }

    DisposableEffect(item.file) {
        onDispose { renderer.close() }
    }

    LaunchedEffect(item.file) {
        // [deep-audit-1048 B-01] IO computes, Main publishes: `renderer.open`
        // (page indexing + first decode) stays on Dispatchers.IO; the page
        // count and the failure banner are published after it returns.
        val opened = withContext(Dispatchers.IO) {
            runCatching {
                // Render at (about) the on-screen width instead of a fixed
                // 1600px: same readability on a phone, a third of the
                // memory per page.
                val width = context.resources.displayMetrics.widthPixels.coerceIn(720, 1600)
                renderer.open(item.file, width)
            }
        }
        opened.fold(
            onSuccess = { count -> pageCount = count },
            onFailure = { e ->
                AppLogger.warning("FilePreview", "PdfRenderer failed for ${item.name}: ${e.message}")
                error = e.message ?: context.getString(R.string.filepreview_pdf_render_failed_fallback)
            },
        )
    }

    // -1 = not loaded yet; 0 = empty document. Keeping the count non-null
    // here avoids relying on when-branch smart casts for items().
    val count = pageCount ?: -1
    when {
        error != null -> PdfOpenExternalFallback(item, error!!)
        count < 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.filepreview_loading_pdf), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        count == 0 -> PdfOpenExternalFallback(item, stringResource(R.string.filepreview_pdf_empty))
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(count) { idx ->
                    // [deep-audit-1047 B-03] `idx` alone is not a data-source
                    // key: the preview composable is reused for the next file,
                    // so the page slot keeps the SAME `idx` and the old bitmap
                    // survives the switch (and the `if (value == null)` guard
                    // then skips the render, leaving the previous document's
                    // page on screen). `item.file` is the source the page comes
                    // from; the top-level `error`/`pageCount`/`renderer` are all
                    // keyed on it for exactly this reason.
                    //
                    // `initialValue = renderer.cached(idx)` must stay FIRST: a
                    // re-key still reuses the new renderer's LRU before paying
                    // for a re-render when the page is already warm.
                    val page by produceState<Bitmap?>(
                        initialValue = renderer.cached(idx),
                        key1 = idx,
                        key2 = item.file,
                    ) {
                        if (value == null) {
                            value = withContext(Dispatchers.IO) { renderer.render(idx) }
                        }
                    }
                    val bitmap = page
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.filepreview_page_n, idx + 1),
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth,
                        )
                    } else {
                        // A4-ish placeholder keeps the scroll position stable
                        // while this page renders.
                        Box(
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f / 1.4142f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.filepreview_page_n, idx + 1),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * [audit-0909 T10-H1] Byte-bounded, Mutex-serialised PDF page renderer.
 *
 * Evicted bitmaps are deliberately NOT recycled: an Image may still hold a
 * reference for a frame or two, and recycling would crash the canvas with
 * "trying to use a recycled bitmap". Their pixels live in the native heap
 * and are freed by the NativeAllocationRegistry once the last reference is
 * collected, so dropping the LRU entry is enough.
 */
private class PdfPageRenderer {
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pageCount = 0
    private var targetWidth = 0

    private val cache = object : LruCache<Int, Bitmap>(CACHE_BUDGET_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    suspend fun open(file: File, width: Int): Int = mutex.withLock {
        closeLocked()
        closed.set(false)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val pdf = PdfRenderer(pfd)
        descriptor = pfd
        renderer = pdf
        targetWidth = width
        pageCount = minOf(pdf.pageCount, MAX_PREVIEW_PAGES)
        pageCount
    }

    fun cached(index: Int): Bitmap? = cache.get(index)

    suspend fun render(index: Int): Bitmap? = mutex.withLock {
        if (closed.get()) return@withLock null
        cache.get(index)?.let { return@withLock it }
        val pdf = renderer ?: return@withLock null
        if (index < 0 || index >= pageCount) return@withLock null
        val width = targetWidth.coerceAtLeast(1)
        val bitmap = pdf.openPage(index).use { page ->
            val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bmp ->
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
        cache.put(index, bitmap)
        if (closed.get()) closeLocked()
        bitmap
    }

    /**
     * Idempotent and safe to call while a render is in flight: if the lock
     * is held, the in-flight render observes `closed` and closes the native
     * handles itself on completion.
     */
    fun close() {
        closed.set(true)
        if (mutex.tryLock()) {
            try {
                closeLocked()
            } finally {
                mutex.unlock()
            }
        }
    }

    private fun closeLocked() {
        cache.evictAll()
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        renderer = null
        descriptor = null
        pageCount = 0
    }

    private companion object {
        /** ~64 MB of page bitmaps (~9 A4 pages at 1080px wide). */
        const val CACHE_BUDGET_BYTES = 64 * 1024 * 1024
        const val MAX_PREVIEW_PAGES = 50
    }
}

@Composable
private fun PdfOpenExternalFallback(item: FileItem, reason: String) {
    val context = LocalContext.current
    // T149: same regression as Office — fallback page now reuses the shared
    // metadata block under the placeholder so an unrenderable PDF still shows
    // file basics rather than just a button.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.filepreview_pdf_render_failed, reason),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            MinisTextButton(onClick = { openExternally(context, item, "application/pdf") }) {
                Text(stringResource(R.string.filepreview_open_externally))
            }
        }
        FileMetadataBlock(item)
    }
}

// ==================== CSV / TSV ====================

@Composable
private fun CsvPreview(item: FileItem) {
    // [fix/render-ui F-276] Error fallbacks below are user-visible; resolve
    // them through resources like the rest of this screen.
    val context = androidx.compose.ui.platform.LocalContext.current
    var rows by remember(item.file) { mutableStateOf<List<List<String>>?>(null) }
    var byteTruncated by remember(item.file) { mutableStateOf(false) }
    var rowTruncated by remember(item.file) { mutableStateOf(false) }
    var error by remember(item.file) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.file) {
        // [deep-audit-1048 B-01] IO computes, Main publishes (see TextPreview).
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val sep = if (item.file.extension.equals("tsv", true)) '\t' else ','
                // [da1026-F] was a bare `item.file.bufferedReader(...)` +
                // readLine() loop with only a ROW cap — see
                // [readCsvPreviewSlice] for why the row cap did not bound
                // memory.
                readCsvPreviewSlice(item.file, sep, MAX_CSV_PREVIEW_ROWS)
            }
        }
        loaded.fold(
            onSuccess = { slice ->
                rows = slice.rows
                byteTruncated = slice.byteTruncated
                rowTruncated = slice.rowTruncated
            },
            onFailure = { e ->
                error = e.message ?: context.getString(R.string.filepreview_csv_parse_error)
            },
        )
    }

    when {
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        rows == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.filepreview_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> {
            val table = rows!!
            Column(Modifier.fillMaxSize()) {
                if (byteTruncated || rowTruncated) {
                    Text(
                        // Byte cap and row cap are independent: a huge single
                        // line trips the byte cap without ever reaching 200
                        // rows, and a 200-row file trips only the row cap.
                        // Report the one that actually clipped the table.
                        text = if (byteTruncated) {
                            stringResource(
                                R.string.filepreview_truncated_kb,
                                MAX_TEXT_PREVIEW_BYTES / 1000,
                                item.formattedSize,
                            )
                        } else {
                            stringResource(
                                R.string.filepreview_truncated_rows,
                                MAX_CSV_PREVIEW_ROWS,
                                item.formattedSize,
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    HorizontalDivider()
                }
                LazyColumn(modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                    items(table.size) { rowIdx ->
                        val cols = table[rowIdx]
                        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            cols.forEach { cell ->
                                Text(
                                    text = cell,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        fontWeight = if (rowIdx == 0) FontWeight.Bold else FontWeight.Normal,
                                    ),
                                    modifier = Modifier.padding(end = 16.dp).width(140.dp),
                                    maxLines = 1,
                                )
                            }
                        }
                        if (rowIdx == 0) HorizontalDivider()
                    }
                }
            }
        }
    }
}

// --- begin csv-bounded-preview (JVM harness / unit test slices this region) ---

/** [da1026-F] Result of [readCsvPreviewSlice]: parsed rows + why it stopped early. */
internal data class CsvPreviewSlice(
    val rows: List<List<String>>,
    val byteTruncated: Boolean,
    val rowTruncated: Boolean,
)

/**
 * [da1026-F] Row-bounded **and** byte-bounded CSV/TSV preview slice.
 *
 * The previous implementation streamed `bufferedReader().readLine()` with a
 * 200-row cap and nothing else. A row cap is not a memory bound: `readLine()`
 * materialises the whole line before the cap can look at it, so a CSV whose
 * single line is hundreds of MB (a minified data dump, a one-line export)
 * grew one String to the size of the file → `OutOfMemoryError`. That is an
 * `Error`, not an `Exception`, so the surrounding `catch (e: Exception)` in
 * the caller never saw it and the process was killed. Same class of bug
 * [rv1014-E1] fixed for TextPreview/MarkdownPreview/JsonPreview and
 * [da1020-08] fixed for buildPrintHtml — this was the one site left, because
 * the gate only looked for `.readBytes()`.
 *
 * Now the file goes through the same [readCapped] the other four surfaces use
 * (at most [MAX_TEXT_PREVIEW_BYTES] + 1 bytes), so both the decode and the
 * split operate on a bounded String no matter how the file is shaped.
 *
 * **Residual-fragment policy (deliberate).** A byte-capped slice normally ends
 * mid-line. That fragment is DISCARDED rather than parsed: rendering it would
 * put a half row on screen looking exactly like a complete one, and the row it
 * belongs to is not otherwise recoverable. This mirrors [decodeUtf8Capped],
 * which drops a split multi-byte stump for the same "don't show a lie" reason.
 * Consequence: when the cut lands exactly on a newline the last *complete* row
 * is dropped too — a bounded over-drop, never an under-drop, and the banner
 * tells the user the preview is clipped anyway.
 *
 * Line splitting mirrors `BufferedReader.readLine()` rather than a naive
 * `split('\n')`: a terminating newline does not produce a trailing empty row,
 * an empty file produces no rows, and interior blank lines are preserved.
 *
 * @param maxRows row cap for the rendered table (rows beyond it set
 *   [CsvPreviewSlice.rowTruncated]).
 */
internal fun readCsvPreviewSlice(file: File, sep: Char, maxRows: Int): CsvPreviewSlice {
    val capped = readCapped(file, MAX_TEXT_PREVIEW_BYTES)
    val byteTruncated = capped.size > MAX_TEXT_PREVIEW_BYTES
    val lines = splitPreviewLines(decodeUtf8Capped(capped))
    val completeLines = if (byteTruncated) lines.dropLast(1) else lines
    val rowTruncated = completeLines.size > maxRows
    val kept = if (rowTruncated) completeLines.subList(0, maxRows) else completeLines
    return CsvPreviewSlice(
        rows = kept.map { parseCsvLine(it, sep) },
        byteTruncated = byteTruncated,
        rowTruncated = rowTruncated,
    )
}

/** `readLine()`-equivalent line split; see [readCsvPreviewSlice]. */
internal fun splitPreviewLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    // Kotlin's lines() splits on CRLF / LF / CR — the same three terminators
    // BufferedReader.readLine() does, so CRLF files keep their original
    // behaviour (the \r never reaches a cell). `lines()` yields one trailing
    // empty element when the text ends in a terminator; readLine() would not
    // have produced that row, so drop it.
    val parts = text.lines()
    return if (parts.last().isEmpty()) parts.dropLast(1) else parts
}

// --- end csv-bounded-preview ---

private fun parseCsvLine(line: String, sep: Char): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                cur.append('"'); i += 2; continue
            }
            c == '"' -> inQuotes = !inQuotes
            c == sep && !inQuotes -> { out.add(cur.toString()); cur.clear() }
            else -> cur.append(c)
        }
        i++
    }
    out.add(cur.toString())
    return out
}

// ==================== JSON pretty-print ====================

@Composable
private fun JsonPreview(item: FileItem) {
    // [fix/render-ui F-276] Error fallbacks below are user-visible; resolve
    // them through resources like the rest of this screen.
    val context = androidx.compose.ui.platform.LocalContext.current
    var pretty by remember(item.file) { mutableStateOf<String?>(null) }
    var truncated by remember(item.file) { mutableStateOf(false) }
    var error by remember(item.file) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.file) {
        // [deep-audit-1048 B-01] IO computes, Main publishes (see TextPreview).
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                // [rv1014-E1] capped read via readCapped — see TextPreview above.
                val capped = readCapped(item.file, MAX_TEXT_PREVIEW_BYTES)
                val clipped = capped.size > MAX_TEXT_PREVIEW_BYTES
                val raw = decodeUtf8Capped(capped)
                val formatted = try {
                    when (raw.trimStart().firstOrNull()) {
                        '{' -> org.json.JSONObject(raw).toString(2)
                        '[' -> org.json.JSONArray(raw).toString(2)
                        else -> raw
                    }
                } catch (_: Exception) { raw }
                formatted to clipped
            }
        }
        loaded.fold(
            onSuccess = { (formatted, clipped) ->
                pretty = formatted
                truncated = clipped
            },
            onFailure = { e ->
                error = e.message ?: context.getString(R.string.filepreview_file_read_error)
            },
        )
    }

    when {
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        pretty == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.filepreview_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> Column(Modifier.fillMaxSize()) {
            if (truncated) {
                Text(
                    text = stringResource(R.string.filepreview_truncated_kb, MAX_TEXT_PREVIEW_BYTES / 1000, item.formattedSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                HorizontalDivider()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Box(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = pretty!!,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        ),
                        softWrap = false,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

// ==================== Archive (ZIP/JAR/APK) ====================

/** One zip entry row. File-private (not a local class) so the type also
 *  crosses the `withContext(Dispatchers.IO)` boundary in the loader. */
private data class ArchiveEntry(val name: String, val size: Long, val isDir: Boolean)

@Composable
private fun ArchivePreview(item: FileItem) {
    // [fix/render-ui F-276] Error fallbacks below are user-visible; resolve
    // them through resources like the rest of this screen.
    val context = androidx.compose.ui.platform.LocalContext.current
    var entries by remember(item.file) { mutableStateOf<List<ArchiveEntry>?>(null) }
    var error by remember(item.file) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.file) {
        // [deep-audit-1048 B-01] IO computes, Main publishes (see TextPreview):
        // the zip walk returns the sorted entry list; the state is assigned
        // after `withContext` returns.
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val out = mutableListOf<ArchiveEntry>()
                java.util.zip.ZipFile(item.file).use { zf ->
                    val it = zf.entries()
                    while (it.hasMoreElements()) {
                        val e = it.nextElement()
                        out.add(ArchiveEntry(e.name, e.size, e.isDirectory))
                        if (out.size >= 2000) break
                    }
                }
                out.sortedBy { it.name }
            }
        }
        loaded.fold(
            onSuccess = { list -> entries = list },
            onFailure = { e ->
                error = e.message ?: context.getString(R.string.filepreview_archive_read_error)
            },
        )
    }

    when {
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        entries == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.filepreview_loading_archive), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> {
            val list = entries!!
            Column(Modifier.fillMaxSize()) {
                Text(
                    text = "${list.size} entries  •  ${item.formattedSize}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(list.size) { i ->
                        val e = list[i]
                        ListItem(
                            headlineContent = {
                                Text(
                                    e.name,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                    ),
                                    maxLines = 1,
                                )
                            },
                            supportingContent = if (e.isDir) null else {
                                // [sweep1014-B29] was Formatter.formatFileSize(null, …) —
                                // AOSP Formatter returns "" for a null context; use the
                                // pure-Kotlin formatter from FileBrowserViewModel.kt.
                                { Text(formatFileSize(e.size)) }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// ==================== Office (xlsx/docx/pptx → external) ====================

@Composable
private fun OfficeOpenExternal(item: FileItem) {
    val context = LocalContext.current
    // T149: was full-screen-centered placeholder + button only — restored
    // metadata block beneath. Top half = preview hint + Open externally,
    // bottom = Name/Size/Type/Modified/Path so the user always sees the
    // basic file info that existed pre-T144.
    // T164: outer scrollable wrapper pumps overscroll deltas into our
    // IosBounceOverscrollEffect — `Modifier.verticalScroll` doesn't
    // expose an overscrollEffect slot, so we mirror the
    // AlwaysStretchOverscrollBox trick (wrap with a noop scrollable
    // that consumes 0 deltas, pipe the same effect through both
    // Modifier.scrollable's overscrollEffect param and Modifier.overscroll
    // for the visual rubber-band). The inner verticalScroll still owns
    // the actual scroll position; only the over-edge component gets
    // routed through the spring bounce.
    val bounce = rememberIosBounceOverscrollEffect()
    val noopState = rememberScrollableState { 0f }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .scrollable(
                state = noopState,
                orientation = Orientation.Vertical,
                overscrollEffect = bounce,
            )
            .overscroll(bounce)
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.filepreview_office_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            MinisTextButton(onClick = {
                val mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(item.file.extension.lowercase())
                    ?: "application/octet-stream"
                openExternally(context, item, mime)
            }) {
                Text(stringResource(R.string.filepreview_open_externally))
            }
        }
        FileMetadataBlock(item)
    }
}

private fun openExternally(context: Context, item: FileItem, mime: String) {
    try {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, item.file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // [deep-audit-1049 B-03] The chooser is what gets started, and it does
        // not inherit the inner intent's flags — the hand-rolled version left
        // NEW_TASK on the inner intent only. The chooser label moves from a
        // hard-coded English literal to the existing localized string.
        ExternalIntent.share(
            context,
            intent,
            context.getString(R.string.filepreview_open_externally),
        ) {
            Toast.makeText(
                context,
                context.getString(R.string.filepreview_no_app),
                Toast.LENGTH_SHORT,
            ).show()
        }
    } catch (e: Exception) {
        AppLogger.warning("FilePreview", "openExternally failed: ${e.message}")
        Toast.makeText(context, context.getString(R.string.filepreview_no_app), Toast.LENGTH_SHORT).show()
    }
}

// ==================== File Info (fallback for unsupported types) ====================

@Composable
private fun FileInfoView(item: FileItem) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Icon header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Preview not available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        FileMetadataBlock(item)
    }
}

/**
 * T149: shared metadata list — Name / Size / Type / Modified / Path (+ symlink
 * target). Pre-T142 this lived inline in FileInfoView; T144 only wired it for
 * the unknown-type fallback, so Office/PDF-fallback regressed to "just a
 * button". Extracted so any preview path that doesn't render bytes inline can
 * still surface the basics.
 */
@Composable
private fun FileMetadataBlock(item: FileItem) {
    HorizontalDivider()
    val attrs = remember(item) { buildFileAttributes(item) }
    attrs.forEach { (key, value) ->
        ListItem(
            headlineContent = { Text(value) },
            overlineContent = { Text(key) },
        )
    }
}

private fun buildFileAttributes(item: FileItem): List<Pair<String, String>> {
    val attrs = mutableListOf<Pair<String, String>>()
    attrs.add("Name" to item.name)
    attrs.add("Size" to item.formattedSize)

    val ext = item.file.extension
    if (ext.isNotEmpty()) {
        attrs.add("Type" to ext.uppercase())
    }

    val modified = item.file.lastModified()
    if (modified > 0) {
        attrs.add("Modified" to FileBrowserViewModel.formatDate(modified))
    }

    attrs.add("Path" to item.file.absolutePath)

    if (item.isSymlink) {
        try {
            val target = java.nio.file.Files.readSymbolicLink(item.file.toPath())
            attrs.add("Link Target" to target.toString())
        } catch (_: Exception) { }
    }

    return attrs
}

// ==================== Share / Save helpers (T142) ====================

/**
 * Share any file via the system share sheet. Mirrors iOS UIActivityViewController.
 * Uses our FileProvider authority so the receiving app can read the bytes,
 * with FLAG_GRANT_READ_URI_PERMISSION attached so the grant follows the chooser
 * pick (the chooser itself launches a separate activity that wouldn't otherwise
 * be in the granted set).
 */
private fun shareFile(context: Context, item: FileItem) {
    try {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, item.file)
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(item.file.extension.lowercase())
            ?: "*/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // [deep-audit-1049 B-03] Shared launcher: NEW_TASK lands on the
        // chooser (the intent that is actually started) instead of nowhere.
        ExternalIntent.share(
            context,
            intent,
            context.getString(R.string.file_share_chooser_title),
        ) { t ->
            Toast.makeText(
                context,
                context.getString(R.string.file_share_failed_toast, t.message ?: ""),
                Toast.LENGTH_SHORT,
            ).show()
        }
    } catch (e: Exception) {
        AppLogger.warning("FilePreview", "share failed for ${item.name}: ${e.message}")
        Toast.makeText(context, context.getString(R.string.file_share_failed_toast, e.message ?: ""), Toast.LENGTH_SHORT).show()
    }
}

/**
 * Print a previewable file via the Android print framework. HTML loads
 * directly; text-based files (markdown / plain text / json / csv) are wrapped
 * in an HTML `<pre>` block so a single `WebView.createPrintDocumentAdapter`
 * path covers every case. Mirrors iOS where every preview surface funnels
 * through one print controller.
 *
 * The off-screen WebView must outlive this function: print is async (we kick it
 * off only after `onPageFinished`), so we hold the instance in a captured var
 * and clear it once the adapter is handed to PrintManager.
 *
 * T10-M4: [preRenderedHtml] is produced by [buildPrintHtml] on Dispatchers.IO
 * by the caller; this function itself only touches the WebView, which must
 * stay on the main thread. Pass `null` for HTML files, which load from disk
 * through `loadUrl`.
 */
private fun printFile(context: Context, item: FileItem, preRenderedHtml: String?) {
    // [fix/render-ui F-274] The previous body kept a `var holder: WebView?`
    // local to "keep webView reachable across the async page load" and cleared
    // it in `onPageFinished`. Local-variable assignments do not create GC roots
    // (the compiler even needed `@Suppress("UNUSED_VALUE")` because nothing ever
    // read it), so the guarantee was fictional and `destroy` appeared 0 times in
    // this file — every print leaked a WebView + renderer process handle.
    //
    // A *field* does create a GC root, so `printWebView` below is the real
    // version of that intent. It is deliberately NOT destroyed in
    // `onPageFinished`: `createPrintDocumentAdapter` hands the framework an
    // adapter that reads this WebView lazily for as long as the job is
    // spooling, and nothing tells us when that ends. Destroying on the success
    // path would silently produce blank printouts whenever a job outlives its
    // call.
    //
    // [deep-audit-1049 B-05] The slot IS released before the next print claims
    // it, and on preview exit — but only when the current view has not started
    // spooling yet (`releasePrintWebViewIfNotSpooling`). A spooling view keeps
    // the old "bounded to whatever is still printing" behaviour; what no longer
    // happens is the double dialog, because `onPageFinished` now refuses to
    // start a job for a view that is no longer the slot owner.
    try {
        releasePrintWebViewIfNotSpooling()
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = true
        }
        printWebView = webView
        printWebViewSpooling = false
        webView.webViewClient = object : SafeWebViewClient() {
            /**
             * [GH#341] Renderer died mid-print. The print job cannot proceed —
             * `createPrintDocumentAdapter` reads this WebView lazily while
             * spooling — so release the single slot here. [releasePrintWebView]
             * (identity-checked) leaves a newer print's view alone.
             */
            override fun onRendererGone(view: WebView?) {
                AppLogger.warning("FilePreview", "print renderer gone for ${item.name}")
                // `view` is nullable in the callback signature; the overload
                // takes a non-null WebView because its identity check is the
                // whole point.
                if (view != null) releasePrintWebView(view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                // [deep-audit-1049 B-05] A superseded view must not pull a
                // print job. Its page keeps loading after the slot was claimed
                // by a newer print, and the old code started a second dialog
                // from here whose adapter `releasePrintWebView(view)` could no
                // longer reach (the identity check made it a no-op), leaving
                // both the dialog and the renderer behind. Never spooling means
                // this view can be torn down immediately.
                if (printWebView !== view) {
                    AppLogger.warning(
                        "FilePreview",
                        "print page finished for a superseded view: ${item.name}",
                    )
                    destroyPrintWebView(view)
                    return
                }
                printWebViewSpooling = true
                val printManager =
                    context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val jobName = "${context.getString(R.string.app_name)} - ${item.name}"
                val adapter = view.createPrintDocumentAdapter(jobName)
                printManager.print(
                    jobName,
                    adapter,
                    PrintAttributes.Builder().build(),
                )
                // Deliberately no release here — see the note above.
            }

            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                // Without this the failure path never ran onPageFinished, so the
                // view was never released at all.
                AppLogger.warning(
                    "FilePreview",
                    "print page load failed for ${item.name}: ${error.description}",
                )
                releasePrintWebView(view)
            }
        }
        if (preRenderedHtml == null) {
            webView.loadUrl(fileUrlFor(item.file))
        } else {
            webView.loadDataWithBaseURL(null, preRenderedHtml, "text/html", "utf-8", null)
        }
    } catch (e: Exception) {
        AppLogger.warning("FilePreview", "print failed for ${item.name}: ${e.message}")
        Toast.makeText(context, context.getString(R.string.file_print_failed_toast, e.message ?: ""), Toast.LENGTH_SHORT).show()
    }
}

/**
 * [fix/render-ui F-274] The one live reference to the print WebView. Being a
 * field (not a local) is what makes it an actual GC root; see the note in
 * [printFile] for why it outlives `onPageFinished`.
 *
 * ponytail: 单槽，打印中不销毁 | 天花板: 一次打印作业 spool 期间再点打印，前一个
 * WebView 无法安全销毁（销毁 = 空白纸），会留到 GC；非 spool 的旧实例在下次打印
 * 前与退出预览时都会被释放（[releasePrintWebViewIfNotSpooling]）| 升级触发:
 * PrintManager 提供作业完成回调，或出现「打印相关 WebView 驻留」的内存报告。
 */
private var printWebView: WebView? = null

/**
 * [deep-audit-1049 B-05] Whether [printWebView] has already handed its adapter
 * to PrintManager. A spooling view is still being read by the framework, so it
 * is the one instance that must NOT be destroyed.
 */
private var printWebViewSpooling = false

/**
 * [deep-audit-1049 B-05] Decision behind [releasePrintWebViewIfNotSpooling],
 * pulled out so a JVM test can pin the branch (the WebView itself is
 * Android-only). Pure: the caller supplies the two facts.
 */
internal fun shouldReleasePrintSlot(hasSlot: Boolean, slotSpooling: Boolean): Boolean =
    hasSlot && !slotSpooling

/**
 * [fix/render-ui F-274] Drop the reference and tear the renderer down. Called
 * on a load failure, by [releasePrintWebViewIfNotSpooling] before a new print
 * claims the slot, and when the preview screen goes away. `destroy()` on an
 * already-destroyed WebView is a no-op, and the identity check makes a stale
 * callback from a superseded view safe.
 */
private fun releasePrintWebView() {
    val view = printWebView ?: return
    printWebView = null
    printWebViewSpooling = false
    destroyPrintWebView(view)
}

/**
 * [fix/render-ui F-274] Release [view] only if it is still the current one, so
 * a late `onReceivedError` from a superseded print cannot tear down the view a
 * newer print is using.
 */
private fun releasePrintWebView(view: WebView) {
    if (isCurrentPrintView(printWebView, view)) releasePrintWebView()
}

/**
 * [deep-audit-1049 B-05] Identity, not equality: two WebViews that would
 * compare "equal" are still different renderers, and releasing the wrong one
 * blanks the job the other is spooling. Pure so the identity semantics the
 * whole slot guard rests on are pinned on the JVM.
 */
internal fun isCurrentPrintView(owner: Any?, candidate: Any?): Boolean =
    owner != null && owner === candidate

/**
 * [deep-audit-1049 B-05] Release the slot before it is re-claimed (a new print)
 * or abandoned (the preview screen exits) — the contract [releasePrintWebView]
 * always documented but nothing called. A view whose job is already spooling is
 * skipped: `createPrintDocumentAdapter` reads it lazily, so destroying it here
 * would print blank pages.
 */
private fun releasePrintWebViewIfNotSpooling() {
    if (shouldReleasePrintSlot(printWebView != null, printWebViewSpooling)) {
        releasePrintWebView()
    }
}

/** Tear down one WebView that no print job depends on any more. */
private fun destroyPrintWebView(view: WebView) {
    runCatching {
        view.stopLoading()
        view.destroy()
    }
}

/**
 * Read a text-ish preview file (capped at [MAX_TEXT_PREVIEW_BYTES]) and wrap
 * it in a printable HTML document. Call from Dispatchers.IO.
 *
 * [da1020-08] The read is bounded as well, through the same [readCapped] the
 * preview surfaces below use: this used to call `File.readBytes()`, so printing
 * a multi-GB .log pulled the whole file into the heap before [decodeUtf8Capped]
 * applied its cap. Printed output is unchanged — at most MAX+1 bytes are read
 * (the one-extra-byte verdict documented on [readCapped]) and [decodeUtf8Capped]
 * still hands the WebView exactly [MAX_TEXT_PREVIEW_BYTES] bytes.
 */
private fun buildPrintHtml(item: FileItem): String {
    val raw = decodeUtf8Capped(readCapped(item.file, MAX_TEXT_PREVIEW_BYTES))
    return buildString {
        append("<html><head><meta charset=\"utf-8\">")
        append("<style>body{font-family:monospace;font-size:12px;white-space:pre-wrap;word-wrap:break-word;}</style>")
        append("</head><body><pre>")
        append(escapeHtml(raw))
        append("</pre></body></html>")
    }
}

/**
 * [fix/audit-b17 / T10-L10a] Decode at most [cap] bytes of UTF-8. A raw byte
 * cut can split a multi-byte sequence, and `String(bytes, UTF_8)` then renders
 * the stump as a trailing U+FFFD — every non-ASCII preview showed a stray "�".
 * Drop that single replacement char when we know the cut is what produced it.
 */
private fun decodeUtf8Capped(bytes: ByteArray, cap: Int = MAX_TEXT_PREVIEW_BYTES): String {
    val truncated = bytes.size > cap
    val slice = if (truncated) bytes.copyOf(cap) else bytes
    val text = String(slice, Charsets.UTF_8)
    return if (truncated && text.endsWith('\uFFFD')) text.dropLast(1) else text
}

/**
 * [rv1014-E1] Bounded replacement for java.io.InputStream.readNBytes, which
 * is API 33+ — minSdk is 26 and desugaring is off, so on Android 8–12
 * previewing any text file threw an uncatchable NoSuchMethodError (`Error`,
 * not `Exception`).
 *
 * Reads at most [maxBytes] + 1 bytes into a ByteArrayOutputStream with a fixed
 * [READ_BUFFER_BYTES] buffer, stopping as soon as the bound is reached — same
 * semantics the call sites relied on from `readNBytes(maxBytes + 1)`: the one
 * extra byte lets the caller derive the truncated verdict (`size > maxBytes`)
 * in a single pass.
 */
private fun readCapped(file: File, maxBytes: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(minOf(maxBytes + 1, READ_BUFFER_BYTES))
    val buf = ByteArray(READ_BUFFER_BYTES)
    file.inputStream().use { input ->
        var remaining = maxBytes + 1
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size, remaining))
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }
    return out.toByteArray()
}

private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

/**
 * Build the swipe-able gallery's (items, startIndex) for a tapped image
 * file: enumerate all image siblings in its parent directory (sorted by
 * name, case-insensitive), find the tapped one's index, and wrap them as
 * [com.rikkaminis.app.ui.components.ImageGalleryItem]. Non-image files are
 * filtered by extension here — matches [FileItem.isImageFile].
 *
 * Falls back to a 1-item list (just the tapped file) when the parent dir
 * can't be listed.
 */
private val IMAGE_GALLERY_EXTENSIONS =
    setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "ico")

private fun collectImageGallery(
    file: java.io.File,
): Pair<List<com.rikkaminis.app.ui.components.ImageGalleryItem>, Int> {
    val parent = file.parentFile
    val siblings = parent?.listFiles()
        ?.filter {
            it.isFile && it.extension.lowercase() in IMAGE_GALLERY_EXTENSIONS
        }
        ?.sortedBy { it.name.lowercase() }
        .orEmpty()
    val list = if (siblings.isEmpty()) listOf(file) else siblings
    val startIdx = list.indexOfFirst { it.absolutePath == file.absolutePath }
        .coerceAtLeast(0)
    val items = list.map {
        com.rikkaminis.app.ui.components.ImageGalleryItem(
            model = it,
            caption = it.name,
        )
    }
    return items to startIdx
}

// ── file:// URL encoding (da1022-H / backlog §1021-V) ─────────────────────────

// --- begin file-url-path-encode (JVM harness slices this region verbatim) ---

private const val FILE_URL_HEX = "0123456789ABCDEF"

/**
 * Percent-encode an absolute filesystem path for use in a `file://` URL.
 *
 * Why this exists (backlog §1021-V, device-verified): a name containing `#`
 * pasted raw into a `file://` URL makes WebView treat everything after the
 * `#` as a fragment, so `a#b.html` loads as `a.html` → `net::ERR_FILE_NOT_FOUND`.
 * Space, `?`, `%` and non-ASCII names break the same way.
 *
 * [com.rikkaminis.app.ui.preview.toAssetLoaderUrl] does NOT apply here: it
 * maps to the asset loader's `https://appassets.androidplatform.net/` origin,
 * which only resolves when a WebViewAssetLoader intercepts the load —
 * FilePreviewScreen loads plain `file://` URLs, so it needs the path itself
 * encoded. This is also why `android.net.Uri.encode(path, "/")` is not used:
 * this function is JVM-pure (no Android imports) so it is unit-testable
 * without Robolectric.
 *
 * Semantics: the RFC 3986 unreserved set (`ALPHA` / `DIGIT` / `-._~`) plus
 * `/` survives byte-for-byte (encoding `/` would destroy the path structure);
 * every other UTF-8 byte becomes `%XX`. `%` itself is escaped (`50%off` →
 * `50%25off`), which is what keeps a file literally named `a#b.html` or
 * `50%23.html` round-tripping to itself after WebView's percent-decode —
 * deliberately NOT idempotent on already-encoded input for the same reason.
 */
internal fun encodeFileUrlPath(path: String): String {
    val sb = StringBuilder(path.length)
    for (b in path.toByteArray(Charsets.UTF_8)) {
        val value = b.toInt() and 0xFF
        val c = value.toChar()
        val safe = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
            c == '-' || c == '.' || c == '_' || c == '~' || c == '/'
        if (safe) {
            sb.append(c)
        } else {
            sb.append('%').append(FILE_URL_HEX[value shr 4]).append(FILE_URL_HEX[value and 0x0F])
        }
    }
    return sb.toString()
}

// --- end file-url-path-encode ---

/**
 * Build the `file://` URL for [file] with the path percent-encoded — never
 * concatenate `"file://" + file.absolutePath` raw: a name containing `#` or
 * `?` is parsed as fragment/query by WebView and the load fails with
 * `net::ERR_FILE_NOT_FOUND` (backlog §1021-V, device-verified 2026-10-17).
 *
 * [fix/fileurl-contract] This is the single construction point for a `file://`
 * URL that crosses to a system component (WebView / asset loader / FileProvider),
 * so it is `internal` rather than `private`: the chat HTML preview
 * (`ChatScreen.openHtmlPreview` + the pinned-shortcut deep link) and the webapp
 * shortcut (`WebAppActivity`) used to concatenate the raw path themselves, which
 * left the two contracts spliced together — a file literally named `50%23.html`
 * reached the decoding consumers as `50#.html`. Contract: **encoded at
 * construction, decoded at every path-semantics consumer** (`decodeUriPath`).
 */
internal fun fileUrlFor(file: File): String = "file://" + encodeFileUrlPath(file.absolutePath)

