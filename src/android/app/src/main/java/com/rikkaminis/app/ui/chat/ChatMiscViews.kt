package com.rikkaminis.app.ui.chat

// [T-android-split-chat] Tail composables extracted verbatim from ChatScreen.kt:
// BorderedMarkdownTable, FallbackInfoBlock, CompactSummarySheet,
// parseInlineMarkdown, rememberBrowserLiveSnapshot, ResumeBanner, SwipeToSendHint.
// Imports pruned to used symbols (2026-10-27). Externally
// called ones flipped private->internal; cluster-only ones stay private.

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import com.rikkaminis.app.R
import com.rikkaminis.app.ui.components.MinisAlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import com.rikkaminis.app.ui.theme.ChatColors
import com.rikkaminis.app.ui.components.MinisTextButton

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
