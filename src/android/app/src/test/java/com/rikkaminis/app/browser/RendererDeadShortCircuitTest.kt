package com.rikkaminis.app.browser

import java.io.File
import java.io.FileNotFoundException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [r5-F F-F-01 / F-C-01b] The dead-renderer bypass, and the two download
 * failures that only reached logcat.
 *
 * F-F-01 — once `onRendererGone` has fired, this instance can never report
 * back: the bridge lives in the dead renderer and the system fires the death
 * callback ONCE. `navigate()`, `captureWebViewBitmap()` and
 * `evaluateJavascript()` were taught to short-circuit back in r3-B09; four
 * entries were not, and each of them arms a waiter that no callback can ever
 * complete (`asyncJsSlot` for the two async-JS paths, `navigationDeferred` for
 * the two refreshes), so the agent got a 30s/60s timeout — or a fake
 * TIMED_OUT — instead of "this tab is gone".
 *
 * The guard has to sit BEFORE the registration, not before the await: a
 * request begun after the teardown has no completer at all (proved by
 * `AsyncJsSlotTeardownTest`'s slot semantics + the arm-1 truth table below).
 *
 * F-C-01b — `startUrlDownload` without a bound session, and `saveBlobDownload`
 * with an unusable filename, each dropped the download with a `Log.w`: no
 * panel row, no chat notice. The sibling no-session branch in
 * `saveBlobDownload` already went through `reportDownloadFailure` (r3-B09
 * F-10), so this pins that all three now flow through that one funnel.
 *
 * A WebView cannot be instantiated on the JVM, so the evidence is split:
 *
 *  1. **pure decisions** ([deadTabGoneMessage] / [deadTabAsyncJsAnswer]) —
 *     real objects at the top level of `BrowserUseManager.kt`, expectations
 *     written as literals;
 *  2. **wiring shapes** — asserted on the real sources, with the enclosing
 *     declaration body extracted by brace balancing (never a character
 *     window: two earlier waves shipped criteria that went red on a *correct*
 *     edit because an added comment pushed the anchor past the window).
 */
class RendererDeadShortCircuitTest {

    // ── arm 1: the pure dead-tab decisions ─────────────────────────────────

    @Test
    fun `a destroyed tab tells the agent to pick another tab`() {
        assertEquals(
            "This tab's WebView was already destroyed (the tab was closed or evicted) — " +
                "pick another tab or open a new one.",
            deadTabGoneMessage(destroyed = true),
        )
    }

    @Test
    fun `a renderer-killed tab tells the agent this tab is done`() {
        assertEquals(
            "This tab's WebView renderer was killed by the system; this tab can no longer " +
                "load pages — open a new tab.",
            deadTabGoneMessage(destroyed = false),
        )
    }

    @Test
    fun `a live tab is not short-circuited`() {
        assertNull(
            "deadTabAsyncJsAnswer's null is the ONE signal that means 'proceed' — a " +
                "refusal on a live tab would break every execute_js / fetch",
            deadTabAsyncJsAnswer(destroyed = false, rendererDead = false),
        )
    }

    @Test
    fun `the async-JS answer is the bridge reject shape`() {
        // `fetch` parses a bare (non-JSON) payload as a SUCCESS (its "not JSON"
        // fallback), so the dead-tab answer must stay JSON — the same
        // `{"error":…}` shape onRendererGone/destroy complete an in-flight
        // waiter with.
        //
        // The comparison goes through a parse, not a string match: JSONObject.quote
        // escapes the em dash as `\u2014` on the JVM reference implementation and
        // leaves it raw on Android's libcore, so an expected literal carrying the
        // wire text would pin an encoding accident instead of the contract. The
        // MESSAGE itself is pinned verbatim by the two tests above.
        val killed = deadTabAsyncJsAnswer(destroyed = false, rendererDead = true)
        assertNotNull(killed)
        assertTrue("must be the bridge reject shape:\n$killed", killed!!.startsWith("{\"error\":"))
        assertEquals(
            "This tab's WebView renderer was killed by the system; this tab can no longer " +
                "load pages — open a new tab.",
            JSONObject(killed).getString("error"),
        )
        val destroyed = deadTabAsyncJsAnswer(destroyed = true, rendererDead = false)
        assertNotNull(destroyed)
        assertEquals(
            "This tab's WebView was already destroyed (the tab was closed or evicted) — " +
                "pick another tab or open a new one.",
            JSONObject(destroyed!!).getString("error"),
        )
    }

    @Test
    fun `either dead cause refuses, and the two causes stay distinguishable`() {
        // `webViewInert()` is the disjunction; a short-circuit that only looked
        // at one flag would still hang for the other cause.
        for (destroyed in listOf(false, true)) {
            for (rendererDead in listOf(false, true)) {
                val answer = deadTabAsyncJsAnswer(destroyed, rendererDead)
                if (!destroyed && !rendererDead) {
                    assertNull("a live tab must proceed", answer)
                } else {
                    assertNotNull("$destroyed/$rendererDead must refuse", answer)
                    assertTrue("the answer must be JSON", answer!!.startsWith("{\"error\":"))
                    assertTrue(
                        "both causes must map to one of the two messages",
                        answer.contains("\"") && answer.endsWith("\"}"),
                    )
                }
            }
        }
        assertFalse(
            "destroyed and renderer-dead must not collapse onto the same wording — the " +
                "caller's fix differs (pick another tab vs open a new one)",
            deadTabGoneMessage(true) == deadTabGoneMessage(false),
        )
    }

    // ── arm 2: the wiring ──────────────────────────────────────────────────

    @Test
    fun `no async-JS slot is registered on a dead tab`() {
        val src = codeOnly(manager())
        for ((label, decl) in listOf(
            "executeJS" to "private suspend fun executeJS(",
            "awaitPromiseJs" to "private suspend fun awaitPromiseJs(",
        )) {
            val body = bodyOf(src, decl)
            val guard = body.indexOf("if (webViewInert())")
            val asyncGuard = body.indexOf("deadTabAsyncJsAnswer(destroyed, rendererDead)")
            val arm = body.indexOf("beginAsyncJsRequest()")
            val dispatch = body.indexOf("webView.evaluateJavascript(")
            assertTrue(
                "$label: no dead-tab guard at all — a slot armed on an inert tab has no " +
                    "completer and burns the full timeout:\n$body",
                guard >= 0 || asyncGuard >= 0,
            )
            assertTrue("$label: the slot registration is gone?:\n$body", arm >= 0)
            assertTrue(
                "$label: the guard must sit BEFORE beginAsyncJsRequest() — guarding only " +
                    "the await still leaks the slot:\n$body",
                (guard >= 0 && guard < arm) || (asyncGuard >= 0 && asyncGuard < arm),
            )
            assertTrue(
                "$label: the guard must sit before the JS dispatch too:\n$body",
                dispatch >= 0 && (guard in 0 until dispatch || asyncGuard in 0 until dispatch),
            )
        }
    }

    @Test
    fun `both best-effort refreshes leave before arming a navigation waiter`() {
        val src = codeOnly(manager())
        for ((label, decl) in listOf(
            "reloadAndWait" to "suspend fun reloadAndWait(",
            "loadBlankPage" to "suspend fun loadBlankPage(",
        )) {
            val body = bodyOf(src, decl)
            val guard = body.indexOf("if (webViewInert())")
            val arm = body.indexOf("navigationDeferred = deferred")
            val loading = body.indexOf("_isLoading.value = true")
            assertTrue("$label: no inert guard:\n$body", guard >= 0)
            assertTrue(
                "$label: the guard must precede `navigationDeferred = deferred` — a " +
                    "deferred armed on a dead tab only completes by timing out:\n$body",
                arm >= 0 && guard < arm,
            )
            assertTrue(
                "$label: the guard must precede the loading flag — claiming to be loading " +
                    "on a tab that can never finish is the fake-success shape:\n$body",
                loading >= 0 && guard < loading,
            )
            // Early exit, never a fabricated completion.
            val guardBlock = ifBlockOf(body, guard)
            assertTrue("$label: the guard must return (early exit)", guardBlock.contains("return"))
            assertTrue(
                "$label: the early exit must be observable — the dead-tab drop is logged",
                guardBlock.contains("Log.w(TAG,"),
            )
            assertFalse(
                "$label: the short-circuit must NOT complete the deferred — 'the reload " +
                    "happened' is exactly the claim this path cannot make:\n$guardBlock",
                guardBlock.contains("deferred.complete("),
            )
        }
    }

    @Test
    fun `the dead-tab answers come from one wording`() {
        val src = manager()
        val result = bodyOf(codeOnly(src), "private fun webViewGoneResult(")
        assertTrue(
            "webViewGoneResult must answer with the shared message — otherwise `fetch` and " +
                "`navigate` describe the same dead tab differently:\n$result",
            result.contains("deadTabGoneMessage(destroyed)"),
        )
        val answer = bodyOf(codeOnly(src), "internal fun deadTabAsyncJsAnswer(")
        assertTrue(
            "the async-JS reject shape must carry the same message:\n$answer",
            answer.contains("deadTabGoneMessage(destroyed)"),
        )
        assertTrue(
            "both causes refuse (the same disjunction as webViewInert):\n$answer",
            answer.contains("if (destroyed || rendererDead)"),
        )
    }

    @Test
    fun `both rejected downloads reach the panel funnel`() {
        val pool = codeOnly(readRepoFile(TabPoolPath))
        val url = bodyOf(pool, "internal fun startUrlDownload(")
        val noSession = branchOf(url, "sessionWorkspaceDir() ?: run")
        assertTrue(
            "a URL download with no bound session must reach reportDownloadFailure — a " +
                "log line leaves the panel empty and the user with nothing to act on:\n" +
                noSession,
            noSession.contains("reportDownloadFailure(") &&
                noSession.contains("\"no session is bound to this browser pool\""),
        )

        val blob = bodyOf(pool, "internal fun saveBlobDownload(")
        val unsafe = branchOf(blob, "sanitizeDownloadName(filename) ?: run")
        assertTrue(
            "an unusable blob filename must reach the same funnel:\n$unsafe",
            unsafe.contains("reportDownloadFailure(") &&
                unsafe.contains("\"unsafe download filename\""),
        )
        val blobNoSession = branchOf(blob, "sessionWorkspaceDir() ?: run")
        assertTrue(
            "control: the sibling no-session branch (r3-B09 F-10) still reports",
            blobNoSession.contains("reportDownloadFailure("),
        )

        val funnel = bodyOf(pool, "internal fun reportDownloadFailure(")
        assertTrue("the funnel must produce a visible FAILED row", funnel.contains("DownloadState.FAILED"))
        assertTrue("with no destination to open", funnel.contains("destination = null"))
        assertTrue(
            "and the same chat notice the streaming failure path posts",
            funnel.contains("onDownloadEvent?.invoke(\"Download failed:"),
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
     * The declaration's own body — the indent domain that OWNS the calls
     * inside it. Deliberately not a character window: an added comment between
     * a guard and its anchor must not change the verdict.
     *
     * Handles both body shapes: a block body (brace-balanced from its `{`) and
     * an expression body (`= if (…) { … } else { … }`, which is the shape of
     * the three pure decisions; slicing at the first `{` would drop the
     * `else` half).
     */
    private fun bodyOf(src: String, decl: String): String {
        val at = src.indexOf(decl)
        assertTrue("declaration not found: $decl", at >= 0)
        // Skip the parameter list — default lambdas there are not the body.
        var k = src.indexOf('(', at)
        var depth = 0
        while (k < src.length) {
            if (src[k] == '(') depth++
            if (src[k] == ')') {
                depth--
                if (depth == 0) break
            }
            k++
        }
        var p = k + 1
        while (p < src.length && src[p].isWhitespace()) p++
        var brace = p
        var assign = -1
        while (brace < src.length && src[brace] != '{' && src[brace] != '=') brace++
        if (brace >= src.length) throw AssertionError("no body for $decl")
        if (src[brace] == '=') {
            assign = brace
            return expressionBody(src, at, assign)
        }
        val start = brace
        depth = 0
        while (brace < src.length) {
            if (src[brace] == '{') depth++
            if (src[brace] == '}') {
                depth--
                if (depth == 0) return src.substring(start, brace + 1)
            }
            brace++
        }
        throw AssertionError("unbalanced body for $decl")
    }

    /** `= expr` body: the `=` line plus deeper-indented lines, to the blank/next decl. */
    private fun expressionBody(src: String, declAt: Int, eqAt: Int): String {
        val lineStart = src.lastIndexOf('\n', declAt) + 1
        val declIndent = declAt - lineStart
        var i = eqAt
        var end = src.length
        while (i < src.length) {
            val nl = src.indexOf('\n', i)
            if (nl < 0) {
                end = src.length
                break
            }
            val next = nl + 1
            if (next >= src.length) {
                end = src.length
                break
            }
            var j = next
            var lead = 0
            while (j < src.length && (src[j] == ' ' || src[j] == '\t')) {
                j++
                lead++
            }
            if (j >= src.length || src[j] == '\n' || src[j] == '\r' || lead <= declIndent) {
                end = nl
                break
            }
            // Advance PAST the newline: `src.indexOf('\n', i)` returns `i`
            // itself when i already sits on one, so `i = nl` never moves and the
            // loop spins forever (measured: 9 min of CPU before it was killed).
            i = nl + 1
        }
        return src.substring(declAt, end)
    }

    /** The `{ … }` owned by the `if (…)` at [at] — brace-balanced, no window. */
    private fun ifBlockOf(body: String, at: Int): String {
        val open = body.indexOf('{', at)
        assertTrue("no block after the guard at $at", open >= 0)
        var depth = 0
        var k = open
        while (k < body.length) {
            if (body[k] == '{') depth++
            if (body[k] == '}') {
                depth--
                if (depth == 0) return body.substring(open, k + 1)
            }
            k++
        }
        throw AssertionError("unbalanced guard block")
    }

    /** The `… ?: run { … }` branch starting at [anchor], brace-balanced. */
    private fun branchOf(body: String, anchor: String): String {
        val at = body.indexOf(anchor)
        assertTrue("branch not found: $anchor", at >= 0)
        val open = body.indexOf('{', at)
        var depth = 0
        var k = open
        while (k < body.length) {
            if (body[k] == '{') depth++
            if (body[k] == '}') {
                depth--
                if (depth == 0) return body.substring(at, k + 1)
            }
            k++
        }
        throw AssertionError("unbalanced branch for $anchor")
    }

    private companion object {
        private const val ManagerPath =
            "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserUseManager.kt"
        private const val TabPoolPath =
            "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserTabPool.kt"
    }
}
