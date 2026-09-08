package com.openminis.app.ui.chat

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors

/**
 * Standardized half-screen modal sheet used by every popup launched from the
 * chat input "⋯" menu. Mirrors [CompactSummarySheet]: 90% screen height by
 * default, the same header row (optional leading action / centered title /
 * close button), 0.5dp separator, and a body slot that fills the rest. The
 * body stays independent — each call site supplies its own [content].
 *
 * Uses a compact custom drag handle: the Material3 default reserves ~22dp of
 * padding above and below the indicator, which produced too much whitespace
 * between the indicator and the title — this version tightens it to 6dp / 4dp.
 *
 * [heightFraction] lets a caller request a smaller detent — for example
 * a compact picker passes 0.5f. The fraction is clamped to (0, 1] so
 * callers can't accidentally collapse the sheet to nothing. Passing
 * `null` switches the sheet to fit-content mode: the sheet is only as
 * tall as its content, capped at the same fraction-of-screen ceiling —
 * used by [TokenUsageSheet], whose stat rows must all be visible on
 * open (a fixed half-screen detent forced scrolling to the lower
 * sections).
 *
 * [showClose] toggles the header's close button. Sheets that rely on
 * swipe-down / scrim-tap dismissal alone (e.g. [TokenUsageSheet]) pass false;
 * the reserved 48dp slot stays on both sides so the title remains centered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandardChatSheet(
    title: String,
    onDismiss: () -> Unit,
    leadingAction: (@Composable () -> Unit)? = null,
    heightFraction: Float? = 0.9f,
    showClose: Boolean = true,
    content: @Composable () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    // Fit-content (null) caps at the default 0.9 ceiling too — never flush
    // against the screen top: the chat title bar stays visible above the
    // sheet when it fully expands.
    val sheetHeight = (configuration.screenHeightDp * (heightFraction ?: 0.9f).coerceIn(0.1f, 1f)).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ChatColors.background,
        dragHandle = { CompactDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Fixed detents pin the height; fit-content (null) only
                // caps it, letting the sheet shrink-wrap shorter content
                // while still scrolling anything taller than the cap.
                .then(
                    if (heightFraction == null) {
                        Modifier.heightIn(max = sheetHeight)
                    } else {
                        Modifier.height(sheetHeight)
                    }
                ),
        ) {
            StandardChatSheetHeader(
                title = title,
                onDismiss = onDismiss,
                leadingAction = leadingAction,
                showClose = showClose,
            )
            HorizontalDivider(thickness = 0.5.dp, color = ChatColors.separator)
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}

/**
 * Slim replacement for [androidx.compose.material3.BottomSheetDefaults.DragHandle].
 * Same 32×4 indicator pill, but with 6dp top + 4dp bottom padding so the title
 * sits closer to the indicator than the Material default (22dp / 22dp).
 */
@Composable
private fun CompactDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(4.dp)
                .background(
                    color = ChatColors.secondaryText.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(2.dp),
                ),
        )
    }
}

/**
 * Shared header row used by all chat sheets — optional close button on the
 * right, centered title, and an optional leading slot. When [showClose] is
 * false the right side keeps a 48dp spacer so the title stays optically
 * centered, mirroring the left slot reserved when [leadingAction] is null.
 */
@Composable
fun StandardChatSheetHeader(
    title: String,
    onDismiss: () -> Unit,
    leadingAction: (@Composable () -> Unit)? = null,
    showClose: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingAction != null) {
            leadingAction()
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = ChatColors.primaryText,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (showClose) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.standard_sheet_close),
                    tint = ChatColors.secondaryText,
                )
            }
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }
    }
}
