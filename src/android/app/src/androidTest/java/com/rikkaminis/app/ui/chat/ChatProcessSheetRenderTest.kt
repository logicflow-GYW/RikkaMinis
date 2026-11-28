package com.rikkaminis.app.ui.chat

import android.accessibilityservice.AccessibilityService
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rikkaminis.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native window/gesture coverage. CI compiles these tests; it does not run them. */
@RunWith(AndroidJUnit4::class)
class ChatProcessSheetRenderTest {
    @get:Rule val compose = createComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun label(id: Int) = instrumentation.targetContext.getString(id)
    private val processTitle get() = label(R.string.chat_process_title)
    private val thinkingTitle get() = label(R.string.chat_deep_thinking)
    private val pane get() = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, processTitle)
    private val dismiss get() = SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)
    private fun parent() = compose.onNode(pane, useUnmergedTree = true)
    private fun handle() = compose.onNode(dismiss and hasAnyAncestor(pane), useUnmergedTree = true)
    private fun list() = compose.onNode(hasScrollAction() and hasAnyAncestor(pane), useUnmergedTree = true)
    private fun scrim() = compose.onNode(hasContentDescription(label(R.string.standard_sheet_close)) and hasClickAction())
    private fun toolLabel(index: Int) = "Process test tool $index"
    private var parentDismissed = 0
    private var toolCleared = 0
    private var transcriptTaps = 0

    private fun show(count: Int = 1, thinking: Boolean = false) {
        val blocks = if (thinking) {
            listOf(AssistantBlock("reasoning", "thinking", content = "Selectable reasoning", toolStatus = ToolBlockStatus.SUCCESS))
        } else {
            List(count) { index ->
                AssistantBlock(id = "tool-$index", kind = "tool_use", toolTitle = toolLabel(index),
                    toolName = "file_read", toolArgs = "{}", content = "Tool output", toolStatus = ToolBlockStatus.SUCCESS)
            }
        }
        compose.setContent {
            MaterialTheme {
                var open by remember { mutableStateOf(true) }
                var selectedToolId by remember { mutableStateOf<String?>(null) }
                Box(Modifier.fillMaxSize()) {
                    Text("Transcript underneath")
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxSize(0.15f)
                        .clickable { transcriptTaps++ })
                    if (open) {
                        ChatProcessSheet(
                            messageId = "turn",
                            blocks = blocks,
                            isStreaming = false,
                            onDismiss = { parentDismissed++; open = false },
                            toolActions = { null },
                            selectedToolId = selectedToolId,
                            onSelectTool = { selectedToolId = it },
                            onClearTool = { toolCleared++; selectedToolId = null },
                            onOpenTerminalWithCommand = {},
                            onOpenBrowserForUrl = {},
                        )
                    }
                }
            }
        }
        parent().assertIsDisplayed()
    }

    private fun swipeToolDown() {
        compose.onNodeWithText("RikkaMinis Computer").assertIsDisplayed().performTouchInput {
            swipe(center, center + Offset(0f, 240.dp.toPx()))
        }
        compose.waitForIdle()
    }

    private fun assertParentDismissed() {
        parent().assertDoesNotExist()
        scrim().assertDoesNotExist()
        compose.onNodeWithText("Transcript underneath").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, parentDismissed); assertEquals(0, transcriptTaps) }
    }

    @Test fun long_list_opens_at_latest_and_only_hands_upward_drag_off_at_the_bottom() {
        show(count = 100)
        compose.onNodeWithText(toolLabel(99)).assertIsDisplayed()
        val expanded = parent().getUnclippedBoundsInRoot()
        list().performTouchInput { swipe(percentOffset(0.8f, 0.20f), percentOffset(0.8f, 0.79f), 500L) }
        compose.waitForIdle()
        val axis = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(axis.value() < axis.maxValue())
        val before = axis.value()
        list().performTouchInput { swipe(percentOffset(0.8f, 0.70f), percentOffset(0.8f, 0.55f), 480L) }
        compose.waitForIdle()
        assertTrue(axis.value() > before)
        assertEquals(expanded, parent().getUnclippedBoundsInRoot())
        list().performScrollToIndex(99)
        list().performTouchInput { swipe(percentOffset(0.8f, 0.75f), percentOffset(0.8f, 0.30f)) }
        assertParentDismissed()
    }

    @Test fun tool_detail_swipe_keeps_the_exact_parent_list_position_and_allows_reopen() {
        show(count = 100)
        list().performScrollToIndex(12)
        val row = compose.onNodeWithText(toolLabel(13))
        val before = row.getUnclippedBoundsInRoot()
        repeat(3) {
            row.performClick()
            swipeToolDown()
            parent().assertIsDisplayed()
            assertEquals(before, row.getUnclippedBoundsInRoot())
        }
        compose.runOnIdle { assertEquals(0, parentDismissed); assertEquals(3, toolCleared) }
        handle().performTouchInput { swipe(center, center - Offset(0f, 150.dp.toPx())) }
        assertParentDismissed()
    }

    @Test fun reasoning_reader_has_no_visible_shell_title_and_still_swipes_down_to_list() {
        show(thinking = true)
        compose.onNode(hasText(thinkingTitle) and hasClickAction()).performClick()
        val reader = hasScrollAction() and !hasAnyAncestor(pane)
        compose.waitUntil(5_000) { compose.onAllNodes(reader, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val readerNode = compose.onNode(reader, useUnmergedTree = true).assertIsDisplayed()
        val axis = readerNode.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(0f, axis.value(), 0f)
        compose.onNode(hasText(thinkingTitle) and !hasAnyAncestor(pane), useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(dismiss and !hasAnyAncestor(pane), useUnmergedTree = true).performTouchInput {
            swipe(center, center + Offset(0f, 220.dp.toPx()))
        }
        parent().assertIsDisplayed()
        compose.onNodeWithText(thinkingTitle).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, parentDismissed); assertEquals(1, toolCleared) }
    }

    @Test fun short_list_and_handle_taps_do_not_close_but_short_list_upward_drag_does() {
        show()
        handle().performTouchInput { click() }
        list().performTouchInput { click() }
        parent().assertIsDisplayed()
        list().performTouchInput { swipe(percentOffset(0.8f, 0.75f), percentOffset(0.8f, 0.30f)) }
        assertParentDismissed()
    }

    @Test fun scrim_tap_uses_the_parent_exit_and_does_not_tap_underlying_chat() {
        show()
        val panel = parent().getUnclippedBoundsInRoot()
        val outside = scrim().getUnclippedBoundsInRoot()
        assertEquals(outside.top.value, panel.top.value, 1f)
        assertEquals(0.86f, (panel.bottom - panel.top) / (outside.bottom - outside.top), 0.02f)
        compose.onNodeWithText(processTitle, useUnmergedTree = true).assertDoesNotExist()
        scrim().performTouchInput { click(percentOffset(0.5f, 0.93f)) }
        assertParentDismissed()
    }

    @Test fun scrim_short_drag_rebounds_without_turning_into_a_tap_and_full_drag_closes() {
        show()
        val expanded = parent().getUnclippedBoundsInRoot()
        scrim().performTouchInput {
            down(percentOffset(0.5f, 0.94f))
            moveBy(Offset(0f, -28.dp.toPx()))
            advanceEventTime(200L)
            up()
        }
        assertEquals(expanded, parent().getUnclippedBoundsInRoot())
        scrim().performTouchInput { swipe(percentOffset(0.5f, 0.94f), percentOffset(0.5f, 0.45f)) }
        assertParentDismissed()
    }

    @Test fun reversed_and_cancelled_handle_drag_return_to_the_same_geometry() {
        show()
        val expanded = parent().getUnclippedBoundsInRoot()
        handle().performTouchInput {
            down(center)
            moveBy(Offset(0f, -110.dp.toPx()))
            moveBy(Offset(0f, 120.dp.toPx()))
            up()
        }
        assertEquals(expanded, parent().getUnclippedBoundsInRoot())
        handle().performTouchInput {
            down(center)
            moveBy(Offset(0f, -110.dp.toPx()))
            cancel()
        }
        assertEquals(expanded, parent().getUnclippedBoundsInRoot())
        handle().performSemanticsAction(SemanticsActions.Dismiss)
        assertParentDismissed()
    }

    @Test fun system_back_leaves_detail_then_parent_and_no_other_layer() {
        show()
        compose.onNodeWithText(toolLabel(0)).performClick()
        compose.onNodeWithText("RikkaMinis Computer").assertIsDisplayed()
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitForIdle()
        parent().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, parentDismissed); assertEquals(1, toolCleared) }
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        assertParentDismissed()
    }
}
