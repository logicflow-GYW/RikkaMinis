package com.rikkaminis.app.ui.chat

// [T-android-split-chat] Assistant-message + tool-pill + thinking rendering
// extracted verbatim from ChatScreen.kt: AssistantHeader, AssistantMessageView,
// BoundsTrackedBlock, InlineErrorBanner, ToolStopButton,
// formatToolDetailsForClipboard, ToolCallPill, ThinkingBlock.
// Imports pruned to used symbols (2026-10-27); externally-called ones internal.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenuItem
import com.rikkaminis.app.BuildConfig
import com.rikkaminis.app.R
import com.rikkaminis.app.ui.settings.autoExpandThinkingEnabled
import com.rikkaminis.app.ui.settings.thinkingCharCountVisibleEnabled
import com.rikkaminis.app.ui.settings.thinkingDurationVisibleEnabled
import com.rikkaminis.app.ui.components.MinisMenu
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.ui.theme.ChatColors
import com.rikkaminis.app.ui.components.MinisTextButton

@Composable
internal fun AssistantHeader() {
    // [T-soul-md] Identity header = locked ✨ sparkle gradient icon +
    // SOUL.md-driven `name`. The emoji-customization field was removed,
    // so we no longer branch on `SoulMetadata.emoji`; the icon stays the
    // canonical sparkle (iOS: sparkles SF Symbol + gradient). Only the
    // `name` field is user-customizable — defaults to the app name
    // ("RikkaMinis", see SoulMetadata.DEFAULT.name) when
    // SOUL.md is missing the field or set to the default value.
    val soulMeta by com.rikkaminis.app.agent.SoulStore.cachedMetadata.collectAsState()
    val displayName = soulMeta.name.ifBlank { com.rikkaminis.app.agent.SoulMetadata.DEFAULT.name }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            // [T-android-user-assistant-spacing-16] top=10 so the
            // User→Assistant boundary reads ~16dp: user-bubble bottom(4) +
            // LazyColumn spacedBy(2) + this top(10) = 16. The header→body gap
            // inside the turn is unaffected (that's this row's bottom=2).
            .padding(top = 10.dp, bottom = 2.dp),
    ) {
        val sparkleGradient = Brush.linearGradient(
            colors = listOf(SparkleColor1, SparkleColor2),
        )
        Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(brush = sparkleGradient, blendMode = BlendMode.SrcIn)
                },
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = displayName,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Stage D aggregate-path per-tool-call pill actions.
 *
 * [AssistantMessageView] renders a whole assistant message in one item; each
 * inner tool_use is drawn as a single [ToolCallPill] (all tools of one turn
 * collapse into ONE LazyColumn row rather than a separate flat item each).
 * The caller supplies per-block actions ─ stop / detail / rerun-from-here /
 * copy / open-terminal ─ mirroring the flat
 * [FlatChatItem.AssistantToolRunGroup] branch in ChatScreen.kt, but
 * scoped to the current tool_use block.
 *
 * A `null` return from the provider falls back to the pill's defaults (the
 * legacy call site passes null everywhere → no-op actions, exactly as before
 * this class existed).
 */

// [render-churn-1] Ambient source of per-tool progress text (shell delay
// countdown). ChatScreen provides a STABLE StateFlow reference once at the
// LazyColumn root — the reference never changes, so no scope recomposes on
// provide. Pills read it inside their running branch via [ToolProgressBadge],
// which confines the per-second recomposition to a tiny Text; completed
// blocks never subscribe. Null = no provider (legacy call sites) → pills
// fall back to the bouncing dots.
val LocalToolProgressById = staticCompositionLocalOf<StateFlow<Map<String, String>>?> { null }

/**
 * [render-churn-1] Pill-only progress readout. Reads [LocalToolProgressById]
 * inside its own composition scope so a per-second progress tick recomposes
 * ONLY this badge (a small monospace Text), never the pill, never the
 * LazyColumn row, never the message item. [fallback] is composed when no
 * progress text is present for the tool.
 */
@Composable
private fun ToolProgressBadge(
    toolId: String,
    fallback: @Composable () -> Unit,
) {
    val progressById = LocalToolProgressById.current
    if (progressById == null) {
        fallback()
        return
    }
    val progress by progressById.collectAsState()
    val text = progress[toolId]
    if (text.isNullOrEmpty()) {
        fallback()
    } else {
        Text(
            text = text,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

internal data class ToolPillActions(
    val onRetry: (() -> Unit)? = null,
    val onStop: (() -> Unit)? = null,
    val onOpenTerminalWithCommand: (String) -> Unit = {},
    val onOpenDetail: (String) -> Unit = {},
    val onRerunFromHere: (() -> Unit)? = null,
    val onCopyDetails: (() -> Unit)? = null,
)

@Composable
internal fun AssistantMessageView(
    message: ChatMessage,
    onRetry: (() -> Unit)? = null,
    // Stage D: per-tool_use-block actions for the pill (stop/detail/rerun/
    // copy/open-terminal). Null → legacy no-op pill (all defaults).
    toolPillActions: (AssistantBlock) -> ToolPillActions? = { null },
    // Stage D: "Revert Compact" action for an info block whose toolName is
    // "compact" — mirrors the flat AssistantInfo branch in ChatScreen.kt.
    onRevert: (() -> Unit)? = null,
    // [fix/zero-chunk-cancel] Non-zero while the request is out with no content
    // yet; drives the TypingIndicator elapsed counter. Default 0 keeps every
    // other caller (previews, legacy paths) rendering the plain indicator.
    awaitingResponseSinceMs: Long = 0L,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        // [fix/da1016-chat-sysinfo] A "system" row is a NOTICE, not a speaker
        // turn: slash-command confirmations (`/memory`, `/thinking`), browser
        // download progress, context-pressure/trim warnings and the compact
        // divider all arrive as `role = "system"` messages built by
        // `appendSystemInfo` (the only production writer of that role — see
        // ChatViewModel.flushPendingSysInfo). Rendering the ✨ identity header
        // above each of them put an AI attribution on rows the assistant never
        // said. The legacy flat builder already skipped the header for
        // `isSystem` rows (legacy/LegacyFlatChatBuilder.kt), but the aggregate
        // pipeline — the ONLY live one (ChatScreen aggregate-path note) —
        // renders every non-user message through this composable, so the skip
        // has to live here. Guarding here (rather than emitting a different
        // FlatChatItem) keeps the row's body identical: FallbackInfoBlock is a
        // self-contained 44dp card with its own padding, so nothing collapses.
        if (message.role != "system") {
            AssistantHeader()
        }

        // Render blocks in original order — text, thinking, and tool calls interleaved
        // exactly as they arrived in the stream (each assistant turn may contain multiple
        // text ↔ tool_use transitions, which must be preserved for coherent reading).
        val toolPillBlocks = message.toolBlocks.filter { it.kind == "tool_use" }
        val lastThinkingId = message.toolBlocks.lastOrNull { it.kind == "thinking" }?.id
        // [T-android-thinking-auto-collapse] Mirror the FlatChatItem path's
        // `isLastBlockOverall` so the legacy renderer's ThinkingBlock also
        // flips !isStreaming when a sibling block arrives (id of the last
        // block of ANY kind in the message). See ChatFlatItems builder.
        val lastBlockIdOverall = message.toolBlocks.lastOrNull()?.id
        // Backward compat: if there are no text blocks but message.content is non-empty
        // (e.g. legacy sessions saved before the text-block migration), fall back to
        // rendering message.content after all tool blocks.
        val hasAnyTextBlock = message.toolBlocks.any { it.kind == "text" }
        val lastTextBlockIndex = message.toolBlocks.indexOfLast { it.kind == "text" }
        message.toolBlocks.forEachIndexed { index, block ->
            when (block.kind) {
                "thinking" -> {
                    // T300: same per-message thinking-level gate as the
                    // FlatChatItem path. AssistantMessageView is currently
                    // unreferenced (legacy pre-FlatChatItem code) but the
                    // gate stays here so any future re-introduction
                    // doesn't silently bring back the always-render bug.
                    val effectiveLevel = message.thinkingLevel
                        ?: com.rikkaminis.app.data.model.ThinkingLevel.MEDIUM
                    if (effectiveLevel.isEnabled) {
                        // [T-android-thinking-auto-collapse] Stream signal
                        // requires THIS block to be the trailing block of
                        // any kind, not just the last thinking — see
                        // FlatChatItem path + iOS ThinkingBlockView parity.
                        val isTrailingThinking = block.id == lastBlockIdOverall
                        ThinkingBlock(
                            block,
                            isStreaming = isTrailingThinking && message.isStreaming,
                            isLast = block.id == lastThinkingId,
                        )
                    }
                }
                "info" -> {
                    FallbackInfoBlock(block, onRevert = onRevert)
                }
                "text" -> {
                    if (block.content.isNotEmpty()) {
                        val isLastTextBlock = index == lastTextBlockIndex
                        val streaming = message.isStreaming && isLastTextBlock
                        // T-android-gc-storm-issue17: defensive guard on the legacy
                        // pre-FlatChatItem path too.
                        LargeContentGuard(
                            content = block.content,
                            isStreaming = streaming,
                            stableKey = "legacy-text:${message.id}:${block.id}",
                        ) {
                            StreamingMarkdownText(
                                content = block.content,
                                // Only the trailing text block is "still streaming"; earlier
                                // text blocks (before a tool call) are frozen.
                                isStreaming = streaming,
                                // Stage G: register a stable TextShard so MinisTextKit can
                                // hit-test and select this text (long-press + copy).
                                // `block.id` is the stable pre-stream-rebuild block key —
                                // do NOT use index (streaming inserts shift indices).
                                shardId = TextShardId(
                                    messageId = message.id,
                                    shardId = block.id,
                                ),
                            )
                        }
                    }
                }
                else -> {
                    // tool_use — Stage D aggregate path wires pill actions
                    // (stop/detail/rerun/copy/open-terminal) through
                    // [toolPillActions]; legacy/no-provider retains the
                    // default no-op pill.
                    val actions = toolPillActions(block)
                    if (actions != null) {
                        ToolCallPill(
                            block = block,
                            allToolBlocks = toolPillBlocks,
                            onRetry = actions.onRetry,
                            onStop = actions.onStop,
                            onOpenTerminalWithCommand = actions.onOpenTerminalWithCommand,
                            onOpenDetail = actions.onOpenDetail,
                            onRerunFromHere = actions.onRerunFromHere,
                            onCopyDetails = actions.onCopyDetails,
                        )
                    } else {
                        ToolCallPill(block, allToolBlocks = toolPillBlocks)
                    }
                }
            }
        }

        // [fix/typing-band-live] The in-bubble typing indicator moved to the
        // persistent fixed-height status band at the transcript bottom
        // (ChatScreen) — keeping BOTH would render the indicator twice while
        // the band is active. Same condition (streaming, no visible content),
        // now derived in ChatScreen from the live trailing message.

        // Legacy fallback: render message.content when no text blocks exist (old sessions).
        if (!hasAnyTextBlock && message.content.isNotEmpty()) {
            LargeContentGuard(
                content = message.content,
                isStreaming = message.isStreaming,
                stableKey = "legacy-fallback:${message.id}",
            ) {
                StreamingMarkdownText(
                    content = message.content,
                    isStreaming = message.isStreaming,
                    // Stage G: same shard registration for the legacy fallback path
                    // (fixes select/copy on pre-text-block sessions).
                    shardId = TextShardId(
                        messageId = message.id,
                        shardId = "legacy-text",
                    ),
                )
            }
        }

        // Inline error banner (iOS: red exclamation + error text + Retry button)
        // [T-error-no-permanent-scars] Neutral style + collapsible technical
        // details — see InlineErrorBanner.
        if (message.error != null) {
            InlineErrorBanner(error = message.error, errorDetail = message.errorDetail, onRetry = onRetry)
        }
    }
}

/**
 * Wraps a per-message LazyColumn item, registering its bounds (in window
 * coordinates) into [LocalMessageBoundsRegistry] so the selection toolbar
 * can look up which message a selection rect belongs to. The slot key
 * disambiguates multiple items belonging to the same message id (e.g. a
 * message with several text blocks).
 */
@Composable
internal fun BoundsTrackedBlock(
    messageId: String,
    slotKey: String,
    markdown: String,
    content: @Composable () -> Unit,
) {
    val registry = LocalMessageBoundsRegistry.current
    Box(
        modifier = Modifier.onGloballyPositioned { coords ->
            registry?.put(messageId, slotKey, coords.boundsInWindow(), markdown)
        },
    ) {
        content()
    }
    androidx.compose.runtime.DisposableEffect(messageId, slotKey) {
        onDispose { registry?.remove(messageId, slotKey) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InlineErrorBanner(error: String, errorDetail: String? = null, onRetry: (() -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    // [T-error-no-permanent-scars] Neutral presentation: the banner is a quiet
    // notice (warning palette, small text), NOT a red alarm. The raw technical
    // detail (per-model trail, original error codes) is collapsed behind a
    // "Technical details" disclosure and only expanded on tap — it no longer
    // dominates the chat record.
    var showDetail by remember { mutableStateOf(false) }
    val bg = ChatColors.warningBg
    val fg = ChatColors.warningText
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(error))
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = error,
                color = fg,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(fg.copy(alpha = 0.15f))
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(10.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.chat_longpress_retry), color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        // [T-error-no-permanent-scars] Technical detail disclosure: collapsed by
        // default so the raw error codes never sit in the chat record; expanded
        // on tap for the user who actually wants to debug. Only present while
        // the failure is in memory (errorDetail is never persisted), so a
        // reloaded session shows just the clean summary line.
        if (!errorDetail.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showDetail = !showDetail }
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (showDetail) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = fg.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = stringResource(R.string.error_tech_detail),
                    color = fg.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            AnimatedVisibility(visible = showDetail) {
                Text(
                    text = errorDetail,
                    color = ChatColors.secondaryText,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(errorDetail)) },
                        ),
                )
            }
        }
    }
}

/**
 * T14: per-card stop affordance shown on running/streaming tool blocks.
 * Mirrors iOS `ToolCapsuleView`'s small red square that appears trailing
 * the tool title when `block.toolStatus == .running`. Tapping it routes to
 * the same global `cancelStream()` callback iOS uses for `onStop?()` —
 * iOS also has no per-tool cancellation API; the per-card button is purely
 * an affordance-discoverability win. Resume banner (T13) makes the global
 * cancel UX recoverable.
 *
 * Visual: 14×14 red rounded square (Color 0xFFFF3B30 = iOS systemRed).
 */
@Composable
private fun ToolStopButton(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.stop_tool)
    // Outer Box keeps the *layout* footprint at 18×18 (unchanged capsule width).
    // The inner clickable Box is 24×24 and overflows the outer bounds equally on
    // all sides (requiredSize ignores the parent's 18dp constraint), enlarging the
    // touch target to 24 while the visual 10×10 red square stays identical.
    Box(
        modifier = modifier.size(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .requiredSize(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(
                    onClickLabel = label,
                    onClick = onStop,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ChatColors.error),
            )
        }
    }
}

// ─── Tool Call Capsule (iOS: Capsule(), inline, tool-colored icon + title + duration) ─

/**
 * [T-android-tool-bubble-longpress-menu] Render a tool_use block as a
 * human-readable, paste-back-friendly clipboard string: the tool call
 * (name + id + pretty-printed input JSON) followed by the tool result
 * (char count + status + the result text). Input JSON is pretty-printed
 * when it parses as a JSON object/array; otherwise it's emitted verbatim
 * so a malformed / partial args string still copies usefully.
 */
internal fun formatToolDetailsForClipboard(block: AssistantBlock): String {
    val prettyInput = run {
        val raw = block.toolArgs
        if (raw.isBlank()) return@run "(none)"
        try {
            when (raw.trimStart().firstOrNull()) {
                '{' -> org.json.JSONObject(raw).toString(2)
                '[' -> org.json.JSONArray(raw).toString(2)
                else -> raw
            }
        } catch (_: Exception) {
            raw
        }
    }
    val statusLabel = when (block.toolStatus) {
        ToolBlockStatus.SUCCESS -> "success"
        ToolBlockStatus.FAILED -> "error"
        ToolBlockStatus.TIMEOUT -> "timeout"
        ToolBlockStatus.CANCELLED -> "cancelled"
        // [T-dedup-neutral-status] Copied-out details show the true reason.
        ToolBlockStatus.DEDUPLICATED -> "deduplicated (skipped identical call)"
        ToolBlockStatus.RUNNING, ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING -> "running"
        null -> "unknown"
    }
    val resultText = block.content
    return buildString {
        append("## Tool Call\n")
        append("name: ").append(block.toolName).append('\n')
        append("id: ").append(block.id).append('\n')
        append("input:\n").append(prettyInput).append('\n')
        append('\n')
        append("## Tool Result\n")
        append("(").append(resultText.length).append(" chars, ").append(statusLabel).append(")\n")
        if (resultText.isNotEmpty()) append(resultText)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolCallPill(
    block: AssistantBlock,
    allToolBlocks: List<AssistantBlock> = listOf(block),
    onRetry: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null,
    onOpenTerminalWithCommand: (String) -> Unit = {},
    // T261: detail open routes through ChatViewModel so the sheet survives
    // LazyColumn item disposal. Default no-op for the legacy
    // AssistantMessageView call site (currently dead code).
    onOpenDetail: (String) -> Unit = {},
    // [T-android-tool-bubble-longpress-menu] Long-press actions. Null
    // disables the corresponding menu item (e.g. re-run is null while
    // streaming or when there's no preceding user turn to re-run from).
    onRerunFromHere: (() -> Unit)? = null,
    onCopyDetails: (() -> Unit)? = null,
) {
    // T-android-jank-profile: this log was firing on every ToolCallPill
    // recomposition (every streaming token while a tool call is live),
    // showing up as 1.6% main thread time in profiles. Logs at composable
    // top level multiply with the number of pills × recompose rate. Gate
    // behind BuildConfig.DEBUG so production builds skip the string-build
    // entirely, and the rest of release builds don't pay for it.
    if (com.rikkaminis.app.BuildConfig.DEBUG && false) {
        android.util.Log.d("ToolChain[UI]", "ToolCallPill render: id=${block.id} name=${block.toolName} title=${block.toolTitle} status=${block.toolStatus} contentLen=${block.content.length} argsLen=${block.toolArgs.length}")
    }

    // PENDING shares RUNNING's spinner affordance — tool JSON is received but
    // execution hasn't flipped the block to RUNNING yet (brief gap). TIMEOUT
    // shares FAILED's error styling but the icon mapping distinguishes them.
    val isRunning = block.toolStatus == ToolBlockStatus.RUNNING ||
        block.toolStatus == ToolBlockStatus.STREAMING ||
        block.toolStatus == ToolBlockStatus.PENDING
    val isDone = block.toolStatus == ToolBlockStatus.SUCCESS
    val isFailed = block.toolStatus == ToolBlockStatus.FAILED ||
        block.toolStatus == ToolBlockStatus.TIMEOUT
    // [T-dedup-neutral-status] DEDUPLICATED joins the cancelled bucket: neutral
    // grey styling, NO error color — the drop was intentional, not a failure.
    val isCancelled = block.toolStatus == ToolBlockStatus.CANCELLED ||
        block.toolStatus == ToolBlockStatus.DEDUPLICATED

    val toolAccent = toolAccentColor(block.toolName)
    val toolIcon = toolIconFor(block.toolName)

    // Icon color: tool color when running/done, error/cancel colors on failure
    val iconTint = when {
        isFailed -> ToolErrorColor
        isCancelled -> ToolCancelColor
        isDone -> ToolCheckColor
        else -> toolAccent
    }

    // iOS: always shows tool-type icon, only changes color based on status
    val displayIcon = toolIcon

    // Duration text (iOS: "0.4s" format)
    val durationText = if (block.durationMs > 0 && !isRunning) {
        val seconds = block.durationMs / 1000.0
        if (seconds < 10) String.format("%.1fs", seconds)
        else String.format("%.0fs", seconds)
    } else null

    // T125: drop the spinner that used to replace the tool icon while
    // running. iOS only animates a left→right shimmer sweep across the
    // pill background and keeps the typed icon visible — the spinner
    // both fought the icon for attention and looked stylistically off
    // next to the iOS counterpart. The bottom FloatingToolStatusBar
    // still shows a CircularProgressIndicator (that is the running-tool
    // status surface, where a spinner reads correctly).
    val shimmerTranslate = if (isRunning) {
        val transition = rememberInfiniteTransition(label = "toolPillShimmer")
        transition.animateFloat(
            initialValue = -1f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2800, easing = LinearEasing),
            ),
            label = "toolPillShimmerTranslate",
        )
    } else null

    // [T-android-tool-bubble-longpress-menu] Long-press menu state, scoped
    // to this pill. The DropdownMenu is anchored to the pill via the Box
    // wrapper below so it opens beneath the tapped bubble.
    var showToolMenu by remember { mutableStateOf(false) }

    // Pill stretches up to the full row width so long titles can ellipsize
    // without pushing the duration out of view. Title takes the remaining
    // space via weight(1f), duration stays fixed-width (softWrap=false).
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).animateContentSize()) {
      Box(modifier = Modifier.weight(1f, fill = false)) {
        Row(
            modifier = Modifier
                .background(
                    ChatColors.toolCapsuleBg,
                    CircleShape,
                )
                .border(0.5.dp, ChatColors.toolBorder, CircleShape)
                .clip(CircleShape)
                .then(
                    if (shimmerTranslate != null) {
                        Modifier.drawWithContent {
                            drawContent()
                            val w = size.width
                            val band = w * 0.6f
                            val x = shimmerTranslate.value * w
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0f),
                                        Color.White.copy(alpha = 0.18f),
                                        Color.White.copy(alpha = 0f),
                                    ),
                                    start = Offset(x, 0f),
                                    end = Offset(x + band, 0f),
                                ),
                            )
                        }
                    } else Modifier,
                )
                .combinedClickable(
                    onClick = { onOpenDetail(block.id) },
                    onLongClick = if (onRerunFromHere != null || onCopyDetails != null) {
                        { showToolMenu = true }
                    } else null,
                )
                .padding(horizontal = 12.dp)
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status icon — always the typed tool icon. Color shifts to
            // reflect terminal status (success / failed / cancelled); while
            // running it stays in the tool's accent color so the user can
            // still recognize the tool at a glance.
            Icon(
                displayIcon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(14.dp),
            )

            Spacer(modifier = Modifier.width(8.dp))

            // [T-step-timestamp v2 aa8b1128] Inline HH:mm:ss prefix removed
            // — user found it visually noisy on every tool pill. Start
            // time + elapsed duration now live in the tool detail bottom
            // sheet header instead (ToolDetailSheet, this file ~line
            // 5209). formatStepTimestamp() is still defined further down
            // because the detail sheet calls it.

            // Tool title + streaming dots after title (iOS: Text + bouncing "...").
            // weight(1f) lets the title absorb leftover width, ellipsis trims overflow.
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.toolTitle.ifEmpty { block.toolName },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isRunning) {
                    // iOS streaming: "..." bouncing dots after title text.
                    // [render-churn-1] The progress badge (delay countdown)
                    // takes precedence while present — it renders in its own
                    // tiny scope so per-second ticks never recompose the
                    // pill or the message row.
                    ToolProgressBadge(toolId = block.id) {
                        StreamingDotsText()
                    }
                }
            }

            // Duration badge (iOS: monospaced gray text after title) — fixed width,
            // never compressed by the title. The HH:mm:ss start time is
            // surfaced inside the tool detail bottom sheet's bottom bar
            // instead of here — the inline pill list stays clean.
            if (durationText != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = durationText,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    softWrap = false,
                    maxLines = 1,
                )
            }
            // T168: restore per-tool stop button (reverts T117). Renders only
            // for running/streaming/pending blocks so completed pills stay
            // clean. Routes to the same global cancelStream() — there is no
            // per-tool cancellation API on either platform.
            if (isRunning && onStop != null) {
                Spacer(modifier = Modifier.width(8.dp))
                ToolStopButton(onStop = onStop)
            }
        }
        // [T-android-tool-bubble-longpress-menu] Long-press menu anchored to
        // the pill. Items mirror the user-bubble menu's style (MinisMenu +
        // DropdownMenuItem + leading icon). Each item no-ops gracefully if
        // its callback is null (re-run is gated while streaming / when no
        // preceding user turn exists).
        // [T-android-tool-menu-minwidth] Minimum width = min(220dp, screen
        // width) — the menu wants to be 220dp wide, but must never exceed the
        // device width on a narrow screen. screenWidthDp is the usable width in
        // dp; cap max to the same value so the widthIn(min,max) range is always
        // valid (min <= max) even on a sub-220dp display.
        val toolMenuWidthDp = minOf(220, LocalConfiguration.current.screenWidthDp).dp
        MinisMenu(
            expanded = showToolMenu,
            onDismissRequest = { showToolMenu = false },
            offset = androidx.compose.ui.unit.DpOffset(0.dp, 6.dp),
            modifier = Modifier.widthIn(max = toolMenuWidthDp),
            minWidth = toolMenuWidthDp,
        ) {
            if (onRerunFromHere != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tool_longpress_rerun_from_here)) },
                    onClick = { showToolMenu = false; onRerunFromHere() },
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            if (onCopyDetails != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tool_longpress_copy_details)) },
                    onClick = { showToolMenu = false; onCopyDetails() },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
      }
        // T251: removed inline Retry affordance next to cancelled/failed pills —
        // the pill's own status icon (yellow on FAILED, gray on CANCELLED) is
        // already the unified failure tip. The button was visually noisy and
        // redundant. ToolCallPill keeps the `onRetry` parameter so upstream
        // callers don't need to change; the lambda just isn't surfaced inline
        // any more. retryLast() / retryFromMessage() remain reachable from
        // other entry points (long-press menu, etc.).
        // iOS: Spacer(minLength: 0) — pill stays content-width, not full-row-width
    }
}

// ─── Tool Run Group (iOS-style collapsible "agent turn" card) ──────────────
// [T-android-tool-run-collapse] One assistant message with >= 2 tool_use
// blocks renders as a single foldable row. While any tool is live the group
// stays expanded (live progress visible); once every tool reached a terminal
// state the card auto-collapses to a one-line summary ("N tools · total"),
// exactly like the ThinkingBlock does for long deep-think content. Tapping
// the header expands it again and takes over the state.

/**
 * Header-only summary for a collapsed/expanded tool run group.
 *
 * The card is collapsed by default and NEVER auto-expands: the one-line
 * header (spinner + "Running N tools" / "Thinking…" while live) is the
 * always-visible status; the user taps to reveal thinking + tools.
 */
@Composable
internal fun ToolCallRunGroup(
    group: FlatChatItem.AssistantToolRunGroup,
    onRetry: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null,
    onOpenTerminalWithCommand: (String) -> Unit = {},
    onOpenDetail: (String) -> Unit = {},
    onRerunFromHere: (() -> Unit)? = null,
    onCopyDetails: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isRunning = group.isRunning
    // No tools → nothing to render (thinking no longer lives in this card;
    // it's a separate AssistantThinking row upstream).
    if (group.tools.isEmpty()) return
    // [render-churn-2] Stable lambdas for the pill actions that don't
    // depend on group content. The call site re-creates these closures on
    // every recomposition, and an unstable lambda parameter forces the pill
    // to recompose even when its block is frozen — which, for a 20-pill
    // run card, meant re-running every completed pill at 1Hz during tool
    // progress churn. Captured once: their semantics are identical across
    // ticks (they only touch the ViewModel / parent callbacks). onRetry /
    // onRerunFromHere stay dynamic — they genuinely depend on group state.
    // onCopyDetails must ALSO stay dynamic: the call site gates it on
    // !isStreaming, so freezing it here captures the null from the first
    // composition (a tool-run group always first composes while streaming)
    // and the Copy menu item never appears after the stream ends (T288
    // regression). No perf is lost: while streaming it is a stable null
    // (equals holds, pill skip unaffected) and the null -> non-null flip
    // at turn end recomposes the pill exactly once.
    val stableOnStop = remember { onStop }
    val stableOnOpenDetail = remember { onOpenDetail }
    val stableOnOpenTerminal = remember { onOpenTerminalWithCommand }
    // [T-android-run-group-manual] The card is collapsed by default and
    // never auto-expands, not even while running — the header itself IS the
    // live status (spinner + "Running N tools" / "Thinking…"), so an
    // auto-open would only fight the user's scroll position. The user taps
    // the header to expand, taps again to collapse; `expanded` is the single
    // source of truth.
    var expanded by remember(group.messageId) { mutableStateOf(false) }
    val effectiveExpanded = expanded

    val groupAccent = toolAccentColor("shell_execute")
    val totalDurationText = when {
        group.totalDurationMs > 0 -> {
            val seconds = group.totalDurationMs / 1000.0
            if (seconds < 10) String.format("%.1fs", seconds)
            else String.format("%.0fs", seconds)
        }
        else -> null
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        // Header — always visible; the collapsing unit is the content below.
        // [T-android-run-group-thinking] Two-line header: row 1 = icon +
        // title (tool count / thinking state) + duration + chevron; row 2
        // (completed cards with tools only) = the last tool's title as a
        // one-line summary, so a collapsed run card still says what it
        // ended on. Zero new strings — the tool title is the summary.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(ChatColors.toolCapsuleBg, RoundedCornerShape(14.dp))
                .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(14.dp))
                .clickable {
                    expanded = !expanded
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        color = groupAccent,
                        strokeWidth = 1.5.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = groupAccent,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                // With tools the header keeps the familiar tool count; a
                // thinking-only turn reads as "Thinking…" / "Deep Thinking".
                Text(
                    text = when {
                        group.tools.isNotEmpty() && isRunning -> "Running ${group.count} tools"
                        group.tools.isNotEmpty() -> "${group.count} tools"
                        isRunning -> "Thinking…"
                        else -> "Deep Thinking"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (totalDurationText != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = totalDurationText,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        softWrap = false,
                        maxLines = 1,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Icon(
                    imageVector = if (effectiveExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (effectiveExpanded) "Collapse tools" else "Expand tools",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp),
                )
            }
            // Last-step summary — only on the completed card with tools.
            // 22.dp start pad ≈ icon (14dp) + spacer (8dp): aligns the
            // summary under the row-1 title.
            if (!isRunning && group.tools.isNotEmpty()) {
                val lastTool = group.tools.lastOrNull()
                val summary = if (lastTool != null) lastTool.toolTitle.ifEmpty { lastTool.toolName } else ""
                if (summary.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = summary,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 22.dp),
                    )
                }
            }
        }

        // Pill list — only shown when expanded. AnimatedVisibility gives the
        // collapse/expand a smooth height + fade transition.
        AnimatedVisibility(
            visible = effectiveExpanded,
            enter = expandVertically(
                animationSpec = tween(durationMillis = 260),
            ) + fadeIn(animationSpec = tween(durationMillis = 200)),
            exit = shrinkVertically(
                animationSpec = tween(durationMillis = 200),
            ) + fadeOut(animationSpec = tween(durationMillis = 160)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                group.tools.forEach { block ->
                    ToolCallPill(
                        block = block,
                        allToolBlocks = group.tools,
                        onRetry = if (group.isLastCancelled && block.id == group.tools.lastOrNull()?.id) onRetry else null,
                        onStop = stableOnStop,
                        onOpenTerminalWithCommand = stableOnOpenTerminal,
                        onOpenDetail = stableOnOpenDetail,
                        onRerunFromHere = onRerunFromHere,
                        onCopyDetails = onCopyDetails,
                    )
                }
            }
        }
    }
}

// iOS-style bouncing dots (3 dots, easeInOut, staggered delay)
// [T-android-split-chat] BouncingDots / StreamingDotsText / TypingIndicator
// moved verbatim to ChatIndicators.kt (same package, now `internal`).

// ─── Thinking Block (iOS: collapsible "Deep Thinking" section, blue tint) ────

@Composable
internal fun ThinkingBlock(block: AssistantBlock, isStreaming: Boolean, isLast: Boolean = true) {
    // Per-block expand state, keyed by block.id so the user's manual toggle on
    // an earlier (finished) thinking block survives recomposition while a
    // later block is still streaming. The previous LaunchedEffect snapped
    // every non-last block back to collapsed on each `isLast` flip, which
    // fought the user's tap and produced a flicker that read as "tapping the
    // earlier block shows the streaming block's content."
    // [T-thinking-auto-expand-toggle] The initial auto-expand of a new
    // streaming block is gated on the Appearance setting (default ON =
    // historical behavior). When the user turned it off, a new streaming block
    // starts collapsed; a manual header tap still expands it (setting
    // userTouched, so neither the stream-end auto-collapse nor anything else
    // fights the user). Read once at mount — mirrors iOS ThinkingBlockView,
    // where the same UserDefaults gate sits at the one-shot auto-expand site.
    val context = LocalContext.current
    val autoExpandThinking = remember { autoExpandThinkingEnabled(context) }
    // [T-thinking-header-toggles] Which extras this row shows on its right
    // edge. Read once at mount, same convention as the auto-expand gate above:
    // a settings change lands on the next mounted row, not mid-stream.
    val showThinkingDuration = remember { thinkingDurationVisibleEnabled(context) }
    val showThinkingCharCount = remember { thinkingCharCountVisibleEnabled(context) }
    var expanded by remember(block.id) { mutableStateOf(autoExpandThinking && isLast && isStreaming) }
    var userTouched by remember(block.id) { mutableStateOf(false) }
    LaunchedEffect(block.id, isStreaming) {
        // One-shot auto-collapse when streaming for this block ends, but only
        // if the user hasn't taken control of its state yet.
        if (!isStreaming && !userTouched) expanded = false
    }
    val thinkingBlue = ChatColors.thinking
    val charCount = block.content.length
    // [T-thinking-header-toggles] Duration + char count are user-toggled; the
    // decision itself lives in a pure function (ChatFormattingUtils) so the
    // whole toggle matrix is JVM-testable.
    val headerExtras = thinkingHeaderExtras(
        showDuration = showThinkingDuration,
        showCharCount = showThinkingCharCount,
        isStreaming = isStreaming,
        durationMs = block.durationMs,
        charCount = charCount,
    )
    // [T-thinking-render-perf-android] Compose `Text` measures/lays out the
    // ENTIRE string even when only ~300dp is visible, so a 200k-char thinking
    // block froze the UI (and a per-token recomposition re-measured all 200k
    // each tick). Two tiers guard this:
    //  • > HARD_CAP: the inline scroller can't render it at all — show a
    //    "View full content" entry that opens a native TextView dialog
    //    (Android TextView handles large text far better than Compose Text).
    //  • otherwise: render only the last WINDOW chars (tail) — capping layout
    //    cost to O(WINDOW) regardless of total length.
    val thinkingWindowSize = 8000
    val thinkingHardCap = 100_000
    val overHardCap = charCount > thinkingHardCap
    var showFullContent by remember(block.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Header row — only the header reacts to taps. Mirrors iOS, where
        // .onTapGesture is on the header HStack, not the whole VStack. With
        // clickable on the outer Column, a release after dragging in the
        // inner scroller registered as a tap and toggled `expanded`.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    userTouched = true
                    // [T-thinking-render-perf-android] Over the hard cap the
                    // inline scroller is bypassed entirely; tapping the header
                    // opens the native full-content viewer instead of toggling
                    // the (never-shown) inline expansion.
                    if (overHardCap) showFullContent = true
                    else expanded = !expanded
                },
        ) {
            if (isStreaming && block.toolStatus != ToolBlockStatus.SUCCESS) {
                // iOS: ProgressView().controlSize(.mini) while streaming
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    color = thinkingBlue,
                    strokeWidth = 1.5.dp,
                )
                Spacer(modifier = Modifier.width(6.dp))
            } else {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = thinkingBlue,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            // [T-thinking-title] While this block is live the header shows the
            // model's own newest action phrase instead of the fixed label, so a
            // collapsed header answers "what is it doing right now" at a glance.
            // Frozen blocks keep the static label — see [extractThinkingTitle]'s
            // KDoc for why the title is live-only in this app.
            val thinkingTitle = remember(block.content.length, isStreaming) {
                if (isStreaming) extractThinkingTitle(block.content) else null
            }
            Text(
                text = thinkingTitle ?: stringResource(R.string.chat_deep_thinking),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = thinkingBlue,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Holds the leftover width itself (the Spacer that used to do
                // that job is gone): a long phrase ellipsizes while the duration
                // / char count and the chevron stay pinned to the right edge.
                // With a short label the layout is identical to before.
                modifier = Modifier.weight(1f),
            )
            // [T-thinking-header-toggles] Both extras are optional (Settings →
            // Appearance → Deep Thinking) and each can be absent on its own, so
            // they render independently. Duration keeps the in-session-only
            // convention: the stamp is never persisted, so a reloaded row has
            // nothing to show even with the toggle ON.
            headerExtras.duration?.let { durationLabel ->
                Text(
                    text = durationLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = thinkingBlue.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            headerExtras.charCount?.let { countLabel ->
                Text(
                    text = countLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = thinkingBlue.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            if (overHardCap) {
                // [T-thinking-render-perf-android] No expand/collapse chevron —
                // the content is too large for the inline Compose scroller.
                // Offer the native full-content viewer instead.
                Text(
                    text = stringResource(R.string.thinking_view_full),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = thinkingBlue,
                )
            } else {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = thinkingBlue.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        // Expanded content. Mirrors iOS ThinkingBlockView (AssistantBlockView.swift:648):
        // an inner scroller capped at 300dp, auto-follow to the bottom while the
        // block is streaming, manual drag at any time, and pause-on-user-scroll
        // so a user reading earlier reasoning isn't yanked back to the tail by
        // the next token.
        AnimatedVisibility(
            visible = expanded && !overHardCap,
            // Smooth height + fade on collapse/expand: without the animation
            // the block's height snaps to zero the instant streaming ends,
            // yanking the viewport (the "content suddenly jumps" feel).
            enter = expandVertically(
                animationSpec = tween(durationMillis = 220),
            ) + fadeIn(animationSpec = tween(durationMillis = 160)),
            exit = shrinkVertically(
                animationSpec = tween(durationMillis = 220),
            ) + fadeOut(animationSpec = tween(durationMillis = 160)),
        ) {
            val scrollState = rememberScrollState()
            // [T-android-thinking-inner-scroll] Pause auto-follow once the user
            // scrolls away from the bottom; resume it when they return. iOS
            // pulls the user back unconditionally — but that fights every
            // touch on Compose's smaller pause-threshold scroller, so we
            // honor the user's drag the way the outer chat list does.
            var userScrolledAway by remember(block.id) { mutableStateOf(false) }
            // [F-14 meta-audit-1028] Pausing the *scroll* wasn't enough: the
            // window below is anchored to the tail, so on every token it
            // dropped another slice from the head and re-based the whole
            // string — the lines the reader was looking at slid up out of the
            // viewport even though `scrollTo` was never called. Pin the head
            // of the visible slice for as long as the pause lasts; release it
            // (snap back to the live tail) the moment they return to the
            // bottom, which the collector below already detects via gap <= 4.
            var frozenWindowStart by remember(block.id) { mutableStateOf<Int?>(null) }
            // [T-thinking-render-perf-android] Render only the tail window so
            // Compose lays out at most `thinkingWindowSize` chars. Recompute
            // cost is O(window), not O(total). The cut snaps to the next
            // newline (within 200 chars) so we don't start mid-line.
            val tailWindowStart = remember(block.content) {
                thinkingTailWindowStart(block.content, thinkingWindowSize)
            }
            LaunchedEffect(userScrolledAway, tailWindowStart) {
                if (userScrolledAway) {
                    // Capture at pause time — without this the head would keep
                    // advancing on the very next token.
                    if (frozenWindowStart == null) frozenWindowStart = tailWindowStart
                } else if (frozenWindowStart != null) {
                    frozenWindowStart = null
                }
            }
            val isTruncated = charCount > thinkingWindowSize
            val displayContent = remember(block.content, tailWindowStart, frozenWindowStart) {
                thinkingWindowSlice(
                    content = block.content,
                    start = frozenWindowStart ?: tailWindowStart,
                    windowSize = thinkingWindowSize,
                    frozen = frozenWindowStart != null,
                )
            }
            LaunchedEffect(scrollState, block.id) {
                snapshotFlow {
                    Triple(
                        scrollState.value,
                        scrollState.maxValue,
                        scrollState.isScrollInProgress,
                    )
                }.collect { (v, max, dragging) ->
                    // A nonzero gap from the bottom while the user is actively
                    // dragging counts as "they took control". We don't flip
                    // back until the gap closes — gives them room to scroll
                    // up briefly without ping-ponging.
                    val gap = (max - v).coerceAtLeast(0)
                    when {
                        dragging && gap > 4 -> userScrolledAway = true
                        gap <= 4 -> userScrolledAway = false
                    }
                }
            }
            // [F-1 sweep1013] `block` is a plain parameter, so the old
            // snapshotFlow { block.content.length } registered no snapshot
            // dependency and fired only once — the live tail froze after the
            // first tick. rememberUpdatedState hands the closure a State whose
            // .value tracks every recomposition, so content growth is
            // observable again (same fix as StreamingMarkdownText f255; this
            // is the second family occurrence).
            val currentBlock by rememberUpdatedState(block)
            LaunchedEffect(scrollState, block.id, isStreaming) {
                if (!isStreaming) return@LaunchedEffect
                snapshotFlow { currentBlock.content.length }
                    .collect {
                        if (userScrolledAway) return@collect
                        // scrollTo (not animateScrollTo) — animating fights
                        // back-to-back token ticks; iOS uses a 0.15s linear
                        // animation, but Compose's animateScrollTo cancels
                        // any in-flight scroll, so streaming bursts get
                        // jankier than a direct snap.
                        scrollState.scrollTo(scrollState.maxValue)
                    }
            }
            Column(
                modifier = Modifier
                    .padding(top = 6.dp)
                    // Expanded content keeps the tinted container (moved here
                    // from the outer Column, which is now a plain one-line
                    // header when collapsed — the lightweight form).
                    .background(thinkingBlue.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                    .border(0.5.dp, thinkingBlue.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .heightIn(max = 300.dp)
                    .verticalScroll(scrollState),
            ) {
                if (isTruncated) {
                    Text(
                        text = stringResource(
                            R.string.thinking_truncated_hint,
                            displayContent.length / 1000,
                            charCount / 1000,
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                Text(
                    text = displayContent,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    lineHeight = 19.sp,
                )
            }
        }

        // [T-thinking-render-perf-android] Hard-cap full-content viewer. A
        // native TextView (selectable, scrollable) renders arbitrarily large
        // thinking text without the Compose `Text` measure freeze.
        if (overHardCap && showFullContent) {
            ThinkingFullContentDialog(
                content = block.content,
                onDismiss = { showFullContent = false },
            )
        }
    }
}

/**
 * [T-thinking-render-perf-android] Full-screen viewer for thinking content
 * that exceeds the inline hard cap. Wraps a native Android [android.widget.TextView]
 * (inside a scroller) — it lays out very large strings far more cheaply than
 * Compose `Text`, and stays selectable.
 */
@Composable
private fun ThinkingFullContentDialog(content: String, onDismiss: () -> Unit) {
    val textColor = MaterialTheme.colorScheme.onSurface
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.chat_deep_thinking),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ChatColors.link,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    MinisTextButton(onClick = onDismiss) {
                        Text(text = stringResource(android.R.string.ok))
                    }
                }
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        android.widget.ScrollView(ctx).apply {
                            addView(
                                android.widget.TextView(ctx).apply {
                                    textSize = 13f
                                    setTextColor(textColor.toArgb())
                                    setTextIsSelectable(true)
                                    setPadding(36, 24, 36, 48)
                                    text = content
                                }
                            )
                        }
                    },
                    // [dv19-B02] The factory runs once, so a dialog opened
                    // while the block was still streaming (or after a theme
                    // flip) kept the text and colour it was born with. Sync
                    // them here instead of keying the node on `content`: the
                    // TextView holds >100k chars in this code path and
                    // rebuilding it on every appended token would reset the
                    // reader's scroll offset and text selection.
                    update = { scrollView ->
                        val textView = scrollView.getChildAt(0) as? android.widget.TextView
                        if (textView != null) {
                            textView.setTextColor(textColor.toArgb())
                            // Setting the same text again forces a full
                            // re-measure/re-layout of the whole buffer, and
                            // this block re-runs on every recomposition. Only
                            // write when it actually changed (TextView keeps
                            // the exact CharSequence it was handed, so a
                            // length check settles the common no-op case).
                            if (shouldSyncFullContentText(textView.text, content)) {
                                textView.text = content
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

/**
 * [dv19-B02] Whether the full-content thinking [android.widget.TextView] must
 * be handed the text again.
 *
 * `AndroidView.update` runs on every recomposition, and this dialog is opened
 * precisely when the block is huge (>100k chars). Re-assigning an identical
 * string makes the TextView re-measure and re-layout the whole buffer, so the
 * `update` block must only write on a real change — while still picking up
 * every appended token and every theme flip (the reason `update` exists here
 * at all instead of `key(content)`, which would rebuild the node and reset
 * the reader's scroll offset and selection).
 *
 * Kept as a plain function so the decision is unit-testable without an
 * Android runtime.
 */
internal fun shouldSyncFullContentText(shown: CharSequence?, content: String): Boolean {
    if (shown == null) return true
    if (shown.length != content.length) return true
    return shown.toString() != content
}

// ─── [F-14 meta-audit-1028] thinking window slicing ─────────────────────────

/** How far past the window start we'll look for a newline before giving up and
 *  cutting mid-line. Matches the pre-F-14 inline behaviour (200 chars). */
internal const val THINKING_WINDOW_NEWLINE_SLACK = 200

/**
 * Start offset of the tail-following window over [content]: the last
 * [windowSize] characters, snapped forward to the next newline when one is
 * close enough that the reader never sees a half line at the top.
 *
 * Returns 0 when the content already fits (nothing to trim).
 */
internal fun thinkingTailWindowStart(content: String, windowSize: Int): Int {
    if (windowSize <= 0 || content.length <= windowSize) return 0
    val start = content.length - windowSize
    val nl = content.indexOf('\n', start)
    return if (nl in start until start + THINKING_WINDOW_NEWLINE_SLACK) nl + 1 else start
}

/**
 * Visible slice of [content] for a window anchored at [start].
 *
 * - `frozen == false` (following the tail): everything from [start] onward, so
 *   the window keeps growing at the bottom as tokens arrive.
 * - `frozen == true` (the reader scrolled away): exactly [windowSize] chars from
 *   [start], so appended tokens can't re-base the string under them. Without
 *   the upper bound the slice would still lengthen on every token, and the
 *   head — which is what we're protecting — would stay pinned while the
 *   content the reader is watching drifted upward.
 *
 * [start] is clamped, so a stale frozen offset (the block shrank) can't throw.
 */
internal fun thinkingWindowSlice(
    content: String,
    start: Int,
    windowSize: Int,
    frozen: Boolean = false,
): String {
    if (content.isEmpty()) return content
    val from = start.coerceIn(0, content.length)
    if (!frozen) return content.substring(from)
    val size = if (windowSize > 0) windowSize else content.length
    return content.substring(from, minOf(content.length, from + size))
}
