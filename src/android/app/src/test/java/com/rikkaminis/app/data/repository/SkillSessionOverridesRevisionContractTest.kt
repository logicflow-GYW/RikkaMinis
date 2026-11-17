package com.rikkaminis.app.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [W1118-D5 F-07] Contract: every write to `session_skill_overrides` moves
 * [SkillRepository.sessionOverridesRevision] (the class KDoc says so —
 * "[dv22-B4] Bumped by every write to session_skill_overrides").
 *
 * Row deletes were the hole: [SkillRepository.delete] and the stale-skill prune
 * inside [SkillRepository.loadAll] both ran `DELETE FROM session_skill_overrides`
 * without bumping, so a consumer that only watches the revision (the slash menu)
 * kept a deleted skill's per-session toggle cached. `setSessionOverride` /
 * `clearSessionOverrides` / `renameSessionOverrides` already bumped.
 *
 * Why a source contract instead of a behavioural test: the repository needs an
 * Android Context + SQLite, so there is no JVM seam for `delete()` /
 * `loadAll()` (a fake would have to reimplement the DB). The assertion is
 * structural — for every DELETE site, the function that contains it must also
 * bump — so deleting either bump, or adding a fourth DELETE without one, turns
 * it red. Same shape as the other wiring tests in this module.
 */
class SkillSessionOverridesRevisionContractTest {

    private val funHead = Regex(
        "(?m)^ {4}(?:private |internal |override |suspend |private suspend |internal suspend )*fun (\\w+)",
    )

    private fun source(): String {
        // Same root discovery as MirrorPublicBaseWiringTest: Gradle sets
        // user.dir to the module directory, the JVM harness to the repo root.
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, PATH)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw IllegalStateException("$PATH not found from user.dir=${System.getProperty("user.dir")}")
    }

    /** (body, function name) of the class-level function containing [offset]. */
    private fun enclosingFunction(src: String, offset: Int): Pair<String, String> {
        val heads = funHead.findAll(src).toList()
        val head = heads.lastOrNull { it.range.first < offset }
            ?: error("no enclosing function for source offset $offset")
        val next = heads.firstOrNull { it.range.first > head.range.first }?.range?.first ?: src.length
        return src.substring(head.range.first, next) to head.groupValues[1]
    }

    @Test
    fun `every delete of session_skill_overrides bumps the revision`() {
        val src = source()
        val hits = Regex("DELETE FROM session_skill_overrides").findAll(src)
            .map { it.range.first }
            .toList()
        assertTrue("no DELETE sites found — the anchor moved", hits.isNotEmpty())

        val offenders = hits
            .map { enclosingFunction(src, it) }
            .filter { (body, _) -> !body.contains("_sessionOverridesRevision.value") }
            .map { (_, name) -> name }
        assertTrue("writers that delete session_skill_overrides without bumping: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the two paths that used to skip the bump are still among the writers`() {
        // Guards the gate itself: if `delete()` or the loadAll prune is renamed,
        // the per-site assertion above would silently stop covering them.
        val src = source()
        val names = Regex("DELETE FROM session_skill_overrides").findAll(src)
            .map { enclosingFunction(src, it.range.first).second }
            .toSet()
        assertTrue("delete() no longer writes session_skill_overrides: $names", "delete" in names)
        assertTrue("loadAll() no longer prunes session_skill_overrides: $names", "loadAll" in names)
    }

    private companion object {
        const val PATH =
            "src/android/app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt"
    }
}
