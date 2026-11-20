package com.rikkaminis.app.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** DATA-12 / DATA-14: tests execute the actual pure plan and file target, not
 *  a modeling copy. MirrorStore end-to-end with its Context shell is probed by
 *  the sibling JVM harness against the production source and minimal stubs. */
class MirrorIntegrityTest {
    private val root = Files.createTempDirectory("mirror-integrity-").toFile()
    private val target = FileTarget(root)

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test fun forgedManifestPathsAndPreviousPointsAreRejectedWhole() {
        val bad = listOf(
            "../outside.md", "./local.md", "memory/../../outside.md",
            "memory//double.md", "memory/trailing/", "/etc/passwd",
            "memory\\outside.md", "C:/outside.md", "random/outside.md",
            "memory/\u0000.md", "memory/\r.md", "memory/\t.md",
        )
        for (path in bad) {
            assertNull("unsafe path must null the whole manifest: $path",
                MirrorPlan.decodeManifest("v1\nsynced\t1\nfile\t1\t2\t$path\n"))
        }
        for (point in listOf("..", ".", "../outside", "a/b", "a\\b", "a:b", "")) {
            assertNull(MirrorPlan.decodeManifest("v1\nsynced\t1\nprev\t$point\n"))
        }
    }

    @Test fun legitimateWhitelistedSourcesAndOpaqueEncodedNamesStillDecode() {
        val manifest = MirrorPlan.Manifest(1L, listOf(
            MirrorPlan.Entry("memory/a.md", 1L, 2L),
            MirrorPlan.Entry("skills/x/SKILL.md", 1L, 2L),
            MirrorPlan.Entry("shared/backlog.md", 1L, 2L),
            MirrorPlan.Entry("shared/work/b/reports/r.md", 1L, 2L),
            MirrorPlan.Entry("memory/%2e%2e%2f.md", 1L, 2L),
        ), listOf("20261120-010203", "handmade-copy"))
        assertEquals(manifest, MirrorPlan.decodeManifest(MirrorPlan.encodeManifest(manifest)))
        // `%2f` is a literal four-character filename, never a separator alias.
        assertTrue(target.write("${MirrorStore.MIRROR_REL}/current/memory/%2e%2e%2f.md", byteArrayOf(4)))
        assertFalse(File(root, "memory").exists())
    }

    @Test fun unchangedSourceWithDeletedTargetIsPlannedAgain() {
        val previous = listOf(MirrorPlan.Entry("memory/a.md", 5L, 9L))
        assertEquals(0, MirrorPlan.diff(previous, previous).toWrite.size)
        val damaged = MirrorPlan.diff(previous, previous, setOf("memory/a.md"))
        assertEquals(0, damaged.added.size)
        assertEquals(previous, damaged.changed)
        assertEquals(previous, damaged.toWrite)
        assertEquals(0, damaged.unchanged)
        assertEquals(1, damaged.toWrite.size + damaged.removed.size)
    }

    @Test fun outgoingMirrorRootSymlinkCannotRedefineTheGuardBoundary() {
        val outside = File(root, "outside").apply { mkdirs() }
        val sentinel = File(outside, "keep.md").apply { writeText("OUTSIDE") }
        val mirror = File(root, MirrorStore.MIRROR_REL)
        mirror.parentFile!!.mkdirs()
        Files.createSymbolicLink(mirror.toPath(), outside.toPath())
        // If canonicalizing the boundary itself follows this link, `outside`
        // becomes trusted after the attack. The fixed anchor stays lexical.
        assertNull(target.read("${MirrorStore.MIRROR_REL}/keep.md"))
        assertFalse(target.write("${MirrorStore.MIRROR_REL}/current/memory/x.md", byteArrayOf(1)))
        assertFalse(target.delete("${MirrorStore.MIRROR_REL}/keep.md"))
        assertFalse(target.deleteTree("${MirrorStore.MIRROR_REL}/keep.md"))
        assertEquals("OUTSIDE", sentinel.readText())
    }

    @Test fun targetTraversalAndLinkEscapeCannotTouchOutsideSentinel() {
        val outside = File(root, "outside").apply { mkdirs() }
        val sentinel = File(outside, "keep.md").apply { writeText("NEVER-READ-OR-DELETE") }
        // Five segments leave Download/RikkaMinis/backup/mirror/current and
        // land exactly at <volume>/outside/keep.md (the actual sentinel).
        for (path in listOf("../../../../../outside/keep.md", "/etc/passwd")) {
            val escaped = if (path.startsWith('/')) path else "${MirrorStore.CURRENT_REL}/$path"
            assertNull(target.read(escaped))
            assertFalse(target.exists(escaped))
            assertFalse(target.write(escaped, "ATTACK".toByteArray()))
            assertFalse(target.delete(escaped))
            assertFalse(target.deleteTree(escaped))
            assertEquals(emptyList<String>(), target.list(escaped))
            assertEquals("NEVER-READ-OR-DELETE", sentinel.readText())
        }
        val current = File(root, MirrorStore.CURRENT_REL)
        current.parentFile!!.mkdirs()
        Files.createSymbolicLink(current.toPath(), outside.toPath())
        val link = "${MirrorStore.CURRENT_REL}/keep.md"
        assertNull(target.read(link))
        assertFalse(target.exists(link))
        assertFalse(target.write(link, "ATTACK".toByteArray()))
        assertFalse(target.delete(link))
        assertFalse(target.deleteTree(link))
        assertEquals("NEVER-READ-OR-DELETE", sentinel.readText())
        assertTrue(sentinel.isFile)
        // Auto remains a legitimate sibling of mirror; it must not inherit a
        // mirror-only path guard that silently disables daily backup copies.
        val auto = "${MirrorStore.AUTO_REL}/rikkaminis-auto-20261120-010203.json"
        assertTrue(target.write(auto, "ok".toByteArray()))
        assertEquals("ok", target.read(auto)!!.toString(Charsets.UTF_8))
    }
}
