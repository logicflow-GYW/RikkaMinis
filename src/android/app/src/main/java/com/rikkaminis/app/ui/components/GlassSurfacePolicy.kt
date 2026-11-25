package com.rikkaminis.app.ui.components

import kotlin.math.abs
import com.rikkaminis.app.data.ChatTuningPrefs

/** Shared last-mile bounds and eligibility; no preference writes or second glass mode. */
object GlassSurfacePolicy {
    fun tintAlpha(percent: Int): Float = (percent / 100f).coerceIn(0f, 1f)

    // Small toolbar/menu labels need the same contrast margin as the chat title.
    fun topBarTintAlpha(percent: Int): Float = (tintAlpha(percent) + 0.25f).coerceIn(0f, 1f)

    fun blurDp(dp: Int): Int = dp.coerceIn(
        ChatTuningPrefs.COMPOSER_GLASS_BLUR_MIN,
        ChatTuningPrefs.COMPOSER_GLASS_BLUR_MAX,
    )

    fun canRecord(enabled: Boolean, sdk: Int, hasSafeSource: Boolean): Boolean =
        enabled && sdk >= 31 && hasSafeSource

    /** Compose's minimum-edge rule, applied to the visible viewport BELOW the opaque bar. */
    fun focusScrollDistance(offset: Float, size: Float, containerSize: Float, topInset: Float): Float {
        val inset = topInset.coerceIn(0f, containerSize.coerceAtLeast(0f))
        val leading = offset - inset
        val visibleSize = (containerSize - inset).coerceAtLeast(0f)
        val trailing = leading + size
        return when {
            leading >= 0f && trailing <= visibleSize -> 0f
            leading < 0f && trailing > visibleSize -> 0f
            abs(leading) < abs(trailing - visibleSize) -> leading
            else -> trailing - visibleSize
        }
    }

    /** Nested scroll reports only the LazyColumn's real consumption, including fling. */
    fun scrollOffset(previous: Float, consumedY: Float): Float = (previous - consumedY).coerceAtLeast(0f)

    fun showTopBar(hasBackdrop: Boolean, scrolledUnder: Boolean): Boolean =
        hasBackdrop && scrolledUnder

    // A custom container is an explicit caller choice, never silently replaced with glass.
    fun menuGlass(hasBackdrop: Boolean, sourceReady: Boolean, customContainer: Boolean): Boolean =
        hasBackdrop && sourceReady && !customContainer
}
