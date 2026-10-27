package com.rikkaminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/da1021-A] JVM tests for the skill-restore id threading (ISSUE-01) and
 * the cross-db session cleanup closure (ISSUE-10).
 *
 * The decision logic ([SkillRestorePolicy.resolveImportId]) is pure and
 * JVM-testable. The Android-side wiring (SkillRepository.add /
 * importFromContent / installBundledSkill / ChatRepository delete entries /
 * MinisApp wiring) needs Android's Context/SQLite and can't be instantiated
 * on the JVM — its presence is pinned by source-shape assertions with DEAD
 * LITERAL expectations (not derived from production constants, so a constant
 * edit can't mask a break).
 */
class SkillImportPreferredIdTest {

    // -- Pure logic: resolveImportId decides which id a preferred one lands under --

    @Test
    fun resolveImportId_validPreferredId_winsOverNameSlug() {
        assertEquals(
            "semantic-memory",
            SkillRestorePolicy.resolveImportId("semantic-memory", "name-slug"),
        )
    }

    @Test
    fun resolveImportId_illegalOrBlank_fallsBackToSlug() {
        // "/", "\", ".." escape the skills dir; blank/null carry no id. All of
        // them fall back to the name slug — an illegal preferredId is never
        // adopted verbatim (add() would turn it into /var/minis/skills/<id>/).
        assertEquals("fallback", SkillRestorePolicy.resolveImportId("../evil", "fallback"))
        assertEquals("fallback", SkillRestorePolicy.resolveImportId("a/b", "fallback"))
        assertEquals("fallback", SkillRestorePolicy.resolveImportId("a\\b", "fallback"))
        assertEquals("fallback", SkillRestorePolicy.resolveImportId("a..b", "fallback"))
        assertEquals("fallback", SkillRestorePolicy.resolveImportId("", "fallback"))
        assertEquals("fallback", SkillRestorePolicy.resolveImportId("   ", "fallback"))
        assertEquals("fallback", SkillRestorePolicy.resolveImportId(null, "fallback"))
    }

    @Test
    fun resolveImportId_nullPreferred_yieldsSlugOnly() {
        // The legacy behavior for callers without a preferred id (file / URL
        // / paste imports, SessionForkManager.copySkill).
        assertEquals("fresh-import", SkillRestorePolicy.resolveImportId(null, "fresh-import"))
    }

    // -- ISSUE-01 wiring: add() must resolve the id via the policy, both
    //    production callers must pass their resolved id through --

    @Test
    fun add_firstAssignment_resolvesThroughPolicy() {
        val src = readRepoFile("app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt")
        // The add() body's first `val id =` statement must go through the
        // policy with the preferredId parameter (dead literal comparison).
        val addBody = src.substringAfter("): Skill? {")
        val line = addBody
            .substringAfter("val id = ")
            .substringBefore("\n")
        assertTrue(
            "add() first id assignment must resolve via SkillRestorePolicy.resolveImportId",
            line == "SkillRestorePolicy.resolveImportId(preferredId, slugify(name))",
        )
    }

    @Test
    fun add_signature_hasPreferredIdParameter() {
        val src = readRepoFile("app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt")
        val sig = src.substringAfter("fun add(").substringBefore("): Skill? {")
        assertTrue(
            "add() must accept preferredId: String? = null as the last parameter",
            sig.contains("preferredId: String? = null"),
        )
    }

    @Test
    fun importFromContent_returnAdd_passesResolvedPreferredId() {
        val fn = readRepoFile("app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt")
            .substringAfter("fun importFromContent(")
            .substringBefore("fun skillMdPath")
        val returnAdd = fn.substringAfter("return add(").substringBefore("\n        )")
        assertTrue(
            "importFromContent's return add() must pass preferredId = id",
            returnAdd.contains("preferredId = id"),
        )
    }

    @Test
    fun installBundledSkill_add_passesDirNameAsPreferredId() {
        val fn = readRepoFile("app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt")
            .substringAfter("private fun installBundledSkill(dirName: String) {")
            .substringBefore("private fun isAdoptableBundledRow(")
        val addCall = fn.substringAfter("add(").substringBefore("\n            )")
        assertTrue(
            "installBundledSkill's add() must pass preferredId = dirName",
            addCall.contains("preferredId = dirName"),
        )
    }

    // -- [dv22-B6 §1042-B-07] the URL import must carry the caller's resolved
    //    id: the browser resolves the update target BEFORE importing, and a
    //    repository that drops the value on the floor re-creates the
    //    duplicate-under-slug defect the dedup was meant to close --

    @Test
    fun importFromGitHub_signature_hasPreferredIdParameter() {
        val src = readRepoFile("app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt")
        val sig = src.substringAfter("suspend fun importFromGitHub(").substringBefore("): Skill? =")
        assertTrue(
            "importFromGitHub must accept preferredId: String? = null as the last parameter",
            sig.contains("preferredId: String? = null"),
        )
    }

    @Test
    fun importFromGitHub_passesPreferredIdToImportFromContent() {
        val fn = readRepoFile("app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt")
            .substringAfter("suspend fun importFromGitHub(")
            .substringBefore("/** Outcome of [updateFromURL]")
        val call = fn.substringAfter("importFromContent(").substringBefore("\n")
        assertTrue(
            "importFromGitHub's importFromContent call must pass preferredId = preferredId " +
                "(without it the landing id is always slugify(name) and a renamed / " +
                "custom-id local record is duplicated instead of refreshed)",
            call.contains("preferredId = preferredId"),
        )
    }

    // -- ISSUE-10 wiring: cross-db session cleanup closure --

    @Test
    fun chatRepository_constructor_exposesOnSessionDeleted() {
        val src = readRepoFile("app/src/main/java/com/rikkaminis/app/data/repository/ChatRepository.kt")
        val ctor = src.substringAfter("class ChatRepository(").substringBefore(") {")
        assertTrue(
            "ChatRepository must expose val onSessionDeleted: ((String) -> Unit)? = null",
            ctor.contains("val onSessionDeleted: ((String) -> Unit)? = null"),
        )
    }

    @Test
    fun chatRepository_bothDeleteEntries_invokeOnSessionDeleted() {
        val src = readRepoFile("app/src/main/java/com/rikkaminis/app/data/repository/ChatRepository.kt")
        val deleteSession = src
            .substringAfter("suspend fun deleteSession(id: String): SessionFileStore.DeleteResult? {")
            .substringBefore("suspend fun deleteSessionRowOnly")
        val rowOnly = src
            .substringAfter("suspend fun deleteSessionRowOnly(id: String) {")
            .substringBefore("\n    /**")
        assertTrue(
            "deleteSession must invoke onSessionDeleted?.invoke(",
            deleteSession.contains("onSessionDeleted?.invoke(id)"),
        )
        assertTrue(
            "deleteSessionRowOnly must invoke onSessionDeleted?.invoke(",
            rowOnly.contains("onSessionDeleted?.invoke(id)"),
        )
    }

    @Test
    fun chatRepository_invokeRunsAfterSessionRowDeletion() {
        // The cleanup must run AFTER the session row is gone (the rows are the
        // source of truth for "session exists").
        val src = readRepoFile("app/src/main/java/com/rikkaminis/app/data/repository/ChatRepository.kt")
        val deleteSession = src
            .substringAfter("suspend fun deleteSession(id: String): SessionFileStore.DeleteResult? {")
            .substringBefore("suspend fun deleteSessionRowOnly")
        val rowDelete = deleteSession.indexOf("dao.deleteSession(id)")
        val invoke = deleteSession.indexOf("onSessionDeleted?.invoke(id)")
        assertFalse("deleteSession body must contain dao.deleteSession(id)", rowDelete < 0)
        assertFalse("deleteSession body must contain onSessionDeleted?.invoke(id)", invoke < 0)
        assertTrue(
            "onSessionDeleted must be invoked AFTER dao.deleteSession(id)",
            invoke > rowDelete,
        )
    }

    @Test
    fun minisApp_skillMcpConstructedBeforeChatRepository_andCallbackReapsBoth() {
        val src = readRepoFile("app/src/main/java/com/rikkaminis/app/MinisApp.kt")
        // MinisApp's dbVersion-guard KDoc quotes `chatRepository = ChatRepository(`
        // inside a comment — anchor on code lines only.
        val skillLine = indexOfCode(src, "skillRepository = SkillRepository(")
        val mcpLine = indexOfCode(src, "mcpRepository = MCPRepository(")
        val chatLine = indexOfCode(src, "chatRepository = ChatRepository(")
        assertFalse("MinisApp must construct skillRepository", skillLine < 0)
        assertFalse("MinisApp must construct mcpRepository", mcpLine < 0)
        assertFalse("MinisApp must construct chatRepository", chatLine < 0)
        // The callback must reference the real singletons: skill/mcp are built
        // BEFORE chatRepository (a second copy built after would reap a
        // repository the app doesn't use).
        assertTrue(
            "skillRepository must be constructed before chatRepository",
            skillLine < chatLine,
        )
        assertTrue(
            "mcpRepository must be constructed before chatRepository",
            mcpLine < chatLine,
        )
        val ctor = src.substringAfter("chatRepository = ChatRepository(")
            .substringBefore("\n        )")
        assertTrue(
            "chatRepository must be built with an onSessionDeleted callback",
            ctor.contains("onSessionDeleted = { sid ->"),
        )
        assertTrue(
            "callback must reap skill overrides",
            ctor.contains("skillRepository.clearSessionOverrides(sid)"),
        )
        assertTrue(
            "callback must reap mcp overrides",
            ctor.contains("mcpRepository.clearSessionOverrides(sid)"),
        )
    }

    private fun indexOfCode(src: String, needle: String): Int {
        var idx = 0
        while (idx < src.length) {
            val i = src.indexOf(needle, idx)
            if (i < 0) return -1
            val lineStart = src.lastIndexOf('\n', i) + 1
            val line = src.substring(lineStart).lineSequence().firstOrNull()?.trim() ?: ""
            if (!line.startsWith("//")) return i
            idx = i + 1
        }
        return -1
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
}
