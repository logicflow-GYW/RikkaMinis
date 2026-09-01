package com.openminis.app.ui.chat

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.widget.Toast
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
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material.icons.filled.Bolt
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
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.config.AttachActionCatalog
import com.openminis.app.config.ChatActionCatalog
import com.openminis.app.config.ChatMenuPrefs
import com.openminis.app.config.isChatActionAvailable
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
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
import com.openminis.app.offload.OffloadPermissionManager
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.browser.BrowserSheet
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.components.MinisTextButton

// iOS ChatColors equivalent — semantic status colors read from the chat
// palette so they follow the active light/dark theme.
internal val ToolCheckColor: Color
    @Composable
    @ReadOnlyComposable
    get() = ChatColors.success
internal val ToolErrorColor: Color
    @Composable
    @ReadOnlyComposable
    get() = ChatColors.error
// iOS .yellow / .pink have no chat-palette slot; keep fixed.
internal val ToolCancelColor = Color(0xFFFFCC00) // iOS .yellow
internal val ToolMemoryAccent = Color(0xFFFF2D55) // iOS .pink
// Sparkle gradient colors (iOS uses linear gradient)
internal val SparkleColor1 = Color(0xFFB8B096) // rgb(0.72, 0.69, 0.59)
internal val SparkleColor2 = Color(0xFF99998C) // rgb(0.6, 0.6, 0.55)

// T129: cap photo/video and file pickers at 50 items per launch. Above this
// count Android's PickMultipleVisualMedia silently truncates anyway, but our
// document picker has no native cap — so we apply the same limit on both
// sides and toast the user when their selection is trimmed. Mirrors iOS
// PHPickerConfiguration.selectionLimit = 50.
private const val ATTACHMENT_PICK_LIMIT = 50
// [forward-stable] Bottom sentinel row key — the single scroll target for
// every "go to bottom" request in the forward (non-reverse) chat list.
private const val ScrollBottomKey = "__scroll_bottom__"

/**
 * [T-android-send-no-autoscroll-behind-preview] Follow-grace window after a
 * user message append: within it the reserve-change pin bypasses the
 * isNearBottom gate (send intent is unambiguous; the freshly-inserted rows
 * make the live anchor transiently read "not at bottom").
 */
/**
 * [T-slash-picker-fixed-height port from iOS 73f1b94a] Locked popup
 * height for the slash and mention pickers: up to 4 rows are visible,
 * any overflow scrolls. Computed as `rowHeight * visibleRows + 8dp`.
 * [T-android-slash-menu-density] Rows were tightened (vertical padding
 * 10→7dp) so rowHeight ≈ 42dp covers a 14sp title + 11sp subtitle + 7dp
 * vertical padding; 42*4 + 8 ≈ 176dp. Keeps 4 rows visible with no extra
 * blank space at the bottom.
 */
private val SLASH_PICKER_FIXED_HEIGHT: Dp = 176.dp

/**
 * Draw a thin scroll thumb on the right edge of a [LazyColumn] (or any
 * scrollable) so the user can see at a glance that the list overflows
 * and is scrollable — mirrors iOS `.scrollIndicators(.visible)` which
 * Compose does not provide out of the box for LazyColumn.
 *
 * The thumb fades in while scrolling / shortly after, similar to the
 * platform scrollbar.
 */
// [forward-stable] True when the bottom sentinel row (the last index) is
// currently measured inside the viewport — the authoritative "user is at the
// very bottom" signal for the follow state machine. The sentinel is a 5dp
// spacer so it is only measurable at the true end of the transcript.
private fun isBottomSentinelVisible(
    layoutInfo: androidx.compose.foundation.lazy.LazyListLayoutInfo,
): Boolean {
    val total = layoutInfo.totalItemsCount
    return total > 0 && layoutInfo.visibleItemsInfo.any { it.index == total - 1 }
}

/**
 * [fix/chat-sentinel-crash-on-import] Resolve the bottom-sentinel scroll index
 * safely. `requestScrollToItem` throws `IllegalArgumentException("Index should
 * be non-negative (-1)")` when handed a negative index; a cold-open
 * InitialOpen request can fire before the LazyColumn has measured anything
 * (layoutInfo.totalItemsCount == 0), so `totalItemsCount - 1` is -1. Returning
 * null means "nothing to scroll yet" — the next committed row revision will
 * raise the real bottom request once rows exist.
 */
internal fun safeBottomScrollIndex(totalItems: Int): Int? =
    if (totalItems > 0) totalItems - 1 else null

private fun Modifier.verticalScrollbar(
    listState: androidx.compose.foundation.lazy.LazyListState,
    width: Dp = 3.dp,
    color: Color = Color(0x55888888),
): Modifier = this.then(Modifier.drawWithContent {
    drawContent()
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo
    if (totalItems == 0 || visibleItems.isEmpty()) return@drawWithContent
    if (visibleItems.size >= totalItems &&
        visibleItems.first().index == 0 &&
        visibleItems.last().index == totalItems - 1 &&
        visibleItems.first().offset >= 0
    ) {
        // Fully visible, no scroll possible — no thumb.
        return@drawWithContent
    }
    val firstIndex = visibleItems.first().index
    val firstOffsetPx = visibleItems.first().offset.toFloat()
    val avgItemSize = visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
    val totalContentPx = avgItemSize * totalItems
    val viewportHeight = this.size.height
    if (totalContentPx <= viewportHeight || avgItemSize <= 0f) return@drawWithContent
    val scrollOffsetPx = firstIndex * avgItemSize - firstOffsetPx
    val thumbHeight = (viewportHeight * (viewportHeight / totalContentPx)).coerceAtLeast(24f)
    val maxScroll = (totalContentPx - viewportHeight).coerceAtLeast(1f)
    val maxTop = (viewportHeight - thumbHeight).coerceAtLeast(0f)
    val thumbTop = (scrollOffsetPx / maxScroll * maxTop).coerceIn(0f, maxTop)
    val widthPx = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(this.size.width - widthPx - 1f, thumbTop),
        size = androidx.compose.ui.geometry.Size(widthPx, thumbHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(widthPx / 2, widthPx / 2),
    )
})

// [T-android-tool-autoscroll] Combined signal for the streaming auto-follow
// LaunchedEffect. data class so distinctUntilChanged uses structural equality
// — any field flip propagates a tick. Per-block (id, kind, status, length)
// folded into [blockSig] (FNV-1a 64-bit hash) so a RUNNING→SUCCESS flip on a
// tool block, a new block appearing (id flips), or a kind change all wake the
// collector even when growth/size/awaiting alone would have stayed equal.
private data class ScrollFollowKey(
    val lastIndex: Int,
    val growth: Long,
    val toolBlockCount: Int,
    val awaiting: Boolean,
    val blockSig: Long,
)

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun ChatScreen(
    sessionId: String,
    /**
     * [P0-0] When non-null, scroll to this message once after the list is
     * populated and briefly highlight it. Null (the default) reproduces the
     * pre-P0-0 behaviour exactly — no extra scroll is issued, so the existing
     * follow/anchor state machine is untouched for ordinary chat opens.
     *
     * Consumed once: after the jump the local target is cleared so returning
     * to this screen (config change, back-from-terminal) does not re-jump and
     * fight the user's own scrolling.
     */
    focusMessageId: String? = null,
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    memoryRepository: MemoryRepository? = null,
    skillRepository: com.openminis.app.data.repository.SkillRepository? = null,
    mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
    onBack: () -> Unit,
    /** [T-new-chat-menu-entry] "New Chat" from the chat "..." menu: caller
     *  navigates to a fresh draft chat (same funnel as the session list's
     *  new-chat button), replacing this chat on the back stack. */
    onNewChat: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    /** Open the in-app terminal with [command] pre-filled at the prompt
     *  (no trailing newline — the user reviews and presses Enter manually).
     *  Wired to the top-right Terminal button on a shell_execute ToolDetailSheet. */
    onOpenTerminalWithCommand: (command: String) -> Unit = {},
    /** "Move to…" capsule (T51): called when the user picks a target session
     *  from MoveToSessionSheet after a share-injected turn. The caller is
     *  responsible for navigating; this screen has already stashed the
     *  pending transfer in [ChatViewModelStore.stashPendingTransfer]. */
    onMoveToSession: (sessionId: String) -> Unit = {},
    onBrowseChatFiles: () -> Unit = {},
    /** T150: open FilePreviewScreen for a non-image attachment in a user bubble. */
    onPreviewAttachment: (com.openminis.app.ui.sandbox.FileItem) -> Unit = {},
    /** [T-android-modelpicker-group-edit] Navigate to the Model Groups
     *  management screen — wired to the "Edit" button on the model picker's
     *  Model Groups section header. */
    onModelGroupsClick: () -> Unit = {},
    /** Open another conversation from the chat-history drawer. The caller
     *  navigates to that chat (same funnel as the session list's onSessionClick). */
    onOpenSession: (String) -> Unit = {},
    /** Open Settings from the chat-history drawer footer. */
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Scoped to a process-level per-session ViewModelStore (ChatViewModelStore)
    // so the ViewModel and its viewModelScope survive:
    //   - configuration changes (rotation — NavBackStackEntry still alive)
    //   - leaving the chat screen via popBackStack (NavBackStackEntry destroyed)
    // The VM is released only when the session is deleted (see SessionListViewModel).
    val viewModel: ChatViewModel = viewModel(
        viewModelStoreOwner = ChatViewModelStore.ownerFor(sessionId),
        factory = ChatViewModel.factory(
            sessionId = sessionId,
            chatRepository = chatRepository,
            providerRepository = providerRepository,
            appContext = context.applicationContext,
            memoryRepository = memoryRepository,
            skillRepository = skillRepository,
            mcpRepository = mcpRepository,
        ),
    )
    // [T-android-larky-longsession-followup] Consume the tail-windowed
    // view instead of the canonical full list. For sessions with ≤300
    // messages this is the SAME reference (zero overhead); for longer
    // sessions (Larky's 600+) it caps at INITIAL_VISIBLE_MESSAGE_CAP and
    // grows in steps when the user reaches the top via [viewModel.loadOlderMessages].
    // Callers needing the full history (compact / fork / regenerate / send)
    // continue to read viewModel.messages directly inside the VM.
    val messages by viewModel.uiMessages.collectAsState()
    val hasOlderMessages by viewModel.hasOlderMessages.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val canResume by viewModel.canResume.collectAsState()
    val error by viewModel.error.collectAsState()
    val modelName by viewModel.modelName.collectAsState()
    val sessionCategory by viewModel.sessionCategory.collectAsState()
    val attachments by viewModel.attachments.collectAsState()
    val availableGroups by viewModel.availableGroups.collectAsState()
    val selectedGroupId by viewModel.selectedGroupId.collectAsState()
    val showBrowserSheet by viewModel.showBrowserSheet.collectAsState()
    val showMemorySheet by viewModel.showMemorySheet.collectAsState()
    val memoryToolRecords by viewModel.memoryToolRecords.collectAsState()
    val selectedGroupName by viewModel.selectedGroupName.collectAsState()
    val providerName by viewModel.providerName.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Hoisted to ChatViewModel so it survives ChatScreen disposal/recomposition
    // across forward navigation (file preview, env vars, etc.); see
    // ChatViewModel.listState for the why.
    val listState = viewModel.listState

    // ─── T51: Share Injection + Move-to capsule ───────────────────────
    // Drain any pending share buffered by ShareCoordinator (cold start =
    // bufferVersion already non-zero on first composition; warm start =
    // version increments while the user is mid-session). Runs on every
    // bufferVersion bump.
    val shareBufferVersion by com.openminis.app.share.ShareCoordinator.bufferVersion.collectAsState()
    androidx.compose.runtime.LaunchedEffect(shareBufferVersion) {
        if (shareBufferVersion == 0) return@LaunchedEffect
        val pending = com.openminis.app.share.ShareCoordinator.consumeBuffer(context)
            ?: return@LaunchedEffect
        com.openminis.app.logging.AppLogger.info(
            "ChatScreen",
            "[Share] injecting ${pending.items.size} item(s) into chat session=$sessionId",
        )
        val sharedDir = com.openminis.app.share.SharedShareStore.sharedFileDirectory(context)
        for (item in pending.items) {
            when (item.kind) {
                com.openminis.app.share.PendingShare.Item.Kind.INLINE_TEXT -> {
                    val sep = if (viewModel.inputText.value.isNotEmpty()) "\n" else ""
                    val needsTrailingSpace = item.value.startsWith("http://") ||
                        item.value.startsWith("https://")
                    val newText = viewModel.inputText.value + sep + item.value +
                        if (needsTrailingSpace) " " else ""
                    // [fix/setinputtext-caret-intent] Explicit caret to the end of
                    // the appended text (caret lands after the just-injected text).
                    viewModel.setInputText(newText, caretOverride = newText.length)
                }
                com.openminis.app.share.PendingShare.Item.Kind.ATTACHMENT -> {
                    viewModel.addAttachmentFromStagedShare(java.io.File(sharedDir, item.value))
                }
            }
        }
        viewModel.markShareInjected()
        com.openminis.app.share.SharedShareStore.cleanSharedFiles(context)
    }

    // T311: publish "this is the active chat" while ChatScreen is composed,
    // so `minis-config session.*` reads/writes target it. Mirrors iOS
    // `AIChatViewModel.activeSessionId` which is updated on appear / disappear.
    // [T-HANG-DIAG] capture the application context so we can read the
    // current hang count from non-composable scopes below. LocalContext is
    // already used elsewhere in this file via `context`, but DisposableEffect
    // is a non-composable scope so we lift the read up here.
    val tHangDiagAppContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    androidx.compose.runtime.DisposableEffect(sessionId) {
        ChatViewModelStore.setActiveSession(sessionId)
        // [T-HANG-DIAG] enter / dispose markers around the ChatScreen lifetime
        // so we can correlate "user tapped session X" → loadSession timings
        // and any subsequent hang record. Removable by grepping out
        // `[T-HANG-DIAG]` from this file.
        println(
            "[T-HANG-DIAG] ChatScreen MOUNT session=$sessionId hangCount=" +
                com.openminis.app.diagnostics.HangDetector.currentHangCount(tHangDiagAppContext),
        )
        com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "chatScreen.mount")
        onDispose {
            println("[T-HANG-DIAG] ChatScreen UNMOUNT session=$sessionId")
            ChatViewModelStore.setActiveSession(null)
            // T-android-new-chat-empty-residue: drop sessions materialised by
            // a settings toggle (ensureSession via /memory, /thinking, etc.)
            // but never sent a real message. VM guards on streaming + DB count
            // so an in-flight agent or non-empty session is left alone.
            // Skip cleanup on configuration changes (e.g. rotation) — the
            // composable is about to re-mount with the same session and its
            // pending attachments would be lost if we released the ViewModel.
            val activity = context as? android.app.Activity
            if (activity?.isChangingConfigurations != true) {
                viewModel.cleanupIfEmptyOnExit()
            }
        }
    }

    // Hang-detector quiet-period reset: if the user lands on a chat session
    // and stays for 10s without the watchdog firing again, the previous
    // hang count was a transient blip and the breaker can release. The call
    // itself is cheap — early-returns when the count is already zero.
    androidx.compose.runtime.LaunchedEffect(sessionId) {
        kotlinx.coroutines.delay(10_000)
        com.openminis.app.diagnostics.HangDetector.markHealthyTick()
    }

    // Drain any pending Move-to transfer when entering this session — the
    // source ChatScreen stashed (inputText + attachments) into the global
    // ChatViewModelStore.pendingTransfer slot before navigating here.
    androidx.compose.runtime.LaunchedEffect(sessionId) {
        val transfer = ChatViewModelStore.consumePendingTransfer() ?: return@LaunchedEffect
        com.openminis.app.logging.AppLogger.info(
            "ChatScreen",
            "[MoveTo] draining transfer into session=$sessionId text=${transfer.inputText.length}ch attachments=${transfer.attachments.size}",
        )
        // Clear any stale unsent attachments on the target session before
        // injecting (mirrors iOS injectPendingTransferIfNeeded).
        viewModel.clearAttachments()
        if (transfer.inputText.isNotEmpty()) {
            val sep = if (viewModel.inputText.value.isNotEmpty()) "\n" else ""
            val appended = viewModel.inputText.value + sep + transfer.inputText
            // [fix/setinputtext-caret-intent] Explicit caret to end of injected
            // transfer text so the composer shows the tail after injection.
            viewModel.setInputText(appended, caretOverride = appended.length)
        }
        for (a in transfer.attachments) viewModel.addAttachment(a)
        viewModel.markShareInjected()
    }

    val inputFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    val coroutineScope = rememberCoroutineScope()

    var showModelPicker by remember { mutableStateOf(false) }
    // [T-android-thinking-badge-navbar] Whether the thinking-level sheet
    // (opened by tapping the navbar thinking badge) is presented. Mirrors iOS
    // AIChatView.showThinkingLevelSheet.
    var showThinkingLevelSheet by remember { mutableStateOf(false) }
    var showChatMenu by remember { mutableStateOf(false) }
    // [T-input-history] "Input History" sheet visibility, mirrors rikkahub's
    // top-bar Chat Options preview. Treated as a pinned, always-visible
    // top-bar action (like New Chat) rather than a "..." menu entry — both
    // are high-frequency actions the user wants immune to the "hide empty
    // menu" collapse.
    var showInputHistorySheet by remember { mutableStateOf(false) }
    // [T-input-history] Focus-jump target and highlight anchor. Hoisted from
    // inside the Scaffold content lambda so the top-bar "input history" button
    // (rendered before the content lambda) can write pendingFocusId and
    // trigger a scroll + highlight without needing a separate channel.
    // Semantics mirror iOS ChatView.focusMessageId nav param.
    var pendingFocusId by remember(sessionId, focusMessageId) {
        mutableStateOf(focusMessageId?.takeIf { it.isNotBlank() })
    }
    var highlightedMessageId by remember(sessionId) { mutableStateOf<String?>(null) }
    var showSkillsSheet by remember { mutableStateOf(false) }
    // [T-mcp-integration-android] MCPs-in-Session sheet visibility.
    var showMcpsSheet by remember { mutableStateOf(false) }
    var showTokenUsageSheet by remember { mutableStateOf(false) }
    // [bottom-toolbar-customizable] Export format picker shared by the "..." menu
    // and the history-drawer footer (replaces the old inline submenu).
    var showExportFormatSheet by remember { mutableStateOf(false) }
    var showClearChatDialog by remember { mutableStateOf(false) }
    // [T-android-enhanced-cache] First-enable confirmation dialog visibility.
    var showEnhancedCacheDialog by remember { mutableStateOf(false) }

    // [T-context-exhausted-dialog] 'Context Full' prompt — surfaced directly
    // from the ViewModel (send-at-capacity stash), so the dialog renders
    // whenever the VM asks, with no local trigger of its own.
    val showContextExhaustedDialog by viewModel.showContextExhaustedDialog.collectAsState()

    // Bridge VM's slash-command "/clear" request into local Compose state so
    // the menu and slash-command entry points share a single confirmation
    // dialog instance. ack the VM flag immediately to avoid re-firing on
    // recomposition.
    val clearChatRequested by viewModel.clearChatConfirmRequested.collectAsState()
    LaunchedEffect(clearChatRequested) {
        if (clearChatRequested) {
            showClearChatDialog = true
            viewModel.ackClearChatConfirmRequest()
        }
    }

    // [bottom-toolbar-customizable] Live resolved order + visibility/pin state
    // for the customizable action pool (top-right "..." menu + history-drawer
    // footer). Re-read on every appearance_prefs change (Chat Menu settings
    // screen, minis-config write, backup restore) so this screen never needs a
    // restart to pick up a new arrangement.
    val chatActions = rememberChatActionState(context)
    // [bottom-toolbar-customizable] Session memory toggle, hoisted to the top of
    // the composable so both the menu gate and the footer availability filter
    // (footerSpecs below) read the same live value.
    val menuMemoryEnabled by viewModel.memoryEnabled.collectAsState()

    // [bottom-toolbar-customizable] Single dispatch point shared by the "..."
    // menu and the history-drawer footer. Each action resolves to exactly one
    // side effect here, so the same feature can never open a different sheet
    // depending on which entry point triggered it (see ExportFormatSheet for
    // why EXPORT in particular needed this).
    fun dispatchChatAction(key: String) {
        when (key) {
            ChatMenuPrefs.TERMINAL -> onOpenTerminal()
            ChatMenuPrefs.BROWSER -> viewModel.toggleBrowserSheet()
            ChatMenuPrefs.CHAT_FILES -> onBrowseChatFiles()
            ChatMenuPrefs.COMPACT -> viewModel.runCompactNow()
            ChatMenuPrefs.THINKING -> viewModel.toggleThinking()
            ChatMenuPrefs.SESSION_SKILLS -> showSkillsSheet = true
            ChatMenuPrefs.SESSION_MCPS -> showMcpsSheet = true
            ChatMenuPrefs.SESSION_MEMORY -> viewModel.toggleMemorySheet()
            ChatMenuPrefs.SLASH_COMMANDS -> {
                if (viewModel.showSlashMenu.value) {
                    viewModel.setInputText(viewModel.dismissSlashMenu(viewModel.inputText.value))
                } else {
                    viewModel.setInputText(viewModel.showSlashMenuOverInput(viewModel.inputText.value))
                }
                runCatching { inputFocusRequester.requestFocus() }
                keyboardController?.show()
            }
            ChatMenuPrefs.EXPORT -> showExportFormatSheet = true
            ChatMenuPrefs.TOKEN_USAGE -> showTokenUsageSheet = true
            ChatMenuPrefs.SETTINGS -> onOpenSettings()
        }
    }

    // "Choose Photos & Videos" — uses the Photo Picker on Android 13+ via the
    // PickMultipleVisualMedia contract; AndroidX falls back to
    // ACTION_OPEN_DOCUMENT on older versions. Mirrors iOS PHPicker
    // (.imagesAndVideos, selectionLimit=50). T129: switched from single to
    // multi-select with a 50-item cap — picks above 50 are truncated and we
    // toast the user so they aren't silently dropped.
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(
            maxItems = ATTACHMENT_PICK_LIMIT,
        ),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val limited = uris.take(ATTACHMENT_PICK_LIMIT)
        for (uri in limited) {
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            val isVideo = mimeType.startsWith("video/")
            val defaultName = if (isVideo) "video.mp4" else "image.jpg"
            val fileName = getFileName(context, uri) ?: defaultName
            viewModel.addAttachment(
                InputAttachment(
                    fileName = fileName,
                    uri = uri,
                    mimeType = mimeType,
                    // Videos are routed as DOCUMENT for now — vision pipeline only
                    // handles images today; videos still upload as raw files so
                    // tools that read them (e.g. ffmpeg) get the bytes.
                    kind = if (isVideo) InputAttachment.Kind.DOCUMENT else InputAttachment.Kind.IMAGE,
                ),
            )
        }
        if (uris.size > ATTACHMENT_PICK_LIMIT) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.chat_attachment_limit, ATTACHMENT_PICK_LIMIT),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // "Take Photo" — Bug 1 in the MIUI feedback report had this silently
    // drop photos because the default TakePicture contract trusts
    // resultCode, and MIUI's camera occasionally returns CANCELED even
    // after writing the file (or OK with the file flushed late). We use
    // StartActivityForResult directly and trust the filesystem instead:
    // if the staging file has nonzero length, we got a photo.
    // [T-android-camera-rotate-lost-photo] MainActivity has no
    // configChanges="orientation", so capturing in one orientation and
    // returning in another RECREATES the Activity. These pending handles must
    // therefore survive the recreate — `remember` is reset on recomposition
    // after recreation, so the ActivityResult callback would see a null uri
    // and silently drop the just-taken photo (gallery picks are unaffected:
    // their result Uri arrives directly in-callback). `rememberSaveable`
    // persists through savedInstanceState: Uri is Parcelable; the staging
    // File is saved as its absolute path string and rebuilt on read.
    var pendingCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingCameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // [T-android-overlay-hide-camera] Release the overlay-suppress
        // gate as soon as we hear back from the camera Activity (success,
        // cancel, or system kill). Without this the floating overlay
        // would stay suppressed indefinitely after a single capture.
        com.openminis.app.service.SessionActivityTracker.setCameraSuppressActive(false)
        val uri = pendingCameraUri
        val file = pendingCameraFilePath?.let { java.io.File(it) }
        pendingCameraUri = null
        pendingCameraFilePath = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        // Don't trust resultCode on MIUI — check the file.
        val ok = file.exists() && file.length() > 0
        if (ok) {
            val fileName = getFileName(context, uri) ?: file.name
            viewModel.addAttachment(
                InputAttachment(
                    fileName = fileName,
                    uri = uri,
                    mimeType = "image/jpeg",
                    kind = InputAttachment.Kind.IMAGE,
                ),
            )
        } else {
            AppLogger.warning(
                "Camera",
                "capture failed: rc=${result.resultCode}, file=${file.name} len=${file.length()}",
            )
            file.delete()
        }
    }
    val launchCamera: () -> Unit = {
        val (uri, file) = createCameraOutputUri(context)
        pendingCameraUri = uri
        pendingCameraFilePath = file.absolutePath
        val intent = android.content.Intent(
            android.provider.MediaStore.ACTION_IMAGE_CAPTURE,
        ).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // [T-android-overlay-hide-camera] Suppress the floating bg-overlay
        // BEFORE handing off to the system camera. The camera Activity
        // takes foreground, which by #451's rule would otherwise satisfy
        // "Minis backgrounded → show overlay" and the capsule would draw
        // on top of the viewfinder. Cleared in the ActivityResult callback.
        com.openminis.app.service.SessionActivityTracker.setCameraSuppressActive(true)
        runCatching { cameraLauncher.launch(intent) }
            .onFailure {
                AppLogger.warning("Camera", "launch failed: ${it.message}")
                // Launch never reached the camera Activity — release the
                // suppress flag here since the result callback won't fire.
                com.openminis.app.service.SessionActivityTracker.setCameraSuppressActive(false)
                pendingCameraUri = null
                pendingCameraFilePath = null
                file.delete()
            }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
    }

    // App-icon quick action: when the user launched via
    // `minis://action/camera_chat`, auto-open the camera on first compose.
    // Consumed exactly once so re-entering the chat later does NOT re-trigger.
    // (The old voice quick-action variant was removed with the mic button.)
    LaunchedEffect(sessionId) {
        val pending = com.openminis.app.deeplink.DeepLinkCoordinator
            .pendingChatAction.value
        if (pending == com.openminis.app.deeplink.DeepLinkCoordinator
                .ChatAction.OPEN_CAMERA
        ) {
            com.openminis.app.deeplink.DeepLinkCoordinator
                .consumePendingChatAction()
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) launchCamera()
            else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    // File picker launcher — T129: multi-select via OpenMultipleDocuments
    // (GetContent has no multi-select equivalent). The launch arg is now a
    // mime-type array; "*/*" stays as the wildcard. Selections above
    // ATTACHMENT_PICK_LIMIT are truncated with a toast so silent drops can't
    // happen. OpenMultipleDocuments returns persistable URIs by default
    // (good — survives process death better than the GetContent stream).
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val limited = uris.take(ATTACHMENT_PICK_LIMIT)
        for (uri in limited) {
            val fileName = getFileName(context, uri) ?: "file"
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val kind = if (mimeType.startsWith("image/")) InputAttachment.Kind.IMAGE else InputAttachment.Kind.DOCUMENT
            viewModel.addAttachment(
                InputAttachment(fileName = fileName, uri = uri, mimeType = mimeType, kind = kind)
            )
        }
        if (uris.size > ATTACHMENT_PICK_LIMIT) {
            android.widget.Toast.makeText(
                context,
                "Only the first $ATTACHMENT_PICK_LIMIT files were attached.",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // Android system permission launcher for agent tools (e.g. location).
    //
    // The launcher's `results` map can't be trusted alone: on several Android
    // versions `RequestMultiplePermissions` returns an empty map (or `false`
    // entries) for permissions that were already granted and thus didn't need
    // a dialog. Re-query the live permission state via checkSelfPermission to
    // decide success — this is what actually matters to the caller.
    val currentPermissionsRef = remember { mutableStateOf<Array<String>>(emptyArray()) }
    val androidPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val perms = currentPermissionsRef.value
        val grantedNow = perms.isNotEmpty() && perms.any { p ->
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        }
        OffloadPermissionManager.respondToAndroidPermission(grantedNow)
    }
    val pendingAndroidPermission by OffloadPermissionManager.pendingAndroidPermission.collectAsState()
    LaunchedEffect(pendingAndroidPermission) {
        val req = pendingAndroidPermission ?: return@LaunchedEffect
        val perms = req.permissions.toTypedArray()
        // Short-circuit when everything's already granted — some OEM builds
        // launch a no-op dialog that still flashes on screen otherwise.
        val alreadyGranted = perms.isNotEmpty() && perms.any { p ->
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        }
        if (alreadyGranted) {
            OffloadPermissionManager.respondToAndroidPermission(true)
            return@LaunchedEffect
        }
        // [P3-all-any-unify] Single source of truth for runtime permissions now
        // lives here (MainActivity's bridge was removed). Before launching,
        // detect permanently-denied permissions (dialog would no-op): report
        // DENIED so the handler can fall back to the in-app settings gate.
        val permanentlyDenied = req.permissions.any { permission ->
            when {
                ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED -> false
                // shouldShowRequestPermissionRationale is an Activity method; the
                // Compose LocalContext is the Activity in this single-activity app.
                OffloadPermissionManager.hasAskedForPermission(context, permission) &&
                    (context as? android.app.Activity)
                        ?.shouldShowRequestPermissionRationale(permission) == false -> true
                else -> false
            }
        }
        if (permanentlyDenied) {
            OffloadPermissionManager.respondToAndroidPermission(false)
            return@LaunchedEffect
        }
        for (p in req.permissions) {
            OffloadPermissionManager.markPermissionAsked(context, p)
        }
        currentPermissionsRef.value = perms
        androidPermissionLauncher.launch(perms)
    }

    val tagScroll = "ChatScrollFollow"
    // Scroll wrappers used by every code path that mutates the LazyColumn
    // position. Kept as named lambdas so re-enabling per-call telemetry
    // (during a scroll-positioning regression) is a one-line edit here
    // instead of changing 20+ call sites. Currently silent.
    val tracedScrollToItem: suspend (source: String, idx: Int, off: Int) -> Unit = { source, idx, off ->
        // [T-android-top-drag-jump] TEMP: log every programmatic scroll's source
        // so we can see which one fights the user near the top. Remove after fix.
        AppLogger.debug(
            "ScrollSrc",
            "scrollToItem src=$source idx=$idx off=$off canBwd=${listState.canScrollBackward} firstIdx=${listState.firstVisibleItemIndex} firstOff=${listState.firstVisibleItemScrollOffset} inProgress=${listState.isScrollInProgress}",
        )
        // [Txxx-android-anchor-settle-debounce] Short-circuit a scrollToItem that
        // is already AT its target (same index AND same offset). Multiple
        // auto-follow sources (trailing-row, stream-end, settle-after-interaction,
        // LAYOUT-DRIFT-SNAP) fire back-to-back during an agent turn, and several
        // of them aim at the already-settled bottom (idx=0 off=0) — a scrollToItem
        // to the current position is a no-op that still goes through the gesture
        // pipeline and, in a LazyColumn already being jittered by content
        // insertion, can land as a visible snap. Folding these no-ops out removes
        // the redundant re-pins (log shows them firing every drag-end while
        // streaming) without changing the intent of any source: each still scrolls
        // exactly when it has to, and a source that truly needs to re-anchor after
        // a content-size change will observe a changed firstOff and pass through.
        val alreadyThere = listState.firstVisibleItemIndex == idx &&
            listState.firstVisibleItemScrollOffset == off
        if (!alreadyThere) runCatching { listState.scrollToItem(idx, off) }
        Unit
    }
    val tracedScrollBy: suspend (source: String, delta: Float) -> Unit = { source, delta ->
        AppLogger.debug(
            "ScrollSrc",
            "scrollBy src=$source delta=$delta canBwd=${listState.canScrollBackward} firstIdx=${listState.firstVisibleItemIndex} firstOff=${listState.firstVisibleItemScrollOffset}",
        )
        runCatching { listState.scrollBy(delta) }
        Unit
    }
    // T-android-jank-profile: gate verbose scroll telemetry behind a constant
    // so every snapshotFlow / derivedStateOf body in this file can cheaply
    // skip the AppLogger.debug call (which builds a long format string and
    // writes a daily log file). Flip locally when debugging scroll behavior.
    val verboseScrollLogs = false

    // ─── T120: scroll-follow rewrite (supersedes T66 / T92 / T99 / T100 / T101 / T112) ───
    //
    // Five iterations of "fight the LazyColumn" (anchor lock, fling-settle
    // gate, isStreaming/lastToolCount/lastAwaiting force-follow LEs) never
    // truly stopped the streaming jitter. Survey of production Compose
    // chat clients (google-ai-edge/gallery, GetStream/stream-chat-android-ai,
    // lambiengcode/compose-chatgpt-kotlin-android-chatbot, Taewan-P/gpt_mobile)
    // showed a consistent pattern:
    //
    //   1. Trust reverseLayout's native bottom anchor — do not call
    //      scrollToItem(0) on every streaming token.
    //   2. Auto-scroll only on TERMINAL events (user sends, IME opens,
    //      stream finishes) — never per-token.
    //   3. Treat "user scrolled away" as a derived value of the current
    //      list position, not a stateful flag mutated by a gesture flow.
    //   4. Provide a JumpToBottom FAB as the universal escape hatch
    //      (already present in this file).
    //
    // What was removed
    //   - Anchor-lock LaunchedEffect (T92 / T99 / T112) — Compose's
    //     reverseLayout already keeps a fixed item anchored, the lock
    //     was fighting that.
    //   - userScrolledAway state mutation in two snapshotFlow collectors —
    //     replaced by a single derivedStateOf<Boolean>.
    //   - LE(isStreaming) edge force-follow.
    //   - LE(lastToolCount) force-follow.
    //   - LE(lastAwaiting) force-follow.
    //   - LE(bottomReserve) inside the toolbar block.
    //   - LE(messages.size) for assistant/tool/system rows — only the
    //     user-send branch survives, because sending is the one event
    //     where "follow the new turn" is unambiguously the user's intent.
    //
    // What stayed
    //   - User-action scroll calls at the send button, retry buttons,
    //     and the JumpToBottom FAB — those are direct user intent.
    //   - reverseLayout=true on the LazyColumn — handles "stick to
    //     bottom while user is at bottom" natively.

    // T128: tightened from 90 dp (google-ai-edge/gallery) to 32 dp.
    // 90 dp made the JumpToBottom FAB appear well before the user had
    // really left the bottom — users reported the "Quick to bottom" button
    // appearing too often. 32 dp is roughly half the floating tool-bar height, so the
    // visual definition of "at bottom" lines up with what the user sees.
    val nearBottomThresholdPx = with(LocalDensity.current) { 32.dp.toPx() }
    // T138 phase 2 v3: ground-truth bottom test via layoutInfo. If
    // LazyList currently renders the visual-bottom item (data-index 0
    // under reverseLayout) and its bottom edge sits within `threshold`
    // px of the viewport bottom, the user is visually at the bottom.
    // `firstVisibleItemIndex` is unreliable here: when a single message
    // emission expands into N tool / text flat items, firstVisible
    // drifts by N in one frame (logcat showed jumps of 5+ on a
    // multi-tool turn). Anchor on the rendered set instead.
    val isNearBottom = remember(listState, nearBottomThresholdPx) {
        derivedStateOf {
            // [forward-stable] Forward list: "at bottom" ⇔ the bottom
            // sentinel row is visible, or the last visible row's bottom edge
            // reaches the viewport end. The sentinel is a 5dp spacer one past
            // the last message row, so it is only measurable when the user
            // really is at the bottom — and it cannot be fooled by a tall
            // last item (the T173 failure mode of the old gap test).
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return@derivedStateOf false
            val sentinelIdx = total - 1
            val sentinel = info.visibleItemsInfo.firstOrNull { it.index == sentinelIdx }
            val result = if (sentinel != null) {
                true
            } else {
                val viewportEnd = info.viewportEndOffset
                val last = info.visibleItemsInfo.maxByOrNull { it.index }
                last != null && last.offset + last.size <= viewportEnd
            }
            // T-android-jank-profile: was logging on every scroll frame (this
            // is a derivedStateOf body — it re-runs when any of
            // listState.layoutInfo / firstVisibleItemIndex /
            // firstVisibleItemScrollOffset / canScrollForward / etc. change,
            // i.e. ~60 times/second during a scroll fling). String-building
            // + file write per frame measurably contributed to scroll jank.
            // Gate behind a debug toggle so the log path stays available for
            // future scroll-debugging sessions but doesn't ship by default.
            if (false) {
                val sentinelVis = sentinel != null
                val lastRow = info.visibleItemsInfo.maxByOrNull { it.index }
                AppLogger.debug(
                    tagScroll,
                    "isNearBottom: total=$total sentinelVisible=$sentinelVis lastIdx=${lastRow?.index} lastBottom=${lastRow?.let { it.offset + it.size }} viewportEnd=${info.viewportEndOffset} canScrollForward=${listState.canScrollForward} canScrollBackward=${listState.canScrollBackward} isScrollInProgress=${listState.isScrollInProgress} -> $result",
                )
            }
            result
        }
    }
    // T170: derived "does content actually overflow the viewport?". Mirrors
    // iOS where `maxOffset > 0` naturally hides the FAB on short sessions.
    // Without this, an IME-driven synthetic drag-stop on a short chat could
    // pin the FAB on screen until the keyboard closed.
    val contentOverflows = remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewportSize = info.viewportEndOffset - info.viewportStartOffset
            val canScroll = listState.canScrollForward || listState.canScrollBackward
            val moreItemsThanVisible = info.totalItemsCount > info.visibleItemsInfo.size
            val visibleSum = info.visibleItemsInfo.sumOf { it.size }
            val sumExceedsViewport = visibleSum > viewportSize
            val result = canScroll || moreItemsThanVisible || sumExceedsViewport
            // T-android-jank-profile: gate per-frame derivedStateOf logs.
            if (false) {
                AppLogger.debug(
                    tagScroll,
                    "contentOverflows: canScroll=$canScroll moreItems=$moreItemsThanVisible sumExceeds=$sumExceedsViewport visibleSum=$visibleSum viewport=$viewportSize total=${info.totalItemsCount} visible=${info.visibleItemsInfo.size} → $result",
                )
            }
            result
        }
    }

    // [T-android-scroll-to-first-message] One-screen distance checks driving the
    // floating scroll buttons, mirroring iOS (T-ios-scroll-to-first-message):
    //   - down-button: shown whenever scrolled away from the bottom (existing
    //     userScrolledAway gate below).
    //   - up-button (scroll to first/oldest message): shown ONLY in the MIDDLE
    //     region — more than one screen from BOTH ends (isFarFromTop &&
    //     isFarFromBottom) — so it never clutters the at-bottom resting state
    //     and never appears on short chats.
    // The list is reverseLayout=true: index 0 is the NEWEST message (visual
    // bottom), the highest index is the OLDEST (visual top). "Far from bottom" =
    // scrolled up away from index 0 by > 1 viewport; "far from top" = the oldest
    // item is still > 1 viewport below the viewport top.
    // [T-android-scroll-to-first-message] "Far from an end" = that end's edge
    // message has been scrolled entirely off-screen by MORE THAN one viewport.
    // Anchoring on the edge item's actual pixel position (not "is it fully at
    // the bottom") avoids the bug where the up-button appeared less than a
    // screen from the bottom: it now requires the newest message to be a full
    // viewport above the viewport bottom before the middle region begins.
    // [T-android-scroll-to-first-message] Distance from each end, measured in
    // viewports. Item heights vary wildly (a one-line bubble vs a screen-tall
    // code block), so anchoring on a single edge item's pixel position breaks
    // once it scrolls off (the earlier "index 0 not fully at bottom" check made
    // the up-button appear less than a screen from the bottom). Instead estimate
    // the scrolled distance: (whole items between the viewport and the end) ×
    // average visible item height + the partial offset of the boundary item.
    // The up-button shows only when BOTH ends are > one viewport away.
    // [T-android-scroll-to-first-message] Compose's LazyListLayoutInfo exposes
    // sizes of CURRENTLY visible items only — no contentSize / contentOffset
    // equivalent to iOS's UIScrollView. Earlier estimations (off-screen item
    // count × avg/min visible-item size) misfired badly because one assistant
    // message expands into many FlatChatItems (header, several markdown
    // blocks, tool blocks, typing indicator); the index count balloons out
    // of proportion to actual pixel distance, so the up-button kept popping
    // up right above the input bar when only a tool-block + header lay
    // off-screen.
    //
    // Instead we OBSERVE: every time an item enters the viewport, cache its
    // (index → size). As the user scrolls we accumulate ground truth for
    // every index we've ever seen. Distance to either end then = sum of
    // cached sizes for the off-screen indices we know about, plus the
    // visible items' real partial overhang. Indices we've never seen still
    // contribute zero — that's a strict lower bound, so we can only
    // under-show the button, never flash it near an end.
    val itemSizeByIndex = remember(listState) { mutableStateMapOf<Int, Int>() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
            .collect { vis ->
                for (it in vis) {
                    val cached = itemSizeByIndex[it.index]
                    if (cached == null || cached != it.size) itemSizeByIndex[it.index] = it.size
                }
            }
    }
    // [T-android-scroll-fab-first-entry] Average observed item size, used to
    // estimate the height of indices we've never had on-screen. The pure
    // cache-only approach (b58e9515) was one-sided: belowSum (already-scrolled-
    // past items, cached) worked, but aboveSum summed indices we hadn't reached
    // yet, which are NEVER cached at the moment they're off-screen ABOVE — so
    // aboveSum stayed 0 forever and the up-button never appeared (logged:
    // aboveSum=0 across an entire top-scroll, even with all 68 items eventually
    // cached). Estimating unknown indices by the running average makes BOTH
    // ends symmetric and direction-independent. The average is a real measured
    // mean (not a wild min/avg-of-visible extrapolation that the commit comment
    // warned against), so it tracks actual pixel distance closely enough for a
    // one-viewport threshold.
    val avgItemSize = remember(listState, itemSizeByIndex) {
        derivedStateOf {
            val sizes = itemSizeByIndex.values
            if (sizes.isEmpty()) 0 else sizes.sum() / sizes.size
        }
    }
    fun sizeAt(index: Int): Int = itemSizeByIndex[index] ?: avgItemSize.value
    val isFarFromBottom = remember(listState, itemSizeByIndex, avgItemSize) {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewportH = info.viewportEndOffset - info.viewportStartOffset
            val visible = info.visibleItemsInfo
            if (viewportH <= 0 || visible.isEmpty()) return@derivedStateOf false
            // [forward-stable] At the very bottom the list cannot scroll
            // forward; never report "far from bottom" there. (canScrollForward
            // already flips with the layout direction.)
            if (!listState.canScrollForward) return@derivedStateOf false
            // Forward list: the highest visible index is the visual bottom.
            val nearestBottom = visible.maxByOrNull { it.index }!!
            // Sum of (cached, else avg-estimated) sizes for the WHOLE items
            // below the viewport: (nearestBottom.index+1)..total-1. No
            // partial-overhang term — a tall bottom item would make it
            // spuriously large (same rationale as the old reverseLayout code).
            val total = info.totalItemsCount
            var belowSum = 0L
            for (i in (nearestBottom.index + 1) until total) {
                belowSum += sizeAt(i)
            }
            belowSum > viewportH
        }
    }
    val isFarFromTop = remember(listState, itemSizeByIndex, avgItemSize) {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val viewportH = info.viewportEndOffset - info.viewportStartOffset
            val visible = info.visibleItemsInfo
            if (total == 0 || viewportH <= 0 || visible.isEmpty()) return@derivedStateOf false
            // Symmetric to isFarFromBottom: at the top the list cannot scroll
            // backward; never report "far from top" there.
            if (!listState.canScrollBackward) return@derivedStateOf false
            // Forward list: the lowest visible index is the visual top.
            val nearestTop = visible.minByOrNull { it.index }!!
            // Whole items above the viewport (older): 0..nearestTop.index-1.
            // Unmeasured on first entry (scrolled up from the bottom), so
            // estimate via the running average. No partial-overhang term,
            // mirroring above.
            var aboveSum = 0L
            for (i in 0 until nearestTop.index) {
                aboveSum += sizeAt(i)
            }
            aboveSum > viewportH
        }
    }

    // ─── stickToBottom state machine (explicit-trigger semantics) ───
    //
    // Replaces the passive position gates (isNearBottom / firstVisibleItemIndex)
    // as the SINGLE source of truth for auto-follow. Follow is ENGAGED by an
    // explicit intent only — the down-arrow FAB (jump to bottom), send while
    // anchored, resume, and auto-push while already sticky. A gesture-based
    // "hit the bottom edge" NestedScroll trigger was tried and removed because
    // it proved unreliable on device; the down-arrow FAB is the deterministic
    // replacement. Content insertion can never flip this flag, which is what
    // the three patch rounds (884d9f1 → 58fe086 → 1955485) were fighting.
    //
    // Model:  down-arrow / return-to-bottom → stickToBottom=true (follow);
    //         scroll-away-from-bottom → stickToBottom=false (stop moving);
    //         down-arrow again → resume follow.
    // [forward-stable] Follow state machine — pure reducer
    // (ChatFollowController). FOLLOWING: data revisions + explicit intents
    // raise exactly ONE pending bottom request, consumed by the effect
    // below. DETACHED: tokens/tools/stream end/auto-retry/IME never scroll.
    // Replaces the reverseLayout-era stickToBottom flag + anchor-guard.
    var followState by remember(sessionId) { mutableStateOf(FollowState()) }
    // [fix/scroll-follow-simplify] `prevRowKeys` prefix telemetry removed with
    // the flatten-collector follow dispatch (aggregate early-return + SIMPLE_FOLLOW).

    // T-android-use-dragging-guard: does a real pointer drag currently own the
    // list? The bottom-scroll consumer reads this so it never fires a scroll
    // while the user's finger is mid-gesture (racing the drag would cause
    // visible jitter and yank the reader). Tracked by the DragInteraction
    // collector below. Declared BEFORE the consumer effect so it is in scope.
    var isUserDragging by remember { mutableStateOf(false) }
    // [bottom-fix] Timestamp of the last drag-stop. Kept for the follow
    // disengage decision below (which still reads the raw list position).
    var lastDragStopMs by remember { mutableStateOf(0L) }

    // [forward-stable] Session open: one initial bottom request, unless the
    // open targets a specific message (focus-message owns the scroll then).
    LaunchedEffect(sessionId) {
        if (pendingFocusId == null) {
            followState = followReducer(followState, FollowEvent.InitialOpen)
        }
    }

    // [forward-stable] Single bottom-request consumer. FOLLOWING + data-ready
    // + no in-flight gesture + sentinel not yet visible → scroll to the
    // bottom sentinel (the last item). Exactly ONE consumed request per event;
    // the effect never re-fires on layout change.
    //
    // [fix/history-open-at-bottom-04] Structural fix — the drift that broke
    // rounds 1–3 came from scrolling a stale/half-released layout AND re-firing
    // on every layout change, not from "index vs key":
    //  - `listState.layoutInfo` is REMOVED from the key set. That key was the
    //    "scroll → layout changes → re-scroll → jump away" feedback loop: every
    //    item-height release re-ran the effect and re-issued the scroll with a
    //    stale count, yanking a reading user. The third round's poll ("wait N
    //    frames for stability") was a band-aid over this same key and
    //    introduced the "scrolled up → suddenly jumped to a far earlier
    //    message" regression.
    //  - INITIAL_OPEN is no longer consumed HERE: the async flatten chain
    //    (sessionLoaded flips before flatItems is non-empty) means this effect
    //    would see an empty list and consume the opening scroll — the round-4
    //    "open then jump away" bug. That scroll is owned by the flatten
    //    collector (see shouldScrollToBottomOnFirstRows) at the exact point
    //    the real rows first appear. This effect only handles the remaining
    //    explicit intents (Send / Resume / Retry / FabDown / StreamRowsChanged),
    //    whose data is already in flight.
    //  - The scroll itself is exactly-once and skipped outright while the user
    //    is dragging, so a reading user is never yanked.
    LaunchedEffect(followState.pendingBottomRequest, followState.rowRevision) {
        val reason = followState.pendingBottomRequest ?: return@LaunchedEffect

        // The INITIAL_OPEN scroll is owned by the flatten collector. Here we
        // must NOT consume it either — the collector consumes it once it has
        // fired. (Consuming here would strand the request on a reload where
        // the collector already passed the first-publish point.)
        if (reason == BottomRequestReason.INITIAL_OPEN) return@LaunchedEffect

        // Delegate the decision to the pure gate (ChatFollowController) so the
        // exact contract is JVM-tested; here we only map its verdict to
        // Compose side effects.
        val sentinelVisible = isBottomSentinelVisible(listState.layoutInfo)
        val hasRows = listState.layoutInfo.totalItemsCount > 0
        when (
            decideBottomScroll(
                sentinelVisible = sentinelVisible,
                hasRows = hasRows,
                isFollowing = followState.isFollowing,
                isScrollInProgress = listState.isScrollInProgress,
                isUserDragging = isUserDragging,
                focusTarget = pendingFocusId != null,
            )
        ) {
            BottomScrollAction.SCROLL_TO_BOTTOM -> {
                // Scroll to the bottom sentinel (always the LAST item). The
                // suspend overload snapToItemIndexInternal(forceRemeasure =
                // true) re-measures against the LIVE item provider, so the
                // index clamp in LazyListMeasure lands on the true last item.
                AppLogger.debug("ScrollSrc", "scroll-bottom reason=$reason revision=${followState.rowRevision}")
                listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
            }

            BottomScrollAction.SKIP_AND_CONSUME -> {
                AppLogger.debug("ScrollSrc", "request-bottom skipped (focus/draft/drag/not-following) reason=$reason revision=${followState.rowRevision}")
            }
        }

        // Exactly-once: consume the request after this single run. No retained
        // INITIAL_OPEN, no layout-re-fire. This is what eliminates the
        // "already at bottom then jumps away again" loop and the third-round
        // "scrolled up then jumped to an earlier message" regression.
        followState = consumeBottomRequest(followState)
    }

    // [forward-stable] Anchor-guard REMOVED — the reverseLayout compensation
    // loop is gone with the forward list. Content grows at the tail; the
    // viewport anchor (first visible row key) never moves; there is no
    // (index0 → index1 → index0) transient to fight, so no drift watcher, no
    // deadzone, no 100ms throttle, no forced re-pin. Follow decisions live in
    // the drag-stop handler + explicit intents below (FollowController in
    // Commit D turns these into a single request protocol).
    //
    // [T-android-composer-input-blocked-while-streaming] True only while the
    // user's FINGER is actively dragging the message list. Programmatic scrolls
    // (the streaming auto-follow glide, settle, pin-to-bottom) go through
    // `listState.scroll { }` / `scrollToItem`, which set
    // `listState.isScrollInProgress = true` but emit NO DragInteraction. So a
    // gesture-only signal lets us distinguish "user scrolled the transcript"
    // (should dismiss the keyboard) from "streaming auto-followed" (must NOT
    // touch focus). Without this the keyboard closed itself mid-stream and
    // dropped the in-flight keystroke (the reported "can't type while
    // streaming" bug).
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            // T-android-jank-profile: drag interactions fire on every drag
            // event during a scroll (Press / Cancel / Stop). String-building
            // logs here added measurable load. Gate behind a constant.
            when (interaction) {
                is androidx.compose.foundation.interaction.DragInteraction.Start -> {
                    isUserDragging = true
                    // [forward-stable] A real pointer drag begins — drop any
                    // in-flight bottom request so nothing scrolls mid-gesture.
                    // This also maintains DETACHED/FOLLOWING for the explicit
                    // intent consumer above.
                    followState = followReducer(followState, FollowEvent.UserDragStart)
                }
                is androidx.compose.foundation.interaction.DragInteraction.Stop -> {
                    isUserDragging = false
                    lastDragStopMs = SystemClock.elapsedRealtime()
                    // [bottom-fix] Drive the disengage decision off the raw
                    // list position, NOT the lazy isNearBottom derived-state.
                    // isNearBottom caches and can lag one snapshot behind a
                    // drag-stop, so reading its `.value` here can return the
                    // pre-drag "bottom" (index 0) long after the user has
                    // actually scrolled up — leaving stickToBottom=true and
                    // letting the anchor-guard yank the reader (the observed
                    // "output-end jump"). Reading the raw index/offset is
                    // authoritative and settles in the same snapshot.
                    val stoppedIdx = listState.firstVisibleItemIndex
                    val stoppedTotal = listState.layoutInfo.totalItemsCount
                    // [forward-stable] Forward list: at bottom ⇔ the viewport
                    // reaches the last rows (sentinel index = total-1) with a
                    // small scroll offset. Raw index/offset are authoritative
                    // here — the cached isNearBottom can lag a snapshot.
                    val stoppedAtBottom = stoppedTotal > 0 &&
                        stoppedIdx >= stoppedTotal - 2 &&
                        listState.firstVisibleItemScrollOffset <= nearBottomThresholdPx.toInt()
                    // [forward-stable] Drag end flips the mode: sentinel in
                    // view → follow; scrolled away → detach (nothing may yank).
                    // Maintains the mode for the explicit-intent consumer above.
                    followState = followReducer(followState, FollowEvent.UserDragEnd(atBottom = stoppedAtBottom))
                }
                is androidx.compose.foundation.interaction.DragInteraction.Cancel -> {
                    isUserDragging = false
                    lastDragStopMs = SystemClock.elapsedRealtime()
                }
                else -> Unit
            }
        }
    }
    // T169 / T170: an IME show/hide animates the LazyColumn's content area,
    // which can briefly register as a synthetic drag-stop and flip
    // userScrolledAway=true even though the user never actually scrolled.
    //
    // T170: only force-reset when we're actually back at the bottom. Earlier
    // behaviour of unconditionally clearing userScrolledAway hid the FAB on
    // users who had scrolled up to read history and then opened the keyboard
    // to send a follow-up — auto-follow then yanked them away from where
    // they were reading.
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottomPx) {
        // [forward-stable] The IME reshaped the viewport while the user was
        // actually at the bottom — keep the follow state in sync (drag-stop
        // position verdicts cover the animation window).
        if (!followState.isFollowing && isNearBottom.value) {
            followState = followReducer(followState, FollowEvent.UserDragEnd(atBottom = true))
        }
    }
    // [T-android-tool-autoscroll] Start-of-turn edge from ViewModel: resume() /
    // retryLast() / retryFromMessage() / rerunFromToolBlock() emit Unit on
    // forceScrollToBottom because they don't append a new user-message row. This
    // collector is the explicit "show the continuation" signal for those turns;
    // the anchor-guard handles the passive re-pin once the stream starts rolling.
    LaunchedEffect(listState, viewModel) {
        viewModel.forceScrollToBottom.collect {
            // [fix/force-scroll-respect-viewport] resume/retry/rerun fire this
            // BOTH on an explicit user gesture AND when the agent loop re-runs
            // a tool block / retries a message on its own mid-multi-turn. Only
            // honour it when the user is still following — a reader in
            // history should not be yanked back on every retry. [forward-stable]
            // Raise a data-revision request instead of scrolling directly; the
            // consumer effect gates on sentinel + gesture. DETACHED is never
            // yanked by an automatic re-run.
            if (followState.isFollowing) {
                followState = followReducer(followState, FollowEvent.StreamRowsChanged)
            }
        }
    }

    // [fix/scroll-follow-simplify] RikkaHub-style simple explicit follow.
    // Item granularity is message-level under AGGREGATE_MESSAGE_ITEMS, so the
    // fragment-churn the old guard stack fought is gone. Streaming auto-follow
    // = the exact rikkahub ChatList contract: `isAtBottom() && streaming` →
    // requestScrollToItem at the bottom sentinel, driven directly off the
    // rendered viewport (no state machine, no defensive guard).
    //   - isStreaming: the user is getting something — follow the growing tail.
    //   - listState.isScrollInProgress: never fight a live gesture / fling.
    //   - isAtBottom (isBottomSentinelVisible): only nudge when the sentinel
    //     is already on screen — a history reader is never yanked.
    // When the sentinel is in view, forward-layout anchoring already holds the
    // bottom and requestScrollToItem(sentinel) is a harmless no-op scrolling to
    // the current position, so the effect simply keeps a bottom-anchored viewer
    // pinned as the tail grows. Explicit user intents (Send / FabDown /
    // Resume / Retry / InitialOpen) keep flowing through the follow reducer +
    // consumer above, which still scroll when the sentinel has scrolled out.
    // The old data-collector StreamRowsChanged dispatch (which neither ran
    // under AGGREGATE_MESSAGE_ITEMS nor is needed with this effect) was removed.
    if (SIMPLE_FOLLOW) {
        val bottomScrollTarget: (Int) -> Int? = { total -> safeBottomScrollIndex(total) }
        LaunchedEffect(listState, isStreaming) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo }
                .collect { vis ->
                    if (listState.isScrollInProgress) return@collect
                    if (!isStreaming) return@collect
                    val total = listState.layoutInfo.totalItemsCount
                    if (total == 0) return@collect
                    val atBottom = isBottomSentinelVisible(listState.layoutInfo)
                    if (!atBottom) return@collect
                    // [fix/place-storm-follow-clamp-loop] Clamp guard: the
                    // sentinel being visible + content flush against the
                    // bottom (canScrollForward == false) means any
                    // requestScrollToItem is UNREACHABLE — the list clamps it
                    // right back, visibleItemsInfo re-emits, and the loop
                    // re-requests: a self-sustaining 60Hz measure storm that
                    // lasted the whole streaming turn (PlaceStorm dumps,
                    // minis-2026-09-01__2_.log). Only nudge when the clamp
                    // was actually released (new content grew the list).
                    if (!shouldRequestFollowScroll(listState.canScrollForward)) return@collect
                    val scrollIdx = bottomScrollTarget(total)
                    if (scrollIdx != null) {
                        listState.requestScrollToItem(scrollIdx)
                    }
                }
        }
    }

    // [T-android-no-auto-focus] Entering the app / opening a new chat no
    // longer auto-focuses the composer, so the keyboard stays hidden until
    // the user actually taps the input field (RikkaHub-style: no implicit
    // focus requests anywhere). The old block below requested focus 300 ms
    // after mounting any "__new__" draft session, which popped the keyboard
    // on every cold start.
    //
    // T176 note (kept for history): theme switch (Activity recreate) used to
    // re-enter that LaunchedEffect before the composer's focusRequester was
    // attached, so requestFocus() threw `FocusRequester is not initialized`.
    // With auto-focus removed the crash path is gone too.

    // Show top-level error in snackbar (only for errors without an assistant message)
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // T-imgsize: surface composer-side image-budget actions (compress / drop)
    // via Snackbar. Each event is one user send; we emit at most two short
    // notices (compressed count + dropped count) so the user understands
    // why we touched their attachments before the provider would 413.
    LaunchedEffect(Unit) {
        viewModel.imageBudgetEvent.collect { ev ->
            if (ev.compressedCount > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.image_budget_compressed, ev.compressedCount),
                )
            }
            if (ev.droppedCount > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.image_budget_total_exceeded),
                )
            }
        }
    }

    // [T-error-no-permanent-scars] Show a transient Snackbar when a model-group
    // fallback switches models mid-turn. The event is emitted as a one-shot
    // SharedFlow, so the Snackbar auto-dismisses after a few seconds and leaves
    // no permanent trace in the chat record.
    LaunchedEffect(Unit) {
        viewModel.fallbackToastEvent.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    // T-request-imgsize: surface request-level image-budget elisions
    // (older images compacted into text placeholders to fit the 25MB
    // request cap). Independent flow from the composer-side budget so
    // both can fire on the same turn without racing.
    LaunchedEffect(Unit) {
        viewModel.requestBudgetEvent.collect { plan ->
            if (plan.droppedCount > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(
                        R.string.image_budget_request_elided,
                        plan.droppedCount,
                    ),
                )
            }
        }
    }

    val appearancePrefs = remember { com.openminis.app.ui.settings.getAppearancePrefs(context) }
    var messageFontLevel by remember { mutableStateOf(appearancePrefs.getInt(com.openminis.app.ui.settings.KEY_FONT_MESSAGE, 0)) }
    var chatInputLevel by remember { mutableStateOf(appearancePrefs.getInt(com.openminis.app.ui.settings.KEY_FONT_CHAT_INPUT, 0)) }
    var toolPreviewEnabled by remember { mutableStateOf(appearancePrefs.getBoolean(com.openminis.app.ui.settings.KEY_TOOL_PREVIEW, false)) }
    // Whole FloatingToolStatusBar visibility — when OFF the entire bar is
    // hidden (text + spinner + thumbnail) and no height is reserved. Live
    // toggles via Settings → Appearance and minis-config.
    var toolStatusBarEnabled by remember { mutableStateOf(appearancePrefs.getBoolean(com.openminis.app.ui.settings.KEY_TOOL_STATUS_BAR, true)) }
    // T-chat-title-pill: live-toggled by Settings → Appearance and by
    // `minis-config set appearance.show_chat_title …`. Default ON.
    var showChatTitlePill by remember { mutableStateOf(appearancePrefs.getBoolean(com.openminis.app.ui.settings.KEY_SHOW_CHAT_TITLE, true)) }
    // T-chat-title-pill-edit: state for the in-chat edit-title sheet (the
    // exact same SessionEditSheet hosted by the session list home screen,
    // reused via `internal` visibility — no duplicate UI). Populated by an
    // async repo lookup once the user taps the title pill.
    var editingSession by remember { mutableStateOf<com.openminis.app.data.db.ChatSessionEntity?>(null) }
    DisposableEffect(appearancePrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            when (key) {
                com.openminis.app.ui.settings.KEY_FONT_MESSAGE -> messageFontLevel = sp.getInt(key, 0)
                com.openminis.app.ui.settings.KEY_FONT_CHAT_INPUT -> chatInputLevel = sp.getInt(key, 0)
                com.openminis.app.ui.settings.KEY_TOOL_PREVIEW -> toolPreviewEnabled = sp.getBoolean(key, false)
                com.openminis.app.ui.settings.KEY_TOOL_STATUS_BAR -> toolStatusBarEnabled = sp.getBoolean(key, true)
                com.openminis.app.ui.settings.KEY_SHOW_CHAT_TITLE -> showChatTitlePill = sp.getBoolean(key, true)
            }
        }
        appearancePrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { appearancePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val markdownFontScale = com.openminis.app.ui.settings.fontScaleForLevel(messageFontLevel)
    val chatInputFontScale = com.openminis.app.ui.settings.fontScaleForLevel(chatInputLevel)

    var previewUrl by remember { mutableStateOf<String?>(null) }
    // T146: dedicated state for the immersive HTML preview path. Holding
    // both a `holder` and `fullscreen` flag (rather than two separate
    // states) ensures the same WebView survives the sheet→fullscreen
    // toggle without reloading the page (iOS parity, WebPreviewSheet.swift).
    var htmlPreviewHolder by remember {
        mutableStateOf<com.openminis.app.ui.preview.WebViewHolder?>(null)
    }
    var htmlPreviewFallbackTitle by remember { mutableStateOf("") }
    var htmlPreviewFullscreen by remember { mutableStateOf(false) }
    val appCtx = context.applicationContext
    val openHtmlPreview = remember<(java.io.File, String) -> Unit>(appCtx) {
        { file, title ->
            // Reuse the same WebViewHolder as long as the file path doesn't
            // change. Tapping the same html link twice should pick up wherever
            // the user left off rather than reloading from scratch.
            val url = "file://${file.absolutePath}"
            val existing = htmlPreviewHolder
            if (existing == null || existing.currentUrl != url) {
                existing?.destroy()
                htmlPreviewHolder = com.openminis.app.ui.preview.WebViewHolder(appCtx, url)
            }
            htmlPreviewFallbackTitle = title
            htmlPreviewFullscreen = false
        }
    }
    // Pinned-shortcut deep link: minis://session/<id>/<resource-path>
    // consumes here on first composition iff this screen is showing the
    // matching session; opens fullscreen HTML preview backed by a fresh
    // holder. Pending state is left untouched when a different chat is on
    // screen so the right ChatScreen instance still consumes it later.
    LaunchedEffect(sessionId) {
        val pending = com.openminis.app.deeplink.DeepLinkCoordinator
            .pendingHtmlPreview.value ?: return@LaunchedEffect
        if (pending.sessionId != sessionId) return@LaunchedEffect
        com.openminis.app.deeplink.DeepLinkCoordinator.consumePendingHtmlPreview()
        // Resolve through PRootKernel so dot-segments / separators can never
        // escape the `/var/minis` sandbox into an arbitrary host path — the
        // preview WebView serves from the host file, so we must hand it the
        // normalized host File rather than naively concatenating the raw
        // resource path onto `/var/minis`.
        val file = com.openminis.app.sandbox.PRootKernel.resolveHostPath(
            "/var/minis${pending.resourcePath}"
        )
        if (file == null || !file.exists()) {
            com.openminis.app.logging.AppLogger.warning(
                "ChatScreen",
                "pinned HTML preview path missing or unsafe: ${pending.resourcePath}",
            )
            return@LaunchedEffect
        }
        val url = "file://${file.absolutePath}"
        htmlPreviewHolder?.destroy()
        htmlPreviewHolder = com.openminis.app.ui.preview.WebViewHolder(appCtx, url)
        htmlPreviewFallbackTitle = pending.title
        htmlPreviewFullscreen = true
    }
    // T-imgswipe-4f446d83: replace previous single-image preview state with a
    // gallery (list + start index) so callers can pass sibling images (input
    // chip row, message attachments, file-browser dir contents). Single-image
    // taps still work — they pass a 1-item list.
    var previewImageGallery by remember {
        mutableStateOf<Pair<List<com.openminis.app.ui.components.ImageGalleryItem>, Int>?>(null)
    }
    // Video links from chat go through MinisFullscreenVideoPlayer rather than
    // FilePreviewScreen → InlineVideoPlayer. The inline player wraps a bare
    // VideoView with an anchored MediaController and never starts playback,
    // so a tap on an mp4 link rendered as a black surface until the user
    // happened to tap again to surface the controller. The fullscreen player
    // auto-starts on prepared, has a built-in scrubber + play/pause, and an
    // onError listener so failures actually log instead of silently blanking.
    var previewVideoFile by remember { mutableStateOf<java.io.File?>(null) }
    // T-pwa-2: long-press on an HTML attachment chip opens the
    // "Add to Home Screen" sheet for that attachment.
    var webAppSheetTarget by remember { mutableStateOf<InputAttachment?>(null) }
    val urlClickHandler = remember<(String) -> Unit>(viewModel) {
        { url ->
            // Pass the current session id so `minis://attachments/...` resolves
            // against this chat's session directory rather than whichever
            // session booted its PRoot shell most recently (which is what
            // the global bindMounts map would answer).
            when (val action = ChatLinkResolver.resolve(url, viewModel.currentSessionId, context)) {
                is ChatLinkAction.DeepLink -> ChatLinkResolver.dispatchDeepLink(context, url)
                is ChatLinkAction.SandboxFile -> {
                    when {
                        action.item.isImageFile -> {
                            // Single image — caption = filename. Sibling
                            // collection from markdown context is not
                            // plumbed here (iOS does cross-session
                            // assistant images via fingerprint).
                            previewImageGallery = listOf(
                                com.openminis.app.ui.components.ImageGalleryItem(
                                    model = action.item.file,
                                    caption = action.item.name,
                                ),
                            ) to 0
                        }
                        action.item.isVideoFile -> previewVideoFile = action.item.file
                        // T146: HTML files take the immersive web-preview path
                        // (iOS-style 90% bottom sheet + fullscreen toggle)
                        // instead of FilePreviewScreen's plain fullscreen
                        // Scaffold. snake_game.html and similar generated
                        // pages need browser controls to feel right.
                        action.item.isHtmlFile -> openHtmlPreview(action.item.file, action.item.name)
                        // T279: route through the NavHost FILE_PREVIEW destination
                        // (same path as user-bubble attachments and "Browse Chat Files")
                        // so FilePreviewScreen inherits the Activity's edge-to-edge
                        // window setup. The previous in-place Dialog wrapper had
                        // its own Window without enableEdgeToEdge, painting the
                        // platform default scrim on the status / nav bars.
                        else -> onPreviewAttachment(action.item)
                    }
                }
                is ChatLinkAction.ExternalApp ->
                    com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                        .handle(context, action.url)
                is ChatLinkAction.MissingFile ->
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.chat_link_file_missing),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                is ChatLinkAction.Web -> previewUrl = action.url
            }
        }
    }

    // Auto-present the in-app preview when a shell tool's stdout emits an
    // OSC MinisOpenURL marker (via /usr/local/bin/minis-open). The broker is
    // populated by ChatViewModel's shell lineCallback; forwarding the URL
    // into `urlClickHandler` routes it exactly like a chat-link tap —
    // http(s)/about → UrlPreviewSheet, minis:// deep links → DeepLinkHandler,
    // minis://<host>/<path> → in-app file preview by extension.
    val pendingMinisOpenUrl by com.openminis.app.terminal.MinisOpenUrlBroker.pendingUrl
        .collectAsState()
    val minisOpenTerminalVisible by com.openminis.app.terminal.MinisOpenUrlBroker.terminalVisible
        .collectAsState()
    LaunchedEffect(pendingMinisOpenUrl, minisOpenTerminalVisible) {
        val url = pendingMinisOpenUrl ?: return@LaunchedEffect
        // The fullscreen TerminalScreen owns the broker while it's up —
        // let it present its own web preview (mirrors iOS ISHTerminalView)
        // so we don't try to open a sheet on a covered ChatScreen.
        if (minisOpenTerminalVisible) return@LaunchedEffect
        urlClickHandler(url.toString())
        com.openminis.app.terminal.MinisOpenUrlBroker.consume()
    }

    // [T-android-markdown-image-gallery-cross-message] Collect every
    // `![alt](src)` markdown image emitted by any assistant message in the
    // current windowed view, in chronological order, then open the paged
    // ImageGalleryViewer positioned at the tapped image. Mirrors iOS
    // AIChatView.handleMarkdownImageTap (AIChatView.swift:2082). The regex
    // matches the standard inline image form; tool-block content stays
    // untouched (toolBlocks live in a separate AssistantBlock list, not
    // in `content`). Video/audio extensions are filtered out so the gallery
    // only contains still images. Resolution of `minis://` → host File is
    // deferred to the gallery's Coil model — Coil's MinisImageFetcher walks
    // the same session-aware resolver we use for inline rendering.
    val markdownImageTapHandler = remember<(String, String) -> Unit>(messages, sessionId) {
        handler@{ tappedMessageId, tappedUrl ->
            val imageRegex = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)")
            data class Ref(val messageId: String, val source: String, val title: String)
            val refs = mutableListOf<Ref>()
            for (msg in messages) {
                if (msg.role != "assistant") continue
                val content = msg.content
                if (content.isEmpty()) continue
                for (m in imageRegex.findAll(content)) {
                    val alt = m.groupValues.getOrNull(1).orEmpty()
                    val src = m.groupValues.getOrNull(2).orEmpty()
                    if (src.isEmpty()) continue
                    val pathPart = src.substringBefore('?').substringBefore('#')
                    val ext = pathPart.substringAfterLast('.', "").lowercase()
                    // Skip non-image media so the gallery stays still-image only,
                    // matching iOS minisVideoExtensions / minisAudioExtensions.
                    if (ext in setOf("mp4", "mov", "avi", "mkv", "webm",
                                     "mp3", "wav", "aac", "flac", "ogg", "m4a")) continue
                    val title = alt.ifEmpty { pathPart.substringAfterLast('/').ifEmpty { src } }
                    refs.add(Ref(msg.id, src, title))
                }
            }
            if (refs.isEmpty()) {
                // Defensive: tap arrived for a URL that isn't in the visible
                // window (compacted away, just deleted, etc.). Fall back to
                // the single-item URL handler so the user still sees the
                // tapped image rather than swallowing the tap silently.
                urlClickHandler(tappedUrl)
                return@handler
            }
            val startIndex = refs.indexOfFirst { it.messageId == tappedMessageId && it.source == tappedUrl }
                .takeIf { it >= 0 }
                ?: refs.indexOfFirst { it.source == tappedUrl }.takeIf { it >= 0 }
                ?: 0
            val items = refs.map { ref ->
                // Resolve minis://... / file:// / /abs → host File so Coil
                // doesn't have to re-walk PRootKernel for every page swipe.
                // Falls back to the raw URL string when resolution misses —
                // AsyncImage will route it through MinisImageFetcher anyway.
                val resolved = resolveMdMediaFile(context, ref.source, sessionId)
                com.openminis.app.ui.components.ImageGalleryItem(
                    model = resolved ?: ref.source,
                    caption = ref.title,
                )
            }
            previewImageGallery = items to startIndex
        }
    }

    CompositionLocalProvider(
        LocalBrowserTabPool provides viewModel.browserTabPool,
        LocalMarkdownFontScale provides markdownFontScale,
        LocalToolPreviewEnabled provides toolPreviewEnabled,
        LocalMarkdownUrlClickHandler provides urlClickHandler,
        LocalMarkdownImageTapHandler provides markdownImageTapHandler,
        // Route markdown media resolution through this chat's session so
        // minis://attachments/* lookups don't rely on the global bindMounts
        // map (which is last-writer-wins across sessions).
        LocalMarkdownSessionId provides sessionId,
    ) {
    // RikkaHub-style left-swipe drawer: the chat is wrapped in a
    // ModalNavigationDrawer whose sheet is the conversation history. A left
    // edge-swipe (or the hamburger) opens it; tapping a session, New Chat, or
    // Settings navigates and closes it. gesturesEnabled is on so the swipe
    // works anywhere the chat body would otherwise not consume a horizontal
    // drag.
    val historyDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    // Reuse the screen's existing coroutineScope (declared above) to drive
    // open/close animations.
    val historyDrawerScope = coroutineScope
    // [composer-draft-v1/ime] Dismiss the IME the moment the history drawer
    // starts opening (targetValue, not isOpen) so the sheet is never covered
    // by the keyboard: the drawer sheet sits OUTSIDE the chat's imePadding(),
    // so an open keyboard would otherwise overlap its bottom rows (session
    // entries + footer buttons).
    LaunchedEffect(historyDrawerState) {
        snapshotFlow { historyDrawerState.targetValue }
            .distinctUntilChanged()
            .collect { target ->
                if (target == DrawerValue.Open) {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }
            }
    }
    // [P0-0-drawer-fix] Hoist slash/mention menu visibility to the ChatScreen
    // scope (was previously declared inside the ModalNavigationDrawer content
    // lambda) so the drawer BackHandler below can also dismiss them. Both
    // subscriptions snap to the same StateFlow the in-drawer popups read.
    val slashMenuOpen by viewModel.showSlashMenu.collectAsState()
    val mentionMenuOpenForSpy by viewModel.showMentionMenu.collectAsState()
    // [bottom-toolbar-customizable] Footer actions resolved from the pin order,
    // filtered by runtime availability so a hidden Skills/MCPs/Memory
    // repository never leaves a dead button in the footer.
    val footerSpecs = remember(chatActions.footerOrder, skillRepository, mcpRepository, memoryRepository, menuMemoryEnabled) {
        chatActions.footerOrder.mapNotNull { key ->
            if (!isChatActionAvailable(
                    key,
                    skillsAvailable = skillRepository != null,
                    mcpsAvailable = mcpRepository != null,
                    memoryAvailable = memoryRepository != null && menuMemoryEnabled,
                )
            ) {
                null
            } else {
                ChatActionCatalog.spec(key)
            }
        }
    }

    // [bottom-toolbar-customizable] Footer taps dispatch directly via
    // historyDrawerScope. We deliberately do NOT drive them through a keyed
    // LaunchedEffect: a footer tap that sets a state key and then suspends on
    // historyDrawerState.close() would be the LaunchedEffect's own key, so the
    // effect would cancel its own coroutine the moment that state is written,
    // killing close() and dropping the dispatch (footer buttons appeared to
    // "do nothing"). Launching into historyDrawerScope (the un-keyed, shared
    // scope, same as onNewChat / onOpenDraft) keeps the launch alive across
    // recomposition; suspending close() inside it also makes sure the slash-menu
    // focus restore runs only after the drawer fully closed.
    ModalNavigationDrawer(
        drawerState = historyDrawerState,
        gesturesEnabled = true,
        drawerContent = {
            // [composer-draft-v1] Live snapshot of the persisted draft slot,
            // surfaced as a "Draft" row at the top of the history drawer.
            val draftSnapshot by com.openminis.app.data.ComposerDraftStore
                .observeDraftSnapshot(context)
                .collectAsState()
            ChatHistoryDrawer(
                chatRepository = chatRepository,
                currentSessionId = sessionId,
                draft = draftSnapshot
                    ?.takeIf { it.text.isNotBlank() && it.id != sessionId },
                onOpenDraft = {
                    // [history-drawer-auto-close] Close the drawer BEFORE
                    // navigating: onOpenSession swaps the whole ChatScreen out
                    // of the composition, which cancels historyDrawerScope's
                    // coroutine and kills an in-flight close() animation —
                    // leaving the drawer visually open during the switch. So
                    // wait for the drawer to finish closing (suspend), then
                    // navigate. Catch CancellationException in case the user
                    // re-opens the drawer mid-animation; navigation should
                    // still proceed (their intent was to switch).
                    historyDrawerScope.launch {
                        try {
                            historyDrawerState.close()
                        } catch (_: kotlinx.coroutines.CancellationException) {}
                        draftSnapshot?.let { if (it.id != sessionId) onOpenSession(it.id) }
                    }
                },
                onDiscardDraft = {
                    draftSnapshot?.let { com.openminis.app.data.ComposerDraftStore.clearDraft(context, it.id) }
                },
                onSessionClick = { id ->
                    if (id == sessionId) {
                        // Current session: just close the drawer, no navigation.
                        historyDrawerScope.launch { historyDrawerState.close() }
                    } else {
                        // [history-drawer-auto-close] Other session: close the
                        // drawer first and only navigate after it settles.
                        // Navigating synchronously cancels the close() coroutine
                        // (screen leaves composition) and the drawer stays open.
                        historyDrawerScope.launch {
                            try {
                                historyDrawerState.close()
                            } catch (_: kotlinx.coroutines.CancellationException) {}
                            onOpenSession(id)
                        }
                    }
                },
                onNewChat = {
                    // [promote-draft-on-new-chat] Free the draft slot before
                    // closing the drawer + navigating, so onNewChat opens a
                    // genuinely fresh draft (the typed text is promoted to a
                    // real session instead of lost).
                    viewModel.promoteDraftIfNeeded()
                    // [history-drawer-auto-close] Same ordering: close first,
                    // navigate after the animation settles.
                    historyDrawerScope.launch {
                        try {
                            historyDrawerState.close()
                        } catch (_: kotlinx.coroutines.CancellationException) {}
                        onNewChat()
                    }
                },
                // [bottom-toolbar-customizable] Footer: resolved action list +
                // single dispatcher. The drawer no longer owns Settings/Token
                // Usage semantics — the caller (dispatchChatAction) does, so
                // footer taps behave exactly like their "..." menu twins.
                //
                // dispatch order mirrors the pre-refactor drawer (onSettings /
                // onTokenUsage): fire the action immediately and let the drawer
                // close in a parallel launch. Suspending close()-then-dispatch
                // was NOT equivalent and broke footer taps on device — a
                // ModalNavigationDrawer AnchoredDraggable close() can suspend
                // until the sheet settles, and the trailing dispatch then never
                // runs (footer buttons "did nothing"). Only SLASH_COMMANDS
                // genuinely needs the drawer closed first (it re-focuses the
                // composer underneath), so it alone waits on close().
                footerActions = footerSpecs,
                onAction = { key ->
                    if (key == ChatMenuPrefs.SLASH_COMMANDS) {
                        historyDrawerScope.launch {
                            historyDrawerState.close()
                            dispatchChatAction(key)
                        }
                    } else {
                        historyDrawerScope.launch { historyDrawerState.close() }
                        dispatchChatAction(key)
                    }
                },
                // [session-pin-toggle] Pin/unpin from the history drawer. The
                // drawer is a pure renderer (no scope of its own), so the DB
                // write runs on the chat screen's scope; drawer stays open so
                // the row visibly hops to the PINNED section live.
                onPinSession = { id ->
                    coroutineScope.launch {
                        val session = chatRepository.observeSessions()
                            .first()
                            .find { it.id == id } ?: return@launch
                        chatRepository.pinSession(id, session.pinnedAt == null)
                    }
                },
            )
        },
    ) {
    Scaffold(
        containerColor = ChatColors.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            // [perf] Collect topBar-only state here instead of at ChatScreen
            // scope so a title/model change recomposes only this lambda, not
            // the whole message list below.
            val topBarSessionTitle by viewModel.sessionTitle.collectAsState()
            TopAppBar(
                title = {
                    // RikkaHub-style LEFT-aligned nav title, sitting directly
                    // beside the hamburger instead of floating in the centre.
                    // Previously this was centre-aligned with 32dp horizontal
                    // padding, which left the title visually detached from both
                    // the nav icon and the overflow button. Left alignment gives
                    // the title the full remaining width (long session names
                    // truncate later) and reads as "this drawer -> this chat".
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        val noFontPad = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                        )
                        // Fallback pulse animation (iOS: 3× red pulse on model switch)
                        val fallbackTrigger by viewModel.fallbackTrigger.collectAsState()
                        val fallbackPulseAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
                        LaunchedEffect(fallbackTrigger) {
                            if (fallbackTrigger == 0) return@LaunchedEffect
                            repeat(3) {
                                fallbackPulseAlpha.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(350))
                                fallbackPulseAlpha.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(350))
                            }
                        }
                        Column(
                            horizontalAlignment = Alignment.Start,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Red.copy(alpha = 0.35f * fallbackPulseAlpha.value))
                                // [T-android-topbar-shrink] vertical 4dp→2dp
                                // closes the dead-space gap between the model
                                // row and the TopAppBar bottom edge.
                                //
                                // Horizontal was 32dp to pad the fallback pulse
                                // around a centred title; now that the block is
                                // left-aligned that much inset would push the
                                // title away from the hamburger, so it drops to
                                // 4dp — the pulse highlight still has room
                                // because the Column no longer spans the full
                                // width.
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            // Nav title: current session title when one
                            // exists and the toggle is on, else the app name
                            // ("RikkaMinis"). A fresh draft shows the app
                            // name — the Soul name stays in the input
                            // placeholder, where it belongs; app_name was
                            // chosen over the Soul name so the top bar reads
                            // as the app, not the assistant persona.
                            // Tap opens the same SessionEditSheet used from
                            // the session list — drafts return null from
                            // loadSessionEntity so the sheet stays closed.
                            val displayTitle = when {
                                showChatTitlePill
                                    && topBarSessionTitle.isNotBlank()
                                    && topBarSessionTitle != "New Chat" -> topBarSessionTitle
                                else -> stringResource(R.string.app_name)
                            }
                            Text(
                                text = displayTitle,
                                fontSize = 16.sp,
                                lineHeight = 19.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ChatColors.primaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = noFontPad,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        coroutineScope.launch {
                                            editingSession = viewModel.loadSessionEntity()
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                            // Model picker subtitle: green dot + group +
                            // provider/model. Tap opens the model picker —
                            // separated from the title above so tapping the
                            // title rows opens the rename sheet instead.
                            Column(
                                horizontalAlignment = Alignment.Start,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { showModelPicker = true }
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            ) {
                                // Line 1: green dot + group name + dropdown
                                // arrow (iOS: "● Default ⌄"). Only shown when a
                                // GROUP is the active binding. When the user
                                // picked a single concrete model instead
                                // (selectEntry → selectedGroupId = null), this
                                // row is hidden entirely rather than falling
                                // back to the default group name — the old
                                // fallback made every single-model selection
                                // read as "Default group", which looked like
                                // the picker wasn't switching at all. Line 2
                                // below still shows the concrete provider·model,
                                // so no information is lost.
                                if (selectedGroupId != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .background(
                                                    if (modelName.isNotEmpty()) ChatColors.success else Color(0xFFFF9500),
                                                    CircleShape,
                                                ),
                                        )
                                        // selectedGroupName can still be briefly
                                        // empty in the window before loadSession
                                        // resolves the group; fall back to the
                                        // live config's name for selectedGroupId,
                                        // then to the badge string. This is the
                                        // group-resolve race only — it no longer
                                        // fires for single-model selections,
                                        // which don't enter this branch.
                                        val groupNameDisplay = selectedGroupName.ifEmpty {
                                            availableGroups.firstOrNull { it.id == selectedGroupId }?.name
                                                ?: stringResource(R.string.model_picker_default_badge)
                                        }
                                        Text(
                                            text = groupNameDisplay,
                                            fontSize = 12.sp,
                                            lineHeight = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ChatColors.secondaryText,
                                            maxLines = 1,
                                            style = noFontPad,
                                        )
                                        Icon(
                                            Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = ChatColors.tertiaryText,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                                // Line 2: "provider · model" (iOS: "MiniMax ·
                                // MiniMax-M2.7") + the thinking-level badge laid
                                // out as a Row of two SEPARATE tappable siblings
                                // (mirrors iOS AIChatView row-2 HStack).
                                //
                                // [T-android-thinking-badge-navbar] Gesture
                                // separation: the whole subtitle Column above owns
                                // `clickable { showModelPicker = true }`, so a tap
                                // on the model text still opens the model picker.
                                // The badge declares its OWN `clickable` (see
                                // ThinkingLevelBadge), and in Compose the innermost
                                // clickable consumes the down/up events — so a tap
                                // that lands on the badge opens the thinking sheet
                                // and never bubbles up to the Column's model-picker
                                // handler. Two hit targets, zero gesture conflict,
                                // no pointerInput plumbing needed.
                                //
                                // Sizing: the model text takes `weight(1f, fill =
                                // false)` so it truncates first (Ellipsis) when the
                                // navbar is narrow; the badge has no weight, so it
                                // keeps its intrinsic width and always renders in
                                // full — the level label never gets clipped.
                                if (providerName.isNotEmpty() || modelName.isNotEmpty()) {
                                    val thinkingLevelBadgeState by viewModel.thinkingLevel.collectAsState()
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        // [T-codex-fast-mode] ⚡ badge ahead of the
                                        // resolved model name — small orange circle
                                        // + white bolt, shown only while Fast Mode
                                        // is enabled AND the active model is
                                        // eligible (iOS 9e3c76ef row-3 placement,
                                        // 09944220 9pt sizing).
                                        val fastBadgeEligible by viewModel.showFastModeToggle.collectAsState()
                                        val fastBadgeOn by viewModel.fastModeEnabled.collectAsState()
                                        if (fastBadgeEligible && fastBadgeOn) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .size(11.dp)
                                                    .background(Color(0xFFFF9500), CircleShape),
                                            ) {
                                                Icon(
                                                    Icons.Default.Bolt,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(9.dp),
                                                )
                                            }
                                        }
                                        Text(
                                            text = if (providerName.isNotEmpty() && modelName.isNotEmpty()) {
                                                "$providerName · $modelName"
                                            } else {
                                                modelName.ifEmpty { providerName }
                                            },
                                            fontSize = 11.sp,
                                            lineHeight = 13.sp,
                                            color = ChatColors.tertiaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = noFontPad,
                                            // Yield first when space is tight; the
                                            // badge to the right stays intrinsic.
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        // Show the badge whenever the bound model can
                                        // think, including when the current level is OFF.
                                        // The OFF state must keep this entry point alive:
                                        // hiding the only badge that opens the level sheet
                                        // made thinking impossible to re-enable within a
                                        // session. In the OFF state the badge displays
                                        // "Off"; tapping it opens the sheet and the user
                                        // can select LOW/MEDIUM/HIGH/etc. again.
                                        if (viewModel.availableThinkingLevels.isNotEmpty()) {
                                            ThinkingLevelBadge(
                                                level = thinkingLevelBadgeState,
                                                onClick = { showThinkingLevelSheet = true },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    // Hamburger opens the RikkaHub-style chat-history drawer
                    // (ModalNavigationDrawer wrapping this Scaffold). A left
                    // edge-swipe opens the same drawer. System/predictive back
                    // is consumed by the [P0-0-drawer-fix] BackHandler below:
                    // drawer open -> close drawer, menu open -> dismiss menu,
                    // otherwise -> background the task (never the stock
                    // SESSION_LIST). [P0-1-back-exit][P0-2-session-list-out]
                    IconButton(onClick = {
                        historyDrawerScope.launch {
                            if (historyDrawerState.isOpen) historyDrawerState.close()
                            else historyDrawerState.open()
                        }
                    }) {
                        Icon(Icons.Default.Menu, contentDescription = "Sessions")
                    }
                },
                actions = {
                    // New Chat — promoted from the "..." menu to a persistent
                    // top-bar button beside "..." (iOS parity: square.and.pencil
                    // sits next to the overflow, one tap instead of two).
                    // Always creates the draft directly — even while streaming.
                    // Leaving the chat does NOT stop the running task: the VM
                    // lives in the process-level ChatViewModelStore, so the
                    // agent loop keeps streaming in the background and the
                    // finished response lands in this session's history (the
                    // sessions list shows a spinner meanwhile). This matches
                    // session switching via the drawer, which never confirms
                    // either; a "stop the task" prompt here would be both
                    // inconsistent and factually wrong.
                    IconButton(onClick = {
                        viewModel.promoteDraftIfNeeded()
                        onNewChat()
                    }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.chat_menu_new_chat))
                    }
                    // [T-input-history] Always-visible history button (pinned
                    // like New Chat) — shows a searchable list of all messages
                    // in the current session so the user can jump back to any
                    // earlier input in one tap. Intentionally NOT inside the
                    // hasAnyMenuItems gate: both high-frequency actions (New
                    // Chat and Input History) are immune to the "hide empty
                    // menu" collapse that governs "...". However the user CAN
                    // opt-out via Settings → Appearance → Chat Menu.
                    if (chatActions.topBarInputHistoryVisible) {
                        IconButton(onClick = { showInputHistorySheet = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.FormatListBulleted,
                                contentDescription = stringResource(R.string.input_history_title),
                            )
                        }
                    }
                    // [T-chat-menu-empty] The "..." button is only worth
                    // rendering when it has at least one entry to show. If
                    // every customizable entry is off AND no model-conditional
                    // toggle (Enhanced Cache / Fast Mode) applies AND this
                    // isn't a DEBUG build, the menu would open empty — a dead
                    // control — so nothing renders and the New Chat pencil
                    // becomes the last action on the bar.
                    // [T-chat-menu-solo] When EXACTLY ONE entry would render,
                    // the overflow is pointless: promote it to a direct
                    // top-bar IconButton (one tap instead of two) and drop the
                    // "..." entirely. Two+ entries keep the overflow. The
                    // DEBUG crash trigger is counted but never promoted — a
                    // stray tap on a lone top-bar button would crash the app —
                    // so a menu that would contain only it keeps the "...".
                    // The gates below mirror the ones applied while rendering
                    // the items, so button visibility and menu content can
                    // never disagree.
                    val visibleCustomEntries = chatActions.menuOrder.filter { key ->
                        chatActions.isVisible(key) && isChatActionAvailable(
                            key,
                            skillsAvailable = skillRepository != null,
                            mcpsAvailable = mcpRepository != null,
                            memoryAvailable = memoryRepository != null && menuMemoryEnabled,
                        )
                    }
                    val showEnhancedCache by viewModel.showEnhancedCacheToggle.collectAsState()
                    val enhancedCacheOn by viewModel.enhancedCacheEnabled.collectAsState()
                    val showFastMode by viewModel.showFastModeToggle.collectAsState()
                    val fastModeOn by viewModel.fastModeEnabled.collectAsState()
                    val visibleMenuCount =
                        visibleCustomEntries.size +
                            (if (showEnhancedCache) 1 else 0) +
                            (if (showFastMode) 1 else 0) +
                            (if (BuildConfig.DEBUG) 1 else 0)
                    // Lone customizable entry (Terminal / Browser / Export / …)
                    // → direct button. Icon + label come from the same
                    // ChatActionCatalog the settings screen uses, so the
                    // promoted button always matches the entry it replaces.
                    val soloCustomKey =
                        if (visibleMenuCount == 1 && visibleCustomEntries.size == 1) visibleCustomEntries.first() else null
                    // Lone model-invocation toggle → direct button whose tint
                    // carries the on/off state (the trailing Switch would have
                    // nowhere to live).
                    val soloCacheToggle = visibleMenuCount == 1 && visibleCustomEntries.isEmpty() && showEnhancedCache
                    val soloFastToggle = visibleMenuCount == 1 && visibleCustomEntries.isEmpty() && showFastMode
                    val hasAnyMenuItems =
                        visibleMenuCount >= 1 && soloCustomKey == null && !soloCacheToggle && !soloFastToggle
                    // iOS: "..." circle button → dropdown menu
                    if (soloCustomKey != null) {
                        val spec = ChatActionCatalog.spec(soloCustomKey)
                        IconButton(onClick = { dispatchChatAction(soloCustomKey) }) {
                            Icon(
                                spec?.icon ?: Icons.Default.MoreVert,
                                contentDescription = spec?.let { stringResource(it.titleRes) } ?: "More",
                            )
                        }
                    } else if (soloCacheToggle) {
                        // Enhanced Cache as a lone top-bar toggle — same
                        // first-enable confirmation flow as the menu entry.
                        IconButton(
                            onClick = {
                                if (enhancedCacheOn) {
                                    viewModel.setEnhancedCacheEnabled(false)
                                } else if (viewModel.isEnhancedCacheConfirmed()) {
                                    viewModel.setEnhancedCacheEnabled(true)
                                } else {
                                    showEnhancedCacheDialog = true
                                }
                            },
                        ) {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = stringResource(R.string.chat_menu_enhanced_cache),
                                tint = if (enhancedCacheOn) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    } else if (soloFastToggle) {
                        IconButton(onClick = { viewModel.setFastModeEnabled(!fastModeOn) }) {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = stringResource(R.string.chat_menu_fast_mode),
                                tint = if (fastModeOn) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    } else if (hasAnyMenuItems) {
                        Box {
                            IconButton(onClick = { showChatMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            MinisMenu(
                                expanded = showChatMenu,
                                onDismissRequest = { showChatMenu = false },
                            ) {
                            // [T-android-memory-enabled-minisconfig] Gate the
                            // "Memories in Session" item below on the session's
                            // live memoryEnabled — when memory is off the entry
                            // disappears, consistent with the per-session gating
                            // of the memory_get / memory_write tools and the
                            // system-prompt injection. (menuMemoryEnabled is
                            // collected at the top of ChatScreen, next to
                            // chatActions, so the menu and the drawer footer
                            // stay in sync.)
                            // [T-customizable-chat-menu] The eight action /
                            // session entries below are driven by
                            // ChatMenuPrefs (Settings → Appearance → Chat
                            // Menu): the user can hide entries and reorder
                            // them. Rendering follows chatActions.menuOrder
                            // (missing keys appended in default order, unknown
                            // keys dropped) and skips entries whose visibility
                            // switch is OFF. Conditional entries (Skills /
                            // MCPs / Memory) additionally keep their runtime
                            // gate: an entry only shows when BOTH the user
                            // toggle AND the runtime condition are true, so
                            // hiding it never resurrects a dead repository.
                            // Model-invocation toggles (Enhanced Cache, Fast
                            // Mode) and the DEBUG crash trigger are NOT part
                            // of this list — they stay pinned at the bottom.
                            // Taps funnel through dispatchChatAction() (hoisted
                            // near the top of ChatScreen) so the "..." menu and
                            // the history-drawer footer share one implementation
                            // per action. EXPORT opens ExportFormatSheet instead
                            // of the old inline JSON/Plain submenu.
                            for (entryKey in chatActions.menuOrder) {
                                if (!chatActions.isVisible(entryKey)) continue
                                key(entryKey) {
                                    when (entryKey) {
                                        ChatMenuPrefs.TERMINAL -> {
                                            // Open Terminal (iOS parity) — session-bound, starts in /var/minis
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.chat_menu_open_terminal)) },
                                                onClick = {
                                                    showChatMenu = false
                                                    dispatchChatAction(ChatMenuPrefs.TERMINAL)
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Default.Terminal, contentDescription = null)
                                                },
                                            )
                                        }
                                        ChatMenuPrefs.BROWSER -> {
                                            // Open Browser (iOS parity)
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.chat_menu_open_browser)) },
                                                onClick = {
                                                    showChatMenu = false
                                                    dispatchChatAction(ChatMenuPrefs.BROWSER)
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Default.Language, contentDescription = null)
                                                },
                                            )
                                        }
                                        ChatMenuPrefs.CHAT_FILES -> {
                                            // Browse Chat Files (iOS parity) — opens file browser at /var/minis
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.chat_menu_browse_chat_files)) },
                                                onClick = {
                                                    showChatMenu = false
                                                    dispatchChatAction(ChatMenuPrefs.CHAT_FILES)
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Default.Description, contentDescription = null)
                                                },
                                            )
                                        }
                                        ChatMenuPrefs.COMPACT -> {
                                            // Compress conversation history into a summary.
                                            // [bottom-toolbar-customizable] Moved out of the slash
                                            // picker into the customizable chat-action pool — it is
                                            // a frequent session-level operation, not an input aid.
                                            // Shows a live "compressing…" hint while a compaction
                                            // is in progress.
                                            val compacting = viewModel.isCompacting.collectAsState()
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text(stringResource(R.string.chat_menu_compact))
                                                        if (compacting.value) {
                                                            Text(
                                                                stringResource(R.string.menu_compacting_in_progress),
                                                                fontSize = 12.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            )
                                                        }
                                                    }
                                                },
                                                onClick = {
                                                    showChatMenu = false
                                                    dispatchChatAction(ChatMenuPrefs.COMPACT)
                                                },
                                                enabled = !compacting.value,
                                                leadingIcon = {
                                                    Icon(Icons.Default.Compress, contentDescription = null)
                                                },
                                            )
                                        }
                                        ChatMenuPrefs.THINKING -> {
                                            // Thinking level (off/low/med/high). Displays the
                                            // current level as a subtitle so the user sees state
                                            // before deciding to toggle. Tap toggles OFF<->MEDIUM;
                                            // the level picker sheet is reachable from the current
                                            // level badge elsewhere. Unsupported models grey out
                                            // the row. [bottom-toolbar-customizable] Moved out of
                                            // the slash picker for the same reason as compact.
                                            val lev = viewModel.thinkingLevel.collectAsState().value
                                            val supported = viewModel.currentModelSupportsReasoning
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text(stringResource(R.string.chat_menu_thinking))
                                                        Text(
                                                            if (supported) lev.localizedName(context)
                                                            else context.getString(R.string.slash_thinking_unsupported),
                                                            fontSize = 12.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    showChatMenu = false
                                                    dispatchChatAction(ChatMenuPrefs.THINKING)
                                                },
                                                enabled = supported,
                                                leadingIcon = {
                                                    Icon(Icons.Default.Lightbulb, contentDescription = null)
                                                },
                                            )
                                        }
                                        ChatMenuPrefs.SLASH_COMMANDS -> {
                                            // Slash commands. Moved here from the dedicated "/"
                                            // circle button that used to sit next to "+" in the
                                            // composer: the button occupied permanent space in
                                            // the input row for an action most users invoke by
                                            // simply typing "/", and the row is the most
                                            // contended horizontal space on the screen. The
                                            // menu keeps it discoverable for people who don't
                                            // know the typed shortcut. Placed directly above
                                            // Token Usage per the requested ordering.
                                            //
                                            // Behaviour is unchanged from the old button: it
                                            // toggles, so opening the menu while the slash
                                            // sheet is already up dismisses it. (Toggle logic
                                            // lives in dispatchChatAction so the footer entry
                                            // behaves identically.)
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.chat_menu_slash_commands)) },
                                                onClick = {
                                                    showChatMenu = false
                                                    dispatchChatAction(ChatMenuPrefs.SLASH_COMMANDS)
                                                },
                                                leadingIcon = {
                                                    // Match the old button's italic-bold "/"
                                                    // glyph rather than substituting a generic
                                                    // icon, so the entry reads as the same
                                                    // affordance that moved.
                                                    Text(
                                                        "/",
                                                        fontSize = 20.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        fontStyle = FontStyle.Italic,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                },
                                            )
                                        }
                                        ChatMenuPrefs.EXPORT -> {
                                            // Export conversation (JSON / Plain Text) — the
                                            // session list's long-press Export, surfaced from
                                            // inside the chat. Same streaming ChatExporter, so
                                            // long chats stay bounded-memory. Placed between
                                            // Slash Commands and Token Usage per the requested
                                            // ordering.
                                            //
                                            // [bottom-toolbar-customizable] The old inline
                                            // JSON/Plain submenu is gone: both the "..." menu
                                            // and the history-drawer footer now open the same
                                            // shared ExportFormatSheet, so the format choice
                                            // lives in exactly one implementation.
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.sessionlist_export)) },
                                                onClick = {
                                                    showChatMenu = false
                                                    showExportFormatSheet = true
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Default.Share, contentDescription = null)
                                                },
                                            )
                                        }
                                        ChatMenuPrefs.SESSION_SKILLS -> {
                                            // Session Skills (iOS parity)
                                            if (skillRepository != null) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.session_skills_title)) },
                                                    onClick = {
                                                        showChatMenu = false
                                                        dispatchChatAction(ChatMenuPrefs.SESSION_SKILLS)
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.Build, contentDescription = null)
                                                    },
                                                )
                                            }
                                        }
                                        ChatMenuPrefs.SESSION_MCPS -> {
                                            // [T-mcp-integration-android] MCPs in Session, next to Skills.
                                            if (mcpRepository != null) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.session_mcps_title)) },
                                                    onClick = {
                                                        showChatMenu = false
                                                        dispatchChatAction(ChatMenuPrefs.SESSION_MCPS)
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.Extension, contentDescription = null)
                                                    },
                                                )
                                            }
                                        }
                                        ChatMenuPrefs.SESSION_MEMORY -> {
                                            // Session Memory (iOS parity)
                                            if (memoryRepository != null && menuMemoryEnabled) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.session_memory_title)) },
                                                    onClick = {
                                                        showChatMenu = false
                                                        dispatchChatAction(ChatMenuPrefs.SESSION_MEMORY)
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.Psychology, contentDescription = null)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            // Enhanced Cache (iOS parity, commit 57aaf122):
                            // 1-hour Anthropic cache TTL. Only shown for the
                            // official Anthropic API (not relays / other
                            // providers) — showEnhancedCacheToggle recomputes on
                            // model/provider switch. First enable prompts a
                            // one-time extra-billing confirmation. (These
                            // flags are collected above, next to the "..."
                            // visibility check.)
                            if (showEnhancedCache) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_menu_enhanced_cache)) },
                                    onClick = {
                                        if (enhancedCacheOn) {
                                            viewModel.setEnhancedCacheEnabled(false)
                                        } else if (viewModel.isEnhancedCacheConfirmed()) {
                                            viewModel.setEnhancedCacheEnabled(true)
                                        } else {
                                            showChatMenu = false
                                            showEnhancedCacheDialog = true
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Bolt, contentDescription = null)
                                    },
                                    trailingIcon = {
                                        Switch(
                                            checked = enhancedCacheOn,
                                            onCheckedChange = {
                                                if (enhancedCacheOn) {
                                                    viewModel.setEnhancedCacheEnabled(false)
                                                } else if (viewModel.isEnhancedCacheConfirmed()) {
                                                    viewModel.setEnhancedCacheEnabled(true)
                                                } else {
                                                    showChatMenu = false
                                                    showEnhancedCacheDialog = true
                                                }
                                            },
                                        )
                                    },
                                )
                            }
                            // [T-codex-fast-mode] Fast Mode (iOS parity,
                            // fb671083 + 838ba929): shown when the active model
                            // is a gpt-family model served through the
                            // Responses path (useResponsesAPI instance or Codex
                            // OAuth). App-level persisted toggle; while on, the
                            // Responses body carries service_tier="priority"
                            // (≈1.5x faster at 2x credit burn on the ChatGPT
                            // subscription) and the nav model row shows a ⚡
                            // badge. Sits next to Enhanced Cache — both are
                            // model-invocation controls (iOS 09944220 grouping).
                            if (showFastMode) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_menu_fast_mode)) },
                                    onClick = { viewModel.setFastModeEnabled(!fastModeOn) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Bolt, contentDescription = null)
                                    },
                                    trailingIcon = {
                                        Switch(
                                            checked = fastModeOn,
                                            onCheckedChange = { viewModel.setFastModeEnabled(it) },
                                        )
                                    },
                                )
                            }
                            // T287: debug-only crash trigger so the user can verify
                            // ACRA/native crash log generation (T283). Throws a
                            // RuntimeException from the click handler — the
                            // uncaught-exception handler catches it and writes
                            // a crash-<stamp>.log under filesDir/logs/.
                            if (BuildConfig.DEBUG) {
                                MinisMenuDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.debug_trigger_crash_menu),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        showChatMenu = false
                                        throw RuntimeException(
                                            "Debug crash triggered by user (T287)",
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.BugReport,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                )
                            }
                        }
                    }
                    } // [T-chat-menu-solo] end else-if chain: solo buttons / "..." overflow
                },
                windowInsets = WindowInsets.statusBars,
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = ChatColors.background.copy(alpha = 0.92f),
                    scrolledContainerColor = ChatColors.background.copy(alpha = 0.92f),
                ),
                // [T-android-topbar-shrink] 76dp → 68dp. The earlier
                // T-topbar-model-row-clip fix bumped 60dp → 76dp to give the
                // 3-row title (14sp/lh17 + 12sp/lh14 + 11sp/lh13 ≈ 44sp text
                // + 4dp+2dp+1dp vertical padding ≈ 51dp on mdpi, mid-60s on
                // xxhdpi) room to breathe — but overshot, leaving visible
                // dead-space below the model row. This trim pairs with the
                // outer Column's vertical-padding drop (4dp→2dp above):
                // budget is now ~44sp text + 2dp+2dp+1dp ≈ 49dp typical,
                // ~58-62dp at xxhdpi 2.625× rounding. 68dp keeps a 6-10dp
                // safety margin so the model name still fits at any
                // user-configured font scale on xhdpi/xxhdpi without
                // re-clipping (T-topbar-model-row-clip regression check).
                // Font sizes + lineHeights stay untouched per spec.
                expandedHeight = 68.dp,
            )
        },
        // [T-error-no-permanent-scars] SnackbarHost is NOT registered here —
        // it's rendered inside the content Box aligned to the TOP (below the
        // app bar) so transient notices (model-fallback switches, budget
        // events) appear "from above" and auto-dismiss, never polluting the
        // chat record.
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Dismiss keyboard when the USER scrolls the messages. Gated on
            // `isUserDragging` (a real finger drag) rather than
            // `listState.isScrollInProgress` — the latter is also true during
            // the streaming auto-follow's programmatic glide, so the old code
            // hid the keyboard + cleared focus on every streaming tick, which
            // closed the IME mid-stream and dropped the user's in-flight
            // keystroke. [T-android-composer-input-blocked-while-streaming]
            LaunchedEffect(isUserDragging) {
                if (isUserDragging) {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }
            }

            // Messages + scroll-to-bottom button
            Box(modifier = Modifier.weight(1f)) {
                var toolBarHeightPx by remember { mutableStateOf(0) }
                val density = LocalDensity.current
                val toolBarHeightDp = with(density) { toolBarHeightPx.toDp() }
                // T166 / T170 / T173: bottomReserve must clear the visible
                // top of the floating tool-status overlay. Layout primitives
                // come from FloatingToolStatusBar:
                //   - status bar height = 38 dp
                //   - thumbnail floats over the bar with overhang = 27 dp
                //   - thumbnail TOP = bar top - overhang = 65 dp above the
                //     input bar's upper edge (which is also the LazyColumn
                //     bottom edge under reverseLayout).
                //
                // `onGloballyPositioned` on the wrapper Box reports ~98 dp
                // because it includes wrapper padding(bottom=6) + horizontal
                // padding insets + shadow allowance — none of which are
                // *visually occluding* the LazyColumn. Using the measured
                // value + 8 dp left a ~25 dp gap above the thumbnail (red
                // box in the user's report).
                //
                // Pin to the visual constant: thumbnail height (65 dp) + a
                // visual buffer (18 dp) so the latest row's bottom has clear
                // breathing room above the thumbnail top.
                //
                // T174: an earlier version gated reserve on `toolBarHeightPx
                // > 0`, but `onGloballyPositioned` fires asynchronously after
                // the first floating-bar layout pass; for one frame after
                // toolBlocks appeared the reserve evaluated the small
                // default (28 dp) and the just-arrived user bubble landed
                // beneath the bar. Logcat showed the inverse glitch too:
                // `toolBarHeightPx=258 toolBarHeightDp=0 reserve=28` — the
                // px state and the dp/reserve values come from different
                // recomposition snapshots. Drive the reserve directly off
                // the same predicate used for *whether* the floating bar is
                // emitted (`hasFloatingTools` below) so reserve and bar
                // visibility flip on the same frame.
                // [T-android-chat-cannot-scroll-bottom-many-tools]
                // Bug 𝙓𝙄𝙉 TG36286: with 7+ tools the user couldn't scroll the
                // last messages above the floating tool status bar.
                //
                // Asymmetry between the bar's render condition and its
                // bottomReserve gate: [lastToolBlocks] (drives whether to
                // mount FloatingToolStatusBar) merges `messages` with the
                // streaming-side-channel `streamingById`, so during a live
                // turn the in-flight tool's toolStatus shows up there →
                // bar renders. [hasFloatingTools] (drives bottomReserve)
                // only read `messages`, which the streaming architecture
                // intentionally leaves stable during a turn — so the
                // in-flight tool is invisible to this predicate → reserve
                // collapsed to 20dp while a 65dp+6dp floating bar covered
                // the bottom of the LazyColumn. The new arrivals (status
                // pill, "Minis is thinking" indicator, inline retry banner) landed
                // behind the bar with no way to scroll them into view.
                //
                // Fix: also subscribe to streamingById so the predicate
                // matches the bar's actual mount condition. The bar's
                // mount uses `lastToolBlocks.isNotEmpty()` over the merged
                // view; we mirror that semantically by checking the same
                // filter on both sources.
                val streamingById by viewModel.streamingById.collectAsState()
                // Also gated on toolStatusBarEnabled: when the user hides the
                // whole floating bar, hasFloatingTools must report false so
                // bottomReserve drops to the normal no-tool padding (otherwise
                // an empty 65dp+ gap stays above the composer). Recompute on
                // toggle so the reserve and bar flip together.
                val hasFloatingTools = remember(messages, streamingById, toolStatusBarEnabled) {
                    if (!toolStatusBarEnabled) return@remember false
                    val merged = if (streamingById.isEmpty()) messages
                                 else mergeStreamingOverlay(messages, streamingById, viewModel.currentStreamEpoch())
                    merged.any { msg ->
                        msg.role == "assistant" && msg.toolBlocks.any { tb ->
                            tb.toolStatus != null && tb.kind != "thinking" && tb.kind != "info"
                        }
                    }
                }
                val visualOverlayHeight = 65.dp  // thumbnailHeight in FloatingToolStatusBar
                // Halve the breathing room above the input bar in both
                // states — felt too sparse before. The thumbnail's 65dp
                // physical height is preserved (it has to clear the
                // floating overlay).
                //
                // T245: buffer raised 9dp → 14dp so the gap between the
                // last LazyColumn tool row and the floating thumbnail's
                // top reads at least as loose as the inter-tool spacing
                // (each ToolCallPill carries padding(vertical = 3.dp) +
                // LazyColumn spacedBy(2.dp) = ~8dp inter-tool gap; the
                // 9dp buffer combined with the floating bar's internal
                // overhang was visually tighter than 8dp). 14dp also
                // matches the no-tool branch — single visual constant
                // for "row-bottom → bottom chrome" breathing room.
                // [T-bottom-occluded 0a6d3c92] No-tools branch bumped from
                // 14dp → 20dp to give the last message bubble a comfortable
                // gap above the composer's top edge. With 14dp the trailing
                // line sat too close to the composer shadow / rounded edge
                // (user reported "the bottom of the text is slightly clipped"). The floating-tools branch
                // already reserves visualOverlayHeight (65dp) + buffer and
                // was not part of the report; keep its +14 buffer.
                val bottomReserve =
                    if (hasFloatingTools) visualOverlayHeight + 14.dp else 20.dp
                // T120: removed three streaming-time LaunchedEffects that
                // each called scrollToItem(0) on every chunk:
                //   - LE(bottomReserve): toolbar resize re-snap
                //   - LE(lastToolCount): per-tool-pill follow
                //   - LE(lastAwaiting):  thinking-indicator follow
                // They fired several times per second during streaming and
                // turned every layout pass into a scroll command, fighting
                // reverseLayout's native bottom-anchor behavior. With
                // reverseLayout=true Compose already keeps the visual
                // bottom pinned when the list is at offset 0; if the user
                // scrolled away, the JumpToBottom FAB is the explicit
                // affordance to return.

                // Flatten each message into multiple LazyColumn items so that older blocks
                // (text / tool pills / thinking) are frozen LazyList items while only the
                // last streaming block changes height. This keeps scroll-hovering stable:
                // LazyListState anchors on a stable item key + pixel offset, and inserting
                // or growing the trailing item never disturbs earlier items.
                //
                // T94: long sessions (hundreds of messages, deep tool chains) made the
                // flatten step expensive enough to stall composition on the main thread —
                // every streaming token recomposed the parent and re-ran the O(N · blocks)
                // walk inside `remember`, producing visible jank and ANRs on slower
                // devices. Run the flatten on Dispatchers.Default and publish the result
                // through a snapshot-state field so the LazyColumn renders the previous
                // frame's list while the next one computes. Keyed on `sessionId` so a
                // chat-switch resets the cache; LaunchedEffect(messages) reruns the
                // computation on every new emission.
                var flatItems by remember(sessionId) {
                    mutableStateOf<List<FlatChatItem>>(emptyList())
                }
                // [fix/long-session-aggregate-storm] Aggregate-path reuse pair
                // stored at `remember(sessionId)` scope so it SURVIVES the
                // messages-keyed effect restart. This is what lets a pause /
                // cancel (side-channel drained into `_messages` → `messages`
                // key changes → effect restarts) reuse the frozen prefix via
                // buildAggregateChatItemsIncremental instead of a cold O(N)
                // rebuild. Reset on session switch (keyed on sessionId).
                //
                // Plain holder (NOT snapshot state): these are read/written
                // only inside the flatten effect's coroutine, never in a
                // composition body, so making them observable would add
                // needless invalidation overhead with no reader.
                val aggregateReuse = remember(sessionId) {
                    object {
                        var messages: List<ChatMessage>? = null
                        var items: List<FlatChatItem>? = null
                    }
                }
                // [forward-stable] Session-lifetime stable row ledger. Owns the
                // row list once seeded: cold open builds canonically, then every
                // tick is an incremental reconcile with prefix-stable keys.
                val rowLedger = remember(sessionId) {
                    StableChatRowLedger(
                        onDivergence = { messageId, blockId, count, snippet ->
                            // [fix/stream-segmenter-duplication] Real-device breadcrumb
                            // for the segmenter divergence path — the former source of
                            // token-level duplication. Any non-zero count during normal
                            // streaming is worth surfacing while we validate the rewrite.
                            AppLogger.warning(
                                "SegmenterDivergence",
                                "msg=$messageId block=$blockId count=$count snippet=[$snippet]",
                            )
                        },
                    )
                }
                // [T-android-coldload-offmain-parse] Composition-snapshot
                // prewarmer (captures the markdown palette) used by the
                // flatten effect below to warm the parse caches for the
                // viewport-candidate fragments off-main.
                val prewarmMarkdown = rememberMarkdownPrewarmer()
                // [T-android-jank-diag-logging] Cold-open one-line summary
                // state: emitted ONCE per session open at the first
                // firstItem.placed; prewarmMs is filled by the parallel
                // prewarm when (if) it has finished by then, else -1.
                var lastColdPrewarmMs by remember(sessionId) { mutableStateOf(-1L) }
                var coldOpenSummaryEmitted by remember(sessionId) { mutableStateOf(false) }
                val screenMountAtMs = remember(sessionId) { System.currentTimeMillis() }
                // T-streaming-side-channel: messages-level changes (new
                // message, retry, etc.) AND streamingById deltas both feed
                // buildFlatChatItems, but we subscribe to streamingById
                // INSIDE LaunchedEffect (not at top-level) so per-token
                // emissions don't recompose the surrounding ChatScreen
                // scope. The flatten still runs per token (cheap-ish; ran
                // before too), but the rebuild stays off the main UI
                // composable's invalidation list.
                LaunchedEffect(messages, sessionId) {
                    // [T-android-stream-pipeline-incremental] Frozen/live split.
                    //
                    // `messages` is CONSTANT within this effect (the effect is
                    // keyed on it and the streaming turn writes high-frequency
                    // fields into the streamingById side-channel, never the
                    // canonical list). So the rows for every message BEFORE the
                    // first streamed one (= the frozen prefix) can be computed
                    // ONCE per effect lifetime and reused by reference on every
                    // tick. Per tick we only rebuild the live suffix (usually a
                    // single message). Pre-split, every 80ms tick re-flattened
                    // ALL messages (1146 rows on the ANR-loop session), re-ran
                    // splitMarkdownIntoBlockTexts over every frozen message,
                    // and allocated the whole row set fresh — the 130–180MB/s
                    // GC storm and the 100s builds in minis-2026-06-10.log.
                    //
                    // Row-for-row equivalence with the old full build holds by
                    // construction: buildFlatChatItems' neighbor lookbacks
                    // (precededByUser / isResumeContinuation) only ever read
                    // EARLIER messages, the live suffix is built against the
                    // full merged list with fromIndex (lookbacks cross the
                    // boundary), and dedupe continuity is preserved via
                    // seedKeys. Frozen rows are the same instances every tick,
                    // so LazyColumn's key+equals skip path sees ZERO change.
                    //
                    // Throttle (unchanged): conflate() + sample(80) keeps UI
                    // publication at ~12fps regardless of token rate.
                    var streamWasActive = false
                    // [fix/long-session-flatten-storm] Content-noop detection:
                    // the merged list from the LAST collect tick, used to skip
                    // the incremental reconcile when the underlying data is
                    // byte-identical (stream drained, side-channel stable).
                    // Data-class `==` covers every rendering-relevant field
                    // (content, toolBlocks incl. all toolStatus, isStreaming,
                    // error, queued, ...), so a matched fingerprint is a true
                    // "nothing to do" — skipping avoids re-running segmenters
                    // (full markdown re-split of the accumulated text) and
                    // re-building non-text rows on every 80ms tick during a
                    // long tool execution. Resets to null on each effect
                    // restart (session switch / messages key change).
                    var lastMergedFingerprint: List<Any?>? = null
                    // [fix/history-open-at-bottom-04] The INITIAL_OPEN
                    // scroll-to-bottom is owned by THIS collector, not the
                    // bottom-scroll consumer effect. Rationale: the consumer
                    // keys off sessionLoaded / pending requests and can run
                    // against an EMPTY flatItems (sessionLoaded flips before
                    // this async flatten chain publishes), consuming the
                    // opening scroll and stranding the list at the oldest
                    // message — the round-4 "open then jump away" bug. The
                    // first non-empty publish below is the single point where
                    // the real rows actually exist, so the scroll (and its
                    // consume) happens here, exactly once per effect lifetime.
                    var initialBottomScrollFired = false
                    // [T-android-stream-pipeline-incremental] Flush the perf
                    // turn when this effect is CANCELLED mid-turn: the
                    // turn-end drain emits `_messages` FIRST (restarting this
                    // messages-keyed effect) and clears the side-channel
                    // after, so the cancelled collector never sees the
                    // empty-stream tick that would fire turnEnd — without the
                    // finally, same-session turns accumulate forever and no
                    // [StreamPerf] summary is ever emitted.
                    try {
                    kotlinx.coroutines.flow.combine(
                        kotlinx.coroutines.flow.flowOf(messages),
                        viewModel.streamingById,
                    ) { msgs, stream -> msgs to stream }
                        .conflate()
                        .sample(80L)
                        .collect { (msgs, stream) ->
                            val tickStartNs = System.nanoTime()
                            if (stream.isNotEmpty() && !streamWasActive) {
                                streamWasActive = true
                                com.openminis.app.diagnostics.StreamPerfMonitor.turnStart(sessionId)
                            }
                            // ── [forward-stable] StableChatRowLedger path ──
                            // The ledger owns the row list once seeded: cold
                            // open builds canonically (off-main, with the
                            // viewport prewarm), every later tick is an
                            // incremental reconcile — new messages append at
                            // the tail, the active turn's text is
                            // segmenter-managed (mdslot keys, live tail only),
                            // and published keys are prefix-stable. Structural
                            // list changes (load-older, deletion, compaction)
                            // invalidate the append-only contract and force a
                            // full re-seed via isIncrementallyCompatible().
                            val merged = mergeStreamingOverlay(msgs, stream, viewModel.currentStreamEpoch())
                            // [fix/message-node-item-generator] Message-level
                            // aggregate path (default OFF). When enabled, the
                            // whole merged list is built once into one item
                            // per message — no ledger / segmenter. Stage D
                            // flips this on once the shared AssistantMessageView
                            // renders AssistantMessageItem. Early-return keeps
                            // the ledger path below exactly as it was.
                            if (AGGREGATE_MESSAGE_ITEMS) {
                                // [fix/long-session-aggregate-storm] Incremental
                                // rebuild. A pause/cancel drains the side-channel
                                // into `_messages` (ONLY the live tail message
                                // changes) and restarts this effect; without the
                                // incremental path every such restart re-ran
                                // buildAggregateChatItems over the WHOLE session
                                // (O(N) on the default dispatcher + a fresh list
                                // reference → LazyColumn full diff). Now: frozen
                                // prefix items are reused by reference, only the
                                // changed tail is rebuilt, and an identical
                                // merged list reuses the previous items as-is.
                                //
                                // Cold start (no previous publish) still runs the
                                // full build OFF-MAIN — the incremental path only
                                // takes over once we have a prior items+merge
                                // pair to diff against.
                                val prevMsgs = aggregateReuse.messages
                                val prevItems = aggregateReuse.items
                                val nextItems = if (prevMsgs == null || prevMsgs.isEmpty() ||
                                    prevItems == null || prevItems.isEmpty()
                                ) {
                                    withContext(Dispatchers.Default) {
                                        buildAggregateChatItems(merged)
                                    }
                                } else {
                                    buildAggregateChatItemsIncremental(prevItems, prevMsgs, merged)
                                }
                                flatItems = nextItems
                                aggregateReuse.messages = merged
                                aggregateReuse.items = nextItems
                                if (!initialBottomScrollFired) {
                                    initialBottomScrollFired = true
                                    if (nextItems.isNotEmpty() &&
                                        shouldScrollToBottomOnFirstRows(
                                            pendingBottomRequest = followState.pendingBottomRequest,
                                            isFollowing = followState.isFollowing,
                                        )
                                    ) {
                                        // Land on the bottom sentinel with an
                                        // oversized index: LazyListMeasure clamps
                                        // index >= itemsCount to itemsCount-1, so
                                        // this resolves against the LIVE providers
                                        // (real rows + resume banner + sentinel)
                                        // without ever trusting a stale
                                        // totalItemsCount. The ROWS were just
                                        // published into the same snapshot state,
                                        // so the next measure sees them. Use a
                                        // finite-but-huge index (not Int.MAX_VALUE)
                                        // so NearestRangeState's window math cannot
                                        // overflow its sliding-window arithmetic.
                                        AppLogger.debug("ScrollSrc", "scroll-bottom reason=INITIAL_OPEN(first-rows) rows=${nextItems.size}")
                                        listState.scrollToItem(index = 1_000_000, scrollOffset = 0)
                                    }
                                    followState = consumeBottomRequest(followState)
                                }
                                return@collect
                            }
                            if (flatItems.isEmpty()) {
                                val tBuildStart = System.nanoTime()
                                com.openminis.app.diagnostics.PerfLongCtx.step(
                                    sessionId,
                                    "buildFlatChatItems.start",
                                    "msgCount=${msgs.size}",
                                )
                                val rows = withContext(Dispatchers.Default) {
                                    // [T-android-flatitems-sublist-cme] Pass a
                                    // SNAPSHOT COPY (msgs.take), not a subList —
                                    // a subList is a live VIEW sharing the
                                    // parent's modCount and threw
                                    // ConcurrentModificationException when the
                                    // backing list changed mid-build.
                                    buildFlatChatItems(merged, sessionId)
                                }
                                val buildMs = (System.nanoTime() - tBuildStart) / 1_000_000
                                rowLedger.seed(rows, merged.size)
                                // [T-android-coldload-offmain-parse] Parallel
                                // viewport prewarm: block-parse + inline-warm
                                // the newest (viewport-candidate) markdown
                                // fragments off-main so the first frame's rows
                                // compose as cache HITs. Deliberately launched
                                // in PARALLEL with the flatItems publish, not
                                // before it — blocking the publish would add
                                // the parse latency to time-to-first-frame.
                                if (stream.isEmpty() && rows.isNotEmpty()) {
                                    val prewarmRowLimit = 16
                                    val prewarmCharBudget = 96_000
                                    val raws = mutableListOf<String>()
                                    var charSum = 0
                                    for (item in rows.asReversed()) {
                                        if (raws.size >= prewarmRowLimit || charSum >= prewarmCharBudget) break
                                        val raw = (item as? FlatChatItem.AssistantMarkdownBlock)?.rawText ?: continue
                                        raws.add(raw)
                                        charSum += raw.length
                                    }
                                    if (raws.isNotEmpty()) {
                                        launch(Dispatchers.Default) {
                                            val tPrewarmNs = System.nanoTime()
                                            prewarmMarkdown(raws)
                                            val prewarmMs = (System.nanoTime() - tPrewarmNs) / 1_000_000
                                            lastColdPrewarmMs = prewarmMs
                                            com.openminis.app.diagnostics.PerfLongCtx.step(
                                                sessionId,
                                                "coldPrewarm.done",
                                                "rows=${raws.size} chars=$charSum prewarmMs=$prewarmMs",
                                            )
                                        }
                                    }
                                }
                                com.openminis.app.diagnostics.PerfLongCtx.step(
                                    sessionId,
                                    "buildFlatChatItems.firstBuild",
                                    "msgCount=${msgs.size} rowCount=${rows.size} buildMs=$buildMs",
                                )
                                if (rows.size > 3000) {
                                    com.openminis.app.diagnostics.PerfLongCtx.step(
                                        sessionId,
                                        "buildFlatChatItems.highRowCount",
                                        "rowCount=${rows.size} threshold=3000 msgCount=${msgs.size}",
                                    )
                                }
                            } else {
                                // Incremental reconcile, or a full re-seed when
                                // the message list structure changed.
                                // [fix/long-session-flatten-storm] Content-noop
                                // skip: when `merged` is byte-identical to the
                                // previous tick (streaming drained and the
                                // side-channel is stable — e.g. the whole time
                                // the agent loop is blocked on a long tool
                                // execution), reconcile would only re-run each
                                // AppendOnlyMarkdownSegmenter (a full
                                // re-split of the accumulated 10k+ char answer)
                                // and re-build the same non-text rows, producing
                                // identical rows. Skip it entirely — the
                                // fingerprint is the full data-class-equal
                                // list, so no field change can be missed
                                // (it covers content, toolBlocks with all
                                // toolStatus, isStreaming, error, queued, ...).
                                // The turn-end tick is NOT skipped: it drains
                                // the side-channel into merged (final terminal
                                // tool states + complete text), so it differs
                                // from the live-stream tick that precedes it.
                                if (lightFingerprint(merged) != lastMergedFingerprint) {
                                    if (!rowLedger.isIncrementallyCompatible(merged)) {
                                        val tRebuildStart = System.nanoTime()
                                        val rows = withContext(Dispatchers.Default) {
                                            buildFlatChatItems(merged, sessionId)
                                        }
                                        val buildMs = (System.nanoTime() - tRebuildStart) / 1_000_000
                                        rowLedger.seed(rows, merged.size)
                                        com.openminis.app.diagnostics.PerfLongCtx.step(
                                            sessionId,
                                            "buildFlatChatItems.ledgerReseed",
                                            "msgCount=${msgs.size} rowCount=${rows.size} buildMs=$buildMs",
                                        )
                                    }
                                    rowLedger.reconcile(merged)
                                }
                                lastMergedFingerprint = lightFingerprint(merged)
                            }
                            flatItems = rowLedger.snapshot()
                            // [fix/scroll-follow-simplify] Removed the
                            // prevRowKeys append-only prefix telemetry and the
                            // followReducer(StreamRowsChanged) dispatch. Under
                            // AGGREGATE_MESSAGE_ITEMS this code is unreachable
                            // (the aggregate branch early-returns above), and
                            // under SIMPLE_FOLLOW the dedicated
                            // `isStreaming && isAtBottom → requestScrollToItem`
                            // effect is the single follow driver — the reducer
                            // neither scrolls nor needs a data-revision poke.
                            com.openminis.app.diagnostics.StreamPerfMonitor.tick(
                                flattenNanos = System.nanoTime() - tickStartNs,
                                frozenReused = true,
                                frozenRows = 0,
                                liveRows = flatItems.size,
                            )
                            if (stream.isEmpty() && streamWasActive) {
                                streamWasActive = false
                                com.openminis.app.diagnostics.StreamPerfMonitor.turnEnd()
                                // [fix/chat-render-turnend-settle] Turn end no
                                // longer forces a full canonical rebuild +
                                // ledger re-seed. The side-channel drains the
                                // delta into `_messages` as a single emit, so
                                // this tick's `merged` already carries the
                                // terminal tool states and complete text; the
                                // incremental reconcile converges it in place:
                                // textual rows are settled by their
                                // AppendOnlyMarkdownSegmenter
                                // (streamEnded=true — settle only, keys stay
                                // mdslot:..., no re-split), RUNNING tool group
                                // pills flip to their terminal state, and the
                                // typing indicator retires. Published keys are
                                // prefix-stable, so the LazyColumn slots are
                                // updated in place with zero churn — this is
                                // what kills the "list jumps / re-draws at
                                // answer end" artifact and the per-turn
                                // segmenter reset that made every finished
                                // turn re-render from scratch.
                                //
                                // Convergence guard for the
                                // AssistantMarkdownBlock cheap-equals blind
                                // spot: `equals` only compares rawText LENGTH
                                // (ChatFlatItems.kt), so a same-length
                                // content rewrite between the last streaming
                                // tick and the terminal snapshot would be
                                // invisible to LazyColumn's skip decision and
                                // the stale text would stay rendered.
                                // reconcileAndVerifyTerminalText re-derives
                                // each segmenter's slots from the canonical
                                // terminal text and force-publishes any slot
                                // whose content differs — content equality,
                                // not length equality.
                                rowLedger.reconcile(merged)
                                rowLedger.reconcileAndVerifyTerminalText(merged)
                                flatItems = rowLedger.snapshot()
                            }
                        }
                    } finally {
                        // Effect cancelled (turn-end drain emit / session
                        // switch / screen dispose) — flush the open turn.
                        if (streamWasActive) {
                            com.openminis.app.diagnostics.StreamPerfMonitor.turnEnd()
                        }
                    }
                }
                // T304: when a new tool-use item appears at the trailing
                // edge (head of flatItems with reverseLayout=true), pin
                // back to the bottom so the just-arrived tool card is
                // visible above the floating Computer overlay + composer.
                //
                // The streaming-content snapshotFlow (LE around L798) does
                // detect `m.toolBlocks.size` growth, but it fires on the
                // raw `messages` model — and the LazyColumn renders the
                // async-flattened `flatItems`. The scroll can run BEFORE
                // flatItems repopulates with the new tool item, so item 0
                // is still the previous trailing item; the new tool block
                // ends up appended below the visible viewport. Pinning
                // again keyed on `flatItems` head fixes the race without
                // disturbing T281/T282 (those still own user-send and
                // resume scroll). userScrolledAway is honoured so users
                // reading history aren't yanked back.
                // messageId → isCompactedHistory map. Used to fade entire
                // assistant-row clusters (header + text + tool pills) at
                // render time — mirrors iOS isCompactedHistory opacity(0.5).
                // The lookup uses the underlying message id stripped of any
                // dedupe suffix (`id#2`) added by buildFlatChatItems.
                // originalMessageId is defined in ChatScreenUtils.kt.
                val grayedMap = remember(messages) {
                    messages.associate { it.id to it.isCompactedHistory }
                }

                // ─── [P0-0] focus-a-message: scroll + transient highlight ───
                //
                // Shared primitive for "open this session AND land on this
                // message" (search results, bookmarks, translation, range
                // export). Three constraints came out of the source audit and
                // each one is load-bearing:
                //
                //   1. flatItems is published ASYNCHRONOUSLY (frozenRows +
                //      liveRows). Scrolling on first composition would search
                //      an empty list, get -1 and silently do nothing — the
                //      exact race the trailing-row pin above documents. So the
                //      effect keys on flatItems and waits for a non-empty list.
                //   2. The LazyColumn is reverseLayout=true and renders
                //      `flatItems.asReversed()`, so the index handed to
                //      scrollToItem must be computed in REVERSED space.
                //      Using the oldest-first index would jump to the mirror
                //      position at the other end of the conversation.
                //   3. buildFlatChatItems disambiguates duplicate ids with a
                //      `#n` suffix, so matching goes through
                //      originalMessageId() on both sides.
                //
                // The target is consumed once (cleared after the jump) so a
                // config change or back-from-terminal recomposition does not
                // re-yank a user who has since scrolled elsewhere.
                LaunchedEffect(pendingFocusId, flatItems) {
                    val target = pendingFocusId ?: return@LaunchedEffect
                    if (flatItems.isEmpty()) return@LaunchedEffect
                    // [forward-stable] Forward list — natural order, no
                    // reversed-space mirror math.
                    val idx = flatItems.indexOfFirst {
                        originalMessageId(it.owningMessageId()) == originalMessageId(target)
                    }
                    if (idx < 0) {
                        // Message not in this session (deleted, or a stale
                        // link). Consume the request so we don't re-scan on
                        // every subsequent flatten publish, and leave the list
                        // at its default anchor rather than guessing.
                        AppLogger.debug(
                            "ChatFocus",
                            "focus target not found session=$sessionId target=$target rows=${flatItems.size}",
                        )
                        pendingFocusId = null
                        return@LaunchedEffect
                    }
                    tracedScrollToItem("FOCUS-MESSAGE", idx, 0)
                    highlightedMessageId = originalMessageId(target)
                    // Clearing pendingFocusId cancels THIS coroutine (it is a
                    // key of the enclosing LaunchedEffect), so the highlight
                    // fade-out must not live here — it gets its own effect
                    // below keyed on highlightedMessageId.
                    pendingFocusId = null
                }
                // [P0-0] Auto-expire the highlight. Separate from the jump
                // effect on purpose: see the note above about the jump's
                // coroutine being cancelled the moment it consumes the target.
                LaunchedEffect(highlightedMessageId) {
                    if (highlightedMessageId == null) return@LaunchedEffect
                    // Long enough for the eye to catch the row after the jump
                    // settles, short enough that it doesn't read as permanent
                    // selection state.
                    kotlinx.coroutines.delay(1_600L)
                    highlightedMessageId = null
                }
                fun FlatChatItem.isCompacted(): Boolean = isCompactedItem(this, grayedMap)
                // SelectionContainer must wrap the WHOLE LazyColumn — placing
                // it per-item breaks long-press because items get disposed
                // when scrolled out and the selection registrar/detector goes
                // with them. One outer SelectionContainer registers each Text
                // child as it enters composition, and the long-press gesture
                // detector lives at this stable scope. Mirrors Compose's
                // recommended LazyColumn + selection pattern.
                //
                // The custom LocalTextToolbar replaces the system Copy bar
                // with a 3-button popup (Copy / Copy Markdown / Copy Rich
                // Text); the latter two read from the bounds registry which
                // each AssistantMessageView updates via onGloballyPositioned.
                val messageBounds = remember { MessageBoundsRegistry() }
                // [T-selection-add-to-input] Toolbar's "Add to Chat Input"
                // action funnels the selected substring back into the
                // composer via the same StateFlow that the TextField is
                // bound to. Capture `viewModel` by reference so the
                // toolbar instance survives recomposition without
                // re-creation.
                // [T-add-to-input-focus] After append, request focus on the
                // composer + pop the soft keyboard so the user can keep
                // typing without an extra tap. Keyboard `show()` is best-effort
                // (controller may be null pre-attach); focus is guarded against
                // FocusRequester-not-attached the same way the auto-focus path
                // elsewhere in this file is.
                // MinisTextKit selection controller — declared BEFORE the
                // markdown toolbar so the toolbar can read table actions off it
                // ([T-android-markdown-table-copy-actions]). Hoisted ABOVE the
                // LazyColumn so item dispose can't kill the selection: when a
                // shard scrolls out of viewport it deregisters its TextShard,
                // but the (messageId, shardId, charOffset) endpoints stay valid;
                // scrolling back in re-registers the shard and the highlight
                // redraws automatically.
                val selectionController = remember { SelectionController() }
                val markdownToolbar = remember(context, messageBounds, viewModel, inputFocusRequester, keyboardController, selectionController) {
                    MinisMarkdownTextToolbar(
                        context = context,
                        registry = messageBounds,
                        onAddToInput = { snippet ->
                            viewModel.appendToInputText(snippet)
                            try {
                                inputFocusRequester.requestFocus()
                            } catch (_: IllegalStateException) {
                                // FocusRequester not yet attached — composer
                                // will gain focus on next user tap.
                            }
                            keyboardController?.show()
                        },
                        selectionController = selectionController,
                    )
                }
                // Wrap any callback that truncates / replaces / removes rows from
                // the message list. Hiding the toolbar + clearing focus tears down
                // the SelectionManager's pending toolbar update before the
                // SelectionContainer subtree gets reshuffled — without this,
                // notifySelectionUpdateEnd → updateSelectionToolbar → getContentRect
                // → sort hits stale LayoutCoordinates and crashes with
                // "layouts are not part of the same hierarchy".
                val safeMutate: (() -> Unit) -> Unit = { block ->
                    markdownToolbar.hide()
                    focusManager.clearFocus()
                    block()
                }
                // (selectionController declared above, before markdownToolbar.)
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalMessageBoundsRegistry provides messageBounds,
                    androidx.compose.ui.platform.LocalTextToolbar provides markdownToolbar,
                    LocalMinisSelectionController provides selectionController,
                ) {
                // Hoisted out of AlwaysStretchOverscrollBox lambda so
                // SelectionDragTracker (which lives outside the lambda) can
                // read the LazyColumn's window-space root coords for edge
                // auto-scroll calculations.
                var listRootCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
                // [Perf][LongCtx] T-android-long-ctx-reentry-perf:
                // fires once per session when the LazyColumn first reports
                // a layout. Combined with `buildFlatChatItems.firstBuild`
                // (above) and `lazyColumn.firstItem.placed` (below) this
                // tells us whether the bottleneck is row-list build,
                // initial list measure, or per-row composition.
                val perfFirstLayoutFired = remember(sessionId) { java.util.concurrent.atomic.AtomicBoolean(false) }
                // [bottom-trigger] Gesture edge-trigger REMOVED — it proved
                // unreliable on device. Follow is engaged only by explicit
                // intent: the down-arrow FAB below, send-when-anchored, and
                // auto-push while already sticky. (See AlwaysStretchOverscroll.kt.)
                Box {
                AlwaysStretchOverscrollBox { sharedEffect ->
                // [forward-stable] Forward (non-reverse) list: content
                // grows at the BOTTOM, so the viewport anchor naturally stays
                // put during streaming — no reverseLayout index-0 insertion
                // churn, no need for an anchor-guard compensation loop.
                LazyColumn(
                    state = listState,
                    reverseLayout = false,
                    // T30: when no tool status bar is rendered, a small bottom
                    // padding keeps the latest message off the composer's
                    // top edge so the conversation breathes. Reuses the same
                    // bottomReserve when the toolbar is present.
                    // Tuned so the visible gap to the composer's outer edge is ~18dp.
                    //
                    // [T-android-chat-first-message-top-padding] top reduced
                    // 12dp → 4dp. Under reverseLayout this top padding sits at
                    // the VISUAL top, so the first message's gap below the model
                    // title bar was top(12) + the first bubble's own top(4) =
                    // 16dp (≈44px @ 440dpi) — looser than needed. 4dp here +
                    // the bubble's 4dp = 8dp (≈22px), tighter but still a clear
                    // breath under the title bar. Bottom padding and inter-
                    // message spacing are untouched.
                    contentPadding = PaddingValues(
                        top = 4.dp,
                        bottom = if (bottomReserve == 0.dp) 12.dp else bottomReserve,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .onGloballyPositioned {
                            listRootCoords = it
                            if (perfFirstLayoutFired.compareAndSet(false, true)) {
                                val info = listState.layoutInfo
                                com.openminis.app.diagnostics.PerfLongCtx.step(
                                    sessionId,
                                    "lazyColumn.firstLayout",
                                    "totalItems=${info.totalItemsCount} visibleItems=${info.visibleItemsInfo.size} viewport=${info.viewportSize.width}x${info.viewportSize.height}",
                                )
                            }
                        }
                        .minisTextKitSelectionGesture(
                            controller = selectionController,
                            listState = listState,
                            rootCoordinates = { listRootCoords },
                            // [forward-stable] Forward list: dragging
                            // toward the bottom edge reveals NEWER messages
                            // (higher index) — no sign flip needed.
                            reverseLayout = false,
                        )
                        // T29 dismiss-on-tap spy. Only active while the slash
                        // popup is showing. awaitFirstDown(requireUnconsumed=false,
                        // pass=Initial) lets us see the tap *before* any child
                        // gesture (LazyColumn scroll, message long-press) without
                        // consuming it — the gesture continues to its real
                        // handler. We close the menu on the very first finger
                        // down anywhere inside the chat list, exactly like
                        // tapping outside an iOS popover.
                        .pointerInput(slashMenuOpen, mentionMenuOpenForSpy) {
                            if (!slashMenuOpen && !mentionMenuOpenForSpy) return@pointerInput
                            awaitEachGesture {
                                awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                                )
                                if (slashMenuOpen) {
                                    viewModel.setInputText(viewModel.dismissSlashMenu(viewModel.inputText.value))
                                }
                                if (mentionMenuOpenForSpy) {
                                    viewModel.dismissMentionMenu()
                                }
                            }
                        },
                    // [forward-stable] T303's reverseLayout-specific
                    // bottom-anchored arrangement is gone with the forward
                    // list — new rows land at the tail naturally.
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    overscrollEffect = sharedEffect,
                ) {
                    // T13 Resume banner — [forward-stable] placed AFTER
                    // items() so it renders at the visual BOTTOM of the list
                    // (just below the last assistant message); the bottom
                    // sentinel follows it. Mirrors iOS resumeBanner in
                    // CollectionViewMessageListV3.swift:360. Hidden while
                    // streaming or when an error banner is showing.
                    //
                    // T114: also hide when the last assistant message carries
                    // a message-level error — the inline Retry banner already
                    // covers that turn, and showing both at once is confusing
                    // (Resume on a turn that hit rate-limit would just retrace
                    // into the same failure).
                    if (hasOlderMessages) {
                        item(key = "__load_older_messages__", contentType = "load_older") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { viewModel.loadOlderMessages() }
                                    .padding(vertical = 8.dp, horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.chat_load_older_messages),
                                    color = ChatColors.secondaryText,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    items(
                        items = flatItems,
                        key = { it.key },
                        contentType = { it.contentType },
                    ) { item ->
                        // [Perf][LongCtx] T-android-long-ctx-reentry-perf:
                        // the newest message (index 0 in reverseLayout) is
                        // the first row painted in the viewport — its
                        // onPlaced is the moment the user actually sees
                        // content. SideEffect fires on first composition
                        // (before measure); onPlaced fires after layout.
                        if (item == flatItems.lastOrNull()) {
                            androidx.compose.runtime.SideEffect {
                                com.openminis.app.diagnostics.PerfLongCtx.step(
                                    sessionId,
                                    "lazyColumn.firstItem.compose",
                                )
                            }
                        }
                        // [Perf][LongCtx] aggregate compose-count tracker.
                        // Each row that enters composition during the reentry
                        // burst increments the per-session counter. When the
                        // 10th and 50th rows hit, emit one line each carrying
                        // the wall-time since `lazyColumn.firstLayout` plus
                        // the row's class — gives a "per-N-rows compose
                        // budget" signal without per-row log spam.
                        com.openminis.app.diagnostics.PerfLongCtx.maybeReportRowComposed(
                            sessionId,
                            item::class.java.simpleName,
                        )
                        // 0.4f matches iOS .opacity(0.5) closely once Compose's
                        // sRGB compositing is factored in. Renders below normal
                        // intensity but the message stays selectable + readable.
                        val rowAlpha = if (item.isCompacted()) 0.4f else 1f
                        // [T-HANG-DIAG] log on first composition of any item
                        // whose content is large enough to be a likely hang
                        // suspect. SideEffect runs after the first successful
                        // composition; if rendering stalls on the way to that
                        // SideEffect, we'll see the LAUNCH-RENDER line for it
                        // immediately followed by the watchdog's HANG dump
                        // and the missing FINISH-RENDER tells us this is the
                        // item that locked up the layout pass. Gated on size
                        // so normal turns don't spam the log.
                        val tHangDiagLen = remember(item.key) {
                            when (item) {
                                is FlatChatItem.UserBubble -> item.message.content.length
                                is FlatChatItem.AssistantText -> item.messageMarkdown.length
                                else -> 0
                            }
                        }
                        if (tHangDiagLen >= 50_000) {
                            androidx.compose.runtime.SideEffect {
                                println(
                                    "[T-HANG-DIAG] LAUNCH-RENDER key=${item.key} " +
                                        "type=${item::class.java.simpleName} len=$tHangDiagLen",
                                )
                            }
                            androidx.compose.runtime.DisposableEffect(item.key) {
                                onDispose {
                                    println("[T-HANG-DIAG] FINISH-RENDER key=${item.key} (composed → disposed)")
                                }
                            }
                        }
                        val isNewestItem = item == flatItems.lastOrNull()
                        // [P0-0] Transient focus highlight. Matching on the
                        // owning message id (not item.key) means every row of
                        // a multi-row message — header, text blocks, tool
                        // pills — lights up as one unit, which is what reads
                        // as "this message" to the user.
                        //
                        // Drawn as a background tint on the existing row Box
                        // rather than a border/scale so it cannot shift layout
                        // (a size change here would perturb the very scroll
                        // anchor we just positioned) and cannot interfere with
                        // text selection.
                        val isFocusHighlighted = highlightedMessageId != null &&
                            originalMessageId(item.owningMessageId()) == highlightedMessageId
                        val focusTint by animateColorAsState(
                            targetValue = if (isFocusHighlighted) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            } else {
                                Color.Transparent
                            },
                            animationSpec = tween(durationMillis = if (isFocusHighlighted) 180 else 520),
                            label = "focusHighlight",
                        )
                        Box(
                            modifier = Modifier
                                .alpha(rowAlpha)
                                .background(focusTint)
                                .then(
                                    if (isNewestItem) {
                                        Modifier.onPlaced {
                                            com.openminis.app.diagnostics.PerfLongCtx.step(
                                                sessionId,
                                                "lazyColumn.firstItem.placed",
                                                "size=${it.size.width}x${it.size.height}",
                                            )
                                            // [T-android-jank-diag-logging]
                                            // One quotable line per session
                                            // open, after the first frame's
                                            // newest row has laid out.
                                            if (!coldOpenSummaryEmitted) {
                                                coldOpenSummaryEmitted = true
                                                val totalChars = messages.sumOf { m -> m.content.length }
                                                val maxChars = messages.maxOfOrNull { m -> m.content.length } ?: 0
                                                AppLogger.info(
                                                    "JankDiag",
                                                    "[JankDiag] coldOpen summary session=$sessionId msgs=${messages.size} rows=${flatItems.size} " +
                                                        "totalChars=$totalChars maxChars=$maxChars prewarmMs=$lastColdPrewarmMs " +
                                                        "sinceMountMs=${System.currentTimeMillis() - screenMountAtMs} " +
                                                        "hangCount=${com.openminis.app.diagnostics.HangDetector.currentHangCount(context)}",
                                                )
                                                // [T-android-content-perf-diag] Per-large-message structural
                                                // fingerprint so a future hang report maps straight to "which
                                                // message, what structure" without re-querying the DB. Gated at
                                                // 5000 chars — small messages never drive a render hang.
                                                messages.forEachIndexed { idx, m ->
                                                    if (m.content.length >= com.openminis.app.diagnostics.CONTENT_DIAG_MIN_CHARS) {
                                                        val s = com.openminis.app.diagnostics.ContentDiag.summarize(m.content)
                                                        AppLogger.info(
                                                            "Perf",
                                                            "[Perf][ContentDiag] session=$sessionId msgIdx=$idx role=${m.role} " +
                                                                "streaming=${m.isStreaming} ${s.asLogFields()}",
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                        when (item) {
                            is FlatChatItem.UserBubble -> {
                                // User bubbles intentionally don't register
                                // MinisTextKit shards — long-press on a user
                                // bubble shows its own action menu (Copy /
                                // Retry / Edit) instead of starting text
                                // selection, matching iOS UX.
                                UserMessageBubble(
                                message = item.message,
                                // [T-android-candidate-bubble-gap] extra top
                                // gap when this bubble directly follows another
                                // user bubble (back-to-back candidate sends).
                                precededByUser = item.precededByUser,
                                onCopy = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("message", item.message.content))
                                },
                                // T119: pass null while a turn is in flight so
                                // the long-press menu hides Retry; once the
                                // stream stops (cancel or natural end) the
                                // option reappears. Gating execution alone
                                // wasn't enough — users still saw a tappable
                                // Retry that silently no-op'd.
                                onRetry = if (isStreaming) null else ({
                                    coroutineScope.launch {
                                        tracedScrollToItem("RETRY-FROM-MSG", (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0), 0)
                                    }
                                    safeMutate { viewModel.retryFromMessage(item.message.id) }
                                }),
                                // T187: long-press → Edit pulls the user message
                                // text into the composer; the next send truncates
                                // from this turn (inclusive) before persisting
                                // the edited content. Gated on isStreaming the
                                // same way Retry is.
                                onEdit = if (isStreaming || item.message.isQueued) null else ({
                                    val prefill = viewModel.editMessage(item.message.id)
                                    if (prefill != null) {
                                        // [fix/setinputtext-caret-intent] Editing a full message:
                                        // place the caret at the end so the user sees the tail.
                                        viewModel.setInputText(prefill, caretOverride = prefill.length)
                                        coroutineScope.launch {
                                            tracedScrollToItem("EDIT-MSG", (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0), 0)
                                        }
                                        inputFocusRequester.requestFocus()
                                    }
                                }),
                                onWithdraw = if (item.message.isQueued) {
                                    { safeMutate { viewModel.withdrawQueuedMessage(item.message.id) } }
                                } else null,
                                onPreviewFile = { uri, name ->
                                    // T150: turn the persisted file:// URI back
                                    // into a FileItem and hand off to the host
                                    // navigator (FilePreviewScreen). Mirrors
                                    // FileBrowser's onPreviewFile contract so
                                    // both entry points share one screen.
                                    val file = uri.path?.let { java.io.File(it) }
                                    if (file != null && file.exists()) {
                                        onPreviewAttachment(
                                            com.openminis.app.ui.sandbox.FileItem(
                                                file = file,
                                                name = name,
                                                isDirectory = false,
                                                isSymlink = false,
                                                size = file.length(),
                                                modifiedMs = file.lastModified(),
                                            )
                                        )
                                    }
                                },
                            )
                            } // close UserBubble SideEffect + UserMessageBubble block
                            is FlatChatItem.AssistantHeader -> AssistantHeader()
                            is FlatChatItem.AssistantText -> BoundsTrackedBlock(
                                messageId = item.messageId,
                                slotKey = "text:${item.block.id}",
                                markdown = item.messageMarkdown,
                            ) {
                                // T-android-gc-storm-issue17: collapse oversized frozen
                                // assistant text before feeding the markdown parser, which
                                // is the GC-storm hotspot for legacy sessions.
                                LargeContentGuard(
                                    content = item.block.content,
                                    isStreaming = item.isStreaming,
                                    stableKey = "text:${item.messageId}:${item.block.id}",
                                ) {
                                    SideEffect {
                                        selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                                    }
                                    StreamingMarkdownText(
                                        content = item.block.content,
                                        isStreaming = item.isStreaming,
                                        shardId = TextShardId(
                                            messageId = item.messageId,
                                            shardId = "text:${item.block.id}",
                                        ),
                                    )
                                }
                            }
                            is FlatChatItem.AssistantMarkdownBlock -> BoundsTrackedBlock(
                                messageId = item.messageId,
                                slotKey = "mdblock:${item.parentBlockId}:${item.blockIndex}",
                                markdown = item.messageMarkdown,
                            ) {
                                LargeContentGuard(
                                    content = item.rawText,
                                    isStreaming = item.isStreaming,
                                    stableKey = "mdblock:${item.messageId}:${item.parentBlockId}:${item.blockIndex}",
                                ) {
                                    SideEffect {
                                        selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                                    }
                                    MarkdownBlock(
                                        rawText = item.rawText,
                                        isStreaming = item.isStreaming,
                                        shardId = TextShardId(
                                            messageId = item.messageId,
                                            shardId = "mdblock:${item.parentBlockId}:${item.blockIndex}",
                                        ),
                                    )
                                }
                            }
                            is FlatChatItem.AssistantThinking -> {
                                // T300: hide Deep Thinking block when the user
                                // currently has thinking turned off — even if
                                // a forced-reasoning model (e.g. xAI Grok 4.x
                                // via OpenRouter) still streams reasoning_-
                                // content. Snapshot on the message wins so
                                // toggling the level after a turn finishes
                                // doesn't retro-hide an already-visible block;
                                // legacy DB-restored messages (snapshot=null)
                                // follow the chat's current level.
                                val effectiveLevel = item.messageThinkingLevel
                                    ?: viewModel.thinkingLevel.value
                                if (effectiveLevel.isEnabled) {
                                    // [T-android-thinking-auto-collapse] Use
                                    // `isLastBlockOverall` (not `isLast` =
                                    // last-thinking-only) so the block flips
                                    // to !isStreaming the moment a sibling
                                    // text/tool_use arrives — that's the
                                    // edge ThinkingBlock's LaunchedEffect
                                    // hooks for auto-collapse, matching iOS
                                    // ThinkingBlockView semantics.
                                    ThinkingBlock(
                                        block = item.block,
                                        isStreaming = item.isLastBlockOverall && item.messageIsStreaming,
                                        isLast = item.isLast,
                                    )
                                }
                            }
                            is FlatChatItem.AssistantToolRunGroup -> ToolCallRunGroup(
                                group = item,
                                onRetry = if (item.isLastCancelled && !isStreaming && !canResume) ({ safeMutate { viewModel.retryLast() } }) else null,
                                onStop = { viewModel.cancelStream() },
                                onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                                onOpenDetail = { viewModel.openToolDetail(it) },
                                onRerunFromHere = if (!isStreaming) ({
                                    val anchorId = item.tools.firstOrNull()?.id
                                    if (anchorId != null) {
                                        coroutineScope.launch {
                                            tracedScrollToItem("RERUN-FROM-TOOLRUN", (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0), 0)
                                        }
                                        safeMutate { viewModel.rerunFromToolBlock(item.messageId, anchorId) }
                                    }
                                }) else null,
                                // T288: same long-press copy menu as single pills —
                                // copies the whole group's tool list summary.
                                onCopyDetails = if (!isStreaming) ({
                                    val text = item.tools.joinToString("\n") { b ->
                                        "${b.toolTitle.ifEmpty { b.toolName }} - ${b.toolStatus}"
                                    }
                                    val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cb.setPrimaryClip(android.content.ClipData.newPlainText("toolrun", text))
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.tool_longpress_copied_toast),
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }) else null,
                            )
                            is FlatChatItem.AssistantToolUse -> ToolCallPill(
                                block = item.block,
                                allToolBlocks = item.allToolBlocks,
                                onRetry = if (item.isLastCancelled && !isStreaming && !canResume) ({ safeMutate { viewModel.retryLast() } }) else null,
                                // T14: route per-card stop to the global
                                // cancelStream(). The button only renders
                                // when the block is RUNNING/STREAMING — see
                                // ToolCallPill `isRunning && onStop != null`
                                // — so passing it unconditionally is safe.
                                onStop = { viewModel.cancelStream() },
                                onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                                // T261: route detail open through ViewModel so
                                // the sheet is hoisted out of LazyColumn item
                                // scope (otherwise the sheet snaps shut when
                                // the pill scrolls off-screen and Compose
                                // disposes the item).
                                onOpenDetail = { viewModel.openToolDetail(it) },
                                // [T-android-rerun-from-tool-block-position]
                                // Re-run cuts at THIS tool_use block: keep the
                                // blocks before it in the same turn, drop it +
                                // everything after, then regenerate. The block
                                // id (== tool_use id for a tool_use block) is
                                // the stable anchor. Gated off while streaming
                                // (mutating an in-flight turn corrupts agent
                                // state, same rule as Retry on the user bubble).
                                // safeMutate tears down the selection toolbar
                                // before the truncation reshuffles the list.
                                onRerunFromHere = if (!isStreaming) ({
                                    coroutineScope.launch {
                                        tracedScrollToItem("RERUN-FROM-TOOL", (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0), 0)
                                    }
                                    safeMutate { viewModel.rerunFromToolBlock(item.messageId, item.block.id) }
                                }) else null,
                                onCopyDetails = {
                                    val text = formatToolDetailsForClipboard(item.block)
                                    val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cb.setPrimaryClip(android.content.ClipData.newPlainText("tool", text))
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.tool_longpress_copied_toast),
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                            is FlatChatItem.AssistantInfo -> FallbackInfoBlock(
                                block = item.block,
                                // Only the compact-divider info block should
                                // surface a "Revert Compact" button on its
                                // detail sheet — other info rows (slash
                                // notices, fallback notices) have nothing
                                // to revert.
                                onRevert = if (item.block.toolName == "compact") {
                                    { viewModel.revertCompact() }
                                } else null,
                            )
                            is FlatChatItem.AssistantTyping -> TypingIndicator()
                            is FlatChatItem.AssistantError -> InlineErrorBanner(
                                error = item.error,
                                errorDetail = item.errorDetail,
                                onRetry = {
                                    coroutineScope.launch { tracedScrollToItem("INLINE-RETRY-LAST", (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0), 0) }
                                    safeMutate { viewModel.retryLast() }
                                },
                            )
                            is FlatChatItem.AssistantLegacyContent -> BoundsTrackedBlock(
                                messageId = item.messageId,
                                slotKey = "legacy",
                                markdown = item.messageMarkdown,
                            ) {
                                LargeContentGuard(
                                    content = item.content,
                                    isStreaming = item.isStreaming,
                                    stableKey = "legacy:${item.messageId}",
                                ) {
                                    SideEffect {
                                        selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                                    }
                                    StreamingMarkdownText(
                                        content = item.content,
                                        isStreaming = item.isStreaming,
                                        shardId = TextShardId(
                                            messageId = item.messageId,
                                            shardId = "legacy",
                                        ),
                                    )
                                }
                            }
                            is FlatChatItem.AssistantMessageItem -> {
                                // [fix/message-node-item-renderer] Stage D —
                                // aggregate message row, now the MAIN path
                                // (AGGREGATE_MESSAGE_ITEMS=true). A whole
                                // assistant message is ONE LazyColumn item,
                                // rendered by the reused AssistantMessageView
                                // (thinking / text / tool_use in original
                                // stream order). Per-tool pill actions mirror
                                // the flat AssistantToolUse branch's behavior:
                                // stop routes to cancelStream, detail hoists to
                                // the ViewModel sheet, rerun-from-here cuts at
                                // THIS tool_use block. Toolcalls of one turn all
                                // live inside this single row, so each pill gets
                                // its own actions keyed off the current block.
                                BoundsTrackedBlock(
                                    messageId = item.messageId,
                                    slotKey = "msg",
                                    markdown = item.messageMarkdown,
                                ) {
                                    SideEffect {
                                        selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                                    }
                                    AssistantMessageView(
                                        message = item.message,
                                        onRetry = { safeMutate { viewModel.retryLast() } },
                                        // "Revert Compact" only surfaces on the
                                        // compact-divider info row (mirrors the
                                        // flat AssistantInfo branch).
                                        onRevert = if (item.message.toolBlocks.any { it.kind == "info" && it.toolName == "compact" }) {
                                            { viewModel.revertCompact() }
                                        } else null,
                                        toolPillActions = { block ->
                                            if (block.kind != "tool_use") {
                                                null
                                            } else {
                                                val isCancelled = block.toolStatus == ToolBlockStatus.CANCELLED
                                                ToolPillActions(
                                                    // re-run only surfaces on a cancelled trailing tool (mirrors
                                                    // flat `isLastCancelled && !isStreaming && !canResume` — here
                                                    // derived per-block from this message's own tool status).
                                                    onRetry = if (isCancelled && !isStreaming && !canResume) {
                                                        { safeMutate { viewModel.retryLast() } }
                                                    } else null,
                                                    onStop = { viewModel.cancelStream() },
                                                    onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                                                    onOpenDetail = { viewModel.openToolDetail(it) },
                                                    onRerunFromHere = if (!isStreaming) ({
                                                        val messageId = item.messageId
                                                        val blockId = block.id
                                                        coroutineScope.launch {
                                                            tracedScrollToItem("RERUN-FROM-AGG-TOOL", (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0), 0)
                                                        }
                                                        safeMutate { viewModel.rerunFromToolBlock(messageId, blockId) }
                                                    }) else null,
                                                    onCopyDetails = {
                                                        val text = formatToolDetailsForClipboard(block)
                                                        val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                        cb.setPrimaryClip(android.content.ClipData.newPlainText("tool", text))
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            context.getString(R.string.tool_longpress_copied_toast),
                                                            android.widget.Toast.LENGTH_SHORT,
                                                        ).show()
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        } // Box (alpha wrapper)
                    }
                    val lastAssistantHasError = messages
                        .lastOrNull { it.role == "assistant" }
                        ?.error
                        ?.isNotBlank() == true
                    if (canResume && !isStreaming && error == null && !lastAssistantHasError) {
                        item(key = "__resume_banner__", contentType = "resume_banner") {
                            ResumeBanner(onResume = {
                                viewModel.resume()
                                // T282: same dual-scroll trick as the regular
                                // send paths (T281). Resume kicks off a fresh
                                // stream, so the "Minis is thinking" indicator
                                // mounts a frame or two later — pin once now,
                                // then again after 100ms so the indicator
                                // doesn't land below the fold.
                                // [bottom-trigger] Resume = user wants to see
                                // the continuation → restore follow.
                                // [forward-stable] One pending bottom request;
                                // the "Minis is thinking" indicator mounts via
                                // the next StreamRowsChanged revision.
                                followState = followReducer(followState, FollowEvent.Resume)
                            })
                        }
                    }
                    // [forward-stable] Bottom sentinel — the single target of
                    // every "scroll to bottom" request and the authoritative
                    // "at bottom" signal (its presence in the viewport means
                    // the user reached the very end of the transcript).
                    item(key = ScrollBottomKey, contentType = "scroll_bottom") {
                        Spacer(Modifier.height(5.dp))
                    }
                }
                } // AlwaysStretchOverscrollBox
                // SelectionDragTracker bridges gesture-published dragIntent
                // with listState scroll observation — that's what keeps the
                // selection extending across newly-scrolled-in shards when
                // the user's finger is stationary in the edge auto-scroll
                // zone (the inline pointer loop can't see those because
                // it only fires on pointer events).
                SelectionDragTracker(
                    controller = selectionController,
                    listState = listState,
                    listRootCoordinates = { listRootCoords },
                    reverseLayout = false,
                )
                MinisMarkdownTextToolbarHost(markdownToolbar)
                // MinisTextKit floating toolbar — driven by selectionController.
                MinisSelectionToolbarHost(
                    controller = selectionController,
                    // Clamp the menu's vertical position inside the
                    // LazyColumn's viewport in window coords, so it can't
                    // float above the chat header or below the composer /
                    // navigation bar. Computed lazily so the menu picks up
                    // re-layout (rotation, IME show/hide, etc.) without us
                    // having to recompose this composable.
                    contentViewportBounds = {
                        val coords = listRootCoords
                        if (coords != null && coords.isAttached) {
                            val origin = coords.positionInWindow()
                            androidx.compose.ui.geometry.Rect(
                                left = origin.x,
                                top = origin.y,
                                right = origin.x + coords.size.width,
                                bottom = origin.y + coords.size.height,
                            )
                        } else null
                    },
                    actions = SelectionToolbarActions(
                        // Resolve the parent message's joined markdown via
                        // the bounds registry — only when the selection sits
                        // within a single message (cross-message selections
                        // return null and the markdown / rich-text buttons
                        // are hidden).
                        resolveSelectionMarkdown = {
                            // Use the controller's own cached
                            // message-markdown — survives both endpoint
                            // shards scrolling off-screen, unlike the rect-
                            // based MessageBoundsRegistry lookup whose
                            // entries are removed on shard dispose.
                            selectionController.selectionMessageMarkdown()
                        },
                        onAddToInput = { snippet ->
                            viewModel.appendToInputText(snippet)
                            try { inputFocusRequester.requestFocus() } catch (_: IllegalStateException) {}
                            keyboardController?.show()
                        },
                    ),
                )
                // iOS-style selection handle dots, one at each endpoint.
                MinisSelectionHandlesHost(
                    controller = selectionController,
                    listState = listState,
                    reverseLayout = false,
                )
                } // Box (selection scope)
                } // CompositionLocalProvider

                // Floating tool status bar — shows only actual tool calls (not text/thinking/info).
                // Matches iOS: filter on toolStatus != nil (text blocks have toolStatus = null).
                //
                // T-streaming-side-channel-tool-blocks: derive lastToolBlocks
                // from a state that combines messages + streamingById INSIDE
                // a LaunchedEffect (not via a top-level collectAsState read),
                // so streaming-tick churn stays off the ChatScreen invalidation
                // list. Without including streamingById, a tool pill clicked
                // mid-turn is missing from lastToolBlocks → ToolDetailSheet
                // never opens (and its sentinel LaunchedEffect immediately
                // closes the detail state because the id "doesn't exist").
                var lastToolBlocks by remember { mutableStateOf<List<AssistantBlock>>(emptyList()) }
                LaunchedEffect(messages) {
                    kotlinx.coroutines.flow.combine(
                        kotlinx.coroutines.flow.flowOf(messages),
                        viewModel.streamingById,
                    ) { msgs, stream ->
                        val merged = if (stream.isEmpty()) msgs else mergeStreamingOverlay(msgs, stream, viewModel.currentStreamEpoch())
                        merged.filter { it.role == "assistant" }
                            .flatMap { it.toolBlocks }
                            .filter { it.toolStatus != null && it.kind != "thinking" && it.kind != "info" }
                    }.collect { lastToolBlocks = it }
                }
                val allToolBlocks = lastToolBlocks
                // Whole-bar visibility gate: when the user disables the
                // floating tool status bar in Appearance, skip both the render
                // AND the height reserve (otherwise an empty gap lingers above
                // the composer). Reserve and bar must flip on the same frame for
                // the T174 reserve logic to stay consistent.
                if (lastToolBlocks.isNotEmpty() && toolStatusBarEnabled) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .onGloballyPositioned { toolBarHeightPx = it.size.height }
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 6.dp),
                    ) {
                        FloatingToolStatusBar(
                            toolBlocks = lastToolBlocks,
                            // T14: per-card stop on the floating bar — same
                            // global cancel as the message-list pill button.
                            onStop = { viewModel.cancelStream() },
                            onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                            // T261: route detail open through the same VM
                            // state as in-list pills so both surfaces share
                            // one always-mounted sheet instance.
                            onOpenDetail = { viewModel.openToolDetail(it) },
                        )
                    }
                } else {
                    SideEffect { toolBarHeightPx = 0 }
                }

                // T261: tool-detail sheet hoisted out of LazyColumn item
                // scope. Visibility driven by ViewModel state so streaming /
                // pill-disposal / new-tool emissions can't snap it shut.
                // existence guard auto-closes the sheet when the underlying
                // block disappears (T258 retry-preserve removes in-flight
                // tools, clearChat, etc.). Reuses lastToolBlocks (already
                // computed above) so we don't traverse messages twice.
                val selectedToolDetailId by viewModel.selectedToolDetailId.collectAsState()
                LaunchedEffect(selectedToolDetailId, lastToolBlocks) {
                    val id = selectedToolDetailId ?: return@LaunchedEffect
                    if (lastToolBlocks.none { it.id == id }) viewModel.closeToolDetail()
                }
                val selectedToolBlock = selectedToolDetailId?.let { id ->
                    lastToolBlocks.firstOrNull { it.id == id }
                }
                if (selectedToolBlock != null) {
                    val initialIdx = lastToolBlocks
                        .indexOfFirst { it.id == selectedToolBlock.id }
                        .coerceAtLeast(0)
                    ToolDetailSheet(
                        toolBlocks = lastToolBlocks,
                        initialIndex = initialIdx,
                        onDismiss = { viewModel.closeToolDetail() },
                        onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                        onOpenBrowserForUrl = { url ->
                            viewModel.closeToolDetail()
                            viewModel.openBrowserSheetForUrl(url)
                        },
                    )
                }

                // Scroll-to-bottom FAB (iOS: circle chevron.down, bottom-right)
                // T138 phase 2 v3: show on user-scroll intent, not transient
                // layout state. Otherwise the FAB flickers whenever multi-tool
                // emissions briefly bump the bottom item off-screen during
                // re-anchoring.
                //
                // T170: gate also on `contentOverflows` so short sessions
                // (one Q+A on a tall screen) never flash the FAB if an IME
                // animation produces a synthetic drag-stop. iOS gets this
                // for free via `maxOffset > 0`; Compose needs the explicit
                // check.
                // [T-android-scroll-to-first-message] Floating "scroll to first
                // message" up-button. Mirrors iOS: shown ONLY in the middle
                // region (isFarFromTop && isFarFromBottom — more than one screen
                // from both ends) so it never lingers at the bottom or on short
                // chats. Sits ABOVE the scroll-to-bottom button (same BottomEnd
                // anchor, extra bottom padding = down-button height 36dp + 10dp
                // spacing). Scrolls to the OLDEST message (highest index under
                // reverseLayout).
                if (messages.isNotEmpty() && isFarFromTop.value && isFarFromBottom.value) {
                    val upBaseBottom = if (lastToolBlocks.isNotEmpty()) 80.dp else 8.dp
                    androidx.compose.material3.FilledIconButton(
                        onClick = {
                            coroutineScope.launch {
                                // [forward-stable] Forward list: oldest message
                                // is index 0 (was totalItemsCount-1 under
                                // reverseLayout).
                                tracedScrollToItem("FAB-UP", 0, 0)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = upBaseBottom + 46.dp)
                            .shadow(4.dp, CircleShape)
                            .size(36.dp),
                        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                            containerColor = ChatColors.inputBg,
                            contentColor = ChatColors.primaryText,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "Scroll to first message",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // [bottom-trigger] Down-FAB shows when follow is NOT engaged
                // (stickToBottom==false) — i.e. the user scrolled away to read
                // history and the "return to newest" button should be available
                // as the explicit resume-follow escape hatch.
                if (!followState.isFollowing && contentOverflows.value && messages.isNotEmpty()) {
                    val fabBottomPadding = if (lastToolBlocks.isNotEmpty()) 80.dp else 8.dp
                    androidx.compose.material3.FilledIconButton(
                        onClick = {
                            // [T-android-scroll-fab-down-stuck] Re-engage follow
                            // SYNCHRONOUSLY on tap. Don't rely on a detection
                            // pass: on a long reverseLayout session scrollToItem
                            // can settle on a non-zero firstVisibleItemIndex
                            // while unmeasured items resolve, so a position-based
                            // re-engage may either misfire or fail. The user
                            // tapped "go to bottom" — the intent is unambiguous,
                            // so re-engage stickToBottom directly.
                            // [bottom-trigger] Tapping the JumpToBottom FAB is
                            // the explicit "return to newest / resume follow"
                            // gesture. [forward-stable] Exactly one pending
                            // bottom request — no settle second call.
                            followState = followReducer(followState, FollowEvent.FabDown)
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = fabBottomPadding)
                            .shadow(4.dp, CircleShape)
                            .size(36.dp),
                        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                            containerColor = ChatColors.inputBg,
                            contentColor = ChatColors.primaryText,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Scroll to bottom",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // T51 / T185: the "Move to…" capsule was previously rendered
                // here, on top of the message list. After the user actually
                // sends the share-injected turn, the capsule was overlapping
                // the user-message bubble area and obscuring attachment chips.
                // Moved to the composer's top-right corner — see the Box
                // overlay around the input Column below, mirroring iOS
                // AIChatView.swift:1817 (.overlay(alignment: .topTrailing)).

                // T-chat-title-pill: sticky session title overlay. Sits
                // above the LazyColumn (top-center), animates in once the
            }

            // T-chat-title-pill-edit: reuse SessionEditSheet from the session
            // list (same composable, exposed `internal`) so title + category
            // edits from the in-chat pill are visually + behaviourally
            // identical to the home-screen long-press flow.
            editingSession?.let { session ->
                com.openminis.app.ui.sessions.SessionEditSheet(
                    session = session,
                    onDismiss = { editingSession = null },
                    onSave = { newTitle, newCategory ->
                        viewModel.updateTitleAndCategory(newTitle, newCategory)
                        editingSession = null
                    },
                )
            }

            // ─── Input area (composer + slash/mention menus + send) ───
            // [fix/input-lag] Extracted to ChatInputArea so the `inputText`
            // StateFlow subscription lives INSIDE the composer leaf instead of
            // at ChatScreen scope — typing no longer re-executes the whole
            // message-list body (the long-session input jank root cause).
            ChatInputArea(
                viewModel = viewModel,
                sessionId = sessionId,
                chatRepository = chatRepository,
                chatActions = chatActions,
                isStreaming = isStreaming,
                isNearBottom = isNearBottom,
                onFollowEvent = { followState = followReducer(followState, it) },
                onMoveToSession = onMoveToSession,
                onOpenModelPicker = { showModelPicker = true },
                onPreviewAttachment = onPreviewAttachment,
                onPreviewImageGallery = { items, idx -> previewImageGallery = items to idx },
                onOpenWebAppSheet = { target -> webAppSheetTarget = target },
                chatInputFontScale = chatInputFontScale,
                onPickMedia = { mediaPickerLauncher.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                    ),
                ) },
                onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
                onLaunchCamera = launchCamera,
                onLaunchCameraPermission = { cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) },
                keyboardController = keyboardController,
                focusManager = focusManager,
                coroutineScope = coroutineScope,
                inputFocusRequester = inputFocusRequester,
            )

            // [T-context-exhausted-dialog] iOS 'Context Full' alert parity:
            // the context reached its capacity and the user tried to send.
            // Offer a real way forward (new chat / clear chat) instead of a
            // silent drop. Cancel restores the stashed message to the input.
            if (showContextExhaustedDialog) {
                Dialog(
                    onDismissRequest = { viewModel.dismissContextExhaustedDialog(restoreInput = true) },
                    properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.context_full_dialog_title),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.context_full_dialog_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            // New Chat
                            TextButton(
                                onClick = {
                                    viewModel.dismissContextExhaustedDialog(restoreInput = false)
                                    onNewChat()
                                },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            ) { Text(stringResource(R.string.chat_menu_new_chat)) }
                            // Clear Chat (destructive, iOS parity)
                            TextButton(
                                onClick = {
                                    viewModel.dismissContextExhaustedDialog(restoreInput = false)
                                    viewModel.clearChat()
                                },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.chat_menu_clear_chat),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            // Cancel — restore the stashed message to the input
                            TextButton(
                                onClick = { viewModel.dismissContextExhaustedDialog(restoreInput = true) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            ) { Text(stringResource(R.string.common_cancel)) }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }

            // T137: Clear Chat confirmation. Wipes messages + agent history +
            // compact markers; the session row, workspace files, attachments,
            // and offload payloads are intentionally preserved (iOS parity).
            if (showClearChatDialog) {
                MinisAlertDialog(
                    onDismissRequest = { showClearChatDialog = false },
                    title = stringResource(R.string.chat_menu_clear_chat),
                    text = stringResource(R.string.chat_clear_dialog_body),
                    confirmText = stringResource(R.string.chat_clear_dialog_confirm),
                    isDestructive = true,
                    onConfirm = {
                        viewModel.clearChat()
                        viewModel.setInputText("")
                        showClearChatDialog = false
                    },
                )
            }
            // [T-android-enhanced-cache] One-time extra-billing confirmation
            // before the first enable. Accepting records the durable ack and
            // turns the toggle on; subsequent enables skip the dialog.
            if (showEnhancedCacheDialog) {
                MinisAlertDialog(
                    onDismissRequest = { showEnhancedCacheDialog = false },
                    title = stringResource(R.string.chat_menu_enhanced_cache),
                    text = stringResource(R.string.enhanced_cache_dialog_body),
                    confirmText = stringResource(R.string.enhanced_cache_dialog_confirm),
                    onConfirm = {
                        viewModel.confirmAndEnableEnhancedCache()
                        showEnhancedCacheDialog = false
                    },
                )
            }
        }
        // Top gradient fade: messages fade into the Scaffold background.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(6.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            ChatColors.background,
                            ChatColors.background.copy(alpha = 0f),
                        ),
                    )
                )
        )
        // [T-error-no-permanent-scars] Transient notices (model-fallback switch
        // "已切换至 xxx", image-budget events, top-level errors) render here —
        // a top-aligned Snackbar that auto-dismisses after a few seconds.
        // Unlike the old fallback info block (inserted into the message stream
        // and persisted), this leaves no permanent trace in the chat record.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .zIndex(10f),
        )
        }
    }
    }
    // [P0-0-drawer-fix] Always enabled BackHandler (registered AFTER
    // ModalNavigationDrawer so it outranks the drawer's predictive-back
    // handler). Priority order:
    //   1. history drawer open        -> close drawer, consume back
    //   2. slash/mention menu open    -> dismiss menu, consume back
    //   3. otherwise                  -> double-press-to-exit: first press
    //      shows a toast, second press within 2s backgrounds the task.
    //      The drawer is the ONLY history interface — back gesture must
    //      never land on the stock SESSION_LIST below ChatScreen on the
    //      nav stack. [P0-1-back-exit][P0-2-session-list-out]
    val lastBackPressTimeMs = remember { mutableStateOf(0L) }
    val doubleBackToast = stringResource(R.string.back_to_exit_press_again)
    androidx.activity.compose.BackHandler(enabled = true) {
        when {
            historyDrawerState.isOpen -> {
                historyDrawerScope.launch { historyDrawerState.close() }
            }
            slashMenuOpen || mentionMenuOpenForSpy -> {
                if (slashMenuOpen) {
                    viewModel.setInputText(viewModel.dismissSlashMenu(viewModel.inputText.value))
                }
                if (mentionMenuOpenForSpy) {
                    viewModel.dismissMentionMenu()
                }
            }
            else -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackPressTimeMs.value < 2000L) {
                    val activity = context as? android.app.Activity
                    if (activity != null) {
                        activity.moveTaskToBack(true)
                    } else {
                        onBack()
                    }
                } else {
                    lastBackPressTimeMs.value = now
                    Toast.makeText(context, doubleBackToast, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Browser bottom sheet
    if (showBrowserSheet) {
        BrowserSheet(
            tabPool = viewModel.browserTabPool,
            onDismiss = { viewModel.dismissBrowserSheet() },
        )
    }

    // Session Token Usage bottom sheet
    if (showTokenUsageSheet) {
        TokenUsageSheet(
            viewModel = viewModel,
            onDismiss = { showTokenUsageSheet = false },
        )
    }

    // Input history sheet — lists all messages in the current session so the
    // user can jump back to a specific prior input (or reply). Reuses the
    // same pendingFocusId + highlightedMessageId mechanism as session-switch
    // deep links, so selecting an entry scrolls to it and briefly highlights it.
    if (showInputHistorySheet) {
        InputHistorySheet(
            messages = messages,
            onSelect = { messageId ->
                showInputHistorySheet = false
                pendingFocusId = messageId
            },
            onDismiss = { showInputHistorySheet = false },
        )
    }

    // [bottom-toolbar-customizable] Export format picker — shared by the "..."
    // menu EXPORT entry and the history-drawer footer EXPORT action. The old
    // inline JSON/Plain submenu lived only in the menu; the sheet keeps the
    // format choice reachable from both entry points with one implementation.
    if (showExportFormatSheet) {
        ExportFormatSheet(
            onDismiss = { showExportFormatSheet = false },
            onExport = { format ->
                showExportFormatSheet = false
                exportCurrentChat(context, viewModel, chatRepository, coroutineScope, format)
            },
        )
    }

    // Memory bottom sheet
    if (showMemorySheet && memoryRepository != null) {
        SessionMemorySheet(
            memoryRepository = memoryRepository,
            toolRecords = memoryToolRecords,
            onDismiss = { viewModel.dismissMemorySheet() },
            onRevokeRecord = { record -> viewModel.revokeMemoryRecord(record) },
            onSaveRecord = { record, newContent -> viewModel.replaceMemoryRecord(record, newContent) },
        )
    }

    // Session Skills bottom sheet
    if (showSkillsSheet && skillRepository != null) {
        SessionSkillsSheet(
            skillRepository = skillRepository,
            sessionId = sessionId,
            onDismiss = { showSkillsSheet = false },
        )
    }

    // [T-mcp-integration-android] MCPs-in-Session sheet.
    if (showMcpsSheet && mcpRepository != null) {
        SessionMcpsSheet(
            mcpRepository = mcpRepository,
            sessionId = sessionId,
            onDismiss = { showMcpsSheet = false },
        )
    }

    // [T-android-thinking-badge-navbar] Thinking-level sheet opened by tapping
    // the navbar thinking badge. Mirrors iOS ThinkingLevelSheetView: an Off row
    // plus every level the current model supports, each selectable.
    if (showThinkingLevelSheet) {
        val currentThinkingLevel by viewModel.thinkingLevel.collectAsState()
        ThinkingLevelSheet(
            currentLevel = currentThinkingLevel,
            availableLevels = viewModel.availableThinkingLevels,
            onSelect = { level ->
                viewModel.setThinkingLevel(level)
                showThinkingLevelSheet = false
            },
            onDismiss = { showThinkingLevelSheet = false },
        )
    }

    // Model Picker bottom sheet
    if (showModelPicker) {
        val config by providerRepository.config.collectAsState()
        val activeEntryId by viewModel.activeEntryId.collectAsState()

        // When the user picks a model whose output is image/audio/video, defer
        // the actual binding behind a confirmation dialog — those models can't
        // drive an Agent loop, so we steer the user toward a text-output model
        // (or, if they really want it, hint at adding it as a tool inside an
        // Agent loop instead).
        var pendingNonTextSelection by remember {
            mutableStateOf<PendingNonTextSelection?>(null)
        }
        val resolveImageLabel = stringResource(R.string.model_picker_modality_image)
        val resolveAudioLabel = stringResource(R.string.model_picker_modality_audio)
        val resolveVideoLabel = stringResource(R.string.model_picker_modality_video)
        fun nonTextLabelFor(model: LLMModel): String? {
            val mods = model.outputModalities?.map { it.lowercase() } ?: emptyList()
            return when {
                "image" in mods -> resolveImageLabel
                "audio" in mods -> resolveAudioLabel
                "video" in mods -> resolveVideoLabel
                else -> null
            }
        }
        fun entryById(entryId: String): ModelEntry? =
            config.modelEntries.firstOrNull { it.id == entryId }

        ModelPickerSheet(
            groups = availableGroups,
            selectedGroupId = selectedGroupId,
            activeEntryId = activeEntryId,
            defaultPrimaryGroupId = config.defaultPrimaryGroupId,
            config = config,
            providerRepository = providerRepository,
            onSelectGroup = { groupId ->
                val group = availableGroups.firstOrNull { it.id == groupId }
                val firstEntry = group?.memberEntryIds?.firstNotNullOfOrNull(::entryById)
                val label = firstEntry?.model?.let(::nonTextLabelFor)
                if (label != null) {
                    pendingNonTextSelection = PendingNonTextSelection.Group(
                        groupId = groupId,
                        modelDisplayName = firstEntry.model.displayName,
                        modalityLabel = label,
                    )
                } else {
                    viewModel.selectGroup(groupId)
                    showModelPicker = false
                }
            },
            onSelectGroupEntry = { groupId, entryId ->
                val entry = entryById(entryId)
                val label = entry?.model?.let(::nonTextLabelFor)
                if (entry != null && label != null) {
                    pendingNonTextSelection = PendingNonTextSelection.GroupEntry(
                        groupId = groupId,
                        entryId = entryId,
                        modelDisplayName = entry.model.displayName,
                        modalityLabel = label,
                    )
                } else {
                    viewModel.selectGroupEntry(groupId, entryId)
                    showModelPicker = false
                }
            },
            onSelectEntry = { entryId ->
                val entry = entryById(entryId)
                val label = entry?.model?.let(::nonTextLabelFor)
                if (entry != null && label != null) {
                    pendingNonTextSelection = PendingNonTextSelection.Entry(
                        entryId = entryId,
                        modelDisplayName = entry.model.displayName,
                        modalityLabel = label,
                    )
                } else {
                    viewModel.selectEntry(entryId)
                    showModelPicker = false
                }
            },
            onDismiss = { showModelPicker = false },
            // [T-android-modelpicker-group-edit] Close the picker first, then
            // navigate — pushing the management screen on top of an open bottom
            // sheet leaves the sheet lingering behind it on back.
            onEditGroups = {
                showModelPicker = false
                onModelGroupsClick()
            },
        )

        pendingNonTextSelection?.let { pending ->
            MinisAlertDialog(
                onDismissRequest = { pendingNonTextSelection = null },
                title = stringResource(R.string.model_picker_non_text_warning_title),
                text = stringResource(
                    R.string.model_picker_non_text_warning_body,
                    pending.modelDisplayName,
                    pending.modalityLabel,
                    pending.modalityLabel,
                ),
                confirmText = stringResource(R.string.model_picker_non_text_warning_use_anyway),
                dismissText = stringResource(R.string.model_picker_non_text_warning_choose_other),
                onConfirm = {
                    when (val sel = pending) {
                        is PendingNonTextSelection.Group -> viewModel.selectGroup(sel.groupId)
                        is PendingNonTextSelection.GroupEntry ->
                            viewModel.selectGroupEntry(sel.groupId, sel.entryId)
                        is PendingNonTextSelection.Entry -> viewModel.selectEntry(sel.entryId)
                    }
                    pendingNonTextSelection = null
                    showModelPicker = false
                },
            )
        }
    }

    // Offload permission dialog
    OffloadPermissionDialog()

    // URL preview sheet — shown when a markdown link is tapped
    previewUrl?.let { url ->
        com.openminis.app.ui.components.UrlPreviewSheet(
            url = url,
            onDismiss = { previewUrl = null },
        )
    }

    // T146: immersive HTML preview — bottom sheet (90% height) by default,
    // with a Fullscreen button that swaps to a Dialog-based fullscreen
    // surface using the SAME WebViewHolder so the page never reloads.
    htmlPreviewHolder?.let { holder ->
        val onFullDismiss = {
            holder.destroy()
            htmlPreviewHolder = null
            htmlPreviewFallbackTitle = ""
            htmlPreviewFullscreen = false
        }
        if (htmlPreviewFullscreen) {
            com.openminis.app.ui.preview.WebPreviewFullscreenScreen(
                holder = holder,
                fallbackTitle = htmlPreviewFallbackTitle,
                onCollapseToSheet = {
                    holder.detach()
                    htmlPreviewFullscreen = false
                },
                onDismiss = onFullDismiss,
            )
        } else {
            com.openminis.app.ui.preview.WebPreviewBottomSheet(
                holder = holder,
                fallbackTitle = htmlPreviewFallbackTitle,
                pinSessionId = sessionId,
                onExpandFullscreen = {
                    holder.detach()
                    htmlPreviewFullscreen = true
                },
                onDismiss = onFullDismiss,
            )
        }
    }

    // T279: sandbox file preview is now routed through the NavHost
    // FILE_PREVIEW destination via onPreviewAttachment (see line ~1103),
    // matching how user-bubble attachments and "Browse Chat Files" already work.
    // The old in-place Dialog wrapper here was the source of the gray
    // status/nav bars — a Compose Dialog creates its own Window that
    // doesn't inherit MainActivity's enableEdgeToEdge, so the platform
    // default scrim painted over the bars regardless of what
    // FilePreviewScreen itself did.

    // Fullscreen image gallery — tapped image link from chat markdown or
    // composer chip. Pager-backed so multi-image messages support iOS-
    // style swipe between images. Single-image case is a 1-item list.
    previewImageGallery?.let { (items, startIdx) ->
        com.openminis.app.ui.components.ImageGalleryViewer(
            items = items,
            startIndex = startIdx,
            onDismiss = { previewImageGallery = null },
        )
    }

    // Fullscreen video player — tapped video link (mp4/mov/m4v/…) from chat
    // markdown. Reuses the same dialog player as the markdown-rendered
    // ![](minis://...) syntax so behaviour is consistent regardless of how
    // the LLM emitted the reference.
    previewVideoFile?.let { file ->
        com.openminis.app.ui.media.MinisFullscreenVideoPlayer(
            file = file,
            onDismiss = { previewVideoFile = null },
        )
    }

    // T-pwa-2: Add-to-Home-Screen sheet, hosted at screen level so it can
    // outlive the chip that triggered it (the chip Box may scroll out of
    // composition while the sheet is up).
    webAppSheetTarget?.let { target ->
        com.openminis.app.webapp.AddToHomeSheet(
            source = com.openminis.app.webapp.WebAppSource.ChatAttachment(
                uri = target.uri,
                fileName = target.fileName,
                sessionId = sessionId,
                sessionTitle = null,
            ),
            onDismiss = { webAppSheetTarget = null },
        )
    }
    } // CompositionLocalProvider
}

// [T-android-split-chat] UserMessageBubble / UserAttachmentList /
// FileAttachmentTile / fileIconFor / ImageGalleryDialog moved verbatim to
// ChatUserMessageUI.kt.
// [T-android-split-chat] FlatChatItem / mergeStreamingOverlay / buildFlatChatItems
// moved verbatim to ChatFlatItems.kt (now internal).

// [T-android-split-chat] AssistantHeader / AssistantMessageView /
// BoundsTrackedBlock / InlineErrorBanner / ToolStopButton /
// formatToolDetailsForClipboard / ToolCallPill / ThinkingBlock moved verbatim
// to ChatAssistantMessageUI.kt.

// ─── Tool Detail Bottom Sheet (iOS: ToolLiveSheet — nav bar + content + bottom bar) ──

// [T-android-split-chat] ToolDetailSheet + helpers (extractShellCommand,
// extractPartialJsonString, chunkToolOutput, initialRevealChunks,
// LazyRevealToolText, EditorCard) moved verbatim to ChatToolDetailUI.kt.
// [T-android-split-chat] AttachmentChip / InputCircleButton / MicButton /
// ToolPreviewThumbnail / FloatingToolStatusBar / ThinkingLevelPicker moved
// verbatim to ChatComposerWidgets.kt.

// [T-android-split-chat] createCameraOutputUri / getFileName /
// PendingNonTextSelection moved verbatim to ChatScreenHelpers.kt (now internal).


// [T-android-split-chat] fuzzyMatch / ModelPickerSheet / providerDotColor moved
// verbatim to ChatModelPickerSheet.kt (ModelPickerSheet now internal).

// [T-android-split-chat] BorderedMarkdownTable / FallbackInfoBlock /
// CompactSummarySheet / parseInlineMarkdown / rememberBrowserLiveSnapshot /
// ResumeBanner / SwipeToSendHint moved verbatim to ChatMiscViews.kt.
// Sun May 24 11:01:25 CST 2026

// [fix/message-node-item-renderer] Message-level aggregate pipeline switch.
// STAGE D FLIPPED TO TRUE — the aggregate path is now the MAIN path: the
// flatten collect emits one aggregated item per ChatMessage
// (buildAggregateChatItems) — no ledger, no segmenter. ChatScreen renders the
// resulting AssistantMessageItem via the reused
// ChatAssistantMessageUI.AssistantMessageView, with per-tool pill actions
// (stop/detail/rerun/copy/open-terminal) wired for parity with the old flat
// AssistantToolUse / AssistantToolRunGroup branches.
internal const val AGGREGATE_MESSAGE_ITEMS: Boolean = true

// [fix/scroll-follow-simplify] RikkaHub-style simple explicit follow.
// Stage E: with AGGREGATE_MESSAGE_ITEMS=true the flatten collect emits one
// item per ChatMessage (buildAggregateChatItems) and early-returns BEFORE the
// prevRowKeys prefix check and the followReducer(StreamRowsChanged) dispatch —
// so the fragment-churn the old guard stack was built to damp is GONE, and the
// old reducer's STREAM_PROGRESS revisions are effectively never raised from
// the data collector anyway. When SIMPLE_FOLLOW=true, streaming auto-follow is
// driven by a direct `isAtBottom() && isStreaming → requestScrollToItem(sentinel)`
// effect (rikkahub ChatList's `isAtBottom && loading` contract); the follow
// reducer/consumer remain, but only for EXPLICIT user intents (Send / FabDown /
// Resume / Retry / InitialOpen), which the effect does not replace.
//
// FLIPPED TO TRUE — the SIMPLE_FOLLOW effect is now the streaming auto-follow
// driver (see docs/scroll-follow-simplification.md).
internal const val SIMPLE_FOLLOW: Boolean = true

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
private fun ChatInputArea(
    viewModel: ChatViewModel,
    sessionId: String,
    chatRepository: com.openminis.app.data.repository.ChatRepository,
    chatActions: ChatActionState,
    isStreaming: Boolean,
    isNearBottom: androidx.compose.runtime.State<Boolean>,
    onFollowEvent: (FollowEvent) -> Unit,
    onMoveToSession: (String) -> Unit,
    onOpenModelPicker: () -> Unit,
    onPreviewAttachment: (com.openminis.app.ui.sandbox.FileItem) -> Unit,
    onPreviewImageGallery: (List<com.openminis.app.ui.components.ImageGalleryItem>, Int) -> Unit,
    onOpenWebAppSheet: (InputAttachment) -> Unit,
    chatInputFontScale: Float,
    onPickMedia: () -> Unit,
    onPickFile: () -> Unit,
    onLaunchCamera: () -> Unit,
    onLaunchCameraPermission: () -> Unit,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    focusManager: androidx.compose.ui.focus.FocusManager,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    inputFocusRequester: androidx.compose.ui.focus.FocusRequester,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val inputText by viewModel.inputText.collectAsState()
    val attachments by viewModel.attachments.collectAsState()
    var showMoveSheet by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    // Mirrors `inputText` for the BasicTextField but tracks selection so we
    // can position the cursor (e.g. AFTER the leading "/" when the slash
    // button inserts it) — a plain String overload would reset cursor to 0
    // on every external write.
    var inputFieldValue by remember {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
    }
    // [Txxx-android-composer-caret] Authoritative caret written on EVERY
    // onValueChange from the IME's own TextFieldValue.selection. The old
    // fallback read inputFieldValue.selection.end — but under the long-paste
    // race inputFieldValue holds the PRE-write text, so its selection.end was
    // a stale index that, coerced into the new text, landed at a random
    // mid-string position. This never contains the corruption: onValueChange
    // is the single writer of the true caret, and LaunchedEffect merely reads
    // this stable value back when no external pendingCaret intent exists.
    var lastTrueCaretEnd by remember { mutableStateOf(0) }
    // T217-2: suppress IME commits arriving briefly after send. clearFocus
    // triggers finishComposingText, which makes voice/Pinyin IMEs commit
    // their pending candidate back through onValueChange even after we
    // cleared inputText. Drop those late commits during a short window.
    var lastSendTimeMs by remember { mutableStateOf(0L) }
    // [fix/voice-crash-observability tail] IME burst debounce state. Buffers
    // the latest large-increment edit and the job that flushes it 150 ms
    // after the last burst, so voice dictation's N-event bursts collapse to
    // a single setInputText commit (see shouldDebounceImeBurst). Null buffer =
    // nothing pending; the job is cancelled+replaced on every new burst.
    var imeBurstBuffer by remember { mutableStateOf<String?>(null) }
    var imeBurstJob by remember { mutableStateOf<Job?>(null) }
    // Paired with imeBurstBuffer: the caret that must be scanned against the
    // FLUSHED text when a debounced burst finally commits. Stored alongside
    // the buffer (not read late from inputFieldValue) so the mention scan in
    // the flush sees the caret that belonged to that exact burst, not a
    // later-arrived one.
    var imeBurstCaret by remember { mutableStateOf(0) }
    // Commit the composer input-mode pref on send. Voice input was removed, so
    // every composition is now "text"; the old voiceUsedSinceClear tracker and
    // the VoiceCorrection vocabulary miner went with it.
    val noteSendForInputModePref: () -> Unit = {
        ComposerInputModePrefs.save(context, voice = false)
    }
    androidx.compose.runtime.LaunchedEffect(inputText) {
        if (inputFieldValue.text != inputText) {
            // [Txxx-android-composer-caret-jump] Honor a one-shot caret
            // override from the slash flow (prepend "/ " → caret 1; insert
            // "/<skill> " → caret after the prefix). Read-and-clear so it
            // applies exactly once.
            //
            // When there's NO pendingCaret (normal typing / paste / external
            // write that didn't tag a caret), PRESERVE the user's current caret
            // instead of forcing it to inputText.length. The old `?: inputText.length`
            // reset the selection to the END on every relaunch where the guard
            // above fired — and because this LaunchedEffect races the
            // BasicTextField.onValueChange writer to inputFieldValue, a long
            // paste could see a mid-frame text mismatch, trigger the reset, and
            // randomly yank the caret (often to the end) while the user was
            // editing the middle of a long message. The slash/mention/draft
            // intent paths all set pendingCaret, so this keeps their precise
            // positioning intact; only the unintended end-jump is removed.
            // Coerce into bounds both ways defensively.
            val caret = viewModel.consumePendingCaret()?.coerceIn(0, inputText.length)
                ?: lastTrueCaretEnd.coerceIn(0, inputText.length)
            inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                text = inputText,
                selection = androidx.compose.ui.text.TextRange(caret),
                // T217: explicitly drop any pending IME composing buffer so voice
                // recognition / Pinyin candidates don't get re-committed back into
                // the field after send (mirrors iOS unmarkText in AIChatView.swift
                // updateUIView L5638).
                composition = null,
            )
        }
    }
    // Mirror of iOS `inputFocused` — needed so the swipe-up-on-empty-input
    // gesture only pops the keyboard when it's actually collapsed.
    var inputFocused by remember { mutableStateOf(false) }

    // --- Swipe-up-to-send (parity with iOS AIChatView.swift) ---------------
    // Drag progress 0..1 as fraction of the trigger distance. Drives the
    // floating send-arrow hint + "Release to send" capsule overlay. Only
    // updated while the input has non-empty text.
    var sendSwipeProgress by remember { mutableStateOf(0f) }
    // Live fingertip position inside the input bar (px). Hint floats ~60dp
    // above this point so it isn't hidden under the user's thumb.
    var sendSwipeLocation by remember { mutableStateOf(Offset.Zero) }
    val swipeThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }
    // Match iOS: haptic + capsule full-opacity + release-fires-send all
    // engage at this fraction (below 1.0 so user gets earlier confirmation).
    val swipeArmFraction = 0.8f
    val swipeHapticOffsetPx = with(LocalDensity.current) { 60.dp.toPx() }
    val swipeArrowHalfPx = with(LocalDensity.current) { 17.dp.toPx() }
    val swipeHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current


    val performSendOrEnqueue: (String) -> Unit = handler@{ rawText ->
        if (viewModel.tryExecuteInputAsSlashCommand(rawText)) {
            viewModel.setInputText("")
            keyboardController?.hide()
            focusManager.clearFocus()
            return@handler
        }
        lastSendTimeMs = SystemClock.elapsedRealtime()
        // [P2-scroll-user-send] Capture the viewport anchor BEFORE sendMessage:
        // sending inserts the user row at index 0, which in reverseLayout
        // pushes firstVisibleItemIndex from 0 → 1, so reading isNearBottom
        // AFTER the insert would wrongly read "scrolled into history" for a
        // bottom-anchored sender. Snapshot first, then act.
        val wasScrolledIntoHistory = !isNearBottom.value
        viewModel.setInputText("")
        keyboardController?.hide()
        focusManager.clearFocus()
        viewModel.sendMessage(rawText)
        noteSendForInputModePref()
        // [P2-scroll-user-send] The send re-engages follow ONLY if the user was
        // already at the bottom (wasScrolledIntoHistory==false) — sending does
        // NOT turn a history reader into a follower. The FAB remains the way a
        // reader re-engages. [forward-stable] The reducer raises exactly ONE
        // pending bottom request (no initial+settle double scroll); a history
        // reader stays detached and is never yanked.
        if (!wasScrolledIntoHistory) {
            // [P2-scroll-user-send] Re-engage follow (a bottom-anchored sender
            // wants the new turn followed). SIMPLE_FOLLOW's dedicated effect
            // also covers this (streaming just started + sender at bottom), and
            // this reducer request is a harmless duplicate targeting the same
            // sentinel — kept for the explicit-intent path.
            onFollowEvent(FollowEvent.Send)
        } else {
            AppLogger.debug("USER-SEND", "reader-in-history, skip yank (firstIdx drift scene)")
        }
    }
    // [forward-stable] Anchor-guard REMOVED — the reverseLayout compensation
    // loop is gone with the forward list. Content grows at the tail; the
    // viewport anchor (first visible row key) never moves; there is no
    // (index0 → index1 → index0) transient to fight, so no drift watcher, no
    // deadzone, no 100ms throttle, no forced re-pin. Follow decisions live in
    // the drag-stop handler + explicit intents below (FollowController in
    // Commit D turns these into a single request protocol).

        // ─── Input area (iOS-style: rounded box with text + buttons below) ───
        // [composer-width-align-rikkahub] The input card's horizontal inset
        // is raised from 12dp to 16dp to line up its edges with the message
        // list's 16dp gutter — the list and the composer previously sat on
        // different rails, which read as a narrower input box than the chat.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 8.dp),
        ) {
            // T13 banner moved INSIDE the LazyColumn so it renders at the
            // visual end of the message list (mirrors iOS — see the
            // banner item before items() in the LazyColumn block above).

            // Slash-command menu (mirrors iOS slashCommandMenu) — rendered as
            // a Popup so it overlays content (tool status bar, chat list)
            // instead of pushing them up. Anchored above the composer via
            // PopupProperties so its bottom edge sits just above this Column.
            // Tap-outside dismisses via dismissOnClickOutside.
            val showSlashMenu by viewModel.showSlashMenu.collectAsState()
            val filteredSlashCommands = remember(
                showSlashMenu,
                viewModel.slashFilter.collectAsState().value,
                viewModel.memoryEnabled.collectAsState().value,
                viewModel.thinkingLevel.collectAsState().value,
            ) { viewModel.filteredSlashCommands() }

            if (showSlashMenu && filteredSlashCommands.isNotEmpty()) {
                val thinkingLevelState by viewModel.thinkingLevel.collectAsState()
                val thinkingSupported = viewModel.currentModelSupportsReasoning
                val memoryOnState by viewModel.memoryEnabled.collectAsState()
                androidx.compose.ui.window.Popup(
                    popupPositionProvider = remember {
                        object : androidx.compose.ui.window.PopupPositionProvider {
                            override fun calculatePosition(
                                anchorBounds: androidx.compose.ui.unit.IntRect,
                                windowSize: androidx.compose.ui.unit.IntSize,
                                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                popupContentSize: androidx.compose.ui.unit.IntSize,
                            ): androidx.compose.ui.unit.IntOffset {
                                // Anchor: top-edge of the composer column. Place
                                // the popup so its bottom sits 12dp above that edge.
                                // T301: bumped from 6dp — at 6dp the panel was
                                // visually glued to the composer; 12dp gives a
                                // clear breathing gap matching the iOS spacing.
                                val gap = 12
                                val x = ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2)
                                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                val y = (anchorBounds.top - popupContentSize.height - gap)
                                    .coerceAtLeast(0)
                                return androidx.compose.ui.unit.IntOffset(x, y)
                            }
                        }
                    },
                    onDismissRequest = {
                        viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                    },
                    properties = androidx.compose.ui.window.PopupProperties(
                        focusable = false,
                        dismissOnBackPress = true,
                        // dismissOnClickOutside=false: with focusable=false the popup
                        // never receives focus, so the system "click outside" detector
                        // can't tell a tap on the BasicTextField below from a tap on
                        // the chat list — flipping this off would dismiss the popup
                        // every time the IME caret was moved. Dismiss is driven from
                        // the chat list / topbar tap-spy below instead, which lets
                        // the input field keep focus while still closing the menu
                        // when the user clearly looks elsewhere.
                        dismissOnClickOutside = false,
                    ),
                ) {
                    // [T-slash-picker-fixed-height port from iOS 73f1b94a]
                    // Locked popup height = 4 rows × 46dp + 8dp = 192dp.
                    // Short lists show empty space below the last row;
                    // long lists scroll inside the same frame with a
                    // visible scroll indicator. Prevents installed
                    // Skills + built-ins from pushing the menu past
                    // the input bar / off the top of the screen.
                    val slashListState = androidx.compose.foundation.lazy.rememberLazyListState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            // T240: keep a thin visible border instead of the
                            // diffuse 8dp halo that bled out past the panel edge.
                            .shadow(elevation = 3.dp, shape = RoundedCornerShape(10.dp))
                            .background(ChatColors.inputBg, RoundedCornerShape(10.dp))
                            .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp)),
                    ) {
                        androidx.compose.foundation.lazy.LazyColumn(
                            state = slashListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(SLASH_PICKER_FIXED_HEIGHT)
                                .verticalScrollbar(slashListState),
                        ) {
                        itemsIndexed(filteredSlashCommands, key = { _, c -> c.id }) { index, cmd ->
                            // Section divider between builtins and
                            // installed Skills (mirrors iOS divider
                            // at the first skill row). Drawn as the
                            // top of the skill row, not between every
                            // row — keeps the menu visually grouped
                            // without splitting every command.
                            if (cmd.isSkill && index > 0 && !filteredSlashCommands[index - 1].isSkill) {
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = ChatColors.toolBorder,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                            val isThinking = cmd.id == "thinking"
                            val isThinkingActive = isThinking && thinkingLevelState.isEnabled && thinkingSupported
                            val titleColor = if (isThinkingActive) ChatColors.sendButton else ChatColors.primaryText
                            val subtitleColor = if (isThinking && !thinkingSupported) {
                                ChatColors.secondaryText
                            } else if (isThinkingActive) {
                                ChatColors.sendButton.copy(alpha = 0.7f)
                            } else ChatColors.secondaryText
                            val iconTint = if (isThinkingActive) ChatColors.sendButton else ChatColors.primaryText
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .let {
                                        if (!isThinking) {
                                            it.clickable {
                                                // [T-android-slash-menu-clears-input] Pass the
                                                // LIVE input so an action command keeps the
                                                // user's body text instead of wiping it.
                                                viewModel.setInputText(viewModel.executeSlashCommand(cmd, inputText))
                                                // For Skill rows, "/<name> "
                                                // is a typing aid — the user
                                                // still needs to type
                                                // arguments. Bring the IME
                                                // back up + grab focus so
                                                // they can keep typing
                                                // without an extra tap on
                                                // the composer.
                                                if (cmd.isSkill) {
                                                    try {
                                                        inputFocusRequester.requestFocus()
                                                    } catch (_: IllegalStateException) {
                                                        // FocusRequester not attached yet.
                                                    }
                                                    keyboardController?.show()
                                                }
                                            }
                                        } else if (thinkingSupported) {
                                            it.clickable {
                                                val newLevel = if (thinkingLevelState.isEnabled) ThinkingLevel.OFF else ThinkingLevel.MEDIUM
                                                viewModel.setThinkingLevel(newLevel)
                                            }
                                        } else it
                                    }
                                    // [T-android-slash-menu-density] Tighter
                                    // vertical padding (10→7) so slash rows
                                    // read as compact as iOS, not sparse.
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = cmd.icon,
                                    contentDescription = null,
                                    tint = iconTint,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "/${cmd.title.lowercase()}",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = titleColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    // Cap to one line + ellipsis (mirrors
                                    // iOS T-slash-picker-product-rules
                                    // 051896e2). Long Skill descriptions
                                    // would otherwise stretch the row,
                                    // breaking the locked 4-row band and
                                    // crowding the menu visually.
                                    Text(
                                        text = cmd.subtitle,
                                        fontSize = 11.sp,
                                        color = subtitleColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (cmd.id == "memory") {
                                    Icon(
                                        imageVector = if (memoryOnState) Icons.Default.CheckCircle else Icons.Default.Block,
                                        contentDescription = null,
                                        tint = if (memoryOnState) ChatColors.sendButton else ChatColors.secondaryText,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                if (isThinking && thinkingSupported) {
                                    ThinkingLevelPicker(
                                        current = thinkingLevelState,
                                        // [T-android-thinking-level-arch] Only
                                        // offer tiers the bound model supports.
                                        availableLevels = viewModel.availableThinkingLevels,
                                        onSelect = { level -> viewModel.setThinkingLevel(level) },
                                    )
                                }
                            }
                        }
                        }
                    }
                }
            }

            // T4: @ file-mention picker — same anchoring + tap-spy
            // contract as the slash popup (mutually exclusive in the VM,
            // so they never both render). Reuses Popup so the bar over
            // the composer is consistent and respects IME inset.
            val showMentionMenu by viewModel.showMentionMenu.collectAsState()
            val mentionEntries by viewModel.mentionEntries.collectAsState()
            val isMentionScanning by viewModel.isMentionScanning.collectAsState()
            val mentionSelectedIndex by viewModel.mentionSelectedIndex.collectAsState()
            if (showMentionMenu) {
                androidx.compose.ui.window.Popup(
                    popupPositionProvider = remember {
                        object : androidx.compose.ui.window.PopupPositionProvider {
                            override fun calculatePosition(
                                anchorBounds: androidx.compose.ui.unit.IntRect,
                                windowSize: androidx.compose.ui.unit.IntSize,
                                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                popupContentSize: androidx.compose.ui.unit.IntSize,
                            ): androidx.compose.ui.unit.IntOffset {
                                val gap = 6
                                val x = ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2)
                                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                val y = (anchorBounds.top - popupContentSize.height - gap)
                                    .coerceAtLeast(0)
                                return androidx.compose.ui.unit.IntOffset(x, y)
                            }
                        }
                    },
                    onDismissRequest = { viewModel.dismissMentionMenu() },
                    properties = androidx.compose.ui.window.PopupProperties(
                        focusable = false,
                        dismissOnBackPress = true,
                        // Same rationale as the slash popup: dismissOnClickOutside=false
                        // because the input field below the popup sits in the
                        // "outside" region (focusable=false → caret moves still
                        // count as outside). The chat-list tap-spy that drives
                        // dismissSlashMenu also dismisses this menu via
                        // dismissMentionMenu(); see the LazyColumn pointerInput.
                        dismissOnClickOutside = false,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .shadow(elevation = 8.dp, shape = RoundedCornerShape(10.dp))
                            .background(ChatColors.inputBg, RoundedCornerShape(10.dp))
                            .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp)),
                    ) {
                        if (mentionEntries.isEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isMentionScanning) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 1.5.dp,
                                        color = ChatColors.secondaryText,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(
                                    text = stringResource(
                                        if (isMentionScanning) R.string.mention_scanning
                                        else R.string.mention_no_match
                                    ),
                                    fontSize = 13.sp,
                                    color = ChatColors.secondaryText,
                                )
                            }
                        } else {
                            // [T-slash-picker-fixed-height port from iOS 73f1b94a]
                            // Mention picker shares the slash picker's
                            // locked 192dp height (4 rows × 46dp + 8dp)
                            // so both popups have the same band on screen.
                            val mentionListState = androidx.compose.foundation.lazy.rememberLazyListState()
                            // Keep the highlighted row visible when the user
                            // navigates with a hardware keyboard. iOS gets this
                            // for free from SwiftUI's List/scrollTo binding;
                            // mimic it explicitly here.
                            LaunchedEffect(mentionSelectedIndex, mentionEntries.size) {
                                val idx = mentionSelectedIndex
                                if (idx in mentionEntries.indices) {
                                    mentionListState.animateScrollToItem(idx)
                                }
                            }
                            LazyColumn(
                                state = mentionListState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(SLASH_PICKER_FIXED_HEIGHT)
                                    .verticalScrollbar(mentionListState),
                            ) {
                                itemsIndexed(mentionEntries, key = { _, e -> e.linuxPath }) { i, entry ->
                                    val isSelected = i == mentionSelectedIndex
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                if (isSelected) {
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                } else {
                                                    Color.Transparent
                                                },
                                            )
                                            .clickable {
                                                val (newText, newCaret) = viewModel.selectMention(
                                                    entry,
                                                    currentText = inputFieldValue.text,
                                                    currentCaret = inputFieldValue.selection.end,
                                                )
                                                // [fix/setinputtext-caret-intent] Pass the explicit caret
                                                // intent so the consuming LaunchedEffect places the cursor
                                                // exactly here — replaces the old manual TextFieldValue
                                                // double-write that raced the effect's pendingCaret read.
                                                viewModel.setInputText(newText, caretOverride = newCaret)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        // Single doc icon for every entry — the scope/mount
                                        // capsule on the right already labels what bucket
                                        // this is (workspace / skills / shared / memory /
                                        // <mountName>). iOS varies the icon per scope but
                                        // we keep it uniform here so the row stays
                                        // visually consistent at small sizes on Pixel 4a.
                                        Icon(
                                            imageVector = Icons.Default.Description,
                                            contentDescription = null,
                                            tint = ChatColors.secondaryText,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = entry.basename,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = ChatColors.primaryText,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = entry.displayPath,
                                                fontSize = 11.sp,
                                                color = ChatColors.secondaryText,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        // Scope / mount badge — matches iOS capsule.
                                        Text(
                                            text = entry.mountName ?: entry.scope.displayLabel,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ChatColors.secondaryText,
                                            modifier = Modifier
                                                .background(
                                                    ChatColors.toolCapsuleBg,
                                                    RoundedCornerShape(8.dp),
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Input box: iOS-style floating card — no visible border, separated
            // from the backdrop by a symmetric soft shadow painted by hand
            // (Android's Modifier.shadow only casts downward).
            val inputBgArgb = ChatColors.inputBg.toArgb()
            val shadowPaint = remember(inputBgArgb) {
                android.graphics.Paint().apply {
                    color = inputBgArgb
                    isAntiAlias = true
                }
            }
            // T185: Move-to-session capsule mirrors iOS
            // AIChatView.swift:1816 (.overlay(alignment: .topTrailing))
            // on the input card. We render it as the first child of the
            // composer Column, right-aligned, so it visually sits inside
            // the input card's top-right corner — Compose doesn't have a
            // free overlay primitive that doesn't need a Box wrapper,
            // and an in-flow Row at the top with Arrangement.End is the
            // cleanest equivalent.
            val showMoveCapsule by viewModel.hasInjectedShareContent.collectAsState()
            // Mirrors iOS swipe-up-to-send: drag the input bar upward —
            // if it holds text, a floating send-arrow + "Release to send"
            // capsule track the finger; releasing past `swipeArmFraction`
            // sends. With empty text + collapsed keyboard, releasing
            // activates the keyboard instead. Box wraps the existing
            // composer Column so the gesture + overlay live in the same
            // coordinate space without disturbing the bar's own layout.
            Box(modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalDx = 0f
                        var totalDy = 0f
                        var claimed = false
                        var lastPos = down.position
                        verticalDrag(down.id) { change ->
                            val delta = change.positionChange()
                            totalDx += delta.x
                            totalDy += delta.y
                            lastPos = change.position
                            if (!claimed) {
                                // Wait until a clearly vertical drag of
                                // at least `slop` px before claiming.
                                // Below that the TextField / list still
                                // get the events (taps, text scroll, …).
                                if (kotlin.math.abs(totalDy) < slop) return@verticalDrag
                                if (kotlin.math.abs(totalDy) <= kotlin.math.abs(totalDx)) return@verticalDrag
                                claimed = true
                            }
                            change.consume()
                            if (totalDy < 0) {
                                // Swiping up. Show hint only when there
                                // is text to send; otherwise keep the
                                // overlay hidden and defer keyboard
                                // activation to onEnd.
                                val hasText = inputText.isNotBlank()
                                if (hasText) {
                                    val newProgress = (-totalDy / swipeThresholdPx).coerceIn(0f, 1f)
                                    if (newProgress >= swipeArmFraction && sendSwipeProgress < swipeArmFraction) {
                                        swipeHaptics.performHapticFeedback(
                                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                        )
                                    }
                                    sendSwipeProgress = newProgress
                                    sendSwipeLocation = lastPos
                                } else if (sendSwipeProgress != 0f) {
                                    sendSwipeProgress = 0f
                                }
                            } else if (sendSwipeProgress != 0f) {
                                // Reversed direction; clear any hint.
                                sendSwipeProgress = 0f
                            }
                        }
                        // Drag ended (finger up or pointer cancel).
                        val hasText = inputText.isNotBlank()
                        val swipedUp = claimed && totalDy < 0 &&
                            kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx)
                        if (swipedUp && hasText) {
                            val attachmentsCount = viewModel.attachments.value.size
                            val canSendNow = hasText || attachmentsCount > 0
                            if (sendSwipeProgress >= swipeArmFraction && canSendNow) {
                                // T-drag-send-queue: route through the
                                // shared send-or-enqueue handler so a
                                // drag-to-send during streaming enqueues
                                // the prompt instead of being dropped —
                                // matches the send-button tap path which
                                // already enqueues mid-stream via
                                // viewModel.sendMessage → enqueuePrompt.
                                performSendOrEnqueue(viewModel.inputText.value)
                            }
                            sendSwipeProgress = 0f
                        } else if (swipedUp && !hasText && !inputFocused) {
                            // Empty input + collapsed keyboard -> bring
                            // up the keyboard. If the keyboard is
                            // already open, do nothing so a stray drag
                            // doesn't re-trigger anything.
                            inputFocusRequester.requestFocus()
                            keyboardController?.show()
                            sendSwipeProgress = 0f
                        } else {
                            sendSwipeProgress = 0f
                        }
                    }
                },
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val radiusPx = 20.dp.toPx()
                        val canvas = drawContext.canvas.nativeCanvas
                        // Pass 1: symmetric ambient halo — small blur, low alpha.
                        shadowPaint.setShadowLayer(
                            6.dp.toPx(), 0f, 0f,
                            android.graphics.Color.argb(22, 0, 0, 0),
                        )
                        canvas.drawRoundRect(
                            0f, 0f, size.width, size.height,
                            radiusPx, radiusPx,
                            shadowPaint,
                        )
                        // Pass 2: soft downward shadow (spot light).
                        shadowPaint.setShadowLayer(
                            10.dp.toPx(), 0f, 3.dp.toPx(),
                            android.graphics.Color.argb(24, 0, 0, 0),
                        )
                        canvas.drawRoundRect(
                            0f, 0f, size.width, size.height,
                            radiusPx, radiusPx,
                            shadowPaint,
                        )
                    }
                    .padding(top = if (attachments.isNotEmpty()) 8.dp else 4.dp),
            ) {
                // T185: Move-to capsule lives INSIDE the composer card,
                // pinned 8dp from the top-right corner, mirroring iOS
                // AIChatView.swift:1816 (.overlay(alignment: .topTrailing)
                // padding(.top, 6).padding(.trailing, 10)). A Popup
                // keeps it out of the composer's layout flow so the
                // attachment row + text field still own the full
                // vertical rhythm.
                if (showMoveCapsule) {
                    // T185: align Move-to right edge with the
                    // attachment row + button row (both 12dp). The
                    // anchorBounds rect is in px, so convert via
                    // LocalDensity rather than treating the constant
                    // as dp directly.
                    val popupDensity = androidx.compose.ui.platform.LocalDensity.current
                    val rightInsetPx = with(popupDensity) { 12.dp.roundToPx() }
                    val topInsetPx = with(popupDensity) { 6.dp.roundToPx() }
                    androidx.compose.ui.window.Popup(
                        popupPositionProvider = remember(rightInsetPx, topInsetPx) {
                            object : androidx.compose.ui.window.PopupPositionProvider {
                                override fun calculatePosition(
                                    anchorBounds: androidx.compose.ui.unit.IntRect,
                                    windowSize: androidx.compose.ui.unit.IntSize,
                                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                    popupContentSize: androidx.compose.ui.unit.IntSize,
                                ): androidx.compose.ui.unit.IntOffset {
                                    val x = (anchorBounds.right - popupContentSize.width - rightInsetPx)
                                        .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                    val y = (anchorBounds.top + topInsetPx).coerceAtLeast(0)
                                    return androidx.compose.ui.unit.IntOffset(x, y)
                                }
                            }
                        },
                        onDismissRequest = {},
                        properties = androidx.compose.ui.window.PopupProperties(
                            focusable = false,
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false,
                        ),
                    ) {
                        androidx.compose.material3.Surface(
                            shape = androidx.compose.foundation.shape.CircleShape,
                            // Mirrors iOS .ultraThinMaterial — solid-
                            // looking pill against the input bg.
                            // Without a hairline border the capsule
                            // washed out into the input card on the
                            // light theme, which is why it stopped
                            // reading as a pill.
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shadowElevation = 0.dp,
                            tonalElevation = 0.dp,
                            border = androidx.compose.foundation.BorderStroke(
                                0.5.dp,
                                ChatColors.thumbnailBorder,
                            ),
                            modifier = Modifier.clickable { showMoveSheet = true },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                            ) {
                                // arrow.right.circle look-alike: an
                                // outlined ring around a → glyph.
                                Box(
                                    modifier = Modifier
                                        .size(15.dp)
                                        .border(
                                            1.dp,
                                            ChatColors.secondaryText,
                                            CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(10.dp),
                                        tint = ChatColors.secondaryText,
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Move to…",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = ChatColors.secondaryText,
                                )
                            }
                        }
                    }
                }
                // Attachment thumbnails inside the box (iOS: 64×64 squares)
                if (attachments.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            // T185: 12dp horizontal so the row's left
                            // edge lines up with the +/slash button
                            // column and the typed text below.
                            .padding(horizontal = 12.dp),
                        // The chip itself now bakes in 8dp of trailing
                        // visual room for the remove badge that spills
                        // past the top-right; no extra spacedBy needed.
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        items(attachments, key = { it.id }) { attachment ->
                            // T-pwa-2: long-press menu only appears for
                            // .html / .htm attachments. The menu lives in
                            // a Box that anchors to the chip; the sheet
                            // itself is hosted at screen level (see
                            // webAppSheetTarget).
                            val isHtmlAttachment = attachment.fileName
                                .substringAfterLast('.', "")
                                .lowercase()
                                .let { it == "html" || it == "htm" }
                            var webAppMenuExpanded by remember(attachment.id) { mutableStateOf(false) }
                            Box {
                            AttachmentChip(
                                attachment = attachment,
                                onRemove = { viewModel.removeAttachment(attachment.id) },
                                // Intentionally hidden (WebApp entry point).
                                onLongClick = if (false && isHtmlAttachment) {
                                    { webAppMenuExpanded = true }
                                } else null,
                                onClick = {
                                    // Mirror iOS InputAttachmentTile
                                    // (AIChatView.swift:3699) which
                                    // .sheet's an AttachmentPreviewView
                                    // routed by file type. Images go
                                    // through the in-app fullscreen
                                    // viewer; non-image files take the
                                    // in-app FilePreviewScreen when we
                                    // hold a host file path, falling
                                    // back to the system viewer for
                                    // foreign content:// URIs.
                                    if (attachment.isImage) {
                                        // Collect every image chip in
                                        // the composer row so the user
                                        // can swipe through them.
                                        val imageChips = attachments.filter { it.isImage }
                                        val startIdx = imageChips.indexOfFirst { it.id == attachment.id }
                                            .coerceAtLeast(0)
                                        onPreviewImageGallery(
                                            imageChips.map { ic ->
                                                com.openminis.app.ui.components.ImageGalleryItem(
                                                    model = ic.uri,
                                                    caption = ic.fileName,
                                                )
                                            },
                                            startIdx,
                                        )
                                    } else {
                                        // T162: shares funnel through
                                        // addAttachmentFromStagedShare,
                                        // which copies the bytes into
                                        // cacheDir/share_inbound/<uuid>-
                                        // <name> and returns a
                                        // Uri.fromFile() URI. Handing
                                        // that file:// URI directly to
                                        // Intent.ACTION_VIEW raises
                                        // FileUriExposedException on
                                        // API 24+ and crashed the app
                                        // on the user's first chip tap.
                                        // Route file:// chips into the
                                        // in-app FilePreviewScreen via
                                        // the host onPreviewAttachment
                                        // callback (same path the user-
                                        // bubble chip uses); leave
                                        // content:// chips on the
                                        // system viewer because we
                                        // don't have a host path for
                                        // those.
                                        val uri = attachment.uri
                                        val asFile = if (uri.scheme == "file") {
                                            uri.path?.let { java.io.File(it) }
                                        } else null
                                        if (asFile != null && asFile.exists()) {
                                            onPreviewAttachment(
                                                com.openminis.app.ui.sandbox.FileItem(
                                                    file = asFile,
                                                    name = attachment.fileName,
                                                    isDirectory = false,
                                                    isSymlink = false,
                                                    size = asFile.length(),
                                                    modifiedMs = asFile.lastModified(),
                                                )
                                            )
                                        } else {
                                            val intent = android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                            ).apply {
                                                setDataAndType(uri, attachment.mimeType)
                                                addFlags(
                                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                                )
                                            }
                                            try {
                                                context.startActivity(intent)
                                            } catch (_: android.content.ActivityNotFoundException) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "No app available to open this attachment.",
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    }
                                },
                            )
                            // Intentionally hidden (WebApp entry point).
                            if (false && isHtmlAttachment) {
                                com.openminis.app.ui.components.MinisMenu(
                                    expanded = webAppMenuExpanded,
                                    onDismissRequest = { webAppMenuExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.webapp_add_to_home)) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.AppShortcut,
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            webAppMenuExpanded = false
                                            onOpenWebAppSheet(attachment)
                                        },
                                    )
                                }
                            }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // Composer input field. (Voice input mode was removed
                // with the mic button; the field is now the only path.)
                run {
                    val interactionSource = remember { MutableInteractionSource() }
                    val mergedTextStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 16.5.sp * chatInputFontScale,
                        lineHeight = 20.sp * chatInputFontScale,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // [T-android-enter-to-send-broken] Live read of the
                    // "Return key sends" preference. Bound here (not
                    // captured at BasicTextField construction) so a
                    // toggle in Settings reflects on the next IME
                    // commit without recomposing the chat tree.
                    val sendOnEnter = com.openminis.app.ui.settings
                        .returnKeySendsMessage(context)
                    // Shared "Enter pressed → send" body used by BOTH
                    // the hardware-keyboard onKeyEvent path AND the
                    // soft-keyboard KeyboardActions.onSend below.
                    // Pre-fix only the onKeyEvent path existed and
                    // most soft IMEs (Gboard, Sogou, MIUI) never
                    // route an Enter through onKeyEvent under
                    // ImeAction.Default — they just inserted a '\n'
                    // and the preference appeared not to work. We
                    // now flip imeAction to Send when the toggle is
                    // on, so the IME shows the send icon AND fires
                    // onSend; this lambda is the single source of
                    // truth for what "press Enter to send" means.
                    val performEnterSend: () -> Boolean = handler@{
                        if (inputText.isBlank() && attachments.isEmpty()) return@handler false
                        // Intercept slash commands so "/compact" et al.
                        // run locally instead of being sent as a chat
                        // turn. Mirrors iOS performSend().
                        if (viewModel.tryExecuteInputAsSlashCommand(inputText)) {
                            viewModel.setInputText("")
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            return@handler true
                        }
                        // T160: snapshot → clear state + IME →
                        // sendMessage. Same ordering as the send-
                        // button click; finishComposingText fires
                        // when focus drops so any IME composing
                        // buffer is committed/dropped before the
                        // empty inputText becomes visible.
                        val toSend = inputText
                        lastSendTimeMs = SystemClock.elapsedRealtime()
                        // [P2-scroll-user-send] snapshot BEFORE sendMessage
                        // (insert pushes index 0 → 1; must not read after).
                        val wasScrolledIntoHistory = !isNearBottom.value
                        viewModel.setInputText("")
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        viewModel.sendMessage(toSend)
                        noteSendForInputModePref()
                        // [bottom-trigger] Re-engage follow only if the
                        // reader was anchored at the bottom (P2: must not
                        // turn a history reader into a follower).
                        // [forward-stable] Exactly one pending request.
                        if (!wasScrolledIntoHistory) {
                            onFollowEvent(FollowEvent.Send)
                        } else {
                            AppLogger.debug("USER-SEND", "ime-action reader-in-history, skip yank")
                        }
                        true
                    }
                    BasicTextField(
                        value = inputFieldValue,
                        onValueChange = { tfv ->
                            // T217-2: drop IME residue commits after send.
                            // clearFocus triggers finishComposingText, which makes
                            // voice/Pinyin IMEs replay their composing candidate
                            // through onValueChange after we cleared inputText.
                            // Residue always carries a non-null composition region;
                            // normal user typing arrives with composition==null,
                            // so we only gate the post-send window when the IME
                            // is mid-composition — fast typing right after a send
                            // is never dropped.
                            val now = SystemClock.elapsedRealtime()
                            if (tfv.composition != null && now - lastSendTimeMs < 500L && tfv.text.isNotEmpty()) {
                                return@BasicTextField
                            }
                            // [T-android-enter-to-send-multiline] Root cause:
                            // the composer is a multi-line BasicTextField
                            // (maxLines=6 ⇒ EditorInfo carries
                            // TYPE_TEXT_FLAG_MULTI_LINE, confirmed inputType
                            // 0x28001 in dumpsys input_method). In multi-line
                            // mode soft IMEs (Gboard/LatinIME, Sogou, MIUI)
                            // render Enter as a newline and IGNORE
                            // IME_ACTION_SEND — so KeyboardActions.onSend
                            // never fires and the "Return key sends" pref
                            // looked inert. The IME commits the Enter as a
                            // plain '\n' through onValueChange (not through
                            // onKeyEvent / a KEYCODE_ENTER), so the only
                            // place to catch it for soft keyboards is here.
                            //
                            // Detect a single '\n' freshly inserted into the
                            // text (one Enter keypress) and convert it to a
                            // send. Guarded to a single added newline so a
                            // paste containing newlines is NOT swallowed —
                            // those increase the count by >1 and fall through
                            // to the normal multi-line edit. Hardware-keyboard
                            // Enter / Shift+Enter still go through onKeyEvent
                            // below (Shift+Enter inserts a newline there and
                            // never reaches the send path).
                            if (sendOnEnter && !showMentionMenu) {
                                val oldText = inputFieldValue.text
                                val newText = tfv.text
                                val addedNewline = newText.length == oldText.length + 1 &&
                                    newText.count { it == '\n' } == oldText.count { it == '\n' } + 1
                                if (addedNewline) {
                                    val caret = tfv.selection.end
                                    // The inserted char sits just before the
                                    // caret; confirm it is the newline so we
                                    // don't misfire on an unrelated 1-char edit
                                    // that happens to keep newline parity.
                                    if (caret in 1..newText.length &&
                                        newText[caret - 1] == '\n'
                                    ) {
                                        performEnterSend()
                                        return@BasicTextField
                                    }
                                }
                            }
                            inputFieldValue = tfv
                            // [Txxx-android-composer-caret] The IME's
                            // tfv.selection.end is the authoritative user
                            // caret right now. Persist it so a later
                            // LaunchedEffect can fall back to it instead of
                            // an already-stale inputFieldValue.selection.
                            lastTrueCaretEnd = tfv.selection.end.coerceAtLeast(0)
                            if (inputText != tfv.text) {
                                // [fix/voice-crash-observability tail] IME
                                // voice dictation drives onValueChange with
                                // high-frequency, large text bursts. Each
                                // burst used to call setInputText
                                // immediately, re-serializing the draft +
                                // recomputing slash/mention state + running
                                // the full ledger reconcile on the main
                                // thread. A single large increment (>8
                                // chars — see shouldDebounceImeBurst) is
                                // debounced: buffer locally and flush once
                                // 150 ms after the last burst, so a burst
                                // of N events collapses to one commit.
                                // Ordinary typing (<=8 chars) and deletes
                                // go through immediately — instant feedback
                                // is preserved.
                                if (shouldDebounceImeBurst(inputText, tfv.text)) {
                                    imeBurstBuffer = tfv.text
                                    imeBurstCaret = tfv.selection.end
                                    imeBurstJob?.cancel()
                                    imeBurstJob = coroutineScope.launch {
                                        delay(150)
                                        val flushed = imeBurstBuffer
                                        if (flushed != null && flushed != inputText) {
                                            viewModel.setInputText(flushed)
                                            viewModel.updateSlashMenuState(flushed)
                                            // [fix/voice-ime-mention-scan] Sweep the
                                            // @-mention scan into the same flush as the
                                            // slash state. A voice/Pinyin burst arrives as
                                            // dozens of large, space-less CJK edits; the old
                                            // unconditional mention scan below was walking back
                                            // from the caret to the text head (O(n)) on EVERY
                                            // one of them, re-typing unchanged menu state.
                                            // Folding it into the 150 ms flush keeps the scan
                                            // semantics identical while collapsing N scans to 1.
                                            viewModel.updateMentionMenuState(
                                                text = flushed,
                                                caret = imeBurstCaret.coerceIn(0, flushed.length),
                                            )
                                        }
                                    }
                                } else {
                                    viewModel.setInputText(tfv.text)
                                    viewModel.updateSlashMenuState(tfv.text)
                                    viewModel.updateMentionMenuState(
                                        text = tfv.text,
                                        caret = tfv.selection.end,
                                    )
                                }
                            } else {
                                // Text unchanged, caret may have moved: the @-mention
                                // token/filter must still re-scan on a pure selection change
                                // (the only case that doesn't go through the branches above).
                                viewModel.updateMentionMenuState(
                                    text = tfv.text,
                                    caret = tfv.selection.end,
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 25.dp)
                            .focusRequester(inputFocusRequester)
                            .onFocusChanged { inputFocused = it.isFocused }
                            .onKeyEvent { event ->
                                // T-at-filepicker-keyboard: while the @-mention
                                // menu is open, hardware Up/Down navigates the
                                // list and Return commits the highlighted entry.
                                // Falls through to the normal Return-send path
                                // when there are no mention candidates so the
                                // user isn't stuck if the menu is empty.
                                if (showMentionMenu && event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.DirectionUp -> {
                                            viewModel.mentionMenuUp()
                                            return@onKeyEvent true
                                        }
                                        Key.DirectionDown -> {
                                            viewModel.mentionMenuDown()
                                            return@onKeyEvent true
                                        }
                                        Key.Enter -> {
                                            val result = viewModel.executeSelectedMention(
                                                currentText = inputFieldValue.text,
                                                currentCaret = inputFieldValue.selection.end,
                                            )
                                            if (result != null) {
                                                val (newText, newCaret) = result
                                                // [fix/setinputtext-caret-intent] Explicit caret intent,
                                                // same as the tap path — single-writer through LaunchedEffect.
                                                viewModel.setInputText(newText, caretOverride = newCaret)
                                                return@onKeyEvent true
                                            }
                                            // Menu open but no candidates → fall
                                            // through to Return-send / newline.
                                        }
                                        Key.Escape -> {
                                            viewModel.dismissMentionMenu()
                                            return@onKeyEvent true
                                        }
                                        else -> Unit
                                    }
                                }
                                // Return-key behavior is user-configurable
                                // (Appearance → Return Key, default Newline =
                                // iOS shipping default). Shift+Enter always
                                // inserts a newline regardless of the setting,
                                // mirroring iOS hardware-keyboard semantics.
                                // [T-android-enter-to-send-broken] Hardware-
                                // keyboard path. Soft IME route goes through
                                // KeyboardActions.onSend below.
                                if (event.type == KeyEventType.KeyDown &&
                                    event.key == Key.Enter &&
                                    !event.isShiftPressed &&
                                    sendOnEnter
                                ) {
                                    performEnterSend()
                                } else false
                            },
                        textStyle = mergedTextStyle,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 6,
                        // [T-android-enter-to-send-broken] When the
                        // user has Return-Key=Send turned on, ask the
                        // IME for the Send action so it (a) shows the
                        // send glyph instead of "Enter" and (b)
                        // actually invokes KeyboardActions.onSend
                        // instead of silently inserting '\n'. With
                        // Default, Gboard / Sogou / MIUI etc. never
                        // routed Enter through onKeyEvent so the
                        // preference appeared inert.
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (sendOnEnter) ImeAction.Send else ImeAction.Default,
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = { performEnterSend() },
                        ),
                        interactionSource = interactionSource,
                        decorationBox = { innerTextField ->
                            OutlinedTextFieldDefaults.DecorationBox(
                                value = inputText,
                                innerTextField = innerTextField,
                                enabled = true,
                                singleLine = false,
                                visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                                interactionSource = interactionSource,
                                // Placeholder removed per user request: the
                                // "Message <SoulName>" hint was dropped so
                                // the composer starts visually empty. No
                                // placeholder param = no hint text; the "@
                                // to mention files" affordance is still
                                // discoverable by typing "@".
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                ),
                                // T7: vertical 10dp → 7dp (≈ −15%) to slim the
                                // chat composer. Settings TextFields keep
                                // Material3 default padding — those are
                                // 1-shot config inputs, not the daily-
                                // friction surface the user wants tightened.
                                // T185: 12dp horizontal lines the
                                // typed text up with the +/slash and
                                // mic/send icon-button row below
                                // (Modifier.padding(horizontal = 12.dp)
                                // there) and the attachment chip row
                                // (also 12dp). 16dp left an unaligned
                                // jog where the text started further
                                // right than every other composer
                                // element.
                                contentPadding = PaddingValues(
                                    horizontal = 12.dp,
                                    vertical = 7.dp,
                                ),
                            )
                        },
                    )
                }

                // Button row below text field (iOS layout: + / ... mic send)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // T185: 12dp horizontal lines the +/slash and
                        // mic/send icon-button column up with the
                        // attachment row + textfield + Move-to popup.
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Circular model-picker button — matches the + and send
                    // button style for a unified look. The model name and
                    // resolved-status dot live in the nav-bar subtitle;
                    // this is just a compact trigger that can't be
                    // mistaken for the text field (no long text label).
                    //
                    // [T-composer-model-picker-hide] Hideable via Settings
                    // → Appearance → Chat Menu → "Model picker button": the
                    // nav-bar subtitle (model name + status dot) remains
                    // tappable and opens the same picker, so hiding this
                    // button only removes the redundant in-composer trigger.
                    if (chatActions.composerModelPickerVisible) {
                        InputCircleButton(onClick = { onOpenModelPicker() }) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.model_picker_default_badge),
                                tint = ChatColors.secondaryText,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    // The "/" slash-command circle button used to sit here.
                    // Moved into the top-right chat menu (above Token Usage)
                    // to reclaim composer width; typing "/" still opens the
                    // same sheet, so no functionality was removed.

                    // T187: Exit Edit Mode pill, only while editingMessageId
                    // is non-null. Tap clears the edit flag + composer text
                    // without truncating history. iOS parity:
                    // AIChatView.swift L1586 editExitButton.
                    val editingId by viewModel.editingMessageId.collectAsState()
                    if (editingId != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = ChatColors.inputBg,
                            modifier = Modifier.clickable {
                                viewModel.cancelEdit()
                                viewModel.setInputText("")
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.chat_edit_exit_button),
                                style = MaterialTheme.typography.labelMedium,
                                color = ChatColors.secondaryText,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Attach (+): moved from the left edge to sit
                    // directly beside mic/send. The three actions
                    // here (photos, file, camera) all produce
                    // something that gets SENT, so grouping them
                    // with the send affordance keeps the
                    // "compose -> attach -> send" gesture inside one
                    // thumb arc instead of spanning the full width.
                    //
                    // [T-attach-menu-customizable] The "+" menu mirrors
                    // the top-right "..." menu pattern: the three attach
                    // actions are individually hideable and reorderable
                    // from Settings → Appearance → Chat Menu → Attach.
                    // Rendering follows chatActions.visibleAttachOrder
                    // (user order filtered by visibility) and converges:
                    //   • 2+ visible → "+" opens a menu of exactly those;
                    //   • exactly 1 visible → promoted to a direct
                    //     InputCircleButton (one tap instead of two,
                    //     mirroring the "..." menu's soloCustomKey);
                    //   • 0 visible → no "+" renders at all (it would
                    //     open an empty menu — a dead control).
                    // Taps funnel through launchAttach() so the promoted
                    // button and the menu share one implementation.
                    val attachKeys = chatActions.visibleAttachOrder
                    fun launchAttach(key: String) {
                        when (key) {
                            AttachActionCatalog.CHOOSE_PHOTOS ->
                                onPickMedia()
                            AttachActionCatalog.ADD_FILE ->
                                // OpenMultipleDocuments takes a mime-
                                // type array; "*/*" stays the wildcard.
                                onPickFile()
                            AttachActionCatalog.TAKE_PHOTO -> {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    android.Manifest.permission.CAMERA,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    onLaunchCamera()
                                } else {
                                    onLaunchCameraPermission()
                                }
                            }
                        }
                    }
                    // Solo attach key → direct button (no "+" menu).
                    val soloAttachKey = attachKeys.singleOrNull()
                    if (attachKeys.isNotEmpty()) {
                        Box {
                            InputCircleButton(
                                onClick = {
                                    if (soloAttachKey != null) {
                                        launchAttach(soloAttachKey)
                                    } else {
                                        showAttachMenu = true
                                    }
                                },
                            ) {
                                if (soloAttachKey != null) {
                                    val soloSpec = AttachActionCatalog.spec(soloAttachKey)
                                    Icon(
                                        soloSpec?.icon ?: Icons.Default.Add,
                                        contentDescription = soloSpec?.let {
                                            stringResource(it.titleRes)
                                        } ?: "Attach",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Attach",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            if (soloAttachKey == null) {
                                MinisMenu(
                                    expanded = showAttachMenu,
                                    onDismissRequest = { showAttachMenu = false },
                                ) {
                                    // Orders differ per user; the default is
                                    // Choose Photos & Videos / Add File / Take
                                    // Photo. Picking existing media is by far
                                    // the most frequent attach action (first
                                    // slot, closest to the thumb), while Take
                                    // Photo is the rarest and also the only
                                    // destructive-ish one (opens the camera,
                                    // can lose the draft on some OEM camera
                                    // apps), so it sits last where it can't be
                                    // hit by accident — unless the user
                                    // reorders it in Chat Menu settings.
                                    for (entryKey in attachKeys) {
                                        val spec = AttachActionCatalog.spec(entryKey) ?: continue
                                        key(entryKey) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(spec.titleRes)) },
                                                leadingIcon = {
                                                    Icon(spec.icon, contentDescription = null)
                                                },
                                                onClick = {
                                                    showAttachMenu = false
                                                    launchAttach(entryKey)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Voice input (mic button, language pill, inline
                    // voice panel, read-replies TTS) removed here.

                    // Right: 3-state Send / Enqueue / Stop button (mirrors iOS sendButton).
                    //   • streaming + hasText  → SEND (routes through viewModel.sendMessage,
                    //     which dispatches to enqueuePrompt since _isStreaming is true).
                    //     Visual feedback for the queued prompt comes from the dashed
                    //     bubble that ChatViewModel.enqueuePrompt appends to the message
                    //     list — no extra button badge needed (matches iOS).
                    //   • streaming + !hasText → STOP (cancel current run).
                    //   • !streaming           → SEND (full color when hasText, dimmed
                    //     when empty; same as before).
                    // T180: an attachments-only send (no caption) is a
                    // valid message — mirrors iOS where !attachments.isEmpty
                    // satisfies the composer's send guard. Without this an
                    // image-only "look at this" send is impossible.
                    val hasText = inputText.isNotBlank()
                    val hasContent = hasText || attachments.isNotEmpty()
                    val showStop = isStreaming && !hasContent
                    if (showStop) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(ChatColors.error, CircleShape)
                                .clip(CircleShape)
                                .clickable { viewModel.cancelStream() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Stop",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    } else {
                        // Streaming with content → Send-into-queue; Idle with content → Send.
                        // Idle without text or attachments → disabled.
                        val canActivate = hasContent
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(
                                    if (canActivate) ChatColors.sendButton
                                    else ChatColors.sendButtonDisabled,
                                    CircleShape,
                                )
                                .clip(CircleShape)
                                .clickable(enabled = canActivate) {
                                    // T-drag-send-queue: route through the
                                    // shared send-or-enqueue handler. Same
                                    // semantics as before: slash short-
                                    // circuit, snapshot text, clear input
                                    // + focus, then sendMessage (which
                                    // routes to enqueuePrompt when
                                    // _isStreaming is true), then re-pin
                                    // the list to index 0 with a 100ms
                                    // re-pin to catch the late-mounting
                                    // "thinking" indicator.
                                    performSendOrEnqueue(inputText)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.ArrowUpward,
                                contentDescription = "Send",
                                tint = if (canActivate) ChatColors.background
                                else ChatColors.primaryText.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
            // --- Swipe-to-send floating hint (extracted helper) ---
            SwipeToSendHint(
                progress = sendSwipeProgress,
                armFraction = swipeArmFraction,
                location = sendSwipeLocation,
                hoverAbovePx = swipeHapticOffsetPx,
                arrowHalfPx = swipeArrowHalfPx,
                // While streaming, sendMessage() routes the prompt
                // through enqueuePrompt() instead — surface that in
                // the hint so the user knows the gesture still works
                // mid-stream (mirrors the send-button's send/enqueue
                // toggle, since on Android there's no separate visual
                // state for the queued case).
                isEnqueue = isStreaming,
            )
        } // end swipe-to-send Box wrapping the composer Column

        if (showMoveSheet) {
            MoveToSessionSheet(
                currentSessionId = sessionId,
                chatRepository = chatRepository,
                onDismiss = { showMoveSheet = false },
                onSelect = { targetId ->
                    ChatViewModelStore.stashPendingTransfer(
                        ChatViewModelStore.PendingTransfer(
                            inputText = inputText,
                            attachments = viewModel.attachments.value,
                        ),
                    )
                    viewModel.setInputText("")
                    viewModel.clearAttachments()
                    viewModel.clearShareInjectedFlag()
                    showMoveSheet = false
                    onMoveToSession(targetId)
                },
            )
        }

}
