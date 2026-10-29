package com.rikkaminis.app.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R4-V F-08] Who owns a drag inside the full-screen gallery.
 *
 * The defect: `GalleryPage` hung a bare `detectTransformGestures` on the image
 * at every zoom level. That detector consumes every position change past touch
 * slop, so a single-finger horizontal swipe never reached `HorizontalPager` —
 * paging was dead while the picture was at 1×, and the pan it swallowed was
 * thrown away (the old lambda only applied pan when `scale > 1f`).
 *
 * Two halves are pinned here:
 *  - the DECISION ([galleryPageOwnsDrag]) as a literal table, because a gesture
 *    recogniser cannot run off-device;
 *  - the WIRING (source shape), because the decision is worthless if the
 *    pointer loop consumes before consulting it, or if a future edit puts the
 *    recogniser back.
 */
class GalleryGestureOwnershipTest {

    private companion object {
        const val VIEWER =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/components/ImageGalleryViewer.kt"
    }

    // ── the decision table ───────────────────────────────────────────────

    @Test
    fun `only a magnified picture or a pinch owns the drag`() {
        // literal expectations — at rest the pager must keep the swipe
        assertEquals(false, galleryPageOwnsDrag(scale = 1f, pressedPointers = 1))
        assertEquals(false, galleryPageOwnsDrag(scale = 1f, pressedPointers = 0))
        // a pinch (2+ fingers) is ours even before the scale has moved
        assertEquals(true, galleryPageOwnsDrag(scale = 1f, pressedPointers = 2))
        // any magnification owns it
        assertEquals(true, galleryPageOwnsDrag(scale = 1.01f, pressedPointers = 1))
        assertEquals(true, galleryPageOwnsDrag(scale = 2.5f, pressedPointers = 1))
        assertEquals(true, galleryPageOwnsDrag(scale = 8f, pressedPointers = 3))
    }

    // ── the wiring ───────────────────────────────────────────────────────

    @Test
    fun `the swallowing gesture detector is gone`() {
        val src = readRepoFile(VIEWER)
        assertFalse(
            "detectTransformGestures consumes every drag past slop — it must not come back",
            src.contains("detectTransformGestures {") || src.contains("detectTransformGestures("),
        )
        assertTrue(
            "the page must run its own awaitEachGesture loop",
            src.contains("awaitEachGesture {"),
        )
        assertTrue(
            "the pager must still be there (this test is the pager's only voice)",
            src.contains("HorizontalPager("),
        )
    }

    @Test
    fun `the pointer loop consults the decision before consuming`() {
        val src = readRepoFile(VIEWER)
        // The ownership decision must be the IF, not a sibling call: a bare
        // `galleryPageOwnsDrag(...)` that nobody branches on would still let an
        // unguarded consume re-create the defect.
        val guard = src.indexOf("if (galleryPageOwnsDrag(scale, pressed))")
        assertTrue("the pointer loop must branch on galleryPageOwnsDrag(...)", guard > 0)

        val consume = src.indexOf("it.positionChanged()) it.consume()")
        assertTrue("the loop must consume the drag it owns", consume > 0)
        assertTrue(
            "consumption must sit AFTER the ownership decision, inside its branch",
            consume > guard && consume - guard < 900,
        )
        assertEquals(
            "exactly one consumption site — a second unguarded one re-creates the defect",
            1,
            Regex(Regex.escape("it.consume()")).findAll(src).count(),
        )
    }

    // ── helper ───────────────────────────────────────────────────────────

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}
