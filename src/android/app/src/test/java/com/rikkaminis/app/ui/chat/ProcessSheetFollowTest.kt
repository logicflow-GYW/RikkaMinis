package com.rikkaminis.app.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The same per-opening policy used by ChatProcessList, plus the real shell callbacks. */
class ProcessSheetFollowTest {
    private fun tail(count: Int = 100, height: Int? = 36) =
        ProcessTailGeometry(count, if (count > 0) "tool_use:tool-${count - 1}" else null, height)

    private fun ProcessSheetFollow.update(
        geometry: ProcessTailGeometry = tail(),
        forward: Boolean = true,
        busy: Boolean = false,
        covered: Boolean = false,
        closing: Boolean = false,
        viewportEnd: Int = 680,
        padding: Int = 20,
    ): ProcessTailTarget? {
        observe(geometry, covered, closing)
        return target(geometry, forward, busy, covered, closing, viewportEnd, padding)
    }

    @Test fun open_empty_then_late_data_and_three_committed_batches_include_final_batch() {
        val f = ProcessSheetFollow()
        assertNull(f.update(tail(0, null)))
        assertEquals(ProcessTailTarget(19, 0), f.update(tail(20)))
        assertNull(f.update(tail(20), forward = false))
        for (count in listOf(21, 29, 40)) {
            assertEquals(ProcessTailTarget(count - 1, 0), f.update(tail(count)))
            assertNull(f.update(tail(count), forward = false))
        }
    }

    @Test fun already_at_end_short_list_and_unchanged_geometry_never_reposition() {
        val f = ProcessSheetFollow()
        assertNull(f.update(tail(2), forward = false))
        repeat(12) { assertNull(f.update(tail(2), forward = true)) }
        assertEquals(ProcessTailTarget(2, 0), f.update(tail(3)))
    }

    @Test fun actual_tail_growth_follows_but_viewport_change_or_shrink_does_not() {
        val f = ProcessSheetFollow()
        assertNull(f.update(tail(), forward = false))
        assertNull(f.update(viewportEnd = 280))
        assertNull(f.update(tail(height = 32)))
        assertEquals(ProcessTailTarget(99, 20), f.update(tail(height = 280), viewportEnd = 280))
    }

    @Test fun oversized_last_row_aligns_bottom_and_does_not_self_trigger_on_remeasurement() {
        val f = ProcessSheetFollow()
        assertEquals(ProcessTailTarget(99, 0), f.update(tail(height = null)))
        assertEquals(ProcessTailTarget(99, 164), f.update(tail(height = 240), viewportEnd = 96))
        assertNull(f.update(tail(height = 240), forward = false, viewportEnd = 96))
        repeat(12) { assertNull(f.update(tail(height = 240), viewportEnd = 96)) }
    }

    @Test fun successful_target_is_not_reissued_even_if_measure_still_reports_overflow() {
        val f = ProcessSheetFollow()
        val target = f.update(tail())!!
        f.positioned(target)
        repeat(20) { assertNull(f.update(tail())) }
        assertEquals(ProcessTailTarget(109, 0), f.update(tail(110)))
    }

    @Test fun busy_coalesces_to_latest_without_changing_owner_and_close_drops_it() {
        val f = ProcessSheetFollow()
        assertNull(f.update(tail(), busy = true))
        assertNull(f.update(tail(130), busy = true))
        assertEquals(ProcessTailTarget(129, 0), f.update(tail(130)))
        assertNull(f.update(tail(131), closing = true))
        assertNull(f.update(tail(131)))
        assertTrue(f.following)
    }

    @Test fun first_consumed_history_pixel_detaches_even_while_tail_is_still_visible() {
        val f = ProcessSheetFollow()
        val touch = TopSheetTouch()
        touch.update(true, false)
        val connection = TopSheetListScroll(touch, { 0f }, { error("must not drag sheet") }, {}, f::detach)
        assertNull(f.update(busy = true))
        assertEquals(Offset.Zero, connection.onPostScroll(Offset(0f, 0.25f), Offset.Zero, NestedScrollSource.UserInput))
        assertFalse(f.following)
        assertNull(f.update(tail(110)))
    }

    @Test fun returning_to_tail_and_stream_completion_never_restore_detached_owner() {
        val f = ProcessSheetFollow()
        assertNull(f.update(forward = false))
        f.detach()
        assertNull(f.update(tail(110), forward = true))
        assertNull(f.update(tail(110), forward = false)) // manual return, NOT a new opening
        assertFalse(f.following)
        assertNull(f.update(tail(120))) // including a final/complete batch
        val reopened = ProcessSheetFollow()
        assertEquals(ProcessTailTarget(119, 0), reopened.update(tail(120)))
    }

    @Test fun unpressed_wheel_keyboard_accessibility_and_focus_history_also_detach() {
        repeat(4) {
            val f = ProcessSheetFollow()
            val connection = TopSheetListScroll(TopSheetTouch(), { 0f }, { error("no physical drag") }, {}, f::detach)
            assertEquals(Offset.Zero, connection.onPostScroll(Offset(0f, 1f), Offset(0f, 5f), NestedScrollSource.UserInput))
            assertFalse(f.following)
        }
    }

    @Test fun tap_short_list_handle_and_inertial_frames_do_not_detach() {
        val f = ProcessSheetFollow()
        val touch = TopSheetTouch()
        val connection = TopSheetListScroll(touch, { 0f }, { 0f }, {}, f::detach)
        touch.update(true, false)
        connection.onPostScroll(Offset.Zero, Offset(0f, 40f), NestedScrollSource.UserInput)
        connection.onPostScroll(Offset(0f, 40f), Offset.Zero, NestedScrollSource.SideEffect)
        connection.onPostScroll(Offset(0f, -40f), Offset.Zero, NestedScrollSource.UserInput)
        connection.dragBy(-40f) // separate handle path
        touch.update(false, true)
        assertTrue(f.following)
        assertEquals(ProcessTailTarget(109, 0), f.update(tail(110)))
    }

    @Test fun detail_updates_and_return_drop_old_work_without_changing_following_mode() {
        val f = ProcessSheetFollow()
        assertNull(f.update(forward = false))
        f.suspendForDetail()
        assertNull(f.update(tail(110), covered = true))
        assertNull(f.update(tail(120), covered = true))
        assertNull(f.update(tail(120))) // returning is not a content revision
        assertTrue(f.following)
        assertEquals(ProcessTailTarget(129, 0), f.update(tail(130)))
    }

    @Test fun detail_open_and_return_before_observer_runs_still_discards_pending_append() {
        val f = ProcessSheetFollow()
        assertNull(f.update(busy = true))
        f.suspendForDetail()
        assertNull(f.update(tail(110)))
        assertEquals(ProcessTailTarget(119, 0), f.update(tail(120)))
    }

    @Test fun detached_detail_return_stays_detached() {
        val f = ProcessSheetFollow()
        f.detach()
        f.suspendForDetail()
        assertNull(f.update(tail(110), covered = true))
        assertNull(f.update(tail(110)))
        assertNull(f.update(tail(120)))
        assertFalse(f.following)
    }

    @Test fun latest_commit_target_is_rechecked_after_a_busy_batch() {
        val f = ProcessSheetFollow()
        assertNull(f.update(tail(120), busy = true))
        assertEquals(ProcessTailTarget(139, 0), f.update(tail(140)))
        f.detach()
        assertNull(f.target(tail(140), true, false, false, false, 680, 20))
    }

    @Test fun cancellation_of_one_scroll_child_does_not_cancel_the_observer_scope() = runBlocking {
        val writes = mutableListOf<Int>()
        val f = ProcessSheetFollow()
        for (count in listOf(100, 110)) {
            coroutineScope {
                launch {
                    val target = f.update(tail(count)) ?: return@launch
                    if (count == 100) throw CancellationException("User mutation won")
                    writes += target.index
                }
            }
        }
        assertEquals(listOf(109), writes)
    }
}
