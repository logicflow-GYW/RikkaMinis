package com.rikkaminis.app.ui.chat

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.rikkaminis.app.data.ChatTuningPrefs

/**
 * [composer-glass-1115] The numbers behind the frosted-glass surfaces, converted
 * into the values haze actually consumes, plus the two layout extents that make
 * the effect possible at all.
 *
 * These live outside the composables for one reason: the clamps and the extent
 * arithmetic are the last place a bad number can reach the render path, and a
 * JVM test can hold them to literal expectations without a Context, a GPU or a
 * device.
 *
 * `ChatTuningPrefs.readClamped` already clamps on read, but that is not the only
 * way a value arrives — `minis-config` writes the raw key, a restored backup
 * writes the raw key, and a hand-edited prefs file can hold anything. A blur
 * radius of 10000dp or an alpha of -3 would not crash; it would render a
 * composer nobody can read, which is worse to diagnose than a crash.
 */
internal object ComposerGlass {

    /**
     * Density percent → the alpha of the tint drawn OVER the blurred sample.
     * 0 = no tint at all (pure blur), 100 = the card colour covers the blur
     * completely (a solid card).
     *
     * [incr1116-B F-06] `HazeTint` is a scrim drawn ON TOP of the blurred
     * backdrop (`HazeStyle.tints` — "the HazeTints to apply to the blurred
     * content"; `backgroundColor` is the separate fill drawn BEHIND it), so
     * this alpha is opacity over the blur. The first cut of this file's KDoc
     * (and the settings copy in seven languages) described the two ends the
     * other way round, which is what the strings now match instead: the
     * percentage names how much card colour remains, not how much blur.
     */
    fun tintAlpha(percent: Int): Float = (percent / 100f).coerceIn(0f, 1f)

    /**
     * Same knob, for the TOP BAR.
     *
     * The bar carries three lines of 11-14sp text and has no scrim behind it,
     * while the composer sits under a gradient that already fades content out.
     * So the bar takes a fixed +25 points of density on top of the user's value:
     * enough that the model name stays readable over blurred bubbles, not so
     * much that the blur disappears.
     *
     * Fixed, not a second knob, on purpose: a user who tunes the density for the
     * composer would otherwise have to tune the same look twice, and the reason
     * the bar differs is a property of the bar (text size + no scrim), not a
     * preference.
     */
    fun topBarTintAlpha(percent: Int): Float = (tintAlpha(percent) + 0.25f).coerceIn(0f, 1f)

    /** Blur radius in dp, clamped to the range the Chat Tuning panel offers. */
    fun blurDp(dp: Int): Int = dp.coerceIn(
        ChatTuningPrefs.COMPOSER_GLASS_BLUR_MIN,
        ChatTuningPrefs.COMPOSER_GLASS_BLUR_MAX,
    )
}

/**
 * Gives a composable [extra] of extra drawing room ABOVE itself while reporting
 * its original height, and shifts what it draws up by the same amount.
 *
 * The mirror image of `composerArcTail` (which extends downward). Used for the
 * message list so it can run underneath the top bar: the glass can only blur
 * content that is actually recorded, and a viewport that stops at the bar's
 * bottom edge has nothing behind it.
 *
 * Bails out (leaving the measure exactly as it was) when the parent hands us an
 * unbounded or too-small height, and when [extra] is zero — which is the case
 * whenever the glass switch is off, so the no-effect build measures exactly as
 * it did before this file existed.
 */
internal fun Modifier.topBarTail(extra: Dp): Modifier = layout { measurable, constraints ->
    val extraPx = extra.roundToPx()
    val available = constraints.maxHeight
    if (extraPx <= 0 || available == Constraints.Infinity || available <= extraPx) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val extended = constraints.copy(
        minHeight = available + extraPx,
        maxHeight = available + extraPx,
    )
    val placeable = measurable.measure(extended)
    // Report the parent's height (it keeps its slot) and draw from extraPx above
    // it; the caller compensates with the same amount of top content padding so
    // no row actually moves.
    layout(placeable.width, (placeable.height - extraPx).coerceAtLeast(0)) {
        placeable.place(0, -extraPx)
    }
}
