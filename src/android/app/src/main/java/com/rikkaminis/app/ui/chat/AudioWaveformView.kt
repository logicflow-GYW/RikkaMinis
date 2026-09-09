package com.rikkaminis.app.ui.chat

// [fix/audit-b22 / T2-L3] RUNTIME-DEAD: zero callers anywhere in the app tree
// (the voice-input waveform that consumed it was removed). Kept only until the
// next cleanup pass; do not wire it into a new screen without re-checking.

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Horizontal row of animated bars driven by a rolling RMS window.
 *
 * Mirrors iOS `AudioWaveformView.swift`: each bar animates between a small
 * resting height and the container height proportional to its normalized
 * level. The newest sample lands on the right so the waveform reads as a
 * "now" indicator instead of a scrolling history.
 */
@Composable
fun AudioWaveformView(
    levels: List<Float>,
    modifier: Modifier = Modifier,
    barColor: Color,
    heightDp: Int = 28,
    barSpacingDp: Int = 2,
) {
    val animatedLevels = levels.map { level ->
        val animated by animateFloatAsState(
            targetValue = level.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 100),
            label = "waveformBar",
        )
        animated
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp),
    ) {
        val barCount = animatedLevels.size
        if (barCount == 0) return@Canvas

        val totalSpacing = barSpacingDp.dp.toPx()
        val barWidth = ((size.width - totalSpacing * (barCount - 1)) / barCount).coerceAtLeast(1f)
        val maxBarHeight = size.height
        val minBarHeight = 3.dp.toPx()
        val cornerRadiusPx = barWidth / 2f

        animatedLevels.forEachIndexed { index, level ->
            val barHeight = (minBarHeight + level * (maxBarHeight - minBarHeight))
                .coerceIn(minBarHeight, maxBarHeight)
            val x = index * (barWidth + totalSpacing)
            val y = (size.height - barHeight) / 2f
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
            )
        }
    }
}
