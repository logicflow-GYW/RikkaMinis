package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [shouldCatchUpAfterOpen] — the bounded post-open catch-up
 * gate (see its KDoc for the 2026-09-13 device forensics: the INITIAL_OPEN
 * snap resolves against the newest row's first layout, which can still grow
 * 20%-95% within 9-150ms, leaving the open "one screen short").
 */
class OpenCatchUpGuardTest {

    private fun decide(
        armed: Boolean = true,
        userEngaged: Boolean = false,
        canScrollForward: Boolean = true,
        isScrollInProgress: Boolean = false,
        rollsUsed: Int = 0,
        maxRolls: Int = 3,
    ) = shouldCatchUpAfterOpen(
        armed = armed,
        userEngaged = userEngaged,
        canScrollForward = canScrollForward,
        isScrollInProgress = isScrollInProgress,
        rollsUsed = rollsUsed,
        maxRolls = maxRolls,
    )

    @Test
    fun `rolls when armed and the clamp is released with nobody engaged`() {
        assertTrue(decide())
    }

    @Test
    fun `never rolls when disarmed`() {
        assertFalse(decide(armed = false))
    }

    @Test
    fun `never rolls once the user engaged - even if everything else allows`() {
        assertFalse(decide(userEngaged = true))
        assertFalse(decide(userEngaged = true, rollsUsed = 0, canScrollForward = true))
    }

    @Test
    fun `never rolls while the clamp is still flush`() {
        assertFalse(decide(canScrollForward = false))
    }

    @Test
    fun `never rolls while a scroll is in progress`() {
        assertFalse(decide(isScrollInProgress = true))
        assertFalse(decide(isScrollInProgress = true, canScrollForward = true))
    }

    @Test
    fun `respects the roll cap`() {
        assertFalse(decide(rollsUsed = 3, maxRolls = 3))
        assertTrue(decide(rollsUsed = 2, maxRolls = 3)) // last allowed roll
        assertFalse(decide(rollsUsed = 4, maxRolls = 3))
    }

    @Test
    fun `sequential walk over a permanently released clamp stops at maxRolls`() {
        var rolls = 0
        repeat(10) {
            if (decide(rollsUsed = rolls)) rolls++
        }
        assertEquals(3, rolls)
    }

    @Test
    fun `single content growth mid-open rolls exactly once then settles`() {
        // open -> snap (flush) -> H1 growth releases clamp -> one roll
        // re-flushes -> nothing more until the next release.
        var rolls = 0
        assertFalse(decide(canScrollForward = false)) // right after the snap
        if (decide(canScrollForward = true)) rolls++ // H1 growth released it
        assertFalse(decide(canScrollForward = false)) // re-flushed
        assertEquals(1, rolls)
    }

    @Test
    fun `user engaging mid-open stops any further rolls`() {
        var rolls = 0
        var engaged = false
        // first release rolls
        if (decide(userEngaged = engaged, canScrollForward = true)) rolls++
        // user drags -> engaged
        engaged = true
        // second release must NOT roll
        if (decide(userEngaged = engaged, canScrollForward = true)) rolls++
        assertEquals(1, rolls)
    }

    // ── [FIX-R4-Q F-12] wiring: the two jump-away entry points must mark ──

    @Test
    fun fabUpTapAndFocusJumpMarkTheUserEngagedBeforeScrollingAway() {
        // [FIX-R4-Q F-12] Both entry points scroll AWAY from the bottom while
        // the open-catchup window may still be armed (<2.5s after opening the
        // session — e.g. a search-result tap into the same session). Without
        // the marking the collector rolls the viewport straight back to the
        // bottom and the jump the user asked for is cancelled.
        val src = readChatScreen()
        assertMarkedBefore(src, "tracedScrollToItem(\"FAB-UP\"")
        assertMarkedBefore(src, "tracedScrollToItem(\"FOCUS-MESSAGE\"")
    }

    /** `openCatchUpUserEngaged = true` must appear in the 1200 chars before
     *  the jump call — i.e. on the code path that leads to the scroll, not
     *  somewhere unrelated in the file. */
    private fun assertMarkedBefore(src: String, marker: String) {
        val at = src.indexOf(marker)
        assertTrue("marker not found: $marker", at >= 0)
        val windowStart = (at - 1200).coerceAtLeast(0)
        assertTrue(
            "engagement marking missing before $marker",
            src.substring(windowStart, at).contains("openCatchUpUserEngaged = true"),
        )
    }

    private fun readChatScreen(): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(
                dir,
                "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt",
            )
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("ChatScreen.kt not found from user.dir=${System.getProperty("user.dir")}")
    }
}
