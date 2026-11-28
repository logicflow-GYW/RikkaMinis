package com.rikkaminis.app.ui.chat

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Shapes here prove signal routing; policy/gesture tests above provide behavioral evidence. */
class ProcessSheetFollowWiringTest {
    private fun source(name: String): String {
        val explicit = System.getProperty("minis.test.sourceRoot")
        var dir: File? = explicit?.let(::File) ?: File(System.getProperty("user.dir"))
        while (dir != null) {
            for (prefix in listOf("src/android/app", "app")) {
                val f = File(dir, "$prefix/src/main/java/com/rikkaminis/app/ui/chat/$name.kt")
                if (f.isFile) return f.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("(?m)^\\s*//[^\\n]*"), "")
            }
            if (explicit != null) break
            dir = dir.parentFile
        }
        error("Missing production $name")
    }

    @Test fun observer_uses_committed_geometry_not_streaming_or_content_and_one_cancel_safe_write() {
        val source = source("ChatProcessUI")
        assertTrue(source.contains("LaunchedEffect(listState, follow) {"))
        val observer = source.substringAfter("LaunchedEffect(listState, follow) {").substringBefore("    LazyColumn(")
        assertFalse(observer.contains("isStreaming"))
        assertFalse(observer.contains(".content"))
        assertTrue(observer.contains("layout.totalItemsCount != latestBlocks.size"))
        assertTrue(observer.contains("measured.key != tailKey"))
        assertTrue(observer.contains("listState.canScrollForward"))
        assertTrue(observer.contains("listState.isScrollInProgress || !latestCanFollow()"))
        assertTrue(observer.contains("val target = currentTarget() ?: return@launch"))
        assertTrue(observer.contains("coroutineScope {"))
        assertTrue(observer.contains("follow.positioned(target)"))
        assertEquals(1, Regex("listState\\.scrollToItem\\(").findAll(observer).count())
    }

    @Test fun parent_wires_single_owner_detail_suspension_and_message_scoped_list() {
        val source = source("ChatProcessUI")
        assertTrue(source.contains("remember(messageId) { ProcessSheetFollow() }"))
        assertTrue(source.contains("onHistoryScroll = follow::detach"))
        assertEquals(2, Regex("follow\\.suspendForDetail\\(\\)").findAll(source).count())
        assertTrue(source.contains("detailCovered = { page != ChatProcessPage.List }"))
        assertTrue(source.contains("key(messageId) {"))
        assertTrue(source.contains("initialFirstVisibleItemIndex = blocks.lastIndex.coerceAtLeast(0)"))
    }

    @Test fun list_intent_notification_precedes_physical_drag_gate_and_shell_owns_busy_state() {
        val shell = source("TopChatSheet")
        val post = shell.substringAfter("override fun onPostScroll(").substringBefore("override suspend fun onPreFling(")
        val signal = "if (source == NestedScrollSource.UserInput && consumed.y > 0f) onHistoryScroll()"
        assertTrue(post.contains(signal))
        assertTrue(post.indexOf(signal) < post.indexOf("!touch.pressed"))
        assertTrue(shell.contains("var pressed by mutableStateOf(false)"))
        assertTrue(shell.contains("entered && !closeRequested && !touch.pressed &&"))
        assertTrue(shell.contains("state.targetValue == TopSheetAnchor.Expanded"))
        assertEquals(1, Regex("\\.pointerInput\\(").findAll(shell).count())
    }
}
