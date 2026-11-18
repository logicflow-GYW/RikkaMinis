package com.rikkaminis.app.ui.chat

/**
 * [fix/topbar-glass-flash-1118] The opaque floor that must sit UNDER the
 * top-bar glass, expressed as the one number the composable needs.
 *
 * Kept Android-free (no Compose types) on purpose: this is the only part of the
 * fix a JVM test can hold to a literal expectation, and the arithmetic IS the
 * fix — if the pair does not add up to a fully opaque bar, the reported flash
 * comes back.
 */
internal object TopBarGlassOcclusion {

    /**
     * Alpha of the opaque floor drawn under the top-bar glass: the exact
     * complement of the glass alpha.
     *
     * Why a floor at all (field report 2026-11-18, confirmed by the user on
     * device): the message list is lifted under the bar by `topBarTail`, and
     * that lift follows the *switch* (`composerGlassOn`), while the glass
     * layer's own visibility follows *scroll position* (`canScrollBackward`).
     * Between the two, opening a chat has a window where the lift is already on
     * but the glass is not: `LazyListState(0, 0)` reports
     * `canScrollBackward = false` until the list has been measured and scrolled
     * to the bottom, and `animateFloatAsState` takes its target as the INITIAL
     * value, so the first frame really is alpha 0. A
     * `graphicsLayer { alpha = 0f }` draws nothing, the bar's container is
     * transparent under the glass switch, and the lifted rows are painted raw —
     * the "flash of the content the bar was hiding" the user saw. The glass then
     * springs 0→1 over ~300 ms, so the content fades out instead of vanishing.
     *
     * The floor closes that window without touching the layout: it is the same
     * bar-sized box, filled with the chat background, so alpha 0 reads as the
     * ordinary opaque bar (exactly the pre-glass look) and any intermediate
     * value cross-fades bar → glass. Animating `topBarTail` with the same alpha
     * instead would change the list's measured height every frame and jitter
     * every row.
     */
    fun scrimAlpha(glassAlpha: Float): Float = (1f - glassAlpha).coerceIn(0f, 1f)
}
