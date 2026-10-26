package com.rikkaminis.app.webapp

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.rikkaminis.app.MainActivity
import com.rikkaminis.app.MinisApp
import com.rikkaminis.app.R
import com.rikkaminis.app.browser.SafeWebViewClient
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.ui.preview.ASSET_LOADER_HOST
import com.rikkaminis.app.ui.preview.toAssetLoaderUrl
import com.rikkaminis.app.ui.sandbox.fileUrlFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * T-pwa-1 (renamed Pwa → WebApp): immersive WebView host for a pinned
 * WebApp shortcut. Receives the `webapp_shortcut_id` intent extra (sent
 * by [ShortcutPinner]'s launchIntent), resolves the backing HTML file
 * via [WebAppPathResolver], and renders it
 * via [WebViewAssetLoader] under `https://appassets.androidplatform.net/`.
 * External navigations are blocked — only the asset-loader host and
 * `about:` URIs are allowed.
 *
 * T-pwa-preview-polish: replaces the bare full-bleed WebView with a
 * Compose host that overlays a translucent floating toolbar (close,
 * fullscreen toggle, open-in-browser, share, reload). Toolbar auto-fades
 * after 4s of inactivity and reappears on tap. Default state is
 * non-fullscreen (system bars visible); fullscreen is opt-in via the
 * toolbar button. iOS analog is the edge-swipe-to-dismiss minimal host —
 * Android lacks that gesture vocabulary so we surface a small set of
 * controls instead.
 */
class WebAppActivity : ComponentActivity() {

    private val logTag = "WebAppActivity"
    private var webViewRef: WebView? = null
    /**
     * [GH#341] Counts renderer deaths for the WebView owned by this Activity.
     * `loadHtml` (which builds the WebViewClient) runs on the Activity, while
     * the Compose host is the only place that can rebuild the instance — this
     * mutable state is the hand-off between them. The host collects it as a
     * `key`, so an increment discards the dead WebView and reloads the page.
     */
    private var rendererEpoch by androidx.compose.runtime.mutableIntStateOf(0)
    private var resolvedFile: File? = null
    /**
     * [da1032-A] The ONE fullscreen source. It lives on the Activity — not in
     * the composition — because two writers need it (the system back callback
     * below and the toolbar toggle in [WebAppHost]); [WebAppHost] therefore
     * only *reads* it and never keeps a copy. The previous shape held a plain
     * `Boolean` here plus a keyed `remember` copy inside the host, and that
     * copy could survive a host re-run whose key did not change (false → false),
     * leaving the toolbar icon and the system bars disagreeing after a back
     * press. A snapshot state read by the composition makes that class of drift
     * unrepresentable.
     */
    private val fullscreenState = mutableStateOf(false)

    // T-n01-andmenu-l10n: pre-Tiramisu locale override (see MainActivity).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.rikkaminis.app.i18n.LocaleWrap.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Default state: draw under system bars but keep them visible.
        // Fullscreen toggle hides them on demand. Both bars are made
        // transparent so the WebView truly bleeds edge-to-edge instead of
        // sitting under an opaque status-bar strip.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            // Default to light (white) icons since arbitrary WebApp content
            // could be either light or dark — the dark scrim behind the
            // floating toolbar covers the worst-case white-on-white.
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        applySystemBars(fullscreen = false)

        // T-pwa-3: back press — exit fullscreen if active, else finish.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreenState.value) {
                    // [da1032-A] Flip the single source only. The composition
                    // reads it, so the toolbar icon follows by recomposition —
                    // renderHost() is deliberately NOT called here: re-running
                    // setContent bought nothing once the state moved to the
                    // Activity (nothing here owns the fullscreen value any
                    // more) and it is the exact call that made a WebView
                    // rebuild possible ([audit-0909 T7-M2]).
                    fullscreenState.value = false
                    applySystemBars(fullscreen = false)
                } else {
                    finish()
                }
            }
        })

        val shortcutId = intent?.getStringExtra(EXTRA_SHORTCUT_ID)
        if (shortcutId.isNullOrBlank()) {
            renderSourceMissing(sourceSessionId = null)
            return
        }

        lifecycleScope.launch {
            val app = applicationContext as MinisApp
            val shortcut = withContext(Dispatchers.IO) {
                app.webAppShortcutRepository.get(shortcutId)
            }
            if (shortcut == null) {
                renderSourceMissing(sourceSessionId = null)
                return@launch
            }
            val file = withContext(Dispatchers.IO) {
                WebAppPathResolver.resolve(this@WebAppActivity, shortcut)
            }
            if (file == null) {
                AppLogger.warning(logTag, "WebApp source missing for shortcut ${shortcut.id}: ${shortcut.htmlPath}")
                renderSourceMissing(sourceSessionId = shortcut.sourceSessionId)
                return@launch
            }
            shortcut.title.takeIf { it.isNotBlank() }?.let { title = it }
            resolvedFile = file
            renderHost()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-apply current bar state — dialogs / external activities can
        // resurrect bars even when we asked for fullscreen.
        applySystemBars(fullscreen = fullscreenState.value)
    }

    private fun applySystemBars(fullscreen: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun renderHost() {
        val file = resolvedFile ?: return
        setContent {
            MaterialTheme {
                WebAppHost(
                    file = file,
                    fullscreen = fullscreenState.value,
                    onFullscreenChange = { fs ->
                        fullscreenState.value = fs
                        applySystemBars(fullscreen = fs)
                    },
                    onClose = { finish() },
                    onWebViewReady = { wv -> webViewRef = wv },
                )
            }
        }
    }

    /**
     * T-pwa-3: replaces the prior toast-and-finish with an inline error
     * screen offering an "Open in Minis" escape hatch.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun renderSourceMissing(sourceSessionId: String?) {
        webViewRef?.apply {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webViewRef = null

        setContent {
            MaterialTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text(stringResource(R.string.webapp_activity_label)) })
                    },
                ) { padding ->
                    Box(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Card(modifier = Modifier.padding(24.dp)) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = stringResource(R.string.webapp_source_missing_title),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = stringResource(R.string.webapp_source_missing_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(onClick = { openInMinis(sourceSessionId) }) {
                                    Text(stringResource(R.string.webapp_open_in_minis))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openInMinis(sourceSessionId: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // [fix/audit-1015 BUG-08①] This used to put a `target_session_id`
            // string extra — a dead letter: MainActivity has no reader for it
            // (the only occurrence of the key in the repo was the constant
            // itself), so "Open in Minis" always landed on whatever MainActivity
            // happened to be showing. The app already has a first-class
            // session deep link — minis://session/<sid> →
            // DeepLinkAction.OpenSession → Routes.chat — handled both warm
            // (MainActivity.onNewIntent:752 → handleDeepLink) and cold
            // (onCreate:475 parses the launch intent's data). Route through it
            // instead of inventing a parallel extra. Uri.encode keeps the
            // segment safe; DeepLinkHandler reads uri.path which decodes it
            // back. No session (source missing) → plain launch, unchanged.
            sourceSessionId
                ?.let { data = Uri.parse("minis://session/" + Uri.encode(it)) }
        }
        startActivity(intent)
        finish()
    }

    @Composable
    private fun WebAppHost(
        file: File,
        fullscreen: Boolean,
        onFullscreenChange: (Boolean) -> Unit,
        onClose: () -> Unit,
        onWebViewReady: (WebView) -> Unit,
    ) {
        val context = LocalContext.current
        // [da1032-A] `fullscreen` is a plain read here on purpose: the Activity
        // owns the single snapshot (fullscreenState) and this composable only
        // observes it. Do NOT reintroduce a `remember { mutableStateOf(...) }`
        // copy — a second source is exactly what let the toolbar icon and the
        // system bars drift apart when the host was re-run with an unchanged
        // key. (Superseded note from [fix/audit-1015 BUG-08②]: that key existed
        // to paper over the re-run, and the re-run is gone — the back callback
        // now only flips state.)
        var toolbarVisible by remember { mutableStateOf(true) }
        // Bumped on each user interaction; LaunchedEffect uses it as a key
        // so the auto-hide timer restarts on every tap.
        var interactionTick by remember { mutableStateOf(0) }
        var webViewState by remember { mutableStateOf<WebView?>(null) }
        // [GH#341] `rendererEpoch` is the Activity-owned counter below; reading
        // it here makes this composition a subscriber, so an increment (the
        // renderer died) discards the dead WebView via the `key` and rebuilds
        // it — a WebApp has nothing to lose by reloading.

        LaunchedEffect(toolbarVisible, interactionTick) {
            if (toolbarVisible) {
                delay(AUTO_HIDE_MS)
                toolbarVisible = false
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // [GH#341] `key` so a dead renderer is actually replaced: bumping
            // rendererEpoch below discards this AndroidView and re-runs the
            // factory with a fresh WebView.
            key(rendererEpoch) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val wv = WebView(ctx)
                    // [audit-0909 T7-M1] Do NOT call
                    // CookieManager.setAcceptCookie(false) here: it is a
                    // PROCESS-WIDE switch (Android has no per-WebView
                    // first-party cookie toggle) and it was never restored on
                    // destroy — opening any WebApp silently stopped every
                    // already-live agent-browser tab from accepting cookies
                    // (login/hCaptcha failures until that tab is recreated and
                    // BrowserUseManager.configureWebView flips the switch back
                    // on). This asset-loader page needs no cookies anyway; the
                    // per-WebView third-party switch below is the real knob.
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false)
                    configureWebView(wv)
                    // Tap-to-show — return false so WebView still handles the input.
                    wv.setOnTouchListener { _, ev ->
                        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                            toolbarVisible = true
                            interactionTick += 1
                        }
                        false
                    }
                    loadHtml(wv, file)
                    onWebViewReady(wv)
                    webViewState = wv
                    wv
                },
                // [audit-0909 T7-M2] The composition can still replace this
                // AndroidView (a renderer death bumps `rendererEpoch` above,
                // which re-keys the block), and a pass used to drop the
                // previous instance without destroying its WebView — a live
                // renderer per replacement (BrowserUseManager's own note puts
                // those at 50-100 MB each). onRelease covers both the
                // replacement and the activity-teardown path; destroy() is
                // idempotent and clearing the refs keeps onDestroy() from
                // touching an already-destroyed instance.
                // [da1032-A] The fullscreen toggle no longer re-runs
                // setContent (the state is Activity-owned and this host only
                // reads it), so the epoch is the only remaining rebuild path
                // this hook guards.
                onRelease = { wv ->
                    if (webViewRef === wv) webViewRef = null
                    if (webViewState === wv) webViewState = null
                    wv.stopLoading()
                    wv.destroy()
                },
            )
            }

            // Top inset spacer reserves room when system bars are visible so
            // the toolbar doesn't sit under the status bar.
            val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

            AnimatedVisibility(
                visible = toolbarVisible,
                enter = fadeIn(animationSpec = tween(220)),
                exit = fadeOut(animationSpec = tween(300)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topInset + 12.dp),
            ) {
                FloatingToolbar(
                    fullscreen = fullscreen,
                    onClose = onClose,
                    onToggleFullscreen = {
                        onFullscreenChange(!fullscreen)
                        toolbarVisible = true
                        interactionTick += 1
                    },
                    onOpenInBrowser = {
                        toolbarVisible = true
                        interactionTick += 1
                        openInExternalBrowser(context, file)
                    },
                    onShare = {
                        toolbarVisible = true
                        interactionTick += 1
                        shareWebAppFile(context, file)
                    },
                    onReload = {
                        toolbarVisible = true
                        interactionTick += 1
                        webViewState?.reload()
                    },
                )
            }
        }
    }

    @Composable
    private fun FloatingToolbar(
        fullscreen: Boolean,
        onClose: () -> Unit,
        onToggleFullscreen: () -> Unit,
        onOpenInBrowser: () -> Unit,
        onShare: () -> Unit,
        onReload: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .background(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(28.dp),
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.webapp_action_close),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onReload) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.webapp_action_reload),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onToggleFullscreen) {
                Icon(
                    imageVector = if (fullscreen) Icons.Outlined.FullscreenExit
                    else Icons.Outlined.Fullscreen,
                    contentDescription = stringResource(
                        if (fullscreen) R.string.webapp_action_exit_fullscreen
                        else R.string.webapp_action_fullscreen
                    ),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onShare) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = stringResource(R.string.webapp_action_share),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onOpenInBrowser) {
                Icon(
                    imageVector = Icons.Outlined.OpenInBrowser,
                    contentDescription = stringResource(R.string.webapp_action_open_browser),
                    tint = Color.White,
                )
            }
        }
    }

    /**
     * Stage the HTML file under cacheDir/share/ (covered by FileProvider's
     * <cache-path name="share" path="share/"/>) and hand its content URI
     * to the system browser. Copying — rather than exposing the original
     * `<filesDir>/sessions/...` path — keeps the FileProvider authority
     * surface narrow and matches LogManagementScreen's pattern.
     */
    private fun openInExternalBrowser(context: android.content.Context, file: File) {
        // [fix/audit0917-b8] stagedShareUri copies the file; do it off the main
        // thread (see its KDoc) and only build/start the Intent back on Main.
        lifecycleScope.launch {
            val uri = stagedShareUri(context, file) ?: run {
                Toast.makeText(context, R.string.webapp_no_browser_app, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/html")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                AppLogger.warning(logTag, "No browser app for WebApp preview: ${e.message}")
                Toast.makeText(context, R.string.webapp_no_browser_app, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareWebAppFile(context: android.content.Context, file: File) {
        lifecycleScope.launch {
            val uri = stagedShareUri(context, file) ?: run {
                Toast.makeText(context, R.string.webapp_no_browser_app, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // [deep-audit-1049 B-03] Shared launcher: NEW_TASK stays on the
            // chooser (the intent actually started) and a device with no share
            // target now says so instead of only writing a log line.
            com.rikkaminis.app.util.ExternalIntent.share(
                context,
                send,
                context.getString(R.string.webapp_share_chooser_title),
            ) { t ->
                Toast.makeText(
                    context,
                    context.getString(R.string.file_share_failed_toast, t.message ?: ""),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    /**
     * Copy [file] into `cacheDir/share/` and return a FileProvider Uri for
     * it. Returns null on IO failure. The staged file overwrites any prior
     * copy with the same name — [pruneShareDir] clears entries older than a
     * day before each new staging, so share/ no longer grows unbounded.
     *
     * [fix/audit0917-b8] Suspends and copies on [Dispatchers.IO]: the caller is
     * a toolbar click handler on the main thread, and a large page (bundled
     * asset / big HTML) made `copyTo` a visible main-thread stall.
     */
    private suspend fun stagedShareUri(context: android.content.Context, file: File): Uri? {
        return try {
            val stem = file.nameWithoutExtension.ifBlank { "webapp" }
            val staged = withContext(Dispatchers.IO) {
                // [da1022-H] mkdirs + prune on IO, not the main thread: the
                // caller is a toolbar click handler and `dir.listFiles()` in
                // pruneShareDir is a disk walk that stalls on large cache dirs.
                val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
                // [da1022-H] Nothing deleted share/ entries before the pruner
                // landed (fix/render-ui F-273) — share/ accumulated one blob
                // per WebApp for the life of the install. Prune before
                // staging: the 24h TTL cutoff always postdates the fresh copy
                // written below, so prune-then-copy can never delete the file
                // this call is about to stage.
                com.rikkaminis.app.ui.components.pruneShareDir(shareDir)
                val s = File(shareDir, "$stem.html")
                file.copyTo(s, overwrite = true)
                s
            }
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                staged,
            )
        } catch (t: Throwable) {
            AppLogger.warning(logTag, "FileProvider staging failed: ${t.message}")
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mediaPlaybackRequiresUserGesture = false
        }
    }

    private fun loadHtml(webView: WebView, file: File) {
        val parent = file.parentFile ?: run {
            renderSourceMissing(sourceSessionId = null)
            return
        }
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/",
                WebViewAssetLoader.InternalStoragePathHandler(this, parent),
            )
            .build()

        webView.webViewClient = object : SafeWebViewClient() {
            /**
             * [GH#341] Renderer died. Clear the refs (so [onDestroy] and the
             * toolbar's reload/share actions never touch a dead instance) and
             * bump the host's epoch so the WebView is rebuilt and the page
             * reloaded — a WebApp has nothing to lose by reloading.
             */
            override fun onRendererGone(view: WebView?) {
                if (webViewRef === view) webViewRef = null
                // This Activity owns the WebView but not the recovery: the
                // Compose host rebuilds it off the epoch below. The dead
                // instance still has to go, though — nothing else will
                // (overriding this hook replaced the base teardown).
                destroyDeadWebView(view)
                // The renderer died in the Activity-owned WebView, but the
                // Compose host owns the instance and is the only place that can
                // rebuild it — hand the signal over via a state flag.
                rendererEpoch += 1
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ) = loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url ?: return true
                val scheme = url.scheme?.lowercase()
                val host = url.host?.lowercase()
                if (scheme == "https" && host == ASSET_LOADER_HOST) return false
                if (scheme == "about") return false
                AppLogger.info(logTag, "Blocked external navigation: $url")
                Toast.makeText(
                    this@WebAppActivity,
                    R.string.webapp_external_nav_blocked,
                    Toast.LENGTH_SHORT,
                ).show()
                return true
            }
        }

        // [da1021-F] Route through the shared single-source mapping
        // (WebViewHolder.toAssetLoaderUrl) instead of pasting `file.name` raw:
        // the loader resolves its path as a URL path, so a space/CJK name used
        // to produce an unresolvable URL. The mapping's root is the initial
        // file's *parent* directory — the same root this handler serves
        // (`InternalStoragePathHandler(this, parent)`), so only the last path
        // segment is meaningful here and the reuse is exact. The null branch
        // is unreachable for an existing File (the input is always a well-
        // formed file:// URL) but fails closed into the source-missing state
        // rather than a doomed 404.
        //
        // [fix/fileurl-contract] The file URL is built by the shared encoder
        // (`ui.sandbox.fileUrlFor`), not `"file://" + file.absolutePath`:
        // `toAssetLoaderUrl` consumes the URL contract (encoded path), and a raw
        // concatenation made a file literally named `50%23.html` decode to
        // `50#.html` inside the mapping — the shortcut then pointed at a
        // non-existent sibling.
        val url = toAssetLoaderUrl(fileUrlFor(file))
            ?: run {
                renderSourceMissing(sourceSessionId = null)
                return
            }
        webView.loadUrl(url)
    }

    override fun onDestroy() {
        webViewRef?.apply {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webViewRef = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SHORTCUT_ID = "webapp_shortcut_id"
        const val ACTION_OPEN_WEBAPP = "com.rikkaminis.app.action.OPEN_WEBAPP"
        // [fix/audit-1015 BUG-08①] EXTRA_TARGET_SESSION_ID removed: it had no
        // reader anywhere in the repo (grep across all sources: the constant's
        // only consumer was the write it was created for). openInMinis now uses
        // the existing minis://session/<sid> deep link.
        // [da1021-F] ASSET_LOADER_HOST removed: the single source now lives in
        // ui/preview/WebViewHolder.kt (`internal const val`) — this activity
        // imports it (with toAssetLoaderUrl) instead of carrying a second copy
        // of the origin string that could drift apart from the loader mapping.
        private const val AUTO_HIDE_MS = 4000L
    }
}
