package com.rikkaminis.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rikkaminis.app.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real native sheet tests. Build CI compiles these; a device is needed to run them. */
@RunWith(AndroidJUnit4::class)
class ChatProcessSheetRenderTest {
    @get:Rule val composeRule = createComposeRule()

    private val processTitle: String
        get() = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_process_title)
    private val thinkingTitle: String
        get() = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_deep_thinking)
    private val toolTitle = "Stack test tool"
    private var parentDismissed = 0
    private var toolCleared = 0

    private fun show(kind: String) {
        val block = if (kind == "thinking") {
            AssistantBlock(id = "reasoning", kind = kind, content = "Selectable reasoning", toolStatus = ToolBlockStatus.SUCCESS)
        } else {
            AssistantBlock(id = "tool", kind = "tool_use", toolTitle = toolTitle,
                toolName = "file_read", toolArgs = "{}", content = "Tool output", toolStatus = ToolBlockStatus.SUCCESS)
        }
        composeRule.setContent {
            MaterialTheme {
                var open by remember { mutableStateOf(true) }
                var selectedToolId by remember { mutableStateOf<String?>(null) }
                Text("Transcript underneath")
                if (open) {
                    ChatProcessSheet(
                        messageId = "turn",
                        blocks = listOf(block),
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

    /** Start in the title: not in text selection or a list that can consume the drag. */
    private fun swipeDown(title: String, durationMillis: Long = 180L) {
        composeRule.onNodeWithText(title).performTouchInput {
            swipe(center, center + Offset(0f, 900f), durationMillis = durationMillis)
        }
        composeRule.waitForIdle()
    }

    @Test fun tool_swipe_removes_only_detail_then_parent_swipe_reaches_chat() {
        show("tool")
        composeRule.onNodeWithText(toolTitle).performClick()
        composeRule.onNodeWithText("RikkaMinis Computer").assertIsDisplayed()
        swipeDown("RikkaMinis Computer")
        composeRule.onNodeWithText(processTitle).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, parentDismissed); assertEquals(1, toolCleared) }
        swipeDown(processTitle)
        composeRule.onNodeWithText("Transcript underneath").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, parentDismissed) }
    }

    @Test fun reasoning_swipe_returns_to_process_without_tapping_a_scrim() {
        show("thinking")
        composeRule.onNode(hasText(thinkingTitle) and hasClickAction()).performClick()
        composeRule.onNodeWithText("Selectable reasoning").assertIsDisplayed()
        swipeDown(thinkingTitle, durationMillis = 500L)
        composeRule.onNodeWithText(processTitle).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, parentDismissed); assertEquals(1, toolCleared) }
    }

    @Test fun detail_can_be_reopened_after_swipe_without_reusing_a_hidden_sheet() {
        show("tool")
        repeat(3) {
            composeRule.onNodeWithText(toolTitle).performClick()
            composeRule.onNodeWithText("RikkaMinis Computer").assertIsDisplayed()
            swipeDown("RikkaMinis Computer")
            composeRule.onNodeWithText(processTitle).assertIsDisplayed()
        }
        composeRule.runOnIdle { assertEquals(0, parentDismissed); assertEquals(3, toolCleared) }
    }
}
