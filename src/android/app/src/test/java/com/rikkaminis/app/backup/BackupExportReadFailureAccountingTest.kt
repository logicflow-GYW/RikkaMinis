package com.rikkaminis.app.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [RC-U03] Every export failure that loses DATA must reach the `readFailures`
 * channel, because that count is the only thing the two manual export paths
 * surface ("this backup may be incomplete") and the only thing the automatic
 * backup now folds into its Partial outcome.
 *
 * The defect: `buildSections` counted per-field failures and missing env-var
 * values, but two losses stopped at a `Log.w`:
 *   - a provider whose `exportInstanceJSON` came back unparseable was dropped
 *     from the payload entirely (the restore simply lacked it);
 *   - a skill that fell back to SKILL.md-only (no archive, or an archive over
 *     MAX_SKILL_ARCHIVE_BYTES) lost its bundled scripts/references.
 *
 * Source-shape test: `buildSections` needs the whole Android surface
 * (Base64 / JSONObject / the repositories), so the sandbox cannot execute it.
 * The assertions are scoped to the section that owns each loss, with hard-coded
 * literals (never derived from production constants) and a discriminating
 * negative control per arm.
 */
class BackupExportReadFailureAccountingTest {

    private val rel = "app/src/main/java/com/rikkaminis/app/backup/ConfigBackup.kt"

    private fun src(): String = readRepoFile(rel)

    private fun section(startAnchor: String, endAnchor: String): String {
        val text = src()
        val start = text.indexOf(startAnchor)
        assertTrue("start anchor lost: $startAnchor", start >= 0)
        val end = text.indexOf(endAnchor, start)
        assertTrue("end anchor lost: $endAnchor (after $startAnchor)", end > start)
        return text.substring(start, end)
    }

    /** Strip `//` line comments so a sentence about the fix cannot satisfy it. */
    private fun codeOnly(text: String): String =
        text.lines().joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) else line
        }

    // ── arm 1: an unparseable provider is a failed export, not a silent skip ──

    private fun providerSection() =
        section("val providers = JSONArray()", "// Skills are a directory tree per skill")

    private fun assertProviderAccounting(body: String) {
        val code = codeOnly(body)
        val catchAt = code.indexOf("} catch (t: Throwable) {")
        assertTrue("the provider JSONObject try/catch is gone — re-point this arm", catchAt >= 0)
        val branch = code.substring(catchAt, minOf(code.length, catchAt + 700))
        assertTrue(
            "an unparseable provider must increment readFailures — otherwise the provider is " +
                "dropped from the payload and no caller ever reports the backup as incomplete",
            branch.contains("readFailures++"),
        )
        assertFalse(
            "the bare `continue` after a log line is the defect itself",
            !branch.contains("readFailures++") && branch.contains("continue"),
        )
    }

    @Test
    fun anUnparseableProviderCountsAsAReadFailure() {
        assertProviderAccounting(providerSection())
    }

    @Test
    fun theProviderAccountingProbeHasDiscriminatingPower() {
        val body = providerSection()
        assertProviderAccounting(body)
        val mutated = body.replaceFirst(
            "readFailures++\n                Log.w(TAG, \"export: unparseable provider",
            "Log.w(TAG, \"export: unparseable provider",
        )
        var rejected = false
        try {
            assertProviderAccounting(mutated)
        } catch (_: AssertionError) {
            rejected = true
        }
        assertTrue("negative control: the probe accepted a body that dropped the counter", rejected)
    }

    // ── arm 2: a skill degraded to SKILL.md-only is a failed export ──────────

    private fun skillSection() =
        section("val skills = JSONArray()", "// Memory: `memory.enabled` in the registry")

    private fun assertSkillAccounting(body: String) {
        val code = codeOnly(body)
        val elseAt = code.indexOf("entry.put(\"body\", skill.body)")
        assertTrue("the SKILL.md-only fallback branch is gone — re-point this arm", elseAt >= 0)
        val branch = code.substring(elseAt, minOf(code.length, elseAt + 1200))
        assertTrue(
            "the SKILL.md-only fallback loses bundled scripts/references, so it must increment " +
                "readFailures (it used to stop at the log line)",
            branch.contains("readFailures++"),
        )
    }

    @Test
    fun aSkillDegradedToSkillMdOnlyCountsAsAReadFailure() {
        assertSkillAccounting(skillSection())
    }

    @Test
    fun theSkillAccountingProbeHasDiscriminatingPower() {
        val body = skillSection()
        assertSkillAccounting(body)
        val mutated = body.replaceFirst(
            "readFailures++\n                    Log.w(",
            "Log.w(",
        )
        var rejected = false
        try {
            assertSkillAccounting(mutated)
        } catch (_: AssertionError) {
            rejected = true
        }
        assertTrue("negative control: the probe accepted a body without the counter", rejected)
    }

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}
