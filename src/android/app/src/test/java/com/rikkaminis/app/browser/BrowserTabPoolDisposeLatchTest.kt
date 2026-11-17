package com.rikkaminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

/**
 * [WAVE-1118 D7 F-01 + F-03] Threading and disposal latches on `BrowserTabPool`.
 *
 * WebView / Message / Context cannot be instantiated on the JVM, so — like
 * `BrowserTabPoolDisposeScopeTest`, `BrowserTabPoolSaveStateTest` and
 * `BrowserTabPoolBusyPublishTest`, which pin the same class the same way —
 * these are source-shape assertions on the real file, and every family has a
 * negative control that runs the SAME check against a mutated copy.
 *
 * Pinned here:
 *  - F-01 `savedURLs` is a concurrent map (it is mutated by eviction/trim from
 *    the memory hooks, read by `saveState` from the agent's IO thread, written
 *    by `createTab`/`destroyTab` on Main — `mutableMapOf` is a plain
 *    `LinkedHashMap`, so the saveState iteration could throw and silently drop
 *    the tab-state file).
 *  - F-01 `evictIdleTabs()` runs its body on Main (`_tabs.value` is a
 *    read-modify-write; only `manager.destroy()` used to be hopped).
 *  - F-01 the follow-selected branch of `execute()` makes ONE snapshot of
 *    `_tabs.value` (three reads could see it empty between `isNotEmpty()` and
 *    `maxByOrNull { }!!`).
 *  - F-03 nothing allocates a WebView after `dispose()` (createTab /
 *    acquireTab entry + its 250 ms poll / handleNewWindow / trim callbacks).
 */
class BrowserTabPoolDisposeLatchTest {

    private val poolFile = "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserTabPool.kt"

    // ── F-01: savedURLs is shared across three threads ───────────────────────

    @Test
    fun `savedURLs is a concurrent map`() {
        val src = poolSource()
        assertTrue(
            "savedURLs must be thread-safe (eviction/trim write it, saveState iterates it from IO)",
            src.contains("private val savedURLs = java.util.concurrent.ConcurrentHashMap<Int, String>()"),
        )
        assertFalse(
            "a plain mutableMapOf here is a LinkedHashMap — no synchronization at all",
            src.contains("private val savedURLs = mutableMapOf<Int, String>()"),
        )
    }

    @Test
    fun `negative control - the pre-fix mutableMapOf declaration is rejected`() {
        val mutated = poolSource().replace(
            "private val savedURLs = java.util.concurrent.ConcurrentHashMap<Int, String>()",
            "private val savedURLs = mutableMapOf<Int, String>()",
        )
        assertTrue("the negative control must actually mutate the source", mutated != poolSource())
        assertFalse(savedUrlsIsConcurrent(mutated))
    }

    // ── F-01: eviction pass runs on Main ────────────────────────────────────

    @Test
    fun `evictIdleTabs hops to the main thread before touching the tab list`() {
        val body = withoutComments(bodyOf(poolSource(), "fun evictIdleTabs()"))
        assertTrue(
            "evictIdleTabs must hop to Main (its callers are the memory hooks in MinisApp):\n$body",
            body.contains("Looper.myLooper() != Looper.getMainLooper()") &&
                body.contains("evictIdleTabs()") &&
                body.contains("Handler(Looper.getMainLooper())"),
        )
        val hopAt = body.indexOf("Handler(Looper.getMainLooper())")
        val listWriteAt = body.indexOf("_tabs.value = currentTabs")
        assertTrue("the hop must precede the first _tabs write", hopAt in 0 until listWriteAt)
        assertTrue(
            "a disposed pool must not evict",
            body.contains("if (isDisposed) return"),
        )
    }

    @Test
    fun `negative control - an off-main eviction pass is rejected`() {
        val mutated = poolSource().replace(
            "        if (Looper.myLooper() != Looper.getMainLooper()) {\n" +
                "            Handler(Looper.getMainLooper()).post { evictIdleTabs() }\n" +
                "            return\n" +
                "        }\n",
            "",
        )
        assertTrue("the negative control must actually mutate the source", mutated != poolSource())
        assertFalse(evictHopsToMain(mutated))
    }

    // ── F-01: one snapshot in the follow-selected branch ────────────────────

    @Test
    fun `the follow-selected branch reads _tabs once`() {
        val src = poolSource()
        val branchAt = src.indexOf("val followTabs = _tabs.value")
        assertTrue("the snapshot must exist", branchAt >= 0)
        val callAt = src.indexOf("executeSerialized(targetId, input)", branchAt)
        assertTrue("the branch must end in executeSerialized", callAt > branchAt)
        val branch = src.substring(branchAt, callAt)
        assertEquals(
            "exactly ONE read of _tabs.value inside the branch (a second one can see an emptied " +
                "list and NPE on `!!`):\n$branch",
            1,
            Regex("""_tabs\.value""").findAll(withoutComments(branch)).count(),
        )
    }

    @Test
    fun `negative control - a second _tabs read inside the branch is rejected`() {
        val mutated = poolSource().replace(
            "                        val sel = _selectedTabId.value\n" +
                "                        val targetId = if (followTabs.any { it.id == sel }) sel",
            "                        val sel = _selectedTabId.value\n" +
                "                        val targetId = if (_tabs.value.any { it.id == sel }) sel",
        )
        assertTrue("the negative control must actually mutate the source", mutated != poolSource())
        assertFalse(singleSnapshotBranch(mutated))
    }

    // ── F-03: nothing allocates a WebView after dispose ─────────────────────

    @Test
    fun `createTab refuses to run on a disposed pool before building a WebView`() {
        val body = withoutComments(bodyOf(poolSource(), "private fun createTab("))
        val guardAt = body.indexOf("if (isDisposed) return null")
        val webViewAt = body.indexOf("WebView(context)")
        assertTrue("createTab must guard on isDisposed", guardAt >= 0)
        assertTrue("the guard must come BEFORE the WebView is built", guardAt < webViewAt)
    }

    @Test
    fun `acquireTab gives up when the pool is disposed, including inside its wait poll`() {
        val body = withoutComments(bodyOf(poolSource(), "private suspend fun acquireTab("))
        assertEquals(
            "acquireTab needs the latch at entry AND inside the implicit-tab wait:\n$body",
            2,
            occurrences(body, "if (isDisposed) return@withContext null"),
        )
    }

    @Test
    fun `handleNewWindow refuses to run on a disposed pool`() {
        val body = withoutComments(bodyOf(poolSource(), "private fun handleNewWindow("))
        val guardAt = body.indexOf("if (isDisposed) return")
        val webViewAt = body.indexOf("WebView(context)")
        assertTrue("handleNewWindow must guard on isDisposed", guardAt >= 0)
        assertTrue("the guard must come BEFORE the WebView is built", guardAt < webViewAt)
    }

    @Test
    fun `negative control - creating a WebView without the disposal latch is rejected`() {
        val mutated = poolSource()
            .replace(
                "        if (isDisposed) return null\n        if (tabs.size >= MAX_TABS) return null",
                "        if (tabs.size >= MAX_TABS) return null",
            )
            .replace(
                "        if (isDisposed) return\n        val currentTabs = _tabs.value.toMutableList()\n        if (currentTabs.size >= MAX_TABS) {",
                "        val currentTabs = _tabs.value.toMutableList()\n        if (currentTabs.size >= MAX_TABS) {",
            )
        assertTrue("the negative control must actually mutate the source", mutated != poolSource())
        assertFalse(createGuardsDispose(mutated, "private fun createTab("))
        assertFalse(createGuardsDispose(mutated, "private fun handleNewWindow("))
    }

    // ── checks as functions of the source text ──────────────────────────────

    private fun savedUrlsIsConcurrent(source: String): Boolean =
        source.contains("private val savedURLs = java.util.concurrent.ConcurrentHashMap<Int, String>()")

    private fun evictHopsToMain(source: String): Boolean {
        val body = withoutComments(bodyOf(source, "fun evictIdleTabs()"))
        return body.contains("Looper.myLooper() != Looper.getMainLooper()") &&
            body.contains("Handler(Looper.getMainLooper())")
    }

    private fun singleSnapshotBranch(source: String): Boolean {
        val branchAt = source.indexOf("val followTabs = _tabs.value")
        if (branchAt < 0) return false
        val callAt = source.indexOf("executeSerialized(targetId, input)", branchAt)
        if (callAt <= branchAt) return false
        val branch = withoutComments(source.substring(branchAt, callAt))
        return Regex("""_tabs\.value""").findAll(branch).count() == 1
    }

    private fun createGuardsDispose(source: String, header: String): Boolean {
        val body = withoutComments(bodyOf(source, header))
        val guardAt = body.indexOf("if (isDisposed) return")
        val webViewAt = body.indexOf("WebView(context)")
        return guardAt >= 0 && (webViewAt < 0 || guardAt < webViewAt)
    }

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var from = 0
        while (from <= text.length - needle.length) {
            val hit = text.indexOf(needle, from)
            if (hit < 0) return count
            count++
            from = hit + 1
        }
        return count
    }

    private fun withoutComments(text: String): String = text
        .replace(Regex("""//[^\n]*"""), " ")
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")

    private fun poolSource(): String =
        readRepoFile(poolFile)

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun bodyOf(source: String, header: String): String {
        val at = source.indexOf(header)
        assertTrue("anchor missing: $header", at >= 0)
        val open = source.indexOf('{', at)
        assertTrue("no body brace after $header", open >= 0)
        var depth = 0
        var inStr = false
        var escaped = false
        var i = open
        while (i < source.length) {
            val c = source[i]
            if (inStr) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return source.substring(at, i + 1)
                    }
                }
            }
            i++
        }
        error("unbalanced braces after $header")
    }
}
