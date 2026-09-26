package com.rikkaminis.app.network

import okhttp3.Call
import okhttp3.Response

/**
 * [absorb-network-pack: feat-provider-health] Trace listener + provider
 * health feed in one. Subclasses the shared [OkHttpNetTraceListener] (trace
 * semantics untouched) and feeds the per-model ring in
 * [com.rikkaminis.app.diagnostics.ProviderHealthTracker]:
 *
 *   - callStart            → begin(attempt)
 *   - responseHeadersStart → recordFirstToken(ttfb = ms since callStart)
 *   - callEnd              → finish(success = last HTTP status < 400,
 *                                  failureReason = "HTTP <code>" on 4xx/5xx)
 *   - callFailed           → finish(success = false, failureReason = ioe message)
 *
 * One instance per call (each provider's eventListenerFactory creates a
 * fresh one), so per-call state (the attempt token, last status) is safe.
 *
 * Zero-overhead contract (mirrors StreamPerfMonitor): all bookkeeping is
 * once per CALL (not per chunk) — two data-class copies + one synchronized
 * ring append per attempt; the per-chunk path stays untouched.
 */
internal class ProviderHealthTraceListener(
    private val modelId: String,
    private val modelDisplayName: String,
) : OkHttpNetTraceListener() {

    private var attempt: com.rikkaminis.app.diagnostics.ProviderHealthTracker.Attempt? = null
    private var lastHttpStatus = 0

    override fun callStart(call: Call) {
        attempt = com.rikkaminis.app.diagnostics.ProviderHealthTracker.begin(modelId, modelDisplayName)
        super.callStart(call)
    }

    override fun responseHeadersStart(call: Call) {
        attempt?.let {
            // recordFirstToken returns a COPY carrying the TTFB — the tracker never
            // mutates the attempt it was given, and finish() is what appends to the
            // ring. Dropping this return value (the obvious-looking call) silently
            // leaves every recorded attempt with a null TTFB, which makes
            // recordedTtfbs()/ttfbP50/P95 permanently empty. Keep the copy.
            attempt = attempt?.let {
                com.rikkaminis.app.diagnostics.ProviderHealthTracker.recordFirstToken(it, ms())
            }
        }
        super.responseHeadersStart(call)
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        lastHttpStatus = response.code
        super.responseHeadersEnd(call, response)
    }

    override fun callEnd(call: Call) {
        attempt?.let {
            if (lastHttpStatus in 400..599) {
                // OkHttp-level success but HTTP-level failure (4xx/5xx) — the
                // endpoint answered, so this is a service attempt that failed.
                com.rikkaminis.app.diagnostics.ProviderHealthTracker.finish(
                    it, success = false, failureReason = "HTTP $lastHttpStatus", durationMs = ms(),
                )
            } else {
                com.rikkaminis.app.diagnostics.ProviderHealthTracker.finish(it, success = true, durationMs = ms())
            }
        }
        super.callEnd(call)
    }

    override fun callFailed(call: Call, ioe: java.io.IOException) {
        attempt?.let {
            com.rikkaminis.app.diagnostics.ProviderHealthTracker.finish(
                it, success = false, failureReason = "${ioe.javaClass.simpleName}:${ioe.message}", durationMs = ms(),
            )
        }
        super.callFailed(call, ioe)
    }
}
