package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [meta-audit-1028 B06 F-12] — the footer section of [ChatMenuSettingsScreen]
 * renders `ChatMenuPrefs.settingsPinOrder(prefs)`: every key with `SETTINGS`
 * appended last. The `SETTINGS` row is composed with `draggable = false` (no
 * drag handle, shows a "fixed right" hint), but the drag handler lived on the
 * *other* rows and clamped its landing slot to `order.lastIndex` only — so
 * dragging the entry directly above `SETTINGS` one row down swapped it WITH
 * `SETTINGS`:
 *
 *  1. the settings list then showed `SETTINGS` second-to-last while its own
 *     hint still claimed the footer anchors it last;
 *  2. `ChatMenuPrefs.writePinOrder` persisted that order — the stored order no
 *     longer matched what the list renders;
 *  3. the drawer re-anchors `SETTINGS` last at render time
 *     (`resolvePinnedOrder` → `anchorSettingsLast`), so the drag had **no
 *     effect at all** on the target the user was dragging; the only thing that
 *     visibly moved was the row they were not allowed to grab.
 *
 * The fix is a pure landing-slot function with a `lockedKeys` wall
 * ([chatMenuDragTargetIndex] / [chatMenuDragSwap]). Two layers here, the
 * convention `SkillFileViewerContractTest` uses:
 *  1. the pure math, executed for real (expected values are hardcoded
 *     literals, never derived from the code under test — a mutation cannot
 *     masquerade as PASS);
 *  2. the wiring shape, so the screen cannot keep the old inline math while
 *     the helpers stay green (and vice versa).
 */
class ChatMenuDragReorderTest {

    /** The twelve footer keys in their default resolved order (literals). */
    private val footer = listOf(
        "menu_terminal",
        "menu_browser",
        "menu_chat_files",
        "menu_compact",
        "menu_thinking",
        "menu_session_skills",
        "menu_session_mcps",
        "menu_session_memory",
        "menu_slash_commands",
        "menu_export",
        "footer_token_usage",
        "footer_settings",
    )
    private val wall = setOf("footer_settings")

    // ── ① the wall: SETTINGS can never be swapped out of the last slot ──

    @Test
    fun rowDirectlyAboveSettings_cannotDragIntoIt() {
        // The reported gesture: grab `footer_token_usage` (index 10) and drag
        // one row down. Pre-fix the target was 11 (SETTINGS) and the two
        // swapped; the wall clamps the step to a no-op.
        assertEquals(10, chatMenuDragTargetIndex(footer, 10, 1, wall))
    }

    @Test
    fun longDragDownStopsAtTheRowBeforeTheWall() {
        // A fast fling from index 5 requests the last index; it must land on
        // the last *draggable* slot, not on SETTINGS.
        assertEquals(10, chatMenuDragTargetIndex(footer, 5, 99, wall))
    }

    @Test
    fun stepOverTheWallIsABlockedStep_notAWallSwap() {
        // index 9 + 2 would cross 10 into 11: stop at 10.
        assertEquals(10, chatMenuDragTargetIndex(footer, 9, 2, wall))
        // …and 10 + 1 is the same measurement from the other side.
        assertEquals(10, chatMenuDragTargetIndex(footer, 10, 1, wall))
    }

    @Test
    fun draggingTheLockedRowItselfNeverMovesIt() {
        assertEquals(11, chatMenuDragTargetIndex(footer, 11, -1, wall))
        assertEquals(11, chatMenuDragTargetIndex(footer, 11, -99, wall))
    }

    // ── ② free movement is unchanged above the wall ────────────────────

    @Test
    fun downwardStepMovesOneRow() {
        assertEquals(6, chatMenuDragTargetIndex(footer, 5, 1, wall))
    }

    @Test
    fun upwardStepMovesOneRow() {
        assertEquals(4, chatMenuDragTargetIndex(footer, 5, -1, wall))
    }

    @Test
    fun topAndBottomClamp_insideTheDraggableRun() {
        assertEquals(0, chatMenuDragTargetIndex(footer, 3, -10, wall))
        assertEquals(10, chatMenuDragTargetIndex(footer, 3, 10, wall))
        // index 10 upward is inside the run, so it moves freely.
        assertEquals(0, chatMenuDragTargetIndex(footer, 10, -99, wall))
    }

    @Test
    fun zeroDeltaIsANoOp() {
        assertEquals(7, chatMenuDragTargetIndex(footer, 7, 0, wall))
    }

    @Test
    fun outOfRangeIndexIsANoOp() {
        assertEquals(-1, chatMenuDragTargetIndex(footer, -1, 1, wall))
        assertEquals(12, chatMenuDragTargetIndex(footer, 12, -1, wall))
    }

    @Test
    fun emptyWallKeepsThePreFixBehaviour() {
        // Sections A/C pass the default empty set; this pins that the new
        // parameter is the only difference (the menu/attach lists must keep
        // swapping into their own last slot).
        assertEquals(11, chatMenuDragTargetIndex(footer, 10, 1, emptySet()))
        assertEquals(11, chatMenuDragTargetIndex(footer, 0, 99, emptySet()))
    }

    // ── ③ the commit is the visible order ──────────────────────────────

    @Test
    fun swapExchangesTheTwoSlots() {
        assertEquals(
            listOf("menu_terminal", "menu_browser", "menu_chat_files"),
            chatMenuDragSwap(listOf("menu_terminal", "menu_chat_files", "menu_browser"), 1, 2),
        )
    }

    @Test
    fun multiRowJumpSwapsTheTwoSlots_itDoesNotInsert() {
        // A fast fling can ask for a landing slot several rows away. The
        // pre-fix handler exchanged the two slots; an insert-move is a
        // DIFFERENT list (it shifts the rows in between), so the pure swap is
        // pinned here — a refactor that reorders differently is a behaviour
        // change, not a rewrite of the same thing.
        assertEquals(
            listOf("menu_chat_files", "menu_browser", "menu_terminal", "menu_compact"),
            chatMenuDragSwap(footer.take(4), 0, 2),
        )
    }

    @Test
    fun noOpSwapReturnsTheSameListInstance() {
        // A caller must not be able to persist a "reordered" list for a drag
        // that never landed.
        assertSame(footer, chatMenuDragSwap(footer, 4, 4))
        assertSame(footer, chatMenuDragSwap(footer, 4, 99))
        assertSame(footer, chatMenuDragSwap(footer, -1, 4))
    }

    @Test
    fun committedOrderAfterATheoreticalDrag_keepsSettingsLast() {
        // Simulate the drag loop the gesture runs (delta = ±1 per step) for a
        // row trying to walk to the very bottom, then assert the committed
        // list is still the list the screen renders (SETTINGS last) and that
        // the dragged key is now the last *draggable* entry.
        var order = footer
        var index = 0 // menu_terminal
        repeat(12) {
            val target = chatMenuDragTargetIndex(order, index, 1, wall)
            if (target == index) return@repeat
            order = chatMenuDragSwap(order, index, target)
            index = target
        }
        assertEquals(
            listOf(
                "menu_browser",
                "menu_chat_files",
                "menu_compact",
                "menu_thinking",
                "menu_session_skills",
                "menu_session_mcps",
                "menu_session_memory",
                "menu_slash_commands",
                "menu_export",
                "footer_token_usage",
                "menu_terminal",
                "footer_settings",
            ),
            order,
        )
        // and the same walk pre-fix (no wall) would have buried SETTINGS:
        var legacy = footer
        var legacyIndex = 0
        repeat(12) {
            val target = chatMenuDragTargetIndex(legacy, legacyIndex, 1, emptySet())
            if (target == legacyIndex) return@repeat
            legacy = chatMenuDragSwap(legacy, legacyIndex, target)
            legacyIndex = target
        }
        assertEquals("footer_settings", legacy[10])
        assertEquals("menu_terminal", legacy[11])
    }

    // ── ④ wiring shape (the screen must use the pure math) ─────────────

    private val screen: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/settings/ChatMenuSettingsScreen.kt")
    }

    private fun code(): String = screen.lines()
        // Comment anchors rot; the wiring assertions must judge code only.
        .filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .map { it.substringBefore("//") }
        .joinToString("\n")

    @Test
    fun footerPassesSettingsAsTheWall() {
        assertTrue(
            "the footer section must declare SETTINGS a wall; draggable = !isSettings only " +
                "prevents grabbing that row, it does not stop a neighbour swapping with it",
            code().contains("lockedKeys = setOf(ChatMenuPrefs.SETTINGS),"),
        )
    }

    @Test
    fun dragHandlerUsesThePureDecision() {
        val c = code()
        assertTrue("handler must ask the pure function", c.contains("chatMenuDragTargetIndex("))
        assertTrue("handler must commit the pure swap", c.contains("chatMenuDragSwap(currentOrder"))
        assertTrue(
            "the pre-fix inline clamp must be gone — with it back the wall is bypassed",
            !c.contains("(currentIndex + delta).coerceIn(0, currentOrder.lastIndex)"),
        )
    }

    @Test
    fun handlerReadsLockedKeysThroughRememberUpdatedState() {
        // pointerInput freezes captured locals at launch; a fresh
        // `setOf(...)` per recomposition must not leave a stale set behind.
        assertTrue(
            code().contains("val currentLockedKeys by rememberUpdatedState(lockedKeys)"),
        )
    }

    private fun readRepoFile(relative: String): String {
        val cwd = System.getProperty("user.dir") ?: "."
        var dir: File? = File(cwd).absoluteFile
        repeat(6) {
            val candidate = dir ?: return@repeat
            val f = File(candidate, relative)
            if (f.isFile) return f.readText()
            dir = candidate.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from user.dir=$cwd")
    }
}
