package com.rikkaminis.app.browser

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [r3-B09 F-04] `inUse` means "an agent action is EXECUTING"; the post-action
 * reuse grace hold is [BrowserTabPool.Tab.graceHeld], and only reads that mean
 * "don't hand this tab to another caller yet" use [BrowserTabPool.Tab.isHeld].
 *
 * Why the split: `agentBusy` — BrowserSheet's "Minis is browsing" state, which
 * disables back/forward/reload and shows the Takeover bar — derives from
 * `any { it.inUse }`. With one flag for both meanings a plain tab-less
 * `navigate` kept the sheet locked for the full 15s grace window after the
 * action had finished, and a Takeover during an action was re-locked by that
 * action's own release path (`armImplicitGraceRelease` re-set `inUse = true`).
 *
 * The busy carrier itself is NOT re-pointed: `browser_tabs_lifecycle_check` C9
 * pins it to `_tabs.value.any { it.inUse }` because the pre-split defect it was
 * written for was a *stale* flag (a writer that forgot to publish). Keeping the
 * predicate and fixing what the predicate MEANS is the repair; re-pointing it
 * at a third field would make the published signal unobservable again.
 *
 * Expectations are literals — nothing is derived from a production constant.
 */
class BrowserTabHeldSplitTest {

    // ── the pure predicate ──────────────────────────────────────────────

    @Test
    fun `held means executing or grace-held`() {
        assertFalse("a free tab is not held", tabHeld(inUse = false, graceHeld = false))
        assertTrue("an executing tab is held", tabHeld(inUse = true, graceHeld = false))
        assertTrue("a grace-held tab is held", tabHeld(inUse = false, graceHeld = true))
        assertTrue("both at once stays held", tabHeld(inUse = true, graceHeld = true))
    }

    @Test
    fun `a reserved tab is never an eviction victim`() {
        assertFalse(
            "an executing tab must survive a trim/evict sweep no matter how idle",
            isIdleEvictionCandidate(held = true, idleMs = 1_000_000L, timeoutMs = 15_000L),
        )
        assertFalse(
            "a grace-held tab must survive the sweep (it is a follow-up's tab)",
            isIdleEvictionCandidate(held = true, idleMs = 60_000L, timeoutMs = 15_000L),
        )
    }

    @Test
    fun `the idle window is inclusive and a fresh tab is not a victim`() {
        assertFalse(
            "a tab active 14_999ms ago is still warm for a 15_000ms window",
            isIdleEvictionCandidate(held = false, idleMs = 14_999L, timeoutMs = 15_000L),
        )
        assertTrue(
            "exactly at the window boundary the tab is a victim (>=, not >)",
            isIdleEvictionCandidate(held = false, idleMs = 15_000L, timeoutMs = 15_000L),
        )
        assertTrue(
            "a long-idle free tab is a victim",
            isIdleEvictionCandidate(held = false, idleMs = 1_000_000L, timeoutMs = 15_000L),
        )
    }

    // ── wiring (shape) ──────────────────────────────────────────────────

    @Test
    fun `the busy signal still derives from inUse and never from the grace hold`() {
        val body = memberSlice(src(), "private fun publishAgentBusy(")
        assertTrue(
            "the busy carrier must keep the literal `_tabs.value.any { it.inUse }` " +
                "derivation (browser_tabs_lifecycle_check C9):\n$body",
            body.contains("_tabs.value.any { it.inUse }"),
        )
        assertFalse(
            "the grace hold must NOT feed the published busy flag — that is exactly " +
                "the 15s UI lock this split removes:\n$body",
            body.contains("graceHeld"),
        )
    }

    @Test
    fun `the grace hold is armed without touching inUse`() {
        val body = memberSlice(src(), "private fun armImplicitGraceRelease(")
        assertTrue("the hold must be set:\n$body", body.contains("tab.graceHeld = true"))
        assertFalse(
            "arming the hold must not write `inUse` — inUse is the executing signal, " +
                "and the action it guards has already finished:\n$body",
            body.contains("setTabInUse("),
        )
        assertFalse(
            "no raw inUse write either:\n$body",
            body.contains(".inUse ="),
        )
    }

    @Test
    fun `execution ends before the grace hold is armed`() {
        val body = memberSlice(src(), "private suspend fun runAcquiredAction(")
        val release = body.indexOf("setTabInUse(tab, false)")
        val arm = body.indexOf("armImplicitGraceRelease(tab)")
        assertTrue("the release must exist:\n$body", release >= 0)
        assertTrue("the implicit re-arm must exist:\n$body", arm >= 0)
        assertTrue(
            "the executing bit has to be released BEFORE the hold is re-armed, or a " +
                "concurrent acquire sees 'executing' on a finished action",
            release < arm,
        )
        assertTrue(
            "the explicit-tab path must retire a hold armed by an earlier tab-less call",
            body.contains("tab.graceHeld = false"),
        )
    }

    @Test
    fun `every reservation read uses isHeld and not the raw flag`() {
        val src = src()
        assertEquals(
            "the implicit fan-out must skip reserved tabs via isHeld (2 picks)",
            2,
            Regex("firstOrNull \\{ !it\\.isHeld \\}").findAll(src).count(),
        )
        assertTrue(
            "the trim victim filters must use isHeld",
            src.contains("_tabs.value.filter { it.id != _selectedTabId.value && !it.isHeld }") &&
                src.contains("_tabs.value.filter { !it.isHeld }"),
        )
        assertTrue(
            "the trim/idle predicate must be the pure helper fed with isHeld",
            src.contains("isIdleEvictionCandidate(tab.isHeld,") &&
                src.contains("isIdleEvictionCandidate(it.isHeld,"),
        )
        assertFalse(
            "no `!x.inUse` read may survive: that spelling either means 'executing' " +
                "(so a finished-but-held tab looks busy) or 'held' (so an executing " +
                "tab can be stolen) — it cannot mean both",
            Regex("!\\w+\\.inUse").containsMatchIn(src),
        )
    }

    @Test
    fun `a takeover releases the reservation too`() {
        val body = memberSlice(src(), "fun releaseAllTabs(")
        assertTrue("the executing bit is released:\n$body", body.contains("setTabInUse(tab, false)"))
        assertTrue(
            "the grace hold must be retired as well — the user now owns every tab, and " +
                "a surviving hold would fan the next tab-less acquire around it:\n$body",
            body.contains("tab.graceHeld = false"),
        )
    }

    @Test
    fun `re-acquiring a held tab retires its hold`() {
        val body = memberSlice(src(), "private suspend fun acquireTab(")
        assertTrue(
            "re-acquiring a still-held tab must drop the hold before marking it " +
                "executing:\n$body",
            body.contains("tab.graceHeld = false") && body.contains("setTabInUse(tab, true)"),
        )
    }

    @Test
    fun `the tab exposes the split`() {
        val src = src()
        assertTrue(
            "graceHeld must be a Tab field with a default",
            src.contains("var graceHeld: Boolean = false"),
        )
        assertTrue(
            "isHeld must be the derived reservation predicate",
            src.contains("val isHeld: Boolean get() = tabHeld(inUse = inUse, graceHeld = graceHeld)"),
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun src(): String = readRepoFile(TabPoolPath)

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    /** Lines of the member declared at [marker], up to the next sibling member. */
    private fun memberSlice(src: String, marker: String): String {
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains(marker) }
        assertTrue("marker not found: $marker", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { i ->
            val t = lines[i]
            t.startsWith("    /**") || t.startsWith("    @") ||
                t.startsWith("    fun ") || t.startsWith("    internal fun ") ||
                t.startsWith("    private fun ") || t.startsWith("    private suspend fun ") ||
                t.startsWith("    internal val ") || t.startsWith("    private val ") ||
                t.startsWith("    // --")
        } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private companion object {
        private const val TabPoolPath =
            "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserTabPool.kt"
    }
}
