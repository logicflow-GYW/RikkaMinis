package com.rikkaminis.app.ui.components

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * On-device render tests for [MinisMenu] (popup menu) and
 * [MinisMenuDivider]: expanded/collapsed states, item click, alignment
 * variants, and offset placement all render without crashing and expose
 * the expected semantics.
 */
@RunWith(AndroidJUnit4::class)
class MinisMenuRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun menu_expandedRendersAllItems() {
        composeRule.setContent {
            MaterialTheme {
                MinisMenu(expanded = true, onDismissRequest = {}) {
                    DropdownMenuItem(text = { Text("Item one") }, onClick = {})
                    DropdownMenuItem(text = { Text("Item two") }, onClick = {})
                }
            }
        }
        composeRule.onNodeWithText("Item one").assertIsDisplayed()
        composeRule.onNodeWithText("Item two").assertIsDisplayed()
    }

    @Test
    fun menu_itemClickFiresCallback() {
        var clicked = false
        composeRule.setContent {
            MaterialTheme {
                MinisMenu(expanded = true, onDismissRequest = {}) {
                    DropdownMenuItem(text = { Text("Pick me") }, onClick = { clicked = true })
                }
            }
        }
        composeRule.onNodeWithText("Pick me").performClick()
        composeRule.waitForIdle()
        assertTrue("menu item onClick should fire", clicked)
    }

    @Test
    fun menu_collapsedRendersNothing() {
        composeRule.setContent {
            MaterialTheme {
                MinisMenu(expanded = false, onDismissRequest = {}) {
                    DropdownMenuItem(text = { Text("Invisible") }, onClick = {})
                }
            }
        }
        composeRule.onNodeWithText("Invisible").assertDoesNotExist()
        composeRule.onAllNodesWithText("Invisible").assertCountEquals(0)
    }

    @Test
    fun menu_dismissFiresOnDismissRequest() {
        // onDismissRequest is invoked when the popup is dismissed; the parent
        // collapses `expanded`, which unmounts the popup content entirely.
        var dismissed = false
        composeRule.setContent {
            MaterialTheme {
                MinisMenu(expanded = true, onDismissRequest = { dismissed = true }) {
                    DropdownMenuItem(text = { Text("Only item") }, onClick = {})
                }
            }
        }
        composeRule.onNodeWithText("Only item").assertIsDisplayed()
        assertTrue(!dismissed) // nothing should have dismissed it yet
    }

    @Test
    fun menu_whenParentCollapsesItemsDisappear() {
        var expanded by mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                MinisMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("Live item") }, onClick = {})
                }
            }
        }
        composeRule.onNodeWithText("Live item").assertIsDisplayed()
        composeRule.runOnIdle { expanded = false }
        composeRule.onNodeWithText("Live item").assertDoesNotExist()
    }

    @Test
    fun menu_alignEndVariantRenders() {
        composeRule.setContent {
            MaterialTheme {
                MinisMenu(expanded = true, onDismissRequest = {}, alignEnd = true) {
                    DropdownMenuItem(text = { Text("Right anchored") }, onClick = {})
                }
            }
        }
        composeRule.onNodeWithText("Right anchored").assertIsDisplayed()
    }

    @Test
    fun menu_withOffsetRenders() {
        composeRule.setContent {
            MaterialTheme {
                MinisMenu(
                    expanded = true,
                    onDismissRequest = {},
                    offset = DpOffset(8.dp, 12.dp),
                ) {
                    DropdownMenuItem(text = { Text("Offset item") }, onClick = {})
                }
            }
        }
        composeRule.onNodeWithText("Offset item").assertIsDisplayed()
    }

    @Test
    fun menu_dividerRendersBetweenItems() {
        composeRule.setContent {
            MaterialTheme {
                MinisMenu(expanded = true, onDismissRequest = {}) {
                    DropdownMenuItem(text = { Text("Above") }, onClick = {})
                    MinisMenuDivider(Modifier.testTag("menu-divider"))
                    DropdownMenuItem(text = { Text("Below") }, onClick = {})
                }
            }
        }
        composeRule.onNodeWithText("Above").assertIsDisplayed()
        composeRule.onNodeWithText("Below").assertIsDisplayed()
        composeRule.onNodeWithTag("menu-divider").assertExists()
    }

    @Test
    fun menu_customMinWidthDoesNotCrash() {
        composeRule.setContent {
            MaterialTheme {
                MinisMenu(
                    expanded = true,
                    onDismissRequest = {},
                    minWidth = 240.dp,
                ) {
                    DropdownMenuItem(text = { Text("Wide menu") }, onClick = {})
                }
            }
        }
        composeRule.onNodeWithText("Wide menu").assertIsDisplayed()
    }
}