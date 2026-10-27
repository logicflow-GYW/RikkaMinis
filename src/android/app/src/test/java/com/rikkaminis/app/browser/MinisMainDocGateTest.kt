package com.rikkaminis.app.browser

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1016 9a] The `minis://` trust gate, pinned on the JVM.
 *
 * `MinisMainDocGate` holds what used to be a private instance method of
 * [BrowserUseManager] (plus its two private origin helpers, moved verbatim), so
 * the policy can finally be exercised without a WebView. Three arms:
 *
 *  1. **subresource** ([MinisMainDocGate.allowsSubresource]) — a committed main
 *     document is the sole authority, so a page-supplied `Origin` header can
 *     never rescue a website document (the header is attacker-controlled).
 *     Behavior pins for the code that was moved, including the loose-prefix
 *     host bug that was already tightened.
 *  2. **top-level navigation** ([MinisMainDocGate.allowsTopLevelNavigation]) —
 *     new in 9a: a website must not be able to hand the tab a `minis://`
 *     document (local-file disclosure into the tab, plus a trusted-main-document
 *     foothold if the landed file carries script).
 *  3. **source shape** — the `lastMainDocUrl` write in
 *     `shouldOverrideUrlLoading` must sit *after* the two branches that cancel
 *     the in-WebView navigation, so a URL the hook itself refuses can never be
 *     recorded as the tab's committed document.
 *
 * Expectations are literals; nothing is derived from a production constant.
 */
class MinisMainDocGateTest {

    private val gate = MinisMainDocGate

    // ── arm 1: which main documents may reference minis:// ──────────────

    @Test
    fun `trusted main documents`() {
        assertTrue("about:blank (blank-tab bootstrap)", gate.isTrustedMainDoc("about:blank"))
        assertTrue("scheme comparison is case-insensitive", gate.isTrustedMainDoc("ABOUT:BLANK"))
        assertTrue("minis:// page", gate.isTrustedMainDoc("minis://workspace/page.html"))
        assertTrue("uppercase scheme", gate.isTrustedMainDoc("MINIS://workspace/page.html"))
        assertTrue(
            "appassets page",
            gate.isTrustedMainDoc("https://appassets.androidplatform.net/index.html"),
        )
        assertTrue(
            "appassets with an explicit port",
            gate.isTrustedMainDoc("https://appassets.androidplatform.net:8443/index.html"),
        )
    }

    @Test
    fun `untrusted main documents`() {
        assertFalse("no document", gate.isTrustedMainDoc(null))
        assertFalse("blank document", gate.isTrustedMainDoc(""))
        assertFalse("whitespace document", gate.isTrustedMainDoc("   "))
        assertFalse(
            "a website main document",
            gate.isTrustedMainDoc("http://127.0.0.1:8765/attack.html"),
        )
        assertFalse("bogus origin string", gate.isTrustedMainDoc("null"))
        assertFalse("partial about:blank is not about:blank", gate.isTrustedMainDoc("about:blan"))
        // Pins the exact-host tightening: a loose `startsWith("https://appassets")`
        // prefix would wave both of these through.
        assertFalse(
            "appassets in a subdomain or a longer host is not appassets",
            gate.isTrustedMainDoc("https://appassets.evil.com/x"),
        )
        assertFalse(
            "appassets as a prefix of another host is not appassets",
            gate.isTrustedMainDoc("https://appassets.androidplatform.net.evil.com/x"),
        )
        assertFalse(
            "a minis:// URL inside a query string is not a minis:// document",
            gate.isTrustedMainDoc("https://evil.com/?next=minis://workspace/x"),
        )
        assertFalse(
            "an opaque-origin null sentinel is not about:blank",
            gate.isTrustedMainDoc("null://null"),
        )
    }

    @Test
    fun `a committed document is the sole authority for subresources`() {
        assertTrue(
            "minis:// page pulling a minis:// image (built-in preview path)",
            gate.allowsSubresource("minis://workspace/page.html", null),
        )
        assertTrue(
            "appassets page pulling a minis:// image",
            gate.allowsSubresource("https://appassets.androidplatform.net/x.html", "null"),
        )
        assertTrue(
            "fresh-tab bootstrap page",
            gate.allowsSubresource("about:blank", null),
        )
        assertFalse(
            "a website main document cannot read sandbox files",
            gate.allowsSubresource("https://evil.com/x", null),
        )
        assertFalse(
            "an Origin header cannot rescue a disallowed document",
            gate.allowsSubresource("https://evil.com/x", "minis://workspace/x"),
        )
        assertFalse(
            "nor can an appassets Origin header",
            gate.allowsSubresource("https://evil.com/x", "https://appassets.androidplatform.net"),
        )
    }

    @Test
    fun `no committed document yet falls back to the Origin header only`() {
        assertTrue("minis:// origin", gate.allowsSubresource(null, "minis://workspace/x"))
        assertTrue("uppercase minis:// origin", gate.allowsSubresource(null, "MINIS://workspace/x"))
        assertTrue(
            "appassets origin",
            gate.allowsSubresource(null, "https://appassets.androidplatform.net"),
        )
        assertTrue(
            "a blank document is still a header-arm decision",
            gate.allowsSubresource("", "minis://workspace/x"),
        )
        assertFalse("opaque-origin sentinel", gate.allowsSubresource(null, "null"))
        assertFalse("website origin", gate.allowsSubresource(null, "https://evil.com"))
        assertFalse("no document, no header", gate.allowsSubresource(null, null))
    }

    @Test
    fun `origin normalization`() {
        assertEquals("opaque sentinel maps to empty", "", gate.originOf("null"))
        assertEquals("case and path are stripped", "https://a.com", gate.originOf("HTTPS://A.com/x?y"))
        assertEquals("port is preserved", "minis://host:1234", gate.originOf("minis://HOST:1234/a/b"))
        assertEquals("non-origin text maps to empty", "", gate.originOf("workspace/x.html"))
    }

    // ── arm 2: top-level minis:// navigation (new in 9a) ────────────────

    @Test
    fun `a website may not navigate the tab to a minis document`() {
        assertFalse(
            "website page doing location.href = 'minis://…'",
            gate.allowsTopLevelNavigation("https://evil.com/page", "minis://workspace/secret.html"),
        )
        assertFalse(
            "the probe harness case (plain http document)",
            gate.allowsTopLevelNavigation("http://127.0.0.1:8765/attack.html", "minis://workspace/x.html"),
        )
        assertFalse(
            "target scheme matching is case-insensitive",
            gate.allowsTopLevelNavigation("https://evil.com/page", "MINIS://workspace/x.html"),
        )
    }

    @Test
    fun `trusted documents and document-less tabs may still navigate to minis`() {
        assertTrue(
            "no document committed yet (the app's own first load never reaches this hook)",
            gate.allowsTopLevelNavigation(null, "minis://workspace/x.html"),
        )
        assertTrue(
            "blank document",
            gate.allowsTopLevelNavigation("about:blank", "minis://workspace/x.html"),
        )
        assertTrue(
            "a minis:// page may follow another minis:// link",
            gate.allowsTopLevelNavigation("minis://workspace/a.html", "minis://workspace/b.html"),
        )
        assertTrue(
            "an appassets page may open a minis:// file",
            gate.allowsTopLevelNavigation("https://appassets.androidplatform.net/x.html", "minis://workspace/b.html"),
        )
    }

    @Test
    fun `this guard never touches non-minis targets`() {
        assertTrue(
            "ordinary navigation out of a website",
            gate.allowsTopLevelNavigation("https://evil.com/page", "https://other.example/x"),
        )
        assertTrue(
            "app-routed schemes stay with the external-scheme handler",
            gate.allowsTopLevelNavigation("https://evil.com/page", "tel:+10000000000"),
        )
        assertTrue("no target", gate.allowsTopLevelNavigation("https://evil.com/page", null))
        assertTrue("plain relative target", gate.allowsTopLevelNavigation("https://evil.com/page", "/x"))
    }

    // ── arm 3: the write must follow the cancel branches (source shape) ──

    @Test
    fun `the main-document write only happens for a navigation the hook adopts`() {
        val hook = regionAfter(
            readRepoFile(ManagerPath),
            "override fun shouldOverrideUrlLoading(",
            listOf("override fun "),
        )
        val idxWrite = hook.indexOf("lastMainDocUrl = urlStr")
        val idxAuth = hook.indexOf("GoogleAuthRouter.shouldRouteExternally(")
        val idxExternal = hook.indexOf("BrowserExternalSchemeHandler")
        assertTrue("shouldOverrideUrlLoading no longer commits the main document:\n$hook", idxWrite >= 0)
        assertTrue("the Google-auth branch marker is gone:\n$hook", idxAuth >= 0)
        assertTrue("the external-scheme branch marker is gone:\n$hook", idxExternal >= 0)
        assertTrue(
            "the main-document write must run AFTER the Google-auth cancel branch " +
                "(write@$idxWrite auth@$idxAuth) — writing first records a URL this hook " +
                "then refuses, so the page that named it keeps running with a trusted main doc",
            idxWrite > idxAuth,
        )
        assertTrue(
            "the main-document write must run AFTER the external-scheme cancel branch " +
                "(write@$idxWrite external@$idxExternal)",
            idxWrite > idxExternal,
        )
        assertTrue(
            "the write must be guarded by !handled:\n$hook",
            hook.substring(maxOf(0, idxWrite - 200), idxWrite).contains("!handled"),
        )
        assertTrue(
            "the top-level minis:// guard is gone:\n$hook",
            hook.contains("MinisMainDocGate.allowsTopLevelNavigation("),
        )
        assertTrue(
            "the guard must be decided by the COMMITTED document, not the incoming " +
                "target — passing the target would make every minis:// url trust itself:\n$hook",
            hook.contains("MinisMainDocGate.allowsTopLevelNavigation(lastMainDocUrl, urlStr)"),
        )
        // Reachability, not mere presence. `if (false && …)` — or any other term
        // spliced in ahead of the gate — keeps the call text while disabling the
        // guard, and no runtime arm can see it: those exercise the gate directly.
        // Only the source shape can pin that the gate IS the branch condition.
        assertTrue(
            "the guard must be the branch condition itself, not an unreachable term " +
                "(a dead `&&` prefix keeps the call text and drops the check):\n$hook",
            Regex(
                "if \\(request\\.isForMainFrame &&\\s*" +
                    "!MinisMainDocGate\\.allowsTopLevelNavigation\\(lastMainDocUrl, urlStr\\)",
            ).containsMatchIn(hook),
        )
        // A guard whose block does not return still lets the navigation through to
        // the fall-through path, so the refusal has to be in the block itself.
        val guardAt = hook.indexOf(
            "!MinisMainDocGate.allowsTopLevelNavigation(lastMainDocUrl, urlStr)",
        )
        val guardEnd = hook.indexOf("\n                }", guardAt)
        val guardBody = hook.substring(guardAt, if (guardEnd > 0) guardEnd else hook.length)
        assertTrue(
            "the guard must actually refuse the navigation — its block must return " +
                "true, otherwise it falls through and the page wins anyway:\n$guardBody",
            guardBody.contains("return true"),
        )
        assertFalse(
            "the guard must not read the incoming target as its trust input",
            hook.contains("allowsTopLevelNavigation(urlStr"),
        )
        assertFalse(
            "no second main-document write may be re-introduced ahead of the cancel branches",
            hook.substring(0, idxAuth).contains("lastMainDocUrl = "),
        )
    }

    @Test
    fun `the gate is a single source of truth for both arms`() {
        val src = readRepoFile(ManagerPath)
        assertTrue(
            "the subresource gate must delegate to MinisMainDocGate",
            src.contains("MinisMainDocGate.allowsSubresource(mainDocUrl, originHeader)"),
        )
        assertTrue(
            "the top-level guard must delegate to MinisMainDocGate",
            src.contains("MinisMainDocGate.allowsTopLevelNavigation(lastMainDocUrl, urlStr)"),
        )
        // Two copies of the trust predicate is exactly the drift this pins away.
        assertEquals(
            "the appassets host may only be spelled inside MinisMainDocGate",
            1,
            src.lines().count { it.contains("https://appassets.androidplatform.net\"") },
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun readRepoFile(relative: String): String {
        // Gradle (CI) sets user.dir to the module dir; a sandbox JVM run may
        // start anywhere under the repo — walk up instead of assuming a depth.
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    /** Text from the line containing [marker] up to the next [stopAt] line. */
    private fun regionAfter(src: String, marker: String, stopAt: List<String>): String {
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains(marker) }
        assertTrue("marker not found: $marker", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { i ->
            stopAt.any { lines[i].trimStart().startsWith(it) }
        } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private companion object {
        private const val ManagerPath =
            "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserUseManager.kt"
    }
}
