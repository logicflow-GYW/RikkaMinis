package com.rikkaminis.app.offload

/**
 * [FULLSCAN-1119 F-16] The `WebChromeClient.onCreateWindow` contract, as a pure
 * decision.
 *
 * WebView's rule: the return value and the `resultMsg` must not contradict each
 * other. Returning `true` claims "the host owns this request" — from that moment
 * WebView waits for a `Message` carrying the new WebView's transport. If the
 * host returns `true` and never sends, the popup's `WebContents` is never
 * released: a leak per `window.open()` / `target="_blank"` click.
 *
 * The pre-fix code did exactly that on every refusal path: `handleNewWindow`
 * returns early when the pool is at its tab cap or already disposed, but
 * `onCreateWindow` still answered `true` because a handler was attached.
 *
 * The decision is expressed here (not inline in the Android class) so it is
 * pinned by a unit test: whatever happens, exactly one of "a transport with a
 * WebView was handed back" or "the request is explicitly declined" must occur.
 */
internal object PopupWindowPolicy {

    enum class Action {
        /** The handler produced a WebView; the message is already on its way. */
        ACCEPTED,

        /** Nobody will open a window — send the message with a NULL WebView. */
        DECLINE,
    }

    /**
     * @param hasHandler whether a `window.open` callback is attached at all.
     * @param transportHasWebView whether the callback filled the transport.
     */
    fun decide(hasHandler: Boolean, transportHasWebView: Boolean): Action = when {
        // No handler: nobody can ever answer, so decline rather than claim it.
        !hasHandler -> Action.DECLINE
        // Handler ran but left the transport empty (tab cap / pool disposed):
        // the request is answered — with "no".
        !transportHasWebView -> Action.DECLINE
        else -> Action.ACCEPTED
    }

    /**
     * Log line for a declined popup. Kept next to the decision so the two
     * cannot drift; the specific cause (tab cap, disposed pool, no session) is
     * logged by the pool handler that refused it.
     */
    fun declineReason(hasHandler: Boolean): String = if (hasHandler) {
        "window.open declined by the host (see the preceding tab-pool reason: tab cap / pool disposed)"
    } else {
        "window.open declined: no host handler attached"
    }
}
