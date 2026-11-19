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
 * [R2 F-04 / F-05] The sandbox browser's BOUNDARY arms, pinned on the JVM with
 * real filesystem fixtures.
 *
 * The defect family: every guard in `FileBrowserViewModel` asked about a
 * STRING — the logical path built from the browser's root + breadcrumb + name —
 * while the delete/read went to the path the filesystem resolved. Two shapes
 * fell out of that:
 *
 *  - **F-04**: `ln -s <readonly>/memory <workspace>/m`, enter `m`, delete a file.
 *    The listing shows the REAL memory files, the logical path still reads
 *    `/var/minis/workspace/m/SOUL.md`, so every read-only rule says "writable".
 *  - **F-05**: a link to `/data/user/0/<pkg>/shared_prefs` (or the relative
 *    `../../../../databases` spelling) was browsable / previewable / deletable,
 *    because the resolver fell back to `File(rawTarget)` when the guest mapper
 *    missed.
 *
 * The NEGATIVE CONTROLS are half of this class: a link into a writable
 * workspace must still be listed, entered and deleted. A fix that forbids
 * symlinks outright would satisfy every refusal arm while breaking the feature,
 * and that is not a fix.
 */
class SymlinkBoundaryTest {

    // ── canonical prefix helper ────────────────────────────────────────────

    @Test
    fun `the canonical prefix helper compares whole segments`() {
        withFixture { base, _ws, _ro, _private, _outside ->
            val ws = File(base, "ws")
            val file = File(ws, "a.txt").apply { writeText("a") }
            assertTrue(isCanonicalUnder(file, ws))
            assertTrue(isCanonicalUnder(ws, ws))
            // Sibling whose name starts with the same characters is NOT inside.
            assertFalse(isCanonicalUnder(File(base, "ws-other/x"), ws))
            assertFalse(isCanonicalUnder(base, ws))
        }
    }

    @Test
    fun `an unresolvable path is never under anything`() {
        val ghost = File("/definitely/not/here/x")
        assertNull(canonicalFileOrNull(ghost))
        assertFalse(isCanonicalUnder(ghost, File("/")))
        assertFalse(isUnderAnyRoot(ghost, listOf(File("/"))))
        assertNull(boundedSymlinkTarget(ghost, listOf(File("/"))))
    }

    @Test
    fun `an empty root list allows nothing`() {
        // Fail-closed: "no roots declared" must read as "nothing is proven
        // inside", never as "no restriction".
        withFixture { _base, ws, _ro, _private, _outside ->
            assertFalse(isUnderAnyRoot(File(ws, "a.txt"), emptyList()))
            assertNull(boundedSymlinkTarget(File(ws, "a.txt"), emptyList()))
        }
    }

    // ── F-05: a link out of the sandbox ───────────────────────────────────

    @Test
    fun `a link to the app private dir is not a browseable directory`() {
        withFixture { _base, ws, _ro, privateDir, _outside ->
            val link = File(ws, "p")
            Files.createSymbolicLink(link.toPath(), privateDir.toPath())
            val item = FileItem.from(link, sandboxRoots = listOf(ws))!!
            assertTrue(item.isSymlink)
            assertFalse("a private-dir link must not read as a directory", item.isDirectory)
            assertSame("the entry stays the link — refuse instead of guess", link, item.hostPath)
        }
    }

    @Test
    fun `a relative escape link is not a browseable directory`() {
        withFixture { base, ws, _ro, _private, outside ->
            // `ln -s ../../outside <ws>/db` — the spelling F-05 names.
            val link = File(ws, "db")
            Files.createSymbolicLink(link.toPath(), File("../outside").toPath())
            val item = FileItem.from(link, sandboxRoots = listOf(ws))!!
            assertFalse("a `..` escape must not read as a directory", item.isDirectory)
            assertSame(link, item.hostPath)
            // …while the same link is a perfectly good directory on disk: the
            // refusal is the boundary, not a broken fixture.
            assertTrue(outside.isDirectory)
            assertTrue(base.isDirectory)
        }
    }

    @Test
    fun `entering a link that points outside yields the literal path, not the target`() {
        withFixture { _base, ws, _ro, privateDir, _outside ->
            val link = File(ws, "p")
            Files.createSymbolicLink(link.toPath(), privateDir.toPath())
            val dir = resolveListingHostDir(link, { null }, listOf(ws))
            assertFalse("the private dir's children must not be listed", dir.listFiles()?.isNotEmpty() == true)
            // The link itself is the refused entry, so the answer must not BE
            // the link either (listFiles() would follow it).
            assertFalse("the refused listing must not be the link", dir.canonicalPath == link.canonicalPath)
        }
    }
    @Test
    fun `a guest-absolute link that maps outside the roots is refused`() {
        withFixture { _base, ws, _ro, privateDir, _outside ->
            val link = File(ws, "g")
            Files.createSymbolicLink(link.toPath(), File("/var/minis/workspace/priv").toPath())
            // The mapper answers the private dir for that guest spelling — the
            // bound is what stops it, not the mapper.
            val dir = resolveListingHostDir(link, { privateDir }, listOf(ws))
            assertTrue(
                "the listing must stay inside the allowed root, got ${dir.absolutePath}",
                dir.canonicalPath.startsWith(ws.canonicalPath + "/"),
            )
            assertFalse("nothing may be listed for a refused link", dir.exists())
        }
    }

    // ── negative controls: legitimate links keep working ──────────────────

    @Test
    fun `a link into the writable root is still listed and entered`() {
        withFixture { _base, ws, _ro, _private, _outside ->
            val real = File(ws, "real").apply { mkdirs() }
            File(real, "x.txt").writeText("x")
            val link = File(ws, "ok")
            Files.createSymbolicLink(link.toPath(), real.toPath())
            val item = FileItem.from(link, sandboxRoots = listOf(ws))!!
            assertTrue("a legitimate link must keep its folder affordance", item.isDirectory)
            assertEquals(real.canonicalPath, item.hostPath.canonicalPath)
            val dir = resolveListingHostDir(link, { null }, listOf(ws))
            assertEquals(1, dir.listFiles()?.size)
        }
    }

    @Test
    fun `a guest-absolute link mapped inside the roots still lists`() {
        withFixture { _base, ws, _ro, _private, _outside ->
            val real = File(ws, "mapped").apply { mkdirs() }
            File(real, "y.txt").writeText("y")
            val link = File(ws, "g")
            Files.createSymbolicLink(link.toPath(), File("/var/minis/workspace/mapped").toPath())
            val dir = resolveListingHostDir(link, { real }, listOf(ws))
            assertEquals(real.canonicalPath, dir.canonicalPath)
            assertEquals(1, dir.listFiles()?.size)
        }
    }

    @Test
    fun `a plain directory still resolves through the fast path`() {
        withFixture { _base, ws, _ro, _private, _outside ->
            val plain = File(ws, "plain").apply { mkdirs() }
            File(plain, "z.txt").writeText("z")
            val dir = resolveListingHostDir(plain, { null }, listOf(ws))
            assertEquals(plain.canonicalPath, dir.canonicalPath)
            assertEquals(1, dir.listFiles()?.size)
        }
    }

    // ── F-04: the canonical delete verdict ────────────────────────────────

    @Test
    fun `deleting a file reached through a read-only link is refused`() {
        withFixture { _base, ws, ro, _private, _outside ->
            // The browser lists `ws/m/SOUL.md` (a REAL file under `ro`), so
            // `hostPath` is the memory file even though the logical path says
            // /var/minis/workspace/m/SOUL.md.
            val link = File(ws, "m")
            Files.createSymbolicLink(link.toPath(), ro.toPath())
            val item = FileItem.from(File(link, "SOUL.md"), sandboxRoots = listOf(ws, ro))!!
            val verdict = canonicalDeleteRefusal(
                target = item.hostPath,
                isSymlink = item.isSymlink,
                allowedRoots = listOf(ws, ro),
                readOnlyRoots = listOf(ro),
            )
            assertEquals(CanonicalDeleteRefusal.ReadOnlyZone, verdict)
        }
    }

    @Test
    fun `deleting inside a writable root is allowed`() {
        withFixture { _base, ws, _ro, _private, _outside ->
            val victim = File(ws, "a.txt").apply { writeText("a") }
            val item = FileItem.from(victim, sandboxRoots = listOf(ws))!!
            assertNull(
                "a writable-root delete must stay allowed (negative control)",
                canonicalDeleteRefusal(item.hostPath, item.isSymlink, listOf(ws), emptyList()),
            )
        }
    }

    @Test
    fun `deleting a target outside every root is refused`() {
        withFixture { _base, ws, _ro, privateDir, _outside ->
            val victim = File(privateDir, "secrets.xml").apply { writeText("s") }
            assertEquals(
                CanonicalDeleteRefusal.OutsideSandbox,
                canonicalDeleteRefusal(victim, isSymlink = false, allowedRoots = listOf(ws), readOnlyRoots = emptyList()),
            )
        }
    }

    @Test
    fun `an unresolvable target is refused, not assumed fine`() {
        assertEquals(
            CanonicalDeleteRefusal.OutsideSandbox,
            canonicalDeleteRefusal(File("/definitely/not/here"), false, listOf(File("/")), emptyList()),
        )
        assertEquals(
            CanonicalDeleteRefusal.OutsideSandbox,
            canonicalDeleteRefusal(null, false, listOf(File("/")), emptyList()),
        )
    }

    @Test
    fun `deleting a symlink itself is judged by the directory that holds it`() {
        withFixture { _base, ws, ro, _private, _outside ->
            // Removing the NAME is safe even when the target is read-only: the
            // link is a name inside the workspace, and unlinking it touches no
            // data. Judging it by its target would make dangling/pointing links
            // undeletable and would refuse the one operation that cannot leak.
            val link = File(ws, "m")
            Files.createSymbolicLink(link.toPath(), ro.toPath())
            assertNull(
                canonicalDeleteRefusal(link, isSymlink = true, allowedRoots = listOf(ws), readOnlyRoots = listOf(ro)),
            )
            // …and a link whose OWN directory is outside the roots stays refused.
            val stray = File(ro, "stray")
            Files.createSymbolicLink(stray.toPath(), File(ws, "a.txt").toPath())
            assertEquals(
                CanonicalDeleteRefusal.OutsideSandbox,
                canonicalDeleteRefusal(stray, isSymlink = true, allowedRoots = listOf(ws), readOnlyRoots = emptyList()),
            )
        }
    }

    @Test
    fun `a link pointing inside the same root is refused when the zone is read-only`() {
        withFixture { _base, ws, ro, _private, _outside ->
            // A link INSIDE the read-only zone is still a read-only entry.
            val inner = File(ro, "alias")
            Files.createSymbolicLink(inner.toPath(), File(ro, "SOUL.md").toPath())
            assertEquals(
                CanonicalDeleteRefusal.ReadOnlyZone,
                canonicalDeleteRefusal(inner, isSymlink = true, allowedRoots = listOf(ws, ro), readOnlyRoots = listOf(ro)),
            )
        }
    }

    // ── the unbounded host fallback (the F-05 mechanism itself) ────────────

    @Test
    fun `a mapped target outside the roots is not used, and the raw fallback is bounded too`() {
        withFixture { _base, ws, _ro, privateDir, _outside ->
            val inRoot = File(ws, "ok.txt").apply { writeText("ok") }
            // mapped answer is fine, raw fallback names the private dir.
            val viaMapped = resolveHostSymlinkTarget(
                mapped = inRoot,
                rawTarget = File(privateDir, "databases").absolutePath,
                exists = { it.exists() },
                roots = listOf(ws),
            )
            assertEquals(inRoot.canonicalPath, viaMapped?.canonicalPath)

            // mapped answer is out of bounds ⇒ the RAW arm must not rescue it.
            val escaped = resolveHostSymlinkTarget(
                mapped = File(privateDir, "databases"),
                rawTarget = File(privateDir, "databases").absolutePath,
                exists = { it.exists() },
                roots = listOf(ws),
            )
            assertNull("the raw fallback must not rescue an out-of-bounds target", escaped)
        }
    }

    @Test
    fun `a host-relative link into the root is still resolved`() {
        withFixture { _base, ws, _ro, _private, _outside ->
            val real = File(ws, "real").apply { mkdirs() }
            // The case the fallback exists for: nothing mapped it, the target is
            // a host path that IS inside the browser's root.
            val resolved = resolveHostSymlinkTarget(
                mapped = null,
                rawTarget = real.absolutePath,
                exists = { it.exists() },
                roots = listOf(ws),
            )
            assertEquals(real.canonicalPath, resolved?.canonicalPath)
        }
    }

    @Test
    fun `an unmapped raw target that does not exist resolves to nothing`() {
        assertNull(
            resolveHostSymlinkTarget(
                mapped = null,
                rawTarget = "/definitely/not/here",
                exists = { it.exists() },
                roots = listOf(File("/")),
            ),
        )
        assertNull(resolveHostSymlinkTarget(null, null, { true }, listOf(File("/"))))
        assertNull(resolveHostSymlinkTarget(null, "", { true }, listOf(File("/"))))
    }

    // ── fixture ────────────────────────────────────────────────────────────

    /**
     * `base/` holds four trees: `ws` (the writable browser root), `ro` (the
     * read-only zone, standing in for `minis-global/memory`), `private` (the
     * app data dir stand-in) and `outside` (a sibling a `..` link reaches).
     */
    private fun withFixture(block: (File, File, File, File, File) -> Unit) {
        val base = Files.createTempDirectory("symlink-boundary").toFile()
        try {
            val ws = File(base, "ws").apply { mkdirs() }
            val ro = File(base, "ro").apply { mkdirs() }
            File(ro, "SOUL.md").writeText("soul")
            val privateDir = File(base, "private").apply {
                mkdirs()
                File(this, "databases").apply { mkdirs() }
                File(this, "shared_prefs").apply { mkdirs() }
            }
            File(File(privateDir, "databases"), "minis.db").writeText("db")
            val outside = File(base, "outside").apply {
                mkdirs()
                File(this, "keep.txt").writeText("keep")
            }
            block(base, ws, ro, privateDir, outside)
        } finally {
            base.deleteRecursively()
        }
    }
}
