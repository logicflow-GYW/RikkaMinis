package com.rikkaminis.app.ui.chat

// [T-android-split-chat] Tail composables extracted verbatim from ChatScreen.kt:
// BorderedMarkdownTable, FallbackInfoBlock, CompactSummarySheet,
// parseInlineMarkdown, rememberBrowserLiveSnapshot, ResumeBanner, SwipeToSendHint.
// Full import block copied from ChatScreen.kt (unused = warnings). Externally
// called ones flipped private->internal; cluster-only ones stay private.

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.automirrored.filled.Article
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.rikkaminis.app.BuildConfig
import com.rikkaminis.app.R
import com.rikkaminis.app.data.FileMentionIndex
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.ui.components.MinisAlertDialog
import com.rikkaminis.app.ui.components.MinisMenu
import com.rikkaminis.app.ui.components.MinisMenuDivider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rikkaminis.app.offload.OffloadPermissionManager
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ModelGroup
import com.rikkaminis.app.data.model.ProviderConfig
import com.rikkaminis.app.data.model.ProviderType
import com.rikkaminis.app.data.model.RoutingStrategy
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.data.repository.ChatRepository
import com.rikkaminis.app.data.repository.MemoryRepository
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.ui.browser.BrowserSheet
import com.rikkaminis.app.ui.theme.ChatColors
import com.rikkaminis.app.ui.components.MinisTextButton

// ─── Bordered Markdown Table (iOS style: bordered cells with grid lines) ─────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BorderedMarkdownTable(
    content: String,
    node: ASTNode,
    textStyle: TextStyle,
) {
    val borderColor = ChatColors.tableBorder
    val inlineCodeText = ChatColors.inlineCodeText
    val inlineCodeBg = ChatColors.inlineCodeBg
    val linkColor = ChatColors.link
    val headerStyle = textStyle.copy(fontWeight = FontWeight.SemiBold)

    // [T-markdown-table-copy-actions] long-press → context menu with
    // Copy Table (markdown text) + Copy Table Image (rendered bitmap).
    // Mirrors iOS SelectableMarkdownView.copyTable/copyTableImage UX.
    // Capture target Bitmap via Compose GraphicsLayer (the only Compose-
    // first way to materialise an arbitrary composable subtree); writing
    // via FileProvider matches the existing copyBitmapToClipboard helper
    // (FullscreenImageViewer.kt) so paste targets receive a real image
    // clip URI with read-grant, not just text.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val graphicsLayer = rememberGraphicsLayer()
    var menuExpanded by remember { mutableStateOf(false) }
    val copyTableLabel = stringResource(R.string.markdown_table_copy_table)
    val copyTableImageLabel = stringResource(R.string.markdown_table_copy_table_image)
    val copiedToast = stringResource(R.string.markdown_table_copied_toast)
    val imageCopiedToast = stringResource(R.string.markdown_table_image_copied_toast)
    val copyImageFailedToast = stringResource(R.string.markdown_table_image_copy_failed_toast)

    // Parse table from AST node children
    val rows = mutableListOf<List<String>>()
    var isHeader = true
    val headerRowIndices = mutableSetOf<Int>()

    for (child in node.children) {
        val typeName = child.type.toString()
        if (typeName == "TABLE_ROW" || typeName == "TABLE_HEADER") {
            val cells = mutableListOf<String>()
            for (cellNode in child.children) {
                val cellType = cellNode.type.toString()
                if (cellType == "TABLE_CELL" || cellType == "TABLE_HEADER_CELL") {
                    cells.add(cellNode.getTextInNode(content).toString().trim())
                }
            }
            if (cells.isNotEmpty()) {
                if (typeName == "TABLE_HEADER") {
                    headerRowIndices.add(rows.size)
                }
                rows.add(cells)
            }
        }
    }

    // Fallback: parse from raw content if AST parsing yields nothing
    if (rows.isEmpty()) {
        val lines = content.substring(node.startOffset, node.endOffset)
            .lines()
            .filter { it.isNotBlank() }
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            // Skip separator lines (e.g., |---|---|)
            if (trimmed.matches(Regex("^\\|?[\\s\\-:|]+\\|?$"))) continue
            val cells = trimmed.split("|")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (cells.isNotEmpty()) {
                if (rows.isEmpty()) headerRowIndices.add(0)
                rows.add(cells)
            }
        }
    }

    if (rows.isEmpty()) return

    val colCount = rows.maxOf { it.size }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp))
                // Capture rendered pixels into a GraphicsLayer so the
                // Copy Table Image action can materialise the exact
                // styled table the user sees (cells, borders, header
                // shading, inline code) without re-rendering. The
                // drawWithContent layer must wrap the FULL composition
                // including the border / background, hence on the outer
                // Column rather than per-row.
                .drawWithContent {
                    // Record this draw pass into the GraphicsLayer so the
                    // Copy Table Image action can later materialise it via
                    // toImageBitmap(). DrawScope.drawLayer(GraphicsLayer) is
                    // the canonical compose-ui 1.7+ way to also render the
                    // recorded content as the visible output.
                    graphicsLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(graphicsLayer)
                }
                .combinedClickable(
                    onClick = {},
                    onLongClick = { menuExpanded = true },
                    // No visible ripple — the table is content, not a button.
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                ),
        ) {
            rows.forEachIndexed { rowIndex, cells ->
                val isHeaderRow = rowIndex in headerRowIndices
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .then(
                            if (isHeaderRow) Modifier.background(ChatColors.secondaryBg)
                            else Modifier
                        ),
                ) {
                    for (colIndex in 0 until colCount) {
                        if (colIndex > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(1.dp)
                                    .background(borderColor),
                            )
                        }
                        val cellText = cells.getOrElse(colIndex) { "" }
                        val cellStyle = if (isHeaderRow) headerStyle else textStyle
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = parseInlineMarkdown(cellText, cellStyle.copy(fontSize = 14.sp), inlineCodeText, inlineCodeBg, linkColor),
                                style = cellStyle.copy(fontSize = 14.sp),
                            )
                        }
                    }
                }
                // Row divider (not after last)
                if (rowIndex < rows.size - 1) {
                    HorizontalDivider(color = borderColor)
                }
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(copyTableLabel) },
                onClick = {
                    menuExpanded = false
                    // Copy the original markdown segment that produced this
                    // table — exact round-trip, no re-serialisation drift
                    // (e.g. cell padding, escape chars, alignment row). Guard
                    // against pathological AST offsets that fall outside the
                    // backing string (defensive: a malformed parse upstream
                    // would otherwise crash the UI thread).
                    val start = node.startOffset.coerceIn(0, content.length)
                    val end = node.endOffset.coerceIn(start, content.length)
                    val md = content.substring(start, end)
                    if (md.isNotEmpty()) {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("table", md))
                        android.widget.Toast.makeText(context, copiedToast, android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
            )
            DropdownMenuItem(
                text = { Text(copyTableImageLabel) },
                onClick = {
                    menuExpanded = false
                    scope.launch {
                        try {
                            // toImageBitmap() reads what drawWithContent
                            // captured on the most recent draw pass — i.e.
                            // exactly the table at its current size /
                            // layout. Convert through android.graphics.Bitmap
                            // so we can compress to PNG.
                            val imageBitmap = graphicsLayer.toImageBitmap()
                            val androidBitmap = imageBitmap.asAndroidBitmap()
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val shareDir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                                val outFile = java.io.File(shareDir, "table_${System.currentTimeMillis()}.png")
                                outFile.outputStream().use {
                                    androidBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                                }
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    outFile,
                                )
                                // Grant read so paste targets (Photos, Gboard
                                // preview, Files) can openInputStream() without
                                // SecurityException — mirrors copyBitmapToClipboard
                                // in FullscreenImageViewer.kt.
                                context.grantUriPermission(
                                    "*", uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                )
                                val clip = android.content.ClipData.newUri(
                                    context.contentResolver, "table-image", uri,
                                )
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    cm.setPrimaryClip(clip)
                                    android.widget.Toast.makeText(
                                        context, imageCopiedToast, android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(
                                context,
                                copyImageFailedToast,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            )
        }
    }
}

/**
 * Parse inline markdown (bold, italic, code, strikethrough, links) into AnnotatedString.
 */
// ─── System Notice Card (compact divider / slash notices / fallback rows) ────
//
// Every system notice (compact-summary divider, slash-command notice,
// model-switch fallback) renders as one rounded surface card: leading
// kind icon, 13sp label, trailing chevron when the row carries a
// payload. FallbackInfoBlock documents why this replaced the iOS-style
// hairline (unreadable 10sp text + a 14dp tap target).

@Composable
internal fun FallbackInfoBlock(block: AssistantBlock, onRevert: (() -> Unit)? = null) {
    // [fix/compact-divider-ux] This used to be a 1dp hairline with 10sp
    // secondary-grey text sitting on it (an iOS systemDividerRow port) and
    // a 14dp info glyph as the only tap target. On Android that reads as a
    // rendering artefact rather than a notice, and 14dp is far below the
    // 48dp touch minimum — users never discovered the summary sheet. Now:
    // a rounded surface card, 13sp medium-weight text in primary colour,
    // at least 44dp tall, tappable across its whole width whenever it
    // carries a detail payload.
    val surface = ChatColors.toolCapsuleBg
    val fg = ChatColors.primaryText
    val accent = ChatColors.secondaryText
    val icon = when (block.toolName) {
        // T84: CloseFullscreen is still the closest built-in glyph to
        // iOS's arrow.down.right.and.arrow.up.left (fold/collapse).
        "compact" -> Icons.Default.CloseFullscreen
        "memory" -> Icons.Default.Psychology
        "thinking" -> Icons.Default.Lightbulb
        else -> Icons.Default.Info
    }
    // A block carrying a payload (e.g. a compact summary) opens the detail
    // sheet; tapping anywhere on the card does it, not just the glyph.
    val hasDetail = block.toolArgs.isNotEmpty()
    var showDetail by remember(block.id) { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(surface)
            .then(if (hasDetail) Modifier.clickable { showDetail = true } else Modifier)
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = block.content,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = fg,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (hasDetail) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = stringResource(R.string.chat_show_full_summary),
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }

    if (showDetail && hasDetail) {
        CompactSummarySheet(
            summary = block.toolArgs,
            onDismiss = { showDetail = false },
            onRevert = onRevert,
        )
    }
}

/**
 * Bottom sheet showing the full compact summary. Mirrors iOS
 * `CompactSummarySheet`: scrollable, copy-to-clipboard, dismiss button.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CompactSummarySheet(
    summary: String,
    onDismiss: () -> Unit,
    onRevert: (() -> Unit)? = null,
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var showRevertConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    StandardChatSheet(
        title = stringResource(R.string.chat_compact_summary),
        onDismiss = onDismiss,
        leadingAction = {
            IconButton(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(summary))
                copied = true
                scope.launch {
                    kotlinx.coroutines.delay(1500)
                    copied = false
                }
            }) {
                Icon(
                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.selection_copy),
                    tint = if (copied) ChatColors.success else ChatColors.secondaryText,
                )
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            androidx.compose.foundation.text.selection.SelectionContainer(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = summary,
                    fontSize = 14.sp,
                    color = ChatColors.primaryText,
                    lineHeight = 20.sp,
                )
            }
            if (onRevert != null) {
                HorizontalDivider(color = ChatColors.separator)
                MinisTextButton(
                    onClick = { showRevertConfirm = true },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.chat_revert_compact),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }

    if (showRevertConfirm && onRevert != null) {
        MinisAlertDialog(
            onDismissRequest = { showRevertConfirm = false },
            title = stringResource(R.string.chat_revert_compact_title),
            text = stringResource(R.string.chat_revert_compact_confirm),
            confirmText = stringResource(R.string.logs_config_revert),
            onConfirm = {
                showRevertConfirm = false
                onDismiss()
                onRevert()
            },
            isDestructive = true,
        )
    }
}

private fun parseInlineMarkdown(
    text: String,
    baseStyle: TextStyle,
    inlineCodeText: Color,
    inlineCodeBg: Color,
    linkColor: Color,
): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // Bold + italic: ***text***
                text.startsWith("***", i) -> {
                    val end = text.indexOf("***", i + 3)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 3, end))
                        }
                        i = end + 3
                    } else {
                        append(text[i]); i++
                    }
                }
                // Bold: **text** or __text__
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i]); i++
                    }
                }
                // Strikethrough: ~~text~~
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i]); i++
                    }
                }
                // Inline code: `text`
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end != -1) {
                        withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            color = inlineCodeText,
                            background = inlineCodeBg,
                        )) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }
                // Link: [text](url)
                text[i] == '[' -> {
                    val closeBracket = text.indexOf(']', i + 1)
                    if (closeBracket != -1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                        val closeParen = text.indexOf(')', closeBracket + 2)
                        if (closeParen != -1) {
                            val linkText = text.substring(i + 1, closeBracket)
                            withStyle(SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                            )) {
                                append(linkText)
                            }
                            i = closeParen + 1
                        } else {
                            append(text[i]); i++
                        }
                    } else {
                        append(text[i]); i++
                    }
                }
                // Italic: *text* or _text_  (single delimiter, not followed by another)
                (text[i] == '*' || text[i] == '_') && (i + 1 < text.length && text[i + 1] != text[i]) -> {
                    val delimiter = text[i]
                    val end = text.indexOf(delimiter, i + 1)
                    if (end != -1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }
                else -> {
                    append(text[i]); i++
                }
            }
        }
    }
}

// ─── Browser live-preview plumbing ──────────────────────────────────────────
//
// Mirrors iOS `takeBrowserSnapshot()` timer (ToolLiveSheet.swift:1803-1825):
// while a browser_use block is RUNNING/STREAMING, poll the active WebView at
// a fixed interval so the Minis Computer sheet, detail sheet, and floating
// thumbnail can show the current page state — not just screenshots saved by
// visualChangeActions (NAVIGATE/CLICK/SCROLL/HOVER/TYPE). Actions like
// get_readable, get_text, execute_js, fetch never save an imageFilePath, so
// without this they'd render a blank/globe placeholder.

// [T-android-split-chat] internal (was private) — referenced from ChatScreen.kt
// after the move.
internal val LocalBrowserTabPool = compositionLocalOf<com.rikkaminis.app.browser.BrowserTabPool?> { null }
internal val LocalToolPreviewEnabled = compositionLocalOf { false }

@Composable
internal fun rememberBrowserLiveSnapshot(
    block: AssistantBlock,
    intervalMs: Long = 3000L,
): android.graphics.Bitmap? {
    if (block.toolName != "browser_use") return null
    val isLive = block.toolStatus == ToolBlockStatus.RUNNING ||
        block.toolStatus == ToolBlockStatus.STREAMING ||
        block.toolStatus == ToolBlockStatus.PENDING
    // Only live blocks poll the WebView. Completed blocks fall through to
    // their own saved imageFilePath — capturing the active WebView for a
    // completed block would bleed the latest navigation's frame across all
    // earlier completed blocks (iOS parity: ToolLiveSheet resets
    // browserSnapshot on imageFilePath change, achieving the same result).
    if (!isLive) return null
    val tabPool = LocalBrowserTabPool.current ?: return null
    val lifecycleOwner = LocalLifecycleOwner.current
    val snapshot by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        block.id,
        tabPool,
        lifecycleOwner,
    ) {
        // [T-ui-lifecycle-polling] Gate the 3s WebView capture loop behind
        // STARTED: while the app is backgrounded (below STARTED) the loop is
        // cancelled and no frames are captured; returning to the foreground
        // restarts it automatically. repeatOnLifecycle keeps the produceState
        // coroutine alive across backgrounding — only the inner polling loop
        // suspends/cancels.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val first = tabPool.activeManager?.captureLiveSnapshot()
            if (first != null) value = first
            while (true) {
                kotlinx.coroutines.delay(intervalMs)
                val next = tabPool.activeManager?.captureLiveSnapshot() ?: continue
                value = next
            }
        }
    }
    return snapshot
}

/**
 * Resume banner shown at the visual bottom of the message list when an
 * agent loop was interrupted (manual stop / tool cancel / cold-start
 * with persisted-but-incomplete history) and is resumable. Mirrors iOS
 * `resumeBanner` in `CollectionViewMessageListV3.swift:360`:
 *
 *   - orange-tinted rounded rectangle background
 *   - leading "pause" icon + secondary text on the left
 *   - trailing orange-capsule "Resume" button
 *
 * Uses iOS systemOrange (`#FF9F0A` dark / `#FF9500` light flatten to
 * a single mid value, since it sits over a low-alpha tint and reads
 * the same on both themes).
 */
@Composable
internal fun ResumeBanner(onResume: () -> Unit) {
    val orange = Color(0xFFFF9500)
    // [T-android-c3a-resume-one-tap] Crash-aware resume. When the previous app
    // cycle ended in crash_or_stall we surface a one-line warning as CONTEXT
    // (the user is about to re-enter the load that killed the last cycle), but
    // the warning is informational only — a deliberate tap on the Resume BUTTON
    // is itself an explicit user action, so it resumes in one tap. (Previously
    // the first tap only armed the warning and a second tap was required; that
    // extra confirm was friction on an already-explicit gesture.)
    val showCrashWarning = com.rikkaminis.app.diagnostics.LaunchCycleBeacon.lastCycleWasCrash
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(orange.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = orange,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (showCrashWarning) {
                    stringResource(R.string.chat_resume_crash_warning)
                } else {
                    stringResource(R.string.resume_banner_title)
                },
                fontSize = 11.sp,
                color = if (showCrashWarning) MaterialTheme.colorScheme.error else ChatColors.secondaryText,
            )
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(orange)
                .clickable(onClick = { onResume() })
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(10.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.resume_action),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
}

/// Floating "send arrow + Release to send capsule" hint shown while the
/// user is dragging the input bar upward to send. Mirrors iOS
/// `SwipeToSendHint` in AIChatView.swift. Reuses `ChatColors.sendButton` /
/// `ChatColors.background` for theme-aware contrast (auto-inverts in dark
/// mode without needing a separate palette).
///
/// - `progress` 0..1: drag distance as a fraction of the trigger threshold.
///   Arrow opacity = `min(1, progress * 1.4)`. Capsule opacity ramps in over
///   the band `[armFraction - 0.4, armFraction]` so it reaches full opacity
///   exactly at the haptic / arming point.
/// - `armFraction`: progress at which release fires send (default 0.8 in
///   the caller, matching iOS).
/// - `location`: live fingertip position in the wrapping Box's local px.
///   Hint sits `hoverAbovePx` above this point, ~one thumb-tip away.
/// - `arrowHalfPx`: half the rendered arrow size, used to center the
///   arrow on the fingertip's X.
@Composable
internal fun SwipeToSendHint(
    progress: Float,
    armFraction: Float,
    location: Offset,
    hoverAbovePx: Float,
    arrowHalfPx: Float,
    /** When true, the capsule advertises "Release to queue" instead of
     *  "Release to send" — used while the agent is mid-stream, when the
     *  gesture routes through `enqueuePrompt()` instead of an immediate
     *  send. Lets the user see the gesture still works mid-stream. */
    isEnqueue: Boolean = false,
) {
    if (progress <= 0f) return
    val palette = ChatColors
    val chipBg = palette.sendButton
    val chipFg = palette.background
    // Capsule full opacity at `armFraction`. Linear from
    // `armFraction - 0.4` -> `armFraction`.
    val capsuleStart = (armFraction - 0.4f).coerceAtLeast(0f)
    val capsuleSpan = (armFraction - capsuleStart).coerceAtLeast(0.0001f)
    val capsuleAlpha = ((progress - capsuleStart) / capsuleSpan).coerceIn(0f, 1f)
    val rootAlpha = (progress * 1.4f).coerceIn(0f, 1f)
    val scale = 0.9f + 0.18f * progress
    val density = androidx.compose.ui.platform.LocalDensity.current
    val xDp = with(density) { (location.x - arrowHalfPx).toDp() }
    val yDp = with(density) { (location.y - hoverAbovePx - arrowHalfPx).toDp() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .offset(x = xDp, y = yDp)
            .graphicsLayer {
                alpha = rootAlpha
                scaleX = scale
                scaleY = scale
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
            },
    ) {
        // Send arrow disc — paints the same circle background + inverted
        // arrow as the real send button so the gesture reads as "drag the
        // message up to send".
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(chipBg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = null,
                tint = chipFg,
                modifier = Modifier.size(18.dp),
            )
        }
        // "Release to send" capsule — same height as the disc, opacity
        // ramps in over the second half of the drag so it doubles as a
        // "you're almost there" confirmation cue.
        Box(
            modifier = Modifier
                .height(34.dp)
                .background(chipBg, RoundedCornerShape(50))
                .padding(horizontal = 14.dp)
                .graphicsLayer { alpha = capsuleAlpha },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(
                    if (isEnqueue) R.string.composer_swipe_release_to_queue
                    else R.string.composer_swipe_release_to_send,
                ),
                color = chipFg,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
// Sun May 24 10:16:43 CST 2026
