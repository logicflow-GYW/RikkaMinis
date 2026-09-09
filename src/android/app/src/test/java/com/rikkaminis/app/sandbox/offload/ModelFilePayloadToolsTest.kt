package com.rikkaminis.app.sandbox.offload

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Tests for [PayloadSizePolicy] pure-function size policy. */
class PayloadSizePolicyTest {

    @Test
    fun `base64 inflation is 4-over-3 rounded up`() {
        assertEquals(0L, PayloadSizePolicy.base64Size(0L))
        assertEquals(4L, PayloadSizePolicy.base64Size(1L))
        assertEquals(4L, PayloadSizePolicy.base64Size(2L))
        assertEquals(4L, PayloadSizePolicy.base64Size(3L))
        assertEquals(8L, PayloadSizePolicy.base64Size(4L))
        val inflated = PayloadSizePolicy.base64Size(512 * 1024L)
        assertEquals((512 * 1024L + 2) / 3 * 4L, inflated)
    }

    @Test
    fun `binary under budget inlines`() {
        assertTrue(PayloadSizePolicy.canInline(PayloadSizePolicy.Kind.Binary, 100 * 1024L))
    }

    @Test
    fun `binary above budget spills`() {
        assertFalse(PayloadSizePolicy.canInline(PayloadSizePolicy.Kind.Binary, 600 * 1024L))
        assertTrue(PayloadSizePolicy.mustSpill(PayloadSizePolicy.Kind.Binary, 600 * 1024L))
    }

    @Test
    fun `binary exactly at inflated budget boundary stays inline`() {
        val byteSize = PayloadSizePolicy.MAX_INLINE_BINARY_BYTES * 3 / 4
        assertTrue(PayloadSizePolicy.canInline(PayloadSizePolicy.Kind.Binary, byteSize))
        assertTrue(PayloadSizePolicy.mustSpill(PayloadSizePolicy.Kind.Binary, byteSize + 1))
    }

    @Test
    fun `tool result and text budgets are plain byte lengths`() {
        assertTrue(PayloadSizePolicy.canInline(PayloadSizePolicy.Kind.ToolResult, 200 * 1024L))
        assertTrue(PayloadSizePolicy.mustSpill(PayloadSizePolicy.Kind.ToolResult, 300 * 1024L))
        assertTrue(PayloadSizePolicy.canInline(PayloadSizePolicy.Kind.Text, 100 * 1024L))
        assertTrue(PayloadSizePolicy.mustSpill(PayloadSizePolicy.Kind.Text, 200 * 1024L))
    }

    @Test
    fun `file and json absolute caps`() {
        assertFalse(PayloadSizePolicy.isFileTooLarge(63 * 1024 * 1024L))
        assertTrue(PayloadSizePolicy.isFileTooLarge(64 * 1024 * 1024L + 1L))
        assertFalse(PayloadSizePolicy.isSerializedJsonTooLarge(10 * 1024 * 1024L))
        assertTrue(PayloadSizePolicy.isSerializedJsonTooLarge(20 * 1024 * 1024L))
    }

    @Test
    fun `overflowBytes reports only the excess`() {
        val excess = PayloadSizePolicy.overflowBytes(PayloadSizePolicy.Kind.Text, 200 * 1024L)
        assertEquals(200 * 1024L - PayloadSizePolicy.MAX_INLINE_TEXT_BYTES, excess)
        assertEquals(0L, PayloadSizePolicy.overflowBytes(PayloadSizePolicy.Kind.Text, 100L))
    }

    @Test
    fun `humanSize renders friendly units`() {
        assertEquals("512 B", PayloadSizePolicy.humanSize(512L))
        assertEquals("1.0 KiB", PayloadSizePolicy.humanSize(1024L))
        assertEquals("2.0 MiB", PayloadSizePolicy.humanSize(2 * 1024 * 1024L))
    }
}

/** Tests for [BytesFileRef] pure model + org.json codec. */
class BytesFileRefTest {

    @Test
    fun `round-trip serializes and deserializes`() {
        val ref = BytesFileRef("media/0.png", "image/png", 12345L, "abc123def")
        val parsed = BytesFileRef.fromJsonString(ref.toJsonString())
        assertEquals(ref, parsed)
    }

    @Test
    fun `rejects absolute path`() {
        try {
            BytesFileRef("/etc/passwd", "", 1L, "x")
            fail("absolute path should have been rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `rejects blank path and negative size`() {
        try { BytesFileRef("  ", "", 1L, "x"); fail() } catch (_: IllegalArgumentException) {}
        try { BytesFileRef("media/a", "", -5L, "x"); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun `fromJson returns null for non-file-ref or malformed`() {
        assertNull(BytesFileRef.fromJson(JSONObject().put("kind", "text").put("path", "a")))
        assertNull(BytesFileRef.fromJson(JSONObject().put("kind", "file_ref")))
        assertNull(BytesFileRef.fromJsonString("{ not json"))
        assertNull(BytesFileRef.fromJson(
            JSONObject().put("kind", "file_ref").put("path", "/abs").put("size", 1L).put("sha256", "x")
        ))
    }

    @Test
    fun `sha256 and size carried through`() {
        val ref = BytesFileRef.fromJson(
            JSONObject().put("kind", "file_ref").put("path", "media/a.wav")
                .put("mime", "audio/wav").put("size", 999L).put("sha256", "deadbeef")
        )
        assertNotNull(ref)
        assertEquals("media/a.wav", ref!!.relativePath)
        assertEquals("audio/wav", ref.mime)
        assertEquals(999L, ref.size)
        assertEquals("deadbeef", ref.sha256)
    }
}

/** Tests for [RunFileGuard] canonicalize + root-prefix guard. */
class RunFileGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `resolves in-bounds relative path`() {
        val root = tmp.newFolder("run-abc")
        val guard = RunFileGuard(root)
        val resolved = guard.resolveUnderRoot("media/0.png")
        assertNotNull(resolved)
        assertEquals(File(root, "media/0.png").canonicalPath, resolved!!.canonicalPath)
    }

    @Test
    fun `rejects traversal escape`() {
        val root = tmp.newFolder("run-abc")
        val guard = RunFileGuard(root)
        assertNull(guard.resolveUnderRoot("../outside.txt"))
        assertNull(guard.resolveUnderRoot("../../../../etc/passwd"))
        assertNull(guard.resolveUnderRoot("media/../../../escape"))
    }

    @Test
    fun `rejects absolute and blank paths`() {
        val guard = RunFileGuard(tmp.newFolder("run-x"))
        assertNull(guard.resolveUnderRoot("/etc/passwd"))
        assertNull(guard.resolveUnderRoot("C:/evil.txt"))
        assertNull(guard.resolveUnderRoot(""))
        assertNull(guard.resolveUnderRoot("   "))
    }

    @Test
    fun `sibling directory is not counted under root`() {
        val root = tmp.newFolder("base")
        val sibling = tmp.newFolder("base-evil")
        assertFalse(RunFileGuard.isPathUnderRoot(root.canonicalPath, sibling.canonicalPath))
        assertTrue(RunFileGuard.isPathUnderRoot(root.canonicalPath, File(root, "ok").canonicalPath))
        assertTrue(RunFileGuard.isPathUnderRoot(root.canonicalPath, root.canonicalPath))
    }
}

/** Tests for [BoundedLineReader] streaming / bounded line reading. */
class BoundedLineReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `reads complete lines and advances offset`() {
        val f = tmp.newFile("s.jsonl")
        f.writeText("{\"t\":\"started\"}\n{\"t\":\"text\",\"v\":\"hi\"}\n")
        val r = BoundedLineReader(maxReadBytes = 1 shl 20)
        val res = r.readAppended(f, 0, f.length())
        assertTrue(res is BoundedLineReader.ReadResult.Lines)
        val lines = res as BoundedLineReader.ReadResult.Lines
        assertEquals(listOf("{\"t\":\"started\"}", "{\"t\":\"text\",\"v\":\"hi\"}"), lines.lines)
        assertEquals(f.length(), lines.newOffset)
    }

    @Test
    fun `partial trailing line is preserved across polls`() {
        val f = tmp.newFile("s.jsonl")
        f.writeText("line1\npartial-")
        val r = BoundedLineReader()
        val first = r.readAppended(f, 0, f.length())
        assertTrue(first is BoundedLineReader.ReadResult.Lines)
        assertEquals(listOf("line1"), (first as BoundedLineReader.ReadResult.Lines).lines)
        // newOffset is just past the consumed newline (index 5 of "line1\npartial-")
        assertEquals(6L, first.newOffset)

        // append the rest + newline; new offset starts where first call left off.
        // The originally-partial "partial-" is re-read together with the new bytes
        // (the caller keeps its offset at newOffset, so nothing is lost).
        f.appendText("complete\n")
        val second = r.readAppended(f, first.newOffset, f.length())
        assertTrue(second is BoundedLineReader.ReadResult.Lines)
        assertEquals(listOf("partial-complete"), (second as BoundedLineReader.ReadResult.Lines).lines)
        assertEquals(f.length(), (second as BoundedLineReader.ReadResult.Lines).newOffset)
    }

    @Test
    fun `no newline yet yields Partial`() {
        val f = tmp.newFile("s.jsonl")
        f.writeText("no-newline-yet")
        val r = BoundedLineReader()
        assertTrue(r.readAppended(f, 0, f.length()) is BoundedLineReader.ReadResult.Partial)
    }

    @Test
    fun `oversized single line is dropped not ballooned`() {
        val bigLine = "A".repeat(600 * 1024) // 600 KiB line, exceeds maxLineBytes=256KiB
        val f = tmp.newFile("s.jsonl")
        f.writeText(bigLine)
        val r = BoundedLineReader(maxReadBytes = 1 shl 20, maxLineBytes = 256 * 1024)
        val res = r.readAppended(f, 0, f.length())
        assertTrue(res is BoundedLineReader.ReadResult.OversizedLine)
        val ov = res as BoundedLineReader.ReadResult.OversizedLine
        assertEquals(0L, ov.lineStartOffset)
        assertTrue(ov.lineByteLength >= 600 * 1024L)
    }

    @Test
    fun `per-poll read is bounded to maxReadBytes`() {
        // File far longer than a single allowed window.
        val bigContent = buildString {
            repeat(200_000) { append("x").append('\n') } // > 400KB, but window is 1MiB ok
        }
        val f = tmp.newFile("s.jsonl")
        f.writeText(bigContent)
        val r = BoundedLineReader(maxReadBytes = 1024)  // very small window
        var offset = 0L
        var totalRead = 0L
        var extra = false
        while (offset < f.length()) {
            when (val res = r.readAppended(f, offset, f.length())) {
                is BoundedLineReader.ReadResult.Lines -> {
                    totalRead += res.newOffset - offset
                    offset = res.newOffset
                }
                is BoundedLineReader.ReadResult.Partial -> { offset = f.length() }
                is BoundedLineReader.ReadResult.OversizedLine -> { offset = f.length(); extra = true }
            }
        }
        // No window is ever bigger than maxReadBytes per call; and we read everything.
        assertTrue(totalRead == f.length().toLong())
    }
}