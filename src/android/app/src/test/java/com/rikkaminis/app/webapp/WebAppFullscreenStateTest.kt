package com.rikkaminis.app.webapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [da1032-A] ISSUE-01 — regression net for the fullscreen single-source fix.
 *
 * The defect was a *shape* defect: the toggle had two owners (a plain
 * `Boolean` field on the Activity plus a `remember`-ed copy inside
 * `WebAppHost`), so whether the toolbar icon followed the system bars
 * depended on Compose re-composition trivia that cannot be settled without a
 * device. The fix removes the question instead of answering it — the state
 * lives on the Activity, the composition only reads it, and the callback is
 * the single writer. These assertions pin exactly that.
 *
 * Layer: source-shape assertions + one "must not change" anchor (no Android
 * runtime in CI unit tests, and the whole point is that the shape — not a
 * render — is what regressed). Expectations are hard-coded literals so a
 * mutation that reintroduces a second source turns this red.
 */
class WebAppFullscreenStateTest {

    private val src: String = readRepoFile(ACTIVITY_REL)

    @Test
    fun `fullscreen is a single Activity-owned snapshot state`() {
        assertTrue(
            "the fullscreen toggle must be backed by one Activity snapshot state",
            src.contains("private val fullscreenState = mutableStateOf(false)"),
        )
        // The pre-fix shapes, verbatim, must not come back.
        assertFalse(
            "a plain Boolean field must not come back as a second source",
            src.contains("isFullscreen"),
        )
        assertFalse(
            "the initialFullscreen parameter/key must not come back",
            src.contains("initialFullscreen"),
        )
        assertFalse(
            "the composition must not keep its own copy of the flag",
            src.contains("var fullscreen by remember"),
        )
    }

    @Test
    fun `composition observes the same state the callback writes`() {
        assertTrue(
            "the host must read the Activity state",
            src.contains("fullscreen = fullscreenState.value"),
        )
        assertTrue(
            "the callback must write that same state",
            src.contains("fullscreenState.value = fs"),
        )
        assertTrue(
            "onResume must re-apply bars from the single source",
            src.contains("applySystemBars(fullscreen = fullscreenState.value)"),
        )
        assertTrue(
            "the host takes a plain value + callback, never a second owner",
            src.contains("    private fun WebAppHost(\n        file: File,\n        fullscreen: Boolean,"),
        )
    }

    @Test
    fun `back press flips the state instead of re-running setContent`() {
        val handler = codeOnly(src)
            .substringAfter("override fun handleOnBackPressed() {")
            .substringBefore("} else {")
        assertTrue(
            "back press must clear the single source",
            handler.contains("fullscreenState.value = false"),
        )
        assertTrue(
            "back press must still re-apply the system bars",
            handler.contains("applySystemBars(fullscreen = false)"),
        )
        assertFalse(
            "back press must not re-run the host composition (the state change is the update)",
            handler.contains("renderHost"),
        )
    }

    // --- "must not change" anchors -----------------------------------------

    @Test
    fun `system-bar and render call sites are unchanged by the fix`() {
        // The fix moved *ownership* of the flag; it must not add or drop bar
        // applications (1 definition + 4 call sites) and must not add a second
        // composition entry point (renderHost + renderSourceMissing).
        val code = codeOnly(src)
        assertEquals(
            "applySystemBars definition + call sites",
            5,
            countOccurrences(code, "applySystemBars("),
        )
        assertEquals("setContent entry points", 2, countOccurrences(code, "setContent {"))
        assertEquals(
            "renderHost definition + the single load-time call",
            2,
            countOccurrences(code, "renderHost()"),
        )
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Drop `//`-comment tails so a *comment* cannot satisfy (or break) an
     * assertion. Safe for the assertions above: none of them looks for text
     * that would live after a `//` on a line (the file's `"file://"` strings
     * are all on lines the assertions do not inspect).
     */
    private fun codeOnly(text: String): String =
        text.lineSequence().joinToString("\n") { it.substringBefore("//") }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            count++
            i = haystack.indexOf(needle, i + needle.length)
        }
        return count
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]. Gradle sets user.dir to the module dir; a sandbox JVM run
     * can start anywhere under the repo — no fixed depth works.
     */
    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    private companion object {
        const val ACTIVITY_REL =
            "src/android/app/src/main/java/com/rikkaminis/app/webapp/WebAppActivity.kt"
    }
}
