package com.rikkaminis.app.ui.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/r3-data-1119 F-16] Turning a custom mirror OFF must actually restore
 * the official source.
 *
 * The defect: `restoreOfficial` only handled the `.bak` path. A fresh Alpine
 * rootfs ships no `root/.npmrc`, so `applyMirror`'s own backup guard
 * (`if (!bakFile.exists() && configFile.exists())`) had nothing to copy and
 * then wrote a custom `root/.npmrc`; turning the switch off found no `.bak`
 * and took the "skipping restore" early return. The UI said "official" while
 * every `npm`/`npx` in the sandbox kept using the custom registry — forever,
 * with no way back short of hand-editing the file.
 *
 * Expected values are hard-coded literals (the official host strings are
 * spelled out), so an edit to the catalogue flips the test instead of
 * silently re-baselining it.
 */
class MirrorRestorePlanTest {

    // ── the decision ─────────────────────────────────────────────────────

    @Test
    fun `a backup wins over everything`() {
        assertEquals(
            MirrorRestoreAction.RESTORE_FROM_BACKUP,
            decideMirrorRestore(bakExists = true, configExists = true),
        )
        assertEquals(
            MirrorRestoreAction.RESTORE_FROM_BACKUP,
            decideMirrorRestore(bakExists = true, configExists = false),
        )
    }

    @Test
    fun `no backup but a config means overwrite, never skip`() {
        // The F-16 shape: fresh rootfs, no .bak, custom file written by us.
        assertEquals(
            MirrorRestoreAction.OVERWRITE_WITH_OFFICIAL,
            decideMirrorRestore(bakExists = false, configExists = true),
        )
    }

    @Test
    fun `neither file means there is nothing to undo`() {
        assertEquals(
            MirrorRestoreAction.NOTHING_TO_DO,
            decideMirrorRestore(bakExists = false, configExists = false),
        )
    }

    // ── the official body ────────────────────────────────────────────────

    @Test
    fun `npm official body points at the public registry`() {
        val content = officialConfigContent(MirrorCategory.NPM)

        assertEquals("registry=https://registry.npmjs.org/\n", content)
        // The literal that matters: whatever else changes, this line must not
        // name a mirror host.
        assertTrue(content.contains("registry=https://registry.npmjs.org/"))
        for (mirrorHost in listOf("npmmirror", "aliyun", "tencent", "huaweicloud")) {
            assertFalse(
                "the official body must not name the $mirrorHost mirror",
                content.contains(mirrorHost),
            )
        }
    }

    @Test
    fun `alpine official body resolves main and community from the CDN`() {
        val content = officialConfigContent(MirrorCategory.ALPINE)

        assertEquals(
            "https://dl-cdn.alpinelinux.org/alpine/v3.21/main\n" +
                "https://dl-cdn.alpinelinux.org/alpine/v3.21/community\n",
            content,
        )
    }

    @Test
    fun `pip official body keeps the shape applyMirror writes`() {
        val content = officialConfigContent(MirrorCategory.PIP)

        assertEquals(
            "[global]\nbreak-system-packages = true\n" +
                "index-url = https://pypi.org/simple/\ntrusted-host = pypi.org\n",
            content,
        )
        // Same three keys as the apply path, so turning the mirror off cannot
        // leave a half-customised pip.conf behind.
        assertTrue(content.startsWith("[global]"))
        assertTrue(content.contains("index-url = "))
        assertTrue(content.contains("trusted-host = "))
    }

    @Test
    fun `every category resolves to a non-empty official body`() {
        for (category in MirrorCategory.entries) {
            val content = officialConfigContent(category)
            assertTrue(
                "official body for $category must not be empty — the catalogue " +
                    "lost its isOfficial entry",
                content.isNotEmpty(),
            )
            assertFalse(
                "official body for $category must not name a mirror host",
                content.contains("tuna") || content.contains("ustc") ||
                    content.contains("npmmirror"),
            )
        }
    }

    @Test
    fun `the official entry is the one the catalogue flags`() {
        // The two directions (apply a custom mirror / restore the official
        // one) must read the same catalogue, so this pins that each category
        // HAS an official entry to read.
        for (category in MirrorCategory.entries) {
            val official = MirrorCatalog.mirrors(category).filter { it.isOfficial }
            assertEquals(
                "exactly one official entry for $category",
                1,
                official.size,
            )
        }
    }
}
