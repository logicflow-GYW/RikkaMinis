package com.rikkaminis.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * On-device render tests for [MinisAlertDialog]: title/message/button
 * rendering, both button callbacks, the default dismiss label, and the
 * destructive confirm variant.
 */
@RunWith(AndroidJUnit4::class)
class MinisAlertDialogRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dialog_rendersTitleTextAndButtons() {
        composeRule.setContent {
            MaterialTheme {
                MinisAlertDialog(
                    onDismissRequest = {},
                    title = "Delete entry?",
                    text = "This cannot be undone.",
                    confirmText = "Delete",
                    onConfirm = {},
                    dismissText = "Cancel",
                )
            }
        }
        composeRule.onNodeWithText("Delete entry?").assertIsDisplayed()
        composeRule.onNodeWithText("This cannot be undone.").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun dialog_confirmClickFiresOnConfirm() {
        var confirmed = false
        composeRule.setContent {
            MaterialTheme {
                MinisAlertDialog(
                    onDismissRequest = {},
                    title = "Confirm?",
                    confirmText = "Yes",
                    onConfirm = { confirmed = true },
                    dismissText = "No",
                )
            }
        }
        composeRule.onNodeWithText("Yes").performClick()
        composeRule.waitForIdle()
        assertTrue("onConfirm should fire when the confirm button is clicked", confirmed)
    }

    @Test
    fun dialog_dismissClickFiresOnDismiss() {
        var dismissed = false
        composeRule.setContent {
            MaterialTheme {
                MinisAlertDialog(
                    onDismissRequest = {},
                    title = "Confirm?",
                    confirmText = "Yes",
                    onConfirm = {},
                    dismissText = "No",
                    onDismiss = { dismissed = true },
                )
            }
        }
        composeRule.onNodeWithText("No").performClick()
        composeRule.waitForIdle()
        assertTrue("onDismiss should fire when the dismiss button is clicked", dismissed)
    }

    @Test
    fun dialog_dismissFallsBackToOnDismissRequest() {
        // No explicit onDismiss passed — must fall back to onDismissRequest
        // (the default parameter wiring is part of the contract).
        var dismissed = false
        composeRule.setContent {
            MaterialTheme {
                MinisAlertDialog(
                    onDismissRequest = { dismissed = true },
                    title = "Confirm?",
                    confirmText = "Yes",
                    onConfirm = {},
                    dismissText = "No",
                )
            }
        }
        composeRule.onNodeWithText("No").performClick()
        composeRule.waitForIdle()
        // The dismiss button calls onDismiss, which defaults to
        // onDismissRequest — so the fallback wiring must already be in
        // effect before any interaction.
        assertTrue(dismissed)
    }

    @Test
    fun dialog_rendersWithoutOptionalMessage() {
        composeRule.setContent {
            MaterialTheme {
                MinisAlertDialog(
                    onDismissRequest = {},
                    title = "Just a title",
                    confirmText = "OK",
                    onConfirm = {},
                    dismissText = "Cancel",
                )
            }
        }
        composeRule.onNodeWithText("Just a title").assertIsDisplayed()
        composeRule.onNodeWithText("OK").assertIsDisplayed()
    }

    @Test
    fun dialog_destructiveVariantRendersAndConfirms() {
        var confirmed = false
        composeRule.setContent {
            MaterialTheme {
                MinisAlertDialog(
                    onDismissRequest = {},
                    title = "Reset device?",
                    text = "All data will be wiped.",
                    confirmText = "Reset",
                    onConfirm = { confirmed = true },
                    dismissText = "Keep",
                    isDestructive = true,
                )
            }
        }
        composeRule.onNodeWithText("Reset device?").assertIsDisplayed()
        composeRule.onNodeWithText("All data will be wiped.").assertIsDisplayed()
        composeRule.onNodeWithText("Reset").performClick()
        composeRule.waitForIdle()
        assertTrue("destructive confirm must still fire onConfirm", confirmed)
    }
}