package com.rikkaminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [review1112-p1-2] The restore-side half of the selection-only invariant.
 *
 * The rule "a hidden non-custom row is a disposable catalog copy, never a user
 * selection" was enforced on only ONE of the two restore paths: the OpenMinis
 * compat converter drops such rows ([decideLegacyHiddenImportKeep]), while the
 * native backup importer wrote `isHidden` through verbatim. A pre-split backup
 * carries one hidden row per catalog model, so restoring it re-minted exactly
 * the rows the one-shot migration deletes — and that migration's `prefs` gate
 * had already flipped, so nothing would ever clean them up again.
 *
 * [decideImportedRowKeep] is the extracted production rule; the table below is
 * the literal truth table. Kept in this file rather than folded into
 * [SelectionOnlyEntryRulesTest] so the restore-side rule has one obvious owner.
 */
class ImportedRowSelectionOnlyTest {

    // ── the truth table, as literals ────────────────────────────────────

    @Test
    fun hiddenNonCustomRow_isDropped() {
        assertFalse(
            "a pre-split hidden catalog row was allowed back into the table — " +
                "it is invisible to every !isHidden reader while the Manage sheet " +
                "still shows it selected, and the next backup drops it",
            decideImportedRowKeep(isHidden = true, isCustom = false),
        )
    }

    @Test
    fun hiddenCustomRow_isKept() {
        assertTrue(
            "a user-authored custom model was dropped by a restore",
            decideImportedRowKeep(isHidden = true, isCustom = true),
        )
    }

    @Test
    fun visibleRow_isKept() {
        assertTrue(decideImportedRowKeep(isHidden = false, isCustom = false))
        assertTrue(decideImportedRowKeep(isHidden = false, isCustom = true))
    }

    // ── the rule must be the SAME one the compat importer applies ───────

    @Test
    fun theCompatImporterDelegatesToThisRule() {
        // The compat converter's entry point must DELEGATE here, not carry its
        // own copy of the expression — a second literal is exactly how the two
        // restore paths diverged in the first place. Source-text assertion
        // because OpenMinisBackupCompat needs org.json + Android, but the claim
        // is structural: the alias must call this function.
        val src = readRepoFile(
            "src/main/java/com/rikkaminis/app/backup/OpenMinisBackupCompat.kt",
        )
        val body = src.substringAfter("internal fun decideLegacyHiddenImportKeep(", "")
        assertTrue(
            "the compat importer no longer delegates to decideImportedRowKeep — " +
                "the two restore paths can now diverge again",
            body.contains("decideImportedRowKeep"),
        )
        assertFalse(
            "the compat importer carries its own copy of the rule expression again",
            body.substringBefore("\n}").contains("!isHidden || isCustom"),
        )
    }

    /**
     * Locate a production source file.
     *
     * The relative path is written for the Gradle test working directory
     * (`src/android/app`), which is what CI uses. The sandbox JVM harness runs
     * from its own directory, so the same path is also tried under the repo
     * root found by walking up for `src/android/app/src/main` — without this
     * fallback the assertion would silently test nothing outside Gradle.
     */
    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val candidates = listOf(
                java.io.File(dir, relative),
                java.io.File(dir, "src/android/app/$relative"),
            )
            for (f in candidates) if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    // ── and the migration that owns the table must agree too ────────────

    @Test
    fun theMigrationKeepsTheSameRows() {
        // decideSelectionOnlyKeep additionally keeps voice-template seeds and
        // System voice entries, so it can only ever be MORE permissive than the
        // import rule. For a plain row (no seed, not System voice) the two must
        // agree — otherwise an imported row would be one the migration deletes.
        val hiddenCatalog = com.rikkaminis.app.data.model.ModelEntry(
            providerInstanceId = "inst",
            baseModel = com.rikkaminis.app.data.model.LLMModel(
                id = "some-catalog-model",
                displayName = "Some Catalog Model",
                provider = "Custom",
            ),
            isHidden = true,
            isCustom = false,
        )
        assertFalse(
            decideSelectionOnlyKeep(
                entry = hiddenCatalog,
                voiceTemplateModelIds = emptySet(),
                providerInstanceIdIsSystemVoice = false,
            ),
        )
        assertFalse(decideImportedRowKeep(isHidden = true, isCustom = false))
    }
}
