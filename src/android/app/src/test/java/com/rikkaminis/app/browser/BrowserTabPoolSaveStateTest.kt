package com.rikkaminis.app.browser

import java.io.FileNotFoundException
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [dv16-B7] Pins the window.open / window.close tab-state persistence.
 *
 * `handleNewWindow` / `handleCloseWindow` were the only two of the twelve
 * tab-mutation paths that changed `_tabs` / `_selectedTabId` without calling
 * `saveState()`: a JS `window.open` tab (OAuth popups are the largest
 * traffic) or a `window.close()`'d tab resurrected from / vanished out of
 * `browser_tabs/<sid>.json` on session reload, and `selectedTabId` could
 * point at a destroyed tab. WebView/Message cannot be instantiated on the
 * JVM, so these are source-shape assertions on the real file (literal
 * anchors, same convention as CompactModelPinTest).
 *
 * The `dispose()` guard double-serves as the C4a regression anchor: dispose
 * must NEVER re-persist — an unconditional saveState() there resurrected a
 * just-deleted session's browser_tabs/<sid>.json (dv14-B2).
 */
class BrowserTabPoolSaveStateTest {

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    private fun poolSrc(): String =
        readRepoFile("app/src/main/java/com/rikkaminis/app/browser/BrowserTabPool.kt")

    private fun bodyOf(src: String, header: String): String {
        val at = src.indexOf(header)
        assertTrue("anchor missing: $header", at >= 0)
        val open = src.indexOf('{', at)
        assertTrue("no body brace after $header", open >= 0)
        var depth = 0
        var inStr = false
        var i = open
        while (i < src.length) {
            val c = src[i]
            if (inStr) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        return src.substring(open)
    }

    @Test
    fun `handleNewWindow persists after the tab-state assignments`() {
        val body = bodyOf(poolSrc(), "private fun handleNewWindow(")
        val atTabs = body.indexOf("_tabs.value = currentTabs")
        val atSelected = body.indexOf("_selectedTabId.value = id")
        val atSave = body.indexOf("saveState()")
        val atSend = body.indexOf("resultMsg.sendToTarget()")
        assertTrue("handleNewWindow no longer touches _tabs", atTabs >= 0)
        assertTrue("handleNewWindow no longer switches the selected tab", atSelected >= 0)
        assertTrue(
            "handleNewWindow does not call saveState( — window.open tabs " +
                "resurrect from the stale state file on reload",
            atSave >= 0)
        assertTrue("handleNewWindow no longer sends the WebView transport", atSend >= 0)
        // Same position as closeTabFromUI: after all state assignments, so the
        // selected-id switch lands on disk even when the popup stays on
        // about:blank (no later save path runs for it).
        assertTrue("saveState must sit after the state assignments", atSave > atSelected && atSave > atTabs)
        assertTrue("saveState must precede the transport send (no later save path for a blank popup)", atSave < atSend)
    }

    @Test
    fun `handleCloseWindow persists after destroyTab`() {
        val body = bodyOf(poolSrc(), "private fun handleCloseWindow(")
        val atDestroy = body.indexOf("destroyTab(")
        val atSave = body.indexOf("saveState()")
        assertTrue("handleCloseWindow does not destroy the closing tab", atDestroy >= 0)
        assertTrue(
            "handleCloseWindow does not call saveState( — window.close tabs " +
                "stay in the state file (and resurrect on reload)",
            atSave >= 0)
        // destroyTab removes the closed id from savedURLs first, so the
        // persisted JSON drops it too; saving before destroy would re-add it.
        assertTrue("saveState must sit after destroyTab (its savedURLs.remove feeds the JSON)", atSave > atDestroy)
    }

    @Test
    fun `dispose still never re-persists (C4a regression anchor)`() {
        val body = bodyOf(poolSrc(), "fun dispose(")
        assertTrue("dispose() body not extractable", body.isNotEmpty())
        assertTrue(
            "dispose() must not call saveState( — an unconditional saveState() " +
                "there resurrected deleted sessions' browser_tabs/<sid>.json",
            !body.contains("saveState("))
    }

    @Test
    fun `ui close path keeps its saveState (sibling anchor)`() {
        val body = bodyOf(poolSrc(), "suspend fun closeTabFromUI(")
        assertTrue(
            "closeTabFromUI lost its saveState( — the window.close path mirrors it",
            body.contains("saveState(")
        )
    }

    // [dv22-B2] The six destroy paths must also re-derive the busy flag after
    // their _tabs.value write; the full six-path family lives in
    // BrowserTabPoolBusyPublishTest — this anchor pins the UI close path here
    // so the persistence probe fails loudly if the busy publish and the
    // saveState are ever separated.
    @Test
    fun `ui close path keeps its busy re-derivation (dv22-B2 sibling anchor)`() {
        val body = bodyOf(poolSrc(), "suspend fun closeTabFromUI(")
        assertTrue(
            "closeTabFromUI lost its publishAgentBusy( — agentBusy freezes at true " +
                "when the closed tab was the last in-use one and the sheet stays stuck",
            body.contains("publishAgentBusy(")
        )
    }
}
