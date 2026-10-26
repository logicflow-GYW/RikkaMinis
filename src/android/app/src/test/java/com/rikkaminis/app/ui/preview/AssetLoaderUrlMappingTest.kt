package com.rikkaminis.app.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [da1020-D] JVM-pure tests for the two-way asset-loader URL mapping
 * (`toAssetLoaderUrl` / `fromAssetLoaderUrl`, WebViewHolder.kt).
 *
 * The mapping is deliberately free of `android.net.Uri`: with
 * `unitTests.isReturnDefaultValues = true` the stubbed `Uri.parse` returns
 * defaults instead of throwing, so a test built on it would pass while testing
 * nothing (the trap this branch was told to avoid). String literals only.
 *
 * Bug context (ISSUE-04): `onPageStarted` used to publish the WebView's
 * internal URL (`https://appassets.androidplatform.net/<name>`) as
 * `currentUrl`, which broke three consumers that key off the user-visible URL:
 * the FileProvider branch of `openExternalFromSheet`, the `startsWith("file://")`
 * gate of the pin-to-home entry, and ChatScreen's holder-reuse comparison.
 */
class AssetLoaderUrlMappingTest {

    private val rootDir = "/data/x"

    // ---- forward: file:// -> asset loader origin --------------------------

    @Test fun plainFileUrlMapsToAssetLoaderOrigin() {
        assertEquals(
            "https://appassets.androidplatform.net/a.html",
            toAssetLoaderUrl("file:///data/x/a.html"),
        )
    }

    @Test fun asciiUnreservedNamesAreByteIdenticalToLegacyOutput() {
        // Legacy shape was "https://appassets.androidplatform.net/${file.name}"
        // — ASCII unreserved names must not change what gets loaded.
        assertEquals(
            "https://appassets.androidplatform.net/report-2024_final.v2.html",
            toAssetLoaderUrl("file:///data/x/report-2024_final.v2.html"),
        )
    }

    @Test fun nonFileUrlForwardMapsToNull() {
        assertNull(toAssetLoaderUrl("https://example.com/page.html"))
        assertNull(toAssetLoaderUrl("about:blank"))
        assertNull(toAssetLoaderUrl("content://media/1"))
    }

    @Test fun fileUrlWithoutNameComponentMapsToNull() {
        // Degenerate input (directory-style URL): fail closed to "load the URL
        // unchanged" instead of inventing a name.
        assertNull(toAssetLoaderUrl("file:///data/x/"))
    }

    @Test fun cjkAndSpaceFileNameIsPercentEncoded() {
        assertEquals(
            "https://appassets.androidplatform.net/%E6%88%91%E7%9A%84%20%E6%8A%A5%E5%91%8A.html",
            toAssetLoaderUrl("file:///data/x/我的 报告.html"),
        )
    }

    @Test fun plusAndPercentAreEscapedNotLeftLiteral() {
        // '+' must reach the loader as %2B: URLDecoder-based decode (what the
        // androidx loader uses) turns a literal '+' into a space.
        assertEquals(
            "https://appassets.androidplatform.net/a%2Bb.html",
            toAssetLoaderUrl("file:///data/x/a+b.html"),
        )
        assertEquals(
            "https://appassets.androidplatform.net/50%25off.html",
            toAssetLoaderUrl("file:///data/x/50%off.html"),
        )
    }

    // ---- reverse: asset loader origin -> file:// --------------------------

    @Test fun assetLoaderUrlMapsBackToOriginalFileUrl() {
        assertEquals(
            "file:///data/x/a.html",
            fromAssetLoaderUrl("https://appassets.androidplatform.net/a.html", rootDir),
        )
    }

    @Test fun externalUrlMapsToNullSoCallerKeepsIt() {
        assertNull(fromAssetLoaderUrl("https://example.com/page.html", rootDir))
        assertNull(fromAssetLoaderUrl("file:///data/x/a.html", rootDir))
    }

    @Test fun lookalikeHostIsRejectedByExactOriginCheck() {
        // The '/' after the host is part of the accepted prefix, so this
        // lookalike origin (BrowserUseManager:2808 family) must NOT match.
        assertNull(
            fromAssetLoaderUrl("https://appassets.androidplatform.net.evil.com/a.html", rootDir),
        )
    }

    @Test fun missingRootDirMapsToNull() {
        assertNull(fromAssetLoaderUrl("https://appassets.androidplatform.net/a.html", null))
    }

    @Test fun subDirectoryNavigationMapsBackUnderTheRoot() {
        assertEquals(
            "file:///data/x/sub/b.html",
            fromAssetLoaderUrl("https://appassets.androidplatform.net/sub/b.html", rootDir),
        )
    }

    @Test fun rootDirWithTrailingSlashIsNormalized() {
        assertEquals(
            "file:///data/x/a.html",
            fromAssetLoaderUrl("https://appassets.androidplatform.net/a.html", "/data/x/"),
        )
    }

    @Test fun encodedNameRoundTripsToTheSameFileUrl() {
        // [fix/fileurl-contract] Round trip in the ENCODED form: what the reverse
        // mapping publishes is the URL the holder was constructed with, not a raw
        // path (ChatScreen compares the two strings to decide holder reuse).
        val fileUrl = "file:///data/x/%E6%88%91%E7%9A%84%20%E6%8A%A5%E5%91%8A.html"
        val assetUrl = toAssetLoaderUrl(fileUrl)!!
        assertEquals(fileUrl, fromAssetLoaderUrl(assetUrl, rootDir))
        assertEquals("/data/x/我的 报告.html",
            com.rikkaminis.app.ui.chat.decodeUriPath(fileUrl.removePrefix("file://")))

        val plusUrl = "file:///data/x/a%2Bb.html"
        assertEquals(plusUrl, fromAssetLoaderUrl(toAssetLoaderUrl(plusUrl)!!, rootDir))
    }

    @Test fun escapedPlusDecodesAsLiteralPlusNotSpace() {
        // [fix/fileurl-contract] decoded to the name `a+b.html`, then published in
        // the encoded form the contract requires (`+` is unreserved for this
        // encoder, so it stays literal; only the `%2B` disappears).
        assertEquals(
            "file:///data/x/a%2Bb.html",
            fromAssetLoaderUrl("https://appassets.androidplatform.net/a%2Bb.html", rootDir),
        )
        assertEquals(
            "/data/x/a+b.html",
            com.rikkaminis.app.ui.chat.decodeUriPath(
                fromAssetLoaderUrl("https://appassets.androidplatform.net/a%2Bb.html", rootDir)!!
                    .removePrefix("file://"),
            ),
        )
    }

    @Test fun traversalAboveRootMapsToNull() {
        // The loader itself rejects such requests; the URL we expose must obey
        // the same rule instead of publishing /etc/passwd as a file:// URL.
        assertNull(
            fromAssetLoaderUrl("https://appassets.androidplatform.net/../../etc/passwd", rootDir),
        )
        assertNull(
            fromAssetLoaderUrl("https://appassets.androidplatform.net/sub/../../y.html", rootDir),
        )
    }

    // ---- differential arm: the three consumers the leak broke --------------

    // Verbatim shapes of the three downstream judgements (they live outside
    // this branch's file set, so they are modelled here):
    //   WebPreviewBottomSheet openExternalFromSheet FileProvider branch gate
    //   WebPreviewBottomSheet pin-to-home entry gate
    //   ChatScreen holder-reuse comparison
    private fun externalOpenUsesFileProviderBranch(published: String) =
        published.startsWith("file://")

    private fun pinEntryAvailable(published: String) = published.startsWith("file://")

    private fun holderReusedWithoutRebuild(published: String, requested: String) =
        published == requested

    /** New wiring: `currentUrl = fromAssetLoaderUrl(url, assetRootDir) ?: url`. */
    private fun fixedWiringPublishes(webviewReported: String): String =
        fromAssetLoaderUrl(webviewReported, rootDir) ?: webviewReported

    /** Old wiring, modelled verbatim: `currentUrl = url` (direct assign). */
    private fun baselineWiringPublishes(webviewReported: String): String = webviewReported

    @Test fun fixedWiringKeepsAllThreeDownstreamJudgementsGreen() {
        val requested = "file:///data/x/a.html"
        val webviewReports = "https://appassets.androidplatform.net/a.html"
        val published = fixedWiringPublishes(webviewReports)

        assertTrue(externalOpenUsesFileProviderBranch(published))
        assertTrue(pinEntryAvailable(published))
        assertTrue(holderReusedWithoutRebuild(published, requested))
    }

    @Test fun baselineWiringBreaksAllThreeDownstreamJudgements() {
        val requested = "file:///data/x/a.html"
        val webviewReports = "https://appassets.androidplatform.net/a.html"
        val published = baselineWiringPublishes(webviewReports)

        // This is the bug: all three consumers keyed off `file://` / the
        // requested URL and all three saw the internal origin instead.
        assertFalse(externalOpenUsesFileProviderBranch(published))
        assertFalse(pinEntryAvailable(published))
        assertFalse(holderReusedWithoutRebuild(published, requested))
    }

    @Test fun externalNavigationStillPublishesTheRealUrl() {
        // A real https:// page is not on our origin: keep the reported URL
        // (`fromAssetLoaderUrl` returns null, the elvis arm applies).
        assertEquals(
            "https://example.com/page.html",
            fixedWiringPublishes("https://example.com/page.html"),
        )
    }
}
