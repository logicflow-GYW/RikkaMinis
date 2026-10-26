package com.rikkaminis.app.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [da1021-F] Regression net for the WebApp asset-URL single-source fix.
 *
 * Two layers:
 *  1. Behavior on [toAssetLoaderUrl] — the pure string function that
 *     WebAppActivity now routes through instead of pasting `file.name`
 *     raw (a space/CJK name used to produce an unresolvable loader URL).
 *     Expectations are hard-coded literals, NOT derived from the
 *     function's internals, so a mutation of the encoding cannot
 *     masquerade as a pass.
 *  2. A source shape pin on `WebAppActivity.kt` — the call site must
 *     route through `toAssetLoaderUrl` and must not carry the bare
 *     paste or a second `Uri.encode` implementation (same wiring-probe
 *     approach as BoundedDecodeCallSitesTest).
 */
class WebAppAssetUrlTest {

    // --- 1. behavior: toAssetLoaderUrl ---

    @Test
    fun `encodes CJK and space file names`() {
        // 我=E68891 的=E79A84 space=20 报=E68AA5 告=E5918A — hard-coded, not derived.
        assertEquals(
            "https://appassets.androidplatform.net/%E6%88%91%E7%9A%84%20%E6%8A%A5%E5%91%8A.html",
            toAssetLoaderUrl("file:///data/user/0/app/sessions/s1/attachments/x/我的 报告.html"),
        )
    }

    @Test
    fun `encodes reserved URL characters`() {
        assertEquals(
            "https://appassets.androidplatform.net/a%23b%3Fc%25d.html",
            toAssetLoaderUrl("file:///srv/html/a#b?c%d.html"),
        )
    }

    @Test
    fun `leaves ASCII unreserved names identical`() {
        assertEquals(
            "https://appassets.androidplatform.net/page-1.2_3~x.html",
            toAssetLoaderUrl("file:///srv/html/page-1.2_3~x.html"),
        )
    }

    @Test
    fun `returns null for non-file URLs and empty names`() {
        assertNull(toAssetLoaderUrl("https://example.com/x.html"))
        assertNull(toAssetLoaderUrl("content://media/external/1"))
        assertNull(toAssetLoaderUrl("file://"))
        assertNull(toAssetLoaderUrl("file:///dir/"))
    }

    @Test
    fun `round trips through fromAssetLoaderUrl`() {
        val root = File.createTempFile("webapp-root", ".d")
        root.delete()
        assertTrue(root.mkdirs())
        val html = File(root, "我的 报告.html").apply { writeText("<html></html>") }
        // [fix/fileurl-contract] The URL that goes in is the ENCODED form
        // (`fileUrlFor`), and what comes back out is byte-identical to it.
        val requested = com.rikkaminis.app.ui.sandbox.fileUrlFor(html)
        val url = toAssetLoaderUrl(requested)
        assertEquals(requested, fromAssetLoaderUrl(url!!, root.absolutePath))
        // and the consumer decodes back to the real path
        assertEquals(
            html.absolutePath,
            com.rikkaminis.app.ui.chat.decodeUriPath(
                requireNotNull(fromAssetLoaderUrl(url, root.absolutePath)).removePrefix("file://"),
            ),
        )
        root.deleteRecursively()
    }

    // --- 2. wiring: WebAppActivity must route through the single source ---

    @Test
    fun `WebAppActivity routes asset URLs through toAssetLoaderUrl`() {
        val src = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/webapp/WebAppActivity.kt",
        )
        assertTrue(
            "the WebApp loader URL must be built by the shared single source",
            src.contains("toAssetLoaderUrl(fileUrlFor(file))"),
        )
        // Pre-fix shape, restored verbatim, must be gone.
        assertFalse(
            "the bare file.name paste must not come back",
            src.contains("https://\$ASSET_LOADER_HOST/\${file.name}"),
        )
        // No second *asset path* encoding implementation: the encoding comes
        // from ui/preview/WebViewHolder.kt only. (A Uri.encode on the
        // minis:// session deep link is a pre-existing legitimate use.)
        assertFalse(
            "a second Uri.encode on the file path must not be introduced",
            src.contains("Uri.encode(file."),
        )
        assertFalse(
            "a second URLEncoder implementation must not be introduced",
            src.contains("URLEncoder.encode("),
        )
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]. Gradle sets user.dir to the module dir; a sandbox JVM run
     * can start anywhere under the repo — no fixed depth works.
     */
    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }
}
