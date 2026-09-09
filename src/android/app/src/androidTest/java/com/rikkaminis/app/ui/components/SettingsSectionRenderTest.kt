package com.rikkaminis.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * On-device render tests for [SettingsSection] and [SettingsRowDivider]:
 * the iOS-style inset grouped settings card. Asserts the uppercase header
 * transform, content rendering, and the divider's presence with a
 * multiline row group.
 */
@RunWith(AndroidJUnit4::class)
class SettingsSectionRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun section_rendersUppercaseTitle() {
        composeRule.setContent {
            MaterialTheme {
                SettingsSection(title = "General") {
                    Text("Row one")
                }
            }
        }
        // SettingsSection uppercases the header — the rendered text must be
        // the uppercase form, not the raw input.
        composeRule.onNodeWithText("GENERAL").assertIsDisplayed()
        composeRule.onNodeWithText("General").assertDoesNotExist()
    }

    @Test
    fun section_rendersContentRows() {
        composeRule.setContent {
            MaterialTheme {
                SettingsSection(title = "Network") {
                    Text("Wi-Fi")
                    Text("Bluetooth")
                }
            }
        }
        composeRule.onNodeWithText("NETWORK").assertIsDisplayed()
        composeRule.onNodeWithText("Wi-Fi").assertIsDisplayed()
        composeRule.onNodeWithText("Bluetooth").assertIsDisplayed()
    }

    @Test
    fun section_rendersWithDividerBetweenRows() {
        composeRule.setContent {
            MaterialTheme {
                SettingsSection(title = "Storage") {
                    Column {
                        Text("Used")
                        SettingsRowDivider(Modifier.testTag("row-divider"))
                        Text("Free")
                    }
                }
            }
        }
        composeRule.onNodeWithText("Used").assertIsDisplayed()
        composeRule.onNodeWithText("Free").assertIsDisplayed()
        composeRule.onNodeWithTag("row-divider").assertExists()
    }

    @Test
    fun section_emptyTitleRenders() {
        composeRule.setContent {
            MaterialTheme {
                SettingsSection(title = "") {
                    Text("No header")
                }
            }
        }
        composeRule.onNodeWithText("No header").assertIsDisplayed()
    }

    @Test
    fun section_supportsCallerModifier() {
        composeRule.setContent {
            MaterialTheme {
                SettingsSection(
                    title = "Padded",
                    modifier = Modifier.testTag("section-root"),
                ) {
                    Text("Inside")
                }
            }
        }
        composeRule.onNodeWithText("PADDED").assertIsDisplayed()
        composeRule.onNodeWithText("Inside").assertIsDisplayed()
    }
}