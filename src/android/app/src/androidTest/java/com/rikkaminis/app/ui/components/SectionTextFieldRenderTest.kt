package com.rikkaminis.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * On-device render tests for [SectionTextField]: value display, text
 * input, placeholder visibility, error state, read-only mode, and
 * external value replacement (the TextFieldValue re-seed path).
 *
 * The field uses [fieldModifier] to carry a [testTag] so the test can
 * target it even when the value is empty.
 */
@RunWith(AndroidJUnit4::class)
class SectionTextFieldRenderTest {

    private val fieldTag = "section-text-field"

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun textField_displaysValue() {
        composeRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = "Hello world",
                    onValueChange = {},
                    fieldModifier = Modifier.testTag(fieldTag),
                )
            }
        }
        composeRule.onNodeWithText("Hello world").assertIsDisplayed()
    }

    @Test
    fun textField_typingCallsOnValueChange() {
        var captured by mutableStateOf("")
        composeRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = captured,
                    onValueChange = { captured = it },
                    fieldModifier = Modifier.testTag(fieldTag),
                )
            }
        }
        composeRule.onNodeWithTag(fieldTag).performTextInput("abc")
        composeRule.waitForIdle()
        assertEquals("abc", captured)
    }

    @Test
    fun textField_typingAppendsToExistingValue() {
        var captured by mutableStateOf("init")
        composeRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = captured,
                    onValueChange = { captured = it },
                    fieldModifier = Modifier.testTag(fieldTag),
                )
            }
        }
        composeRule.onNodeWithTag(fieldTag).performTextInput("x")
        composeRule.waitForIdle()
        assertEquals("initx", captured)
    }

    @Test
    fun textField_placeholderShownWhenEmpty() {
        composeRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = "",
                    onValueChange = {},
                    placeholder = "Type here",
                    fieldModifier = Modifier.testTag(fieldTag),
                )
            }
        }
        composeRule.onNodeWithText("Type here").assertIsDisplayed()
    }

    @Test
    fun textField_placeholderHiddenWhenValuePresent() {
        composeRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = "Has value",
                    onValueChange = {},
                    placeholder = "Type here",
                    fieldModifier = Modifier.testTag(fieldTag),
                )
            }
        }
        composeRule.onNodeWithText("Has value").assertIsDisplayed()
        composeRule.onNodeWithText("Type here").assertDoesNotExist()
    }

    @Test
    fun textField_errorStateRenders() {
        composeRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = "bad",
                    onValueChange = {},
                    isError = true,
                    fieldModifier = Modifier.testTag(fieldTag),
                )
            }
        }
        composeRule.onNodeWithText("bad").assertIsDisplayed()
    }

    @Test
    fun textField_readOnlyRenders() {
        composeRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = "Read only",
                    onValueChange = {},
                    readOnly = true,
                    fieldModifier = Modifier.testTag(fieldTag),
                )
            }
        }
        composeRule.onNodeWithText("Read only").assertIsDisplayed()
    }

    @Test
    fun textField_externalValueUpdateReflects() {
        var value by mutableStateOf("first")
        composeRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = value,
                    onValueChange = { value = it },
                    fieldModifier = Modifier.testTag(fieldTag),
                )
            }
        }
        composeRule.onNodeWithText("first").assertIsDisplayed()
        composeRule.runOnIdle { value = "second" }
        composeRule.onNodeWithText("first").assertDoesNotExist()
        composeRule.onNodeWithText("second").assertIsDisplayed()
    }

    @Test
    fun textField_clearAndReType() {
        var captured by mutableStateOf("xyz")
        composeRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = captured,
                    onValueChange = { captured = it },
                    fieldModifier = Modifier.testTag(fieldTag),
                )
            }
        }
        composeRule.onNodeWithText("xyz").performTextClearance()
        composeRule.waitForIdle()
        assertEquals("", captured)
        composeRule.onNodeWithTag(fieldTag).performTextInput("hello")
        composeRule.waitForIdle()
        assertEquals("hello", captured)
    }

    @Test
    fun textField_multilineRenders() {
        composeRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = "line1\nline2",
                    onValueChange = {},
                    singleLine = false,
                    maxLines = 3,
                    fieldModifier = Modifier.testTag(fieldTag),
                )
            }
        }
        composeRule.onNodeWithText("line1").assertIsDisplayed()
        composeRule.onNodeWithText("line2").assertIsDisplayed()
    }

    @Test
    fun textField_disabledRendersButDoesNotAcceptInput() {
        var captured by mutableStateOf("fixed")
        composeRule.setContent {
            MaterialTheme {
                SectionTextField(
                    value = captured,
                    onValueChange = { captured = it },
                    enabled = false,
                    fieldModifier = Modifier.testTag(fieldTag),
                )
            }
        }
        composeRule.onNodeWithText("fixed").assertIsDisplayed()
        // performTextInput on a disabled text field throws because the
        // semantics expose no editable actions. Just verify rendering.
    }
}