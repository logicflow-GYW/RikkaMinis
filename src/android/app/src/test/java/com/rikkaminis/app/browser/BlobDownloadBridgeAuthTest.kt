package com.rikkaminis.app.browser

import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SURGERY-C02] The blob-download bridge contract: ONE credential for BOTH
 * terminals (success and failure), and no business state for a call that does
 * not own it.
 *
 * The defect: `blobDownloadError(error)` — the failure hook the injected
 * FileReader calls — carried no token at all, so any page holding this WebView
 * (iframes included, since `addJavascriptInterface` reaches every frame) could
 * call it and mint a FAILED row in the downloads panel plus a native
 * "Download failed: …" chat notice, with attacker-authored text, unbounded in
 * count and length. The success callback did require a token, but it compared
 * and cleared in two unsynchronised steps.
 *
 * Evidence split (a WebView cannot exist on the JVM):
 *
 *  1. **[BlobDownloadTokenSlot]** — the real state machine, exercised directly:
 *     single use, replacement, expiry, replay, wrong-token safety, concurrent
 *     claims, and success/error mutual exclusion.
 *  2. **[boundedBlobFailureReason]** — the real sanitizer/bounder.
 *  3. **[blobDownloadFetchScript]** — the REAL generator: the two error call
 *     sites are asserted on its output (`BlobDownloadBridgeAuthTest`'s JVM run
 *     is paired with a node execution of the same script in the branch's
 *     probe, because "the string contains the token" and "the JS actually
 *     passes it at runtime" are different claims).
 *  4. **source shapes** — which member claims what, and that the refusal path
 *     returns BEFORE the reporting funnel.
 */
class BlobDownloadBridgeAuthTest {

    // ── arm 1: the credential state machine ─────────────────────────────────

    @Test
    fun `nothing armed means nothing can be answered`() {
        val slot = BlobDownloadTokenSlot()
        assertEquals(
            "an unarmed bridge must refuse — that is the whole C02 hole",
            BlobDownloadTokenSlot.Claim.NO_TOKEN_ARMED,
            slot.claim("anything", 120_000L),
        )
        assertNull("an unarmed slot holds no credential", slot.activeToken)
    }

    @Test
    fun `a valid credential is consumed by the first answer`() {
        val slot = BlobDownloadTokenSlot()
        slot.arm("tok-1")
        assertEquals(BlobDownloadTokenSlot.Claim.MATCH, slot.claim("tok-1", 120_000L))
        assertNull("one-shot: the credential dies with the call it authorised", slot.activeToken)
        assertEquals(
            "a replay of the same value after the answer must be refused",
            BlobDownloadTokenSlot.Claim.NO_TOKEN_ARMED,
            slot.claim("tok-1", 120_000L),
        )
    }

    @Test
    fun `an empty token never matches a real credential`() {
        val slot = BlobDownloadTokenSlot()
        slot.arm("tok-1")
        assertEquals(
            "an empty String must not be able to win by accident",
            BlobDownloadTokenSlot.Claim.NOT_CURRENT,
            slot.claim("", 120_000L),
        )
        assertEquals("…and must not disarm the pending request", "tok-1", slot.activeToken)
    }

    @Test
    fun `a wrong credential leaves the real request alone`() {
        val slot = BlobDownloadTokenSlot()
        slot.arm("tok-1")
        assertEquals(
            BlobDownloadTokenSlot.Claim.NOT_CURRENT,
            slot.claim("attacker-guess", 120_000L),
        )
        assertEquals(
            "a page racing garbage at the bridge must not knock out the download",
            "tok-1",
            slot.activeToken,
        )
        assertEquals(
            "the legitimate answer still lands afterwards",
            BlobDownloadTokenSlot.Claim.MATCH,
            slot.claim("tok-1", 120_000L),
        )
    }

    @Test
    fun `a newer request replaces the older credential`() {
        val slot = BlobDownloadTokenSlot()
        slot.arm("first")
        slot.arm("second")
        assertEquals(
            "the old credential cannot settle the replacement's request",
            BlobDownloadTokenSlot.Claim.NOT_CURRENT,
            slot.claim("first", 120_000L),
        )
        assertEquals(
            "…and the newer request is still servable",
            BlobDownloadTokenSlot.Claim.MATCH,
            slot.claim("second", 120_000L),
        )
    }

    @Test
    fun `an armed request older than the window is refused and disarmed`() {
        var now = 1_000L
        val slot = BlobDownloadTokenSlot(nowMs = { now })
        slot.arm("tok-1")
        now += 120_001L
        assertEquals(
            "armed but never answered must not stay replayable",
            BlobDownloadTokenSlot.Claim.EXPIRED,
            slot.claim("tok-1", 120_000L),
        )
        assertNull("the stale credential is consumed, not kept", slot.activeToken)
        assertEquals(
            "…so a second attempt is a plain replay",
            BlobDownloadTokenSlot.Claim.NO_TOKEN_ARMED,
            slot.claim("tok-1", 120_000L),
        )
    }

    @Test
    fun `a request answered inside the window still lands`() {
        var now = 5_000L
        val slot = BlobDownloadTokenSlot(nowMs = { now })
        slot.arm("tok-1")
        now += 119_999L
        assertEquals(
            "the window must not cut a real FileReader read",
            BlobDownloadTokenSlot.Claim.MATCH,
            slot.claim("tok-1", 120_000L),
        )
    }

    @Test
    fun `success and error cannot both be the terminal for one download`() {
        // success first, then the late error hook
        val a = BlobDownloadTokenSlot()
        a.arm("tok")
        assertEquals(BlobDownloadTokenSlot.Claim.MATCH, a.claim("tok", 120_000L))
        assertEquals(
            "a late error after the bytes landed must be diagnostic only",
            BlobDownloadTokenSlot.Claim.NO_TOKEN_ARMED,
            a.claim("tok", 120_000L),
        )

        // error first, then the late success hook
        val b = BlobDownloadTokenSlot()
        b.arm("tok")
        assertEquals(BlobDownloadTokenSlot.Claim.MATCH, b.claim("tok", 120_000L))
        assertEquals(
            "a late success after the failure was reported must be diagnostic only",
            BlobDownloadTokenSlot.Claim.NO_TOKEN_ARMED,
            b.claim("tok", 120_000L),
        )
    }

    @Test
    fun `exactly one of many concurrent claims wins`() {
        // The pre-fix success path compared `token != blobDownloadActiveToken`
        // and then cleared in a separate step, so two JavaBridge callbacks could
        // both pass the check. The compare-and-consume must be one operation.
        val threads = 8
        repeat(50) {
            val slot = BlobDownloadTokenSlot()
            slot.arm("tok-race")
            val start = CountDownLatch(1)
            val done = CountDownLatch(threads)
            val matches = AtomicInteger(0)
            val workers = (0 until threads).map {
                Thread {
                    start.await()
                    if (slot.claim("tok-race", 120_000L) == BlobDownloadTokenSlot.Claim.MATCH) {
                        matches.incrementAndGet()
                    }
                    done.countDown()
                }
            }
            workers.forEach { it.start() }
            start.countDown()
            done.await()
            assertEquals("one credential must authorise exactly one answer", 1, matches.get())
        }
    }

    @Test
    fun `re-arming null disarms the slot`() {
        val slot = BlobDownloadTokenSlot()
        slot.arm("tok")
        slot.arm(null)
        assertEquals(
            "the teardown path (destroy) must leave nothing replayable",
            BlobDownloadTokenSlot.Claim.NO_TOKEN_ARMED,
            slot.claim("tok", 120_000L),
        )
    }

    @Test
    fun `a destroyed tab does not re-arm on a late request`() {
        val slot = BlobDownloadTokenSlot()
        slot.arm("before-teardown")
        slot.close()
        assertEquals(BlobDownloadTokenSlot.Claim.NO_TOKEN_ARMED, slot.claim("before-teardown", 120_000L))
        slot.arm("after-teardown")
        assertNull(slot.activeToken)
        assertEquals(BlobDownloadTokenSlot.Claim.NO_TOKEN_ARMED, slot.claim("after-teardown", 120_000L))
    }

    @Test
    fun `negative elapsed time does not extend a download credential`() {
        var now = 10_000L
        val slot = BlobDownloadTokenSlot(nowMs = { now })
        slot.arm("token")
        now = 9_999L
        assertEquals(BlobDownloadTokenSlot.Claim.EXPIRED, slot.claim("token", 120_000L))
    }

    // ── arm 2: the reason bound ─────────────────────────────────────────────

    @Test
    fun `a page-supplied reason is bounded and its control characters collapsed`() {
        val huge = "x".repeat(4_096)
        val bounded = boundedBlobFailureReason(huge, "fallback")
        assertEquals("the ellipsis is included in the bound", 240, bounded.length)
        assertEquals(
            "a character crossing the cutoff stays whole",
            "x".repeat(238) + "…",
            boundedBlobFailureReason("x".repeat(238) + "🙂rest", "fallback"),
        )
        assertTrue("the truncation is visible: $bounded", bounded.endsWith("…"))

        assertEquals(
            "newlines/tabs must not survive into a chat notice (line forging)",
            "a b c",
            boundedBlobFailureReason("a\n\nb\tc", "fallback"),
        )
        assertEquals(
            "a blank reason keeps the caller's own wording",
            "fallback",
            boundedBlobFailureReason(" \n\t ", "fallback"),
        )
        assertEquals(
            "a short reason is passed through untouched",
            "FileReader error",
            boundedBlobFailureReason("FileReader error", "fallback"),
        )
    }

    // ── arm 3: the real injected script ────────────────────────────────────

    @Test
    fun `both error callbacks carry the credential`() {
        val js = blobDownloadFetchScript(
            blobUrl = "blob:https://example.com/abc",
            token = "tok-123",
            filename = "report.pdf",
        )
        assertEquals(
            "the generator must call the error hook twice (FileReader + fetch)",
            2,
            Regex("blobDownloadError\\(").findAll(js).count(),
        )
        val errCalls = Regex("blobDownloadError\\(([^)]*)\\)").findAll(js).map { it.groupValues[1] }.toList()
        for (args in errCalls) {
            assertTrue(
                "post-fix shape: token first, reason second — got `$args`",
                args.startsWith("\"tok-123\", "),
            )
        }
        assertTrue(
            "the success callback keeps its credential too:\n$js",
            js.contains("saveBlobDownload(\"tok-123\", reader.result"),
        )
        assertTrue("the fetch still targets the blob URL:\n$js", js.contains("\"blob:https://example.com/abc\""))
        assertTrue("load, not loadend, is the success event", js.contains("reader.onload ="))
        assertFalse("loadend runs after failures too", js.contains("reader.onloadend"))
        assertTrue("FileReader abort also settles", js.contains("reader.onabort = reader.onerror"))
    }

    @Test
    fun `the generated script survives a hostile filename and token`() {
        val js = blobDownloadFetchScript(
            blobUrl = "blob:https://x/y\"z",
            token = "tok\"quote",
            filename = "a\"b\nc.pdf",
        )
        assertTrue(
            "the filename is JSON-escaped, not spliced raw (a newline would end the literal):\n$js",
            js.contains("a\\\"b\\nc.pdf"),
        )
        assertTrue(
            "the token is JSON-escaped too — it is interpolated into the same literals:\n$js",
            js.contains("tok\\\"quote"),
        )
    }

    // ── arm 4: which member claims, and where the funnel sits ──────────────

    @Test
    fun `the failure bridge takes a token and refuses before reporting`() {
        val src = codeOnly(manager())
        assertTrue(
            "the signature must take the credential as its first parameter",
            src.contains("fun blobDownloadError(token: String, error: String)"),
        )
        val body = funBody(src, "fun blobDownloadError(token: String")
        assertTrue("the member must claim through the shared slot:\n$body", body.contains("blobDownloadSlot.claim("))
        val refusal = body.indexOf("BlobDownloadTokenSlot.Claim.MATCH")
        val report = body.indexOf("reportBlobFailure(")
        assertTrue("the match test must be present:\n$body", refusal >= 0)
        assertTrue("the authenticated failure must still reach the panel funnel:\n$body", report >= 0)
        assertTrue(
            "the refusal must RETURN before the funnel — otherwise a page without the " +
                "credential still writes business state (the C02 hole):\n$body",
            body.indexOf("return") in (refusal + 1)..(report - 1),
        )
        assertEquals(
            "exactly one claim per call — a second one would consume someone else's answer",
            1,
            Regex("blobDownloadSlot\\.claim\\(").findAll(body).count(),
        )
    }

    @Test
    fun `the success bridge claims atomically instead of comparing a field`() {
        val body = codeOnly(funBody(codeOnly(manager()), "fun saveBlobDownload("))
        assertTrue(
            "success and failure must share ONE credential source:\n$body",
            body.contains("blobDownloadSlot.claim("),
        )
        assertFalse(
            "the old `token != blobDownloadActiveToken` compare-then-clear is not atomic:\n$body",
            body.contains("token != blobDownloadActiveToken"),
        )
        assertFalse(
            "a bare clear would race a concurrent claim:\n$body",
            body.contains("blobDownloadActiveToken = null"),
        )
        assertEquals(
            "one claim per call",
            1,
            Regex("blobDownloadSlot\\.claim\\(").findAll(body).count(),
        )
    }

    @Test
    fun `the injection arms the credential behind the latch and uses the generator`() {
        val body = codeOnly(funBody(codeOnly(manager()), "private fun fetchBlobDownload("))
        val latch = body.indexOf("if (destroyed)")
        val arm = body.indexOf("blobDownloadActiveToken = token")
        assertTrue("a dispatch the latch refuses must not arm a credential", latch in 0 until arm)
        assertTrue(
            "the injected script must come from the testable generator:\n$body",
            body.contains("blobDownloadFetchScript(blobUrl, token, guessedName)"),
        )
        assertFalse(
            "an inline copy would drift from the generator the JS proof runs:\n$body",
            body.contains("__minis__.blobDownloadError("),
        )
    }

    @Test
    fun `the slot is the only owner and teardown disarms it`() {
        val src = codeOnly(manager())
        assertEquals(
            "exactly one slot instance — two would split the credential",
            1,
            Regex("private val blobDownloadSlot\\s*=\\s*BlobDownloadTokenSlot\\(").findAll(src).count(),
        )
        val destroy = codeOnly(funBody(src, "fun destroy()"))
        val close = destroy.indexOf("blobDownloadSlot.close()")
        assertTrue(
            "close the slot before platform teardown that may fail:\n$destroy",
            close in 0 until destroy.indexOf("try {"),
        )
        assertTrue(
            "the bridge registration itself is unchanged (both members on __minis__)",
            src.contains("addJavascriptInterface(jsBridge, \"__minis__\")"),
        )
        assertNotNull(
            "the facade property the lifecycle gate anchors on must still exist",
            Regex("blobDownloadActiveToken: String\\?").find(src),
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun manager(): String = readRepoFile(ManagerPath)

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    /** Comments blanked: a KDoc that *names* a shape is not that shape. */
    private fun codeOnly(src: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < src.length) {
            if (src.startsWith("//", i)) {
                val j = src.indexOf('\n', i)
                i = if (j < 0) src.length else j
                continue
            }
            if (src.startsWith("/*", i)) {
                val j = src.indexOf("*/", i + 2)
                i = if (j < 0) src.length else j + 2
                continue
            }
            out.append(src[i])
            i++
        }
        return out.toString()
    }

    /**
     * The body of the declaration whose signature contains [marker].
     *
     * Brace-balanced with string literals skipped (double-quoted, including
     * escapes, and raw `"""` blocks) — a `{` inside the injected JS or a reason
     * string must not end the body early, and a character window is out of the
     * question (two earlier waves shipped criteria that went red on a correct
     * edit because an added comment pushed the anchor past the window).
     */
    private fun funBody(src: String, marker: String): String {
        val at = src.indexOf(marker)
        assertTrue("marker not found: $marker", at >= 0)
        var i = src.indexOf('{', at)
        assertTrue("no block body after $marker", i >= 0)
        val start = i
        var depth = 0
        while (i < src.length) {
            val ch = src[i]
            when {
                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3)
                    i = if (end < 0) src.length else end + 3
                    continue
                }
                ch == '"' -> {
                    i++
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\\') i++
                        i++
                    }
                }
                ch == '\'' -> {
                    i++
                    while (i < src.length && src[i] != '\'') {
                        if (src[i] == '\\') i++
                        i++
                    }
                }
                ch == '{' -> depth++
                ch == '}' -> {
                    depth--
                    if (depth == 0) return src.substring(start, i + 1)
                }
            }
            i++
        }
        throw IllegalStateException("unbalanced body for $marker")
    }

    private companion object {
        private const val ManagerPath =
            "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserUseManager.kt"
    }
}
