package com.rikkaminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rikkaminis.app.R
import com.rikkaminis.app.ui.components.SectionDesign

/**
 * [reorder-unify] Shared building blocks for drag-reorderable, iOS-style
 * inset-grouped card sections rendered inside a `LazyColumn`.
 *
 * Background: `SectionCard` / `SectionDivider` (SectionDesign.kt) are
 * Column-scoped — they wrap their rows in one composable. That's fine for a
 * static list, but `ReorderableLazyListState` only sees *direct* children of
 * the LazyColumn, so any reorderable section must emit each row as its own
 * top-level `item`. These helpers repaint the same card visual on a per-row
 * basis so a run of rows still reads as one continuous panel.
 *
 * They were originally private to `AgentLoopModelsScreen` (T313). They moved
 * here when the Model Groups list became reorderable too, so both screens
 * share one implementation of the card visual, the divider, the badge, and
 * the drag handle instead of drifting apart.
 *
 * Deliberately NOT applied to the provider list: that screen buckets
 * instances by `providerType` and its order carries no meaning, so it stays
 * a static grouped list (see ProviderListScreen).
 */

/**
 * Paints one row of an inset-grouped card. Applies the screen's horizontal
 * insets, the card fill, and corner rounding only on the first/last row of
 * the run, so consecutive rows visually merge into a single panel.
 *
 * Vertical breathing room matches `SectionCard`'s inner Column padding, but
 * only on the outer edges (top of first row, bottom of last row) — inner
 * rows stay flush so the dividers land mid-panel.
 */
@Composable
internal fun Modifier.cardRow(isFirst: Boolean, isLast: Boolean): Modifier {
    val shape = when {
        isFirst && isLast -> SectionDesign.CardShape
        isFirst -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        isLast -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
        else -> RectangleShape
    }
    return this
        .padding(horizontal = SectionDesign.ScreenHorizontalPadding)
        .clip(shape)
        .background(SectionDesign.cardColor())
        .padding(
            top = if (isFirst) SectionDesign.CardInnerVerticalPadding else 0.dp,
            bottom = if (isLast) SectionDesign.CardInnerVerticalPadding else 0.dp,
        )
}

/**
 * Divider between two [cardRow] rows. Painted on the same card fill so it
 * reads as an inset divider *inside* the panel rather than a hard line
 * floating on the page background.
 */
@Composable
internal fun SectionDividerInsetCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SectionDesign.ScreenHorizontalPadding)
            .background(SectionDesign.cardColor()),
    ) {
        HorizontalDivider(
            modifier = Modifier.padding(start = SectionDesign.DividerStartInset),
            thickness = SectionDesign.DividerThickness,
            color = SectionDesign.dividerColor(),
        )
    }
}

/** Small colored badge label (e.g. "Primary", "Sub", "Group"). */
@Composable
internal fun BadgeLabel(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
        )
    }
}

/**
 * The drag affordance for a reorderable row.
 *
 * [T198] The handle MUST be an `IconButton`, not a bare `Icon`: reorderable
 * 2.4.0's `draggableHandle()` attaches a pointer-input node, and a raw Icon
 * is just vector graphics with no pointer consumer, so it never receives
 * ACTION_DOWN/MOVE. `IconButton` is Compose's clickable container and does
 * consume pointer events, which lets [handleModifier] route the gesture.
 *
 * [handleModifier] is built by the caller because
 * `ReorderableItemScope.draggableHandle()` is scope-bound; threading it in
 * keeps row composables scope-agnostic.
 *
 * Using an explicit handle (rather than making the whole row draggable) is
 * also what keeps drag-to-reorder from fighting a row's other gestures —
 * on the Model Groups list the row itself is clickable *and* wrapped in a
 * horizontal `SwipeToDismissBox` for delete.
 */
@Composable
internal fun DragHandleButton(
    handleModifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    IconButton(
        onClick = {},
        modifier = handleModifier.size(36.dp),
    ) {
        Icon(
            imageVector = Icons.Default.DragHandle,
            contentDescription = contentDescription
                ?: stringResource(R.string.model_group_detail_drag_to_reorder),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}
