package com.rikkaminis.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [da1028-B / ISSUE-03] Two fields disappeared into the gap between a screen
 * and the config registry, and neither the compiler nor the screen could see
 * it:
 *
 * 1. `envvars.privacyMode` is a registered READWRITE scalar, but
 *    `ConfigBackup.BACKED_UP_SCOPES` did not list its scope — so the field was
 *    written by nobody on export *and* a payload carrying it was rejected on
 *    the way back in ("outside backup scope"). Migrating devices silently lost
 *    the privacy switch.
 * 2. `background.overlay` was a real Settings → Background switch with no
 *    registry entry: `minis-config set background.overlay …` answered
 *    "unknown field" while the same toggle worked by tap.
 *
 * The scope list is asserted as a *value* (a JVM run against the real object),
 * because a text probe cannot tell "envvars is in the set" from "the string
 * appears in the KDoc above it". The registration is asserted as a *shape*,
 * because its body needs a live Context — with the single-source rule
 * (constants, never re-typed key literals) pinned as its own property.
 *
 * Expected sets are written out as literals on purpose: deriving them from the
 * production constant under test would let a mutation move both sides at once
 * and pass. Every shape assertion carries an in-test negative control.
 */
class ConfigBackupEnvvarsAndOverlayTest {

    private val builtinsRel =
        "src/android/app/src/main/java/com/rikkaminis/app/config/ConfigBuiltins.kt"

    private val configBackupRel =
        "src/android/app/src/main/java/com/rikkaminis/app/backup/ConfigBackup.kt"

    // ── 1. the scope whitelist (real JVM value) ──────────────────────────

    /** Literal copy of the eight scopes that predate this fix, plus the new one. */
    private val expectedScopes = setOf(
        "appearance",   // theme, font scale, chat bubble/background look
        "chat",         // composer + rendering preferences
        "background",   // background image / effect settings
        "defaults",     // default model group, agent-loop entries and groups
        "soul",         // SOUL.md persona fields
        "memory",       // memory feature toggles
        "logs",         // log retention preferences
        "runtime",      // agent-runtime knobs (e.g. max concurrent sessions)
        "envvars",      // env-var privacy mode toggle
    )

    @Test
    fun `backup scope whitelist carries envvars`() {
        assertEquals(
            "BACKED_UP_SCOPES changed shape — envvars.privacyMode is dropped from " +
                "backups and refused on restore when its scope is missing",
            expectedScopes,
            ConfigBackup.BACKED_UP_SCOPES,
        )
    }

    @Test
    fun `whitelist assertion has discriminating power`() {
        // Negative control for the assertion above: the pre-fix set (no
        // "envvars") must NOT satisfy it, otherwise the test would pass on the
        // broken tree too.
        assertNotEquals(
            "the assertion above cannot detect a missing scope",
            ConfigBackup.BACKED_UP_SCOPES,
            expectedScopes - "envvars",
        )
        assertTrue("envvars must be in the whitelist", "envvars" in ConfigBackup.BACKED_UP_SCOPES)
    }

    // ── 2. the overlay registration (source shape) ───────────────────────

    private fun registerBackgroundBody(src: String = readRepoFile(builtinsRel)): String {
        val start = src.indexOf("private fun registerBackground(")
        assertTrue("registerBackground not found in $builtinsRel", start >= 0)
        val end = src.indexOf("private fun registerLogs(", start)
        assertTrue("registerLogs must follow registerBackground (probe anchor lost)", end > start)
        return src.substring(start, end)
    }

    private fun assertOverlayRegistered(rawBody: String) {
        val body = codeOnly(rawBody)
        assertTrue(
            "registerBackground must register path \"background.overlay\" — otherwise the " +
                "Settings → Background overlay switch is unreachable via minis-config",
            body.contains("path = \"background.overlay\""),
        )
        assertTrue(
            "background.overlay must go through BackgroundSettingsRepository.KEY_BG_OVERLAY_ENABLED",
            body.contains("KEY_BG_OVERLAY_ENABLED"),
        )
        assertTrue(
            "background.overlay must go through BackgroundSettingsRepository.DEFAULT_BG_OVERLAY_ENABLED",
            body.contains("DEFAULT_BG_OVERLAY_ENABLED"),
        )
        assertFalse(
            "the raw prefs key literal is back in the registry — it drifts out of " +
                "BackgroundSettingsRepository silently (F-238/F-239 shape)",
            body.contains("\"backgroundOverlayEnabled\""),
        )
    }

    @Test
    fun `registerBackground wires the overlay switch to the repository constants`() {
        assertOverlayRegistered(registerBackgroundBody())
    }

    // ── 3. env var restore: the decision, run for real on the JVM ────────
    //
    // [T-envvar-restore-deadlock] Stage 4 skipped every key that already
    // existed, which made the reinstall case unrecoverable (see the KDoc on
    // EnvVarRestorePlan). These arms exercise `planEnvVarRestore` — the real
    // production function, pure Kotlin, no Android types — and every expected
    // value is a literal, never derived from the code under test.

    /** Reinstall shape: the keystore re-bind left the entry with no value. */
    @Test
    fun `empty local value takes the backup value`() {
        for (localValue in listOf<String?>(null, "")) {
            val plan = planEnvVarRestore(
                key = "OPENAI_API_KEY",
                localValue = localValue,
                localNote = "",
                localGroup = "",
                backupValue = "sk-live-0123456789",
                backupNote = "",
                backupGroup = "",
            )
            assertEquals("sk-live-0123456789", plan.value)
            assertTrue("a restored value counts as imported", plan.writesValue)
            assertTrue(plan.touchesAnything)
            assertEquals(null, plan.report)
        }
    }

    /** Two live values differ → the local one survives, visibly. */
    @Test
    fun `live local value is kept when the backup differs`() {
        val plan = planEnvVarRestore(
            key = "OPENAI_API_KEY",
            localValue = "sk-current",
            localNote = "",
            localGroup = "",
            backupValue = "sk-stale-from-backup",
            backupNote = "",
            backupGroup = "",
        )
        assertEquals("the masked local value must not be overwritten", null, plan.value)
        assertFalse(plan.writesValue)
        assertFalse("nothing else changed, so no repository write is needed", plan.touchesAnything)
        assertEquals(
            "env var \"OPENAI_API_KEY\": local value kept (backup differs) — " +
                "replace it manually if the backup is right",
            plan.report,
        )
    }

    /** Same value on both sides → no write, and no skip noise either. */
    @Test
    fun `equal values are idempotent`() {
        val plan = planEnvVarRestore(
            key = "OPENAI_API_KEY",
            localValue = "sk-same",
            localNote = "",
            localGroup = "",
            backupValue = "sk-same",
            backupNote = "",
            backupGroup = "",
        )
        assertEquals(null, plan.value)
        assertEquals(null, plan.report)
        assertFalse(plan.touchesAnything)
    }

    /** Secret-free backup → no value anywhere; the entry is left alone. */
    @Test
    fun `secret free backup writes nothing`() {
        for (localValue in listOf<String?>(null, "", "sk-kept")) {
            val plan = planEnvVarRestore(
                key = "OPENAI_API_KEY",
                localValue = localValue,
                localNote = "",
                localGroup = "",
                backupValue = "",
                backupNote = "",
                backupGroup = "",
            )
            assertEquals("a secret-free backup carries no value to write", null, plan.value)
            assertFalse(plan.writesValue)
            assertFalse(plan.touchesAnything)
            assertEquals("no report line — the entry is unchanged", null, plan.report)
        }
    }

    /** Metadata (never masked) is backfilled into blanks, never overwritten. */
    @Test
    fun `note and group are backfilled into blanks only`() {
        val filled = planEnvVarRestore(
            key = "OPENAI_API_KEY",
            localValue = "",
            localNote = "",
            localGroup = "   ",
            backupValue = "sk-live",
            backupNote = "  primary key  ",
            backupGroup = "Cloudflare",
        )
        assertEquals("primary key", filled.note)
        assertEquals("Cloudflare", filled.group)

        val kept = planEnvVarRestore(
            key = "OPENAI_API_KEY",
            localValue = "sk-current",
            localNote = "local note",
            localGroup = "Local Group",
            backupValue = "sk-stale",
            backupNote = "backup note",
            backupGroup = "Backup Group",
        )
        assertEquals("a live note must not be overwritten", null, kept.note)
        assertEquals("a live group must not be overwritten", null, kept.group)
        assertEquals(
            "env var \"OPENAI_API_KEY\": local value kept (backup differs) — " +
                "replace it manually if the backup is right",
            kept.report,
        )
    }

    /**
     * The fix must not regress the create path (a key that does NOT exist is
     * still added, empty value included, with its "re-enter it" line) and must
     * not come back as a wholesale skip. Source shape, because the create path
     * runs inside `ConfigBackup.import`, which needs Android repositories.
     */
    @Test
    fun `stage four routes existing keys through the planner and keeps the create path`() {
        val body = stageFourBody()
        assertStageFourShape(body)
        // Negative control: the pre-fix line must be rejected by the probe.
        assertStageFourRejects(body.replace("planEnvVarRestore(", "isDuplicateKey("))
        assertStageFourRejects(body.replace("envVarRepo.add(key, value, note, group)", "Unit"))
    }

    private fun stageFourBody(): String {
        val src = readRepoFile(configBackupRel)
        val start = src.indexOf("// -- Stage 4: environment variables")
        assertTrue("Stage 4 marker lost in $configBackupRel", start >= 0)
        val end = src.indexOf("// -- Stage 5:", start)
        assertTrue("Stage 5 must follow Stage 4 (probe anchor lost)", end > start)
        return codeOnly(src.substring(start, end))
    }

    private fun assertStageFourShape(body: String) {
        assertTrue(
            "an existing key must go through planEnvVarRestore (an empty local value " +
                "has to take the backup's value — the reinstall deadlock)",
            body.contains("planEnvVarRestore("),
        )
        assertFalse(
            "the wholesale skip is back: every restore onto an install that already " +
                "has the key would drop the value again",
            body.contains("already exists, left as-is"),
        )
        assertTrue(
            "a key that does not exist yet must still be created (empty value included)",
            body.contains("envVarRepo.add(key, value, note, group)"),
        )
        assertTrue(
            "a secret-free restore must keep telling the user to re-enter the value",
            body.contains("restored without value — re-enter it"),
        )
    }

    private fun assertStageFourRejects(mutated: String) {
        assertStageFourShape(stageFourBody()) // the probe accepts the real source
        var rejected = false
        try {
            assertStageFourShape(mutated)
        } catch (_: AssertionError) {
            rejected = true
        }
        assertTrue("negative control: the Stage 4 probe still accepts a mutated body", rejected)
    }

    // ── negative controls ───────────────────────────────────────────────

    @Test
    fun `probe rejects a registration that is not there`() {
        val body = registerBackgroundBody()
        assertRejects(body, body.replace("path = \"background.overlay\"", "path = \"background.overlayOff\""))
    }

    @Test
    fun `probe rejects a key re-spelled as a literal`() {
        val body = registerBackgroundBody()
        assertRejects(body, body.replace(".KEY_BG_OVERLAY_ENABLED", ".KEY_SOMETHING_ELSE"))
    }

    @Test
    fun `probe rejects a hard-coded default`() {
        val body = registerBackgroundBody()
        assertRejects(body, body.replace(".DEFAULT_BG_OVERLAY_ENABLED", "false"))
    }

    private fun assertRejects(pristine: String, mutated: String) {
        // Accept the real source first: a probe that rejects everything would
        // also "reject" the mutated body and prove nothing.
        assertOverlayRegistered(pristine)
        var rejected = false
        try {
            assertOverlayRegistered(mutated)
        } catch (_: AssertionError) {
            rejected = true
        }
        assertTrue("negative control: the probe still accepts mutated source", rejected)
    }

    private fun codeOnly(body: String): String = body.lines().joinToString("\n") { line ->
        val t = line.trimStart()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
            " ".repeat(line.length)
        } else {
            line
        }
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]. Gradle sets user.dir to the module dir; a sandbox JVM run can
     * start anywhere under the repo — no fixed depth works.
     */
    // ── 4. [FIX-P1A / FINDING-7] env-var export accounting ───────────────
    //
    // W1 (encrypted store unusable → `getValue` returns null) used to make the
    // export emit `{key, note}` — no `value`, no marker, no counter — while the
    // document still said `includesSecrets: true`. A payload that claimed to
    // carry credentials was indistinguishable from one that did, and the loss
    // only surfaced after a device transfer. `buildEnvVarExportEntries` is the
    // real production function (pure Kotlin, no Android); every expected value
    // below is a literal.

    private fun entriesWithValue(json: org.json.JSONArray): Int {
        var n = 0
        for (i in 0 until json.length()) {
            if (json.getJSONObject(i).has("value")) n++
        }
        return n
    }

    @Test
    fun `unreadable secret value is marked per entry and counted`() {
        val out = buildEnvVarExportEntries(
            entries = listOf(
                EnvVarBackupMeta(key = "OPENAI_API_KEY", note = "llm", group = "models"),
                EnvVarBackupMeta(key = "GONE_KEY", note = "was here", group = ""),
            ),
            includeSecrets = true,
            readValue = { key -> if (key == "OPENAI_API_KEY") "sk-live" else null },
        )
        assertEquals(2, out.entries.length())
        val present = out.entries.getJSONObject(0)
        assertEquals("OPENAI_API_KEY", present.getString("key"))
        assertEquals("sk-live", present.getString("value"))
        assertFalse(
            "a readable value must not be marked unreadable",
            present.has(ENV_VALUE_UNREADABLE_KEY),
        )
        val missing = out.entries.getJSONObject(1)
        assertEquals("GONE_KEY", missing.getString("key"))
        assertEquals("was here", missing.getString("note"))
        assertFalse("no value may be fabricated", missing.has("value"))
        assertTrue(
            "a declared-but-unreadable value MUST carry the marker — otherwise the " +
                "payload is indistinguishable from one whose value was never set",
            missing.getBoolean(ENV_VALUE_UNREADABLE_KEY),
        )
        assertEquals(listOf("GONE_KEY"), out.missingSecretValues)
        // The document has to be self-reconcilable: entries without a value
        // minus markers == 0, and the reported list == the marker count.
        assertEquals(out.entries.length() - entriesWithValue(out.entries), out.missingSecretValues.size)
    }

    @Test
    fun `secret-free export carries no marker and reports nothing`() {
        val out = buildEnvVarExportEntries(
            entries = listOf(EnvVarBackupMeta(key = "K", note = "n", group = "g")),
            includeSecrets = false,
            readValue = { error("must not be read when secrets are excluded") },
        )
        val entry = out.entries.getJSONObject(0)
        assertFalse(
            "without secrets the absence of a value is the REQUESTED shape, not a failure",
            entry.has(ENV_VALUE_UNREADABLE_KEY),
        )
        assertFalse(entry.has("value"))
        assertEquals(emptyList<String>(), out.missingSecretValues)
        assertEquals("g", entry.getString("group"))
    }

    @Test
    fun `every value readable means nothing is reported`() {
        val out = buildEnvVarExportEntries(
            entries = listOf(
                EnvVarBackupMeta(key = "A", note = "", group = ""),
                EnvVarBackupMeta(key = "B", note = "", group = ""),
            ),
            includeSecrets = true,
            readValue = { "v-$it" },
        )
        assertEquals(listOf("v-A", "v-B"), (0 until 2).map { out.entries.getJSONObject(it).getString("value") })
        assertEquals(emptyList<String>(), out.missingSecretValues)
        assertEquals(2, entriesWithValue(out.entries))
    }

    /** buildSections' env-var block, from the function head to the skills pass. */
    private fun envVarExportBody(): String {
        val src = readRepoFile(configBackupRel)
        val start = src.indexOf("private suspend fun buildSections(")
        assertTrue("buildSections not found in $configBackupRel", start >= 0)
        val end = src.indexOf("val skills = JSONArray()", start)
        assertTrue("skills section anchor lost — re-point the probe", end > start)
        return src.substring(start, end)
    }

    private fun assertExportAccounting(body: String) {
        val code = codeOnly(body)
        assertTrue(
            "buildSections must build the envVars section through buildEnvVarExportEntries( — " +
                "otherwise the per-entry marker and the missing-value count cannot exist",
            code.contains("buildEnvVarExportEntries("),
        )
        assertTrue(
            "a throwing value read must degrade to 'missing', not abort the whole backup",
            code.contains("runCatching { envVarRepo.getValue(key) }.getOrNull()"),
        )
        assertTrue(
            "the missing values must land in the readFailures channel the export callers surface",
            code.contains("readFailures += missingSecretValues.size"),
        )
        assertFalse(
            "the silent `getValue(...)?.let { put(\"value\", it) }` form is back — a missing " +
                "value would again leave no trace in the payload",
            code.replace(" ", "").contains("envVarRepo.getValue(entry.key)?.let"),
        )
    }

    @Test
    fun `buildSections wires the env-var accounting`() {
        assertExportAccounting(envVarExportBody())
    }

    @Test
    fun `env-var accounting probe has discriminating power`() {
        val body = envVarExportBody()
        assertExportAccounting(body)
        for (mutated in listOf(
            body.replace("buildEnvVarExportEntries(", "buildSomethingElse("),
            body.replace("runCatching { envVarRepo.getValue(key) }.getOrNull()", "envVarRepo.getValue(key)"),
            body.replace("readFailures += missingSecretValues.size", "Unit"),
        )) {
            var rejected = false
            try {
                assertExportAccounting(mutated)
            } catch (_: AssertionError) {
                rejected = true
            }
            assertTrue("negative control: the probe still accepts a mutated body", rejected)
        }
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
