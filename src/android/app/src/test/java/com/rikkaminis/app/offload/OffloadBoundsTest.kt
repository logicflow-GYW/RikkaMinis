package com.rikkaminis.app.offload

import com.rikkaminis.app.sandbox.offload.PayloadSizePolicy
import com.rikkaminis.app.sandbox.offload.TarReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * FULLSCAN-1119 C2 (offload) — the six findings' bounds, each pinned by a
 * property that would be false if the bound were removed.
 *
 * Every expected value is a literal written from the contract, never derived
 * from the implementation: a test that recomputes the implementation's own
 * arithmetic passes even when both are wrong.
 */
class OffloadBoundsTest {

    @org.junit.Rule
    @JvmField
    val tmp = TemporaryFolder()

    // ── F-03: ShizukuBackend.runProcess capture ceiling ──────────────────

    private fun capture(cap: Int, chunks: List<String>): BoundedStreamCapture {
        val c = BoundedStreamCapture("stdout", cap)
        for (s in chunks) {
            val arr = s.toCharArray()
            c.append(arr, arr.size)
        }
        c.finish()
        return c
    }

    @Test
    fun `small output is captured byte-for-byte, unterminated last line kept`() {
        val c = capture(1024, listOf("line1\nline2\nno-newline-tail"))
        assertEquals("line1\nline2\nno-newline-tail\n", c.text())
        assertFalse(c.truncated)
        assertNull(c.truncationMarker())
    }

    @Test
    fun `CR and CRLF are line terminators exactly like readLine`() {
        // The pre-fix reader was BufferedReader.forEachLine + append('\n').
        val c = capture(1024, listOf("a\r\nb\rc\n"))
        assertEquals("a\nb\nc\n", c.text())
        assertFalse(c.truncated)
    }

    @Test
    fun `capture stops growing at the ceiling and says so`() {
        // 100 lines of 20 bytes = 2000 bytes into a 200-byte budget.
        val line = "0123456789012345678\n"   // 20 bytes
        val c = capture(200, List(100) { line })
        assertTrue("must be flagged as truncated", c.truncated)
        assertTrue("kept ${c.text().length} bytes", c.text().toByteArray().size <= 200)
        assertTrue("must report how much was dropped", c.droppedBytes > 0)
        val marker = c.truncationMarker()
        assertTrue("marker must name the cap", marker != null && marker.contains("200"))
        assertTrue("marker must name the stream", marker!!.contains("stdout"))
    }

    @Test
    fun `one enormous line cannot balloon the buffer`() {
        val c = capture(64, listOf("x".repeat(100_000)))
        assertTrue(c.truncated)
        assertTrue("kept must be bounded", c.text().toByteArray().size <= 64)
    }

    @Test
    fun `CJK is budgeted in bytes not chars`() {
        // 30 chars × 3 bytes = 90 bytes; a char-based budget of 50 would keep
        // all 30 chars (and hold 90 bytes). Byte accounting keeps ≤ 50 bytes.
        val c = capture(50, listOf("中".repeat(30)))
        val kept = c.text().toByteArray(StandardCharsets.UTF_8)
        assertTrue("kept ${kept.size} bytes", kept.size <= 50)
        assertTrue(c.truncated)
        // Never split a multi-byte char: the kept text must decode cleanly.
        assertEquals(kept.size, String(kept, StandardCharsets.UTF_8).toByteArray(StandardCharsets.UTF_8).size)
    }

    @Test
    fun `the byte budget survives several CJK flushes`() {
        // Byte accounting must not drift across flushes: undercounting a line
        // (e.g. charging its CHAR count) lets the next line through and the
        // retained text ends up far over the declared byte ceiling.
        val c = capture(30, List(4) { "中文中文中文中文" + "\n" })   // 8 chars = 24 bytes each
        val kept = c.text().toByteArray(StandardCharsets.UTF_8)
        assertTrue("retained ${kept.size} bytes must stay within the 30-byte cap", kept.size <= 30)
        assertTrue("must be flagged as truncated", c.truncated)
    }

    // ── F-14: pax UTF-8 byte offsets ─────────────────────────────────────

    private fun paxRecord(key: String, value: String): ByteArray {
        val body = "$key=$value\n".toByteArray(StandardCharsets.UTF_8)
        var n = body.size + 2
        while (n.toString().toByteArray().size + 1 + body.size != n) n = n.toString().toByteArray().size + 1 + body.size
        return n.toString().toByteArray() + " ".toByteArray() + body
    }

    private fun tarWithPax(paxPayload: ByteArray, fallbackName: String, data: ByteArray): ByteArray {
        fun header(name: String, size: Long, type: Char): ByteArray {
            val b = ByteArray(512)
            val n = name.toByteArray()
            System.arraycopy(n, 0, b, 0, minOf(n.size, 100))
            System.arraycopy("0000644\u0000".toByteArray(), 0, b, 100, 8)
            System.arraycopy("0000000\u0000".toByteArray(), 0, b, 108, 8)
            System.arraycopy("0000000\u0000".toByteArray(), 0, b, 116, 8)
            System.arraycopy(String.format("%011o\u0000", size).toByteArray(), 0, b, 124, 12)
            System.arraycopy(String.format("%011o\u0000", 1700000000L).toByteArray(), 0, b, 136, 12)
            b[156] = type.code.toByte()
            System.arraycopy("ustar\u0000".toByteArray(), 0, b, 257, 6)
            System.arraycopy("00".toByteArray(), 0, b, 263, 2)
            for (i in 148 until 156) b[i] = ' '.code.toByte()
            var sum = 0
            for (x in b) sum += x.toInt() and 0xFF
            System.arraycopy(String.format("%06o\u0000 ", sum).toByteArray(), 0, b, 148, 8)
            return b
        }
        val out = ByteArrayOutputStream()
        out.write(header("PaxHeaders/p", paxPayload.size.toLong(), 'x'))
        out.write(paxPayload)
        val pad = (512 - paxPayload.size % 512) % 512
        out.write(ByteArray(pad))
        out.write(header(fallbackName, data.size.toLong(), '0'))
        out.write(data)
        out.write(ByteArray((512 - data.size % 512) % 512))
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun firstName(archive: ByteArray): String {
        val r = TarReader(ByteArrayInputStream(archive))
        val m = r.next()!!
        r.skipData()
        return m.name
    }

    @Test
    fun `pax path with a long CJK name survives`() {
        val cjk = "中文文件名".repeat(9) + ".txt"      // 139 bytes → needs pax
        val archive = tarWithPax(paxRecord("path", cjk), "fallback.txt", "hi".toByteArray())
        assertEquals(cjk, firstName(archive))
    }

    @Test
    fun `pax path does not swallow the following record`() {
        // The pre-fix parser advanced by BYTES over a decoded String, so a short
        // CJK path overshot its record and glued "20 mtime=..." onto the name.
        val payload = paxRecord("path", "中文.txt") + paxRecord("mtime", "1700000000")
        val archive = tarWithPax(payload, "fallback.txt", "hi".toByteArray())
        assertEquals("中文.txt", firstName(archive))
    }

    @Test
    fun `pax path with mixed 2 3 4 byte characters survives`() {
        val name = "é中𝄞é中𝄞-mixed.txt"   // 2-byte, 3-byte and 4-byte code points
        val archive = tarWithPax(paxRecord("path", name), "fallback.txt", "hi".toByteArray())
        assertEquals(name, firstName(archive))
    }

    @Test
    fun `plain ASCII pax path still works`() {
        val archive = tarWithPax(paxRecord("path", "plain/ascii.txt"), "fallback.txt", "hi".toByteArray())
        assertEquals("plain/ascii.txt", firstName(archive))
    }

    // ── F-15 / F-22: bounded file reads ─────────────────────────────────

    @Test
    fun `image read under the cap returns every byte`() {
        val f = tmp.newFile("ok.png")
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        f.writeBytes(bytes)
        val r = BoundedFileRead.readBytes(f)
        assertTrue(r is BoundedFileRead.Outcome.Ok)
        assertArrayEqualsBytes(bytes, (r as BoundedFileRead.Outcome.Ok).bytes)
    }

    @Test
    fun `image read at exactly the cap is allowed`() {
        val f = tmp.newFile("edge.png")
        f.writeBytes(ByteArray(1024))
        val r = BoundedFileRead.readBytes(f, capBytes = 1024)
        assertTrue("exactly-at-cap must pass", r is BoundedFileRead.Outcome.Ok)
    }

    @Test
    fun `image read one byte over the cap fails loudly instead of truncating`() {
        val f = tmp.newFile("big.png")
        f.writeBytes(ByteArray(1025))
        val r = BoundedFileRead.readBytes(f, capBytes = 1024)
        assertTrue(r is BoundedFileRead.Outcome.TooLarge)
        val t = r as BoundedFileRead.Outcome.TooLarge
        assertEquals(1025L, t.actualBytes)
        assertEquals(1024L, t.capBytes)
        assertTrue(BoundedFileRead.tooLargeMessage(t.actualBytes, t.capBytes).contains("too large"))
    }

    @Test
    fun `the default cap is the protocol single-file ceiling`() {
        // Not a new number: the bound must be PayloadSizePolicy.MAX_FILE_BYTES.
        val f = tmp.newFile("grow.bin")
        f.writeBytes(ByteArray(16))
        assertTrue(BoundedFileRead.readBytes(f) is BoundedFileRead.Outcome.Ok)
        assertEquals(64L * 1024 * 1024, PayloadSizePolicy.MAX_FILE_BYTES)
    }

    @Test
    fun `minis preview refuses oversized files and accepts normal HTML`() {
        assertTrue(MinisPreviewPolicy.fitsPreview(1024))
        assertFalse(MinisPreviewPolicy.fitsPreview(PayloadSizePolicy.MAX_FILE_BYTES + 1))
        assertTrue("exactly at the cap is previewable", MinisPreviewPolicy.fitsPreview(PayloadSizePolicy.MAX_FILE_BYTES))
    }

    @Test
    fun `viewport detection is byte-level, quote-agnostic and case-insensitive`() {
        assertTrue(MinisPreviewPolicy.declaresViewport("""<meta name="viewport" content="width=1">""".toByteArray()))
        assertTrue(MinisPreviewPolicy.declaresViewport("""<META NAME='viewport'>""".toByteArray()))
        assertFalse(MinisPreviewPolicy.declaresViewport("""<meta name="description" content="viewport">""".toByteArray()))
        assertFalse(MinisPreviewPolicy.declaresViewport("<p>没有视口</p>".toByteArray()))
        // A CJK page without a viewport must still be detected as missing one.
        assertFalse(MinisPreviewPolicy.declaresViewport("<html><body>中文页面</body></html>".toByteArray()))
        assertTrue(
            MinisPreviewPolicy.declaresViewport(
                "<html>中文<meta name=\"viewport\" content=\"x\"></html>".toByteArray()
            )
        )
    }

    // ── F-16: popup contract ────────────────────────────────────────────

    @Test
    fun `popup accepted only when a handler attached and a WebView was handed back`() {
        assertEquals(PopupWindowPolicy.Action.ACCEPTED, PopupWindowPolicy.decide(true, true))
        assertEquals(PopupWindowPolicy.Action.DECLINE, PopupWindowPolicy.decide(true, false))
        assertEquals(PopupWindowPolicy.Action.DECLINE, PopupWindowPolicy.decide(false, false))
    }

    @Test
    fun `decline reason distinguishes the two refusal causes`() {
        assertTrue(PopupWindowPolicy.declineReason(true).contains("host"))
        assertTrue(PopupWindowPolicy.declineReason(false).contains("no host handler"))
    }

    // ── F-09: oversize policy ───────────────────────────────────────────

    @Test
    fun `the stream line ceiling admits a legal max-size media frame`() {
        // 512 KiB of image bytes, Base64-inflated, is what a legal media line
        // costs. It must fit under the reader's per-line ceiling — the pre-fix
        // 256 KiB did not, which is exactly why images vanished.
        val legalMediaLine = PayloadSizePolicy.base64Size(512L * 1024)
        assertEquals(699052L, legalMediaLine)   // literal, not re-derived
        val readerCeiling = PayloadSizePolicy.base64Size(PayloadSizePolicy.MAX_INLINE_BINARY_BYTES)
        assertTrue("ceiling $readerCeiling must admit a legal media frame", legalMediaLine <= readerCeiling)
        assertTrue(
            "ceiling must stay strictly below the read window",
            readerCeiling < PayloadSizePolicy.MAX_POLL_READ_BYTES,
        )
    }

    @Test
    fun `oversize before any output fails, after output logs and continues`() {
        assertEquals(StreamOversizePolicy.Action.FAIL_BEFORE_OUTPUT, StreamOversizePolicy.decide(false))
        assertEquals(StreamOversizePolicy.Action.LOG_AND_CONTINUE, StreamOversizePolicy.decide(true))
    }

    @Test
    fun `oversize message names the size, the ceiling and the loss`() {
        val m = StreamOversizePolicy.message(900_000L, 1_048_576, 42L)
        assertTrue(m.contains("900000B"))
        assertTrue(m.contains("1048576B"))
        assertTrue(m.contains("NOT delivered"))
    }

    @Test
    fun `frame summary recognises a media frame and stays bounded`() {
        val head = """{"t":"media","type":"image","mime":"image/png","b64":"AAAA"}""".toByteArray()
        val s = StreamOversizePolicy.summary(head)
        assertTrue("summary must name the frame type", s.contains("\"t\":\"media\""))
        assertTrue("summary must be bounded", s.length <= 48)
        assertEquals("(empty)", StreamOversizePolicy.summary(ByteArray(0)))
    }

    private fun assertArrayEqualsBytes(a: ByteArray, b: ByteArray) {
        assertEquals("byte count", a.size, b.size)
        for (i in a.indices) assertEquals("byte $i", a[i], b[i])
    }
}
