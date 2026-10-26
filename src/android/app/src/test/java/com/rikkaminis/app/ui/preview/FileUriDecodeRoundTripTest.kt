package com.rikkaminis.app.ui.preview

import com.rikkaminis.app.ui.chat.decodeUriPath
import com.rikkaminis.app.ui.sandbox.encodeFileUrlPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [da1023-H] JVM-pure tests for ISSUE-08: local `file://` URLs were fed to
 * `Uri.parse(...).path`, which reparses the string as a *generic* URI — a
 * literal `#` / `?` inside a local file name started a fragment / query and
 * was cut, so `report#1.html` resolved to `…/report`, `exists()` was false,
 * and "open in browser" / markdown media silently did nothing.
 *
 * Like [AssetLoaderUrlMappingTest] this class is deliberately free of
 * `android.net.Uri`: with `unitTests.isReturnDefaultValues = true` the stubbed
 * `Uri.parse` returns defaults instead of throwing, so a test built on it
 * would pass while testing nothing. String literals only.
 *
 * What each arm pins:
 *  * the single decode exit (ui.chat.decodeUriPath) has NO delimiter
 *    semantics — `#` / `?` are plain filename characters, `%XX` decodes,
 *    plain paths pass through byte-identical;
 *  * the asset-loader mapping round-trips names containing `#` / `?` /
 *    space / CJK / a literal `%XX`, and what comes back out is byte-identical
 *    to the URL the holder was constructed with (encoded in, encoded out —
 *    the holder-reuse invariant);
 *  * a LITERAL `#` / `?` in a *reported* asset URL is always a fragment /
 *    query (the encoder escapes real names to %23 / %3F), so
 *    [fromAssetLoaderUrl] must cut it before decoding — the androidx loader
 *    itself resolves the path only, and publishing `…/a.html#section` as a
 *    `file://` URL produces a path no `File.exists()` check can satisfy.
 *
 * The two Compose/Context call sites (openExternalFromSheet's `file://`
 * branch and resolveMdMediaFile's `file://` branch) are not callable on the
 * JVM; their wiring — the exact `decodeUriPath(url.removePrefix("file://"))`
 * and `decodeUriPath(stripped.removePrefix("file://"))` expressions and the
 * ban on `Uri.parse(...).path` — is pinned structurally by
 * scripts/scan/file_uri_parse_guard_check.py, and the modelled resolution
 * shape is asserted here (same "downstream judgment, verbatim model" pattern
 * as AssetLoaderUrlMappingTest).
 */
class FileUriDecodeRoundTripTest {

    private val rootDir = "/data/x"

    /** Verbatim model of the fixed expression at both call sites
     * (WebPreviewBottomSheet: `decodeUriPath(url.removePrefix("file://"))`,
     * StreamingMarkdownText: `decodeUriPath(stripped.removePrefix("file://"))`). */
    private fun fixedResolve(url: String): String =
        decodeUriPath(url.removePrefix("file://"))

    // ---- decodeUriPath semantics: no fragment/query semantics ------------

    @Test fun hashIsALiteralNotAFragment() {
        assertEquals("/data/x/report#1.html", fixedResolve("file:///data/x/report#1.html"))
        assertEquals("/data/x/report#1.html", decodeUriPath("/data/x/report#1.html"))
    }

    @Test fun questionMarkIsALiteralNotAQuery() {
        assertEquals("/data/x/v1?draft.html", fixedResolve("file:///data/x/v1?draft.html"))
        assertEquals("/data/x/v1?draft.html", decodeUriPath("/data/x/v1?draft.html"))
    }

    @Test fun percentEscapesDecode() {
        assertEquals("/data/x/a b.png", fixedResolve("file:///data/x/a%20b.png"))
        assertEquals("/data/x/我的 报告.html",
            fixedResolve("file:///data/x/%E6%88%91%E7%9A%84%20%E6%8A%A5%E5%91%8A.html"))
    }

    @Test fun plainPathPassesThroughByteIdentical() {
        assertEquals("/data/x/plain-name_v2.1.html",
            fixedResolve("file:///data/x/plain-name_v2.1.html"))
        // fast path: no '%' at all
        assertEquals("/data/x/plain-name_v2.1.html", decodeUriPath("/data/x/plain-name_v2.1.html"))
    }

    @Test fun malformedEscapeSurvivesVerbatim() {
        // A bare `%` (100%.txt) is left as-is, never throws — composition-safety.
        assertEquals("/data/x/100%.txt", fixedResolve("file:///data/x/100%.txt"))
    }

    // ---- asset-loader mapping round-trips real names ----------------------

    // [fix/fileurl-contract] The round trip is asserted in the ENCODED URL form:
    // the contract is "encoded at construction, decoded at every path-semantics
    // consumer", so what must come back out of the asset origin is exactly the
    // URL the holder was constructed with (that byte-equality is what the
    // holder-reuse comparison in ChatScreen depends on).
    private fun fileUrl(path: String): String = "file://" + encodeFileUrlPath(path)

    @Test fun hashNameRoundTripsThroughAssetOrigin() {
        val url = fileUrl("/data/x/report#1.html")
        assertEquals("file:///data/x/report%231.html", url)
        val forward = toAssetLoaderUrl(url)
        assertEquals("https://appassets.androidplatform.net/report%231.html", forward)
        assertEquals(url, fromAssetLoaderUrl(requireNotNull(forward), rootDir))
    }

    @Test fun questionMarkNameRoundTripsThroughAssetOrigin() {
        val url = fileUrl("/data/x/v1?draft.html")
        assertEquals("file:///data/x/v1%3Fdraft.html", url)
        val forward = toAssetLoaderUrl(url)
        assertEquals("https://appassets.androidplatform.net/v1%3Fdraft.html", forward)
        assertEquals(url, fromAssetLoaderUrl(requireNotNull(forward), rootDir))
    }

    @Test fun spaceAndCjkNameRoundTripsThroughAssetOrigin() {
        val url = fileUrl("/data/x/我的 报告.html")
        assertEquals("file:///data/x/%E6%88%91%E7%9A%84%20%E6%8A%A5%E5%91%8A.html", url)
        assertEquals(url, fromAssetLoaderUrl(requireNotNull(toAssetLoaderUrl(url)), rootDir))
    }

    @Test fun publishedUrlIsByteIdenticalToTheRequestedUrl() {
        // The holder-reuse invariant (ChatScreen: existing.currentUrl != url):
        // after a page start, currentUrl must compare EQUAL to the URL the
        // holder was built from, or every repeat tap destroys the WebView.
        for (name in listOf("a.html", "my file.html", "我的 报告.html", "report#1.html",
                            "v1?draft.html", "50%23.html")) {
            val url = fileUrl("/data/x/$name")
            val published = fromAssetLoaderUrl(requireNotNull(toAssetLoaderUrl(url)), rootDir)
            assertEquals("published URL must equal the requested URL for `$name`", url, published)
            // and the decoders get the real path back
            assertEquals("/data/x/$name", decodeUriPath(requireNotNull(published).removePrefix("file://")))
        }
    }

    @Test fun literalPercentNameSurvivesWhereALiteralDecodeWouldCorrupt() {
        // fix/debt-sweep-1026 regression: a file NAMED `50%23.html` used to reach
        // the decoding consumers as `50#.html` because the construction site
        // concatenated the raw path. Encoded construction escapes the `%` first,
        // so the URL carries `%2523` and decodes back to `%23`.
        val raw = "/data/x/50%23.html"
        val url = fileUrl(raw)
        assertEquals("file:///data/x/50%2523.html", url)
        assertEquals("https://appassets.androidplatform.net/50%2523.html", toAssetLoaderUrl(url))
        val published = fromAssetLoaderUrl(requireNotNull(toAssetLoaderUrl(url)), rootDir)
        assertEquals(url, published)
        assertEquals(raw, decodeUriPath(requireNotNull(published).removePrefix("file://")))
        // the shape this branch replaced: a raw concatenation decoded literally
        // by the consumer mangles the name (this is the witness, not the fix).
        assertTrue("decoding the RAW path must mangle the literal %XX name",
            decodeUriPath(raw) != raw)
    }

    @Test fun unencodedLegacyUrlStillMapsToTheSameAssetUrl() {
        // Backward-compat for any caller that still hands over a raw path (no
        // literal %XX in it): decode-then-re-encode is idempotent there.
        assertEquals(
            toAssetLoaderUrl(fileUrl("/data/x/我的 报告.html")),
            toAssetLoaderUrl("file:///data/x/我的 报告.html"),
        )
    }

    // ---- literal #/? in a REPORTED asset URL = fragment/query, cut it ----

    @Test fun literalFragmentIsCutBeforeDecode() {
        // Pre-1023-H this published "file:///data/x/a.html#section" — a path
        // File.exists() can never satisfy.
        assertEquals("file:///data/x/a.html",
            fromAssetLoaderUrl("https://appassets.androidplatform.net/a.html#section", rootDir))
    }

    @Test fun literalQueryIsCutBeforeDecode() {
        assertEquals("file:///data/x/style.css",
            fromAssetLoaderUrl("https://appassets.androidplatform.net/style.css?v=2", rootDir))
    }

    @Test fun queryAndFragmentCutInEitherOrder() {
        assertEquals("file:///data/x/a.html",
            fromAssetLoaderUrl("https://appassets.androidplatform.net/a.html?v=2#s", rootDir))
        assertEquals("file:///data/x/a.html",
            fromAssetLoaderUrl("https://appassets.androidplatform.net/a.html#s?not-a-query", rootDir))
    }

    @Test fun fragmentOnlyUrlFailsClosedToRoot() {
        // Degenerate: nothing left after cutting -> the root directory, which
        // the exists()/isFile() consumers reject downstream.
        assertEquals("file:///data/x",
            fromAssetLoaderUrl("https://appassets.androidplatform.net/#frag", rootDir))
    }

    // ---- unchanged fail-closed arms (guard, not discriminator) ------------

    @Test fun nonAssetOriginStillMapsToNull() {
        assertNull(fromAssetLoaderUrl("https://example.com/a.html", rootDir))
        assertNull(fromAssetLoaderUrl("https://appassets.androidplatform.net.evil.com/a.html", rootDir))
        assertNull(fromAssetLoaderUrl("https://appassets.androidplatform.net/../../etc/passwd", rootDir))
        assertNull(fromAssetLoaderUrl("https://appassets.androidplatform.net/a.html", null))
    }

    @Test fun plainNameMappingIsByteIdenticalToLegacy() {
        assertEquals("https://appassets.androidplatform.net/a.html",
            toAssetLoaderUrl("file:///data/x/a.html"))
    }
}
