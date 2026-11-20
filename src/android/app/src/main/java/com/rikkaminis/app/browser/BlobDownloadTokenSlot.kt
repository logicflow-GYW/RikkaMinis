package com.rikkaminis.app.browser

import org.json.JSONObject

/**
 * [SURGERY-C02] The one-shot credential registry behind the blob-download
 * bridge (`__minis__.saveBlobDownload` / `__minis__.blobDownloadError`).
 *
 * Why the failure callback is credentialed at all: `addJavascriptInterface`
 * exposes the bridge to EVERY page the tab loads (iframes included), so a
 * callback that needs no credential lets any page write business state — a
 * FAILED row in the downloads panel plus a native "Download failed: …" notice
 * in the chat — with attacker-authored text, arbitrarily often, arbitrarily
 * long. Before this wave `blobDownloadError(error)` took no token at all; the
 * success callback had one but compared it with `==` and cleared it in two
 * separate steps, so two concurrent JavaBridge calls could both pass.
 *
 * This object is the single place where "may this callback write business
 * state?" is decided, so success and error cannot drift apart:
 *
 *  - **one owner at a time** — [arm] stores the token [BrowserUseManager.fetchBlobDownload]
 *    interpolates into the injected FileReader;
 *  - **atomic single use** — [claim] compares AND clears under one lock, so of
 *    two callbacks carrying the same valid token exactly one gets [Claim.MATCH]
 *    (success and error are mutually exclusive by construction, and a replay
 *    after the real answer is dead);
 *  - **a wrong token never destroys the pending request** — [Claim.NOT_CURRENT]
 *    leaves the slot armed, so a page racing garbage at the bridge cannot
 *    knock out the download it is guessing at.
 *
 * Pure JVM (no Android types) so the state machine is unit-testable: [nowMs]
 * is injected, which is the only way to exercise the expiry arm deterministically.
 */
internal class BlobDownloadTokenSlot(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Outcome of presenting a token to [claim]. The caller's reaction differs
     * per value (see [BrowserUseManager]'s bridge members), which is why this
     * is an enum and not a boolean:
     *
     *  - [MATCH] — this callback owns the armed request; consume and act.
     *  - [NO_TOKEN_ARMED] — nothing is pending (replay after the real answer,
     *    or a page calling the bridge on its own). Diagnostic only.
     *  - [NOT_CURRENT] — a NEWER request owns the slot (this reader answered
     *    after the page replaced its download). Diagnostic + the visible
     *    "replaced" failure the success path has reported since r3-B09 F-10.
     *  - [EXPIRED] — the armed request is older than [BrowserUseManager]'s
     *    window, i.e. armed but never answered. Diagnostic only, by design: the
     *    only way to present the expired value is to hold the token we injected,
     *    so acting on it would hand a stale page the panel entry this change
     *    exists to deny.
     */
    internal enum class Claim { MATCH, NO_TOKEN_ARMED, NOT_CURRENT, EXPIRED }

    /** The armed credential, or null when no request is pending. */
    @Volatile
    var activeToken: String? = null
        private set

    /** When [activeToken] was armed (`0` when nothing is armed). */
    @Volatile
    private var armedAtMs: Long = 0L

    /** Arm [value] as the one credential the bridge will accept. */
    fun arm(value: String?) {
        synchronized(this) {
            activeToken = value
            armedAtMs = if (value == null) 0L else nowMs()
        }
    }

    /**
     * Present [presented] and, on a match, consume the credential.
     *
     * Consumption happens even when the match is stale ([Claim.EXPIRED]) —
     * "armed but never answered" must not stay replayable. A mismatch leaves
     * the slot untouched (see the class note).
     */
    fun claim(presented: String, maxAgeMs: Long): Claim = synchronized(this) {
        val active = activeToken ?: return Claim.NO_TOKEN_ARMED
        if (presented.isEmpty() || presented != active) return Claim.NOT_CURRENT
        val ageMs = nowMs() - armedAtMs
        activeToken = null
        armedAtMs = 0L
        if (maxAgeMs > 0 && ageMs > maxAgeMs) Claim.EXPIRED else Claim.MATCH
    }
}

/**
 * [SURGERY-C02] Upper bound on a page-supplied download-failure reason.
 *
 * The reason is rendered verbatim in the downloads panel and in a native chat
 * notice, so an unauthenticated caller could previously grow the list entry and
 * the chat line to megabytes per call. 240 chars keeps a real `String(e)` /
 * FileReader message readable and bounds the row; control characters are
 * collapsed first (a reason carrying `\n` could otherwise fake extra lines in
 * the chat notice). This bound is NOT the fix for the missing credential — the
 * credential is — it only keeps a credentialed-but-hostile page from turning a
 * visible failure into an unbounded one.
 */
internal const val MAX_BLOB_FAILURE_REASON_CHARS = 240

/**
 * Sanitize + bound [reason] for the downloads panel / chat notice.
 *
 * Collapses every control character (C0 + DEL) to a single space and the
 * surrounding whitespace, then truncates to [MAX_BLOB_FAILURE_REASON_CHARS]
 * with an ellipsis. Blank input keeps the caller's own wording, so an empty
 * `String(e)` still yields a sentence rather than an empty row.
 */
internal fun boundedBlobFailureReason(reason: String, fallback: String): String {
    val cleaned = buildString(minOf(reason.length, MAX_BLOB_FAILURE_REASON_CHARS * 2)) {
        var pendingSpace = false
        for (ch in reason) {
            val control = ch.code < 0x20 || ch.code == 0x7F
            if (control || ch.isWhitespace()) {
                pendingSpace = isNotEmpty()
                continue
            }
            if (pendingSpace) {
                append(' ')
                pendingSpace = false
            }
            append(ch)
        }
    }.trim()
    if (cleaned.isEmpty()) return fallback
    return if (cleaned.length <= MAX_BLOB_FAILURE_REASON_CHARS) cleaned
    else cleaned.take(MAX_BLOB_FAILURE_REASON_CHARS) + "…"
}

/**
 * [SURGERY-C02] The script [BrowserUseManager.fetchBlobDownload] injects: fetch
 * the blob: URL, read it as a data URL, hand it to the bridge.
 *
 * Top-level and Android-free (same lift as `decodeDataUrlPayload` /
 * `MinisMainDocGate`) so the generated JS can be executed outside a WebView:
 * the defect this wave fixes is that both error callbacks called
 * `__minis__.blobDownloadError(error)` WITHOUT the credential, i.e. a real
 * WebView was the only place it could be seen. Now that the token is part of
 * every bridge call, the script can be run against a stub `__minis__` and the
 * two call sites asserted directly (see BlobDownloadBridgeAuthTest).
 *
 * Both quoted arguments use [JSONObject.quote] — the original escaping — so the
 * JS stays valid for names containing quotes, `</script>`-style text or newlines.
 */
internal fun blobDownloadFetchScript(blobUrl: String, token: String, filename: String): String {
    val tokenLiteral = JSONObject.quote(token)
    return """
        (function() {
            fetch(${JSONObject.quote(blobUrl)})
                .then(function(r) { return r.blob(); })
                .then(function(blob) {
                    var reader = new FileReader();
                    reader.onloadend = function() {
                        __minis__.saveBlobDownload($tokenLiteral, reader.result, ${JSONObject.quote(filename)});
                    };
                    reader.onerror = function() { __minis__.blobDownloadError($tokenLiteral, 'FileReader error'); };
                    reader.readAsDataURL(blob);
                })
                .catch(function(e) { __minis__.blobDownloadError($tokenLiteral, String(e)); });
        })();
    """.trimIndent()
}
