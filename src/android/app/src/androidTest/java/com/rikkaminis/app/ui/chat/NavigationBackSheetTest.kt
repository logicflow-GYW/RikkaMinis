package com.rikkaminis.app.ui.chat

import android.accessibilityservice.AccessibilityService
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rikkaminis.app.R
import com.rikkaminis.app.data.repository.MemoryRepository
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Sends real system back to the Dialog window, not a manually invoked callback. */
@RunWith(AndroidJUnit4::class)
class NavigationBackSheetTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scratch: File
    private var dismisses = 0
    private var saves = 0
    private fun label(id: Int) = context.getString(id)

    @After fun cleanup() { if (::scratch.isInitialized) scratch.deleteRecursively() }

    private fun showMemory() {
        scratch = File(context.cacheDir, "navigation-back-${System.nanoTime()}").apply { mkdirs() }
        val repository = MemoryRepository(scratch)
        val record = MemoryToolRecord(
            title = "Navigation test entry", isWrite = true, preview = "Saved preview",
            output = "OK", writtenContent = "Original saved content", toolName = "memory_write",
        )
        compose.setContent {
            MaterialTheme {
                var open by remember { mutableStateOf(true) }
                Text("Underlying transcript")
                if (open) SessionMemorySheet(
                    memoryRepository = repository, toolRecords = listOf(record),
                    onDismiss = { dismisses++; open = false },
                    onRevokeRecord = { MemoryRepository.EntryMutationResult.NotFound },
                    onSaveRecord = { _, _ -> saves++; MemoryRepository.EntryMutationResult.Success("2026-11-27") },
                )
            }
        }
    }

    private fun systemBack() {
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_BACK,
        ))
        compose.waitForIdle()
    }

    @Test fun system_back_cancels_edit_then_detail_then_list_without_saving() {
        showMemory()
        compose.onNode(hasText("Navigation test entry") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription(label(R.string.memory_action_edit)).performClick()
        compose.onNodeWithText(label(R.string.memory_action_save)).assertIsDisplayed()
        systemBack()
        // A body BackHandler attached to the Activity cannot satisfy this: the
        // Material3 Dialog would otherwise already be hidden at this point.
        compose.onNodeWithContentDescription(label(R.string.memory_action_edit)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, dismisses); assertEquals(0, saves) }
        systemBack()
        compose.onNodeWithText(label(R.string.session_memory_title)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, dismisses); assertEquals(0, saves) }
        systemBack()
        compose.onNodeWithText("Underlying transcript").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, dismisses); assertEquals(0, saves) }
    }

    @Test fun explicit_close_does_not_turn_into_pop_or_save() {
        showMemory()
        compose.onNode(hasText("Navigation test entry") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription(label(R.string.memory_action_edit)).performClick()
        compose.onNodeWithContentDescription(label(R.string.standard_sheet_close)).performClick()
        compose.onNodeWithText("Underlying transcript").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, dismisses); assertEquals(0, saves) }
    }

    @Test fun explicit_save_stays_explicit_and_preserves_the_saved_detail() {
        showMemory()
        compose.onNode(hasText("Navigation test entry") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription(label(R.string.memory_action_edit)).performClick()
        compose.onNodeWithText(label(R.string.memory_action_save)).performClick()
        compose.onNodeWithContentDescription(label(R.string.memory_action_edit)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, dismisses); assertEquals(1, saves) }
        systemBack()
        compose.onNodeWithText(label(R.string.session_memory_title)).assertIsDisplayed()
    }

    @Test fun ordinary_sheet_without_custom_back_keeps_native_system_dismissal() {
        compose.setContent {
            MaterialTheme {
                var open by remember { mutableStateOf(true) }
                Text("Ordinary transcript")
                if (open) StandardChatSheet(title = "Ordinary sheet", onDismiss = { dismisses++; open = false }) {
                    Text("Ordinary sheet body")
                }
            }
        }
        compose.onNodeWithText("Ordinary sheet body").assertIsDisplayed()
        systemBack()
        compose.onNodeWithText("Ordinary transcript").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, dismisses) }
    }
}
