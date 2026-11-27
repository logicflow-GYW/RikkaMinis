package com.rikkaminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableScope
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
 * [reorder-providers] The provider list now uses them as well (drag-to-reorder
 * with a same-section guard): the "order carries no meaning" rationale for
 * keeping it static is gone — the order is persisted via
 * ProviderRepository.reorderInstances (sortOrder = list index). The added
 * wrinkle vs the other two screens is the section structure: the provider list
 * buckets rows by providerType plus a pinned Favorites section, so its reorder
 * callback refuses cross-section moves instead of accepting any pair.
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
 * Whole-row long-press reorder for a LazyColumn item. The 2.4.0 API exposes
 * [ReorderableCollectionItemScope], not the newer ReorderableListItemScope.
 */
@Composable
internal fun ReorderableCollectionItemScope.longPressReorder(
    isDragging: Boolean,
    enabled: Boolean = true,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 0.95f else 1f,
        label = "reorder_scale",
    )
    return Modifier
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .longPressDraggableHandle(
            enabled = enabled,
            onDragStarted = {
                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            },
            onDragStopped = {
                haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
            },
        )
}

/** Whole-row long-press reorder for the 2.4.0 ReorderableColumn API. */
@Composable
internal fun ReorderableScope.longPressReorder(
    isDragging: Boolean,
    enabled: Boolean = true,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 0.95f else 1f,
        label = "reorder_scale",
    )
    return Modifier
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .longPressDraggableHandle(
            enabled = enabled,
            onDragStarted = {
                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            },
            onDragStopped = {
                haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
            },
        )
}

/**
 * Keeps reorder reachable without a visible handle. Each action calls the
 * screen's real persistence callback and returns false only when absent.
 */
internal fun Modifier.reorderAccessibilityActions(
    moveUpLabel: String,
    onMoveUp: (() -> Boolean)?,
    moveDownLabel: String,
    onMoveDown: (() -> Boolean)?,
): Modifier = semantics {
    customActions = buildList {
        onMoveUp?.let { callback ->
            add(CustomAccessibilityAction(moveUpLabel) { callback() })
        }
        onMoveDown?.let { callback ->
            add(CustomAccessibilityAction(moveDownLabel) { callback() })
        }
    }
}
