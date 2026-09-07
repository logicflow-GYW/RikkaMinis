package com.openminis.app.ui.chat

// [T-android-split-chat] Flat-chat-item data model + flatten/merge transforms
// extracted verbatim from ChatScreen.kt: FlatChatItem (sealed), mergeStreamingOverlay,
// buildFlatChatItems. Full import block copied (unused=warnings); all internal.

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
import androidx.compose.runtime.Immutable
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
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
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

/**
 * [render-churn-2] Element-reference list equality for block lists: true
 * when both lists carry the same instances. A per-tick rebuild of unchanged
 * blocks (publish's `toolBlocks.toList()`) compares equal WITHOUT walking
 * multi-KB content strings — completed blocks stay frozen while only the
 * live (copy()'d) block differs by reference.
 */
internal fun sameBlockRefs(a: List<AssistantBlock>, b: List<AssistantBlock>): Boolean {
    if (a === b) return true
    if (a.size != b.size) return false
    for (i in a.indices) {
        if (a[i] !== b[i]) return false
    }
    return true
}

@Immutable
internal sealed class FlatChatItem {
    abstract val key: String
    abstract val contentType: String

    /**
     * Cheap-equals — see [AssistantText]. User messages are short and don't
     * stream, but during a streaming overlay rebuild we still re-create the
     * entire FlatChatItem list, and LazyColumn calls equals to decide skip.
     * Compare by id + reference identity of the wrapped ChatMessage.
     */
    /**
     * [T-android-candidate-bubble-gap] `precededByUser` is true when the
     * immediately-preceding flat item is also a user bubble (e.g. two
     * candidate / queued messages sent back to back). Consecutive user
     * bubbles have no intervening AssistantHeader row to create visual
     * separation, and the LazyColumn's `spacedBy(2.dp)` alone is too tight
     * — the two bubbles read as one. When set, UserMessageBubble adds extra
     * top padding so the pair is clearly two distinct messages.
     */
    @Immutable
    class UserBubble(
        val message: ChatMessage,
        val precededByUser: Boolean = false,
    ) : FlatChatItem() {
        override val key = "user:${message.id}"
        override val contentType = "user"
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UserBubble) return false
            // ChatMessage is a data class; reuse its equals (cheap for user
            // bubbles which carry short content and small attachment lists).
            return message == other.message && precededByUser == other.precededByUser
        }
        override fun hashCode(): Int = message.hashCode() * 31 + precededByUser.hashCode()
    }

    @Immutable
    data class AssistantHeader(val messageId: String) : FlatChatItem() {
        override val key = "header:$messageId"
        override val contentType = "header"
    }

    /**
     * Equality on this class previously compared every field including
     * `messageMarkdown` — a CONCATENATED markdown of the entire parent
     * assistant message — char-by-char. During streaming, LazyColumn called
     * the autogenerated equals to decide stable-skip per item, and a typical
     * 50-fragment message with 1 KB per fragment ate ~3.7 seconds of main
     * thread (profile #3, `String.charAt` at 91.8% exclusive). The hand-
     * rolled equals below compares cheap stable identity (key fields + the
     * String *reference* of large bodies) instead, falling back to length
     * if the references differ — never a full char-by-char walk.
     */
    @Immutable
    class AssistantText(
        val messageId: String,
        val block: AssistantBlock,
        val isStreaming: Boolean,
        /** Joined raw markdown of the parent message, used by the selection toolbar's Copy Markdown action. */
        val messageMarkdown: String,
    ) : FlatChatItem() {
        override val key = "text:$messageId:${block.id}"
        override val contentType = "text"
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AssistantText) return false
            return messageId == other.messageId &&
                block === other.block &&
                isStreaming == other.isStreaming &&
                messageMarkdown.length == other.messageMarkdown.length
        }
        override fun hashCode(): Int {
            var h = messageId.hashCode()
            h = h * 31 + System.identityHashCode(block)
            h = h * 31 + isStreaming.hashCode()
            h = h * 31 + messageMarkdown.length
            return h
        }
    }

    /**
     * One rendered markdown sub-block (paragraph, code block, list, …) of an
     * AssistantText. Pattern A from the streaming-markdown research: each
     * block is its own LazyColumn item so completed blocks are frozen by
     * LazyList's per-item anchor and only the trailing "live" block
     * re-parses on every chunk. Replaces the previous "whole AssistantText
     * is a single LazyColumn item containing an internal Column of blocks"
     * design which caused the user's scroll position to drift mid-stream.
     */
    /**
     * See [AssistantText] for the rationale behind the hand-rolled equals.
     * `rawText` and `messageMarkdown` are both potentially long; we compare
     * by length (cheap proxy for "has content grown") and identity instead
     * of char-by-char.
     */
    @Immutable
    class AssistantMarkdownBlock(
        val messageId: String,
        val parentBlockId: String,
        val rawText: String,
        val blockIndex: Int,
        val isLastBlockOfMessage: Boolean,
        val messageIsStreaming: Boolean,
        /** Joined raw markdown of the parent message, used by Copy Markdown. */
        val messageMarkdown: String,
    ) : FlatChatItem() {
        override val key = "mdblock:$messageId:$parentBlockId:$blockIndex"
        override val contentType = "mdblock"
        /** True when this fragment is the streaming tail of a live message. */
        val isStreaming: Boolean get() = messageIsStreaming && isLastBlockOfMessage
        // [render-churn-2] messageMarkdown is deliberately NOT compared: it
        // is the joined markdown of ALL text blocks in the message, so it
        // grows on every streamed chunk — comparing its length made every
        // mdblock row of a long message recompose on every tick (LongCtx
        // 3-5s re-layout root cause). The selection toolbar's Copy Markdown
        // reads the freshest registration via rememberMessageMarkdown from
        // the trailing row (which DOES recompose while streaming), and the
        // turn-end reconcileAndVerifyTerminalText converges same-length
        // rewrites — so dropping it from equals loses nothing rendered.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AssistantMarkdownBlock) return false
            return messageId == other.messageId &&
                parentBlockId == other.parentBlockId &&
                blockIndex == other.blockIndex &&
                isLastBlockOfMessage == other.isLastBlockOfMessage &&
                messageIsStreaming == other.messageIsStreaming &&
                rawText.length == other.rawText.length
        }
        override fun hashCode(): Int {
            var h = messageId.hashCode()
            h = h * 31 + parentBlockId.hashCode()
            h = h * 31 + blockIndex
            h = h * 31 + isLastBlockOfMessage.hashCode()
            h = h * 31 + messageIsStreaming.hashCode()
            h = h * 31 + rawText.length
            return h
        }
    }

    @Immutable
    data class AssistantThinking(
        val messageId: String,
        val block: AssistantBlock,
        val isLast: Boolean,
        val messageIsStreaming: Boolean,
        // T300: thinking level captured at the message's creation. Null
        // for assistant messages restored from DB (legacy / pre-T300) —
        // the renderer falls back to the chat's current level.
        val messageThinkingLevel: com.openminis.app.data.model.ThinkingLevel? = null,
        // [T-android-thinking-auto-collapse] True when this thinking block
        // is the LAST block of any kind in the message (including text /
        // tool_use), not merely the last thinking block. Drives the
        // auto-collapse `isStreaming` signal so a thinking block flips to
        // collapsed the moment a subsequent text or tool block arrives —
        // mirrors iOS ThinkingBlockView, which only sees `isStreaming=true`
        // while it really is the trailing block. Defaults false so DB-
        // restored / legacy items render collapsed (the pre-change
        // behaviour for non-trailing thinking).
        val isLastBlockOverall: Boolean = false,
    ) : FlatChatItem() {
        override val key = "thinking:$messageId:${block.id}"
        override val contentType = "thinking"
    }

    @Immutable
    data class AssistantToolUse(
        val messageId: String,
        val block: AssistantBlock,
        val allToolBlocks: List<AssistantBlock>,
        /** True if this is the last cancelled tool in its message — only one Retry button per message. */
        val isLastCancelled: Boolean = false,
    ) : FlatChatItem() {
        override val key = "tool:$messageId:${block.id}"
        override val contentType = "tool"
        // [render-churn-2] Hand-rolled cheap equals: the block is compared
        // by REFERENCE — an unchanged completed block is the same instance
        // across ticks (only live blocks get copy()'d), so completed pills
        // stay frozen. allToolBlocks (re-created per publish via toList())
        // is compared element-wise by reference for the same reason. Never
        // walk the data-class field comparison (multi-KB content strings).
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AssistantToolUse) return false
            if (messageId != other.messageId || block !== other.block ||
                isLastCancelled != other.isLastCancelled) return false
            val a = allToolBlocks
            val b = other.allToolBlocks
            if (a === b) return true
            if (a.size != b.size) return false
            for (i in a.indices) {
                if (a[i] !== b[i]) return false
            }
            return true
        }
        override fun hashCode(): Int {
            var h = messageId.hashCode()
            h = h * 31 + System.identityHashCode(block)
            h = h * 31 + isLastCancelled.hashCode()
            h = h * 31 + allToolBlocks.size
            return h
        }
    }

    /**
     * [T-android-tool-run-collapse] One collapsible row representing ALL
     * tool_use blocks of a single assistant message. Emitted instead of
     * individual [AssistantToolUse] rows when a message has >= 2 tool blocks.
     *
     * Renders as a foldable "tool run" card: while any tool is still
     * streaming/pending/running the group stays expanded (so the user sees
     * live progress); once every tool reached a terminal state the card
     * auto-collapses into a single summary header ("N tools · total").
     * Tapping the header expands it again (user takes over the state).
     *
     * Mirrors OmniBot's AgentRunHeader semantics: running forces open,
     * completion collapses, user tap overrides either way.
     *
     * `isRunning` is derived here from the carried blocks rather than
     * persisted: a tool is live while its status is STREAMING/PENDING/
     * RUNNING. This keeps collapse/expand purely a UI concern — nothing in
     * the data layer changes.
     */
    @Immutable
    data class AssistantToolRunGroup(
        val messageId: String,
        val tools: List<AssistantBlock>,
        /**
         * Same-message thinking blocks (kind == "thinking"), folded into the
         * same run group so ONE agent turn = ONE card (thinking + tools).
         * Rendered above the tool pills inside the expanded area; the group
         * header shows the tool count / thinking title + aggregate duration.
         */
        val thinkingBlocks: List<AssistantBlock> = emptyList(),
        /** True if ANY block (thinking or tool) in the group is still live (STREAMING/PENDING/RUNNING). */
        val isRunning: Boolean,
        /** True if the last tool in the group is CANCELLED — drives the single Retry affordance. */
        val isLastCancelled: Boolean,
        // T300: thinking-level snapshot at the message's creation, carried
        // through from ChatMessage so the renderer can gate the thinking
        // section exactly like the retired AssistantThinking row did. Null
        // for DB-restored messages — the renderer falls back to the chat's
        // current level (see ChatScreen's ToolCallRunGroup call site).
        val messageThinkingLevel: com.openminis.app.data.model.ThinkingLevel? = null,
    ) : FlatChatItem() {
        override val key = "toolrun:$messageId"
        override val contentType = "toolrun"

        /** Aggregate duration of all finished tools, ms. */
        val totalDurationMs: Long
            get() = tools.sumOf { it.durationMs }

        val count: Int get() = tools.size

        /** Thinking + tool block count. */
        val stepCount: Int get() = thinkingBlocks.size + tools.size

        // [render-churn-2] Hand-rolled cheap equals: tools/thinkingBlocks
        // compared element-wise by REFERENCE — unchanged completed blocks
        // are the same instances across ticks (only the live block gets
        // copy()'d), so a per-tick rebuild of the same blocks compares
        // equal and the completed run card stays frozen. The old
        // data-class equals walked every field including multi-KB content
        // strings and reported "changed" whenever the live block's content
        // ticked, recomposing the whole group every second.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AssistantToolRunGroup) return false
            if (messageId != other.messageId) return false
            if (isRunning != other.isRunning || isLastCancelled != other.isLastCancelled) return false
            if (messageThinkingLevel != other.messageThinkingLevel) return false
            if (!sameBlockRefs(tools, other.tools)) return false
            if (!sameBlockRefs(thinkingBlocks, other.thinkingBlocks)) return false
            return true
        }
        override fun hashCode(): Int {
            var h = messageId.hashCode()
            h = h * 31 + tools.size
            h = h * 31 + thinkingBlocks.size
            h = h * 31 + isRunning.hashCode()
            h = h * 31 + isLastCancelled.hashCode()
            h = h * 31 + (messageThinkingLevel?.hashCode() ?: 0)
            return h
        }
    }

    @Immutable
    data class AssistantInfo(
        val messageId: String,
        val block: AssistantBlock,
    ) : FlatChatItem() {
        override val key = "info:$messageId:${block.id}"
        override val contentType = "info"
    }

    @Immutable
    data class AssistantTyping(val messageId: String) : FlatChatItem() {
        override val key = "typing:$messageId"
        override val contentType = "typing"
    }

    @Immutable
    data class AssistantError(val messageId: String, val error: String, val errorDetail: String? = null) : FlatChatItem() {
        override val key = "error:$messageId"
        override val contentType = "error"
    }

    /**
     * See [AssistantText] — same cheap-equals rationale.
     */
    @Immutable
    class AssistantLegacyContent(
        val messageId: String,
        val content: String,
        val isStreaming: Boolean,
        /** Same as content here (no separate text-block markdown for legacy rows). */
        val messageMarkdown: String = content,
    ) : FlatChatItem() {
        override val key = "legacy:$messageId"
        override val contentType = "legacy"
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AssistantLegacyContent) return false
            return messageId == other.messageId &&
                isStreaming == other.isStreaming &&
                content.length == other.content.length &&
                messageMarkdown.length == other.messageMarkdown.length
        }
        override fun hashCode(): Int {
            var h = messageId.hashCode()
            h = h * 31 + isStreaming.hashCode()
            h = h * 31 + content.length
            h = h * 31 + messageMarkdown.length
            return h
        }
    }

    /**
     * [fix/message-node-item-generator] Message-level aggregated item —
     * the "one node, one card" row used by the aggregate pipeline (gated by
     * [AGGREGATE_MESSAGE_ITEMS] in ChatScreen.kt). A whole assistant message
     * collapses into a single item instead of being flattened into 6-7 rows
     * (header + fragment blocks + tool run group + thinking). Stage D renders
     * THIS item via the reused AssistantMessageView.
     *
     * [T-android-cheap-equals-aggregate] Like [AssistantText], equals is
     * hand-rolled cheap: a *frozen* message (same instance every tick —
     * the ledger path reuses frozen instances by reference) returns the
     * identity-equal fast path so LazyColumn stable-skips; a *streaming*
     * message arrives as a fresh instance each emit, so `message !==` is
     * true and the row recomposes — precisely the live-tail behavior wanted
     * for the active turn. Never a char-by-char walk of content / blocks.
     */
    @Immutable
    class AssistantMessageItem(
        val messageId: String,
        val message: ChatMessage,
        /** Joined raw markdown of the whole message — selection toolbar Copy Markdown. */
        val messageMarkdown: String,
    ) : FlatChatItem() {
        override val key = "msg:$messageId"
        override val contentType = "assistantMessage"
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AssistantMessageItem) return false
            return messageId == other.messageId && message === other.message
        }
        override fun hashCode(): Int = messageId.hashCode() * 31 + System.identityHashCode(message)
    }
}

/**
 * [P0-0] The owning message id for any flat row.
 *
 * Deliberately an extension rather than an `abstract val` on [FlatChatItem]:
 * nine subclasses already expose a `messageId` property, but [UserBubble]
 * carries the id inside `message.id`. Adding an abstract member would force a
 * rename there for no behavioural gain, so the mapping lives here instead.
 *
 * Note one message flattens into MANY rows (header + text blocks + tools + …),
 * so this is intentionally many-to-one: focusing a message matches every row
 * belonging to it, which is what makes whole-message highlighting fall out for
 * free.
 */
internal fun FlatChatItem.owningMessageId(): String = when (this) {
    is FlatChatItem.UserBubble -> message.id
    is FlatChatItem.AssistantHeader -> messageId
    is FlatChatItem.AssistantText -> messageId
    is FlatChatItem.AssistantMarkdownBlock -> messageId
    is FlatChatItem.AssistantThinking -> messageId
    is FlatChatItem.AssistantToolUse -> messageId
    is FlatChatItem.AssistantToolRunGroup -> messageId
    is FlatChatItem.AssistantInfo -> messageId
    is FlatChatItem.AssistantTyping -> messageId
    is FlatChatItem.AssistantError -> messageId
    is FlatChatItem.AssistantLegacyContent -> messageId
    is FlatChatItem.AssistantMessageItem -> messageId
}

/**
 * T-streaming-side-channel: overlay any active [StreamingDelta]s on top of
 * the canonical [messages] list, producing the snapshot
 * [buildFlatChatItems] should fold over. The original [messages] list is
 * never mutated; affected entries are replaced via `copy()` so downstream
 * keying / equality stays correct. When [streaming] is empty the input is
 * returned as-is to skip the per-element walk on idle frames.
 */
/**
 * [fix/chat-render-tick-scan] Lightweight per-message fingerprint for the
 * streaming tick dirty check.
 *
 * Replaces the previous full data-class `==` across the whole message list
 * (which deep-compared every content string, toolArgs blob, error detail,
 * image URI list, ... on every 80ms tick — O(total bytes), the dominant
 * cost of the main-thread scan in long sessions).
 *
 * The fingerprint is per-message and O(1)-per-field:
 *  - Strings are compared by LENGTH only (content.length, and per tool block
 *    content.length) — cheap "has content grown" proxy, never a char walk.
 *  - Live flags (isStreaming / isAwaitingModelResponse / isQueued / error)
 *    are plain booleans.
 *  - toolBlocks reduce to a digest of (kind, status, content.length,
 *    durationMs, toolTitle) — state + growth signals without the payload
 *    bytes (toolTitle renders on the tool card, so it participates).
 *
 * Invariant: fingerprint equality ⇒ the rendering-relevant view is
 * unchanged, so the caller may skip reconcile. Any field LazyColumn actually
 * renders from a ChatMessage is covered: text growth (content/tool-block
 * lengths), live-state flips (flags + tool status), row-set changes
 * (toolBlocks.size), tool card duration ticks (durationMs). Payload fields
 * that never render (toolArgs internals, attachment URIs, errorDetail) are
 * intentionally excluded.
 *
 * Note on turn-end: the terminal snapshot can rewrite content at the same
 * length ("AAAA"→"BBBB") with an identical fingerprint — that blind spot is
 * closed by [StableChatRowLedger.reconcileAndVerifyTerminalText] on the
 * turn-end tick (content equality, not length), not by this fingerprint.
 */
internal fun lightFingerprint(messages: List<ChatMessage>): List<Any?> {
    if (messages.isEmpty()) return emptyList()
    return messages.map { m ->
        listOf(
            m.id,
            m.content.length,
            m.isStreaming,
            m.isAwaitingModelResponse,
            m.error != null,
            m.isQueued,
            m.toolBlocks.size,
            m.toolBlocks.joinToString("|") {
                "${it.kind}:${it.toolStatus?.name}:${it.content.length}:${it.durationMs}:${it.toolTitle}"
            },
        )
    }
}

internal fun mergeStreamingOverlay(
    messages: List<ChatMessage>,
    streaming: Map<String, StreamingDelta>,
    currentEpoch: Long = 0L,
): List<ChatMessage> {
    if (streaming.isEmpty()) return messages
    return messages.map { m ->
        val delta = streaming[m.id] ?: return@map m
        // [T-android-thinking-indicator-linger] Epoch filter: a delta from a
        // previous turn (cancelled-then-resent, trailing flush that survived
        // streamJob.cancel) carries an older epoch — ignore it here so it can
        // never force a stale message back to isStreaming=true, which is what
        // rendered the residual second "thinking" row.
        if (delta.epoch != currentEpoch) return@map m
        m.copy(
            content = delta.content,
            isStreaming = true,
            toolBlocks = delta.toolBlocks,
            isAwaitingModelResponse = delta.isAwaitingModelResponse,
        )
    }
}

internal fun buildFlatChatItems(
    messages: List<ChatMessage>,
    // [T-android-perf-logging] Optional — when supplied, emit a progress
    // breadcrumb every 100 messages so a low-memory repro shows which batch
    // drives the heap up. Null (default) skips all progress logging, so the
    // hot per-token streaming rebuild path stays log-free.
    sessionId: String? = null,
    // [T-android-stream-pipeline-incremental] Build rows only for
    // messages[fromIndex, size). Neighbor lookbacks (precededByUser /
    // isResumeContinuation) still read the FULL list, so a suffix built with
    // fromIndex > 0 is row-for-row identical to the same span of a full
    // build — rows never depend on later messages, only earlier ones.
    fromIndex: Int = 0,
    // Dedupe continuity for split builds: pass the key set of the frozen
    // prefix so the defensive key-collision suffixing behaves exactly as a
    // single full build would.
    seedKeys: Set<String> = emptySet(),
    // [fix/long-session-flatten-storm] Skip the expensive Pass 2 text-block
    // markdown split entirely. Used by the StableChatRowLedger's live-tail
    // reconcile path, where per-block text is ALWAYS owned by the
    // AppendOnlyMarkdownSegmenter (pass 3) and the freshly-split
    // AssistantMarkdownBlock rows would be immediately discarded by
    // `filterNot { it is AssistantMarkdownBlock }` — a full re-split paid for
    // twice, with one result thrown away. Turning it off eliminates that
    // dual-split allocation storm on every 80ms streaming tick without
    // changing any published row: non-text rows (header / tool group /
    // thinking / info / typing / error) are byte-identical either way, and
    // text rows never derive from this builder in the ledger path.
    skipTextBlocks: Boolean = false,
): List<FlatChatItem> {
    val out = mutableListOf<FlatChatItem>()
    val usedKeys = if (seedKeys.isEmpty()) mutableSetOf() else seedKeys.toMutableSet()
    fun dedupe(item: FlatChatItem): FlatChatItem {
        // Defensive: duplicated keys crash LazyColumn. If any slip through, suffix
        // a counter until unique. This should never fire if upstream dedup is correct.
        if (usedKeys.add(item.key)) return item
        var n = 2
        while (!usedKeys.add("${item.key}#$n")) n++
        return when (item) {
            is FlatChatItem.UserBubble -> FlatChatItem.UserBubble(item.message.copy(id = "${item.message.id}#$n"), item.precededByUser)
            is FlatChatItem.AssistantHeader -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantText -> FlatChatItem.AssistantText(
                messageId = "${item.messageId}#$n",
                block = item.block,
                isStreaming = item.isStreaming,
                messageMarkdown = item.messageMarkdown,
            )
            is FlatChatItem.AssistantMarkdownBlock -> FlatChatItem.AssistantMarkdownBlock(
                messageId = "${item.messageId}#$n",
                parentBlockId = item.parentBlockId,
                rawText = item.rawText,
                blockIndex = item.blockIndex,
                isLastBlockOfMessage = item.isLastBlockOfMessage,
                messageIsStreaming = item.messageIsStreaming,
                messageMarkdown = item.messageMarkdown,
            )
            is FlatChatItem.AssistantThinking -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantToolUse -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantToolRunGroup -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantInfo -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantTyping -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantError -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantLegacyContent -> FlatChatItem.AssistantLegacyContent(
                messageId = "${item.messageId}#$n",
                content = item.content,
                isStreaming = item.isStreaming,
                messageMarkdown = item.messageMarkdown,
            )
            is FlatChatItem.AssistantMessageItem -> FlatChatItem.AssistantMessageItem(
                messageId = "${item.messageId}#$n",
                message = item.message,
                messageMarkdown = item.messageMarkdown,
            )
        }
    }
    for (idx in fromIndex until messages.size) {
        val message = messages[idx]
        // [T-android-perf-logging] Per-100-message progress breadcrumb.
        // `out.size` is the running row count, so a sudden jump between two
        // progress lines localizes the heavy batch. Only fires on the
        // full-build path (sessionId != null), never per streaming token.
        if (sessionId != null && idx > 0 && idx % 100 == 0) {
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "buildFlatChatItems.progress",
                "msgIdx=$idx of=${messages.size} rowsSoFar=${out.size}",
            )
        }
        if (message.role == "user") {
            // [T-android-candidate-bubble-gap] Flag when the previous message
            // is also a user message so the bubble can add a separating top
            // gap — back-to-back candidate / queued sends otherwise have no
            // AssistantHeader between them and visually merge.
            val prevIsUser = idx > 0 && messages[idx - 1].role == "user"
            out.add(dedupe(FlatChatItem.UserBubble(message, precededByUser = prevIsUser)))
            continue
        }
        // System messages (slash-command notices, compact divider, etc.) render
        // as horizontal-divider rows — no "Minis" attribution, no card. Skip
        // the assistant header so each info block stands on its own. Mirrors
        // iOS systemDividerRow / compactDividerRow.
        val isSystem = message.role == "system"
        val joinedMarkdown = run {
            val parts = message.toolBlocks
                .filter { it.kind == "text" && it.content.isNotEmpty() }
                .joinToString("\n\n") { it.content }
            if (parts.isNotEmpty()) parts else message.content
        }
        // T83: when Resume creates a fresh assistant bubble after the user
        // stopped a streaming turn, the previous (cancelled) assistant
        // message is right before this one in the list. Visually they should
        // read as one continuous turn — suppress the duplicate "Minis"
        // header. Skip system rows when looking back since they render as
        // dividers, not as separate speaker turns. iOS achieves this by
        // reusing the existing ChatMessage in runAgentLoop(resumingAt:);
        // we reach the same end-result at the render layer.
        val prevNonSystem = (idx - 1 downTo 0).asSequence()
            .map { messages[it] }
            .firstOrNull { it.role != "system" }
        val isResumeContinuation = prevNonSystem?.role == "assistant"
        if (!isSystem && !isResumeContinuation) {
            out.add(dedupe(FlatChatItem.AssistantHeader(message.id)))
        }

        val blocks = message.toolBlocks
        val toolPillBlocks = blocks.filter { it.kind == "tool_use" }
        val lastTextIdx = blocks.indexOfLast { it.kind == "text" }
        val hasAnyTextBlock = lastTextIdx >= 0
        // Only the last cancelled tool_use in the message gets the Retry button —
        // retryLast() re-runs the whole turn, so one button is enough.
        val lastCancelledToolId = blocks.lastOrNull { it.kind == "tool_use" && it.toolStatus == ToolBlockStatus.CANCELLED }?.id
        // [T-android-tool-run-collapse] First tool_use block index — the group
        // row is emitted exactly once, at the first tool, and covers the rest.
        val firstToolIndex = blocks.indexOfFirst { it.kind == "tool_use" }

        // ─────────────────────────────────────────────────────────────────
        // [T-android-run-group-first] Emit the rows of one assistant message
        // in a FIXED order regardless of the order the model produced the
        // blocks in: thinking row → tool run card → answer text. Rows come
        // FIRST (process before result reads naturally); because thinking and
        // the tool card are collapsed by default, they never push the answer
        // around. Then the answer text, then info rows.
        //
        // [T-thinking-split-row] Thinking is emitted as ONE independent
        // AssistantThinking row (all same-message thinking blocks merged),
        // BEFORE the tool run group. This drops the old double-fold where
        // thinking was hidden inside a group that was itself collapsed: a
        // thinking turn now needs ONE tap to reveal, collapses independently
        // of the tools, and never fights the run-group expand state.
        // Default-collapsed, tap-to-expand remains the ThinkingBlock contract.
        val thinkingBlocks = blocks.filter { it.kind == "thinking" }
        if (thinkingBlocks.isNotEmpty()) {
            // The row carries ONE thinking block whose content is the merged
            // reasoning of all same-message blocks (keeps the first id for
            // stable ThinkingBlock state). Whether it's the message's last
            // *visible* block of any kind drives the streaming auto-fold
            // signal: if a sibling text/tool_use exists, thinking is already
            // "done" and the row collapses the moment that sibling arrives.
            val lastRealBlock = blocks.lastOrNull { !(it.kind == "text" && it.content.isEmpty()) }
            val thinkingIsTrailing = thinkingBlocks.any { it === lastRealBlock }
            val mergedThinking = thinkingBlocks.first().copy(
                content = thinkingBlocks.joinToString("\n") { it.content },
            )
            out.add(dedupe(FlatChatItem.AssistantThinking(
                messageId = message.id,
                block = mergedThinking,
                isLast = true,
                messageIsStreaming = message.isStreaming,
                messageThinkingLevel = message.thinkingLevel,
                isLastBlockOverall = thinkingIsTrailing,
            )))
        }

        // Tool run group — tools ONLY now (thinking lives in its own row above).
        if (firstToolIndex >= 0) {
            out.add(dedupe(FlatChatItem.AssistantToolRunGroup(
                messageId = message.id,
                tools = toolPillBlocks,
                // No thinking blocks folded in — they are a separate
                // AssistantThinking row emitted above.
                thinkingBlocks = emptyList(),
                isRunning = toolPillBlocks.any {
                    it.toolStatus == ToolBlockStatus.STREAMING ||
                        it.toolStatus == ToolBlockStatus.PENDING ||
                        it.toolStatus == ToolBlockStatus.RUNNING
                },
                isLastCancelled = lastCancelledToolId != null &&
                    lastCancelledToolId == toolPillBlocks.lastOrNull()?.id,
                messageThinkingLevel = message.thinkingLevel,
            )))
        }

        // Pass 2 — text blocks (the answer), in model order.
        if (!skipTextBlocks) {
        blocks.forEachIndexed { index, block ->
            if (block.kind != "text") return@forEachIndexed
            if (block.content.isNotEmpty()) {
                val isLastText = index == lastTextIdx
                        // Pattern A: split this text block's content into
                        // independent markdown fragments so each becomes its
                        // own LazyColumn item. Frozen prefix fragments are
                        // anchored separately by LazyList; only the trailing
                        // live fragment can change height during streaming.
                        //
                        // [T-android-defensive-fragment-merge] For a FROZEN
                        // (non-streaming) message, coalesce adjacent
                        // plain-text fragments so a long reply produces a
                        // handful of rows instead of dozens — cuts cold-open
                        // full-build row count ~8x and eases GC pressure on
                        // low-memory devices. The live streaming tail message
                        // keeps fine-grained fragments so only the trailing
                        // paragraph re-parses per token (Pattern A jank
                        // optimization preserved). Code fences stay standalone
                        // either way.
                        val rawFragments = splitMarkdownIntoBlockTexts(block.content)
                        // [T-android-stream-end-reflow-flicker-v18] Preserve
                        // per-fragment FlatChatItem keys across the
                        // streaming→idle boundary. Previously the trailing
                        // text block kept rawFragments only while
                        // `message.isStreaming==true`; the moment it flipped
                        // false the fragments coalesced into fewer rows, all
                        // mdblock:msgId:parentBlockId:N keys for N >= K
                        // suddenly vanished from the flatItems list. That
                        // wipe-and-rebuild was the "整个页面像被重刷" the
                        // user reported — LazyColumn lost every key it was
                        // using to anchor the viewport, fell back to numeric
                        // firstVisibleItemIndex, and parked the viewport on
                        // whatever row happened to take that numeric slot
                        // (often the previous assistant message).
                        //
                        // Fix: keep the live (== last in the list) text block
                        // on rawFragments regardless of isStreaming. The
                        // boundary that actually warrants coalesce is "a
                        // NEWER message exists below this one" — i.e. a
                        // subsequent user turn pushed this assistant turn
                        // into history. Until then, the same key set the
                        // user was scrolled into stays valid.
                        // [T-android-flatitems-sublist-cme] Index-based scan
                        // instead of messages.subList(idx+1, size).all{} — a
                        // subList is a live view sharing the parent's modCount,
                        // which threw ConcurrentModificationException when the
                        // backing list changed under it. A plain index loop
                        // touches no view.
                        val isLastAssistantTurn = idx == messages.lastIndex ||
                            (idx + 1 until messages.size).all { messages[it].role != "assistant" }
                        val fragments = if (isLastText && isLastAssistantTurn) {
                            rawFragments
                        } else {
                            coalesceMarkdownFragments(rawFragments)
                        }
                        if (fragments.isEmpty()) {
                            // Defensive: if the splitter returns nothing for
                            // a non-empty input (shouldn't happen), fall back
                            // to a single fragment so content isn't dropped.
                            out.add(dedupe(FlatChatItem.AssistantMarkdownBlock(
                                messageId = message.id,
                                parentBlockId = block.id,
                                rawText = block.content,
                                blockIndex = 0,
                                isLastBlockOfMessage = isLastText && message.isStreaming,
                                messageIsStreaming = message.isStreaming && isLastText,
                                messageMarkdown = joinedMarkdown,
                            )))
                        } else {
                            fragments.forEachIndexed { fragIdx, raw ->
                                val isLastFragOfText = fragIdx == fragments.lastIndex
                                out.add(dedupe(FlatChatItem.AssistantMarkdownBlock(
                                    messageId = message.id,
                                    parentBlockId = block.id,
                                    rawText = raw,
                                    blockIndex = fragIdx,
                                    isLastBlockOfMessage = isLastText && isLastFragOfText,
                                    messageIsStreaming = message.isStreaming && isLastText,
                                    messageMarkdown = joinedMarkdown,
                                )))
                            }
                        }
                    }
                }
        }
        // Pass 3 — info blocks (inline system notices), in model order.
        blocks.forEach { block ->
            if (block.kind == "info") {
                out.add(dedupe(FlatChatItem.AssistantInfo(
                    messageId = message.id,
                    block = block,
                )))
            }
        }

        // Typing indicator: show only while streaming and NO visible content
        // has arrived yet. Once any answer content is on screen (or the
        // message has any blocks), the indicator is redundant — the thinking
        // already lives in the run-group card above the answer, and an extra
        // "thinking…" row below the text reads as a stray duplicate.
        // Mirrors iOS more strictly: iOS shows it during the initial network
        // gap only (`isActiveMessage && !hasVisibleContent`); the
        // isAwaitingModelResponse window (waiting after tool results were
        // sent back) previously re-inserted the row under finished text —
        // precisely the duplicate the user wants gone.
        val hasRealBlocks = blocks.any { it.kind != "info" }
        val hasVisibleContent = hasRealBlocks || message.content.isNotEmpty()
        if (message.isStreaming && !hasVisibleContent) {
            out.add(dedupe(FlatChatItem.AssistantTyping(message.id)))
        }

        // Legacy fallback: pre-migration sessions stored all text in message.content
        // with no text-kind blocks. Render it after blocks in that case only.
        if (!hasAnyTextBlock && message.content.isNotEmpty()) {
            out.add(dedupe(FlatChatItem.AssistantLegacyContent(
                messageId = message.id,
                content = message.content,
                isStreaming = message.isStreaming,
            )))
        }

        // Inline error banner
        message.error?.let {
            out.add(dedupe(FlatChatItem.AssistantError(message.id, it, message.errorDetail)))
        }
    }
    return out
}

/**
 * [fix/message-node-item-generator] Message-level aggregate generator — the
 * stage-D counterpart of [buildFlatChatItems]. Semantic contract: **one
 * [ChatMessage] in → exactly one [FlatChatItem] out**.
 *
 *  - user role      → [FlatChatItem.UserBubble], `precededByUser` mirrors the
 *                     existing lookback (previous message is also user).
 *  - assistant role → [FlatChatItem.AssistantMessageItem] carrying the whole
 *                     message; `messageMarkdown` is the joined raw markdown
 *                     of the message's text-kind blocks (falling back to
 *                     `content`), byte-consistent with the `joinedMarkdown`
 *                     computed inside [buildFlatChatItems] so Copy Markdown
 *                     semantics stay aligned.
 *  - `isInternalBridge` messages are always skipped (defensive, mirrors the
 *    uiMessages sink's bridge filter) — a bridge must never render.
 *
 * No ledger / segmenter / skipTextBlocks machinery: the whole list is built
 * in one pass. It is the aggregate pipeline (ChatScreen AGGREGATE_MESSAGE_ITEMS)
 * and staged by stage D / the reused AssistantMessageView.
 */
internal fun buildAggregateChatItems(messages: List<ChatMessage>): List<FlatChatItem> {
    val out = mutableListOf<FlatChatItem>()
    val usedKeys = mutableSetOf<String>()
    for (idx in messages.indices) {
        val message = messages[idx]
        // [T-bridge-message-ui-leak-android] Defensive bridge filter — same
        // contract as the uiMessages sink; an internal bridge must never
        // surface as a chat bubble.
        if (message.isInternalBridge) continue
        if (message.role == "user") {
            val prevIsUser = idx > 0 && messages[idx - 1].role == "user"
            val key = "user:${message.id}"
            if (usedKeys.add(key)) {
                out.add(FlatChatItem.UserBubble(message, precededByUser = prevIsUser))
            }
            continue
        }
        // Joined raw markdown of the whole assistant message, matching the
        // joinedMarkdown computation in buildFlatChatItems (text-kind tool
        // blocks joined with blank lines, else message.content).
        val messageMarkdown = run {
            val parts = message.toolBlocks
                .filter { it.kind == "text" && it.content.isNotEmpty() }
                .joinToString("\n\n") { it.content }
            if (parts.isNotEmpty()) parts else message.content
        }
        out.add(FlatChatItem.AssistantMessageItem(
            messageId = message.id,
            message = message,
            messageMarkdown = messageMarkdown,
        ))
    }
    return out
}

/**
 * [fix/long-session-aggregate-storm] Incremental aggregate rebuild — the
 * AGGREGATE_MESSAGE_ITEMS counterpart of the ledger's frozen/live split.
 *
 * The aggregate path had NO increment: every collect tick (and every
 * pause/cancel that drains the side-channel and restarts the messages-keyed
 * effect) rebuilt the WHOLE list via [buildAggregateChatItems], O(N) per
 * tick. In a long session that is the same flatten storm the ledger path
 * already fixed — but the aggregate branch short-circuited around it.
 *
 * Because one message maps to exactly one item (bridge messages skipped),
 * the increment is simple and prefix-stable:
 *  1. Identity-prefix scan: frozen messages are the SAME instance across
 *     ticks ([mergeStreamingOverlay] only `copy()`s the streamed tail), so
 *     we find the first `===` mismatch and reuse the matching item prefix
 *     by reference.
 *  2. Full match → return `prevItems` as-is (zero allocation, zero rebuild).
 *  3. Tail mismatch → rebuild only the suffix from the first changed
 *     message, keeping every earlier item's identity so LazyColumn's
 *     key+equals skip path sees no change for already-composed rows.
 *
 * `precededByUser` lookback reads the previous RAW message (even a bridge),
 * so the suffix rebuild must know the role of the last prefix message; a
 * full `[]`/`null` prev degenerates to a plain [buildAggregateChatItems].
 */
internal fun buildAggregateChatItemsIncremental(
    prevItems: List<FlatChatItem>,
    prevMessages: List<ChatMessage>,
    messages: List<ChatMessage>,
): List<FlatChatItem> {
    // Cold start / first publish → full build.
    if (prevItems.isEmpty() || prevMessages.isEmpty()) return buildAggregateChatItems(messages)

    // 1) Identity prefix length (in MESSAGES). Frozen messages are the same
    //    instance; only the streamed/live tail (and any structural change)
    //    breaks identity.
    var prefix = 0
    val n = minOf(prevMessages.size, messages.size)
    while (prefix < n && prevMessages[prefix] === messages[prefix]) prefix++

    // 2) Everything identical → reuse by reference.
    if (prefix == prevMessages.size && prefix == messages.size) return prevItems

    // 3) Map message-prefix → item-prefix. Each non-bridge prefix message
    //    produced exactly one item, so the item prefix count = number of
    //    non-bridge messages in messages 0..<prefix. We walk prevItems and
    //    stop at the first item whose owning id is NOT in the prefix set
    //    (robust against future builder changes; ids are unique).
    val prefixIds = HashSet<String>(prefix * 2)
    for (i in 0 until prefix) prefixIds.add(prevMessages[i].id)
    var prefixItems = 0
    while (prefixItems < prevItems.size &&
        prefixIds.contains(prevItems[prefixItems].owningMessageId())
    ) {
        prefixItems++
    }

    // 4) Rebuild the suffix from messages prefix..size. Builder is pure; we only
    //    need `precededByUser` correct for the suffix's first user message,
    //    which looks back at the last prefix message.
    val suffix = buildAggregateChatItemsFrom(messages, fromIndex = prefix)
    val out = ArrayList<FlatChatItem>(prefixItems + suffix.size)
    for (i in 0 until prefixItems) out.add(prevItems[i])
    out.addAll(suffix)
    return out
}

/**
 * Build the aggregate items for messages fromIndex..size, used by the
 * incremental path's suffix rebuild. Semantics identical to
 * [buildAggregateChatItems] restricted to the suffix; `precededByUser` for
 * the suffix's first element still looks back at messages[fromIndex - 1]
 * (the caller guarantees fromIndex ≤ size; a fromIndex of 0 yields the full
 * build).
 */
private fun buildAggregateChatItemsFrom(
    messages: List<ChatMessage>,
    fromIndex: Int,
): List<FlatChatItem> {
    val out = mutableListOf<FlatChatItem>()
    val usedKeys = mutableSetOf<String>()
    for (idx in fromIndex until messages.size) {
        val message = messages[idx]
        if (message.isInternalBridge) continue
        if (message.role == "user") {
            val prevIsUser = idx > 0 && messages[idx - 1].role == "user"
            val key = "user:${message.id}"
            if (usedKeys.add(key)) {
                out.add(FlatChatItem.UserBubble(message, precededByUser = prevIsUser))
            }
            continue
        }
        val messageMarkdown = run {
            val parts = message.toolBlocks
                .filter { it.kind == "text" && it.content.isNotEmpty() }
                .joinToString("\n\n") { it.content }
            if (parts.isNotEmpty()) parts else message.content
        }
        out.add(FlatChatItem.AssistantMessageItem(
            messageId = message.id,
            message = message,
            messageMarkdown = messageMarkdown,
        ))
    }
    return out
}
