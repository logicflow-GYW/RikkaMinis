package com.rikkaminis.app.webapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AGColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rikkaminis.app.MinisApp
import com.rikkaminis.app.R
import com.rikkaminis.app.ui.components.sanitizeSingleLineInput
import com.rikkaminis.app.data.repository.WebAppShortcutRepository
import com.rikkaminis.app.data.storage.SessionFileStore
import com.rikkaminis.app.util.ImageDecoding
// [deep-audit-1047 B-02] The picked icon's bytes go through the repo's single
// bounded SAF/stream reader instead of a bare `readBytes()`.
import com.rikkaminis.app.ui.settings.readAtMost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Source of HTML bytes for the "Add to Home Screen" sheet. T-pwa-2 only
 * supported chat-attachment URIs; T-pwa-3 adds [HostFile] for FileBrowser
 * rows where we already know an absolute Linux path under
 * `/var/minis/shared/...` or `/var/minis/mounts/<name>/...` and don't
 * need to copy bytes — the shortcut links to the live file in place.
 */
sealed class WebAppSource {
    /** Chat attachment chip → copy bytes into per-session attachments dir. */
    data class ChatAttachment(
        val uri: Uri,
        val fileName: String,
        val sessionId: String,
        val sessionTitle: String?,
    ) : WebAppSource()

    /**
     * FileBrowser row → host file already on disk, link in place.
     * [linuxPath] is the absolute `/var/minis/...` path the shortcut
     * persists; [pathScope] is `shared` or `mount`; [scopeContext] is
     * null for shared, the mount name for mount.
     */
    data class HostFile(
        val file: File,
        val fileName: String,
        val pathScope: String,
        val scopeContext: String?,
        val linuxPath: String,
    ) : WebAppSource()
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
/**
 * Bottom-sheet for "Add to Home Screen". T-pwa-2 launched it from the
 * chat-attachment chip's long-press menu; T-pwa-3 also launches it from
 * the FileBrowser row long-press menu via [WebAppSource.HostFile]. The sheet
 * lets the user pick a title + icon, then calls
 * [WebAppShortcutRepository.create] + [ShortcutPinner.pin].
 */
@Composable
fun AddToHomeSheet(
    source: WebAppSource,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // shortcutId is decided up-front so the copied attachment lives at
    // <attachmentsDir>/<shortcutId>/<fileName> — stable for the life of
    // the shortcut even if the user later removes the chip. (Only used
    // by the ChatAttachment branch; the HostFile branch links in place.)
    val shortcutId = remember { UUID.randomUUID().toString() }

    val fileName: String = when (source) {
        is WebAppSource.ChatAttachment -> source.fileName
        is WebAppSource.HostFile -> source.fileName
    }

    // For ChatAttachment we copy the source URI into a stable host path
    // before the user commits; for HostFile the file already exists so
    // we just point [copiedFile] at it.
    var copiedFile by remember { mutableStateOf<File?>(null) }
    var pinned by remember { mutableStateOf(false) }
    // [deep-audit-1048 B-06] In-flight flag for the primary action. `create(…)`
    // inserts a DB row and `ShortcutPinner.pin(…)` is an IPC round trip to the
    // launcher, so the button stayed enabled for the whole trip and a second
    // tap raced a second row + a second pin against the first attempt's
    // cleanup. Decision lives in [pinActionEnabled] / [pinShouldCommit].
    var isPinning by remember { mutableStateOf(false) }
    // [deep-audit-1048 B-06] "The sheet is gone" latch. Every teardown hook
    // (Cancel, `onDismissRequest`, `onDispose` — the sheet can also be removed
    // externally via `webAppSheetTarget = null`, which only `onDispose` sees)
    // sets it, and the pin coroutine checks it after the IO staging step: a pin
    // the user already cancelled must not insert a row or add an icon.
    var sheetClosed by remember { mutableStateOf(false) }

    // [sweep1014-b7a] If the sheet closes without a successful pin, delete
    // the ChatAttachment copy so it doesn't outlive the sheet as an orphan.
    // HostFile points at a live host file — never deleted here.
    val cleanupCopiedAttachment = {
        val f = copiedFile
        if (f != null && source is WebAppSource.ChatAttachment && !pinned) {
            f.delete()
            // Empty <shortcutId>/ dir cleanup; no-op when non-empty.
            f.parentFile?.delete()
        }
        Unit
    }
    // [da1021-F] Universal cleanup: the Cancel button (plain `onDismiss`) and
    // any external state removal (`webAppSheetTarget = null` at both call
    // sites) tear the sheet out of composition WITHOUT invoking
    // `onDismissRequest`, which used to leak the copied attachment dir
    // permanently. onDispose is the one hook every disappearance path funnels
    // through; cleanupCopiedAttachment is idempotent (deleting a null or
    // already-deleted file is a no-op) and its `!pinned` guard keeps a
    // successful pin intact, so stacking it with the explicit
    // `onDismissRequest` call is safe.
    DisposableEffect(Unit) {
        onDispose {
            sheetClosed = true
            cleanupCopiedAttachment()
        }
    }
    var copyFailed by remember { mutableStateOf(false) }
    var parsedTitle by remember { mutableStateOf<String?>(null) }
    var webpageIconPath by remember { mutableStateOf<String?>(null) }

    // [deep-audit-1048 B-01] IO computes, Main publishes. The copy, the title
    // parse and the icon extraction all run on Dispatchers.IO (unchanged); only
    // the RESULT crosses back, and the Compose state is assigned after
    // `withContext` returns — i.e. on the composition's own dispatcher. Writing
    // those states from inside the IO block gave the frame no happens-before
    // with them, and two overlapping opens could publish a file from one copy
    // with a title parsed from another.
    LaunchedEffect(source, shortcutId) {
        val staged = withContext(Dispatchers.IO) {
            when (source) {
                is WebAppSource.ChatAttachment -> {
                    // [sweep1014-b7a] Use SessionFileStore's same root
                    // (filesDir/minis-sessions/<sid>/attachments/) so the
                    // copy lives inside the orphan-scan / deleteSession
                    // cleanup face instead of a detached filesDir/sessions/.
                    val attachmentsDir = File(
                        SessionFileStore(context).sessionsRoot,
                        "${source.sessionId}/attachments/$shortcutId",
                    )
                    attachmentsDir.mkdirs()
                    // Sanitize the ContentResolver DISPLAY_NAME: strip any
                    // directory components so a hostile `../../x.html` (or an
                    // absolute path) can't escape the shortcut subdir, and fall
                    // back to "page.html" when nothing usable remains.
                    val safeName = sanitizeFileName(source.fileName) ?: "page.html"
                    val target = File(attachmentsDir, safeName)
                    runCatching {
                        context.contentResolver.openInputStream(source.uri)?.use { input ->
                            target.outputStream().use { out -> input.copyTo(out) }
                        } ?: error("openInputStream returned null")
                    }.map {
                        StagedCopy(
                            file = target,
                            title = parseHtmlTitle(target),
                            iconPath = WebAppIconExtractor.extractFromHtml(target.absolutePath)?.localPath,
                            failed = false,
                        )
                    }.getOrElse { StagedCopy(file = null, title = null, iconPath = null, failed = true) }
                }
                is WebAppSource.HostFile -> {
                    if (!source.file.exists() || !source.file.isFile) {
                        StagedCopy(file = null, title = null, iconPath = null, failed = true)
                    } else {
                        StagedCopy(
                            file = source.file,
                            title = parseHtmlTitle(source.file),
                            iconPath = WebAppIconExtractor.extractFromHtml(source.file.absolutePath)?.localPath,
                            failed = false,
                        )
                    }
                }
            }
        }
        copiedFile = staged.file
        parsedTitle = staged.title
        webpageIconPath = staged.iconPath
        copyFailed = staged.failed
    }

    // [dv14-B2] The field is seeded from the de-suffixed file name up front;
    // the async parsed <title> backfills it only while the user hasn't typed
    // anything of their own. (Pre-fix: an empty initial value raced the async
    // parse — the first frame blanked the field from fileName, so the parsed
    // <title> was silently dropped 100% of the time.)
    var titleText by remember(fileName) { mutableStateOf(webAppTitleFallback(fileName)) }
    var userEditedTitle by remember { mutableStateOf(false) }
    LaunchedEffect(parsedTitle) {
        webAppTitleBackfill(parsedTitle, userEditedTitle)?.let { titleText = it }
    }

    var selectedIcon by remember { mutableStateOf<IconChoice>(IconChoice.Preset(IconPreset.Globe)) }

    // Auto-promote to webpage icon once we discover one.
    LaunchedEffect(webpageIconPath) {
        if (webpageIconPath != null && selectedIcon is IconChoice.Preset) {
            selectedIcon = IconChoice.Webpage(webpageIconPath!!)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            selectedIcon = IconChoice.Gallery(uri)
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            sheetClosed = true
            cleanupCopiedAttachment()
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.webapp_add_to_home),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            // Title input.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.webapp_sheet_title_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                OutlinedTextField(
                    value = titleText,
                    onValueChange = {
                        userEditedTitle = true
                        titleText = sanitizeSingleLineInput(it)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Source label (read-only). For chat attachments we show the
            // linked session id/title; for FileBrowser host files we show
            // the absolute Linux path so the user knows what's being linked.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.webapp_sheet_session_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                val sourceLabel = when (source) {
                    is WebAppSource.ChatAttachment ->
                        source.sessionTitle?.takeIf { it.isNotBlank() } ?: source.sessionId
                    is WebAppSource.HostFile -> source.linuxPath
                }
                Text(
                    text = sourceLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Icon selector.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.webapp_sheet_icon_label),
                    style = MaterialTheme.typography.labelLarge,
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Webpage icon (only if extractor found one).
                    webpageIconPath?.let { path ->
                        item {
                            IconOption(
                                selected = selectedIcon is IconChoice.Webpage,
                                onClick = { selectedIcon = IconChoice.Webpage(path) },
                                contentDescription = stringResource(R.string.webapp_sheet_icon_webpage),
                            ) {
                                // [audit3-F-13] Bounded decode. This tile is
                                // 36.dp, but the bare `decodeFile` here
                                // materialised the whole source on the Compose
                                // thread — a 12 Mpx webpage icon is ~48 MB of
                                // ARGB_8888 for a thumbnail, and a 4K one blew
                                // past the canvas draw ceiling.
                                // [fix/dv21-b4-B08] …and the bounded decode was
                                // still running ON that thread, inside a
                                // `remember {}`: composition blocked until the
                                // file was read and sampled. `produceState`
                                // keeps the same call and moves it to
                                // Dispatchers.IO; the tile shows the
                                // placeholder icon until it lands.
                                val bmp by produceState<Bitmap?>(initialValue = null, path) {
                                    value = withContext(Dispatchers.IO) {
                                        ImageDecoding.decodeFileBounded(path, ICON_MAX_EDGE_PX)?.bitmap
                                    }
                                }
                                val bmpNow = bmp
                                if (bmpNow != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = bmpNow.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp),
                                    )
                                } else {
                                    Icon(Icons.Default.Web, contentDescription = null)
                                }
                            }
                        }
                    }

                    // 8 presets.
                    items(IconPreset.values().toList()) { preset ->
                        IconOption(
                            selected = (selectedIcon as? IconChoice.Preset)?.preset == preset,
                            onClick = { selectedIcon = IconChoice.Preset(preset) },
                            contentDescription = preset.name,
                        ) {
                            Icon(preset.imageVector, contentDescription = null)
                        }
                    }
                }

                // Gallery picker entry.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val galleryUri = (selectedIcon as? IconChoice.Gallery)?.uri
                    IconOption(
                        selected = selectedIcon is IconChoice.Gallery,
                        onClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        contentDescription = stringResource(R.string.webapp_sheet_icon_pick),
                    ) {
                        if (galleryUri != null) {
                            // [fix/dv21-b4-B08] `loadBitmapFromUri` reads the
                            // WHOLE picked image into memory (`readBytes()`) and
                            // then decodes it — in a `remember {}` that ran on
                            // the composition thread, so choosing a multi-MB
                            // gallery photo stalled the sheet's next frame for
                            // as long as the read + decode took.
                            val bmp by produceState<Bitmap?>(initialValue = null, galleryUri) {
                                value = withContext(Dispatchers.IO) {
                                    loadBitmapFromUri(context, galleryUri)
                                }
                            }
                            val bmpNow = bmp
                            if (bmpNow != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bmpNow.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                )
                            } else {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                            }
                        } else {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                        }
                    }
                    Text(
                        text = stringResource(R.string.webapp_sheet_icon_pick),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (copyFailed) {
                Text(
                    text = stringResource(R.string.webapp_pin_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Buttons row.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        // [deep-audit-1048 B-06] Cancel is a teardown hook: latch
                        // first, then clean up. A pin already in flight then stops
                        // at its post-staging guard instead of inserting a row for
                        // a shortcut the user has cancelled.
                        sheetClosed = true
                        cleanupCopiedAttachment()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
                Button(
                    // [deep-audit-1048 B-06] `isPinning` closes the double-tap
                    // window; `sheetClosed` keeps the last frame inert.
                    enabled = pinActionEnabled(
                        hasTarget = copiedFile != null,
                        hasTitle = titleText.isNotBlank(),
                        inFlight = isPinning,
                        closed = sheetClosed,
                    ),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        // Read-then-set in the same frame: Compose re-composes
                        // asynchronously, so `enabled` alone cannot stop a
                        // second tap that lands before the next frame.
                        if (isPinning) return@Button
                        val target = copiedFile ?: return@Button
                        isPinning = true
                        val finalTitle = titleText.trim().ifBlank {
                            fileName.removeSuffix(".html").removeSuffix(".htm")
                        }
                        scope.launch {
                            // [deep-audit-1048 B-06] `finally` releases the
                            // in-flight flag on every exit (success, pin failure,
                            // or the post-staging abort below), so a future edit that
                            // moves `onDismiss()` cannot leave the button dead.
                            try {
                                val iconBitmap = withContext(Dispatchers.IO) {
                                    renderIconBitmap(context, selectedIcon, finalTitle)
                                }

                                // [deep-audit-1048 B-06] The sheet may have been
                                // torn down while `renderIconBitmap` ran (Cancel,
                                // drag-to-dismiss, or an external removal of the
                                // sheet target). Committing now would insert a
                                // shortcut row and add a launcher icon for a pin the
                                // user already cancelled.
                                if (!pinShouldCommit(closed = sheetClosed, inFlight = isPinning)) {
                                    cleanupCopiedAttachment()
                                    return@launch
                                }
                                val iconRef = when (val c = selectedIcon) {
                                    is IconChoice.Webpage -> "html"
                                    is IconChoice.Gallery -> "file:${c.uri}"
                                    is IconChoice.Preset -> "preset:${c.preset.name.lowercase()}"
                                }
                                val app = context.applicationContext as MinisApp
                                val entity = when (source) {
                                    is WebAppSource.ChatAttachment -> {
                                        // htmlPath stays relative under the
                                        // session attachments dir — WebAppPathResolver's
                                        // "relative path" branch resolves it back to
                                        // <filesDir>/minis-sessions/<sid>/attachments/<htmlPath>.
                                        val htmlPath = "$shortcutId/${target.name}"
                                        app.webAppShortcutRepository.create(
                                            htmlPath = htmlPath,
                                            pathScope = WebAppShortcutRepository.SCOPE_SESSION_ATTACHMENT,
                                            scopeContext = source.sessionId,
                                            title = finalTitle,
                                            iconRef = iconRef,
                                            iconCachePath = null,
                                            sourceSessionId = source.sessionId,
                                        )
                                    }
                                    is WebAppSource.HostFile -> {
                                        // T-pwa-3: link in place — htmlPath is the
                                        // /var/minis/... linux path; WebAppPathResolver
                                        // routes through PRootKernel.resolveHostPath.
                                        app.webAppShortcutRepository.create(
                                            htmlPath = source.linuxPath,
                                            pathScope = source.pathScope,
                                            scopeContext = source.scopeContext,
                                            title = finalTitle,
                                            iconRef = iconRef,
                                            iconCachePath = null,
                                            sourceSessionId = null,
                                        )
                                    }
                                }
                                val ok = ShortcutPinner.pin(context, entity, iconBitmap)
                                if (ok) {
                                    pinned = true
                                    Toast.makeText(
                                        context,
                                        R.string.webapp_pin_success,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    // [da1021-F] Pin failed: the DB row was already
                                    // inserted by `create(...)` above — delete it too,
                                    // or `webapp_shortcuts` keeps an orphan record
                                    // pointing at a copy this sheet just cleaned up.
                                    // `delete` is suspend; this runs inside the
                                    // coroutine scope the pin block already launched.
                                    runCatching { app.webAppShortcutRepository.delete(entity.id) }
                                    cleanupCopiedAttachment()
                                }
                                onDismiss()
                            } finally {
                                isPinning = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.webapp_sheet_cta))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * [deep-audit-1048 B-01] Result of the off-main staging step: the copied (or
 * in-place) file plus everything parsed from it. Produced on Dispatchers.IO,
 * consumed on the composition's dispatcher — the state writes happen only after
 * `withContext` returns.
 */
private data class StagedCopy(
    val file: File?,
    val title: String?,
    val iconPath: String?,
    val failed: Boolean,
)

@Composable
private fun IconOption(
    selected: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .size(48.dp)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp),
            )
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// internal (was private) so presetGlyphKind doesn't expose a private-in-file type.
internal enum class IconPreset(val imageVector: ImageVector) {
    Globe(Icons.Default.Public),
    Chart(Icons.Default.Insights),
    Book(Icons.Default.Book),
    App(Icons.Default.Apps),
    Video(Icons.Default.VideoLibrary),
    Game(Icons.Default.SportsEsports),
    Music(Icons.Default.MusicNote),
    Generic(Icons.Default.AppShortcut),
}

// internal (was private) so the preset→glyph dispatch is JVM-testable.
internal enum class PresetGlyph { Globe, Chart, Book, AppGrid, Video, Game, Music, Generic }

/**
 * [sweep1014-b7b] Map each [IconPreset] to a distinct glyph kind.
 * Top-level + internal so the 8-preset dispatch table is unit-testable
 * (all 8 presets → 8 distinct glyphs, none falling back to the title glyph).
 */
internal fun presetGlyphKind(preset: IconPreset): PresetGlyph = when (preset) {
    IconPreset.Globe -> PresetGlyph.Globe
    IconPreset.Chart -> PresetGlyph.Chart
    IconPreset.Book -> PresetGlyph.Book
    IconPreset.App -> PresetGlyph.AppGrid
    IconPreset.Video -> PresetGlyph.Video
    IconPreset.Game -> PresetGlyph.Game
    IconPreset.Music -> PresetGlyph.Music
    IconPreset.Generic -> PresetGlyph.Generic
}

/**
 * [sweep1014-b7b] Draw a simple white vector glyph for the chosen preset,
 * inside the existing Paint/Canvas system (no new resource files).
 */
private fun drawPresetGlyph(preset: IconPreset, canvas: Canvas, size: Int) {
    val c = size / 2f
    val stroke = Paint().apply {
        isAntiAlias = true
        color = AGColor.WHITE
        style = Paint.Style.STROKE
        strokeWidth = size * 0.05f
        strokeCap = Paint.Cap.ROUND
    }
    val fill = Paint().apply {
        isAntiAlias = true
        color = AGColor.WHITE
        style = Paint.Style.FILL
    }
    when (presetGlyphKind(preset)) {
        PresetGlyph.Globe -> {
            canvas.drawCircle(c, c, size * 0.28f, stroke)
            canvas.drawLine(c - size * 0.28f, c, c + size * 0.28f, c, stroke)
            canvas.drawOval(
                RectF(c - size * 0.12f, c - size * 0.28f, c + size * 0.12f, c + size * 0.28f),
                stroke,
            )
        }
        PresetGlyph.Chart -> {
            val w = size * 0.11f
            val bottom = c + size * 0.24f
            canvas.drawRect(c - size * 0.22f, bottom - size * 0.20f, c - size * 0.22f + w, bottom, fill)
            canvas.drawRect(c - w / 2f, bottom - size * 0.42f, c + w / 2f, bottom, fill)
            canvas.drawRect(c + size * 0.22f - w, bottom - size * 0.30f, c + size * 0.22f, bottom, fill)
        }
        PresetGlyph.Book -> {
            val half = size * 0.22f
            canvas.drawRoundRect(
                RectF(c - half, c - size * 0.26f, c + half, c + size * 0.26f),
                size * 0.03f, size * 0.03f, stroke,
            )
            canvas.drawLine(c, c - size * 0.26f, c, c + size * 0.26f, stroke)
        }
        PresetGlyph.AppGrid -> {
            val s = size * 0.15f
            val off = s + size * 0.05f
            for (ox in listOf(-off, off)) for (oy in listOf(-off, off)) {
                canvas.drawRoundRect(
                    RectF(c + ox - s, c + oy - s, c + ox + s, c + oy + s),
                    size * 0.03f, size * 0.03f, fill,
                )
            }
        }
        PresetGlyph.Video -> {
            val r = size * 0.24f
            val tri = Path().apply {
                moveTo(c - r * 0.7f, c - r)
                lineTo(c + r * 0.9f, c)
                lineTo(c - r * 0.7f, c + r)
                close()
            }
            canvas.drawPath(tri, fill)
        }
        PresetGlyph.Game -> {
            val a = size * 0.08f
            val l = size * 0.20f
            canvas.drawRect(c - l - a, c - a, c + l + a, c + a, fill)
            canvas.drawRect(c - a, c - l - a, c + a, c + l + a, fill)
            canvas.drawCircle(c + size * 0.30f, c - size * 0.10f, size * 0.045f, fill)
            canvas.drawCircle(c + size * 0.30f, c + size * 0.10f, size * 0.045f, fill)
        }
        PresetGlyph.Music -> {
            val headR = size * 0.09f
            val hx = c - size * 0.08f
            val hy = c + size * 0.16f
            canvas.drawCircle(hx, hy, headR, fill)
            canvas.drawLine(hx + headR, hy, hx + headR, c - size * 0.22f, stroke)
        }
        PresetGlyph.Generic -> {
            val half = size * 0.26f
            canvas.drawRoundRect(
                RectF(c - half, c - half, c + half, c + half),
                size * 0.08f, size * 0.08f, stroke,
            )
            canvas.drawCircle(c, c, size * 0.08f, fill)
        }
    }
}

private sealed class IconChoice {
    data class Preset(val preset: IconPreset) : IconChoice()
    data class Webpage(val path: String) : IconChoice()
    data class Gallery(val uri: Uri) : IconChoice()
}

// ── helpers ─────────────────────────────────────────────────────────────────

private val TITLE_RE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

private fun parseHtmlTitle(file: File): String? {
    val head = runCatching {
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            val n = input.read(buf)
            if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
        }
    }.getOrNull() ?: return null
    val m = TITLE_RE.find(head) ?: return null
    return m.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
}

/**
 * [audit3-F-13] Longest edge of a decoded icon bitmap, in pixels. Matches the
 * 192x192 adaptive-ready icon [renderIconBitmap] builds (108dp safe zone) and
 * the 36.dp preview tile, so nothing is decoded that cannot be shown.
 */
private const val ICON_MAX_EDGE_PX = 192

/**
 * [deep-audit-1047 B-02] Cap on the picked image's ENCODED bytes.
 *
 * The decode itself is already bounded ([ImageDecoding.decodeBytesBounded]
 * samples down to [ICON_MAX_EDGE_PX]), but the byte array it is handed was not:
 * `readBytes()` reads whatever the user picked, so a several-hundred-MB image
 * allocated its full size in the heap before the bound applied — the bound was
 * on pixels, the OOM is on bytes.
 *
 * The number is the repo's existing "largest raw user-picked file we still
 * accept" figure (32 MiB, same as `FileReadTool.MAX_INPUT_BYTES` /
 * `MAX_SKILL_IMPORT_BYTES`) rather than a value invented for this screen: an
 * encoded image that cannot carry a 192px icon within that budget is not a
 * usable icon source, and nothing here is a document import that would justify
 * a larger ceiling.
 */
internal const val MAX_ICON_SOURCE_BYTES = 32L * 1024 * 1024

/**
 * Bounded decode of a picked image. Reads the encoded bytes once — with a
 * STREAM-level cap, not a declared-size precheck (a provider may report
 * nothing, or lie) — and hands them to [ImageDecoding.decodeBytesBounded],
 * which probes the header (`inJustDecodeBounds`), samples the decode, and
 * shrinks to the exact bound, so a 50 Mpx gallery photo never allocates its
 * ~200 MB full-resolution bitmap just to fill a 36.dp tile.
 *
 * [deep-audit-1047 B-02] The read used to be a bare `readBytes()`: the
 * pixel bound could not help bytes that were already in the heap, so picking a
 * multi-hundred-MB image OOM'd before any decode ran. [readAtMost] stops one
 * byte past [MAX_ICON_SOURCE_BYTES] and reports the overflow as null, which
 * this function already maps to "no icon" (the caller falls back to the
 * preset/title glyph).
 *
 * Callers must stay off the main thread: both call sites wrap this in
 * `withContext(Dispatchers.IO)`.
 */
private fun loadBitmapFromUri(
    context: Context,
    uri: Uri,
    maxEdge: Int = ICON_MAX_EDGE_PX,
): Bitmap? = runCatching {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        readAtMost(input, MAX_ICON_SOURCE_BYTES)
    } ?: return@runCatching null
    ImageDecoding.decodeBytesBounded(bytes, maxEdge)?.bitmap
}.getOrNull()

/**
 * Render a 192x192 adaptive-ready bitmap for the launcher pin. Adaptive
 * icons need 108dp safe-zone inside a 108dp shape; we go with 192x192px
 * which most launchers accept and crop down as needed.
 */
private fun renderIconBitmap(context: Context, choice: IconChoice, title: String): Bitmap {
    val size = 192
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    val bg = Paint().apply {
        isAntiAlias = true
        color = AGColor.parseColor("#FF3B82F6")
    }
    canvas.drawRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), bg)

    when (choice) {
        is IconChoice.Webpage -> {
            // [audit3-F-13] Bounded decode + explicit recycling: the pre-fix
            // code decoded the whole file, then `createScaledBitmap` left both
            // the full-size source and the 192x192 copy unreachable-but-alive
            // (bitmap pixels are native memory ART's GC does not see).
            val src = ImageDecoding.decodeFileBounded(choice.path, size)?.bitmap
            if (src != null) {
                // Kept on purpose: the source keeps its aspect ratio while the
                // launcher icon is square, and the pre-fix code stretched the
                // source over the whole canvas. Dropping this pass would
                // letterbox every non-square icon — a visible change that has
                // nothing to do with the decode bound.
                val scaled = Bitmap.createScaledBitmap(src, size, size, true)
                canvas.drawBitmap(scaled, 0f, 0f, null)
                // `canvas` is a software canvas (Canvas(bmp) below), so the
                // pixels are copied during drawBitmap and both temporaries can
                // go immediately. `createScaledBitmap` returns its own input
                // when no scaling is needed, hence the identity check.
                if (scaled !== src) scaled.recycle()
                src.recycle()
                return bmp
            }
            drawTitleGlyph(canvas, title, size)
        }
        is IconChoice.Gallery -> {
            val src = loadBitmapFromUri(context, choice.uri, maxEdge = size)
            if (src != null) {
                val scaled = Bitmap.createScaledBitmap(src, size, size, true)
                canvas.drawBitmap(scaled, 0f, 0f, null)
                if (scaled !== src) scaled.recycle()
                src.recycle()
                return bmp
            }
            drawTitleGlyph(canvas, title, size)
        }
        is IconChoice.Preset -> {
            drawPresetGlyph(choice.preset, canvas, size)
        }
    }
    return bmp
}

private fun drawTitleGlyph(canvas: Canvas, title: String, size: Int) {
    val glyph = title.trim().firstOrNull()?.toString()?.uppercase() ?: "M"
    val paint = Paint().apply {
        isAntiAlias = true
        color = AGColor.WHITE
        textSize = size * 0.5f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(glyph, size / 2f, baseline, paint)
}

/**
 * Strip directory components from a user-influenced file name (the
 * ContentResolver DISPLAY_NAME) so it can be safely joined under a shortcut
 * subdir. `File(fileName).name` keeps only the last path segment, which turns
 * `../../evil.html`, `/abs/evil.html`, and `..\\evil.html` into `evil.html`.
 * Returns null when the name is blank or degenerates to `.` / `..` (nothing
 * usable), letting the caller fall back to its default.
 *
 * Top-level (not a member) so it can be unit-tested with zero Android deps.
 */
internal fun sanitizeFileName(fileName: String): String? {
    val last = fileName.trim().let { if (it.isEmpty()) "" else File(it).name }
    return when (last) {
        "", ".", ".." -> null
        else -> last
    }
}

/**
 * [dv14-B2] Initial title: the de-suffixed file name. Pure (zero Android
 * deps) so the seed decision is JVM-testable.
 */
internal fun webAppTitleFallback(fileName: String): String =
    fileName.removeSuffix(".html").removeSuffix(".htm")

/**
 * [dv14-B2] Backfill decision: the async parsed `<title>` may replace the
 * seed only while the user hasn't edited the field and the parse produced a
 * non-blank title; returns null = keep the current field value. Pure.
 */
internal fun webAppTitleBackfill(parsedTitle: String?, userEditedTitle: Boolean): String? {
    if (userEditedTitle) return null
    if (parsedTitle.isNullOrBlank()) return null
    return parsedTitle
}
