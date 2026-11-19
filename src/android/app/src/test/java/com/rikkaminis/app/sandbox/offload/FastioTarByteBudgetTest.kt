package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/**
 * [FULLSCAN-1119 R2 F-03] `minis-fastio tar -x` bounded the ENTRY COUNT only.
 *
 * Entry count is not a size bound: a `.tar.gz` holding ONE all-zero 6 GiB
 * member costs one entry, so it validated in full and then filled `/data`
 * until the write failed — and the failure mode there is app-wide (Room and
 * Prefs renames start failing), not "this extract failed".
 *
 * The fixture below is the same shape without the destructive half: a header
 * that DECLARES 6 GiB, followed by a stream that refuses to be read further.
 * A reader that touches the payload at all raises [BombPayloadRead], so
 * "refused from the header" is asserted mechanically instead of by timing.
 *
 * 6 GiB, not 10: a ustar size field is 11 octal digits, i.e. at most
 * 8 GiB - 1. 6 GiB is a representable hostile declaration well above
 * the existing 64 MiB file ceiling reused here.
 */
class FastioTarByteBudgetTest {

    /** Thrown if anything reads past the header block: the pre-fix behaviour. */
    private class BombPayloadRead : IOException("payload was read")

    @Test
    fun `a single member declaring 6 GiB is refused while validating`() {
        val dest = Files.createTempDirectory("f03-validate")
        val out = TarExtractOutcome()

        val safe = tarValidate(bombArchive(), dest, 10_000L, out)

        assertFalse("a 6 GiB single-member archive must be refused", safe)
        assertTrue("budget refusal before payload: ${out.errors}", out.errors.single().contains("byte budget"))
        assertFalse(
            "nothing may be written for a refused archive",
            File(dest.toFile(), "zero.bin").exists(),
        )
    }

    @Test
    fun `entry count alone does not bound an archive`() {
        val dest = Files.createTempDirectory("f03-entries")
        val out = TarExtractOutcome()

        // The pre-fix configuration: a huge entry budget changes nothing,
        // because the bomb spends exactly ONE entry.
        assertFalse(tarValidate(bombArchive(), dest, 50_000_000L, out))
        assertTrue(out.errors.any { it.contains("byte budget") })
    }

    @Test
    fun `the ceiling is inclusive and one byte past it is not`() {
        val dest = Files.createTempDirectory("f03-boundary")
        val payload = ByteArray(3 * 1024 * 1024)

        val atLimit = TarExtractOutcome()
        assertTrue(
            "declared == ceiling must be accepted: ${atLimit.errors}",
            tarValidate(archiveWith("ok.bin", payload), dest, 100L, atLimit, payload.size.toLong()),
        )
        assertEquals(payload.size.toLong(), atLimit.declaredBytes)

        val overLimit = TarExtractOutcome()
        assertFalse(
            "declared == ceiling + 1 must be refused",
            tarValidate(archiveWith("ok.bin", payload), dest, 100L, overLimit, payload.size - 1L),
        )
        assertTrue(overLimit.errors.any { it.contains("byte budget") })
    }

    @Test
    fun `extract refuses the same archive instead of writing it`() {
        val dest = Files.createTempDirectory("f03-extract")
        val out = TarExtractOutcome()

        // No tarValidate first — a second caller must not be able to walk
        // around the ceiling by skipping the validation pass.
        tarExtract(bombArchive(), dest, force = true, maxEntries = 10_000L, out = out)

        assertTrue(
            "extraction must surface the budget refusal: ${out.errors}",
            out.errors.any { it.contains("byte budget") },
        )
        assertFalse("the over-budget member must not land", File(dest.toFile(), "zero.bin").exists())
        assertEquals(0L, out.bytes)
    }

    @Test
    fun `a normal archive inside the ceiling still round-trips`() {
        val dest = Files.createTempDirectory("f03-normal")
        val out = TarExtractOutcome()

        assertTrue(tarValidate(archiveWith("a.txt", "hello".toByteArray()), dest, 100L, out))
        tarExtract(archiveWith("a.txt", "hello".toByteArray()), dest, false, 100L, out)

        assertEquals("hello", File(dest.toFile(), "a.txt").readText())
        assertTrue("a legal archive must report no error: ${out.errors}", out.errors.isEmpty())
        assertEquals(5L, out.bytes)
    }

    @Test
    fun `shared existing file ceiling is enforced by both default passes`() {
        assertEquals(67108864L, PayloadSizePolicy.MAX_FILE_BYTES)
        val source = readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/FastioTar.kt")
        assertEquals(2, Regex("maxBytes: Long = PayloadSizePolicy.MAX_FILE_BYTES").findAll(source).count())
        assertTrue(source.contains("TarReader(source, maxBytes)"))
    }

    @Test fun `cumulative members and skipped types spend bytes`() {
        val dest = Files.createTempDirectory("f03-cumulative")
        val two = header("a", 3) + byteArrayOf(1,2,3) + ByteArray(509) + header("b", 3) + byteArrayOf(4,5,6) + ByteArray(509)
        val out = TarExtractOutcome()
        assertFalse(tarValidate(two.inputStream(), dest, 100, out, 5))
        assertTrue(out.errors.single().contains("byte budget"))
    }

    @Test fun `reader copy and skip both charge actual payload bytes`() {
        for (copy in listOf(false, true)) {
            val reader = TarReader(archiveWith("x", byteArrayOf(1,2,3)), 3)
            reader.next()
            if (copy) reader.copyData(java.io.ByteArrayOutputStream(), 3) else reader.skipData()
            assertEquals(3L, reader.consumedBytes)
        }
    }

    @Test fun `hardlink materialisation spends disk bytes even with zero payload`() {
        val dest = Files.createTempDirectory("f03-hardlink")
        val original = header("a", 3) + byteArrayOf(1,2,3) + ByteArray(509)
        val link = header("b", 0)
        link[156] = '1'.code.toByte()
        link[157] = 'a'.code.toByte()
        for (i in 148 until 156) link[i] = 32
        octal(link, 148, 7, link.sumOf { it.toLong() and 255 })
        link[155] = 32
        val archive = original + link + ByteArray(1024)
        val validation = TarExtractOutcome()
        assertFalse(tarValidate(archive.inputStream(), dest, 100, validation, 5))
        assertTrue(validation.errors.single().contains("byte budget"))
        val extraction = TarExtractOutcome()
        tarExtract(archive.inputStream(), dest, false, 100, extraction, 5)
        assertFalse("second copy would exceed budget", dest.resolve("b").toFile().exists())
        assertEquals(3L, dest.resolve("a").toFile().length())
    }

    @Test fun `metadata and skipped payloads also consume the archive budget`() {
        val dest = Files.createTempDirectory("f03-metadata")
        val archive = header("a", 3) + byteArrayOf(1,2,3) + ByteArray(509)
        val special = header("not-a-file", 3)
        special[156] = '6'.code.toByte()
        for (i in 148 until 156) special[i] = 32
        octal(special,148,7,special.sumOf { it.toLong() and 255 })
        special[155]=32
        val out=TarExtractOutcome()
        assertFalse(tarValidate((archive+special+ByteArray(512)).inputStream(),dest,100,out,5))
        assertTrue(out.errors.single().contains("byte budget"))
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** Header declaring 6 GiB, then a stream that refuses to be read further. */
    private fun bombArchive(): InputStream {
        val header = header("zero.bin", 6L * 1024 * 1024 * 1024)
        return object : InputStream() {
            private var pos = 0
            override fun read(): Int {
                if (pos >= header.size) throw BombPayloadRead()
                return header[pos++].toInt() and 0xFF
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= header.size) throw BombPayloadRead()
                val n = minOf(len, header.size - pos)
                System.arraycopy(header, pos, b, off, n)
                pos += n
                return n
            }
        }
    }

    private fun archiveWith(name: String, payload: ByteArray): InputStream =
        (header(name, payload.size.toLong()) + payload + ByteArray(pad(payload.size))).inputStream()

    private fun pad(size: Int): Int = (512 - (size % 512)) % 512

    /** Independent ustar writer — never the engine under test. */
    private fun header(name: String, size: Long): ByteArray {
        val block = ByteArray(512)
        val bytes = name.toByteArray()
        System.arraycopy(bytes, 0, block, 0, bytes.size)
        octal(block, 100, 8, 0b110_100_100L)
        octal(block, 108, 8, 0L)
        octal(block, 116, 8, 0L)
        octal(block, 124, 12, size)
        octal(block, 136, 12, 0L)
        for (i in 148 until 156) block[i] = ' '.code.toByte()
        block[156] = '0'.code.toByte()
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
        octal(block, 148, 7, sum)
        block[155] = ' '.code.toByte()
        return block
    }

    private fun octal(block: ByteArray, offset: Int, length: Int, value: Long) {
        val text = java.lang.Long.toOctalString(value)
        // The field holds `length - 1` digits plus a NUL. Writing more silently
        // truncates the LEADING digits, which is how a "10 GiB bomb" fixture
        // turns into a 1.25 GiB member and measures nothing.
        require(text.length <= length - 1) { "$value does not fit ${length - 1} octal digits" }
        val padded = text.padStart(length - 1, '0')
        for (i in 0 until length - 1) block[offset + i] = padded[i].code.toByte()
        block[offset + length - 1] = 0
    }

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val file = File(dir, relative)
            if (file.isFile) return file.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(relative)
    }
}