package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/dv20-b4-backup-busy] deep-audit-1040 B-08.
 *
 * `BackupSettingsScreen`'s fetch-to-local path (`onFetch`, reached when the
 * auto-backup remote list is opened for an entry) pulls a full payload into
 * memory and then writes + rotates `filesDir/backup-autos` — the same
 * directory the nightly run, the SAF export and every restore touch. It used
 * to raise only the dialog-local `autoRemoteLoading`, so dismissing the dialog
 * mid-flight (its OK button and the back gesture are never gated) left
 * "Run Now" / "Restore" / "Export" clickable and let two full payloads land on
 * that directory at once. Its post-fetch `openAutoRemoteList()` also ran
 * unconditionally, re-arming the spinner from a scope that a torn-down screen
 * had already cancelled.
 *
 * The screen is Compose-only (not JVM-instantiable: it needs a real `Context`,
 * a WebDAV client and the auto-backup directory), so the contract is pinned by
 * source-shape assertions — the same approach `SharedCacheExportPruneTest` and
 * `SkillFileViewerSaveShapeTest` use for their screens.
 *
 * Expected values are hardcoded literals (never derived from the code under
 * test) so a mutation of the production code cannot masquerade as PASS.
 */
class BackupFetchBusyGateTest {

    private val screen: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/settings/BackupSettingsScreen.kt")
    }

    /** Comment-stripped source: every assertion below is about CODE, and the
     *  handler's own prose mentions `openAutoRemoteList()` (in the ordering
     *  note), so a raw `indexOf` would anchor on the comment instead of the
     *  call. The scan gate strips comments for the same reason. */
    private val code: String by lazy { withoutComments(screen) }

    /** The `onFetch = { entry -> … }` handler region, located by brace matching
     *  (the handler is a lambda argument, so a line-based anchor would drift on
     *  any reformat). The candidate carrying the post-fetch list refresh wins. */
    private val handler: String by lazy { fetchHandlerRegion(code) }

    // -- ① the handler region is still locatable (fail-closed anchor) --

    @Test
    fun fetchHandler_regionIsLocated() {
        val anchor = screen.indexOf("onFetch")
        assertTrue("onFetch handler anchor disappeared from the screen", anchor >= 0)
        val braceBalanced = handler.isNotEmpty() && handler.count { it == '{' } == handler.count { it == '}' }
        assertTrue("onFetch handler region must be brace-balanced, got: ${handler.take(80)}", braceBalanced)
    }

    // -- ② the fetch path claims the shared busy gate --

    @Test
    fun fetchHandler_claimsTheSharedBusyGate() {
        assertEquals(
            "fetch-to-local must raise the shared operationBusy gate exactly once",
            1,
            handler.split("operationBusy = true").size - 1,
        )
    }

    // -- ③ and releases it on the exit path, not just the happy path --

    @Test
    fun fetchHandler_releasesTheGateInsideFinally() {
        val finallyIdx = handler.indexOf("finally")
        assertTrue("fetch-to-local must release the shared gate on its exit path (no `finally`)", finallyIdx >= 0)
        val finallyBlock = bracedRegion(handler, finallyIdx)
        assertEquals(
            "the shared gate must be lowered exactly once inside `finally`",
            1,
            finallyBlock.split("operationBusy = false").size - 1,
        )
    }

    // -- ④ the tail refresh is liveness-guarded --

    @Test
    fun postFetchRefresh_isLivenessGuarded() {
        val refreshIdx = handler.indexOf("openAutoRemoteList(")
        assertTrue("the post-fetch refresh anchor disappeared", refreshIdx >= 0)
        val window = handler.substring(maxOf(0, refreshIdx - 200), refreshIdx)
        assertTrue(
            "the post-fetch openAutoRemoteList() must be liveness-guarded (isActive / ensureActive())",
            window.contains("isActive") || window.contains("ensureActive("),
        )
    }

    // -- ⑤ a cancelled fetch must not be reported as a failure --

    @Test
    fun cancellation_isRethrownNotSwallowed() {
        assertTrue(
            "a screen-gone cancellation must be rethrown, not folded into the error path",
            handler.contains("catch (t: CancellationException)") && handler.contains("throw t"),
        )
    }

    // -- ⑥ radius: only this path gained a gate (whole-file literals) --

    @Test
    fun onlyTheFetchPathGainedAGateSite() {
        assertEquals(
            "exactly one new operationBusy = true site (the fetch path)",
            7,
            code.split("operationBusy = true").size - 1,
        )
        assertEquals(
            "exactly one new operationBusy = false site (the fetch path's finally)",
            10,
            code.split("operationBusy = false").size - 1,
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun fetchHandlerRegion(src: String): String {
        var from = 0
        while (true) {
            val i = src.indexOf("onFetch", from)
            if (i < 0) return ""
            val region = bracedRegion(src, i)
            if (region.contains("openAutoRemoteList(")) return region
            from = i + "onFetch".length
        }
    }

    /** Substring from [marker] through the brace that closes the first block
     *  opened after it. */
    private fun bracedRegion(src: String, marker: Int): String {
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

    /** Comment-stripped source (string-literal aware), mirroring the scan
     *  gate's `strip_comments`: every assertion below is about CODE, and the
     *  handler's own prose mentions `openAutoRemoteList()` (in the ordering
     *  note), so a raw `indexOf` would anchor on the comment instead of the
     *  call. */
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
