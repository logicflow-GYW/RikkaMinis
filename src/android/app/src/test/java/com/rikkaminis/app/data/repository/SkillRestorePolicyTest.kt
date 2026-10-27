package com.rikkaminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/audit-1015-b2] JVM tests for backup-restore skill import decisions
 * (audit 2026-10-15, BUG-02A/B/C/D + BUG-04 #2/#3).
 *
 * The decision logic lives in [SkillRestorePolicy] (pure, JVM-testable — the
 * SkillMetadataSync / SkillAssetSync split). The Android-side wiring
 * (importFromContent / importFromArchive / installBundledSkill upgrade branch
 * / ConfigBackup restore loop) can't be instantiated on the JVM, so its
 * presence is pinned by source-grep assertions — the same approach
 * CompactExhaustedRescueTest uses for Context-dependent call sites.
 */
class SkillRestorePolicyTest {

    // -- BUG-02C: preferredId adoption --

    @Test
    fun preferredId_existingSkill_resolvesToPreferredId() {
        // Skill already installed locally → the id resolves to the preferred
        // one and importFromContent's replace branch refreshes it in place.
        assertEquals(
            "semantic-memory",
            SkillRestorePolicy.resolveImportId("semantic-memory", "new-slug"),
        )
    }

    @Test
    fun preferredId_cleanInstall_adoptsValidIdInsteadOfSlugFallback() {
        // On a clean install _skills is empty at restore time; the exported id
        // is the user's own backup payload, so it is adopted verbatim instead
        // of being silently dropped in favour of slugify(parsed.name).
        assertEquals(
            "my-renamed-skill",
            SkillRestorePolicy.resolveImportId("my-renamed-skill", "my-renamed-skill-slug"),
        )
    }

    @Test
    fun preferredId_unsafeOrBlank_rejected() {
        // "../", "/" and "\" escape the skills dir; blank/null carry no id.
        assertEquals("fallback-slug", SkillRestorePolicy.resolveImportId("../evil", "fallback-slug"))
        assertEquals("fallback-slug", SkillRestorePolicy.resolveImportId("a/b", "fallback-slug"))
        assertEquals("fallback-slug", SkillRestorePolicy.resolveImportId("a\\b", "fallback-slug"))
        assertEquals("fallback-slug", SkillRestorePolicy.resolveImportId("a..b", "fallback-slug"))
        assertEquals("fallback-slug", SkillRestorePolicy.resolveImportId("", "fallback-slug"))
        assertEquals("fallback-slug", SkillRestorePolicy.resolveImportId("   ", "fallback-slug"))
        assertEquals("fallback-slug", SkillRestorePolicy.resolveImportId(null, "fallback-slug"))
    }

    // -- BUG-02B: usage metadata restore --

    @Test
    fun restoreUsage_appliesAllThreeFieldsFromBackup() {
        val out = SkillRestorePolicy.restoreUsage(
            current = SkillRestorePolicy.Usage(installedAt = 100L, updatedAt = 200L, useCount = 3.0),
            backup = SkillRestorePolicy.Usage(installedAt = 55L, updatedAt = 66L, useCount = 42.0),
        )
        assertEquals(55L, out.installedAt)
        assertEquals(66L, out.updatedAt)
        assertEquals(42.0, out.useCount, 0.0)
    }

    @Test
    fun restoreUsage_oldBackupWithoutKeys_leavesFreshValuesUntouched() {
        // Older backups decode missing keys as 0 — must not zero useCount or
        // stamp epoch-0 timestamps over the freshly created row.
        val out = SkillRestorePolicy.restoreUsage(
            current = SkillRestorePolicy.Usage(installedAt = 100L, updatedAt = 200L, useCount = 3.0),
            backup = SkillRestorePolicy.Usage(installedAt = 0L, updatedAt = 0L, useCount = 0.0),
        )
        assertEquals(100L, out.installedAt)
        assertEquals(200L, out.updatedAt)
        assertEquals(3.0, out.useCount, 0.0)
    }

    // -- Wiring: Android-side call sites must actually use the policy --

    @Test
    fun importFromContent_resolvesIdThroughPolicy() {
        val fn = readRepoFile("app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt")
            .substringAfter("fun importFromContent(")
            .substringBefore("fun skillMdPath")
        assertTrue(
            "importFromContent must resolve the id via SkillRestorePolicy.resolveImportId",
            fn.contains("SkillRestorePolicy.resolveImportId(preferredId, slugify(parsed.name))"),
        )
        // The replace/add branch keyed on existence is still there — it
        // decides replace-vs-add, not whether the preferred id survives.
        assertTrue(
            "replace branch keyed on existence must remain",
            fn.contains("_skills.value.any { it.id == id }"),
        )
    }

    @Test
    fun importFromArchive_signatureAndPassthrough() {
        val fn = readRepoFile("app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt")
            .substringAfter("fun importFromArchive(")
            .substringBefore("// -- Import from Zip Archive --")
        assertTrue(
            "importFromArchive must accept a source parameter",
            fn.contains("source: ImportSource = ImportSource.FILE"),
        )
        assertTrue(
            "importFromArchive must accept a sourceURL parameter",
            fn.contains("sourceURL: String? = null"),
        )
        assertTrue(
            "importFromArchive must forward both to importFromContent",
            fn.contains("importFromContent(") &&
                fn.substringAfter("importFromContent(").substringBefore(") ?: return null")
                    .contains("source"),
        )
    }

    @Test
    fun configBackup_archiveBranch_passesSourceAndRestoresUsage() {
        val stage5 = readRepoFile("app/src/main/java/com/rikkaminis/app/backup/ConfigBackup.kt")
            .substringAfter("Stage 5: skills")
            .substringBefore("Stage 6: memory files")
        val archiveCall = stage5.substringAfter("skillRepo.importFromArchive(").substringBefore("} else {")
        assertTrue(
            "archive branch must pass the exported importSource through",
            archiveCall.contains("ImportSource.from("),
        )
        assertTrue(
            "archive branch must pass the exported sourceURL through",
            archiveCall.contains("sourceURL"),
        )
        assertTrue(
            "restore loop must call restoreUsageMetadata for BUG-02B",
            stage5.contains("skillRepo.restoreUsageMetadata("),
        )
        assertTrue(
            "restore loop must read installedAt/updatedAt/useCount",
            stage5.contains("s.optLong(\"installedAt\", 0L)") &&
                stage5.contains("s.optLong(\"updatedAt\", 0L)") &&
                stage5.contains("s.optDouble(\"useCount\", 0.0)"),
        )
    }

    @Test
    fun installBundledSkill_upgradeBranch_refreshesFrontmatterNotSource() {
        val upgrade = readRepoFile("app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt")
            .substringAfter("if (existing != null) {")
            .substringBefore("Upgraded bundled skill")
        assertTrue(
            // [fix/da1023-E] Re-anchored: the batch converges frontmatter
            // extraction onto ParsedSkill (one extraction per document), so the
            // old per-site `extractFrontmatterBlock(content)` call this
            // assertion pinned is gone from the upgrade branch — the refresh
            // now rides on `frontmatter = parsed.frontmatter`, which resolves
            // to the same incoming block.
            "upgrade branch must refresh frontmatter from the incoming content",
            upgrade.contains("frontmatter = parsed.frontmatter"),
        )
        assertFalse(
            "upgrade branch must not rewrite importSource (version gate's contract)",
            upgrade.contains("importSource ="),
        )
    }

    // -- Wiring: editor call sites (BUG-04 #2/#3) --

    @Test
    fun skillEditor_pinnedPreferredId_andCarriesVersion() {
        val screen = readRepoFile("app/src/main/java/com/rikkaminis/app/ui/settings/SkillsManagementScreen.kt")
        val edit = screen
            .substringAfter("SKILL.md edits go through importFromContent")
            .substringBefore("} else {")
        assertTrue(
            "SKILL.md edit save must pin preferredId to the edited skill (BUG-04 #2)",
            edit.contains("preferredId = skillId"),
        )
        val fileUpdate = screen
            .substringAfter("Re-parse the supplied SKILL.md")
            .substringBefore("updateStatus = if (ok)")
        assertTrue(
            "file update must pass the parsed version (BUG-04 #3)",
            fileUpdate.contains("version = parsed.version"),
        )
    }

    // -- Wiring: 锁边界（da1029-C / I04） --

    @Test
    fun recordSkillUse_publishesTheMirrorInsideTheAddLock() {
        // [da1029-C] SkillRepository 在 JVM 上装不起来（Context +
        // SQLiteOpenHelper + OkHttp），所以「写回与 add() 同锁」这件事按源码形状
        // 钉住 —— 与上面的 editor 调用点断言同一手法。
        //
        // 判据落在**锁归属**上而不是右侧表达式的形状：块内
        // `_skills.value = <任意表达式>` 都算合规（内联 map，或先赋局部变量）。
        val fn = readRepoFile("app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt")
            .substringAfter("fun recordSkillUse(")
            .substringBefore("private fun normalizeUseCounts")

        val lockAt = fn.indexOf("synchronized(addLock)")
        assertTrue("recordSkillUse 必须在 addLock 内做读-改-写", lockAt >= 0)

        // 与调用方同缩进（8 空格）的 `}` 就是 synchronized 块的收尾。
        val blockEnd = fn.indexOf("\n        }", lockAt)
        assertTrue("找不到 synchronized(addLock) 块的收尾", blockEnd > 0)
        val inLock = fn.substring(lockAt, blockEnd)
        val afterLock = fn.substring(blockEnd)

        assertTrue(
            "（da1029-C）use_count 的写回必须在 synchronized(addLock) 块内",
            inLock.contains("_skills.value ="),
        )
        assertFalse(
            "（da1029-C）锁释放后不得再发布 _skills.value —— 并发 recordSkillUse 会丢一次" +
                "增量（内存落后 DB），并发 add() 会被基于旧快照的写回抹掉",
            afterLock.contains("_skills.value ="),
        )
    }

    // -- [dv16-A05] sparse-clear decision + source wiring --

    @Test
    fun sparseClear_equalsGlobal_deletesTheOverrideRow() {
        // (true, true) / (false, false): the session value equals the CURRENT
        // global default → DELETE the row so a later global flip propagates.
        assertTrue(shouldDeleteSessionSkillOverride(true, true))
        assertTrue(shouldDeleteSessionSkillOverride(false, false))
    }

    @Test
    fun divergentValue_writesTheOverrideRow() {
        // Session value genuinely diverges from the global default → keep
        // (write) the row.
        assertFalse(shouldDeleteSessionSkillOverride(true, false))
        assertFalse(shouldDeleteSessionSkillOverride(false, true))
    }

    @Test
    fun unknownSkill_conservativelyWritesTheRow() {
        // globalEnabled == null (skill not in memory yet) → never delete;
        // the row is written so the toggle survives the load.
        assertFalse(shouldDeleteSessionSkillOverride(null, true))
        assertFalse(shouldDeleteSessionSkillOverride(null, false))
    }

    @Test
    fun setSessionOverride_hasTheSparseClearBranch() {
        val src = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt",
        )
        val fn = memberSlice(setSessionOverrideMarker, src)
        assertTrue(
            "setSessionOverride must DELETE the row when the value equals the global default",
            fn.contains("DELETE FROM session_skill_overrides WHERE session_id=? AND skill_id=?"),
        )
        assertTrue(
            "the DELETE branch must be decided by the shared pure decision",
            fn.contains("shouldDeleteSessionSkillOverride("),
        )
        assertTrue(
            "the global default must be read from the in-memory skill list first",
            fn.contains("_skills.value.find { it.id == skillId }?.isEnabled"),
        )
        assertTrue(
            "the divergent branch must still write the row",
            fn.contains("INSERT OR REPLACE INTO session_skill_overrides"),
        )
    }

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

    /** Lines of the member declared at [marker], up to the next sibling member. */
    private fun memberSlice(marker: String, src: String): String {
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains(marker) }
        assertTrue("marker not found: $marker", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { i ->
            val line = lines[i]
            val indent = line.takeWhile { it == ' ' }.length
            indent <= 4 && line.isNotBlank() && !line.trimStart().startsWith("//") &&
                !line.trimStart().startsWith("*") &&
                Regex("^(private|internal|public|protected|fun|val|var|@)").containsMatchIn(line.trimStart())
        } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private companion object {
        const val setSessionOverrideMarker = "fun setSessionOverride(sessionId: String, skillId: String, enabled: Boolean)"
    }
}
