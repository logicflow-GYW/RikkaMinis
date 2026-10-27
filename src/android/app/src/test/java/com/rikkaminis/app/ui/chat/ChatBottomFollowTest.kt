package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B-03 deep-audit-1037] The ONE bottom verdict, pinned at the value level and
 * at the source level.
 *
 * Before the fix the drag-stop handler compared the viewport-TOP row index
 * (`firstVisibleItemIndex`) against `totalItemsCount - 2` with a
 * `firstVisibleItemScrollOffset <= threshold` tail — a formula left over from
 * the reverseLayout era. On the forward list this app actually renders it was
 * wrong in BOTH directions: a multi-row viewport sitting at the true bottom
 * read FALSE (the list stopped auto-following after the user's drag), and a
 * tall last row whose TOP sat at the viewport top read TRUE (a reader mid-item
 * got yanked back). The `nearBottomThresholdPx` knob was also captured in the
 * `isNearBottom` remember key but never read inside its body, so it did
 * nothing on the "last row's bottom edge is on screen" arm.
 *
 * Every expectation below is a literal (evidence discipline): none is derived
 * from the implementation. The two legacy-control tests call a LOCAL replica of
 * the old formula so the fix's discriminating power is visible in the same
 * file — they are controls, not the contract.
 *
 * Source: `src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt`
 */
class ChatBottomFollowTest {

    private val sourcePath = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt"

    /** Viewport end offset used by every geometry below. */
    private val viewportEnd = 1000

    // ── value level: the verdict itself ────────────────────────────────────

    @Test
    fun `a row whose bottom edge is flush with the viewport end is at bottom`() {
        // Row spans 900..1000, viewport ends at 1000 → distance 0.
        assertTrue(isListAtBottom(lastVisibleOffsetPx = 900, lastVisibleSizePx = 100, viewportEndOffsetPx = viewportEnd, thresholdPx = 48))
    }

    @Test
    fun `a row whose bottom edge is within the threshold is at bottom`() {
        // Bottom edge 20px short of the viewport end, threshold 48.
        assertTrue(isListAtBottom(880, 100, viewportEnd, 48))
    }

    @Test
    fun `the threshold is inclusive`() {
        // Bottom edge 48px short of the viewport end, threshold 48.
        assertTrue(isListAtBottom(852, 100, viewportEnd, 48))
    }

    @Test
    fun `one pixel past the threshold is not at bottom`() {
        // Bottom edge 49px short of the viewport end → the gap is real.
        assertFalse(isListAtBottom(851, 100, viewportEnd, 48))
    }

    @Test
    fun `content still below the viewport end is not at bottom`() {
        assertFalse(isListAtBottom(500, 100, viewportEnd, 48))
    }

    @Test
    fun `a tall last row whose top is at the viewport top is not at bottom`() {
        // The old formula's false positive: the row is 5000px tall, its top is
        // at the viewport top, so the reader is at the START of it — nowhere
        // near the list's end.
        assertFalse(isListAtBottom(0, 5000, viewportEnd, 48))
    }

    @Test
    fun `a row bottom already scrolled past the viewport end is never at bottom`() {
        // Bottom edge 1050 > viewport end 1000 → overscrolled past the end;
        // even a huge threshold must not claim "at bottom".
        assertFalse(isListAtBottom(950, 100, viewportEnd, 10_000))
    }

    @Test
    fun `a short list fully on screen is at bottom`() {
        assertTrue(isListAtBottom(0, 1000, viewportEnd, 48))
    }

    @Test
    fun `the threshold decides the verdict - it is consumed, not ignored`() {
        // Same geometry (bottom edge 980, viewport end 1000): threshold 0 must
        // say "not at bottom", threshold 48 must say "at bottom". This is the
        // pair the pre-fix code failed — the knob was captured but never read.
        assertFalse(isListAtBottom(880, 100, viewportEnd, 0))
        assertTrue(isListAtBottom(880, 100, viewportEnd, 48))
    }

    // ── legacy control: the reversed formula, replicated locally ────────────

    /** The pre-fix verdict, verbatim: viewport-TOP index vs `total - 2`. */
    private fun legacyReversedVerdict(
        firstVisibleItemIndex: Int,
        totalItemsCount: Int,
        firstVisibleItemScrollOffsetPx: Int,
        thresholdPx: Int,
    ): Boolean =
        firstVisibleItemIndex >= totalItemsCount - 2 && firstVisibleItemScrollOffsetPx <= thresholdPx

    @Test
    fun `legacy control - at the true bottom with a multi-row viewport it said not-at-bottom`() {
        // 20 rows, the viewport shows rows 15..19 at the true bottom (last row
        // bottom edge flush with the viewport end → distance 0).
        assertFalse(legacyReversedVerdict(15, 20, 0, 48))
        // …while the shared verdict reads the same geometry correctly.
        assertTrue(isListAtBottom(950, 50, viewportEnd, 48))
    }

    @Test
    fun `legacy control - a tall last row at the viewport top it said at-bottom`() {
        // Row 19 (the last of 20) is tall and its top is at the viewport top.
        assertTrue(legacyReversedVerdict(19, 20, 0, 48))
        // The shared verdict correctly says the reader is mid-item.
        assertFalse(isListAtBottom(0, 5000, viewportEnd, 48))
    }

    // ── source contract ────────────────────────────────────────────────────

    @Test
    fun `the bottom verdict has exactly one implementation`() {
        val src = readRepoFile(sourcePath)
        assertEquals(
            "isListAtBottom must be declared exactly once",
            1,
            Regex("""internal fun isListAtBottom\(""").findAll(src).count(),
        )
    }

    @Test
    fun `both consumers call the shared verdict`() {
        val src = readRepoFile(sourcePath)
        assertTrue(
            "the isNearBottom fallback arm must use the shared verdict",
            src.contains("isListAtBottom(last.offset, last.size, viewportEnd, nearBottomThresholdPx.toInt())"),
        )
        assertTrue(
            "the drag-stop verdict must use the shared verdict",
            src.contains("last.offset, last.size, info.viewportEndOffset, nearBottomThresholdPx.toInt(),"),
        )
    }

    @Test
    fun `the reversed formula left no residue`() {
        val src = readRepoFile(sourcePath)
        assertFalse("the old index alias must be gone", src.contains("stoppedIdx"))
        assertFalse("the viewport-top comparison must be gone", src.contains("totalItemsCount - 2"))
        assertFalse("…in either spelling", src.contains("stoppedTotal - 2"))
    }

    @Test
    fun `the threshold knob is consumed at both bottom verdicts`() {
        val src = readRepoFile(sourcePath)
        val consumed = Regex("""nearBottomThresholdPx\.toInt\(\)""").findAll(src).count()
        assertTrue(
            "nearBottomThresholdPx must be read (count=$consumed) at both call sites",
            consumed >= 2,
        )
    }

    private fun readRepoFile(relative: String): String {
        var dir = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}
