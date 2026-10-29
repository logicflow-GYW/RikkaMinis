package com.rikkaminis.app.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.core.content.FileProvider
import com.rikkaminis.app.browser.SafeWebViewClient
import com.rikkaminis.app.data.AgentRuntimeLimitsPrefs
import com.rikkaminis.app.sandbox.PRootKernel
import com.rikkaminis.app.util.saveOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Manages a single Android WebView for browser automation.
 * Mirrors iOS BrowserUseManager.
 */
class BrowserUseManager(
    val webView: WebView,
    profile: UserAgentProfile = UserAgentProfile.MOBILE_CHROME,
) {
    companion object {
        private const val TAG = "BrowserUseManager"
        // [feat/chat-tuning-panel-b] NAVIGATION_TIMEOUT_MS / SCREENSHOT_QUALITY /
        // DEFAULT_DOM_STABLE_TIMEOUT_MS are now user-tunable via
        // AgentRuntimeLimitsPrefs (defaults 30 s / 80 / 5 s, matching the
        // previous literals).
        private const val SNAPSHOT_QUALITY = 70          // Auto-snapshot after visual-change actions (iOS: 0.7)

        /**
         * Cap full_page screenshot stretched viewport at 32768 px. Above this,
         * Bitmap.createBitmap risks OOM (e.g. 32768 × ~1130 px × 4 B/ARGB ≈ 144 MB
         * at desktop 1280 CSS × 2.75 density). Pages taller than the cap are
         * truncated and the metadata exposes `truncated:true` + the original
         * scrollHeight so the agent can scroll-then-stitch if it needs more.
         */
        private const val MAX_FULL_PAGE_HEIGHT_PX = 32768

        /**
         * [T-android-screenshot-cache-cap] Bucket size for [pruneScreenshotCache]:
         * keep at most this many snapshot/screenshot jpg files in
         * `cacheDir/browser_screenshots`. 128 files ≈ a few MB of JPEG at
         * typical viewport sizes — bounded, and auto-snapshot-heavy sessions
         * still leave the most recent frames intact for the agent to read.
         */
        private const val MAX_SCREENSHOT_CACHE_FILES = 128

        /**
         * Minimal HTML used in place of `about:blank` when a fresh tab
         * needs a page refresh (e.g. `set_viewport` before any navigation).
         * `<meta name="viewport" content="width=device-width">` makes
         * `window.innerWidth` track the container we just laid out, instead
         * of Android WebView's hardcoded 980px fallback for blank pages.
         * Loaded via `loadDataWithBaseURL` so WebView accepts raw HTML
         * without URL encoding.
         */
        private const val BLANK_PAGE_HTML =
            "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width\"></head><body></body></html>"
        // [T-android-domstable-min-budget] C5: with the old 200ms floor and a
        // 200ms poll interval the loop could only ever take ONE sample
        // (first sample never matches lastSize=-1, then the delay exhausts
        // the budget) — wait_for_dom_stable failed on every page at the
        // minimum. 1000ms fits >=4 samples so the smallest budget can
        // actually observe two equal readings.
        private const val MIN_DOM_STABLE_TIMEOUT_MS = 1_000
        private const val MAX_DOM_STABLE_TIMEOUT_MS = 60_000

        /**
         * [feat/browser-console-network-upload] Ring-buffer caps for the
         * per-tab diagnostics buffers. Console entries are small; network
         * entries carry a full URL. 200/100 entries keep the last few page
         * loads visible without unbounded growth.
         */
        private const val CONSOLE_BUFFER_CAP = 200
        private const val NETWORK_BUFFER_CAP = 100

        /** Output cap for the diagnostics readouts (mirrors get_text's
         *  readable-bound philosophy — a chatty page shouldn't flood the
         *  LLM context). */
        private const val DIAGNOSTICS_TEXT_CAP = 16_000

        /**
         * [audit2-A2] Ceiling on the `data:` URL string a page may push through
         * the `__minis__.saveBlobDownload` bridge / the download listener.
         *
         * Sized off the DECODED payload — 24 MiB of URL text is ≈16 MiB of
         * bytes for base64 (4 chars per 3 bytes) — which is comfortably above
         * any real export a page offers (worksheets, PDFs, images, small
         * archives) and far below "allocate the app to death": the decode step
         * holds the string and its byte copy at once, and the pool then writes
         * that same payload into the session workspace.
         */
        private const val MAX_BLOB_DATA_URL_CHARS = 24 * 1024 * 1024

        @SuppressLint("SetJavaScriptEnabled")
        fun configureWebView(webView: WebView, profile: UserAgentProfile, customUA: String? = null) {
            // [T-android-browser-blank] The browser lives inside a Material3
            // ModalBottomSheet, which hosts content in its own secondary
            // window. On some OEM GPUs the WebView's hardware draw functor
            // fails to composite in that window — the page loads (title/URL
            // update normally) but the content area stays black or white.
            // Rendering the WebView through its own hardware layer texture is
            // the standard workaround for WebView-in-dialog blank rendering.
            webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = false
                setSupportMultipleWindows(true)
                // [deep-audit-1016 9b] Explicitly deny the file:// and
                // content:// surfaces. WebView's defaults for these only became
                // false in API 30, so on the minSdk 26 floor this pool would
                // still inherit the old permissive defaults unless they are
                // named. Nothing here needs them — the pool loads http/https
                // plus minis://, which is served through shouldInterceptRequest
                // rather than as a file URL — and WebAppActivity sets the same
                // four for its own WebView. The KaTeX pool is a different
                // WebView with a deliberate file:// load, so it is unaffected.
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                val ua = customUA ?: profile.userAgentString
                if (ua != null) userAgentString = ua
            }
            // T-android-webview-v3-port: enable first- + third-party cookies.
            // WebView ships with third-party cookies disabled by default; that
            // breaks hCaptcha / Turnstile / reCAPTCHA flows where the
            // verification widget lives in a cross-origin iframe and posts its
            // token back to the parent through a Set-Cookie round-trip. The
            // agent-driven browser is the user's surrogate; matching Chrome's
            // default unblocks the same captcha flows the user would clear in
            // a real browser tab. First-party setAcceptCookie defaults to
            // true on every Android version we support — calling it
            // explicitly anyway so the intent is grep-able.
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
        }
    }

    private val _currentURL = MutableStateFlow("")
    val currentURL: StateFlow<String> = _currentURL.asStateFlow()

    /**
     * Chat session that owns this tab's WebView. Populated by
     * [BrowserTabPool.setSession] (which receives it from the same internal
     * call sites that stamp [BrowserActionInput.sessionId] — the CLI offload
     * handler / ChatViewModel tool executor; NEVER parsed from model JSON).
     * Null in the manual browser sheet (no session context) →
     * [interceptMinisURL] falls back to the pre-existing global resolver
     * (the manual sheet had no session pinning before this fix either).
     */
    @Volatile
    var minisSessionId: String? = null

    /**
     * Last committed main-document URL for the minis:// subresource gate.
     *
     * Maintained ONLY on the WebView's UI thread, and only from signals that
     * mean this tab actually adopted the document: onPageStarted /
     * onPageFinished, the main-frame minis:// interception in
     * shouldInterceptRequest, and shouldOverrideUrlLoading — that last one only
     * once it has decided not to route the navigation away (deep-audit-1016
     * 9a: recording a URL this hook then cancels let a page seed the gate with
     * an origin the tab never committed to). Reset to null on destroy().
     *
     * Keep the [MinisMainDocGate] gate fail-closed for null — fresh tabs commit
     * only when the blank bootstrap page lands.
     */
    @Volatile
    private var lastMainDocUrl: String? = null

    /**
     * [r3-B09 F-14] Status code of the last main-frame HTTP error on this tab
     * (403 / 429 / …), cleared when a new document starts loading. Read by
     * `BrowserTabPool` and handed to
     * [BrowserRiskControl.detectChallenge] as `statusCode` — the field existed
     * for a long time with no producer at all, so the two status-based kinds
     * (`rate_limited`, `access_denied`) were unreachable.
     */
    @Volatile
    var lastMainFrameHttpError: Int? = null
        private set

    private val _pageTitle = MutableStateFlow("")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()

    private var currentProfile: UserAgentProfile = profile

    /** Callback for window.open / target="_blank" — TabPool hooks this. */
    var onNewWindow: ((Message) -> Unit)? = null

    /** Callback for window.close — TabPool hooks this. */
    var onCloseWindow: (() -> Unit)? = null

    /**
     * Callback when the page triggers a file download over http/https
     * (Content-Disposition attachment, <a download>, unrenderable MIME type).
     * TabPool hooks this and streams the URL into the session workspace.
     */
    var onDownloadStart: ((url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, contentLength: Long) -> Unit)? = null

    /**
     * Callback delivering the bytes of a blob: download. blob: URLs only exist
     * inside the page's JS context, so [fetchBlobDownload] reads them via an
     * injected FileReader and hands the decoded bytes back through the bridge.
     */
    var onBlobDownloadData: ((data: ByteArray, filename: String, mimeType: String?) -> Unit)? = null

    /**
     * [r3-B09 F-10] A blob:/data: download failed before any bytes reached the
     * pool (no bridge token match, FileReader error, unsafe name, malformed or
     * oversized data URL, decode failure, or the tab died mid-flight).
     *
     * The pool cannot infer any of these: they happen entirely inside the page
     * JS / bridge handshake, so without this callback the only trace was a
     * Log.w here and the user saw nothing at all after tapping "download".
     * `filename` is null when the failure is before a name exists.
     */
    var onBlobDownloadFailed: ((filename: String?, reason: String) -> Unit)? = null

    /**
     * Deferred for awaiting navigation completion.
     *
     * [audit-0916b] Carries a [NavigationResult], not a bare `Unit`. Every
     * completion path (onPageFinished / main-frame onReceivedError / the
     * timeout runnable / handing the URL to another app) stamps which of those
     * actually happened, because the old `Unit` made them indistinguishable and
     * navigate() answered `success = true` for all of them (§20b).
     *
     * [deep-audit-1019 ISSUE-08] `@Volatile`, for the same reason
     * [pendingFileChooser] carries one ("every write happens on the main
     * thread, but readers run on the offload coroutine") and the same reason
     * [AsyncJsSlot] replaced two bare fields: here the shapes are inverted but
     * the hazard is identical — `navigate()` / `reloadAndWait()` install the
     * deferred from the calling coroutine thread, while the readers are WebView
     * callbacks and [destroy], all on Main. A teardown read that saw a stale
     * `null` would skip the wake-up this fix exists to perform, making it
     * silently dependent on scheduling. `complete()` is itself thread-safe;
     * this does not make the reference swap atomic, so every existing
     * identity guard (`navigationDeferred === deferred`) stays as it was.
     */
    @Volatile
    private var navigationDeferred: CompletableDeferred<NavigationResult>? = null

    /** Screenshots directory. */
    private val screenshotsDir: File by lazy {
        File(webView.context.cacheDir, "browser_screenshots").also { it.mkdirs() }
    }

    /**
     * [T-android-screenshot-cache-cap] Upper bound on the number of local
     * screenshot jpg files kept in [screenshotsDir]. `attachSnapshot` writes a
     * snapshot for every visual action; without a cap this directory grows
     * without bound (independently of the system-managed cache cleanup).
     * Pruned on [saveBitmapToFile] — cheap, best-effort, and only after a
     * successful save so a write failure never drops older artifacts.
     */
    private fun pruneScreenshotCache() {
        val cap = MAX_SCREENSHOT_CACHE_FILES
        val files = screenshotsDir.listFiles() ?: return
        if (files.size <= cap) return
        // T7-L4: sort by mtime, not by name. Filenames do embed epoch-millis,
        // but the two prefixes ("screenshot_", "snapshot_") sort as blocks, so
        // a name sort keeps every screenshot ahead of every snapshot no matter
        // how old — a just-captured screenshot could be pruned while an ancient
        // snapshot survived.
        files.sortedBy { it.lastModified() }
            .dropLast(cap)
            .forEach { runCatching { it.delete() } }
    }

    /**
     * Deferred used by executeJS to receive results from async scripts via JS
     * bridge. [fix/audit-s6h2] each request is tagged with a unique token that
     * the JS callbacks echo back; a late resolve/reject from a TIMED-OUT prior
     * request is discarded instead of completing the next request's deferred
     * (which previously caused cross-request result串台 — request A's data
     * delivered to request B).
     */
    /**
     * Single-slot registry for in-flight async JS bridge requests.
     * [fix/audit-s6h2] each request is tagged with a unique token that the JS
     * callbacks echo back; a late resolve/reject from a TIMED-OUT prior
     * request is discarded instead of completing the next request's deferred
     * (which previously caused cross-request result串台 — request A's data
     * delivered to request B).
     *
     * [deep-audit-1016c ISSUE-03B] Was two bare fields (`asyncJsDeferred` /
     * `asyncJsActiveToken`) with no `@Volatile` and an unconditional,
     * token-less `finish` — see [AsyncJsSlot] for the three defects it fixes
     * (visibility on the JavaBridge thread, finish only clearing its own slot,
     * begin completing the evicted deferred with a structured error).
     */
    private val asyncJsSlot = AsyncJsSlot<CompletableDeferred<String>>()

    /**
     * [audit2-A2] One-shot credential for the `__minis__.saveBlobDownload`
     * bridge call — same shape as the async-JS token above (a fresh UUID per
     * request, compared with `==`). [fetchBlobDownload] arms it right before
     * injecting the FileReader that echoes it back.
     *
     * Why it exists: `addJavascriptInterface` exposes the bridge to EVERY page
     * this tab loads, so without a credential any third-party page (or an
     * iframe inside one) could call `__minis__.saveBlobDownload(…)` itself and
     * write attacker-chosen bytes into the session workspace — no user action
     * needed. The token is only knowable to script we injected ourselves.
     *
     * Consumed (cleared) on first use, so a replayed call after the real
     * download landed is rejected as well.
     */
    // @Volatile: armed on the main thread (download listener →
    // fetchBlobDownload), read on the WebView's JavaBridge thread.
    @Volatile
    private var blobDownloadActiveToken: String? = null

    // ── [feat/browser-console-network-upload] diagnostics buffers ────────────

    /** One page JS console message, captured in onConsoleMessage. */
    data class ConsoleEntry(
        val timestamp: Long,
        val level: String,
        val message: String,
        val line: Int,
        val source: String,
    )

    /**
     * One network request observed at shouldInterceptRequest. NOTE: the
     * WebView API only exposes the REQUEST at interception time — response
     * status + duration are merged from the page's PerformanceResourceTiming
     * entries (Chromium 109+) at read time, and show "?" when unavailable.
     */
    data class NetworkEntry(
        val timestamp: Long,
        val method: String,
        val url: String,
        val isMainFrame: Boolean,
    )

    private val consoleLog = ArrayDeque<ConsoleEntry>()
    private val networkLog = ArrayDeque<NetworkEntry>()

    /** onConsoleMessage arrives on the main thread, shouldInterceptRequest on
     *  a background thread — both buffers are synchronized for safety. */
    private fun recordConsole(entry: ConsoleEntry) = synchronized(consoleLog) {
        if (consoleLog.size >= CONSOLE_BUFFER_CAP) consoleLog.removeFirst()
        consoleLog.addLast(entry)
    }

    private fun recordNetwork(entry: NetworkEntry) = synchronized(networkLog) {
        if (networkLog.size >= NETWORK_BUFFER_CAP) networkLog.removeFirst()
        networkLog.addLast(entry)
    }

    /**
     * The file chooser a page opened (an <input type="file"> the agent
     * clicked), awaiting a file_upload action. WebView requires the callback
     * to be answered EXACTLY ONCE: file_upload resolves it with FileProvider
     * URIs; navigation away (onPageStarted) or a newer chooser cancels it
     * with null so a stale callback can never swallow a later upload.
     */
    // [fix/browser-trio-audit] @Volatile: every write happens on the main
    // thread (chrome-client callbacks / destroy), but readers run on the
    // offload coroutine (execute()'s hint check, fileUpload's initial
    // read) — without volatile those reads may see a stale null/non-null.
    @Volatile
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private var pendingFileAccept: String? = null

    private fun cancelPendingFileChooser(reason: String) {
        val pending = pendingFileChooser ?: return
        pendingFileChooser = null
        pendingFileAccept = null
        runCatching { pending.onReceiveValue(null) }
        Log.i(TAG, "file chooser cancelled: $reason")
    }

    /** Allocate a fresh deferred + token for an async JS bridge request. */
    private fun beginAsyncJsRequest(): Pair<CompletableDeferred<String>, String> {
        val token = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        // [deep-audit-1016c ISSUE-03B] A request starting while another is
        // still in flight evicts it — complete the evicted deferred with a
        // structured error (same shape as the bridge's own reject, so both
        // executeJS and fetch surface it as a real error) instead of
        // orphaning it until the full 30s/60s timeout.
        asyncJsSlot.begin(token, deferred)?.complete(
            "{\"error\":\"superseded by a newer async JS request\"}"
        )
        return deferred to token
    }

    /**
     * Release the slot iff [token] still owns it. [deep-audit-1016c ISSUE-03B]
     * a timed-out / cancelled / failed request must never clear the slot of a
     * newer request that already began.
     */
    private fun finishAsyncJsRequest(token: String) {
        asyncJsSlot.finish(token)
    }

    /** JavaScript interface for async script result callbacks. */
    private val jsBridge = object {
        @JavascriptInterface
        fun resolve(token: String, result: String) {
            asyncJsSlot.ifOwner(token)?.complete(result)
        }

        @JavascriptInterface
        fun reject(token: String, error: String) {
            asyncJsSlot.ifOwner(token)?.complete("{\"error\":${JSONObject.quote(error)}}")
        }

        /**
         * Receives a blob: download read as a data URL by [fetchBlobDownload]'s
         * injected FileReader. Runs on the WebView's JavaBridge thread — file
         * I/O downstream is fine, but don't touch the WebView from here.
         *
         * [audit2-A2] Credential check. This method is reachable from ANY page
         * the tab loads (that is what `addJavascriptInterface` means), so the
         * call is only honoured when it carries the token [fetchBlobDownload]
         * just armed. An unauthenticated call is dropped silently — never
         * thrown back into the page, which would leak internals — and leaves a
         * warn line for forensics.
         */
        @JavascriptInterface
        fun saveBlobDownload(token: String, dataUrl: String, filename: String) {
            if (token != blobDownloadActiveToken) {
                // [r3-B09 F-10] A newer blob: fetch overwrote the single-slot
                // token before this reader answered; say so instead of dropping
                // the download on the floor.
                reportBlobFailure(filename, "the page replaced this download request before it finished")
                return
            }
            // One-shot: the credential dies with the call it authorised.
            blobDownloadActiveToken = null
            deliverBlobDownload(dataUrl, filename)
        }

        @JavascriptInterface
        fun blobDownloadError(error: String) {
            // [r3-B09 F-10] No token on this member (the page-side error hook is
            // not credentialed), so no name is known here either.
            reportBlobFailure(null, error.ifBlank { "the page failed to read the download" })
        }
    }

    /**
     * Decode a `data:` URL produced by the page-side blob reader and hand the
     * bytes to [onBlobDownloadData]. Deliberately NOT a `@JavascriptInterface`
     * member: the two legitimate callers are the bridge method above (after its
     * credential check) and the `data:` branch of the download listener (an
     * in-process call from native code, which needs no token).
     */
    private fun deliverBlobDownload(dataUrl: String, filename: String) {
        // [audit2-A2] Bound the payload before touching it. The page chooses
        // this string, and the decode below allocates a SECOND copy of it
        // (base64 is 4 chars per 3 bytes, so ≈0.75×; percent-encoded text can
        // double), then the pool persists it to the workspace — an unbounded
        // input is an unbounded allocation plus an unbounded file. The check
        // must be on the STRING length, before decoding: decoding first would
        // mean the allocation we are trying to prevent has already happened.
        // (The page-side string itself is built inside the WebView before we
        // ever see it, so this bounds our amplification, not the page's.)
        if (dataUrl.length > MAX_BLOB_DATA_URL_CHARS) {
            reportBlobFailure(filename, "payload too large (${dataUrl.length} > $MAX_BLOB_DATA_URL_CHARS chars)")
            return
        }
        // [audit-RC11] Defense-in-depth: page-provided names are untrusted.
        // The pool also sanitizes, but refuse as early as possible so the
        // bytes are never even handed off for an unsafe name.
        val safeName = BrowserTabPool.sanitizeDownloadName(filename) ?: run {
            reportBlobFailure(filename, "the page supplied an unusable filename")
            return
        }
        val comma = dataUrl.indexOf(',')
        if (comma < 0 || !dataUrl.startsWith("data:")) {
            reportBlobFailure(filename, "malformed data URL (len=${dataUrl.length})")
            return
        }
        val header = dataUrl.substring(5, comma)
        val mime = header.substringBefore(';').ifEmpty { null }
        val bytes = try {
            // [deep-audit-1019 ISSUE-08] The decode decision lives in the
            // top-level [decodeDataUrlPayload] so the `+`-vs-form semantics are
            // unit-testable without a WebView — the same JVM lift [MinisMainDocGate]
            // was created for.
            decodeDataUrlPayload(header, dataUrl.substring(comma + 1))
        } catch (t: Throwable) {
            reportBlobFailure(filename, "payload decode failed: ${t.message}")
            return
        }
        Log.i(TAG, "blob download decoded: $safeName (${bytes.size} bytes, mime=$mime)")
        onBlobDownloadData?.invoke(bytes, safeName, mime)
    }

    /**
     * [r3-B09 F-10] One funnel for "this download died before the pool saw a
     * byte": log for forensics AND tell the owner, so the failure reaches the
     * downloads panel + chat instead of dying in logcat.
     */
    private fun reportBlobFailure(filename: String?, reason: String) {
        Log.w(TAG, "blob download failed${filename?.let { " ($it)" } ?: ""}: $reason")
        onBlobDownloadFailed?.invoke(filename, reason)
    }

    init {
        configureWebView(webView, profile)
        webView.addJavascriptInterface(jsBridge, "__minis__")
        setupWebViewClient()
        setupWebChromeClient()
        // Intercept page-triggered downloads (Content-Disposition attachment,
        // <a download>, unrenderable MIME types). Without a listener, WebView
        // silently drops these — the user taps "download" and nothing happens.
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            Log.i(TAG, "onDownloadStart: ${url.take(120)} mime=$mimetype len=$contentLength")
            when {
                // blob: object URLs only exist inside the page — read via JS.
                url.startsWith("blob:") -> fetchBlobDownload(url, contentDisposition, mimetype)
                // data: URLs carry the payload inline — decode directly
                // (java.net.URL can't fetch them in the pool's downloader).
                // [audit2-A2] In-process call: no bridge token involved, the
                // value never travelled through page JS.
                url.startsWith("data:") -> {
                    val name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                    deliverBlobDownload(url, name)
                }
                else -> onDownloadStart?.invoke(url, userAgent, contentDisposition, mimetype, contentLength)
            }
        }
        // Track the on-screen WebView width so applyViewport() can compute a
        // shrink-to-fit initial scale. The synthetic measure/layout pass that
        // applyViewport performs to make `window.innerWidth` match the agent
        // viewport is independent of the container the AndroidView is hosted
        // in — without this listener we have no way to learn the container's
        // visible width, and oversize CSS viewports (e.g. 1280×800 on a
        // ~1080px-wide phone) would render off-screen to the right.
        webView.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val w = r - l
            if (w > 0 && w != lastKnownContainerWidthPx) {
                lastKnownContainerWidthPx = w
                // Re-apply the initial scale if we already have an active
                // viewport — covers rotation, sheet resize, container layout
                // settling after the WebView is first parented.
                lastAppliedViewport?.let { (vw, _) -> applyShrinkToFit(vw) }
            }
        }
    }

    /**
     * Read a blob: URL from inside the page's JS context and deliver its bytes
     * through the `__minis__.saveBlobDownload` bridge. blob: object URLs are
     * scoped to the page — they cannot be fetched from native code, so this
     * injected fetch + FileReader round-trip is the only way to get the data.
     *
     * [audit2-A2] Arms [blobDownloadActiveToken] first and interpolates that
     * token into the injected script, so the bridge only accepts the callback
     * from THIS reader.
     */
    private fun fetchBlobDownload(blobUrl: String, contentDisposition: String?, mimeType: String?) {
        // [deep-audit-1045 B-02] Check the latch BEFORE arming the credential:
        // a dispatch that [runJsOnMain] will refuse must not leave a one-shot
        // bridge token armed for a request that can never come back.
        if (destroyed) {
            reportBlobFailure(
                android.webkit.URLUtil.guessFileName(blobUrl, contentDisposition, mimeType),
                "the tab was closed before the download could start",
            )
            return
        }
        val guessedName = android.webkit.URLUtil.guessFileName(blobUrl, contentDisposition, mimeType)
        // [audit2-A2] Arm the one-shot bridge credential for THIS fetch: the
        // token interpolated below is the only value the bridge will accept
        // until the page's FileReader hands its data back. Kept separate from
        // the async-JS slot's token (rather than reusing it) so the download
        // path stays independent of any in-flight executeJS request — the two
        // have different lifetimes and would otherwise clear each other's token.
        val token = UUID.randomUUID().toString()
        blobDownloadActiveToken = token
        val js = """
            (function() {
                fetch(${JSONObject.quote(blobUrl)})
                    .then(function(r) { return r.blob(); })
                    .then(function(blob) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            __minis__.saveBlobDownload(${JSONObject.quote(token)}, reader.result, ${JSONObject.quote(guessedName)});
                        };
                        reader.onerror = function() { __minis__.blobDownloadError('FileReader error'); };
                        reader.readAsDataURL(blob);
                    })
                    .catch(function(e) { __minis__.blobDownloadError(String(e)); });
            })();
        """.trimIndent()
        // [deep-audit-1045 B-02] NOT `webView.post { … }`: that is a View-queue
        // write. On a DETACHED WebView — which every headless pool tab is until
        // something attaches it to a window — the runnable parks in View's
        // HandlerActionQueue and is only flushed on a later attach. For a tab
        // that never attaches, the injected FileReader therefore never runs:
        // the download silently never lands and the one-shot bridge token armed
        // above is never consumed. `evaluateJavascript` needs the main THREAD,
        // not a window (the pool drives detached tabs this way everywhere
        // else), so the injection goes through the same main-thread chokepoint
        // [setUserAgent] uses — one that always drains.
        runJsOnMain("blob-download") { webView.evaluateJavascript(js, null) }
    }

    /**
     * [deep-audit-1045 B-02] Main-thread chokepoint for one-shot JS injections
     * requested from a non-suspending caller (download listener, WebView
     * client callbacks).
     *
     * Two things it owns that a bare `View.post` does not:
     *  1. **the queue** — a main-`Looper` hop always drains; a detached View's
     *     `post` queue waits for an attach that a headless pool tab never gets
     *     ([jsDispatchFor] carries the whole decision).
     *  2. **the latch** — the hop can land *after* [destroy], so the destroyed
     *     flag is re-checked at execution time, and a WebView throwing
     *     "… on a destroyed WebView" is logged instead of crashing the app.
     */
    private fun runJsOnMain(what: String, block: () -> Unit) {
        when (jsDispatchFor(destroyed, Looper.myLooper() == Looper.getMainLooper())) {
            JsDispatch.REJECT_DESTROYED ->
                Log.w(TAG, "JS dispatch dropped ($what): the WebView was already destroyed")
            JsDispatch.INLINE -> runJsNow(what, block)
            JsDispatch.HOP_TO_MAIN -> Handler(Looper.getMainLooper()).post { runJsNow(what, block) }
        }
    }

    /** Run [block] if the tab is still alive; a destroy race stays a log line. */
    private fun runJsNow(what: String, block: () -> Unit) {
        if (destroyed) {
            Log.w(TAG, "JS dispatch dropped ($what): the WebView was destroyed before it ran")
            return
        }
        runCatching(block).onFailure {
            Log.w(TAG, "JS dispatch failed ($what): ${it.message}")
        }
    }

    /**
     * Latest on-screen width (in physical pixels) of the AndroidView hosting
     * this WebView. Updated by the layout listener installed in [init];
     * 0 until the WebView is parented and laid out for the first time.
     */
    private var lastKnownContainerWidthPx: Int = 0

    /**
     * Compute and install a `setInitialScale` so a page authored at
     * [cssWidth] CSS pixels fits inside the visible WebView container. Called
     * after every [applyViewport] and on every container size change.
     *
     * `setInitialScale(percent)` is sticky — it applies on the next page
     * load. The tab pool's `applyViewportToAllTabs()` already reloads each
     * tab after viewport changes, so the scale is picked up on that reload.
     * Plain navigation between pages reuses whatever scale was last set.
     *
     * Passing 0 restores WebView's default behavior (use page's own scale).
     * We pass 0 whenever the CSS viewport already fits — no point shrinking
     * a 412-wide viewport on a 1080-wide container.
     */
    private fun applyShrinkToFit(cssWidth: Int) {
        val containerPx = lastKnownContainerWidthPx
        if (containerPx <= 0 || cssWidth <= 0) return
        val density = webView.resources.displayMetrics.density
        val cssWidthPx = (cssWidth * density).toInt()
        var scalePct = if (cssWidthPx > containerPx) {
            ((containerPx.toLong() * 100) / cssWidthPx).toInt().coerceAtLeast(1)
        } else {
            0 // CSS viewport already fits — let WebView use its default scale.
        }
        // [T-android-browser-blank] Guard against a corrupted container width
        // (e.g. a synthetic 1px layout leaking into the layout listener): a
        // microscopic sticky initial scale renders the next page load
        // effectively blank. No legitimate shrink-to-fit is below ~10%
        // (desktop 1280 CSS on a 360dp phone is ~28%) — fall back to the
        // WebView default instead.
        if (scalePct in 1..9) {
            Log.w(TAG, "applyShrinkToFit: implausible scale $scalePct% (container=${containerPx}px, css=${cssWidthPx}px) — using default")
            scalePct = 0
        }
        webView.setInitialScale(scalePct)
    }

    // originOf() and isAppAssetsOrigin() moved into MinisMainDocGate below so
    // the whole minis:// trust decision is one JVM-testable pure unit.

    /**
     * [audit-RC13] Same-origin policy gate for minis:// SUBRESOURCE loads.
     * Thin delegation to [MinisMainDocGate], which owns the decision (and the
     * only reason it is JVM-testable): see its KDoc for the full rationale —
     * a committed main document is the sole authority, a page-supplied Origin
     * header can never rescue a disallowed document, and both arms fail closed.
     */
    internal fun minisMainDocAllowsSubresource(
        mainDocUrl: String?,
        originHeader: String?,
    ): Boolean = MinisMainDocGate.allowsSubresource(mainDocUrl, originHeader)

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewClient() {
        webView.webViewClient = object : SafeWebViewClient() {
            /**
             * [GH#341] The renderer for this tab died. The app must not abort
             * (that is [SafeWebViewClient]'s job), but this WebView instance is
             * gone for good, so a pending `navigate()` wait has to learn about
             * it — otherwise the caller sits until the navigation timeout and
             * then reports `TIMED_OUT`, blaming the network for a dead
             * renderer. Complete it as [NavigationOutcome.FAILED] with a
             * description that says what actually happened.
             */
            override fun onRendererGone(view: WebView?) {
                _isLoading.value = false
                // [r3-B09 F-02] Arm the inertness latch FIRST: every later entry
                // into this instance (this navigation's metadata read, the next
                // navigate(), the auto-snapshot) has to be able to see that no
                // callback will ever arrive again.
                rendererDead = true
                Log.e(TAG, "renderer gone — failing pending navigation")
                navigationDeferred?.complete(
                    NavigationResult(
                        NavigationOutcome.FAILED,
                        errorDescription = "WebView renderer process was killed by the system",
                    )
                )
                navigationDeferred = null
                // [deep-audit-1019 ISSUE-08] The navigation wait has been woken
                // here for a long time; the async-JS wait sitting next to it was
                // not. `__minis__.resolve` / `.reject` live inside this renderer,
                // so an in-flight executeJS()/fetch() would have burned its full
                // 30s/60s timeout and then reported a JS timeout for a tab whose
                // renderer is already gone. Completed with the bridge's own
                // reject shape (`{"error":…}`, same literal form as the eviction
                // message in beginAsyncJsRequest) — a bare text payload would
                // fall through executeJS's "not JSON" branch and surface as a
                // SUCCESSFUL result for a request that never ran.
                asyncJsSlot.takeCurrent()?.complete(
                    "{\"error\":\"WebView renderer process was killed before the async JS request completed\"}"
                )
                // Deliberately NOT destroying the instance here (unlike the
                // rebuild-by-key hosts): this tab is still in BrowserTabPool's
                // list and the pool keeps handing the same WebView to the next
                // navigate()/screenshot call. Destroying it would turn "this
                // tab is blank" into "every later call on this tab throws",
                // which is worse than an inert instance. The handle slot is
                // released when the tab is closed or the pool is disposed.
                //
                // ponytail: 只收敛等待者，不重建 tab | 天花板: 该 tab 的 WebView
                // 永久空白，后续 navigate()/screenshot 全部失效，需用户手动关掉
                // 再开一个 tab | 升级触发: 真机日志出现
                // "renderer gone — failing pending navigation" 后用户仍频繁遇到
                // 浏览器 tab 空白（说明手动重开不可接受），届时在此处接入
                // BrowserTabPool 的 tab 淘汰 + 重建。
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val urlStr = request.url?.toString()
                // [deep-audit-1016 9a] A page must not be able to hand this tab
                // a minis:// document: that discloses a sandbox file into the
                // tab, and if the landed file carries script it becomes a
                // trusted main document that may then pull *other* minis://
                // files. Refuse a top-level minis:// navigation unless the
                // document that initiated it is already trusted. Tested against
                // the committed document, i.e. before this navigation could
                // change it; a tab with no document yet passes, so the app's
                // own first load (loadUrl does not reach this hook) and a
                // trusted page's navigations are unaffected.
                //
                // Deliberately no completeNavigationExternally here, unlike the
                // two branches below: those end a load the app is waiting on,
                // whereas this hook only sees renderer-initiated navigations —
                // never the navigation a navigate() wait is attached to. There
                // is also no NavigationOutcome for "blocked", so completing the
                // wait would mislabel it as ROUTED_EXTERNALLY.
                if (request.isForMainFrame &&
                    !MinisMainDocGate.allowsTopLevelNavigation(lastMainDocUrl, urlStr)
                ) {
                    Log.w(
                        TAG,
                        "Blocked top-level minis:// navigation from mainDoc=" +
                            "${lastMainDocUrl?.take(80)} target=${urlStr?.take(80)}",
                    )
                    return true
                }
                // T234: Google permanently disallows WebView for sign-in /
                // OAuth. Hand any auth-domain navigation to Chrome Custom
                // Tab so the user can complete login in their real Chrome
                // session instead of hitting 403 disallowed_useragent.
                if (GoogleAuthRouter.shouldRouteExternally(urlStr)) {
                    if (urlStr != null) {
                        GoogleAuthRouter.openInCustomTab(view.context, urlStr)
                    }
                    // Main frame only: a subframe routed away does not end the
                    // page-load we are waiting on.
                    if (request.isForMainFrame) completeNavigationExternally(urlStr)
                    return true
                }
                // T134: route intent://, market://, tel:, mailto:, … out
                // of the WebView so they reach the matching app instead of
                // surfacing as ERR_UNKNOWN_URL_SCHEME.
                val handled = com.rikkaminis.app.ui.browser.BrowserExternalSchemeHandler
                    .handle(view.context, request.url)
                // [audit-0916b] Both branches above cancel the in-WebView
                // navigation, so no page-load callback will ever fire for it. A
                // pending navigate() wait would otherwise sit until the
                // navigation timeout and then report the wrong thing (before
                // this fix: a plain success).
                if (handled && request.isForMainFrame) completeNavigationExternally(urlStr)
                // [audit-RC13 / deep-audit-1016 9a] Commit the minis:// gate's
                // main-document for a MAIN-frame navigation this hook actually
                // lets the WebView carry out. This write used to sit at the top
                // of the method, ahead of the two cancel branches above, so a
                // URL this hook then refuses was recorded as the tab's document
                // while the page that named it kept running — the gate trusted
                // an origin the tab never committed to. Kept here rather than
                // dropped so the RC13 intent still holds: an adopted navigation
                // is committed before the new document's own subresources fire.
                if (!handled && request.isForMainFrame && !urlStr.isNullOrEmpty()) {
                    lastMainDocUrl = urlStr
                }
                return handled
            }

            override fun onPageFinished(view: WebView, url: String?) {
                _isLoading.value = false
                _currentURL.value = url ?: ""
                _pageTitle.value = view.title ?: ""
                _canGoBack.value = view.canGoBack()
                _canGoForward.value = view.canGoForward()
                // [audit-RC13] Final commit of the tab's main document for the
                // minis:// subresource gate (covers redirects that never passed
                // through a shouldOverrideUrlLoading main-frame branch).
                if (!url.isNullOrEmpty()) lastMainDocUrl = url
                // [audit-0916b] LOADED — this is the only path that means "the
                // page finished loading". A main-frame onReceivedError has
                // already completed (and cleared) the same deferred, so the
                // error page's own onPageFinished is a no-op here.
                navigationDeferred?.complete(NavigationResult(NavigationOutcome.LOADED))
                navigationDeferred = null
                // Record in browser history
                val histUrl = url ?: ""
                val histTitle = view.title ?: ""
                if (histUrl.isNotEmpty() && histUrl != "about:blank") {
                    BrowserHistoryStore.getInstance(view.context).record(histUrl, histTitle)
                }
                // T-webview-popup-d3c6e10f (Issue 1): after the pool WebView's
                // setInitialScale settles, force a JS `resize` event so
                // `position:fixed` elements (sticky headers, cookie banners,
                // floating chat) recompute against the post-scale visual
                // viewport instead of the pre-scale layout viewport. Without
                // this, fixed-positioned UI on some sites drifts off the
                // visible region after the synthetic measure+layout pass.
                // [r3-B09 F-06] NOT `view.postDelayed`: a pool tab is detached
                // until the sheet opens, and `View.post*` on a detached view
                // parks the runnable in its HandlerActionQueue — which the
                // destroy-path's `removeCallbacks(null)` cannot reach, because
                // that API is a documented no-op for a null token. The 80ms
                // resize therefore survived the tab: it either fired on a dead
                // renderer after destroy() or was flushed into the next page
                // attach. A main-Looper hop always drains, and [runJsNow]
                // re-checks the destroyed latch when it runs (which is what
                // `browser_destroy_wakeup_check`'s chokepoint criterion pins).
                Handler(Looper.getMainLooper()).postDelayed({
                    runJsNow("post-scale-resize") {
                        webView.evaluateJavascript(
                            "window.dispatchEvent(new Event('resize'));", null,
                        )
                    }
                }, 80)
            }

            /**
             * [r3-B09 F-14] HTTP-level failures (403 / 429 / 503) are NOT
             * [onReceivedError] — that one only reports transport errors, so a
             * rate-limited page used to reach no callback at all and
             * `BrowserRiskControl.detectChallenge(statusCode = …)` had no
             * caller that ever passed a status. Recorded for the main frame
             * only; subresource 404s are page furniture, not a challenge.
             */
            override fun onReceivedHttpError(
                view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame) {
                    lastMainFrameHttpError = errorResponse.statusCode
                    Log.w(TAG, "main-frame HTTP ${errorResponse.statusCode} for ${request.url}")
                }
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    _isLoading.value = false
                    Log.e(TAG, "Navigation error: ${error.description}")
                    // [audit-0916b] FAILED — completed here rather than waiting
                    // for the WebView's own error page to fire onPageFinished,
                    // so the awaiter learns immediately (and, when the load was
                    // refused outright, at all). The metadata appended to the
                    // result is read from the live WebView, so the title may
                    // still be the previous page's — the text says plainly that
                    // the page did not load, which is the point.
                    navigationDeferred?.complete(
                        NavigationResult(
                            NavigationOutcome.FAILED,
                            errorDescription = error.description?.toString(),
                        )
                    )
                    navigationDeferred = null
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // [r3-B09 F-14] One HTTP-error reading per document: the risk
                // detector reads this after every action, and a stale 429 from
                // the previous page would keep throttling a page that loaded
                // fine.
                lastMainFrameHttpError = null
                // [audit-RC13] Commit the main document for the minis://
                // subresource gate BEFORE its subresources fire (onPageStarted
                // is invoked before subresource resolution begins).
                if (!url.isNullOrEmpty()) lastMainDocUrl = url
                // [feat/browser-console-network-upload] A pending file chooser
                // belongs to the page that opened it. Navigating away cancels
                // it (answered with null) so a stale chooser can never
                // swallow a file_upload meant for the next page.
                cancelPendingFileChooser("page navigated to ${url?.take(80)}")
            }

            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                val url = request.url ?: return null
                // [feat/browser-console-network-upload] Observe (never
                // intercept) every real http/https request the page makes —
                // main frame + subresources + XHR/fetch all pass through
                // here. minis:// resolutions are app plumbing, not page
                // traffic, and are not recorded. This callback runs on a
                // background thread; the ring buffer is synchronized.
                val scheme = url.scheme
                if (scheme == "http" || scheme == "https") {
                    recordNetwork(
                        NetworkEntry(
                            timestamp = System.currentTimeMillis(),
                            method = request.method ?: "GET",
                            url = url.toString(),
                            isMainFrame = request.isForMainFrame,
                        )
                    )
                }
                if (url.scheme != "minis") return null
                // [audit-RC13] Only serve minis:// from page-initiated loads that
                // are either the main frame (agent navigation / top-level href)
                // or a subresource of a same-app-origin main document (minis://
                // page pulling minis:// assets, appassets preview, about:blank
                // bootstrap). A subresource of any WEBSITE main document —
                // with or without an Origin header (<img src> / <iframe src> /
                // <script src> send none) — must not be able to pull local
                // workspace files through it (SSRF / cross-session file
                // disclosure vector). Origin is looked up case-insensitively:
                // Chromium may hand us "Origin" or "origin".
                if (!request.isForMainFrame) {
                    val headers = request.requestHeaders
                    val originHeader = headers?.entries?.firstOrNull {
                        it.key.equals("Origin", ignoreCase = true)
                    }?.value
                    // null mainDoc (no committed main document yet) evaluates
                    // fail-closed: only an explicit allowed-app Origin header
                    // can still let it through, everything else is blocked.
                    val mainDoc = lastMainDocUrl?.takeIf { it.isNotBlank() }
                    if (!minisMainDocAllowsSubresource(mainDoc, originHeader)) {
                        Log.w(TAG, "Blocked cross-origin minis:// subresource from " +
                            "mainDoc=${mainDoc?.take(80)} origin=${originHeader?.take(60)}")
                        val blockMsg = "Blocked: minis:// subresource requires a " +
                            "minis://, appassets, or about:blank main document"
                        return android.webkit.WebResourceResponse(
                            "text/plain", "UTF-8", 403, "Forbidden",
                            emptyMap(),
                            blockMsg.toByteArray(Charsets.UTF_8).inputStream(),
                        )
                    }
                } else {
                    // Main-frame minis:// load (agent navigation): commit it as
                    // the tab's main document BEFORE its own subresources fire
                    // (belt-and-braces with the onPageStarted commit — the two
                    // write the same value in WebView's ordering).
                    lastMainDocUrl = url.toString()
                }
                return interceptMinisURL(url)
            }
        }
    }

    /** Resolve minis:// URLs to local workspace files. */
    private fun interceptMinisURL(uri: android.net.Uri): android.webkit.WebResourceResponse? {
        try {
            // minis://workspace/foo.html → /var/minis/workspace/foo.html, then
            // resolve to the host file. Per-session subdirs (workspace /
            // attachments / offloads / browser) resolve through
            // [PRootKernel.resolvePathForSession] — pinned to THIS chat
            // session's minis-sessions/<sid>/ tree (T178 pattern, same as
            // file_upload) instead of the global fallback that returns the
            // first same-named file across ALL sessions (cross-session
            // disclosure). Non-session paths (minis://attachments-shared/…,
            // minis://minis-global/…) fall through to the global resolver
            // inside resolveSessionHostPath itself. [audit3 F-08] A path the
            // session resolver *rejects* stays rejected — resolvePathForSession
            // never re-resolves it globally.
            val host = uri.host ?: return null
            val path = uri.path ?: ""
            val linuxPath = "/var/minis/$host$path"
            // runCatching keeps the pre-existing exception safety, but its null
            // is now the single answer acted on below: a session-scoped
            // rejection must not fall back to the global resolver.
            val localFile = runCatching {
                PRootKernel.resolvePathForSession(minisSessionId, linuxPath, webView.context)
            }.getOrNull()
            if (localFile == null || !localFile.exists() || !localFile.isFile) {
                return android.webkit.WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                    emptyMap(), "File not found: $host$path".byteInputStream())
            }
            val mimeType = guessMimeType(localFile.name)
            // For HTML mainframe responses, inject a `<meta viewport>` matching
            // the agent's session viewport when the page doesn't declare one.
            // Without this, Android WebView falls back to a hardcoded 980 CSS
            // px width regardless of the WebView's measured size, making
            // `set_viewport` look like a no-op for `minis://` HTML pages.
            val stream = if (mimeType == "text/html" && lastAppliedViewport != null) {
                ensureMetaViewport(localFile.readBytes(), lastAppliedViewport!!.first)
            } else {
                localFile.inputStream()
            }
            return android.webkit.WebResourceResponse(mimeType, "UTF-8", 200, "OK",
                emptyMap(),
                stream)
        } catch (e: Exception) {
            Log.w(TAG, "minis:// intercept error: ${e.message}")
            return null
        }
    }

    /**
     * If the HTML lacks a `<meta name="viewport">`, splice one in matching
     * the agent's CSS-px viewport. Leaves pages that already declare a
     * viewport untouched so author intent (e.g. `width=1200`) wins.
     */
    private fun ensureMetaViewport(html: ByteArray, cssWidth: Int): java.io.InputStream {
        val text = String(html, Charsets.UTF_8)
        if (text.contains("name=\"viewport\"", ignoreCase = true) ||
            text.contains("name='viewport'", ignoreCase = true)) {
            return text.toByteArray(Charsets.UTF_8).inputStream()
        }
        // user-scalable=yes is the default but state it explicitly so a
        // future change to WebView defaults can't silently disable
        // pinch-zoom on the agent's auto-injected viewport.
        val meta = "<meta name=\"viewport\" content=\"width=$cssWidth, initial-scale=1.0, user-scalable=yes\">"
        val headIdx = text.indexOf("<head", ignoreCase = true).takeIf { it >= 0 }?.let {
            text.indexOf('>', it).takeIf { gt -> gt >= 0 }?.plus(1)
        }
        val rewritten = if (headIdx != null) {
            text.substring(0, headIdx) + meta + text.substring(headIdx)
        } else {
            // No <head>: prepend the meta so it's still parsed before body.
            "$meta$text"
        }
        return rewritten.toByteArray(Charsets.UTF_8).inputStream()
    }

    private fun setupWebChromeClient() {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                _pageTitle.value = title ?: ""
            }

            /**
             * [feat/browser-console-network-upload] Capture every page JS
             * console message into the per-tab ring buffer so the agent can
             * read page errors (get_console_messages) instead of guessing at
             * a blank/broken page. Return false — default logging continues.
             */
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage ?: return false
                recordConsole(
                    ConsoleEntry(
                        timestamp = System.currentTimeMillis(),
                        level = consoleMessage.messageLevel()?.name ?: "LOG",
                        message = consoleMessage.message() ?: "",
                        line = consoleMessage.lineNumber(),
                        source = consoleMessage.sourceId() ?: "",
                    )
                )
                return false
            }

            /**
             * [feat/browser-console-network-upload] The page opened a file
             * chooser (agent clicked an <input type="file">). Returning true
             * means WE own the answer: the file_upload action resolves the
             * callback with FileProvider URIs. A stale chooser (page opened
             * a second one, or navigation) is cancelled with null first —
             * WebView tolerates exactly one live callback per chooser.
             */
            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                val callback = filePathCallback ?: return false
                pendingFileChooser?.let { old -> runCatching { old.onReceiveValue(null) } }
                pendingFileChooser = callback
                pendingFileAccept = fileChooserParams?.acceptTypes
                    ?.filterNotNull()?.joinToString(",")
                    ?.takeIf { it.isNotBlank() }
                Log.i(TAG, "file chooser opened (accept=$pendingFileAccept)")
                return true
            }

            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                onNewWindow?.invoke(resultMsg)
                return onNewWindow != null
            }

            override fun onCloseWindow(window: WebView) {
                onCloseWindow?.invoke()
            }
        }
    }

    // -- Execute Action --

    suspend fun execute(input: BrowserActionInput): BrowserActionResult {
        val prevUrl = withContext(Dispatchers.Main) { webView.url }
        var result: BrowserActionResult = when (input.action) {
            BrowserAction.NAVIGATE -> navigate(input.url)
            BrowserAction.SCREENSHOT -> return screenshot(fullPage = input.fullPage)
            BrowserAction.CLICK -> click(input.selector, input.coordinateX, input.coordinateY)
            BrowserAction.TYPE -> type(input.selector, input.text)
            BrowserAction.GET_TEXT -> return getText(input.selector)
            BrowserAction.SCROLL -> scroll(input.selector, input.direction, input.amount)
            BrowserAction.GET_PAGE_INFO -> return getPageInfo()
            // [fix/browser-trio-audit] EXECUTE_JS must NOT return early: the
            // file-chooser hint below checks for it, and a `return` here made
            // that branch dead code — execute_js is the common way agents
            // trigger a chooser (document.querySelector('input[type=file]').click()),
            // and its result never showed the hint. Falling through is safe:
            // EXECUTE_JS is not in visualChangeActions, so no snapshot side
            // effect is added by the code below.
            BrowserAction.EXECUTE_JS -> executeJS(input.script)
            BrowserAction.FIND_ELEMENTS -> return findElements(input.selector)
            BrowserAction.HOVER -> hover(input.selector)
            BrowserAction.GET_READABLE -> return getReadable()
            BrowserAction.SET_USER_AGENT -> return setUserAgent(input.userAgent)
            BrowserAction.SET_VIEWPORT ->
                return BrowserActionResult.error("set_viewport must be routed through BrowserTabPool")
            BrowserAction.GET_BACKBONE -> return getBackbone(input.maxDepth)
            BrowserAction.FETCH -> return fetch(input.url)
            BrowserAction.GET_COOKIES -> return getCookies(input.keywords, input.fuzzy)
            BrowserAction.SET_COOKIES -> return setCookies(input.cookies)
            BrowserAction.SCROLL_AND_COLLECT -> return scrollAndCollect(
                input.scrollCount, input.itemSelector, input.keywords,
            )
            BrowserAction.WAIT_FOR_DOM_STABLE -> return waitForDomStable(input.timeoutMs)
            BrowserAction.GET_CONSOLE_MESSAGES -> return getConsoleMessages(input.clear)
            BrowserAction.GET_NETWORK_REQUESTS -> return getNetworkRequests(input.clear)
            // Non-return branch: falls through to the visualChange snapshot so
            // the agent sees the page's reaction to the uploaded file.
            BrowserAction.FILE_UPLOAD -> fileUpload(input)
            BrowserAction.NEW_TAB, BrowserAction.CLOSE_TAB, BrowserAction.LIST_TABS ->
                return BrowserActionResult.error("Tab management actions must be routed through BrowserTabPool")
        }

        // [feat/browser-console-network-upload] If a click / execute_js just
        // opened a page file chooser (an <input type="file">), tell the agent
        // what to do next — the page is blocked waiting for files and only
        // file_upload can answer it. Without this hint the agent sees a
        // successful click and moves on, leaving the chooser dangling until
        // navigation cancels it.
        if (result.success &&
            (input.action == BrowserAction.CLICK || input.action == BrowserAction.EXECUTE_JS) &&
            pendingFileChooser != null
        ) {
            delay(150) // onShowFileChooser is posted to the main thread — give it a beat
            if (pendingFileChooser != null) {
                val accept = pendingFileAccept?.takeIf { it.isNotBlank() }
                result = result.copy(
                    text = result.text + "\n[File chooser opened]" +
                        (accept?.let { " (accept: $it)" } ?: "") +
                        " — the page is waiting for files. Call file_upload with 'paths' " +
                        "(Linux paths, e.g. [\"/var/minis/attachments/img.png\"]) to answer it."
                )
            }
        }

        // Auto-capture screenshot after visual-change actions
        //
        // [audit-0916b] NAVIGATE is exempt from the success gate: it always
        // produced a snapshot before (a failed navigation used to be reported as
        // success), and "what is on screen right now" is precisely what the
        // agent needs to judge a navigation that did not confirm — the error
        // text deliberately allows for a page that is merely still loading.
        // Every other action keeps the original success-only gate.
        if ((result.success || input.action == BrowserAction.NAVIGATE) &&
            BrowserAction.visualChangeActions.contains(input.action)
        ) {
            result = attachSnapshot(result)
        }

        // Detect URL change after visual-change actions (ignore hash-only changes)
        if (result.success && BrowserAction.visualChangeActions.contains(input.action)) {
            val newUrl = withContext(Dispatchers.Main) { webView.url }
            if (prevUrl != null && newUrl != null) {
                // [fix/audit-s6l1] skip noise from intercepted/internally-owned
                // URLs (about:blank, minis://) — hash comparison is meaningless
                // for them and previously produced spurious "Page navigated"
                // lines.
                val isNoise = { u: String ->
                    u == "about:blank" || u.startsWith("minis://") || u.startsWith("data:")
                }
                if (!isNoise(prevUrl) && !isNoise(newUrl)) {
                    val prevNoHash = prevUrl.substringBefore("#")
                    val curNoHash = newUrl.substringBefore("#")
                    if (prevNoHash != curNoHash) {
                        result = result.copy(
                            text = result.text + "\n[URL Changed] Page navigated: $prevUrl -> $newUrl. Take a screenshot to see the current state before continuing."
                        )
                    }
                }
            }
        }

        return result
    }

    private suspend fun attachSnapshot(result: BrowserActionResult): BrowserActionResult {
        return try {
            delay(300) // Let page settle
            val bitmap = captureWebViewBitmap() ?: return result
            val file = saveBitmapToFile(bitmap, "snapshot", SNAPSHOT_QUALITY)
            bitmap.recycle()
            // [deep-audit-1048 A-02] A failed encode leaves no file to point at;
            // the result keeps its text and simply carries no image (rather than
            // a path to a 0-byte file).
            if (file == null) result else result.copy(imageFilePath = file.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "Auto-snapshot failed: ${e.message}")
            result
        }
    }

    // -- Navigate --

    private suspend fun navigate(urlString: String?): BrowserActionResult {
        if (urlString.isNullOrEmpty()) return BrowserActionResult.error("Missing 'url' parameter")

        // [r3-B09 F-02] A dead tab never reports back. Before this short-circuit
        // an explicit-tab navigate onto a renderer-killed / destroyed tab loaded
        // into nothing, waited out the whole navigation timeout, then asked the
        // live page for its title/viewport/scroll — and `evaluateJavascript` has
        // no timeout at all, so the call hung past the timeout until the tool
        // deadline (~120s) and reported a network problem for a tab that is gone.
        if (webViewInert()) return webViewGoneResult()

        // [audit-0916] Scheme-aware normalization — see normalizeBrowserUrl.
        // The previous `contains("://")` test turned `about:blank` into
        // `https://about:blank`, which Chromium refuses to load.
        val normalized = normalizeBrowserUrl(urlString)

        val deferred = CompletableDeferred<NavigationResult>()
        navigationDeferred = deferred
        _isLoading.value = true

        withContext(Dispatchers.Main) {
            // Re-assert the last applied viewport before loadUrl. Intercepted
            // navigations (minis://) served via shouldInterceptRequest skip
            // the layout pass that a real network load triggers, so without
            // this the page reports Android WebView's 980px no-meta fallback
            // even when a session override (e.g. 960x540) is active.
            lastAppliedViewport?.let { (w, h) -> applyViewport(w, h) }
            webView.loadUrl(normalized)
        }

        // Wait with timeout
        val navTimeoutSec = AgentRuntimeLimitsPrefs.browserNavTimeoutSec()
        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            // [audit-0909 T7-H1] Complete UNCONDITIONALLY. navigationDeferred
            // may have been replaced by another coroutine inside this 30s
            // window (reloadAndWait / a second navigate / applyViewportToAllTabs
            // from set_viewport or a UA switch). The old identity check then
            // skipped complete() forever — this runnable fires only once, and
            // onPageFinished only completes the *current* deferred — so the
            // original await() hung with no completion path until the run
            // deadline. complete() is idempotent; only the reference cleanup
            // (and the loading flag) stay identity-guarded.
            if (navigationDeferred === deferred) {
                Log.w(TAG, "Navigation timed out for $normalized")
                _isLoading.value = false
                navigationDeferred = null
            }
            // [audit-0916b] TIMED_OUT, not a bare Unit: nothing reported that
            // the page finished loading, so the caller must not answer success.
            deferred.complete(NavigationResult(NavigationOutcome.TIMED_OUT))
        }
        handler.postDelayed(timeoutRunnable, navTimeoutSec * 1000L)

        val navResult = try {
            deferred.await()
        } finally {
            handler.removeCallbacks(timeoutRunnable)
        }

        _currentURL.value = _currentURL.value.ifEmpty { normalized }
        _isLoading.value = false

        // [audit-0916b] LOADED keeps the historical URL source byte-for-byte.
        // See navigationReportedUrl.
        val reportedUrl = navigationReportedUrl(
            outcome = navResult.outcome,
            currentUrl = _currentURL.value,
            requestedUrl = normalized,
        )

        // [audit-0916b] Report what actually happened instead of the constant
        // "Navigated to $url" / success=true. §20b: a URL Chromium refuses to
        // load ("Refusing to load for invalid virtual URL: https://about:blank/",
        // 2026-09-16 10:40:55) fires neither onPageFinished nor onReceivedError,
        // so it burned the whole navigation timeout and was then reported as a
        // successful navigation (10:41:26, success=true output=Navigated to
        // about:blank) — the agent believed it was on a page that never loaded.
        val report = navigationReport(
            outcome = navResult.outcome,
            url = reportedUrl,
            // [r3-B09 F-02] The wake-up can be "the renderer died" / "the tab was
            // destroyed" instead of "the page finished": reading the live page
            // then hangs on a JS callback that cannot arrive, so say plainly that
            // there is no page to describe.
            details = if (webViewInert()) "  (no live WebView — the tab's renderer is gone)" else navigationDetails(),
            errorDescription = navResult.errorDescription,
            routedTarget = navResult.routedTarget,
            timeoutSec = navTimeoutSec,
        )
        return BrowserActionResult(text = report.text, success = report.success)
    }

    /**
     * [audit-0916b] A navigation was handed to another app rather than loaded in
     * the WebView (Google auth → Chrome Custom Tab; tel:/mailto:/intent:/… →
     * the system handler). No page-load callback will ever arrive for it, so any
     * pending navigation wait is completed here — as
     * [NavigationOutcome.ROUTED_EXTERNALLY], which reports success (the tool
     * call did what was asked) while stating that the in-app page did not
     * change, instead of hanging until the timeout and then claiming a
     * navigation that never happened.
     */
    private fun completeNavigationExternally(target: String?) {
        val deferred = navigationDeferred ?: return
        navigationDeferred = null
        _isLoading.value = false
        deferred.complete(
            NavigationResult(NavigationOutcome.ROUTED_EXTERNALLY, routedTarget = target)
        )
    }

    /**
     * [audit-0916b] The page metadata block that follows the outcome line:
     * title / viewport / page size / scroll position, each read from the live
     * WebView. Split out of [navigationMetadata] so the outcome line and the
     * metadata cannot disagree — [navigationMetadata] used to hardcode its own
     * "Navigated to $url" header, which would have duplicated (or worse,
     * contradicted) the error line now prepended for a failed navigation.
     */
    private suspend fun navigationDetails(): String {
        val title = _pageTitle.value
        // Read the viewport directly from the page (`window.innerWidth/Height`)
        // so a session or global viewport override shows the actual layout
        // size, not the UA profile default. Matches iOS which likewise queries
        // the WKWebView's live bounds rather than a profile constant.
        val scrollInfo = evaluateJavascript(
            "JSON.stringify({" +
                "sx:window.scrollX||0,sy:window.scrollY||0," +
                "pw:document.documentElement.scrollWidth||0," +
                "ph:document.documentElement.scrollHeight||0," +
                "vw:window.innerWidth||0,vh:window.innerHeight||0" +
                "})"
        )
        var scrollX = 0; var scrollY = 0; var pageW = 0; var pageH = 0
        var vpW = 0; var vpH = 0
        try {
            val info = JSONObject(scrollInfo)
            scrollX = info.optInt("sx"); scrollY = info.optInt("sy")
            pageW = info.optInt("pw"); pageH = info.optInt("ph")
            vpW = info.optInt("vw"); vpH = info.optInt("vh")
        } catch (_: Exception) {}

        // Fall back to the UA profile default when the page hasn't populated
        // `window.inner*` yet (e.g. navigation failure / about:blank).
        val fallback = currentProfile.viewportSize
        val effectiveVpW = if (vpW > 0) vpW else fallback.first
        val effectiveVpH = if (vpH > 0) vpH else fallback.second

        return buildString {
            if (title.isNotEmpty()) appendLine("  Title: $title")
            appendLine("  Viewport: ${effectiveVpW}x$effectiveVpH")
            if (pageW > 0 || pageH > 0) appendLine("  Page size: ${pageW}x$pageH")
            append("  Scroll position: ($scrollX, $scrollY)")
        }
    }

    // -- Screenshot --

    private suspend fun screenshot(fullPage: Boolean = false): BrowserActionResult {
        var truncated = false
        var originalHeightPx = 0
        var didStretch = false
        var savedW = 0
        var savedH = 0

        if (fullPage) {
            // Measure full document height in CSS pixels.
            val cssScrollHeight = evaluateJavascript("document.documentElement.scrollHeight").let {
                it.trim().toIntOrNull() ?: 0
            }
            val density = webView.resources.displayMetrics.density
            val scrollHeightPx = if (cssScrollHeight > 0) {
                (cssScrollHeight * density).toInt()
            } else {
                withContext(Dispatchers.Main) { webView.height }
            }
            originalHeightPx = scrollHeightPx
            val cappedPx = scrollHeightPx.coerceAtMost(MAX_FULL_PAGE_HEIGHT_PX)
            truncated = scrollHeightPx > MAX_FULL_PAGE_HEIGHT_PX

            // Eagerize lazy images and wait two RAFs so layout settles before capture.
            try {
                evaluateJavascript(
                    """
                    (async () => {
                        document.querySelectorAll('img[loading="lazy"]').forEach(i => i.loading = 'eager');
                        await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
                        return 'ok';
                    })()
                    """.trimIndent()
                )
            } catch (_: Exception) { /* best-effort */ }
            delay(50)

            // Snapshot current viewport, stretch to cssScrollHeight, capture, restore.
            val applied = lastAppliedViewport ?: currentProfile.viewportSize
            savedW = applied.first
            savedH = applied.second
            val cssCappedHeight = (cappedPx / density).toInt().coerceAtLeast(savedH)
            withContext(Dispatchers.Main) {
                applyViewport(savedW, cssCappedHeight)
            }
            didStretch = true
            Log.i(TAG, "full_page stretch: ${savedW}x$cssCappedHeight CSS (px=$cappedPx, original=$scrollHeightPx, truncated=$truncated)")
        }

        val bitmap = try {
            captureWebViewBitmap()
        } finally {
            if (didStretch) {
                withContext(Dispatchers.Main) {
                    applyViewport(savedW, savedH)
                }
            }
        } ?: return BrowserActionResult.error("Failed to capture screenshot")

        val out = ByteArrayOutputStream()
        // [deep-audit-1048 A-02] `compress` answers false when the JPEG encoder
        // wrote nothing — no throw. Both channels below read that as success: the
        // base64 handed to the model and the file whose path was published. The
        // encode verdict is the only place that can differ, so it is answered
        // once, before either channel is built.
        val encoded = bitmap.compress(
            Bitmap.CompressFormat.JPEG,
            AgentRuntimeLimitsPrefs.browserScreenshotQuality(),
            out,
        )
        val jpegBytes = out.toByteArray()
        if (!saveOutcome(streamOpened = true, compressed = encoded)) {
            bitmap.recycle()
            return BrowserActionResult.error("Failed to encode screenshot (JPEG encoder wrote no bytes)")
        }

        // null = the encoder refused the file write too; the caller must not be
        // handed a path to a 0-byte file (see saveBitmapToFile).
        val file = saveBitmapToFile(bitmap, "screenshot")
        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        val w = bitmap.width; val h = bitmap.height
        bitmap.recycle()

        Log.i(TAG, "Screenshot captured: ${file?.absolutePath ?: "<not saved>"}, ${w}x$h, ${jpegBytes.size} bytes (full_page=$fullPage)")

        val meta = viewportMetadata(
            imageW = w,
            imageH = h,
            fileSize = jpegBytes.size,
            fullPage = fullPage,
            truncated = truncated,
            originalHeightPx = originalHeightPx,
        )

        return BrowserActionResult(
            text = meta, base64Image = base64, imageFilePath = file?.absolutePath
        )
    }

    /** Collect viewport + page metadata for screenshot results. Mirrors iOS viewportMetadata. */
    private suspend fun viewportMetadata(
        imageW: Int,
        imageH: Int,
        fileSize: Int,
        fullPage: Boolean = false,
        truncated: Boolean = false,
        originalHeightPx: Int = 0,
    ): String {
        val url = _currentURL.value
        val title = _pageTitle.value

        val scrollInfo = evaluateJavascript(
            "JSON.stringify({" +
                "sx:window.scrollX||0,sy:window.scrollY||0," +
                "pw:document.documentElement.scrollWidth||0," +
                "ph:document.documentElement.scrollHeight||0," +
                "vw:window.innerWidth||0,vh:window.innerHeight||0" +
                "})"
        )
        var scrollX = 0; var scrollY = 0; var pageW = 0; var pageH = 0
        var vpW = 0; var vpH = 0
        try {
            val info = JSONObject(scrollInfo)
            scrollX = info.optInt("sx"); scrollY = info.optInt("sy")
            pageW = info.optInt("pw"); pageH = info.optInt("ph")
            vpW = info.optInt("vw"); vpH = info.optInt("vh")
        } catch (_: Exception) {}

        val fallback = currentProfile.viewportSize
        val effectiveVpW = if (vpW > 0) vpW else fallback.first
        val effectiveVpH = if (vpH > 0) vpH else fallback.second

        return buildString {
            appendLine("Screenshot captured")
            appendLine("  URL: $url")
            if (title.isNotEmpty()) appendLine("  Title: $title")
            appendLine("  Image: ${imageW}x$imageH (${fileSize / 1024}KB)")
            appendLine("  Viewport: ${effectiveVpW}x$effectiveVpH")
            if (pageW > 0 || pageH > 0) appendLine("  Page size: ${pageW}x$pageH")
            if (fullPage) {
                appendLine("  Full page: true")
                if (originalHeightPx > 0) appendLine("  Original height: ${originalHeightPx}px")
                if (truncated) appendLine("  Truncated: true (capped at ${MAX_FULL_PAGE_HEIGHT_PX}px)")
            }
            append("  Scroll position: ($scrollX, $scrollY)")
        }
    }

    /**
     * Public live-preview snapshot — mirrors iOS `webView.takeSnapshot()`.
     * Called by the UI on a timer (e.g. every 3s while a tool is streaming) so
     * the Minis Computer sheet and FloatingToolStatusBar can show the browser
     * state even for actions that don't save an imageFilePath (get_readable,
     * get_text, execute_js, fetch, etc.).
     */
    suspend fun captureLiveSnapshot(): Bitmap? = captureWebViewBitmap()

    private suspend fun captureWebViewBitmap(): Bitmap? = withContext(Dispatchers.Main) {
        // [r3-B09 F-02] `navigate()` captures a snapshot even when it fails, so a
        // dead tab reached the capture path by design; touching a destroyed
        // WebView throws, and a renderer-killed one is a blank surface. Either
        // way there is nothing to show: report "no image" instead of burning the
        // call on a crash log.
        if (webViewInert()) {
            Log.w(TAG, "snapshot skipped: the WebView is gone")
            return@withContext null
        }
        try {
            // WebView may be detached (pool-owned, never added to a window), so
            // width/height can be 0. Ensure it has a layout box matching the
            // agent viewport before drawing.
            val vp = currentProfile.viewportSize
            val density = webView.resources.displayMetrics.density
            var w = webView.width
            var h = webView.height
            if (w <= 0 || h <= 0) {
                // Profile sizes are CSS px; scale to physical px so the CSS
                // viewport actually matches. See applyViewport() for context.
                val targetW = (vp.first * density).toInt().coerceAtLeast(1)
                val targetH = (vp.second * density).toInt().coerceAtLeast(1)
                webView.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(targetW, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(targetH, android.view.View.MeasureSpec.EXACTLY),
                )
                webView.layout(0, 0, targetW, targetH)
                w = targetW; h = targetH
            }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)
            Log.d(TAG, "captureWebViewBitmap ${w}x$h")
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "captureWebViewBitmap failed: ${e.message}")
            null
        }
    }

    /**
     * Encode [bitmap] to the screenshot cache. Returns **null** when the encoder
     * wrote nothing — [Bitmap.compress] reports that by returning false, not by
     * throwing, and a dropped verdict publishes a path to a 0-byte file that the
     * agent (and the vision model) then treat as a real screenshot.
     *
     * [deep-audit-1048 A-02]
     */
    private fun saveBitmapToFile(bitmap: Bitmap, prefix: String, quality: Int = AgentRuntimeLimitsPrefs.browserScreenshotQuality()): File? {
        val filename = "${prefix}_${System.currentTimeMillis()}.jpg"
        val file = File(screenshotsDir, filename)
        val encoded = file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        if (!saveOutcome(streamOpened = true, compressed = encoded)) {
            // Do not leave the 0-byte artefact behind: the cache prune is
            // age-based, so a failed encode would otherwise linger for days and
            // any path that guessed the name would find it.
            file.delete()
            Log.w(TAG, "saveBitmapToFile: encoder wrote no bytes for $filename")
            return null
        }
        // [T-android-screenshot-cache-cap] Bound the local cache after a
        // successful save (see pruneScreenshotCache for the rationale).
        pruneScreenshotCache()
        return file
    }

    // -- Click --

    private suspend fun click(selector: String?, x: Int?, y: Int?): BrowserActionResult {
        // Validate arguments BEFORE the file-input probe: with both null the
        // probe JS would evaluate elementFromPoint(null, null) — JS coerces
        // null to 0 — and silently probe the element at (0,0), which could
        // mis-fire a real touch at the page's top-left corner instead of
        // rejecting the malformed call like the JS path below does.
        if (selector == null && (x == null || y == null)) {
            return BrowserActionResult.error("click requires 'selector' or 'coordinate_x'/'coordinate_y'")
        }
        // [fix/browser-filechooser-gesture] File inputs need a REAL touch:
        // Chromium only opens the page file chooser from a user-activated
        // gesture, and JS-dispatched events (our normal click path) carry
        // no activation, so onShowFileChooser never fires — the click
        // "succeeds" while the page silently waits forever. Probe the
        // target first; a file input (or a label bound to one) gets a
        // synthesized touch instead of the JS event sequence.
        val probe = runCatching {
            JSONObject(evaluateJavascript(BrowserUseJS.clickTargetInfo(selector, x, y)))
        }.getOrNull()
        if (probe != null && probe.optBoolean("isFile", false)) {
            if (probe.has("error")) {
                return BrowserActionResult.error(probe.getString("error"))
            }
            return clickFileInputWithRealTouch(selector, x, y, probe)
        }
        val js = when {
            selector != null -> BrowserUseJS.click(selector)
            x != null && y != null -> BrowserUseJS.clickCoordinate(x, y)
            else -> return BrowserActionResult.error("click requires 'selector' or 'coordinate_x'/'coordinate_y'")
        }
        return evaluateAndReturn(js)
    }

    /**
     * [fix/browser-filechooser-gesture] Dispatch a synthesized touch at the
     * file input's center so Chromium sees a genuine gesture (user
     * activation) and opens the chooser our onShowFileChooser is waiting
     * for. Coordinates: the probe returned CSS viewport units; touch events
     * take WebView-local px — BrowserTouchPlanner converts via the
     * width ratio, which absorbs density / shrink-to-fit / set_viewport.
     */
    private suspend fun clickFileInputWithRealTouch(
        selector: String?,
        x: Int?,
        y: Int?,
        target: JSONObject,
    ): BrowserActionResult {
        val cssX = target.optDouble("cx", Double.NaN)
        val cssY = target.optDouble("cy", Double.NaN)
        val cssVw = target.optDouble("vw", Double.NaN)
        val accept = target.optString("accept", "")
        val (touch, viewW, viewH) = withContext(Dispatchers.Main) {
            val w = webView.width
            val h = webView.height
            Triple(BrowserTouchPlanner.plan(cssX, cssY, cssVw, w, h), w, h)
        }
        if (touch == null) {
            return BrowserActionResult.error(
                "click: cannot map file input center ($cssX, $cssY) CSS px into the WebView " +
                    "(${viewW}x${viewH} px, viewport ${cssVw} CSS px) — element likely outside the visible area",
            )
        }
        withContext(Dispatchers.Main) {
            val downAt = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, touch.x, touch.y, 0)
            val up = MotionEvent.obtain(downAt, downAt + 64, MotionEvent.ACTION_UP, touch.x, touch.y, 0)
            try {
                webView.dispatchTouchEvent(down)
                webView.dispatchTouchEvent(up)
            } finally {
                down.recycle()
                up.recycle()
            }
        }
        // The tap → Chromium gesture → Blink user activation →
        // onShowFileChooser chain lands asynchronously. Give it a beat,
        // then fail LOUDLY if the chooser did not open — silent failure is
        // the exact bug this fix closes. The [File chooser opened] hint in
        // execute() still appends itself afterwards when it did open.
        delay(300)
        val opened = withContext(Dispatchers.Main) { pendingFileChooser != null }
        val text = buildString {
            append("clicked file input")
            if (selector != null) append(" (selector: $selector)")
            else if (x != null && y != null) append(" at ($x, $y)")
            append(" with a real touch gesture at view px (${touch.x.toInt()}, ${touch.y.toInt()})")
            if (accept.isNotBlank()) append(" — accept: $accept")
            if (!opened) {
                append(
                    "\n[Warning] the WebView did not open a file chooser after the real touch — " +
                        "the page may swallow the tap, or this WebView build ignores synthesized " +
                        "touches. Retry the click, or inspect the element."
                )
            }
        }
        return BrowserActionResult(text = text)
    }

    // -- Type --

    private suspend fun type(selector: String?, text: String?): BrowserActionResult {
        if (selector == null) return BrowserActionResult.error("type requires 'selector'")
        if (text == null) return BrowserActionResult.error("type requires 'text'")
        return evaluateAndReturn(BrowserUseJS.type(selector, text))
    }

    // -- Get Text --

    private suspend fun getText(selector: String?): BrowserActionResult {
        val js = BrowserUseJS.getText(selector)
        return evaluateJSAndParse(js)
    }

    // -- Get Readable --

    private suspend fun getReadable(): BrowserActionResult {
        return evaluateJSAndParse(BrowserUseJS.getReadable())
    }

    // -- Scroll --

    private suspend fun scroll(selector: String?, direction: ScrollDirection?, amount: Int?): BrowserActionResult {
        val dir = direction ?: ScrollDirection.DOWN
        val px = amount ?: 500
        val js = BrowserUseJS.scroll(dir, px, selector)
        return evaluateJSAndParse(js)
    }

    // -- Get Page Info --

    private suspend fun getPageInfo(): BrowserActionResult {
        return evaluateAndReturn(BrowserUseJS.getPageInfo())
    }

    // -- Execute JS --

    private suspend fun executeJS(script: String?): BrowserActionResult {
        if (script.isNullOrEmpty()) return BrowserActionResult.error("execute_js requires 'script'")
        // [r5-F F-F-01] Decided BEFORE beginAsyncJsRequest(): a slot armed on an
        // inert tab has no completer (onRendererGone fired at most once; the
        // bridge lives in the dead renderer), so the request would sit out the
        // whole 30s timeout and report a phantom "JavaScript execution timed
        // out" for a tab that is gone. Same one-liner as navigate()'s.
        if (webViewInert()) return webViewGoneResult()
        // Wrap in an async IIFE so `await` works in user scripts.
        // Android WebView doesn't resolve Promises from evaluateJavascript,
        // so we use a JS bridge callback (__minis__.resolve / __minis__.reject).
        // [deep-audit-1016c ISSUE-03B] begin() hoisted out of the try: the
        // catch blocks below must release the slot THEY own (the old
        // declaration was invisible from the catch clauses). It has no
        // throwing surface (UUID + a CompletableDeferred constructor).
        val (deferred, token) = beginAsyncJsRequest()
        return try {
            val wrapped = """
                (async function(){
                    try {
                        var __r__ = (async function(){ $script })();
                        var __v__ = await __r__;
                        if (__v__ === undefined || __v__ === null) {
                            __minis__.resolve('$token', String(__v__));
                        } else if (typeof __v__ === 'object') {
                            __minis__.resolve('$token', JSON.stringify(__v__));
                        } else {
                            __minis__.resolve('$token', String(__v__));
                        }
                    } catch(e) {
                        __minis__.reject('$token', e.message || String(e));
                    }
                })();
            """.trimIndent()
            withContext(Dispatchers.Main) {
                webView.evaluateJavascript(wrapped, null)
            }
            val raw = withTimeoutOrNull(30_000L) { deferred.await() }
                ?: run {
                    finishAsyncJsRequest(token)
                    return BrowserActionResult.error("JavaScript execution timed out (30s)")
                }
            finishAsyncJsRequest(token)
            val json = try { JSONObject(raw) } catch (_: Exception) { null }
            if (json != null) {
                if (json.has("error")) {
                    BrowserActionResult.error(json.getString("error"))
                } else {
                    BrowserActionResult(text = formatJSONResult(json))
                }
            } else {
                BrowserActionResult(text = raw)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // [audit-0917] Cancellation must propagate. The generic catch below
            // swallowed CancellationException and turned a cancelled run into a
            // "JavaScript error" result — the caller then treated an aborted
            // tool call as a completed one. Still release the registry slot.
            finishAsyncJsRequest(token)
            throw e
        } catch (e: Exception) {
            finishAsyncJsRequest(token)
            BrowserActionResult.error("JavaScript error: ${e.message}")
        }
    }

    // -- Find Elements --

    private suspend fun findElements(selector: String?): BrowserActionResult {
        if (selector == null) return BrowserActionResult.error("find_elements requires 'selector'")
        return evaluateAndReturn(BrowserUseJS.findElements(selector))
    }

    // -- Hover --

    private suspend fun hover(selector: String?): BrowserActionResult {
        if (selector == null) return BrowserActionResult.error("hover requires 'selector'")
        return evaluateAndReturn(BrowserUseJS.hover(selector))
    }

    // -- Get Backbone --

    private suspend fun getBackbone(maxDepth: Int?): BrowserActionResult {
        val depth = maxDepth ?: 5
        val js = BrowserUseJS.getBackbone(depth)
        val raw = evaluateJavascript(js)
        return try {
            val json = JSONObject(raw)
            if (json.has("error")) {
                BrowserActionResult.error(json.getString("error"))
            } else {
                BrowserActionResult(text = formatBackboneResult(json))
            }
        } catch (e: Exception) {
            BrowserActionResult.error("JavaScript error: ${e.message}")
        }
    }

    // -- Fetch --

    private suspend fun fetch(urlString: String?): BrowserActionResult {
        if (urlString.isNullOrEmpty()) return BrowserActionResult.error("fetch requires 'url' parameter")

        // The fetch JS runs `await fetch(...)` inside an async IIFE, which
        // resolves to a Promise. Android's `WebView.evaluateJavascript` does
        // NOT await Promises, so calling `evaluateJavascript(js)` returns the
        // Promise's `{}` string representation and the caller sees a
        // "No value for base64" parse error. Route through the __minis__
        // bridge so we actually wait for the Promise to resolve.
        val raw = awaitPromiseJs(BrowserUseJS.fetch(urlString))
            ?: return BrowserActionResult.error("fetch timed out")
        return try {
            val json = JSONObject(raw)
            if (json.has("error")) {
                return BrowserActionResult.error("Fetch failed: ${json.getString("error")}")
            }
            val contentType = json.optString("contentType", "")
            val status = json.optInt("status", 0)
            val finalURL = json.optString("url", urlString)

            // [r3-B09 F-11] 404's body is an error PAGE, not the fetch result:
            // the pre-fix code only read `status` to print it and handed the
            // body over as a successful download. The policy is pure
            // ([fetchHttpFailure]) so the two directions can be pinned with
            // literals: non-2xx (when the JS reported one) is a failure, and a
            // 0-byte 2xx is a legal empty resource, not an error.
            fetchHttpFailure(status, finalURL)?.let { return BrowserActionResult.error(it) }

            // `base64` is optional — only present when the JS captured bytes.
            // If missing, fall back to `text` (JSON / plain text) so callers
            // can fetch human-readable payloads without forcing a byte roundtrip.
            val data: ByteArray? = json.optString("base64").takeIf { it.isNotEmpty() }?.let {
                try { Base64.decode(it, Base64.DEFAULT) } catch (_: Exception) { null }
            } ?: json.optString("text").takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)

            if (data == null) {
                // [r3-B09 F-11] The 200/204-no-body direction: the old code
                // answered "no body" as an ERROR for exactly the responses the
                // fetch succeeded on. An empty 2xx body is a real (empty)
                // result — report it, don't fail it.
                if (status in 200..299) {
                    val text = buildString {
                        appendLine("Fetched $finalURL")
                        appendLine("  Status: $status")
                        appendLine("  Content-Type: $contentType")
                        appendLine("  Size: 0 bytes (empty body)")
                    }
                    return BrowserActionResult(
                        text = text,
                        fetchedFileData = ByteArray(0),
                        fetchedFileName = "fetch_${System.currentTimeMillis()}.${extensionForMimeType(contentType)}",
                    )
                }
                return BrowserActionResult.error("Fetch returned no body (status=$status)")
            }
            val size = json.optInt("size", data.size)
            val filename = "fetch_${System.currentTimeMillis()}.${extensionForMimeType(contentType)}"

            val text = buildString {
                appendLine("Fetched $finalURL")
                appendLine("  Status: $status")
                appendLine("  Content-Type: $contentType")
                appendLine("  Size: ${formatBytes(size)}")
                append("  Filename: $filename")
            }

            BrowserActionResult(
                text = text,
                fetchedFileData = data,
                fetchedFileName = filename,
            )
        } catch (e: Exception) {
            BrowserActionResult.error("Fetch parse error: ${e.message}")
        }
    }

    /**
     * Evaluate an `(async function(){...})()` expression and wait for the
     * returned Promise to resolve via the `__minis__` bridge. Returns the
     * resolved string (JSON or plain) or null on timeout. Mirrors the same
     * pattern used by [executeJS].
     */
    private suspend fun awaitPromiseJs(js: String): String? {
        // [r5-F F-F-01] Same decision as executeJS, one level lower: fetch's
        // only entry here. `onRendererGone` fires at most once and cannot wake
        // a slot armed after it, so registering on an inert tab means the full
        // 60s timeout and a "fetch timed out" for a tab that is gone. The
        // reject shape carries webViewGoneResult()'s wording (deadTabGoneMessage),
        // which `fetch` already parses into "Fetch failed: …".
        deadTabAsyncJsAnswer(destroyed, rendererDead)?.let { return it }
        val (deferred, token) = beginAsyncJsRequest()
        val wrapped = """
            (async function(){
                try {
                    var __v__ = await ($js);
                    if (__v__ === undefined || __v__ === null) {
                        __minis__.resolve('$token', 'null');
                    } else if (typeof __v__ === 'object') {
                        __minis__.resolve('$token', JSON.stringify(__v__));
                    } else {
                        __minis__.resolve('$token', String(__v__));
                    }
                } catch(e) {
                    __minis__.reject('$token', e && e.message ? e.message : String(e));
                }
            })();
        """.trimIndent()
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(wrapped, null)
        }
        // [audit-0917] try/finally so the registry slot is released even when
        // the await is cancelled or times out. Previously a cancellation
        // between begin and finish leaked the entry (and its deferred).
        val raw = try {
            withTimeoutOrNull(60_000L) { deferred.await() }
        } finally {
            finishAsyncJsRequest(token)
        }
        return raw
    }

    // -- Set User Agent --

    /** Set user agent from UI settings (public, non-result). */
    fun setUserAgent(profile: UserAgentProfile, customUA: String? = null) {
        // [deep-audit-1045 B-02] The latch must be the FIRST statement: the
        // thread hop below re-enters this same function, so one check here
        // covers both the direct call and a hop that lands after destroy().
        // Without it the body wrote `settings.userAgentString` and called
        // reload() on a destroyed WebView — "Attempt to perform WebView method
        // on a destroyed WebView" for any UI path that outlived its tab.
        if (destroyed) {
            Log.w(TAG, "setUserAgent dropped: the WebView was already destroyed")
            return
        }
        // [audit-0917] WebView.settings / reload() are main-thread-only, and
        // this is a public entry point. Every current caller happens to be on
        // the main thread, but the failure mode (CalledFromWrongThreadException)
        // is remote from here — hop when needed instead of trusting callers.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { setUserAgent(profile, customUA) }
            return
        }
        currentProfile = profile
        val ua = if (profile == UserAgentProfile.CUSTOM && !customUA.isNullOrEmpty()) customUA
            else profile.userAgentString
        if (ua != null) {
            webView.settings.userAgentString = ua
        }
        applyViewport()
        if (_currentURL.value.isNotEmpty()) {
            webView.reload()
        }
    }

    /**
     * Force the detached pool WebView to lay out at the agent viewport size so
     * page scripts see `window.innerWidth` matching the selected profile (Mobile
     * 412×915 / Desktop 1280×800). Without this, a detached WebView has
     * width/height = 0 and pages render using WebView defaults.
     *
     * The profile dimensions are CSS pixels (iOS "points"). Android WebView
     * uses physical pixels for measure/layout and derives CSS px via
     * window.devicePixelRatio = system density. Passing 412 px directly on a
     * 2.75-density device makes the CSS viewport ~150px wide, causing pages
     * to render at a tiny logical width then upscale, making elements look
     * oversized and clipping on the right. Scale by density so the CSS
     * viewport actually matches the profile.
     */
    fun applyViewport() {
        val vp = currentProfile.viewportSize
        applyViewport(vp.first, vp.second)
    }

    /**
     * Last CSS-pixel viewport applied via [applyViewport]. Used so [navigate]
     * can re-assert the same size before `loadUrl()` — intercepted
     * (`minis://`) loads skip WebView's measure pass, otherwise stranding the
     * page at the 980px no-meta fallback.
     */
    private var lastAppliedViewport: Pair<Int, Int>? = null

    /**
     * Lay out the detached WebView at the given CSS-pixel viewport. Used by
     * [BrowserTabPool] to apply a session or global custom viewport override
     * — mirrors iOS `BrowserUseManager.setViewport(width:height:...)`.
     */
    fun applyViewport(cssWidth: Int, cssHeight: Int) {
        val density = webView.resources.displayMetrics.density
        val w = ((cssWidth * density).toInt()).coerceAtLeast(1)
        val h = ((cssHeight * density).toInt()).coerceAtLeast(1)
        webView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, w, h)
        lastAppliedViewport = cssWidth to cssHeight
        // Pages authored at this CSS width need to fit inside the visible
        // container. Compute and stash a setInitialScale so the next reload
        // (the tab pool always reloads after a viewport change) renders at
        // the shrink-to-fit ratio. pinch-zoom remains enabled because we
        // never touch builtInZoomControls or the page's user-scalable hint.
        applyShrinkToFit(cssWidth)
    }

    private suspend fun setUserAgent(profile: UserAgentProfile?): BrowserActionResult {
        // [deep-audit-1045 B-02] Same latch as the public overload, but this is
        // the AGENT-facing entry (BrowserAction.SET_USER_AGENT → the answer
        // slot). Silently returning would report "Switched to …" for a tab that
        // no longer exists, so the refusal goes into the result.
        if (destroyed) return destroyedTabResult()
        val newProfile = profile ?: UserAgentProfile.MOBILE_CHROME
        val ua = newProfile.userAgentString
        // Every WebView method must be called on the main thread, but the
        // offload handler's `runBlocking { ... execute(...) }` dispatches on
        // a worker. `applyViewport(...)` measures/layouts the detached
        // WebView; settings / reload likewise. Hop to main so we don't
        // crash with "A WebView method was called on thread 'worker-N'".
        val applied = withContext(Dispatchers.Main) {
            // Re-checked after the hop: the tab can be evicted while we are
            // waiting for the main thread.
            if (destroyed) {
                false
            } else {
                currentProfile = newProfile
                if (ua != null) {
                    webView.settings.userAgentString = ua
                }
                applyViewport()
                val oldUrl = _currentURL.value
                if (oldUrl.isNotEmpty()) {
                    webView.reload()
                }
                true
            }
        }
        if (!applied) return destroyedTabResult()
        val vp = newProfile.viewportSize
        return BrowserActionResult(text = "Switched to ${newProfile.value} (${vp.first}x${vp.second})")
    }

    /**
     * [deep-audit-1045 B-02] The answer-slot form of "this tab is gone".
     * Every entry that can outlive its tab returns this instead of a fabricated
     * success or a silent no-op.
     */
    private fun destroyedTabResult(): BrowserActionResult = BrowserActionResult.error(
        "This tab's WebView was already destroyed (the tab was closed or evicted) — " +
            "the user agent was not applied; open a new tab and retry",
    )

    // -- User Navigation --

    fun goBack() { if (webView.canGoBack()) webView.goBack() }
    fun goForward() { if (webView.canGoForward()) webView.goForward() }
    fun reload() { webView.reload() }
    fun stopLoading() { webView.stopLoading(); _isLoading.value = false }

    /**
     * Reload the current page and suspend until `onPageFinished` fires (or
     * the navigation timeout expires). Used by [BrowserTabPool] after a
     * viewport change so a follow-up `get_page_info` reads the new CSS
     * viewport instead of a stale snapshot. Must be called on the main
     * thread.
     *
     * When the tab has no loaded URL (fresh WebView) or is sitting on
     * `about:blank`, a bare `webView.reload()` is a no-op and
     * `onPageFinished` never fires — we'd time out for no reason. Explicit
     * `loadUrl("about:blank")` always triggers the lifecycle, so the
     * viewport-change callers still get a deterministic page refresh.
     */
    suspend fun reloadAndWait() {
        // [r5-F F-F-01] Best-effort refresh (set_viewport / UA switch). On an
        // inert tab `onPageFinished` can never fire, so installing
        // navigationDeferred here would burn the whole navigation timeout and
        // hand the caller a fake TIMED_OUT. Early-exit instead — and NOT by
        // completing the deferred: "the reload happened" is exactly the claim
        // this path cannot make (the next read, e.g. get_page_info, answers the
        // dead-tab error through the evaluateJavascript guard).
        if (webViewInert()) {
            Log.w(TAG, "reloadAndWait skipped: the WebView is gone")
            return
        }
        // [audit-0916b] Typed like navigate()'s. These two are internal
        // best-effort refreshes (viewport change / fresh-tab init) and
        // deliberately ignore the outcome — a failed reload must not break
        // set_viewport — but they must still complete with a real
        // NavigationResult so the shared callbacks stay type-correct.
        val deferred = CompletableDeferred<NavigationResult>()
        navigationDeferred = deferred
        _isLoading.value = true
        val url = _currentURL.value
        if (url.isEmpty() || url == "about:blank") {
            // Android WebView's `about:blank` reports `window.innerWidth=980`
            // regardless of container size (the no-meta-viewport fallback),
            // so a plain blank reload wouldn't reflect the new viewport. Load
            // an empty page that declares `width=device-width` instead —
            // `window.innerWidth` then tracks the container we just laid out.
            // `loadDataWithBaseURL(null, html, ...)` lands on `about:blank`
            // as the reported URL but with our meta-viewport in effect.
            webView.loadDataWithBaseURL(null, BLANK_PAGE_HTML, "text/html", "utf-8", null)
        } else {
            webView.reload()
        }
        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            // [audit-0909 T7-H1] Complete unconditionally — see navigate()'s
            // timeoutRunnable. If navigationDeferred was replaced inside the
            // window, the old deferred otherwise had no completion path at all.
            if (navigationDeferred === deferred) {
                _isLoading.value = false
                navigationDeferred = null
            }
            deferred.complete(NavigationResult(NavigationOutcome.TIMED_OUT))
        }
        handler.postDelayed(timeoutRunnable, AgentRuntimeLimitsPrefs.browserNavTimeoutSec() * 1000L)
        try { deferred.await() } finally { handler.removeCallbacks(timeoutRunnable) }
        _isLoading.value = false
    }

    /**
     * Load a minimal HTML page with `<meta viewport content="width=device-width">`
     * so `window.innerWidth` tracks the just-laid-out container size. Used by
     * [BrowserTabPool.createTab] when no initial URL is supplied, so a follow-up
     * `get_page_info` on a fresh tab reports the session viewport instead of
     * WebView's hardcoded `about:blank` 980px fallback.
     *
     * Suspends until `onPageFinished` fires so a follow-up JS evaluation sees
     * `document.body` populated. Must be called on the main thread.
     */
    suspend fun loadBlankPage() {
        // [r5-F F-F-01] Same early-exit as reloadAndWait(): this is the
        // fresh-tab init refresh, also best-effort. A renderer-killed tab never
        // reports onPageFinished, so arming navigationDeferred would stall the
        // caller (createTab / tab switch) for the whole navigation timeout.
        if (webViewInert()) {
            Log.w(TAG, "loadBlankPage skipped: the WebView is gone")
            return
        }
        // [audit-0916b] Typed like navigate()'s. These two are internal
        // best-effort refreshes (viewport change / fresh-tab init) and
        // deliberately ignore the outcome — a failed reload must not break
        // set_viewport — but they must still complete with a real
        // NavigationResult so the shared callbacks stay type-correct.
        val deferred = CompletableDeferred<NavigationResult>()
        navigationDeferred = deferred
        _isLoading.value = true
        webView.loadDataWithBaseURL(null, BLANK_PAGE_HTML, "text/html", "utf-8", null)
        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            // [audit-0909 T7-H1] Complete unconditionally — see navigate()'s
            // timeoutRunnable. If navigationDeferred was replaced inside the
            // window, the old deferred otherwise had no completion path at all.
            if (navigationDeferred === deferred) {
                _isLoading.value = false
                navigationDeferred = null
            }
            deferred.complete(NavigationResult(NavigationOutcome.TIMED_OUT))
        }
        handler.postDelayed(timeoutRunnable, AgentRuntimeLimitsPrefs.browserNavTimeoutSec() * 1000L)
        try { deferred.await() } finally { handler.removeCallbacks(timeoutRunnable) }
        _isLoading.value = false
    }

    fun loadURL(urlString: String) {
        // [audit-0916] Same scheme-aware normalization as navigate() — see
        // normalizeBrowserUrl. Keep the two sites in sync: callers pass both
        // user-typed hostnames and already-normalized URLs, and an opaque
        // scheme (`about:blank`) must survive either way.
        val normalized = normalizeBrowserUrl(urlString)
        _isLoading.value = true
        webView.loadUrl(normalized)
    }

    // -- JS Evaluation Helpers --

    private suspend fun evaluateJavascript(js: String): String = withContext(Dispatchers.Main) {
        // [r3-B09 F-02] The callback handed to `WebView.evaluateJavascript` runs
        // in the renderer. With the renderer gone (or the view destroyed) it
        // never runs, and this `deferred.await()` has no timeout — the caller
        // would sit until the tool deadline. Answer with the bridge's own reject
        // shape so `evaluateAndReturn` / `evaluateJSAndParse` surface an error
        // instead of a hang.
        if (webViewInert()) {
            Log.w(TAG, "JS evaluation dropped: the WebView is gone")
            return@withContext "{\"error\": \"This tab's WebView is gone (renderer killed or tab destroyed) — open a new tab\"}"
        }
        val deferred = CompletableDeferred<String>()
        webView.evaluateJavascript(js) { result ->
            // Android WebView returns JSON-encoded strings, so unquote
            val unquoted = if (result != null && result.startsWith("\"") && result.endsWith("\"")) {
                try {
                    JSONObject("{\"v\":$result}").getString("v")
                } catch (_: Exception) {
                    result
                }
            } else {
                result ?: "null"
            }
            deferred.complete(unquoted)
        }
        deferred.await()
    }

    private suspend fun evaluateAndReturn(js: String): BrowserActionResult {
        return try {
            val raw = evaluateJavascript(js)
            val json = try { JSONObject(raw) } catch (_: Exception) { null }
            if (json != null) {
                if (json.has("error")) {
                    BrowserActionResult.error(json.getString("error"))
                } else {
                    BrowserActionResult(text = formatJSONResult(json))
                }
            } else {
                BrowserActionResult(text = raw)
            }
        } catch (e: Exception) {
            BrowserActionResult.error("JavaScript error: ${e.message}")
        }
    }

    private suspend fun evaluateJSAndParse(js: String): BrowserActionResult {
        return try {
            val raw = evaluateJavascript(js)
            val json = try { JSONObject(raw) } catch (_: Exception) { null }
            if (json != null) {
                if (json.has("error")) {
                    BrowserActionResult.error(json.getString("error"))
                } else {
                    BrowserActionResult(
                        text = formatJSONResult(json),
                        truncated = json.optBoolean("truncated", false),
                        fullTextLength = json.optInt("fullLength").takeIf { json.has("fullLength") },
                    )
                }
            } else {
                BrowserActionResult(text = raw)
            }
        } catch (e: Exception) {
            BrowserActionResult.error("JavaScript error: ${e.message}")
        }
    }

    // -- Result Formatting --

    private fun formatJSONResult(json: JSONObject): String = buildString {
        when {
            json.optBoolean("clicked") -> {
                val tag = json.optString("tag", "?")
                appendLine("Clicked <$tag>")
                if (json.has("x") && json.has("y")) appendLine("  Position: (${json.optInt("x")}, ${json.optInt("y")})")
                val text = json.optString("text", "")
                if (text.isNotEmpty()) append("  Text: ${text.take(200)}")
            }
            json.optBoolean("typed") -> {
                val sel = json.optString("selector", "?")
                val len = json.optInt("length", 0)
                append("Typed $len chars into $sel")
            }
            json.optBoolean("scrolled") -> {
                val dir = json.optString("direction", "?")
                val amt = json.optInt("amount", 0)
                appendLine("Scrolled $dir ${amt}px")
                if (json.has("scrollY")) appendLine("  Scroll Y: ${json.optInt("scrollY")}")
                if (json.has("scrollHeight")) appendLine("  Page height: ${json.optInt("scrollHeight")}")
                if (json.has("viewportHeight")) append("  Viewport height: ${json.optInt("viewportHeight")}")
            }
            json.has("scrolledTo") -> {
                val sel = json.optString("scrolledTo")
                val tag = json.optString("tag", "?")
                append("Scrolled to <$tag> ($sel)")
            }
            json.optBoolean("hovered") -> {
                val tag = json.optString("tag", "?")
                appendLine("Hovered <$tag>")
                val text = json.optString("text", "")
                if (text.isNotEmpty()) append("  Text: ${text.take(200)}")
            }
            json.has("text") && json.has("length") -> {
                val title = json.optString("title", "")
                if (title.isNotEmpty()) appendLine("Title: $title")
                val text = json.optString("text", "")
                val len = json.optInt("length", text.length)
                val truncated = json.optBoolean("truncated", false)
                val fullLength = json.optInt("fullLength", len)
                if (truncated) {
                    appendLine("Text ($len of $fullLength chars; truncated):")
                } else {
                    appendLine("Text ($len chars):")
                }
                append(text)
            }
            json.has("count") && json.has("elements") -> {
                val count = json.optInt("count")
                val elements = json.optJSONArray("elements")
                val shown = json.optInt("shown", elements?.length() ?: 0)
                appendLine("Found $count element(s) (showing $shown):")
                if (elements != null) {
                    for (i in 0 until elements.length()) {
                        val el = elements.getJSONObject(i)
                        val idx = el.optInt("index")
                        val tag = el.optString("tag", "?")
                        val text = el.optString("text", "").take(80)
                        val line = buildString {
                            append("  [$idx] <$tag>")
                            val id = el.optString("id", "")
                            if (id.isNotEmpty()) append(" #$id")
                            if (text.isNotEmpty()) append(" \"$text\"")
                            val href = el.optString("href", "")
                            if (href.isNotEmpty()) append(" -> $href")
                        }
                        appendLine(line)
                    }
                }
            }
            else -> {
                // Fallback: format each key-value pair
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    appendLine("  $key: ${json.opt(key)}")
                }
            }
        }
    }.trimEnd()

    private fun formatBackboneResult(json: JSONObject): String = buildString {
        val nodeCount = json.optInt("nodeCount")
        val depth = json.optInt("depth")
        val merged = json.optInt("merged")
        appendLine("Page backbone: $nodeCount nodes, depth $depth, $merged merged")

        val backbone = json.optJSONArray("backbone")
        if (backbone != null) {
            for (i in 0 until backbone.length()) {
                formatBackboneNode(backbone.getJSONObject(i), 0, this)
            }
        }
    }.trimEnd()

    private fun formatBackboneNode(node: JSONObject, indent: Int, sb: StringBuilder) {
        val pad = "  ".repeat(indent)
        val tag = node.optString("tag", "?")
        val sel = node.optString("sel", "")
        val rect = node.optString("rect", "")

        val header = buildString {
            append("$pad<$tag>")
            val id = node.optString("id", "")
            if (id.isNotEmpty()) append(" #$id")
            val cls = node.optString("cls", "")
            if (cls.isNotEmpty()) append(" .${cls.replace(" ", ".")}")
            val role = node.optString("role", "")
            if (role.isNotEmpty()) append(" [$role]")
            append(" | $sel | $rect")
        }
        sb.appendLine(header)

        val text = node.optString("text", "")
        if (text.isNotEmpty()) sb.appendLine("$pad  \"$text\"")
        val href = node.optString("href", "")
        if (href.isNotEmpty()) sb.appendLine("$pad  -> $href")
        val img = node.optString("img", "")
        if (img.isNotEmpty()) sb.appendLine("$pad  img: $img")
        val input = node.optString("input", "")
        if (input.isNotEmpty()) sb.appendLine("$pad  input: $input")

        val children = node.optJSONArray("children")
        if (children != null) {
            for (i in 0 until children.length()) {
                formatBackboneNode(children.getJSONObject(i), indent + 1, sb)
            }
        }
    }

    // -- Get Cookies --

    /**
     * Return cookies for the current page's origin, filtered by keyword.
     * Android exposes cookies as a single `Cookie` header string via
     * [CookieManager]; we split on `;` and match each `name=value` pair.
     *
     * `fuzzy=false` (default): exact name match (case-insensitive).
     * `fuzzy=true`: substring match within the cookie name.
     */
    private fun getCookies(keywords: List<String>?, fuzzy: Boolean): BrowserActionResult {
        val url = _currentURL.value.takeIf { it.isNotEmpty() }
            ?: return BrowserActionResult.error("get_cookies: no page is loaded (navigate first)")
        val cookieMgr = runCatching { CookieManager.getInstance() }.getOrNull()
            ?: return BrowserActionResult.error("get_cookies: CookieManager unavailable")
        val raw = cookieMgr.getCookie(url).orEmpty()
        if (raw.isEmpty()) {
            return BrowserActionResult(text = "No cookies set for $url")
        }
        val pairs = raw.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull {
                val eq = it.indexOf('=')
                if (eq <= 0) null else it.substring(0, eq).trim() to it.substring(eq + 1).trim()
            }
        val filtered = if (keywords.isNullOrEmpty()) pairs else pairs.filter { (name, _) ->
            keywords.any { kw ->
                if (fuzzy) name.contains(kw, ignoreCase = true)
                else name.equals(kw, ignoreCase = true)
            }
        }
        val text = buildString {
            appendLine("Cookies for $url (${filtered.size} of ${pairs.size}):")
            for ((name, value) in filtered) {
                val preview = if (value.length > 80) value.take(77) + "…" else value
                appendLine("  $name = $preview")
            }
        }.trimEnd()
        return BrowserActionResult(text = text)
    }

    // -- Set Cookies --

    /**
     * Write cookies into the WebView cookie store via [CookieManager.setCookie],
     * which accepts a Set-Cookie-style string and (unlike `document.cookie`) can
     * set HttpOnly cookies. Symmetric with [getCookies]. Each entry needs
     * `name` + `value`; `domain` defaults to the current page host, `path` to
     * "/". Mirrors iOS `setCookies`.
     */
    private fun setCookies(cookies: List<Map<String, Any?>>?): BrowserActionResult {
        val url = _currentURL.value.takeIf { it.isNotEmpty() }
            ?: return BrowserActionResult.error("set_cookies: no page is loaded (navigate first)")
        // Distinguish "field omitted/unparseable" from "field present but empty".
        // The schema types `cookies` as a string, so a model may send a JSON
        // STRING that failed to re-parse, or the CLI's shell-escaping mangled the
        // array — guide the caller instead of a confusing empty result.
        if (cookies == null) {
            return BrowserActionResult.error(
                "set_cookies: 'cookies' must be a JSON array of cookie objects " +
                    "(e.g. [{\"name\":\"foo\",\"value\":\"bar\"}]). It was missing or could not be " +
                    "parsed — if you passed it as a string, ensure it is valid JSON; from the CLI " +
                    "prefer --cookies-file <path> to avoid shell escaping.",
            )
        }
        if (cookies.isEmpty()) {
            return BrowserActionResult.error(
                "set_cookies: 'cookies' array is empty — provide at least one {name, value} object.",
            )
        }
        val cookieMgr = runCatching { CookieManager.getInstance() }.getOrNull()
            ?: return BrowserActionResult.error("set_cookies: CookieManager unavailable")

        // Default domain = current page host.
        val defaultDomain = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()

        // expires (Unix seconds) → RFC-1123 "Expires=" date in GMT.
        val httpDateFmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }

        val setNames = mutableListOf<String>()
        val domainsTouched = linkedSetOf<String>()
        val failures = mutableListOf<String>()

        for (raw in cookies) {
            // Accept the field-name variants common cookie exports use (browser
            // extensions EditThisCookie / Cookie-Editor, Playwright / Puppeteer
            // storage), so a model can paste cookies verbatim. [set-cookies-formats]
            val name = cookieString(raw, "name")?.takeIf { it.isNotEmpty() }
            val value = cookieString(raw, "value")
            if (name == null || value == null) {
                failures.add("(missing name/value)")
                continue
            }
            val domain = cookieString(raw, "domain")?.takeIf { it.isNotEmpty() } ?: defaultDomain
            val path = cookieString(raw, "path")?.takeIf { it.isNotEmpty() } ?: "/"

            val sb = StringBuilder()
            sb.append(name).append('=').append(value)
            if (domain.isNotEmpty()) sb.append("; Domain=").append(domain)
            sb.append("; Path=").append(path)
            if (cookieBool(raw, "secure") == true) sb.append("; Secure")
            // camelCase httpOnly (extensions / Playwright) + snake_case http_only.
            if (cookieBool(raw, "http_only", "httpOnly") == true) sb.append("; HttpOnly")
            // Expiry in Unix seconds. Aliases: expires (Puppeteer) + expirationDate
            // (EditThisCookie / Cookie-Editor, often fractional). <= 0 (Puppeteer's
            // -1, or 0) → session cookie (no Expires attribute).
            cookieNumber(raw, "expires", "expirationDate")?.takeIf { it > 0 }?.let { expires ->
                val date = java.util.Date(expires.toLong() * 1000L)
                sb.append("; Expires=").append(httpDateFmt.format(date))
            }
            // sameSite accepted (Lax/Strict/None, any case) so exports including
            // it aren't rejected, but NOT applied yet — CookieManager.setCookie
            // honors a SameSite attribute, but wiring it needs validation against
            // the cross-site captcha flows. TODO [set-cookies-samesite].
            //
            // [audit-B-3] This is a deliberate deferral, not an oversight: the
            // field is parsed and type-checked above strictly to avoid rejecting
            // otherwise-valid cookie exports, but omitting the SameSite attribute
            // keeps the cookie at the WebView default (Lax). If a cross-site
            // captcha flow ever needs a precise SameSite=None + Secure pair, add
            // "; SameSite=…" here — and keep the existing parse guard.
            @Suppress("UNUSED_VARIABLE")
            val sameSite = cookieString(raw, "sameSite", "same_site")

            cookieMgr.setCookie(url, sb.toString())
            setNames.add(name)
            domainsTouched.add(domain)
        }
        cookieMgr.flush()

        if (setNames.isEmpty()) {
            return BrowserActionResult.error(
                "set_cookies: no cookies were set (every entry was invalid: ${failures.joinToString(", ")})",
            )
        }
        val domainLabel = if (domainsTouched.size == 1) domainsTouched.first() else domainsTouched.joinToString(", ")
        var text = "Set ${setNames.size} cookie(s) for $domainLabel: ${setNames.joinToString(", ")}"
        if (failures.isNotEmpty()) {
            text += "\nSkipped ${failures.size} invalid entry(ies): ${failures.joinToString(", ")}"
        }
        return BrowserActionResult(text = text)
    }

    // -- Cookie field readers (format-tolerant) --

    // -- Wait for DOM Stable --

    /**
     * Poll the DOM for stability: repeatedly measures `document.body.innerHTML.length`
     * at ~200ms intervals and returns when two successive readings match, or the
     * timeout elapses. Matches iOS `wait_for_dom_stable`.
     */
    private suspend fun waitForDomStable(timeoutMs: Int?): BrowserActionResult {
        val budget = (timeoutMs ?: AgentRuntimeLimitsPrefs.browserDomStableSec() * 1000).coerceIn(
            MIN_DOM_STABLE_TIMEOUT_MS, MAX_DOM_STABLE_TIMEOUT_MS,
        )
        val pollInterval = 200L
        var lastSize = -1L
        var stable = false
        val deadline = System.currentTimeMillis() + budget
        // [T-android-domstable-min-budget] C5 fast path: a document that has
        // already finished loading (readyState === 'complete') is almost
        // always stable — confirm with two samples 50ms apart and return
        // without paying the 200ms poll interval. Keeps trivial static
        // pages (e.g. minis:// docs) fast at any budget.
        val readyState = evaluateJavascript(
            "(function(){try{return document.readyState;}catch(e){return '';}})()"
        ).trim('"')
        if (readyState == "complete") {
            val first = evaluateJavascript(
                "(function(){try{return (document.body&&document.body.innerHTML.length)||0;}catch(e){return -1;}})()"
            ).toLongOrNull() ?: -1L
            delay(50)
            val second = evaluateJavascript(
                "(function(){try{return (document.body&&document.body.innerHTML.length)||0;}catch(e){return -1;}})()"
            ).toLongOrNull() ?: -1L
            // [T-android-review-p1-fixes] F4: require a NON-EMPTY body.
            // SPAs report readyState=complete with an empty/skeleton body
            // before the JS app renders — `first >= 0` declared those
            // "stable" instantly and the agent read a blank page. Empty
            // bodies fall through to the polling loop unchanged (which can
            // legitimately conclude an actually-empty page is stable, but
            // only after giving scripts the full budget to render).
            if (first == second && first > 0) {
                return BrowserActionResult(
                    text = "DOM stable immediately (readyState=complete, body length=$first)",
                )
            }
            // Fast path inconclusive (DOM still mutating post-load, or body
            // still empty) — fall through to the normal polling loop with
            // lastSize untouched so its stability criterion stays exactly
            // as before.
        }
        while (System.currentTimeMillis() < deadline) {
            val raw = evaluateJavascript(
                "(function(){try{return (document.body&&document.body.innerHTML.length)||0;}catch(e){return -1;}})()"
            )
            val size = raw.toLongOrNull() ?: -1L
            if (size == lastSize && size >= 0) { stable = true; break }
            lastSize = size
            delay(pollInterval)
        }
        val elapsed = budget - (deadline - System.currentTimeMillis())
        return if (stable) {
            BrowserActionResult(text = "DOM stable after ${elapsed}ms (body length=$lastSize)")
        } else {
            BrowserActionResult(
                text = "DOM did not stabilize within ${budget}ms (last body length=$lastSize)",
                success = false,
            )
        }
    }

    // -- Scroll and Collect --

    /**
     * Scroll the page [scrollCount] times, collecting text of every element
     * matching [itemSelector]. Optional [keywords] filter the collected text
     * (case-insensitive substring match). Mirrors iOS `scroll_and_collect`.
     */
    private suspend fun scrollAndCollect(
        scrollCount: Int?,
        itemSelector: String?,
        keywords: List<String>?,
    ): BrowserActionResult {
        val iterations = (scrollCount ?: 5).coerceIn(1, 50)
        val selector = itemSelector?.takeIf { it.isNotBlank() }
            ?: return BrowserActionResult.error("scroll_and_collect requires --item-selector")

        val collected = LinkedHashSet<String>()
        for (i in 0 until iterations) {
            // [audit2-A2] Was a hand-rolled `replace("\\","\\\\").replace("'","\\'")`,
            // which left raw line terminators in the literal below — a selector
            // containing a newline (a legal CSS attribute value, e.g.
            // div[title="a\nb"]) produced an unterminated JS string, so the
            // whole snippet died as a SyntaxError and scroll_and_collect
            // silently returned nothing. Use the one escaper the rest of the
            // browser JS is built with (`\n` / `\r` / `\t` / quotes).
            val escaped = BrowserUseJS.jsQuote(selector)
            val raw = evaluateJavascript(
                """(function(){
                    try {
                        var nodes = document.querySelectorAll('$escaped');
                        var out = [];
                        for (var i=0;i<nodes.length;i++) {
                            var t = (nodes[i].innerText || nodes[i].textContent || '').trim();
                            if (t) out.push(t);
                        }
                        return JSON.stringify(out);
                    } catch(e) { return '[]'; }
                })()"""
            )
            try {
                val arr = org.json.JSONArray(raw)
                for (j in 0 until arr.length()) {
                    val s = arr.optString(j)
                    if (s.isNotBlank()) collected.add(s)
                }
            } catch (_: Exception) { /* ignore malformed batches */ }

            // Scroll one viewport down and let the page settle before re-querying.
            evaluateJavascript("window.scrollBy(0, window.innerHeight);")
            delay(400)
        }

        val filtered = if (keywords.isNullOrEmpty()) collected.toList()
            else collected.filter { text -> keywords.any { k -> text.contains(k, ignoreCase = true) } }

        val text = buildString {
            appendLine("scroll_and_collect: $iterations scrolls, selector='$selector'")
            appendLine("  matched: ${filtered.size} / ${collected.size} total")
            for ((i, item) in filtered.withIndex()) {
                val preview = if (item.length > 160) item.take(157) + "…" else item
                appendLine("  [${i + 1}] $preview")
            }
        }.trimEnd()
        return BrowserActionResult(text = text)
    }

    // -- Teardown --

    /**
     * Permanently release this manager's WebView. Must be called on the main
     * thread. Detaches the WebView from the view hierarchy first, then calls
     * [WebView.destroy] so its renderer process and native heap are actually
     * freed — merely dropping the Tab from the pool's list leaves the WebView
     * reachable and leaks 50-100 MB per tab.
     *
     * Re-entrancy-safe: destroying an already-destroyed WebView throws
     * "Attempt to perform WebView method on a destroyed WebView", so callers
     * that race (trim callback vs. user close) are protected by the flag.
     */
    @Volatile
    private var destroyed = false

    /**
     * [r3-B09 F-02] The renderer behind this WebView was killed by the system
     * (`onRendererGone`), so the instance is inert: it can never fire
     * `onPageFinished`, and `evaluateJavascript` callbacks never arrive.
     *
     * Deliberately NOT [destroyed] — the pool still holds this tab, the WebView
     * object is alive (it is only the renderer that died) and it must be
     * destroyed for real when the tab goes, so a `destroyed = true` here would
     * make `destroy()` return early and leak the view. This is the "don't wait
     * for a callback that cannot come" half of the GH#341 latch.
     */
    @Volatile
    private var rendererDead = false

    /** [r3-B09 F-02] No live renderer: destroyed, or renderer killed. */
    private fun webViewInert(): Boolean = destroyed || rendererDead

    /**
     * [r3-B09 F-02] The result for an action aimed at a tab that has no live
     * WebView.
     *
     * Two causes, two different fixes on the caller's side, so they get two
     * different messages: a torn-down tab means "pick another tab", a killed
     * renderer means "this tab is done, open a new one". Both used to surface as
     * a network-flavoured timeout.
     *
     * [r5-F F-F-01] The wording now lives in [deadTabGoneMessage] so the
     * async-JS reject shape (`deadTabAsyncJsAnswer`, what `executeJS`/`fetch`
     * answer with) cannot drift from this one — one dead tab, one story.
     */
    private fun webViewGoneResult(): BrowserActionResult =
        BrowserActionResult.error(deadTabGoneMessage(destroyed))

    // ── [feat/browser-console-network-upload] diagnostics + file upload ──────

    /**
     * get_console_messages — the page's JS console output captured in this
     * tab (per-manager buffer, capped at [CONSOLE_BUFFER_CAP]). Use after a
     * suspicious page state (blank render, failed captcha, broken upload) to
     * see JS errors instead of guessing. --clear empties the buffer.
     */
    private suspend fun getConsoleMessages(clear: Boolean): BrowserActionResult {
        val entries = synchronized(consoleLog) {
            val copy = consoleLog.toList()
            if (clear) consoleLog.clear()
            copy
        }
        if (entries.isEmpty()) {
            return BrowserActionResult(
                text = "No console messages captured" + if (clear) " (buffer cleared)" else "",
            )
        }
        val sb = StringBuilder("Console messages (${entries.size}):\n")
        for (e in entries) {
            sb.append('[').append(e.level).append("] ").append(e.message)
            if (e.source.isNotBlank()) {
                sb.append("  (").append(e.source.substringAfterLast('/'))
                    .append(':').append(e.line).append(')')
            }
            sb.append('\n')
        }
        if (clear) sb.append("\n(buffer cleared after read)")
        var text = sb.toString().trimEnd()
        if (text.length > DIAGNOSTICS_TEXT_CAP) {
            text = text.take(DIAGNOSTICS_TEXT_CAP) +
                "\n…(truncated: ${text.length} chars > $DIAGNOSTICS_TEXT_CAP — re-run with clear=true to start fresh)"
        }
        return BrowserActionResult(text = text)
    }

    /**
     * get_network_requests — requests the page made, captured at
     * shouldInterceptRequest. Native interception only sees the request, so
     * response status + duration are merged from the page's
     * PerformanceResourceTiming entries (Chromium 109+; older WebView shows
     * "?"). --clear empties both the native buffer and the page's
     * resource-timing entries.
     */
    private suspend fun getNetworkRequests(clear: Boolean): BrowserActionResult {
        // Pull timing data (status/duration by URL) from the page's
        // Performance API. responseStatus needs Chromium 109+ — anything
        // older returns null status and we print "?".
        val timingByUrl = mutableMapOf<String, org.json.JSONObject>()
        val timingJs = """
            (function() {
                try {
                    var entries = performance.getEntriesByType('resource');
                    var out = [];
                    for (var i = 0; i < entries.length; i++) {
                        var e = entries[i];
                        out.push({name: e.name, status: (typeof e.responseStatus === 'number') ? e.responseStatus : null, duration: Math.round(e.duration)});
                    }
                    if (${if (clear) "true" else "false"}) { try { performance.clearResourceTimings(); } catch (err) {} }
                    return JSON.stringify(out);
                } catch (err) { return '[]'; }
            })();
        """.trimIndent()
        runCatching {
            val raw = evaluateJavascript(timingJs)
            val arr = org.json.JSONArray(raw.takeIf { it.startsWith("[") } ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                timingByUrl[o.optString("name")] = o
            }
        } // timing merge is best-effort — a page without JS context still gets the request list

        val entries = synchronized(networkLog) {
            val copy = networkLog.toList()
            if (clear) networkLog.clear()
            copy
        }
        if (entries.isEmpty()) {
            return BrowserActionResult(
                text = "No network requests captured" + if (clear) " (buffer cleared)" else "",
            )
        }
        val sb = StringBuilder("Network requests (${entries.size}):\n")
        for (e in entries) {
            val t = timingByUrl[e.url]
            val status = t?.opt("status")
            val duration = t?.opt("duration")
            sb.append(if (e.isMainFrame) "MAIN " else "sub  ")
                .append(e.method).append(' ').append(e.url)
            if (t != null) {
                sb.append("  -> ").append(status?.let { it.toString() } ?: "?")
                if (duration is Number) sb.append(" ").append(duration).append("ms")
            }
            sb.append('\n')
        }
        if (clear) sb.append("\n(buffer cleared after read)")
        var text = sb.toString().trimEnd()
        if (text.length > DIAGNOSTICS_TEXT_CAP) {
            text = text.take(DIAGNOSTICS_TEXT_CAP) +
                "\n…(truncated: ${text.length} chars > $DIAGNOSTICS_TEXT_CAP — re-run with clear=true to start fresh)"
        }
        return BrowserActionResult(text = text)
    }

    /**
     * file_upload — answer a page-triggered file chooser (an <input
     * type="file"> the agent opened via click / execute_js) with files from
     * the Linux sandbox. Paths are resolved through [PRootKernel] to host
     * files (session-scoped via [BrowserActionInput.sessionId], T178
     * pattern) and handed to the WebView as FileProvider content URIs (only
     * declared provider roots are shareable: minis-sessions/, minis-global/,
     * alpine-rootfs/ — i.e. everything under /var/minis/ and the sandbox
     * tree). Validation happens BEFORE the chooser callback is consumed, so
     * a bad path leaves the chooser open for a corrected retry.
     */
    private suspend fun fileUpload(input: BrowserActionInput): BrowserActionResult {
        val paths = input.paths
        if (paths.isNullOrEmpty()) {
            return BrowserActionResult.error(
                "file_upload requires 'paths' — Linux paths of the files to upload, " +
                    "e.g. [\"/var/minis/attachments/photo.png\"]",
            )
        }
        val callback = pendingFileChooser
            ?: return BrowserActionResult.error(
                "No file chooser is open on this tab. Trigger one first: click the page's " +
                    "visible <input type=\"file\"> (or its label) with the click action — " +
                    "a JS click via execute_js cannot open file choosers — then immediately " +
                    "call file_upload.",
            )
        // Resolve Linux paths -> host files. Fail WITHOUT consuming the
        // callback so a corrected retry can still answer the same chooser.
        // [fix/browser-trio-audit] Session-scoped resolution first (T178
        // pattern, same as file_read/file_edit/read_image): the global
        // resolveHostPath fallback searches EVERY minis-sessions/<id>/ tree
        // and returns the first same-named hit — i.e. possibly another chat
        // session's file. [audit3 F-08] resolvePathForSession is the single
        // entry point: with a session in scope the global resolver is never
        // consulted (not even when the session resolver rejects the path);
        // non-session paths (/tmp/...) still fall through to the global
        // resolver inside resolveSessionHostPath itself.
        val context = webView.context
        val hostFiles = mutableListOf<File>()
        for (linuxPath in paths) {
            val host = runCatching {
                PRootKernel.resolvePathForSession(input.sessionId, linuxPath, context)
            }.getOrNull()
            if (host == null || !host.exists() || !host.isFile) {
                return BrowserActionResult.error(
                    "file_upload: cannot resolve '$linuxPath' to an existing file in the sandbox",
                )
            }
            hostFiles += host
        }
        // Host file -> shareable content URI. Files outside the declared
        // provider roots (e.g. arbitrary /data paths) throw here — report
        // with a copy-to-workspace remedy instead of failing the chooser.
        val authority = "${context.packageName}.fileprovider"
        val uris = arrayOfNulls<Uri>(hostFiles.size)
        for (i in hostFiles.indices) {
            uris[i] = try {
                FileProvider.getUriForFile(context, authority, hostFiles[i])
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        val failedAt = uris.indexOfFirst { it == null }
        if (failedAt >= 0) {
            return BrowserActionResult.error(
                "file_upload: '${paths[failedAt]}' resolves outside the shareable provider " +
                    "roots (sessions/, global/, rootfs/) — copy it under /var/minis/workspace " +
                    "or /var/minis/attachments first",
            )
        }
        // Consume the chooser exactly once, on the main thread as WebView
        // expects — and only if it is STILL the pending one. Navigation
        // (onPageStarted) or a newer onShowFileChooser may have cancelled /
        // replaced the callback while we were resolving paths (TOCTOU):
        // answering a callback twice is undefined WebView behavior, and
        // answering a replaced one hands the files to a dead chooser. The
        // identity check runs on Main, where every other pendingFileChooser
        // write happens, so check-then-consume is atomic.
        // [fix/browser-trio-audit]
        var accept: String? = null
        val delivered: Boolean? = withContext(Dispatchers.Main) {
            if (pendingFileChooser !== callback) {
                null // stale — cancelled or replaced while paths were resolving
            } else {
                pendingFileChooser = null
                accept = pendingFileAccept
                pendingFileAccept = null
                runCatching { callback.onReceiveValue(uris.filterNotNull().toTypedArray()) }.isSuccess
            }
        }
        if (delivered == null) {
            return BrowserActionResult.error(
                "file_upload: the file chooser was cancelled or replaced while the paths were " +
                    "being resolved (page navigated / opened a new chooser) — re-trigger it " +
                    "(click the <input type=\"file\"> again) and retry",
            )
        }
        if (!delivered) {
            return BrowserActionResult.error("file_upload failed delivering files to the page")
        }
        return BrowserActionResult(
            text = "Uploaded ${hostFiles.size} file(s): ${paths.joinToString(", ")}" +
                (accept?.let { "\n(chooser accepted: $it)" } ?: "") +
                "\nThe page received the files and will run its upload/change handler.",
        )
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        try {
            // [feat/browser-console-network-upload] Answer a still-pending
            // file chooser before tearing the WebView down — an unanswered
            // callback holds renderer-side state.
            runCatching { pendingFileChooser?.onReceiveValue(null) }
            pendingFileChooser = null
            pendingFileAccept = null
            // [deep-audit-1019 ISSUE-08] Wake the other two in-flight waiters
            // BEFORE the client is swapped for a no-op and the WebView is
            // destroyed: after that neither onPageFinished nor a
            // `__minis__.resolve`/`.reject` callback can ever arrive, so
            // navigate()/reloadAndWait() and executeJS()/fetch() would each
            // burn their full 30s/60s timeout and then blame the network / the
            // page for what was really a tab that no longer exists. Same
            // complete-then-clear pairing every other completion site in this
            // file uses. The async-JS error keeps the bridge's own reject shape
            // (`{"error":…}`) because executeJS treats any non-JSON payload as
            // a successful result.
            navigationDeferred?.complete(
                NavigationResult(
                    NavigationOutcome.FAILED,
                    errorDescription = "WebView was destroyed before the navigation completed",
                )
            )
            navigationDeferred = null
            asyncJsSlot.takeCurrent()?.complete(
                "{\"error\":\"WebView was destroyed before the async JS request completed\"}"
            )
            // Remove from any parent before destroying — WebView.destroy() on
            // an attached view is undefined behavior on some OEMs and can
            // crash the app (ConnectionTerminated / missing renderer).
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.stopLoading()
            // [deep-audit-1045 B-02] Drop everything queued on this view before
            // it dies: `onPageFinished` posts a synthetic `resize` via
            // `view.postDelayed(…, 80)` and a late attach would flush that
            // runnable straight into a destroyed renderer (KaTeXView.kt and
            // FilePreviewScreen.kt carry the same three-call teardown: stop →
            // drop callbacks → destroy).
            //
            // Order matters twice over: this MUST sit after the removeView()
            // above, because `View.removeCallbacks(null)` on an ATTACHED view
            // also drains the shared window handler's null-token messages,
            // while on a detached one it only drops this view's run queue.
            webView.removeCallbacks(null)
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.settings.javaScriptEnabled = false
            // [audit-RC13] Drop the committed main document so a recycled or
            // destroyed WebView can never carry stale main-doc state into a
            // new page's subresource gate. Fail-closed: null blocks until the
            // next onPageStarted commits again.
            lastMainDocUrl = null
            lastMainFrameHttpError = null // [r3-B09 F-14] no status outlives the tab
            // Assign a no-op implementation instead of null — the Kotlin
            // Android SDK bindings expose webViewClient/webChromeClient as
            // non-null types (API 26+), so "= null" won't compile.
            webView.webChromeClient = object : android.webkit.WebChromeClient() {}
            webView.webViewClient = object : SafeWebViewClient() {}
            webView.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy() error: ${e.message}")
        }
        Log.i(TAG, "BrowserUseManager WebView destroyed")
    }

}

/**
 * [deep-audit-1016 9a] Pure trust decisions for the `minis://` gate: which
 * main documents may reference minis:// resources, and which may navigate the
 * tab to a minis:// document.
 *
 * Lifted out of [BrowserUseManager] (two private helpers plus the gate body,
 * moved verbatim) so the whole policy is one JVM-testable unit — it had no
 * direct test before, because an Android-owned instance method cannot be
 * exercised without a WebView.
 */
internal object MinisMainDocGate {

    /** Strip an arbitrary `Origin` header value down to `scheme://host[:port]`.
     *  Scheme + authority are lowercased (RFC 3986 case-insensitivity) so
     *  `MINIS://…` / `HTTPS://APPASSETS…` spellings normalize before any
     *  prefix comparison; the `null` opaque-origin sentinel maps to "". */
    fun originOf(headerValue: String): String {
        // `null` (sentinel for sandboxed/opaque origins) and anything that
        // doesn't look like scheme://… carries no trustable site identity.
        val v = headerValue.trim()
        val schemeEnd = v.indexOf("://")
        if (v.equals("null", ignoreCase = true) || schemeEnd <= 0) return ""
        val rest = v.substring(schemeEnd + 3)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        return "${v.substring(0, schemeEnd).lowercase()}://${authority.lowercase()}"
    }

    /** Exact-host app-assets origin check (tightened from the old loose
     *  `startsWith("https://appassets")` prefix, which also matched
     *  `https://appassets.evil.com`). Allows the bare origin plus path /
     *  port suffixes only. */
    fun isAppAssetsOrigin(origin: String): Boolean {
        val base = "https://appassets.androidplatform.net"
        return origin == base || origin.startsWith("$base/") || origin.startsWith("$base:")
    }

    /**
     * Whether a page whose COMMITTED main document is [mainDocUrl] is trusted
     * to reach sandbox-local `minis://` content at all. Null / blank means "no
     * document committed yet" and is NOT trusted here — callers that need the
     * pre-first-commit race arm use [allowsSubresource] instead.
     */
    fun isTrustedMainDoc(mainDocUrl: String?): Boolean {
        // Lowercase: scheme is case-insensitive per RFC 3986 and this gate's
        // prefix comparisons are exact — normalize before matching. (Chromium
        // emits normalized lowercase URLs anyway; this defends the gate
        // against unnormalized input shapes.)
        val doc = mainDocUrl?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        // The blank-tab bootstrap page (loadDataWithBaseURL(null, …)) lands on
        // about:blank; blocking it would break fresh-tab subresource loads.
        // (Known second-order nuance: an about:blank window.open'ed by a
        // website inherits the opener's origin — logged as a follow-up
        // hardening candidate, out of scope here.)
        return doc == "about:blank" ||
            doc.startsWith("minis://") ||
            isAppAssetsOrigin(originOf(doc))
    }

    /**
     * [audit-RC13] Same-origin policy gate for minis:// SUBRESOURCE loads.
     * Callers pass the current tab's main-document URL and the optional Origin
     * request header (value looked up case-insensitively by the caller —
     * Chromium may send "Origin" or "origin").
     *
     * When a main document IS committed, it is the sole authority: a
     * page-supplied Origin header can never rescue a disallowed document
     * (the header is attacker-controllable — `Origin: minis://…` sent by an
     * https:// page must not re-open the door the main-document gate just
     * closed). Only minis:// / appassets / about:blank main documents may
     * reference minis:// subresources — that is the built-in preview path
     * (minis:// page pulling minis:// attachment images). Any website main
     * document is rejected with or without an Origin header: no-Origin
     * subresources (<img src>, <iframe src>, <script src>) were previously
     * waved through, which let any web page read sandbox-local minis://
     * files (local disclosure / SSRF vector). Fail-closed.
     *
     * When NO main document is committed yet (subresource racing the very
     * first navigation), fall back to an explicit Origin header naming an
     * allowed app origin; the opaque-origin `null` sentinel and website
     * origins reject here too.
     */
    fun allowsSubresource(mainDocUrl: String?, originHeader: String?): Boolean {
        val doc = mainDocUrl?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (doc != null) return isTrustedMainDoc(doc)
        // No committed main document yet — header-based fallback only.
        // (The header is forgeable; this only covers the pre-first-commit
        // race window, so it keeps the original semantics: any minis://
        // origin passes — harmless, since resolution is subdir-pinned —
        // while the appassets origin is checked against its exact host.)
        val headerOrigin = originOf(originHeader ?: "")
        return headerOrigin.startsWith("minis://") || isAppAssetsOrigin(headerOrigin)
    }

    /**
     * [deep-audit-1016 9a] Whether a TOP-LEVEL navigation to [targetUrl] may
     * proceed out of a page whose committed main document is [mainDocUrl].
     *
     * Non-minis:// targets are always allowed — this gate exists only for the
     * arm `shouldInterceptRequest` cannot cover: a page navigating the tab to a
     * `minis://` document (`location.href = "minis://workspace/…"`), which
     * displays a sandbox file in the tab and — if that file carries script —
     * hands the page a trusted main document to pull other minis:// files
     * with. [mainDocUrl] null/blank means the tab has no document yet, so the
     * navigation is allowed: `loadUrl` never reaches the caller's hook, which
     * is how the app's own (trusted) navigations arrive.
     */
    fun allowsTopLevelNavigation(mainDocUrl: String?, targetUrl: String?): Boolean {
        val target = targetUrl?.trim()?.lowercase() ?: return true
        if (!target.startsWith("minis://")) return true
        if (mainDocUrl.isNullOrBlank()) return true
        return isTrustedMainDoc(mainDocUrl)
    }
}

/**
 * [deep-audit-1019 ISSUE-08] Decode the payload half of a `data:` URL.
 *
 * RFC 2397 payloads are percent-encoded (RFC 3986), where a literal `+` is
 * just a `+` — never a space. [java.net.URLDecoder] implements FORM decoding
 * (`application/x-www-form-urlencoded`, '+' → ' '), which silently corrupted
 * every non-base64 download that contained one: `data:text/plain,1+1%3D2`
 * landed on disk as `1 1=2` (likewise `1e+5` in CSV, `+` in code/text). The
 * pre-escape of a literal '+' as "%2B" is the same fix WebDavClient's
 * `decodeHrefSegment` and OAuthCallbackServer's query decoder already carry;
 * `%20` still decodes to a space, and an already-encoded `%2B` still decodes
 * to a literal '+'.
 *
 * Lifted out of `deliverBlobDownload` (decision moved verbatim, call site
 * semantics unchanged) for the same reason [MinisMainDocGate] exists: an
 * Android-owned instance method cannot be exercised without a WebView, and
 * silent data corruption is exactly what a pinned unit test should hold
 * still. `base64Decoder` is a parameter so the *routing* decision is testable
 * on the JVM too; [decodeDataUrlBase64] stays the production default.
 */
internal fun decodeDataUrlPayload(
    header: String,
    payload: String,
    base64Decoder: (String) -> ByteArray = ::decodeDataUrlBase64,
): ByteArray =
    if (header.endsWith(";base64")) {
        base64Decoder(payload)
    } else {
        java.net.URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8")
            .toByteArray(Charsets.UTF_8)
    }

/** [deep-audit-1019 ISSUE-08] Production Base64 half of [decodeDataUrlPayload]. */
private fun decodeDataUrlBase64(payload: String): ByteArray =
    Base64.decode(payload, Base64.DEFAULT)

/**
 * [r3-B09 F-11] Pure policy for the agent-facing `fetch`: which statuses are a
 * failure even though the server sent a body.
 *
 * Lifted to the top level so both directions can be pinned with literals —
 * `fetch` itself is a manager method and cannot be exercised without a
 * WebView. `status = 0` means the JS never reported one (bridge error / older
 * shape): an unknown status is not a failure, it is reported as-is in the
 * result text.
 */
internal fun fetchHttpFailure(status: Int, url: String): String? = when {
    status in 200..299 -> null
    status == 0 -> null
    else -> "Fetch failed: HTTP $status for $url — the response body is the " +
        "server's error page, not the resource you asked for"
}

/**
 * [deep-audit-1045 B-02] Where a one-shot JS injection must land.
 *
 * Lifted to the top level next to [MinisMainDocGate] / [decodeDataUrlPayload]
 * for the same reason they were: an Android-owned instance member cannot be
 * exercised without a WebView, and "the download goes into a queue nothing
 * ever drains" is exactly the kind of silent loss a pinned unit test should
 * hold still.
 *
 * The axis is deliberately **not** "is the view attached". A headless pool tab
 * is never attached and must still run JS — `evaluateJavascript` needs the main
 * *thread*, not a window (every other entry in this file drives detached tabs
 * that way). The axis is *which queue receives the block*:
 *
 *  * `View.post` on a detached view parks the runnable into
 *    `HandlerActionQueue` and replays it only when attachInfo arrives — never,
 *    for a tab that never attaches. The pre-fix blob: download path used it,
 *    so the injected FileReader never ran: no file, no error, and the armed
 *    one-shot bridge token left dangling.
 *  * a `Looper.getMainLooper()` hop always drains, and is what the sibling
 *    entries ([setUserAgent]'s thread hop, `navigate()`'s timeout runnable) use.
 */
internal enum class JsDispatch {
    /** Already on the main thread: run the block directly. */
    INLINE,

    /** Posted to the main Looper — always runs (unlike a detached View queue). */
    HOP_TO_MAIN,

    /** The WebView is destroyed: nothing may touch it, on any thread. */
    REJECT_DESTROYED,
}

/**
 * [deep-audit-1045 B-02] Three-state decision. `destroyed` outranks the thread:
 * a destroyed WebView must not be touched even from the main thread (every
 * method throws "Attempt to perform WebView method on a destroyed WebView").
 */
internal fun jsDispatchFor(destroyed: Boolean, onMainThread: Boolean): JsDispatch = when {
    destroyed -> JsDispatch.REJECT_DESTROYED
    onMainThread -> JsDispatch.INLINE
    else -> JsDispatch.HOP_TO_MAIN
}

/**
 * [r5-F F-F-01] The message a tab with no live WebView answers with, in one
 * place. `destroyed` and `rendererDead` need different advice (pick another
 * tab / this tab is done, open a new one), and the two consumers —
 * [BrowserUseManager]'s `webViewGoneResult()` and [deadTabAsyncJsAnswer]'s
 * async-JS reject shape — must not drift apart: the same dead tab used to
 * report "timed out" to `fetch` and "renderer was killed" to `navigate`.
 *
 * Pure (and top level, like [fetchHttpFailure]) so the JVM can pin both
 * branches with literals.
 */
internal fun deadTabGoneMessage(destroyed: Boolean): String =
    if (destroyed) {
        "This tab's WebView was already destroyed (the tab was closed or evicted) — " +
            "pick another tab or open a new one."
    } else {
        "This tab's WebView renderer was killed by the system; this tab can no longer " +
            "load pages — open a new tab."
    }

/**
 * [r5-F F-F-01] The async-JS answer for an inert tab: null when the tab is
 * live (proceed), else the bridge's own reject shape — `{"error":…}`, the form
 * `fetch` parses into "Fetch failed: …" and the teardown paths
 * (`onRendererGone` / `destroy`) complete an in-flight waiter with.
 *
 * Why this must be decided BEFORE `beginAsyncJsRequest()`: `onRendererGone`
 * fires at most once. A request registered after it has no completer at all —
 * the bridge lives in the dead renderer and no second death will come — so it
 * sits on the slot until `withTimeoutOrNull` gives up (30s for `executeJS`,
 * 60s for `awaitPromiseJs`). The two booleans are the same disjunction as
 * `webViewInert()`, spelled out so the decision is testable off-device.
 */
internal fun deadTabAsyncJsAnswer(destroyed: Boolean, rendererDead: Boolean): String? =
    if (destroyed || rendererDead) {
        "{\"error\":${JSONObject.quote(deadTabGoneMessage(destroyed))}}"
    } else {
        null
    }
