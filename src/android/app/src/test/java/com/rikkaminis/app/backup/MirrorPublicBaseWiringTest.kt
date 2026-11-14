package com.rikkaminis.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pins the one thing that went wrong on device: the writer's base directory and
 * the relative paths it is given must not both carry [MirrorStore.ROOT_REL].
 *
 * Measured 2026-11-14 on the shipped build: the mirror was live and healthy
 * (fresh `previous/` stamps) but writing into
 * `…/RikkaMinis/backup/Download/RikkaMinis/backup/mirror/current/…` — 41 MB
 * one directory too deep — because `openTargetWithKind` passed
 * `FileTarget(publicBase())` while every path from `runLocked` starts with
 * `Download/RikkaMinis/backup/`.
 *
 * The wiring half is a source-text test (this class cannot build a Context);
 * the behaviour half uses [FileTarget] directly, which is the exact join the
 * defect was made of.
 */
class MirrorPublicBaseWiringTest {

    private val repoRoot: File = File(System.getProperty("user.dir"))

    private fun storeSource(): String {
        val f = File(repoRoot, "src/android/app/src/main/java/com/rikkaminis/app/backup/MirrorStore.kt")
        if (!f.isFile) throw IllegalStateException("MirrorStore.kt not found under ${repoRoot.path}")
        return f.readText()
    }

    @Test
    fun thePublicWriterGetsTheVolumeRootAndNotTheAppendedDirectory() {
        val src = storeSource()
        assertTrue(
            "the PUBLIC_DOWNLOADS arm must pass the volume root",
            src.contains("MirrorPlan.TargetKind.PUBLIC_DOWNLOADS -> FileTarget(publicStorageRoot())"),
        )
        assertFalse(
            "publicBase() appends ROOT_REL; passing it as the base doubles the prefix",
            src.contains("MirrorPlan.TargetKind.PUBLIC_DOWNLOADS -> FileTarget(publicBase())"),
        )
    }

    @Test
    fun theMigrationKeepsAddressingTheMirrorRootItself() {
        val src = storeSource()
        // Park site: `val base = publicBase()` then `File(base, LEGACY_TREE)`.
        assertTrue(
            "the v1 tree lives at ROOT_REL/mirror; the migration must keep using publicBase()",
            Regex("""val base = publicBase\(\)""").containsMatchIn(src),
        )
        assertTrue(
            "publicBase() must still append ROOT_REL",
            Regex("""private fun publicBase\(\): File = File\(Environment\.getExternalStorageDirectory\(\), ROOT_REL\)""")
                .containsMatchIn(src),
        )
    }

    @Test
    fun aStorageRelativePathLandsUnderTheRootExactlyOnce() {
        val rootRel = Regex("""const val ROOT_REL = "([^"]+)"""")
            .find(storeSource())!!.groupValues[1]
        // CURRENT_REL is written as "$MIRROR_REL/current" in the source, so the
        // resolved value is built from ROOT_REL here instead of re-parsed.
        val currentRel = "$rootRel/mirror/current"

        val volume = Files.createTempDirectory("mirror-volume").toFile()
        val target = FileTarget(volume)
        assertTrue(target.write("$currentRel/memory/x.md", byteArrayOf(7)))

        val landed = File(volume, "$currentRel/memory/x.md")
        assertTrue("written at the documented path", landed.isFile)
        assertEquals(
            "$rootRel/mirror/current/memory/x.md",
            landed.relativeTo(volume).path,
        )
        assertFalse(
            "no second ROOT_REL copy",
            File(volume, "$rootRel/$currentRel").exists(),
        )
    }
}
