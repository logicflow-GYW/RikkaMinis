package com.rikkaminis.app.browser

import java.io.FileNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [dv22-B2] Pins the busy-signal re-derivation on every tab destroy path.
 *
 * `BrowserTabPool.agentBusy` is the only reactive carrier of "the agent is
 * working" (`Tab.inUse` flips in place, so the `tabs` StateFlow emission is
 * content-equal and suppressed). Six paths destroy a tab and rewrite
 * `_tabs.value` WITHOUT touching `Tab.inUse`: closeTab, closeTabFromUI,
 * handleCloseWindow, dispose, onTrimMemory, evictIdleTabs. Before dv22-B2
 * none of them re-derived the flag, so destroying the last in-use tab left
 * `agentBusy` frozen at `true` and BrowserSheet stayed stuck on its
 * "agent working" affordances.
 *
 * WebView/Message cannot be instantiated on the JVM, so these are
 * source-shape assertions on the real file (literal anchors, same
 * convention as BrowserTabPoolSaveStateTest / BrowserUserAgentPoolSyncTest).
 * `mutationsAreCaught` re-runs the whole family on mutated copies that
 * remove exactly the thing each assertion pins.
 *
 * The publish deliberately lives at the CALLER's `_tabs.value` write, not
 * inside destroyTab: destroyTab receives the caller's local mutable list
 * (not yet written back), so a publish inside it would derive from the stale
 * `_tabs.value` — the destroyed tab still looks in-use; and dispose's list
 * write happens after the destroy loop, out of destroyTab's reach. The
 * assertion family below pins that division of labour too.
 */
class BrowserTabPoolBusyPublishTest {

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

    /** The six destroy paths, header spelling exactly as shipped. */
    private val destroyPaths = listOf(
        "private suspend fun closeTab(",
        "suspend fun closeTabFromUI(",
        "private fun handleCloseWindow(",
        "fun dispose(",
        "fun onTrimMemory(",
        "fun evictIdleTabs(",
    )

    private fun assertPublishesAfterListWrite(src: String, header: String) {
        val body = bodyOf(src, header)
        val destroyAt = body.indexOf("destroyTab(")
        assertTrue("$header no longer destroys a tab", destroyAt >= 0)
        val writes = Regex("""_tabs\.value\s*=""").findAll(body).map { it.range.first }.toList()
        assertTrue("$header rewrites _tabs.value nowhere — destroy must settle the list", writes.isNotEmpty())
        val lastWrite = writes.max()!!
        val publishAt = body.indexOf("publishAgentBusy()")
        assertTrue(
            "$header destroys a tab but never re-derives the busy flag — " +
                "agentBusy freezes at its pre-destroy value when the last in-use tab " +
                "is destroyed (UI stuck on \"agent working\")",
            publishAt >= 0,
        )
        assertTrue(
            "$header publishes the busy flag before its last _tabs.value write — " +
                "the re-derivation must see the post-destroy list or it recomputes a " +
                "stale value",
            publishAt > lastWrite,
        )
    }

    @Test
    fun sixDestroyPathsEachPublishAfterTheListWrite() {
        val src = poolCode()
        for (header in destroyPaths) {
            assertPublishesAfterListWrite(src, header)
        }
    }

    @Test
    fun publisherIsStillDerivedNotGuessed() {
        val src = poolCode()
        val pub = bodyOf(src, "private fun publishAgentBusy(")
        assertTrue(
            "publishAgentBusy no longer re-derives from the live tab list",
            pub.contains("_tabs.value.any { it.inUse }"),
        )
    }

    @Test
    fun destroyTabStaysAPureTeardownCallersPublish() {
        val src = poolCode()
        val body = bodyOf(src, "private fun destroyTab(")
        assertFalse(
            "destroyTab publishes the busy flag itself — it sees the caller's local " +
                "list, not _tabs.value, so the derivation is stale by construction; " +
                "the publish belongs at the caller's list write",
            body.contains("publishAgentBusy()"),
        )
        // The two pre-existing publication points must survive (C9 anchors).
        assertTrue("releaseAllTabs lost its publish", bodyOf(src, "fun releaseAllTabs(").contains("publishAgentBusy()"))
        assertTrue("updateTabs lost its publish", bodyOf(src, "private fun updateTabs(").contains("publishAgentBusy()"))
    }

    @Test
    fun consumerSubscribesToTheFlow() {
        val sheet = readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/ui/browser/BrowserSheet.kt")
        assertTrue(
            "BrowserSheet no longer subscribes to the pool's busy flow",
            sheet.contains("val isAgentBusy by tabPool.agentBusy.collectAsState()"),
        )
    }

    /** Each assertion family is re-run on a mutated copy that removes exactly
     *  the thing it pins — a green run on the mutation means the assertion
     *  above is toothless. */
    @Test
    fun mutationsAreCaught() {
        val src = poolCode()
        var caught = 0
        var expected = 0
        for (header in destroyPaths) {
            val at = src.indexOf(header)
            assertTrue("anchor missing: $header", at >= 0)
            val body = bodyOf(src, header)
            if (!body.contains("publishAgentBusy()")) {
                // This path already lacks the re-derivation — the primary
                // assertion above is the one that must fail; there is nothing
                // to mutate here.
                continue
            }
            expected++
            val mutated = src.replaceRange(
                src.indexOf(body, at),
                src.indexOf(body, at) + body.length,
                body.replaceFirst(Regex("""\n(?:[ \t]*//[^\n]*\n)+[ \t]*publishAgentBusy\(\)"""), "\n"),
            )
            if (mutated != src) {
                try {
                    assertPublishesAfterListWrite(mutated, header)
                    // Not caught — assertion is toothless for this path.
                } catch (e: AssertionError) {
                    caught++
                }
            } else {
                throw AssertionError("mutation did not change the source for $header (anchor drift?)")
            }
        }
        assertTrue(
            "mutation guard has nothing to remove (expected at least one destroy " +
                "path carrying a re-derivation) — the fixture drifted",
            expected > 0,
        )
        assertTrue(
            "mutation guard caught only $caught of $expected removals — " +
                "some destroy-path assertion is toothless",
            caught == expected,
        )
    }
}
