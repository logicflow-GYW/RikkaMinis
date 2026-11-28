package com.rikkaminis.app.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replay the actual nested-scroll callbacks, with only the anchor animation as a test seam. */
class TopChatSheetGestureTest {
    private class Replay {
        val touch = TopSheetTouch()
        var offset = 0f
        var settled = TopSheetAnchor.Expanded
        val releases = mutableListOf<Float>()
        val connection = TopSheetListScroll(touch, { offset }, { delta ->
            val previous = offset
            offset = (offset + delta).coerceIn(-688f, 0f)
            offset - previous
        }, { velocity ->
            releases += velocity
            settled = topSheetReleaseTarget(offset, velocity, 56f, 125f, touch.releasedNormally)
            offset = if (settled == TopSheetAnchor.Hidden) -688f else 0f
        })

        fun down() = touch.update(pressed = true, normalUp = false)

        fun pre(y: Float, source: NestedScrollSource = NestedScrollSource.UserInput) =
            connection.onPreScroll(Offset(19f, y), source)

        fun post(consumed: Float, leftover: Float, source: NestedScrollSource = NestedScrollSource.UserInput) =
            connection.onPostScroll(Offset(0f, consumed), Offset(19f, leftover), source)

        suspend fun release(y: Float, cancel: Boolean = false): Velocity {
            touch.update(pressed = false, normalUp = !cancel)
            return connection.onPreFling(Velocity(13f, y))
        }
    }

    @Test fun list_keeps_history_scroll_and_reaching_bottom_alone_does_not_own_release() = runBlocking {
        val r = Replay()
        r.down()
        assertEquals(Offset.Zero, r.pre(90f))
        assertEquals(Offset.Zero, r.post(90f, 0f))
        assertEquals(Offset.Zero, r.post(30f, 20f)) // first history item: no opening overshoot
        assertEquals(Offset.Zero, r.pre(-80f))
        assertEquals(Offset.Zero, r.post(-80f, 0f)) // exactly the last item
        assertEquals(Velocity.Zero, r.release(-800f))
        assertTrue(r.releases.isEmpty())
        assertEquals(0f, r.offset, 0f)
    }

    @Test fun leftover_after_a_real_drag_moves_only_y_and_releases_the_same_anchor() = runBlocking {
        val r = Replay()
        r.down()
        assertEquals(Offset.Zero, r.pre(-102f))
        assertEquals(Offset(0f, -70f), r.post(-32f, -70f))
        assertEquals(-70f, r.offset, 0f)
        assertEquals(Velocity.Zero, r.release(0f)) // position threshold still applies at rest
        assertEquals(listOf(0f), r.releases)
        assertEquals(-688f, r.offset, 0f)
        assertTrue(topSheetHasDismissed(r.settled, r.offset, -688f, entered = true, closeRequested = false))
        assertEquals(Velocity.Zero, r.connection.onPostFling(Velocity.Zero, Velocity(0f, -900f)))
        assertEquals(1, r.releases.size)
    }

    @Test fun short_list_at_bottom_can_dismiss_and_only_consumes_movement_to_its_bound() = runBlocking {
        val r = Replay()
        r.down()
        assertEquals(Offset(0f, -688f), r.post(0f, -750f))
        assertFalse(topSheetHasDismissed(r.settled, r.offset, -688f, entered = true, closeRequested = false))
        assertEquals(Velocity(0f, -125f), r.release(-125f))
        assertEquals(TopSheetAnchor.Hidden, r.settled)
    }

    @Test fun reverse_drag_first_returns_the_sheet_then_leaves_the_remaining_delta_to_the_list() = runBlocking {
        val r = Replay()
        r.down()
        r.post(0f, -100f)
        assertEquals(Offset(0f, 100f), r.pre(145f))
        assertEquals(Offset.Zero, r.post(45f, 0f))
        assertEquals(Velocity.Zero, r.release(400f))
        assertEquals(0f, r.offset, 0f)
        assertTrue(r.releases.isEmpty())
    }

    @Test fun fling_and_other_side_effects_neither_move_the_sheet_nor_own_its_release() = runBlocking {
        val r = Replay()
        r.down()
        r.post(-50f, 0f)
        repeat(12) {
            assertEquals(Offset.Zero, r.post(0f, -100f, NestedScrollSource.SideEffect))
        }
        assertEquals(Velocity.Zero, r.release(-1200f))
        assertEquals(Velocity.Zero, r.connection.onPostFling(Velocity.Zero, Velocity(0f, -1200f)))
        assertEquals(0f, r.offset, 0f)
        assertTrue(r.releases.isEmpty())
    }

    @Test fun cancelled_drag_beyond_threshold_returns_instead_of_dismissing() = runBlocking {
        val r = Replay()
        r.down()
        r.post(0f, -97f)
        r.release(0f, cancel = true)
        assertEquals(listOf(0f), r.releases)
        assertEquals(TopSheetAnchor.Expanded, r.settled)
        assertEquals(0f, r.offset, 0f)
    }

    @Test fun short_slow_release_rebounds_without_leaving_an_offset() = runBlocking {
        val r = Replay()
        r.down()
        r.post(0f, -25f)
        assertEquals(Velocity(0f, -30f), r.release(-30f))
        assertEquals(listOf(-30f), r.releases)
        assertEquals(0f, r.offset, 0f)
        assertEquals(TopSheetAnchor.Expanded, r.settled)
    }

    @Test fun unpressed_user_input_like_a_wheel_at_bottom_does_not_drag() = runBlocking {
        val r = Replay()
        assertEquals(Offset.Zero, r.post(0f, -200f))
        assertEquals(Velocity.Zero, r.connection.onPreFling(Velocity(0f, -200f)))
        assertTrue(r.releases.isEmpty())
    }

    @Test fun material_release_checks_boundary_speed_position_and_direction() {
        fun target(y: Float, v: Float) = topSheetReleaseTarget(y, v, 56f, 125f, releasedNormally = true)
        assertEquals(TopSheetAnchor.Expanded, target(0f, -900f))
        assertEquals(TopSheetAnchor.Expanded, target(-55f, -124f))
        assertEquals(TopSheetAnchor.Hidden, target(-55f, -125f))
        assertEquals(TopSheetAnchor.Expanded, target(-55f, 125f))
        assertEquals(TopSheetAnchor.Hidden, target(-56f, 0f))
        assertEquals(TopSheetAnchor.Hidden, target(-56f, 125f))
    }

    @Test fun initial_hidden_and_unfinished_hide_are_not_exit_signals() {
        assertFalse(topSheetHasDismissed(TopSheetAnchor.Hidden, Float.NaN, Float.NaN, false, true))
        assertFalse(topSheetHasDismissed(TopSheetAnchor.Hidden, -688f, -688f, false, false))
        assertFalse(topSheetHasDismissed(TopSheetAnchor.Hidden, -120f, -688f, false, true))
        assertFalse(topSheetHasDismissed(TopSheetAnchor.Expanded, -688f, -688f, true, true))
        assertTrue(topSheetHasDismissed(TopSheetAnchor.Hidden, -688f, -688f, false, true))
        assertTrue(topSheetHasDismissed(TopSheetAnchor.Hidden, -688f, -688f, true, false))
    }
}
