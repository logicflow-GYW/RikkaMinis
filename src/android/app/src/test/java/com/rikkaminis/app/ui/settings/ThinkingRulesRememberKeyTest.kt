package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/da1028-c] — deep-audit-1028 ISSUE-02.
 *
 * `ThinkingRulesSection` shows two values that are DERIVED inside
 * `ProviderRepository`:
 *   - `builtInThinkingRulesForDisplay(instanceId)` reads
 *     `config.instances[id].effectiveBaseURL` (= `customBaseURL` +
 *     `appendV1Suffix`) and filters `config.modelEntries` down to the
 *     instance, then keeps the built-ins whose `modelIds` intersect.
 *   - `firstModelId(instanceId)` reads the same `config.modelEntries`.
 *
 * Both were cached under `remember(instance.id, reloadKey)`. `reloadKey` only
 * bumps on rule mutations and `instance.id` never changes, so editing the
 * gateway URL, toggling `appendV1Suffix`, or refreshing the model catalog left
 * the section showing (and the resolution preview resolving) a stale snapshot
 * until the screen was recreated.
 *
 * The screen is Compose-only (it needs a real Context + ProviderRepository), so
 * the contract is pinned by source-shape assertions — the approach this repo
 * already uses for Compose-only screens (`SharedCacheExportPruneTest`). Expected
 * key sets are hardcoded literals and compared as SETS, never derived from the
 * code under test, so a mutation cannot masquerade as PASS. The companion
 * assertion that a non-derived `remember` stays untouched is the precision
 * control: the criterion keys on `builtInThinkingRulesForDisplay(` /
 * `firstModelId(` specifically, it is not "every remember must be keyed".
 */
class ThinkingRulesRememberKeyTest {

    private val section: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/settings/ThinkingRulesSection.kt")
    }
    private val detail: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/settings/ProviderDetailScreen.kt")
    }

    @Test
    fun `builtIns keys every source the repository call derives from`() {
        val keys = rememberKeys(codeOnly(section), "LaunchedEffect(instance.id, instance.customBaseURL")
        assertEquals(
            "builtIns derives from effectiveBaseURL(customBaseURL, appendV1Suffix) + the " +
                "instance's modelEntries — all three must be keys",
            setOf(
                "instance.id",
                "instance.customBaseURL",
                "instance.appendV1Suffix",
                "modelEntries",
                "reloadKey",
            ),
            keys,
        )
    }

    @Test
    fun `sampleModelId keys the entry list`() {
        val keys = rememberKeys(codeOnly(section), "LaunchedEffect(instance.id, modelEntries")
        assertEquals(
            "firstModelId reads config.modelEntries; without that key a catalog refresh " +
                "leaves the resolution preview pointing at a model that no longer exists",
            setOf("instance.id", "modelEntries", "reloadKey"),
            keys,
        )
    }

    // -- [deep-audit-1043 B-02] the Room-backed derivations left the composition --

    /**
     * `thinkingRulesWithIds` / `builtInThinkingRulesForDisplay` / `firstModelId`
     * are synchronous Room (+ config-load) reads: `thinkingRulesWithIds` is
     * literally `= runBlocking { providerDao.load… }`. Read from a `remember`
     * initializer they ran on the main thread inside the composition, once per
     * key change — including every edit made on this screen, since each one
     * bumps `reloadKey`.
     *
     * The fix keeps the dependency set and moves only WHERE the read happens:
     * `remember` holds the last result, a `LaunchedEffect` refills it from
     * Dispatchers.IO. These three assertions pin the carrier (no `remember`
     * initializer may hold the read) and the hop (the effect must actually hop),
     * so neither half can be reverted alone.
     */
    private val roomReads = listOf(
        "thinkingRulesWithIds(",
        "builtInThinkingRulesForDisplay(",
        "firstModelId(",
    )

    @Test
    fun `no Room-backed rule read runs in a remember initializer`() {
        val code = codeOnly(section)
        for (read in roomReads) {
            val offenders = rememberInitializers(code).filter { read in it.value }
            assertEquals(
                "$read is a blocking Room/config read and must not run in a `remember` " +
                    "initializer — it belongs in a LaunchedEffect behind withContext(Dispatchers.IO)",
                emptyList<String>(),
                offenders.keys.toList(),
            )
        }
    }

    @Test
    fun `each Room-backed rule read is produced by an IO effect`() {
        val code = codeOnly(section)
        val effects = effectBodies(code)
        for (read in roomReads) {
            val producing = effects.filter { read in it.value && "withContext(Dispatchers.IO)" in it.value }
            assertTrue(
                "$read must be loaded by a LaunchedEffect that hops to Dispatchers.IO; " +
                    "found ${effects.size} effect(s), ${producing.size} carrying the hop",
                producing.isNotEmpty(),
            )
        }
    }

    @Test
    fun `the custom-rule loader keeps its three keys and hops off the main thread`() {
        // The [da1030-C ISSUE-05] contract is a DEPENDENCY contract: a rule
        // written outside this screen (backup restore, minis-config) must still
        // re-key the loader. Moving the read into an effect must not drop it.
        assertEquals(
            "customRulesAndIds still depends on exactly (instance.id, reloadKey, rulesRevision) " +
                "— the revision is what re-reads after an EXTERNAL write",
            setOf("instance.id", "reloadKey", "rulesRevision"),
            rememberKeys(codeOnly(section), "LaunchedEffect(instance.id, reloadKey, rulesRevision)"),
        )
        val body = effectBodies(codeOnly(section))["LaunchedEffect(instance.id, reloadKey, rulesRevision)"]
        assertTrue(
            "the loader body must hop to Dispatchers.IO — a keyed effect that still reads " +
                "Room inline blocks the composition thread just as before",
            body != null && "withContext(Dispatchers.IO)" in body,
        )
    }

    @Test
    fun `the dialog only closes on a loaded column`() {
        // [deep-audit-1043 B-02] The custom-rule columns are empty until the
        // first async load lands. An unguarded "is my id gone?" test would read
        // that empty first frame as "the rule vanished" and dismiss an editor
        // the user had just opened — the read moved, so the guard must come
        // with it.
        val code = codeOnly(section)
        assertTrue(
            "the section needs a `rulesLoaded` flag raised once the async load lands",
            code.contains("var rulesLoaded by remember(instance.id) { mutableStateOf(false) }"),
        )
        val close = code.indexOf("LaunchedEffect(editorRequest?.existingId, editorLiveIndex, rulesLoaded)")
        assertTrue("the close effect must observe `rulesLoaded`", close >= 0)
        val body = code.substring(close, minOf(code.length, close + 320))
        assertTrue(
            "the close test must be gated on `rulesLoaded` — an empty first frame is not " +
                "evidence that the edited rule is gone",
            body.contains("if (rulesLoaded && req?.existingId != null && editorLiveIndex < 0)"),
        )
    }

    @Test
    fun `detail screen hands the section its entry list`() {
        val code = codeOnly(detail)
        val call = code.indexOf("ThinkingRulesSection(")
        assertTrue("ThinkingRulesSection( call site not found in ProviderDetailScreen", call >= 0)
        assertFalse(
            "matched the declaration, not the call site",
            code.substring(maxOf(0, call - 6), call).contains("fun "),
        )
        val args = code.substring(call, minOf(code.length, call + 240))
        assertTrue(
            "the call site must forward `modelEntries = ...` — a section that is never given " +
                "the list cannot key on it at all",
            args.contains("modelEntries = "),
        )
        assertTrue(
            "the forwarded value must be the FULL entry list for this instance " +
                "(entriesFor), not the visible-only subset: builtInThinkingRulesForDisplay " +
                "filters all of config.modelEntries, hidden entries included",
            args.contains("modelEntries = allEntries"),
        )
    }

    // -- precision control: nothing else in the section may be dragged along --

    @Test
    fun `the pure UI flags stay keyless`() {
        val code = codeOnly(section)
        for (flag in listOf("reloadKey", "defaultsExpanded")) {
            assertTrue(
                "`var $flag by remember {` is a pure UI flag and must stay keyless",
                code.contains("var $flag by remember {"),
            )
        }
    }

    // -- helpers --

    /** Comment lines cannot satisfy a key list. */
    private fun codeOnly(src: String): String = src
        .split("\n")
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    /**
     * `(a, b, c)` key list of the keyed call `marker` — `remember(...) {` or
     * `LaunchedEffect(...) {`. Both carriers get the same treatment: which one
     * holds a derivation is an implementation detail, the key list is the
     * contract ([deep-audit-1043 B-02] moved three derivations from the former
     * to the latter without changing a single dependency).
     */
    private fun rememberKeys(code: String, marker: String): Set<String> {
        val start = code.indexOf(marker)
        assertTrue("marker not found: $marker", start >= 0)
        val open = code.indexOf('(', start)
        assertTrue("no key list after: $marker", open >= 0)
        val close = code.indexOf(") {", open)
        assertTrue("key list not closed after: $marker", close >= 0)
        return code.substring(open + 1, close)
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /** marker -> body, for every keyed call in [code]. */
    private fun keyedBodies(code: String, call: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        var i = code.indexOf(call + "(")
        while (i >= 0) {
            val open = code.indexOf('(', i)
            val close = code.indexOf(") {", open)
            if (close < 0) break
            val keys = code.substring(open + 1, close).trim()
            // Brace-matching body: nested blocks (the `withContext` lambda) must
            // not be cut short by their own closing brace.
            var depth = 0
            var k = close + 2
            var end = -1
            while (k < code.length) {
                if (code[k] == '{') depth++
                else if (code[k] == '}') {
                    depth--
                    if (depth == 0) { end = k; break }
                }
                k++
            }
            if (end < 0) break
            out["$call($keys)"] = code.substring(close + 2, end)
            i = code.indexOf(call + "(", end)
        }
        return out
    }

    /** initializer text of every `remember(...) { … }` / `remember { … }`. */
    private fun rememberInitializers(code: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for ((marker, body) in keyedBodies(code, "remember")) out[marker] = body
        // The keyless `remember { ... }` form has no `) {` — the body starts at `{`.
        var i = code.indexOf("remember {")
        while (i >= 0) {
            var depth = 0
            var k = i + 9
            var end = -1
            while (k < code.length) {
                if (code[k] == '{') depth++
                else if (code[k] == '}') { depth--; if (depth == 0) { end = k; break } }
                k++
            }
            if (end < 0) break
            out["remember @$i"] = code.substring(i + 9, end)
            i = code.indexOf("remember {", end)
        }
        return out
    }

    private fun effectBodies(code: String): Map<String, String> =
        keyedBodies(code, "LaunchedEffect")

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
