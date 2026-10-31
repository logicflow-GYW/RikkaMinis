package com.rikkaminis.app.browser

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [r3-B09] The browser teardown / failure-signal seams this wave repaired, all
 * of them "the code looks right and the defect is in what it never reaches":
 *
 *  - **F-01** `destroyTab` → `manager.destroy()` off the main thread. The
 *    WebView calls throw "A WebView method was called on thread …", the throw
 *    lands inside `destroy()`'s single catch AFTER it latched
 *    `destroyed = true`, so no later call can retry and the renderer leaks.
 *    Reachable from the memory hooks in `MinisApp` (all non-main) and from
 *    `dispose()` (`ViewModel.onCleared`).
 *  - **F-02** a woken `navigate()` reads page metadata off a tab whose
 *    renderer is gone → `evaluateJavascript`'s callback can never fire and
 *    `deferred.await()` has no timeout → the call hangs past the navigation
 *    timeout until the tool deadline.
 *  - **F-06** the post-scale `resize` dispatch went through the VIEW queue on a
 *    detached tab (parks in HandlerActionQueue) with no latch re-check.
 *  - **F-09** a URL download took any response — 404 page, cross-protocol 3xx
 *    stub — as the payload and reported success.
 *  - **F-10** a failed blob:/data: download ended as a Log.w inside the
 *    manager: no panel entry, no chat notice, nothing.
 *  - **F-14** `detectChallenge(statusCode = …)` had no producer: the two
 *    status-based kinds were unreachable.
 *
 * Everything here is a shape/ordering assertion on the real sources — the JVM
 * cannot start a WebView, and these are exactly the defects a pure function
 * cannot carry.
 */
class BrowserTeardownAndFailureSignalTest {

    // ── F-01: WebView teardown is a main-thread operation ───────────────

    @Test
    fun `tab teardown routes the manager destroy through the main thread`() {
        val pool = readRepoFile(TabPoolPath)
        val body = memberSlice(pool, "private fun destroyTab(")
        assertTrue(
            "destroyTab must not call manager.destroy() directly:\n$body",
            !body.contains("manager.destroy()"),
        )
        assertTrue(
            "destroyTab must route it through the main-thread helper:\n$body",
            body.contains("destroyManagerOnMain(tab.manager)"),
        )
        val helper = memberSlice(pool, "private fun destroyManagerOnMain(")
        assertTrue(
            "the helper must inline on Main:\n$helper",
            helper.contains("Looper.myLooper() == Looper.getMainLooper()"),
        )
        assertTrue(
            "the helper must hop otherwise — a `post` on the main Looper always " +
                "drains, unlike a detached View queue:\n$helper",
            helper.contains("Handler(Looper.getMainLooper()).post { manager.destroy() }"),
        )
        val total = Regex("manager\\.destroy\\(\\)").findAll(pool).count()
        val inHelper = Regex("manager\\.destroy\\(\\)").findAll(helper).count()
        assertTrue(
            "every manager.destroy() call in the pool must sit inside the helper " +
                "(found $inHelper of $total)",
            total == 2 && inHelper == 2,
        )
    }

    @Test
    fun `destroy itself stays a single pinned body`() {
        val body = memberSlice(readRepoFile(ManagerPath), "fun destroy()")
        assertTrue(
            "the teardown body must keep detaching + cancelling callbacks before " +
                "webView.destroy() — it is pinned by browser_destroy_wakeup_check, so " +
                "the thread hop lives in the pool, not here:\n$body",
            body.contains("webView.removeCallbacks(null)") && body.contains("webView.destroy()"),
        )
        assertFalse(
            "no thread hop may be introduced inside destroy() — moving the body behind " +
                "a helper takes every pinned wake-up/latch criterion with it:\n$body",
            body.contains("HOP_TO_MAIN"),
        )
    }

    // ── F-02: a dead tab answers, it does not wait ──────────────────────

    @Test
    fun `the renderer-gone latch is armed where the renderer dies`() {
        val src = readRepoFile(ManagerPath)
        assertTrue(
            "the inertness latch must be its own flag (destroyed would make the real " +
                "destroy() return early and leak the view)",
            src.contains("private var rendererDead = false"),
        )
        assertTrue(
            "inert means destroyed OR renderer-dead",
            src.contains("private fun webViewInert(): Boolean = destroyed || rendererDead"),
        )
        val gone = window(src, "override fun onRendererGone(", 1_600)
        assertTrue(
            "onRendererGone must arm the latch before it wakes the waiters:\n$gone",
            gone.contains("rendererDead = true"),
        )
    }

    @Test
    fun `navigate refuses a dead tab instead of burning the timeout`() {
        val body = memberSlice(readRepoFile(ManagerPath), "private suspend fun navigate(")
        assertTrue(
            "navigate must short-circuit on an inert tab:\n$body",
            body.contains("if (webViewInert()) return webViewGoneResult()"),
        )
        assertTrue(
            "the metadata read must also be gated: the wake-up can be 'renderer died', " +
                "and reading the live page then awaits a callback that cannot arrive:\n$body",
            body.contains("details = if (webViewInert())"),
        )
        // The short-circuit names a helper; pin the definition too, so a rename or
        // a dropped body cannot leave the call dangling (that is a compile error,
        // and CI is the only compiler this wave has).
        val gone = memberSlice(readRepoFile(ManagerPath), "private fun webViewGoneResult(")
        assertTrue(
            "webViewGoneResult must be defined", gone.isNotEmpty(),
        )
        assertTrue(
            "webViewGoneResult must answer with the shared dead-tab message:\n$gone",
            gone.contains("deadTabGoneMessage(destroyed)"),
        )
        // [r5-F F-F-01] The wording moved to a top-level pure helper
        // (deadTabGoneMessage) so the async-JS reject shape executeJS/fetch
        // answer with carries the SAME text — one dead tab, one story. The
        // distinguish-the-two-causes criterion moves with it.
        val wording = topLevelSlice(readRepoFile(ManagerPath), "internal fun deadTabGoneMessage(")
        assertTrue(
            "the result must distinguish 'tab torn down' from 'renderer killed' — the " +
                "caller's fix differs:\n$wording",
            wording.contains("if (destroyed)") && wording.contains("renderer was killed"),
        )
    }

    @Test
    fun `no JS wait or capture outlives the WebView`() {
        val src = readRepoFile(ManagerPath)
        val eval = memberSlice(src, "private suspend fun evaluateJavascript(")
        assertTrue(
            "evaluateJavascript has no timeout: on a dead tab the callback never " +
                "arrives, so it must answer with the bridge's error shape instead of " +
                "awaiting forever:\n$eval",
            eval.contains("if (webViewInert()) {") &&
                eval.contains("return@withContext") &&
                // [F-B-02] The bridge's error shape now comes from ONE source
                // (deadTabAsyncJsAnswer → deadTabGoneMessage), pinned by the
                // wording test above. Accept the call so the gate still bites
                // on "answers without an error shape" without forcing a fourth
                // hand-written literal back into the file.
                (eval.contains("deadTabAsyncJsAnswer(") || eval.contains("{\\\"error\\\":"))),
        )
        val capture = memberSlice(src, "private suspend fun captureWebViewBitmap(")
        assertTrue(
            "the auto-snapshot must not touch a dead WebView:\n$capture",
            capture.contains("if (webViewInert()) {") && capture.contains("return@withContext null"),
        )
    }

    // ── F-06: the resize dispatch lands on the Looper, guarded ──────────

    @Test
    fun `the post-scale resize does not use the view queue`() {
        val src = readRepoFile(ManagerPath)
        assertFalse(
            "`view.postDelayed({` parks the runnable in a detached tab's " +
                "HandlerActionQueue, which the destroy path cannot reach " +
                "(`removeCallbacks(null)` is a no-op) — it either fires on a dead " +
                "renderer or replays into the next page attach",
            src.contains("view.postDelayed({"),
        )
        assertTrue(
            "the dispatch must go through the main Looper",
            src.contains("Handler(Looper.getMainLooper()).postDelayed({"),
        )
        assertTrue(
            "…and must re-check the destroyed latch when it runs",
            src.contains("runJsNow(\"post-scale-resize\")"),
        )
    }

    // ── F-09: a download is only a download on 2xx ──────────────────────

    @Test
    fun `url downloads gate on the status and chase redirects by hand`() {
        val body = memberSlice(readRepoFile(TabPoolPath), "internal fun startUrlDownload(")
        assertTrue(
            "an error page must not be written as a completed download:\n$body",
            body.contains("if (code !in 200..299) {") &&
                body.contains("throw java.io.IOException(\"HTTP \$code\")"),
        )
        assertTrue(
            "cross-protocol redirects are not auto-followed by HttpURLConnection, so " +
                "Location has to be chased — bounded:\n$body",
            body.contains("getHeaderField(\"Location\")") &&
                body.contains("MAX_DOWNLOAD_REDIRECT_HOPS"),
        )
        assertFalse(
            "the original (stale) length preference must be gone: after a redirect the " +
                "listener's contentLength describes the ORIGINAL url",
            body.contains("if (contentLength > 0) contentLength else conn.contentLengthLong"),
        )
    }

    // ── F-10: a failed blob download is visible ─────────────────────────

    @Test
    fun `blob download failures reach the panel and the chat`() {
        val manager = readRepoFile(ManagerPath)
        assertTrue(
            "the manager must expose a failure channel",
            manager.contains("var onBlobDownloadFailed: ((filename: String?, reason: String) -> Unit)? = null"),
        )
        val calls = Regex("reportBlobFailure\\(").findAll(manager).count()
        assertTrue(
            "every silent drop branch must go through the reporting funnel " +
                "(definition + 7 sites, found $calls)",
            calls >= 8,
        )
        assertFalse(
            "no bare 'rejected' / 'dropped' log may remain — those were the silent " +
                "branches",
            manager.contains("blob download rejected:") || manager.contains("blob download dropped:"),
        )
        val pool = readRepoFile(TabPoolPath)
        assertTrue(
            "the pool must wire the channel",
            pool.contains("manager.onBlobDownloadFailed = { name, reason -> reportDownloadFailure(name, reason) }"),
        )
        val report = memberSlice(pool, "internal fun reportDownloadFailure(")
        assertTrue(
            "the failure must land in the downloads panel as a FAILED entry:\n$report",
            report.contains("DownloadState.FAILED"),
        )
        assertTrue(
            "…and post the same chat notice the streaming failure path posts:\n$report",
            report.contains("onDownloadEvent?.invoke(\"Download failed:"),
        )
        assertTrue(
            "the entry must carry no destination — there is no file to open:\n$report",
            report.contains("destination = null"),
        )
    }

    // ── F-14: the risk detector finally gets a status ────────────────────

    @Test
    fun `the risk detector is fed the main-frame http status`() {
        val pool = readRepoFile(TabPoolPath)
        assertTrue(
            "the production call site must pass statusCode",
            pool.contains("statusCode = tab.manager.lastMainFrameHttpError"),
        )
        val manager = readRepoFile(ManagerPath)
        assertTrue(
            "the manager must own the reading",
            manager.contains("var lastMainFrameHttpError: Int? = null"),
        )
        val httpError = window(manager, "override fun onReceivedHttpError(", 1_200)
        assertTrue(
            "only the MAIN frame counts — a subresource 404 is page furniture:\n$httpError",
            httpError.contains("if (request.isForMainFrame)"),
        )
        assertTrue(
            "the status recorded is the response's:\n$httpError",
            httpError.contains("lastMainFrameHttpError = errorResponse.statusCode"),
        )
        val pageStarted = window(manager, "override fun onPageStarted(", 1_200)
        assertTrue(
            "a new document must clear the previous status, or a stale 429 keeps " +
                "throttling a page that loaded fine:\n$pageStarted",
            pageStarted.contains("lastMainFrameHttpError = null"),
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun memberSlice(src: String, marker: String): String {
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains(marker) }
        assertTrue("marker not found: $marker", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { i ->
            val t = lines[i]
            t.startsWith("    /**") || t.startsWith("    @") ||
                t.startsWith("    fun ") || t.startsWith("    internal fun ") ||
                t.startsWith("    private fun ") || t.startsWith("    private suspend fun ") ||
                t.startsWith("    internal val ") || t.startsWith("    private val ") ||
                t.startsWith("    // --")
        } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    /** [chars] characters following [marker] — for members nested in objects. */
    private fun window(src: String, marker: String, chars: Int): String {
        val at = src.indexOf(marker)
        assertTrue("marker not found: $marker", at >= 0)
        return src.substring(at, minOf(src.length, at + chars))
    }

    /**
     * [r5-F F-F-01] A top-level declaration, sliced by ITS OWN domain — the
     * marker line through the next blank line — rather than a character
     * window: two earlier waves shipped criteria that went red on a correct
     * edit because an inserted comment pushed the anchor out of the window.
     */
    private fun topLevelSlice(src: String, marker: String): String {
        val at = src.indexOf(marker)
        assertTrue("marker not found: $marker", at >= 0)
        val lineStart = src.lastIndexOf('\n', at) + 1
        val blank = src.indexOf("\n\n", at)
        return src.substring(lineStart, if (blank < 0) src.length else blank)
    }

    private companion object {
        private const val TabPoolPath =
            "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserTabPool.kt"
        private const val ManagerPath =
            "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserUseManager.kt"
    }
}
