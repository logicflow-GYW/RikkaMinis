package com.rikkaminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [da1023-C] JVM tests for the model-entry composite-key guard and the
 * compact-pin secondary-reference handling (deep-audit-1023 ISSUE-03 + 09).
 *
 * ProviderRepository itself needs a Room-backed Context and is not
 * JVM-constructible, so the three decisions under test were extracted into
 * top-level pure functions in ProviderRepository.kt (same package, so
 * `internal` is reachable from the test source set). Their *application* —
 * which function calls them, inside which branch — is pinned by source-shape
 * assertions at the bottom of this class (house pattern: the wiring is
 * asserted on real text, the logic on real values).
 *
 * Expected values are hardcoded literals, never derived from the tested
 * constants (evidence-discipline: a mutation must not move the expectation).
 */
class ProviderEntryKeyAndPinTest {

    private val inst1 = "inst-1"
    private val inst2 = "inst-2"

    /** (entryId, compositeKey) pairs as addEntry builds them from live rows. */
    private val live = listOf(
        "uuid-a" to "$inst1/gpt-4o",
        "uuid-b" to "$inst1/claude-3",
        "uuid-c" to "$inst2/gpt-4o",
    )

    // ── decideEntryAdd: composite-key uniqueness ───────────────────────────

    @Test
    fun `new instance-model pair is allowed and persists the composite key`() {
        val d = decideEntryAdd(inst1, "o3-mini", live)
        assertTrue(d.allowed)
        assertEquals("inst-1/o3-mini", d.persistedId)
    }

    @Test
    fun `same model id on a different instance is allowed`() {
        // The key is the PAIR: gpt-4o exists on inst-1 and inst-2 in `live`,
        // and a third instance must not be treated as a duplicate.
        val d = decideEntryAdd("inst-3", "gpt-4o", live)
        assertTrue(d.allowed)
        assertEquals("inst-3/gpt-4o", d.persistedId)
    }

    @Test
    fun `duplicate pair is rejected and yields the surviving entry id`() {
        val d = decideEntryAdd(inst1, "gpt-4o", live)
        assertFalse(d.allowed)
        // Not the composite string: callers surface this as "already exists
        // as <id>", i.e. the uuid the live row actually carries.
        assertEquals("uuid-a", d.persistedId)
    }

    @Test
    fun `duplicate detection is instance-scoped not model-scoped`() {
        // Same modelId on inst-2 must resolve to inst-2's row, never inst-1's.
        val d = decideEntryAdd(inst2, "gpt-4o", live)
        assertFalse(d.allowed)
        assertEquals("uuid-c", d.persistedId)
    }

    @Test
    fun `empty live set always allows`() {
        val d = decideEntryAdd(inst1, "gpt-4o", emptyList())
        assertTrue(d.allowed)
        assertEquals("inst-1/gpt-4o", d.persistedId)
    }

    // ── decideCompactPinMigration: rename rewrite ──────────────────────────

    @Test
    fun `pin equal to the renamed-old key migrates to the new key`() {
        assertEquals(
            "inst-1/new-model",
            decideCompactPinMigration("inst-1/old-model", "inst-1/old-model", "inst-1/new-model"),
        )
    }

    @Test
    fun `pin pointing elsewhere is left alone`() {
        assertNull(
            decideCompactPinMigration("inst-2/gpt-4o", "inst-1/old-model", "inst-1/new-model"),
        )
    }

    @Test
    fun `empty pin is never treated as a match`() {
        // "" means "follow the session chain" — rewriting it on any rename
        // would silently pin the user to an unrelated model.
        assertNull(decideCompactPinMigration("", "inst-1/old-model", "inst-1/new-model"))
        assertNull(decideCompactPinMigration("", "", "inst-1/new-model"))
    }

    @Test
    fun `rename without an actual key change does not rewrite`() {
        assertNull(
            decideCompactPinMigration("inst-1/same", "inst-1/same", "inst-1/same"),
        )
    }

    // ── decideCompactPinCleanup: removal reset ─────────────────────────────

    @Test
    fun `pin inside the removed set is reset`() {
        assertTrue(decideCompactPinCleanup("inst-1/gone", setOf("inst-1/gone")))
        assertTrue(decideCompactPinCleanup("inst-1/gone", setOf("other", "inst-1/gone")))
    }

    @Test
    fun `pin outside the removed set is untouched`() {
        assertFalse(decideCompactPinCleanup("inst-1/keep", setOf("inst-1/gone")))
    }

    @Test
    fun `empty pin is never reset`() {
        assertFalse(decideCompactPinCleanup("", setOf("inst-1/gone", "")))
    }

    // ── source-shape: the wiring that these decisions plug into ────────────
    // Mutation ① (drop the addEntry guard) and the pin call sites are not
    // reachable from a JVM value test — ProviderRepository needs a real
    // Context — so they are pinned on the real source text.

    private fun repositorySource(): String =
        readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/data/repository/ProviderRepository.kt")

    private fun bodyOf(src: String, header: String): String {
        val i = src.indexOf(header)
        require(i >= 0) { "header not found: $header" }
        var depth = 0
        val open = src.indexOf('{', i)
        var j = open
        while (j < src.length) {
            if (src[j] == '{') depth++
            if (src[j] == '}') {
                depth--
                if (depth == 0) return src.substring(i, j + 1)
            }
            j++
        }
        error("unbalanced braces after: $header")
    }

    @Test
    fun `addEntry guard is unconditional, not gated on isCustom`() {
        val body = bodyOf(repositorySource(), "fun addEntry(")
        assertTrue("addEntry must call the composite-key decision", body.contains("decideEntryAdd("))
        assertFalse(
            "the duplicate guard must not sit inside an isCustom-only branch",
            body.contains("!entry.isCustom"),
        )
    }

    @Test
    fun `addEntry returns the canonical persisted id`() {
        val body = bodyOf(repositorySource(), "fun addEntry(")
        // The re-query is what makes the return value the id actually stored
        // (saveConfig canonicalizes the uuid); a bare `entry.id` return would
        // hand back the pre-persist temp uuid.
        assertTrue(body.contains("_config.value.modelEntries.firstOrNull"))
        assertFalse(body.trimEnd().endsWith("return@synchronized entry.id\n    }"))
    }

    @Test
    fun `updateEntry migrates the compact pin inside the rename branch`() {
        val body = bodyOf(repositorySource(), "fun updateEntry(")
        assertTrue(body.contains("decideCompactPinMigration("))
        assertTrue(body.contains("AgentRuntimeLimitsPrefs.compactModelEntryId()"))
        // Must live next to the lastUsedEntryId rewrite, i.e. inside
        // `if (renamed)`, not after the block closes.
        val renameIdx = body.indexOf("if (renamed)")
        val migrateIdx = body.indexOf("decideCompactPinMigration(")
        assertTrue(renameIdx in 0 until migrateIdx)
    }

    @Test
    fun `updateEntry migrates the voice overrides inside the rename branch`() {
        // [da1031-A] The two prefs-held voice overrides are the same class of
        // per-device secondary reference as lastUsedEntryId — a rename without
        // migrating them leaves the voice panel pointing at an entry id the
        // repository no longer resolves (silent fallback to the group binding).
        val body = bodyOf(repositorySource(), "fun updateEntry(")
        assertTrue(
            body.contains("if (voiceInputOverrideEntryId == oldKey) voiceInputOverrideEntryId = newUuid"),
        )
        assertTrue(
            body.contains("if (voiceOutputOverrideEntryId == oldKey) voiceOutputOverrideEntryId = newUuid"),
        )
        // Judged on the write points, not the function name: the rewrites must
        // sit INSIDE `if (renamed)` — the migration would otherwise fire on
        // every update, not just renames. decideCompactPinMigration( is still
        // inside the block, so ordering against it bounds the block from the
        // right without re-walking braces.
        val renameIdx = body.indexOf("if (renamed)")
        val voiceInIdx = body.indexOf("if (voiceInputOverrideEntryId == oldKey)")
        val voiceOutIdx = body.indexOf("if (voiceOutputOverrideEntryId == oldKey)")
        val pinIdx = body.indexOf("decideCompactPinMigration(")
        assertTrue(renameIdx in 0 until voiceInIdx)
        assertTrue(voiceInIdx < voiceOutIdx)
        assertTrue(voiceOutIdx < pinIdx)
    }

    @Test
    fun `updateEntry stamps the entry only in its default edit mode`() {
        // [fix/dv21-a3 A-03] The write face carries two modes: the default
        // (true) stamps "now" for every editor / agent caller, `false` is the
        // merge restore's mode and must carry the entry's own timestamp. A
        // bare `userModifiedAt = System.currentTimeMillis()` (the baseline
        // shape) re-dates every restore, which kills the merge's no-op
        // short-circuit and steals shadowVoiceProviders' arbitration.
        val src = repositorySource()
        val decl = src.indexOf("fun updateEntry(")
        require(decl >= 0) { "fun updateEntry( not found" }
        val header = src.substring(decl, src.indexOf("{", decl))
        assertTrue(
            "the write face needs a switch to tell a restore from an edit: $header",
            header.contains("stampUserModified: Boolean = true"),
        )
        val body = bodyOf(src, "fun updateEntry(")
        val writeIdx = body.indexOf("userModifiedAt =")
        require(writeIdx >= 0) { "no userModifiedAt write in updateEntry" }
        val write = body.substring(writeIdx, minOf(body.length, writeIdx + 200))
        assertTrue("the stamp must be guarded: $write", write.contains("if (stampUserModified)"))
        assertTrue("the restore mode keeps the entry's value: $write", write.contains("entry.userModifiedAt"))
    }

    @Test
    fun `merge hit branch writes the restored entry in the restore mode`() {
        // [fix/dv21-a3 A-03] Judge the CALL SITE, not the helper: the restore
        // must reach the write face with the non-stamping mode selected. A
        // bare `updateEntry(updated)` is the baseline defect.
        val body = bodyOf(repositorySource(), "fun mergeImportInstanceJSON(")
        assertTrue(
            "merge must route the restored entry through updateEntry: $body",
            body.contains("updateEntry("),
        )
        assertTrue(
            "the restored entry must be written with stampUserModified = false",
            body.contains("updateEntry(updated, stampUserModified = false)"),
        )
    }

    @Test
    fun `removeEntry clears the compact pin it just orphaned`() {
        val body = bodyOf(repositorySource(), "fun removeEntry(")
        assertTrue(body.contains("decideCompactPinCleanup("))
        assertTrue(body.contains("compactModelEntryId = \"\""))
    }

    @Test
    fun `removeInstance clears the compact pin for the whole removed set`() {
        // removeInstance cascades on its own and never routes through
        // removeEntry, so it needs its own cleanup.
        val body = bodyOf(repositorySource(), "fun removeInstance(")
        assertTrue(body.contains("decideCompactPinCleanup("))
        assertTrue(body.contains("compactModelEntryId = \"\""))
    }

    // ── [da1024-C] decideStaleRefCleanup: the prefs-held refs next to the pin ─
    // lastUsedEntryId / voiceInput·OutputOverrideEntryId are SharedPreferences
    // values held as `String?`. A bare `ref in ids` on such a property DOES
    // compile and behaves (null and "" are simply absent from the set — both
    // measured on kotlinc 2.0.21, see probe/jvm/snippet_semantics.kt), so this
    // predicate is not a typing workaround: it is one shared rule for three
    // refs across four removal paths, mirroring the pin's house pattern, and
    // testable without a Context.

    @Test
    fun `stale ref inside the removed set is cleared`() {
        assertTrue(decideStaleRefCleanup("inst-1/gone", setOf("inst-1/gone")))
        assertTrue(decideStaleRefCleanup("inst-1/gone", setOf("other", "inst-1/gone")))
    }

    @Test
    fun `stale ref outside the removed set is untouched`() {
        assertFalse(decideStaleRefCleanup("inst-1/keep", setOf("inst-1/gone")))
        assertFalse(decideStaleRefCleanup("inst-1/keep", emptySet()))
    }

    @Test
    fun `unset ref is never stale`() {
        // null = "never picked a model" (lastUsed) / "follow the group binding"
        // (voice overrides). Claiming a match would write a pointless pref
        // clear on every unrelated removal.
        assertFalse(decideStaleRefCleanup(null, setOf("inst-1/gone", "")))
        assertFalse(decideStaleRefCleanup(null, emptySet()))
    }

    @Test
    fun `empty-string ref is only stale when the removed set contains it`() {
        // The compact pin spells "follow the session chain" as "", the voice /
        // lastUsed prefs use null. Pinned so a future refactor of the shared
        // predicate cannot conflate the two idioms.
        assertTrue(decideStaleRefCleanup("", setOf("", "inst-1/gone")))
        assertFalse(decideStaleRefCleanup("", setOf("inst-1/gone")))
    }

    @Test
    fun `a System voice sentinel can never be stale`() {
        // The voice overrides may hold "<sentinel>/system-tts" for the
        // on-device engine; that is not a provider entry id.
        assertFalse(
            decideStaleRefCleanup(
                "minis-system-voice/system-tts",
                setOf("inst-1/gone", "inst-2/keep"),
            ),
        )
    }

    // ── [da1024-C] source-shape: the widened removal cascade ───────────────

    @Test
    fun `replaceEntries cascades pin and per-device refs inside the else branch`() {
        val body = bodyOf(repositorySource(), "fun replaceEntries(")
        // The else of `if (suspiciousShrink)` — a transient API hiccup must
        // keep both the curated agent-loop set AND the pin.
        val elseBlock = elseBlockAfter(body, "if (suspiciousShrink)")
        assertTrue(elseBlock.contains("decideCompactPinCleanup("))
        assertTrue(elseBlock.contains("compactModelEntryId = \"\""))
        assertTrue(elseBlock.contains("clearStaleSecondaryRefs(prunedEntryIds)"))
    }

    @Test
    fun `ensureVoiceTemplateModels cascades every ref of the dissolved seeds`() {
        val body = bodyOf(repositorySource(), "fun ensureVoiceTemplateModels(")
        val guard = body.indexOf("if (staleSeedIds.isNotEmpty())")
        assertTrue("the stale-seed guard must still be the entry point", guard >= 0)
        val block = body.substring(guard)
        // [da1031-A] the cascade must compare ENTRY ids — staleEntryIds, the
        // uuid/composite form every secondary reference is stored in. The
        // pre-fix spelling (bare staleSeedIds = baseModel.id values) made all
        // four routes a silent no-op.
        assertTrue(block.contains("val staleEntryIds"))
        assertTrue(block.contains("memberEntryIds.removeAll { it in staleEntryIds }"))
        assertTrue(block.contains("config.agentLoopModelEntryIds.removeAll { it in staleEntryIds }"))
        assertTrue(block.contains("decideCompactPinCleanup("))
        assertTrue(block.contains("clearStaleSecondaryRefs(staleEntryIds)"))
    }

    @Test
    fun `the per-device applier clears all three refs through the shared predicate`() {
        val body = bodyOf(repositorySource(), "private fun clearStaleSecondaryRefs(")
        assertTrue(body.contains("decideStaleRefCleanup(lastUsedEntryId"))
        assertTrue(body.contains("decideStaleRefCleanup(voiceInputOverrideEntryId"))
        assertTrue(body.contains("decideStaleRefCleanup(voiceOutputOverrideEntryId"))
    }

    @Test
    fun `ModelGroupsScreen re-reads the compact pin from a strongly held listener`() {
        // Mutation ② (inline the listener) must be caught here too: the guard
        // gate covers the GC hazard, this covers "the page stopped refreshing".
        val src = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/ModelGroupsScreen.kt",
        )
        assertTrue(src.contains("val listener = SharedPreferences.OnSharedPreferenceChangeListener"))
        assertTrue(src.contains("registerOnSharedPreferenceChangeListener(listener)"))
        assertTrue(src.contains("unregisterOnSharedPreferenceChangeListener(listener)"))
        assertTrue(src.contains("AgentRuntimeLimitsPrefs.KEY_COMPACT_MODEL_ENTRY_ID"))
        assertTrue(src.contains("compactModelEntryId = AgentRuntimeLimitsPrefs.compactModelEntryId()"))
    }

    /** Balanced `{ ... }` of the first `else` that follows [marker]. */
    private fun elseBlockAfter(src: String, marker: String): String {
        val m = src.indexOf(marker)
        require(m >= 0) { "marker not found: $marker" }
        val e = src.indexOf("else", m)
        require(e >= 0) { "no else branch after: $marker" }
        val open = src.indexOf('{', e)
        require(open >= 0) { "no block after the else of: $marker" }
        var depth = 0
        for (j in open until src.length) {
            if (src[j] == '{') depth++
            if (src[j] == '}') {
                depth--
                if (depth == 0) return src.substring(open, j + 1)
            }
        }
        error("unbalanced else block after: $marker")
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]. Gradle sets user.dir to the module dir; a sandbox JVM run
     * can start anywhere under the repo — no fixed depth works.
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
