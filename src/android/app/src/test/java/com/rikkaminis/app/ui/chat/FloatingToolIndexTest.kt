package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-C8/F-07] The floating tool bar's page cursor across a shrinking list.
 *
 * The defect: `currentIndex` is raw `remember` state (no key), and the
 * `LaunchedEffect(lastIndex)` body only ever moved it FORWARD
 * (`if (newestActive) currentIndex = lastIndex`). Edit-and-resend, retry and
 * session switch all replace `toolBlocks` with a shorter list, so the cursor
 * could end up past the end — `toolBlocks.getOrNull(currentIndex)` returned
 * null, the composable early-returned, and the bar disappeared entirely (no
 * thumbnail, no stop affordance, no pager) while ChatScreen's bottomReserve —
 * driven by `hasFloatingTools`, a different predicate — kept reserving 83 dp of
 * blank space above the composer.
 *
 * Every expectation is a literal; none is derived from the implementation.
 * Source: `src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatComposerWidgets.kt`
 */
class FloatingToolIndexTest {

    private val sourcePath =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatComposerWidgets.kt"

    // ── the state transition ───────────────────────────────────────────────

    /** The regression: list shrank 6 → 3 while the cursor sat at 5. */
    @Test
    fun `cursor past the new end clamps to the newest surviving block`() {
        assertEquals(2, floatingToolIndexAfterUpdate(currentIndex = 5, lastIndex = 2, newestActive = false))
    }

    /** One past the end is already enough to blank the bar. */
    @Test
    fun `cursor exactly one past the end clamps too`() {
        assertEquals(1, floatingToolIndexAfterUpdate(currentIndex = 2, lastIndex = 1, newestActive = false))
    }

    /** A live tool still wins over the user's paged position (T261 kept). */
    @Test
    fun `a running newest block pulls the cursor forward`() {
        assertEquals(3, floatingToolIndexAfterUpdate(currentIndex = 0, lastIndex = 3, newestActive = true))
    }

    /** Paging back to a finished tool is deliberate and must survive. */
    @Test
    fun `a paged-back cursor inside range is left alone`() {
        assertEquals(1, floatingToolIndexAfterUpdate(currentIndex = 1, lastIndex = 3, newestActive = false))
    }

    /** Empty list → 0 (getOrNull still returns null, so the bar hides on purpose). */
    @Test
    fun `an emptied list parks the cursor at zero`() {
        assertEquals(0, floatingToolIndexAfterUpdate(currentIndex = 4, lastIndex = -1, newestActive = false))
    }

    @Test
    fun `a negative cursor is normalised to zero`() {
        assertEquals(0, floatingToolIndexAfterUpdate(currentIndex = -3, lastIndex = 2, newestActive = false))
    }

    // ── the read site ──────────────────────────────────────────────────────

    @Test
    fun `read index is clamped into range`() {
        assertEquals(2, floatingToolSafeIndex(currentIndex = 5, lastIndex = 2))
        assertEquals(1, floatingToolSafeIndex(currentIndex = 1, lastIndex = 3))
        assertEquals(0, floatingToolSafeIndex(currentIndex = -2, lastIndex = 3))
    }

    @Test
    fun `read index of an emptied list is zero rather than negative`() {
        assertEquals(0, floatingToolSafeIndex(currentIndex = 3, lastIndex = -1))
    }

    @Test
    fun `read index always resolves to a real block when one exists`() {
        // Property used by the composable: a non-empty list never early-returns
        // because of a stale cursor.
        for (last in 0..5) {
            for (cur in -2..12) {
                val idx = floatingToolSafeIndex(cur, last)
                assertEquals(true, idx in 0..last)
            }
        }
    }

    // ── source shape ───────────────────────────────────────────────────────

    @Test
    fun `the composable routes its cursor through the pure helpers`() {
        val src = readRepoFile(sourcePath)
        assertTrue("write site must go through the helper",
            src.contains("currentIndex = floatingToolIndexAfterUpdate(currentIndex, lastIndex, newestActive)"))
        assertTrue("read site must clamp",
            src.contains("val shownIndex = floatingToolSafeIndex(currentIndex, lastIndex)"))
        assertTrue("block lookup must use the clamped index",
            src.contains("val block = toolBlocks.getOrNull(shownIndex) ?: return"))
        assertFalse("the unclamped lookup is the defect",
            src.contains("val block = toolBlocks.getOrNull(currentIndex) ?: return"))
        assertFalse("the forward-only update is the defect",
            src.contains("if (newestActive) currentIndex = lastIndex.coerceAtLeast(0)"))
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]. Gradle sets user.dir to the module dir
     * (`src/android/app`), a sandbox JVM run can start anywhere under the
     * repo — no fixed relative path works for both, so walk up.
     */
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
