package com.rikkaminis.app.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [FULLSCAN-1119 A] Wiring pins for the two fixes whose production files are
 * Android-bound and therefore cannot be compiled in the sandbox harness:
 *
 *  * **F-01** — Stage 7 of [ConfigBackup.import] must merge the live credential
 *    values into a secrets-free payload BEFORE importing it.
 *  * **F-19** — [OpenMinisBackupCompat]'s `supportsReasoning` passthrough must
 *    treat an explicit JSON null as "undeclared", not as `false`.
 *  * **F-20** — `importChatSections` must read `pinnedAt` /
 *    `thinkingOverride` / `reasoningContent` through the tri-state rule.
 *
 * The behaviour of each rule is pinned on a real JVM by
 * [McpCredentialFieldsTest] / [ChatRestoreFieldsTest]; what is left here is
 * exactly "is that rule reachable from the production call site", which is the
 * half a refactor silently drops (the rule keeps existing, the caller stops
 * calling it). Same house pattern as [MirrorPublicBaseWiringTest]: walk up from
 * `user.dir` so it works both under Gradle (module dir) and the JVM harness
 * (repo root).
 */
class BackupCredentialWiringTest {

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw IllegalStateException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    private val configBackup: String by lazy {
        readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/backup/ConfigBackup.kt")
    }

    private val compat: String by lazy {
        readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/backup/OpenMinisBackupCompat.kt")
    }

    /** Body of `fun <name>(` — brace-matched, so a later function cannot
     *  satisfy a pin that belongs to this one. */
    private fun functionBody(source: String, header: String): String {
        val start = source.indexOf(header)
        if (start < 0) error("$header not found — re-anchor this pin")
        val open = source.indexOf('{', start)
        if (open < 0) error("$header has no body — re-anchor this pin")
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
            i++
        }
        error("$header body is unbalanced — re-anchor this pin")
    }

    // ── F-01: Stage 7 merges before importing ────────────────────────────

    @Test
    fun stage7MergesLiveCredentialsIntoASecretsFreePayload() {
        val body = functionBody(configBackup, "suspend fun import(")
        assertTrue(
            "Stage 7 must call McpCredentialFields.mergeRedactedMcpServerPayload( — " +
                "without it a secrets-free restore imports the blanked header/env over " +
                "the live values and the server silently stops authenticating",
            body.contains("McpCredentialFields.mergeRedactedMcpServerPayload("),
        )
        // …gated on the payload NOT carrying secrets: the includeSecrets=true
        // direction must stay a plain overwrite.
        val callIdx = body.indexOf("McpCredentialFields.mergeRedactedMcpServerPayload(")
        val guardIdx = body.lastIndexOf("if (!payloadHasSecrets)", callIdx)
        assertTrue(
            "the merge call must sit behind `if (!payloadHasSecrets)` — an " +
                "unguarded merge would let a with-secrets backup be overwritten by " +
                "this device's older credentials",
            guardIdx >= 0,
        )
        // And the flag has to be the payload's own declaration.
        assertTrue(
            "payloadHasSecrets must be read from the document (includesSecrets)",
            body.contains("""val payloadHasSecrets = root.optBoolean("includesSecrets", false)"""),
        )
        // …and the merge must not run before that check: an unconditional merge
        // would clobber a with-secrets restore with this device's stale values.
        assertFalse(
            "the merge must not run before the payloadHasSecrets check",
            callIdx < guardIdx,
        )
        // The import call itself must still receive the (possibly merged) srv.
        assertTrue(
            "Stage 7 must still import the entry",
            body.contains("mcpRepo.importJSON(srv.toString())"),
        )
    }

    // ── F-19: the compat passthrough is tri-state ────────────────────────

    @Test
    fun compatSupportsReasoningTreatsExplicitNullAsUndeclared() {
        val idx = compat.indexOf("""mo.put("supportsReasoning",""")
        assertTrue("supportsReasoning passthrough not found — re-anchor this pin", idx >= 0)
        // The guard immediately above the write decides the shape.
        val window = compat.substring(maxOf(0, idx - 400), idx)
        assertTrue(
            "the supportsReasoning write must be guarded by `has(…) && !isNull(…)` — " +
                "`has()` alone is true for an explicit JSON null, and optBoolean folds " +
                "that to false, so an undeclared capability restored as 'explicitly off'",
            window.contains("""if (model.has("supportsReasoning") && !model.isNull("supportsReasoning"))"""),
        )
    }

    // ── F-20: importChatSections reads through the tri-state rule ────────

    @Test
    fun importChatSectionsUsesTheTriStateReads() {
        val body = functionBody(configBackup, "internal suspend fun importChatSections(")
        assertTrue(
            "pinnedAt / thinkingOverride must come from ChatRestoreFields — " +
                "`if (has(…)) optLong/optString` turns an explicit JSON null into 0/\"\"",
            body.contains("ChatRestoreFields.readSessionScalars("),
        )
        assertTrue(
            "reasoningContent must come from ChatRestoreFields — an explicit null was " +
                "capped into an empty (but present) blob",
            body.contains("ChatRestoreFields.readReasoningContent("),
        )
        assertFalse(
            "no raw `if (s.has(\"pinnedAt\"))` read may remain in importChatSections",
            body.contains("""if (s.has("pinnedAt"))"""),
        )
        assertFalse(
            "no raw `if (m.has(\"reasoningContent\"))` read may remain in importChatSections",
            body.contains("""if (m.has("reasoningContent"))"""),
        )
    }
}
