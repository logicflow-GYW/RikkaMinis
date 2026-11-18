package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/topbar-glass-flash-1118] Wiring test for the opaque floor under the
 * top-bar glass.
 *
 * The maths is pinned by [TopBarGlassOcclusionTest]; what can still go
 * wrong lives in the composable, and each failure mode has its own arm:
 *
 *  1. the floor is DELETED → the reported flash returns (a zero-alpha
 *     graphicsLayer draws nothing while `topBarTail` has already lifted the rows
 *     behind the bar);
 *  2. the floor is filled with a CONSTANT (1f or 0f) → 1f makes the bar opaque
 *     forever (the glass is never seen), 0f is the bug again;
 *  3. the floor is drawn AFTER the glass → it paints over the blur, same visual
 *     result as (2);
 *  4. the floor stops following the bar's size → the corners/insets show raw
 *     content even when the alpha is right;
 *  5. someone "fixes" the row flash by animating `topBarTail` with the same
 *     alpha → the list's measured height changes every frame and every row
 *     jitters (the reason this fix uses a floor instead).
 *
 * Read from the repository (walk-up) so Gradle's module `user.dir` and a repo
 * root both resolve.
 */
class TopBarGlassFloorWiringTest {

    private val source: String get() = readRepoFile(CHAT_SCREEN)

    @Test
    fun `the top bar draws an opaque floor from the glass alpha`() {
        val floor = source.indexOf("TopBarGlassOcclusion.scrimAlpha(topBarGlassAlpha)")
        assertTrue(
            "the floor is gone — without it a zero-alpha glass layer leaves the " +
                "lifted rows painted raw behind a transparent bar",
            floor >= 0,
        )
        val window = source.substring(maxOf(0, floor - 400), floor)
        assertTrue(
            "the floor must be filled with the chat background, not left to the parent",
            window.contains(".background("),
        )
        assertTrue(
            "the floor must follow the bar's measured size, or its edges show raw content",
            window.contains("matchParentSize()"),
        )
    }

    @Test
    fun `the floor is drawn under the glass layer, not over it`() {
        val floor = source.indexOf("TopBarGlassOcclusion.scrimAlpha(topBarGlassAlpha)")
        val glass = source.indexOf("hazeEffect(state = hazeState, style = topBarGlassStyle)")
        assertTrue("the top-bar glass layer is gone", glass >= 0)
        assertTrue(
            "the floor is drawn after the glass, so it paints over the blur — the bar " +
                "would read as a solid strip and the effect would be invisible",
            floor in 0 until glass,
        )
    }

    @Test
    fun `the glass layer still follows the scroll-driven alpha`() {
        assertTrue(
            "the glass no longer fades with topBarGlassAlpha",
            source.contains("graphicsLayer { alpha = topBarGlassAlpha }"),
        )
    }

    @Test
    fun `the list lift stays bound to the switch, not to the alpha`() {
        // Animating the tail with the same alpha would change the list's measured
        // height every frame: every row moves, which is worse than the flash.
        assertTrue(
            "topBarTail must read the switch; binding it to an animated alpha jitters " +
                "every row as the glass fades",
            source.contains("topBarTail(if (composerGlassOn) topBarHeightDp else 0.dp)"),
        )
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

    private companion object {
        const val CHAT_SCREEN =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt"
    }
}
