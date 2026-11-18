package com.rikkaminis.app.ui.chat

/**
 * [fix/topbar-occlusion-1118c] The opaque floor that must sit UNDER the top-bar
 * glass, expressed as the one number the composable needs.
 *
 * Kept Android-free (no Compose types) on purpose: this is the only part of the
 * fix a JVM test can hold to a literal expectation, and the arithmetic IS the
 * fix.
 *
 * **THE MODEL.** A layer pair is composited with **SrcOver**, not by adding
 * alphas. Draw a floor at alpha `f` and the glass at alpha `a` on top of it, and
 * the fraction of the bar that ends up covered is
 *
 *     coverage(a) = 1 - (1 - a) * (1 - f)
 *
 * That is the quantity [TopBarGlassOcclusionTest] pins, and the first version of
 * this fix (`f = 1 - a`, kept in `fix/topbar-glass-flash-1118`) misdescribed it
 * as a sum. `f = 1 - a` gives `coverage(a) = 1 - a * (1 - a)`, whose minimum is
 * `0.75` at `a = 0.5` — on exactly the frames where the glass is half up, a
 * quarter of the raw, un-blurred message rows still painted through the bar. The
 * sum really did equal 1 at every step, so a test written against that sum was
 * green while the property it was named after never held.
 *
 * **WHY THE FLOOR IS A CONSTANT `1f`.** The floor has to block the raw rows and
 * the glass has to be the thing that supplies the blur, so whether the floor may
 * follow `a` depends on whether the blur reads the floor at all. It does not.
 * `dev.chrisbanes.haze` 1.6.10 (`ChatScreen.kt` uses `dev.chrisbanes.haze:haze:
 * 1.6.10`) samples **the declared `hazeSource`**, never the pixels below the
 * effect node:
 *
 *  - `HazeSourceNode.ContentDrawScope.draw()` records the source node's own
 *    subtree — `contentLayer.record { this@draw.drawContentSafely() }` — into
 *    `HazeArea.contentLayer`, then paints that layer (`HazeSourceNode.kt`,
 *    `draw()`);
 *  - `HazeEffectNode.updateEffect()` sets `areas = state.areas` — the areas
 *    registered by those source nodes — and its blur input layer is built by
 *    drawing each `area.contentLayer` into an offscreen layer
 *    (`BlurEffect.kt`, `drawScaledContent` / the layer recording there);
 *  - `RenderEffectBlurEffect` then applies `node.getOrCreateRenderEffect(...)`
 *    to that layer and `drawLayer(contentLayer)`; the blur-disabled fallback,
 *    `ScrimBlurEffect`, draws a scrim over the recorded layers and does not read
 *    the window either.
 *
 * The bar's glass reads the message-list layer recorded by
 * `Modifier.hazeSource(hazeState)` on the `LazyColumn` (`ChatScreen.kt`), so it
 * is independent of the floor: an opaque floor does not hide the content the
 * glass blurs. That makes the correct floor a constant `1f`. At `a = 0` the
 * glass draws nothing and the bar reads as the ordinary opaque bar (the
 * pre-glass look, which is what closes the first-frame flash); as `a` rises the
 * blurred rows fade in over the solid bar, and at `a = 1` the floor is fully
 * covered and the glass is the glass. There is no frame in which a raw row
 * shows through, and no curve to solve for.
 *
 * The `glassAlpha` parameter is kept (rather than collapsing this into a
 * constant at the call site) so the call site and the test state the same
 * decision point: which of the two layers carries the alpha. It must not read
 * `glassAlpha`.
 */
internal object TopBarGlassOcclusion {

    /**
     * Alpha of the opaque floor drawn under the top-bar glass: always `1f`.
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
     * the "flash of the content the bar was hiding" the user saw. The floor
     * closes that window without touching the layout: it is the same bar-sized
     * box, filled with the chat background, so alpha 0 reads as the ordinary
     * opaque bar and the blurred rows cross-fade in over it. Animating
     * `topBarTail` with the same alpha instead would change the list's measured
     * height every frame and jitter every row.
     */
    fun floorAlpha(glassAlpha: Float): Float = 1f
}
