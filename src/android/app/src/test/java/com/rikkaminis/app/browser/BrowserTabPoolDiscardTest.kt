package com.rikkaminis.app.browser

import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fw-1030b A / r3-B09 F-05] Pins the "clear chat" browser-state wipe.
 *
 * `ChatViewModel.clearChat` used to call `releaseAllTabs()` and then delete
 * `filesDir/browser_tabs/<sid>.json`. Release only retires the agent's
 * reservation — the live [BrowserTabPool.Tab] objects stayed in `_tabs`, so
 * every save after the delete rebuilt the file from them: the pool's own
 * trailing `saveState()` inside release, and then `newTabFromUI` (the sheet's
 * "+"), `runAcquiredAction` (any `browser_use` action), `onTrimMemory` or
 * `evictIdleTabs`. Reopening the session resurrected the "cleared" tabs.
 *
 * The fix is a second, distinct entry point — [BrowserTabPool.discardAllTabs]
 * — that destroys the tabs through the existing teardown funnel and drops both
 * halves of the persisted state (live list + `savedURLs`, the evicted-tab
 * memory `saveState()` also folds into the document), inside a persistence
 * suppression window that closes when the wipe returns.
 *
 * WebView/Message cannot be instantiated on the JVM, so the teardown itself is
 * pinned by source-shape assertions on the real files (literal anchors, same
 * convention as BrowserTabPoolSaveStateTest / BrowserTabPoolBusyPublishTest);
 * the "what is actually left to persist" half is pinned by running the real
 * `mergedTabUrls` with literal expectations.
 */
class BrowserTabPoolDiscardTest {

    private fun readRepoFile(relative: String): String {
        var dir = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (true) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile ?: throw FileNotFoundException(
                "$relative not found from user.dir=${System.getProperty("user.dir")}",
            )
        }
    }

    private fun poolSrc(): String =
        readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserTabPool.kt")

    private fun viewModelSrc(): String =
        readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt")

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

    /**
     * Body text with comments masked — same intent as the scan gates: a KDoc
     * that merely MENTIONS a call must neither satisfy a criterion nor break
     * one (this file's own prose names `releaseAllTabs()`, `saveState()` and
     * `destroy()` while explaining them).
     */
    private fun codeOf(body: String): String {
        val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(body, " ")
        return noBlock.lineSequence().joinToString("\n") { line ->
            val at = line.indexOf("//")
            if (at < 0) line else line.substring(0, at)
        }
    }

    /** Replace [old] inside the body of [header] — mutations stay surgical. */
    private fun mutateBody(text: String, header: String, old: String, new: String): String {
        val body = bodyOf(text, header)
        val at = text.indexOf(body, text.indexOf(header))
        val mutated = body.replaceFirst(old, new)
        assertTrue("mutation target missing in $header: $old", mutated != body)
        return text.substring(0, at) + mutated + text.substring(at + body.length)
    }

    // -- Real logic: what the next save would still write -------------------

    @Test
    fun `a discarded pool leaves no tab url for the next save`() {
        // Discard clears BOTH halves => the document saveState builds from them
        // carries no tabURLs at all, so nothing can be restored on reopen.
        assertTrue(
            "a discard that clears the live list and savedURLs must leave nothing to persist",
            mergedTabUrls(emptyList(), emptyMap()).isEmpty(),
        )
        // Negative control: an empty live list ALONE is not "no tabs". saveState
        // folds savedURLs in, so tabs trimmed/evicted earlier in the session
        // would be re-persisted — this is why discardAllTabs() clears savedURLs
        // and not just `_tabs`.
        assertEquals(
            mapOf(7 to "https://evicted.example/"),
            mergedTabUrls(emptyList(), mapOf(7 to "https://evicted.example/")),
        )
        // A blank live URL (a tab still on about:blank) never reaches the file.
        assertTrue(mergedTabUrls(listOf(2 to ""), emptyMap()).isEmpty())
        // Sibling sanity: a live tab with a URL still persists normally.
        assertEquals(
            mapOf(1 to "https://live.example/"),
            mergedTabUrls(listOf(1 to "https://live.example/"), emptyMap()),
        )
    }

    // -- Shape: the pool's discard entry point ------------------------------

    private fun assertDiscardShape(src: String) {
        val body = codeOf(bodyOf(src, "fun discardAllTabs("))
        assertTrue(
            "discardAllTabs no longer destroys the tabs through the single teardown " +
                "funnel — a second teardown drifts from trim/evict/dispose and leaks " +
                "the renderer (50-100 MB/tab)",
            body.contains("destroyTab("),
        )
        assertTrue(
            "discardAllTabs no longer empties the published list — the tabs survive " +
                "every later save and the cleared chat reopens with them",
            body.contains("_tabs.value = emptyList()"),
        )
        assertTrue(
            "discardAllTabs no longer re-derives the busy flag — agentBusy stays true " +
                "and the sheet keeps offering to close tabs nobody holds",
            body.contains("publishAgentBusy()"),
        )
        assertTrue(
            "the busy re-derivation must see the emptied list (publish AFTER the write)",
            body.indexOf("publishAgentBusy()") > body.indexOf("_tabs.value = emptyList()"),
        )
        assertTrue(
            "discardAllTabs no longer clears savedURLs — URLs of tabs trimmed earlier " +
                "in the session are re-persisted by the next save",
            body.contains("savedURLs.clear()"),
        )
        assertTrue(
            "discardAllTabs no longer retires the selection",
            body.contains("_selectedTabId.value = 0"),
        )
        assertFalse(
            "discardAllTabs tears a WebView down itself — the Main-thread rule and the " +
                "per-tab bookkeeping live in destroyTab/destroyManagerOnMain",
            body.contains(".destroy()"),
        )
    }

    private fun assertSuppressionWindow(src: String) {
        val discard = codeOf(bodyOf(src, "fun discardAllTabs("))
        assertTrue(
            "the wipe no longer runs inside the persistence-suppression window — the " +
                "WebView teardown re-enters saveState() with a half-emptied list",
            discard.contains("suppressingTabPersistence {"),
        )
        assertTrue(
            "the suppression window must WRAP the teardown, not follow it",
            discard.indexOf("suppressingTabPersistence {") < discard.indexOf("destroyTab("),
        )
        val helper = codeOf(bodyOf(src, "private fun suppressingTabPersistence("))
        assertTrue("the latch is never set: ${helper.take(40)}", helper.contains("discardInProgress = true"))
        assertTrue("the window is not try/finally — an exception leaks the latch", helper.contains("finally"))
        assertTrue(
            "the latch is set after the block runs — the guard would never see it",
            helper.indexOf("discardInProgress = true") < helper.indexOf("block()"),
        )
        assertTrue(
            "the latch is never cleared — every later save is swallowed and tab state " +
                "stops being persisted for the rest of the process",
            helper.indexOf("discardInProgress = false") > helper.indexOf("block()"),
        )
        assertTrue(
            "the discard latch is not @Volatile — discardAllTabs can run off Main while " +
                "an IO-thread saveState reads it",
            Regex("""@Volatile\s+private var discardInProgress""").containsMatchIn(src),
        )
        val save = codeOf(bodyOf(src, "private fun saveState("))
        val guard = save.indexOf("if (discardInProgress) return")
        assertTrue("saveState does not short-circuit on the discard latch", guard >= 0)
        val disk = save.indexOf("browser_tabs")
        assertTrue("saveState no longer touches browser_tabs (fail-closed)", disk >= 0)
        assertTrue(
            "saveState consults the discard latch only after building the path — the " +
                "pre-wipe list is already on its way to disk",
            guard < disk,
        )
    }

    private fun assertClearChatDiscards(vm: String) {
        val body = codeOf(bodyOf(vm, "fun clearChat("))
        assertTrue(
            "clearChat no longer discards the session's browser tabs",
            body.contains("_browserTabPoolRef?.discardAllTabs()"),
        )
        assertFalse(
            "clearChat releases again — release keeps the live tabs on purpose, so the " +
                "next save resurrects the deleted session's browser_tabs/<sid>.json",
            body.contains("releaseAllTabs()"),
        )
        val del = body.indexOf("browser_tabs")
        assertTrue("clearChat no longer deletes the snapshot file", del >= 0)
        assertTrue(
            "the snapshot delete must follow the discard (delete-then-discard leaves a " +
                "window where a save rewrites the file after the delete)",
            del > body.indexOf("discardAllTabs()"),
        )
    }

    private fun assertReleaseSemantics(src: String) {
        val body = codeOf(bodyOf(src, "fun releaseAllTabs("))
        assertFalse(
            "releaseAllTabs destroys tabs now — BrowserSheet's takeover hands the agent's " +
                "tabs to the user and must keep them alive",
            body.contains("destroyTab("),
        )
        assertFalse(
            "releaseAllTabs empties the tab list now — takeover would close every tab",
            body.contains("_tabs.value = emptyList()"),
        )
        assertTrue("releaseAllTabs lost its busy re-derivation", body.contains("publishAgentBusy()"))
        assertTrue("releaseAllTabs lost its saveState()", body.contains("saveState()"))
    }

    @Test
    fun `discardAllTabs destroys the pool through the single teardown funnel`() {
        assertDiscardShape(poolSrc())
    }

    @Test
    fun `the wipe runs inside a persistence suppression window`() {
        assertSuppressionWindow(poolSrc())
    }

    @Test
    fun `clearChat discards instead of releasing`() {
        assertClearChatDiscards(viewModelSrc())
    }

    @Test
    fun `releaseAllTabs keeps its takeover semantics`() {
        assertReleaseSemantics(poolSrc())
    }

    /** Each criterion is re-run on a mutated copy that removes exactly the
     *  thing it pins — a green run on the mutation means the assertion above
     *  is toothless (the shape of the defect can come back unnoticed). */
    @Test
    fun mutationsAreCaught() {
        val pool = poolSrc()
        val vm = viewModelSrc()
        // M1: discard degenerates to release (no teardown, list survives).
        // M2: savedURLs survive => evicted URLs are re-persisted.
        // M3: publish moves before the list write.
        // M4: the latch is never set.  M5: the latch is never cleared.
        // M6: saveState ignores the latch.
        // M7: clearChat goes back to releasing.
        // M8: release gains a teardown (takeover would close the user's tabs).
        val arms = listOf(
            Arm(
                "M1 list.write",
                mutateBody(pool, "fun discardAllTabs(", "            _tabs.value = emptyList()\n", ""),
                ::assertDiscardShape,
            ),
            Arm(
                "M2 savedURLs",
                mutateBody(pool, "fun discardAllTabs(", "            savedURLs.clear()\n", ""),
                ::assertDiscardShape,
            ),
            Arm(
                "M3 publish.order",
                mutateBody(
                    pool,
                    "fun discardAllTabs(",
                    "            _tabs.value = emptyList()\n            publishAgentBusy()\n",
                    "            publishAgentBusy()\n            _tabs.value = emptyList()\n",
                ),
                ::assertDiscardShape,
            ),
            Arm(
                "M4 latch.set",
                mutateBody(
                    pool,
                    "private fun suppressingTabPersistence(",
                    "        discardInProgress = true\n",
                    "",
                ),
                ::assertSuppressionWindow,
            ),
            Arm(
                "M5 latch.clear",
                mutateBody(
                    pool,
                    "private fun suppressingTabPersistence(",
                    "        } finally {\n            discardInProgress = false\n        }",
                    "        }",
                ),
                ::assertSuppressionWindow,
            ),
            Arm(
                "M6 save.guard",
                mutateBody(pool, "private fun saveState(", "        if (discardInProgress) return\n", ""),
                ::assertSuppressionWindow,
            ),
            Arm(
                "M7 clearChat",
                mutateBody(
                    vm,
                    "fun clearChat(",
                    "_browserTabPoolRef?.discardAllTabs()",
                    "_browserTabPoolRef?.releaseAllTabs()",
                ),
                ::assertClearChatDiscards,
            ),
            Arm(
                "M8 release.destroys",
                mutateBody(
                    pool,
                    "fun releaseAllTabs(",
                    "        publishAgentBusy()\n        saveState()",
                    "        _tabs.value.forEach { destroyTab(_tabs.value.toMutableList(), it) }\n" +
                        "        publishAgentBusy()\n        saveState()",
                ),
                ::assertReleaseSemantics,
            ),
        )
        var caught = 0
        for (arm in arms) {
            try {
                arm.check(arm.mutated)
                throw AssertionError("${arm.label}: criterion stayed green on the mutated source — toothless")
            } catch (e: AssertionError) {
                if (e.message?.startsWith("${arm.label}: criterion stayed green") == true) throw e
                caught++
            }
        }
        assertEquals("every mutation arm must be caught", arms.size, caught)
    }

    /** One "put the defect back" arm: [mutated] source + the criterion that
     *  must reject it. */
    private class Arm(val label: String, val mutated: String, val check: (String) -> Unit)
}
