package com.rikkaminis.app.ui.chat

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * [composer-arc-1114] Geometry shared by the message list and the composer.
 *
 * Before this file, the list and the composer were plain siblings in a Column:
 * the LazyColumn's viewport ended exactly at the composer's top edge, so the
 * message content was terminated by a straight horizontal line and the card's
 * 20dp rounded corners only decorated the card's own background. Side by side
 * that reads as "the content is cut by a square, not by the arc".
 *
 * The fix is a real overlap: [composerArcTail] lets the list run
 * [ComposerArcOverlap] px past its own bounds so it slides underneath the
 * composer, and [ComposerArcClipShape] clips it to everything above the card
 * plus the two corner notches — the region inside each corner's bounding
 * square that lies OUTSIDE the quarter disc. Content is therefore visible
 * exactly where the card is not, i.e. the arc is what occludes it.
 *
 * These three values must match what ChatInputArea actually draws; they are
 * declared here (not duplicated there) so the two can never drift.
 */
internal val ComposerCardCornerRadius: Dp = 20.dp

/** Gap between the composer's horizontal edge and the screen edge. */
internal val ComposerCardHorizontalInset: Dp = 16.dp

/** Gap between the composer's top edge and the card's top edge. */
internal val ComposerCardTopInset: Dp = 2.dp

/**
 * How far the message list runs on underneath the composer. The corner arc
 * lives entirely inside `topInset + radius`, so that is exactly as deep as the
 * list has to go for content to show through both notches.
 */
internal val ComposerArcOverlap: Dp = ComposerCardCornerRadius + ComposerCardTopInset

/**
 * Where the arc band sits, in the clipped element's own pixel coordinates.
 *
 * [cardTop] is the card's own top edge: everything above it is visible,
 * nothing below it is — except inside the two notch squares, minus the quarter
 * disc of the card's corner.
 *
 * Note it is the CARD's top edge, not the composer column's: the column starts
 * [ComposerCardTopInset] higher (that gap is the card's top padding). Cutting
 * at the column edge instead would leave a 2dp step where the middle of the
 * content stops above the card but the corners still reach the arc.
 */
internal data class ComposerArcBand(
    val cardTop: Float,
    val leftNotch: Rect,
    val rightNotch: Rect,
    val radius: Float,
) {
    companion object {
        /**
         * @return null when the element is too small for the band to make
         * sense (e.g. a 0-height first measure, or a window narrower than both
         * corners). Callers fall back to a plain rectangle — i.e. the old
         * straight cut — rather than carving an arc out of nothing.
         */
        fun of(
            widthPx: Float,
            heightPx: Float,
            radiusPx: Float,
            horizontalInsetPx: Float,
            topInsetPx: Float,
        ): ComposerArcBand? {
            // The caller overlaps the list by `radius + topInset`, so this
            // element's bottom is the deepest point of the arcs; the card's top
            // edge sits one radius above that, i.e. exactly `topInset` below
            // the composer column's own top edge.
            val band = radiusPx + topInsetPx
            val notchTop = heightPx - band + topInsetPx
            if (notchTop <= 0f) return null
            if (widthPx < 2f * (horizontalInsetPx + radiusPx)) return null
            return ComposerArcBand(
                cardTop = notchTop,
                leftNotch = Rect(
                    horizontalInsetPx,
                    notchTop,
                    horizontalInsetPx + radiusPx,
                    notchTop + radiusPx,
                ),
                rightNotch = Rect(
                    widthPx - horizontalInsetPx - radiusPx,
                    notchTop,
                    widthPx - horizontalInsetPx,
                    notchTop + radiusPx,
                ),
                radius = radiusPx,
            )
        }
    }
}

/**
 * Gives [overlap] of extra drawing room below a composable while reporting its
 * original height, so the parent layout is untouched and the content simply
 * continues on beneath whatever is drawn next (here: the composer).
 *
 * Bails out (leaving the measure exactly as it was) when the parent hands us an
 * unbounded or too-small height — a list shorter than the band has nothing to
 * reveal anyway.
 */
internal fun Modifier.composerArcTail(overlap: Dp = ComposerArcOverlap): Modifier =
    layout { measurable, constraints ->
        val extra = overlap.roundToPx()
        val available = constraints.maxHeight
        if (extra <= 0 || available == Constraints.Infinity || available <= extra) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val extended = constraints.copy(
            minHeight = available + extra,
            maxHeight = available + extra,
        )
        val placeable = measurable.measure(extended)
        layout(placeable.width, (placeable.height - extra).coerceAtLeast(0)) {
            placeable.place(0, 0)
        }
    }

/**
 * Clip outline for the list: full width above the composer, plus the two
 * corner notches. Applied to the element that carries [composerArcTail], so the
 * outline's own height already includes the overlap band.
 */
private class ArcClipShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val band = ComposerArcBand.of(
            widthPx = size.width,
            heightPx = size.height,
            radiusPx = with(density) { ComposerCardCornerRadius.toPx() },
            horizontalInsetPx = with(density) { ComposerCardHorizontalInset.toPx() },
            topInsetPx = with(density) { ComposerCardTopInset.toPx() },
        ) ?: return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        val path = Path().apply {
            addRect(Rect(0f, 0f, size.width, band.cardTop))
            addPath(arcNotchPath(band.leftNotch, band.radius, fromLeft = true))
            addPath(arcNotchPath(band.rightNotch, band.radius, fromLeft = false))
        }
        return Outline.Generic(path)
    }
}

/**
 * Stateless, so every composition shares one instance — a fresh shape on each
 * recomposition would make `Modifier.clip` invalidate its layer every frame.
 */
internal val composerArcClipShape: Shape = ArcClipShape()

/**
 * The part of [notch] that lies outside its quarter disc: bounded by the two
 * outer edges of the square and the arc that connects them.
 *
 * The sub-path never overlaps the rect above it, so its winding direction is
 * irrelevant — `Path` fills it either way.
 */
private fun arcNotchPath(notch: Rect, radius: Float, fromLeft: Boolean): Path = Path().apply {
    if (fromLeft) {
        // 9 o'clock → 12 o'clock, i.e. the card's top-left arc.
        moveTo(notch.left, notch.top + radius)
        arcTo(
            Rect(notch.left, notch.top, notch.left + 2 * radius, notch.top + 2 * radius),
            startAngleDegrees = 180f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false,
        )
        lineTo(notch.left, notch.top)
        close()
    } else {
        // 3 o'clock → 12 o'clock, i.e. the card's top-right arc.
        moveTo(notch.right, notch.top + radius)
        arcTo(
            Rect(notch.right - 2 * radius, notch.top, notch.right, notch.top + 2 * radius),
            startAngleDegrees = 0f,
            sweepAngleDegrees = -90f,
            forceMoveTo = false,
        )
        lineTo(notch.right, notch.top)
        close()
    }
}
