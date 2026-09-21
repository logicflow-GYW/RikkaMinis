package com.rikkaminis.app.browser

import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import com.rikkaminis.app.logging.AppLogger

/**
 * [GH#341] A [WebViewClient] that survives the death of its renderer process.
 *
 * ## Why this class exists (the rule it defends against)
 *
 * Android runs WebView content in a separate, sandboxed Chromium process
 * ("renderer"). The system kills that process whenever it likes — most often
 * under memory pressure, which on a chat app with large conversations is a
 * matter of *when*, not *if*.
 *
 * Chromium's contract for that event is unforgiving: when the renderer dies,
 * the framework calls [onRenderProcessGone] on the WebView's client. If the
 * callback is not overridden, or if it returns `false`, the framework concludes
 * that **the application cannot handle the situation** and kills the whole app
 * process with an uncaught abort — not just the WebView. Every other WebView,
 * every live chat session and all in-memory state go with it.
 *
 * So a single WebView built on a plain `WebViewClient()` is enough to make the
 * entire app abortable by the OS. Upstream issue OpenMinis/OpenMinis#341
 * documents four such crashes on a real device inside 2h20m (`dumpsys activity
 * exit-info`), all with the framework abort message naming the missing
 * `onRenderProcessGone` handling.
 *
 * `minSdk = 26` for this app, and `onRenderProcessGone` is API 26+, so no
 * version guard is needed.
 *
 * ## The contract after the callback returns
 *
 * Returning `true` means "I handled it, do not kill the app" — and nothing
 * more. It does **not** heal anything: the renderer behind [view] is already
 * gone, so that `WebView` instance is permanently dead. Every subsequent call
 * on it (`loadUrl`, `evaluateJavascript`, `draw`) is undefined behaviour, and
 * reusing it will show a blank or frozen surface forever.
 *
 * Hosts must therefore **discard the instance** and build a new one if they
 * want to keep rendering. [onRendererGone] is the hook for that:
 * [destroyDeadWebView] is the shared teardown for hosts that have nothing
 * better to do, and hosts with richer recovery (re-load a URL, fall back to a
 * text rendering, evict a pool slot) override it.
 *
 * [onRenderProcessGone] is deliberately `final` so that no subclass can
 * reintroduce the original bug by overriding it with a `false` return.
 */
open class SafeWebViewClient : WebViewClient() {

    /**
     * Handles renderer death and **always** returns `true`.
     *
     * Returning `true` is the entire point of this class: it is the only
     * answer that stops Chromium from aborting the application process. Never
     * delegate to `super` here — the base implementation returns `false`.
     */
    final override fun onRenderProcessGone(
        view: WebView?,
        detail: RenderProcessGoneDetail?,
    ): Boolean {
        // didCrash() == true  → the renderer crashed (bad native code, OOM kill
        //                       inside the sandbox); false → the system reclaimed
        //                       it, typically memory pressure.
        // rendererPriorityAtExit() tells us how important the framework thought
        // the renderer was when it died — useful for telling "our own leak got
        // us killed" apart from "the device was just busy".
        val didCrash = detail?.didCrash()
        val priorityAtExit = detail?.rendererPriorityAtExit()
        AppLogger.warning(
            TAG,
            "renderer process gone (didCrash=$didCrash, priorityAtExit=$priorityAtExit) — " +
                "WebView instance is now dead; app kept alive (returning true)",
        )
        // Host-specific recovery. Anything this hook does NOT do, the host
        // simply does not get — the app stays alive either way.
        onRendererGone(view)
        return true
    }

    /**
     * Called after the renderer for [view] has died and the instance has
     * become unusable. Override to rebuild, evict, or fall back.
     *
     * The default implementation discards the dead instance via
     * [destroyDeadWebView]: a WebView whose renderer is gone still holds a
     * renderer handle slot, and leaving it attached would keep that slot until
     * GC. Hosts that override this take over the teardown themselves — either
     * because they rebuild (and therefore need the old view gone) or because
     * they have a more specific release path.
     */
    protected open fun onRendererGone(view: WebView?) {
        destroyDeadWebView(view)
    }

    /**
     * Discards a WebView whose renderer has died.
     *
     * Safe to call from [onRendererGone]: detaching before `destroy()` avoids
     * the OEM-specific crashes that `destroy()` on an attached view can cause
     * (ConnectionTerminated / missing renderer), and the whole sequence is
     * wrapped because a dead WebView is exactly the kind of object whose
     * teardown throws.
     */
    protected fun destroyDeadWebView(view: WebView?) {
        if (view == null) return
        runCatching {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        }.onFailure { t ->
            AppLogger.warning(TAG, "teardown of dead WebView failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "SafeWebViewClient"
    }
}
