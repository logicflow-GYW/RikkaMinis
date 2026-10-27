package com.rikkaminis.app.browser

import java.io.FileNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [dv24-B4 B-07] Pins that disposal owns the pool's ASYNC faces, not just the
 * eviction ticker.
 *
 * `dispose()` used to cancel `evictionJob` and destroy the tabs, and nothing
 * else: the two instance-owned CoroutineScopes (`evictionScope`,
 * `downloadScope`), the `downloadJobs` table, the pending `savedURLs` restore
 * queue and the `onDownloadEvent` chat callback all outlived the pool. The
 * trigger is plain: `loadSavedState()` queues
 * `evictionScope.launch { withContext(Dispatchers.Main) { restoreSavedTabs() } }`
 * and the session is deleted right after, so `restoreSavedTabs()` ran on a
 * disposed pool — rebuilding WebViews nobody would ever destroy (main thread
 * + the Context they hold) and then persisting the tab list, which re-created
 * the very `browser_tabs/<sid>.json` the deletion had just removed (the
 * dv14-B2 invariant) — while in-flight downloads kept writing into the
 * deleted session's workspace and posting progress into the cleared
 * ViewModel.
 *
 * WebView / Message / Context cannot be instantiated on the JVM, so — like
 * BrowserTabPoolSaveStateTest and BrowserTabPoolBusyPublishTest, which pin
 * dispose's persistence and busy-flag faces the same way — these are
 * source-shape assertions on the real file, with `mutationsAreCaught`
 * re-running every family on a mutated copy that removes exactly the thing it
 * pins.
 */
class BrowserTabPoolDisposeScopeTest {

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    private fun poolCode(): String =
        readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserTabPool.kt")

    private fun bodyOf(src: String, header: String): String {
        val at = src.indexOf(header)
        assertTrue("anchor missing: $header", at >= 0)
        val open = src.indexOf('{', at)
        assertTrue("no body brace after $header", open >= 0)
        var depth = 0
        var inStr = false
        var i = open
        while (i < src.length) {
            val c = src[i]
            if (inStr) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        return src.substring(open)
    }

    /** Every `val name [ : CoroutineScope ] = CoroutineScope(` in the pool —
     *  derived from the file, so a third scope is covered by construction. */
    private fun scopeFields(src: String): List<String> {
        val re = Regex("""\bval\s+([A-Za-z_]\w*)\s*(?::\s*CoroutineScope\s*)?=\s*CoroutineScope\s*\(""")
        return re.findAll(src).map { it.groupValues[1] }.toList()
    }

    private fun assertDisposeCancels(src: String, scope: String) {
        val body = bodyOf(src, "fun dispose(")
        assertTrue(
            "dispose() never cancels `$scope` — work already queued on that scope keeps " +
                "running against a destroyed pool (a Main-dispatched restoreSavedTabs " +
                "rebuilt WebViews nobody will destroy and re-persisted a just-deleted " +
                "session's browser_tabs file)",
            body.contains("$scope.cancel("),
        )
    }

    /** The latch must be cross-thread visible, set by dispose, and checked
     *  BEFORE the first release step (idempotent release half). */
    private fun assertDisposeLatch(src: String) {
        val body = bodyOf(src, "fun dispose(")
        assertTrue("dispose() no longer sets the disposal latch", body.contains("isDisposed = true"))
        val guardAt = body.indexOf("if (isDisposed) return")
        assertTrue(
            "dispose() does not short-circuit on the disposal latch — a second dispose " +
                "re-runs the release half (destroy loop, unregister, scope cancels)",
            guardAt >= 0,
        )
        val firstEffect = listOf("cancel(", "unregisterComponentCallbacks", "destroyTab(")
            .map { body.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
        assertTrue("dispose() performs no release at all (anchor drift)", firstEffect != null)
        assertTrue(
            "dispose() sets the latch only after it starts releasing — the guard must run " +
                "before the first release step",
            guardAt < firstEffect!!,
        )
        assertFalse(
            "dispose() persists tab state again — it resurrected a just-deleted session's " +
                "browser_tabs/<sid>.json (dv14-B2)",
            body.contains("saveState("),
        )
        assertTrue("dispose() no longer destroys tabs (C4b anchor)", body.contains("destroyTab("))
        assertTrue(
            "dispose() leaves the pending saved-URL restore table populated",
            body.contains("savedURLs.clear()"),
        )
        assertTrue(
            "dispose() leaves onDownloadEvent wired to the cleared ViewModel",
            body.contains("onDownloadEvent = null"),
        )
    }

    private fun assertLatchGuard(src: String, header: String, before: String, what: String) {
        val body = bodyOf(src, header)
        val guard = Regex("""\bif\s*\(\s*isDisposed\s*\)\s*return\b""").find(body)
        assertTrue(
            "$what does not short-circuit on the disposal latch — the continuation keeps " +
                "working against a pool whose owner is gone",
            guard != null,
        )
        val workAt = body.indexOf(before)
        assertTrue("$what no longer contains `$before` (anchor drift)", workAt >= 0)
        assertTrue(
            "$what consults the latch only after `$before` — the work already happened",
            guard!!.range.first < workAt,
        )
    }

    @Test
    fun disposeCancelsEveryPoolOwnedScope() {
        val src = poolCode()
        val scopes = scopeFields(src)
        assertTrue(
            "no `= CoroutineScope(` field found in the pool — this criterion exists to " +
                "keep disposal owning them",
            scopes.isNotEmpty(),
        )
        for (scope in scopes) assertDisposeCancels(src, scope)
    }

    @Test
    fun disposalLatchIsVolatile() {
        val src = poolCode()
        assertTrue(
            "the disposal latch is missing entirely",
            Regex("""\bvar\s+isDisposed\b""").containsMatchIn(src),
        )
        // dispose() may run on the ViewModel-clearing thread while the
        // Main-dispatched restore reads it: a stale read is the bug.
        assertTrue(
            "the disposal latch is not declared @Volatile — a stale read on the restore " +
                "thread is exactly what the latch exists to prevent",
            Regex("""@Volatile\b[\s\S]{0,160}?\bvar\s+isDisposed\b""").containsMatchIn(src),
        )
        assertDisposeLatch(src)
    }

    @Test
    fun bothAsyncContinuationsGuardOnTheLatch() {
        val src = poolCode()
        // A restore that landed on Main before disposal must not write the tab
        // list (and re-create a deleted session's file).
        assertLatchGuard(src, "private fun saveState(", "browser_tabs", "saveState()")
        // …and must bail before the first WebView is rebuilt.
        assertLatchGuard(src, "private fun restoreSavedTabs(", "createTab(", "restoreSavedTabs()")
    }

    /** Every family re-run on copies that remove exactly what it pins: a
     *  mutation that stays green means that assertion is toothless. */
    @Test
    fun mutationsAreCaught() {
        val src = poolCode()
        allAssertions(src) // sanity: green on the shipped source
        val scope = scopeFields(src).first()

        val arms = listOf(
            "drop `$scope.cancel()`" to src.replace("        $scope.cancel()\n", ""),
            "drop the dispose latch" to
                src.replaceFirst("if (isDisposed) return\n        isDisposed = true\n", ""),
            "drop the saveState guard" to
                removeGuard(src, "private fun saveState(", "if (isDisposed) return"),
            "drop the restoreSavedTabs guard" to
                removeGuard(src, "private fun restoreSavedTabs(", "if (isDisposed) return"),
            "make the latch non-volatile" to
                src.replaceFirst(
                    "@Volatile\n    private var isDisposed = false",
                    "private var isDisposed = false",
                ),
        )

        var caught = 0
        for ((label, mutated) in arms) {
            if (mutated == src) throw AssertionError("mutation `$label` changed nothing (anchor drift)")
            var failed = false
            try {
                allAssertions(mutated)
            } catch (e: AssertionError) {
                failed = true
            }
            if (failed) caught++
        }
        assertTrue(
            "mutation guard caught only $caught of ${arms.size} removals — some disposal " +
                "assertion is toothless",
            caught == arms.size,
        )
    }

    private fun allAssertions(src: String) {
        val scopes = scopeFields(src)
        assertTrue("no scope field", scopes.isNotEmpty())
        for (scope in scopes) assertDisposeCancels(src, scope)
        assertTrue(
            "the disposal latch is not @Volatile",
            Regex("""@Volatile\b[\s\S]{0,160}?\bvar\s+isDisposed\b""").containsMatchIn(src),
        )
        assertDisposeLatch(src)
        assertLatchGuard(src, "private fun saveState(", "browser_tabs", "saveState()")
        assertLatchGuard(src, "private fun restoreSavedTabs(", "createTab(", "restoreSavedTabs()")
    }

    /** Remove the first occurrence of [guard] inside the body of [header]. */
    private fun removeGuard(src: String, header: String, guard: String): String {
        val at = src.indexOf(header)
        assertTrue("anchor missing: $header", at >= 0)
        val body = bodyOf(src, header)
        val bodyAt = src.indexOf(body, at)
        val guardAt = body.indexOf(guard)
        assertTrue("guard `$guard` not found in $header", guardAt >= 0)
        return src.removeRange(bodyAt + guardAt, bodyAt + guardAt + guard.length)
    }
}
