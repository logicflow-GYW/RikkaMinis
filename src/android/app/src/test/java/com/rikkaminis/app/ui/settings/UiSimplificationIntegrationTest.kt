package com.rikkaminis.app.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whole-tree source contracts only; gesture feel and Android back dispatch require a device. */
class UiSimplificationIntegrationTest {
    private fun productionRoot(): File {
        System.getProperty("minis.test.sourceRoot")?.let { root ->
            return File(root, "src/android/app/src/main/java/com/rikkaminis/app").also {
                check(it.isDirectory) { "production source root missing: $it" }
            }
        }
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            for (prefix in listOf("src/android/app", "app")) {
                val candidate = File(dir, "$prefix/src/main/java/com/rikkaminis/app")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        error("production source root not found")
    }

    private fun code(file: File): String = file.readText()
        .replace(Regex("(?ms)^\\h*/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^\\h*//[^\\n]*"), "")

    private fun source(relative: String): String {
        val file = File(productionRoot(), relative)
        check(file.isFile) { "production source missing: $relative" }
        return code(file)
    }

    @Test fun `application navigation no longer draws back arrows but web history retains its arrow`() {
        val root = productionRoot()
        val arrow = Regex("Icons\\s*\\.\\s*(?:AutoMirrored\\s*\\.\\s*)?(?:Filled|Default)\\s*\\.\\s*ArrowBack\\b")
        val found = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { arrow.containsMatchIn(code(it)) }
            .map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
        assertEquals("Only web history is a functional backward arrow; application navigation relies on system back",
            listOf("ui/browser/BrowserSheet.kt"), found)
    }

    @Test fun `browser history back keeps its history callback and busy protection`() {
        val browser = source("ui/browser/BrowserSheet.kt")
        val start = browser.indexOf("icon = Icons.AutoMirrored.Filled.ArrowBack")
        assertTrue("browser history button missing", start >= 0)
        val button = browser.substring(start).substringBefore("ToolbarIcon(")
        assertTrue(button.contains("enabled = canGoBack && !isAgentBusy"))
        assertTrue(button.contains("selectedTab?.manager?.goBack()"))
    }

    @Test fun `system permissions page still exits through the supplied callback without a navigation slot`() {
        val screen = source("ui/settings/SystemPermissionsScreen.kt")
        assertTrue(screen.contains("fun SystemPermissionsScreen(onBack: () -> Unit)"))
        assertTrue(Regex("BackHandler\\(onBack\\s*=\\s*onBack\\)").containsMatchIn(screen))
        assertFalse(screen.contains("navigationIcon ="))
        val navigation = source("ui/navigation/AppNavigation.kt")
        val route = navigation.substringAfter("composable(Routes.SYSTEM_PERMISSIONS) {")
            .substringBefore("composable(")
        assertTrue("permissions route must remain registered", route != navigation)
        assertTrue(route.contains("SystemPermissionsScreen("))
        assertTrue(route.contains("onBack = { navController.safePopBackStack() }"))
    }

    @Test fun `only custom thinking rules sort and both inputs use the same persistence path`() {
        val section = source("ui/settings/ThinkingRulesSection.kt")
        val start = section.indexOf("ReorderableColumn(")
        val builtInHeader = section.indexOf("title = stringResource(R.string.thinking_rules_default_rules_count")
        assertTrue("custom rules must precede built-ins", start >= 0 && builtInHeader > start)
        val custom = section.substring(start, builtInHeader)
        assertTrue(custom.contains("list = customIds"))
        assertTrue(custom.contains("onSettle = { fromIndex, toIndex -> moveRule(customIds, fromIndex, toIndex) }"))
        assertTrue(custom.contains("longPressReorder(isDragging, enabled = customIds.size > 1 && !reorderInFlight)"))
        assertTrue(custom.contains(".reorderAccessibilityActions("))
        assertTrue(custom.contains("moveRule(customIds, idx, idx - 1)"))
        assertTrue(custom.contains("moveRule(customIds, idx, idx + 1)"))
        assertTrue(section.contains("val moved = movedThinkingRules(customRulesAndIds, dragIds, fromIndex, toIndex) ?: return false"))
        assertTrue(section.contains("customRulesAndIds = moved"))
        assertTrue(section.contains("providerRepository.reorderThinkingRules(instance.id, moved.second)"))
        val builtIns = section.substring(builtInHeader).substringBefore("title = stringResource(R.string.thinking_rules_add_rule)")
        assertTrue(builtIns.contains("locked = true"))
        assertFalse(builtIns.contains("longPressReorder("))
        assertFalse(section.contains("KeyboardArrowDown"))
    }

    @Test fun `release top bar has no empty overflow while menu visibility stays editable`() {
        val chat = source("ui/chat/ChatScreen.kt")
        val menuStart = chat.indexOf("val visibleCustomEntries = resolveVisibleMenuEntries(")
        assertTrue("visible action calculation must exist", menuStart >= 0)
        val menu = chat.substring(menuStart).substringBefore("expanded = showChatMenu,")
        assertTrue(menu.contains("chatActions::isVisible"))
        assertTrue(menu.contains("visibleCustomEntries.size +"))
        assertTrue(menu.contains("if (BuildConfig.DEBUG) 1 else 0"))
        assertTrue(menu.contains("val hasAnyMenuItems = visibleMenuCount >= 1 && soloCustomKey == null"))
        assertTrue(menu.contains("else if (hasAnyMenuItems) {"))
        assertTrue(menu.contains("IconButton(onClick = { showChatMenu = true })"))
        val settings = source("ui/settings/ChatMenuSettingsScreen.kt")
        assertEquals(2, Regex("\\.filter \\{ it in ChatMenuPrefs\\.DEFAULT_ORDER \\}").findAll(settings).count())
        assertFalse("visibility defaults must not drop configurable rows", settings.contains("?.defaultMenuVisible"))
    }

    @Test fun `agent loop entry keeps the section header and has only the trailing arrow`() {
        val groups = source("ui/settings/ModelGroupsScreen.kt")
        val header = groups.substringAfter("item(\"agent_loop_entry_header\")", "")
            .substringBefore("item(\"agent_loop_entry_row\")")
        assertTrue("the entry card's section title stays",
            header.contains("SectionHeader(text = stringResource(R.string.agent_loop_section_entry_title))"))
        assertTrue(groups.contains("AgentLoopEntryRow(onClick = onAgentLoopClick)"))
        val row = groups.substringAfter("private fun AgentLoopEntryRow(", "")
            .substringBefore("private data class GroupModalityMarker(")
        assertTrue("the full row remains clickable", row.contains(".clickable(onClick = onClick)"))
        val label = row.indexOf("text = stringResource(R.string.agent_loop_section_entry_title)")
        assertTrue("the one icon is the arrow after the label",
            label >= 0 && row.indexOf("imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight") > label)
        assertEquals(1, row.split("Icon(").size - 1)
        assertFalse("no gap for a removed icon", row.contains("Spacer("))
    }

    @Test fun `agent loop screen shows only its app bar title and one compact top gap`() {
        val screen = source("ui/settings/AgentLoopModelsScreen.kt")
        assertEquals(1, Regex("\\bR\\.string\\.agent_loop_section_title\\b").findAll(screen).count())
        assertTrue(screen.contains("title = { Text(stringResource(R.string.agent_loop_section_title)) }"))
        val top = screen.substringAfter("LazyColumn(", "").substringBefore("agentLoopModelsSectionItems(", "")
        assertEquals(1, top.split("Spacer(").size - 1)
        assertTrue(top.contains("Spacer(modifier = Modifier.height(SectionDesign.FirstSectionTopGap))"))
        val sectionStart = screen.substringAfter("private fun LazyListScope.agentLoopModelsSectionItems(", "")
            .substringBefore("if (isEmpty)", "")
        assertTrue("find the section before its first rendered content", sectionStart.contains("val isEmpty ="))
        assertFalse("the content must not add another intro item", sectionStart.contains("item("))
    }

    @Test fun `model entry editor keeps explicit cancel and save rather than treating them as arrows`() {
        val editor = source("ui/settings/ModelEntryDetailScreen.kt")
        assertTrue(editor.contains("fun ModelEntryDetailScreen("))
        assertTrue(editor.contains("navigation = {"))
        assertTrue(editor.contains("R.string.common_cancel"))
        assertTrue(editor.contains("R.string.common_save"))
        assertTrue("cancel remains an action, not a decorative label",
            Regex("MinisTextButton\\(\\s*onClick\\s*=\\s*onBack").containsMatchIn(editor))
    }
}
