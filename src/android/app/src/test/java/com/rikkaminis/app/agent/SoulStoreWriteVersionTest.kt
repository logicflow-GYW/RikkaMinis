package com.rikkaminis.app.agent

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [da1028-B / ISSUE-04] Settings → Soul loads its editors once
 * (`LaunchedEffect(Unit)`) and then keeps editing the snapshot it read at open
 * time. An outside write — `minis-config set soul.body …` behind the confirm
 * sheet, a backup-restore, the chat memory sheet's SOUL.md editor — leaves that
 * snapshot stale, and the user's next Save writes it straight back over the
 * external change (the 1026-A / 1027-E silent-rollback family).
 *
 * The fix is a monotonic `SoulStore.writeVersion` counter that the screen keys
 * its load on. Three layers are asserted, each with its own device:
 *
 *  1. *Behaviour, really executed*: the production `save()` / `refreshCache()`
 *     bodies run on a real filesystem through a stub `Context` whose only
 *     meaningful member is `filesDir`. This is the layer that decides whether
 *     the counter actually moves.
 *  2. *Mechanism, executed*: why a counter is needed at all. `cachedMetadata`
 *     is a `MutableStateFlow` and de-duplicates equal values, and SOUL.md's
 *     front matter is not part of `SoulMetadata` — so the common body-only
 *     rewrite leaves the metadata equal and never emits. Both arms collect
 *     from live StateFlows; the premise of the fix is proven, not asserted.
 *  3. *Wiring, as source shape*: the screen keys its load on the counter. A
 *     `LaunchedEffect(Unit)` load cannot be exercised from a JVM test (it needs
 *     the whole screen), so this layer reads the shipped source — the house
 *     pattern for Context-bound call sites (see ScreenshotCaptureShapeTest /
 *     BrowserSessionBindingTest) — and every shape assertion carries an in-test
 *     negative control: the same probe that accepts the shipped source must
 *     reject a mutated copy of it.
 *
 * probe/jvm/ compiles this exact file against a hand-written stub of the
 * production slice (`extract.py` is anchor-anchored and fail-closed), so the
 * layers that can be run are run twice: once here in CI, once offline.
 */
class SoulStoreWriteVersionTest {

    /**
     * `ContextWrapper(null)` + an overridden `filesDir` is enough: the paths
     * under test touch no other Context member. Framework methods outside the
     * override return defaults (the module sets
     * `unitTests.isReturnDefaultValues = true`; without it the constructor
     * itself would be a stub that throws).
     */
    private class StubContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }

    private val tempDir: File = Files.createTempDirectory("soul-write-version").toFile()
    private val context: Context = StubContext(tempDir)

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun soulFile(body: String): SoulFile = SoulFile(SoulMetadata.DEFAULT, body)

    // ── 1. behaviour: the production write paths ─────────────────────────

    @Test
    fun `save bumps writeVersion so observers can re-seed`() {
        val before = SoulStore.writeVersion.value
        SoulStore.save(context, soulFile("first body"))
        val afterFirst = SoulStore.writeVersion.value
        assertEquals("save() must publish exactly one write signal", before + 1, afterFirst)

        SoulStore.save(context, soulFile("second body"))
        assertEquals(
            "the counter must be monotonic across writes",
            afterFirst + 1,
            SoulStore.writeVersion.value,
        )
    }

    @Test
    fun `body-only rewrite is visible to observers`() {
        // The trap this whole fix exists for: two writes whose METADATA is
        // identical. cachedMetadata (a StateFlow of the metadata) stays silent;
        // the counter must not.
        SoulStore.save(context, soulFile("same metadata, body A"))
        val metadataAfterFirst = SoulStore.cachedMetadata.value
        val versionAfterFirst = SoulStore.writeVersion.value

        SoulStore.save(context, soulFile("same metadata, body B"))

        assertEquals(
            "precondition: the metadata really is unchanged between the two writes",
            metadataAfterFirst,
            SoulStore.cachedMetadata.value,
        )
        assertEquals(
            "a body-only rewrite must still bump the counter (cachedMetadata cannot " +
                "carry this signal — StateFlow de-duplicates equal values)",
            versionAfterFirst + 1,
            SoulStore.writeVersion.value,
        )
    }

    @Test
    fun `refreshCache notifies too when the file is rewritten outside save`() {
        // The chat memory sheet writes SOUL.md through MemoryRepository.saveFile
        // and then calls refreshCache: that is the documented "rewritten outside
        // save()" notification path, so it must carry the same signal.
        val before = SoulStore.writeVersion.value
        SoulStore.refreshCache(context)
        assertEquals(
            "refreshCache() must bump writeVersion (it is the external-rewrite notification)",
            before + 1,
            SoulStore.writeVersion.value,
        )
    }

    @Test
    fun `save is still atomic and round-trips through the parser`() {
        SoulStore.save(context, soulFile("round trip body"))
        assertTrue("save() must write the file", SoulStore.fileLocation(context).exists())
        assertTrue(
            "save() must not leave its .tmp sibling behind",
            !File(SoulStore.fileLocation(context).parentFile, "SOUL.md.tmp").exists(),
        )
        val loaded = SoulStore.load(context)
        assertNotNull("the written file must parse back", loaded)
        assertEquals("round trip body", loaded!!.body)
    }

    // ── 2. the mechanism (executed) ──────────────────────────────────────

    /**
     * `minis-config set soul.body …` rewrites the file without touching the
     * front matter: the metadata copied out of the file is *equal* both times.
     * A real collector over the same kind of StateFlow the production code holds
     * must therefore see nothing.
     */
    @Test
    fun `a metadata StateFlow stays silent on a body-only rewrite`() = runBlocking {
        val cache = MutableStateFlow(SoulMetadata.DEFAULT)
        val seen = mutableListOf<SoulMetadata>()
        val job = launch(Dispatchers.Unconfined) { cache.collect { seen += it } }
        yield()
        val baseline = seen.size

        cache.value = SoulMetadata.DEFAULT
        yield()
        cache.value = SoulMetadata.DEFAULT
        yield()

        job.cancel()
        assertEquals(
            "StateFlow must have swallowed the two equal rewrites — if this ever " +
                "changes, the premise of the writeVersion counter is wrong",
            baseline,
            seen.size,
        )
    }

    /**
     * The counter half of the same argument: a body-only rewrite changes the
     * metadata not at all, so only a value that is distinct every time can
     * carry the signal.
     */
    @Test
    fun `a monotonic counter emits on every rewrite`() = runBlocking {
        val version = MutableStateFlow(0L)
        val seen = mutableListOf<Long>()
        val job = launch(Dispatchers.Unconfined) { version.collect { seen += it } }
        yield()

        repeat(3) {
            version.value += 1
            yield()
        }

        job.cancel()
        assertEquals(
            "every rewrite must reach the collector, including the ones that " +
                "changed nothing observable",
            listOf(0L, 1L, 2L, 3L),
            seen,
        )
    }

    // ── 3. the wiring (source shape) ─────────────────────────────────────

    private val storeRel =
        "src/android/app/src/main/java/com/rikkaminis/app/agent/SoulStore.kt"
    private val screenRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/SoulSettingsScreen.kt"

    private fun memberSlice(src: String, header: String, label: String): String {
        val start = src.indexOf(header)
        assertTrue("$label not found ($header) — probe anchor lost", start >= 0)
        val open = src.indexOf('{', start + header.length)
        assertTrue("$label has no body", open > start)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(start, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced braces in $label")
    }

    /** Blank comment lines: a bump mentioned only in a KDoc must not satisfy a probe. */
    private fun codeOnly(text: String): String = text.lines().joinToString("\n") { line ->
        val t = line.trimStart()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
            " ".repeat(line.length)
        } else {
            line
        }
    }

    private val storeSource: String get() = codeOnly(readRepoFile(storeRel))

    private fun assertStoreWired(rawSrc: String) {
        val src = codeOnly(rawSrc)

        assertTrue(
            "SoulStore must expose a read-only writeVersion StateFlow",
            Regex("val writeVersion: StateFlow<Long>").containsMatchIn(src),
        )
        assertTrue(
            "the backing counter must be private",
            Regex("private val _writeVersion = MutableStateFlow\\(0L\\)").containsMatchIn(src),
        )
        val save = memberSlice(src, "fun save(", "SoulStore.save")
        assertTrue(
            "save() must bump _writeVersion — otherwise Settings → Soul keeps a stale " +
                "snapshot and the next Save rolls back the external write",
            save.contains("_writeVersion.value += 1"),
        )
        val refresh = memberSlice(src, "fun refreshCache(", "SoulStore.refreshCache")
        assertTrue(
            "refreshCache() must bump _writeVersion too — it is the documented " +
                "\"SOUL.md rewritten outside save()\" notification (the chat memory sheet)",
            refresh.contains("_writeVersion.value += 1"),
        )
    }

    private fun assertScreenWired(src: String) {
        val clean = codeOnly(src)
        assertTrue(
            "SoulSettingsScreen must collect SoulStore.writeVersion",
            clean.contains("SoulStore.writeVersion.collectAsState()"),
        )
        val effect = memberSlice(clean, "LaunchedEffect(writeVersion)", "the writeVersion-keyed load")
        assertTrue(
            "the writeVersion-keyed effect must re-read the file (SoulStore.load)",
            effect.contains("SoulStore.load(context)"),
        )
        assertFalse(
            "a LaunchedEffect(Unit) load is the pre-fix shape: it runs once per " +
                "composition and never re-seeds after an outside write",
            clean.contains("LaunchedEffect(Unit)"),
        )
    }

    @Test
    fun `soul store bumps the write counter on both write paths`() {
        assertStoreWired(storeSource)
    }

    @Test
    fun `soul settings screen re-seeds from the write counter`() {
        assertScreenWired(readRepoFile(screenRel))
    }

    // ── negative controls ────────────────────────────────────────────────

    private val bump = "        _writeVersion.value += 1"

    /**
     * Pull the member opened by [header] out of [source], apply [target] →
     * [replacement] there, and splice it back. Scoping the edit to the member is
     * the whole point: the first version of this control glued the assignment to
     * the bump in one string, but a KDoc block sits between the two lines in
     * `save()` — so the edit was a silent no-op and the control proved nothing
     * (the no-op guard below is what caught it, by failing the run).
     */
    private fun mutateInMember(
        source: String,
        header: String,
        target: String,
        replacement: String,
    ): String {
        val slice = memberSlice(source, header, header)
        val mutatedSlice = slice.replaceFirst(target, replacement)
        assertNotEquals(
            "mutation was a no-op — the probe proves nothing",
            slice,
            mutatedSlice,
        )
        val at = source.indexOf(slice)
        return source.substring(0, at) + mutatedSlice + source.substring(at + slice.length)
    }

    @Test
    fun `store probe rejects a save that stopped bumping`() {
        assertRejectsStore(storeSource, mutateInMember(storeSource, "fun save(", bump, ""))
    }

    @Test
    fun `store probe rejects a refreshCache that stopped bumping`() {
        assertRejectsStore(storeSource, mutateInMember(storeSource, "fun refreshCache(", bump, ""))
    }

    @Test
    fun `store probe rejects a bump moved into a comment`() {
        // The blank-comment-lines rule in action: the bump survives only as
        // prose, so the probe must go red.
        val mutated =
            mutateInMember(storeSource, "fun save(", bump, "        // _writeVersion.value += 1")
        assertRejectsStore(storeSource, mutated)
    }

    @Test
    fun `screen probe rejects a one-shot load`() {
        val src = readRepoFile(screenRel)
        val mutated = src.replace("LaunchedEffect(writeVersion)", "LaunchedEffect(Unit)")
        assertNotEquals("mutation was a no-op — the probe proves nothing", src, mutated)
        assertRejectsScreen(src, mutated)
    }

    private fun assertRejectsStore(pristine: String, mutated: String) {
        assertStoreWired(pristine)
        var rejected = false
        try {
            assertStoreWired(mutated)
        } catch (_: AssertionError) {
            rejected = true
        }
        assertTrue("negative control: the store probe still accepts mutated source", rejected)
    }

    private fun assertRejectsScreen(pristine: String, mutated: String) {
        assertScreenWired(pristine)
        var rejected = false
        try {
            assertScreenWired(mutated)
        } catch (_: AssertionError) {
            rejected = true
        }
        assertTrue("negative control: the screen probe still accepts mutated source", rejected)
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]: Gradle sets user.dir to the module dir, a sandbox JVM run can
     * start anywhere under the repo — no fixed depth works.
     */
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
