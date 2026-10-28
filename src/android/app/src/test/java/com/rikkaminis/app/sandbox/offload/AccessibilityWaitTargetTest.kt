package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [meta1028-D2 F-03] `android-a11y-cli wait appear|disappear`.
 *
 * Two defects, both silent:
 *  1. **Per-root verdict.** `MinisAccessibilityService.rootNodes()` returns one
 *     root PER WINDOW (status bar, IME, overlay included), and the verdict
 *     `found == present` sat INSIDE the root loop — so `wait disappear`
 *     answered "disappeared" as soon as the FIRST root lacked the target, even
 *     while the app window still showed it, and `wait appear` could only
 *     succeed if the first window held the node.
 *  2. **No selector, no search.** With no `--text/--id/--class` the match list
 *     is empty by construction: `disappear` returned instantly (a guaranteed
 *     lie) and `appear` burned the whole timeout to answer found=false.
 *
 * Also pinned: the wait path used to UNION each selector's hits (OR) while
 * `ui find` ANDs them through `matchesPredicate` — same CLI, opposite semantics.
 */
class AccessibilityWaitTargetTest {

    // ── 1. selector presence ─────────────────────────────────────────────

    @Test
    fun `no selector at all is refused, every single one is accepted`() {
        assertFalse(
            "a wait with no selector must be refused, not answered from an empty search",
            waitSelectorProvided(text = null, textContains = null, id = null, className = null),
        )
        assertTrue(waitSelectorProvided(text = "Loading", textContains = null, id = null, className = null))
        assertTrue(waitSelectorProvided(text = null, textContains = "Load", id = null, className = null))
        assertTrue(waitSelectorProvided(text = null, textContains = null, id = "com.x:id/y", className = null))
        assertTrue(
            waitSelectorProvided(
                text = null, textContains = null, id = null, className = "android.widget.TextView",
            ),
        )
        assertTrue(waitSelectorProvided("a", "b", "c", "d"))
    }

    // ── 2. the whole-screen verdict ──────────────────────────────────────

    @Test
    fun `appear is satisfied only by a match, disappear only by its absence`() {
        assertEquals(WaitVerdict.SATISFIED, waitVerdict(present = true, screenMatch = true))
        assertEquals(WaitVerdict.KEEP_WAITING, waitVerdict(present = true, screenMatch = false))
        assertEquals(WaitVerdict.KEEP_WAITING, waitVerdict(present = false, screenMatch = true))
        assertEquals(WaitVerdict.SATISFIED, waitVerdict(present = false, screenMatch = false))
    }

    // ── 3. wiring (source shape) ─────────────────────────────────────────

    private val handlerRel =
        "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/AccessibilityOffloadHandler.kt"

    @Test
    fun `the verdict is applied once per poll, never per window root`() {
        val body = memberSlice(readRepoFile(handlerRel), "private fun waitAppear(")
        assertFalse(
            "the root loop must not sit in waitAppear — that is the per-window bug shape",
            body.contains("for (root in svc.rootNodes())"),
        )
        assertTrue(
            "waitAppear must search the whole screen through findWaitTarget",
            body.contains("findWaitTarget(svc, args)"),
        )
        assertTrue(
            "waitAppear must refuse a selector-less call before polling",
            body.contains("waitSelectorProvided("),
        )
        assertTrue(
            "the verdict must come from the shared predicate",
            body.contains("waitVerdict(present, screenMatch = match != null)"),
        )
        assertFalse(
            "union-of-selectors (OR) is the divergence from ui find and must be gone",
            body.contains("matches.add(") || body.contains("matches.isNotEmpty()"),
        )
    }

    @Test
    fun `findWaitTarget is the only place that walks every window root`() {
        val body = memberSlice(readRepoFile(handlerRel), "private fun findWaitTarget(")
        assertTrue(
            "all window roots must be searched before the screen answer is formed",
            body.contains("for (root in svc.rootNodes())"),
        )
        assertTrue(
            "an AND predicate per node is what makes the answer match ui find",
            body.contains("findWaitMatchIn(root, args"),
        )
    }

    @Test
    fun `timeouts keep the family contract of exit 0 plus timedOut`() {
        val body = memberSlice(readRepoFile(handlerRel), "private fun waitAppear(")
        assertTrue(
            "wait appear|disappear answers exit 0 + timedOut=true like wait stable/activity",
            body.contains("put(\"timedOut\", true)"),
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun memberSlice(src: String, marker: String): String {
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains(marker) }
        assertTrue("marker not found: $marker", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { i ->
            val line = lines[i]
            val indent = line.takeWhile { it == ' ' }.length
            indent <= 4 && line.isNotBlank() && !line.trimStart().startsWith("//") &&
                !line.trimStart().startsWith("*") &&
                Regex("^(private|internal|public|protected|fun|val|var|@)").containsMatchIn(line.trimStart())
        } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }
}
