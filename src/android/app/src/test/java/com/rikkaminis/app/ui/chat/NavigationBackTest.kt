package com.rikkaminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Policy tests execute the production decision point; wiring tests are source-only. */
class NavigationBackTest {
    @Test fun editing_cancels_before_detail_or_sheet_can_pop() {
        assertEquals(MemorySheetBackAction.CancelEdit, memorySheetBackAction(true, true))
        assertEquals(MemorySheetBackAction.CancelEdit, memorySheetBackAction(true, false))
    }

    @Test fun read_only_detail_returns_to_list() {
        assertEquals(MemorySheetBackAction.ShowList, memorySheetBackAction(false, true))
    }

    @Test fun only_list_back_dismisses() {
        assertEquals(MemorySheetBackAction.Dismiss, memorySheetBackAction(false, false))
    }

    private fun source(relative: String): String {
        val prefix = "src/main/java/com/rikkaminis/app/"
        System.getProperty("minis.test.sourceRoot")?.let { root ->
            val file = File(root, "src/android/app/$prefix$relative")
            check(file.isFile) { "Missing source: $file" }
            return stripComments(file.readText())
        }
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            for (module in listOf("src/android/app", "app")) {
                val file = File(dir, "$module/$prefix$relative")
                if (file.isFile) return stripComments(file.readText())
            }
            dir = dir.parentFile
        }
        error("Missing production source: $relative")
    }

    private fun stripComments(text: String): String = text
        .replace(Regex("(?ms)^\\h*/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^\\h*//[^\\n]*"), "")

    @Test fun memory_sheet_uses_production_policy_and_each_action_mutates_only_its_level() {
        val sheet = source("ui/chat/SessionMemorySheet.kt")
        assertTrue(sheet.contains("fun SessionMemorySheet("))
        assertTrue(sheet.contains("onBackPress = {"))
        assertTrue(sheet.contains("memorySheetBackAction(isEditing, mode != MemorySheetMode.List)"))
        assertTrue(sheet.contains("MemorySheetBackAction.CancelEdit -> isEditing = false"))
        assertTrue(sheet.contains("MemorySheetBackAction.ShowList -> mode = MemorySheetMode.List"))
        assertTrue(sheet.contains("MemorySheetBackAction.Dismiss -> onDismiss()"))
        assertTrue(sheet.contains("onDismiss = onDismiss,"))
        assertFalse(sheet.contains("ArrowBack"))
    }

    @Test fun custom_back_is_inside_the_dialog_and_does_not_replace_native_dismissal() {
        val shell = source("ui/chat/StandardChatSheet.kt")
        assertTrue(shell.contains("onBackPress: (() -> Unit)? = null"))
        val modal = shell.indexOf("    ModalBottomSheet(")
        val owner = shell.indexOf("LocalView.current.findViewTreeOnBackPressedDispatcherOwner()")
        val binding = shell.indexOf("LocalOnBackPressedDispatcherOwner provides dialogOwner")
        val handler = shell.indexOf("BackHandler(onBack = onBackPress)")
        assertTrue(modal >= 0 && owner > modal && binding > owner && handler > binding)
        assertTrue(shell.contains("LocalLifecycleOwner provides dialogOwner"))
        assertTrue(shell.contains("shouldDismissOnBackPress = onBackPress == null"))
        assertTrue(shell.contains("onDismissRequest = onDismiss,"))
        assertTrue(shell.contains("skipPartiallyExpanded = true"))
        assertTrue(shell.contains("dragHandle = { CompactDragHandle() }"))
    }

    @Test fun settings_keep_cancel_slots_and_bind_only_a_supplied_system_handler() {
        val settings = source("ui/settings/SettingsComponents.kt")
        assertTrue(settings.contains("BackHandler(enabled = onBack != null) { onBack?.invoke() }"))
        assertTrue(settings.contains("navigation?.invoke()"))
        assertEquals(2, Regex("navigationIcon = navigationSlot").findAll(settings).count())
        assertFalse(settings.contains("ArrowBack"))
    }

    @Test fun existing_inner_flows_keep_directory_and_provider_step_back() {
        val browser = source("ui/sandbox/FileBrowserScreen.kt")
        assertTrue(browser.contains("if (!state.canGoBack || !viewModel.goBack()) onBack()"))
        assertTrue(browser.contains("BackHandler(enabled = true, onBack = handleBack)"))
        val provider = source("ui/settings/AddProviderScreen.kt")
        assertTrue(provider.contains("BackHandler(enabled = step != AddProviderStep.CHOOSE_TYPE) { handleBack() }"))
        assertTrue(provider.contains("step = AddProviderStep.CHOOSE_TYPE"))
        assertTrue(provider.contains("selectedType = null"))
    }

    @Test fun real_browser_history_and_functional_composer_indicator_remain() {
        assertTrue(source("ui/browser/BrowserSheet.kt").contains("Icons.AutoMirrored.Filled.ArrowBack"))
        val composer = source("ui/chat/ChatComposerWidgets.kt")
        assertTrue(composer.contains("Icons.Default.KeyboardArrowUp"))
        assertTrue(composer.contains("thinkingTapTarget(level, requested)"))
    }
}
