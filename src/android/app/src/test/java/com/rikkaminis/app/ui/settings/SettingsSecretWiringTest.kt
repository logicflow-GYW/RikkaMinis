package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [meta-audit-1028 D4 F-03 / F-04] Two settings screens read a secret asynchronously
 * (Keystore → IO) and then rewrite it on save. Both used to let Save run before the read
 * landed, and both wrote the box's content unconditionally — so a box that was empty
 * because the read had not finished erased the stored secret.
 *
 * The DECISION is unit-tested in `SettingsWriteGatesTest` as a pure function; what is
 * pinned here is the WIRING in the two Compose screens, which cannot be instantiated in a
 * JVM test (they need a real `Context`). Source-shape assertions, literal expectations.
 *
 * The one sentence to keep in mind: an empty box means three different things — still
 * loading, nothing stored, or unreadable — and only "loaded and edited" is an instruction
 * from the user.
 */
class SettingsSecretWiringTest {

    private val envScreen: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/settings/EnvironmentVariablesScreen.kt")
    }
    private val mcpScreen: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/settings/MCPIntegrationsScreen.kt")
    }

    private fun code(src: String): String = withoutComments(src)

    // ── F-03: env-var form ────────────────────────────────────────────────────

    @Test
    fun envFormTracksTheLoadState() {
        assertTrue(
            "the form must track whether the secret box's content came from disk",
            code(envScreen).contains("var secretLoad by remember"),
        )
        assertTrue(
            "the load state must be published by the IO read (both outcomes)",
            code(envScreen).contains("secretLoad = SecretLoadState.LOADED") &&
                code(envScreen).contains("secretLoad = SecretLoadState.UNREADABLE"),
        )
    }

    @Test
    fun envSaveIsGatedOnTheLoadState() {
        assertTrue(
            "canSave must go through canSaveWithSecretField (the key check alone is not " +
                "enough: the value is what gets erased)",
            code(envScreen).contains("canSaveWithSecretField("),
        )
        assertTrue(
            "the Save button must be enabled by that gate",
            code(envScreen).contains("enabled = canSave"),
        )
    }

    @Test
    fun envSaveRefusesWhenTheSecretIsNotLoaded() {
        assertTrue(
            "onClick must fail closed too — a disabled button is not the only path",
            code(envScreen).contains("if (secretLoad != SecretLoadState.LOADED) return@MinisTextButton"),
        )
    }

    /** The read failure must be visible, not just silently block Save. */
    @Test
    fun envShowsWhyTheSaveIsBlocked() {
        assertTrue(
            "an unreadable secret must be explained to the user",
            envScreen.contains("R.string.settings_secret_unreadable"),
        )
    }

    // ── F-04: MCP OAuth secret ────────────────────────────────────────────────

    @Test
    fun mcpTracksLoadAndEditIntent() {
        assertTrue(
            "the sheet must track whether the secret box's content came from disk",
            code(mcpScreen).contains("var oauthSecretLoad by remember"),
        )
        assertTrue(
            "'the user typed' is the only way to express 'change this'",
            code(mcpScreen).contains("oauthSecretEdited = true"),
        )
        assertTrue(
            "both outcomes of the read must be published",
            code(mcpScreen).contains("oauthSecretLoad = SecretLoadState.LOADED") &&
                code(mcpScreen).contains("oauthSecretLoad = SecretLoadState.UNREADABLE"),
        )
    }

    @Test
    fun mcpWriteGoesThroughTheThreeWayDecision() {
        val calls = code(mcpScreen)
        assertTrue(
            "the save path must not collapse 'empty' into 'remove' via ifBlank",
            calls.contains("decideClientSecretWrite("),
        )
        // Two writers: Save and Authorize. Both must ask.
        assertTrue(
            "both the Save and the Authorize handler must ask the decision",
            countOf(calls, "decideClientSecretWrite(") >= 2,
        )
        assertFalse(
            "a LEAVE_UNTOUCHED decision must not write at all",
            Regex("if \\(secretWrite != SecretWrite\\.LEAVE_UNTOUCHED\\)")
                .findAll(calls).count() < 2,
        )
    }

    @Test
    fun mcpBothButtonsWaitForTheRead() {
        // Both writers, judged by their own `enabled` line: `enabled = !oauthBusy` is the
        // pre-fix shape of BOTH, so counting is what tells "one was fixed" from "both".
        assertEquals(
            "the Authorize and Save buttons must both wait for the secret read",
            2,
            countOf(code(mcpScreen), "enabled = !oauthBusy && oauthSecretReady,"),
        )
        assertTrue(
            "oauthSecretReady must mean the read landed (not merely 'not busy')",
            code(mcpScreen).contains(
                "val oauthSecretReady = oauthSecretLoad == SecretLoadState.LOADED",
            ),
        )
    }

    /** The read's binding shape is what `skill_import_offmain_check` arm8 judges. */
    @Test
    fun mcpEffectStillBindsTheEditedServerId() {
        assertTrue(
            "the seed effect must bind the edited server's id (the gate reads it to tell " +
                "reset from seed)",
            code(mcpScreen).contains("val sid = editServer?.id"),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun countOf(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    private fun withoutComments(src: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    val nl = src.indexOf('\n', i)
                    i = if (nl < 0) src.length else nl
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    val end = src.indexOf("*/", i + 2)
                    i = if (end < 0) src.length else end + 2
                }
                c == '"' -> {
                    out.append(c)
                    i++
                    while (i < src.length) {
                        if (src[i] == '\\' && i + 1 < src.length) {
                            out.append(src, i, i + 2)
                            i += 2
                            continue
                        }
                        out.append(src[i])
                        if (src[i] == '"') {
                            i++
                            break
                        }
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(
            System.getProperty("user.dir") ?: error("user.dir unset"),
        )
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
