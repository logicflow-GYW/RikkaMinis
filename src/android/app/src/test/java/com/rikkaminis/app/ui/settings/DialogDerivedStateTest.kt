package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.data.repository.EnvVarRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [da1034-B4] — deep-audit-1034 B-07 (env-var edit sheet degrades to "new
 * variable" when its target is deleted behind its back) / B-08
 * (`WebDavConfigDialog` freezes four form fields at the first composition's
 * `initial`).
 *
 * Both defects are the same shape: a dialog's derived state is seeded from a
 * live source and then never re-seeded when that source moves (or disappears).
 *
 * Two kinds of pinning, matching what each half actually is:
 *  - BEHAVIOUR: the disappearance decision is a pure function
 *    (`editTargetVanished`), so it is run for real here — expectations are
 *    literal, never derived from the implementation's own constants.
 *  - WIRING: the Compose side (an effect's keys, a `remember` key list) cannot
 *    run off-device, so it is pinned by source-shape assertions, the approach
 *    `SharedCacheExportPruneTest` / `DeepLinkReactiveConsumeTest` use.
 */
class DialogDerivedStateTest {

    private fun entry(id: String) = EnvVarRepository.EnvVarEntry(id = id, key = "KEY_$id")

    // -- behaviour: the disappearance decision, run for real ----------------

    @Test
    fun `a target that is still in the list is not a disappearance`() {
        assertFalse(editTargetVanished(listOf(entry("a"), entry("b")), "b"))
    }

    @Test
    fun `a target removed behind the sheet's back is a disappearance`() {
        assertTrue(
            "the sheet is open for an id the live list no longer resolves; without this the " +
                "form drops to `isEditing == false` and Save writes a NEW entry under the " +
                "key the user believes they are editing",
            editTargetVanished(listOf(entry("a")), "b"),
        )
    }

    @Test
    fun `removing the last entry while its own sheet is open still closes it`() {
        assertTrue(editTargetVanished(emptyList<EnvVarRepository.EnvVarEntry>(), "a"))
    }

    @Test
    fun `add mode is never a disappearance`() {
        assertFalse(
            "`editEntryId == null` is the Add path (showAddSheet); closing on it would " +
                "dismiss a sheet the user just opened",
            editTargetVanished(emptyList<EnvVarRepository.EnvVarEntry>(), null),
        )
        assertFalse(editTargetVanished(listOf(entry("a")), null))
    }

    @Test
    fun `only the id that vanished counts, not a list that merely changed`() {
        assertFalse(
            "an unrelated entry being added/removed must not close an open editor",
            editTargetVanished(listOf(entry("a"), entry("b"), entry("c")), "b"),
        )
    }

    // -- wiring: the WebDav dialog's initial-seeded fields ------------------

    private val backupScreen: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/settings/BackupSettingsScreen.kt")
    }

    @Test
    fun `the webdav dialog re-seeds every field it was seeded from`() {
        val src = codeOnly(backupScreen)
        val dialog = src.substringAfter("private fun WebDavConfigDialog(")
        assertTrue("WebDavConfigDialog not found — was it renamed?", dialog.isNotEmpty())
        for (field in listOf("url", "username", "password", "path")) {
            val m = Regex("""var\s+$field\s+by\s+remember\s*\(([^)]*)\)""").find(dialog)
            assertTrue(
                "`var $field by remember(...)` not found in WebDavConfigDialog — keyless or " +
                    "renamed: `initial` lands asynchronously (first frame null, [deep-audit-1033 " +
                    "I08]), so a keyless seed freezes the box at the first composition's value " +
                    "and Save writes it back over the real config",
                m != null,
            )
            assertEquals(
                "`var $field` must key on the `initial` it was seeded from",
                "initial",
                m!!.groupValues[1].trim(),
            )
        }
    }

    @Test
    fun `the webdav dialog leaves its non-derived flags keyless`() {
        val src = codeOnly(backupScreen)
        val dialog = src.substringAfter("private fun WebDavConfigDialog(")
        for (flag in listOf("testing", "testResult", "confirmClearPassword")) {
            assertTrue(
                "`var $flag by remember {` should stay keyless — it is UI-local state that " +
                    "derives from nothing; keying it on `initial` would reset an in-flight " +
                    "connection test whenever the config reloads",
                Regex("""var\s+$flag\s+by\s+remember\s*\{""").containsMatchIn(dialog),
            )
        }
    }

    // -- helpers -----------------------------------------------------------

    private fun codeOnly(src: String): String = src
        .split("\n")
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(relative)
    }
}
