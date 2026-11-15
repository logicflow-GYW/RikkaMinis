package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.ChatTuningPrefs

/**
 * [composer-glass-1115] The two Chat Tuning numbers behind the frosted-glass
 * composer card, converted into the values haze actually consumes.
 *
 * These live outside the composable for one reason: the clamp is the last place
 * a bad number can reach the render path, and a JVM test can hold it to literal
 * expectations without a Context, a GPU or a device.
 *
 * `ChatTuningPrefs.readClamped` already clamps on read, but that is not the only
 * way a value arrives — `minis-config` writes the raw key, a restored backup
 * writes the raw key, and a hand-edited prefs file can hold anything. A blur
 * radius of 10000dp or an alpha of -3 would not crash; it would just render a
 * composer nobody can read, which is worse to diagnose than a crash.
 */
internal object ComposerGlass {

    /**
     * Density percent → the alpha of the tint drawn OVER the blurred sample.
     * 0 = the card keeps its full colour (blur invisible), 100 = pure blur.
     */
    fun tintAlpha(percent: Int): Float = (percent / 100f).coerceIn(0f, 1f)

    /** Blur radius in dp, clamped to the range the Chat Tuning panel offers. */
    fun blurDp(dp: Int): Int = dp.coerceIn(
        ChatTuningPrefs.COMPOSER_GLASS_BLUR_MIN,
        ChatTuningPrefs.COMPOSER_GLASS_BLUR_MAX,
    )
}
