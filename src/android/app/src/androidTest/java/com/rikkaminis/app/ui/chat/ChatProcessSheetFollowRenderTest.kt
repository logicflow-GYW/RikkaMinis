package com.rikkaminis.app.ui.chat

import android.accessibilityservice.AccessibilityService
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rikkaminis.app.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real ChatProcessSheet with mutable batches. CI compiles, but DOES NOT RUN, these tests. */
@RunWith(AndroidJUnit4::class)
class ChatProcessSheetFollowRenderTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val title get() = instrumentation.targetContext.getString(R.string.chat_process_title)
    private val pane get() = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)
    private val dismiss get() = SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)
    private fun parent() = compose.onNode(pane, useUnmergedTree = true)
    private fun list() = compose.onNode(hasScrollAction() and hasAnyAncestor(pane), useUnmergedTree = true)
    private fun handle() = compose.onNode(dismiss and hasAnyAncestor(pane), useUnmergedTree = true)
    private fun row(i: Int) = compose.onNodeWithText("Follow tool $i")
    private var blocks by mutableStateOf(emptyList<AssistantBlock>())
    private var streaming by mutableStateOf(true)
    private var open by mutableStateOf(true)
    private var selected by mutableStateOf<String?>(null)
    private var exits = 0

    private fun tool(i: Int) = AssistantBlock(id = "tool-$i", kind = "tool_use", toolTitle = "Follow tool $i",
        toolName = "file_read", toolArgs = "{}", content = "Follow output $i", toolStatus = ToolBlockStatus.SUCCESS)

    private fun show(count: Int = 60, reasoning: Boolean = false) {
        blocks = List(count, ::tool) + if (reasoning) listOf(AssistantBlock(id = "reasoning", kind = "thinking",
            content = "Selectable follow reasoning", toolStatus = ToolBlockStatus.SUCCESS)) else emptyList()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    Text("Follow transcript underneath")
                    if (open) ChatProcessSheet(
                        messageId = "follow-turn", blocks = blocks, isStreaming = streaming,
                        onDismiss = { exits++; open = false }, toolActions = { null },
                        selectedToolId = selected, onSelectTool = { selected = it }, onClearTool = { selected = null },
                        onOpenTerminalWithCommand = {}, onOpenBrowserForUrl = {},
                    )
                }
            }
        }
        parent().assertIsDisplayed()
    }

    private fun append(count: Int, complete: Boolean = false) {
        compose.runOnIdle { blocks = List(count, ::tool); streaming = !complete }
        compose.waitForIdle()
    }

    private fun historySwipe() {
        list().performTouchInput { swipe(percentOffset(0.8f, 0.25f), percentOffset(0.8f, 0.70f), 600L) }
        compose.waitForIdle()
    }

    private fun axis() = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
    private fun firstVisibleTool(): Int = (0 until blocks.count { it.kind == "tool_use" }).first { i ->
        runCatching { row(i).assertIsDisplayed() }.isSuccess
    }
    private fun backDetail() {
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitForIdle()
        parent().assertIsDisplayed()
        assertEquals(0, exits)
    }

    @Test fun real_sheet_follows_three_batches_including_last_batch_with_completion() {
        show(60)
        row(59).assertIsDisplayed()
        for (count in listOf(85, 110, 150)) {
            append(count, complete = count == 150)
            row(count - 1).assertIsDisplayed()
            assertEquals(axis().maxValue(), axis().value(), 0.001f)
        }
        assertEquals(0, exits)
    }

    @Test fun late_first_data_after_empty_mount_still_follows() {
        show(0)
        append(60)
        row(59).assertIsDisplayed()
        append(90, complete = true)
        row(89).assertIsDisplayed()
    }

    @Test fun slight_history_drag_held_during_append_detaches_before_release() {
        show(60)
        val node = list()
        node.performTouchInput {
            down(percentOffset(0.8f, 0.45f))
            moveBy(Offset(0f, 38.dp.toPx()))
            advanceEventTime(100L)
        }
        val key = firstVisibleTool()
        val before = row(key).getUnclippedBoundsInRoot()
        // The next batch is committed while the SAME pointer is still down.
        append(85)
        assertEquals(before, row(key).getUnclippedBoundsInRoot())
        node.performTouchInput { advanceEventTime(300L); up() }
        compose.waitForIdle()
        val released = row(key).getUnclippedBoundsInRoot()
        append(110, complete = true)
        assertEquals(released, row(key).getUnclippedBoundsInRoot())
        assertTrue(axis().value() < axis().maxValue())
        assertEquals(0, exits)
    }

    @Test fun manual_return_to_tail_never_restores_but_parent_reopen_does() {
        show(60)
        historySwipe()
        // A real semantics action, not performScrollToIndex pretending to be a drag.
        list().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 20_000f) }
        compose.waitForIdle()
        row(59).assertIsDisplayed()
        val before = row(59).getUnclippedBoundsInRoot()
        append(90)
        assertEquals(before, row(59).getUnclippedBoundsInRoot())
        handle().performSemanticsAction(SemanticsActions.Dismiss)
        parent().assertDoesNotExist()
        assertEquals(1, exits)
        compose.runOnIdle { open = true }
        row(89).assertIsDisplayed()
        append(120)
        row(119).assertIsDisplayed()
    }

    @Test fun tool_detail_hidden_batches_return_without_jump_then_future_update_follows() {
        show(60)
        val before = row(59).getUnclippedBoundsInRoot()
        row(59).performClick()
        compose.onNodeWithText("RikkaMinis Computer").assertIsDisplayed()
        append(90)
        backDetail()
        assertEquals(before, row(59).getUnclippedBoundsInRoot())
        append(120)
        row(119).assertIsDisplayed()
    }

    @Test fun thinking_detail_hidden_batches_keep_parent_geometry_and_follow_mode() {
        show(60, reasoning = true)
        val thinking = compose.onNode(hasText(instrumentation.targetContext.getString(R.string.chat_deep_thinking)) and hasClickAction())
        val key = firstVisibleTool()
        val before = row(key).getUnclippedBoundsInRoot()
        thinking.performClick()
        compose.runOnIdle { blocks = blocks + List(30) { tool(it + 60) } }
        compose.waitForIdle()
        backDetail()
        assertEquals(before, row(key).getUnclippedBoundsInRoot())
        compose.runOnIdle { blocks = blocks + tool(90) }
        row(90).assertIsDisplayed()
    }

    @Test fun detached_tool_detail_return_remains_detached_after_more_batches() {
        show(60)
        historySwipe()
        val key = firstVisibleTool() + 1
        val before = row(key).getUnclippedBoundsInRoot()
        row(key).performClick()
        append(90)
        backDetail()
        assertEquals(before, row(key).getUnclippedBoundsInRoot())
        append(120)
        assertEquals(before, row(key).getUnclippedBoundsInRoot())
        assertEquals(0, exits)
    }

    @Test fun fling_from_history_cannot_close_but_direct_leftover_upward_drag_can() {
        show(100)
        repeat(3) { historySwipe() }
        list().performTouchInput { swipe(percentOffset(0.8f, 0.85f), percentOffset(0.8f, 0.20f), 100L) }
        compose.waitForIdle()
        parent().assertIsDisplayed()
        assertEquals(0, exits)
        list().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 20_000f) }
        compose.waitForIdle()
        row(99).assertIsDisplayed()
        val node = list()
        node.performTouchInput { down(percentOffset(0.8f, 0.75f)); moveBy(Offset(0f, -100.dp.toPx())) }
        append(130)
        node.performTouchInput { moveBy(Offset(0f, -100.dp.toPx())); up() }
        compose.waitForIdle()
        parent().assertDoesNotExist()
        assertEquals(1, exits)
        compose.onNodeWithText("Follow transcript underneath").assertIsDisplayed()
        compose.onNode(hasContentDescription(instrumentation.targetContext.getString(R.string.standard_sheet_close)) and hasClickAction()).assertDoesNotExist()
    }
}
