package com.rikkaminis.app.ui.components

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rikkaminis.app.data.ChatTuningPrefs
import com.rikkaminis.app.data.ChatTuningValues
import com.rikkaminis.app.ui.chat.TopBarGlassOcclusion
import com.rikkaminis.app.ui.settings.rememberChatTuning
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** Scoped to one screen's recorded content, never a global screenshot or a navigation-root state. */
data class GlassBackdrop(val state: HazeState, val blurDp: Int, val tintPct: Int)

val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/** Preview host geometry, passed without changing the bounded-reader's frozen signature. */
data class GlassDocumentLayout(val modifier: Modifier = Modifier, val topInset: Dp = 0.dp)
val LocalGlassDocumentLayout = staticCompositionLocalOf { GlassDocumentLayout() }

/** One existing, disposed preference listener per screen, never per row or popup. */
@Composable
fun rememberGlassBackdrop(state: HazeState, hasSafeSource: Boolean): GlassBackdrop? {
    val tuning = rememberChatTuning(LocalContext.current)
    return rememberGlassBackdrop(state, tuning, hasSafeSource)
}

/** Chat already owns the tuning listener; reuse its snapshot instead of registering another. */
@Composable
fun rememberGlassBackdrop(
    state: HazeState,
    tuning: ChatTuningValues,
    hasSafeSource: Boolean,
): GlassBackdrop? = remember(
    state, tuning.composerGlassEnabled, tuning.composerGlassBlurDp,
    tuning.composerGlassTintPct, hasSafeSource,
) {
    if (GlassSurfacePolicy.canRecord(tuning.composerGlassEnabled, Build.VERSION.SDK_INT, hasSafeSource)) {
        GlassBackdrop(state, tuning.composerGlassBlurDp, tuning.composerGlassTintPct)
    } else {
        null
    }
}

@Composable
fun rememberGlassStyle(backdrop: GlassBackdrop?, base: Color): HazeStyle =
    remember(backdrop?.blurDp, backdrop?.tintPct, base) {
        HazeStyle(
            backgroundColor = base,
            tint = HazeTint(base.copy(alpha = GlassSurfacePolicy.topBarTintAlpha(backdrop?.tintPct ?: 100))),
            blurRadius = GlassSurfacePolicy.blurDp(backdrop?.blurDp ?: ChatTuningPrefs.COMPOSER_GLASS_BLUR_DEFAULT).dp,
            noiseFactor = 0f,
            fallbackTint = HazeTint(base),
        )
    }

/** Keep focus scrolling below the opaque toolbar, while retaining the platform spec when off. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberGlassBringIntoViewSpec(topInset: Dp): BringIntoViewSpec {
    val parent = LocalBringIntoViewSpec.current
    val insetPx = with(LocalDensity.current) { topInset.toPx() }
    return remember(parent, insetPx) {
        if (insetPx <= 0f) parent else object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                GlassSurfacePolicy.focusScrollDistance(offset, size, containerSize, insetPx)
        }
    }
}

/**
 * The ONE opt-in point for the experimental bring-into-view local: hosts call
 * this instead of naming `LocalBringIntoViewSpec`, so a future API graduation
 * (or deprecation) is a single edit rather than a per-screen opt-in.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProvideGlassBringIntoViewSpec(topInset: Dp, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalBringIntoViewSpec provides rememberGlassBringIntoViewSpec(topInset),
        content = content,
    )
}

/** Must be called before the toolbar, as siblings of it, never inside its source subtree. */
@Composable
fun BoxScope.GlassTopBarLayers(backdrop: GlassBackdrop?, scrolledUnder: Boolean, base: Color) {
    if (backdrop == null) return
    // Opaque at every alpha: the declared Haze source is independent of this floor.
    Box(Modifier.matchParentSize().background(base.copy(alpha = TopBarGlassOcclusion.floorAlpha(0f))))
    if (GlassSurfacePolicy.showTopBar(backdrop != null, scrolledUnder)) {
        val style = rememberGlassStyle(backdrop, base)
        Box(Modifier.matchParentSize().hazeEffect(state = backdrop.state, style = style))
    }
}
