package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * `minis-fastio tar` engine ([T-minis-fastio] phase 3).
 *
 * These tests own the FORMAT half of the evidence: ustar field layout, name
 * splitting, long-name fallback, checksum, pax/GNU record handling, and the
 * tar-slip rule. The other half — that the guest's real `busybox tar 1.37`
 * accepts what this writes and vice versa — cannot live here (it needs the
 * sandbox's tar) and is covered by the interop probe
 * (`shared/work/fastio-p3/jvm/run_probe3.sh`), which drives the same engine
 * through the real handler with `tar` as the counterparty. A reader/writer pair
 * that only agrees with itself proves nothing about a container format, which
 * is why both halves exist.
 *
 * Expected values are hard literals, and the hostile archives are built by a
 * SECOND, independent implementation ([craftHeader]) rather than by the writer
 * under test — an archive the writer mangles would otherwise still be checked
 * against the writer's own idea of the format.
 */
class FastioTarTest {

    // ── round trip ──────────────────────────────────────────────────────────

    @Test
    fun `create and extract round-trips content, symlinks and counts`() {
        val root = Files.createTempDirectory("fastio-tar")
        val tree = Files.createDirectory(root.resolve("tree"))
        Files.write(tree.resolve("a.txt"), "12345".toByteArray())
        val sub = Files.createDirectory(tree.resolve("sub"))
        Files.write(sub.resolve("c.log"), "cc".toByteArray())
        Files.createDirectory(tree.resolve("empty"))
        Files.createSymbolicLink(tree.resolve("link.txt"), Paths.get("a.txt"))
        val dest = root.resolve("out")

        val archive = ByteArrayOutputStream()
        val created = TarCreateOutcome()
        tarCreate(archive, listOf(tree to tree.toString()), 1000L, created)

        assertEquals(2L, created.files)
        assertEquals(3L, created.dirs)
        assertEquals(1L, created.symlinks)
        assertEquals(7L, created.bytes)
        assertEquals(0L, created.skippedSpecial)
        assertTrue(created.errors.isEmpty())

        val bytes = archive.toByteArray()
        val validation = TarExtractOutcome()
        val safe = tarValidate(ByteArrayInputStream(bytes), dest, 1000L, validation)
        assertTrue("engine's own archive must validate: ${validation.errors}", safe)

        // The stored name is the guest path minus its leading '/', so the tree
        // lands under dest/<that path>, not under dest/<basename>.
        val extractedAt = dest.resolve(tree.toString().trimStart('/'))
        val extracted = TarExtractOutcome()
        tarExtract(ByteArrayInputStream(bytes), dest, false, 1000L, extracted)

        assertEquals(2L, extracted.files)
        assertEquals(3L, extracted.dirs)
        assertEquals(1L, extracted.symlinks)
        assertEquals(7L, extracted.bytes)
        assertTrue(extracted.errors.isEmpty())

        val treeOut = extractedAt
        assertEquals("12345", Files.readAllBytes(treeOut.resolve("a.txt")).decodeToString())
        assertEquals("cc", Files.readAllBytes(treeOut.resolve("sub/c.log")).decodeToString())
        assertTrue(Files.isSymbolicLink(treeOut.resolve("link.txt")))
        assertEquals("a.txt", Files.readSymbolicLink(treeOut.resolve("link.txt")).toString())
        assertTrue(Files.isDirectory(treeOut.resolve("empty")))
    }

    @Test
    fun `gzip round-trips through the same engine`() {
        val root = Files.createTempDirectory("fastio-tar-gz")
        val tree = Files.createDirectory(root.resolve("tree"))
        Files.write(tree.resolve("a.txt"), "hello".toByteArray())
        val dest = root.resolve("out")

        val archive = ByteArrayOutputStream()
        GZIPOutputStream(archive).use { sink ->
            tarCreate(sink, listOf(tree to tree.toString()), 1000L, TarCreateOutcome())
        }
        val bytes = archive.toByteArray()

        // The stored name is the guest path minus its leading '/', so the tree
        // lands under dest/<that path>, not under dest/<basename>.
        val extractedAt = dest.resolve(tree.toString().trimStart('/'))
        val extracted = TarExtractOutcome()
        GZIPInputStream(ByteArrayInputStream(bytes)).use {
            tarExtract(it, dest, false, 1000L, extracted)
        }
        assertEquals(1L, extracted.files)
        assertEquals("hello", Files.readAllBytes(dest.resolve("$tree/a.txt")).decodeToString())
    }

    // ── member naming (the ARGUMENT spelling, not the resolved path) ────────

    @Test
    fun `a member prefix keeps the argument spelling`() {
        // Expected values are GNU/busybox's own rule written out by hand: the
        // stored name is the argument AS SPELLED, minus the two forms both tars
        // normalize away (a leading '/', leading '../'). Naming members after
        // the resolved path instead nests every extraction under
        // dest/var/minis/... — review finding F1.
        assertEquals("src", tarMemberPrefix("src"))
        assertEquals("./src", tarMemberPrefix("./src"))
        assertEquals("src", tarMemberPrefix("src/"))
        assertEquals("src/sub", tarMemberPrefix("src/sub/"))
        assertEquals("var/minis/workspace/src", tarMemberPrefix("/var/minis/workspace/src"))
        assertEquals("x.txt", tarMemberPrefix("x.txt"))
        assertEquals(".", tarMemberPrefix("."))
        assertEquals(".", tarMemberPrefix(".."))
        assertEquals("x", tarMemberPrefix("../x"))
        assertEquals("a/b", tarMemberPrefix("../../a/b"))
        // Not everything with dots is a `..`: these keep their spelling.
        assertEquals("...", tarMemberPrefix("..."))
        assertEquals("..x/y", tarMemberPrefix("..x/y"))
        // An INNER `..` is kept, as both real tars keep it.
        assertEquals("a/../b", tarMemberPrefix("a/../b"))
    }

    @Test
    fun `a relative argument produces relative member names, not the resolved path`() {
        val root = Files.createTempDirectory("fastio-tar-arg-name")
        val tree = Files.createDirectory(root.resolve("src"))
        Files.write(tree.resolve("f1.txt"), "F1".toByteArray())
        val sub = Files.createDirectory(tree.resolve("sub"))
        Files.write(sub.resolve("f3.txt"), "F3".toByteArray())

        val archive = ByteArrayOutputStream()
        tarCreate(archive, listOf(tree to tarMemberPrefix("src")), 1000L, TarCreateOutcome())
        val bytes = archive.toByteArray()

        assertEquals(
            listOf("src/", "src/f1.txt", "src/sub/", "src/sub/f3.txt"),
            readMembers(bytes).map { it.name },
        )

        // …so the tree lands at dest/src, not at dest/<some resolved path>.
        val dest = root.resolve("out")
        val extracted = TarExtractOutcome()
        tarExtract(ByteArrayInputStream(bytes), dest, false, 1000L, extracted)
        assertEquals("F1", Files.readAllBytes(dest.resolve("src/f1.txt")).decodeToString())
        assertFalse(Files.exists(dest.resolve("var")))
    }

    // ── name fields ─────────────────────────────────────────────────────────

    @Test
    fun `a name that fits the 100-byte field is stored verbatim`() {
        val unpacked = splitUstarName("tmp/fix/a.txt")
        assertEquals("", unpacked!!.first)
        assertEquals("tmp/fix/a.txt", unpacked.second)
    }

    @Test
    fun `a long name is split across name and prefix, and survives a read-back`() {
        val segment = "d".repeat(60)
        val name = "tmp/$segment/$segment/a.txt"      // 136 bytes: no plain fit
        val unpacked = splitUstarName(name)!!
        assertTrue("prefix must be non-empty", unpacked.first.isNotEmpty())
        // 154 / 99, not 155 / 100: putText keeps the last byte of each field for
        // the NUL terminator, so those are the real ceilings (ISSUE-03).
        assertTrue(unpacked.first.toByteArray().size <= 154)
        assertTrue(unpacked.second.toByteArray().size <= 99)

        val root = Files.createTempDirectory("fastio-tar-long")
        val deep = root.resolve(segment).resolve(segment)
        Files.createDirectories(deep)
        Files.write(deep.resolve("a.txt"), "x".toByteArray())
        val archive = ByteArrayOutputStream()
        tarCreate(archive, listOf(root to root.toString()), 1000L, TarCreateOutcome())

        val guest = root.toString().trimStart('/')
        val members = readMembers(archive.toByteArray())
        assertEquals("$guest/$segment/$segment/a.txt", members.last().name)
        assertEquals('0', members.last().type)
    }

    @Test
    fun `a name too long for the prefix split falls back to a GNU long-name record`() {
        // 200 bytes in ONE segment: too long for the 100-byte name field, and
        // there is no '/' available to split on once the path prefix is used up,
        // so the writer must fall back to a GNU long-name record. (The
        // filesystem's own 255-byte segment limit is why this is 200 and not
        // larger — a longer fixture cannot even be created.)
        val long = "e".repeat(200)
        assertNull(splitUstarName(long))

        val root = Files.createTempDirectory("fastio-tar-gnu")
        val dir = Files.createDirectory(root.resolve(long))
        Files.write(dir.resolve("a.txt"), "y".toByteArray())
        val archive = ByteArrayOutputStream()
        tarCreate(archive, listOf(root to root.toString()), 1000L, TarCreateOutcome())

        val bytes = archive.toByteArray()
        assertTrue(
            "the archive must contain a GNU @LongLink record",
            String(bytes, Charsets.ISO_8859_1).contains("././@LongLink"),
        )
        val guest = root.toString().trimStart('/')
        val members = readMembers(bytes)
        assertEquals("$guest/$long/a.txt", members.last().name)

        // and the engine reads its own long form back
        val dest = root.resolve("out")
        val out = TarExtractOutcome()
        tarExtract(ByteArrayInputStream(bytes), dest, false, 1000L, out)
        assertEquals("y", Files.readAllBytes(dest.resolve("$guest/$long/a.txt")).decodeToString())
    }

    @Test
    fun `splitUstarName gives up on a single segment longer than the name field`() {
        // The ustar ceiling is 154 (prefix) + '/' + 99 (name) — putText keeps the
        // final byte of every field for the NUL terminator, so a single segment
        // can never be split above that and the writer must switch to a GNU
        // record rather than silently truncating the name. (deep-audit-1019
        // ISSUE-03: this test used to assert that a 100-byte segment DOES fit,
        // which is precisely the off-by-one being fixed.)
        assertNull(splitUstarName("f".repeat(101)))
        assertNull(splitUstarName("f".repeat(4000)))
        assertNull(splitUstarName("f".repeat(100)))
        // 99 bytes is the real ceiling: it must still take the short-name path.
        val exact = splitUstarName("f".repeat(99))!!
        assertEquals("", exact.first)
        assertEquals("f".repeat(99), exact.second)
    }

    @Test
    fun `a pax path override wins over the header name`() {
        val pax = paxRecord("path", "real.txt")
        val archive = ByteArrayOutputStream()
        archive.write(craftHeader("PaxHeader", 'x', pax.toByteArray().size.toLong(), 420, ""))
        archive.write(pax.toByteArray())
        archive.write(ByteArray(pad(pax.toByteArray().size.toLong())))
        archive.write(craftHeader("placeholder", '0', 5, 420, ""))
        archive.write("hello".toByteArray())
        archive.write(ByteArray(pad(5)))
        archive.write(ByteArray(1024))

        val dest = Files.createTempDirectory("fastio-tar-pax").resolve("out")
        val out = TarExtractOutcome()
        tarExtract(ByteArrayInputStream(archive.toByteArray()), dest, false, 100L, out)

        assertEquals(1L, out.files)
        assertEquals(1L, out.paxHeaders)
        assertEquals("hello", Files.readAllBytes(dest.resolve("real.txt")).decodeToString())
        assertFalse(Files.exists(dest.resolve("placeholder")))
    }

    // ── special types ───────────────────────────────────────────────────────

    @Test
    fun `device nodes and fifos are skipped and counted, never invented`() {
        val archive = ByteArrayOutputStream()
        archive.write(craftHeader("dev/null", '3', 0, 420, ""))
        archive.write(craftHeader("pipe", '6', 0, 420, ""))
        archive.write(craftHeader("ok.txt", '0', 2, 420, ""))
        archive.write("hi".toByteArray())
        archive.write(ByteArray(pad(2)))
        archive.write(ByteArray(1024))

        val dest = Files.createTempDirectory("fastio-tar-special").resolve("out")
        val out = TarExtractOutcome()
        tarExtract(ByteArrayInputStream(archive.toByteArray()), dest, false, 100L, out)

        assertEquals(2L, out.skippedSpecial)
        assertEquals(1L, out.files)
        assertEquals(listOf("dev/null", "pipe"), out.skippedNames)
        assertFalse(Files.exists(dest.resolve("pipe")))
        assertEquals("hi", Files.readAllBytes(dest.resolve("ok.txt")).decodeToString())
    }

    @Test
    fun `hard links are reported instead of materialised`() {
        val archive = ByteArrayOutputStream()
        archive.write(craftHeader("a.txt", '0', 2, 420, ""))
        archive.write("hi".toByteArray())
        archive.write(ByteArray(pad(2)))
        archive.write(craftHeader("b.txt", '1', 0, 420, "a.txt"))
        archive.write(ByteArray(1024))

        val dest = Files.createTempDirectory("fastio-tar-hard").resolve("out")
        val out = TarExtractOutcome()
        tarExtract(ByteArrayInputStream(archive.toByteArray()), dest, false, 100L, out)

        assertEquals(1L, out.files)
        assertEquals(1L, out.skippedHardlink)
        assertEquals(listOf("b.txt"), out.skippedNames)
        assertEquals("hi", Files.readAllBytes(dest.resolve("a.txt")).decodeToString())
    }

    // ── the archive inside the tree it packs (F8) ───────────────────────────

    @Test
    fun `an archive written inside the packed tree does not contain itself`() {
        // [§6 F8] `tar -cf src/a.tar src`: the file being written is walked
        // like any other child. Archiving it while it grows makes the member's
        // declared size disagree with its payload, which corrupts every member
        // after it. It must be skipped and counted, not packed.
        val root = Files.createTempDirectory("fastio-tar-self")
        val tree = Files.createDirectory(root.resolve("tree"))
        Files.write(tree.resolve("a.txt"), "hello".toByteArray())
        // Stand in for the sink: production opens this file before the walk.
        val archiveFile = Files.createFile(tree.resolve("pack.tar"))

        val archive = ByteArrayOutputStream()
        val created = TarCreateOutcome()
        tarCreate(archive, listOf(tree to tree.toString()), 1000L, created, archiveFile)

        assertEquals(1L, created.skippedSelf)
        assertEquals(listOf("${tarMemberPrefix(tree.toString())}/pack.tar"), created.skippedSelfNames)
        assertTrue(created.errors.isEmpty())
        // a.txt only: the directory header, one file, and NO pack.tar member.
        assertEquals(1L, created.files)

        val dest = root.resolve("out")
        val out = TarExtractOutcome()
        tarExtract(ByteArrayInputStream(archive.toByteArray()), dest, false, 1000L, out)
        val extractedTree = dest.resolve(tree.toString().trimStart('/'))
        assertEquals("hello", Files.readAllBytes(extractedTree.resolve("a.txt")).decodeToString())
        assertFalse(
            "the archive must not archive itself",
            Files.exists(extractedTree.resolve("pack.tar"), LinkOption.NOFOLLOW_LINKS),
        )
    }

    @Test
    fun `an archive outside the packed tree keeps every member and counts no self skips`() {
        // The control arm: the same walk with the archive elsewhere must skip
        // exactly nothing — otherwise "skipped_self" would just hide members.
        val root = Files.createTempDirectory("fastio-tar-self-outside")
        val tree = Files.createDirectory(root.resolve("tree"))
        Files.write(tree.resolve("a.txt"), "hello".toByteArray())
        val archiveFile = Files.createFile(root.resolve("pack.tar"))

        val created = TarCreateOutcome()
        tarCreate(ByteArrayOutputStream(), listOf(tree to tree.toString()), 1000L, created, archiveFile)

        assertEquals(0L, created.skippedSelf)
        assertTrue(created.skippedSelfNames.isEmpty())
        assertEquals(1L, created.files)
        assertEquals(1L, created.dirs)
    }

    // ── tar slip ────────────────────────────────────────────────────────────

    @Test
    fun `the slip rule refuses absolute, climbing and empty names but keeps the legal ones`() {
        val dest = Files.createTempDirectory("fastio-tar-slip")
        fun member(name: String) = TarMember(name, '0', 0, 420, 0, "")

        assertTrue(tarEntryTarget(dest, member("../evil.txt")) is TarRefusal.Slip)
        assertTrue(tarEntryTarget(dest, member("/etc/passwd")) is TarRefusal.Slip)
        assertTrue(tarEntryTarget(dest, member("a/../../evil.txt")) is TarRefusal.Slip)
        assertTrue(tarEntryTarget(dest, member("..")) is TarRefusal.Slip)
        // audit3 F-01: `dest` is the destination ROOT, so `member(name)` —
        // which is a type '0' (regular file) header — must NOT be allowed to
        // name it: writing "root as a regular file" replaces the root.
        assertTrue(tarEntryTarget(dest, member(".")) is TarRefusal.Slip)
        assertTrue(tarEntryTarget(dest, member("./")) is TarRefusal.Slip)
        assertTrue(tarEntryTarget(dest, member("a/..")) is TarRefusal.Slip)
        // audit-1014 B4a: a DIRECTORY entry that names the root IS legal (this
        // used to assert Slip for every type, which is exactly the bug:
        // `tar -cf a.tar .` writes `./` as its first member and our own
        // extractor refused it).
        assertNull(tarEntryTarget(dest, TarMember(".", '5', 0, 493, 0, "")))
        assertNull(tarEntryTarget(dest, member("a/../b.txt")))
        assertNull(tarEntryTarget(dest, member("dir/file.txt")))
        assertNull(tarEntryTarget(dest, member("./dir/file.txt")))
    }

    @Test
    fun `extraction refuses a name that writes through a symlink the archive itself declares`() {
        val archive = ByteArrayOutputStream()
        archive.write(craftHeader("lnk", '2', 0, 511, "/tmp/elsewhere"))
        archive.write(craftHeader("lnk/pwned.txt", '0', 2, 420, ""))
        archive.write("hi".toByteArray())
        archive.write(ByteArray(pad(2)))
        archive.write(ByteArray(1024))

        val root = Files.createTempDirectory("fastio-tar-chain")
        val dest = root.resolve("out")
        val validation = TarExtractOutcome()
        val safe = tarValidate(ByteArrayInputStream(archive.toByteArray()), dest, 100L, validation)

        assertFalse(safe)
        assertTrue(validation.errors.toString(), validation.errors[0].contains("tar_slip"))
        assertTrue(validation.errors[0].contains("declared by this archive"))
        assertFalse("validation must not have created anything", Files.exists(dest))
    }

    @Test
    fun `extraction refuses a name whose parent chain crosses a symlink already on disk`() {
        val root = Files.createTempDirectory("fastio-tar-ondisk")
        val dest = Files.createDirectory(root.resolve("out"))
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.createSymbolicLink(dest.resolve("lnk"), outside)

        val archive = ByteArrayOutputStream()
        archive.write(craftHeader("lnk/pwned.txt", '0', 2, 420, ""))
        archive.write("hi".toByteArray())
        archive.write(ByteArray(pad(2)))
        archive.write(ByteArray(1024))

        val validation = TarExtractOutcome()
        val safe = tarValidate(ByteArrayInputStream(archive.toByteArray()), dest, 100L, validation)

        assertFalse(safe)
        assertTrue(validation.errors.toString(), validation.errors[0].contains("tar_slip"))
        assertFalse(Files.exists(outside.resolve("pwned.txt")))
    }

    // ── audit3 F-01: the extraction ROOT as an attack surface ───────────────

    /**
     * The full attack: a `.` symlink entry replaces the destination directory
     * itself with a link to anywhere on the host, and the member after it
     * writes through that link. Validation must refuse the archive outright,
     * so nothing is written anywhere ("refuse and leave nothing behind").
     */
    @Test
    fun `a root symlink member is refused at validation and writes nothing`() {
        val root = Files.createTempDirectory("fastio-tar-rootslip")
        val dest = Files.createDirectory(root.resolve("out"))
        val outside = root.resolve("outside")

        val archive = ByteArrayOutputStream()
        archive.write(craftHeader(".", '2', 0, 511, outside.toString()))
        archive.write(craftHeader("pwned.txt", '0', 2, 420, ""))
        archive.write("hi".toByteArray())
        archive.write(ByteArray(pad(2)))
        archive.write(ByteArray(1024))

        val validation = TarExtractOutcome()
        val safe = tarValidate(ByteArrayInputStream(archive.toByteArray()), dest, 100L, validation)

        assertFalse(validation.errors.toString(), safe)
        assertTrue(validation.errors.toString(), validation.errors[0].contains("tar_slip"))
        assertTrue(
            validation.errors.toString(),
            validation.errors[0].contains("names the extraction root itself with type '2'"),
        )
        assertTrue("dest must still be a real directory", Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS))
        assertFalse("dest must not have become a symlink", Files.isSymbolicLink(dest))
        assertEquals("validation must not create anything under dest", 0, listingOf(dest).size)
        assertFalse("nothing may land outside the destination", Files.exists(outside))
    }

    /**
     * Second layer: the extraction pass re-checks every name, so a caller that
     * skipped validation (or a future refactor that lets this archive through)
     * still cannot swap the destination root for a symlink under --force.
     * The link's target directory EXISTS here — that is the shape the pre-fix
     * code escaped into (probe: shared/work/audit3-fix/a/jvm/).
     */
    @Test
    fun `--force extraction cannot replace the destination root with a symlink`() {
        val root = Files.createTempDirectory("fastio-tar-rootforce")
        val dest = Files.createDirectory(root.resolve("out"))
        val outside = Files.createDirectory(root.resolve("outside"))

        val archive = ByteArrayOutputStream()
        archive.write(craftHeader(".", '2', 0, 511, outside.toString()))
        archive.write(craftHeader("pwned.txt", '0', 2, 420, ""))
        archive.write("hi".toByteArray())
        archive.write(ByteArray(pad(2)))
        archive.write(ByteArray(1024))

        val out = TarExtractOutcome()
        tarExtract(ByteArrayInputStream(archive.toByteArray()), dest, true, 100L, out)

        assertTrue("extraction must report the refusal: ${out.errors}", out.errors.isNotEmpty())
        assertTrue(out.errors.toString(), out.errors[0].contains("tar_slip"))
        assertTrue("dest must still be a real directory", Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS))
        assertFalse("dest must not have become a symlink", Files.isSymbolicLink(dest))
        assertEquals("the root entry must not have been written at all", 0, listingOf(dest).size)
        assertFalse(
            "the member after the root link must not write through it",
            Files.exists(outside.resolve("pwned.txt")),
        )
    }

    /**
     * Only a DIRECTORY entry may name the root (`tar -cf a.tar .` writes `./`
     * as its first member — that must keep working). Every other type naming
     * the root replaces the root, and is refused.
     */
    @Test
    fun `every non-directory entry that names the root is refused`() {
        val dest = Files.createTempDirectory("fastio-tar-roottype")
        val rootNames = listOf(".", "./", "././", "a/..")
        val refusedTypes = charArrayOf('0', '2', '1', '7', '\u0000')
        for (name in rootNames) {
            for (type in refusedTypes) {
                val refusal = tarEntryTarget(dest, TarMember(name, type, 0L, 420, 0L, ""))
                assertTrue("'$name' type '$type' must be refused", refusal is TarRefusal.Slip)
            }
        }
        // The compat red line: a DIRECTORY entry naming the root is legal.
        assertNull(tarEntryTarget(dest, TarMember(".", '5', 0L, 493, 0L, "")))
        assertNull(tarEntryTarget(dest, TarMember("./", '5', 0L, 493, 0L, "")))
    }


    /**
     * The compat red line, built by the SECOND writer (not [tarCreate]): an
     * archive whose first member is a root DIRECTORY entry — what GNU and
     * busybox `tar -cf a.tar .` produce — must still extract.
     */
    @Test
    fun `a crafted archive whose first member is a root directory still extracts`() {
        val root = Files.createTempDirectory("fastio-tar-rootdir")
        val dest = root.resolve("out")
        val archive = ByteArrayOutputStream()
        archive.write(craftHeader("./", '5', 0, 493, ""))
        archive.write(craftHeader("./a.txt", '0', 2, 420, ""))
        archive.write("hi".toByteArray())
        archive.write(ByteArray(pad(2)))
        archive.write(ByteArray(1024))
        val bytes = archive.toByteArray()

        val validation = TarExtractOutcome()
        assertTrue(
            validation.errors.toString(),
            tarValidate(ByteArrayInputStream(bytes), dest, 100L, validation),
        )
        val out = TarExtractOutcome()
        tarExtract(ByteArrayInputStream(bytes), dest, false, 100L, out)
        assertTrue("extract errors: ${out.errors}", out.errors.isEmpty())
        assertEquals(1L, out.dirs)
        assertEquals(1L, out.files)
        assertEquals("hi", Files.readAllBytes(dest.resolve("a.txt")).decodeToString())
    }


    /**
     * Sibling shape of F-01, independent of the root rule: an archive that
     * first creates a directory and then declares a symlink of the SAME name.
     * `--force` used to delete the (empty) directory and leave a link in its
     * place, silently, reported as success. The '2' branch now mirrors the
     * regular-file branch and refuses to replace a directory at all.
     */
    @Test
    fun `--force cannot replace a directory member with a symlink of the same name`() {
        val root = Files.createTempDirectory("fastio-tar-dirlink")
        val dest = Files.createDirectory(root.resolve("out"))
        val archive = ByteArrayOutputStream()
        archive.write(craftHeader("sub/", '5', 0, 493, ""))
        archive.write(craftHeader("sub", '2', 0, 511, "/tmp/fastio-f01-target"))
        archive.write(ByteArray(1024))


        val out = TarExtractOutcome()
        tarExtract(ByteArrayInputStream(archive.toByteArray()), dest, true, 100L, out)

        assertEquals(
            listOf("sub: target is a directory; refusing to replace it with a symlink"),
            out.errors,
        )
        assertEquals(1L, out.dirs)
        assertEquals(0L, out.symlinks)
        assertTrue(Files.isDirectory(dest.resolve("sub"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.isSymbolicLink(dest.resolve("sub")))
    }


    /**
     * F-01 rule 5, on-disk half: a destination that is ALREADY a symlink is a
     * precondition already met — no member may be written through it.
     */
    @Test
    fun `a destination root that is already a symlink is refused`() {
        val root = Files.createTempDirectory("fastio-tar-linkroot")
        val real = Files.createDirectory(root.resolve("real"))
        val dest = Files.createSymbolicLink(root.resolve("out"), real)
        val archive = ByteArrayOutputStream()
        archive.write(craftHeader("a.txt", '0', 2, 420, ""))
        archive.write("hi".toByteArray())
        archive.write(ByteArray(pad(2)))
        archive.write(ByteArray(1024))
        val bytes = archive.toByteArray()

        val validation = TarExtractOutcome()
        assertFalse(
            validation.errors.toString(),
            tarValidate(ByteArrayInputStream(bytes), dest, 100L, validation),
        )
        assertTrue(validation.errors.toString(), validation.errors[0].contains("is itself a symlink"))

        val out = TarExtractOutcome()
        tarExtract(ByteArrayInputStream(bytes), dest, false, 100L, out)
        assertTrue(out.errors.toString(), out.errors.isNotEmpty())
        assertFalse("no member may land through the root symlink", Files.exists(real.resolve("a.txt")))
    }

    private fun listingOf(dir: Path): List<Path> {
        val names = ArrayList<Path>()
        Files.newDirectoryStream(dir).use { for (p in it) names.add(p) }
        return names
    }

    @Test
    fun `a hostile entry anywhere leaves the destination untouched - the benign control proves the rule is not blanket`() {
        val good = ByteArrayOutputStream()
        good.write(craftHeader("dir/", '5', 0, 493, ""))
        good.write(craftHeader("dir/hello.txt", '0', 2, 420, ""))
        good.write("hi".toByteArray())
        good.write(ByteArray(pad(2)))
        good.write(ByteArray(1024))

        val bad = ByteArrayOutputStream()
        bad.write(craftHeader("dir/", '5', 0, 493, ""))
        bad.write(craftHeader("dir/hello.txt", '0', 2, 420, ""))
        bad.write("hi".toByteArray())
        bad.write(ByteArray(pad(2)))
        bad.write(craftHeader("../escape.txt", '0', 2, 420, ""))
        bad.write("hi".toByteArray())
        bad.write(ByteArray(pad(2)))
        bad.write(ByteArray(1024))

        val root = Files.createTempDirectory("fastio-tar-atomic")
        val goodDest = root.resolve("good")
        val goodOutcome = TarExtractOutcome()
        assertTrue(tarValidate(ByteArrayInputStream(good.toByteArray()), goodDest, 100L, goodOutcome))
        assertEquals("the benign archive has dir/ and dir/hello.txt", 2, countMembers(good.toByteArray()))

        val badDest = root.resolve("bad")
        val badOutcome = TarExtractOutcome()
        assertFalse(tarValidate(ByteArrayInputStream(bad.toByteArray()), badDest, 100L, badOutcome))
        assertFalse(
            "a refusal must leave nothing behind, not even the good prefix",
            Files.exists(badDest),
        )
    }

    @Test
    fun `a corrupt header checksum is refused`() {
        val block = craftHeader("ok.txt", '0', 2, 420, "")
        block[148] = '9'.code.toByte()
        val archive = ByteArrayOutputStream()
        archive.write(block)
        archive.write("hi".toByteArray())
        archive.write(ByteArray(pad(2)))
        archive.write(ByteArray(1024))

        val dest = Files.createTempDirectory("fastio-tar-sum").resolve("out")
        val out = TarExtractOutcome()
        val safe = tarValidate(ByteArrayInputStream(archive.toByteArray()), dest, 100L, out)
        assertFalse(safe)
        assertTrue(out.errors.toString(), out.errors[0].contains("checksum"))
        assertFalse(Files.exists(dest))
    }

    @Test
    fun `a truncated archive is refused rather than half-extracted`() {
        val full = ByteArrayOutputStream()
        full.write(craftHeader("a.txt", '0', 100, 420, ""))
        full.write(ByteArray(512))
        val truncated = full.toByteArray().copyOf(600)   // data cut mid-entry

        val dest = Files.createTempDirectory("fastio-tar-cut").resolve("out")
        val out = TarExtractOutcome()
        val safe = tarValidate(ByteArrayInputStream(truncated), dest, 100L, out)
        assertFalse(safe)
        assertTrue(out.errors.toString(), out.errors[0].contains("truncated"))
    }

    // ── overwrite policy ────────────────────────────────────────────────────

    @Test
    fun `extraction reuses an existing directory but refuses to replace an existing file without force`() {
        val archive = ByteArrayOutputStream()
        archive.write(craftHeader("dir/", '5', 0, 493, ""))
        archive.write(craftHeader("dir/hello.txt", '0', 5, 420, ""))
        archive.write("fresh".toByteArray())
        archive.write(ByteArray(pad(5)))
        archive.write(ByteArray(1024))
        val bytes = archive.toByteArray()

        val root = Files.createTempDirectory("fastio-tar-overwrite")
        val dest = Files.createDirectory(root.resolve("out"))
        Files.createDirectory(dest.resolve("dir"))
        Files.write(dest.resolve("dir/hello.txt"), "old".toByteArray())

        val refused = TarExtractOutcome()
        tarExtract(ByteArrayInputStream(bytes), dest, false, 100L, refused)
        assertEquals(0L, refused.files)
        assertEquals(1L, refused.dirs)
        assertTrue(refused.errors[0].contains("already_exists"))
        assertEquals("old", Files.readAllBytes(dest.resolve("dir/hello.txt")).decodeToString())

        val forced = TarExtractOutcome()
        tarExtract(ByteArrayInputStream(bytes), dest, true, 100L, forced)
        assertEquals(1L, forced.files)
        assertTrue(forced.errors.isEmpty())
        assertEquals("fresh", Files.readAllBytes(dest.resolve("dir/hello.txt")).decodeToString())
    }

    @Test
    fun `the entry budget stops the walk and says so`() {
        val root = Files.createTempDirectory("fastio-tar-budget")
        val tree = Files.createDirectory(root.resolve("tree"))
        for (i in 0 until 5) Files.write(tree.resolve("f$i.txt"), "x".toByteArray())

        val out = TarCreateOutcome()
        tarCreate(ByteArrayOutputStream(), listOf(tree to tree.toString()), 3L, out)

        assertTrue(out.truncated)
        assertEquals(3L, out.visited)
    }

    // ── dv14-A2: a member that dies mid-copy must not corrupt the archive ───

    @Test
    fun `an entry whose read dies immediately still fills its declared payload`() {
        val root = Files.createTempDirectory("fastio-tar-readfail")
        // A directory: Files.size succeeds (so the header can be written — the
        // declared size is the directory's own), while the read fails at once
        // with "Is a directory". This is the narrow window where a header used
        // to go out with nothing behind it: the old code recorded the error and
        // returned with only the 512-byte header in the stream.
        val notAFile = Files.createDirectory(root.resolve("dir-entry"))
        val declared = Files.size(notAFile)

        val sink = ByteArrayOutputStream()
        val out = TarCreateOutcome()
        writeRegular(sink, "dir-entry", notAFile, out)

        assertEquals(1, out.errors.size)
        assertEquals(0L, out.files)
        assertEquals(0L, out.bytes)
        // header + the whole declared payload (all zeros) + padding, so the
        // next member still starts on a block boundary.
        assertEquals((512 + declared + pad(declared)).toInt(), sink.size())
        val bytes = sink.toByteArray()
        for (i in 512 until (512 + declared).toInt()) {
            assertEquals("byte $i of the declared payload", 0, bytes[i].toInt())
        }
        for (i in (512 + declared).toInt() until bytes.size) {
            assertEquals("pad byte $i", 0, bytes[i].toInt())
        }
    }

    @Test
    fun `a member whose stream shrinks mid-copy is zero-filled to its declared size`() {
        val root = Files.createTempDirectory("fastio-tar-shrank")
        val big = root.resolve("big.bin")
        Files.write(big, ByteArray(1000) { 'A'.code.toByte() })

        val sink = ByteArrayOutputStream()
        val out = TarCreateOutcome()
        // The file on disk really is 1000 bytes (that is what the header
        // declares), but the stream hands over 5 and then reports EOF — a file
        // truncated while the archive was being written.
        writeRegular(sink, "big.bin", big, out, fiveBytesThenEof())

        assertEquals(1, out.errors.size)
        assertTrue(out.errors[0], out.errors[0].contains("file shrank"))
        // The member is not counted as archived, but the stream stays whole.
        assertEquals(0L, out.files)
        assertEquals(0L, out.bytes)

        val bytes = sink.toByteArray()
        // header(512) + 5 real bytes + 995 zero bytes + 24 padding = 1536.
        assertEquals(1536, bytes.size)
        for (i in 0 until 5) assertEquals("byte $i", 'A'.code, bytes[512 + i].toInt())
        for (i in 5 until 1000) assertEquals("byte $i must be the zero fill", 0, bytes[512 + i].toInt())
        for (i in 1000 until 1024) assertEquals("pad byte $i", 0, bytes[512 + i].toInt())
    }

    @Test
    fun `a member whose stream throws mid-copy stays aligned and the next member extracts`() {
        val root = Files.createTempDirectory("fastio-tar-threw")
        val big = root.resolve("big.bin")
        Files.write(big, ByteArray(1000) { 'A'.code.toByte() })
        val after = root.resolve("after.txt")
        Files.write(after, "tail".toByteArray())

        val sink = ByteArrayOutputStream()
        val out = TarCreateOutcome()
        writeRegular(sink, "big.bin", big, out, fiveBytesThenThrow())
        writeRegular(sink, "after.txt", after, out)
        sink.write(ByteArray(1024)) // the two end blocks a complete archive has

        assertEquals(1, out.errors.size)
        assertTrue(out.errors[0], out.errors[0].contains("disk on fire"))
        assertEquals(1L, out.files) // only after.txt really landed
        assertEquals(4L, out.bytes)

        val bytes = sink.toByteArray()
        // 1536 (padded shrank member) + 512 + 4 + 508 + 1024 (end blocks).
        assertEquals(3584, bytes.size)

        val members = readMembers(bytes)
        assertEquals(listOf("big.bin", "after.txt"), members.map { it.name })
        assertEquals(listOf(1000L, 4L), members.map { it.size })

        val dest = root.resolve("out")
        val validation = TarExtractOutcome()
        assertTrue(
            "engine's own archive must still validate: ${validation.errors}",
            tarValidate(ByteArrayInputStream(bytes), dest, 1000L, validation),
        )

        val extracted = TarExtractOutcome()
        tarExtract(ByteArrayInputStream(bytes), dest, false, 1000L, extracted)
        assertTrue("errors: ${extracted.errors}", extracted.errors.isEmpty())
        assertEquals("tail", Files.readAllBytes(dest.resolve("after.txt")).decodeToString())

        val padded = Files.readAllBytes(dest.resolve("big.bin"))
        assertEquals(1000, padded.size)
        for (i in 0 until 5) assertEquals('A'.code.toByte(), padded[i])
        for (i in 5 until 1000) assertEquals("byte $i of the extracted member", 0, padded[i].toInt())
    }

    /** Hand over 5 bytes and then report EOF, like a file truncated mid-read. */
    private fun fiveBytesThenEof(): InputStream = object : InputStream() {
        private var served = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= 5) return -1
            val n = minOf(len, 5 - served)
            b.fill('A'.code.toByte(), off, off + n)
            served += n
            return n
        }
    }

    /** Hand over 5 bytes and then fail with an IOException, like a dying disk. */
    private fun fiveBytesThenThrow(): InputStream = object : InputStream() {
        private var served = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= 5) throw java.io.IOException("disk on fire")
            val n = minOf(len, 5 - served)
            b.fill('A'.code.toByte(), off, off + n)
            served += n
            return n
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Read every member of [bytes] with the engine's own reader (names/types only). */
    private fun readMembers(bytes: ByteArray): List<TarMember> {
        val reader = TarReader(ByteArrayInputStream(bytes))
        val members = ArrayList<TarMember>()
        while (true) {
            val member = reader.next() ?: break
            reader.skipData()
            members.add(member)
        }
        return members
    }

    private fun countMembers(bytes: ByteArray): Int = readMembers(bytes).size

    private fun pad(size: Long): Int = ((512 - (size % 512)) % 512).toInt()

    /** pax record: `"<length> key=value\n"`, where length counts the whole record. */
    private fun paxRecord(key: String, value: String): String {
        val body = "$key=$value\n"
        var length = body.length + 1
        while (true) {
            val candidate = "$length $body"
            if (candidate.length == length) return candidate
            length = candidate.length
        }
    }

    /**
     * A SECOND ustar writer, deliberately independent of [tarCreate]: hostile
     * archives must not be produced by the code under test, or a format bug
     * would be baked into both sides of the assertion.
     */
    private fun craftHeader(name: String, type: Char, size: Long, mode: Int, link: String): ByteArray {
        val block = ByteArray(512)
        val nameBytes = name.toByteArray()
        assertTrue("craft header only writes short names", nameBytes.size <= 100)
        System.arraycopy(nameBytes, 0, block, 0, nameBytes.size)
        putOctal(block, 100, 8, mode.toLong())
        putOctal(block, 108, 8, 0L)
        putOctal(block, 116, 8, 0L)
        putOctal(block, 124, 12, size)
        putOctal(block, 136, 12, 0L)
        for (i in 148 until 156) block[i] = ' '.code.toByte()
        block[156] = type.code.toByte()
        val linkBytes = link.toByteArray()
        System.arraycopy(linkBytes, 0, block, 157, linkBytes.size)
        block[257] = 'u'.code.toByte()
        block[258] = 's'.code.toByte()
        block[259] = 't'.code.toByte()
        block[260] = 'a'.code.toByte()
        block[261] = 'r'.code.toByte()
        block[262] = 0
        block[263] = '0'.code.toByte()
        block[264] = '0'.code.toByte()
        var sum = 0L
        for (b in block) sum += (b.toLong() and 0xFF)
        putOctal(block, 148, 7, sum)
        block[155] = ' '.code.toByte()
        return block
    }

    // ── audit-1014 FIX-3: root entries are LEGAL (B4a) ──────────────────────

    @Test
    fun `root entries dot and dot-slash are not tar-slip`() {
        // B4a: GNU/busybox `tar -cf a.tar .` writes `./` as its first member;
        // collapsing these to null made our own extractor refuse such archives.
        val dest = Paths.get("/tmp/f3test-dest-a")
        assertEquals(emptyList<String>(), tarNormalizeMember("."))
        assertEquals(emptyList<String>(), tarNormalizeMember("./"))
        assertEquals(emptyList<String>(), tarNormalizeMember("././"))
        val member = TarMember("./", '5', 0L, 0b111_101_101, 0L, "")
        assertNull(tarEntryTarget(dest, member))
        // The four tar-slip rules are untouched: every one still refused.
        assertTrue(tarEntryTarget(dest, TarMember("/etc/passwd", '0', 0L, 0, 0L, "")) is TarRefusal.Slip)
        assertTrue(tarEntryTarget(dest, TarMember("../x", '0', 0L, 0, 0L, "")) is TarRefusal.Slip)
        assertTrue(tarEntryTarget(dest, TarMember("a\u0000b", '0', 0L, 0, 0L, "")) is TarRefusal.Slip)
    }

    @Test
    fun `archive with root prefix round-trips through our own reader`() {
        // B4a: tarCreate with memberPrefix "." (what `tar -cf a.tar .` means)
        // must produce an archive our own extractor accepts.
        val root = Files.createTempDirectory("fastio-tar-root")
        val tree = Files.createDirectory(root.resolve("tree"))
        Files.write(tree.resolve("a.txt"), "payload".toByteArray())
        val dest = root.resolve("out")

        val archive = ByteArrayOutputStream()
        tarCreate(archive, listOf(tree to "."), 1000L, TarCreateOutcome())
        val bytes = archive.toByteArray()

        val validation = TarExtractOutcome()
        assertTrue(
            "archive with '.' prefix must validate (B4a): ${validation.errors}",
            tarValidate(ByteArrayInputStream(bytes), dest, 1000L, validation),
        )
        val extracted = TarExtractOutcome()
        tarExtract(ByteArrayInputStream(bytes), dest, false, 1000L, extracted)
        assertTrue("self-extract errors: ${extracted.errors}", extracted.errors.isEmpty())
        // memberPrefix "." stores the tree's CONTENTS at the root (what
        // `tar -cf a.tar .` from inside the tree means), so files land
        // directly under dest, not under dest/tree.
        assertEquals("payload", Files.readAllBytes(dest.resolve("a.txt")).decodeToString())
    }

    // ── audit-1014 FIX-3: long symlink targets are NOT truncated (B4b) ──────

    @Test
    fun `symlink target past the linkname field survives the round trip`() {
        // B4b: a 140-byte target is 41 past the 99-byte linkname ceiling;
        // putText used to truncate it silently. The writer now emits a GNU 'K'
        // record (which the reader has parsed since v1).
        val root = Files.createTempDirectory("fastio-tar-longlink")
        val longTarget = "x".repeat(140)
        Files.createSymbolicLink(root.resolve("ll"), Paths.get(longTarget))
        val dest = root.resolve("out")

        val archive = ByteArrayOutputStream()
        val created = TarCreateOutcome()
        tarCreate(archive, listOf(root to "root"), 1000L, created)
        assertEquals(1L, created.symlinks)

        val extracted = TarExtractOutcome()
        tarExtract(ByteArrayInputStream(archive.toByteArray()), dest, false, 1000L, extracted)
        assertTrue("errors: ${extracted.errors}", extracted.errors.isEmpty())
        assertEquals("full 140-byte target must come back", longTarget,
            Files.readSymbolicLink(dest.resolve("root/ll")).toString())
    }

    private fun putOctal(block: ByteArray, offset: Int, length: Int, value: Long) {
        val text = java.lang.Long.toOctalString(value)
        val width = length - 1
        val padded = if (text.length >= width) text.substring(text.length - width) else "0".repeat(width - text.length) + text
        for (i in padded.indices) block[offset + i] = padded[i].code.toByte()
        block[offset + width] = 0
    }
}
