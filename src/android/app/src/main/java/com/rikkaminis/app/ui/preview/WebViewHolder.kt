package com.rikkaminis.app.ui.preview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.webkit.WebViewAssetLoader
import com.rikkaminis.app.browser.SafeWebViewClient
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.ui.chat.decodeUriPath
import com.rikkaminis.app.ui.sandbox.encodeFileUrlPath
import java.io.File

/**
 * Holds a single WebView instance + the page-state observed from it.
 * Mirrors iOS [WebPreviewSheet.swift] `WebViewHolder` (L20-160) so the
 * same UX contracts hold:
 *
 *  - The WebView is created lazily but exactly once per holder. Compose
 *    can recompose freely without churning the view (which would lose
 *    scroll position and force a reload).
 *  - `pageTitle` / `currentUrl` / `isLoading` are Compose state, so any
 *    composable reading them recomposes when WebView reports progress.
 *  - The same holder is **shared** across the bottom-sheet preview and
 *    the fullscreen variant — that's how iOS makes the expand toggle
 *    feel instant. Tearing down + recreating the WebView would reload
 *    the page, breaking that illusion.
 */
class WebViewHolder(
    appContext: Context,
    initialUrl: String,
) {

    var pageTitle by mutableStateOf("")
        private set
    var currentUrl by mutableStateOf(initialUrl)
        private set
    var isLoading by mutableStateOf(true)
        private set
    var desktopMode by mutableStateOf(false)
        private set
    var pageFavicon by mutableStateOf<Bitmap?>(null)
        private set
    /**
     * [GH#341] Set when the WebView's renderer process dies. The app survives
     * (that is [SafeWebViewClient]'s contract), but [webView] is unusable from
     * then on, and this holder has no way to rebuild itself — it owns exactly
     * one WebView instance and its [assetLoader] is bound to the initial URL.
     * Hosts should read this flag and surface the failure instead of showing a
     * permanently blank preview.
     */
    var rendererGone by mutableStateOf(false)
        private set

    private var mobileUserAgent: String = ""

    /**
     * For file:// URLs, use WebViewAssetLoader to serve the HTML and its
     * sibling resources from the file's parent directory under the secure
     * `https://appassets.androidplatform.net/` origin. This prevents the
     * WebView from having direct file:// access to the filesystem.
     */
    private val assetLoader: WebViewAssetLoader?

    /**
     * [da1020-D] Absolute path of the directory [assetLoader] serves, or null
     * when this holder does not serve local files. The loader's
     * `InternalStoragePathHandler` root is the initial file's parent directory,
     * so [fromAssetLoaderUrl] needs that same root to map an
     * `https://appassets.androidplatform.net/…` URL back to the `file://` URL
     * the host actually asked for.
     */
    private val assetRootDir: String?

    init {
        // [fix/fileurl-contract] `initialUrl` is a percent-ENCODED `file://` URL
        // (construction contract: anything that crosses to a system component —
        // here the WebView and the asset loader — is encoded). Everything below
        // is filesystem semantics, so the URL is decoded back to a raw path
        // first: `File("…/%E6%88%91…")` would name a directory that does not
        // exist, and the loader would then serve nothing.
        val parent = if (initialUrl.startsWith("file://")) {
            File(decodeUriPath(initialUrl.removePrefix("file://"))).parentFile
        } else null
        assetRootDir = parent?.absolutePath
        @Suppress("DEPRECATION")
        assetLoader = if (parent != null) {
            WebViewAssetLoader.Builder()
                .addPathHandler("/", WebViewAssetLoader.InternalStoragePathHandler(appContext, parent))
                .build()
        } else null
    }

    @SuppressLint("SetJavaScriptEnabled")
    val webView: WebView = WebView(appContext).apply {
        settings.javaScriptEnabled = true       // sandbox HTML demos rely on JS
        settings.domStorageEnabled = true
        // [T-audit-p1-1] File access is DISABLED in the preview WebView.
        // Local HTML files are served via WebViewAssetLoader under the
        // secure `https://appassets.androidplatform.net/` origin, which
        // only exposes the file's parent directory — not the entire
        // filesystem. The earlier `allowFileAccessFromFileURLs` and
        // `allowUniversalAccessFromFileURLs` flags have been removed, so
        // a malicious HTML page can no longer read sibling files outside
        // its parent directory or exfiltrate them via fetch/XHR.
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        // T-htmlpreview-2d5c4f3d: pages that use viewport units (`100vh` /
        // `height: 100%`) combined with `overflow: hidden` would render
        // blank inside the bottom-sheet preview, because the WebView starts
        // life detached (created with the Application context — no window)
        // and loadUrl can fire before Compose attaches it. With no
        // metaviewport handling, Blink resolves CSS viewport units against
        // a zero container, collapsing `body { height: 100vh; overflow:
        // hidden }` to a 0-height clipped box that the resize after attach
        // never re-expands.
        //
        // Turning on useWideViewPort + loadWithOverviewMode tells WebView
        // to honor the page's <meta viewport> (or fall back to 980 CSS px
        // when missing) and shrink-to-fit, which decouples the CSS viewport
        // from the initial measured size and makes 100vh resolve against
        // the metaviewport instead. Matches the WebApp path (WebAppActivity)
        // implicitly via WebViewAssetLoader's defaults.
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        mobileUserAgent = settings.userAgentString
        // T-android-webview-v3-port: enable first- + third-party cookies so
        // the in-chat preview matches Chrome cookie semantics. Without
        // third-party cookies the embedded captcha/auth iframes that
        // production sites use (hCaptcha, Cloudflare Turnstile, OAuth
        // popups) silently drop tokens — the same flow the user clears in
        // a real browser tab fails inside our preview. WebView ships with
        // 3P cookies disabled by default; first-party defaults to true on
        // every Android version we support, but we set both explicitly so
        // the intent is grep-able. `this` here is the outer
        // WebView(appContext).apply{…} receiver — capture it locally so
        // the nested CookieManager.apply{} stays readable.
        val wv = this
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        webViewClient = object : SafeWebViewClient() {
            /**
             * [GH#341] Renderer died. [assetLoader] is bound to this holder's
             * initial URL, so recovery is not a matter of re-running a load —
             * the holder has no construction path for a replacement instance.
             * Flag it for the host and stop pretending the page is live.
             */
            override fun onRendererGone(view: WebView?) {
                rendererGone = true
                isLoading = false
                // Deliberately NOT destroying the instance: `webView` is a val
                // on this holder and hosts keep calling into it (reload,
                // desktop-mode toggle, detach). A destroyed WebView throws on
                // every one of those, which is worse than an inert one — the
                // holder's own [destroy] stays the single teardown path.
                //
                // ponytail: 只置标志，不重建 holder | 天花板: 该预览永久空白，
                // 重新打开预览才会拿到新 holder（rememberWebViewHolder 以 url 为
                // key）| 升级触发: 真机日志出现 renderer gone 且用户在预览里看到
                // 空白却无法通过重开恢复，届时给 holder 加"重建 webView 实例"的
                // 工厂（需要把 assetLoader 的构建抽成函数）。
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): android.webkit.WebResourceResponse? {
                val url = request.url ?: return null
                // For file:// URLs, serve via the asset loader which only
                // exposes the file's parent directory under the secure
                // appassets origin. For https://appassets.androidplatform.net/
                // URLs, the asset loader also serves the local files.
                // Non-file, non-appassets URLs pass through to the network.
                if (url.scheme == "file" || (url.scheme == "https" && url.host == ASSET_LOADER_HOST)) {
                    return assetLoader?.shouldInterceptRequest(url)
                }
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val urlStr = request.url?.toString()
                // T234: Google permanently disallows WebView for sign-in /
                // OAuth. Hand auth-domain navigation to Chrome Custom Tab.
                if (com.rikkaminis.app.browser.GoogleAuthRouter.shouldRouteExternally(urlStr)) {
                    if (urlStr != null) {
                        com.rikkaminis.app.browser.GoogleAuthRouter.openInCustomTab(view.context, urlStr)
                    }
                    return true
                }
                // T134: intent:// / market:// / tel: / mailto: get routed
                // out instead of trying to load as a page.
                return com.rikkaminis.app.ui.browser
                    .BrowserExternalSchemeHandler
                    .handle(view.context, request.url)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                isLoading = true
                // [da1020-D] The WebView reports the URL it is really loading,
                // which for local HTML is the asset-loader origin
                // (`https://appassets.androidplatform.net/<name>`) — NOT the
                // `file://` URL this holder was constructed with. Publishing it
                // here silently broke three consumers that key off the
                // user-visible URL:
                //   * bottom-sheet / fullscreen "open in browser" — the
                //     FileProvider branch is gated on `startsWith("file://")`,
                //     so it degraded to a meaningless ACTION_VIEW of an internal
                //     URL (the page opened by nothing);
                //   * the "pin to home" entry point, gated the same way, so it
                //     disappeared as soon as the page started loading;
                //   * ChatScreen's holder-reuse check (`existing.currentUrl !=
                //     url`), which compared appassets vs `file://` and therefore
                //     destroyed + rebuilt the holder on every repeat tap,
                //     dropping scroll position and typed form state.
                // Keep `currentUrl` a single-sourced, user-meaningful URL: map
                // back to `file://` when the WebView is on our asset-loader
                // origin, otherwise keep whatever the WebView reports.
                currentUrl = fromAssetLoaderUrl(url, assetRootDir) ?: url
                AppLogger.debug(TAG, "onPageStarted url=${url.take(160)}")
            }

            override fun onPageFinished(view: WebView, url: String) {
                isLoading = false
                pageTitle = view.title.orEmpty()
                AppLogger.debug(TAG, "onPageFinished title=${pageTitle.take(60)}")
                // T-htmlpreview-resize: WebView commits its first layout
                // against whatever viewport height the container had at
                // loadUrl-time. If that height was a transient pre-animation
                // value (bottom-sheet still expanding, IME inset not yet
                // settled), Blink resolves `100vh` / `height:100%` against
                // it once and never re-evaluates — pages with
                // `overflow:hidden` collapse to a clipped 0-height box
                // (white screen), and `position:fixed` popups that read
                // `window.innerHeight` on first paint cache the wrong
                // coordinates inline. Android WebView, unlike desktop
                // Chrome, does NOT auto-dispatch a viewport `resize` when
                // the container resizes. Mirror what BrowserUseManager
                // does (T-webview-popup-d3c6e10f): post a synthetic
                // `resize` after first commit so vh/innerHeight readers
                // recompute against the stabilised height.
                view.postDelayed({
                    view.evaluateJavascript(
                        "window.dispatchEvent(new Event('resize'));",
                        null,
                    )
                }, 100)
            }
        }
        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrEmpty()) pageTitle = title
            }

            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                if (icon != null) pageFavicon = icon
            }
        }
        // T-htmlpreview-resize: sheet→fullscreen toggle, IME open/close,
        // and rotation all change the WebView's height after the page is
        // already loaded. Without a `resize` notification, fixed/vh-based
        // layouts stay anchored to the original height and end up offset
        // or clipped. Forward every container-height change to JS.
        addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val oldH = oldBottom - oldTop
            val newH = bottom - top
            if (newH > 0 && newH != oldH) {
                (v as WebView).evaluateJavascript(
                    "window.dispatchEvent(new Event('resize'));",
                    null,
                )
            }
        }
    }

    private var hasLoaded = false

    /**
     * Trigger the initial load on first use. Calling repeatedly is a no-op
     * — repeats happen because both `WebPreviewBottomSheet` and
     * `WebPreviewFullscreenScreen` invoke this on first composition; the
     * second invocation would otherwise re-fetch the page, defeating the
     * whole "shared holder" trick.
     */
    fun startIfNeeded() {
        if (hasLoaded) return
        hasLoaded = true
        // For file:// URLs, load via the asset loader's secure origin
        // instead of granting direct file:// access.
        val loadUrl = toAssetLoaderUrl(currentUrl) ?: currentUrl
        if (webView.isAttachedToWindow && webView.width > 0 && webView.height > 0) {
            AppLogger.info(TAG, "loadUrl (attached) ${loadUrl.take(160)}")
            webView.loadUrl(loadUrl)
            return
        }
        webView.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                v.removeOnAttachStateChangeListener(this)
                v.post {
                    if (v.width > 0 && v.height > 0) {
                        AppLogger.info(TAG, "loadUrl (post-attach) ${loadUrl.take(160)}")
                        webView.loadUrl(loadUrl)
                    } else {
                        v.viewTreeObserver.addOnGlobalLayoutListener(object :
                            android.view.ViewTreeObserver.OnGlobalLayoutListener {
                            override fun onGlobalLayout() {
                                if (v.width > 0 && v.height > 0) {
                                    v.viewTreeObserver.removeOnGlobalLayoutListener(this)
                                    AppLogger.info(TAG, "loadUrl (post-layout) ${loadUrl.take(160)}")
                                    webView.loadUrl(loadUrl)
                                }
                            }
                        })
                    }
                }
            }
            override fun onViewDetachedFromWindow(v: android.view.View) {}
        })
    }

    fun reload() {
        AppLogger.info(TAG, "reload")
        webView.reload()
    }

    /**
     * Toggle desktop / mobile UA + viewport. Matches the Browser settings'
     * "Desktop site" behavior: Chrome 134 desktop UA, CSS viewport 1280×800,
     * with `setInitialScale` shrunk so the 1280-wide CSS viewport fits the
     * physical container width — the same shrink-to-fit math used in
     * `BrowserUseManager.applyShrinkToFit`.
     */
    fun toggleDesktopMode() {
        // [deep-audit-1045 B-02] Same latch the pool's setUserAgent entries
        // carry: the holder is destroyed by a Compose DisposableEffect and by
        // the dismiss callback, and a desktop/mobile toggle from a still-visible
        // toolbar can land after either. Pre-fix this wrote UA + clearCache +
        // reload() on a destroyed WebView.
        if (destroyed.get()) {
            AppLogger.warning(TAG, "toggleDesktopMode dropped: WebView already destroyed")
            return
        }
        desktopMode = !desktopMode
        if (desktopMode) {
            webView.settings.userAgentString = DESKTOP_UA
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
            webView.settings.setSupportZoom(true)
            webView.settings.builtInZoomControls = true
            webView.settings.displayZoomControls = false
            applyShrinkToFit(DESKTOP_VIEWPORT_CSS_WIDTH)
        } else {
            webView.settings.userAgentString = mobileUserAgent
            val mobile = mobileViewportSettings()
            webView.settings.useWideViewPort = mobile.useWideViewPort
            webView.settings.loadWithOverviewMode = mobile.loadWithOverviewMode
            webView.setInitialScale(mobile.initialScale)
        }
        AppLogger.info(TAG, "toggleDesktopMode → $desktopMode")
        // Clear cache so the site can't serve a cached variant keyed on the
        // previous UA. Sites like baidu pin their first-paint variant to a
        // cookie + UA combo, so a plain reload often returns the old page.
        webView.clearCache(false)
        webView.reload()
    }

    /**
     * Compute setInitialScale so a page authored at [cssWidth] CSS pixels
     * fits the WebView's container width. Mirrors `BrowserUseManager`'s
     * applyShrinkToFit.
     */
    private fun applyShrinkToFit(cssWidth: Int) {
        val containerPx = webView.width.takeIf { it > 0 }
            ?: webView.resources.displayMetrics.widthPixels
        val density = webView.resources.displayMetrics.density
        val cssWidthPx = (cssWidth * density).toInt()
        val scalePct = if (cssWidthPx > containerPx) {
            ((containerPx.toLong() * 100) / cssWidthPx).toInt().coerceAtLeast(1)
        } else {
            0
        }
        webView.setInitialScale(scalePct)
    }

    fun stopLoading() {
        AppLogger.info(TAG, "stopLoading")
        webView.stopLoading()
        isLoading = false
    }

    /**
     * Detach the WebView from any parent. Compose's AndroidView re-parents
     * the same view between the sheet and the fullscreen screen — Android
     * throws if a view is added to a new parent while still attached to
     * the old one, so callers should invoke this between handoffs.
     */
    fun detach() {
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
    }

    /**
     * [fix/audit0917-b8] Latches the first `destroy()` so later calls are
     * no-ops. Callers now include a Compose `DisposableEffect(onDispose)` in
     * addition to the user-initiated dismiss callback, and BOTH fire on a
     * normal dismissal — without the latch the second pass would run
     * stopLoading/loadUrl/destroy on an already-destroyed WebView (undefined
     * behaviour, and the `catch (Throwable)` below would swallow the symptom
     * into a warning line).
     */
    private val destroyed = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Final teardown. Call when the preview path is fully closed (sheet
     * dismissed AND fullscreen popped) so the WebView's renderer process
     * doesn't linger. Safe to call multiple times.
     */
    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        try {
            detach()
            webView.stopLoading()
            // [deep-audit-1045 B-02] Same three-call teardown KaTeXView.kt
            // (T10-M1) and FilePreviewScreen.kt (F-274) already use: stop →
            // drop this view's queued callbacks → destroy. The queue that
            // matters here is `onPageFinished`'s `view.postDelayed({ resize })`
            // (:249): a late attach would flush it into a destroyed renderer.
            // Safe by ordering — detach() above already cleared attachInfo, so
            // this only drains THIS view's run queue, never the shared window
            // handler's null-token messages.
            webView.removeCallbacks(null)
            webView.loadUrl("about:blank")
            webView.destroy()
            AppLogger.info(TAG, "destroyed")
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "destroy failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "WebViewHolder"
        // Match BrowserAction.UserAgentProfile.DESKTOP_CHROME so the in-chat
        // web preview and the Browser tab desktop modes are identical.
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/134.0.0.0 Safari/537.36"
        // Match UserAgentProfile.DESKTOP_CHROME.viewportSize.
        private const val DESKTOP_VIEWPORT_CSS_WIDTH = 1280
    }
}

// ---------------------------------------------------------------------------
// [da1020-D] Asset-loader URL mapping — single source for BOTH directions.
//
// The preview WebView serves local HTML through `WebViewAssetLoader` under the
// secure `https://appassets.androidplatform.net/` origin (see the holder's
// init), so a URL *reported* by the WebView is in that origin while the URL the
// rest of the app expects is the original `file://` one. Keeping the forward
// transform here — instead of inline at the `loadUrl` call site — and the
// inverse next to it makes the pair provably symmetric: the origin/prefix
// strings exist exactly once, so the two directions cannot drift apart.
//
// Deliberately free of `android.net.Uri` / `android.webkit.*`: the mapping is
// pure string work so it is unit-testable on a plain JVM. That is not just a
// test convenience — `unitTests.isReturnDefaultValues = true` (build.gradle.kts)
// makes stubbed `Uri.parse` return null instead of throwing, so a test built on
// `android.net.Uri` would pass while testing nothing.
//
// [fix/fileurl-contract] Both directions speak the ONE `file://` contract:
// **encoded at construction (encodeFileUrlPath), decoded at every
// path-semantics consumer (decodeUriPath)**. The segment is decoded/re-encoded
// across the loader boundary instead of being pasted through, which keeps
// `fileUrlFor(file)` → holder → `currentUrl` → consumer byte-stable for names
// containing space / CJK / `#` / `?` / a literal `%XX`.
// --- begin asset-loader-url-mapping (JVM harness slices this region verbatim) ---

/**
 * Mobile preview viewport contract, single-sourced so the desktop-toggle
 * path cannot drift from the init defaults (:127-128): honor the page's
 * `<meta name="viewport">` (useWideViewPort) and shrink-to-fit
 * (loadWithOverviewMode), scale reset. Toggling Desktop → Mobile used to
 * set both to `false` — the exact opposite of the init defaults — which
 * collapsed `100vh` pages to a zero-height box and locked the layout to a
 * narrow viewport until the holder was rebuilt (T-htmlpreview-2d5c4f3d).
 */
internal data class MobileViewportSettings(
    val useWideViewPort: Boolean,
    val loadWithOverviewMode: Boolean,
    val initialScale: Int,
)

internal fun mobileViewportSettings(): MobileViewportSettings =
    MobileViewportSettings(useWideViewPort = true, loadWithOverviewMode = true, initialScale = 0)

internal const val ASSET_LOADER_HOST = "appassets.androidplatform.net"

private const val ASSET_LOADER_PREFIX = "https://$ASSET_LOADER_HOST/"
private const val FILE_URL_PREFIX = "file://"
private const val HEX_DIGITS = "0123456789ABCDEF"

/**
 * Convert a `file://` URL to the asset loader's secure origin URL, percent-
 * encoding the file name. Returns null for anything that is not a file URL with
 * a trailing path segment, so callers fall back to loading the URL unchanged.
 *
 * Encoding the name is a fix, not decoration: the loader resolves its path as a
 * URL path, so a name containing a space or a CJK character previously produced
 * an invalid URL (`/我的 报告.html`) that it could not resolve. Names limited to
 * ASCII unreserved characters encode to the identical string as before.
 *
 * Scope: the loader's root is the initial file's *parent* directory, so only the
 * last path segment is meaningful here — this mapping is one-way-then-back with
 * [fromAssetLoaderUrl] and must stay symmetric with it.
 *
 * [fix/fileurl-contract] The incoming `fileUrl` carries the ENCODED URL form
 * (`file://` + `encodeFileUrlPath(path)`), while the loader needs a path segment
 * to percent-encode itself. Decoding the segment first is what makes the pair
 * symmetric instead of double-encoding: without it a name already encoded as
 * `%E6%88%91.html` would reach the loader as `%25E6%2588%2591.html` and 404.
 * The split happens on the ENCODED form on purpose — `encodeFileUrlPath` leaves
 * every `/` literal and never emits `%2F`, so the last `/`-separated piece is
 * exactly one file name, and a name literally spelled `a%2Fb.html`
 * (encoded `a%252Fb.html`) still decodes to itself.
 */
internal fun toAssetLoaderUrl(fileUrl: String): String? {
    if (!fileUrl.startsWith(FILE_URL_PREFIX)) return null
    val name = decodeUriPath(fileUrl.removePrefix(FILE_URL_PREFIX).substringAfterLast('/'))
    if (name.isEmpty()) return null
    return ASSET_LOADER_PREFIX + encodePathSegment(name)
}

/**
 * Inverse of [toAssetLoaderUrl]: map an asset-loader URL back to the `file://`
 * URL it was derived from, under [rootDir] — the directory the loader serves
 * (the holder's initial file's parent directory).
 *
 * Returns null when the URL is not on our asset-loader origin, when no root is
 * known, or when the path would resolve outside the root; every caller keeps the
 * URL it already had in that case, so an unmappable URL degrades to "show the
 * real URL" rather than to a wrong one.
 *
 * The host comparison is exact because the `/` right after the host is part of
 * [ASSET_LOADER_PREFIX]: `https://appassets.androidplatform.net.evil.com/…` does
 * not match (`startsWith` fails on the `.`). Same guard shape as
 * `BrowserUseManager`'s asset-loader origin check.
 *
 * [da1023-H] A literal `#` / `?` in the reported asset URL is always a
 * fragment / query, never part of the file name: [toAssetLoaderUrl] encodes
 * the whole last segment, so a name that really contains `#` or `?` arrives
 * here as `%23` / `%3F`. Cutting them BEFORE [decodeUriPath] matches how the
 * androidx loader itself resolves the request (path only, fragment never sent
 * on the wire) and keeps the published `file://` URL one a `File.exists()`
 * check can actually satisfy — `…/a.html#section` previously survived the
 * round trip and became a path that exists() rejected.
 *
 * [fix/fileurl-contract] The value is published in the ENCODED URL form, so the
 * mapping stays byte-symmetric with what the holder was constructed with: the
 * holder-reuse check in `ChatScreen` compares `currentUrl` against a freshly
 * built `fileUrlFor(file)`, and every path-semantics consumer of `currentUrl`
 * ([openExternalFromSheet], [WebPreviewShortcut.pin]) decodes. Publishing the
 * raw path made the reuse check fail on every repeat tap (holder destroyed and
 * rebuilt) and fed the decoders a form that mangles a literal `%XX` name.
 */
internal fun fromAssetLoaderUrl(assetUrl: String, rootDir: String?): String? {
    if (rootDir == null) return null
    if (!assetUrl.startsWith(ASSET_LOADER_PREFIX)) return null
    val relative = decodeUriPath(
        assetUrl.removePrefix(ASSET_LOADER_PREFIX).substringBefore('#').substringBefore('?'),
    )
    val resolved = resolveWithinRoot(relative, rootDir) ?: return null
    return FILE_URL_PREFIX + encodeFileUrlPath(resolved)
}

/**
 * Percent-encode one path segment: the RFC 3986 unreserved set (`ALPHA` /
 * `DIGIT` / `-._~`) survives, every other UTF-8 byte becomes `%XX`. Spaces and
 * non-ASCII characters therefore encode, while `+` and `%` are escaped too —
 * which is what keeps the inverse ([decodeUriPath], path semantics: `+` is a
 * literal, not a space) an exact round-trip.
 */
private fun encodePathSegment(segment: String): String {
    val sb = StringBuilder(segment.length)
    for (b in segment.toByteArray(Charsets.UTF_8)) {
        val value = b.toInt() and 0xFF
        val c = value.toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
            sb.append(c)
        } else {
            sb.append('%').append(HEX_DIGITS[value shr 4]).append(HEX_DIGITS[value and 0x0F])
        }
    }
    return sb.toString()
}

/**
 * Join [relative] under [rootDir], collapsing empty and `.` segments and
 * resolving `..` lexically. Returns null when the path would climb above the
 * root: the reverse mapping must never hand a caller a path outside the
 * directory the asset loader is allowed to serve (the loader rejects such
 * requests itself — this makes the URL we *expose* obey the same rule).
 */
private fun resolveWithinRoot(relative: String, rootDir: String): String? {
    val segments = ArrayList<String>()
    for (segment in relative.split('/')) {
        when (segment) {
            "", "." -> {}
            ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.size - 1)
            else -> segments.add(segment)
        }
    }
    val root = rootDir.trimEnd('/')
    return if (segments.isEmpty()) root else root + "/" + segments.joinToString("/")
}
// --- end asset-loader-url-mapping ---

/**
 * Compose-friendly holder factory. Produces a holder keyed on URL — the
 * holder lives across recompositions but is rebuilt if the caller swaps
 * the URL.
 */
@Composable
fun rememberWebViewHolder(url: String): WebViewHolder {
    val context = androidx.compose.ui.platform.LocalContext.current
    val holder = remember(url) { WebViewHolder(context.applicationContext, url) }
    // [T10-M3] remember(url) silently rebuilds the holder when the URL changes,
    // while destruction was left to the caller (UrlPreviewSheet.onDismiss only).
    // A second URL while the sheet stayed open — two OSC-1337 links, two chat
    // links — replaced the holder and leaked a whole renderer process. Tie
    // destruction to the holder's own lifetime; destroy() is idempotent and
    // detaches first.
    DisposableEffect(holder) {
        onDispose { holder.destroy() }
    }
    return holder
}
