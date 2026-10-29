package com.rikkaminis.app.ui.sandbox

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R5-F F-F-02] The sandbox browser's symlink NAVIGATION arms, pinned on the
 * JVM with real filesystem fixtures.
 *
 * [SymlinkEntryResolutionTest] covers how a link is DESCRIBED (the row's
 * affordance + the delete decision). This class covers what happens when the
 * user ENTERS it, which is a different question and the one the report's
 * F-F-02 is about:
 *
 *  - `FileItem.from` must carry the resolved target in [FileItem.hostPath]
 *    while [FileItem.file] keeps the LINK — that split is what lets the row
 *    open AND keeps delete unlinking a name instead of wiping a tree
 *    ([R4-V F-11]).
 *  - [resolveListingHostDir] must follow a guest-absolute link at any depth:
 *    `navigateTo` appends the link's *name* to the logical path, so the next
 *    listing asks for `<root>/dirLink/sub` — a path `toRealPath()` cannot
 *    resolve because PRoot stored `/var/minis/…` in the link.
 *
 * Expectations are literals and every fixture is rebuilt per test.
 */
class SymlinkListingNavTest {

    // ── FileItem.from ──────────────────────────────────────────────────────

    @Test
    fun `a directory link carries the mapped target in hostPath`() {
        withFixture { ws, real, dirLink, _broken, _rel ->
            val item = FileItem.from(dirLink, guestMapper(ws))
            assertNotNull(item)
            item!!
            assertEquals(real.absolutePath, item.hostPath.absolutePath)
            assertTrue("the row must keep its folder affordance", item.isDirectory)
            assertEquals(0L, item.size)
        }
    }

    @Test
    fun `a directory link keeps the link as file so delete unlinks the name`() {
        withFixture { ws, _real, dirLink, _broken, _rel ->
            val item = FileItem.from(dirLink, guestMapper(ws))!!
            assertEquals("dirLink", item.name)
            assertSame("delete must see the link, never its target", dirLink, item.file)
            assertTrue(item.isSymlink)
            assertFalse(shouldDeleteRecursively(item.isDirectory, item.isSymlink))
        }
    }

    @Test
    fun `a plain file has file and hostPath equal`() {
        withFixture { ws, _real, _dirLink, _broken, _rel ->
            val plain = File(ws, "plain.txt").apply { writeText("p") }
            val item = FileItem.from(plain)!!
            assertEquals(plain.absolutePath, item.hostPath.absolutePath)
            assertEquals(plain.absolutePath, item.file.absolutePath)
            assertFalse(item.isSymlink)
            // A real directory is still deleted THROUGH; only links are not.
            assertTrue(shouldDeleteRecursively(true, false))
            assertFalse(shouldDeleteRecursively(false, false))
        }
    }

    @Test
    fun `an unmappable link keeps the refuse-instead-of-guess answer`() {
        withFixture { ws, _real, _dirLink, broken, _rel ->
            val item = FileItem.from(broken, guestMapper(ws))
            assertNotNull("a dangling link is still a listable entry", item)
            item!!
            assertEquals(broken.absolutePath, item.hostPath.absolutePath)
            assertFalse("an unknown target must never read as a directory", item.isDirectory)
        }
    }

    @Test
    fun `a relative link resolves against its own parent`() {
        withFixture { ws, real, _dirLink, _broken, rel ->
            val item = FileItem.from(rel, guestMapper(ws))!!
            assertEquals(real.absolutePath, item.hostPath.absolutePath)
            assertTrue(item.isDirectory)
        }
    }

    @Test
    fun `without a mapper every absolute link stays unknown`() {
        withFixture { _ws, _real, dirLink, _broken, _rel ->
            val item = FileItem.from(dirLink)!!
            assertEquals(dirLink.absolutePath, item.hostPath.absolutePath)
            assertFalse(item.isDirectory)
        }
    }

    @Test
    fun `a hand-built item defaults hostPath to file`() {
        withFixture { ws, _real, _dirLink, _broken, _rel ->
            val plain = File(ws, "plain.txt").apply { writeText("p") }
            val item = FileItem(
                file = plain,
                name = "plain.txt",
                isDirectory = false,
                isSymlink = false,
                size = 1L,
            )
            assertEquals(plain.absolutePath, item.hostPath.absolutePath)
        }
    }

    // ── resolveListingHostDir ──────────────────────────────────────────────

    @Test
    fun `entering the link lists the target's contents`() {
        withFixture { ws, real, dirLink, _broken, _rel ->
            val dir = resolveListingHostDir(dirLink, guestMapper(ws))
            assertEquals(real.absolutePath, dir.absolutePath)
            // 2 entries: x.txt + sub — literal, so an empty listing reds here.
            assertEquals(2, dir.listFiles()?.size)
        }
    }

    @Test
    fun `entering a link at depth still lists the target's contents`() {
        withFixture { ws, _real, dirLink, _broken, _rel ->
            val deep = File(dirLink, "sub")
            val dir = resolveListingHostDir(deep, guestMapper(ws))
            assertEquals(File(File(ws, "real"), "sub").absolutePath, dir.absolutePath)
            assertEquals(1, dir.listFiles()?.size)
            assertEquals("y.txt", dir.listFiles()!![0].name)
        }
    }

    @Test
    fun `an unmappable link falls back to the literal path`() {
        withFixture { ws, _real, _dirLink, broken, _rel ->
            val dir = resolveListingHostDir(broken, guestMapper(ws))
            assertEquals(broken.absolutePath, dir.absolutePath)
            assertNull("a dangling link has nothing to list", dir.listFiles())
        }
    }

    @Test
    fun `a plain directory resolves through the fast path`() {
        withFixture { _ws, real, _dirLink, _broken, _rel ->
            val dir = resolveListingHostDir(File(real, "."), guestMapper(real))
            assertEquals(real.canonicalPath, dir.absolutePath)
            assertEquals(2, dir.listFiles()?.size)
        }
    }

    @Test
    fun `a missing plain path is not walked into a neighbour`() {
        withFixture { ws, _real, _dirLink, _broken, _rel ->
            val missing = File(ws, "no-such-dir")
            assertEquals(missing.absolutePath, resolveListingHostDir(missing, guestMapper(ws)).absolutePath)
        }
    }

    // ── fixture ────────────────────────────────────────────────────────────

    /**
     * Stands in for the browser's own `resolveGuestAbsoluteTarget`: a guest
     * path under `/var/minis/workspace` maps onto [ws], and only a mapped AND
     * existing path is an answer (otherwise the VM falls back to the literal
     * host path — which is exactly what made the pre-fix listing empty).
     */
    private fun guestMapper(ws: File): (String) -> File? {
        val prefix = "/var/minis/workspace"
        return { guest ->
            when {
                guest == prefix -> ws
                guest.startsWith("$prefix/") -> File(ws, guest.removePrefix("$prefix/"))
                    .takeIf { it.exists() }
                else -> null
            }
        }
    }

    /**
     * `ws/` is the host dir backing `/var/minis/workspace`, holding:
     * `plain.txt`, `real/{x.txt,sub/y.txt}`, plus three links — `dirLink`
     * (absolute GUEST target, the shape PRoot writes), `brokenLink` (a guest
     * target nothing maps), `relLink` (a host-relative target).
     */
    private fun withFixture(block: (File, File, File, File, File) -> Unit) {
        val ws = Files.createTempDirectory("symlink-listing-nav").toFile()
        try {
            val real = File(ws, "real").apply { mkdirs() }
            File(real, "x.txt").writeText("hello")
            File(real, "sub").apply { mkdirs() }
            File(File(real, "sub"), "y.txt").writeText("y")
            val dirLink = File(ws, "dirLink")
            Files.createSymbolicLink(dirLink.toPath(), File("/var/minis/workspace/real").toPath())
            val broken = File(ws, "brokenLink")
            Files.createSymbolicLink(broken.toPath(), File("/var/minis/nowhere").toPath())
            val rel = File(ws, "relLink")
            Files.createSymbolicLink(rel.toPath(), File("real").toPath())
            block(ws, real, dirLink, broken, rel)
        } finally {
            ws.deleteRecursively()
        }
    }
}
