package com.rikkaminis.app.ui.browser

import com.rikkaminis.app.browser.BrowserHistoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1039 B-07] — `BrowserSettingsSheet` must refresh its domain list
 * from the STORE's mutation counter.
 *
 * Defect (P1): the sheet's cookie/domain section was keyed on the panel-local
 * `cookieRefreshTick` alone. That tick moves only when a cookie is deleted from
 * THIS panel ([da1022-G]), but the listed domains are HISTORY domains
 * (`historyStore.uniqueDomains()`), so a mutation this panel causes indirectly
 * was invisible: switching UA (or Viewport) in the sheet reloads the active
 * tab, `BrowserUseManager.onPageFinished` calls `historyStore.record(...)` and
 * `revision` advances — the list kept rendering the pre-reload snapshot until
 * the sheet was closed and reopened. `BrowserHistorySheet` already keys on
 * `historyStore.revision` ([dv15-B3]); the settings sheet never subscribed.
 *
 * Two halves, deliberately verified two different ways:
 *
 * **A. the signal contract (runs for real).** The sheet now keys its memoized
 * read on `revision`, so that counter has to move exactly when the read's input
 * moves — `uniqueDomains()` is the read. The store is Android-bound
 * (`Context.filesDir`) with a private constructor, so the instance is allocated
 * without a constructor (same `sun.misc.Unsafe` plumbing
 * `BrowserHistorySnapshotTest` uses); the file write then fails on the null
 * context and is swallowed by `save()`'s own catch, which is irrelevant here —
 * the discriminator is the counter vs. the domain set, not what lands on disk.
 * Expectations are literals (0/1/2, `["a.example"]`): deriving them from the
 * store's own constants would let a mutation move the expectation with it.
 *
 * **B. the wiring shape (asserted on source text).** Compose + Context wiring
 * cannot be instantiated on the JVM, so the key/subscription decision is pinned
 * the way this repo pins other UI contracts (`SkillsMcpReloadOffMainTest`,
 * `SkillFileViewerSaveShapeTest`). The analyzer here is written independently
 * of the scanner gate (`browser_cookie_ui_refresh_check.py` family B1–B2) on
 * purpose — two implementations of the same decision, one of which is CI-only.
 * It binds the DECISION (a key fed by the store's counter), not a spelling: the
 * value's name, the subscription's receiver and the key order are all free.
 */
class BrowserSettingsCookieRefreshTest {

    private val sheet: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/browser/BrowserSettingsSheet.kt")
    }

    // ── A. the store-side signal contract ───────────────────────────────────

    @Suppress("PrivateApi")
    private fun allocateStore(): BrowserHistoryStore {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val f = unsafeClass.getDeclaredField("theUnsafe")
        f.isAccessible = true
        val unsafe = f.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, BrowserHistoryStore::class.java) as BrowserHistoryStore
    }

    @Suppress("PrivateApi")
    private fun install(store: BrowserHistoryStore, field: String, value: Any) {
        val f = BrowserHistoryStore::class.java.getDeclaredField(field)
        f.isAccessible = true
        f.set(store, value)
    }

    /** A store whose `entries` list and `revision` counter both exist. */
    private fun liveStore(): Pair<BrowserHistoryStore, MutableStateFlow<Int>> {
        val store = allocateStore()
        install(store, "entries", ArrayList<BrowserHistoryStore.Entry>())
        val revision = MutableStateFlow(0)
        install(store, "_revision", revision)
        return store to revision
    }

    /**
     * The invalidation contract the sheet's `remember(historyRevision)` relies
     * on: a visit that adds a domain must move the counter, and the domain read
     * must show it. Mutations that flip this red: dropping `bumpRevision()`
     * from `record()`, or bumping BEFORE the list mutation.
     */
    @Test
    fun aNewVisitMovesBothTheCounterAndTheDomainRead() {
        val (store, revision) = liveStore()

        assertEquals(emptyList<String>(), store.uniqueDomains())
        store.record("https://a.example/1", "t")

        assertEquals(1, revision.value)
        assertEquals(listOf("a.example"), store.uniqueDomains())
    }

    /** A consecutive repeat is not stored, so it must NOT invalidate observers. */
    @Test
    fun aRepeatedVisitMovesNeither() {
        val (store, revision) = liveStore()
        store.record("https://a.example/1", "t")

        store.record("https://a.example/1", "t")

        assertEquals(1, revision.value)
        assertEquals(listOf("a.example"), store.uniqueDomains())
    }

    /** Clear-all drops every domain, so the sheet must be told to re-read. */
    @Test
    fun clearAllMovesBothTheCounterAndTheDomainRead() {
        val (store, revision) = liveStore()
        store.record("https://a.example/1", "t")
        store.record("https://b.example/2", "t")

        store.clear()

        assertEquals(3, revision.value)
        assertEquals(emptyList<String>(), store.uniqueDomains())
    }

    /**
     * The reason the panel-local tick alone was not enough: a mutation this
     * panel did not make (the reload-driven record above) is only visible
     * through the store counter — and the counter is strictly increasing, so a
     * `remember` keyed on it cannot confuse two different snapshots.
     */
    @Test
    fun aForeignMutationIsObservableThroughTheCounterAlone() {
        val (store, revision) = liveStore()
        store.record("https://a.example/1", "t")
        val before = revision.value
        val domainsBefore = store.uniqueDomains()

        store.record("https://c.example/3", "t") // e.g. the sheet's UA switch reload

        assertNotEquals(before, revision.value)
        assertNotEquals(domainsBefore, store.uniqueDomains())
    }

    // ── B. the panel-side wiring shape ──────────────────────────────────────

    /** Key tokens of the `remember(...)` that follows `anchor`; null if absent. */
    private fun rememberKeys(src: String, anchor: String): List<String>? {
        val at = src.indexOf(anchor)
        if (at < 0) return null
        val open = src.indexOf('(', at + anchor.length)
        if (open < 0) return null
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return src.substring(open + 1, i)
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                }
            }
            i++
        }
        return null
    }

    /** True when `key`'s own declaration subscribes to the store's counter. */
    private fun fedByRevision(src: String, key: String): Boolean {
        if (!Regex("[A-Za-z_][A-Za-z0-9_]*").matches(key)) return false
        return Regex(
            "val\\s+" + Regex.escape(key) + "\\s+by\\s+[\\w.]*revision\\s*\\.\\s*collectAsState",
        ).containsMatchIn(src)
    }

    @Test
    fun theSheetSubscribesToTheStoreCounter() {
        val code = sheet.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

        assertTrue(
            "BrowserSettingsSheet must subscribe to `historyStore.revision` — the " +
                "domain list is history-derived, so a record() from another cause " +
                "(the UA/viewport reload) cannot reach a panel-local tick",
            Regex("revision\\s*\\.\\s*collectAsState\\s*\\(\\s*\\)").containsMatchIn(code),
        )
    }

    @Test
    fun theDomainReadIsKeyedOnARevisionBackedValue() {
        val code = sheet.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        val keys = rememberKeys(code, "val allDomains = remember")

        assertTrue("no `val allDomains = remember` declaration found", keys != null)
        assertTrue(
            "allDomains is keyed on $keys, none of which is fed by the store's " +
                "revision flow — the read cannot follow a reload-driven record()",
            keys!!.any { fedByRevision(code, it) },
        )
    }

    /**
     * Family-4 regression anchor: the panel-local tick still owns the cookie
     * deletes / clear-all, so it must remain a key of the cookie section.
     */
    @Test
    fun theLocalTickStaysAKeyOfTheDomainRead() {
        val code = sheet.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        val keys = rememberKeys(code, "val allDomains = remember")

        assertTrue("no `val allDomains = remember` declaration found", keys != null)
        assertTrue(
            "allDomains dropped cookieRefreshTick ($keys) — per-domain delete and " +
                "clear-all still invalidate through it",
            keys!!.contains("cookieRefreshTick"),
        )
    }

    /**
     * Precision control for the key criterion: the local filter box must stay
     * UN-keyed ([V5] in the gate). A family whose criterion were "every
     * remember has a key" would pass the B-07 fixtures while breaking this.
     */
    @Test
    fun theFilterBoxStaysUnKeyed() {
        val code = sheet.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

        assertTrue(
            "`var cookieFilterText by remember {` must stay keyless — a key resets " +
                "the user's typed filter text on every recomposition",
            code.contains("var cookieFilterText by remember {"),
        )
    }

    /**
     * A `by` delegate is a LOCAL val, not a hoisted symbol: if the subscription
     * is written below the remembers that key on it, the real Gradle build dies
     * with `Unresolved reference 'historyRevision'` (this assertion exists
     * because exactly that happened on the first CI run — the sandbox has no
     * Android SDK, so the Compose file itself cannot be compiled here, and
     * Part B's text analysis is the only local detector for this class).
     */
    @Test
    fun theSubscriptionIsDeclaredBeforeItsConsumers() {
        val code = sheet.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        val decl = Regex("val\\s+(\\w+)\\s+by\\s+[\\w.]*revision\\s*\\.\\s*collectAsState")
            .find(code)

        assertTrue("no revision subscription declaration found", decl != null)
        val declaredAt = decl!!.range.first
        for (anchor in listOf("val hasCookies = remember", "val allDomains = remember")) {
            val use = code.indexOf(anchor)
            assertTrue("$anchor not found", use >= 0)
            assertTrue(
                "`${decl.groupValues[1]}` is declared at offset $declaredAt but used by " +
                    "`$anchor` at $use — a `by` delegate is a local val, so the real " +
                    "build fails with Unresolved reference",
                use > declaredAt,
            )
        }
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }
}
