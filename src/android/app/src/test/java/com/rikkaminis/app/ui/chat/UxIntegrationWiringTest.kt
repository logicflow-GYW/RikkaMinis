package com.rikkaminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source wiring only. These assertions are not a Compose/GPU/visual performance test. */
class UxIntegrationWiringTest {
    private fun source(path: String): String {
        val explicitRoot = System.getProperty("minis.test.sourceRoot")
        if (explicitRoot != null) {
            val file = File(explicitRoot, "src/android/app/src/main/java/com/rikkaminis/app/$path")
            check(file.isFile) { "explicit source not found: $file" }
            return withoutComments(file.readText())
        }
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            for (prefix in listOf("src/android/app", "app")) {
                val file = File(dir, "$prefix/src/main/java/com/rikkaminis/app/$path")
                if (file.isFile) return withoutComments(file.readText())
            }
            dir = dir.parentFile
        }
        error("production source not found: $path")
    }

    private fun withoutComments(text: String): String = text
        .replace(Regex("(?ms)^\\h*/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^\\h*//[^\\n]*"), "")

    /** Same walk-up contract as [source], for a file under `src/main/res`. */
    private fun resource(relative: String): String {
        System.getProperty("minis.test.sourceRoot")?.let { root ->
            return File(root, "src/android/app/src/main/res/$relative").readText()
        }
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            for (prefix in listOf("src/android/app", "app")) {
                val file = File(dir, "$prefix/src/main/res/$relative")
                if (file.isFile) return file.readText()
            }
            dir = dir.parentFile
        }
        error("production resource not found: $relative")
    }

    @Test fun `compact display is a projection not a scroll or settlement decision`() {
        val screen = source("ui/chat/ChatScreen.kt")
        val row = screen.substringAfter("is FlatChatItem.AssistantMessageItem -> {")
        assertTrue(row.contains("AssistantMessageView("))
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        val view = ui.substringAfter("internal fun AssistantMessageView(").substringBefore("internal fun BoundsTrackedBlock(")
        assertTrue(view.contains("val compactProcess = processFoldEnabled && message.role == \"assistant\""))
        assertFalse(view.contains("ChatProcessFoldPlanner"))
        assertFalse(view.contains("isBrowsingHistory"))
        assertFalse(view.contains("requestScrollToItem("))
        assertFalse(view.contains("onFoldAnchor"))
        assertFalse(screen.contains("collapseAllEpoch"))
    }

    @Test fun `process status is a sibling below the identity rather than a header column`() {
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        val render = ui.substringAfter("if (message.role != \"system\") {").substringBefore("// Text and notices")
        assertTrue(render.contains("AssistantHeader()\n"))
        assertTrue(render.indexOf("ChatProcessEntry(") > render.indexOf("AssistantHeader()"))
        assertTrue(render.contains("isExpanded = isProcessExpanded"))
    }

    @Test fun `tool shimmer is an isolated sibling and never rerecords static content`() {
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        val pill = ui.substringAfter("internal fun ToolCallPill(").substringBefore("internal fun ToolCallRunGroup(")
        val overlay = pill.substringAfter("if (shimmerTick != null) {").substringBefore("MinisMenu(")
        assertTrue(pill.contains("val shimmerTick = if (isRunning) rememberDecorativeTick() else null"))
        assertTrue(overlay.contains("Modifier.matchParentSize()"))
        val layer = overlay.indexOf(".animationLayer()")
        val cache = overlay.indexOf(".drawWithCache {")
        val draw = overlay.indexOf("onDrawBehind {")
        val tick = overlay.indexOf("shimmerTick.value")
        assertTrue(layer >= 0 && cache > layer && draw > cache && tick > draw)
        assertTrue(pill.indexOf(".combinedClickable(") < pill.indexOf("if (shimmerTick != null) {"))
        assertFalse(overlay.contains("drawContent()"))
        assertFalse(overlay.contains(".clickable("))
        assertFalse(overlay.contains(".pointerInput("))
        assertFalse(pill.contains("rememberInfiniteTransition("))
        assertFalse(pill.contains("animateContentSize("))
    }

    @Test fun `shared spinners preserve progress semantics and full viewer dimensions`() {
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        assertEquals(3, Regex("DecorativeSpinner\\(").findAll(ui).count())
        assertFalse(ui.contains("CircularProgressIndicator("))
        assertTrue(ui.contains("DecorativeSpinner(color = textColor, modifier = Modifier.padding(24.dp).size(40.dp))"))
        val decorative = source("ui/components/DecorativeAnimation.kt")
        assertTrue(decorative.substringAfter("fun DecorativeSpinner(").contains(".progressSemantics()"))
    }

    @Test fun `full-content entry exists only after the thinking body is expanded`() {
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        val start = ui.indexOf("internal fun ThinkingBlock(")
        val block = ui.substring(start, ui.indexOf("private fun ThinkingFullContentDialog(", start))
        val occurrences = Regex("R\\.string\\.thinking_view_full").findAll(block).toList()
        // 1) >100K header entry (inline body can never render), 2) expanded-body link.
        assertEquals(2, occurrences.size)
        val expand = block.indexOf("visibleState = thinkingBodyVisible")
        assertTrue(block.contains("MutableTransitionState(expanded && !overHardCap)"))
        assertTrue(block.contains("thinkingBodyVisible.targetState = expanded && !overHardCap"))
        assertFalse(block.contains("visible = expanded && !overHardCap"))
        assertTrue(expand > 0)
        assertTrue(occurrences[1].range.first > expand)
        assertTrue(block.substring(expand).contains("if (!isStreaming && isTruncated)"))
        // The collapsed path must not add a row after the header.
        assertFalse(block.substring(0, expand).contains("MinisTextButton("))
    }

    @Test fun `process folding defaults to on without overriding a saved choice`() {
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        assertTrue(ui.contains("processFoldEnabled: Boolean,"))
        assertTrue(ui.contains("if (compactProcess && block.isChatProcessBlock()) return@forEachIndexed"))
        val settings = source("ui/settings/AppearanceScreen.kt")
        assertTrue(settings.contains("const val KEY_TOOL_FOLD_ENABLED = \"chat.toolFoldEnabled\""))
        assertTrue(settings.contains("const val DEFAULT_TOOL_FOLD_ENABLED = true"))
        assertTrue(settings.contains("prefs.getBoolean(KEY_TOOL_FOLD_ENABLED, DEFAULT_TOOL_FOLD_ENABLED)"))
        assertTrue(settings.contains("mutableStateOf(readToolFoldEnabled(prefs))"))
        assertTrue(settings.contains("KEY_TOOL_FOLD_ENABLED -> toolFoldEnabled = readToolFoldEnabled(prefs)"))
        assertTrue(settings.contains("putBoolean(KEY_TOOL_FOLD_ENABLED, it)"))
        val config = source("config/ConfigBuiltins.kt").substringAfter("path = \"chat.toolFoldEnabled\"")
            .substringBefore("\n            )")
        assertTrue(config.contains("key = com.rikkaminis.app.ui.settings.KEY_TOOL_FOLD_ENABLED"))
        assertTrue(config.contains("defaultValue = com.rikkaminis.app.ui.settings.DEFAULT_TOOL_FOLD_ENABLED"))
        // The screen must own the live value so a switch flip updates mounted rows.
        val screen = source("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("mutableStateOf(readToolFoldEnabled(appearancePrefs))"))
        assertTrue(Regex("KEY_TOOL_FOLD_ENABLED ->\\s*processFoldEnabled = readToolFoldEnabled\\(sp\\)").containsMatchIn(screen))
        assertTrue(screen.contains("processFoldEnabled = processFoldEnabled,"))
    }

    @Test fun `both modes preserve text ordering and the original tool actions`() {
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        val aggregate = ui.substringAfter("internal fun AssistantMessageView(").substringBefore("internal fun BoundsTrackedBlock(")
        val loop = aggregate.indexOf("message.toolBlocks.forEachIndexed")
        val guard = aggregate.indexOf("if (compactProcess && block.isChatProcessBlock()) return@forEachIndexed")
        val keyedBlock = aggregate.indexOf("key(message.id, block.id)")
        assertTrue(loop >= 0 && guard > loop && keyedBlock > guard)
        assertTrue(aggregate.contains("FallbackInfoBlock(block, onRevert = onRevert)"))
        assertTrue(aggregate.contains("InlineErrorBanner(error = message.error"))
        assertTrue(aggregate.contains("if (!hasAnyTextBlock && message.content.isNotEmpty())"))
        assertTrue(aggregate.contains("val actions = toolPillActions(block)"))
        val screen = source("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("toolPillActions = { block -> processToolActions(item.message, block) }"))
        assertTrue(screen.contains("toolActions = { block ->\n                                processToolActions(processMessage, block)"))
    }

    @Test fun `detailed mode keeps the original reasoning card`() {
        // Regression (2026-11-26 user report: "启动那个折叠的，思考胶囊就变回原版
        // 没有背景了"). The segment renderer used to pass inProcessSegment=true and
        // strip the card, so turning folding ON silently changed the look of
        // every reasoning block. The reasoning is a member of the run for
        // ORDERING purposes only — its card is presentation the user already had.
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        assertFalse(ui.contains("inProcessSegment"))
        val thinking = ui.substringAfter("internal fun ThinkingBlock(").substringBefore("internal fun ChatProcessThinkingText(")
        assertTrue(thinking.contains(".background(thinkingBlue.copy(alpha = 0.06f), RoundedCornerShape(12.dp))"))
        assertTrue(thinking.contains(".border(0.5.dp, thinkingBlue.copy(alpha = 0.15f), RoundedCornerShape(12.dp))"))
        // The card must not be behind a condition that a fold mode can flip.
        assertFalse(thinking.contains("if (segment"))
    }

    @Test fun `legacy floating bar and its space share one decision`() {
        val screen = source("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("val hasFloatingTools = !processOpen && showsLegacyToolBar(processFoldEnabled, toolStatusBarEnabled, legacyToolBlocks.isNotEmpty())"))
        assertTrue(screen.contains("val bottomReserve = if (hasFloatingTools) 79.dp else 20.dp"))
        assertTrue(Regex("if \\(hasFloatingTools && !processOpen\\) \\{[\\s\\S]*?FloatingToolStatusBar\\(").containsMatchIn(screen))
        assertTrue(screen.contains("val upBaseBottom = if (hasFloatingTools)"))
        assertTrue(screen.contains("val fabBottomPadding = if (hasFloatingTools)"))
    }

    @Test fun `process entry is unique and the details keep the parent alive`() {
        val screen = source("ui/chat/ChatScreen.kt")
        val band = screen.substringAfter("item(key = \"__typing_band__\"").substringBefore("item(key = ScrollBottomKey")
        assertFalse(band.contains("ChatProcessStatusBand("))
        assertTrue(band.contains("TypingIndicator("))
        assertTrue(screen.contains("ChatProcessSheet("))
        assertTrue(screen.contains("isProcessExpanded = processMessageId == item.message.id"))
        assertFalse(screen.contains("ChatProcessDetailHost("))
        val ui = source("ui/chat/ChatProcessUI.kt")
        val host = ui.substringAfter("internal fun ChatProcessSheet(")
        assertTrue(host.contains("BackHandler(page !is ChatProcessPage.List, onBack = backToList)"))
        assertTrue(host.contains("ChatProcessPage.Tool -> ToolDetailSheetContent("))
        assertTrue(host.contains("is ChatProcessPage.Thinking -> ChatProcessThinkingReader("))
        val entry = ui.substringAfter("internal fun ChatProcessEntry(").substringBefore("internal sealed interface ChatProcessPage")
        assertTrue(entry.contains("stringResource(if (isExpanded) R.string.chat_process_collapse else R.string.chat_process_expand)"))
        assertFalse(entry.contains("KeyboardArrowRight"))
        val detail = source("ui/chat/ChatToolDetailUI.kt")
        assertTrue(detail.contains("val currentIdx = toolBlocks.indexOfFirst { it.id == selectedToolId }"))
        assertTrue(screen.contains("keepStopWithDraft = processFoldEnabled,"))
        val input = source("ui/chat/ChatInputArea.kt")
        assertTrue(input.contains("if (keepStopWithDraft && isStreaming && hasContent)"))
    }

    @Test fun `a header reopen starts at the top but drag does not restart the scroller`() {
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        val thinking = ui.substringAfter("internal fun ThinkingBlock(")
            .substringBefore("private fun ThinkingFullContentDialog(")
        val header = thinking.substringAfter(".clickable(role = Role.Button) {")
            .substringBefore("if (isStreaming && block.toolStatus")
        val body = thinking.substringAfter("visibleState = thinkingBodyVisible,")
        assertTrue(header.contains("if (!expanded) headerOpenGeneration++"))
        assertFalse(body.contains("headerOpenGeneration++"))
        assertTrue(body.contains("expandFrom = Alignment.Top"))
        assertTrue(body.contains("shrinkTowards = Alignment.Top"))
        assertTrue(body.contains("key(headerOpenGeneration) { rememberScrollState() }"))
        assertTrue(body.contains("remember(block.id, headerOpenGeneration)"))
        assertFalse(body.contains("scrollState.scrollTo(0)"))
        // A drag can keep an automatic block open without making it a manual reopen.
        assertTrue(body.contains("if (manualChoice == null && nextAnchor.isReadingHistory) manualChoice = true"))
        assertTrue(body.contains("val followThinkingTail = isStreaming && expanded && headerOpenGeneration == 0"))
        assertTrue(body.contains("if (readAnchor.isReadingHistory || currentDragging) return@collect"))
    }

    @Test fun `compact process labels are available in all full locales`() {
        for (locale in listOf("values", "values-de", "values-ja", "values-ko", "values-ru", "values-zh", "values-zh-rTW")) {
            val strings = resource("$locale/strings_chat_process.xml")
            assertTrue(locale, strings.contains("name=\"chat_process_title\""))
            assertTrue(locale, strings.contains("name=\"chat_process_running\""))
            assertTrue(locale, strings.contains("name=\"chat_process_expand\""))
            assertTrue(locale, strings.contains("name=\"chat_process_collapse\""))
        }
    }
}
