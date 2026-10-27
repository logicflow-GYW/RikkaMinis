package com.rikkaminis.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [da1021-H] Pins the scheme gate on MinisOpenUrlBroker's two entry points.
 *
 * Why behaviour tests only cover the classifier: `android.net.Uri` is a
 * private-constructed abstract class and the sandbox JVM cannot materialise
 * an instance (with `isReturnDefaultValues = true` the stub's `Uri.parse`
 * returns null for every input, which makes an `offer(String)` behavioural
 * test a false green — the `?: return` fires before the gate regardless).
 * The whitelist classifier itself IS a pure string function, so it gets a
 * real behavioural test; the WIRING of the gate into `offer(uri: Uri)` is
 * pinned by the production-text probe below, with the pre-fix text as the
 * negative control (that is what makes the pin falsifiable rather than
 * decorative — see BoundedDecodeCallSitesTest for the same pattern).
 */
class MinisOpenUrlBrokerSchemeTest {

    private companion object {
        const val BROKER =
            "src/main/java/com/rikkaminis/app/terminal/MinisOpenUrlBroker.kt"
    }

    // ---------------------------------------------------------- behaviour ---

    @Test
    fun `isSupportedScheme accepts exactly the four routable schemes`() {
        assertEquals(true, MinisOpenUrlBroker.isSupportedScheme("http"))
        assertEquals(true, MinisOpenUrlBroker.isSupportedScheme("https"))
        assertEquals(true, MinisOpenUrlBroker.isSupportedScheme("about"))
        assertEquals(true, MinisOpenUrlBroker.isSupportedScheme("minis"))
    }

    @Test
    fun `isSupportedScheme is case-insensitive`() {
        assertEquals(true, MinisOpenUrlBroker.isSupportedScheme("HTTP"))
        assertEquals(true, MinisOpenUrlBroker.isSupportedScheme("HTTPS"))
        assertEquals(true, MinisOpenUrlBroker.isSupportedScheme("Minis"))
        assertEquals(true, MinisOpenUrlBroker.isSupportedScheme("About"))
    }

    @Test
    fun `isSupportedScheme refuses non-routable schemes fail-closed`() {
        // The payloads the audit-0917 comment named, plus the usual suspects.
        assertEquals(false, MinisOpenUrlBroker.isSupportedScheme("file"))
        assertEquals(false, MinisOpenUrlBroker.isSupportedScheme("javascript"))
        assertEquals(false, MinisOpenUrlBroker.isSupportedScheme("content"))
        assertEquals(false, MinisOpenUrlBroker.isSupportedScheme("data"))
        assertEquals(false, MinisOpenUrlBroker.isSupportedScheme("ftp"))
        assertEquals(false, MinisOpenUrlBroker.isSupportedScheme(""))
        assertEquals(false, MinisOpenUrlBroker.isSupportedScheme(null))
        // Near-misses must not ride in on a prefix match.
        assertEquals(false, MinisOpenUrlBroker.isSupportedScheme("minisx"))
        assertEquals(false, MinisOpenUrlBroker.isSupportedScheme("httpx"))
    }

    // ------------------------------------------------- production-text pin ---

    @Test
    fun `offer(Uri) consults the scheme gate before publishing`() {
        val src = readRepoFile(BROKER)
        val body = offerUriBody(src)

        assertTrue(
            "offer(uri: Uri) must consult isSupportedScheme (a bare publish revert is the audit-0917 half-fix)",
            body.contains("isSupportedScheme("),
        )
        val gate = body.indexOf("isSupportedScheme(")
        val publish = body.indexOf("_pendingUrl.value = uri")
        assertTrue("offer(uri: Uri) must publish to _pendingUrl", publish >= 0)
        assertTrue(
            "the gate must precede the publish (order mutation publishes unvetted URIs)",
            gate in 0 until publish,
        )

        // Negative control: the pre-fix bare publish (verbatim) must be RED.
        assertProbeRejects(src) {
            offerUriBody(it.replace(
                "if (!isSupportedScheme(uri.scheme)) return",
                "// gate removed",
            )).let { body -> body.contains("isSupportedScheme(") }
        }
    }

    @Test
    fun `both entry points share a single gate and a single publish`() {
        val src = readRepoFile(BROKER)
        val bodyStr = offerStringBody(src)

        // Funnel: the String overload either gates inline or delegates.
        assertTrue(
            "offer(String) must funnel through the shared gate (delegate to offer(parsed) or gate on parsed.scheme)",
            bodyStr.contains("offer(parsed)") ||
                bodyStr.contains("isSupportedScheme(parsed.scheme)"),
        )
        // Exactly one non-null publish point in the whole file — a second
        // inline copy is the next drift site (same-family asymmetry).
        val writes = Regex("_pendingUrl\\.value\\s*=\\s*(?=[^n ]|n[^u]|nu[^l]|nul[^l])")
            .findAll(src)
            .count()
        assertEquals(
            "exactly one non-null _pendingUrl.value publish point expected",
            1,
            writes,
        )
    }

    // ------------------------------------------------------------- helpers ---

    private fun offerUriBody(src: String): String {
        val start = src.indexOf("fun offer(uri: Uri)")
        val end = src.indexOf("fun offer(uriString: String)")
        assertTrue("offer(uri: Uri) declaration missing", start >= 0)
        assertTrue("offer(String) declaration missing (slice anchor)", end > start)
        return src.substring(start, end)
    }

    private fun offerStringBody(src: String): String {
        val start = src.indexOf("fun offer(uriString: String)")
        val end = src.indexOf("fun consume()")
        assertTrue("offer(String) declaration missing", start >= 0)
        assertTrue("consume() declaration missing (slice anchor)", end > start)
        return src.substring(start, end)
    }

    private inline fun assertProbeRejects(src: String, probe: (String) -> Boolean) {
        assertFalse(
            "the negative control (pre-fix text restored verbatim) must make the probe fail",
            probe(src),
        )
    }

    /**
     * Walk up from the test working directory to the module dir containing
     * [relative]. Gradle sets user.dir to the module dir; a sandbox JVM run
     * can start anywhere under the repo — no fixed depth works.
     */
    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }
}
