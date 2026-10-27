package com.rikkaminis.app.ui.markdown

import com.rikkaminis.app.R
import com.rikkaminis.app.browser.SafeWebViewClient
import com.rikkaminis.app.ui.preview.ReleaseStep
import com.rikkaminis.app.ui.preview.WebViewReleaseLatch
import com.rikkaminis.app.ui.preview.releasePlan
import com.rikkaminis.app.ui.theme.ChatColors
import androidx.compose.ui.res.stringResource
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.LruCache
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.rikkaminis.app.logging.AppLogger

private const val TAG = "KaTeXView"

/**
 * The render token handed to the shared asset HTML (`assets/katex/katex-render.html`).
 *
 * [bridge-arity] This renderer has exactly one in-flight `renderMath` call per
 * WebView instance, so request identity is never used here — but the argument
 * must be PRESENT. Chromium resolves `@JavascriptInterface` methods by name AND
 * argument count, so the HTML's 5-argument `renderMath(..., token)` and its
 * 4-argument `AndroidBridge.onRendered(..., token)` only reach a host that
 * declares the same arity. `undefined` happens to coerce to 0 in the bridge, but
 * writing the 0 explicitly keeps the JS side and the Kotlin side visibly paired
 * at the call site. `KatexWebViewPool` passes a real monotonically increasing
 * token; this file deliberately does not, because there is no second request a
 * late callback could be misattributed to.
 */
private const val KATEX_RENDER_TOKEN = 0L

/**
 * Renders a LaTeX string using KaTeX in an offscreen WebView, capturing the result as a bitmap.
 * Uses an LRU cache to avoid re-rendering identical expressions.
 */
object KaTeXRendererCache {
    /** [width]/[height] are the bitmap's physical-pixel dimensions
     *  (CSS px * device density) so the image stays sharp on hi-DPI screens.
     *  [cssWidth]/[cssHeight] are KaTeX's reported size in CSS pixels — used
     *  as the dp size for `Image` so the formula displays at the same visual
     *  scale as surrounding text instead of the raw bitmap-pixel size. (T206) */
    data class CacheEntry(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val cssWidth: Int,
        val cssHeight: Int,
    )

    // [T10-M2] Bound the cache by BYTES, not entries. One display-mode
    // formula can be several MB of ARGB_8888 (2000x600 px ~ 4.8 MB), so the
    // old 200-entry cap allowed ~1 GB of native bitmaps while scrolling a
    // math-heavy document. sizeOf() reports KB, so maxSize is 64 MB.
    val cache = object : LruCache<String, CacheEntry>(64 * 1024) {
        override fun sizeOf(key: String, value: CacheEntry): Int =
            (value.bitmap.byteCount / 1024).coerceAtLeast(1)
    }

    /**
     * Drop every cached formula bitmap. (ARCH-03)
     *
     * [cache] is byte-budgeted but had NO reclaim path: the app's
     * memory-pressure hooks called `KatexWebViewPool.clearRenderCacheForMemoryPressure()`
     * — a different object that merely shares the "KaTeX cache" prefix in its
     * name — so up to 64 MB of native bitmaps survived `onTrimMemory(TRIM_MEMORY_COMPLETE)`
     * even though the cache is pure derived state (the next render is a cache miss
     * and simply re-renders). This is the counterpart of that pool method; both
     * caches are now cleared at every reclaim site in `MinisApp`.
     */
    fun clearForMemoryPressure() {
        runCatching { cache.evictAll() }
    }

    fun cacheKey(latex: String, displayMode: Boolean, isDark: Boolean = false): String =
        (if (displayMode) "D:" else "I:") + (if (isDark) "k:" else "l:") + latex
}

/**
 * Composable that renders a display-mode LaTeX math block.
 */
@Composable
fun MathBlockView(
    latex: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        KaTeXRenderView(latex = latex, displayMode = true)
    }
}

/**
 * Core KaTeX rendering composable. Uses a hidden WebView to render LaTeX, then captures
 * the result as a Bitmap displayed via Image composable. Falls back to monospace text on error.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KaTeXRenderView(
    latex: String,
    displayMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // [fix/render-ui F-256] ChatColors.isDark follows the in-app theme override
    // (Settings -> theme_mode); isSystemInDarkTheme() only tracks the system
    // setting, so a forced-light app on a dark system rendered formulas with
    // #fff glyphs on the light body background (invisible).
    val isDark = ChatColors.isDark
    val fontSize = 16f
    // [fix/render-ui F-256] The key must include isDark: without it the cache
    // serves a bitmap rendered for the *other* theme after a theme flip.
    // KatexWebViewPool.cacheKey already keys on isDark — same reasoning.
    val cacheKey = remember(latex, displayMode, isDark) {
        KaTeXRendererCache.cacheKey(latex, displayMode, isDark)
    }

    // Check cache first
    val cached = remember(cacheKey) { KaTeXRendererCache.cache.get(cacheKey) }

    if (cached != null) {
        // T206: bitmap is rendered at density × CSS px for sharpness, but
        // we display at CSS-pixel dp so the formula visually matches the
        // surrounding 16sp text (otherwise Image maps physical pixels 1:1
        // and the formula renders ~density× too large).
        Image(
            bitmap = cached.bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.chat_math_content_desc, latex),
            modifier = modifier.size(cached.cssWidth.dp, cached.cssHeight.dp),
        )
        return
    }

    // State for the rendered bitmap
    var renderedBitmap by remember(cacheKey) { mutableStateOf<Bitmap?>(null) }
    // T206: keep CSS size alongside the bitmap so the post-render Image
    // can size itself the same way the cache-hit path does.
    var renderedCssWidth by remember(cacheKey) { mutableStateOf(0) }
    var renderedCssHeight by remember(cacheKey) { mutableStateOf(0) }
    var renderError by remember(cacheKey) { mutableStateOf<String?>(null) }
    // [GH#341] Bumped when the offscreen renderer dies. Used as a `key` around
    // the AndroidView below so Compose discards the dead WebView and runs the
    // factory again — a fresh renderer — instead of reusing an instance whose
    // Chromium process no longer exists.
    var rendererEpoch by remember(cacheKey) { mutableStateOf(0) }

    if (renderedBitmap != null) {
        Image(
            bitmap = renderedBitmap!!.asImageBitmap(),
            contentDescription = stringResource(R.string.chat_math_content_desc, latex),
            modifier = modifier.size(renderedCssWidth.dp, renderedCssHeight.dp),
        )
    } else if (renderError != null) {
        // Fallback: show raw LaTeX in monospace
        Text(
            text = latex,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier,
        )
    } else {
        // Placeholder while rendering
        Box(
            modifier = modifier
                .then(
                    if (displayMode) Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                    else Modifier
                        .widthIn(min = 20.dp)
                        .height(20.dp)
                ),
        )

        // Render with offscreen WebView
        // [GH#341] `key` so a dead renderer actually gets replaced: bumping
        // rendererEpoch discards this AndroidView and re-runs the factory with
        // a fresh WebView, instead of reusing an instance whose Chromium
        // process is gone (every call on such an instance is undefined).
        // [dv17-3 B-03] `cacheKey` must stay in the key: `remember(cacheKey)`
        // resets `rendererEpoch` to 0 when the key changes (streaming LaTeX
        // updates / theme flips), so a bare `key(rendererEpoch)` never changes
        // (0→0) and the factory never re-runs — while `renderedBitmap` /
        // `renderError` below are `remember(cacheKey)` and *do* reset. The row
        // therefore fell into the "placeholder while rendering" branch and
        // stayed blank for the new formula, instead of rendering it.
        key(cacheKey, rendererEpoch) {
            // [deep-audit-1046 S-07] One latch per WebView instance. `key(...)`
            // discards this block — and therefore this `remember` — whenever the
            // renderer is replaced, so a released latch is never handed to the
            // next WebView.
            val releaseLatch = remember { WebViewReleaseLatch() }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(1, 1)
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = true
                        // T208 Layer A: keep WebView text-zoom at 100% regardless
                        // of the user's accessibility font-size setting. Without
                        // this, the bitmap KaTeX renders is multiplied by the
                        // system font scale, but our bridge reports the
                        // pre-scale CSS dimensions — Image then displays the
                        // (over-rendered) bitmap inside an undersized box and
                        // ContentScale.Fit makes the formula appear physically
                        // larger than the surrounding 16sp text.
                        settings.textZoom = 100
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)

                        addJavascriptInterface(object {
                            // [bridge-arity BUG-05] The asset HTML
                            // (`assets/katex/katex-render.html`) has called
                            // `AndroidBridge.onRendered(w, h, error, token)` with FOUR
                            // arguments ever since the audit-0909 T2-M3 change added the
                            // render token to the shared pool. Chromium resolves
                            // `@JavascriptInterface` members by name AND argument count,
                            // so this three-argument declaration never matched: every
                            // call died as "Method not found" *inside the page*, the
                            // `renderMath` callback threw in its `requestAnimationFrame`
                            // (outside its own try/catch), `renderedBitmap` stayed null
                            // for the whole life of the row, and each uncached formula
                            // kept its WebView alive — exactly the retention the
                            // `onRelease` teardown (audit-0909 T10-M1) exists to prevent.
                            //
                            // Both arities are declared here so a three-argument caller
                            // (none today) keeps working. Never change the count on one
                            // side only: `scripts/scan/katex_bridge_arity_check.py`
                            // mechanically cross-checks the JS call sites against the
                            // declarations and fails the build on a mismatch.
                            @JavascriptInterface
                            fun onRendered(width: Int, height: Int, error: String) {
                                onRendered(width, height, error, KATEX_RENDER_TOKEN)
                            }

                            /**
                             * Four-argument form — the one the HTML actually calls.
                             *
                             * [token] is the request identity `KatexWebViewPool` uses to
                             * ignore a late callback from a render it already gave up on.
                             * This renderer does not need it: a WebView instance here
                             * renders exactly one formula (a fresh WebView per uncached
                             * `latex`, destroyed by `onRelease` the moment the bitmap
                             * lands), so there is no second request a late callback could
                             * be attributed to. The parameter is accepted and
                             * deliberately unused rather than omitted — omitting it is
                             * what broke the bridge in the first place.
                             */
                            @JavascriptInterface
                            fun onRendered(width: Int, height: Int, error: String, token: Long) {
                                // [deep-audit-1046 S-07] The page can deliver this
                                // while the row is being torn down (the first
                                // successful render already replaced this
                                // AndroidView with an Image). Nothing past this
                                // point is meaningful on a released renderer.
                                if (!releaseLatch.canRunDeferred()) return
                                if (error.isNotEmpty()) {
                                    AppLogger.warning(TAG, "KaTeX render failed: $error · latex=${latex.take(80)}")
                                    renderError = error
                                    return
                                }
                                if (width <= 0 || height <= 0) {
                                    AppLogger.warning(TAG, "KaTeX render produced zero dimensions · latex=${latex.take(80)}")
                                    renderError = "zero dimensions"
                                    return
                                }
                                // Scale for device density
                                val scale = ctx.resources.displayMetrics.density
                                val bitmapW = (width * scale).toInt()
                                val bitmapH = (height * scale).toInt()
                                // [fix/memory-hardening-capture-cap] The capture size
                                // comes from KaTeX's JS-reported content box — bound it
                                // before allocating (see KatexCaptureLimit). A formula
                                // past the cap is drawn slightly smaller (same layout
                                // box, softer glyphs) instead of aborting the process.
                                val fit = internalFitCaptureSize(bitmapW, bitmapH)
                                if (!fit.ok) {
                                    AppLogger.warning(TAG, "KaTeX capture size rejected ${bitmapW}x$bitmapH · latex=${latex.take(80)}")
                                    renderError = "capture size"
                                    return
                                }
                                if (fit.scale < 1f) {
                                    AppLogger.warning(
                                        TAG,
                                        "KaTeX capture ${bitmapW}x$bitmapH exceeds cap — scaled to ${fit.width}x${fit.height} · latex=${latex.take(80)}"
                                    )
                                }

                                // Resize WebView to content size, then capture.
                                // [deep-audit-1046 S-07] BOTH hops are latch-
                                // guarded. This callback arrives on the JS bridge
                                // thread, and by the time either hop runs the row
                                // may already be gone (the first successful render
                                // replaces this AndroidView with an Image). Pre-fix
                                // the 100 ms block ran on the destroyed WebView:
                                // `draw()` produced an EMPTY bitmap and
                                // `KaTeXRendererCache.cache.put()` stored it under
                                // this formula's key — which is a cache HIT
                                // forever after, so the formula rendered blank for
                                // the life of the process. `onRelease`'s
                                // `removeCallbacks(null)` never stopped it: AOSP
                                // guards that method on `action != null`, i.e. the
                                // null form removes nothing at all.
                                post {
                                    if (!releaseLatch.canRunDeferred()) return@post
                                    layoutParams = ViewGroup.LayoutParams(bitmapW, bitmapH)
                                    requestLayout()
                                    // Named so `onRelease` can remove it by
                                    // identity — the only cancellation form that
                                    // actually works.
                                    val capture = Runnable {
                                        if (!releaseLatch.canRunDeferred()) return@Runnable
                                        val bitmap = Bitmap.createBitmap(fit.width, fit.height, Bitmap.Config.ARGB_8888)
                                        val canvas = android.graphics.Canvas(bitmap)
                                        if (fit.scale < 1f) canvas.scale(fit.scale, fit.scale)
                                        draw(canvas)
                                        KaTeXRendererCache.cache.put(
                                            cacheKey,
                                            KaTeXRendererCache.CacheEntry(
                                                bitmap = bitmap,
                                                width = fit.width,
                                                height = fit.height,
                                                cssWidth = width,
                                                cssHeight = height,
                                            )
                                        )
                                        renderedCssWidth = width
                                        renderedCssHeight = height
                                        renderedBitmap = bitmap
                                    }
                                    releaseLatch.track(capture)
                                    postDelayed(capture, 100)
                                }
                            }
                        }, "AndroidBridge")

                        webViewClient = object : SafeWebViewClient() {
                            /**
                             * [GH#341] The offscreen renderer died. Two things
                             * matter here:
                             *
                             * 1. Never leave the placeholder spinning forever —
                             *    that branch has no timeout, so a dead renderer
                             *    used to mean a formula that stayed a blank box
                             *    for the lifetime of the message. Fail over to the
                             *    raw-LaTeX fallback, which is what the user sees
                             *    when rendering is impossible anyway.
                             * 2. Bump [rendererEpoch] so the `key` below throws
                             *    this WebView away and builds a fresh one, letting
                             *    a later recomposition render normally again. The
                             *    AndroidView's own `onRelease` destroys the dead
                             *    instance as the key change drops it.
                             */
                            override fun onRendererGone(view: WebView?) {
                                renderError = "renderer gone"
                                rendererEpoch += 1
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val escapedLatex = latex
                                    .replace("\\", "\\\\")
                                    .replace("'", "\\'")
                                    .replace("\n", "\\n")
                                    .replace("\r", "")
                                evaluateJavascript(
                                    "renderMath('$escapedLatex', $displayMode, $fontSize, $isDark, $KATEX_RENDER_TOKEN)",
                                    null
                                )
                            }
                        }

                        loadUrl("file:///android_asset/katex/katex-render.html")
                    }
                },
                modifier = Modifier.height(0.dp), // Hidden
                // [audit-0909 T10-M1] Destroy the offscreen renderer when this
                // AndroidView leaves the composition — which happens on the very
                // first successful render, when `renderedBitmap != null` replaces
                // it with an Image. Without onRelease every uncached formula left
                // a live WebView + renderer process handle behind (no destroy()
                // anywhere in this file), accumulating for the life of the app.
                onRelease = { wv ->
                    // [audit-0917 / deep-audit-1046 S-07] Cancel the pending
                    // capture BEFORE destroying. The capture runs 100 ms after the
                    // resize post and calls draw(canvas) on this WebView — if the
                    // view left the composition in that window (renderedBitmap
                    // already replaced it, or the row scrolled away), destroy()
                    // had run and the callback drew into a destroyed WebView,
                    // caching an empty bitmap for that formula.
                    //
                    // `removeCallbacks(null)` — what this used to call, and what
                    // the comment above it claimed "drops everything queued on
                    // this view" — removes NOTHING: AOSP is
                    // `if (action != null) { … } return false;`. The runnables are
                    // now registered with the latch and removed by identity, and
                    // every deferred body carries its own latch guard.
                    //
                    // Order (releasePlan, derived so both hosts cannot drift):
                    // latch → detach → drop tracked work → stopLoading → destroy.
                    val outstanding = releaseLatch.release()
                    for (step in releasePlan(attached = wv.parent != null)) {
                        when (step) {
                            ReleaseStep.DETACH -> (wv.parent as? ViewGroup)?.removeView(wv)
                            ReleaseStep.DROP_DEFERRED -> {
                                for (task in outstanding) wv.removeCallbacks(task)
                            }
                            ReleaseStep.STOP_LOADING -> wv.stopLoading()
                            ReleaseStep.DESTROY -> wv.destroy()
                        }
                    }
                },
            )
        }
    }
}
