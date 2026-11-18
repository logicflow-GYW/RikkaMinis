package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/topbar-occlusion-1118c] Wiring test for the opaque floor under the
 * top-bar glass.
 *
 * The coverage maths is pinned by [TopBarGlassOcclusionTest]; what can still go
 * wrong lives in the composable, and each failure mode has its own arm:
 *
 *  1. the floor is DELETED → the reported flash returns (a zero-alpha
 *     graphicsLayer draws nothing while `topBarTail` has already lifted the rows
 *     behind the bar);
 *  2. the floor is filled with something other than the chat background → the
 *     bar stops being the plain opaque bar it reads as while the glass is still
 *     coming up;
 *  3. the floor is drawn AFTER the glass → it paints over the blur, same visual
 *     result as (2);
 *  4. the floor stops following the bar's size → the corners/insets show raw
 *     content even when the alpha is right;
 *  5. someone "fixes" the row flash by animating `topBarTail` with the same
 *     alpha → the list's measured height changes every frame and every row
 *     jitters (the reason this fix uses a floor instead);
 *  6. the floor box grows its own glass → the floor and the glass must stay
 *     siblings, so the floor box is asserted to carry no glass modifiers;
 *  7. the floor starts deriving its alpha from the glass (the old `1 - a`
 *     complement) → covered by the anchor being the constant-floor call, plus
 *     the coverage assertions in [TopBarGlassOcclusionTest].
 *
 * Blocks are matched by parens, not by a fixed character window: a window that
 * has drifted with the comment above the floor lets a change to that comment
 * satisfy the assertions without the floor itself being there.
 *
 * Read from the repository (walk-up) so Gradle's module `user.dir` and a repo
 * root both resolve.
 */
class TopBarGlassFloorWiringTest {

    private val source: String get() = readRepoFile(CHAT_SCREEN)

    @Test
    fun `the top bar draws an opaque floor under the glass`() {
        val anchor = source.indexOf(FLOOR_CALL)
        assertTrue(
            "the floor is gone — without it a zero-alpha glass layer leaves the " +
                "lifted rows painted raw behind a transparent bar",
            anchor >= 0,
        )
        val floor = enclosingBoxBlock(source, anchor)
        assertTrue(
            "the floor must be filled with the chat background, not left to the parent",
            floor.contains(".background(") && floor.contains("topBarGlassBase"),
        )
        assertTrue(
            "the floor must follow the bar's measured size, or its edges show raw content",
            floor.contains("matchParentSize()"),
        )
    }

    @Test
    fun `the floor is drawn under the glass layer, not over it`() {
        val anchor = source.indexOf(FLOOR_CALL)
        assertTrue("the floor call is missing", anchor >= 0)
        val glass = source.indexOf("hazeEffect(state = hazeState, style = topBarGlassStyle)")
        assertTrue("the top-bar glass layer is gone", glass >= 0)
        val floor = enclosingBoxBlock(source, anchor)
        val floorEnd = source.indexOf(floor) + floor.length
        assertTrue(
            "the floor is drawn after the glass, so it paints over the blur — the bar " +
                "would read as a solid strip and the effect would be invisible",
            floorEnd <= glass,
        )
    }

    @Test
    fun `the floor box carries no glass of its own`() {
        val anchor = source.indexOf(FLOOR_CALL)
        assertTrue("the floor call is missing", anchor >= 0)
        val floor = enclosingBoxBlock(source, anchor)
        assertFalse(
            "the floor box also draws the glass — the floor and the glass must stay " +
                "siblings, otherwise one masks the other",
            floor.contains("hazeEffect(") || floor.contains("graphicsLayer"),
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

    /** The text of the `Box(...)` call enclosing the floor call at [from]. */
    private fun enclosingBoxBlock(source: String, from: Int): String {
        val open = source.lastIndexOf("Box(", from)
        assertTrue("no enclosing Box( before index $from", open >= 0)
        return balancedCall(source, open + "Box".length)
    }

    /**
     * The text from the `(` at [openParen] through its matching `)`, with
     * comments and string/char literals skipped so they cannot fake or break a
     * nesting count.
     */
    private fun balancedCall(source: String, openParen: Int): String {
        var depth = 0
        var i = openParen
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                c == '/' && next == '/' -> while (i < source.length && source[i] != '\n') i++
                c == '/' && next == '*' -> {
                    i += 2
                    while (i < source.length && !(source[i] == '*' && source.getOrNull(i + 1) == '/')) i++
                    i++
                }
                c == '"' || c == '\'' -> i = skipLiteral(source, i, c)
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return source.substring(openParen, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced call starting at index $openParen")
    }

    private fun skipLiteral(source: String, start: Int, terminator: Char): Int {
        val isRaw = start + 2 < source.length &&
            source[start + 1] == terminator &&
            source[start + 2] == terminator
        if (isRaw) {
            val stop = source.indexOf("$terminator$terminator$terminator", start + 3)
            check(stop >= 0) { "unterminated raw literal from index $start" }
            return stop + 3
        }
        var i = start + 1
        while (i < source.length) {
            when (source[i]) {
                '\\' -> i += 2
                terminator -> return i + 1
                else -> i++
            }
        }
        throw AssertionError("unterminated literal from index $start")
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
        const val FLOOR_CALL = "TopBarGlassOcclusion.floorAlpha(topBarGlassAlpha)"
    }
}
