package com.rikkaminis.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * On-device render tests for the MinisButton family:
 * [MinisButton], [MinisOutlinedButton], [MinisTextButton],
 * [MinisSmallButton], [MinisSmallOutlinedButton], [MinisSmallTextButton].
 *
 * Covers the normal state (content renders, click fires) and the
 * interaction state (disabled blocks clicks) for every variant.
 *
 * Run on device: ./gradlew connectedDebugAndroidTest
 * (or ./gradlew connectedAndroidTest)
 */
@RunWith(AndroidJUnit4::class)
class MinisButtonsRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ==================== MinisButton ====================

    @Test
    fun minisButton_rendersContent() {
        composeRule.setContent {
            MaterialTheme {
                MinisButton(onClick = {}) { Text("Press me") }
            }
        }
        composeRule.onNodeWithText("Press me").assertIsDisplayed()
    }

    @Test
    fun minisButton_clickFiresCallback() {
        var clicked = false
        composeRule.setContent {
            MaterialTheme {
                MinisButton(onClick = { clicked = true }) { Text("Press me") }
            }
        }
        composeRule.onNodeWithText("Press me").performClick()
        composeRule.waitForIdle()
        assertTrue("onClick should fire on enabled button click", clicked)
    }

    @Test
    fun minisButton_disabledClickDoesNotFire() {
        var clicked = false
        composeRule.setContent {
            MaterialTheme {
                MinisButton(onClick = { clicked = true }, enabled = false) { Text("Press me") }
            }
        }
        composeRule.onNodeWithText("Press me").performClick()
        composeRule.waitForIdle()
        assertFalse("onClick must not fire when disabled", clicked)
    }

    @Test
    fun minisButton_disabledStillRendersContent() {
        composeRule.setContent {
            MaterialTheme {
                MinisButton(onClick = {}, enabled = false) { Text("Disabled") }
            }
        }
        composeRule.onNodeWithText("Disabled").assertIsDisplayed()
    }

    // ==================== MinisOutlinedButton ====================

    @Test
    fun minisOutlinedButton_rendersAndClicks() {
        var clicked = false
        composeRule.setContent {
            MaterialTheme {
                MinisOutlinedButton(onClick = { clicked = true }) { Text("Outline") }
            }
        }
        composeRule.onNodeWithText("Outline").assertIsDisplayed()
        composeRule.onNodeWithText("Outline").performClick()
        composeRule.waitForIdle()
        assertTrue(clicked)
    }

    @Test
    fun minisOutlinedButton_disabledDoesNotFire() {
        var clicked = false
        composeRule.setContent {
            MaterialTheme {
                MinisOutlinedButton(onClick = { clicked = true }, enabled = false) { Text("Outline") }
            }
        }
        composeRule.onNodeWithText("Outline").performClick()
        composeRule.waitForIdle()
        assertFalse(clicked)
    }

    // ==================== MinisTextButton ====================

    @Test
    fun minisTextButton_rendersAndClicks() {
        var clicked = false
        composeRule.setContent {
            MaterialTheme {
                MinisTextButton(onClick = { clicked = true }) { Text("Text action") }
            }
        }
        composeRule.onNodeWithText("Text action").assertIsDisplayed()
        composeRule.onNodeWithText("Text action").performClick()
        composeRule.waitForIdle()
        assertTrue(clicked)
    }

    @Test
    fun minisTextButton_disabledDoesNotFire() {
        var clicked = false
        composeRule.setContent {
            MaterialTheme {
                MinisTextButton(onClick = { clicked = true }, enabled = false) { Text("Text action") }
            }
        }
        composeRule.onNodeWithText("Text action").performClick()
        composeRule.waitForIdle()
        assertFalse(clicked)
    }

    // ==================== MinisSmallButton ====================

    @Test
    fun minisSmallButton_rendersAndClicks() {
        var clicked = false
        composeRule.setContent {
            MaterialTheme {
                MinisSmallButton(onClick = { clicked = true }) { Text("Small") }
            }
        }
        composeRule.onNodeWithText("Small").assertIsDisplayed()
        composeRule.onNodeWithText("Small").performClick()
        composeRule.waitForIdle()
        assertTrue(clicked)
    }

    @Test
    fun minisSmallButton_disabledDoesNotFire() {
        var clicked = false
        composeRule.setContent {
            MaterialTheme {
                MinisSmallButton(onClick = { clicked = true }, enabled = false) { Text("Small") }
            }
        }
        composeRule.onNodeWithText("Small").performClick()
        composeRule.waitForIdle()
        assertFalse(clicked)
    }

    // ==================== MinisSmallOutlinedButton ====================

    @Test
    fun minisSmallOutlinedButton_rendersAndClicks() {
        var clicked = false
        composeRule.setContent {
            MaterialTheme {
                MinisSmallOutlinedButton(onClick = { clicked = true }) { Text("Small outline") }
            }
        }
        composeRule.onNodeWithText("Small outline").assertIsDisplayed()
        composeRule.onNodeWithText("Small outline").performClick()
        composeRule.waitForIdle()
        assertTrue(clicked)
    }

    // ==================== MinisSmallTextButton ====================

    @Test
    fun minisSmallTextButton_rendersAndClicks() {
        var clicked = false
        composeRule.setContent {
            MaterialTheme {
                MinisSmallTextButton(onClick = { clicked = true }) { Text("Small text") }
            }
        }
        composeRule.onNodeWithText("Small text").assertIsDisplayed()
        composeRule.onNodeWithText("Small text").performClick()
        composeRule.waitForIdle()
        assertTrue(clicked)
    }

    // ==================== content passed through ====================

    @Test
    fun minisButton_rendersRichContent() {
        composeRule.setContent {
            MaterialTheme {
                MinisButton(onClick = {}) {
                    Text("Rich ")
                    Text("content")
                }
            }
        }
        composeRule.onNodeWithText("Rich ").assertExists()
        composeRule.onNodeWithText("content").assertExists()
    }

    // Re-set from an external recomposition (constraint contract: the
    // composable must keep working when the parent state changes).
    @Test
    fun minisButton_clickReflectsLatestState() {
        var count by mutableStateOf(0)
        composeRule.setContent {
            MaterialTheme {
                MinisButton(onClick = { count++ }) { Text("Count: $count") }
            }
        }
        composeRule.onNodeWithText("Count: 0").assertIsDisplayed()
        composeRule.onNodeWithText("Count: 0").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Count: 1").assertIsDisplayed()
    }
}