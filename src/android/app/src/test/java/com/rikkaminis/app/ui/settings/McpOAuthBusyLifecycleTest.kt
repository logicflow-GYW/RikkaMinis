package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WAVE-1118 D4 F-02] The OAuth authorize coroutine must lower `oauthBusy` on
 * EVERY exit, and cancellation must not be reported as a failed authorization.
 *
 * `MCPIntegrationsScreen` is Compose + Keystore + Custom Tabs, so the lifecycle
 * is pinned by source shape (the same technique `RestorePayloadBusyGateTest`
 * uses for the backup screen's gate): the arms below are exactly the properties
 * that made the old body lose the flag, so reverting any one of them reddens its
 * own arm.
 *
 * Why it matters in user terms: `Authorize` is gated on `!oauthBusy &&
 * oauthSecretReady`. A body that exits between the claim and the reset leaves the
 * button disabled with no retry, and the only escape is closing the sheet.
 */
class McpOAuthBusyLifecycleTest {

    private val screen: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/settings/MCPIntegrationsScreen.kt")
    }

    /** Comment-stripped: the fix's own prose names every verb asserted below. */
    private val code: String by lazy { withoutComments(screen) }

    private val launchBody: String by lazy { launchBlockAfterOauthClaim() }

    // -- ① the claim and its reset live in one launch --

    @Test
    fun theClaimIsSynchronous() {
        val authorizeAt = code.indexOf(".authorize(")
        assertTrue("no `.authorize(` call found (fail-closed)", authorizeAt > 0)
        val claimAt = code.lastIndexOf("oauthBusy = true", authorizeAt)
        val launchAt = code.lastIndexOf("oauthScope.launch {", authorizeAt)
        assertTrue("no `oauthBusy = true` before the authorize call", claimAt > 0)
        assertTrue(
            "the claim must be synchronous (before the launch), not inside the coroutine " +
                "where a second tap could slip past the disabled button",
            claimAt < launchAt,
        )
    }

    @Test
    fun theResetLivesOnlyInTheFinally() {
        assertEquals(
            "oauthBusy = false must exist exactly once — the finally — or an exit path can skip it",
            1,
            countOf(code, "oauthBusy = false"),
        )
        val finallyBody = blockAfter(launchBody, "finally") { it.contains("oauthBusy = false") }
        assertTrue(
            "the single reset must sit inside a finally block",
            finallyBody.contains("oauthBusy = false"),
        )
    }

    @Test
    fun theAuthorizeCallSitsInsideTheTry() {
        val tryBody = blockAfter(launchBody, "try") { it.contains(".authorize(") }
        assertTrue(
            "the Keystore write and the authorize call must be inside the guarded block",
            tryBody.contains(".authorize("),
        )
        assertTrue(
            "the Keystore write must be inside the guarded block too",
            tryBody.contains("setClientSecret("),
        )
    }

    @Test
    fun cancellationIsRethrownNotSwallowed() {
        assertTrue(
            "a cancelled authorize must rethrow (a dismissed sheet is not a failure)",
            code.contains("catch (cancelled: kotlinx.coroutines.CancellationException)") &&
                code.contains("throw cancelled"),
        )
    }

    @Test
    fun aThrownFailureIsLoggedAndSurfaced() {
        assertTrue(
            "the catch arm must log instead of crashing the app",
            code.contains("OAuth authorize for") && code.contains("AppLogger.warning"),
        )
        assertTrue(
            "the catch arm must surface the message the same way the Failed branch does",
            code.contains("errorText = t.message?.takeIf { it.isNotBlank() }"),
        )
    }

    @Test
    fun theResultHandlingRunsOnlyWhenThereIsAResult() {
        assertTrue(
            "the `when (result)` block must be after the guarded block, never inside it",
            launchBody.indexOf("if (result == null) return@launch") in 1 until
                launchBody.indexOf("when (result)"),
        )
    }

    // ---------------------------------------------------------------- helpers

    /** The body of the `oauthScope.launch { … }` that contains `.authorize(`. */
    private fun launchBlockAfterOauthClaim(): String {
        val authorizeAt = code.indexOf(".authorize(")
        assertTrue("no `.authorize(` call found (fail-closed)", authorizeAt > 0)
        val launchAt = code.lastIndexOf("oauthScope.launch {", authorizeAt)
        assertTrue("no enclosing oauthScope.launch (fail-closed)", launchAt > 0)
        return braced(code, launchAt)
    }

    /** The braced block whose opening `{` follows [keyword] in [src] and whose body
     *  satisfies [predicate] — fail-closed when no such block exists. */
    private fun blockAfter(src: String, keyword: String, predicate: (String) -> Boolean): String {
        var from = 0
        while (true) {
            val at = src.indexOf(keyword, from)
            if (at < 0) throw AssertionError("no `$keyword` block matching the predicate (fail-closed)")
            val body = braced(src, at)
            if (predicate(body)) return body
            from = at + keyword.length
        }
    }

    /** Substring from [marker] through the brace that closes the first block
     *  opened after it. */
    private fun braced(src: String, marker: Int): String {
        val open = src.indexOf('{', marker)
        assertTrue("no block opened at/after offset $marker", open >= 0)
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
        }
        throw AssertionError("unbalanced block opened at offset $open")
    }

    private fun countOf(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    private fun withoutComments(src: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    val nl = src.indexOf('\n', i)
                    i = if (nl < 0) src.length else nl
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    val end = src.indexOf("*/", i + 2)
                    i = if (end < 0) src.length else end + 2
                }
                c == '"' -> {
                    out.append(c)
                    i++
                    while (i < src.length) {
                        if (src[i] == '\\' && i + 1 < src.length) {
                            out.append(src, i, i + 2)
                            i += 2
                            continue
                        }
                        out.append(src[i])
                        if (src[i] == '"') {
                            i++
                            break
                        }
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(
            System.getProperty("user.dir") ?: error("user.dir unset"),
        )
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
